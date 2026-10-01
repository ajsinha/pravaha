"""
The deck, as data. Parts 1 to 3: why ask once and answer always, the vocabulary
from nothing, and a query's life from CREATE to a subscriber.

One deck split across several modules only to keep each file short; read them in
order (see GUIDE.md). Each slide's ``source`` becomes its speaker notes and names
where every figure and claim on it comes from.

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

PART1: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "1",
        "title": "Why ask once, answer always",
        "sub": "A batch answer is as old as its last run, and a poll pays for its freshness by "
        "re-reading what it has already read. Pravaha inverts the arrangement: the question is "
        "registered once, and the engine keeps its answer current as the data changes.",
        "points": [
            "What a batch answer costs, in the case studies' own words",
            "What polling re-reads, and what it misses",
            "The inversion: a computation, not a request",
            "One query, end to end",
            "Pravaha in one slide",
        ],
        "source": "Source: examples/case-studies/*/README.md (problem statements); docs/guides/CONCEPTS.md §1; "
        "README.md 'What it is'.",
    },
    {
        "kind": "table",
        "kicker": "The problem with batch",
        "title": "A batch answer is as old as the last run",
        "intro": "Five of the thirteen case studies open with the same complaint. "
        "Each quotation is from the study's own README.",
        "rows": [
            ["Study", "What the batch answer costs"],
            ["Card authorisation velocity", "“The fraud is detected four minutes after the fourth "
             "authorisation cleared.”"],
            ["Intraday counterparty exposure", "“The number on the risk screen is as old as the last run.”"],
            ["Checkout funnel", "The payment integration starts failing at 10:06; “the dashboard — "
             "refreshed from the warehouse every hour — shows nothing until eleven.”"],
            ["Call-detail-record fraud", "SIM-box bypass and revenue-share fraud: “a nightly batch over the "
             "day's CDRs finds both, twelve hours and several thousand pounds late.”"],
            ["Order revenue into Iceberg", "“By mid-morning the analysts are looking at yesterday.”"],
        ],
        "col_w": [1.0, 2.6],
        "note": "Nobody in these studies is short of data. They are short of an answer that is "
        "current when somebody needs to act on it.",
        "source": "Source: examples/case-studies/banking-card-velocity/README.md, "
        "finance-counterparty-exposure/README.md, ecommerce-checkout-funnel/README.md, "
        "telecom-cdr-fraud/README.md, lakehouse-orders-iceberg/README.md (problem statements, quoted).",
    },
    {
        "kind": "split",
        "kicker": "The problem with polling",
        "title": "Polling buys freshness by reading everything again",
        "intro": "The usual fix for a stale answer is to ask more often. Two of the studies say "
        "what that costs the database and what it still cannot see.",
        "left": {
            "head": "What a poll re-reads",
            "items": [
                ("Every row, every run, for ever",
                 "“The interesting part is not that batch is slow — it is that the job re-reads rows "
                 "it has already read, every run, forever.” (counterparty exposure)"),
                ("A scan on the database that serves the tills",
                 "Polling WHERE on_hand <= reorder_point “puts a scan on the database that serves the "
                 "tills.” (stock levels from MySQL)"),
            ],
        },
        "right": {
            "head": "What a poll cannot see",
            "items": [
                ("A change between two polls",
                 "The same poll “misses a line that dips and recovers between polls.”"),
                ("A delete",
                 "A poll with a watermark carries “the honest admission that deletes are invisible” "
                 "(design §19)."),
                ("Something that did not happen",
                 "“‘No scan yet’ is not an event anything can react to.” (delivery SLA breaches)"),
            ],
        },
        "source": "Source: examples/case-studies/finance-counterparty-exposure/README.md; "
        "retail-inventory-mysql/README.md; logistics-delivery-sla/README.md; docs/design/system_design.md "
        "§19 (connector shapes table, 'Poll with a watermark').",
    },
    {
        "kind": "bullets",
        "kicker": "The inversion",
        "title": "A continuous query is a computation, not a request",
        "intro": "A database answers a question when asked. Pravaha is told the question in advance "
        "and maintains the answer, so asking is a hash probe rather than a scan.",
        "items": [
            ("Register the SQL once",
             "It keeps running, and keeps a view current, until somebody drops it."),
            ("Registering is expensive; querying is cheap",
             "A registration commits the node to memory and a share of a lane for as long as it "
             "exists, which is why registering is authorised separately from reading."),
            ("What one costs, measured",
             "About 1 MiB off-heap idle, 1.3 MiB once rows move, ~65 KiB of heap, ~16 ms to register "
             "(mostly planning), and no platform thread of its own."),
            ("Two ways to take the answer",
             "Read the view by key, or subscribe and receive every commit as weighted changes."),
        ],
        "source": "Source: docs/guides/CONCEPTS.md §1 'A continuous query is a computation, not a request' "
        "(costs measured by NodeScaleTest, per docs/operations/OPERATIONS.md 'Sizing a node for many queries').",
    },
    {
        "kind": "code",
        "kicker": "One query, end to end",
        "title": "A windowed, enriched answer, registered once",
        "code": [
            "SELECT STREAM",
            "    TUMBLE_END(event_time, INTERVAL '10' SECOND)",
            "                  AS window_end,",
            "    t.user_id, p.tier,",
            "    COUNT(*)      AS txn_count,",
            "    SUM(t.amount) AS total_volume",
            "FROM  txn_stream AS t",
            "LEFT JOIN user_profile",
            "      FOR SYSTEM_TIME AS OF t.event_time AS p",
            "       ON t.user_id = p.user_id",
            "WHERE t.status = 'COMPLETED'",
            "GROUP BY TUMBLE(t.event_time, INTERVAL '10' SECOND),",
            "         t.user_id, p.tier",
        ],
        "code_w": 0.55,
        "items": [
            ("Four things in one statement",
             "A tumbling window, a temporal lookup join, a filter evaluated inside Aerospike rather "
             "than after the read, and two aggregates."),
            ("Run against a real Aerospike",
             "By AerospikeContinuousQueryIT, over a binding with deletes: detect — without it a scan "
             "re-reads an updated record as another row, and the aggregate is refused (PRV-2042)."),
            ("Every query is continuous",
             "SELECT STREAM is accepted and redundant. Registered as CREATE CONTINUOUS QUERY "
             "txn_volume KEYED BY (window_end, user_id) AS SELECT …"),
        ],
        "size": 16,
        "source": "Source: README.md 'What it is' (the query and its description, verbatim; "
        "AerospikeContinuousQueryIT needs Docker).",
    },
    {
        "kind": "stats",
        "kicker": "Pravaha in one slide",
        "title": "Continuous SQL where your data already lives",
        "stats": [
            ("Java 21", "One language. Calcite plans; Pravaha's own operators execute"),
            ("10 plugins", "Source and sink connectors under plugins/, each its own jar"),
            ("1 node", "Clustering is not built; a node refuses PARTITIONED mode (PRV-9002)"),
            ("13 studies", "Worked systems, each run and checked by the build"),
        ],
        "items": [
            ("What it is",
             "An embeddable, store-native, incrementally-maintained SQL engine. It runs continuous "
             "SQL over Aerospike, Cassandra, PostgreSQL or any JDBC database, MySQL, Kafka, files and "
             "Delta tables, keeps each answer current, and serves it back by key — so the result needs "
             "no second database to live in."),
            ("How it is reached",
             "Arrow Flight SQL and the PostgreSQL wire protocol for rows; REST under /api/v1 for "
             "everything that manages the engine; Java and Python SDKs; a console; or in process."),
        ],
        "size": 16,
        "source": "Source: README.md header, 'What it is', 'How it is built', 'Building' (plugin "
        "list; plugins/ holds ten connector plugins plus pravaha-cluster-zookeeper), 'What is not built' "
        "(PRV-9002); examples/case-studies/README.md (thirteen studies; CaseStudyRunTest).",
    },
]

PART2: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "2",
        "title": "The vocabulary, from nothing",
        "sub": "A handful of words carry the rest of this deck. Each is defined here before it is used, "
        "and each definition is the one the engine's own documentation gives.",
        "points": [
            "Stream, continuous query, view",
            "Source, binding, lookup",
            "Event time and the watermark",
            "A weight on every row, and the commit",
            "Lane, checkpoint, sink",
        ],
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §1–§2; docs/guides/CONCEPTS.md; docs/design/EXECUTION_MODEL.md §1.",
    },
    {
        "kind": "flow",
        "kicker": "Three nouns",
        "title": "A stream becomes a query becomes a view",
        "steps": [
            ("Source", "A plugin that produces rows: a file, a table, a topic, a change log"),
            ("Stream", "A named, typed, unbounded sequence of rows, bound to a source"),
            ("Continuous query", "A registered computation over one or more streams"),
            ("View", "The query's answer, maintained incrementally, readable by SQL"),
            ("Read or subscribe", "SELECT by key, or a batch of weighted changes per commit"),
        ],
        "box_h": 1.95,
        "rows": [
            ["Noun", "Lives for", "Note"],
            ["Stream", "The node's lifetime", "Declared with a schema and the column that carries its "
             "event time"],
            ["Continuous query", "Until dropped", "You do not ask it for an answer; you register it once "
             "and it keeps one"],
            ["View", "As long as its query", "Reading it is cheap, repeatable, and not the query "
             "running again"],
        ],
        "col_w": [1.0, 1.1, 3.0],
        "size": 15,
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §1 'The three nouns' (table and the source → "
        "stream → continuous query → view → SELECT line).",
    },
    {
        "kind": "code",
        "kicker": "Where rows come from",
        "title": "A stream is declared; a source is bound to it",
        "code": [
            "pravaha:",
            "  streams:",
            "    txn:",
            "      schema: \"txn_id:INT64,user_id:STRING,",
            "               amount:INT64,event_time:TIMESTAMP\"",
            "      event-time: event_time",
            "      out-of-orderness: 10s",
            "  sources:",
            "    txn:",
            "      plugin: filesystem",
            "      options:",
            "        path: /opt/pravaha/data/incoming/txn.csv",
            "        event.time: event_time",
        ],
        "code_w": 0.52,
        "items": [
            ("Stream: what it is",
             "pravaha.streams.<name> — schema, event time, lateness. Enough to plan and validate a "
             "query with nothing attached."),
            ("Source binding: where rows come from",
             "pravaha.sources.<name> — a plugin and its options, keyed by the stream it feeds. A "
             "binding is per stream name, not per query: one reader per binding can feed every query."),
            ("Lookup: a table a query may ask",
             "pravaha.lookups.<name> — the right side of a temporal join. A source is consumed and "
             "advances event time; a lookup is only asked."),
        ],
        "size": 16,
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §2 'Declaring a stream' (the three blocks and "
        "the YAML, schema line wrapped for the slide); docs/guides/CONNECTORS.md §3 ('a binding is per "
        "stream name, not per query'); README.md 'Sources' (one reader per source binding).",
    },
    {
        "kind": "bullets",
        "kicker": "Event time and the watermark",
        "title": "A window closes because data said so, not because time passed",
        "items": [
            ("Event time",
             "Every window, watermark and retention is measured in the timestamp in the data. Load "
             "the same rows in any order, at any speed, tomorrow, and the answer is the same."),
            ("Watermark: “nothing earlier is coming”",
             "At a watermark T no row earlier than T will arrive, so everything ending at or before T "
             "can be published. It closes windows and releases join state."),
            ("How late is late belongs to the stream",
             "Out-of-orderness is declared per stream; a stream that says nothing gets 10 seconds. "
             "Each source partition contributes a watermark, the query takes the minimum, and a "
             "partition that has gone quiet is excluded rather than holding everyone back."),
            ("No event time, no window",
             "A windowed query over a stream that declares no event-time column is refused at "
             "registration (PRV-2002) — it would report RUNNING, ingest everything and emit nothing."),
        ],
        "source": "Source: docs/guides/CONCEPTS.md §2 'Event time, not clock time' and §3 'Watermarks' "
        "(10-second default, minimum across partitions, idle exclusion, PRV-2002 / TIME-6).",
    },
    {
        "kind": "code",
        "kicker": "A weight on every row",
        "title": "A change is a row with a weight; a correction is −1 then +1",
        "code": [
            "-- snapshot",
            "+1 {window_end 08:03, press-02, readings 6, max 980}",
            "-- commit",
            "-1 {window_end 08:03, press-02, readings 6, max 980}",
            "+1 {window_end 08:03, press-02, readings 7, max 991}",
        ],
        "code_w": 0.5,
        "items": [
            ("Weight: the Z-set row",
             "+1 is a row appearing, −1 one being withdrawn. A row is present exactly while its "
             "weights sum positive; a change of weight zero is never delivered."),
            ("Commit",
             "The point at which the engine says a prefix of the input is fully processed. Changes "
             "arrive per commit, never per row, so nobody acts on a total still being assembled."),
            ("What a consumer does",
             "Wants current values: ignore −1 and overwrite by key. Keeps its own aggregate: apply "
             "the weights, or it drifts from the view the first time a window is corrected."),
        ],
        "size": 16,
        "note": "The panel is a real run: a late reading from press-02 corrected the 08:03 minute "
        "(machine sensor anomalies, step 5), trimmed to the columns that changed.",
        "source": "Source: docs/guides/CONCEPTS.md §4 'Changes carry weights' (STRM-1); docs/guides/USER_GUIDE.md "
        "'Subscribing to a registered query' (per commit, never per row); "
        "examples/case-studies/manufacturing-sensor-anomalies/README.md step 5 (the snapshot and "
        "commit lines, columns trimmed; temperatures are deci-degrees).",
    },
    {
        "kind": "cards",
        "kicker": "Where it runs, and what survives",
        "title": "Lane, checkpoint, sink",
        "cols": 3,
        "cards": [
            ("LANE", "One thread at a time, one inbox, one arena",
             "The unit of execution: a driver and the memory only it may touch — an off-heap inbox "
             "ring, off-heap arena slabs and a compiled operator pipeline. One thread per core steps "
             "many lanes; no locks in the steady state."),
            ("CHECKPOINT", "Operator state, source offsets and the view, at one cut",
             "Taken with every input held between rows, so the state and the offsets name the same "
             "point (ADR-008). A restart resumes from it rather than replaying from scratch or "
             "starting empty."),
            ("SINK", "Where every commit of a view is also written",
             "Named at registration and sent retractions too. Its delivery is stated up front: "
             "exactly once to a transactional sink, effectively once to an idempotent upsert, at "
             "least once to a plain append."),
        ],
        "source": "Source: docs/design/EXECUTION_MODEL.md §1–§2 ('One thread, one inbox, one arena, one "
        "processor, one loop'); docs/design/adr/008-aligned-checkpoints.md; README.md 'Recovery', 'Sinks', "
        "'How it is built' (Correctness).",
    },
]

PART3: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "3",
        "title": "A query's life",
        "sub": "From CREATE CONTINUOUS QUERY to a planned, fingerprinted computation on a lane, to a "
        "view a reader asks by key and a subscriber follows commit by commit.",
        "points": [
            "Registering: the statement and its key",
            "What happens at registration",
            "Refused at registration, not discovered later",
            "Reading the answer",
            "Subscribing to the changes",
        ],
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §3–§4; docs/design/ARCHITECTURE.md 'One path, end to end'.",
    },
    {
        "kind": "code",
        "kicker": "Register",
        "title": "CREATE CONTINUOUS QUERY: a name, a key, a retention",
        "code": [
            "CREATE CONTINUOUS QUERY hourly_spend",
            "    KEYED BY (user_id, window_end)",
            "    RETAIN FOR P7D",
            "AS",
            "SELECT user_id,",
            "       window_end,",
            "       SUM(amount) AS spend",
            "FROM TABLE(TUMBLE(TABLE txn,",
            "     DESCRIPTOR(event_time), INTERVAL '1' HOUR))",
            "GROUP BY user_id, window_start, window_end;",
        ],
        "code_w": 0.53,
        "items": [
            ("KEYED BY decides what a row replaces",
             "A second row with the same key supersedes the first. Given by column name, resolved by "
             "planning the SELECT; part of the query's identity."),
            ("RETAIN FOR: event time the view keeps",
             "Left out, the view keeps forever. Also part of the fingerprint, and journalled."),
            ("From anywhere SQL arrives",
             "Any Flight SQL client, the CLI, an SDK, the console, the embedded engine. SHOW, PAUSE, "
             "RESUME and DROP CONTINUOUS QUERY manage what runs."),
        ],
        "size": 16,
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §3 'Registering a continuous query' (the "
        "statement verbatim, the TUMBLE line wrapped for the slide; --keys, --retain, TY-21).",
    },
    {
        "kind": "flow",
        "kicker": "What happens at registration",
        "title": "Plan once, fingerprint, share or place, then serve",
        "steps": [
            ("Parse, validate", "Against the declared streams, by Calcite"),
            ("Check sources and sink", "Does a source repeat rows? Can the sink take this changelog?"),
            ("Plan and fingerprint", "Plan, row filters, key columns, retention — and the tenant"),
            ("Share or place", "Same fingerprint running: join it. Otherwise a lane and a feed"),
            ("Register the view", "In the catalogue; readable at once"),
        ],
        "box_h": 1.9,
        "items": [
            ("Why a thousand dashboards cost one computation",
             "Identical fingerprints share one computation under many names. The match is on the "
             "normalised plan, so whitespace and aliases do not matter — operand order does."),
            ("Released only by its last name",
             "Neither registrant knows the other exists, so dropping eagerly would be an outage "
             "caused by somebody tidying up their own query."),
        ],
        "size": 16,
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §3 'What happens at registration' (six steps, "
        "condensed to five); docs/guides/CONCEPTS.md §5 'Sharing is by fingerprint'; "
        "docs/design/adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md (tenant in the "
        "fingerprint).",
    },
    {
        "kind": "table",
        "kicker": "Refused by name",
        "title": "What cannot work is refused at registration, before anything opens",
        "rows": [
            ["Code", "Refuses", "Because"],
            ["PRV-2002", "A windowed query over a stream with no event-time column",
             "No watermark would advance, so no window could ever close"],
            ["PRV-2050", "GROUP BY a key with no window", "One accumulator per key for ever: unbounded state"],
            ["PRV-2042", "An aggregate or join over a source that repeats rows",
             "It would count a re-read record twice; the fix is deletes: detect"],
            ["PRV-2041", "A revising query into an append-only sink", "The sink cannot take a retraction"],
            ["PRV-8010", "A sink whose columns or key differ from the query's", "Checked against the sink's declared schema() and keyColumns()"],
            ["PRV-2020", "Session windows", "Not built; tumbling and hopping windows are"],
            ["PRV-8020 · 8021", "A registration over its tenant's query or state quota",
             "Admission is decided before a sink opens or a feed starts"],
        ],
        "col_w": [0.9, 2.2, 2.2],
        "size": 15,
        "note": "A refusal names the setting to change. A query that could never answer is not "
        "allowed to report RUNNING with an empty view.",
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §2 (PRV-2002, TIME-6), §3 steps 2 (PRV-2042, "
        "PRV-2041); docs/guides/CONCEPTS.md §7 (PRV-2050); README.md 'Windows and event time' (PRV-2020), "
        "'Sinks' (PRV-8010); docs/guides/CONNECTORS.md §1 (a sink reports schema() and keyColumns()); "
        "docs/design/adr/050 §2 (PRV-8020, PRV-8021).",
    },
    {
        "kind": "code",
        "kicker": "Read",
        "title": "Reading the answer is ordinary SQL over the view",
        "code": [
            "// Java SDK",
            "try (QueryResult result = client.query(",
            "    \"SELECT total_volume FROM user_volume\"",
            "  + \" WHERE user_id = ?\", \"u42\")) {",
            "  for (Row row : result) {",
            "    long volume = row.getLong(\"total_volume\");",
            "  }",
            "}",
            "",
            "# Python SDK, against the same engine",
            "for row in client.query(",
            "    \"SELECT total_volume FROM user_volume\"",
            "    \" WHERE user_id = ?\", \"u42\"):",
            "    volume = row[\"total_volume\"]",
        ],
        "code_w": 0.52,
        "items": [
            ("The same planner and operators",
             "A read is planned by the planner a continuous query uses, so a WHERE means exactly "
             "what it means there rather than nearly. A lookup by key is a hash probe."),
            ("Over Arrow Flight SQL",
             "The Java and Python SDKs, the CLI and the console."),
            ("Over the PostgreSQL wire protocol",
             "Off by default (pravaha.pgwire.enabled): psql, DBeaver, Grafana and any Postgres "
             "driver can read a view, with TLS."),
        ],
        "size": 16,
        "source": "Source: README.md 'What it is' (both SDK snippets, string literal split across "
        "lines for the slide) and 'Serving'; docs/design/ARCHITECTURE.md 'One path, end to end' step 4.",
    },
    {
        "kind": "bullets",
        "kicker": "Subscribe",
        "title": "A subscriber follows the view commit by commit",
        "items": [
            ("From a snapshot, with nothing lost between",
             "The first batch is the view as a commit left it; every batch after is a later commit, "
             "whole and in order. Applying the snapshot, then each commit by weight, gives the view."),
            ("Registration and subscription are separate",
             "Many subscribers share one computation, which outlives them all: a reconnecting "
             "dashboard costs nothing. Each may tap its own filter — still one read, one state."),
            ("A slow subscriber never blocks the engine",
             "Its buffer is bounded and overflow is declared — CONFLATE, DROP_OLDEST or FAIL — and "
             "whatever is lost is counted, and reaches the client over Flight."),
            ("Its consumer runs on its own thread",
             "A commit hands each subscriber the batch and returns; a slow consumer backs up its own "
             "buffer, not the feed's publish timer."),
        ],
        "source": "Source: docs/guides/USER_GUIDE.md 'Subscribing to a registered query' (snapshot then "
        "commits; CONFLATE, DROP_OLDEST, FAIL); docs/guides/CONCEPTS.md §8 (STRM-8); docs/operations/OPERATIONS.md "
        "'What to watch' (dropped()/conflated(), ChangeBatch.droppedBefore, STRM-10).",
    },
]

SLIDES = PART1 + PART2 + PART3
