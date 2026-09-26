# The connector framework

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential; see [`../LICENSE`](../LICENSE).

> **The single source of truth for writing a connector.** [`ARCHITECTURE.md`](ARCHITECTURE.md) and
> [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) link here rather than repeating it. If they and this
> disagree, this one wins — it is the one kept beside the SPI.

Read this if you are adding a source, a sink or a lookup, or deciding whether a store can be one.

> **Using a connector, or building one?** The line between this document and
> [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) is not connectors-versus-queries; it is
> **using versus building**. Configuring a source that already ships — its YAML, its required and
> optional keys — is [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1, because you cannot run
> your first query without it. Everything about writing a *new* one is here: the SPI, the TCK, what
> a connector may honestly claim, and why change-data-capture is the shape the engine was built for.
> Neither repeats the other.

---

## 1. The shape of it

A connector is a **jar with a service declaration**. Nothing in the engine is edited, nothing is
rebuilt, and the engine never learns the connector's name at compile time.

```
your-connector.jar
├── com/example/CassandraSourcePlugin.class
└── META-INF/services/
    └── com.ash.messaging.pravaha.api.plugin.StreamSourcePlugin   ← one line: com.example.CassandraSourcePlugin
```

Drop it on the classpath, name it in configuration, and a query can read from it.

**"On the classpath" means on the running process's classpath, and the shipped `pravaha-server`
executable jar carries `filesystem` alone.** Every other name in the tables below — `feedfile`,
`jdbc`, `delta`, `aerospike`, `cassandra`, `postgres-cdc`, `kafka` and their sink counterparts —
lives in its own module under `plugins/` and has to be added to the classpath of the node that is
to use it, which is a packaging decision rather than a configuration one. Naming one that is not
there is refused at startup with `PRV-5090`, and that refusal now says which of the two mistakes it
is, because "Available: [filesystem]" read beside this document's seven names looked like a
contradiction rather than an answer (CFG-4). There is **no drop-a-jar-in directory** yet: see
[section 8](#8-what-is-missing-from-this-framework-today).

Three kinds, and a connector may be more than one:

| Interface | What it does | Shipped examples |
|---|---|---|
| `StreamSourcePlugin` | Rows in. The thing a `FROM` clause reads | filesystem, feedfile, aerospike, delta, jdbc, cassandra, `postgres-cdc` (a changelog: deletes and before-images), `kafka` (a topic, one reader per partition, exactly once from the checkpoint's offsets) |
| `StreamSinkPlugin` | Rows out — every commit of a query that names the sink at registration ([ADR-043](adr/043-how-a-continuous-query-names-its-sink.md)). A sink with a configured schema or key reports it through `schema()` and `keyColumns()`, and a registration that does not match is refused | filesystem (append-only), `aerospike-sink` (upsert and delete by key, composite keys), `jdbc-sink` (upsert and delete by key or append, into a table you create; transactional through a staging table, so exactly once on a checkpointed node), `kafka-sink` (keyed JSON upserts with a tombstone for a retraction, or an explicit changelog, to a topic you create; transactional through a staging topic, so exactly once to a `read_committed` consumer on a checkpointed node), `delta-sink` (a Delta Lake table kept equal to the view by key, or a changelog of every change; one Delta commit per checkpoint, so exactly once on a checkpointed node) |
| `LookupSourcePlugin` | Point lookups for a temporal join's right side | `aerospike-lookup`, `jdbc-lookup` — **with the suffix**: a plugin answers to the name it reports for itself, and these two report `aerospike-lookup` and `jdbc-lookup`. This row said "aerospike, jdbc" until CFG-4, so `pravaha.lookups.<n>.plugin: jdbc` copied from it was refused at startup with `PRV-5090` |

---

## 2. The contract, in full

### `PravahaPlugin` — every plugin

```java
String  name();                  // what configuration calls it
Version version();               // yours
Version requiredApiVersion();    // defaults to the API you compiled against
void    configure(PluginContext context) throws ConfigurationException;
void    open();
void    close();
```

`configure` is given the settings and **must validate them there**. A setting that is wrong should
fail at `configure`, not at the first row — a node that starts and then fails per-record is much
harder to diagnose than one that refuses to start.

### `StreamSourcePlugin`

```java
SourceCapabilities   capabilities();
List<StreamSchema>   discoverSchemas();
List<SourcePartition> partitions(String streamName);
PartitionReader      createReader(SourcePartition partition, SourceOffset resumeFrom);
```

**Partitions are the unit of parallelism.** One reader per partition, and a partition's rows are
delivered to the lane that owns its key range. A source with no natural split returns one partition;
Kafka returns one per topic-partition.

### `PartitionReader`

```java
int         poll(RecordSink sink, int maxRecords);   // rows written, 0 when nothing is ready
SourceOffset position();                              // resume point, checkpointed
default void checkpointed(SourceOffset offset) {}     // a checkpoint holding this offset is durable
void        close();
```

`poll` returning `0` means *nothing right now*, not *nothing ever*. The engine will ask again. Write
rows through `sink.beginRow()` and `commit()`; never buffer a batch of your own, because the row you
are given is a cell in the lane's inbox and copying defeats the whole memory design
([`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) §4).

`checkpointed` is for a source that holds something on the store's side until told it may let go.
The engine calls it, from the checkpointing thread, once the checkpoint recording that offset is
durable (`PeriodicCheckpointer`, after `CheckpointStore.store` returns) — the only moment it is safe
to release history, because a restart can only resume from a durable checkpoint. `postgres-cdc`
confirms its replication slot here and nowhere else; `kafka`, which keeps its position in the
checkpoint alone, uses it only to report that position to a consumer group for lag monitoring when
`monitoring.group` is set; every other shipped source leaves the default no-op. A reader shared
between queries is not told.

### `SourceCapabilities` — the part that is load-bearing

```java
record SourceCapabilities(
        boolean replayableOffsets,
        boolean orderedWithinPartition,
        boolean emitsDeletes,
        boolean emitsBeforeImage,
        DeliveryGuarantee guarantee,      // AT_MOST_ONCE | AT_LEAST_ONCE | EXACTLY_ONCE
        Set<PushdownKind> pushdown,       // FILTER | PROJECT | PARTIAL_AGGREGATE
        Duration typicalLatency,
        boolean repeatsRows)              // the seven-argument constructor means false
```

**These are promises the engine acts on, not documentation.** `SharedSourceGroup` refuses to share
one reader between queries when a source claims `EXACTLY_ONCE`, non-replayable offsets, or ordering
within a partition — because the catch-up handover for a late joiner duplicates its overlap, and
that is unacceptable for a source promising exactly-once (SRC-3).

**`repeatsRows` is whether the feed is a changelog at all** (SCAN-1). Say `true` when, in normal
running and not only after a failure, the source can deliver a row it already delivered without
retracting the earlier copy — a periodic scan re-reading an unchanged row, a poll re-reading an
updated one, a watermark filter that re-reads its boundary. Every copy arrives at `+1`, so the
registry refuses, with `PRV-2042`, anything whose answer depends on how many times a row arrived:
an aggregate, a join, a sink that cannot upsert by key. A keyed view of the rows stays admitted,
because a copy only overwrites its own key. Answer per configuration: `cassandra` and `aerospike`
say `true` under `deletes: ignore` and `false` under `deletes: detect`; `jdbc` says `false` only
with `key.column` and `watermark.moves.on.update: false`. A source that repeats cannot claim
`EXACTLY_ONCE` — the record refuses the pair. Re-delivery after a crash is the guarantee's to
describe, not this flag's. Omitting the argument (the seven-argument constructor every plugin used
before the flag existed) means `false`, so **a scan-shaped connector must pass it**, or its
aggregates are silently wrong in exactly the way SCAN-1 was.

So **a connector that overstates its guarantee gets different engine behaviour, and the failure is
silent duplication.** Claim the weakest thing that is true. `SourceCapabilities.minimal()` is
at-least-once with no pushdown and is the right starting point.

---

## 3. Writing one: a worked example

A minimal source over an imaginary store.

```java
public final class ExampleSourcePlugin implements StreamSourcePlugin {

    private String endpoint;
    private StreamSchema schema;
    private ExampleClient client;

    @Override public String name() { return "example"; }
    @Override public Version version() { return Version.of(1, 0, 0); }

    @Override
    public void configure(PluginContext context) {
        // Validate here. A bad setting must not survive to the first row.
        this.endpoint = context.get("endpoint", "");
        if (endpoint.isBlank()) {
            throw new ConfigurationException(
                    ExampleErrors.BAD_CONFIGURATION,
                    "example.endpoint is required; it is the host:port this connector reads from");
        }
        this.schema = FilesystemSourcePlugin.parseSchema("example", context.get("schema", ""));
    }

    @Override public void open()  { this.client = ExampleClient.connect(endpoint); }
    @Override public void close() { if (client != null) client.close(); }

    @Override
    public SourceCapabilities capabilities() {
        return new SourceCapabilities(
                true,                       // replayableOffsets: we can resume from a token
                true,                       // orderedWithinPartition
                false,                      // emitsDeletes: this store only appends
                false,                      // emitsBeforeImage
                DeliveryGuarantee.AT_LEAST_ONCE,
                Set.of(PushdownKind.FILTER),
                Duration.ofMillis(50));
    }

    @Override public List<StreamSchema> discoverSchemas() { return List.of(schema); }

    @Override
    public List<SourcePartition> partitions(String streamName) {
        return client.shards().stream()
                .map(shard -> new SourcePartition(streamName, shard.id()))
                .toList();
    }

    @Override
    public PartitionReader createReader(SourcePartition partition, SourceOffset resumeFrom) {
        return new ExampleReader(client, partition, resumeFrom, schema);
    }
}
```

And the reader's `poll`:

```java
@Override
public int poll(RecordSink sink, int maxRecords) {
    int produced = 0;
    for (ExampleRecord record : client.read(partition, cursor, maxRecords)) {
        RowWriter row = sink.beginRow();           // a cell in the lane's inbox
        row.setLong(0, record.id());
        row.setString(1, record.name());
        row.eventTimestampNanos(record.timestamp())
           .weight(1L)                             // +1 = insert. See §5
           .sequence(record.offset())
           .commit();
        cursor = record.offset();
        produced++;
    }
    return produced;                                // 0 means "not now", never "never"
}
```

### Then run the TCK

```java
class ExampleSourceTckTest extends SourcePluginTck {
    @Override protected StreamSourcePlugin createPlugin() { return configured(); }
    @Override protected String streamName()               { return "example"; }
    @Override protected int expectedRecordCount()         { return 100; }
    @Override protected RowCollector newCollector(StreamSourcePlugin p) { return new Collector(p.schema()); }
}
```

`SourcePluginTck` is the conformance suite the four shipped source connectors run against. It checks
the things that are easy to get subtly wrong: that `poll` respects `maxRecords`, that an offset
round-trips, that resuming delivers exactly the remainder, that `close` is idempotent, that `poll`
after exhaustion returns zero rather than throwing.

**Run it before you believe your connector works.** Four of this project's own connectors have had
defects it would have caught.

---

## 4. Joining across different sources

**A continuous query can join streams that come from entirely different connectors.** One side from
Kafka, another from Aerospike, a third from Postgres, in one `SELECT`.

This works because a binding is per *stream name*, not per query:

```yaml
pravaha:
  sources:
    orders:                              # a directory of files arriving over time
      plugin: feedfile
      options:
        dir: /var/feeds/orders
        schema: "id:INT64,customer_id:STRING,sku:STRING,qty:INT64,ts:TIMESTAMP"
    customers:                           # Postgres, through the jdbc plugin
      plugin: jdbc
      options:
        url: "jdbc:postgresql://db-1:5432/crm"
        table: public.customers
        watermark.column: updated_at
        # What updated_at's numbers mean, so a row's event time can be built from them:
        # none (the default), nanos, micros, millis, seconds. `none` says the column is a
        # cursor and not a time, and rows then carry no event time -- so no window over this
        # stream can close. The column used to be stamped raw, which made an epoch-millis
        # column an event time out by a factor of a million, silently (T-5).
        watermark.unit: millis
    prices:                              # an Aerospike set
      plugin: aerospike
      options:
        hosts: "as-1:3000"
        namespace: ref
        set: prices
        schema: "sku:STRING,unit:INT64"
```

Three shipped plugins, three unrelated stores, one query. Adding Kafka to that list changes nothing
structural — it is another binding under another stream name.

```sql
SELECT o.id, c.segment, o.qty * p.unit
FROM orders o
JOIN customers c ON c.id = o.customer_id
JOIN prices p    ON p.sku = o.sku
```

By the time rows reach a lane they are binary rows carrying a stream id; nothing downstream knows or
cares where they came from. A join gets **one inbox per input**, so a burst on one side cannot starve
the other.

> **Status, stated plainly: this is now demonstrated for two shipped connectors, and still
> construction-only beyond that.** `pravaha-it`'s `CrossConnectorJoinTest` binds one stream to
> `filesystem` and another to `feedfile` — two independently-discovered `StreamSourcePlugin`
> implementations — and registers a real `JOIN` across them: rows land in a file the `filesystem`
> plugin reads and in a directory the `feedfile` plugin reads, and the join keeps exactly the pairs
> that share a key, with an unmatched row on each side proving it is not a cross product or a
> pass-through. The mechanism holds for that pair. The three-way `feedfile` + `jdbc` + `aerospike`
> shape above, and any join touching `jdbc`, `delta` or `aerospike`, remain what the earlier wording
> called them — supported by construction and not yet demonstrated by a test.

**What differs across sources is time, not plumbing.** Each source declares its own out-of-orderness,
and the watermark is the minimum across inputs — so a join is only as current as its laggiest side.
That is correct and it surprises people: adding a slow source to a query makes the whole query
slower to close windows.

---

## 5. Z-sets, deletes, and why CDC is the natural fit

The engine is built on **DBSP**. Every row carries a **weight**: `+1` inserted, `−1` retracted. A
collection is the sum of its weights — a *Z-set*. An update is a retraction and an insertion
together.

This is what makes a continuous query cheap: if `COUNT(*)` is 100 and a `−1` arrives, the answer is
99, and nothing rescans. Every operator has an incremental form that consumes changes and produces
changes.

**Most connectors have to pretend the world is insert-only.** A filesystem source appends. The
Aerospike source scans a set and infers what changed. They set `weight(1)` on everything and
`emitsDeletes = false` — unless, for the two scan sources, `deletes: detect` is set, which infers
retractions by comparing each full pass with what was emitted ([below](#retractions-you-can-have-today-without-cdc)).

**A change-data-capture source does not have to pretend.** Its input *is* a changelog.

### Debezium

Debezium reads a database's own replication log — MySQL binlog, Postgres WAL, MongoDB oplog — and
emits every insert, update and delete as an event carrying `before` and `after` images.

The correspondence is exact:

| Debezium event | Pravaha |
|---|---|
| `c` (create) | one row, `weight(+1)` |
| `d` (delete) | one row from `before`, `weight(-1)` |
| `u` (update) | **two** rows: `before` at `-1`, `after` at `+1` |

That is a Z-set stream with no translation. **`postgres-cdc` is that correspondence, built** — natively
on PostgreSQL's logical replication rather than through Debezium ([ADR-041](adr/041-change-data-capture-without-debezium.md)),
decoding `pgoutput`'s `Insert`, `Update` and `Delete` into exactly the rows in this table. It is the
first source to set `emitsDeletes = true` and `emitsBeforeImage = true`, **two `SourceCapabilities`
fields that existed since the SPI was written and that no connector used until it.** Debezium stays
the explanation of what CDC is, and the route ADR-041 names for a second database.

It is also push rather than poll: the Aerospike source scans once a second, and CDC delivers the
change when it happens — lower latency, and no scan load on the table.

One Debezium connector reaches MySQL, Postgres, SQL Server, Oracle, MongoDB, Db2 and Cassandra;
`postgres-cdc` reaches PostgreSQL 14 and later, and nothing else.

#### Worked: one `UPDATE` becomes two rows

A customer moves from the silver tier to gold:

```sql
UPDATE customers SET tier = 'gold' WHERE id = 42;
```

Debezium emits one event carrying both images:

```json
{
  "op": "u",
  "before": { "id": 42, "tier": "silver", "region": "EU" },
  "after":  { "id": 42, "tier": "gold",   "region": "EU" },
  "source": { "ts_ms": 1789000000000, "lsn": 48219374 }
}
```

The connector emits **two** rows, and the weights are the whole translation:

```
(42, "silver", "EU")   weight -1      ← from before
(42, "gold",   "EU")   weight +1      ← from after
```

A continuous query counting customers per tier sees both, and both matter:

```sql
SELECT tier, COUNT(*) AS customers FROM customers GROUP BY tier
```

| | `silver` | `gold` |
|---|---|---|
| before the update | 900 | 100 |
| after the `-1` | 899 | 100 |
| after the `+1` | 899 | 101 |

**A source that dropped the `before` image would leave `silver` at 900 for ever.** Nothing would look
wrong — no error, no lag, no gap in a metric — the number would simply be too high by one, and by
one more with every future update. That is why `emitsBeforeImage` is a capability a connector
declares rather than a detail of its implementation, and why a connector that cannot produce a
before-image must say so (§6) instead of emitting the `after` row alone.

**Proved against a real PostgreSQL**, through the path a node runs: `PostgresCdcRegistrationTest`
binds a `postgres-cdc` source, loads 900 silver and 100 gold customers, and watches the registered
aggregate walk to 899/101 on the `UPDATE` above and to 899/100 on a `DELETE` of a gold customer —
then restarts from a checkpoint and replays from its LSN with nothing lost or counted twice. One
difference from the SQL above: the engine refuses an unwindowed keyed `GROUP BY` over a stream as
unbounded state (`PRV-2050`), so the test writes the two tiers as one global aggregate,
`SUM(CASE WHEN tier = 'silver' THEN ... END)`, which revises by exactly the same weights.

#### Polling is not the legacy option — choose deliberately

Nothing above deprecates the `jdbc` source. **Polling a `SELECT` is a first-class way to feed this
engine and is frequently the only one available**, because CDC's prerequisites are granted by a DBA
rather than by a config file: `wal_level = logical` needs a restart, a `REPLICATION` role is rarely
handed out, `REPLICA IDENTITY FULL` changes write costs on production tables, and a managed instance
or a read replica may not offer logical decoding at all.

| | Polling (`jdbc`) | Change data capture |
|---|---|---|
| Sees deletes | **no** | yes, with a full before-image |
| Sees every intermediate value | no — two writes between polls look like one | yes |
| Latency | the poll interval | as fast as the log is read |
| Load on the database | a query per interval, indexed | reads the log the database already writes |
| **State on the database server** | **none** | a replication slot that retains WAL |
| Can take the database down | no | yes — an abandoned slot fills the disk |
| Permissions needed | `SELECT` | replication role, server settings, a restart |
| Arbitrary joins and casts server-side | **yes, via `query`** | no — you get the table's own rows |

**Prefer polling when** the question is "what is the current state" rather than "what changed";
deletes do not reduce an answer; a poll interval of seconds is fast enough; or you simply cannot get
the permissions. It has no server-side state, which makes its failure mode strictly better: a
Pravaha node that dies inconveniences nobody else.

**Prefer CDC when** deletes or updates must *retract* — that is the case polling cannot express at
all, since `JdbcPartitionReader` writes `weight(1)` on every row — or when intermediate values
matter, or when the poll load on a large table has become the problem.

**They compose.** A stream is bound per name, so one continuous query can join a CDC-fed stream to a
polled one to a file, and §4 is that mechanism. Nothing requires a deployment to pick one style.

#### The fine print: what actually goes wrong with CDC

Every item here has cost somebody a production incident, and most of them fail **silently** — which
is the reason they are written down. They apply to Debezium and to a hand-written reader equally;
they are properties of the database, not of the connector.

**`REPLICA IDENTITY` decides whether corrections are possible at all.** Postgres defaults a table to
`REPLICA IDENTITY DEFAULT`, which puts only the **primary key** in the before-image of an update or
delete. Work the §5 example with that setting: the before-image is `(42)` and nothing else, so there
is no `(42, "silver")` to retract, and silver stays at 900 for ever. No error, no lag, no gap in any
metric — just a number that is wrong and stays wrong.

So a CDC source must **refuse a table that is not `ALTER TABLE ... REPLICA IDENTITY FULL`**, at
configuration time, and name the statement that fixes it. And the setting is not free: `FULL` writes
the entire old row into the WAL on every update and delete, so WAL volume grows and wide tables pay
for it on every write. That is a real cost an operator agrees to, not a box to tick.

**Unchanged TOAST values arrive as a placeholder, not as data.** This is the subtlest one. Postgres
stores large values out of line, and if a wide `text` or `jsonb` column is *not modified* by an
update, its new value is **not in the stream** — a marker says "unchanged" instead. A consumer that
takes the after-image at face value writes a placeholder or a null into a column that in truth still
holds the old value, and the view is quietly corrupted in exactly the column nobody is watching.

The fix is that the connector must carry the old value forward for any column marked unchanged, which
it can only do if it has the old row — which is `REPLICA IDENTITY FULL` again, from a second
direction.

**A row must reach the engine on a transaction boundary, never before.** The stream carries `Begin`
and `Commit`. Emitting rows as they arrive publishes uncommitted state, and a rolled-back transaction
then needs retracting rows that were never true. This engine already promises a subscriber whole
commits and never half a batch (`STRM-11`), so a CDC source buffers to `Commit` and hands over the
transaction entire.

Which creates the opposite problem: **one enormous transaction**. A ten-million-row `UPDATE` is one
transaction, and buffering it whole is a memory bound nothing here spills past — operator state can spill to the
mapped tier (ADR-044), but inboxes are fixed and off-heap and a transaction being assembled is
held on the heap until its `Commit`. Postgres 14 and later can stream an in-progress transaction, which
moves the problem rather than removing it: the connector then knows about changes that may still roll
back. Whichever is chosen, it is a decision to record, not a default to inherit.

**An idle table fills the database's disk.** The replication slot advances only when the client
confirms an LSN, and the client only sees messages for *captured* tables. So a quiet table on a busy
database means the slot's position stands still while WAL piles up behind it — and the first symptom
is the database running out of disk, not anything visible in Pravaha. The remedy is a heartbeat:
periodically confirm the current LSN even with nothing to report. Debezium calls this
`heartbeat.interval.ms`; a hand-written reader needs its own, and it is not optional on any database
that has tables Pravaha does not capture.

**A slot is not automatically safe across a failover.** A replication slot lives on one server, and
historically did not follow a promotion to a standby — after failover the slot is simply gone, and
recovery means a fresh snapshot of everything. Recent Postgres versions add support for
synchronising slots to standbys; **check what your version actually does before relying on it**,
because the recovery path differs enormously between "resume from the slot" and "re-snapshot the
table".

**A dropped slot cannot be resumed, only re-snapshotted.** `max_slot_wal_keep_size` caps how much WAL
a slot may retain, which protects the disk by *invalidating* the slot when the cap is passed. That is
the right trade, and it means a connector must detect an invalidated slot and say plainly that a new
snapshot is required rather than resuming from an LSN the server no longer has.

**`pgoutput` needs a publication, and adding a table later is its own event.** The stream carries
what a `PUBLICATION` names. `FOR ALL TABLES` is convenient and captures things nobody meant to
capture; a named list is explicit and means `ALTER PUBLICATION` when a table is added — at which
point that table has no snapshot, and the splice question in the next section applies again to it
alone.

**A table with no primary key and no replica identity is not replicated at all.** Postgres will
refuse the update or skip it depending on version and settings, so the rows simply never appear. A
connector should check at configuration rather than let a table be silently absent.

**MySQL has the same two settings under different names.** `binlog_format = ROW` and
`binlog_row_image = FULL` are the binlog equivalents of logical decoding and `REPLICA IDENTITY FULL`;
`MINIMAL` reproduces the key-only before-image problem exactly. And binlogs expire — a consumer down
longer than `binlog_expire_logs_seconds` cannot resume and needs a new snapshot, which is the same
shape as an invalidated slot.

**The offset is the LSN, and it belongs in the checkpoint.** Resuming means telling the server the
last LSN durably applied. Confirm too early and a crash loses changes the server will never resend;
confirm only at a Pravaha checkpoint and the two recover to the same point. That is what
`PartitionReader.checkpointed` exists for (§2).

#### How `postgres-cdc` answers the fine print

Each item above, and what the shipped source does about it. The class comments in
`plugins/pravaha-plugin-postgres-cdc` carry the argument; the tests named run against a real
PostgreSQL 16 with `wal_level=logical` (Testcontainers).

| The trap | What `postgres-cdc` does |
|---|---|
| `REPLICA IDENTITY` not `FULL` | **Refused at open**, `PRV-5112`, naming `ALTER TABLE <table> REPLICA IDENTITY FULL;`, before any slot or publication is created. A key-only before-image that arrives anyway (the identity changed while streaming) stops the source rather than retracting nothing. `aTableWithReplicaIdentityDefaultIsRefusedNamingTheAlterTable` |
| Unchanged TOAST placeholder | The `u` column of the new row is filled from the same column of the old row, which `FULL` carries whole. A placeholder is never written as a value; one with no old row to fill it from stops the source. `anUnchangedToastValueIsCarriedForward…`, against a 64 KB out-of-line value |
| Transaction boundaries | Nothing is handed over before `Commit` (protocol version 1, so PostgreSQL never streams an uncommitted transaction), and a poll takes a transaction only if all of it fits — so a checkpoint, taken between polls, never falls inside one |
| One enormous transaction | Buffered whole in heap until its `Commit`: the memory bound is the largest transaction. One larger than any poll the engine has offered is handed over in order across polls, and the offset records how far in (`lsn=X/Y;partial=A/B+N`), so a restore inside it delivers exactly the rest. A view can publish between those parts |
| An idle table fills the disk | `heartbeat.interval` (10s) writes a non-transactional `pg_logical_emit_message` into the WAL; it comes back through the slot behind everything committed before it and becomes a position the engine checkpoints and the slot confirms. `aHeartbeatMovesThePosition…`, and the contrast with it off |
| Failover, dropped or invalidated slot | Detected, not papered over: a missing slot, or `wal_status = 'lost'`, is refused with the rebuild steps. A restore from a position the slot has already confirmed past — which PostgreSQL would silently skip forward from — is refused, `PRV-5115` |
| Publication | Created `FOR TABLE <table>` before the slot (a slot created first would decode changes from before the publication existed); an existing one must publish insert, update and delete and include the table, or is refused naming the `ALTER PUBLICATION` |
| `TRUNCATE` | **Refused**, `PRV-5116`: the stream stops after delivering everything before it. A truncate carries no rows, so there is nothing to retract, and retracting "what the view holds" would need the table's contents at that LSN, which the log does not have. The remedy is to drop the slot and re-register; use `DELETE FROM` on a captured table to have its rows retracted |
| The offset is the LSN | The slot is confirmed only from `checkpointed`, at the newest durable checkpoint's LSN, never backwards. `theSlotIsConfirmedOnlyAtCheckpointedPositions…`, `restartingFromACheckpointedPosition…` |
| The snapshot, and its seam | `snapshot.mode: initial` reads the rows already there under an exported snapshot and splices them in at its consistent point; a checkpoint half-way through resumes exactly, from a new snapshot (below). `PostgresCdcSnapshotTest`, and the TCK run again in that mode |

**Declared capabilities**: `replayableOffsets`, `orderedWithinPartition`, `emitsDeletes` and
`emitsBeforeImage` all true; **`EXACTLY_ONCE`**; no pushdown. Exactly once because the position is a
commit LSN and replay from one is deterministic, the reader drops anything ending at or before it,
and the slot never releases what a durable checkpoint could ask for. It passes the source TCK
(`PostgresCdcSourceTckTest`), which holds an exactly-once source to no duplicates on resume as well as
no loss. What it does not survive is the slot itself going away — and that is refused, not skipped.

#### Not every store has a log you can subscribe to

CDC is not one mechanism. What a store offers differs enough to decide whether a connector is worth
writing at all, and the differences are licensing and architecture rather than effort.

| Store | Mechanism | Who initiates | Deletes | Catch |
|---|---|---|---|---|
| **Postgres** | logical replication of the WAL | client pulls | yes, with `REPLICA IDENTITY FULL` | needs `wal_level = logical` and a restart |
| **MySQL** | binlog; the client registers as a replica | client pulls | yes | needs `binlog_format = ROW` |
| **MongoDB** | oplog / change streams | client pulls | yes | needs a replica set, even of one |
| **SQL Server** | CDC change tables | client pulls (ordinary queries) | yes | Standard edition or better |
| **Aerospike** | **XDR** | **the store pushes** | durable deletes only | **Enterprise only — Community has no change feed** |
| **Cassandra** | commitlog segments in `cdc_raw/` | **read from local disk, per node** | tombstones | the exception that proves the rule — see below |

**Aerospike is the interesting one, and this repository already decided it.** `AerospikeStrategy`
names four ways to get changes out and implements one:

- `lut-scan` — a partition-parallel scan filtered on each record's last-update time. **The only
  strategy that works on Community Edition**, and honest about its cost: at-least-once, no
  before-image, and **deletes are invisible**. A deleted record is simply absent from the next scan,
  which is indistinguishable from one that never existed. It also misses intra-interval overwrites:
  two writes between scans are seen as one. Those are properties of *scanning*, not of the
  implementation, and no amount of care removes them. It also **repeats rows**: an updated record
  comes back as the new row with nothing retracting the old, and a record written while a scan ran
  is read again by the next one — so the source declares `repeatsRows`, and an aggregate, a join or
  an append-only sink over it is refused at registration with `PRV-2042` (SCAN-1); a keyed view of
  the records is admitted. `deletes: detect` buys the deletes, the before-image and an exact
  changelog back at the price of a full scan each pass and the emitted rows kept in memory
  ([`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1); the collapsed overwrites stay collapsed.
  A pass is read a page at a time, each page no larger than what the engine asked one poll for, and
  the client resumes the pass where the page stopped — so the first pass of a new query, which
  matches every record in the set, never holds more than a page on the heap (SRC-7). The offset
  moves once the whole pass has been read and handed on.
- `xdr-kafka`, `xdr-http` — Enterprise, not built.
- `write-intercept` — every writer goes through a Pravaha wrapper. Intrusive, not built.

Asking for an unimplemented one is refused at configuration rather than silently downgraded to
`lut-scan`, because the strategies have *different delivery guarantees* and a query whose source
cannot see deletes should be told at registration rather than discover it from a total that never
goes down.

**And notice the shape of `xdr-http`.** XDR *pushes* — Aerospike connects out to a destination
rather than being read by a client. That is not the Postgres shape at all; it is
[ADR-040](adr/040-the-remote-connector.md)'s shape with Aerospike as the sender. Whoever builds the
remote connector's inbound endpoint gets most of an Aerospike XDR source with it, which is an
argument for building that endpoint before writing a second store-specific reader.

**Cassandra is the one case where the log really is a file.** Its CDC writes commitlog segments to a
`cdc_raw` directory **on every node**, to be read locally — so it needs an agent per node and gives
no ordering across them. That is why a Cassandra *table scan* source ([ADR-039](adr/039-ga-includes-the-known-gaps-and-clustering.md)
item 6) is tractable and Cassandra *CDC* is a different, unbuilt project. It is also the exception
behind the rule above: for the stores worth capturing first, change capture is a network conversation.

**The table scan is built.** `plugins/pravaha-plugin-cassandra` pages a table by `token()` range
rather than `ALLOW FILTERING`, on the same honest terms as the Aerospike `lut-scan`: at-least-once,
no before-image, deletes invisible. It is not even incremental the way `lut-scan` is — CQL's
`writetime()` cannot be filtered server-side without `ALLOW FILTERING`, and is tracked per column
rather than per row, so `CassandraStrategy` refuses `writetime-incremental` for the same reason it
refuses `commitlog-cdc`: a full scan that says what it is beats an incremental one that quietly
misses rows. With the default `deletes: ignore` every pass emits every row again, so the source
declares `repeatsRows` and an aggregate, a join or an append-only sink over it is refused with
`PRV-2042` (SCAN-1) — a `COUNT` over it would grow by the table's size every interval. With `deletes:
detect` the same passes become an exact changelog: each is merged, in token order, with the rows
already emitted, and only the difference is emitted. `ignore` stays the default because `detect`
needs a durable state directory and memory for every emitted row; for Cassandra it costs no extra
reads. See `docs/CONTINUOUS_QUERIES.md` §2.1 for the configuration.

#### Where does it run? The database is on another machine

**Nowhere near the database.** This is the first question anyone asks and the answer is better than
expected: a CDC connector does **not** read log files, does **not** need a mounted volume, and does
**not** put an agent on the database host.

Logical replication is a **network protocol**. The client opens an ordinary connection to the
database — same host, same port, same TLS as any other client — and asks for a replication stream.
*The database server* reads its own write-ahead log, decodes it through an output plugin, and streams
the changes back over that socket. Pravaha is a client, exactly as the `jdbc` source is a client.

| | Where the data lives | How Pravaha reaches it |
|---|---|---|
| `jdbc` source | a database on another host | polls with `SELECT`, over the network |
| **CDC source** | that same database's write-ahead log | **streams over the same network port; the server does the decoding** |
| Remote connector ([ADR-040](adr/040-the-remote-connector.md)) | inside somebody else's application, never in a database | that application pushes rows to Pravaha |

The same holds for the others, which is worth knowing before choosing one: **MySQL** streams binlog
events to a client that registers as a *replica*; **MongoDB**'s oplog is a collection read through
the normal driver; **SQL Server** exposes CDC as ordinary tables. None of them needs disk access to
the database host. A connector that asked you to mount a log directory would be doing it wrong.

**What the database side does need**, and it is a real prerequisite rather than a formality:

- `wal_level = logical` in `postgresql.conf` — **a server restart**, so it is a change somebody has to
  schedule rather than apply during an incident.
- A role carrying the `REPLICATION` attribute, and `max_replication_slots` with room for one more.
- `REPLICA IDENTITY FULL` on each captured table — see below, because without it corrections are
  silently impossible.

**And one risk that distance makes worse.** The replication slot lives *on the database server* and
retains WAL until a client consumes it. A Pravaha node that dies, or a network partition that lasts,
means the slot stops advancing and the database's disk fills — a Pravaha outage becoming a Postgres
outage, which is a much worse failure than the one that started it. Anyone running this monitors slot
lag on the database, not only on Pravaha.

#### What a CDC binding looks like

**Built, for PostgreSQL:**

```yaml
pravaha:
  sources:
    customers:
      plugin: postgres-cdc
      options:
        url: "jdbc:postgresql://db-1:5432/crm?sslmode=verify-full&sslrootcert=/etc/pravaha/db-ca.pem"
        user: pravaha_cdc                # REPLICATION, and owner of the table to create the publication
        password: "${PRAVAHA_CDC_PASSWORD}"
        table: public.customers          # must be REPLICA IDENTITY FULL
        slot: pravaha_customers
        publication: pravaha_customers
        snapshot.mode: initial           # rows already there first; never (the default) is changes only
```

Every option, with its default, is in [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1; the
slot's care and feeding is in [`OPERATIONS.md`](OPERATIONS.md). For comparison, what a Debezium
binding would have looked like — **not built**, and by ADR-041 not the first thing to build; it is
the route for a *second* database:

```yaml
pravaha:
  sources:
    customers:
      plugin: debezium              # does not exist yet
      options:
        connector: postgres
        hostname: db-1
        port: "5432"
        database: crm
        table: public.customers
        slot.name: pravaha_customers
        snapshot: initial           # initial | never — the splice in the next section
        event.time: updated_at
```

`slot` is the part that is not cosmetic: a Postgres replication slot is server-side state that
retains WAL until it is consumed, so a connector that creates one and stops being read will fill the
database's disk. A CDC connector's operational story is mostly about that slot, not about Pravaha —
which is why `postgres-cdc` reports the WAL its slot retains as its health, and goes `DEGRADED` past
`slot.lag.warn.bytes`.


### Snapshot, and splicing it to the stream

A changelog starts from *now*. A query over "all orders" needs the rows that already existed, and
those are not in the log.

So a CDC connector does two things in sequence:

1. **Snapshot** — read the table as it is, emit every row at `+1`.
2. **Stream** — switch to the log, from the position the snapshot was consistent with.

The hard part is the seam. Read the snapshot, then start the log a moment later, and changes in
between are lost; start the log first and the same row arrives twice.

**This is already built.** `pravaha-backfill` has `SplicedReader` — phase-explicit offsets, a bounded
off-heap change buffer that fails loudly rather than silently growing, and dedup keyed on *changed*
keys rather than on every key in the snapshot, which is what makes it survivable on a table nobody
could hold in memory. `BackfillThrottle` governs the history scan only, because throttling the change
feed would make the query fall behind the present in order to protect the store from the past.

**`postgres-cdc` snapshots with `snapshot.mode: initial`**, and does not use `SplicedReader` to do
it: PostgreSQL offers an exact seam of its own, and the part that seam leaves open — a checkpoint
cut half-way through the read — has an exact answer too. `InitialSnapshot` in the plugin carries the
argument; in short:

- **The seam is PostgreSQL's.** A temporary logical slot is created with `EXPORT_SNAPSHOT`, and the
  table is read in a `REPEATABLE READ` transaction that imports it (`SET TRANSACTION SNAPSHOT`).
  That snapshot sees exactly the transactions committed before the slot's consistent point `C`, and
  none after. The reader streams the registration's own slot up to `C`, dropping every change to the
  table (the snapshot has them all), delivers the table in primary-key order at `+1`, and streams on
  from `C`. No watermarks, no de-duplication window, no reasoning about which commits a snapshot
  happened to see.
- **The checkpoint is `(L, K)`.** While the snapshot is unfinished, the offset — `lsn=L;snapshot=N@K`
  — means one thing: the engine holds the table's rows whose key is at or below `K`, as of log
  position `L`, and nothing else. That statement does not mention the snapshot it came from, which
  is why it survives that snapshot's connection.
- **A restart re-establishes it.** It takes a *new* exported snapshot at a new point `C'`, streams
  from `L` to `C'` delivering a change only when its row's key is at or below `K` — each image of an
  update judged by its own key, so a row whose key moves across `K` is retracted on one side and
  inserted on the other — and then reads the rows keyed above `K` at `C'`. A change above `K` is
  dropped because the snapshot at `C'` reads the row as that change left it. Every comparison with
  `K` is made by PostgreSQL, in the key's own type and collation, the same comparison the chunk
  query's `ORDER BY` makes: a Java comparison of a `text` key would disagree with an ICU or libc
  collation about exactly the rows at the boundary.
- **Memory is bounded.** Chunks of `snapshot.chunk.rows` (10,000) by keyset — `WHERE key > last
  ORDER BY key LIMIT n` on the primary key's index — read on a thread of their own, never more than
  two ahead of the engine. While the snapshot is read the stream waits, holding at most
  `buffer.rows`, and the slot retains the WAL from `C` on.

What it needs: a **primary key** (the order a resume continues in; without one, `snapshot.mode:
initial` is refused, `PRV-5112`), `SELECT` on the table, room for one more slot in
`max_replication_slots` while the snapshot starts, and no transaction left open from before it —
PostgreSQL creates the temporary slot only once every transaction running at that moment has ended,
and a snapshot that cannot start within `start.timeout` is refused, `PRV-5118`. The snapshot's
`REPEATABLE READ` transaction is open for as long as the table takes to read, and holds back vacuum
for that long. Snapshot rows carry the snapshot's own time as their event time, or the `event.time`
column when one is set.

`never` stays the default: `initial` reads whole tables and needs a primary key, and an existing
binding should not start doing either without asking. An offset written before snapshots existed has
no `;snapshot=` part and resumes as the stream it always was; one taken mid-snapshot finishes the
snapshot whatever `snapshot.mode` now says, because the engine holds part of the table and only the
rest of it makes that whole. There is no `initial_only`: a source here has no way to say it has
finished, and a table read once is the `jdbc` source's job.

### Retractions you can have today, without CDC

A connector is not the only way into the retraction model. The **filesystem** source reads an
optional `op.column` naming a column whose value says whether a row inserts or retracts — so any
producer that can write a changelog as a file can feed Z-sets now, with no database and no log
reader.

```csv
user_id,tier,op
ann,silver,I
bob,gold,I
cat,silver,I
bob,gold,D
```

```yaml
pravaha:
  streams:
    customers:
      schema: "user_id:STRING,tier:STRING,op:STRING"
  sources:
    customers:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/incoming/customers.csv
        schema: "user_id:STRING,tier:STRING,op:STRING"
        skip.header: "true"
        op.column: op
        op.delete.values: "D,DELETE,-,-1"
```

```sql
SELECT user_id, tier FROM customers
```

Three inserted, one retracted, so two survive — `ann` and `cat`. That is the engine's whole premise
proved through a configured deployment rather than a test harness, and it is
`IncrementalTest.incr003_aConfiguredSourceCanDeliverARetraction` run against exactly this shape.

**The operation column is an ordinary column, and it must be declared.** It appears in the schema
like any other field, it is matched by *name* so its position does not matter, and the file must
carry a value for it on every line — a row with one fewer field than the schema is a decode failure,
not a defaulted insert. What `op.column` adds is a second meaning on top of the value: it also
decides the row's weight. It stays selectable afterwards, so `SELECT *` will show it; the query above
simply does not ask for it.

`op.delete.values` is the set of values meaning *retract* — `D,DELETE,-,-1` by default. Anything
else, `I` included, is an insertion. There is no value meaning "update": an update is a retraction
and an insertion, which is the same two rows Debezium's `u` event produces above.

**Every other shipped source plugin but `postgres-cdc`, `kafka` (in `format: changelog`), and
`aerospike` and `cassandra` with `deletes: detect` hard-codes `weight(+1)`.** Until `postgres-cdc`
arrived, this was the one route a retraction had into a configured deployment, which is why the Z-set
model went so long without one — and why a CDC connector matters beyond the convenience of not
writing the file yourself.

**Retractions from a scan, by comparison.** A store with no change feed can still yield a changelog
if the source remembers what it has emitted. `aerospike` and `cassandra` with `deletes: detect` keep
every row they have emitted (compactly, bounded by `deletes.max.keys`, persisted beside the
checkpoint) and compare each complete pass with it: a row not held is `+1`, a changed row is the held
row at `−1` then the new one at `+1`, a row missing from the pass is the held row at `−1`. Two things
make this honest rather than a guess. The comparison is against the rows *emitted*, not against the
previous pass as read, so the output always sums to exactly what the store held at the last complete
pass; and a pass that fails part way retracts nothing it did not reach. What it cannot recover is
what scanning never had: two writes between passes are still one, and a delete is seen a scan
interval late. The costs — a full scan each pass for Aerospike, about 150 bytes of heap per row plus
the row — are in [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1.

## 6. What a connector should refuse to claim

Honesty here is not politeness — the engine changes its behaviour based on these.

- **`EXACTLY_ONCE`** — only if a reader resumed from a checkpointed offset delivers exactly the
  remainder, no more and no less. If you cannot replay, you are at-least-once.
- **`orderedWithinPartition`** — only if two rows for one key always arrive in their true order.
- **`emitsDeletes`** — only if a removal in the store produces a `−1`. Inferring deletion by absence
  in a scan is not this *unless* the absence is judged against a complete pass and the `−1` is the
  whole row the source itself emitted — which is what `deletes: detect` does, and why it may declare
  it. A scan that merely stops returning a row has retracted nothing.
- **Pushdown** — `FILTER` means you *applied* the filter, not that you accepted it. The engine
  re-applies filters it keeps, but a filter you claim and drop silently returns too many rows.
  A `ReadRequest` may also carry `alternatives` — the OR a reader shared by several queries asks for.
  Dropping a filter from inside one alternative widens it (safe); dropping a whole alternative
  narrows the OR (never). An alternative you can express nothing of makes the OR true.
  `PROJECT` means every column in `ReadRequest.columns()` arrives; any other column may be written
  with `RowWriter.setUnread` — the engine never reads it.
  `PARTIAL_AGGREGATE` is stricter still: a source that claims it returns pre-combined `COUNT`/`SUM`
  values instead of rows, so there are no rows left for the engine's filter to run against — the
  partial must already honour every filter, or the answer is wrong with nothing downstream placed to
  notice. The planner asks for one only when every predicate below the aggregate is pushable and the
  source declares `FILTER` too; a reader that still cannot express one declines — answers `false`
  from `PartitionReader.deliversPartialAggregate()` and returns rows — and one that honours it writes
  each partial in the aggregate's output layout (group keys, then one `BIGINT` per call) and never
  writes a group of zero rows. What "the same data" means for a partial is the source's own
  delivery: a partial must equal the sum of exactly the rows the source would otherwise have sent,
  retractions included.

What the shipped plugins claim, and why not more (ADR-039 item 6):

| Plugin | `FILTER` | `PROJECT` | `PARTIAL_AGGREGATE` |
|---|---|---|---|
| `jdbc` | yes — bound `WHERE`, `alternatives` as an `OR` | yes — the `SELECT` list, plus the watermark and key columns | with `key.column` only: one `GROUP BY` per keyset page, `COUNT` and `SUM` over `BIGINT`; declined for a filter SQL cannot carry, and for text comparisons or text group keys unless `collation.binary: true`, since a case-insensitive collation groups `'DONE'` with `'done'` |
| `aerospike` | yes — server-side expressions, `alternatives` as `Exp.or` (with `deletes: detect` too, where a record leaving the filter is retracted) | yes — the scan's bin names | no — server-side aggregation needs Lua stream UDFs registered on the cluster, and a last-update-time scan has no retraction for an overwritten record |
| `cassandra` | no — anything but the partition key needs `ALLOW FILTERING` | yes — the CQL `SELECT` list | no — every pass re-reads the whole range, so no partial could cover "new rows only" |
| `filesystem`, `feedfile`, `delta` | no | no | no |
| `kafka` | no — a broker has no server-side filter; every record is fetched whole | no | no |

The source TCK verifies two of these: that resuming from a recorded offset loses nothing, and that a
source claiming exactly-once resumes without duplicates too (`SourcePluginTck.replayableOffsetsActuallyReplay`,
`capabilitiesAreInternallyConsistent`). Ordering, deletes and pushdown are trusted, not tested by
the kit; the shipped plugins' pushdown is equivalence-tested against real stores instead
(`SourcePushdownEquivalenceTest` and `PartialAggregatePushdownEquivalenceTest` against H2, the
plugins' container ITs against Postgres, Aerospike and Cassandra).

---

## 7. Connectors worth building, and what each one proves

| Connector | Kind | Proves |
|---|---|---|
| **Kafka** | streaming | replayable offsets and real exactly-once resumption — **built, both ways**, `plugins/pravaha-plugin-kafka`: the source `kafka` (one reader per partition, its offsets in the checkpoint; below) and the sink `kafka-sink` (its transactional mapping is below) |
| **Debezium CDC** | changelog | deletes, before-images, Z-sets end to end — the engine's own model. **Proved for PostgreSQL by `postgres-cdc`**, built natively ([ADR-041](adr/041-change-data-capture-without-debezium.md)); Debezium is the route for a second database |
| **Cassandra** | table scan | the scan path generalises beyond Aerospike — **built**, ADR-039 item 6: a full `token()`-range scan with projection pushdown, `plugins/pravaha-plugin-cassandra` |
| **ScyllaDB** | table scan | speaks the same CQL wire protocol as Cassandra; not built or tested against — the `cassandra` plugin has not been run against it |
| **MySQL / Postgres** | table or CDC | direct; CDC is the better form. Postgres CDC is built (`postgres-cdc`); MySQL's binlog is not |
| **RabbitMQ / ActiveMQ / SQS / NATS** | queue | **at-least-once only** — acknowledgement is not an offset, so there is nothing to rewind to |
| **Pulsar / Kinesis / Redpanda** | streaming | as Kafka |
| **Iceberg / Hudi** | table format | as Delta, which already exists **both ways** — the source `delta` and the sink `delta-sink`, `plugins/pravaha-plugin-delta` |

The queue connectors are architecturally different and it is worth saying so before one is written: a
queue gives you *acknowledgement*, not a position you can return to. They cannot be `EXACTLY_ONCE`,
and `SharedSourceGroup` will decline to share their readers for the same reason it declines JDBC's.


### A replayable source: Kafka

`kafka` is the source the SPI's `SourceOffset` was written for. A topic's partitions are its
partitions — one `SourcePartition` per Kafka partition, in partition order, and one reader on each,
*assigned* its partition rather than subscribed, so no consumer group moves partitions between
readers behind the engine's back. A reader's position is the next Kafka offset to read, written
`topic/partition@next` (`orders/3@42`), and **it lives in the engine's checkpoint and nowhere else**:
a restore seeks each partition to the offset the checkpoint recorded, the log replays
deterministically from there, and the engine receives exactly the records the checkpoint does not
hold. That is the whole of the exactly-once argument ([ADR-008](adr/008-aligned-checkpoints.md)), so
the source declares `EXACTLY_ONCE` — and is therefore never shared between queries.

What it deliberately does not do:

- **A consumer group's committed offset is never read.** A group offset is committed when a consumer
  says so, not when a checkpoint is durable, so resuming from it would lose or repeat whatever lay
  between the two. `monitoring.group`, when set, is *committed* the offset each durable checkpoint
  recorded (from `PartitionReader.checkpointed`), so `kafka-consumer-groups` and lag dashboards see
  where a restore would resume. It is a report, never a position.
- **A position the log no longer has is not skipped past.** If retention deletes records a checkpoint
  has not yet read, or a checkpoint's offset is past the partition's end (the topic was deleted and
  recreated), the reader refuses with `PRV-5106` rather than reading on from wherever the log now
  starts. `auto.offset.reset` is `none` and cannot be passed through.
- **Aborted transactions are never delivered.** `isolation.level` defaults to `read_committed`, so a
  topic written transactionally — `kafka-sink`'s, or any exactly-once producer's — reads back exactly
  once too. The position steps over commit markers and aborted records only once everything before
  them has been handed over, so a checkpoint never records a position past a row the engine has not
  seen. `read_uncommitted` is an option, and reads what was aborted.

`poll` never touches the network: a fetch thread per reader owns the (not thread-safe) consumer and
decodes into a bounded queue, and while the engine has the reader paused, the partition is paused at
the consumer. Two value formats, stated precisely:

| `format` | A record's value | Weight | Deletes |
|---|---|---|---|
| `json` (default) | a JSON object of the row's columns, matched by name — what `kafka-sink` upsert mode writes | `+1` | none: `emitsDeletes` false. A **tombstone** (null value) says a key was deleted without saying what row it held, so there is nothing to retract: it is a dead letter (or `PRV-5105`), unless `tombstone: skip` reads the topic as insertions only |
| `changelog` | `kafka-sink`'s changelog envelope, `{"op":"insert"\|"delete","weight":n,"row":{...}}` | the envelope's | yes: a retraction written by one query's sink is a retraction in the next query's source. `emitsDeletes` and `emitsBeforeImage` true |
| `avro` | Avro's binary encoding of one record, against the writer schema in `schema.file` or the one the record's schema id names in the registry | `+1` | as `json` |
| `protobuf` | one message of the `FileDescriptorSet` in `schema.descriptor`, read with `DynamicMessage` | `+1` | as `json` |

Upsert-mode tombstones are not read as retractions because doing so would need the last value of
every key — state the source would have to hold, and a checkpoint to hold it in, to replay exactly.
Read `kafka-sink`'s changelog mode instead when a downstream query must see retractions. Values are
decoded the way `kafka-sink` encodes them, and anything that does not fit the declared schema — not
JSON, a fraction in an integer column, a decimal with more places than its scale — is a dead letter
with a reason, never a guessed value.

#### Avro, Protobuf and a schema registry, without a new dependency

The three were added with **no library added to the build for any of them**, which is a decision
about what a deployment has to trust, not a saving in bytes:

- **Avro** is read by a binary reader of this repository's own (`AvroBinary`, `AvroSchema`,
  `AvroRowReader`), the way `postgres-cdc` reads `pgoutput` by hand. Avro's binary encoding is small
  and frozen — zig-zag varints, little-endian floats, length-prefixed bytes, blocks, a union's branch
  index, a record's fields in order and nothing else — and the writer schema is Avro JSON, parsed
  with the Jackson streaming parser already on the classpath. Logical types are read where they
  change what the bytes mean: `date`, `time-millis`, `time-micros`, `timestamp-millis`,
  `timestamp-micros`, and `decimal` over `bytes` or `fixed`. `local-timestamp-*` is refused rather
  than read as UTC, and a plain `int` is not read as a `DATE` nor a plain `long` as a `TIMESTAMP`:
  the schema either says what a number means or it does not.
- **Protobuf** is read with `DynamicMessage` over a `FileDescriptorSet` the deployment supplies
  (`protoc --include_imports --descriptor_set_out=x.desc`), so no generated classes and no `protoc`
  at run time. `protobuf-java` was already in the build through gRPC and Avatica; B7 declared it and
  pinned it in the root pom's `dependencyManagement` so its version is somebody's decision.
- **The schema registry** is spoken over its REST API with the JDK's own `HttpClient`: the Confluent
  wire format (one `0x00` byte, a four-byte big-endian schema id, then the payload) and one request,
  `GET <url>/schemas/ids/{id}`, cached by id forever because an id's meaning never changes. No
  Confluent client library — it is under the Confluent Community License and is not on Maven Central
  — and nothing here is Confluent-specific, so **Karapace** and **Apicurio**'s `ccompat` endpoint
  work the same (point `schema.registry.url` at them, path prefix and all).

**Mapped by name, refused by name.** Every column of the declared schema must be a top-level Avro
field or a protobuf field of that name (exactly, then ignoring case), of a type that can become it; a
field no column names is skipped whole. With `schema.file` or `schema.descriptor` the mapping is made
and refused when the query registers — `PRV-5108`, naming the column or the field — so a mismatch is a
registration that fails rather than a stream of dead letters. A schema that arrives *with* the record
(the registry's) cannot be refused that early: that record is a dead letter naming the schema id and
the mismatch, and the mismatch is remembered per id. A registry that cannot be read is `PRV-5109` and
stops the reader, because a registry being down is not a record's fault.

**proto3 defaults are not NULL, and this is not hidden.** A proto3 scalar without `optional` has no
presence on the wire: never set and set to `0`/`""`/`false` are the same bytes, none. Such a field
fills its column with the type's default and never with NULL, even when the column is nullable. A
field that does carry presence — `optional` in proto3, any message field, proto2's `optional` — reads
as NULL when it is absent. Declare the field `optional` if a column must be able to be unknown.

A value that begins with `0x00` while no registry is configured is the mistake this format makes
most often, so a refusal that follows one says so and names `schema.registry.url`.

Proved against a real broker (Testcontainers): the source TCK; an aborted transaction skipped, and
resumption from an offset between it and its markers exact; three partitions restarted from
checkpointed positions with every record once; and through the registry's own checkpoints
(`KafkaSourceRegistrationTest`), a restart that counts every record once, and a `kafka-sink`
changelog topic read back by a `kafka` source with its retraction applied; and an Avro topic, an Avro
topic whose values carry a registry's five-byte prefix, and a Protobuf topic each read end to end
(`KafkaSourceFormatBrokerTest`). Away from the broker: the Avro reader against hand-written
specification vectors and a seeded property test (500 random schemas, 2 000 records), the Protobuf
reader against messages written with `DynamicMessage`, and the registry against an HTTP server in the
test — caching, basic auth, a bearer token, a 401, a 404, a 500, a body that is not the documented
shape, and a registry that is down. The binding's options are in
[`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1.

### A transactional sink on a store with no prepare: Kafka

The sink SPI's transactional protocol is two-phase: `prepare(id)` makes a transaction durable and
invisible and returns a handle the checkpoint records, and `commit(handle)` — possibly from another
process after a restart — makes it visible. Kafka's transactional producer has no such state. A
transaction is open or committed; a restarted producer with the same `transactional.id` *aborts*
whatever its predecessor left open (that is how it fences it); and one producer has one open
transaction, where the protocol begins the next the moment it prepares one.

So the obvious mapping — write into an open Kafka transaction, `commitTransaction` at commit — loses
data: a process that dies after its checkpoint is durable and before the commit leaves a handle the
restore will commit, naming a transaction the restart has already aborted, holding changes from
before the checkpoint's cut that the replay does not write again (`SinkDelivery.recover`,
`TransactionalSinkDeliveryTest`). KIP-939 adds a real prepare to Kafka; it needs brokers and
clients this plugin cannot assume.

`kafka-sink` does what `jdbc-sink` does with a staging table, with a **staging topic**: writes go
there (one partition, delete policy), `prepare` names the checkpoint's changes by their staging
offsets, and `commit` reads them back and writes them to the target in one Kafka transaction
together with an offset for a consumer group that serves as the commit's receipt — which is what
makes a repeated commit a no-op. The guarantee is exactly once **to a `read_committed` consumer**;
every change is written twice. `KafkaSinkPlugin`'s class comment has the argument in full, and
`KafkaSinkBrokerTest` the crashes — between prepare and commit, and inside a commit — proved against
a broker.

### A transactional sink on a format with no delete: Delta

`delta-sink` maintains a continuous query's answer in a Delta Lake table, on Delta Kernel and not
Spark, and it runs into two properties of the format that are worth stating because they shape what
it can promise.

**Delta has no prepare.** A Delta commit is visible the instant its log entry lands; there is no
durable-but-invisible state a new process could pick up, which is what the SPI's `prepare` means. So
this sink does what `jdbc-sink` and `kafka-sink` do — it stages. Writes go to
`<path>/_pravaha_sink/<transaction.id>/<label>/` as files (a directory Delta's own `VACUUM` skips,
by the rule that keeps `_delta_log` safe), `prepare` names the label, and `commit` applies the whole
label as **one** Delta commit. A reader therefore sees all of a checkpoint's changes or none of
them. Every commit carries a Delta `txn` action naming `transaction.id` and the label, and *that* is
what makes a repeated commit a no-op — the table's own record, not a note the process kept, so it
survives the process that wrote it.

**Delta has no delete.** Without deletion vectors, removing a row means rewriting the file that
holds it. So upsert mode is a copy-on-write merge: the changes are collapsed by key, the table's data
files are read *through their key columns alone* to find the ones holding an affected key, and each
of those is rewritten without those rows while the commit removes the old file. Files holding no
affected key are not read past their key column and not rewritten. **The cost of a commit is
therefore proportional to the table, not to the number of changes** — which is the price of exact
upsert semantics on a format whose files are immutable, and is why `mode: changelog`, which only
appends, is what a high-volume query should be bound to.

**Partitioned tables.** `partition.columns` names the table's partition columns, in order. Delta
keeps a partition column's value out of the data file — the file sits in a directory named for its
partition and its `add` action records the value in `partitionValues` — so one file holds one
partition's rows. The sink groups a commit's rows by their partition values and writes one file per
partition under that partition's directory, through Kernel, which strips the columns from the
Parquet and records the values in the log; a rewritten file goes back into the partition it came
from, and a file left with no surviving row is removed and not replaced by an empty one. None of it
changes the guarantees: however many partitions a checkpoint touches, it is one Delta commit
carrying one `txn` action, refused whole with `PRV-5059` on a conflict, in both modes. Refused at
registration with `PRV-5056`: a partition column that is not one of the query's output columns
(changelog mode's `_op` and `_weight` included), a `BYTES` column (Kernel writes a binary partition
value as its bytes read as UTF-8, which does not round-trip), a column named twice, and partitioning
by every column. An existing table partitioned otherwise than the binding says — including an
unpartitioned binding over a partitioned table — is refused with `PRV-5057` when the sink opens.
`DeltaSinkPartitionTest` holds this against real tables.

**Deletion vectors: read by the source, refused by the sink.** On a table with deletion vectors a
`DELETE` rewrites no file; it replaces the file's log entry with one naming a vector that marks the
deleted rows. The `delta` source identifies a file by its path *and* its vector, so that replaced
entry is a removal of the file as the old vector left it and an addition of the file as the new one
leaves it. Kernel applies each vector as it reads and the source emits only the rows it leaves live,
so a row newly deleted reaches the view as one retraction — with every surviving row of the file
emitted once each way and annihilating, the same over-emission a file rewrite costs. A resume
inside such a file counts rows of the file, deleted ones included, so it repeats and misses
nothing. `delta-sink` does not write deletion vectors, and its upsert mode refuses (`PRV-5055`) to
rewrite a table whose files carry them: it would have to carry each vector through the rewrite, and
the rows the vector deletes would otherwise come back. `DeltaDeletionVectorTest` holds both, against
tables whose vectors are written in the protocol's own format and loaded, size and checksum
checked, by Kernel's reader.

**Concurrent writers, exactly.** A writer that finishes before this sink's commit begins is simply
the snapshot the commit merges onto, and where it wrote a key the sink also holds, the sink's value
wins — that key is the query's answer. A writer that commits *inside* the commit's window, after the
snapshot it read and before its log entry, makes the commit fail with `PRV-5059`. The transaction is
built with `withMaxRetries(0)` on purpose: Kernel's own retry is safe for a blind append and not for
a merge whose removals name files the other writer has just rewritten. The sink is detached
(`PRV-8009`) with the checkpoint's changes still staged, and it never retries. Both cases say the
same thing about deployment: a Delta table maintained by a continuous query should have no other
writer.

**What it costs in files.** One commit per checkpoint, each writing at least one Parquet file plus
one for every file it had to rewrite. The table accumulates small files and needs compaction —
which is Delta's `OPTIMIZE`, run by an engine that has one (Spark, `delta-rs`), not this sink's:
Kernel has no compaction API and the plugin does not pretend to one. Nor does it `VACUUM` the files
its rewrites leave behind. `DeltaSinkPluginTest` holds the behaviour against real tables on the
local filesystem, and `DeltaSinkRegistrationTest` holds a registered query's table equal to its view
across a crash.

### The remote connector — the source that inverts this table

Every row above is a connector Pravaha writes in order to reach a system. The **remote connector** is
the other direction: a small library an application embeds, which streams rows to a node over Arrow
Flight `DoPut`. The application becomes a source, and the set of systems Pravaha can ingest from
stops being the set somebody wrote a plugin for.

It is designed and not built. The design — why Flight rather than a bespoke socket, why at-least-once
delivery **requires** server-side deduplication in an engine where a duplicate row is a real `+1`,
why an idle agent must not freeze a watermark, and what an inbound write path has to enforce before
it is opened — is [ADR-040](adr/040-the-remote-connector.md). Scheduled after cluster mode, before GA.

---

## 8. What is missing from this framework today

Stated so nobody discovers it mid-build:

| | |
|---|---|
| A sink TCK and a lookup TCK | Only sources have one. The transactional sink protocol is tested per sink — `TransactionalSinkDeliveryTest` against a model, `JdbcSinkPluginTest` and `JdbcSinkRegistrationTest` against H2, `KafkaSinkBrokerTest` and `KafkaSinkRegistrationTest` against a real broker, `DeltaSinkPluginTest` and `DeltaSinkRegistrationTest` against Delta tables on the local filesystem — not by a kit a new sink can run |
| Capability verification in the TCK | Replay and exactly-once are tested; ordering, deletes and pushdown claims are believed, not tested |
| An SPI stability statement | `Version` exists; nothing says what change breaks a plugin |
| Plugin isolation | A connector shares the engine's classpath; a dependency clash is yours to resolve |
| A way to add one to a shipped node | The server jar carries `filesystem` and nothing else, and there is no directory a jar can be dropped into and no documented launcher that would read one. Adding a connector to a deployment today means building a jar that depends on both, so every name in this document except one is reachable from source and not from a release (`I-7`, reconfirmed from the configuration surface by CFG-4) |

---

## 9. Where to go next

| You want | Read |
|---|---|
| How a lane consumes what you produce | [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) |
| Where connectors sit in the whole | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Configuring a source on a node | [`OPERATIONS.md`](OPERATIONS.md) |
| Z-sets and incremental computation | [`CONCEPTS.md`](CONCEPTS.md) |
| The snapshot/CDC splice as built | `pravaha-backfill`, [ADR-036](adr/036-one-node-thousands-of-queries.md) §3 |
