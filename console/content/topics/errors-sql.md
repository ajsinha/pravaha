---
title: SQL codes (PRV-2xxx)
slug: errors-sql
category: errors
order: 30
icon: code-square
summary: "PRV-2001 to PRV-2073: every way the planner refuses a query — syntax, names, operators and functions it will not run, unbounded state, parameters, sinks, and CREATE CONTINUOUS QUERY."
badge: PRV-2XXX
audience: Analysts, developers
keywords: [syntax, validation, unknown column, unsupported, order by, limit, union, unbounded, group by, parameter, placeholder, keyed by, range, create continuous query, insert into, emit mode, retraction, append-only, sink]
guide: continuous-queries#19-error-codes
related: [sql-refusals, sql-reference, create-continuous-query, sql-parameters, errors-overview]
---

The 2xxx range is the planner speaking. Every code here is raised **before anything runs** — by the
workbench as you type, by `pravaha validate`, by `POST /api/v1/queries/validate`, and at
registration — so meeting one costs a rewrite, not an incident. That is the point: a query Pravaha
cannot run is refused when it is planned, with a code and an explanation, and never accepted and then
approximated.

Every refused example on this page is **planned by the real engine in the build** and checked to be
refused with exactly the code shown, so the page cannot drift from the engine. The examples use the
help's example streams: `txn`, `orders`, `shipments`, `trades`, `quotes` and `readings` (each with an
`event_time`), and the lookup tables `user_profile`, `customers` and `instruments`.

| Code | Name | In one line |
|---|---|---|
| PRV-2001 | SQL_PARSE_FAILED | Not SQL the parser can read |
| PRV-2002 | SQL_VALIDATION_FAILED | Parsed, but a name or a type does not check |
| PRV-2003 | SQL_UNKNOWN_STREAM | A stream the node has not declared |
| PRV-2010 | SQL_PLANNING_FAILED | The planner's catch-all for a conversion that failed |
| PRV-2011 | SQL_PREDICATE_TOO_LARGE | A predicate too large for the planner to convert |
| PRV-2020 | SQL_UNSUPPORTED_OPERATOR | A relational operator the engine will not run |
| PRV-2021 | SQL_UNSUPPORTED_EXPRESSION | An expression or function the engine will not compile |
| PRV-2041 | SQL_EMIT_MODE_MISMATCH | The query revises its answer; the sink can only append |
| PRV-2042 | SQL_SOURCE_REPEATS_ROWS | The answer depends on how often a row arrived, and the source repeats rows |
| PRV-2050 | SQL_UNBOUNDED_STATE | The query's state would grow for ever |
| PRV-2060 | SQL_PARAMETER_NOT_BOUND | Fewer values than placeholders |
| PRV-2061 | SQL_PARAMETER_ARITY | The number of values does not match the placeholders |
| PRV-2062 | SQL_PARAMETER_TYPE | A value of the wrong type for its placeholder |
| PRV-2063 | SQL_PARAMETER_NOT_A_VALUE | A `?` where a value cannot go |
| PRV-2070 | SQL_STATEMENT_MALFORMED | A `CREATE`/`DROP`/`PAUSE`/`RESUME`/`SHOW` statement without its shape |
| PRV-2071 | SQL_KEY_COLUMN_UNKNOWN | `KEYED BY` names a column the query does not produce |
| PRV-2072 | SQL_CLAUSE_NOT_BUILT | A clause of the design's grammar that is not built |

## Reading the query

### PRV-2001 — parse failed

The text is not SQL the parser can read. The message keeps the parser's **line and column**, which is
most of what makes a syntax error fixable, and the workbench underlines the exact range. Also used
for a statement that is only a comment, and for two statements sent as one.

<!-- sql: refused PRV-2001 -->
```sql
SELEC txn_id FROM txn
```

A reserved word used as an alias is a parse error too, and a common one: `hour`, `year`, `value`,
`timestamp` cannot name a column without quoting. Over `TABLE(TUMBLE(...))`, keep `window_end`
under its own name — renaming a window column (`window_end AS hour_end`) is refused with PRV-2050 —
and rename it downstream; in the group-window form, `TUMBLE_END(...) AS hour_end` is fine.

<!-- sql: refused PRV-2001 -->
```sql
SELECT user_id, window_end AS hour FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
```

### PRV-2002 — validation failed

It parsed; a name or a type does not check. An unknown column, an unknown view or stream in `FROM`, a
type mismatch (`amount = 'ten'`), or an identifier in the wrong case — identifiers keep their case, so
`USER_ID` is not `user_id`.

