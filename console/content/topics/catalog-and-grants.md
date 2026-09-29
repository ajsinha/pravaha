---
title: Catalog and grants — who may do what, kept in the engine
slug: catalog-and-grants
category: security
order: 25
icon: diagram-3
summary: "The Pravaha Catalog (ADR-059): namespaces, owners, descriptions and tags; USE, SELECT, SUBSCRIBE, BUILD_ON and the rest, granted to roles and users and inherited down; changed with GRANT and REVOKE, and enforced on every read, subscribe and registration."
badge: SECURITY
audience: Operators
keywords: [catalog, catalogue, grant, revoke, namespace, owner, tags, comment, privilege, USE, SELECT, SUBSCRIBE, BUILD_ON, CREATE, WRITE, MODIFY, MANAGE, OWN, "SHOW GRANTS", "SHOW EFFECTIVE ACCESS", CatalogPolicy, ADR-059, PRV-7030, PRV-7031, PRV-7033, PRV-7034, "pravaha catalog", "pravaha grant", "pravaha access why"]
guide: security
related: [row-filters-and-masks, authorization, authentication, audit, errors-security, cli-reference]
---

With the catalogue on, **grants live in the engine**. Every governed object — a namespace, a
registered query and its view, a stream, a sink — has an **owner**, a **description**, **tags** and a
**version**, and who may do what to it is a set of grants the engine keeps, journals and enforces on
every read, subscription and registration, whichever client asks. Changing access is a statement,
not a restart.

## Turning it on

```yaml
pravaha:
  catalog:
    enabled: true
    authority: import      # or: catalog
```

It is **off by default**. With `authority: import` the first start imports
`pravaha.security.policy` once as grants that mean what it meant — `permissive` gives every
privilege on the whole catalogue to `ROLE public` (everyone), `authenticated` gives reading,
subscribing, building and registering to `ROLE authenticated` (every verified caller) — so a node
behaves the same the moment after as the moment before. If that setting later changes, the node
refuses to start (`PRV-7034`): two authorities that disagree is the failure the catalogue removes.
`authority: catalog` imports nothing and lets grants alone decide. The grants are kept in
`catalog.journal` beside the registry journal unless `pravaha.catalog.journal` says otherwise.

## Names

Objects are named `tenant.namespace.object`. A query registered as `revenue` by a principal of
tenant `acme` is `acme.default.revenue`, owned by whoever registered it — so every statement written
before the catalogue keeps working. Move it into a namespace with `ALTER VIEW revenue SET NAMESPACE
sales`, and its grants go with it. What the node is configured with lives under `node`:
`node.streams.orders`, `node.sinks.audit_out`.

## Privileges

| Privilege | On | Means |
|---|---|---|
| `USE` | namespace | may resolve names in it — needed for anything inside it |
| `SELECT` | view, stream | may read by point read or scan |
| `SUBSCRIBE` | view | may receive its changes live — separate from `SELECT` |
| `BUILD_ON` | view, stream | may register a query that reads it |
| `CREATE` | namespace, tenant | may register queries (a namespace) or create namespaces (a tenant) |
| `WRITE` | sink | may name it in `WRITING TO` |
| `MODIFY` | view | may pause, resume, replace or drop it |
| `MANAGE` | any | may change its grants, description and tags |
| `OWN` | any | everything; one owner, moved with `OWNER TO` |

The rules are few. Grants are **allow-only**, to a `ROLE` or a `USER`; access is the union. A grant
on a namespace, a tenant or the whole `CATALOG` is **inherited** by everything under it, now and
later. An **owner** holds everything on what they own. The **`admin` role** holds everything, so a
node with one administrator before the catalogue has the same one after it. **Tenants are walls**:
a grant inside `acme` reaches only `acme`'s principals. Everyone may use their own tenant's
`default` namespace and the node's.

Reading a view needs `SELECT` on the view, not on its sources — that is the point of a view — but
**registering** a query needs `BUILD_ON` on every input it names, so nobody builds on what they could
not read. A revoked `SUBSCRIBE` ends an open subscription within seconds with `PRV-7002`.

## The statements

```text
CREATE NAMESPACE sales COMMENT 'Order-to-cash';
GRANT USE, CREATE ON NAMESPACE sales TO ROLE sales_eng;
ALTER VIEW revenue SET NAMESPACE sales;
GRANT SELECT, SUBSCRIBE ON VIEW sales.revenue TO ROLE analyst;
REVOKE SUBSCRIBE ON VIEW sales.revenue FROM ROLE analyst;
ALTER VIEW sales.revenue SET TAGS ('domain' = 'finance', 'certified');
COMMENT ON VIEW sales.revenue IS 'Revenue per region per closed hour';
ALTER VIEW sales.revenue OWNER TO ROLE finance_data;
SHOW GRANTS ON VIEW sales.revenue;
SHOW GRANTS TO ROLE analyst;
SHOW EFFECTIVE ACCESS FOR USER ana ON VIEW sales.revenue;
SHOW NAMESPACES;
```

They run wherever `CREATE CONTINUOUS QUERY` runs — Flight SQL, JDBC, `pravaha query` — and answer
with rows. The same is `/api/v1/catalog/...` over REST, `pravaha catalog`, `pravaha grant`,
`pravaha revoke` and `pravaha access why ana sales.revenue` on the command line, and the console's
**Catalog → Objects and grants** tab and **Admin → Grants** editor. Every change is an audit event.

## User attributes as claims {#user-attributes-as-claims}

A grant is to a role or a user. What a [row filter or mask](/help/topics/row-filters-and-masks) may
say about the reader goes further: `session_attribute('region')` reads a **claim** of the reader's
credential. For a person the engine keeps, the claims are their **attributes**, set by an
administrator:

- in the console, **Admin · Users**, the Attributes column (`region=EU, desk=rates`);
- `pravaha user attrs ann region=EU desk=rates` on the command line;
- `PUT /api/v1/users/{username}/attributes` over HTTP.

Every session and API key of that person presents them from their next call on; an API key carries
exactly its holder's attributes, never more. A static token (`pravaha.security.tokens`) carries the
`claims` configured for it. A policy that reads a claim the reader lacks refuses the read with
`PRV-7039` rather than guess — so set the attribute before binding the filter that reads it. A person
carries at most 32 attributes of up to 256 characters each, and the names the engine gives a
credential's own claims (`via`, `session`, `key`, `mustChangePassword`) are refused. Changing an
attribute is an audit event (`user.attributes_changed`), like a grant.

## What goes wrong

- `PRV-7002` — a read, subscription or registration the grants do not allow. The message says which
  privilege was missing, and on what.
- `PRV-7031` — no object by that name that you may see; the same answer whether it does not exist or
  you may not `USE` its namespace, so it confirms nothing.
- `PRV-7033` — changing grants, a description or tags needs `MANAGE`; giving an object away needs
  ownership.
- `PRV-7030` — the catalogue is off on this node; `pravaha.security.policy` decides there.

The rest are on [Security codes](/help/topics/errors-security).

## Where next

- [Authorization](/help/topics/authorization) — the policy the catalogue implements
- [Audit](/help/topics/audit) — every grant, revocation and refusal is recorded
- [CLI reference](/help/topics/cli-reference#the-pravaha-catalog-and-grants)
