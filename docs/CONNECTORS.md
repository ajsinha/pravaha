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

Three kinds, and a connector may be more than one:

| Interface | What it does | Shipped examples |
|---|---|---|
| `StreamSourcePlugin` | Rows in. The thing a `FROM` clause reads | filesystem, feedfile, aerospike, delta, jdbc, cassandra |
| `StreamSinkPlugin` | Rows out — every commit of a query that names the sink at registration ([ADR-043](adr/043-how-a-continuous-query-names-its-sink.md)). A sink with a configured schema or key reports it through `schema()` and `keyColumns()`, and a registration that does not match is refused | filesystem (append-only), `aerospike-sink` (upsert and delete by key, composite keys), `jdbc-sink` (upsert and delete by key or append, into a table you create; transactional through a staging table, so exactly once on a checkpointed node), `kafka-sink` (keyed JSON upserts with a tombstone for a retraction, or an explicit changelog, to a topic you create; transactional through a staging topic, so exactly once to a `read_committed` consumer on a checkpointed node) |
| `LookupSourcePlugin` | Point lookups for a temporal join's right side | aerospike, jdbc |

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
void        close();
```

`poll` returning `0` means *nothing right now*, not *nothing ever*. The engine will ask again. Write
rows through `sink.beginRow()` and `commit()`; never buffer a batch of your own, because the row you
are given is a cell in the lane's inbox and copying defeats the whole memory design
([`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) §4).

### `SourceCapabilities` — the part that is load-bearing

```java
record SourceCapabilities(
        boolean replayableOffsets,
        boolean orderedWithinPartition,
        boolean emitsDeletes,
        boolean emitsBeforeImage,
        DeliveryGuarantee guarantee,      // AT_MOST_ONCE | AT_LEAST_ONCE | EXACTLY_ONCE
        Set<PushdownKind> pushdown,       // FILTER | PROJECT | PARTIAL_AGGREGATE
        Duration typicalLatency)
```

**These are promises the engine acts on, not documentation.** `SharedSourceGroup` refuses to share
one reader between queries when a source claims `EXACTLY_ONCE`, non-replayable offsets, or ordering
within a partition — because the catch-up handover for a late joiner duplicates its overlap, and
that is unacceptable for a source promising exactly-once (SRC-3).

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
`emitsDeletes = false`.

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

That is a Z-set stream with no translation. A Debezium connector would be the first source able to
set `emitsDeletes = true` and `emitsBeforeImage = true` — **two `SourceCapabilities` fields that have
existed since the SPI was written and that no connector uses.** The framework was designed for this
and has been waiting for it.

It is also push rather than poll: the Aerospike source scans once a second, and CDC delivers the
change when it happens — lower latency, and no scan load on the table.

One connector reaches MySQL, Postgres, SQL Server, Oracle, MongoDB, Db2 and Cassandra.

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
transaction, and buffering it whole is a memory bound nothing here spills past — inboxes are fixed
and off-heap, and nothing spills. Postgres 14 and later can stream an in-progress transaction, which
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
confirm only at a Pravaha checkpoint and the two recover to the same point. This is why a CDC source
plugs into `SplicedReader`'s phase-explicit offsets rather than keeping its own position.

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
  implementation, and no amount of care removes them.
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
misses rows. See `docs/CONTINUOUS_QUERIES.md` §2.1 for the configuration.

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

#### What a CDC binding would look like

**Not built.** No Debezium plugin ships today, and this is the design rather than configuration you
can paste — the shipped source types and their real options are in
[`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1.

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

`slot.name` is the part that is not cosmetic: a Postgres replication slot is server-side state that
retains WAL until it is consumed, so a connector that creates one and stops being read will fill the
database's disk. A CDC connector's operational story is mostly about that slot, not about Pravaha.


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

A Debezium connector plugs into that seam rather than inventing one.

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

**Four of the five shipped plugins hard-code `weight(+1)`.** This is the one route a retraction has
into a configured deployment today, which is why the Z-set model went so long without one — and why
a CDC connector matters beyond the convenience of not writing the file yourself.

## 6. What a connector should refuse to claim

Honesty here is not politeness — the engine changes its behaviour based on these.

- **`EXACTLY_ONCE`** — only if a reader resumed from a checkpointed offset delivers exactly the
  remainder, no more and no less. If you cannot replay, you are at-least-once.
- **`orderedWithinPartition`** — only if two rows for one key always arrive in their true order.
- **`emitsDeletes`** — only if a removal in the store produces a `−1`. Inferring deletion by absence
  in a scan is not this.
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
| `aerospike` | yes — server-side expressions, `alternatives` as `Exp.or` | yes — the scan's bin names | no — server-side aggregation needs Lua stream UDFs registered on the cluster, and a last-update-time scan has no retraction for an overwritten record |
| `cassandra` | no — anything but the partition key needs `ALLOW FILTERING` | yes — the CQL `SELECT` list | no — every pass re-reads the whole range, so no partial could cover "new rows only" |
| `filesystem`, `feedfile`, `delta` | no | no | no |

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
| **Kafka** | streaming | replayable offsets and real exactly-once resumption. The **sink** is built (`kafka-sink`, `plugins/pravaha-plugin-kafka`; its transactional mapping is below); the source is not |
| **Debezium CDC** | changelog | deletes, before-images, Z-sets end to end — the engine's own model |
| **Cassandra** | table scan | the scan path generalises beyond Aerospike — **built**, ADR-039 item 6: a full `token()`-range scan with projection pushdown, `plugins/pravaha-plugin-cassandra` |
| **ScyllaDB** | table scan | speaks the same CQL wire protocol as Cassandra; not built or tested against — the `cassandra` plugin has not been run against it |
| **MySQL / Postgres** | table or CDC | direct; CDC is the better form |
| **RabbitMQ / ActiveMQ / SQS / NATS** | queue | **at-least-once only** — acknowledgement is not an offset, so there is nothing to rewind to |
| **Pulsar / Kinesis / Redpanda** | streaming | as Kafka |
| **Iceberg / Hudi** | table format | as Delta, which already exists |

The queue connectors are architecturally different and it is worth saying so before one is written: a
queue gives you *acknowledgement*, not a position you can return to. They cannot be `EXACTLY_ONCE`,
and `SharedSourceGroup` will decline to share their readers for the same reason it declines JDBC's.


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
| A sink TCK and a lookup TCK | Only sources have one. The transactional sink protocol is tested per sink — `TransactionalSinkDeliveryTest` against a model, `JdbcSinkPluginTest` and `JdbcSinkRegistrationTest` against H2, `KafkaSinkBrokerTest` and `KafkaSinkRegistrationTest` against a real broker — not by a kit a new sink can run |
| Capability verification in the TCK | Replay and exactly-once are tested; ordering, deletes and pushdown claims are believed, not tested |
| An SPI stability statement | `Version` exists; nothing says what change breaks a plugin |
| Plugin isolation | A connector shares the engine's classpath; a dependency clash is yours to resolve |

---

## 9. Where to go next

| You want | Read |
|---|---|
| How a lane consumes what you produce | [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) |
| Where connectors sit in the whole | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Configuring a source on a node | [`OPERATIONS.md`](OPERATIONS.md) |
| Z-sets and incremental computation | [`CONCEPTS.md`](CONCEPTS.md) |
| The snapshot/CDC splice as built | `pravaha-backfill`, [ADR-036](adr/036-one-node-thousands-of-queries.md) §3 |
