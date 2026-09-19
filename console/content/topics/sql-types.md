---
title: Types, NULLs and expressions
slug: sql-types
category: sql
order: 90
icon: braces
summary: "The types a stream declares and a query computes with, three-valued logic and what NULL does in each clause, CASE, casts, the string functions, why DECIMAL is refused, and the 64-column ceiling."
audience: Analysts and developers
keywords: [types, bigint, integer, double, varchar, boolean, timestamp, date, time, bytes, varbinary, decimal, null, three-valued logic, unknown, case, cast, substring, trim, upper, lower, concatenation, PRV-3030, 64 columns]
guide: continuous-queries#16-types
related: [sql-reference, sql-refusals, streams, pgwire, aggregation]
---

A stream's schema is declared, not inferred — `name:TYPE,name:TYPE`, with `?` for a nullable column
-- in `pravaha.streams.<name>.schema`, in `POST /api/v1/streams`, and in a source's `schema` option.
Sniffing types from the first rows guesses wrong on exactly the columns that matter: an identifier of
all digits becomes an integer, and the first row containing a letter fails at 3 a.m.

## The types

| Schema type | SQL type | Arithmetic | Aggregates | Notes |
|---|---|---|---|---|
| `BOOLEAN` | `BOOLEAN` | — | `COUNT` | A bare column is a valid predicate: `WHERE flagged` |
| `INT8`, `INT16`, `INT32`, `INT64` | `TINYINT` ... `BIGINT` | yes | all | The type for money: integer minor units |
| `FLOAT32`, `FLOAT64` | `REAL`, `DOUBLE` | yes | `COUNT` only | `SUM`/`AVG`/`MIN`/`MAX` refused, PRV-2020 |
| `STRING` | `VARCHAR` | — | `COUNT` | UTF-8; `=` and `<>` only |
| `BYTES` | `VARBINARY` | — | — | Carried over Flight; refused by the PostgreSQL gateway |
| `DATE`, `TIME`, `TIMESTAMP` | same | intervals | `COUNT` | `TIMESTAMP` carries event time, in nanoseconds |
| `DECIMAL(p,s)` | `DECIMAL` | **refused** | — | Declarable only programmatically |

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

## NULL and three-valued logic

A comparison with NULL is neither true nor false — it is **UNKNOWN**. Each clause then does
something specific with UNKNOWN:

| Where | What NULL / UNKNOWN does |
|---|---|
| `WHERE`, `HAVING`, a join's `ON` | The row is kept only if the predicate is TRUE; UNKNOWN drops it |
| `GROUP BY` | NULL is a group: every row with a NULL key gathers under one NULL key |
| `COUNT(col)`, `COUNT(DISTINCT col)` | NULLs are not counted; `COUNT(*)` counts rows |
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

### A projected boolean must be TRUE or FALSE

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

## `CASE`

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

Every branch must produce the same type — with one exception that surprises people. A number in a
text `CASE` is accepted, because the validator coerces `0` to the string `'0'` before the engine sees
it. The column is **text**:

```sql
SELECT txn_id, CASE WHEN amount > 100 THEN 'big' ELSE 0 END AS band FROM txn
```

A guarded division is safe, because the untaken branch is never evaluated:

```sql
SELECT order_id, CASE WHEN amount = 0 THEN 0 ELSE 1000000 / amount END AS inverse_millionths
FROM orders
```

## Numbers

| Expression | Runs | Notes |
|---|---|---|
| `+ - * /` | yes | Integer or floating; integer division truncates |
| `%`, `MOD(a, b)` | yes | Integers and floats; the remainder takes the dividend's sign, so `-50 % 3` is `-2` |
| `ABS`, `FLOOR`, `CEIL`, `ROUND` | yes | One argument each |
| `ROUND(x, 2)` | no | PRV-2021 — scale, round, and name the scale |
| `SQRT`, `POWER`, `LN`, ... | no | PRV-2021 |
| `CAST` between numeric types | yes | |
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

## Strings

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

## Casts

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

## DECIMAL

`DECIMAL` is refused in arithmetic rather than evaluated as a floating-point number, because the
rounding decision belongs to whoever owns the ledger and not to a serialiser:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, CAST(amount AS DECIMAL(12, 2)) / 100 AS amount_major FROM txn
```

And **`DECIMAL(p,s)` cannot be declared through a schema string at all**, even though the refusal for
an unknown type names it: `amt:DECIMAL(10,2)` in `--schema`, `pravaha.streams.*.schema` or
`POST /api/v1/streams` is cut at its own comma and fails as `unknown type 'DECIMAL(10'`. A decimal
column can be declared programmatically through `Types.decimal(p, s)`; it is then carried through
scans, filters and projections correctly — what is not built is arithmetic over it.

**The rewrite everyone ends up with:** integer minor units. `amount` here is cents; a JDBC source can
do the conversion where the decimal already lives
(`CAST(o.amount * 100 AS BIGINT) AS amount_cents` in its `query`).

## Timestamps

`TIMESTAMP` is the event-time type — nanosecond precision end to end. Arithmetic with a day-time
interval works; truncating to a date by cast does not (use a one-day window instead):

```sql
SELECT window_start, window_end, COUNT(*) AS txn_count
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' DAY))
GROUP BY window_start, window_end
```

## BYTES and TIME on the wire

Both serialise over Arrow Flight — the SDKs and the CLI receive them. The **PostgreSQL gateway
refuses `BYTES` and `TIME` by name**, and does so before sending a `RowDescription`, so a client gets
a clean error instead of a truncated result it might treat as complete. That is a gap in that
gateway's type mapping, not in the engine. Read such a view over Flight, or project the column away
for psql.

## Sixty-four columns — PRV-3030

A row cannot have more than 64 output columns. Every row is built by a writer that tracks which fields
have been written in one 64-bit word — one bit per field — and cannot represent a 65th. It binds the
**output** width whatever made it wide: `SELECT *` over a wide stream, a long select list, or a join
whose combined output passes 64.

It is architectural, not a setting. Note when it bites: validation accepts a 1,000-column projection,
because nothing writes a row during validation; the ceiling is met when rows start moving, with
PRV-3030. If a query is that wide, split it into several narrower ones keyed the same way.

## Name your columns

An output column takes its alias, else the column's own name; a qualified `t.amount` is `amount`. An
unaliased expression has no name to take and comes back as `EXPR$n`:

```sql
SELECT txn_id, amount * 2 AS doubled FROM txn
```

## Where next

- [The SQL reference](/help/topics/sql-reference) — every construct, with examples.
- [What is refused, and why](/help/topics/sql-refusals) — every refusal, with its rewrite.
- [Streams](/help/topics/streams) — declaring a schema.
