---
title: Lookup tables
slug: lookups
category: sources
order: 80
icon: search
summary: "Dimension tables a query asks rather than consumes — aerospike-lookup and jdbc-lookup — joined with FOR SYSTEM_TIME AS OF, fanned out on virtual threads, cached if you allow it, and holding no state to checkpoint."
badge: LOOKUP
audience: Operators
keywords: [lookup, dimension, enrichment, temporal join, for system_time as of, aerospike-lookup, jdbc-lookup, key.bin, key.columns, cache.seconds, pool.size, concurrency]
guide: continuous-queries#22-lookup-sources-for-temporal-joins
related: [joins, source-aerospike, source-jdbc, connector-security]
---

A **lookup** is a table a query *asks*, one row at a time, rather than a stream it *consumes*. Each
arriving row of a stream asks the lookup for the row matching its key, and the answer is joined on.
This is how a transaction picks up its customer's tier, a trade its instrument's sector, an order its
customer's segment — enrichment from reference data that lives in a store you already run.

A lookup is different from a source in three ways that matter:

- **It never advances event time.** Only sources are consumed and drive the watermark.
- **It holds no state to checkpoint.** The answer lives in the store; a cache lost on restart costs
  latency and nothing else.
- **It is opened when the node starts**, not at the first registration that needs it. A dimension
  table that cannot be reached is a node that cannot answer, and finding that out when the first
  record arrives would mean finding it out in production.

Lookups live under `pravaha.lookups`, and two plugins ship: `aerospike-lookup` and `jdbc-lookup`.

## At a glance

| | `aerospike-lookup` | `jdbc-lookup` |
|---|---|---|
| Module | `plugins/pravaha-plugin-aerospike` | `plugins/pravaha-plugin-jdbc` |
| In the server jar | no | no |
| Asks | a record by its key bin | `SELECT * FROM <table> WHERE k1 = ? AND k2 = ?` |
| Schema comes from | the `schema` option | the database's metadata for `table` |
| **Name a query joins against** | the `stream` option, else the **set name** | the **`table`** option |
| Concurrency | `concurrency` in-flight lookups | a pool of `pool.size` connections |
| Cache | `cache.seconds`, default none | `cache.seconds`, default none |
| TLS | the shared `tls.*` options plus `tls.name` | in the URL; `tls.*` refused |

!!! warning "The SQL name is the plugin's, not the binding's"
    A query joins a lookup by the name its plugin reports: for `jdbc-lookup` that is `table`, for
    `aerospike-lookup` it is `stream` or else `set`. The key under `pravaha.lookups` is a label for the
    log. Keep all three the same and nobody has to remember this; a binding called `user_profile` over
    a set called `users` with no `stream` option is joined as `users`.

## `aerospike-lookup` options

