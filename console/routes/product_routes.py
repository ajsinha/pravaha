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
from routes.base import ROLES, Routes, failure, role_of, sign_in_first

logger = logging.getLogger(__name__)


def _refuse_anonymous(request: Request) -> JSONResponse | None:
    if current_user(request) is None:
        return sign_in_first()
    return None


class ProductRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        config = self.ctx["config"]
        api = self.api

        def safe(fn, fallback, what: str = ""):
            """The engine's answer, or the fallback and a Failure the page renders as its error
            or partial state (design 23.12): message, code, whether a retry can help, and the
            correlation id that is also on the log line."""
            try:
                return fn(), None
            except ServiceError as exc:
                return fallback, failure(exc, what=what)

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
                             templates=authoring.templates(chosen),
                             register_refused=services.admin.affordances().register_refused())

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
                                 current="/catalog", what=self.t("not_found.what.stream"), identifier=name,
                                 back_href="/catalog", back_label=self.t("not_found.back.catalog"),
                                 detail=str(exc))
            queries, readers_error = safe(
                lambda: services.queries.find(limit=services.queries.MAX_LIMIT).items, [], "the queries")
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
                             readers=readers, readers_error=readers_error, lineage=lineage,
                             templates=authoring.templates(stream))

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
                                 current="/views", what=self.t("not_found.what.view"), identifier=name,
                                 back_href="/views", back_label=self.t("not_found.back.views"),
                                 detail=str(exc))
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
            siblings, _ = safe(lambda: services.queries.siblings(name), [], "the shared names")
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
                                 current="/views", what=self.t("not_found.what.view"), identifier=name,
                                 back_href="/views", back_label=self.t("not_found.back.views"),
                                 detail=str(exc))
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

        @self.app.get("/plugins", response_class=HTMLResponse, tags=["ui"])
        def plugins(request: Request):
            """The plugins the node loaded, their health, and what each is bound as."""
            if (refusal := login_required(request)) is not None:
                return refusal
            inventory = services.plugins.inventory()
            # Each call that failed, logged once with the correlation id the page shows (23.12).
            inventory["errors"] = {call: failure(ServiceError(message, 503), what=f"the {call} call")
                                   for call, message in inventory["errors"].items()}
            return self.page(request, "plugins.html", current="/plugins", inventory=inventory)

        # ================================================================== JSON

        @self.app.get(f"{api}/plugins", tags=["api"])
        def api_plugins(request: Request):
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            return JSONResponse(jsonable(services.plugins.inventory()))

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
                    # The registered query's measured numbers, attached only while the SQL
                    # being explained is still that query's own: under an edited query they
                    # would be numbers about a different plan. Same SQL means the same plan
                    # means the same node ids, which is what lets the per-operator block be
                    # drawn on this graph's nodes rather than on a set that only looks alike.
                    try:
                        registered = services.queries.get(query)
                        if registered.sql.strip() == sql.strip():
                            running = services.authoring.plan(query)
                            answer["query_metrics"] = running.get("query_metrics")
                            answer["operator_metrics"] = running.get("operator_metrics")
                            answer["bottleneck"] = running.get("bottleneck")
                            answer["metrics_note"] = running.get("metrics_note")
                            answer["metrics_state"] = running.get("metrics_state")
                    except ServiceError:
                        pass
                return answer
            return self.json_guard(explain, request=request)

        @self.app.post(f"{api}/sql/diff", tags=["api"])
        async def api_diff(request: Request):
            """Two versions side by side: ``{"left": {"query": name} | {"sql", "label"},
            "right": {...}}`` -- SQL, plans matched operator by operator, and consequences."""
            if (refusal := _refuse_anonymous(request)) is not None:
                return refusal
            body = await _body(request)
            left, right = body.get("left"), body.get("right")
            return self.json_guard(
                lambda: services.authoring.diff(left if isinstance(left, dict) else {},
                                                right if isinstance(right, dict) else {}, services.queries),
                request=request)

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
                return JSONResponse({"error": self.t("api.filters_object"), "status": 400},
                                    status_code=400)
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
            and stream, and the lifecycle actions each query's state and the engine's policy
            allow -- an action a query cannot take is absent rather than offered and refused.
            """
            t = self.t

            def page(key: str, href: str) -> dict[str, Any]:
                return {"kind": "page", "title": t(f"palette.page.{key}"), "href": href,
                        "hint": t(f"palette.hint.{key}")}

            items: list[dict[str, Any]] = [
                page("landing", "/"), page("help", "/help"), page("tutorials", "/tutorials"),
            ]
            if current_user(request) is None:
                items.append({"kind": "page", "title": t("palette.page.sign_in"), "href": "/login", "hint": ""})
                return JSONResponse({"signed_in": False, "items": items})
            role = role_of(request, default_role())
            items += [
                page("workbench", "/workbench"), page("catalog", "/catalog"), page("views", "/views"),
                page("operations", "/operations"), page("queries", "/queries"), page("start", "/start"),
                page("plugins", "/plugins"), page("access", "/admin/access"), page("audit", "/admin/audit"),
                {"kind": "action", "title": t("palette.action.new_query"), "href": "/workbench?new=1",
                 "hint": t("palette.hint.new_query")},
            ]
            for key, meta in ROLES.items():
                if key != role:
                    items.append({"kind": "role", "title": t("palette.role", role=t(meta["label"]).lower()),
                                  "role": key, "hint": t(meta["blurb"])})
            queries, _ = safe(lambda: services.queries.find(limit=services.queries.MAX_LIMIT).items, [])
            # Lifecycle actions the engine's policy refuses this identity are absent (design 23.16):
            # the query's own page says why, and a palette is no place for a refusal.
            may = services.admin.affordances()
            for q in queries:
                items.append({"kind": "query", "title": q.name, "href": f"/queries/{q.name}",
                              "hint": q.state, "state": q.state})
                items.append({"kind": "view", "title": t("palette.view.browse", name=q.name),
                              "href": f"/views/{q.name}", "hint": t("palette.hint.browse")})
                items.append({"kind": "view", "title": t("palette.view.live", name=q.name),
                              "href": f"/views/{q.name}/live", "hint": t("palette.hint.live")})
                items.append({"kind": "action", "title": t("palette.action.open", name=q.name),
                              "href": f"/workbench?query={q.name}", "hint": t("palette.hint.open")})
                # B5. A read, so every identity that may see the query gets it: the engine
                # decides what of each record it may then see, and replay is authorized
                # separately on the screen itself.
                items.append({"kind": "query", "title": t("palette.query.dead_letters", name=q.name),
                              "href": f"/queries/{q.name}/dead-letters",
                              "hint": t("palette.hint.dead_letters")})
                if may.administer_refused(q.name) is not None:
                    continue
                # B9. A read of the replacement screen, but every control on it needs the
                # administer permission, so an identity the policy refuses is not offered the
                # way in either -- the palette is no place to discover a refusal.
                items.append({"kind": "query", "title": t("palette.query.replacement", name=q.name),
                              "href": f"/queries/{q.name}/replacement",
                              "hint": t("palette.hint.replacement")})
                if q.state == "RUNNING":
                    items.append({"kind": "lifecycle", "title": t("palette.action.pause", name=q.name),
                                  "query": q.name, "action": "pause", "hint": t("palette.hint.pause")})
                elif q.state in {"PAUSED"}:
                    items.append({"kind": "lifecycle", "title": t("palette.action.resume", name=q.name),
                                  "query": q.name, "action": "resume", "hint": t("palette.hint.resume")})
                items.append({"kind": "lifecycle", "title": t("palette.action.drop", name=q.name),
                              "query": q.name, "action": "drop", "hint": t("palette.hint.drop"),
                              "href": f"/queries/{q.name}#drop"})
            for s in services.catalog.streams_or_empty():
                items.append({"kind": "stream", "title": s.get("name"),
                              "href": f"/catalog/streams/{s.get('name')}",
                              "hint": t("palette.hint.stream", n=len(s.get("fields") or []))})
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
