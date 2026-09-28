"""``pravaha ask`` and ``pravaha assist eval``, and the evaluation harness's scoring, with scripted
answers from the fake provider and a stand-in for the engine's HTTP API.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The scoring is pinned case by case: a draft equal to the reference passes by plan; one that
differs fails unless ``--run`` shows the same fingerprint or the same answer; a negative case
passes when refused or asked about, and a model that loosens the question to get it accepted is
caught. Nothing reaches a real model or anything beyond 127.0.0.1.
"""

from __future__ import annotations

import io
import json
import os
from typing import Any

import pytest
from assist_support import fake_config
from engine_support import FakeClient, FakeEngine, stream

from pravaha.api import EngineApi
from pravaha.assist import AssistConfig, Assistant, Evaluator, ModelRouter, UsageLedger
from pravaha.assist import load_golden_set
from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_USAGE, main
from pravaha.cli import _assist as assist_cli
from pravaha.cli._common import Context

LOW_STOCK = ("SELECT sku, warehouse, on_hand, reorder_point, updated_at\nFROM stock\n"
             "WHERE on_hand <= reorder_point")
WINDOWED = ("SELECT STREAM TUMBLE_END(ts, INTERVAL '1' MINUTE) AS window_end, customer, "
            "COUNT(*) AS orders FROM orders GROUP BY TUMBLE(ts, INTERVAL '1' MINUTE), customer")


def reply(sql: str, keys: Any, *, questions: Any = (), name: str = "drafted") -> str:
    return json.dumps({
        "name": name, "sql": sql, "keys": list(keys),
        "options": {"retention": None, "index": None, "sink": None, "lane": None},
        "explanation": "e", "assumptions": [], "questions": list(questions), "confidence": 0.5,
    })


@pytest.fixture
def engine():
    fake = FakeEngine()
    fake.streams = [
        stream("stock", "sku:STRING", "warehouse:STRING", "on_hand:INT32", "reorder_point:INT32",
               "updated_at:TIMESTAMP", event_time="updated_at"),
        stream("order_line", "order_id:STRING", "region:STRING", "amount_minor:INT64",
               "ordered_at:TIMESTAMP", event_time="ordered_at"),
        stream("card_auth", "auth_id:STRING", "card_id:STRING", "status:STRING",
               "auth_time:TIMESTAMP", event_time="auth_time"),
        stream("ad_click", "click_id:STRING", "impression_id:STRING", "click_time:TIMESTAMP",
               event_time="click_time"),
        stream("ad_impression", "impression_id:STRING", "impression_time:TIMESTAMP",
               event_time="impression_time"),
        stream("orders", "customer:STRING", "ts:TIMESTAMP", event_time="ts"),
    ]
    try:
        yield fake
    finally:
        fake.close()


def evaluator(tmp_path, engine, replies, **kwargs: Any) -> Evaluator:
    config = AssistConfig.from_dict(fake_config({"m": replies}))
    router = ModelRouter(config, ledger=UsageLedger(tmp_path / "u.json"), environ={}, user="ana")
    return Evaluator(Assistant(router, EngineApi(engine.url)), sleep=lambda _s: None, **kwargs)


CASES = ["retail-inventory-mysql/low_stock", "retail-inventory-mysql/stock_levels",
         "trade-processing/trade_feed", "negative/unbounded-group-by",
         "negative/join-without-time-bound", "negative/stream-does-not-exist"]

