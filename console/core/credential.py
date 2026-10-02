"""Whose credential an engine call carries: the signed-in person's, and nobody else's.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

ADR-052: the engine is the identity authority, and the console holds no shared key. A person
signs in against the engine (``POST /api/v1/auth/login``); the session token it answers with is
kept by the console under the opaque id its session cookie carries (``routes.session_vault``), and
every call the console makes while serving that
person's request carries that token and no other. This module is how the token travels from the
request to the one adapter that talks to the engine without every service in between passing it
along: the console's middleware binds a :class:`Credential` to the request's context, and
``core.engine.Engine`` reads it on every call.

The credential also carries what the engine *said* about it. A call refused because the session
is over (``PRV-7016``, or any 401) marks the credential ``expired``; one refused because the
person must change their password first (``PRV-7018``) marks ``must_change``. The middleware reads
those marks once the route has answered and turns them into the one response that fits -- back to
the sign-in form, or to the password page -- so no route has to recognise either refusal itself.

A thread the console starts for a request (a live view's feed) does not inherit the context, so
it takes the credential with it explicitly (:func:`bound`).
"""
from __future__ import annotations

import contextlib
import contextvars
import dataclasses
import hashlib
import re
from collections.abc import Iterator

#: The engine's codes for a credential that no longer stands (ADR-052): the session has expired
#: or ended, or nothing was presented where something was needed.
EXPIRED_CODES = frozenset({"PRV-7016", "PRV-7001"})
#: The person must change their password before this session may do anything else.
MUST_CHANGE_CODE = "PRV-7018"

_ALL_CODES = re.compile(r"PRV-\d{4}")


@dataclasses.dataclass
class Credential:
    """One request's bearer token, and what the engine said about it."""

    token: str | None = None
    expired: bool = False
    must_change: bool = False

    def scope(self) -> str:
        """A short, one-way name for this credential, for keying what the console shares
        between requests (a live feed, a cached catalogue). Never the token itself: a key is
        logged and held in memory where a secret should not be."""
        if not self.token:
            return "-"
        return hashlib.sha256(self.token.encode("utf-8")).hexdigest()[:16]


_CURRENT: contextvars.ContextVar[Credential | None] = contextvars.ContextVar(
    "pravaha_console_credential", default=None)


def current() -> Credential | None:
    """The credential bound to this request, or None outside any request (a test calling a
    service directly, or startup)."""
    return _CURRENT.get()


def token() -> str | None:
    """The bearer token an engine call made now should carry, or None for an anonymous one."""
    credential = _CURRENT.get()
    return credential.token if credential is not None else None


def scope() -> str:
    credential = _CURRENT.get()
    return credential.scope() if credential is not None else "-"


def bind(credential: Credential) -> contextvars.Token:
    return _CURRENT.set(credential)


def unbind(handle: contextvars.Token) -> None:
    _CURRENT.reset(handle)


@contextlib.contextmanager
def bound(credential: Credential | None) -> Iterator[Credential | None]:
    """``credential`` bound for the duration of a block -- how a thread started on a request's
    behalf keeps acting as that request's person."""
    handle = _CURRENT.set(credential)
    try:
        yield credential
    finally:
        _CURRENT.reset(handle)


def code_of(exc: BaseException) -> str | None:
    """The engine's ``PRV-nnnn`` for a refusal, whichever layer's exception carried it."""
    for attribute in ("code", "engine_code"):
        value = getattr(exc, attribute, None)
        if isinstance(value, str) and value.upper().startswith("PRV-"):
            return value.upper()
        if isinstance(value, int) and value >= 1000:
            return f"PRV-{value}"
    text = str(exc)
    marker = text.find("PRV-")
    if marker >= 0:
        return text[marker:marker + 8]
    return None


def codes_of(exc: BaseException) -> set[str]:
    """Every ``PRV-nnnn`` a refusal names: its own code and each one its message carries. The SDK
    wraps a transport failure in its own code and keeps the engine's after it -- ``PRV-1041
    PRV-7001 this server requires a credential`` -- so the first code alone is not the answer."""
    named = set(_ALL_CODES.findall(str(exc)))
    first = code_of(exc)
    if first:
        named.add(first)
    return named


def note_refusal(exc: BaseException) -> None:
    """Marks the bound credential from an engine refusal: expired on a 401 or ``PRV-7016``,
    must-change on ``PRV-7018``. Anything else leaves it alone. Called by the engine adapter on
    every failure, so no service or route has to."""
    credential = _CURRENT.get()
    if credential is None or not credential.token:
        return
    code = code_of(exc)
    status = getattr(exc, "status", None)
    # Every code the refusal names, not only the first: the SDK wraps a transport failure in its
    # own code and keeps the engine's after it -- "PRV-1041 PRV-7001 this server requires a
    # credential" -- and reading only the first is how a signed-out session was drawn as a
    # signed-in page with an error in it (LOGOUTREPLAY-1).
    named = codes_of(exc)
    if MUST_CHANGE_CODE in named:
        credential.must_change = True
    elif named & EXPIRED_CODES or (code is None and status == 401) or _unauthenticated(exc):
        # A 401 with a code of its own is about something else -- PRV-7010 is a current
        # password refused on a change, and ends nothing.
        credential.expired = True


def _unauthenticated(exc: BaseException) -> bool:
    """A Flight refusal of the credential itself, which pyarrow raises as its own type."""
    try:
        from pyarrow import flight  # type: ignore[import-untyped]
    except ImportError:  # pragma: no cover -- the console needs the SDK's flight extra
        return False
    return isinstance(exc, flight.FlightUnauthenticatedError)
