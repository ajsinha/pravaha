---
title: Sinks — how a query writes to one, and every sink
slug: sinks-overview
category: sinks
order: 10
icon: box-arrow-right
summary: "Bind a sink under pravaha.sinks, name it when you register, and every commit of the view is written there too — checked for shape and for retractions before anything opens. One page per shipped sink."
badge: SINKS
audience: Engineers
keywords: [sink, WRITING TO, "--sink", pravaha.sinks, shape check, emit mode, append, upsert, retract, detached, /api/v1/sinks, kafka, delta]
guide: operations#one-engine-and-what-the-server-still-lacks
related: [delivery-guarantees, sink-jdbc, sink-kafka, sink-delta, sink-iceberg, sink-aerospike, sink-filesystem, create-continuous-query, zset-weights]
---

A continuous query always maintains its **view** — the answer clients read and subscribe to. A
**sink** is somewhere *else* the same answer is written: a file, a relational table, an Aerospike
set, a Kafka topic, a Delta Lake table. You declare the sink once, in the node's configuration, and a registration **names** it. From
then on every commit of that query's view — insertions, and retractions as rows with a negative
weight — is also handed to the sink, on the query's own commit.

Nothing about the view changes when a sink is attached. Clients still read it, subscribers still
receive every commit, and if the sink later fails, the view carries on without it.

## Every sink {#every-sink}

| Plugin | Page |
|---|---|
| `aerospike-sink` | [The Aerospike sink](/help/topics/sink-aerospike) |
| `delta-sink` | [The Delta sink — a lakehouse table that holds the answer](/help/topics/sink-delta) |
| `filesystem` | [The filesystem sink](/help/topics/sink-filesystem) |
| `iceberg-sink` | [The Iceberg sink — an Apache Iceberg table that holds the answer](/help/topics/sink-iceberg) |
| `jdbc-sink` | [The jdbc sink — a transactional, exactly-once table](/help/topics/sink-jdbc) |
| `kafka-sink` | [The Kafka sink — a topic that holds the answer](/help/topics/sink-kafka) |

What each can promise, from at-least-once to exactly-once, side by side:
[delivery guarantees](/help/topics/delivery-guarantees).

## The three steps

1. **Bind** the sink under `pravaha.sinks.<name>`, with a `plugin:` and its `options:` — the same
   shape a source binding has under `pravaha.sources.<stream>`.
2. **Register** a query that names the sink: `WRITING TO <name>` in SQL, `--sink <name>` on the
   CLI, or the `sink` argument of either SDK's `register`.
3. **Check** what the engine promised: the registration is logged with the delivery guarantee it
   will actually give (see [delivery guarantees](/help/topics/delivery-guarantees)), and the
   console's **Catalog → Sinks** tab shows the sink, what it accepts, and who writes to it.

A binding on its own does nothing: nothing is opened until a registration names it.

## The binding

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
        event.time: event_time
        skip.header: "true"
        follow: "true"
  sinks:
    large_payments:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/outgoing/large_payments.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64"
        append: "true"
```

| Key | What it is |
|---|---|
| `pravaha.sinks.<name>` | The binding's name. It is what a registration names, what `GET /api/v1/sinks` lists, and (for `jdbc-sink`, `kafka-sink` and `delta-sink`) the default transaction id |
| `plugin` | The plugin's own name: `filesystem`, `jdbc-sink`, `aerospike-sink`, `kafka-sink`, `delta-sink` and `iceberg-sink` ship today |
| `options` | Passed to the plugin untouched. **Nested under `options:`** — a key written one level too high is not read |
| `options.schema` | The row shape the sink writes, `name:TYPE,...`. Every shipped sink requires one, and the registration is checked against it |

Sinks are bound before the registry recovers its journal at startup, so a restart re-attaches each
journalled registration to its sink. A journalled registration whose sink is no longer bound is
refused by name in the recovery report, rather than recovered writing to nothing.

## Naming the sink when you register

In SQL, over any Flight SQL client, the CLI's `query`, the console's workbench, or the embedded
engine:

```sql
CREATE CONTINUOUS QUERY big_txn_feed
    KEYED BY (txn_id)
    WRITING TO large_payments
AS
SELECT txn_id, user_id, amount
FROM txn
WHERE amount > 1000;
```

It answers with one row — the name, its state, the fingerprint of the computation, and the sink:

```text
name          state    fingerprint        sink
big_txn_feed  RUNNING  9c41e0b7d2a35f18   large_payments
```

(The fingerprint above is illustrative; yours is computed from the plan.) The same registration from
the CLI, where the key is given as output-column ordinals:

```bash
pravaha register --name big_txn_feed \
  --sql "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000" \
  --keys 0 --sink large_payments
