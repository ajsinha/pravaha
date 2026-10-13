"""The engine's plain-English features from the command line (docs/guides/AI.md): ``query --ask``,
``chat``, ``ask --engine``, ``alert --ask``, ``explain --why-changed``, ``explain-sql --engine``,
``why --engine``, ``subscribe --narrate`` -- and ``EngineApi``'s ``ai_*`` calls under them.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Against the recording HTTP server of ``test_cli``, with a stand-in for the Flight client where a
command registers, creates or subscribes. What is pinned: the endpoint and body, that every call
carries the person's own token, what prints (the SQL the model chose first), the exit codes, and that
nothing is registered or created without ``--yes`` or a ``y``. What the engine decides is pinned in
``AiFeaturesEndToEndTest`` (pravaha-it).
"""

from __future__ import annotations

import io
import json
from typing import Any

import pytest

from pravaha.api import EngineApi
from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_USAGE
from test_cli import _Engine, answer, engine, home, last, run  # noqa: F401 -- the recording server

ASK = {
    "question": "total by region",
    "sql": "SELECT region, SUM(amount) AS total FROM payments GROUP BY region",
    "explanation": "Sums the amounts per region.",
    "columns": [{"name": "region", "type": "VARCHAR"}, {"name": "total", "type": "BIGINT"}],
    "rows": [["EU", 40]],
    "rowCount": 1,
    "truncated": False,
    "chart": {"kind": "bar", "x": "region", "y": "total", "series": None},
    "values": "labels-only",
    "summary": None,
    "turns": 1,
}

DRAFT = {
    "description": "spend per region",
    "name": "region_spend",
    "sql": "SELECT region, SUM(amount) AS total FROM payments GROUP BY region",
    "keys": ["region"],
    "options": {},
    "statement": "CREATE CONTINUOUS QUERY region_spend\n  KEYED BY (region)\nAS\n"
                 "SELECT region, SUM(amount) AS total FROM payments GROUP BY region",
    "explanation": "Total spend per region.",
    "turns": 2,
    "dryRun": {"fingerprint": "fp-1", "plan": "Aggregate\n  Scan payments", "outputFields":
               [{"name": "region", "type": "VARCHAR"}], "reads": ["payments"],
               "lane": "the lane of by_region's computation", "sharesWith": ["by_region"]},
    "registered": False,
}

ALERT = {
    "description": "over 500", "name": "big_total", "view": "by_region", "condition": "total > 500",
    "channels": ["ops_log"], "statement": "CREATE ALERT big_total ON by_region\n  WHERE total > 500\n  NOTIFY ops_log",
    "explanation": "Fires when a region passes 500.", "turns": 1, "created": False,
}


class _Result:
    def __init__(self, columns: "list[str]", rows: "list[list[Any]]") -> None:
        self.columns = columns
        self._rows = rows

    def __iter__(self):
        return iter(self._rows)


class _Row(list):
    def __init__(self, values: "list[Any]", weight: int, columns: "list[str]") -> None:
        super().__init__(values)
        self.weight = weight
        self.columns = columns

    def to_dict(self) -> "dict[str, Any]":
        return dict(zip(self.columns, self))


class _Batch(list):
    snapshot = False
    reconnected = False
    dropped_before = 0

    def __init__(self, rows: "list[_Row]", frontier: int) -> None:
        super().__init__(rows)
        self.frontier = frontier


class _Flight:
    """The Flight client's part these commands use: a statement run as you, a subscription."""

    def __init__(self) -> None:
        self.statements: "list[str]" = []
        self.batches: "list[_Batch]" = []

    def query(self, sql: str, params: Any = None) -> _Result:
        self.statements.append(sql)
        return _Result(["name", "state", "fingerprint"], [["region_spend", "RUNNING", "fp-1"]])

    def subscribe(self, view: str, *args: Any, **kwargs: Any) -> "list[_Batch]":
        return list(self.batches)

    def close(self) -> None:
        pass


