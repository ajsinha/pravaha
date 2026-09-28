"""
The deck, as data. Parts 6 to 9: changing a running query (ADR-046), the
connectors in plugins/, security and identity (ADR-031, ADR-052, ADR-050), and
operating it (deployment, console, CLI, SDKs, metrics).

One deck split across several modules only to keep each file short; read them in
order (see GUIDE.md).

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

PART6: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "6",
        "title": "Changing a running query",
        "sub": "A query's SQL is changed without taking its answer away. The new version runs beside "
        "the old, replays the source, and takes the name only when both have consumed exactly the "
        "same input — a position, not a moment.",
        "points": [
            "Why not at a moment",
            "Blue/green, step by step",
            "What readers, subscribers and sinks see",
            "The statement, its options and its refusals",
        ],
        "source": "Source: docs/adr/046-a-replacement-meets-the-running-version-at-a-position.md; "
        "docs/CONTINUOUS_QUERIES.md §8.1.",
    },
    {
        "kind": "table",
        "kicker": "ADR-046 · alternatives considered",
        "title": "A cutover at a moment is wrong in a way nothing reports",
        "rows": [
            ["Alternative", "What goes wrong"],
            ["Swap at a wall-clock instant", "Two versions running at slightly different speeds either "
             "drop records between them or emit records from both. The numbers are merely a little off."],
            ["Swap when the candidate has read as many rows, or run long enough",
             "Both are proxies, wrong exactly when the input rate changes — which is when somebody is "
             "most likely to be deploying."],
            ["Take the name at once and let the new version warm up",
             "It takes the answer away from whoever is reading it; the view they get back is an "
             "aggregate missing its history."],
            ["Move the checkpoint files at the cutover",
             "A window in which a crash leaves the name pointing at the other version's state."],
            ["Chosen: meet at a position",
             "Cut over when both versions have consumed exactly the same input, compared as source "
             "positions per partition, with both feeds stopped."],
        ],
        "col_w": [1.6, 3.4],
        "size": 15.5,
        "source": "Source: docs/adr/046-a-replacement-meets-the-running-version-at-a-position.md "
        "'Decision' and 'Alternatives considered' (paraphrased closely).",
    },
    {
        "kind": "flow",
        "kicker": "Blue/green · CREATE OR REPLACE",
        "title": "Replay, splice at the running version's position, then swap",
        "steps": [
            ("Beside, as a shadow", "Its own state, checkpoints and readers; no reader can reach it"),
            ("Read the history", "The source from the beginning, throttled, one record at a time"),
            ("Splice at the seam", "Read to the running version's exact position, then a reader opens at it"),
            ("Caught up", "Every partition has reached the live stream"),
            ("Cut over", "Both feeds stopped at the same input; the name moves"),
        ],
        "box_h": 2.0,
        "items": [
            ("A reader sees the old answer up to the seam and the new one after it",
             "No gap and nothing counted twice. A history that runs out before the seam stops with "
             "PRV-4013 rather than reading past it; a cutover that cannot find the same position in "
             "thirty seconds is refused with PRV-4014."),
            ("A replacement survives a restart",
             "The journal records it pending (P), the name's change (C) and an end that never took "
             "the name (E); the candidate resumes from its own checkpoints."),
        ],
        "size": 16,
        "source": "Source: docs/CONTINUOUS_QUERIES.md §8.1 ('What happens, in order'; PRV-4013, "
        "PRV-4014); docs/adr/046 §1 (OffsetSplicedReader), §4 (P, C, E records).",
    },
    {
        "kind": "table",
        "kicker": "At the cutover",
        "title": "What each party sees when the name changes version",
        "rows": [
            ["Party", "Sees"],
            ["A reader of the name", "One version's view or the other's, resolved at the moment of the "
             "read — never a mixture, and neither is behind"],
            ["A subscriber", "Every commit the replaced version made, then the end of its subscription "
             "with PRV-4019: told, rather than handed another query's changes on top"],
            ["The sink", "Follows the name at a checkpoint boundary and is sent only the difference "
             "between what it holds and the new view, as one batch"],
            ["Whoever wants it back", "The replaced version keeps running for rollback.retention (an hour "
             "by default), so a rollback is one swap, not a second backfill"],
            ["The console", "Backfill progress with no ETA and no percentage — a source does not say how "
             "much history it holds — and cutover and rollback confirmed by the typed name"],
        ],
        "col_w": [1.3, 3.8],
        "size": 15.5,
        "source": "Source: docs/adr/046 §2–§3 and 'Consequences'; docs/CONTINUOUS_QUERIES.md §8.1 "
        "('What a reader / subscriber / sink sees'); README.md 'The console' (backfill and cutover).",
    },
    {
        "kind": "code",
        "kicker": "The statement",
        "title": "Options that are ceilings, and refusals that are named",
        "code": [
            "CREATE OR REPLACE CONTINUOUS QUERY spend",
            "    KEYED BY (user_id)",
            "    WITH (backfill = 'history',",
            "          backfill.rate.limit = 5000,",
            "          cutover = 'manual')",
            "AS SELECT user_id, SUM(amount) AS total,",
            "          COUNT(*) AS payments",
            "   FROM txn",
            "   GROUP BY user_id,",
            "            TUMBLE(ts, INTERVAL '1' HOUR);",
        ],
        "code_w": 0.5,
        "rows": [
            ["Code", "Refused"],
            ["PRV-4018", "A source that cannot be replayed; an option not built (backfill.adaptive); a "
             "rate above the ceiling; a changed sink or retention"],
            ["PRV-4017", "A second replacement of one name; a drop while a candidate runs"],
            ["PRV-8003", "A computation shared with another name"],
            ["PRV-8022", "A principal from another tenant"],
            ["PRV-7002", "Anyone without administer on the name"],
        ],
        "col_w": [0.9, 3.0],
        "size": 14.5,
        "note": "backfill.rate.limit is a ceiling: an operator may lower it while it runs (pravaha "
        "throttle) and may not raise it. Eight gauges watch every query, replaced or not.",
        "source": "Source: docs/CONTINUOUS_QUERIES.md §8.1 (the statement verbatim, reflowed; options "
        "table; refusals table); docs/adr/050 §3 / docs/SECURITY.md 'A replacement stays in its tenant' "
        "(PRV-8022); README.md 'Blue/green replacement' (eight gauges).",
    },
]

PART7: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "7",
        "title": "Connectors",
        "sub": "Ten connector plugins, each a jar with a service declaration. Every source says what it "
        "can promise — ordered or not, exactly once or at least once — and the registry holds a "
        "query to that promise.",
        "points": [
            "Sources, and what each promises",
            "Sinks, and what each delivers",
            "Change data capture without Debezium",
            "What a connector must earn",
        ],
        "source": "Source: plugins/ (ten connector plugins and pravaha-cluster-zookeeper); "
        "docs/CONNECTORS.md; README.md 'Sources', 'Sinks'.",
    },
    {
        "kind": "table",
        "kicker": "Sources · plugins/",
        "title": "Nine sources, two lookups",
        "rows": [
            ["Plugin", "Reads", "Promise and pushdown"],
            ["filesystem", "One file, bounded or followed like tail -f", "Ordered; shared exactly (ADR-054)"],
            ["feedfile", "A directory of CSV or Parquet files arriving over time", "—"],
            ["delta", "A Delta Lake table, deletion vectors included", "Reader per query for now"],
            ["jdbc", "A table or any SELECT, polled by a watermark column",
             "Filters, projections, and COUNT/SUM as one partial per page pushed in"],
            ["aerospike", "A set, scanned by last-update time", "Filters and projections pushed in; deletes: detect"],
            ["cassandra", "A table, by token() range", "Key restrictions pushed in; deletes: detect"],
            ["postgres-cdc", "Logical replication; initial snapshot available",
             "Exactly once; slot confirmed only at checkpoints"],
            ["mysql-cdc", "The row-based binlog, read as a replica", "Exactly once from file and offset; changes only"],
            ["kafka", "A topic, one reader per partition, read_committed",
             "Exactly once from checkpoint offsets; shared exactly"],
            ["jdbc-lookup · aerospike-lookup", "A dimension table for a temporal join", "Asked, not consumed"],
        ],
        "col_w": [1.2, 2.3, 2.3],
        "size": 14,
        "source": "Source: README.md 'Sources'; docs/CONNECTORS.md §1 (kinds and shipped examples, "
        "lookup names with suffix); plugins/*/src/main/resources/META-INF/services/ (which plugin "
        "declares which kind); docs/adr/054 ('Which sources').",
    },
    {
        "kind": "table",
        "kicker": "Sinks · plugins/",
        "title": "Six sinks, each with its delivery said at registration",
        "rows": [
            ["Sink", "Writes", "Delivery"],
            ["filesystem", "Append-only files", "At least once"],
            ["aerospike-sink", "Upsert and delete by key, composite keys", "Effectively once"],
            ["jdbc-sink", "A table you create: upsert and delete by key, or append; staging table or "
             "PostgreSQL's PREPARE TRANSACTION", "Exactly once on a checkpointed node"],
            ["kafka-sink", "Keyed upserts with a tombstone per retraction (JSON, Avro or Protobuf), or a "
             "JSON changelog", "Exactly once to a read_committed consumer"],
            ["delta-sink", "A Delta table kept equal to the view, or a changelog; on Delta Kernel, not "
             "Spark", "Exactly once: one Delta commit per checkpoint"],
            ["iceberg-sink", "A local-filesystem Iceberg table, upsert by equality deletes or changelog; "
             "on iceberg-core, not Spark", "Exactly once: one snapshot per checkpoint"],
        ],
        "col_w": [1.1, 3.1, 1.7],
        "size": 14.5,
        "note": "A sink with a configured schema or key reports it, and a registration that does not "
        "match is refused before the sink opens (PRV-8010).",
        "source": "Source: README.md 'Sinks'; docs/CONNECTORS.md §1 (StreamSinkPlugin row); "
        "docs/RELEASE_NOTES.md 0.2.0 (Avro/Protobuf, commit.mode: prepared) and 'Unreleased' "
        "(iceberg-sink).",
    },
    {
        "kind": "split",
        "kicker": "Change data capture · ADR-041",
        "title": "Change data capture on the database's own protocol",
        "left": {
            "head": "Why not Debezium, at least first",
            "items": [
                ("A second framework in the process",
                 "Embedded Debezium brings the Kafka Connect API and client: its own lifecycle, "
                 "threading, offset storage and configuration, and tens of megabytes."),
                ("The driver already does it",
                 "postgres-cdc is a logical-replication reader on the PostgreSQL JDBC driver; "
                 "mysql-cdc reads the binlog as a registered replica."),
            ],
            "size": 16,
        },
        "right": {
            "head": "What arrives, in weights",
            "items": [
                ("Insert +1, delete −1, update both",
                 "The delete carries the whole old row. Whole transactions, exactly once."),
                ("Confirmed only at checkpoints",
                 "The PostgreSQL slot advances when a checkpoint is durable."),
                ("Misconfiguration refused by name",
                 "binlog_format other than ROW, or a user without replication grants: PRV-5152."),
            ],
            "size": 16,
        },
        "source": "Source: docs/adr/041-change-data-capture-without-debezium.md; README.md 'Sources' "
        "(postgres-cdc, mysql-cdc); docs/RELEASE_NOTES.md 'Unreleased' (mysql-cdc, PRV-5152).",
    },
    {
        "kind": "bullets",
        "kicker": "What a connector must earn · ADR-028",
        "title": "A connector declares what it can promise, and is held to it",
        "items": [
            ("A jar with a service declaration",
             "Nothing in the engine is edited or rebuilt. The server jar carries filesystem alone; "
             "every other plugin goes on the node's classpath, and naming one that is not there is "
             "refused at startup (PRV-5090)."),
            ("It earns its place by a capability, not by breadth",
             "A shallow connector declaring capabilities it lacks causes silent data loss during "
             "recovery, months later."),
            ("A source that repeats rows must say so",
             "An aggregate or join over it is refused (PRV-2042) until deletes: detect lets a scan "
             "retract what changed."),
            ("Connections can be encrypted",
             "JDBC, PostgreSQL CDC, Aerospike, Cassandra and Kafka (CONNECTOR_TLS.md). Kafka's lz4 "
             "is refused: it needs native code the build refuses (ADR-053)."),
        ],
        "source": "Source: docs/CONNECTORS.md §1 (PRV-5090, CFG-4); docs/system_design.md §33 ADR-028 "
        "row; docs/CONTINUOUS_QUERIES.md §2.1 (PRV-2042, deletes: detect); README.md 'Sources' and "
        "'Boundaries' (TLS; lz4).",
    },
]

PART8: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "8",
        "title": "Security and identity",
        "sub": "Authorization is enforced by Pravaha on every read, at the layer that produces the "
        "row. The engine keeps its own users, keys and sessions, stores only what cannot be "
        "reversed, and a tenant shares only with itself.",
        "points": [
            "Why not delegate to the store (ADR-031)",
            "Three seams and one rule",
            "The engine is the identity authority (ADR-052)",
            "Tenancy (ADR-050)",
            "What is not built",
        ],
        "source": "Source: docs/adr/031-authorization-at-the-pravaha-layer.md; docs/adr/052; "
        "docs/adr/050; docs/SECURITY.md.",
    },
    {
        "kind": "cards",
        "kicker": "ADR-031",
        "title": "The store is structurally unable to authorize a view",
        "cols": 2,
        "cards": [
            ("1", "A view is derived data the store has never seen",
             "No record in the store has permissions matching “u4's gold-tier total for the window "
             "ending at 12:05”."),
            ("2", "A change feed is read once and shared",
             "Authorizing at the source means a read per principal — the amplification the design "
             "removes — or a superuser read, which enforces nothing."),
            ("3", "The store may have nothing to enforce with",
             "Aerospike Community Edition has no row-level security at all."),
            ("4", "A continuous query has no caller",
             "It runs for months while nobody is connected; there is no session to carry down."),
        ],
        "source": "Source: docs/adr/031-authorization-at-the-pravaha-layer.md 'Why it cannot be "
        "delegated to the store'.",
    },
    {
        "kind": "split",
        "kicker": "Three seams, one rule",
        "title": "Who is this, what may they read, and what were they told",
        "left": {
            "head": "The seams",
            "rows": [
                ["Seam", "Answers"],
                ["TokenVerifier", "Credential in, Principal (id, tenant, roles) out"],
                ["SecurityPolicy", "May this principal read this view — allow, allow with a row filter, deny"],
                ["AuditSink", "Every decision, allow and deny alike"],
            ],
            "col_w": [1.2, 2.6],
            "size": 14.5,
        },
        "right": {
            "head": "What follows",
            "items": [
                ("The soundness rule",
                 "A row filter applies at read time iff the view carries every column it names; "
                 "otherwise the read is refused (PRV-7003)."),
                ("Filters are part of the fingerprint",
                 "Different entitlements never share a computation."),
                ("A registration is a standing read",
                 "mayRead is asked for every stream in the plan; a sink is a standing write "
                 "(mayWriteTo), audited by name."),
                ("A row-filtered principal cannot subscribe",
                 "It reads the view instead, which honours the filter."),
            ],
            "size": 15,
        },
        "source": "Source: docs/adr/031 (three pieces; soundness rule); docs/SECURITY.md 'What a "
        "registration is allowed to read / write', 'A conditional entitlement cannot subscribe' "
        "(STRM-13).",
    },
    {
        "kind": "table",
        "kicker": "Identity · ADR-052",
        "title": "The engine keeps users, keys and sessions, and nothing reversible",
        "rows": [
            ["Secret", "Stored as", "Rules"],
            ["Password", "Argon2id (64 MiB, t=3, p=4); PBKDF2 where Argon2 is unavailable",
             "12+ characters from 3 of 4 classes; not one of the last 5; 90-day maximum age"],
            ["API key", "The same KDF; secret shown once",
             "prv_<env>_<keyid>_<secret>; roles a subset of the holder's; expiry mandatory, 90 days "
             "default, 365 at most; 7-day rotation overlap"],
            ["Session token", "SHA-256 of 256 random bits",
             "30 minutes idle, 12 hours absolute, at most 3 per person"],
            ["Login failures", "Recorded before the refusal is answered",
             "5 inside 15 minutes lock the account for 30"],
        ],
        "col_w": [1.0, 2.1, 2.9],
        "size": 14.5,
        "note": "An append-only, fsync'd journal holds it — no database to run. The console signs "
        "each person in against the engine and acts as them. MFA and SSO were dropped by the owner.",
        "source": "Source: docs/adr/052-the-engine-is-the-identity-authority.md (secrets table, policy, "
        "lockout, API keys, sessions, store; status line: stages 4 and 5 dropped 2026-09-27); README.md "
        "'Security'.",
    },
    {
        "kind": "split",
        "kicker": "Tenancy · ADR-050",
        "title": "A tenant owns names and state, and shares only with itself",
        "left": {
            "head": "A tenant owns",
            "items": [
                ("Its names, and the computations behind them",
                 "The tenant is in the fingerprint: the same SQL from two tenants is two computations."),
                ("The keys those views hold",
                 "What max-state-keys counts; max-queries counts names."),
                ("Refused by name at registration",
                 "PRV-8020 or PRV-8021, before a sink opens; audited as DENY; a running query is "
                 "never stopped by a quota. Only its own tenant may replace a name (PRV-8022)."),
            ],
            "size": 15.5,
        },
        "right": {
            "head": "A tenant does not scope, by decision",
            "items": [
                ("View names", "Unique on the node, whichever tenant holds them."),
                ("Reads, sources and sinks", "The policy decides; no tenant boundary it did not draw."),
                ("Lanes and CPU", "Shared; per-tenant CPU scheduling is not built."),
                ("Operator state outside the view", "Bounded per query by its ceilings instead."),
            ],
            "size": 15.5,
        },
        "source": "Source: docs/adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md "
        "§1–§2; docs/SECURITY.md 'Tenants'.",
    },
    {
        "kind": "bullets",
        "kicker": "Stated plainly",
        "title": "What the security model does not do yet",
        "items": [
            ("Drop, pause and resume are authorized as reads",
             "mayAdminister defaults to mayRead: anyone entitled to any rows of a view may drop it "
             "for everyone. A deployment that needs ownership must override mayAdminister."),
            ("The HTTP API's TLS is Spring Boot's",
             "Flight carries TLS by default; /api/v1 is reached over HTTPS only through server.ssl.*."),
            ("No mTLS between nodes, no OIDC or JWT verifier out of the box",
             "There are no nodes yet; TokenVerifier is the seam for a verifier."),
            ("No column masking; grants live in the deployment's policy",
             "The console shows grants and does not edit them."),
        ],
        "source": "Source: docs/SECURITY.md 'Drop, pause and resume are authorized as reads' (SX-2), "
        "'Transport', 'What is not built'; README.md 'Boundaries' (grants).",
    },
]

PART9: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "9",
        "title": "Operating it",
        "sub": "Three ways to run one engine, a console that is a product and not a dashboard, two "
        "command lines, two SDKs that survive a restart, and metrics that say what is not "
        "measured rather than reporting zero.",
        "points": [
            "Three ways to run it",
            "The console",
            "pravaha and pravaha-engine",
            "SDKs, and reconnecting",
            "What to watch",
            "The time-travel debugger",
        ],
        "source": "Source: README.md 'How it is built' (Deployment), 'The console', 'Try it', "
        "'Observability', 'Time-travel debugger'.",
    },
    {
        "kind": "cards",
        "kicker": "Deployment",
        "title": "Three ways to run one engine",
        "cols": 3,
        "cards": [
            ("IN PROCESS", "pravaha-embedded",
             "PravahaEngine runs the whole loop inside an application — streams, bindings, queries, "
             "pushed rows, SQL reads, subscriptions, journal and checkpoints — with no Spring and "
             "no network."),
            ("IN YOUR SPRING APP", "pravaha-spring-boot-starter",
             "An engine bean from pravaha.*, PravahaTemplate, @PravahaListener delivering committed "
             "changes to a method, and a @PravahaTest slice."),
            ("AS A SERVER", "pravaha-server",
             "The engine, Flight SQL, the PostgreSQL gateway and /status. A non-root image on a JDK 21 "
             "glibc base, and a Helm chart that installs one node as a StatefulSet."),
        ],
        "note": "The engine core contains no Spring, enforced by the build (ADR-019). The console is "
        "a separate process on the published SDK, so it cannot reach past the public API (ADR-024).",
        "source": "Source: README.md 'Embedding' and 'How it is built' (Deployment; ADR-019, ADR-024, "
        "ADR-045, ADR-047, ADR-053).",
    },
    {
        "kind": "bullets",
        "kicker": "The console",
        "title": "Each persona lands on its own screen",
        "items": [
            ("Analyst: the SQL Workbench",
             "Monaco with catalog-aware completion, validation as you type with one-click fixes, the "
             "plan drawn as a graph, and registration with keys picked by name."),
            ("Developer: Views",
             "Point queries and copy-paste client code for both SDKs, psql and the CLI."),
            ("Operator: Operations",
             "The engine's metrics read into a verdict — healthy, and if not, where — with backfill "
             "and cutover, and any view watched live with its +1/−1 weights."),
            ("Administrator: Admin",
             "Users, keys and sessions; the audit trail, filterable and paged; and Admin → Lanes."),
        ],
        "note": "FastAPI, server-rendered, with every asset vendored so it runs air-gapped. All eight "
        "§23.18 journeys run in headless Chrome; the manual WCAG 2.2 AA audit is not done.",
        "source": "Source: README.md 'The console'; docs/RELEASE_NOTES.md 'Unreleased' (Admin → "
        "Lanes); commit ff9fae5d (console per-user sign-in, account and admin pages).",
    },
    {
        "kind": "split",
        "kicker": "Two command lines",
        "title": "pravaha talks to a node; pravaha-engine runs one in process",
        "left": {
            "head": "pravaha — Python, with the Python SDK",
            "items": [
                ("Every command that talks to a running engine",
                 "query, register, queries, pause, resume, drop; replace, cutover, rollback, "
                 "throttle and the rest of a replacement; subscribe, dlq, debug; login, user, key, "
                 "session; lanes."),
                ("Installed with the SDK",
                 "pip install './sdk/python[flight]'."),
            ],
            "size": 16,
        },
        "right": {
            "head": "pravaha-engine — Java, no server",
            "items": [
                ("validate, explain, run, version",
                 "What needs the engine in-process, and carries no token."),
                ("A moved command says where it went",
                 "Typing one of the server commands prints where it lives now and exits 2."),
            ],
            "size": 16,
        },
        "source": "Source: docs/RELEASE_NOTES.md 'Unreleased' (the Java CLI is now pravaha-engine; "
        "commands moved to the Python CLI); docs/QUICKSTART.md (pip install); docs/ARCHITECTURE.md "
        "module table (pravaha-cli). Commit 90b14871.",
    },
    {
        "kind": "code",
        "kicker": "SDKs",
        "title": "A subscription that survives a server restart",
        "code": [
            "# Python",
            "for batch in client.subscribe(\"large_payments\",",
            "        snapshot=True, reconnect=True):",
            "    if batch.snapshot:",
            "        copy = {}   # a fresh snapshot: replace",
            "    apply(copy, batch)",
            "",
            "// Java",
            "ReconnectingSubscription s =",
            "  client.subscribeFromSnapshot(\"large_payments\",",
            "    Map.of(),",
            "    ReconnectingSubscription.Reconnect.defaults()",
            "        .onReconnected(copy::clear), copy::apply);",
            "s.run();",
        ],
        "code_w": 0.55,
        "items": [
            ("Reopened with backoff",
             "A stream ended by a restart, a broken connection or PRV-6105 is reopened from 250 ms to "
             "10 s apart, for up to five minutes by default."),
            ("Pair it with a snapshot",
             "The first batch after reopening is a fresh snapshot, so nothing committed while the "
             "node was down is lost or counted twice."),
            ("A permanent refusal is raised at once",
             "Other calls fail with PRV-1040, retryable, and work as soon as the node is back."),
        ],
        "size": 16,
        "source": "Source: docs/USER_GUIDE.md 'Surviving a restart: reconnect' (both snippets, "
        "reflowed); docs/RELEASE_NOTES.md 'Unreleased' (JavaSdkReconnectTest, test_reconnect.py).",
    },
    {
        "kind": "table",
        "kicker": "What to watch",
        "title": "Per-query metrics, and the ones to alert on",
        "rows": [
            ["Metric", "Answers"],
            ["pravaha_query_feed_stopped", "A source stopped mid-read while the query still says RUNNING"],
            ["pravaha_query_state_fraction", "State held against its ceiling, 0 to 1 — before PRV-4001"],
            ["pravaha_query_watermark_lag_seconds", "How far behind event time it is (NaN before a row)"],
            ["pravaha_query_backpressure_blocked_fraction", "Is this query the limit, or blocked by a neighbour"],
            ["pravaha_query_checkpoint_last_success_timestamp_seconds", "How much recovery would replay now"],
            ["pravaha_query_commit_latency_seconds (count, sum)", "An exact mean; no percentiles are "
             "published, because none is measured"],
            ["pravaha_lane_own_queries", "Rising with sharing on: the shared lanes are full"],
        ],
        "col_w": [3.3, 2.5],
        "size": 14.5,
        "note": "Per-operator rows, state and sampled self time appear on GET /api/v1/queries/{name}/plan "
        "when pravaha.metrics.operators is on — off by default, as it costs about 8 % of throughput.",
        "source": "Source: docs/OPERATIONS.md 'Watching a running node' (per-query and per-node tables; "
        "FEED-1; ADR-037 B1); README.md 'Observability' (8 %).",
    },
    {
        "kind": "bullets",
        "kicker": "Time-travel debugger · ADR-048",
        "title": "An incident, stepped by hand and exported as a test",
        "items": [
            ("A second computation nothing can read",
             "A query is forked from one of its retained checkpoints, reading the same sources from "
             "that checkpoint's offsets — every sink disabled, its view in no catalogue, its lanes "
             "its own."),
            ("Stepped one row, N rows, to a commit, a watermark or a value",
             "Each step reports the rows that entered with their weights, every operator's rows in "
             "and out, and the view's changes: a filter that rejected the row is told apart from an "
             "aggregate that produced a zero delta."),
            ("Deterministic, then a regression test",
             "Two sessions over one checkpoint given the same steps report identically; the session "
             "exports as a self-contained JUnit test that compiles and passes."),
        ],
        "source": "Source: README.md 'Time-travel debugger'; "
        "docs/adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md.",
    },
]

SLIDES = PART6 + PART7 + PART8 + PART9
