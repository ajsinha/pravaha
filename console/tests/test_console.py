"""The console against a real engine.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Started here the same way the Python SDK's tests start one: the actual Java server, with a
registry, from the classpath the Maven build writes. A console tested against a fake engine
would prove the fake works.
"""
import os
import pathlib
import subprocess
import sys
import threading
import time

import pytest

pytest.importorskip("pyarrow", reason="the console needs the SDK's flight extra")
fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from fake_identity import csrf_of, sign_in

from core.config.properties_configurator import PropertiesConfigurator
from core.engine import Engine, EngineHttpError
from run_pravaha_web import create_app

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]
FLIGHT_CLASSES = REPO_ROOT / "pravaha-flight" / "target" / "test-classes"

TRADE_SQL = "SELECT trade_id, product_type, trade_json FROM trade"


def _classpath() -> str:
    entries = [str(FLIGHT_CLASSES), str(REPO_ROOT / "pravaha-flight" / "target" / "classes")]
    written = REPO_ROOT / "pravaha-flight" / "target" / "test-classpath.txt"
    if written.exists():
        entries.append(written.read_text().strip())
    return os.pathsep.join(entries)


@pytest.fixture(scope="module")
def engine():
    """The engine's URL and the path of the file it tails, as a pair."""
    if not FLIGHT_CLASSES.exists():
        pytest.skip("pravaha-flight is not built; run ./mvnw -pl pravaha-flight test-compile")
    java = os.environ.get("JAVA_HOME", "")
    java_bin = str(pathlib.Path(java) / "bin" / "java") if java else "java"

    process = subprocess.Popen(
        [
            java_bin,
            "--add-opens=java.base/java.nio=ALL-UNNAMED",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-cp",
            _classpath(),
            "com.ash.messaging.pravaha.flight.TestFlightServerMain",
            "0",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    # Drained on a thread for the whole run. Reading until the port appears and then stopping
    # leaves the pipe to fill, and the next thing the engine writes blocks it -- which presents as
    # an engine that accepts a subscription and never delivers, and is indistinguishable from a
    # product defect until you look at the process.
    found = {}

    def drain():
        while True:
            line = process.stdout.readline()
            if not line:
                return
            if line.startswith("PRAVAHA_FLIGHT_PORT="):
                found["port"] = int(line.strip().split("=", 1)[1])
            elif line.startswith("PRAVAHA_FEED_FILE="):
                found["feed"] = line.strip().split("=", 1)[1]

    threading.Thread(target=drain, daemon=True).start()
    deadline = time.time() + 90
    while time.time() < deadline and not ("port" in found and "feed" in found):
        time.sleep(0.05)
    port = found.get("port")
    if port is None:
        process.kill()
        tail = ""
        try:
            tail = (process.stdout.read() or "")[-400:]
        except Exception:  # noqa: BLE001, S110 -- the output is a nicety; the skip is the point
            pass
        # Says why. This used to skip with no reason, and the commonest cause is JAVA_HOME
        # being unset in the shell running pytest -- at which point nineteen tests quietly
        # vanish and the suite still reports success, which is the worst way for a test to
        # fail.
        pytest.skip(
            "the Pravaha server did not start using java at '"
            + java_bin
            + "' (set JAVA_HOME to a JDK 25, or put java 25 on PATH). Output: "
            + (tail or "none")
        )

    yield f"grpc://localhost:{port}", found.get("feed")
    process.kill()
    process.wait(timeout=30)


@pytest.fixture(scope="module")
def engine_url(engine):
    """Just the URL, for the many tests that do not feed anything."""
    return engine[0]


@pytest.fixture
def feed(engine):
    """Appends rows to the stream the engine is tailing, so a query can actually change.

    Without this the console's tests ran against a real engine and a query nothing fed: every
    screen was exercised against a view that would never move, which is most of what an operator
    console exists to show.
    """
    feed_file = engine[1]
    if feed_file is None:
        pytest.skip("the engine did not report a feed file; rebuild pravaha-flight's test classes")

    def append(trade_id, product_type, payload="{}", weight=1):
        with open(feed_file, "a", encoding="utf-8") as handle:
            handle.write(f"{trade_id},{product_type},{payload},{weight}\n")
            handle.flush()

    return append


USER = "operator-one"
PASSWORD = "Test-console-password-1"
#: The engine session the stand-in hands out: a secret the console holds for the person, in its
#: signed cookie, and repeats on no page.
SESSION_TOKEN = "prv_s_s3cret-engine-session-token-should-never-render"
SESSION_SECRET = "s3cret-session-signing-key-should-never-render"


class SignInOnly(Engine):
    """The real engine adapter, with ADR-052's identity endpoints answered as the engine answers
    them -- because the Flight test server these tests start has no HTTP surface at all. Every
    Flight call is the real one, carrying the session the stand-in issued; the server here does
    not authenticate, so what these tests prove is that the console signs in, holds the session
    and carries it, not that the engine checks it (the engine's own tests do)."""

    def login(self, username, password, for_address=None):
        if (username, password) != (USER, PASSWORD):
            raise EngineHttpError(401, "the username or password was not accepted", "PRV-7010")
        return {"token": SESSION_TOKEN, "expiresAt": "2099-01-01T00:00:00Z",
                "mustChangePassword": False, "mfa": "ok"}

    def me(self):
        return {"username": USER, "principal": USER, "roles": ["operator"], "tenant": "public"}

    def logout(self):
        return None


def _app(engine_url, secret="test-only-secret", **settings):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", engine_url)
    # No HTTP surface: the Flight test server has none, and the default names localhost:18080 -- where
    # a developer's own node may be running, with credentials these tests do not hold. Left in, the
    # suite's answer depended on what else was running on the machine.
    config.set("engine.http_url", "")
    config.set("console.session_secret", secret)
    for key, value in settings.items():
        config.set(key, value)
    return fastapi_testclient.TestClient(create_app(config, engine=SignInOnly(
        engine_url, http_url=config.get("engine.http_url") or None)))


@pytest.fixture
def client(engine_url):
    """The real application, built the way `run_pravaha_web.py` builds it, over the real
    engine adapter.

    Through the configurator rather than by constructing services directly, so
    the tests exercise the wiring an operator actually gets -- including which
    templates exist and which routes are registered.
    """
    client = _app(engine_url)
    # Signed in, because every route that names a registered query -- reading or writing --
    # is gated now. A fixture that did not would exercise the login redirect instead of the
    # thing each test is about -- and the gate itself is tested directly, below.
    sign_in(client, USER, PASSWORD, "/overview")
    return client


@pytest.fixture
def anonymous(engine_url):
    """A client that has not signed in, holding its session's CSRF token as a browser that has
    opened the sign-in form does -- so what refuses it is the sign-in gate."""
    client = _app(engine_url)
    client.headers["X-CSRF-Token"] = csrf_of(client.get("/login").text)
    return client


@pytest.fixture
def secretive_client(engine_url):
    """Every secret this console holds set to a distinctive, greppable value: the session
    secret, and the engine session the person was issued."""
    client = _app(engine_url, secret=SESSION_SECRET)
    sign_in(client, USER, PASSWORD, "/overview")
    return client


def test_health_reports_a_reachable_engine(client):
    body = client.get("/health").json()

    # The console's own health at the top, what it can see of the engine nested
    # under it. Conflating the two is how a monitor ends up reporting the console
    # as down when the console is fine and saying so.
    assert body["status"] == "healthy"
    assert body["engine"]["reachable"] is True
    assert "queries" in body["engine"]


def test_health_reports_an_unreachable_engine_rather_than_failing():
    # A console whose own health endpoint 500s when the engine is down cannot tell you the
    # engine is down, which is the one thing you need it for at that moment.
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", "grpc://localhost:1")
    offline = fastapi_testclient.TestClient(create_app(config))
    response = offline.get("/health")

    assert response.status_code == 200
    assert response.json()["engine"]["reachable"] is False


def test_the_overview_lists_registered_queries(client):
    client.post("/queries", data={"name": "console_a", "sql": TRADE_SQL, "keys": "0"})
    try:
        page = client.get("/overview").text
        assert "console_a" in page
        assert "RUNNING" in page
    finally:
        client.post("/queries/console_a/drop")


def test_the_landing_page_says_what_this_is_without_an_engine():
    # `/` answers "what is this server", not "what is it doing". Somebody arriving
    # at a bare host name is at least as likely to be asking the first, and it must
    # answer even when the engine is down -- which is when it is most likely to be
    # the page somebody lands on.
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", "grpc://localhost:1")
    offline = fastapi_testclient.TestClient(create_app(config))
    page = offline.get("/")

    assert page.status_code == 200
    assert "Ask once" in page.text
    assert "engine unreachable" in page.text


def test_the_index_shows_when_a_computation_is_shared(client):
    # Two names, one fingerprint: the claim is "ten analysts on one dashboard cost one
    # query", and an operator should be able to watch it holding.
    client.post("/queries", data={"name": "share_a", "sql": TRADE_SQL, "keys": "0"})
    client.post(
        "/queries",
        data={"name": "share_b", "sql": "SELECT t.trade_id, t.product_type, t.trade_json FROM trade AS t", "keys": "0"},
    )
    try:
        page = client.get("/overview").text
        assert "shared" in page
    finally:
        client.post("/queries/share_a/drop")
        client.post("/queries/share_b/drop")


def test_a_query_can_be_paused_resumed_and_dropped(client):
    client.post("/queries", data={"name": "life", "sql": TRADE_SQL, "keys": "0"})

    client.post("/queries/life/pause")
    assert "PAUSED" in client.get("/queries/life").text

    client.post("/queries/life/resume")
    assert "RUNNING" in client.get("/queries/life").text

    client.post("/queries/life/drop")
    # By name in the registry, not by substring on a page: "life" is in "lifecycle".
    assert "life" not in [q["name"] for q in client.get("/api/v1/queries").json()["items"]]


def test_registering_something_invalid_shows_the_engines_message(client):
    response = client.post("/queries", data={"name": "bad", "sql": "SELECT nope FROM nosuchstream", "keys": "0"})

    # The engine's own diagnosis, PRV code and all. A console that replaced it with
    # "registration failed" would be throwing away the part that says what to do.
    assert "PRV-" in response.text


def test_the_detail_page_shows_the_sql_and_fingerprint(client):
    client.post("/queries", data={"name": "detail", "sql": TRADE_SQL, "keys": "0"})
    try:
        page = client.get("/queries/detail").text
        assert "SELECT trade_id" in page
        assert "fingerprint" in page
    finally:
        client.post("/queries/detail/drop")


def test_dropping_a_query_needs_its_name_typed_not_just_clicked_through(client):
    # A plain OK/Cancel confirm() is exactly the dialog a hurried click clears without
    # reading. §23.16 requires typed confirmation of the object's name for a destructive
    # action, so the rendered page must carry the query's own name as the value the input
    # has to match -- not merely a generic "are you sure" string.
    client.post("/queries", data={"name": "typed_confirm", "sql": TRADE_SQL, "keys": "0"})
    try:
        page = client.get("/queries/typed_confirm").text
        assert 'data-expected="typed_confirm"' in page
        # The no-JavaScript path stays a real, working form -- a modal is JavaScript by
        # definition, and the console's own rule is that every control still works when a
        # script does not.
        assert '<form method="post" action="/queries/typed_confirm/drop"' in page
        assert 'id="dropConfirmSubmit"' in page and "disabled" in page
    finally:
        client.post("/queries/typed_confirm/drop")


def test_no_secret_is_ever_serialised_to_the_browser(secretive_client):
    # §23.20's own words. The person's engine session and the session-signing secret are the
    # two values this console holds that must never appear in anything a page or an endpoint
    # answers -- the cookie is signed with the session secret, not carrying it, and the engine
    # session travels only in that HttpOnly cookie and on the console's own calls. Checked
    # across every page and API response the rest of the suite exercises, on a real running
    # engine and a registered query, rather than asserted about one screen in isolation.
    secretive_client.post(
        "/queries", data={"name": "secrets_check", "sql": TRADE_SQL, "keys": "0"})
    try:
        pages = [
            "/", "/about", "/help", "/help/concepts", "/tutorials",
            "/overview", "/queries", "/queries/secrets_check", "/workbench",
            "/api/v1/health", "/api/v1/queries", "/api/v1/queries/secrets_check",
            "/api/v1/stats",
        ]
        for path in pages:
            body = secretive_client.get(path).text
            assert SESSION_TOKEN not in body, f"engine session leaked on {path}"
            assert SESSION_SECRET not in body, f"session secret leaked on {path}"

        # A bad query's own error text is the likeliest accidental leak: an engine message
        # that happened to echo back configuration would land here first.
        bad = secretive_client.post(
            "/workbench", data={"sql": "SELECT * FROM nowhere", "params": ""}).text
        assert SESSION_TOKEN not in bad
        assert SESSION_SECRET not in bad

        # And the session cookie itself carries a signature, not the secret that produced it.
        session_cookie = secretive_client.cookies.get("pravaha_console") or ""
        assert session_cookie, "the console's session cookie is named pravaha_console"
        assert SESSION_SECRET not in session_cookie
    finally:
        secretive_client.post("/queries/secrets_check/drop")


def test_an_unknown_query_says_so(client):
    response = client.get("/queries/never_registered")

    # A 404, not a 200 with an apology in it: nothing is registered under that
    # name, and "not found" is both true and what a client can act on.
    assert response.status_code == 404
    assert "never_registered" in response.text
    assert "no such query" in response.text.lower()


def test_the_workbench_runs_a_parameterised_query(client):
    response = client.post(
        "/workbench",
        data={"sql": "SELECT user_id, total FROM user_volume WHERE total > ?", "params": "40"},
    )

    assert response.status_code == 200
    assert "u1" in response.text


def test_a_decimal_column_reaches_the_browser_exactly_and_plain(client):
    # FLIGHTDECIMAL-1: the workbench reads over Flight, which refused DECIMAL until 2.1. Now the
    # value arrives exact and goes to the browser as its digits -- a string, never a float, and
    # never 0E-10 for a zero at scale ten.
    body = client.post("/api/v1/query", json={"sql": "SELECT entry_id, amount, rate FROM ledger"}).json()

    assert "error" not in body, body
    assert body["types"][1:] == ["decimal128(18, 2)", "decimal128(38, 10)"]
    rows = {row[0]: row[1:] for row in body["rows"]}
    assert rows == {
        "e1": ["1234567890123456.78", "0.0000000001"],
        "e2": ["-0.01", "0.0000000000"],
        "e3": ["0.00", None],
    }


def test_a_bad_query_shows_the_error_rather_than_a_stack_trace(client):
    response = client.post("/workbench", data={"sql": "SELECT * FROM nowhere", "params": ""})

    assert "PRV-" in response.text
    assert "Traceback" not in response.text


# --- Help and documentation in the UI -------------------------------------------------------

def test_the_help_index_lists_the_guides(client):
    page = client.get("/help").text

    assert "Quick start" in page
    assert "Concepts" in page
    assert "Troubleshooting" in page


def test_a_guide_renders_from_the_repositorys_own_documentation(client):
    page = client.get("/help/concepts").text

    # Rendered, not linked away to: an operator reading a console is already where the
    # question arose, and sending them elsewhere loses the thread.
    assert "soundness rule" in page
    assert "<table>" in page          # its tables survive
    assert "<pre tabindex=\"0\">" in page   # its code blocks survive, and a keyboard can scroll them


def test_a_guide_links_to_other_guides_inside_the_console(client):
    page = client.get("/help/quickstart").text

    # Cross-references stay in the console rather than pointing at files on disk.
    # The documents link to each other as `CONCEPTS.md`, which is right in a
    # checkout and a dead link here, so the renderer repoints them.
    assert "/help/concepts" in page
    # No LINK may point at a .md file. Checked on hrefs rather than on the whole page,
    # because the documents legitimately mention filenames in prose -- the quick start
    # explains the `include: docs/guides/CONCEPTS.md` mechanism itself, and that is not a broken
    # link, it is the sentence describing why there are none.
    assert 'href="' not in page or ".md\"" not in page


def test_an_unknown_help_page_is_refused_rather_than_read_from_disk(client):
    # The allow-list is the control. A console that joined a name to a directory would serve
    # whatever was asked for, and normalising afterwards is never as reliable as not accepting
    # the name at all.
    for attempt in ["../../../etc/passwd", "..%2f..%2fetc%2fpasswd", "HANDOVER.md"]:
        response = client.get(f"/help/{attempt}")
        assert "no such page" in response.text or response.status_code == 404


def test_every_page_offers_contextual_help(client):
    client.post("/queries", data={"name": "helpful", "sql": TRADE_SQL, "keys": "0"})
    try:
        for path in ["/overview", "/queries", "/queries/helpful", "/workbench"]:
            page = client.get(path).text
            assert "helpcards" in page, f"{path} has no help card"
            # Each card points at the document that says the rest.
            assert "read more" in page
    finally:
        client.post("/queries/helpful/drop")


def test_help_is_reachable_from_every_page(client):
    for path in ["/", "/overview", "/queries", "/workbench", "/help", "/about"]:
        assert "/help" in client.get(path).text


def test_the_help_index_offers_the_worked_systems(client):
    page = client.get("/help").text

    # A developer deciding how to shape a query wants an example far more often than a
    # specification, and the worked systems are the most practical documentation there is.
    assert "Tutorials" in page
    assert "Working through Pravaha" in client.get("/tutorials").text


def test_a_case_study_renders_in_the_console(client):
    page = client.get("/help/case-studies/trade-processing").text

    assert "trade_event_id" in page
    assert "<table" in page


def test_a_case_studys_old_tutorial_address_moves_to_its_page(client):
    # The studies were served under /tutorials until they had their own section; an address
    # somebody kept still reaches the study, and says it moved.
    response = client.get("/tutorials/trade-processing", follow_redirects=False)
    assert response.status_code == 301
    assert response.headers["location"] == "/help/case-studies/trade-processing"
    assert client.get("/tutorials/card-velocity", follow_redirects=False).headers["location"] == (
        "/help/case-studies/banking-card-velocity")


def test_an_unknown_case_study_is_refused(client):
    # Two layers refuse this and either is fine: the router normalises the path away before the
    # handler sees it, and the handler's allow-list would refuse the name anyway. What matters is
    # that nothing outside the tutorials is ever read from disk.
    for attempt in ["../../etc", "nonexistent", "HANDOVER"]:
        response = client.get(f"/tutorials/{attempt}")
        assert response.status_code == 404


# --- The service layer and its API (ADR-033) --------------------------------------------

def test_the_api_lists_queries_as_json(client):
    client.post("/queries", data={"name": "api_a", "sql": TRADE_SQL, "keys": "0"})
    try:
        body = client.get("/api/v1/queries").json()

        assert body["total"] >= 1
        assert any(item["name"] == "api_a" for item in body["items"])
        # The browser gets paging context, not just rows: "20 of 847" and "20 of 3" mean
        # different things to a reader and only the second means the filter worked.
        assert {"items", "total", "offset", "limit"} <= set(body)
    finally:
        client.post("/queries/api_a/drop")


def test_a_retention_chosen_in_the_console_reaches_the_engine_and_comes_back_in_its_listing(client):
    # End to end over Flight: the register form's retention travels as the fifth field of
    # pravaha.register, and the engine's listing reports it with the key as trailing fields.
    client.post("/queries", data={"name": "kept_6h", "sql": TRADE_SQL, "keys": "0", "retention": "PT6H"})
    try:
        items = client.get("/api/v1/queries").json()["items"]
        kept = [item for item in items if item["name"] == "kept_6h"]
        assert kept, items
        assert kept[0]["retention"] == "PT6H"
        assert kept[0]["key_columns"] == [0]
        assert kept[0]["sink"] is None
    finally:
        client.post("/queries/kept_6h/drop")


def test_the_api_filters_and_the_filter_is_the_url(client):
    client.post("/queries", data={"name": "findme", "sql": TRADE_SQL, "keys": "0"})
    try:
        assert client.get("/api/v1/queries?search=findme").json()["total"] == 1
        # Filtered to nothing is a state, not an error: there are queries, just not these.
        assert client.get("/api/v1/queries?search=nothing_like_this").json()["total"] == 0
    finally:
        client.post("/queries/findme/drop")


def test_an_api_error_carries_the_engines_code(client):
    body = client.post("/api/v1/query", json={"sql": "SELECT * FROM nowhere"}).json()

    # The PRV code travels so the UI can link straight to the entry in TROUBLESHOOTING
    # instead of leaving the reader to search for the useful part of a long message.
    assert "error" in body
    assert body.get("code", "").startswith("PRV-")


def test_the_browsers_correlation_id_reaches_the_servers_own_log(client, caplog):
    # api.js generates a correlation id, sends it as X-Correlation-Id, and shows it on
    # screen -- but that only means something if the same string lands in the log an
    # operator would actually search. Before this, json_guard never read the header at
    # all: the id on screen and the id in the log were two different pieces of paper.
    import logging

    caplog.set_level(logging.WARNING, logger="routes.base")
    response = client.post(
        "/api/v1/query", json={"sql": "SELECT * FROM nowhere"},
        headers={"X-Correlation-Id": "test-corr-abc123"})

    assert response.status_code >= 400
    assert any("test-corr-abc123" in record.message for record in caplog.records), (
        "the request's own correlation id never reached the server's log line"
    )


def test_a_request_with_no_correlation_id_still_logs_cleanly(client, caplog):
    # The server-rendered forms (pause/resume/drop) never run api.js and so never send
    # this header -- that must not be an error, or every no-JavaScript action would throw.
    import logging

    caplog.set_level(logging.WARNING, logger="routes.base")
    response = client.post("/api/v1/query", json={"sql": "SELECT * FROM nowhere"})

    assert response.status_code >= 400
    # With no id sent, the console gives the request one (core.observability.RequestContext): the
    # log line carries it, and so does the response, so the two can still be matched.
    given = response.headers["x-correlation-id"]
    assert any(f"[{given}]" in record.message for record in caplog.records)


def test_one_engine_subscription_serves_every_browser(engine_url):
    # The product's claim is that ten analysts asking one question cost one computation. A
    # console that opened a subscription per tab would quietly contradict it.
    from core.services import Services

    services = Services(Engine(engine_url))
    first = services.feeds.subscribe("user_volume")
    second = services.feeds.subscribe("user_volume")
    try:
        assert services.feeds.live_feeds() == 1
    finally:
        first.close()
        assert services.feeds.live_feeds() == 1, "the last subscriber has not left yet"
        second.close()

    # And it is released when the last one goes. Before this was ref-counted, a closed tab
    # left its engine subscription open for the life of the process.
    assert services.feeds.live_feeds() == 0


def test_a_slow_browser_loses_its_oldest_rows_rather_than_blocking(engine_url):
    from core.services import Broadcaster, Services

    services = Services(Engine(engine_url))
    subscriber = services.feeds.subscribe("user_volume")
    try:
        for i in range(Broadcaster.BUFFER + 25):
            subscriber.offer({"n": i})

        # Dropped, and counted. Blocking here would push back on the engine's own
        # subscriber, which would make one slow browser everybody's problem.
        assert subscriber.dropped == 25
        newest = subscriber.drain(limit=Broadcaster.BUFFER + 50)
        assert newest[-1]["n"] == Broadcaster.BUFFER + 24
    finally:
        subscriber.close()


def test_the_console_shows_a_continuous_query_answering(client, feed):
    """A query registered from the console, fed, and its answer visible on the console.

    Every screen test above runs against a real engine and a query nothing feeds -- so they prove
    the pages render, and prove nothing about what an operator is actually there to watch. This one
    changes the data underneath and reads the change back through the console's own HTTP surface.
    """
    client.post("/queries", data={"name": "ui_live", "sql": TRADE_SQL, "keys": "0"})
    try:
        feed("UI-1", "SWAP")
        feed("UI-2", "EQUITY")

        deadline = time.time() + 30
        seen = 0
        while time.time() < deadline:
            seen = _rows_in(client.get("/api/v1/queries").json(), "ui_live")
            if seen >= 2:
                break
            time.sleep(0.2)

        assert seen >= 2, f"the console never saw the rows the query consumed: {seen}"

        # And the query's own page reports it, which is the screen an operator opens.
        detail = client.get("/api/v1/queries/ui_live").json()
        assert detail["rows_in"] >= 2
        assert detail["state"] == "RUNNING"
    finally:
        client.post("/queries/ui_live/drop")


def test_the_consoles_query_page_reports_rows_arriving(client, feed):
    """The detail page's row count moves as the stream moves.

    An operator's first question about a query is whether it is doing anything. A page that renders
    a fixed zero answers it wrongly and looks healthy doing so.
    """
    client.post("/queries", data={"name": "ui_counting", "sql": TRADE_SQL, "keys": "0"})
    try:
        before = client.get("/api/v1/queries").json()
        feed("UI-10", "SWAP")
        feed("UI-11", "SWAP")

        deadline = time.time() + 30
        after = before
        while time.time() < deadline:
            after = client.get("/api/v1/queries").json()
            if _rows_in(after, "ui_counting") > _rows_in(before, "ui_counting"):
                break
            time.sleep(0.2)

        assert _rows_in(after, "ui_counting") > _rows_in(before, "ui_counting"), (
            "the console's query list never showed the rows arriving"
        )
        page = client.get("/queries/ui_counting")
        assert page.status_code == 200
    finally:
        client.post("/queries/ui_counting/drop")


def _rows_in(payload, name):
    """The rows-in count the console reports for one query."""
    for entry in payload.get("items", []):
        if entry.get("name") == name:
            return entry.get("rows_in") or 0
    return 0


def test_a_browser_watching_a_view_receives_what_the_engine_publishes(engine, feed):
    """The live tail: one engine subscription, fanned out to browsers, actually carrying rows.

    The ref-counting test above proves one subscription serves many watchers. It does not prove
    that anything travels along it -- and nothing did, because nothing fed the stream.
    """
    from core.services import Services

    engine_url, _ = engine
    services = Services(Engine(engine_url))
    client_side = fastapi_testclient  # noqa: F841 -- imported for symmetry with the other tests

    # Register through the SDK the console itself uses, then watch the view it maintains.
    services.engine.register("ui_tail", TRADE_SQL, [0])
    subscriber = services.feeds.subscribe("ui_tail")
    try:
        time.sleep(1.0)
        feed("UI-20", "SWAP")

        deadline = time.time() + 30
        seen = []
        while time.time() < deadline and not seen:
            seen = subscriber.drain(limit=50)
            if seen:
                break
            time.sleep(0.2)

        assert seen, "a browser watching the view received nothing while the stream moved"
    finally:
        subscriber.close()
        try:
            services.engine.drop("ui_tail")
        except Exception:  # noqa: BLE001,S110 -- the drop is cleanup, not the assertion
            pass


def test_a_browser_starts_from_rows_committed_before_it_opened_the_view(engine, feed):
    """SUB-1 through the console against the real engine: the starting view comes with the stream.

    A row committed before anyone watched is in the view; the live page used to learn it from a
    separate read, and a commit between that read and the stream was lost. Now the stream's own
    first event carries it, and the change after it follows.
    """
    from core.services import Services

    engine_url, _ = engine
    services = Services(Engine(engine_url))
    # Its own SQL, so it is its own computation: the same question as another test's would be
    # shared with it (one question, one computation) and start from that test's rows.
    services.engine.register("ui_snap", "SELECT trade_id, product_type FROM trade", [0])
    subscriber = None
    try:
        feed("UI-40", "SWAP")
        deadline = time.time() + 30
        while time.time() < deadline and not services.engine.query_typed("SELECT trade_id FROM ui_snap")[1]:
            time.sleep(0.1)

        subscriber = services.feeds.subscribe("ui_snap")
        taken = None
        deadline = time.time() + 30
        while time.time() < deadline and taken is None:
            taken = subscriber.take_snapshot()
            time.sleep(0.05)
        assert taken is not None, "the stream never gave a starting view"
        rows, frontier = taken
        assert "UI-40" in [row["trade_id"] for row in rows]
        assert frontier is not None

        feed("UI-41", "EQUITY")
        seen = []
        deadline = time.time() + 30
        while time.time() < deadline and not seen:
            seen = subscriber.drain(limit=50)
            time.sleep(0.1)
        assert ("UI-41", 1) in [(row["trade_id"], row["_weight"]) for row in seen]
        assert "UI-40" not in [row["trade_id"] for row in seen], "a row in the starting view is not sent again"
    finally:
        if subscriber is not None:
            subscriber.close()
        try:
            services.engine.drop("ui_snap")
        except Exception:  # noqa: BLE001,S110 -- the drop is cleanup, not the assertion
            pass


def test_every_screen_renders_before_its_javascript_does(client):
    client.post("/queries", data={"name": "norender", "sql": TRADE_SQL, "keys": "0"})
    try:
        # The data is in the server's HTML, not fetched afterwards. A console that is blank
        # until a module loads is blank exactly when somebody is looking at it because
        # something is not loading.
        assert "norender" in client.get("/queries").text
        assert "SELECT trade_id" in client.get("/queries/norender").text
    finally:
        client.post("/queries/norender/drop")


# --- The console's own gate ---------------------------------------------------------------

def test_an_anonymous_visitor_cannot_drop_a_query(anonymous, client):
    client.post("/queries", data={"name": "guarded", "sql": TRADE_SQL, "keys": "0"})
    try:
        # Before this gate existed, the console held one engine token and acted as it for
        # everyone: anyone who could reach the port could destroy production state, and
        # nothing recorded who did. Since ADR-052 it holds none: a visitor has no engine
        # session, so there is nobody the console could act as.
        refused = anonymous.post("/queries/guarded/drop", follow_redirects=False)

        assert refused.status_code == 303
        assert "/login" in refused.headers["location"]
        assert "guarded" in client.get("/queries").text, "the query must still be there"
    finally:
        client.post("/queries/guarded/drop")


def test_the_api_refuses_an_anonymous_mutation_with_401_not_a_redirect(anonymous):
    # A fetch that received a login page as data would report a parse error rather than a
    # permission problem, so the API answers with a status a client can act on.
    response = anonymous.post("/api/v1/queries", json={"name": "x", "sql": TRADE_SQL, "keys": [0]})

    assert response.status_code == 401
    assert "sign in" in response.json()["error"]


def test_reading_stays_open_to_an_anonymous_visitor(anonymous):
    # The landing page, the documentation and the health probes are deliberately not gated:
    # an operator opening the console during an incident needs it to load and say what is
    # wrong before they find their password.
    assert anonymous.get("/").status_code == 200
    assert anonymous.get("/help").status_code == 200
    assert anonymous.get("/health").status_code == 200


def test_reading_what_is_registered_is_not_open_to_an_anonymous_visitor(anonymous, client):
    # Distinct from the test above on purpose. A registered query's name, its SQL and its
    # live row-level output are the engine's own data, reached with the console's one shared
    # engine identity -- not console chrome. Before this gate existed, an anonymous visitor
    # who merely reached the console's port saw exactly what a signed-in operator saw: the
    # read half of the exact defect the login system's own docstring (routes/auth_routes.py)
    # says it exists to close on the write side. This is the SX-5 shape: a caller learning
    # what exists (and here, what it says, and what it is producing) through a door the
    # engine's own authorization never sees.
    client.post("/queries", data={"name": "guarded_read", "sql": TRADE_SQL, "keys": "0"})
    try:
        for path in ("/overview", "/queries", "/queries/guarded_read"):
            refused = anonymous.get(path, follow_redirects=False)
            assert refused.status_code == 303, path
            assert "/login" in refused.headers["location"], path

        for path in (
            "/api/v1/queries",
            "/api/v1/queries/guarded_read",
            "/api/v1/stats",
            "/api/v1/views/guarded_read/stream",
        ):
            refused = anonymous.get(path)
            assert refused.status_code == 401, path
            assert "sign in" in refused.json()["error"], path

        # And the same page, signed in, still shows it -- the gate refuses the caller, not
        # the query.
        assert "guarded_read" in client.get("/queries").text
    finally:
        client.post("/queries/guarded_read/drop")


@pytest.mark.parametrize("path", ["/admin", "/admin/access", "/admin/audit",
                                  "/admin/audit?principal=ann&decision=deny", "/plugins"])
def test_the_admin_screens_are_behind_the_sign_in_gate(anonymous, path):
    # The audit trail names every principal that read anything and the SQL they read it with.
    # The engine authorizes reading it; the console must not become a door that asks nobody.
    refused = anonymous.get(path, follow_redirects=False)
    assert refused.status_code == 303, path
    assert "/login" in refused.headers["location"], path


@pytest.mark.parametrize("path", ["/api/v1/admin/audit", "/api/v1/admin/audit?principal=ann",
                                  "/api/v1/admin/permissions", "/api/v1/plugins"])
def test_the_admin_json_refuses_an_anonymous_caller_with_401(anonymous, path):
    refused = anonymous.get(path)
    assert refused.status_code == 401, path
    assert "sign in" in refused.json()["error"], path


def test_the_audit_screen_names_the_missing_http_url_rather_than_showing_an_empty_trail(engine_url):
    # This engine is reached over Flight only, and the audit trail is an HTTP endpoint: with no
    # engine.http_url the screen says which setting is missing, and is not an empty table.
    client = _app(engine_url, **{"engine.http_url": ""})
    sign_in(client, USER, PASSWORD, "/overview")
    page = client.get("/admin/audit")
    assert page.status_code == 503
    assert "could not be read" in page.text and "engine.http_url" in page.text
    assert 'id="audit-events"' not in page.text


def test_a_wrong_password_is_refused(engine_url):
    client = _app(engine_url)
    response = sign_in(client, USER, "not it", "/overview", keep_token=False)

    assert response.status_code == 401
    assert "were not accepted" in response.text
    assert client.get("/overview", follow_redirects=False).status_code == 303


def test_the_console_has_no_password_of_its_own(engine_url):
    # ADR-052. The console's old shared password is not read any more: set it, and signing in
    # with it is refused like any other wrong password, because only the engine checks one.
    client = _app(engine_url, **{"console.password": "the-old-shared-password"})
    response = sign_in(client, "", "the-old-shared-password", "/", keep_token=False)
    assert response.status_code == 401
    assert "No console password is set" not in client.get("/login").text
