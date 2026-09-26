---
title: The Kafka sink — a topic that holds the answer
slug: sink-kafka
category: sinks
order: 45
icon: broadcast
summary: "kafka-sink writes a query's changes to a Kafka topic as keyed JSON — upserts with a tombstone for each retraction, or an explicit changelog — exactly once to read_committed consumers, through a staging topic."
badge: SINK
audience: Engineers
keywords: [kafka, kafka-sink, topic, compacted, compaction, tombstone, upsert, changelog, json, read_committed, isolation.level, transactional.id, staging topic, staging.topic, commit.group, exactly once, sasl, scram, gzip, compression, ktable, ksqldb]
guide: connectors#a-transactional-sink-on-a-store-with-no-prepare-kafka
related: [sinks-overview, delivery-guarantees, source-kafka, sink-jdbc, checkpoints-recovery, zset-weights, connector-security, source-postgres-cdc]
---

`kafka-sink` writes every commit of a query's view to a **Kafka topic**. In its default mode the
topic becomes the answer itself: each record is keyed by the view's key, its value is the row, and a
retraction is a **tombstone** — so a compacted topic holds one record per key, exactly the view, and
Kafka Streams, ksqlDB or a Kafka Connect sink downstream read it as a table. In `changelog` mode it
writes every change with its weight instead, for a consumer that keeps its own aggregate.

It is **transactional**, like [`jdbc-sink`](/help/topics/sink-jdbc): on a node that takes
checkpoints, every change reaches the topic exactly once **as seen by a consumer reading with
`isolation.level=read_committed`**, and each checkpoint's changes become visible together. The price
is that every change is written twice (to a staging topic, then to the target) and the topic trails
the view by up to one checkpoint interval.

The same plugin ships a **source**, `kafka`, which reads a topic back — this sink's changelog
included, weights and all, so one query's retractions reach the next query through a topic, exactly
once ([the Kafka source](/help/topics/source-kafka)).

## At a glance

