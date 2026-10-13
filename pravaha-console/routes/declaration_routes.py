"""
Pravaha console — managing the declaration files: streams, named connections, lookups and sinks (ADR-064).
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

    /catalog/streams/new                  the New stream form: schema builder, event time, source
    /catalog/streams/{name}/edit          the same form, from the stream's file; or the file itself
    /catalog/connectors                   the plugins this node can load, and its named connections
    /catalog/connectors/new               the New connection form
    /catalog/connectors/{name}/edit       the same form, from the connection's file
    /catalog/connectors/{name}/test       a connection tested through its plugin (POST)
    /catalog/lookups/new, /catalog/sinks/new, /catalog/{lookups|sinks}/{name}/edit
                                          a lookup's or sink's file, edited as YAML
    /catalog/{kind}/{name}/{delete|enable|disable}
                                          a dry run first (what uses it), then the change (POST)

The files are the single source of truth: every Save writes ``config/<kind>/<name>.yaml`` through
the engine, which writes the file a person would and applies it at once. Validate is the engine's
dry run -- the exact file it would write, whether it accepts it, and what uses the object -- and
writes nothing. Every refusal and every permission is the engine's; the console hides the controls
a person the engine's policy would refuse cannot use, and the engine checks again regardless.
"""
from __future__ import annotations

import logging
from typing import Any
from urllib.parse import quote

from fastapi import Request
from fastapi.responses import HTMLResponse, RedirectResponse

from core.declarations import TYPES, Column, ConnectionForm, DeclarationService, StreamForm
from core.services import ServiceError
from routes.auth_routes import current_user, flash, login_required, take_flashes
from routes.base import Routes, failure, session_roles

logger = logging.getLogger(__name__)

#: The kinds a confirm page acts on, and the actions each allows.
_ACTIONS = {"streams": ("delete", "enable", "disable"), "connectors": ("delete", "enable", "disable"),
            "lookups": ("delete",), "sinks": ("delete",)}


def _refusal(exc: ServiceError) -> dict:
    return {"valid": False, "code": exc.code or "", "message": str(exc), "dependents": [], "status": exc.status}


def _list_of(form: Any, action: str, prefix: str) -> int | None:
    """The row a ``remove_<prefix>-<n>`` action names, or None."""
    if action.startswith(f"remove_{prefix}-"):
        try:
            return int(action.rsplit("-", 1)[1])
        except ValueError:
            return None
    return None


class DeclarationRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        declarations = DeclarationService(services.engine)
        self.ctx["declarations"] = declarations

        def admin(request: Request) -> bool:
            return "admin" in session_roles(request)

        def manageable(request: Request) -> tuple[bool, Any]:
            """Whether to offer New, and which names to offer a change on: the admin role offers every
            control; otherwise the streams the engine's policy lets this person administer. An engine
            that did not say keeps the change controls -- it decides on the click, as always."""
            if admin(request):
                return True, lambda name: True
            try:
                permissions = services.admin.permissions()
            except ServiceError:
                return False, lambda name: True
            names = {s.get("name") for s in permissions.get("streams") or []
                     if isinstance(s, dict) and (s.get("administer") or {}).get("allowed") is not False}
            return False, lambda name: name in names

        def may_manage(request: Request, name: str | None = None) -> bool:
            create, change = manageable(request)
            return create if name is None else change(name)

        self.ctx["manageable"] = manageable

        def plugins() -> tuple[list[dict], Any]:
            try:
                return services.engine.plugins(), None
            except Exception as exc:  # noqa: BLE001 -- shown as the page's partial state
                return [], failure(ServiceError(str(exc), 503), what="the plugins")

        def connections(request: Request) -> tuple[list[dict], Any]:
            try:
                return declarations.connectors(), None
            except ServiceError as exc:
                return [], failure(exc, request, "the named connections")

        # ------------------------------------------------------------------ streams

        def stream_page(request: Request, form: StreamForm, *, mode: str, outcome: dict | None = None,
                        dependents: list | None = None, http_status: int = 200):
            if mode == "edit" and not form.yaml_text:
                try:
                    form.yaml_text = declarations.file("streams", form.name).get("yaml") or ""
                except ServiceError:
                    pass  # the refusal is the page's outcome already, or the next Save's
            plugin_rows, plugins_error = plugins()
            connection_rows, _ = connections(request)
            return self.page(request, "stream_form.html", http_status=http_status, current="/catalog", mode=mode,
                             form=form, outcome=outcome, dependents=dependents or [], types=TYPES,
                             plugins=[p for p in plugin_rows if "source" in (p.get("kinds") or [])],
                             plugins_error=plugins_error, connections=connection_rows,
                             secret_keys=form.secret_problems(), flashes=take_flashes(request),
                             allowed=may_manage(request, form.name if mode == "edit" else None))

        def edit_rows(form: StreamForm, action: str) -> None:
            if action == "add_column":
                form.columns.append(Column())
            elif action == "add_option":
                form.options.append(("", ""))
            elif (row := _list_of(form, action, "column")) is not None and 0 <= row < len(form.columns):
                del form.columns[row]
                form.columns = form.columns or [Column()]
            elif (row := _list_of(form, action, "option")) is not None and 0 <= row < len(form.options):
                del form.options[row]

        async def stream_post(request: Request, name: str | None):
            form_data = await request.form()
            form = StreamForm.from_form(form_data)
            if name is not None:
                form.name = name
            action = str(form_data.get("action") or "validate")
            mode = "edit" if name is not None else "create"
            if action in ("add_column", "add_option") or action.startswith("remove_"):
                edit_rows(form, action)
                return stream_page(request, form, mode=mode)
            as_yaml = action in ("validate_yaml", "save_yaml")
            body = {"yaml": form.yaml_text} if as_yaml else form.body()
            dry_run = action in ("validate", "validate_yaml")
            try:
                outcome = declarations.write("streams", form.name, body, update=mode == "edit", dry_run=dry_run)
            except ServiceError as exc:
                return stream_page(request, form, mode=mode, outcome=_refusal(exc),
                                   http_status=exc.status if 400 <= exc.status < 500 else 503)
            if dry_run or not outcome.get("applied"):
                return stream_page(request, form, mode=mode, outcome=outcome,
                                   dependents=outcome.get("dependents") or [])
            logger.info("'%s' %s stream %s through the console", current_user(request),
                        "updated" if mode == "edit" else "created", form.name)
            flash(request, self.t("decl.stream.saved", name=form.name, path=outcome.get("path") or ""), "success")
            return RedirectResponse(f"/catalog/streams/{quote(form.name, safe='')}", status_code=303)

        @self.app.get("/catalog/streams/new", response_class=HTMLResponse, tags=["ui"])
        def new_stream(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return stream_page(request, StreamForm(), mode="create")

        @self.app.post("/catalog/streams/new", response_class=HTMLResponse, tags=["ui"])
        async def create_stream(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return await stream_post(request, None)

        @self.app.get("/catalog/streams/{name}/edit", response_class=HTMLResponse, tags=["ui"])
        def edit_stream(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                held = declarations.file("streams", name)
            except ServiceError as exc:
                return stream_page(request, StreamForm(name=name), mode="edit", outcome=_refusal(exc),
                                   http_status=exc.status if 400 <= exc.status < 500 else 503)
            return stream_page(request, StreamForm.from_file(name, held.get("yaml") or ""), mode="edit",
                               dependents=held.get("dependents") or [])

        @self.app.post("/catalog/streams/{name}/edit", response_class=HTMLResponse, tags=["ui"])
        async def update_stream(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            return await stream_post(request, name)

        # ------------------------------------------------------------------ connections

        def connectors_page(request: Request, *, tested: dict | None = None, tested_name: str = "",
                            http_status: int = 200):
            plugin_rows, plugins_error = plugins()
            connection_rows, connections_error = connections(request)
            return self.page(request, "connectors.html", http_status=http_status, current="/catalog/connectors",
                             plugins=plugin_rows, plugins_error=plugins_error, connections=connection_rows,
                             connections_error=connections_error, tested=tested, tested_name=tested_name,
                             may_create=may_manage(request), flashes=take_flashes(request))

        def connection_page(request: Request, form: ConnectionForm, *, mode: str, outcome: dict | None = None,
                            dependents: list | None = None, http_status: int = 200):
            if mode == "edit" and not form.yaml_text:
                try:
                    form.yaml_text = declarations.file("connectors", form.name).get("yaml") or ""
                except ServiceError:
                    pass  # the refusal is the page's outcome already, or the next Save's
            plugin_rows, plugins_error = plugins()
            chosen = next((p for p in plugin_rows if p.get("name") == form.plugin), None)
            return self.page(request, "connector_form.html", http_status=http_status, current="/catalog/connectors",
                             mode=mode, form=form, outcome=outcome, dependents=dependents or [],
                             plugins=[p for p in plugin_rows if p.get("loaded")], plugins_error=plugins_error,
                             settings=(chosen or {}).get("settings") or [], secret_keys=form.secret_problems(),
                             flashes=take_flashes(request), allowed=may_manage(request))

        async def connection_post(request: Request, name: str | None):
            form_data = await request.form()
            form = ConnectionForm.from_form(form_data)
            if name is not None:
                form.name = name
            action = str(form_data.get("action") or "validate")
            mode = "edit" if name is not None else "create"
            if action == "add_option":
                form.options.append(("", ""))
                return connection_page(request, form, mode=mode)
            if (row := _list_of(form, action, "option")) is not None:
                if 0 <= row < len(form.options):
                    del form.options[row]
                form.options = form.options or [("", "")]
                return connection_page(request, form, mode=mode)
            if action == "pick_plugin":
                return connection_page(request, form, mode=mode)
            as_yaml = action in ("validate_yaml", "save_yaml")
            body = {"yaml": form.yaml_text} if as_yaml else form.body()
            dry_run = action in ("validate", "validate_yaml")
            try:
                outcome = declarations.write("connectors", form.name, body, update=mode == "edit", dry_run=dry_run)
            except ServiceError as exc:
                return connection_page(request, form, mode=mode, outcome=_refusal(exc),
                                       http_status=exc.status if 400 <= exc.status < 500 else 503)
            if dry_run or not outcome.get("applied"):
                return connection_page(request, form, mode=mode, outcome=outcome,
                                       dependents=outcome.get("dependents") or [])
            logger.info("'%s' %s connection %s through the console", current_user(request),
                        "updated" if mode == "edit" else "created", form.name)
            flash(request, self.t("decl.connection.saved", name=form.name, path=outcome.get("path") or ""), "success")
            return RedirectResponse("/catalog/connectors", status_code=303)

        @self.app.get("/catalog/connectors", response_class=HTMLResponse, tags=["ui"])
        def connectors(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return connectors_page(request)

        @self.app.get("/catalog/connectors/new", response_class=HTMLResponse, tags=["ui"])
        def new_connection(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return connection_page(request, ConnectionForm(), mode="create")

        @self.app.post("/catalog/connectors/new", response_class=HTMLResponse, tags=["ui"])
        async def create_connection(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            return await connection_post(request, None)

        @self.app.get("/catalog/connectors/{name}/edit", response_class=HTMLResponse, tags=["ui"])
        def edit_connection(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                held = declarations.file("connectors", name)
            except ServiceError as exc:
                return connection_page(request, ConnectionForm(name=name), mode="edit", outcome=_refusal(exc),
                                       http_status=exc.status if 400 <= exc.status < 500 else 503)
            return connection_page(request, ConnectionForm.from_file(name, held.get("yaml") or ""), mode="edit",
                                   dependents=held.get("dependents") or [])

        @self.app.post("/catalog/connectors/{name}/edit", response_class=HTMLResponse, tags=["ui"])
        async def update_connection(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            return await connection_post(request, name)

        @self.app.post("/catalog/connectors/{name}/test", response_class=HTMLResponse, tags=["ui"])
        async def test_connection(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("'%s' tested connection %s through the console", current_user(request), name)
            try:
                tested = declarations.test(name)
            except ServiceError as exc:
                tested = {"ok": False, "state": "REFUSED", "detail": str(exc), "code": exc.code or "",
                          "latencyMillis": None}
            return connectors_page(request, tested=tested, tested_name=name)

        # ------------------------------------------------------------------ lookups and sinks, as YAML

        def yaml_page(request: Request, kind: str, name: str, text: str, *, mode: str,
                      outcome: dict | None = None, dependents: list | None = None, http_status: int = 200):
            return self.page(request, "declaration_yaml.html", http_status=http_status,
                             current="/catalog", kind=kind, name=name, text=text, mode=mode, outcome=outcome,
                             dependents=dependents or [], flashes=take_flashes(request),
                             allowed=may_manage(request))

        async def yaml_post(request: Request, kind: str, name: str | None):
            form_data = await request.form()
            chosen = (name if name is not None else str(form_data.get("name") or "").strip())
            text = str(form_data.get("yaml") or "")
            action = str(form_data.get("action") or "validate")
            mode = "edit" if name is not None else "create"
            dry_run = action != "save"
            try:
                outcome = declarations.write(kind, chosen, {"yaml": text}, update=mode == "edit", dry_run=dry_run)
            except ServiceError as exc:
                return yaml_page(request, kind, chosen, text, mode=mode, outcome=_refusal(exc),
                                 http_status=exc.status if 400 <= exc.status < 500 else 503)
            if dry_run or not outcome.get("applied"):
                return yaml_page(request, kind, chosen, text, mode=mode, outcome=outcome,
                                 dependents=outcome.get("dependents") or [])
            flash(request, self.t("decl.yaml.saved", name=chosen, path=outcome.get("path") or ""), "success")
            return RedirectResponse("/catalog?tab=sinks" if kind == "sinks" else "/catalog", status_code=303)

        for kind in ("lookups", "sinks"):
            def routes_for(kind: str = kind) -> None:
                @self.app.get(f"/catalog/{kind}/new", response_class=HTMLResponse, tags=["ui"],
                              name=f"new_{kind}")
                def new_binding(request: Request):
                    if (refusal := login_required(request)) is not None:
                        return refusal
                    return yaml_page(request, kind, "", "plugin: \noptions:\n  \n", mode="create")

                @self.app.post(f"/catalog/{kind}/new", response_class=HTMLResponse, tags=["ui"],
                               name=f"create_{kind}")
                async def create_binding(request: Request):
                    if (refusal := login_required(request)) is not None:
                        return refusal
                    return await yaml_post(request, kind, None)

                @self.app.get(f"/catalog/{kind}/{{name}}/edit", response_class=HTMLResponse, tags=["ui"],
                              name=f"edit_{kind}")
                def edit_binding(request: Request, name: str):
                    if (refusal := login_required(request)) is not None:
                        return refusal
                    try:
                        held = declarations.file(kind, name)
                    except ServiceError as exc:
                        return yaml_page(request, kind, name, "", mode="edit", outcome=_refusal(exc),
                                         http_status=exc.status if 400 <= exc.status < 500 else 503)
                    return yaml_page(request, kind, name, held.get("yaml") or "", mode="edit",
                                     dependents=held.get("dependents") or [])

                @self.app.post(f"/catalog/{kind}/{{name}}/edit", response_class=HTMLResponse, tags=["ui"],
                               name=f"update_{kind}")
                async def update_binding(request: Request, name: str):
                    if (refusal := login_required(request)) is not None:
                        return refusal
                    return await yaml_post(request, kind, name)

            routes_for()

        # ------------------------------------------------------------------ delete, enable, disable

        def back_to(kind: str, name: str, action: str) -> str:
            if kind == "streams":
                return "/catalog" if action == "delete" else f"/catalog/streams/{quote(name, safe='')}"
            if kind == "connectors":
                return "/catalog/connectors"
            return "/catalog?tab=sinks" if kind == "sinks" else "/catalog"

        @self.app.get("/catalog/{kind}/{name}/{action}", response_class=HTMLResponse, tags=["ui"])
        def confirm(request: Request, kind: str, name: str, action: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            if action not in _ACTIONS.get(kind, ()):
                return self.page(request, "not_found.html", http_status=404, current="/catalog",
                                 what=self.t("decl.confirm.what"), identifier=f"{kind}/{name}/{action}",
                                 back_href="/catalog", back_label=self.t("not_found.back.catalog"), detail="")
            try:
                outcome = (declarations.delete(kind, name, dry_run=True) if action == "delete"
                           else declarations.switch(kind, name, action == "enable", dry_run=True))
            except ServiceError as exc:
                outcome = _refusal(exc)
            return self.page(request, "declaration_confirm.html", current="/catalog", kind=kind, name=name,
                             action=action, outcome=outcome, back_href=back_to(kind, name, "back"),
                             flashes=take_flashes(request),
                             allowed=may_manage(request, name if kind == "streams" else None))

        @self.app.post("/catalog/{kind}/{name}/{action}", tags=["ui"])
        async def act(request: Request, kind: str, name: str, action: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            if action not in _ACTIONS.get(kind, ()):
                return RedirectResponse("/catalog", status_code=303)
            logger.info("'%s' asked the engine to %s %s %s", current_user(request), action, kind, name)
            try:
                if action == "delete":
                    declarations.delete(kind, name, dry_run=False)
                else:
                    declarations.switch(kind, name, action == "enable", dry_run=False)
            except ServiceError as exc:
                flash(request, self.t("decl.confirm.refused", detail=str(exc) + (
                    f" ({exc.code})" if exc.code and exc.code not in str(exc) else "")), "danger")
                return RedirectResponse(f"/catalog/{kind}/{quote(name, safe='')}/{action}", status_code=303)
            flash(request, self.t(f"decl.confirm.done_{action}", name=name), "success")
            return RedirectResponse(back_to(kind, name, action), status_code=303)


__all__ = ["DeclarationRoutes"]
