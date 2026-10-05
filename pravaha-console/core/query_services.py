"""The services over registered queries: health, the query list, ad-hoc SQL.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Split out of ``core.services`` (CONSOLESIZE-1), which re-exports every name here.
"""
from __future__ import annotations

import dataclasses
import threading
import time
from typing import Any

from core.engine import Engine, QueryRow
from core.service_base import QUOTA_CODES, ServiceError, _code_in, _refusal, jsonable


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
    #: Whether rows still reach it, and why not (FEED-1); see :class:`core.engine.QueryRow`.
    feed: str | None = None
    feed_code: str | None = None
    feed_message: str | None = None
    feed_where: str | None = None
    feed_at: str | None = None

    @property
    def source_stopped(self) -> bool:
        return self.feed == "STOPPED"

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

    def readiness(self) -> dict:
        """Whether the engine can serve, asked without a credential (CONSOLEREADY-1); see
        ``Engine.readiness``. An engine adapter without that probe answers with :meth:`health`."""
        probe = getattr(self._engine, "readiness", None)
        if callable(probe):
            return dict(probe())
        health = self.health().as_dict()
        return {**health, "ready": bool(health.get("reachable"))}


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
            code = getattr(exc, "code", None) or _code_in(str(exc))
            # A quota refusal is a conflict with what the tenant already holds, not a fault in
            # the request, so it is the engine's own 409 (ADR-050 section 4) here too.
            status = 409 if code in QUOTA_CODES else 400
            raise ServiceError(str(exc), status=status, code=code) from exc
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
            feed=getattr(row, "feed", None),
            feed_code=getattr(row, "feed_code", None),
            feed_message=getattr(row, "feed_message", None),
            feed_where=getattr(row, "feed_where", None),
            feed_at=getattr(row, "feed_at", None),
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
