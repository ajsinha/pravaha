"""The ``--json`` shapes and exit codes scripts rely on, pinned so that changing one is deliberate.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

Two kinds of shape. Most HTTP commands print the engine's own document unchanged (``status``,
``health``, ``whoami``, ``describe``, ``streams``, ``views describe``): what is pinned is that the CLI
does not reshape it, and the keys the engine's records declare (``ApiDtos.java``,
``IdentityController.Me``), as the docs list them. The rest are the CLI's own: ``queries`` (the SDK's
``RegisteredQuery``, snake_case), ``views list``, ``version``, ``doctor`` and the error object. If a
test here fails, update the console's CLI reference ("JSON output") in the same change.
"""

from __future__ import annotations

import dataclasses
import json

import pytest

from pravaha.cli import EXIT_INTERRUPTED, EXIT_OK, EXIT_REFUSED, EXIT_UNREACHABLE, EXIT_USAGE
from pravaha.cli._settings import Settings
from pravaha.records import FeedStop, RegisteredQuery, SinkFailure
from test_cli import answer, engine, home, run  # noqa: F401

NODE_STATUS = {"instanceId": "node-1", "version": "2.3.1", "engineState": "RUNNING",
               "uptimeSeconds": 5321, "registeredQueries": 4, "plugins": [], "streams": 2,
               "stoppedFeeds": 0, "flight": "grpc://node-1:19090"}
ME = {"username": "ann", "principal": "ann", "tenant": "acme", "roles": ["analyst"],
      "via": "session", "displayName": "Ann", "email": None, "mustChangePassword": False,
      "passwordExpiresAt": None}
STREAM = {"name": "trades", "version": 1, "fieldCount": 1,
          "fields": [{"name": "id", "type": "BIGINT", "nullable": False, "ordinal": 0}],
          "eventTime": "ts", "outOfOrderness": "PT10S", "source": None, "allowedLateness": None}
QUERY = {"name": "spend", "state": "RUNNING", "sql": "SELECT 1", "fingerprint": "fp1",
         "sharedWith": [], "keyColumns": [{"name": "user_id", "ordinal": 0}], "retention": "P7D",
         "sink": None, "rowsIn": 12, "countsWithheld": False, "registeredAt": None, "failure": None,
         "reads": ["trades"], "feed": {"state": "RUNNING", "sources": []}, "execution": [],
         "lane": "shared", "sharedLane": 0, "readsFrom": [], "dependants": [],
         "accessPaths": None, "owner": "ann", "checkpoint": None}
VIEW = {"name": "spend", "schema": [], "keyColumns": [{"name": "user_id", "ordinal": 0}],
        "retention": "P7D", "sink": None, "fingerprint": "fp1"}


@pytest.mark.parametrize("argv, path, document", [
    (("status",), "/api/v1/status", NODE_STATUS),
    (("health",), "/actuator/health", {"status": "UP", "components": {"engine": {"status": "UP"}}}),
    (("whoami",), "/api/v1/auth/me", ME),
    (("describe", "spend"), "/api/v1/queries/spend", QUERY),
    (("streams", "list"), "/api/v1/streams", [STREAM]),
    (("streams", "describe", "trades"), "/api/v1/streams/trades", STREAM),
    (("views", "describe", "spend"), "/api/v1/views/spend", VIEW),
])
def test_the_engines_document_is_printed_unchanged(engine, home, argv, path, document):
    answer("GET", path, document)
    code, out, err = run(*argv, "--http", engine, "--json")
    assert code == EXIT_OK, err
    assert json.loads(out) == document


def test_views_list_is_the_clis_own_rows(engine, home):
    answer("GET", "/api/v1/queries", [QUERY])
    _, out, _ = run("views", "--http", engine, "--json")
    rows = json.loads(out)
    assert [sorted(r) for r in rows] == [["fingerprint", "key", "name", "retention", "sink", "state"]]
    assert rows[0]["key"] == "user_id"


