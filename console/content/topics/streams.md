---
title: Streams
slug: streams
category: concepts
order: 10
icon: water
summary: "A stream is a named, typed, unbounded sequence of rows. How to declare one — schema, event time, lateness — in configuration, over HTTP or in code, and how it is bound to a source."
audience: Everyone
keywords: [stream, schema, declare, pravaha.streams, event-time, out-of-orderness, allowed-lateness, allowedLateness, types, nullable, source binding, POST /api/v1/streams, catalog]
guide: continuous-queries#2-declaring-a-stream
related: [event-time-watermarks, sources-overview, source-filesystem, query-lifecycle, sql-types]
---

A **stream** is a named, typed, unbounded sequence of rows. It is the input side of everything:
continuous queries read streams, and their views are what you read back. A stream has no storage of
its own — rows pass through it on their way into the queries that read it — and it lives as long as
the node does.

Declaring a stream and feeding it are **two separate things**, and a running query needs both:

| Block | Says | Needed for |
|---|---|---|
| `pravaha.streams.<name>` | what the stream **is** — its schema, which column is its event time, how late its rows may be | **planning**. A query can be written and validated against a stream with nothing attached |
| `pravaha.sources.<name>` | where its rows **come from** — a plugin and its options | **running**. The key is the stream it feeds |
| `pravaha.lookups.<name>` | a dimension table a query may **ask** | temporal joins. A lookup is asked, never consumed, and never advances time |

The separation is deliberate: a query can be written ahead of its source, a source can be swapped
without touching the queries, and an embedder pushing rows in directly needs no source at all.

## Declaring a stream in configuration

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 30s
```

| Key | Required | Default | What it does |
|---|---|---|---|
| `schema` | yes | — | the columns, as `name:TYPE,name:TYPE`; a `?` suffix makes a column nullable |
| `event-time` | for any windowed query or time-bounded join | none | the `TIMESTAMP` column that carries each row's own time. **Without it no watermark advances, no window could ever close, and a windowed query over the stream is refused with `PRV-2002`** |
| `out-of-orderness` | no | `10s` | how late this stream's rows may arrive and still be waited for; the watermark trails the newest event time by this much |
| `allowed-lateness` | no | `0s` | how long after a window has been published a late row may still **correct** it — the old result at `−1`, the new one at `+1`. Needs `event-time`; refused negative. Non-zero makes every windowed query over the stream one that revises, so it needs a sink that takes retractions (PRV-2041) |

Declared here, a stream comes back on every start. The keys are per stream on purpose: lateness is a
property of the **source** — a feed of mobile clients over a bad network and a scan of data at rest
have nothing in common — and a query over three streams gets three tolerances rather than the worst
of them.

!!! warning "pravaha.watermark.out-of-orderness is not read"
    The shipped `application.yaml` carries `pravaha.watermark.out-of-orderness`, described as an
    engine-wide default. **Nothing reads it** (DOCX-6): setting it changes no answer. The per-stream
    `out-of-orderness` above is the only key that does. The 10-second default is real; it lives in
    the engine's code.

## The schema grammar

One grammar everywhere a schema is written — `pravaha.streams.*.schema`, a source's `schema`
option, `POST /api/v1/streams`, and the CLI's `--schema`:

| Type | Aliases | Notes |
|---|---|---|
| `BOOLEAN` | `BOOL` | |
| `INT8`, `INT16`, `INT32`, `INT64` | `BYTE`, `SHORT`, `INT`, `LONG` | `INT64` is SQL `BIGINT` |
| `FLOAT32`, `FLOAT64` | `FLOAT`, `DOUBLE` | `SUM`, `AVG`, `MIN` and `MAX` over a float column are refused (PRV-2020): every aggregate accumulates in a 64-bit integer. `COUNT` works |
| `STRING` | `VARCHAR`, `TEXT` | UTF-8 |
| `BYTES` | `BINARY` | carried over Flight; the PostgreSQL gateway refuses it by name |
| `DATE`, `TIME`, `TIMESTAMP` | | ISO-8601 in files (`2026-09-19`, `09:30:00`, `2026-09-19T09:30:00Z`); a bare number is days for a date, nanoseconds for the other two |
| `DECIMAL(p,s)` | | carried through scans, filters and projections; **arithmetic over it is refused**, rather than done in floating point |

Names are case-sensitive and kept as written — `USER_ID` and `user_id` are different columns, and
the wrong case is refused (PRV-2002). `ARRAY`, `MAP` and `ROW` are not supported.

## Binding it to a source

The key under `pravaha.sources` is the stream it feeds. Plugin options live **under `options:`**:

```yaml
pravaha:
  sources:
    txn:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        follow: "true"
