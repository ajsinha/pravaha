---
title: The SQL reference
slug: sql-reference
category: sql
order: 10
icon: code-square
summary: "Everything the engine runs, construct by construct: projection, filters, expressions, aggregation, windows, joins, derived tables — each with an example the build plans against the real engine."
badge: REFERENCE
audience: Analysts and developers
keywords: [select, where, having, case, like, in, between, cast, substring, upper, lower, trim, concat, tumble, hop, join, with, cte, derived table, select stream]
guide: continuous-queries#10-continuous-queries-and-view-reads-run-the-same-sql
related: [sql-refusals, create-continuous-query, windows-worked, joins, aggregation, sql-types]
---

Pravaha runs **the shape of query people actually write against a stream**: pick columns, filter
them, compute a few expressions, join on a key, aggregate over a window. It does not try to be a
general analytics engine, and every construct it cannot run is refused *when the query is planned*,
with a `PRV-` code and a sentence saying why — never accepted and then approximated.

This page is the positive half: what runs. [What is refused, and why](/help/topics/sql-refusals)
is the other half. Every example below is planned by the engine's own planner and plan builder --
the same code path `POST /api/v1/queries/validate` and the workbench use — when the build runs, so
if a construct stops working, this page fails the build rather than misleading you.

## One planner, two uses

There is exactly one SQL implementation. A **continuous query** registered against a stream and a
**read** of a maintained view both go through the same planner and the same physical operators, so
a `WHERE` means precisely the same thing in both.

| | Continuous query | Read of a view |
|---|---|---|
| Registered with | `CREATE CONTINUOUS QUERY`, `pravaha register`, `client.register(...)` | `pravaha query`, `client.query(...)`, psql, the workbench's Run |
| Reads from | one or more **streams** (and lookup tables) | exactly **one view** |
| Lives | until dropped, maintained incrementally | one request, then gone |
| Unwindowed `GROUP BY key` | refused, PRV-2050 — its state would grow for ever | **supported** — the scan ends |
| `?` parameters | bound at registration (see [parameters](/help/topics/sql-parameters)) | bound per call |

The one asymmetry, the unwindowed `GROUP BY`, is about the *input*, not the query: over an endless
stream its state never stops growing, over a bounded scan of a view it is bounded by the scan.

The examples below run against the streams the rest of the help uses:

```text
txn        (txn_id BIGINT, user_id VARCHAR, merchant VARCHAR, amount BIGINT, currency VARCHAR,
            status VARCHAR NULL, event_time TIMESTAMP)                -- card payments
orders     (order_id BIGINT, customer_id VARCHAR, region VARCHAR, amount BIGINT, status VARCHAR, event_time)
shipments  (shipment_id BIGINT, order_id BIGINT, carrier VARCHAR, event_time)
trades     (trade_id BIGINT, account VARCHAR, symbol VARCHAR, side VARCHAR, qty BIGINT, price_cents BIGINT, event_time)
quotes     (symbol VARCHAR, bid_cents BIGINT, ask_cents BIGINT, event_time)
readings   (device_id VARCHAR, site VARCHAR, temperature DOUBLE, battery_pct INTEGER NULL, event_time)
lookup tables: user_profile (user_id, tier, region), customers (customer_id, segment, country),
               instruments (symbol, sector, lot_size)
```

## Projection — `SELECT`

| Construct | Runs | Notes |
|---|---|---|
| `SELECT a, b`, `SELECT *` | yes | |
| Column aliases — `amount AS a` | yes | An output column takes its alias, else its own name |
| Table aliases — `FROM txn AS t`, `FROM txn t` | yes | With or without `AS`; an alias may shadow another stream's name |
| Qualified columns — `t.amount`, `t.*` | yes | `t.amount` produces a column called `amount` |
| Arithmetic — `+ - * / %`, `MOD(a, b)` | yes | Integer and floating point; the remainder takes the dividend's sign |
| `CAST(x AS DOUBLE)` between numeric types | yes | Casts to or from text are refused |
| Literals — `SELECT 1`, `SELECT 'flagged'` | yes | One per row |
| `CASE WHEN ... THEN ... [ELSE ...] END` | yes | Only the branch taken is evaluated; no `ELSE` means NULL |
| `ABS`, `FLOOR`, `CEIL`, `ROUND` | yes | One argument each |
| `UPPER`, `LOWER`, `TRIM`, `SUBSTRING`, `\|\|` | yes | Root locale; positions in code points |
| A boolean that cannot be UNKNOWN — `amount > 50`, `status IS NULL` | yes | See [types and NULLs](/help/topics/sql-types) |
| `SELECT DISTINCT` | no | PRV-2050 over a stream |

Plain columns and aliases:

```sql
SELECT txn_id, user_id, amount AS amount_cents
FROM txn
```

Qualified columns with a table alias, and an arithmetic expression:

```sql
SELECT t.txn_id, t.amount * 2 + 1 AS doubled_plus_one, t.amount % 3 AS remainder
FROM txn AS t
```