```

```text
registered big_txn_feed  state=RUNNING  fingerprint=9c41e0b7d2a35f18  sink=large_payments
a query with the same fingerprint is the same computation, shared
```

And from Python:

```python
client.register("big_txn_feed",
                "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000",
                keys=[0], sink="large_payments")
```

`INTO large_payments` is accepted as a synonym for `WRITING TO large_payments`; `INSERT INTO` is
not — it is refused with PRV-2020, because Pravaha answers questions and sinks write the results.

The node's log says, at `INFO` from `QueryRegistry`, what this pair will actually deliver:

```text
query 'big_txn_feed' writes to sink 'large_payments', at-least-once: the sink appends and is not transactional, so a restart delivers again what was written after the last checkpoint; a view commit carries no sequence to deduplicate the repeat on
```

## Two checks, before anything opens

A registration that names a sink is checked twice, at registration, before the sink is opened. Both
refusals exist because the alternative is a sink full of plausible nonsense and no error.

### The shape check — PRV-8010

A sink reads each row through the schema in its binding. The engine hands it rows laid out as the
query produced them. So the query's `SELECT` list must match the sink's `schema` **column for
column: the same order, the same names (ignoring case) and the same types**. Nullability is not
compared — a sink writes a null wherever the query produces one.

A keyed sink (`jdbc-sink`, `kafka-sink` and `delta-sink` in upsert mode, `aerospike-sink`) must also key its records
by **exactly the view's key**. On fewer columns, distinct rows collapse onto one record and retracting one
deletes the other; on more, a changed row leaves its old record behind.

A keyed sink in upsert mode is handed **how the view's answer changed** at each commit, not the
changelog: the row that left a key (`-1`) and the row that entered it (`+1`). A keyed view keeps
every distinct row of a key and shows the newest; retracting the row it shows brings the one
behind it back. The changelog of that commit is the retraction alone, and an upsert sink fed it
deleted a key the view still showed (SINKKEYROWS-1). A sink in changelog mode (`mode: changelog`)
is still handed the changelog, weights as applied. A row that retention ages out of the view is
not deleted from either: it was not withdrawn, and the sink is where it is kept.

When a commit replaces a key's row, the upsert sink is handed the new row alone: the insert
overwrites the key's record, and writing the old row's withdrawal first would delete the record on
the way — a tombstone every consumer of a Kafka topic reads, a `DELETE` a reader of a table can
see (SINKKEYROWS-2). A key that leaves the answer and does not come back in the same commit is
still deleted.

The query below plans perfectly — and is refused at registration, because the columns are in the
wrong order for `large_payments` (`txn_id, user_id, amount`):

```sql
-- Plans. Registering it with WRITING TO large_payments is refused with PRV-8010:
-- the sink would read user_id's bytes as txn_id, and nothing would fail.
SELECT user_id, txn_id, amount FROM txn WHERE amount > 1000
```

```text
PRV-8010  sink 'large_payments' is configured for rows (txn_id:INT64, user_id:STRING, amount:INT64), and this query produces (user_id:STRING, txn_id:INT64, amount:INT64). The sink reads each row through its own schema, so every column would be read from the wrong place and nothing would fail. Make the query's SELECT list match the sink's schema in order, name and type, or change the binding's schema.
```

Names matter as much as order. An aggregate with no alias is named `EXPR$2` by the planner, so
`SUM(amount)` never matches a sink column called `total` — write `SUM(amount) AS total`. Types matter
too: `COUNT` and every `SUM` of an integer are `INT64`, even over an `INT32` column, while `MIN` and
`MAX` keep the column's own type.

### The retraction check — PRV-2041

Some queries only ever **append**: over streams whose sources only append, a filter, a projection,
a join, a tumbling or hopping window with no allowed lateness — each output row is final the moment
it is emitted. Others **revise**
their answer, and the engine works out which from the plan:

- **an aggregate with no window** — `SELECT COUNT(*) AS payments, SUM(amount) AS total FROM txn`
  has one result, and every new row replaces it: the old result is retracted and the new one
  inserted;
- **a windowed aggregate that allows lateness** re-emits a window it has already reported, as a
  retraction of the old answer and the corrected one. (Allowed lateness is a property of a stream:
  `pravaha.streams.<name>.allowed-lateness`, or `allowedLateness` on `POST /api/v1/streams`. It is
  zero unless declared, and then no window revises.)
- **a stream whose source emits deletes** — [postgres-cdc](/help/topics/source-postgres-cdc), Delta,
  the [Kafka source](/help/topics/source-kafka) with `format: changelog` — sends retractions through a
  plain filter or a join too, so any query over it that passes rows on revises. The check asks the
  stream's bound source whether it deletes. (A windowed aggregate over such a stream, without
  lateness, still fires each window once and is append-only.)

A revision arrives as a retraction (weight `-1`) and an insertion (`+1`).

A sink declares what it accepts:

| Sink | Accepts | Takes a revising query |
|---|---|---|
| `filesystem` | `APPEND` | no |
| `jdbc-sink`, `mode: append` | `APPEND` | no |
| `jdbc-sink`, `mode: upsert` (default) | `UPSERT`, `RETRACT` | yes |
| `aerospike-sink` | `UPSERT`, `RETRACT` | yes |
| `kafka-sink`, `mode: upsert` (default) | `UPSERT`, `RETRACT` — a retraction is a tombstone | yes |
| `kafka-sink`, `mode: changelog` | `APPEND`, `RETRACT` — a retraction is an `"op":"delete"` record | yes |
| `delta-sink`, `mode: upsert` (default) | `UPSERT`, `RETRACT` — a retraction rewrites the Delta file without the row | yes |
| `delta-sink`, `mode: changelog` | `APPEND`, `RETRACT` — a retraction is a row with `_op` `delete` | yes |
| `iceberg-sink`, `mode: upsert` (default) | `UPSERT`, `RETRACT` — a retraction is an equality delete of the key | yes |
| `iceberg-sink`, `mode: changelog` | `APPEND`, `RETRACT` — a retraction is a row with `_op` `delete` | yes |

A revising query pointed at an append-only sink is refused with PRV-2041 at registration. This
query plans, and registering it `WRITING TO large_payments` is refused:

```sql
-- Plans. WRITING TO an append-only sink it is refused with PRV-2041: its one row is replaced on
-- every payment, and a file cannot take the retraction of the old one.
SELECT COUNT(*) AS payments, SUM(amount) AS total FROM txn
```

```text
PRV-2041  sink 'large_payments' accepts [APPEND], but this query needs one of [UPSERT, RETRACT].
  Why: the aggregate over txn revises its answer on every record: each new row makes the previous result wrong, so the previous result has to be withdrawn.
  Fix: point it at a sink that supports keyed upsert (Aerospike, Redis, JDBC, a compacted Kafka topic), or make the query append-only -- a tumbling window with no allowed lateness fires each window once and never revises it.
