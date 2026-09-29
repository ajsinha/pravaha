---
title: Sharing by fingerprint
slug: sharing
category: concepts
order: 80
icon: diagram-2
summary: "Two registrations that plan to the same computation share one copy of its state, whatever they are called. What goes into a fingerprint, what makes two queries share or not, and why sharing is deliberately conservative."
audience: Everyone
keywords: [fingerprint, sharing, shared, dedup, deduplication, same computation, normalised plan, siblings, drop last name, security predicates, row filters, parameters, fork]
guide: concepts#5-sharing-is-by-fingerprint-not-by-name-or-text
related: [query-lifecycle, views-and-keys, sql-reference, row-filters-and-masks, subscriptions]
---

When a query is registered, its physical plan is **fingerprinted**. If a computation with the same
fingerprint is already running, the new registration does not build a second one: the new **name**
points at the existing **computation**, and both names read one copy of the state. A thousand
dashboards asking the same question cost one read of the source and one set of operators.

The fingerprint is taken from the **normalised plan**, not from the SQL text and not from the name —
so whitespace, aliases and the name you chose do not matter, and neither do the names others chose.

## What is in a fingerprint

| Part | Why it is included |
|---|---|
| the normalised physical plan | the computation itself |
| the **key** columns | the key changes what a row replaces, so it changes the answer |
| the **retention** | a view kept for a day and one kept for a week are different answers |
| **bound parameter values** | a registration binding `region = 'EU'` computes something different from one binding `'US'` |
| the **security predicates** (row filters) applied for the registering principal | two principals with different entitlements produce different plans, and must never land on each other's state |

What is **not** in it: the query's name, its sink (two names for one computation may each write to a
different sink — the computation is shared and each sink is fed from it), and who registered it
beyond the row filters their entitlements add.

## Seeing it happen

These two plan to the same thing — different aliases, different layout:

```sql
CREATE CONTINUOUS QUERY big_spenders_desk_a
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000;

CREATE CONTINUOUS QUERY big_spenders_desk_b
    KEYED BY (txn_id)
AS
SELECT t.txn_id, t.user_id, t.amount
FROM txn AS t
WHERE t.amount > 5000;
```

```bash
pravaha queries
```

```text
NAME                 STATE    FINGERPRINT   ROWS IN  SINK
big_spenders_desk_a  RUNNING  9d41c07ae3b2  18244    -
big_spenders_desk_b  RUNNING  9d41c07ae3b2  18244    -
```

One fingerprint, one computation, one `ROWS IN` counter. The CLI's `register` says so when it
happens ("a query with the same fingerprint is the same computation, shared"); the console's catalog
lists the names sharing each computation; and `GET /api/v1/queries/{name}` names the others you may
see.

## What makes two queries *not* share

| Difference | Shares? | Why |
|---|---|---|
| Whitespace, line breaks, comments | yes | not in the plan |
| Table and column aliases (`FROM txn AS t`) | yes | normalised away |
| Query name | yes | names are not in the fingerprint |
| Sink | yes | each name's sink is fed from the one computation |
| **Key** (`KEYED BY (txn_id)` vs `(user_id)`) | **no** | different answer |
| **Retention** (`RETAIN FOR P1D` vs `P7D`) | **no** | different answer |
| **Bound parameter values** | **no** | different computation |
| A principal with a **row filter** vs one without | **no** | different plan |
| **`AND` operands in a different order** | **no** | see below |

The one you will meet first is the last. The planner keeps predicates in the order the text gives
them, so these two fingerprint differently:

```sql
CREATE CONTINUOUS QUERY eu_large_a
    KEYED BY (order_id)
AS
SELECT order_id, amount FROM orders WHERE region = 'EU' AND amount > 1000;

CREATE CONTINUOUS QUERY eu_large_b
    KEYED BY (order_id)
AS
SELECT order_id, amount FROM orders WHERE amount > 1000 AND region = 'EU';
```

That costs a second copy of the state. It never costs a wrong answer.

## Why sharing is conservative

Normalisation goes as far as the planner's own canonical form and no further. Two queries a person
would call identical can still get separate computations; two queries that are **different** can
never share one. Where those two risks meet the design takes the first: sharing too little is a
missed efficiency, sharing too much would be two queries reading each other's rows. If you want two
registrations shared, **write the predicate the same way in both** — or register once and let both
teams read the one name.

