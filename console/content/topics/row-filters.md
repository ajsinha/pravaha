---
title: Row filters — allowed, but only these rows
slug: row-filters
category: security
order: 30
icon: funnel
summary: "A policy can allow a read with a row filter. It is injected into the plan, never concatenated into the SQL, applies only where the view still carries the column, and changes a query's fingerprint."
badge: SECURITY
audience: Engineers
keywords: [row filter, row-level security, rls, allowWithRowFilter, AccessDecision, PRV-7003, fingerprint, tenant, region, filtered read]
guide: security#row-filters-and-the-rule-that-bounds-them
related: [authorization, sharing, views-and-keys, point-reads, subscriptions]
---

Row-level security in Pravaha is a decision a policy returns: **allow, but only the rows where this
predicate holds**. A regional analyst may read `region_revenue` — the rows for `EU`. A tenant may
read the shared `orders` view — its own orders. The engine then guarantees three things: the filter
cannot be escaped from the SQL, it is applied to the input rather than to the answer, and it is
refused — never quietly skipped — where it cannot be enforced.

Row filters come from a `SecurityPolicy` you implement (see
[authorization](/help/topics/authorization)); the two policies `pravaha-server` ships, `permissive` and
`authenticated`, return none.

## Returning one

```java
@Override
public AccessDecision mayRead(Principal who, String view) {
    if (who.hasRole("regional-eu")) {
        return AccessDecision.allowWithRowFilter("region = 'EU'");
    }
    return AccessDecision.allow();
}
```

The predicate is SQL over the view's columns, written by the policy — never by the caller.

## Injected into the plan, never into the text

The filter is parsed against the view's own schema and **injected into the plan**, above the scan
and below anything the reader's query does. It is never concatenated into the SQL the caller sent.
That matters: `WHERE revenue > 0 OR revenue <= 0` would neutralise an appended `AND region = 'EU'`,
but a predicate placed in the plan has no syntax for the caller to reach.

Below the reader's aggregate, not above it: when a regional reader asks for a total, the total is
over their rows only.

<!-- sql: read -->
```sql
SELECT window_end, SUM(revenue) AS revenue FROM region_revenue GROUP BY window_end
```

With these rows in the view:

```text
region | window_end          | revenue
-------+---------------------+--------
EU     | 2026-09-19 09:05:00 |  12000
US     | 2026-09-19 09:05:00 |  30500
APAC   | 2026-09-19 09:05:00 |   7200
```

an unrestricted reader gets `49700` for the window; a reader holding `region = 'EU'` gets `12000` --
the sum of the rows they may see, not a filter applied to everybody's total.

## The rule that bounds it — PRV-7003

> A row filter is sound **if and only if the view carries every column it names.**

If the column was aggregated away, each row of the view already mixes values the principal may and
may not see, and nothing applied afterwards can separate them. So the read is **refused** with
PRV-7003, and the message names the fix.

`hourly_spend` is keyed by user and hour; it has no `region` column. A reader whose policy returns
`region = 'EU'` for it is refused on every read:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend WHERE spend > 1000
```

```text
PRV-7003  the row filter for hourly_spend (region = 'EU') cannot be applied to it: <why the column is not there>. A filter naming a column this view does not carry cannot be enforced on it -- the column was aggregated away, so each row already mixes values this principal may and may not see. Register a view that applies the filter before aggregating.
```

The fix is a **different query**, with the filter applied to the input before aggregating — its own
computation and its own state:

```sql
CREATE CONTINUOUS QUERY eu_revenue
    KEYED BY (window_end)
AS
SELECT window_start, window_end, SUM(amount) AS revenue, COUNT(*) AS orders
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
WHERE region = 'EU'
GROUP BY window_start, window_end;
```

Or keep the dimension in the view, so one computation serves every region and the filter applies at
read time:

```sql
CREATE CONTINUOUS QUERY revenue_by_region
    KEYED BY (region, window_end)
AS
SELECT region, window_start, window_end, SUM(amount) AS revenue
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end;
```

<!-- sql: read -->
```sql
SELECT region, window_end, revenue FROM revenue_by_region WHERE window_end > TIMESTAMP '2026-09-19 09:00:00'
```

Forking state per principal is available and explicit, never automatic: a per-user filter applied by
forking would multiply engine state by the number of users, and you would learn that from a memory
alarm.

### A filter that restricts nothing is refused too

When a filter is true for every row — `TRUE`, `1 = 1 OR region = 'EU'`, `x IS NULL OR x IS NOT
NULL`, a `NOT NULL` column compared with itself — or drops only the rows where a column it compares is
NULL, as `region = region` does on a nullable column, it restricts nothing. Rather than serve the read
unrestricted while the audit trail records "allowed with a row filter", the engine refuses it with
PRV-7003. It decides this over the predicate that would run, not over whether the planner folded it
away, which it does for `TRUE` and almost nothing else (TAUTOFILTER-1); it never refuses a filter that
keeps some rows and not others by their values, and it does not find every tautology. A filter that
says `region IS NOT NULL` is a restriction and is applied. A policy that means "this principal may see
everything" says so with `allow()`. A policy that builds its predicate
from a claim should check the claim is present, rather than let a missing value produce a predicate
that means something other than intended.

## Row filters and sharing

Sharing is by fingerprint — two registrations with the same normalised plan share one computation
(see [sharing](/help/topics/sharing)). **The fingerprint folds in the registrant's row filters**,
sorted, taken from the policy's answer for every stream the query reads. Two principals with the same
entitlement share one computation; two with different entitlements get two. Before this, a principal
restricted to one region and one restricted to none could share one computation and one copy of the
state, with the read path the only thing standing between them.

## What else a row filter changes

| Operation | With a row filter |
|---|---|
| Read the view (point read, `SELECT`, psql) | allowed; the filter applies |
| Subscribe to the view | **refused** — the change stream is the shared computation, and filtering it per principal at the tap is not the same as filtering a read. Read instead |
| Drop, pause, resume | **refused** — a slice is not a claim on the whole view, which every other reader depends on |
| List queries | the view is listed; its row count is withheld and sent as `-1` (the CLI prints `-`) |
| `GET /api/v1/me/permissions` | `"read": "filtered"`, without the predicate |
| Audit | recorded as `allowed with a row filter` |

## Pitfalls

!!! warning "Pitfall: moving to a filtering policy removes subscriptions"
    Every principal whose decision starts carrying a row filter loses the ability to subscribe. Plan
    the migration: readers who relied on a live feed need a view registered with the filter applied,
    which they may read without one.

!!! warning "Pitfall: a filter over a column the view renamed"
    The filter is parsed against the view's schema, so it must use the view's column names — the
    alias from the `SELECT` list, not the source column. A filter naming a column the view does not
    have is PRV-7003.

!!! note "Filters are the policy's, not the caller's"
    A caller's own `WHERE` narrows further; it can never widen what the filter allows.

## Where next

- [Authorization](/help/topics/authorization) — the four questions and the shipped policies
- [Sharing by fingerprint](/help/topics/sharing)
- [Audit](/help/topics/audit)
