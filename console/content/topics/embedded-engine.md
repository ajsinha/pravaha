---
title: The embedded engine
slug: embedded-engine
category: embedding
order: 10
icon: box
summary: "The whole engine inside your own JVM with no server, no network and no Spring: declare streams, register continuous queries, push rows, close windows, read and subscribe — one complete, runnable program."
badge: JAVA
audience: Developers
keywords: [PravahaEngine, pravaha-embedded, createDefault, declareStream, push, advanceEventTime, register, subscribe, RowChange, ContinuousQuery, in-process, library, Configuration]
guide: user-guide#9-embed-the-engine-in-your-application
related: [spring-boot-starter, point-reads, subscriptions, event-time-watermarks, configuration]
---

`pravaha-embedded` is the engine as a library. Everything the server does to a continuous query —
planning, lanes, incremental operators, maintained views, commits, checkpoints — happens in your
process, called directly, with no port opened. It is the right shape for a service that owns its own
input (it *pushes* rows rather than having a connector read them), for tests, and for an application
that wants a maintained answer without running a second process.

The core contains **no Spring**, and the build enforces it (ADR-019): an embedded engine inherits its
host's JVM and whatever Spring version the host happens to use, so it cannot bring one of its own. The
Spring integration is a separate layer — [Spring Boot starter](/help/topics/spring-boot-starter).

## At a glance

| | |
|---|---|
| Artifact | `com.ash.messaging:pravaha-embedded` (version `0.1.0-SNAPSHOT` in this repository) |
| Entry point | `PravahaEngine.createDefault()` or `PravahaEngine.create(Configuration)` |
| Lifecycle | create → **declare** → `start()` → register, push, read, subscribe → `close()` |
| Security | None: every call runs as the anonymous principal under a permissive policy. The host decides who may call |
| Persistence | Opt-in, with the server's keys: `pravaha.registry.journal` brings registrations back, `pravaha.checkpoint.directory` their state |
| Plugins | `filesystem` is on the classpath already; any other plugin jar on the classpath can be bound by name |
| Singletons | None. Several engines run side by side in one JVM, each with its own configuration and plugins |

## A complete program

A payments service that keeps a per-merchant, per-minute total and a list of large payments, pushes
its own rows, closes windows by advancing event time, and reads and subscribes. Every call is the
engine's real API.

```java
import java.time.Instant;
import java.util.List;

import com.ash.messaging.pravaha.embedded.PravahaEngine;
import com.ash.messaging.pravaha.embedded.RowChange;

public final class Payments {

    record Large(long txnId, String merchant, long amount) {}

    public static void main(String[] args) {
        try (PravahaEngine engine = PravahaEngine.createDefault()) {
            // 1. Declare, before start: the registry plans every query against these streams.
            engine.declareStream("txn",
                    "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,"
                            + "currency:STRING,status:STRING?,event_time:TIMESTAMP",
                    "event_time");                       // the event-time column: windows can close
            engine.start();

            // 2. Register, in SQL -- query(sql) runs CREATE CONTINUOUS QUERY too.
            engine.query("""
                    CREATE CONTINUOUS QUERY merchant_minutes
                        KEYED BY (merchant, window_end)
                    AS SELECT merchant, window_start, window_end, SUM(amount) AS spend
                       FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
                       GROUP BY merchant, window_start, window_end""");
            // ... or with the registration call, keys by output column name.
            engine.register("large_payments",
                    "SELECT txn_id, merchant, amount FROM txn WHERE amount > 1000", "txn_id");

            // 3. Subscribe: every commit, retractions included, on the committing thread.
            engine.subscribe("merchant_minutes", changes -> changes.forEach(c ->
                    System.out.println((c.isRetraction() ? "- " : "+ ") + c.values())));

            // 4. Push rows, in column order. Returns once every query reading txn has committed them.
            Instant t = Instant.parse("2026-09-19T09:30:05Z");
            engine.push("txn",
                    new Object[] {1L, "u1", "ACME", 400L, "GBP", "OK", t},
                    new Object[] {2L, "u2", "ACME", 1250L, "GBP", "OK", t.plusSeconds(20)},
                    new Object[] {3L, "u1", "TRAVELCO", 4800L, "GBP", null, t.plusSeconds(40)});

            // 5. Close the 09:30 window: no row of txn earlier than 09:31:15 is still coming.
            engine.advanceEventTime("txn", Instant.parse("2026-09-19T09:31:15Z"));

            // 6. Read, as rows or as records matched by column name.
            List<Large> large = engine.query(Large.class, "SELECT * FROM large_payments");
            System.out.println(large);
            Object acme = engine.query(
                    "SELECT spend FROM merchant_minutes WHERE merchant = ?", "ACME").rows().get(0)[0];
            System.out.println("ACME 09:30 spend = " + acme);
        }
    }
}
```

