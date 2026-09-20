"""The persona surfaces, against a stand-in for the engine adapter.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

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

from fake_engine import PROMETHEUS, SINK_SECRET, TXN, FakeEngine

from core import authoring, metrics, snippets
from core.config.properties_configurator import PropertiesConfigurator
from core.content.codes import lookup as code_lookup
from run_pravaha_web import create_app

PASSWORD = "product-test-password"
ENGINE_TOKEN = "s3cret-engine-token-must-never-reach-a-browser"
SESSION_SECRET = "s3cret-session-key-must-never-reach-a-browser"


def _app(engine: FakeEngine, **overrides):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.password", PASSWORD)
    config.set("console.session_secret", SESSION_SECRET)
    config.set("engine.token", ENGINE_TOKEN)
    config.set("ui.default_role", overrides.get("default_role", "operator"))
    # The configurator is a process-wide singleton, and the browser harness turns the gallery on.
    config.set("ui.component_gallery", "false")
    return fastapi_testclient.TestClient(create_app(config, engine=engine))


@pytest.fixture
def engine():
    return FakeEngine()


def _signed_in_on(engine: FakeEngine, **overrides):
    """A signed-in client over this engine.

    A test that changes what the engine answers *before* anybody has asked builds its own
    client with this: signing in lands on a screen, that screen scrapes, and the scrape is
    cached for a second -- so a fixture-built client has already read the old answer.
    """
    client = _app(engine, **overrides)
    client.post("/login", data={"password": PASSWORD, "next": "/home"})
    return client


@pytest.fixture
def signed_in(engine):
    return _signed_in_on(engine)


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
             "/queries/big_txn/dead-letters", "/queries/big_txn/replacement",
             "/workbench?template=tumble&stream=txn", "/plugins", "/admin", "/admin/access",
             "/admin/audit", "/admin/audit?principal=ann&decision=deny"]

NEW_JSON_GETS = ["/api/v1/me", "/api/v1/catalog/streams", "/api/v1/catalog/streams/txn",
                 "/api/v1/catalog/completions", "/api/v1/catalog/templates?stream=txn",
                 "/api/v1/views/big_txn/schema", "/api/v1/views/big_txn/snippets?key=user_id&value=u1",
                 "/api/v1/catalog/sinks", "/api/v1/views/big_txn",
                 "/api/v1/ops/snapshot", "/api/v1/ops/series?metric=rows_in", "/api/v1/ops/stream",
                 "/api/v1/plugins", "/api/v1/replacements", "/api/v1/queries/big_txn/replacement",
                 "/api/v1/admin/audit", "/api/v1/admin/audit?principal=ann",
                 "/api/v1/admin/permissions"]

NEW_JSON_POSTS = [("/api/v1/sql/validate", {"sql": "SELECT * FROM txn"}),
                  ("/api/v1/sql/explain", {"sql": "SELECT * FROM txn"}),
                  ("/api/v1/sql/diff", {"left": {"query": "big_txn"}, "right": {"sql": "SELECT * FROM txn"}}),
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

RENDERED = [p for p in NEW_PAGES if p not in {"/home", "/admin"}]


@pytest.mark.parametrize("path", RENDERED)
def test_every_new_page_renders_for_a_signed_in_person(signed_in, path):
    page = signed_in.get(path)
    assert page.status_code == 200, path
    assert "<main" in page.text


@pytest.mark.parametrize("path", RENDERED)
def test_every_new_page_renders_with_the_engine_down(engine_down, path):
    page = engine_down.get(path)
    # A page about something that cannot be looked up is a clear 404/503; everything else
    # is a 200 that says what is missing. Never a 500.
    assert page.status_code in {200, 404, 503}, (path, page.status_code)
    assert "engine unreachable" in page.text


def test_a_query_with_no_dead_letters_says_so_rather_than_failing(signed_in, engine):
    """B5 wrote two empty states for the dead-letter screen and nothing ever opened either.

    The shared ``_states.html`` had no ``empty`` macro, so both the empty queue and the node
    with no ``pravaha.dlq.directory`` -- the commonest way the screen is seen, because most
    queues are empty most of the time -- rendered a 500 rather than the state written for
    them. Two lines of a test, and it would never have shipped.
    """
    empty = signed_in.get("/queries/big_txn/dead-letters")
    assert empty.status_code == 200
    assert 'id="dlq-empty"' in empty.text and "big_txn" in empty.text

    engine.dlq_configured = False
    unconfigured = signed_in.get("/queries/big_txn/dead-letters")
    assert unconfigured.status_code == 200
    assert 'id="dlq-unconfigured"' in unconfigured.text
    assert "pravaha.dlq.directory" in unconfigured.text


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
    assert "Commit latency percentiles" in ops
    # What the engine now publishes is no longer listed as missing. B6 measures backpressure
    # and per-operator telemetry, so the list is one entry shorter rather than being kept for
    # the shape of it -- and the list must not name something the dashboard is now drawing.
    assert "needs-engine" not in ops and "Checkpoint health" not in ops
    listed = ops.split("Not measured by the engine", 1)[1]
    assert "lane backpressure" not in listed and "per operator" not in listed
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


# ============================================================ FEED-1: a stopped source

def test_a_healthy_query_page_says_its_source_is_reading(signed_in):
    page = signed_in.get("/queries/big_txn").text
    assert 'id="feed"' in page and ">receiving rows<" in page and "reading txn (1 partition)" in page
    assert 'id="feed-failure"' not in page and ">source stopped<" not in page


def test_a_stopped_source_is_on_the_query_page_with_its_code_linked_to_help(signed_in, engine):
    engine.stop_source("fff000")
    page = signed_in.get("/queries/hot").text
    assert 'id="feed-failure"' in page
    assert '<a href="/help/codes/PRV-5040">PRV-5040</a>' in page
    assert "is not an INT64" in page and "txn#0, stopped 2026-09-19T08:00:00Z" in page
    assert "Not retried" in page
    # The query is still RUNNING -- the state is not changed, the stop is shown beside it.
    assert ">RUNNING<" in page and ">source stopped<" in page
    # The sink's own failure is untouched by it.
    assert 'id="sink-failure"' in signed_in.get("/queries/big_txn").text


def test_a_stopped_source_is_marked_in_the_listing_and_its_json(signed_in, engine):
    engine.stop_source("fff000")
    items = {q["name"]: q for q in signed_in.get("/api/v1/queries").json()["items"]}
    assert items["hot"]["feed"] == "STOPPED" and items["hot"]["feed_code"] == "PRV-5040"
    assert items["hot"]["feed_where"] == "txn#0"
    # One computation, one feed: the other name on it reports the same stop.
    assert items["hot_alias"]["feed"] == "STOPPED"
    assert items["big_txn"]["feed"] == "RUNNING" and items["big_txn"]["feed_code"] is None
    page = signed_in.get("/queries").text
    assert page.count("source-stopped") == 2 and "PRV-5040" in page


def test_the_operations_verdict_names_a_stopped_source_with_its_code(signed_in, engine):
    engine.stop_source("abc123def456", code="PRV-5092", where="txn#0")
    body = signed_in.get("/api/v1/ops/snapshot").json()
    stopped = [f for f in body["findings"] if f["title"] == "Source stopped"]
    assert [f["query"] for f in stopped] == ["big_txn"]
    assert stopped[0]["severity"] == "critical" and stopped[0]["code"] == "PRV-5092"
    assert "reading txn#0 with PRV-5092" in stopped[0]["detail"]
    assert body["verdict"]["status"] == "critical" and "big_txn" in body["verdict"]["where"]
    assert {q["name"]: q for q in body["queries"]}["big_txn"]["feed"] == "STOPPED"
    page = signed_in.get("/operations").text
    assert '<a class="mono" href="/help/codes/PRV-5092">PRV-5092</a>' in page


def test_a_healthy_engine_has_no_stopped_source_finding(signed_in):
    body = signed_in.get("/api/v1/ops/snapshot").json()
    assert not [f for f in body["findings"] if f["title"] == "Source stopped"]


def test_the_feed_stopped_gauge_alone_is_a_finding():
    """The metric catches a stop the listing has not shown yet, and the reverse."""
    summary = metrics.summarize(metrics.parse(
        'pravaha_query_feed_stopped{query="q"} 1.0\npravaha_query_feed_failures_total{query="q"} 1.0\n'
        'pravaha_query_feed_stopped{query="ok"} 0.0\n'))
    assert summary["queries"]["q"]["feed_stopped"] == 1.0
    assert summary["queries"]["q"]["feed_failures"] == 1.0
    found = metrics.findings(summary["queries"])
    assert [(f.query, f.title, f.code) for f in found] == [("q", "Source stopped", None)]
    with_code = metrics.findings({"q": {}}, feed_stops={"q": {"code": "PRV-5040", "where": "t#1"}})
    assert with_code[0].code == "PRV-5040" and "reading t#1 with PRV-5040" in with_code[0].detail


def test_an_unknown_view_or_stream_is_a_404(signed_in):
    assert signed_in.get("/views/nope").status_code == 404
    assert signed_in.get("/views/nope/live").status_code == 404
    assert signed_in.get("/catalog/streams/nope").status_code == 404


# ============================================================ role-aware landing

@pytest.mark.parametrize("role,landing", [("analyst", "/workbench"), ("operator", "/operations"),
                                          ("admin", "/admin/access"),
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


class _ScriptedMirror(FakeEngine):
    """An engine whose snapshot subscription delivers exactly what the test hands it, when it does."""

    def __init__(self) -> None:
        super().__init__()
        import queue as _queue

        self.script: _queue.Queue = _queue.Queue()

    def mirror(self, view, filters=None):
        while True:
            event = self.script.get()
            if event is None:
                return
            yield event


def _zset(rows):
    sums: dict = {}
    for row in rows:
        key = tuple(sorted((k, v) for k, v in row.items() if k != "_weight"))
        sums[key] = sums.get(key, 0) + row.get("_weight", 1)
    return {k: w for k, w in sums.items() if w}


def _await(condition, what):
    import time

    deadline = time.time() + 5
    while not condition():
        if time.time() > deadline:
            raise AssertionError(f"timed out waiting for {what}")
        time.sleep(0.01)


def test_a_browser_attaching_between_commits_starts_from_the_view_and_misses_nothing():
    """SUB-1, one level up: the live page's starting view and its changes, with nothing between.

    The page read the view and then opened its stream; a commit landing between the two reached it
    by neither. Now the feed keeps the view its snapshot subscription describes and hands each
    browser that view and then every change after it, in one step. A browser attaching late gets
    the commits it missed in its starting view, not as changes and not twice.
    """
    from core.services import Services

    engine = _ScriptedMirror()
    services = Services(engine)
    u1 = {"user_id": "u1", "amount": 10, "_weight": 1}
    u2 = {"user_id": "u2", "amount": 20, "_weight": 1}
    early = services.feeds.subscribe("big_txn")
    try:
        engine.script.put(("snapshot", [u1], 1))
        _await(lambda: early._snapshot is not None, "the first browser's starting view")
        engine.script.put(("commit", [u2], 2))
        _await(lambda: early._queue.qsize() == 1, "the commit to reach the first browser")

        late = services.feeds.subscribe("big_txn")
        try:
            retract = dict(u1, _weight=-1)
            engine.script.put(("commit", [retract], 3))
            _await(lambda: late._queue.qsize() == 1, "the next commit to reach the late browser")

            for browser in (early, late):
                rows, _frontier = browser.take_snapshot()
                copy = _zset(rows + browser.drain())
                assert copy == _zset([u2]), "snapshot plus changes is the view, for every browser"
            assert services.feeds.live_feeds() == 1, "still one engine subscription"
        finally:
            late.close()
    finally:
        early.close()
        engine.script.put(None)


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
    responses = [signed_in.get(p) for p in RENDERED]
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
    "/static/vendor/echarts/echarts.common.min.js",
    "/static/vendor/elkjs/elk.bundled.js",
    "/static/vendor/preact/preact.module.js",
    "/static/vendor/preact/hooks.module.js",
    "/static/vendor/htm/htm.module.js",
    "/static/app/workbench.js",
    "/static/app/diff.js",
    "/static/app/product.css",
]


@pytest.mark.parametrize("path", VENDORED)
def test_vendored_assets_are_served_by_the_console_itself(anonymous, path):
    assert anonymous.get(path).status_code == 200, path


def test_scripts_and_pages_are_sent_compressed(signed_in):
    """Design 23.15 states its budget gzipped; the console now sends what it measures. (That a
    compressed console still streams a live view change by change is proven in a browser, by
    test_browser_journeys' onboarding journey.)"""
    script = signed_in.get("/static/vendor/bootstrap/js/bootstrap.bundle.min.js",
                           headers={"Accept-Encoding": "gzip"})
    assert script.headers.get("content-encoding") == "gzip"
    page = signed_in.get("/catalog", headers={"Accept-Encoding": "gzip"})
    assert page.headers.get("content-encoding") == "gzip" and "txn_id" in page.text


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
    for path in RENDERED + ["/", "/help", "/login"]:
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


# ============================================================ the plugins screen

def test_the_plugins_screen_is_the_engines_manifest_listing_decorated_with_binding_details(signed_in):
    body = signed_in.get("/api/v1/plugins").json()
    by_name = {p["name"]: p for p in body["plugins"]}
    fs = by_name["filesystem"]
    # From GET /api/v1/plugins: the manifest and what the code can be, which the console used
    # to say the engine did not publish.
    assert fs["loaded"] and fs["compatible"] and fs["required_api"] == "0.1.0"
    assert fs["kinds"] == ["sink", "source"]
    assert fs["source_capabilities"]["guarantee"] == "EXACTLY_ONCE"
    # Health the engine did not measure is said to be unreported, never drawn as healthy.
    assert fs["health_reported"] is False and fs["healthy"] is False
    assert fs["bound_as"] == ["source", "sink"]
    # The binding's details come from the stream catalogue and the sink list.
    assert [s["name"] for s in fs["sources"]] == ["txn"] and fs["sources"][0]["event_time"] == "event_time"
    assert [k["name"] for k in fs["sinks"]] == ["audit_out"] and fs["sinks"][0]["writers"] == ["big_txn"]
    # A registered plugin's live health and its manifest's setting names.
    vault = by_name["vault"]
    assert vault["health"] == "DEGRADED" and vault["health_reported"] and vault["settings"] == ["endpoint", "token"]
    # A binding naming a plugin the engine cannot load is shown, not dropped, and listed last.
    nope = by_name["nope"]
    assert not nope["loaded"] and nope["sinks"][0]["problem"]["code"] == "PRV-5093"
    assert body["plugins"][-1]["name"] == "nope"
    page = signed_in.get("/plugins").text
    assert "not loaded" in page and "health not reported" in page and "Needs plugin API" in page
    assert "Not published by the engine" in page and "pravaha_plugin_*" in page
    assert "requiredApiVersion" not in page, "the manifest is published now; the page must not say it is not"
    assert SINK_SECRET not in page and SINK_SECRET not in json.dumps(body)


def test_the_plugins_screen_names_the_call_that_failed_with_the_engine_down(engine_down):
    page = engine_down.get("/plugins")
    assert page.status_code == 200
    assert "plugin listing is not answering" in page.text
    assert "status endpoint is not answering" in page.text
    body = engine_down.get("/api/v1/plugins").json()
    assert body["available"] is False and {"plugins", "status"} <= set(body["errors"])


# ============================================================ admin: access and the audit trail

def test_admin_is_in_the_navigation_the_palette_and_is_the_admin_personas_landing(engine, signed_in):
    assert 'href="/admin"' in signed_in.get("/catalog").text
    hrefs = {i.get("href") for i in signed_in.get("/api/v1/palette").json()["items"]}
    assert {"/admin/access", "/admin/audit"} <= hrefs
    assert signed_in.get("/admin", follow_redirects=False).headers["location"] == "/admin/access"
    admin = _app(engine)
    admin.post("/login", data={"password": PASSWORD, "role": "admin", "next": "/home"})
    admin.post("/preferences/role", data={"role": "admin", "next": "/home"})
    assert admin.get("/home", follow_redirects=False).headers["location"] == "/admin/access"


def test_the_access_page_shows_the_policys_answers_for_the_consoles_identity(signed_in):
    page = signed_in.get("/admin/access").text
    assert "authenticated" in page and "console" in page
    assert "register continuous queries" in page and "read the audit trail" in page
    assert 'href="/queries/big_txn"' in page and 'href="/catalog/streams/txn"' in page
    body = signed_in.get("/api/v1/admin/permissions").json()
    assert body["readAudit"]["allowed"] is True


def test_the_audit_screen_passes_every_filter_to_the_engine_and_pages_by_its_cursor(signed_in, engine):
    page = signed_in.get("/admin/audit?principal=carol&decision=deny&since=2026-09-19T08:00").text
    assert engine.audit_calls[-1] == {"since": "2026-09-19T08:00:00Z", "principal": "carol",
                                      "decision": "deny", "limit": 50}
    assert "not an analyst" in page and "payroll" in page
    # The filter survives into the form and into every link, so the page is a URL.
    assert 'value="carol"' in page and '<option value="deny" selected>' in page

    first = signed_in.get("/admin/audit").text
    older = re.search(r'href="(/admin/audit\?cursor=\d+)" rel="next"', first)
    assert older, "a second page is offered as a link"
    second = signed_in.get(older.group(1).replace("&amp;", "&")).text
    assert engine.audit_calls[-1]["cursor"] == older.group(1).split("=")[-1]
    assert "Newest" in second and "The oldest readable decision is on this page." in second
    sequences = [int(n) for n in re.findall(r'<td class="num mono text-muted">(\d+)</td>', first + second)]
    assert sequences == sorted(sequences, reverse=True) and len(sequences) == len(set(sequences)) == 70


def test_the_audit_screen_says_not_permitted_when_the_engine_refuses_this_identity(signed_in, engine):
    engine.audit_allowed = False
    page = signed_in.get("/admin/audit")
    assert page.status_code == 403
    assert "Not permitted" in page.text and "audit-readers" in page.text
    assert "needs one of the roles [admin]" in page.text, "the engine's own reason is shown"
    assert "<table" not in page.text.split('id="audit-filters"')[1].split("How the trail is kept")[0]
    api = signed_in.get("/api/v1/admin/audit")
    assert api.status_code == 403 and api.json()["permitted"] is False
    access = signed_in.get("/admin/access").text
    assert "refused" in access


def test_an_action_the_policy_refuses_is_disabled_with_its_reason_not_offered(signed_in, engine):
    """Design 23.16: RBAC drives affordances. Before this, the query page offered Pause, Resume and
    Drop, and the palette its lifecycle actions, whatever the engine's policy said -- a control that
    failed on click with the engine's refusal."""
    engine.administer_refused["hot"] = "administering 'hot' needs one of the roles [ops]"
    engine.register_refusal = "registering needs one of the roles [author]"
    page = signed_in.get("/queries/hot").text
    assert 'id="controls-refused"' in page and "needs one of the roles [ops]" in page
    assert 'action="/queries/hot/drop"' not in page and 'id="dropConfirmModal"' not in page
    assert re.search(r'id="drop" type="button" disabled', page)
    # Another query, which the policy allows, keeps its controls.
    other = signed_in.get("/queries/big_txn").text
    assert 'action="/queries/big_txn/drop"' in other and 'id="controls-refused"' not in other

    items = signed_in.get("/api/v1/palette").json()["items"]
    lifecycle = {(i["action"], i["query"]) for i in items if i["kind"] == "lifecycle"}
    assert not {a for a in lifecycle if a[1] == "hot"}, lifecycle
    assert ("pause", "big_txn") in lifecycle and ("drop", "big_txn") in lifecycle
    assert any(i.get("href") == "/queries/hot" for i in items), "the query itself is still found"

    workbench = signed_in.get("/workbench").text
    assert 'data-register-refused="registering needs one of the roles [author]"' in workbench
    assert 'id="register-refused"' in workbench
    assert 'data-register-refused=' in signed_in.get("/start").text

    # The grant is made where grants live -- the deployment's identity system -- and the console
    # follows the engine's next answer.
    engine.administer_refused.clear()
    engine.register_refusal = None
    assert 'action="/queries/hot/drop"' in signed_in.get("/queries/hot").text
    assert "data-register-refused" not in signed_in.get("/workbench").text


def test_the_component_gallery_is_off_unless_set_and_gated_when_on(engine, signed_in, anonymous):
    """A development aid: a 404 in a deployment that did not ask for it, and behind the sign-in
    in one that did."""
    assert signed_in.get("/_components").status_code == 404
    assert anonymous.get("/_components", follow_redirects=False).status_code == 404
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("console.password", PASSWORD)
    config.set("console.session_secret", SESSION_SECRET)
    config.set("ui.component_gallery", "true")
    on = fastapi_testclient.TestClient(create_app(config, engine=engine))
    refused = on.get("/_components", follow_redirects=False)
    assert refused.status_code == 303 and refused.headers["location"].startswith("/login")
    on.post("/login", data={"password": PASSWORD, "next": "/home"})
    page = on.get("/_components")
    assert page.status_code == 200
    assert len(re.findall(r'id="state-[a-z_]+" data-state=', page.text)) == 8, "a card for each state of 23.12"
    assert "/static/js/components.js" in page.text


def test_an_unknown_policy_keeps_the_controls():
    """An engine that did not answer the permissions call is not a refusal: the engine re-checks
    every action anyway, so the control stays and the engine's own answer is what fails."""
    from core.admin import Affordances

    assert Affordances(None).administer_refused("hot") is None
    assert Affordances(None).register_refused() is None
    assert Affordances({"views": [{"name": "x"}]}).administer_refused("x") is None


def test_a_malformed_time_is_refused_before_the_engine_is_asked(signed_in, engine):
    page = signed_in.get("/admin/audit?since=yesterday")
    assert page.status_code == 400 and "is not a time" in page.text
    assert engine.audit_calls == []
    api = signed_in.get("/api/v1/admin/audit?decision=maybe")
    assert api.status_code == 400 and api.json()["code"] == "PRV-1051"


def test_the_audit_screen_with_the_engine_down_names_the_failure(engine_down):
    page = engine_down.get("/admin/audit")
    assert page.status_code == 503 and "could not be read" in page.text


def test_the_plugins_screen_is_reachable_from_the_account_menu_and_the_palette(signed_in):
    assert 'href="/plugins"' in signed_in.get("/catalog").text
    items = signed_in.get("/api/v1/palette").json()["items"]
    assert any(i.get("href") == "/plugins" for i in items)


# ============================================================ backfill and cutover (23.10)

def _replacing(engine: FakeEngine, name: str = "big_txn", **options):
    return engine.start_replacement(
        name, "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500", [0],
        backfill="history", rate_limit=5000, **options)


def test_a_query_nothing_is_replacing_says_what_a_replacement_is(signed_in):
    """Never had data (23.12): the screen explains the thing and offers the way to start one,
    rather than an empty progress panel that reads as a stalled job."""
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200
    assert 'id="rep-none"' in page.text
    assert "beside the running one" in page.text
    assert 'href="/workbench?query=big_txn"' in page.text
    # And nothing that looks like progress: no zeros standing in for a job that does not exist.
    assert 'id="rep-numbers"' not in page.text


def test_the_replacement_screen_shows_what_is_measured_and_no_estimate(signed_in, engine):
    """Design 23.10 asks for an ETA. There is not one, and this is where that is enforced:
    a source does not say how much history it holds, so every denominator is invented."""
    _replacing(engine)
    engine.backfill_progress("big_txn", historyRows=412_000, liveRows=980, rowsPerSecond=4800.0,
                             partitionsLive=3, lagSeconds=12.5)
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200
    body = page.text
    assert "412,000" in body and "980" in body and "4,800 rows/s" in body
    assert "3 of 4" in body, "partitions live against total is the closest thing to progress"
    assert "12.5 s" in body
    assert "There is no estimate and no percentage here" in body
    # Nothing that draws a share of unknown work.
    numbers = body.split('id="rep-numbers"', 1)[1].split("</dl>", 1)[0]
    assert "progress" not in numbers.lower() and 'role="meter"' not in numbers
    assert "%" not in numbers


def test_a_backfill_that_has_read_nothing_says_the_lag_is_not_known(signed_in, engine):
    """Zero never stands in for "not measured": a 0.0 s lag would say the candidate had
    caught up exactly, which is the opposite of what it means before the first row."""
    _replacing(engine)
    page = signed_in.get("/queries/big_txn/replacement").text
    lag = page.split("Candidate behind the running version", 1)[1].split("</dd>", 1)[0]
    assert "not known yet" in lag and "0" not in lag


def test_the_cutover_is_refused_until_every_partition_has_reached_the_seam(signed_in, engine):
    """Offered only when the engine would accept it: a control that fails on click is the
    thing 23.12's unauthorized state exists to prevent, and the same rule holds for a
    control the engine's own precondition would refuse."""
    _replacing(engine)
    early = signed_in.get("/queries/big_txn/replacement").text
    button = early.split('id="rep-cutover-plain"', 1)[1].split(">", 1)[0]
    assert "disabled" in button
    assert "has not read all of the history yet" in early

    engine.backfill_progress("big_txn", partitionsLive=4, historyComplete=True)
    ready = signed_in.get("/queries/big_txn/replacement").text
    assert "disabled" not in ready.split('id="rep-cutover-plain"', 1)[1].split(">", 1)[0]


def test_cutting_over_and_rolling_back_reach_the_engine_and_say_what_happened(signed_in, engine):
    _replacing(engine)
    engine.backfill_progress("big_txn", partitionsLive=4, historyComplete=True)
    done = signed_in.post("/queries/big_txn/replacement/cutover", follow_redirects=False)
    assert done.status_code == 303
    assert ("cutover", "big_txn", None) in engine.replacement_calls
    page = signed_in.get(done.headers["location"])
    assert "now answers the new version" in page.text

    back = signed_in.post("/queries/big_txn/replacement/rollback", follow_redirects=False)
    assert back.status_code == 303
    assert ("rollback", "big_txn", None) in engine.replacement_calls
    assert "answers the replaced version again" in signed_in.get(back.headers["location"]).text


def test_the_rollback_window_is_shown_honestly_including_when_it_has_passed(signed_in, engine):
    """A window that has closed is the fact an operator most needs. A screen that simply
    stopped offering the button would leave them guessing which of the two it was."""
    _replacing(engine)
    engine.backfill_progress("big_txn", partitionsLive=4, historyComplete=True)
    assert "Nothing has been cut over yet" in signed_in.get("/queries/big_txn/replacement").text

    engine.cut_over("big_txn")
    open_window = signed_in.get("/queries/big_txn/replacement").text
    assert "retained until 2026-09-19T15:30:00Z" in open_window

    engine.replacements_by_name["big_txn"]["rollbackAvailable"] = False
    closed = signed_in.get("/queries/big_txn/replacement").text
    assert "closed" in closed and "2026-09-19T15:30:00Z" in closed
    assert "no longer retained" in closed
    assert "disabled" in closed.split('id="rep-rollback-plain"', 1)[1].split(">", 1)[0]


def test_the_engine_refusing_a_cutover_is_shown_with_its_code(signed_in, engine):
    _replacing(engine)
    refused = signed_in.post("/queries/big_txn/replacement/cutover", follow_redirects=False)
    page = signed_in.get(refused.headers["location"]).text
    assert "PRV-4014" in page and "has not caught up" in page
    assert ("cutover", "big_txn", None) not in engine.replacement_calls


def test_throttling_sends_what_was_typed_rather_than_clamping_it(signed_in, engine):
    """The engine refuses a raise above the ceiling the replacement started with. The console
    sends the number typed and shows that refusal: a silently altered number is worse."""
    _replacing(engine)
    signed_in.post("/queries/big_txn/replacement/throttle", data={"rate": "2000"},
                   follow_redirects=False)
    assert ("throttle", "big_txn", 2000) in engine.replacement_calls

    refused = signed_in.post("/queries/big_txn/replacement/throttle", data={"rate": "99999"},
                             follow_redirects=False)
    assert ("throttle", "big_txn", 99999) in engine.replacement_calls, "the console clamped it"
    page = signed_in.get(refused.headers["location"]).text
    assert "PRV-4018" in page and "may be slowed, not sped up" in page
    assert engine.replacements_by_name["big_txn"]["backfill"]["rateLimit"] == 2000


def test_pausing_the_backfill_keeps_what_it_has_read(signed_in, engine):
    _replacing(engine)
    engine.backfill_progress("big_txn", historyRows=412_000)
    signed_in.post("/queries/big_txn/replacement/pause", follow_redirects=False)
    paused = signed_in.get("/queries/big_txn/replacement").text
    assert "paused" in paused and "412,000" in paused
    signed_in.post("/queries/big_txn/replacement/resume", follow_redirects=False)
    assert ("resume", "big_txn", None) in engine.replacement_calls


def test_a_reader_sees_the_whole_screen_with_every_control_disabled(signed_in, engine):
    """Everything here needs the administer permission. The engine's policy decides, and a
    refusal is a state of the screen with its reason on the page (23.16), not a hidden page."""
    _replacing(engine)
    engine.administer_refused["big_txn"] = "administering 'big_txn' needs one of the roles [ops]"
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200
    assert "needs one of the roles [ops]" in page.text
    assert 'id="rep-refused"' in page.text
    for control in ("bf-pause", "bf-throttle", "rep-cutover", "rep-rollback-btn", "rep-finish"):
        button = page.text.split(f'id="{control}"', 1)[1].split(">", 1)[0]
        assert "disabled" in button, control
        assert 'aria-describedby="rep-refused"' in button, control
    # And the numbers are still there: a reader may read.
    assert 'id="rep-numbers"' in page.text


def test_the_replacement_screen_lists_who_has_served_this_name(signed_in, engine):
    """The versions that have served the name and the frontier each took over at, oldest
    first. It reaches the console over the engine's REST surface, because a control-wire row
    is a flat list of strings and a list of sentences does not fit in one."""
    _replacing(engine)
    page = signed_in.get("/queries/big_txn/replacement").text
    assert 'id="rep-history"' in page
    assert "from the beginning: abc123def456" in page
    assert 'id="rep-history-partial"' not in page

    engine.backfill_progress("big_txn", historyRows=412_000, historyComplete=True)
    engine.cut_over("big_txn")
    page = signed_in.get("/queries/big_txn/replacement").text
    assert "from 412000: newfp" in page


def test_the_replacement_screen_says_when_it_could_not_read_the_version_history(signed_in, engine):
    """Partial (23.12). The history comes from a second call to a second surface, and a node
    with no HTTP URL configured has the replacement and not the trail. An empty list there
    would read as "nobody has served this name", which is never true of a query that runs."""
    engine.history_carried = False
    _replacing(engine)
    page = signed_in.get("/queries/big_txn/replacement").text
    assert 'id="rep-history-partial"' in page
    assert 'id="rep-history"' not in page


def test_the_replacement_json_answers_null_rather_than_404_for_a_query_without_one(signed_in, engine):
    body = signed_in.get("/api/v1/queries/big_txn/replacement").json()
    assert body == {"query": "big_txn", "replacement": None}
    _replacing(engine)
    body = signed_in.get("/api/v1/queries/big_txn/replacement").json()
    assert body["replacement"]["state"] == "BACKFILLING"
    assert body["replacement"]["backfill"]["partitions"] == 4
    assert body["replacement"]["history"] == ["from the beginning: abc123def456"]
    assert signed_in.get("/api/v1/replacements").json()["items"][0]["name"] == "big_txn"


def test_an_engine_that_does_not_answer_is_the_screens_error_state(signed_in, engine):
    engine.fail("replacement")
    page = signed_in.get("/queries/big_txn/replacement")
    assert page.status_code == 200 and 'id="rep-error"' in page.text
    assert "correlation" in page.text


# ============================================================ backpressure and operators (B6)

def test_the_dashboard_draws_the_lane_backpressure_the_engine_now_publishes(signed_in):
    ops = signed_in.get("/operations").text
    assert "Shared lanes" in ops and "lane 0" in ops and "lane 1" in ops
    assert 'id="ops-lanes"' in ops
    snapshot = signed_in.get("/api/v1/ops/snapshot").json()
    hot = next(q for q in snapshot["queries"] if q["name"] == "hot")
    assert hot["blocked_fraction"] == 0.92
    assert hot["inbox_depth"] == 2040 and hot["inbox_cells"] == 2048
    assert hot["backpressure_waits"] == 41 and hot["backpressure_wait_seconds"] == 312.5
    assert snapshot["operators_enabled"] == 1


def test_a_node_with_no_shared_lane_says_so_rather_than_drawing_an_empty_table(engine):
    engine.metrics_text = "\n".join(
        line for line in engine.metrics_text.splitlines() if not line.startswith("pravaha_lane_"))
    ops = _signed_in_on(engine).get("/operations").text
    assert 'id="ops-lanes-none"' in ops and "No lane is shared" in ops
    assert 'id="ops-lanes"' not in ops


def test_the_verdict_names_the_query_and_the_operator_the_time_goes_into(signed_in):
    """Design 23.20's "is everything healthy, and if not, where?" -- and on a backpressured
    query, "where" is the operator, not the query."""
    snapshot = signed_in.get("/api/v1/ops/snapshot").json()
    assert snapshot["verdict"]["where"] == "hot → Aggregate (n0)"
    backpressured = next(f for f in snapshot["findings"] if f["title"] == "Cannot be fed fast enough")
    assert backpressured["query"] == "hot" and backpressured["operator"] == "Aggregate (n0)"
    assert "92% of the time" in backpressured["detail"]
    assert "2,040 of 2,048 cells" in backpressured["detail"]
    assert "shared lane" in backpressured["detail"], "whose fault it is on a shared lane"
    assert "Most of the time goes into Aggregate (n0)" in signed_in.get("/operations").text


def test_a_query_that_is_not_backpressured_raises_no_finding_about_it(signed_in):
    snapshot = signed_in.get("/api/v1/ops/snapshot").json()
    assert not [f for f in snapshot["findings"]
                if f["query"] == "big_txn" and f["title"] == "Cannot be fed fast enough"]


def test_the_plan_of_a_registered_query_carries_its_operators_and_its_bottleneck(signed_in):
    """Explaining a registered query's own SQL gets its running plan's numbers attached --
    same SQL, same plan, same node ids -- and explaining anything else does not, because the
    numbers would then be about a different plan."""
    plan = signed_in.post("/api/v1/sql/explain",
                          json={"sql": "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id",
                                "query": "hot"}).json()
    assert plan["metrics_state"] == "measured"
    assert set(plan["operator_metrics"]) == {n["id"] for n in plan["graph"]["nodes"]}
    assert plan["bottleneck"] == "n0"
    assert plan["query_metrics"]["blockedFraction"] == 0.92

    edited = signed_in.post("/api/v1/sql/explain",
                            json={"sql": "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id "
                                         "HAVING COUNT(*) > 2", "query": "hot"}).json()
    assert edited["metrics_state"] == "not_running"
    assert edited["operator_metrics"] is None and edited["bottleneck"] is None
    assert "not running" in edited["metrics_note"]
    assert edited["query_metrics"] is None

    anonymous_sql = signed_in.post("/api/v1/sql/explain", json={"sql": "SELECT * FROM txn"}).json()
    assert anonymous_sql["metrics_state"] == "not_running"
    assert anonymous_sql["operator_metrics"] is None


def test_the_running_plan_is_keyed_by_the_graphs_own_node_ids(signed_in, engine):
    from core.services import AuthoringService, CatalogService

    authoring_service = AuthoringService(engine, CatalogService(engine))
    plan = authoring_service.plan("hot")
    ids = {n["id"] for n in plan["graph"]["nodes"]}
    assert set(plan["operator_metrics"]) == ids
    assert plan["bottleneck"] in ids
    assert plan["metrics_state"] == "measured"
    assert plan["query_metrics"]["blockedFraction"] == 0.92


def test_a_node_with_the_counters_off_says_so_and_names_the_setting(signed_in, engine):
    """Three answers, not two: not registered, counters off, measured. The middle one must
    name the setting rather than showing an empty graph."""
    from core.services import AuthoringService, CatalogService

    engine.operator_metrics = False
    plan = AuthoringService(engine, CatalogService(engine)).plan("hot")
    assert plan["metrics_state"] == "operators_off"
    assert plan["operator_metrics"] is None and plan["bottleneck"] is None
    assert "pravaha.metrics.operators is off" in plan["metrics_note"]
    # The query's own totals are still there -- they are measured either way.
    assert plan["query_metrics"]["inboxDepth"] == 2040


def test_the_bottleneck_is_absent_rather_than_guessed_when_the_time_is_even(engine):
    """Measured, not inferred from row counts. A plan whose samples put nothing ahead names
    nothing, and a console that always marked the maximum would always accuse somebody."""
    from core.services import AuthoringService, CatalogService

    engine.operator_shares.pop("hot", None)
    plan = AuthoringService(engine, CatalogService(engine)).plan("hot")
    assert plan["metrics_state"] == "measured"
    assert plan["bottleneck"] is None
    snapshot = _signed_in_on(engine).get("/api/v1/ops/snapshot").json()
    assert snapshot["verdict"]["where"] == "hot"


# ============================================================ comparing two versions (23.7)

def _graph(*spec):
    """A plan as the engine sends it, from (operator, label, stateful, fields, consumer index)."""
    nodes, edges = [], []
    for i, (op, label, stateful, fields, consumer) in enumerate(spec):
        nodes.append({"id": f"n{i}", "operator": op, "detail": label, "stateful": stateful, "fields": fields})
        if consumer is not None:
            edges.append({"from": f"n{i}", "to": f"n{consumer}"})
    return authoring.plan_graph({"nodes": nodes, "edges": edges})


_COLS = ["txn_id", "user_id", "merchant", "amount", "event_time"]
#: The real engine's plans for a per-user tumbling count, and the same per user and merchant over
#: transactions above 100 (``pravaha explain``, physical).
_V1 = _graph(
    ("WindowedAggregate", "WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2], 1 aggregate(s))", True,
     ["window_start", "window_end", "user_id", "txns"], None),
    ("Project", "Project[window_start, window_end, user_id]", False, ["window_start", "window_end", "user_id"], 0),
    ("WindowAssign", "WindowAssign(TUMBLING size=60000ms slide=60000ms on event_time)", False,
     _COLS + ["window_start", "window_end"], 1),
    ("Scan", "Scan(txn)", False, _COLS, 2))
_V2 = _graph(
    ("WindowedAggregate", "WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2, 3], 1 aggregate(s))", True,
     ["window_start", "window_end", "user_id", "merchant", "txns"], None),
    ("Project", "Project[window_start, window_end, user_id, merchant]", False,
     ["window_start", "window_end", "user_id", "merchant"], 0),
    ("Filter", "Filter(amount > 100)", False, _COLS + ["window_start", "window_end"], 1),
    ("WindowAssign", "WindowAssign(TUMBLING size=60000ms slide=60000ms on event_time)", False,
     _COLS + ["window_start", "window_end"], 2),
    ("Scan", "Scan(txn)", False, _COLS, 3))


def test_an_inserted_operator_is_one_addition_and_disturbs_nothing_beneath_it():
    diff = authoring.plan_diff(_V1, _V2)
    # Matched by place and kind, not by id: v1's WindowAssign is n2, v2's is n3.
    assert ["n2", "n3"] in diff["pairs"] and ["n3", "n4"] in diff["pairs"]
    assert diff["right"] == {"n0": "changed", "n1": "changed", "n2": "added", "n3": "same", "n4": "same"}
    assert diff["left"] == {"n0": "changed", "n1": "changed", "n2": "same", "n3": "same"}
    assert diff["counts"] == {"added": 1, "removed": 0, "changed": 2, "same": 2}
    assert diff["identical"] is False
    added = [c for c in diff["operators"] if c["change"] == "added"]
    assert [(c["op"], c["label"]) for c in added] == [("Filter", "Filter(amount > 100)")]


def test_a_changed_aggregate_names_its_keys_through_its_inputs_columns():
    diff = authoring.plan_diff(_V1, _V2)
    agg = diff["operators"][0]
    assert agg["change"] == "changed" and agg["op"] == "WindowedAggregate"
    assert agg["what"] == ["label", "fields"]
    assert agg["keys"] == {"before": ["window_start", "window_end", "user_id"],
                           "after": ["window_start", "window_end", "user_id", "merchant"]}
    assert agg["fields"]["added"] == ["merchant"] and agg["fields"]["removed"] == []
    # A label without key ordinals, or ordinals past the input's columns, gives no names at all.
    assert authoring._keys_by_name({"label": "Filter(x > 1)"}, {"fields": ["x"]}) is None
    assert authoring._keys_by_name({"label": "Aggregate(group=[3], [COUNT(c)])"}, {"fields": ["a"]}) is None


def test_an_edited_predicate_is_one_changed_operator():
    before = _graph(("Project", "Project(a)", False, ["a"], None), ("Filter", "Filter(a > 100)", False, ["a"], 0),
                    ("Scan", "Scan(t)", False, ["a"], 1))
    after = _graph(("Project", "Project(a)", False, ["a"], None), ("Filter", "Filter(a > 500)", False, ["a"], 0),
                   ("Scan", "Scan(t)", False, ["a"], 1))
    diff = authoring.plan_diff(before, after)
    assert diff["counts"] == {"added": 0, "removed": 0, "changed": 1, "same": 2}
    changed = next(c for c in diff["operators"] if c["change"] == "changed")
    assert (changed["before"], changed["after"], changed["what"]) == ("Filter(a > 100)", "Filter(a > 500)", ["label"])


def test_an_operator_never_changes_into_another_kind():
    before = _graph(("Project", "Project(a)", False, ["a"], None), ("Scan", "Scan(t)", False, ["a"], 0))
    after = _graph(("Aggregate", "Aggregate(group=[0], [COUNT(c)])", True, ["a", "c"], None),
                   ("Scan", "Scan(t)", False, ["a"], 0))
    diff = authoring.plan_diff(before, after)
    assert diff["left"] == {"n0": "removed", "n1": "same"}
    assert diff["right"] == {"n0": "added", "n1": "same"}
    assert diff["counts"]["changed"] == 0


def test_a_joins_inputs_are_paired_left_with_left_and_right_with_right():
    before = _graph(("Join", "Join[a.k = b.k]", True, ["k"], None), ("Scan", "Scan(a)", False, ["k"], 0),
                    ("Scan", "Scan(b)", False, ["k"], 0))
    after = _graph(("Join", "Join[a.k = b.k]", True, ["k"], None), ("Scan", "Scan(a)", False, ["k"], 0),
                   ("Filter", "Filter(k > 1)", False, ["k"], 0), ("Scan", "Scan(b)", False, ["k"], 2))
    diff = authoring.plan_diff(before, after)
    assert diff["pairs"] == [["n0", "n0"], ["n1", "n1"], ["n2", "n3"]]
    assert diff["right"]["n2"] == "added" and diff["counts"] == {"added": 1, "removed": 0, "changed": 0, "same": 3}
    # A whole input that exists on one side only goes with everything beneath it.
    lone = _graph(("Join", "Join[a.k = b.k]", True, ["k"], None), ("Scan", "Scan(a)", False, ["k"], 0))
    assert authoring.plan_diff(before, lone)["left"]["n2"] == "removed"


def test_identical_plans_are_identical_and_an_empty_side_is_all_added():
    assert authoring.plan_diff(_V1, _V1)["identical"] is True
    assert authoring.plan_diff(_V1, _V1)["counts"]["same"] == 4
    empty = authoring.plan_diff(None, _V1)
    assert empty["identical"] is False and set(empty["right"].values()) == {"added"}


def test_the_consequences_say_what_is_known_and_what_is_not():
    differ = authoring.plan_diff(_V1, _V2)
    left = {"query": "v1", "keys": ["user_id", "window_end"], "retention": "PT24H", "fingerprint": "aaa",
            "output_fields": [{"name": "user_id", "type": "VARCHAR"}, {"name": "window_end", "type": "TIMESTAMP"}]}
    right = {"output_fields": [{"name": "user_id", "type": "VARCHAR"}, {"name": "merchant", "type": "VARCHAR"}]}
    kinds = {f["kind"]: f for f in authoring.diff_consequences(differ, left, right)}
    assert "separate_computation" in kinds and "not_determinable" in kinds
    assert kinds["schema_changes"]["added"] == ["merchant"] and kinds["schema_changes"]["removed"] == ["window_end"]
    assert kinds["keys_missing"]["missing"] == ["window_end"]
    assert kinds["state_changes"]["changed"] == [_V2["nodes"][0]["label"]]
    # Identical plans may share -- never "will": keys, retention and row filters decide it.
    same = {f["kind"]: f for f in authoring.diff_consequences(authoring.plan_diff(_V1, _V1), left,
                                                                  dict(left, query=None, fingerprint=None))}
    assert same["may_share"]["keys"] == ["user_id", "window_end"] and same["may_share"]["retention"] == "PT24H"
    assert "state_same" in same and "schema_same" in same
    # Both registered: the fingerprints answer it outright.
    known = authoring.diff_consequences(differ, left, dict(right, fingerprint="aaa", registered_as="v2"))
    assert known[0] == {"kind": "fingerprint_same", "left": "aaa", "right": "aaa", "left_name": "v1", "right_name": "v2"}
    # No plan on a side: the computation is not guessed at.
    assert authoring.diff_consequences(None, left, {"output_fields": None})[0]["kind"] == "computation_unknown"


def test_a_draft_compared_with_a_registered_query(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "big_txn"},
        "right": {"sql": "SELECT txn_id, user_id, amount FROM txn WHERE amount > 500", "label": "draft"}}).json()
    assert body["left"]["query"] == "big_txn" and body["left"]["sql"].endswith("amount > 100")
    assert body["left"]["keys"] == ["txn_id"] and body["left"]["fingerprint"] == "abc123def456"
    # Measured totals are the registered query's, and only on its side.
    assert body["left"]["query_metrics"]["rowsIn"] == 1200
    assert body["right"]["query_metrics"] is None and body["right"]["registered_as"] is None
    changed = [c for c in body["plan"]["operators"] if c["change"] == "changed"]
    assert [(c["before"], c["after"]) for c in changed] == [("Filter(amount > 100)", "Filter(amount > 500)")]
    assert body["consequences"][0]["kind"] == "separate_computation"
    assert body["same_sql"] is False


