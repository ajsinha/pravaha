# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see LICENSE at the repository root.
"""
The deck, as data. Act 2: Pravaha 2.4, the product.

One slide of hard facts; what makes it different; three ways to run it; the console
screen by screen, from the documentation's real screenshots; the command line with
output captured from a real node; the connectors; the SDKs.

Project Pravaha -- Ask once. Answer always.
Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See LICENSE at the repository root.
"""

from __future__ import annotations

from typing import Any

SHOTS = "docs/assets/screenshots/"
SHOT_SOURCE = (
    "docs/design/architecture/console-screens.md (the screens and their captions); the images are "
    "docs/assets/screenshots/*.png, made by pravaha-console/tools/docs_screenshots.py with the real "
    "console in real Chrome over the fake engine the product tests use, so names and numbers are the "
    "fake's (big_txn, hot)"
)

SLIDES: list[dict[str, Any]] = [
    {
        "kind": "act",
        "num": "2",
        "title": "Pravaha 2.4: the product",
        "sub": "One engine, three ways to run it, a console, a command line, connectors and SDKs.",
        "source": "Source: docs/project/RELEASE_NOTES.md '2.4.0 — 2026-10-05', '2.4.1 — 2026-10-06'.",
        "talk": "Now the product itself, as it stands in the 2.4 line.",
    },
    {
        "kind": "stats",
        "kicker": "Release 2.4 · one node · Java 21 or later",
        "title": "Pravaha 2.4 — Overview",
        "stats": [
            ("26 modules", "In the reactor's build order, each described and checked against pom.xml"),
            ("10 connectors", "Plugins under plugins/: nine sources, six sinks, two lookups"),
            ("291 codes", "PRV error codes, every one in the troubleshooting table"),
            ("5,112 tests", "Full build after the 2.0 QA's three fix waves: 0 failures"),
        ],
        "items": [
            ("SQL",
             "Calcite parses and plans; Pravaha's own operators run over off-heap rows, with whole-stage "
             "code generation. Tumbling and hopping windows, stream-stream and temporal lookup joins, "
             "aggregates, top-N; 12 of Nexmark's 23 queries run."),
            ("Reached by",
             "Arrow Flight SQL and the PostgreSQL wire protocol for rows; REST under /api/v1 for "
             "everything else; Java and Python SDKs; a console; a CLI; or in process."),
            ("Kept honest",
             "582 findings recorded, 563 fixed, 0 open. One node: a node refuses PARTITIONED mode "
             "(PRV-9002) rather than pretend to be a cluster."),
        ],
        "size": 14.5,
        "source": "Source: README.md 'Building' (module list, checked by DocumentationFreshnessTest; plugin "
        "list), 'What works' (SQL, Serving), 'What is not built' (PRV-9002); docs/guides/TROUBLESHOOTING.md "
        "'Every code' (291 rows, held to the declared codes by a test); docs/development/TESTING.md 'Where the "
        "numbers come from' (5,112 tests, 0 failures, 0 errors, 122 skipped); docs/project/gates/"
        "measured-2026-09-20/README.md (Nexmark 12 of 23); docs/project/qa/FINDINGS.md header (582, 563, 0).",
        "talk": "Four numbers, each with a file behind it. Twenty-six modules, ten connector plugins, 291 "
        "error codes each documented, and the full build's 5,112 tests. Below them: what SQL runs, how you "
        "reach it, and the register — every finding ever recorded, and none open.",
    },
    {
        "kind": "bullets",
        "kicker": "Why Pravaha",
        "title": "Why Pravaha — what makes this different",
        "items": [
            ("It serves its own results.",
             "The maintained view is read by key over Flight SQL or the PostgreSQL protocol — no "
             "second database to keep in step."),
            ("It refuses unbounded state at plan time.",
             "GROUP BY with no window, a session window, a sink that cannot take a retraction: refused "
             "with a code at registration, not discovered weeks later."),
            ("It changes a running query without a gap.",
             "Blue/green replacement replays and swaps at the exact position the running version "
             "reached; a rollback is one swap."),
            ("It governs the live answer.",
             "Grants, row filters and masks apply to every read, subscription and chained query; a "
             "revoke ends an open stream."),
            ("It reads stores where they are.",
             "Filters pushed into JDBC, Aerospike and Cassandra; change data capture on PostgreSQL's and "
             "MySQL's own protocols, without Debezium."),
            ("It debugs an incident by replaying it.",
             "Fork a query from a checkpoint, step it row by row, and export the session as a JUnit "
             "test that compiles and passes."),
        ],
        "size": 15,
        "source": "Source: docs/publications/COMPETITIVE_LANDSCAPE.md 'Where Pravaha shines, and how' (Serving "
        "its own results; Refusing unbounded state at plan time; Blue/green replacement with backfill; A "
        "governed catalogue of live answers; Store-native pushdown; Native change data capture; Time-travel "
        "debugging); README.md 'What works'; docs/design/adr/041, 046, 048, 059.",
        "talk": "Six claims, each one a row the competitive landscape scores Yes for Pravaha and holds to a "
        "decision record or test. If an audience asks why not Flink or a streaming database, these six "
        "are the answer — and the landscape is equally plain about where those win.",
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
             "Flight SQL, the PostgreSQL gateway, REST and /status. A non-root image on "
             "eclipse-temurin:21-jre, a compose stack, and a Helm chart for one node."),
        ],
        "note": "One directory, /opt/pravaha — conf, secrets, plugins, data, logs — the same layout in a "
        "container and in an unpacked distribution. The engine core contains no Spring, enforced by the "
        "build (ADR-019).",
        "takeaway": "Start embedded in a test, ship it as a server: the SQL, the state and the answers are "
        "the same engine's.",
        "source": "Source: README.md 'Embedding'; docs/design/ARCHITECTURE.md §1 (ways to run it); "
        "docs/operations/RUNNING_IN_DOCKER.md 'One root: PRAVAHA_HOME'; docs/project/RELEASE_NOTES.md "
        "'2.3.0' (eclipse-temurin:21-jre); docs/design/adr/019-spring-free-engine-core.md.",
        "talk": "The same engine three ways: a library in a plain Java process, a bean in a Spring Boot "
        "application, or a server with an image and a Helm chart. Whichever you pick, it lives in one "
        "directory.",
    },
    {
        "kind": "table",
        "kicker": "The console · a separate process on the published SDK",
        "title": "The console — thirteen screens, one job each",
        "rows": [
            ["Screen", "What it is for"],
            ["Overview", "The engine at a glance: queries registered, running and sharing, and the busiest"],
            ["Operations", "Is everything healthy — and if not, where: state, backpressure, lag, checkpoints"],
            ["Queries · a query", "Every query you may see; one query's SQL, key, owner, sink, feed and controls"],
            ["Workbench", "Write, validate, explain, run and register against the engine's own planner"],
            ["A view · Live", "Read by key with copy-paste client code; watch commits arrive with weights"],
            ["Replacement", "A new version beside the running one, replayed, cut over when caught up"],
            ["Debugger", "Fork a query from a retained checkpoint and step it"],
            ["Catalogue · Plugins", "What exists and what reads it; what the node can load"],
            ["Admin · access", "What the policy lets each principal do; users, keys, grants, audit"],
            ["Help", "Topics, the long-form guides, and every error code"],
        ],
        "col_w": [1.5, 4.6],
        "size": 13.5,
        "note": "Every page ends with “About this page”; every page works at 360 px on a phone; the console "
        "listens on every interface and every page but the landing page and the docs needs a sign-in.",
        "source": "Source: docs/design/architecture/console-screens.md (thirteen screenshots and captions); "
        "docs/project/RELEASE_NOTES.md '2.4.0' ('About this page' on every page) and 'Unreleased' (the "
        "console on a phone at 360 and 390 px; listens on 0.0.0.0, sign-in required); README.md 'How it is "
        "built' (the console is a separate process on the SDK, ADR-024).",
        "talk": "Thirteen screens in the documentation, each with one job. The next slides show them as "
        "they are: these are real screenshots of the real console, driven over the test suite's fake "
        "engine, which is why the names are big_txn and hot.",
    },
    {
        "kind": "shot",
        "kicker": "Console · Operations",
        "title": "Is everything healthy — and if not, where?",
        "image": SHOTS + "console-operations.png",
        "items": [
            ("A verdict, not a wall of graphs",
             "The engine's metrics read into what needs attention, most severe first, each saying why."),
            ("Per query",
             "State against its ceiling, rows in, watermark lag, backpressure, checkpoints."),
            ("Shared lanes",
             "Which queries share a lane, and whose neighbour is the bottleneck."),
        ],
        "size": 14,
        "source": "Source: " + SHOT_SOURCE + "; docs/operations/OPERATIONS.md 'Watching a running node'.",
        "talk": "The operator's screen. It does not show every metric; it reads them into a verdict. Here "
        "two problems: one query near its state ceiling, and one that cannot be fed fast enough.",
    },
    {
        "kind": "shot",
        "kicker": "Console · Workbench",
        "title": "Write it, check it, see its plan, register it",
        "image": SHOTS + "console-workbench.png",
        "items": [
            ("The engine's own planner",
             "Validate as you type; diagnostics are the engine's, not a guess."),
            ("Explain and register",
             "The plan as a graph; registration with keys picked by name."),
            ("Describe it",
             "Plain English to SQL through any model, with the engine as the judge and a person "
             "confirming."),
        ],
        "size": 14,
        "source": "Source: " + SHOT_SOURCE + "; README.md 'The assistant' (ADR-058).",
        "talk": "The analyst's screen. The streams on the left, the editor in the middle, and the output "
        "schema from the engine's planner underneath. Describe it is the assistant — experimental, and the "
        "engine judges every draft.",
    },
    {
        "kind": "shots",
        "kicker": "Console · a query and its view",
        "title": "One query, and the view it keeps",
        "images": [
            (SHOTS + "console-query.png", "A query",
             "Its SQL, state, rows in, fingerprint, key, retention, owner, sink — here a PRV-8009 "
             "detach — feed and controls."),
            (SHOTS + "console-view.png", "A view",
             "Read it by key, and copy the code to do the same from each SDK, psql or HTTP."),
        ],
        "source": "Source: " + SHOT_SOURCE + ".",
        "talk": "Left, everything about one registered query on one page, including a sink that failed and "
        "was detached while the query kept running. Right, the developer's view: a point read and the "
        "client code to paste.",
    },
    {
        "kind": "shots",
        "kicker": "Console · live changes and replacement",
        "title": "Watch the answer move; change the query without a gap",
        "images": [
            (SHOTS + "console-live.png", "Live",
             "A view's committed changes as they arrive, weights included: +1 appearing, −1 withdrawn."),
            (SHOTS + "console-replacement.png", "Replacement",
             "A new version beside the running one, its history replayed, cut over when caught up."),
        ],
        "source": "Source: " + SHOT_SOURCE + "; README.md 'Blue/green replacement' (ADR-046).",
        "talk": "Live shows the weights from act one, on screen. Replacement is blue/green: the new version "
        "replays, catches up to the running one's position, and only then takes the name.",
    },
    {
        "kind": "shots",
        "kicker": "Console · debugger and catalogue",
        "title": "Step an incident; see what exists and what reads it",
        "images": [
            (SHOTS + "console-debug.png", "Debugger",
             "Fork a query from a retained checkpoint and step it — sinks disabled, nothing can read it."),
            (SHOTS + "console-catalog.png", "Catalogue",
             "Streams with their event time and source, queries, sinks, objects and grants."),
        ],
        "source": "Source: " + SHOT_SOURCE + "; README.md 'Time-travel debugger' (ADR-048), 'Governed "
        "catalogue' (ADR-059).",
        "talk": "The debugger is a second computation nobody can read: the live query never notices. The "
        "catalogue answers the governance question — what exists, who owns it, and what reads it.",
    },
    {
        "kind": "shots",
        "kicker": "Console · administration and help",
        "title": "Who may do what; and every answer in the help",
        "images": [
            (SHOTS + "console-admin-access.png", "Admin · access",
             "What the policy lets each principal do — the engine decides, the console only shows."),
            (SHOTS + "console-help.png", "Help",
             "Topics by category, the long-form guides, and every error code."),
        ],
        "source": "Source: " + SHOT_SOURCE + "; docs/operations/SECURITY.md 'The console acts as the person "
        "signed in'.",
        "talk": "The console holds no credential of its own; it acts as the person signed in, and the "
        "engine authorises every action. Help carries every guide and every code, so an operator never "
        "leaves the console during an incident.",
    },
    {
        "kind": "code",
        "kicker": "The CLI · pravaha doctor",
        "title": "Run it first when something does not work",
        "code": [
            "$ pravaha doctor",
            "GREEN   python      Python 3.x (python); needs >=3.9",
            "GREEN   cli         pravaha <version> (sdk/python/pravaha)",
            "GREEN   pyarrow     pyarrow <version>, with Flight",
            "GREEN   java        JAVA_HOME=$JAVA_HOME: Java 21",
            "GREEN   token file  ~/.config/pravaha/token: none saved …",
            "GREEN   context     none in use: flags, environment …",
            "GREEN   http        http://localhost:18080 answered in N ms",
            "GREEN   version     cli <version>, node <version>",
            "GREEN   flight      grpc://localhost:19090 answered in N ms",
            "YELLOW  auth        no token: every call is anonymous …",
            "                    fix: pravaha login --user <name> --save",
            "doctor: 0 red, 1 yellow",
        ],
        "code_w": 0.62,
        "items": [
            ("One line per check",
             "GREEN, YELLOW or RED, with the fix under each that is not green."),
            ("A pre-flight step",
             "Exits 1 on any RED; --json for a script. It changes nothing and never prints the token."),
            ("Checks the whole path",
             "Java 21+, token file mode, TLS trust and expiry, health, version skew, Flight, who you are."),
        ],
        "size": 14,
        "note": "Captured from a scratch node by sdk/python/tools/cli_captures.py, not typed; lines "
        "trimmed for the slide.",
        "source": "Source: pravaha-console/content/topics/cli-reference.md 'Doctor' (the captured block, "
        "lines trimmed with …) and 'Captured output'; docs/project/RELEASE_NOTES.md '2.4.0' (pravaha doctor).",
        "talk": "New in 2.4. The first thing to run on a new machine or when a call fails: it walks the "
        "whole path from Python to the node and tells you the fix. The output is captured from a real "
        "node by a tool, and a test fails if the documentation drifts from the program.",
    },
    {
        "kind": "code",
        "kicker": "The CLI · contexts and dry runs",
        "title": "Name your connections; see a change before you make it",
        "code": [
            "$ pravaha context add prod \\",
            "    --url grpc+tls://node-1:19090 \\",
            "    --http https://node-1:18080 --tls-ca ca.pem",
            "$ pravaha --context prod login --user ann --save",
            "",
            "$ pravaha drop --name spend_by_minute --dry-run",
            "dry run: drop spend_by_minute -- nothing was changed",
            "state        RUNNING",
            "shared with  nothing",
            "reads from   txn",
            "dependants   none",
            "subscribers  0",
            "would:",
            "  - the name spend_by_minute goes",
            "  - it is the computation's last name: the",
            "    computation stops; view and state released",
        ],
        "code_w": 0.56,
        "items": [
            ("Contexts, as kubectl does it",
             "A named connection — URLs, token, TLS — in contexts.json, mode 0600, written atomically; "
             "show never prints a token."),
            ("--dry-run on everything destructive",
             "drop, replace and its steps, grant, revoke, policy, user, key revoke, alert drop: reads "
             "only, then what would happen."),
            ("Honest about the unknown",
             "What only the change itself can find is listed as not known without doing it."),
        ],
        "size": 14,
        "source": "Source: pravaha-console/content/topics/cli-reference.md 'Contexts' (the commands) and "
        "'Dry runs'; the drop block is the captured 'drop-dry-run' output, owner, fingerprint and sink "
        "lines trimmed and the effect lines wrapped for the slide; docs/project/RELEASE_NOTES.md '2.4.0'.",
        "talk": "Two habits from mature tools. Contexts save a connection by name. Dry run makes every "
        "destructive command show what it would touch — sharing, dependants, subscribers — and change "
        "nothing.",
    },
    {
        "kind": "code",
        "kicker": "The CLI · top, init, plugin new",
        "title": "Watch a node live; start a project or a connector that builds",
        "code": [
            "$ pravaha top --once --interval 1",
            "NAME             STATE    LANE  ROWS IN  ROWS/S",
            "spend_by_minute  RUNNING  own   0        0.0",
            "",
            "$ pravaha init my-project",
            "# conf/application.yaml, data/txn.csv,",
            "# queries/spend_per_minute.sql,",
            "# docker-compose.yml, README.md",
            "",
            "$ pravaha plugin new my-store --kind source \\",
            "    --package com.acme.mystore",
            "$ cd pravaha-plugin-my-store && mvn verify",
        ],
        "code_w": 0.55,
        "items": [
            ("top",
             "Rows a second, watermark delay, state and its bytes, view rows, subscribers — redrawn "
             "live, or --once --json for a script."),
            ("init",
             "A stream, its CSV, a first windowed query and a compose file; a test starts a node on it "
             "and runs the query."),
            ("plugin new",
             "A Maven connector with the TCK wired in: all ten source tests pass as generated."),
            ("Exit codes are a contract",
             "0, 1 refused, 2 usage, 3 unreachable, 130 interrupted — pinned by tests."),
        ],
        "size": 13.5,
        "source": "Source: pravaha-console/content/topics/cli-reference.md 'Top' (captured 'top-once', columns "
        "after ROWS/S trimmed), 'Starting a project', 'A connector project', 'Exit codes'; "
        "docs/project/RELEASE_NOTES.md '2.4.0' (test_cli_init.py; the TCK passes).",
        "talk": "The rest of the 2.4 command line. top is the node's queries live. init writes a project that "
        "runs as generated, and plugin new a connector whose test kit passes before you write a line.",
    },
    {
        "kind": "table",
        "kicker": "Connectors · plugins/",
        "title": "Ten connector plugins: nine sources, six sinks, two lookups",
        "rows": [
            ["Plugin", "Reads", "Writes", "What it promises"],
            ["kafka", "A topic, a reader per partition", "Keyed upserts (JSON, Avro, Protobuf) or a changelog",
             "Exactly once both ways"],
            ["postgres-cdc", "Logical replication", "—", "Exactly once; slot confirmed at checkpoints"],
            ["mysql-cdc", "The row-based binlog, as a replica", "—", "Exactly once from file and offset, or GTID"],
            ["jdbc", "A table or any SELECT, polled", "Upsert and delete by key, or append; and a lookup",
             "Filters, projections, COUNT/SUM pushed in"],
            ["delta", "A table, deletion vectors included", "Kept equal to the view, or a changelog",
             "One Delta commit per checkpoint"],
            ["iceberg", "—", "A local-filesystem table, equality deletes", "One snapshot per checkpoint"],
            ["aerospike", "A set, by last-update time", "Upsert and delete by key; and a lookup",
             "Filters pushed in; sink effectively once"],
            ["cassandra", "A table, by token() range", "—", "Key restrictions pushed in"],
            ["filesystem · feedfile", "A file, bounded or followed; a directory of CSV or Parquet",
             "Append-only files", "Sink at least once"],
        ],
        "col_w": [1.25, 2.0, 2.3, 2.0],
        "size": 15,
        "note": "Delivery is stated at registration, and a query a sink cannot take is refused before the "
        "sink opens (PRV-2041, PRV-8010). Change data capture is on each database's own protocol, without "
        "Debezium (ADR-041).",
        "source": "Source: docs/design/ARCHITECTURE.md §2 plugin table (which plugin registers which names); "
        "README.md 'Sources', 'Sinks'; docs/guides/CONNECTORS.md §1; docs/design/adr/041-change-data-capture-"
        "without-debezium.md.",
        "talk": "Only what is in the plugins directory. Nine sources, six sinks, two lookups across ten "
        "plugins, and each says what it can promise. A transactional sink commits with the checkpoint, so "
        "it is exactly once.",
    },
    {
        "kind": "cards",
        "kicker": "SDKs and protocols",
        "title": "Four ways to take the answer into your code",
        "cols": 4,
        "cards": [
            ("JAVA", "pravaha-sdk-java-flight",
             "query, subscribe, subscribeFromSnapshot and a reconnecting subscription. A thin jar through "
             "Maven or Gradle, or a 19 MB -all jar."),
            ("PYTHON", "the pravaha wheel",
             "Imports with the standard library alone; pravaha[flight] adds Flight. The CLI and the "
             "assistant ship in it."),
            ("FLIGHT SQL", "Any Arrow Flight SQL client",
             "TLS by default; both SDKs refuse to send a token over plaintext unless told to."),
            ("POSTGRESQL", "psql, DBeaver, Grafana, Power BI",
             "Simple and extended protocol, text and binary, TLS; the same grants, filters and masks. "
             "Off by default."),
        ],
        "note": "SdkIndependenceTest fails the build if an SDK reaches a server module; four clients outside "
        "the repository are built and run against a throwaway node.",
        "source": "Source: README.md 'Serving', 'BI tools'; docs/project/RELEASE_NOTES.md '1.0.0' "
        "(SDKSTANDALONE-1; the -all jar); docs/operations/SECURITY.md 'Transport'; docs/development/TESTING.md "
        "'The SDKs on their own'.",
        "talk": "The SDKs ship and run without the server — a test makes sure of it. And because the "
        "gateway speaks PostgreSQL, a BI tool reads a live view as if it were a table, under the same "
        "security.",
    },
    {
        "kind": "code",
        "kicker": "SDKs · reconnect",
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
             "A stream ended by a restart or a broken connection is reopened 250 ms to 10 s apart, for "
             "up to five minutes by default."),
            ("Paired with a snapshot",
             "The first batch after reopening is a fresh snapshot, so nothing committed while the node "
             "was down is lost or counted twice."),
            ("Every call has a deadline",
             "60 s by default; past it, PRV-1045. A permanent refusal is raised at once."),
        ],
        "size": 14.5,
        "source": "Source: docs/guides/USER_GUIDE.md 'Surviving a restart: reconnect' (both snippets, "
        "reflowed); docs/project/RELEASE_NOTES.md '2.2.0' (SDK deadline, PRV-1045).",
        "talk": "What an application developer writes. Subscribe from a snapshot with reconnect on, and a "
        "node restart costs you nothing: you get a fresh snapshot and carry on.",
    },
]
