# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The deck, as data. Parts 11 and 12: the thirteen case studies, four in depth,
and the evidence -- what is measured and tested, the adversarial QA round,
what 2.x promises, what is not built -- and where to start.

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
        "num": "11",
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
                 "CREATE ALERT low_stock_alert ON low_stock NOTIFY buyers: FIRED sku-200 at 09:05, "
                 "CLEARED at 09:20 — the retraction the binlog delivered."),
                ("Exactly once from the binlog",
                 "Resumes from the file and position its last checkpoint recorded."),
            ],
            "size": 15,
        },
        "source": "Source: examples/case-studies/retail-inventory-mysql/README.md steps 4–5 (the "
        "morning table, abridged to four of six statements; units per warehouse) and step 7 (the "
        "alert, its FIRED and CLEARED lines); checked by CaseStudyRunTest and "
        "RetailLowStockAlertEndToEndTest.",
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
        "num": "12",
        "title": "Evidence, 2.x, and where to start",
        "sub": "Every number here was taken on a developer laptop running other work, with the load "
        "average beside it. A target that was not reached is reported as not reached, and none of "
        "these figures is quoted as the engine's capability.",
        "points": [
            "The requirement, and the gates kept beside it",
            "What the gates measured; Nexmark; micro-benchmarks",
            "How it is tested, and what testing found",
            "The adversarial round against 2.0.0, and three waves of fixes",
            "What 2.x promises, and what is experimental",
            "What is not built; boundaries",
            "Where to start",
        ],
        "source": "Source: docs/project/gates/measured-2026-09-20/README.md; benchmarks/README.md; "
        "docs/design/adr/042-the-throughput-bar-is-the-requirement.md; docs/development/TESTING.md; docs/project/qa/FINDINGS.md; "
        "docs/operations/COMPATIBILITY.md; README.md 'Performance', 'What is not built'.",
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
        "source": "Source: docs/design/adr/042-the-throughput-bar-is-the-requirement.md (Decision; 'Why this "
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
             "28–42 % (09-20); 33–46 % without the coverage agent (09-26); 30–31 % re-taken at load "
             "3.1–5.2 (09-29); the machine's own ceiling for work sharing nothing: 51–55 %",
             "Not reached"],
            ["P3 · Profile B throughput", "≥ 350 k rows/s per lane",
             "Every one of 15 passes above it; the worst 1,096,560, during a load spike", "Reached"],
            ["W5 · Nexmark vs Flink", "Parity on ≥ 18 of 22", "Not run: no Flink, no quiet machine, no "
             "reference generator here", "Not reached"],
        ],
        "col_w": [1.4, 1.2, 3.2, 0.9],
        "size": 14.5,
        "note": "The machine: an AMD Ryzen AI 9 HX 370 laptop — 12 physical cores of two designs, SMT2 "
        "— running other build agents throughout, at load averages from 5.8 to 77.8.",
        "source": "Source: docs/project/gates/measured-2026-09-20/README.md (verdict line; 'The machine, named'; "
        "Gate P2 throughput and scaling; re-measured 2026-09-26 incl. machineScalingReference; Gate "
        "P3; W5); docs/project/RELEASE_NOTES.md '1.0.0' PERF-1 and commit 75e9fc9e (30–31 % at load 3.1–5.2).",
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
        "source": "Source: docs/project/gates/measured-2026-09-20/README.md 'Coverage re-measured on "
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
        "false sharing; PF-2); docs/project/gates/measured-2026-09-20/README.md 'C-7: generated against "
        "interpreted, end to end'.",
    },
    {
        "kind": "table",
        "kicker": "How it is tested · docs/development/TESTING.md, 2026-09-29 to 10-01",
        "title": "Tiers, what each needs, and what each ran",
        "rows": [
            ["Tier", "Needs", "Recorded"],
            ["The whole reactor", "JDK 25", "4,832 tests, 0 failures, 12 skipped (2026-10-01); "
             "the Python SDK's 426 against a node on 25"],
            ["In-process integration (pravaha-it)", "JDK 25", "960 tests (counted on 21): real nodes, "
             "documentation checks, every case study against hand-worked answers"],
            ["Plugins against real stores", "Docker", "Testcontainers: Kafka 50, Postgres-CDC 60, JDBC 13, "
             "MySQL-CDC 9, Aerospike 40, Cassandra 39 — all green"],
            ["Python SDK · console", "Python · Chrome", "426 passed · 1,937 passed with the browser "
             "suites, zero axe violations in every theme"],
            ["SDKs on their own", "Docker, Maven", "Four clients outside the repository, green"],
            ["Performance gates", "A quiet machine", "Refuse to report under a coverage agent (PERF-1)"],
        ],
        "col_w": [1.9, 1.1, 3.4],
        "size": 13.5,
        "note": "A green build without Docker has not tested any connector against its store. Error "
        "Prone runs under -Pep and CI gates it: 0 warnings; NullAway's backlog is listed, and the API and SDKs are at 0.",
        "source": "Source: docs/development/TESTING.md 'Where the numbers come from' (4,832 tests, 12 skipped, "
        "on 25), 'The tiers at a glance', 'With Docker', 'Static analysis: Error Prone and NullAway'; "
        "docs/project/RELEASE_NOTES.md '2.0.0' (proved on 25), '1.0.0' (PERF-1), 'Unreleased' (ERRORPRONE-1).",
    },
    {
        "kind": "table",
        "kicker": "The findings register · docs/project/qa/FINDINGS.md",
        "title": "553 findings, one open — and each found one step outside the last test",
        "rows": [
            ["Finding", "Found by", "What it was"],
            ["SINKKEYROWS-2", "A broker test that had not run since a fix",
             "A keyed Kafka sink saw a replaced key as a tombstone, then the value"],
            ["PGWIRETX-1", "psycopg against a running stack",
             "BEGIN refused, so default-mode clients and ORMs failed on their first read"],
            ["SDKNETTYMIX-1", "A Maven client outside the build",
             "Two Netty lines resolved; the SDK failed on its first call"],
            ["VIEWW-1", "The property test the research paper asked for",
             "A key could show the row just retracted; fails 5 of 5 on the old code"],
            ["TESTNAME-1", "Closing the paper's own gaps",
             "A property test whose name no test runner matched had never run"],
        ],
        "col_w": [1.3, 2.2, 2.9],
        "size": 13.5,
        "note": "533 fixed, 10 closed by design, 9 superseded, 1 open (SDKDEADLINE-1); a finding is fixed only when the "
        "case that found it has been re-run. The commonest defect: built, correct, connected to nothing.",
        "source": "Source: docs/project/qa/FINDINGS.md (header counts: 553 — 533 FIXED, 1 OPEN, 10 BY DESIGN, "
        "9 SUPERSEDED; SINKKEYROWS-2, PGWIRETX-1, SDKNETTYMIX-1, VIEWW-1, TESTNAME-1; 'The commonest defect'); "
        "docs/development/TESTING.md 'What this pass found'; docs/project/RELEASE_NOTES.md '1.0.0', 'Unreleased'.",
    },
    {
        "kind": "stats",
        "kicker": "Adversarial QA of 2.0.0 · 2026-10-01 · docs/project/qa/SUMMARY.md",
        "title": "322 cases written to break a promise, each naming the promise",
        "stats": [
            ("322", "Cases in two passes: engine, data and security; surfaces, operations, packaging"),
            ("66", "Failed; 244 passed; 12 blocked or not run, each with its reason"),
            ("46", "Defects — 10 high, 17 medium, 19 low — and 4 design notes"),
            ("0 open", "All 50 fixed in three waves by 2 October, reproductions switched on"),
        ],
        "items": [
            ("Method",
             "Cases written before execution and logged with evidence (an exploratory probe shaped the "
             "expression and window cases, and the log says so). A case passes when the engine does what "
             "its documentation says; a failure needs a reproduction."),
            ("Against what",
             "v2.0.0 on JDK 25, no product code changed: the embedded engine, a child JVM killed with "
             "SIGKILL, a node with four principals in two tenants, and the shipped images in a 1 GiB "
             "container, through psql, psycopg, pgjdbc, pyarrow, the CLI and the console."),
        ],
        "size": 13.5,
        "source": "Source: docs/project/qa/SUMMARY.md 'Adversarial QA of 2.0.0' (table, outcome); "
        "docs/project/qa/cases/ADV-ENGINE.md (stance, harnesses); docs/project/qa/logs/ADV-ENGINE.md "
        "('A note on method'); docs/project/qa/logs/ADV-SURFACE.md (environment).",
    },
    {
        "kind": "split",
        "kicker": "Adversarial QA of 2.0.0 · what held",
        "title": "The seams and the governance held; the edges did not",
        "left": {
            "head": "Held, against an independent oracle",
            "items": [
                ("420 seeded window runs", "≈11,000 watermark advances compared; late data and "
                 "corrections matched exactly"),
                ("40 seeds × 150 chained operations", "Queries on queries, top-N and interval joins "
                 "matched on every step"),
                ("19 SIGKILLs", "At random points: nothing lost, nothing doubled"),
                ("9,800 random predicates", "Interpreted and generated agreed, apart from NANNOT-1"),
                ("Masks, row filters, tenants, grants", "Held on every read path tried; every escalation "
                 "refused"),
            ],
            "size": 13,
        },
        "right": {
            "head": "Broke, in four classes",
            "items": [
                ("The value domain's edges", "Empty groups, narrow integers, −0.0 and NaN, hop alignment"),
                ("Bookkeeping around recovery", "Checksums, journal damage, a shared name's state, "
                 "a lost CDC slot"),
                ("What a stranger or a setting can exhaust", "Pre-auth allocation, request bodies, "
                 "a 1 ms hop over a day, a 512-byte cell"),
                ("Credentials", "A revoked key still reading; lockout naming real users"),
            ],
            "size": 13,
        },
        "source": "Source: docs/project/qa/SUMMARY.md 'What held' and HIGH list; docs/project/qa/logs/ADV-ENGINE.md "
        "(differential volume; QE-077 SIGKILLs); docs/project/qa/FINDINGS.md 'Found by the adversarial QA of "
        "2.0.0' (both sections).",
    },
    {
        "kind": "table",
        "kicker": "Adversarial QA of 2.0.0 · the ten high-severity defects",
        "title": "Ten severe defects in a release with one open finding",
        "rows": [
            ["Finding", "What it did", "Fixed by"],
            ["ALLNULLAGG-1", "SUM/AVG/MIN/MAX of all-NULL groups published 0", "NULL, as SQL says"],
            ["NARROWINT-1", "INT, SMALLINT, TINYINT results published wrapped", "An overflow, named"],
            ["FINEHOP-1", "A 1 ms hop over a day wedged a lane and its stream", "PRV-3026 at registration"],
            ["JOURNALMID-1", "Mid-journal damage silently dropped later registrations",
             "PRV-8005: refuse the start"],
            ["SHAREDLOSS-1", "A shared computation's surviving name restarted empty", "Its home journalled"],
            ["CELLBYTES-1", "Embedded: a row over 512 bytes stopped every query", "Settings read; row refused"],
            ["PGPREAUTH-1", "60 silent pgwire sockets ended a 1 GiB node", "16 KiB pre-auth, capped sockets"],
            ["HTTPBODY-1", "30 anonymous 19 MB sign-ins did the same", "413 before the body is read"],
            ["PGREVOKE-1", "A revoked credential kept reading on pgwire", "Re-verified every statement"],
            ["CDCSLOT-1", "A dropped CDC slot: RUNNING, UP, changes lost", "PRV-5117; health DEGRADED"],
        ],
        "col_w": [1.4, 3.1, 2.0],
        "size": 12.5,
        "source": "Source: docs/project/qa/SUMMARY.md HIGH list; docs/project/qa/FINDINGS.md status lines for "
        "each; docs/project/RELEASE_NOTES.md 'Unreleased'; '2.0.0' register line (492 findings, 1 open).",
    },
    {
        "kind": "table",
        "kicker": "Measured · a 1 GiB container, the image's defaults, JDK 25",
        "title": "What an unauthenticated client could do to the heap, before and after",
        "rows": [
            ["Attack", "Release 2.0.0", "After the fix"],
            ["pgwire sockets, each declaring a 16 MiB password message (PGPREAUTH-1)",
             "60 sockets: OutOfMemoryError, container exit 3",
             "60 and 300 sockets: heap ≤ 272 MiB, node UP"],
            ["Concurrent anonymous sign-ins with a 19 MB body (HTTPBODY-1)",
             "30 at once: OutOfMemoryError, container exit 3",
             "3 bursts of 30: heap ≤ 276 MiB, node UP"],
            ["Both, on a node with -Xmx1g and no exit on error",
             "100 sockets: heap 1,043,804 of 1,048,576 KiB, 42 OutOfMemoryErrors; 30 sign-ins: 38",
             "—"],
        ],
        "col_w": [2.3, 2.1, 2.0],
        "size": 14,
        "note": "Single replays, recorded with the findings: they bound the attack on this configuration "
        "and say nothing about throughput. TLS was not exercised; Npgsql was not run (no .NET here).",
        "source": "Source: docs/project/qa/FINDINGS.md PGPREAUTH-1 and HTTPBODY-1 status lines (replays: heap "
        "≤272 MiB, ≤276 MiB, node UP); docs/project/qa/logs/ADV-SURFACE.md (QI-012, QI-045 before the fix; "
        "-Xmx1g node figures; 'No .NET on this machine').",
    },
    {
        "kind": "flow",
        "kicker": "From finding to fix · 2026-10-01 to 10-02",
        "title": "Three waves, each fix with its reproduction switched on",
        "steps": [
            ("Two passes", "322 cases written first; every verdict logged with evidence"),
            ("Wave 1", "The ten high: answers, recovery, heap, credentials"),
            ("Wave 2", "The seventeen medium: casts, checksums, grouping, hops, DLQ"),
            ("Wave 3", "The nineteen low and the four design notes"),
            ("Register", "553 findings, 1 open; 3 more found while fixing, fixed"),
        ],
        "box_h": 1.95,
        "items": [
            ("Kept, not thrown away",
             "The engine round's reproductions stay in pravaha-it (opt-in, -Dpravaha.qa.adversarial=true) "
             "and the surface round's in tests/qa/adv_surface, against a running node."),
            ("Two narrower fixes, and why",
             "No per-record journal checksum: a 2.0.0 node would read it as a torn tail. MIN/MAX over "
             "retractions refused rather than supported: that needs per-slice value multisets."),
            ("The uncomfortable lesson",
             "A green reactor of 4,832 tests still held 46 defects. “None open” describes what has "
             "been looked for."),
        ],
        "size": 13,
        "source": "Source: docs/project/qa/SUMMARY.md 'Outcome (2026-10-02)'; docs/project/RELEASE_NOTES.md "
        "'Unreleased' (register line); docs/project/qa/FINDINGS.md (JOURNALMID-1 and MINRETRACT-1 status "
        "lines; FLIGHTPRINCIPAL-1, CLIDLQ-1, ERRORPRONE-1); docs/development/TESTING.md (4,832 tests).",
    },
    {
        "kind": "split",
        "kicker": "Release 2.0 · docs/operations/COMPATIBILITY.md",
        "title": "What 2.x keeps stable, and what may still move",
        "left": {
            "head": "Stable in 2.x",
            "items": [
                ("Java 25, and the language and APIs",
                 "JDK 25 the minimum through 2.x. The SQL dialect; the SDKs; every /api/v1 path and field "
                 "(an OpenAPI lock checks it); Flight verbs and columns; what pgwire clients send."),
                ("Operations",
                 "PRV codes and SQLSTATEs, never reused; CLI commands and exit codes; pravaha.* keys; "
                 "metric names; the /opt/pravaha layout."),
                ("State on disk",
                 "A 2.x node reads what any 1.x or 2.x wrote. Going back is not promised."),
            ],
            "size": 13.5,
        },
        "right": {
            "head": "Experimental, or not in 2.0",
            "items": [
                ("The assistant", "Works and is tested; may change in a minor release."),
                ("The catalogue beyond phases 1–2",
                 "Grants, namespaces, filters and masks are stable; lineage and contracts are not built."),
                ("Cluster mode (wave 11)",
                 "On hold; when it comes, a 2.x addition a single node need not adopt."),
                ("A patch may change an answer",
                 "Only where the old one was the defect, and the release notes say so."),
            ],
            "size": 13.5,
        },
        "source": "Source: docs/operations/COMPATIBILITY.md (opening: a patch changes nothing a client relies "
        "on except where the old behaviour was the defect; 2.0: what breaks; Stable in 2.x; Experimental in "
        "1.0 and 2.0; Clients and servers); docs/project/RELEASE_NOTES.md 'Unreleased' ('Answers change').",
    },
    {
        "kind": "split",
        "kicker": "Not built",
        "title": "One node, feature-complete; these are deferred, or not yet done",
        "left": {
            "head": "Deferred or dropped by decision",
            "items": [
                ("Multi-node execution — wave 11, on hold",
                 "Membership, fenced leases and rebalance are built as libraries and no node consumes "
                 "them; a node refuses PARTITIONED mode (PRV-9002)."),
                ("MFA and single sign-on",
                 "Dropped by the owner: users, passwords, API keys and sessions are what Pravaha keeps."),
                ("Not yet proven",
                 "The eight-lane scaling target; the manual WCAG 2.2 AA audit."),
            ],
            "size": 15,
        },
        "right": {
            "head": "First versions: not yet",
            "rows": [
                ["Piece", "Not yet"],
                ["mysql-cdc", "An initial snapshot, TLS"],
                ["iceberg-sink", "Object stores, catalogs, partitioned tables, schema evolution"],
                ["One reader per ordered source", "Delta and JDBC; a CDC binding has one consumer"],
                ["Queries on queries", "Windows, joins, MIN/MAX over a view; replacing a chain member"],
                ["Alerts", "Email, Teams and PagerDuty channels"],
            ],
            "col_w": [1.4, 2.6],
            "size": 13.5,
        },
        "source": "Source: README.md 'What is not built, or not finished' (deferred by decision; first "
        "versions table; not yet proven); docs/design/adr/039; docs/design/adr/052 status line; docs/design/adr/056 "
        "'Consequences'; docs/design/adr/057 status line; docs/project/RELEASE_NOTES.md '1.0.0' (MYC-2 GTID positions; "
        "IDXVIS-1 access paths visible; CDCREPL-2).",
    },
    {
        "kind": "bullets",
        "kicker": "Boundaries",
        "title": "Limits of a store, a format or a decision — more code would not remove them",
        "items": [
            ("MIN and MAX cannot be retracted incrementally",
             "Never pre-combined at a source, and over an input that deletes refused at registration "
             "(PRV-2076)."),
            ("End-to-end delivery is capped by the source",
             "Transactional sinks stage each checkpoint; a source that cannot rewind caps them."),
            ("No RocksDB (ADR-044); no lz4 (ADR-053)",
             "The memory-mapped tier is for surviving state larger than memory, not for capacity."),
            ("Formats say what they say",
             "A proto3 scalar without optional has no NULL; an upsert tombstone does not say which row "
             "it deletes; a TRUNCATE names no rows to retract."),
            ("A transaction over a live answer is READ COMMITTED",
             "Each read sees the last commit; the gateway says so rather than promise repeatable reads."),
        ],
        "source": "Source: README.md 'Boundaries: limits of a store, a format or a recorded decision'; "
        "docs/project/RELEASE_NOTES.md '1.0.0' (PGWIRE-TX-1: READ COMMITTED, REPEATABLE READ with a NOTICE), "
        "'Unreleased' (MINRETRACT-1: PRV-2076).",
    },
    {
        "kind": "table",
        "kicker": "Where to start",
        "title": "From a clone to a changing view, then the whole surface",
        "rows": [
            ["Read", "For"],
            ["docs/guides/QUICKSTART.md", "Clone to a running, changing view in about ten minutes"],
            ["docs/operations/RUNNING_IN_DOCKER.md", "Images, the compose stack and its profiles, /opt/pravaha"],
            ["docs/operations/COMPATIBILITY.md", "What 1.x keeps stable, and what is experimental"],
            ["docs/guides/CONCEPTS.md", "The eight ideas; most surprises are one of them working correctly"],
            ["docs/guides/tutorials/", "Four lessons: registering, following, replacing and debugging a query"],
            ["examples/case-studies/", "Thirteen worked systems to copy, each checked by the build"],
            ["docs/guides/CONTINUOUS_QUERIES.md", "Streams, sources, registration, reading, and every SQL "
             "construct that works or is refused"],
            ["docs/operations/OPERATIONS.md · SECURITY.md", "Sizing, lanes, recovery, what to watch; authentication, "
             "authorization, audit"],
            ["docs/design/adr/", "Every architectural decision, including the ones later reversed"],
        ],
        "col_w": [1.7, 3.6],
        "size": 15.5,
        "note": "Ask once. Answer always.",
        "source": "Source: README.md 'Try it' and 'Documentation'; docs/guides/CONCEPTS.md opening; "
        "examples/case-studies/README.md (docs/guides/tutorials/, four lessons); docs/operations/RUNNING_IN_DOCKER.md; "
        "docs/operations/COMPATIBILITY.md.",
    },
]

SLIDES = PART10 + PART11
