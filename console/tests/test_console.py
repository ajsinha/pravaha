"""The console against a real engine.

Started here the same way the Python SDK's tests start one: the actual Java server, with a
registry, from the classpath the Maven build writes. A console tested against a fake engine
would prove the fake works.
"""
import os
import pathlib
import subprocess
import sys
import time

import pytest

pytest.importorskip("pyarrow", reason="the console needs the SDK's flight extra")
fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from core.config.properties_configurator import PropertiesConfigurator
from core.engine import Engine
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
def engine_url():
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
    port = None
    deadline = time.time() + 90
    while time.time() < deadline:
        line = process.stdout.readline()
        if not line:
            break
        if line.startswith("PRAVAHA_FLIGHT_PORT="):
            port = int(line.strip().split("=", 1)[1])
            break
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
            + "' (set JAVA_HOME, or put java 21 on PATH). Output: "
            + (tail or "none")
        )

    yield f"grpc://localhost:{port}"
    process.kill()
    process.wait(timeout=30)


@pytest.fixture
def client(engine_url):
    """The real application, built the way `run_pravaha_web.py` builds it.

    Through the configurator rather than by constructing services directly, so
    the tests exercise the wiring an operator actually gets -- including which
    templates exist and which routes are registered.
    """
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", engine_url)
    config.set("console.password", CONSOLE_PASSWORD)
    config.set("console.session_secret", "test-only-secret")
    client = fastapi_testclient.TestClient(create_app(config))
    # Signed in, because every state-changing route is gated now. A fixture that did not
    # would exercise the login redirect instead of the thing each test is about -- and the
    # gate itself is tested directly, below.
    client.post("/login", data={"password": CONSOLE_PASSWORD, "next": "/overview"})
    return client


CONSOLE_PASSWORD = "test-console-password"


@pytest.fixture
def anonymous(engine_url):
    """A client that has not signed in."""
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", engine_url)
    config.set("console.password", CONSOLE_PASSWORD)
    config.set("console.session_secret", "test-only-secret")
    return fastapi_testclient.TestClient(create_app(config))


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
    assert "life" not in client.get("/").text


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
    assert "<pre>" in page            # its code blocks survive


def test_a_guide_links_to_other_guides_inside_the_console(client):
    page = client.get("/help/quickstart").text

    # Cross-references stay in the console rather than pointing at files on disk.
    # The documents link to each other as `CONCEPTS.md`, which is right in a
    # checkout and a dead link here, so the renderer repoints them.
    assert "/help/concepts" in page
    # No LINK may point at a .md file. Checked on hrefs rather than on the whole page,
    # because the documents legitimately mention filenames in prose -- the quick start
    # explains the `include: docs/CONCEPTS.md` mechanism itself, and that is not a broken
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
    page = client.get("/tutorials/trade-processing").text

    assert "trade_event_id" in page
    assert "<table" in page


def test_an_unknown_case_study_is_refused(client):
    # Two layers refuse this and either is fine: the router normalises the path away before the
    # handler sees it, and the handler's allow-list would refuse the name anyway. What matters is
    # that nothing outside the five studies is ever read from disk.
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
        # nothing recorded who did.
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


def test_a_wrong_password_is_refused(anonymous):
    response = anonymous.post("/login", data={"password": "not it", "next": "/overview"})

    assert response.status_code == 401
    assert "not the console password" in response.text


def test_with_no_password_configured_nobody_can_sign_in(engine_url):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", engine_url)
    config.set("console.password", "")
    config.set("console.session_secret", "test-only-secret")
    unconfigured = fastapi_testclient.TestClient(create_app(config))

    # The safe failure. A default password is a public password, and this console can drop
    # queries -- so an unset one locks the controls rather than opening them.
    page = unconfigured.get("/login")
    assert "No console password is set" in page.text
    assert unconfigured.post("/login", data={"password": "", "next": "/"}).status_code == 401
