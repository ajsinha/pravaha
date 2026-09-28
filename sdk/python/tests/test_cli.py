"""The ``pravaha`` command line: parsing, output, exit codes, and every HTTP command.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The HTTP commands run against a local ``http.server`` that records each request and answers what
the test told it to, so what is pinned is the CLI's half: the right endpoint with the right
query string and body, the token where it belongs, what prints, and what the process exits with.
What each endpoint decides is pinned in ``pravaha-server``'s own tests.
"""

from __future__ import annotations

import io
import json
import os
import stat
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import Any, ClassVar

import pytest

from pravaha.cli import EXIT_OK, EXIT_REFUSED, EXIT_UNREACHABLE, EXIT_USAGE, main
from pravaha.cli._app import classify
from pravaha.cli._common import UsageError
from pravaha.cli._flight import coerce, parse_filters, weight_text
from pravaha.cli._output import cell, format_table
from pravaha.cli._settings import Settings, read_saved_token, save_token
from pravaha.errors import InvalidOptionsError, PravahaError
from pravaha.rest import ApiError


# ---------------------------------------------------------------------------------- harness


class _Engine(BaseHTTPRequestHandler):
    calls: ClassVar[list] = []
    answers: ClassVar[dict] = {}

    def log_message(self, *_: Any) -> None:
        pass

    def _answer(self, method: str) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8") if length else None
        _Engine.calls.append(
            {
                "method": method,
                "path": self.path,
                "authorization": self.headers.get("Authorization"),
                "body": json.loads(body) if body else None,
            }
        )
        status, payload = _Engine.answers.get((method, self.path.split("?")[0]), (200, {}))
        if status == 204:
            self.send_response(204)
            self.end_headers()
            return
        data = payload.encode("utf-8") if isinstance(payload, str) else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/plain" if isinstance(payload, str) else "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self) -> None:
        self._answer("GET")

    def do_POST(self) -> None:
        self._answer("POST")

    def do_PUT(self) -> None:
        self._answer("PUT")

    def do_PATCH(self) -> None:
        self._answer("PATCH")

    def do_DELETE(self) -> None:
        self._answer("DELETE")


@pytest.fixture
def engine():
    _Engine.calls = []
    _Engine.answers = {}
    server = HTTPServer(("127.0.0.1", 0), _Engine)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


@pytest.fixture
def home(tmp_path, monkeypatch):
    """A config directory of the test's own, and no token or URL leaking in from the machine."""
    for name in list(os.environ):
        if name.startswith("PRAVAHA_") or name == "NO_COLOR":
            monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("PRAVAHA_CONFIG_DIR", str(tmp_path / "config"))
    return tmp_path / "config"


def run(*argv: str) -> "tuple[int, str, str]":
    out, err = io.StringIO(), io.StringIO()
    code = main(list(argv), stdout=out, stderr=err)
    return code, out.getvalue(), err.getvalue()


def answer(method: str, path: str, payload: Any, status: int = 200) -> None:
    _Engine.answers[(method, path)] = (status, payload)


def last() -> dict:
    return _Engine.calls[-1]


# ---------------------------------------------------------------------------------- units


def test_a_table_aligns_every_column_to_its_widest_cell():
    lines = format_table(
        [{"name": "a", "state": "RUNNING"}, {"name": "longer_name", "state": None}],
        ["name", ("state", "STATE NOW")],
    )
    assert lines == [
        "NAME         STATE NOW",
        "a            RUNNING",
        "longer_name  -",
    ]


def test_a_cell_prints_nothing_as_a_dash_and_a_list_comma_joined():
    assert cell(None) == "-"
    assert cell("") == "-"
    assert cell(["a", "b"]) == "a,b"
    assert cell(True) == "yes"
    assert cell(3) == "3"


def test_filters_are_pairs_repeatable_and_comma_separated():
    assert parse_filters(["a=1,b=2", "c = x=y"]) == {"a": "1", "b": "2", "c": "x=y"}
    with pytest.raises(UsageError, match="column=value"):
        parse_filters(["nope"])


def test_a_parameter_is_an_integer_then_a_decimal_then_text():
    assert coerce("42") == 42
    assert coerce("4.5") == 4.5
    assert coerce("u1") == "u1"