def test_version_is_cli_server_and_server_error(engine, home):
    answer("GET", "/api/v1/status", NODE_STATUS)
    _, out, _ = run("version", "--http", engine, "--json")
    assert json.loads(out) == {"cli": json.loads(out)["cli"], "server": "2.3.1", "serverError": None}


def test_queries_is_a_list_of_registered_queries_in_snake_case(home, monkeypatch):
    listed = [RegisteredQuery("spend", "RUNNING", "SELECT 1", "fp1", 12, (0,), "out", "P7D",
                              "STOPPED", FeedStop("PRV-5040", "bad record", "ev#0", "t0"),
                              "DETACHED", SinkFailure("PRV-8009", "refused"), "ann")]

    class Client:
        def queries(self):
            return listed

        def close(self):
            pass

    monkeypatch.setattr(Settings, "client", lambda self: Client())
    code, out, _ = run("queries", "--json")
    assert code == EXIT_OK
    row = json.loads(out)[0]
    assert list(row) == ["name", "state", "sql", "fingerprint", "rows_in", "key_columns", "sink",
                         "retention", "feed", "feed_stop", "sink_state", "sink_failure", "owner"]
    assert [f.name for f in dataclasses.fields(RegisteredQuery)] == list(row)
    assert row["feed_stop"] == {"code": "PRV-5040", "message": "bad record", "where": "ev#0",
                                "at": "t0"}
    assert row["sink_failure"] == {"code": "PRV-8009", "message": "refused"}
    assert row["key_columns"] == [0]


def test_doctor_is_a_list_of_name_status_detail_fix(home):
    _, out, _ = run("doctor", "--http", "http://127.0.0.1:1", "--url", "grpc://127.0.0.1:1",
                    "--timeout", "2", "--json")
    assert {tuple(sorted(c)) for c in json.loads(out)} == {("detail", "fix", "name", "status")}


# ---------------------------------------------------------------------------------- failures


def test_a_refusal_is_one_json_error_on_stderr(engine, home):
    answer("GET", "/api/v1/queries/x", {"code": "PRV-8002", "message": "no query x"}, 404)
    code, out, err = run("describe", "x", "--http", engine, "--json")
    assert code == EXIT_REFUSED and out == ""
    assert json.loads(err) == {"error": {"code": "PRV-8002", "message": "no query x", "exit": 1,
                                         "status": 404}}


def test_unreachable_usage_and_interrupted_codes(home, monkeypatch):
    code, _, err = run("status", "--http", "http://127.0.0.1:1", "--json")
    assert code == EXIT_UNREACHABLE == 3
    assert set(json.loads(err)["error"]) == {"code", "message", "exit", "status"}
    code, _, err = run("describe", "--json")
    assert code == EXIT_USAGE == 2
    assert json.loads(err) == {"error": {"code": None, "message": "the query's name is required",
                                         "exit": 2}}

    def interrupted(self):
        raise KeyboardInterrupt

    monkeypatch.setattr(Settings, "api", interrupted)
    assert run("status")[0] == EXIT_INTERRUPTED == 130


def test_a_missing_pyarrow_is_usage_and_a_json_error_under_json(home, monkeypatch):
    def no_pyarrow(self):
        raise ImportError("No module named 'pyarrow'", name="pyarrow")

    monkeypatch.setattr(Settings, "client", no_pyarrow)
    code, out, err = run("queries", "--json")
    assert code == EXIT_USAGE and out == ""
    error = json.loads(err)["error"]
    assert error["exit"] == 2 and 'pip install "pravaha[flight]"' in error["message"]
    code, _, err = run("queries")
    assert code == EXIT_USAGE and 'pip install "pravaha[flight]"' in err


def test_another_missing_module_is_named_not_mistaken_for_pyarrow(home, monkeypatch):
    def no_module(self):
        raise ImportError("No module named 'grpc_tools'", name="grpc_tools")

    monkeypatch.setattr(Settings, "client", no_module)
    code, _, err = run("queries")
    assert code == EXIT_USAGE and "grpc_tools" in err and "pyarrow" not in err
