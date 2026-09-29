# ADR-031: Authorization is enforced at the Pravaha layer, not delegated to the store

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **row filters are programmatic-only** — the soundness rule and the plan injection are built and tested, and no server configuration can supply a policy that returns a row filter |
| Date | 2026-09-10 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-025 (security predicates in the sharing fingerprint), ADR-030 (Flight SQL as the client protocol), §25 |

## Decision

**Authentication and authorization are enforced by Pravaha, on every read, at the layer that
produces the row.** They are not pushed down to the source store, and not left to the caller.

Three pieces, and the boundaries between them are the point:

| Piece | Answers | SPI |
|---|---|---|
| Authentication | Who is this? | `TokenVerifier`: credential in, `Principal` out |
| Authorization | What may they read? | `SecurityPolicy`: `(Principal, view)` in, `AccessDecision` out |
| Audit | Who asked what, and what were they told? | `AuditSink`: every decision, allow and deny alike |

A `Principal` carries an id, a tenant, roles and claims. A decision is `allow`, `allow with a row
filter`, or `deny`. Enforcement lives in `ViewQuery` -- the query path itself -- rather than in the
Flight transport, so a second transport cannot arrive without it.

## Why it cannot be delegated to the store

This was the question asked directly, and the answer is not "defence in depth". It is that the
store is structurally unable to answer it.

**A view is derived data the store has never seen.** `SELECT user_id, SUM(amount) ... GROUP BY
user_id, TUMBLE(...)` produces rows that exist only inside Pravaha. There is no record in Aerospike
whose permissions correspond to "u4's gold-tier total for the window ending at 12:05". Asking the
store to authorize it is asking it about a row it does not have.

**A change feed is read once and shared by many queries.** ADR-027 has one lane multiplexing many
query pipelines over one ingest. If authorization happened at the source, the feed would have to be
read once per principal -- which is the read amplification the architecture exists to avoid -- or
read once as a superuser, which is the same as not enforcing anything.

**The store may have nothing to enforce with.** Aerospike Community Edition has no row-level
security at all, and ADR-029 already ships against it. A design that only works on stores with a
particular access-control model is a design that does not work.

**Continuous queries have no caller to attribute.** A registered query runs for months, refreshing
state while nobody is connected. There is no session to carry down to the source, and inventing a
service account is exactly the "read everything, filter later" posture this decision rejects.

The store's own controls still matter -- Pravaha's connection to it should be least-privileged --
but they protect the connection, not the query.

## The soundness rule

A row filter can be applied at read time **iff every column it names is present in the view.**
Otherwise the read is refused with `PRV-7003`.

This is not pedantry about missing columns. If a filter says `region = 'emea'` and the view
aggregated `region` away, then each row of that view already *mixes* the regions: the sum this
principal is looking at was computed from rows they may not see. No filter applied afterwards can
separate them, and serving the row leaks precisely what the policy exists to prevent. The three
outcomes, in order of preference:

1. **Every filter column is in the view** -- inject the filter into the plan and serve. Cheap; one
   view serves every principal.
2. **A column is missing** -- refuse, and say what would fix it: a view registered with the filter
   applied *before* the aggregate. That is a different continuous query with its own state, and
   ADR-025's fingerprint already makes it a different query by construction.
3. **Neither** -- refuse. A contaminated aggregate is worse than an error, because an error stops
   and a wrong number gets acted upon.

Automatically forking state per principal was considered and rejected as a default: a policy with a
per-user filter would silently multiply the engine's state by the number of users, and the operator
would learn this from a memory alarm. Registration-time filtering stays available and explicit.

### A filter must restrict something (amended 2026-09-28, TAUTOFILTER-1)

A filter that is present but restricts nothing is a false record of enforcement: the audit says
"allowed with a row filter" and every row is served. The first form of this rule refused only a
filter the planner folded away (SX-15: `TRUE`), and the planner folds almost nothing else, so
`region = region` and `1 = 1 OR region = 'x'` were served as restrictions. The rule is now decided
over the compiled predicate, the one that runs, by `FilterVacuity`:

- **Vacuous** -- true for every row the object can carry, a `NOT NULL` column read as never null --
  is refused with `PRV-7003`.