def test_a_draft_that_is_a_registered_querys_text_carries_its_fingerprint(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "hot"}, "right": {"sql": "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"}}).json()
    assert body["same_sql"] is True and body["plan"]["identical"] is True
    assert body["right"]["registered_as"] == "hot, hot_alias" and body["right"]["fingerprint"] == "fff000"
    assert body["consequences"][0]["kind"] == "fingerprint_same"


def test_two_drafts_compare_without_registry_findings(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"sql": "SELECT a FROM txn WHERE amount > 1", "label": "one"},
        "right": {"sql": "SELECT a FROM txn WHERE amount > 1", "label": "two"}}).json()
    assert body["left"]["query_metrics"] is None and body["left"]["keys"] == []
    assert body["consequences"][0]["kind"] == "may_share_drafts"
    assert not any(f["kind"].startswith("keys_") for f in body["consequences"])


def test_a_side_the_engine_will_not_plan_is_partial_and_the_rest_still_answers(signed_in):
    body = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "big_txn"}, "right": {"sql": "SELECT PLANONLY FROM txn"}}).json()
    assert body["right"]["graph"] is None and body["right"]["plan_error"]["code"] == "PRV-2050"
    assert body["right"]["output_fields"] is None
    assert body["plan"] is None and body["left"]["graph"] is not None
    kinds = [f["kind"] for f in body["consequences"]]
    assert kinds[0] == "computation_unknown" and "schema_unknown" in kinds