def test_a_weight_is_always_signed():
    assert weight_text(1) == "+1"
    assert weight_text(-1) == "-1"


def test_each_failure_exits_with_its_own_code():
    assert classify(UsageError("x")) == EXIT_USAGE
    assert classify(InvalidOptionsError("x")) == EXIT_USAGE
    assert classify(ApiError(0, "nothing answered")) == EXIT_UNREACHABLE
    assert classify(ApiError(404, "no such view", "PRV-2003")) == EXIT_REFUSED
    assert classify(PravahaError(1040, "down", retryable=True)) == EXIT_UNREACHABLE
    assert classify(PravahaError(1041, "refused")) == EXIT_REFUSED
    assert classify(ValueError("two placeholders, one value")) == EXIT_USAGE


def test_a_flag_beats_the_environment_and_the_environment_beats_the_saved_token(home):
    import argparse

    save_token("from-file")
    env = {"PRAVAHA_CONFIG_DIR": str(home)}
    assert Settings.resolve(argparse.Namespace(), env).token == "from-file"
    env["PRAVAHA_TOKEN"] = "from-env"
    env["PRAVAHA_HTTP"] = "http://h:1/"
    resolved = Settings.resolve(argparse.Namespace(), env)
    assert (resolved.token, resolved.token_source, resolved.http) == ("from-env", "env", "http://h:1")
    flagged = Settings.resolve(argparse.Namespace(token="from-flag", http="http://f:2"), env)
    assert (flagged.token, flagged.token_source, flagged.http) == ("from-flag", "flag", "http://f:2")


def test_the_java_clis_http_variable_is_still_read(home):
    import argparse

    env = {"PRAVAHA_CONFIG_DIR": str(home), "PRAVAHA_ENGINE_HTTP": "http://old:18080"}
    assert Settings.resolve(argparse.Namespace(), env).http == "http://old:18080"


def test_a_saved_token_is_readable_by_its_owner_only(home):
    path = save_token("s3cret")
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    assert read_saved_token() == "s3cret"


# ---------------------------------------------------------------------------------- usage


def test_help_needs_no_engine_and_exits_zero(home):
    code, out, _ = run("register", "--help")
    assert code == EXIT_OK
    assert "--retain" in out and "--sql-file" in out


def test_no_command_and_an_unknown_one_are_usage_errors(home):
    assert run()[0] == EXIT_USAGE
    code, _, err = run("frobnicate")
    assert code == EXIT_USAGE
    assert "invalid choice" in err


def test_register_refuses_parameters_rather_than_dropping_them(home):
    code, _, err = run("register", "--name", "v", "--sql", "SELECT 1", "--params", "1")
    assert code == EXIT_USAGE
    assert "register takes no parameters" in err


def test_a_missing_required_flag_names_it(home):
    code, _, err = run("pause")
    assert code == EXIT_USAGE
    assert "--name is required" in err


def test_the_offline_commands_point_at_pravaha_engine(home):
    code, _, err = run("validate", "--sql", "SELECT 1", "--schema", "a:INT64", "--stream", "t")
    assert code == EXIT_USAGE
    assert "pravaha-engine validate" in err
    code, _, err = run("run", "--sql", "x", "--in", "a.csv")
    assert code == EXIT_USAGE
    assert "pravaha-engine run" in err


def test_global_options_are_taken_before_the_command_or_after_it(engine, home):
    answer("GET", "/api/v1/status", {"version": "9.9"})
    assert run("--http", engine, "status")[0] == EXIT_OK
    assert run("status", "--http", engine)[0] == EXIT_OK
    assert len(_Engine.calls) == 2


def test_a_token_is_not_sent_over_plaintext_unless_asked_for(engine, home):
    code, _, err = run("status", "--http", engine, "--token", "t")
    assert code == EXIT_USAGE
    assert "plaintext" in err
    assert _Engine.calls == []
    assert run("status", "--http", engine, "--token", "t", "--insecure-token")[0] == EXIT_OK
    assert last()["authorization"] == "Bearer t"


# ---------------------------------------------------------------------------------- refusals


