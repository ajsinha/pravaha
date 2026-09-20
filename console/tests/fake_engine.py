"""A stand-in for ``core.engine.Engine``, shared by the product tests and the browser tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

It replaces exactly one object -- the only thing in the console that touches the SDK or the
engine's HTTP API -- so everything above it (services, routes, templates, the session gate,
the islands in a real browser) is the real console. Its answers are fixed, which is what
lets a screenshot of a page be compared with yesterday's.
"""
from __future__ import annotations

import dataclasses
import sys
import time

from core.engine import EngineHttpError, QueryRow

SINK_SECRET = "jdbc-password-that-the-engine-never-publishes"

TXN = {"name": "txn", "version": 1, "fieldCount": 4, "eventTime": "event_time",
       "outOfOrderness": "PT10S", "source": "filesystem", "fields": [
    {"name": "txn_id", "type": "BIGINT", "nullable": False, "ordinal": 0},
    {"name": "user_id", "type": "VARCHAR", "nullable": False, "ordinal": 1},
    {"name": "amount", "type": "BIGINT", "nullable": False, "ordinal": 2},
    {"name": "event_time", "type": "TIMESTAMP(3)", "nullable": False, "ordinal": 3},
]}

PROMETHEUS = """\
# HELP pravaha_query_rows_in
# TYPE pravaha_query_rows_in gauge
pravaha_query_rows_in{query="big_txn"} 1200.0
pravaha_query_rows_in{query="hot"} 50.0
pravaha_query_state_fraction{query="big_txn"} 0.2
pravaha_query_state_fraction{query="hot"} 0.95
pravaha_query_state_held{query="hot"} 950.0
pravaha_query_state_ceiling{query="hot"} 1000.0
pravaha_query_view_size{query="big_txn"} 17.0
pravaha_query_watermark_lag_seconds{query="big_txn"} NaN
pravaha_query_watermark_lag_seconds{query="hot"} 1200.0
pravaha_query_running{query="big_txn"} 1.0
pravaha_query_running{query="hot"} 1.0
pravaha_query_subscribers{query="big_txn"} 3.0
pravaha_query_checkpoint_last_success_timestamp_seconds{query="big_txn"} NaN
pravaha_query_checkpoint_failures_total{query="big_txn"} 0.0
pravaha_query_backpressure_waits_total{query="big_txn"} 0.0
pravaha_query_backpressure_wait_seconds_total{query="big_txn"} 0.0
pravaha_query_backpressure_blocked_fraction{query="big_txn"} 0.01
pravaha_query_inbox_depth{query="big_txn"} 12.0
pravaha_query_inbox_cells{query="big_txn"} 2048.0
pravaha_query_backpressure_waits_total{query="hot"} 41.0
pravaha_query_backpressure_wait_seconds_total{query="hot"} 312.5
pravaha_query_backpressure_blocked_fraction{query="hot"} 0.92
pravaha_query_inbox_depth{query="hot"} 2040.0
pravaha_query_inbox_cells{query="hot"} 2048.0
pravaha_lane_blocked_fraction{lane="0"} 0.92
pravaha_lane_inbox_depth{lane="0"} 2040.0
pravaha_lane_shared_queries{lane="0"} 2.0
pravaha_lane_blocked_fraction{lane="1"} 0.03
pravaha_lane_inbox_depth{lane="1"} 4.0
pravaha_lane_shared_queries{lane="1"} 1.0
pravaha_lane_own_queries 1.0
pravaha_lane_shared_bytes 2097152.0
pravaha_metrics_operators_enabled 1.0
jvm_memory_used_bytes{area="heap",id="G1 Eden Space"} 1048576.0
jvm_memory_max_bytes{area="heap",id="G1 Old Gen"} 4194304.0
process_uptime_seconds 42.5
this line is not a sample
"""


