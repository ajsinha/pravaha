---
title: The Delta Lake source
slug: source-delta
category: sources
order: 50
icon: layers
summary: "Reads a Delta Lake table's snapshot, then follows its commits: added files arrive at +1 and removed files at -1, so a table's own history is already a Z-set changelog — through Delta Kernel, with no Spark."
badge: SOURCE
audience: Operators
keywords: [delta, delta lake, lakehouse, kernel, parquet, start.version, snapshot, commit, vacuum, deletion vectors, retraction]
guide: continuous-queries#21-every-source-type-configured
related: [sources-overview, zset-weights, source-feedfile, late-data]
---

The `delta` plugin reads a **Delta Lake table** as a continuous stream. It starts from a snapshot —
every row of the table as of one version — and then follows the transaction log: each new commit is
turned into rows as it appears.

The interesting property is how little it has to do. Delta rewrites whole files: an `UPDATE`, a
`DELETE` or a `MERGE` removes the files it touched and adds replacements. So the difference between
version *n* and version *n+1* is exactly a set of removed files and a set of added files — and emitting
the added files' rows at weight `+1` and the removed files' rows at weight `−1` **is** the Z-set delta
for that commit. Delta's storage semantics and Pravaha's algebra agree without an adapter. With
[postgres-cdc](/help/topics/source-postgres-cdc) and the [Kafka source](/help/topics/source-kafka)
reading a changelog, it is one of the shipped sources whose deletes and updates reach a view as
retractions without any help from you.

It is built on **Delta Kernel, not Spark**: the connector-facing library that understands the log,
checkpoints, protocol versions and column mapping, bringing Hadoop and Parquet with it but not a
cluster.

## At a glance

| | |
|---|---|
| Plugin name | `delta` |
| Module | `plugins/pravaha-plugin-delta` — in the server jar |
| Kind | stream source |
| Delivery guarantee | `EXACTLY_ONCE` — a (version, phase, file, row) offset resumes exactly; Delta versions are immutable |
| Replayable offsets | yes |
| Emits deletes | **yes**, as retractions of the rows of removed files |
| Before-image | not as a paired image: the previous value arrives as a retraction of the whole old row |
| Pushdown | none |
| Schema comes from | **the table** — there is no `schema` option to keep in step |
| Partitions | one reader |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `path` | yes | — | The table root: the directory holding `_delta_log/` |
| `start.version` | no | the latest snapshot | The version to start from. Its whole snapshot is emitted first, then every later commit. A value that is not a number is refused with PRV-5050 |
| `stream` | no | the table directory's name | The stream name the plugin reports |
| `event.time` | no | on a server, the stream's `event-time`; otherwise none | A `TIMESTAMP` column of the table whose value stamps each row's event time — what the watermark and every window run on. A column the table does not have, or that is not a Delta `TIMESTAMP`, is refused with PRV-5050. A NULL stamps zero. Without it every row is stamped zero; nothing is derived from commit or file times |
| `share.reader` | no | `true` | Read by the binding layer; an exactly-once source is never shared, so it has no effect |

## How a table becomes rows

1. **Snapshot.** The version named by `start.version` (or the latest) is read file by file; every row
   arrives at `+1`. Left at the default, history before the latest snapshot is not replayed — the
   stream begins with the table as it is now.
2. **Each later commit**, in version order: the rows of the files it **added**, at `+1`, then the rows of
   the files it **removed**, at `−1`. Either order sums to the same answer; a fixed one makes a replay
   byte-for-byte identical.
3. **Nothing new** is the ordinary idle case: the reader returns and is polled again.

Supported column types are Delta's `boolean`, `byte`, `short`, `integer`, `long`, `float`, `double`,
`decimal`, `string`, `binary`, `date`, `timestamp` and `timestamp_ntz`; anything else (a struct, an
array, a map) is refused with PRV-5051, naming the column, rather than dropped.

## A complete binding

The table was written by a Spark or Flink job with the same columns as the `trades` stream the help
uses. Because the schema comes from the table, the `pravaha.streams` block only has to agree with it:

```yaml
pravaha:
  streams:
    trades:
      schema: "trade_id:INT64,account:STRING,symbol:STRING,side:STRING,qty:INT64,price_cents:INT64,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 5s
  sources:
    trades:
      plugin: delta
      options:
        path: /warehouse/trading/trades
        start.version: "142"
```

`start.version: "142"` replays version 142's snapshot and everything since; leave it out to start
from now.

## A query over it

