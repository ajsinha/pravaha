"""The persona surfaces, against a stand-in for the engine adapter.

``tests/test_console.py`` drives the console against a real engine, and that is where the
claim "the console works" is proven end to end. This file covers what those tests cannot
reach without a Java build: every new route and JSON endpoint, the sign-in gate on each,
the engine being down, and the pure logic underneath -- the Prometheus parser, the health
verdict, the client snippets, the plan graph, the diagnostics and their fixes.

The stand-in replaces exactly one object, ``core.engine.Engine``, the only thing that
touches the SDK or the engine's HTTP API. Everything above it -- services, routes,
templates, the session gate -- is the real console, built by the real ``create_app``.
"""
from __future__ import annotations

import json
import math
import pathlib
import re
import sys

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO_ROOT = CONSOLE_ROOT.parent
sys.path.insert(0, str(CONSOLE_ROOT))

from core import authoring, metrics, snippets
from core.config.properties_configurator import PropertiesConfigurator
from core.content.codes import lookup as code_lookup
from core.engine import EngineHttpError, QueryRow
from run_pravaha_web import create_app

PASSWORD = "product-test-password"
ENGINE_TOKEN = "s3cret-engine-token-must-never-reach-a-browser"
SESSION_SECRET = "s3cret-session-key-must-never-reach-a-browser"

SINK_SECRET = "jdbc-password-that-the-engine-never-publishes"

TXN = {"name": "txn", "version": 1, "fieldCount": 4, "eventTime": "event_time",
       "outOfOrderness": "PT10S", "source": "filesystem", "fields": [
    {"name": "txn_id", "type": "BIGINT", "nullable": False, "ordinal": 0},
    {"name": "user_id", "type": "VARCHAR", "nullable": False, "ordinal": 1},
    {"name": "amount", "type": "BIGINT", "nullable": False, "ordinal": 2},
    {"name": "event_time", "type": "TIMESTAMP(3)", "nullable": False, "ordinal": 3},
]}

PROMETHEUS = """\
# HELP pravaha_query_rows_in
# TYPE pravaha_query_rows_in gauge
pravaha_query_rows_in{query="big_txn"} 1200.0
pravaha_query_rows_in{query="hot"} 50.0
pravaha_query_state_fraction{query="big_txn"} 0.2
pravaha_query_state_fraction{query="hot"} 0.95
pravaha_query_state_held{query="hot"} 950.0
pravaha_query_state_ceiling{query="hot"} 1000.0
pravaha_query_view_size{query="big_txn"} 17.0
pravaha_query_watermark_lag_seconds{query="big_txn"} NaN
pravaha_query_watermark_lag_seconds{query="hot"} 1200.0
pravaha_query_running{query="big_txn"} 1.0
pravaha_query_running{query="hot"} 1.0
pravaha_query_subscribers{query="big_txn"} 3.0
pravaha_query_checkpoint_last_success_timestamp_seconds{query="big_txn"} NaN
pravaha_query_checkpoint_failures_total{query="big_txn"} 0.0
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1048576.0
jvm_memory_max_bytes{area="heap",id="G1 Old Gen"} 4194304.0
process_uptime_seconds 42.5
this line is not a sample
"""


