---
title: Streams
slug: streams
category: concepts
order: 10
icon: water
summary: "A stream is a named, typed, unbounded sequence of rows. How to declare one — schema, event time, lateness — in a file of its own, in configuration, over HTTP or in code, and how it is bound to a source."
audience: Everyone
keywords: [stream, schema, declare, config/streams, declaration file, origin, not applied, watch-interval, connector, pravaha.streams, event-time, out-of-orderness, allowed-lateness, allowedLateness, types, nullable, source binding, POST /api/v1/streams, catalog]
guide: continuous-queries#2-declaring-a-stream
related: [event-time-watermarks, sources-overview, source-filesystem, query-lifecycle, sql-reference]
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

## Declaring a stream in a file

The way `pravaha init` writes one (into its project's `conf/streams/`): **a YAML file per stream,
named after it**, in `config/streams/` (`$PRAVAHA_HOME/conf/streams/` under a home; `pravaha.config.streams-dir` to put it elsewhere). The
file holds what `pravaha.streams.<name>` would, plus an optional `source:` block holding what
`pravaha.sources.<name>` would -- so one file says what the stream is and where it comes from:

```yaml
# config/streams/txn.yaml -- the stream is called txn because the file is
schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"
event-time: event_time
out-of-orderness: 10s
source:
  plugin: filesystem
  options:
    path: data/incoming/txn.csv
    follow: "true"
```

It is read by the same binder as the inline block, so every key below means the same thing in both.
A file is stricter: an unknown key is refused (PRV-1064), a literal password, secret, token or key is
refused (PRV-1063 -- write `${ENV_NAME}`), and a name that would need brackets is refused (PRV-1061).
The same name in a file and inline is PRV-1062. `config/lookups/<name>.yaml` and
`config/sinks/<name>.yaml` work the same way, and a `source:` may name a shared connection with
`connector:` ([sources](/help/topics/sources-overview)).

**The folders are watched.** Every `pravaha.config.watch-interval` (`10m`; `off` reads them at start
only) the node looks again: **add a file and the stream is declared, with its source bound; take a
file out and the stream is removed; change one and it is replaced** -- no restart. The one exception:
a stream a registered query reads is never changed or removed under the query. The running
definition is kept, the node warns (PRV-1065, naming the file and the queries), and the stream is
shown as *not applied* -- in `pravaha streams describe`, `pravaha status`, the Catalog page and
`GET /api/v1/declarations` -- until those queries are dropped; the next look then applies it. A file
that is broken while the node runs is reported and ignored, and never takes the node down.

Every stream says where it came from: `origin` on `GET /api/v1/streams` and the ORIGIN column of
`pravaha streams` is `file:<path>`, `config` (inline) or `api`.

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

Declared here -- or in a file -- a stream comes back on every start. The keys are per stream on purpose: lateness is a
property of the **source** — a feed of mobile clients over a bad network and a scan of data at rest
have nothing in common — and a query over three streams gets three tolerances rather than the worst
of them.

!!! note "pravaha.watermark.out-of-orderness is the node's default"
    The per-stream `out-of-orderness` above overrides it, and a stream that declares an event time
    and no lateness of its own takes it. **It was read by nothing until 2026-09-20** (DOCX-6) —
    setting it changed no answer at all. Its default is the same 10 seconds a stream already took,
    so nothing moved for a deployment that had not set it.

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
        path: /opt/pravaha/data/incoming/txn.csv
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
curl -s -X POST http://localhost:18080/api/v1/streams \
     -H 'Content-Type: application/json' \
     -d '{"name": "txn",
          "schema": "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP",
          "eventTime": "event_time",
          "outOfOrderness": "PT10S"}'
curl -s http://localhost:18080/api/v1/streams
```

An optional `"allowedLateness": "PT1M"` declares the allowed lateness the same way.
`GET /api/v1/streams` reports each stream's schema, `eventTime`, `outOfOrderness`, `allowedLateness`
(`PT0S` when windows are final at close) and the `source` plugin feeding it — the plugin's name only,
never its options. Over HTTP the durations are ISO-8601 (`PT10S`); in YAML, `10s` works. An
`outOfOrderness` or an `allowedLateness` without an `eventTime` is refused: each is about an event
time, and there is none. A stream
declared over HTTP lasts until the node restarts; declare it in configuration to keep it.

A stream declared over HTTP (or with `pravaha streams declare`) can be registered over at once —
`pravaha register`, `CREATE CONTINUOUS QUERY`, any SDK — exactly like one from configuration
(DECLSTREAM-1: before 2.0.1 it validated and was then refused `PRV-2002 not found`). It has no source
binding unless `pravaha.sources` names it, so a query over it starts and waits for rows. Because the
declaration does not survive a restart, neither does a registration over it: at the next start
recovery refuses that query, and logs why, unless the stream is declared in configuration by then.

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
[late data and corrections](/help/topics/event-time-watermarks#late-data).

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

## Managing streams from the console {#managing-streams-from-the-console}

**Catalog → New stream** (and **Edit** on a stream declared by a file) is a form over exactly the
file above: a schema built a column at a time — a name, a type from the list (`DECIMAL(p,s)` typed
in), and *nullable* — the event-time column, out-of-orderness and allowed lateness, and a source: a
plugin and its options, or a [named connection](/help/topics/connections) and the options laid over
it. **The file is the single source of truth**: Save writes `config/streams/<name>.yaml` — the file a
person would write — into the node's streams folder, and the node applies it at once, not at its next
look.

- **Validate** is a dry run. The engine reads the file it would write, checks it as it checks every
  declaration file, and answers whether it accepts it, what uses the stream, and the **exact file** —
  shown under *Preview YAML*. Nothing is written.
- **A change to a stream a query reads is refused** (`PRV-1065`), and nothing is written: the page
  names the queries. Drop them first — or disable the stream instead.
- **Disable** writes `enabled: false` into the file. The stream stays declared and bound; its source
  is read from nothing, and the queries over it **keep running and receive nothing** until it is
  enabled again, when they resume from where their readers stood. Disabling is never refused for being
  in use — it is what you do to a stream a query reads.
- **Delete** shows the dry run first — what reads the stream — and is refused while anything does.
- **A secret option** (a key naming a password, secret, token or API key) is written as
  `${ENV_NAME}` and the variable set where the node runs; a literal value is refused (`PRV-1063`),
  as it is in a file written by hand.
- **Edit the file directly** (on Edit) changes the YAML itself and keeps its comments; the form
  rewrites the file from its fields.

Changing a stream needs the `admin` role or a grant to administer it; the buttons are not offered to
somebody the engine would refuse, and the engine checks again regardless. Every write is audited as
`config.stream.<action>` with who made it. The same writes are `pravaha streams create|edit|delete|enable|disable`
and `POST|PUT|DELETE /api/v1/streams/{name}` (each with `dryRun=true`). `POST /api/v1/streams` (no
name in the path) still declares a stream on the running node only, with no file and no source.

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
    it would write UNKNOWN into a boolean — `SELECT (status = 'ok') IS TRUE` plans. See [types](/help/topics/sql-reference#types-nulls-and-expressions).

## Where next

- [Event time and watermarks](/help/topics/event-time-watermarks) — what `event-time` and `out-of-orderness` actually do
- [Sources](/help/topics/sources-overview) — every shipped connector
- [Continuous queries and their lifecycle](/help/topics/query-lifecycle)
- The long form: [Streams, queries and SQL §2](/help/continuous-queries#2-declaring-a-stream)