SCRIPT = [
    reply(LOW_STOCK, ["sku", "warehouse"]),                           # low_stock: the reference
    reply("SELECT sku, warehouse, on_hand FROM stock WHERE on_hand > 0",
          ["sku", "warehouse"]),                                     # stock_levels: not the same
    reply("SELECT card_id, COUNT(*) AS n FROM card_auth GROUP BY card_id", ["card_id"]),
    reply("SELECT card_id, COUNT(*) AS n FROM card_auth GROUP BY card_id", ["card_id"]),
    reply("SELECT card_id, COUNT(*) AS n FROM card_auth GROUP BY card_id", ["card_id"]),
    reply("SELECT card_id, COUNT(*) AS n FROM card_auth GROUP BY card_id", ["card_id"]),
    reply("", [], questions=["With no time bound the engine would still bound the join: what "
                             "attribution window do you want?"]),
    reply("SELECT STREAM TUMBLE_END(ordered_at, INTERVAL '1' HOUR) AS window_end, region, "
          "SUM(amount_minor) AS refunds FROM order_line "
          "GROUP BY TUMBLE(ordered_at, INTERVAL '1' HOUR), region",
          ["window_end", "region"]),                                 # loosened: not refunds
]


def test_the_golden_set_is_scored_by_meaning_and_a_loosened_question_is_caught(tmp_path, engine):
    report = evaluator(tmp_path, engine, SCRIPT).run(only=CASES)
    results = {r.id: r for r in report.results}
    assert [r.id for r in report.results] == [c.id for c in load_golden_set() if c.id in CASES]

    low = results["retail-inventory-mysql/low_stock"]
    assert low.passed and low.outcome == "accepted" and low.equal == "plan"
    assert low.inputs_match and low.keys_match and low.model == "m" and low.tokens > 0

    levels = results["retail-inventory-mysql/stock_levels"]
    assert levels.passed is False and levels.outcome == "accepted" and levels.equal is None
    assert "plan differs" in levels.reason and "--run" in levels.reason

    skipped = results["trade-processing/trade_feed"]
    assert skipped.passed is None and skipped.outcome == "skipped" and "trade" in skipped.reason

    unbounded = results["negative/unbounded-group-by"]
    assert unbounded.passed and unbounded.outcome == "refused" and unbounded.repairs == 3
    assert "PRV-2050" in unbounded.reason

    join = results["negative/join-without-time-bound"]
    assert join.passed and join.outcome == "questions"

    loosened = results["negative/stream-does-not-exist"]
    assert loosened.passed is False and loosened.outcome == "accepted"
    assert "different question" in loosened.reason and "order_line" in loosened.reason

    summary = report.summary()
    assert summary["scored"] == 5 and summary["skipped"] == 1
    assert summary["passed"] == 3 and summary["failed"] == 2
    assert summary["negativesCaught"] == 2 and summary["negatives"] == 3
    assert summary["referencesAccepted"] == 2 and summary["referencesEqual"] == 1
    assert summary["repairs"] == 3 and summary["models"] == ["m"]
    assert not report.all_passed
    json.dumps(report.to_dict(full=True))


def test_the_case_own_example_is_not_in_its_prompt(tmp_path, engine):
    ev = evaluator(tmp_path, engine, [reply(LOW_STOCK, ["sku", "warehouse"])])
    ev.run(only=["retail-inventory-mysql/low_stock"])
    sent = ev.assistant.router.provider("m").requests[0]
    assert "(retail-inventory-mysql/low_stock)" not in (sent.system or "")


def test_a_reference_read_from_other_streams_fails(tmp_path, engine):
    report = evaluator(tmp_path, engine, [reply(
        "SELECT order_id, region FROM order_line", ["order_id"])]).run(
        only=["retail-inventory-mysql/low_stock"])
    result = report.results[0]
    assert result.passed is False and result.inputs_match is False
    assert "reads order_line; the reference reads stock" in result.reason


