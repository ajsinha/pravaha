# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The deck, as data. Parts 6 to 10: changing a running query (ADR-046), answers built
on answers (ADR-056, ADR-057), the connectors in plugins/, security, identity and
governance (ADR-031, ADR-052, ADR-050, ADR-059, ADR-060), and
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
        "source": "Source: docs/design/adr/046-a-replacement-meets-the-running-version-at-a-position.md; "
        "docs/guides/CONTINUOUS_QUERIES.md §8.1.",
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
        "source": "Source: docs/design/adr/046-a-replacement-meets-the-running-version-at-a-position.md "
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
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §8.1 ('What happens, in order'; PRV-4013, "
        "PRV-4014); docs/design/adr/046 §1 (OffsetSplicedReader), §4 (P, C, E records).",
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
        "source": "Source: docs/design/adr/046 §2–§3 and 'Consequences'; docs/guides/CONTINUOUS_QUERIES.md §8.1 "
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
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §8.1 (the statement verbatim, reflowed; options "
        "table; refusals table); docs/design/adr/050 §3 / docs/operations/SECURITY.md 'A replacement stays in its tenant' "
        "(PRV-8022); README.md 'Blue/green replacement' (eight gauges).",
    },
]

CHAINS: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "7",
        "title": "Answers built on answers",
        "sub": "A query can read another query's view and follow its answer, exactly, across restarts. "
        "An alert follows a view's answer and says when a row enters it and when it leaves.",
        "points": [
            "Queries on queries (ADR-056)",
            "A seam with no position: the consumed answer",
            "Alerts that fire and clear (ADR-057)",
            "Exactly-once state, at-least-once delivery",
        ],
        "source": "Source: docs/design/adr/056-queries-on-queries.md; docs/design/adr/057-alerts.md; "
        "docs/project/RELEASE_NOTES.md '1.0.0'.",
    },
    {
        "kind": "code",
        "kicker": "Queries on queries · ADR-056",
        "title": "A query over a view follows its answer, not its changelog",
        "code": [
            "CREATE CONTINUOUS QUERY cleaned",
            "  KEYED BY (user_id)",
            "AS SELECT user_id, region, amount",
            "   FROM txn WHERE amount > 0;",
            "",
            "CREATE CONTINUOUS QUERY by_region",
            "  KEYED BY (region)",
            "AS SELECT region, SUM(amount) AS total,",
            "   COUNT(*) AS n",
            "   FROM cleaned GROUP BY region;",
            "",
            "CREATE CONTINUOUS QUERY big_regions",
            "  KEYED BY (region)",
            "AS SELECT region, total",
            "   FROM by_region WHERE total > 100;",
        ],
        "code_size": 12,
        "code_w": 0.52,
        "items": [
            ("Fed the answer's changes",
             "Its snapshot, then per commit the row that left the answer at −1 and the one that entered "
             "at +1 — so an upsert upstream is an update downstream, not a second row."),
            ("What can be maintained exactly",
             "Filters, projections and unwindowed COUNT, SUM and AVG, with GROUP BY; windows, joins, "
             "top-N, MIN/MAX and COUNT(DISTINCT) are refused (PRV-2075)."),
            ("Named refusals",
             "Dropping a view others read (PRV-8024, no cascade), a cycle (PRV-8025), replacing a chain "
             "member (PRV-8026), deeper than eight (PRV-8027)."),
        ],
        "size": 15,
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §3.1 (the three statements, verbatim, reflowed); "
        "docs/design/adr/056-queries-on-queries.md §1, §4, §5; docs/project/RELEASE_NOTES.md '1.0.0' (Queries on "
        "queries).",
    },
    {
        "kind": "flow",
        "kicker": "Exactly once across a chain",
        "title": "No position to meet at, so the seam is the answer consumed",
        "steps": [
            ("A view has no position",
             "Commits follow a timer; after a restart the upstream's commit n is a different set of "
             "changes. A sequence number cannot be resumed from."),
            ("Record the answer seen",
             "The downstream keeps an image of the upstream rows it was handed, and its checkpoint "
             "stores it, cut under the same freeze as its state."),
            ("Restore by difference",
             "Follow the upstream again, and feed its snapshot minus the image first — retractions and "
             "insertions — then every change."),
        ],
        "box_h": 2.05,
        "items": [
            ("Exact whichever side checkpointed later",
             "The difference is between two answers, not two positions. It works because every operator "
             "allowed over a view depends only on what its input adds up to."),
            ("A reader that falls 65,536 changes behind",
             "Drops its queue, re-snapshots and is fed the difference: conflation without loss."),
        ],
        "size": 15,
        "source": "Source: docs/design/adr/056-queries-on-queries.md §2 (the image, the freeze, restore by "
        "difference, the 65,536-change queue); QueryChainsTest, UpstreamReaderTest; "
        "docs/publications/research/continuous-queries-as-maintained-answers.tex §8 (Theorem: a chain is exact).",
    },
    {
        "kind": "code",
        "kicker": "Alerts · ADR-057",
        "title": "Told when a row enters the answer, and when it leaves",
        "code": [
            "CREATE ALERT low_stock_alert",
            "  ON low_stock",
            "  WHERE warehouse = 'LDN'",
            "  NOTIFY buyers",
            "  WITH (severity = 'warning',",
            "        fire_after = '1m',",
            "        clear_after = '5m',",
            "        dedupe = '10m',",
            "        resend_every = '1h')",
            "",
            "SNOOZE ALERT low_stock_alert FOR '2h'",
            "ACK    ALERT low_stock_alert",
        ],
        "code_w": 0.5,
        "items": [
            ("A clear is a retraction, not a guess",
             "A delivery arrives from mysql-cdc as −1 old, +1 new; the filter passes neither half, the "
             "row leaves low_stock, and the alert is told."),
            ("What is true, and what is said",
             "fire_after and clear_after decide the first; dedupe, pause, snooze and reminders only "
             "hold the second back — a flap folds into its end state."),
            ("Channels are plugins",
             "A webhook signed with HMAC-SHA256 (a Slack format too) and a log channel; email, Teams "
             "and PagerDuty are designed, not built."),
        ],
        "size": 15,
        "source": "Source: docs/design/adr/057-alerts.md §1–§3, §5 (statement shape and options from §2; "
        "channels built and designed); docs/project/RELEASE_NOTES.md '1.0.0' (Alerts).",
    },
    {
        "kind": "table",
        "kicker": "Guarantees, plainly",
        "title": "Exactly-once alert state, at-least-once delivery",
        "rows": [
            ["Situation", "What happens"],
            ["Any decision", "Fired, cleared, told, acknowledged — journalled and forced to disk before "
             "anything is sent"],
            ["Restart, key still in the answer", "Not fired again"],
            ["Restart, a clear decided but not delivered", "Delivered: it is owed, and the journal says so"],
            ["A channel refuses or times out", "Retried with backoff, then re-sent every minute until "
             "accepted; the failure is on the alert (PRV-8045)"],
            ["A receiver sees one twice", "Every attempt carries the same Idempotency-Key, across "
             "retries and restarts"],
            ["The view it follows is dropped", "Refused while the alert exists (PRV-8024)"],
        ],
        "col_w": [2.2, 3.8],
        "size": 14.5,
        "note": "Tested end to end: the retail study's low-stock alert on a real node with a signed "
        "webhook and a restart (RetailLowStockAlertEndToEndTest).",
        "source": "Source: docs/design/adr/057-alerts.md §4 (journal, restart, redelivery, idempotency key) and "
        "§6 (PRV-8024); docs/project/RELEASE_NOTES.md '1.0.0' (Alerts, RetailLowStockAlertEndToEndTest).",
    },
]

