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
from routes.base import Routes

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
            page = _safe(
                lambda: services.queries.find(search=search, state=state, sort=sort,
                                              offset=offset, limit=page_size),
                None)
            return self.page(request, "queries.html", current="/queries",
                             page=page, search=search, state_filter=state,
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
                                 current="/queries", what="query", identifier=name,
                                 back_href="/queries", back_label="Back to queries",
                                 detail=str(exc))
            return self.page(request, "query_detail.html", current="/queries",
                             query=query, siblings=siblings)

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
                                 current="/queries", what=f"{action} '{name}'",
                                 detail=str(exc), code=exc.code or "",
                                 back_href=f"/queries/{name}", back_label="Back to the query")
            # Drop removes the thing this page was about, so it returns to the
            # list; the others come back here. Redirect-after-POST either way, so
            # a refresh does not repeat the action.
            return RedirectResponse("/queries" if action == "drop" else f"/queries/{name}",
                                    status_code=303)

        # --------------------------------------------------------- workbench
        @self.app.get("/workbench", response_class=HTMLResponse, tags=["ui"])
        def workbench(request: Request):
            return self.page(request, "workbench.html", current="/workbench",
                             result=None, sql="", params="")

        @self.app.post("/workbench", response_class=HTMLResponse, tags=["ui"])
        def run(request: Request, sql: str = Form(...), params: str = Form("")):
            # A read, but it reaches the engine as this deployment's principal, so it is
            # gated too. An unauthenticated visitor should not be able to use the console
            # as a free query endpoint against data they cannot otherwise reach.
            if (refusal := login_required(request)) is not None:
                return refusal
            values = [_typed(part) for part in params.split(",") if part.strip()]
            try:
                result = services.adhoc.run(sql, values or None)
            except ServiceError as exc:
                return self.page(request, "workbench.html", http_status=400,
                                 current="/workbench", result=None, sql=sql,
                                 params=params, error=str(exc), code=exc.code or "")
            return self.page(request, "workbench.html", current="/workbench",
                             result=result, sql=sql, params=params)

        @self.app.post("/queries", tags=["ui"])
        def register_query(request: Request, name: str = Form(...), sql: str = Form(...),
                           keys: str = Form("0")):
            if (refusal := login_required(request)) is not None:
                return refusal
            logger.info("%s registered '%s'", current_user(request), name)
            ordinals = [int(part.strip()) for part in keys.split(",") if part.strip()]
            try:
                services.queries.register(name, sql, ordinals)
            except ServiceError as exc:
                return self.page(request, "refused.html", http_status=400,
                                 current="/workbench", what=f"register '{name}'",
                                 detail=str(exc), code=exc.code or "",
                                 back_href="/workbench", back_label="Back to the workbench")
            return RedirectResponse(f"/queries/{name}", status_code=303)