<!-- sql: refused PRV-2002 -->
```sql
SELECT txn_id, customer FROM txn
```

<!-- sql: refused PRV-2002 -->
```sql
SELECT USER_ID FROM txn
```

The message deliberately does **not** list the streams that do exist: validation runs before any
authorization can, and a list would hand every caller the node's inventory (SX-5). It says how many
are declared and points at `GET /api/v1/streams`, which is filtered by what you may read.

**A stream declaration the node cannot accept carries this code too.** An event-time column that
is not a `TIMESTAMP`, a negative out-of-orderness or allowed lateness, or either of those given
with no event time for them to be about. Each names the stream and both spellings of the setting —
`pravaha.streams.<name>.out-of-orderness`, or `outOfOrderness` on `POST /api/v1/streams`. See
[Event time and watermarks](/help/topics/event-time-watermarks).

**And so does a window over a stream that declares no event time at all.** The query is
well-formed and the column it names is real; what is missing is the declaration that makes a
watermark advance, and without one no window the query opens could ever close. It is refused when
you register it rather than left running empty:

```text
PRV-2002  TUMBLE is given DESCRIPTOR(event_time), but 'txn' declares no event-time column -- so no
          watermark advances over it and no window this query opens can ever close. It would
          register, report RUNNING, ingest every row and emit nothing, for ever.
            Declare the column: pravaha.streams.txn.event-time: event_time, or 'eventTime' on
            POST /api/v1/streams. The column must be a TIMESTAMP.
          Refused at registration rather than discovered from an empty view later.
```

The fix is the declaration; there is nothing to turn off, because there is no configuration in
which the refused query answers. A query over a **bounded** read is not refused: those windows are
fired by the end of the scan, not by a watermark.

### PRV-2003 — unknown stream

A stream named by an API call — `GET /api/v1/streams/{name}`, a registration against a stream — is not
A refusal the validator makes first still ends with what this engine *does* evaluate. An unknown
function (`LTRIM`, `RTRIM`, `CONCAT`), a function given the wrong number of arguments (`ABS(x, 1)`),
and a cast between types that do not convert (`CAST(<boolean> AS INTEGER)`) are all caught by SQL's
own validator before Pravaha's compiler sees them, so the code is PRV-2002 and the first sentence is
the validator's. The sentence after it is this engine's, and names the supported set (TY-24).

declared on this node. In SQL an unknown name in `FROM` is reported by the validator as PRV-2002
first; this code is what the catalogue itself says. Declare the stream under
`pravaha.streams.<name>` or `POST /api/v1/streams`, and check you are pointed at the node you think.

### PRV-2010 — planning failed

The planner's catch-all: converting the validated query into a plan failed for a reason with no more
specific code. It includes a `StackOverflowError` during planning — an extremely deep expression —
caught and refused with a message naming the cause. If you meet it with ordinary SQL, the message is
the diagnosis; it is also worth reporting, because a common shape deserves its own code.

### PRV-2011 — predicate too large

An `AND`/`OR` chain of a few thousand terms — the shape a client library produces by rewriting a wide
`IN (...)` list, or a generated query — is too large for the planner to convert. The message is a
**summary** (the operator and the term count), not the predicate itself: it used to echo about 125 KB
of SQL back for a 3,000-term chain.

```text
PRV-2011  the query planner failed while converting a predicate, and the predicate itself is omitted
from this message because it is a 3000-term AND chain (124807 characters) -- too large to be useful
here. ...
```

**Do:** write it as `IN (...)`, use a range comparison, split the query, or join against a lookup
table of values instead of listing them.

## What the engine will not run

### PRV-2020 — unsupported operator

A relational operator Pravaha does not execute: `ORDER BY`, `LIMIT`/`OFFSET`, `UNION`/`INTERSECT`/
`EXCEPT`, `VALUES`, `RIGHT`/`FULL OUTER`/`CROSS JOIN`, a `LEFT JOIN` between streams with no time
bound, a non-equi join, `SESSION` windows, `ROLLUP`, `INSERT`/`UPDATE`/`DELETE` — and `SUM`, `AVG`,
`MIN` or `MAX` over a floating-point column, because every aggregate accumulator reads and writes a
64-bit integer (only `COUNT` of a float plans). A `WHERE` on a looked-up column over an inner lookup
join is refused with this code too; the message says a filter on a lookup column is refused and that
the `LEFT JOIN ... WHERE` form plans — write the join `LEFT` ([lookups](/help/topics/lookups)).

