"""
Pravaha console — signing in.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Until this existed the console held one engine token from configuration and
acted as it for every visitor. Anyone who could reach the port could register,
pause and drop continuous queries, and nothing recorded who did. That quietly
undid ADR-031: the engine enforces authorization carefully and the console handed
every anonymous browser the same standing identity.

A single shared secret, not a user directory. That is deliberate for now: the
engine's own identities come from a deployment's identity provider through
``TokenVerifier``, and inventing a second, weaker account system beside it would
be worse than an honest gate. What this does is stop an unauthenticated visitor
acting, and record who acted.
"""
from __future__ import annotations

import hmac
import logging
from urllib.parse import quote

from fastapi import Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse

from routes.base import Routes

logger = logging.getLogger(__name__)


def local_path(target: str) -> str:
    """Confines a redirect to this site.

    A ``next`` parameter is rendered back into the form and then followed by the
    browser, so an absolute URL here is an open redirect with a login page in
    front of it.
    """
    if not target or not target.startswith("/") or target.startswith("//"):
        return "/overview"
    return target


def current_user(request: Request):
    try:
        return request.session.get("user")
    except Exception:  # noqa: BLE001 -- no session middleware configured
        return None


def login_required(request: Request):
    """A redirect when the caller is anonymous, otherwise None."""
    if current_user(request) is None:
        return RedirectResponse(
            f"/login?next={quote(local_path(request.url.path), safe='/')}", status_code=303)
    return None


class AuthRoutes(Routes):
    def register(self) -> None:
        secret = self.ctx["config"].get("console.password", "") or ""

        @self.app.get("/login", response_class=HTMLResponse, tags=["auth"])
        def login_page(request: Request, next: str = "/overview"):
            return self.page(request, "login.html", current="/login",
                             next=local_path(next), error=None, configured=bool(secret))

        @self.app.post("/login", tags=["auth"])
        def login_submit(request: Request, password: str = Form(""),
                         next: str = Form("/overview")):
            # Defaulted rather than required, so an empty submission reaches the check below
            # and is refused as a wrong password. Declared required, the form validator
            # rejects it first with a 422 -- a validation error, which is not what happened.
            # Constant-time, because a timing difference on a shared secret is a
            # way to learn it one character at a time.
            if not secret or not hmac.compare_digest(password, secret):
                logger.warning("failed console sign-in from %s",
                               request.client.host if request.client else "unknown")
                return self.page(request, "login.html", http_status=401, current="/login",
                                 next=local_path(next), configured=bool(secret),
                                 error="That is not the console password.")
            request.session["user"] = "operator"
            logger.info("console sign-in from %s",
                        request.client.host if request.client else "unknown")
            return RedirectResponse(local_path(next), status_code=303)

        @self.app.get("/logout", tags=["auth"])
        def logout(request: Request):
            request.session.clear()
            return RedirectResponse("/", status_code=303)
