# SEC — Security

Authentication, authorization, the startup refusal, row filters, entitlement-aware query sharing,
journal re-authorization, TLS, and secret hygiene.

The three properties under test, in the owner's words:

1. Authorization is enforced at the Pravaha layer and **not** pushed down to persistence.
2. Only authenticated users may access data.
3. Users receive only the data they are authorized for.

Ports used: HTTP 18200–18219, Flight 19200–19219. Every server started is recorded by PID and
killed by PID.

---

## Group A — the startup refusal

## SEC-001 — a default-configured node refuses to start
**Intent:** `refuseAccidentalOpenServer` is the single control that stops an open server shipping by
accident. If the shipped defaults start, every other control is optional.
**Setup:** the built `pravaha-server-*-app.jar`, no profile, no overrides beyond the HTTP/Flight port.
**Steps:** start the server on HTTP 18200 / Flight 19200 with nothing else set; wait for exit.
**Expected:** the process exits non-zero. The log carries `PRV-7002` and names all three escapes
(`authentication=token`, `policy=authenticated`, `allow-anonymous=true`). No HTTP listener is left
bound on 18200.

## SEC-002 — the `dev` profile starts open, and says so
**Intent:** the documented first-run path must work, and must be visibly an acknowledgement rather
than a default.
**Setup:** as SEC-001.
**Steps:** start with `--spring.profiles.active=dev`.
**Expected:** the node starts; the log line `security: authentication=none, policy=permissive` appears;
`GET /api/v1/status` answers 200 with no credential. This is the *intended* open configuration.

## SEC-003 — `allow-anonymous` on the command line starts an open server
**Intent:** the escape hatch must be reachable without editing a file, and must be the only way.
**Steps:** start with `--pravaha.security.allow-anonymous=true`.
**Expected:** starts; behaves as SEC-002.

## SEC-004 — `allow-anonymous` via environment variable
**Intent:** Spring relaxed binding means `PRAVAHA_SECURITY_ALLOWANONYMOUS=true` is the same switch.
A reviewer reading only the YAML would not see it. Establish that it works so the operator's threat
model includes it.
**Steps:** start with `PRAVAHA_SECURITY_ALLOWANONYMOUS=true` in the environment and nothing on the
command line.
**Expected:** starts open. (Documented as a fact about the control, not a defect in itself.)

## SEC-005 — `allow-anonymous` as a non-boolean string does not silently open the server
**Intent:** a tired operator writes `allow-anonymous: yes`. The dangerous outcome is that it binds
to `true`, or binds to `false` while looking like it worked. Either way the operator must not be
able to *believe* the server is closed when it is open, or vice versa.
**Steps:** start with `--pravaha.security.allow-anonymous=yes`.
**Expected:** either a binding failure that names the property, or a start refusal (PRV-7002).
Specifically NOT: a server that starts open.

## SEC-006 — a typo in `policy` is refused, not treated as permissive
**Intent:** a policy name the node does not know must fail closed. Falling through to PERMISSIVE
would turn a typo into an open server.
**Steps:** start with `--pravaha.security.policy=authentcated --pravaha.security.allow-anonymous=true`
(allow-anonymous set so that the *only* thing that can refuse the start is the unknown policy name).
**Expected:** refuses to start, PRV-7002, message quotes the bad value and lists the valid ones.

## SEC-007 — `policy: AUTHENTICATED` in the wrong case is accepted
**Intent:** the switch lowercases before matching. Confirm, because the alternative — an uppercase
value falling into `default` — would be a refusal at startup, and an operator would "fix" it by
setting allow-anonymous.
**Steps:** start with `--pravaha.security.policy=AUTHENTICATED` and no allow-anonymous.
**Expected:** starts (the policy is `AuthenticatedOnlyPolicy`, so the node is not open).

