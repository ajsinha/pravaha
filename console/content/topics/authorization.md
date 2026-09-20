---
title: Authorization — what a principal may do
slug: authorization
category: security
order: 20
icon: key
summary: "Four questions a policy answers — may this principal read a view, register a query, administer one, read the audit trail — the two policies that ship, and how every surface asks the same one."
badge: SECURITY
audience: Operators
keywords: [authorization, policy, permissive, authenticated, SecurityPolicy, mayRead, mayRegisterQuery, mayAdminister, mayReadAudit, audit-readers, permissions, PRV-7002, "/api/v1/me/permissions"]
guide: security#authorization-is-enforced-here-not-in-the-store
related: [authentication, row-filters, audit, sharing, query-lifecycle]
---

Once [authentication](/help/topics/authentication) has named the caller, a **policy** decides what
they may do. Pravaha enforces it itself, in the engine, on every surface — Flight, the HTTP API, the
PostgreSQL gateway, the embedded engine — and never delegates it to the stores the data came from.
That is structural, not a preference:

- **A view is derived data the store has never seen.** No record in Aerospike carries the
  permissions of "ann's gold-tier total for the window ending 12:05".
- **A source is read once and shared by every query on it**, wherever it can be. Enforcing per
  principal at the source would mean reading it once per principal — the amplification the engine exists to remove --
  or reading it as a superuser, which enforces nothing.
- **A continuous query has no caller.** It runs for months with nobody connected; there is no session
  to push down.

Your stores' own controls still matter — Pravaha's connection to each should be least-privileged --
but they protect the connection, not the query.

## The five questions

A `SecurityPolicy` answers five questions. Each answer is *allow*, *allow with a row filter*, or
*deny with a reason*.

| Question | Asked when | Default if a policy does not say |
|---|---|---|
| `mayRead(principal, view)` | every read, every subscribe, `getFlightInfo` and schema lookups, every listing entry — and, at registration, **every stream the query reads** | (must be implemented) |
| `mayRegisterQuery(principal)` | `register`, `CREATE CONTINUOUS QUERY` | anonymous callers refused; any verified principal allowed |
| `mayAdminister(principal, view)` | `drop`, `pause`, `resume` (and their SQL statements); redeclaring a stream over HTTP | allowed exactly when `mayRead` allows **without** a row filter |
| `mayWriteTo(principal, sink)` | a registration or a replacement that names a sink — `--sink`, `WITH (sink = ...)`, `WRITING TO` | allowed. A row filter is refused with PRV-7005 rather than ignored |
| `mayReadAudit(principal)` | `GET /api/v1/audit` | **denied** — a permission of its own, never derived from reading |

### Registering is a standing read

A registration is authorized twice: may this principal register at all, and **may they read every
stream the query names** — taken from the plan, not the SQL text, because the text can name a
stream the planner optimised away and omit one a view expanded into. Without the second check a
principal could register `SELECT * FROM payroll` under an innocent name and read it back. Each check
is audited as `register:source` against the stream.

### Naming a sink is a standing write

A registration that names a sink writes to a store outside Pravaha, under this node's credentials,
for as long as it runs. `mayWriteTo` is asked for it, after the source reads, and the decision is
audited as `register:sink` against the **sink's** name — which is what makes "who put this in that
table" a question the trail can answer.

It is not a disclosure check: a registrant can only write what the source checks let them read. It
is a placement check, and it allows by default, because a sink is a binding the operator put in
this node's own configuration. A deployment whose bindings are not all equally trusted overrides
it. A decision that allows the write *and* carries a row filter is refused with PRV-7005: a sink
takes the whole changelog or none of it, so the alternative to refusing is writing the excluded
rows anyway.

### Administering is not reading a slice

`mayAdminister` defaults to "you may administer what you may read in full". A principal who may read
only a row-filtered slice of a view is **refused** drop, pause and resume, because dropping or
pausing a view affects every other reader. A deployment that separates operators from readers more
strictly should override `mayAdminister` in its own policy.

## The policies that ship

`pravaha-server` is configured with one of two, by name:

| `pravaha.security.policy` | Read | Register | Drop / pause / resume | Read the audit trail |
|---|---|---|---|---|
| `permissive` (default) | everyone, everything | everyone | everyone | everyone |
| `authenticated` (also `authenticated-only`) | any verified principal, everything; anonymous refused | any verified principal | any verified principal | a verified principal holding a role in `pravaha.security.audit-readers` (default `[admin]`) |

Any other value is refused at startup. Neither shipped policy returns row filters or distinguishes
one view from another — that is what a policy of your own is for (below). And `permissive` with
`authentication: none` needs `pravaha.security.allow-anonymous: true` to start at all; see
[authentication](/help/topics/authentication).

```yaml
pravaha:
  security:
    authentication: token
    policy: authenticated
    audit: file
    audit-readers: [admin, security-officer]
    tokens:
      "9f3c1d7a-analyst-2b8e4f6a1c5d":
        id: ann
        roles: [analyst]
      "c41e8b02-ops-7d9a3f5e6b10":
        id: ops-console
        roles: [operator, admin]
```

With this node, `ann` may read and register but not read the audit trail; `ops-console` may do all
four. An empty `audit-readers` closes the trail to everybody over HTTP.

## Asking the node what you may do

