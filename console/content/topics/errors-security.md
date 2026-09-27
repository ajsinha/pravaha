---
title: Security codes (PRV-7xxx)
slug: errors-security
category: errors
order: 80
icon: shield-exclamation
summary: "Not authenticated, not authorized, an unenforceable row filter, a setting the node will not start with, a sink write allowed only in part (PRV-7001 to 7005), and the node's own users, keys and sessions (PRV-7010 to 7021)."
badge: PRV-7XXX
audience: Everyone
keywords: [password, lockout, api key, session, reset token, identity, unauthenticated, forbidden, unauthorized, 401, 403, token, bearer, credential, grant, row filter, policy, permissive, authenticated, allow-anonymous, audit, misconfigured]
guide: security
related: [authentication, authorization, row-filters, audit, errors-overview]
---

Five codes, and the reason there are five is the advice. Each one is answered by a different person
doing a different thing — presenting a credential, granting access, fixing a view, editing a file,
correcting a policy — and a code that meant two of them would give the right advice to only one.

| Code | Name | Who fixes it | How |
|---|---|---|---|
| PRV-7001 | SECURITY_UNAUTHENTICATED | The caller | Present a credential, or a fresh one |
| PRV-7002 | SECURITY_FORBIDDEN | Whoever grants access | A grant; a new credential will not help |
| PRV-7003 | SECURITY_FILTER_NOT_ENFORCEABLE | Whoever owns the view or the policy | Make the filter's column part of the view |
| PRV-7004 | SECURITY_MISCONFIGURED | The operator | An edit to the node's security settings |
| PRV-7005 | SECURITY_SINK_WRITE_NOT_FILTERABLE | Whoever owns the policy | Answer `mayWriteTo` with `allow()` or `deny()`, not with a row filter |

How each travels:

| Code | Flight status | PostgreSQL SQLSTATE | REST |
|---|---|---|---|
| PRV-7001 | `UNAUTHENTICATED` | `28000` | `401` from the bearer-token check |
| PRV-7002, PRV-7003, PRV-7005 | `UNAUTHORIZED` | `42501` | `403` |
| PRV-7004 | — (the node does not start) | — | — |

## PRV-7001 — unauthenticated

No credential, or one that did not verify. **Present a credential, or a fresh one.**

The message says only that the credential was not accepted — never *why*. "Expired" versus "unknown"
versus "wrong signature" is three bits of an oracle for whoever is working through guesses, so the
engine keeps them to itself; the node's own log and the audit trail record the decision.

```bash
curl -s http://localhost:18080/api/v1/queries                       # no token: 401, PRV-7001
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" http://localhost:18080/api/v1/queries
```

```python
from pravaha import connect
from pravaha.endpoint import Endpoint
from pravaha.options import ClientOptions

options = ClientOptions(endpoint=Endpoint.parse("grpc+tls://pravaha-1:19090"), token=token)
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
    audit-file: /opt/pravaha/logs/audit.jsonl
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

## PRV-7005 — a sink write allowed only in part

The policy answered `mayWriteTo(principal, sink)` with **allow plus a row filter**, and there is no
way to carry that out. A sink receives the query's whole changelog or none of it: withholding the
rows the filter excludes would leave the destination unequal to the view, which is the one promise
a sink makes. The registration is refused at registration time, before the sink is opened.

```text
PRV-7005  the policy would let dana write to sink 'orders' only through the row filter
          (user_id = 'dana'), and a sink takes the query's whole changelog or none of it.
          Refused rather than written unfiltered: answer mayWriteTo with allow() or deny().
```

**Whoever owns the policy fixes it**, by answering that question with `allow()` or `deny()`. It is
deliberately not PRV-7002: nothing was denied, so telling the caller to go and ask for a grant
would send the right person nowhere. See [Authorization](/help/topics/authorization).

## Where next

- [Authentication](/help/topics/authentication), [Authorization](/help/topics/authorization),
  [Row filters](/help/topics/row-filters), [Audit](/help/topics/audit)
- [Security (long form)](/help/security)

## PRV-7010 to PRV-7021 — users, passwords, API keys and sessions

These come from the identity the node keeps itself (`pravaha.identity.enabled`, ADR-052). A
transport never tells a caller why a credential was refused: Flight, the PostgreSQL gateway and
REST answer `PRV-7001` for every refused session or key, and the specific code below is in the
audit trail. The sign-in, password and administration endpoints answer with the specific code,
because the person using them needs it to act.

| Code | Name | What happened | What to do |
|---|---|---|---|
| PRV-7010 | IDENTITY_CREDENTIALS_REFUSED | User name and password do not match. The answer is the same for an unknown user and a wrong password | Try again; each failure counts towards the lockout |
| PRV-7011 | IDENTITY_LOCKED | Five failures within 15 minutes locked the account for 30 | Wait until the stated time, or ask an administrator for a reset |
| PRV-7012 | IDENTITY_PASSWORD_POLICY | A new password breaks a rule; the message names it | At least 12 characters from 3 of lower, upper, digits and symbols, and none of the last 5 |
| PRV-7013 | IDENTITY_KEY_NOT_VALID | An API key that is unknown, expired, revoked, past its rotation overlap, or had the wrong secret | Issue a new key, or use the rotated successor |
| PRV-7014 | IDENTITY_KEY_WRONG_ENVIRONMENT | A key minted by another deployment, such as `prv_qa_…` presented to `prod` | Issue a key on this deployment |
| PRV-7015 | IDENTITY_WOULD_WIDEN | A key asked for a role its holder does not have, or an administrator tried to drop their own admin role | Ask for a subset of your roles; another administrator changes yours |
| PRV-7016 | IDENTITY_SESSION_EXPIRED | Idle 30 minutes, 12 hours in all, logged out, or ended by a password change, reset or disable | Sign in again |
| PRV-7017 | IDENTITY_RESET_TOKEN_INVALID | A reset token that is unknown, used, or older than 60 minutes | Ask an administrator for another |
| PRV-7018 | IDENTITY_MUST_CHANGE_PASSWORD | Only with `pravaha.identity.password.force-change: true`: the password was set by an administrator | Change the password; the session can do nothing else until then |
| PRV-7019 | IDENTITY_DEFAULT_ADMIN_PASSWORD | `admin` still has its published default password on a node outside the dev profile, which refuses to start | Change the password, or set `pravaha.identity.allow-default-admin-password` on purpose |
| PRV-7020 | IDENTITY_INVALID_REQUEST | A user name outside the allowed form, a user that exists, an unknown status, or a key life outside 1 to 365 days | Correct the request |
| PRV-7021 | IDENTITY_NOT_FOUND | No user, or no session, by that name | Check the name |