@pytest.fixture
def flight(monkeypatch):
    fake = _Flight()
    monkeypatch.setattr("pravaha.cli._settings.Settings.client", lambda self: fake)
    return fake


def cli(url: str, *argv: str) -> "tuple[int, str, str]":
    return run("--http", url, "--token", "ana-token", "--insecure-token", *argv)


# ---------------------------------------------------------------------------------- the SDK


def test_every_feature_is_one_endpoint_with_its_body(engine):
    api = EngineApi(engine, token="ana-token", allow_insecure_token=True)
    answer("POST", "/api/v1/ai/ask", ASK)
    assert api.ai_ask("and only EU?", [{"question": "total by region", "sql": ASK["sql"]}])["chart"]["kind"] == "bar"
    assert last()["body"] == {"question": "and only EU?",
                              "conversation": [{"question": "total by region", "sql": ASK["sql"]}]}
    assert last()["authorization"] == "Bearer ana-token"
    api.ai_draft("spend per region", "region_spend")
    assert last()["path"] == "/api/v1/ai/draft" and last()["body"] == {"description": "spend per region",
                                                                       "name": "region_spend"}
    api.ai_alert_draft("over 500")
    assert last()["path"] == "/api/v1/ai/alert-draft" and last()["body"] == {"description": "over 500"}
    api.ai_explain("change", view="payments")
    assert last()["body"] == {"kind": "change", "view": "payments"}
    api.ai_explain("refusal", code="PRV-2002", sql="SELECT nope FROM payments")
    assert last()["body"] == {"kind": "refusal", "code": "PRV-2002", "sql": "SELECT nope FROM payments"}
    api.ai_narrate("payments", [{"weight": 1, "values": ["p9", 90]}])
    assert last()["body"] == {"view": "payments", "changes": [{"weight": 1, "values": ["p9", 90]}]}
    api.ai_page("status", "The node's health.", "what is this?", [{"title": "Status", "text": "..."}])
    assert last()["path"] == "/api/v1/ai/page" and last()["body"]["topics"] == [{"title": "Status", "text": "..."}]


# ---------------------------------------------------------------------------------- ask your data


def test_query_ask_prints_the_sql_the_model_chose_then_the_rows_and_the_chart(engine, home):
    answer("POST", "/api/v1/ai/ask", ASK)
    code, out, err = cli(engine, "query", "--ask", "total by region", "--chart")
    assert code == EXIT_OK, err
    assert out.index("the SQL the model chose") < out.index("SELECT region") < out.index("EU")
    assert "chart  bar  x=region y=total" in out
    assert "run as you" in err and "nothing saved" in err
    assert last()["body"] == {"question": "total by region"} and last()["authorization"] == "Bearer ana-token"
    code, out, _ = cli(engine, "query", "--ask", "total by region", "--json")
    assert code == EXIT_OK and json.loads(out)["sql"] == ASK["sql"]


def test_query_ask_prints_a_timestamp_as_query_does(engine, home):
    # AIASKTS-1: the answer's JSON carries epoch nanoseconds; query prints the instant.
    answer("POST", "/api/v1/ai/ask", dict(
        ASK, columns=[{"name": "window_end", "type": "TIMESTAMP(3) WITH LOCAL TIME ZONE"},
                      {"name": "total", "type": "BIGINT"}],
        rows=[[1790845260000000000, 6800], [None, 1]], rowCount=2))
    code, out, err = cli(engine, "query", "--ask", "total spend per minute")
    assert code == EXIT_OK, err
    assert "2026-10-01 09:01:00+00:00  6800" in out and "1790845260000000000" not in out
    code, out, _ = cli(engine, "query", "--ask", "total spend per minute", "--json")
    assert json.loads(out)["rows"][0][0] == 1790845260000000000, "--json is the engine's answer, unchanged"


