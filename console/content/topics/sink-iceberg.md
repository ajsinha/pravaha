---
title: The Iceberg sink — an Apache Iceberg table that holds the answer
slug: sink-iceberg
category: sinks
order: 55
icon: layers
summary: "iceberg-sink maintains a query's answer in an Apache Iceberg table on the local filesystem — upserting and removing by key through equality deletes, or appending a changelog — as one Iceberg snapshot per checkpoint, with no Spark."
badge: SINK
audience: Engineers
keywords: [iceberg sink, iceberg-sink, apache iceberg, lakehouse, equality deletes, format version 2, parquet, upsert, changelog, exactly once, exactly-once, transaction.id, key.columns, snapshot, compaction]
guide: connectors#a-transactional-sink-with-equality-deletes-iceberg
related: [sinks-overview, delivery-guarantees, sink-delta, sink-jdbc, sink-kafka, checkpoints-recovery, zset-weights]
listed_on: sinks-overview
---

`iceberg-sink` keeps an **Apache Iceberg table** equal to a query's view. Each row the query commits
is written or replaced by its key, and each retraction removes the record its key names. Whatever
reads Iceberg — Spark, Trino, DuckDB, PyIceberg — reads a maintained answer.

It is built on **iceberg-core and iceberg-parquet, not Spark**, and the catalog is Iceberg's own
filesystem catalog: the table is a directory **on the local filesystem**, and its metadata files are
the catalog. On a node that takes checkpoints the sink is **exactly once**: each checkpoint is one
Iceberg snapshot, and a crash neither loses nor repeats a row.

## At a glance

| | |
|---|---|
| Plugin name | `iceberg-sink` (under `pravaha.sinks.<name>`) |
| Ships in | the `pravaha-plugin-iceberg` jar, in the server jar |
| Table | a directory on the local filesystem. **The sink creates it** (format version 2) if it is not there, and never alters one that is |
| Accepts | `mode: upsert` (default): `UPSERT`, `RETRACT`. `mode: changelog`: `APPEND`, `RETRACT` |
| Keyed | in upsert mode, by `key.columns`, which become the table's identifier fields |
| Delivery | **exactly once** with `transactional: true` and checkpoints; effectively once (upsert) or at least once (changelog) otherwise |
| Not supported | object stores (`s3://` is refused), catalog services, partitioned tables, schema evolution, compaction |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `path` | yes | — | The table directory, a local path. Missing, or a URI, is PRV-5140 |
| `schema` | yes | — | The row shape, `name:TYPE,...` (`?` for nullable). Must match the query (PRV-8010) and an existing table (PRV-5141) |
| `key.columns` | in upsert mode | — | The key. Must be the registration's key; not floating-point or nullable |
| `mode` | no | `upsert` | `upsert` keeps the table equal to the view; `changelog` appends every change with `_op` and `_weight` |
| `transactional` | no | `true` | `true` commits once per checkpoint; `false` commits each batch as its own snapshot |
| `transaction.id` | no | the binding's name | Recorded in every snapshot the sink commits. **One writer per id** |
| `create` | no | `true` | `false` refuses to write unless the table exists |

Types: `BOOLEAN`, `INT8` and `INT16` (widened to Iceberg `int`), `INT32`, `INT64`, `FLOAT32`,
`FLOAT64`, `STRING`, `BYTES`, `DATE`, `TIME`, `TIMESTAMP` (as `timestamptz`) and `DECIMAL(p,s)`.
A time that is not a whole number of microseconds is refused with PRV-5142, never rounded.

## An example

```yaml
pravaha:
  sinks:
    spend_iceberg:
      plugin: iceberg-sink
      options:
        path: /warehouse/iceberg/spend_by_user
        schema: "user_id:STRING,window_end:TIMESTAMP,total:INT64"
        key.columns: user_id,window_end
        mode: upsert
```

Register the query with `WRITING TO spend_iceberg`, as on [the Delta sink](/help/topics/sink-delta).

## Upsert mode: equality deletes, so a commit costs the change

Iceberg can delete without rewriting. At each checkpoint the sink collapses the changes by key (the
last change to a key decides), then writes **one equality delete file** naming every affected key
and **one data file** of the rows that survive, and commits both in one snapshot. Iceberg applies an
equality delete only to older data, so the new rows stand and the old ones are gone. Nothing already
in the table is read or rewritten: unlike the Delta sink, a commit costs in proportion to the change.

!!! warning "Readers pay for deletes until the table is compacted"
    Each checkpoint adds a delete file every reader applies. Compact the table and expire old
    snapshots with the engine that owns it (Spark's `rewrite_data_files`, for example). Keep the
    sink's newest snapshot: its summary is the sink's record of what it committed.

The collapsed changes of one checkpoint are held in memory until the checkpoint is prepared.

## Changelog mode

`mode: changelog` appends every change with two added columns: `_op` (`insert` or `delete`) and
`_weight`, the change's weight. Nothing is removed; fold the changes where they are read.

## Exactly once, and how

A data file no snapshot names is invisible. So a checkpoint's files are written to
`<path>/data/_pravaha/<transaction.id>/<checkpoint>/` before the checkpoint completes, and committed
as one snapshot once it is durable. That snapshot's summary carries `pravaha.transaction-id` and
`pravaha.label`; after a restart, a commit the table already records is skipped, and files of a
checkpoint that never became durable are deleted.

## Where next

- [Sinks overview](/help/topics/sinks-overview) — binding, naming and the checks at registration
- [Delivery guarantees](/help/topics/delivery-guarantees) — what exactly once means here
- [The Delta sink](/help/topics/sink-delta) — the other lakehouse format
- [Plugin codes](/help/topics/errors-plugins) — PRV-5140, PRV-5141 and PRV-5142
