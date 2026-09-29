---
title: What is refused, and why
slug: sql-refusals
category: sql
order: 20
icon: slash-circle
summary: "Every SQL construct the engine refuses, with its code, the reason, the refused query and — right beside it — the rewrite that plans. Refusals happen at plan time, never at 3 a.m."
badge: REFUSALS
audience: Analysts and developers
keywords: [PRV-2050, PRV-2020, PRV-2021, PRV-2002, PRV-2001, unbounded state, order by, limit, union, distinct, subquery, outer join, float sum, decimal, like escape]
guide: continuous-queries#17-what-to-do-when-something-here-is-refused
related: [sql-reference, errors-sql, aggregation, joins]
---

**A query Pravaha cannot run is refused when it is planned, with a code and an explanation. It is
never accepted and then approximated.** That is a deliberate trade: a refusal costs a developer five
minutes; a query that runs and returns a plausible wrong number costs whatever was decided on the
strength of it.

This page walks every refusal family. Each one shows the refused query — the build asks the engine
to plan it and checks it is refused with exactly that code — and then the rewrite that says what you
meant and plans. If you have a code in hand, its own page is one click away: PRV-2050, PRV-2020,
PRV-2021.

## How to read a refusal

A refusal is one line: the code, then a sentence that names the construct and, where there is one,
what to write instead. In the workbench it is underlined where it happens, and the diagnostic links
to the code's page. From the CLI:

```bash
pravaha query --sql "SELECT user_id, COUNT(*) AS n FROM txn GROUP BY user_id"
```

```text
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of
distinct keys and never shrinks. ...
```

| Code | Family | What it means |
|---|---|---|
| PRV-2001 | Syntax | The parser stopped; Calcite's line and column are kept |
| PRV-2002 | Validation | An unknown stream or column, a wrong case, a type mismatch |
| PRV-2011 | Too large | A predicate (a long `AND`/`OR` chain) too big to convert |
| PRV-2020 | Operator | A relational operator the engine cannot execute |
| PRV-2021 | Expression | An expression or function the engine cannot compile |
| PRV-2050 | Unbounded state | The query's state would grow without limit |
| PRV-2060 to PRV-2063 | Parameters | See [parameters](/help/topics/sql-reference#parameters) |
| PRV-2070 to PRV-2074 | Statements | See [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query) |

The three rules underneath almost every refusal:

1. **State must be bounded.** A stream has no end; any operator whose memory grows with the number
   of distinct keys *ever seen* will fail months later, in production. So it is refused now.
2. **An answer must not depend on arrival order or a guess.** Floating-point sums, text collation,
   decimal rounding and "a month" all hide a choice somebody should make deliberately.
3. **A continuous query answers a question asked in advance.** A total order, a set difference or an
   ad-hoc cross product is a different kind of engine's job (ADR-030 puts it out of scope).

## Unbounded state — PRV-2050

### An unwindowed `GROUP BY` over a stream

The query that surprises people most. Over an endless stream this aggregate keeps one entry per
distinct `user_id` for ever; its memory is a function of how many users ever appear, not of anything
configured. It does not fail on the day it ships — it fails months later.

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) AS txn_count
FROM txn
GROUP BY user_id
```

**Rewrite: add a window.** A window closes, so its state is released:

```sql
SELECT user_id, window_start, window_end, COUNT(*) AS txn_count
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

**Or, if you wanted a running total per user for a dashboard,** maintain the windowed view and do
the unwindowed `GROUP BY` at read time, where the scan ends:

<!-- sql: read -->
```sql
SELECT user_id, SUM(spend) AS spend_to_date
FROM hourly_spend
GROUP BY user_id
```

### A windowed stream grouped without its window

Grouping a windowed stream without putting both `window_start` and `window_end` in the `GROUP BY`
is the unbounded case wearing a window's clothes — the aggregate would span every window at once:

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) AS txn_count
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id
```

<!-- sql: refused PRV-2050 -->
```sql
SELECT window_start, COUNT(*) AS n
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY window_start
```

**Rewrite:** group by both.

```sql
SELECT window_start, window_end, COUNT(*) AS n
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY window_start, window_end
```

!!! warning "Pitfall: renaming `window_end` in the SELECT list"
    `SELECT user_id, window_end AS hour_end, ... GROUP BY user_id, window_start, window_end` is
    refused with PRV-2050, although it groups by both columns: the window is found by its columns'
    *names*, and the rename hides one. The message lists the grouped columns it found and says so.
    Select the column under its own name — `window_end` — and rename it where you read it.

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, window_end AS hour_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

```sql
SELECT user_id, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

### `SELECT DISTINCT` and an unwindowed `COUNT(DISTINCT)`

