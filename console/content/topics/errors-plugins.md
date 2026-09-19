---
title: Plugin codes (PRV-5xxx)
slug: errors-plugins
category: errors
order: 60
icon: plug
summary: "PRV-5001 to PRV-5117: loading and naming plugins, then every connector's own refusals — filesystem, Delta, feedfile, JDBC, Aerospike, Cassandra, Kafka, PostgreSQL CDC — and attaching a source or a sink to a registered query."
badge: PRV-5XXX
audience: Operators
keywords: [plugin, classpath, serviceloader, binding, options, filesystem, decode, delta, vacuum, deletion vectors, feedfile, jdbc, aerospike, cassandra, kafka, fenced, staging topic, retention, resume point, tombstone, undecodable record, postgres-cdc, replication slot, wal_level, replica identity, truncate, offset, sink, source, connect failed, schema]
guide: connectors
related: [sources-overview, sinks-overview, source-jdbc, source-postgres-cdc, source-kafka, sink-kafka, source-delta, connector-security, errors-overview]
---

Every source, lookup and sink is a **plugin**, found on the classpath by its name
(`ServiceLoader`), configured by the `options:` under its binding, and asked to read or write. The
5xxx range covers each of those steps. It is the largest range because every connector has its own
block, so a code says not only *what* failed but *which* connector — "which 5001?" is not a question
a support conversation should have to start with.

**Two rules hold for every connector**, and many of the codes below are them being kept:

1. **Refuse at configuration, never silently downgrade.** A strategy that is declared and not built, a
   TLS setting a driver cannot honour, a table feature whose semantics cannot be kept — each is
   refused by name when the binding is opened, not replaced by something with different delivery
   properties.
2. **Never serve rows that are wrong without being obviously wrong.** A file vacuumed from under a
   Delta table, a feed file rotated away mid-read, an offset this plugin did not write — each stops
   the feed rather than skipping data nobody would notice was skipped.

| Block | Area |
|---|---|
| PRV-5001, PRV-5010 – PRV-5013, PRV-5030 | Loading and naming plugins |
| PRV-5040 | `filesystem` |
| PRV-5050 – PRV-5055 | `delta` |
| PRV-5060 – PRV-5065 | `feedfile` |
| PRV-5070 – PRV-5076 | `jdbc`, `jdbc-lookup`, `jdbc-sink` |
| PRV-5080 – PRV-5084 | `aerospike`, `aerospike-lookup`, `aerospike-sink` |
| PRV-5085 – PRV-5089 | `cassandra` |
| PRV-5090 – PRV-5094 | Attaching a source or sink to a registered query |
| PRV-5100 – PRV-5107 | `kafka` (source) and `kafka-sink` |
| PRV-5110 – PRV-5117 | `postgres-cdc` |

## Loading and naming plugins

### PRV-5001 — plugin missing setting

A plugin asked for an option its binding does not give — or gave one that should be a number and is
not. The message names the plugin instance and the key.

!!! warning "Pitfall: options belong under options:"
    A key written one level too high — directly under the source instead of under its `options:` — is
    not read and not reported, and the plugin then says a required setting is missing. If the option
    is plainly in your file, check its indentation first.

```yaml
pravaha:
  sources:
    txn:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
```

### PRV-5010 — plugin not found

No plugin on the classpath answers to the name. The message lists the names that *are* available.
Only `filesystem` is inside the server jar; `feedfile`, `jdbc`, `delta`, `aerospike`, `cassandra`,
`postgres-cdc`, `kafka` and `kafka-sink` are separate modules, added by dropping the jar on the classpath. Discovery happens when a query is
first registered against the stream, **not at startup** — so a binding naming a missing plugin starts
a server cleanly and fails at the registration that needs it.

### PRV-5011 — plugin incompatible API

The plugin's manifest says it was built against a plugin API this engine cannot host. The message
names both versions: rebuild the plugin against this engine's API. The console's **Plugins** screen
shows each plugin's required API and whether this engine can host it.

### PRV-5012 — plugin load failed

The plugin was found and could not be loaded — a missing dependency of its own, a class that failed
to initialise. The cause follows in the message; it is usually a jar missing beside the plugin's.

### PRV-5013 — plugin duplicate name

