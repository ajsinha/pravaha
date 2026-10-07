# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The deck, as data. Act 4: evidence, by the numbers -- one idea a slide, a number in
every title -- and Act 5: where it is going, then thank you.

Performance figures appear only with the machine and the method they were taken with,
and a target that was not reached is shown as not reached.

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

EVIDENCE: list[dict[str, Any]] = [
    {
        "kind": "act",
        "num": "4",
        "title": "Evidence, by the numbers",
        "sub": "Measured, with the machine named; found, and fixed; promised, and tested.",
        "source": "Source: docs/project/gates/; docs/project/qa/FINDINGS.md; docs/development/TESTING.md; "
        "docs/operations/COMPATIBILITY.md.",
        "talk": "Claims are cheap. Each slide in this act carries one number, where it came from, and what it "
        "does not mean.",
    },
    {
        "kind": "code",
        "kicker": "Errors as a contract",
        "title": "291 error codes, every one documented",
        "code": [
            "$ pravaha validate --sql \"SELECT user_id,",
            "    COUNT(*) FROM txn GROUP BY user_id\"",
            "PRV-2050  GROUP BY user_id has no bound on its",
            "  key space, so its state grows with the number",
            "  of distinct keys and never shrinks. …",
            "  Bound it with a window -- GROUP BY",
            "  TUMBLE(event_time, INTERVAL '1' MINUTE), user_id --",
            "  so state is released when each window closes.",
            "Refusing now rather than exhausting memory later.",
            "(exit 1)",
        ],
        "code_w": 0.56,
        "rows": [
            ["Area", "Codes"],
            ["Configuration, API, client", "35"],
            ["SQL", "21"],
            ["Runtime and codegen", "14"],
            ["State and serving", "32"],
            ["Plugins", "82"],
            ["Flight and PostgreSQL gateways", "29"],
            ["Security and identity", "30"],
            ["Query registry", "41"],
            ["Cluster coordination", "7"],
        ],
        "col_w": [3.0, 0.9],
        "size": 13,
        "takeaway": "A code is never reused, and the troubleshooting table and the declared codes are held "
        "equal, both ways, by a test.",
        "source": "Source: docs/guides/TROUBLESHOOTING.md 'Every code' (291 rows, counted by range); "
        "pravaha-it/.../ErrorCodeUniquenessTest.java (one meaning per code, ranges by thousand); README.md "
        "'Documentation' (the code table must match the declared codes in both directions); "
        "pravaha-console/content/topics/cli-reference.md captured 'validate-refused' (wrapped, one sentence "
        "elided with …).",
        "talk": "Every refusal carries a code, and every code has a page: what it means and what to change. "
        "The panel is a real refusal, captured from a node: an unbounded GROUP BY refused at registration, "
        "with the fix in the message.",
    },
    {
        "kind": "table",
        "kicker": "Java 21 against Java 25 · the same jar · JMH, 2026-10-04",
        "title": "One jar, two JDKs: the path a node runs is level",
        "rows": [
            ["Profile A, batches of 512 rows", "JDK 21", "JDK 25", "JDK 25 baseline"],
            ["Generated (fused) — what a node runs", "630,864 ± 1 % (323 M rows/s)",
             "586,418 ± 10 % (300 M rows/s)", "618,952 ± 2 % (317 M rows/s)"],
            ["Interpreted", "149,966 ± 31 % (76.8 M rows/s)", "179,003 ± 1 % (91.6 M rows/s)",
             "177,940 ± 1 % (91.1 M rows/s)"],
            ["Memory access getLong / putLong, ns", "1.06 / 1.08", "1.29 / 1.37", "1.28 / 1.40"],
        ],
        "col_w": [1.9, 1.75, 1.75, 1.75],
        "size": 14,
        "note": "OpenJDK 21.0.12.1 and 25.0.4.1, back to back, on an AMD Ryzen AI 9 HX 370 laptop (12 cores, 24 "
        "threads, 60 GiB) at load 1.2–2.4. A regression detector, not a capability: the 31 % errors on 21's "
        "interpreted arm make its 16 % gap a reading, not a measurement.",
        "takeaway": "No regression this harness can see: generated is level, memory access level or faster "
        "on 21.",
        "source": "Source: docs/project/gates/measured-2026-10-04-jdk21/README.md (both tables, the machine, "
        "JDKs, load, verdict, 'What these numbers are not'); benchmarks/baselines/*-jdk21-2026-10.json.",
        "talk": "From 2.3, Pravaha runs on Java 21 or later, so one jar can be measured on both. The "
        "generated path — the one a node actually runs — is level. Every figure says the machine and the "
        "load it was taken at, and none of them is a capability claim.",
    },
    {
        "kind": "table",
        "kicker": "Gates P2 and P3 · JDK 21 from 2026-09-20, JDK 25 on 2026-10-04",
        "title": "Per-lane throughput: reached. Eight-lane scaling: not reached",
        "rows": [
            ["Gate", "Target", "Measured", "Verdict"],
            ["P2 · Profile A, per lane", "≥ 1.2 M rows/s",
             "JDK 25: all 30 passes above it, best 55–60 M. JDK 21: 29 of 30, the miss 1.04 M at load 77",
             "Reached"],
            ["P3 · Profile B, per lane", "≥ 350 k rows/s",
             "JDK 25: all 20 passes, best 2.30–2.73 M. JDK 21: all 15, worst 1.10 M", "Reached"],
            ["P2 · scaling, 1 → 8 lanes", "≥ 90 % of linear",
             "JDK 25: 32–37 %. JDK 21: 28–42 %. The machine's own ceiling for work sharing nothing: 50–55 %",
             "Not reached"],
            ["W5 · Nexmark against Flink", "Parity on ≥ 18 of 22",
             "Not run: no Flink, no quiet machine, no reference generator here", "Not reached"],
        ],
        "col_w": [1.6, 1.2, 3.4, 0.9],
        "size": 14,
        "note": "The requirement is about 1,000 rows a second (ADR-042); the design's figures are kept as the "
        "gate criteria so a reader can see the bar moved and judge why. A laptop's figure is a floor, never "
        "the engine's ceiling.",
        "source": "Source: docs/project/gates/measured-2026-10-04-jdk25/README.md (Gate P2: all thirty passes "
        "above, verdict REACHED; P3; scaling 32–37 %); docs/project/gates/measured-2026-09-20/README.md (JDK 21 "
        "figures; machineScalingReference 50–55 %; W5); docs/design/adr/042-the-throughput-bar-is-the-"
        "requirement.md; README.md 'Not yet proven'.",
        "talk": "Two gates reached, two not, and the slide says which. Scaling across eight lanes reaches about "
        "a third of linear on this laptop — and the laptop's own ceiling for perfectly independent work is "
        "about half, so better hardware is needed before anyone can call it.",
    },
    {
        "kind": "stats",
        "kicker": "Many queries on one node · ADR-036",
        "title": "200 queries add 24 threads; 1,000 queries run on 8 lanes",
        "stats": [
            ("+24 threads", "For 200 queries on 24 cores — where they once added 400"),
            ("~1 MiB", "Off-heap per idle query on a lane of its own"),
            ("8 lanes", "For 1,000 queries over one source: a row written 8 times, not 1,000"),
            ("64", "Queries that own a lane each, under auto, before new ones share"),
        ],
        "items": [
            ("Threads follow cores, not queries",
             "A fixed pool of one thread per core drives every lane; watermark and checkpoint clocks are one "
             "timer for the process."),
            ("Isolation while it is cheap, sharing once memory binds",
             "WITH (lane = 'dedicated') keeps one query apart; an administrator's rebalance moves shared "
             "queries onto their own lanes without losing an answer."),
            ("Each component reports its own bytes",
             "Per-query gauges show state approaching its ceiling before it refuses."),
        ],
        "size": 14,
        "takeaway": "The cost of a query follows the data it holds, not a thread it owns.",
        "source": "Source: README.md 'Many queries on one node' (200 queries add 24 platform threads on 24 "
        "cores; about 1 MiB off-heap per idle query; 1,000 queries over one source on 8 lanes, each row "
        "written 8 times), 'Lanes' (64, auto, dedicated, rebalance), 'State'; docs/design/adr/036-one-node-"
        "thousands-of-queries.md.",
        "talk": "The engine is built to hold many registered questions on one machine. Threads follow the "
        "cores, not the query count, and lane sharing means a thousand queries over one source write each "
        "row eight times, not a thousand.",
    },
    {
        "kind": "table",
        "kicker": "Nexmark coverage · NexmarkCoverageIT",
        "title": "12 of Nexmark's 23 queries run; what is missing is SQL, not speed",
        "rows": [
            ["Verdict", "Queries", "Why"],
            ["Runs", "q0, q1, q2, q3, q7, q8, q9, q18, q19, q20, q21, q22",
             "Up from 5, after exact DECIMAL, self joins, a maintained top-N, REGEXP_EXTRACT and SPLIT_INDEX"],
            ["Refused on purpose", "q4, q5, q15, q16, q17", "Unwindowed GROUP BY or COUNT(DISTINCT): unbounded "
             "state (PRV-2050)"],
            ["Refused", "q6 · q11", "An AVG over a row frame (PRV-2021) · session windows (PRV-2020)"],
            ["Cannot be written here", "q10, q12, q13, q14", "A partitioned file sink, PROCTIME(), no "
             "lookup source registered, a user-defined function"],
        ],
        "col_w": [1.3, 2.2, 3.0],
        "size": 14.5,
        "note": "The head-to-head against Flink has not been run; running it on a subset and publishing that "
        "would be selective benchmarking.",
        "takeaway": "Five of the eleven are refused by design: an unbounded query is the failure Pravaha "
        "exists to prevent.",
        "source": "Source: docs/project/gates/measured-2026-09-20/README.md 'Coverage re-measured on "
        "2026-09-26 — 12 of 23' and 'The head-to-head cannot be run here'; README.md 'Performance'.",
        "talk": "Nexmark is the standard streaming benchmark. Twelve of its queries run; five are refused "
        "deliberately because their state is unbounded; the rest need SQL that is not built yet.",
    },
    {
        "kind": "stats",
        "kicker": "Adversarial QA of 2.0.0 · 2026-10-01",
        "title": "322 cases written to break a promise, each naming the promise",
        "stats": [
            ("322", "Cases in two passes: engine, data and security; surfaces, operations, packaging"),
            ("66", "Failed; 244 passed; 12 blocked or not run, each with its reason"),
            ("46", "Defects — 10 high, 17 medium, 19 low — and 4 design notes"),
            ("0 open", "All 50 fixed in three waves by 2 October, reproductions switched on"),
        ],
        "items": [
            ("Method",
             "Cases written before execution, logged with evidence. A case passes when the engine does what "
             "its documentation says; a failure needs a reproduction."),
            ("Held, against an independent oracle",
             "420 seeded window runs, 40 seeds × 150 chained operations, 19 SIGKILLs at random points, 9,800 "
             "random predicates; masks, filters, tenants and grants held on every read path tried."),
            ("Broke, at the edges",
             "Empty groups and narrow integers, recovery bookkeeping, what a stranger could exhaust, and "
             "credentials."),
        ],
        "size": 13.5,
        "takeaway": "A green build of 4,832 tests still held 46 defects — so the round is kept as tests.",
        "source": "Source: docs/project/qa/SUMMARY.md 'Adversarial QA of 2.0.0' (table, outcome, what held); "
        "docs/project/qa/logs/ADV-ENGINE.md (differential volume; 19 SIGKILLs); docs/project/qa/cases/"
        "ADV-ENGINE.md (stance); docs/development/TESTING.md (the whole reactor on 25, 2026-10-01: 4,832 tests, 0 "
        "failures).",
        "talk": "After 2.0.0 shipped, a round of testing was written specifically to break its promises. It "
        "found forty-six defects in a release whose ordinary build was green. All fifty findings were fixed "
        "within a day, each with its reproduction kept as a test.",
    },
    {
        "kind": "table",
        "kicker": "Adversarial QA of 2.0.0 · the ten high-severity defects",
        "title": "Ten severe defects the round found — all ten fixed",
        "rows": [
            ["Finding", "What it did", "Fixed by"],
            ["ALLNULLAGG-1", "SUM/AVG/MIN/MAX of all-NULL groups published 0", "NULL, as SQL says"],
            ["NARROWINT-1", "INT, SMALLINT, TINYINT results published wrapped", "An overflow, named"],
            ["FINEHOP-1", "A 1 ms hop over a day wedged a lane and its stream", "PRV-3026 at registration"],
            ["JOURNALMID-1", "Mid-journal damage silently dropped later registrations",
             "PRV-8005: refuse the start"],
            ["SHAREDLOSS-1", "A shared computation's surviving name restarted empty", "Its home journalled"],
            ["CELLBYTES-1", "Embedded: a row over 512 bytes stopped every query", "Settings read; row refused"],
            ["PGPREAUTH-1", "60 silent PostgreSQL sockets ended a 1 GiB node", "16 KiB before sign-in; capped"],
            ["HTTPBODY-1", "30 anonymous 19 MB sign-ins did the same", "413 before the body is read"],
            ["PGREVOKE-1", "A revoked credential kept reading on PostgreSQL", "Re-verified every statement"],
            ["CDCSLOT-1", "A dropped CDC slot: RUNNING, UP, changes lost", "PRV-5117; health DEGRADED"],
        ],
        "col_w": [1.4, 3.1, 2.0],
        "size": 12.5,
        "source": "Source: docs/project/qa/SUMMARY.md HIGH list; docs/project/qa/FINDINGS.md status lines for "
        "each (FIXED).",
        "talk": "The ten worst, by name. Two families stand out: answers at the edge of the value domain, and "
        "what an unauthenticated stranger could make a node hold. Every one is fixed and has a test.",
    },
    {
        "kind": "stats",
        "kicker": "The findings register · docs/project/qa/FINDINGS.md",
        "title": "Zero open findings out of 582",
        "stats": [
            ("582", "Findings carrying a status, from every round and pass since the first"),
            ("563", "Fixed — and fixed means the case that found it was re-run"),
            ("19", "Closed otherwise: 10 by design, 9 superseded"),
            ("0 open", "0 GA-blocker, 0 GA-required, 0 post-GA"),
        ],
        "items": [
            ("Counted by the build",
             "FindingsRegisterTest counts the register with the pattern the header uses, so the number "
             "here and the number the build enforces are one number."),
            ("The commonest defect: built, correct, connected to nothing",
             "Fixed seven times under seven identifiers — a value not carried forward, a getter with no "
             "caller, a check applied to the wrong noun."),
            ("The latest pass, against Java 21",
             "ADV-JDK21 found the lock conversion sound; its seven findings were fixed in 2.4.0 and 2.4.1."),
        ],
        "size": 13.5,
        "takeaway": "“None open” describes what has been looked for — which is why the looking never stops.",
        "source": "Source: docs/project/qa/FINDINGS.md header (582 findings carrying a status — 563 FIXED, 0 "
        "OPEN, 10 BY DESIGN, 9 SUPERSEDED; 0 GA-BLOCKER, 0 GA-REQUIRED, 0 POST-GA; FindingsRegisterTest) and "
        "'The commonest defect in this register'; docs/project/RELEASE_NOTES.md '2.4.0' (ADV-JDK21: J21-1 to "
        "J21-3 fixed, four open) and '2.4.1' (J21-4 to J21-7 fixed).",
        "talk": "Every defect ever found is in one file, with what happened to it. Today none is open. The "
        "takeaway is the honest one: zero open describes what we have looked for so far.",
    },
    {
        "kind": "bullets",
        "kicker": "Compatibility · ADR-062",
        "title": "Java 21 → 25, one build",
        "items": [
            ("Every module is Java 21 class files",
             "The API and the Java SDKs included; any JDK from 21 builds and runs Pravaha, and CI runs the "
             "whole reactor on 21 and on 25."),
            ("A dependency built for a newer Java fails the build",
             "An enforcer rule holds every dependency to Java 21 bytecode; a package on JDK 25 was checked: "
             "42 jars, about 128,600 classes, all Java 21."),
            ("No carrier pinned on 21",
             "The locks virtual threads can block inside are ReentrantLocks; -Djdk.tracePinnedThreads=full "
             "reports no pinned park across the suites."),
            ("One image",
             "eclipse-temurin:21-jre, no per-Java tag; launchers refuse a JVM older than 21 by name."),
            ("One caveat, stated",
             "JDK 21's ZGC is not generational: a heap-heavy deployment on ZGC may prefer 25."),
        ],
        "size": 15,
        "takeaway": "One jar runs where production runs and where the newest JDK runs — and the build "
        "proves it.",
        "source": "Source: docs/project/RELEASE_NOTES.md '2.3.0' (Java 21 or later; CI 21/25 matrix; "
        "ReentrantLock; tracePinnedThreads; temurin:21-jre; ZGC caveat) and '2.4.0' (enforce-java-21-"
        "bytecode; 42 jars, about 128,600 classes); docs/design/adr/062-java-21-or-later.md; "
        "docs/operations/COMPATIBILITY.md 'Java 21 or later'.",
        "talk": "2.0 required Java 25; 2.3 brought the floor back to 21 so it runs where production runs. "
        "The build now guarantees it, down to every library inside the jar.",
    },
    {
        "kind": "table",
        "kicker": "How it is tested · docs/development/TESTING.md",
        "title": "5,112 tests in the full build, and the tiers around it",
        "rows": [
            ["Tier", "Needs", "Recorded"],
            ["The full build, after the QA's fix waves", "JDK 21 or later",
             "5,112 tests, 0 failures, 0 errors, 122 skipped (2026-10-02)"],
            ["In-process integration (pravaha-it)", "JDK 21 or later",
             "960 tests: real nodes, documentation checks, every case study against hand-worked answers"],
            ["Plugins against real stores", "Docker",
             "211 container tests across 6 plugins and 3 in pravaha-it, green"],
            ["Adversarial suites", "Opt-in; a node", "110 engine tests and 21 against a running node"],
            ["Python SDK · console", "Python · Chrome",
             "432 · 1,957 collected; the console's pass with zero axe violations in every theme"],
            ["SDKs on their own", "Docker, Maven", "Four clients outside the repository, green"],
        ],
        "col_w": [2.2, 1.2, 3.2],
        "size": 13.5,
        "note": "Performance gates refuse to report under a coverage agent. A green build without Docker has "
        "not tested any connector against its store.",
        "source": "Source: docs/development/TESTING.md 'Where the numbers come from' (5,112 tests, 0 failures, "
        "0 errors, 122 skipped; SDK 432 and console 1,957 collected on 2026-10-02) and 'The tiers at a glance' "
        "(960; 211 container tests across 6 plugins and 3 in pravaha-it; adversarial 110 and 21; four "
        "standalone clients; axe); docs/project/RELEASE_NOTES.md '1.0.0' (PERF-1).",
        "talk": "The number in the title is the full build. Around it: integration against real nodes, every "
        "connector against its real store in containers, the adversarial suites, and the SDKs built and run "
        "from outside the repository.",
    },
    {
        "kind": "split",
        "kicker": "The compatibility promise · docs/operations/COMPATIBILITY.md",
        "title": "What 2.x keeps stable, and what may still move",
        "left": {
            "head": "Stable in 2.x",
            "items": [
                ("Java 21 or later, and the APIs",
                 "The SQL dialect; the SDKs; every /api/v1 path and field (an OpenAPI lock checks it); "
                 "Flight verbs; what PostgreSQL clients send."),
                ("Operations",
                 "PRV codes, never reused; CLI commands and exit codes; pravaha.* keys; metric names; the "
                 "/opt/pravaha layout."),
                ("State on disk",
                 "A 2.x node reads what any 1.x or 2.x wrote."),
            ],
            "size": 14,
        },
        "right": {
            "head": "Experimental, or not yet",
            "items": [
                ("The assistant", "Works and is tested; may change in a minor release."),
                ("The catalogue beyond phases 1–2",
                 "Grants, filters and masks are stable; lineage and contracts are not built."),
                ("Cluster mode",
                 "On hold; when it comes, an addition a single node need not adopt."),
                ("A patch may change an answer",
                 "Only where the old one was the defect, and the release notes say so."),
            ],
            "size": 14,
        },
        "source": "Source: docs/operations/COMPATIBILITY.md (Stable in 2.x; Experimental; Java 21 or later; a "
        "patch changes an answer only where the old behaviour was the defect).",
        "talk": "The promise a user can plan around. What is stable is listed, what is experimental is "
        "labelled, and a patch only changes an answer when the old answer was wrong.",
    },
]