`DISTINCT` is a `GROUP BY` over every selected column, so it is the same unbounded key space:

<!-- sql: refused PRV-2050 -->
```sql
SELECT DISTINCT user_id FROM txn
```

<!-- sql: refused PRV-2050 -->
```sql
SELECT COUNT(DISTINCT user_id) AS users FROM txn
```

**Rewrite:** count distinct users per window.

```sql
SELECT window_start, window_end, COUNT(DISTINCT user_id) AS active_users
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY window_start, window_end
```

Over a view, `DISTINCT` is fine — the scan ends:

<!-- sql: read -->
```sql
SELECT DISTINCT tier FROM user_volume
```

## Operators the engine does not execute — PRV-2020

### Sorting and paging

`ORDER BY` over a stream asks for a total order over rows that have not all arrived. `LIMIT` has
nothing stable to cut. Both are refused over streams **and** over views (there is no sort operator):

<!-- sql: refused PRV-2020 -->
```sql
SELECT txn_id, amount FROM txn ORDER BY amount
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT txn_id FROM txn LIMIT 10
```

<!-- sql: read-refused PRV-2020 -->
```sql
SELECT user_id, spend FROM hourly_spend ORDER BY spend
```

<!-- sql: read-refused PRV-2020 -->
```sql
SELECT user_id, spend FROM hourly_spend LIMIT 5
```

**Rewrite:** filter to what matters, and sort in the client. A "top N" is usually a threshold in
disguise:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend WHERE spend > 5000
```

### Set operations

<!-- sql: refused PRV-2020 -->
```sql
SELECT user_id FROM txn UNION ALL SELECT customer_id FROM orders
```

**Rewrite:** register two queries, or one per source stream, and read both. If what you want is
"users who paid or ordered", that is two views and a client-side merge.

```sql
SELECT user_id, txn_id, event_time FROM txn
```

```sql
SELECT customer_id, order_id, event_time FROM orders
```

### `VALUES` and writes

<!-- sql: refused PRV-2020 -->
```sql
SELECT n FROM (VALUES (1), (2)) AS v (n)
```

<!-- sql: refused PRV-2020 -->
```sql
DELETE FROM txn WHERE amount > 1
```

<!-- sql: refused PRV-2020 -->
```sql
UPDATE txn SET amount = 1 WHERE amount > 1
```

Pravaha answers questions; sinks write results. **Rewrite:** name a sink at registration
(see [sinks](/help/topics/sinks-overview)):

```sql
CREATE CONTINUOUS QUERY large_payments
    KEYED BY (txn_id)
    WRITING TO audit_trail
AS
SELECT txn_id, user_id, amount FROM txn WHERE amount > 10000;
```

### Grouping extensions and session windows

<!-- sql: refused PRV-2020 -->
```sql
SELECT merchant, currency, COUNT(*) AS n FROM txn GROUP BY ROLLUP (merchant, currency)
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT window_start, window_end, COUNT(*) AS n
FROM TABLE(SESSION(TABLE txn, DESCRIPTOR(event_time), DESCRIPTOR(user_id), INTERVAL '5' MINUTE))
GROUP BY window_start, window_end
```

**Rewrite:** one windowed query per grouping level; for sessions, a short tumbling or hopping window
is the nearest supported shape (session windows exist in the runtime but have no SQL surface yet).

```sql
SELECT merchant, currency, window_start, window_end, COUNT(*) AS n
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, currency, window_start, window_end
```

```sql
SELECT merchant, window_start, window_end, COUNT(*) AS n
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, window_start, window_end
```

### Floating-point aggregates

Every aggregate accumulator reads and writes a 64-bit integer, whatever the column's type, so a
float column is refused rather than aggregated into nonsense — before the refusal, a float aggregate
produced no rows under a success status. This covers `SUM`, `AVG`, `MIN` and `MAX`; only `COUNT` of a
float column plans, because it never reads the value:

<!-- sql: refused PRV-2020 -->
```sql
SELECT site, window_start, window_end, SUM(temperature) AS total
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT site, window_start, window_end, MAX(temperature) AS hottest
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

**Rewrite:** aggregate an integer. Cast (which truncates — say so on the dashboard), or better,
carry the value in integer units from the source (hundredths of a degree, cents):

```sql
SELECT site, window_start, window_end,
       SUM(CAST(temperature AS BIGINT)) AS total_degrees,
       COUNT(temperature) AS samples
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

### Joins that cannot be made incremental

Each of these would hold every unmatched row indefinitely, or has no key to partition on. The full
matrix, with rewrites, is on [joins](/help/topics/joins).

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id, s.carrier FROM orders o LEFT JOIN shipments s ON s.order_id = o.order_id
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o RIGHT JOIN shipments s ON s.order_id = o.order_id
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o FULL JOIN shipments s ON s.order_id = o.order_id
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o CROSS JOIN shipments s
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o JOIN shipments s ON s.order_id > o.order_id
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT o.order_id FROM orders o
JOIN shipments s ON s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' HOUR
```