def test_a_refusal_prints_the_engines_code_and_exits_one(engine, home):
    answer("GET", "/api/v1/queries/nope", {"code": "PRV-4001", "message": "no query 'nope'"}, 404)
    code, out, err = run("describe", "nope", "--http", engine)
    assert code == EXIT_REFUSED
    assert out == ""
    assert "PRV-4001  no query 'nope'" in err
    assert "PRV-4001" in err.splitlines()[-1]  # the help line names the code too


def test_a_refusal_in_json_mode_is_json_on_stderr(engine, home):
    answer("GET", "/api/v1/queries/nope", {"code": "PRV-4001", "message": "no query 'nope'"}, 404)
    code, out, err = run("describe", "--name", "nope", "--http", engine, "--json")
    assert code == EXIT_REFUSED
    assert json.loads(err) == {
        "error": {"code": "PRV-4001", "message": "no query 'nope'", "exit": 1, "status": 404}
    }


def test_an_engine_that_does_not_answer_exits_three_and_says_where_it_looked(home):
    code, _, err = run("status", "--http", "http://127.0.0.1:1", "--timeout", "2")
    assert code == EXIT_UNREACHABLE
    assert "PRV-1040" in err and "http://127.0.0.1:1" in err


# ---------------------------------------------------------------------------------- the node


def test_status_prints_the_node_and_its_plugins(engine, home):
    answer("GET", "/api/v1/status", {
        "instanceId": "n1", "version": "0.2.1", "engineState": "RUNNING", "uptimeSeconds": 5,
        "registeredQueries": 2, "streams": 1, "stoppedFeeds": 0, "flight": "grpc://h:19090",
        "plugins": [{"name": "kafka", "version": "1", "health": "UP", "detail": None}],
    })
    code, out, _ = run("status", "--http", engine)
    assert code == EXIT_OK
    assert "RUNNING" in out and "kafka" in out
    code, out, _ = run("status", "--http", engine, "--json")
    assert json.loads(out)["instanceId"] == "n1"


def test_health_answers_a_503_document_and_exits_one_when_down(engine, home):
    answer("GET", "/actuator/health", {"status": "UP"})
    assert run("health", "--http", engine)[0:2] == (EXIT_OK, "UP\n")
    answer("GET", "/actuator/health", {"status": "DOWN", "components": {"engine": {"status": "DOWN"}}}, 503)
    code, out, _ = run("health", "--http", engine)
    assert code == EXIT_REFUSED
    assert out.startswith("DOWN")


def test_version_reports_the_cli_and_the_node(engine, home):
    answer("GET", "/api/v1/status", {"version": "0.2.1"})
    code, out, _ = run("version", "--http", engine, "--json")
    assert code == EXIT_OK
    assert json.loads(out)["server"] == "0.2.1"
    code, out, _ = run("version", "--client")
    assert code == EXIT_OK and out.startswith("pravaha ")


def test_metrics_are_raw_and_can_be_narrowed(engine, home):
    answer("GET", "/actuator/prometheus", "pravaha_rows_in 5\njvm_threads 9\n")
    code, out, _ = run("metrics", "--http", engine, "--grep", "pravaha_")
    assert (code, out) == (EXIT_OK, "pravaha_rows_in 5\n")


def test_plugins_and_sinks_list(engine, home):
    answer("GET", "/api/v1/plugins", [{"name": "kafka", "version": "1", "kinds": ["source"],
                                        "compatible": True, "loaded": True,
                                        "health": {"state": "UP"}, "bindings": []}])
    answer("GET", "/api/v1/sinks", [{"name": "s", "plugin": "jdbc", "emitModes": ["UPSERT"]}])
    assert "kafka" in run("plugins", "--http", engine)[1]
    assert "jdbc" in run("sinks", "--http", engine)[1]


# ---------------------------------------------------------------------------------- catalogue