## SEC-008 — `policy: authenticated` with no authentication configured
**Intent:** this is one of the three documented ways to satisfy the startup guard. The node starts
with **no credential mechanism at all** and a policy that refuses anonymous callers. Everything a
caller can still reach is, by construction, reached anonymously.
**Steps:** start with `--pravaha.security.policy=authenticated` and no authentication. Then, with no
credential: `GET /api/v1/streams`, `GET /api/v1/status`, `POST /api/v1/streams`, `POST
/api/v1/queries/explain`.
**Expected:** every one of those is refused (401 or 403). Anything that answers is an authorization
gap: the configuration says "only verified callers see anything".

## SEC-009 — `authentication: token` with no tokens configured
**Intent:** `verifier()` returns `TokenVerifier.rejectAll()` for an empty token table. A server that
authenticates against nothing must refuse everyone rather than accept everyone.
**Steps:** start with `--pravaha.security.authentication=token` and no `tokens:`; call
`GET /api/v1/streams` with and without a credential.
**Expected:** starts; both calls 401; the message says the server has no way to verify credentials.

## SEC-010 — a typo in `authentication` silently disables authentication
**Intent:** `authenticates()` is `"token".equalsIgnoreCase(authentication)`. Anything else — including
`tokens`, `Token `, `bearer`, `oidc` — is "none". Combined with `policy: authenticated` the node
starts and nobody is told that authentication is off.
**Steps:** start with `--pravaha.security.authentication=tokens --pravaha.security.policy=authenticated`
plus a token table; call `GET /api/v1/streams` with no credential.
**Expected (as designed):** ideally the node refuses an authentication mechanism it does not know.
Falling back to "none" without a word is a defect; record which happens.

## SEC-011 — the startup refusal happens before anything is served
**Intent:** a refusal that happens after the HTTP connector binds is a window in which the open
server is reachable.
**Steps:** during SEC-001, poll `http://127.0.0.1:18200/actuator/health` continuously from process
start until exit.
**Expected:** no successful HTTP response at any point.

---

## Group B — authentication on the HTTP surface

Baseline for this group: the node runs with `authentication=token`, one token
`sek-ret-token-aaaaaaaaaaaaaaaaaaaa` → principal `ann`, and `policy=permissive`.

## SEC-012 — no Authorization header
**Steps:** `curl -i http://127.0.0.1:18201/api/v1/streams`
**Expected:** 401, `application/json`, code `PRV-7001`.

## SEC-013 — empty Authorization header
**Steps:** `curl -i -H 'Authorization;' .../api/v1/streams` (sends `Authorization:` with no value).
**Expected:** 401. Must not be treated as a credential.

## SEC-014 — `Authorization: Bearer` with no token
**Steps:** header exactly `Bearer` (6 chars, no trailing space) and exactly `Bearer ` (trailing
space, empty token).
**Expected:** 401 for both. `Bearer ` must not authenticate as the empty token.

## SEC-015 — a wrong token
**Steps:** `Authorization: Bearer not-the-token`.
**Expected:** 401, and the message must not distinguish "unknown" from "expired" or "malformed" —
one message for every failure.

## SEC-016 — a valid token
**Steps:** `Authorization: Bearer sek-ret-token-aaaaaaaaaaaaaaaaaaaa`.
**Expected:** 200 and the real response body. Proves the 401s above are not vacuous.

## SEC-017 — `bearer` in lower case
**Intent:** RFC 7235 says the scheme is case-insensitive. Rejecting it would break clients; accepting
it must not be accidental.
**Steps:** `Authorization: bearer <valid>`.
**Expected:** 200.

## SEC-018 — a token surrounded by whitespace
**Steps:** `Authorization: Bearer    <valid>   ` (extra spaces and a tab).
**Expected:** 200 — the filter strips. Record whether an *interior* space also passes (it must not).

## SEC-019 — a very long token
**Intent:** an unbounded credential is a cheap way to spend a server's memory or trip a container
limit in a way that fails open.
**Steps:** 64 KiB of `A` as the token.
**Expected:** a refusal (401 from the filter, or 400/431 from the container). Never 200, never a
stack trace, never a hung connection.