def test_a_plan_the_policy_withholds_is_not_permitted_not_an_error(signed_in, engine):
    engine.plan_refused["big_txn"] = "reading plans needs one of the roles [ops]"
    answer = signed_in.post("/api/v1/sql/diff", json={
        "left": {"query": "big_txn"}, "right": {"sql": "SELECT * FROM txn"}})
    assert answer.status_code == 200
    left = answer.json()["left"]
    assert left["refused"]["status"] == 403 and "roles [ops]" in left["refused"]["message"]
    assert left["graph"] is None and left["query_metrics"] is None


def test_comparing_needs_something_on_each_side(signed_in, engine_down):
    assert signed_in.post("/api/v1/sql/diff", json={"left": {"query": "big_txn"}, "right": {}}).status_code == 400
    assert signed_in.post("/api/v1/sql/diff", json={"left": {"query": "nope"},
                                                    "right": {"sql": "SELECT 1"}}).status_code == 404
    assert engine_down.post("/api/v1/sql/diff", json={"left": {"query": "big_txn"},
                                                      "right": {"sql": "SELECT 1"}}).status_code == 503


def test_the_workbench_offers_the_compare_topic(signed_in):
    page = signed_in.get("/workbench?query=big_txn").text
    assert 'href="/help/topics/compare-versions"' in page
    assert signed_in.get("/help/topics/compare-versions").status_code == 200