class FakeEngine:
    """Engine's public surface, answered from memory. ``down`` makes every call fail."""

    def __init__(self, down: bool = False) -> None:
        self.url = "grpc://engine.test:9090"
        self.http_url = "http://engine.test:8080"
        self.down = down
        self.registered: list[dict] = []
        self.queries_seen: list[tuple[str, list | None]] = []
        self.rows = [[1, "u1", 150], [2, "u2", 900]]
        self.streams_list = [dict(TXN)]
        self.metrics_text = PROMETHEUS
        self.sinks_list = [
            {"name": "audit_out", "plugin": "filesystem",
             "fields": [{"name": "txn_id", "type": "INT64", "nullable": False, "ordinal": 0}],
             "keyColumns": [], "emitModes": ["APPEND"], "acceptsRetractions": False,
             "guarantee": "AT_LEAST_ONCE", "writers": ["big_txn"], "problem": None},
            {"name": "broken_out", "plugin": "nope", "fields": [], "keyColumns": [], "emitModes": [],
             "acceptsRetractions": False, "guarantee": None, "writers": [],
             "problem": {"code": "PRV-5093", "message": "the 'nope' plugin could not describe this sink",
                         "helpUrl": ""}},
        ]
        self._queries = [
            QueryRow("big_txn", "RUNNING", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 100",
                     "abc123def456", 1200, (0,), "audit_out", "PT24H"),
            QueryRow("hot", "RUNNING", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "fff000", 50),
            QueryRow("hot_alias", "PAUSED", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "fff000", 50),
        ]
        for q in self._queries:
            object.__setattr__(q, "_shared", q.fingerprint == "fff000")

    def _check(self):
        if self.down:
            raise EngineHttpError(0, "the engine's HTTP API at http://engine.test:8080 did not answer")

    # Flight half
    def health(self):
        if self.down:
            return {"reachable": False, "url": self.url, "error": "connection refused"}
        return {"reachable": True, "url": self.url, "queries": len(self._queries)}

    def queries(self):
        if self.down:
            raise ConnectionError("connection refused")
        return list(self._queries)

    def register(self, name, sql, keys, sink=None, retention=None):
        self._check()
        self.registered.append({"name": name, "sql": sql, "keys": list(keys), "sink": sink,
                                "retention": retention})
        return QueryRow(name, "RUNNING", sql, "newfp", 0, tuple(keys), sink, retention)

    def lifecycle(self, action, name):
        self._check()

    def query(self, sql, parameters=None):
        columns, rows, _ = self.query_typed(sql, parameters)
        return columns, rows

    def query_typed(self, sql, parameters=None):
        self._check()
        self.queries_seen.append((sql, parameters))
        return ["txn_id", "user_id", "amount"], [list(r) for r in self.rows], ["int64", "string", "int64"]

    def tail(self, view, filters=None):
        yield {"txn_id": 1, "user_id": "u1", "amount": 150, "_weight": 1}
        yield {"txn_id": 1, "user_id": "u1", "amount": 150, "_weight": -1}

    # REST half
    def streams(self):
        self._check()
        return list(self.streams_list)

    def declare_stream(self, name, schema, event_time=None, out_of_orderness=None):
        self._check()
        fields = [{"name": p.split(":")[0], "type": p.split(":")[1], "nullable": True, "ordinal": i}
                  for i, p in enumerate(schema.split(","))]
        stream = {"name": name, "version": 1, "fieldCount": len(fields), "fields": fields,
                  "eventTime": event_time, "outOfOrderness": out_of_orderness if event_time else None,
                  "source": None}
        self.streams_list.append(stream)
        return stream

    def validate(self, sql):
        self._check()
        # Positions as the engine sends them: from the parser, 1-based, end column inclusive.
        for word, code in (("txm", "PRV-2003"), ("amout", "PRV-2002")):
            if word in sql:
                return {"valid": False, "diagnostics": [{"code": code, "severity": "error",
                        "message": f"'{word}' is not known here",
                        "helpUrl": f"https://docs.pravaha.io/errors/{code}",
                        "range": _range_of(sql, word)}],
                        "outputFields": [], "elapsedMicros": 900}
        if "PLANONLY" in sql:
            # A refusal about the plan, not the text: the engine gives no position.
            return {"valid": False, "diagnostics": [{"code": "PRV-2050", "severity": "error",
                    "message": "an unwindowed COUNT(DISTINCT) is refused", "helpUrl": ""}],
                    "outputFields": [], "elapsedMicros": 500}
        return {"valid": True, "diagnostics": [], "elapsedMicros": 1234, "outputFields": [
            {"name": "txn_id", "type": "BIGINT", "nullable": False, "ordinal": 0},
            {"name": "user_id", "type": "VARCHAR", "nullable": False, "ordinal": 1},
            {"name": "amount", "type": "BIGINT", "nullable": False, "ordinal": 2}]}

    def explain(self, sql, level="physical"):
        self._check()
        return {"level": level, "plan": "Project(txn_id, user_id)\n  Filter(amount > 100)\n    Scan(txn)\n",
                "outputFields": [], "graph": dict(PLAN_GRAPH)}

    def sinks(self):
        self._check()
        return [dict(s) for s in self.sinks_list]

    def describe_queries(self):
        self._check()
        return [self.describe_query(q.name) for q in self._queries]

    def describe_query(self, name):
        self._check()
        for q in self._queries:
            if q.name == name:
                return {"name": q.name, "state": q.state, "sql": q.sql, "fingerprint": q.fingerprint,
                        "sharedWith": [o.name for o in self._queries
                                       if o.fingerprint == q.fingerprint and o.name != q.name],
                        "keyColumns": [{"name": "txn_id", "ordinal": 0}], "retention": q.retention or "forever",
                        "sink": ({"name": q.sink, "attached": False,
                                  "failure": {"code": "PRV-8009", "message": "sink 'audit_out' failed and has been detached",
                                              "helpUrl": ""}, "rowsWritten": 7} if q.sink else None),
                        "rowsIn": q.rows_in, "countsWithheld": False, "registeredAt": "2026-09-19T00:00:00Z",
                        "failure": None, "reads": ["txn"]}
        raise EngineHttpError(404, f"no registered query named '{name}' that you may see", "PRV-8002")

    def query_plan(self, name):
        self._check()
        self.describe_query(name)
        return dict(PLAN_GRAPH, query={"rowsIn": 1200, "stateHeld": 3, "stateCeiling": 100,
                                       "viewSize": 17, "watermark": None, "subscribers": 2})

    def describe_view(self, name):
        self._check()
        detail = self.describe_query(name)
        return {"name": name, "schema": [
            {"name": "txn_id", "type": "BIGINT", "nullable": False, "ordinal": 0},
            {"name": "user_id", "type": "VARCHAR", "nullable": False, "ordinal": 1},
            {"name": "amount", "type": "BIGINT", "nullable": False, "ordinal": 2}],
            "keyColumns": detail["keyColumns"], "retention": detail["retention"],
            "sink": detail["sink"]["name"] if detail["sink"] else None, "fingerprint": detail["fingerprint"]}

    def status(self):
        self._check()
        return {"instanceId": "n1", "version": "0.1.0", "engineState": "RUNNING", "uptimeSeconds": 5,
                "registeredQueries": 3, "plugins": [{"name": "filesystem", "version": "1", "health": "UP",
                                                     "detail": ""}]}

    def prometheus(self):
        self._check()
        return self.metrics_text


PLAN_GRAPH = {
    "nodes": [
        {"id": "n0", "operator": "Project", "detail": "Project(txn_id, user_id)", "stateful": False,
         "fields": ["txn_id", "user_id"]},
        {"id": "n1", "operator": "Filter", "detail": "Filter(amount > 100)", "stateful": False,
         "fields": ["txn_id", "user_id", "amount"]},
        {"id": "n2", "operator": "Scan", "detail": "Scan(txn)", "stateful": False,
         "fields": ["txn_id", "user_id", "amount"]},
    ],
    "edges": [{"from": "n1", "to": "n0"}, {"from": "n2", "to": "n1"}],
    "operatorMetrics": None,
    "metricsNote": "Per-operator rows, state and watermarks are not published.",
    "query": None,
}


def _range_of(sql: str, word: str) -> dict:
    for number, line in enumerate(sql.splitlines(), start=1):
        at = line.find(word)
        if at >= 0:
            return {"startLine": number, "startColumn": at + 1, "endLine": number,
                    "endColumn": at + len(word)}
    raise AssertionError(word)


def _app(engine: FakeEngine, **overrides):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.password", PASSWORD)
    config.set("console.session_secret", SESSION_SECRET)
    config.set("engine.token", ENGINE_TOKEN)
    config.set("ui.default_role", overrides.get("default_role", "operator"))
    return fastapi_testclient.TestClient(create_app(config, engine=engine))


@pytest.fixture
def engine():
    return FakeEngine()


