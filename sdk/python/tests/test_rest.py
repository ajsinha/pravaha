"""The SDK's calls to the engine's published HTTP API.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

What each endpoint *decides* -- who may see what, which counts are withheld, that no sink
option is ever serialised -- is pinned in ``pravaha-server``'s own tests, against the real
controllers. What is pinned here is the client's half: the right method, path and body, the
token on every call and never over plaintext by accident, a name that cannot address a
different endpoint, and a refusal that arrives as ``ApiError`` carrying the engine's own code.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, HTTPServer
from typing import ClassVar

import pytest

from pravaha import ApiError, ClientOptions, InvalidOptionsError
from pravaha.rest import RestClient


class _Recorder(BaseHTTPRequestHandler):
    calls: ClassVar[list] = []
    answers: ClassVar[dict] = {}

    def log_message(self, *_):
        pass

    def _answer(self, method: str) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode("utf-8") if length else None
        _Recorder.calls.append(
            {
                "method": method,
                "path": self.path,
                "authorization": self.headers.get("Authorization"),
                "body": json.loads(body) if body else None,
            }
        )
        status, payload = _Recorder.answers.get((method, self.path.split("?")[0]), (200, []))
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self._answer("GET")

    def do_POST(self):
        self._answer("POST")


@pytest.fixture
def engine():
    _Recorder.calls = []
    _Recorder.answers = {}
    server = HTTPServer(("127.0.0.1", 0), _Recorder)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_address[1]}"
    finally:
        server.shutdown()
        server.server_close()


def _client(url: str, token: str | None = "t0ken"):
    pytest.importorskip("pyarrow.flight")
    from pravaha.client import Client

    # The Flight half is never contacted by these calls; a client is still a client.
    return Client(
        ClientOptions.create(
            "grpc://127.0.0.1:1", token=token, allow_insecure_token=True, http_url=url
        )
    )


def test_each_call_reaches_its_published_endpoint_with_the_token(engine):
    _Recorder.answers[("GET", "/api/v1/sinks")] = (200, [{"name": "orders_out", "plugin": "filesystem"}])
    client = _client(engine)

    client.streams()
    client.validate("SELECT 1")
    client.explain("SELECT 1", graph=True)
    client.describe_queries()
    client.describe_query("orders")
    client.query_plan("orders")
    client.replacement_http("orders")
    client.describe_view("orders")
    assert client.sinks() == [{"name": "orders_out", "plugin": "filesystem"}]
    client.status()
    client.plugins()
    client.permissions()
    client.tenants()
    client.declare_stream("clicks", "at:TIMESTAMP", event_time="at", out_of_orderness="PT5S")

    seen = [(c["method"], c["path"]) for c in _Recorder.calls]
    assert seen == [
        ("GET", "/api/v1/streams"),
        ("POST", "/api/v1/queries/validate"),
        ("POST", "/api/v1/queries/explain?level=physical&format=graph"),
        ("GET", "/api/v1/queries"),
        ("GET", "/api/v1/queries/orders"),
        ("GET", "/api/v1/queries/orders/plan"),
        ("GET", "/api/v1/queries/orders/replacement"),
        ("GET", "/api/v1/views/orders"),
        ("GET", "/api/v1/sinks"),
        ("GET", "/api/v1/status"),
        ("GET", "/api/v1/plugins"),
        ("GET", "/api/v1/me/permissions"),
        ("GET", "/api/v1/tenants"),
        ("POST", "/api/v1/streams"),
    ]
    assert all(c["authorization"] == "Bearer t0ken" for c in _Recorder.calls)
    assert _Recorder.calls[1]["body"] == {"sql": "SELECT 1"}
    assert _Recorder.calls[-1]["body"] == {
        "name": "clicks",
        "schema": "at:TIMESTAMP",
        "eventTime": "at",
        "outOfOrderness": "PT5S",
    }


def test_the_replacement_over_http_carries_the_history_the_flight_row_cannot(engine):
    """The reason this call exists beside :meth:`Client.replacement`: the control wire lays a
    status out as a flat list of strings, and the list of versions that have served a name
    does not fit in one. Over HTTP a list is a list."""
    _Recorder.answers[("GET", "/api/v1/queries/orders/replacement")] = (
        200,
        {
            "name": "orders",
            "state": "CUT_OVER",
            "history": ["from the beginning: abc123def456", "from 4471: 0f9e8d7c6b5a"],
            "historyEntries": [{"fromFrontier": None, "version": "abc123def456"},
                               {"fromFrontier": 4471, "version": "0f9e8d7c6b5a"}],
            "backfill": {"historyRows": 412000, "historyComplete": True},
        },
    )
    client = _client(engine)

    status = client.replacement_http("orders")

    assert _Recorder.calls[0]["path"] == "/api/v1/queries/orders/replacement"
    assert status["history"] == ["from the beginning: abc123def456", "from 4471: 0f9e8d7c6b5a"]
    assert status["historyEntries"][1] == {"fromFrontier": 4471, "version": "0f9e8d7c6b5a"}
    assert status["state"] == "CUT_OVER"


def test_a_name_that_is_not_being_replaced_is_refused_rather_than_answered_with_nothing(engine):
    """PRV-4017, not an empty body: "there is no replacement" and "there is no such query"
    are different answers, and only the second is safe to give a caller who may not read the
    name. The SDK passes the engine's refusal through with its code."""
    _Recorder.answers[("GET", "/api/v1/queries/orders/replacement")] = (
        404,
        {"code": "PRV-4017", "message": "'orders' is not being replaced"},
    )
    client = _client(engine)

    with pytest.raises(ApiError) as refused:
        client.replacement_http("orders")

    assert refused.value.status == 404
    assert refused.value.engine_code == "PRV-4017"


