"""The operator's services: health over time, plugins, dead letters, replacements, debug.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

Split out of ``core.services`` (CONSOLESIZE-1), which re-exports every name here.
"""
from __future__ import annotations

import threading
import time
from typing import Any

from core.authoring_services import AuthoringService, CatalogService
from core.engine import Engine
from core.feeds import Broadcaster
from core.query_services import QueryService
from core.service_base import ServiceError, _refusal


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
        self._authoring: AuthoringService | None = None
        #: name -> (asked at, the bottleneck node's label or None). Only for a query the
        #: dashboard has already flagged, and at most every BOTTLENECK_TTL seconds: the
        #: snapshot is taken once a second, and reading a plan is a call per query.
        self._bottlenecks: dict[str, tuple[float, str | None]] = {}
        self._bottleneck_lock = threading.Lock()

    #: How long a bottleneck answer is reused. Which operator the time goes into does not
    #: move between two seconds, and the plan endpoint is not free.
    BOTTLENECK_TTL = 15.0

    def with_authoring(self, authoring: AuthoringService) -> OpsService:
        """The plan reader, so a backpressure finding can name the operator (B6)."""
        self._authoring = authoring
        return self

    def _bottleneck(self, name: str) -> str | None:
        """The operator most of this query's sampled time goes into, or ``None``.

        ``None`` covers three things the caller must not conflate with "no bottleneck": the
        plan could not be read, nothing has been sampled yet, and the node runs with
        ``pravaha.metrics.operators`` off. The finding says nothing about an operator in any
        of them rather than naming one it does not have.
        """
        if self._authoring is None:
            return None
        with self._bottleneck_lock:
            cached = self._bottlenecks.get(name)
            if cached is not None and time.monotonic() - cached[0] < self.BOTTLENECK_TTL:
                return cached[1]
        label: str | None = None
        try:
            plan = self._authoring.plan(name)
            node_id = plan.get("bottleneck")
            if node_id:
                node = next((n for n in (plan.get("graph") or {}).get("nodes") or []
                             if n.get("id") == node_id), None)
                label = f"{node['op']} ({node_id})" if node else str(node_id)
        except Exception:  # noqa: BLE001 -- a finding is not worth failing the dashboard for
            label = None
        with self._bottleneck_lock:
            self._bottlenecks[name] = (time.monotonic(), label)
        return label

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
        # FEED-1: a query whose source stopped says RUNNING; this is how the dashboard knows.
        feed_stops = {name: {"code": q.feed_code, "where": q.feed_where}
                      for name, q in registered.items() if q.source_stopped}
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
            numbers["feed"] = query.feed if query else None
            numbers["feed_code"] = query.feed_code if query else None
            numbers["shared"] = query.shared if query else False
            numbers["fingerprint"] = query.fingerprint if query else None
            if numbers.get("rows_in") is None and query is not None:
                numbers["rows_in"] = query.rows_in
            numbers["metrics_published"] = name in scraped["queries"]
            per_query.append(numbers)
        found = metrics.findings(scraped["queries"], lag_warn_seconds=self._lag_warn,
                                 registered_states=states, feed_stops=feed_stops)
        # A query the registry lists as FAILED but the metrics have not caught up with yet
        # (they reconcile every fifteen seconds) is still a finding.
        for name, state in states.items():
            if state == "FAILED" and not any(f.query == name for f in found):
                found.insert(0, metrics.Finding("critical", name, "Not running",
                                                f"{name} is FAILED. Open it to see why."))
        # Likewise a stopped source the metrics have not published yet.
        for name, stop in feed_stops.items():
            if not any(f.query == name and f.title == metrics.SOURCE_STOPPED for f in found):
                found.insert(0, metrics.source_stopped(name, stop))
        # B6. A backpressure finding names the operator the time goes into, when the engine
        # measured one. Only for a query already flagged, so a healthy dashboard reads no plans.
        findings = [f.as_dict() for f in found]
        for entry in findings:
            if entry["title"] != metrics.BACKPRESSURED or not entry["query"]:
                continue
            entry["operator"] = self._bottleneck(entry["query"])
        return {
            "at": scraped["at"],
            "metrics": {"reachable": scraped["reachable"], "error": scraped["error"]},
            "registry": {"reachable": registry_error is None, "error": registry_error},
            "node": {**scraped["node"], "status": self._node_status()},
            "queries": per_query,
            "lanes": list(scraped.get("lanes") or []),
            # 1, 0, or None from an engine that predates the gauge -- three answers, and the
            # panel says which rather than reading a missing gauge as "switched off".
            "operators_enabled": scraped["node"].get("operators_enabled"),
            "findings": findings,
            "verdict": metrics.verdict(scraped["reachable"] or registry_error is None, found,
                                       len(names), bottlenecks={
                                           f["query"]: f.get("operator") for f in findings
                                           if f["title"] == metrics.BACKPRESSURED and f["query"]}),
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
    ("Live health of a plugin found on the classpath",
     ("an instance the node holds and asks; each binding configures its own today, so "
      "GET /api/v1/plugins answers UNKNOWN with reported: false")),
    ("The settings a classpath plugin accepts, with their descriptions",
     ("a manifest for ServiceLoader-discovered plugins; only a plugin registered with the engine "
      "carries PluginManifest.configSchema")),
    ("Per-plugin throughput, errors and last activity",
     "pravaha_plugin_* meters on /actuator/prometheus"),
]