@pytest.fixture
def signed_in(engine):
    client = _app(engine)
    client.post("/login", data={"password": PASSWORD, "next": "/home"})
    return client


@pytest.fixture
def anonymous(engine):
    return _app(engine)


@pytest.fixture
def engine_down():
    fake = FakeEngine(down=True)
    client = _app(fake)
    client.post("/login", data={"password": PASSWORD, "next": "/home"})
    return client


NEW_PAGES = ["/home", "/start", "/catalog", "/catalog?tab=queries", "/catalog?tab=sinks",
             "/catalog/streams/txn", "/views", "/views/big_txn", "/views/big_txn?key=user_id&value=u1",
             "/views/big_txn/live", "/operations", "/workbench", "/workbench?query=big_txn",
             "/workbench?template=tumble&stream=txn"]

NEW_JSON_GETS = ["/api/v1/me", "/api/v1/catalog/streams", "/api/v1/catalog/streams/txn",
                 "/api/v1/catalog/completions", "/api/v1/catalog/templates?stream=txn",
                 "/api/v1/views/big_txn/schema", "/api/v1/views/big_txn/snippets?key=user_id&value=u1",
                 "/api/v1/catalog/sinks", "/api/v1/views/big_txn",
                 "/api/v1/ops/snapshot", "/api/v1/ops/series?metric=rows_in", "/api/v1/ops/stream"]

NEW_JSON_POSTS = [("/api/v1/sql/validate", {"sql": "SELECT * FROM txn"}),
                  ("/api/v1/sql/explain", {"sql": "SELECT * FROM txn"}),
                  ("/api/v1/views/big_txn/lookup", {"filters": {"user_id": "u1"}}),
                  ("/api/v1/catalog/streams", {"name": "orders", "schema": "id:INT64"})]


# ============================================================ the gate, on every new surface

@pytest.mark.parametrize("path", NEW_PAGES)
def test_every_new_page_sends_an_anonymous_visitor_to_sign_in(anonymous, path):
    refused = anonymous.get(path, follow_redirects=False)
    assert refused.status_code == 303, path
    assert refused.headers["location"].startswith("/login"), path


@pytest.mark.parametrize("path", NEW_JSON_GETS)
def test_every_new_json_read_refuses_an_anonymous_caller_with_401(anonymous, path):
    refused = anonymous.get(path)
    assert refused.status_code == 401, path
    assert "sign in" in refused.json()["error"]


@pytest.mark.parametrize("path,body", NEW_JSON_POSTS)
def test_every_new_json_write_refuses_an_anonymous_caller_with_401(anonymous, engine, path, body):
    refused = anonymous.post(path, json=body)
    assert refused.status_code == 401, path
    assert engine.registered == []


def test_the_role_preference_cannot_be_set_anonymously(anonymous):
    refused = anonymous.post("/preferences/role", data={"role": "analyst"}, follow_redirects=False)
    assert refused.status_code == 303 and refused.headers["location"].startswith("/login")


def test_the_error_code_pages_are_public_like_the_rest_of_the_documentation(anonymous):
    page = anonymous.get("/help/codes/PRV-2050")
    assert page.status_code == 200
    assert "PRV-2050" in page.text and "SQL_UNBOUNDED_STATE" in page.text
    assert "GROUP BY" in page.text  # the section TROUBLESHOOTING.md writes about it


def test_a_malformed_error_code_is_a_404(anonymous):
    assert anonymous.get("/help/codes/not-a-code").status_code == 404
    assert anonymous.get("/help/codes/PRV-12").status_code == 404


def test_the_palette_offers_an_anonymous_visitor_only_public_pages(anonymous):
    body = anonymous.get("/api/v1/palette").json()
    assert body["signed_in"] is False
    assert all(item["kind"] == "page" for item in body["items"])
    text = json.dumps(body)
    assert "big_txn" not in text and "txn" not in text.replace("Tutorials", "")


# ============================================================ the pages, signed in

@pytest.mark.parametrize("path", [p for p in NEW_PAGES if p != "/home"])
def test_every_new_page_renders_for_a_signed_in_person(signed_in, path):
    page = signed_in.get(path)
    assert page.status_code == 200, path
    assert "<main" in page.text


@pytest.mark.parametrize("path", [p for p in NEW_PAGES if p != "/home"])
def test_every_new_page_renders_with_the_engine_down(engine_down, path):
    page = engine_down.get(path)
    # A page about something that cannot be looked up is a clear 404/503; everything else
    # is a 200 that says what is missing. Never a 500.
    assert page.status_code in {200, 404, 503}, (path, page.status_code)
    assert "engine unreachable" in page.text


def test_pages_render_their_data_before_any_script_runs(signed_in):
    assert "txn_id" in signed_in.get("/catalog").text
    assert "big_txn" in signed_in.get("/views").text
    assert "SELECT txn_id, user_id, amount FROM txn" in signed_in.get("/workbench?query=big_txn").text
    ops = signed_in.get("/operations").text
    assert "hot" in ops and "State ceiling nearly reached" in ops


def test_the_point_query_works_without_javascript_and_binds_its_value(signed_in, engine):
    page = signed_in.get("/views/big_txn?key=amount&value=150")
    assert page.status_code == 200
    sql, parameters = engine.queries_seen[-1]
    # A parameter, never spliced: the typed value is bound, and it is a number.
    assert sql == "SELECT * FROM big_txn WHERE amount = ?"
    assert parameters == [150]
    assert "u2" in page.text


def test_the_catalog_shows_which_queries_share_a_computation(signed_in):
    page = signed_in.get("/catalog?tab=queries").text
    assert "hot_alias" in page and "none — its own computation" in page


def test_what_the_engine_does_not_measure_is_named_not_faked(signed_in):
    ops = signed_in.get("/operations").text
    assert "Not measured by the engine" in ops
    assert "Commit latency percentiles" in ops and "Backpressure" in ops
    # What the engine now publishes is no longer listed as missing.
    assert "needs-engine" not in ops and "Checkpoint health" not in ops
    for page in ("/catalog", "/catalog?tab=sinks", "/views/big_txn", "/catalog/streams/txn", "/start"):
        assert "needs-engine" not in signed_in.get(page).text, page