def test_streams_list_describe_and_declare(engine, home):
    answer("GET", "/api/v1/streams", [{"name": "txn", "version": 1, "fields": [
        {"name": "id", "type": "INT64", "nullable": False, "ordinal": 0}]}])
    code, out, _ = run("streams", "--http", engine)
    assert code == EXIT_OK and "txn" in out and "id INT64" in out
    answer("GET", "/api/v1/streams/txn", {"name": "txn", "fields": []})
    assert run("streams", "describe", "txn", "--http", engine)[0] == EXIT_OK
    assert last()["path"] == "/api/v1/streams/txn"
    answer("POST", "/api/v1/streams", {"name": "ev"})
    code, out, _ = run("streams", "declare", "ev", "--schema", "a:INT64,t:TIMESTAMP",
                       "--event-time", "t", "--out-of-orderness", "PT5S", "--http", engine)
    assert code == EXIT_OK and "declared ev" in out
    assert last()["body"] == {"name": "ev", "schema": "a:INT64,t:TIMESTAMP", "eventTime": "t",
                              "outOfOrderness": "PT5S"}


def test_views_are_the_registered_queries_and_one_is_described_by_name(engine, home):
    answer("GET", "/api/v1/queries", [{"name": "spend", "state": "RUNNING",
                                        "keyColumns": [{"name": "user_id", "ordinal": 0}]}])
    code, out, _ = run("views", "--http", engine)
    assert code == EXIT_OK and "spend" in out and "user_id" in out
    answer("GET", "/api/v1/views/spend", {"name": "spend", "schema": [], "keyColumns": []})
    assert run("views", "describe", "spend", "--http", engine)[0] == EXIT_OK
    assert last()["path"] == "/api/v1/views/spend"


def test_describe_shows_the_lane_and_escapes_the_name(engine, home):
    answer("GET", "/api/v1/queries/a%2Fb", {"name": "a/b", "state": "RUNNING", "lane": "shared",
                                             "sharedLane": 2, "sql": "SELECT 1"})
    code, out, _ = run("describe", "a/b", "--http", engine)
    assert code == EXIT_OK
    assert "shared #2" in out
    assert last()["path"] == "/api/v1/queries/a%2Fb"


def test_plan_prints_nodes_with_their_measurements(engine, home):
    answer("GET", "/api/v1/queries/q/plan", {
        "nodes": [{"id": "n0", "operator": "Scan", "stateful": False}],
        "edges": [{"from": "n0", "to": "n1"}],
        "operatorMetrics": {"n0": {"rowsIn": 10, "rowsOut": 7, "selfTimeShare": 0.5}},
        "bottleneck": "n0",
    })
    code, out, _ = run("plan", "q", "--http", engine)
    assert code == EXIT_OK
    assert "Scan" in out and "50%" in out and "bottleneck  n0" in out


def test_validate_against_the_node_exits_one_when_invalid(engine, home):
    answer("POST", "/api/v1/queries/validate", {"valid": False, "diagnostics": [
        {"code": "PRV-2050", "message": "unbounded", "range": {"startLine": 1, "startColumn": 8}}]})
    code, _, err = run("validate", "--sql", "SELECT x FROM t GROUP BY x", "--http", engine)
    assert code == EXIT_REFUSED
    assert "PRV-2050  unbounded (line 1, column 8)" in err
    answer("POST", "/api/v1/queries/explain", {"plan": "Scan(t)"})
    code, out, _ = run("explain", "--sql", "SELECT 1", "--level", "logical", "--http", engine)
    assert (code, out) == (EXIT_OK, "Scan(t)\n")
    assert "level=logical" in last()["path"]


# ---------------------------------------------------------------------------------- lanes


def test_lanes_lists_the_settings_and_every_querys_lane(engine, home):
    answer("GET", "/api/v1/lanes", {"mode": "auto", "autoFrom": 8, "maxQueriesPerLane": 4,
                                     "hosted": 3, "ownLaneQueries": 2, "dedicatedQueries": 1})
    answer("GET", "/api/v1/queries", [{"name": "q1", "state": "RUNNING", "lane": "own"}])
    code, out, _ = run("lanes", "--http", engine)
    assert code == EXIT_OK
    assert "mode auto, a lane each until 8" in out and "q1" in out