Two plugins both call themselves the same name. Names are how configuration refers to a plugin, so
they must be unique; remove one of the jars (usually two versions of the same plugin).

### PRV-5030 — plugin capability mismatch

The plugin named is not the kind the binding needs: a source configured where a sink was meant, or the
reverse — `plugin: jdbc` under `pravaha.sinks` instead of `jdbc-sink`. Each connector's source, lookup
and sink are separate plugins with separate names.

## filesystem

### PRV-5040 — filesystem decode failed

A line of a delimited file does not match the declared schema — a value that does not parse as its
column's type, the wrong number of fields — or the schema itself cannot be declared. Three causes are
worth knowing because the message alone will not tell you:

- **`unknown type 'DECIMAL(10'` when you wrote `DECIMAL(10,2)`.** The `name:TYPE,name:TYPE` grammar is
  split on commas, and a decimal cannot be declared through any schema string today (TY-7). Declare
  it programmatically, or carry integer cents in an `INT64`.
- **`read failed at line 0` on a file that is plainly there.** Some byte in the file is not valid
  UTF-8. The reader decodes whole lines as UTF-8 before any column, so one invalid sequence ends the
  read — including one inside a `BYTES` column (TY-12). Base64 binary into a `STRING` instead.
- **Too many open files.** One bound source costs about one descriptor; near the process's ceiling a
  source that fails to open can surface under this decode code. The node logs its descriptor ceiling
  at startup; raise `ulimit -n` / `LimitNOFILE`.

Without `pravaha.dlq.directory`, one undecodable record stops the source — loudly, on purpose. With
it, the record is written to the dead-letter queue with its bytes and reason, and reading continues.
See [Dead letters](/help/topics/dead-letters).

## delta

### PRV-5050 — Delta table unreadable

The path is not there, or is not a Delta table at all (no `_delta_log`). Check `path` and the process
user's permissions.

### PRV-5051 — Delta unsupported type

A column of a Delta type this plugin does not map. Named, with the column — never silently dropped.

### PRV-5052 — Delta malformed offset

A stored offset this plugin did not write, or wrote in an older format — so it cannot say where in the
table's history reading should resume. Resume from a version you choose with `start.version`.

### PRV-5053 — Delta file vacuumed

A data file the Delta log still references has been **removed from disk** — typically by `VACUUM`.
Its retractions cannot be reconstructed, and the rows it removed would otherwise keep being served as
though still live. **Do:** keep the table's `VACUUM` retention longer than the longest a query may be
behind the table, and restart the query from a version that still has its files.

### PRV-5054 — Delta read failed

Reading the table failed for a reason the Delta Kernel library reported; the message carries it.

### PRV-5055 — Delta unsupported feature

A table feature whose semantics this plugin cannot honour — **deletion vectors**, today. Changes are
derived by diffing each version's file list, and a deletion vector deletes rows *without rewriting
the file*, so the deleted rows would keep being served as live, silently. Set
`delta.enableDeletionVectors=false` on the table.

## feedfile

### PRV-5060 — feedfile directory unreadable

The feed directory (`dir`) is missing or cannot be read by the process user.

### PRV-5061 — feedfile bad schema

The `schema` option cannot be parsed. The grammar is `name:TYPE,name:TYPE`, with `?` after a type for
nullable.

### PRV-5062 — feedfile decode failed

A record does not match the declared schema. The message names the **file and the line**. As with
`filesystem`, `pravaha.dlq.directory` decides whether this stops the feed or writes the record aside.

### PRV-5063 — feedfile malformed offset

A stored offset this plugin did not write, or wrote in an older format.

### PRV-5064 — feedfile file gone

A file an offset points into is **no longer in the feed directory** while the reader was part-way
through it — so continuing would skip the rest of it without anybody noticing. Feed files must outlive
the readers still in them: raise the retention on whatever rotates them, or let the query finish the
file before it is moved. (`archive.dir` does this correctly: a file is archived after it is read.)

### PRV-5065 — feedfile bad configuration

An option value the plugin cannot honour, named in the message: `completion` must be `marker`,
`stable` or `immediate`; `order` must be `name` or `mtime`; `format` must be `csv` or `parquet`;
`delimiter` must be a single character. A valid binding, for comparison:

