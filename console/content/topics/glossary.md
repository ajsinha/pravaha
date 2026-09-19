---
title: Glossary
slug: glossary
category: reference
order: 10
icon: book-half
summary: "Every term Pravaha uses, A to Z, in a sentence or two — stream, view, key, weight, retraction, watermark, frontier, fingerprint, lane, sink, spill — each linked to the topic that explains it."
badge: A-Z
audience: Everyone
keywords: [glossary, terms, definitions, vocabulary, z-set, weight, retraction, watermark, frontier, fingerprint, lane, view, key, sink, lookup, retention]
guide: concepts
related: [start-here, streams, views-and-keys, zset-weights, event-time-watermarks]
---

Pravaha's vocabulary is small, and most of it names a mechanism precisely — a *watermark* is not "a
bit of lag", and a *retraction* is not "an update". Each entry says what the word means here, in a
sentence or two, and links to the topic that explains it with examples.

**A** · [B](#b) · [C](#c) · [D](#d) · [E](#e) · [F](#f) · [H](#h) · [I](#i) · [J](#j) · [K](#k) ·
[L](#l) · [M](#m) · [N](#n) · [O](#o) · [P](#p) · [R](#r) · [S](#s) · [T](#t) · [V](#v) · [W](#w) ·
[Z](#z)

## A

**Admission control.** The read path's limits: so many reads at once, a bounded queue, and a share per
tenant. A read past them is refused (PRV-4026, PRV-4027, PRV-4028) and retried by the client, so a
client in a loop cannot stop a continuous query keeping up. → [State and serving codes](/help/topics/errors-state)

**Allowed lateness.** How long after a window has emitted a late row may still correct it — zero
unless a stream declares it (`pravaha.streams.<name>.allowed-lateness`). A window with allowed lateness
*revises* its answer, which matters for which sinks it may write to.
→ [Late data and corrections](/help/topics/late-data)

**Arena.** The slabs of off-heap memory a lane's operators write output batches into, sized by
`pravaha.lane.arena.slab-bytes`. Full: PRV-3001. → [Sizing and lanes](/help/topics/sizing-lanes)

**At least once.** A delivery guarantee: after a restart, a sink may receive some rows again. The
`filesystem` sink is at least once. → [Delivery guarantees](/help/topics/delivery-guarantees)

**Audit trail.** The record of every authorization decision — who asked for what, allowed or denied —
kept in memory, in a file, or both, and readable over `GET /api/v1/audit` by permitted roles.
→ [Audit](/help/topics/audit)

## B

**Batch.** The rows a lane hands an operator at once (`pravaha.lane.batch-size`). A commit ends only
where a batch ended. → [Sizing and lanes](/help/topics/sizing-lanes)

**Binding.** The configuration that attaches a plugin to a name: a source to a stream under
`pravaha.sources`, a lookup table under `pravaha.lookups`, a sink under `pravaha.sinks` — a `plugin`
and its `options`. → [Sources](/help/topics/sources-overview)

## C

**Catalog.** The streams, lookup tables, queries, views and sinks a node knows about. In this console,
the Catalog screen. → [Streams](/help/topics/streams)

**Change data capture (CDC).** Reading a database's own log of changes instead of polling its
tables, so a delete arrives as a `−1` and an update as a `−1` and a `+1`. The `postgres-cdc` source
does it for PostgreSQL. → [The postgres-cdc source](/help/topics/source-postgres-cdc)

**Changelog (Kafka).** A topic of changes with their weights, `{"op","weight","row"}`: what
`kafka-sink` writes in `mode: changelog`, and what the `kafka` source reads with `format: changelog`,
so one query's retractions reach another through a topic.
→ [The Kafka source](/help/topics/source-kafka) · [The Kafka sink](/help/topics/sink-kafka)

**Checkpoint.** A query's accumulated state and its source offsets, written periodically under
`pravaha.checkpoint.directory` so a restart recovers answers, not only questions.
→ [Checkpoints and recovery](/help/topics/checkpoints-recovery)

**Cluster mode.** What is asked of several nodes — `SINGLE`, `REPLICATED`, or `PARTITIONED` (refused by
a node today) — as distinct from the *mechanism* that coordinates them. → [Cluster mode](/help/topics/cluster-mode)

**Commit.** The unit in which a view changes and subscribers hear about it: all of one batch's changes,
applied together. A subscriber never sees half a commit; an update's retraction and its insert arrive
in the same one. → [Consistency](/help/topics/consistency)

**Continuous query.** A registered computation over one or more streams, maintained until it is
dropped. Not a request: you register it once and it keeps an answer. → [The life of a query](/help/topics/query-lifecycle)

**Correction.** What a late row does to a window that already emitted: the old result is retracted at
weight −1 and the new one inserted at +1. A count can go down; that is a correction working.
→ [Late data and corrections](/help/topics/late-data)

## D

**Dead-letter queue.** Where a source's undecodable records are written, with their bytes and reason,
when `pravaha.dlq.directory` is set — instead of the record stopping the source.
→ [Dead letters](/help/topics/dead-letters)

**Delivery guarantee.** How many times a sink may receive a change across a restart: at least once,
effectively once (idempotent upserts), or exactly once (transactional).
→ [Delivery guarantees](/help/topics/delivery-guarantees)

**Descriptor.** The `DESCRIPTOR(event_time)` argument of a window table function: which column the
window is cut on. It must be the stream's declared event time. → [Windows](/help/topics/windows)

## E

**Effectively once.** A delivery guarantee: a replay rewrites what is already there, so the sink ends
up with each change once. `aerospike-sink` is effectively once. → [Delivery guarantees](/help/topics/delivery-guarantees)

**Embedded engine.** The engine running inside an application's own JVM (`PravahaEngine`), fed by rows
the application pushes. → [The embedded engine](/help/topics/embedded-engine)

**Event time.** The time a row *says* it happened, carried in a column and declared per stream with
`event-time`. Windows are cut and closed in event time, never on the wall clock.
→ [Event time and watermarks](/help/topics/event-time-watermarks)

**Exactly once.** A delivery guarantee: each change is committed to the sink once, even across a
crash — a transactional sink (`jdbc-sink`, or `kafka-sink` to a `read_committed` consumer) on a node
that checkpoints. A source can be exactly once too — filesystem, Delta, postgres-cdc, Kafka (and feedfile,
configured for it) resume at the exact position their checkpoint recorded. → [Delivery guarantees](/help/topics/delivery-guarantees)

## F

**Fingerprint.** The identity of a computation: its normalised plan, row filters, key columns and
retention. Two registrations with the same fingerprint share one computation.
→ [Sharing by fingerprint](/help/topics/sharing)

**Flight SQL.** Arrow Flight SQL, the client protocol (port 9090) the SDKs, the CLI, the console and
JDBC/ADBC drivers speak. → [Client code](/help/topics/client-snippets)

**Frontier.** How far in its input a view has committed. A read can ask for at least a frontier; a view
cannot answer as of a past one (PRV-4020). → [Consistency](/help/topics/consistency)

## H

**Hopping window.** A window of fixed width that starts every *slide* — a minute of history recomputed
every ten seconds — so each row belongs to several windows. → [Windows](/help/topics/windows)

## I

**Idle partition.** A source partition that has produced nothing for `pravaha.watermark.idle-after`, and
so stops holding the watermark back. → [Event time and watermarks](/help/topics/event-time-watermarks)

**Inbox.** The fixed-size cells a lane's rows arrive in (`pravaha.lane.inbox.cells` ×
`pravaha.lane.inbox.cell-bytes`) — most of what an idle query costs in memory. → [Sizing and lanes](/help/topics/sizing-lanes)

## J

**Journal.** The file at `pravaha.registry.journal` where registrations are written and replayed at
startup. It keeps the questions; checkpoints keep the answers. → [Checkpoints and recovery](/help/topics/checkpoints-recovery)

## K

**Kafka offset.** A position in one partition of a topic. The `kafka` source keeps each partition's
next offset in the query's checkpoint — never in a consumer group — which is what makes it exactly
once. → [The Kafka source](/help/topics/source-kafka)

**Key (`KEYED BY`).** The output columns that decide what a view row *replaces*: a second row with the
same key supersedes the first. Part of the query's identity. → [Views and keys](/help/topics/views-and-keys)

## L

**Lane.** The single-threaded unit that runs a query's pipeline, with its own inbox and arena.
→ [Sizing and lanes](/help/topics/sizing-lanes)

**Lane sharing.** Letting many queries share a fixed set of lanes (`pravaha.lane.multiplex.*`) instead
of holding one apiece; a shared lane shares its fate. → [Lane sharing](/help/topics/lane-sharing)

**Lookup.** A dimension table a query *asks* rather than consumes — `jdbc-lookup`, `aerospike-lookup` —
joined with `FOR SYSTEM_TIME AS OF`. It never advances event time and holds no checkpointed state.
→ [Lookups](/help/topics/lookups) · [Temporal joins](/help/topics/temporal-joins)

## M

**Match window.** The event-time bound on a stream-to-stream join: which pairs match and how long each
side's rows are held. → [Joins](/help/topics/joins)

## N

**Node id.** `pravaha.node.id`: the node's name in logs and in the ownership marker of every directory
it writes. A standby shares its primary's. → [Standby](/help/topics/standby)

## O

**Out-of-orderness.** How late a stream's rows may arrive, per stream
(`pravaha.streams.<name>.out-of-orderness`). The watermark trails the rows by this much.
→ [Event time and watermarks](/help/topics/event-time-watermarks)

**Ownership marker.** The `.pravaha-owner` file a node writes into its state directories, naming
itself, refreshed on a lease — how a node knows a directory is its own (PRV-4003).
→ [Standby](/help/topics/standby)

## P

**Parameter.** A `?` placeholder for a value, in `WHERE` and `HAVING` only — never a part of the
query's shape. → [Parameters](/help/topics/sql-parameters)

**Plugin.** A source, lookup or sink implementation found on the classpath by name.
→ [Sources](/help/topics/sources-overview) · [Sinks](/help/topics/sinks-overview)

**Point read.** A read of one key (or a few) from a view — the cheap, repeatable operation a view
exists for. → [Point reads](/help/topics/point-reads)

**PostgreSQL gateway.** The read-only PostgreSQL wire protocol (port 5432, off by default) that lets
`psql`, Grafana and ORMs read views. → [The PostgreSQL gateway](/help/topics/pgwire)

**Pushdown.** Work a source does for the engine at the store: a filter (`WHERE`), the columns read, or
a running `COUNT`/`SUM`. Each source declares which it can do; the engine keeps its own filter
regardless. → [Sources](/help/topics/sources-overview)

**Principal.** Who a caller is, once authenticated: an id, a tenant and roles.
→ [Authentication](/help/topics/authentication)

**PRV code.** The stable `PRV-nnnn` code every failure carries. → [Reading a PRV code](/help/topics/errors-overview)

## R

**Replication slot.** PostgreSQL's record of how far a change-data-capture reader has confirmed; it
keeps write-ahead log until then. `postgres-cdc` confirms it only at checkpoints.
→ [The postgres-cdc source](/help/topics/source-postgres-cdc)

**Retention.** How much event time a view keeps (`RETAIN FOR P7D`, `--retain`); older rows are evicted.
Part of the fingerprint. → [Views and keys](/help/topics/views-and-keys)

**Retraction.** A change with weight −1: a row withdrawn from the answer. How corrections, deletes and
updates are expressed. → [Z-set weights and retractions](/help/topics/zset-weights)

**Row filter.** A predicate a security policy attaches to a principal for a view, applied to every read
so the principal sees only its rows (PRV-7003 when it cannot be enforced).
→ [Row filters](/help/topics/row-filters)

## S

**Shape check.** The registration-time check that a query's output matches its sink's declared schema
and key, column for column (PRV-8010). → [How a query writes to a sink](/help/topics/sinks-overview)

**Sink.** Where a query's committed changes are also written — a file, a table, an Aerospike set, a
Kafka topic — named with `WRITING TO`. → [Sinks](/help/topics/sinks-overview)

**Source.** Where a stream's rows come from — a file, a directory, a table, a Delta table, a set, a
PostgreSQL table's change log, a Kafka topic.
→ [Sources](/help/topics/sources-overview)

**Spill tier.** Memory-mapped files on disk that a query's state overflows into instead of being refused
at its ceiling. → [State and spill](/help/topics/state-spill)

**Standby.** A node that holds nothing and takes over from the newest checkpoint when its primary stops
refreshing its ownership claim. → [Standby](/help/topics/standby)

**State ceiling.** The most state an operator may hold before PRV-4001;
`pravaha_query_state_fraction` says how close each query is. → [State and spill](/help/topics/state-spill)

**Stream.** A named, typed, unbounded sequence of rows, bound to a source. → [Streams](/help/topics/streams)

**Subscription.** Receiving a view's committed changes as they happen, each with its weight.
→ [Subscriptions](/help/topics/subscriptions)

## T

**Temporal join.** A join against a lookup table as of each row's own event time:
`JOIN t FOR SYSTEM_TIME AS OF s.event_time`. → [Temporal joins](/help/topics/temporal-joins)

**Tenant.** The group a principal belongs to; read admission shares capacity per tenant.
→ [Authorization](/help/topics/authorization)

**Tombstone.** A Kafka record with a key and a null value: how `kafka-sink` in upsert mode writes a
retraction, and what a compacted topic reads as a delete. The `kafka` source cannot retract one — it
does not say what row the key held — so it refuses it unless told `tombstone: skip`.
→ [The Kafka sink](/help/topics/sink-kafka) · [The Kafka source](/help/topics/source-kafka)

**Tumbling window.** Fixed, non-overlapping windows: every row falls in exactly one.
→ [Windows](/help/topics/windows)

## V

**View.** A continuous query's answer, maintained incrementally and readable by SQL. A query and its
view share one name. → [Views and keys](/help/topics/views-and-keys)

## W

**Watermark.** "Nothing earlier than this is coming": event time derived from the rows arriving, minus
the stream's out-of-orderness. A window emits when the watermark passes its end.
→ [Event time and watermarks](/help/topics/event-time-watermarks)

**Weight.** The +1 or −1 on every change: insert or retract. The current answer is the sum of every
change's weight. → [Z-set weights and retractions](/help/topics/zset-weights)

**Window.** A finite group cut from an unbounded stream in event time — tumbling or hopping — which is
what lets an aggregate keep bounded state. → [Windows](/help/topics/windows) · [Windows, worked](/help/topics/windows-worked)

## Z

**Z-set.** A set of rows each carrying an integer weight. Pravaha's operators consume and produce
Z-sets, which is what makes every operator incremental and a correction just another change
(ADR-013). → [Z-set weights and retractions](/help/topics/zset-weights)

## Where next

- [Start here](/help/topics/start-here)
- [Concepts (long form)](/help/concepts)