class FakeEngine:
    """Engine's public surface, answered from memory. ``down`` makes every call fail."""

    def __init__(self, down: bool = False) -> None:
        self.url = "grpc://engine.test:9090"
        self.http_url = "http://engine.test:8080"
        self.down = down
        #: Calls that fail, and calls that take this many seconds, by method name (``_check``).
        self.failing: dict[str, Exception] = {}
        self.slow: dict[str, float] = {}
        self.registered: list[dict] = []
        self.queries_seen: list[tuple[str, list | None]] = []
        self.rows = [[1, "u1", 150], [2, "u2", 900]]
        self.streams_list = [dict(TXN)]
        self.metrics_text = PROMETHEUS
        self.sinks_list = [
            {"name": "audit_out", "plugin": "filesystem",
             "fields": [{"name": "txn_id", "type": "INT64", "nullable": False, "ordinal": 0}],
             "keyColumns": [], "emitModes": ["APPEND"], "acceptsRetractions": False,
             "guarantee": "AT_LEAST_ONCE", "writers": ["big_txn"], "problem": None},
            {"name": "broken_out", "plugin": "nope", "fields": [], "keyColumns": [], "emitModes": [],
             "acceptsRetractions": False, "guarantee": None, "writers": [],
             "problem": {"code": "PRV-5093", "message": "the 'nope' plugin could not describe this sink",
                         "helpUrl": ""}},
        ]
        self._queries = [
            QueryRow("big_txn", "RUNNING", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 100",
                     "abc123def456", 1200, (0,), "audit_out", "PT24H"),
            QueryRow("hot", "RUNNING", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "fff000", 50),
            QueryRow("hot_alias", "PAUSED", "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id", "fff000", 50),
        ]
        for q in self._queries:
            object.__setattr__(q, "_shared", q.fingerprint == "fff000")
        #: Sources that have stopped (FEED-1), by fingerprint -- one computation, one feed, so
        #: every name on it reports the stop. Empty by default: the screenshots are of a healthy
        #: engine, and a test that wants a stopped source calls :meth:`stop_source`.
        self.feed_stops: dict[str, dict] = {}
        #: The dead letters each query holds (B5), by query name, newest last -- the store's own
        #: order, so the fake reverses it exactly as the engine does. Empty by default: the
        #: screenshots are of a healthy engine, and a test that wants one calls :meth:`reject`.
        self.dead_letter_queues: dict[str, list[dict]] = {}
        #: What retention has evicted from each queue, by query name.
        self.dead_letters_evicted: dict[str, int] = {}
        #: Whether this node has a pravaha.dlq.directory at all. False is a different state
        #: from an empty queue and the screen says so differently.
        self.dlq_configured = True
        #: Ids whose replay decodes this time; anything else fails again and returns to the queue.
        self.replay_succeeds: set[str] = set()
        self.replays: list[tuple[str, list[str]]] = []
        #: Whether the engine's policy lets the console's identity read the audit trail, and
        #: the decisions it has to show (empty: a node that has recorded nothing yet).
        self.audit_allowed = True
        self.audit_events: list[dict] = list(AUDIT_EVENTS)
        self.audit_calls: list[dict] = []
        #: The policy's other refusals, by view name and for registering: what a deployment's
        #: identity system decides, and what a grant made there changes.
        self.administer_refused: dict[str, str] = {}
        self.register_refusal: str | None = None
        #: Plans by exact SQL, for a test that needs a particular shape; any other SQL is planned
        #: by ``_shaped_plan``.
        self.plans: dict[str, dict] = {}
        #: Registered queries whose running plan the policy will not show, by name, with the reason.
        self.plan_refused: dict[str, str] = {}
        #: B6. Whether this node was started with pravaha.metrics.operators on. The real default
        #: is off (it costs about 8 %); the fake's is on, because a screen that draws the numbers
        #: is the one worth auditing, and a test that wants the other answer sets this to False.
        self.operator_metrics = True
        #: Each query's share of the sampled self time, by plan node id. Without an entry the
        #: share is spread evenly, which is a plan with no operator standing out.
        self.operator_shares: dict[str, dict[str, float]] = {
            "hot": {"n0": 0.86, "n1": 0.07, "n2": 0.07},
        }
        #: B9/ADR-046. Blue/green replacements by the name being replaced, as the engine's
        #: status reports them. Empty by default: the screenshots are of an engine with nothing
        #: being replaced, and a test that wants one calls :meth:`start_replacement`.
        self.replacements_by_name: dict[str, dict] = {}
        #: Every replacement call the console made, as (action, name, argument).
        self.replacement_calls: list[tuple] = []
        self.plugins_list = [
            {"name": "filesystem", "version": "0.1.0", "requiredApiVersion": "0.1.0", "compatible": True,
             "loaded": True, "kinds": ["sink", "source"],
             "capabilities": {
                 "source": {"replayableOffsets": True, "orderedWithinPartition": True, "emitsDeletes": False,
                            "emitsBeforeImage": False, "guarantee": "EXACTLY_ONCE", "pushdown": [],
                            "typicalLatency": "PT0S"},
                 "sink": {"emitModes": ["APPEND"], "transactional": False, "idempotentUpsert": False,
                          "maxBatchRows": 0, "guarantee": "AT_LEAST_ONCE"},
                 "note": "as the plugin declares them before configuration; a binding's configuration can "
                         "narrow them, and each sink's own are on /api/v1/sinks"},
             "settings": [],
             "health": {"state": "UNKNOWN", "reported": False,
                        "detail": "no instance this node holds reports health; each binding configures its "
                                  "own, and its failures show on the query or sink that uses it"},
             "bindings": [{"kind": "sink", "name": "audit_out"}, {"kind": "source", "name": "txn"}]},
            {"name": "vault", "version": "2.1.0", "requiredApiVersion": "0.1.0", "compatible": True,
             "loaded": True, "kinds": ["lookup"],
             "capabilities": {"source": None, "sink": None, "note": None},
             "settings": ["endpoint", "token"],
             "health": {"state": "DEGRADED", "reported": True, "detail": "slow to answer"},
             "bindings": []},
            {"name": "nope", "version": None, "requiredApiVersion": None, "compatible": False,
             "loaded": False, "kinds": [], "capabilities": None, "settings": [],
             "health": {"state": "UNKNOWN", "reported": False,
                        "detail": "not on this node's classpath, so nothing bound to it can run"},
             "bindings": [{"kind": "sink", "name": "broken_out"}]},
        ]

    def _check(self):
        """Every call passes through here: ``down`` fails them all, and ``failing`` and ``slow``
        -- keyed by the calling method's name -- fail or delay one, which is how a test puts a
        screen in its partial, error or first-loading state (design 23.12)."""
        if self.down:
            raise EngineHttpError(0, "the engine's HTTP API at http://engine.test:8080 did not answer")
        call = sys._getframe(1).f_code.co_name
        if self.slow.get(call):
            time.sleep(self.slow[call])
        if call in self.failing:
            raise self.failing[call]

    def fail(self, *calls: str, status: int = 503, code: str | None = None,
             message: str = "the engine did not answer this call") -> None:
        """Make each named call fail as the engine's HTTP API would, until ``heal``."""
        for call in calls:
            self.failing[call] = EngineHttpError(status, f"{message} ({call})", code)

    def heal(self) -> None:
        self.failing.clear()
        self.slow.clear()

    # Flight half
    def health(self):
        if self.down:
            return {"reachable": False, "url": self.url, "error": "connection refused"}
        return {"reachable": True, "url": self.url, "queries": len(self._queries)}

    def stop_source(self, fingerprint: str, code: str = "PRV-5040",
                    message: str = "line 3 of txn.csv: 'abc' is not an INT64", where: str = "txn#0",
                    at: str = "2026-09-19T08:00:00Z") -> None:
        """Stops the feed of the computation with this fingerprint, as a source failing mid-read does."""
        self.feed_stops[fingerprint] = {"code": code, "message": message, "where": where, "at": at}

    def _with_feed(self, q: QueryRow) -> QueryRow:
        stop = self.feed_stops.get(q.fingerprint)
        row = dataclasses.replace(
            q, feed="STOPPED" if stop else "RUNNING", feed_code=(stop or {}).get("code"),
            feed_message=(stop or {}).get("message"), feed_where=(stop or {}).get("where"),
            feed_at=(stop or {}).get("at"))
        object.__setattr__(row, "_shared", q.shared)
        return row

    def queries(self):
        if self.down:
            raise ConnectionError("connection refused")
        if self.slow.get("queries"):
            time.sleep(self.slow["queries"])
        if "queries" in self.failing:
            raise ConnectionError(str(self.failing["queries"]))
        return [self._with_feed(q) for q in self._queries]

    def register(self, name, sql, keys, sink=None, retention=None):
        self._check()
        self.registered.append({"name": name, "sql": sql, "keys": list(keys), "sink": sink,
                                "retention": retention})
        return QueryRow(name, "RUNNING", sql, "newfp", 0, tuple(keys), sink, retention)

    def lifecycle(self, action, name):
        self._check()

    def query(self, sql, parameters=None):
        columns, rows, _ = self.query_typed(sql, parameters)
        return columns, rows

    def query_typed(self, sql, parameters=None):
        self._check()
        self.queries_seen.append((sql, parameters))
        return ["txn_id", "user_id", "amount"], [list(r) for r in self.rows], ["int64", "string", "int64"]

    def tail(self, view, filters=None):
        yield {"txn_id": 1, "user_id": "u1", "amount": 150, "_weight": 1}
        yield {"txn_id": 1, "user_id": "u1", "amount": 150, "_weight": -1}

    def snapshot_rows(self):
        """The view as a snapshot subscription starts from it: the rows a read would give."""
        return [{"txn_id": r[0], "user_id": r[1], "amount": r[2], "_weight": 1} for r in self.rows]

    def mirror(self, view, filters=None):
        yield ("snapshot", self.snapshot_rows(), 1)
        yield ("commit", list(self.tail(view, filters)), 2)

    # REST half
    def streams(self):
        self._check()
        return list(self.streams_list)

    def declare_stream(self, name, schema, event_time=None, out_of_orderness=None):
        self._check()
        fields = [{"name": p.split(":")[0], "type": p.split(":")[1], "nullable": True, "ordinal": i}
                  for i, p in enumerate(schema.split(","))]
        stream = {"name": name, "version": 1, "fieldCount": len(fields), "fields": fields,
                  "eventTime": event_time, "outOfOrderness": out_of_orderness if event_time else None,
                  "source": None}
        self.streams_list.append(stream)
        return stream

    def validate(self, sql):
        self._check()
        # Positions as the engine sends them: from the parser, 1-based, end column inclusive.
        for word, code in (("txm", "PRV-2003"), ("amout", "PRV-2002")):
            if word in sql:
                return {"valid": False, "diagnostics": [{"code": code, "severity": "error",
                        "message": f"'{word}' is not known here",
                        "helpUrl": f"https://docs.pravaha.io/errors/{code}",
                        "range": _range_of(sql, word)}],
                        "outputFields": [], "elapsedMicros": 900}
        if "PLANONLY" in sql:
            # A refusal about the plan, not the text: the engine gives no position.
            return {"valid": False, "diagnostics": [{"code": "PRV-2050", "severity": "error",
                    "message": "an unwindowed COUNT(DISTINCT) is refused", "helpUrl": ""}],
                    "outputFields": [], "elapsedMicros": 500}
        return {"valid": True, "diagnostics": [], "elapsedMicros": 1234, "outputFields": [
            {"name": "txn_id", "type": "BIGINT", "nullable": False, "ordinal": 0},
            {"name": "user_id", "type": "VARCHAR", "nullable": False, "ordinal": 1},
            {"name": "amount", "type": "BIGINT", "nullable": False, "ordinal": 2}]}

    def explain(self, sql, level="physical"):
        self._check()
        if "PLANONLY" in sql:
            raise EngineHttpError(400, "an unwindowed COUNT(DISTINCT) is refused", "PRV-2050")
        graph = self.plan_for(sql)
        return {"level": level, "plan": _plan_text(graph), "outputFields": [], "graph": graph}

    def plan_for(self, sql):
        """The plan the planner would draw for ``sql``: one a test set in ``plans``, else one
        shaped by the SQL -- its WHERE predicate in the Filter, an Aggregate for a GROUP BY -- so
        two versions of a query plan differently, as they would on the real engine."""
        if sql.strip() in self.plans:
            return dict(self.plans[sql.strip()])
        return _shaped_plan(sql)

    def sinks(self):
        self._check()
        return [dict(s) for s in self.sinks_list]

    def describe_queries(self):
        self._check()
        return [self.describe_query(q.name) for q in self._queries]

    def describe_query(self, name):
        self._check()
        for q in self._queries:
            if q.name == name:
                return {"name": q.name, "state": q.state, "sql": q.sql, "fingerprint": q.fingerprint,
                        "sharedWith": [o.name for o in self._queries
                                       if o.fingerprint == q.fingerprint and o.name != q.name],
                        "keyColumns": [{"name": "txn_id", "ordinal": 0}], "retention": q.retention or "forever",
                        "sink": ({"name": q.sink, "attached": False,
                                  "failure": {"code": "PRV-8009", "message": "sink 'audit_out' failed and has been detached",
                                              "helpUrl": ""}, "rowsWritten": 7} if q.sink else None),
                        "rowsIn": q.rows_in, "countsWithheld": False, "registeredAt": "2026-09-19T00:00:00Z",
                        "failure": None, "reads": ["txn"], "feed": self._feed_detail(q)}
        raise EngineHttpError(404, f"no registered query named '{name}' that you may see", "PRV-8002")

    def _feed_detail(self, q: QueryRow) -> dict:
        """``GET /api/v1/queries/{name}``'s ``feed``, as the engine sends it."""
        stop = self.feed_stops.get(q.fingerprint)
        failure = ({"code": stop["code"], "message": stop["message"],
                    "helpUrl": f"https://docs.pravaha.io/errors/{stop['code']}"} if stop else None)
        return {"state": "STOPPED" if stop else "RUNNING", "description": "reading txn (1 partition)",
                "sources": [{"stream": "txn", "partition": 0, "state": "STOPPED" if stop else "RUNNING",
                             "shared": False, "origin": bool(stop), "failure": failure,
                             "stoppedAt": stop["at"] if stop else None}],
                "stoppedSources": 1 if stop else 0, "failure": failure}

    # ------------------------------------------------------------------ dead letters (B5)

    def reject(self, query: str, *, reason: str = "column 'amount' (INT64): cannot read '12.50' as a number",
               code: str = "PRV-5040", offset: str = "line 812", raw: str = "812,u-1042,acme,12.50",
               at: str = "2026-09-19T09:12:00Z", withheld: bool = False,
               letter_id: str | None = None) -> str:
        """Records one dead letter, as a feed that could not decode a record would.

        ``withheld`` is what the engine sends a caller whose read of the view is row-filtered:
        the record and the decoder's sentence are absent and a sentence says why, while the
        count, the offset and the code are not.
        """
        import base64
        import uuid

        made = letter_id or str(uuid.uuid4())
        queue = self.dead_letter_queues.setdefault(query, [])
        queue.append({
            "id": made,
            "sequence": len(queue) + 1,
            "query": query,
            "stream": "txn",
            "offset": offset,
            "code": code,
            "reason": "" if withheld else reason,
            "at": at,
            "size": len(raw.encode()),
            "raw": None if withheld else base64.b64encode(raw.encode()).decode(),
            "withheld": ("the record is withheld: your access to this view is row-filtered, and a "
                         "record that failed to decode has no row for that filter to be applied to. "
                         "The count, the offset and the code are not withheld.") if withheld else None,
            "replay": "NEW",
            "replayedAt": None,
        })
        return made

    def dead_letters(self, name, offset=0, limit=50):
        self._check()
        self.describe_query(name)
        queue = list(reversed(self.dead_letter_queues.get(name, [])))
        window = queue[offset:offset + limit]
        evicted = self.dead_letters_evicted.get(name, 0)
        return {
            "query": name,
            "entries": window,
            "offset": offset,
            "limit": limit,
            "total": len(queue),
            "more": offset + len(window) < len(queue),
            "bytes": sum(e["size"] for e in queue),
            "evicted": evicted,
            "evictedBytes": evicted * 24,
            "replayed": sum(1 for e in queue if e["replay"] == "REPLAYED"),
            "failedAgain": sum(1 for e in queue if e["replay"] == "FAILED_AGAIN"),
            "oldest": queue[-1]["at"] if queue else None,
            "newest": queue[0]["at"] if queue else None,
            "retention": "268435456 bytes",
            "configured": self.dlq_configured,
        }

    def replay_dead_letters(self, name, ids):
        self._check()
        self.describe_query(name)
        if name in self.administer_refused:
            raise EngineHttpError(
                403, f"console may not dlq.replay '{name}': {self.administer_refused[name]}", "PRV-7002")
        self.replays.append((name, list(ids)))
        queue = self.dead_letter_queues.setdefault(name, [])
        results, done, again = [], 0, 0
        for wanted in ids:
            entry = next((e for e in queue if e["id"] == wanted), None)
            if entry is None:
                raise EngineHttpError(
                    404, f"'{name}' has no dead letter with the id '{wanted}'", "PRV-4091")
            if wanted in self.replay_succeeds:
                entry["replay"] = "REPLAYED"
                done += 1
                results.append({"id": wanted, "outcome": "REPLAYED", "newId": "",
                                "detail": "the record decoded and is a row of '" + name + "' now, applied at "
                                          "the frontier the query has reached -- not at the offset it "
                                          "originally came from."})
            else:
                entry["replay"] = "FAILED_AGAIN"
                again += 1
                # Back on the queue as a new entry, which is what a client would replay next.
                fresh = self.reject(name, reason="replay of " + wanted + " failed again: still not a number")
                results.append({"id": wanted, "outcome": "FAILED_AGAIN", "newId": fresh,
                                "detail": "the record failed to decode again and has gone back on the "
                                          "queue as a new entry. It is not retried."})
        return {"query": name, "results": results, "replayed": done, "failedAgain": again}

    def query_plan(self, name):
        self._check()
        self.describe_query(name)
        if name in self.plan_refused:
            raise EngineHttpError(403, f"console may not read the plan of '{name}': {self.plan_refused[name]}",
                                  "PRV-7002")
        sql = next(q.sql for q in self._queries if q.name == name)
        graph = self.plan_for(sql)
        # B6. What the engine measures for the query as a whole, backpressure included.
        telemetry = {"rowsIn": 1200, "stateHeld": 3, "stateCeiling": 100, "viewSize": 17,
                     "watermark": None, "subscribers": 2,
                     "backpressureWaits": 41 if name == "hot" else 0,
                     "backpressureWaitSeconds": 312.5 if name == "hot" else 0.0,
                     "blockedFraction": 0.92 if name == "hot" else 0.01,
                     "inboxDepth": 2040 if name == "hot" else 12, "inboxCells": 2048}
        if not self.operator_metrics:
            # A node started with pravaha.metrics.operators off: the counters were never built
            # into the stages, which is a different answer from every one of them reading zero.
            return dict(graph, operatorMetrics=None, bottleneck=None,
                        metricsNote=NOTE_OPERATORS_OFF, query=telemetry)
        measured, bottleneck = _operator_metrics(graph["nodes"], self.operator_shares.get(name))
        return dict(graph, operatorMetrics=measured, bottleneck=bottleneck,
                    metricsNote=NOTE_MEASURED, query=telemetry)

    # -------------------------------------------- blue/green replacement (ADR-046, B9)

    def _replacement_or_refuse(self, name: str) -> dict:
        """The replacement of ``name``, or the engine's refusal -- policy first, then 404.

        The policy is checked before the lookup, as the engine's is: whether this identity
        may administer the name does not depend on whether a replacement happens to exist.
        """
        if name in self.administer_refused:
            raise EngineHttpError(
                403, f"console may not administer '{name}': {self.administer_refused[name]}",
                "PRV-7002")
        found = self.replacements_by_name.get(name)
        if found is None:
            raise EngineHttpError(404, f"'{name}' is not being replaced", "PRV-4017")
        return found

    def start_replacement(self, name, sql, keys, *, backfill=None, rate_limit=None,
                          cutover=None, rollback_retention=None):
        self._check()
        if name in self.administer_refused:
            raise EngineHttpError(
                403, f"console may not administer '{name}': {self.administer_refused[name]}",
                "PRV-7002")
        options = ";".join(
            part for part in (f"backfill={backfill}" if backfill else "",
                              f"backfill.rate.limit={rate_limit}" if rate_limit else "",
                              f"cutover={cutover}" if cutover else "",
                              f"rollback.retention={rollback_retention}" if rollback_retention else "")
            if part)
        self.replacement_calls.append(("start", name, sql))
        made = {
            "name": name, "state": "BACKFILLING", "sql": sql, "candidate": "newfp",
            "replacing": next((q.fingerprint for q in self._queries if q.name == name), None),
            "sink": None, "options": options, "owner": "console",
            "startedAt": "2026-09-19T09:00:00Z", "cutOverAt": None, "rollbackUntil": None,
            "rollbackAvailable": False,
            "backfill": {"historyRows": 0, "liveRows": 0, "rowsPerSecond": 0.0, "partitions": 4,
                         "partitionsLive": 0, "historyComplete": False,
                         "rateLimit": int(rate_limit or 0), "paused": False, "lagSeconds": None},
            "history": None, "failure": None,
        }
        self.replacements_by_name[name] = made
        return dict(made)

    def replacement(self, name):
        self._check()
        if name in self.administer_refused and name not in self.replacements_by_name:
            # A reader still sees the screen; the engine only withholds what it authorizes.
            return None
        found = self.replacements_by_name.get(name)
        return dict(found) if found is not None else None

    def replacements(self):
        self._check()
        return [dict(r) for r in self.replacements_by_name.values()]

    def cut_over(self, name):
        self._check()
        found = self._replacement_or_refuse(name)
        if not found["backfill"]["historyComplete"]:
            raise EngineHttpError(
                409, f"'{name}' has not caught up: the two versions are not at the same position "
                     "in their input, and a cutover there would leave records in neither",
                "PRV-4014")
        self.replacement_calls.append(("cutover", name, None))
        found.update(state="CUT_OVER", cutOverAt="2026-09-19T09:30:00Z",
                     rollbackUntil="2026-09-19T15:30:00Z", rollbackAvailable=True)
        return dict(found)

    def roll_back(self, name):
        self._check()
        found = self._replacement_or_refuse(name)
        if not found["rollbackAvailable"]:
            raise EngineHttpError(
                409, f"the version '{name}' replaced is no longer retained, so there is nothing "
                     "to roll back to", "PRV-4016")
        self.replacement_calls.append(("rollback", name, None))
        found.update(state="ROLLED_BACK", rollbackAvailable=False)
        return dict(found)

    def finish_replacement(self, name):
        self._check()
        found = self._replacement_or_refuse(name)
        self.replacement_calls.append(("finish", name, None))
        found.update(state="FINISHED", rollbackAvailable=False, rollbackUntil=None)
        return dict(found)

    def abandon_replacement(self, name):
        self._check()
        found = self._replacement_or_refuse(name)
        self.replacement_calls.append(("abandon", name, None))
        found.update(state="ABANDONED")
        return dict(found)

    def throttle_backfill(self, name, records_per_second):
        self._check()
        found = self._replacement_or_refuse(name)
        # Recorded before the ceiling is checked: what the console *sent* is the thing a test
        # about "the console does not clamp the number it was given" has to be able to see.
        self.replacement_calls.append(("throttle", name, records_per_second))
        ceiling = found["backfill"]["rateLimit"]
        if ceiling and records_per_second > ceiling:
            raise EngineHttpError(
                409, f"the backfill of '{name}' started with a ceiling of {ceiling} records a "
                     "second and may be slowed, not sped up", "PRV-4018")
        found["backfill"]["rateLimit"] = records_per_second
        return dict(found)

    def pause_backfill(self, name):
        self._check()
        found = self._replacement_or_refuse(name)
        self.replacement_calls.append(("pause", name, None))
        found["backfill"]["paused"] = True
        return dict(found)

    def resume_backfill(self, name):
        self._check()
        found = self._replacement_or_refuse(name)
        self.replacement_calls.append(("resume", name, None))
        found["backfill"]["paused"] = False
        return dict(found)

    def backfill_progress(self, name: str, **numbers) -> dict:
        """Moves a replacement's backfill on, as the engine reading history would."""
        found = self.replacements_by_name[name]
        found["backfill"].update(numbers)
        if found["backfill"]["historyComplete"]:
            found["state"] = "CAUGHT_UP"
        return found

    def describe_view(self, name):
        self._check()
        detail = self.describe_query(name)
        return {"name": name, "schema": [
            {"name": "txn_id", "type": "BIGINT", "nullable": False, "ordinal": 0},
            {"name": "user_id", "type": "VARCHAR", "nullable": False, "ordinal": 1},
            {"name": "amount", "type": "BIGINT", "nullable": False, "ordinal": 2}],
            "keyColumns": detail["keyColumns"], "retention": detail["retention"],
            "sink": detail["sink"]["name"] if detail["sink"] else None, "fingerprint": detail["fingerprint"]}

    def status(self):
        self._check()
        return {"instanceId": "n1", "version": "0.1.0", "engineState": "RUNNING", "uptimeSeconds": 5,
                "registeredQueries": 3, "streams": 2, "plugins": [{"name": "filesystem", "version": "1", "health": "UP",
                                                     "detail": ""}]}

    def prometheus(self):
        self._check()
        return self.metrics_text

    def plugins(self):
        self._check()
        return [dict(p) for p in self.plugins_list]

    def permissions(self):
        self._check()
        audit = ({"allowed": True, "reason": None} if self.audit_allowed else
                 {"allowed": False, "reason": AUDIT_REFUSAL})
        return {"principal": "console", "tenant": "public", "roles": ["admin"] if self.audit_allowed else [],
                "anonymous": False, "policy": "authenticated",
                "register": ({"allowed": False, "reason": self.register_refusal} if self.register_refusal
                             else {"allowed": True, "reason": None}), "readAudit": audit,
                "views": [{"name": q.name, "read": "full",
                           "administer": ({"allowed": False, "reason": self.administer_refused[q.name]}
                                          if q.name in self.administer_refused
                                          else {"allowed": True, "reason": None})}
                          for q in self._queries],
                "streams": [{"name": s["name"], "read": "full", "administer": {"allowed": True, "reason": None}}
                            for s in self.streams_list]}

    def audit(self, since=None, until=None, principal=None, view=None, action=None, decision=None,
              limit=100, cursor=None):
        """The engine's page semantics: newest first, filtered, ``cursor`` continues below a sequence."""
        self._check()
        asked = {"since": since, "until": until, "principal": principal, "view": view,
                 "action": action, "decision": decision, "limit": limit, "cursor": cursor}
        self.audit_calls.append({k: v for k, v in asked.items() if v is not None})
        if not self.audit_allowed:
            raise EngineHttpError(403, "console may not read the audit trail: " + AUDIT_REFUSAL, "PRV-7002")
        matching = [e for e in reversed(self.audit_events)
                    if (principal is None or e["principal"] == principal)
                    and (view is None or (e["target"] or "").lower() == view.lower())
                    and (action is None or e["action"] == action)
                    and (decision is None or e["decision"] == decision.upper())
                    and (since is None or e["at"] >= since) and (until is None or e["at"] < until)
                    and (cursor is None or e["sequence"] < int(cursor))]
        page = matching[: int(limit or 100)]
        more = len(matching) > len(page)
        return {"recording": True, "sink": "file", "capacity": 10000, "retained": len(self.audit_events),
                "evicted": 0, "oldestRetained": self.audit_events[0]["at"] if self.audit_events else None,
                "actions": sorted({e["action"] for e in self.audit_events}), "events": [dict(e) for e in page],
                "nextCursor": str(page[-1]["sequence"]) if more and page else None,
                "note": "The most recent 10000 decisions on this node are readable here; none has been "
                        "evicted since it started."}


