"""The console's only way of reaching the engine: the published Python SDK.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

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

import contextlib
import dataclasses
import json
import logging
import threading
import urllib.parse
from collections.abc import Iterator, Sequence

from pravaha import connect
from pravaha.client import QueryError

from core import credential

try:  # the SDK's REST error; absent only from an SDK older than this console
    from pravaha import ApiError
except ImportError:  # pragma: no cover
    ApiError = None  # type: ignore[assignment,misc]

logger = logging.getLogger(__name__)


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


def _replacement(status, history: list[dict] | None = None) -> dict:
    """An SDK :class:`pravaha.client.Replacement` as the shape the engine's REST API uses.

    One shape above this module whichever transport answered, and the names the API
    documents (``rollbackUntil``, ``backfill.historyRows``) rather than two spellings of
    each. ``history`` stays ``None`` -- not ``[]`` -- when the caller did not read one:
    "not known" and "nobody has served this name before" are different answers and the
    screen shows them differently. Each entry is ``{"fromFrontier", "version"}``, the
    engine's ``historyEntries``: the position the version took over at (``None`` for the
    first, which has served from the beginning) and its fingerprint.
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
        "history": None if history is None else list(history),
        "failure": ({"code": getattr(status, "failure_code", None) or None,
                     "message": getattr(status, "failure", None) or ""}
                    if getattr(status, "failure", None) or getattr(status, "failure_code", None)
                    else None),
    }


def _debug_session(session) -> dict:
    """An SDK :class:`pravaha.debug.DebugSession` as the shape the engine's REST API uses.

    ``sinksDisabled`` is carried even though it is always true (ADR-048: the isolation is three
    absences, not a flag), because the screen has to be able to say so from the answer rather
    than from a sentence somebody wrote into the template.
    """
    return {
        "id": getattr(session, "id", ""),
        "query": getattr(session, "query", ""),
        "sql": getattr(session, "sql", ""),
        "checkpointId": int(getattr(session, "checkpoint_id", 0) or 0),
        "owner": getattr(session, "owner", "") or "",
        "startedAt": getattr(session, "started_at", "") or "",
        "lastUsedAt": getattr(session, "last_used_at", "") or "",
        "steps": int(getattr(session, "steps", 0) or 0),
        "rowsConsumed": int(getattr(session, "rows_consumed", 0) or 0),
        "viewSize": int(getattr(session, "view_size", 0) or 0),
        "watermarkNanos": getattr(session, "watermark_nanos", None),
        "sinksDisabled": bool(getattr(session, "sinks_disabled", True)),
        "streams": list(getattr(session, "streams", ()) or ()),
    }


def _debug_step(step) -> dict:
    """An SDK :class:`pravaha.debug.DebugStep` as the shape the engine's REST API uses.

    Four answers at once, which is what makes a wrong row explainable (ADR-048 4a): what came
    in, what every operator did with it, what the view did, and where event time stands.
    """
    return {
        "session": getattr(step, "session", ""),
        "sequence": int(getattr(step, "sequence", 0) or 0),
        "kind": getattr(step, "kind", "") or "",
        "rowsIn": [{"stream": row.stream, "partition": int(row.partition),
                    "offset": str(row.offset), "weight": int(row.weight),
                    "eventTimeNanos": int(row.event_time_nanos),
                    "values": list(row.values)}
                   for row in getattr(step, "rows_in", ()) or ()],
        "operators": [{"id": op.id, "kind": op.kind, "label": op.label,
                       "rowsIn": int(op.rows_in), "rowsOut": int(op.rows_out)}
                      for op in getattr(step, "operators", ()) or ()],
        "viewChanges": [{"weight": int(change.weight), "values": list(change.values)}
                        for change in getattr(step, "view_changes", ()) or ()],
        "watermarkNanos": getattr(step, "watermark_nanos", None),
        "rowsConsumed": int(getattr(step, "rows_consumed", 0) or 0),
        "viewSize": int(getattr(step, "view_size", 0) or 0),
        "exhausted": bool(getattr(step, "exhausted", False)),
        "stopped": getattr(step, "stopped", "") or "",
    }