def test_the_sinks_tab_lists_what_the_engine_publishes_and_nothing_it_does_not(signed_in, engine):
    page = signed_in.get("/catalog?tab=sinks").text
    assert "audit_out" in page and "filesystem" in page and "append only" in page
    assert '<a class="mono" href="/queries/big_txn">big_txn</a>' in page
    assert "PRV-5093" in page
    assert SINK_SECRET not in page
    body = signed_in.get("/api/v1/catalog/sinks").json()
    assert [s["name"] for s in body["items"]] == ["audit_out", "broken_out"]


def test_the_stream_page_shows_event_time_lateness_source_and_engine_lineage(signed_in):
    page = signed_in.get("/catalog/streams/txn").text
    assert "event_time" in page and "PT10S" in page and "filesystem" in page
    assert "From the engine: the streams each query" in page
    catalog = signed_in.get("/catalog").text
    assert "no event time" not in catalog and "event time" in catalog


def test_the_view_page_shows_its_key_retention_and_sink_from_the_engine(signed_in):
    page = signed_in.get("/views/big_txn").text
    assert 'id="view-shape"' in page
    assert "PT24H" in page and "audit_out" in page and "(#0)" in page
    described = signed_in.get("/api/v1/views/big_txn").json()
    assert described["retention"] == "PT24H" and described["keyColumns"][0]["name"] == "txn_id"
    assert signed_in.get("/api/v1/views/big_txn/schema").json()["fields"][0]["name"] == "txn_id"


def test_the_query_page_shows_a_detached_sink_and_its_failure(signed_in):
    page = signed_in.get("/queries/big_txn").text
    assert 'id="sink-failure"' in page and "PRV-8009" in page and "detached" in page
    assert "PT24H" in page


def test_an_unknown_view_or_stream_is_a_404(signed_in):
    assert signed_in.get("/views/nope").status_code == 404
    assert signed_in.get("/views/nope/live").status_code == 404
    assert signed_in.get("/catalog/streams/nope").status_code == 404


# ============================================================ role-aware landing

@pytest.mark.parametrize("role,landing", [("analyst", "/workbench"), ("operator", "/operations"),
                                          ("developer", "/views")])
def test_each_role_lands_on_its_own_screen(engine, role, landing):
    client = _app(engine)
    signed = client.post("/login", data={"password": PASSWORD, "next": "/home", "role": role},
                         follow_redirects=False)
    assert signed.headers["location"] == "/home"
    home = client.get("/home", follow_redirects=False)
    assert home.headers["location"] == landing


def test_the_default_role_comes_from_configuration(engine):
    client = _app(engine, default_role="developer")
    client.post("/login", data={"password": PASSWORD, "next": "/home"})
    assert client.get("/home", follow_redirects=False).headers["location"] == "/views"


def test_a_role_can_be_changed_after_sign_in(signed_in):
    moved = signed_in.post("/preferences/role", data={"role": "analyst", "next": "/home"},
                           follow_redirects=False)
    assert moved.headers["location"] == "/workbench"
    assert signed_in.get("/api/v1/me").json()["role"] == "analyst"
    # An unknown role is ignored rather than stored.
    signed_in.post("/preferences/role", data={"role": "root", "next": "/home"})
    assert signed_in.get("/api/v1/me").json()["role"] == "analyst"


def test_first_run_lands_on_onboarding(engine):
    engine._queries = []
    client = _app(engine)
    client.post("/login", data={"password": PASSWORD, "next": "/home"})
    assert client.get("/home", follow_redirects=False).headers["location"] == "/start"


def test_the_sign_in_page_offers_the_roles(anonymous):
    page = anonymous.get("/login").text
    for role in ("analyst", "operator", "developer"):
        assert f'value="{role}"' in page


# ============================================================ JSON endpoints

def test_completions_carry_streams_columns_types_and_functions(signed_in):
    body = signed_in.get("/api/v1/catalog/completions").json()
    assert body["streams"][0]["name"] == "txn"
    assert {"name": "amount", "type": "BIGINT", "nullable": False, "ordinal": 2} in body["streams"][0]["fields"]
    names = {f["name"] for f in body["functions"]}
    assert {"TUMBLE", "HOP", "COUNT", "SUM"} <= names
    assert "SELECT" in body["keywords"]


def test_validate_places_the_diagnostic_links_local_help_and_offers_the_fix(signed_in):
    body = signed_in.post("/api/v1/sql/validate", json={"sql": "SELECT *\nFROM txm"}).json()
    assert body["valid"] is False
    diag = body["diagnostics"][0]
    assert diag["code"] == "PRV-2003"
    assert diag["range"] == {"startLine": 2, "startColumn": 6, "endLine": 2, "endColumn": 9}
    # The engine's helpUrl names a host that does not exist; the console links its own page.
    assert diag["help"] == "/help/codes/PRV-2003"
    fix = diag["fixes"][0]
    assert fix["title"] == "Replace 'txm' with 'txn'"
    assert fix["edits"][0]["text"] == "txn"


def test_validate_suggests_a_close_column_name(signed_in):
    body = signed_in.post("/api/v1/sql/validate", json={"sql": "SELECT amout FROM txn"}).json()
    assert body["diagnostics"][0]["fixes"][0]["title"] == "Replace 'amout' with 'amount'"


def test_validate_reports_an_unreachable_engine_as_503(engine_down):
    answer = engine_down.post("/api/v1/sql/validate", json={"sql": "SELECT 1"})
    assert answer.status_code == 503
    assert "did not answer" in answer.json()["error"]


