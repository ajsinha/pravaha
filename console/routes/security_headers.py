"""
Pravaha console — the response headers that tell a browser what this console's pages may do.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

CONSOLEHDR-1. The console sent none of them, so a signed-in operator could be shown it inside
another site's frame -- the pause, drop and revoke buttons under somebody else's page. Its forms
carry CSRF tokens and its cookie is SameSite=Lax; framing is the attack those do not stop.

Every response now carries:

- ``Content-Security-Policy`` -- scripts only from this console, or inline with this response's
  nonce (the theme snippet, the import map, the views filter); styles from this console or inline
  (the help pages' and Monaco's own); images, fonts, fetches and workers from this console;
  nothing may frame it (``frame-ancestors 'none'``); no plugins; forms post only here;
- ``X-Frame-Options: DENY`` -- the same refusal for a browser that predates frame-ancestors;
- ``X-Content-Type-Options: nosniff``;
- ``Referrer-Policy: same-origin`` -- a console URL (a view's name, a ``next=`` path) is not sent
  to another site a page links to;
- ``Permissions-Policy`` denying the camera, microphone, geolocation and payment;
- ``Strict-Transport-Security`` when the request reached the console over https.

FastAPI's own API documentation (``/api/docs``, ``/api/redoc``) loads Swagger UI and ReDoc from a
CDN with inline bootstrap code; those two pages get every header but the script and style rules.
"""
from __future__ import annotations

import secrets

#: Where FastAPI serves its generated documentation, whose pages this console does not write.
DOCS_PATHS = ("/api/docs", "/api/redoc")

#: The ``request.state`` key templates read the nonce from (``routes.base.Routes.brand``).
NONCE_KEY = "csp_nonce"


def policy(nonce: str) -> str:
    """The Content-Security-Policy for a console page whose inline scripts carry ``nonce``."""
    return "; ".join((
        "default-src 'self'",
        f"script-src 'self' 'nonce-{nonce}'",
        "style-src 'self' 'unsafe-inline'",
        "img-src 'self' data: blob:",
        "font-src 'self' data:",
        "connect-src 'self'",
        "worker-src 'self' blob:",
        "object-src 'none'",
        "base-uri 'self'",
        "form-action 'self'",
        "frame-ancestors 'none'",
    ))


#: What FastAPI's documentation pages get: everything that does not stop them rendering.
DOCS_POLICY = "object-src 'none'; base-uri 'self'; frame-ancestors 'none'"

STATIC_HEADERS = (
    (b"x-frame-options", b"DENY"),
    (b"x-content-type-options", b"nosniff"),
    (b"referrer-policy", b"same-origin"),
    (b"permissions-policy", b"camera=(), microphone=(), geolocation=(), payment=()"),
)


class SecurityHeaders:
    """Pure ASGI, outermost but for the request context: every response, error pages included."""

    def __init__(self, app) -> None:
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        nonce = secrets.token_urlsafe(18)
        scope.setdefault("state", {})[NONCE_KEY] = nonce
        path = scope.get("path") or "/"
        csp = DOCS_POLICY if path.startswith(DOCS_PATHS) else policy(nonce)
        https = scope.get("scheme") == "https"

        async def with_headers(message):
            if message["type"] == "http.response.start":
                headers = list(message.get("headers") or [])
                present = {name.lower() for name, _ in headers}
                if b"content-security-policy" not in present:
                    headers.append((b"content-security-policy", csp.encode("latin-1")))
                for name, value in STATIC_HEADERS:
                    if name not in present:
                        headers.append((name, value))
                if https and b"strict-transport-security" not in present:
                    headers.append((b"strict-transport-security", b"max-age=31536000"))
                message = {**message, "headers": headers}
            await send(message)

        await self.app(scope, receive, with_headers)
