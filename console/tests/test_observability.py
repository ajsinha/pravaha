"""The console's observability, and the shipped observability files it has a stake in.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

* ``/metrics``: off by default; behind ``metrics.token`` when one is set; counting requests, tokens,
  failures, fallbacks and latency per model and profile at the router, and today's ledger tokens.
* ``logging.format: json``: one JSON object per line carrying the request's correlation id.
* Each request's correlation id, and a ``traceparent`` it arrived with carried onto engine calls.
* The files under ``deploy/observability``: every dashboard is valid JSON with the variables it
  needs; every ``pravaha_console_*`` metric a dashboard or rule reads is one ``/metrics`` publishes;
  the rules file, the Helm chart's copy and the "Metrics and alerts" help topic say the same thing;
  and ``promtool check rules`` passes where promtool is installed.
"""
from __future__ import annotations

import asyncio
import io
import json
import logging
import pathlib
import re
import shutil
import subprocess
import sys

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = CONSOLE_ROOT.parent
OBSERVABILITY = REPO / "deploy" / "observability"
sys.path.insert(0, str(CONSOLE_ROOT))

from test_assist import Console, ask, document, refusal

from core.observability import (
    AssistMetrics,
    JsonFormatter,
    RequestContext,
    authorized,
    configure_logging,
    correlation,
    ledger_today,
)

TOKEN = "scrape-token-for-tests"
UNAVAILABLE = {"error": "unavailable", "message": "the model is down"}


def _console(tmp_path, monkeypatch, *, enabled=True, token=TOKEN, chains=None, models=None):
    # Through the environment, as a deployment would set them (config/application.yaml reads both).
    monkeypatch.setenv("CONSOLE_METRICS_ENABLED", "true" if enabled else "false")
    monkeypatch.setenv("CONSOLE_METRICS_TOKEN", token or "")
    return Console(tmp_path, config=document(models or {"a": [UNAVAILABLE], "b": [refusal("b")]},
                                             chains or {"explain": ["a", "b"], "draft": ["b"]}))


def scrape(client, token: str | None = TOKEN):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    return client.get("/metrics", headers=headers)


def samples(text: str) -> dict[str, float]:
    """``name{labels}`` -> value, for every sample line."""
    found = {}
    for line in text.splitlines():
        if line and not line.startswith("#"):
            key, value = line.rsplit(" ", 1)
            found[key] = float(value)
    return found


def names(text: str) -> set[str]:
    return {re.split(r"[{ ]", line, maxsplit=1)[0] for line in text.splitlines() if line and not line.startswith("#")}


# ============================================================ /metrics

def test_metrics_is_off_unless_configured(tmp_path, monkeypatch):
    console = _console(tmp_path, monkeypatch, enabled=False)
    assert fastapi_testclient.TestClient(console.app).get("/metrics").status_code == 404


def test_a_scrape_without_the_token_is_refused(tmp_path, monkeypatch):
    client = fastapi_testclient.TestClient(_console(tmp_path, monkeypatch).app)
    assert scrape(client, None).status_code == 401
    assert scrape(client, "wrong").status_code == 401
    answer = scrape(client)
    assert answer.status_code == 200
    assert answer.headers["content-type"].startswith("text/plain; version=0.0.4")
    assert "pravaha_console_info{" in answer.text


def test_requests_tokens_fallbacks_failures_and_latency_are_counted_per_model_and_profile(tmp_path, monkeypatch):
    console = _console(tmp_path, monkeypatch)
    admin = console.client()
    answer = ask(admin, "explain-refusal", code="PRV-2050")
    assert answer.json()["result"]["answeredBy"]["modelId"] == "b"
    text = scrape(admin).text
    got = samples(text)
    assert got['pravaha_console_assist_requests_total{model="b",profile="explain",outcome="ok"}'] == 1
    assert got['pravaha_console_assist_fallbacks_total{model="a",profile="explain"}'] == 1
    assert got['pravaha_console_assist_failures_total{model="a",profile="explain",kind="model_unavailable"}'] == 1
    assert got['pravaha_console_assist_latency_seconds_count{model="b",profile="explain"}'] == 1
    assert got['pravaha_console_assist_latency_seconds_bucket{model="b",profile="explain",le="+Inf"}'] == 1
    assert any(k.startswith("pravaha_console_assist_tokens_total{") for k in got)
    assert any(k.startswith("pravaha_console_assist_ledger_tokens_today{") for k in got)
    # Nobody's name is a label: who asked what is the assist log's to keep.
    assert "admin" not in text


