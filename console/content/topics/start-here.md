---
title: Start here
slug: start-here
category: start
order: 10
icon: signpost-2
summary: "What Pravaha is, the problem it solves, the three nouns everything else is built from, and where to go next for the job you came to do."
badge: START
audience: Everyone
keywords: [overview, introduction, what is pravaha, orientation, stream, view, continuous query, mental model]
guide: quickstart
related: [first-view, choosing-a-client, console-tour, streams, views-and-keys]
---

Pravaha is a streaming SQL engine that **maintains answers**. You tell it a question once — as
SQL, under a name — and it keeps the answer current for as long as the name is registered. The
answer is a **view**: an indexed table you read by key, scan with SQL, or subscribe to for its
changes. Nothing is re-run when you read it; the reading is a lookup.

That is the inversion the rest of the product follows from. A database answers a question when you
ask it and forgets it afterwards. Pravaha is told the question in advance, so it can do the work as
rows arrive and have the answer ready before anybody asks. Its motto is the same sentence written
short: *ask once, answer always*.

## The problem it solves

Most systems that need a continuously correct number — a card's spend in the last minute, a
counterparty's open exposure, the orders per region in the current five minutes — end up with three
moving parts: a stream processor that computes, a database that stores what it computed, and code
that keeps the two consistent. Every one of those seams is a place for the number to be wrong.

Pravaha collapses them:

| You would otherwise build | In Pravaha |
|---|---|
| A job in a stream processor | A `CREATE CONTINUOUS QUERY` statement |
| A serving database the job writes into | The view the query maintains, readable by key over Arrow Flight SQL or the PostgreSQL wire protocol |
| A cache or materialised table refreshed on a schedule | The same view — it is updated per commit, not per schedule |
| Retry and de-duplication logic for corrections | Z-set weights: a correction is a `-1` for the old row and a `+1` for the new one, applied by the engine and delivered to subscribers |
| A second copy of the pipeline for each team asking the same question | Sharing by fingerprint: identical questions become one computation with several names |

It reads the stores you already have — files, directories of feed files, JDBC databases, a
PostgreSQL table's change log, Kafka topics, Delta tables, Aerospike, Cassandra — and can write each
answer back out to a sink (a file, a JDBC table, an Aerospike set, a Kafka topic) as well as serving
it.

## The three nouns

Everything in the help is about one of these, or about how two of them meet.

| | What it is | Lives for |
|---|---|---|
| **Stream** | A named, typed, unbounded sequence of rows, bound to a source. Declared once with its schema, the column that carries each row's own time, and how late its rows may be | The node's lifetime |
| **Continuous query** | SQL registered under a name, with a key. It reads one or more streams and keeps running | Until it is dropped |
| **View** | The query's answer, maintained incrementally, readable by SQL, subscribable per commit | As long as its query |

```text
source ──feeds──► stream ──read by──► continuous query ──maintains──► view ──read by──► SELECT / subscribe / sink
```

A continuous query is **not a request**. You do not ask it for an answer; you register it once and
it keeps one. Reading the view is a different, cheap, repeatable operation. That is why registering
is authorised separately from reading, and why registering costs something (memory, a share of a
lane) while reading does not.

## The mental model in five sentences

1. **Rows carry weights.** `+1` is a row appearing, `-1` one being withdrawn; every operator does
   the same arithmetic on both, which is what lets an aggregate be maintained instead of recomputed
   ([Z-set weights](/help/topics/zset-weights)).
2. **Time is the time in the data.** Windows close when the data says a period is over — when the
   watermark passes the window's end — not when the wall clock does
   ([event time and watermarks](/help/topics/event-time-watermarks)).
3. **Everything is bounded or refused.** A query whose state would grow for ever — an unwindowed
   `GROUP BY` over a stream — is refused when it is planned (PRV-2050), not deployed to fail months
   later ([what the engine refuses](/help/topics/sql-refusals)).
4. **The key decides what a row replaces.** A view is keyed; a second row with the same key
   supersedes the first ([views and keys](/help/topics/views-and-keys)).
5. **Same question, one computation.** Two registrations that plan to the same thing share their
   state, whatever they are called ([sharing](/help/topics/sharing)).

## A first look, in SQL

The shape nearly everybody writes: filter a stream, window it in event time, aggregate, keep the
answer keyed.

