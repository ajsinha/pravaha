---
title: Delivery guarantees — at least once, effectively once, exactly once
slug: delivery-guarantees
category: sinks
order: 50
icon: shield-check
summary: "What reaches a sink after a crash depends on the sink, on checkpoints and on the source. The three guarantees, how each is achieved, and what the node tells you at registration."
badge: SINKS
audience: Engineers
keywords: [exactly once, exactly-once, effectively once, at least once, delivery, guarantee, checkpoint, replay, transactional, idempotent, crash, restart]
guide: operations#one-engine-and-what-the-server-still-lacks
related: [sinks-overview, sink-jdbc, sink-kafka, sink-aerospike, sink-filesystem, checkpoints-recovery, source-kafka, zset-weights]
---

Inside the engine, every row affects the view exactly once: state is checkpointed, and after a crash
the node restores the last checkpoint and replays the source from the offsets recorded in it. The
question this page answers is what that replay does to a **sink** — somewhere outside the engine
that has already been written to.

There are three answers, and Pravaha tells you which one each registration gets, in words, at the
moment you register it.

## The three guarantees

| Guarantee | Means | After a crash, the sink |
|---|---|---|
| **At least once** | nothing is lost; some rows may arrive twice | holds a repeat of what was written after the last checkpoint |
| **Effectively once** | rows may be *written* twice, but a repeated write changes nothing | converges to exactly the view, because each replayed write puts the same value under the same key |
| **Exactly once** | every change reaches the sink once, and a checkpoint's changes become visible together | holds exactly what the restored checkpoint committed, then the replay's changes, once |

## Why a replay repeats anything

1. The engine commits the view — a batch of insertions and retractions — and hands the same batch
   to the sink.
2. A checkpoint records the state of every operator and the source's offsets, at a cut, every
   `pravaha.checkpoint.interval` (one minute by default).
3. The node dies between two checkpoints. Everything the sink received since the last one was
   written, but the engine's memory of having produced it is gone.
4. The restart restores the checkpoint and replays the source from its offsets. The same rows are
   produced again, and handed to the sink again.

What the sink does with that second copy is the guarantee.

## How each sink handles it

| Sink declares | Example | With `pravaha.checkpoint.directory` set | Without it |
|---|---|---|---|
| **transactional** | [`jdbc-sink`](/help/topics/sink-jdbc) and [`kafka-sink`](/help/topics/sink-kafka) (both by default) | **exactly once** (for `kafka-sink`, to a `read_committed` consumer) | no checkpoint to tie a transaction to, so each commit is its own and a restart repeats it: **effectively once** in upsert mode (the default — a repeated upsert rewrites the value already there), at least once in `mode: append` or `mode: changelog` |
| **idempotent upsert** | [`aerospike-sink`](/help/topics/sink-aerospike); `jdbc-sink` or `kafka-sink` with `transactional: false`, `mode: upsert` (Kafka on a compacted topic) | effectively once | effectively once |
| **neither** | [`filesystem`](/help/topics/sink-filesystem); `jdbc-sink` with `transactional: false`, `mode: append`; `kafka-sink` with `transactional: false`, `mode: changelog` | at least once | at least once |

### Exactly once: prepare at the checkpoint, commit when it is durable

What a transactional sink is written between two checkpoints goes into one transaction. At each
checkpoint's cut the transaction is **prepared** and its handle recorded **in the checkpoint**. Once
the checkpoint is safely stored, the transaction is **committed**. On restore, the sink commits what
the restored checkpoint recorded and abandons everything after it — which the replay then writes
again. `jdbc-sink` implements the transaction with a staging table and one database transaction per
checkpoint; `kafka-sink` with a staging topic and one Kafka transaction per checkpoint, because Kafka
has no *prepare* a restarted producer could commit. Their pages have the details.

The cost is latency: a row reaches the sink only once the checkpoint that recorded it is durable, so
the sink trails the view by up to one checkpoint interval.

### Effectively once: the same write, the same result

An upsert keyed by the view's key is idempotent. Replaying it puts the value the record already
holds; replaying a delete deletes what is already gone. No transaction is needed, and the sink is
current within one commit of the view. What it does not give is atomicity *across* records: during a
replay a reader can see some records rewound to an older value and not yet caught up.

### At least once: nothing to deduplicate on

An append-only sink cannot tell a replayed row from a new one: a view commit carries no sequence
number that a replay would repeat. Expect repeats after a restart and deduplicate downstream by the
query's key if they matter.

## The source caps it too

The guarantee is end to end only if the source can **rewind** to a checkpoint's offsets. A source
that cannot is at least once end to end, whatever the sink does. The sources that ship each declare their own delivery —
`postgres-cdc`, [`kafka`](/help/topics/source-kafka), filesystem, Delta and feedfile can be exactly
once; JDBC, Aerospike and Cassandra are at least once; see [choosing a source](/help/topics/sources-overview).

