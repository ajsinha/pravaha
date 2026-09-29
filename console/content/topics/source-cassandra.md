---
title: The Cassandra source
slug: source-cassandra
category: sources
order: 70
icon: hdd-stack
summary: "Scans a Cassandra table by token() range on an interval — a full, honest periodic scan rather than an incremental one that would quietly miss rows — with hostname-verified TLS."
badge: SOURCE
audience: Operators
keywords: [cassandra, cql, deletes, detect, PRV-2042, repeats rows, deletes.max.keys, deletes.state.dir, retraction, scylla, token range, scan, partition.key, local.datacenter, consistency.level, fetch.size, writetime, cdc, tombstone, pushdown, projection, allow filtering]
guide: continuous-queries#21-every-source-type-configured
related: [sources-overview, source-aerospike, connector-security, event-time-watermarks]
listed_on: sources-overview
---

The `cassandra` plugin reads a table by paging through it in **`token()` order**: each of its readers
takes a slice of the token ring and walks it a page at a time, then waits out `scan.interval.ms` and
walks it again. Every pass reads the whole assigned range.

That is a **full periodic scan, and deliberately so.** Cassandra has no server-side way to ask "what
changed since": CQL cannot filter on `writetime()` without `ALLOW FILTERING` — which reads every
partition anyway — and `writetime()` is tracked per *column*, so a key-only write moves nothing.
Cassandra's CDC writes commitlog segments on every node for a local agent to read, with no ordering
across nodes, which is a different project from a connector. The plugin names both of those
strategies and refuses them, because **a full scan that says what it is beats an incremental one that
quietly misses rows**.

## At a glance

