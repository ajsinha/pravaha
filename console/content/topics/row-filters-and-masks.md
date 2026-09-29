---
title: Row filters and masks — policies kept in the catalogue
slug: row-filters-and-masks
category: security
order: 27
icon: shield-lock
summary: "Row filters and column masks as catalogue objects (ADR-059 §4): created once, bound to a stream, a view or a tag, applied to every read, subscription, registration and alert, and exempting only an EXCEPT ROLE."
badge: SECURITY
audience: Operators
keywords: [row filter, mask, column mask, masking, policy, "CREATE ROW FILTER", "CREATE MASK", "SET POLICY", "UNSET POLICY", "SHOW POLICIES", session_attribute, current_user, is_member, "EXCEPT ROLE", tag, pii, PRV-7003, PRV-7006, PRV-7007, PRV-7038, PRV-7039, PRV-7040, "pravaha policy", tautology, vacuous, attributes, "user attrs"]
guide: security
related: [catalog-and-grants, row-filters, authorization, errors-security, cli-reference]
---

A grant decides **whether** you may read an object. A **policy** decides **what of it you are
shown**: a **row filter** keeps some rows, a **mask** replaces one column's value. Both are catalogue
objects with an owner, a description, tags and a version, kept in the engine's journal and applied
by the engine wherever rows leave the object — whichever client asks.

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
