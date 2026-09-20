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

from core.services import ServiceError
from routes.auth_routes import current_user
from routes.base import Routes, sign_in_first

logger = logging.getLogger(__name__)

def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, default=str)}\n\n"


async def _json_body(request: Request) -> dict:
    """The request's JSON object, or an empty one: a body that is not an object carries no
    fields, and reading a field off it is the caller's mistake, reported where it is made."""
    try:
        body = await request.json()
    except (ValueError, UnicodeDecodeError):
        return {}
    return body if isinstance(body, dict) else {}


def _refuse_checkpoint(asked: object):
    """A checkpoint id the console cannot even send: refused by name, never rounded."""
    def refuse():
        raise ServiceError(
            f"'{asked}' is not a checkpoint id; a checkpoint id is a whole number, and "
            "leaving it out asks for the newest one this node still retains", status=400)
    return refuse


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
                return sign_in_first()
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

        @self.app.get(f"{api}/replacements", tags=["api"])
        def list_replacements(request: Request):
            """Every blue/green replacement the engine knows about (ADR-046)."""
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"items": services.replacements.all()},
                                   request=request)

        @self.app.get(f"{api}/queries/{{name}}/replacement", tags=["api"])
        def get_replacement(request: Request, name: str):
            """One replacement, whole: state, backfill progress and the rollback window.

            ``{"replacement": null}`` when there is not one -- which is a fact, not a 404:
            the query exists and is not being replaced, and the screen draws that as its
            never-had-data state rather than as an error.
            """
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(
                lambda: {"query": name, "replacement": services.replacements.status(name)},
                request=request)

        @self.app.get(f"{api}/queries/{{name}}/replacement/stream", tags=["api"])
        def replacement_stream(request: Request, name: str):
            """The replacement at 1 Hz, as design 23.11's "long jobs" row asks for.

            The engine publishes no stream of its own for a backfill, so the console asks
            it once a second and fans that out -- the same bargain the dashboard makes.
            A hidden tab closes the stream, and the browser never polls beside it.
            """
            if (refusal := _signed_in(request)) is not None:
                return refusal

            async def events():
                while True:
                    if await request.is_disconnected():
                        return
                    try:
                        status = await anyio.to_thread.run_sync(
                            services.replacements.status, name)
                        yield _sse("replacement", {"query": name, "replacement": status})
                    except Exception as exc:  # noqa: BLE001 -- delivered, as the page's error state
                        yield _sse("failed", {"query": name, "message": str(exc),
                                              "code": getattr(exc, "code", None)})
                    await anyio.sleep(1.0)

            return StreamingResponse(events(), media_type="text/event-stream",
                                     headers={"Cache-Control": "no-cache",
                                              "X-Accel-Buffering": "no"})

        # -------------------------------------- the time-travel debugger (ADR-048, 23.9)
        #
        # The engine's own paths, one for one, because the console is a client of the
        # published API and a second spelling of the same call is a second thing to keep in
        # step. Request/response throughout: design 23.11 reserves a WebSocket for the
        # debugger and ADR-048 did not build it, because a step is asked for and answered
        # and nothing arrives that was not asked for.

        @self.app.get(f"{api}/queries/{{name}}/debug/checkpoints", tags=["api"])
        def debug_checkpoints(request: Request, name: str):
            """The positions a fork of this query could still start from, newest first."""
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(
                lambda: {"query": name, "checkpoints": services.debug.checkpoints(name)},
                request=request)

        @self.app.post(f"{api}/queries/{{name}}/debug", tags=["api"])
        async def debug_fork(request: Request, name: str):
            """Forks the query into a second computation nothing can read.

            No ``checkpointId`` means the newest retained one, and that choice is the
            engine's: a node prunes between the list being drawn and the button being
            pressed, so a console that pinned the id it happened to show would be asking
            for a position that has gone.
            """
            if (refusal := _signed_in(request)) is not None:
                return refusal
            body = await _json_body(request)
            asked = body.get("checkpointId")
            try:
                checkpoint = None if asked in (None, "") else int(asked)
            except (TypeError, ValueError):
                return self.json_guard(_refuse_checkpoint(asked), request=request)
            logger.info("%s forked '%s' into a debug session", current_user(request), name)
            return self.json_guard(lambda: services.debug.fork(name, checkpoint),
                                   request=request)

        @self.app.get(f"{api}/debug/sessions", tags=["api"])
        def debug_sessions(request: Request):
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"items": services.debug.sessions()},
                                   request=request)

        @self.app.get(f"{api}/debug/sessions/{{session_id}}", tags=["api"])
        def debug_session(request: Request, session_id: str):
            """One session, or ``{"session": null}`` -- ended or expired is a fact, not a 404."""
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"session": services.debug.session(session_id)},
                                   request=request)

        @self.app.post(f"{api}/debug/sessions/{{session_id}}/step", tags=["api"])
        async def debug_step(request: Request, session_id: str):
            """One step, and everything it did: rows in, every operator, the view, the time."""
            if (refusal := _signed_in(request)) is not None:
                return refusal
            body = await _json_body(request)
            return self.json_guard(
                lambda: services.debug.step(session_id, str(body.get("step") or "")),
                request=request)

        @self.app.get(f"{api}/debug/sessions/{{session_id}}/state", tags=["api"])
        def debug_state(request: Request, session_id: str):
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"slots": services.debug.state(session_id)},
                                   request=request)

        @self.app.get(f"{api}/debug/sessions/{{session_id}}/state/{{operator}}", tags=["api"])
        def debug_inspect(request: Request, session_id: str, operator: str,
                          key: str = "", offset: int = 0, limit: int = 50):
            """A page of one operator's state. The engine refuses a page above its ceiling."""
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(
                lambda: services.debug.inspect(session_id, operator, key=key or None,
                                               offset=offset, limit=limit),
                request=request)

        @self.app.get(f"{api}/debug/sessions/{{session_id}}/view", tags=["api"])
        def debug_view(request: Request, session_id: str):
            if (refusal := _signed_in(request)) is not None:
                return refusal
            return self.json_guard(lambda: {"changes": services.debug.view(session_id)},
                                   request=request)

        @self.app.post(f"{api}/debug/sessions/{{session_id}}/fixture", tags=["api"])
        async def debug_fixture(request: Request, session_id: str):
            """The session as a JUnit fixture: class name, where it belongs, and its source."""
            if (refusal := _signed_in(request)) is not None:
                return refusal
            body = await _json_body(request)
            return self.json_guard(
                lambda: services.debug.export(session_id, str(body.get("name") or "")),
                request=request)

        @self.app.delete(f"{api}/debug/sessions/{{session_id}}", tags=["api"])
        def debug_end(request: Request, session_id: str):
            if (refusal := _signed_in(request)) is not None:
                return refusal

            def release():
                services.debug.end(session_id)
                return {"session": session_id, "ended": True}
            return self.json_guard(release, request=request)

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
            # How much event time the view keeps: ISO-8601 (PT24H) or "forever"; the engine
            # checks it and refuses what it cannot read rather than keeping something else.
            retention = str(body.get("retention") or "").strip() or None
            sql = str(body.get("sql", ""))

            def register_it():
                ordinals = list(keys)
                if key_names:
                    # Chosen by NAME in the workbench and mapped to ordinals here, against
                    # the schema the engine itself validated -- so the ordinal sent is the
                    # one the engine will use, whatever order the SELECT list is in.
                    ordinals, _fields = services.authoring.key_ordinals(sql, list(key_names))
                payload = services.queries.register(
                    str(body.get("name", "")), sql, ordinals, sink=sink,
                    retention=retention).as_dict()
                payload["keys"] = ordinals
                payload["sink"] = sink
                payload["retention"] = retention
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

            The first event after ``open`` is ``snapshot``: the view's rows (at most
            the view row limit, with ``truncated`` and ``returned`` saying so) and the
            frontier they are true at; every ``row`` after it is a change after that
            view. A page that read the view separately and then opened this stream
            could lose the commit landing between the two (SUB-1).

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
                    started = False
                    while True:
                        if await request.is_disconnected():
                            return
                        if not started:
                            # The view first, then only the changes after it (SUB-1): nothing
                            # is drained until the starting view has been sent.
                            taken = subscriber.take_snapshot()
                            if taken is None:
                                if subscriber.failure():
                                    yield _sse("error", {"message": subscriber.failure()})
                                    return
                                yield ": keep-alive\n\n"
                                await anyio.sleep(0.1)
                                continue
                            rows, frontier = taken
                            limit = services.feeds.snapshot_rows
                            yield _sse("snapshot", {"rows": rows[:limit], "frontier": frontier,
                                                    "truncated": len(rows) > limit,
                                                    "returned": min(len(rows), limit)})
                            started = True
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