class PluginService:
    """The plugins the engine can load, from the engine's own manifest listing.

    ``GET /api/v1/plugins`` answers what the console used to assemble by joining three other
    calls: each plugin with its version, the plugin API it needs and whether this engine can
    host it, what its code can be (source, sink, lookup), the capabilities it declares, its
    health and where that came from, and the bindings this identity may see -- never a
    binding's options. The stream catalogue and the sink list add the details of each binding
    (a stream's event time, what a sink accepts and who writes to it); they decorate the
    engine's answer and never decide what is listed.
    """

    def __init__(self, engine: Engine, catalog: CatalogService) -> None:
        self._engine = engine
        self._catalog = catalog

    def inventory(self) -> dict:
        errors: dict[str, str] = {}
        try:
            listed = self._engine.plugins()
        except Exception as exc:  # noqa: BLE001 -- the screen names which call failed
            listed, errors["plugins"] = [], str(exc)
        try:
            status = self._engine.status()
        except Exception as exc:  # noqa: BLE001
            status, errors["status"] = {}, str(exc)
        kinds = {b.get("kind") for p in listed for b in p.get("bindings") or []}
        streams: dict[str, dict] = {}
        sinks: dict[str, dict] = {}
        if "source" in kinds or "plugins" in errors:
            try:
                streams = {str(s.get("name")): s for s in self._catalog.streams()}
            except ServiceError as exc:
                errors["streams"] = str(exc)
        if "sink" in kinds or "plugins" in errors:
            try:
                sinks = {str(s.get("name")): s for s in self._catalog.sinks()}
            except ServiceError as exc:
                errors["sinks"] = str(exc)

        items = []
        for plugin in listed:
            health = plugin.get("health") or {}
            capabilities = plugin.get("capabilities") or {}
            item: dict[str, Any] = {
                "name": plugin.get("name"),
                "version": plugin.get("version"),
                "required_api": plugin.get("requiredApiVersion"),
                "compatible": bool(plugin.get("compatible")),
                "loaded": bool(plugin.get("loaded")),
                "kinds": list(plugin.get("kinds") or []),
                "source_capabilities": capabilities.get("source"),
                "sink_capabilities": capabilities.get("sink"),
                "capabilities_note": capabilities.get("note"),
                "settings": list(plugin.get("settings") or []),
                "health": health.get("state"),
                "health_reported": bool(health.get("reported")),
                "detail": health.get("detail") or "",
                "sources": [], "lookups": [], "sinks": [],
            }
            for binding in plugin.get("bindings") or []:
                name, kind = binding.get("name"), binding.get("kind")
                if kind == "source":
                    stream = streams.get(name) or {}
                    item["sources"].append(
                        {"name": name, "event_time": stream.get("eventTime"),
                         "lateness": stream.get("outOfOrderness"),
                         "columns": len(stream.get("fields") or []) if stream else None})
                elif kind == "lookup":
                    item["lookups"].append({"name": name})
                elif kind == "sink":
                    sink = sinks.get(name) or {}
                    item["sinks"].append(
                        {"name": name, "emit_modes": list(sink.get("emitModes") or []),
                         "accepts_retractions": bool(sink.get("acceptsRetractions")),
                         "guarantee": sink.get("guarantee"), "writers": list(sink.get("writers") or []),
                         "problem": sink.get("problem"), "described": bool(sink)})
            item["bound_as"] = [k for k, key in (("source", "sources"), ("lookup", "lookups"),
                                                 ("sink", "sinks")) if item[key]]
            item["healthy"] = item["health_reported"] and str(item["health"] or "").upper() == "HEALTHY"
            items.append(item)
        items.sort(key=lambda p: (not p["loaded"], str(p["name"]).lower()))
        return {
            "available": "plugins" not in errors,
            "node": {"instance": status.get("instanceId"), "version": status.get("version"),
                     "state": status.get("engineState")},
            "plugins": items,
            "errors": errors,
            "not_exposed": [{"what": what, "needs": needs} for what, needs in PLUGINS_NOT_EXPOSED],
        }


