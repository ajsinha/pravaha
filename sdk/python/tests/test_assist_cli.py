"""``pravaha explain-sql``, ``pravaha why`` and ``pravaha assist``, with the fake provider and a
local stand-in for the engine's HTTP API.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

What is pinned is the command line's half: which engine calls it makes, what it prints on stdout
and stderr, and the exit codes -- 1 for a model failure with the normalised error, 2 for a wrong
assistant configuration, 3 for an engine that is not there.
"""

from __future__ import annotations

import io
import json
import os

import pytest
from assist_support import EXPLANATION, REFUSAL, PlaybackServer, fake_config

from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_UNREACHABLE, EXIT_USAGE, main

SQL = "SELECT customer, COUNT(*) FROM orders GROUP BY customer"
EXPLAIN_PATH = "/api/v1/queries/explain?level=physical&format=text"


@pytest.fixture
def home(tmp_path, monkeypatch):
    for name in list(os.environ):
        if name.startswith("PRAVAHA_") or name == "NO_COLOR":
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("PRAVAHA_CONFIG_DIR", str(tmp_path / "config"))
    (tmp_path / "config").mkdir()
    return tmp_path / "config"


@pytest.fixture
def engine():
    server = PlaybackServer()
    server.queue("POST", EXPLAIN_PATH, {"status": 200, "body": {"plan": "Aggregate\n  Scan[orders]"}})
    server.queue("POST", "/api/v1/queries/validate", {"status": 200, "body": {
        "valid": False, "diagnostics": [{"code": "PRV-2050", "message": "unbounded state"}]}},
        {"status": 200, "body": {"valid": True, "diagnostics": []}})
    server.queue("GET", "/api/v1/queries/per_customer", {"status": 200, "body": {
        "name": "per_customer", "sql": SQL, "retention": "PT24H"}})
    try:
        yield server
    finally:
        server.close()


def configure(home, document) -> None:
    (home / "assist.json").write_text(json.dumps(document))


def run(*argv: str) -> "tuple[int, str, str]":
    out, err = io.StringIO(), io.StringIO()
    code = main(list(argv), stdout=out, stderr=err)
    return code, out.getvalue(), err.getvalue()


# ---------------------------------------------------------------------------------- explain-sql


def test_explain_sql_prints_the_explanation_and_who_answered(home, engine):
    configure(home, fake_config({"m": [EXPLANATION]}))
    code, out, err = run("explain-sql", "--sql", SQL, "--http", engine.url, "--show-plan")
    assert code == EXIT_OK, err
    assert out.startswith("It keeps a count of orders per customer")
    assert "1. Reads the orders stream." in out and "- A late order corrects" in out
    assert "Aggregate\n  Scan[orders]" in out
    assert "answered by m (fake fake-m)" in err and "prompt explain_query@v1" in err
    assert [r.path for r in engine.requests] == [EXPLAIN_PATH]
    assert engine.requests[0].body == {"sql": SQL}


def test_explain_sql_of_a_registered_query_as_json(home, engine):
    configure(home, fake_config({"m": [EXPLANATION]}))
    code, out, _ = run("explain-sql", "--query", "per_customer", "--http", engine.url, "--json")
    assert code == EXIT_OK
    body = json.loads(out)
    assert body["queryName"] == "per_customer" and body["sql"] == SQL
    assert body["enginePlan"]["plan"].startswith("Aggregate")
    assert body["answeredBy"]["modelId"] == "m"


def test_explain_sql_of_sql_the_engine_refuses_asks_no_model(home, engine):
    configure(home, fake_config({"m": [{"error": "unavailable", "message": "must not be asked"}]}))
    engine.queue("POST", EXPLAIN_PATH, {"status": 400, "body": {
        "code": "PRV-2001", "message": "syntax error at line 1"}})
    code, _, err = run("explain-sql", "--sql", "SELEC", "--http", engine.url)
    assert code == EXIT_REFUSED
    assert "PRV-2001" in err and "must not be asked" not in err