## SEC-020 — injection-shaped content in the token
**Steps:** tokens `' OR '1'='1`, `"; DROP TABLE x; --`, `${jndi:ldap://x/y}`, `%00admin`, and a token
containing `"` and a newline.
**Expected:** 401 for each. The response body must remain well-formed JSON — the filter builds it by
string concatenation, so a quote or a newline in an echoed value would break the shape.

## SEC-021 — the 401 body matches the ApiError shape
**Intent:** the filter hand-writes its JSON while every other failure is serialised from
`ApiDtos.ApiError(code, message, helpUrl, timestamp, path)`. The codebase states in three places
that there is exactly one error shape. Verify it.
**Steps:** capture the 401 body from SEC-012 and a 400 body from `POST /api/v1/queries/explain` with
`level=nonsense` (authenticated). Compare the field sets.
**Expected:** identical field names.

## SEC-022 — a credential is not accepted anywhere but the header
**Steps:** `?token=<valid>`, `Cookie: token=<valid>`, `X-Api-Key: <valid>`, `Proxy-Authorization: Bearer <valid>`.
**Expected:** 401 for all four.

---

## Group C — which HTTP paths answer without a credential

Baseline: same as Group B (`authentication=token`).

## SEC-023 — enumerate every mapped endpoint unauthenticated
**Intent:** the open list is a static `Set` of prefixes matched with `startsWith`. Anything not in it
must be closed, and anything in it must disclose nothing.
**Steps:** with no credential, request each of: `/`, `/status`, `/api/v1/status`, `/api/v1/streams`,
`/api/v1/streams/txn`, `POST /api/v1/streams`, `POST /api/v1/queries/validate`,
`POST /api/v1/queries/explain`, `/error`, `/actuator`, `/actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus`,
`/api/v1/openapi.json`, `/api/docs`, `/swagger-ui/index.html`.
**Expected:** 401 for everything except `/actuator/health*`, `/actuator/info`, `/api/v1/openapi.json`,
`/api/docs`, `/swagger-ui/*`. In particular `/actuator/metrics` and `/actuator/prometheus` are
exposed by `management.endpoints.web.exposure.include` and are **not** in the open list, so they must
be 401.

## SEC-024 — the sensitive actuator endpoints are not exposed at all
**Intent:** `/actuator/env` and `/actuator/configprops` would print `pravaha.security.tokens`, whose
**map keys are the tokens themselves**; Spring's sanitiser masks values, not keys. `/actuator/heapdump`
would hand over the token table in memory. `/actuator/beans`, `/threaddump`, `/loggers`, `/mappings`
are each a disclosure.
**Steps:** request `/actuator/env`, `/actuator/env/pravaha.security.tokens`, `/actuator/configprops`,
`/actuator/beans`, `/actuator/heapdump`, `/actuator/threaddump`, `/actuator/loggers`,
`/actuator/mappings`, `/actuator/shutdown`, `/actuator/caches`, `/actuator/conditions` — both with
and without a valid credential.
**Expected:** 404 (not exposed) unauthenticated *and* authenticated. A 200 on any of them
unauthenticated is CRITICAL; a 200 authenticated is HIGH.

## SEC-025 — `/actuator/health` discloses no details
**Intent:** it is deliberately open. `show-details: when-authorized` with no Spring Security in the
context is the case that decides whether "authorized" means "nobody" or "everybody".
**Steps:** `GET /actuator/health` with no credential.
**Expected:** `{"status":"UP"}` and nothing else — no component names, no disk paths, no versions.

## SEC-026 — the open prefixes cannot be used to reach a closed path
**Intent:** `shouldNotFilter` is `path.startsWith(prefix)`. A path that *starts* with an open prefix
but is routed elsewhere would bypass authentication entirely.
**Steps:** request `/actuator/health/../env`, `/actuator/health/..;/env`, `/actuator/health%2f..%2fenv`,
`/api/docs/../v1/streams`, `/api/v1/openapi.json/../../v1/streams`, `/swagger-ui/../api/v1/streams`,
`//api/v1/streams`, `/api/v1/streams;x=/actuator/health`, `/actuator/healthz`, `/actuator/health-check`,
and `/api/v1/openapi.jsonx`.
**Expected:** every request either 401, or 400/404 from the container. None returns stream data.

