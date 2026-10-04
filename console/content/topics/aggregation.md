---
title: Aggregation
slug: aggregation
category: sql
order: 80
icon: bar-chart-steps
summary: "COUNT, SUM, MIN, MAX, AVG and COUNT(DISTINCT), with HAVING: over a window on a stream, over the whole of a view, and why the same GROUP BY is refused on one and allowed on the other."
audience: Analysts
keywords: [count, sum, min, max, avg, count distinct, having, group by, null group, integer, float, windowed aggregate, global aggregate, EXPR$]
guide: continuous-queries#13-aggregation
related: [windows, sql-refusals, zset-weights, views-and-keys, sql-reference]
---

**Windowed aggregation is the point of the engine.** A continuous query that only filters and
projects is `grep` with extra steps; the value is in maintaining `SUM`, `COUNT` and
`COUNT(DISTINCT)` over a window and keeping them correct as data arrives — incrementally, with every
change carrying a weight so a retraction subtracts exactly what an insertion added
([Z-set weights](/help/topics/zset-weights)).

## The functions

| Function | Over integers | Over `FLOAT64` / `FLOAT32` | Over text | Notes |
|---|---|---|---|---|
| `COUNT(*)` | — | — | — | Counts rows |
| `COUNT(col)` | yes | yes | yes | Skips NULLs |
| `COUNT(DISTINCT col)` | yes | yes | yes | Windowed on a stream; anywhere on a view. Does not count NULL |
| `SUM(col)` | yes | **refused**, PRV-2020 | no | Accumulates in 64-bit integers. **The result is a `BIGINT`** (`INT64`) over a `TINYINT`, `SMALLINT` or `INT` column too — a running sum outgrows 32 bits |
| `AVG(col)` | yes | **refused**, PRV-2020 | no | The exact sum over the count, as the column's integer type — except through the PostgreSQL gateway, which answers a `numeric` at sixteen places, as PostgreSQL does (AVGINT-1) |
| `MIN(col)`, `MAX(col)` | yes | **refused**, PRV-2020 | — | The column's own type: `MIN` of an `INT` is an `INT`. Take no retraction: over a source that retracts (CDC, a file with an operation column) they are refused at registration, [PRV-2076](/help/codes/PRV-2076) |
| Over an expression — `SUM(qty * price_cents)` | yes | | | |

`SUM`, `AVG`, `MIN` and `MAX` over a float column are all refused because every accumulator reads and
writes a 64-bit integer, whatever the column's type — before the refusal existed, a float aggregate
produced no rows under a success status. Only `COUNT` of a float column plans, because it never reads
the value. The refusal suggests the cast that works: `SUM(CAST(price AS BIGINT))`, if the rounding is
acceptable. Better still, carry money and
measurements in integer minor units (cents, hundredths of a degree) from the source.

## Three places an aggregate can run

| | Over a stream, windowed | Over a stream, global | Over a view (a read) |
|---|---|---|---|
| `GROUP BY key` | yes, with `window_start, window_end` | **refused**, PRV-2050 | yes |
| No `GROUP BY` | — | yes — one group | yes |
| `COUNT(DISTINCT)` | yes | refused, PRV-2050 | yes |
| `HAVING` | yes | yes | yes |
| State | keys x *open* windows | one row | bounded by the scan |

### Windowed, over a stream

The primary use. Per-merchant totals every minute:

```sql
CREATE CONTINUOUS QUERY merchant_minute
    KEYED BY (merchant, window_end)
AS
SELECT merchant, window_start, window_end,
       COUNT(*) AS txn_count,
       SUM(amount) AS spend,
       MIN(amount) AS smallest,
       MAX(amount) AS largest,
       AVG(amount) AS mean_amount,
       COUNT(DISTINCT user_id) AS payers
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end;
```

With the sample payments (1200 and 50 from `ann` at `acme`, 250 from `bob` at `bolt`, 400 from `cat`
at `corner`, all in the 09:00 minute), once the minute closes:

<!-- sql: read -->
```sql
SELECT merchant, txn_count, spend, smallest, largest, mean_amount, payers FROM merchant_minute
```

```text
merchant  txn_count  spend  smallest  largest  mean_amount  payers
acme      2          1250   50        1200     625          1
bolt      1          250    250       250      250          1
corner    1          400    400       400      400          1
```

An aggregate over an expression, per symbol:

```sql
SELECT symbol, window_start, window_end,
       SUM(qty) AS shares,
       SUM(qty * price_cents) AS notional_cents,
       SUM(qty * price_cents) / SUM(qty) AS vwap_cents
FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY symbol, window_start, window_end
```

Grouping by more than one column, with a `WHERE` before the aggregate:

```sql
SELECT region, status, window_start, window_end, COUNT(*) AS orders, SUM(amount) AS revenue
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
WHERE amount > 0
GROUP BY region, status, window_start, window_end
```

### Global, over a stream

No `GROUP BY` at all is one group, and one group is bounded:

```sql
SELECT COUNT(*) AS txn_count, SUM(amount) AS total_amount, MAX(amount) AS largest FROM txn
```

```sql
SELECT MIN(price_cents) AS lowest_cents, MAX(price_cents) AS highest_cents FROM trades
```

The view holds one row, revised with every commit: after the four sample payments,
`txn_count = 4`, `total_amount = 1900`, `largest = 1200`.

### Keyed, over a stream — refused

<!-- sql: refused PRV-2050 -->
```sql
SELECT merchant, SUM(amount) AS spend FROM txn GROUP BY merchant
```

This is a decision, not a gap. A stream has no end, so this aggregate keeps one entry per distinct
merchant **for ever**: its memory is a function of how many merchants ever appear, not of anything
configured. It does not fail the day it ships; it fails months later, and it looks innocent in the
review that approved it. There is no setting that fixes it. Add a window.

### Over a view — a read

Over a bounded read of a view the same argument does not hold, because the scan ends. This is what a
dashboard asks:

<!-- sql: read -->
```sql
SELECT tier, COUNT(*) AS user_hours, SUM(total) AS spend, AVG(total) AS mean_total,
       COUNT(DISTINCT user_id) AS users
FROM user_volume
GROUP BY tier
```

<!-- sql: read -->
```sql
SELECT region, SUM(revenue) AS revenue, SUM(orders) AS order_count, MAX(revenue) AS best_window
FROM region_revenue
GROUP BY region
HAVING SUM(revenue) > ?
```

<!-- sql: read -->
```sql
SELECT COUNT(*) AS large_payments, SUM(amount) AS large_total FROM big_txn
```

<!-- sql: read -->
```sql
SELECT symbol, SUM(shares) AS shares_today, SUM(fills) AS fills_today
FROM symbol_volume
GROUP BY symbol
```

The operator caps distinct groups and refuses rather than grows without limit, and row order is
stable between identical reads — there is no `ORDER BY` to make it meaningful, but an answer that
shuffled would cost somebody an afternoon.

## `HAVING`

A filter above the aggregate, on a stream or a view:

```sql
SELECT user_id, window_start, window_end, COUNT(*) AS txn_count, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' MINUTE))
GROUP BY user_id, window_start, window_end
HAVING COUNT(*) >= 5 OR SUM(amount) > 5000
```

That is a velocity check: users with five or more payments, or more than 5,000, in ten minutes.

## NULL in aggregates

Two rules that follow SQL rather than convenience:

- **NULL is a group.** Rows with no value for a group column gather under one NULL key — unlike a
  comparison, where NULL is UNKNOWN and the row is filtered out.
- **Aggregates skip NULLs.** `COUNT(col)` and `COUNT(DISTINCT col)` do not count them; `COUNT(*)`
  does. `status` is NULL for one sample payment, so over the four: `COUNT(*) = 4`,
  `COUNT(status) = 3`.
