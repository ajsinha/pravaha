---
title: The filesystem sink
slug: sink-filesystem
category: sinks
order: 20
icon: file-earmark-arrow-down
summary: "Appends each committed row to a delimited file. Append-only and at-least-once: it takes filters, projections and windows without lateness, and nothing that retracts."
badge: SINK
audience: Engineers
keywords: [filesystem, csv, file, delimited, append, flush.every.batch, null.literal, at-least-once]
guide: operations#one-engine-and-what-the-server-still-lacks
related: [sinks-overview, delivery-guarantees, source-filesystem, windows-worked]
---

The `filesystem` sink writes every row a query commits to one delimited text file, one line per row,
in the column order of its `schema`. It is the simplest sink there is, it ships inside the server
jar (the same plugin as the [filesystem source](/help/topics/source-filesystem)), and it is honest
about what a file can do: **append**. A file cannot express "replace the row for this key" or "that
row is withdrawn", so the engine refuses to point a query that revises its answer at it.

Use it for an audit feed, an alert log, a hand-off to a batch system that picks files up, or to see
what a query emits while you develop it.

## At a glance

| | |
|---|---|
| Plugin name | `filesystem` (as a sink, under `pravaha.sinks.<name>`) |
| Ships in | the server jar — nothing to add to the classpath |
| Accepts | `APPEND` only |
| Takes a revising query | **no** — refused with PRV-2041 |
| Keyed | no |
| Transactional / idempotent | no / no |
| Delivery | **at least once** — a restart repeats what was written after the last checkpoint |
| Batch size | unbounded: a commit is written as one batch |
| TLS | not applicable |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `path` | yes | — | The file to write. Parent directories are created when the sink opens |
| `schema` | yes | — | The row shape, `name:TYPE,...` (`?` suffix for nullable). The registered query's `SELECT` list must match it in order, name and type (PRV-8010) |
| `delimiter` | no | `,` | The field separator. Only its first character is used |
| `null.literal` | no | empty | What a NULL is written as |
| `append` | no | `true` | `true` keeps what the file already holds and appends to it, **across restarts too**. `false` empties the file every time the sink opens — a restart included — so it throws away everything written before |
| `flush.every.batch` | no | `true` | Flush the file after every batch, so a line a query committed is in the file when the commit returns. `false` leaves flushing to the buffer and to close |

## How values are written

| Column type | Written as |
|---|---|
| `INT8`, `INT16`, `INT32`, `INT64` | the integer, e.g. `2500` |
| `FLOAT32`, `FLOAT64` | Java's decimal rendering, e.g. `21.5` |
| `BOOLEAN` | `true` / `false` |
| `STRING`, `BYTES` | the text, **unquoted and unescaped** |
| `TIMESTAMP` | nanoseconds since the Unix epoch, UTC, e.g. `1789812000000000000` |
| `DATE` | days since the epoch |
| `TIME` | nanoseconds since midnight |
| NULL | `null.literal` |

There is no header line. Nothing is quoted, so a string containing the delimiter or a newline makes
a line a reader will split wrongly — choose a delimiter your data cannot contain.

## A complete example: large payments to a file

The node's configuration — a stream, its source, and the sink:

```yaml
pravaha:
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
    interval: 1m
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
        null.literal: ""
```

The query — its `SELECT` list is exactly the sink's schema, and a filter never revises its answer:

```sql
CREATE CONTINUOUS QUERY big_txn_feed
    KEYED BY (txn_id)
    WRITING TO large_payments
AS
SELECT txn_id, user_id, amount
FROM txn
WHERE amount > 1000;
```

With these rows arriving in `txn.csv`:

```text
txn_id,user_id,merchant,amount,currency,status,event_time
1,ann,m-17,250,EUR,OK,2026-09-19T09:00:01Z
2,bob,m-03,4200,EUR,OK,2026-09-19T09:00:02Z
3,ann,m-17,1800,EUR,,2026-09-19T09:00:04Z
4,cat,m-44,90,USD,OK,2026-09-19T09:00:05Z
```

`large_payments.csv` holds, one commit later:

```text
2,bob,4200
3,ann,1800
```

And the view is readable as ever:

<!-- sql: read -->
```sql
SELECT txn_id, user_id, amount FROM big_txn_feed WHERE user_id = 'ann'
```

```text
txn_id | user_id | amount
-------+---------+-------
     3 | ann     |   1800
```

## A windowed example: per-merchant totals per minute