<!-- sql: refused PRV-2020 -->
```sql
SELECT txn_id, amount FROM txn ORDER BY amount
```

<!-- sql: refused PRV-2020 -->
```sql
SELECT site, window_start, window_end, AVG(temperature) AS mean_temp
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

The second one has a fix the refusal suggests — aggregate an integer:

```sql
SELECT site, window_start, window_end, AVG(CAST(temperature AS BIGINT)) AS mean_temp_whole
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY site, window_start, window_end
```

`ORDER BY` over a stream would mean a total order over rows that have not all arrived; there is no
answer to give. Most of these are ADR-030's deliberate scope rather than an unfinished corner.

**Every spelling of `ORDER BY` is refused, including the one that used to run.** A sort inside a
derived table with no `FETCH` cannot change the answer, so the optimiser deletes it — and it was
deleted before the refusal could see it, which meant this planned and ran under a success code while
the same clause one line up was refused (TY-20):

<!-- sql: refused PRV-2020 -->
```sql
SELECT * FROM (SELECT txn_id FROM txn ORDER BY txn_id) x
```

**`SUM` or `AVG` over a text column** is this code too, and names the column. SQL would otherwise
cast the operand to `DECIMAL(38,19)` on your behalf, and the refusal you met was about decimal
arithmetic in a ledger — a true sentence about a cast nobody wrote, saying nothing about summing
text (TY-16):

<!-- sql: refused PRV-2020 -->
```sql
SELECT SUM(user_id) AS total FROM txn
```

### PRV-2021 — unsupported expression

An expression or function the engine will not compile: numeric functions beyond `ABS`, `FLOOR`,
`CEIL` and one-argument `ROUND`; string functions beyond `UPPER`, `LOWER`, `TRIM`, `SUBSTRING` and
`||`; `LIKE ... ESCAPE`; ordering comparisons between strings; comparing text to a number; casts into
or out of text; subqueries (`IN (SELECT ...)`, `EXISTS`, scalar); window functions such as
`ROW_NUMBER() OVER (...)`; and a projected comparison over a nullable column whose answer could be
UNKNOWN.

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, SQRT(amount) AS root FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, CAST(amount AS VARCHAR) AS amount_text FROM txn
```

!!! note "The function named may not be the one you typed"
    The name in a PRV-2021 message is the function the *planner* saw. The SQL front end rewrites some
    calls before the engine reads the query — `SQRT(x)` becomes `POWER(x, 0.5)` — so a refusal can
    name a function your SQL does not contain.

**A number cannot become text, however it is written.** There is no number-to-text conversion
anywhere in this engine, and until TY-23 that rule depended on spelling: `user_id || amount` was
refused and `user_id || 5` succeeded, because SQL's own coercion turns the literal into text and the
optimiser folds the cast away before anything here can look at it. Both refuse now:

<!-- sql: refused PRV-2021 -->
```sql
SELECT user_id || 5 AS tagged FROM txn
```

<!-- sql: refused PRV-2021 -->
```sql
SELECT CAST(5 AS VARCHAR) AS five FROM txn
```

`CAST(NULL AS VARCHAR)` is still accepted — it converts nothing, and it is what the bare-`NULL`
refusal tells you to write.

**A `CASE` whose branches produce different types** is refused rather than widened, because a row
whose type depends on its own values has no schema. `CASE WHEN c THEN 1 ELSE 1.5 END` is `DECIMAL`
to SQL and is refused exactly as `amount * 1.5` is; it used to escape as an uncoded Java exception
(TY-4).

**A `BYTES`, `ARRAY`, `MAP` or `ROW` column compared in a predicate** is refused with this code and
the column's name. A `BYTES` column can be selected, null-checked and used as a join key; comparing
one is not built, and the refusal used to name only the implicit cast SQL had inserted (TY-14).

**`IS NULL` over an expression plans** — `(CASE WHEN … END) IS NULL`, `(amount * 2) IS NULL`. Only
the bare-column form compiled until TY-5.

A nullable comparison projected as a boolean is refused because writing UNKNOWN into a boolean column
would report it as `false` — a wrong answer under a success code. The message says to choose what
UNKNOWN means: `(<condition>) IS TRUE` reads it as FALSE, `(<condition>) IS NOT FALSE` as TRUE, and
both are never UNKNOWN, so both plan:

<!-- sql: refused PRV-2021 -->
```sql
SELECT txn_id, status = 'SETTLED' AS settled FROM txn
```

```sql
SELECT txn_id, (status = 'SETTLED') IS TRUE AS settled FROM txn
```

`CASE WHEN status = 'SETTLED' THEN TRUE ELSE FALSE END` means the same as `IS TRUE` and plans too.
Adding `status IS NOT NULL AND` to the comparison does **not**: the whole expression is still typed
as possibly UNKNOWN, and is refused the same way.

### The refusal with no code

A **self-join** — one stream on both sides of a join — plans, and is then refused when the pipeline
is built, because rows enter a join by stream name and the name cannot say which side a row is for.
It is the one refusal that still arrives without a PRV code; the message says plainly what is wrong.

```text
SELECT a.txn_id, b.txn_id AS other_txn
FROM txn AS a
JOIN txn AS b ON a.user_id = b.user_id
```

Because it is refused only when the pipeline is compiled, `pravaha validate` and the workbench's
validation — which plan and build but do not compile — **accept it**, and the refusal arrives at
registration. It is shown as text here for that reason: the build's check of this page's SQL plans
each example the way validation does, and this one passes that step.

## State that would grow for ever

### PRV-2050 — unbounded state

**The most common refusal, and it is the engine working.** A `GROUP BY` over a stream with no window
keeps one accumulator per key *for ever*; `COUNT(DISTINCT ...)` over an unwindowed stream keeps one
entry per distinct value for ever; `SELECT DISTINCT` is the same thing spelled differently. None of
them fails on the day it is deployed. It fails months later, and the query looked innocent in the
review that approved it.

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) AS txns FROM txn GROUP BY user_id
```

<!-- sql: refused PRV-2050 -->
```sql
SELECT DISTINCT merchant FROM txn
```

A windowed `GROUP BY` that leaves the window out of the grouping is the same thing in a window's
clothes, and is refused the same way:

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id
```

**Do:** add a window, and group by `window_start` and `window_end` as well as the key:

```sql
SELECT window_start, window_end, user_id, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end, user_id
```

**Over a view it is allowed**: a read of a maintained view scans a finite set of rows and stops, so
the same shape is answered there.

<!-- sql: read -->
```sql
SELECT tier, COUNT(*) AS users, SUM(total) AS spend FROM user_volume GROUP BY tier
```

## Parameters

`?` placeholders are values — in `WHERE` and `HAVING` — never parts of the query's shape (ADR-032).
The four codes tell apart the four ways binding goes wrong. See
[Parameters](/help/topics/sql-parameters) for the whole position table.

### PRV-2060 — parameter not bound