def test_run_mode_compares_fingerprints_then_answers_and_drops_what_it_registered(
        tmp_path, engine):
    different = "SELECT sku, warehouse, on_hand FROM stock WHERE on_hand > 0"
    reference = next(c for c in load_golden_set() if c.id == "retail-inventory-mysql/stock_levels")
    client = FakeClient()
    client.fingerprints = {different: "same", str(reference.reference["sql"]): "same"}
    ev = evaluator(tmp_path, engine, [reply(different, ["sku", "warehouse"])], run=True,
                   client=client, prefix="t_")
    result = ev.run(only=["retail-inventory-mysql/stock_levels"]).results[0]
    assert result.passed and result.equal == "fingerprint"
    assert [r[0] for r in client.registered] == ["t_ref_stock_levels", "t_draft_stock_levels"]
    assert client.registered[0][2] == [0, 1]
    assert client.dropped == ["t_draft_stock_levels", "t_ref_stock_levels"]

    client = FakeClient()
    client.rows = {"t_ref_stock_levels": [{"sku": "a", "w": "x"}, {"sku": "b", "w": "y"}],
                   "t_draft_stock_levels": [{"sku": "b", "w": "y"}, {"sku": "a", "w": "x"}]}
    ev = evaluator(tmp_path, engine, [reply(different, ["sku", "warehouse"])], run=True,
                   client=client, prefix="t_")
    result = ev.run(only=["retail-inventory-mysql/stock_levels"]).results[0]
    assert result.passed and result.equal == "answer" and "2 rows" in result.reason
    assert client.queries == ["SELECT * FROM t_ref_stock_levels",
                              "SELECT * FROM t_draft_stock_levels"]
    assert sorted(client.dropped) == ["t_draft_stock_levels", "t_ref_stock_levels"]

    client = FakeClient()
    client.rows = {"t_ref_stock_levels": [{"sku": "a"}], "t_draft_stock_levels": []}
    ev = evaluator(tmp_path, engine, [reply(different, ["sku", "warehouse"])], run=True,
                   client=client, prefix="t_")
    result = ev.run(only=["retail-inventory-mysql/stock_levels"]).results[0]
    assert result.passed is False and "different answer" in result.reason
    assert len(client.dropped) == 2


def test_run_mode_needs_a_client_and_a_sane_prefix(tmp_path, engine):
    from pravaha.assist import AssistConfigError

    with pytest.raises(AssistConfigError):
        evaluator(tmp_path, engine, [reply(LOW_STOCK, [])], run=True)
    with pytest.raises(ValueError):
        evaluator(tmp_path, engine, [reply(LOW_STOCK, [])], prefix="bad-prefix;")


def test_a_model_failure_is_an_error_case_not_a_crash(tmp_path, engine):
    report = evaluator(tmp_path, engine, [{"error": "refused", "message": "policy"}]).run(
        only=["retail-inventory-mysql/low_stock"], limit=1)
    result = report.results[0]
    assert result.outcome == "error" and result.passed is False and "ModelRefused" in result.reason


# ---------------------------------------------------------------------------------- the CLI


@pytest.fixture
def home(tmp_path, monkeypatch):
    for name in list(os.environ):
        if name.startswith("PRAVAHA_") or name == "NO_COLOR":
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("PRAVAHA_CONFIG_DIR", str(tmp_path / "config"))
    (tmp_path / "config").mkdir()
    return tmp_path / "config"


def configure(home, replies) -> None:
    (home / "assist.json").write_text(json.dumps(fake_config({"m": replies})))


def run(*argv: str) -> "tuple[int, str, str]":
    out, err = io.StringIO(), io.StringIO()
    code = main(list(argv), stdout=out, stderr=err)
    return code, out.getvalue(), err.getvalue()


@pytest.fixture
def client(monkeypatch):
    fake = FakeClient()
    monkeypatch.setattr(Context, "client", property(lambda self: fake))
    return fake


def test_ask_prints_the_draft_the_plan_and_the_turns(home, engine):
    configure(home, [reply("SELECT customer, COUNT(*) AS n FROM orders GROUP BY customer",
                           ["customer"]), reply(WINDOWED, ["window_end", "customer"])])
    code, out, err = run("ask", "orders", "per", "customer", "per", "minute", "--http", engine.url)
    assert code == EXIT_OK, err
    assert out.startswith("the engine accepts it after 1 repair turn")
    assert "CREATE CONTINUOUS QUERY drafted\n    KEYED BY (window_end, customer)" in out
    assert "The engine's plan (physical)" in out and "Plan" in out
    assert "1. draft   refused by the engine: PRV-2050" in out
    assert "2. repair  accepted by the engine" in out
    assert "answered by m (fake fake-m)" in err and "prompt draft_query@v1" in err
    assert engine.calls("/api/v1/queries/validate")[0]["sql"].startswith("SELECT customer")


