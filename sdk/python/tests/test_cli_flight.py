"""The ``pravaha`` Flight commands, against a Flight server and against the real engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Two layers. A small ``pyarrow.flight`` server written here answers Pravaha's control actions with
rows framed by the SDK's own ``_wire_encode`` -- so every Flight command runs end to end over a
real socket, including on a machine where the Java engine is not built. Then the fixture server
``test_client`` starts -- the engine's own Flight service -- drives the commands that matter most,
skipped exactly when that module is.
"""

from __future__ import annotations

import io
import json
import threading
from typing import Any

import pytest

pa = pytest.importorskip("pyarrow", reason="the Flight commands need the 'flight' extra")
flight = pytest.importorskip("pyarrow.flight")

from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_UNREACHABLE, EXIT_USAGE, main  # noqa: E402
from pravaha.client import _wire_decode, _wire_encode  # noqa: E402
# The engine's fixture server, shared rather than started a second way. Skips when unbuilt.
from test_client import TRADE_SQL, server  # noqa: E402,F401


def _replacement_row(name: str, state: str) -> "list[str]":
    row = [""] * 23
    row[0], row[1], row[2], row[3], row[4] = name, state, "SELECT 1", "cand", "old"
    row[11], row[10] = "true", "2026-09-28T12:00:00Z"
    row[12], row[14], row[15], row[16] = "120", "40", "2", "1"
    return row


def _list_row(**fields: str) -> "list[str]":
    order = ("name", "state", "sql", "fingerprint", "rows_in", "key_ordinals", "sink", "retention",
             "feed_state", "feed_code", "feed_message", "feed_where", "feed_at", "sink_state",
             "sink_code", "sink_message")
    return [fields.get(name, "") for name in order]


SESSION = ["dbg-1", "spend", "SELECT 1", "7", "ops", "t0", "t1", "0", "0", "0", "", "true", "txn"]


class _Engine(flight.FlightServerBase):
    """Answers each action with canned rows and records what it was asked."""

    def __init__(self) -> None:
        super().__init__("grpc://127.0.0.1:0")
        self.actions: list[tuple[str, list[str]]] = []

    # --- reads and subscriptions -------------------------------------------------------

    def get_flight_info(self, context: Any, descriptor: Any) -> Any:
        command = descriptor.command
        if b"nope" in command:
            raise flight.FlightServerError("PRV-4023 no view 'nope'; this server serves ['spend']")
        schema = pa.schema([("user_id", pa.string()), ("total", pa.int64())])
        endpoint = flight.FlightEndpoint(b"read", [])
        return flight.FlightInfo(schema, descriptor, [endpoint], -1, -1)

    def do_get(self, context: Any, ticket: Any) -> Any:
        if ticket.ticket == b"read":
            table = pa.table({"user_id": ["u1", "u2"], "total": [300, None]})
            return flight.RecordBatchStream(table)
        fields = _wire_decode(ticket.ticket)
        self.actions.append(("ticket", fields))
        weight = pa.field("__weight", pa.int64(), metadata={b"pravaha.weight": b"true"})
        schema = pa.schema([pa.field("user_id", pa.string()), weight])

        def batches():
            if fields[0].endswith(".snapshot"):
                snap = pa.record_batch([pa.array(["u1"]), pa.array([1])], schema=schema)
                yield snap, pa.py_buffer(b"pravaha:snapshot-end:5")
            change = pa.record_batch([pa.array(["u1", "u2"]), pa.array([-1, 1])], schema=schema)
            yield change, pa.py_buffer(b"pravaha:commit:6:0")

        return flight.GeneratorStream(schema, batches())

    # --- control actions ------------------------------------------------------------------

    def do_action(self, context: Any, action: Any) -> Any:
        fields = _wire_decode(action.body.to_pybytes())
        self.actions.append((action.type, fields))
        rows = self._rows(action.type, fields)
        return [flight.Result(pa.py_buffer(_wire_encode(row))) for row in rows]

    def _rows(self, kind: str, fields: "list[str]") -> "list[list[str]]":
        if kind == "pravaha.register":
            if fields[0] == "bad":
                raise flight.FlightServerError("PRV-2041 the sink only appends")
            return [[fields[0], "RUNNING", "3f9c2a61d0b4"]]
        if kind == "pravaha.list":
            return [
                _list_row(name="spend", state="RUNNING", fingerprint="fp1", rows_in="12", sink="t",
                          sink_state="DETACHED", sink_code="PRV-8009", sink_message="refused"),
                _list_row(name="spend_too", state="RUNNING", fingerprint="fp1", rows_in="-1"),
                _list_row(name="w10", state="RUNNING", fingerprint="fp2", rows_in="3",
                          feed_state="STOPPED", feed_code="PRV-5040", feed_where="ev#0"),
            ]
        if kind in ("pravaha.drop", "pravaha.pause", "pravaha.resume", "pravaha.debug.end"):
            return []
        if kind == "pravaha.replacement":
            return [_replacement_row(fields[0] if fields else "spend", "CAUGHT_UP")]
        if kind in ("pravaha.replace", "pravaha.cutover", "pravaha.finish", "pravaha.backfill"):
            return [_replacement_row(fields[0], "CUT_OVER" if kind != "pravaha.replace" else "BACKFILLING")]
        if kind == "pravaha.dlq.list":
            entry = ["id-1", "0", "txn", "line 3", "PRV-5001", "bad json", "t", "4", "YWJjZA==", ""]
            return [entry, ["#", "1", "4", "2", "10", "0", "0", "1 MiB", "true"]]
        if kind == "pravaha.dlq.show":
            return [["id-1", "0", "txn", "line 3", "PRV-5001", "bad json", "t", "4", "YWJjZA==", ""]]
        if kind == "pravaha.dlq.replay":
            return [["id-1", "REPLAYED", "a row now"], ["id-2", "FAILED_AGAIN", "still bad", "id-3"]]
        if kind == "pravaha.debug.session":
            return [SESSION]
        if kind == "pravaha.debug.fork":
            return [SESSION]
        if kind == "pravaha.debug.checkpoints":
            return [[fields[0], "9", "7"]]
        raise flight.FlightServerError(f"PRV-9999 the test server does not answer {kind}")


