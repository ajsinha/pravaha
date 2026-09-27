---
title: Sources, and choosing one
slug: sources-overview
category: sources
order: 10
icon: box-arrow-in-right
summary: "Where rows come from: the three configuration blocks (streams, sources, lookups), how a plugin is found, what each of the eight shipped sources can and cannot see, and how to pick one."
badge: START HERE
audience: Operators
keywords: [source, binding, plugin, connector, pravaha.sources, pravaha.streams, pravaha.lookups, options, classpath, serviceloader, capabilities, delivery guarantee, share.reader, pushdown, projection, partial aggregate, cdc, kafka]
guide: continuous-queries#2-declaring-a-stream
related: [streams, source-filesystem, source-jdbc, source-postgres-cdc, source-mysql-cdc, source-kafka, lookups, connector-security]
---

A **source** is what feeds a stream: a plugin, and the options that tell it where to read. Pravaha
ships eight stream sources and two lookup plugins, each discovered by name, each declaring honestly what
it can deliver. This page is the map: how a node is told about its data, what happens when a query
first needs a source, and how the eight differ in the one thing that decides whether your answer is
right — **what they can see**.

## Three blocks, kept separate on purpose

A node is described by three blocks under `pravaha:`, and they are separate because they are read by
different parts of the engine at different times.

| Block | Says | Read when | Needed for |
|---|---|---|---|
| `pravaha.streams.<name>` | What the stream **is**: `schema`, `event-time`, `out-of-orderness` | At startup, into the catalogue | Planning. A query can be written, validated and registered against a stream with nothing attached |
| `pravaha.sources.<name>` | Where its rows **come from**: `plugin` and `options`. The key is the stream it feeds | At the first registration that reads the stream | Running |
| `pravaha.lookups.<name>` | A dimension table a query may **ask** | At startup — opened eagerly | [Temporal joins](/help/topics/lookups) |

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  sources:
    txn:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        follow: "true"