ROADMAP: list[dict[str, Any]] = [
    {
        "kind": "act",
        "num": "5",
        "title": "Where it is going",
        "sub": "Feature-complete for one node; what is left, in the order it matters.",
        "source": "Source: docs/development/REMAINING.md 'What is genuinely left (2026-10-02)'; README.md "
        "'Roadmap'.",
        "talk": "Finally, the road ahead — only what the repository's own plan says.",
    },
    {
        "kind": "table",
        "kicker": "Roadmap · docs/development/REMAINING.md",
        "title": "What is left — and what each item is",
        "rows": [
            ["Item", "Kind"],
            ["Cluster mode (wave 11): multi-node execution", "Deferred; membership, fenced leases "
             "and rebalance are built as libraries no node consumes yet (PRV-9002)"],
            ["The eight-lane scaling criterion", "Not reached on a laptop; needs reference hardware"],
            ["Nexmark: 11 of 23 queries; the head-to-head against Flink", "Missing SQL, not speed"],
            ["mysql-cdc initial snapshot and TLS; iceberg-sink object stores, catalogs, partitioning",
             "Buildable: first versions' follow-ups"],
            ["One reader per Delta or JDBC source; showing which access path a read took", "Buildable"],
            ["The catalogue's phases 3–4: lineage, contracts, sharing", "Only if wanted"],
            ["The manual WCAG 2.2 AA audit", "A person's task; the automated half is green"],
        ],
        "col_w": [3.1, 3.3],
        "size": 14,
        "takeaway": "Every gap the plan listed for one node is built or decided; the next step up is more "
        "than one node.",
        "source": "Source: docs/development/REMAINING.md 'What is genuinely left (2026-10-02)' (the table) and "
        "the 'Done, 2026-09-27' paragraph; README.md 'Roadmap' (wave 11 not started — on hold), 'What is not "
        "built, or not finished'; docs/design/adr/045-cluster-mode-assigns-queries-not-rows.md.",
        "talk": "Nothing here is a promise of a date. Cluster mode is designed and partly built as libraries, "
        "and on hold by decision. The rest are named follow-ups and one audit only a person can do.",
    },
    {
        "kind": "table",
        "kicker": "Where to start",
        "title": "From a clone to a changing view, then the whole surface",
        "rows": [
            ["Read", "For"],
            ["docs/guides/QUICKSTART.md", "Clone to a running, changing view in about ten minutes"],
            ["pravaha init", "A project that runs as generated, with a compose file"],
            ["docs/guides/CONCEPTS.md", "The ten ideas; most surprises are one of them working correctly"],
            ["docs/guides/tutorials/", "Registering, following, replacing and debugging a query"],
            ["examples/case-studies/", "Thirteen worked systems to copy, each checked by the build"],
            ["docs/operations/OPERATIONS.md · SECURITY.md", "Sizing, lanes, what to watch; identity, "
             "authorization, audit"],
            ["docs/publications/COMPETITIVE_LANDSCAPE.md", "Where Pravaha fits, and where something else wins"],
        ],
        "col_w": [2.4, 3.6],
        "size": 15,
        "source": "Source: README.md 'Try it' and 'Documentation'; docs/guides/CONCEPTS.md (sections 1–10); "
        "examples/case-studies/README.md (thirteen studies; CaseStudyRunTest); pravaha-console/content/topics/"
        "cli-reference.md 'Starting a project'.",
        "talk": "If this was interesting, here is the order to read in: the quick start, then the concepts, "
        "then a case study close to your own problem.",
    },
    {
        "kind": "thanks",
        "title": "Thank you",
        "sub": "Ask once. Answer always.",
        "lines": ["Ashutosh Sinha", "ajsinha@gmail.com", "Pravaha 2.4 · continuous SQL where your data already lives"],
        "source": "Source: brand/README.md (the slogan and line); README.md (author).",
        "talk": "Thank you. Questions — and if there is time, a live demo from pravaha init to a changing view.",
    },
]

SLIDES = EVIDENCE + ROADMAP
