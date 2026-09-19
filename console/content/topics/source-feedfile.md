---
title: The feedfile source
slug: source-feedfile
category: sources
order: 30
icon: folder2-open
summary: "A drop directory of CSV or Parquet files arriving over time: each read once, in a declared order, only when it is complete — then archived, or quarantined when it will not decode."
badge: SOURCE
audience: Operators
keywords: [feed, directory, drop, batch, csv, parquet, glob, completion, marker, stable, archive, quarantine, sftp, eod]
guide: continuous-queries#21-every-source-type-configured
related: [sources-overview, source-filesystem, source-delta, event-time-watermarks, dead-letters]
---

The `feedfile` plugin reads the **batch-feed shape**: a directory into which files land over time —
an end-of-day positions export, a partner's hourly SFTP drop, a Parquet extract from a warehouse.
Each file is read once, in an order you declare, only once it is judged completely written, and then
optionally moved to an archive directory. A file that will not decode can be moved to a quarantine
directory instead of stopping the feed.

What separates it from pointing the [filesystem source](/help/topics/source-filesystem) at a file is
the part that goes wrong in production: **a file that has appeared is not a file that has finished
arriving.** A reader that opens a 400 MB transfer after its first megabyte gets a perfectly decodable,
perfectly short file, and no schema check can tell.

## At a glance

