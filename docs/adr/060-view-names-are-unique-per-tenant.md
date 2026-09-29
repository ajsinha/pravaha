# ADR-060: view names are unique per tenant, not per node

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **built, with the amendments in *As built*** — the registry keys a view by (tenant, name), and every surface resolves a name in the caller's tenant: the registry, `ViewQuery`, Flight SQL and its actions, pgwire and its catalogue shim, REST, listings, subscriptions, dead letters, debug sessions, replacements, queries on queries, alerts and the catalogue. Closes TEN-1 |
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

## As built

Built in three slices. The first made a refusal name no tenant: a cross-tenant replacement
(`PRV-8022`, reached only by an admin now) still does not name the holder, while the `register:name`
audit of a registration choosing another tenant's name is gone with the collision -- that name is
free. The second (the registry, recovery, the catalogue, `ViewQuery`) and the third (Flight, pgwire,
REST) build §1–§6. Where the code taught something the decision above did not say, or said
differently, it is recorded here and this section wins.

### The engine name is the catalogue name

§2 left the separator open ("a character the name grammar refuses"). It is the catalogue's own form:
outside the default tenant a view's engine name is `tenant.default.name` -- `acme.default.orders` --
which is also the name §5 has an admin address it by and the name `byEngineName` keys by (§4). A view
name is a plain identifier and cannot hold a dot, so the form cannot collide with a default-tenant
name or with another tenant's. `ViewNames` (in `pravaha-security`) is the one place a name is made,
taken apart, resolved and shown. The catalogue records the view under its bare name in its tenant's
default namespace, as before (`acme.default.orders`), so the two line up without a mapping.

### Resolution, and what it refuses

- A bare name is the caller's own tenant's. A catalogue name in the caller's own tenant is also its
  own. A catalogue name of another tenant is honoured for an admin (the `admin` role) and refused to
  anyone else with `PRV-7002`, in words that do not depend on whether the name is held -- resolution
  is syntax only and never asks what exists. In a SQL `FROM` clause a catalogue name is quoted
  (`SELECT * FROM "acme.default.orders"`); on a control action, a REST path or the CLI it is written
  as it is.
- A listing holds the caller's tenant's views under their bare names; an admin's holds every
  tenant's, the others by catalogue name. Another tenant's name is not a name the caller is refused:
  it is one nothing holds, so it is neither listed nor audited as a refusal.
- `ViewQuery` plans against `ViewCatalog.scopedTo(principal)` -- the caller's views under the names
  it writes -- and caches a plan per scope, since `FROM orders` is a different view in each tenant.
- Another tenant's view is administered by nobody outside it but an admin, under either
  `pravaha.security.administer` rule (`QueryOwners`): a name only reaches one through a catalogue
  name.
- The policy, the catalogue, the audit trail, checkpoints, dead letters, sinks and metrics are asked
  and keyed by engine name. A policy written against bare names still matches every default-tenant
  view; one that names another tenant's views names them `tenant.default.name`.

### §3, corrected: what an entry records, and the one record recovery writes

§3 said every journal entry records the directory it started with. An `R` record does not -- only a
cutover's `C` does -- and its directory is the one its journalled name implies. So recovery restores
each entry from the recorded directory, or from the one its **journalled** name implies, never from
one derived from the new engine name: a view another tenant registered under a bare name keeps the
directory that name implied, and nothing is moved.

What recovery does write, once per such entry, is a journal record of a new kind, `N old new`: the
entry is keyed by its engine name from then on, with its indexes, lane and pending replacement.
Without it, a drop of the view would be journalled under a name its registration does not have, and
a default-tenant registration of the same bare name would replace it at the next replay. `N` is a
kind of its own so that a build that predates it refuses the journal by name rather than replaying a
view under a name it no longer has -- a node that has run this cannot be downgraded past it.

Two things §3 did not foresee follow from keeping the old directory:

- **A default-tenant registration whose directory such an entry still holds** checkpoints beside it
  (`orders-1`), and is journalled as a `C` record, the one that carries a directory. A `C` record now
  carries a registration's bound values after its eight fields; a build that reads eight reads what it
  always did.
- **Restored sink state** recorded under the bare name is claimed by the engine name: a checkpoint is
  one computation's and a computation one tenant's, so the bare name there can only be the view's own.

### Dead letters move

Unlike checkpoints, a view's dead-letter files (`<name>.dlq`, `.dlq.replays`, `.dlq.evicted`) are
renamed to its engine name at the same recovery, before its feed opens a queue. A queue is read by
name, so left where it was, a default-tenant view later registered under the same bare name would be
shown another tenant's rejected records -- the raw bytes of them. A file the new name already has is
not overwritten.

### Alerts and the catalogue

An alert's view is resolved in the alert's tenant; an alert recorded before names were per tenant
recorded the bare name, which is read as its tenant's. A notification and an alert's status name the
view by its bare name, beside the tenant. The catalogue re-keys a view recorded under its bare engine
name the first time it is registered under its engine name, keeping where it was moved, its owner and
its grants; a `GRANT … ON VIEW orders` resolves `orders` in the caller's tenant.

### What operators see

Operator surfaces show engine names, which say the tenant: the metrics endpoint's `query` label, the
audit trail's targets, `GET /api/v1/tenants`, lane rebalancing, the registry journal and the recovery
log (`Recovery.recovered()` is engine names). `GET /api/v1/status` counts registered names across the
node; a count says nothing about which names exist.

### Tests

`TenantViewNamesTest` (registry: the same name in two tenants on every registry surface, the oracle
closed on describe, require, drop, pause, dead letters, read, schema and prepare, admins by
catalogue name, upstreams, and a restart restoring both tenants' same-named views with their own
state); a fixture of the state develop `1f52ecf2` wrote (`src/test/resources/adr060/`, made by
`Adr060DevelopFixture`) loading with the default tenant unchanged and another tenant's view under its
tenant; `AlertTenantNamesTest`; `CatalogJournalTest`'s re-keying; `DeadLetterRenameTest`;
`FlightTenantNamesTest`, `PgTenantNamesTest` and `TenantViewNamesHttpTest` on the wire. `TenancyTest`
now proves the name is free in another tenant and the refusal within a tenant says nothing of others.

### Left open

- **Alert names** are still unique on the node (ADR-057), so `CREATE ALERT` of a name another tenant
  holds is refused with `PRV-8041`: the same class of oracle, for alerts rather than views. Scoping
  them is the same change over `AlertService`'s map and is not part of this ADR.
- **pgwire oids** are minted from one node-wide counter, as before; an oid seen by one tenant
  describes nothing to another, but the numbering says how many relations were described before.

## Consequences

- Every published contract that takes a view name keeps its shape; what changes is which view a
  name means, and only on a node with more than one tenant.
- A deployment that relied on reading another tenant's view by its bare name — possible under
  `permissive` and `authenticated` — must now be an admin and use its catalogue name. That is the
  point of the change and is called out in the release notes.
