---
title: Row filters and masks — which rows, and which values, a reader is shown
slug: row-filters-and-masks
category: security
order: 27
icon: shield-lock
summary: "Row filters and column masks as catalogue policies (ADR-059): bound to a stream, a view or a tag and applied on every path. And the row filters a SecurityPolicy returns, with the rule that bounds them (PRV-7003)."
badge: SECURITY
audience: Operators
keywords: [row filter, row-level security, rls, mask, column mask, masking, policy, "CREATE ROW FILTER", "CREATE MASK", "SET POLICY", "UNSET POLICY", "SHOW POLICIES", session_attribute, current_user, is_member, "EXCEPT ROLE", tag, pii, allowWithRowFilter, AccessDecision, fingerprint, tenant, region, filtered read, PRV-7003, PRV-7006, PRV-7007, PRV-7038, PRV-7039, PRV-7040, "pravaha policy", tautology, vacuous, attributes, "user attrs"]
guide: security
related: [catalog-and-grants, authorization, sharing, audit, errors-security]
---

A grant decides **whether** you may read an object. A **policy** decides **what of it you are
shown**: a **row filter** keeps some rows, a **mask** replaces one column's value.

A filter reaches a read by one of two routes, and they differ in one way that matters:

| Route | Where it is written | Applies to |
|---|---|---|
| **Catalogue policies** (ADR-059 §4) — the rest of this part | `CREATE ROW FILTER` / `CREATE MASK`, bound with `SET POLICY` | every read, point read, pgwire read, **subscription**, registration and alert |
| **A `SecurityPolicy`'s row filter** — [below](#row-filters-from-a-securitypolicy) | Java: `AccessDecision.allowWithRowFilter(...)` | reads; a **subscription is refused**, because that path has no plan to put the predicate into |

With the catalogue on, prefer its policies: they are objects with an owner, a description, tags and
a version, kept in the engine's journal and applied by the engine wherever rows leave the object —
whichever client asks.

## Define, then bind

A policy is defined once and bound where it applies. Defining needs `CREATE` on the namespace;
binding to a stream or view needs `MANAGE` on it; binding to a tag needs `MANAGE` on the tenant,
because it reaches every object of the tenant carrying that tag, now and later.

```sql
CREATE ROW FILTER sales.region_scope
  AS region = session_attribute('region')
  EXCEPT ROLE finance_admin;

CREATE MASK sales.card_last4 ON COLUMN card_number
  AS 'XXXX-XXXX-XXXX-' || RIGHT(card_number, 4)
  EXCEPT ROLE payments_ops;

-- one object
ALTER STREAM orders SET POLICY sales.region_scope;
-- every object tagged pii: this one now, and the next one tagged
ALTER TAG 'pii' SET POLICY sales.card_last4;
ALTER VIEW payments SET TAGS ('pii');

SHOW POLICIES ON VIEW payments;
-- which filter, which mask, and why
SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW payments;
ALTER STREAM orders UNSET POLICY sales.region_scope;
-- refused while it is still bound anywhere
DROP ROW FILTER sales.region_scope;
```

## What an expression may say

The object's columns, literals, operators, `CASE`, `CAST`, a fixed list of pure functions, and three
about the reader: `session_attribute('claim')` (a claim of their credential), `current_user()` and
`is_member('role')`. A subquery, a non-deterministic function such as `RAND()` or
`CURRENT_TIMESTAMP`, or any function not on the list is refused with `PRV-7038`. A mask may name
only its own column and must keep its type. A reader whose credential lacks a claim a policy reads
is refused with `PRV-7039`, never guessed for.

**Where a claim comes from.** A static token carries the `claims` configured for it. A user the
engine keeps carries their **attributes**: an administrator sets them under **Admin · Users** (the
Attributes column, `region=EU, desk=rates`), with `pravaha user attrs ann region=EU`, or with
`PUT /api/v1/users/{name}/attributes`, and every session and API key of that user's presents them as
claims from their next call on. An API key carries exactly its holder's attributes. So
`session_attribute('region')` reads `EU` for `ann` once her `region` is set, and refuses her with
`PRV-7039` until it is.

## A filter must restrict something

A filter that lets every row through is refused rather than recorded as a restriction. That covers
more than `TRUE`: the engine decides the filter as it will run, so these are refused too (`PRV-7003`
at a read, `PRV-7038` when a policy that reads nothing about the reader is bound):

