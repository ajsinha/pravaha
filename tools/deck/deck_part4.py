"""
The deck, as data. Parts 10 and 11: the thirteen case studies, four in depth,
and what is measured, what is not built, and where to start.

Every number on a case-study slide is quoted from the study's README, where it
is either the output of a real run or an answer the build checks
(CaseStudyRunTest). Every performance figure is from the gate packs and
benchmarks/README.md, with the machine it was taken on.

One deck split across several modules only to keep each file short; read them in
order (see GUIDE.md).

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

PART10: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "10",
        "title": "Thirteen worked systems",
        "sub": "Each study is a business problem, a data model, the data, the continuous queries "
        "and the client code in Java and Python — and every one is run by the build against "
        "answers worked out by hand.",
        "points": [
            "The thirteen, and what each shows",
            "How the build checks them",
            "Late data, corrected in place",
            "Change data capture from MySQL",
            "A continuous aggregate in Iceberg",
            "Many desks, one Kafka topic",
            "Two joins: attribution and absence",
        ],
        "source": "Source: examples/case-studies/README.md.",
    },
    {
        "kind": "table",
        "kicker": "The case studies",
        "title": "Thirteen systems, from a pass-through feed to a lakehouse",
        "rows": [
            ["Study", "Store", "Shows"],
            ["Trade processing", "Aerospike", "Start here: a pass-through feed, many filtered subscribers on one computation"],
            ["Card authorisation velocity", "Aerospike", "Tumbling windows, temporal lookup join, filter pushdown"],
            ["Intraday counterparty exposure", "PostgreSQL", "Incremental polling, money as minor units, value time vs insert time"],
            ["Order flow surveillance", "Aerospike", "Hopping windows, and what to do without CASE"],
            ["Sequencing run QC", "Aerospike", "A non-financial domain; integer AVG; breadth as well as depth"],
            ["Machine sensor anomalies", "CSV", "A filter writing to a sink; late data and its correction"],
            ["Checkout funnel", "CSV", "Windows over several keys, COUNT(DISTINCT), HAVING as an alert"],
            ["Click attribution", "CSV", "A time-bounded join between two streams; a window over a join"],
            ["Call-detail-record fraud", "CSV", "Sliding windows, an IN filter, a maintained top-N"],
            ["Delivery SLA breaches", "CSV", "An interval join to a sink; a LEFT JOIN that reports an absence"],
            ["Stock levels from MySQL", "MySQL binlog", "mysql-cdc: an update is −1 old, +1 new; alerts clear themselves"],
            ["Order revenue into Iceberg", "CSV → Iceberg", "iceberg-sink upsert; a late row corrects the table"],
            ["Many desks, one topic", "Kafka", "Three queries on one reader, exactly once; INDEX (merchant)"],
        ],
        "col_w": [1.6, 0.95, 3.3],
        "size": 13.5,
        "source": "Source: examples/case-studies/README.md, the study table ('Shows' column abridged).",
    },
    {
        "kind": "bullets",
        "kicker": "Checked by the build",
        "title": "Every study is run, and every read compared with a hand-worked answer",
        "items": [
            ("CaseStudySqlTest plans every .sql file against the real engine",
             "And asserts that each README quotes the file rather than a retyping of it; it fails if "
             "a study directory is not listed."),
            ("CaseStudyRunTest runs every study in an embedded engine",
             "No server, no store, no Docker: it registers each study's queries, pushes the rows of "
             "data/sample/ merged by event time, and compares every read with answers.txt."),
            ("Running them found real defects, now fixed",
             "Wrong view keys in four studies — biology, finance, trading, trade processing — and a "
             "windowing defect a filtered windowed query would have met on its first row."),
            ("Driven only through the published SDK or the CLI",
             "None reaches into the engine, so copying one into an application drags no engine "
             "with it."),
        ],
        "source": "Source: examples/case-studies/README.md ('What they have in common', the test "
        "paragraphs); commit f2ff2572 and 89d56996 (the four wrong keys; the windowing defect).",
    },
    {
        "kind": "code",
        "kicker": "In depth · machine sensor anomalies",
        "title": "A late reading corrects a closed minute",
        "intro": "A press runs hot before its seals fail. machine_health keeps each machine's minute; "
        "the stream allows 30 seconds of lateness. The gateway delivers a buffered reading late:",
        "code": [
            "-- snapshot",
            "+1 {window_end 08:03, press-02, readings 6,",
            "    min_temperature_dc 940, max_temperature_dc 980}",
            "-- commit",
            "-1 {window_end 08:03, press-02, readings 6,",
            "    min_temperature_dc 940, max_temperature_dc 980}",
            "+1 {window_end 08:03, press-02, readings 7,",
            "    min_temperature_dc 940, max_temperature_dc 991}",
        ],
        "code_w": 0.52,
        "items": [
            ("Inside the lateness: corrected",
             "The minute ending 08:03:00 was published at watermark 08:03:15 and stays correctable "
             "to 08:03:30, so press-02-late was applied — old row withdrawn, new inserted, one commit."),
            ("Outside it: dropped",
             "press-01-stale, for 08:00:45, arrived long after its lateness ran out at 08:01:30."),
            ("Published when event time moves",
             "The second heartbeat carried the correction out."),
        ],
        "size": 15.5,
        "source": "Source: examples/case-studies/manufacturing-sensor-anomalies/README.md step 5 "
        "(output lines trimmed of line_id and vibration; temperatures in deci-degrees; the three "
        "bullets follow 'What happened, reading by reading').",
    },
    {
        "kind": "split",
        "kicker": "In depth · stock levels from MySQL",
        "title": "Change data capture: an alert that clears itself",
        "intro": "stock_levels mirrors the inventory table from its binlog; low_stock is WHERE on_hand "
        "<= reorder_point. One morning of changes, as the generator writes it:",
        "left": {
            "head": "The morning",
            "rows": [
                ["Time", "Change", "Effect"],
                ["09:05", "UPDATE sku-200 LDN 12 → 7", "Under reorder point 8: alert"],
                ["09:20", "UPDATE sku-200 LDN 7 → 27", "A delivery: the alert is −1"],
                ["09:25", "DELETE sku-400 MAN", "Discontinued: the row is −1"],
                ["09:30", "UPDATE sku-100 LDN 40 → 10", "At reorder point 10: alert"],
            ],
            "col_w": [0.6, 2.0, 1.8],
            "size": 14,
        },
        "right": {
            "head": "What the view says",
            "items": [
                ("LDN holds 3 lines and 39 units",
                 "39 is 10 + 27 + 2, today's numbers — not 40 + 12 + 6 plus the changes, because each "
                 "update withdrew the row it replaced."),
                ("A cleared alert is a −1",
                 "“A consumer that only listened for rows arriving would page the buyer about toasters "
                 "for ever.”"),
                ("Exactly once from the binlog",
                 "Resumes from the file and position its last checkpoint recorded."),
            ],
            "size": 15,
        },
        "source": "Source: examples/case-studies/retail-inventory-mysql/README.md steps 4–5 (the "
        "morning table, abridged to four of six statements; units per warehouse; the toaster "
        "sentence); checked by CaseStudyRunTest.",
    },
    {
        "kind": "code",
        "kicker": "In depth · order revenue into Iceberg",
        "title": "A late line corrects a row in an Iceberg table, in place",
        "intro": "hourly_revenue is written by iceberg-sink in upsert mode, one snapshot per "
        "checkpoint, with fifteen minutes of allowed lateness. A UK line for 11:50 arrives late:",
        "code": [
            "-- commit",
            "-1 {window_end 12:00, region UK, orders 4,",
            "    revenue_minor 29970, customers 4}",
            "+1 {window_end 12:00, region UK, orders 5,",
            "    revenue_minor 34170, customers 4}",
        ],
        "code_w": 0.5,
        "items": [
            ("One row for the hour, not two",
             "The −1/+1 pair for one key collapses to its last change: the next snapshot holds an "
             "equality delete for (12:00, UK) and a data file with the new row. A reader sees 5 "
             "orders and 34170."),
            ("customers stays 4",
             "The late line's customer had already ordered in that hour."),
            ("An append-only sink could not have done this",
             "Nor mode: changelog, which keeps the history with _op and _weight instead."),
        ],
        "size": 15.5,
        "source": "Source: examples/case-studies/lakehouse-orders-iceberg/README.md step 5 (commit lines "
        "trimmed of the date; the collapse to one row, customers staying 4); checked by CaseStudyRunTest.",
    },
    {
        "kind": "code",
        "kicker": "In depth · many desks, one topic",
        "title": "Three queries on one Kafka reader, and an index by merchant",
        "code": [
            "CREATE CONTINUOUS QUERY merchant_minute",
            "  KEYED BY (window_end, merchant)",
            "  INDEX (merchant)",
            "AS",
            "SELECT STREAM",
            "  TUMBLE_END(paid_at, INTERVAL '1' MINUTE)",
            "                    AS window_end,",
            "  merchant,",
            "  COUNT(*)          AS payments,",
            "  SUM(amount_minor) AS amount_minor",
            "FROM payment",
            "WHERE status = 'APPROVED'",
            "GROUP BY TUMBLE(paid_at, INTERVAL '1' MINUTE),",
            "         merchant",
        ],
        "code_w": 0.52,
        "items": [
            ("One reader for all three queries",
             "“Register the three queries in any order, at any time, and pause, resume or restart any "
             "of them. The topic is read once, and each record reaches each query exactly once.”"),
            ("A lookup by merchant is a probe",
             "m-coffee reads 5 payments, 17578; m-books 5 payments, 81250. The build fails the study "
             "if no read went through the index."),
        ],
        "size": 15.5,
        "source": "Source: examples/case-studies/payments-shared-kafka/README.md (the statement "
        "verbatim, one line wrapped; the quotation; coffee and books totals); ADR-054, ADR-055; "
        "CaseStudyRunTest.",
    },
    {
        "kind": "split",
        "kicker": "Two joins",
        "title": "A join that attributes, and a join that sees an absence",
        "left": {
            "head": "Click attribution",
            "items": [
                ("A click is paid for only after an impression",
                 "attributed_clicks joins two streams where the click falls within ten minutes of "
                 "the impression; the time bound bounds the state too."),
                ("61 impressions, 17 clicks, 14 attributed",
                 "Click-through rate is two views joined by the reader: joining two views in one read "
                 "is refused (PRV-4025)."),
            ],
            "size": 15.5,
        },
        "right": {
            "head": "Delivery SLA breaches",
            "items": [
                ("“This is the query that sees an absence”",
                 "undelivered is a LEFT JOIN of dispatches to deliveries within four hours, WHERE the "
                 "delivery is NULL: padded with nulls once, when event time passes the bound."),
                ("25 dispatches, 23 deliveries",
                 "P1023, P1020 and P1002 are reported, all from depot BRS3. P1002 was delivered — four "
                 "hours and ten minutes after dispatch, outside the bound; a null-padded row is "
                 "emitted once and never withdrawn."),
            ],
            "size": 15.5,
        },
        "source": "Source: examples/case-studies/adtech-click-attribution/README.md (generator output "
        "'wrote 61 impressions and 17 clicks'; 'Fourteen clicks are attributed in all'; PRV-4025); "
        "examples/case-studies/logistics-delivery-sla/README.md ('wrote 25 dispatches and 23 "
        "deliveries'; undelivered output; the quotation).",
    },
]

PART11: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "11",
        "title": "What is measured, what is not built, and where to start",
        "sub": "Every number here was taken on a developer laptop running other work, with the load "
        "average beside it. A target that was not reached is reported as not reached, and none of "
        "these figures is quoted as the engine's capability.",
        "points": [
            "The requirement, and the gates kept beside it",
            "What the gates measured",
            "Nexmark: what runs",
            "What the micro-benchmarks say, and do not",
            "What is not built",
            "Boundaries",
            "Where to start",
        ],
        "source": "Source: docs/gates/measured-2026-09-20/README.md; benchmarks/README.md; "
        "docs/adr/042-the-throughput-bar-is-the-requirement.md; README.md 'Performance', 'What is "
        "not built'.",
    },
    {
        "kind": "bullets",
        "kicker": "ADR-042",
        "title": "The requirement is about 1,000 rows a second, and the old bar is kept",
        "items": [
            ("Why so low",
             "Pravaha maintains answers to registered questions rather than moving bulk data; about a "
             "thousand rows a second is what the owner's workload needs."),
            ("Restated, not redefined",
             "The design's figures — 1.2 M rows/s per lane for Profile A, 350 k for Profile B, ≥ 90 % "
             "scaling from one lane to eight — stay on the page as the gate criteria, so a reader can "
             "see the bar moved and judge the reason."),
            ("What it does not license",
             "A number from this laptop is “this machine sustained that, under this load” — a floor, "
             "never the engine's ceiling."),
        ],
        "source": "Source: docs/adr/042-the-throughput-bar-is-the-requirement.md (Decision; 'Why this "
        "is not the thing this project forbids'; 'What this decision does NOT license'); README.md "
        "'Performance'.",
    },
    {
        "kind": "table",
        "kicker": "Gates P2 and P3 · measured 2026-09-20, re-measured 2026-09-26",
        "title": "Per-lane throughput reached; scaling not reached",
        "rows": [
            ["Gate", "Target", "Measured", "Verdict"],
            ["P2 · Profile A throughput", "≥ 1.2 M rows/s per lane",
             "All 30 warm passes and 29 of 30 cold above it; the miss 1,043,974 at load 77", "Reached"],
            ["P2 · scaling, 1 → 8 lanes", "≥ 90 % of linear",
             "28–42 % (09-20); 33–46 % without the coverage agent (09-26); the machine's own ceiling "
             "for work sharing nothing: 51–55 %", "Not reached"],
            ["P3 · Profile B throughput", "≥ 350 k rows/s per lane",
             "Every one of 15 passes above it; the worst 1,096,560, during a load spike", "Reached"],
            ["W5 · Nexmark vs Flink", "Parity on ≥ 18 of 22", "Not run: no Flink, no quiet machine, no "
             "reference generator here", "Not reached"],
        ],
        "col_w": [1.4, 1.2, 3.2, 0.9],
        "size": 14.5,
        "note": "The machine: an AMD Ryzen AI 9 HX 370 laptop — 12 physical cores of two designs, SMT2 "
        "— running other build agents throughout, at load averages from 5.8 to 77.8.",
        "source": "Source: docs/gates/measured-2026-09-20/README.md (verdict line; 'The machine, named'; "
        "Gate P2 throughput and scaling; re-measured 2026-09-26 incl. machineScalingReference; Gate "
        "P3; W5).",
    },
    {
        "kind": "table",
        "kicker": "Nexmark coverage · NexmarkCoverageIT",
        "title": "12 of Nexmark's 23 queries run; what is missing is SQL, not speed",
        "rows": [
            ["Verdict", "Queries", "Why"],
            ["Runs (2026-09-26)", "q0, q1, q2, q3, q7, q8, q9, q18, q19, q20, q21, q22",
             "Up from 5 on 2026-09-20, after exact DECIMAL, self joins, a maintained top-N, "
             "REGEXP_EXTRACT and SPLIT_INDEX"],
            ["Refused", "q4, q5, q15, q16, q17", "Unwindowed GROUP BY or COUNT(DISTINCT): unbounded state "
             "(PRV-2050), refused on purpose"],
            ["Refused", "q6", "An AVG over a row frame (PRV-2021)"],
            ["Refused", "q11", "Session windows in SQL (PRV-2020)"],
            ["Cannot be written here", "q10, q12, q13, q14", "A partitioned file sink, PROCTIME(), no "
             "lookup source registered, a user-defined function"],
        ],
        "col_w": [1.1, 2.0, 2.9],
        "size": 14.5,
        "note": "The head-to-head against Flink has not been run. Running it on a subset and "
        "publishing that is the selective benchmarking the design says this audience punishes.",
        "source": "Source: docs/gates/measured-2026-09-20/README.md 'Coverage re-measured on "
        "2026-09-26 — 12 of 23' and 'The head-to-head cannot be run here'; README.md 'Performance'.",
    },
    {
        "kind": "table",
        "kicker": "Micro-benchmarks · benchmarks/README.md",
        "title": "Ratios that decide design choices, not capabilities",
        "rows": [
            ["Measurement", "Figure", "What it is not"],
            ["Generated vs interpreted, the fused operator alone", "292 M vs 28.7 M rows/s — roughly 10×",
             "The gate figure: no decode, arena, handoff or sink"],
            ["The same plan as a whole pipeline, one thread", "106–118 M vs 60–66 M rows/s — 1.7×",
             "End to end, where the two are level (0.95–1.13×): the producer is the bound"],
            ["Lane machinery, one lane", "21 M rows/s", "Operators, decode or sinks"],
            ["Padding between two cursors", "453 M vs 110 M ops/s — roughly 4×",
             "Precise: error bars are wide on this hardware; the gap is not"],
        ],
        "col_w": [2.0, 2.0, 2.2],
        "size": 14.5,
        "note": "No build compares a run with the committed baselines, and the page says so: a claim "
        "about a gate that does not exist is worth less than no claim.",
        "source": "Source: benchmarks/README.md (Profile A generated vs interpreted; lane scaling; "
        "false sharing; PF-2); docs/gates/measured-2026-09-20/README.md 'C-7: generated against "
        "interpreted, end to end'.",
    },
    {
        "kind": "split",
        "kicker": "Not built",
        "title": "Feature-complete for one node; these are deferred, or not yet done",
        "left": {
            "head": "Deferred or dropped by decision",
            "items": [
                ("Multi-node execution",
                 "Membership, fenced leases and rebalance are built as libraries and no node consumes "
                 "them; a node refuses PARTITIONED mode (PRV-9002)."),
                ("MFA and single sign-on",
                 "Dropped by the owner: users, passwords, API keys and sessions are what Pravaha keeps."),
                ("Not yet proven",
                 "The eight-lane scaling target; the manual WCAG 2.2 AA audit."),
            ],
            "size": 15.5,
        },
        "right": {
            "head": "First versions: not yet",
            "rows": [
                ["Piece", "Not yet"],
                ["mysql-cdc", "An initial snapshot, TLS, GTID positions across a failover"],
                ["iceberg-sink", "Object stores, catalogs, partitioned tables, schema evolution"],
                ["One reader per ordered source", "Delta and JDBC; CDC keeps a reader per query"],
                ["Equality index", "A way to see which access path a read took"],
            ],
            "col_w": [1.4, 2.6],
            "size": 14,
        },
        "source": "Source: README.md 'What is not built, or not finished' (deferred by decision; first "
        "versions table; not yet proven); docs/adr/039; docs/adr/052 status line.",
    },
    {
        "kind": "bullets",
        "kicker": "Boundaries",
        "title": "Limits of a store, a format or a decision — more code would not remove them",
        "items": [
            ("MIN and MAX cannot be retracted incrementally",
             "So they are never pre-combined at a source; an Aerospike partial would need UDFs on "
             "the customer's cluster."),
            ("End-to-end delivery is capped by the source",
             "Transactional sinks stage each checkpoint; a source that cannot rewind caps them."),
            ("No RocksDB (ADR-044); no lz4 (ADR-053)",
             "The memory-mapped tier is for surviving state larger than memory, not for capacity."),
            ("Formats say what they say",
             "A proto3 scalar without optional has no NULL; an upsert tombstone does not say which row "
             "it deletes; a TRUNCATE names no rows to retract."),
            ("Grants live in the deployment's policy",
             "The console shows them and does not edit them."),
        ],
        "source": "Source: README.md 'Boundaries: limits of a store, a format or a recorded decision'.",
    },
    {
        "kind": "table",
        "kicker": "Where to start",
        "title": "From a clone to a changing view, then the whole surface",
        "rows": [
            ["Read", "For"],
            ["docs/QUICKSTART.md", "Clone to a running, changing view in about ten minutes"],
            ["docs/CONCEPTS.md", "The eight ideas; most surprises are one of them working correctly"],
            ["docs/tutorials/", "Four lessons: registering, following, replacing and debugging a query"],
            ["examples/case-studies/", "Thirteen worked systems to copy, each checked by the build"],
            ["docs/CONTINUOUS_QUERIES.md", "Streams, sources, registration, reading, and every SQL "
             "construct that works or is refused"],
            ["docs/OPERATIONS.md · SECURITY.md", "Sizing, lanes, recovery, what to watch; authentication, "
             "authorization, audit"],
            ["docs/adr/", "Every architectural decision, including the ones later reversed"],
        ],
        "col_w": [1.7, 3.6],
        "size": 15.5,
        "note": "Ask once. Answer always.",
        "source": "Source: README.md 'Try it' and 'Documentation'; docs/CONCEPTS.md opening; "
        "examples/case-studies/README.md (docs/tutorials/, four lessons).",
    },
]

SLIDES = PART10 + PART11
