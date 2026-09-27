---
title: Joining streams
slug: joins
category: sql
order: 60
icon: diagram-2
summary: "Stream-to-stream joins: equality keys, time bounds on one side or both, LEFT joins that emit their unmatched rows once, three-way joins — and the shapes that are refused because they cannot be made incremental."
audience: Analysts and developers
keywords: [join, inner join, left join, time bound, between, interval, symmetric hash join, match window, three-way join, self join, cross join, right join, full outer join, non-equi join]
guide: continuous-queries#14-joins-what-runs
related: [temporal-joins, windows-worked, event-time-watermarks, sql-refusals, sources-overview]
---

A join between two streams keeps **state for each side** and emits a row whenever a pair matches --
whichever side arrived second. It is a symmetric hash join, incremental in both directions: a new
order is matched against the shipments already held, a new shipment against the orders already held.
What bounds that state is **event time**: rows are evicted when the watermark has moved past the
point at which they could still match anything.

The two shapes that make up nearly every real join are both supported: **a stream joined to a
stream on a key** (this page) and **a stream enriched from a table** ([temporal joins](/help/topics/temporal-joins)).

## What runs, and what is refused

| Shape | Runs | Code if refused | Why |
|---|---|---|---|
| `INNER JOIN` on an equality | yes | | Symmetric hash join |
| Multi-column equi-join — `ON a.x = b.x AND a.y = b.y` | yes | | |
| Equality **and** a time bound — `BETWEEN` | yes | | The bound decides which pairs match and how long state is kept |
| Equality and a one-sided bound — `>=` | yes | | The unstated side closes at zero |
| `LEFT JOIN` with a time bound | yes | | The null-padded row is emitted once, when the window passes |
| Three-way and deeper, between distinct streams | yes | | |
| A time bound with no equality | no | PRV-2020 | Every row is still a candidate for every other inside the window |
| A time bound in months | no | PRV-2020 | A month has no fixed length |
| `LEFT JOIN` without a time bound | no | PRV-2020 | No moment at which a row can be declared unmatched |
| `RIGHT` / `FULL OUTER` | no | PRV-2020 | Swap the inputs and use `LEFT` |
| `CROSS JOIN` | no | PRV-2020 | No key to partition on |
| Non-equi — `ON a.x > b.x` | no | PRV-2020 | A cross product in disguise |
| Self join — one stream on both sides | yes | | Each row is handed to both sides ([below](#the-refused-shapes)); a windowed `COUNT(*) … HAVING` is often what is meant |

## An inner join with a time bound

Orders and their shipments, matched when the shipment is recorded within a day of the order:

```sql
CREATE CONTINUOUS QUERY shipped_orders
    KEYED BY (order_id, shipment_id)
AS
SELECT o.order_id, o.customer_id, o.region, o.amount,
       s.shipment_id, s.carrier
FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' DAY;
```

With these rows --

```text
orders                                              shipments
order_id customer_id region amount event_time       shipment_id order_id carrier event_time
101      c-7         EMEA   4200   09:00:00         9001        101      DHL     13:20:00
102      c-9         APAC   1500   09:05:00         9002        102      UPS     (next day) 10:00:00
103      c-7         EMEA   800    09:10:00
```

-- the view holds one row: order 101 shipped four hours later. Order 102's shipment came 24 h 55 m
after the order, outside the bound, so it does not match; order 103 has no shipment yet.

<!-- sql: read -->
```sql
SELECT order_id, region, carrier FROM shipped_orders
```

```text
order_id  region  carrier
101       EMEA    DHL
```

The bound does two jobs at once: it says which pairs **count**, and it tells the engine it may forget
an order once the shipments' watermark has passed `order time + 1 day`. That is what keeps the state
bounded.

### Writing the bound

Both forms state a time relation between one timestamp from each side:

```sql
SELECT o.order_id, s.carrier
FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time - INTERVAL '5' MINUTE AND o.event_time + INTERVAL '2' HOUR
```

A one-sided bound — only the lower edge is written ("a quote from at most thirty seconds before the
trade"); the unstated upper side closes at zero:

```sql
SELECT t.trade_id, t.symbol, t.price_cents, q.bid_cents, q.ask_cents
FROM trades t
JOIN quotes q
  ON q.symbol = t.symbol
 AND q.event_time >= t.event_time - INTERVAL '30' SECOND
```

An equality on the timestamps themselves is also a (zero-width) bound:

```sql
SELECT t.trade_id, q.bid_cents
FROM trades t
JOIN quotes q
  ON q.symbol = t.symbol
 AND q.event_time = t.event_time
```

### Without a bound

An inner equi-join with **no** time bound is accepted: the engine applies a default match window so
that the join is survivable. That default exists to keep memory bounded, not to be right for your
data — **state the bound you mean**:

```sql
SELECT o.order_id, s.shipment_id, s.carrier
FROM orders o
JOIN shipments s ON s.order_id = o.order_id
```

## Multi-column keys

```sql
SELECT t.trade_id, q.bid_cents
FROM trades t
JOIN quotes q
  ON q.symbol = t.symbol
 AND q.event_time BETWEEN t.event_time - INTERVAL '5' SECOND AND t.event_time
```

```sql
SELECT o.order_id, s.carrier
FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.shipment_id = o.order_id
```

Every additional equality narrows the key; every row still needs its partner to share all of them.

## LEFT JOIN — unmatched rows, emitted once

"Orders not shipped within two days" is a `LEFT JOIN` with a bound, and a filter for the
null-padded rows:

```sql
CREATE CONTINUOUS QUERY unshipped_orders
    KEYED BY (order_id)
AS
SELECT o.order_id, o.customer_id, o.region, o.amount, s.shipment_id
FROM orders o
LEFT JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '2' DAY
WHERE s.shipment_id IS NULL;
```

The null-padded row for an order is emitted **when the watermark passes the end of its window** --
two days after the order, in event time — **once, and never retracted**. Before then the engine
cannot know the shipment will not come; after then it can, because the bound says so.

An order whose own `order_id` is NULL is one of these. NULL is not equal to NULL in a join, so it
matches nothing — not even another NULL — and a `LEFT JOIN` therefore emits it null-padded like any
other unmatched left row.

With the rows above, once the watermark passes 09:10 two days later, only order 103 is left: order
101 shipped after four hours, and order 102's shipment (24 h 55 m after the order) is inside a two-day
bound, so both matched.

<!-- sql: read -->
```sql
SELECT order_id, region, amount FROM unshipped_orders
```

```text
order_id  region  amount
103       EMEA    800
```

Without a bound there is no moment at which an order can be declared unshipped, so every unmatched
row would be held for the life of the process:

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id, s.carrier FROM orders o LEFT JOIN shipments s ON s.order_id = o.order_id
```

`RIGHT` and `FULL` are refused for the same reason; a `RIGHT JOIN` is a `LEFT JOIN` with the inputs
swapped:

<!-- sql: refused PRV-2020 -->
```sql
SELECT s.shipment_id, o.order_id FROM orders o RIGHT JOIN shipments s ON s.order_id = o.order_id
```

```sql
SELECT s.shipment_id, o.order_id
FROM shipments s
LEFT JOIN orders o
  ON o.order_id = s.order_id
 AND o.event_time BETWEEN s.event_time - INTERVAL '7' DAY AND s.event_time
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o FULL JOIN shipments s ON s.order_id = o.order_id
```

## Three-way joins

Joins chain across **distinct** streams. A trade, the quote before it, and the order stream keyed by
account:

```sql
SELECT t.trade_id, t.symbol, q.bid_cents, o.order_id
FROM trades t
JOIN quotes q
  ON q.symbol = t.symbol
 AND q.event_time BETWEEN t.event_time - INTERVAL '5' SECOND AND t.event_time
JOIN orders o
  ON o.customer_id = t.account
```

A stream join and a lookup mix freely:

```sql
SELECT o.order_id, s.carrier, c.segment
FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
LEFT JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c
  ON c.customer_id = o.customer_id
```

A join's watermark is the **minimum** of its inputs' watermarks: a query is only as current as its
laggiest input. If shipments trail orders by an hour, the join's windows close an hour late. That is
correct, and it surprises people.

## Aggregating a join

Windowing the output of a stream-to-stream join is refused today: the window is assigned on one input
and does not survive the join, so the aggregate above it has no window to be bounded by (PRV-2050):

<!-- sql: refused PRV-2050 -->
```sql
SELECT s.carrier, window_start, window_end, SUM(o.amount) AS shipped_amount
FROM TABLE(TUMBLE(TABLE shipments, DESCRIPTOR(event_time), INTERVAL '1' HOUR)) AS s
JOIN orders o
  ON o.order_id = s.order_id
GROUP BY s.carrier, window_start, window_end
```

**Rewrite:** maintain the join as a view, and aggregate it when you read — the scan ends, so the
unwindowed `GROUP BY` is allowed there:

```sql
CREATE CONTINUOUS QUERY shipment_values
    KEYED BY (shipment_id)
AS
SELECT s.shipment_id, s.carrier, o.amount
FROM shipments s
JOIN orders o
  ON o.order_id = s.order_id
 AND o.event_time BETWEEN s.event_time - INTERVAL '7' DAY AND s.event_time;
```

<!-- sql: read -->
```sql
SELECT carrier, SUM(amount) AS shipped_amount, COUNT(*) AS shipments
FROM shipment_values
GROUP BY carrier
```

A window over a stream **enriched from a lookup table** is different and works — a lookup is not a
join between two stateful inputs ([windows, worked](/help/topics/windows-worked#windows-with-filters-joins-and-lookups)).

## The refused shapes

A time bound with no equality — no key to index either side by:

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o
JOIN shipments s ON s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' HOUR
```

A bound in months:

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' MONTH
```

Use days — `INTERVAL '31' DAY` — and say which month length you mean.

A cross join and a non-equi join:

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id, s.shipment_id FROM orders o CROSS JOIN shipments s
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o JOIN shipments s ON s.order_id > o.order_id
```

An inequality between **timestamps** is a time bound and is supported; between anything else it is a
cross product and is not.

A self join — the same stream on both sides — runs: each row is handed to the first side and then
the second, which is the join's own delta rule applied to one stream. Until 2026-09-26 it was refused
when the pipeline was built, with no `PRV-` code.

```text
SELECT a.order_id, b.order_id FROM orders a JOIN orders b ON a.customer_id = b.customer_id
```

The usual workaround is a windowed aggregate: "customers with more than one order in an hour" is a
`COUNT(*)` with `HAVING`, not a self join:

```sql
SELECT customer_id, window_start, window_end, COUNT(*) AS order_count
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY customer_id, window_start, window_end
HAVING COUNT(*) > 1
```

## Across connectors

The two sides may come from entirely different sources — a `filesystem` file on one side and a
`feedfile` directory on the other is demonstrated end to end by the engine's own tests. Joins across
`jdbc`, `delta`, `cassandra`, `postgres-cdc` or `kafka` sources are supported by construction and not
yet demonstrated end to end. Two `aerospike` sets joined with each other and with a followed CSV file
are, in the tutorial [Joining two Aerospike sets and a CSV file](/tutorials/aerospike-fulfilment). Each side keeps its own out-of-orderness; the join waits for the slower.

## Pitfalls

!!! warning "Pitfall: a bound too wide for the traffic"
    State is roughly *rows per second x bound*. A seven-day bound on a busy stream holds seven days
    of rows on each side. Watch `pravaha_query_state_fraction` and alert well before PRV-4001.

!!! warning "Pitfall: one slow input stalls the join"
    The join's watermark is the minimum of both sides. A source that goes quiet is excluded after
    `pravaha.watermark.idle-after`; a source that is merely slow holds everything back.

!!! note "LEFT JOIN rows are final"
    A null-padded row is emitted once, after the window, and never retracted — a partner arriving
    later than the bound does not undo it. That is the definition of the bound, not a race.

## Where next

- [Temporal joins](/help/topics/temporal-joins) — enriching from a table instead of a stream.
- [Event time and watermarks](/help/topics/event-time-watermarks) — what releases join state.
- [State and spill](/help/topics/state-spill) — when join state is larger than memory.