# ============================================================ the UI string catalog

def test_every_ui_string_key_a_template_or_island_uses_is_in_the_catalog():
    """A typo in a key renders the key; this is what stops `nav.catlog` shipping in a nav bar."""
    from core.i18n import Messages

    messages = Messages()
    used: set[str] = set()
    prefixes: set[str] = set()
    for template in (CONSOLE_ROOT / "web" / "templates").glob("*.html"):
        text = template.read_text(encoding="utf-8")
        used |= set(re.findall(r"""\bt\(\s*["']([a-z0-9_.]+)["']\s*[,)]""", text))
        prefixes |= set(re.findall(r"""\bt\(\s*["']([a-z0-9_.]+\.)["']\s*~""", text))
    # The islands, and the classic per-screen scripts, which reach the same js.* keys through
    # PravahaApi.t (api.js).
    scripts = [*(CONSOLE_ROOT / "web" / "static" / "app").glob("*.js"),
               *(CONSOLE_ROOT / "web" / "static" / "js").glob("*.js")]
    for island in scripts:
        text = island.read_text(encoding="utf-8")
        used |= {"js." + k for k in re.findall(r"""\bt\(\s*"([a-z0-9_.]+)"\s*[,)]""", text)}
        prefixes |= {"js." + k for k in re.findall(r"""\bt\(\s*"([a-z0-9_.]+\.)"\s*\+""", text)}
    assert len(used) > 40
    missing = sorted(k for k in used if k not in messages.catalog)
    assert not missing, f"keys used but not in web/i18n/en.json: {missing}"
    for prefix in prefixes:
        assert any(k.startswith(prefix) for k in messages.catalog), prefix


