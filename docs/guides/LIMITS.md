# Known limits, and what could still be built

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

> The full text of what the README summarises under "What is not built". Each entry says what is true
> now, and then, in bold, whether it is **deferred**, **buildable** or a **boundary**. A boundary is a
> property of a store, a format or a recorded decision, and building more code would not remove it.
> The order in which the buildable entries get built is in [`../development/REMAINING.md`](../development/REMAINING.md).


## Deferred by decision

- **Multi-node execution.** Membership produces a real partition assignment, and partition ownership
  is a fenced lease proved against a real ZooKeeper ensemble — but no node consumes it yet, so
  execution is single-node and a node refuses `PARTITIONED` mode (`PRV-9002`) rather than serve every
  partition while claiming to own some. Rebalance and handoff are built as a library and wired to
  nothing ([ADR-039](../design/adr/039-ga-includes-the-known-gaps-and-clustering.md) item 8).

  **Deferred by the owner's decision** (2026-09-26: build every gap except multi-node). The lease, assignment and handoff libraries stay tested; wiring them is ADR-039 item 8.


## Buildable: work that is simply not done yet

- **One read of a source per query, for JDBC, CDC, Delta and followed files.** With lane sharing
  (`pravaha.lane.multiplex.enabled`, `auto` by default: from the 65th query) any registered query
  shares a lane — inbox, arena and thread — whatever it reads, joins included, and a reader shared by
  several queries writes each row into a shared lane once for all of them (LANE-2). Readers are shared
  for sources that declare at-least-once and no order (SRC-3; Aerospike and Cassandra), and exactly
  for Kafka and a file read once through, which meet a joining query at an exact position (ADR-054).
  A JDBC, CDC or Delta source, or a followed file, still keeps a reader per query, each writing its own
  copy into the shared inbox, so a thousand queries over one such source read it a thousand times.

  **Built for Kafka and for files read once through** ([ADR-054](../design/adr/054-an-ordered-source-is-shared-at-an-exact-seam.md)): one reader per binding, each record to each query once and in order, and partitions a topic gains joined by every query sharing its reader. **Buildable:** Delta and JDBC, once their positions are shown to be totally ordered. postgres-cdc stays per query: its slot can be confirmed only up to the slowest member's checkpoint.

