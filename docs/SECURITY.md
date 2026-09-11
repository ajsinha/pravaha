# Security

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Who is asking, what they may see, and what is written down about it.

The decision behind all of this is [ADR-031](adr/031-authorization-at-the-pravaha-layer.md).

---

## Authorization is enforced here, not in the store

This is structural rather than defence in depth, and the reasons are worth knowing because they
determine what you can and cannot delegate:

**A view is derived data the store has never seen.** There is no record in Aerospike whose
permissions correspond to "u4's gold-tier total for the window ending 12:05".

**A change feed is read once and shared by every query registered against it.** Enforcing per
principal at the source would mean reading it once per principal — the read amplification the
architecture exists to remove — or reading it as a superuser, which enforces nothing.

**The store may have nothing to enforce with.** Aerospike Community Edition has no row-level security
at all.

**A continuous query has no caller to attribute.** It runs for months with nobody connected. There is
no session to push down even in principle.

Your store's own controls still matter — Pravaha's connection to it should be least-privileged — but
they protect the connection, not the query.

## The three seams

| | Answers | SPI |
|---|---|---|
| Authentication | Who is this? | `TokenVerifier`: credential in, `Principal` out |
| Authorization | What may they read? | `SecurityPolicy`: allow / allow-with-row-filter / deny |
| Audit | Who asked what, and what were they told? | `AuditSink`: every decision, allows included |

Kept apart so a deployment can adopt an external identity provider without rewriting its rules, or
tighten its rules without touching authentication.

**Pravaha stores no passwords and runs no identity provider.** `TokenVerifier` is where you plug in
the one you already have. `StaticTokenVerifier` exists for tests and single-tenant installs and says
so in its name.

## Setting it up

```java
PravahaFlightServer server = new PravahaFlightServer(views)
        .authenticatedBy(myTokenVerifier)          // credential -> Principal
        .authorizedBy(myPolicy, myAuditSink)       // what they may read, and the record
        .admitting(ReadAdmission.of(16), Duration.ofSeconds(30))
        .hosting(registry)
        .start("0.0.0.0", 9090);
```

Credentials travel as `authorization: Bearer <token>` on **every call** — Flight has no session, which
is what lets a server behind a load balancer answer without the balancer pinning a client to a node.

A server started **without** `authenticatedBy` accepts every call as `Principal.ANONYMOUS`. That is
correct for an engine embedded in a process that has already authenticated its caller, and wrong for
anything on a network somebody else can reach. Two explicit states, not a default that quietly
downgrades.

## Registering is authorized separately from reading

Different risks. A read costs a scan and ends; a registration commits the node to memory and a share
of every lane for as long as it exists. The default policy lets an anonymous caller read and refuses
to let them register.

> **A lambda does not override `mayRegisterQuery`.** `SecurityPolicy` is a functional interface on
> `mayRead`, so `(principal, view) -> allow()` keeps the default — which is right far more often than
> not, and surprising exactly when somebody meant a permissive policy for a development server.
> `SecurityPolicy.PERMISSIVE` is written out explicitly for that reason; it once said "everyone sees
> everything" and then refused registration.

## Row filters, and the rule that bounds them

A policy may return a row filter, which is injected **into the plan**, above the scan and below any
aggregate:

```java
AccessDecision.allowWithRowFilter("tier = 'gold'")
```

**Never concatenated into the SQL.** `WHERE total > 0 OR total <= 0` is enough to neutralise an
appended `AND tier = 'gold'`; a predicate in the plan has no syntax for the caller to reach. There is
a test for exactly that attack.

**Below the aggregate, not above it.** `SUM` over gold rows is 1500, not 1557 — filtering the *answer*
is not filtering the input.

And the rule that decides whether it can be applied at all:

> A row filter is sound **iff the view carries every column it names**. If the column was aggregated
> away, the rows already mix values this principal may and may not see, and nothing applied
> afterwards separates them.

When it cannot, the read is **refused** (`PRV-7003`) with a message naming the fix: a view registered
with the filter applied *before* aggregating, which is a different query with its own state. Forking
state per principal is available and explicit; it is not automatic, because a per-user filter would
otherwise multiply engine state by the number of users and you would learn that from a memory alarm.

## Metadata is data

`getFlightInfo` is authorized as strictly as fetching rows. A schema is the list of columns an
organisation keeps about its customers; the catalogue is a map of what a deployment does.

A denial for a view that exists and one for a view that does not **read identically**
(`AccessDecision.deniedWithoutDetail`), so a caller cannot enumerate a deployment by probing it.

## Telling failures apart

| | | Client should |
|---|---|---|
| `PRV-7001` | Not authenticated | Log in again |
| `PRV-7002` | Authenticated, not authorized | Ask for access |
| `PRV-7003` | Filter not enforceable on this view | Register a view that filters before aggregating |

Deliberately distinct: "log in again" and "ask for access" are different actions, and collapsing them
produces a support call where nobody can tell which happened.

The messages behind `7001` say only that the credential was not accepted. Never why — "expired" versus
"unknown" versus "wrong signature" is three bits of an oracle for whoever is working through guesses.

## Audit

Every decision is recorded, **allows as well as denials**. A log of refusals answers "who was stopped"
and not "who read the payroll view", which is the question that actually gets asked.

`AuditEvent` never carries the credential, and `Principal.toString()` never prints its claims — both
have tests, because a secret reaching a log line reaches everything that reads logs.

## Transport

TLS is the client default; `grpc://` plaintext has to be spelled out. Both SDKs refuse to send a token
over plaintext unless explicitly permitted.

mTLS between nodes is in the design (§25) and not implemented, because there are no nodes yet.

## What is not built

- **Column masking and per-column policy** — deliberately out of ADR-031 until a deployment asks
  (ADR-028: a feature earns its place)
- **OIDC / JWT verification out of the box** — `TokenVerifier` is the seam; no implementation ships
- **mTLS between nodes**, certificate rotation — Wave 8, with clustering
- **Secret management integration** (`SecretProvider` SPI in the design) — not built
- **Security review and SBOM** — Wave 10
