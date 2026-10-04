# Security

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Who is asking, what they may see, and what is written down about it.

The decision behind all of this is [ADR-031](../design/adr/031-authorization-at-the-pravaha-layer.md).

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
        .start("0.0.0.0", 19090);
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

## Tenants: what they own, what they may hold, and whom they share with

A principal's tenant (`Principal.tenant()`, `public` when the token does not name one) owns the
names it registers, the computations behind those names, and the view keys those computations
hold. It does not scope reads, sources or sinks, which the policy decides, and it does not scope
lanes, which every tenant shares
([ADR-050](../design/adr/050-a-tenant-owns-names-and-state-and-shares-only-with-itself.md)).

- **A view name is unique within its tenant, and every name is resolved in the caller's tenant**
  ([ADR-060](../design/adr/060-view-names-are-unique-per-tenant.md)). Two tenants may each register `orders`,
  and each reads, subscribes to, lists, describes, drops and builds on its own. A name another tenant
  holds is, to the caller, a name nothing holds -- registering it succeeds, and every lookup of it
  (Flight, pgwire, REST, SQL statements, subscriptions, dead letters, debug sessions, replacements,
  `GetTables`, pgwire's catalogue) answers exactly as for a name nobody holds. Before this, a
  registration choosing another tenant's name was refused with `PRV-8001` (TEN-1), which told the
  caller the name existed.
- **Inside the engine** a view outside the default tenant is known by its catalogue name,
  `tenant.default.name`; a default-tenant view by its name, so a one-tenant node is unchanged. The
  policy, the catalogue, the audit trail and the metrics see that engine name.
- **Only an admin reaches another tenant's view**, by its catalogue name (`acme.default.orders`, quoted
  in a SQL `FROM` clause). Anyone else naming another tenant is refused `PRV-7002` in the same words
  whether the view exists or not, and another tenant's view is administered by nobody outside it but
  an admin.

- **Sharing stays inside a tenant.** The tenant is part of the fingerprint, so identical SQL from
  two tenants is two computations. Within one tenant it is still one computation. Before this
  change, one tenant's `pause` could stop another tenant's view, and one tenant's quota could pay
  for another tenant's query.
- **Quotas are refused by name at registration.** `pravaha.tenancy` sets `max-queries` and
  `max-state-keys`, as defaults and per tenant. A registration over either limit is `PRV-8020` or
  `PRV-8021` (HTTP `409`). It is refused before a sink is opened, audited as `register:quota`
  `DENY`, and counted. A running query is never stopped by a quota.
- **A replacement stays in its tenant.** Only a principal of the tenant that registered a name can
  replace it (`PRV-8022`, HTTP `403`, audited as `replace:tenant`; the refusal does not name the
  tenant that holds it, the audit record does). `drop`, `pause` and `resume`
  are decided by ownership, as the next section describes.
- **Who sees the use.** `GET /api/v1/tenants` shows every tenant to a principal allowed to read the
  audit trail. Any other principal sees only their own tenant.

## Drop, pause, resume and replace are authorized by ownership, not by reading

A registered view is administered — dropped, paused, resumed, replaced, debugged, or fed a replayed
dead letter — by:

1. **its owner**: the principal who registered it, or who replaced it last (the new version runs on
   their authority). The same id in the same tenant; anonymous callers are one principal, so on an
   open node an anonymous registration stays administrable by anonymous callers;
2. **a principal the policy grants it to**: with the catalogue on, `MODIFY` or `MANAGE` on the view;
   without it, a `SecurityPolicy` that implements `mayAdminister` itself is taken at its word. A
   policy that inherits the interface's default (`permissive`, `authenticated`) grants nothing
   beyond ownership;
3. **the `admin` role.**

Anyone else is refused `PRV-7002` (HTTP `403`), audited as the verb with `DENY`. Reading a view,
unfiltered or not, is no longer a claim on it: before this, any principal entitled to read every row
of a view could drop it out from under every other reader (SX-2, SX-6, LIFE-040). The refusal says
what administering takes and does not say whose the view is.