```

Three rules that catch everyone once:

- **Plugin options live under `options:`.** A key written one level too high is not read, not reported,
  and the node starts and ingests nothing.
- **The schema is written twice** for a source that has no schema of its own (filesystem, feedfile,
  Aerospike, Cassandra, Kafka) — once for the catalogue to plan against, once for the plugin to decode with.
  The two are read by different components that do not share a parser, and must agree column for
  column. JDBC, Delta and postgres-cdc read the schema from the store, so only the catalogue copy is
  yours (postgres-cdc also takes a declared `schema`, checked against the table).
- **`event-time` is the setting people most often omit, and its absence used to be silent.** Without
  it no watermark advances and no window over the stream could ever close, so a windowed query over
  one is now refused at registration with `PRV-2002` naming the key. `out-of-orderness` is dropped
  too if `event-time` is not also declared.
  On a server node the declared column is handed down to the source as its `event.time` option, so you
  write it once.

The node logs all three at startup — read them before anything else when rows do not arrive:

```text
streams declared in configuration: [txn]
sources bound: [txn <- filesystem[path, schema, follow, event.time]]
```

A stream with **no binding** is not an error: it registers, runs, and receives only what a client pushes
into it — a legitimate way to feed an embedded engine. The node says so:
`no sources are bound, so registered queries receive rows only from clients that push them`.

## How a plugin is found

Each plugin reports its own name and is discovered with Java's `ServiceLoader` — **when a query is
first registered against the stream, not at startup**. So a binding naming a plugin that is not
present starts a server cleanly and fails at the registration that needs it, with PRV-5090 and the
names that *are* available:

```text
PRV-5090  no source plugin named 'jdbc' is on the classpath, so stream 'orders' cannot be fed. Available: [filesystem]
```

Lookups are the exception: they are opened at startup, so a missing lookup plugin or an unreachable
table stops the node from starting.

### Getting a plugin onto a node

Nothing to do: **every connector the project builds ships inside the server jar**, and so does the
PostgreSQL JDBC driver. `java -jar pravaha-server.jar` can bind any of them.

| Plugin | Module | In the server jar |
|---|---|---|
| `filesystem` (source and sink) | `plugins/pravaha-plugin-filesystem` | yes |
| `feedfile` | `plugins/pravaha-plugin-feedfile` | yes |
| `jdbc`, `jdbc-lookup`, `jdbc-sink` | `plugins/pravaha-plugin-jdbc` | yes — with the PostgreSQL driver; another database's driver is not bundled |
| `delta`, `delta-sink` | `plugins/pravaha-plugin-delta` | yes |
| `iceberg-sink` | `plugins/pravaha-plugin-iceberg` | yes |
| `aerospike`, `aerospike-lookup`, `aerospike-sink` | `plugins/pravaha-plugin-aerospike` | yes |
| `cassandra` | `plugins/pravaha-plugin-cassandra` | yes |
| `postgres-cdc` | `plugins/pravaha-plugin-postgres-cdc` | yes, driver included |
| `mysql-cdc` | `plugins/pravaha-plugin-mysql-cdc` | yes, no driver needed |
| `kafka` (source), `kafka-sink` | `plugins/pravaha-plugin-kafka` | yes |

**One limit worth knowing.** The launcher still reads only what is inside the jar: there is no
plugins directory and `-Dloader.path` is not honoured, so a JDBC driver for a database other than
PostgreSQL cannot yet be added at deployment time. Drivers for MySQL, Oracle and others are not
bundled because their licences do not allow a proprietary product to redistribute them freely.

**In an application that embeds the engine** (the embedded engine or the Spring Boot starter), a
plugin module on your application's classpath is found by `ServiceLoader` like any other — nothing
else to do. See [the embedded engine](/help/topics/embedded-engine).

## The eight sources side by side

What each can see decides what a view over it can mean. "Emits deletes" is the question to ask first:
a source that cannot see a delete gives a view that keeps serving deleted rows.

| | Reads | Emits deletes | Before-image | Incremental | Guarantee | Pushdown | Shared by queries |
|---|---|---|---|---|---|---|---|
| [filesystem](/help/topics/source-filesystem) | one delimited file, once or followed | only through `op.column` | no | yes (appends) | exactly-once | none | no |
| [feedfile](/help/topics/source-feedfile) | a directory of CSV/Parquet files | no | no | yes (new files) | exactly-once *or* at-least-once, by configuration | none | no |
| [jdbc](/help/topics/source-jdbc) | a table or `SELECT`, polled on a monotonic column | no | no | yes (beyond the watermark) | at-least-once | filter, columns, and `COUNT`/`SUM` partials with `key.column` | no |
| [postgres-cdc](/help/topics/source-postgres-cdc) | a PostgreSQL table's changes, from its write-ahead log | **yes** (the whole old row at `−1`) | **yes** — an update is `−1` then `+1` | yes (every commit) | exactly-once | none | no |
| [mysql-cdc](/help/topics/source-mysql-cdc) | a MySQL table's changes, from its row-based binary log | **yes** (the whole old row at `−1`) | **yes** — an update is `−1` then `+1` | yes (every commit) | exactly-once | none | no |
| [kafka](/help/topics/source-kafka) | a Kafka topic, one reader per partition | only with `format: changelog` (`kafka-sink`'s envelope, weights and all) | with `format: changelog` | yes (new records) | exactly-once | none | no |
| [delta](/help/topics/source-delta) | a Delta table: snapshot, then each commit | **yes** (removed files at `−1`) | as a retraction of the old row | yes (new commits) | exactly-once | none | no |
| [aerospike](/help/topics/source-aerospike) | a set, scanned by last-update time | no | no | yes (server-side filter) | at-least-once | filter, columns | **yes** |
| [cassandra](/help/topics/source-cassandra) | a table, scanned by `token()` range | no | no | **no** — every pass reads everything | at-least-once | columns | **yes** |

And the event time each stamps on a row — which is what the watermark, and so every window, runs on:

| Source | Event time of a row |
|---|---|
| filesystem | the `event.time` column (on a server, the declared `event-time`); without it, when the row was read |
| aerospike | the `event.time` bin, read as nanoseconds; without it, when its scan started |
| cassandra | the `event.time` `TIMESTAMP` column; without it, when its pass started |
| jdbc | the `watermark.column` value, as nanoseconds, unconverted |
| postgres-cdc | the `event.time` timestamp column (on a server, the declared `event-time`); without it, the transaction's commit time |
| kafka | the `event.time` timestamp column (on a server, the declared `event-time`); without it, the record's Kafka timestamp |
| feedfile, delta | the `event.time` timestamp column (on a server, the declared `event-time`); without it, **zero** — so declare the stream's `event-time` before windowing over them |

### Choosing

- **Files a producer appends to, or a changelog you can write** → [filesystem](/help/topics/source-filesystem).
  The only way to feed retractions from configuration.
- **Batch drops — an export, a partner feed** → [feedfile](/help/topics/source-feedfile), with
  `completion: marker` if the producer can write one.
- **A PostgreSQL table whose deletes and updates must reduce totals** → [postgres-cdc](/help/topics/source-postgres-cdc),
  if the DBA can grant logical replication; it leaves a replication slot on the server to look after.
- **A Kafka topic** → [kafka](/help/topics/source-kafka): exactly once from the checkpoint's offsets.
  `format: changelog` reads another query's `kafka-sink` changelog with its retractions.
- **A relational table with no change feed you can use** → [jdbc](/help/topics/source-jdbc), on a
  database-maintained monotonic column. Deletes will not reduce totals.
- **A lakehouse table whose upstream updates and deletes must flow through** → [delta](/help/topics/source-delta).
- **Current state in Aerospike** → [aerospike](/help/topics/source-aerospike): incremental, pushed down,
  one scan for many queries.
- **Current state in Cassandra** → [cassandra](/help/topics/source-cassandra), with a keyed view over it.
- **Reference data to enrich with** → not a source at all: a [lookup](/help/topics/lookups).

Two different sources can feed one query: a join across a `filesystem` stream and a `feedfile` stream
is demonstrated end to end by the engine's own tests. The joined query's watermark is the minimum of
its inputs', so it is only as current as its laggiest source.

## Capabilities: what a plugin declares

Every source declares, in code, what it can promise: replayable offsets, ordering within a partition,
deletes, before-images, a delivery guarantee, which pushdown kinds it accepts, and a typical latency.
Today three things read those declarations:

- **Pushdown.** A source is asked only for the kinds it declares. `FILTER` (JDBC, Aerospike): the
  conjuncts of a `WHERE` directly over the scan that compare one column with a literal. `PROJECT`
  (JDBC, Aerospike, Cassandra): the columns the query reads, when its path to the scan is only
  projections and filters. `PARTIAL_AGGREGATE` (JDBC with `key.column`): a continuous global
  `COUNT`/`SUM` taken by the database, asked for only when every predicate can go with it. The engine
  keeps its own filter on rows whatever the source does, so filter and column pushdown change bytes
  read, never answers; a partial is declined, and rows read instead, whenever it could not be exact.
  Each source's page lists exactly what it pushes and what it declines.
- **Sharing.** A source that is at-least-once, replayable and unordered (Aerospike, Cassandra) is read
  once per binding for every query over it, and that one reader pushes the `OR` of its queries'
  filters and the union of their columns; the others are read once per query. `share.reader: "false"`
  on a binding opts out, giving each query its own narrower read.

- **The retraction check.** A source that declares it emits deletes — postgres-cdc, Delta, Kafka
  with `format: changelog` — makes every query over it one that can withdraw rows, so pointed at an
  append-only sink it is refused with [PRV-2041](/help/codes/PRV-2041), a plain filter included
  ([sinks](/help/topics/sinks-overview)).

What the declarations do **not** do yet is refuse a query for needing deletes its source cannot see:
a view over a source that cannot see a delete is accepted
([ADR-028](/help/decisions/028-connectors-earn-their-place): declared, not enforced). Choose the
source with the table above in mind.

## Pitfalls

!!! warning "Pitfall: the stream is declared, the plugin is not there"
    `sources bound` in the startup log proves the binding was read, not that the plugin exists. The first
    registration is what finds out. Register one query right after deploying a new source and read its
    answer.

!!! warning "Pitfall: a scan or a poll cannot see a delete"
    Of the eight, only postgres-cdc, Delta, Kafka with `format: changelog` (and filesystem with
    `op.column`) can withdraw a row. Over
    JDBC, Aerospike and Cassandra, a deleted row simply stops appearing — and a view that already
    counted it keeps it. Model deletes as a status column you filter on, or choose a source that sees
    them.

!!! note "Credentials belong in the environment"
    `password: "${PRAVAHA_DB_PASSWORD}"` keeps the secret out of the file. See
    [connector security](/help/topics/connector-security) for how placeholders resolve and what every
    connector's TLS verifies.

## Where next

- [Streams](/help/topics/streams) — the declaration itself: schema grammar, event time, lateness
- [The filesystem source](/help/topics/source-filesystem) — the one to start with
- [The Kafka source](/help/topics/source-kafka) — a topic, exactly once
- [Lookup tables](/help/topics/lookups) — reference data asked, not consumed
- [Connector security](/help/topics/connector-security) — credentials and TLS for every store