Two Pravaha queries connected by a Kafka topic — the first writing it with `kafka-sink`, the second
reading it with the `kafka` source at its default `isolation.level: read_committed` — are exactly
once end to end: the sink commits once per checkpoint, and the source's offsets are its own
checkpoint's. See [the Kafka source](/help/topics/source-kafka).

## What the node tells you

Every registration that names a sink is logged at `INFO` by `QueryRegistry` with the guarantee it
will actually get and the reason. These are the five sentences it can say:

```text
query 'q' writes to sink 's', exactly-once: the sink is transactional, so what is written between checkpoints is prepared at each checkpoint's cut, recorded in the checkpoint, and committed once the checkpoint is durable
query 'q' writes to sink 's', at-least-once: the sink is transactional, but this query takes no checkpoints (pravaha.checkpoint.directory is unset), so each commit is its own transaction and a restart delivers again
query 'q' writes to sink 's', effectively-once: the sink is transactional, but this query takes no checkpoints (pravaha.checkpoint.directory is unset), so each commit is its own transaction and a restart delivers again; the sink upserts idempotently, so the repeat rewrites the values already there
query 'q' writes to sink 's', effectively-once: the sink upserts idempotently, so what a restart delivers again rewrites records with the values they already hold
query 'q' writes to sink 's', at-least-once: the sink appends and is not transactional, so a restart delivers again what was written after the last checkpoint; a view commit carries no sequence to deduplicate the repeat on
```

`GET /api/v1/sinks` reports each sink's `guarantee` as **what this node gives**, in the same terms as
the log line: `EXACTLY_ONCE` for a transactional sink only when the node takes checkpoints,
`EFFECTIVELY_ONCE` for an idempotent upsert (`aerospike-sink`, and `jdbc-sink` or `kafka-sink` in
upsert mode on a node without checkpoints), and `AT_LEAST_ONCE` for anything else. It does not know
which source a query will read; the log line is still the authority for a particular registration.
(`GET /api/v1/plugins` shows each plugin's own claim, which knows nothing of the node.)

## Worked example: turning effectively-once into exactly-once

A node with no checkpoint directory, a `jdbc-sink`, and this registration:

```sql
CREATE CONTINUOUS QUERY region_totals
    KEYED BY (region, window_end)
    WRITING TO region_table
AS
SELECT region, window_start, window_end, SUM(amount) AS revenue, COUNT(*) AS orders
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end;
```

logs `effectively-once: the sink is transactional, but this query takes no checkpoints` — a
`jdbc-sink` upserts by default, so a restart's repeat rewrites rows with the values they already
hold — but while the replay catches up, the table can show rows rewound to values they held before.
Give the node a checkpoint directory:

```yaml
pravaha:
  checkpoint:
    directory: /var/lib/pravaha/checkpoints
    interval: 1m
    keep: 3
  sinks:
    region_table:
      plugin: jdbc-sink
      options:
        url: "jdbc:postgresql://pg.internal:5432/analytics?sslmode=verify-full&sslrootcert=/etc/pravaha/tls/pg-ca.pem"
        user: pravaha
        password: "${PG_PASSWORD}"
        table: region_revenue
        schema: "region:STRING,window_start:TIMESTAMP,window_end:TIMESTAMP,revenue:INT64,orders:INT64"
        key.columns: "region,window_end"
```

restart (with `pravaha.registry.journal` set, so the registration is recovered rather than lost),
and the recovered registration logs `exactly-once: the sink is transactional, ...`. The
table now changes once per checkpoint, all of a checkpoint's rows together.

## Where the guarantee ends

- **At PRV-8009.** A sink that refuses a batch is detached. Re-registering sends the view's whole
  contents again: harmless to an upsert sink, a repeat for an append sink.
- **At a stop that is never followed by a start.** Dropping a registration commits what its
  transactional sink was written. Stopping the node does not: the tail since the last checkpoint
  stays uncommitted, as after a crash, and the restart writes it — so a node stopped and never
  restarted leaves that tail out of the sink.
- **At a second writer.** Exactly-once for `jdbc-sink` assumes one writer per `transaction.id` and
  nothing else writing the target's keys; for `kafka-sink`, one writer per `transactional.id` — a
  second fences the first.
- **At a `read_uncommitted` consumer.** `kafka-sink`'s exactly-once is what a `read_committed`
  consumer sees; one reading uncommitted can also see a commit a crash aborted and the restore redid.
  That includes a `kafka` source configured with `isolation.level: read_uncommitted`.
- **At a second name.** A sink attached to a computation that is already running is first sent the
  view's whole contents, inside its first transaction when it is transactional.

!!! note "Subscribers are different"
    A subscription is not a sink and none of this applies to it: it delivers whole commits live to
    whoever is attached. See [subscriptions](/help/topics/subscriptions).

## Where next

- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what a checkpoint holds and how a
  restore works
- [The jdbc sink](/help/topics/sink-jdbc) — the staging table in detail
- [The Kafka sink](/help/topics/sink-kafka) — the staging topic, and what `read_committed` has to do with it
- [The Kafka source](/help/topics/source-kafka) — reading such a topic back, exactly once
- [How a query writes to a sink](/help/topics/sinks-overview)