```yaml
pravaha:
  sources:
    orders:
      plugin: feedfile
      options:
        dir: /var/feeds/orders
        glob: "orders-*.csv"
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        completion: marker
        completion.marker.suffix: .done
        archive.dir: /var/feeds/orders-done
```

## jdbc

### PRV-5070 — JDBC connect failed

The database could not be reached, or the credentials were refused. Check `url`, `user`, `password`,
and that the JDBC driver jar is on the classpath.

### PRV-5071 — JDBC query failed

A poll or a lookup failed at the database, or a result could not be read. The database's own message
follows.

### PRV-5072 — JDBC unsupported type

A SQL type this plugin does not map to a Pravaha type; the column is named. `DECIMAL`/`NUMERIC` is the
usual one — the engine refuses decimal arithmetic rather than rounding silently, so cast to integer
cents in the database with a `query:` instead of `table:`.

### PRV-5073 — JDBC malformed offset

A stored offset this plugin did not write.

### PRV-5074 — JDBC bad configuration

A configuration that cannot be honoured. The commonest: **both `table` and `query`, or neither** —
exactly one is required, and the refusal says which does what. And **`tls.*` options on a JDBC
binding** are refused: a JDBC driver's TLS is configured in its URL, and accepting the shared `tls.*`
options would promise an encrypted connection the driver never made. Put TLS in the `url`, or set
`tls.enabled: false` to say the plaintext connection is deliberate.

```yaml
pravaha:
  sources:
    orders:
      plugin: jdbc
      options:
        url: "jdbc:postgresql://db-1:5432/sales?sslmode=verify-full"
        user: pravaha
        password: "${PRAVAHA_DB_PASSWORD}"
        table: orders
        watermark.column: updated_at
        key.column: order_id
```

### PRV-5075 — JDBC sink table mismatch

A `jdbc-sink`'s table disagrees with its declaration: the table or a column does not exist (or this
user cannot see it), a column's type cannot hold the declared type, the key has nothing in the table
to enforce it, or the table has a `NOT NULL` column with no default that the sink's schema does not
write — so every insert would fail. The sink **writes into a table you create**; it does not create
one, because the column types, the key and the indexes are decisions about your database. The message
names the table and the column. Checked when the sink is opened, before any row is written.

### PRV-5076 — JDBC write failed

A sink's write, staging, commit or abort failed at the database. The sink is then **detached** from
its query with PRV-8009 — see [Registry codes](/help/topics/errors-registry) — and the view carries on.

## aerospike

### PRV-5080 — Aerospike connect failed

The cluster could not be reached or refused the connection. Two causes that look like others: a
containerised Aerospike reports its *bridge* address to clients, so the client connects to the seed
and is redirected somewhere it cannot reach — run it with `--network host`; and running out of file
descriptors surfaces here too, because the client's exception carries no cause to tell them apart
(SRC-4).

### PRV-5081 — Aerospike operation failed

A scan, read or write failed at the cluster; the client's message follows.

### PRV-5082 — Aerospike unsupported type

A bin holds a type this plugin will not guess at. Declare the bin's type in `schema`, or store it as
one of the supported types.

### PRV-5083 — Aerospike bad configuration

A configuration that cannot be honoured — including **a `strategy` this build cannot run**. `strategy`
declares four values and implements one, `lut-scan`; `xdr-kafka`, `xdr-http` and `write-intercept`
are refused here rather than silently replaced by a strategy with different delivery properties.

### PRV-5084 — Aerospike malformed offset

A stored offset this plugin did not write.

## cassandra

### PRV-5085 — Cassandra connect failed

The cluster could not be reached or refused the connection. Name `local.datacenter` when the cluster
has more than one.

### PRV-5086 — Cassandra operation failed

A CQL statement failed against the server; the driver's message follows.

### PRV-5087 — Cassandra unsupported type

A column of a CQL type this plugin will not guess at; the column is named.

### PRV-5088 — Cassandra bad configuration

A configuration that cannot be honoured — including a `strategy` that is declared and not built:
`writetime-incremental` and `commitlog-cdc` are refused; `token-range-scan` is the one implemented.
**A full periodic scan that says what it is beats an incremental one that quietly misses rows.**

### PRV-5089 — Cassandra malformed offset

A stored offset this plugin did not write.

