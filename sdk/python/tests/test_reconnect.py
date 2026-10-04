# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE.
"""``subscribe(..., reconnect=True)``: reopening a stream a restart ended, without a server."""
from __future__ import annotations

import pytest

import pravaha.client as client_module
from pravaha.client import ChangeBatch, Client, ConnectError, QueryError


class _Scripted(Client):
    """A client whose streams are a script: each entry is one attempt to open the stream."""

    def __init__(self, attempts):  # noqa: D107 -- no connection is made
        self._attempts = list(attempts)
        self.opened = 0

    def _open_subscription(self, ticket, call="subscribe"):
        self.opened += 1
        step = self._attempts.pop(0)
        if isinstance(step, Exception):
            raise step
        return step

    def _batches(self, reader):
        for item in reader:
            if isinstance(item, Exception):
                raise item
            yield ChangeBatch([], snapshot=item == "snap")


@pytest.fixture(autouse=True)
def no_waiting(monkeypatch):
    waits = []
    monkeypatch.setattr(client_module, "_sleep", waits.append)
    return waits


def _run(client, count, **options):
    stream = client._reconnecting(None, options.get("timeout", 300.0))
    return [next(stream) for _ in range(count)]


def test_a_stream_a_restart_broke_is_reopened_and_the_first_batch_after_says_so(no_waiting):
    down = ConnectError("PRV-1040 nothing is listening")
    client = _Scripted([["snap", "c1", QueryError("Socket closed")], down, down, ["snap", "c2"]])

    batches = _run(client, 4)

    assert [b.snapshot for b in batches] == [True, False, True, False]
    assert [b.reconnected for b in batches] == [False, False, True, False]
    assert client.opened == 4
    # Backoff doubles while the node is down.
    assert no_waiting == [0.25, 0.5, 1.0]


def test_a_stream_the_server_closed_cleanly_is_reopened(no_waiting):
    client = _Scripted([["c1"], ["snap"]])

    batches = _run(client, 2)

    assert batches[1].reconnected and batches[1].snapshot


def test_falling_behind_is_answered_by_subscribing_again():
    client = _Scripted([["c1", QueryError("PRV-6105 this subscriber fell behind")], ["snap"]])

    assert _run(client, 2)[1].reconnected


def test_a_refusal_that_will_not_change_is_raised_at_once():
    client = _Scripted([["c1"], QueryError("PRV-4023 no view named gone")])

    stream = client._reconnecting(None, 300.0)
    next(stream)
    with pytest.raises(QueryError, match="PRV-4023"):
        next(stream)


def test_a_node_that_stays_down_past_the_timeout_is_reported(monkeypatch):
    clock = iter([0.0, 5.0, 11.0])
    monkeypatch.setattr(client_module.time, "monotonic", lambda: next(clock))
    down = ConnectError("PRV-1040 nothing is listening")
    client = _Scripted([down, down, down])

    with pytest.raises(ConnectError):
        next(client._reconnecting(None, 10.0))
    assert client.opened == 3