A tumbling window with no allowed lateness emits each window **once**, when the watermark passes its
end, and never revises it — so it is append-only and a file can take it.

```yaml
pravaha:
  sinks:
    merchant_minutes:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/outgoing/merchant_minutes.tsv
        schema: "merchant:STRING,window_start:TIMESTAMP,window_end:TIMESTAMP,payments:INT64,total:INT64"
        delimiter: "\t"
        append: "true"
```

```sql
CREATE CONTINUOUS QUERY merchant_minutes
    KEYED BY (merchant, window_end)
    WRITING TO merchant_minutes
AS
SELECT merchant, window_start, window_end,
       COUNT(*)    AS payments,
       SUM(amount) AS total
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end;
```

Each line is written when its minute closes — that is, once the watermark (the latest event time
seen minus the stream's 10 s out-of-orderness) passes the window's end. Timestamps are nanoseconds:

```text
m-17	1789808400000000000	1789808460000000000	2	2050
m-03	1789808400000000000	1789808460000000000	1	4200
m-44	1789808400000000000	1789808460000000000	1	90
```

A query whose stream has no `event-time` declared never advances its watermark, so no window ever
closes and nothing is ever written. That is the most common "the file stays empty" cause.

## What is refused

A query that revises its answer cannot write to a file. A windowed `SUM(amount)` is fine; the same
aggregate **without** a window replaces its single result on every row, and is refused at
registration:

```sql
-- Plans. Registering it WRITING TO a filesystem sink is refused with PRV-2041.
SELECT COUNT(*) AS payments, SUM(amount) AS total FROM txn
```

```text
PRV-2041  sink 'merchant_minutes' accepts [APPEND], but this query needs one of [UPSERT, RETRACT].
```

The check also asks each stream's source whether it deletes. A query over postgres-cdc, Delta or a
[Kafka source](/help/topics/source-kafka) reading a changelog passes retractions on even through a
plain filter or a join, so it is refused the same way. The one retracting source the check cannot see
is the filesystem source with `op.column`, whose plugin declares no deletes: a file writes such a
retraction as an ordinary line, with no weight to tell it apart. Keep it away from this sink.

Point such a query at [`jdbc-sink`](/help/topics/sink-jdbc),
[`aerospike-sink`](/help/topics/sink-aerospike) or [`kafka-sink`](/help/topics/sink-kafka), which take a
retraction — as a delete, or a tombstone.

## Delivery

**At least once.** After a crash or a restart the query resumes from its last checkpoint and replays
what came after it; the file, which the sink reopens for append, keeps everything written before and
receives those rows again below it. A view commit carries no sequence number the
sink could deduplicate on, so expect repeats after a restart and deduplicate downstream by the
query's key if it matters. Nothing is ever taken back out of the file: what was written stays
written.

The registration logs exactly that:

```text
query 'big_txn_feed' writes to sink 'large_payments', at-least-once: the sink appends and is not transactional, so a restart delivers again what was written after the last checkpoint; a view commit carries no sequence to deduplicate the repeat on
```

## Pitfalls

!!! danger "Pitfall: `append: false` empties the file at every restart"
    The default, `append: true`, keeps the file across restarts: a restored view does not send again
    what it wrote before its checkpoint, so that output exists only in the file. `append: "false"`
    empties the file every time the sink opens, a restart included, and so throws that output away.
    Use it only where each start should write a fresh file.

!!! note "`pravaha-engine run` replaces its output"
    A one-shot `pravaha-engine run --out` writes the whole answer in one go, so it opens its output with
    `append: false`: running it twice leaves one answer in the file, not two.

!!! warning "Pitfall: a delimiter that appears in the data"
    Nothing is quoted or escaped. A merchant name containing a comma produces a line with one field
    too many. Use a delimiter the data cannot contain, such as a tab or `|`.

!!! warning "Pitfall: DECIMAL columns"
    The encoder has no case for `DECIMAL`, so a decimal column is written as `null.literal`. The
    engine refuses decimal arithmetic anyway; carry money as integer minor units (`amount` in cents)
    and it is written exactly.

!!! note "Two writers, one file"
    Two bindings with the same `path` are two writers interleaving lines. Give each binding its own
    file.

## Where next

- [How a query writes to a sink](/help/topics/sinks-overview) — the shape check, detach, and
  `GET /api/v1/sinks`
- [Delivery guarantees](/help/topics/delivery-guarantees)
- [The filesystem source](/help/topics/source-filesystem) — the same plugin, reading