```text
+ {merchant=ACME, window_start=2026-09-19T09:30:00Z, window_end=2026-09-19T09:31:00Z, spend=1650}
+ {merchant=TRAVELCO, window_start=2026-09-19T09:30:00Z, window_end=2026-09-19T09:31:00Z, spend=4800}
[Large[txnId=2, merchant=ACME, amount=1250], Large[txnId=3, merchant=TRAVELCO, amount=4800]]
ACME 09:30 spend = 1650
```

(The exact rendering of a `RowChange`'s values follows its `toString`; the numbers are what the three
pushed rows add up to.) The two SQL statements, as the engine plans them:

```sql
CREATE CONTINUOUS QUERY merchant_minutes
    KEYED BY (merchant, window_end)
AS SELECT merchant, window_start, window_end, SUM(amount) AS spend
   FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
   GROUP BY merchant, window_start, window_end;
```

```sql
SELECT txn_id, merchant, amount FROM txn WHERE amount > 1000
```

and the reads:

<!-- sql: read -->
```sql
SELECT spend FROM merchant_minutes WHERE merchant = ?
```

## Every call

| Call | What it does |
|---|---|
| `PravahaEngine.createDefault()` / `create(Configuration)` | Builds an engine; does not start it |
| `declareStream(name, "col:TYPE,…")` / `(name, spec, eventTimeColumn)` / `(StreamSchema)` | A stream. Name the event-time column, or no window over it ever closes |
| `bindSource(stream, plugin, options)` | Feed a stream from a source plugin by the name it reports (`filesystem`, `jdbc`, …), with its options |
| `bindLookup(table, plugin, options)` | A dimension table for `FOR SYSTEM_TIME AS OF` joins (`jdbc-lookup`, `aerospike-lookup`) |
| `bindSink(sink, plugin, options)` | A sink a query may write to with `WRITING TO` |
| `declareQuery(ContinuousQuery)` | A query registered at start, after anything the journal recovers |
| `start()` / `stop()` / `close()` | Lifecycle; `close()` stops if needed, so try-with-resources works. A failed engine is not restarted in place |
| `state()` | `CREATED`, `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`, … |
| `register(name, sql, keyColumns…)` / `register(ContinuousQuery)` | A continuous query, or a new name on the computation already answering it |
| `query(sql, params…)` | SQL over the views at their committed frontier — and `CREATE`/`DROP`/`PAUSE`/`RESUME CONTINUOUS QUERY`, `SHOW CONTINUOUS QUERIES` |
| `query(Class<R>, sql, params…)` | The same, each row read into a record by column name |
| `subscribe(query, consumer)` / `subscribe(query, SubscriptionOptions, consumer)` | Every commit of a view, as `List<RowChange>`; close the returned `Subscription` to stop |
| `push(stream, Object[]…)` / `push(stream, List<Object[]>)` / `push(stream, Map)` | Rows in; returns once every running query reading the stream has committed them, with the number of computations reached |
| `advanceEventTime(stream, Instant)` | "Nothing earlier than this is coming" — closes windows over pushed rows. A bound source's watermark advances on its own |
| `find(name)`, `queries()`, `pause`, `resume`, `drop` | The registry's lifecycle |
| `registry()` | The `QueryRegistry` underneath, for everything else (parameter classification, sink status, lane sharing) |

`ContinuousQuery.named("x").sql(…).keyedBy("a", "b").retaining(Duration.ofHours(8)).writingTo("sink").build()`
is the builder for a query with retention or a sink.

### Values a push accepts

| Column type | Java value |
|---|---|
| `INT64`, `INT32`, `INT16`, `INT8` | any `Number` that fits |
| `FLOAT64`, `FLOAT32` | any `Number` |
| `STRING` | a `String` |
| `BOOLEAN` | a `Boolean` |
| `TIMESTAMP` | an `Instant`, or epoch **nanoseconds** as a `long` |
| `DATE` | a `LocalDate` |
| `BYTES` | a `byte[]` |
| nullable (`?`) | `null` |

**The whole batch is checked first.** One bad row — a string where a number belongs, a missing value
for a non-nullable column — and the push delivers nothing, refused with PRV-8102. A stream that was
never declared is PRV-8101.

