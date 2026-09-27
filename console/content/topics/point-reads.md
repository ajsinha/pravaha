---
title: Point reads
slug: point-reads
category: reading
order: 10
icon: search
summary: "Reading a maintained view by key: bound parameters, what a read sees, what it costs, the ceilings it runs under, and why a view whose query failed refuses to answer."
badge: READ
audience: Developers
keywords: [select, where, key, lookup, parameter, bind, "?", view read, PRV-8004, PRV-4023, MAX_RESULT_ROWS, admission]
guide: user-guide#3-ask-a-question
related: [views-and-keys, sql-parameters, consistency, client-snippets, subscriptions]
---

A continuous query is registered once and keeps its answer current; the answer is its **view**. Reading
the view is a different operation from running the query: it is an ordinary `SELECT` over rows the
engine has already computed, answered at the view's last commit. Nothing is recomputed, no source is
touched, and a thousand readers asking the same question cost a thousand small reads of one
computation — not a thousand computations.

This page is about that read: how to write it, how to bind a value into it, what it sees, what it
costs, and what it refuses. Subscribing to a view's *changes* instead of asking for its current rows is
[Subscriptions](/help/topics/subscriptions).

## At a glance

| | |
|---|---|
| What you send | One `SELECT` naming one view, with `?` placeholders for values |
| Where | Flight SQL (Java SDK `query`, Python SDK `query`, `pravaha query`, the console's workbench and view pages, any Flight SQL client), the PostgreSQL gateway, or an embedded engine's `query(sql)` |
| What it reads | The view's rows at its **committed frontier** — never a half-applied commit |
| Planned by | The same planner and operators a continuous query uses, so `WHERE` means exactly the same thing in both |
| Result ceiling | 1,000,000 rows per read (`ViewQuery.MAX_RESULT_ROWS`); past it the read fails naming the number, never truncates |
| Plan cache | 256 distinct statements per node, so the same parameterised text is planned once |
| Authorization | Per read, as the calling principal; a row filter is added to the plan, not to your SQL |
| A failed query | Its view refuses reads with PRV-8004 rather than serving a frozen snapshot as current |

## The views used on this page

Every example below reads views a node in the help's example deployment maintains. Two of them:

| View | Columns | Key |
|---|---|---|
| `hourly_spend` | `user_id`, `window_start`, `window_end`, `spend` | `user_id`, `window_end` |
| `big_txn` | `txn_id`, `user_id`, `merchant`, `amount` | `txn_id` |

`hourly_spend` is registered as:

```sql
CREATE CONTINUOUS QUERY spend_per_hour
    KEYED BY (user_id, window_end)
    RETAIN FOR P7D
AS
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

(The page registers it as `spend_per_hour` so as not to collide with the deployment's own
`hourly_spend`; the two are the same computation, and the engine would share them — see
[Sharing by fingerprint](/help/topics/sharing).)

## Reading one key

The shape almost every application uses: one row, found by the view's key.

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend
FROM hourly_spend
WHERE user_id = ?
```

The `?` is a **bound parameter**. From the CLI:

```bash
pravaha query --sql "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?" \
              --params u1
```

```text
user_id	window_end	spend
u1	2026-09-19T09:00:00Z	4200
u1	2026-09-19T10:00:00Z	1350
2 rows
```

(Sample data: `u1` spent 4,200 in the 08:00–09:00 window and 1,350 in the next. The CLI prints a
tab-separated header, one line per row, and a row count.)

From Python:

```python
from pravaha import connect

with connect("grpc://localhost:19090") as client:
    for row in client.query(
            "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?", ["u1"]):
        print(row["user_id"], row["window_end"], row["spend"])
```

```text
u1 2026-09-19 09:00:00+00:00 4200
u1 2026-09-19 10:00:00+00:00 1350
```

From Java:

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:19090");
     QueryResult result = client.query(
             "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?", "u1")) {
    for (Row row : result) {
        System.out.println(row.getString("user_id") + " " + row.getLong("spend"));
    }
}
```

```text
u1 4200
u1 1350
```

Every other client — `psql`, curl-free Flight SQL tools, the embedded engine — is on
[Client snippets](/help/topics/client-snippets).

## Reading the whole key, and ranges

Both key columns pin one row:

<!-- sql: read -->
```sql
SELECT spend
FROM hourly_spend
WHERE user_id = ? AND window_end = ?
```

A `TIMESTAMP` placeholder is bound as a number: **nanoseconds since the epoch** (the engine's
timestamp unit). The Java SDK sends any `Number` for it; the CLI's `--params` turns a value that looks
like a number into one, so this works as written — `1789808400000000000` is 2026-09-19T09:00:00Z:

```bash
pravaha query --sql "SELECT spend FROM hourly_spend WHERE user_id = ? AND window_end = ?" \
              --params u1,1789808400000000000
```

```text
spend
4200
1 row
```

A range over one key column and a predicate over a value column are just as ordinary:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend
FROM hourly_spend
WHERE spend > 1000 AND user_id IN ('u1', 'u2', 'u7')
```

<!-- sql: read -->
```sql
SELECT txn_id, merchant, amount
FROM big_txn
WHERE amount BETWEEN 5000 AND 20000
  AND merchant LIKE 'ACME%'
```

## Aggregating over a view

A dashboard asks for a summary, not a row. Over a **view** an unwindowed `GROUP BY` is supported — the
read is bounded by the view's size, so the state it needs ends when the scan does. The same SQL over a
**stream** is refused with PRV-2050, because there it would grow for ever (see
[Aggregation](/help/topics/aggregation)).

