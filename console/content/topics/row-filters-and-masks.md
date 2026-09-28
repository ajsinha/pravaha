---
title: Row filters and masks — policies kept in the catalogue
slug: row-filters-and-masks
category: security
order: 27
icon: shield-lock
summary: "Row filters and column masks as catalogue objects (ADR-059 §4): created once, bound to a stream, a view or a tag, applied to every read, subscription, registration and alert, and exempting only an EXCEPT ROLE."
badge: SECURITY
audience: Operators
keywords: [row filter, mask, column mask, masking, policy, "CREATE ROW FILTER", "CREATE MASK", "SET POLICY", "UNSET POLICY", "SHOW POLICIES", session_attribute, current_user, is_member, "EXCEPT ROLE", tag, pii, PRV-7006, PRV-7007, PRV-7038, PRV-7039, PRV-7040, "pravaha policy"]
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