class DeadLetterService:
    """The records a query's feed could not decode, and putting them back (B5).

    Thin on purpose. Every decision worth making -- whether this identity may know the queue
    exists, whether they may see a record's bytes, whether they may replay -- is the engine's,
    made by the view's own rules, and the console asks rather than repeats. What is here is
    the two things a screen needs that an API does not give it: a page size it can defend, and
    a refusal turned into something a template can render.
    """

    #: Rows a page shows. Fifty is what the API defaults to, and a screen of failures is read
    #: from the top: an operator looking at a queue wants the newest, not all of it.
    PAGE = 50

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def page(self, name: str, offset: int = 0, limit: int = PAGE) -> dict:
        """One page, newest first, with the queue's totals beside it."""
        try:
            return self._engine.dead_letters(name, offset=max(0, offset), limit=limit)
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    def replay(self, name: str, ids: list[str]) -> dict:
        """Feeds chosen entries back through the query.

        Refused rather than guessed when nothing is chosen: replaying a whole queue is not
        offered by the engine either, because a queue is usually a mix of causes and most of
        it is still malformed.
        """
        chosen = [i.strip() for i in ids if i and i.strip()]
        if not chosen:
            # A backstop, not the message a person reads: the screen checks first and says it in
            # the catalog's words. Strings belong above this layer, where there is a language.
            raise ServiceError("no dead letters were chosen to replay", status=400)
        try:
            return self._engine.replay_dead_letters(name, chosen)
        except Exception as exc:
            raise _refusal(exc, 503) from exc


class ReplacementService:
    """Blue/green replacement and the backfill behind it (ADR-046, design 23.10).

    Thin, like :class:`DeadLetterService`, and for the same reason: every decision is the
    engine's. Whether this identity may start, throttle, cut over or roll back is the
    administer permission on the name; whether the two versions have consumed the same
    input is the engine's answer to a cutover, not a judgement the console may make.

    **What this refuses to compute.** There is no ETA and no percentage anywhere in here.
    A source does not say how much history it holds, so a denominator would be invented and
    a progress bar drawn from it would be a promise the engine never made. What the screen
    gets is what is measured: rows read, the rate, partitions that have reached the live
    stream, and how far behind the running version the candidate's event time is.
    """

    #: The states in which the engine is still doing something, so the screen keeps watching.
    ACTIVE = ("BACKFILLING", "CAUGHT_UP", "CUT_OVER")

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def all(self) -> list[dict]:
        """Every replacement the engine knows about, in flight or finished."""
        try:
            return list(self._engine.replacements())
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    def status(self, name: str) -> dict | None:
        """The one answer the screen renders, or ``None`` when ``name`` has no replacement."""
        try:
            return self._engine.replacement(name)
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    def start(self, name: str, sql: str, keys: list[int], *, backfill: str | None = None,
              rate_limit: int | None = None, cutover: str | None = None,
              rollback_retention: str | None = None) -> dict:
        if not sql.strip():
            raise ServiceError("a replacement needs the SQL of the new version", status=400)
        if not keys:
            raise ServiceError("a replacement needs the new version's key columns", status=400)
        if backfill not in (None, "", "history", "none"):
            raise ServiceError(f"'{backfill}' is not a backfill mode; it is history or none",
                               status=400)
        try:
            return self._engine.start_replacement(
                name, sql.strip(), keys, backfill=backfill or None,
                rate_limit=rate_limit, cutover=cutover or None,
                rollback_retention=rollback_retention or None)
        except Exception as exc:
            raise _refusal(exc) from exc

    def act(self, name: str, action: str) -> dict:
        """``cutover``, ``rollback``, ``finish``, ``abandon``, ``pause`` or ``resume``."""
        calls = {
            "cutover": self._engine.cut_over,
            "rollback": self._engine.roll_back,
            "finish": self._engine.finish_replacement,
            "abandon": self._engine.abandon_replacement,
            "pause": self._engine.pause_backfill,
            "resume": self._engine.resume_backfill,
        }
        if action not in calls:
            raise ServiceError(f"'{action}' is not something a replacement can be asked to do",
                               status=400)
        try:
            return calls[action](name)
        except Exception as exc:
            raise _refusal(exc) from exc

    def throttle(self, name: str, records_per_second: int) -> dict:
        """Lowers the backfill's ceiling. The engine refuses a raise above the one it started
        with, and the console does not pretend otherwise by clamping it here."""
        if records_per_second < 0:
            raise ServiceError("a rate limit cannot be negative", status=400)
        try:
            return self._engine.throttle_backfill(name, records_per_second)
        except Exception as exc:
            raise _refusal(exc) from exc


