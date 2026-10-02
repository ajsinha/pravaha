---
title: The Delta sink — a lakehouse table that holds the answer
slug: sink-delta
category: sinks
order: 50
icon: layers
summary: "delta-sink maintains a query's answer in a Delta Lake table — upserting and removing by key, or appending a changelog — as one Delta commit per checkpoint, through Delta Kernel with no Spark."
badge: SINK
audience: Engineers
keywords: [delta sink, delta-sink, delta lake, lakehouse, kernel, parquet, upsert, changelog, exactly once, exactly-once, transaction.id, key.columns, staging.dir, OPTIMIZE, VACUUM, small files, copy-on-write, merge]
guide: connectors#a-transactional-sink-on-a-format-with-no-delete-delta
related: [sinks-overview, delivery-guarantees, source-delta, sink-jdbc, sink-kafka, checkpoints-recovery, zset-weights]
listed_on: sinks-overview
---

`delta-sink` keeps a **Delta Lake table** equal to a query's view. Each row the query commits is
written or replaced by its key; each retraction removes the record its key names. Whatever reads
your lakehouse — Spark, Trino, DuckDB, `delta-rs`, a warehouse that mounts Delta — reads a
maintained answer, without anything scheduled to refresh it.

It is built on **Delta Kernel, not Spark**, like the [Delta source](/help/topics/source-delta): the
transaction log, the protocol versions and the Parquet writing are Kernel's, and a node needs no
cluster to keep a table current. On a node that takes checkpoints the Delta sink is **exactly
once**: every change the engine commits reaches the table exactly once, a checkpoint's changes
become visible together, and a crash neither loses nor repeats a row. The price is that the table
trails the view by up to one checkpoint interval.

## At a glance

| | |
|---|---|
| Plugin name | `delta-sink` (under `pravaha.sinks.<name>`) |
| Ships in | the `pravaha-plugin-delta` jar, beside the `delta` source. Kernel brings Hadoop and Parquet with it, which is why the server jar does not carry it |
| Table | a directory with a `_delta_log`. **The sink creates it** with the binding's schema if it is not there, and never alters one that is |
| Accepts | `mode: upsert` (default): `UPSERT`, `RETRACT` — any query. `mode: changelog`: `APPEND`, `RETRACT` |
| Keyed | in upsert mode, by `key.columns`, which must be the view's key |
| Transactional | yes, by default (`transactional: true`) |
| Delivery | **exactly once** with `transactional: true` and `pravaha.checkpoint.directory` set; effectively once (upsert) or at least once (changelog) otherwise |
| Rows per batch | at most 1,000 per write |
| Partitioned tables | yes — name the columns with `partition.columns`; each row is filed under its partition and the log records the values |
| Not supported | writing deletion vectors, upsert into a table whose files carry them (`PRV-5055`), schema evolution, compaction |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `path` | yes | — | The table root. A local path, or any filesystem the plugin's Hadoop configuration can reach |
| `schema` | yes | — | The row shape, `name:TYPE,...` (`?` for nullable). Must match the registered query's `SELECT` list in order, name and type (PRV-8010), and the table's own columns (PRV-5057) |
| `key.columns` | in upsert mode | — | Comma-separated key columns. Must be the registration's key; may not be floating-point or nullable. Refused in changelog mode |
| `mode` | no | `upsert` | `upsert` keeps the table equal to the view; `changelog` appends every change with `_op` and `_weight` |
| `transactional` | no | `true` | `true` stages writes and commits them per checkpoint; `false` commits each batch on its own |
| `transaction.id` | no | the binding's name | The Delta application id each commit is recorded under, and the staging directory's name. Letters, digits, dot, underscore, hyphen. **One writer per id** |
| `staging.dir` | no | `<path>/_pravaha_sink` | Where a checkpoint's changes wait. Inside the table by default, so it travels with it; the leading underscore is what Delta's `VACUUM` skips |
| `create` | no | `true` | `false` refuses to write unless the table already exists |

Types map to Delta as the source maps them back: `BOOLEAN`, `INT8`, `INT16`, `INT32`, `INT64`,
`FLOAT32`, `FLOAT64`, `STRING`, `BYTES`, `DATE`, `TIMESTAMP` and `DECIMAL(p,s)`. **`TIME` is
refused** with PRV-5051 — Delta has `DATE` and `TIMESTAMP` and nothing for a time of day, and a
`BIGINT` of nanoseconds is not a substitute any Delta reader understands.

## A complete example: hourly spend per user, in the warehouse