## SEC-027 — the OpenAPI document and the docs UI disclose only the API shape
**Steps:** fetch `/api/v1/openapi.json` unauthenticated; grep it for stream names, token values,
`pravaha.security`, file paths, and host names.
**Expected:** the document describes paths and DTO schemas. It must not contain configured stream
names, tokens, or filesystem paths.

## SEC-028 — the HTTP surface under `policy=authenticated` (the authorization question)
**Intent:** SEC-008 from the other side, and the sharpest form of requirement 1: the SecurityPolicy
is the Pravaha-layer authorization point. If no HTTP controller consults it, then `policy` governs
Flight only and the HTTP surface is governed by the filter alone.
**Steps:** run with `authentication=token` **and** `policy=authenticated`. Authenticate as `ann`.
Then grep the four controllers for any use of `BearerTokenFilter.principalOf` or `SecurityPolicy`.
**Expected:** authenticated calls succeed. Record whether the policy is consulted at all on HTTP;
if it is not, the finding is that `policy` does not apply to HTTP.

---

## Group D — Flight authentication

Baseline: `authentication=token`, one token for `ann`, `policy=permissive`, Flight on 19202.

## SEC-029 — an unauthenticated Flight call is refused
**Steps:** `pravaha queries --url grpc://127.0.0.1:19202` with no `--token`.
**Expected:** UNAUTHENTICATED with `PRV-7001`; no query list is printed.

## SEC-030 — a bad Flight token is refused
**Steps:** same with `--token wrong-token`.
**Expected:** UNAUTHENTICATED; the message says only that the credential was not accepted.

## SEC-031 — a valid Flight token is accepted
**Steps:** same with the real token.
**Expected:** the call succeeds (an empty list is a success). Proves SEC-029/030 are not vacuous.

## SEC-032 — every Flight verb is authenticated, not just the data ones
**Intent:** `PrincipalMiddleware` claims metadata calls are verified too, because a schema is a list
of the columns a business keeps and a view list is a map of the deployment.
**Steps:** with no credential, attempt `queries` (LIST), `register`, `query` (a SQL read),
`subscribe`, `drop`, `pause`, `resume`.
**Expected:** all seven refused before any work is done.

## SEC-033 — an anonymous Flight caller under `policy=authenticated`, authentication off
**Intent:** the configuration the startup guard accepts as "closed". Flight has no middleware, so
every caller is `Principal.ANONYMOUS`, and `AuthenticatedOnlyPolicy` is the only thing between them
and the node.
**Steps:** with `policy=authenticated` and `authentication=none`, from an anonymous client: `register`,
`queries` (LIST), `query` (read a view), `subscribe`, `drop`, `pause`, `resume`.
**Expected:** all refused with PRV-7002. Any verb that succeeds is an authorization gap; a verb that
returns query text or rows is CRITICAL.

## SEC-034 — lifecycle verbs are authorized
**Intent:** `doAction` calls `policy.mayRegisterQuery` for REGISTER and calls nothing for DROP, PAUSE
and RESUME. Destroying another principal's standing computation is an authorization decision.
**Steps:** as an anonymous caller under `policy=authenticated` (SEC-033's node, with a query
pre-registered by an authorized principal through a harness), call `drop`, `pause`, `resume` on it.
**Expected:** refused. If they succeed, an unauthenticated caller can destroy every query on the node.

---

## Group E — authorization, row filters, entitlements

These need a policy with real rules. A test-only harness under the scratch directory builds a
`QueryRegistry` + `PravahaFlightServer` on the released jars with a policy that gives `ann` an
unrestricted read and `bob` a row filter, and denies `carol` the `payroll` stream. No production
code is modified.

## SEC-035 — a denied principal cannot read a view
**Steps:** harness policy denies `carol` everything. `carol` runs `SELECT * FROM v`.
**Expected:** PRV-7002, no rows.

