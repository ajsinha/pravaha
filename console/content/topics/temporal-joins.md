---
title: Temporal joins — enriching from a table
slug: temporal-joins
category: sql
order: 70
icon: table
summary: "FOR SYSTEM_TIME AS OF: each row asks a dimension table for its key as of the row's own event time. The aerospike-lookup and jdbc-lookup bindings, caching, pool sizing, what a lookup costs, and what it cannot filter."
audience: Analysts and developers
keywords: [for system_time as of, lookup, dimension table, enrichment, aerospike-lookup, jdbc-lookup, cache.seconds, pool.size, concurrency, key.bin, key.columns, virtual threads]
guide: continuous-queries#temporal-joins-enriching-from-a-table
related: [lookups, joins, source-aerospike, source-jdbc, windows-worked]
---

Most enrichment is not a join between two streams at all. A payment needs its customer's tier, a
trade its instrument's sector: reference data that lives in a table somewhere and changes slowly.
Pravaha models that as a **lookup**, and a query asks it with a temporal join:

```sql
SELECT t.txn_id, t.amount, p.tier
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
```

The right-hand side is **asked, not consumed**. Each `txn` row issues a key-value read — "the
`user_profile` row for this `user_id`" — and the answer is joined on. Consequences, all of them
useful:

- **No join state.** Nothing is held for the table side, so there is nothing to bound, spill or
  checkpoint. A cache lost on restart costs latency, never correctness.
- **A lookup never advances event time.** Only sources do; a lookup cannot hold a window open.
- **Lookups run concurrently.** They are fanned out on virtual threads so the operator is not capped
  at the inverse of the store's latency, and the output keeps the input's order.

## Stream join or lookup?

| | Stream-to-stream join | Temporal (lookup) join |
|---|---|---|
| Right side is | a stream, consumed | a table, asked per row |
| State | both sides, bounded by a time bound | none |
| Advances event time | yes (the minimum of both) | no |
| Declared under | `pravaha.sources.<name>` | `pravaha.lookups.<name>` |
| SQL | `JOIN s ON ... AND <time bound>` | `JOIN t FOR SYSTEM_TIME AS OF x.event_time AS alias ON ...` |
| Use when | both sides are events | the right side is reference data |

## Binding a lookup

Two lookup plugins ship. A lookup is declared under `pravaha.lookups`, keyed by the name the query
joins against; the stream it enriches is declared as usual.

### From Aerospike

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
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        follow: "true"
  lookups:
    user_profile:
      plugin: aerospike-lookup
      options:
        hosts: "as-1:3000,as-2:3000"
        namespace: prod
        set: users
        schema: "user_id:STRING,tier:STRING,region:STRING"
        key.bin: user_id
        cache.seconds: "60"
        concurrency: "16"
```

| `aerospike-lookup` option | Required | Default | What it does |
|---|---|---|---|
| `hosts` | yes | — | Seed nodes, `host:port`, comma-separated |
| `namespace` | yes | — | The Aerospike namespace |
| `set` | yes | — | The set holding one record per key |
| `schema` | yes | — | The columns the query may select, `name:TYPE` |
| `key.bin` | yes | — | Which column is the record key the join asks by |
| `stream` | no | the set name | The table's name in the catalogue |
| `cache.seconds` | no | `0` — no cache | How long an answer may be reused |
| `concurrency` | no | `16` | Lookups in flight at once |
| `user` / `password` | no | empty | Credentials |

### From any JDBC database

```yaml
pravaha:
  lookups:
    customers:
      plugin: jdbc-lookup
      options:
        url: "jdbc:postgresql://db-1:5432/ref"
        user: pravaha
        password: "${PRAVAHA_DB_PASSWORD}"
        table: customers
        key.columns: "customer_id"
        pool.size: "8"
        cache.seconds: "300"
```

| `jdbc-lookup` option | Required | Default | What it does |
|---|---|---|---|
| `url` | yes | — | The JDBC URL; the driver must be on the classpath |
| `table` | yes | — | The table asked |
| `key.columns` | yes | — | The key, comma-separated for a composite key — **must be indexed** |
| `pool.size` | no | `8` | Connections, one per concurrent lookup |
| `cache.seconds` | no | `0` — ask every time | How long an answer may be reused |
| `user` / `password` | no | empty | Credentials |

`key.columns` is declared rather than inferred on purpose: a database answers a point read quickly
only on an indexed key, and a join on an unindexed column would run a sequential scan per row — a
pipeline mysteriously a thousand times slower, with the symptom pointing at the network.

`pool.size` is not a tuning knob to leave alone: the engine asks from several threads at once, and
one JDBC connection shared by two threads interleaves result sets and returns rows attached to the
wrong query. The pool is what makes concurrent lookups **correct**, not merely faster.

## Worked example

Payments enriched with the customer's tier, then totalled per tier and hour:

```sql
CREATE CONTINUOUS QUERY txn_with_tier
    KEYED BY (txn_id)