def test_query_ask_refusals_keep_the_exit_code_contract(engine, home):
    assert cli(engine, "query", "--ask", "x", "--sql", "SELECT 1")[0] == EXIT_USAGE
    assert cli(engine, "query", "--sql", "SELECT 1", "--chart")[0] == EXIT_USAGE
    answer("POST", "/api/v1/ai/ask", {"code": "PRV-1088", "message": "PRV-1088  the engine refused the SQL the "
                                      "model chose, and again after one repair", "helpUrl": "", "timestamp": "",
                                      "path": "/api/v1/ai/ask"}, status=422)
    code, _, err = cli(engine, "query", "--ask", "never right")
    assert code == EXIT_REFUSED and "PRV-1088" in err
    answer("POST", "/api/v1/ai/ask", {"code": "PRV-1080", "message": "PRV-1080  the AI layer is off",
                                      "helpUrl": "", "timestamp": "", "path": "/api/v1/ai/ask"}, status=409)
    code, _, err = cli(engine, "query", "--ask", "anything")
    assert code == EXIT_REFUSED and "PRV-1080" in err


def test_chat_follows_up_with_the_earlier_questions_and_their_sql(engine, home, monkeypatch):
    answer("POST", "/api/v1/ai/ask", ASK)
    monkeypatch.setattr("sys.stdin", io.StringIO("total by region\nand only EU?\n"))
    code, out, _ = cli(engine, "chat")
    assert code == EXIT_OK
    asks = [c["body"] for c in _Engine.calls if c["path"] == "/api/v1/ai/ask"]
    assert asks[0] == {"question": "total by region"}
    assert asks[1] == {"question": "and only EU?", "conversation": [{"question": "total by region", "sql": ASK["sql"]}]}
    assert out.count("the SQL the model chose") == 2
    code, out, _ = cli(engine, "chat", "--once", "total by region", "--json")
    assert code == EXIT_OK and json.loads(out)["rows"] == [["EU", 40]]


def test_chat_says_a_refusal_and_keeps_going(engine, home, monkeypatch):
    answer("POST", "/api/v1/ai/ask", {"code": "PRV-7002", "message": "PRV-7002  the SQL the model chose was "
                                      "refused", "helpUrl": "", "timestamp": "", "path": "/api/v1/ai/ask"}, status=403)
    monkeypatch.setattr("sys.stdin", io.StringIO("show me everything\nand again\n"))
    code, _, err = cli(engine, "chat")
    assert code == EXIT_OK and err.count("PRV-7002  the SQL") == 2


# ---------------------------------------------------------------------------------- drafts


def test_ask_engine_shows_the_judged_draft_and_its_dry_run_and_registers_nothing(engine, home, flight):
    answer("POST", "/api/v1/ai/draft", DRAFT)
    code, out, err = cli(engine, "ask", "spend", "per", "region", "--engine")
    assert code == EXIT_OK, err
    assert "after one repair turn" in out and "CREATE CONTINUOUS QUERY region_spend" in out
    assert "shares with" in out and "by_region" in out and "fp-1" in out
    assert "nothing was registered" in err
    assert flight.statements == []
    assert last()["body"] == {"description": "spend per region"}


def test_ask_engine_registers_only_when_confirmed(engine, home, flight):
    answer("POST", "/api/v1/ai/draft", DRAFT)
    code, _, err = cli(engine, "ask", "spend per region", "--engine", "--register")
    assert code == EXIT_OK and flight.statements == [] and "nobody confirmed it" in err
    code, out, _ = cli(engine, "ask", "spend per region", "--engine", "--register", "--yes")
    assert code == EXIT_OK and flight.statements == [DRAFT["statement"]]
    assert "registered region_spend" in out
    code, out, _ = cli(engine, "ask", "spend per region", "--engine", "--json")
    assert json.loads(out)["registered"] is None
    assert cli(engine, "ask", "x", "--engine", "--yes")[0] == EXIT_USAGE
    assert cli(engine, "ask", "x", "--engine", "--model", "m")[0] == EXIT_USAGE


