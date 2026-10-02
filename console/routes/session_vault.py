"""
Pravaha console — the secrets a session holds, kept on the server, never in the cookie.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

COOKIETOKEN-1. The session cookie is signed, not encrypted: its payload is base64 JSON anyone
holding the cookie can read. It used to carry the engine session token (``prv_s_…``) -- a bearer
credential that works directly against Flight, HTTP and the PostgreSQL gateway -- and, for one
round trip, an API key or reset token the engine had just issued. A leaked cookie was therefore a
credential for every door, not only the console's.

Now the cookie carries an opaque session id (``sid``), random and meaningless outside this
process, and :class:`SessionSecrets` keeps what it stands for: the token and any just-issued
secret. :class:`TokenVault` sits between the cookie middleware and everything else and swaps them
in and out, so every route still reads ``request.session["token"]`` exactly as before:

- on the way in, a ``sid`` the vault knows puts its secrets back into the session; one it does not
  know empties the session, and one it knows was *signed out* marks the request so that a page
  that would send the person to sign in sends them to the landing page instead
  (LOGOUTREPLAY-1: a cookie copied before sign-out is dead, and reads as signed out);
- on the way out, the cookie is written from a copy without the secrets, which go into the vault
  under the session's ``sid`` (a new one when the token changed); a session that no longer has a
  token ends its ``sid``.

The vault is this process's memory: a restart ends every console session (the engine's sessions
themselves are unaffected and expire on their own), and several console instances behind one
address need sticky sessions. Entries expire with the session (12 hours, ``SESSION_SECONDS``).
"""
from __future__ import annotations

import secrets
import threading
import time
from typing import Any

#: The session keys that are credentials, and so live here rather than in the cookie: the engine
#: session token, and a key or reset secret the engine has just issued (shown once, then popped).
SECRET_KEYS = ("token", "issued")

#: The ASGI scope key that says this request's cookie named a session that was signed out.
SIGNED_OUT = "pravaha.signed_out"


class SessionSecrets:
    """``sid`` -> the session's secrets, each until the session's own expiry; and the ``sid`` s
    signed out, remembered as long as their cookie could still be presented."""

    def __init__(self, lifetime_seconds: float, clock=time.monotonic) -> None:
        self._lifetime = lifetime_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._live: dict[str, tuple[dict[str, Any], float]] = {}
        self._ended: dict[str, float] = {}

    def _prune(self, now: float) -> None:
        for table in (self._live, self._ended):
            stale = [sid for sid, value in table.items()
                     if (value[1] if isinstance(value, tuple) else value) <= now]
            for sid in stale:
                del table[sid]

    def get(self, sid: str) -> dict[str, Any] | None:
        now = self._clock()
        with self._lock:
            held = self._live.get(sid)
            if held is None or held[1] <= now:
                return None
            return dict(held[0])

    def put(self, held: dict[str, Any], sid: str | None = None) -> str:
        """Keeps ``held`` under ``sid`` (a fresh one if None) and answers the ``sid``."""
        now = self._clock()
        with self._lock:
            self._prune(now)
            if sid is None or sid not in self._live:
                sid = secrets.token_urlsafe(32)
                expires = now + self._lifetime
            else:
                expires = self._live[sid][1]
            self._live[sid] = (dict(held), expires)
            return sid

    def end(self, sid: str) -> None:
        """Forgets ``sid``'s secrets and remembers that it was signed out."""
        now = self._clock()
        with self._lock:
            if self._live.pop(sid, None) is not None:
                self._ended[sid] = now + self._lifetime

    def was_signed_out(self, sid: str) -> bool:
        now = self._clock()
        with self._lock:
            expires = self._ended.get(sid)
            return expires is not None and expires > now

    def __len__(self) -> int:
        with self._lock:
            return len(self._live)


def _mark_modified(session: dict) -> None:
    marker = getattr(session, "mark_modified", None)
    if callable(marker):
        marker()


def _without_secrets(session: dict) -> dict:
    """``session`` without :data:`SECRET_KEYS`, as the same class with the same marks."""
    copy = dict.__new__(type(session))
    dict.__init__(copy, {key: value for key, value in session.items() if key not in SECRET_KEYS})
    for mark in ("accessed", "modified"):
        if mark in vars(session):
            setattr(copy, mark, getattr(session, mark))
    return copy


class TokenVault:
    """Pure ASGI, between the session cookie middleware (outside) and the identity middleware."""

    def __init__(self, app, *, store: SessionSecrets) -> None:
        self.app = app
        self.store = store

    async def __call__(self, scope, receive, send):
        session = scope.get("session")
        if scope.get("type") != "http" or not isinstance(session, dict):
            await self.app(scope, receive, send)
            return
        arrived = session.get("sid")
        arrived = arrived if isinstance(arrived, str) else None
        if arrived is not None:
            held = self.store.get(arrived)
            if held is None:
                # Signed out, expired, or from before this process started: nothing the cookie
                # says about who this was stands any more.
                if self.store.was_signed_out(arrived):
                    scope[SIGNED_OUT] = True
                session.clear()
                arrived = None
            else:
                session.update(held)
        else:
            # A cookie written by a console from before COOKIETOKEN-1, with the token in it: kept
            # for this request and moved into the vault on the way out, so it is never written back.
            pass

        async def vaulted(message):
            if message["type"] != "http.response.start":
                await send(message)
                return
            current = scope.get("session")
            if not isinstance(current, dict):
                await send(message)
                return
            secret = {key: current[key] for key in SECRET_KEYS if current.get(key)}
            if secret.get("token"):
                keep = current.get("sid") if current.get("sid") == arrived else None
                if keep is not None:
                    previous = self.store.get(keep) or {}
                    if previous.get("token") != secret["token"]:
                        keep = None
                sid = self.store.put(secret, keep)
                if arrived is not None and arrived != sid:
                    self.store.end(arrived)
                if current.get("sid") != sid:
                    current["sid"] = sid
                if arrived is None:
                    # A token that came in a cookie (one written before COOKIETOKEN-1): the cookie
                    # is written again, now without it.
                    _mark_modified(current)
            else:
                current.pop("sid", None)
                if arrived is not None:
                    self.store.end(arrived)
            # The cookie middleware outside encodes scope["session"] as this message passes it;
            # it sees a copy without the secrets -- the same type, carrying the same "was it
            # read, was it changed" marks, so the cookie is rewritten exactly when it would have
            # been -- and the route's own session comes back after.
            written = _without_secrets(current)
            scope["session"] = written
            try:
                await send(message)
            finally:
                scope["session"] = current

        await self.app(scope, receive, vaulted)