def test_ask_json_and_a_refusal_after_repairs_exits_1(home, engine):
    configure(home, [reply("SELECT customer, COUNT(*) AS n FROM orders GROUP BY customer",
                           ["customer"])])
    code, out, _ = run("ask", "count every customer's orders forever", "--http", engine.url,
                       "--json", "--repairs", "1")
    assert code == EXIT_REFUSED
    body = json.loads(out)
    assert body["status"] == "refused" and body["verdict"]["code"] == "PRV-2050"
    assert len(body["turns"]) == 2 and body["registered"] is None


def test_ask_with_questions_prints_them_and_asks_no_engine_to_validate(home, engine):
    configure(home, [reply("", [], questions=["Which amount?"])])
    code, out, _ = run("ask", "big orders", "--http", engine.url)
    assert code == EXIT_OK
    assert "the model needs answers" in out and "- Which amount?" in out
    assert engine.calls("/api/v1/queries/validate") == []


def test_ask_register_yes_registers_under_the_callers_client(home, engine, client):
    configure(home, [reply(WINDOWED, ["window_end", "customer"])])
    code, out, err = run("ask", "orders per customer per minute", "--http", engine.url,
                         "--register", "--yes", "--name", "per_minute")
    assert code == EXIT_OK, err
    assert client.registered == [("per_minute", WINDOWED, [0, 1], None, None)]
    assert "registered per_minute" in out


def test_ask_register_without_confirmation_registers_nothing(home, engine, client, monkeypatch):
    configure(home, [reply(WINDOWED, ["window_end", "customer"])])
    monkeypatch.setattr(assist_cli, "confirm_on_terminal", lambda ctx, question: False)
    code, _, err = run("ask", "orders per customer per minute", "--http", engine.url,
                       "--register")
    assert code == EXIT_OK
    assert client.registered == [] and "nobody confirmed it" in err
    monkeypatch.setattr(assist_cli, "confirm_on_terminal", lambda ctx, question: True)
    code, _, _ = run("ask", "orders per customer per minute", "--http", engine.url, "--register")
    assert code == EXIT_OK and len(client.registered) == 1


def test_ask_register_of_a_refused_draft_registers_nothing(home, engine, client):
    configure(home, [reply("SELECT customer, COUNT(*) AS n FROM orders GROUP BY customer",
                           ["customer"])])
    code, _, err = run("ask", "count forever", "--http", engine.url, "--register", "--yes",
                       "--repairs", "0")
    assert code == EXIT_REFUSED and client.registered == []
    assert "not registered" in err


def test_ask_usage_errors(home, engine):
    configure(home, [reply(WINDOWED, ["window_end", "customer"])])
    assert run("ask", "x", "--http", engine.url, "--yes")[0] == EXIT_USAGE
    assert run("ask", "x", "--http", engine.url, "--repairs", "4")[0] == EXIT_USAGE


def test_assist_eval_reports_a_table_and_json(home, engine):
    configure(home, SCRIPT)
    code, out, err = run("assist", "eval", "--http", engine.url, "--case", ",".join(CASES))
    assert code == EXIT_REFUSED            # two scored cases failed
    assert "CASE" in out and "retail-inventory-mysql/low_stock" in out
    assert "passed" in out and "3 of 5 scored (1 skipped, 0 errors)" in out
    assert "2 of 3 refused or asked" in out
    assert "pass  retail-inventory-mysql/low_stock" in err
    configure(home, [reply(LOW_STOCK, ["sku", "warehouse"])])
    code, out, _ = run("assist", "eval", "--http", engine.url, "--case",
                       "retail-inventory-mysql/low_stock", "--json")
    assert code == EXIT_OK
    body = json.loads(out)
    assert body["summary"]["passed"] == 1 and body["results"][0]["equal"] == "plan"
    assert "draft" not in body["results"][0]