**Rewrite:** an equality plus a time bound — and for an outer join, the bound is what lets the
engine declare a row unmatched:

```sql
SELECT o.order_id, s.carrier
FROM orders o
LEFT JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '2' DAY
```

## Expressions the engine does not compile — PRV-2021

### Subqueries and window functions

A *correlated* subquery — one naming a column of the outer row, as this one does — is PRV-2020, and
the refusal names the one correlated form that runs, `JOIN dim FOR SYSTEM_TIME AS OF <time>`:

<!-- sql: refused PRV-2020 -->
```sql
SELECT txn_id FROM txn
WHERE EXISTS (SELECT 1 FROM orders WHERE orders.customer_id = txn.user_id)
```

An uncorrelated one is PRV-2021:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, (SELECT COUNT(*) FROM orders) AS orders FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_time) AS rn FROM txn
```

That `ROW_NUMBER` is refused because nothing bounds it: every row's number would change whenever an
earlier row arrived. **Filtered to a top N it runs** — `SELECT * FROM (SELECT ..., ROW_NUMBER() OVER
(PARTITION BY user_id ORDER BY amount DESC) AS rn FROM txn) WHERE rn <= 3` is maintained as a top-N
that emits a `-1` and a `+1` wherever the first three change, and a retraction promotes the row below.

**Rewrite:** a subquery that tests for a matching row is a join with a time bound; a lookup against
reference data is a temporal join:

```sql
SELECT t.txn_id, o.order_id
FROM txn t
JOIN orders o
  ON o.customer_id = t.user_id
 AND o.event_time BETWEEN t.event_time - INTERVAL '1' HOUR AND t.event_time
```

### Functions outside the supported set

The numeric set is `ABS`, `FLOOR`, `CEIL`, `ROUND` (one argument); the text set is `UPPER`, `LOWER`,
`TRIM`, `SUBSTRING` and `||`. Anything else is refused by name:

<!-- sql: refused PRV-2021 -->
```sql
SELECT SQRT(amount) AS root FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT ROUND(temperature, 2) AS t FROM readings
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT CHAR_LENGTH(merchant) AS len FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT REPLACE(merchant, 'a', 'b') AS m FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT POSITION('a' IN merchant) AS p FROM txn
```

**Rewrite for rounding to places:** scale, round, and keep the scale in the column's name:

```sql
SELECT device_id, ROUND(temperature * 100) AS temperature_centi FROM readings
```

### Casts that leave the numeric family

<!-- sql: refused PRV-2021 -->
```sql
SELECT CAST(amount AS VARCHAR) AS amount_text FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT CAST(event_time AS DATE) AS txn_day FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT CAST(amount AS DECIMAL(10, 2)) AS amount_decimal FROM txn
```

`DECIMAL` division and remainder are refused rather than rounded, because the rounding decision
belongs to whoever owns the ledger (addition, subtraction and multiplication are exact). **Rewrite:** integer minor units, which the samples here already
use (`amount` is cents; `price_cents` says so in its name):

```sql
SELECT txn_id, amount / 100 AS whole_units, amount % 100 AS cents_part FROM txn
```

### Text comparisons that need a collation

`=` and `<>` on text work. Ordering text needs a collation, and assuming one gives wrong answers that
look right. Comparing text to a number is refused too:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id FROM txn WHERE merchant > user_id
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id FROM txn WHERE amount > merchant
```

### `LIKE` with `ESCAPE`, or a pattern that is not a literal

A pattern is compiled once, when the query is registered:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id FROM txn WHERE merchant LIKE 'a!%' ESCAPE '!'
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id FROM txn WHERE merchant LIKE status
```

```sql
SELECT txn_id FROM txn WHERE merchant LIKE 'ac%' AND merchant NOT LIKE '%test%'
```

### A projected boolean that can be UNKNOWN

`status` is nullable, so `status = 'SETTLED'` is TRUE, FALSE or UNKNOWN. A boolean column holds two
values; writing UNKNOWN into one would report it as `false` under a success exit code:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, status = 'SETTLED' AS settled FROM txn
```

**Rewrite:** say which answer UNKNOWN should be, as the message suggests. `IS TRUE` reads it as
`FALSE`; `IS NOT FALSE` reads it as `TRUE`. Neither can be UNKNOWN:

```sql
SELECT txn_id,
       (status = 'SETTLED') IS TRUE AS settled,
       (status = 'SETTLED') IS NOT FALSE AS settled_or_unknown
FROM txn
```