```

(The message is the engine's; the name after "over" is the aggregate's input as the plan names it.) The fix
text names Redis, which has no shipped sink; a compacted Kafka topic is [`kafka-sink`](/help/topics/sink-kafka)
in upsert mode, and it, `jdbc-sink`, [`delta-sink`](/help/topics/sink-delta) and `aerospike-sink`
are the shipped sinks that take a revising query. A file holding rows that are each correct and a total that is wrong for ever is
the failure this check exists to prevent.

Over a stream fed by postgres-cdc, the same refusal names the stream instead:

```text
  Why: stream 'orders' is read from a source that emits deletes, so a row it delivers can later be withdrawn, and whatever this query built from it -- a join's pair included -- is withdrawn with it.
```

!!! warning "What the check does not see: the filesystem source's `op.column`"
    The filesystem plugin declares that it never deletes, even when `op.column` makes some of its rows
    retractions. A query over such a file is judged append-only, and pointed at an append-only sink its
    retractions are written as ordinary lines. Send it to a sink that takes retractions.

## What the sink receives

- **Whole commits.** Rows arrive per commit of the view — never half a window, never a retraction
  without the insertion that replaces it. The feed commits on its own timer, so a sink trails the
  source by about one commit.
- **Retractions as rows.** An update is a `-1` of the old row and a `+1` of the new one, in the same
  commit. An upsert sink turns the `-1` into a delete of the key and the `+1` into an upsert.
- **Delivery on the query's thread.** A slow sink slows the query that feeds it and nothing else. A
  checkpoint also prepares each transactional sink on that lane, so a sink slow to prepare lengthens
  that query's checkpoint pause; `pravaha.checkpoint.timeout` bounds it.
- **Commit latency includes the sink.** `pravaha_query_commit_latency_seconds_sum` measures from
  applying the changes to the last subscriber **and sink** having them.

## A second name, and its first snapshot

Registering the same computation under a second name — the same fingerprint — shares it: one
computation, one copy of the state. Each name may write to its own sink. A sink attached to a
computation that is **already running** is first sent the view's whole contents — at the query's
next change or checkpoint, not at once — inside its first transaction when it is transactional.

```sql
CREATE CONTINUOUS QUERY big_txn_audit
    KEYED BY (txn_id)
    WRITING TO large_payments_copy