class DebugService:
    """The time-travel debugger (ADR-048, design 23.9).

    Thin, and thinner than most, because a debug session is the one place where a console
    that helped would be dangerous. The engine decides what a step means, what a predicate
    may compare, how big a page may be and how many rows a session may consume; every one of
    those has a refusal with a code (PRV-8011 to PRV-8016), and every one of them is
    forwarded here as the engine worded it. A console that pre-parsed ``until:total:<:0``
    would be a second parser of a syntax that has one, and the place it disagreed would be
    the one tool somebody opened because they already had a wrong answer.

    What this does do is refuse the two things the *console* got wrong before the engine ever
    sees them: an empty step, which would otherwise mean "row" by accident, and a fixture
    with no name, which the engine refuses too but not before a round trip.

    Nothing is cached and no session is held here. The session id is in the URL, the engine
    owns the session, and a console that kept a copy would be a second answer to "what has
    this fork consumed" that could disagree with the first.
    """

    #: What the engine's ``StatePage`` will build. Sent as it is typed; the engine refuses a
    #: page above its own ceiling with PRV-8015, and the console does not clamp it quietly.
    DEFAULT_PAGE = 50

    def __init__(self, engine: Engine) -> None:
        self._engine = engine

    def checkpoints(self, name: str) -> list[int]:
        """The positions a fork of ``name`` could start from, newest first."""
        try:
            return list(self._engine.debug_checkpoints(name))
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    def fork(self, name: str, checkpoint_id: int | None = None) -> dict:
        """Starts a session: a second computation of ``name``, on lanes of its own."""
        try:
            return self._engine.debug_fork(name, checkpoint_id)
        except Exception as exc:
            raise _refusal(exc) from exc

    def sessions(self) -> list[dict]:
        try:
            return list(self._engine.debug_sessions())
        except Exception as exc:
            raise _refusal(exc, 503) from exc

    #: What the engine answers when a session has ended or its TTL has released it.
    NO_SUCH_SESSION = "PRV-8013"

    def session(self, session_id: str) -> dict | None:
        """One session, or ``None`` when the engine knows no such id.

        The engine says "no such session" two ways depending on which surface answered --
        an empty answer, or PRV-8013 -- and both mean the same thing: it was ended, or
        nobody touched it for long enough that the node released it. Translated by the
        code, never by guessing at a status: every other refusal is raised as it arrived,
        and the screen shows it with its code.
        """
        try:
            return self._engine.debug_session(session_id)
        except Exception as exc:
            refusal = _refusal(exc)
            if refusal.code == self.NO_SUCH_SESSION:
                return None
            raise refusal from exc

    def step(self, session_id: str, step: str) -> dict:
        """Advances the fork. ``step`` is the engine's own spelling, sent unparsed."""
        asked = str(step or "").strip()
        if not asked:
            raise ServiceError(
                "a step has to say how far: row, rows:N, commit, watermark:<nanos> or "
                "until:<column>:<op>:<value>", status=400)
        try:
            return self._engine.debug_step(session_id, asked)
        except Exception as exc:
            raise _refusal(exc) from exc

    def state(self, session_id: str) -> list[dict]:
        try:
            return list(self._engine.debug_state(session_id))
        except Exception as exc:
            raise _refusal(exc) from exc

    def inspect(self, session_id: str, operator: str, *, key: str | None = None,
                offset: int = 0, limit: int = DEFAULT_PAGE) -> dict:
        """One page of one operator's state, read on the lane that owns it."""
        if not str(operator or "").strip():
            raise ServiceError("inspecting state needs the operator to inspect", status=400)
        try:
            return self._engine.debug_inspect(
                session_id, operator.strip(), key=(key or None), offset=offset, limit=limit)
        except Exception as exc:
            raise _refusal(exc) from exc

    def view(self, session_id: str) -> list[dict]:
        """The fork's view as changes with their weights."""
        try:
            return list(self._engine.debug_view(session_id))
        except Exception as exc:
            raise _refusal(exc) from exc

    def export(self, session_id: str, name: str) -> dict:
        """The session as a JUnit fixture. The name becomes the test class's name."""
        wanted = str(name or "").strip()
        if not wanted:
            raise ServiceError(
                "an exported fixture needs a name: name it after the thing it reproduces, "
                "and it becomes the test class's name", status=400)
        try:
            return self._engine.debug_export(session_id, wanted)
        except Exception as exc:
            raise _refusal(exc) from exc

    def end(self, session_id: str) -> None:
        try:
            self._engine.debug_end(session_id)
        except Exception as exc:
            raise _refusal(exc) from exc
