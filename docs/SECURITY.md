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

**These three seams apply to the Flight surface only.** `pravaha-server`'s HTTP REST controllers
(`GET`/`POST /api/v1/streams`, `/api/v1/queries/validate`, `/explain`, `/api/v1/status`) authenticate
a bearer token through the same `BearerTokenFilter` — a request with no token, or an invalid one, is
correctly rejected — but once authenticated, **no controller behind that filter consults
`SecurityPolicy` or `AuditSink` at all.** Confirmed live: a principal denied every payroll-named view
on Flight receives the identical, unfiltered `payroll` schema and unfiltered query plans over HTTP,
and `POST /api/v1/streams` accepts a new stream declaration from *any* authenticated caller with no
policy check whatsoever — the write does not reach the query engine (see "What is not built" isn't
the reason; this is a distinct gap), but the schema itself is published and visible to every other
caller regardless of what that caller may register or read on Flight. Do not rely on `SecurityPolicy`
rules to govern the HTTP surface: today, "has a valid token" is the entire HTTP authorization model.
See `docs/qa/FINDINGS.md`'s SX-3.

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

## Drop, pause and resume are authorized as reads, not as ownership

`mayAdminister` — the check behind `drop`, `pause` and `resume` — **defaults to `mayRead`**. The
owning principal is recorded at registration and journalled, but nothing in the drop/pause/resume
path consults it (§25 records the intent; the code does not implement it). The practical consequence,
confirmed live in the SECX round: **any principal entitled to read any rows of a view may destroy or
freeze it for every other reader**, even a principal entitled to only a filtered slice of it, and even
one denied the view under the name it is registered against but who reaches it under a different,
innocuous name that computation happens to share. A row-filtered principal who may read only the
`region = 'EU'` rows of a view may `drop` the whole view out from under every other reader, or
`pause` it and freeze the row count everyone else sees, not only their own filtered view of it. A
deployment that needs drop/pause/resume gated on registration ownership, rather than read access,
must implement and wire its own `SecurityPolicy.mayAdminister` override — the shipped default does
not do this, and nothing here previously said so. See `docs/qa/FINDINGS.md`'s SX-2.

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

**A second, more severe soundness failure exists and is not yet fixed: a filter that plans to no
`FilterOperator` at all fails open.** `withRowFilter` plans the filter predicate and walks the tree
for the first `FilterOperator` to inject above the scan; when Calcite's own optimizer reduces the
predicate to a compile-time constant (confirmed for `TRUE` and `1 = 1`, and plausibly for any
equivalent tautology), there is no `FilterOperator` anywhere in the plan, and the read proceeds
**completely unrestricted, with no error**, recorded in the audit only as "allowed with a row
filter." Unlike the column-not-carried case above, there is no refusal and no message — a policy
author whose filter predicate happens to be tautological for a given principal (for example, a
filter built from a claim that is empty or absent for some tenant) gets silent, total over-service
for that principal, not a `PRV-7003`. There is no configuration or authoring guidance yet that avoids
this; it is a defect, not a documented limitation. See `docs/qa/FINDINGS.md`'s SX-15.

## Metadata is data

`getFlightInfo` is authorized as strictly as fetching rows. A schema is the list of columns an
organisation keeps about its customers; the catalogue is a map of what a deployment does.

**How a denial reads, and what it no longer tells you.** `subscribe`, `drop`, `pause` and `resume`
**authorize before they resolve the name**. A principal the policy denies gets `PRV-7002` naming only
the view they asked for, and gets exactly that whether or not the view exists — so the two replies no
longer distinguish "forbidden" from "not there". And the refusal for a name nobody registered is now
`PRV-8002  no query named 'x' is registered`, which names the one name asked for; it used to append
`this node has [...]` with every registered view on the node, unfiltered by any policy, so one denied
principal could read the whole catalogue out of a single misspelling (STRM-9, closing the disclosure
half of SX-1).

What is still true: `AccessDecision.deniedWithoutDetail()` exists in `pravaha-security` and is called
from **no production path** — it is API with tests and no caller. A policy that allows a principal
broadly can still probe which names exist, because for them the allow/refuse distinction is
legitimate; that is the residue, and under the default `permissive` policy it is everybody. A caller
entitled to know what exists should use `LIST`, which is filtered by the policy rather than refused.
See `docs/qa/logs/SECX.md` (SECX-028) and `docs/qa/FINDINGS.md`'s SX-1 and STRM-9.

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

**"Unless explicitly permitted" is true of the raw SDK in isolation, but not of the shipped CLI.**
`bin/pravaha`'s `ServerCommand.connect(args)` sets `allowInsecureToken(true)` unconditionally, on every
one of its server-talking commands, on every invocation with `--token` — there is no flag to opt out,
and nothing is printed. A token passed to any `pravaha` command over a plaintext `grpc://` URL is
sent in the clear silently, not merely "if you permit it." Confirmed on a wire capture: the literal
token appears in the clear on `queries`, `query`, `register`, `pause`, `resume`, `subscribe` and
`drop` alike (P-3, reconfirmed under SECX-062).