## SEC-036 — a denied principal cannot register
**Steps:** `carol` registers any query.
**Expected:** PRV-7002 from `mayRegisterQuery`, before any plan is built.

## SEC-037 — registration authorizes the SOURCE streams, not only the view name
**Intent:** the documented past hole — `SELECT * FROM payroll AS anything` under a name the policy
happens to allow.
**Steps:** policy: `mayRead(p, "payroll")` denies `carol`; `mayRead(p, anything-else)` allows.
`carol` registers `SELECT employee, salary FROM payroll` as `harmless`.
**Expected:** refused, and the message names `payroll`. Then prove it is not vacuous: `ann`, who may
read `payroll`, registers the same query successfully.

## SEC-038 — a row filter is honoured on a read
**Steps:** policy returns `allowWithRowFilter("region = 'EU'")` for `bob`. Feed a view rows in EU and
US. `ann` reads it (all rows); `bob` reads it.
**Expected:** `ann` sees both regions, `bob` sees only EU. `ann` seeing both is what makes `bob`'s
result non-vacuous.

## SEC-039 — a row filter naming a column the view does not carry is refused
**Intent:** `SecurityErrors.FILTER_NOT_ENFORCEABLE`. Serving an aggregate that mixed in rows the
principal may not see is the failure this prevents.
**Steps:** policy returns `allowWithRowFilter("region = 'EU'")` against a view that aggregated
`region` away.
**Expected:** PRV-7003, no rows.

## SEC-040 — a row filter on subscribe (the past data leak)
**Intent:** subscribe once discarded row filters and streamed everything. Verify the fix.
**Steps:** `bob` (filtered to EU) subscribes to a view containing EU and US rows.
**Expected:** either only EU rows, or a refusal that says the filter cannot be enforced on this path.
Receiving a US row is **CRITICAL**.

## SEC-041 — an unfiltered principal can still subscribe
**Intent:** proves SEC-040 did not pass because subscribe is broken for everybody.
**Steps:** `ann` subscribes to the same view.
**Expected:** rows arrive.

## SEC-042 — two principals with different row filters do not share one computation
**Intent:** the fingerprint includes the sorted row filters. If it did not, the restricted principal's
data would sit in the same state as the unrestricted one and only the read path would separate them.
**Steps:** `ann` (no filter) and `bob` (`region = 'EU'`) each register the same SQL under different
names. Compare `RegisteredQuery` identity and the fingerprint short form.
**Expected:** two distinct fingerprints, two executions. Prove it is not vacuous: two principals with
the *same* filter registering the same SQL must share one fingerprint.

## SEC-043 — the LIST action does not disclose other principals' queries
**Intent:** `doAction` LIST returns every name and the full SQL text of every registered query, with
no `mayRead`. SQL text carries column names, filter predicates and literal values.
**Steps:** `ann` registers `SELECT * FROM payroll WHERE account = 'ACC-0007'`. `carol`, who may not
read `payroll`, calls LIST.
**Expected:** `carol` does not receive the SQL of a query over a stream she may not read.

## SEC-044 — a journalled query is re-authorized on recovery
**Intent:** a journal must not be a way to keep an entitlement after it was revoked.
**Steps:** with a journal file, `ann` registers a query. Stop the node. Restart with a policy under
which `ann` is denied. Inspect the recovery log and the registry.
**Expected:** the query is listed as refused and is not registered; the refusal names the query.

## SEC-045 — a journalled query whose owner is unknown is not recovered
**Steps:** hand-edit a journal entry's owner to an id the resolver rejects (blank).
**Expected:** refused with "there is nobody to authorize it as"; the node still starts and recovers
the other entries.

## SEC-046 — recovery does not silently drop a row filter
**Intent:** recovery re-registers through the same path, so the recovered query's fingerprint must
include the owner's current row filters.
**Steps:** `bob` (filtered) registers; restart; compare the recovered fingerprint against a fresh
registration by `bob` and against one by `ann`.
**Expected:** recovered == `bob`'s, != `ann`'s.