| | |
|---|---|
| Plugin name | `kafka-sink` (under `pravaha.sinks.<name>`) |
| Module | `plugins/pravaha-plugin-kafka` — in the server jar |
| Format | JSON, the one format built: the key and the value are JSON objects by column name |
| Accepts | `mode: upsert` (default): `UPSERT`, `RETRACT`. `mode: changelog`: `APPEND`, `RETRACT`. **Both take a revising query** |
| Keyed | in upsert mode, by `key.columns`, which must be the view's key |
| Transactional | yes, by default (`transactional: true`) |
| Delivery | **exactly once to `read_committed` consumers** with `transactional: true` and `pravaha.checkpoint.directory` set; see [below](#without-transactions) otherwise |
| Rows per batch | at most 1,000 |
| Target topic | **you create it**; the sink never does |
| Compression | `none` and `gzip` as shipped |
| Security | the shared `tls.*` options; SASL `PLAIN` (TLS required), `SCRAM-SHA-256` or `SCRAM-SHA-512` |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `bootstrap.servers` | yes | — | `host:port,host:port` |
| `topic` | yes | — | The target topic. Must exist when the sink opens (PRV-5101). Letters, digits, `.`, `_`, `-`, at most 249 characters |
| `schema` | yes | — | The row shape, `name:TYPE,...` (`?` for nullable; `DECIMAL(p,s)` is accepted). Must match the registered query's `SELECT` list in order, name and type (PRV-8010) |
| `mode` | no | `upsert` | `upsert`: the topic is a table keyed by `key.columns`, retractions as tombstones. `changelog`: every change as `{"op","weight","row"}` |
| `key.columns` | in upsert mode | — | Comma-separated. Must be the registration's key. Not floating-point, not nullable. Optional in changelog mode, where it keys the records if given |
| `format` | no | `json` | `json` is the only value accepted; anything else is PRV-5100 |
| `transactional` | no | `true` | `true` stages changes and commits them per checkpoint; `false` sends each change straight to the target |
| `transactional.id` | no | the binding's name | The Kafka transactional id, 1 to 200 characters. **One writer per id**: a second opens and fences the first |
| `staging.topic` | no | `pravaha-staging.<transactional.id>` | Where changes wait for their checkpoint. Created if missing — one partition, `cleanup.policy=delete`. Must not be the target, and must not be compacted (PRV-5103) |
| `staging.retention.ms` | no | `604800000` (a week) | The staging topic's retention when the sink creates it. Positive, or `-1` for no limit. Must outlast the longest outage |
| `commit.group` | no | `pravaha-sink.<transactional.id>` | The consumer group whose committed offset is each commit's receipt |
| `user` / `password` | no | empty | SASL credentials. Both or neither: half a credential is refused |
| `sasl.mechanism` | no | `PLAIN` when `user` is set | `PLAIN`, `SCRAM-SHA-256` or `SCRAM-SHA-512`. `PLAIN` without TLS is refused — it sends the password in the clear |
| `tls.*` | no | off | The shared TLS options — see [connector security](/help/topics/connector-security) |
| `kafka.<property>` | no | — | Any other Kafka producer, consumer or admin client property, prefix removed: `kafka.linger.ms: "20"`. A property no Kafka client knows is refused, so a typo is not silently dropped |

**`kafka.*` properties the sink refuses** (PRV-5100, each with what to do instead), because it sets
them itself or because they would weaken what it promises:

| Refused | Why |
|---|---|
| `kafka.bootstrap.servers`, `kafka.transactional.id`, `kafka.sasl.mechanism` | set them on the binding itself |
| `kafka.sasl.jaas.config` | set `user` and `password` |
| `kafka.security.protocol` | it follows from `tls.*` and `user`/`password` |
| `kafka.ssl.*` | TLS is the shared `tls.*` options |
| `kafka.key.serializer`, `kafka.value.serializer` (and the deserializers) | the sink writes JSON itself |
| `kafka.group.id`, `kafka.isolation.level`, `kafka.enable.auto.commit`, `kafka.auto.offset.reset` | the staging reader's settings are part of the guarantee |
| `kafka.enable.idempotence: false` | a retried send could be written twice or out of order |
| `kafka.acks` other than `all` / `-1` | a leader failover could lose a change the sink was told was written |

## Upsert or changelog: what a record looks like

### `mode: upsert` — the topic is a table

The **key** is a JSON object of the key columns, in `key.columns` order. The **value** is a JSON object
of every column. A **retraction** is a tombstone: the same key, a null value.

```text
key:   {"order_id":90114}
value: {"order_id":90114,"customer_id":"c42","region":"EU","amount":250000,"status":"OPEN"}

key:   {"order_id":90114}
value: null                        <- the row left the view: a tombstone
```

That is exactly what log compaction and every table-shaped consumer read as "delete". An update of a
row in the view arrives as the retraction of the old row and the insertion of the new one in the same
commit — a tombstone then the new value under the same key — and compaction keeps the last.

### `mode: changelog` — every change and its weight

The value carries the change itself, not the state after it:

```text
key:   {"txn_id":9001,"user_id":"u1","merchant":"ACME-GROCERY","amount":1250}
value: {"op":"insert","weight":1,"row":{"txn_id":9001,"user_id":"u1","merchant":"ACME-GROCERY","amount":1250}}

key:   {"txn_id":9001,"user_id":"u1","merchant":"ACME-GROCERY","amount":1250}
value: {"op":"delete","weight":-1,"row":{"txn_id":9001,"user_id":"u1","merchant":"ACME-GROCERY","amount":1250}}
```

The key is `key.columns` when set, otherwise the whole row — so a row's insertion and its later
retraction land on the same partition, in order, which a null key would not promise. A consumer of a
changelog **must apply the weight**: summing `row.amount` without it double-counts at the first
correction ([Z-set weights](/help/topics/zset-weights)). A Pravaha [`kafka` source](/help/topics/source-kafka)
with `format: changelog` reads this envelope and applies the weight itself.

### How values are written

| Type | JSON |
|---|---|
| `BOOLEAN`, `INT8` … `INT64` | a number or `true`/`false` |
| `FLOAT32`, `FLOAT64` | a number; a non-finite value as the string `"NaN"`, `"Infinity"` or `"-Infinity"` |
| `DECIMAL` | a number with its exact digits (`1234.50`) |
| `STRING` | a string |
| `BYTES` | base64 |
| `DATE` / `TIME` | `"2026-09-19"` / `"10:15:30.5"` |
| `TIMESTAMP` | an ISO-8601 instant in UTC, `"2026-09-19T10:00:00Z"` |
| NULL | `null` |

The encoding is deterministic — the same key always produces the same bytes — which compaction relies
on: it collapses records whose key *bytes* are equal.

## A complete example: open orders, kept in a compacted topic

Orders come from PostgreSQL through [change data capture](/help/topics/source-postgres-cdc), so an
order that ships is **withdrawn** from the view of open orders — and the topic receives a tombstone
for it.

### 1. Create the topic

The sink checks the target exists and never creates it: its partition count and its cleanup policy
are decisions for whoever owns the topic. In upsert mode make it compacted:

```bash
kafka-topics.sh --bootstrap-server kafka-1.internal:9093 --command-config admin.properties \
  --create --topic open-orders --partitions 6 --replication-factor 3 \
  --config cleanup.policy=compact
```

An upsert sink on a topic that is not compacted is allowed, and logged at `WARNING`: every change is
there, but retention will eventually delete keys the query never retracted. The staging topic
(`pravaha-staging.open_orders_topic` here) is created by the sink on first open if the principal may
create topics.

### 2. Bind it

```yaml
pravaha:
  checkpoint:
    directory: /var/lib/pravaha/checkpoints
    interval: 1m
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 30s
  sources:
    orders:
      plugin: postgres-cdc
      options:
        url: "jdbc:postgresql://db-1.internal:5432/sales?sslmode=verify-full&sslrootcert=/etc/pravaha/tls/pg-ca.pem"
        user: pravaha_cdc
        password: "${PRAVAHA_CDC_PASSWORD}"
        table: public.orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
  sinks:
    open_orders_topic:
      plugin: kafka-sink
      options:
        bootstrap.servers: "kafka-1.internal:9093,kafka-2.internal:9093"
        topic: open-orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING"
        key.columns: order_id
        mode: upsert
        transactional: "true"
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-512
        tls.enabled: "true"
        tls.ca: /etc/pravaha/tls/kafka-ca.pem
        kafka.linger.ms: "20"
        kafka.compression.type: gzip
```

### 3. Register the query

The `SELECT` list is the sink's `schema`, column for column, and `KEYED BY` is its `key.columns`:

```sql
CREATE CONTINUOUS QUERY open_orders
    KEYED BY (order_id)
    WRITING TO open_orders_topic
AS
SELECT order_id, customer_id, region, amount, status
FROM orders
WHERE status = 'OPEN';
```

The node logs the guarantee it will give:

```text
query 'open_orders' writes to sink 'open_orders_topic', exactly-once: the sink is transactional, so what is written between checkpoints is prepared at each checkpoint's cut, recorded in the checkpoint, and committed once the checkpoint is durable
```

### 4. What a consumer sees

An order is inserted, then shipped (`UPDATE orders SET status = 'SHIPPED' WHERE order_id = 90114`).
The update arrives from PostgreSQL as the old row at `-1` and the new row at `+1`; the filter keeps the
first and drops the second, so the view loses the order — and after the next checkpoint:

```bash
kafka-console-consumer.sh --bootstrap-server kafka-1.internal:9093 --consumer.config reader.properties \
  --topic open-orders --from-beginning --isolation-level read_committed \
  --property print.key=true --property key.separator=' | '
```

```text
{"order_id":90114} | {"order_id":90114,"customer_id":"c42","region":"EU","amount":250000,"status":"OPEN"}
{"order_id":90114} | null
```

(Illustrative rows; the console consumer prints a tombstone's value as `null`.) The view says the
same thing at the same moment, a checkpoint interval earlier:

<!-- sql: read -->
```sql
SELECT order_id, customer_id, amount FROM open_orders WHERE region = 'EU'
```

## A changelog for a consumer that aggregates

A consumer maintaining its own totals wants every change, not the state after it. Payments over 1,000,
as a changelog with no key:

```yaml
pravaha:
  sinks:
    big_txn_changes:
      plugin: kafka-sink
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: big-payments-changes
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64"
        mode: changelog
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-256
```

```sql
CREATE CONTINUOUS QUERY big_txn_changes
    KEYED BY (txn_id)
    WRITING TO big_txn_changes
AS
SELECT txn_id, user_id, merchant, amount FROM txn WHERE amount > 1000;
```

With no `key.columns`, the sink's key check has nothing to compare, so any `KEYED BY` registers; each
record is keyed by its whole row. Leave the topic uncompacted: compaction would keep one record per
row and throw away the history a changelog exists to carry.

## How exactly once works: a staging topic

Kafka's transactional producer has no *prepare*: a transaction is open or committed, and a restarted
producer with the same `transactional.id` **aborts** whatever its predecessor left open. Holding a
Kafka transaction open until the checkpoint is durable would therefore lose a checkpoint's changes
whenever the process died between the checkpoint and the commit. So the sink does what `jdbc-sink`
does with a staging table, with a topic:

1. **Write.** Each change is encoded into the exact key and value the target will receive and sent to
   the **staging topic** (one partition, delete policy) through an idempotent producer. Nobody
   reading the target sees it.
2. **Prepare** at the checkpoint's cut: flush, and name the transaction by the staging offsets it
   occupies. The checkpoint records that range, which means the same thing to a process started
   tomorrow.
3. **Commit**, once the checkpoint is durable: read the range back and, in **one Kafka transaction**
   under `transactional.id`, write those records to the target **and** commit the range's end offset
   for `commit.group`. That offset is the commit's receipt: it becomes visible exactly when the
   records do. A commit repeated after a crash finds the receipt already past the range and writes
   nothing.
4. **Open** fences any earlier producer with the same `transactional.id` and aborts the transaction
   it died inside, which the restored checkpoint's commit then redoes.

**What it costs:** every change is written twice, and the target trails the view by up to one
`pravaha.checkpoint.interval` — a minute by default.

**What it assumes:**

- **One writer per `transactional.id`.** A second sink opening with the same id fences the first,
  which is detached with PRV-5102 and then PRV-8009. The default id is the binding's name, so give
  each registration its own binding.
- **Staged changes outlive the gap** between a checkpoint and its commit, restarts included
  (`staging.retention.ms`). A commit whose staged range retention has already deleted is refused with
  PRV-5103 rather than skipped.
- **Readers use `read_committed`.** A `read_uncommitted` consumer can also see the records of a commit
  a crash aborted and the restore redid: at least once, for it.
- **Permissions:** write on the target and staging topics, read on the staging topic, the
  `transactional.id`, and read on `commit.group`.

## Without transactions

`transactional: "false"` sends each change straight to the target through an idempotent producer —
no staging topic, no lag behind the checkpoint:

| Mode | Guarantee without transactions | Why |
|---|---|---|
| `upsert` | **effectively once** on a compacted topic | a replay rewrites keys with the values they already hold |
| `changelog` | **at least once** | a replayed change is a second record, indistinguishable from a new one |

On a node with no `pravaha.checkpoint.directory` there is no checkpoint to tie a transaction to, so
each commit is its own transaction and a restart repeats it: a transactional sink is then
**effectively once** in upsert mode (the repeat rewrites keys with the values they already hold) and
**at least once** in changelog mode. The registration's log line and `GET /api/v1/sinks` both say
which. See [delivery guarantees](/help/topics/delivery-guarantees).

## Compression

`kafka.compression.type` accepts `none` (the default) and `gzip` as the plugin ships. The `lz4`,
`snappy` and `zstd` codecs are native libraries the plugin does not bundle; asking for one is refused
at configuration with PRV-5100, naming the class that is missing, **unless** you put that codec's
library on the plugin's classpath yourself. The refusal comes before the sink opens, not at the first
write.

## Security

TLS is the shared `tls.*` options every connector reads — `tls.enabled`, `tls.ca` or
`tls.truststore`, `tls.certificate` and `tls.key` or `tls.keystore` for a client certificate,
`tls.verify-hostname` (default `true`) — mapped onto Kafka's own `ssl.*` properties. Credentials are
SASL: `user` and `password`, with `sasl.mechanism`. `PLAIN` sends the password as it is, so `PLAIN`
without TLS is refused; the SCRAM mechanisms never send it and are allowed either way. The resulting
`security.protocol` (`PLAINTEXT`, `SSL`, `SASL_PLAINTEXT` or `SASL_SSL`) follows from the two. See
[connector security](/help/topics/connector-security).

## Troubleshooting

| Code | When | What to do |
|---|---|---|
| [PRV-5100](/help/codes/PRV-5100) | at registration, before anything opens | An option missing or malformed, a refused `kafka.*` property, a misspelled one, `PLAIN` without TLS, a float or nullable key column, a codec whose library is absent. The message names it |
| [PRV-5101](/help/codes/PRV-5101) | when the sink opens | Brokers unreachable, the target topic missing, credentials or ACLs refused |
| [PRV-5102](/help/codes/PRV-5102) | while running | A send or commit failed at the broker — most often **fenced** by another writer with the same `transactional.id`, or a transaction that outlived `kafka.transaction.timeout.ms` |
| [PRV-5103](/help/codes/PRV-5103) | at open or at a commit | The staging topic is compacted, cannot be created, or no longer holds the range a checkpoint recorded |
| [PRV-8010](/help/codes/PRV-8010) | at registration | The query's columns are not the sink's `schema`, or its key is not `key.columns` |
| [PRV-8009](/help/codes/PRV-8009) | after a refused batch | The sink is detached; the view carries on. Drop and re-register to start it again |

After PRV-8009 a re-registration sends the view's whole contents first: harmless on a compacted upsert
topic, a repeat of every row on a changelog.

## Pitfalls

!!! warning "Pitfall: a consumer reading uncommitted"
    Kafka consumers default to `read_uncommitted`. Such a consumer also sees staging-then-commit
    records a crash aborted and the restore redid. Set `isolation.level=read_committed` on every
    consumer of the topic, or accept at-least-once.

!!! warning "Pitfall: the topic trails the view"
    With `transactional: true`, a change reaches the topic only after the checkpoint that recorded it
    is durable. A dashboard comparing the topic and the view sees the gap. Read the view when you need
    it now.

!!! warning "Pitfall: two registrations on one binding"
    They share a `transactional.id`, and the second fences the first. Bind one sink per query.

!!! warning "Pitfall: a staging topic that expires too soon"
    A node down longer than `staging.retention.ms` restarts to a checkpoint whose staged changes are
    gone (PRV-5103), and those changes never reach the topic. Size the retention for your longest
    outage; `-1` keeps staged records for ever, at the cost of the disk they hold.

!!! note "Changelog topics are not compacted"
    Compaction keeps one record per key. In changelog mode that deletes the changes a consumer needs
    to replay the history; use a delete policy with a retention long enough for your consumers.

## Where next

- [How a query writes to a sink](/help/topics/sinks-overview) — the shape check, the retraction check
  and detach
- [Delivery guarantees](/help/topics/delivery-guarantees) — how the five shipped sinks compare
- [The Kafka source](/help/topics/source-kafka) — reading a changelog topic back into another query
- [The postgres-cdc source](/help/topics/source-postgres-cdc) — deletes and updates that reach the
  topic as tombstones
- [Z-set weights](/help/topics/zset-weights) — what a changelog consumer must do with `weight`