@pytest.fixture
def engine(monkeypatch, tmp_path):
    for name in ("PRAVAHA_URL", "PRAVAHA_TOKEN", "PRAVAHA_HTTP", "PRAVAHA_ENGINE_HTTP"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("PRAVAHA_CONFIG_DIR", str(tmp_path))
    server = _Engine()
    thread = threading.Thread(target=server.serve, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()


def run(server: Any, *argv: str) -> "tuple[int, str, str]":
    out, err = io.StringIO(), io.StringIO()
    code = main([*argv, "--url", f"grpc://127.0.0.1:{server.port}"], stdout=out, stderr=err)
    return code, out.getvalue(), err.getvalue()


def test_a_query_prints_an_aligned_table_a_tsv_or_json(engine):
    code, out, err = run(engine, "query", "--sql", "SELECT user_id, total FROM spend")
    assert code == EXIT_OK
    assert out.splitlines() == ["user_id  total", "u1       300", "u2       NULL"]
    assert "2 rows" in err
    code, out, _ = run(engine, "query", "--sql", "SELECT 1", "--tsv")
    assert out == "user_id\ttotal\nu1\t300\nu2\tNULL\n"
    code, out, _ = run(engine, "query", "--sql", "SELECT 1", "--json")
    assert json.loads(out) == [{"user_id": "u1", "total": 300}, {"user_id": "u2", "total": None}]


def test_a_refused_query_exits_one_with_the_engines_code(engine):
    code, out, err = run(engine, "query", "--sql", "SELECT * FROM nope")
    assert code == EXIT_REFUSED
    assert out == ""
    assert "PRV-4023" in err


def test_sql_is_read_from_a_file(engine, tmp_path):
    path = tmp_path / "q.sql"
    path.write_text("SELECT user_id FROM spend\n")
    assert run(engine, "query", "--sql-file", str(path))[0] == EXIT_OK
    assert run(engine, "query", "--sql-file", str(tmp_path / "missing.sql"))[0] == EXIT_USAGE


def test_register_sends_the_name_sql_keys_sink_and_retention(engine):
    code, out, _ = run(engine, "register", "--name", "spend", "--sql", "SELECT 1", "--keys", "0,2",
                       "--sink", "t", "--retain", "P7D")
    assert code == EXIT_OK
    assert "registered spend" in out and "fingerprint=3f9c2a61d0b4" in out
    assert engine.actions[-1] == ("pravaha.register", ["spend", "SELECT 1", "0,2", "t", "P7D"])
    code, _, err = run(engine, "register", "--name", "bad", "--sql", "SELECT 1", "--sink", "t")
    assert code == EXIT_REFUSED and "PRV-2041" in err


def test_queries_lists_and_says_why_a_source_stopped_or_a_sink_detached(engine):
    code, out, err = run(engine, "queries", "--verbose")
    assert code == EXIT_OK
    lines = out.splitlines()
    assert lines[0].split() == ["NAME", "STATE", "FINGERPRINT", "ROWS", "IN", "SINK", "FEED"]
    assert "t (detached)" in out and "RUNNING (source stopped)" in out
    assert "w10: source stopped with PRV-5040 reading ev#0" in err
    assert "spend: sink 't' detached with PRV-8009: refused" in err
    code, out, _ = run(engine, "queries", "--json")
    assert [q["name"] for q in json.loads(out)] == ["spend", "spend_too", "w10"]


def test_pause_and_resume_act_and_drop_needs_yes(engine):
    assert run(engine, "pause", "--name", "spend")[1] == "paused spend\n"
    assert engine.actions[-1] == ("pravaha.pause", ["spend"])
    code, out, _ = run(engine, "drop", "--name", "spend")
    assert code == EXIT_OK
    assert "would drop spend" in out and "shared with spend_too" in out
    assert all(kind != "pravaha.drop" for kind, _ in engine.actions)
    code, out, _ = run(engine, "drop", "--name", "spend", "--yes")
    assert (code, out) == (EXIT_OK, "dropped spend\n")
    assert engine.actions[-1] == ("pravaha.drop", ["spend"])
    assert run(engine, "drop", "--name", "ghost")[0] == EXIT_REFUSED


def test_a_replacement_is_started_moved_listed_and_finished_with_yes(engine):
    code, out, err = run(engine, "replace", "--name", "spend", "--sql", "SELECT 2",
                         "--backfill", "none", "--rate-limit", "100")
    assert code == EXIT_OK and "backfilling spend" in out and "pravaha cutover" in err
    assert engine.actions[-1] == ("pravaha.replace",
                                  ["spend", "SELECT 2", "0", "backfill=none;backfill.rate.limit=100"])
    assert "cut over spend" in run(engine, "cutover", "--name", "spend")[1]
    run(engine, "throttle", "--name", "spend", "--rate", "50")
    assert engine.actions[-1] == ("pravaha.backfill", ["spend", "throttle", "50"])
    code, out, _ = run(engine, "replacements")
    assert "CAUGHT_UP" in out and "1/2" in out
    code, out, _ = run(engine, "finish", "--name", "spend")
    assert "would finish" in out and engine.actions[-1][0] == "pravaha.replacement"
    run(engine, "finish", "--name", "spend", "--yes")
    assert engine.actions[-1] == ("pravaha.finish", ["spend"])


def test_subscribe_prints_the_snapshot_then_each_commit_with_signed_weights(engine):
    code, out, err = run(engine, "subscribe", "--view", "spend", "--snapshot",
                         "--filter", "user_id=u1")
    assert code == EXIT_OK
    assert out.splitlines() == [
        "WEIGHT\tuser_id",
        "+1\tu1",
        "-- snapshot at frontier 5, 1 row",
        "-1\tu1",
        "+1\tu2",
        "-- commit, 2 rows",
    ]
    assert "subscribing to spend" in err
    assert engine.actions[-1] == ("ticket", ["subscribe.snapshot", "spend", "user_id", "u1"])


def test_subscribe_answer_asks_for_the_answer_on_the_ticket(engine):
    # SUBANSWERWIRE-1: --answer is the answer's own verb; with --snapshot, its snapshot form.
    code, _, err = run(engine, "subscribe", "--view", "spend", "--answer")
    assert code == EXIT_OK
    assert "(its answer)" in err
    assert engine.actions[-1] == ("ticket", ["subscribe.answer", "spend"])
    run(engine, "subscribe", "--view", "spend", "--answer", "--snapshot")
    assert engine.actions[-1] == ("ticket", ["subscribe.answer.snapshot", "spend"])


def test_subscribe_as_json_lines_and_with_a_limit(engine):
    code, out, _ = run(engine, "subscribe", "--view", "spend", "--json", "--limit", "1")
    lines = [json.loads(line) for line in out.splitlines()]
    assert lines[0] == {"type": "change", "weight": -1, "row": {"user_id": "u1"}}
    assert lines[-1]["type"] == "commit" and lines[-1]["rows"] == 2
    assert run(engine, "subscribe", "--view", "spend", "--filter", "oops")[0] == EXIT_USAGE


def test_dead_letters_list_show_and_replay(engine):
    code, out, _ = run(engine, "dlq", "list", "--name", "spend")
    assert code == EXIT_OK
    assert "id-1" in out and "bad json" in out and "retention 1 MiB" in out
    code, out, _ = run(engine, "dlq", "show", "--name", "spend", "--id", "id-1")
    assert out.splitlines()[-1] == "abcd"
    code, out, err = run(engine, "dlq", "replay", "--name", "spend", "--id", "id-1,id-2")
    assert code == EXIT_REFUSED  # one failed again
    assert "replayed id-1" in out and "id-2 -> back on the queue as id-3" in err
    assert engine.actions[-1] == ("pravaha.dlq.replay", ["spend", "id-1", "id-2"])


def test_the_debugger_forks_lists_and_ends(engine):
    code, out, _ = run(engine, "debug", "fork", "--name", "spend", "--checkpoint", "7")
    assert (code, out) == (EXIT_OK, "dbg-1\n")
    assert engine.actions[-1] == ("pravaha.debug.fork", ["spend", "7"])
    assert run(engine, "debug", "checkpoints", "--name", "spend")[1] == "9\n7\n"
    assert "dbg-1" in run(engine, "debug", "sessions")[1]
    assert run(engine, "debug", "end", "--session", "dbg-1")[1] == "ended dbg-1\n"


def test_nothing_listening_exits_three():
    out, err = io.StringIO(), io.StringIO()
    code = main(["queries", "--url", "grpc://127.0.0.1:1", "--timeout", "2"], stdout=out, stderr=err)
    assert code == EXIT_UNREACHABLE
    assert "grpc://127.0.0.1:1" in err.getvalue()


# ---------------------------------------------------------------------------------- the real engine


@pytest.fixture
def real(server):
    """The engine's own Flight fixture server, started by test_client; skipped when it is."""
    port, _ = server
    return port


def _real(port: int, *argv: str) -> "tuple[int, str, str]":
    out, err = io.StringIO(), io.StringIO()
    code = main([*argv, "--url", f"grpc://localhost:{port}"], stdout=out, stderr=err)
    return code, out.getvalue(), err.getvalue()


def test_against_the_engine_a_query_and_a_registration_round_trip(real):
    code, out, _ = _real(real, "query", "--sql",
                         "SELECT user_id, total FROM user_volume WHERE total > ?", "--params", "40",
                         "--json")
    assert code == EXIT_OK
    assert sorted((r["user_id"], r["total"]) for r in json.loads(out)) == [("u1", 300), ("u2", 50)]
    assert _real(real, "register", "--name", "cli_feed", "--sql", TRADE_SQL)[0] == EXIT_OK
    assert "cli_feed" in _real(real, "queries")[1]
    assert _real(real, "drop", "--name", "cli_feed", "--yes")[0] == EXIT_OK
    code, _, err = _real(real, "drop", "--name", "cli_feed", "--yes")
    assert code == EXIT_REFUSED and "PRV-8002" in err