- **A secondary predicate is a probe only on a declared column, by equality.** The design's
  `CREATE CONTINUOUS QUERY` grammar is built: `RANGE (column)` keeps an ordered index over the key's
  last column, a lookup by the whole key is a hash probe on any view, and `INDEX (column)` (or `WITH
  (index = 'column')`) keeps an equality index over one column outside the key, kept in the view's
  own commit and journalled with the registration, so `WHERE column = literal` or `column IN (...)`
  probes it ([ADR-055](../design/adr/055-an-equality-index-over-a-column-outside-the-key.md)). What is still a
  scan and a filter: a column nobody declared, a range on a non-key column, `<>`, an `OR` across
  columns, and a partial key with no index. At most four equality indexes per view, on the heap and
  bounded by the view's key ceiling; they do not spill, because the view does not. Refused at
  registration by name: an `INDEX` over `FLOAT`, `DECIMAL`, `BYTES` or the view's whole key
  (`PRV-2074`), `RANGE` over a column with no total order (`PRV-2073`), a `WITH` option that does
  not exist (`PRV-8017`), `EMIT CHANGES WITH (...)` (`PRV-2072`), and `INSERT INTO <sink> SELECT`
  (`PRV-2020`) — which carries neither the query's name nor its key, so the refusal names `WRITING
  TO`, `WITH (sink = ...)` and `--sink` instead
  ([ADR-049](../design/adr/049-an-ordered-index-over-the-keys-last-column.md)).

  **Buildable:** design §17.2's warning at registration that suggests an index, and a plan a user can
  ask for that shows which access path a view read took (today the view's counters say it).

- **Queries over queries follow an answer; not every operator can.** A continuous query whose `FROM`
  names a registered query follows that query's answer, exactly once across restarts
  ([ADR-056](../design/adr/056-queries-on-queries.md)). Over such an input filters, projections and unwindowed
  `COUNT`/`SUM`/`AVG` run; windows, joins, top-N, `MIN`, `MAX` and `COUNT(DISTINCT)` are refused
  (`PRV-2075`), and so are replacing a member of a chain and `RETAIN FOR` on a query over a view
  (`PRV-8026`). A chain is at most **eight** levels deep (`PRV-8027`), and a downstream holds one
  on-heap entry per upstream row for what it has consumed, bounded by the upstream's key ceiling.
  Chains are single-node: a downstream on a node without its upstream plans as an unknown name.

  **Buildable:** windows over a view (they need the upstream to carry a watermark over one of its
  columns), `MIN`/`MAX` and `COUNT(DISTINCT)` with weighted per-group state, and a replacement that
  hands its followers across at the cutover's position.

- **The snapshot-and-change-feed splice: a boundary, not a gap.** Design §16.1's other backfill — a
  table snapshot joined to a change feed, deduplicated by the store's own version — is built and
  tested as `SplicedReader` in `pravaha-backfill`, and deliberately reachable from no running path.
  Its rule (drop a snapshot row when the feed holds a change to its key at or after the row's
  version, then replay the feed) is right for a feed of whole rows where the newest wins, and wrong
  for the weighted changelog this engine reads. `postgres-cdc` sends an update as the old row at
  `-1` and the new at `+1`: the splice drops the old row from the snapshot, then replays its `-1`,
  which retracts a row that was never added — `SUM` over one row updated from 5 to 7 reads 2. Nor
  does `postgres-cdc` need it: its `snapshot.mode: initial` is already exact, at one consistent LSN,
  with no deduplication at all, and it has no per-row version comparable with its feed. A
  replacement's backfill meets the running version at an offset
  ([ADR-046](../design/adr/046-a-replacement-meets-the-running-version-at-a-position.md)) instead.
  The design's `backfill.parallelism`, `backfill.window` and `backfill.adaptive` are refused by name
  (`PRV-4018`): a backfill reads each partition once, from the beginning, at the rate an operator
  sets, and nothing probes the store's own latency to adapt to.

  **Buildable:** a splice for a weighted feed would drop a snapshot row only when the feed holds the
  `+1` of that exact row version, and needs a source whose snapshot rows carry a version the feed
  also carries; no source here has one. `backfill.adaptive` would need a latency probe per store.

- **Sinks: six, and two lakehouse formats.** `filesystem`, `aerospike-sink`, `jdbc-sink`,
  `kafka-sink`, `delta-sink` and `iceberg-sink`, and no others. Each is unit-tested without a server and again
  against a real one — Aerospike, PostgreSQL and a Kafka broker under Testcontainers, Delta and
  Iceberg tables on the local filesystem — so a machine without Docker skips those, by name, rather than passing.
  `kafka-sink` writes JSON, or Avro or Protobuf values in upsert mode, compressed with `none`, `gzip`, `snappy` or `zstd`; `lz4` is refused (see the boundary below).
  **Delta and Iceberg are the lakehouse formats written**; there is no Hudi sink.
  `iceberg-sink` writes unpartitioned tables on the **local filesystem only** (no object store, no
  catalog service), never evolves a table's schema, holds an upsert checkpoint's collapsed changes
  in memory until `prepare` — at most `upsert.max.keys` distinct keys, refused past that with
  `PRV-5143` rather than spilled (an equality delete cannot apply to data of its own commit) — and
  runs no compaction or snapshot expiry; its equality deletes cost a reader until the table's own
  engine compacts. It stays on Iceberg 1.2.1, the newest on the Parquet the Delta and feedfile
  connectors share, with Avro and Caffeine moved to fixed and server-aligned versions. `delta-sink` writes unpartitioned or partitioned tables
  (`partition.columns`), creates no deletion vectors — and refuses to rewrite a table whose files
  carry them (`PRV-5055`) — and runs no compaction: `OPTIMIZE` and `VACUUM` belong to an engine that
  has them. The `delta` source reads deletion vectors, so a row a `DELETE` marks deleted reaches a
  view as a retraction. Its upsert mode rewrites the data files holding a changed key, so a commit costs in proportion to the table
  rather than to the change.

  `kafka-sink` writes Avro and Protobuf values in upsert mode only (built 2026-09-27); its changelog
  envelope is JSON, since a schema's fields have no place for the op and weight. Keys are JSON by
  default, or text, Avro or Protobuf (`key.format`). It registers no schema: `schema.id` and
  `key.schema.id` are checked against `schema.registry.url` at registration when it is set (an Avro
  id without a registry is written unchecked; a Protobuf id needs one, for its message indexes). An
  Avro time field takes a column declared no finer than it (`TIMESTAMP(3)` for `-millis`), each value
  floored to the declared digits.

  **Buildable:** `iceberg-sink` on an object store (S3 through Iceberg's own FileIO) or a REST or
  Hive catalog, and partitioned Iceberg tables. Compaction stays with the table's own engine.

- **Change data capture, beyond one PostgreSQL table's changes.** `postgres-cdc`
  ([ADR-041](../design/adr/041-change-data-capture-without-debezium.md)) streams one table per binding
  from PostgreSQL 14 or later, one slot per binding and one query per binding: a second, different
  query over a binding is refused at registration (`PRV-8028`, CDCREPL-2) rather than left to fail on
  the slot 15 seconds later; bind the table a second time, with a slot of its own, for a second query. Rows already in the table are delivered
  only with `snapshot.mode: initial` (it needs a primary key; `never`, changes only, is the default),
  and that snapshot is exact across a restart half-way through it. A `TRUNCATE` of the captured
  table stops it (`PRV-5116`) rather than being guessed into retractions, and only MySQL (`mysql-cdc`, below) has a change
  feed besides — the other sources poll or scan. Aerospike and Cassandra scans see deletes only with
  `deletes: detect`, which compares each full pass with the rows already emitted and retracts what is
  gone: a delete arrives up to a scan interval late, two writes between passes are still one, and
  every emitted row is held in memory (about 150 bytes plus the row), bounded by `deletes.max.keys`.
  Its replication slot retains WAL on the database until a checkpoint confirms it
  ([`../operations/OPERATIONS.md`](../operations/OPERATIONS.md)).
  Without it (the default) a scan repeats rows — Cassandra every pass, Aerospike on an update — and
  so, where a `jdbc` update moves the watermark column, does a poll: a keyed view of the rows is
  right, and an aggregate, a join or an append-only sink over such a stream is refused at
  registration with `PRV-2042` naming the fix, rather than counting a row again (SCAN-1).

  `mysql-cdc` streams one MySQL table per binding from the row-based binary log
  (`binlog_format = ROW`, `binlog_row_image = FULL`, both refused otherwise, by name). This version
  reads changes only: `snapshot.mode: initial` is refused (`PRV-5150`), so rows already in the table
  must come another way. It connects in plaintext (`tls.*` is refused), takes no declared `schema`,
  and maps no `JSON`, `ENUM`, `SET`, `BIT`, `TIME`, `YEAR` or spatial column. Positions are a binlog
  file and offset, and with `gtid_mode = ON` the executed GTID set too, which survives a failover to a
  replica that has caught up (one that has not is `PRV-5158`). MySQL keeps binlog files by time, not by
  what a replica has read, so a checkpoint whose following changes are purged cannot be resumed
  (`PRV-5155`); an idle table's position follows the log, so that takes an outage, not a quiet table.
  `TRUNCATE`, `ALTER`, `DROP` or `RENAME` of the captured table, or a column type change seen in the
  binlog's table description, stops it (`PRV-5156`); `CHAR`/`BINARY`, `VARCHAR`/`VARBINARY` and
  `TEXT`/`BLOB` share a binlog type, and a change between them is seen only as the `ALTER` itself.

  **Neither CDC source can be replaced in place** (`CREATE OR REPLACE`, `PRV-4018` naming the slot or
  replica id): the running version is the one reader of the binding's replication slot or replica
  connection, and a second could neither start beside it nor replay what it has read (CDCREPL-1).
  Drop and register the new version, or register it on a second binding with a slot (`server.id`)
  of its own. A debug fork is refused the same way (`PRV-8012`).

  **Buildable:** `mysql-cdc`'s initial snapshot and TLS. A CDC replacement that
  catches up on a slot of its own from an initial snapshot, for a query whose running version also
  began from one. `TRUNCATE` stays a refusal: it names no rows to retract.


## Boundaries: limits of the stores, the formats or a decision

- **Pushdown past what the stores can say exactly.** Projection is pushed into JDBC, Aerospike and
  Cassandra, and a continuous `COUNT`/`SUM` into JDBC as one partial per polled page — but only
  there: Aerospike would need Lua UDFs on the cluster and Cassandra re-reads its whole table each
  pass, so neither claims a partial. A windowed aggregate is never pre-combined, nor a `MIN`/`MAX`
  (not retractable), nor anything filtered by a predicate SQL cannot carry. Cassandra pushes a
  filter only on the key: the whole partition key by equality, then clustering restrictions in their
  declared order; anything else would need `ALLOW FILTERING`, which reads every partition anyway.
  `EXPLAIN` shows the plan, not what a source was asked for; a query's feed description does
  (`feed.description` on `GET /api/v1/queries/{name}`), including what Cassandra was asked for
  ([ADR-039](../design/adr/039-ga-includes-the-known-gaps-and-clustering.md) item 6).

  **A boundary of the stores.** `MIN`/`MAX` cannot be retracted incrementally, an Aerospike partial needs UDFs installed on the cluster, and a Cassandra filter off the key needs `ALLOW FILTERING`.

- **Transactional sinks cost a second write.** `jdbc-sink`, `kafka-sink` and `delta-sink` are
  transactional, and none uses its store's own two-phase commit: `jdbc-sink` stages each
  checkpoint's changes in a staging table, `kafka-sink` in a staging topic, `delta-sink` in a
  staging directory inside the table, and each applies them in one transaction once the
  checkpoint is durable (Kafka has no prepare a restarted producer could commit, and a Delta commit
  is visible the instant its log entry lands; see [`CONNECTORS.md`](CONNECTORS.md)). So every
  change is written twice and the output trails the view by up to a checkpoint interval.
  `kafka-sink` is exactly once to a `read_committed` consumer only. `aerospike-sink` stays effectively once and `filesystem` at least once, whose repeats after a
  restart stay in the file. End to end is still capped by the source: one that cannot rewind to a
  checkpoint's offsets (ADR-029) is at least once whatever the sink does.

  **A deliberate trade, with one exception built:** `jdbc-sink` over PostgreSQL can use `commit.mode: prepared` (`PREPARE TRANSACTION` / `COMMIT PREPARED`), writing each change once, at the cost of `max_prepared_transactions > 0` on the database and row locks held for a checkpoint interval. Staging stays the default. Kafka and Delta have no equivalent.

- **The console has its persona surfaces but not the §23.20 release gate** — workbench, catalog,
  views, live results, operations with lane backpressure and per-operator numbers on the plan, a
  dead-letter screen, a backfill and cutover screen, a plugins screen built on the engine's
  manifest listing, and admin screens for access, the audit trail and tenants are built — the
  tenants screen shows each tenant's queries and state keys against its admission quotas, and the
  registrations refused for it, from the engine's `GET /api/v1/tenants` (ADR-050) — and a
  headless-Chrome suite holds zero axe violations, visual baselines in light and dark at both
  densities, the measurable §23.15 budgets, the eight states of §23.12 screen by screen, and all
  eight journeys, all of them end to end. A component gallery the
  console renders itself stands in for Storybook, which is not adopted (it needs Node). Not done:
  the manual WCAG 2.2 AA audit, plus cluster screens, and editing grants or quotas (the engine is
  not where grants live, and quotas are the node's configuration).

  **Not code, deferred, or a boundary:** the manual WCAG audit is a person's task, cluster screens wait for multi-node, and grants live outside the engine by design.

- **The Kafka source reads JSON, Avro and Protobuf — with no library for any of them.** JSON rows or
  `kafka-sink`'s changelog; Avro's binary encoding through a reader written here from the
  specification, against `schema.file` or a schema id fetched from a Confluent-compatible registry
  over its REST API (Karapace and Apicurio included); and Protobuf through `DynamicMessage` over a
  descriptor set the deployment supplies. No `org.apache.avro`, and no Confluent client. Columns are
  matched by name and a schema that cannot be mapped is refused at registration (`PRV-5108`). A
  proto3 scalar without `optional` has no presence, so it reads as its type's default and never as
  NULL. An upsert topic's tombstones cannot be retractions (a tombstone does not say what row it
  deletes), so they are refused or, with `tombstone: skip`, ignored. Partitions added to the topic
  while a query runs are found every `partitions.refresh` and read from their first record. Its broker
  tests, like the sink's, need Docker.

  **A boundary of the formats:** the proto3 presence rule and tombstones are properties of the formats, not gaps.

- **Kafka's `lz4` codec.** The `kafka` source and `kafka-sink` read and write `none`, `gzip`, `snappy`
  and `zstd`. `snappy` and `zstd` are snappy-java and zstd-jni, the two native libraries the build
  allows because Parquet needs them, so they work on the platforms those are built for (glibc Linux,
  macOS, Windows, FreeBSD). `lz4` would need lz4-java, a third native family: `kafka.compression.type:
  lz4` is refused at configuration (`PRV-5100`) and an lz4 batch stops the source's reader (`PRV-5107`),
  both naming the ADR ([`CONNECTORS.md`](CONNECTORS.md), "Compressed topics").

  **Decided, not missing** ([ADR-053](../design/adr/053-native-code-only-where-java-cannot.md)): allowing a third native family takes an ADR.

- **The spill tier is survival, not capacity.** There is no RocksDB, by decision
  ([ADR-044](../design/adr/044-no-rocksdb-the-mapped-tier-is-l1.md)): the memory-mapped overflow tier is
  the on-disk tier, and it is finished — `COUNT(DISTINCT)` spills, churned slabs are compacted away,
  the disk is budgeted in bytes (`max-bytes`, refusing by code before it fills), and a key index
  spills whole, its slot table included, so a spilled query's memory no longer grows with its keys.
  It stays off by default. Both measurements are in the ADR: while the page cache holds the files,
  spilled state runs within about 2x of RAM; with the process capped below its state (cgroup v2,
  swap off), uniformly random access falls to 1,000–2,500 operations a second on an NVMe — two or
  three page faults each, and 124 KiB read per fault from the kernel's read-around — with a tail of
  seconds while the kernel reclaims. Size the page cache for the index.

  **Decided, not missing** ([ADR-044](../design/adr/044-no-rocksdb-the-mapped-tier-is-l1.md)).