```sql
CREATE CONTINUOUS QUERY merchant_minute
    KEYED BY (merchant, window_end)
    RETAIN FOR P1D
AS
SELECT merchant, window_start, window_end,
       COUNT(*)    AS payments,
       SUM(amount) AS takings
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE status = 'COMPLETED'
GROUP BY merchant, window_start, window_end;
```

Once registered, reading one merchant's latest minutes is a lookup against the view, not a scan of
the stream:

<!-- sql: read -->
```sql
SELECT window_end, payments, takings
FROM merchant_minute
WHERE merchant = 'm-042'
```

With sample data, that read answers something like this (one row per closed minute):

```text
window_end            payments  takings
2026-09-19 09:31:00   14        52300
2026-09-19 09:32:00   9         18750
```

The engine answers with one row when the `CREATE` succeeds — the name, its state, the fingerprint
of the computation it landed on, and its sink:

```text
name             state    fingerprint    sink
merchant_minute  RUNNING  7c1e2a9b4f03
```

(The fingerprint is a hash of the normalised plan; yours will differ.)

## What it is not

- **Not a database you load and query ad hoc.** It answers questions asked in advance. Ad-hoc
  federated analytics is out of scope on purpose ([ADR-030](/help/decisions/030-flight-sql-as-the-client-protocol)),
  and every SQL refusal is a case of that decision rather than an unfinished corner.
- **Not a job runner.** There is no job to submit, no cluster to wait for, and no separate serving
  store to keep in step.
- **Not (yet) distributed.** Today it is one node, scaled to its cores; a standby can take over from
  the newest checkpoint. Clustering is the remaining road to GA.
- **This console is not the product.** It is a separate process that reaches the engine only
  through the published Python SDK — the same API an integrator uses.

## Ten minutes, in order

| Minute | Do | Page |
|---|---|---|
| 0–3 | Read this page and [Concepts](/help/concepts) §1–§4 | this one |
| 3–8 | Start a node, declare a stream, register a query, read it, subscribe | [Your first maintained view](/help/topics/first-view) |
| 8–10 | Try a query the engine refuses, and read why | [What is refused, and why](/help/topics/sql-refusals) |

## Where to go by what you came to do

| You are | You want | Start with |
|---|---|---|
| An **analyst** writing SQL | What runs, what is refused, windows and joins | [The SQL reference](/help/topics/sql-reference), [windows](/help/topics/windows), [joins](/help/topics/joins) |
| A **developer** reading answers | Point reads, subscriptions, client code | [Choosing a client](/help/topics/choosing-a-client), [point reads](/help/topics/point-reads), [subscriptions](/help/topics/subscriptions) |
| An **operator** running a node | Configuration, sizing, metrics, recovery | [Configuration](/help/topics/configuration), [metrics and alerts](/help/topics/metrics-alerts), [checkpoints](/help/topics/checkpoints-recovery) |
| Connecting a **data store** | Options, a complete binding, pitfalls | [Sources](/help/topics/sources-overview), [sinks](/help/topics/sinks-overview) |
| Responsible for **security** | Who may read what, and the audit trail | [Authentication](/help/topics/authentication), [authorization](/help/topics/authorization) |
| Holding an **error code** | What it means and what to do | [Every error code](/help/codes), or type the code in the help search |
| Embedding it in a **JVM application** | No server, no network | [The embedded engine](/help/topics/embedded-engine), [Spring Boot](/help/topics/spring-boot-starter) |

!!! tip "The single most common first-run surprise"
    A windowed query refused with `PRV-2002` saying the stream "declares no event-time column".
    Without that key no watermark advances and no window could ever close, so the engine refuses
    the query instead of running it empty for ever. Declare it and register again. If the query
    *did* register and the view is still empty, no row has yet arrived past a window's end plus the
    stream's `out-of-orderness`. See [event time and watermarks](/help/topics/event-time-watermarks).

!!! note "Three ports"
    The engine's Flight SQL endpoint is **19090**, its HTTP API and Prometheus endpoint **18080**, the
    PostgreSQL gateway (when enabled) **5432**, and this console **17070**. Confusing them is the
    commonest way a first connection fails.

## Where next

- [Your first maintained view](/help/topics/first-view) — the whole loop, with expected output at every step
- [Choosing a client](/help/topics/choosing-a-client) — CLI, SDKs, Flight SQL, psql, HTTP, embedded
- [The console, screen by screen](/help/topics/console-tour)
- [Streams](/help/topics/streams) and [views and keys](/help/topics/views-and-keys)
- The long form: [Quick start](/help/quickstart) and [Concepts](/help/concepts)
