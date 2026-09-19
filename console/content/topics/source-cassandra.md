---
title: The Cassandra source
slug: source-cassandra
category: sources
order: 70
icon: hdd-stack
summary: "Scans a Cassandra table by token() range on an interval — a full, honest periodic scan rather than an incremental one that would quietly miss rows — with hostname-verified TLS."
badge: SOURCE
audience: Operators
keywords: [cassandra, cql, scylla, token range, scan, partition.key, local.datacenter, consistency.level, fetch.size, writetime, cdc, tombstone, pushdown, projection, allow filtering]
guide: continuous-queries#21-every-source-type-configured
related: [sources-overview, source-aerospike, connector-security, event-time-watermarks]
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
| Module | `plugins/pravaha-plugin-cassandra` — **not in the server jar** |
| Kind | stream source |
| Strategy | `token-range-scan` — the only one implemented |
| Delivery guarantee | `AT_LEAST_ONCE` |
| Replayable offsets | yes — a token cursor within the pass |
| Emits deletes / before-image | no / no |
| Pushdown | `PROJECT` only — the CQL `SELECT` list. Not `FILTER`, not `PARTIAL_AGGREGATE` |
| Shared between queries | yes — one reader per binding serves every query over it |
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

A windowed aggregate works too, with the event time read from the row:

```sql
CREATE CONTINUOUS QUERY orders_per_region_hour
    KEYED BY (region, window_end)
AS
SELECT region, window_start, window_end, COUNT(*) AS placed
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY region, window_start, window_end;
```

But mind the second pitfall: a windowed **count** over a source that re-reads every row counts each
row once per pass that lands in an open window.

## Pushdown

`PROJECT`, and only that. The pass's CQL `SELECT` names the columns the query uses, plus the
`event.time` column the reader stamps each row with — the `token()` the pages are walked by is
computed on the server whatever is selected — and a column nobody reads is bytes Cassandra never
sends. `open_order_book` above happens to need all six columns of this table (`status` for its
filter, `event_time` for the reader); over a wider table with, say, a long `notes` column, it would
never fetch `notes`. The engine works this out through projections and
filters directly over the scan; a query that aggregates, joins, windows or computes a column asks for
every column. A shared reader selects the union of the columns its queries read.

**Not `FILTER`.** A `WHERE` on anything but the partition key needs `ALLOW FILTERING`, which still
reads every partition on the server and has null and collation rules of its own to be exact about. So
the engine applies the `WHERE` after each row arrives, and every pass reads the whole range.

**Not `PARTIAL_AGGREGATE`.** CQL aggregates run per partition, and every pass here re-reads the whole
range with no retraction of the previous pass, so no partial could be "the new rows only".

## Delivery guarantee

`AT_LEAST_ONCE`. The offset is the last token consumed inside the current pass; a restart resumes the
unread remainder of that pass. What the plugin cannot give you, it declares: deletes are invisible, two
writes between passes are one, and there is no before-image.

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
        tls.truststore: /etc/pravaha/tls/cassandra-truststore.p12
        tls.truststore.password: "${TRUSTSTORE_PASSWORD}"
```

## Pitfalls

!!! danger "Pitfall: deletes are invisible"
    A tombstoned row is simply absent from the next pass, indistinguishable from one that never
    existed. A view keeps serving it. Soft-delete with a status column and filter on it.

!!! warning "Pitfall: every pass re-delivers every row"
    The source is not incremental. A keyed view that holds current state is right; a stream aggregate
    that adds up rows (`COUNT`, `SUM` over the stream) sees a row again on every pass inside an open
    window. Aggregate over the keyed view instead, as the read example above does.

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
