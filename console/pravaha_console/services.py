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

from pravaha_console.engine import Engine, QueryRow


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

    def as_dict(self) -> dict:
        return dataclasses.asdict(self)


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

    def register(self, name: str, sql: str, keys: list[int]) -> Query:
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
            row = self._engine.register(name.strip(), sql.strip(), keys)
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
            columns, rows = self._engine.query(sql, parameters)
        except Exception as exc:
            raise ServiceError(str(exc), status=400, code=_code_in(str(exc))) from exc
        took_ms = round((time.monotonic() - started) * 1000, 1)
        truncated = len(rows) > limit
        return {
            "columns": columns,
            # Truncated here rather than in the browser: a query that returns a million
            # rows should not be able to make the console the reason the tab dies.
            "rows": rows[:limit],
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


class Services:
    """Everything the API layer needs, constructed once."""

    def __init__(self, engine: Engine) -> None:
        self.engine = engine
        self.health = HealthService(engine)
        self.queries = QueryService(engine)
        self.adhoc = AdHocService(engine)
        self.feeds = Broadcaster(engine)
