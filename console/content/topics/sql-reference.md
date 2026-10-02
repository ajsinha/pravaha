---
title: The SQL reference
slug: sql-reference
category: sql
order: 10
icon: code-square
summary: "Everything the engine runs, construct by construct — projection, filters, expressions, aggregation, windows, joins, derived tables — then types, NULLs and casts, and ? parameters: each with an example the build plans against the real engine."
badge: REFERENCE
audience: Analysts and developers
keywords: [select, where, having, case, like, in, between, cast, substring, upper, lower, trim, concat, tumble, hop, join, with, cte, derived table, select stream, types, bigint, integer, double, varchar, boolean, timestamp, date, time, bytes, varbinary, decimal, null, three-valued logic, unknown, concatenation, PRV-3030, 64 columns, placeholder, question mark, prepared statement, bind, "$1", injection, PRV-2060, PRV-2061, PRV-2062, PRV-2063, tap, registration, fingerprint, ADR-032]
guide: continuous-queries#10-continuous-queries-and-view-reads-run-the-same-sql
related: [sql-refusals, create-continuous-query, windows, joins, aggregation, views-and-keys]
---

Pravaha runs **the shape of query people actually write against a stream**: pick columns, filter
them, compute a few expressions, join on a key, aggregate over a window. It does not try to be a
general analytics engine, and every construct it cannot run is refused *when the query is planned*,
with a `PRV-` code and a sentence saying why — never accepted and then approximated.