- **A group with no non-null value has a NULL `SUM`, `AVG`, `MIN` and `MAX`** — in a window, a
  continuous query, over a view and on a read — and `COUNT(col)` of it is 0. A retraction that
  leaves a group only NULLs makes them NULL again. Until 2.0.1 (ALLNULLAGG-1) they were published 0,
  which no reader could tell from a real total of zero; `COALESCE(SUM(x), 0)` asks for that answer.
- **A `DOUBLE` or `REAL` group follows SQL equality**: `-0.0` and `0.0` are one group, published
  `0.0`, and every `NaN` is one group (NANGROUP-1, 2.0.1).

```sql
SELECT currency, window_start, window_end,
       COUNT(*) AS all_rows,
       COUNT(status) AS with_status
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY currency, window_start, window_end
```

A counter written as a `SUM` of a `CASE` is a `BIGINT`, like any `SUM` of an integer — so a sink's
`schema` names it `INT64`, whatever the literals' type:

```sql
SELECT window_start, window_end,
       SUM(CASE WHEN status = 'SETTLED' THEN 1 ELSE 0 END) AS settled,
       COUNT(*) AS payments
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end
```

A nullable integer aggregates the same way:

```sql
SELECT site, window_start, window_end,
       MIN(battery_pct) AS lowest_battery,
       COUNT(battery_pct) AS reporting_devices
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY site, window_start, window_end
```

## Name your columns

An output column takes its alias. An unaliased aggregate has no name to take, so `COUNT(*)` comes back
as `EXPR$1`. That matters twice: `KEYED BY` names columns, and readers select them.

```sql
SELECT merchant, window_start, window_end, COUNT(*) AS txn_count
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end
```

## Float columns

<!-- sql: refused PRV-2020 -->
```sql
SELECT site, window_start, window_end, AVG(temperature) AS mean_temperature
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT site, window_start, window_end, MIN(temperature) AS coldest
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

`COUNT` over a float column is fine; so is casting to an integer first (the cast truncates — scale
first if you need precision):

```sql
SELECT site, window_start, window_end,
       COUNT(temperature) AS samples,
       SUM(CAST(temperature * 100 AS BIGINT)) AS centidegree_sum,
       MAX(CAST(temperature * 100 AS BIGINT)) AS hottest_centidegrees
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

## How an aggregate changes over time

A windowed aggregate is published when its window closes; before that, nothing. A global aggregate,
or an aggregate over a view, changes with every commit — and a subscriber sees each change as a
retraction of the old row at `-1` and an insertion of the new at `+1`, in the same commit. A reader of
the view only ever sees the current row.

```text
commit 1   total_amount=1200   +1
commit 2   total_amount=1200   -1
           total_amount=1450   +1
```

If you maintain your own total from a subscription, **apply the weights** — ignore them and your
total drifts from the view's the first time a row is corrected.

## Refusals, in one place

| Query | Code | Fix |
|---|---|---|
| `GROUP BY key` on a stream, no window | PRV-2050 | Add a window, or aggregate the view at read time |
| Windowed, but `GROUP BY` lacks `window_start` or `window_end` | PRV-2050 | Group by both |
| `COUNT(DISTINCT)` on a stream, no window | PRV-2050 | Add a window |
| `SUM`/`AVG`/`MIN`/`MAX` over a float | PRV-2020 | Integer units, or a cast |
| `GROUPING SETS`, `CUBE`, `ROLLUP` | PRV-2020 | One query per grouping level |
| `SESSION` window | PRV-2020 | Tumbling or hopping |

## Where next

- [Windows, worked](/help/topics/windows#worked-examples) — when each window is published.
- [Z-set weights](/help/topics/zset-weights) — why an aggregate can be maintained incrementally.
- [Point reads](/help/topics/views-and-keys#point-reads) — reading an aggregate view.
- How it is built: [Architecture: runtime](/help/architecture-runtime)
