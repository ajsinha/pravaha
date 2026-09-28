# ADR-025: registration and subscription are separate

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — the fingerprint covers the plan, the security predicates and the key columns (`QueryFingerprint`, `SharingIdentityTest`), so two registrations differing only in their keys are two views |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

**Registration and subscription are separate objects with separate lifetimes.** Registering a query
creates a computation; subscribing attaches a consumer to one. Many subscriptions may share one
computation, and a computation may exist with none.

Two clients share a computation when their queries have the same **canonical fingerprint** — the
normalised plan **including the security predicates applied to it** — never when their SQL text
matches.

## Alternatives considered

**One query per connection, released on disconnect.** The draft's implicit model. Simple, and wrong
for the product: a dashboard that reconnects loses its warm state, and a query that must survive a
deploy has nowhere to live.

**Sharing by SQL text hash.** Cheap to compute and immediately appealing. Rejected outright: two
users may issue byte-identical SQL and be entitled to different rows, because row-level security
predicates are applied per principal. Text-hash sharing would serve one user's rows to another —
a data leak produced by a caching optimisation, which is the worst kind because it looks like
performance work.

## Rationale and consequences

**The fingerprint must include everything that changes the answer**, and security predicates change
the answer. Canonicalising the *plan after predicate injection* makes sharing safe by construction:
two principals with different entitlements produce different fingerprints and therefore different
computations, without anyone having to remember the rule.

**Lifetime becomes explicit rather than incidental.** A subscription may end without the computation
ending; a computation with a stated lifetime mode survives disconnects, with a grace period for the
common case of a client that reconnects in seconds. Nothing is released implicitly on a persistent
query — releasing warm state because a browser tab closed is a bug the user experiences as
"it's slow again every morning".

**Consequence:** ten analysts opening the same dashboard are one computation with ten taps. The
subscriber count grows and the *query* count does not, which is the mechanism that makes the
subscriber-scale numbers in §20.3b achievable and the query-density target in NFR-2d realistic.