PART7: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "8",
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
        "docs/guides/CONNECTORS.md; README.md 'Sources', 'Sinks'.",
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
        "source": "Source: README.md 'Sources'; docs/guides/CONNECTORS.md §1 (kinds and shipped examples, "
        "lookup names with suffix); plugins/*/src/main/resources/META-INF/services/ (which plugin "
        "declares which kind); docs/design/adr/054 ('Which sources').",
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
        "source": "Source: README.md 'Sinks'; docs/guides/CONNECTORS.md §1 (StreamSinkPlugin row); "
        "docs/project/RELEASE_NOTES.md 0.2.0 (Avro/Protobuf, commit.mode: prepared) and '1.0.0' "
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
        "source": "Source: docs/design/adr/041-change-data-capture-without-debezium.md; README.md 'Sources' "
        "(postgres-cdc, mysql-cdc); docs/project/RELEASE_NOTES.md '1.0.0' (mysql-cdc, PRV-5152).",
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
        "source": "Source: docs/guides/CONNECTORS.md §1 (PRV-5090, CFG-4); docs/design/system_design.md §33 ADR-028 "
        "row; docs/guides/CONTINUOUS_QUERIES.md §2.1 (PRV-2042, deletes: detect); README.md 'Sources' and "
        "'Boundaries' (TLS; lz4).",
    },
]

