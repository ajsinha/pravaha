"""
The deck, as data. Parts 4 and 5: correctness (exactly once, one checkpoint as
one cut, weights and retractions, late data, survival) and scale on one node
(lanes, sharing, dedicated lanes, rebalance, exact-seam readers, the equality
index).

One deck split across several modules only to keep each file short; read them in
order (see GUIDE.md).

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

PART4: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "4",
        "title": "Correctness",
        "sub": "Exactly-once state, output cut at the same point, a weight on every change, and "
        "late data applied as a correction rather than dropped or double-counted — with each "
        "guarantee stated as narrowly as the engine can keep it.",
        "points": [
            "One checkpoint is one cut (ADR-008)",
            "Output cut at the same marker",
            "Recovery: what a restart gives back",
            "Weights and retractions",
            "Late data: two settings, two meanings",
            "Survival, and state that degrades",
            "A bound that changes the answer",
            "Answers SQL defines, and recovery that refuses aloud (2.0.1)",
        ],
        "source": "Source: docs/design/adr/008-aligned-checkpoints.md; README.md 'Corrections', 'Recovery', "
        "'Survival', 'How it is built'; docs/guides/CONCEPTS.md §4, §7.",
    },
    {
        "kind": "cards",
        "kicker": "Exactly-once state · ADR-008",
        "title": "A checkpoint is one cut across every input, not a snapshot per lane",
        "intro": "Three separate defects each broke the promise before they were fixed. The fix for "
        "each is what the guarantee now rests on.",
        "cols": 3,
        "cards": [
            ("1 · HOLD", "Every source is held between rows for the cut",
             "Inside one freeze the engine reads every source's offset and hands every lane its "
             "marker, and only then waits. The offsets and the state name the same rows. Before, "
             "offsets were read after the snapshots and a restore skipped rows."),
            ("2 · EXACT", "A marker is honoured exactly, not approximately",
             "A lane cuts its batch at the marker, so a snapshot holds the state at the position "
             "it names — not a batch's worth beyond it, which the recorded offset would replay."),
            ("3 · EVERY", "Every source's offset is recorded",
             "Including a shuffling one: the pumps feeding a multi-lane query were missing from "
             "the offsets map, so such a checkpoint could not be rewound to at all."),
        ],
        "note": "AlignedCheckpointBarrierTest and ControlTaskBarrierTest hold all three, taking "
        "checkpoints with the sources still running.",
        "source": "Source: docs/design/adr/008-aligned-checkpoints.md 'Implementation status — as of "
        "2026-09-14' (W8-2, W8-3, W8-4; IngestPump.pumpOnce, QueryExecution.checkpoint, "
        "freezeIngest; the two named tests).",
    },
    {
        "kind": "table",
        "kicker": "Output · ADR-008 as amended, ADR-043",
        "title": "Output is cut at the same marker, and its guarantee is stated per sink",
        "intro": "A registered query's view is committed and snapshotted on the lane at the "
        "marker. What a sink then gets depends on what the sink can do, and is said at "
        "registration.",
        "rows": [
            ["Sink kind", "Shipped examples", "Delivery", "How"],
            ["Transactional", "jdbc-sink, kafka-sink, delta-sink, iceberg-sink", "Exactly once",
             "Prepared at the checkpoint's cut; committed once the checkpoint is durable"],
            ["Idempotent upsert", "aerospike-sink", "Effectively once",
             "A repeated upsert rewrites the values already there"],
            ["Plain append", "filesystem", "At least once", "A restart may repeat what it appended"],
        ],
        "col_w": [1.1, 1.7, 1.0, 2.4],
        "size": 15.5,
        "note": "End to end is still capped by the source: anything read from Aerospike is at "
        "least once, whatever the engine does (ADR-029), and a source that cannot rewind caps "
        "every sink behind it.",
        "source": "Source: docs/design/adr/008-aligned-checkpoints.md ('Output is cut at the same marker "
        "(amended 2026-09-19, ADR-043 \"As built\")', ADR-029 cap); README.md 'Sinks' and "
        "'Boundaries' (transactional sinks; capped by whether the source can rewind); "
        "docs/operations/OPERATIONS.md sink delivery table.",
    },
    {
        "kind": "bullets",
        "kicker": "Recovery",
        "title": "What a restart gives back, and what it refuses to",
        "items": [
            ("Registrations come back from a journal",
             "Name, SQL, key, owner, retention and bound values: small, rarely changed, and "
             "impossible to recompute. A journal that cannot be written refuses the registration."),
            ("State comes back only if checkpointing is on",
             "With pravaha.checkpoint.directory set, state, offsets and the view resume from the "
             "newest checkpoint (the newest 3 are kept). Unset is the default, and the node says so "
             "at startup; a restart then costs a warm-up, not an outage."),
            ("Owners are re-checked on replay",
             "A principal who has lost access does not get the query back; recovery names it."),
            ("Rows in flight between lanes are refused, not dropped",
             "The exchange between lanes is not cut. No compiled pipeline sends on it, and a "
             "checkpoint that would have to cut it is refused rather than stored wrong."),
        ],
        "source": "Source: docs/operations/OPERATIONS.md 'Restarts: what survives' and 'Checkpoints: what is "
        "actually true' (PRV-8006; pravaha.checkpoint.keep default 3); docs/design/adr/008 ('The exchange is "
        "not cut', QueryExecution.refuseWhileRowsCrossTheExchange).",
    },
    {
        "kind": "bullets",
        "kicker": "Weights and retractions",
        "title": "Every change carries a weight, from the engine to the SDK",
        "items": [
            ("One arithmetic for everything",
             "A correction is a −1 for the old row and a +1 for the new, not a special message "
             "type every consumer must recognise. That is the Z-set idea, and why an aggregate is "
             "maintained incrementally without a separate retract path to get wrong."),
            ("Carried the whole way",
             "Through the engine, across the wire and into both SDKs. A sink receives retractions "
             "too: a tombstone in kafka-sink, an equality delete in iceberg-sink."),
            ("Present while the weights sum positive",
             "A net weight of zero means the row is not there, so a change of weight zero is "
             "never delivered to a subscriber (STRM-1)."),
            ("A consumer that ignores weights drifts",
             "Overwrite by key if only current values matter; apply the weights if you keep your "
             "own total."),
        ],
        "source": "Source: docs/guides/CONCEPTS.md §4; README.md 'Corrections' and 'Sinks' (kafka-sink "
        "tombstone, iceberg-sink equality deletes).",
    },
    {
        "kind": "split",
        "kicker": "Late data",
        "title": "Two settings, two meanings: waiting and correcting",
        "intro": "Both are declared per stream, because a topic fed by phones over a flaky network "
        "and a scan of data at rest have nothing in common.",
        "left": {
            "head": "Out-of-orderness: how long to wait",
            "items": [
                ("Decides when a window is complete",
                 "10 seconds unless the stream says otherwise; the watermark trails the data by it."),
                ("A join is as current as its laggiest input",
                 "It takes the minimum of its streams' watermarks."),
                ("Unitless is seconds",
                 "out-of-orderness: 60 is a minute; it once bound as milliseconds (TIME-3)."),
            ],
        },
        "right": {
            "head": "Allowed lateness: whether to correct",
            "items": [
                ("Zero by default",
                 "A row arriving after its window closed is counted as late and dropped."),
                ("Declared, a late row is a correction",
                 "pravaha.streams.<name>.allowed-lateness reopens the window: a retraction of the "
                 "published answer and the corrected one, in one commit."),
                ("Published when event time next moves",
                 "Not the instant the late row is read."),
            ],
        },
        "source": "Source: docs/guides/CONCEPTS.md §3 (lateness belongs to the source; 10-second default; "
        "allowed lateness defaults to zero); docs/guides/CONTINUOUS_QUERIES.md §2 (TIME-3; join takes the "
        "minimum); examples/case-studies/manufacturing-sensor-anomalies/README.md step 5 "
        "('A correction is emitted when event time next moves').",
    },
    {
        "kind": "bullets",
        "kicker": "Survival on one node",
        "title": "Failures that end one thing, loudly, instead of everything, quietly",
        "items": [
            ("A node claims the directories it writes",
             "Two nodes cannot silently share state (PRV-4003). A standby takes over when the claim "
             "goes stale and reports what the takeover cost."),
            ("Bad rows go to a dead-letter directory",
             "Undecodable input, and a row whose WHERE or projection fails (PRV-3027), instead of "
             "ending the query."),
            ("State past its ceiling can spill instead of stopping",
             "With pravaha.state.spill.* set, state beyond its memory ceiling moves to memory-mapped "
             "files and the query slows. There is no RocksDB, by decision (ADR-044)."),
            ("A ceiling you can see coming",
             "Each query reports state held against its ceiling: alert on "
             "pravaha_query_state_fraction before PRV-4001 refuses it."),
            ("A lane fails alone",
             "A throwing pipeline drops its lane; sibling lanes never learn of it."),
        ],
        "source": "Source: README.md 'Survival', 'State', 'Boundaries' (ADR-044); docs/guides/CONCEPTS.md §7 "
        "(state_held, _ceiling, _fraction; PRV-4001); docs/design/EXECUTION_MODEL.md §2 'A lane fails alone'; "
        "docs/project/RELEASE_NOTES.md 'Unreleased' (DLQPROJ-1: PRV-3027).",
    },
    {
        "kind": "table",
        "kicker": "Bounds",
        "title": "A bound that changes the answer belongs in the query",
        "intro": "A bound that protects the machine belongs in configuration, and fails rather than "
        "quietly altering results.",
        "rows": [
            ["Holder", "Bounded by", "On exceeding"],
            ["Windowed aggregate state", "The window — it closes and releases", "—"],
            ["Unwindowed keyed aggregate", "Nothing", "Refused at planning (PRV-2050)"],
            ["Stream-to-stream join state", "The match window (1 h event time by default)",
             "Rows released as the watermark passes"],
            ["…and as a backstop", "maxRowsPerSide", "Fails (PRV-3021) — never evicts"],
            ["A served view", "Retention: forever unless RETAIN FOR says", "Oldest rows forgotten"],
            ["…and as a backstop", "maxKeys", "Fails (PRV-4022)"],
            ["Subscriber buffers", "The subscriber's options", "Conflate, drop or fail — counted"],
        ],
        "col_w": [1.5, 2.0, 1.9],
        "size": 15,
        "note": "A view forgets because retention is a cache policy; a join fails because evicting "
        "to fit would silently lose matches the query asked for.",
        "source": "Source: docs/operations/OPERATIONS.md 'What holds memory, and what bounds it' (rows "
        "abridged); docs/guides/CONCEPTS.md §7.",
    },
    {
        "kind": "table",
        "kicker": "Semantics fixed in 2.0.1 · the adversarial round",
        "title": "Answers now say what SQL says — and some answers change",
        "rows": [
            ["Case", "2.0.0 published", "Now"],
            ["SUM, AVG, MIN, MAX of a group with only NULLs", "0 — a real total of zero, to a reader",
             "NULL; COUNT(col) is 0; a retraction back to all-NULL is NULL again (ALLNULLAGG-1)"],
            ["INT, SMALLINT, TINYINT out of range", "2e9 * 2 as −294,967,296; WHERE i * 2 > 0 kept the row",
             "An overflow, as BIGINT's is; filter and projection agree (NARROWINT-1)"],
            ["GROUP BY a DOUBLE holding −0.0, 0.0, NaNs", "Five rows in, counts summing to four",
             "Either zero is one group, every NaN one (NANGROUP-1)"],
            ["HOP(10 s slide, 25 s size), a row at 12 s", "Windows [−5, 20) and [5, 30)",
             "[−10, 15), [0, 25), [10, 35) — as Calcite and Flink (HOPALIGN-1)"],
            ["HOP(1 ms, 1 day)", "Accepted; one row wedged its lane and stream",
             "Refused at registration, PRV-3026 (FINEHOP-1)"],
            ["MIN or MAX over a source that deletes", "Accepted; stopped on the first retraction",
             "Refused at registration, PRV-2076 (MINRETRACT-1)"],
        ],
        "col_w": [1.7, 1.9, 2.6],
        "size": 13,
        "note": "A tumbling window, and a hop whose size is a multiple of its slide, keep exactly the "
        "windows they had. The release notes mark every answer that changes and what it does to an "
        "older checkpoint.",
        "source": "Source: docs/project/RELEASE_NOTES.md 'Unreleased' (ALLNULLAGG-1, NARROWINT-1, NANGROUP-1, "
        "HOPALIGN-1, FINEHOP-1, MINRETRACT-1; 'Answers change'); docs/project/qa/FINDINGS.md status lines; "
        "docs/project/qa/logs/ADV-ENGINE.md (QE-001–006, 045–048, 065, 159, 163).",
    },
    {
        "kind": "table",
        "kicker": "Recovery · fixed in 2.0.1",
        "title": "What a restart is restored from is checked, and a refusal is visible",
        "rows": [
            ["Was", "Now"],
            ["A flipped bit in a checkpoint was restored and published: 8–10 of 16 flips (CKPTSUM-1)",
             "A CRC32C ends every checkpoint; a mismatch is skipped (PRV-4094) and the one before restored"],
            ["A checkpoint of another output schema was restored into the view (RETYPERESTORE-1)",
             "Not restored (PRV-4095); the query rebuilds from its sources"],
            ["One damaged length mid-journal dropped every later registration, at every start "
             "(JOURNALMID-1)", "The start is refused, PRV-8005, naming both byte offsets"],
            ["A shared computation's surviving name came back empty (SHAREDLOSS-1)",
             "Its checkpoint home is journalled with the drop"],
            ["A registration refused at recovery vanished; health UP (RECOVERYHEALTH-1)",
             "Listed FAILED with its code; health DEGRADED"],
            ["A dropped CDC slot or deleted topic: RUNNING, UP, changes lost (CDCSLOT-1, TOPICGONE-1)",
             "The feed stops, PRV-5117 or PRV-5130; health DEGRADED"],
        ],
        "col_w": [3.2, 3.0],
        "size": 13,
        "note": "19 SIGKILLs at random points lost and doubled nothing: the cut held. What it was restored "
        "from, and whether anybody was told, did not.",
        "source": "Source: docs/project/RELEASE_NOTES.md 'Unreleased' (CKPTSUM-1, RETYPERESTORE-1, JOURNALMID-1, "
        "SHAREDLOSS-1, RECOVERYHEALTH-1, CDCSLOT-1, TOPICGONE-1); docs/project/qa/SUMMARY.md 'What held' "
        "(19 SIGKILLs).",
    },
]

PART5: list[dict[str, Any]] = [
    {
        "kind": "divider",
        "num": "5",
        "title": "Scale on one node",
        "sub": "Thousands of continuous queries on one node, at a thread and memory cost that stops "
        "following the query count — and an operator who can see, and change, where each one runs.",
        "points": [
            "What a lane is, and why it has no locks",
            "Threads follow cores, not queries",
            "Sharing lanes: the auto mode",
            "One row written once per lane",
            "Dedicated lanes and admin rebalance",
            "One reader at an exact seam (ADR-054)",
            "An equality index (ADR-055)",
        ],
        "source": "Source: README.md 'Project status', 'Many queries on one node'; "
        "docs/design/EXECUTION_MODEL.md; docs/operations/OPERATIONS.md 'Sizing a node for many queries'.",
    },
    {
        "kind": "cards",
        "kicker": "The execution model",
        "title": "One thread, one inbox, one arena, one processor, one loop",
        "cols": 3,
        "cards": [
            ("THE LANE", "Everything it touches, it owns",
             "An off-heap inbox ring many producers write and one lane reads; off-heap arena slabs "
             "rewound once per batch; its own compiled pipeline. Rows are flyweights over bytes in a "
             "cell, not copies."),
            ("NO LOCKS", "Confinement is the correctness model",
             "No synchronized, no concurrent map, no CAS on a shared aggregate in the steady state. "
             "A key hashes to one of 1024 virtual partitions, and a partition belongs to one lane."),
            ("ONE THREAD AT A TIME", "Not one thread per lane",
             "A fixed pool of one thread per core steps many lanes in turn, the event-loop shape. A "
             "lane stays on its runner thread, so every ordering rule still holds."),
        ],
        "note": "A full inbox backpressures the source rather than spilling; a row wider than a "
        "cell is refused at ingest (PRV-3001) rather than buffered.",
        "source": "Source: docs/design/EXECUTION_MODEL.md §1 'A lane', §2 'Why there are no locks', §3 "
        "'Lane vs thread'; docs/operations/OPERATIONS.md 'Sizing a node for many queries' (PRV-3001).",
    },
    {
        "kind": "stats",
        "kicker": "Measured on the development machine, JDK 25, 2026-10-04",
        "title": "Threads follow cores, not queries",
        "stats": [
            ("24 threads", "Added by 200 queries on 24 cores — where they once added 400"),
            ("~1 MiB", "Off-heap per idle query on a lane of its own; 1.3 MiB active"),
            ("2.6 ms", "To register each of a thousand distinct continuous queries (3.7 ms on JDK 21)"),
            ("62 MiB", "Off-heap for those thousand, at the advised inbox sizing"),
        ],
        "items": [
            ("The per-query megabyte is the inbox",
             "2,048 cells × 512 bytes = 1,024 KiB. Sized to 256 × 256 for a slow source, it is 64 "
             "KiB — about 64 MB for a thousand queries rather than a gigabyte."),
            ("No per-query platform thread is left",
             "Lanes share the runner pool; periodic work shares one process-wide clock; the feed loop "
             "and Flight's call executor are virtual; Aerospike shares one client per cluster per "
             "credential."),
        ],
        "size": 16,
        "source": "Source: README.md 'Many queries on one node' and 'Performance' (200 queries, 24 "
        "threads; 1 MiB idle; 3.7 ms and 61 MiB on JDK 21, NodeScaleTest and SourceScaleTest); "
        "docs/project/gates/measured-2026-10-04-jdk25/README.md (ThousandQueryTest on JDK 25: 2.6 ms, 62 MiB); "
        "docs/operations/OPERATIONS.md 'Sizing a node for many queries' (1,024 KiB, 1,328 KiB, 64 KiB) and "
        "'Sharing lanes between queries' (SRC-2).",
    },
    {
        "kind": "split",
        "kicker": "Sharing lanes · the default is auto",
        "title": "Isolation while it is cheap, sharing once memory binds",
        "left": {
            "head": "pravaha.lane.multiplex.*",
            "rows": [
                ["Key", "Default", "Decides"],
                ["enabled", "auto", "auto shares after auto-from; true always; false never"],
                ["auto-from", "64", "Queries that own a lane before sharing starts"],
                ["lanes", "0", "Shared lanes; 0 is one per processor"],
                ["max-queries-per-lane", "300", "The ceiling on one shared lane"],
            ],
            "col_w": [1.5, 0.7, 2.2],
            "size": 14.5,
        },
        "right": {
            "head": "How a registration is placed",
            "items": [
                ("Least loaded shared lane wins",
                 "By query count, below the ceiling. What it reads does not matter — joins too."),
                ("Nowhere to fit is not a refusal",
                 "It gets a lane of its own, visible as pravaha_lane_own_queries."),
                ("A shared lane shares its fate",
                 "A throwing pipeline takes down every query on the lane — the reason the first 64 "
                 "own one."),
                ("Running queries are never moved",
                 "A restart re-places every query in journal order."),
            ],
            "size": 15.5,
        },
        "source": "Source: docs/operations/OPERATIONS.md 'Sizing a node for many queries' (settings table) and "
        "'Sharing lanes between queries'; docs/project/RELEASE_NOTES.md '1.0.0' ('Lane sharing is on by "
        "default, as auto').",
    },
    {
        "kind": "stats",
        "kicker": "Sharing what is read",
        "title": "A row is written once per lane, not once per query",
        "stats": [
            ("8 copies", "Of each row, for 1,000 queries over one source on 8 shared lanes"),
            ("1,000 copies", "The same queries on lanes of their own"),
            ("8 MiB", "Of inboxes at the defaults, shared — against about 1 GB"),
        ],
        "items": [
            ("One shared reader writes each row into a shared lane once",
             "Every query on that lane reading it is handed the one copy (LANE-2); what a query is "
             "fed alone travels on its own route and reaches no other."),
            ("A slow neighbour drops nothing",
             "It slows the lane's drain for every query on it; a full inbox holds everything "
             "feeding the lane, and a lane that frees no cell for 30 seconds fails the feed with "
             "PRV-3002 rather than hanging it."),
            ("Identical questions are one computation",
             "Within a tenant, matched on the normalised plan: ten desks asking the same thing cost "
             "one read of the source."),
        ],
        "size": 16,
        "source": "Source: docs/operations/OPERATIONS.md 'Sharing lanes between queries' (SharedLaneDensityTest; "
        "LANE-2; PRV-3002); README.md 'Many queries on one node', 'Continuous queries'.",
    },
    {
        "kind": "code",
        "kicker": "Dedicated lanes",
        "title": "Keeping one query on a lane of its own",
        "code": [
            "-- a lane of its own, whatever the node's mode",
            "CREATE CONTINUOUS QUERY <name>",
            "    WITH (lane = 'dedicated') AS <SQL>",
            "",
            "-- move a running query: a lossless cutover",
            "CREATE OR REPLACE CONTINUOUS QUERY <name>",
            "    WITH (lane = 'dedicated') AS <the same SQL>",
            "",
            "-- and back, under the node's mode",
            "CREATE OR REPLACE CONTINUOUS QUERY <name>",
            "    WITH (lane = 'shared') AS <the same SQL>",
        ],
        "code_w": 0.55,
        "items": [
            ("When isolation is worth an inbox",
             "'shared', the default, follows the node's mode; anything else is PRV-8017."),
            ("It survives a restart",
             "Journalled as an L record beside the registration; an older build refuses the "
             "journal by name rather than replaying the query onto a shared lane."),
            ("Where each query runs is visible",
             "GET /api/v1/queries/{name} reports lane — dedicated, shared or own — and sharedLane."),
            ("One refusal",
             "A dedicated registration that would join a computation already on a shared lane: "
             "PRV-8017."),
        ],
        "size": 16,
        "source": "Source: docs/operations/OPERATIONS.md 'Keeping one query on its own lane' (statements quoted, "
        "names elided); docs/project/RELEASE_NOTES.md '1.0.0' (WITH (lane = 'dedicated'), the L record, "
        "PRV-8017, GET /api/v1/queries lane and sharedLane); commit fba15e14.",
    },
    {
        "kind": "flow",
        "kicker": "Admin rebalance",
        "title": "Rebalancing is an administrator's action, never automatic",
        "steps": [
            ("Preview", "The plan first: dryRun=true, or pravaha lanes rebalance without --yes"),
            ("Oldest first", "Room under auto-from goes to queries on shared lanes, one at a time"),
            ("Blue/green move", "SQL unchanged, lane = 'own': a lane of its own, not pinned"),
            ("Release", "The old version is released once the new one has the name"),
        ],
        "box_h": 1.95,
        "items": [
            ("Admin role only; the engine checks it either way",
             "Console Admin → Lanes shows every query's lane, the mode and each shared lane's fill; "
             "pravaha lanes and GET|POST /api/v1/lanes/rebalance do the same from a shell."),
            ("It skips what it cannot move safely",
             "Queries answering to several names or already being replaced are skipped; a move a "
             "replacement refuses is reported and the rest go on."),
        ],
        "size": 16,
        "source": "Source: docs/operations/OPERATIONS.md 'An administrator rebalances by hand'; commits 14914ae0, "
        "6869419b, 86dd3e19; docs/project/RELEASE_NOTES.md '1.0.0'.",
    },
    {
        "kind": "table",
        "kicker": "One reader, exactly once · ADR-054",
        "title": "An ordered source is shared by meeting at an exact seam",
        "intro": "A source that declares ordered positions and can stop reading before a position "
        "is read once for every query on it. Each member is in one of three states:",
        "rows": [
            ["State", "Receives", "Its checkpoint's offset"],
            ["Attached", "Every record the shared reader reads, through the fan-out",
             "The shared reader's position"],
            ["Catching up (joined behind)", "Only its private catch-up, read with pollBefore(shared "
             "position)", "The catch-up's position"],
            ["Waiting (joined ahead)", "Nothing, until the shared reader reaches its position",
             "Its own position"],
        ],
        "col_w": [1.3, 2.4, 1.5],
        "size": 15,
        "note": "Built for Kafka and files read once through: a thousand queries over one topic read "
        "it once. Delta and JDBC follow when their order is shown total; CDC keeps a reader per "
        "query, because a slot can only be confirmed as far as its slowest member.",
        "source": "Source: docs/design/adr/054-an-ordered-source-is-shared-at-an-exact-seam.md (Decision, "
        "table verbatim, 'Which sources'); README.md 'What is not built' first-versions table; "
        "docs/project/RELEASE_NOTES.md 0.2.0.",
    },
    {
        "kind": "split",
        "kicker": "Equality index · ADR-055",
        "title": "An index over a column outside the key that cannot disagree with the view",
        "left": {
            "head": "What it does",
            "items": [
                ("INDEX (column), or WITH (index = 'column')",
                 "WHERE column = literal, or IN (…), probes once per value instead of walking the "
                 "view — over Flight SQL, REST and pgwire alike."),
                ("Allocated by the declaration, and written down",
                 "Built before the registration is acknowledged; journalled as an X record; carried "
                 "to a replacement by column name."),
                ("Bounded",
                 "At most four per view, on the heap, one entry per visible row."),
            ],
            "size": 15.5,
        },
        "right": {
            "head": "Why it stays right",
            "items": [
                ("Filed under the row the view held",
                 "The entry to delete is found from the view's own previous row, never from the "
                 "retraction — so a retraction carrying a wrong value cannot mislead it."),
                ("Tested against the obvious bug",
                 "Seeded with removal under the incoming row's value, both index tests fail."),
                ("Refused where equality lies",
                 "FLOAT, DECIMAL, BYTES and the whole key: PRV-2074."),
            ],
            "size": 15.5,
        },
        "source": "Source: docs/design/adr/055-an-equality-index-over-a-column-outside-the-key.md §1–§4 "
        "(ServedView.commit, SecondaryIndexTest, SecondaryIndexRegistryTest, MAX_EQUALITY_INDEXES, "
        "PRV-2074); docs/project/RELEASE_NOTES.md '1.0.0' (IDXVIS-1: access paths visible).",
    },
]

SLIDES = PART4 + PART5