## Dropping a shared computation

**A drop removes a name.** The computation is released when its **last** name is dropped:

```sql
DROP CONTINUOUS QUERY big_spenders_desk_a;
```

`big_spenders_desk_b` keeps running with its state warm. Neither registrant knows the other exists,
and dropping eagerly would be an outage caused by somebody tidying up their own query.

## Parameters: carry the column, not the value

A bound value is part of the plan, so it is part of the fingerprint — two bindings of the same SQL
are two computations. Usually the better design is a view that **carries** the column and is
filtered at read time or at the subscription tap, so one computation answers every value:

<!-- sql: parameterised -->
```sql
SELECT region, window_start, window_end, SUM(amount) AS revenue
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
WHERE region = ?
GROUP BY region, window_start, window_end
```

Registered once per region with a bound value — through the registry's
`register(name, sql, keys, principal, BoundParameters)` — that is one computation per region.
(Neither the CLI's `register` nor the SDKs' `register` takes bound values today; an embedder
reaching the registry does.) Registered **without** the filter — as `region_revenue`, grouped by region —
it is one computation for all of them, and each reader asks for its own:

<!-- sql: read -->
```sql
SELECT window_end, revenue FROM region_revenue WHERE region = ?
```

A subscriber gets the same effect at the tap with `--filter region=EU`: ten desks, ten filters, one
computation. The rule that decides whether a filter can be applied to a view rather than forking a
computation is one rule in three places — row filters, subscription filters and parameters: **a
filter can be applied to a view iff the view carries every column it names.** See
[parameters](/help/topics/sql-reference#parameters).

## One reader for many queries {#one-reader-for-many-queries}

A fingerprint shares a *computation*. Queries that ask **different** questions of one source can
still share its **reader**: one read of the source, each record handed to every query that reads it.
Which sources allow it depends on what they promise:

| Source | Readers | Why |
|---|---|---|
| Aerospike, Cassandra | **one** per binding | at-least-once and unordered, so a query joining late can catch up privately and overlap harmlessly (SRC-3) |
| Kafka, a file read once through | **one** per binding, met at an **exact seam** (ADR-054) | their positions are ordered and a reader can stop exactly at one, so a joining query catches up to the shared reader's position and is attached there: each record reaches each query once, in order |
| JDBC, `postgres-cdc`, `mysql-cdc`, Delta, a followed file | one **per query** | not yet shown to have a total order of positions (JDBC, Delta), or a replication slot confirmed per reader (CDC) |

At the exact seam, a query joining **behind** the shared reader reads only the gap, privately, until
its position equals the shared one; a query joining **ahead** of it (after a restore from a newer
checkpoint) waits until the reader reaches it. Pausing, resuming and restoring cost only that gap.
A catch-up that stops making progress towards its seam fails the group with a named error rather
than read past it and deliver a record twice. A thousand queries over one topic read it **once**.

On [shared lanes](/help/topics/lanes#sharing-lanes) the saving compounds: a shared reader writes each
record into each shared lane once, and every query on that lane is handed the one copy.

## Subscribers share too

Registration and subscription are separate. Many subscribers attach to one computation, and the
computation outlives all of them — which is why a dashboard reconnecting costs nothing: the state
is warm because it belongs to the query, not to whoever was watching. The console goes one step
further: ten browsers on one live view are **one** subscription on the engine.

## Pitfalls

!!! warning "Two queries that look identical are not shared"
    Different keys, different retention, a different bound value, a row filter on one principal —
    or `AND` operands in a different order. Compare the fingerprints with `pravaha queries`.

!!! tip "Measure what sharing saves"
    `ROWS IN` counts per computation, not per name, and the per-query state gauges
    (`pravaha_query_state_held`) likewise. Two names on one fingerprint show the same numbers; that
    is the sharing, not a double count.

## Where next

- [Continuous queries and their lifecycle](/help/topics/query-lifecycle)
- [Parameters](/help/topics/sql-reference#parameters) — when a parameter forks a computation
- [Row filters](/help/topics/row-filters-and-masks) — the security predicates in a fingerprint
- The long form: [Concepts §5–§6](/help/concepts#5-sharing-is-by-fingerprint-not-by-name-or-text)
