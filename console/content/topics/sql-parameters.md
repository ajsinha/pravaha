---
title: Parameters
slug: sql-parameters
category: sql
order: 40
icon: input-cursor-text
summary: "A ? stands for a value that selects rows — in WHERE and HAVING, nowhere else. Binding from the SDKs, the CLI and psql; why a bound value can never be SQL; and what a parameter costs a continuous query."
audience: Developers
keywords: [placeholder, question mark, prepared statement, bind, "$1", injection, PRV-2060, PRV-2061, PRV-2062, PRV-2063, tap, registration, fingerprint, ADR-032]
guide: continuous-queries#9-parameters
related: [point-reads, client-snippets, pgwire, sharing, sql-refusals]
---

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

## Where a `?` may stand

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

## Parameters in a read

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

### Binding them

Python — values in a list, in placeholder order:

```python
from pravaha import ClientOptions, connect

with connect(options=ClientOptions.create("grpc+tls://pravaha:9090", token=token)) as client:
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

### NULL

`WHERE tier = ?` bound to NULL matches **nothing**, because `x = NULL` is UNKNOWN for every row and a
filter keeps only TRUE. The engine follows the standard rather than rewriting the comparison into
`IS NULL` behind your back. If you mean "the ones with no tier", say so:

<!-- sql: read -->
```sql
SELECT user_id, window_end, total FROM user_volume WHERE tier IS NULL
```

## Refused positions

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

## When the values do not fit

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

## Parameters in a continuous query

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

### How a registration binds values today

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

## Handles carry statements, not sessions

A prepared-statement handle contains the statement and, once bound, its values. The server keeps
nothing between preparing and fetching: no session table, nothing to expire, no requirement that the
second call reach the same node as the first. Plans are cached, bounded, and keyed on the catalogue's
generation, so a plan built when a view had three columns is not reused after it gains a fourth.
**A handle is a plan, never a permission:** authorization runs on every execution.

## Where next

- [Point reads](/help/topics/point-reads) — the read side in depth.
- [Client snippets](/help/topics/client-snippets) — binding from every client.
- [Sharing by fingerprint](/help/topics/sharing) — why a bound value makes a new computation.
