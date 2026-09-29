"""A stand-in for ``core.engine.Engine``, shared by the product tests and the browser tests.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential. See LICENSE at the repository root.

It replaces exactly one object -- the only thing in the console that touches the SDK or the
engine's HTTP API -- so everything above it (services, routes, templates, the session gate,
the islands in a real browser) is the real console. Its answers are fixed, which is what
lets a screenshot of a page be compared with yesterday's.

It is also the identity authority the console signs people in against (ADR-052,
``fake_identity.FakeIdentity``): every call made while serving a request carries that request's
engine session (``core.credential``), and the fake refuses one that has ended with ``PRV-7016``
exactly as the engine does -- so a console that forgot to send the person's token, or kept
serving a session the engine has ended, fails its tests.
"""
from __future__ import annotations

import dataclasses
import hashlib
import re
import sys
import time

from fake_alerts import FakeAlerts
from fake_catalog import FakeCatalog
from fake_identity import ADMIN, ADMIN_PASSWORD, FakeIdentity

from core import credential
from core.engine import EngineHttpError, QueryRow

__all__ = ["ADMIN", "ADMIN_PASSWORD", "FakeEngine"]

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

    def __init__(self, down: bool = False, *, force_change: bool = False,
                 clock=time.time, max_sessions: int | None = 3) -> None:
        self.url = "grpc://engine.test:19090"
        self.http_url = "http://engine.test:18080"
        self.down = down
        #: ADR-052's users, sessions and keys; the console signs in against this.
        self.identity = FakeIdentity(force_change=force_change, clock=clock, max_sessions=max_sessions)
        #: ADR-059's catalogue: objects, owners, tags and grants; ``governed.on = False`` is a node
        #: whose catalogue is off.
        self.governed = FakeCatalog(self)
        #: ADR-057's alerts; ``alerting.on = False`` is a node whose alert service is off.
        self.alerting = FakeAlerts(self)
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
        #: ADR-050. Each tenant's use as ``GET /api/v1/tenants`` reports it. ``None`` in a limit
        #: is no limit. Which tenants are shown follows ``audit_allowed``, as the engine's does.
        self.tenant_defaults: dict = {"maxQueries": 20, "maxStateKeys": None}
        self.tenant_rows: list[dict] = [dict(t) for t in TENANTS]
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
        #: B9/ADR-048. The positions each query can still be forked from, newest first.
        #: Fixed, because a screenshot of the fork form has to be the same one tomorrow.
        self.checkpoints_by_query: dict[str, list[int]] = {"big_txn": [4471, 4470, 4469],
                                                           "hot": [4471, 4470]}
        #: Open debug sessions by id, as the engine's status reports them. Empty by default:
        #: the screenshots are of a node debugging nothing, and a test that wants a session
        #: calls :meth:`debug_fork`.
        self.debug_sessions_by_id: dict[str, dict] = {}
        #: What each fork is holding, beside the status: the plan's nodes, how far the replay
        #: has been consumed, and the view it has built.
        self._debug_forks: dict[str, dict] = {}
        #: Every debug call the console made, as (verb, subject, argument).
        self.debug_calls: list[tuple] = []
        #: ``pravaha.debug.sessions.max``. Past it the engine refuses with PRV-8014.
        self.debug_sessions_max = 4
        #: Whether the version history reaches the console. It comes from the engine's REST
        #: surface rather than the control wire (``Engine._replacement_history``), so a node
        #: with no ``engine.http_url``, or one whose HTTP surface refuses, has a replacement
        #: and no history -- which the screen draws as its partial state. True here, because
        #: a console configured the way its README configures it reads one.
        self.history_carried = True
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
            raise EngineHttpError(0, "the engine's HTTP API at http://engine.test:18080 did not answer")
        self._session()
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

    def _session(self):
        """The bearer this request carries, checked as the engine checks it. Outside a request --
        a test calling the fake directly -- there is no bearer to check."""
        if credential.current() is None:
            return None
        return self._as_identity(lambda token: self.identity.principal(token)[0])

    def principal_name(self) -> str:
        """Who the current request is, for answers that name the caller."""
        held = credential.current()
        if held is None or not held.token:
            return "anonymous"
        user = self.identity.sessions.get(hashlib.sha256(held.token.encode()).hexdigest())
        return user["username"] if user else "anonymous"

    def _as_identity(self, call):
        """An identity call as the engine's HTTP API answers it: refused when down, and a refusal
        of the credential noted on it, as the real adapter notes it."""
        if self.down:
            raise EngineHttpError(0, "the engine's HTTP API at http://engine.test:18080 did not answer")
        try:
            return call(credential.token())
        except EngineHttpError as exc:
            credential.note_refusal(exc)
            raise

    # ADR-052: the identity endpoints, as core.engine.Engine calls them
    def login(self, username, password):
        if self.down:
            raise EngineHttpError(0, "the engine's HTTP API at http://engine.test:18080 did not answer")
        return self.identity.login(username, password)

    def logout(self):
        return self._as_identity(self.identity.logout)

    def me(self):
        return self._as_identity(self.identity.me)

    def change_password(self, current, new):
        return self._as_identity(lambda t: self.identity.change_password(t, current, new))

    def redeem_reset(self, token, password):
        if self.down:
            raise EngineHttpError(0, "the engine's HTTP API at http://engine.test:18080 did not answer")
        return self.identity.redeem(token, password)

    def users(self):
        return self._as_identity(self.identity.list_users)

    def create_user(self, fields):
        return self._as_identity(lambda t: self.identity.create_user(t, fields))

    def update_user(self, username, fields):
        return self._as_identity(lambda t: self.identity.update_user(t, username, fields))

    def set_roles(self, username, roles):
        return self._as_identity(lambda t: self.identity.set_roles(t, username, roles))

    def reset_password(self, username):
        return self._as_identity(lambda t: self.identity.reset(t, username))

    def keys(self, all_keys=False):
        return self._as_identity(lambda t: self.identity.list_keys(t, all_keys))

    def create_key(self, name, roles, expires_days, for_user=None):
        return self._as_identity(lambda t: self.identity.create_key(t, name, roles, expires_days, for_user))

    def revoke_key(self, key_id):
        return self._as_identity(lambda t: self.identity.revoke_key(t, key_id))

    def rotate_key(self, key_id):
        return self._as_identity(lambda t: self.identity.rotate_key(t, key_id))

    def key_report(self):
        return self._as_identity(self.identity.key_report)

    def sessions(self, all_sessions=False):
        return self._as_identity(lambda t: self.identity.list_sessions(t, all_sessions))

    def end_session(self, session_id):
        return self._as_identity(lambda t: self.identity.end_session(t, session_id))

    # Lanes: every fake query shares lane 0 but the first, which owns one.
    def lanes(self):
        self._check()
        shared = max(0, len(self._queries) - 1)
        return {"mode": "auto", "autoFrom": 1, "maxQueriesPerLane": 300,
                "sharedLanes": [{"lane": 0, "queries": shared}], "ownLaneQueries": min(1, len(self._queries)),
                "dedicatedQueries": 0, "hosted": len(self._queries)}

    def lane_placements(self):
        self._check()
        return [{"name": q.name, "state": q.state, "lane": "own" if i == 0 else "shared",
                 "sharedLane": None if i == 0 else 0} for i, q in enumerate(self._queries)]

    def rebalance(self, dry_run):
        self._check()
        if "admin" not in self._as_identity(lambda t: self.identity.me(t)).get("roles", []):
            raise EngineHttpError(403, "PRV-7002 a lane rebalance needs the admin role")
        self.rebalances = getattr(self, "rebalances", 0) + (0 if dry_run else 1)
        return {"mode": "auto", "autoFrom": 1, "room": 0, "running": not dry_run, "ownLaneQueries": 1,
                "moves": [{"name": q.name, "fromSharedLane": 0, "status": "planned" if dry_run else "waiting",
                           "detail": ""} for q in self._queries[1:2]]}

    def rebalance_status(self):
        return self.rebalance(True)

    # ADR-059: the catalogue, as core.engine.Engine calls it (fake_catalog keeps the contract)
    def catalog_objects(self, namespace=None, kind=None, search=None):
        return self.governed.catalog_objects(namespace, kind, search)

    def catalog_object(self, name):
        return self.governed.catalog_object(name)

    def catalog_namespaces(self):
        return self.governed.catalog_namespaces()

    def create_namespace(self, name, description=""):
        return self.governed.create_namespace(name, description)

    def change_catalog_object(self, name, fields):
        return self.governed.change_catalog_object(name, fields)

    def grants(self, on=None, grantee_type=None, grantee=None):
        return self.governed.grant_list(on, grantee_type, grantee)

    def grant(self, on, privileges, grantee_type, grantee):
        return self.governed.grant(on, privileges, grantee_type, grantee)

    def revoke(self, on, privileges, grantee_type, grantee):
        return self.governed.revoke(on, privileges, grantee_type, grantee)

    def access(self, user, on):
        return self.governed.access(user, on)

    # ADR-057: the alerts, as core.engine.Engine calls them (fake_alerts keeps the contract)
    def alerts(self):
        return self.alerting.list()

    def alert(self, name):
        return self.alerting.detail(name)

    def alert_channels(self):
        return self.alerting.channels()

    def pause_alert(self, name):
        return self.alerting.change(name, "pause")

    def resume_alert(self, name):
        return self.alerting.change(name, "resume")

    def snooze_alert(self, name, duration):
        return self.alerting.change(name, "snooze", duration=duration)

    def ack_alert(self, name, key=None):
        return self.alerting.change(name, "ack", key=key)

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
        self._session()
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
        self._session()
        yield {"txn_id": 1, "user_id": "u1", "amount": 150, "_weight": 1}
        yield {"txn_id": 1, "user_id": "u1", "amount": 150, "_weight": -1}

    def snapshot_rows(self):
        """The view as a snapshot subscription starts from it: the rows a read would give."""
        return [{"txn_id": r[0], "user_id": r[1], "amount": r[2], "_weight": 1} for r in self.rows]

    def mirror(self, view, filters=None):
        self._session()
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
                        # A node with no pravaha.docs.base-url set, which is the default: the field stays
                        # in the body and is empty (DOCX-21).
                        "helpUrl": "",
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

    def explain(self, sql, level="physical", **_registration):
        # keys/retention/sink/name are ignored: this fake is an engine that answers no fingerprint.
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
                    "helpUrl": ""} if stop else None)
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
        replacing = next((q.fingerprint for q in self._queries if q.name == name), None)
        made = {
            "name": name, "state": "BACKFILLING", "sql": sql, "candidate": "newfp",
            "replacing": replacing,
            "sink": None, "options": options, "owner": "console",
            "startedAt": "2026-09-19T09:00:00Z", "cutOverAt": None, "rollbackUntil": None,
            "rollbackAvailable": False,
            "backfill": {"historyRows": 0, "liveRows": 0, "rowsPerSecond": 0.0, "partitions": 4,
                         "partitionsLive": 0, "historyComplete": False,
                         "rateLimit": int(rate_limit or 0), "paused": False, "lagSeconds": None},
            # Oldest first, and a seam per entry: the running version has served the name
            # from the beginning, and a cutover adds the frontier the next one took over at.
            "history": ([{"fromFrontier": None, "version": replacing}]
                        if self.history_carried else None),
            "failure": None,
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
        if found["history"] is not None:
            found["history"] = [*found["history"],
                                {"fromFrontier": found["backfill"]["historyRows"],
                                 "version": found["candidate"]}]
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

    # ------------------------------------------ the time-travel debugger (ADR-048, B9)
    #
    # A fork, answered from memory: the same plan's node ids as ``query_plan`` gives, a fixed
    # replay read round-robin across two partitions in a fixed order, and an aggregate that
    # holds groups where the plan has one. Fixed, because a screenshot of a stepped session
    # has to be the same one tomorrow, and because "the same session twice gives the same
    # answers" is the property ADR-048 3 arranges and a fake that drifted could not show.

    def _session_or_refuse(self, session_id: str) -> dict:
        found = self.debug_sessions_by_id.get(session_id)
        if found is None:
            raise EngineHttpError(
                400, f"no debug session answers to '{session_id}': it was ended, or nobody "
                     "touched it for long enough that this node released it", "PRV-8013")
        return found

    def debug_checkpoints(self, name):
        self._check()
        if name in self.administer_refused:
            raise EngineHttpError(
                403, f"console may not debug '{name}': {self.administer_refused[name]}",
                "PRV-7002")
        return list(self.checkpoints_by_query.get(name, []))

    def debug_fork(self, name, checkpoint_id=None):
        self._check()
        if name in self.administer_refused:
            raise EngineHttpError(
                403, f"console may not debug '{name}': {self.administer_refused[name]}",
                "PRV-7002")
        held = list(self.checkpoints_by_query.get(name, []))
        if not held:
            raise EngineHttpError(
                400, f"there is no checkpoint of '{name}' to fork a debug session from: this "
                     "query has not taken one yet", "PRV-8011")
        if checkpoint_id is not None and checkpoint_id not in held:
            raise EngineHttpError(
                400, f"checkpoint {checkpoint_id} of '{name}' has been pruned; this node still "
                     f"holds {', '.join(str(c) for c in held)}", "PRV-8011")
        if len(self.debug_sessions_by_id) >= self.debug_sessions_max:
            raise EngineHttpError(
                400, f"this node already holds {self.debug_sessions_max} debug sessions "
                     f"({', '.join(self.debug_sessions_by_id)}); end one before forking another",
                "PRV-8014")
        query = next((q for q in self._queries if q.name == name), None)
        if query is None:
            raise EngineHttpError(404, f"no query is registered as '{name}'", "PRV-8003")
        forks = sum(1 for call in self.debug_calls if call[0] == "fork")
        session_id = "dbg-" + DEBUG_SESSION_IDS[forks % len(DEBUG_SESSION_IDS)]
        self.debug_calls.append(("fork", name, checkpoint_id))
        nodes = _shaped_plan(query.sql)["nodes"]
        grouped = any(n["stateful"] for n in nodes)
        made = {
            "id": session_id, "query": name, "sql": query.sql,
            "checkpointId": checkpoint_id if checkpoint_id is not None else held[0],
            "owner": "console", "startedAt": "2026-09-19T09:00:00Z",
            "lastUsedAt": "2026-09-19T09:00:00Z", "steps": 0, "rowsConsumed": 0,
            "viewSize": 2, "watermarkNanos": None, "sinksDisabled": True, "streams": ["txn"],
        }
        self.debug_sessions_by_id[session_id] = made
        # Everything the fork is holding, kept beside the status the console reads.
        self._debug_forks[session_id] = {
            "nodes": nodes, "grouped": grouped, "consumed": 0,
            "groups": {"u1": 1, "u2": 1} if grouped else {},
            "rows": [["1", "u1", "150"], ["2", "u2", "900"]] if not grouped else [],
        }
        return dict(made)

    def debug_sessions(self):
        self._check()
        return [dict(s) for s in self.debug_sessions_by_id.values()
                if s["query"] not in self.administer_refused]

    def debug_session(self, session_id):
        self._check()
        found = self.debug_sessions_by_id.get(session_id)
        return dict(found) if found is not None else None

    def debug_step(self, session_id, step):
        self._check()
        session = self._session_or_refuse(session_id)
        fork = self._debug_forks[session_id]
        kind, count, watermark = _debug_request(step)
        self.debug_calls.append(("step", session_id, step))
        rows_in, changes = [], []
        for _ in range(count):
            if fork["consumed"] >= len(DEBUG_REPLAY):
                break
            stream, partition, offset, weight, at, values = DEBUG_REPLAY[fork["consumed"]]
            fork["consumed"] += 1
            rows_in.append({"stream": stream, "partition": partition, "offset": offset,
                            "weight": weight, "eventTimeNanos": at, "values": list(values)})
            changes.extend(_debug_apply(fork, values))
        if watermark is not None:
            session["watermarkNanos"] = watermark
        session["steps"] += 1
        session["rowsConsumed"] += len(rows_in)
        session["viewSize"] = (len(fork["groups"]) if fork["grouped"] else len(fork["rows"]))
        exhausted = fork["consumed"] >= len(DEBUG_REPLAY)
        return {
            "session": session_id, "sequence": session["steps"], "kind": kind,
            "rowsIn": rows_in,
            "operators": _debug_operator_flow(fork["nodes"], rows_in, len(changes)),
            "viewChanges": changes,
            "watermarkNanos": session["watermarkNanos"],
            "rowsConsumed": session["rowsConsumed"], "viewSize": session["viewSize"],
            "exhausted": exhausted,
            "stopped": "the replay has no more rows" if exhausted else _DEBUG_STOPPED[kind],
        }

    def debug_state(self, session_id):
        self._check()
        self._session_or_refuse(session_id)
        fork = self._debug_forks[session_id]
        if not fork["grouped"]:
            return []
        return [{"id": "aggregate#0", "kind": "aggregate", "label": "groups",
                 "entries": len(fork["groups"])}]

    def debug_inspect(self, session_id, operator, key=None, offset=0, limit=50):
        self._check()
        self._session_or_refuse(session_id)
        fork = self._debug_forks[session_id]
        if not fork["grouped"] or operator != "aggregate#0":
            raise EngineHttpError(
                400, f"this fork holds no operator state called '{operator}'", "PRV-8015")
        if limit < 1 or limit > 500:
            raise EngineHttpError(
                400, f"a page of {limit} entries is outside 1..500; an unbounded page of a "
                     "join holding ten million rows takes the node down", "PRV-8015")
        held = sorted(fork["groups"].items())
        if key:
            held = [pair for pair in held if pair[0] == key]
        window = held[offset:offset + limit]
        return {"id": operator, "kind": "aggregate", "key": key, "offset": offset,
                "limit": limit, "total": len(held),
                "hasMore": offset + len(window) < len(held),
                "entries": [{"key": group, "values": {"n": str(count)}}
                            for group, count in window]}

    def debug_view(self, session_id):
        self._check()
        self._session_or_refuse(session_id)
        fork = self._debug_forks[session_id]
        if fork["grouped"]:
            return [{"weight": 1, "values": [group, str(count)]}
                    for group, count in sorted(fork["groups"].items())]
        return [{"weight": 1, "values": list(row[:2])} for row in fork["rows"]]

    def debug_export(self, session_id, name):
        self._check()
        session = self._session_or_refuse(session_id)
        if not str(name).strip():
            raise EngineHttpError(
                400, "an exported fixture needs a name", "PRV-8015")
        class_name = "".join(part[:1].upper() + part[1:]
                             for part in re.findall(r"[A-Za-z0-9]+", name)) + "FixtureTest"
        path = ("pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures/"
                + class_name + ".java")
        self.debug_calls.append(("fixture", session_id, name))
        return {"className": class_name, "path": path, "source": FIXTURE_SOURCE.format(
            class_name=class_name, sql=session["sql"], rows=session["rowsConsumed"])}

    def debug_end(self, session_id):
        self._check()
        self._session_or_refuse(session_id)
        self.debug_calls.append(("end", session_id, None))
        self.debug_sessions_by_id.pop(session_id, None)
        self._debug_forks.pop(session_id, None)

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
        return {"principal": self.principal_name(), "tenant": "public", "roles": ["admin"] if self.audit_allowed else [],
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

    def tenants(self):
        self._check()
        rows = [dict(t) for t in self.tenant_rows]
        if not self.audit_allowed:
            rows = [t for t in rows if t["tenant"] == "public"]
        return {"scope": "all" if self.audit_allowed else "own", "defaults": dict(self.tenant_defaults),
                "tenants": rows}

    def audit(self, since=None, until=None, principal=None, view=None, action=None, decision=None,
              limit=100, cursor=None):
        """The engine's page semantics: newest first, filtered, ``cursor`` continues below a sequence."""
        self._check()
        asked = {"since": since, "until": until, "principal": principal, "view": view,
                 "action": action, "decision": decision, "limit": limit, "cursor": cursor}
        self.audit_calls.append({k: v for k, v in asked.items() if v is not None})
        if not self.audit_allowed:
            raise EngineHttpError(403, self.principal_name() + " may not read the audit trail: " + AUDIT_REFUSAL, "PRV-7002")
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


#: Three tenants: one well within its limits, one at its query limit with state grown past its
#: state quota (which only state can do, ADR-050 section 2), and one limited to zero queries.
TENANTS = [
    {"tenant": "public", "queries": 4, "computations": 3, "stateKeys": 1200,
     "limits": {"maxQueries": 20, "maxStateKeys": None}, "queryRefusals": 0, "stateRefusals": 0},
    {"tenant": "risk", "queries": 5, "computations": 5, "stateKeys": 52000,
     "limits": {"maxQueries": 5, "maxStateKeys": 50000}, "queryRefusals": 3, "stateRefusals": 1},
    {"tenant": "ops", "queries": 0, "computations": 0, "stateKeys": 0,
     "limits": {"maxQueries": 0, "maxStateKeys": None}, "queryRefusals": 2, "stateRefusals": 0},
]

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


#: B9/ADR-048. Session ids, handed out in order: a screenshot of a session has its id in it,
#: and a random one would make every screenshot differ from yesterday's.
DEBUG_SESSION_IDS = ["7f3a2b91c604", "5c1d8e40ab73", "2e90f5c1d884", "9b47ac02e15f"]

#: What a fork replays, as (stream, partition, offset, weight, event time, values). Two
#: partitions read round-robin in a fixed order, which is what the engine's replay does and
#: what buys a session the property that stepping it twice gives the same answers.
DEBUG_REPLAY = [
    ("txn", 0, "8841", 1, 1740000000000000000, ["3", "u2", "900"]),
    ("txn", 1, "8842", 1, 1740000001000000000, ["4", "u2", "40"]),
    ("txn", 0, "8843", 1, 1740000002000000000, ["5", "u1", "260"]),
    ("txn", 1, "8844", 1, 1740000003000000000, ["6", "u3", "1500"]),
    ("txn", 0, "8845", 1, 1740000004000000000, ["7", "u1", "75"]),
    ("txn", 1, "8846", 1, 1740000005000000000, ["8", "u3", "620"]),
]

#: Why each kind of step stopped, when it was not the replay running out.
_DEBUG_STOPPED = {
    "ROW": "one row, as asked",
    "ROWS": "the count was reached",
    "COMMIT": "the view changed",
    "WATERMARK": "event time was advanced; no row was read",
    "UNTIL": "the view satisfies the comparison",
}

FIXTURE_SOURCE = """\
// Generated by the Pravaha time-travel debugger. What this asserts is the answer over the
// rows below FROM EMPTY, not over the history that preceded the checkpoint the session was
// forked from -- and it was produced by replaying them through an empty copy of the query.
package com.ash.messaging.pravaha.it.fixtures;

class {class_name} {{
    private static final String SQL = "{sql}";
    // {rows} rows, in the order the session consumed them.
}}
"""


def _debug_request(step: str) -> tuple[str, int, int | None]:
    """A step spec as (kind, how many rows to read, the watermark to set).

    Refused by name, never guessed at: an unreadable step is PRV-8015, as the engine's own
    parser answers it, rather than quietly meaning "one row".
    """
    asked = str(step or "").strip()
    if asked == "row":
        return "ROW", 1, None
    if asked == "commit":
        return "COMMIT", 1, None
    if asked.startswith("rows:"):
        count = asked[5:]
        if not count.isdigit() or int(count) < 1:
            raise EngineHttpError(400, f"'{asked}' asks for no rows", "PRV-8015")
        return "ROWS", int(count), None
    if asked.startswith("watermark:"):
        nanos = asked[10:]
        if not nanos.isdigit():
            raise EngineHttpError(
                400, f"'{asked}' is not a watermark; a watermark is nanoseconds since the "
                     "epoch and it does not go backwards", "PRV-8015")
        return "WATERMARK", 0, int(nanos)
    if asked.startswith("until:"):
        parts = asked.split(":")
        if len(parts) != 4 or parts[2] not in ("=", "!=", "<", "<=", ">", ">="):
            raise EngineHttpError(
                400, f"'{asked}' is not a comparison; it is until:<column>:<op>:<value> with "
                     "one of = != < <= > >=", "PRV-8015")
        return "UNTIL", 3, None
    raise EngineHttpError(
        400, f"'{asked}' is not a step; it is row, rows:N, commit, watermark:<nanos> or "
             "until:<column>:<op>:<value>", "PRV-8015")


def _debug_apply(fork: dict, values: list) -> list[dict]:
    """One replayed row through the fork's view, as the changes it made with their weights."""
    if fork["grouped"]:
        group = values[1]
        before = fork["groups"].get(group)
        after = (before or 0) + 1
        fork["groups"][group] = after
        changes = [] if before is None else [{"weight": -1, "values": [group, str(before)]}]
        return changes + [{"weight": 1, "values": [group, str(after)]}]
    # The stateless plan is Scan -> Filter(amount > 100) -> Project: a row the filter rejects
    # reaches the view as nothing at all, which is exactly the case a view alone cannot
    # explain and the operator lines can.
    if int(values[2]) <= 100:
        return []
    fork["rows"].append(list(values))
    return [{"weight": 1, "values": list(values[:2])}]


def _debug_operator_flow(nodes: list[dict], rows_in: list[dict], changes: int) -> list[dict]:
    """Rows in and out per plan node, root first, keyed by the plan's own node ids.

    The same ids ``query_plan`` publishes its per-operator numbers under (ADR-048 4a), so a
    step and the plan graph cannot show an operator two answers. The numbers are the whole
    point of the panel: a filter that rejected the row reads ``in=1 out=0`` and an aggregate
    that restated a group reads ``in=1 out=2``, and from the view alone they look the same.
    """
    read = len(rows_in)
    kept = sum(1 for row in rows_in if int(row["values"][2]) > 100)
    flow, carried = [], read
    for node in reversed(nodes):  # leaf first: the scan is what sees them all
        rows_out = {"Filter": kept, "Aggregate": changes}.get(node["operator"], carried)
        flow.append({"id": node["id"], "kind": node["operator"].lower(),
                     "label": node["detail"], "rowsIn": carried, "rowsOut": rows_out})
        carried = rows_out
    # Reported root first, as the plan lists its nodes and as the CLI prints them.
    return list(reversed(flow))


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
