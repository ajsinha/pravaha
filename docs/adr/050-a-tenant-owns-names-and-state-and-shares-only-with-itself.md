# ADR-050: a tenant owns names and state, is admitted by quota, and shares only with itself

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `TenantQuotas` and `QueryFingerprint` in `pravaha-registry`, `pravaha.tenancy.*`, `GET /api/v1/tenants` and `pravaha.tenant.*` in `pravaha-server`. The console's tenants screen (`/admin/tenants`) and the register screen's quota refusals are built |
| Date | 2026-09-26 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-025 (registration), ADR-031 (row filters), ADR-039 item 7, ADR-045 (cluster mode, on hold), ADR-046 (replacement) |

## Context

`Principal` has carried a tenant since the security module was written, and until now two things
read it: the audit log, which recorded it, and `ReadAdmission`, which stops one tenant holding
every read permit. A registration ignored it. Any tenant could register as many queries as the
node would hold, and identical SQL from two tenants became one computation.

ADR-045 is on hold, so this ADR covers a **single node**. Nothing here assigns a tenant to a node,
and nothing here would stop a later ADR-045 from doing that.

## Decision

### 1. What a tenant scopes

A tenant **owns** three things:

- **the names it registers**. The registry records the tenant of each name when it registers the
  name, and removes the record when the name is dropped. A cutover keeps the name's tenant.
- **the computations behind those names**. A computation belongs to exactly one tenant (§3).
- **the keys those computations' views hold**. This is what the state quota counts (§2).

The audit trail records the tenant on every event, as it already did, and a quota decision is
recorded with the tenant as its target (`register:quota`, `replace:quota`,
`replace:tenant`).

A tenant **does not scope** the following. Each is a deliberate choice:

- **Names.** A view name is unique on the node, whichever tenant holds it. Scoped names would mean
  every read path — Flight SQL, pgwire, the REST view read, `ViewQuery` — resolving a name through
  the caller's tenant. That is a namespace feature with its own ADR. One consequence follows and
  was already true: `PRV-8001` tells a principal in one tenant that a name is taken in another.
- **Reads, sources and sinks.** The policy decides these (`mayRead`, `mayWriteTo`,
  `mayAdminister`), as before. A policy that should confine reads to a tenant says so in
  `mayRead`. The engine does not assume a tenant boundary that the policy did not draw.
- **Administration.** `drop`, `pause` and `resume` are still decided by `mayAdminister`, so an
  operator in an `ops` tenant can still drop a query. Replacement is the exception, in §3.
- **Lanes and CPU.** Every tenant's queries run on the node's lane threads, and shared lanes are
  shared across tenants. A query's CPU is bounded only by how many queries its tenant may hold.
  Per-tenant CPU scheduling is not built.
- **Operator state that lives outside the view.** Window slices and join rows are bounded per
  query by ADR-037's ceilings (`PRV-4001`). The tenant's state quota does not count them.

### 2. Admission quotas, refused by name at registration

There are two limits, and both are set per tenant under `pravaha.tenancy`. A tenant with no entry
of its own takes the defaults. A limit that is not set means no limit, which is how a node behaved
before this ADR. Zero is a limit: a tenant limited to zero queries can register none.

| Limit | Counts | Refused with | When |
|---|---|---|---|
| `max-queries` | names the tenant holds, including a name attached to a computation the tenant already runs | `PRV-8020 REGISTRY_TENANT_QUERY_QUOTA` | a registration that would add a name when the tenant already holds `max-queries` names |
| `max-state-keys` | keys held by the views of the tenant's computations | `PRV-8021 REGISTRY_TENANT_STATE_QUOTA` | a registration or replacement that would start a computation when the tenant's views already hold `max-state-keys` keys |

The check runs **after** authorization and planning, and **before** a sink is opened or a feed
starts. Authorization comes first so that a principal who may not register learns nothing about
the tenant's use. The check comes before the sink so that a refusal costs nothing. Every refusal
is recorded three ways:

1. it is thrown with its code and a sentence that names the tenant, the count and the limit;
2. it is audited as a `DENY`;
3. it is counted per tenant and per quota.

When the tenant has a limit, an admitted registration is audited as an `ALLOW` with its counts.

**Why state is counted in view keys.** Three measures were available:

- **Off-heap bytes** attribute a shared lane's inbox to every query on the lane, so summing them
  over a tenant overstates the tenant by the number of its queries on each lane.
- **Operator state** is reported in slices for windows and in rows for joins. These units cannot
  be added together.
- **View keys** are owned by exactly one computation, counted exactly, and already bounded per view
  by `DEFAULT_MAX_KEYS`.

View keys are therefore the measure. Bytes remain published per query, as they were.

