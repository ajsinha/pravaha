"""W3C trace context on the engine calls the client makes (pravaha.tracecontext).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

A node with tracing on continues a caller's trace from ``traceparent``. The client sends one only
when there is a trace -- one set with :func:`pravaha.tracecontext.use`, or OpenTelemetry's current
context when that package is installed -- and never a malformed one.
"""
from __future__ import annotations

import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import ClassVar

import pytest

from pravaha import tracecontext
from pravaha.rest import RestClient

PARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


class _Headers(BaseHTTPRequestHandler):
    seen: ClassVar[list] = []

    def log_message(self, *_):
        pass

    def do_GET(self):
        _Headers.seen.append({"traceparent": self.headers.get("traceparent"),
                              "tracestate": self.headers.get("tracestate")})
        data = b"[]"
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


@pytest.fixture
def engine():
    _Headers.seen = []
    server = HTTPServer(("127.0.0.1", 0), _Headers)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


def _otel_installed() -> bool:
    try:
        import opentelemetry  # noqa: F401
    except ImportError:
        return False
    return True


def test_nothing_is_sent_without_a_trace(engine):
    RestClient(engine).get("/api/v1/streams")
    if not _otel_installed():
        assert tracecontext.headers() == {}
    assert _Headers.seen[0]["traceparent"] is None or tracecontext.valid(_Headers.seen[0]["traceparent"])


def test_a_trace_set_for_a_block_reaches_every_rest_call_in_it(engine):
    with tracecontext.use(PARENT, "vendor=1"):
        RestClient(engine).get("/api/v1/streams")
        assert tracecontext.headers() == {"traceparent": PARENT, "tracestate": "vendor=1"}
    assert _Headers.seen[0] == {"traceparent": PARENT, "tracestate": "vendor=1"}


def test_a_malformed_traceparent_is_not_forwarded(engine):
    for bad in ("00-zz-00f067aa0ba902b7-01", "00-" + "0" * 32 + "-00f067aa0ba902b7-01", "x\r\ninjected: 1"):
        assert not tracecontext.valid(bad)
        with tracecontext.use(bad):
            assert "traceparent" not in tracecontext.headers() or _otel_installed()


def test_an_inner_block_can_clear_an_outer_one():
    with tracecontext.use(PARENT):
        with tracecontext.use(None):
            assert tracecontext.headers().get("traceparent") != PARENT
        assert tracecontext.headers()["traceparent"] == PARENT


def test_flight_calls_carry_the_trace_beside_the_token():
    flight = pytest.importorskip("pyarrow.flight")
    from pravaha import ClientOptions
    from pravaha.client import Client

    received: list[dict] = []

    class Recording(flight.ServerMiddlewareFactory):
        def start_call(self, info, headers):
            received.append({k: v for k, v in headers.items()})
            return None

    class Server(flight.FlightServerBase):
        def do_action(self, context, action):
            return iter([])

    server = Server("grpc://127.0.0.1:0", middleware={"recording": Recording()})
    try:
        client = Client(ClientOptions.create(f"grpc://127.0.0.1:{server.port}", token="t0ken",
                                             allow_insecure_token=True))
        with tracecontext.use(PARENT):
            list(client._client.do_action(flight.Action("ping", b""), client._call_options))
        list(client._client.do_action(flight.Action("ping", b""), client._call_options))
    finally:
        server.shutdown()
    assert received[0]["traceparent"] == [PARENT]
    assert received[0]["authorization"] == ["Bearer t0ken"]
    assert "traceparent" not in received[1] or _otel_installed()