def test_explain_returns_the_plan_as_a_graph(signed_in):
    body = signed_in.post("/api/v1/sql/explain", json={"sql": "SELECT * FROM txn", "level": "physical"}).json()
    ops = [n["op"] for n in body["graph"]["nodes"]]
    assert ops == ["Project", "Filter", "Scan"]
    assert body["operator_metrics"] is None
    assert "not published" in body["metrics_note"]
    assert body["query_metrics"] is None
    # Explaining a registered query's own SQL attaches what the engine measures for it; an
    # edited copy does not, because those numbers would be about a different plan.
    own = signed_in.post("/api/v1/sql/explain", json={
        "sql": "SELECT txn_id, user_id, amount FROM txn WHERE amount > 100", "query": "big_txn"}).json()
    assert own["query_metrics"]["subscribers"] == 2
    edited = signed_in.post("/api/v1/sql/explain", json={"sql": "SELECT 1", "query": "big_txn"}).json()
    assert edited["query_metrics"] is None
    bad = signed_in.post("/api/v1/sql/explain", json={"sql": "SELECT 1", "level": "quantum"})
    assert bad.status_code == 400


def test_registering_maps_key_names_to_the_validated_ordinals_and_passes_the_sink(signed_in, engine):
    answer = signed_in.post("/api/v1/queries", json={
        "name": "by_user", "sql": "SELECT txn_id, user_id, amount FROM txn",
        "key_names": ["user_id", "AMOUNT"], "sink": "audit_trail"})
    assert answer.status_code == 200, answer.text
    assert answer.json()["keys"] == [1, 2]
    assert engine.registered[-1] == {"name": "by_user", "sql": "SELECT txn_id, user_id, amount FROM txn",
                                     "keys": [1, 2], "sink": "audit_trail", "retention": None}


def test_registering_passes_the_retention_through_json_and_the_plain_form(signed_in, engine):
    answer = signed_in.post("/api/v1/queries", json={
        "name": "kept", "sql": "SELECT txn_id, user_id, amount FROM txn", "keys": [0], "retention": "PT6H"})
    assert answer.status_code == 200, answer.text
    assert answer.json()["retention"] == "PT6H"
    assert engine.registered[-1]["retention"] == "PT6H"
    form = signed_in.post("/queries", data={"name": "kept2", "sql": "SELECT txn_id FROM txn", "keys": "0",
                                            "retention": "forever"}, follow_redirects=False)
    assert form.status_code == 303
    assert engine.registered[-1]["retention"] == "forever"


def test_declaring_a_stream_passes_its_event_time_and_lateness(signed_in, engine):
    answer = signed_in.post("/api/v1/catalog/streams", json={
        "name": "clicks", "schema": "user:STRING,at:TIMESTAMP", "event_time": "at", "out_of_orderness": "PT5S"})
    assert answer.status_code == 200, answer.text
    assert answer.json()["eventTime"] == "at" and answer.json()["outOfOrderness"] == "PT5S"


def test_registering_with_a_key_the_output_does_not_have_is_refused_before_the_engine(signed_in, engine):
    answer = signed_in.post("/api/v1/queries", json={
        "name": "by_user", "sql": "SELECT txn_id FROM txn", "key_names": ["nope"]})
    assert answer.status_code == 400
    assert "not a column of this query's output" in answer.json()["error"]
    assert engine.registered == []


def test_the_register_form_takes_key_names_too(signed_in, engine):
    signed_in.post("/queries", data={"name": "form_q", "sql": "SELECT txn_id, user_id FROM txn",
                                     "keys": "user_id", "sink": ""})
    assert engine.registered[-1]["keys"] == [1]
    assert engine.registered[-1]["sink"] is None


def test_lookup_refuses_a_column_the_view_does_not_have(signed_in, engine):
    answer = signed_in.post("/api/v1/views/big_txn/lookup", json={"filters": {"1=1; DROP": "x"}})
    assert answer.status_code == 400
    assert not any("DROP" in sql for sql, _ in engine.queries_seen)


def test_lookup_answers_with_types_and_bound_parameters(signed_in):
    body = signed_in.post("/api/v1/views/big_txn/lookup", json={"filters": {"user_id": "u1"}}).json()
    assert body["sql"] == "SELECT * FROM big_txn WHERE user_id = ?"
    assert body["parameters"] == ["u1"]
    assert body["types"] == ["int64", "string", "int64"]


def test_snippets_endpoint_refuses_a_name_that_is_not_an_identifier(signed_in):
    answer = signed_in.get("/api/v1/views/big_txn/snippets?key=x;drop&value=1")
    assert answer.status_code == 400


def test_the_operations_snapshot_answers_where(signed_in):
    body = signed_in.get("/api/v1/ops/snapshot").json()
    assert body["verdict"]["status"] == "critical"
    assert "hot" in body["verdict"]["where"]
    by_name = {q["name"]: q for q in body["queries"]}
    assert by_name["big_txn"]["watermark_lag_seconds"] is None   # NaN is "no watermark", not zero
    assert by_name["hot_alias"]["metrics_published"] is False
    assert body["node"]["status"]["engineState"] == "RUNNING"
    assert body["node"]["heap_used_bytes"] == 1048576.0


def test_the_operations_snapshot_survives_the_engine_being_down(engine_down):
    body = engine_down.get("/api/v1/ops/snapshot").json()
    assert body["verdict"]["status"] == "critical"
    assert body["metrics"]["reachable"] is False
    assert body["node"]["status"]["available"] is False


def test_the_series_endpoint_only_charts_known_metrics(signed_in):
    assert signed_in.get("/api/v1/ops/series?metric=state_fraction").status_code == 200
    assert signed_in.get("/api/v1/ops/series?metric=__import__").status_code == 400


def test_the_signed_in_palette_offers_queries_views_streams_and_allowed_actions(signed_in):
    items = signed_in.get("/api/v1/palette").json()["items"]
    titles = {i["title"] for i in items}
    assert "big_txn" in titles and "txn" in titles and "Pause big_txn" in titles
    assert "Resume hot_alias" in titles and "Pause hot_alias" not in titles
    drop = next(i for i in items if i["title"] == "Drop big_txn…")
    # Drop never runs from the palette: it goes where the typed confirmation is.
    assert drop["href"] == "/queries/big_txn#drop"


def test_declaring_a_stream_validates_the_name(signed_in):
    assert signed_in.post("/api/v1/catalog/streams", json={"name": "bad name", "schema": "a:INT64"}).status_code == 400
    made = signed_in.post("/api/v1/catalog/streams", json={"name": "orders", "schema": "id:INT64"})
    assert made.status_code == 200 and made.json()["name"] == "orders"