---

## Group F — TLS

## SEC-047 — a self-signed certificate produces a genuinely TLS Flight port
**Steps:** generate a self-signed cert/key with `openssl`; start with
`pravaha.flight.tls.certificate/key`; run `openssl s_client -connect 127.0.0.1:19206`.
**Expected:** a TLS handshake completes and the server certificate is presented. The startup log says
`flight transport=TLS`.

## SEC-048 — a plaintext client cannot talk to the TLS port
**Intent:** proves SEC-047 is not a certificate that was configured and ignored.
**Steps:** `pravaha queries --url grpc://127.0.0.1:19206`.
**Expected:** the call fails at the transport. It must not succeed in clear text.

## SEC-049 — a missing certificate is refused at startup
**Steps:** point `pravaha.flight.tls.certificate` at a path that does not exist.
**Expected:** the node exits with `PRV-` TLS_UNREADABLE naming the path. It must not start in
plaintext.

## SEC-050 — an unreadable certificate is refused at startup
**Intent:** `encryptedWith` checks `isFile()`, which is true for a file the process cannot read. The
failure must still be a refusal and not a fallback.
**Steps:** `chmod 000` the certificate; start.
**Expected:** the node exits. Specifically NOT: a node that starts with `transport=PLAINTEXT`.

## SEC-051 — an empty certificate path is plaintext with a warning, when authentication is on
**Intent:** the documented compromise (a sidecar may terminate TLS). Verify the warning exists so the
operator has something to see.
**Steps:** start with `authentication=token` and no TLS.
**Expected:** starts; a WARN naming `pravaha.flight.tls.certificate` appears.

---

## Group G — secrets in logs and on disk

## SEC-052 — a token never appears in a server log line
**Steps:** run a node with a distinctive token; make one successful and one failed call on both HTTP
and Flight; grep the whole log for the token.
**Expected:** zero matches.

## SEC-053 — a rejected token is not echoed to the client or the log
**Steps:** present a token containing a marker string; grep the log and the 401 body for the marker.
**Expected:** zero matches in both.

## SEC-054 — bound parameter values do not appear in a log line
**Intent:** `application.yaml` says the journal holds "query text and bound parameter values (account
numbers, customer ids)" and must be permissioned like data. A value that also reaches a log line has
escaped that permissioning.
**Steps:** register a parameterised query with a marker value; grep the server log.
**Expected:** the marker does not appear in the log. (It may appear in the journal file — that is the
journal's job.)

## SEC-055 — the journal file is not world-readable
**Intent:** the configuration says to permission it like data. Check what the engine actually creates.
**Steps:** stat the journal file after a registration.
**Expected:** no read bit for other. Record the actual mode either way.

## SEC-056 — `Principal.toString` does not print claims
**Intent:** a principal reaches log lines and exception messages; claims carry email addresses.
**Steps:** unit-level check via the harness: build a principal with a claim and log it; also trigger a
denial so the reason reaches an error message.
**Expected:** the claim value appears nowhere.

---

## Added during execution

## SEC-057 — read-time authorization has no view lineage
**Added after SEC-043 was executed, not written in advance. Recorded as an addition rather than
folded into an existing case.**
**Intent:** registration authorizes a query's source streams (SEC-037), but `mayRead` is given only
the view name. If a policy cannot tell what a view derives from, a principal who may read a
restricted stream can register a view over it under a name the policy's rules do not catch, and a
principal denied that stream can then read the derived view.
**Setup:** the harness policy denies `carol` any view whose name contains `payroll`.
**Steps:** confirm `carol` is refused `payroll_view`. Have `ann` (entitled) register
`SELECT employee, region, salary FROM payroll WHERE salary >= 0` as `hr_summary` and feed it rows.
Have `carol` read `hr_summary`. Have `ann` read it too, for comparison.
**Expected:** `carol` is refused, or receives no payroll-derived row. Receiving the rows is a leak.