`CASE WHEN status = 'SETTLED' THEN TRUE ELSE FALSE END` is the long way to write `IS TRUE`, and plans
too.

!!! note "`IS NOT NULL AND ... =` is refused"
    `status IS NOT NULL AND status = 'SETTLED'` is never UNKNOWN in fact, but the planner still types
    the `AND` as possibly UNKNOWN, and it is refused with PRV-2021 all the same. Use `IS TRUE`; in a
    `WHERE`, either form works.

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, status IS NOT NULL AND status = 'SETTLED' AS settled_known FROM txn
```

### `IS NULL` over an expression — not a refusal any more

This was refused: the compiler required a bare column reference and turned everything else away,
including the ordinary idiom of null-checking a computed `CASE`. It plans now (TY-5), over
arithmetic and over a `CASE` alike:

```sql
SELECT txn_id FROM txn WHERE (amount * 2) IS NULL
```

```sql
SELECT txn_id FROM txn WHERE (CASE WHEN amount > 100 THEN status ELSE 'none' END) IS NULL
```

```sql
SELECT txn_id FROM txn WHERE amount IS NULL
```

(`amount` is `NOT NULL` here, so the first and last filters never match — they plan, and are honest
about it.)

## Names that do not resolve — PRV-2002 and PRV-2001

Identifiers are **case-sensitive**. A name in the wrong case is refused, and the message suggests the
right one:

<!-- sql: refused PRV-2002 -->
```sql
SELECT TXN_ID FROM txn
```

<!-- sql: refused PRV-2002 -->
```sql
SELECT nosuch FROM txn
```

<!-- sql: refused PRV-2002 -->
```sql
SELECT txn_id FROM nosuchstream
```

The unknown-stream message does **not** list the streams that do exist: validation runs before
authorization, and a list would tell a caller who may read nothing what is there. Use the catalog,
which is filtered by what you may see.

A window must name the stream's **declared event time**. A grid keyed to another column would be
closed by a clock that knows nothing about it:

<!-- sql: refused PRV-2002 -->
```sql
SELECT window_start, window_end, COUNT(*) AS n
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(txn_id), INTERVAL '1' HOUR))
GROUP BY window_start, window_end
```

A reserved word as an alias is a syntax error — `hour`, `year`, `value`, `large` and friends:

<!-- sql: refused PRV-2001 -->
```sql
SELECT user_id, amount AS value FROM txn
```

```sql
SELECT user_id, amount AS amount_value FROM txn
```

## Reads that are refused

A read is one question over **one view**. Joining two views in a single request is analytics, and is
refused with PRV-4025:

<!-- sql: read-refused PRV-4025 -->
```sql
SELECT b.txn_id, u.tier FROM big_txn b JOIN user_volume u ON u.user_id = b.user_id
```

**Rewrite:** register the join as a continuous query — then it is maintained, and reading it is cheap.

```sql
CREATE CONTINUOUS QUERY big_txn_with_tier
    KEYED BY (txn_id)
AS
SELECT t.txn_id, t.user_id, t.amount, p.tier
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
WHERE t.amount > 1000;
```

<!-- sql: read -->
```sql
SELECT txn_id, amount, tier FROM big_txn_with_tier WHERE tier = 'gold'
```

## One refusal without a code

A **self join** — one stream on both sides — plans, and is refused when the pipeline is built,
because rows enter a join by stream name and a name cannot say which side a row is for. It is the one
refusal that arrives without a `PRV-` code; the message says `stream 'txn' appears on both sides of
this plan; self-joins are not supported yet`. It is not shown as an executable example here because
validation accepts it; registration refuses it.

```text
SELECT a.txn_id FROM txn a JOIN txn b ON a.user_id = b.user_id      -- refused at registration
```

## Common surprises

| Symptom | What it is |
|---|---|
| `GROUP BY` works in a read, is refused in a query | The input: a view scan ends, a stream does not (PRV-2050) |
| `COUNT(*)` comes back as `EXPR$1` | An unaliased aggregate; add `AS name` |
| `SUM(price)` refused | A `DOUBLE` column; aggregate integer units |
| A query with `AS hour` does not parse | A reserved word; choose another alias |
| The workbench says a stream is unknown although it exists | Case: identifiers are case-sensitive |
| `ORDER BY` refused on a view | There is no sort operator; sort in the client |

## Where next

- [The SQL reference](/help/topics/sql-reference) — the positive half.
- [SQL error codes](/help/topics/errors-sql) — every PRV-2xxx code.
- [Troubleshooting: the five you will actually meet](/help/troubleshooting#the-five-you-will-actually-meet).