AUDIT_REFUSAL = "reading the audit trail needs one of the roles [admin]"


def _audit_events() -> list[dict]:
    """Seventy fixed decisions, oldest first: enough for two pages and a filter to narrow."""
    people = [("ann", ["analyst"]), ("carol", ["intern"]), ("root", ["admin"])]
    events = []
    for i in range(70):
        who, roles = people[i % 3]
        denied = who == "carol" and i % 2 == 1
        target = "payroll" if denied else ("big_txn" if i % 2 else "txn")
        action = "http.audit.read" if who == "root" and i % 9 == 2 else ("query" if i % 4 else "http.read")
        events.append({
            "sequence": i + 1, "at": f"2026-09-19T08:{i // 60:02d}:{i % 60:02d}Z",
            "principal": who, "tenant": "acme", "roles": roles,
            "action": action, "target": "audit" if action == "http.audit.read" else target,
            "decision": "DENY" if denied else "ALLOW",
            "reason": "not an analyst" if denied else "allowed",
            "detail": None if action.startswith("http.") else f"SELECT * FROM {target} WHERE id = {i}"})
    return events


AUDIT_EVENTS = _audit_events()


#: The three answers ``GET /api/v1/queries/{name}/plan`` gives about per-operator numbers,
#: word for word as DtoMapper writes them. A console that paraphrased them would be a second
#: place where "not measured" is worded, and the two would drift.
NOTE_MEASURED = (
    "Per-operator rows, rows out, state bytes and watermark are measured. Self time is "
    "sampled: one row in every 1,024 that enters the pipeline is timed at every operator "
    "on its path, and 'sampledRows' says how many that was, so a share read off a handful "
    "of samples can be recognised as one. 'bottleneck' is the node most of the sampled "
    "time went into.")
