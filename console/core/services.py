"""The service layer: everything the browser can ask for, as typed calls.

ADR-033. The browser talks to versioned JSON services; the services talk to the engine
through the SDK. Nothing above this layer knows the SDK exists, and nothing below it
knows a browser does.

Three things follow from that split, and they are the reasons for it.

**The services hold no per-browser state, so the UI scales sideways.** Any instance can
answer any request, and instances go behind a load balancer without coordination. A page
that kept an engine connection per browser tab could not be scaled that way, because the
connection *is* the state.

**One engine subscription serves every browser watching the same view.** See
:class:`Broadcaster`. Without it, twenty analysts on one dashboard would be twenty
subscriptions to the engine -- and the product's claim is that ten analysts asking one
question cost one computation. A UI that quietly multiplied the load would be
contradicting the thing it exists to demonstrate.

**A slow browser cannot slow the engine.** Each subscriber has a bounded buffer and loses
its oldest rows when it falls behind, which is the same choice the engine's own
subscriptions make and for the same reason: a live view is about what is true now, so the
newest row matters more than the one the reader has not caught up to.
"""
from __future__ import annotations

import dataclasses
import queue
import threading
import time
from collections.abc import Callable, Iterator
from typing import Any

from core.engine import Engine, QueryRow


@dataclasses.dataclass(frozen=True)
class Health:
    """Whether the engine answers, and what it said if it did not."""

    reachable: bool
    url: str
    queries: int = 0
    error: str | None = None

    def as_dict(self) -> dict:
        payload: dict[str, Any] = {
            "reachable": self.reachable,
            "url": self.url,
            "queries": self.queries,
        }
        if self.error:
            payload["error"] = self.error
        return payload


@dataclasses.dataclass(frozen=True)
class Query:
    """One registered continuous query, as a service returns it."""

    name: str
    state: str
    sql: str
    fingerprint: str
    rows_in: int
    shared: bool
    key_columns: tuple = ()
    sink: str | None = None
    retention: str | None = None

    def as_dict(self) -> dict:
        out = dataclasses.asdict(self)
        out["key_columns"] = list(self.key_columns)
        return out


@dataclasses.dataclass(frozen=True)
class Page:
    """A slice of a list, with enough context for the client to render a pager.

    ``total`` is the size before the slice and after the filter, because "showing 20 of
    847" and "showing 20 of 3" are different things to a reader and only the second means
    the filter worked.
    """

    items: list
    total: int
    offset: int
    limit: int

    def as_dict(self) -> dict:
        return {
            "items": [item.as_dict() if hasattr(item, "as_dict") else item for item in self.items],
            "total": self.total,
            "offset": self.offset,
            "limit": self.limit,
        }