| | |
|---|---|
| Plugin name | `cassandra` |
| Module | `plugins/pravaha-plugin-cassandra` — in the server jar |
| Kind | stream source |
| Strategy | `token-range-scan` — the only one implemented |
| Delivery guarantee | `AT_LEAST_ONCE`; `EXACTLY_ONCE` with `deletes: detect` |
| Replayable offsets | yes — a token cursor within the pass (with `deletes: detect`, a count of emitted rows backed by files) |
| Emits deletes / before-image | no / no — **yes / yes with `deletes: detect`**, [below](#seeing-deletes-deletes-detect) |
| Repeats rows | **yes** with `deletes: ignore` — every pass emits every row again, so an aggregate, a join or an append-only sink is refused (PRV-2042); no with `deletes: detect` |
| Pushdown | `PROJECT` only — the CQL `SELECT` list. Not `FILTER`, not `PARTIAL_AGGREGATE` |
| Shared between queries | yes — one reader per binding serves every query over it; not with `deletes: detect` |
| Schema comes from | the `schema` option you write |
| Partitions | `partitions` readers, each over an equal slice of the token ring |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `contact.points` | yes | — | `host:port,host:port`, usually port 9042 |
| `keyspace` | yes | — | The keyspace |
| `table` | yes | — | The table |
| `schema` | yes | — | `column:TYPE,…`. Types: `BOOLEAN`, `INT8`/`TINYINT`, `INT16`/`SMALLINT`, `INT32`/`INT`, `INT64`/`BIGINT`/`COUNTER`, `FLOAT32`/`FLOAT`, `FLOAT64`/`DOUBLE`, `STRING`/`TEXT`/`VARCHAR`/`ASCII`, `UUID`/`TIMEUUID`/`INET` (read as `STRING`), `BYTES`/`BLOB`, `TIMESTAMP`. `DECIMAL` and `VARINT` are refused with PRV-5087 |
| `partition.key` | yes | — | The table's partition-key columns, comma-separated, **in CQL's order** — what `token()` is computed over. Each must be in `schema` |
| `local.datacenter` | no | auto-detected | Name it when the cluster has more than one datacenter; a single-datacenter cluster is detected from the contact points |
| `event.time` | no | on a server, the stream's declared `event-time` | A `TIMESTAMP` column holding each row's event time. Any other type is refused with PRV-5088. Without it every row carries the time its pass started |
| `strategy` | no | `token-range-scan` | `writetime-incremental` and `commitlog-cdc` are named and refused with PRV-5088 |
| `partitions` | no | `1` | Parallel readers over the ring |
| `scan.interval.ms` | no | `60000` | The pause between passes. Ten times Aerospike's default, because this scan is not filtered |
| `fetch.size` | no | `5000` | Rows per page |
| `consistency.level` | no | `LOCAL_ONE` | Any driver consistency level name: `ONE`, `LOCAL_QUORUM`, `QUORUM`, … An unknown one is refused, listing the valid ones |
| `request.timeout.ms` | no | `30000` | Per-request timeout. Must be positive — zero would wait for ever |
| `user` / `password` | no | empty | Credentials |
| `deletes` | no | `ignore` | `detect` merges each pass with every row already emitted and emits only the difference, retractions included — [below](#seeing-deletes-deletes-detect). Anything else is PRV-5088 |
| `deletes.state.dir` | with `detect` | — | Where each reader keeps the rows it has emitted, so a restore gets them back exactly. Durable local disk. Missing is PRV-5088 |
| `deletes.max.keys` | no | `1000000` | Rows one token-range reader may hold before the pass is refused with PRV-5122 |
| `stream` | no | the table name | The stream name the plugin reports |
| `share.reader` | no | `true` | Read by the binding layer: `false` gives each query its own scan, selecting only its own columns |
| `tls.*` | no | off | The shared TLS options; hostname verification is honoured — see below |

## A complete binding

```yaml
pravaha:
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 30s
  sources:
    orders:
      plugin: cassandra
      options:
        contact.points: "cass-1.internal:9042,cass-2.internal:9042"
        local.datacenter: dc1
        keyspace: sales
        table: orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        partition.key: order_id
        event.time: event_time
        strategy: token-range-scan
        partitions: "8"
        scan.interval.ms: "60000"
        fetch.size: "5000"
        consistency.level: LOCAL_ONE
        user: pravaha
        password: "${CASSANDRA_PASSWORD}"
```

## A query over it

Because every pass re-reads every row, the natural queries over this source are ones that **keep
current state** — a keyed filter or projection whose view is overwritten by each pass:

```sql
CREATE CONTINUOUS QUERY open_order_book
    KEYED BY (order_id)
AS
SELECT order_id, customer_id, region, amount
FROM orders
WHERE status = 'OPEN';
```

<!-- sql: read -->
```sql
SELECT region, COUNT(*) AS open_orders, SUM(amount) AS open_value
FROM open_order_book
GROUP BY region
```

With four open EU orders totalling 9,100 and two US orders totalling 4,000 in the table:

```text
region	open_orders	open_value
EU	4	9100
US	2	4000
2 rows
```

The view is keyed by `order_id`, so the second pass re-reading the same orders replaces each row with
itself rather than adding it again.

An aggregate over the stream itself — windowed or not — needs `deletes: detect` on the binding
([below](#seeing-deletes-deletes-detect)). With the default `deletes: ignore` every pass emits every
row again, so a `COUNT` would count each row once per pass, and registration refuses it with
**PRV-2042**, naming the binding and the fix. With `detect` it registers and stays equal to the
table:

```sql
CREATE CONTINUOUS QUERY orders_per_region_hour
    KEYED BY (region, window_end)
AS
SELECT region, window_start, window_end, COUNT(*) AS placed
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY region, window_start, window_end;
```

## Pushdown

`PROJECT`, and only that. The pass's CQL `SELECT` names the columns the query uses, plus the
`event.time` column the reader stamps each row with — the `token()` the pages are walked by is
computed on the server whatever is selected — and a column nobody reads is bytes Cassandra never
sends. `open_order_book` above happens to need all six columns of this table (`status` for its
filter, `event_time` for the reader); over a wider table with, say, a long `notes` column, it would
never fetch `notes`. The engine works this out through projections and
filters directly over the scan; a query that aggregates, joins, windows or computes a column asks for
every column. A shared reader selects the union of the columns its queries read.

**`FILTER` on the key, and only there.** CQL answers two shapes without `ALLOW FILTERING`, and both
are pushed:

- **The whole partition key by equality** (`WHERE tenant = 'acme' AND id = 7` over
  `PRIMARY KEY ((tenant, id), ...)`): each pass reads that partition instead of the token range. A
  reader shared by several queries, each pinning its own key, reads each of those partitions, up to 256.
- **Then the clustering columns, in their declared order**: equality down a prefix, then a range on the
  next one (`AND day = 3 AND ts > '2026-09-01'`), which Cassandra answers by slicing the partition.

Anything else stays with the engine: a `WHERE` on a regular column, a partition key pinned only in
part or by a range, a clustering restriction that skips a column. CQL would need `ALLOW FILTERING`
for those, which reads every partition on the server anyway. The engine applies the whole `WHERE`
after each row arrives in every case, so a pushed restriction only ever reads fewer rows, never
different ones. Values are pushed only where they are exact: a `timestamp` bound is widened to the
millisecond Cassandra stores, a text column is pushed by equality only, and key columns of types
other than `tinyint`, `smallint`, `int`, `bigint`, `timestamp`, `text`, `ascii` and `boolean` are
not pushed. The key columns and their types come from the table's schema metadata when the source
opens. The query's feed description (`feed.description` on `GET /api/v1/queries/{name}`) says what
was pushed: `pushed to Cassandra: partition key tenant = 'acme' and id = 7, clustering day = 3`, or
`no filter pushed to Cassandra: ...` and why. With `deletes: detect`, one partition key is pushed.

**Not `PARTIAL_AGGREGATE`.** CQL aggregates run per partition, and every pass here re-reads the whole
range with no retraction of the previous pass, so no partial could be "the new rows only".

## Seeing deletes: `deletes: detect`

By default every pass adds every row again at `+1` and a deleted row simply stops being read. With
`deletes: detect` the reader keeps every row it has emitted and merges each pass with them in token
order. When the pass moves past a token, rows held below it that the pass did not reach are
retracted, and the rows under the token itself — a partition's clustering rows — are compared as a
multiset:

| The pass finds | Emitted |
|---|---|
| a row not emitted before | the row at `+1` |
| a row whose columns changed | the **whole old row** at `−1`, then the new one at `+1` |
| a row unchanged | nothing |
| no row where one was emitted | the whole old row at `−1`, with the event time it was inserted at |

A view over the table then equals the table after every pass — including a stream aggregate, which
no longer counts a row once per pass, and which is therefore admitted where `ignore` refuses it
(PRV-2042).

```yaml
pravaha:
  sources:
    orders:
      plugin: cassandra
      options:
        contact.points: "cass-1.internal:9042,cass-2.internal:9042"
        local.datacenter: dc1
        keyspace: sales
        table: orders
        schema: "customer_id:STRING,order_id:INT64,amount:INT64,status:STRING"
        partition.key: customer_id
        consistency.level: LOCAL_QUORUM
        deletes: detect
        deletes.state.dir: /opt/pravaha/data/scan-state
        deletes.max.keys: "2000000"
```

- **Nothing more is read** — each pass was already a full scan. Only the current token's rows are
  buffered, and a poll reads at most `fetch.size` rows.
- **A delete is as timely as the next pass**: up to `scan.interval.ms` plus the pass's own time.
- **Use a consistency level that cannot miss a row**, such as `LOCAL_QUORUM`, on a table with more
  than one replica: a row one replica has not received is absent from that pass, retracted, and
  inserted again when it reappears.
- **About 150 bytes of heap per row plus the row** (136 measured for a three-column row).
  `deletes.max.keys` bounds the rows each token-range reader holds at every moment and refuses by
  code (PRV-5122) rather than forgetting rows whose deletes could then never be seen.
- **A restart is exact.** The rows are logged under `deletes.state.dir`, forced to disk at every
  checkpoint; a restore replays them to exactly the checkpoint's count and starts a fresh pass from
  the bottom of the range, re-reading and not re-emitting what did not change. Missing or damaged
  state is refused with PRV-5123.
- **`EXACTLY_ONCE`, with deletes and before-images** — so the reader is no longer shared between
  queries, and a query over it may be refused an append-only sink (PRV-2041). Two writes between
  passes are still one; changing `deletes` on an existing checkpoint is refused (PRV-5088).

## Delivery guarantee

`AT_LEAST_ONCE` by default. The offset is the last token consumed inside the current pass; a restart
-- or a page of the pass that fails -- resumes the pass **from that token's partition, inclusive**, so
a wide partition the reader stopped inside is read again from its first clustering row rather than
skipped until the next pass (CASS-1); its first rows arrive twice, as every row does on every pass. What the plugin cannot give you then, it declares: deletes
are invisible, two writes between passes are one, there is no before-image, and every pass repeats
every row. `deletes: detect`, above, is `EXACTLY_ONCE`, sees deletes and repeats nothing.

## TLS

The obvious way to hand the DataStax driver an `SSLContext` encrypts the connection and performs **no
hostname verification**. This plugin uses the driver's programmatic engine factory instead, which
honours `tls.verify-hostname` — default `true`.

```yaml
pravaha:
  sources:
    orders:
      plugin: cassandra
      options:
        contact.points: "cass-1.internal:9042,cass-2.internal:9042"
        local.datacenter: dc1
        keyspace: sales
        table: orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        partition.key: order_id
        event.time: event_time
        user: pravaha
        password: "${CASSANDRA_PASSWORD}"
        tls.enabled: "true"
        tls.truststore: /opt/pravaha/conf/tls/cassandra-truststore.p12
        tls.truststore.password: "${TRUSTSTORE_PASSWORD}"
```

## Pitfalls

!!! danger "Pitfall: by default, deletes are invisible"
    A tombstoned row is simply absent from the next pass, indistinguishable from one that never
    existed. A view keeps serving it — unless the binding sets `deletes: detect`, above. Otherwise,
    soft-delete with a status column and filter on it.

!!! warning "Pitfall: by default, every pass re-delivers every row"
    Without `deletes: detect` the source repeats rows, and says so. A keyed view that holds current
    state is right — each copy overwrites its own key. What would count the copies is refused at
    registration with PRV-2042: any aggregate over the stream, a join, and a sink that cannot upsert
    by key. Set `deletes: detect` on the binding, or aggregate over the keyed view instead, as the
    read example above does. A subscriber to the keyed view sees each copy as another `+1` of a row
    it already has: overwrite by key rather than summing weights.

    **Why `ignore` is still the default:** `detect` needs a durable `deletes.state.dir` and holds
    every emitted row in memory, up to `deletes.max.keys` per token range; it costs no extra reads.

!!! warning "Pitfall: a short interval on a large table"
    Each pass reads the whole range. `scan.interval.ms: "1000"` on a hundred-million-row table is a
    scan that never stops running. Size the interval to how long a pass takes and how stale the view
    may be.

!!! note "`partition.key` is order-sensitive"
    List the partition-key columns exactly as the table's `PRIMARY KEY ((a, b), …)` does. The wrong
    order computes a different `token()` and pages the ring incorrectly.

## Where next

- [The Aerospike source](/help/topics/source-aerospike) — a scan that *is* incremental, with filters pushed to the server
- [Connector security](/help/topics/connector-security) — truststores, keystores and hostname checking
- [Sources overview](/help/topics/sources-overview) — choosing a source for the question
