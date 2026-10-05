"""``pravaha doctor``: every check's green, yellow and red, the exit code, ``--json``, and no secret.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

The node is ``test_cli``'s recording HTTP server; Flight, where a test needs it answered, is a
``pyarrow.flight`` server of a few lines here; TLS is a real handshake against a certificate made
for the test. Nothing reads or writes the person's own configuration: ``home`` points
``PRAVAHA_CONFIG_DIR`` at the test's temporary directory.
"""

from __future__ import annotations

import datetime
import json
import os
import ssl
import threading
from http.server import HTTPServer

import pytest

import pravaha
from pravaha.cli import _doctor
from test_cli import _Engine, answer, engine, home, run  # noqa: F401

DEAD_URL = "grpc://127.0.0.1:1"  # nothing listens on port 1


@pytest.fixture
def jdk(tmp_path, monkeypatch):
    """A JAVA_HOME whose release file says the version asked for."""

    def make(version: str) -> str:
        path = tmp_path / f"jdk-{version}"
        path.mkdir()
        (path / "release").write_text(f'JAVA_VERSION="{version}"\n', encoding="utf-8")
        monkeypatch.setenv("JAVA_HOME", str(path))
        return str(path)

    return make


def _by_name(stdout: str) -> "dict[str, dict]":
    return {c["name"]: c for c in json.loads(stdout)}


def _healthy(version: str = "") -> None:
    answer("GET", "/actuator/health", {"status": "UP"})
    answer("GET", "/api/v1/status", {"version": version or pravaha.__version__})
    answer("GET", "/api/v1/auth/me", {"username": "ann", "principal": "ann", "tenant": "acme",
                                      "roles": ["operator", "analyst"], "via": "token"})


def test_a_node_that_does_not_answer_still_gets_a_whole_report_and_exits_one(home, jdk):
    jdk("21.0.5")
    code, out, err = run("doctor", "--http", "http://127.0.0.1:1", "--url", DEAD_URL,
                         "--timeout", "2")
    assert code == 1, err
    lines = out.splitlines()
    assert lines[-1].startswith("doctor: ") and " red, " in lines[-1]
    assert any(line.startswith("RED") and " http " in line for line in lines)
    assert any(line.startswith("SKIPPED") and " auth " in line for line in lines)
    # Every non-green line is followed by its fix.
    assert "fix: start the node, or pass --http" in out
    for name in ("python", "cli", "java", "token file", "port 18080"):
        assert f" {name} " in out, name


def test_json_is_a_list_of_checks_with_name_status_detail_and_fix(engine, home, jdk):
    jdk("21")
    _healthy()
    code, out, _ = run("doctor", "--http", engine, "--url", DEAD_URL, "--json", "--timeout", "2")
    checks = json.loads(out)
    assert isinstance(checks, list) and checks
    for item in checks:
        assert set(item) == {"name", "status", "detail", "fix"}
        assert item["status"] in ("GREEN", "YELLOW", "RED", "SKIPPED")
    assert code == (1 if any(c["status"] == "RED" for c in checks) else 0)


def test_all_green_exits_zero(engine, home, jdk):
    flight = pytest.importorskip("pyarrow.flight")
    jdk("21.0.5")
    _healthy()
    server = _flight_server(flight)
    try:
        code, out, err = run("doctor", "--http", engine, "--url",
                             f"grpc://127.0.0.1:{server.port}", "--timeout", "5")
    finally:
        server.shutdown()
    assert code == 0, out + err
    assert out.splitlines()[-1] == "doctor: 0 red, 1 yellow"  # no token: anonymous is a yellow
    assert "GREEN    flight" in out and "0 continuous queries" in out
    assert "GREEN    http" in out and "health UP" in out


def _flight_server(flight):
    from pravaha.client import _wire_encode  # noqa: F401 -- the list answers no rows

    class Node(flight.FlightServerBase):
        def do_action(self, context, action):
            return []

    server = Node("grpc://127.0.0.1:0")
    threading.Thread(target=server.serve, daemon=True).start()
    return server