PART8: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "9",
        "title": "Security, identity and governance",
        "sub": "Authorization is enforced by Pravaha on every read, at the layer that produces the "
        "row. Grants, row filters and masks live in the engine as a governed catalogue of live "
        "answers; the engine keeps its own users, keys and sessions; a tenant shares only with itself.",
        "points": [
            "Why not delegate to the store (ADR-031)",
            "Three seams and one rule",
            "The governed catalogue (ADR-059)",
            "Row filters and masks as policies",
            "The engine is the identity authority (ADR-052)",
            "Tenancy and ownership (ADR-050, ADR-060)",
            "What is not built",
        ],
        "source": "Source: docs/design/adr/031-authorization-at-the-pravaha-layer.md; docs/design/adr/052; "
        "docs/design/adr/050; docs/design/adr/059; docs/design/adr/060; docs/operations/SECURITY.md.",
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
        "source": "Source: docs/design/adr/031-authorization-at-the-pravaha-layer.md 'Why it cannot be "
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
                ("Catalogue filters reach subscriptions",
                 "A catalogue row filter is enforced per change; a programmatic policy's filter still "
                 "refuses subscribe (STRM-13) — read the view instead."),
            ],
            "size": 15,
        },
        "source": "Source: docs/design/adr/031 (three pieces; soundness rule); docs/operations/SECURITY.md 'What a "
        "registration is allowed to read / write', 'A conditional entitlement cannot subscribe' "
        "(STRM-13; catalogue filters enforced per change).",
    },
    {
        "kind": "table",
        "kicker": "The Pravaha Catalog · ADR-059",
        "title": "Grants live in the engine, and govern answers still being computed",
        "rows": [
            ["Privilege", "What it allows — and why a live answer needs it"],
            ["USE · SELECT", "Enter a namespace; read an object's rows"],
            ["SUBSCRIBE", "Follow a view's changes; re-asked every two seconds, so a REVOKE ends an open stream"],
            ["BUILD_ON", "Name an object as a registration's input: a registration is a standing read"],
            ["CREATE · WRITE", "Create in a namespace; write to a sink or notify a channel"],
            ["MODIFY · MANAGE · OWN", "Administer: drop, pause, replace; change grants; own it"],
        ],
        "col_w": [1.6, 4.4],
        "size": 14.5,
        "intro": "tenant.namespace.object names, each with an owner, description, tags and version. "
        "Grants are allow-only, to roles and users, inherited down namespaces; tenants are walls.",
        "note": "GRANT, REVOKE, SHOW EFFECTIVE ACCESS run wherever CREATE CONTINUOUS QUERY does; "
        "pravaha grant | revoke | access why; the console's Catalog and Admin → Grants.",
        "source": "Source: docs/project/RELEASE_NOTES.md '1.0.0' (The Pravaha Catalog, phase 1); "
        "docs/design/adr/059-the-pravaha-catalog-governs-live-answers.md 'Phase 1, as built' (mid-stream "
        "revocation every two seconds); README.md 'Governed catalogue'.",
    },
    {
        "kind": "code",
        "kicker": "Policies · ADR-059 phase 2",
        "title": "Row filters and masks are policies, applied where rows leave an object",
        "code": [
            "CREATE ROW FILTER sales.region_scope",
            "  AS region = session_attribute('region')",
            "  EXCEPT ROLE finance_admin;",
            "",
            "CREATE MASK sales.card_last4 ON COLUMN card",
            "  AS 'XXXX-' || RIGHT(card, 4)",
            "  EXCEPT ROLE payments_ops;",
            "",
            "ALTER STREAM orders",
            "  SET POLICY sales.region_scope;",
            "ALTER TAG 'pii'",
            "  SET POLICY sales.card_last4;",
        ],
        "code_w": 0.5,
        "items": [
            ("One place covers every path",
             "Flight and point reads, pgwire text and binary, subscriptions, registrations built on "
             "the view, and alerts evaluated as their owner."),
            ("A masked column is never an operand",
             "As a filter, group, join or sort key, aggregate argument or view key it would leak the "
             "true value — refused, PRV-7006."),
            ("In the fingerprint",
             "Registrants narrowed differently get different computations; a query over a masked view "
             "carries the mask into its own answer."),
        ],
        "size": 15,
        "source": "Source: docs/operations/SECURITY.md 'Row filters and masks as catalogue objects (ADR-059 §4)' "
        "(the statements, verbatim, reflowed); docs/project/RELEASE_NOTES.md '1.0.0' (phase 2: where it is "
        "enforced; PRV-7006; the fingerprint); docs/design/adr/059 'Phase 2, as built'.",
    },
    {
        "kind": "split",
        "kicker": "Vacuity · TAUTOFILTER-1, VACUITYGAP-1",
        "title": "A filter that restricts nothing is refused, not enforced",
        "left": {
            "head": "Refused",
            "rows": [
                ["Filter", "Why"],
                ["region = region", "true for every row"],
                ["1 = 1 OR region = 'x'", "folds to TRUE"],
                ["a < 5 OR a > 2", "covers every integer"],
                ["x IS NULL OR x IS NOT NULL", "true for every row"],
                ["a >= a", "drops only NULLs, unsaid"],
            ],
            "col_w": [2.1, 1.7],
            "size": 13.5,
        },
        "right": {
            "head": "Sound, not complete",
            "items": [
                ("Never refuses a real restriction",
                 "Constants cut a column's values into regions; each comparison is decided region by "
                 "region. A property test checks every verdict against every region."),
                ("Decided where it can be",
                 "A session-free policy at binding (PRV-7038); one reading the session per reader, at "
                 "each read and registration (PRV-7003)."),
                ("Too large to decide",
                 "Assumed to restrict."),
            ],
            "size": 14.5,
        },
        "source": "Source: docs/project/RELEASE_NOTES.md '1.0.0' (TAUTOFILTER-1: the listed filters, PRV-7003, "
        "PRV-7038); commit af5ee635 (VACUITYGAP-1: a < 5 OR a > 2, regions); docs/design/adr/059 'Vacuity'; "
        "FilterVacuityTest.",
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
            ["Login failures", "Recorded before the refusal is answered; every refusal reads alike",
             "5 from one address in 15 minutes bar that address for 30; 50 from any lock the account"],
        ],
        "col_w": [1.0, 2.1, 2.9],
        "size": 14.5,
        "note": "An append-only, fsync'd journal holds it — no database to run. The console signs "
        "each person in against the engine and acts as them. MFA and SSO were dropped by the owner.",
        "source": "Source: docs/design/adr/052-the-engine-is-the-identity-authority.md (secrets table, policy, "
        "lockout, API keys, sessions, store; status line: stages 4 and 5 dropped 2026-09-27); README.md "
        "'Security'; docs/project/RELEASE_NOTES.md 'Unreleased' (LOCKENUM-1: identical 401 PRV-7010, five "
        "per address, fifty per account).",
    },
    {
        "kind": "table",
        "kicker": "Found by the adversarial round · fixed in 2.0.1",
        "title": "A credential ends when it is revoked; a stranger's bytes are bounded",
        "rows": [
            ["Was", "Now"],
            ["A revoked key, a signed-out session or a disabled user kept reading on an open PostgreSQL "
             "connection (PGREVOKE-1)",
             "The credential is verified again before every statement; FATAL 28000, PRV-6218. Flight "
             "re-checks every 2 s, and now the principal too"],
            ["Before sign-in, the gateway allocated a declared 16 MiB message; no connection cap "
             "(PGPREAUTH-1)",
             "16 KiB before sign-in, refused on its declared length; one 10 s handshake deadline; "
             "100 connections, 32 unauthenticated (53300, PRV-6216)"],
            ["An HTTP body was read whole before authentication, up to 20 M characters (HTTPBODY-1)",
             "Refused on its declared length: 16 KB open paths, 4 MB otherwise (413, PRV-1054); "
             "8 sign-ins at once (429, PRV-1055)"],
            ["The console cookie carried a usable engine token (COOKIETOKEN-1)",
             "An opaque id; the secrets stay in the console process"],
        ],
        "col_w": [2.9, 3.3],
        "size": 13.5,
        "note": "TLS was not exercised by the round. The console now sends a CSP, X-Frame-Options and "
        "nosniff on every response (CONSOLEHDR-1).",
        "source": "Source: docs/project/RELEASE_NOTES.md 'Unreleased' (PGREVOKE-1, PGPREAUTH-1, HTTPBODY-1, "
        "COOKIETOKEN-1, FLIGHTPRINCIPAL-1, CONSOLEHDR-1); docs/project/qa/FINDINGS.md 'Found by the adversarial "
        "QA of 2.0.0, surfaces' (status lines); docs/project/qa/logs/ADV-SURFACE.md.",
    },
    {
        "kind": "split",
        "kicker": "Tenancy · ADR-050, ADR-060",
        "title": "A tenant owns names and state, and shares only with itself",
        "left": {
            "head": "A tenant owns",
            "items": [
                ("Its names, and the computations behind them",
                 "A view or alert name is unique within its tenant; another tenant's is, to the caller, a "
                 "name nothing holds. The same SQL from two tenants is two computations."),
                ("The keys those views hold",
                 "What max-state-keys counts; max-queries counts names."),
                ("Refused by name at registration",
                 "PRV-8020 or PRV-8021, before a sink opens; audited as DENY; a running query is "
                 "never stopped by a quota. Only its own tenant may replace a name (PRV-8022)."),
            ],
            "size": 15.5,
        },
        "right": {
            "head": "Who may administer a view",
            "items": [
                ("Its owner, a grantee, or an admin",
                 "Drop, pause, replace, debug: the registrant, MODIFY or MANAGE, or the admin role. "
                 "Reading a view no longer lets you destroy it (PRV-7002)."),
                ("A tenant does not scope lanes or CPU",
                 "Shared by decision; per-tenant CPU scheduling is not built."),
                ("Upgrading from 0.2 is one-way",
                 "Once a view outside the default tenant is recovered under per-tenant names."),
            ],
            "size": 15,
        },
        "source": "Source: docs/design/adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md "
        "§1–§2; docs/design/adr/060-view-names-are-unique-per-tenant.md; docs/operations/SECURITY.md 'Tenants'; "
        "docs/project/RELEASE_NOTES.md '1.0.0' (upgrade notes; LIFE-040, SX-6 ownership; one-way upgrade).",
    },
    {
        "kind": "bullets",
        "kicker": "Stated plainly",
        "title": "What the security model does not do yet",
        "items": [
            ("The HTTP API's TLS is Spring Boot's",
             "Flight carries TLS by default; /api/v1 is reached over HTTPS only through server.ssl.*."),
            ("No mTLS between nodes, no OIDC or JWT verifier out of the box",
             "There are no nodes yet; TokenVerifier is the seam for a verifier."),
            ("The catalogue's phases 3 and 4",
             "Column tags, lineage and labels that follow it, contracts, sharing and access history "
             "are not built."),
            ("MFA and single sign-on",
             "Dropped by the owner: users, passwords, API keys and sessions are what Pravaha keeps."),
        ],
        "source": "Source: docs/operations/SECURITY.md 'Transport', 'What is not built'; docs/design/adr/059 status line "
        "(phases 3 and 4 not built); docs/design/adr/052 status line.",
    },
]