def test_a_subscribed_row_carries_its_weight(engine):
    from core.services import Services

    services = Services(engine)
    subscriber = services.feeds.subscribe("big_txn")
    try:
        import time

        deadline = time.time() + 5
        rows: list = []
        while time.time() < deadline and len(rows) < 2:
            rows += subscriber.drain()
            time.sleep(0.02)
        assert [r["_weight"] for r in rows] == [1, -1]
    finally:
        subscriber.close()


# ============================================================ secrets, assets, air gap

def test_no_secret_reaches_any_new_page_or_endpoint(signed_in):
    responses = [signed_in.get(p) for p in NEW_PAGES if p != "/home"]
    responses += [signed_in.get(p) for p in NEW_JSON_GETS if p != "/api/v1/ops/stream"]
    responses += [signed_in.post(p, json=b) for p, b in NEW_JSON_POSTS]
    responses.append(signed_in.get("/api/v1/palette"))
    for response in responses:
        for secret in (ENGINE_TOKEN, SESSION_SECRET, PASSWORD):
            assert secret not in response.text, response.url


VENDORED = [
    "/static/vendor/monaco/vs/loader.js",
    "/static/vendor/monaco/vs/editor.js",
    "/static/vendor/monaco/vs/toggleHighContrast-qGX7E9o7.js",
    "/static/vendor/monaco/vs/editor/editor.main.css",
    "/static/vendor/monaco/vs/assets/editor.worker-lj3bdIIn.js",
    "/static/vendor/echarts/echarts.min.js",
    "/static/vendor/elkjs/elk.bundled.js",
    "/static/vendor/preact/preact.module.js",
    "/static/vendor/preact/hooks.module.js",
    "/static/vendor/htm/htm.module.js",
    "/static/app/workbench.js",
    "/static/app/product.css",
]


@pytest.mark.parametrize("path", VENDORED)
def test_vendored_assets_are_served_by_the_console_itself(anonymous, path):
    assert anonymous.get(path).status_code == 200, path


def test_every_monaco_module_the_editor_loads_is_vendored():
    """The editor's AMD modules name their dependencies; every one must be on disk."""
    vs = CONSOLE_ROOT / "web" / "static" / "vendor" / "monaco" / "vs"
    pending, seen = ["editor", "toggleHighContrast-qGX7E9o7"], set()
    while pending:
        module = pending.pop()
        if module in seen:
            continue
        seen.add(module)
        source = (vs / f"{module}.js").read_text(encoding="utf-8")
        header = re.search(r'define\("vs/[^"]+",\s*\[([^\]]*)\]', source)
        assert header, module
        for dependency in re.findall(r'"\./([^"]+)"', header.group(1)):
            assert (vs / f"{dependency}.js").exists(), f"{module} needs {dependency}"
            pending.append(dependency)


def test_every_vendored_library_carries_its_licence_and_is_in_the_notices():
    vendor = CONSOLE_ROOT / "web" / "static" / "vendor"
    for library, licence in [("monaco", "LICENSE"), ("echarts", "LICENSE"), ("echarts", "NOTICE"),
                             ("elkjs", "LICENSE.md"), ("preact", "LICENSE"), ("htm", "LICENSE"),
                             ("bootstrap", "LICENSE"), ("bootstrap-icons", "LICENSE")]:
        assert (vendor / library / licence).exists(), f"{library}/{licence}"
    notices = (REPO_ROOT / "THIRD-PARTY-NOTICES.md").read_text(encoding="utf-8")
    for name in ("Monaco Editor", "Apache ECharts", "elkjs", "Preact", "htm", "Bootstrap"):
        assert name in notices, name


def test_the_import_map_resolves_only_to_files_this_console_serves():
    base = (CONSOLE_ROOT / "web" / "templates" / "base.html").read_text(encoding="utf-8")
    block = re.search(r'<script type="importmap">\s*(\{.*?\})\s*</script>', base, re.DOTALL)
    assert block
    for target in json.loads(block.group(1))["imports"].values():
        assert target.startswith("/static/")
        path = CONSOLE_ROOT / "web" / target.lstrip("/")
        assert path.exists() or target.endswith("/"), target


_EXTERNAL = re.compile(r"""(?:src|href|url\(|import\s[^;]*?from\s|@import)\s*=?\s*["']?(?:https?:)?//""", re.IGNORECASE)


def test_no_template_script_or_stylesheet_of_ours_references_the_network():
    """Air-gapped: nothing the console authors may load from another host.

    Links a reader clicks (``<a href>`` to documentation) are not loads; everything that
    the browser fetches by itself -- script, stylesheet, image, font, import -- is checked.
    """
    web = CONSOLE_ROOT / "web"
    offenders = []
    for path in list((web / "templates").rglob("*.html")) + list((web / "static" / "app").rglob("*")) \
            + list((web / "static" / "js").rglob("*.js")):
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), start=1):
            if re.search(r"<a\s[^>]*href=", line, re.IGNORECASE) and "<script" not in line and "<link" not in line:
                continue
            if _EXTERNAL.search(line):
                offenders.append(f"{path.relative_to(CONSOLE_ROOT)}:{line_no}")
    assert offenders == []


def test_no_page_asks_the_browser_to_fetch_from_another_host(signed_in):
    for path in [p for p in NEW_PAGES if p != "/home"] + ["/", "/help", "/login"]:
        text = signed_in.get(path).text
        for match in re.finditer(r'<(?:script|link|img|iframe|source)\b[^>]*(?:src|href)="([^"]+)"', text):
            assert match.group(1).startswith("/"), (path, match.group(1))


# ============================================================ pure logic

def test_prometheus_parsing_handles_labels_nan_and_junk():
    samples = metrics.parse(PROMETHEUS + 'odd{q="a\\"b",x="1"} +Inf\n')
    by = {(s.name, tuple(sorted(s.labels.items()))): s.value for s in samples}
    assert by[("pravaha_query_rows_in", (("query", "big_txn"),))] == 1200.0
    assert math.isnan(by[("pravaha_query_watermark_lag_seconds", (("query", "big_txn"),))])
    assert by[("odd", (("q", 'a"b'), ("x", "1")))] == math.inf
    assert not any(s.name == "this" for s in samples)


