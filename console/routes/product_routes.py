"""
Pravaha console — the persona surfaces.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

The screens each persona of design 23.2 lands on, and the JSON they are built on:

    /home                   role-aware landing (analyst, operator, developer)
    /start                  first-run onboarding: a stream, a query, a live view
    /catalog                streams, registered queries, sinks
    /catalog/streams/{n}    one stream's schema
    /views, /views/{n}      the served-view browser, point queries, client snippets
    /views/{n}/live         committed changes as they happen, with their weights
    /operations             "is everything healthy, and if not, where?"

Every screen is rendered by the server first with whatever the engine answered, and made
live by an island (``web/static/app/*.js``). Every one of them names a registered query
or reads the engine's data, so every one is behind the sign-in gate -- only the landing
page, the documentation and the health probes are not (routes/auth_routes.py).
"""
from __future__ import annotations

import json
import logging
from typing import Any

import anyio
from fastapi import Form, Request
from fastapi.responses import (
    HTMLResponse,
    JSONResponse,
    RedirectResponse,
    StreamingResponse,
)

from core import authoring
from core.services import ServiceError, jsonable
from core.snippets import SnippetError, snippets
from routes.auth_routes import current_user, local_path, login_required
from routes.base import ROLES, Routes, role_of

logger = logging.getLogger(__name__)


def _refuse_anonymous(request: Request) -> JSONResponse | None:
    if current_user(request) is None:
        return JSONResponse({"error": "sign in to the console first", "status": 401},
                            status_code=401)
    return None


class ProductRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        config = self.ctx["config"]
        api = self.api

        def safe(fn, fallback):
            try:
                return fn(), None
            except ServiceError as exc:
                logger.info("rendering without engine data: %s", exc)
                return fallback, str(exc)

        def default_role() -> str:
            return config.get("ui.default_role", "operator")

        # ================================================================= pages

        @self.app.get("/home", tags=["ui"])
        def home(request: Request):
            """Where a signed-in person lands: their role's screen, or onboarding on first run.

            First run is decided by the engine, not by the browser: an engine with nothing
            registered is a first run for whoever opens it, and the flow that gets a query
            running is the most useful thing to show them.
            """
            if (refusal := login_required(request)) is not None:
                return refusal
            landing = ROLES[role_of(request, default_role())]["landing"]
            page, _error = safe(lambda: services.queries.find(limit=1), None)
            if page is not None and page.total == 0:
                return RedirectResponse("/start", status_code=303)
            return RedirectResponse(landing, status_code=303)

        @self.app.post("/preferences/role", tags=["ui"])
        def choose_role(request: Request, role: str = Form(...), next: str = Form("/home")):
            if (refusal := login_required(request)) is not None:
                return refusal
            if role in ROLES:
                request.session["role"] = role
            target = local_path(next)
            return RedirectResponse(ROLES.get(role, {}).get("landing", target)
                                    if target == "/home" else target, status_code=303)

        @self.app.get("/start", response_class=HTMLResponse, tags=["ui"])
        def start(request: Request, stream: str = ""):
            if (refusal := login_required(request)) is not None:
                return refusal
            streams, streams_error = safe(services.catalog.streams, [])
            chosen = next((s for s in streams if s.get("name") == stream),
                          streams[0] if streams else None)
            return self.page(request, "start.html", current="/start", streams=streams,
                             streams_error=streams_error, chosen=chosen,
                             templates=authoring.templates(chosen))

        @self.app.get("/catalog", response_class=HTMLResponse, tags=["ui"])
        def catalog(request: Request, tab: str = "streams"):
            if (refusal := login_required(request)) is not None:
                return refusal
            tab = tab if tab in {"streams", "queries", "sinks"} else "streams"
            streams, streams_error = safe(services.catalog.streams, [])
            queries, queries_error = safe(
                lambda: services.queries.find(limit=services.queries.MAX_LIMIT).items, [])
            siblings: dict[str, list[str]] = {}
            for q in queries:
                siblings.setdefault(q.fingerprint, []).append(q.name)
            sinks, sinks_error = (safe(services.catalog.sinks, []) if tab == "sinks" else ([], None))
            return self.page(request, "catalog.html", current="/catalog", tab=tab,
                             streams=streams, streams_error=streams_error, queries=queries,
                             queries_error=queries_error, siblings=siblings,
                             sinks=sinks, sinks_error=sinks_error,
                             engine_http=services.engine.http_url)

        @self.app.get("/catalog/streams/{name}", response_class=HTMLResponse, tags=["ui"])
        def stream_detail(request: Request, name: str):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                stream = services.catalog.stream(name)
            except ServiceError as exc:
                return self.page(request, "not_found.html", http_status=exc.status if exc.status == 404 else 503,
                                 current="/catalog", what="stream", identifier=name,
                                 back_href="/catalog", back_label="Back to the catalog",
                                 detail=str(exc))
            queries, _ = safe(lambda: services.queries.find(limit=services.queries.MAX_LIMIT).items, [])
            # Lineage from the engine -- the streams each query's plan reads -- when it says;
            # a name match in the SQL text only for an engine that does not.
            reads, _ = safe(services.queries.reads, None)
            if reads is not None:
                wanted = stream["name"].lower()
                readers = [q for q in queries
                           if wanted in {r.lower() for r in reads.get(q.name, [])}]
                lineage = "engine"
            else:
                readers = [q for q in queries if _names(q.sql, stream["name"])]
                lineage = "name"
            return self.page(request, "stream_detail.html", current="/catalog", stream=stream,
                             readers=readers, lineage=lineage, templates=authoring.templates(stream))

        @self.app.get("/views", response_class=HTMLResponse, tags=["ui"])
        def views(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            queries, error = safe(
                lambda: services.queries.find(limit=services.queries.MAX_LIMIT).items, None)
            return self.page(request, "views.html", current="/views", queries=queries,
                             queries_error=error)

        @self.app.get("/views/{name}", response_class=HTMLResponse, tags=["ui"])
        def view_detail(request: Request, name: str, key: str = "", value: str = "",
                        client: str = "java"):
            """The served-view browser. A point query works as a plain GET form, with the
            answer rendered here, so it needs no JavaScript at all."""
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                query = services.queries.get(name)
            except ServiceError as exc:
                return self.page(request, "not_found.html", http_status=404 if exc.status == 404 else 503,
                                 current="/views", what="view", identifier=name,
                                 back_href="/views", back_label="Back to views", detail=str(exc))
            # The engine's own description of the view: schema, key, retention, sink.
            view, schema_error = safe(lambda: services.views.describe(name), {})
            schema = list((view or {}).get("schema") or [])
            keys = [k.get("name") for k in (view or {}).get("keyColumns") or [] if k.get("name")]
            result, lookup_error = (None, None)
            if key:
                result, lookup_error = safe(lambda: services.views.lookup(name, {key: value}), None)
            try:
                code = snippets(name, engine_url=services.engine.url,
                                pgwire=config.get("engine.pgwire", "localhost:5432"),
                                key_column=key or (keys[0] if keys else schema[0]["name"] if schema else None),
                                key_value=value if key else None)
                snippet_error = None
            except SnippetError as exc:
                code, snippet_error = {}, str(exc)
            siblings, _ = safe(lambda: services.queries.siblings(name), [])
            return self.page(request, "view_detail.html", current="/views", query=query,
                             schema=schema, schema_error=schema_error, view=view, key=key, key_value=value,
                             result=result, lookup_error=lookup_error, snippets=code,
                             snippet_error=snippet_error, client=client, siblings=siblings)

        @self.app.get("/views/{name}/live", response_class=HTMLResponse, tags=["ui"])
        def view_live(request: Request, name: str, filter: str = ""):
            if (refusal := login_required(request)) is not None:
                return refusal
            try:
                query = services.queries.get(name)
            except ServiceError as exc:
                return self.page(request, "not_found.html", http_status=404 if exc.status == 404 else 503,
                                 current="/views", what="view", identifier=name,
                                 back_href="/views", back_label="Back to views", detail=str(exc))
            schema, _ = safe(lambda: services.views.schema(name), [])
            return self.page(request, "live.html", current="/views", query=query, schema=schema,
                             tail_buffer=config.get_int("ui.tail_buffer", 256))

        @self.app.get("/operations", response_class=HTMLResponse, tags=["ui"])
        def operations(request: Request):
            if (refusal := login_required(request)) is not None:
                return refusal
            snapshot = services.ops.snapshot()
            return self.page(request, "operations.html", current="/operations",
                             snapshot=snapshot, snapshot_data=jsonable(snapshot))

        # ================================================================== JSON

        @self.app.get(f"{api}/me", tags=["api"])
        def me(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            role = role_of(request, default_role())
            return JSONResponse({"user": current_user(request), "role": role,
                                 "landing": ROLES[role]["landing"], "roles": ROLES})

        @self.app.get(f"{api}/catalog/streams", tags=["api"])
        def api_streams(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"items": services.catalog.streams()}, request=request)

        @self.app.post(f"{api}/catalog/streams", tags=["api"], status_code=201)
        async def api_declare_stream(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            body = await _body(request)
            logger.info("%s declared stream '%s'", current_user(request), body.get("name"))
            return self.json_guard(lambda: services.catalog.declare(
                str(body.get("name", "")), str(body.get("schema", "")),
                event_time=str(body.get("event_time") or body.get("eventTime") or "") or None,
                out_of_orderness=str(body.get("out_of_orderness") or body.get("outOfOrderness") or "")
                or None), request=request)

        @self.app.get(f"{api}/catalog/streams/{{name}}", tags=["api"])
        def api_stream(request: Request, name: str):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(lambda: services.catalog.stream(name), request=request)

        @self.app.get(f"{api}/catalog/sinks", tags=["api"])
        def api_sinks(request: Request):
            """The sink bindings the engine lists for this console's identity -- never options."""
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"items": services.catalog.sinks()}, request=request)

        @self.app.get(f"{api}/views/{{name}}", tags=["api"])
        def api_view(request: Request, name: str):
            """A view described by the engine: schema, key, retention, sink, fingerprint."""
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(lambda: services.views.describe(name), request=request)

        @self.app.get(f"{api}/catalog/completions", tags=["api"])
        def api_completions(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(services.catalog.completions, request=request)

        @self.app.get(f"{api}/catalog/templates", tags=["api"])
        def api_templates(request: Request, stream: str = ""):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal

            def build():
                chosen = None
                if stream:
                    chosen = services.catalog.stream(stream)
                return {"items": authoring.templates(chosen)}
            return self.json_guard(build, request=request)

        @self.app.post(f"{api}/sql/validate", tags=["api"])
        async def api_validate(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            body = await _body(request)
            return self.json_guard(lambda: services.authoring.validate(str(body.get("sql", ""))),
                                   request=request)

        @self.app.post(f"{api}/sql/explain", tags=["api"])
        async def api_explain(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            body = await _body(request)
            sql = str(body.get("sql", ""))
            query = str(body.get("query") or "").strip()

            def explain():
                answer = services.authoring.explain(sql, str(body.get("level") or "physical"))
                if query:
                    # The registered query's measured totals, attached only while the SQL being
                    # explained is still that query's own: under an edited query they would be
                    # numbers about a different plan.
                    try:
                        registered = services.queries.get(query)
                        if registered.sql.strip() == sql.strip():
                            answer["query_metrics"] = services.authoring.plan(query).get("query_metrics")
                    except ServiceError:
                        pass
                return answer
            return self.json_guard(explain, request=request)

        @self.app.get(f"{api}/views/{{name}}/schema", tags=["api"])
        def api_view_schema(request: Request, name: str):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"view": name, "fields": services.views.schema(name)},
                                   request=request)

        @self.app.post(f"{api}/views/{{name}}/lookup", tags=["api"])
        async def api_view_lookup(request: Request, name: str):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            body = await _body(request)
            filters = body.get("filters") or {}
            if not isinstance(filters, dict):
                return JSONResponse({"error": "filters must be an object of column: value",
                                     "status": 400}, status_code=400)
            return self.json_guard(lambda: services.views.lookup(name, filters), request=request)

        @self.app.get(f"{api}/views/{{name}}/snippets", tags=["api"])
        def api_view_snippets(request: Request, name: str, key: str = "", value: str = ""):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal

            def build():
                try:
                    return {"view": name, "key": key or None, "value": value or None,
                            "snippets": snippets(
                                name, engine_url=services.engine.url,
                                pgwire=config.get("engine.pgwire", "localhost:5432"),
                                key_column=key or None, key_value=value if key else None)}
                except SnippetError as exc:
                    raise ServiceError(str(exc), status=400) from exc
            return self.json_guard(build, request=request)

        @self.app.get(f"{api}/ops/snapshot", tags=["api"])
        def api_ops_snapshot(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return JSONResponse(jsonable(services.ops.snapshot()))

        @self.app.get(f"{api}/ops/series", tags=["api"])
        def api_ops_series(request: Request, metric: str = "rows_in"):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return self.json_guard(lambda: jsonable(services.ops.series(metric)), request=request)

        @self.app.get(f"{api}/ops/stream", tags=["api"])
        def api_ops_stream(request: Request):
            """The dashboard at 1 Hz over server-sent events (design 23.11).

            The snapshot behind it is cached for a second, so ten operators watching cost
            the engine one scrape a second. The browser closes the stream on a hidden tab.
            """
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal

            async def events():
                while True:
                    if await request.is_disconnected():
                        return
                    snapshot = await anyio.to_thread.run_sync(services.ops.snapshot)
                    yield ("event: snapshot\ndata: "
                           + json.dumps(jsonable(snapshot), default=str) + "\n\n")
                    await anyio.sleep(1.0)

            return StreamingResponse(events(), media_type="text/event-stream",
                                     headers={"Cache-Control": "no-cache",
                                              "X-Accel-Buffering": "no"})

        @self.app.get(f"{api}/palette", tags=["api"])
        def api_palette(request: Request):
            """Everything the command palette can jump to or do.

            Signed out, it offers only pages that are public. Signed in, every query, view
            and stream, and the lifecycle actions each query's state allows -- an action a
            query cannot take is absent rather than offered and refused.
            """
            items: list[dict[str, Any]] = [
                {"kind": "page", "title": "Landing", "href": "/", "hint": "what Pravaha is"},
                {"kind": "page", "title": "Help", "href": "/help", "hint": "documentation"},
                {"kind": "page", "title": "Tutorials", "href": "/tutorials", "hint": "worked walkthroughs"},
            ]
            if current_user(request) is None:
                items.append({"kind": "page", "title": "Sign in", "href": "/login", "hint": ""})
                return JSONResponse({"signed_in": False, "items": items})
            role = role_of(request, default_role())
            items += [
                {"kind": "page", "title": "SQL Workbench", "href": "/workbench", "hint": "write, validate, explain, register"},
                {"kind": "page", "title": "Catalog", "href": "/catalog", "hint": "streams, queries, sinks"},
                {"kind": "page", "title": "Views", "href": "/views", "hint": "point queries and client code"},
                {"kind": "page", "title": "Operations", "href": "/operations", "hint": "is everything healthy?"},
                {"kind": "page", "title": "Queries", "href": "/queries", "hint": "the full list, filterable"},
                {"kind": "page", "title": "Get started", "href": "/start", "hint": "first-run onboarding"},
                {"kind": "action", "title": "New query in the workbench", "href": "/workbench?new=1", "hint": "blank tab"},
            ]
            for key, meta in ROLES.items():
                if key != role:
                    items.append({"kind": "role", "title": f"Switch to the {meta['label'].lower()} view",
                                  "role": key, "hint": meta["blurb"]})
            queries, _ = safe(lambda: services.queries.find(limit=services.queries.MAX_LIMIT).items, [])
            for q in queries:
                items.append({"kind": "query", "title": q.name, "href": f"/queries/{q.name}",
                              "hint": q.state, "state": q.state})
                items.append({"kind": "view", "title": f"{q.name} — browse view",
                              "href": f"/views/{q.name}", "hint": "point query, snippets"})
                items.append({"kind": "view", "title": f"{q.name} — watch live",
                              "href": f"/views/{q.name}/live", "hint": "committed changes"})
                items.append({"kind": "action", "title": f"Open {q.name} in the workbench",
                              "href": f"/workbench?query={q.name}", "hint": "prefilled"})
                if q.state == "RUNNING":
                    items.append({"kind": "lifecycle", "title": f"Pause {q.name}", "query": q.name,
                                  "action": "pause", "hint": "keeps the state"})
                elif q.state in {"PAUSED"}:
                    items.append({"kind": "lifecycle", "title": f"Resume {q.name}", "query": q.name,
                                  "action": "resume", "hint": "from its frontier"})
                items.append({"kind": "lifecycle", "title": f"Drop {q.name}…", "query": q.name,
                              "action": "drop", "hint": "asks for the name", "href": f"/queries/{q.name}#drop"})
            for s in services.catalog.streams_or_empty():
                items.append({"kind": "stream", "title": s.get("name"),
                              "href": f"/catalog/streams/{s.get('name')}",
                              "hint": f"stream · {len(s.get('fields') or [])} columns"})
            return JSONResponse({"signed_in": True, "role": role, "items": items})


async def _body(request: Request) -> dict:
    try:
        body = await request.json()
    except (ValueError, UnicodeDecodeError):
        return {}
    return body if isinstance(body, dict) else {}


def _names(sql: str, stream: str) -> bool:
    """Whether a query's SQL mentions a stream by name, as a whole word."""
    import re

    return re.search(r"(?<![A-Za-z0-9_])" + re.escape(stream) + r"(?![A-Za-z0-9_])",
                     sql or "", re.IGNORECASE) is not None