A running position per account and symbol is exactly the shape that needs retractions: when an
upstream job corrects a trade, Delta removes the file holding it and adds one with the fix, and the
view must forget the old quantity. Summing over a *view* is allowed without a window; over the stream,
use a window:

```sql
CREATE CONTINUOUS QUERY buys_by_symbol
    KEYED BY (symbol, window_end)
AS
SELECT symbol, window_start, window_end,
       SUM(qty)               AS shares,
       SUM(qty * price_cents) AS notional_cents,
       COUNT(*)               AS fills
FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE side = 'BUY'
GROUP BY symbol, window_start, window_end;
```

And a filter that follows corrections exactly, which works today regardless of event time:

```sql
CREATE CONTINUOUS QUERY block_trades
    KEYED BY (trade_id)
AS
SELECT trade_id, account, symbol, qty, price_cents
FROM trades
WHERE qty >= 10000;
```

Suppose version 143 appends a file holding trade 7 (`qty` 12,000), and version 144 is an upstream
`UPDATE` correcting it to 9,000. Version 144 removes that file and adds a rewritten one. The reader
emits the rewritten file's rows at `+1` (trade 7 at 9,000 — filtered out) and the old file's rows at
`−1` (trade 7 at 12,000 — which retracts it from the view). Every other row in the rewritten file
arrives once at `+1` and once at `−1` and cancels.

<!-- sql: read -->
```sql
SELECT trade_id, qty FROM block_trades WHERE trade_id = 7
```

After version 143:

```text
trade_id	qty
7	12000
1 row
```

After version 144:

```text
trade_id	qty
0 rows
```

A subscriber to `block_trades` saw the `−1` for trade 7 in the commit that carried version 144.

!!! warning "Windows need the stream's `event-time`"
    Each row is stamped with its `event.time` column's value, and the engine's watermark comes from
    those stamps. On a server the stream's declared `event-time` is handed to the source as
    `event.time`, so `buys_by_symbol` publishes its windows as the table's own times advance. A
    windowed query over a stream that declares none is refused at registration (`PRV-2002`); declare
    `event.time` alone, with no `event-time` on the stream, and every row is stamped zero instead, so
    the query registers and never publishes a window. A **retraction** carries the removed row's own time, so a correction to a
    window that has already closed is late — dropped, unless the stream declares allowed lateness.

## Pushdown

None. Kernel's scan builder takes a predicate and file-skipping from log statistics is the obvious next
step; declaring pushdown before implementing it would tell the engine filtering had happened when it
had not, so the plugin declares none.

## Delivery guarantee

`EXACTLY_ONCE`. A Delta version is immutable and its file list deterministic, so the offset — version,
phase (snapshot, additions, removals), file index, row — resumes exactly after a restore.

## TLS and credentials

The plugin opens `path` through Kernel's default Hadoop-backed engine; there are no `tls.*` or
credential options. A table on local disk or a mounted filesystem works as shown; reaching an object
store needs whatever the Hadoop filesystem for that scheme needs on the classpath and in its own
configuration, which this plugin does not manage.

## Pitfalls

!!! warning "Pitfall: VACUUM under a reader"
    A file the log still references but that `VACUUM` has deleted cannot be read, and its retractions
    cannot be reconstructed from anywhere. The reader stops with PRV-5053 naming the file. Keep the
    table's retention longer than the longest a query can be paused or behind, or restart the query
    from a later `start.version`.

!!! warning "Pitfall: deletion vectors"
    A table with deletion vectors enabled hides row-level deletes from file diffing — the file stays,
    with some rows marked gone. Reading it as if it had no vectors would keep deleted rows alive, so
    the reader refuses it with PRV-5055. Disable deletion vectors on tables Pravaha reads, or rewrite
    them (`REORG … APPLY (PURGE)` in Spark) first.

!!! note "Update-heavy tables are expensive this way"
    Rewriting a 100,000-row file to change one row yields 100,000 retractions and 100,000 insertions,
    99,999 pairs of which cancel. The *answer* is right; the *volume* is proportional to file size, not
    change size. Append-heavy tables never pay it. Delta's change data feed is the better reader for
    update-heavy tables, and Kernel exposes no public API for it yet.

!!! note "Not in the server jar"
    The first registration that reads the stream fails with PRV-5090, listing the plugins that are
    available, until the module is on the node's classpath.

## Where next

- [Weights and retractions](/help/topics/zset-weights) — why a removed file's rows at `−1` are exactly right
- [The feedfile source](/help/topics/source-feedfile) — files that only ever add
- [Sources overview](/help/topics/sources-overview) — the seven sources side by side
