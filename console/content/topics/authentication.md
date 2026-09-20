---
title: Authentication — who is asking
slug: authentication
category: security
order: 10
icon: person-badge
summary: "How a node decides who a caller is: bearer tokens on every Flight and HTTP call, the none and token modes, static tokens for development, and why a node refuses to start open by accident."
badge: SECURITY
audience: Operators
keywords: [authentication, token, bearer, pravaha.security.authentication, tokens, allow-anonymous, principal, TokenVerifier, PRV-7001, dev profile, console token]
guide: security#the-three-seams
related: [authorization, audit, tls, row-filters, configuration]
---

Authentication answers one question — **who is this?** — and nothing else. A credential comes in; a
**principal** comes out: an id, a tenant, and a set of roles. What that principal may then read,
register or administer is [authorization](/help/topics/authorization), a separate seam, so a
deployment can change its identity provider without rewriting its rules or tighten its rules without
touching identity.

**Pravaha stores no passwords and runs no identity provider.** The `TokenVerifier` interface is where
a deployment plugs in the one it already has. What ships in the server is a static table of tokens --
named `StaticTokenVerifier` so nobody mistakes it for an identity system — for development servers
and tests.

## How a credential travels

The same token on every surface — as an `authorization: Bearer <token>` header on every Flight and
HTTP call, and as the password on the PostgreSQL gateway.

| Surface | Port | How the token is sent | No credential, or a bad one |
|---|---|---|---|
| Arrow Flight SQL (CLI, both SDKs, the console, JDBC/ADBC Flight drivers) | 9090 | `authorization: Bearer <token>` gRPC header | refused with PRV-7001 |
| HTTP API (`/api/v1/*`, `/actuator/prometheus`) | 8080 | `Authorization: Bearer <token>` | `401` with PRV-7001 |
| PostgreSQL gateway (`psql`, BI tools), when enabled | 5432 | the token **as the password** (cleartext password exchange, after the TLS handshake) | the connection is refused with PRV-7001 |

Flight has no session, and that is deliberate: a server behind a load balancer can answer any call
without the balancer pinning a client to one node.

Over HTTP, `/actuator/health`, `/actuator/info`, `/api/v1/openapi.json` and `/api/docs` stay open
so probes and API browsers work without a credential. Everything else — including
`/actuator/prometheus` — needs one when authentication is on.

## The settings

| Setting | Default | Values and meaning |
|---|---|---|
| `pravaha.security.authentication` | `none` | `none`: every caller is the anonymous principal. `token`: every call must carry a token the node can verify. Anything else is refused at startup — a misspelling here would otherwise mean `none` |
| `pravaha.security.tokens.*` | empty | The static token table: each key is a token, each value the identity it stands for (`id` — **required** — `tenant`, default `public`, and `roles`) |
| `pravaha.security.policy` | `permissive` | What a principal may do — see [authorization](/help/topics/authorization) |
| `pravaha.security.allow-anonymous` | `false` | The written acknowledgement that this node serves everything to unauthenticated callers. Required to start a node with `authentication: none` and `policy: permissive` |

## The node refuses to start open

The shipped defaults — `authentication: none`, `policy: permissive`, `allow-anonymous: false` --
**refuse to start**, on purpose. A server that serves every view to every unauthenticated caller is a
legitimate way to run an engine behind a boundary that has already authenticated somebody; it is not
something anybody should get by not reading the configuration. Pick one of three:

| You want | Set |
|---|---|
| Real credentials | `authentication: token` and `tokens` (or your own `TokenVerifier`) |
| Anonymous callers get nothing | `policy: authenticated` (with `authentication: token`) |
| Open, on purpose | `allow-anonymous: true` — or start with `--spring.profiles.active=dev`, which is exactly that |

Started without one of them, the node stops with PRV-7004 and says which three settings would fix
it:

```text
PRV-7004  this node is configured to accept unauthenticated callers and serve them every view (pravaha.security.authentication=none, policy=permissive). That is a reasonable way to run an engine behind a boundary that has already authenticated the caller, and a bad way to run one on a network. Set pravaha.security.authentication=token with pravaha.security.tokens.*, or set pravaha.security.policy=authenticated, or -- if open really is what you want -- set pravaha.security.allow-anonymous=true to say so on purpose.
```

`allow-anonymous` does not change who is admitted; it is the acknowledgement that lets a node that
already admits everyone start. And the opposite contradiction is refused too: `policy: authenticated`
with `authentication: none` is a node nobody can use (the policy serves only verified callers and
nothing can verify one), so it stops with PRV-7004 as well.

## Every token needs an `id`

The map key under `pravaha.security.tokens` **is the bearer credential**. The `id` beside it used to
be optional and to default to that key — so a node written as

```yaml
pravaha:
  security:
    tokens:
      "s3cr3t-value": {}          # refused: no id
```

put the secret in two durable places nobody chose: the audit trail, and the registry journal at
`pravaha.registry.journal`, as the owner of every query that principal registered, where it
survives restarts and backups. The credential is correctly kept out of the startup log and out of
`/actuator/env`, which made the journal the only leak and an easy one to miss. An entry without an
`id` is now refused at startup with PRV-7004, and the refusal does not print the credential
(CFG-11).

Give every entry at least that one property for a second reason: `x: {}` in YAML flattens to no
property at all, so Spring's binder never sees the key. The credential is in the file, absent from
the verifier chain, and there is nothing in the process that can notice it is missing — the node
does not warn, because it has never been told (CFG-10).

### Two keys YAML will not hand over as you typed them

The key **is the bearer credential**, so anything that happens to it between the file and the map
changes who can authenticate. Both of these are refused at startup with `PRV-7004` rather than
quietly repaired (SX-14):

- **A bare `yes:`, `on:`, `y:` or their negatives.** YAML 1.1 reads them as booleans, so the key
  binds as the word `true` and no client can present it — a node that starts, reports that it
  authenticates, and authenticates nobody. Quote it: `"[yes]": {id: ...}`. Two of them in one table
  collapse into one key and fail the whole file's load with a duplicate-key error naming neither
  line; nothing here can catch that, because the file never loads.
- **Leading or trailing whitespace.** Spring discards it while binding, so `" tok "` and `"tok"`
  are one entry and one of the two credentials you wrote is gone. Whitespace *inside* a credential
  is fine.

A node with `authentication: token` and **no** entries does say so, at startup:

```text
pravaha.security.authentication=token with no entries under pravaha.security.tokens: this node can
verify no credential and refuses every call with PRV-7001.
```

## Worked example: a token-authenticated node

```yaml
pravaha:
  security:
    authentication: token
    policy: authenticated
    audit: file
    audit-file: /var/log/pravaha/audit.jsonl
    tokens:
      "9f3c1d7a-analyst-2b8e4f6a1c5d":
        id: ann
        tenant: acme
        roles: [analyst]
      "c41e8b02-ops-7d9a3f5e6b10":
        id: ops-console
        tenant: acme
        roles: [operator, admin]
  flight:
    tls:
      certificate: /etc/pravaha/tls/server-chain.pem
      key: /etc/pravaha/tls/server-key.pem
```

Tokens are long random strings; generate them (`openssl rand -hex 24`) rather than inventing them,
and keep the file readable only by the service account. **Encrypt the transport** (the `flight.tls`
block above; see [TLS everywhere](/help/topics/tls)): a bearer token over a plaintext channel is
handed to anyone on the path.

### From the CLI

```bash
export PRAVAHA_TOKEN="9f3c1d7a-analyst-2b8e4f6a1c5d"
pravaha queries --url grpc+tls://pravaha.internal:9090 --token "$PRAVAHA_TOKEN"
```

Over a plaintext `grpc://` endpoint the CLI refuses to send a token at all, before connecting,
unless you type `--insecure-token` — which exists for a loopback socket or a sidecar that terminates
TLS, and nothing else:

```bash
pravaha queries --url grpc://localhost:9090 --token "$PRAVAHA_TOKEN" --insecure-token
```

### From Python

```python
import os
from pravaha import ClientOptions, connect

options = ClientOptions.create("grpc+tls://pravaha.internal:9090",
                               token=os.environ["PRAVAHA_TOKEN"],
                               http_url="https://pravaha.internal:8080")
with connect(options=options) as client:
    print(client.permissions()["register"])
```

The SDK refuses a token over `grpc://` or `http://` unless `allow_insecure_token=True` is passed.

### Over HTTP

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" https://pravaha.internal:8080/api/v1/queries
```

Without the header:

```bash
curl -s https://pravaha.internal:8080/api/v1/queries
```

```json
{"code":"PRV-7001","message":"this server requires a credential; send it as 'Authorization: Bearer <token>'","helpUrl":"","timestamp":"2026-09-19T09:30:00Z","path":"/api/v1/queries"}
```

(The `helpUrl` host does not resolve; this help's own page for the code is the one to read.)

## What a refusal does not tell you

A credential the node cannot verify is refused with PRV-7001 and a message that says only that it was
not accepted — never *why*. "Expired" versus "unknown" versus "wrong signature" would be three bits
of an oracle for whoever is working through guesses.

PRV-7001 and PRV-7002 are kept apart on purpose: **7001** means "present a credential, or a fresh
one"; **7002** means "you are known, and not allowed — ask for access; a new credential will not
help". Collapsing them produces support calls where nobody can tell which happened.

## The console's own identity

The console is a separate process that reaches the engine as **one** identity: the token in its
`engine.token` setting (environment variable `PRAVAHA_TOKEN`), sent on every Flight and HTTP call and
never to a browser. Everything a signed-in console user does, the engine sees as that principal --
so give the console a token whose roles cover what its operators need, and read its actions in the
[audit trail](/help/topics/audit) under that id.

Signing in to the console is a separate, simpler gate: a shared password, `console.password`, held in
a signed session cookie. Unset, nobody can sign in. It distinguishes signed-in from anonymous and
nothing finer; a deployment needing per-person identity should put the console behind its own
single-sign-on proxy.

## Bringing your own identity provider

`TokenVerifier` takes the credential and returns a `Principal`, or throws PRV-7001. An
implementation that validates your IdP's tokens — OIDC, JWT, an internal service — is the supported
way to use real identity; **no OIDC or JWT verifier ships**. Wire it where the Flight server is built:

```java
PravahaFlightServer server = new PravahaFlightServer(views)
        .authenticatedBy(myTokenVerifier)          // credential -> Principal
        .authorizedBy(myPolicy, myAuditSink)       // what they may read, and the record
        .hosting(registry)
        .start("0.0.0.0", 9090);
```

A Flight server started **without** `authenticatedBy` accepts every call as the anonymous principal
-- correct for an engine embedded in a process that has already authenticated its caller, wrong for
anything on a network.

## Pitfalls

!!! danger "Pitfall: a token over plaintext"
    Authentication over an unencrypted channel hands the bearer token to anyone who can see the
    traffic. Configure `pravaha.flight.tls.certificate` and `pravaha.flight.tls.key` before
    `authentication: token` leaves a loopback socket — and note that the HTTP port needs its own TLS
    (see [TLS everywhere](/help/topics/tls)).

!!! warning "Pitfall: a journalled owner the node cannot identify"
    A restart recovers each registration under the principal that made it, looked up by id in the
    configured tokens. Remove a token and the registrations its identity owned cannot be
    re-authorized, so they are refused at recovery rather than recovered under an invented identity.

!!! note "Tokens with no table"
    `authentication: token` with no `tokens` configured (and no verifier of your own) refuses every
    call. That is the safe failure; if every client gets PRV-7001, check that the token table was
    actually read.

## Where next

- [Authorization](/help/topics/authorization) — what a principal may do
- [Audit](/help/topics/audit) — every decision, recorded
- [TLS everywhere](/help/topics/tls) — so the token is not in the clear