def test_every_model_failing_is_a_model_error_outcome(tmp_path, monkeypatch):
    console = _console(tmp_path, monkeypatch, models={"a": [UNAVAILABLE], "b": [UNAVAILABLE]})
    admin = console.client()
    assert ask(admin, "explain-refusal", code="PRV-2050").status_code >= 400
    got = samples(scrape(admin).text)
    assert got['pravaha_console_assist_requests_total{model="b",profile="explain",outcome="model_error"}'] == 1
    assert got['pravaha_console_assist_fallbacks_total{model="a",profile="explain"}'] == 1


def test_the_text_format_escapes_what_a_label_could_carry():
    metrics = AssistMetrics()

    class Routed:
        model_id = 'odd"model\\with\nnewline'
        usage = None
        attempts = ()

    metrics.answered("explain", Routed(), 0.3)
    text = metrics.render(version="0.2.1")
    assert 'model="odd\\"model\\\\with\\nnewline"' in text
    assert ledger_today({"days": {"2026-09-28": {"ann": {"a": 5, "b": "x"}, "bob": {"a": 2}}}}, "2026-09-28") == {"a": 7}
    assert authorized("Bearer t", "t") and not authorized("Bearer u", "t") and authorized(None, "")


# ============================================================ logs and request context

def test_json_log_lines_carry_the_request_correlation_id():
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(JsonFormatter())
    log = logging.getLogger("pravaha.console.test")
    log.addHandler(handler)
    log.setLevel(logging.INFO)
    token = correlation.set("req-42")
    try:
        log.info('a "quoted"\nline')
        try:
            raise RuntimeError("boom")
        except RuntimeError:
            log.exception("failed")
    finally:
        correlation.reset(token)
        log.removeHandler(handler)
    lines = [json.loads(line) for line in stream.getvalue().splitlines()]
    assert lines[0]["message"] == 'a "quoted"\nline'
    assert {"@timestamp", "level", "logger", "thread", "message"} <= set(lines[0])
    assert lines[0]["correlationId"] == "req-42"
    assert "RuntimeError: boom" in lines[1]["stack_trace"]


def test_a_logging_format_that_is_neither_text_nor_json_refuses_the_start():
    with pytest.raises(ValueError, match="text or json"):
        configure_logging("yaml")


