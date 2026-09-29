# ADR-060: view names are unique per tenant, not per node

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **slice 1 built** — a refusal no longer names the tenant that holds a name, and a registration choosing another tenant's name is audited as `register:name`. The per-tenant registry key and the resolution of names within the caller's tenant (§2–§6) are **not built**; until they are, names stay unique on the node and `PRV-8001` still tells a caller that a name is taken |
| Date | 2026-09-29 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-050 (tenancy) — **supersedes its §1 "Names" bullet**; ADR-056 (queries on queries), ADR-057 (alerts), ADR-059 (the catalogue's `tenant.namespace.object`) |

## Context

ADR-050 kept view names unique on the node, whichever tenant holds them, and accepted one
consequence: a registration choosing a name another tenant already holds is refused with
`PRV-8001`, which tells the second tenant the name exists (TEN-1). It chose that disclosure over
scoping every surface that addresses a view by name through a tenant.

The disclosure is an existence oracle of the same class SX-1 and SX-5 closed on the subscribe path:
a caller who may read nothing of another tenant can learn, one probe at a time, which names that
tenant has registered. Under the shipped `permissive` and `authenticated` policies it tells nobody
anything new, because those policies let every tenant read every view. It matters exactly where
tenants are meant to be separate — the catalogue (ADR-059), whose grants are per tenant, and any
deployment whose policy confines reads to a tenant — and those are the deployments that will be
multi-tenant.

The catalogue already names a view `tenant.namespace.object` (`acme.default.orders`), so the
catalogue has per-tenant names and the engine does not: two tenants' `orders` are two catalogue
names and one engine name.

## Decision

### 1. A name is unique within its tenant

The registry's key becomes `(tenant, name)`. Two tenants may each register `orders`; each sees,
reads, subscribes to, describes, lists, drops and builds on its own. A name is resolved in the
caller's tenant on every surface, and a name another tenant holds is, to the caller, a name nothing
holds: registering it succeeds, reading it answers as for any unknown view. No refusal, message,
listing, metric a tenant can read, or timing difference says another tenant holds a name.

### 2. The engine name, and the default tenant's layout

Inside the engine a view is known by an **engine name**: for the default tenant (`public`) the name
itself, unchanged; for any other tenant a qualified name that cannot collide with a default-tenant
name or with another tenant's (the tenant and the name, joined by a character the name grammar
refuses). Checkpoint directories, dead-letter directories, sink deliveries, journal entries and
metric labels are keyed by the engine name. The default tenant's names, directories and journal
entries are therefore exactly what they are today, and a node that has only ever had one tenant
changes nothing on disk.

A checkpoint directory is already an encoding of the name (`QueryCheckpoints.directoryFor`), so a
tenant name from an identity provider — which may hold `/` or `..` — cannot escape the state
directory.

### 3. Existing state loads without a migration step

Every journal entry records the registrant and the checkpoint directory it was started with
(ADR-046). At recovery an entry is registered again as its owner, in the owner's tenant, and restored
from the **recorded** directory, not from one derived from the name. A view a non-default tenant
registered before this change therefore comes back under its tenant-scoped key with its state, and
its directory keeps its old name until it is dropped. Nothing is moved or rewritten.

### 4. Upstreams, alerts and the catalogue resolve in the owner's tenant

- **Queries on queries (ADR-056).** An upstream named in a `FROM` clause is resolved in the
  downstream registrant's tenant — which ADR-056's "same tenant only" already requires — so a chain
  cannot reach another tenant's view by naming it.
- **Alerts (ADR-057).** An alert's view is resolved in the alert's tenant, which the alert already
  records.
- **The catalogue (ADR-059).** A view's catalogue name is `tenant.default.name`, as now; its engine
  name (§2) is what `byEngineName` keys by, so the two line up without the catalogue changing its
  naming.

### 5. Admins and other tenants' views

An admin addresses a view in another tenant by its catalogue name (`acme.default.orders`) where a
surface takes a name. Unqualified, a name resolves in the admin's own tenant like anyone else's.

### 6. Surfaces

Flight SQL (statements, `GetTables`, the control actions), pgwire (queries and its catalogue shim),
REST (`/api/v1/queries`, `/views`, dead letters, debug, replacements, alerts), `ViewQuery`, the SDKs,
the CLI and the console all pass the caller's principal to the resolution; none of them sees an
engine name. Operator surfaces that already show every tenant — `GET /api/v1/tenants`, the metrics
endpoint, the audit trail — show engine names, which say the tenant.

## Slice 1, as built

Stopping the oracle needs §1–§6 together: while two tenants cannot both hold `orders`, any refusal of
the second registration tells the second tenant something. What was safe to build first:

- **The refusal says nothing about whose a name is.** A registration of a name another tenant holds
  is refused with `PRV-8001` in exactly the words used when the caller's own tenant holds it; a
  cross-tenant replacement (`PRV-8022`, reached only by an admin, or by a reader under
  `pravaha.security.administer=legacy-read`) no longer names the holding tenant.
- **The probe is visible.** Each such registration is audited as `register:name` `DENY`, with the
  holding tenant in the reason, so an operator can see one tenant probing another's names.
- Under the ownership rule (`pravaha.security.administer=ownership`, the default), a principal of
  another tenant cannot drop, pause or replace a view it did not register, so the `PRV-8001`
  message's advice ("drop it first") no longer leads anywhere across tenants.

`TenancyTest` proves both refusals and the audit record.

## Not built

The registry key, the engine name and resolution in the caller's tenant (§1–§6), and with them the
tests this ADR requires: two tenants register the same name and each sees only its own; a tenant
cannot learn of another tenant's name by registration or lookup; a restart restores both; existing
default-tenant state still loads. Until they are built `docs/SECURITY.md` says names are unique on
the node, and TEN-1 stands.

## Consequences

- Every published contract that takes a view name keeps its shape; what changes is which view a
  name means, and only on a node with more than one tenant.
- A deployment that relied on reading another tenant's view by its bare name — possible under
  `permissive` and `authenticated` — must use its catalogue name once §1 is built. That is the point
  of the change and is called out in the release notes when it ships.
