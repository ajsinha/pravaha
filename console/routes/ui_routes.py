"""
Pravaha console — the operator interface.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The UI holds no engine logic: it renders what the services computed, with their
reasoning. Every asset is vendored, so it renders air-gapped.

Each screen is rendered by the SERVER first and then kept current by its module.
Two reasons, both real. A page that is empty until a fetch returns looks broken
for the half-second before it isn't, and this one is opened by somebody who
already suspects something is wrong. And where a proxy eats `text/event-stream`
— which is exactly the deployment nobody tests — the page still works, just
without the live part.
"""
from __future__ import annotations

import logging

from fastapi import Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse

from core.services import ServiceError
from routes.auth_routes import current_user, login_required
from routes.base import Routes, failure

logger = logging.getLogger(__name__)


def _typed(value: str):
    """A form field is a string. A parameter is a value.

    Coerced here rather than left to the engine, because sending "40" where the
    column is an integer compares a string to a number and silently matches
    nothing — which looks like an empty result rather than like a form filled in
    wrongly (ADR-032).
    """
    text = str(value).strip()
    negative = text.startswith("-")
    digits = text[1:] if negative else text
    if digits.isdigit():
        return int(text)
    if digits.replace(".", "", 1).isdigit():
        return float(text)
    return value


class UIRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        page_size = self.ctx["config"].get_int("ui.page_size", 25)

        def _safe(fn, fallback):
            """The engine being unreachable is a state the page renders.

            Not an exception it raises: the bar already says the engine is down,
            and a 500 here would replace that with a stack trace that says less.
            """
            try:
                return fn()
            except ServiceError as exc:
                logger.warning("rendering without engine data: %s", exc)
                return fallback

        def _attempt(fn, fallback, what: str = ""):
            """``_safe``, keeping what failed: a Failure the page renders as its error or partial
            state (design 23.12), with the correlation id that is also on the log line."""
            try:
                return fn(), None
            except ServiceError as exc:
                return fallback, failure(exc, what=what)

        # ---------------------------------------------------------- overview
        @self.app.get("/overview", response_class=HTMLResponse, tags=["ui"])
        def overview(request: Request):
            """Is it up, what is registered, and how much of it is shared.

            Gated, unlike the landing page and the docs: a registered query's name and
            fingerprint are the engine's own data, reached with the console's one shared
            engine identity, not console-specific metadata safe to hand to anyone who can
            reach the port. Before this gate existed, an anonymous visitor saw exactly what
            an operator sees -- which is the read half of the same defect the login system
            was built to close on the write side (see routes/auth_routes.py's own account
            of why it exists).
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            busiest = _safe(lambda: services.queries.find(limit=8, sort="-rows_in").items, [])
            return self.page(request, "overview.html", current="/overview",
                             queries=busiest)

        # ----------------------------------------------------------- queries
        @self.app.get("/queries", response_class=HTMLResponse, tags=["ui"])
        def queries(request: Request, search: str = "", state: str = "",
                    sort: str = "name", offset: int = 0):
            """The list. Every filter is in the URL, so a view can be shared as it is.

            Gated for the same reason as /overview: this is which queries exist and what
            SQL they run, not console chrome.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            page, page_error = _attempt(
                lambda: services.queries.find(search=search, state=state, sort=sort,
                                              offset=offset, limit=page_size),
                None, "the query list")
            return self.page(request, "queries.html", current="/queries",
                             page=page, page_error=page_error, search=search, state_filter=state,
                             sort=sort, offset=offset, page_size=page_size)

        @self.app.get("/queries/{name}", response_class=HTMLResponse, tags=["ui"])
        def query_detail(request: Request, name: str):
            """The detail page: full SQL, fingerprint, siblings, a live tail.

            Gated -- this is the single most sensitive read the console has. The SQL text
            of a continuous query can itself be confidential (table names, join keys,
            business logic), which is exactly the "caller learning what exists" SX-5 names.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                query = services.queries.get(name)
                siblings = services.queries.siblings(name)
            except ServiceError as exc:
                return self.page(request, "not_found.html", http_status=404,
                                 current="/queries", what=self.t("not_found.what.query"), identifier=name,
                                 back_href="/queries", back_label=self.t("not_found.back.queries"),
                                 detail=str(exc))
            # The engine's description: keys by name, retention, the sink and whether it is still
            # attached. Rendered without it (older engine, HTTP port down) rather than refused.
            detail, detail_error = _attempt(lambda: services.queries.detail(name), None,
                                            "the query's description")
            # Pause, resume and drop are offered only when the engine's policy would allow them;
            # refused, they are disabled with its reason (design 23.16), not left to fail on click.
            refused = services.admin.affordances().administer_refused(name)
            return self.page(request, "query_detail.html", current="/queries",
                             query=query, siblings=siblings, detail=detail, detail_error=detail_error,
                             refused=refused)

        # The lifecycle actions as ordinary form posts. The module intercepts
        # them so the page does not reload, but they work without it: a control
        # that only exists once a script has run is not one an operator can rely
        # on when something on the page has already thrown.
        @self.app.post("/queries/{name}/{action}", tags=["ui"])
        def act(request: Request, name: str, action: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            # Named, and at INFO, because "who dropped it" is the question asked after a
            # query disappears and there was previously nothing that could answer it.
            logger.info("%s requested %s on '%s'", current_user(request), action, name)
            try:
                services.queries.act(name, action)
            except ServiceError as exc:
                return self.page(request, "refused.html", http_status=400,
                                 current="/queries", what=self.t("refused.what.action", action=action, name=name),
                                 detail=str(exc), code=exc.code or "",
                                 back_href=f"/queries/{name}", back_label=self.t("not_found.back.query"))
            # Drop removes the thing this page was about, so it returns to the
            # list; the others come back here. Redirect-after-POST either way, so
            # a refresh does not repeat the action.
            return RedirectResponse("/queries" if action == "drop" else f"/queries/{name}",
                                    status_code=303)

        # ------------------------------------------------ the component gallery
        gallery = self.ctx["config"].get_bool("ui.component_gallery", False)

        @self.app.get("/_components", response_class=HTMLResponse, tags=["ui"])
        def components(request: Request):
            """Every design-system component, and the eight states of design 23.12, on one page.

            The no-build stand-in for Storybook (23.20): the console renders it with its own
            templates, tokens and ``states.js``, so what is reviewed here is what the screens
            use, and the browser tests audit and photograph it like any screen. A development
            aid -- off unless ``ui.component_gallery`` is set, when it is a 404 that does not
            say it exists -- and behind the sign-in when on.
            """
            if not gallery:
                return self.page(request, "not_found.html", http_status=404, current="",
                                 what=self.t("not_found.what.page"), identifier="/_components",
                                 back_href="/", back_label=self.t("not_found.back.start"), detail="")
            if (refusal := login_required(request)) is not None:
                return refusal
            return self.page(request, "components.html", current="/_components")

        # --------------------------------------------------------- workbench
        @self.app.get("/workbench", response_class=HTMLResponse, tags=["ui"])
        def workbench(request: Request, query: str = "", sql: str = "", template: str = "",
                      stream: str = ""):
            """The SQL Workbench: the analyst's landing (design 23.7).

            Gated now, because it renders the catalog -- which streams exist and what is in
            them -- and prefills from a registered query's own SQL. ``?query=`` opens a
            registered query, ``?sql=`` a piece of SQL, ``?template=`` a library template
            written against ``?stream=``.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            from core import authoring

            streams = _safe(services.catalog.streams, [])
            prefill, origin = sql, ""
            if query:
                found = _safe(lambda: services.queries.get(query), None)
                if found is not None:
                    prefill, origin = found.sql, query
            elif template:
                chosen = next((s for s in streams if s.get("name") == stream), None)
                for item in authoring.templates(chosen or (streams[0] if streams else None)):
                    if item["id"] == template:
                        prefill = item["sql"]
            return self.page(request, "workbench.html", current="/workbench",
                             result=None, sql=prefill, params="", origin=origin,
                             streams=streams, sinks=services.catalog.sinks_or_empty(),
                             library=authoring.templates(streams[0] if streams else None),
                             register_refused=services.admin.affordances().register_refused())

        @self.app.post("/workbench", response_class=HTMLResponse, tags=["ui"])
        def run(request: Request, sql: str = Form(...), params: str = Form("")):
            # A read, but it reaches the engine as this deployment's principal, so it is
            # gated too. An unauthenticated visitor should not be able to use the console
            # as a free query endpoint against data they cannot otherwise reach.
            if (refusal := login_required(request)) is not None:
                return refusal
            values = [_typed(part) for part in params.split(",") if part.strip()]
            from core import authoring

            streams = _safe(services.catalog.streams, [])
            library = authoring.templates(streams[0] if streams else None)
            try:
                result = services.adhoc.run(sql, values or None)
            except ServiceError as exc:
                return self.page(request, "workbench.html", http_status=400,
                                 current="/workbench", result=None, sql=sql,
                                 params=params, error=str(exc), code=exc.code or "",
                                 origin="", streams=streams, library=library)
            return self.page(request, "workbench.html", current="/workbench",
                             result=result, sql=sql, params=params, origin="",
                             streams=streams, library=library)

        @self.app.post("/queries", tags=["ui"])
        def register_query(request: Request, name: str = Form(...), sql: str = Form(...),
                           keys: str = Form("0"), sink: str = Form(""), retention: str = Form("")):
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s registered '%s'", current_user(request), name)
            # Keys by ordinal ("0,1") or by name ("user_id, window_end"): a name is mapped
            # against the schema the engine validated, which is what the workbench does too.
            parts = [part.strip() for part in keys.split(",") if part.strip()]
            try:
                if parts and not all(p.lstrip("-").isdigit() for p in parts):
                    ordinals, _fields = services.authoring.key_ordinals(sql, parts)
                else:
                    ordinals = [int(p) for p in parts]
                services.queries.register(name, sql, ordinals, sink=sink or None,
                                          retention=retention or None)
            except ServiceError as exc:
                return self.page(request, "refused.html", http_status=400,
                                 current="/workbench", what=self.t("refused.what.register", name=name),
                                 detail=str(exc), code=exc.code or "",
                                 back_href="/workbench", back_label=self.t("not_found.back.workbench"))
            return RedirectResponse(f"/queries/{name}", status_code=303)