- **Nulls only** -- true for every row whose compared columns are present, while stating no null test
  of its own. `region = region` on a nullable column is UNKNOWN, so dropped, exactly where `region` is
  NULL: it does restrict, but only as a side effect of three-valued logic, and whoever reads it believes
  it restricts by value. Refused with `PRV-7003` too. The line is drawn at what the filter *states*: a
  filter written `region IS NOT NULL` restricts by its own words and is accepted.
- **Empty** -- false for every row. Not a leak; it fails closed. Enforced when bound to a principal,
  because there it can be that principal's right answer; refused only where it is the same for
  everybody (ADR-059 §4, a session-free policy at binding), because then it is a deny by another name.

The analysis is sound and deliberately incomplete: it refuses what it shows to restrict nothing and
assumes the rest restricts, because refusing a genuine filter stops a reader entitled to rows, and a
filter too large to decide within a fixed budget is assumed to restrict. Floating point follows IEEE
754 (`d = d` is false for NaN, so it restricts).

## Where the filter goes

Into the **plan**, immediately above the scan and below any aggregate -- never concatenated into the
SQL text. Text concatenation is how a filter gets removed by a caller who understands operator
precedence better than whoever wrote the concatenation; `WHERE total > 0 OR total <= 0` is enough to
neutralise an appended `AND tier = 'gold'`. A predicate in the plan has no syntax for the caller to
reach. §25 already required this; this ADR is where it becomes mandatory rather than intended.

Below the aggregate, not above it, for the reason in the soundness rule: filtering the answer is not
filtering the input.

## Metadata is data

`getFlightInfo` is authorized as strictly as `getStream`. A schema is the list of columns an
organisation keeps about its customers, and the catalogue is a map of what a deployment does. A
denial for a view that exists and a denial for one that does not read identically
(`AccessDecision.deniedWithoutDetail`), so a caller cannot enumerate a deployment by probing it.

## Authentication on the wire

Flight middleware, not Flight's `CallHeaderAuthenticator`. An authenticator hands the call an opaque
`peerIdentity` string, and a string is not enough to authorize with -- the policy wants the tenant,
the roles, and the claims. Recovering those means a server-side session table keyed by the identity
string, which is state to size, evict and get wrong. Middleware carries the whole principal on the
call, and Flight builds it per call, so there is nothing to evict and nothing to leak between
callers.

Credentials travel as `authorization: Bearer <token>`. Both SDKs refuse to send one over a plaintext
connection unless explicitly told to (`allowInsecureToken` / `allow_insecure_token`), which exists
for loopback tests and sidecar-terminated TLS and is named so that nobody enables it by accident.

Pravaha does not store passwords and does not run an identity provider. `TokenVerifier` is the seam
where a deployment plugs in the one it already has; `StaticTokenVerifier` is for tests and for
single-tenant installs, and says so in its name.

## Error codes

| Code | Means | The caller should |
|---|---|---|
| `PRV-7001` | Not authenticated | Present a credential, or a fresh one |
| `PRV-7002` | Authenticated, not authorized | Ask for access; a new credential will not help |
| `PRV-7003` | Filter not enforceable on this view | Register a view that applies the filter before aggregating |

Telling 7001 and 7002 apart is deliberate. "Log in again" and "ask for access" are different
actions, and collapsing them produces a support call in which nobody can tell which happened. The
messages behind them say nothing more: `TokenVerifier` must not distinguish "expired" from "unknown"
from "wrong signature", because that is three bits of an oracle for whoever is working through
guesses.

## Consequences

Every read costs one policy call. `SecurityPolicy` is on the path of every query, so an
implementation that talks to a remote service on each call will be felt; caching is the
implementation's business, and the SPI is narrow enough to make it easy.

A server started without `authenticatedBy` accepts every call as `Principal.ANONYMOUS`. That is
correct for an engine embedded in a process that has already authenticated its caller, and wrong for
anything listening on a network somebody else can reach. It is two explicit states rather than a
default that quietly downgrades.

Column masking and per-column policy are not in this decision. Row filters and view-level access
are; masking arrives when there is a deployment asking for it, following ADR-028's rule that a
feature earns its place.