```yaml
pravaha:
  sinks:
    spend_lake:
      plugin: delta-sink
      options:
        path: /warehouse/spend_by_user
        schema: "user_id:STRING,window_end:TIMESTAMP,total:INT64"
        key.columns: user_id,window_end
        mode: upsert
        transactional: "true"
```

```sql
CREATE CONTINUOUS QUERY spend_by_user
    KEYED BY (user_id, window_end)
    WRITING TO spend_lake
AS
SELECT user_id,
       TUMBLE_END(event_time, INTERVAL '1' HOUR) AS window_end,
       SUM(amount)                               AS total
FROM txn
GROUP BY user_id, TUMBLE(event_time, INTERVAL '1' HOUR);
```

The table is created on the first open if it is not there, with exactly those three columns. From
then on the sink writes to it and never changes its shape.

## Upsert mode: a merge, because Delta has no delete

A Delta data file is immutable. The sink does not write deletion vectors, so removing a row means
**rewriting the file that holds it** (and upsert refuses a table whose files already carry deletion
vectors, `PRV-5055`, because a rewrite would bring the deleted rows back). So a commit in upsert mode is a
copy-on-write merge:

1. The checkpoint's changes are collapsed by key. Of everything that happened to one key between
   checkpoints, only the last decides what the table holds, so a key that changed a hundred times is
   written once.
2. The table's data files are read **through their key columns alone** to find the ones holding a
   key that is about to change. Parquet is columnar, so this reads the key column and not the rows.
3. Each such file is rewritten without those rows, and the commit removes the old file and adds the
   new one. A file holding no affected key is not read past its key column and not rewritten.
4. The rows with a positive weight are written as one more file. A retraction contributes no row,
   which is how the record ends up gone.

!!! warning "A commit costs in proportion to the table, not to the change"
    Reading every file's key column, and rewriting whole files to remove a row from them, is the
    price of exact upsert semantics on a format whose files are immutable. A view of modest size
    maintained at a checkpoint's cadence is what this mode is for. For a high-volume query, bind
    `mode: changelog` and fold the changes where they are read.

## Changelog mode: the history, with weights

`mode: changelog` appends every change as a row, with two columns the sink adds after the declared
ones:

| Column | Type | What it holds |
|---|---|---|
| `_op` | `STRING` | `insert` for a positive weight, `delete` for a negative one |
| `_weight` | `BIGINT` | the change's Z-set weight, signed |

Nothing is ever removed, so a reader folds the table itself — the usual "latest row per key" window
function, or a `SUM(_weight)` per group. A declared schema that already has a column called `_op` or
`_weight` is refused with PRV-5056 rather than one of them quietly winning.

```yaml
pravaha:
  sinks:
    spend_changes:
      plugin: delta-sink
      options:
        path: /warehouse/spend_changes
        schema: "user_id:STRING,window_end:TIMESTAMP,total:INT64"
        mode: changelog
```

## How exactly-once works: a staging directory, then one commit

A Delta commit is visible the instant its log entry lands. There is no *prepared* state — durable
but not yet visible — which is what the engine's two-phase protocol asks a transactional sink for.
So the Delta sink stages, as [`jdbc-sink`](/help/topics/sink-jdbc) does with a table and
[`kafka-sink`](/help/topics/sink-kafka) with a topic:

1. **Write** encodes the batch and appends it to
   `staging.dir/<transaction.id>/<label>/`, as files. Nothing is committed; no reader of the table
   sees anything. A file is written to `.tmp` and renamed, so a file that is there is a file that is
   whole.
2. **Prepare**, at the checkpoint's cut, names the label. Everything it holds is already durable.
3. **Commit**, once that checkpoint is durable, reads the label back and applies all of it as **one
   Delta commit**. A reader sees all of a checkpoint's changes or none of them.
4. **After a restart**, the engine commits the handles the restored checkpoint recorded and
   discards everything staged after it, which the replay writes again.

Each commit carries a Delta `txn` action naming `transaction.id` and the label. That is what makes a
repeated commit a no-op — a restore cannot know whether the first commit arrived, so it sends it
again, and the table's own record of the label says it is done. The record is the table's, not a
note the process kept, so it survives the process that wrote it.

## Small files, and whose job compaction is

One commit per checkpoint, each writing at least one Parquet file plus one for every file an upsert
had to rewrite. **The table accumulates small files, and this sink does not compact it.**

- Run Delta's `OPTIMIZE` from an engine that has one — Spark, or `delta-rs` — on whatever schedule
  the table's size asks for.
- Run `VACUUM` to remove the files the rewrites superseded, with a retention longer than the longest
  a [Delta source](/help/topics/source-delta) reading the same table may be behind it.