PART9: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "10",
        "title": "Operating it",
        "sub": "Three ways to run one engine, one directory it lives in with or without Docker, a "
        "console that is a product and not a dashboard, two command lines, SDKs that ship on their "
        "own and survive a restart, BI tools over the PostgreSQL protocol, and metrics that say what "
        "is not measured rather than reporting zero.",
        "points": [
            "Three ways to run it; one /opt/pravaha",
            "The console",
            "pravaha and pravaha-engine",
            "SDKs, on their own, and reconnecting",
            "BI tools over the PostgreSQL protocol",
            "What to watch",
            "The assistant (experimental)",
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
             "The engine, Flight SQL, the PostgreSQL gateway and /status. A non-root image on a Java 21 "
             "glibc base, and a Helm chart that installs one node as a StatefulSet."),
        ],
        "note": "The engine core contains no Spring, enforced by the build (ADR-019). The console is "
        "a separate process on the published SDK, so it cannot reach past the public API (ADR-024).",
        "source": "Source: README.md 'Embedding' and 'How it is built' (Deployment; ADR-019, ADR-024, "
        "ADR-045, ADR-047, ADR-053).",
    },
    {
        "kind": "code",
        "kicker": "In Docker and out of it · RUNNING_IN_DOCKER.md",
        "title": "One directory, /opt/pravaha, owned by whoever runs it",
        "code": [
            "deploy/docker/build.sh \\",
            "  --tag pravaha/pravaha-server:local",
            "deploy/docker/console/build.sh \\",
            "  --tag pravaha/pravaha-console:local",
            "tools/docker-env.sh   # .env + pravaha-home/",
            "docker compose \\",
            "  -f deploy/docker/compose/docker-compose.yml \\",
            "  --profile seed up -d",
            "",
            "# console  http://localhost:17070",
            "# Flight   grpc://localhost:19090",
            "# REST     http://localhost:18080",
            "# psql     localhost:15432",
        ],
        "code_w": 0.55,
        "code_size": 12,
        "items": [
            ("PRAVAHA_HOME",
             "conf/, secrets/, plugins/, data/, logs/, tmp/ — the same layout in a container and in an "
             "unpacked distribution; relative paths mean the same in both."),
            ("As you, read-only root",
             "Engine and console run as the invoking uid:gid; nothing is written outside the home."),
            ("Profiles",
             "seed, cdc (PostgreSQL, MySQL), stores (Aerospike, Cassandra), observability "
             "(Prometheus, Grafana), tools."),
        ],
        "size": 14,
        "source": "Source: docs/operations/RUNNING_IN_DOCKER.md 'The short version' (commands, ports) and 'One root: "
        "PRAVAHA_HOME' (layout); commit 36d766f8 (compose stack: profiles, uid:gid, read-only roots); "
        "commit c2af8cfb (PRAVAHA_HOME, PravahaHomeLayoutTest).",
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
             "Users, keys and sessions; grants and policies; AI models; the audit trail; Lanes."),
            ("Everyone: Catalog and Alerts",
             "Objects with owners, tags and grants; alerts firing, snoozed and acknowledged."),
        ],
        "note": "FastAPI, server-rendered, every asset vendored so it runs air-gapped; four themes, "
        "crimson by default. 1,937 console tests pass with the browser suites, zero axe violations in "
        "every theme; the manual WCAG 2.2 AA audit is not done.",
        "source": "Source: README.md 'The console'; docs/project/RELEASE_NOTES.md '1.0.0' (Admin → Lanes, Admin · "
        "AI models, Catalog and Admin → Grants/Policies, Alerts screens, MAYA design and four themes); "
        "docs/development/TESTING.md tiers table (console 1,937 passed); commit ff9fae5d.",
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
        "source": "Source: docs/project/RELEASE_NOTES.md '1.0.0' (the Java CLI is now pravaha-engine; "
        "commands moved to the Python CLI); docs/guides/QUICKSTART.md (pip install); docs/design/ARCHITECTURE.md "
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
        "source": "Source: docs/guides/USER_GUIDE.md 'Surviving a restart: reconnect' (both snippets, "
        "reflowed); docs/project/RELEASE_NOTES.md '1.0.0' (JavaSdkReconnectTest, test_reconnect.py).",
    },
    {
        "kind": "cards",
        "kicker": "SDKs · SDKSTANDALONE-1",
        "title": "The client SDKs build, ship and run without the server",
        "cols": 3,
        "cards": [
            ("JAVA", "pravaha-sdk-java-flight",
             "The thin jar through Maven or Gradle — the default — or a 19 MB -all jar with every "
             "runtime dependency, for a client with no build tool. Sources and javadoc jars attached."),
            ("PYTHON", "the pravaha wheel",
             "Imports with the standard library alone; pip install \"pravaha[flight]\" adds Flight. "
             "The pravaha CLI and the assistant ship in it."),
            ("PROVED OUTSIDE", "sdk-standalone-check.sh",
             "Four clients outside the repository against a throwaway node: Maven, the -all jar with "
             "plain java, the wheel with and without [flight]."),
        ],
        "note": "SdkIndependenceTest fails the build if an SDK reaches a server module or the server's "
        "jar carries SDK classes. The standalone check found SDKNETTYMIX-1: a Maven client resolved two "
        "Netty lines and failed on its first call — invisible to tests inside the build.",
        "source": "Source: docs/project/RELEASE_NOTES.md '1.0.0' (SDKSTANDALONE-1, SDKNETTYMIX-1); "
        "docs/development/TESTING.md 'The SDKs on their own'; commit bc971c6b (sources and javadoc jars).",
    },
    {
        "kind": "split",
        "kicker": "BI tools · the PostgreSQL gateway",
        "title": "Power BI, psql and psycopg read views as if from PostgreSQL",
        "left": {
            "head": "What was needed",
            "items": [
                ("Npgsql 4.0.17, Power BI's own driver",
                 "Its type-loading queries, binary results for every type, the navigator's "
                 "INFORMATION_SCHEMA queries, DirectQuery's LIMIT 1000001."),
                ("Transactions, as no-ops",
                 "BEGIN … COMMIT with PostgreSQL's tags and status: psycopg's default mode, pgjdbc "
                 "without auto-commit, most ORMs (PGWIRE-TX-1)."),
                ("psql 18",
                 "\\d answered."),
            ],
            "size": 14.5,
        },
        "right": {
            "head": "What is stated, not implied",
            "items": [
                ("READ COMMITTED, always",
                 "Each read sees the view as its last commit left it; REPEATABLE READ is accepted with "
                 "a NOTICE saying so. Writes are refused."),
                ("AVG of an integer is numeric here only",
                 "As PostgreSQL clients expect; every other surface keeps the engine's integer AVG."),
                ("Same grants, filters and masks",
                 "Signed in with an API key or session token."),
            ],
            "size": 14.5,
        },
        "note": "Tested with the real Npgsql 4.0.17, pgjdbc, psycopg 3 and psql. Power BI Desktop itself "
        "was not run.",
        "source": "Source: docs/project/RELEASE_NOTES.md '1.0.0' (Power BI through the gateway; PGWIRE-TX-1; "
        "AVGINT-1; PGWIREPASS-1); commit 2a77768a (psql 18's \\d); README.md 'BI tools'.",
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
        "note": "Shipped beside them: four Grafana dashboards, Prometheus rules (and a Helm "
        "PrometheusRule), JSON logs with correlation and trace ids, OpenTelemetry tracing (off by "
        "default). Per-operator metrics cost about 10–13 % of throughput (JDK 25), so they are off by default.",
        "source": "Source: docs/operations/OPERATIONS.md 'Watching a running node' (per-query and per-node tables; "
        "FEED-1; ADR-037 B1); docs/project/RELEASE_NOTES.md '1.0.0' (Observability, built out; PERF-1: about "
        "12 %, re-taken without the coverage agent); OPERATIONS.md 'What it costs' (10–13 % on JDK 25, 2026-10-04).",
    },
    {
        "kind": "flow",
        "kicker": "The assistant · ADR-058 · experimental in 1.0",
        "title": "Plain English to continuous SQL, with the engine as the judge",
        "steps": [
            ("Context, as you",
             "Streams and views you may read, sinks you may write to, the dialect's rules, a few "
             "worked examples. No row is ever sent."),
            ("Any model drafts",
             "Hosted or local — built-in providers, any OpenAI-compatible server, Ollama, or a "
             "plugin — with fallback chains and budgets; switched at runtime."),
            ("The engine judges",
             "Validates and explains the draft; up to three repair turns, none allowed to change what "
             "the query reads."),
            ("A person registers",
             "Only an accepted draft, only after confirmation; a running query with the same plan is "
             "offered for reuse."),
        ],
        "box_h": 2.15,
        "items": [
            ("Where", "pravaha ask, explain-sql and why; the console's Describe it and Explain; Admin · "
             "AI models."),
            ("Measured how",
             "pravaha assist eval scores a model on a golden set generated from the case studies: 27 "
             "cases that must be accepted, 3 that must be refused or asked about. No score is claimed "
             "here."),
        ],
        "size": 14.5,
        "source": "Source: docs/design/adr/058-plain-english-to-continuous-sql.md (status; §1, §2, §3); "
        "docs/project/RELEASE_NOTES.md '1.0.0' (the assistant, phases 1–3; 27 + 3 golden cases); "
        "docs/operations/COMPATIBILITY.md 'Experimental in 1.0'; docs/guides/ASSIST.md.",
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
        "docs/design/adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md.",
    },
]

SLIDES = PART6 + CHAINS + PART7 + PART8 + PART9