| Option | Required | Default | What it does |
|---|---|---|---|
| `hosts` | yes | — | `host:port,host:port` |
| `namespace` | yes | — | The namespace |
| `set` | yes | — | The set |
| `schema` | yes | — | `bin:TYPE,…` — the bins the join may project |
| `key.bin` | yes | — | The bin a row is looked up by. Must be in `schema` |
| `stream` | no | the set name | The table name queries join against |
| `cache.seconds` | no | `0` | How long an answer may be reused. `0` asks every time |
| `concurrency` | no | `16` | Lookups in flight at once. At least 1 |
| `user` / `password` | no | empty | Credentials |
| `tls.*`, `tls.name` | no | off | As for the [Aerospike source](/help/topics/source-aerospike#tls) |

## `jdbc-lookup` options

| Option | Required | Default | What it does |
|---|---|---|---|
| `url` | yes | — | The JDBC URL, TLS included |
| `table` | yes | — | The table, and the name queries join against |
| `key.columns` | yes | — | The key, comma-separated for a composite one. Declared, not inferred: an unindexed key would be a sequential scan per record |
| `pool.size` | no | `8` | Connections, one per concurrent lookup. At least 1 |
| `cache.seconds` | no | `0` | How long an answer may be reused |
| `user` / `password` | no | empty | Credentials |

`pool.size` is not a knob to leave alone. The engine asks a lookup from several threads at once to hide
the round trip, and one JDBC `Connection` used from two threads interleaves result sets and returns rows
attached to the wrong query. The pool is what makes concurrent lookups **correct**, not merely faster.

## A complete binding

Both kinds side by side, beside the stream they enrich:

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
  sources:
    txn:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        follow: "true"
  lookups:
    user_profile:
      plugin: aerospike-lookup
      options:
        hosts: "as-1.internal:3000"
        namespace: ref
        set: users
        stream: user_profile
        schema: "user_id:STRING,tier:STRING,region:STRING"
        key.bin: user_id
        cache.seconds: "60"
        concurrency: "16"
    customers:
      plugin: jdbc-lookup
      options:
        url: "jdbc:postgresql://db-1.internal:5432/ref?ssl=true&sslmode=verify-full&sslrootcert=/opt/pravaha/conf/tls/pg-ca.pem"
        user: pravaha
        password: "${PRAVAHA_DB_PASSWORD}"
        table: customers
        key.columns: customer_id
        pool.size: "8"
        cache.seconds: "300"
```

At startup the node opens both and logs them — binding, plugin and the option keys it received,
never their values (the order of the two entries may differ):

```text
dimension tables: [user_profile <- aerospike-lookup[hosts, namespace, set, stream, schema, key.bin, cache.seconds, concurrency], customers <- jdbc-lookup[url, user, password, table, key.columns, pool.size, cache.seconds]]
```

## Queries over them

The join is written `FOR SYSTEM_TIME AS OF <the stream row's event time>`: each row asks the table as
of its own time.

```sql
CREATE CONTINUOUS QUERY txn_with_tier
    KEYED BY (txn_id)
AS
SELECT t.txn_id, t.user_id, t.amount, p.tier, p.region
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id;
```

`LEFT` keeps a transaction whose user is not in the table, with `tier` and `region` NULL. An inner
join drops it instead:

```sql
CREATE CONTINUOUS QUERY orders_with_segment
    KEYED BY (order_id)
AS
SELECT o.order_id, o.amount, c.segment, c.country
FROM orders o
JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c
  ON c.customer_id = o.customer_id;
```

To keep only some of the enriched rows, filter on the lookup's columns — but write it as a `LEFT`
join. An inner join with a `WHERE` on the lookup's columns is refused, and the message says so and
points at the `LEFT` form:

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id, o.amount, c.segment
FROM orders o
JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c
  ON c.customer_id = o.customer_id
WHERE c.segment = 'ENTERPRISE'
```

The `LEFT` form means the same thing — a customer missing from the table has a NULL `segment`, and
NULL is not `'ENTERPRISE'`, so the `WHERE` drops it exactly as the inner join would have — and it plans:

```sql
CREATE CONTINUOUS QUERY enterprise_orders
    KEYED BY (order_id)
AS
SELECT o.order_id, o.amount, c.segment, c.country
FROM orders o
LEFT JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c
  ON c.customer_id = o.customer_id
WHERE c.segment = 'ENTERPRISE';
```

Enrichment and a window together — spend per tier per hour:

```sql
CREATE CONTINUOUS QUERY spend_by_tier
    KEYED BY (tier, window_end)
AS
SELECT p.tier, window_start, window_end,
       SUM(t.amount) AS spend,
       COUNT(*)      AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR)) AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
GROUP BY p.tier, window_start, window_end;
```

<!-- sql: read -->
```sql
SELECT txn_id, user_id, amount, tier FROM txn_with_tier WHERE tier IS NULL
```

With `u9` absent from the `users` set, its transactions come back unenriched:

```text
txn_id	user_id	amount	tier
5031	u9	120	NULL
1 row
```

## How lookups run

- **Asynchronously, on virtual threads.** An operator is not capped at the inverse of the store's
  latency: with `concurrency: 16`, sixteen lookups are in flight at once. Output stays in input order.
- **Cached if you say so.** `cache.seconds` trades staleness for round trips. It never costs
  correctness in the checkpoint sense — a lookup holds no checkpointed state — but a cached answer is
  the answer as of when it was fetched, not as of the row's event time.
- **Not versioned by the store.** `FOR SYSTEM_TIME AS OF` is the SQL for "ask as of this row", and the
  two plugins answer with the store's *current* row. A tier changed at 10:00 is seen by a 09:59 row that
  arrives after 10:00. For history-exact enrichment, keep the history in the stream.

## Pitfalls

!!! warning "Pitfall: the join names the wrong table"
    `Object 'user_profile' not found` beside a configuration that plainly has `pravaha.lookups.user_profile`
    means the plugin reports another name — the set or table. Add `stream:` (Aerospike) or name the
    binding after the table (JDBC).

!!! warning "Pitfall: an unreachable table stops the node"
    Lookups are opened at startup. A database that is down, a wrong password or a missing driver stops
    the node from starting (PRV-5070 for JDBC, PRV-5080 for Aerospike), rather than failing the first
    record. That is deliberate; read the startup log.

!!! note "Index the key columns"
    `jdbc-lookup` runs one `SELECT … WHERE key = ?` per uncached row. Without an index on
    `key.columns` that is a table scan per record, and the pipeline is a thousand times slower than the
    same query elsewhere for a reason that looks like the network.

!!! note "A lookup is not a stream you can window or aggregate"
    It can only be the right side of a `FOR SYSTEM_TIME AS OF` join. To aggregate reference data, bind
    it as a source instead.

## Where next

- [Temporal joins](/help/topics/joins#temporal-joins) — the SQL in depth, and what a lookup join may and may not do
- [Stream joins](/help/topics/joins) — joining two sources, with a time bound
- [The Aerospike source](/help/topics/source-aerospike) and [the jdbc source](/help/topics/source-jdbc) — the same stores, consumed as streams
- How it is built: [Architecture: sinks and lookups](/help/architecture-ingest-egress#sinks-and-lookups)