`GET /api/v1/me/permissions` returns the policy's own answers for the caller — read-only; there is
no endpoint to change a grant, because grants live in your identity system, not in the engine.

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" https://pravaha.internal:8080/api/v1/me/permissions
```

```json
{
  "principal": "ann",
  "tenant": "public",
  "roles": ["analyst"],
  "anonymous": false,
  "policy": "authenticated",
  "register": {"allowed": true, "reason": "allowed"},
  "readAudit": {"allowed": false, "reason": "reading the audit trail needs one of the roles [admin, security-officer]"},
  "views": [
    {"name": "hourly_spend", "read": "full", "administer": {"allowed": true, "reason": "allowed"}}
  ],
  "streams": [
    {"name": "txn", "read": "full", "administer": {"allowed": true, "reason": "allowed"}}
  ]
}
```

(Illustrative values.) `read` is `full` or `filtered` — the filter itself is not repeated. Views come
from the caller's own listing, so a view hidden from them is absent rather than listed with a "no".
The Python SDK's `client.permissions()` returns the same document, and the console's **Admin →
Access** screen renders it for the console's own identity.

## What a denial looks like — and what it does not reveal

A refusal is PRV-7002 (over HTTP, a `403`), naming what was asked for and the policy's reason:

```text
PRV-7002  ann may not register 'payroll_copy' because it reads 'payroll', which they may not read: <the policy's reason>. A registration is a standing read of everything the query names, so it is refused here rather than at the first row.
```

**Metadata is data.** A schema is the list of columns an organisation keeps about its customers, so
`getFlightInfo`, the stream catalogue and the query listing are authorized as strictly as rows are:

- `subscribe`, `drop`, `pause` and `resume` **authorize before they resolve the name**. A denied
  principal gets the same PRV-7002 whether or not the view exists, so the two replies do not
  distinguish "forbidden" from "not there".
- A name nobody registered is PRV-8002, naming only the name asked for — never a list of what does
  exist.
- `GET /api/v1/queries/{name}`: a denied name is `403` whether or not it exists; an allowed name that
  is not registered — **or whose query reads a stream the caller may not read** — is `404`, the same
  way, so neither answer is an existence oracle.
- `GET /api/v1/streams` lists only the streams the caller may read; `GET /api/v1/sinks` only the
  sinks whose names they may read, never a binding's options.

The listing (`pravaha queries`, `SHOW CONTINUOUS QUERIES`, `GET /api/v1/queries`) hides a view the
caller may not read, and one whose query reads a stream they may not read. For a view they may read
only through a row filter, the row count is withheld and sent as `-1` — a view's true size is data
about rows they are not entitled to. The CLI prints it as `-`.

## Sharing respects entitlement

Two registrations with the same plan share one computation (see [sharing](/help/topics/sharing)) --
but the fingerprint folds in the **row filters** the registrant's policy imposed. Two principals with
the same entitlement share; two with different ones do not. See [row filters](/help/topics/row-filters).

## A policy of your own

The shipped policies are coarse by design. Per-view rules, tenants, row filters and separated
operator rights come from implementing `SecurityPolicy` against your own identity model, in Java,
where the Flight server (or your embedded engine) is built:

```java
SecurityPolicy policy = new SecurityPolicy() {
    @Override
    public AccessDecision mayRead(Principal who, String view) {
        if (view.equals("payroll") && !who.hasRole("hr")) {
            return AccessDecision.deny("payroll is readable by the hr role only");
        }
        if (who.hasRole("regional")) {
            return AccessDecision.allowWithRowFilter("region = 'EU'");
        }
        return AccessDecision.allow();
    }

    @Override
    public AccessDecision mayRegisterQuery(Principal who) {
        return who.hasRole("analyst") ? AccessDecision.allow()
                                      : AccessDecision.deny("registering needs the analyst role");
    }

    @Override
    public AccessDecision mayReadAudit(Principal who) {
        return who.hasRole("security") ? AccessDecision.allow()
                                       : AccessDecision.deny("the trail is for the security role");
    }
};
```

!!! warning "Implement the interface, not a lambda"
    `SecurityPolicy` is a functional interface on `mayRead`, so `(who, view) -> allow()` keeps the
    **defaults** for the other three: anonymous callers cannot register, and **nobody** may read the
    audit trail. That is the safe way for the surprise to go, and still a surprise. Write the class out
    when you mean something else.

The engine asks the policy on every call and caches nothing on its behalf: a revocation takes effect
on the next call. So a policy should be fast, and should do its own caching.

**One policy per deployment.** The server and the registry each hold the policy, and giving it to
only one is refused at startup — configured separately, registering would be judged by one set of
rules and reading by the other.

## Pitfalls

!!! warning "Pitfall: any registrant may name any sink"
    There is no per-sink authorization. A principal allowed to register may name any bound sink, and
    the registration's audit line does not record which. A sink is a destination other people read:
    bind only sinks every registrant may write to.

!!! warning "Pitfall: a row-filtered principal cannot subscribe"
    `subscribe` is the one path that refuses a principal whose decision carries a row filter — the
    change stream is the shared computation, and filtering it per principal at the tap is not the
    same as filtering a read. Reading the view works, and applies the filter. Switching to a policy
    that returns row filters silently removes subscriptions from every conditionally-entitled
    principal.

!!! note "`permissive` means everything"
    Under `permissive` every caller may read, register, drop and read the audit trail. It is for a
    single-tenant node behind a boundary that has already decided who gets in.

## Where next

- [Row filters](/help/topics/row-filters) — allow, but only these rows
- [Audit](/help/topics/audit) — every decision this page describes, recorded
- [Authentication](/help/topics/authentication) — where the principal comes from