This page is the positive half: what runs — then [types, NULLs and expressions](#types-nulls-and-expressions) and [parameters](#parameters). [What is refused, and why](/help/topics/sql-refusals)
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
| `?` parameters | bound at registration (see [parameters](/help/topics/sql-reference#parameters)) | bound per call |

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
| A boolean that cannot be UNKNOWN — `amount > 50`, `status IS NULL` | yes | See [types and NULLs](/help/topics/sql-reference#types-nulls-and-expressions) |
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
| `SUM`/`MIN`/`MAX` over `DECIMAL` | yes, exact at the column's scale | yes |
| `AVG` over `DECIMAL` | no, PRV-2021 (a quotient would be rounded) | no, PRV-2021 |
| `MIN`/`MAX` over a source that retracts (CDC, an operation column) | no, PRV-2076 | — |
| A `HOP` a row would land in more than `pravaha.lane.max-windows-per-row` windows of | no, PRV-3026 | — |
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

[Aggregation](/help/topics/aggregation) and [windows, worked](/help/topics/windows#worked-examples) take
each of these apart with expected output.

## Joins

| Construct | Runs | Notes |
|---|---|---|
| `INNER JOIN` on equality, one or several columns | yes | Symmetric hash join, incremental both ways |
| Equality plus a time bound, both-sided or one-sided | yes | The bound also decides how long state is kept |
| `LEFT JOIN` with a time bound | yes | The null-padded row is emitted once, when the window passes |
| Three streams or more, all distinct | yes | |
| Temporal lookup, `FOR SYSTEM_TIME AS OF` | yes | A key-value read per row; no state. A condition on a looked-up column is refused (PRV-2020) in `ON`, and in `WHERE` over an inner lookup — write the join `LEFT` and filter in `WHERE`: see [temporal joins](/help/topics/joins#temporal-joins) |
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

## Types, NULLs and expressions {#types-nulls-and-expressions}

A stream's schema is declared, not inferred — `name:TYPE,name:TYPE`, with `?` for a nullable column
-- in `pravaha.streams.<name>.schema`, in `POST /api/v1/streams`, and in a source's `schema` option.
Sniffing types from the first rows guesses wrong on exactly the columns that matter: an identifier of
all digits becomes an integer, and the first row containing a letter fails at 3 a.m.

### The types

| Schema type | SQL type | Arithmetic | Aggregates | Notes |
|---|---|---|---|---|
| `BOOLEAN` | `BOOLEAN` | — | `COUNT` | A bare column is a valid predicate: `WHERE flagged` |
| `INT8`, `INT16`, `INT32`, `INT64` | `TINYINT` ... `BIGINT` | yes | all | The type for money: integer minor units |
| `FLOAT32`, `FLOAT64` | `REAL`, `DOUBLE` | yes | `COUNT` only | `SUM`/`AVG`/`MIN`/`MAX` refused, PRV-2020 |
| `STRING` | `VARCHAR` | — | `COUNT` | UTF-8; `=` and `<>` only |
| `BYTES` | `VARBINARY` | — | — | Carried over Flight; refused by the PostgreSQL gateway |
| `DATE`, `TIME`, `TIMESTAMP` | same | intervals | `COUNT` | `TIMESTAMP` carries event time, in nanoseconds |
| `DECIMAL(p,s)` | `DECIMAL` | exact `+ − ×`, `SUM`, `MIN`, `MAX`; `÷` and `AVG` refused | — | Declarable only programmatically |

Year-month intervals (`INTERVAL '1' MONTH`) are refused, because a month is not a fixed length of
time; day-time intervals (`SECOND` to `WEEK`) work, and are what windows use.

Timestamp arithmetic with a day-time interval plans:

```sql
SELECT txn_id, event_time, event_time - INTERVAL '1' HOUR AS an_hour_earlier FROM txn
```

A nullable integer computes like any other; the result is NULL where the input is:

```sql
SELECT device_id, battery_pct, battery_pct + 5 AS after_charge, CAST(battery_pct AS BIGINT) AS as_bigint
FROM readings
WHERE battery_pct < 20
```

### NULL and three-valued logic

A comparison with NULL is neither true nor false — it is **UNKNOWN**. Each clause then does
something specific with UNKNOWN:

| Where | What NULL / UNKNOWN does |
|---|---|
| `WHERE`, `HAVING`, a join's `ON` | The row is kept only if the predicate is TRUE; UNKNOWN drops it |
| `GROUP BY` | NULL is a group: every row with a NULL key gathers under one NULL key |
| `COUNT(col)`, `COUNT(DISTINCT col)` | NULLs are not counted; `COUNT(*)` counts rows |
| `SUM`, `AVG`, `MIN`, `MAX` | NULLs are skipped; a group with no non-null value answers NULL (0 before 2.0.1) |
| Arithmetic | NULL in, NULL out |
| `\|\|` concatenation | NULL concatenated with anything is NULL — not an empty string |
| A projected boolean | Must not be able to be UNKNOWN (below) |
| A parameter bound to NULL | `x = ?` matches nothing; `IS NULL` is what finds the empty ones |

`status` is nullable in the sample payments (1 SETTLED, 2 PENDING, 3 NULL, 4 SETTLED). So:

```sql
SELECT txn_id FROM txn WHERE status = 'SETTLED'
```

keeps rows 1 and 4, and

```sql
SELECT txn_id FROM txn WHERE status <> 'SETTLED'
```

keeps only row 2 — row 3 (NULL status) is in neither. To include it, say so:

```sql
SELECT txn_id FROM txn WHERE status <> 'SETTLED' OR status IS NULL
```

The truth-value tests are total by definition, so they project freely:

```sql
SELECT txn_id,
       status IS NULL AS no_status,
       status IS NOT NULL AS has_status
FROM txn
```

#### A projected boolean must be TRUE or FALSE

A boolean column holds two values; SQL comparisons produce three. `SELECT amount > 50` is fine,
because `amount` is `NOT NULL` and the comparison cannot be UNKNOWN. Over a nullable column it is
refused — writing UNKNOWN into a boolean column would report it as `false`, a wrong answer under a
success exit code:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, status = 'SETTLED' AS settled FROM txn
```

Say which you mean. `IS TRUE` collapses UNKNOWN to `FALSE` deliberately, `IS NOT FALSE` to `TRUE`
(a `CASE WHEN ... THEN TRUE ELSE FALSE END` is the long form of `IS TRUE`):

```sql
SELECT txn_id,
       amount > 50 AS over_fifty,
       (status = 'SETTLED') IS TRUE AS settled
FROM txn
```

### `CASE`

Any number of branches, with or without `ELSE`. Only the branch taken is evaluated; with no `ELSE`,
an unmatched row is NULL — not zero:

```sql
SELECT txn_id, CASE WHEN amount > 1000 THEN 1 END AS large_flag FROM txn
```

```text
txn_id  large_flag
1       1
2       NULL
3       NULL
4       NULL
```

Every branch must produce the same type, and there is no longer an exception. A number in a text
`CASE` used to be accepted — the validator coerces `0` to the string `'0'` before the engine sees
it, and the column came out as text — while the same `CASE` with a numeric *column* in the other
branch was refused. There is no number-to-text conversion anywhere in this engine, so the literal
form was the odd one out and is refused now (TY-23):

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, CASE WHEN amount > 100 THEN 'big' ELSE 0 END AS band FROM txn
```

Write the branch as text, and it plans:

```sql
SELECT txn_id, CASE WHEN amount > 100 THEN 'big' ELSE 'small' END AS band FROM txn
```

Branches that disagree on a *numeric* type are refused too, and by the decimal rule rather than by
a rule of their own: `CASE WHEN c THEN 1 ELSE 1.5 END` is `DECIMAL` to SQL, exactly as
`amount * 1.5` is. It used to escape as an uncoded Java exception (TY-4).

A guarded division is safe, because the untaken branch is never evaluated:

```sql
SELECT order_id, CASE WHEN amount = 0 THEN 0 ELSE 1000000 / amount END AS inverse_millionths
FROM orders
```

### Numbers

| Expression | Runs | Notes |
|---|---|---|
| `+ - * /` | yes | Integer or floating; integer division truncates |
| `%`, `MOD(a, b)` | yes | Integers and floats; the remainder takes the dividend's sign, so `-50 % 3` is `-2` |
| `ABS`, `FLOOR`, `CEIL`, `ROUND` | yes | One argument each |
| `ROUND(x, 2)` | no | PRV-2021 — scale, round, and name the scale |
| `SQRT`, `POWER`, `LN`, ... | no | PRV-2021 |
| `CAST` between numeric types | yes | A value the target cannot hold — `NaN`, `±Infinity`, past its range — is an overflow, not `0` or a clamped extreme |
| A result outside its type's range | — | An overflow, never wrapped: `INT * INT` is an `INT`, and `2e9 * 2` stops the query naming the expression (or, with a dead-letter queue, the row is dead-lettered, PRV-3027). `CAST(i AS BIGINT) * 2` asks for 64 bits |
| `CAST` to or from text or dates | no | PRV-2021 |

```sql
SELECT trade_id,
       qty * price_cents AS notional_cents,
       price_cents / 100 AS price_whole,
       price_cents % 100 AS price_cents_part,
       ABS(qty) AS abs_qty
FROM trades
```

```sql
SELECT device_id,
       ROUND(temperature * 10) AS decidegrees,
       CAST(temperature AS BIGINT) AS whole_degrees
FROM readings
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT device_id, ROUND(temperature, 1) AS t FROM readings
```

### Strings

| Function | Runs | Notes |
|---|---|---|
| `UPPER(s)`, `LOWER(s)` | yes | Root locale, so the answer does not depend on the machine the lane runs on |
| `TRIM(s)` | yes | Spaces, both ends. `TRIM(LEADING ...)` and other characters are refused |
| `SUBSTRING(s FROM i [FOR n])` | yes | 1-based, counted in code points — a substring never splits an emoji |
| `a \|\| b` | yes | Any length of chain; NULL anywhere makes NULL |
| `s LIKE 'pat%'` | yes | Literal pattern only; no `ESCAPE` |
| `=`, `<>` against a literal | yes | |
| `<`, `>` on text | no | PRV-2021 — needs a collation |
| `REPLACE`, `POSITION`, `CHAR_LENGTH`, `LPAD` | no | PRV-2021 |

```sql
SELECT txn_id,
       UPPER(TRIM(merchant)) || '/' || currency AS label,
       SUBSTRING(user_id FROM 2) AS user_tail,
       SUBSTRING(merchant FROM 1 FOR 2) AS merchant_head
FROM txn
```

```text
txn_id  label        user_tail  merchant_head
1       ACME/USD     nn         ac
2       BOLT/EUR     ob         bo
3       ACME/USD     nn         ac
4       CORNER/USD   at         co
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT TRIM(LEADING ' ' FROM merchant) AS m FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT TRIM('x' FROM merchant) AS m FROM txn
```

Evaluating a string allocates one where the numeric path does not. It costs what the engine already
spends to compare strings, but a text projection is not the place to put your hottest query.

### Casts

```sql
SELECT txn_id, CAST(amount AS DOUBLE) / 3 AS third, CAST(amount AS INTEGER) AS narrowed FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT CAST(user_id AS BIGINT) AS numeric_user FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT CAST(amount AS VARCHAR) AS amount_text FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT CAST(event_time AS DATE) AS event_date FROM txn
```

### DECIMAL

`DECIMAL` addition, subtraction and multiplication are computed **exactly**, at the precision and
scale SQL derives, and a query whose result would need rounding past 38 digits is refused at
registration. Division and remainder are still refused rather than rounded, because the rounding
decision belongs to whoever owns the ledger and not to a serialiser:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, CAST(amount AS DECIMAL(12, 2)) / 100 AS amount_major FROM txn
```

**`DECIMAL(p,s)` can be declared through a schema string** — `amt:DECIMAL(10,2)` in `--schema`,
`pravaha.streams.*.schema` or `POST /api/v1/streams`. It used to be cut at its own comma and fail as
`unknown type 'DECIMAL(10'`, because the `name:TYPE,name:TYPE` grammar was split on every comma
before any type was read (TY-7). A declared decimal is carried through scans, filters and
projections correctly.

**`SUM`, `MIN` and `MAX` of a decimal are exact** (DECSUM-1): `SUM` of a `DECIMAL(10, 2)` is a
`DECIMAL(38, 2)`, so a total may outgrow the column it adds up; `MIN` and `MAX` keep the column's
type. A value with more than 18 digits unscaled cannot be held by the 64-bit accumulators and is
refused `PRV-3020`, naming the column. **`AVG` of a decimal is refused `PRV-2021`**: it is a
quotient, SQL types it at the column's own scale, and answering would round most groups silently.
Ask for `SUM` and `COUNT` and divide where the rounding is yours.

**A decimal can be a group key, and counted distinct** — in a window, on a read of a view, and (the
key) in a continuous query over a view — by its whole unscaled value, so `1.50` and `2.75` are two
groups (WINDECKEY-1, DECKEYGROUP-1).

A schema string that will not parse answers **`PRV-1028`**, a configuration code, and names both
places: `stream 'd', column 'amt': unknown type 'DECIMAL'`. It used to answer `PRV-5040` — the
filesystem plugin's decode code — which made `POST /api/v1/streams` return `500` for a typo in the
caller's own request (TY-8), and it named neither the stream nor the column (TY-9).

**The rewrite everyone ends up with:** integer minor units. `amount` here is cents; a JDBC source can
do the conversion where the decimal already lives
(`CAST(o.amount * 100 AS BIGINT) AS amount_cents` in its `query`).

### Timestamps

`TIMESTAMP` is the event-time type — nanosecond precision end to end, so it spans 1677-09-21 to
2262-04-11 UTC; a value outside that is refused where it enters (a file line `PRV-5040`), never
wrapped. Arithmetic with a day-time interval works; truncating to a date by cast does not (use a
one-day window instead):

```sql
SELECT window_start, window_end, COUNT(*) AS txn_count
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' DAY))
GROUP BY window_start, window_end
```

### BYTES and TIME on the wire

Both serialise over Arrow Flight — the SDKs and the CLI receive them. The **PostgreSQL gateway
refuses `BYTES` and `TIME` by name**, and does so before sending a `RowDescription`, so a client gets
a clean error instead of a truncated result it might treat as complete. That is a gap in that
gateway's type mapping, not in the engine. Read such a view over Flight, or project the column away
for psql.

### Sixty-four columns — PRV-3030

A row cannot have more than 64 output columns. Every row is built by a writer that tracks which fields
have been written in one 64-bit word — one bit per field — and cannot represent a 65th. It binds the
**output** width whatever made it wide: `SELECT *` over a wide stream, a long select list, or a join
whose combined output passes 64.

It is architectural, not a setting. Note when it bites: validation accepts a 1,000-column projection,
because nothing writes a row during validation; the ceiling is met when rows start moving, with
PRV-3030. If a query is that wide, split it into several narrower ones keyed the same way.

### Name your columns

An output column takes its alias, else the column's own name; a qualified `t.amount` is `amount`. An
unaliased expression has no name to take and comes back as `EXPR$n`:

```sql
SELECT txn_id, amount * 2 AS doubled FROM txn
```

## Parameters {#parameters}

**A `?` belongs in a `WHERE` clause, and in `HAVING` because that is a filter too — and nowhere
else.** A parameter selects rows. Every other position a placeholder could occupy — the select list,
a window size, a group key, an aggregate argument, a table name — would make a *different query*
rather than a different binding of one, and is refused. That is the whole rule
([ADR-032](/help/decisions/032-parameters-are-values-not-queries)).

Pass values for `?` rather than building the SQL string. It is safer, and it is faster:

- **A bound value can never be read as SQL.** Binding happens when the physical plan is built, after
  parsing; there is no parser left for a value to reach. That is a stronger guarantee than escaping.
- **One question asked about a thousand users is one plan.** The server plans a statement once and
  reuses it; string interpolation would produce a thousand texts, a thousand plans, and a log in
  which one question looks like a thousand.
- **A bound value and a written literal compile to the same predicate** — not an equivalent one, the
  same one. The parameterised form cannot be slower or behave differently.

### Where a `?` may stand

| Position | Allowed | Notes |
|---|---|---|
| `WHERE col = ?`, `<> < <= > >=` | yes | |
| `WHERE col IN (?, ?, ?)` | yes | Expanded to a chain of equalities |
| `WHERE col BETWEEN ? AND ?` | yes | Both ends bind |
| Under `AND`, `OR`, `NOT` | yes | |
| `HAVING agg(col) > ?` | yes | A filter above the aggregate |
| Select list — `SELECT col * ?` | **no** | PRV-2063 (reads); PRV-2021 (continuous) |
| Window size, group key, table name | **no** | They decide what the query *is* |
| `WHERE col LIKE ?` | not yet | PRV-2021 — a pattern is compiled once, at planning |
| `ORDER BY ?`, `LIMIT ?` | not yet | There is no sort operator with a literal either |

Parameter **types are inferred**, not declared: `WHERE user_id = ?` takes a string because
`user_id` is one, and the server sends that parameter schema when the statement is prepared. Nothing
in either SDK guesses a type.

### Parameters in a read

Every one of these plans against the maintained views, with its placeholders bound per call:

<!-- sql: read -->
```sql
SELECT txn_id, merchant, amount FROM big_txn WHERE user_id = ?
```

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend WHERE spend BETWEEN ? AND ?
```

<!-- sql: read -->
```sql
SELECT symbol, window_end, shares FROM symbol_volume WHERE symbol IN (?, ?, ?)
```

<!-- sql: read -->
```sql
SELECT region, SUM(revenue) AS revenue FROM region_revenue WHERE region = ? GROUP BY region
```

<!-- sql: read -->
```sql
SELECT user_id, SUM(spend) AS spend_to_date
FROM hourly_spend
GROUP BY user_id
HAVING SUM(spend) > ?
```

<!-- sql: read -->
```sql
SELECT user_id, window_end, total FROM user_volume WHERE tier = ? AND (total > ? OR txn_count > ?)
```

#### Binding them

Python — values in a list, in placeholder order:

```python
from pravaha import ClientOptions, connect

with connect(options=ClientOptions.create("grpc+tls://pravaha:19090", token=token)) as client:
    for row in client.query("SELECT txn_id, merchant, amount FROM big_txn WHERE user_id = ?", ["ann"]):
        print(row["txn_id"], row["merchant"], row["amount"])
```

```text
1 acme 1200
```

Java — values as trailing arguments:

```java
try (QueryResult rows = client.query("SELECT txn_id, merchant, amount FROM big_txn WHERE user_id = ?", "ann")) {
    for (Row row : rows) {
        System.out.println(row.getString(0) + " " + row.getString(1) + " " + row.getString(2));
    }
}
```

The CLI — `--params`, comma-separated; a value that looks like a number is sent as one:

```bash
pravaha query --sql "SELECT user_id, window_end, spend FROM hourly_spend WHERE spend BETWEEN ? AND ?" \
  --params 1000,5000
```

```text
user_id	window_end	spend
ann	2026-09-19 10:00:00	1250
1 row
```

psql and any PostgreSQL driver — PostgreSQL's own `$1`, `$2` placeholders, which the gateway
rewrites to `?` before planning (psql 16 or later has `\bind`):

```text
pravaha=> SELECT txn_id, merchant, amount FROM big_txn WHERE user_id = $1 \bind 'ann' \g
 txn_id | merchant | amount
--------+----------+--------
      1 | acme     |   1200
(1 row)
```

A `$n` reused, or numbered out of order, is refused rather than guessed at — the rewrite could not
mean the same thing twice.

(The rows are illustrative: whatever your view holds.)

#### NULL

`WHERE tier = ?` bound to NULL matches **nothing**, because `x = NULL` is UNKNOWN for every row and a
filter keeps only TRUE. The engine follows the standard rather than rewriting the comparison into
`IS NULL` behind your back. If you mean "the ones with no tier", say so:

<!-- sql: read -->
```sql
SELECT user_id, window_end, total FROM user_volume WHERE tier IS NULL
```

### Refused positions

A placeholder in the select list of a read is refused with PRV-2063:

<!-- sql: read-refused PRV-2063 -->
```sql
SELECT user_id, spend * ? AS scaled FROM hourly_spend
```

A parameterised `LIKE` pattern with PRV-2021 (a literal pattern works):

<!-- sql: read-refused PRV-2021 -->
```sql
SELECT txn_id FROM big_txn WHERE merchant LIKE ?
```

<!-- sql: read -->
```sql
SELECT txn_id FROM big_txn WHERE merchant LIKE 'ac%'
```

A placeholder anywhere but a `WHERE` or `HAVING` clause is refused with PRV-2063, before binding is
considered — a projection, a group key, a window size and a bare `SELECT ?` all answer alike
(finding X-9; three different codes used to come back depending on where the `?` stood):

<!-- sql: refused PRV-2063 -->
```sql
SELECT txn_id, amount * ? AS scaled FROM txn
```

### When the values do not fit

| Code | Means | Example |
|---|---|---|
| PRV-2060 | A placeholder has no value bound | two `?`, no values sent |
| PRV-2061 | The number of values does not match the placeholders | two `?`, three values |
| PRV-2062 | A value's type is not the one inferred for its placeholder | `'abc'` for `spend > ?` |
| PRV-2063 | A `?` stands where the shape of the plan goes | `SELECT spend * ?` |

```bash
pravaha query --sql "SELECT user_id FROM hourly_spend WHERE spend BETWEEN ? AND ?" --params 1000
```

```text
PRV-2061  ... 2 placeholders, 1 value bound ...
```

(The message text is abbreviated; the code is the stable part.)

### Parameters in a continuous query

In a continuous query the same text asks one of two very different things, and the engine decides
which — and tells you — rather than guessing silently:

| Placement | When | Cost |
|---|---|---|
| `TAP` | The view **carries** the parameter's column | One computation serves every binding; a subscriber filters at its own tap, for nothing |
| `REGISTRATION` | The query **aggregates the column away** | Each distinct binding is a separate computation with its own state |

Binding into the query is always *correct*: the bound values are part of the plan and therefore part
of the fingerprint, so two bindings are honestly two computations. The classification makes sure the
expensive case is never the quiet one.

A parameter on a column the view keeps — the cheap case. Register it once, unbound, and let each
reader or subscriber filter:

```sql
CREATE CONTINUOUS QUERY payments_by_currency
    KEYED BY (txn_id)
AS SELECT txn_id, user_id, currency, amount FROM txn;
```

<!-- sql: read -->
```sql
SELECT txn_id, user_id, amount FROM payments_by_currency WHERE currency = ?
```

```bash
pravaha subscribe --view payments_by_currency --filter currency=EUR
```

A parameter on a column the aggregate removes — `currency` is filtered before the window and is not
in the output, so every currency bound is its own computation:

<!-- sql: parameterised -->
```sql
SELECT merchant, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
WHERE currency = ?
GROUP BY merchant, window_start, window_end
```

<!-- sql: parameterised -->
```sql
SELECT txn_id, user_id, amount FROM txn WHERE amount > ? AND currency IN (?, ?)
```

<!-- sql: parameterised -->
```sql
SELECT merchant, window_start, window_end, COUNT(*) AS n
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, window_start, window_end
HAVING COUNT(*) > ?
```

Often the better design is to keep the column and read with a parameter — one computation answers
every currency:

```sql
CREATE CONTINUOUS QUERY merchant_currency_hourly
    KEYED BY (merchant, currency, window_end)
AS
SELECT merchant, currency, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, currency, window_start, window_end;
```

<!-- sql: read -->
```sql
SELECT merchant, window_end, spend FROM merchant_currency_hourly WHERE currency = ?
```

#### How a registration binds values today

Values are bound into a continuous query through the registry's Java API — in the embedded engine,
`engine.registry()`. Ask first what a binding would cost:

```java
QueryRegistry registry = engine.registry();
String sql = """
    SELECT merchant, window_start, window_end, SUM(amount) AS spend
    FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
    WHERE currency = ?
    GROUP BY merchant, window_start, window_end""";

registry.classify(sql).forEach(p -> System.out.println(p.describe()));

RegisteredQuery eur = registry.register(
        "eur_merchant_hourly", sql, List.of(0, 2), Principal.ANONYMOUS, BoundParameters.of("EUR"));
eur.avoidableForks().forEach(p -> System.out.println("avoidable: " + p.describe()));
```

```text
?1 on 'currency': REGISTRATION -- ...
```

`RegisteredQuery.parameterPlacements()` says what was decided for each parameter, and
`avoidableForks()` names the ones that did not need to fork anything — what an operator reads as
"you are running N copies of something that could be one".

!!! warning "The CLI and the SDKs' `register` do not take values"
    `pravaha register` has `--keys`, `--sink` and `--retain`, and the SDKs' `register(name, sql,
    keys, sink, retention)` the same -- neither binds `?`. Registering SQL with an unbound `?` over
    Flight is refused with PRV-2060. For a parameter the view can carry, register without it and
    filter at read or subscribe time, which is cheaper anyway.

<!-- sql: refused PRV-2060 -->
```sql
SELECT txn_id, amount FROM txn WHERE currency = ?
```

### Handles carry statements, not sessions

A prepared-statement handle contains the statement and, once bound, its values. The server keeps
nothing between preparing and fetching: no session table, nothing to expire, no requirement that the
second call reach the same node as the first. Plans are cached, bounded, and keyed on the catalogue's
generation, so a plan built when a view had three columns is not reused after it gains a fourth.
**A handle is a plan, never a permission:** authorization runs on every execution.

## Where next

- [What is refused, and why](/help/topics/sql-refusals) — every refusal, with its rewrite
- [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query) — registering one of these
- [Streams](/help/topics/streams) — declaring a schema
- [Views, keys and point reads](/help/topics/views-and-keys#point-reads) — the read side in depth
- [Clients and SDKs](/help/topics/clients#snippets) — binding a parameter from every client
- [Sharing by fingerprint](/help/topics/sharing) — why a bound value makes a new computation
- [Streams, queries and SQL](/help/continuous-queries#11-projection-select) — the long form