class ServiceError(Exception):
    """Something the caller should be told about, with a status to send."""

    def __init__(self, message: str, status: int = 400, code: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        # The engine's PRV code when there is one, so the UI can link to TROUBLESHOOTING
        # rather than showing a wall of text with the useful part buried in it.
        self.code = code


def _code_in(message: str) -> str | None:
    """Pulls a PRV code out of an engine message, if it carries one."""
    marker = message.find("PRV-")
    if marker < 0:
        return None
    tail = message[marker : marker + 12]
    code = tail.split()[0].strip(".,:;")
    return code if len(code) > 4 else None


class HealthService:
    """Is the engine there.

    Cached for a second. The overview polls this, and every browser asking the engine
    directly would mean the health check became a load source of its own -- which is the
    failure mode where monitoring takes down the thing it monitors.
    """

    def __init__(self, engine: Engine, ttl_seconds: float = 1.0) -> None:
        self._engine = engine
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._cached: Health | None = None
        self._at = 0.0

    def health(self) -> Health:
        with self._lock:
            now = time.monotonic()
            if self._cached is not None and now - self._at < self._ttl:
                return self._cached
        raw = self._engine.health()
        health = Health(
            reachable=bool(raw.get("reachable")),
            url=str(raw.get("url", "")),
            queries=int(raw.get("queries", 0) or 0),
            error=raw.get("error"),
        )
        with self._lock:
            self._cached = health
            self._at = time.monotonic()
        return health


class QueryService:
    """Registered continuous queries: listing, detail, and the lifecycle actions."""

    #: Guards against a client asking for everything at once on a server with thousands.
    MAX_LIMIT = 500

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def find(
        self,
        *,
        search: str = "",
        state: str = "",
        offset: int = 0,
        limit: int = 50,
        sort: str = "name",
    ) -> Page:
        """Filtered, sorted and paged.

        Named ``find`` rather than ``list`` because a method called ``list`` shadows the
        builtin inside the class body, and every ``list[...]`` annotation below it then
        fails to resolve -- which mypy reports as a confusing error a long way from here.

        Filtering happens here rather than in the browser because a deployment with a
        thousand registered queries should not send a thousand rows to render twenty, and
        because every filter is in the URL (a §23.20 requirement) which only means
        something if the server honours it.
        """
        rows = [self._of(row) for row in self._safe_queries()]

        needle = search.strip().lower()
        if needle:
            rows = [q for q in rows if needle in q.name.lower() or needle in q.sql.lower()]
        if state:
            wanted = state.strip().upper()
            rows = [q for q in rows if q.state.upper() == wanted]

        reverse = sort.startswith("-")
        key = sort.lstrip("-") or "name"
        if key not in {"name", "state", "rows_in"}:
            key = "name"
        rows.sort(key=lambda q: getattr(q, key), reverse=reverse)

        total = len(rows)
        limit = max(1, min(limit, self.MAX_LIMIT))
        offset = max(0, offset)
        return Page(items=rows[offset : offset + limit], total=total, offset=offset, limit=limit)

    def get(self, name: str) -> Query:
        for row in self._safe_queries():
            if row.name == name:
                return self._of(row)
        raise ServiceError(f"no query is registered as '{name}'", status=404)

    def siblings(self, name: str) -> list[str]:
        """Other names sharing this computation.

        Shown on the detail page because dropping a name that somebody else also
        registered does not stop the computation, and an operator about to drop something
        should be able to see that before they do rather than after.
        """
        target = self.get(name)
        return sorted(
            row.name
            for row in self._safe_queries()
            if row.fingerprint == target.fingerprint and row.name != name
        )

    def detail(self, name: str) -> dict:
        """What the engine says about one query beyond the listing: keys by name, retention,
        the sink and whether it is still attached (``PRV-8009``), the names sharing it and the
        streams it reads. ``GET /api/v1/queries/{name}`` through the SDK, so the engine decides
        what this identity may see of it."""
        try:
            return self._engine.describe_query(name)
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    def reads(self) -> dict[str, list[str]] | None:
        """The streams each visible query reads, by name, from the engine's descriptions.

        ``None`` when the engine's descriptions do not carry it (an older engine), so a
        caller can say it fell back to matching names rather than claim a lineage it
        guessed.
        """
        try:
            described = self._engine.describe_queries()
        except Exception as exc:
            raise _refusal(exc, 503) from exc
        if described and all("reads" not in d for d in described):
            return None
        return {str(d.get("name")): list(d.get("reads") or []) for d in described}

    def register(self, name: str, sql: str, keys: list[int], sink: str | None = None,
                 retention: str | None = None) -> Query:
        if not name.strip():
            raise ServiceError("a registration needs a name", status=400)
        if not sql.strip():
            raise ServiceError("a registration needs a query", status=400)
        if not keys:
            raise ServiceError(
                "a registration needs at least one key column: a view with no key is a log, "
                "and a point read against it has nothing to look up",
                status=400,
            )
        try:
            extra: dict = {}
            if sink and sink.strip():
                extra["sink"] = sink.strip()
            if retention and retention.strip():
                extra["retention"] = retention.strip()
            row = self._engine.register(name.strip(), sql.strip(), keys, **extra)
        except Exception as exc:
            raise ServiceError(str(exc), status=400, code=_code_in(str(exc))) from exc
        return self._of(row)

    def act(self, name: str, action: str) -> None:
        if action not in {"pause", "resume", "drop"}:
            raise ServiceError(f"'{action}' is not something a query can be asked to do", status=400)
        try:
            self._engine.lifecycle(action, name)
        except Exception as exc:
            raise ServiceError(str(exc), status=400, code=_code_in(str(exc))) from exc

    def _safe_queries(self) -> list[QueryRow]:
        try:
            return self._engine.queries()
        except Exception as exc:
            # Unreachable is a state the UI renders, not a crash. An operator opening the
            # console during an incident needs it to load and say what is wrong.
            raise ServiceError(str(exc), status=503, code=_code_in(str(exc))) from exc

    @staticmethod
    def _of(row: QueryRow) -> Query:
        return Query(
            name=row.name,
            state=row.state,
            sql=row.sql,
            fingerprint=row.fingerprint,
            rows_in=row.rows_in,
            shared=row.shared,
            key_columns=tuple(getattr(row, "key_columns", ()) or ()),
            sink=getattr(row, "sink", None),
            retention=getattr(row, "retention", None),
        )


class AdHocService:
    """One-off questions, for the workbench."""

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def run(self, sql: str, parameters: list | None = None, limit: int = 500) -> dict:
        if not sql.strip():
            raise ServiceError("nothing to run", status=400)
        started = time.monotonic()
        try:
            columns, rows, types = self._engine.query_typed(sql, parameters)
        except Exception as exc:
            raise ServiceError(str(exc), status=400, code=_code_in(str(exc))) from exc
        took_ms = round((time.monotonic() - started) * 1000, 1)
        truncated = len(rows) > limit
        return {
            "columns": columns,
            "types": types,
            # Truncated here rather than in the browser: a query that returns a million
            # rows should not be able to make the console the reason the tab dies.
            "rows": jsonable(rows[:limit]),
            "truncated": truncated,
            "returned": min(len(rows), limit),
            "took_ms": took_ms,
        }


class Broadcaster:
    """One engine subscription per view, fanned out to every browser watching it.

    This is the piece that makes the UI's load independent of how many people have it
    open. Twenty analysts on one dashboard are twenty browser connections here and *one*
    subscription to the engine. Without it the UI would multiply engine load by the number
    of open tabs, which would contradict the product's own claim that one question costs
    one computation however many people ask it.

    Ref-counted, like the registry's own sharing: the upstream subscription starts with
    the first subscriber and stops when the last one leaves. Stopping on the first
    departure would take the feed away from everyone else, who have no idea the first
    subscriber existed.
    """

    #: Rows buffered per browser before the oldest are dropped.
    BUFFER = 256

    def __init__(self, engine: Engine) -> None:
        self._engine = engine
        self._lock = threading.Lock()
        self._feeds: dict[str, _Feed] = {}

    def subscribe(self, view: str, filters: dict | None = None) -> Subscriber:
        key = view if not filters else view + "?" + "&".join(f"{k}={v}" for k, v in sorted(filters.items()))
        with self._lock:
            feed = self._feeds.get(key)
            if feed is None:
                feed = _Feed(self._engine, view, filters or {}, lambda: self._release(key))
                self._feeds[key] = feed
            return feed.attach()

    def _release(self, key: str) -> None:
        with self._lock:
            self._feeds.pop(key, None)

    def live_feeds(self) -> int:
        """How many engine subscriptions are open. For the overview, and for a test."""
        with self._lock:
            return len(self._feeds)


class Subscriber:
    """One browser's view of a feed, with a bounded buffer."""

    def __init__(self, feed: _Feed) -> None:
        self._feed = feed
        self._queue: queue.Queue = queue.Queue(maxsize=Broadcaster.BUFFER)
        self.dropped = 0
        self.closed = False

    def offer(self, row: dict) -> None:
        try:
            self._queue.put_nowait(row)
        except queue.Full:
            # Drop the oldest, keep the newest. A live view is about what is true now, so
            # the row the reader has not caught up to matters less than the one that just
            # arrived -- and blocking here would push back on the engine's own subscriber.
            try:
                self._queue.get_nowait()
                self._queue.put_nowait(row)
            except (queue.Empty, queue.Full):
                pass
            self.dropped += 1

    def rows(self, timeout: float = 0.5) -> Iterator[dict]:
        """Yields buffered rows, returning when there are none rather than blocking forever."""
        while not self.closed:
            try:
                yield self._queue.get(timeout=timeout)
            except queue.Empty:
                return

    def drain(self, limit: int = 256) -> list:
        """Takes whatever is buffered right now, without waiting for more.

        The non-blocking form exists because the SSE endpoint must stay able to notice that
        the browser has gone. A generator blocked on a queue cannot check for that, and a
        synchronous one running in a thread pool cannot be cancelled into noticing either --
        so it would keep its engine subscription open for a tab that closed an hour ago.
        """
        rows: list = []
        while len(rows) < limit:
            try:
                rows.append(self._queue.get_nowait())
            except queue.Empty:
                break
        return rows

    def close(self) -> None:
        if not self.closed:
            self.closed = True
            self._feed.detach(self)


class _Feed:
    """The engine-side half: one subscription, many subscribers."""

    def __init__(self, engine: Engine, view: str, filters: dict, on_empty: Callable[[], None]) -> None:
        self._engine = engine
        self._view = view
        self._filters = filters
        self._on_empty = on_empty
        self._lock = threading.Lock()
        self._subscribers: list[Subscriber] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.error: str | None = None

    def attach(self) -> Subscriber:
        subscriber = Subscriber(self)
        with self._lock:
            self._subscribers.append(subscriber)
            first = len(self._subscribers) == 1
        if first:
            self._thread = threading.Thread(target=self._pump, name=f"feed-{self._view}", daemon=True)
            self._thread.start()
        return subscriber

    def detach(self, subscriber: Subscriber) -> None:
        with self._lock:
            if subscriber in self._subscribers:
                self._subscribers.remove(subscriber)
            last = not self._subscribers
        if last:
            # The upstream subscription is released when the last browser goes, which is
            # what stops a closed tab leaving a subscription open on the engine for ever.
            self._stop.set()
            self._on_empty()

    def _pump(self) -> None:
        try:
            for row in self._engine.tail(self._view, self._filters):
                if self._stop.is_set():
                    return
                with self._lock:
                    targets = list(self._subscribers)
                for subscriber in targets:
                    subscriber.offer(row)
        except Exception as exc:  # noqa: BLE001
            # Recorded and delivered to the browsers rather than dying silently: a live
            # tail that simply stops looks exactly like a stream with nothing in it.
            self.error = str(exc)
            with self._lock:
                targets = list(self._subscribers)
            for subscriber in targets:
                subscriber.offer({"_error": self.error})


def jsonable(value: Any) -> Any:
    """Engine values as JSON: a timestamp as ISO-8601, a decimal as a string, bytes as hex.

    A decimal becomes a string rather than a float on purpose -- a money column rounded
    by the console on its way to the browser would be a wrong number on the one screen
    people copy numbers from.
    """
    import datetime as _dt
    import decimal as _decimal
    import math as _math

    if isinstance(value, list):
        return [jsonable(v) for v in value]
    if isinstance(value, tuple):
        return [jsonable(v) for v in value]
    if isinstance(value, dict):
        return {str(k): jsonable(v) for k, v in value.items()}
    if isinstance(value, (_dt.datetime, _dt.date, _dt.time)):
        return value.isoformat()
    if isinstance(value, _dt.timedelta):
        return value.total_seconds()
    if isinstance(value, _decimal.Decimal):
        return str(value)
    if isinstance(value, (bytes, bytearray)):
        return bytes(value).hex()
    if isinstance(value, float) and (_math.isnan(value) or _math.isinf(value)):
        return None
    return value


def _refusal(exc: Exception, status: int = 400) -> ServiceError:
    """An engine refusal as a ServiceError, keeping the engine's own code and status."""
    code = getattr(exc, "code", None) or _code_in(str(exc))
    http = getattr(exc, "status", None)
    if http == 0:
        # Did not answer at all: the engine is down or the URL is wrong, which is retryable.
        return ServiceError(str(exc), status=503, code=code)
    if isinstance(http, int) and 400 <= http < 600:
        status = 503 if http >= 500 else http
    return ServiceError(str(exc), status=status, code=code)


class CatalogService:
    """What exists to be queried: streams and their fields, from the engine's public REST API.

    Cached for a few seconds. The workbench asks on every page load and completion asks on
    every keystroke burst; the catalog changes when an administrator declares a stream,
    which is not a thing that happens between two keystrokes.
    """

    def __init__(self, engine: Engine, ttl_seconds: float = 5.0) -> None:
        self._engine = engine
        self._ttl = ttl_seconds
        self._lock = threading.Lock()
        self._cached: list[dict] | None = None
        self._at = 0.0

    def streams(self, fresh: bool = False) -> list[dict]:
        with self._lock:
            if (not fresh and self._cached is not None
                    and time.monotonic() - self._at < self._ttl):
                return self._cached
        try:
            streams = sorted(self._engine.streams(), key=lambda s: str(s.get("name", "")))
        except Exception as exc:
            raise _refusal(exc, 503) from exc
        with self._lock:
            self._cached, self._at = streams, time.monotonic()
        return streams

    def streams_or_empty(self) -> list[dict]:
        """For callers that can do without the catalog -- validation, the palette."""
        try:
            return self.streams()
        except ServiceError:
            return []

    def stream(self, name: str) -> dict:
        for stream in self.streams():
            if str(stream.get("name", "")).lower() == name.lower():
                return stream
        raise ServiceError(f"no stream named '{name}' is declared on this engine, or this "
                           "console's identity may not read it", status=404)

    def sinks(self) -> list[dict]:
        """The sink bindings this identity may see (``GET /api/v1/sinks`` via the SDK).

        Only what the engine publishes: plugin, row shape, key, emit modes, whether a revising
        query may write there, and the visible writers. A binding's options -- where its
        credentials live -- are never part of the engine's answer, so they cannot be part of
        this one.
        """
        try:
            return sorted(self._engine.sinks(), key=lambda s: str(s.get("name", "")))
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    def sinks_or_empty(self) -> list[dict]:
        try:
            return self.sinks()
        except ServiceError:
            return []

    def declare(self, name: str, schema: str, event_time: str | None = None,
                out_of_orderness: str | None = None) -> dict:
        from core.snippets import IDENTIFIER

        if not IDENTIFIER.match(name or ""):
            raise ServiceError("a stream name must be letters, digits and underscores", status=400)
        if not (schema or "").strip():
            raise ServiceError("a stream needs a schema, as name:TYPE pairs separated by commas",
                               status=400)
        try:
            declared = self._engine.declare_stream(
                name, schema.strip(), event_time=(event_time or "").strip() or None,
                out_of_orderness=(out_of_orderness or "").strip() or None)
        except Exception as exc:
            raise _refusal(exc) from exc
        with self._lock:
            self._cached = None
        return declared

    def completions(self) -> dict:
        """Everything the editor completes: streams, their columns with types, functions."""
        from core import authoring

        return {
            "streams": [{"name": s.get("name"), "version": s.get("version"),
                         "fields": s.get("fields") or []} for s in self.streams_or_empty()],
            "functions": authoring.FUNCTIONS,
            "keywords": authoring.KEYWORDS,
            "types": authoring.TYPES,
        }


class AuthoringService:
    """Validate and explain, through the engine's public REST API, made editor-shaped."""

    LEVELS = ("physical", "logical", "codegen")

    def __init__(self, engine: Engine, catalog: CatalogService) -> None:
        self._engine = engine
        self._catalog = catalog

    def validate(self, sql: str) -> dict:
        from core import authoring

        try:
            raw = self._engine.validate(sql)
        except Exception as exc:
            raise _refusal(exc, 503) from exc
        return authoring.enrich(raw, sql, self._catalog.streams_or_empty())

    def explain(self, sql: str, level: str = "physical") -> dict:
        from core import authoring

        if level not in self.LEVELS:
            raise ServiceError(f"level must be one of {', '.join(self.LEVELS)}", status=400)
        if not sql.strip():
            raise ServiceError("nothing to explain", status=400)
        try:
            raw = self._engine.explain(sql, level)
        except Exception as exc:
            raise _refusal(exc) from exc
        text = str(raw.get("plan") or "")
        engine_graph = raw.get("graph") or {}
        return {
            "level": raw.get("level", level),
            "plan": text,
            # The engine's own structure, renamed for the island -- not a parse of the text.
            "graph": authoring.plan_graph(engine_graph),
            "output_fields": list(raw.get("outputFields") or []),
            # The engine does not count per operator, and says so; the console repeats it
            # rather than drawing zeroes.
            "operator_metrics": engine_graph.get("operatorMetrics"),
            "metrics_note": engine_graph.get("metricsNote"),
            "query_metrics": None,
        }

    def plan(self, name: str) -> dict:
        """The plan a registered query is running, with the totals the engine measures for it."""
        from core import authoring

        try:
            raw = self._engine.query_plan(name)
        except Exception as exc:
            raise _refusal(exc) from exc
        return {
            "level": "physical",
            "plan": "",
            "graph": authoring.plan_graph(raw),
            "output_fields": [],
            "operator_metrics": raw.get("operatorMetrics"),
            "metrics_note": raw.get("metricsNote"),
            "query_metrics": raw.get("query"),
        }

    def key_ordinals(self, sql: str, names: list[str]) -> tuple[list[int], list[dict]]:
        """Key columns by name -> the ordinals the engine takes, against its own schema."""
        from core import authoring

        checked = self.validate(sql)
        if not checked["valid"]:
            first = checked["diagnostics"][0] if checked["diagnostics"] else {}
            raise ServiceError(first.get("message") or "the query does not validate",
                               status=400, code=first.get("code"))
        try:
            return authoring.output_ordinals(checked["output_fields"], names), checked["output_fields"]
        except KeyError as exc:
            raise ServiceError(str(exc.args[0]), status=400) from exc


class ViewService:
    """A registered query's view, as an application developer consumes it."""

    def __init__(self, engine: Engine, queries: QueryService, authoring: AuthoringService,
                 row_limit: int = 500) -> None:
        self._engine = engine
        self._queries = queries
        self._authoring = authoring
        self._limit = row_limit

    def describe(self, name: str) -> dict:
        """The view as the engine describes it, without reading it: schema, key, retention,
        sink and fingerprint (``GET /api/v1/views/{name}`` via the SDK).

        This replaced re-validating the query's SQL to guess the view's columns -- which
        answered "what would this SQL produce now", not "what does the running view hold" --
        and, failing that, reading the whole view to keep only its header.
        """
        try:
            return self._engine.describe_view(name)
        except Exception as exc:
            raise _refusal(exc) from exc

    def schema(self, name: str) -> list[dict]:
        """The view's columns, from the engine's description of the view itself."""
        return list(self.describe(name).get("schema") or [])

    def lookup(self, name: str, filters: dict[str, Any]) -> dict:
        """A point query: ``SELECT * FROM view WHERE col = ? AND ...``, parameterised.

        Column names are checked against the view's own schema and the values bound as
        parameters, so nothing typed into the form is ever spliced into SQL.
        """
        from core.snippets import IDENTIFIER, _typed

        if not IDENTIFIER.match(name or ""):
            raise ServiceError(f"'{name}' is not a view name this console can query", status=400)
        known = {str(f.get("name")).lower(): str(f.get("name")) for f in self.schema(name)}
        clauses, values = [], []
        for column, value in filters.items():
            if value is None or str(value) == "":
                continue
            actual = known.get(str(column).lower())
            if actual is None:
                raise ServiceError(f"'{column}' is not a column of {name}", status=400)
            clauses.append(f"{actual} = ?")
            values.append(_typed(str(value)))
        sql = f"SELECT * FROM {name}" + (" WHERE " + " AND ".join(clauses) if clauses else "")
        started = time.monotonic()
        try:
            columns, rows, types = self._engine.query_typed(sql, values or None)
        except Exception as exc:
            raise _refusal(exc) from exc
        return {"sql": sql, "parameters": jsonable(values), "columns": columns, "types": types,
                "rows": jsonable(rows[: self._limit]), "truncated": len(rows) > self._limit,
                "returned": min(len(rows), self._limit),
                "took_ms": round((time.monotonic() - started) * 1000, 1)}


class OpsService:
    """The operations dashboard's data: parsed metrics, node status, and a verdict."""

    def __init__(self, engine: Engine, queries: QueryService, feeds: Broadcaster,
                 lag_warn_seconds: float = 300.0) -> None:
        from core.metrics import MetricsHistory

        self._engine = engine
        self._queries = queries
        self._feeds = feeds
        self._lag_warn = lag_warn_seconds
        self.history = MetricsHistory(engine.prometheus)
        self._status_lock = threading.Lock()
        self._status: tuple[float, dict] | None = None

    def _node_status(self) -> dict:
        with self._status_lock:
            if self._status and time.monotonic() - self._status[0] < 5:
                return self._status[1]
        try:
            status = {"available": True, **self._engine.status()}
        except Exception as exc:  # noqa: BLE001 -- rendered, not raised
            status = {"available": False, "error": str(exc)}
        with self._status_lock:
            self._status = (time.monotonic(), status)
        return status

    def snapshot(self) -> dict:
        from core import metrics
        from core.metrics import QUERY_METERS

        scraped = self.history.snapshot()
        try:
            registered = {q.name: q for q in self._queries.find(limit=QueryService.MAX_LIMIT).items}
            registry_error = None
        except ServiceError as exc:
            registered, registry_error = {}, str(exc)
        states = {name: q.state for name, q in registered.items()}
        per_query = []
        names = sorted(set(scraped["queries"]) | set(registered))
        for name in names:
            # Every meter present as a key, None when unpublished, so a screen can tell
            # "not published" from "zero" without guarding each lookup.
            numbers: dict[str, Any] = {key: None for key in QUERY_METERS.values()}
            for derived in metrics.DERIVED_METERS:
                numbers[derived] = None
            numbers.update(scraped["queries"].get(name) or {})
            numbers["name"] = name
            query = registered.get(name)
            numbers["state"] = query.state if query else None
            numbers["shared"] = query.shared if query else False
            numbers["fingerprint"] = query.fingerprint if query else None
            if numbers.get("rows_in") is None and query is not None:
                numbers["rows_in"] = query.rows_in
            numbers["metrics_published"] = name in scraped["queries"]
            per_query.append(numbers)
        found = metrics.findings(scraped["queries"], lag_warn_seconds=self._lag_warn,
                                 registered_states=states)
        # A query the registry lists as FAILED but the metrics have not caught up with yet
        # (they reconcile every fifteen seconds) is still a finding.
        for name, state in states.items():
            if state == "FAILED" and not any(f.query == name for f in found):
                found.insert(0, metrics.Finding("critical", name, "Not running",
                                                f"{name} is FAILED. Open it to see why."))
        return {
            "at": scraped["at"],
            "metrics": {"reachable": scraped["reachable"], "error": scraped["error"]},
            "registry": {"reachable": registry_error is None, "error": registry_error},
            "node": {**scraped["node"], "status": self._node_status()},
            "queries": per_query,
            "findings": [f.as_dict() for f in found],
            "verdict": metrics.verdict(scraped["reachable"] or registry_error is None, found,
                                       len(names)),
            "console": {"upstream_subscriptions": self._feeds.live_feeds()},
            "not_exposed": metrics.NOT_EXPOSED,
        }

    def series(self, metric: str) -> dict:
        from core.metrics import DERIVED_METERS, QUERY_METERS

        allowed = set(QUERY_METERS.values()) | set(DERIVED_METERS)
        if metric not in allowed:
            raise ServiceError(f"'{metric}' is not a per-query metric this console charts",
                               status=400)
        return {"metric": metric, "series": self.history.series(metric)}


#: What the engine does not publish about a plugin, named on the plugins screen instead of
#: guessed. Each entry is (what, the engine API that would answer it).
PLUGINS_NOT_EXPOSED: list[tuple[str, str]] = [
    ("What a plugin declares it can be — a source, a sink, both — before anything binds it",
     "the plugin manifest's declared kinds on GET /api/v1/status (or GET /api/v1/plugins)"),
    ("The API version a plugin was built against, and whether it is compatible",
     "PluginManifest.requiredApiVersion in the same answer"),
    ("The settings a plugin accepts (names and descriptions, never values)",
     "PluginManifest.configSchema keys in the same answer"),
    ("Per-plugin throughput, errors and last activity",
     "pravaha_plugin_* meters on /actuator/prometheus"),
]


class PluginService:
    """The plugins the engine has loaded, joined with what binds them.

    Three published answers, and nothing inferred beyond them: ``GET /api/v1/status`` names each
    registered plugin with its version and health; each stream in the catalog names the plugin
    that feeds it (``source``); each sink binding names the plugin that writes it and what that
    binding accepts. A plugin's *declared* capabilities are in its manifest, which the engine does
    not publish, so "source" and "sink" here mean "bound as", never "able to be".
    """

    def __init__(self, engine: Engine, catalog: CatalogService) -> None:
        self._engine = engine
        self._catalog = catalog

    def inventory(self) -> dict:
        errors: dict[str, str] = {}
        try:
            status = self._engine.status()
        except Exception as exc:  # noqa: BLE001 -- the screen names which call failed
            status, errors["status"] = {}, str(exc)
        try:
            streams = self._catalog.streams()
        except ServiceError as exc:
            streams, errors["streams"] = [], str(exc)
        try:
            sinks = self._catalog.sinks()
        except ServiceError as exc:
            sinks, errors["sinks"] = [], str(exc)

        plugins: dict[str, dict[str, Any]] = {}

        def entry(name: str, registered: bool) -> dict[str, Any]:
            key = name.lower()
            if key not in plugins:
                plugins[key] = {"name": name, "version": None, "health": None, "detail": "",
                                "registered": registered, "sources": [], "sinks": []}
            return plugins[key]

        for plugin in status.get("plugins") or []:
            found = entry(str(plugin.get("name", "")), True)
            found.update(version=plugin.get("version"), health=plugin.get("health"),
                         detail=plugin.get("detail") or "", registered=True)
        for stream in streams:
            if stream.get("source"):
                entry(str(stream["source"]), False)["sources"].append(
                    {"name": stream.get("name"), "event_time": stream.get("eventTime"),
                     "lateness": stream.get("outOfOrderness"),
                     "columns": len(stream.get("fields") or [])})
        for sink in sinks:
            if sink.get("plugin"):
                entry(str(sink["plugin"]), False)["sinks"].append(
                    {"name": sink.get("name"), "emit_modes": list(sink.get("emitModes") or []),
                     "accepts_retractions": bool(sink.get("acceptsRetractions")),
                     "guarantee": sink.get("guarantee"), "writers": list(sink.get("writers") or []),
                     "problem": sink.get("problem")})
        items = sorted(plugins.values(), key=lambda p: (not p["registered"], p["name"].lower()))
        for item in items:
            item["bound_as"] = [kind for kind, key in (("source", "sources"), ("sink", "sinks")) if item[key]]
            item["healthy"] = str(item["health"] or "").upper() in {"HEALTHY", "UP", "OK"}
        return {
            "available": "status" not in errors,
            "node": {"instance": status.get("instanceId"), "version": status.get("version"),
                     "state": status.get("engineState")},
            "plugins": items,
            "errors": errors,
            "not_exposed": [{"what": what, "needs": needs} for what, needs in PLUGINS_NOT_EXPOSED],
        }


class Services:
    """Everything the API layer needs, constructed once."""

    def __init__(self, engine: Engine, *, row_limit: int = 500,
                 lag_warn_seconds: float = 300.0) -> None:
        self.engine = engine
        self.health = HealthService(engine)
        self.queries = QueryService(engine)
        self.adhoc = AdHocService(engine)
        self.feeds = Broadcaster(engine)
        self.catalog = CatalogService(engine)
        self.authoring = AuthoringService(engine, self.catalog)
        self.views = ViewService(engine, self.queries, self.authoring, row_limit)
        self.ops = OpsService(engine, self.queries, self.feeds, lag_warn_seconds)
        self.plugins = PluginService(engine, self.catalog)
