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
| Authorization | What may they read, register, administer, write to — and may they read the audit trail? | `SecurityPolicy`: allow / allow-with-row-filter / deny |
| Audit | Who asked what, and what were they told? | `AuditSink`: every decision, allows included |

Kept apart so a deployment can adopt an external identity provider without rewriting its rules, or
tighten its rules without touching authentication.

**Pravaha stores no passwords and runs no identity provider.** `TokenVerifier` is where you plug in
the one you already have. `StaticTokenVerifier` exists for tests and single-tenant installs and says
so in its name.

**The HTTP API decides with the same policy.** `pravaha-server`'s REST controllers authenticate a
bearer token through `BearerTokenFilter`, and since SX-3 authorize through `HttpAuthorizer`, which asks
the same `SecurityPolicy` and records into the same `AuditSink` as Flight: `GET /api/v1/streams` lists
only the streams the caller may read, `GET /api/v1/streams/{name}`, `/validate` and `/explain` refuse a
stream the caller may not read, and `POST /api/v1/streams` is an administrative act. The endpoints that
describe what is registered go further and call the **same code** as Flight's `pravaha.list`
(`QueryListing`, in `pravaha-registry`), so the two surfaces cannot disagree:

| Endpoint | Who sees what |
|---|---|
| `GET /api/v1/queries` | The Flight listing, exactly: hidden by name, hidden by what the query reads (SX-11), counts `-1` for a row-filtered principal (SX-18), every refusal audited (SX-8, verb `http.list`) |
| `GET /api/v1/queries/{name}`, `/{name}/plan`, `GET /api/v1/views/{name}` | A name the policy denies: `403` whether or not it exists. An allowed name that is not registered, **or whose query reads a stream the caller may not read**: `404`, identically — so neither answer is an existence oracle. Other names sharing the computation are listed only if the caller may know them. A row-filtered caller gets counts and failure text withheld |
| `GET /api/v1/sinks` | A sink appears to a caller the policy lets read its name; its writers are the queries the caller's own listing shows. **A binding's options are never read to build the answer** — they are where credentials live — and a sink failure's text (`PRV-8009`) has every configured option value that could be a credential struck out before it leaves |

