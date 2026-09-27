"""
Pravaha console — the browser-facing half of signing in: the session cookie, the request's
credential, the forced password change, and CSRF.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Nothing here authenticates anybody. The engine does (ADR-052): a password, a key and a session
are checked there and nowhere else. What the console keeps is the engine's session token, in a
signed, HttpOnly, SameSite=Lax cookie, and what this module does with it is carry it:

- :class:`SchemeSessions` -- the session cookie, marked ``Secure`` whenever the request reached
  the console over https (directly, or through a proxy uvicorn is told to trust), and not
  otherwise, so one configuration serves a loopback install and a TLS-terminated one;
- :class:`IdentityMiddleware` -- binds the session's token to the request (``core.credential``)
  so every engine call made while serving it is made as that person; holds a person whose
  password must change to the password page; and, when an engine call said the session is over
  (``PRV-7016``, a 401) or that the password must change first (``PRV-7018``), answers with the
  way back instead of the page that could not be drawn;
- :func:`csrf_protect` -- MAYA's synchronizer token: one random value per session, sent back by
  every form (``csrf_token``) and every state-changing fetch (``X-CSRF-Token``), compared in
  constant time. It is installed on every route, so a POST nobody remembered cannot skip it.
"""
from __future__ import annotations

import hmac
import secrets
from urllib.parse import quote

from fastapi import Request
from starlette.middleware.sessions import SessionMiddleware
from starlette.responses import JSONResponse, RedirectResponse

from core import credential
from routes.base import ui_text

#: The methods that change something, and so must carry the session's CSRF token.
UNSAFE = frozenset({"POST", "PUT", "PATCH", "DELETE"})

#: Where a session that must change its password may still go: the page that changes it, the
#: way out, the sign-in (to become somebody else), the assets those pages load, and the probes.
MUST_CHANGE_ALLOWED = ("/account/password", "/logout", "/login", "/static/", "/health")

#: The engine's session lasts at most 12 hours (ADR-052); the cookie holding it no longer.
SESSION_SECONDS = 12 * 3600


class SchemeSessions:
    """Starlette's signed-cookie sessions, ``Secure`` exactly when the request came over https.

    Starlette fixes the flag when the middleware is built. Two instances over the same secret and
    cookie name, chosen per request by the scheme uvicorn reports, is what lets the flag follow
    how the console is served rather than a setting somebody has to remember to change.
    ``secure`` forces it on (a proxy that terminates TLS without saying so).
    """

    def __init__(self, app, *, secret_key: str, secure: bool = False,
                 cookie: str = "pravaha_console") -> None:
        self._plain = SessionMiddleware(app, secret_key=secret_key, session_cookie=cookie, same_site="lax",
                                        max_age=SESSION_SECONDS, https_only=secure)
        self._secure = SessionMiddleware(app, secret_key=secret_key, session_cookie=cookie, same_site="lax",
                                         max_age=SESSION_SECONDS, https_only=True)

    async def __call__(self, scope, receive, send):
        if scope.get("type") in ("http", "websocket") and scope.get("scheme") in ("https", "wss"):
            await self._secure(scope, receive, send)
        else:
            await self._plain(scope, receive, send)


def _allowed_while_must_change(path: str) -> bool:
    return any(path == p or (p.endswith("/") and path.startswith(p)) or path.startswith(p + "/")
               for p in MUST_CHANGE_ALLOWED)


def _next_of(scope) -> str:
    """Where to return after signing in again: this page, for a GET; the landing otherwise."""
    if scope.get("method") != "GET":
        return "/home"
    path = scope.get("path") or "/home"
    query = (scope.get("query_string") or b"").decode("latin-1")
    return path + ("?" + query if query else "")


class IdentityMiddleware:
    """Pure ASGI, inside the session middleware (it reads ``scope["session"]``)."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        session = scope.get("session")
        if not isinstance(session, dict):
            session = {}
        token = session.get("token") or None
        path = scope.get("path") or "/"
        if token and session.get("must_change") and not _allowed_while_must_change(path):
            await _must_change_answer(scope)(scope, receive, send)
            return

        held = credential.Credential(token)
        handle = credential.bind(held)
        replaced = False

        async def guarded(message):
            nonlocal replaced
            if message["type"] == "http.response.start" and (held.expired or held.must_change):
                # The route has answered, but with a page drawn around a refusal of the session
                # itself. The answer that helps is the way back, so it replaces that page.
                replaced = True
                if held.expired:
                    session.clear()
                    answer = _expired_answer(scope)
                else:
                    session["must_change"] = True
                    answer = _must_change_answer(scope)
                await answer(scope, receive, send)
                return
            if replaced:
                return
            await send(message)

        try:
            await self.app(scope, receive, guarded)
        finally:
            credential.unbind(handle)


def _is_api(scope) -> bool:
    return (scope.get("path") or "").startswith("/api/")


def _expired_answer(scope):
    if _is_api(scope):
        return JSONResponse({"error": ui_text("session.expired"), "status": 401, "code": "PRV-7016"},
                            status_code=401)
    return RedirectResponse("/login?expired=1&next=" + quote(_next_of(scope), safe="/"), status_code=303)


def _must_change_answer(scope):
    if _is_api(scope):
        return JSONResponse({"error": ui_text("session.must_change"), "status": 403, "code": "PRV-7018"},
                            status_code=403)
    return RedirectResponse("/account/password", status_code=303)


# ------------------------------------------------------------------------------------ CSRF

def csrf_token(request: Request) -> str:
    """This session's synchronizer token, made on first use."""
    try:
        session = request.session
    except AssertionError:  # no session middleware on this application
        return ""
    token = session.get("csrf")
    if not token:
        token = secrets.token_urlsafe(24)
        session["csrf"] = token
    return str(token)


class CsrfRefused(Exception):
    """A state-changing request without this session's token."""


async def csrf_protect(request: Request) -> None:
    """Every POST, PUT, PATCH and DELETE carries the session's token, or is refused.

    The header first (``X-CSRF-Token``, which api.js sends on every call), then the form field
    (``csrf_token``, which every server-rendered form carries). Compared in constant time: a
    comparison that stops at the first wrong character tells a patient attacker how many were
    right. A session with no token at all -- it expired, or the browser dropped the cookie --
    refuses too; there is nothing the request could have matched.
    """
    if request.method not in UNSAFE:
        return
    try:
        expected = request.session.get("csrf")
    except AssertionError:
        expected = None
    sent = request.headers.get("x-csrf-token")
    if not sent:
        kind = request.headers.get("content-type", "")
        if kind.startswith(("application/x-www-form-urlencoded", "multipart/form-data")):
            field = (await request.form()).get("csrf_token")
            sent = field if isinstance(field, str) else None
    if not expected or not sent or not hmac.compare_digest(str(sent).encode("utf-8"),
                                                           str(expected).encode("utf-8")):
        raise CsrfRefused()