**This section describes Flight only.** The main HTTP API (`/api/v1/*`) carries the identical bearer
token and has no TLS story of its own in Pravaha's configuration — HTTPS on the HTTP surface is
reachable only through Spring Boot's own, separate `server.ssl.*` keys, undocumented anywhere in this
project (confirmed working when set by hand). A deployment that configures Flight TLS per this
section and stops there has not secured the HTTP transport carrying the same credential.

mTLS between nodes is in the design (§25) and not implemented, because there are no nodes yet.

**An ephemeral-port node's reported address is unusable.** The `Location` handed to
`PravahaFlightSqlProducer` (`PravahaFlightServer.java:229-234`) is built from the *requested* host
and port, so a node started with `--pravaha.flight.port=0` advertises port `0` to `getFlightInfo`
callers and a client following the endpoint it was just handed dials a dead port. The scheme half of
`docs/qa/FINDINGS.md`'s SX-16 has since been fixed: that `Location` is `forGrpcTls` when a
certificate is configured and `forGrpcInsecure` otherwise, so the transport it reports is now
correct. There is no `PravahaFlightServer.location()` method — `location` is a private field; the
public accessors are `port()`, `uri()`, `catalog()` and `isEncrypted()`.

**Several TLS certificate/key misconfigurations are not caught at startup.** A cert and key that are
each individually valid but do not match each other lets the node start and report
`flight transport=TLS`; the mismatch surfaces only at the first client handshake. See
`docs/qa/FINDINGS.md`'s SX-17.

## What is not built

- **Column masking and per-column policy** — deliberately out of ADR-031 until a deployment asks
  (ADR-028: a feature earns its place)
- **OIDC / JWT verification out of the box** — `TokenVerifier` is the seam; no implementation ships
- **mTLS between nodes**, certificate rotation — deferred with multi-node execution
  ([ADR-034](adr/034-distribution-deferred.md)). Wave 8 was survival on one node, not
  clustering ([ADR-035](adr/035-wave-8-is-survival-not-distribution.md)), and a standby talks to
  a directory rather than to its primary, so there is no node-to-node channel to secure yet
- **Secret management integration** (`SecretProvider` SPI in the design) — not built
- **Security review and SBOM** — Wave 10

## A conditional entitlement cannot subscribe

**A principal whose `AccessDecision` carries a row filter is refused on `subscribe`**, and is the one
path that refuses it. Reading the same view works and applies the filter; a subscription does not,
because the change stream is the shared computation and filtering it per principal at the tap is not
the same thing as filtering a read.

This is deliberate — it replaced a leak — but it is a migration hazard nobody was told about:
switching a deployment to a policy that returns row filters silently removes the ability to subscribe
from every conditionally-entitled principal. The remedy is to read the view rather than subscribe to
it, which does honour the filter. Recorded as STRM-13.

## What a registration is allowed to read

A registration is a **standing read** of every stream the query names, so it is authorized as one.
At registration the engine asks `mayRead` for each source stream in the plan — taken from the plan
rather than the SQL text, because the text can name a stream the planner optimised away and omit one
a view expanded into.

This closes a bypass. Registration previously asked only `mayRegisterQuery` — whether somebody may
register *anything* — and never whether they may read what the query names. A principal who could
register could name a stream they had no access to, give the view a name of their own choosing, and
read it back: the read check is against the *view's* name, and the policy was never told what the
view derives from. A careful policy author could not have refused it, because the engine gave them
nothing to refuse on.

**Row filters are part of the fingerprint.** Sharing is by canonical fingerprint (ADR-025), and the
fingerprint now folds in the principal's row filters, sorted. Two principals with the same
entitlement share one computation — which is the claim the product makes. Two with *different*
entitlements do not, and previously did: the same fingerprint, one computation, one copy of the
state, with the read path the only thing between a restricted principal and everything.

**One policy per deployment.** The server and the registry each hold a `SecurityPolicy`, and passing
it to only one is refused at startup. Configured separately, registering was judged by one set of
rules and reading by the other — silently, in the direction of whichever was more permissive.

## The console's own gate

The console authenticates with a **single shared secret** (`console.password`), held in a signed
session cookie. Unset by default, and unset means nobody can sign in.

**This is not the engine's identity model, and is not meant to become one.** The engine
authenticates its clients through the deployment's identity provider via `TokenVerifier`, and a
second, weaker account system beside it would be worse than an honest lock. What this gate does is
stop an unauthenticated visitor acting, and record who acted.

| Surface | Gated |
|---|---|
| Landing, about, help, tutorials, health probes | No — an operator needs the console to load during an incident |
| Overview, query list and detail | No — reading only |
| Register, pause, resume, drop | **Yes** |
| Ad-hoc query (`/workbench`, `/api/v1/query`) | **Yes** — it reaches the engine as this deployment's principal |
| `/api/v1` mutations | **Yes**, answering `401` rather than redirecting, so a `fetch` gets a status it can act on |

Every state-changing action is logged with the session's identity, which is the question — *who
dropped it* — that nothing could previously answer.

**What this does not do.** It is one identity, so it distinguishes signed-in from anonymous and
nothing finer; there are no roles and no per-user attribution beyond `operator`. A deployment
needing that should put the console behind its own SSO proxy.