With the four sample rows used throughout this page --

```text
txn_id  user_id  merchant  amount  currency  status    event_time
1       ann      acme      1200    USD       SETTLED   2026-09-19 09:00:03
2       bob      bolt      250     EUR       PENDING   2026-09-19 09:00:07
3       ann      acme      50      USD       NULL      2026-09-19 09:00:12
4       cat      corner    400     USD       SETTLED   2026-09-19 09:00:18
```

-- the query above maintains:

```text
txn_id  doubled_plus_one  remainder
1       2401              0
2       501               1
3       101               2
4       801               1
```

`SELECT *` and the qualified star mean the same thing:

```sql
SELECT t.* FROM txn AS t
```

Numeric conversion and the four numeric functions:

```sql
SELECT txn_id,
       CAST(amount AS DOUBLE) / 100 AS amount_units,
       MOD(amount, 7) AS mod_seven,
       ABS(amount) AS magnitude
FROM txn
```

```sql
SELECT device_id,
       ROUND(temperature) AS rounded,
       FLOOR(temperature) AS floor_c,
       CEIL(temperature) AS ceil_c
FROM readings
```

A `CASE` with several branches — the first true branch wins:

```sql
SELECT txn_id,
       CASE WHEN amount > 1000 THEN 'large'
            WHEN amount > 100 THEN 'medium'
            ELSE 'small' END AS size_band
FROM txn
```

```text
txn_id  size_band
1       large
2       medium
3       small
4       medium
```

Only the branch that is taken is evaluated, so a guarded division never divides by zero:

```sql
SELECT order_id,
       CASE WHEN amount = 0 THEN 0 ELSE 10000 / amount END AS per_ten_thousand
FROM orders
```

Text functions and concatenation:

```sql
SELECT txn_id,
       UPPER(merchant) AS merchant_upper,
       LOWER(currency) AS currency_lower,
       TRIM(merchant) AS merchant_trimmed,
       SUBSTRING(merchant FROM 1 FOR 3) AS merchant_prefix,
       user_id || ':' || merchant AS who_where
FROM txn
```

```text
txn_id  merchant_upper  currency_lower  merchant_trimmed  merchant_prefix  who_where
1       ACME            usd             acme              acm              ann:acme
2       BOLT            eur             bolt              bol              bob:bolt
3       ACME            usd             acme              acm              ann:acme
4       CORNER          usd             corner            cor              cat:corner
```

!!! note "Name every computed column"
    An unaliased expression has no name to take, so it comes back as `EXPR$1`. Write
    `COUNT(*) AS txn_count`, not `COUNT(*)` — a `KEYED BY` clause has to name its columns, and a
    dashboard should not show `EXPR$2`.

A projected boolean is allowed when it can only be TRUE or FALSE:

```sql
SELECT txn_id,
       amount > 500 AS is_large,
       status IS NULL AS status_missing,
       CASE WHEN status = 'SETTLED' THEN TRUE ELSE FALSE END AS is_settled
FROM txn
```

## Filtering — `WHERE` and `HAVING`

| Construct | Runs | Notes |
|---|---|---|
| `=`, `<>`, `<`, `<=`, `>`, `>=` | yes | On text only `=` and `<>` |
| `AND`, `OR`, `NOT` | yes | `NOT` is pushed down at compile time |
| `IN (a, b, c)` | yes | Expanded to a chain of equalities |
| `BETWEEN a AND b` | yes | Inclusive at both ends |
| `IS NULL`, `IS NOT NULL` | yes | Over a column; over an expression it is refused |
| `LIKE`, `NOT LIKE` against a literal pattern | yes | Compiled once at registration; no `ESCAPE` |
| A bare boolean column, arithmetic in a predicate | yes | |
| `HAVING` | yes | A filter above the aggregate |

```sql
SELECT txn_id, amount
FROM txn
WHERE amount > 1000 OR (merchant = 'acme' AND NOT currency = 'USD')
```

```sql
SELECT txn_id, merchant, amount
FROM txn
WHERE merchant LIKE 'ac%'
  AND currency IN ('USD', 'EUR')
  AND amount BETWEEN 100 AND 500
```

`BETWEEN` includes both ends, so `amount BETWEEN 50 AND 250` keeps the 50 and the 250.

```sql
SELECT txn_id FROM txn WHERE amount * 2 > 100
```

```sql
SELECT txn_id, user_id FROM txn WHERE status IS NULL
```

```sql
SELECT txn_id FROM txn WHERE status IS NOT NULL AND status = 'SETTLED'
```

Three-valued logic is honoured: a comparison with NULL is UNKNOWN, and a filter keeps only rows
where the predicate is TRUE. So `status = 'SETTLED'` never keeps a row whose `status` is NULL, and
neither does `status <> 'SETTLED'`.

`HAVING` filters the output of an aggregate:

```sql
SELECT merchant, window_start, window_end, COUNT(*) AS n, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
GROUP BY merchant, window_start, window_end
HAVING COUNT(*) >= 3 AND SUM(amount) > 500
```