def test_audit_sends_only_the_filters_given_and_passes_the_cursor_back(engine):
    _Recorder.answers[("GET", "/api/v1/audit")] = (
        200,
        {"events": [{"sequence": 7, "principal": "ann"}], "nextCursor": "7"},
    )
    client = _client(engine)

    first = client.audit()
    client.audit(principal="ann", decision="deny", since="2026-09-19T08:00:00Z", limit=50,
                 cursor=first["nextCursor"])

    assert [c["path"] for c in _Recorder.calls] == [
        "/api/v1/audit",
        "/api/v1/audit?since=2026-09-19T08%3A00%3A00Z&principal=ann&decision=deny&limit=50&cursor=7",
    ]
    assert first["events"][0]["principal"] == "ann"


def test_a_principal_not_allowed_the_audit_trail_gets_a_403_api_error(engine):
    _Recorder.answers[("GET", "/api/v1/audit")] = (
        403,
        {"code": "PRV-7002", "message": "ann may not read the audit trail"},
    )
    client = _client(engine)

    with pytest.raises(ApiError) as refused:
        client.audit()

    assert refused.value.status == 403
    assert refused.value.engine_code == "PRV-7002"


def test_a_name_is_one_path_segment_and_cannot_reach_another_endpoint(engine):
    client = _client(engine)

    client.describe_query("../status")

    assert _Recorder.calls[0]["path"] == "/api/v1/queries/..%2Fstatus"


def test_a_refusal_arrives_as_api_error_with_the_engines_code_and_status(engine):
    _Recorder.answers[("GET", "/api/v1/queries/payroll")] = (
        403,
        {"code": "PRV-7002", "message": "carol may not read 'payroll'."},
    )
    client = _client(engine)

    with pytest.raises(ApiError) as refused:
        client.describe_query("payroll")

    assert refused.value.status == 403
    assert refused.value.engine_code == "PRV-7002"
    assert refused.value.code == 7002
    assert "may not read" in str(refused.value)


def test_an_engine_that_does_not_answer_is_status_zero_rather_than_a_hang():
    client = RestClient("http://127.0.0.1:1", timeout_seconds=2)

    with pytest.raises(ApiError) as refused:
        client.get("/api/v1/status")

    assert refused.value.status == 0


def test_a_client_without_an_http_url_says_which_setting_is_missing():
    pytest.importorskip("pyarrow.flight")
    from pravaha.client import Client

    client = Client(ClientOptions.create("grpc://127.0.0.1:1"))

    with pytest.raises(ApiError) as refused:
        client.streams()

    assert "http_url" in str(refused.value)


def test_a_token_is_not_sent_over_plaintext_http_unless_asked_for():
    with pytest.raises(InvalidOptionsError):
        ClientOptions.create("grpc+tls://engine:19090", token="t", http_url="http://engine:18080")
    with pytest.raises(InvalidOptionsError):
        RestClient("http://engine:18080", token="t")
    with pytest.raises(InvalidOptionsError):
        ClientOptions.create("grpc+tls://engine:19090", http_url="engine:18080")

    # Asked for by name, for a loopback engine.
    RestClient("http://127.0.0.1:18080", token="t", allow_insecure_token=True)
