"""The console against a real engine.

Started here the same way the Python SDK's tests start one: the actual Java server, with a
registry, from the classpath the Maven build writes. A console tested against a fake engine
would prove the fake works.
"""
import os
import pathlib
import subprocess
import time

import pytest

pytest.importorskip("pyarrow", reason="the console needs the SDK's flight extra")
fastapi_testclient = pytest.importorskip("fastapi.testclient")

from pravaha_console import Engine, create_app  # noqa: E402

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
        pytest.skip("the Pravaha server did not start")

    yield f"grpc://localhost:{port}"
    process.kill()
    process.wait(timeout=30)


@pytest.fixture
def client(engine_url):
    return fastapi_testclient.TestClient(create_app(Engine(engine_url)))


def test_health_reports_a_reachable_engine(client):
    body = client.get("/health").json()

    assert body["reachable"] is True
    assert "queries" in body


def test_health_reports_an_unreachable_engine_rather_than_failing():
    # A console whose own health endpoint 500s when the engine is down cannot tell you the
    # engine is down, which is the one thing you need it for at that moment.
    offline = fastapi_testclient.TestClient(create_app(Engine("grpc://localhost:1")))
    response = offline.get("/health")

    assert response.status_code == 200
    assert response.json()["reachable"] is False


def test_the_index_lists_registered_queries(client):
    client.post("/queries", data={"name": "console_a", "sql": TRADE_SQL, "keys": "0"})
    try:
        page = client.get("/").text
        assert "console_a" in page
        assert "RUNNING" in page
    finally:
        client.post("/queries/console_a/drop")


def test_the_index_shows_when_a_computation_is_shared(client):
    # Two names, one fingerprint: the claim is "ten analysts on one dashboard cost one
    # query", and an operator should be able to watch it holding.
    client.post("/queries", data={"name": "share_a", "sql": TRADE_SQL, "keys": "0"})
    client.post(
        "/queries",
        data={"name": "share_b", "sql": "SELECT t.trade_id, t.product_type, t.trade_json FROM trade AS t", "keys": "0"},
    )
    try:
        page = client.get("/").text
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
    assert "no query named" in client.get("/queries/never_registered").text


def test_the_ask_page_runs_a_parameterised_query(client):
    response = client.post(
        "/query",
        data={"sql": "SELECT user_id, total FROM user_volume WHERE total > ?", "params": "40"},
    )

    assert response.status_code == 200
    assert "u1" in response.text


def test_a_bad_query_shows_the_error_rather_than_a_stack_trace(client):
    response = client.post("/query", data={"sql": "SELECT * FROM nowhere", "params": ""})

    assert "PRV-" in response.text
    assert "Traceback" not in response.text


# --- Help and documentation in the UI -------------------------------------------------------

def test_the_help_index_lists_the_guides(client):
    page = client.get("/help").text

    assert "Quickstart" in page
    assert "Concepts" in page
    assert "Troubleshooting" in page


def test_a_guide_renders_from_the_repositorys_own_documentation(client):
    page = client.get("/help/CONCEPTS.md").text

    # Rendered, not linked away to: an operator reading a console is already where the
    # question arose, and sending them elsewhere loses the thread.
    assert "soundness rule" in page
    assert "<table>" in page          # its tables survive
    assert "<pre>" in page            # its code blocks survive


def test_a_guide_links_to_other_guides_inside_the_console(client):
    page = client.get("/help/QUICKSTART.md").text

    # Cross-references stay in the console rather than pointing at files on disk.
    assert "/help/CONCEPTS.md" in page


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
        for path in ["/", "/queries/helpful", "/query"]:
            page = client.get(path).text
            assert "helpcards" in page, f"{path} has no help card"
            # Each card points at the document that says the rest.
            assert "read more" in page
    finally:
        client.post("/queries/helpful/drop")


def test_help_is_reachable_from_every_page(client):
    for path in ["/", "/query", "/help"]:
        assert "/help" in client.get(path).text


def test_the_help_index_offers_the_worked_systems(client):
    page = client.get("/help").text

    # A developer deciding how to shape a query wants an example far more often than a
    # specification, and the case studies are the most practical documentation there is.
    assert "worked systems" in page
    assert "Trade processing" in page


def test_a_case_study_renders_in_the_console(client):
    page = client.get("/help/study/trade-processing").text

    assert "trade_event_id" in page
    assert "<table>" in page


def test_an_unknown_case_study_is_refused(client):
    # Two layers refuse this and either is fine: the router normalises the path away before the
    # handler sees it, and the handler's allow-list would refuse the name anyway. What matters is
    # that nothing outside the five studies is ever read from disk.
    for attempt in ["../../etc", "nonexistent", "HANDOVER"]:
        response = client.get(f"/help/study/{attempt}")
        assert response.status_code == 404 or "no such case study" in response.text