<!-- sql: read -->
```sql
SELECT user_id, SUM(spend) AS spend_today, COUNT(*) AS hours_active
FROM hourly_spend
GROUP BY user_id
HAVING SUM(spend) > 10000
```

```text
user_id	spend_today	hours_active
u1	12450	6
u9	30120	9
2 rows
```

<!-- sql: read -->
```sql
SELECT tier, COUNT(*) AS users, SUM(total) AS spend
FROM user_volume
GROUP BY tier
```

`NULL` is a group here, as SQL says: users with no `tier` gather under one `NULL` row rather than
vanishing.

## Parameters: bind, never concatenate

**Bind values; never build the SQL string.** A bound value is never parsed as SQL — by the time it
reaches the server the statement is already planned — and the server plans a parameterised statement
once however many values you ask about. The node keeps plans for 256 distinct statements, so a loop that
pastes each user id into the text evicts every plan and replans on every call; a loop that binds plans
once.

| Rule | What happens otherwise |
|---|---|
| `?` only in `WHERE` and `HAVING` | Elsewhere it is refused with PRV-2063: a parameter is a value, not a column or a table |
| One value per `?`, in order | Too few or too many is PRV-2061 |
| The value must fit the column | A string where a `BIGINT` is wanted is PRV-2062, raised before the call leaves your process where the SDK can tell |
| A statement run with no values for its `?` | PRV-2060 |

The rules and the reasoning are on [Parameters](/help/topics/sql-parameters) and in
[ADR-032](/help/decisions/032-parameters-are-values-not-queries).

## What a read sees

A read is answered **at the view's committed frontier**: the state after the last commit the lane
finished, never a commit in progress. Two consequences worth knowing:

- **An update is never half visible.** A corrected window is a retraction and an insert in the same
  commit, so a reader sees the old row or the new one, never neither and never both.
- **A row can change between two reads.** The view is live. Reading `hourly_spend` twice a second
  apart may give two different `spend` values for an open window — that is the answer being maintained,
  not an inconsistency.

[Consistency](/help/topics/consistency) goes further: what the frontier is, how it relates to the
watermark, and what retention evicts.

## What a read costs

A view is lane-local state already in memory, so a read needs no execution engine of its own: the
engine plans your SQL once, then runs its filter and projection over the view's committed rows, with
the result materialised before it is sent. That makes a read cheap and makes its cost honest:

| Cost | Grows with |
|---|---|
| Planning | Nothing, after the first call with the same text — the plan is cached |
| Scanning | The number of rows the view holds, which is why [retention](/help/topics/views-and-keys) matters |
| Sending | The rows that pass the filter |

Three bounds keep one careless read from hurting everyone else:

| Bound | What you see |
|---|---|
| More than 1,000,000 result rows | The read fails naming the ceiling (PRV-4024) — never a silently truncated answer |
| The node's read admission is full | PRV-4026 (queue full, retry with backoff), PRV-4027 (waited and gave up), or PRV-4028 (your tenant's share is used). All three reach a client as `RESOURCE_EXHAUSTED`, which drivers retry |
| A read deadline, when the node sets one | PRV-4029, naming how long it ran and how many rows it had produced |

## What a read refuses

| You wrote | Refused with | Why |
|---|---|---|
| A view name that is not registered | PRV-2002 when the planner cannot resolve it; PRV-4023 when the view went away between planning and reading | The message deliberately does not list the views that do exist; check `pravaha queries` |
| A view whose query has **failed** | PRV-8004 | The rows are still held and were correct as of the failure — what cannot be offered is the impression that they are current |
| A view you may not read | PRV-7002 | And the refusal is the same whether or not the view exists, so a name cannot be probed |
| `ORDER BY` / `LIMIT` | PRV-2020 | Sort in the application over a result the `WHERE` already narrowed |
| A join between two views | PRV-4025 | A request/response read names exactly one view; do the join in the continuous query instead |

<!-- sql: read-refused PRV-2020 -->
```sql
SELECT user_id, spend
FROM hourly_spend
ORDER BY spend DESC
```

<!-- sql: read-refused PRV-4025 -->
```sql
SELECT h.user_id, h.spend, b.amount
FROM hourly_spend h
JOIN big_txn b ON b.user_id = h.user_id
```

The PRV-8004 refusal reads like this, and says where to look:

```text
PRV-8004  the query behind 'hourly_spend' has failed, so this view stopped being current when it did:
<the lane's failure>. Its rows are still held and are correct as of that moment -- what cannot be
offered is the impression that they are live. Check the query's state and failure through the
registry, fix the cause, and re-register it.
```

!!! warning "Pitfall: a paused query still answers"
    `PAUSE CONTINUOUS QUERY` stops a query advancing but keeps its view answering at the frontier it
    reached. Reads succeed and return rows that are no longer being updated. Check the state
    (`pravaha queries`, or the query's page in the console) when numbers stop moving.

!!! warning "Pitfall: an empty result from a RUNNING windowed query"
    A windowed view is empty until the watermark passes the first window's end — so append a row
    past it. A stream with no declared event time cannot get here at all: the query is refused with
    `PRV-2002` rather than registered. See
    [Event time and watermarks](/help/topics/event-time-watermarks).

!!! tip "Name your columns"
    A read returns the view's column names. An unaliased aggregate in the continuous query comes back
    as `EXPR$2`; write `SUM(amount) AS spend` when you register, and every reader thanks you.

## Where next

- [Subscriptions](/help/topics/subscriptions) — be told about every change instead of asking.
- [Consistency](/help/topics/consistency) — what exactly a read is consistent with.
- [The PostgreSQL gateway](/help/topics/pgwire) — the same reads from `psql`, DBeaver or Grafana.
- [Client snippets](/help/topics/client-snippets) — this read in every client.