NOTE_NOT_RUNNING = (
    "Per-operator numbers are not published for a plan that is not running: there is nothing "
    "to measure. Register the query and read its plan to get them.")
NOTE_OPERATORS_OFF = (
    "Per-operator numbers are not published on this node: pravaha.metrics.operators is off, so "
    "the counters were never built into this query's stages. Set it and re-register the query. "
    "The query's own totals are under 'query'.")


PLAN_GRAPH = {
    "nodes": [
        {"id": "n0", "operator": "Project", "detail": "Project(txn_id, user_id)", "stateful": False,
         "fields": ["txn_id", "user_id"]},
        {"id": "n1", "operator": "Filter", "detail": "Filter(amount > 100)", "stateful": False,
         "fields": ["txn_id", "user_id", "amount"]},
        {"id": "n2", "operator": "Scan", "detail": "Scan(txn)", "stateful": False,
         "fields": ["txn_id", "user_id", "amount"]},
    ],
    "edges": [{"from": "n1", "to": "n0"}, {"from": "n2", "to": "n1"}],
    "operatorMetrics": None,
    "bottleneck": None,
    "metricsNote": NOTE_NOT_RUNNING,
    "query": None,
}


def _operator_metrics(nodes: list[dict], shares: dict[str, float] | None) -> tuple[dict, str | None]:
    """Per-operator telemetry for a plan's nodes, and the node most of the time went into.

    Shaped exactly as ``ApiDtos.OperatorTelemetry``: counts that are exact to the last batch
    boundary, the query's own watermark repeated on every node, and a self time that is
    *sampled* -- one row in 1,024 -- with ``sampledRows`` beside it so a share read off four
    samples can be recognised as one.
    """
    even = 1.0 / max(1, len(nodes))
    shares = shares or {n["id"]: even for n in nodes}
    total_nanos = 1_150_000
    measured = {}
    for node in nodes:
        share = shares.get(node["id"], 0.0)
        measured[node["id"]] = {
            "rowsIn": 4213, "rowsOut": 4213,
            "stateBytes": 8388608 if node.get("stateful") else None,
            "watermark": "2026-09-19T09:29:00Z",
            "selfNanos": int(total_nanos * share), "sampledRows": 4,
            "selfTimeShare": share,
        }
    top = max(measured, key=lambda k: measured[k]["selfTimeShare"], default=None)
    # No bottleneck where the time is spread evenly: the engine names one only when the
    # samples put one ahead, and a console that always named the maximum would always
    # accuse somebody.
    if top is None or measured[top]["selfTimeShare"] <= even + 1e-9:
        return measured, None
    return measured, top