The statement was executed with fewer values than it has placeholders — including **any continuous
query registered over the wire with a `?` in it**. The register action (`pravaha register`, either
SDK's `register`, `CREATE CONTINUOUS QUERY`) carries a name, SQL, keys, a sink and a retention, and no
values, so a placeholder there always meets this refusal. `pravaha register --param` does not exist:
the CLI refuses `--param` and `--params` on `register` with a usage error rather than dropping them.

<!-- sql: refused PRV-2060 -->
```sql
SELECT txn_id, amount FROM txn WHERE merchant = ?
```

Only an application embedding the engine binds values into a registration, through
`QueryRegistry.register(..., BoundParameters.of("acme"))`; with its value the same query plans:

<!-- sql: parameterised -->
```sql
SELECT txn_id, amount FROM txn WHERE merchant = ?
```

Over the wire, write the value into the SQL, or register once without the filter and select by that
column at read time — `pravaha query ... --params acme`, or `pravaha subscribe --filter merchant=acme`
— which is one computation for every value. See [Parameters](/help/topics/sql-parameters).

### PRV-2061 — parameter arity

The number of values bound does not equal the number of placeholders — two values for one `?`, or one
for two. The Java SDK checks this itself before a prepared statement is sent (no round trip, and it
can name both counts), and raises it under this same code.

### PRV-2062 — parameter type

A value was bound whose type is not the one the planner inferred for that placeholder: a string for
`amount > ?`, where `amount` is a `BIGINT`. Bind the value in the column's type.

```python
client.query("SELECT user_id, spend FROM hourly_spend WHERE spend > ?", ["1000"])   # PRV-2062
client.query("SELECT user_id, spend FROM hourly_spend WHERE spend > ?", [1000])     # plans
```

### PRV-2063 — parameter not a value

A `?` in a position that decides the **shape** of the plan rather than a value: `GROUP BY ?`, a
parameterised window size, a table name. These are not parameters; they are different queries
wearing the same syntax, and a window of one size cannot share state with a window of another at all.
Write the value into the SQL, and register one query per shape.

## Sinks

### PRV-2041 — emit mode mismatch

The query **revises its answer** — an unwindowed aggregate over a view, a window with allowed
lateness, a join that can withdraw a match, or anything that passes on rows from a source that
deletes (postgres-cdc, Delta, a Kafka changelog) — and the sink it names can only **append**, such as a
`filesystem` sink. Refused at registration, before the sink is opened, because the alternative is a
file holding rows that are each correct and a total that is wrong for ever: a retraction has nowhere
to go (design §15.5).

Over sources that only append, a filter, a projection, or a tumbling window without lateness never
revises and goes to any sink; over a source that deletes, the message's *Why* names the stream.
**Do:** point a revising query at a sink that accepts updates (`jdbc-sink`, `aerospike-sink`, `kafka-sink`), or
change the query so it does not revise. The catalog's *Sinks* tab says what each binding accepts. See
[How a query writes to a sink](/help/topics/sinks-overview).

### PRV-2042 — the source repeats rows

The stream is read from a source that **repeats rows** — it delivers a row it has already delivered,
with nothing retracting the earlier copy — and the query's answer depends on how many times a row
arrived. Refused at registration, before a feed or a sink opens (SCAN-1). Every copy arrives at `+1`,
so a `COUNT` over a Cassandra table would grow by the table's size every pass, and an Aerospike
update would be counted twice, under a success status.

Which sources repeat, by configuration:

| Source | Repeats rows |
|---|---|
| `cassandra`, `deletes: ignore` (the default) | yes — every pass emits every row again |
| `aerospike`, `deletes: ignore` (the default) | yes — an update is read again as the new row; a record written during a scan is read by the next scan too |
| `jdbc` | yes, unless `key.column` is set and `watermark.moves.on.update: false` — an update that moves the watermark brings the row back |
| `cassandra` / `aerospike` with `deletes: detect`, `postgres-cdc`, `kafka`, `delta`, `feedfile`, `filesystem` | no |

What is refused over such a stream: **any aggregate**, windowed or not (`MIN`, `MAX` and `DISTINCT`
too — an update is never retracted from them); **a join**, which pairs every copy again; and **a sink
that cannot upsert by key** (`filesystem`, `jdbc-sink` with `mode: append`, `kafka-sink` with
`format: changelog`), which would write every copy as another row.

What is admitted: a filter or projection served as a keyed view, or written to a sink that upserts
by key. A copy overwrites its own key with the values it already has, so a read returns each row
once, as the store holds it. A subscriber sees each copy as another `+1` of a row it already has —
overwrite by key rather than summing weights.

**Do:** set `deletes: detect` on the binding the message names (with `deletes.state.dir`) — each pass
then becomes an exact changelog, and the query registers. For `jdbc`, poll a watermark column only
an insert sets, with `key.column`, and say so with `watermark.moves.on.update: false`; or read the
table through `postgres-cdc`. Or keep the query a keyed view of the rows and aggregate over the view.
See [the Cassandra source](/help/topics/source-cassandra) and
[the Aerospike source](/help/topics/source-aerospike).

## The statements that register and manage queries

`CREATE CONTINUOUS QUERY`, `DROP`/`PAUSE`/`RESUME CONTINUOUS QUERY` and `SHOW CONTINUOUS QUERIES` are
recognised by their leading words before the SQL parser sees them, so a statement that begins as one
and goes wrong is refused with a code of its own — never with a syntax error about a word the parser
has never heard of. (Sent to the read-only PostgreSQL gateway, they are PRV-6211.)

### PRV-2070 — statement malformed

The statement starts as one of these and does not have its shape: no `KEYED BY`, a clause given
twice, a retention that is not a duration, a month as a retention, words after the name, or
parameters bound to a statement that takes none. The message states the shape it expected, what it
found, and the line and column where reading stopped.

<!-- sql: refused PRV-2070 -->
```sql
CREATE CONTINUOUS QUERY big_spenders AS
SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000
```

```text
CREATE CONTINUOUS QUERY <name> KEYED BY (<column>, ...) [WRITING TO <sink>]
    [RETAIN FOR <duration> | RETAIN FOREVER] AS <select>
```

### PRV-2071 — key column unknown

`KEYED BY` names a column the `SELECT` does not produce, or names one twice. Keys are matched against
the **output** columns — by alias where the `SELECT` gives one, so `SUM(amount) AS spend` is `spend`,
not `amount`.

<!-- sql: refused PRV-2071 -->
```sql
CREATE CONTINUOUS QUERY merchant_spend
    KEYED BY (merchant, amount)
AS
SELECT merchant, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, window_start, window_end
```

`amount` is an input column; the output has `spend`. Keyed by what the view actually holds, it plans:

```sql
CREATE CONTINUOUS QUERY merchant_spend
    KEYED BY (merchant, window_end)
    RETAIN FOR P7D
AS
SELECT merchant, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, window_start, window_end;
```

The message does not list the columns, because it is raised before the registry has decided whether
you may read what the query reads.

### PRV-2072 — clause not built

Two clauses from the design's grammar that this engine does not build: `EMIT CHANGES WITH (...)`,
and a `SERVE AS VIEW` naming something other than the query. **Refused by name rather than
ignored**: an ignored `'allowed.lateness' = '30s'` drops rows somebody asked to be waited for.

<!-- sql: refused PRV-2072 -->
```sql
CREATE CONTINUOUS QUERY recent_big_txn
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000
EMIT CHANGES WITH ('allowed.lateness' = '30s')
```

**Do:** drop the `EMIT CHANGES WITH` list — every continuous query emits its changes — and say a
retention with `RETAIN FOR` or `WITH (retention = ...)`; give the query the name clients read. The
spellings the design uses that mean the same thing are accepted: `INDEXED BY (...)` for `KEYED BY`,
`INTO sink` for `WRITING TO`, and a trailing `EMIT CHANGES`.

```sql
CREATE CONTINUOUS QUERY recent_big_txn
    INDEXED BY (txn_id)
    RETAIN FOR PT24H
AS
SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000
EMIT CHANGES;
```

### PRV-2073 — `RANGE` over a column with no total order

`RANGE (column)` asks for an ordered index over the key's last column, and an index needs an order.
Three of the types on offer have none here that would not be a guess: text needs a collation (which
is why `<` on text is refused in a `WHERE` clause at all), `FLOAT` is IEEE 754 and `NaN` is ordered
against nothing, and a `DECIMAL`'s `compareTo` disagrees with its `equals`, so `1.0` and `1.00`
would be one index entry and two view rows. `BYTES` and `BOOLEAN` are refused with them.

<!-- sql: refused PRV-2073 -->
```sql
CREATE CONTINUOUS QUERY by_currency
    INDEXED BY (merchant) RANGE (currency)
AS SELECT merchant, currency FROM txn
```

**Do:** drop the `RANGE` — the key still works as a key, and point reads and full-key lookups are
unaffected — or range-scan a whole-number or temporal column:

```sql
CREATE CONTINUOUS QUERY by_amount
    INDEXED BY (merchant) RANGE (amount)
AS SELECT merchant, amount FROM txn
```

The check runs at registration, against the columns the view will actually have, rather than at the
first read that wanted the index.

### PRV-2020 on `INSERT` — there is no DML surface

`INSERT`, `UPDATE`, `DELETE` and `MERGE` are refused: there is nothing here whose rows a statement
may edit in place. `INSERT INTO <sink> SELECT ...` is refused rather than read as a registration,
because it carries neither the name the query is managed and read by — which is not the sink's, and
would collide the moment a second query wrote to the same sink — nor the key its view needs.

<!-- sql: refused PRV-2020 -->
```sql
INSERT INTO audit_trail SELECT txn_id, amount FROM txn
```

**Do:** say the name, the key and the sink:

```sql
CREATE CONTINUOUS QUERY audited KEYED BY (txn_id) WRITING TO audit_trail
AS SELECT txn_id, amount FROM txn
```

or `WITH (sink = 'audit_trail')`, or `pravaha register --sink audit_trail`.

An unknown `WITH (...)` option is the registry's PRV-8017, not a PRV-2xxx — see
[Registry codes](/help/topics/errors-registry).

A reserved word as the query's name is refused by the registry's name rule, PRV-8008, however the
query was registered — see [Registry codes](/help/topics/errors-registry).

## Where next

- [What the engine refuses, and why](/help/topics/sql-refusals) — the same ground by construct
- [The SQL reference](/help/topics/sql-reference) and [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query)
- [Parameters](/help/topics/sql-parameters)