def _debug_page(page) -> dict:
    """An SDK :class:`pravaha.debug.StatePage` as the console's shape.

    One departure from the engine's REST answer, and it is deliberate. Over REST a page's
    entries are flat maps with the entry's key merged in under ``"key"``, so an operator
    holding a column of its own called ``key`` has two things under one name and the column
    wins. The console keeps the key beside the columns instead, because a state page exists
    to be read literally and a silently overwritten column is exactly the kind of wrong
    answer somebody opens a debugger to chase.
    """
    return {
        "id": getattr(page, "id", ""),
        "kind": getattr(page, "kind", "") or "",
        "key": getattr(page, "key", None),
        "offset": int(getattr(page, "offset", 0) or 0),
        "limit": int(getattr(page, "limit", 0) or 0),
        "total": int(getattr(page, "total", 0) or 0),
        "hasMore": bool(getattr(page, "has_more", False)),
        "entries": [{"key": entry.key, "values": dict(entry.values or {})}
                    for entry in getattr(page, "entries", ()) or ()],
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


def _refused_credential(exc: Exception) -> bool:
    """Whether the engine answered by refusing the caller's credential (or its absence)."""
    code = credential.code_of(exc)
    if code in credential.EXPIRED_CODES or code in {"PRV-7002", credential.MUST_CHANGE_CODE}:
        return True
    status = getattr(exc, "status", None)
    return status in (401, 403) or credential._unauthenticated(exc)


class Engine:
    """A connection to one Pravaha server.

    Not a connection pool. The console is an operator tool with one server in view, and a
    pool would add a failure mode nobody is watching for the sake of concurrency nobody
    needs at this scale.
    """

    def __init__(self, url: str, http_url: str | None = None,
                 http_timeout: float = 5.0) -> None:
        self._url = url
        self._lock = threading.Lock()
        # The engine's HTTP surface is a separate port from Flight -- 18080 against 19090 -- and
        # conflating the two is the commonest way a first run fails, so it is its own setting.
        self._http = (http_url or "").rstrip("/")
        self._http_timeout = http_timeout

    @property
    def url(self) -> str:
        return self._url

    @property
    def http_url(self) -> str:
        return self._http

    @contextlib.contextmanager
    def _client(self):
        """A client carrying the bearer token of the person this request is for (ADR-052).

        The token is the signed-in person's engine session, bound to the request by the
        console's middleware (``core.credential``); an anonymous request carries none. There is
        no console-wide token to fall back to: a call made for a person is authorised and
        audited as that person, or not made. A refusal of the credential itself is noted on it
        on the way out, so the middleware can send the person back to the sign-in form.
        """
        from pravaha.options import ClientOptions

        kwargs: dict = {}
        bearer = credential.token()
        if bearer:
            kwargs["token"] = bearer
            # The console reaches a loopback or in-cluster engine over plaintext as often as not,
            # and the choice of transport is the deployment's (engine.url), not the person's.
            kwargs["allow_insecure_token"] = True
        if self._http:
            kwargs["http_url"] = self._http
            kwargs["request_timeout_seconds"] = self._http_timeout
        try:
            client = (connect(self._url) if not kwargs
                      else connect(options=ClientOptions.create(self._url, **kwargs)))
            with client as opened:
                yield opened
        except Exception as exc:
            credential.note_refusal(exc)
            raise

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
        # Asked on every page, the public ones included, so it must not end a session: what a
        # page's own calls learn about the credential decides that, not the chrome's probe.
        held = credential.current()
        marks = (held.expired, held.must_change) if held is not None else None
        try:
            with self._client() as client:
                queries = client.queries()
            return {"reachable": True, "url": self._url, "queries": len(queries)}
        except Exception as exc:  # noqa: BLE001 -- deliberate: see below
            # An engine that refused the credential (a visitor with none, a session that has
            # ended) answered: it is reachable, and what it holds is not this caller's to count.
            if _refused_credential(exc):
                return {"reachable": True, "url": self._url, "queries": 0}
            # Reported rather than raised: a console whose own page 500s when the engine is
            # down is a console that cannot tell you the engine is down.
            return {"reachable": False, "url": self._url, "error": str(exc)}
        finally:
            if held is not None and marks is not None:
                held.expired, held.must_change = marks

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
    # The numbers come through the SDK's published Flight actions, like everything else here.
    # One field is not on that wire: ``history``, the versions that have served this name and
    # the frontier each took over at. A control-wire row is a flat list of strings and a list
    # of sentences does not fit in one, so the engine answers it over REST and the SDK reads
    # it with ``replacement_http``.

    def replacements(self) -> list[dict]:
        """Every replacement this engine knows about, in flight or finished.

        Without each one's ``history``: this is the list, and nothing renders a version
        history from a list. The screen that shows one asks for one name.
        """
        return [_replacement(r) for r in self._flight(lambda c: c.replacements()) or []]

    def replacement(self, name: str) -> dict | None:
        """How the replacement of ``name`` is getting on, or ``None`` when there is not one.

        Two calls, and the second one's failure is not the first one's. The Flight action
        answers the state and the backfill's numbers; the REST call answers the version
        history. They are two moments, which ADR-046 refuses for the numbers -- a screen that
        asked three times would show three moments of a job that moves every second -- and
        which costs nothing here, because the history is append-only: an answer a second old
        is a prefix of the current one, never a different one.

        A history the engine will not or cannot give stays ``None`` and the screen draws its
        partial state. That is the honest answer and not a fallback: the console does not
        invent a list, and it does not fail a screen whose subject -- a backfill in flight --
        it has in hand.
        """
        found = self._flight(lambda c: c.replacement(name))
        if found is None:
            return None
        return _replacement(found, self._replacement_history(name))

    def _replacement_history(self, name: str) -> list[dict] | None:
        """The versions that have served ``name``, oldest first, or ``None`` if unread.

        Read from ``historyEntries``, the trail in parts (RPL-1), so the screen formats the
        position and shows the fingerprint itself. An engine that answers only the sentences
        in ``history`` has not given the parts, and that is "not carried" rather than a guess
        at splitting its wording."""
        if not self._http:
            return None
        try:
            answer = self._rest(lambda c: c.replacement_http(name))
        except Exception as exc:  # noqa: BLE001 -- reported as "not carried", never as a screen
            logger.info("the version history of '%s' was not answered: %s", name, exc)
            return None
        carried = answer.get("historyEntries")
        if carried is None:
            if answer.get("history") is not None:
                logger.info("the engine answered the version history of '%s' without its "
                            "historyEntries, so it is shown as not carried", name)
            return None
        return [{"fromFrontier": None if entry.get("fromFrontier") is None
                 else int(entry["fromFrontier"]),
                 "version": str(entry.get("version") or "")} for entry in carried]

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

    # --------------------------------------------- the time-travel debugger (ADR-048)
    #
    # Nine Flight actions, all of them request/response: a step is asked for and answered,
    # which is why design 23.11's WebSocket for the debugger is reserved and not built. The
    # console holds no session state of its own -- the id is in the URL and the engine owns
    # everything behind it -- so a reload, a second tab and a pasted link all show the same
    # session, and closing the browser leaves nothing for the console to clean up.

    def debug_checkpoints(self, name: str) -> list[int]:
        """The checkpoints of ``name`` a session could still fork from, newest first."""
        return [int(c) for c in self._flight(lambda c: c.debug_checkpoints(name)) or []]

    def debug_fork(self, name: str, checkpoint_id: int | None = None) -> dict:
        """Forks ``name`` into a second computation nothing can read, at a checkpoint.

        No checkpoint means the newest retained one, which is the engine's choice and not
        the console's: a node prunes between the list being drawn and the button being
        pressed, and a console that pinned the id it happened to show would fork from a
        position that no longer exists.
        """
        return _debug_session(
            self._flight(lambda c: c.debug_fork(name, checkpoint_id=checkpoint_id)))

    def debug_sessions(self) -> list[dict]:
        """Every debug session on this node that this identity may administer."""
        return [_debug_session(s) for s in self._flight(lambda c: c.debug_sessions()) or []]

    def debug_session(self, session_id: str) -> dict | None:
        """One session's status, or ``None`` when the engine knows no such session."""
        found = self._flight(lambda c: c.debug_session(session_id))
        return _debug_session(found) if found is not None else None

    def debug_step(self, session_id: str, step: str) -> dict:
        """Advances the fork by one step and reports everything it did."""
        return _debug_step(self._flight(lambda c: c.debug_step(session_id, step)))

    def debug_state(self, session_id: str) -> list[dict]:
        """Every piece of operator state the fork holds, with how many entries each has."""
        return [{"id": slot.id, "kind": slot.kind, "label": slot.label,
                 "entries": int(slot.entries)}
                for slot in self._flight(lambda c: c.debug_state(session_id)) or []]

    def debug_inspect(self, session_id: str, operator: str, *, key: str | None = None,
                      offset: int = 0, limit: int = 50) -> dict:
        """One page of one operator's state. Bounded on the way in as well as out."""
        return _debug_page(self._flight(lambda c: c.debug_inspect(
            session_id, operator, key=key, offset=offset, limit=limit)))

    def debug_view(self, session_id: str) -> list[dict]:
        """The fork's whole view, as changes with their weights."""
        return [{"weight": int(change.weight), "values": list(change.values)}
                for change in self._flight(lambda c: c.debug_view(session_id)) or []]

    def debug_export(self, session_id: str, name: str) -> dict:
        """The session as a JUnit fixture: the class name, where it belongs, and its source."""
        fixture = self._flight(lambda c: c.debug_export(session_id, name))
        return {"className": getattr(fixture, "class_name", ""),
                "path": getattr(fixture, "path", ""),
                "source": getattr(fixture, "source", "")}

    def debug_end(self, session_id: str) -> None:
        """Releases the session's second copy of the query."""
        self._flight(lambda c: c.debug_end(session_id))

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

    def tenants(self) -> dict:
        """Each tenant's use against its admission quotas, and its refusals (ADR-050):
        ``GET /api/v1/tenants``, through the SDK's ``Client.tenants``."""
        return dict(self._rest(lambda c: c.tenants()) or {})

    # ------------------------------------------------------------------ identity (ADR-052)
    #
    # The engine is the identity authority: signing in, the person's own account, and the
    # administration of users, keys and sessions are its REST endpoints, called here with the
    # signed-in person's own session token -- or with none, for the sign-in itself. The console
    # verifies nothing: a password, a key and a session are checked by the engine or not at all.

    def _identity(self, method: str, path: str, body: dict | None = None,
                  query: dict | None = None, *, anonymous: bool = False):
        """``method`` on ``/api/v1<path>`` through the SDK's HTTP client, as this request's person
        (or as nobody, for the sign-in), with a refusal as an :class:`EngineHttpError`."""
        if not self._http:
            raise EngineHttpError(0, "no engine HTTP URL is configured (engine.http_url)")
        rest = _IdentityRest(self._http, token=None if anonymous else credential.token(),
                             timeout_seconds=self._http_timeout, allow_insecure_token=True)
        try:
            return rest.send(method, "/api/v1" + path, body, query)
        except Exception as exc:
            if not anonymous:
                credential.note_refusal(exc)
            translated = _translated(exc)
            if translated is exc:
                raise
            raise translated from exc

    def login(self, username: str, password: str) -> dict:
        """``POST auth/login``: ``{token, expiresAt, mustChangePassword, mfa}``, or the engine's
        refusal (``PRV-7010`` credentials refused, ``PRV-7011`` locked)."""
        return dict(self._identity("POST", "/auth/login", {"username": username, "password": password},
                                   anonymous=True) or {})

    def logout(self) -> None:
        self._identity("POST", "/auth/logout", {})

    def me(self) -> dict:
        """``GET auth/me``: the principal and the person's own fields."""
        return dict(self._identity("GET", "/auth/me") or {})

    def change_password(self, current: str, new: str) -> None:
        self._identity("POST", "/auth/password", {"current": current, "new": new})

    def redeem_reset(self, token: str, password: str) -> None:
        """``POST auth/reset/redeem``: single use, and it ends every session of the person."""
        self._identity("POST", "/auth/reset/redeem", {"token": token, "password": password},
                       anonymous=True)

    def users(self) -> list[dict]:
        return _items(self._identity("GET", "/users"), "users")

    def create_user(self, fields: dict) -> dict:
        return dict(self._identity("POST", "/users", fields) or {})

    def update_user(self, username: str, fields: dict) -> dict:
        return dict(self._identity("PATCH", "/users/" + _segment(username), fields) or {})

    def set_roles(self, username: str, roles: list[str]) -> dict:
        return dict(self._identity("PUT", "/users/" + _segment(username) + "/roles",
                                   {"roles": list(roles)}) or {})

    def reset_password(self, username: str) -> dict:
        """``{resetToken, expiresAt}``: the token is in this answer and nowhere else, ever."""
        return dict(self._identity("POST", "/users/" + _segment(username) + "/password-reset", {}) or {})

    def keys(self, all_keys: bool = False) -> list[dict]:
        return _items(self._identity("GET", "/keys", query={"all": "true"} if all_keys else None), "keys")

    def create_key(self, name: str, roles: list[str], expires_days: int,
                   for_user: str | None = None) -> dict:
        """``{key, keyId, expiresAt}``: the key is in this answer and nowhere else, ever."""
        body: dict = {"name": name, "roles": list(roles), "expiresDays": int(expires_days)}
        if for_user:
            body["forUser"] = for_user
        return dict(self._identity("POST", "/keys", body) or {})

    def revoke_key(self, key_id: str) -> None:
        self._identity("DELETE", "/keys/" + _segment(key_id))

    def rotate_key(self, key_id: str) -> dict:
        """``{key, keyId, expiresAt, oldExpiresAt}``: the successor, shown once."""
        return dict(self._identity("POST", "/keys/" + _segment(key_id) + "/rotate", {}) or {})

    def key_report(self) -> dict:
        return dict(self._identity("GET", "/keys/report") or {})

    def sessions(self, all_sessions: bool = False) -> list[dict]:
        return _items(self._identity("GET", "/sessions", query={"all": "true"} if all_sessions else None),
                      "sessions")

    def end_session(self, session_id: str) -> None:
        self._identity("DELETE", "/sessions/" + _segment(session_id))

    # ------------------------------------------------------------------ lanes
    def lanes(self) -> dict:
        """``GET lanes``: the node's lane-sharing mode and how full each shared lane is."""
        return dict(self._identity("GET", "/lanes") or {})

    def lane_placements(self) -> list[dict]:
        """``GET queries``, reduced to where each query runs: name, state, lane, sharedLane."""
        return [{k: q.get(k) for k in ("name", "state", "lane", "sharedLane")}
                for q in (self._identity("GET", "/queries") or []) if isinstance(q, dict)]

    def rebalance(self, dry_run: bool) -> dict:
        """``POST lanes/rebalance``: the plan with ``dry_run``, otherwise the run it starts (admin)."""
        return dict(self._identity("POST", "/lanes/rebalance", {},
                                   {"dryRun": "true"} if dry_run else None) or {})

    def rebalance_status(self) -> dict:
        """``GET lanes/rebalance``: the rebalance running or last run, or what one would do (admin)."""
        return dict(self._identity("GET", "/lanes/rebalance") or {})

    # ------------------------------------------------------------------ the catalogue (ADR-059)
    #
    # Grants live in the engine: every call here is its /api/v1/catalog endpoint, made as the
    # signed-in person, and every rule -- who may see an object, who may grant on it -- is the
    # engine's. A node with the catalogue off answers PRV-7030, and the screens say so.

    def catalog_objects(self, namespace: str | None = None, kind: str | None = None,
                        search: str | None = None) -> list[dict]:
        """``GET catalog/objects``: the objects this person may see, with owner, description, tags."""
        query = {k: v for k, v in (("namespace", namespace), ("kind", kind), ("q", search)) if v}
        return _items(self._identity("GET", "/catalog/objects", query=query or None), "items")

    def catalog_object(self, name: str) -> dict:
        """``GET catalog/objects/{name}``: the object, the grants on it this person may see, and
        what this person may do to it."""
        return dict(self._identity("GET", "/catalog/objects/" + _segment(name)) or {})

    def catalog_namespaces(self) -> list[dict]:
        """``GET catalog/namespaces``: the namespaces this person may use."""
        return _items(self._identity("GET", "/catalog/namespaces"), "items")

    def create_namespace(self, name: str, description: str = "") -> dict:
        """``POST catalog/namespaces``: needs CREATE on the tenant; the person owns it."""
        return dict(self._identity("POST", "/catalog/namespaces",
                                   {"name": name, "description": description}) or {})

    def change_catalog_object(self, name: str, fields: dict) -> dict:
        """``PATCH catalog/objects/{name}``: description, setTags, unsetTags, owner, namespace."""
        return dict(self._identity("PATCH", "/catalog/objects/" + _segment(name), fields) or {})

    def grants(self, on: str | None = None, grantee_type: str | None = None,
               grantee: str | None = None) -> list[dict]:
        """``GET catalog/grants``: on an object, or to a role or user."""
        query: dict = {"object": on} if on else {}
        if grantee:
            query.update({"granteeType": grantee_type or "USER", "grantee": grantee})
        return _items(self._identity("GET", "/catalog/grants", query=query or None), "items")

    def grant(self, on: str, privileges: list[str], grantee_type: str, grantee: str) -> list[dict]:
        """``POST catalog/grants``: needs MANAGE on the object."""
        return _items(self._identity("POST", "/catalog/grants", {
            "object": on, "privileges": list(privileges), "granteeType": grantee_type,
            "grantee": grantee}), "items")

    def revoke(self, on: str, privileges: list[str], grantee_type: str, grantee: str) -> None:
        """``DELETE catalog/grants``: needs MANAGE on the object."""
        self._identity("DELETE", "/catalog/grants", query={
            "object": on, "privileges": ",".join(privileges), "granteeType": grantee_type,
            "grantee": grantee})

    def access(self, user: str, on: str) -> dict:
        """``GET catalog/access``: what ``user`` may do to ``on``, and through which grant."""
        return dict(self._identity("GET", "/catalog/access", query={"user": user, "object": on}) or {})

    # Row filters and masks (ADR-059 section 4): the same door, and the engine checks every
    # expression and every binding.

    def policies(self, on: str | None = None) -> list[dict]:
        """``GET catalog/policies``: the policies this person may see, or those reaching ``on``."""
        return _items(self._identity("GET", "/catalog/policies", query={"object": on} if on else None), "items")

    def create_policy(self, name: str, kind: str, expression: str, column: str = "",
                      except_roles: list[str] | None = None, description: str = "") -> dict:
        """``POST catalog/policies``: a ROW_FILTER or a MASK (naming its column); CREATE on the namespace."""
        body = {"name": name, "kind": kind, "expression": expression, "column": column or None,
                "exceptRoles": list(except_roles or []), "description": description}
        return dict(self._identity("POST", "/catalog/policies", body) or {})

    def bind_policy(self, name: str, on: str = "", tag: str = "") -> dict:
        """``POST catalog/policies/{name}/bindings``: to an object (MANAGE on it) or a tag (MANAGE on the tenant)."""
        return dict(self._identity("POST", "/catalog/policies/" + _segment(name) + "/bindings",
                                   {"object": on or None, "tag": tag or None}) or {})

    def unbind_policy(self, name: str, on: str = "", tag: str = "") -> dict:
        """``DELETE catalog/policies/{name}/bindings``: ``unbound`` says whether it was bound there."""
        query = {key: value for key, value in (("object", on), ("tag", tag)) if value}
        return dict(self._identity("DELETE", "/catalog/policies/" + _segment(name) + "/bindings",
                                   query=query) or {})

    def drop_policy(self, name: str) -> None:
        """``DELETE catalog/policies/{name}``: refused (PRV-7040) while it is bound anywhere."""
        self._identity("DELETE", "/catalog/policies/" + _segment(name))

    # ------------------------------------------------------------------ alerts (ADR-057)
    #
    # Every call is /api/v1/alerts as the signed-in person: who may see an alert (SELECT), pause,
    # snooze or acknowledge it (MODIFY) is the engine's to decide, and its refusals are shown as such.

    def alerts(self) -> list[dict]:
        """``GET alerts``: the alerts this person may see, with how many keys each has firing."""
        return _items(self._identity("GET", "/alerts"), "items")

    def alert(self, name: str) -> dict:
        """``GET alerts/{name}``: the alert, every key it holds, and its recent notifications."""
        return dict(self._identity("GET", "/alerts/" + _segment(name)) or {})

    def alert_channels(self) -> list[dict]:
        """``GET alerts/channels``: the notifier channels the node binds."""
        return _items(self._identity("GET", "/alerts/channels"), "items")

    def pause_alert(self, name: str) -> dict:
        return dict(self._identity("POST", "/alerts/" + _segment(name) + "/pause") or {})

    def resume_alert(self, name: str) -> dict:
        return dict(self._identity("POST", "/alerts/" + _segment(name) + "/resume") or {})

    def snooze_alert(self, name: str, duration: str) -> dict:
        return dict(self._identity("POST", "/alerts/" + _segment(name) + "/snooze", {"duration": duration}) or {})

    def ack_alert(self, name: str, key: str | None = None) -> dict:
        return dict(self._identity("POST", "/alerts/" + _segment(name) + "/ack", {"key": key} if key else {}) or {})


def _segment(value: str) -> str:
    """One path segment, escaped: a username or a key id is never a path of its own."""
    return urllib.parse.quote(str(value), safe="")


def _items(answer, name: str) -> list[dict]:
    """A list the engine answered bare or wrapped (``{"<name>": [...]}`` or ``{"items": [...]}``)."""
    if isinstance(answer, dict):
        answer = answer.get(name, answer.get("items", []))
    return [dict(item) for item in (answer or []) if isinstance(item, dict)]


def _identity_rest_class():
    from pravaha.rest import RestClient

    class IdentityRest(RestClient):
        """The SDK's HTTP client with the verbs the identity endpoints use: its GET and POST, and
        DELETE, PUT and PATCH through the same request path (headers, TLS, error decoding)."""

        def send(self, method: str, path: str, body=None, query=None):
            payload = self._call(method, path, body, query)
            return json.loads(payload.decode("utf-8") or "null")

    return IdentityRest


_IdentityRest = _identity_rest_class()
