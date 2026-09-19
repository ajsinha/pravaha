---
title: Views and keys
slug: views-and-keys
category: concepts
order: 30
icon: table
summary: "A view is a query's answer, kept current and indexed by its key. What the key decides, how to choose it, what retention does, and why a view needs no second database to be served from."
audience: Everyone
keywords: [view, key, keyed by, keys, primary key, retention, retain for, retain forever, point read, served view, upsert, supersede, PRV-2071]
guide: continuous-queries#3-registering-a-continuous-query
related: [query-lifecycle, point-reads, zset-weights, create-continuous-query, sharing]
---

A **view** is a continuous query's answer: a table the engine maintains incrementally, commit by
commit, for as long as the query is registered. It is readable with SQL — by key, by scan, or with a
`GROUP BY` over it — and subscribable for its changes. It is also where the name comes from: a
query registered as `hourly_spend` maintains a view a `FROM` clause calls `hourly_spend`.

A view is **indexed by its key**, which is why reading one is a hash probe rather than a scan and
why it needs no serving database beside it ([ADR-014](/help/decisions/014-serve-maintained-views)):
a view that is already indexed *is* the serving database.

## The key decides what a row replaces

Every registration names a key — `KEYED BY (...)` in SQL, `--keys` as output-column ordinals on the
CLI, a list in the SDKs. **A second row with the same key supersedes the first.** That one rule
decides what the view means:

| Query shape | The key should be | Why |
|---|---|---|
| A windowed aggregate | the group columns plus the window (`user_id, window_end`) | one row per group per window |
| A filter or projection | the row's own identifier (`txn_id`) | one row per source row |
| A lookup-enriched stream | the source's identifier | enrichment adds columns, not rows |
| A global aggregate (`SELECT COUNT(*)…`) | any column of its single row | there is only ever one row |

Get the key wrong and the view is wrong in a way no error reports:

- **Too coarse** — keying a per-user-per-minute aggregate by `user_id` alone — and each minute's row
  replaces the previous minute's. The view holds one row per user: the latest minute, with every
  earlier minute silently gone.
- **Too fine** — keying by something that changes on every update — and rows that should replace
  each other pile up side by side.

A view with no key at all is refused: a view with no key is a log, and a point read against it has
nothing to look up. **`KEYED BY` is required.**

## Naming the key

```sql
CREATE CONTINUOUS QUERY user_minute
    KEYED BY (user_id, window_end)
AS
SELECT user_id, window_start, window_end,
       COUNT(*)    AS payments,
       SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id, window_start, window_end;
```

`KEYED BY` names **output** columns — by alias where the select list gives one, so `SUM(amount) AS
spend` is `spend`. The engine plans the `SELECT` and turns the names into ordinals, so reordering the
select list cannot silently change what the key is. A name the query does not produce, or a name
given twice, is refused:

<!-- sql: refused PRV-2071 -->
```sql
CREATE CONTINUOUS QUERY user_minute_bad
    KEYED BY (user, window_end)
AS
SELECT user_id, window_start, window_end, COUNT(*) AS payments
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id, window_start, window_end;
```

```text
PRV-2071  KEYED BY names 'user', which query 'user_minute_bad' does not produce. A key column is
named as the SELECT list names it -- by its alias where it has one, so SUM(amount) AS total is 'total'.
```

The message does not list the columns, because it is raised before the registry has decided whether
you may read what the query reads.

The registration-call form takes ordinals into the output: for `user_minute` above,
`--keys 0,2` (`user_id` is column 0, `window_end` column 2).

!!! note "The key is part of the query's identity"
    Two registrations differing only in their key are **two computations**, because the key changes
    the answer. See [sharing](/help/topics/sharing).

## Reading a view

A point read binds the key's values and is answered by a probe:

<!-- sql: read -->
```sql
SELECT window_end, payments, spend
FROM user_minute
WHERE user_id = ?
```

A scan with a filter reads every row and keeps the matches:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend
FROM user_minute
WHERE spend > 5000
```

And — because a read of a view is bounded by the view — an **unwindowed `GROUP BY` works over a
view**, where over a stream it is refused:

<!-- sql: read -->
```sql
SELECT tier, COUNT(*) AS users, SUM(total) AS volume
FROM user_volume
GROUP BY tier
```

With sample data:

```text
tier      users  volume
gold      41     2980400
standard  380    1177300
NULL      12     40150
```

NULL is a group, not a row that vanishes — here, users with no profile. Over a stream the same
`GROUP BY` would keep one accumulator per key for ever, and is refused with PRV-2050; over a view the
scan ends, so the state is bounded by it. Same SQL, different answer, because the input differs.

`ORDER BY` and `LIMIT` are refused on reads as everywhere (PRV-2020) — sort in your application
over a result a `WHERE` already narrowed.

## Retention

A view keeps rows for as long as their event time is within its **retention** of the committed
frontier. Rows whose event time falls further behind are evicted.

| Written as | Means |
|---|---|
| `RETAIN FOR P7D`, `RETAIN FOR 'PT24H'` | an ISO-8601 age |
| `RETAIN FOR INTERVAL '8' HOUR` | an interval in one unit: `SECOND`, `MINUTE`, `HOUR`, `DAY` or `WEEK` |
| `RETAIN FOREVER` | keep everything |
| left out | the node's default — forever, unless the node sets one |

A month is not a fixed length of event time and is refused. A retention the server cannot read is
refused rather than defaulted, because keeping a day of a view somebody asked to keep for an hour
changes what the view means. Retention is journalled with the registration, and — like the key — is
part of the fingerprint: the same SQL kept for different lengths of time is two computations.

**Retention is a policy, not a ceiling.** A view forgetting old rows is the policy working, because
a view is a cache of an answer. The separate *ceilings* that protect the machine (maximum keys, rows
per join side) refuse loudly instead of quietly changing results — PRV-4022 for a view that grew
past its ceiling. See [state and spill](/help/topics/state-spill).

## What a view is, physically

The engine's `ServedView` holds the current rows by key, applies each commit's weighted changes
(`+1` inserts or replaces, `-1` removes), and makes the commit visible atomically: a reader sees the
view before a commit or after it, never half of one. An update — the old row withdrawn, the new one
inserted — is published whole, so an answer never vanishes for one commit between the two. See
[consistency](/help/topics/consistency).

## Pitfalls

!!! warning "Pitfall: keying a windowed aggregate without its window"
    `KEYED BY (user_id)` on a per-minute aggregate keeps only the latest minute per user. Include
    `window_end` (or `window_start`) in the key.

!!! warning "Pitfall: a view holds fewer rows than expected"
    Retention. An explicit `RETAIN FOR` evicts by event time relative to the newest commit — replay
    old data into a query with a short retention and most of it is evicted as it lands.

!!! tip "Name your aggregate columns"
    An unaliased `COUNT(*)` comes back as `EXPR$2`, and `KEYED BY` cannot name it sensibly. Write
    `COUNT(*) AS payments`.

## Where next

- [Point reads](/help/topics/point-reads) — reading a view from every client
- [Z-set weights](/help/topics/zset-weights) — how a view is kept current
- [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query) — the whole grammar
- The long form: [Streams, queries and SQL §3](/help/continuous-queries#3-registering-a-continuous-query)
