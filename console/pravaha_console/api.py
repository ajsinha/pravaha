"""The versioned JSON API the browser talks to (ADR-033).

Everything the UI can do goes through here, which is what lets the browser be replaced --
or a second client be written -- without touching the engine integration. The screens
consume JSON; they do not know the SDK exists.

Versioned in the path from the first commit rather than when the first breaking change
arrives, because by then there are clients and no way to tell which version they assumed.

Errors are a single shape. A client that has to parse two error formats will handle one of
them badly, which is the same reasoning that turned off Spring's problem-details in the
engine's own HTTP surface.
"""
from __future__ import annotations

import json

import anyio
from fastapi import APIRouter, Request
from fastapi import Query as QueryParam
from fastapi.responses import JSONResponse, StreamingResponse

from pravaha_console.services import ServiceError, Services

#: Bumped when a response shape changes in a way a client would notice.
API_VERSION = "v1"


def _error(exc: ServiceError) -> JSONResponse:
    body = {"error": str(exc), "status": exc.status}
    if exc.code:
        # The PRV code travels, so the UI can link straight to the entry in
        # TROUBLESHOOTING instead of leaving the reader to search for it.
        body["code"] = exc.code
    return JSONResponse(body, status_code=exc.status)


def api_router(services: Services) -> APIRouter:
    router = APIRouter(prefix=f"/api/{API_VERSION}")

    @router.get("/health")
    def health() -> JSONResponse:
        # Always 200. This endpoint answers "what is the state of the world", and a 503
        # would make a monitoring system report the console as down when the console is
        # fine and reporting accurately that the engine is not.
        return JSONResponse(services.health.health().as_dict())

    @router.get("/queries")
    def list_queries(
        search: str = QueryParam("", description="matches name or SQL"),
        state: str = QueryParam(""),
        sort: str = QueryParam("name"),
        offset: int = QueryParam(0, ge=0),
        limit: int = QueryParam(50, ge=1, le=500),
    ) -> JSONResponse:
        try:
            return JSONResponse(services.queries.find(
                search=search, state=state, sort=sort, offset=offset, limit=limit
            ).as_dict())
        except ServiceError as exc:
            return _error(exc)

    @router.get("/queries/{name}")
    def get_query(name: str) -> JSONResponse:
        try:
            payload = services.queries.get(name).as_dict()
            payload["siblings"] = services.queries.siblings(name)
            return JSONResponse(payload)
        except ServiceError as exc:
            return _error(exc)

    @router.post("/queries")
    async def register(request: Request) -> JSONResponse:
        body = await _body(request)
        keys = body.get("keys") or body.get("key_columns") or []
        if isinstance(keys, str):
            keys = [int(part) for part in keys.replace(" ", "").split(",") if part]
        try:
            return JSONResponse(
                services.queries.register(str(body.get("name", "")), str(body.get("sql", "")), list(keys)).as_dict(),
                status_code=201,
            )
        except ServiceError as exc:
            return _error(exc)

    @router.post("/queries/{name}/{action}")
    def act(name: str, action: str) -> JSONResponse:
        try:
            services.queries.act(name, action)
            return JSONResponse({"name": name, "action": action, "ok": True})
        except ServiceError as exc:
            return _error(exc)

    @router.post("/query")
    async def run(request: Request) -> JSONResponse:
        body = await _body(request)
        try:
            return JSONResponse(services.adhoc.run(str(body.get("sql", "")), body.get("parameters")))
        except ServiceError as exc:
            return _error(exc)

    @router.get("/views/{view}/stream")
    def stream(view: str, request: Request) -> StreamingResponse:
        """A live tail, as server-sent events.

        SSE rather than a WebSocket: this is one-directional, and SSE reconnects by itself,
        goes through proxies that mangle upgrades, and needs no protocol of its own. A
        WebSocket would be a second transport to operate for no capability that is used.

        The subscription behind this is shared with every other browser watching the same
        view (see ``Broadcaster``), so the engine sees one subscriber however many people
        have the page open.
        """
        filters = {k: v for k, v in request.query_params.items() if k not in {"view"}}
        subscriber = services.feeds.subscribe(view, filters)

        async def events():
            """Async on purpose.

            A synchronous generator would be run in a thread pool, where it cannot be
            cancelled -- so when the browser went away it would carry on looping, and the
            engine subscription behind it would stay open for a tab that closed an hour
            ago. One leaked subscription per closed tab is exactly the unbounded growth
            this layer exists to prevent, so the loop checks for the disconnect itself.
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
                        # Keeps the connection open through proxies that drop idle ones, and
                        # tells the client the feed is healthy rather than merely quiet --
                        # on a stream those are easy to confuse and only one is a problem.
                        yield ": keep-alive\n\n"
                    if subscriber.dropped != reported_lag:
                        reported_lag = subscriber.dropped
                        yield _sse("lag", {"dropped": reported_lag})
                    await anyio.sleep(0.25)
            finally:
                # Runs on disconnect now that the loop can be cancelled, which is what
                # releases the engine-side subscription once the last watcher has gone.
                subscriber.close()

        return StreamingResponse(
            events(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no", "Connection": "keep-alive"},
        )

    @router.get("/stats")
    def stats() -> JSONResponse:
        """What the console itself is doing. Its own load, not the engine's."""
        health = services.health.health()
        payload = {"engine": health.as_dict(), "upstream_subscriptions": services.feeds.live_feeds()}
        try:
            page = services.queries.find(limit=500)
            payload["queries"] = page.total
            payload["states"] = _counted(page)
            payload["shared"] = sum(1 for q in page.items if q.shared)
        except ServiceError:
            # The engine being unreachable is already reported in `engine`; repeating it
            # as a failure of the whole endpoint would lose the console's own numbers.
            payload["queries"] = 0
            payload["states"] = {}
            payload["shared"] = 0
        return JSONResponse(payload)

    return router


def _counted(page) -> dict:
    counts: dict = {}
    for item in page.items:
        counts[item.state] = counts.get(item.state, 0) + 1
    return counts


async def _body(request: Request) -> dict:
    """Accepts JSON or a posted form, so the UI works with scripting disabled."""
    content_type = request.headers.get("content-type", "")
    if "application/json" in content_type:
        try:
            return await request.json()
        except Exception:  # noqa: BLE001
            return {}
    form = await request.form()
    return {key: value for key, value in form.items()}


def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, default=str)}\n\n"
