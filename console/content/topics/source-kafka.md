---
title: The Kafka source — a topic read exactly once
slug: source-kafka
category: sources
order: 47
icon: broadcast-pin
summary: "kafka reads a topic as a stream: one reader per partition, its offsets in the query's checkpoint and never in a consumer group, exactly once. JSON, kafka-sink's changelog, Avro or Protobuf, with a schema registry and no library for any of them."
badge: SOURCE
audience: Operators
keywords: [kafka, kafka source, topic, partition, offset, consumer, consumer group, monitoring.group, read_committed, isolation.level, exactly once, changelog, tombstone, json, avro, protobuf, schema registry, schema.file, schema.reader.file, schema resolution, schema evolution, aliases, nested record, schema.descriptor, schema.registry.url, confluent, karapace, apicurio, descriptor set, DynamicMessage, logical type, start.from, earliest, latest, retention, retention.ms, lag, sasl, scram, dead letter, PRV-5106, PRV-5108, PRV-5109, PRV-5130, topic.missing.timeout, deleted topic]
guide: connectors#a-replayable-source-kafka
related: [sources-overview, sink-kafka, source-postgres-cdc, checkpoints-recovery, zset-weights, dead-letters, connector-security, delivery-guarantees]
listed_on: sources-overview
---

The `kafka` plugin reads a **Kafka topic as a stream**. Each partition of the topic gets its own
reader, *assigned* that partition rather than subscribed to the topic, so no consumer group ever
moves partitions between readers behind the engine's back. A reader's position is the next offset
to read, and **that position lives in the query's checkpoint and nowhere else**: a restore seeks each
partition to the offset the checkpoint recorded, the log replays deterministically from there, and
the engine receives exactly the records the checkpoint does not already hold. So the source is
**exactly once**.

It reads four shapes of value: a **JSON object** of the row's columns (what most producers write, and
what [`kafka-sink`](/help/topics/sink-kafka) writes in upsert mode), **`kafka-sink`'s changelog**,
whose records carry their weight — so a retraction one query writes to a topic is a retraction in the
next query that reads it — **Avro**'s binary encoding, and **Protobuf**. Avro and Protobuf are read
without adding a library for either: the Avro reader is written here from the specification, Protobuf
goes through `DynamicMessage` over a descriptor set you supply, and a **schema registry** is spoken
over its REST API rather than through the Confluent client.

The same Kafka plugin ships the sink, `kafka-sink`; this page is its source, named `kafka`.

## At a glance