def test_summaries_and_findings():
    summary = metrics.summarize(metrics.parse(PROMETHEUS))
    assert summary["queries"]["hot"]["state_fraction"] == 0.95
    assert summary["queries"]["big_txn"]["watermark_lag_seconds"] is None
    assert summary["node"]["uptime_seconds"] == 42.5
    found = metrics.findings(summary["queries"], lag_warn_seconds=300)
    kinds = [(f.severity, f.query, f.title) for f in found]
    assert kinds[0] == ("critical", "hot", "State ceiling nearly reached")
    assert ("warn", "hot", "Event time is behind") in kinds
    assert ("info", "big_txn", "No watermark yet") in kinds
    assert metrics.verdict(True, found, 2)["status"] == "critical"
    assert metrics.verdict(True, [], 2)["headline"] == "All 2 queries are healthy"
    assert metrics.verdict(True, [], 0)["status"] == "ok"
    assert metrics.verdict(False, [], 0)["status"] == "critical"


def test_the_new_meters_are_read_and_checkpoint_trouble_is_a_finding():
    calls = []
    clock = [100.0]
    texts = [
        ('pravaha_query_subscribers{query="a"} 2\n'
        'pravaha_query_checkpoint_last_success_timestamp_seconds{query="a"} 90\n'
        'pravaha_query_checkpoint_duration_seconds{query="a"} 0.25\n'
        'pravaha_query_checkpoint_failures_total{query="a"} 1\n'
        'pravaha_query_commit_latency_seconds_count{query="a"} 10\n'
        'pravaha_query_commit_latency_seconds_sum{query="a"} 0.5\n'
        'pravaha_query_checkpoint_last_success_timestamp_seconds{query="b"} NaN\n'),
        ('pravaha_query_subscribers{query="a"} 2\n'
        'pravaha_query_checkpoint_last_success_timestamp_seconds{query="a"} 90\n'
        'pravaha_query_checkpoint_duration_seconds{query="a"} 0.25\n'
        'pravaha_query_checkpoint_failures_total{query="a"} 3\n'
        'pravaha_query_commit_latency_seconds_count{query="a"} 14\n'
        'pravaha_query_commit_latency_seconds_sum{query="a"} 0.9\n'
        'pravaha_query_checkpoint_last_success_timestamp_seconds{query="b"} NaN\n'),
    ]

    def scrape():
        calls.append(1)
        return texts[min(len(calls) - 1, 1)]

    history = metrics.MetricsHistory(scrape, ttl=1.0, clock=lambda: clock[0])
    first = history.snapshot()["queries"]["a"]
    assert first["subscribers"] == 2 and first["commit_latency_mean_seconds"] is None
    clock[0] = 1100.0
    second = history.snapshot()["queries"]
    a = second["a"]
    assert a["commit_latency_mean_seconds"] == 0.1  # (0.9 - 0.5) / (14 - 10), exactly
    assert a["checkpoint_age_seconds"] == 1010.0 and a["checkpoint_failures_new"] == 2
    assert second["b"]["checkpoint_age_seconds"] is None, "not checkpointing is not an age"
    found = [(f.query, f.title) for f in metrics.findings(second)]
    assert ("a", "Checkpoints are failing") in found and ("a", "No recent checkpoint") in found
    assert not any(q == "b" for q, _ in found)


def test_the_operations_page_shows_subscribers_and_checkpoint_state(signed_in):
    page = signed_in.get("/operations").text
    assert "Subscribers" in page and "not checkpointing" in page


def test_a_stopped_query_is_critical():
    found = metrics.findings({"q": {"running": 0}})
    assert found[0].severity == "critical" and found[0].title == "Not running"


def test_the_metrics_history_scrapes_at_most_once_per_ttl_and_computes_rates():
    calls = []
    clock = [100.0]
    texts = ['pravaha_query_rows_in{query="a"} 10\n', 'pravaha_query_rows_in{query="a"} 30\n']

    def scrape():
        calls.append(1)
        return texts[min(len(calls) - 1, 1)]

    history = metrics.MetricsHistory(scrape, ttl=1.0, clock=lambda: clock[0])
    history.snapshot()
    history.snapshot()
    assert len(calls) == 1, "ten viewers in the same second must cost one scrape"
    clock[0] = 102.0
    second = history.snapshot()
    assert second["queries"]["a"]["rows_in_rate"] == 10.0
    assert [v for _, v in history.series("rows_in")["a"]] == [10.0, 30.0]


def test_snippets_cover_every_client_and_bind_rather_than_splice():
    code = snippets.snippets("user_volume", engine_url="grpc://localhost:9090",
                             key_column="user_id", key_value="o'brien")
    assert set(code) == {"java", "python", "psql", "cli"}
    assert 'client.query("SELECT * FROM user_volume WHERE user_id = ?", "o\'brien")' in code["java"]["read"]
    assert "client.query('SELECT * FROM user_volume WHERE user_id = ?', [\"o'brien\"])" in code["python"]["read"]
    # psql has no parameters: the literal is quoted by SQL's rule, then the whole by the shell's,
    # so what the shell hands psql is exactly the SQL with the quote doubled.
    import shlex

    command = code["psql"]["read"].split("\n", 1)[1].replace("\\\n", " ")
    argv = shlex.split(command.split(" ", 1)[1])
    assert argv[argv.index("-c") + 1] == "SELECT * FROM user_volume WHERE user_id = 'o''brien'"
    assert "--params" in code["cli"]["read"] and "--filter" in code["cli"]["subscribe"]
    assert 'client.subscribe("user_volume", Map.of("user_id", "o\'brien")' in code["java"]["subscribe"]


