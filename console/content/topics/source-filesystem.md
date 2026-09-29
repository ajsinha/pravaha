---
title: The filesystem source
slug: source-filesystem
category: sources
order: 20
icon: file-earmark-text
summary: "One delimited file, read once or followed like tail -f — the source inside the server jar, the only one that can carry retractions today, and the one every example uses."
badge: SOURCE
audience: Operators
keywords: [csv, delimited, file, follow, tail, op.column, op.delete.values, skip.header, null.literal, event.time, delimiter, rotation]
guide: continuous-queries#21-every-source-type-configured
related: [sources-overview, source-feedfile, zset-weights, event-time-watermarks, sink-filesystem]
listed_on: sources-overview
---

The `filesystem` plugin reads **one delimited text file** and turns each line into a row of a
stream. It is the simplest source Pravaha has, the only one built into the server jar, and the one
every example and integration test uses as its known-good input — so it is where to start, and
where to go back to when you want to prove whether a problem is in your query or in your store.

It reads a file once and stops, or — with `follow: true` — keeps reading as the file grows, the way
`tail -f` does. And it is the one shipped source that can deliver a **retraction** written by you
into the data: name an operation column and a row can withdraw what an earlier row inserted.

## At a glance

| | |
|---|---|
| Plugin name | `filesystem` |
| Module | `plugins/pravaha-plugin-filesystem` — **inside the server jar**; nothing to add |
| Kind | stream source (also a sink: see [the filesystem sink](/help/topics/sink-filesystem)) |
| Delivery guarantee | `EXACTLY_ONCE` — a byte offset resumes exactly |
| Replayable offsets | yes — the line number; a restart from a checkpoint resumes where it stopped |
| Emits deletes | declared `false`, but see `op.column` below: a configured operation column does carry retractions |
| Before-image | no |
| Pushdown | none — every line is read and the engine filters |
| Schema comes from | the `schema` option you write (the file has no types of its own) |
| Partitions | one per file |
| Shared between queries | no — an exactly-once source is read once per query (see [sources overview](/help/topics/sources-overview)) |

## Options

Every option goes under the binding's `options:` block. Values are strings; quote `"true"` if your
YAML tooling is fussy.

| Option | Required | Default | What it does |
|---|---|---|---|
| `path` | yes | — | The file to read. Must be readable when the first query that needs it registers, or the registration fails with PRV-5040 naming the absolute path |
| `schema` | yes | — | `name:TYPE,name:TYPE,…` in file column order. Types: `BOOLEAN`, `INT8`, `INT16`, `INT32`, `INT64`, `FLOAT32`, `FLOAT64`, `STRING`, `BYTES`, `DATE`, `TIME`, `TIMESTAMP`; a trailing `?` makes a column nullable. Must agree with `pravaha.streams.<name>.schema` |
| `event.time` | no | on a server, the stream's declared `event-time` | The column holding each row's own time. **Needed by any windowed query** — see the first pitfall. Must name a column of `schema`, or configuration fails with PRV-5040 |
| `delimiter` | no | `,` | Exactly one character. `"\t"` for TSV, `"|"` for pipe-separated. Anything longer is refused |
| `skip.header` | no | `false` | Skip the first line |
| `null.literal` | no | `""` | The text that means NULL. With the default an empty field is NULL; set `NULL` or `\N` if your producer writes one |
| `follow` | no | `false` | `false`: read to the end and stop — a bounded read. `true`: end of file is not end of stream; appended lines keep arriving |
| `op.column` | no | none | A column whose value says whether the row inserts or retracts. Without it every row is an insertion (weight `+1`) |
| `op.delete.values` | no | `D,DELETE,-,-1` | The values of `op.column` that mean *retract* (weight `−1`). Anything else is an insertion |
| `share.reader` | no | `true` | Read by the engine's binding layer, not the plugin. Has no effect here, because an exactly-once source is never shared |

### What a line may contain

- **Fields are split on the delimiter, and that is all.** There is no quoting: a comma inside a
  field is a field boundary. If your data has embedded delimiters, use a different delimiter, or
  use the [feedfile source](/help/topics/source-feedfile), whose CSV reader handles quotes.
- **Every line must carry every column**, empty fields included — `1,ann,,` is four fields, the
  last two empty (and so NULL with the default `null.literal`).
