"""
Pravaha console — signing in, signing out, and the signed-in person's own account.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    GET  /login, POST /login        username and password, checked by the engine
    POST /logout                    ends the engine session, then the console's
    GET  /account                   who I am, my API keys, my sessions, where I land
    GET  /account/password, POST    change my password (and the forced change)
    POST /account/keys              create a key -- shown once, then never again
    POST /account/keys/{id}/rotate  a successor with the same scope, shown once
    POST /account/keys/{id}/revoke
    POST /account/sessions/{id}/end

ADR-052: the engine is the identity authority. This module authenticates nobody. Signing in is
``POST /api/v1/auth/login`` to the engine; the console keeps only the session token it answers
with, in its signed session cookie, and every later call it makes for this person carries that
token (``core.credential``, ``routes.web_security``). "Who am I" and the roles are the engine's
``GET auth/me``; signing out is its ``auth/logout``; a password is changed by its
``auth/password``. There is no console password, no user table and no shared engine token --
the console used to have the first and the last, and both are gone.

A secret the engine hands back once -- a new API key -- is carried to the page that shows it
through the session and popped there, as MAYA's web tier does: shown on the next page and on no
page after it.
"""
from __future__ import annotations

import logging
import re
from urllib.parse import quote

from fastapi import Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from core import credential
from core.accounts import expiry_days, roles_from
from core.services import ServiceError
from routes.base import ROLES, Routes, failure, session_roles
from routes.web_security import CsrfRefused, csrf_token

logger = logging.getLogger(__name__)

#: The cookie that remembers each person's landing persona on this browser, by username. Signed
#: with the session secret; a preference, never a permission.
LANDING_COOKIE = "pravaha_landing"


def local_path(target: str) -> str:
    """Confines a redirect to this site.

    A ``next`` parameter is rendered back into the form and then followed by the
    browser, so an absolute URL here is an open redirect with a login page in
    front of it.
    """
    if not target or not target.startswith("/") or target.startswith("//") or "\\" in target:
        return "/home"
    return target


def current_user(request: Request):
    """The signed-in person's username, or None. Signed in means the console holds an engine
    session token for them; a username without one is nobody."""
    try:
        if not request.session.get("token"):
            return None
        return request.session.get("user")
    except Exception:  # noqa: BLE001 -- no session middleware configured
        return None


def login_required(request: Request):
    """A redirect when the caller is anonymous, otherwise None."""
    if current_user(request) is None:
        return RedirectResponse(
            f"/login?next={quote(local_path(request.url.path), safe='/')}", status_code=303)
    return None


def flash(request: Request, message: str, level: str = "info") -> None:
    """A one-line outcome for the next page this person sees (and no page after it)."""
    request.session.setdefault("flashes", []).append([level, message])


def take_flashes(request: Request) -> list[list[str]]:
    try:
        return list(request.session.pop("flashes", []) or [])
    except Exception:  # noqa: BLE001
        return []


def take_issued(request: Request) -> dict | None:
    """The secret the engine issued on the last request, popped: this is the only read."""
    try:
        return request.session.pop("issued", None)
    except Exception:  # noqa: BLE001
        return None


_INSTANT = re.compile(r"\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:?\d{2})?")


def _locked_until(exc: ServiceError) -> str | None:
    """When a lock ends, if the engine's message says (``PRV-7011``)."""
    found = _INSTANT.search(str(exc))
    return found.group(0) if found else None


class AuthRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        config = self.ctx["config"]
        signer = self.ctx.get("signer")

        # ------------------------------------------------------------ the landing preference
        def remembered_landing(request: Request, username: str) -> str | None:
            if signer is None:
                return None
            try:
                prefs = signer.loads(request.cookies.get(LANDING_COOKIE, ""))
            except Exception:  # noqa: BLE001 -- absent, tampered with, or from another secret
                return None
            chosen = prefs.get(username) if isinstance(prefs, dict) else None
            return chosen if chosen in ROLES else None

        def remember_landing(request: Request, response, username: str, role: str) -> None:
            """Per person, on this browser: the next time *this* person signs in here they land
            where they chose; somebody else signing in on the same browser does not."""
            if signer is None or role not in ROLES:
                return
            try:
                prefs = signer.loads(request.cookies.get(LANDING_COOKIE, ""))
                prefs = prefs if isinstance(prefs, dict) else {}
            except Exception:  # noqa: BLE001
                prefs = {}
            prefs[username] = role
            response.set_cookie(LANDING_COOKIE, signer.dumps(prefs), max_age=365 * 86400,
                                httponly=True, samesite="lax",
                                secure=request.url.scheme == "https")

        self.ctx["remember_landing"] = remember_landing

        # ------------------------------------------------------------ CSRF refusals
        @self.app.exception_handler(CsrfRefused)
        async def csrf_refused(request: Request, _exc: CsrfRefused):
            logger.warning("refused a %s to %s without this session's CSRF token", request.method,
                           request.url.path)
            if request.url.path.startswith("/api/"):
                return JSONResponse({"error": self.t("csrf.refused"), "status": 403, "code": "CSRF"},
                                    status_code=403)
            if request.url.path == "/login" or current_user(request) is None:
                # A sign-in form from before a restart, or a session that has gone: start again.
                return RedirectResponse("/login?stale=1", status_code=303)
            referer = request.headers.get("referer", "")
            back = local_path("/" + referer.split("/", 3)[3]) if referer.count("/") >= 3 else "/home"
            return self.page(request, "refused.html", http_status=403, what=self.t("csrf.what"),
                             detail=self.t("csrf.detail"), code=None, quota=None,
                             back_href=back, back_label=self.t("csrf.back"))

        # ------------------------------------------------------------ signing in
        def login_form(request: Request, *, http_status: int = 200, error: str | None = None,
                       next_path: str = "/home", username: str = "", notice: str | None = None):
            csrf_token(request)
            return self.page(request, "login.html", http_status=http_status, current="/login",
                             next=local_path(next_path), error=error, notice=notice,
                             login_username=username)

        @self.app.get("/login", response_class=HTMLResponse, tags=["auth"])
        def login_page(request: Request, next: str = "/home", expired: str = "", stale: str = "",
                       changed: str = ""):
            notice = (self.t("login.expired") if expired else self.t("login.stale") if stale
                      else self.t("login.password_changed") if changed else None)
            return login_form(request, next_path=next, notice=notice)

        @self.app.post("/login", tags=["auth"])
        def login_submit(request: Request, username: str = Form(""), password: str = Form(""),
                         next: str = Form("/home")):
            # Defaulted rather than required, so an empty submission reaches the engine and is
            # refused there like any wrong password -- not by a form validator with a 422.
            where = request.client.host if request.client else "unknown"
            try:
                answer = services.accounts.login(username.strip(), password)
            except ServiceError as exc:
                logger.warning("sign-in refused for '%s' from %s (%s)", username, where, exc.code or "-")
                return login_form(request, http_status=_login_status(exc), error=self._refusal(exc),
                                  next_path=next, username=username)
            token = str(answer.get("token") or "")
            if not token:
                return login_form(request, http_status=502, error=self.t("login.no_token"),
                                  next_path=next, username=username)
            if str(answer.get("mfa") or "ok") != "ok":
                # A second factor is stage 4 of ADR-052. Until the console can ask for one, it
                # does not keep a session the engine has not finished opening.
                with credential.bound(credential.Credential(token)):
                    try:
                        services.accounts.logout()
                    except ServiceError:
                        pass
                return login_form(request, http_status=401, error=self.t("login.mfa_unsupported"),
                                  next_path=next, username=username)
            # Who this is, from the engine. A session whose password must change may be refused
            # everything else (PRV-7018); the roles then wait for the next sign-in.
            me: dict = {}
            with credential.bound(credential.Credential(token)):
                try:
                    me = services.accounts.me()
                except ServiceError:
                    me = {}
            name = str(me.get("username") or me.get("principal") or username.strip())
            request.session.clear()
            request.session.update(
                token=token, user=name, expires=str(answer.get("expiresAt") or ""),
                must_change=bool(answer.get("mustChangePassword") or me.get("mustChangePassword")),
                roles=[str(r) for r in (me.get("roles") or [])],
                tenant=str(me.get("tenant") or ""))
            landing = remembered_landing(request, name)
            if landing:
                request.session["role"] = landing
            # A fresh token for a fresh session: one issued to the anonymous form is not reused.
            request.session.pop("csrf", None)
            csrf_token(request)
            logger.info("'%s' signed in from %s", name, where)
            if request.session["must_change"]:
                flash(request, self.t("account.password.forced_flash"), "warning")
                return RedirectResponse("/account/password", status_code=303)
            return RedirectResponse(local_path(next), status_code=303)

        # ------------------------------------------------------------ redeeming a reset
        # No session, by definition: whoever holds a reset token an administrator issued sets a
        # new password with it. The engine checks the token (single use, 60 minutes) and the
        # password (its policy); the console only carries both.
        def reset_form(request: Request, *, http_status: int = 200, error: str | None = None,
                       token: str = ""):
            csrf_token(request)
            return self.page(request, "login_reset.html", http_status=http_status, current="/login",
                             error=error, reset_token=token)

        @self.app.get("/login/reset", response_class=HTMLResponse, tags=["auth"])
        def reset_page(request: Request, token: str = ""):
            return reset_form(request, token=token)

        @self.app.post("/login/reset", tags=["auth"])
        def reset_submit(request: Request, token: str = Form(""), new_password: str = Form(""),
                         confirm_password: str = Form("")):
            if new_password != confirm_password:
                return reset_form(request, http_status=400, error=self.t("account.password.differ"),
                                  token=token)
            try:
                services.accounts.redeem_reset(token.strip(), new_password)
            except ServiceError as exc:
                message = (self.t("reset.invalid") if exc.code == "PRV-7017"
                           else self._password_refusal(exc))
                return reset_form(request, http_status=400, error=message, token=token)
            request.session.clear()
            return RedirectResponse("/login?changed=1", status_code=303)

        @self.app.post("/logout", tags=["auth"])
        def logout(request: Request):
            if current_user(request) is not None:
                try:
                    services.accounts.logout()
                except ServiceError as exc:
                    # Already over on the engine's side is still signed out on this one.
                    logger.info("the engine did not end the session cleanly (%s)", exc.code or "-")
            request.session.clear()
            # Out to the landing page, not to a sign-in form: somebody who has just left is not
            # halfway through arriving, and the form implies they should try again.
            return RedirectResponse("/", status_code=303)

        # ------------------------------------------------------------ the account page
        def account_page(request: Request, http_status: int = 200):
            me, me_error = self._safe(services.accounts.me, {}, request, "your account")
            my_keys, keys_error = self._safe(services.accounts.keys, [], request, "your API keys")
            my_sessions, sessions_error = self._safe(services.accounts.sessions, [], request,
                                                     "your sessions")
            return self.page(request, "account.html", http_status=http_status, current="/account",
                             me=me, me_error=me_error, my_keys=my_keys, keys_error=keys_error,
                             my_sessions=my_sessions, sessions_error=sessions_error,
                             my_roles=session_roles(request), issued=take_issued(request),
                             flashes=take_flashes(request),
                             default_landing=config.get("ui.default_role", "operator"))

        @self.app.get("/account", response_class=HTMLResponse, tags=["ui"])
        def account(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return account_page(request)

        @self.app.get("/account/password", response_class=HTMLResponse, tags=["ui"])
        def password_page(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return self.page(request, "account_password.html", current="/account",
                             forced=bool(request.session.get("must_change")),
                             flashes=take_flashes(request))

        @self.app.post("/account/password", tags=["ui"])
        def change_password(request: Request, current_password: str = Form(""),
                            new_password: str = Form(""), confirm_password: str = Form("")):
            if (refusal := login_required(request)) is not None:
                return refusal
            if new_password != confirm_password:
                flash(request, self.t("account.password.differ"), "danger")
                return RedirectResponse("/account/password", status_code=303)
            try:
                services.accounts.change_password(current_password, new_password)
            except ServiceError as exc:
                flash(request, self._password_refusal(exc), "danger")
                return RedirectResponse("/account/password", status_code=303)
            forced = bool(request.session.get("must_change"))
            request.session["must_change"] = False
            logger.info("'%s' changed their password", current_user(request))
            # The engine may end every session of the person on a change (ADR-052), this one
            # included; if it did, the way on is to sign in with the new password.
            # Asked on a credential of its own, so a refusal here is this answer and not the
            # middleware's "session expired" (which would say something that is not the case).
            try:
                with credential.bound(credential.Credential(request.session.get("token"))):
                    services.accounts.me()
            except ServiceError:
                request.session.clear()
                return RedirectResponse("/login?changed=1", status_code=303)
            flash(request, self.t("account.password.changed"), "success")
            return RedirectResponse("/home" if forced else "/account", status_code=303)

        # ------------------------------------------------------------ my keys
        @self.app.post("/account/keys", tags=["ui"])
        async def create_key(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            try:
                answer = services.accounts.create_key(
                    str(form.get("name") or "").strip(), roles_from(form.getlist("roles")),
                    expiry_days(days if isinstance(days := form.get("days"), str) else None))
            except ServiceError as exc:
                flash(request, self._refusal(exc), "danger")
                return RedirectResponse("/account#keys", status_code=303)
            request.session["issued"] = {"kind": "key", "secret": str(answer.get("key") or ""),
                                         "keyId": str(answer.get("keyId") or ""),
                                         "expiresAt": str(answer.get("expiresAt") or "")}
            logger.info("'%s' created API key %s", current_user(request), answer.get("keyId"))
            return RedirectResponse("/account#issued", status_code=303)

        @self.app.post("/account/keys/{key_id}/rotate", tags=["ui"])
        def rotate_key(request: Request, key_id: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                answer = services.accounts.rotate_key(key_id)
            except ServiceError as exc:
                flash(request, self._refusal(exc), "danger")
                return RedirectResponse("/account#keys", status_code=303)
            request.session["issued"] = {"kind": "rotated", "secret": str(answer.get("key") or ""),
                                         "keyId": str(answer.get("keyId") or ""),
                                         "expiresAt": str(answer.get("expiresAt") or ""),
                                         "oldKeyId": key_id,
                                         "oldExpiresAt": str(answer.get("oldExpiresAt") or "")}
            logger.info("'%s' rotated API key %s", current_user(request), key_id)
            return RedirectResponse("/account#issued", status_code=303)

        @self.app.post("/account/keys/{key_id}/revoke", tags=["ui"])
        def revoke_key(request: Request, key_id: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                services.accounts.revoke_key(key_id)
            except ServiceError as exc:
                flash(request, self._refusal(exc), "danger")
            else:
                flash(request, self.t("account.keys.revoked", key_id=key_id), "success")
                logger.info("'%s' revoked API key %s", current_user(request), key_id)
            return RedirectResponse("/account#keys", status_code=303)

        # ------------------------------------------------------------ my sessions
        @self.app.post("/account/sessions/{session_id}/end", tags=["ui"])
        def end_session(request: Request, session_id: str, current: str = Form("")):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                services.accounts.end_session(session_id)
            except ServiceError as exc:
                flash(request, self._refusal(exc), "danger")
                return RedirectResponse("/account#sessions", status_code=303)
            if current == "yes":
                # Ending the session this browser is using is signing out.
                request.session.clear()
                return RedirectResponse("/", status_code=303)
            flash(request, self.t("account.sessions.ended"), "success")
            return RedirectResponse("/account#sessions", status_code=303)

    # ---------------------------------------------------------------- shared by the screens
    def _safe(self, fn, fallback, request: Request, what: str):
        try:
            return fn(), None
        except ServiceError as exc:
            return fallback, failure(exc, request, what)

    def _refusal(self, exc: ServiceError) -> str:
        """An engine refusal as the sentence a person needs, with the engine's words after it
        when they add something. One sentence for a wrong username and a wrong password
        (``PRV-7010``): which of the two was wrong is exactly what an attacker wants to learn."""
        code = exc.code or ""
        if code == "PRV-7010":
            return self.t("login.refused")
        if code == "PRV-7011":
            until = _locked_until(exc)
            return self.t("login.locked_until", until=until) if until else self.t("login.locked")
        if code == "PRV-7016":
            return self.t("login.expired")
        if code == "PRV-7015":
            return self.t("account.keys.widen", detail=str(exc))
        if (exc.status or 0) >= 500 or exc.status == 0:
            return self.t("login.engine_down", detail=str(exc))
        return str(exc) + (f" ({code})" if code and code not in str(exc) else "")

    def _password_refusal(self, exc: ServiceError) -> str:
        if exc.code == "PRV-7012":
            # The engine's message names the rule that was broken; that is the useful part.
            return self.t("account.password.policy", detail=str(exc))
        if exc.code == "PRV-7010":
            return self.t("account.password.wrong_current")
        return self._refusal(exc)


def _login_status(exc: ServiceError) -> int:
    if exc.code == "PRV-7011":
        return 423
    if exc.code == "PRV-7010" or exc.status == 401:
        return 401
    return exc.status if exc.status and exc.status >= 400 else 401