## Aggregation and windows

| Construct | Over a stream | Over a view |
|---|---|---|
| Global `COUNT(*)`, `SUM`, `MIN`, `MAX`, `AVG` | yes — one group | yes |
| `TUMBLE` / `HOP` windows | yes | — |
| `GROUP BY key` without a window | **no**, PRV-2050 | yes |
| `COUNT(DISTINCT x)` | windowed only | yes |
| `SUM`/`AVG`/`MIN`/`MAX` over `FLOAT64` | no, PRV-2020 | no, PRV-2020 |
| `SESSION` windows | no, PRV-2020 | — |

A global aggregate is one group, so it is bounded:

```sql
SELECT COUNT(*) AS txn_count, SUM(amount) AS total_amount FROM txn
```

A tumbling window per merchant:

```sql
SELECT merchant, window_start, window_end,
       COUNT(*) AS txn_count, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end
```

A hopping window — a minute of history, advanced every ten seconds:

```sql
SELECT symbol, window_start, window_end,
       SUM(qty) AS shares, MIN(price_cents) AS low_cents, MAX(price_cents) AS high_cents,
       COUNT(DISTINCT account) AS accounts
FROM TABLE(HOP(TABLE trades, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '1' MINUTE))
GROUP BY symbol, window_start, window_end
```

The same over a maintained view needs no window, because the scan ends:

<!-- sql: read -->
```sql
SELECT tier, COUNT(*) AS users, SUM(total) AS spend
FROM user_volume
GROUP BY tier
```

[Aggregation](/help/topics/aggregation) and [windows, worked](/help/topics/windows-worked) take
each of these apart with expected output.

## Joins

| Construct | Runs | Notes |
|---|---|---|
| `INNER JOIN` on equality, one or several columns | yes | Symmetric hash join, incremental both ways |
| Equality plus a time bound, both-sided or one-sided | yes | The bound also decides how long state is kept |
| `LEFT JOIN` with a time bound | yes | The null-padded row is emitted once, when the window passes |
| Three streams or more, all distinct | yes | |
| Temporal lookup, `FOR SYSTEM_TIME AS OF` | yes | A key-value read per row; no state |
| Self join, `RIGHT`, `FULL`, `CROSS`, non-equi | no | See [joins](/help/topics/joins) |

```sql
SELECT o.order_id, o.region, s.carrier
FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' HOUR
```

```sql
SELECT t.txn_id, t.amount, p.tier
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
```

## Derived tables, `WITH`, `SELECT STREAM`

A derived table, a common table expression, and `SELECT STREAM` (a synonym — every query here is
a streaming query) all plan:

```sql
SELECT x.user_id, x.amount
FROM (SELECT user_id, amount FROM txn WHERE amount > 100) AS x
WHERE x.amount < 1000
```

```sql
WITH over_hundred AS (SELECT user_id, merchant, amount FROM txn WHERE amount > 100)
SELECT user_id, merchant, amount FROM over_hundred
```

```sql
SELECT STREAM txn_id, amount FROM txn
```

## Reads of a maintained view

A read is ordinary SQL over one view. It supports everything above, plus the unwindowed `GROUP BY`,
`COUNT(DISTINCT)` without a window and parameters in `WHERE` and `HAVING`:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend WHERE spend > 1000
```

<!-- sql: read -->
```sql
SELECT symbol, shares, notional_cents FROM symbol_volume WHERE symbol IN ('ACME', 'BOLT')
```

<!-- sql: read -->
```sql
SELECT region, SUM(revenue) AS revenue, SUM(orders) AS order_count
FROM region_revenue
GROUP BY region
HAVING SUM(revenue) > 100000
```

<!-- sql: read -->
```sql
SELECT tier, COUNT(DISTINCT user_id) AS distinct_users, AVG(total) AS mean_total
FROM user_volume
GROUP BY tier
```

<!-- sql: read -->
```sql
SELECT txn_id, amount FROM big_txn WHERE user_id = ?
```

A read names **exactly one view** — joining two views in one request is refused with PRV-4025 --
and it has no `ORDER BY` or `LIMIT` (PRV-2020); row order between identical reads is stable, but it
is not a sort.

## Try it

Every example on this page can be pasted into the workbench, which validates as you type and
underlines a refusal where it happens. From a shell:

```bash
pravaha query --sql "SELECT user_id, window_end, spend FROM hourly_spend WHERE spend > 1000"
```

```text
user_id	window_end	spend
ann	2026-09-19 10:00:00	1250
1 row
```

(The values are illustrative — they are whatever your view holds.)

## Where next

- [What is refused, and why](/help/topics/sql-refusals) — every refusal with its rewrite.
- [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query) — registering one of these.
- [Types, NULLs and expressions](/help/topics/sql-types) — the fine print on each expression.
- [Streams, queries and SQL](/help/continuous-queries#11-projection-select) — the long form.