- Lengthening `pravaha.checkpoint.interval` makes fewer, larger commits, at the cost of the table
  trailing the view further.

Delta Kernel exposes no compaction API, so the plugin has none, and a sink that ran table
maintenance the table's owner had not asked for would be a worse answer than saying so.

## Concurrent writers

A Delta table maintained by a continuous query **should have no other writer**. Exactly what happens
if it has one:

| The other writer commits | What happens |
|---|---|
| before this sink's commit begins | Nothing fails. The commit is computed against the snapshot they left, so their rows are in the table it merges onto — and where they wrote a key this sink holds, **this sink's value wins**, because that key is the query's answer |
| inside the commit's own window — after the snapshot it read, before its log entry | The commit fails with PRV-5059, and the sink is detached with PRV-8009. This checkpoint's changes stay staged; nothing is written twice and nothing is written half |

The sink never retries a conflict, deliberately. Delta Kernel would resolve one and try again, which
is safe for a blind append and not for a merge: the file this commit is removing may be one the
other writer has just rewritten, and replaying the removal over their version undoes their change.

## Without transactions

`transactional: "false"` commits each batch as its own Delta commit, visible at once. In upsert mode
that is **effectively once** — a replay after a restart rewrites rows with the values they already
hold. In changelog mode it is **at least once**, and the table keeps the repeats. It also makes a
great many more, much smaller commits, so read the section above first.

## What is refused, and when

| Code | When | Why |
|---|---|---|
| PRV-5056 | binding | A `mode` that is neither `upsert` nor `changelog`; a missing `key.columns` in upsert mode or a pointless one in changelog mode; a key that is floating-point, nullable or not in the schema; a `transaction.id` that cannot also be a directory name; a changelog schema that already declares `_op` or `_weight` |
| PRV-5051 | binding | A column type Delta has no equivalent for — `TIME` |
| PRV-5057 | when the sink opens | The table is partitioned differently from `partition.columns` (in either direction), or its columns are not the binding's by position, name and type, or `create: false` and there is no table. The message names the column |
| PRV-5058 | writing | Staging, reading back or committing failed. Two of its cases are the sink refusing rather than the filesystem failing: a null key column, and a `TIMESTAMP` that is not a whole number of microseconds |
| PRV-5059 | committing | Another writer committed while this commit was being built |
| PRV-8010 | registration | The query's `SELECT` list is not the binding's `schema`, or its key is not `key.columns` |

## Pitfalls

!!! warning "Pitfall: a TIMESTAMP with nanoseconds"
    Delta stores microseconds; the engine stores nanoseconds. A value that is not a whole number of
    microseconds is **refused** with PRV-5058, not rounded — a rounded timestamp reads as true and
    is not, and a key column rounded this way stops matching the row it was meant to replace.
    Truncate the column in the query, or take the event time from a source that records microseconds.

!!! warning "Pitfall: the table trails the view"
    With `transactional: true`, a row appears in the table only after the checkpoint that recorded
    it is durable — up to `pravaha.checkpoint.interval` behind the view. Read the view when you need
    the answer now.

!!! warning "Pitfall: no checkpoint directory"
    Without `pravaha.checkpoint.directory` the sink is still transactional, but each view commit is
    its own Delta commit and a restart delivers again: **effectively once** in upsert mode, **at
    least once** in changelog mode. The registration's log line and `GET /api/v1/sinks` say which.

!!! warning "Pitfall: two registrations on one binding"
    Staged changes are found by `transaction.id`, which defaults to the binding's name, and so is
    the Delta application id that makes a commit idempotent. Two queries writing one binding share
    both. Bind one sink per query.

!!! note "In the server jar"
    `pravaha-plugin-delta` ships inside the server jar. In a process without it on its classpath the
    sink binding is refused with PRV-5090, listing the plugins that are available.

!!! note "Reading the same table you write"
    A [Delta source](/help/topics/source-delta) can read a table this sink maintains, and each of
    this sink's commits is one version for it to diff. Keep `VACUUM`'s retention longer than the
    longest that reader may be behind, or it meets PRV-5053.

## Where next

- [Sinks overview](/help/topics/sinks-overview) — binding, naming and the two checks at registration
- [Delivery guarantees](/help/topics/delivery-guarantees) — what exactly once means here
- [The Delta source](/help/topics/source-delta) — the other direction
- [The jdbc sink](/help/topics/sink-jdbc) and [the Kafka sink](/help/topics/sink-kafka) — the same
  staging idea over a table and a topic
- [Weights and retractions](/help/topics/zset-weights) — what `_weight` is