AS
SELECT txn_id, user_id, amount
FROM txn
WHERE amount > 1000;
```

After a restart, a second name whose sink the restored checkpoint recorded is sent only what changed
since that checkpoint.

## When a sink refuses a batch — PRV-8009

A sink that throws on a write is **detached, not retried**. The first refused batch stops that sink
with PRV-8009, logged at `ERROR`; the query, its view and its subscribers carry on. Writing later
batches past a lost one would leave the sink silently missing a change.

What you see:

- the query's page in the console shows the sink's state and its PRV-8009 failure;
- `pravaha queries` marks the `SINK` cell `<name> (detached)` and prints the code and what
  happened under the table, unasked — a query whose sink has stopped looks entirely healthy
  otherwise, which is the reason it is not behind `--verbose`;
- `GET /api/v1/queries/{name}` reports the sink with `attached: false` and the failure, and both
  SDKs' listings carry it too (`sinkState`/`sinkFailure` in Java, `sink_state`/`sink_failure` in
  Python) — in each case with every configured option value that could be a credential struck
  out, which now happens where the failure is recorded rather than at each surface;
- the node log carries the plugin's own reason (for `jdbc-sink`, a PRV-5076 naming the statement the
  database refused).

To start the sink again, **drop and re-register** the query. The new registration sends the view's
whole contents — which, for any kind of sink, repeats what it already held. The delivery guarantee
ends at PRV-8009.

## Seeing the sinks: `GET /api/v1/sinks`

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" http://localhost:18080/api/v1/sinks
```

```json
[
  {
    "name": "large_payments",
    "plugin": "filesystem",
    "fields": [
      {"name": "txn_id", "type": "BIGINT NOT NULL", "nullable": false, "ordinal": 0},
      {"name": "user_id", "type": "VARCHAR NOT NULL", "nullable": false, "ordinal": 1},
      {"name": "amount", "type": "BIGINT NOT NULL", "nullable": false, "ordinal": 2}
    ],
    "keyColumns": [],
    "emitModes": ["APPEND"],
    "acceptsRetractions": false,
    "guarantee": "AT_LEAST_ONCE",
    "writers": ["big_txn_feed"],
    "problem": null
  }
]
```

(The exact `type` text is the engine's SQL rendering of each column.) **A binding's options are
never read to build this answer** — they are where passwords and connection strings live. A sink
appears to a caller the policy lets read its name, and `writers` are only the queries that caller's
own listing shows. When a plugin refuses to describe its configuration, `problem` carries a code and
a pointer to the node log rather than the plugin's text, which could quote the options.

`guarantee` is what **this node** gives the sink, in the registration log's terms: `EXACTLY_ONCE` for
a transactional sink on a node that takes checkpoints, `EFFECTIVELY_ONCE` for an idempotent upsert
(`aerospike-sink`, and `jdbc-sink`, `kafka-sink` or `delta-sink` in upsert mode without checkpoints),
`AT_LEAST_ONCE` otherwise. What a particular registration gets also depends on its source — the
registration's log line is the authority. See [delivery guarantees](/help/topics/delivery-guarantees).

The Python SDK's `client.sinks()` returns the same list; the console's **Catalog → Sinks** tab
(`/catalog?tab=sinks`) renders it, and its register panel offers exactly these sinks.

## Pitfalls

!!! warning "Pitfall: `schema` nested one level too high"
    `pravaha.sinks.x.schema` is not read. The sink's schema is `pravaha.sinks.x.options.schema`,
    and a binding without one is refused when a registration first opens it.

!!! warning "Pitfall: an unaliased aggregate"
    `SELECT user_id, SUM(amount)` produces a column named `EXPR$1`, which matches no sink column.
    Alias every computed column with the sink's column name.

!!! warning "Pitfall: `--keys` that are not the sink's key"
    The CLI's `--keys` defaults to `0`. For a sink keyed by two columns, pass both ordinals
    (`--keys 0,2`), or use `KEYED BY (...)` in SQL, which names them.

!!! note "Any registrant may name any bound sink"
    There is no per-sink authorization yet: a principal allowed to register may name any bound sink,
    and the registration's audit line does not record which. Bind only sinks every registrant may
    write to.

## Where next

- [The filesystem sink](/help/topics/sink-filesystem), [the jdbc sink](/help/topics/sink-jdbc),
  [the Aerospike sink](/help/topics/sink-aerospike), [the Kafka sink](/help/topics/sink-kafka),
  [the Delta sink](/help/topics/sink-delta)
- [Delivery guarantees](/help/topics/delivery-guarantees) — at-least-once, effectively once, exactly
  once, and what checkpoints have to do with it
- [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query) — the whole grammar
- [Z-set weights and retractions](/help/topics/zset-weights) — why a sink receives `-1` rows
