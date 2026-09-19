<div align="center">

<img src="brand/mark.svg" alt="" width="88" height="88">

# प्रवाह · Pravaha

### Ask once. Answer always.

**An embeddable, store-native, incrementally-maintained SQL engine.**

*Pravaha* (Sanskrit: *continuous, uninterrupted flow*) · pronounced *pruh-VAA-huh*

[![Status](https://img.shields.io/badge/status-wave%209%20of%2011-blue)](docs/HANDOVER.md)
[![Java](https://img.shields.io/badge/Java-21%20LTS-orange)](docs/system_design.md#4-language-decision-java-vs-scala)
[![Build](https://img.shields.io/badge/build-Maven-C71A36)](docs/implementation_plan.md)
[![License](https://img.shields.io/badge/license-Proprietary-red)](LICENSE)

</div>

---

> **Project status: Wave 9 of 11.** One node: an engine that maintains the answers to registered
> SQL questions as data changes, serves them back by key, writes them to sinks, and survives its own
> restart, at a thread and memory cost that stops following the query count. **Clustering is not
> built**, and a node refuses to start in `PARTITIONED` mode rather than pretend to be.
>
> The road to GA is [ADR-039](docs/adr/039-ga-includes-the-known-gaps-and-clustering.md): close the
> known gaps, then cluster mode, then GA. Its progress note says item by item what is closed and what
> remains; [`HANDOVER.md`](docs/HANDOVER.md) has the detail. This file says what is true now, and the
> build checks the parts of it that can be checked.

## What it is

Pravaha runs continuous SQL over the databases you already have — Aerospike, Cassandra, PostgreSQL
or any JDBC database, files and Delta tables — keeps each answer current as the data changes, and
serves the answer back by key, so the result needs no second database to live in.

```sql
SELECT STREAM
    TUMBLE_END(event_time, INTERVAL '10' SECOND) AS window_end,
    t.user_id, p.tier,
    COUNT(*)      AS txn_count,
    SUM(t.amount) AS total_volume
FROM  txn_stream AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
       ON t.user_id = p.user_id
WHERE t.status = 'COMPLETED'
GROUP BY TUMBLE(t.event_time, INTERVAL '10' SECOND), t.user_id, p.tier
```

That query — a tumbling window, a temporal lookup join, a filter evaluated inside Aerospike rather
than after the read, and two aggregates — is registered and run against a real Aerospike server by
`AerospikeContinuousQueryIT`, which needs Docker. `SELECT STREAM` is accepted and redundant: every
Pravaha query is continuous. There is no `CREATE CONTINUOUS QUERY` statement; a query is registered
by name, through the CLI or an SDK, with the columns its view is keyed by.

The answer is then read by key, over Arrow Flight SQL or the PostgreSQL wire protocol:

```java
// The Java SDK.
try (QueryResult result = client.query("SELECT total_volume FROM user_volume WHERE user_id = ?", "u42")) {
    for (Row row : result) {
        long volume = row.getLong("total_volume");
    }
}
```

```python
# The Python SDK, against the same engine.
for row in client.query("SELECT total_volume FROM user_volume WHERE user_id = ?", "u42"):
    volume = row["total_volume"]
```

A consumer that wants the changes rather than the answer subscribes, and receives one batch per
commit with a weight on every row: `+1` for a row appearing, `−1` for one being withdrawn. A window
corrected by late data arrives as a retraction of the old answer followed by the new one.

## What works

| | |
|---|---|
| **SQL** | Calcite parses and optimises; the plan becomes Pravaha's own operator tree, run over off-heap binary rows. Whole-stage code generation runs roughly **10× the interpreted path**. Projections, expressions, `CASE`, string and numeric functions, `LIKE`, filters, aggregates. What is refused, and why, is in [`CONTINUOUS_QUERIES.md`](docs/CONTINUOUS_QUERIES.md), checked against the planner by a test |
| **Continuous queries** | Registered with a name and a key; paused, resumed, dropped. Identical questions share one computation under many names, matched on the normalised plan, so ten desks asking the same thing cost one read of the source |
| **Windows and event time** | Tumbling, sliding and session windows, with slicing. A query derives its watermark from the event-time column its stream declares, a quiet partition stops holding the rest back, and a window publishes when time passes its end |
| **Corrections** | Late data within a stream's declared `allowedLateness` reopens a closed window as a retraction plus the corrected answer. Every change carries a Z-set weight, through the engine, across the wire and into both SDKs |
| **Joins** | Stream-to-stream, and temporal lookup joins against a JDBC or Aerospike dimension table |
| **Sources** | Filesystem (bounded, or followed like `tail -f`), feedfile directories (CSV, Parquet), Delta Lake, JDBC polling, Aerospike scans, Cassandra `token()`-range scans. Filters are pushed into JDBC and Aerospike. One reader per source binding feeds every query bound to it. Connections to JDBC, Aerospike and Cassandra can be encrypted ([`CONNECTOR_TLS.md`](docs/CONNECTOR_TLS.md)) |
| **Serving** | The maintained view is read by key or scanned with SQL, and subscribed to per commit. Over **Arrow Flight SQL** (Java SDK, Python SDK, CLI, console), and over the **PostgreSQL wire protocol** (`pravaha.pgwire.enabled`, off by default) so `psql`, DBeaver, Grafana and any Postgres driver can read a view — simple and extended protocol, `\d`, TLS |
| **Sinks** | A registration can also name a sink (`pravaha register --sink`), and every commit of its view is written there, retractions included. Refused at registration, before the sink opens: a query that revises its answer against an append-only sink (`PRV-2041`), and a sink whose configured columns or key differ from the query's (`PRV-8010`). Shipped: `filesystem` (append-only) and `aerospike-sink` (upsert and delete by key). At least once |
| **State** | Off-heap: join indexes and windowed-aggregate accumulators live in `RowStore` blocks behind open-addressed tables, except `COUNT(DISTINCT)`, which stays on-heap. With `pravaha.state.spill.*` set, state past its memory ceiling spills to memory-mapped files and the query slows instead of stopping. Per-query gauges show state approaching its ceiling |
| **Recovery** | Checkpoints hold operator state, source offsets and the served view, cut at one point across every input (ADR-008), so a restart resumes rather than replaying from scratch or starting empty. The registry journal brings back every registration, and its sink |
| **Survival** | A node claims the directories it writes, so two nodes cannot silently share state (`PRV-4003`). A standby takes over when the claim goes stale and reports what the takeover cost. Undecodable input goes to a dead-letter directory instead of ending the query |
| **Many queries on one node** | A fixed pool of one thread per core drives every lane, and the watermark and checkpoint clocks are one timer for the process: **200 queries add 24 platform threads** on 24 cores, where they once added 400. About **1 MiB off-heap per idle query**, and every component reports its own bytes |
| **Security** | Authentication, authorization on what a query reads rather than what it is called, row filters, prepared statements, audit. The node refuses to start open unless told to |

## What is not built, or not finished

- **Multi-node execution.** Membership produces a real partition assignment, and partition ownership
  is a fenced lease proved against a real ZooKeeper ensemble — but no node consumes it yet, so
  execution is single-node and a node refuses `PARTITIONED` mode (`PRV-9002`) rather than serve every
  partition while claiming to own some. Rebalance and handoff are built as a library and wired to
  nothing ([ADR-039](docs/adr/039-ga-includes-the-known-gaps-and-clustering.md) item 8).
- **Lane sharing by stream, not by query.** `pravaha.lane.multiplex.enabled` (off by default) puts
  registered queries on shared lanes, sharing inbox and arena as well as thread — but a shared lane
  carries only one query per stream, because each query is fed separately and two over one stream
  on one lane would count each other's rows. One ingest per stream per lane is not built, so a
  thousand queries over one source still hold most of their inboxes each (W9-8).
- **Pushdown beyond filters.** Projection and `COUNT`/`SUM` partial-aggregate pushdown are built in
  the planner and the engine and declared by no shipped plugin, so a deployment only pushes filters.
  And when one shared reader serves queries with different filters, it reads unfiltered;
  `share.reader: false` keeps a query's pushdown at the cost of its own read.
- **Exactly-once output.** Sinks are at least once: a restart replays from the last checkpoint, and
  the transactional half of the sink SPI is not tied to checkpoints. An idempotent upsert sink, such
  as `aerospike-sink`, absorbs the repeats; a file keeps them.
- **Sinks beyond two.** No Kafka, no JDBC sink. `aerospike-sink` is unit-tested without a server here;
  its real-server test needs Docker.
- **Change data capture.** A PostgreSQL logical-replication source is designed
  ([ADR-041](docs/adr/041-change-data-capture-without-debezium.md)) and not started. Sources poll or
  scan; Aerospike and Cassandra scans cannot see deletes.
- **The spill tier's last four pieces.** There is no RocksDB, by decision
  ([ADR-044](docs/adr/044-no-rocksdb-the-mapped-tier-is-l1.md)): the memory-mapped overflow tier is
  the on-disk tier. It does not yet compact its slabs, budget in bytes, spill `COUNT(DISTINCT)`, or
  have a measurement at several times RAM.
- **A Spring Boot starter** (ADR-020), and SQL registration statements (`CREATE CONTINUOUS QUERY`). The embedded engine (`pravaha-embedded`) is a lifecycle seam that
  cannot yet register or read a query.
- **Blue/green query updates and backfill splicing** are built in `pravaha-backfill` and reachable
  from no running path.
- **The console is a functional admin console, on purpose** — not the design-system product surface
  design §23 describes.

## Performance: what is measured, and what cannot be here

**The requirement is about 1,000 rows per second** ([ADR-042](docs/adr/042-the-throughput-bar-is-the-requirement.md)),
because Pravaha maintains answers to registered questions rather than moving bulk data. The design's
original figures — 1.2 M rows/s per lane, ≥ 90 % scaling from one lane to eight — are kept as
aspirations, and gates P2 and P3 stay **unmeasured**: they need 16 homogeneous physical cores, and the
development machine is a 12-core heterogeneous laptop part. No number from it is quoted as if it
were one of those.

What this machine can report: the lane machinery runs at about **21 M rows/s**, and the cost of a
query — threads, off-heap bytes, file descriptors, registration time — which `NodeScaleTest` and
`SourceScaleTest` measure. A thousand distinct continuous queries register in 3.7 ms each and hold
61 MiB off-heap at the advised inbox sizing. The evidence packs in [`docs/gates`](docs/gates/) say
what was and was not measured, wave by wave.

## Try it

```bash
./mvnw -q -DskipTests install

# Register a continuous query, ask its view a question, then watch it change.
pravaha register  --name user_volume --sql "SELECT user_id, SUM(amount) AS total FROM txn \
                    GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id" --keys 0
pravaha query     --sql "SELECT total FROM user_volume WHERE user_id = ?" --params u1
pravaha subscribe --view user_volume --filter user_id=u1
```

`pravaha queries`, `pause`, `resume` and `drop` manage what is running; `run`, `explain` and
`validate` work without a server. The [**Quickstart**](docs/QUICKSTART.md) takes a clone to a running,
changing view in about ten minutes.

## How it is built

| | |
|---|---|
| **Language** | Java 21 LTS, one language. Calcite plans; Pravaha's own operators execute. [Why not Scala →](docs/system_design.md#4-language-decision-java-vs-scala) |
| **Execution** | Whole-stage code generation (Janino) over binary flyweight rows in off-heap arenas. No `Map<String,Object>`, no boxing, no allocation on the hot path |
| **Concurrency** | Partitioned lanes and the single-writer principle: one inbox, one state slice and one timer wheel per lane, and exactly one thread driving a lane at a time, drawn from a fixed pool of one per core. No locks in steady state |
| **Incrementality** | Z-sets and DBSP-derived operators: work is proportional to what changed, not to how much data exists |
| **Correctness** | Exactly-once **state**: a checkpoint holds every source between rows, cuts every lane at one point and records the offsets of that same point (ADR-008). Output to a sink is at least once |
| **Deployment** | Two processes, on purpose: `pravaha-server`, a Spring Boot node with the engine, Flight SQL, the PostgreSQL gateway and a plain `/status` page; and the console, a separate Python process built on the published SDK, so it cannot reach past the public API (ADR-024). The engine core contains no Spring |

Queries are registered, listed, paused, dropped and subscribed to over Flight, not REST. The HTTP
surface is deliberately small: `/status`, `/api/v1/streams`, and `/api/v1/queries/validate` and
`/explain`.

## The console

A Python FastAPI application on the published SDK, server-rendered, with every asset vendored so it
runs air-gapped.

```bash
cd console && make install && make run     # :8090, engine at :9090
```

It lists and filters what is registered, shows a query's SQL, fingerprint and siblings with a live
tail, runs pause, resume and drop, has a workbench for asking or registering a query, and renders
this documentation in place. Everything but the landing page, the documentation and the health
probes needs a session. One engine subscription serves every browser watching a view. It is not the
IDE-grade workbench, live plan view or time-travel debugger design §23 specifies, and the §23.20
release gate — Storybook, visual regression, a WCAG 2.2 AA audit — is not done.
[How it is built →](console/README.md)

## Documentation

| Start here | |
|---|---|
| [Quickstart](docs/QUICKSTART.md) | Clone to a running continuous query |
| [Concepts](docs/CONCEPTS.md) | The ideas everything follows from; most surprises are one of these working correctly |
| [User guide](docs/USER_GUIDE.md) | The whole surface, task by task, in Java, Python and the shell |
| [Case studies](examples/case-studies/) | Five worked systems: a store to stand up, a data model, a continuous query and the app code |

| Reference | |
|---|---|
| [Streams, queries and SQL](docs/CONTINUOUS_QUERIES.md) | Declaring streams and sources, registering, reading, sinks, windows, joins — and every SQL construct that works or is refused |
| [Connectors](docs/CONNECTORS.md) and [TLS](docs/CONNECTOR_TLS.md) | Building a source or sink plugin; encrypting every connection |
| [Operations](docs/OPERATIONS.md) | Configuration, sizing, sinks, state, recovery, what to watch |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Every `PRV-` code |
| [Security](docs/SECURITY.md) | Authentication, authorization, row filters, audit |

| How and why | |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | How it is put together, and why each part is shaped that way |
| [System design](docs/system_design.md) | The full specification |
| [Decision records](docs/adr/) | Every architectural decision, including the ones later reversed |
| [Handover](docs/HANDOVER.md) | Current state, and what to pick up next |
| [Findings](docs/qa/FINDINGS.md) | Every defect found, and what happened to it |
| [Gate records](docs/gates/) | Evidence packs, wave by wave |
| [Implementation plan](docs/implementation_plan.md) · [Original SRS](docs/initial_req.md) | The plan, and the draft this design supersedes |

Parts of this documentation are checked by the build rather than by memory: the SQL in
`CONTINUOUS_QUERIES.md` and the case studies is planned against the real engine; the code table in
`TROUBLESHOOTING.md` must match the declared error codes in both directions; the quickstart's
serverless commands are run and their output compared; every module must be described, every cited
ADR must exist, and this file's badge, status line and roadmap must agree; every lane setting and
per-query gauge must be named in `OPERATIONS.md`; and the findings register's header must match its
entries. Prose accuracy beyond that is not checkable, which is why the status sections above are kept
short.

## Building

```xml
<groupId>com.ash.messaging</groupId>
<artifactId>pravaha</artifactId>
<version>0.1.0-SNAPSHOT</version>
```

Base package `com.ash.messaging.pravaha`. Requires **JDK 21+**; the Maven wrapper is vendored.

```bash
./mvnw clean verify                                  # full build
./mvnw -T1C -DskipITs -Dbenchmarks.skip=true test    # fast inner loop
./mvnw -Pall verify                                  # everything, as CI runs it
tools/verify-clean.sh                                # the gate: offline, no stale jars
```

**Modules**, in build order — checked against `pom.xml` by `DocumentationFreshnessTest`:
`pravaha-bom`, `pravaha-api`, `pravaha-common`, `pravaha-algebra`, `pravaha-catalog`,
`pravaha-sql`, `pravaha-runtime`, `pravaha-codegen`, `pravaha-state`, `pravaha-backfill`,
`pravaha-security`, `pravaha-serving`, `pravaha-cluster`, `pravaha-registry`, `pravaha-flight`,
`pravaha-pgwire`, `pravaha-connect`, `pravaha-testkit`, `pravaha-benchmarks`, `pravaha-it`,
`pravaha-embedded`, `pravaha-cli`, `pravaha-server`.

**Plugins:** [`filesystem`](plugins/pravaha-plugin-filesystem), [`delta`](plugins/pravaha-plugin-delta),
[`feedfile`](plugins/pravaha-plugin-feedfile), [`jdbc`](plugins/pravaha-plugin-jdbc),
[`aerospike`](plugins/pravaha-plugin-aerospike), [`cassandra`](plugins/pravaha-plugin-cassandra),
[`cluster-zookeeper`](plugins/pravaha-cluster-zookeeper).

**SDKs:** [`sdk/pravaha-sdk-java`](sdk/pravaha-sdk-java),
[`sdk/pravaha-sdk-java-flight`](sdk/pravaha-sdk-java-flight), [`sdk/python`](sdk/python). The
console is its own artefact in [`console`](console).

## Roadmap

| Wave | Weeks | Milestone | |
|---|---|---|---|
| 1 | 1–2 | Foundations; deterministic harness | ✅ `M1` |
| 2 | 3–5 | Vertical slice; **go/no-go on the incremental core** | ✅ `M2` |
| 3 | 6–11 | Codegen, lanes, exchange — Profile A ≥ 1.2 M rec/s/lane | ✅ built · gate P2 needs hardware |
| 4 | 12–18 | Windows, watermarks, late data, tiered state | ✅ built, with a memory-mapped L1 instead of RocksDB (ADR-044) · gate P3 needs hardware |
| 5 | 19–25 | Joins, Aerospike, checkpointing and recovery | ✅ built |
| 6 | 26–32 | Backfill, blue/green, serving layer — **first defensible demo** | ✅ built · blue/green reachable from nothing |
| 7 | 33–38 | Flight SQL, SDKs, security, registration, subscriptions, console | ✅ built |
| 8 | 39–45 | Survival on one node — state ownership, checkpoint barriers, standby ([ADR-035](docs/adr/035-wave-8-is-survival-not-distribution.md)) | ✅ built · gate P7 passed |
| 9 | — | One node, thousands of queries ([ADR-036](docs/adr/036-one-node-thousands-of-queries.md), [ADR-037](docs/adr/037-state-that-degrades-instead-of-dying.md)) | ✅ built · one item open (W9-8) |
| 10–11 | 46–62 | GA: ADR-039's known gaps, then cluster mode | ▫️ not started |

Waves 10 and 11 were redefined twice: [ADR-038](docs/adr/038-one-node-ga.md) moved the time-travel
debugger and the Nexmark comparison out to the roadmap, and ADR-039 put the known gaps and cluster
mode in their place. Most of the gap work has landed as the unfinished part of waves 8 and 9, which is
why the counter still reads 9. "Built" means the code is there and tested; it does not mean a
performance gate passed.

Work happens on `develop`; `main` is merged from it on request and is normally behind. `M7` is the
newest tag. [Full roadmap with acceptance gates →](docs/system_design.md#31-delivery-roadmap)

## Contributing

Not yet open for outside contributions. The standards that will apply are already enforced by the
build — see [implementation plan §3–§4](docs/implementation_plan.md):

- source files stay under 1500 lines (`SourceFileSizeTest`)
- every production type carries JUnit coverage, with JaCoCo gates per module
- no Spring in the engine core, no `Serializable`, no unbounded collections (`ArchitectureRulesTest`)
- every file carries the copyright and licence notice (`LicenseHeaderTest`)

## Legal

**Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.**

**PROPRIETARY AND CONFIDENTIAL.** Project Pravaha — its source code, design documents,
architecture, algorithms, data formats, documentation and brand marks — is the sole and exclusive
property of Ashutosh Sinha. No licence or right is granted by implication or otherwise.

Use, copying, modification, distribution and disclosure are prohibited except with the express prior
written permission of the copyright holder. See [`LICENSE`](LICENSE) for the full terms.

If you have obtained a copy of this software without written authorisation, you are not permitted to
retain it; destroy all copies and contact ajsinha@gmail.com.

Third-party open-source components used by Pravaha remain the property of their respective owners
and are governed by their own licences, which this project's terms do not affect. Their attribution
notices are reproduced in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

The Pravaha name, the flow mark, and the slogan "Ask once. Answer always." are proprietary; see
[`brand/README.md`](brand/README.md).