@pytest.mark.parametrize("state, status, code", [("UP", "GREEN", None), ("DEGRADED", "YELLOW", None),
                                                 ("DOWN", "RED", 1)])
def test_health_maps_to_green_yellow_red(engine, home, jdk, state, status, code):
    jdk("21")
    _healthy()
    answer("GET", "/actuator/health", {"status": state}, 503 if state == "DOWN" else 200)
    exit_code, out, _ = run("doctor", "--http", engine, "--url", DEAD_URL, "--json")
    assert _by_name(out)["http"]["status"] == status
    if code:
        assert exit_code == code


@pytest.mark.parametrize("node, status", [("same", "GREEN"), ("2.4.0", "YELLOW"), ("3.0.0", "RED"),
                                          ("", "YELLOW")])
def test_the_nodes_version_against_the_clis(engine, home, jdk, monkeypatch, node, status):
    jdk("21")
    monkeypatch.setattr(pravaha, "__version__", "2.3.1")
    _healthy("2.3.1" if node == "same" else node)
    if node == "":
        answer("GET", "/api/v1/status", {}, 401)
    _, out, _ = run("doctor", "--http", engine, "--url", DEAD_URL, "--json")
    assert _by_name(out)["version"]["status"] == status


@pytest.mark.parametrize("version, status", [("17.0.2", "RED"), ("21.0.5", "GREEN"), ("25", "GREEN")])
def test_java_21_or_later(home, jdk, version, status):
    jdk(version)
    assert _doctor.java_check(os.environ, 2.0)["status"] == status


def test_no_java_at_all_is_only_a_yellow(tmp_path):
    found = _doctor.java_check({"PATH": str(tmp_path)}, 2.0, which=lambda _: None)
    assert found["status"] == "YELLOW" and "pravaha-engine" in found["detail"]


def test_python_older_than_the_floor_is_red():
    assert _doctor.python_check((3, 8, 10))["status"] == "RED"
    assert _doctor.python_check((3, 12, 1))["status"] == "GREEN"


def test_a_token_file_others_can_read_is_red_and_is_never_changed(engine, home, jdk):
    jdk("21")
    _healthy()
    home.mkdir(parents=True)
    token = home / "token"
    token.write_text("s3cret-token-value\n", encoding="utf-8")
    os.chmod(token, 0o644)
    code, out, err = run("doctor", "--http", engine, "--url", DEAD_URL, "--insecure-token")
    assert code == 1
    assert f"fix: chmod 600 {token}" in out
    assert oct(token.stat().st_mode & 0o777) == "0o644"  # read, never set
    assert "s3cret-token-value" not in out + err
    os.chmod(token, 0o600)
    _, out, _ = run("doctor", "--http", engine, "--url", DEAD_URL, "--insecure-token", "--json")
    assert _by_name(out)["token file"]["status"] == "GREEN"


def test_no_secret_is_printed_even_when_an_error_quotes_it(engine, home, jdk):
    jdk("21")
    _healthy()
    secret = "tok-ABCDEF-0123456789"
    answer("GET", "/api/v1/auth/me", {"message": f"token {secret} is not known"}, 401)
    for extra in ((), ("--json",)):
        code, out, err = run("doctor", "--http", engine, "--url", DEAD_URL, "--token", secret,
                             "--insecure-token", *extra)
        assert secret not in out + err
        assert code == 1
    assert _by_name(out)["auth"]["status"] == "RED"