def test_snippets_keep_numbers_numeric_and_never_carry_a_token():
    code = snippets.snippets("v", engine_url="grpc+tls://prod:9090", key_column="id", key_value="40")
    assert 'client.query("SELECT * FROM v WHERE id = ?", 40)' in code["java"]["read"]
    assert "[40]" in code["python"]["read"]
    assert "WHERE id = 40" in code["psql"]["read"]
    everything = json.dumps(code)
    assert "PRAVAHA_TOKEN" in everything          # read from the environment...
    assert "System.getenv" in code["java"]["read"]
    plain = snippets.snippets("v", engine_url="grpc://dev:9090")
    assert "--token" not in plain["cli"]["read"]  # ...and not sent over plaintext


def test_snippets_refuse_names_that_are_not_identifiers():
    with pytest.raises(snippets.SnippetError):
        snippets.snippets("v; drop", engine_url="grpc://x:1")
    with pytest.raises(snippets.SnippetError):
        snippets.snippets("v", engine_url="grpc://x:1", key_column="a b", key_value="1")


def test_the_psql_address_accepts_both_spellings():
    assert snippets.pgwire_parts("db.internal:6543") == ("db.internal", "6543")
    assert snippets.pgwire_parts("postgresql://pg.example:5433/pravaha") == ("pg.example", "5433")


def test_the_engines_graph_becomes_the_islands_graph_without_reading_the_text():
    graph = authoring.plan_graph({
        "nodes": [{"id": "n0", "operator": "Join", "detail": "Join[a.k = b.k]", "stateful": True, "fields": ["k"]},
                  {"id": "n1", "operator": "Scan", "detail": "Scan(a)", "stateful": False, "fields": ["k"]},
                  {"id": "n2", "operator": "Filter", "detail": "Filter(x > 1)", "stateful": False, "fields": ["k"]},
                  {"id": "n3", "operator": "Scan", "detail": "Scan(b)", "stateful": False, "fields": ["k"]}],
        "edges": [{"from": "n1", "to": "n0"}, {"from": "n2", "to": "n0"}, {"from": "n3", "to": "n2"}]})
    ops = [(n["id"], n["op"], n["family"], n["depth"]) for n in graph["nodes"]]
    assert ops == [("n0", "Join", "join", 0), ("n1", "Scan", "source", 1), ("n2", "Filter", "filter", 1),
                   ("n3", "Scan", "source", 2)]
    assert graph["nodes"][1]["detail"] == "a" and graph["nodes"][1]["label"] == "Scan(a)"
    assert graph["nodes"][0]["stateful"] is True
    assert {(e["source"], e["target"]) for e in graph["edges"]} == {("n1", "n0"), ("n2", "n0"), ("n3", "n2")}
    assert authoring.plan_graph(None) == {"nodes": [], "edges": []}


def test_a_diagnostic_is_placed_from_the_engines_range_and_never_from_its_wording():
    # Inclusive end from the engine, exclusive for Monaco.
    where = authoring.locate("SELECT a\nFROM t WHERE", {"startLine": 2, "startColumn": 8, "endLine": 2,
                                                       "endColumn": 12})
    assert where == {"startLine": 2, "startColumn": 8, "endLine": 2, "endColumn": 13}
    # No range: the whole first line, however helpful the message's English looks.
    assert authoring.locate("SELECT 1", None) == {"startLine": 1, "startColumn": 1, "endLine": 1,
                                                  "endColumn": 9}
    enriched = authoring.enrich({"diagnostics": [{"code": "PRV-2001",
                                                  "message": "Encountered at line 2, column 8"}]},
                                "SELECT a\nFROM t", [])
    assert enriched["diagnostics"][0]["range"]["startLine"] == 1
    assert enriched["diagnostics"][0]["positioned"] is False


def test_a_diagnostic_without_a_position_offers_no_text_edit(signed_in):
    body = signed_in.post("/api/v1/sql/validate", json={"sql": "SELECT PLANONLY FROM txn"}).json()
    diag = body["diagnostics"][0]
    assert diag["positioned"] is False
    assert diag["range"] == {"startLine": 1, "startColumn": 1, "endLine": 1, "endColumn": 25}
    assert all("edits" not in fix for fix in diag["fixes"])


def test_fixes_are_offered_only_where_certain():
    assert authoring.fixes_for("PRV-2050", "", [])[0]["action"] == "template:tumble"
    assert authoring.fixes_for("PRV-2041", "", [])[0]["action"] == "clear-sink"
    assert authoring.fixes_for("PRV-9999", "anything", []) == []
    at = {"startLine": 1, "startColumn": 15, "endLine": 1, "endColumn": 21}
    # A name nothing like any stream gets no replacement, only the catalogue.
    far = authoring.fixes_for("PRV-2003", "SELECT * FROM zzzzqq", [TXN], at)
    assert [f.get("action") for f in far] == ["open-catalog"]
    # Without the engine's range there is no word to replace, so no edit is guessed.
    blind = authoring.fixes_for("PRV-2003", "SELECT * FROM txm", [TXN])
    assert [f.get("action") for f in blind] == ["open-catalog"]


def test_key_names_map_to_ordinals_case_insensitively():
    fields = [{"name": "a", "ordinal": 0}, {"name": "B", "ordinal": 1}]
    assert authoring.output_ordinals(fields, ["b", "A"]) == [1, 0]
    with pytest.raises(KeyError):
        authoring.output_ordinals(fields, ["c"])


def test_templates_are_written_against_the_chosen_streams_columns():
    library = {t["id"]: t["sql"] for t in authoring.templates(TXN)}
    assert "TUMBLE(TABLE txn, DESCRIPTOR(event_time)" in library["tumble"]
    assert "SUM(amount)" in library["tumble-sum"]   # the measure, not the identifier
    assert "GROUP BY window_start, window_end, user_id" in library["tumble"]


def test_every_error_code_in_the_table_resolves():
    docs = REPO_ROOT / "docs"
    table = (docs / "TROUBLESHOOTING.md").read_text(encoding="utf-8")
    codes = sorted(set(re.findall(r"^\|\s*`(PRV-\d{4})`", table, re.MULTILINE)))
    assert len(codes) > 50
    for code in codes:
        entry = code_lookup(code, docs)
        assert entry is not None and entry.constant, code
