"""The console's readiness, in front of an engine that requires a credential.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

CONSOLEREADY-1. ``/health/ready`` was always 503 in front of an engine that requires a credential:
the console holds none of its own (ADR-052), its Flight probe was refused, and the refusal --
wrapped by the SDK as ``PRV-1041 PRV-7001 ...`` -- read as "unreachable". Readiness now asks the
engine's health endpoint, which answers anonymous callers, when the engine's HTTP surface is
configured; and a wrapped refusal of the credential reads as the answer it is.
"""
from __future__ import annotations

import http.server
import json
import pathlib
import socket
import sys
import threading

import pytest

fastapi_testclient = pytest.importorskip("fastapi.testclient")

CONSOLE_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CONSOLE_ROOT))

from core.config.properties_configurator import PropertiesConfigurator
from core.engine import Engine, _refused_credential
from run_pravaha_web import create_app


class _Actuator(http.server.BaseHTTPRequestHandler):
    """An engine's HTTP surface as an anonymous caller sees it: health answers, nothing else does."""

    status = "UP"

    def do_GET(self):  # noqa: N802 -- the stdlib's name
        if self.path == "/actuator/health":
            body = json.dumps({"status": type(self).status}).encode()
            self.send_response(503 if type(self).status in ("DOWN", "OUT_OF_SERVICE") else 200)
        else:
            body = json.dumps({"code": "PRV-7001", "message": "this server requires a credential"}).encode()
            self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


@pytest.fixture
def actuator():
    handler = type("Handler", (_Actuator,), {"status": "UP"})
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield handler, f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


def _closed_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def _console(http_url: str):
    config = PropertiesConfigurator(str(CONSOLE_ROOT / "config" / "application.yaml"))
    config.set("engine.url", f"grpc://127.0.0.1:{_closed_port()}")
    config.set("engine.http_url", http_url)
    config.set("console.session_secret", "readiness-test-secret")
    engine = Engine(config.get("engine.url"), http_url=http_url or None)
    return fastapi_testclient.TestClient(create_app(config, engine=engine))


def test_ready_in_front_of_an_engine_that_requires_a_credential(actuator):
    _, url = actuator
    answer = _console(url).get("/health/ready")
    assert answer.status_code == 200, answer.text
    body = answer.json()
    assert body["status"] == "ready"
    assert body["engine"]["reachable"] is True and body["engine"]["status"] == "UP"


@pytest.mark.parametrize("status, code", [("DEGRADED", 200), ("DOWN", 503), ("OUT_OF_SERVICE", 503)])
def test_the_engines_own_verdict_decides(actuator, status, code):
    handler, url = actuator
    handler.status = status
    answer = _console(url).get("/health/ready")
    assert answer.status_code == code
    assert answer.json()["engine"]["reachable"] is True


def test_not_ready_when_nothing_answers():
    answer = _console(f"http://127.0.0.1:{_closed_port()}").get("/health/ready")
    assert answer.status_code == 503
    assert answer.json()["engine"]["reachable"] is False


def test_a_wrapped_refusal_of_the_credential_is_an_answer():
    """The Flight probe's own case, when no HTTP surface is configured."""
    assert _refused_credential(RuntimeError("PRV-1041 PRV-7001 this server requires a credential"))
    assert not _refused_credential(RuntimeError("PRV-1040 the engine did not answer"))