**The state quota is an admission bound, not a runtime ceiling.** A new computation holds no
state, so a registration cannot be refused on what the computation will hold. It is refused on
what the tenant already holds. A tenant whose queries grow past the quota after they were admitted
keeps running. Its next registration that would start a computation is refused, and the excess is
visible, because `pravaha.tenant.state.keys` is above `pravaha.tenant.quota.state.keys` and the
REST surface shows both numbers. The only alternative is to stop or shed a running query of the
tenant. That imposes a failure on readers who did nothing, and choosing which query to shed is a
policy the engine has no basis for. The per-query ceilings still stop a single query that grows
without bound, as they did before.

**A lowered quota refuses on replay.** Registrations replayed from the journal at startup go
through the same check, in journal order. A registration beyond a lowered limit appears in
`Recovery.refused` with `PRV-8020` or `PRV-8021`, and its journal entry stays live. Raising the
limit and restarting restores it. `PRV-8007` works the same way when a principal has lost access.

### 3. Isolation: identical SQL shares within a tenant and not across tenants

The tenant is part of `QueryFingerprint`, which follows the precedent of the row filters that
`QueryRegistry.prepare` already adds to the fingerprint. The tenant is length-prefixed in the
canonical form. A tenant name comes from an identity provider and could contain a newline, so an
unprefixed tenant `t\nsecurity:p` would hash the same as tenant `t` holding row filter `p`.

Sharing across tenants is ruled out for three reasons, and each is a reason on its own:

1. **Accounting.** A shared computation's state cannot be charged to both tenants, and charging it
   to the first registrant means one tenant's quota pays for another tenant's query. The dropping
   tenant would also change the other tenant's bill.
2. **Blast radius.** A pause, a failure or a replacement acts on the computation, not on the name.
   A shared computation means that tenant A's `pause` stops tenant B's view. `TenancyTest` pauses
   one tenant's name and checks that the other tenant's view is still running.
3. **Disclosure by timing.** A registration that attaches to a running computation returns a view
   that is already populated, while a new registration starts empty. Across tenants, that
   difference tells one tenant what another tenant has asked.

The cost is duplicated computation when two tenants ask the same question. That cost is paid
knowingly. A deployment that wants two groups to share should put them in one tenant. Within a
tenant nothing changes: ten analysts in one tenant who open the same dashboard still cost one
query, as the registry's own javadoc claims.

**A replacement stays in its tenant.** Only a principal of the tenant that registered a name can
replace it. A replacement from another tenant is refused with `PRV-8022 REGISTRY_TENANT_MISMATCH`,
before the new version starts. If it were allowed, the new version would be charged to one tenant
and read by another, or the name would move out of the tenant whose readers use it. `drop`,
`pause` and `resume` are not restricted in this way, because none of them creates anything to
charge.

### 4. What an operator sees

- **`GET /api/v1/tenants`** returns the defaults and, for each tenant, the names held, the distinct
  computations, the view keys held, the limits in force (`null` for none, never zero), and the
  refusals by quota since the node started. A principal whose policy allows `mayReadAudit` sees
  every tenant, because that principal can already read every refusal in the trail. Any other
  principal sees only their own tenant. Each read is audited as `http.tenants.read`, with how
  much it showed.
- **Meters, tagged `tenant`**: `pravaha.tenant.queries`, `pravaha.tenant.state.keys`,
  `pravaha.tenant.quota.queries` and `pravaha.tenant.quota.state.keys`, where a quota of none
  reads `NaN` so that no alert divides by zero. There is also the counter
  `pravaha.tenant.refusals{quota="queries"|"state"}`. A tenant's meters are published the first
  time the tenant appears and are not removed while the node runs, because the refusal count is
  history an alert depends on.
- **Status codes.** `PRV-8020` and `PRV-8021` return `409`: the request body is fine, and what the
  tenant already holds is the reason for the refusal. `PRV-8022` returns `403`.
- **Configuration.** `pravaha.tenancy` is bound with `ignoreUnknownFields = false`, so a misspelt
  key stops the node from starting. A negative limit or a blank tenant is `PRV-8023
  REGISTRY_TENANCY_MISCONFIGURED`.

## Consequences

- Identical SQL registered by two tenants is now two computations. Before this change it was one.
  A deployment where every principal is in the default tenant `public` sees no difference.
- `QueryRegistry` stays under the 1,500-line ceiling because the journal replay moved to
  `RegistryRecovery`.
- **Not built:** tenant-scoped names, per-tenant CPU scheduling, a byte-denominated state quota,
  editing quotas at runtime (they are configuration, read at startup), and the console's screens.
  Editing grants stays out, as `REMAINING.md` says: grants belong to the deployment's identity
  system.
