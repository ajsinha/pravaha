# Pravaha — competitive landscape

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

> **What this page is.** Where Pravaha stands among the products that do part of its job, as
> understood in **September 2026**. It scores **categories of product, not vendors**: each row says
> where a category's centre of gravity sits. Products are named only in the prose, as well-known
> examples of a category, and never in the table. Every claim about Pravaha links to the decision
> record, test or help topic that holds it, or says it is a target. Claims about other products are
> limited to what their own documentation widely states; where that was not certain the score is
> **Partial** and the note says why. Nothing here is a benchmark: no product was measured against
> Pravaha, and none of the numbers below is one.
>
> **This file is the single source.** The console's page at `/about/competitive` is drawn from it —
> the landscape, the table, one note per row and the sections after — and the About page's summary
> is drawn from the same table. A test fails the console's build if a row has no note, a note no row,
> or a note lacks "The problem elsewhere" or "How Pravaha does it".
> [`system_design.md` §2](../design/system_design.md#2-competitive-landscape--winning-strategy) is the design's
> original competitive intent, kept as history.

---

## The landscape

Seven families of product touch the problem Pravaha addresses, and each is good at what it does.
**Distributed dataflow SQL** engines, such as Apache Flink SQL, Spark Structured Streaming and
Arroyo, run event-time SQL at scale with mature exactly-once state and a very large connector
ecosystem, but they compute and write: the answer goes to a sink, and reading it means running
another store. **Streaming databases**, such as Materialize, RisingWave and Feldera, maintain views
incrementally and serve them over the PostgreSQL wire protocol, but they are servers you move data
into, fed by change feeds and topics rather than asked to filter at the store. **Kafka-native**
engines, ksqlDB and Kafka Streams, integrate tightly with Kafka and, in Kafka Streams, embed in a JVM
application, but everything passes through a topic first. **Dataflow libraries**, Timely and
Differential Dataflow and the DBSP crate, hold the deepest incremental model in the field, but are
libraries for people building an engine: no SQL surface, connectors or operations. **Embeddable JVM**
engines, Hazelcast Jet within the Hazelcast Platform, run inside a Java process with event time and
snapshots, but are not incremental view engines. **Governance catalogues**, such as Databricks Unity
Catalog, Apache Polaris and AWS Lake Formation, own names, grants, row filters, column masks and
lineage across an estate, but they govern data at rest and the engines that read it, not an answer
that is still being computed.

The pattern is consistent: the engines that keep an answer current do not govern it, and the
catalogues that govern data do not see an answer that is still moving. Pravaha is a **single-node,
embeddable engine** that maintains the answers to registered SQL over the stores an organisation
already runs, serves each answer back by key, and governs it where it lives — grants, row filters and
masks on the live view, revocation that ends an open stream — with every hand-over between streams of
records made at an exact position rather than a moment
([README](../../README.md), "What it is").

## The scored table

**Yes** means the category, as a whole, does this as a documented, supported feature. **Partial**
means some products in it do, or it is done with limits a buyer would notice. **No** means not, or
not without building it yourself. The Pravaha column is scored the same way against this tree. The
rows where Pravaha has the edge come first; each capability links to its note below.

| Capability | Dataflow SQL | Streaming databases | Kafka-native | Dataflow libraries | Embeddable JVM | Governance catalogues | Pravaha |
|---|---|---|---|---|---|---|---|
| [Refusing unbounded state at plan time](#refusing-unbounded-state-at-plan-time) | Partial | No | No | No | Partial | No | Yes |
| [Time-travel debugging](#time-travel-debugging) | No | No | No | No | No | No | Yes |
| [Blue/green replacement with backfill](#bluegreen-replacement-with-backfill) | Partial | Partial | Partial | No | Partial | No | Yes |
| [Store-native pushdown](#store-native-pushdown) | Partial | No | No | No | Partial | No | Yes |
| [Serving its own results](#serving-its-own-results) | No | Yes | Partial | No | Partial | No | Yes |
| [Sharing identical queries](#sharing-identical-queries) | No | Partial | No | Partial | No | No | Yes |
| [A governed catalogue of live answers](#a-governed-catalogue-of-live-answers) | Partial | Partial | No | No | Partial | Partial | Yes |
| [Row-level security at read time](#row-level-security-at-read-time) | No | Partial | No | No | No | Partial | Yes |
| [Alerts that fire and clear](#alerts-that-fire-and-clear) | No | Partial | No | No | No | No | Yes |
| [BI tools over the PostgreSQL protocol, with security applied](#bi-tools-over-the-postgresql-protocol-with-security-applied) | No | Partial | No | No | No | Partial | Yes |
| [Plain English to continuous SQL, with the engine as judge](#plain-english-to-continuous-sql-with-the-engine-as-judge) | Partial | Partial | Partial | No | No | No | Yes |
| [A lane of its own or a shared one, changed without loss](#a-lane-of-its-own-or-a-shared-one-changed-without-loss) | Partial | Partial | Partial | No | Partial | No | Yes |
| [Native change data capture](#native-change-data-capture) | Partial | Yes | No | No | Partial | No | Yes |
| [Embeddable in-process](#embeddable-in-process) | Partial | Partial | Yes | Yes | Yes | No | Yes |
| [Event time, watermarks and late-data corrections](#event-time-watermarks-and-late-data-corrections) | Partial | Partial | Partial | Yes | Partial | No | Yes |
| [Incremental maintenance with retractions](#incremental-maintenance-with-retractions) | Partial | Yes | Partial | Yes | No | No | Yes |
| [Exactly-once sinks](#exactly-once-sinks) | Yes | Partial | Yes | No | Yes | No | Yes |
| [Observability built in](#observability-built-in) | Yes | Yes | Yes | Partial | Yes | Partial | Yes |
| [Queries on queries](#queries-on-queries) | Partial | Yes | Partial | Yes | No | No | Partial |
| [Delta and Iceberg table sinks](#delta-and-iceberg-table-sinks) | Yes | Partial | Partial | No | No | No | Partial |
| [Recursive queries](#recursive-queries) | No | Partial | No | Yes | No | No | No |
| [SQL breadth](#sql-breadth) | Yes | Yes | Partial | No | Partial | No | Partial |
| [Connector breadth](#connector-breadth) | Yes | Partial | Partial | No | Partial | Partial | Partial |
| [Governing many engines and data at rest](#governing-many-engines-and-data-at-rest) | No | No | No | No | No | Yes | No |
| [Scale-out and HA maturity](#scale-out-and-ha-maturity) | Yes | Yes | Yes | Partial | Yes | Yes | No |
| [MFA and single sign-on](#mfa-and-single-sign-on) | Partial | Yes | Partial | No | Partial | Yes | No |
| [A managed cloud service](#a-managed-cloud-service) | Yes | Yes | Yes | No | Yes | Yes | No |
| [Ecosystem and support](#ecosystem-and-support) | Yes | Yes | Yes | Partial | Yes | Yes | No |

## Where Pravaha shines, and how

One note per row where Pravaha scores **Yes**: the problem as the other categories leave it, how
Pravaha does it and where that is proven, and why it matters to someone choosing an engine.

### Refusing unbounded state at plan time

**The problem elsewhere.** Some queries need state that grows for ever — a `GROUP BY` over a stream
with no window keeps one row per key it has ever seen. Dataflow SQL accepts such a query and bounds it
with a state time-to-live that drops state rather than refusing the query, so an answer silently
forgets old keys; some plans are refused at analysis (a stream-stream outer join without a
watermark), hence Partial. Streaming databases and the Kafka-native engines keep what the query asks
for and leave sizing to the operator. The embeddable JVM category documents its streaming aggregation
as windowed, which was not verified here, hence Partial.

**How Pravaha does it.**

- Every operator that could grow without limit has a bound, and a query with none is refused when it
  is registered, with a code and the reason
  ([ADR-037](../design/adr/037-state-that-degrades-instead-of-dying.md)).
- `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id` is refused `PRV-2050`, and the refusal names
  the fix: `GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id`
  ([`CONTINUOUS_QUERIES.md` §13](../guides/CONTINUOUS_QUERIES.md#why-an-unwindowed-group-by-is-refused),
  [SQL refusals](../../console/content/topics/sql-refusals.md)).
- The support matrix that lists each refusal is checked against the planner by
  `ContinuousQueriesClaimsTest`.

**Why it matters.** A refusal costs five minutes at registration. An accepted query that grows its
state fails months later, on a heap that is full at three in the morning, or answers wrongly after
its state was trimmed.

### Time-travel debugging

**The problem elsewhere.** None of the categories is documented as shipping a step debugger over a
running query. Some products read data as of a past time (a streaming database's `AS OF`, a
lakehouse table's history); that is a read of old data, not stepping a computation. Elsewhere, an
incident is debugged from logs and a copy of production.

**How Pravaha does it.**

- A query is forked from one of its retained checkpoints into a second computation that reads the
  same sources from that checkpoint's offsets — every sink disabled, its view in no catalogue, its
  lanes its own ([ADR-048](../design/adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md)).
- It steps by one row, N rows, to the next commit, to a watermark, or until a column crosses a value,
  and each step reports every operator's rows in and out and the view's changes with their weights:
  `pravaha debug step --session "$SESSION" --step until:total:<:0`
  ([time-travel debugger](../../console/content/topics/time-travel-debugger.md)).
- The session exports as a self-contained JUnit test whose expectation is rehearsed, and
  `DebugFixtureExportTest` compiles and runs one.

**Why it matters.** The question in an incident is "which operator made this number?". Answering it
on the real input, from the real checkpoint, and keeping the answer as a regression test, is the
difference between a fix and a guess.

### Blue/green replacement with backfill

**The problem elsewhere.** Changing a running query's SQL is operator practice. Dataflow SQL restarts
from a savepoint, and a plan change can make the old state unusable. A streaming database builds the
new view beside the old one and the application switches names. Kafka Streams reprocesses the topic
under a new application id; the embeddable JVM category upgrades a job from a snapshot. None is
documented as meeting the running version at an exact input position.

**How Pravaha does it.**

- `CREATE OR REPLACE CONTINUOUS QUERY` (or `pravaha replace`, either SDK, the console) runs the new
  version beside the old, replays the source from the beginning, splices onto the live stream at the
  exact position the running version has reached, and takes the name only when both have consumed
  the same input ([ADR-046](../design/adr/046-a-replacement-meets-the-running-version-at-a-position.md),
  [backfill and cutover](../../console/content/topics/backfill-cutover.md)).
- Subscribers are told the view was replaced (`PRV-4019`); a sink follows the name at a checkpoint
  boundary and is sent only the difference; the replaced version is kept for an hour, so a rollback
  is one step. A replacement in flight survives a restart (`QueryReplacementTest`,
  `ReplacementRestartTest`).

**Why it matters.** Queries change: a threshold moves, a column is added. If each change means an
outage or a window of wrong answers, people stop changing them, and the SQL drifts from what the
business needs.

### Store-native pushdown

**The problem elsewhere.** Dataflow SQL defines source interfaces a connector may implement to accept
filters and projections, and some connectors do — hence Partial. Streaming databases and Kafka-native
engines read a change feed or a topic and filter after ingest. The embeddable JVM category's SQL uses
the indexes of its own structures, not those of an external store.

**How Pravaha does it.**

- Filters are pushed into JDBC and Aerospike, and into Cassandra on the key; projections into all
  three; and a continuous `COUNT`/`SUM` into a JDBC poll as one pre-combined partial per page
  ([README](../../README.md), "Sources"; [the JDBC source](../../console/content/topics/source-jdbc.md),
  [the Aerospike source](../../console/content/topics/source-aerospike.md)). `PushdownEquivalenceTest`,
  `SourcePushdownEquivalenceTest` and `PartialAggregatePushdownEquivalenceTest` hold a pushed query
  to the same answer as an unpushed one.
- A source declares what it can evaluate, and the planner pushes only that
  ([connector guide §4](../development/guides/CONNECTOR_DEVELOPMENT.md#4-capabilities-claim-the-weakest-thing-that-is-true)). A query's
  `feed.description` on `GET /api/v1/queries/{name}` says what the store was asked for.
- Streams from different stores join each other
  ([`CONNECTORS.md` §4](../guides/CONNECTORS.md#4-joining-across-different-sources)); the limits are written
  down: no windowed pre-aggregate, no `MIN`/`MAX` partial ([README](../../README.md), "What is not built").

**Why it matters.** A query that keeps 1 row in 100 moves a hundredth of the bytes when the store
filters, and the store you already run is often the only place the data lives. No second copy has
to be kept in step.

### Serving its own results

**The problem elsewhere.** Dataflow engines compute and write: the answer goes to a sink, and reading
it means running another store. Streaming databases do serve their views, over the PostgreSQL wire
protocol. ksqlDB answers pull and push queries; Kafka Streams exposes local state through interactive
queries the application must serve itself. The embeddable JVM category keeps results in its own
structures, readable by key.

**How Pravaha does it.**

- The view is read by key or scanned with SQL over Arrow Flight SQL, subscribed to per commit or from
  a snapshot with none lost between, and read over the PostgreSQL wire protocol when
  `pravaha.pgwire.enabled` is on ([ADR-014](../design/adr/014-serve-maintained-views.md),
  [ADR-030](../design/adr/030-flight-sql-as-the-client-protocol.md),
  [point reads](../../console/content/topics/views-and-keys.md#point-reads),
  [the PostgreSQL gateway](../../console/content/topics/pgwire.md)).
- An equality index over a column outside the key answers a read by that column without a scan
  ([ADR-055](../design/adr/055-an-equality-index-over-a-column-outside-the-key.md)).
- From Python: `client.query("SELECT total FROM user_volume WHERE user_id = ?", "u42")`.

**Why it matters.** Without it, every streaming answer needs a second database, a loader, and code
that keeps them in step — and the answer read is only as right as the last time they agreed.

### Sharing identical queries

**The problem elsewhere.** Inside one dataflow job a statement set shares a source, but two jobs
asking the same thing are two computations. A streaming database shares an index's arrangement between
the views that use it, and Differential Dataflow shares arrangements between dataflows. Neither is
sharing matched on two users' identical queries.

**How Pravaha does it.**

- A registration is fingerprinted on its normalised physical plan, its key, retention, bound
  parameters and the row filters and masks that apply to whoever registered it — not the SQL text — so
  differences of layout and alias share, and anything that changes the answer does not
  ([ADR-025](../design/adr/025-registration-and-subscription-separated.md),
  [sharing](../../console/content/topics/sharing.md), `SharingIdentityTest`).
- A tenant shares only with itself
  ([ADR-050](../design/adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md)).
- `pravaha queries` shows two names on one fingerprint and one `ROWS IN` counter.

**Why it matters.** Ten desks asking the same thing cost one read of the source and one copy of the
state, and nobody has to coordinate to get that.

### A governed catalogue of live answers

**The problem elsewhere.** Governance catalogues own names, grants, row filters, masks and lineage —
for tables at rest, enforced by the engines that honour them; a revocation affects the next query, and
a running view or an open subscription is not an object they govern. Dataflow SQL gets grants when it
runs under such a catalogue; on its own its catalogues hold metadata, not access. Streaming databases
grant roles on objects, and the embeddable JVM category grants permissions per structure; neither
makes filters and masks objects that follow the data into what is built on it. Kafka's access control
is per topic.

**How Pravaha does it.**

- The Pravaha Catalog names every stream, view, alert and binding as `tenant.namespace.object`, with
  owners, tags and allow-only grants inherited down namespaces, changed by SQL, REST, CLI or the
  console with no restart, every change journalled and audited
  ([ADR-059](../design/adr/059-the-pravaha-catalog-governs-live-answers.md),
  [catalogue and grants](../../console/content/topics/catalog-and-grants.md), `CatalogServiceTest`).
- Row filters and column masks are catalogue objects: `CREATE ROW FILTER` and `CREATE MASK`, bound to
  one object or to every object carrying a tag, now and later. They are enforced on Flight reads,
  point reads, pgwire, subscriptions, registrations and alerts, and carried into the plan and the
  fingerprint of every query built on the view
  ([row filters and masks](../../console/content/topics/row-filters-and-masks.md),
  `CatalogPoliciesEndToEndTest`, `PgNarrowingTest`, `AlertNarrowingTest`).
- **Revocation reaches an open stream.** A revoked `SUBSCRIBE` ends the subscription already running,
  within seconds, with `PRV-7002`; a changed filter or mask ends the affected subscriptions with
  `PRV-7007` rather than changing what an open stream means half-way (`SubscriptionRevocationTest`).
- A masked column used as a filter operand, a group, join or sort key is refused (`PRV-7006`), and a
  filter that restricts nothing is refused rather than enforced as though it did (`PRV-7003`).
- The limits are stated: lineage, labels, cross-tenant shares and access history are phases 3 and 4,
  not built; the catalogue is off by default (`pravaha.catalog.enabled`).

**Why it matters.** A live answer is data too. If the grant that stops someone reading a table does
not stop them reading the view computed from it — or leaves their open stream running after the grant
is gone — the governance has a hole exactly where the data is freshest.

### Row-level security at read time

**The problem elsewhere.** Dataflow engines have no read path to secure. Kafka's access control is per
topic. Streaming databases grant roles on objects; row filters are usually written as views, one per
audience. Governance catalogues define row filters on tables, applied by the engines that read them,
hence Partial.

**How Pravaha does it.**

- Authentication and authorization happen in Pravaha on every read, against the streams a view derives
  from; a policy may return a row filter, which is injected into the plan and into the sharing
  fingerprint ([ADR-031](../design/adr/031-authorization-at-the-pravaha-layer.md),
  [`../operations/SECURITY.md`](../operations/SECURITY.md), [row filters](../../console/content/topics/row-filters-and-masks.md),
  `ViewQueryAuthorizationTest`).
- A filter may read the reader's claims — `session_attribute('region')`, `current_user()`,
  `is_member('role')` — and a store user carries attributes presented as claims.
- A denied name and an unknown one answer the same, so a refusal is not an existence oracle.

**Why it matters.** One maintained answer can serve several audiences with different entitlements,
without a view per audience and without two entitlements ever sharing state.

### Alerts that fire and clear

**The problem elsewhere.** A dataflow engine detects a condition and writes an event; knowing when the
condition stopped being true is left to whoever reads the events, and duplicate or lost notifications
across a restart are theirs to handle. A streaming database's subscription delivers retractions, so a
clear can be built on it, but there is no alert object, notifier or lifecycle — hence Partial. The
Kafka-native engines, libraries and catalogues leave alerting to another system.

**How Pravaha does it.**

- `CREATE ALERT` follows one view's answer: a key **fires** when its row enters the view and
  **clears** when it leaves — deleted, or updated back across the `WHERE`. Clearing is honest because
  the view is fed retractions ([ADR-057](../design/adr/057-alerts.md),
  [alerts](../../console/content/topics/alerts.md), `AlertServiceTest`,
  `RetailLowStockAlertEndToEndTest`).
- What is true (`fire_after`, `clear_after`) and what is said (dedupe, pause, snooze, reminders until
  `ACK`) are kept apart, so muting a notification never changes the state.
- Exactly-once state: every decision is journalled and forced before a send, so there is no re-fire and
  no lost clear across a restart; delivery is at least once under a stable idempotency key, to an
  HMAC-signed webhook or the log, with secrets held by reference only.
- An alert is a catalogue object; `NOTIFY` needs `WRITE` on the notifier, and it evaluates as its owner,
  through the owner's row filters and masks.

**Why it matters.** An alert that fires and never says the problem has gone away trains people to
ignore it. One that says both, exactly once across a crash, is a signal someone can act on.

### BI tools over the PostgreSQL protocol, with security applied

**The problem elsewhere.** Dataflow and Kafka-native engines leave serving to another store, so a BI
tool reads a copy. Streaming databases speak the PostgreSQL protocol, with grants on objects; row
filters and masks per reader are not their centre of gravity, hence Partial. Governance catalogues
govern what a BI tool reads through a warehouse engine, but are not the endpoint it connects to.

**How Pravaha does it.**

- With `pravaha.pgwire.enabled`, `psql`, DBeaver, Grafana and **Power BI** (Import and DirectQuery,
  through its own PostgreSQL connector) read a maintained view: simple and extended protocol, text and
  binary results, `\d`, TLS ([the PostgreSQL gateway](../../console/content/topics/pgwire.md),
  [Power BI](../../console/content/topics/power-bi.md), `PowerBiGatewayTest`, `PgPowerBiTextTest`).
- The same authentication, grants, row filters and masks apply as on Flight — text and binary
  results alike (`PgNarrowingTest`); writes are refused.

**Why it matters.** The people who look at an answer most often do it through a BI tool. If that path
skips the row filter, the filter is decoration.

### Plain English to continuous SQL, with the engine as judge

**The problem elsewhere.** Managed dataflow and Kafka platforms ship assistants that draft SQL in their
editors, on the vendor's chosen model; whether the engine checks the draft's meaning, rather than its
syntax, was not verified here, hence Partial. Some streaming databases expose a server that lets an
outside agent query them; drafting a view is left to the model, hence Partial. The other categories
ship none.

**How Pravaha does it.**

- A model drafts, the engine validates and explains, and a person confirms: `pravaha ask`, and
  *Describe it* on the console's workbench. The draft is planned by the engine under the caller's own
  credentials, fingerprinted, and repaired at most three times without changing what it reads; it is
  registered only when a person says so ([ADR-058](../design/adr/058-plain-english-to-continuous-sql.md),
  [the assistant](../../console/content/topics/assistant.md), [`../guides/ASSIST.md`](../guides/ASSIST.md)).
- **Any model.** Providers plug in behind one protocol by entry point — Anthropic, OpenAI, Azure
  OpenAI, Bedrock, Vertex, Ollama and any OpenAI-compatible server are built in — with fallback
  chains, per-user budgets and runtime switching in Admin · AI models.
- `pravaha explain-sql` and `pravaha why` explain a query and a refusal code in plain words; a golden
  set generated from the case studies scores drafts by fingerprint and by answer
  (`pravaha assist eval`).

**Why it matters.** A model that writes plausible SQL is easy; one whose SQL is checked by the thing
that will run it, and refused with a reason when wrong, is safe to put in front of someone who does
not write SQL.

### A lane of its own or a shared one, changed without loss

**The problem elsewhere.** Dataflow SQL isolates by job and slot-sharing group; moving a job means
stopping it and restoring from a savepoint. Streaming databases place objects on compute clusters or
resource groups chosen at creation. Kafka-native and embeddable JVM engines isolate by application
instance or job. None is documented as moving one running query between isolation and sharing at an
exact position.

**How Pravaha does it.**

- Lane sharing is `auto` by default: a node's first 64 queries each own a lane, so a failing query takes
  down only itself, and registrations after them share, saving about 1 MiB per idle query
  ([lane sharing](../../console/content/topics/lanes.md#sharing-lanes),
  [sizing lanes](../../console/content/topics/lanes.md#sizing-lanes), `NodeLaneSharingTest`).
- `WITH (lane = 'dedicated')` puts one query on a lane of its own whatever the mode, and
  `CREATE OR REPLACE` moves a running query between a shared lane and its own at a lossless cutover
  (`DedicatedLaneTest`).
- An administrator rebalances from Admin → Lanes or `pravaha lanes rebalance`: a preview, then shared
  queries move onto lanes of their own one at a time, each by a blue/green replacement
  (`LaneRebalanceTest`). A dead shared lane is skipped for new placements.

**Why it matters.** A thousand small queries cannot each have a thread, and one important query should
not share a fate with a noisy one. Choosing per query, and changing the choice without a gap, is what
lets one node carry both.

### Native change data capture

**The problem elsewhere.** Dataflow SQL reads change data through connectors that embed Debezium, and
the Kafka-native route is Debezium on the Kafka Connect runtime, publishing to a topic first.
Streaming databases read PostgreSQL and MySQL replication natively. The embeddable JVM category's
change capture has been Debezium-based, hence Partial.

**How Pravaha does it.**

- `postgres-cdc` reads PostgreSQL's logical replication on the JDBC driver, which already ships the
  replication API: an insert at `+1`, a delete as the whole old row at `−1`, an update as both, whole
  transactions, exactly once — the slot is confirmed only at checkpoints — with an initial snapshot that
  resumes exactly mid-read ([ADR-041](../design/adr/041-change-data-capture-without-debezium.md),
  [PostgreSQL CDC](../../console/content/topics/source-postgres-cdc.md), `PostgresCdcSnapshotTest`).
- `mysql-cdc` reads the row-based binlog as a replica, with the same weights, exactly once from a binlog
  file and offset ([MySQL CDC](../../console/content/topics/source-mysql-cdc.md)).
- The limits are stated: MySQL has no initial snapshot yet, and a CDC binding feeds one query — a
  second, different query over it is refused (`PRV-8028`) ([README](../../README.md), "What is not built").

**Why it matters.** A change feed that needs a Connect cluster and a topic before the first row is
three systems to run for one question. Reading the log directly keeps the retraction exact and the
deployment one process.

### Embeddable in-process

**The problem elsewhere.** Kafka Streams and the embeddable JVM engines are libraries, and the dataflow
libraries are Rust crates. Dataflow SQL has local execution modes, documented for development and
testing rather than as an embedding; streaming databases are servers. Catalogues are services.

**How Pravaha does it.**

- `PravahaEngine.createDefault()` runs the whole loop in process — streams, bindings, continuous
  queries, pushed rows, SQL reads, subscriptions, journal and checkpoints — with no Spring and no
  network ([ADR-019](../design/adr/019-spring-free-engine-core.md),
  [embedded engine](../../console/content/topics/embedded-engine.md)).
- `pravaha-spring-boot-starter` makes it a bean, with `@PravahaListener`
  ([ADR-020](../design/adr/020-spring-boot-starter.md)). The same engine runs as a server.
- No Spring in the engine core is enforced by `ArchitectureRulesTest`.

**Why it matters.** A test suite, a desktop tool or a service can own its engine, and move to a
server later without rewriting the SQL.

### Event time, watermarks and late-data corrections

**The problem elsewhere.** Dataflow SQL has mature event time and watermarks, but a row behind the
watermark is dropped by a windowed aggregate rather than corrected. Kafka Streams and ksqlDB update a
window inside its grace period. Streaming databases declare watermarks or lateness, or work in their
own timestamps. Differential Dataflow's frontiers make corrections natural, but the application builds
the windows.

**How Pravaha does it.**

- A query's watermark is the newest event time seen minus the stream's out-of-orderness, a quiet
  partition stops holding the rest back, and a window publishes when the watermark passes its end
  ([event time and watermarks](../../console/content/topics/event-time-watermarks.md)).
- With `allowed-lateness: 5m` on the `txn` stream, a late row reopens a published window as a
  retraction of the old answer and the corrected one, in one commit
  ([`CONTINUOUS_QUERIES.md` §6](../guides/CONTINUOUS_QUERIES.md#6-corrections-and-why-the-answer-can-go-backwards),
  [late data](../../console/content/topics/event-time-watermarks.md#late-data), `LateDataTest`). Allowed lateness is zero by
  default.

**Why it matters.** Real inputs arrive out of order. An engine that drops late rows publishes a
number it knows is wrong; one that corrects it tells every reader exactly what changed.

### Incremental maintenance with retractions

**The problem elsewhere.** Dataflow SQL maintains non-windowed aggregates and joins as changelog
(retract and upsert) streams in some engines and not others, hence Partial. A ksqlDB table or a Kafka
Streams `KTable` emits updates and tombstones for a consumer to apply. Streaming databases and
Differential Dataflow are built on it; the embeddable JVM category is not an incremental view engine.

**How Pravaha does it.**

- Every change is a Z-set delta with a weight — `+1` a row appears, `−1` a row is withdrawn — through
  the engine, across the wire and into both SDKs ([ADR-013](../design/adr/013-zsets-and-dbsp.md),
  [weights and retractions](../../console/content/topics/zset-weights.md), `ZSetTest`).
- An update is `−1` of the old row and `+1` of the new, in one commit.

**Why it matters.** It is what makes an answer cheap to keep current, and what lets a consumer apply a
correction mechanically instead of guessing what changed.

### Exactly-once sinks

**The problem elsewhere.** Dataflow SQL's two-phase-commit sinks, Kafka's transactions and the
embeddable JVM category's transactional sinks do this well. Streaming databases' sinks vary by type
and product. Differential Dataflow leaves output to the application.

**How Pravaha does it.**

- A checkpoint is one consistent cut across every input
  ([ADR-008](../design/adr/008-aligned-checkpoints.md)), so restored state reflects each input record exactly
  once — the research paper states and proves it under listed assumptions.
- `jdbc-sink`, `kafka-sink`, `delta-sink` and `iceberg-sink` stage a checkpoint's changes and commit
  them once the checkpoint is durable; an idempotent upsert such as `aerospike-sink` is effectively
  once, a plain append at least once, and the guarantee is stated at registration
  ([delivery guarantees](../../console/content/topics/delivery-guarantees.md),
  `TransactionalSinkDeliveryTest`).
- It costs a second write and output trails the view by up to a checkpoint interval
  ([README](../../README.md), "What is not built").

**Why it matters.** A ledger or a downstream table that counts a change twice is wrong for good.
Knowing which guarantee each sink gives, before relying on it, is the point.

### Observability built in

**The problem elsewhere.** This is parity, not an edge: Dataflow SQL, streaming databases, the Kafka
stack and the embeddable JVM category all ship metrics, dashboards and operational views. The dataflow
libraries leave it to the application; catalogues offer audit and lineage but not an engine's
operational numbers.

**How Pravaha does it.**

- Prometheus per query — rows in, view size, state against its ceiling, watermark lag, checkpoint
  health, commit latency, backpressure in time — and, with `pravaha.metrics.operators` on, rows in and
  out, state bytes and self time per plan node, naming the bottleneck operator
  ([observability](../../console/content/topics/observability.md),
  [metrics and alerts](../../console/content/topics/observability.md), `PravahaMetricsTest`).
- Metrics for alerts, the catalogue and Flight; four Grafana dashboards and Prometheus rules under
  `deploy/observability/`; JSON logs with correlation, trace and span ids; OpenTelemetry tracing
  (off by default) with spans per request, registration, replacement, checkpoint and notification,
  continuing a caller's `traceparent` (`TracingTest`).
- Where a number is not measured the API says so rather than reporting zero.

**Why it matters.** An engine nobody can see into is one nobody will run in production. Parity here
is the price of admission.

## Where Pravaha is partial or behind, and why

The same note form, for the rows where Pravaha scores **Partial** or **No**.

### Queries on queries

**The problem elsewhere.** Streaming databases build views on views as a matter of course, with the
whole of their SQL, and Differential Dataflow composes arrangements. Dataflow SQL composes views inside
one job, and across jobs through a topic carrying a changelog; ksqlDB builds tables from tables through
topics.

**How Pravaha does it.**

- A continuous query whose `FROM` names another query follows that query's **answer** — its snapshot,
  then each commit's rows leaving and entering — not its changelog, which for a keyed upsert view is
  not how the answer changed ([ADR-056](../design/adr/056-queries-on-queries.md)).
- **Exact across restarts**: the checkpoint carries the answer the downstream consumed, and on restore
  it is fed the difference against the upstream's snapshot, whichever of the two checkpointed later
  (`QueryChainsTest`).
- **Partial**, because only filter, projection and unwindowed `COUNT`/`SUM`/`AVG` run over a view;
  windows, joins, `MIN`/`MAX` and `COUNT(DISTINCT)` are refused (`PRV-2075`). Drop with dependants,
  cycles and replacing a chain member are refused by code; chains are one tenant's and eight deep.

**Why it matters.** Build the shared base once and derive from it; for richer derivations, register
the whole query over the streams instead.

### Delta and Iceberg table sinks

**The problem elsewhere.** Dataflow SQL writes Delta and Iceberg tables with full catalogue and object
store support. Some streaming databases and the Kafka-native stack have Iceberg sinks, with varying
reach.

**How Pravaha does it.**

- `delta-sink` keeps a Delta table equal to the view by key, or writes a changelog of every change with
  its weight, one Delta commit per checkpoint, on Delta Kernel and not Spark
  ([the Delta sink](../../console/content/topics/sink-delta.md)).
- `iceberg-sink` keeps an Iceberg table equal to the view through equality deletes, or writes a
  changelog, one snapshot per checkpoint, on iceberg-core and not Spark
  ([the Iceberg sink](../../console/content/topics/sink-iceberg.md), `IcebergSinkPluginTest`).
- **Partial**, because Iceberg is local-filesystem tables only: no object stores, catalog services,
  partitioned tables or schema evolution yet ([README](../../README.md), "What is not built").

**Why it matters.** A lakehouse table kept equal to a live view without a Spark cluster is useful today
on one machine; on an object store behind a catalog service, it is not yet.

### Recursive queries

**The problem elsewhere.** Some streaming databases maintain recursive queries incrementally
(`WITH MUTUALLY RECURSIVE`, or DBSP's recursion), and Differential Dataflow iterates natively. Dataflow
SQL and the Kafka-native engines do not.

**How Pravaha does it.**

- **Not built — a target.** The design names it as win condition W6
  ([`system_design.md` §2.5](../design/system_design.md#25-win-conditions)); the engine does not plan
  `WITH RECURSIVE` today.

**Why it matters.** Graph-shaped questions — transitive closure, reachability, fraud rings — need
another engine for now.

### SQL breadth

**The problem elsewhere.** The dataflow engines and the streaming databases run broad SQL; ksqlDB's is
narrower.

**How Pravaha does it.**

- Projections, filters, aggregates, tumbling and hopping windows, stream-stream and temporal joins,
  self joins, a maintained top-N and exact `DECIMAL` run. Session windows, recursive queries and much
  else do not.
- **12 of Nexmark's 23 queries run** (5 at the 2026-09-20 measurement), and what is missing is SQL,
  not speed ([gate pack](../project/gates/measured-2026-09-20/README.md), [README](../../README.md), "Performance").
- Every construct that runs and every refusal, with its code and reason, is in
  [`../guides/CONTINUOUS_QUERIES.md`](../guides/CONTINUOUS_QUERIES.md), checked against the planner.

**Why it matters.** Check the support matrix against the queries you need before choosing.

### Connector breadth

**The problem elsewhere.** Dataflow SQL and Kafka Connect have hundreds of connectors; streaming
databases concentrate on Kafka and change data capture; catalogues federate many sources for reading.

**How Pravaha does it.**

- Nine source types (files, feed directories, Delta, JDBC, Aerospike, Cassandra, PostgreSQL CDC,
  MySQL CDC, Kafka) plus another query's answer, two lookup sources and six sinks, all in the server jar
  (`ShippedConnectorsTest`); connections to JDBC, PostgreSQL CDC, Aerospike, Cassandra and Kafka can
  be encrypted.
- No Hudi sink, and no change feed from any database but PostgreSQL and MySQL
  ([README](../../README.md), "What is not built"). A plugin SPI and its TCK let others add more
  ([`../guides/CONNECTORS.md`](../guides/CONNECTORS.md)).

**Why it matters.** If your store is not on the list, you write a connector.

### Governing many engines and data at rest

**The problem elsewhere.** This is what governance catalogues are for: one set of names, grants and
lineage across many engines, files, models and a lakehouse's storage, across a cloud estate.

**How Pravaha does it.**

- **No.** Pravaha's catalogue governs one engine on one node, and what it computes
  ([ADR-059](../design/adr/059-the-pravaha-catalog-governs-live-answers.md), "Where this goes beyond Unity
  Catalog", which is also honest about this direction).
- Its sinks write tables another catalogue may govern; exporting its lineage in OpenLineage form is the
  intended bridge, in phase 4, and not built.

**Why it matters.** Pravaha complements an estate catalogue; it does not replace one.

### Scale-out and HA maturity

**The problem elsewhere.** Every other category except the research libraries runs clustered, and the
catalogues as services, with years of production behind them.

**How Pravaha does it.**

- **No.** One node. A standby takes over when a node's claim on its state goes stale
  ([standby](../../console/content/topics/standby.md)); multi-node execution is designed
  ([ADR-045](../design/adr/045-cluster-mode-assigns-queries-not-rows.md)) and **on hold by the owner**, and a node
  refuses `PARTITIONED` mode with `PRV-9002` rather than pretend ([`../development/REMAINING.md`](../development/REMAINING.md)).
- **The scaling gate is not reached**: eight-lane efficiency measured **28–42 % of linear** against a
  90 % target, on a development laptop rather than reference hardware
  ([gate pack](../project/gates/measured-2026-09-20/README.md)).
- The requirement is about 1,000 rows per second
  ([ADR-042](../design/adr/042-the-throughput-bar-is-the-requirement.md)).

**Why it matters.** A workload that needs a cluster needs another engine today.

### MFA and single sign-on

**The problem elsewhere.** Managed services in most categories sign people in through the
organisation's identity provider, with multi-factor authentication; self-run engines vary.

**How Pravaha does it.**

- **No, by decision.** The engine keeps its own users, Argon2id passwords, scoped expiring API keys and
  sessions, and the console signs each person in against it
  ([ADR-052](../design/adr/052-the-engine-is-the-identity-authority.md),
  [authentication](../../console/content/topics/authentication.md)). MFA and single sign-on were dropped
  by the owner ([README](../../README.md), "What is not built").

**Why it matters.** An organisation that requires its identity provider in front of every tool will
have to put a proxy in front of this one.

### A managed cloud service

**The problem elsewhere.** Every category but the research libraries is offered as a managed service by
at least one vendor.

**How Pravaha does it.**

- **No.** Pravaha runs where you run it: embedded, as a server, in the Docker image or under the Helm
  chart ([`../operations/DEPLOYMENT.md`](../operations/DEPLOYMENT.md)). Nobody operates it for you.

**Why it matters.** Someone on your side is on call for it.

### Ecosystem and support

**The problem elsewhere.** Each category has published artefacts, a community, vendors, training and
integrations; the research libraries have a research community.

**How Pravaha does it.**

- **No.** Nothing is published to any registry or index
  ([`../project/RELEASE_NOTES.md`](../project/RELEASE_NOTES.md), 0.1.0). It is one author's work, tried so far by a QA team
  on one node. It is built to be inspected rather than taken on trust: its decision records, gate
  packs, findings register and test suite are in the repository.

**Why it matters.** Nobody else is on call for it.

## What the rows have in common

- **A seam is a position, not a moment.** Every hand-over — a subscriber joining, a restart, a query
  attaching to a shared reader, a new version taking the name — is made at a point in the input named
  by source positions, and where equality at the seam cannot be shown the engine refuses rather than
  approximates. That is why replacement, sharing, chains and sinks are exact.
- **It refuses before the fact.** An unbounded query, a masked column used as a key, a filter that
  restricts nothing, a model's draft the engine cannot plan: each is refused with a code and a reason,
  not accepted and answered wrongly.
- **It governs the answer where it lives.** Grants, filters and masks apply to the live view and to the
  open stream, not to a copy of it somewhere else.

## The practical reading

Pravaha is not a smaller Flink or a cheaper Materialize, and it is not a catalogue. It is a narrow
tool: an engine that lives beside, or inside, the application and the stores it already has, keeps
the answers to a known set of questions current, serves and governs them where they are, and refuses
up front what it cannot keep bounded or exact. On one node, at the rates its requirement names, that
combination is one no category offers.

It is the wrong choice when the job needs horizontal scale, a long list of connectors, the widest SQL,
recursive queries, single sign-on, a managed service or a vendor to call: a dataflow engine, a
streaming database or the Kafka stack wins there today, and the table says so. Beside an estate
catalogue it is a complement: the catalogue governs the data at rest, Pravaha the answers computed
from it.

## Adjacent tools

Not competitors for the job, and often complements. *CDC tools* (Debezium, Striim, Qlik Replicate)
emit changes but maintain nothing; one can feed Pravaha. *Federated SQL* (Trino, Presto) answers
ad-hoc queries over many stores and serves nothing it maintained; it can read what Pravaha writes.
*Real-time OLAP stores* (Apache Pinot, Apache Druid, ClickHouse) serve fast SQL reads over ingested
data, with materialized views triggered on insert rather than retracting.

## What Pravaha borrows

| From | Idea | Where it lands |
|---|---|---|
| Feldera's DBSP | Z-sets and a mechanically derived incremental form for every operator | [ADR-013](../design/adr/013-zsets-and-dbsp.md) |
| Differential Dataflow, Materialize | Shared state between the questions that need it; the maintained view as the thing that is read | [ADR-025](../design/adr/025-registration-and-subscription-separated.md), [ADR-014](../design/adr/014-serve-maintained-views.md) |
| Flink | Aligned checkpoint barriers; watermarks from event time; sinks that commit when the checkpoint does | [ADR-008](../design/adr/008-aligned-checkpoints.md) |
| Spark | Whole-stage code generation with Janino | [ADR-005](../design/adr/005-whole-stage-codegen.md) |
| Calcite, as Flink and others use it | Parse and optimise with Calcite, execute with operators of one's own | [ADR-002](../design/adr/002-calcite-as-compiler.md) |
| Kafka Streams, Hazelcast Jet | A stream engine that is a library inside a JVM application | [ADR-019](../design/adr/019-spring-free-engine-core.md), [ADR-020](../design/adr/020-spring-boot-starter.md) |
| ksqlDB | Two ways to ask: a read of the answer, and a subscription to its changes | [ADR-026](../design/adr/026-one-subscription-model-three-carriers.md) |
| Materialize, RisingWave | The PostgreSQL wire protocol, so existing tools read a view | [the PostgreSQL gateway](../../console/content/topics/pgwire.md) |
| Debezium | Change data capture from PostgreSQL's logical replication — reimplemented, not depended on | [ADR-041](../design/adr/041-change-data-capture-without-debezium.md) |
| Trino | Connectors that say what they can evaluate, so the planner pushes only that | [connector guide §4](../development/guides/CONNECTOR_DEVELOPMENT.md#4-capabilities-claim-the-weakest-thing-that-is-true) |
| Unity Catalog | Namespaced objects, owners, tags, inherited grants, row filters and column masks as objects | [ADR-059](../design/adr/059-the-pravaha-catalog-governs-live-answers.md) |

## Disclaimer

Categories, not vendors. Each row says where a category's centre of gravity sits as understood in
**September 2026**; individual products differ, change between releases, and some cross these lines.
Nothing here is a statement about any one vendor's current offering or a measurement of it, and no
product named here has reviewed it. Where this page and a product's own documentation disagree, the
product's documentation is right about the product.
