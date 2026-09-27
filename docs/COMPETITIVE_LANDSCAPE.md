# Pravaha — competitive landscape

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../LICENSE`](../LICENSE).

> **What this page is.** Where Pravaha stands among the products that do part of its job, as
> understood in **September 2026**. It scores **categories of product, not vendors**: each row says
> where a category's centre of gravity sits, and names products only as examples. Every claim about
> Pravaha links to the decision record, test or help topic that holds it, or says it is a target.
> Claims about other products are limited to what their own documentation widely states; where that
> was not certain the score is **Partial** and the card says why. Nothing here is a benchmark: no
> product was measured against Pravaha, and none of the numbers below is one.
>
> **This file is the single source.** The console's page at `/about/competitive` is drawn from it —
> the table, one card per row, and the sections after — and the About page's condensed landscape
> is drawn from the same table. A test fails the console's build if a row has no card or a card no
> row. [`system_design.md` §2](system_design.md#2-competitive-landscape--winning-strategy) is the
> design's original competitive intent, kept as history.

---

## The scored table

**Yes** means the category, as a whole, does this as a documented, supported feature. **Partial**
means some products in it do, or it is done with limits a buyer would notice. **No** means not, or
not without building it yourself. The Pravaha column is scored the same way against this tree. The
rows where Pravaha has the edge come first; each capability links to its card below.

| Capability | Dataflow SQL | Streaming databases | Kafka-native | Dataflow libraries | Embeddable JVM | Pravaha |
|---|---|---|---|---|---|---|
| [Refusing unbounded state at plan time](#refusing-unbounded-state-at-plan-time) | Partial | No | No | No | Partial | Yes |
| [Time-travel debugging](#time-travel-debugging) | No | No | No | No | No | Yes |
| [Blue/green replacement with backfill](#bluegreen-replacement-with-backfill) | Partial | Partial | Partial | No | Partial | Yes |
| [Store-native pushdown](#store-native-pushdown) | Partial | No | No | No | Partial | Yes |
| [Serving its own results](#serving-its-own-results) | No | Yes | Partial | No | Partial | Yes |
| [Sharing identical queries](#sharing-identical-queries) | No | Partial | No | Partial | No | Yes |
| [Row-level security at read time](#row-level-security-at-read-time) | No | Partial | No | No | No | Yes |
| [Embeddable in-process](#embeddable-in-process) | Partial | Partial | Yes | Yes | Yes | Yes |
| [Event time, watermarks and late-data corrections](#event-time-watermarks-and-late-data-corrections) | Partial | Partial | Partial | Yes | Partial | Yes |
| [Incremental maintenance with retractions](#incremental-maintenance-with-retractions) | Partial | Yes | Partial | Yes | No | Yes |
| [Exactly-once sinks](#exactly-once-sinks) | Yes | Partial | Yes | No | Yes | Yes |
| [Recursive queries](#recursive-queries) | No | Partial | No | Yes | No | No |
| [SQL breadth](#sql-breadth) | Yes | Yes | Partial | No | Partial | Partial |
| [Connector breadth](#connector-breadth) | Yes | Partial | Partial | No | Partial | Partial |
| [Scale-out and HA maturity](#scale-out-and-ha-maturity) | Yes | Yes | Yes | Partial | Yes | No |
| [Ecosystem and support](#ecosystem-and-support) | Yes | Yes | Yes | Partial | Yes | No |

**The columns.** *Dataflow SQL*: Apache Flink SQL (and managed Flink services), Spark Structured
Streaming, Arroyo. *Streaming databases*: Materialize, RisingWave, Feldera (DBSP) — engines that
maintain views incrementally. *Kafka-native*: ksqlDB, Kafka Streams. *Dataflow libraries*: Timely
and Differential Dataflow. *Embeddable JVM*: Hazelcast Jet, now part of the Hazelcast Platform. The
[categories](#the-categories) section says what each is genuinely good at.

## Where Pravaha shines, and how

One card per row where Pravaha scores **Yes**. Each says what the capability is, how the other
categories meet the same need, what Pravaha does and where that is proven, and why it matters to
someone choosing an engine.

### Refusing unbounded state at plan time

**The capability.** Some queries need state that grows for ever — a `GROUP BY` over a stream with
no window keeps one row per key it has ever seen. An engine can accept such a query and hope, bound
it by quietly dropping state, or refuse it before it runs.

**The problem.** Flink accepts an unbounded aggregate and bounds it with state time-to-live, which
drops state rather than refusing the query, so an answer silently forgets old keys. Spark refuses
some unsupported streaming plans at analysis — a stream-stream outer join without a watermark, for
instance — hence Partial. Streaming databases and Kafka Streams keep what the query asks for and
leave sizing to the operator. Hazelcast is scored Partial because its streaming aggregation is
documented as windowed; that was not verified here.

**How Pravaha does it.**

- Every operator that could grow without limit has a bound, and a query with none is refused when
  it is registered, with a code and the reason
  ([ADR-037](adr/037-state-that-degrades-instead-of-dying.md)).
- `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id` is refused `PRV-2050`, and the refusal names
  the fix: `GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id`
  ([`CONTINUOUS_QUERIES.md` §13](CONTINUOUS_QUERIES.md#why-an-unwindowed-group-by-is-refused),
  [SQL refusals](../console/content/topics/sql-refusals.md)).
- The support matrix that lists each refusal is checked against the planner by
  `ContinuousQueriesClaimsTest`.

**Why it matters.** A refusal costs five minutes at registration. An accepted query that grows its
state fails months later, on a heap that is full at three in the morning, or answers wrongly after
its state was trimmed.

### Time-travel debugging

**The capability.** Taking a running query back to a point it has passed, and stepping it forward
row by row to see why an answer is what it is — without touching the live query.

**The problem.** None of the categories is documented as shipping a step debugger over a running
query. Some products read data as of a past time (Materialize's `AS OF`, a lakehouse table's
history); that is a read of old data, not stepping a computation. Elsewhere, an incident is debugged
from logs and a copy of production.

**How Pravaha does it.**

- A query is forked from one of its retained checkpoints into a second computation that reads the
  same sources from that checkpoint's offsets — every sink disabled, its view in no catalogue, its
  lanes its own ([ADR-048](adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md)).
- It steps by one row, N rows, to the next commit, to a watermark, or until a column crosses a value,
  and each step reports every operator's rows in and out and the view's changes with their weights:
  `pravaha debug step --session "$SESSION" --step until:total:<:0`
  ([time-travel debugger](../console/content/topics/time-travel-debugger.md)).
- The session exports as a self-contained JUnit test whose expectation is rehearsed, and
  `DebugFixtureExportTest` compiles and runs one.

**Why it matters.** The question in an incident is "which operator made this number?". Answering it
on the real input, from the real checkpoint, and keeping the answer as a regression test, is the
difference between a fix and a guess.

### Blue/green replacement with backfill

**The capability.** Changing a running query's SQL without taking its answer away: the new version
catches up on history, then takes over, with nothing lost or counted twice.

**The problem.** Elsewhere this is operator practice. Flink restarts from a savepoint, and a plan
change can make the old state unusable. A streaming database builds the new view beside the old one
and the application switches names. Kafka Streams reprocesses the topic under a new application id.
Hazelcast upgrades a job from a snapshot. None is documented as meeting the running version at an
exact input position.

**How Pravaha does it.**

- `CREATE OR REPLACE CONTINUOUS QUERY` (or `pravaha replace`, either SDK, the console) runs the new
  version beside the old, replays the source from the beginning, splices onto the live stream at the
  exact position the running version has reached, and takes the name only when both have consumed
  the same input ([ADR-046](adr/046-a-replacement-meets-the-running-version-at-a-position.md),
  [backfill and cutover](../console/content/topics/backfill-cutover.md)).
- Subscribers are told the view was replaced (`PRV-4019`); a sink follows the name at a checkpoint
  boundary; the replaced version is kept for an hour, so a rollback is one step. A replacement in
  flight survives a restart (`QueryReplacementTest`, `ReplacementRestartTest`).

**Why it matters.** Queries change: a threshold moves, a column is added. If each change means an
outage or a window of wrong answers, people stop changing them, and the SQL drifts from what the
business needs.

### Store-native pushdown

**The capability.** Asking the store a query reads from to filter and project before rows leave it,
so rows the query does not need are never moved.

**The problem.** Flink and Spark define source interfaces a connector may implement to accept
filters and projections, and some connectors do — hence Partial. Streaming databases and Kafka-native
engines read a change feed or a topic and filter after ingest. Hazelcast's SQL uses the indexes of
its own maps, not those of an external store.

**How Pravaha does it.**

- Filters are pushed into JDBC and Aerospike, projections into those and Cassandra, and a continuous
  `COUNT`/`SUM` into a JDBC poll as one pre-combined partial per page ([README](../README.md),
  "Sources"; [the JDBC source](../console/content/topics/source-jdbc.md),
  [the Aerospike source](../console/content/topics/source-aerospike.md)). `PushdownEquivalenceTest`, `SourcePushdownEquivalenceTest` and
  `PartialAggregatePushdownEquivalenceTest` hold a pushed query to the same answer as an unpushed one.
- A source declares what it can evaluate, and the planner pushes only that
  ([`CONNECTORS.md` §2](CONNECTORS.md#sourcecapabilities--the-part-that-is-load-bearing)). A query's
  `feed.description` on `GET /api/v1/queries/{name}` says what the store was asked for.
- Streams from different stores join each other
  ([`CONNECTORS.md` §4](CONNECTORS.md#4-joining-across-different-sources)).
- The limits are written down: no Cassandra filter, no windowed pre-aggregate, no `MIN`/`MAX`
  partial ([README](../README.md), "What is not built").

**Why it matters.** A query that keeps 1 row in 100 moves a hundredth of the bytes when the store
filters, and the store you already run is often the only place the data lives. No second copy has
to be kept in step.

### Serving its own results

**The capability.** Reading the maintained answer where it is maintained: by key, by SQL scan, as a
stream of its changes, or through the PostgreSQL wire protocol that existing tools speak.

**The problem.** Dataflow engines compute and write: the answer goes to a sink, and reading it means
running another store. Streaming databases do serve their views, over the PostgreSQL wire protocol.
ksqlDB answers pull and push queries; Kafka Streams exposes local state through interactive queries
the application must serve itself. Hazelcast keeps results in its own maps, readable by key.

**How Pravaha does it.**

- The view is read by key or scanned with SQL over Arrow Flight SQL, subscribed to per commit or
  from a snapshot with none lost between, and read over the PostgreSQL wire protocol when
  `pravaha.pgwire.enabled` is on ([ADR-014](adr/014-serve-maintained-views.md),
  [ADR-030](adr/030-flight-sql-as-the-client-protocol.md),
  [point reads](../console/content/topics/point-reads.md),
  [the PostgreSQL gateway](../console/content/topics/pgwire.md)).
- From Python: `client.query("SELECT total FROM user_volume WHERE user_id = ?", "u42")`.

**Why it matters.** Without it, every streaming answer needs a second database, a loader, and code
that keeps them in step — and the answer read is only as right as the last time they agreed.

### Sharing identical queries

**The capability.** When several people register the same question, computing it once.

**The problem.** A Flink statement set shares a source inside one job, but two jobs asking the same
thing are two computations. Materialize shares an index's arrangement between the views that use
it, and a view built on a view reuses its state there and in RisingWave; Differential Dataflow shares
arrangements between dataflows. Neither is sharing matched on two users' identical queries.

**How Pravaha does it.**

- A registration is fingerprinted on its normalised physical plan, its key, retention, bound
  parameters and the row filters of whoever registered it — not the SQL text — so differences of
  layout and alias share, and anything that changes the answer does not
  ([ADR-025](adr/025-registration-and-subscription-separated.md),
  [sharing](../console/content/topics/sharing.md), `SharingIdentityTest`).
- A tenant shares only with itself
  ([ADR-050](adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md)).
- `pravaha queries` shows two names on one fingerprint and one `ROWS IN` counter.

**Why it matters.** Ten desks asking the same thing cost one read of the source and one copy of the
state, and nobody has to coordinate to get that.

### Row-level security at read time

**The capability.** Deciding on every read which rows of an answer the reader may see, from who
they are — not from which table or topic they may open.

**The problem.** Dataflow engines have no read path to secure. Kafka's access control is per topic.
Streaming databases grant roles on objects; row filters are usually written as views, one per
audience.

**How Pravaha does it.**

- Authentication and authorization happen in Pravaha on every read, against the streams a view
  derives from; a policy may return a row filter, which is injected into the plan and into the
  sharing fingerprint ([ADR-031](adr/031-authorization-at-the-pravaha-layer.md),
  [`SECURITY.md`](SECURITY.md), [row filters](../console/content/topics/row-filters.md),
  `ViewQueryAuthorizationTest`).
- A denied name and an unknown one answer the same, so a refusal is not an existence oracle.

**Why it matters.** One maintained answer can serve several audiences with different entitlements,
without a view per audience and without two entitlements ever sharing state.

### Embeddable in-process

**The capability.** Running the engine inside the application's own JVM, with no cluster and no
network.

**The problem.** Kafka Streams and Hazelcast Jet are libraries, and Differential Dataflow is a Rust
library. Flink and Spark have local execution modes, documented for development and testing rather
than as an embedding. Feldera's DBSP is also a Rust crate; Materialize and RisingWave are servers.

**How Pravaha does it.**

- `PravahaEngine.createDefault()` runs the whole loop in process — streams, bindings, continuous
  queries, pushed rows, SQL reads, subscriptions, journal and checkpoints — with no Spring and no
  network ([ADR-019](adr/019-spring-free-engine-core.md),
  [embedded engine](../console/content/topics/embedded-engine.md)).
- `pravaha-spring-boot-starter` makes it a bean, with `@PravahaListener`
  ([ADR-020](adr/020-spring-boot-starter.md)). The same engine runs as a server.
- No Spring in the engine core is enforced by `ArchitectureRulesTest`.

**Why it matters.** A test suite, a desktop tool or a service can own its engine, and move to a
server later without rewriting the SQL.

### Event time, watermarks and late-data corrections

**The capability.** Windows that close when the data says time has passed, and a late row that
corrects a published answer instead of vanishing.

**The problem.** The dataflow engines have mature event time and watermarks, but a row behind the
watermark is dropped by a windowed aggregate rather than corrected. Kafka Streams and ksqlDB update a
window inside its grace period. RisingWave and Feldera declare watermarks or lateness; Materialize
works in its own timestamps. Differential Dataflow's frontiers make corrections natural, but the
application builds the windows.

**How Pravaha does it.**

- A query's watermark is the newest event time seen minus the stream's out-of-orderness, a quiet
  partition stops holding the rest back, and a window publishes when the watermark passes its end
  ([event time and watermarks](../console/content/topics/event-time-watermarks.md)).
- With `allowed-lateness: 5m` on the `txn` stream, a late row reopens a published window as a
  retraction of the old answer and the corrected one, in one commit
  ([`CONTINUOUS_QUERIES.md` §6](CONTINUOUS_QUERIES.md#6-corrections-and-why-the-answer-can-go-backwards),
  [late data](../console/content/topics/late-data.md), `LateDataTest`). Allowed lateness is zero
  by default.

**Why it matters.** Real inputs arrive out of order. An engine that drops late rows publishes a
number it knows is wrong; one that corrects it tells every reader exactly what changed.

### Incremental maintenance with retractions

**The capability.** Keeping an answer current by doing work in proportion to what changed, and
withdrawing a row that stops being true.

**The problem.** Flink SQL maintains non-windowed aggregates and joins as changelog (retract and
upsert) streams; Spark Structured Streaming's update and complete output modes are not general
retraction, so the category is Partial. A ksqlDB table or a Kafka Streams `KTable` emits updates and
tombstones for a consumer to apply. Streaming databases and Differential Dataflow are built on it.
Hazelcast Jet is not an incremental view engine.

**How Pravaha does it.**

- Every change is a Z-set delta with a weight — `+1` a row appears, `−1` a row is withdrawn —
  through the engine, across the wire and into both SDKs
  ([ADR-013](adr/013-zsets-and-dbsp.md), [weights and retractions](../console/content/topics/zset-weights.md),
  `ZSetTest`).
- An update is `−1` of the old row and `+1` of the new, in one commit.

**Why it matters.** It is what makes an answer cheap to keep current, and what lets a consumer apply
a correction mechanically instead of guessing what changed.

### Exactly-once sinks

**The capability.** Writing each change of the answer to an external store once, even across a
crash and a replay.

**The problem.** Flink's two-phase-commit sinks, Kafka's transactions and Hazelcast's transactional
sinks do this well. Materialize's Kafka sink is exactly-once; other sink types and products vary.
Differential Dataflow leaves output to the application.

**How Pravaha does it.**

- `jdbc-sink`, `kafka-sink` and `delta-sink` stage a checkpoint's changes and commit them once the
  checkpoint is durable; an idempotent upsert such as `aerospike-sink` is effectively once, a plain
  append at least once, and the guarantee is stated at registration
  ([ADR-008](adr/008-aligned-checkpoints.md),
  [delivery guarantees](../console/content/topics/delivery-guarantees.md),
  `TransactionalSinkDeliveryTest`).
- It costs a second write and output trails the view by up to a checkpoint interval
  ([README](../README.md), "What is not built").

**Why it matters.** A ledger or a downstream table that counts a change twice is wrong for good.
Knowing which guarantee each sink gives, before relying on it, is the point.

## Where Pravaha is partial or behind, and why

The same card form, for the rows where Pravaha scores **Partial** or **No**.

### Recursive queries

**The capability.** SQL that refers to itself — transitive closure, reachability, fraud rings —
maintained incrementally.

**The problem it solves elsewhere.** Materialize (`WITH MUTUALLY RECURSIVE`) and Feldera maintain
recursive queries incrementally, and Differential Dataflow iterates natively; RisingWave was not
documented as doing so. Dataflow SQL and the Kafka-native engines do not.

**Where Pravaha stands.** **Not built — a target.** The design names it as win condition W6
([`system_design.md` §2.5](system_design.md#25-win-conditions)); the engine does not plan
`WITH RECURSIVE` today.

**Why it matters.** Graph-shaped questions need another engine for now.

### SQL breadth

**The capability.** How much of SQL a continuous query may use.

**The problem it solves elsewhere.** The dataflow engines and the streaming databases run broad SQL;
ksqlDB's is narrower.

**Where Pravaha stands.** **Partial.** Projections, filters, aggregates, tumbling and hopping
windows, stream-stream and temporal joins, a maintained top-N and exact `DECIMAL` run. Session
windows, recursive queries and a secondary index over a non-key column do not. **12 of Nexmark's 23
queries run**, and what is missing is SQL, not speed
([gate pack](gates/measured-2026-09-20/README.md)). Every construct that runs and every refusal, with
its code and reason, is in [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md), checked against the
planner.

**Why it matters.** Check the support matrix against the queries you need before choosing.

### Connector breadth

**The capability.** How many stores an engine reads from and writes to without custom code.

**The problem it solves elsewhere.** Flink, Spark and Kafka Connect have hundreds of connectors;
streaming databases concentrate on Kafka and change data capture.

**Where Pravaha stands.** **Partial.** Eight source types (files, feed directories, Delta, JDBC,
Aerospike, Cassandra, PostgreSQL CDC, Kafka), two lookup sources and five sinks, all in the server
jar (`ShippedConnectorsTest`). No Iceberg or Hudi sink, no change feed from any database but
PostgreSQL ([README](../README.md), "What is not built"); `kafka-sink` writes JSON, Avro or Protobuf. A
plugin SPI and its TCK let others add more ([`CONNECTORS.md`](CONNECTORS.md)).

**Why it matters.** If your store is not on the list, you write a connector.

### Scale-out and HA maturity

**The capability.** Spreading one workload over many machines, and surviving a machine's loss
without an operator.

**The problem it solves elsewhere.** Every other category except the research libraries runs
clustered with years of production behind it.

**Where Pravaha stands.** **No.** One node. A standby takes over when a node's claim on its state
goes stale ([standby](../console/content/topics/standby.md)); multi-node execution is designed
([ADR-045](adr/045-cluster-mode-assigns-queries-not-rows.md)) and **on hold by the owner**, and a node
refuses `PARTITIONED` mode with `PRV-9002` rather than pretend
([`REMAINING.md`](REMAINING.md), "Not scheduled"). The scaling gate is not reached: eight-lane
efficiency measured **28–42 % of linear** against a 90 % target (33–46 % re-measured without the
coverage agent), on a development laptop rather than reference hardware
([gate pack](gates/measured-2026-09-20/README.md)).

**Why it matters.** The requirement is about 1,000 rows per second
([ADR-042](adr/042-the-throughput-bar-is-the-requirement.md)); a workload that needs a cluster needs
another engine today.

### Ecosystem and support

**The capability.** Everything around the engine: published artefacts, a community, a vendor,
managed services, training, integrations.

**The problem it solves elsewhere.** Each category has most of these; the research libraries have a
research community.

**Where Pravaha stands.** **No.** Nothing is published to any registry or index
([`RELEASE_NOTES.md`](RELEASE_NOTES.md), 0.1.0). It is one author's work, tried so far by a QA
team on one node.

**Why it matters.** Nobody else is on call for it.

## Where Pravaha loses

Stated plainly, because a comparison that lists only wins is an advertisement.

- **Connector breadth.** Eight source types, two lookup sources and five sinks, where the dataflow
  engines and Kafka Connect have hundreds.
- **SQL breadth.** Session windows, recursive queries and much of Nexmark are not there: **12 of 23
  Nexmark queries run**.
- **Cluster scale.** One node; multi-node is designed and **on hold**.
- **The scaling gate is not reached.** **28–42 % of linear** at eight lanes against a 90 % target,
  measured on a development laptop.
- **No head-to-head benchmark.** The design's Nexmark comparison with Flink (win condition W5) has
  not been run: there is no Flink deployment, no quiet machine and no reference generator here, and
  too few queries run to compare on ([gate pack](gates/measured-2026-09-20/README.md)).
- **Ecosystem, community and support.** No published artefact, community, vendor, managed service,
  training or third-party integration.
- **One person's work.** The established products have customers, years of production incidents
  behind their defaults, and people on call.

## The categories

| Category | Examples | What they are genuinely good at | Where they stop, for Pravaha's job |
|---|---|---|---|
| **Distributed dataflow SQL** | Apache Flink SQL, Spark Structured Streaming, Arroyo | Scale-out, mature exactly-once state, event time and watermarks, broad SQL, a very large connector ecosystem and community | They compute and write: the answer goes to a sink, and reading it means running another store. They run as a cluster or a job, not inside an application |
| **Streaming databases** | Materialize, RisingWave, Feldera | True incremental view maintenance, broad SQL, serving their own results over the PostgreSQL wire protocol, and — for Materialize and Feldera — recursive queries | A database you move data into, fed by change feeds and topics rather than asked to filter at the store; a server, not a library |
| **Kafka-native** | ksqlDB, Kafka Streams | Tight Kafka integration, exactly-once inside Kafka, and — in Kafka Streams — a library that embeds in a JVM application with queryable local state | Everything goes through a Kafka topic first; ksqlDB's SQL is narrower than the other categories' |
| **Dataflow libraries** | Timely Dataflow, Differential Dataflow | The deepest incremental model in the field: shared arrangements, iteration and recursion, frontiers | Rust libraries for people building an engine: no SQL, connectors, serving or operations surface |
| **Embeddable JVM** | Hazelcast Jet (Hazelcast Platform) | Genuinely embeddable in a Java process, with event time, snapshots and exactly-once to transactional sinks | Narrower streaming SQL; results live in Hazelcast's own structures; not an incremental view engine |

Pravaha is none of these: a **single-node, embeddable engine** that maintains the answers to
registered SQL over the stores an organisation already runs — Aerospike, Cassandra, PostgreSQL or any
JDBC database, Kafka, Delta tables, files — and serves each answer back by key
([README](../README.md), "What it is").

## Adjacent tools

Not competitors for the job, and often complements: a CDC tool can feed Pravaha, and Trino or an
OLAP store can read what it maintains. *CDC tools*: Debezium, Striim, Qlik Replicate. *Federated
SQL*: Trino, Presto. *Real-time OLAP*: Apache Pinot, Apache Druid, ClickHouse.

| Capability | CDC tools | Federated SQL | Real-time OLAP | Pravaha |
|---|---|---|---|---|
| Refusing unbounded state at plan time | No | No | No | Yes |
| Time-travel debugging | No | No | No | Yes |
| Blue/green replacement with backfill | No | No | No | Yes |
| Store-native pushdown | No | Yes | No | Yes |
| Serving its own results | No | Partial | Partial | Yes |
| Sharing identical queries | No | No | Partial | Yes |
| Row-level security at read time | No | Yes | Partial | Yes |
| Embeddable in-process | Partial | No | Partial | Yes |
| Event time, watermarks and late-data corrections | No | No | Partial | Yes |
| Incremental maintenance with retractions | No | No | Partial | Yes |
| Exactly-once sinks | Partial | No | No | Yes |
| Recursive queries | No | Yes | Partial | No |
| SQL breadth | No | Yes | Partial | Partial |
| Connector breadth | Yes | Yes | Partial | Partial |
| Scale-out and HA maturity | Yes | Yes | Yes | No |
| Ecosystem and support | Yes | Yes | Yes | No |

CDC tools emit deletes and updates as change events but maintain nothing; Debezium also ships an
embedded engine, and Kafka Connect supports exactly-once for sources that implement it. Trino
answers ad-hoc queries — it serves nothing it maintained — supports `WITH RECURSIVE`, pushes
predicates into its connectors, and applies row filters through its access control. OLAP stores
serve fast SQL reads but no change subscriptions, and their materialized views are triggered on
insert rather than retracting; ClickHouse has row policies, a query cache, recursive CTEs in recent
versions and an embeddable form (chDB), which Pinot and Druid do not all share — hence Partial.

## What Pravaha borrows

| From | Idea | Where it lands |
|---|---|---|
| Feldera's DBSP | Z-sets and a mechanically derived incremental form for every operator | [ADR-013](adr/013-zsets-and-dbsp.md) |
| Differential Dataflow, Materialize | Shared state between the questions that need it; the maintained view as the thing that is read | [ADR-025](adr/025-registration-and-subscription-separated.md), [ADR-014](adr/014-serve-maintained-views.md) |
| Flink | Aligned checkpoint barriers; watermarks from event time; sinks that commit when the checkpoint does | [ADR-008](adr/008-aligned-checkpoints.md) |
| Spark | Whole-stage code generation with Janino | [ADR-005](adr/005-whole-stage-codegen.md) |
| Calcite, as Flink and others use it | Parse and optimise with Calcite, execute with operators of one's own | [ADR-002](adr/002-calcite-as-compiler.md) |
| Kafka Streams, Hazelcast Jet | A stream engine that is a library inside a JVM application | [ADR-019](adr/019-spring-free-engine-core.md), [ADR-020](adr/020-spring-boot-starter.md) |
| ksqlDB | Two ways to ask: a read of the answer, and a subscription to its changes | [ADR-026](adr/026-one-subscription-model-three-carriers.md) |
| Materialize, RisingWave | The PostgreSQL wire protocol, so existing tools read a view | [the PostgreSQL gateway](../console/content/topics/pgwire.md) |
| Debezium | Change data capture from PostgreSQL's logical replication — reimplemented, not depended on | [ADR-041](adr/041-change-data-capture-without-debezium.md) |
| Trino | Connectors that say what they can evaluate, so the planner pushes only that | [`CONNECTORS.md` §2](CONNECTORS.md#sourcecapabilities--the-part-that-is-load-bearing) |

## Disclaimer

Categories, not vendors. Each score says where a category's centre of gravity sits as understood in
**September 2026**; individual products differ, change between releases, and some cross these lines.
Nothing here is a statement about any one vendor's current offering or a measurement of it, and no
product named here has reviewed it. Where this page and a product's own documentation disagree, the
product's documentation is right about the product.

## Verdict

Pravaha is not a smaller Flink or a cheaper Materialize. It is a narrow tool: an engine that lives
beside, or inside, the application and the stores it already has, keeps the answers to a known set of
questions current, serves them by key, and refuses up front what it cannot keep bounded. On one node,
at the rates its requirement names, with a combination no category offers — refusal at plan time, a
debugger over a running query, gapless replacement, store pushdown, serving and embedding together —
it is a credible choice for that job.

It is the wrong choice when the job needs horizontal scale, a long list of connectors, the widest
SQL, recursive queries, or a vendor to call: a dataflow engine, a streaming database or the Kafka
stack wins there today, and the table says so. Until multi-node execution lands and the Nexmark
comparison has been run, the honest position is a strong single-node engine with a design for more.