def test_a_rebalance_is_a_plan_unless_yes(engine, home):
    plan = {"mode": "auto", "room": 2, "running": False,
            "moves": [{"name": "q1", "fromSharedLane": 0, "status": "PLANNED"}]}
    answer("POST", "/api/v1/lanes/rebalance", plan)
    code, out, err = run("lanes", "rebalance", "--http", engine)
    assert code == EXIT_OK
    assert last()["path"] == "/api/v1/lanes/rebalance?dryRun=true"
    assert "q1" in out and "--yes" in err
    run("lanes", "rebalance", "--yes", "--http", engine)
    assert last()["path"] == "/api/v1/lanes/rebalance"
    answer("GET", "/api/v1/lanes/rebalance", {**plan, "moves": []})
    code, out, _ = run("lanes", "rebalance", "status", "--http", engine)
    assert last()["method"] == "GET" and "nothing to move" in out


# ---------------------------------------------------------------------------------- governance


def test_audit_sends_only_the_filters_given(engine, home):
    answer("GET", "/api/v1/audit", {"recording": True, "events": [
        {"sequence": 3, "principal": "bob", "action": "read", "decision": "deny"}],
        "nextCursor": "c2", "retained": 1, "capacity": 10, "evicted": 0})
    code, out, err = run("audit", "--principal", "bob", "--decision", "deny", "--limit", "5",
                         "--http", engine)
    assert code == EXIT_OK
    assert last()["path"] == "/api/v1/audit?principal=bob&decision=deny&limit=5"
    assert "bob" in out and "--cursor c2" in err


def test_tenants_and_permissions(engine, home):
    answer("GET", "/api/v1/tenants", {"scope": "all", "defaults": {"maxQueries": 5},
                                       "tenants": [{"tenant": "acme", "queries": 2, "limits": {}}]})
    code, out, _ = run("tenants", "--http", engine)
    assert code == EXIT_OK and "acme" in out and "no limit" in out
    answer("GET", "/api/v1/me/permissions", {
        "principal": "ann", "register": {"allowed": True},
        "readAudit": {"allowed": False, "reason": "not an auditor"},
        "views": [{"name": "spend", "read": "full", "administer": {"allowed": True}}]})
    code, out, _ = run("permissions", "--http", engine)
    assert code == EXIT_OK and "refused (not an auditor)" in out and "spend" in out


# ---------------------------------------------------------------------------------- identity


def test_login_prints_the_token_and_sends_none(engine, home):
    answer("POST", "/api/v1/auth/login", {"token": "tok-1", "expiresAt": "later"})
    code, out, _ = run("login", "--user", "ann", "--password", "pw", "--http", engine)
    assert (code, out) == (EXIT_OK, "tok-1\n")
    assert last()["body"] == {"username": "ann", "password": "pw"}
    assert last()["authorization"] is None


def test_login_save_writes_the_token_and_later_commands_send_it(engine, home):
    answer("POST", "/api/v1/auth/login", {"token": "tok-2", "expiresAt": "later"})
    code, out, _ = run("login", "--user", "ann", "--password", "pw", "--save", "--http", engine)
    assert code == EXIT_OK
    assert "tok-2" not in out
    path = home / "token"
    assert path.read_text().strip() == "tok-2"
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    answer("GET", "/api/v1/auth/me", {"username": "ann", "roles": ["admin"], "via": "session"})
    code, out, _ = run("whoami", "--http", engine, "--insecure-token")
    assert code == EXIT_OK and "ann" in out and "file" in out
    assert last()["authorization"] == "Bearer tok-2"


def test_logout_ends_the_session_and_deletes_the_file(engine, home):
    save_token("tok-3")
    answer("POST", "/api/v1/auth/logout", None, 204)
    code, out, _ = run("logout", "--http", engine, "--insecure-token")
    assert code == EXIT_OK and "session ended" in out
    assert last()["authorization"] == "Bearer tok-3"
    assert not (home / "token").exists()


def test_a_password_is_read_from_stdin_when_asked(engine, home, monkeypatch):
    monkeypatch.setattr("sys.stdin", io.StringIO("from-stdin\n"))
    answer("POST", "/api/v1/auth/login", {"token": "t"})
    run("login", "--user", "ann", "--password-stdin", "--http", engine)
    assert last()["body"]["password"] == "from-stdin"


def test_password_change_and_reset_redeem(engine, home):
    answer("POST", "/api/v1/auth/password", None, 204)
    code, out, _ = run("password", "--current", "a", "--new", "b", "--http", engine)
    assert code == EXIT_OK and last()["body"] == {"current": "a", "new": "b"}
    answer("POST", "/api/v1/auth/reset/redeem", None, 204)
    run("password", "--reset-token", "r1", "--new", "b", "--http", engine)
    assert last()["body"] == {"token": "r1", "password": "b"}