def test_every_key_a_route_hands_a_page_is_in_the_catalog():
    """Routes pass keys, not sentences: the palette's entries, a not-found page's "Back to …",
    the roles. A key a route names and the catalog lacks would reach the browser as the key."""
    from core.i18n import Messages
    from routes.base import ROLES

    messages = Messages()
    used: set[str] = set()
    for module in (CONSOLE_ROOT / "routes").glob("*.py"):
        text = module.read_text(encoding="utf-8")
        used |= set(re.findall(r"""\b(?:t|ui_text)\(\s*f?["']([a-z0-9_.]+)["']""", text))
        # page("landing", "/") in the palette: palette.page.<key> and palette.hint.<key>
        for key in re.findall(r"""\bpage\(\s*"([a-z_]+)",\s*"/""", text):
            used |= {f"palette.page.{key}", f"palette.hint.{key}"}
    for meta in ROLES.values():
        used |= {meta["label"], meta["blurb"]}
    for area in ("help", "tutorials"):
        used |= {f"help.area.{area}.{part}" for part in ("kicker", "heading", "title", "blurb")}
    assert len(used) > 50
    missing = sorted(k for k in used if "." in k and k not in messages.catalog)
    assert not missing, f"keys a route uses but web/i18n/en.json lacks: {missing}"