## Attaching sources and sinks to a query

These are raised by the engine's binding layer, between the registry and a plugin: resolving the
plugin a `pravaha.sources.*`, `pravaha.lookups.*` or `pravaha.sinks.*` binding names, opening it, and
keeping it fed.

### PRV-5090 — ingest: no such plugin

No source or lookup plugin on the classpath answers to the name a binding gave. The message lists what
is available — or says that no plugin jar of that kind is on the classpath at all.

### PRV-5091 — ingest: binding failed

The plugin refused its configuration, or could not open what it was pointed at, when a query was
registered against the stream. The message carries the plugin's own reason (often one of the connector
codes above) and, where the process is short of file descriptors, a hint naming `ulimit -n`.

### PRV-5092 — ingest: feed failed

A source failed **after the query was already running**: the feed stopped part-way. The plugin's error
follows. The query stops receiving rows; fix the source, then drop and register the query to reattach
it.

### PRV-5093 — egress: no such sink plugin

No sink plugin answers to the name a `pravaha.sinks.*` binding gave. The shipped sinks are
`filesystem`, `jdbc-sink`, `aerospike-sink` and `kafka-sink`.

### PRV-5094 — egress: sink binding failed

The sink plugin refused its configuration or could not open its target at registration — before the
query's first commit, so nothing is half-written.

## Kafka: kafka-sink and the kafka source

One plugin, one block of codes. PRV-5100 and PRV-5101 are either direction's; PRV-5102 and PRV-5103
are the sink's, PRV-5104 to PRV-5107 the source's. Every option, the staging topic and the sink's
guarantee are on [the Kafka sink](/help/topics/sink-kafka); the source's offsets, formats and
recoveries on [the Kafka source](/help/topics/source-kafka).

### PRV-5100 — Kafka: bad configuration

The binding's options cannot make a sink or a source. For the **sink**: a required option missing (`bootstrap.servers`, `topic`,
`schema`), `key.columns` missing in upsert mode or naming a floating-point or nullable column, an
unknown `format` or `mode`, a `kafka.*` property the sink sets itself or that would weaken its
guarantee (`kafka.acks` below `all`, `kafka.enable.idempotence: false`, serializers, `kafka.ssl.*`)
or that no Kafka client knows, SASL `PLAIN` without TLS, half a credential — or a compression codec
whose library is not on the classpath. `none` and `gzip` work as shipped; lz4, snappy and zstd are
native code the plugin does not bundle, and are refused by name rather than failing at the first
write unless you add the codec's library yourself.

For the **source**: `bootstrap.servers`, `topic` or `schema` missing, a `format` other than `json` or
`changelog`, `tombstone` not `reject` or `skip`, `start.from` not `earliest` or `latest`, an
`isolation.level` other than `read_committed` or `read_uncommitted`, `event.time` naming a column
that is not in `schema` or is not a `TIMESTAMP`, a `kafka.*` property that is not a consumer property
or that would move the position (`kafka.group.id`, `kafka.enable.auto.commit`,
`kafka.auto.offset.reset`, the deserializers, `kafka.allow.auto.create.topics`), and the same TLS and
SASL refusals as the sink. The message says what to do instead.

### PRV-5101 — Kafka: connect failed

At registration, before anything is written or read: the brokers unreachable (for the source, within
`start.timeout`), the topic missing — neither the sink nor the source ever creates the topic it
names — or the credentials or ACLs refused (the source needs `Describe` and `Read` on its topic).

### PRV-5102 — Kafka: write failed

