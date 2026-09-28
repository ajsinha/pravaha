"""
Pravaha console — the catalogue's screens (ADR-059).
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    /catalog?tab=objects        namespaces, owners, descriptions and tags; ?q= searches
    /catalog/objects/{name}     one object: its metadata, the grants on it, what you may do,
                                and (?user=) what someone else may, and why
    /admin/grants               the grants editor: grant, revoke, create a namespace, describe
                                and tag an object
    /admin/policies             row filters and masks (ADR-059 section 4): create, bind to an
                                object or a tag, unbind, drop

Every answer and every refusal is the engine's (``/api/v1/catalog``), called as the signed-in
person. The console filters nothing and allows nothing: a person who may not manage an object
is refused by the engine and the flash says so with its code; a node whose catalogue is off
answers PRV-7030, which every screen here shows as its own state.
"""
from __future__ import annotations

import logging
from urllib.parse import urlencode

from fastapi import Request
from fastapi.responses import HTMLResponse, RedirectResponse

from core.governance import PRIVILEGES, GovernanceService, owner_text, tag_pairs
from core.services import ServiceError
from routes.auth_routes import current_user, flash, login_required, take_flashes
from routes.base import Routes, failure

logger = logging.getLogger(__name__)


def listing(governance: GovernanceService, request: Request, q: str = "", namespace: str = "",
            kind: str = "") -> dict:
    """The objects tab's data: objects and namespaces, an error, or the catalogue being off."""
    answer: dict = {"objects": [], "namespaces": [], "objects_error": None, "catalog_off": False}
    try:
        answer["objects"] = governance.objects(namespace, kind, q)
        answer["namespaces"] = governance.namespaces()
    except ServiceError as exc:
        if GovernanceService.is_off(exc):
            answer["catalog_off"] = True
        else:
            answer["objects_error"] = failure(exc, request, "the catalogue")
    return answer


def _grants_href(name: str) -> str:
    return "/admin/grants" + ("?" + urlencode({"object": name}) if name else "")


def _said(exc: ServiceError) -> str:
    text = str(exc)
    return text + (f" ({exc.code})" if exc.code and exc.code not in text else "")


class CatalogRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        governance = GovernanceService(services.engine)
        self.ctx["governance"] = governance
        helpers = {"owner_text": owner_text, "tag_pairs": tag_pairs}

        @self.app.get("/catalog/objects/{name:path}", response_class=HTMLResponse, tags=["ui"])
        def catalog_object(request: Request, name: str, user: str = ""):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                detail = governance.object(name)
            except ServiceError as exc:
                if GovernanceService.is_off(exc):
                    return self.page(request, "catalog_object.html", http_status=409, current="/catalog",
                                     detail=None, catalog_off=True, why=None, why_error=None,
                                     asked=user, **helpers)
                return self.page(request, "not_found.html", http_status=exc.status if exc.status == 404 else 503,
                                 current="/catalog", what=self.t("governance.object.what"), identifier=name,
                                 back_href="/catalog?tab=objects", back_label=self.t("not_found.back.catalog"),
                                 detail=str(exc))
            why, why_error = None, None
            if user.strip():
                try:
                    why = governance.access(user.strip(), name)
                except ServiceError as exc:
                    why_error = _said(exc)
            return self.page(request, "catalog_object.html", current="/catalog", detail=detail,
                             catalog_off=False, why=why, why_error=why_error, asked=user.strip(),
                             grants_href=_grants_href((detail.get("object") or {}).get("name", name)),
                             **helpers)

        @self.app.get("/admin/grants", response_class=HTMLResponse, tags=["ui"])
        def grants_editor(request: Request, object: str = ""):
            if (refusal := login_required(request)) is not None:
                return refusal
            chosen = object.strip()
            namespaces, grants, detail, error, off = [], [], None, None, False
            try:
                namespaces = governance.namespaces()
                if chosen:
                    detail = governance.object(chosen)
                    grants = detail.get("grants") or []
            except ServiceError as exc:
                if GovernanceService.is_off(exc):
                    off = True
                else:
                    error = failure(exc, request, "the grants")
            return self.page(request, "admin_grants.html", http_status=409 if off else 200,
                             current="/admin", tab="grants", chosen=chosen, detail=detail,
                             grants=grants, namespaces=namespaces, grants_error=error,
                             catalog_off=off, privileges=PRIVILEGES, flashes=take_flashes(request),
                             **helpers)

        def done(request: Request, fn, message: str, back: str, **params):
            try:
                fn()
            except ServiceError as exc:
                flash(request, self.t("governance.failed", detail=_said(exc)), "danger")
                return RedirectResponse(back, status_code=303)
            flash(request, self.t(message, **params), "success")
            return RedirectResponse(back, status_code=303)

        @self.app.post("/admin/grants", tags=["ui"])
        async def grant(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            on = str(form.get("object") or "").strip()
            chosen = [str(p) for p in form.getlist("privileges") if str(p) in PRIVILEGES]
            kind, who = str(form.get("grantee_type") or "ROLE"), str(form.get("grantee") or "").strip()
            logger.info("'%s' asked the engine to grant %s on %s to %s %s",
                        current_user(request), chosen, on, kind, who)
            return done(request, lambda: governance.grant(on, chosen, kind, who), "governance.granted",
                        _grants_href(on), privileges=", ".join(chosen), object=on, grantee=f"{kind} {who}")

        @self.app.post("/admin/grants/revoke", tags=["ui"])
        async def revoke(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            on = str(form.get("object") or "").strip()
            privilege = str(form.get("privilege") or "")
            kind, who = str(form.get("grantee_type") or ""), str(form.get("grantee") or "").strip()
            logger.info("'%s' asked the engine to revoke %s on %s from %s %s",
                        current_user(request), privilege, on, kind, who)
            return done(request, lambda: governance.revoke(on, [privilege], kind, who), "governance.revoked",
                        _grants_href(on), privilege=privilege, object=on, grantee=f"{kind} {who}")

        @self.app.post("/admin/namespaces", tags=["ui"])
        async def create_namespace(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            name = str(form.get("name") or "").strip()
            description = str(form.get("description") or "")
            return done(request, lambda: governance.create_namespace(name, description),
                        "governance.namespace_created", _grants_href(name), name=name)

        # ---------------------------------------------- row filters and masks (ADR-059 s4)

        def _policies_href(name: str = "") -> str:
            return "/admin/policies" + ("?" + urlencode({"object": name}) if name else "")

        @self.app.get("/admin/policies", response_class=HTMLResponse, tags=["ui"])
        def policies_editor(request: Request, object: str = ""):
            if (refusal := login_required(request)) is not None:
                return refusal
            chosen = object.strip()
            listed, error, off = [], None, False
            try:
                listed = governance.policies(chosen)
            except ServiceError as exc:
                if GovernanceService.is_off(exc):
                    off = True
                else:
                    error = failure(exc, request, "the policies")
            return self.page(request, "admin_policies.html", http_status=409 if off else 200,
                             current="/admin", tab="policies", chosen=chosen, policies=listed,
                             policies_error=error, catalog_off=off, flashes=take_flashes(request),
                             **helpers)

        @self.app.post("/admin/policies", tags=["ui"])
        async def create_policy(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            name = str(form.get("name") or "").strip()
            kind = str(form.get("kind") or "ROW_FILTER")
            logger.info("'%s' asked the engine to create the %s %s", current_user(request), kind, name)
            return done(request, lambda: governance.create_policy(
                name, kind, str(form.get("expression") or ""), str(form.get("column") or ""),
                str(form.get("except_roles") or ""), str(form.get("description") or "")),
                "governance.policies.created", _policies_href(), name=name)

        @self.app.post("/admin/policies/bind", tags=["ui"])
        async def bind_policy(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            name = str(form.get("policy") or "").strip()
            on, tag = str(form.get("object") or "").strip(), str(form.get("tag") or "").strip()
            logger.info("'%s' asked the engine to bind %s to %s", current_user(request), name, on or tag)
            return done(request, lambda: governance.bind_policy(name, on, tag), "governance.policies.bound",
                        _policies_href(), name=name, where=on or f"TAG '{tag}'")

        @self.app.post("/admin/policies/unbind", tags=["ui"])
        async def unbind_policy(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            name = str(form.get("policy") or "").strip()
            on, tag = str(form.get("object") or "").strip(), str(form.get("tag") or "").strip()
            logger.info("'%s' asked the engine to unbind %s from %s", current_user(request), name, on or tag)
            return done(request, lambda: governance.unbind_policy(name, on, tag), "governance.policies.unbound",
                        _policies_href(), name=name, where=on or f"TAG '{tag}'")

        @self.app.post("/admin/policies/drop", tags=["ui"])
        async def drop_policy(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            name = str(form.get("policy") or "").strip()
            logger.info("'%s' asked the engine to drop the policy %s", current_user(request), name)
            return done(request, lambda: governance.drop_policy(name), "governance.policies.dropped",
                        _policies_href(), name=name)

        @self.app.post("/admin/grants/describe", tags=["ui"])
        async def describe(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            form = await request.form()
            on = str(form.get("object") or "").strip()
            return done(
                request,
                lambda: governance.change(on, description=str(form.get("description") or ""),
                                          tags=str(form.get("tags") or ""),
                                          unset=str(form.get("unset") or "")),
                "governance.described", _grants_href(on), object=on)


__all__ = ["CatalogRoutes", "listing"]