def test_whoami_lists_roles_and_a_session_expiring_within_a_day_is_yellow(engine, home, jdk):
    jdk("21")
    _healthy()
    soon = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=3)
    answer("GET", "/api/v1/auth/me", {"username": "ann", "tenant": "acme",
                                      "roles": ["operator", "analyst"], "via": "session"})
    answer("GET", "/api/v1/sessions", {"sessions": [
        {"id": "s1", "current": False, "expiresAt": "2020-01-01T00:00:00Z"},
        {"id": "s2", "current": True,
         "expiresAt": soon.strftime("%Y-%m-%dT%H:%M:%S.123456789Z")}]})
    _, out, _ = run("doctor", "--http", engine, "--url", DEAD_URL, "--token", "t",
                    "--insecure-token", "--json")
    checks = _by_name(out)
    assert checks["auth"]["status"] == "GREEN"
    assert "roles analyst,operator" in checks["auth"]["detail"]
    assert checks["token expiry"]["status"] == "YELLOW"


def test_ports_are_reported_for_a_local_node_only_or_with_local(home, jdk):
    jdk("21")
    _, out, _ = run("doctor", "--http", "http://node-1.invalid:18080", "--url", DEAD_URL,
                    "--timeout", "2", "--json")
    assert not any(name.startswith("port ") for name in _by_name(out))
    _, out, _ = run("doctor", "--http", "http://node-1.invalid:18080", "--url", DEAD_URL,
                    "--timeout", "2", "--json", "--local")
    assert {"port 18080", "port 19090", "port 17070"} <= set(_by_name(out))


# ---------------------------------------------------------------------------------- TLS


def _certificate(tmp_path, days: float, name: str = "localhost"):
    crypto = pytest.importorskip("cryptography")  # noqa: F841
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID
    import ipaddress

    key = ec.generate_private_key(ec.SECP256R1())
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, name)])
    now = datetime.datetime.now(datetime.timezone.utc)
    cert = (
        x509.CertificateBuilder().subject_name(subject).issuer_name(subject)
        .public_key(key.public_key()).serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(days=400))
        .not_valid_after(now + datetime.timedelta(days=days))
        .add_extension(x509.SubjectAlternativeName(
            [x509.DNSName(name), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), False)
        .add_extension(x509.BasicConstraints(ca=True, path_length=None), True)
        .sign(key, hashes.SHA256())
    )
    pem = tmp_path / f"cert-{days}.pem"
    pem.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    key_pem = tmp_path / f"key-{days}.pem"
    key_pem.write_bytes(key.private_bytes(serialization.Encoding.PEM,
                                          serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
    return pem, key_pem


@pytest.fixture
def https(tmp_path):
    servers = []

    def start(days: float):
        pem, key = _certificate(tmp_path, days)
        server = HTTPServer(("127.0.0.1", 0), _Engine)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(str(pem), str(key))
        server.socket = context.wrap_socket(server.socket, server_side=True)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        servers.append(server)
        return f"https://127.0.0.1:{server.server_address[1]}", pem

    yield start
    for server in servers:
        server.shutdown()
        server.server_close()


@pytest.mark.parametrize("days, status, words", [(400, "GREEN", "expires"),
                                                 (10, "YELLOW", "in 10 days"),
                                                 (-1, "RED", "expired")])
def test_tls_trust_and_expiry(home, jdk, https, days, status, words):
    jdk("21")
    _Engine.answers = {}
    url, ca = https(days)
    _, out, _ = run("doctor", "--http", url, "--url", DEAD_URL, "--tls-ca", str(ca), "--json",
                    "--timeout", "3")
    found = _by_name(out)["tls http"]
    assert found["status"] == status, found
    assert words in found["detail"]


def test_tls_an_untrusted_certificate_and_a_wrong_name_are_red(home, jdk, https):
    jdk("21")
    url, ca = https(400)
    _, out, _ = run("doctor", "--http", url, "--url", DEAD_URL, "--json", "--timeout", "3")
    assert _by_name(out)["tls http"]["status"] == "RED"
    assert "--tls-ca" in _by_name(out)["tls http"]["fix"]
    _, out, _ = run("doctor", "--http", url, "--url", DEAD_URL, "--tls-ca", str(ca),
                    "--tls-override-hostname", "elsewhere.example", "--json", "--timeout", "3")
    found = _by_name(out)["tls http"]
    assert found["status"] == "RED" and "elsewhere.example" in found["detail"]
