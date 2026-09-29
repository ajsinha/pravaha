"""W3C trace context for the engine calls this client makes.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

A node with ``pravaha.tracing.enabled`` continues a trace a caller sends in ``traceparent``: its REST
request span and its Flight call span become children of the caller's span, so one trace runs from
an application through the engine. This module decides what, if anything, this client sends.

Two sources, in order:

1. :func:`use` -- a ``traceparent`` (and ``tracestate``) set for the calls made inside a ``with``
   block. For a process that has no tracer of its own but received a trace from upstream -- the
   console forwarding one a reverse proxy sent it, say.
2. OpenTelemetry's current context, **when the ``opentelemetry`` package is installed**. It is an
   optional import: the SDK has no required dependency and gains none here. With no tracer
   configured OpenTelemetry's context is empty and nothing is sent.

With neither, no header is added -- exactly what the client sent before.
"""
from __future__ import annotations

import contextlib
import contextvars
import re
from collections.abc import Iterator
from typing import Optional

#: ``version-traceid-parentid-flags``, lower-case hex, the all-zero ids invalid (W3C Trace Context §3.2).
_TRACEPARENT = re.compile(r"^[0-9a-f]{2}-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$")

_given: contextvars.ContextVar[Optional[tuple[str, Optional[str]]]] = contextvars.ContextVar(
    "pravaha_traceparent", default=None
)


def valid(traceparent: Optional[str]) -> bool:
    """Whether ``traceparent`` is a well-formed W3C ``traceparent`` value."""
    return bool(traceparent) and _TRACEPARENT.match(traceparent or "") is not None


@contextlib.contextmanager
def use(traceparent: Optional[str], tracestate: Optional[str] = None) -> Iterator[None]:
    """Sends ``traceparent`` (and ``tracestate``) on every engine call made inside the block.

    A value that is not a well-formed ``traceparent`` is ignored rather than forwarded: a malformed
    one would make the engine start a new trace anyway, and a header copied from a request is text a
    stranger chose. ``None`` clears whatever an outer block set.
    """
    value = (traceparent, tracestate) if traceparent is not None and valid(traceparent) else None
    token = _given.set(value)
    try:
        yield
    finally:
        _given.reset(token)


def headers() -> dict[str, str]:
    """The trace headers to send on the next engine call; empty when there is no trace."""
    given = _given.get()
    if given is not None:
        parent, state = given
        found = {"traceparent": parent}
        if state:
            found["tracestate"] = state
        return found
    try:
        from opentelemetry import propagate  # type: ignore[import-not-found,unused-ignore]  # optional
    except ImportError:
        return {}
    carrier: dict[str, str] = {}
    try:
        propagate.inject(carrier)
    except Exception:  # a misconfigured propagator must not fail the engine call
        return {}
    found = {}
    if valid(carrier.get("traceparent")):
        found["traceparent"] = carrier["traceparent"]
        if carrier.get("tracestate"):
            found["tracestate"] = carrier["tracestate"]
    return found


__all__ = ["headers", "use", "valid"]