def test_explain_sql_needs_sql_or_a_query(home, engine):
    configure(home, fake_config({"m": [EXPLANATION]}))
    assert run("explain-sql", "--http", engine.url)[0] == EXIT_USAGE


def test_an_unreachable_engine_is_exit_3(home):
    configure(home, fake_config({"m": [EXPLANATION]}))
    code, _, _ = run("explain-sql", "--sql", SQL, "--http", "http://127.0.0.1:9")
    assert code == EXIT_UNREACHABLE


# ---------------------------------------------------------------------------------- why


def test_why_without_sql_asks_no_engine(home):
    configure(home, fake_config({"m": [json.dumps({**json.loads(REFUSAL), "rewrite": None})]}))
    code, out, err = run("why", "PRV-2050", "--http", "http://127.0.0.1:9")
    assert code == EXIT_OK, err
    assert out.startswith("PRV-2050  The query's state would grow without bound.")
    assert "fix" in out and "Group by a window" in out
    assert "rewrite" not in out


def test_why_with_sql_shows_the_engine_verdict_on_the_rewrite(home, engine):
    configure(home, fake_config({"m": [REFUSAL]}))
    code, out, err = run("why", "PRV-2050", "--sql", SQL, "--http", engine.url)
    assert code == EXIT_OK, err
    assert "rewrite  (the engine accepts it)" in out
    assert "TUMBLE(ts, INTERVAL '1' MINUTE)" in out
    assert [r.path for r in engine.requests] == ["/api/v1/queries/validate"] * 2


def test_why_json(home, engine):
    configure(home, fake_config({"m": [REFUSAL]}))
    code, out, _ = run("why", "PRV-2050", "--sql", SQL, "--http", engine.url, "--json")
    assert code == EXIT_OK
    body = json.loads(out)
    assert body["code"] == "PRV-2050" and body["engine"]["codes"] == ["PRV-2050"]
    assert body["rewriteVerdict"]["valid"] is True
    assert body["prompt"] == "explain_refusal@v1" and body["dialectCard"]["sections"]


def test_why_refuses_something_that_is_not_a_code(home):
    configure(home, fake_config({"m": [REFUSAL]}))
    code, _, err = run("why", "2050")
    assert code == EXIT_USAGE and "codes look like PRV-2050" in err


# ---------------------------------------------------------------------------------- failures


def test_a_model_failure_is_exit_1_with_the_normalised_error(home):
    configure(home, fake_config({"m": [{"error": "rate_limited", "retry_after": 12,
                                        "message": "slow down"}]}))
    code, out, err = run("why", "PRV-2050")
    assert code == EXIT_REFUSED and out == ""
    assert "ModelRateLimited: slow down (m/fake/fake-m) (retry after 12 s)" in err
    code, _, err = run("why", "PRV-2050", "--json")
    error = json.loads(err)["error"]
    assert error["kind"] == "ModelRateLimited" and error["retryAfter"] == 12
    assert error["alias"] == "m" and error["exit"] == EXIT_REFUSED


def test_a_refused_answer_is_exit_1_naming_the_model(home):
    configure(home, fake_config({"m": [{"error": "refused", "message": "declined"}]}))
    code, _, err = run("why", "PRV-2050")
    assert code == EXIT_REFUSED and "ModelRefused: declined (m/fake/fake-m)" in err


def test_a_budget_refusal_is_exit_1(home):
    configure(home, fake_config({"m": [REFUSAL]}, budgets={"per_request_max_tokens": 100}))
    code, _, err = run("why", "PRV-2050")
    assert code == EXIT_REFUSED and "BudgetExceeded" in err


@pytest.mark.parametrize("document, words", [
    (None, "no model is configured"),
    ({"providers": [{"id": "x", "type": "anthropic", "api_key": "abc"}]}, "never holds a secret"),
    ({"providers": [{"id": "x", "type": "anthropic", "api_key_env": "NOT_SET_HERE"}],
      "models": [{"id": "m", "provider": "x", "model": "claude-opus-5"}],
      "profiles": {"explain": ["m"]}}, "NOT_SET_HERE"),
])
def test_a_wrong_configuration_is_exit_2(home, document, words):
    if document is not None:
        configure(home, document)
    code, _, err = run("why", "PRV-2050")
    assert code == EXIT_USAGE
    assert words in err