| `GET /api/v1/plugins` | Every plugin the node can load, with its manifest and declared capabilities; its **bindings** (streams, lookup tables, sinks) only where the caller may read the binding's name. Options are never read — a binding is its kind and its name — and a manifest's settings are listed by name only |
| `GET /api/v1/audit` | Only a principal `mayReadAudit` allows — see [Audit](#reading-the-audit-trail-get-apiv1audit). Every attempt recorded as `http.audit.read` |
| `GET /api/v1/me/permissions` | The caller's own answers, over what their listing already shows them |

Proven in `pravaha-server`'s `RegistryEndpointsTest`, `HttpAuthorizationTest`, `AdminEndpointsTest`
and `AdminHttpTest`.

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

### Two token-table keys YAML will not hand over as you typed them

The key under `pravaha.security.tokens` **is the bearer credential**, so anything that happens to it
between the file and the map changes who can authenticate. Two shapes are refused at startup
(`PRV-7004`) rather than repaired, because repairing either one silently changes that (SX-14):

- **A bare `yes:`, `on:`, `y:` or their negatives.** YAML 1.1 reads them as booleans, so the key
  binds as the word `true` or `false` and no client can present it — a node that starts, reports
  that it authenticates, and authenticates nobody. Quote the key to bind it verbatim:
  `"[yes]": {id: ...}`. **Two of them in one table** — `yes:` and `on:` — collapse to one key and
  fail the whole file's load with a duplicate-key error naming neither line; nothing in Pravaha can
  catch that, because the file never loads. Quote them.
- **Leading or trailing whitespace.** Spring discards it while binding, so `" tok "` and `"tok"`
  are one entry and one of the two credentials you configured is gone. Whitespace *inside* a
  credential is fine and is left alone.

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

**A filter that plans to no `FilterOperator` at all is refused too, the same way (`PRV-7003`).**
`withRowFilter` plans the filter predicate and looks for the `FilterOperator` to inject above the
scan. When Calcite's optimizer folds the predicate to a constant — `TRUE`, `1 = 1`, a column compared
to itself — there is none, and the read used to proceed **completely unrestricted**, recorded in the
audit as "allowed with a row filter" (SX-15; confirmed live: a principal entitled to two of four rows,
his filter set to `TRUE`, received all four). It now fails closed: the read is refused with a
message saying the filter left no predicate in the plan, and that a principal who may read the whole
view should be given an unrestricted `allow()` rather than a filter that restricts nothing
(`ViewQueryAuthorizationTest`). A policy author whose filter is built from a claim that can be empty
or absent for some tenant therefore sees refusals for that tenant, not silent over-service.

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

**What `LIST` says about a view you may only partly read (SX-18).** A principal whose access is
conditional on a row filter still sees the view — hiding it would be wrong, since they may
legitimately read part of it — but its `ROWS IN` count is **withheld**, sent as `-1`. A view's true
cardinality is data about rows the caller is not entitled to: a filtered principal was being told
`sales_view` holds 4 rows while their own read of it returns 2. `-1` rather than an empty field or
`0`, deliberately: the field stays a decimal long that both SDKs already parse, no counter can ever
equal it, and an empty field is turned into `0` by both — a lie rather than a refusal. The CLI prints
it as `-` with a line saying why.

**`LIST` is audited (SX-8).** Every per-view decision the listing makes is recorded, allows as well
as refusals, under the action `list`. A view hidden because of what it *reads* is recorded against
the stream that hid it, with the view named in the detail — the same noun the read path audits
provenance under, so "who tried to reach payroll" stays one grep over one field. Until this, a
principal could probe a node's whole catalogue and be refused every view in it without leaving a
trace.

**A prepared statement authorizes on every leg (SX-10).** `doPut` — the call that binds parameter
values to a handle — re-authorizes through the same path `getFlightInfo` and the fetch use. It
previously checked nothing: no rows escaped, because the fetch refuses, but a principal who may not
read a statement's view could bind values into another principal's handle and be told it was
accepted. A handle is a plan, never a permission.

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

**One sink, both transports.** `pravaha.security.audit` takes `none`, `memory` or `file`, and the HTTP
surface records into the *same object* the engine and Flight use. Until CFG-5 it did not: the Spring
bean behind `HttpAuthorizer` returned `AuditSink.NONE` unconditionally, so a node set to `memory`
recorded every Flight read and no HTTP read, no HTTP stream declaration and no HTTP refusal, and
nothing at startup said so. `AuditSinkSharingTest` asserts object identity rather than matching
configuration, because resolving the key twice would give the HTTP surface a second in-memory sink
nothing can reach — invisible in exactly the same way, while looking correct.

**What `memory` is, and is not.** It holds recent events in this process and nowhere else: they are
readable over `GET /api/v1/audit` (below) until the process ends, and then they are gone. Do not
deploy `memory` believing it produces a retained audit trail: a node set to it says so at startup (a
`WARN` naming what the setting does not do), and `audit: file` is the setting that leaves a record.

**`audit: file` is the readable trail (CFG-23).** One JSON object per line, appended to
`pravaha.security.audit-file` (default `pravaha-audit.jsonl`), rotating at
`pravaha.security.audit-rotate-bytes` and keeping `pravaha.security.audit-keep` generations. Each
line carries the timestamp, the principal's id, tenant and roles, the action, the target, `ALLOW` or
`DENY`, the reason, and the SQL or filter as `detail`. The claims map is never written, for the same
reason `Principal.toString()` does not print it.

*The file is still the durable record.* The operating system decides who may read it, and it is
created `rw-------`. Set the permissions you want on the directory; Pravaha will not loosen the
file's. The read endpoint below does not replace it: it reads a bounded window of recent decisions,
and the file is where everything older is.

*What it costs and what it refuses.* Writing happens on one daemon thread behind a bounded queue, so
an audit sink can never fail the query it is auditing; a full queue drops and the next line written
is an `audit.dropped` marker with the count, because a gap nothing records is a trail that lies. A
path that cannot be written is `PRV-7004` at startup rather than a discovery at the first decision
nobody sees. A durable sink of your own is still an `AuditSink` implementation you supply.

### Reading the audit trail: `GET /api/v1/audit`

`GET /api/v1/audit?since=&until=&principal=&view=&action=&decision=&limit=&cursor=` returns recorded
decisions **newest first**, one page at a time: each with its sequence number, time, principal (id,
tenant, roles — never claims), action, target, `ALLOW`/`DENY`, reason and detail (the SQL). `since`
and `until` are ISO-8601 instants (inclusive, exclusive); `view` matches the target ignoring case;
`decision` is `allow` or `deny`; `limit` is at most 500 (100 by default); `cursor` is the previous
page's `nextCursor`. A malformed parameter is `PRV-1051` naming it, never a filter silently dropped.
The Python SDK's `Client.audit(...)` calls it; the console's **Admin · Audit** screen is built on it.

**A permission of its own.** An endpoint listing who read what is a disclosure surface — it carries
every principal id and the SQL text that made SX-11 a breach rather than an inconvenience — so it is
authorized by a fourth question on the policy, `SecurityPolicy.mayReadAudit(principal)`, and never
by `mayRead` on some pseudo-view name, which would be a check applied to the wrong noun. **Being
allowed to read every view does not make the trail readable**: that is the property
`AdminEndpointsTest` pins first, and it was seed-proven by answering the question with `mayRead` and
watching the tests fail.

| Policy | Who may read the trail |
|---|---|
| a custom `SecurityPolicy` | **nobody**, unless it overrides `mayReadAudit` — the interface's default denies, and a policy written as a lambda keeps that default |
| `authenticated` | a verified principal holding a role in `pravaha.security.audit-readers` (default `[admin]`); an empty list closes it to everybody over HTTP |
| `permissive` | every caller. That policy already lets every caller read every view, register, and drop, pause or resume any query; there is no reader it could keep the trail from who is not already entitled to everything the trail describes. A deployment that wants the trail kept from its readers wants a different policy |

A refusal is `403 PRV-7002`, and no credential is `401 PRV-7001` before the policy is asked.

**Reading it is audited.** Every attempt — allowed or refused — is recorded as `http.audit.read`
before anything is read, with the filter as its detail, so "who looked at who read payroll" has an
answer too.

**Where it reads from.** Not the file read back. The node keeps a bounded ring of the most recent
decisions (`pravaha.security.audit-recent`, 10,000 by default) beside whatever `audit` records
durably, written on the same call. Reading the file back would mean parsing JSON Lines that are
written asynchronously (so the newest decisions are not in it yet), that rotate underneath the
reader, and that are for the operator's own tools — and a custom sink may not be a file at all. The
ring is O(1) to record, in step with the decisions, and the same whatever the durable sink is. What it
gives up is history across a restart, and the response says so: `capacity`, `retained`, `evicted`,
`oldestRetained`, and a `note`. With `audit: none` there is no ring either; the response is
`recording: false` rather than an empty trail that would read as "nobody asked for anything".

**Auditing still cannot fail a query.** The ring cannot throw, and a durable sink that throws is
counted and swallowed by the wrapper around it — an audit outage must not become a query outage, and
the decision stays readable in the ring meanwhile.

**Paging is by sequence, not offset.** Every decision gets a sequence number, increasing by one and
never reused; `nextCursor` is the sequence to continue below. New decisions arriving between two
pages do not shift the second one, which an offset into a moving list would.

### What a principal may do: `GET /api/v1/me/permissions`

The policy's own answers for the caller, read-only: `register` and `readAudit` (each allowed, or
refused with the policy's reason), and for every view and stream the caller could already see, how
they read it (`full`, or `filtered` — the predicate is not repeated) and whether they may administer
it. Views come from the caller's own listing (the `QueryListing` rule `GET /api/v1/queries` and
Flight's `pravaha.list` use), so a view hidden from them is absent rather than listed with a "no".
There is no endpoint to change a grant: the engine is not where grants live — `SecurityPolicy` is an
SPI a deployment implements against its own identity system, and the configured policies have none
to edit. The console's **Admin · Access** screen shows it.

## Transport

TLS is the client default; `grpc://` plaintext has to be spelled out. Both SDKs refuse to send a token
over plaintext unless explicitly permitted.

**True of the CLI too, since P-3.** `ClientOptions.Builder.build()` has always refused a token over a
plaintext endpoint — but `bin/pravaha`'s `ServerCommand.connect(args)` set `allowInsecureToken(true)`
unconditionally, on every server-talking command, on every invocation with `--token`, with no flag to
opt out and nothing printed. The SDK's refusal was real and switched off for every CLI user, and a
wire capture showed the literal token in the clear on `queries`, `query`, `register`, `pause`,
`resume`, `subscribe` and `drop` alike.

The escape hatch now has to be typed: `--insecure-token`. Without it a token over `grpc://` is
refused before any connection is attempted. With `grpc+tls://` nothing extra is needed, which is the
point — an escape hatch that everyone passes everywhere stops meaning anything.

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
- **Security review and SBOM** — Wave 11 (the GA wave, which moved down one when ADR-036 inserted the scale wave)

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

**A filtered read under a shared view's alias.** Two principals whose row filters are byte-identical
share one computation, and the shared view carries the first registration's name; the second
principal reads it under an alias of their own. A filtered read under that alias used to throw
`PRV-7003` while the identical entitlement under the primary name returned its rows (SX-13). It
failed closed, so nobody saw data they should not have — what it broke is the promise this document
makes that sharing is invisible to the reader. Fixed.

**Row filters are part of the fingerprint.** Sharing is by canonical fingerprint (ADR-025), and the
fingerprint now folds in the principal's row filters, sorted. Two principals with the same
entitlement share one computation — which is the claim the product makes. Two with *different*
entitlements do not, and previously did: the same fingerprint, one computation, one copy of the
state, with the read path the only thing between a restricted principal and everything.

**One policy per deployment.** The server and the registry each hold a `SecurityPolicy`, and passing
it to only one is refused at startup. Configured separately, registering was judged by one set of
rules and reading by the other — silently, in the direction of whichever was more permissive.

## What a registration is allowed to write

A registration that names a sink is a **standing write** to a store outside Pravaha, under the
credentials this node holds for it, for as long as the query runs. The engine asks
`SecurityPolicy.mayWriteTo(principal, sink)` for it — after the source reads, because those decide
what the rows *are* and this one decides where they come to rest — and records the decision as an
audit event whose target is the sink's own name (`register:sink`, or `replace:sink` for a
blue/green replacement, which is authorized by the same rules and inherits the name's sink).

**It is not a disclosure check.** A registrant can only write what the source checks above let them
read. It is a *placement* check: a sink is read by people who never talk to Pravaha, and "who put
this in that table" should be answerable from the trail. Before SINK-3 the register event recorded
the SQL and never the destination.

**The default allows**, because a sink is a binding an operator wrote into this node's own
configuration, and binding it is already most of the way to saying it may be written to. What the
default buys is the question being asked: a deployment whose bindings are not all equally trusted
overrides `mayWriteTo` and says so, where this page could previously only advise binding sinks
every registrant may write to. As with every other verb on the interface, a policy written as a
lambda keeps the default.

**A row filter is refused here rather than ignored** (`PRV-7005`). A sink receives the query's whole
changelog or none of it, so a decision that allowed the write and carried a filter would have had
the excluded rows written anyway. It is refused at registration, before the sink is opened, and it
is not `PRV-7002`: nothing was denied, so it is the policy that has to change.

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