| Filter | Why it restricts nothing |
|---|---|
| `1 = 1 OR region = 'EU'` | one alternative is always true |
| `region IS NULL OR region IS NOT NULL` | every row is one or the other |
| `k = k`, `NOT (k <> k)`, `k >= k` on a `NOT NULL` column | a value always equals itself |
| `region = region`, `LOWER(region) = LOWER(region)`, `b OR NOT b` on a nullable column | true wherever the value is present; the only rows dropped are those where it is NULL, as a side effect |

If dropping rows with a missing value is the point, say it: `region IS NOT NULL` is accepted. A
filter **false for every row** (`amount <> amount`, `region = 'EU' AND 1 = 0`) is refused when a
policy that reads nothing about the reader is bound, since it would hide everything from everybody —
use `REVOKE` for that; bound to a reader through `is_member` or a claim it is enforced, and that
reader sees nothing. A policy that reads the reader (`session_attribute`, `current_user()`,
`is_member`) is judged for each reader when it applies: `NOT is_member('auditor') OR region = 'EU'`
restricts an auditor and restricts nothing for anyone else, so their reads are refused — exempt them
with `EXCEPT ROLE` instead. The check never refuses a filter that keeps some rows and not others by
their values; it does not find every tautology (`amount < 5 OR amount > 2` gets through).

## How they apply

- **Several filters AND together.** A holder of a policy's `EXCEPT ROLE` is not narrowed by it —
  that is the only exemption; ownership and `admin` are not.
- **Every path.** Flight reads and point reads, pgwire (text and binary), subscriptions (the snapshot
  and every commit), registrations and alerts. A registration reads each input as its registrant is
  shown it, so the view holds only that, and every query built on it carries it. An alert runs as
  its owner.
- **A masked column is shown, not compared.** Using it as a filter operand, a group, join or sort
  key, an aggregate's argument, a view key or a tap filter is refused with `PRV-7006`.
- **Two masks on one column** for one reader are refused with `PRV-7040`.
- **A changed policy ends open subscriptions** of the readers it affects with `PRV-7007`; they
  subscribe again under the new one. Two readers narrowed differently never share a computation.

From the command line: `pravaha policy ls|show|create-filter|create-mask|bind|unbind|drop`. In the
console, an object's page lists the policies reaching it, and **Admin · Policies** edits them.

## Row filters from a SecurityPolicy {#row-filters-from-a-securitypolicy}

The older route, and the one a node without the catalogue has: row-level security as a decision a
`SecurityPolicy` returns — **allow, but only the rows where this predicate holds**. A regional analyst may read `region_revenue` — the rows for `EU`. A tenant may
read the shared `orders` view — its own orders. The engine then guarantees three things: the filter
cannot be escaped from the SQL, it is applied to the input rather than to the answer, and it is
refused — never quietly skipped — where it cannot be enforced.

Row filters come from a `SecurityPolicy` you implement (see
[authorization](/help/topics/authorization)); the two policies `pravaha-server` ships, `permissive` and
`authenticated`, return none.

### Returning one

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

### Injected into the plan, never into the text

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

### The rule that bounds it — PRV-7003

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

#### A filter that restricts nothing is refused too

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

### Row filters and sharing

Sharing is by fingerprint — two registrations with the same normalised plan share one computation
(see [sharing](/help/topics/sharing)). **The fingerprint folds in the registrant's row filters**,
sorted, taken from the policy's answer for every stream the query reads. Two principals with the same
entitlement share one computation; two with different entitlements get two. Before this, a principal
restricted to one region and one restricted to none could share one computation and one copy of the
state, with the read path the only thing standing between them.

### What else a row filter changes

| Operation | With a row filter |
|---|---|
| Read the view (point read, `SELECT`, psql) | allowed; the filter applies |
| Subscribe to the view | **refused** — a subscription is handed each change as the view commits it, with no plan to put the predicate into, so it fails closed rather than over-serve. Read instead, or use a catalogue row filter, which a subscription does apply |
| Drop, pause, resume | **refused** — a slice is not a claim on the whole view, which every other reader depends on |
| List queries | the view is listed; its row count is withheld and sent as `-1` (the CLI prints `-`) — for a policy filter and a catalogue `CREATE ROW FILTER` alike |
| `GET /api/v1/me/permissions` | `"read": "filtered"`, without the predicate |
| Audit | recorded as `allowed with a row filter` |

### Pitfalls

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

- [Catalog and grants](/help/topics/catalog-and-grants) — whether a reader may read at all
- [Authorization](/help/topics/authorization) — the policy the grants implement, and the shipped ones
- [Sharing by fingerprint](/help/topics/sharing) — why two readers narrowed differently never share
- [Audit](/help/topics/audit) — every binding, and every filtered read, is recorded
- How it is built: [Architecture: row filters and masks](/help/architecture-governance#row-filters-and-masks)
