"""The console's only way of reaching the engine: the published Python SDK.

Every call in here goes through ``pravaha``, the same client an integrator uses -- Flight for
queries, registration, lifecycle and subscriptions, and the SDK's own calls to the engine's
published, versioned REST endpoints for the catalogue, validation, plans, sinks, per-query
and per-view descriptions, node status and the Prometheus text. That is the point of
ADR-024 rather than an incidental choice: a console that reached into the engine would be a
console whose API boundary is enforced by a test, and a test can be waived by whoever is
under deadline pressure that week. A separate process speaking only the SDK simply cannot.

The console is also the SDK's first real consumer: an awkward corner of the client API
becomes an awkward corner of the console, where somebody notices, instead of being
discovered by an integrator. This module used to hold its own ``urllib`` client for the
REST half because the SDK spoke Flight only; the SDK grew those calls, and the console
stopped being the one place that spoke HTTP to the engine.
"""
from __future__ import annotations

import dataclasses
import threading
from collections.abc import Iterator, Sequence

from pravaha import connect
from pravaha.client import QueryError

try:  # the SDK's REST error; absent only from an SDK older than this console
    from pravaha import ApiError
except ImportError:  # pragma: no cover
    ApiError = None  # type: ignore[assignment,misc]


@dataclasses.dataclass(frozen=True)
class QueryRow:
    """One registered continuous query, as the console shows it."""

    name: str
    state: str
    sql: str
    fingerprint: str
    rows_in: int
    #: The view's key as output ordinals; empty when the engine did not say.
    key_columns: tuple = ()
    #: The sink binding the query also writes to, or ``None``.
    sink: str | None = None
    #: The view's retention, ISO-8601 or ``"forever"``; ``None`` when the engine did not say.
    retention: str | None = None
    #: Whether rows still reach it (FEED-1): ``RUNNING``, ``PAUSED``, ``STOPPED`` or ``NONE``;
    #: ``None`` from an engine that predates it.
    feed: str | None = None
    #: The first stopped source's code (``PRV-5092`` or the source's own), or ``None``.
    feed_code: str | None = None
    #: What it said, or the engine's note that the text is withheld from this identity.
    feed_message: str | None = None
    #: ``stream#partition`` of the source that stopped.
    feed_where: str | None = None
    #: When it stopped, ISO-8601.
    feed_at: str | None = None

    @property
    def source_stopped(self) -> bool:
        """A source failed mid-read: the query says ``RUNNING`` and its view has stopped moving."""
        return self.feed == "STOPPED"

    @property
    def shared(self) -> bool:
        """Whether some other name shares this computation.

        Filled in by :meth:`Engine.queries`, which can see the whole list. Worth surfacing
        because "ten analysts on one dashboard cost one query" is the claim, and an
        operator should be able to see it holding.
        """
        return getattr(self, "_shared", False)


def _feed_of(query) -> dict:
    """The feed fields of an SDK listing row, empty from an SDK that predates them (FEED-1)."""
    stop = getattr(query, "feed_stop", None)
    return {
        "feed": getattr(query, "feed", None),
        "feed_code": getattr(stop, "code", None) or None,
        "feed_message": getattr(stop, "message", None) or None,
        "feed_where": getattr(stop, "where", None) or None,
        "feed_at": getattr(stop, "at", None) or None,
    }


def _replacement(status) -> dict:
    """An SDK :class:`pravaha.client.Replacement` as the shape the engine's REST API uses.

    One shape above this module whichever transport answered, and the names the API
    documents (``rollbackUntil``, ``backfill.historyRows``) rather than two spellings of
    each. ``history`` is ``None`` -- not ``[]`` -- because the status the SDK returns does
    not carry it: "not known" and "nobody has served this name before" are different
    answers and the screen shows them differently.
    """
    lag_nanos = getattr(status, "lag_nanos", None)
    return {
        "name": getattr(status, "name", ""),
        "state": getattr(status, "state", ""),
        "sql": getattr(status, "sql", ""),
        "candidate": getattr(status, "candidate", None),
        "replacing": getattr(status, "replacing", None),
        "sink": getattr(status, "sink", None),
        "options": getattr(status, "options", "") or "",
        "owner": getattr(status, "owner", None),
        "startedAt": getattr(status, "started_at", None),
        "cutOverAt": getattr(status, "cut_over_at", None),
        "rollbackUntil": getattr(status, "rollback_until", None),
        "rollbackAvailable": bool(getattr(status, "rollback_available", False)),
        "backfill": {
            "historyRows": int(getattr(status, "history_rows", 0) or 0),
            "liveRows": int(getattr(status, "live_rows", 0) or 0),
            "rowsPerSecond": float(getattr(status, "rows_per_second", 0) or 0),
            "partitions": int(getattr(status, "partitions", 0) or 0),
            "partitionsLive": int(getattr(status, "partitions_live", 0) or 0),
            "historyComplete": bool(getattr(status, "history_complete", False)),
            "rateLimit": int(getattr(status, "rate_limit", 0) or 0),
            "paused": bool(getattr(status, "paused", False)),
            "lagSeconds": None if lag_nanos is None else float(lag_nanos) / 1e9,
        },
        "history": None,
        "failure": ({"code": getattr(status, "failure_code", None) or None,
                     "message": getattr(status, "failure", None) or ""}
                    if getattr(status, "failure", None) or getattr(status, "failure_code", None)
                    else None),
    }


