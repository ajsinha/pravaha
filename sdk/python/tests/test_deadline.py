# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE.
"""SDKDEADLINE-1: a node that accepts a Flight call and never answers no longer holds the caller.

The server is a bare pyarrow Flight server that misbehaves on purpose: its handlers park until the
test ends, or open a stream and then trickle. Every unary call gives up at
``request_timeout_seconds`` with PRV-1045 naming the call and the deadline; a stream's opening is
bounded the same way; and a subscription that opened is not cut off by a deadline it outlives.
"""
from __future__ import annotations

import threading
import time
from typing import Any

import pyarrow as pa
import pytest
from pyarrow import flight

from pravaha import connect
from pravaha.client import DeadlineExceededError
from pravaha.endpoint import Endpoint
from pravaha.options import ClientOptions

DEADLINE = 0.5


class _Stalling(flight.FlightServerBase):
    def __init__(self) -> None:
        super().__init__("grpc://127.0.0.1:0")
        self.released = threading.Event()
        self.answer_flight_info = False
        self.trickle = False

    def get_flight_info(self, context: Any, descriptor: Any) -> Any:
        if not self.answer_flight_info:
            self.released.wait()
        schema = pa.schema([("n", pa.int64())])
        return flight.FlightInfo(schema, descriptor, [flight.FlightEndpoint(b"result", [])], -1, -1)

    def do_action(self, context: Any, action: Any) -> Any:
        self.released.wait()
        return []

    def do_get(self, context: Any, ticket: Any) -> Any:
        weight = pa.field("__weight", pa.int64(), metadata={b"pravaha.weight": b"true"})
        schema = pa.schema([pa.field("n", pa.int64()), weight])
        if not self.trickle:
            self.released.wait()

        def batches():
            # Five batches, 0.3 s apart: three deadlines' worth.
            for i in range(5):
                time.sleep(0.3)
                yield pa.record_batch([pa.array([i]), pa.array([1])], schema=schema)

        return flight.GeneratorStream(schema, batches())


@pytest.fixture
def server():
    s = _Stalling()
    yield s
    s.released.set()
    s.shutdown()


@pytest.fixture
def client(server):
    with connect(f"grpc://127.0.0.1:{server.port}", timeout=DEADLINE) as c:
        yield c


def _assert_deadline(failure: DeadlineExceededError, call: str) -> None:
    assert failure.code == 1045
    assert failure.retryable
    assert failure.call == call
    assert failure.deadline == DEADLINE
    assert call in str(failure) and "0.5 s" in str(failure) and "request_timeout_seconds" in str(failure)


def test_a_query_the_server_never_plans_fails_at_the_deadline(client):
    started = time.monotonic()
    with pytest.raises(DeadlineExceededError) as caught:
        client.query("SELECT n FROM anything")
    _assert_deadline(caught.value, "query (planning)")
    assert time.monotonic() - started < 10


def test_an_action_the_server_never_answers_fails_at_the_deadline(client):
    with pytest.raises(DeadlineExceededError) as caught:
        client.queries()
    assert caught.value.call.startswith("action ")


def test_a_query_whose_result_never_opens_fails_at_the_deadline(server, client):
    server.answer_flight_info = True
    started = time.monotonic()
    with pytest.raises(DeadlineExceededError) as caught:
        client.query("SELECT n FROM anything")
    _assert_deadline(caught.value, "query (opening its result)")
    assert time.monotonic() - started < 10


def test_a_subscription_that_never_opens_fails_at_the_deadline(client):
    with pytest.raises(DeadlineExceededError) as caught:
        next(iter(client.subscribe("v")))
    _assert_deadline(caught.value, "subscribe(v)")


def test_a_subscription_that_opened_runs_past_the_deadline(server, client):
    server.trickle = True
    started = time.monotonic()
    rows = sum(len(batch) for batch in client.subscribe("v"))
    assert rows == 5
    assert time.monotonic() - started > 2 * DEADLINE


def test_the_default_deadline_is_thirty_seconds_and_connect_sets_it():
    endpoint = Endpoint.parse("grpc://127.0.0.1:1")
    assert ClientOptions(endpoint=endpoint).request_timeout_seconds == 30.0
    with connect("grpc://127.0.0.1:1", timeout=2.5) as c:
        assert c._options.request_timeout_seconds == 2.5
    with pytest.raises(ValueError, match="not both"):
        connect(options=ClientOptions(endpoint=endpoint), timeout=1.0)