**The owner is recorded where it always was.** The registry journal has recorded the registrant of
every registration since it was written, and recovery registers each view again as that principal —
so the owner survives a restart, and a view registered before ownership was enforced comes back owned
by whoever registered it. There is no owner-less view to decide for. A name no view holds (a stream
declared over `POST /api/v1/streams`, or a name that is not registered) is decided by the policy's
`mayAdminister` as before, so a permitted caller still meets `PRV-8002` for a name that does not
exist. Each name of a shared computation has its own owner.

The owner is shown to anyone who may see the query: `owner` in `GET /api/v1/queries` and
`GET /api/v1/queries/{name}`, the last field of the Flight `pravaha.list` row, `owner` on the Python
and Java SDKs' registered-query records, `pravaha describe`, `pravaha queries --verbose`, and the
console's query page. `GET /api/v1/me/permissions` answers `administer` by the same rule.

**`pravaha.security.administer`** names the rule, and from 2.0 there is one:

| Value | Who may administer a registered view |
|---|---|
| `ownership` (the default, and the only value) | its owner, a principal the policy grants it to, or the `admin` role |
| `legacy-read` | **Removed in 2.0**, as 1.0.0 announced. Through 1.x it handed the decision to the policy's `mayAdminister` alone — by default anyone whose read carries no row filter. A node or embedded engine that still sets it **refuses to start** with `PRV-7004 … legacy-read was removed in 2.0; grant MODIFY/MANAGE or use the admin role` rather than quietly enforcing a stricter rule than its operators expect. Move those operators to grants (`GRANT MODIFY ON VIEW ...` or `MANAGE`) or the `admin` role, then remove the setting |

Anything else is refused at start with `PRV-7004` too. The embedded engine reads the same key from its
`Configuration`. With the catalogue on, `authority: import` imports `authenticated` without
`MODIFY` on the catalogue; a catalogue that imported it before this change keeps that grant — the
engine does not rewrite grants — and the node warns at every start until `REVOKE MODIFY ON CATALOG
FROM ROLE authenticated`. `permissive` is imported whole either way: it makes every caller a manager
of the catalogue, who may grant themselves `MODIFY`.

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
scan. When the planner folds the predicate away — `TRUE`; this section used to say `1 = 1` and a
column compared to itself as well, which the planner does not fold (TAUTOFILTER-1, below) — there is
none, and the read used to proceed **completely unrestricted**, recorded in the audit as "allowed with
a row filter" (SX-15; confirmed live: a principal entitled to two of four rows, his filter set to
`TRUE`, received all four). It now fails closed: the read is refused with a message saying the filter
is true for every row, and that a principal who may read the whole
view should be given an unrestricted `allow()` rather than a filter that restricts nothing
(`ViewQueryAuthorizationTest`). A policy author whose filter is built from a claim that can be empty
or absent for some tenant therefore sees refusals for that tenant, not silent over-service.

**A filter that restricts nothing is refused even when it survives planning (TAUTOFILTER-1).** The
check above caught only what the planner folded away, and it folds `TRUE` and almost nothing else:
`region = region`, `1 = 1 OR region = 'x'`, `x IS NULL OR x IS NOT NULL`, `NOT (a <> a)`, `a >= a` and
`lower(r) = lower(r)` reached the plan as predicates and were enforced as though they restricted
something. `FilterVacuity` now judges the compiled predicate — the one that runs, with three-valued
logic already settled — by turning it into a propositional formula (constants folded, an expression
compared with itself given its value, a `NOT NULL` column read as never null, a comparison and its
complement one variable) and deciding it exactly. The rules:

| The filter | Verdict | What happens |
|---|---|---|
| true for every row the object can carry — `1 = 1 OR …`, `x IS NULL OR x IS NOT NULL`, `k = k` on a `NOT NULL` column | vacuous | refused wherever it is applied (`PRV-7003`); at `ALTER … SET POLICY` for a policy that reads nothing about the session (`PRV-7038`) |
| drops only rows where a compared column is NULL — `region = region`, `b OR NOT b`, `r = 'x' OR r <> 'x'` on a nullable column | vacuous (nulls only) | refused the same way. Such a filter *does* drop rows — `region = region` is UNKNOWN for a NULL region — but only as a side effect of three-valued logic, and an administrator reading it believes it restricts by value. A filter that says `region IS NOT NULL` in so many words is a restriction and is accepted |
| false for every row — `a <> a`, `x = 'y' AND 1 = 0` | empty | refused when a session-free policy is bound (it keeps no row for anybody: a deny, which `REVOKE` says plainly); **enforced** when bound to a reader, where it can be the right answer for that reader (`is_member('eu') AND region = 'EU'` for a non-member) and fails closed |
| anything else | restricts | applied |