class EngineHttpError(Exception):
    """The engine's HTTP API refused (``status`` is its HTTP status), or did not answer (0).

    ``code`` is the engine's ``PRV-nnnn`` when it gave one. The console's own error type, so
    nothing above this module needs to know which SDK exception carried the refusal.
    """

    def __init__(self, status: int, message: str, code: str | None = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code


def _translated(exc: Exception) -> Exception:
    """An SDK REST refusal as an :class:`EngineHttpError`; anything else unchanged."""
    if ApiError is not None and isinstance(exc, ApiError):
        return EngineHttpError(int(getattr(exc, "status", 0) or 0),
                               str(getattr(exc, "message", None) or exc),
                               getattr(exc, "engine_code", None))
    return exc


class Engine:
    """A connection to one Pravaha server.

    Not a connection pool. The console is an operator tool with one server in view, and a
    pool would add a failure mode nobody is watching for the sake of concurrency nobody
    needs at this scale.
    """

    def __init__(self, url: str, token: str | None = None, http_url: str | None = None,
                 http_timeout: float = 5.0) -> None:
        self._url = url
        self._token = token
        self._lock = threading.Lock()
        # The engine's HTTP surface is a separate port from Flight -- 8080 against 9090 -- and
        # conflating the two is the commonest way a first run fails, so it is its own setting.
        self._http = (http_url or "").rstrip("/")
        self._http_timeout = http_timeout

    @property
    def url(self) -> str:
        return self._url

    @property
    def http_url(self) -> str:
        return self._http

    def _client(self):
        from pravaha.options import ClientOptions

        kwargs: dict = {}
        if self._token:
            kwargs["token"] = self._token
            kwargs["allow_insecure_token"] = True
        if self._http:
            kwargs["http_url"] = self._http
            kwargs["request_timeout_seconds"] = self._http_timeout
        if not kwargs:
            return connect(self._url)
        return connect(options=ClientOptions.create(self._url, **kwargs))

    def _rest(self, call):
        """Runs ``call(client)`` against the engine's REST surface through the SDK."""
        if not self._http:
            raise EngineHttpError(0, "no engine HTTP URL is configured (engine.http_url)")
        try:
            with self._client() as client:
                return call(client)
        except Exception as exc:
            translated = _translated(exc)
            if translated is exc:
                raise
            raise translated from exc

    # ------------------------------------------------------------------ Flight

    def health(self) -> dict:
        """Whether the engine answers at all, and what it is running."""
        try:
            with self._client() as client:
                queries = client.queries()
            return {"reachable": True, "url": self._url, "queries": len(queries)}
        except Exception as exc:  # noqa: BLE001 -- deliberate: see below
            # Reported rather than raised: a console whose own page 500s when the engine is
            # down is a console that cannot tell you the engine is down.
            return {"reachable": False, "url": self._url, "error": str(exc)}

    def queries(self) -> list[QueryRow]:
        with self._client() as client:
            registered = client.queries()

        counts: dict[str, int] = {}
        for query in registered:
            counts[query.fingerprint] = counts.get(query.fingerprint, 0) + 1

        rows = []
        for query in registered:
            row = QueryRow(
                name=query.name,
                state=query.state,
                sql=query.sql,
                fingerprint=query.fingerprint,
                rows_in=query.rows_in,
                key_columns=tuple(getattr(query, "key_columns", ()) or ()),
                sink=getattr(query, "sink", None),
                retention=getattr(query, "retention", None),
                **_feed_of(query),
            )
            # Two names on one fingerprint are one computation with one copy of the state.
            object.__setattr__(row, "_shared", counts[query.fingerprint] > 1)
            rows.append(row)
        return rows

    def register(self, name: str, sql: str, keys: Sequence[int],
                 sink: str | None = None, retention: str | None = None) -> QueryRow:
        with self._client() as client:
            registered = client.register(name, sql, list(keys), sink=sink or None,
                                         retention=retention or None)
        return QueryRow(
            name=registered.name,
            state=registered.state,
            sql=sql,
            fingerprint=registered.fingerprint,
            rows_in=0,
            key_columns=tuple(keys),
            sink=sink or None,
            retention=retention or None,
        )

    def lifecycle(self, action: str, name: str) -> None:
        with self._client() as client:
            if action == "pause":
                client.pause(name)
            elif action == "resume":
                client.resume(name)
            elif action == "drop":
                client.drop(name)
            else:
                raise QueryError(f"unknown action '{action}'")

    def query(self, sql: str, parameters: Sequence[object] | None = None) -> tuple[list[str], list[list]]:
        columns, rows, _types = self.query_typed(sql, parameters)
        return columns, rows

    def query_typed(self, sql: str, parameters: Sequence[object] | None = None
                    ) -> tuple[list[str], list[list], list[str]]:
        """The answer, plus each column's Arrow type -- which the result grid aligns by."""
        with self._client() as client:
            result = client.query(sql, list(parameters) if parameters else None)
            types = [str(field.type) for field in result.schema]
            rows = [[row[name] for name in result.columns] for row in result]
            return list(result.columns), rows, types

    def tail(self, view: str, filters: dict | None = None) -> Iterator[dict]:
        """Yields one dict per changed row, for as long as the caller keeps reading.

        Held open deliberately: a console that polled would show an operator a number that
        is always a little out of date, and the whole product claim is that it does not
        have to be.

        Each row carries ``_weight``: +1 appearing, -1 withdrawn. A retraction without its
        sign reads as a second copy of the row it withdraws, which is the one mistake a live
        view must not invite.
        """
        with self._client() as client:
            for batch in client.subscribe(view, filters or {}):
                for row in batch:
                    values = {name: row[name] for name in row.columns}
                    values["_weight"] = row.weight
                    yield values

    def mirror(self, view: str, filters: dict | None = None) -> Iterator[tuple[str, list, int | None]]:
        """The view as it stands, then every commit after it: ``(kind, rows, frontier)``.

        ``kind`` is ``"snapshot"`` once, first -- every row of the view at a commit, sent even
        when there are none -- and ``"commit"`` after. Rows are dicts carrying ``_weight`` as
        :meth:`tail`'s do. A snapshot subscription (SUB-1): unlike reading the view and then
        tailing it, nothing that commits in between is lost.
        """
        with self._client() as client:
            for batch in client.subscribe(view, filters or {}, snapshot=True):
                rows = []
                for row in batch:
                    values = {name: row[name] for name in row.columns}
                    values["_weight"] = row.weight
                    rows.append(values)
                yield ("snapshot" if batch.snapshot else "commit", rows, batch.frontier)

    # ------------------------------------------------------------------ the SDK's REST calls

    def streams(self) -> list[dict]:
        """Every stream this principal may read, with fields, event time, lateness and source."""
        return list(self._rest(lambda c: c.streams()) or [])

    def declare_stream(self, name: str, schema: str, event_time: str | None = None,
                       out_of_orderness: str | None = None) -> dict:
        """Declares a stream from ``name:TYPE,...``, optionally with its event time and lateness."""
        return dict(self._rest(lambda c: c.declare_stream(
            name, schema, event_time=event_time or None,
            out_of_orderness=out_of_orderness or None)) or {})

    def validate(self, sql: str) -> dict:
        """Valid, diagnostics (with the parser's own positions), output fields, elapsed."""
        return dict(self._rest(lambda c: c.validate(sql)) or {})

    def explain(self, sql: str, level: str = "physical") -> dict:
        """The plan as text, and as the engine's own graph of operators."""
        return dict(self._rest(lambda c: c.explain(sql, level, graph=True)) or {})

    def sinks(self) -> list[dict]:
        """The sink bindings this principal may see: what each accepts, and who writes to it."""
        return list(self._rest(lambda c: c.sinks()) or [])

    def describe_queries(self) -> list[dict]:
        """Every registered query this identity may see, described in full."""
        return list(self._rest(lambda c: c.describe_queries()) or [])

    def describe_query(self, name: str) -> dict:
        """One registered query: keys, retention, sink state, shared names, counts."""
        return dict(self._rest(lambda c: c.describe_query(name)) or {})

    def dead_letters(self, name: str, offset: int = 0, limit: int = 50) -> dict:
        """A page of the records a query's feed could not decode, newest first, with the
        queue's totals. An entry whose ``raw`` is null carries ``withheld`` saying why: the
        engine decides, by the view's own rules, whether this identity may see the record."""
        return dict(self._rest(lambda c: c.dead_letters_http(name, offset=offset, limit=limit)) or {})

    def replay_dead_letters(self, name: str, ids: list[str]) -> dict:
        """Feeds chosen dead letters back through the query. A new row at its current
        frontier, not a rewind; a record that fails again goes back on the queue."""
        return dict(self._rest(lambda c: c.replay_dead_letters_http(name, ids)) or {})

    def query_plan(self, name: str) -> dict:
        """The plan a registered query is running, with the totals the engine measures."""
        return dict(self._rest(lambda c: c.query_plan(name)) or {})

    def describe_view(self, name: str) -> dict:
        """A view's schema, key, retention, sink and fingerprint, without reading it."""
        return dict(self._rest(lambda c: c.describe_view(name)) or {})

    # ------------------------------------------------- blue/green replacement (ADR-046)
    #
    # Through the SDK's published actions, like everything else here. The engine also serves
    # these over REST (``/api/v1/replacements``, ``/api/v1/queries/{name}/replacement``), and
    # that answer carries one field these do not -- ``history``, who served the name from
    # which seam. The SDK has no call for it, so the console does not have it either and the
    # screen says so rather than drawing a version history it guessed.

    def replacements(self) -> list[dict]:
        """Every replacement this engine knows about, in flight or finished."""
        return [_replacement(r) for r in self._flight(lambda c: c.replacements()) or []]

    def replacement(self, name: str) -> dict | None:
        """How the replacement of ``name`` is getting on, or ``None`` when there is not one."""
        found = self._flight(lambda c: c.replacement(name))
        return _replacement(found) if found is not None else None

    def start_replacement(self, name: str, sql: str, keys: Sequence[int], *,
                          backfill: str | None = None, rate_limit: int | None = None,
                          cutover: str | None = None,
                          rollback_retention: str | None = None) -> dict:
        """Starts a candidate beside the running version. The name still answers the old one."""
        return _replacement(self._flight(lambda c: c.replace(
            name, sql, list(keys), backfill=backfill, rate_limit=rate_limit,
            cutover=cutover, rollback_retention=rollback_retention)))

    def cut_over(self, name: str) -> dict:
        """Moves the name to the candidate, at a position both have consumed exactly."""
        return _replacement(self._flight(lambda c: c.cut_over(name)))

    def roll_back(self, name: str) -> dict:
        """Puts the replaced version back, while it is still retained."""
        return _replacement(self._flight(lambda c: c.roll_back(name)))

    def finish_replacement(self, name: str) -> dict:
        """Confirms a cutover: the replaced version is released, and there is no rollback."""
        return _replacement(self._flight(lambda c: c.finish_replacement(name)))

    def abandon_replacement(self, name: str) -> dict:
        """Ends a replacement that has not cut over, releasing the candidate."""
        return _replacement(self._flight(lambda c: c.abandon_replacement(name)))

    def throttle_backfill(self, name: str, records_per_second: int) -> dict:
        """Lowers how fast the backfill reads history; the ceiling it started with is its top."""
        return _replacement(self._flight(lambda c: c.throttle_backfill(name, records_per_second)))

    def pause_backfill(self, name: str) -> dict:
        return _replacement(self._flight(lambda c: c.pause_backfill(name)))

    def resume_backfill(self, name: str) -> dict:
        return _replacement(self._flight(lambda c: c.resume_backfill(name)))

    def _flight(self, call):
        """``call(client)`` over Flight, with the SDK's refusal translated like a REST one."""
        try:
            with self._client() as client:
                return call(client)
        except Exception as exc:
            translated = _translated(exc)
            if translated is exc:
                raise
            raise translated from exc

    def status(self) -> dict:
        """Node, version, engine state, plugin health."""
        return dict(self._rest(lambda c: c.status()) or {})

    def prometheus(self) -> str:
        """The Prometheus text exposition format, unparsed."""
        return str(self._rest(lambda c: c.metrics_text()) or "")

    def plugins(self) -> list[dict]:
        """Every plugin the node can load: manifest, declared capabilities, visible bindings."""
        return list(self._rest(lambda c: c.plugins()) or [])

    def audit(self, **filters) -> dict:
        """One page of the recorded authorization decisions, newest first; 403 unless permitted."""
        wanted = {k: v for k, v in filters.items() if v not in (None, "")}
        return dict(self._rest(lambda c: c.audit(**wanted)) or {})

    def permissions(self) -> dict:
        """What the engine's policy lets the console's identity do."""
        return dict(self._rest(lambda c: c.permissions()) or {})