def test_no_user_visible_english_bypasses_the_catalog():
    """The islands, the classic scripts and the templates say nothing to a person that is not
    looked up by key. A new hard-coded sentence, label, placeholder or title fails here with its
    file and line; tests/i18n_scan.py says what it reads and ALLOWED what it lets through."""
    from i18n_scan import scan

    found = scan()
    assert not found, "user-visible English outside web/i18n/en.json:\n  " + "\n  ".join(found)


def test_the_english_guard_has_teeth():
    """What the guard must catch, and what it must leave alone, on snippets written for it."""
    from i18n_scan import _css_classes, scan_script_text, scan_template_text

    classes = _css_classes()
    caught = [
        'const v = html`<p class="small text-muted">Nothing here yet</p>`;',
        'const v = html`<button title="Close the panel">×</button>`;',
        'const v = html`<input placeholder="type a name" />`;',
        'const v = html`<div>${ok ? html`<span>Saved</span>` : null}</div>`;',
        'announce("Snippet saved");',
        'button.textContent = "Copied";',
        'const PANELS = [["run", "Run"]];',
        'const v = html`<span>${busy ? "Registering…" : "Register"}</span>`;',
        'throw new Error("the editor did not load");',
    ]
    for snippet in caught:
        assert scan_script_text("x.js", snippet, classes), snippet
    left_alone = [
        'const v = html`<p class="small text-muted">${t("wb.run.idle_body")}</p>`;',
        'const v = html`<div class="d-flex gap-2"><code>PT24H</code> ${t("wb.reg.or")}</div>`;',
        'el.className = "btn btn-sm btn-outline-secondary";',
        'if (event.key === "ArrowRight") next();',
        'setState({ status: "loading" });',
        '/* A comment in English is for the next person reading the code. */ go();',
        'const re = /\\bFROM\\s+([A-Za-z_]\\w*)/i;',
        'call("/sql/validate", { json: { sql } });',
    ]
    for snippet in left_alone:
        assert not scan_script_text("x.js", snippet, classes), snippet
    assert scan_template_text("x.html", "<h1>Hello there</h1>", classes)
    assert scan_template_text("x.html", '<input placeholder="a name">', classes)
    assert not scan_template_text("x.html", "<h1>{{ t('nav.catalog') }}</h1>", classes)
    assert not scan_template_text("x.html", '<p><a href="/help/codes/PRV-2041">PRV-2041</a></p>', classes)
    assert not scan_template_text("x.html", '<span translate="no">RUNNING</span>', classes)
    assert not scan_template_text("x.html", "<script>const a = 'Some words';</script>", classes)


def test_the_catalog_fills_named_parameters_and_says_when_one_is_missing():
    from core.i18n import Messages, MissingMessage

    messages = Messages()
    assert messages("plugins.columns", n=4) == "4 columns"
    assert messages("no.such.key") == "no.such.key"
    strict = Messages(strict=True)
    with pytest.raises(MissingMessage):
        strict("no.such.key")
    with pytest.raises(MissingMessage):
        strict("plugins.columns")  # needs n
    assert messages.for_script()["palette.label"] == "Command palette"
    # An unknown language falls back to English rather than rendering keys.
    assert Messages("xx")("nav.catalog") == "Catalog"


def test_the_shell_and_the_palette_speak_from_the_catalog(signed_in):
    page = signed_in.get("/catalog").text
    assert '<script type="application/json" id="i18n-messages">' in page
    assert '"palette.placeholder"' in page
    assert ">Skip to content<" in page and 'aria-label="Primary"' in page