def test_each_request_gets_a_correlation_id_and_keeps_its_traceparent_for_engine_calls():
    from pravaha import tracecontext

    seen = {}

    async def app(scope, receive, send):
        seen["cid"] = correlation.get()
        seen["trace"] = tracecontext.headers().get("traceparent")
        await send({"type": "http.response.start", "status": 200, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    sent = []

    async def send(message):
        sent.append(message)

    parent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    scope = {"type": "http", "headers": [(b"x-correlation-id", b"abc-1"), (b"traceparent", parent.encode())]}
    asyncio.run(RequestContext(app)(scope, None, send))
    assert seen == {"cid": "abc-1", "trace": parent}
    assert (b"x-correlation-id", b"abc-1") in sent[0]["headers"]

    scope = {"type": "http", "headers": [(b"x-correlation-id", b"bad\nid")]}
    asyncio.run(RequestContext(app)(scope, None, send))
    assert re.fullmatch(r"[0-9a-f]{16}", seen["cid"])
    assert correlation.get() is None


# ============================================================ the shipped files

def _dashboards() -> dict[str, dict]:
    return {p.name: json.loads(p.read_text(encoding="utf-8"))
            for p in sorted((OBSERVABILITY / "grafana").glob("*.json"))}


def _expressions(node) -> list[str]:
    found = []
    if isinstance(node, dict):
        for key, value in node.items():
            if key in ("expr", "definition") and isinstance(value, str):
                found.append(value)
            else:
                found.extend(_expressions(value))
    elif isinstance(node, list):
        for item in node:
            found.extend(_expressions(item))
    return found


def test_every_dashboard_is_valid_json_with_its_variables_and_notice():
    dashboards = _dashboards()
    assert set(dashboards) == {"pravaha-node-overview.json", "pravaha-query-drilldown.json",
                               "pravaha-alerts-catalogue.json", "pravaha-assistant.json"}
    uids = set()
    for name, board in dashboards.items():
        assert board["schemaVersion"] >= 36, name
        assert "Copyright (c) 2026 Ashutosh Sinha" in board["description"], name
        variables = [v["name"] for v in board["templating"]["list"]]
        assert variables[0] == "datasource", name
        if name != "pravaha-assistant.json":
            assert {"node", "query"} <= set(variables), name
        uids.add(board["uid"])
        for panel in board["panels"]:
            if panel["type"] != "row":
                assert panel["datasource"] == {"type": "prometheus", "uid": "${datasource}"}, (name, panel["title"])
                assert panel["targets"], (name, panel["title"])
        assert "#A51C30" in json.dumps(board), f"{name} carries the brand's crimson"
    assert len(uids) == len(dashboards)


def test_every_console_metric_a_dashboard_or_rule_reads_is_published(tmp_path, monkeypatch):
    used = set()
    for board in _dashboards().values():
        for expr in _expressions(board):
            used |= set(re.findall(r"\bpravaha_console_[a-z_]+\b", expr))
    rules = (OBSERVABILITY / "prometheus" / "pravaha-rules.yaml").read_text(encoding="utf-8")
    used |= set(re.findall(r"\bpravaha_console_[a-z_]+\b", rules))
    assert used, "the assistant dashboard reads the console's metrics"

    console = _console(tmp_path, monkeypatch, models={"a": [UNAVAILABLE], "b": [refusal("b")]})
    admin = console.client()
    ask(admin, "explain-refusal", code="PRV-2050")
    console.service.router.ledger.record("admin", "b", 10)
    published = names(scrape(admin).text)
    assert used - published == set(), f"read by a dashboard or rule and not published: {used - published}"


def _rules_body(text: str) -> str:
    """The rules without the file's leading comment header."""
    lines = text.splitlines()
    while lines and lines[0].startswith("#"):
        lines.pop(0)
    return "\n".join(lines).strip() + "\n"


def test_the_rules_file_the_helm_copy_and_the_help_topic_are_the_same_rules():
    rules = (OBSERVABILITY / "prometheus" / "pravaha-rules.yaml").read_text(encoding="utf-8")
    helm = (REPO / "deploy" / "helm" / "pravaha" / "files" / "pravaha-rules.yaml").read_text(encoding="utf-8")
    assert helm == rules, "deploy/helm/pravaha/files/pravaha-rules.yaml is a copy of the rules file; copy it again"
    topic = (CONSOLE_ROOT / "content" / "topics" / "metrics-alerts.md").read_text(encoding="utf-8")
    blocks = re.findall(r"```yaml\n(groups:.*?)```", topic, re.DOTALL)
    assert len(blocks) == 1, "the help topic shows the rules in exactly one yaml block that starts with groups:"
    assert blocks[0].strip() + "\n" == _rules_body(rules), \
        "the help topic's rules and deploy/observability/prometheus/pravaha-rules.yaml differ"


def test_the_rules_parse_and_name_every_documented_alert():
    yaml = pytest.importorskip("yaml")
    document = yaml.safe_load((OBSERVABILITY / "prometheus" / "pravaha-rules.yaml").read_text(encoding="utf-8"))
    alerts = [rule["alert"] for group in document["groups"] for rule in group["rules"]]
    assert alerts[:10] == ["PravahaQueryNotRunning", "PravahaSourceStopped", "PravahaQueryStateNearCeiling",
                           "PravahaWatermarkBehind", "PravahaCheckpointStale", "PravahaCheckpointFailing",
                           "PravahaSpillNearQuota", "PravahaDeadLettersArriving", "PravahaRejectingTooMuch",
                           "PravahaDeadLettersLost"]
    assert {"PravahaNotificationDeliveryFailing", "PravahaAlertNotificationsOwedGrowing",
            "PravahaCatalogDenialsSpike", "PravahaAssistantAllModelsFailing"} <= set(alerts)
    for group in document["groups"]:
        for rule in group["rules"]:
            assert rule["expr"] and rule["labels"]["severity"] and rule["annotations"]["summary"], rule["alert"]


def test_promtool_accepts_the_rules_where_it_is_installed():
    promtool = shutil.which("promtool")
    if promtool is None:
        pytest.skip("promtool is not installed here; the CI image that has it runs this check")
    result = subprocess.run([promtool, "check", "rules", str(OBSERVABILITY / "prometheus" / "pravaha-rules.yaml")],
                            capture_output=True, text=True, timeout=60, check=False)
    assert result.returncode == 0, result.stdout + result.stderr
