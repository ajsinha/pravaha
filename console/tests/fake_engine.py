"""A stand-in for ``core.engine.Engine``, shared by the product tests and the browser tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

It replaces exactly one object -- the only thing in the console that touches the SDK or the
engine's HTTP API -- so everything above it (services, routes, templates, the session gate,
the islands in a real browser) is the real console. Its answers are fixed, which is what
lets a screenshot of a page be compared with yesterday's.
"""
from __future__ import annotations

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
        #: Whether the engine's policy lets the console's identity read the audit trail.
        self.audit_allowed = True
        self.audit_calls: list[dict] = []
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
        if self.down:
            raise EngineHttpError(0, "the engine's HTTP API at http://engine.test:8080 did not answer")

    # Flight half
    def health(self):
        if self.down:
            return {"reachable": False, "url": self.url, "error": "connection refused"}
        return {"reachable": True, "url": self.url, "queries": len(self._queries)}

    def queries(self):
        if self.down:
            raise ConnectionError("connection refused")
        return list(self._queries)

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
        return {"level": level, "plan": "Project(txn_id, user_id)\n  Filter(amount > 100)\n    Scan(txn)\n",
                "outputFields": [], "graph": dict(PLAN_GRAPH)}

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
                        "failure": None, "reads": ["txn"]}
        raise EngineHttpError(404, f"no registered query named '{name}' that you may see", "PRV-8002")

    def query_plan(self, name):
        self._check()
        self.describe_query(name)
        return dict(PLAN_GRAPH, query={"rowsIn": 1200, "stateHeld": 3, "stateCeiling": 100,
                                       "viewSize": 17, "watermark": None, "subscribers": 2})

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
                "registeredQueries": 3, "plugins": [{"name": "filesystem", "version": "1", "health": "UP",
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
                "register": {"allowed": True, "reason": None}, "readAudit": audit,
                "views": [{"name": q.name, "read": "full", "administer": {"allowed": True, "reason": None}}
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
        matching = [e for e in reversed(AUDIT_EVENTS)
                    if (principal is None or e["principal"] == principal)
                    and (view is None or (e["target"] or "").lower() == view.lower())
                    and (action is None or e["action"] == action)
                    and (decision is None or e["decision"] == decision.upper())
                    and (since is None or e["at"] >= since) and (until is None or e["at"] < until)
                    and (cursor is None or e["sequence"] < int(cursor))]
        page = matching[: int(limit or 100)]
        more = len(matching) > len(page)
        return {"recording": True, "sink": "file", "capacity": 10000, "retained": len(AUDIT_EVENTS),
                "evicted": 0, "oldestRetained": AUDIT_EVENTS[0]["at"],
                "actions": sorted({e["action"] for e in AUDIT_EVENTS}), "events": [dict(e) for e in page],
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
    "metricsNote": "Per-operator rows, state and watermarks are not published.",
    "query": None,
}


def _range_of(sql: str, word: str) -> dict:
    for number, line in enumerate(sql.splitlines(), start=1):
        at = line.find(word)
        if at >= 0:
            return {"startLine": number, "startColumn": at + 1, "endLine": number,
                    "endColumn": at + len(word)}
    raise AssertionError(word)
