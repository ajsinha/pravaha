# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The deck, as data. Act 1: the idea -- continuous SQL and incremental view maintenance,
taught before the product is named.

What a continuous query is and the problem it solves; one engine where there were a job,
a sink and a second store; ten principles; what the field offers; the primitives; the
architecture; how an application uses it in five steps; one query and one correction;
the benefits.

One deck split across several modules only to keep each file short; read them in order
(see GUIDE.md). Each slide's ``source`` begins its speaker notes and names where every
figure and claim on it comes from; ``talk`` is the talk track.

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

SLIDES: list[dict[str, Any]] = [
    {
        "kind": "act",
        "num": "1",
        "title": "The idea: continuous SQL",
        "sub": "Register the question once. Let the engine keep the answer current.",
        "source": "Source: docs/guides/CONCEPTS.md §1; README.md 'What it is'.",
        "talk": "Before Pravaha, the category. Most people have met materialised views and stream "
        "processors; fewer have met an engine whose whole job is to keep the answers to registered SQL "
        "current, and to serve them.",
    },
    {
        "kind": "bullets",
        "kicker": "The category",
        "title": "What is continuous SQL — incremental view maintenance?",
        "items": [
            ("A question registered once, not asked again and again",
             "A continuous query is a computation, not a request: registered by name with the columns "
             "its answer is keyed by, it keeps running until somebody drops it."),
            ("The answer is a view the engine maintains",
             "Each change in the input is applied as a change to the answer — a row appearing (+1) or "
             "withdrawn (−1) — instead of recomputing the whole result."),
            ("Reading is a lookup; following is a subscription",
             "Read the view by key or with SQL, as a hash probe rather than a scan; or subscribe and "
             "receive every commit as a batch of weighted changes."),
            ("Correct in event time",
             "Windows close because the data's own timestamps say so, and late data corrects a closed "
             "window as a retraction plus the new answer."),
        ],
        "takeaway": "Registering is the expensive act; asking becomes cheap — the opposite of a database.",
        "source": "Source: docs/guides/CONCEPTS.md §1 'A continuous query is a computation, not a request' "
        "(registering expensive, querying cheap, a hash probe), §2–§4 (event time, watermarks, weights); "
        "README.md 'What it is' (registered by name with key columns; +1 / −1).",
        "talk": "Define the words once. A continuous query is registered, not asked. Its answer is a view "
        "the engine keeps current by applying deltas. You then read it by key, or subscribe to its "
        "changes. Time is the data's time, so the same input gives the same answer however fast it "
        "arrives.",
    },
    {
        "kind": "split",
        "kicker": "The problem",
        "title": "A batch answer is as old as its last run; polling re-reads to stay fresh",
        "left": {
            "head": "Batch, in the case studies' own words",
            "items": [
                ("Card authorisation velocity",
                 "“The fraud is detected four minutes after the fourth authorisation cleared.”"),
                ("Intraday counterparty exposure",
                 "“The number on the risk screen is as old as the last run.”"),
                ("Order revenue into Iceberg",
                 "“By mid-morning the analysts are looking at yesterday.”"),
            ],
            "size": 15,
        },
        "right": {
            "head": "Polling, and what it still misses",
            "items": [
                ("Every row, every run, for ever",
                 "“The job re-reads rows it has already read, every run, forever.”"),
                ("A load on the database that serves the tills",
                 "Polling for low stock “puts a scan on the database that serves the tills.”"),
                ("What happens between two polls",
                 "It “misses a line that dips and recovers between polls.”"),
            ],
            "size": 15,
        },
        "takeaway": "Nobody here is short of data — they are short of an answer that is current when "
        "someone has to act on it.",
        "source": "Source: examples/case-studies/banking-card-velocity/README.md, "
        "finance-counterparty-exposure/README.md, lakehouse-orders-iceberg/README.md, "
        "retail-inventory-mysql/README.md (problem statements, quoted).",
        "talk": "These are quotations from the worked systems in the repository, not invented customers. "
        "Batch is late by construction. Polling buys freshness by reading everything again, and still "
        "cannot see what happened between two polls.",
    },
    {
        "kind": "context",
        "kicker": "The shape of the fix",
        "title": "From a job, a sink and a second store — to one engine that keeps and serves",
        "nodes": [
            {"id": "s1", "x": 0.00, "y": 0.02, "w": 0.15, "h": 0.36, "head": "Your stores",
             "body": "Tables, topics, change logs"},
            {"id": "j", "x": 0.21, "y": 0.02, "w": 0.17, "h": 0.36, "head": "A job per question",
             "body": "Computes, then writes"},
            {"id": "k", "x": 0.44, "y": 0.02, "w": 0.17, "h": 0.36, "head": "A sink",
             "body": "Where the answer lands"},
            {"id": "d", "x": 0.67, "y": 0.02, "w": 0.15, "h": 0.36, "head": "Another store",
             "body": "To serve the answer"},
            {"id": "a1", "x": 0.88, "y": 0.02, "w": 0.12, "h": 0.36, "head": "The app"},
            {"id": "s2", "x": 0.00, "y": 0.58, "w": 0.15, "h": 0.40, "head": "Your stores",
             "body": "Read where they are"},
            {"id": "p", "x": 0.21, "y": 0.58, "w": 0.61, "h": 0.40, "num": "PRAVAHA",
             "head": "Maintains the answer and serves it by key",
             "body": "Filters pushed into the store; one read shared by identical questions; the "
             "view is the thing that is read"},
            {"id": "a2", "x": 0.88, "y": 0.58, "w": 0.12, "h": 0.40, "head": "The app"},
        ],
        "edges": [("s1", "j"), ("j", "k"), ("k", "d"), ("d", "a1"), ("s2", "p"), ("p", "a2")],
        "takeaway": "Dataflow engines compute and write; reading the answer means running another store. "
        "Pravaha serves its own results.",
        "source": "Source: docs/publications/COMPETITIVE_LANDSCAPE.md 'The landscape' (dataflow SQL engines "
        "'compute and write: the answer goes to a sink, and reading it means running another store') and "
        "'Serving its own results', 'Store-native pushdown', 'Sharing identical queries'; README.md 'What "
        "it is' (the result needs no second database to live in).",
        "talk": "The top row is the usual arrangement: every question gets a job, the job writes to a "
        "sink, and something else has to serve the result. The bottom row is the arrangement this "
        "category offers: one engine reads the stores where they are, keeps the answer, and serves it.",
    },
    {
        "kind": "steps",
        "kicker": "Ten principles",
        "title": "Ten principles: why a live answer should be maintained, not recomputed",
        "cols": 2,
        "items": [
            ("A query is a computation, not a request",
             "Registered once, it keeps a view current; asking is a probe."),
            ("Event time, not clock time",
             "Same rows, any order or speed, same answer."),
            ("Watermarks: “nothing earlier is coming”",
             "Windows close because the data says so."),
            ("Changes carry weights",
             "A correction is a retraction (−1) plus an insert (+1)."),
            ("Share by fingerprint, not by name",
             "Identical plans are one computation, per tenant."),
            ("One soundness rule",
             "A filter applies only if the view keeps every column it names."),
            ("Bounds belong in the query or the config",
             "Retention changes meaning; a ceiling refuses loudly."),
            ("Registration and subscription are separate",
             "Many watchers, one computation, warm state."),
            ("Governance is kept with the answer",
             "Who may read, subscribe or build on a live view."),
            ("Alerts say enter and leave",
             "A row leaving the answer clears the alert."),
        ],
        "size": 14,
        "source": "Source: docs/guides/CONCEPTS.md §1–§10 (one principle per section, in its order).",
        "talk": "These ten come straight from the concepts guide, one per section. Most surprises a new "
        "user meets are one of these working correctly — a window that has not closed because the "
        "watermark has not passed, or a filter refused because the view aggregated its column away.",
    },
    {
        "kind": "table",
        "kicker": "What the field says · seven families of product",
        "title": "Each family does part of the job, and is good at it",
        "rows": [
            ["Family", "Well-known examples", "What it leaves to you"],
            ["Distributed dataflow SQL", "Flink SQL, Spark Structured Streaming, Arroyo",
             "Computes and writes; another store serves the answer"],
            ["Streaming databases", "Materialize, RisingWave, Feldera",
             "A server you move data into, fed by change feeds and topics"],
            ["Kafka-native", "ksqlDB, Kafka Streams", "Everything passes through a topic first"],
            ["Dataflow libraries", "Timely and Differential Dataflow, DBSP",
             "No SQL surface, connectors or operations"],
            ["Embeddable JVM", "Hazelcast Jet", "Not an incremental view engine"],
            ["Governance catalogues", "Unity Catalog, Polaris, Lake Formation",
             "Governs data at rest, not an answer still being computed"],
        ],
        "col_w": [1.5, 2.2, 2.6],
        "size": 14,
        "takeaway": "The engines that keep an answer current do not govern it, and the catalogues that "
        "govern data do not see an answer that is still moving.",
        "source": "Source: docs/publications/COMPETITIVE_LANDSCAPE.md 'The landscape' (the seven families, "
        "their examples and limits, and the closing pattern, quoted) and 'Disclaimer' (categories, not "
        "vendors; no product measured). No quotation from a person is used: the repository cites none.",
        "talk": "Instead of quotes from industry figures — the repository cites none, and we do not invent "
        "them — here is the field as the competitive landscape describes it, by category, not vendor. "
        "Every family is good at what it does; the gap is the combination.",
    },
    {
        "kind": "table",
        "kicker": "The primitives",
        "title": "Eight primitives carry the whole model",
        "rows": [
            ["Primitive", "What it is", "Where you meet it"],
            ["Stream and source", "A named, typed, unbounded sequence of rows, bound to a plugin "
             "that produces them", "pravaha.streams / pravaha.sources"],
            ["Continuous query", "A registered computation over one or more streams",
             "CREATE CONTINUOUS QUERY … AS SELECT"],
            ["View and key", "The query's answer, maintained incrementally; a row with the same key "
             "replaces the last", "KEYED BY (…), RETAIN FOR"],
            ["Event time and watermark", "The data's own timestamp; “no row earlier than T is coming”",
             "event-time, out-of-orderness"],
            ["Weight and commit", "+1 a row appears, −1 it is withdrawn; changes arrive per commit, "
             "never per row", "Every subscription batch"],
            ["Lane", "One thread at a time, one inbox, one arena: the unit of execution",
             "WITH (lane = 'dedicated')"],
            ["Sink", "Where every commit is also written, retractions included",
             "pravaha register --sink"],
            ["Subscription", "A consumer attached to a view, from a snapshot, nothing lost between",
             "subscribe(snapshot=True)"],
        ],
        "col_w": [1.45, 3.2, 1.9],
        "size": 13.5,
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §1 'The three nouns' and §2–§3; "
        "docs/guides/CONCEPTS.md §2–§4, §8; docs/design/EXECUTION_MODEL.md §1 (lane); README.md "
        "'Sinks', 'Serving', 'Lanes' (subscribeFromSnapshot, snapshot=True).",
        "talk": "Eight words, and every later slide uses only these. A stream is bound to a source; a "
        "continuous query reads streams; its answer is a keyed view; event time and the watermark decide "
        "when windows close; every change carries a weight and arrives per commit; a lane runs it; a sink "
        "receives it; a subscription follows it.",
    },
    {
        "kind": "context",
        "kicker": "Architecture",
        "title": "How an application uses a maintained view",
        "nodes": [
            {"id": "k", "x": 0.00, "y": 0.00, "w": 0.21, "h": 0.22, "head": "Kafka",
             "body": "One reader per partition"},
            {"id": "c", "x": 0.00, "y": 0.26, "w": 0.21, "h": 0.22, "head": "CDC",
             "body": "PostgreSQL and MySQL logs"},
            {"id": "j", "x": 0.00, "y": 0.52, "w": 0.21, "h": 0.22, "head": "Databases",
             "body": "JDBC, Aerospike, Cassandra"},
            {"id": "f", "x": 0.00, "y": 0.78, "w": 0.21, "h": 0.22, "head": "Files · Delta",
             "body": "Bounded or followed"},
            {"id": "e", "x": 0.29, "y": 0.00, "w": 0.40, "h": 0.66, "num": "PRAVAHA ENGINE",
             "head": "Plan once, run on lanes, keep state, checkpoint at one cut",
             "body": "Calcite plans; Pravaha's own operators run over off-heap rows. Every read, "
             "subscription and registration passes the same grants, row filters and masks."},
            {"id": "v", "x": 0.29, "y": 0.76, "w": 0.40, "h": 0.24, "head": "Views, keyed and current",
             "body": "Read by key, scanned with SQL, or followed per commit"},
            {"id": "sdk", "x": 0.77, "y": 0.00, "w": 0.23, "h": 0.22, "head": "SDKs",
             "body": "Java, Python, over Flight SQL"},
            {"id": "pg", "x": 0.77, "y": 0.26, "w": 0.23, "h": 0.22, "head": "PostgreSQL wire",
             "body": "psql, Power BI, Grafana"},
            {"id": "ui", "x": 0.77, "y": 0.52, "w": 0.23, "h": 0.22, "head": "Console · CLI",
             "body": "REST under /api/v1 too"},
            {"id": "sk", "x": 0.77, "y": 0.78, "w": 0.23, "h": 0.22, "head": "Sinks",
             "body": "Kafka, JDBC, Delta, Iceberg …"},
        ],
        "edges": [("k", "e"), ("c", "e"), ("j", "e"), ("f", "v"), ("e", "v"),
                  ("e", "sdk"), ("e", "pg"), ("e", "ui"), ("v", "sk")],
        "source": "Source: docs/design/ARCHITECTURE.md §1 'The system in one picture' and §2 (layers; "
        "plugin table); README.md 'What works' (SQL, Sources, Serving, Sinks, Governed catalogue).",
        "talk": "Left, the stores Pravaha reads where they are. Middle, the engine: plan once, run on lanes, "
        "keep state off-heap, checkpoint everything at one cut, and govern every read. Right, the four "
        "ways an application takes the answer: the SDKs over Flight SQL, any PostgreSQL client, the console "
        "and CLI, and sinks.",
    },
    {
        "kind": "steps",
        "kicker": "In five steps",
        "title": "How applications use Pravaha",
        "items": [
            ("Declare a stream, bind a source",
             "A schema and its event-time column under pravaha.streams; a plugin and its options under "
             "pravaha.sources — enough to plan with nothing attached."),
            ("Register the question",
             "CREATE CONTINUOUS QUERY name KEYED BY (…) AS SELECT … — from SQL, the CLI, an SDK, the "
             "console or the embedded engine. What cannot work is refused here, by code."),
            ("Read it, or subscribe",
             "Point reads and SQL over the view; or a subscription from a snapshot, then every commit with "
             "its weights, reconnecting across a restart."),
            ("Change it safely",
             "CREATE OR REPLACE runs the new version beside the old, replays, and swaps at the exact "
             "position the running one reached — no gap, nothing counted twice."),
            ("Operate it",
             "pravaha doctor and top, per-query metrics and dashboards, alerts that fire and clear, and a "
             "time-travel debugger that forks a query from a checkpoint."),
        ],
        "size": 15,
        "takeaway": "The application never polls: it asks once, then reads or listens.",
        "source": "Source: docs/guides/CONTINUOUS_QUERIES.md §2–§3 (declaring, registering); "
        "docs/guides/USER_GUIDE.md 'Subscribing to a registered query', 'Surviving a restart: reconnect'; "
        "README.md 'Blue/green replacement' (ADR-046), 'Observability', 'Alerts', 'Time-travel debugger'; "
        "pravaha-console/content/topics/cli-reference.md 'Doctor', 'Top'.",
        "talk": "This is the whole user journey. Declare, register, read or subscribe, change safely, "
        "operate. Step four is where most systems cut over at a moment and silently lose or double "
        "count; Pravaha meets the running version at a position.",
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
             "By AerospikeContinuousQueryIT, over a binding with deletes: detect — without it the "
             "aggregate is refused (PRV-2042) rather than count a record twice."),
            ("Every query is continuous",
             "SELECT STREAM is accepted and redundant; registered as CREATE CONTINUOUS QUERY "
             "txn_volume KEYED BY (window_end, user_id)."),
        ],
        "size": 15,
        "source": "Source: README.md 'What it is' (the query and its description, verbatim; "
        "AerospikeContinuousQueryIT needs Docker).",
        "talk": "One real statement, from the README, run by an integration test against a real Aerospike. "
        "Note the filter: it is evaluated inside the store, so the engine never reads the rows it would "
        "throw away.",
    },
    {
        "kind": "code",
        "kicker": "A weight on every row",
        "title": "A late reading corrects a closed window: −1 then +1",
        "code": [
            "-- snapshot",
            "+1 {window_end 08:03, press-02, readings 6, max 980}",
            "-- commit",
            "-1 {window_end 08:03, press-02, readings 6, max 980}",
            "+1 {window_end 08:03, press-02, readings 7, max 991}",
        ],
        "wide": True,
        "code_h": 1.6,
        "code_size": 15,
        "items": [
            ("Weight: the Z-set row",
             "+1 is a row appearing, −1 one being withdrawn; a change of weight zero is never "
             "delivered."),
            ("Commit",
             "Changes arrive per commit, never per row, so nobody acts on a total still being "
             "assembled."),
            ("What a consumer does",
             "Wants current values: ignore −1 and overwrite by key. Keeps its own aggregate: apply the "
             "weights, or it drifts the first time a window is corrected."),
        ],
        "size": 15,
        "note": "A real run: a late reading from press-02 corrected the 08:03 minute (machine sensor "
        "anomalies, step 5), trimmed to the columns that changed.",
        "source": "Source: docs/guides/CONCEPTS.md §4 'Changes carry weights'; docs/guides/USER_GUIDE.md "
        "'Subscribing to a registered query' (per commit); examples/case-studies/"
        "manufacturing-sensor-anomalies/README.md step 5 (snapshot and commit lines, columns trimmed).",
        "talk": "This is incremental maintenance in one picture. The window had closed with six readings; "
        "a late seventh arrives inside the allowed lateness; the old row is withdrawn and the new one "
        "inserted, in one commit.",
    },
    {
        "kind": "table",
        "kicker": "Benefits",
        "title": "What maintaining the answer buys",
        "rows": [
            ["Benefit", "What it means", "Held by"],
            ["Current answers", "A view moves with every commit of its input", "Watermarks, per-commit delivery"],
            ["No second database", "The answer is served by key where it is kept", "Flight SQL, pgwire"],
            ["Cheap reads", "A point read is a hash probe, not a scan", "The maintained view"],
            ["One computation per question", "Identical plans share state and one read of the source",
             "Fingerprints (ADR-050)"],
            ["Exact hand-overs", "Subscribers, restarts, sharing and replacement meet at a position",
             "ADR-008, ADR-046, ADR-054"],
            ["Refusal before the fact", "Unbounded state, unusable sinks, vacuous filters: refused by code",
             "Registration checks"],
            ["Governed where it lives", "Grants, row filters and masks on the live view and open stream",
             "The catalogue (ADR-059)"],
            ["Survives restart", "State, offsets and view at one cut; resume, not replay",
             "Checkpoints (ADR-008)"],
        ],
        "col_w": [1.6, 3.2, 1.7],
        "size": 13.5,
        "source": "Source: docs/publications/COMPETITIVE_LANDSCAPE.md 'What the rows have in common'; "
        "docs/guides/CONCEPTS.md §1, §5; README.md 'Serving', 'Recovery', 'Governed catalogue'; "
        "docs/design/adr/008, 046, 050, 054, 059.",
        "talk": "Each benefit on the left names the mechanism that holds it on the right, so none of them is "
        "a slogan. That ends the idea. Next: the product.",
    },
]