The analysis is **sound, not complete**: it never calls a filter vacuous that keeps some rows and not
others by their values (a property test evaluates thousands of random predicates over every row of a
small domain, NULLs and a NaN included), but it does not find every tautology, and a filter too large
to decide within its budget is assumed to restrict. An integer or decimal column compared with constants
is judged by the values the comparisons cover — `k < 5 OR k > 2` and `k <= 4 OR k >= 5` are vacuous,
`k < 4 OR k > 4` restricts, and over a nullable column they are "nulls only" (VACUITYGAP-1); a decimal constant at another scale is taken exactly, so `p <= 4.99 OR p >= 5` on a `DECIMAL(10, 2)` is vacuous and `p >= 4.995` means `p >= 5.00` (DECSCALE-1); comparisons
of a floating-point column, or of two expressions, are judged one by one and such a tautology is missed. Floating point is taken as IEEE
754: `d = d` is false for NaN, so over a `DOUBLE` it restricts and is accepted. A catalogue policy that
reads the session (`session_attribute`, `current_user()`, `is_member`) is judged when it is bound to a
reader, at each read — under the stand-in values `CREATE`'s check plans it with, its verdict describes
nobody. The rule is written down in ADR-031 and ADR-059.

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
See `docs/project/qa/logs/SECX.md` (SECX-028) and `docs/project/qa/FINDINGS.md`'s SX-1 and STRM-9.

**What `LIST` says about a view you may only partly read (SX-18).** A principal whose access is
conditional on a row filter still sees the view — hiding it would be wrong, since they may
legitimately read part of it — but its `ROWS IN` count is **withheld**, sent as `-1`. A view's true
cardinality is data about rows the caller is not entitled to: a filtered principal was being told
`sales_view` holds 4 rows while their own read of it returns 2. `-1` rather than an empty field or
`0`, deliberately: the field stays a decimal long that both SDKs already parse, no counter can ever
equal it, and an empty field is turned into `0` by both — a lie rather than a refusal. The CLI prints
it as `-` with a line saying why. The same holds under the catalogue (ADR-059): a reader narrowed by a
`CREATE ROW FILTER` that applies to them is withheld the count exactly as a policy filter withholds it
(LISTCOUNT-1, where the catalogue's filters were not consulted and a reader cut to two rows of three
was told `3`).

**What `GetTables` lists.** Flight SQL `GetTables` lists the views of the caller's own tenant that they
may read, by the same rules as `LIST`: the view's own read right, and whatever may be read *through*
it. Under the catalogue a view's grant is enough — reading a view does not need `SELECT` on the
stream behind it (ADR-059 §2) — so an analyst granted `SELECT` on a view, or owning one, sees it in a
BI tool's table tree (GETTABLES-1, where every non-admin was listed nothing). Nothing of another
tenant is listed, except to an admin, by catalogue name (ADR-060).

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
With the catalogue off, there is no endpoint to change a grant: `SecurityPolicy` is an SPI a
deployment implements against its own identity system, and the configured policies have none to
edit. **With the catalogue on, grants live in the engine** and are changed with `GRANT` and `REVOKE`
— see the next section. The console's **Admin · Access** screen shows the answers either way.

## The Pravaha Catalog: grants kept in the engine (ADR-059)

`pravaha.catalog.enabled: true` makes the engine keep a catalogue — every governed object, its owner,
description, tags and version, and the grants on it — journalled append-only and fsync'd beside the
registry journal (`catalog.journal`, or `pravaha.catalog.journal`), replayed at start, and consulted by
a built-in policy, `CatalogPolicy`, at every enforcement point this document describes. Nothing about
*where* the checks are changes; only where the answers come from:

| The engine asks | The catalogue answers with |
|---|---|
| may this principal read a view or stream | `SELECT` on it |
| may it subscribe | `SUBSCRIBE` on the view — separate from `SELECT`, and re-asked every two seconds on an open Flight subscription, so a revocation ends the stream with `PRV-7002` |
| may it register | `CREATE` on its tenant's `default` namespace, and `BUILD_ON` on every input the query names |
| may it drop, pause, resume or replace | ownership of the view, `MODIFY` (or `MANAGE`) on it, or the `admin` role |
| may it write to a sink | `WRITE` on the sink |
| may it read the audit trail | the `admin` role, a `pravaha.security.audit-readers` role, or `MANAGE` on the whole catalogue |

Grants are allow-only, to roles and users, inherited from the catalogue, a tenant and a namespace
down to every object in it; an owner holds everything on what they own; the `admin` role holds
everything; a tenant is a wall (a grant inside `acme` reaches only `acme`'s principals). Registering
makes the registrant the owner. Reading a view needs `SELECT` on the view and **not** on the streams
behind it — under the catalogue the rule that stops a view laundering its sources is `BUILD_ON` at
registration, not a check of the sources at every read (the SX-11 check remains for any other
policy, through `SecurityPolicy.mayReadThrough`).

**Migration.** `pravaha.catalog.authority: import` (the default) imports `pravaha.security.policy`
once, as grants meaning what it meant, and records that it did; a later start with a different policy
configured refuses (`PRV-7034`) rather than run with two authorities. `authority: catalog` imports
nothing and ignores the policy setting. The catalogue is **off by default** because it changes who
decides, and every deployment written before it was configured against the policy.

Every change is an audit event (`catalog.grant`, `catalog.revoke`, `catalog.owner`, …), allowed or
refused. The statements, the REST endpoints (`/api/v1/catalog/...`), the CLI (`pravaha catalog`,
`pravaha grant`, `pravaha access why`) and the console's grants editor all go through one service
with one set of rules. Lineage, labels, contracts and sharing are later phases of ADR-059 and are
not built.

## Row filters and masks as catalogue objects (ADR-059 §4)

With the catalogue on, row filters and **column masks** are catalogue objects (kind `POLICY`) with an
owner, description, tags and version, journalled with the grants. A policy is defined once and bound
where it applies:

```sql
CREATE ROW FILTER sales.region_scope AS region = session_attribute('region') EXCEPT ROLE finance_admin;
CREATE MASK sales.card_last4 ON COLUMN card AS 'XXXX-' || RIGHT(card, 4) EXCEPT ROLE payments_ops;
ALTER STREAM orders SET POLICY sales.region_scope;     -- MANAGE on the object
ALTER TAG 'pii' SET POLICY sales.card_last4;           -- MANAGE on the tenant: every object tagged pii, now and later
SHOW POLICIES ON VIEW payments;
```

| Rule | As built |
|---|---|
| Who is narrowed | Every reader of the object except holders of an `EXCEPT ROLE` — not its owner, not `admin` |
| Several filters | AND together |
| Masks | One per column per reader; two are refused (`PRV-7040`). A mask keeps its column's type and reads only that column |
| Expressions | Columns, literals, operators, `CASE`, `CAST`, a fixed list of pure functions, `session_attribute('claim')`, `current_user()`, `is_member('role')`. Subqueries, non-deterministic and unlisted functions are refused (`PRV-7038`); a filter that restricts nothing — true for every row, or dropping only rows with a NULL in a compared column — is refused (`PRV-7003`), and one false for every row is refused when it reads nothing about the session (TAUTOFILTER-1) |
| Claims | Bound as SQL literals with their quotes doubled; a claim the reader lacks is refused (`PRV-7039`). Static tokens carry claims under `pravaha.security.tokens.<t>.claims`; users in the identity store carry their **attributes** as claims (below) |
| Tag bindings | Reach objects of the policy's own tenant only; a direct binding reaches every reader of the object |
| A masked column compared | Refused at plan time with `PRV-7006`: filter operand, group, join, sort or ranking key, aggregate argument, a view's key column, a subscription's tap filter, an alert's `WHERE` or a key of the view it follows — the alert at `CREATE ALERT`, to the person creating it (until MASKALERT-1 it was accepted `ACTIVE` and marked broken once it followed); a mask applied to an existing alert's column marks that alert broken with the code |
| A changed policy | Ends open subscriptions of the readers it affects with `PRV-7007`; an alert follows again under the new policy |

**Where they are enforced.** On a read (Flight statements and prepared statements — scans and point
reads alike — and the PostgreSQL gateway, text and binary), the view's rows pass the filter and then
the masks *before* the query's own plan sees them, so every operator of the query works on what the
reader is shown. On a subscription, the snapshot and every commit pass the same operators, each
change keeping its weight. On a registration, the filter and masks of each input are put into the
plan directly above that input's scan: the view computes over what its registrant may see, and every
query built on it carries that (a view does not launder its inputs). The narrowing is part of the
fingerprint, so two registrants narrowed differently never share a computation. An alert runs as its
owner and sees the view through the owner's policies. There is no REST path that serves a view's rows.

## What is not built

## Transport

TLS is the client default; `grpc://` plaintext has to be spelled out. Both SDKs refuse to send a token
over plaintext unless explicitly permitted.

**True of the CLI too, since P-3.** (This was the Java CLI. Its server-talking commands have since
moved to the Python CLI, `pravaha`; what is left in Java, `pravaha-engine`, talks to no server and
carries no token.) `ClientOptions.Builder.build()` has always refused a token over a
plaintext endpoint — but the Java CLI's `ServerCommand.connect(args)` set `allowInsecureToken(true)`
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

**Client certificates are not requested or verified by the node** (MTLSDOC-1). The SDKs and the CLI
can present one (`TlsOptions.clientCertificate`/`client_certificate`, `--tls-cert`), and the Flight
and PostgreSQL listeners never ask for it: a certificate from any CA, or none, is accepted alike, and
the bearer token is what authenticates. Mutual TLS, where a deployment requires it, is terminated in
front of the node.

**An expired certificate stops the node at start** (CERTEXP-1). Flight's and the PostgreSQL gateway's
certificates are checked for their dates as well as their pair: one that has expired or is not valid
yet is refused (`PRV-6104`, `PRV-6206`) naming the date, and one that expires within 30 days starts
with a `WARN`. Before, the node logged `over TLS` and every verifying client failed its handshake.

**A TLS-configured PostgreSQL gateway refuses plaintext** (PGTLSONLY-1): a client that does not send
`SSLRequest` is refused `FATAL 28000`, `PRV-6221`, before the token is asked for, unless
`pravaha.pgwire.tls.allow-plaintext` is `true`.

**An ephemeral-port node's reported address is unusable.** The `Location` handed to
`PravahaFlightSqlProducer` (`PravahaFlightServer.java:229-234`) is built from the *requested* host
and port, so a node started with `--pravaha.flight.port=0` advertises port `0` to `getFlightInfo`
callers and a client following the endpoint it was just handed dials a dead port. The scheme half of
`docs/project/qa/FINDINGS.md`'s SX-16 has since been fixed: that `Location` is `forGrpcTls` when a
certificate is configured and `forGrpcInsecure` otherwise, so the transport it reports is now
correct. There is no `PravahaFlightServer.location()` method — `location` is a private field; the
public accessors are `port()`, `uri()`, `catalog()` and `isEncrypted()`.

**Several TLS certificate/key misconfigurations are not caught at startup.** A cert and key that are
each individually valid but do not match each other lets the node start and report
`flight transport=TLS`; the mismatch surfaces only at the first client handshake. See
`docs/project/qa/FINDINGS.md`'s SX-17.

## What is not built

- **Column tags** — a tag-bound mask names a column and applies to that column of each tagged object;
  tagging columns themselves (and classification that follows lineage) is ADR-059 phase 3
- **OIDC / JWT verification out of the box** — `TokenVerifier` is the seam; no implementation ships
- **mTLS between nodes**, certificate rotation — deferred with multi-node execution
  ([ADR-034](../design/adr/034-distribution-deferred.md)). Wave 8 was survival on one node, not
  clustering ([ADR-035](../design/adr/035-wave-8-is-survival-not-distribution.md)), and a standby talks to
  a directory rather than to its primary, so there is no node-to-node channel to secure yet
- **Secret management integration** (`SecretProvider` SPI in the design) — not built
- **Security review and SBOM** — Wave 11 (the GA wave, which moved down one when ADR-036 inserted the scale wave)

## A conditional entitlement cannot subscribe

This section is about a `SecurityPolicy` that answers with `AccessDecision.allowWithRowFilter`. A
row filter kept in the catalogue (above) is enforced on a subscription, per change.

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

## Bounds on what a caller can make a node hold

An unauthenticated peer can reach the HTTP port and, when it is on, the PostgreSQL gateway. What it
can make the node allocate or hold before it has proved who it is is bounded, and so is what one
signed-in caller can hold (PGPREAUTH-1, HTTPBODY-1). Both used to be open: sixty silent pgwire sockets
each declaring a 16 MiB password, or thirty 19 MB anonymous sign-ins, ran a 1 GiB node out of heap.

| Door | Bound | Setting (default) | Past it |
|---|---|---|---|
| HTTP | A body on a path open without a credential (sign-in, reset, the API documentation) | `pravaha.http.max-anonymous-body` (16KB) | `413`, `PRV-1054`, on the declared length before a byte is read; a chunked body as soon as it passes |
| HTTP | Any other body — read only after the credential is verified | `pravaha.http.max-request-body` (4MB) | `413`, `PRV-1054` |
| HTTP | Sign-ins (each a slow password hash) at once | `pravaha.http.max-concurrent-sign-ins` (8) | `429`, `PRV-1055`, `Retry-After: 1` |
| HTTP | Connections in flight; the unread rest of a refused body | `server.tomcat.max-connections` (1024), `server.tomcat.max-swallow-size` (64KB) | queued, then refused by the kernel; the connection is closed |
| pgwire | Connections, signed in or not | `pravaha.pgwire.limits.max-connections` (100) | `FATAL 53300`, `PRV-6216`, at once and without a thread |
| pgwire | Connections still in their handshake | `pravaha.pgwire.limits.max-unauthenticated` (32) | the same |
| pgwire | The handshake, start to `AuthenticationOk`, as one deadline | `pravaha.pgwire.limits.authentication-timeout` (10s) | the socket is closed |
| pgwire | A message before sign-in | fixed, 16 KiB | `FATAL 54000`, `PRV-6217`, on the declared length |
| pgwire | A message after sign-in | `pravaha.pgwire.limits.max-message-size` (1MB) | the same |
| pgwire | One credential's connections | `pravaha.pgwire.limits.max-connections-per-principal` (0 = no share smaller than the whole) | `FATAL 53300`, `PRV-6216` |
| pgwire | An idle signed-in connection | `pravaha.pgwire.limits.idle-timeout` (0 = never) | `FATAL 57P05`, `PRV-6219` |

HTTP requests run on virtual threads; the engine's lanes, clock and pumps are platform threads of
their own and are never lent to a request. Flight authenticates each call from its headers before
the call is handled; its message-size bounds are gRPC's.

## Users, passwords, API keys and sessions (ADR-052)

The engine can keep its own users (`pravaha.identity.enabled`), the way MAYA does. Every credential
resolves to one principal through the same `TokenVerifier` seam, and the store keeps only derived forms
of secrets:

| Credential | Held as | Rules |
|---|---|---|
| A person's password | Argon2id (PBKDF2-SHA512 where Argon2 is unavailable) | at least 12 characters from 3 of 4 kinds, none of the last 5, 90-day maximum age; 5 failures in 15 minutes lock the account for 30 |
| A session (`prv_s_…`) | SHA-256 | 30 minutes idle, 12 hours in all, at most 3 per person; ended by sign-out, a password change or reset, or disabling the user |
| An API key (`prv_<env>_<keyid>_<secret>`) | the password KDF | shown once; roles a subset of its holder's; expires (90 days by default, at most 365); rotation keeps the old key for 7 days; revocation is immediate; a key from another environment is refused |
| A reset token | SHA-256 | single use, 60 minutes, issued by an administrator |

**Revocation reaches connections that are already open.** Revoking a key, ending or signing out a
session, or disabling a user takes effect on every door at the credential's next use: HTTP verifies
every request; Flight every call, and a running subscription re-verifies every two seconds — that the
credential still verifies, and as the same principal (FLIGHTPRINCIPAL-1) — and ends `UNAUTHENTICATED`; the PostgreSQL gateway verifies the credential again before every statement and
ends the connection `FATAL 28000` (`PRV-6218`) — PGREVOKE-1; it used to check once, at sign-in, and an
open BI connection kept reading after its key was revoked. A connection that sends nothing reads
nothing; `pravaha.pgwire.limits.idle-timeout` closes it as well, if that is wanted.

Sign-in and administration are REST calls under `/api/v1/auth`, `/users`, `/keys` and `/sessions`, and
the same from a shell: `pravaha login`, `pravaha user`, `pravaha key`, `pravaha session` and `pravaha password`.

**Attributes are a user's claims (STORECLAIMS-1).** An administrator records facts about a user —
`region=EU` — with `PUT /api/v1/users/{name}/attributes` (the whole set, like roles), `pravaha user
attrs ann region=EU [--unset K]` or the **Attributes** column of Admin · Users. They are journalled with
the user, audited as `user.attributes_changed` (names only, never values), and presented as claims by
every credential of the user's, read from the store at each request, so a change applies at the
user's next call: a session, an API key — which carries exactly its holder's attributes and none of its
own, so it can neither choose another value (which would widen what a filter reading it keeps) nor
drop one (which would only turn the holder's rows into a `PRV-7039` refusal) — and the principal a
registration is restored as after a restart, or an alert runs as. A policy reading
`session_attribute('region')` therefore applies to store users as it does to static tokens. The
names `via`, `session`, `key` and `mustChangePassword` are the engine's own claims and are refused;
at most 32 attributes, each value 1 to 256 characters with no control characters (`PRV-7020`).
Restoring a registration now asks the identity store for its owner before the token table; before,
a registration by a store user was refused at every restart.
The codes are `PRV-7010` to `PRV-7021` ([`../guides/TROUBLESHOOTING.md`](../guides/TROUBLESHOOTING.md)). Forcing a change
of password at first sign-in is configuration (`pravaha.identity.password.force-change`), off unless
set. Single sign-on and MFA are not built (the owner dropped them on 2026-09-27), so
`pravaha.identity.mode` takes one value, `password` (the default): `sso` or `hybrid` stops the node at
start with `PRV-7004`, naming the setting and the value it accepts, whether identity is on or off
(SSOMODE-1, 2.1). Until 2.1 both were accepted and ignored — the node signed people in with passwords
and said so only in a startup warning. `admin` is created on
first start, from `pravaha.identity.bootstrap-password-file` or with the published default, and a node
outside the dev profile refuses to start while the default is still its password (`PRV-7019`). Every
sign-in, refusal, lockout and key change is an audit event. Static tokens in `pravaha.security.tokens`
still work beside all this, logged as deprecated.

**The shipped profiles, and what each grants (PERMISSIVEUSERS-1).** `dev` sets
`pravaha.security.allow-anonymous: true` and lets `admin` keep its published password: alone it serves
every view to every caller under the default `permissive` policy — one developer on loopback, never
anywhere reachable. `users` (`--spring.profiles.active=dev,users`, the profile the guides run the
console with) turns on this store and token authentication **and sets `policy: authenticated`**: a
signed-in user reads views and registers queries; drop, pause, resume and replace go by ownership,
grants or the `admin` role; the audit trail is for `pravaha.security.audit-readers` (default
`[admin]`); `GET /api/v1/tenants` shows each user their own tenant. Until 2.0.1 it left `permissive` in
force, so every signed-in user could pause any view and read the trail. A home whose catalogue imported
`permissive` under that profile refuses the new one with `PRV-7034`: set `pravaha.security.policy:
permissive` to keep what was imported, or `pravaha.catalog.authority: catalog`.

**Failed sign-ins and lockout (LOCKENUM-1).** The policy, and why each half is what it is:

- *A lock is never announced to someone who has not signed in.* An unknown name, a wrong password, a
  disabled account and a barred sign-in all answer `401 PRV-7010` with the same message, after the
  same password-hash work. In 2.0.0 the sixth failure answered `423 PRV-7011 locked until …` for a
  real account and `401` for a name nobody holds, so six requests told anyone whether a user name
  existed, and the lock's fast refusal told them again by its timing. The right password is refused
  too while barred -- otherwise the lock would be a guessing oracle.
- *Failures bar the address they come from, not the account.* Five failures from one address within
  `lockout.window` (15 minutes) bar that address from that account for `lockout.duration` (30
  minutes); the person signing in from anywhere else is not affected. An account lock counted over
  every source let anyone who knew a user name -- `admin` included -- lock it for 30 minutes with five
  requests, and again every 30 minutes, indefinitely.
- *A bounded account lock stops guessing spread over many addresses.* Ten times as many failures (50)
  from any addresses within the window lock the account itself for `lockout.duration`, then it opens
  again. A barred address's further attempts are not counted, so one address cannot reach it alone.
- *Through a proxy, the person's address counts.* The console signs in for the browser and sends its
  address as `X-Forwarded-For`; the node believes that header only from
  `pravaha.identity.lockout.trusted-proxies` (addresses or CIDR blocks; the compose stack trusts
  `172.16.0.0/12`, Docker's range), so a caller cannot spread guesses over invented addresses.
  Without it, everyone signing in through one proxy is one address.
- What is barred is kept in memory (a restart forgets it; at most 10,000 address-and-account pairs);
  the account-wide lock is in the identity store. Both are audit events (`auth.lockout`, and
  `auth.login_locked` for each refused attempt), and an administrator sees `lockedUntil` on the user.
  A password reset clears it. What remains: an attacker with fifty addresses can lock an account for
  30 minutes at a time -- put sign-in behind a proxy that rate-limits by address if that matters.

**A user's queries after a restart.** A registration is journalled under its owner's id, and at start
the node replays it as that owner, asking the identity store first and the token table second
(RECOVERYOWNER-1; it used to ask only the token table, so every query a store user had registered was
refused `PRV-8007` at the next restart). The principal is the one the user signs in as today — their
tenant and current roles — and the policy re-checks the registration against it, so a revoked
entitlement still refuses the replay. **Disabling a user does not stop their queries**, before or
after a restart: it ends their sessions and keys, and the queries keep running under the account,
with a warning at each start naming the owner. Drop them, or give them another owner, to stop them.
An owner neither the store nor the token table knows is refused `PRV-8007`.

## The console acts as the person signed in

The console holds **no credential of its own**. A person signs in with their user name and password.
The console passes them to the engine's `POST /api/v1/auth/login` and keeps only the session token it
gets back — in the console process, under an opaque id; the browser's cookie carries the id and never
the token (COOKIETOKEN-1), so a copied cookie is not a credential for Flight, HTTP or the PostgreSQL
gateway. It sends that token on every call, over REST and Flight, so the engine
authorizes and audits each action as that person, with their roles. The engine is the only place a
credential is checked. Role checks in the console only decide what it shows; the engine decides what
anyone may do.

| Surface | Signed in? |
|---|---|
| Landing, about, help, tutorials, health probes | No — an operator needs the console to load during an incident |
| Everything that reads or changes the engine | **Yes**, as the person; a session the engine ends (expiry, revocation) sends them to sign in again; a cookie whose session was signed out — a copy presented after sign-out — reads as signed out and goes to the landing page (LOGOUTREPLAY-1) |
| Account: password, API keys, sessions | **Yes**, their own |
| Admin: users, keys, sessions, access, audit | **Yes**, and the engine refuses anyone without `admin` |

Every form and state-changing request carries a per-session CSRF token. The console's session cookie
is HttpOnly and SameSite=Lax, and Secure when served over https (`console.secure_cookies` for a proxy
that terminates TLS). It is signed with `console.session_secret` and holds no credential: the engine
session token, and a key or reset secret the engine has just issued (shown once), stay in the console's
memory under the cookie's opaque id. A console restart therefore signs everyone out of the console
(their engine sessions expire on their own), and several console instances behind one address need
sticky sessions.

Every console response carries security headers (CONSOLEHDR-1): a Content-Security-Policy (scripts
from the console, or inline with the response's nonce; `frame-ancestors 'none'`; `object-src 'none'`;
forms post only to the console), `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`,
`Referrer-Policy: same-origin`, a `Permissions-Policy`, and HSTS over https. The console signs in for
the browser and sends the browser's address as `X-Forwarded-For`, which the engine believes only from
`pravaha.identity.lockout.trusted-proxies` (above).