The sink detached after it had started. Most often another producer **fenced** it: two sinks opened
with the same `transactional.id` — two nodes running one binding, or two registrations naming it —
and the broker lets only the newest write. Give each registration its own binding (the default id is
the binding's name). The same code follows a transaction that outlived
`kafka.transaction.timeout.ms`.

### PRV-5103 — Kafka: staging topic unusable

The staging topic that makes the sink exactly once is compacted, cannot be created, or — at a restart
after a long outage — has already expired the staged changes a checkpoint recorded, which are then
lost to the topic. Raise `staging.retention.ms` and register again.

### PRV-5104 — Kafka: malformed offset

At a restore: the checkpoint holds a source position this plugin did not write, or one for another
topic or partition — the binding's `topic` was changed under a checkpoint that still holds the old
topic's offsets. An offset means nothing in another partition, so it is refused rather than seeked
to. If the change was deliberate, drop the query and register it afresh.

### PRV-5105 — Kafka: undecodable record

A record the source cannot turn into a row — not JSON, a value of the wrong type for its column, a
`NOT NULL` column missing, a changelog record without `row` or `weight`, or a **tombstone** in
`format: json` — and no dead-letter queue to set it aside in. The message names it as
`topic/partition@offset`. The position stays before it, so a restart meets it again. Set
`pravaha.dlq.directory` to set such records aside and read on, fix the producer, or, for an upsert
topic's tombstones, set `tombstone: skip`.

### PRV-5106 — Kafka: resume point gone

The offset the source must read from is no longer in the partition: retention deleted records the
checkpoint had not yet read (a node down, or a query paused, longer than the topic's
`retention.ms`), or the checkpoint's offset is past the partition's end because the topic was deleted
and recreated. Those records are lost to every reader, and resuming anywhere else would hide it. Raise
the topic's retention, then drop the query and register it again; it starts from `start.from`,
without them.

### PRV-5107 — Kafka: read failed

Fetching failed in a way retrying will not fix — an ACL revoked mid-stream, the topic deleted. The
source's health turns `UNHEALTHY` with the reason. Fix the cause. A node restart resumes the query
from its checkpoint's offsets; dropping and registering it instead starts it afresh from
`start.from`.

## postgres-cdc

The prerequisites, every option, the slot and the recoveries are on
[the postgres-cdc source](/help/topics/source-postgres-cdc).

### PRV-5110 — PostgreSQL CDC: bad configuration

The binding's options are wrong: a required option missing, a value out of range, or a shared
`tls.*` option (TLS for this source goes in the JDBC URL).

### PRV-5111 — PostgreSQL CDC: connect failed

The database unreachable, the credentials refused, or the PostgreSQL driver not on the classpath —
the plugin uses the driver the deployment supplies, as `jdbc` does.

### PRV-5112 — PostgreSQL CDC: not capturable

The database cannot support change capture as configured, and the message names the statement that
fixes it: `wal_level` is not `logical` (`ALTER SYSTEM SET wal_level = logical;` and a **restart**), the
table is not `REPLICA IDENTITY FULL` (`ALTER TABLE ... REPLICA IDENTITY FULL;`, without which a delete
could retract only the key), the publication does not publish updates and deletes or does not include
the table, the server is older than PostgreSQL 14, or the slot is missing, invalidated or belongs to
another plugin or database.

### PRV-5113 — PostgreSQL CDC: schema mismatch

The table's columns disagree with a declared `schema`, or a column has a type with no mapping. Leave
that column out of a declared schema.

### PRV-5114 — PostgreSQL CDC: malformed offset

A checkpoint holds an offset this plugin did not write. It is refused rather than guessed at.

### PRV-5115 — PostgreSQL CDC: resume point released

At a restart: the slot has already been confirmed past the checkpoint being restored — the newest
checkpoint was unreadable and recovery fell back to an older one, or the slot was recreated — and
PostgreSQL has released the changes in between. Starting anyway would skip them silently.

### PRV-5116 — PostgreSQL CDC: unrepresentable change

The change stream carried something that cannot become rows: a `TRUNCATE` of the captured table (it
carries no rows, so there is nothing to retract), or a before-image with only the key (the table's
replica identity changed while it was being captured). Everything before it was delivered.

### PRV-5117 — PostgreSQL CDC: stream failed

The replication stream failed in a way no reconnect can fix: the slot dropped or invalidated, or the
role's privileges revoked.

For PRV-5115, 5116 and 5117 the recovery is the same: stop the registration, delete its checkpoint
directory, drop the slot, register again.

## Where next

- [Sources](/help/topics/sources-overview) and [Sinks](/help/topics/sinks-overview), and each
  connector's own page — among them [postgres-cdc](/help/topics/source-postgres-cdc),
  [the kafka source](/help/topics/source-kafka) and [kafka-sink](/help/topics/sink-kafka)
- [Connector security](/help/topics/connector-security) — credentials and TLS per connector
- [Dead letters](/help/topics/dead-letters)
