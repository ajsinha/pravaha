---
title: Security codes (PRV-7xxx)
slug: errors-security
category: errors
order: 80
icon: shield-exclamation
summary: "PRV-7001 to PRV-7004, told apart deliberately: not authenticated (present a credential), not authorized (ask for a grant), a row filter that cannot be enforced, and a security setting the node will not start with."
badge: PRV-7XXX
audience: Everyone
keywords: [unauthenticated, forbidden, unauthorized, 401, 403, token, bearer, credential, grant, row filter, policy, permissive, authenticated, allow-anonymous, audit, misconfigured]
guide: security
related: [authentication, authorization, row-filters, audit, errors-overview]
---

Four codes, and the reason there are four is the advice. Each one is answered by a different person
doing a different thing — presenting a credential, granting access, fixing a view, editing a file —
and a code that meant two of them would give the right advice to only one.

| Code | Name | Who fixes it | How |
|---|---|---|---|
| PRV-7001 | SECURITY_UNAUTHENTICATED | The caller | Present a credential, or a fresh one |
| PRV-7002 | SECURITY_FORBIDDEN | Whoever grants access | A grant; a new credential will not help |
| PRV-7003 | SECURITY_FILTER_NOT_ENFORCEABLE | Whoever owns the view or the policy | Make the filter's column part of the view |
| PRV-7004 | SECURITY_MISCONFIGURED | The operator | An edit to the node's security settings |

How each travels:

| Code | Flight status | PostgreSQL SQLSTATE | REST |
|---|---|---|---|
| PRV-7001 | `UNAUTHENTICATED` | `28000` | `401` from the bearer-token check |
| PRV-7002, PRV-7003 | `UNAUTHORIZED` | `42501` | `403` |
| PRV-7004 | — (the node does not start) | — | — |

## PRV-7001 — unauthenticated

No credential, or one that did not verify. **Present a credential, or a fresh one.**

The message says only that the credential was not accepted — never *why*. "Expired" versus "unknown"
versus "wrong signature" is three bits of an oracle for whoever is working through guesses, so the
engine keeps them to itself; the node's own log and the audit trail record the decision.

```bash
curl -s http://localhost:8080/api/v1/queries                       # no token: 401, PRV-7001
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" http://localhost:8080/api/v1/queries
```

```python
from pravaha import connect
from pravaha.endpoint import Endpoint
from pravaha.options import ClientOptions

options = ClientOptions(endpoint=Endpoint.parse("grpc+tls://pravaha-1:9090"), token=token)
with connect(options=options) as client:
    rows = client.query("SELECT user_id, spend FROM hourly_spend")
```

The SDK refuses to send a token over a plaintext endpoint unless told `allow_insecure_token=True` —
a bearer token over an unencrypted channel is handed to anyone on the path. See
[Client code](/help/topics/client-snippets).

## PRV-7002 — forbidden

Authenticated, and not permitted: this principal may not read that view, read a stream the query
reads, register, drop, pause or resume, or read the audit trail. **Ask for access — a new credential
will not help.** What each principal may do is decided by the node's policy; the console's
**Admin · Access** screen shows the answers for the console's own identity.

A name you may not see answers **exactly as a name that does not exist** (PRV-4023, PRV-8002) — so
that a denial cannot be used to learn what exists. PRV-7002 is for things you may know exist and may
not do.

## PRV-7003 — filter not enforceable

A **row filter** — the predicate a policy attaches to a principal for a view, so that it reads only its
own rows — cannot be applied to the view it is for: it names a column the view does not carry, or it
left no predicate in the plan. The alternative to refusing is serving an aggregate that mixed in rows
this principal may not see, which is why the read is refused and treated like a denial (it maps to the
same statuses as PRV-7002).

**Do:** make the filter's column part of the view — keep `region` in the `SELECT` list if the filter
is `region = 'EU'` — or change the policy's filter to a column the view has. See
[Row filters](/help/topics/row-filters).

!!! note "Subscribing with a row filter is refused on purpose"
    Reading a view applies the caller's row filter. Subscribing to its changes is the one path that
    **refuses** a principal carrying a row filter (STRM-13) rather than streaming changes it could not
    filter per row.

## PRV-7004 — misconfigured

A **security setting this node refuses to start with** — not a caller being denied. The message names
the key and the value. It was split from PRV-7002 (E-3), which had grown four unrelated meanings: an
operator who wrote `policy: permisive` was being told to go and ask somebody for permission.

The cases, each refused at startup:

| Configuration | Why it is refused |
|---|---|
| `authentication: none` with `policy: permissive` and no `allow-anonymous: true` | It serves every view to every unauthenticated caller. Legitimate behind a boundary that already authenticates — and not something anybody should get by not reading the file |
| `policy: authenticated` with `authentication: none` | A node nobody can use: the policy serves only verified callers and nothing can verify one |
| A `policy` other than `permissive` or `authenticated` | Not a policy this node knows (or implement `SecurityPolicy` for your own rules) |
| An `audit` other than `none`, `memory` or `file` | Not an audit mode |
| `audit: file` naming a path this node cannot write | A node that believes it is auditing and writes nowhere has no record at all (CFG-23) |
| `pravaha.security.audit-recent` below 1 | It is how many recent decisions stay readable over `GET /api/v1/audit` |

The three coherent starting points:

```yaml
# 1. Real credentials.
pravaha:
  security:
    authentication: token
    policy: authenticated
    audit: file
    audit-file: /var/log/pravaha/audit.jsonl
```

```yaml
# 2. Open, on purpose, written down -- behind something that already authenticates.
pravaha:
  security:
    authentication: none
    policy: permissive
    allow-anonymous: true
```

```bash
# 3. A local first run: the dev profile is the second shape.
pravaha-server --spring.profiles.active=dev
```

The tokens for `authentication: token` go under `pravaha.security.tokens`, each with an `id`, a
`tenant` and `roles`; that static list is for development and tests, and a real deployment implements
a `TokenVerifier` against its own identity system. See [Authentication](/help/topics/authentication).

## Where next

- [Authentication](/help/topics/authentication), [Authorization](/help/topics/authorization),
  [Row filters](/help/topics/row-filters), [Audit](/help/topics/audit)
- [Security (long form)](/help/security)