- **Dates and times are written the way you would write them.** `DATE` takes `2026-09-19`, `TIME`
  takes `09:30:00`, and `TIMESTAMP` takes ISO-8601 — `2026-09-19T09:30:00Z`,
  `2026-09-19T09:30:00+05:30`, or `2026-09-19T09:30:00` (read as UTC, never as the machine's zone).
  A bare integer is taken as the engine's own unit: days for a `DATE`, nanoseconds for `TIME` and
  `TIMESTAMP`. That is what the filesystem *sink* writes, so a file round-trips.
- **The file must be UTF-8.** One invalid byte anywhere fails the whole read at line 0 (PRV-5040),
  including inside a `BYTES` column; binary payloads belong in base64 in a `STRING` column.
- Empty lines are skipped.

## A complete binding

A node is described by three blocks. `pravaha.streams` says what the stream *is*, so a query can be
planned against it; `pravaha.sources` says where its rows come from. The schema is written twice —
once for the catalogue, once for the plugin — and the two must agree.

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
```

And the file it reads:

```text
txn_id,user_id,merchant,amount,currency,status,event_time
1,ann,acme,1200,GBP,COMPLETED,2026-09-19T09:00:05Z
2,bob,acme,300,GBP,COMPLETED,2026-09-19T09:00:40Z
3,ann,zenith,80,GBP,,2026-09-19T09:01:10Z
4,cat,acme,2500,GBP,COMPLETED,2026-09-19T09:02:00Z
```

`txn_id 3` has an empty `status`, which the default `null.literal` reads as NULL — allowed because
the schema says `status:STRING?`.

When the node starts it logs what it read from this — two lines of this shape, naming the option
keys it received (never their values) — and they are the first thing to check when rows do not
arrive:

```text
streams declared in configuration: [txn]
sources bound: [txn <- filesystem[path, schema, event.time, skip.header, follow]]
```

An option missing from that list was not read — usually because it sits one level too high, outside
`options:`.

## A query over it

A filter needs no event time at all:

```sql
CREATE CONTINUOUS QUERY large_txn
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, merchant, amount
FROM txn
WHERE amount >= 1000;
```

<!-- sql: read -->
```sql
SELECT txn_id, user_id, amount FROM large_txn
```

With the four lines above:

```text
txn_id	user_id	amount
1	ann	1200
4	cat	2500
2 rows
```

A windowed aggregate needs the event time — this one counts each merchant's sales per minute:

```sql
CREATE CONTINUOUS QUERY merchant_minute
    KEYED BY (merchant, window_end)
AS
SELECT merchant, window_start, window_end,
       COUNT(*)    AS sales,
       SUM(amount) AS takings
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end;
```

The window `[09:00, 09:01)` closes when the watermark — the highest event time seen, less the
stream's `out-of-orderness` of ten seconds — passes 09:01. With the file above that happens once
`txn_id 4` (09:02:00) has been read, so `acme`'s first minute (two sales, 1,500) and nothing else is
published; `zenith` at 09:01:10 and `acme` at 09:02:00 wait for later rows.

<!-- sql: read -->
```sql
SELECT merchant, sales, takings FROM merchant_minute WHERE merchant = 'acme'
```

```text
merchant	sales	takings
acme	2	1500
1 row
```

Append a line dated `09:03:30Z` and the 09:01 and 09:02 windows close and appear — with
`follow: true` nothing needs restarting.

## Retractions from a file: `op.column`

Without an operation column a file is an append-only log: every row has weight `+1`. With one, a row
can withdraw an earlier one, which is how a CSV becomes a real Z-set source. The operation column is
an ordinary column: it is declared in both schemas, it must be present on every line, and it stays
selectable.

```yaml
pravaha:
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP,op:STRING"
      event-time: event_time
      out-of-orderness: 30s
  sources:
    orders:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/incoming/orders.csv
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP,op:STRING"
        event.time: event_time
        skip.header: "true"
        op.column: op
        op.delete.values: "D,DELETE,-,-1"
```

```text
order_id,customer_id,region,amount,status,event_time,op
10,c1,EU,500,OPEN,2026-09-19T09:00:00Z,I
11,c2,EU,700,OPEN,2026-09-19T09:00:10Z,I
12,c3,US,900,OPEN,2026-09-19T09:00:20Z,I
11,c2,EU,700,OPEN,2026-09-19T09:00:10Z,D
11,c2,EU,650,OPEN,2026-09-19T09:00:10Z,I
```

The fourth line retracts the second exactly (a retraction must repeat the row it withdraws), and the
fifth inserts its replacement — an update is a retraction plus an insertion, there is no third kind.

```sql
CREATE CONTINUOUS QUERY open_orders
    KEYED BY (order_id)
AS
SELECT order_id, region, amount FROM orders WHERE status = 'OPEN';
```

<!-- sql: read -->
```sql
SELECT order_id, region, amount FROM open_orders
```

```text
order_id	region	amount
10	EU	500
12	US	900
11	EU	650
3 rows
```

A subscriber to `open_orders` saw order 11 arrive at 700 (`+1`), leave (`−1`) and return at 650
(`+1`); a reader of the view sees only the current row. See
[weights and retractions](/help/topics/zset-weights).

## Following a file

With `follow: true`:

- A line becomes a row **only once its newline has arrived**, so a writer caught mid-line never
  produces half a record.
- A file that is **rotated** (a different file at the path) or **truncated** (smaller than what was
  already read) is reopened and read from the start of its replacement. A file that only grew is
  never reopened, because that would replay everything already delivered.
- A file that is **momentarily missing**, mid-rotation, is not an error: the next poll looks again.
- Each followed file holds **one open file descriptor per bound query**. A node binding many
  followed files can meet `ulimit -n`; the error says it is the process's descriptor limit, not the
  file.

Without `follow` the read ends at end of file, the view reaches its final answer, and stays there.

## Pushdown

None. The plugin declares no pushdown kinds, so the engine reads every line and applies the `WHERE`
itself. That is cheap for a local file; for a large one, pre-filter it where it is produced.

## Delivery guarantee

`EXACTLY_ONCE`. The offset is a line number that genuinely resumes: after a crash the node restores
the last checkpoint and reads on from the line it recorded, so no row is counted twice and none is
lost — provided checkpointing is on (`pravaha.checkpoint.directory`; see
[checkpoints and recovery](/help/topics/checkpoints-recovery)) and the file was not rewritten in
between. A rotated file resumes from its replacement's start.

## TLS and credentials

Not applicable: the file is local. The file must be readable by the user the node runs as; nothing
else is checked.

## Pitfalls

!!! warning "Pitfall: a windowed query that emits nothing"
    The plugin decodes with its own copy of the schema, and only `event.time` in its options tells it
    which column carries each row's time. On a **server** node you need not write it: when
    `pravaha.streams.<name>.event-time` is declared and the binding has no `event.time`, the node
    passes the declared column down for you (the `sources bound` log line then lists `event.time`
    last). Everywhere else — the embedded engine, `pravaha-engine run` — set it yourself. Without it every
    row carries the time it was *read*, the watermark runs at wall-clock, and rows dated in the past
    are dropped as late: the query reports `RUNNING`, ingests every row, and its view stays empty.

!!! warning "Pitfall: options one level too high"
    `path`, `schema` and the rest go under `options:`. A key written directly under the source is not
    read and not reported, and the node starts and ingests nothing.

!!! warning "Pitfall: one bad line stops the file"
    A line that will not decode — a letter where an `INT64` belongs, one field too few — fails the read
    with PRV-5040 naming the line and column, and without a dead-letter queue the source stops there.
    Set `pravaha.dlq.directory` and the line is written there with its reason while the source reads
    on. See [dead letters](/help/topics/dead-letters).

!!! note "The two schemas must agree"
    `pravaha.streams.txn.schema` is what queries are planned against; `options.schema` is what the
    plugin decodes with. They are read by different components and are not compared for you. If they
    disagree, values land in the wrong columns.

!!! note "`DECIMAL(p,s)` cannot be declared here"
    The schema string is split on commas before types are parsed, so `DECIMAL(10,2)` is cut in half
    and refused with PRV-5040. Carry money as integer minor units (`amount_cents:INT64`).

!!! note "`-1` is a delete marker by default"
    `op.delete.values` defaults to `D,DELETE,-,-1`. If your operation column holds numeric codes
    where `-1` means something else, set the list explicitly.

## Where next

- [Sources overview](/help/topics/sources-overview) — the three configuration blocks, and how to choose a source
- [The feedfile source](/help/topics/source-feedfile) — a directory of files arriving over time, with quoted CSV and Parquet
- [Event time and watermarks](/help/topics/event-time-watermarks) — why `event.time` decides whether a window ever closes
- [The filesystem sink](/help/topics/sink-filesystem) — writing a view's changes back to a file
