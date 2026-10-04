# Security extension guide: policies, verifiers, audit sinks, and the catalogue

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

How to change who may do what: write a `SecurityPolicy`, a `TokenVerifier` or an `AuditSink`, or — on a
node — express the rules as catalogue grants, row filters and masks instead of code. **Configuring** the
shipped options is [`SECURITY.md`](../../operations/SECURITY.md), the canonical page; the components and the
decision flow are [governance](../../design/architecture/governance.md). Conventions:
[`CONTRIBUTING.md`](CONTRIBUTING.md).

---

## 1. Which seam, and where it can be plugged in

| Seam | Interface | Shipped | Pluggable where |
|---|---|---|---|
| Authentication | `TokenVerifier`: `Principal verify(String token)` | `StaticTokenVerifier`, `IdentityTokenVerifier` (users, keys, sessions — [ADR-052](../../design/adr/052-the-engine-is-the-identity-authority.md)) | a host you assemble: `PravahaFlightServer.authenticatedBy(...)`, `PravahaPgWireServer.authenticatedBy(...)` |
| Authorization | `SecurityPolicy` | `SecurityPolicy.PERMISSIVE`, `AuthenticatedOnlyPolicy`, `CatalogPolicy` | a host you assemble: `new QueryRegistry(views, policy, audit, streams)`, `new ViewQuery(catalog, policy, audit)`, `authorizedBy(policy, audit)` on the Flight and PostgreSQL servers |
| Audit | `AuditSink`: `void record(AuditEvent)` | none, memory, a JSON-lines file (`FileAuditSink`) | the same host assembly |
| Rules as data | grants, row filters, masks, tags | the Pravaha Catalog ([ADR-059](../../design/adr/059-the-pravaha-catalog-governs-live-answers.md)) | **any node**, with `pravaha.catalog.enabled: true`, through SQL (`GRANT`, `CREATE ROW FILTER`, `CREATE MASK`), `/api/v1/catalog/*`, `pravaha grant` / `pravaha policy`, or the console |
| Alert channels | `NotifierPlugin` | `webhook`, `log` | any node, by `ServiceLoader` — the [connector guide](CONNECTOR_DEVELOPMENT.md#lookups-and-notifiers) |

**On `pravaha-server` the policy is not a plug-in point.** `PravahaNode.securityPolicy()` returns the
catalogue's policy when `pravaha.catalog.enabled`, else `SecurityPolicy.PERMISSIVE` or
`AuthenticatedOnlyPolicy` by `pravaha.security.policy`; the verifier is the static table or the identity
store; the audit sink is chosen by `pravaha.security.audit`. There is no bean, `ServiceLoader` or class
name that installs a policy, verifier or sink of your own, and the embedded engine runs everything as the
anonymous principal under `PERMISSIVE`. A rule that the catalogue can express belongs in the catalogue; a
rule that needs code (an external entitlement service, an LDAP group) needs a host you assemble from the
library modules — the [hosts](../../design/architecture/hosts.md) page lists them. (The node's own refusal of
an unknown `pravaha.security.policy` says "or implement SecurityPolicy for rules of your own"; that is
only true of a self-assembled host — suggested finding LETTERS-1.)

---

## 2. Writing a `SecurityPolicy`

Only `mayRead` is abstract; every other question has a default:

| Method | Default | Asked when |
|---|---|---|
| `mayRead(principal, view)` | — | a read of a view or a stream, by the name the SQL gives |
| `maySubscribe(principal, view)` | `mayRead` | opening a subscription, and re-checked while it runs |
| `mayRegisterQuery(principal)` / `(principal, name)` | refuses the anonymous principal | a registration |
| `mayBuildOn(principal, input)` | `mayRead` | each stream or view a registration's plan names |
| `mayBuildThrough(principal, source)`, `mayReadThrough(principal, source)` | `mayRead` | a source reached only through an upstream view |
| `mayAdminister(principal, view)` | ownership only | drop, pause, resume, replace, debug, replay a dead letter |
| `mayWriteTo(principal, sink)` | allow | a registration that names a sink |
| `mayReadAudit(principal)` | deny | `GET /api/v1/audit` |
| `narrowing(principal, object)` | `Narrowing.NONE` | after an allow: row filters and masks kept as objects |
| `registered(owner, view)`, `dropped(view)` | nothing | notifications, at registration (and recovery) and drop |

An `AccessDecision` is `allow()`, `allowWithRowFilter(predicate)`, `deny(reason)` or
`deniedWithoutDetail()`. A row filter is SQL over the object's columns; the engine plans it **into** the
read — above the scan, below any aggregate — never into SQL text. It is enforceable only on a view that
still carries every column it names; otherwise the read is refused with `PRV-7003` and the message says to
filter before aggregating.

### An example, executed

A policy that restricts each reader to the rows of the region their credential claims, and lets only
analysts register:

```java
package com.example.policy;

import com.ash.messaging.pravaha.security.AccessDecision;
import com.ash.messaging.pravaha.security.Principal;
import com.ash.messaging.pravaha.security.SecurityPolicy;

/**
 * Anyone with a "region" claim may read, restricted to rows of their region; an analyst may also
 * register queries. Everyone else is refused.
 */
public final class RegionPolicy implements SecurityPolicy {

    @Override
    public AccessDecision mayRead(Principal principal, String view) {
        return principal.claim("region")
                .filter(region -> region.matches("[A-Z]{2,4}"))   // a claim is data: never trust its shape
                .map(region -> AccessDecision.allowWithRowFilter("region = '" + region + "'"))
                .orElseGet(() -> AccessDecision.deny("no region claim"));
    }

    @Override
    public AccessDecision mayRegisterQuery(Principal principal) {
        return principal.hasRole("analyst")
                ? AccessDecision.allow()
                : AccessDecision.deny("registering needs the analyst role");
    }
}
```

Assembled with the library modules — a registry fed by the `filesystem` source over a four-line CSV
(`1,EU,500`, `2,US,900`, `3,EU,150`, `4,APAC,700`) and a `ViewQuery` asking the same policy:

```java
RegionPolicy policy = new RegionPolicy();
ViewCatalog views = new ViewCatalog();
try (QueryRegistry registry = new QueryRegistry(views, policy, AuditSink.NONE, orders)
        .feedingFrom(new PluginSourceFeeds().bind(new SourceBinding("orders", "filesystem",
                Map.of("path", csv, "schema", "id:INT64,region:STRING,amount:INT64"))))) {
    Principal ann = new Principal("ann", "public", Set.of("analyst"), Map.of("region", "EU"));
    Principal bob = new Principal("bob", "public", Set.of(), Map.of("region", "US"));
    Principal eve = new Principal("eve", "public", Set.of(), Map.of());

    registry.register("orders_view", "SELECT id, region, amount FROM orders", List.of(0), ann);
    ViewQuery reads = new ViewQuery(views, policy, AuditSink.NONE);
    // each of ann, bob, eve: reads.execute("SELECT id, region, amount FROM orders_view", p)
    // then bob tries registry.register("bobs", "SELECT id FROM orders", List.of(0), bob)
}
```

Its real output:

```
ann: [[1, EU, 500], [3, EU, 150]]
bob: [[2, US, 900]]
eve: PRV-7002  eve may not read 'orders_view': no region claim
bob registers: PRV-7002  bob may not register a query: registering needs the analyst role
```

One view, one computation, three readers each shown what they may see. Because the registrant's row
filters are part of the [fingerprint](../../design/architecture/registry.md#registration-and-sharing), two
registrants with different filters never share a computation.

### The rules a policy must keep

- **Be fast.** It is on the path of every read; the engine does not cache a policy's answers, so that a
  revocation takes effect — cache in the policy, and drop the cache on change (`CatalogAccess` caches
  until the catalogue's generation moves).
- **Treat claims as data.** A claim comes from a credential; validate its shape before it goes into a
  predicate, as the example does.
- **Deny with a reason a person can act on**; it reaches the client after `PRV-7002` and the audit trail.
  `deniedWithoutDetail()` when the reason itself would disclose something.
- **Say the same thing whether or not an object exists.** The engine authorizes the name the SQL text gives
  before resolving it, so a missing view and a forbidden one are the same code; a policy that answered
  differently by existence would re-open that oracle.
- A policy that keeps objects (owners, grants) records them in `registered` and forgets them in `dropped`.

---

## 3. Writing a `TokenVerifier` or an `AuditSink`

```java
public interface TokenVerifier {
    Principal verify(String token);              // throw PravahaException(SecurityErrors.UNAUTHENTICATED, ...) to refuse
    static TokenVerifier rejectAll() { ... }
}

public interface AuditSink {
    void record(AuditEvent event);               // every decision, allows included
}
```

A verifier returns a `Principal(id, tenant, roles, claims)` — the tenant decides which names the caller
sees ([ADR-060](../../design/adr/060-view-names-are-unique-per-tenant.md)) and the claims are what a policy
reads. It is called on every HTTP request and Flight call and before every PostgreSQL statement, so a
revoked credential ends open connections; make it fast and thread-safe (one verifier serves every
concurrent call). Never return `null` or `Principal.ANONYMOUS`, and refuse with a message that says the
credential was rejected and **nothing about why** — "expired" versus "unknown" is an oracle for whoever is
guessing. An audit sink must not block a read for long and
must not lose records silently; `FileAuditSink` is one JSON object per line, rotated by size.

---

## 4. Rules as data: the catalogue

On a node, most of what a custom policy would do is a grant, a row filter or a mask:

```sql
GRANT SELECT, SUBSCRIBE ON VIEW sales.revenue TO ROLE analyst;
CREATE ROW FILTER eu_only AS region = 'EU' EXCEPT ROLE auditor;
ALTER VIEW sales.revenue SET POLICY eu_only;
```

`CatalogPolicy` answers every `SecurityPolicy` question from these (`SELECT`, `SUBSCRIBE`, `BUILD_ON`,
`CREATE`, `MODIFY`/`MANAGE`, `WRITE`), and its `narrowing` applies row filters and masks to reads,
subscriptions, registrations and alerts alike — with a masked column used in a comparison refused
(`PRV-7006`). The statements, the REST endpoints and the console screens are [`SECURITY.md`](../../operations/SECURITY.md#the-pravaha-catalog-grants-kept-in-the-engine-adr-059);
the decision rules are `CatalogAccess`'s ([governance](../../design/architecture/governance.md#pravaha-catalog)).

---

## 5. Testing

| What | Test |
|---|---|
| A policy's decisions through every surface | `HttpAuthorizationTest`, `RegistryEndpointsTest` (`pravaha-server`), `FlightRegistryTest` (`pravaha-flight`) as patterns |
| The codes a refusal carries | `ErrcSecurityTest` (`pravaha-cli`) |
| The catalogue | `pravaha-catalog`'s own tests; `console/tests/test_catalog_governance.py`, `test_catalog_policies.py` for the screens |
| Identity | `pravaha-identity`'s tests; `sdk/python/tests/test_authentication.py` against `TestFlightServerMain` |

```bash
tools/worktree-build.sh -o -pl pravaha-security,pravaha-catalog,pravaha-identity test
tools/worktree-build.sh -o -pl pravaha-server test -Dtest='HttpAuthorizationTest,RegistryEndpointsTest'
```