def test_alert_ask_creates_only_when_confirmed(engine, home, flight):
    answer("POST", "/api/v1/ai/alert-draft", ALERT)
    code, out, err = cli(engine, "alert", "--ask", "tell me when a region spends over 500")
    assert code == EXIT_OK and "CREATE ALERT big_total ON by_region" in out
    assert flight.statements == [] and "nothing was created" in err
    code, _, _ = cli(engine, "alert", "--ask", "over 500", "--name", "big_total", "--yes")
    assert code == EXIT_OK and flight.statements == [ALERT["statement"]]
    assert last()["body"] == {"description": "over 500", "name": "big_total"}
    assert cli(engine, "alert")[0] == EXIT_USAGE


# ---------------------------------------------------------------------------------- explain and narrate


def test_explain_why_changed_and_the_engine_explains(engine, home):
    answer("POST", "/api/v1/ai/explain", {
        "kind": "change", "explanation": "One payment entered.", "values": "labels-only", "engineCodes": [],
        "change": {"view": "payments", "since": "2026-10-12T10:00:00Z", "commits": 1, "entered": 1, "left": 0,
                   "keyColumns": ["id"], "enteredKeys": [["p4"]], "leftKeys": []}})
    code, out, err = cli(engine, "explain", "--why-changed", "payments")
    assert code == EXIT_OK, err
    assert out.startswith("One payment entered.") and "p4" in out
    assert last()["body"] == {"kind": "change", "view": "payments"}
    assert cli(engine, "explain", "--why-changed", "payments", "--sql", "SELECT 1")[0] == EXIT_USAGE

    answer("POST", "/api/v1/ai/explain", {"kind": "sql", "explanation": "It sums.", "plan": "Aggregate",
                                          "values": "labels-only", "engineCodes": []})
    code, out, _ = cli(engine, "explain-sql", "--query", "by_region", "--engine", "--show-plan")
    assert code == EXIT_OK and "It sums." in out and "Aggregate" in out
    assert last()["body"] == {"kind": "sql", "query": "by_region"}
    answer("POST", "/api/v1/ai/explain", {"kind": "refusal", "explanation": "The column is not there.",
                                          "code": "PRV-2002", "valid": False, "engineCodes": ["PRV-2002"],
                                          "values": "labels-only"})
    code, out, err = cli(engine, "why", "PRV-2002", "--sql", "SELECT nope FROM payments", "--engine")
    assert code == EXIT_OK and "The column is not there." in out and "PRV-2002" in err
    assert last()["body"] == {"kind": "refusal", "code": "PRV-2002", "sql": "SELECT nope FROM payments"}


def test_subscribe_narrate_words_each_commit_and_a_refusal_never_stops_it(engine, home, flight):
    cols = ["id", "amount"]
    flight.batches = [_Batch([_Row(["p4", 444], 1, cols)], 7), _Batch([_Row(["p5", 5], 1, cols)], 8)]
    answer("POST", "/api/v1/ai/narrate", {"view": "payments", "narrative": "One payment arrived.", "entered": 1,
                                          "left": 0, "values": "labels-only"})
    code, out, _ = cli(engine, "subscribe", "--view", "payments", "--narrate")
    assert code == EXIT_OK
    assert out.count("-- One payment arrived.") == 2
    bodies = [c["body"] for c in _Engine.calls if c["path"] == "/api/v1/ai/narrate"]
    assert bodies[0] == {"view": "payments", "changes": [{"weight": 1, "values": ["p4", 444]}]}
    answer("POST", "/api/v1/ai/narrate", {"code": "PRV-1080", "message": "PRV-1080  off", "helpUrl": "",
                                          "timestamp": "", "path": "/api/v1/ai/narrate"}, status=409)
    code, out, err = cli(engine, "subscribe", "--view", "payments", "--narrate")
    assert code == EXIT_OK and "p5" in out and err.count("no narrative: PRV-1080") == 1
