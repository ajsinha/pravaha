"""
Pravaha console — the administrative screens.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    /admin                  the admin persona's landing: Access
    /admin/access           what the engine's policy lets this console's identity do (read-only)
    /admin/audit            the audit trail: filterable, paged, every filter in the URL
    /admin/tenants          each tenant's use against its admission quotas, and its refusals

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

from fastapi import Request
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse

from core.admin import AUDIT_FILTERS
from core.services import ServiceError
from routes.auth_routes import login_required
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