| | |
|---|---|
| Plugin name | `kafka` (under `pravaha.sources.<stream>`) |
| Module | `plugins/pravaha-plugin-kafka` — in the server jar; the same module as `kafka-sink` |
| Kind | stream source, one topic per binding, **one reader per partition** |
| Delivery guarantee | **`EXACTLY_ONCE`** — the offsets are the checkpoint's |
| Replayable offsets / ordered | yes (for as long as retention keeps the records) / yes, within a partition |
| Emits deletes / before-image | only with `format: changelog` — then **yes / yes** |
| Formats | `json` (default): a JSON object by column name, every row `+1`. `changelog`: `kafka-sink`'s `{"op","weight","row"}` envelope, weight applied. `avro`: Avro binary, against `schema.file` or a schema registry. `protobuf`: one message of a `FileDescriptorSet` |
| Isolation | `read_committed` by default: an aborted transaction's records are never delivered |
| Pushdown | none — Kafka has no server-side filter; every record is fetched whole |
| Shared between queries | **yes** — one reader per binding feeds every query over it, a query joining late caught up to the shared reader's exact offset first ([ADR-054](/help/decisions/054-an-ordered-source-is-shared-at-an-exact-seam)); `share.reader: "false"` gives each query its own |
| Event time | the `event.time` column (on a server, the stream's declared `event-time`); without one, the record's Kafka timestamp |
| The topic | **must exist**; the source never creates it |
| Security | the shared `tls.*` options; SASL `PLAIN` (TLS required), `SCRAM-SHA-256` or `SCRAM-SHA-512` |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `bootstrap.servers` | yes | — | `host:port,host:port` |
| `topic` | yes | — | The topic to read. Letters, digits, `.`, `_`, `-`, at most 249 characters. Must exist when the source opens (PRV-5101) |
| `schema` | yes | — | `name:TYPE,...` — the same grammar as every other connector, `?` after a type for a nullable column, `DECIMAL(p,s)` accepted. Write the stream's own schema here, column for column |
| `format` | no | `json` | `json`: each value is a JSON object of the row. `changelog`: each value is `kafka-sink`'s changelog envelope. `avro`: Avro's binary encoding. `protobuf`: one protobuf message. Anything else is PRV-5100 |
| `schema.file` | with `format: avro` | — | The **writer** schema, as Avro JSON, on this node's disk. Exactly one of this and `schema.registry.url`; with any other format, PRV-5100 naming both |
| `schema.reader.file` | no | — | With `format: avro` only: a **reader** schema, as Avro JSON. Every writer schema — `schema.file`'s, or each registry id's — is resolved against it by the Avro specification's rules, and the columns are matched against it. See [schema evolution](#schema-evolution-a-reader-schema). Any other format: PRV-5100 |
| `schema.registry.url` | with `format: avro` | — | `http(s)://host:port`, any path prefix kept (Apicurio's `/apis/ccompat/v7`). Each value must then begin with the wire format's `0x00` and four-byte schema id. With `format: protobuf` it means only that the prefix and Confluent's message-index array are read past |
| `schema.registry.user` / `schema.registry.password` | no | none | HTTP basic auth for the registry. Refused (PRV-5100) without `schema.registry.url` |
| `schema.registry.token` | no | none | A bearer token instead of basic auth |
| `schema.registry.timeout` | no | `10s` | Per attempt; three attempts a short pause apart, then PRV-5109 |
| `schema.descriptor` | with `format: protobuf`, unless `schema.registry.url` is set | — | A `FileDescriptorSet` file: `protoc --include_imports --descriptor_set_out=x.desc your.proto`. Left out with a registry, the registry describes each record |
| `schema.message` | with `schema.descriptor` | — | The message in it a record holds, by full name (`acme.orders.Order`) or an unambiguous simple name. With the registry's descriptors it is optional, and a record whose message indexes select another message is a dead letter |
| `tombstone` | no | `reject` | What a record with a null value is in `format: json`. `reject`: a dead letter, or the source stops. `skip`: stepped over, so an upsert topic reads as insertions only |
| `event.time` | no | on a server, the stream's `event-time`; otherwise none | A `TIMESTAMP` column of `schema` whose value becomes each row's event time. Naming a column that is not there, or is not a `TIMESTAMP`, is PRV-5100. Without it, a row's event time is the record's Kafka timestamp |
| `start.from` | no | `earliest` | Where a reader starts **when there is no checkpoint**: `earliest`, the first record the partition still holds, or `latest`, after what it holds now. A restore ignores it and resumes at the checkpoint's offsets |
| `isolation.level` | no | `read_committed` | `read_committed` never delivers what an aborted transaction wrote, and waits behind one still open. `read_uncommitted` delivers both |
| `monitoring.group` | no | none | A consumer group the offsets of each **durable checkpoint** are committed to, so Kafka's own tooling and lag dashboards can see the source. **Never read back** — it is a report, not a position |
| `buffer.records` | no | `10000` | Decoded records per partition waiting for the engine before the reader stops fetching. 1 to 2,147,483,647 |
| `start.timeout` | no | `30s` | How long opening waits for the brokers, and for a new reader to have queued what the partition already holds |
| `lag.warn.records` | no | `100000` | Records behind the end of any partition past which the source's health is `DEGRADED` |
| `partitions.refresh` | no | `30s` | How often the topic's partitions are listed again, so one added while a query runs is read without a restart. At least `1s` (PRV-5100): each refresh is a metadata request |
| `topic.missing.timeout` | no | `30s` | How long the topic may be unknown to the brokers before a running reader stops with PRV-5130 (TOPICGONE-1). At least `1s` (PRV-5100); the topic is asked for every fifth of it, at most every 5s, while the partition is quiet |
| `user` / `password` | no | empty | SASL credentials. Both or neither: half a credential is refused |
| `sasl.mechanism` | no | `PLAIN` when `user` is set | `PLAIN`, `SCRAM-SHA-256` or `SCRAM-SHA-512`. `PLAIN` without TLS is refused — it sends the password in the clear |
| `tls.*` | no | off | The shared TLS options — see [connector security](/help/topics/connector-security) |
| `kafka.<property>` | no | — | Any other **Kafka consumer** property, prefix removed: `kafka.fetch.max.bytes: "52428800"`. A name that is not a consumer property is refused, so a typo is not silently dropped |
| `share.reader` | no | `true` | Read by the binding layer: `false` gives each query its own consumers instead of one shared reader |

Durations take `500ms`, `10s`, `5m`, `1h`, or ISO-8601 (`PT30S`).

**`kafka.*` properties the source refuses** (PRV-5100, each with what to do instead) — because it sets
them itself, or because they would move the position somewhere the checkpoint did not put it:

| Refused | Why |
|---|---|
| `kafka.group.id` | the checkpoint is the position, not a group; set `monitoring.group` to report it |
| `kafka.enable.auto.commit` | offsets are committed only after a durable checkpoint, and only to `monitoring.group` |
| `kafka.auto.offset.reset` | the source seeks to exact offsets; `start.from` chooses where a new reader starts. It is set to `none`, so a position the log no longer has is refused (PRV-5106), never quietly reset |
| `kafka.isolation.level`, `kafka.bootstrap.servers`, `kafka.sasl.mechanism` | set them on the binding itself |
| `kafka.sasl.jaas.config` | set `user` and `password` |
| `kafka.security.protocol` | it follows from `tls.*` and `user`/`password` |
| `kafka.ssl.*` | TLS is the shared `tls.*` options |
| `kafka.key.deserializer`, `kafka.value.deserializer` | the source reads the value itself, in the format the binding declares |
| `kafka.allow.auto.create.topics` | the source never creates the topic it reads |

## One reader per partition

The topic's partitions are the source's partitions: one per Kafka partition, in partition order, each
read by a consumer of its own on a fetch thread of its own. The lane's poll never touches the
network — it takes decoded records from a bounded queue (`buffer.records`), and when the engine pauses
the reader for backpressure the partition is paused at the consumer too, so the pressure reaches the
broker rather than the heap.

Within a partition, records arrive in offset order. Across partitions there is no order, which is
Kafka's own rule: key your producer so that the records that must stay in order share a partition.

**Partitions added while a query runs are read.** The source asks the brokers for the topic's
partitions again every `partitions.refresh` (30 seconds by default), and a partition added meanwhile
gets a reader of its own **from its first record**, whatever `start.from` says: a partition that did
not exist when the query registered has no history the query chose to skip. Its offset is in the next
checkpoint like any other, and a restart resumes it there. A partition added while the node was down
is found at the restart and read from its first record too. The query's feed says so
(`txn gained 1 partition while running [3]`), and a refresh the brokers do not answer is retried and
shown there until one succeeds. A new partition's rows are late like any other if their event times are
behind the watermark.

**Threads.** Queries over one binding share its reader: ten queries over a 12-partition topic are
12 consumers and 12 fetch threads, each holding up to `buffer.records` decoded records, and each
record is handed to all ten. A query registered later reads only the gap between its checkpoint (or
the topic's beginning) and the shared reader's offset, privately, and is attached at that exact
offset — every record reaches every query once, in order (ADR-054). With `share.reader: "false"` —
or a query that reads a different set of columns — each query keeps its own consumers: 120 for ten.

## Exactly once: the checkpoint owns the offsets

A reader's position is `topic/partition@next` — `orders/3@42` means "partition 3 of `orders`, next
offset 42" — and it is recorded in the query's checkpoint with everything else. Three things follow:

1. **A restore resumes exactly.** Each partition is seeked to the offset the checkpoint recorded;
   Kafka's log replays the same records from there, the same aborted ones left out, so the engine
   receives exactly what the checkpoint does not hold.
2. **A consumer group's committed offset is never read.** A group offset is committed when a consumer
   says so, not when a checkpoint is durable, and resuming from it would lose or repeat whatever lay
   between the two. That is why `kafka.group.id` is refused.
3. **Checkpointing is required for the guarantee.** On a node without `pravaha.checkpoint.directory`
   there are no offsets to resume from, and a restart starts each partition again from `start.from`:
   with `earliest` the view is rebuilt from what the topic still holds, with `latest` it misses
   everything written while the node was down.

The source leaves **nothing on the brokers**: no slot, no group it depends on. Dropping a registration
needs no cleanup on the Kafka side.

### `monitoring.group` only reports

Kafka's tooling cannot see a consumer that belongs to no group. Set `monitoring.group` and, each time a
checkpoint becomes durable, its offsets are committed to that group:

```bash
kafka-consumer-groups.sh --bootstrap-server kafka-1.internal:9093 --command-config admin.properties \
  --describe --group pravaha-orders
```

shows where a restore would resume — one checkpoint interval behind the live position. No member
ever joins the group, so it shows as `Empty` with offsets, which is correct. **Resetting its offsets
moves nothing**: the group is written, never read. A commit the broker refuses leaves the source
running and turns its health `DEGRADED` with the reason; it is tried again.

### `read_committed`, and what it means with `kafka-sink`

`isolation.level: read_committed` (the default) never delivers the records of an aborted Kafka
transaction, and the reader's position steps over commit markers and aborted records only once
everything before them has been handed over — so a checkpoint never records a position past a row the
engine has not seen.

That is what makes a topic **written by `kafka-sink`** read back exactly once. `kafka-sink` commits one
Kafka transaction per checkpoint, and after a crash it can abort a half-written commit and redo it:
a `read_committed` reader sees the redone records once, a `read_uncommitted` reader sees the aborted
copy too. So between two Pravaha queries connected by a topic, the guarantee is exactly once end to
end — each side's checkpoints, and `read_committed` between them.

The other side of `read_committed`: it waits behind a transaction that is still **open**. A producer
that hangs mid-transaction holds every later record of that partition back until
`transaction.timeout.ms` aborts it, which shows as lag.

## Formats

### `format: json` — a row per record

The value is a JSON object whose members are the row's columns, **matched to the declared schema by
name** (exactly, then ignoring case). A member the schema does not name is ignored; a column the value
does not carry is null, and refused if the column is not nullable. Every row is an insertion, weight
`+1`. **The record's key is not read** — put every column in the value.

```text
key:   {"order_id":90114}
value: {"order_id":90114,"customer_id":"c42","region":"EU","amount":250000,"status":"OPEN","event_time":"2026-09-19T10:00:00Z"}
```

**A tombstone** — a key with a null value — says a key was deleted without saying what row it held, so
there is nothing to retract. It is refused: a dead letter, or PRV-5105 without a dead-letter queue.
`tombstone: skip` steps over tombstones and reads an **upsert topic** (such as `kafka-sink`'s default
mode) as insertions only. Understand what that means: every value a key ever had arrives as a new
`+1` row, and a deleted key is never withdrawn. When a downstream query must see retractions, write the
topic with `kafka-sink`'s `mode: changelog` and read it with `format: changelog`.

### `format: changelog` — kafka-sink's changelog, weights and all

The value is the envelope [`kafka-sink`](/help/topics/sink-kafka) writes in `mode: changelog`:

```text
value: {"op":"insert","weight":1,"row":{"order_id":90114,"customer_id":"c42","region":"EU","amount":250000,"status":"OPEN","event_time":"2026-09-19T10:00:00Z"}}
value: {"op":"delete","weight":-1,"row":{"order_id":90114,"customer_id":"c42","region":"EU","amount":250000,"status":"OPEN","event_time":"2026-09-19T10:00:00Z"}}
```

Each `row` is decoded as in `json`, and the row carries the envelope's **weight**. `weight` is required,
a whole number, never zero; `op`, when present, must be `insert` or `delete` and agree with the
weight's sign. A value with no `row` member is not a changelog record, and is refused. In this format
the source declares that it emits deletes and before-images — an update in the upstream view arrives
as the old row at `−1` and the new row at `+1` — so every operator downstream subtracts as it should
([Z-set weights](/help/topics/zset-weights)).

### How values are read

The inverse of how `kafka-sink` writes them:

| Type | JSON accepted |
|---|---|
| `BOOLEAN` | `true` / `false` |
| `INT8` … `INT64` | a whole number within the type's range — a fraction or an out-of-range value is refused |
| `FLOAT32`, `FLOAT64` | a number, or the strings `"NaN"`, `"Infinity"`, `"-Infinity"` |
| `DECIMAL(p,s)` | a number or a numeric string, read exactly; more than `s` places or `p` digits is **refused, not rounded** |
| `STRING` | a JSON string, and nothing else |
| `BYTES` | base64 |
| `DATE` / `TIME` | `"2026-09-19"` / `"10:15:30.5"` |
| `TIMESTAMP` | an ISO-8601 instant (`"2026-09-19T10:00:00Z"`) or date-time with an offset, or a whole number of **epoch milliseconds** |
| `null` | null — refused for a column not marked `?` |

A record that does not fit — not JSON, not an object, a string in an `INT64` column, a missing
`NOT NULL` column, anything after the object — is never given a guessed value.

### `format: avro` — Avro's binary encoding, read here

**No `org.apache.avro` on the classpath.** Avro's binary encoding is small and frozen — zig-zag
varints, little-endian floats, a length before bytes and strings, blocks for arrays and maps, an index
before a union's branch, and a record's fields in written order with no names and no tags — so this
plugin reads it directly, the way `postgres-cdc` reads PostgreSQL's replication protocol. The writer
schema is Avro JSON, parsed with the Jackson already on the classpath.

Because the encoding carries no names, **the writer schema must be exactly the one the records were
written with**. It comes from one of two places, and naming both (or neither) is PRV-5100:

```yaml
pravaha:
  sources:
    orders:
      plugin: kafka
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: orders
        schema: "order_id:INT64,customer:STRING,amount:DECIMAL(12,2),placed_at:TIMESTAMP"
        format: avro
        schema.file: /opt/pravaha/conf/schemas/orders.avsc
```

What a column accepts, and nothing else:

| Column | Avro |
|---|---|
| `BOOLEAN` | `boolean` |
| `INT8` … `INT64` | `int`, `long` — out of the column's range is **refused, not truncated** |
| `FLOAT32` | `float` |
| `FLOAT64` | `float`, `double` |
| `DECIMAL(p,s)` | `bytes` or `fixed` with `logicalType: decimal` — more than `s` places is **refused, not rounded** |
| `STRING` | `string`, or an `enum`'s symbol |
| `BYTES` | `bytes`, `fixed` |
| `DATE` | `int` with `logicalType: date` |
| `TIME` | `int`/`time-millis`, `long`/`time-micros` |
| `TIMESTAMP` | `long` with `timestamp-millis` or `timestamp-micros` |

A plain `int` is **not** read as a `DATE`, nor a plain `long` as a `TIMESTAMP`: the schema either says
what a number means or it does not, and guessing is how a column silently becomes 1970.
`local-timestamp-millis` and `-micros` carry no zone, so they are refused rather than assumed to be
UTC. A `["null", T]` union reads NULL or its branch — and a NULL in a column not marked `?` is refused
per record, as in every other format.

**Fields and columns are matched by name** (exactly, then ignoring case). A field no column names is
skipped whole, records, arrays and maps included. **A nested record's field is a column by its path
joined with underscores**: column `shipping_city` reads field `city` of record field `shipping`, at
any depth, and through a `["null", record]` union, whose `null` makes every column under it NULL. A
record itself fills no column. Two fields that match one column — a top-level `shipping_city` and
`shipping.city` — are PRV-5108, never chosen between. A column no field carries, or a field whose type
cannot become its column, is PRV-5108 **when the query registers** — not a stream of dead letters at
three in the morning. Every row is an insertion, `+1`; Avro carries no weights, so retractions need
`format: changelog`.

### Schema evolution: a reader schema

Producers change their schemas. With `schema.reader.file` the binding reads every writer schema *as*
the reader schema, by the resolution rules of the Avro specification, so a producer can move on under
a binding that does not change:

| Writer and reader | Read as |
|---|---|
| a field the reader does not have | skipped |
| a reader field the writer does not have | its `default` — and PRV-5108 if it has none |
| a reader field whose `aliases` name the writer's field | the writer's field (a rename) |
| `int` → `long`, `float`, `double`; `long` → `float`, `double`; `float` → `double`; `string` ↔ `bytes` | promoted |
| records, enums, fixeds with the same unqualified name, or the writer's name in the reader type's `aliases` | resolved field by field |
| an enum symbol the reader lacks | the reader enum's `default` symbol — PRV-5108 if it has none |
| a writer union | each branch against the reader; a branch that resolves to nothing is PRV-5108 |
| a reader union | its first branch that matches the writer |

Everything the specification calls an error is refused **when the two schemas are compiled**, not when
a record happens to reach it: a `schema.file` that does not resolve is PRV-5108 at registration, and a
registry id that does not resolve is a dead letter naming the id (and is not fetched again). A logical
type must be the same on both sides, and a decimal's precision and scale too: the specification falls
back to the underlying type when they differ, and a `DECIMAL` read as raw bytes is exactly what this
refusal exists to prevent. Without `schema.reader.file`, each writer schema is read as itself.

```yaml
        format: avro
        schema.registry.url: https://registry.internal:8081
        schema.reader.file: /opt/pravaha/conf/schemas/orders-reader.avsc
```

### `format: protobuf` — one message of a descriptor set

Protobuf's encoding carries field *numbers*, not names, so reading it needs the schema as a
`FileDescriptorSet` — what `protoc` writes:

```bash
protoc --include_imports --descriptor_set_out=/opt/pravaha/conf/schemas/orders.desc orders.proto
```

```yaml
pravaha:
  sources:
    orders:
      plugin: kafka
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: orders
        schema: "order_id:INT64,customer:STRING,amount:DECIMAL(12,2),placed_at:TIMESTAMP"
        format: protobuf
        schema.descriptor: /opt/pravaha/conf/schemas/orders.desc
        schema.message: acme.orders.Order
```

No generated classes and no `protoc` at run time: the message is read with `DynamicMessage`. A
`google/protobuf/*.proto` import the descriptor set left out is taken from the runtime's own copy;
any other missing import is refused, naming it and `--include_imports`.

| Column | Protobuf |
|---|---|
| `BOOLEAN` | `bool` |
| `INT8` … `INT64` | any integer type; `uint32`/`fixed32` widened, a `uint64` past the signed range refused |
| `FLOAT32` / `FLOAT64` | `float` / `float`, `double` |
| `STRING` | `string`, or an `enum`'s symbol |
| `BYTES` | `bytes` |
| `DECIMAL(p,s)` | a `string` holding the number exactly — protobuf has no decimal, and a `double` would not be one |
| `TIMESTAMP` | `google.protobuf.Timestamp`, or an ISO-8601 `string` |
| `DATE` / `TIME` | an ISO-8601 `string` (`"2026-09-19"`, `"10:15:30.5"`) |

A `repeated` field, a map, and any other message type map to no column: name one and the binding is
refused (PRV-5108).

!!! warning "proto3 defaults are not SQL NULL"
    A proto3 scalar without `optional` **has no presence on the wire**: never set and set to `0`, `""`
    or `false` are the same bytes — none. Such a field fills its column with the type's default and
    **never** with NULL, even when the column is declared `?`. Fields that do carry presence — proto3
    `optional`, any message field including `google.protobuf.Timestamp`, and proto2 `optional` — read
    as NULL when they are absent. If "unknown" must be tellable from "zero", declare the field
    `optional` or wrap it.

A field the descriptor declares that arrives with **another wire type** — an `int64` sent
length-delimited, the producer's schema having drifted — makes the record undecodable, naming the field
and both types; it goes to the dead-letter queue (or stops the source without one). Before PBDRIFT-1
it was read as the column's default, `0`. A field number the descriptor does not declare is skipped,
which is protobuf's own rule for a newer producer.

### A schema registry, over its REST API

A registry-aware producer writes each value as **one `0x00` byte, a four-byte big-endian schema id,
then the payload**. Set `schema.registry.url` and the source reads that prefix, fetches the schema of
that id once with `GET <url>/schemas/ids/{id}`, maps it to the columns, and keeps it — an id's meaning
never changes, so a topic with one schema costs one request per source, however many records arrive.

```yaml
        format: avro
        schema.registry.url: https://registry.internal:8081
        schema.registry.user: pravaha
        schema.registry.password: "${REGISTRY_PASSWORD}"
```

**No Confluent client library** (it is under the Confluent Community License and is not on Maven
Central): just the JDK's HTTP client and the documented request. So **Karapace** works, and so does
**Apicurio** through its Confluent compatibility endpoint — give the URL its path prefix and nothing
else changes. TLS for an `https` registry is the same `tls.*` as the brokers'; basic auth or a bearer
token is `schema.registry.user`/`password` or `schema.registry.token`.

What each failure does, deliberately differently:

| | |
|---|---|
| A value without the `0x00` prefix, while a registry is configured | that record is a dead letter saying so |
| A value **with** the prefix while none is configured | the refusal names `schema.registry.url` |
| A schema the registry serves that cannot be mapped to the columns | every record carrying that id is a dead letter naming the id; the mismatch is remembered, not re-fetched |
| The registry unreachable, refusing the credentials, or answering nonsense | PRV-5109, and the reader **stops** — a registry being down is not a record's fault, and dead-lettering good records would lose them |

For `format: protobuf` with `schema.registry.url` and **no** `schema.descriptor`, the registry
describes each record. The schema of the record's id is asked for with `?format=serialized`, which
Confluent Schema Registry answers with a base64 `FileDescriptorProto` rather than `.proto` source, so
nothing here parses `.proto` and no `protoc` runs; each of its `references` (an import) is fetched the
same way from `GET <url>/subjects/{subject}/versions/{version}`. The record's Confluent message-index
array then picks the message — the first index into the file's messages, each next one into the
nested messages of the one before — and it is mapped to the columns once per id and message, with the
same rules as a supplied descriptor. A registry that does not honour `format=serialized` answers with
`.proto` source; that is PRV-5109 by name, and such a topic is read with `schema.descriptor`. With a
`schema.descriptor` as well, the registry is not consulted: the prefix and the message indexes are
read past and the message comes from the descriptor.

```yaml
        format: protobuf
        schema.registry.url: https://registry.internal:8081
        schema.message: acme.orders.Order   # optional: refuse any other message
```

## Dead letters

A record the source cannot decode goes to the [dead-letter queue](/help/topics/dead-letters) when the
node has one (`pravaha.dlq.directory`): its value's bytes, the reason, and where it was, written
`topic/partition@offset` — `orders/3@1041` — and the source reads on. Without a queue the source
**stops** with PRV-5105 and the same text. Its position stays before the record, so a restart meets
the record again and refuses it again, rather than skipping a record nobody set aside.

## Event time

On a server, declare the stream's `event-time` and the node hands it to the source as `event.time`:
each row's event time is then that column's value, which is what the watermark and every window run
on. You write the column once, in `pravaha.streams.<name>`.

With no `event.time` at all, each row is stamped with its record's **Kafka timestamp** — the producer's
create time or the broker's append time, whichever the topic is configured for — and a record with no
timestamp gets the epoch. That timestamp is not a column: nothing in a query can name it. To window on
it, have the producer write it into the value as well.

## A complete binding: JSON orders from a producer

A service produces one JSON object per order to the topic `orders`, six partitions, keyed by
`order_id`:

```yaml
pravaha:
  checkpoint:
    directory: /opt/pravaha/data/checkpoints     # the offsets live here: required for exactly once
    interval: 1m
  dlq:
    directory: /opt/pravaha/data/dlq             # a malformed record is set aside, not fatal
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time                    # handed to the source as event.time
      out-of-orderness: 30s
  sources:
    orders:
      plugin: kafka
      options:
        bootstrap.servers: "kafka-1.internal:9093,kafka-2.internal:9093"
        topic: orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        format: json
        tombstone: reject
        start.from: earliest
        isolation.level: read_committed
        monitoring.group: pravaha-orders
        buffer.records: "10000"
        start.timeout: 30s
        lag.warn.records: "100000"
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-512
        tls.ca: /opt/pravaha/conf/tls/kafka-ca.pem
        kafka.fetch.max.bytes: "52428800"
```

The node logs the binding at startup, with the event-time column it added:

```text
sources bound: [orders <- kafka[bootstrap.servers, topic, schema, format, tombstone, start.from, isolation.level, monitoring.group, buffer.records, start.timeout, lag.warn.records, user, password, sasl.mechanism, tls.ca, kafka.fetch.max.bytes, event.time]]
```

(Option names only — never their values.) Revenue per region every five minutes, closed by the orders' own times:

```sql
CREATE CONTINUOUS QUERY region_revenue_5m
    KEYED BY (region, window_end)
AS
SELECT region, window_start, window_end, SUM(amount) AS revenue, COUNT(*) AS orders
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end;
```

<!-- sql: read -->
```sql
SELECT window_end, revenue, orders FROM region_revenue_5m WHERE region = 'EU'
```

The consumers need `Describe` and `Read` on the topic, and `Read` on `monitoring.group` if one is set.

## A round trip: kafka-sink's changelog, a topic, a kafka source

One query's answer — including the rows it **withdraws** — becomes another query's input, through a
topic, exactly once. The first half is the open-orders view from
[the postgres-cdc source](/help/topics/source-postgres-cdc): an order leaves it when it ships.

### 1. Write the changes, with their weights

Create the topic with a delete policy and a retention longer than any outage (a changelog must not be
compacted: compaction would keep one record per key and throw away the history). Then, on the node
that owns `orders`:

```yaml
pravaha:
  sinks:
    open_order_changes:
      plugin: kafka-sink
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: open-orders-changes
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        mode: changelog
        key.columns: order_id                  # an order's changes share a partition, in order
        transactional: "true"
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-512
        tls.ca: /opt/pravaha/conf/tls/kafka-ca.pem
```

```sql
CREATE CONTINUOUS QUERY open_orders_feed
    KEYED BY (order_id)
    WRITING TO open_order_changes
AS
SELECT order_id, customer_id, region, amount, status, event_time
FROM orders
WHERE status = 'OPEN';
```

An order is inserted, then shipped. At each checkpoint `kafka-sink` commits that checkpoint's changes
in one Kafka transaction:

```text
{"order_id":90114} | {"op":"insert","weight":1,"row":{"order_id":90114,"customer_id":"c42","region":"EU","amount":250000,"status":"OPEN","event_time":"2026-09-19T10:00:00Z"}}
{"order_id":90114} | {"op":"delete","weight":-1,"row":{"order_id":90114,"customer_id":"c42","region":"EU","amount":250000,"status":"OPEN","event_time":"2026-09-19T10:00:00Z"}}
```

(Illustrative records, as a console consumer prints key and value.)

### 2. Read them back, with their weights

On the same node or another one:

```yaml
pravaha:
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
  streams:
    open_order_changes:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 30s
  sources:
    open_order_changes:
      plugin: kafka
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: open-orders-changes
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        format: changelog
        isolation.level: read_committed
        monitoring.group: pravaha-open-order-changes
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-512
        tls.ca: /opt/pravaha/conf/tls/kafka-ca.pem
```

```sql
CREATE CONTINUOUS QUERY open_orders_eu
    KEYED BY (order_id)
AS
SELECT order_id, customer_id, amount
FROM open_order_changes
WHERE region = 'EU';
```

The `+1` puts order 90114 in the view; the `−1` takes it out again, exactly as the upstream view did.
Aggregate the rows at read time:

<!-- sql: read -->
```sql
SELECT COUNT(*) AS open_orders, SUM(amount) AS open_value FROM open_orders_eu
```

**What each side guarantees.** The first node's sink is exactly once to a `read_committed` reader; the
second node's source keeps its offsets in its own checkpoint and reads `read_committed`. Together:
every change reaches `open_orders_eu` exactly once. The second view trails the first by up to one of
the first node's checkpoint intervals, because that is when `kafka-sink` commits.

**What the second node refuses.** `open_order_changes` now emits deletes, so a query over it that
passes a retraction on — a filter like the one above, a join — revises its answer, and pointed at an
append-only sink (a `filesystem` sink, `jdbc-sink` in `mode: append`) it is refused at registration
with [PRV-2041](/help/codes/PRV-2041). A file would write the `−1` as one more row. Point it at a sink
that takes a retraction, or keep it as a view. (Read with `format: json` instead, these envelopes
would not decode at all: their columns are inside `row`, not at the top of the value.)

## Retention and recreated topics: PRV-5106

A restore needs the records after the checkpoint's offsets. Two things take them away, and in both
the source **refuses** rather than reading on from wherever the log now starts:

- **Retention deleted them.** A node down — or a query paused — for longer than the topic's
  `retention.ms` comes back to a partition whose first offset is past the checkpoint's. Those records
  are gone for every reader. Resuming at the log's new start would lose them silently, so opening the
  reader is refused with [PRV-5106](/help/codes/PRV-5106), naming the partition and how many records
  were lost. The same refusal comes mid-stream if a reader falls so far behind that retention
  overtakes it.
- **The topic was deleted and recreated.** The checkpoint's offset is then **past the end** of the
  partition, and the same offsets now mean other records. Also PRV-5106.
- **The topic was deleted, and nothing recreated it.** A running reader asks the brokers for the topic
  while its partition is quiet; once they have not known it for `topic.missing.timeout` (30 seconds by
  default) it stops with [PRV-5130](/help/codes/PRV-5130), the feed stops and node health is
  `DEGRADED`. Until TOPICGONE-1 the consumer only logged "unknown topic or partition", and the query
  stayed `RUNNING` with health `UP` for as long as the topic was gone.

The recovery is the same for both, and means starting without the missing records:

1. Raise the topic's retention beyond the longest outage you plan for.
2. `DROP CONTINUOUS QUERY` the registration, so no restore asks for the lost offsets.
3. Register it again. It starts from `start.from`, with no checkpoint.

A checkpoint that holds a position for another topic or partition — the binding's `topic` changed
under an existing checkpoint — is [PRV-5104](/help/codes/PRV-5104), refused rather than seeked to;
register afresh.

Compaction removes records too, without an error: on a compacted topic a restore reads what compaction
left, which for `format: json` is the latest value of each key.

## Health and lag

The source reports its health to the node (the **Plugins** and **Operations** screens, and the
`plugins` of `GET /api/v1/status`):

```text
topic orders, 6 partitions read, lag in records: p0 0, p1 12, p2 3, p3 0, p4 7, p5 0
```

Lag is each partition's end offset at the broker minus what the engine has been handed. The health is:

| State | When |
|---|---|
| `HEALTHY` | the brokers answer and every partition is within `lag.warn.records` |
| `DEGRADED` | a partition is `lag.warn.records` or more behind — the engine is not keeping up, or is paused behind a slow sink — or committing to `monitoring.group` failed |
| `UNHEALTHY` | the brokers did not answer within two seconds, or a reader has stopped (its error is the detail) |

Alert from Kafka's side too, with `monitoring.group`: a node that is down cannot report its own lag.

## Security

TLS is the shared `tls.*` options every connector reads — `tls.enabled`, `tls.ca` or `tls.truststore`,
`tls.certificate` and `tls.key` or `tls.keystore` for a client certificate, `tls.verify-hostname`
(default `true`) — mapped onto Kafka's `ssl.*` properties by the same code as `kafka-sink`'s.
Credentials are SASL: `user` and `password`, with `sasl.mechanism`. `PLAIN` without TLS is refused; the
SCRAM mechanisms are allowed either way. `security.protocol` follows from the two. See
[connector security](/help/topics/connector-security).

## Not supported

| Not built | Instead |
|---|---|
| **An array or a map as a column** | Flatten it in the producer; a nested *record*'s fields are columns |
| **A registry that serves Protobuf schemas only as `.proto` source** | Supply the descriptor set with `schema.descriptor`; Confluent Schema Registry's `?format=serialized` is what the registry path reads |
| **Writing** Avro or Protobuf | `kafka-sink` writes JSON. See [the Kafka sink](/help/topics/sink-kafka) |
| **The record key** as data | Only the value is read. Put every column in the value, as `kafka-sink` does |
| **Tombstones as deletes** | `format: changelog`, or `tombstone: skip` to read an upsert topic as insertions |
| **Resuming from a consumer group** | The checkpoint is the position; `monitoring.group` only reports |
| **Several topics, or a pattern**, in one binding | One binding per topic, one stream each |
| **Sharing one reader between queries** | Each registration has its own consumers |

## Troubleshooting

| Code | When | What to do |
|---|---|---|
| [PRV-5100](/help/codes/PRV-5100) | at configuration | An option missing or malformed, a refused or misspelled `kafka.*` property, `event.time` naming no `TIMESTAMP` column, `PLAIN` without TLS, half a credential. The message names it |
| [PRV-5101](/help/codes/PRV-5101) | at registration | The brokers did not answer within `start.timeout`, the topic does not exist (the source never creates it), or the credentials or ACLs refused `Describe` or `Read` |
| [PRV-5104](/help/codes/PRV-5104) | at restore | The checkpoint holds a position this source did not write, or one for another topic or partition. Register afresh |
| [PRV-5105](/help/codes/PRV-5105) | while running | A record does not fit the schema (or is a tombstone in `format: json`) and there is no dead-letter queue. The message names it as `topic/partition@offset`. Set `pravaha.dlq.directory`, fix the producer, or for tombstones set `tombstone: skip` |
| [PRV-5106](/help/codes/PRV-5106) | at restore, or while running | The offset to resume from is gone — retention, or a recreated topic. Recover as above |
| [PRV-5107](/help/codes/PRV-5107) | while running | Fetching failed in a way retrying will not fix: an ACL revoked, the topic deleted |
| [PRV-5108](/help/codes/PRV-5108) | at registration | With `format: avro` or `protobuf`: the writer schema cannot become rows of this stream — a column with no field, a field whose type cannot fill its column, two nested paths matching one column, a writer schema that does not resolve against `schema.reader.file`, a `schema.file` that is not an Avro schema, a `schema.descriptor` that is not a descriptor set or has no such message. A registry's schema is checked when its first record arrives instead, and a mismatch there is a dead letter |
| [PRV-5109](/help/codes/PRV-5109) | while running | The schema registry could not be read: unreachable after three attempts, the credentials refused, no schema with that id, or an answer that is not `GET /schemas/ids/{id}`'s documented shape. The reader stops and resumes from its checkpoint once the registry is back |
| [PRV-5130](/help/codes/PRV-5130) | while running | The topic was deleted: the brokers have not known it for `topic.missing.timeout`. The feed stops (FEED-1) and node health is `DEGRADED`. Recreate the topic and register the query again |
| [PRV-2041](/help/codes/PRV-2041) | at registration | A query over a `format: changelog` stream pointed at an append-only sink |

A source that seems stuck with nothing refused is usually `read_committed` waiting behind a producer's
open transaction: the partition cannot pass it until it commits or `transaction.timeout.ms` aborts it.

## Pitfalls

!!! danger "Pitfall: retention shorter than an outage"
    The checkpoint's offsets are only as good as the records behind them. A node down longer than the
    topic's `retention.ms` restarts to PRV-5106, and the records in between are lost to every reader.
    Size retention for your longest outage, not your average one.

!!! warning "Pitfall: no checkpoint directory"
    Without `pravaha.checkpoint.directory` nothing records the offsets, so every restart starts again at
    `start.from`: with `earliest`, the whole retained topic again; with `latest`, a gap.

!!! warning "Pitfall: reading an upsert topic with `tombstone: skip`"
    Every value a key ever had arrives as a new `+1`, and a deleted key is never withdrawn — a count over
    it counts every version of every key. Read a changelog when the answer must go down.

!!! warning "Pitfall: `read_uncommitted` on a transactional topic"
    It delivers the records of transactions that were aborted — including a `kafka-sink` commit a crash
    aborted and redid. In `format: changelog` that is a doubled weight, and a wrong total for ever.

!!! note "Resetting the monitoring group does nothing"
    `kafka-consumer-groups --reset-offsets` on `monitoring.group` changes what the dashboard shows and
    nothing else. To start a query again from the beginning, drop and re-register it with
    `start.from: earliest`.

## Where next

- [The Kafka sink](/help/topics/sink-kafka) — the changelog envelope this source reads, and its staging topic
- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — where the offsets live
- [Z-set weights](/help/topics/zset-weights) — what `+1` and `−1` do in every operator
- [Dead letters](/help/topics/dead-letters) — where an undecodable record goes
- [Sources overview](/help/topics/sources-overview) — every source side by side
