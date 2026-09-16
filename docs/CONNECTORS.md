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
| `StreamSourcePlugin` | Rows in. The thing a `FROM` clause reads | filesystem, feedfile, aerospike, delta, jdbc |
| `StreamSinkPlugin` | Rows out | aerospike |
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

The TCK does not yet verify these claims, and it should. Until then they are trusted.

---

## 7. Connectors worth building, and what each one proves

| Connector | Kind | Proves |
|---|---|---|
| **Kafka** | streaming | replayable offsets and real exactly-once resumption |
| **Debezium CDC** | changelog | deletes, before-images, Z-sets end to end — the engine's own model |
| **Cassandra / ScyllaDB** | table scan | the scan path generalises beyond Aerospike |
| **MySQL / Postgres** | table or CDC | direct; CDC is the better form |
| **RabbitMQ / ActiveMQ / SQS / NATS** | queue | **at-least-once only** — acknowledgement is not an offset, so there is nothing to rewind to |
| **Pulsar / Kinesis / Redpanda** | streaming | as Kafka |
| **Iceberg / Hudi** | table format | as Delta, which already exists |

The queue connectors are architecturally different and it is worth saying so before one is written: a
queue gives you *acknowledgement*, not a position you can return to. They cannot be `EXACTLY_ONCE`,
and `SharedSourceGroup` will decline to share their readers for the same reason it declines JDBC's.


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
| A sink TCK and a lookup TCK | Only sources have one |
| Capability verification in the TCK | A plugin claiming `EXACTLY_ONCE` is believed, not tested |
| An SPI stability statement | `Version` exists; nothing says what change breaks a plugin |
| A cross-source join test | §4's capability is unproven |
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