AS
SELECT t.txn_id, t.user_id, t.merchant, t.amount, p.tier, p.region
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id;
```

With `user_profile` holding `ann -> gold, EU` and `bob -> silver, US`, and no record for `cat`:

<!-- sql: read -->
```sql
SELECT txn_id, user_id, amount, tier, region FROM txn_with_tier
```

```text
txn_id  user_id  amount  tier    region
1       ann      1200    gold    EU
2       bob      250     silver  US
3       ann      50      gold    EU
4       cat      400     NULL    NULL
```

A `LEFT` lookup keeps the payment with NULLs when the key is absent; an inner lookup drops it:

```sql
SELECT t.txn_id, t.amount, p.tier
FROM txn t
JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
```

Windowed, grouping by the looked-up column:

```sql
CREATE CONTINUOUS QUERY tier_hourly
    KEYED BY (tier, window_end)
AS
SELECT p.tier, window_start, window_end,
       COUNT(*) AS txn_count, SUM(t.amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR)) AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
GROUP BY p.tier, window_start, window_end;
```

Rows whose user has no profile gather under a NULL tier — NULL is a group, not a row that vanishes.

## More shapes

Several lookups on one stream:

```sql
SELECT t.txn_id, p.tier, c.segment, c.country
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
LEFT JOIN customers FOR SYSTEM_TIME AS OF t.event_time AS c
  ON c.customer_id = t.user_id
```

A lookup after a stream join:

```sql
SELECT o.order_id, s.carrier, c.segment
FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
LEFT JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c
  ON c.customer_id = o.customer_id
```

Instruments for trades, with arithmetic on the looked-up column:

```sql
SELECT t.trade_id, t.symbol, i.sector, t.qty / i.lot_size AS lots
FROM trades t
JOIN instruments FOR SYSTEM_TIME AS OF t.event_time AS i
  ON i.symbol = t.symbol
```

A filter on the **stream** side works as usual:

```sql
SELECT t.txn_id, t.amount, p.tier
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
WHERE t.amount > 100
```

## What a lookup cannot do

A lookup is a key-value read. A condition on a **looked-up column** — in the `ON`, or in the
`WHERE` of an inner lookup join — is not a key, and is refused (the message talks about correlated
subqueries; that is the planner's word for it). The same `WHERE` over a **`LEFT`** lookup plans, and
returns exactly the inner answer, because a NULL from a missing key fails the comparison — see
[lookups](/help/topics/lookups):

<!-- sql: refused PRV-2020 -->
```sql
SELECT t.txn_id FROM txn t
JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id AND p.tier = 'gold'
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT t.txn_id, p.tier FROM txn t
JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
WHERE p.tier = 'gold'
```

**Rewrite:** maintain the enriched view, and filter it when you read — or subscribe with a filter:

<!-- sql: read -->
```sql
SELECT txn_id, user_id, amount FROM txn_with_tier WHERE tier = 'gold'
```

```bash
pravaha subscribe --view txn_with_tier --filter tier=gold
```

## What it costs

A lookup is a round trip per row unless an answer is cached. With `cache.seconds: "0"` a stream of
20,000 rows per second is 20,000 reads per second against the store; with a cache, repeated keys are
answered locally until they expire.

| Setting | Buys | Costs |
|---|---|---|
| `cache.seconds` > 0 | Far fewer reads for repeated keys | Staleness up to that many seconds — never incorrectness of state, since nothing is checkpointed |
| `concurrency` / `pool.size` up | Hides round-trip latency | Connections and load on the store |
| An index on `key.columns` | Point reads instead of scans | Nothing you should skip |

!!! warning "Pitfall: `AS OF` is the row's time, but the store answers now"
    The syntax says "as of the row's event time", and the join is ordered and keyed by it. The
    shipped plugins read the store's **current** value (possibly cached) — they do not keep history.
    Replaying last week's rows enriches them with today's reference data. If the history matters,
    carry the attribute on the event itself.

!!! note "A lookup has no watermark"
    Because it never advances event time, a lookup can be slow without holding any window open --
    but a slow store makes every row wait for its answer. Watch commit latency.

## Where next

- [Lookup sources](/help/topics/lookups) — the bindings in full.
- [Joining streams](/help/topics/joins) — when the right side is a stream.
- [Aerospike source](/help/topics/source-aerospike) and [JDBC source](/help/topics/source-jdbc).