| | |
|---|---|
| Plugin name | `feedfile` |
| Module | `plugins/pravaha-plugin-feedfile` — **not in the server jar**; see [sources overview](/help/topics/sources-overview#getting-a-plugin-onto-a-node) |
| Kind | stream source |
| Formats | `csv` (quoted fields handled) and `parquet` (columns matched by name) |
| Delivery guarantee | **depends on configuration**: `EXACTLY_ONCE` with `completion: marker` or `immediate` and no `archive.dir`; otherwise `AT_LEAST_ONCE` |
| Replayable offsets | only in the exactly-once configuration above |
| Emits deletes / before-image | no / no — a feed states what *is*, not what changed |
| Pushdown | none |
| Schema comes from | the `schema` option you write |
| Partitions | one reader for the directory |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `dir` | yes | — | The drop directory |
| `schema` | yes | — | `name:TYPE,…`, the same grammar as every schema string. For CSV, in file column order; for Parquet, matched to the file's columns **by name** |
| `glob` | no | `*.csv` | Which files in `dir` belong to this feed |
| `format` | no | `parquet` if `glob` ends in `.parquet`, else `csv` | `csv` (or `delimited`) or `parquet`. Anything else is refused with PRV-5065 |
| `completion` | no | `stable` | When a file is ready: `marker`, `stable` or `immediate` — see below |
| `completion.marker.suffix` | no | `.done` | With `completion: marker`, the sibling file the producer writes last: `positions-0919.csv.done` beside `positions-0919.csv` |
| `completion.quiet.ms` | no | `5000` | With `completion: stable`, how long a file must go unmodified before it is read |
| `order` | no | `name` | `name` (lexical) or `mtime` (modification time, ties broken by name). Anything else is refused |
| `archive.dir` | no | none — files stay put | Where a fully read file (and its marker) is moved |
| `quarantine.dir` | no | none — a bad file stops the feed | Where a file that fails to decode is moved, so the feed carries on |
| `stream` | no | the binding's name | The stream name the plugin reports |
| `delimiter` | no | `,` | CSV only. One character |
| `skip.header` | no | `false` | CSV only. Skip each file's first line |
| `null.literal` | no | `""` | CSV only. The text meaning NULL |
| `event.time` | no | on a server, the stream's `event-time`; otherwise none | A `TIMESTAMP` column of `schema` whose value stamps each row's event time — what the watermark and every window run on. Naming a column that is not there, or is not a `TIMESTAMP`, is refused with PRV-5065. A NULL in it stamps zero. Without it every row is stamped zero |

### Choosing `completion`

| Value | A file is ready when | Trust it when |
|---|---|---|
| `marker` | its sibling `<file><suffix>` exists | **Always** — the producer made the promise. The only safe choice for a remote producer that can stall mid-write |
| `stable` | it has not been modified for `completion.quiet.ms` | The producer never pauses longer than the quiet period. A stalled transfer looks exactly like a finished one, so the plugin declares itself at-least-once |
| `immediate` | it is seen | Files arrive by **atomic rename** — written elsewhere and `mv`'d in. True of most local producers and no remote ones |

### What a CSV file may contain

The CSV reader handles the three things real partner files do: a delimiter inside a quoted field, a
quoted field, and a doubled quote inside one. It does **not** handle a newline inside a quoted field:
a record may not span lines, because resuming by line is what makes the source replayable. A feed that
needs embedded newlines should be Parquet.

Unlike the filesystem source, a CSV `TIMESTAMP` here must be an **integer of nanoseconds since the
epoch** — `1789808400000000000`, not `2026-09-19T09:00:00Z`. Convert when you produce the file.

## A complete binding

A Parquet export of orders, dropped by a warehouse job that writes a marker last:

```yaml
pravaha:
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 30s
  sources:
    orders:
      plugin: feedfile
      options:
        dir: /var/feeds/orders
        glob: "orders-*.parquet"
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        completion: marker
        completion.marker.suffix: ".done"
        order: name
        quarantine.dir: /var/feeds/orders-bad
```

Leaving `archive.dir` unset and using `marker` keeps the offsets replayable, so this feed is
exactly-once across a restart. A CSV variant from an SFTP partner that cannot write markers:

```yaml
pravaha:
  sources:
    orders:
      plugin: feedfile
      options:
        dir: /var/sftp/partner-a/orders
        glob: "orders_*.csv"
        format: csv
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        skip.header: "true"
        completion: stable
        completion.quiet.ms: "30000"
        order: mtime
        archive.dir: /var/sftp/partner-a/done
        quarantine.dir: /var/sftp/partner-a/bad
```

## A query over it

Revenue per region per five minutes, the question the view `region_revenue` answers across the help:

```sql
CREATE CONTINUOUS QUERY feed_region_revenue
    KEYED BY (region, window_end)
AS
SELECT region, window_start, window_end,
       SUM(amount) AS revenue,
       COUNT(*)    AS orders
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end;
```

A filter needs no event time and works today exactly as written:

```sql
CREATE CONTINUOUS QUERY cancelled_orders
    KEYED BY (order_id)
AS
SELECT order_id, customer_id, region, amount
FROM orders
WHERE status = 'CANCELLED';
```

<!-- sql: read -->
```sql
SELECT region, COUNT(*) AS cancelled, SUM(amount) AS cancelled_value
FROM cancelled_orders
GROUP BY region
```

With three files each carrying one cancelled EU order of 100 and one cancelled US order of 250:

```text
region	cancelled	cancelled_value
EU	3	300
US	3	750
2 rows
```

The `GROUP BY` without a window is refused on a stream (PRV-2050) and allowed here because it reads a
maintained view, whose scan ends.

!!! warning "Windows need the stream's `event-time`"
    Each row is stamped with the value of its `event.time` column, and the engine's watermark is
    derived from those stamps. On a server the stream's declared `event-time` (`event_time` above) is
    handed to the source as `event.time`, so `feed_region_revenue` closes its windows as the files'
    own times advance. Without either, every row is stamped zero: the windowed query plans, registers
    and reads every file, and its windows wait for a watermark that never moves — an empty view under
    a `RUNNING` query. Declare `event-time` on the stream (or `event.time` on an embedded binding).

## What happens to each file

1. The directory is listed; files matching `glob` that are **ready** under `completion` are sorted
   by `order`, ties broken by name, so two files with one timestamp still have a total order.
2. The first unread file is opened and decoded record by record, each at weight `+1`.
3. At its end it is moved to `archive.dir` (with its marker) if one is set; otherwise it stays, and
   the offset remembers it was read.
4. A decode failure moves the file to `quarantine.dir` if one is set and the feed continues with the
   next file. **Records already emitted from that file stand** — they were well-formed, and retracting
   them would need a before-image the feed does not have. Without `quarantine.dir` the failure stops
   the source (PRV-5062).

## Pushdown

None declared. Every record of every file is decoded and the engine applies the `WHERE`.

## Delivery guarantee

Computed from the configuration, not fixed:

| `completion` | `archive.dir` | Guarantee | Why |
|---|---|---|---|
| `marker` or `immediate` | unset | `EXACTLY_ONCE` | The offset names a file that is still there and a record within it; a restart resumes exactly |
| any | set | `AT_LEAST_ONCE` | An archived file has left the directory, so an offset naming it can no longer be resumed |
| `stable` | any | `AT_LEAST_ONCE` | "Stopped changing" cannot tell a finished transfer from a stalled one |

## TLS and credentials

Not applicable: the directory is local (or a mounted filesystem). Secure the transfer that fills it.

## Pitfalls

!!! warning "Pitfall: `stable` on a remote producer"
    `stable` is a guess. A partner whose SFTP session pauses for longer than `completion.quiet.ms`
    delivers a truncated file that decodes cleanly. If the producer can write a `.done` file last,
    use `marker`; if it can rename into place, `immediate` is safe.

!!! warning "Pitfall: `order: mtime` and copied files"
    A copy tool that preserves modification times, or a restore from backup, reorders a feed ordered by
    `mtime`. Daily deltas applied out of order give a wrong answer with no error anywhere. Prefer names
    that sort — `orders-2026-09-19T0900.csv` — and `order: name`.

!!! note "Parquet columns are matched by name, CSV columns by position"
    A partner who reorders Parquet columns between releases changes nothing. A partner who reorders
    CSV columns silently moves values into other columns if the types happen to parse.

!!! note "Not in the server jar"
    `sources bound` in the log shows the binding, but the first registration that reads the stream
    fails with PRV-5090 listing the plugins that *are* available until the module is on the node's
    classpath.

## Where next

- [The filesystem source](/help/topics/source-filesystem) — one file, followed, with event time and retractions
- [The Delta source](/help/topics/source-delta) — a table whose commits are already a changelog
- [Dead letters](/help/topics/dead-letters) — keeping undecodable records instead of stopping
- [Sources overview](/help/topics/sources-overview) — choosing between the seven
