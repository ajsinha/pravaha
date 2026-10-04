"""Authentication and authorization, from Python, against the real server.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The point of these tests is that they run against ``TestFlightServerMain`` with
``--authenticated``: the same server, the same policy, and the same refusals the
Java tests see. A Python fake would prove that the fake refuses.
"""

import os
import pathlib
import subprocess
import time

import pytest

pyarrow = pytest.importorskip("pyarrow", reason="the transport needs the 'flight' extra")

from pravaha import connect  # noqa: E402
from pravaha.client import QueryError  # noqa: E402
from pravaha.options import ClientOptions, InvalidOptionsError  # noqa: E402

REPO_ROOT = pathlib.Path(__file__).resolve().parents[3]
FLIGHT_CLASSES = REPO_ROOT / "pravaha-flight" / "target" / "test-classes"

ANALYST_TOKEN = "analyst-token-1"
INTERN_TOKEN = "intern-token-1"


def _classpath() -> str:
    entries = [str(FLIGHT_CLASSES), str(REPO_ROOT / "pravaha-flight" / "target" / "classes")]
    written = REPO_ROOT / "pravaha-flight" / "target" / "test-classpath.txt"
    if written.exists():
        entries.append(written.read_text().strip())
    return os.pathsep.join(entries)


@pytest.fixture(scope="module")
def secure_server():
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
            "--authenticated",
        ],
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )

    port = None
    read = []
    deadline = time.time() + 90
    while time.time() < deadline:
        line = process.stdout.readline()
        if line:
            read.append(line)
        if not line:
            break
        if line.startswith("PRAVAHA_FLIGHT_PORT="):
            port = int(line.strip().split("=", 1)[1])
            break
    if port is None:
        process.kill()
        # What the loop already read, plus whatever is left. Reading only the remainder gave
        # "Output: none" every time, because the loop had consumed the very lines that said why.
        tail = "".join(read)
        try:
            tail += process.stdout.read() or ""
        except Exception:  # noqa: BLE001, S110 -- the output is a nicety; the skip is the point
            pass
        tail = tail[-500:]
        # Says why. This skipped with "is the module built?" for months while the real cause was a
        # configuration refusal the server printed on the line below -- the fixture handed the
        # registry one SecurityPolicy and the server another, and PRV-7002 refused it. Five
        # authentication tests vanished every run and the suite reported success, because a skip is
        # not a failure. A message that names the wrong cause is worse than no message.
        pytest.skip(
            "the Pravaha server did not start using java at '"
            + java_bin
            + "' (set JAVA_HOME to a JDK 21 or later, or put java 21+ on PATH). Output: "
            + (tail or "none")
        )

    yield port
    process.kill()
    process.wait(timeout=30)


def _client(port, token=None):
    # allow_insecure_token because this is loopback plaintext. Over a network the SDK
    # refuses, and that refusal has its own test below.
    return connect(
        options=ClientOptions.create(
            f"grpc://localhost:{port}", token=token, allow_insecure_token=True
        )
    )


def test_a_client_with_no_credential_is_refused(secure_server):
    with _client(secure_server) as client:
        with pytest.raises(QueryError) as refused:
            list(client.query("SELECT user_id FROM user_volume"))

    assert "PRV-7001" in str(refused.value)


def test_an_unknown_credential_is_refused(secure_server):
    with _client(secure_server, token="guessed") as client:
        with pytest.raises(QueryError) as refused:
            list(client.query("SELECT user_id FROM user_volume"))

    assert "PRV-7001" in str(refused.value)


def test_an_authenticated_but_unauthorized_caller_is_told_apart(secure_server):
    with _client(secure_server, token=INTERN_TOKEN) as client:
        with pytest.raises(QueryError) as refused:
            list(client.query("SELECT user_id FROM user_volume"))

    # PRV-7002, not PRV-7001: retrying with a fresh credential will not help. The
    # difference is "log in again" versus "ask for access".
    assert "PRV-7002" in str(refused.value)


def test_an_authorized_caller_sees_only_its_own_rows(secure_server):
    with _client(secure_server, token=ANALYST_TOKEN) as client:
        rows = [row["user_id"] for row in client.query("SELECT user_id, tier FROM user_volume")]

    # The client asked for no filter. u2 is silver and never crosses the wire; u3 has no
    # tier at all, and a NULL is not 'gold' under three-valued logic, so it does not either.
    assert sorted(rows) == ["u1"]


def test_the_filter_holds_under_an_aggregate(secure_server):
    with _client(secure_server, token=ANALYST_TOKEN) as client:
        rows = list(client.query("SELECT SUM(total) AS total FROM user_volume"))

    # 300, not 357: the filter sits below the aggregate, so rows this principal may not
    # see never reach the sum. Filtering the answer afterwards could not have done this.
    assert rows[0]["total"] == 300


def test_a_token_is_refused_over_a_plaintext_connection_by_default():
    with pytest.raises(InvalidOptionsError) as refused:
        ClientOptions.create("grpc://example.com:19090", token="s3cret")

    assert "plaintext" in str(refused.value)


def test_the_token_never_appears_in_a_repr():
    options = ClientOptions.create(
        "grpc://localhost:19090", token="s3cret", allow_insecure_token=True
    )

    # Options reach log lines. A credential in one reaches the log with it.
    assert "s3cret" not in str(options)
    assert "s3cret" not in repr(options)