## Closing windows

A windowed query emits a window when the watermark passes its end. Over a *bound source* the engine
advances the watermark itself. Over *pushed* rows it cannot know that no earlier row is still coming, so
you tell it:

```java
engine.advanceEventTime("txn", Instant.parse("2026-09-19T09:31:15Z"));
```

Advance only as far as you are sure of: a row pushed later with an earlier event time is late, and is
either a correction (within the stream's allowed lateness) or dropped. See
[Event time and watermarks](/help/topics/event-time-watermarks).

## Subscribing without blocking the engine

The consumer runs **on the thread that committed**. Keep it short, or hand the work to an executor:

```java
ExecutorService work = Executors.newSingleThreadExecutor();
engine.subscribe("merchant_minutes", changes -> work.submit(() -> apply(changes)));
```

With an explicit buffer and overflow policy (from `pravaha-registry`):

```java
engine.subscribe("merchant_minutes",
        SubscriptionOptions.of(50_000, SubscriptionOptions.Overflow.FAIL),
        changes -> ledger.apply(changes));
```

| Overflow | Right for |
|---|---|
| `CONFLATE` (default, 10,000 rows) | A dashboard: the latest value per key |
| `DROP_OLDEST` | Recency over completeness |
| `FAIL` | A ledger: being told beats a gap. Ends *this* subscription, never the query |

## Everything from configuration

The same blocks the server reads, so a query can be declared without code:

```java
Configuration configuration = Configuration.builder()
        .set("pravaha.node.id", "payments-service")
        .set("pravaha.streams.txn.schema", "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP")
        .set("pravaha.sources.txn.plugin", "filesystem")
        .set("pravaha.sources.txn.options.path", "/var/lib/app/txn.csv")
        .set("pravaha.sources.txn.options.schema", "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP")
        .set("pravaha.sources.txn.options.event.time", "event_time")
        .set("pravaha.queries.big_txn.sql", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000")
        .set("pravaha.queries.big_txn.keys", "txn_id")
        .set("pravaha.registry.journal", "/var/lib/app/pravaha/registry.journal")
        .set("pravaha.checkpoint.directory", "/var/lib/app/pravaha/checkpoints")
        .build();
try (PravahaEngine engine = PravahaEngine.create(configuration)) {
    engine.start();   // big_txn registered, fed from the file, recovered after a restart
}
```

| Key | |
|---|---|
| `pravaha.node.id` | This engine's id in logs and metrics |
| `pravaha.streams.<name>.schema` | A stream, as `name:TYPE,…` |
| `pravaha.sources.<name>.plugin` / `.options.*` | A source binding — see [Sources](/help/topics/sources-overview) |
| `pravaha.queries.<name>.sql` / `.keys` | A query registered at start |
| `pravaha.registry.journal` | A file; registrations come back after a restart |
| `pravaha.checkpoint.directory` | A directory; each query's state is checkpointed under it, every `pravaha.checkpoint.interval` |

Without a journal and a checkpoint directory an engine is memory only — right for a test, wrong for a
service that must survive a restart. See [Checkpoints and recovery](/help/topics/checkpoints-recovery).

## Pitfalls

!!! warning "Pitfall: declaring after start"
    Streams, bindings and declared queries are fixed at `start()`. `declareStream` afterwards throws
    `IllegalStateException`: the registry was built over the streams it had, and a stream added later is
    one no query could name.

!!! warning "Pitfall: a windowed query that never emits"
    Two causes, both silent: the stream was declared without its event-time column, or rows were
    pushed and event time was never advanced. Name the column; call `advanceEventTime`.

!!! warning "Pitfall: an unwindowed GROUP BY per key"
    `SELECT user_id, SUM(amount) FROM txn GROUP BY user_id` is refused with PRV-2050 here exactly as on
    the server: its state would grow with every new user for ever. Window it. (A global aggregate with
    no `GROUP BY` is fine.)

!!! warning "Pitfall: no authorization"
    An embedded engine trusts its caller completely. If the callers are not all trusted, run the server,
    which authenticates, authorizes and audits.

!!! tip "A paused query drops what it is pushed"
    `pause(name)` keeps the view answering where it stopped; rows pushed while paused are dropped, not
    buffered.

## Where next

- [Spring Boot starter](/help/topics/spring-boot-starter) — the same engine as a bean.
- [Subscriptions](/help/topics/subscriptions) — weights and whole commits.
- [SDK reference](/help/topics/sdk-reference) — talking to a server instead.
