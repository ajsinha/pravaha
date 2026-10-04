<div align="center">

<img src="brand/mark.svg" alt="" width="88" height="88">

# प्रवाह · Pravaha

### Ask once. Answer always.

**An embeddable, store-native, incrementally-maintained SQL engine.**

*Pravaha* (Sanskrit: *continuous, uninterrupted flow*) · pronounced *pruh-VAA-huh*

[![Release](https://img.shields.io/badge/release-2.0.0-crimson)](docs/project/RELEASE_NOTES.md)
[![Status](https://img.shields.io/badge/status-wave%2010%20of%2011-blue)](docs/development/HANDOVER.md)
[![Java](https://img.shields.io/badge/Java-25%20LTS-orange)](docs/design/system_design.md#4-language-decision-java-vs-scala)
[![Build](https://img.shields.io/badge/build-Maven-C71A36)](docs/design/implementation_plan.md)
[![License](https://img.shields.io/badge/license-Proprietary-red)](LICENSE)

</div>

---

> **Pravaha 2.0.0** — JDK 25 only ([what 2.0 changes and what 2.x promises](docs/operations/COMPATIBILITY.md)).
> `develop` is 2.0.1-SNAPSHOT: an adversarial QA of 2.0.0 (2026-10-01, 322 cases) opened 50 findings
> and three fix waves closed every one by 2026-10-02 — some of them change an answer
> ([release notes](docs/project/RELEASE_NOTES.md), "Unreleased"; [what 2.0.1 changes](docs/operations/COMPATIBILITY.md#201-fixes-that-change-an-answer)).
>
> **Project status: Wave 10 of 11.** One node: an engine that maintains the answers to registered
> SQL questions as data changes, serves them back by key, writes them to sinks, and survives its own
> restart, at a thread and memory cost that stops following the query count. **Clustering is not
> built**, and a node refuses to start in `PARTITIONED` mode rather than pretend to be.
>
> **Feature-complete for one node as of 2026-09-27:** ADR-039's known gaps are closed or decided, and
> cluster mode — wave 11 — is on hold by the owner's decision ([ADR-039](docs/design/adr/039-ga-includes-the-known-gaps-and-clustering.md)).
> Since then: queries on queries, alerts, a governed catalogue with grants, row filters and column
> masks, a plain-English assistant over any model, BI tools over the PostgreSQL protocol, and
> observability with dashboards and tracing. 1.0.0 (2026-09-30) was the first release with a
> compatibility promise. What remains is the scaling gate and the manual accessibility audit; no
> finding is open. [`HANDOVER.md`](docs/development/HANDOVER.md) has the detail. This file says what is true now, and the
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
`AerospikeContinuousQueryIT`, which needs Docker, over a binding with `deletes: detect` — without
it an Aerospike scan re-reads an updated record as another row, and the registry refuses an
aggregate over it (`PRV-2042`) rather than count the record twice. `SELECT STREAM` is accepted and
redundant: every Pravaha query is continuous. It is registered by name with the columns its view is keyed by —
`CREATE CONTINUOUS QUERY txn_volume KEYED BY (window_end, user_id) AS SELECT …`, from any SQL
client that speaks Flight SQL, the CLI, an SDK, the console or the embedded engine; or as a
registration whose arguments say the same ([`CONTINUOUS_QUERIES.md`](docs/guides/CONTINUOUS_QUERIES.md)
§3, §10.1).

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

<!-- A list, not a table: these entries are paragraphs, and GitHub's mobile app stops rendering a
     table past about 10,000 bytes, taking the rest of this page with it. -->

- **SQL** — Calcite parses and optimises; the plan becomes Pravaha's own operator tree, run over off-heap binary rows. Whole-stage code generation runs roughly **10× the interpreted path**. Projections, expressions, `CASE`, string and numeric functions, `LIKE`, filters, aggregates. What is refused, and why, is in [`CONTINUOUS_QUERIES.md`](docs/guides/CONTINUOUS_QUERIES.md), checked against the planner by a test
- **Continuous queries** — Registered with a name and a key; paused, resumed, dropped. Identical questions from one tenant share one computation under many names, matched on the normalised plan, so ten desks asking the same thing cost one read of the source. Each tenant is admitted by quota (queries, view state) and refused by name at the limit ([ADR-050](docs/design/adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md))
- **Windows and event time** — Tumbling and hopping (sliding) windows, with slicing, a hop's windows starting on multiples of its slide as SQL's `HOP` does; a hop so fine a row would land in more than `pravaha.lane.max-windows-per-row` windows is refused (`PRV-3026`), and session windows are refused (`PRV-2020`). A query derives its watermark from the event-time column its stream declares, a quiet partition stops holding the rest back — and when every partition has gone quiet the watermark catches up to what the ones that spoke said — and a window publishes when time passes its end
- **Corrections** — Late data within a stream's declared allowed lateness (`pravaha.streams.<name>.allowed-lateness`, or `allowedLateness` on `POST /api/v1/streams`; zero by default) reopens a closed window as a retraction plus the corrected answer. Every change carries a Z-set weight, through the engine, across the wire and into both SDKs
- **Joins** — Stream-to-stream, and temporal lookup joins against a JDBC or Aerospike dimension table
- **Sources** — Filesystem (bounded, or followed like `tail -f`), feedfile directories (CSV, Parquet), Delta Lake (deletion vectors included), JDBC polling, Aerospike scans, Cassandra `token()`-range scans (either retracting deleted and changed rows with `deletes: detect`), **PostgreSQL change data capture** (`postgres-cdc`: logical replication, an insert at +1, a delete as the whole old row at −1, an update as both, whole transactions, exactly once — the slot is confirmed only at checkpoints), **MySQL change data capture** (`mysql-cdc`: the row-based binlog read as a replica, the same weights, whole transactions, exactly once from a binlog file and offset, or a GTID set that survives a failover; changes only, no initial snapshot yet), and **Kafka topics** (`kafka`: one reader per partition, exactly once from the checkpoint's offsets, `read_committed` by default; JSON rows by column name, or `kafka-sink`'s changelog with its retractions). Filters are pushed into JDBC and Aerospike, and into Cassandra on the key (the whole partition key by equality, then clustering restrictions), projections into all three, and a continuous `COUNT`/`SUM` into JDBC as one pre-combined partial per polled page (over a watermark written only on insert: `watermark.moves.on.update: false`). Every source stamps a row with the stream's declared event-time column. One reader per source binding feeds every query bound to it, pushing the OR of their filters — Kafka and files read once through included, shared at an exact position ([ADR-054](docs/design/adr/054-an-ordered-source-is-shared-at-an-exact-seam.md)); a JDBC, CDC or Delta source, or a followed file, keeps a reader per query. A source that goes away under a running query — a file deleted, a Kafka topic deleted (`PRV-5130`), a CDC slot dropped (`PRV-5117`) — stops its feed by code and turns node health `DEGRADED` rather than going quiet. Connections to JDBC, PostgreSQL CDC, Aerospike, Cassandra and Kafka can be encrypted ([`CONNECTOR_TLS.md`](docs/guides/CONNECTOR_TLS.md))
- **Serving** — The maintained view is read by key or scanned with SQL, and subscribed to per commit — or from a snapshot: the view at a commit, then every commit after it, with none lost between (`subscribeFromSnapshot`, `snapshot=True`, `pravaha subscribe --snapshot`). Over **Arrow Flight SQL** (Java SDK, Python SDK, CLI, console), and over the **PostgreSQL wire protocol** (`pravaha.pgwire.enabled`, off by default) so `psql`, DBeaver, Grafana, **Power BI** (Import and DirectQuery, through its own PostgreSQL connector) and any Postgres driver can read a view — simple and extended protocol, text and binary results, `\d`, TLS
- **Sinks** — A registration can also name a sink (`pravaha register --sink`), and every commit of its view is written there, retractions included. Refused at registration, before the sink opens: a query that revises its answer against an append-only sink (`PRV-2041`), any sink but an upsert over a source that repeats rows (`PRV-2042`), and a sink whose configured columns or key differ from the query's (`PRV-8010`). Shipped: `filesystem` (append-only), `aerospike-sink` (upsert and delete by key), `jdbc-sink` (a table in any JDBC database: upsert and delete by key, or append), `kafka-sink` (a Kafka topic: keyed upserts in JSON, Avro or Protobuf — keys too — with a tombstone for a retraction, schema ids checked against a registry, or an explicit changelog) `delta-sink` (a Delta Lake table kept equal to the view by key, or a changelog of every change with its weight; one Delta commit per checkpoint, on Delta Kernel and not Spark) and `iceberg-sink` (an Apache Iceberg table on the local filesystem, kept equal to the view by key through equality deletes, or a changelog; one Iceberg snapshot per checkpoint, on iceberg-core and not Spark). Delivery is stated per sink at registration: a transactional sink such as `jdbc-sink`, `kafka-sink`, `delta-sink` or `iceberg-sink` is prepared at each checkpoint's cut and committed once the checkpoint is durable — exactly once; an idempotent upsert sink such as `aerospike-sink` is effectively once; a plain append sink such as `filesystem` is at least once
- **State** — Off-heap: join indexes and windowed-aggregate accumulators live in `RowStore` blocks behind open-addressed tables, `COUNT(DISTINCT)`'s values included. With `pravaha.state.spill.*` set, state past its memory ceiling spills to memory-mapped files and the query slows instead of stopping. Per-query gauges show state approaching its ceiling
- **Blue/green replacement** — A registered query's SQL is changed without taking its answer away: `CREATE OR REPLACE CONTINUOUS QUERY`, `pravaha replace`, both SDKs, the Flight actions and `/api/v1/queries/{name}/replacement`. The new version runs beside the old one, replays the source from the beginning, **splices onto the live stream at the exact position the running version has reached**, and takes the name only when the two have consumed the same input — so a reader sees the old answer up to the seam and the new one after it, with no gap and nothing counted twice. Subscribers are told the view was replaced (`PRV-4019`) rather than handed another query's changes; a sink follows the name at a checkpoint boundary and is sent only the difference; the replaced version keeps running for an hour, so a rollback is one swap. The backfill is throttled, pausable and watched by eight gauges, and a replacement in flight survives a restart ([ADR-046](docs/design/adr/046-a-replacement-meets-the-running-version-at-a-position.md))
- **Observability** — Grafana dashboards, Prometheus alert rules (Helm `PrometheusRule` too), JSON logs with correlation and trace ids, and OpenTelemetry tracing over OTLP; metrics for alerts, catalogue decisions and the assistant; and Prometheus per query: rows in, view size, state against its ceiling, watermark lag, checkpoint health, commit latency as an exact mean, a replacement's backfill progress, and — since B6 — **backpressure in time rather than in refusals**: how often and how long a writer into the query's lanes had nowhere to put a row, the share of wall clock that is, and the inbox's depth now. With `pravaha.metrics.operators` on (off by default: it costs about 8 % of throughput on the reference machine), `GET /api/v1/queries/{name}/plan` also carries **rows in, rows out, state bytes, watermark and a sampled self time per plan node**, and names the bottleneck operator. Where a number is not measured the API says so rather than reporting zero
- **Queries on queries** — A continuous query can read another query's view: it follows that view's answer — its snapshot, then each commit's rows leaving and entering — exactly across restarts, up to eight levels deep; a view others read cannot be dropped, and a cycle is refused ([ADR-056](docs/design/adr/056-queries-on-queries.md))
- **Alerts** — `CREATE ALERT … ON <view> NOTIFY <channel>` fires when a key's row enters the answer and clears when it leaves, with flapping control, dedupe, snooze and acknowledgement; state is journalled so a restart neither re-fires nor loses a clear; signed webhooks (Slack format too) and a log channel ([ADR-057](docs/design/adr/057-alerts.md))
- **Governed catalogue** — `tenant.namespace.object` names with owners, descriptions and tags; allow-only grants (`GRANT`/`REVOKE`, nine privileges, inherited down namespaces) and `SHOW EFFECTIVE ACCESS`; row filters and column masks as catalogue policies, bound to objects or tags and applied on every read, subscription, chained query and the PostgreSQL gateway; a revoke ends an open stream; user attributes as claims ([ADR-059](docs/design/adr/059-the-pravaha-catalog-governs-live-answers.md))
- **Lanes** — Each query owns a lane until a node hosts 64 (`auto`), then new ones share; `WITH (lane = 'dedicated')` keeps one apart; an administrator's rebalance moves shared queries onto their own lanes without losing an answer
- **The assistant** — Plain English to a continuous query through any model and provider (Anthropic, OpenAI, OpenAI-compatible, Ollama, or a plugin), with the engine as the judge and a person confirming; switched at runtime from Admin · AI models; `pravaha ask`, `explain-sql`, `why`, and an evaluation harness ([ADR-058](docs/design/adr/058-plain-english-to-continuous-sql.md))
- **BI tools** — Power BI and other PostgreSQL clients read views through the gateway (Import and DirectQuery), signed in with an API key or session token, under the same grants, filters and masks
- **Time-travel debugger** — A query is forked from one of its retained checkpoints into a second copy that reads the same sources from the offsets that checkpoint recorded — with **every sink disabled**, its view in no catalogue and its lanes its own, so the live query, its view and its subscribers see nothing. It is stepped by hand: one row, N rows, to the next commit, to a watermark, or until a column of the view crosses a value. Each step reports the rows that entered with their weights, **every operator's rows in and out**, the view's changes, and where event time stands — which is what tells a filter that rejected the row apart from an aggregate that produced a zero delta. An operator's state is readable, bounded and paged, without emitting or evicting anything. Two sessions over one checkpoint given the same steps report identically. The session exports as a **self-contained JUnit test** whose expectation is rehearsed at export time rather than asserted, so the incident becomes a regression test that compiles and passes. `pravaha debug`, Flight actions, `/api/v1/debug/*`, both SDKs, and the console's **Debugger** screen at `/queries/{name}/debug` ([ADR-048](docs/design/adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md))
- **Recovery** — Checkpoints hold operator state, source offsets and the served view, cut at one point across every input (ADR-008), so a restart resumes rather than replaying from scratch or starting empty. The registry journal brings back every registration, and its sink
- **Survival** — A node claims the directories it writes, so two nodes cannot silently share state (`PRV-4003`) — nor two engines in one JVM. A standby takes over when the claim goes stale and reports what the takeover cost. Every checkpoint carries a CRC32C, and a damaged one is skipped for the one before it (`PRV-4094`); damage in the middle of the registry journal refuses the start (`PRV-8005`); a registration refused at recovery stays listed `FAILED` with its code and health `DEGRADED`. Input that cannot be decoded, and a row whose evaluation fails before state (a division by zero, an overflow, `PRV-3027`), go to a dead-letter queue instead of ending the query
- **Many queries on one node** — A fixed pool of one thread per core drives every lane, and the watermark and checkpoint clocks are one timer for the process: **200 queries add 24 platform threads** on 24 cores, where they once added 400. About **1 MiB off-heap per idle query** on a lane of its own, and every component reports its own bytes. With lane sharing on, **1,000 queries over one source run on 8 lanes, and each row is written into them 8 times instead of 1,000**
- **Security** — Authentication through one verifier for every transport, authorization on what a query reads rather than what it is called, row filters, prepared statements, audit, and a node that refuses to start open unless told to. The engine keeps its own users, passwords (Argon2id), API keys (shown once, scoped, expiring, rotatable) and sessions ([ADR-052](docs/design/adr/052-the-engine-is-the-identity-authority.md)); failed sign-ins bar the address they came from and every refusal answers alike, so lockout neither names users nor locks them out at will; revoking a credential ends the PostgreSQL connections and Flight subscriptions it opened at their next statement; what an unauthenticated client can make a node hold — connections, messages, request bodies, sign-ins in flight — is bounded by configuration. The console signs each person in against the engine, keeps the session in its own process behind an opaque cookie and sends CSP and frame headers, and the CLI has `pravaha login`, `user`, `key` and `session`. The build refuses a secret written into shipped configuration
- **Embedding** — `PravahaEngine` runs the whole loop inside an application — streams, plugin bindings, continuous queries, pushed rows, SQL reads, change subscriptions, journal and checkpoints — with no Spring and no network. `pravaha-spring-boot-starter` makes it a bean, with `PravahaTemplate` and `@PravahaListener` delivering committed changes, retractions included, to a method, a `@PravahaTest` slice for testing it, and an actuator endpoint and health contribution when Actuator is present. See [the user guide](docs/guides/USER_GUIDE.md)

## What is not built, or not finished

**Feature-complete for one node** (2026-09-27). Every gap the plan listed is built or has been
decided: tranches A to C in [`REMAINING.md`](docs/development/REMAINING.md) are done, and each entry's full text is
in [`LIMITS.md`](docs/guides/LIMITS.md). What is left is below.

**Deferred or dropped by decision.**

- **Multi-node execution.** Membership, fenced partition leases (proved against a real ZooKeeper
  ensemble), and rebalance and handoff are built as libraries, and no node consumes them. A node
  refuses `PARTITIONED` mode (`PRV-9002`) rather than pretend. The owner put this on hold; the
  console's cluster screens wait for it.
- **MFA and single sign-on.** Dropped by the owner: users, passwords, API keys and sessions are the
  authentication Pravaha keeps ([ADR-052](docs/design/adr/052-the-engine-is-the-identity-authority.md)).

**First versions: what the newest pieces do not do yet.**

| Piece | Not yet |
|---|---|
| `mysql-cdc` | An initial snapshot (`snapshot.mode: initial` is refused), TLS. GTID positions (`gtid_mode = ON`) survive a failover to a caught-up replica; a checkpoint written before them stays a file position |
| `postgres-cdc`, `mysql-cdc` | Replacing a running query in place: `CREATE OR REPLACE` is refused (`PRV-4018`) because the running version is the slot's one reader; drop and register |
| `iceberg-sink` | Object stores, catalog services, partitioned tables, schema evolution; it writes local-filesystem tables. Upsert mode holds at most `upsert.max.keys` changed keys per checkpoint and refuses past that (`PRV-5143`) rather than spilling |
| One reader per ordered source ([ADR-054](docs/design/adr/054-an-ordered-source-is-shared-at-an-exact-seam.md)) | Delta and JDBC, until their positions are shown to be totally ordered; CDC sources are read by one query per binding: a binding names one slot or replica `server.id`, and a second, different query over it is refused at registration (`PRV-8028`); bind the table again for a second query |
| Equality index ([ADR-055](docs/design/adr/055-an-equality-index-over-a-column-outside-the-key.md)) | A way for a user to see which access path a read took |

**Not yet proven.**

- The eight-lane scaling target: measured at 28–42 % of linear against 90 %, on a laptop, with no
  reference hardware (below).
- The manual WCAG 2.2 AA audit, which is a person's task.
- The [findings register](docs/project/qa/FINDINGS.md) holds 553 findings, 533 fixed and one open, POST-GA (SDKDEADLINE-1), on 2026-10-03; the adversarial QA's reproductions are kept as opt-in suites
  ([TESTING](docs/development/TESTING.md#the-adversarial-suites)).

**Boundaries: limits of a store, a format or a recorded decision.** More code would not remove these.

- `MIN` and `MAX` cannot be retracted incrementally, so they are never pre-combined at a source,
  and over a source that retracts they are refused at registration (`PRV-2076`).
  An Aerospike partial aggregate would need UDFs installed on the customer's cluster.
- Transactional sinks stage each checkpoint and apply it once the checkpoint is durable. Kafka and
  Delta have no prepare that a restarted writer could commit. End-to-end delivery is still capped by
  whether the source can rewind.
- There is no RocksDB, by decision ([ADR-044](docs/design/adr/044-no-rocksdb-the-mapped-tier-is-l1.md)).
  The memory-mapped tier is for surviving state larger than memory, not for capacity; both
  measurements are in the ADR.
- Kafka's `lz4` codec is refused on both sides: it needs lz4-java, native code the build refuses
  ([ADR-053](docs/design/adr/053-native-code-only-where-java-cannot.md)). `snappy` and `zstd` are read and
  written, on the platforms their native libraries are built for.
- Formats and stores: a proto3 scalar without `optional` has no NULL; an upsert tombstone does not say
  which row it deletes; a `TRUNCATE` names no rows to retract; Delta `OPTIMIZE` and `VACUUM` belong
  to an engine that has them.
- Grants live in the deployment's policy, not in the engine, so the console shows them and does not
  edit them.
- The snapshot-and-change-feed splice (`SplicedReader`) stays unwired: it keeps the newest row per key,
  which double-retracts on a weighted changelog, and `postgres-cdc`'s own `snapshot.mode: initial` is
  already exact. A replacement splices at an offset (ADR-046); `backfill.adaptive` stays refused.

## Performance: what is measured, and what cannot be here

**The requirement is about 1,000 rows per second** ([ADR-042](docs/design/adr/042-the-throughput-bar-is-the-requirement.md)),
because Pravaha maintains answers to registered questions rather than moving bulk data. The design's
original figures — 1.2 M rows/s per lane for Profile A, 350 k for Profile B, ≥ 90 % scaling from one
lane to eight — are kept, unchanged, as the gate criteria.

**On 2026-09-20 they were measured on the development machine**, because there is no reference
hardware and there is not going to be any. Profile A's per-lane throughput and Profile B's are both
**reached**, with room; the scaling criterion is **not reached** — 28–42 % of linear at eight lanes
against a target of 90 %, recorded as measured rather than restated. The machine is a 12-core
heterogeneous laptop part with SMT2 and one shared frequency envelope, it was running other work
throughout, and the load average is printed beside every figure. **None of those numbers is
reference-hardware evidence and none is quoted as the engine's capability.**
[`docs/project/gates/measured-2026-09-20`](docs/project/gates/measured-2026-09-20/README.md) has them all with their
conditions, and says plainly what a reader must not conclude from them.

Also measured there: **5 of Nexmark's 23 published queries ran** at the 2026-09-20 measurement, and
**12 run as of 2026-09-26** after the SQL batch (self joins, top-N, exact DECIMAL). Win condition W5's
head-to-head against Flink has still not been run, and the eleven that do not run are missing SQL
rather than missing speed.

What this machine reported earlier: the lane machinery runs at about **21 M rows/s**, and the cost
of a query — threads, off-heap bytes, file descriptors, registration time — which `NodeScaleTest`
and `SourceScaleTest` measure. A thousand distinct continuous queries register in 3.7 ms each and
hold 61 MiB off-heap at the advised inbox sizing. The evidence packs in
[`docs/project/gates`](docs/project/gates/) say what was and was not measured, wave by wave.

## Try it

Two routes, the same result — and in both, everything the engine and the console write lives under
one directory, `PRAVAHA_HOME` (`/opt/pravaha` in a container), owned by you:

**With Docker only** (no JDK, no Python on the machine) — the engine, the console and Kafka, with a
sample continuous query registered end to end:

```bash
docker build -t pravaha/pravaha-server:local .                 # the engine, built from source inside Docker
deploy/docker/console/build.sh --tag pravaha/pravaha-console:local
tools/docker-env.sh                                            # .env and pravaha-home/, as you
docker compose -f deploy/docker/compose/docker-compose.yml --profile seed up -d
docker compose -f deploy/docker/compose/docker-compose.yml run --rm cli query \
  --sql "SELECT customer, COUNT(*) AS orders, SUM(amount) AS spend FROM orders_live GROUP BY customer"
```

The console is then at <http://localhost:17070>. Step by step, test suites included:
[`docs/development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md`](docs/development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md); the reference:
[`docs/operations/RUNNING_IN_DOCKER.md`](docs/operations/RUNNING_IN_DOCKER.md).

**Without Docker** (JDK 25, and Python 3.11+ for the CLI and console) — from a checkout, as below, or
from a distribution unpacked anywhere (`deploy/release/dist.sh`; [`docs/operations/DEPLOYMENT.md`](docs/operations/DEPLOYMENT.md),
"Without Docker: the same layout"). The walkthrough is [docs/development/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md](docs/development/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md).

```bash
./mvnw -q -DskipTests install

# Register a continuous query, ask its view a question, then watch it change.
pravaha query     --sql "CREATE CONTINUOUS QUERY user_volume KEYED BY (user_id) AS \
                    SELECT user_id, SUM(amount) AS total FROM txn \
                    GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id"
pravaha query     --sql "SELECT total FROM user_volume WHERE user_id = ?" --params u1
pravaha subscribe --view user_volume --filter user_id=u1
```

`SHOW CONTINUOUS QUERIES` and `DROP` / `PAUSE` / `RESUME CONTINUOUS QUERY` manage what is running —
or `pravaha queries`, `pause`, `resume` and `drop --yes`, with `pravaha register` taking a registration
as arguments, and `pravaha status`, `describe`, `lanes` and `audit` asking the node's HTTP API.
`pravaha` is the Python CLI, built on the Python SDK (`pip install "pravaha[flight]"`, or `bin/pravaha`
from a checkout; [`docs/guides/CLI.md`](docs/guides/CLI.md)); planning and running SQL with no server is the Java
tool `pravaha-engine` (`validate`, `explain`, `run`). The [**Quickstart**](docs/guides/QUICKSTART.md) takes a clone to a running,
changing view in about ten minutes.

## How it is built

- **Language** — Java 25 LTS, one language. Calcite plans; Pravaha's own operators execute. [Why not Scala →](docs/design/system_design.md#4-language-decision-java-vs-scala)
- **Execution** — Whole-stage code generation (Janino) over binary flyweight rows in off-heap arenas. No `Map<String,Object>`, no boxing, no allocation on the hot path
- **Concurrency** — Partitioned lanes and the single-writer principle: one inbox, one state slice and one timer wheel per lane, and exactly one thread driving a lane at a time, drawn from a fixed pool of one per core. No locks in steady state
- **Incrementality** — Z-sets and DBSP-derived operators: work is proportional to what changed, not to how much data exists
- **Correctness** — Exactly-once **state**: a checkpoint holds every source between rows, cuts every lane at one point and records the offsets of that same point (ADR-008). Output is cut at that point too: exactly once to a transactional sink, effectively once to an idempotent one, at least once to a plain append
- **Deployment** — Three ways to run one engine. In process with no Spring and no network (`pravaha-embedded`: declare streams, register, push rows, read, subscribe, persist); in a Spring Boot application of your own (`pravaha-spring-boot-starter`: an engine bean from `pravaha.*`, `PravahaTemplate`, `@PravahaListener`); or as a server — `pravaha-server`, a Spring Boot node with the engine, Flight SQL, the PostgreSQL gateway and a plain `/status` page — with the console as a separate Python process built on the published SDK, so it cannot reach past the public API (ADR-024). The engine core contains no Spring, enforced by the build (ADR-019). Packaged: a non-root container image on a Java 25 glibc base, with native code limited to Parquet's two codecs and enforced by the build (ADR-053), built from the reactor's own artefacts (`deploy/docker/`, ADR-047), and a Helm chart that installs **one** node as a StatefulSet -- because a node claims its state directories by node id, and multi-node is on hold (`deploy/helm/pravaha/`, ADR-045). [Deployment →](docs/operations/DEPLOYMENT.md)

Rows are read and subscribed to over Flight SQL or the PostgreSQL wire protocol. Everything that
manages the engine is also on REST under `/api/v1`: status, streams, queries (validate, explain,
plan, replacement, backfill, dead letters), views, sinks, plugins, tenants, the debugger and the
audit trail. It is described by the OpenAPI document the server publishes, and the
[Python API guide](docs/guides/PYTHON_API_GUIDE.md) has a worked call for each.

## The console

A Python FastAPI application on the published SDK and the engine's public REST API, server-rendered,
with interactive islands in plain ES modules — no bundler, no Node toolchain — and every asset,
Monaco, ECharts and ELK included, vendored so it runs air-gapped.

```bash
cd console && make install && make run     # :17070, engine Flight at :19090 and HTTP at :18080
```

Each persona lands on its own screen. An analyst gets a **SQL Workbench**: Monaco with catalog-aware
completion, validation as you type with inline diagnostics and one-click fixes, the plan drawn as a
graph, a result grid, registration with keys picked by name, and drafts in tabs. A developer gets
**Views**: point queries and copy-paste client code for the Java and Python SDKs, `psql` and the CLI.
An operator gets **Operations**: the engine's metrics read into a verdict — is everything healthy,
and if not, where — with per-query throughput, state against ceiling and watermark lag. Any view can
be watched **live**, every committed change shown with its `+1`/`−1` weight. The design follows
MAYA's: a mega-menu (Catalog, Workbench, Operate, Admin, Help), four themes — Crimson, Dark, Blue,
Green — and a public bar when signed out. **Alerts**, **Lanes**, **Grants**, **Row filters & masks**
and **AI models** have their own screens, and the workbench can **Describe it** in plain English. A **catalog**, a Ctrl-K
command palette, a first-run guide from a stream to a live view, and every `PRV` code resolved to
its documentation complete it. A **help centre** served from the engine's documentation has
tutorials, case studies, a FAQ, the About page and the competitive landscape. Everything but the landing page, the documentation and the health
probes needs a session. An administrator gets **Admin**: what the engine's policy lets the console's
identity do, and the **audit trail** — filterable, paged, every filter in the URL — which the engine
serves only to a principal its policy lets read it (`SecurityPolicy.mayReadAudit`), recording every
attempt. Where a screen needs an API the engine does not have yet, it says which.
An operator also gets **backfill and cutover** ([ADR-046](docs/design/adr/046-a-replacement-meets-the-running-version-at-a-position.md)):
a new version started beside the running one, what its backfill has read — and no ETA and no
percentage, because a source does not say how much history it holds — a throttle that may only be
lowered, and a cutover and a rollback each confirmed by the typed name, with the rollback window
shown as a time and said to have closed when it has.
All eight §23.18 journeys run in headless Chrome, end to end; light, dark and compact
density are photographed and audited by axe. The manual WCAG 2.2 AA audit is not done.
[How it is built →](console/README.md)

## Documentation

| Start here | |
|---|---|
| [Quickstart](docs/guides/QUICKSTART.md) | Clone to a running continuous query |
| [Concepts](docs/guides/CONCEPTS.md) | The ideas everything follows from; most surprises are one of these working correctly |
| [User guide](docs/guides/USER_GUIDE.md) | The whole surface, task by task, in Java, Python and the shell |
| [Case studies](examples/case-studies/) | Thirteen worked systems: a data model, the data, the continuous queries and the app code, each run and checked by the build |
| [CLI](docs/guides/CLI.md) | `pravaha`, the command line for a running engine, and `pravaha-engine` for SQL with no server |
| [The assistant](docs/guides/ASSIST.md) | `pravaha explain-sql` and `pravaha why` through any model, with the engine as the judge (ADR-058) |
| [The deck](docs/publications/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx) | 99 slides for 2.x: why, the vocabulary, correctness, scale, replacement, queries on queries and alerts, connectors, security and governance, operations, case studies, the evidence — the adversarial QA round against 2.0.0 among it — and what 2.x promises. Generated from [`tools/deck`](tools/deck/GUIDE.md) |
| [The Medium post](docs/publications/medium/pravaha-medium-post.md) | The design and its trade-offs for engineers, what 1.0 added, 2.0 on Java 25 and the adversarial QA round run against it, 20 diagrams and three case studies worked end to end; also [as one self-contained page](docs/publications/medium/pravaha-medium-post.html); [how to publish it](docs/publications/medium/README.md) |
| [Python API guide](docs/guides/PYTHON_API_GUIDE.md) | Every REST and SDK call with a Python sample, for integration |
| [Running from an IDE](docs/development/DEVELOPING_IN_AN_IDE.md) | The server in IntelliJ IDEA and the console in PyCharm |
| [Testing](docs/development/TESTING.md) | Every test tier, with and without Docker, what skips and why, measured times; and a [step-by-step build and test without Docker](docs/development/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md) |

| Reference | |
|---|---|
| [Streams, queries and SQL](docs/guides/CONTINUOUS_QUERIES.md) | Declaring streams and sources, registering, reading, sinks, windows, joins — and every SQL construct that works or is refused |
| [Connectors](docs/guides/CONNECTORS.md) and [TLS](docs/guides/CONNECTOR_TLS.md) | Building a source or sink plugin; encrypting every connection |
| [Operations](docs/operations/OPERATIONS.md) | Configuration, sizing, sinks, state, recovery, what to watch |
| [Running in Docker](docs/operations/RUNNING_IN_DOCKER.md) | The two images, the `/opt/pravaha` layout, the compose stack and its profiles, ownership, backup, troubleshooting |
| [Deployment](docs/operations/DEPLOYMENT.md) | The container image and the Helm chart: volumes, ports, environment, probes, upgrading a node, the release procedure, and what the chart deliberately does not do |
| [Troubleshooting](docs/guides/TROUBLESHOOTING.md) | Every `PRV-` code |
| [Security](docs/operations/SECURITY.md) | Authentication, authorization, row filters, audit |
| [Compatibility](docs/operations/COMPATIBILITY.md) | What 2.0 changes (Java 25), which 2.0.1 fixes change an answer, what 2.x keeps stable, what is experimental, which clients work with which nodes, upgrading from 0.2.x |
| [Known limits](docs/guides/LIMITS.md) | What is not built, whether it could be, and what is a boundary |

| How and why | |
|---|---|
| [Architecture](docs/design/ARCHITECTURE.md) | How it is put together, and why each part is shaped that way |
| [System design](docs/design/system_design.md) | The full specification |
| [Decision records](docs/design/adr/) | Every architectural decision, including the ones later reversed |
| [Research paper](docs/publications/research/continuous-queries-as-maintained-answers.pdf) · [article](docs/publications/research/continuous-queries-as-maintained-answers-article.md) | *Continuous Queries as Maintained Answers*: the model, and why subscription, recovery, reader sharing and replacement are exact — third edition for 2.x, adding the adversarial QA round against 2.0.0 and the semantics its fixes changed; each claim marked with the test that carries it, or marked argued |
| [Handover](docs/development/HANDOVER.md) | Current state, and what to pick up next |
| [What is left](docs/development/REMAINING.md) | The build strategy for the gaps, in tranches |
| [Release notes](docs/project/RELEASE_NOTES.md) | What each tagged release contains |
| [Findings](docs/project/qa/FINDINGS.md) | Every defect found, and what happened to it |
| [Gate records](docs/project/gates/) | Evidence packs, wave by wave |
| [Implementation plan](docs/design/implementation_plan.md) · [Original SRS](docs/design/initial_req.md) | The plan, and the draft this design supersedes |

Parts of this documentation are checked by the build rather than by memory: the SQL in
`CONTINUOUS_QUERIES.md` and the case studies is planned against the real engine; the code table in
`TROUBLESHOOTING.md` must match the declared error codes in both directions; the quickstart's
serverless commands are run and their output compared; every module must be described, every cited
ADR must exist, and this file's badge, status line and roadmap must agree; every relative link and
anchor in every markdown file must resolve; every lane setting and per-query gauge must be named in
`OPERATIONS.md`; the dialect guide's claims and the documented limits are run against the engine;
the console's help topics are checked against the code; and the findings register's header must
match its entries. Prose accuracy beyond that is not checkable, which is why the status sections above are kept
short.

## Building

```xml
<groupId>com.ash.messaging</groupId>
<artifactId>pravaha</artifactId>
<version>2.1.1-SNAPSHOT</version>
```

Base package `com.ash.messaging.pravaha`. Requires **JDK 25**, for building and running alike: every module, the API and the Java SDKs included, targets Java 25 ([ADR-061](docs/design/adr/061-jdk-25-is-the-baseline-from-2-0.md), from 2.0; 1.x ran on 21). The Maven wrapper is vendored.

```bash
./mvnw clean verify                                  # full build
./mvnw -T1C -DskipITs -Dbenchmarks.skip=true test    # fast inner loop
./mvnw -Pit verify                                   # + container-backed tests (Docker), as the nightly workflow runs it
./mvnw -Pall verify                                  # + the benchmarks module
./mvnw -Pep clean test-compile                       # Error Prone + NullAway (docs/development/TESTING.md)
tools/verify-clean.sh                                # the gate: offline, no stale jars, + the Python SDK's suite
```

The gate runs the Python SDK's tests too (`-Ppython`, the console's suite left out), from
`sdk/python/.venv` — make it with `make install` in `sdk/python`, name another with
`PRAVAHA_SDK_PYTHON`, or leave the suite out with `PRAVAHA_GATE_SDK=0`. A missing interpreter fails
the gate by name rather than skipping it.

What each tier needs and skips, the container-backed plugin tests (`-Pit` with Docker), and the
Python and console suites: [docs/development/TESTING.md](docs/development/TESTING.md).

**Modules**, in build order — checked against `pom.xml` by `DocumentationFreshnessTest`:
`pravaha-bom`, `pravaha-api`, `pravaha-common`, `pravaha-algebra`, `pravaha-catalog`,
`pravaha-sql`, `pravaha-runtime`, `pravaha-codegen`, `pravaha-state`, `pravaha-backfill`,
`pravaha-security`, `pravaha-identity`, `pravaha-serving`, `pravaha-cluster`, `pravaha-registry`, `pravaha-flight`,
`pravaha-pgwire`, `pravaha-connect`, `pravaha-testkit`, `pravaha-benchmarks`, `pravaha-it`,
`pravaha-bindings`, `pravaha-embedded`, `pravaha-cli`, `pravaha-server`, `pravaha-spring-boot-starter`.

**Plugins:** [`filesystem`](plugins/pravaha-plugin-filesystem), [`delta`](plugins/pravaha-plugin-delta),
[`iceberg`](plugins/pravaha-plugin-iceberg), [`feedfile`](plugins/pravaha-plugin-feedfile), [`jdbc`](plugins/pravaha-plugin-jdbc),
[`aerospike`](plugins/pravaha-plugin-aerospike), [`cassandra`](plugins/pravaha-plugin-cassandra),
[`kafka`](plugins/pravaha-plugin-kafka), [`postgres-cdc`](plugins/pravaha-plugin-postgres-cdc),
[`mysql-cdc`](plugins/pravaha-plugin-mysql-cdc),
[`cluster-zookeeper`](plugins/pravaha-cluster-zookeeper).

**SDKs:** [`sdk/pravaha-sdk-java`](sdk/pravaha-sdk-java),
[`sdk/pravaha-sdk-java-flight`](sdk/pravaha-sdk-java-flight), [`sdk/python`](sdk/python). The
console is its own artefact in [`console`](console).

## Roadmap

| Wave | Weeks | Milestone | |
|---|---|---|---|
| 1 | 1–2 | Foundations; deterministic harness | ✅ `M1` |
| 2 | 3–5 | Vertical slice; **go/no-go on the incremental core** | ✅ `M2` |
| 3 | 6–11 | Codegen, lanes, exchange — Profile A ≥ 1.2 M rec/s/lane | ✅ built · gate P2 throughput reached here 2026-09-20, **scaling not reached** ([numbers](docs/project/gates/measured-2026-09-20/README.md)) |
| 4 | 12–18 | Windows, watermarks, late data, tiered state | ✅ built, with a memory-mapped L1 instead of RocksDB (ADR-044) · gate P3 reached here 2026-09-20 ([numbers](docs/project/gates/measured-2026-09-20/README.md)) |
| 5 | 19–25 | Joins, Aerospike, checkpointing and recovery | ✅ built |
| 6 | 26–32 | Backfill, blue/green, serving layer — **first defensible demo** | ✅ built · blue/green reachable from SQL, the CLI, both SDKs and the API ([ADR-046](docs/design/adr/046-a-replacement-meets-the-running-version-at-a-position.md)) |
| 7 | 33–38 | Flight SQL, SDKs, security, registration, subscriptions, console | ✅ built |
| 8 | 39–45 | Survival on one node — state ownership, checkpoint barriers, standby ([ADR-035](docs/design/adr/035-wave-8-is-survival-not-distribution.md)) | ✅ built · gate P7 passed |
| 9 | — | One node, thousands of queries ([ADR-036](docs/design/adr/036-one-node-thousands-of-queries.md), [ADR-037](docs/design/adr/037-state-that-degrades-instead-of-dying.md)) | ✅ built · lane sharing by stream (LANE-2) |
| 10 | 46–53 | GA: ADR-039's known gaps closed — one node feature-complete ([ADR-039](docs/design/adr/039-ga-includes-the-known-gaps-and-clustering.md)) | ✅ built 2026-09-27 · then queries on queries, alerts, the catalogue, the assistant, BI access and observability ([ADRs 056–059](docs/design/adr/)) |
| 11 | 54–62 | Cluster mode | ▫️ not started — on hold by the owner's decision |

Waves 10 and 11 were redefined twice: [ADR-038](docs/design/adr/038-one-node-ga.md) moved the time-travel
debugger and the Nexmark comparison out to the roadmap, and ADR-039 put the known gaps and cluster
mode in their place. The debugger has since been built anyway
([ADR-048](docs/design/adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md)), its console
screen included. Wave 10 closed ADR-039's known gaps on 2026-09-27; wave 11, cluster mode, is on
hold: the membership, lease and handoff libraries exist and no node uses them. "Built" means the code is there and tested; it does not mean a
performance gate passed.

Work happens on `develop`, and `main` is fast-forwarded to it after each gated change. The newest
release is `v2.0.0`, one node on JDK 25 (`develop` carries 2.0.1's fixes), with a [compatibility promise](docs/operations/COMPATIBILITY.md): `deploy/release/release.sh` cuts a release, and `deploy/qa/bundle.sh`
packs the server and console images and their YAML files into one files-only bundle for a QA host,
everything under `/opt/pravaha` ([Deployment](docs/operations/DEPLOYMENT.md)). [Full roadmap with acceptance gates →](docs/design/system_design.md#31-delivery-roadmap)

## Contributing

Not yet open for outside contributions. The standards that will apply are already enforced by the
build — see [implementation plan §3–§4](docs/design/implementation_plan.md):

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
