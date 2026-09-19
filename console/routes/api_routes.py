"""
Pravaha console — the JSON API the screens are built on.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Everything the interface can do goes through here (ADR-033), which is what lets
the browser be replaced — or a second client written — without touching the
engine integration. The screens consume JSON; they do not know the SDK exists.

Versioned in the path from the first commit rather than when the first breaking
change arrives, because by then there are clients and no way to tell which
version they assumed.
"""
from __future__ import annotations

import json
import logging

import anyio
from fastapi import Request
from fastapi.responses import JSONResponse, StreamingResponse

from routes.auth_routes import current_user
from routes.base import Routes

logger = logging.getLogger(__name__)

def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, default=str)}\n\n"


class ApiRoutes(Routes):
    def register(self) -> None:
        services = self.ctx["services"]
        api = self.api


        def _signed_in(request: Request):
            """A JSON refusal for an anonymous caller, or None.

            The API carries the same power as the screens -- register, pause, drop -- so it
            is gated the same way. It answers 401 rather than redirecting, because a fetch
            handling a login page as data is a worse failure than an honest status.
            """
            if current_user(request) is None:
                return JSONResponse(
                    {"error": "sign in to the console first", "status": 401}, status_code=401)
            return None

        @self.app.get(f"{api}/health", tags=["api"])
        def health():
            return JSONResponse(services.health.health().as_dict())

        @self.app.get(f"{api}/queries", tags=["api"])
        def list_queries(request: Request, search: str = "", state: str = "", sort: str = "name",
                         offset: int = 0, limit: int = 50):
            # Which queries exist and what SQL they run is the engine's own data, read with
            # the console's one shared engine identity -- not console chrome safe to hand to
            # an anonymous caller of the JSON API even though the HTML screen built on this
            # endpoint is gated. Gating only the screen and not the endpoint it calls would
            # be a second, weaker route to the same answer.
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(lambda: services.queries.find(
                search=search, state=state, sort=sort, offset=offset, limit=limit).as_dict(),
                request=request)

        @self.app.get(f"{api}/queries/{{name}}", tags=["api"])
        def get_query(request: Request, name: str):
            if (refusal := _signed_in(request)) is not None:
                return refusal

            def build():
                payload = services.queries.get(name).as_dict()
                payload["siblings"] = services.queries.siblings(name)
                return payload
            return self.json_guard(build, request=request)

        @self.app.post(f"{api}/queries", tags=["api"], status_code=201)
        async def register(request: Request):
            if (refusal := _signed_in(request)) is not None:
                return refusal
            body = await request.json()
            keys = body.get("keys") or []
            if isinstance(keys, str):
                keys = [int(part) for part in keys.replace(" ", "").split(",") if part]
            key_names = body.get("key_names") or []
            sink = str(body.get("sink") or "").strip() or None
            sql = str(body.get("sql", ""))

            def register_it():
                ordinals = list(keys)
                if key_names:
                    # Chosen by NAME in the workbench and mapped to ordinals here, against
                    # the schema the engine itself validated -- so the ordinal sent is the
                    # one the engine will use, whatever order the SELECT list is in.
                    ordinals, _fields = services.authoring.key_ordinals(sql, list(key_names))
                payload = services.queries.register(
                    str(body.get("name", "")), sql, ordinals, sink=sink).as_dict()
                payload["keys"] = ordinals
                payload["sink"] = sink
                return payload

            logger.info("%s registered '%s'%s", current_user(request), body.get("name"),
                        f" writing to sink '{sink}'" if sink else "")
            return self.json_guard(register_it, request=request)

        @self.app.post(f"{api}/queries/{{name}}/{{action}}", tags=["api"])
        def act(request: Request, name: str, action: str):
            if (refusal := _signed_in(request)) is not None:
                return refusal

            def run():
                services.queries.act(name, action)
                return {"name": name, "action": action, "ok": True}
            return self.json_guard(run, request=request)

        @self.app.post(f"{api}/query", tags=["api"])
        async def run_query(request: Request):
            if (refusal := _signed_in(request)) is not None:
                return refusal
            body = await request.json()
            return self.json_guard(
                lambda: services.adhoc.run(str(body.get("sql", "")), body.get("parameters")),
                request=request)

        @self.app.get(f"{api}/stats", tags=["api"])
        def stats(request: Request):
            """What the console can see, including its own fan-out.

            Gated with the query-count breakdown it carries, for the same reason as
            /api/v1/queries: the health half (is the engine reachable) is safe to publish
            to an incident responder with no session, but this endpoint mixes that with
            per-state query counts, which is engine data.
            """
            if (refusal := _signed_in(request)) is not None:
                return refusal
            health = services.health.health()
            payload = {"engine": health.as_dict(),
                       "upstream_subscriptions": services.feeds.live_feeds()}
            try:
                page = services.queries.find(limit=500)
                counts: dict = {}
                for item in page.items:
                    counts[item.state] = counts.get(item.state, 0) + 1
                payload |= {"queries": page.total, "states": counts,
                            "shared": sum(1 for q in page.items if q.shared)}
            except Exception:  # noqa: BLE001 -- already reported under `engine`
                # Repeating the unreachable engine as a failure of the whole
                # endpoint would lose the console's own numbers along with it.
                payload |= {"queries": 0, "states": {}, "shared": 0}
            return JSONResponse(payload)

        @self.app.get(f"{api}/views/{{view}}/stream", tags=["api"])
        def stream(view: str, request: Request):
            """A live tail, as server-sent events.

            SSE rather than a WebSocket: this is one-directional, reconnects by
            itself, and passes through proxies that mangle upgrades. A WebSocket
            would be a second transport to operate for no capability that is used.

            The subscription behind it is shared with every other browser
            watching the same view, so the engine sees one subscriber however
            many people have the page open.

            Gated -- this is not metadata about a query, it is the query's own row-level
            output crossing the wire to a browser. An anonymous caller reaching this
            directly (the browser's EventSource sends the session cookie automatically for
            this same-origin request once signed in, so nothing else changes) would be a
            live read of engine data with no session at all: the exact "second, weaker
            route to the data" a console must not become.
            """
            if (refusal := _signed_in(request)) is not None:
                return refusal
            filters = {k: v for k, v in request.query_params.items()}
            subscriber = services.feeds.subscribe(view, filters)

            async def events():
                """Async on purpose.

                A synchronous generator runs in a thread pool where it cannot be
                cancelled, so when the browser went away it would carry on
                looping and the engine subscription behind it would stay open for
                a tab that closed an hour ago.
                """
                try:
                    yield _sse("open", {"view": view, "filters": filters})
                    reported_lag = 0
                    while True:
                        if await request.is_disconnected():
                            return
                        rows = subscriber.drain()
                        for row in rows:
                            if "_error" in row:
                                yield _sse("error", {"message": row["_error"]})
                                return
                            yield _sse("row", row)
                        if not rows:
                            # Keeps the connection open through proxies that drop
                            # idle ones, and tells the client the feed is healthy
                            # rather than merely quiet -- on a stream those are
                            # easy to confuse and only one is a problem.
                            yield ": keep-alive\n\n"
                        if subscriber.dropped != reported_lag:
                            reported_lag = subscriber.dropped
                            yield _sse("lag", {"dropped": reported_lag})
                        await anyio.sleep(0.25)
                finally:
                    subscriber.close()

            return StreamingResponse(
                events(), media_type="text/event-stream",
                headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})