def _shaped_plan(sql: str) -> dict:
    """Three operators whatever the SQL, as every existing test expects: a GROUP BY is an
    Aggregate over a Project; anything else is ``PLAN_GRAPH`` with the SQL's own WHERE predicate
    in its Filter (``amount > 100`` when it has none)."""
    import re

    grouped = re.search(r"\bGROUP\s+BY\s+([A-Za-z_][A-Za-z0-9_]*)", sql, re.IGNORECASE)
    if grouped:
        key = grouped.group(1)
        return dict(PLAN_GRAPH, nodes=[
            {"id": "n0", "operator": "Aggregate", "detail": "Aggregate(group=[0], [COUNT(EXPR$1)])",
             "stateful": True, "fields": [key, "EXPR$1"]},
            {"id": "n1", "operator": "Project", "detail": f"Project[{key}]", "stateful": False, "fields": [key]},
            {"id": "n2", "operator": "Scan", "detail": "Scan(txn)", "stateful": False,
             "fields": ["txn_id", "user_id", "amount"]}])
    where = re.search(r"\bWHERE\s+(.+?)\s*;?\s*$", sql, re.IGNORECASE | re.DOTALL)
    if not where:
        return dict(PLAN_GRAPH)
    nodes = [dict(n) for n in PLAN_GRAPH["nodes"]]
    nodes[1]["detail"] = f"Filter({' '.join(where.group(1).split())})"
    return dict(PLAN_GRAPH, nodes=nodes)


def _plan_text(graph: dict) -> str:
    """The indented text form ``explain`` prints beside the graph (root first; the fakes are chains)."""
    return "".join("  " * i + n["detail"] + "\n" for i, n in enumerate(graph["nodes"]))


def _range_of(sql: str, word: str) -> dict:
    for number, line in enumerate(sql.splitlines(), start=1):
        at = line.find(word)
        if at >= 0:
            return {"startLine": number, "startColumn": at + 1, "endLine": number,
                    "endColumn": at + len(word)}
    raise AssertionError(word)