# ---------------------------------------------------------------------------------- assist


def test_assist_models_shows_each_model_and_the_chains(home):
    document = fake_config({"a": ["x"], "b": ["y"]}, chain=["a", "b"])
    document["profiles"]["draft"] = ["b"]
    document["providers"].append({"id": "claude", "type": "anthropic", "api_key_env": "CLAUDE_KEY"})
    document["models"].append({"id": "c", "provider": "claude", "model": "claude-opus-5",
                               "enabled": False})
    configure(home, document)
    code, out, err = run("assist", "models")
    assert code == EXIT_OK, err
    assert "explain#1*" in out and "draft#1, explain#2*" in out
    assert "CLAUDE_KEY (NOT SET)" in out
    assert "*explain  a -> b" in out and " draft  b" in out
    code, out, _ = run("assist", "models", "--json")
    body = json.loads(out)
    assert body["defaultProfile"] == "explain" and body["profiles"]["explain"] == ["a", "b"]
    assert [m["id"] for m in body["models"]] == ["a", "b", "c"]
    assert body["models"][2]["key"] == "CLAUDE_KEY" and body["models"][2]["keySet"] is False


def test_assist_models_with_nothing_configured(home):
    code, _, err = run("assist", "models")
    assert code == EXIT_OK and "no models are configured" in err


def test_assist_providers_lists_the_built_ins(home):
    code, out, _ = run("assist", "providers", "--json")
    names = [p["name"] for p in json.loads(out)]
    assert code == EXIT_OK and names[:5] == ["fake", "anthropic", "openai", "openai-compatible",
                                             "ollama"]


def test_assist_check(home):
    document = fake_config({"good": ["x"], "bad": ["x"]})
    document["models"][1]["options"]["ping"] = "fail"
    configure(home, document)
    code, out, _ = run("assist", "check", "--model", "good")
    assert code == EXIT_OK and "ok" in out
    code, out, _ = run("assist", "check")
    assert code == EXIT_REFUSED and "FAILED" in out and "ModelUnavailable" in out
    code, out, _ = run("assist", "check", "--json")
    assert {r["model_id"]: r["ok"] for r in json.loads(out)} == {"good": True, "bad": False}


def test_assist_use_changes_nothing_without_yes(home):
    configure(home, fake_config({"a": ["x"], "b": ["y"]}, chain=["a"]))
    before = (home / "assist.json").read_text()
    code, out, err = run("assist", "use", "explain", "b,a")
    assert code == EXIT_OK
    assert "nothing changed: pass --yes" in err and '"chain": ["b", "a"]' in out
    assert (home / "assist.json").read_text() == before


def test_assist_use_enable_and_disable_with_yes(home):
    configure(home, fake_config({"a": ["x"], "b": ["y"]}, chain=["a"]))
    code, out, _ = run("assist", "use", "explain", "b,a", "--yes", "--json")
    assert code == EXIT_OK
    record = json.loads(out)
    assert record["applied"] and record["after"]["chain"] == ["b", "a"] and record["version"] == 1
    stored = json.loads((home / "assist.json").read_text())
    assert stored["profiles"]["explain"] == ["b", "a"] and stored["version"] == 1
    code, _, err = run("assist", "disable", "a", "--yes")
    assert code == EXIT_USAGE and "model 'a' is disabled" in err, "a chain still names it"
    assert run("assist", "use", "explain", "b", "--yes")[0] == EXIT_OK
    assert run("assist", "disable", "a", "--yes")[0] == EXIT_OK
    assert json.loads((home / "assist.json").read_text())["models"][0]["enabled"] is False
    assert run("assist", "enable", "a", "--yes")[0] == EXIT_OK
    code, _, err = run("assist", "use", "explain", "ghost", "--yes")
    assert code == EXIT_USAGE and "no model 'ghost'" in err
