"""
Pravaha console — the administrative screens.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    /admin                  the admin persona's landing: Access
    /admin/access           what the engine's policy lets this console's identity do (read-only)
    /admin/audit            the audit trail: filterable, paged, every filter in the URL
    /admin/tenants          each tenant's use against its admission quotas, and its refusals
    /admin/users            ADR-052: create a person, set roles, disable or enable, issue a reset
    /admin/keys             every API key by its keyId (never a secret), revoke, the key report
    /admin/sessions         every session, and ending one

Each screen is the engine's answer (``GET /api/v1/me/permissions``, ``GET /api/v1/audit``,
``GET /api/v1/tenants``)
through the SDK, and each is behind the sign-in gate like every screen that reaches the engine.
Neither adds a permission of its own: the engine decides whether the console's identity may
read the audit trail, and a refusal is rendered as the screen's "not permitted" state with the
engine's reason. Grants are not edited here because the engine is not where grants live.
"""
from __future__ import annotations

import logging
from urllib.parse import urlencode

from fastapi import Form, Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from core.accounts import roles_from
from core.admin import AUDIT_FILTERS
from core.services import ServiceError
from routes.auth_routes import (
    current_user,
    flash,
    login_required,
    take_flashes,
    take_issued,
)
from routes.base import Routes, failure, sign_in_first

logger = logging.getLogger(__name__)


def _refuse_anonymous(request: Request) -> JSONResponse | None:
    from routes.auth_routes import current_user

    if current_user(request) is None:
        return sign_in_first()
    return None


def _problem(exc: ServiceError) -> tuple[dict, int]:
    """A refusal's body and status. The service's own status, which ``_refusal`` set from the
    engine's -- a malformed filter is the engine's 400 (``PRV-1051``), not a transport failure."""
    status = exc.status or 400
    body = {"error": str(exc), "status": status}
    if exc.code:
        body["code"] = exc.code
    return body, status


def _link(filters: dict, **extra) -> str:
    """The audit page with these filters, and only the ones that are set."""
    query = {k: v for k, v in {**filters, **extra}.items() if v not in (None, "")}
    return "/admin/audit" + ("?" + urlencode(query) if query else "")


class AdminRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]

        @self.app.get("/admin", tags=["ui"])
        def admin(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return RedirectResponse("/admin/access", status_code=303)

        @self.app.get("/admin/access", response_class=HTMLResponse, tags=["ui"])
        def access(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                permissions, error = services.admin.permissions(), None
            except ServiceError as exc:
                permissions, error = None, failure(exc, request, "the permissions")
            return self.page(request, "admin_access.html", current="/admin", tab="access",
                             permissions=permissions, permissions_error=error)

        @self.app.get("/admin/audit", response_class=HTMLResponse, tags=["ui"])
        def audit(request: Request, cursor: str = ""):
            """The audit trail as a plain GET form and a table: filters, paging and the
            permitted/not-permitted states all work without a script, and every one is a URL."""
            if (refusal := login_required(request)) is not None:
                return refusal
            filters = {name: request.query_params.get(name, "") for name in AUDIT_FILTERS}
            answer, error, http_status = None, None, 200
            try:
                answer = services.admin.audit(filters, cursor=cursor)
                if not answer["permitted"]:
                    http_status = 403
            except ServiceError as exc:
                error, http_status = failure(exc, request, "the audit trail"), _problem(exc)[1]
            page = (answer or {}).get("page") or {}
            next_cursor = page.get("nextCursor")
            return self.page(
                request, "admin_audit.html", http_status=http_status, current="/admin", tab="audit",
                filters=(answer or {}).get("filters") or filters, answer=answer, audit_error=error,
                filtered=any(filters.values()),
                cursor=cursor, newest_href=_link(filters) if cursor else None,
                older_href=_link(filters, cursor=next_cursor) if next_cursor else None,
                principal_href=lambda who: _link({**filters, "principal": who}),
                view_href=lambda what: _link({**filters, "view": what}))

        @self.app.get("/admin/tenants", response_class=HTMLResponse, tags=["ui"])
        def tenants(request: Request):
            """Quotas in force, each tenant's use against them, and the refusals (ADR-050). Which
            tenants are listed is the engine's decision: every tenant for an identity that may
            read the audit trail, its own tenant for any other, and the page says which."""
            if (refusal := login_required(request)) is not None:
                return refusal
            answer, error, http_status = None, None, 200
            try:
                answer = services.admin.tenants()
            except ServiceError as exc:
                error, http_status = failure(exc, request, "the tenants"), _problem(exc)[1]
            return self.page(request, "admin_tenants.html", http_status=http_status,
                             current="/admin", tab="tenants", answer=answer, tenants_error=error)

        @self.app.get(f"{self.api}/admin/tenants", tags=["api"])
        def api_tenants(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(services.admin.tenants, request=request)

        @self.app.get(f"{self.api}/admin/audit", tags=["api"])
        def api_audit(request: Request, cursor: str = "", limit: int = 50):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            filters = {name: request.query_params.get(name, "") for name in AUDIT_FILTERS}
            try:
                answer = services.admin.audit(filters, cursor=cursor, limit=limit)
            except ServiceError as exc:
                logger.warning("refused (%s): %s", exc.code or "-", exc)
                body, status = _problem(exc)
                return JSONResponse(body, status_code=status)
            # Not permitted is the engine's 403, and stays one: a client reading this must not
            # take an empty page for "nobody asked for anything".
            return JSONResponse(answer, status_code=200 if answer["permitted"] else 403)

        @self.app.get(f"{self.api}/admin/permissions", tags=["api"])
        def api_permissions(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(services.admin.permissions, request=request)

        self._register_identity(services)

    # ------------------------------------------------------------------ ADR-052: people
    def _register_identity(self, services) -> None:
        """Users, every key, every session: the engine's administration endpoints, called as
        the signed-in administrator. The console decides nothing -- a person without the
        engine's ``admin`` role is refused by the engine, and the screen says so -- and the
        links to these screens are hidden from such a person only so they are not offered
        a door that will not open."""
        accounts = services.accounts

        def refused(exc: ServiceError) -> dict | None:
            """The engine's refusal of this person, as the screen's not-permitted state."""
            if exc.status == 403 or (exc.code or "") in ("PRV-7002", "PRV-7003"):
                return {"reason": str(exc), "code": exc.code}
            return None

        def load(fn, request: Request, what: str):
            try:
                return fn(), None, None
            except ServiceError as exc:
                if (denied := refused(exc)) is not None:
                    return None, None, denied
                return None, failure(exc, request, what), None

        def outcome(request: Request, fn, done: str, back: str, **params):
            try:
                answer = fn()
            except ServiceError as exc:
                flash(request, self.t("admin.people.failed", detail=_said(exc)), "danger")
                return None, RedirectResponse(back, status_code=303)
            if done:
                flash(request, self.t(done, **params), "success")
            return answer, RedirectResponse(back, status_code=303)

        @self.app.get("/admin/users", response_class=HTMLResponse, tags=["ui"])
        def users(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            rows, error, denied = load(accounts.users, request, "the users")
            return self.page(request, "admin_users.html", http_status=403 if denied else 200,
                             current="/admin", tab="users", users=rows, users_error=error,
                             denied=denied, issued=take_issued(request), flashes=take_flashes(request))

        @self.app.post("/admin/users", tags=["ui"])
        async def create_user(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            fields = {"username": str(form.get("username") or "").strip(),
                      "roles": roles_from(str(form.get("roles") or "")),
                      "password": str(form.get("password") or "")}
            for name in ("displayName", "email", "tenant"):
                value = str(form.get(name) or "").strip()
                if value:
                    fields[name] = value
            _, answer = outcome(request, lambda: accounts.create_user(fields), "admin.users.created",
                                "/admin/users", user=fields["username"])
            logger.info("'%s' asked the engine to create user '%s'", current_user(request),
                        fields["username"])
            return answer

        @self.app.post("/admin/users/{username}/roles", tags=["ui"])
        def set_roles(request: Request, username: str, roles: str = Form("")):
            if (refusal := login_required(request)) is not None:
                return refusal
            chosen = roles_from(roles)
            logger.info("'%s' set the roles of '%s' to %s", current_user(request), username, chosen)
            return outcome(request, lambda: accounts.set_roles(username, chosen), "admin.users.roles_set",
                           "/admin/users", user=username)[1]

        @self.app.post("/admin/users/{username}/status", tags=["ui"])
        def set_status(request: Request, username: str, status: str = Form("")):
            if (refusal := login_required(request)) is not None:
                return refusal
            if status not in ("active", "disabled"):
                flash(request, self.t("admin.users.bad_status"), "danger")
                return RedirectResponse("/admin/users", status_code=303)
            logger.info("'%s' set '%s' %s", current_user(request), username, status)
            return outcome(request, lambda: accounts.set_status(username, status),
                           "admin.users." + ("enabled" if status == "active" else "disabled"),
                           "/admin/users", user=username)[1]

        @self.app.post("/admin/users/{username}/password-reset", tags=["ui"])
        def reset_password(request: Request, username: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            answer, redirect = outcome(request, lambda: accounts.reset_password(username), "",
                                       "/admin/users")
            if answer is not None:
                # Shown once, on the next page: the engine keeps only its hash.
                request.session["issued"] = {"kind": "reset", "for": username,
                                             "secret": str(answer.get("resetToken") or ""),
                                             "expiresAt": str(answer.get("expiresAt") or "")}
                logger.info("'%s' issued a password reset for '%s'", current_user(request), username)
                return RedirectResponse("/admin/users#issued", status_code=303)
            return redirect

        @self.app.get("/admin/keys", response_class=HTMLResponse, tags=["ui"])
        def all_keys(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            rows, error, denied = load(lambda: accounts.keys(all_keys=True), request, "the keys")
            report, report_error = None, None
            if denied is None and error is None:
                report, report_error, _ = load(accounts.key_report, request, "the key report")
            return self.page(request, "admin_keys.html", http_status=403 if denied else 200,
                             current="/admin", tab="keys", keys=rows, keys_error=error, denied=denied,
                             report=report, report_error=report_error, flashes=take_flashes(request))

        @self.app.post("/admin/keys/{key_id}/revoke", tags=["ui"])
        def admin_revoke_key(request: Request, key_id: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("'%s' revoked key %s", current_user(request), key_id)
            return outcome(request, lambda: accounts.revoke_key(key_id), "admin.keys.revoked",
                           "/admin/keys", key_id=key_id)[1]

        @self.app.get("/admin/sessions", response_class=HTMLResponse, tags=["ui"])
        def all_sessions(request: Request, user: str = ""):
            """Every session, or one person's (``?user=``, a link an incident channel can use)."""
            if (refusal := login_required(request)) is not None:
                return refusal
            rows, error, denied = load(lambda: accounts.sessions(all_sessions=True), request,
                                       "the sessions")
            if rows is not None and user:
                rows = [row for row in rows if row["username"] == user]
            return self.page(request, "admin_sessions.html", http_status=403 if denied else 200,
                             current="/admin", tab="sessions", sessions=rows, sessions_error=error,
                             denied=denied, whose=user, flashes=take_flashes(request))

        @self.app.post("/admin/sessions/{session_id}/end", tags=["ui"])
        def admin_end_session(request: Request, session_id: str, current: str = Form("")):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                accounts.end_session(session_id)
            except ServiceError as exc:
                flash(request, self.t("admin.people.failed", detail=_said(exc)), "danger")
                return RedirectResponse("/admin/sessions", status_code=303)
            logger.info("'%s' ended a session", current_user(request))
            if current == "yes":
                # The administrator's own session, from this browser: that is signing out.
                request.session.clear()
                return RedirectResponse("/", status_code=303)
            flash(request, self.t("admin.sessions.ended"), "success")
            return RedirectResponse("/admin/sessions", status_code=303)


def _said(exc: ServiceError) -> str:
    """The engine's refusal, with its code when the message does not already carry it."""
    text = str(exc)
    return text + (f" ({exc.code})" if exc.code and exc.code not in text else "")