def test_user_administration(engine, home):
    answer("GET", "/api/v1/users", {"users": [{"username": "ann", "roles": ["admin"],
                                               "status": "active"}]})
    code, out, _ = run("user", "--http", engine)
    assert code == EXIT_OK and "ann" in out and "admin" in out
    answer("POST", "/api/v1/users", {"username": "bob"})
    run("user", "create", "bob", "--roles", "reader,writer", "--password", "pw", "--tenant", "t1",
        "--http", engine)
    assert last()["body"] == {"username": "bob", "roles": ["reader", "writer"], "password": "pw",
                              "tenant": "t1", "service": False}
    answer("PUT", "/api/v1/users/bob/roles", {})
    run("user", "roles", "bob", "--roles", "reader", "--http", engine)
    assert (last()["method"], last()["body"]) == ("PUT", {"roles": ["reader"]})
    answer("POST", "/api/v1/users/bob/password-reset", {"resetToken": "rt", "expiresAt": "x"})
    assert "rt" in run("user", "reset", "bob", "--http", engine)[1]


def test_disabling_a_user_needs_yes(engine, home):
    code, out, _ = run("user", "disable", "bob", "--http", engine)
    assert code == EXIT_OK and "would disable bob" in out
    assert _Engine.calls == []
    answer("PATCH", "/api/v1/users/bob", {})
    run("user", "disable", "bob", "--yes", "--http", engine)
    assert (last()["method"], last()["body"]) == ("PATCH", {"status": "disabled"})
    run("user", "enable", "bob", "--http", engine)
    assert last()["body"] == {"status": "active"}


def test_keys_are_issued_shown_once_rotated_reported_and_revoked_with_yes(engine, home):
    answer("GET", "/api/v1/keys", {"keys": [{"keyId": "k1", "name": "ci"}]})
    run("key", "list", "--all", "--http", engine)
    assert last()["path"] == "/api/v1/keys?all=true"
    answer("POST", "/api/v1/keys", {"key": "pk_secret", "keyId": "k2", "expiresAt": "x"})
    code, out, _ = run("key", "create", "ci", "--roles", "reader", "--days", "30", "--for", "svc",
                       "--http", engine)
    assert out == "pk_secret\n"
    assert last()["body"] == {"name": "ci", "roles": ["reader"], "expiresDays": 30, "forUser": "svc"}
    answer("POST", "/api/v1/keys/k2/rotate", {"key": "pk_next", "keyId": "k3", "oldExpiresAt": "y"})
    assert run("key", "rotate", "k2", "--http", engine)[1] == "pk_next\n"
    answer("GET", "/api/v1/keys/report", {"unused": [{"keyId": "k9"}], "expiring": [], "superseded": []})
    assert "k9" in run("key", "report", "--http", engine)[1]
    calls = len(_Engine.calls)
    assert "would revoke k2" in run("key", "revoke", "k2", "--http", engine)[1]
    assert len(_Engine.calls) == calls
    answer("DELETE", "/api/v1/keys/k2", None, 204)
    assert run("key", "revoke", "k2", "--yes", "--http", engine)[0] == EXIT_OK
    assert last()["method"] == "DELETE"


def test_sessions_list_and_end(engine, home):
    answer("GET", "/api/v1/sessions", {"sessions": [{"id": "s1", "username": "ann", "current": True}]})
    code, out, _ = run("session", "list", "--http", engine, "--json")
    assert json.loads(out) == [{"id": "s1", "username": "ann", "current": True}]
    answer("DELETE", "/api/v1/sessions/s1", None, 204)
    assert run("session", "end", "s1", "--http", engine)[1] == "ended s1\n"


def test_dlq_count_is_answered_over_http(engine, home):
    answer("GET", "/api/v1/queries/q/dead-letters/count", {"total": 4, "evicted": 0})
    code, out, _ = run("dlq", "count", "--name", "q", "--http", engine)
    assert code == EXIT_OK and "total" in out and "4" in out
