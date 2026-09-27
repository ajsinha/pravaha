# What is left, and the order it gets built in

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../LICENSE`](../LICENSE).

> **Revised 2026-09-27.** [`LIMITS.md`](LIMITS.md) sorts every entry of the README's "What is not built"
> into one of three kinds: deferred by decision (multi-node only), buildable, or a boundary of a store,
> a format or a decision. This page is the strategy for the buildable ones. The owner's constraints
> shape it: every gap except multi-node gets built; at most two agents besides the lead; each piece is
> gated and drilled before the next starts; no RocksDB; and native code only where Java cannot do
> the job ([ADR-053](adr/053-native-code-only-where-java-cannot.md)). The owner set the order on
> 2026-09-27: A, then B, then C.

## The strategy

**Two slots, so the order matters more than the width.** One slot is the *engine* slot. It is serial,
because `QueryRegistry`, `QueryExecution` and the feed wiring sit next to one another and a second
agent there means merging into a moving tree. The other is the *isolated* slot: one plugin, the
console, or build configuration at a time, each in its own tree, so it never merges into the engine
slot's work.

**Value first, within each slot.** The engine slot starts with the gap that changes what the product
can do at scale. The isolated slot starts with small, contained items that each close a README entry
outright. Larger connectors come after both.

**Each piece closes its README entry in the same commit.** It moves the entry out of `LIMITS.md`'s
buildable list, updates the README table, and adds a test that would fail if the claim became untrue.
The findings register tracks anything found along the way.

### Tranche 0: in flight

| Item | Slot | Size |
|---|---|---|
| Users, passwords, API keys, sessions, MFA and SSO, the way MAYA does them ([ADR-052](adr/052-the-engine-is-the-identity-authority.md)): the engine core, then admin REST and CLI, the console signing each person in against the engine, TOTP and WebAuthn, OIDC and SAML, then migration and 0.2.0 | both | six stages |

### Tranche A: small, contained, each closes an entry (isolated slot)

| # | Item | Size | Why this order |
|---|---|---|---|
| A1 | **Built 2026-09-27.** Compressed Kafka under [ADR-053](adr/053-native-code-only-where-java-cannot.md): `kafka-sink` and the `kafka` source read and write snappy and zstd through snappy-java and zstd-jni, the two native families it allows; lz4 is refused by name on both sides | S | Producers in the field default to these codecs |
| A2 | **Built 2026-09-27.** The Kafka source picks up partitions added after registration, through a periodic metadata refresh (`partitions.refresh`) with the new partition's offsets entering the next checkpoint, and a checkpoint that names each offset's partition so a restore matches them across streams | S | A topic scaled out today is silently half-read until a restart |
| A3 | **Built 2026-09-27.** Cassandra filter pushdown on partition-key and clustering columns, which needs no `ALLOW FILTERING`, shown in the feed description | S | Stops a full-table re-read each pass when the filter is on the key |
| A4 | **Built 2026-09-26, confirmed 2026-09-27.** The console's tenants and quotas screen, on `GET /api/v1/tenants` (`/admin/tenants`, with B11): the README and LIMITS still listed it as missing | S | The API already exists |
| A5 | **Built 2026-09-27.** Spring Boot 3.2, 3.3 and 3.4 legs run (41 tests each, green, after pinning four runtime libraries the older BOMs downgrade), and in CI's `matrix` workflow | S | The profiles exist; only running them is missing |

### Tranche B: the engine (engine slot, serial)

| # | Item | Size | Why this order |
|---|---|---|---|
| B1 | **One read of an exactly-once source for every query on it.** *Built for Kafka and files read once through (ADR-054); Delta and JDBC remain.* A shared reader for Kafka first, then files and Delta, whose offsets become one checkpoint cut for every query it feeds, as the barrier already cuts every lane at one point (ADR-008). Lane sharing already writes each row once per shared lane. This removes the last "read it N times" | L | The biggest runtime win left: 1,000 queries over one topic read it once, not 1,000 times |
| B2 | A secondary index on a column outside the view's key: value to keys, maintained in the view's own commit, used by a point read's predicate | M | Turns the last documented full scan in serving into a probe |
| B3 | ~~The snapshot-and-change-feed splice wired~~ **Closed as a boundary 2026-09-27.** `SplicedReader`'s newest-row-per-key rule is unsound on a weighted changelog: over `postgres-cdc` it drops a snapshot row and then replays that row's `-1`. `postgres-cdc`'s own snapshot is already exact. See LIMITS.md; `backfill.adaptive` stays refused | M | Built and tested code that nothing reaches is a liability until it is wired, or until it is shown it must not be |

### Tranche C: larger connectors (isolated slot, after A)

| # | Item | Size | Note |
|---|---|---|---|
| C1 | `jdbc-sink` over PostgreSQL through `PREPARE TRANSACTION`: a real two-phase commit, without the staging table's second write | *Built: `commit.mode: prepared`, opt-in.* M | Other databases keep staging |
| C2 | `kafka-sink` writing Avro and Protobuf, reusing the source's specification-level encoders | M | Pairs with the source's formats |
| C3 | A MySQL binlog CDC source, on ADR-041's model (no Debezium) | L | Build when a deployment needs MySQL |
| C4 | An Iceberg sink on iceberg-core, without Spark | L | Its dependency tree is large; build when a deployment asks for Iceberg |

### What does not get built

Multi-node execution is on hold by the owner's decision, and the console's cluster screens wait for
it. The boundaries in `LIMITS.md` stay boundaries. Examples: retracting `MIN`/`MAX` incrementally,
Aerospike partials (they need UDFs on the customer's cluster), Delta `OPTIMIZE`/`VACUUM`, editing
grants in the console, RocksDB. The manual WCAG audit is a person's work, scheduled with the QA team.

### The estimate

With two slots, tranche A and B1 run side by side once identity is done. A is five small items; B1
is the one large engine change. B2, B3 and C1 to C2 follow in the same two slots. C3 and C4 are
built on demand, not speculatively.

## History: the 2026-09-19 batches

The plan this page carried before the revision. It is kept because other documents cite its batch
names, such as B12 and "Not scheduled".

> **Everything still to build, drawn from the code rather than from a plan.** Its sources are
> [`../README.md`](../README.md)'s "What is not built",
> [ADR-039](adr/039-ga-includes-the-known-gaps-and-clustering.md)'s items, the design's §23.20
> console gate, and the open findings in [`qa/FINDINGS.md`](qa/FINDINGS.md). Written 2026-09-19,
> when no GA-BLOCKER and no GA-REQUIRED finding was open. One GA-REQUIRED finding was opened and
> closed after that: `CASE-1`, the case studies windowing over streams with no declared event time,
> done on 2026-09-20 with `TIME-6`'s plan-time refusal
> ([`qa/case1-time6-2026-09-20.md`](qa/case1-time6-2026-09-20.md)).

Each batch names the files it owns, so batches in the same wave touch disjoint trees and can be
built by different agents at the same time without merging into each other. The constraint that
shapes the schedule is the owner's: **at most five agents besides the lead** (three until the
evening of 2026-09-19, when measuring where the time went showed the work is agent-bound, not
gate-bound: the full gate is 5m30s and runs beside the agents, while a batch takes 40 to 120
minutes). The slots: the engine's core, serial, because `QueryRegistry`, `QueryExecution` and
`ViewSink` are one another's neighbours; two isolated lanes (a plugin, `pravaha-state`,
`pravaha-sql`); the console; and the findings clusters.

### Built on 2026-09-20, after the waves

Everything the five slots carried that day landed, was gated and was drilled: the console's
debugger screen (journey 7, so all eight journeys now run end to end), `ReplacementStatus.history`,
the owner's three console requests — MAYA's crimson theme with the crimson-to-indigo gradient, the
slogan in italics on every page, and a landing page with an animated SVG figure of a query being
maintained — `DOCX-21`'s help URLs (the console's help, configurable, no link at all when unset),
`DOCX-19`, `DOCX-20`, `CFG-4`, `CFG-10`, `LANE-6`, `STRM-4`, `STRM-8`, the API cluster
(`API-F2`/`F6`/`F7`/`F8`/`F10`/`F11`, `E-8`, `E-15`, `SX-19`), and thirty singleton findings.
The register went from 103 open in the morning to **17**, none of them GA-blocking.

### Queued, and why they are not running

Scheduled on 2026-09-20 and stopped before any of them wrote a line: all three agents failed to
start against the account's monthly spend limit (HTTP 429, weekly reset 2026-09-26). Nothing was
half-done and nothing needs unpicking; each is ready to start again as it stands.

| Batch | What | Owns |
|---|---|---|
| **B11** | **Engine built 2026-09-26** ([ADR-050](adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md)); console tenants screen built 2026-09-26. **Tenancy and admission quotas.** What a tenant scopes, how many queries and how much state it may hold, what happens at the limit (a refusal by name, not a silent degradation), whether identical SQL still shares one computation across tenants, and what an operator sees. With its own ADR. | `pravaha-registry`, `pravaha-security`, then `console/` |
| The three MEDIUM findings | `SPILL-3` (fire a large window in bounded batches, then re-run the beyond-RAM measurement), `SRC-7` (an Aerospike scan that buffers everything, so `maxRecords` is not a bound), `W8-14` (a windowed aggregate keyed by a digest, which makes a collision a silently wrong answer) | `pravaha-runtime`, `plugins/pravaha-plugin-aerospike` |
| The LOW findings | `DBG-1`, `DBG-2`, `WIRE-1`, `SINK-4`, `CKPT-6`, `SQL-13`, and the fixed test bounds behind `PGW-1` and `CON-8` | `pravaha-server`, `pravaha-flight`, `pravaha-sql`, `pravaha-pgwire` |

### Wave 1 — built

| Batch | What | Owns |
|---|---|---|
| **B1** | **Built 2026-09-19.** Blue/green and backfill (ADR-039 item 6's successor). Register v2 beside v1, backfill it from history spliced onto the live stream at an exact point, cut over atomically, roll back. `CREATE OR REPLACE CONTINUOUS QUERY`, REST, Flight, CLI, both SDKs, progress metrics. | `pravaha-registry`, `pravaha-backfill`, `pravaha-sql` recognizer, `pravaha-server`, `pravaha-flight`, `pravaha-cli`, SDKs |
| **B2** | **Built 2026-09-19.** The spill tier past the page cache. Measure it with state larger than the memory the process may use; spill a join key index's slot table. `SPILL-2` (the slot table stopped at 2^26 slots and the next doubling went negative) is fixed: segmented `SlotTable`, ceiling 2^30 slots, refused by name rather than overflowed. `SPILL-3` stays open and POST-GA — firing a large window builds it on the heap, which is the operator's own sizing rather than the spill tier's promise. | `pravaha-state`, the join index in `pravaha-runtime`, `tools/` |
| **B3** | **Built 2026-09-19.** The console's strings and its eight states. Every user-visible string through the catalog with a test that keeps it there; the §23.12 states audited screen by screen, each driven in a browser. | `console/` |

### Wave 2 — startable as each slot frees

| Batch | What | Owns | After |
|---|---|---|---|
| **B4** | **Built 2026-09-19** ([ADR-048](adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md)). A debug session forks a query from a retained checkpoint into a second computation on lanes of its own, reading the same sources from that checkpoint's offsets — no sink attached, its view in no catalogue, the live query untouched. Steps by row, N rows, to the next commit, to a watermark, or until one column of the view crosses a value; each step reports the rows in, every operator's rows in and out, the view's changes with weights, and the watermark. Operator state reads on the lane that owns it, bounded and paged, emitting and evicting nothing. An export writes a self-contained JUnit fixture whose expectation is *rehearsed* through a second empty execution, and `DebugFixtureExportTest` compiles and runs one. `pravaha debug`, nine Flight actions, `/api/v1/debug/*`, both SDKs, `pravaha.debug.*` bounds and the `pravaha_debug_sessions_open` gauge. **Left**: a windowed aggregate shows only the windows it has fired (an open one cannot be read without firing it); the replay interleaves partitions in a fixed order, so a bug that needs a particular interleaving may not reproduce; §23.11's WebSocket is not built, the actions are request/response; and the console's screen is B9. | `pravaha-runtime`, `pravaha-registry`, `pravaha-flight` | B1 (same files) |
| ~~**B5**~~ | ~~**Dead letters as a product.**~~ **Built 2026-09-19.** A query's dead letters list (paged, newest first), fetch whole and replay, over REST, a Flight action, `pravaha dlq list\|show\|replay`, both SDKs, the actuator and a console screen. Authorized by the view's own rules: the listing decides whether the queue exists for you, a row-filtered read is refused the bytes and the decoder's sentence (a record that did not decode has no row to filter), and a replay needs `mayAdminister`. A replay is a new row at the current frontier, not a rewind; a record that fails again returns to the queue. Retention (`pravaha.dlq.max-bytes`, 256 MiB by default, `.max-entries`, `.max-age`) evicts oldest-first and records the loss three ways. `DeadLetterRate` is wired, with six meters and a growing queue as a console finding. | `pravaha-server`, `pravaha-bindings` dead-letter path, CLI, SDKs | — |
| **B6** | **Built 2026-09-19.** Backpressure is measured in **episodes**: a writer opens one when it finds no room and closes it when room returns, so the cost is one branch per poll and the numbers are `pravaha_query_backpressure_waits_total`, `_wait_seconds`, `_blocked_fraction`, `pravaha_query_inbox_depth`/`_cells`, and per shared lane `pravaha_lane_blocked_fraction`/`_inbox_depth` with a per-query breakdown behind them. Per-operator rows in, rows out, state bytes, watermark and a **sampled** self time (one row in 1,024) ride on `GET /api/v1/queries/{name}/plan`, keyed by the graph's own node ids, with `bottleneck` naming the node most of the time went into; `PlanNodes` is now the one definition of that order. Off by default behind `pravaha.metrics.operators` — measured at **7.9–8.6 %** of a narrow query's throughput on an idle reference machine, and too noisy to pin down on a busy one (`OperatorMetricsOverheadIT`, which prints the load average beside its result). **Left for B9**: the console's own screens — the dashboard still lists backpressure under "not measured" and the plan graph still says per-operator numbers are not published, because `console/` is B9's tree. | `pravaha-runtime` metrics, `pravaha-server` plan endpoint | — |
| **B7** | **Built 2026-09-19.** `format: avro` reads Avro's binary encoding with a reader written here from the specification (`AvroBinary`, `AvroSchema`, `AvroRowReader`) against `schema.file` or a registry's schema; `format: protobuf` reads one message with `DynamicMessage` over `schema.descriptor` (`protobuf-java` declared, and pinned to 4.33.4 in the root pom's `dependencyManagement`); `schema.registry.url` speaks the Confluent wire format and `GET /schemas/ids/{id}` over the JDK's `HttpClient`, cached by id, with basic auth or a bearer token, and works against Karapace and Apicurio's `ccompat` endpoint. New codes `PRV-5108` (a schema that cannot be mapped, at registration) and `PRV-5109` (a registry that cannot be read). No `org.apache.avro` and no Confluent jar. Protobuf schemas are fetched from the registry serialized (`?format=serialized`, references by subject and version) and chosen by the record's message indexes, built 2026-09-26; a registry that serves only `.proto` source is refused by name. Avro schema resolution against `schema.reader.file` (the specification's rules, aliases included) and nested records as columns were built 2026-09-26 (`AvroResolver`). | `plugins/pravaha-plugin-kafka` | — |
| **B8** | **Built 2026-09-19.** `RANGE (column)` keeps an ordered index over the key's **last** column (design §17.2's `INDEXED BY (user_id) RANGE (window_end)`: `INDEXED BY` is the key, `RANGE` adds the order), so a read that pins the leading columns and bounds the last walks a run instead of the view; a lookup by the whole key is a hash probe, on any view, with nothing declared. Both serve Flight SQL, the REST view read and pgwire, because all three run `ViewQuery`. The index is one entry per visible key, maintained inside `commit()`/`evict()` under the view's own monitor, built on first use and rebuilt after a restore, bounded by the ceiling that already bounds the view, and it does not spill (ADR-049). `WITH (...)` on a plain `CREATE` takes `retention`, `sink` and `keys` — the arguments `pravaha register` already took — read by the parser `CREATE OR REPLACE` uses, with each statement's own vocabulary; an unknown option is `PRV-8017` by name. New code `PRV-2073` for a `RANGE` over a column with no total order here (text, `FLOAT`, `DECIMAL`, `BYTES`, `BOOLEAN`), refused at registration. **Refused, by decision**: a secondary index over a non-key column (design §17.2 calls that row best-effort scan+filter, and such an index has to find the entry to delete from the row's previous values); `INSERT INTO <sink> SELECT` (`PRV-2020`, now raised at the parse tree rather than as an unknown identifier) — it carries neither the query's name nor its key, and the refusal names `WRITING TO`, `WITH (sink = ...)` and `--sink`; `EMIT CHANGES WITH (...)` and the design's `consistency.default`, `parallelism` and `allowed.lateness`. | `pravaha-sql`, the recognizer, `pravaha-serving`, `pravaha-registry` | — |

| **B15** | **Built 2026-09-20**, asked for by the owner outside the waves. `delta-sink`: a continuous query's answer maintained in a Delta Lake table, on Delta Kernel and not Spark. `mode: upsert` (the default) keeps the table equal to the view by `key.columns` — a copy-on-write merge, since Delta has no delete, reading the data files through their key columns alone to find the ones to rewrite; `mode: changelog` appends every change with `_op` and `_weight`. Transactional per checkpoint through a staging directory inside the table, applied as one Delta commit carrying a Delta `txn` action, which is what makes a repeated commit a no-op: exactly once on a node that checkpoints. A concurrent writer inside the commit's window is `PRV-5059`, refused and never retried. New codes `PRV-5056`–`PRV-5059`. Partitioned tables in `delta-sink` and deletion vectors in the `delta` source were added on 2026-09-26. **Left, by decision**: writing deletion vectors, schema evolution and compaction (`OPTIMIZE` and `VACUUM` are Delta's, and Kernel exposes neither). | `plugins/pravaha-plugin-delta` | — |

### Wave 3 — needs wave 2's engine work

| Batch | What | Owns | After |
|---|---|---|---|
| **B9** | **Two thirds built 2026-09-19.** Backfill and cutover control and the backpressure dashboard landed, with journeys 3, 5 and 6 driving the screens instead of asserting their absence. **Left:** the debugger's screen (journey 7), and `ReplacementStatus.history`, which the engine returns and no screen shows — in flight 2026-09-20. | `console/` | B1, B4, B6 |
| **B10** | **Built 2026-09-20.** ADR-039 item 5's leftovers. `CKPT-3` (a closed continuous aggregate re-emits its answer with no retraction), `SINK-3` (no per-sink authorization, and the audit does not record the sink), `pravaha queries` showing a query's sink and a detached sink's `PRV-8009`. | `pravaha-registry`, `pravaha-security`, CLI | — |
| **B11** | **Tenancy and quotas.** Per-tenant isolation and admission quotas in the engine, then the admin screens. Editing grants stays out: grants live in the deployment's identity system. | `pravaha-registry`, `pravaha-security`, then `console/` | B10 |

### Wave 4 — release engineering, and the long tail

| Batch | What | Owns |
|---|---|---|
| **B12** | **Built 2026-09-19.** `deploy/docker/` builds a ~437 MB non-root image (uid 10001, `eclipse-temurin:21-jre-alpine`, the Arrow `--add-opens` on the launcher's exec line, config in three layers, no credential) from artefacts the reactor already produced — not Jib and not distroless, [ADR-047](adr/047-the-image-is-a-dockerfile-over-built-artefacts.md). `deploy/docker/smoke.sh` runs ten steps against a real container. `deploy/helm/pravaha/` is a StatefulSet, because a node claims its state directories by node id (ADR-035); `replicaCount` other than 1 is refused naming [ADR-045](adr/045-cluster-mode-assigns-queries-not-rows.md). `deploy/release/` sets one version across 37 poms, two wheels and the chart, and drives what a release can do offline. Four workflows, with `verify` now asserting the integration tests actually executed and `suites` asserting the browser tests did not skip themselves. `docs/DEPLOYMENT.md` is the page. **Left**: nothing in CI has ever run here — the `verify` integration leg, the JDK 25 leg and the Spring Boot 3.2–3.4 legs are still unrun, and no registry, index, `<distributionManagement>` or signing key exists, so nothing is published. | a new `deploy/`, `.github/workflows` |
| **B13** | **Built 2026-09-20.** The performance gates, on the machine we have. P2's throughput target is reached (about 30 M rows/s warm, 11 M cold, against 1.2 M); **P2's scaling target is not** — eight-lane efficiency measured 28-42 % of linear against a 90 % target, recorded as not reached; P3 is reached and was measured for the first time; ADR-038's Nexmark head-to-head cannot be run here at all, and of its 23 queries **5 run**. `docs/gates/measured-2026-09-20/` carries every number with the machine's load beside it. There is no reference hardware, so P2, P3 and ADR-038's Nexmark comparison are measured on the development machine and reported with it named (owner, 2026-09-19). A target the machine cannot reach is recorded as not reached, with the number, rather than restated as passed. | `pravaha-it` performance packs, `pravaha-benchmarks`, `docs/gates/` |
| **B14** | **The open post-GA findings** — 95 when this was written, 41 on 2026-09-20, which cluster and can be split three ways: `CFG-*` (17, configuration), `STRM-*` and `TIME-*` (24, streams and event time), `API-F*` and `SX-19` (12, API shape and disclosure), `DOCX-*`/`DOCR-*` (8, documentation), `PF-*` (4, performance), `SRC-*`/`SINK-*` (4). The `STRM`/`TIME` cluster ran on 2026-09-19 and `CASE-1`+`TIME-6` followed on 2026-09-20: a windowed query over a stream with no declared event time is now refused at plan time (`PRV-2002`), which is a query that planned before and does not plan now. | by cluster, mostly disjoint |

### Not scheduled

- **Multi-node execution** (ADR-039 item 8) — designed in
  [ADR-045](adr/045-cluster-mode-assigns-queries-not-rows.md), **on hold by the owner**. A node
  refuses to serve `PARTITIONED` until its consumer exists (`PRV-9002`).
- **The manual WCAG 2.2 AA audit** — a person has to do it; the automated half (axe on every page,
  in both themes and both densities) is green.
- **The console's design-system surface** (§23.20) — not built, by decision.

### How this gets built quickly

**Three batches at a time, and the lead never builds.** The lead reviews, merges, runs the gate,
records findings and drills; an agent that finishes hands back and its slot takes the next batch
whose dependencies are met. Nothing waits for a batch it does not depend on.

**The slots are chosen so branches do not collide.** The engine slot is serial because its files
are shared; the other two are disjoint by construction. Where two batches must touch one file (the
console's query page during B5 and B9, say), the lead merges the engine side first and tells the
other agent to rebase — that has been the pattern all day and costs minutes, not a rebuild.

**Every batch carries its own proof.** An agent reproduces a defect before fixing it, writes the
test that fails on the old code, and **seed-proves** it: put the defect back, watch the test fail,
restore. A batch reports exact counts, and a number nobody can reproduce is not a result. Work
against a real store runs under Docker with Testcontainers, never against the owner's containers.

**The gate is the merge criterion, not the agent's own run.** `tools/verify-clean.sh` deletes the
installed artefacts, cleans every `target/`, and rebuilds from the working tree, so a stale class
cannot make a test pass. The console suite and the Python SDK run beside it when either is touched.
A drill follows each green gate, so `main` is never more than one merge behind.

**Documentation moves with the code, in the same commit.** README rows and bullets, the affected
guide, `TROUBLESHOOTING` for a new code, the console's help pages, and an ADR when a decision is
made that the design does not already record. `DocumentationFreshnessTest` fails the build on a
broken reference; it cannot fail on a sentence that is merely untrue, which is why a doc sweep
against the code is part of each batch rather than a phase at the end.

**The register is the memory.** Anything found on the way in — including by an agent working on
something else — becomes a finding with a disposition, and a GA-BLOCKER or GA-REQUIRED one
preempts the queue. Three did today and were fixed the same afternoon.