```

!!! warning "Pitfall: an option one level too high"
    A key written directly under the source instead of under `options:` is not read, not reported,
    and the node starts and ingests nothing.

!!! note "The schema is written twice"
    Once for the catalogue to plan against, once for the plugin to parse rows with. They are read by
    different components and must agree. (Some plugins — Delta — take the schema from the table
    instead and have no `schema` option.)

Only `filesystem` is inside the server jar; `feedfile`, `jdbc`, `delta`, `aerospike`, `cassandra`,
`postgres-cdc` and `kafka` are separate modules dropped on the classpath. A plugin is found when a query is first registered
against the stream, **not at startup** — so a binding naming a missing plugin starts cleanly and
fails at the registration that needs it, listing the plugins that are available. A stream with no
binding at all registers and runs, and the node logs that nothing is attached. See
[sources](/help/topics/sources-overview).

## Declaring a stream over HTTP

The same two things in a JSON body — useful for a stream that exists only for an experiment:

```bash
curl -s -X POST http://localhost:8080/api/v1/streams \
     -H 'Content-Type: application/json' \
     -d '{"name": "txn",
          "schema": "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP",
          "eventTime": "event_time",
          "outOfOrderness": "PT10S"}'
curl -s http://localhost:8080/api/v1/streams
```

An optional `"allowedLateness": "PT1M"` declares the allowed lateness the same way.
`GET /api/v1/streams` reports each stream's schema, `eventTime`, `outOfOrderness`, `allowedLateness`
(`PT0S` when windows are final at close) and the `source` plugin feeding it — the plugin's name only,
never its options. Over HTTP the durations are ISO-8601 (`PT10S`); in YAML, `10s` works. An
`outOfOrderness` or an `allowedLateness` without an `eventTime` is refused: each is about an event
time, and there is none. A stream
declared over HTTP lasts until the node restarts; declare it in configuration to keep it.

## Declaring a stream in code

Embedded, before `start()`:

```java
engine.declareStream("txn",
        "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP", "event_time");
```

or with every property, **allowed lateness** included:

```java
StreamSchema txn = StreamSchema.builder("txn")
        .field("txn_id", Types.int64())
        .field("user_id", Types.string())
        .field("amount", Types.int64())
        .field("event_time", Types.timestamp())
        .eventTime("event_time")
        .outOfOrderness(Duration.ofSeconds(10))
        .allowedLateness(Duration.ofMinutes(5))
        .build();
engine.declareStream(txn);
```

Out-of-orderness decides how long the engine **waits** before calling a window complete. Allowed
lateness decides whether a row arriving **after** that still corrects the published answer. See
[late data and corrections](/help/topics/late-data).

## A query over it

Once declared, a stream is planned against like a table. A filter over `txn`:

```sql
SELECT txn_id, user_id, amount
FROM txn
WHERE status = 'COMPLETED' AND amount > 1000
```

And two streams can be joined, whatever connectors feed them — here orders and their shipments,
with a time bound that also decides how long each side's rows are kept:

```sql
SELECT o.order_id, o.region, s.carrier
FROM orders AS o
JOIN shipments AS s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '2' HOUR
```

A query naming a stream that is not declared is refused:

<!-- sql: refused PRV-2002 -->
```sql
SELECT id FROM payments
```

```text
PRV-2002  Object 'payments' not found ...
```

The message deliberately does not list the streams that do exist — that would tell a caller who
may not read them that they are there. `GET /api/v1/streams` lists what you may see.

## Pitfalls

!!! warning "A windowed query that is RUNNING and empty"
    The stream has no `event-time`, or its source is not stamping rows with it (`event.time` on
    the filesystem and Aerospike sources). No watermark, no closed windows — for ever.

!!! warning "A join is only as current as its laggiest input"
    A query over two streams takes the minimum of their watermarks. A quiet or slow stream holds
    back every window downstream of the join — correct, and surprising.

!!! tip "Nullable columns and three-valued logic"
    `status:STRING?` is nullable. `WHERE status = 'COMPLETED'` keeps only rows where the
    comparison is TRUE, so null statuses are excluded; `SELECT status = 'ok'` is refused because
    it would write UNKNOWN into a boolean — `SELECT (status = 'ok') IS TRUE` plans. See [types](/help/topics/sql-types).

## Where next

- [Event time and watermarks](/help/topics/event-time-watermarks) — what `event-time` and `out-of-orderness` actually do
- [Sources](/help/topics/sources-overview) — every shipped connector
- [Continuous queries and their lifecycle](/help/topics/query-lifecycle)
- The long form: [Streams, queries and SQL §2](/help/continuous-queries#2-declaring-a-stream)
