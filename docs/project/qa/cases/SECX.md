# SECX — Security, round 2

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Area: **policy × authentication × TLS × row filter × principal × verb × transport**, plus lineage,
ownership, the lifetime of an authorization decision, audit completeness, and disclosure through
anything that is not the data itself.

IDs `SECX-001`–`SECX-095`. Budget 95. Supersedes round 1's `SEC.md` (57 cases) and its Re-QA
section for coverage purposes; `SEC.md` stays as the record of what was true then.

The three properties under test, in the owner's words, unchanged:

1. Authorization is enforced at the Pravaha layer and **not** pushed down to persistence.
2. Only authenticated users may access data.
3. Users receive only the data they are authorized for.

**Ports reserved for this area: HTTP 18620–18659, Flight 19620–19659.** Every server started is
recorded by PID and killed by PID. **No production code is modified by any case in this file.**
Scratch: `$QA = <scratchpad>/qa-secx`.

---

## What round 1 already established, so that these cases go past it rather than around it

These are **known**. A case below that touches one of them is written to measure its *extent*, not
to rediscover it. Re-demonstrating them is not a result.

| Ref | Known |
|---|---|
| SEC-043 | `LIST` filters on the **client-chosen view name**, so a payroll query named `secret_pay` still ships its SQL text, `'ACC-0007'` literal included, to a principal denied `payroll` |
| SEC-057 | Read-time authorization is handed a view name and nothing else; a restricted stream registered under an allowed name is readable by a principal denied that stream |
| SEC-058 | `PRV-2002`, `PRV-8002` and `PRV-4023` each enumerate every view; on **subscribe**, `required.require(viewName)` runs *before* `policy.mayRead` |
| SEC-034 | `mayAdminister` defaults to `mayRead` — read access is destroy access; under `AuthenticatedOnlyPolicy` every token holder may drop every other's query; the recorded owner is never consulted |
| SEC-061 | A server node can be given only `permissive` or `authenticated`. Row filters and per-tenant rules are unreachable from configuration |
| SEC-028 | No HTTP controller reads the principal or the policy |
| SEC-059/060 | `tls.key` without `tls.certificate` starts in plaintext; `tls.certificate` without `tls.key` throws a bare NPE |
| SEC-062 | `POST /api/v1/streams` returns 201 and never reaches the engine |
| SEC-063 | No shipped client can present a TLS trust anchor |
| E-4 | `pravaha.security.authentication` has no validation: `tokens`, `bearer`, `Token ` all silently mean `none` |
| P-3 | `--token` over `grpc://` sets `allowInsecureToken(true)` unconditionally and ships the token in clear text |
| S-2 | Recovery rebuilds every owner as `new Principal(id, "unknown", Set.of(), Map.of())` — role-less, tenant `unknown` |

---

## The standing fixture

Used by every case unless it says otherwise. Written once here so no case re-states it.

**`$QA/payroll.csv`** — three rows, one of which carries the marker literal:

```
ACC-0007,EU,99000
ACC-0450,US,250000
ACC-0911,EU,55000
```

**`$QA/sales.csv`** — four rows, three regions:

```
1,EU,100
2,US,250
3,EU,150
4,APAC,300
```

Hand-computed totals used throughout, so no case says "the sum":

| Set | Rows | `SUM(salary)` / `SUM(amount)` |
|---|---|---|
| payroll, all | 3 | `99000 + 250000 + 55000 = 404000` |
| payroll, `region = 'EU'` | 2 | `99000 + 55000 = 154000` |
| sales, all | 4 | `100 + 250 + 150 + 300 = 800` |
| sales, `region = 'EU'` | 2 | `100 + 150 = 250` |
| sales, `region = 'US'` | 1 | `250` |
| sales, `region = 'APAC'` | 1 | `300` |

**`$QA/streams.yaml`** — the stream and source declarations every node shares:

```yaml
pravaha:
  streams:
    payroll:
      schema: "employee:STRING,region:STRING,salary:INT64"
    sales:
      schema: "order_id:INT64,region:STRING,amount:INT64"
  sources:
    payroll: { plugin: filesystem, options: { path: "$QA/payroll.csv", schema: "employee:STRING,region:STRING,salary:INT64" } }
    sales:   { plugin: filesystem, options: { path: "$QA/sales.csv",   schema: "order_id:INT64,region:STRING,amount:INT64" } }
```

**The five registered views**, all registered by `ann` before the matrix runs:

| View | SQL | Keys | Rows |
|---|---|---|---|
| `payroll_view` | `SELECT employee, region, salary FROM payroll` | `0` | 3 |
| `secret_pay` | `SELECT employee, region, salary FROM payroll WHERE employee = 'ACC-0007'` | `0` | 1 |
| `hr_summary` | `SELECT employee, region, salary FROM payroll WHERE salary >= 0` | `0` | 3 |
| `sales_view` | `SELECT order_id, region, amount FROM sales` | `0` | 4 |
| `sales_total` | `SELECT region, SUM(amount) AS total FROM sales GROUP BY region` | `0` | 3 |

`secret_pay` is the laundering probe: a payroll query whose *name* says nothing about payroll and
whose *SQL text* carries the literal `'ACC-0007'`. `sales_total` is the row-filter soundness probe:
it aggregates `region` into the group key but a filter naming `order_id` cannot be enforced on it.

**The four principal classes.** Every matrix case names one of these; they are the rows of the
matrix, and they are the reason a "pass" means something.

| Class | Principal | Credential | What the policy says |
|---|---|---|---|
| **anon** | `Principal.ANONYMOUS` | none | nothing was verified |
| **allowed** | `ann`, tenant `hr`, roles `[analyst]` | `ann-token-aaaa` | reads everything, no filter |
| **denied** | `carol`, tenant `ops`, roles `[viewer]` | `carol-token-cccc` | denied any view or stream whose name contains `payroll` |
| **filtered** | `bob`, tenant `eu`, roles `[analyst]` | `bob-token-bbbb` | reads everything **with row filter `region = 'EU'`** |

**The nodes.** Started once per group, killed by PID at the end of it.

| Node | Configuration | HTTP | Flight |
|---|---|---|---|
| `N-open` | `--spring.profiles.active=dev` (authentication `none`, policy `permissive`, anonymous acknowledged) | 18620 | 19620 |
| `N-perm` | `authentication: token`, `policy: permissive`, three tokens | 18621 | 19621 |
| `N-auth` | `authentication: token`, `policy: authenticated`, three tokens | 18622 | 19622 |
| `N-tls` | as `N-auth` plus `flight.tls.certificate` + `.key` | 18623 | 19623 |
| `N-qa` | the QA harness: `QueryRegistry` + `PravahaFlightServer` built on the **released jars** with `QaPolicy` and a `StaticTokenVerifier` holding the three tokens | — | 19624 |

`QaPolicy` is a QA-only `SecurityPolicy` in the scratch directory implementing the rule table above.
It exists because **no server node can be given a policy with rules** (SECX-004); it is compiled
against the released jars and no production source is touched.

**HTTP cases name both halves.** A server node's HTTP surface cannot be given `QaPolicy`, so every
HTTP matrix case records two answers: what HTTP answered on `N-auth` for that token, and what
Flight answered for the *same token* on `N-qa`. **A difference is the control. Identity is the
defect** — it means the HTTP surface decided without asking who was calling.

---

## Group A — the configuration ceiling: what rules this node can be given at all

Nine cases. Round 1 proved the startup refusal cannot be bypassed in eleven tries; these ask the
next question, which is what the operator gets to configure *after* the node agrees to start.

## SECX-001 — the policy × authentication × allow-anonymous grid, enumerated
**Intent:** three settings, and the whole of a deployment's posture is decided by their twelve
combinations. Round 1 tested six of them one at a time. Enumerate all twelve so that the cells that
start an open server are a list rather than an anecdote.
**Falsifier:** any cell whose outcome is "starts, and answers an unauthenticated caller with data"
that is not one of the three the documentation names as deliberately open.
**Setup:** the released `pravaha-server-*-app.jar` plus `$QA/streams.yaml`, HTTP 18625, Flight 19625.
**Steps:** for each cell, start the node with exactly those three overrides, record exit status, the
`security: authentication=…, policy=…` summary line, whether anything is bound on 18625/19625, and
the result of `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18625/api/v1/status` with no
credential. Kill by PID.

| # | `authentication` | `policy` | `allow-anonymous` | Expected |
|---|---|---|---|---|
| 1 | none | permissive | false | **refuses**, `PRV-7002`, nothing bound |
| 2 | none | permissive | true | starts open; status 200 unauthenticated |
| 3 | none | authenticated | false | **refuses**, `PRV-7002` contradiction message |
| 4 | none | authenticated | true | **refuses** — the acknowledgement must not unlock the contradiction |
| 5 | token (0 tokens) | permissive | false | starts; status 401 (`rejectAll`) |
| 6 | token (0 tokens) | permissive | true | starts; status 401 — `allow-anonymous` must not re-open an authenticating node |
| 7 | token (0 tokens) | authenticated | false | starts; status 401 |
| 8 | token (0 tokens) | authenticated | true | starts; status 401 |
| 9 | token (3 tokens) | permissive | false | starts; 401 without a credential, 200 with `ann-token-aaaa` |
| 10 | token (3 tokens) | permissive | true | starts; same as 9 |
| 11 | token (3 tokens) | authenticated | false | starts; same as 9 |
| 12 | token (3 tokens) | authenticated | true | starts; same as 9 |
**Expected:** exactly cells 2, 6, 8, 10 and 12 involve `allow-anonymous=true`, and **only cell 2**
serves an unauthenticated caller. Cells 1, 3 and 4 exit non-zero with nothing bound. Any other cell
returning 200 to a credential-less request is a finding, with its cell number.
**Vacuity:** cells 9–12 are the control: they prove the 401s in 5–8 come from the filter refusing a
credential rather than from a node that answers nobody. If cell 9 does not return 200 with a valid
token, every 401 above it is uninterpretable and the whole grid is void.

## SECX-002 — `authentication` accepts anything and means `none` for all but one spelling
**Intent:** `SecurityProperties.authenticates()` is `"token".equalsIgnoreCase(authentication)`. The
adjacent `policy` key trims *and* refuses an unknown value; this one does neither. Known (E-4); what
is not known is how large the set of silently-open spellings is, and whether any of them survives
the startup guard.
**Falsifier:** a value a reasonable operator would write that starts a node serving data to
unauthenticated callers while the startup summary does not say `authentication=none`.
**Setup:** `N`-style node on 18626/19626, `policy: permissive`, `allow-anonymous: true` (so the only
thing under test is the spelling), three tokens configured.
**Steps:** for each of `token`, `TOKEN`, `Token`, `" token"`, `"token "`, `"token\t"`, `tokens`,
`bearer`, `Bearer`, `oidc`, `jwt`, `basic`, `""`, `none`, `NONE`, `0`, `true`: start the node, read
the `security: authentication=…` summary line, then `curl` `/api/v1/streams` with no credential.
**Expected:** `token`, `TOKEN` and `Token` authenticate (401 without a credential). **Every other
value** — including the two with surrounding whitespace, which `policy` would have accepted — yields
`authentication=none` and a 200. Record the full list. The defect is that seventeen spellings
produce two behaviours with no diagnostic; the fix is the refusal `policy` already has.
**Vacuity:** `token` and `none` are both in the list, so the case cannot pass by every value
behaving identically.

## SECX-003 — `policy` refuses an unknown value; `audit` refuses an unknown value; neither refuses a *class*
**Intent:** the three `pravaha.security.*` string keys are parsed by three switches with three
different error behaviours. Establish which are fail-closed.
**Falsifier:** a value that reaches a `default` arm and produces a working node rather than a refusal.
**Setup:** node on 18627/19627.
**Steps:** start with each of `policy=permissive|authenticated|authenticated-only|AUTHENTICATED|"
authenticated "|tenant|com.example.MyPolicy|""` and, separately, `audit=none|memory|log|LOG|
"memory "|syslog|""`.
**Expected:** `policy`: the first five start (`authenticated-only` is the documented alias); `tenant`
— which `SecurityProperties`' own javadoc names as a valid value — refuses with `PRV-7002`; a class
name refuses; empty refuses or defaults to `permissive`, recorded either way. `audit`: `none` and
`memory` start; `log` — also named in the javadoc — refuses with `PRV-7002`; `"memory "` refuses,
because `auditSink()` trims but `SecurityProperties` documents no such contract. Two values named in
the code's own documentation are rejected by the code: that is the finding.
**Vacuity:** `permissive` and `none` in the same runs are the controls.

## SECX-004 — no seam exists for a `SecurityPolicy` with rules — enumerated, not asserted
**Intent:** SEC-061 established the ceiling by reading the switch. Establish it by trying every
mechanism a Spring Boot operator would reach for, so the finding is "there is no seam" rather than
"the property does not take a class name".
**Falsifier:** any one of the six attempts producing a node whose `mayRead` ever returns a deny or a
row filter for an authenticated caller.
**Setup:** node on 18628/19628, `authentication: token`, three tokens.
**Steps:** (1) `--pravaha.security.policy=<fully-qualified QaPolicy class name>`; (2) a jar on the
classpath declaring `META-INF/services/com.ash.messaging.pravaha.security.SecurityPolicy`;
(3) an `@Bean SecurityPolicy` in an additional `@Configuration` supplied by
`--spring.main.sources=`; (4) `--pravaha.security.policy=tenant`, the value the javadoc names;
(5) `--pravaha.security.policy=authenticated` and then a `carol` read of `payroll_view` on Flight;
(6) grep `PravahaNode`'s constructor and `PravahaServerApplication`'s beans for any
`SecurityPolicy` or `ObjectProvider<SecurityPolicy>` parameter.
**Expected:** (1) `PRV-7002 … is not a policy this node knows`; (2) starts and ignores the service
file — `ServiceLoader` is never consulted for a policy; (3) the bean is constructed and unused,
`PravahaNode` taking no such parameter; (4) refuses; (5) `carol` reads `payroll_view` and receives
all 3 rows summing to `99000 + 250000 + 55000 = 404000`; (6) zero hits. The conclusion to record:
**requirement 3 is unachievable by configuration on either transport**, and every row-filter case in
this file is therefore reachable only through `N-qa`.
**Vacuity:** step (5) is the non-vacuous half. If `carol` were refused, the ceiling would not exist
and steps 1–4 would be about ergonomics rather than about enforcement.

## SECX-005 — the row-filter machinery is reachable in the harness and from nowhere else
**Intent:** the mirror of SECX-004. If `QaPolicy` on `N-qa` produces filtered reads, then the code is
present and only the configuration seam is missing — which is a different (and cheaper) defect than
a filter that does not work.
**Falsifier:** `bob`'s filtered read on `N-qa` returning 4 rows from `sales_view`.
**Setup:** `N-qa` on 19624 with the five views registered.
**Steps:** `bob` reads `SELECT order_id, region, amount FROM sales_view`; `ann` reads the same.
**Expected:** `ann` gets 4 rows, `SUM(amount) = 100 + 250 + 150 + 300 = 800`. `bob` gets 2 rows
(`order_id` 1 and 3), `SUM(amount) = 100 + 150 = 250`. The machinery works; SECX-004 is a wiring
defect, not an implementation gap.
**Vacuity:** `ann`'s 4 rows are the control. A `bob` result of 2 rows means nothing unless the
unfiltered answer over the same data at the same moment is 4.

## SECX-006 — two policy holders, one node: `requireOnePolicy` under every ordering
**Intent:** `authorizedBy` and `hosting` each call `requireOnePolicy`, and the check is *identity*.
The ordering bug that made `policy=authenticated` unstartable was here. Enumerate the four orderings
so the guard's coverage is a fact.
**Falsifier:** any ordering that starts a server whose registry and producer hold different policy
objects.
**Setup:** the harness, driving `PravahaFlightServer` directly on 19629.
**Steps:** build a registry on `QaPolicy`, then start the server four ways: (a) `authorizedBy` then
`hosting`; (b) `hosting` then `authorizedBy`; (c) `hosting` only; (d) `authorizedBy` with a
*different* `QaPolicy` instance then `hosting`. For each, if it starts, have `carol` read
`payroll_view` and `register` a query over `payroll`.
**Expected:** (a) and (b) start and `carol` is refused both verbs — the guard is order-independent.
(c) starts with the producer on the constructor's `PERMISSIVE`: `carol`'s **read** succeeds while her
**registration** is refused, which is the split model the guard exists to prevent, and it is
reachable by simply not calling `authorizedBy`. (d) refuses with `PRV-7002 … different SecurityPolicy
instances`. Record (c): the guard only fires when a policy was supplied to one of the two, so
supplying it to *neither* the server nor the registry is unguarded, and "pass it to neither and let
both default" — which the message recommends — is not what (c) does.
**Vacuity:** (d) is the control for the guard firing at all; (a) is the control for the node being
usable when it does not.

## SECX-007 — a token table is a token table: what `StaticTokenVerifier` accepts as an entry
**Intent:** the map **key** is the credential. Everything about that map is therefore security
surface: duplicate principals, blank keys, keys with whitespace, a key that is also a YAML boolean,
and a principal resolving to anonymous.
**Falsifier:** any table that starts a node and then authenticates a credential the operator did not
intend, or that starts with a credential nobody can present.
**Setup:** node on 18630/19630, `authentication: token`.
**Steps:** start with each table and then attempt `GET /api/v1/streams` with the literal key:
(a) `{"": {id: x}}`; (b) `{" ": {id: x}}`; (c) `{"tok": {id: ""}}`; (d) `{"tok": {id: anonymous}}`;
(e) `{"tok": {id: ann}, "tok2": {id: ann}}` — two credentials, one identity;
(f) `{"yes": {id: x}}` and `{"on": {id: x}}` — YAML 1.1 booleans as map keys;
(g) `{" tok ": {id: x}}` presented as `Bearer  tok ` and as `Bearer tok`;
(h) a 4 KiB key; (i) a key containing `"` and a newline.
**Expected:** (a), (b) refuse at startup — `StaticTokenVerifier.and` throws on a blank token.
(c) refuses — `Principal`'s constructor rejects a blank id. (d) refuses — `and` rejects the anonymous
principal by name. (e) starts; both tokens authenticate as `ann`, and the audit log cannot tell them
apart, which is recorded as the consequence. (f) records how the key is parsed — `true`/`on` becoming
a boolean key is a credential the operator cannot type back. (g) the header is `strip()`ped on both
transports, so `Bearer tok` must **not** authenticate against a key of `" tok "`; record which does.
(h), (i) authenticate exactly and nothing is echoed anywhere.
**Vacuity:** a known-good single-entry table on the same node is the control for every "refuses".

## SECX-008 — `pravaha.security.tokens` is a map whose keys are secrets: where do they surface?
**Intent:** Spring's actuator sanitiser masks *values*. Here the secret is the **key**. Round 1 asked
whether the endpoints are exposed; this asks where else a map key travels.
**Falsifier:** the literal string `ann-token-aaaa` appearing in any artefact a non-operator can read.
**Setup:** `N-auth`, started with a distinctive token `SECXMARKER-aaaa-token`. Make one successful and
one failed call on each transport, register and drop a query, and trigger a startup refusal.
**Steps:** grep for `SECXMARKER` in: the server log (stdout and any file appender); the journal file;
the checkpoint directory; `/actuator/env`, `/actuator/configprops`, `/actuator/heapdump`,
`/actuator/health`, `/actuator/info`, `/api/v1/openapi.json`, `/status`, `/api/v1/status`; the 401
body; the Flight `UNAUTHENTICATED` description; a thread dump taken with `jcmd`; and the
`ApiExceptionHandler`'s 500 body after forcing a bind failure with a bad token spec.
**Expected:** zero matches in every readable artefact. The heap dump and `/actuator/env` are expected
to be 404 (not exposed); if either answers, the finding is CRITICAL and the marker will be in it.
**Vacuity:** the marker is known to be *in the process*, so a zero result means the grep is
finding-capable only if the same grep over the configuration file that supplied it returns a match.
Run that as the positive control.

## SECX-009 — the startup summary line is the operator's only view of the posture, and it can lie
**Intent:** `security: authentication={}, policy={}, audit={}, flight transport={}` is what an
operator reads to confirm a hardening change. Three of its four fields print the *configured string*
rather than the object that was built.
**Falsifier:** a node whose summary line differs from its behaviour in any field.
**Setup:** five nodes on 18631/19631, one at a time.
**Steps:** (a) `authentication: tokens` (typo) — compare the line against the 200 an anonymous caller
gets; (b) `policy: AUTHENTICATED` — the line prints the raw value, the object is
`AuthenticatedOnlyPolicy`; (c) `audit: memory` — the line says `memory`; ask any surface for an audit
record (SECX-089); (d) `tls.key` set, `certificate` absent — the line says `PLAINTEXT` while the
operator asked for TLS; (e) `authentication: token` with an empty token map — the line says `token`
while the verifier is `rejectAll`.
**Expected:** (a) the line reads `authentication=tokens` and the node is open — the field echoes a
string that means nothing. (b) cosmetic but recorded. (c) `audit=memory` is true and useless.
(d) `PLAINTEXT` is *accurate* and gives no hint that TLS was requested and dropped — the one word
that would have saved SEC-059. (e) `token` is true and the node accepts nobody. Recommendation to
record: print the constructed objects, and print `TLS-REQUESTED-BUT-INCOMPLETE` for (d).
**Vacuity:** (b) and (e) are honest lines; the case cannot pass by every line being wrong.

---

## Group B — the Flight matrix: eight verbs × four principal classes

Thirty-two cases, `SECX-010`–`SECX-041`. This is the matrix round 1 sampled. Every case runs on
**`N-qa`** (19624), the only node on which all four principal classes exist, with the five views
registered by `ann`. Where the same verb behaves differently on a real `policy=authenticated` node,
the case says so and runs the comparison on `N-auth` (19622).

Read the group as a grid:

| verb ↓ / class → | anon | allowed (`ann`) | denied (`carol`) | filtered (`bob`) |
|---|---|---|---|---|
| register | 010 | 011 | 012 | 013 |
| list (`queries`) | 014 | 015 | 016 | 017 |
| read (`getFlightInfo` + `getStream`) | 018 | 019 | 020 | 021 |
| prepared statement | 022 | 023 | 024 | 025 |
| subscribe | 026 | 027 | 028 | 029 |
| drop | 030 | 031 | 032 | 033 |
| pause | 034 | 035 | 036 | 037 |
| resume | 038 | 039 | 040 | 041 |

### register

## SECX-010 — register, anonymous
**Intent:** `mayRegisterQuery`'s default denies anonymous, and `AuthenticatedOnlyPolicy` overrides it
to the same answer. The order of checks matters: a refusal that happens after planning has already
spent the node's time on an unauthenticated caller.
**Falsifier:** a registration that succeeds, or one that is refused only after the plan is built.
**Setup:** `N-qa` with a verifier attached, so an anonymous call is one with no `authorization` header.
**Steps:** `pravaha register --name anon_v --sql "SELECT order_id, region, amount FROM sales" --keys 0
--url grpc://127.0.0.1:19624` with no `--token`. Repeat with the SQL replaced by a 200-clause
predicate that is expensive to plan, and time both.
**Expected:** `PRV-7001` from `PrincipalMiddleware`, before the producer runs. The two timings agree
within noise, proving the refusal is not behind the planner. `pravaha queries --token ann-token-aaaa`
afterwards still shows exactly the five fixture views.
**Vacuity:** SECX-011 registers the identical SQL successfully, so the refusal is not the SQL's.

## SECX-011 — register, allowed
**Intent:** the control for the whole register row, and the check that `mayRead` is consulted for each
**source stream**, not for the name the client chose.
**Falsifier:** `ann`'s registration failing, or succeeding without an audit record naming `sales`.
**Setup:** `N-qa`, `audit` = the harness's `AuditSink.InMemory`.
**Steps:** `pravaha register --name ann_sales --sql "SELECT order_id, region, amount FROM sales"
--keys 0 --token ann-token-aaaa`. Then read the harness's audit events.
**Expected:** `registered ann_sales state=RUNNING fingerprint=<12 hex>`. Two audit events, in order:
`register`/`ann_sales`/ALLOW and `register:source`/`sales`/ALLOW. `pravaha query --sql "SELECT
order_id, region, amount FROM ann_sales"` returns 4 rows, `100 + 250 + 150 + 300 = 800`.
**Vacuity:** the audit events are what distinguish "the policy allowed it" from "the policy was never
asked". Remove the `register:source` loop and this case still returns 4 rows — so the event assertion
is the load-bearing half.

## SECX-012 — register, denied
**Intent:** registration is the one place lineage *is* known. Check that it is checked, and that the
refusal names the stream rather than the view.
**Falsifier:** `carol` registering anything that reads `payroll`, under any name.
**Setup:** `N-qa`.
**Steps:** as `carol`, register each of: (a) `SELECT employee, region, salary FROM payroll` as
`carol_1`; (b) the same as `payroll_copy`; (c) `SELECT p.employee FROM payroll p` as `carol_2`;
(d) `SELECT employee FROM payroll UNION ALL SELECT employee FROM payroll` as `carol_3`;
(e) `SELECT s.order_id FROM sales s JOIN payroll p ON s.region = p.region` as `carol_4`;
(f) `SELECT order_id, region, amount FROM sales` as `carol_ok`.
**Expected:** (a)–(e) refused with `PRV-7002 … because it reads 'payroll'`, the message naming
`payroll` and not the view name. (f) succeeds. Case (e) is the one worth the trouble: a join reaches
`sourceStreams(plan)` with two entries, and if only the first is checked, a `payroll` join lands.
**Vacuity:** (f) is the control — `carol` may register, so (a)–(e) are refused for the stream and not
for the principal.

## SECX-013 — register, filtered
**Intent:** `read.rowFilter().ifPresent(rowFilters::add)` puts the principal's filters in the
fingerprint. Two principals with different entitlements must not share one copy of the state.
**Falsifier:** `bob`'s registration of SQL identical to `ann`'s returning `ann`'s fingerprint.
**Setup:** `N-qa`. `ann` has already registered `ann_sales` (SECX-011).
**Steps:** `bob` registers the byte-identical SQL as `bob_sales`. Compare the two fingerprints. Then
have a second filtered principal `bob2` (same filter `region = 'EU'`) register the same SQL as
`bob2_sales` and compare again. Then read all three views as their own owners.
**Expected:** `fingerprint(ann_sales) != fingerprint(bob_sales)`. `fingerprint(bob2_sales) ==
fingerprint(bob_sales)` — identical filters share, which is the property that makes the first half
meaningful rather than merely "different principals differ". `ann_sales` reads 4 rows / 800;
`bob_sales` reads 2 rows / `100 + 150 = 250`.
**Vacuity:** if the filter were dropped from the fingerprint, `bob2_sales` would still equal
`bob_sales` — so the second comparison alone would pass with the feature removed. Both comparisons
are required, and this is stated because round 1's version of this case had only the first.

### list — the `queries` verb

## SECX-014 — list, anonymous
**Intent:** the set of view names is a map of the deployment. Round 1 fixed the unauthenticated case;
confirm it under a verifier and confirm nothing else answers in its place.
**Falsifier:** any view name reaching a caller with no credential.
**Setup:** `N-qa` with the verifier attached.
**Steps:** `pravaha queries` with no token; then the same against `N-auth` (19622).
**Expected:** `PRV-7001` from the middleware on both, with no names in the message.
**Vacuity:** SECX-015 lists five views with a token on the same node.

## SECX-015 — list, allowed
**Intent:** the control, and the measurement of what a listing discloses when it is allowed: name,
state, **full SQL text**, fingerprint and row count.
**Falsifier:** a listing that omits a view `ann` may read.
**Setup:** `N-qa` (19624) with the five fixture views registered by `ann`, and the harness audit sink drained.
**Steps:** `pravaha queries --token ann-token-aaaa`.
**Expected:** five rows — `payroll_view`, `secret_pay`, `hr_summary`, `sales_view`, `sales_total` —
and the SQL column of `secret_pay` contains the literal `'ACC-0007'`. Record that the SQL text is on
the wire at all: `application.yaml` says bound values are to be permissioned like data.
**Vacuity:** this is the control for SECX-016.

## SECX-016 — list, denied — the extent of the name-keyed filter, measured
**Intent:** known broken (SEC-043). What is *not* known is how much of the registry a denied
principal sees once the views are named the way a real deployment names them. Measure it as a
fraction rather than as an anecdote.
**Falsifier:** none — this case measures. It fails to be useful only if `carol` sees zero payroll
queries, which would mean the filter had been fixed.
**Setup:** `N-qa` with the five fixture views plus five more registered by `ann`, all reading
`payroll`, named the way a bank names things: `comp_q3`, `headcount`, `band_5_only`, `exec_pay`,
`payroll_audit`.
**Steps:** `pravaha queries --token carol-token-cccc`. Count how many of the ten are shown and how
many read `payroll`.
**Expected:** the filter hides exactly the two whose names contain `payroll` (`payroll_view`,
`payroll_audit`). The other **six** payroll-reading views are listed with their SQL text:
`6 / 8 = 75%` of the payroll queries `carol` is denied are disclosed to her, including `secret_pay`
with its `'ACC-0007'` literal. Record the percentage; it is the number that makes "the filter keys on
a client-chosen label" concrete.
**Vacuity:** `sales_view` and `sales_total` must be listed — `carol` is entitled to them — or the
listing is empty for an unrelated reason.

## SECX-017 — list, filtered
**Intent:** a row filter is an *allow*. `mayRead(bob, name).allowed()` is true, so `bob` sees every
name. The question is whether a listing tells a filtered principal anything about the rows behind the
names — it prints `ROWS IN`.
**Falsifier:** `bob`'s listing carrying a row count that reveals rows outside his filter.
**Setup:** `N-qa` with the five fixture views; `sales_view` holds 4 rows and `bob` is filtered to `region = 'EU'`.
**Steps:** `pravaha queries --token bob-token-bbbb`; compare `ROWS IN` against `ann`'s listing.
**Expected:** identical listings, including `ROWS IN` — `sales_view` reports 4 while `bob` may read
2. A filtered principal learns the unfiltered cardinality of every view on the node. Record as a
disclosure channel that the `LIST` filter's design does not consider, because `rowFilter` is not
consulted in the `LIST` arm at all.
**Vacuity:** `bob`'s own read returning 2 rows (SECX-005) is the comparison that makes `4` a leak
rather than a number.

### read — `getFlightInfo` + `getStream` over a view

## SECX-018 — read, anonymous
**Intent:** a read is the verb that returns rows. Establish that neither of Flight's two legs answers an unauthenticated caller, including on a ticket that was minted for somebody who was authenticated.
**Falsifier:** any row, or any schema, reaching an unauthenticated caller.
**Setup:** `N-qa` with the verifier attached, `sales_view` holding 4 rows, and a ticket captured from SECX-019's successful call.
**Steps:** with no token: `pravaha query --sql "SELECT order_id, region, amount FROM sales_view"`;
then a raw `getFlightInfo` for the same SQL; then a raw `getStream` on a ticket captured from
SECX-019's successful call and replayed with no credential.
**Expected:** `PRV-7001` on all three. The replayed ticket is the one that matters: the ticket
carries the SQL and nothing else, so if the middleware did not run, a captured ticket would be a
bearer credential of its own.
**Vacuity:** SECX-019 is the same ticket with a credential.

## SECX-019 — read, allowed
**Intent:** the control for the read row of the matrix, and the fixture's arithmetic pinned once so the three cells below it are differences from a known number.
**Falsifier:** `ann` refused, or a row count or total that differs from the fixture's -- in which case every cell below is being compared against the wrong baseline.
**Setup:** `N-qa`; `sales_view` holds 4 rows and `payroll_view` holds 3; the harness audit sink drained before the run.
**Steps:** `ann` reads `sales_view` and `payroll_view`.
**Expected:** 4 rows / `800` and 3 rows / `404000` respectively. Audit records two `query` ALLOW
events naming `sales_view` and `payroll_view`.
**Vacuity:** the control for 018, 020, 021.

## SECX-020 — read, denied
**Intent:** the read row's denied cell. SEC-057 is known; what this case adds is the ratio on a realistically-named set of views, which is what turns "a name-keyed decision" into a severity.
**Falsifier:** `carol` refused on all four names. That would mean the decision had been given lineage and this row of the matrix had been fixed.
**Setup:** `N-qa` with `payroll_view`, `secret_pay`, `hr_summary` and `sales_view` registered by `ann`.
**Steps:** `carol` reads `payroll_view`, then `secret_pay`, then `hr_summary`, then `sales_view`.
**Expected:** `payroll_view` refused `PRV-7002 … carol may not read 'payroll_view'`. `sales_view`
returns 4 rows. **`secret_pay` and `hr_summary` return payroll rows** — 1 row (`ACC-0007`, `EU`,
`99000`) and 3 rows summing to `404000` — because the decision is taken on the name. This is SEC-057
measured on the fixture; the number to record is that `3 / 4` payroll views are readable by the
principal denied payroll.
**Vacuity:** the `payroll_view` refusal in the same run is the control that `carol` is genuinely
denied; without it the three successes could be a policy that was never installed.

## SECX-021 — read, filtered
**Intent:** the filter is ANDed into the plan above the scan. Check it on every operator shape the
injector handles, because `injectAboveScan` has one arm per operator and an unhandled arm refuses.
**Falsifier:** any row outside `region = 'EU'` reaching `bob`.
**Setup:** `N-qa`; `sales_view` (4 rows) and `sales_total` (3 rows, `region` in the group key); `bob` filtered to `region = 'EU'`.
**Steps:** `bob` reads: (a) `SELECT order_id, region, amount FROM sales_view` (scan → project);
(b) `SELECT order_id FROM sales_view WHERE amount > 120` (scan → filter → project);
(c) `SELECT order_id, amount * 2 AS d FROM sales_view` (compute);
(d) `SELECT region, SUM(amount) AS t FROM sales_view GROUP BY region` (aggregate);
(e) `SELECT * FROM sales_total` (a view that already aggregated `region` into the key).
**Expected:** (a) 2 rows, `100 + 150 = 250`. (b) 1 row — of the EU rows, only `order_id 3` has
`amount = 150 > 120`; the security filter must be applied **below** the user's filter, and if it were
applied above it the answer would be `order_id 2` (`amount = 250`, US), which is the failure this
case is shaped to catch. (c) 2 rows, `d` = `200` and `300`. (d) 1 row, `EU, 250` — not three rows.
(e) `sales_total` *carries* `region`, so the filter is enforceable: 1 row, `EU, 250`.
**Vacuity:** `ann` running (a)–(e) returns 4 / 2 / 4 / 3 / 3 rows respectively. Every assertion here
is a difference from that column; with the filter removed, `bob`'s five answers become `ann`'s five.

### prepared statements

## SECX-022 — prepared statement, anonymous
**Intent:** ADR-032's path has three round trips — `createPreparedStatement`, `doPut` of the
parameters, `getStream` — and the handle travels on the client. Each leg must authenticate.
**Falsifier:** any leg succeeding without a credential.
**Setup:** `N-qa` with the verifier attached, and an unbound and a bound handle captured from SECX-023.
**Steps:** with no token, call `createPreparedStatement("SELECT order_id FROM sales_view WHERE region
= ?")`; then replay a handle captured from SECX-023 into `doPut` and `getStream` with no credential.
**Expected:** `PRV-7001` on all three legs. The replayed **bound** handle is the important one: it
carries the parameter values, so a handle that authenticates by existing would be a capability token.
**Vacuity:** SECX-023 is the same three legs with a credential.

## SECX-023 — prepared statement, allowed
**Intent:** the control for the prepared-statement row, and a check of ADR-032's claim that both schemas come back before any value is bound.
**Falsifier:** a fetch returning a row count other than 2, or either schema arriving only after the parameters are bound.
**Setup:** `N-qa`; `sales_view` holding the 4 fixture rows.
**Steps:** `ann` prepares `SELECT order_id, amount FROM sales_view WHERE region = ?`, binds `'EU'`,
fetches.
**Expected:** the dataset schema is `[order_id: Int(64), amount: Int(64)]` and the parameter schema
has one UTF-8 field, both returned before any value is bound. The fetch returns 2 rows,
`100 + 150 = 250`.
**Vacuity:** control.

## SECX-024 — prepared statement, denied
**Intent:** `prepare` and `execute` each call `mayRead` — the javadoc says authorization is
deliberately not frozen at preparation. Check both, and check the cached-plan path
(`authorized(prepared, …)`), which is a third call site.
**Falsifier:** a `carol` fetch returning payroll rows after a refusal at prepare, or a prepare
succeeding because `ann` had already warmed the plan cache.
**Setup:** `N-qa`. First have `ann` prepare `SELECT employee, salary FROM payroll_view WHERE region =
?` so the plan is in the cache.
**Steps:** `carol` prepares the same SQL; then `carol` prepares `SELECT employee, salary FROM
secret_pay WHERE region = ?`, binds `'EU'`, fetches.
**Expected:** the first is refused `PRV-7002` — the cache must not launder the decision. The second
**succeeds** and returns the `ACC-0007` row (`EU`, `99000`), because the name is `secret_pay`. Both
halves are findings: the cache is correctly re-authorized, and the thing it re-authorizes is the
wrong question.
**Vacuity:** `ann`'s prepare of the first SQL in the same run must succeed, or the refusal is about
the plan cache rather than about `carol`.

## SECX-025 — prepared statement, filtered
**Intent:** the filter must be injected into the **prepared** plan on every execution, not once.
**Falsifier:** a second execution of the same handle returning unfiltered rows.
**Setup:** `N-qa`; `sales_view`; `bob` filtered to `region = 'EU'`.
**Steps:** `bob` prepares `SELECT order_id, amount FROM sales_view WHERE amount > ?`; binds `50`,
fetches; binds `50` again on the same handle, fetches; binds `0`, fetches.
**Expected:** all three fetches filtered. With `?` = 50: EU rows with `amount > 50` are `100` and
`150` → 2 rows, `250`. With `?` = 0: the same 2 rows, `250` — **not** 4 rows and **not** `800`.
**Vacuity:** `ann` with `?` = 0 gets 4 rows / `800`. A stateless handle that forgot the principal
would return `ann`'s answer on the second fetch, which is exactly what repeating the identical bind
is shaped to detect.

### subscribe

## SECX-026 — subscribe, anonymous
**Intent:** a subscription is the longest-lived call this node serves. An unauthenticated one is not a leak of a result but of every future result.
**Falsifier:** a change reaching a subscriber with no credential.
**Setup:** `N-qa` with the verifier attached; `sales_view` registered and running.
**Steps:** `pravaha subscribe --view sales_view --url grpc://127.0.0.1:19624` with no token; also a
raw `getStream` with a hand-built subscription ticket (`ControlWire` magic + `"subscribe"` + the view
name) and no credential.
**Expected:** `PRV-7001` on both, from the middleware, before `requireRegistry()`.
**Vacuity:** SECX-027.

## SECX-027 — subscribe, allowed
**Intent:** the control for the subscribe row, and the proof that a subscription delivers changes as they commit rather than replaying a snapshot and closing.
**Falsifier:** the appended row never arriving, or arriving before it is appended.
**Setup:** `N-qa`; `sales_view`; write access to `$QA/sales.csv` so a row can be appended mid-stream.
**Steps:** `ann` subscribes to `sales_view --limit 4`; append a fifth row `5,EU,400` to
`$QA/sales.csv` while the subscription is open.
**Expected:** four changes for the fixture rows and then the fifth, `5 EU 400`, delivered as an
insert. Audit records one `subscribe` ALLOW naming `sales_view`.
**Vacuity:** the appended row is what makes this a subscription rather than a scan. Without it the
case would pass against a `getStream` that replayed the view once and closed.

## SECX-028 — subscribe, denied — and the `require()`-before-`mayRead` ordering
**Intent:** known (SEC-058/P-2): `required.require(viewName)` runs before `policy.mayRead`. Measure
what that hands a denied principal, and check the ordering directly rather than inferring it.
**Falsifier:** the refusal for an existing-but-denied view being distinguishable from the refusal for
a name that does not exist.
**Setup:** `N-qa` with all five fixture views; `carol` denied any name containing `payroll`.
**Steps:** as `carol`: (a) `subscribe --view payroll_view`; (b) `subscribe --view zzz_nope`;
(c) `subscribe --view sales_view --limit 1`.
**Expected:** (a) `PRV-7002 … carol may not subscribe to 'payroll_view'`. (b) `PRV-8002 no query
named 'zzz_nope' is registered; this node has [payroll_view, secret_pay, hr_summary, sales_view,
sales_total]` — **all five names**, including the one (a) just refused to admit exists. (c) succeeds
with one change. Two findings, both measured on the same principal in the same minute: the
enumeration, and the existence oracle formed by (a) and (b) being different shapes.
**Vacuity:** (c) proves `carol` can subscribe at all, so (a) is a policy refusal.

## SECX-029 — subscribe, filtered — the fail-closed refusal, and what it costs
**Intent:** the subscribe path refuses a conditional entitlement rather than over-serving. Verify the
refusal, and then verify the workaround its own message recommends actually works — a refusal whose
advice is wrong is a defect (see Y-5).
**Falsifier:** a single `region != 'EU'` change reaching `bob`.
**Setup:** `N-qa`; `sales_view`; `QaPolicy` extended with an unconditional grant for `bob` on `sales_eu`; write access to `$QA/sales.csv`.
**Steps:** (a) `bob` subscribes to `sales_view`; (b) follow the message's first suggestion — `bob`
reads the view instead (SECX-021a); (c) follow its second — `ann` registers
`sales_eu` = `SELECT order_id, region, amount FROM sales WHERE region = 'EU'` and the policy grants
`bob` unconditional access to `sales_eu`; `bob` subscribes to it and a row `6,US,600` is appended to
the source.
**Expected:** (a) `PRV-7002 … a subscription cannot enforce a filter`, no rows. (b) 2 rows, `250`.
(c) `bob` receives only EU changes; the appended `6,US,600` never arrives, because the *view* excludes
it. All three of the message's claims hold.
**Vacuity:** in (c), an EU row appended after the US one (`7,EU,700`) must arrive, or the absence of
the US row is a dead subscription rather than a filtered one.

### drop, pause, resume

The three administrative verbs share one gate, `requireAdministrable` → `mayAdminister` → default
`mayRead`. They are enumerated separately because they are three switch arms and because only `drop`
destroys state.

## SECX-030 — drop, anonymous
**Intent:** `drop` destroys accumulated state. An unauthenticated drop is data loss caused by a caller the node never identified.
**Falsifier:** any query's state destroyed by a caller with no credential.
**Setup:** `N-qa` with the verifier attached; `sales_view` `RUNNING` with 4 rows in.
**Steps:** with no token, `pravaha drop --name sales_view`; then `pravaha queries --token
ann-token-aaaa`.
**Expected:** `PRV-7001`; five views still registered; `sales_view` still answers 4 rows / `800`.
**Vacuity:** SECX-031 drops the same view successfully at the end of the group.

## SECX-031 — drop, allowed — and whether ownership is consulted at all
**Falsifier:** `ann` unable to drop her own query, or the view still answering after a successful drop.
**Intent:** the control, and the ownership question in its cleanest form. `ann` owns everything here,
so this case establishes only that the verb works; SECX-032/033 are where ownership would have
mattered.
**Setup:** `N-qa`; a throwaway view `ann_sales` registered by `ann`; the harness audit sink drained.
**Steps:** `ann` drops a throwaway view `ann_sales`; then `pravaha queries --token ann-token-aaaa`;
then `pravaha query --sql "SELECT order_id FROM ann_sales" --token ann-token-aaaa`.
**Expected:** `dropped ann_sales`; the view is gone from the listing; the read is refused with
`PRV-4023`/`PRV-2002`. Audit records a `drop` ALLOW.
**Vacuity:** the read-after-drop is what distinguishes "dropped" from "removed from a listing";
round 1 found a dropped view answering for ever.

## SECX-032 — drop, denied — and what the deny is keyed on
**Intent:** the deny works, and it works on the name. Establish both in one run so the mechanism and
its blind spot are the same evidence.
**Falsifier:** `carol` dropping `payroll_view`; or `carol` being *unable* to drop `secret_pay`, which
would mean the blind spot had been fixed.
**Setup:** `N-qa`; `payroll_view` and `secret_pay` both registered by `ann` and both reading `payroll`.
**Steps:** as `carol`: (a) `drop --name payroll_view`; (b) `drop --name secret_pay`. Then as `ann`,
`pravaha queries`.
**Expected:** (a) `PRV-7002 … carol may not drop 'payroll_view'`. (b) **`dropped secret_pay`** — a
principal denied payroll destroys a payroll query, its accumulated state and the view every other
client held a name for, because the name does not contain the word. `ann`'s listing afterwards is
missing `secret_pay`. The owning `Principal` was recorded at registration and journalled (SEC-044
proved it survives a replay) and `requireAdministrable` never looks at it.
**Vacuity:** (a) is the control for the gate being installed; without it (b) would be "no
authorization at all" rather than "authorization on the wrong key".

## SECX-033 — drop, filtered — read access is destroy access
**Intent:** the sharpest form of SEC-034. `bob` has never been permitted a whole row set of
`sales_view` in his life; `mayAdminister` defaults to `mayRead`, and `mayRead(bob, "sales_view")` is
an *allow with a filter*, whose `.allowed()` is `true`.
**Falsifier:** `bob` being refused. (That would be the fix.)
**Setup:** `N-qa`; `sales_view` registered by `ann`; `bob` filtered to `region = 'EU'`.
**Steps:** `bob` drops `sales_view`. Then `ann` reads it.
**Expected:** `dropped sales_view`; `ann`'s read refused. A principal entitled to 2 of 4 rows deleted
all 4 and the computation behind them. Record separately that `AccessDecision.allowed()` is the only
thing consulted and the `rowFilter` is discarded — a conditional allow is treated as an
unconditional administrative grant.
**Vacuity:** re-register `sales_view` and have `bob` *read* it (2 rows, `250`) to show the same
principal, same view, same policy call answering "partial" for a read and "total" for a drop.

## SECX-034 — pause, anonymous
**Intent:** `pause` is the quiet member of the administrative trio: it destroys nothing and silently stops every holder of the name from seeing new data. Establish that it is gated at the transport for an anonymous caller.
**Falsifier:** a `pause` accepted from a caller with no credential, or a view whose state changes.
**Setup:** `N-qa` with the verifier attached; `sales_view` `RUNNING`.
**Steps:** with no token, `pravaha pause --name sales_view --url grpc://127.0.0.1:19624`; then
append `13,EU,1300` to `$QA/sales.csv`; then `pravaha queries --token ann-token-aaaa` and
`pravaha query --sql "SELECT order_id, region, amount FROM sales_view" --token ann-token-aaaa`.
**Expected:** `PRV-7001` from the middleware. `sales_view` is still `RUNNING`, and the appended row
arrives: 5 rows, `100 + 250 + 150 + 300 + 1300 = 2100`.
**Vacuity:** the appended row is what distinguishes "the pause was refused" from "the pause
succeeded and nothing was flowing anyway". SECX-035 is the positive control for the verb itself.

## SECX-035 — pause, allowed
**Intent:** the control for the pause row, and the one pause case in this file that cannot pass because the source ran dry -- the failure round 1 recorded and this contract was written against.
**Falsifier:** the appended row appearing in the view while it is paused, or failing to appear after the resume.
**Setup:** `N-qa`; `sales_view`; write access to `$QA/sales.csv`.
**Steps:** `ann` pauses `sales_view`; append `8,EU,800` to `$QA/sales.csv`; wait 5s; read the view;
then `resume`; wait 5s; read again.
**Expected:** after the pause the read still returns 4 rows / `800` — the appended row is **not**
there. After the resume it returns 5 rows, `100 + 250 + 150 + 300 + 800 = 1600`. The CLI prints
`pauseped` / `resumeped` (P-7, cosmetic, still shipping).
**Vacuity:** the append is the whole case. A pause test that passes because the source ran dry is
exactly the round-1 failure this contract was written against; here the source is known to have an
unread row, and the resume proves it was waiting rather than lost.

## SECX-036 — pause, denied
**Intent:** pause's denied cell: the same name-keyed gate as `drop`, with a symptom nobody gets paged for.
**Falsifier:** `carol` refused on `secret_pay`, which would mean the gate had stopped keying on the name.
**Setup:** `N-qa`; `payroll_view` and `secret_pay` re-registered by `ann` after SECX-032.
**Steps:** `carol` pauses `payroll_view`, then `secret_pay` (re-registered after SECX-032).
**Expected:** refused / succeeded respectively, same key as SECX-032. A paused query serves stale
answers to everyone holding its name, silently, which is a denial of correctness rather than of
service.
**Vacuity:** the `payroll_view` refusal.

## SECX-037 — pause, filtered
**Intent:** pause's filtered cell. A conditional read entitlement becoming an unconditional power to freeze a view for everyone.
**Falsifier:** `bob` refused. That is the fix, and finding it here would close SECX-033 too.
**Setup:** `N-qa`; `sales_view` `RUNNING`; write access to `$QA/sales.csv`.
**Steps:** `bob` pauses `sales_view`; `ann` reads it; `bob` resumes.
**Expected:** `pauseped`; `ann`'s answer freezes at whatever the view held. Same defect as SECX-033
with a quieter symptom: no state destroyed, every reader silently stale.
**Vacuity:** `ann`'s answer must *change* after `bob`'s resume plus an appended row, or "frozen" is
indistinguishable from "idle".

## SECX-038 — resume, anonymous
**Intent:** `resume` is the only administrative verb that *restores* service, and it is gated on the same key as the two that remove it. Establish the anonymous cell.
**Falsifier:** a `resume` accepted with no credential, or a paused view returning to `RUNNING`.
**Setup:** `N-qa` with the verifier attached; a view already paused by `ann`.
**Steps:** `ann` pauses `sales_view`; with no token, `pravaha resume --name sales_view --url
grpc://127.0.0.1:19624`; append `14,EU,1400` to `$QA/sales.csv`; wait 5s; then as `ann`,
`pravaha queries` and a read of the view.
**Expected:** `PRV-7001`. The state is still `PAUSED` and the read still returns the pre-pause row
set -- 4 rows, `100 + 250 + 150 + 300 = 800` -- with the appended `1400` absent.
**Vacuity:** the absent row is the evidence the pause is still in force; SECX-039 then resumes the
same view and the `1400` arrives, which is what proves the row was waiting rather than lost.

## SECX-039 — resume, allowed
**Intent:** resume's allowed cell, asserted as a state transition and a delivered row rather than as an exit code.
**Falsifier:** `PAUSED` persisting after a successful `resume`, or the row appended during the pause never arriving.
**Setup:** `N-qa`; `sales_view` paused by `ann` with one row appended to `$QA/sales.csv` while it is paused.
**Steps:** covered mechanically by SECX-035's second half; run standalone against a view paused by
`ann` and confirm the state transition in `pravaha queries` is `PAUSED → RUNNING`.
**Expected:** `resumeped`; state `RUNNING`; the row appended while paused arrives.
**Vacuity:** as SECX-035.

## SECX-040 — resume, denied
**Intent:** resume's denied cell. Worth its own case because the asymmetry is instructive: the safe verb is gated exactly as tightly, and exactly as wrongly, as the destructive one.
**Falsifier:** `carol` refused on `secret_pay`.
**Setup:** `N-qa`; `payroll_view` and `secret_pay` both paused by `ann`.
**Steps:** `ann` pauses `payroll_view` and `secret_pay`; `carol` resumes each.
**Expected:** `payroll_view` refused; `secret_pay` resumed. The asymmetry is worth recording in its
own right: `carol` cannot resume a view she cannot read, so the *safe* verb is gated on the same key
as the destructive one, and the gate is equally wrong on both.
**Vacuity:** the refusal.

## SECX-041 — resume, filtered
**Intent:** resume's filtered cell, closing the eight-by-four grid.
**Falsifier:** `bob` refused.
**Setup:** `N-qa`; `sales_view` paused by `ann`.
**Steps:** `bob` resumes a view `ann` paused.
**Expected:** succeeds. Completes the grid: on all three administrative verbs the filtered class is
indistinguishable from the unrestricted one.
**Vacuity:** the state transition must be observable in `pravaha queries`.

---

## Group C — the HTTP matrix: four endpoint groups × four principal classes

Sixteen cases, `SECX-042`–`SECX-057`. Run on **`N-auth`** (18622), the closest a server node comes to
a closed configuration. Because no server node can be given a policy with rules (SECX-004), the
*denied* and *filtered* classes exist here only as **tokens whose principal the policy on `N-qa`
judges differently**. Every case therefore records two answers and compares them:

* **H** — what `N-auth`'s HTTP surface returned for that token.
* **F** — what `N-qa`'s Flight surface returned for the **same token** against the same data.

**H == F is the control. H ignoring the distinction F makes is the defect.** The filter is the only
thing on the HTTP path that knows a principal exists; `BearerTokenFilter` parks one on the request
as `pravaha.principal` and no controller reads it.

| endpoint group ↓ / class → | anon | allowed | denied | filtered |
|---|---|---|---|---|
| `GET /api/v1/streams`, `GET /api/v1/streams/{name}` | 042 | 043 | 044 | 045 |
| `POST /api/v1/streams` | 046 | 047 | 048 | 049 |
| `POST /api/v1/queries/validate`, `…/explain` | 050 | 051 | 052 | 053 |
| `GET /api/v1/status`, `GET /status`, `/actuator/*` | 054 | 055 | 056 | 057 |

## SECX-042 — `GET /api/v1/streams`, anonymous
**Intent:** the schema list is the column inventory of a business. The filter is the only gate.
**Falsifier:** a 200 with any stream name or field name in the body.
**Setup:** `N-auth` (18622) with `$QA/streams.yaml` and the three tokens.
**Steps:** `curl -i http://127.0.0.1:18622/api/v1/streams` and
`curl -i .../api/v1/streams/payroll`, with no header, an empty `Authorization:`, `Authorization:
Bearer`, `Authorization: Bearer ` (trailing space), `Authorization: bearer <valid>` (lower case),
`Authorization: Basic <base64 of ann:x>`, `?token=<valid>`, `Cookie: authorization=Bearer <valid>`,
and `X-Forwarded-Authorization: Bearer <valid>`.
**Expected:** 401 `PRV-7001` for all but the lower-case `bearer`, which is 200 (RFC 7235 makes the
scheme case-insensitive and `regionMatches(true, …)` implements it). Record that
`Authorization: <valid-token>` with **no scheme at all** also authenticates — the filter falls through
to `header.strip()` when the prefix does not match, so the scheme is advisory on HTTP and mandatory
on Flight, where `PrincipalMiddleware` requires it. Two transports, two parsers.
**Vacuity:** the lower-case and no-scheme 200s prove the 401s are the filter's decision.

## SECX-043 — `GET /api/v1/streams`, allowed
**Falsifier:** a 401 for a valid token, or a body that omits the field list -- either makes the three diffs below meaningless.
**Intent:** the control, and the baseline body for the three cases below.
**Setup:** `N-auth` with `$QA/streams.yaml`; the response saved to `$QA/http/H-ann.json`.
**Steps:** `curl -H 'Authorization: Bearer ann-token-aaaa' .../api/v1/streams` and `…/streams/payroll`.
**Expected:** 200. The list names `payroll` and `sales`; `payroll` carries
`fieldCount: 3` and the fields `employee`, `region`, `salary` with their types and `nullable` flags.
Save the bytes as `H-ann.json`.
**Vacuity:** control.

## SECX-044 — `GET /api/v1/streams`, denied
**Intent:** `carol` is denied `payroll` by the policy this node's registry would enforce on Flight.
Ask whether HTTP distinguishes her from `ann` in any byte.
**Falsifier:** `H-carol.json` differing from `H-ann.json` — which would mean HTTP *does* consult
something, and the finding would be smaller than recorded.
**Setup:** `N-auth` (18622) for the **H** half and `N-qa` (19624) for the **F** half, carrying the same three tokens and the same fixture.
**Steps:** the same two requests with `carol-token-cccc`; `diff H-ann.json H-carol.json`. Then **F**:
`carol` on `N-qa` reads `payroll_view`.
**Expected:** **H:** byte-identical to `ann`'s, including `payroll`'s full field list. **F:**
`PRV-7002 … carol may not read 'payroll_view'`. One node, one token, two transports, two answers.
Also record: no audit event is produced for either HTTP request, on a node configured
`audit=memory` — HTTP decisions are not merely unauthorized, they are unrecorded.
**Vacuity:** the F half is the control. If `carol` were allowed on Flight too, the identity on HTTP
would prove nothing.

## SECX-045 — `GET /api/v1/streams`, filtered
**Intent:** `bob`'s entitlement is conditional. HTTP has no row data to filter, so the question is
whether the *schema* disclosure respects anything — and whether the API's own view of the world can
be used to infer rows.
**Falsifier:** anything in the response that varies with `bob`'s filter.
**Setup:** as SECX-044.
**Steps:** the two requests with `bob-token-bbbb`; diff against `H-ann.json`. Then **F:** `bob` reads
`sales_view` on `N-qa`.
**Expected:** **H:** byte-identical to `ann`'s. **F:** 2 rows, `100 + 150 = 250`. Record that
`GET /api/v1/streams` is the one place a caller learns that a column named `salary` exists on a
stream they may only see two rows of — the disclosure ADR-031 says the policy exists to govern.
**Vacuity:** the F half.

## SECX-046 — `POST /api/v1/streams`, anonymous
**Intent:** the only write on the HTTP surface. It was the reason the filter was written.
**Falsifier:** a 201, or any change to `GET /api/v1/streams`.
**Setup:** `N-auth` with `payroll` and `sales` declared and nothing else.
**Steps:** `curl -X POST -H 'Content-Type: application/json' -d '{"name":"anon_injected","schema":"a:STRING"}'
.../api/v1/streams` with no credential; then `GET /api/v1/streams`.
**Expected:** 401 `PRV-7001`; the stream list is unchanged (`payroll`, `sales`).
**Vacuity:** SECX-047 injects successfully with a token.

## SECX-047 — `POST /api/v1/streams`, allowed
**Falsifier:** the injected stream reaching a Flight registration, or surviving a restart. Either would mean the write does reach the engine and SEC-062 is wrong.
**Intent:** the control, and the measurement of what the write actually does — known not to reach the
engine (SEC-062), which bounds every case in this row.
**Setup:** `N-auth`, restartable by recorded PID; `N-qa` for the Flight registration.
**Steps:** as `ann`, POST `{"name":"ann_injected","schema":"a:STRING,b:INT64"}`; then
`GET /api/v1/streams`; then on Flight as `ann`, `register --name ai_v --sql "SELECT a, b FROM
ann_injected" --keys 0`; then restart the node and `GET /api/v1/streams` again.
**Expected:** 201 (the lock file records 200 — P-5). The stream appears in the HTTP listing. The
Flight registration fails `PRV-2002 Object 'ann_injected' not found. Known streams: [payroll, sales]`.
After a restart the injected stream is gone. The API reports a success that changed nothing an engine
can see and that does not survive a restart.
**Vacuity:** the Flight registration and the restart are both required; either alone could be
explained by something other than "the write never reached the registry".

## SECX-048 — `POST /api/v1/streams`, denied
**Intent:** the only write on the HTTP surface, performed by the principal the policy denies. If any HTTP case is going to find a policy call, it is this one.
**Falsifier:** a 401/403 — which would mean the policy is consulted after all.
**Setup:** `N-auth` for the **H** half, `N-qa` for the **F** half.
**Steps:** as `carol`, POST `{"name":"carol_injected","schema":"employee:STRING,salary:INT64"}`; then
`GET /api/v1/streams` as `ann`. Then **F:** `carol` registers anything on `N-qa`.
**Expected:** **H:** 201; `ann`'s subsequent listing contains `carol_injected` with a schema `carol`
authored. **F:** `carol` may register (her `mayRegisterQuery` allows) but is refused the moment the
query names `payroll`. So the HTTP write is the one operation on the node with **no** authorization
check of any kind beyond "a token verified", and its product is visible to every other caller,
the console and the OpenAPI-driven clients as part of the node's self-description.
**Vacuity:** `ann` seeing `carol`'s injection is the half that makes it a shared-state defect rather
than a per-request no-op.

## SECX-049 — `POST /api/v1/streams`, filtered
**Intent:** whether a conditional entitlement restrains a write at all, and whether the write can overwrite a declaration the operator made.
**Falsifier:** the row filter having any effect on a write path.
**Setup:** `N-auth` with `sales` already declared by `$QA/streams.yaml`.
**Steps:** as `bob`, POST a stream named `sales` — the name of an **existing** stream — with a
different schema `{"name":"sales","schema":"order_id:INT64"}`; then `GET /api/v1/streams/sales`.
**Expected:** record whether the existing declaration is overwritten, rejected, or duplicated. An
overwrite is the serious outcome: a filtered principal silently rewrites the node's published schema
for a stream they may see two rows of. Whatever happens, no policy call occurs and no audit event is
written.
**Vacuity:** `GET /api/v1/streams/sales` before and after is the comparison; without the "before" the
result is unreadable.

## SECX-050 — `POST /api/v1/queries/validate` and `…/explain`, anonymous
**Intent:** `explain` returns a physical plan over any stream — the column list, the predicate shape
and the output schema, without a row.
**Falsifier:** a 200 carrying a plan.
**Setup:** `N-auth`; no credential presented.
**Steps:** with no credential, POST `{"sql":"SELECT employee, salary FROM payroll"}` to `/validate`
and to `/explain?level=physical`.
**Expected:** 401 `PRV-7001` for both.
**Vacuity:** SECX-051.

## SECX-051 — validate / explain, allowed
**Intent:** the control for the plan row, and the capture of a well-formed `ApiError` body to compare the 401 against.
**Falsifier:** a 401, or a physical plan that does not name the stream it scans.
**Setup:** `N-auth` with `payroll` and `sales` declared.
**Steps:** as `ann`, both calls; also `?level=logical`, `?level=physical`, `?level=nonsense`.
**Expected:** `validate` reports the field list. `explain` returns
`Project[employee, salary]\n  Scan(payroll)\n` and `outputFields`. `level=nonsense` is a 400 in the
`ApiError` shape — capture it as the comparison partner for SECX-090's error-shape case.
**Vacuity:** control.

## SECX-052 — validate / explain, denied
**Intent:** planning arbitrary SQL over a stream the principal may not read. This is the HTTP
surface's closest approach to data: not rows, but the schema, the plan and the confirmation that a
predicate is satisfiable.
**Falsifier:** a refusal.
**Setup:** `N-auth` for the **H** half, `N-qa` for the **F** half.
**Steps:** as `carol`, explain `SELECT employee, salary FROM payroll`, then
`SELECT employee FROM payroll WHERE salary > 200000`, then `SELECT COUNT(*) FROM payroll`. Then
**F:** `carol` attempts the same three as registrations on `N-qa`.
**Expected:** **H:** 200 for all three, with plans. **F:** `PRV-7002 … because it reads 'payroll'`
for all three. The same SQL, the same principal, the same node: refused on one transport and planned
on the other. Note that `explain` does not execute, so no row is disclosed — the disclosure is the
schema, the plan and the fact that the stream exists and is nameable.
**Vacuity:** the F half.

## SECX-053 — validate / explain, filtered
**Intent:** the sharpest HTTP case. `ViewQuery` would AND `region = 'EU'` into this plan. Does the
plan `explain` returns show it?
**Falsifier:** a filter appearing in `bob`'s plan. (That would mean HTTP had grown a policy call.)
**Setup:** `N-auth` for the **H** half and `N-qa` for the **F** half, with `sales_view` registered on both.
**Steps:** as `bob`, explain `SELECT order_id, region, amount FROM sales_view` at `level=physical`;
diff against `ann`'s plan for the same SQL. Then **F:** `bob` reads it on `N-qa`.
**Expected:** **H:** identical plans, neither containing a `Filter[region = 'EU']`. **F:** 2 rows,
`250`, and the injected filter is observable in the executed plan. So a filtered principal can obtain
from HTTP the *unfiltered* plan of a query whose results they may only see a slice of — including
which predicates are pushed where, which is a map of how to phrase a query that avoids the slice.
**Vacuity:** the F half shows the filter exists and is enforceable on this exact view.

## SECX-054 — status, the status page and the actuator, anonymous
**Intent:** three surfaces the open-prefix list governs, and one (`/status`) it does not.
**Falsifier:** any of them answering with deployment detail to a caller with no credential.
**Setup:** `N-auth`; every request made with no credential of any kind.
**Steps:** with no credential request `/api/v1/status`, `/status`, `/`, `/actuator`,
`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`,
`/actuator/metrics`, `/actuator/prometheus`, `/actuator/env`, `/actuator/configprops`,
`/actuator/heapdump`, `/actuator/threaddump`, `/actuator/loggers`, `/actuator/mappings`,
`/actuator/beans`, `/actuator/shutdown`, `/api/v1/openapi.json`, `/api/docs`, `/swagger-ui/index.html`.
Then the traversal set: `/actuator/health/../env`, `/actuator/health/..;/env`,
`/actuator/health%2f..%2fenv`, `/actuator/healthz`, `/actuator/health-check`, `/api/docs/../v1/streams`,
`/api/v1/openapi.json/../../v1/streams`, `/swagger-ui/../api/v1/streams`, `//api/v1/streams`,
`/api/v1/streams;x=/actuator/health`, `/api/v1/openapi.jsonx`, and `/API/V1/STREAMS` (case).
**Expected:** `/actuator/health*`, `/actuator/info`, `/api/v1/openapi.json`, `/api/docs`,
`/swagger-ui/*` answer without a credential, and `/actuator/health` answers `{"status":"UP"}` with
no component detail. Everything else is 401 or 404. **`/status` — the HTML page — is not in
`OPEN_PREFIXES` and must be 401**; it carries the instance id, the version, the uptime and the stream
count. Every traversal returns 401 or 400/404 and none returns stream data. `/actuator/healthz` and
`/actuator/health-check` are the `startsWith` probes: both are open by prefix and must 404 rather
than route anywhere.
**Vacuity:** the five genuinely open paths returning 200 prove the 401s are the filter's.

## SECX-055 — status, allowed
**Intent:** the control for the status row: the baseline body the two cells below are diffed against.
**Falsifier:** a 401 for a valid token.
**Setup:** `N-auth` with two streams declared and five views registered.
**Steps:** as `ann`, `/api/v1/status` and `/status`.
**Expected:** 200. `registeredQueries` reports the **stream** count (I-9, known) — 2 with the fixture,
not 5. `plugins` is `[]`. Record both as the baseline for SECX-056/057.
**Vacuity:** control.

## SECX-056 — status, denied
**Intent:** whether the node's self-description distinguishes a principal the policy denies from one it does not.
**Falsifier:** a difference from `ann`'s body.
**Setup:** `N-auth`; `ann`'s bodies from SECX-055 kept for the diff.
**Steps:** as `carol`, both paths; diff.
**Expected:** identical. The status page discloses the node id, version, uptime and stream count to
any token holder, and `/api/v1/status` is the surface a denied principal would use to confirm the
node is the one holding the data they were refused.
**Vacuity:** F is not applicable — there is no Flight equivalent — so the control here is SECX-054's
401, which proves the page is gated on *something*.

## SECX-057 — status, filtered
**Intent:** the filtered cell, closing the HTTP grid and fixing the group's summary number.
**Falsifier:** any byte of `bob`'s two bodies differing from `ann`'s.
**Setup:** `N-auth`; `ann`'s bodies from SECX-055 kept for the diff.
**Steps:** as `bob`, both paths; diff against `ann`'s.
**Expected:** identical. Completes the HTTP grid: **sixteen cells, one gate, and the gate is
"a token verified".** The summary line to record for the group: on `N-auth`, the four principal
classes collapse to two — has a token, has not.
**Vacuity:** as SECX-056.

---

## Group D — TLS: the transport, the pair, and what the credential rides on

Nine cases, `SECX-058`–`SECX-066`. TLS now works (SEC-047 verified it with rows on the wire), which
means the interesting questions have moved: what happens to the *halves* of the configuration, what
the server advertises, and whether a credential can still reach the network in clear text.

Certificates are generated once into `$QA/tls/` with `openssl`:
`good.pem`/`good.key` (self-signed, CN `localhost`, SAN `DNS:localhost,IP:127.0.0.1`, valid 30 days),
`other.pem`/`other.key` (a second unrelated self-signed pair),
`expired.pem`/`expired.key` (`-not_after` in the past),
`wronghost.pem`/`wronghost.key` (CN `elsewhere.invalid`, no matching SAN).

## SECX-058 — the certificate × key grid, enumerated
**Intent:** `encryptedWith` is called only when the certificate is non-null and dereferences the key
before checking it. Two known defects (SEC-059/060) are two cells of a grid nobody has drawn.
**Falsifier:** any cell that starts a node which (a) reports `transport=TLS` and serves plaintext,
or (b) reports `transport=PLAINTEXT` after the operator set either TLS key.
**Setup:** node on 18632/19632, `authentication: token`, three tokens, `$QA/streams.yaml`.
**Steps:** for each cell, start, record exit status, the `flight transport=` field of the summary
line, what is bound on 19632 (`ss -ltn`), and the result of a plaintext `pravaha queries --token
ann-token-aaaa --url grpc://127.0.0.1:19632`.

| # | `tls.certificate` | `tls.key` | Expected |
|---|---|---|---|
| 1 | unset | unset | starts PLAINTEXT; WARN naming both properties; plaintext client succeeds |
| 2 | `good.pem` | `good.key` | starts TLS; plaintext client fails at the transport |
| 3 | unset | `good.key` | **defect (SEC-059)** — starts PLAINTEXT with only the generic WARN; plaintext client succeeds and ships the token in clear |
| 4 | `good.pem` | unset | **defect (SEC-060)** — refuses with a bare `NullPointerException`, no `PRV-` code, nothing bound |
| 5 | `good.pem` | `""` (empty string) | the node treats `""` as unset: same as cell 4 |
| 6 | `""` | `good.key` | same as cell 3 |
| 7 | `$QA/tls/nope.pem` | `good.key` | refuses `PRV-6104 … is not a readable file`, naming the path |
| 8 | `good.pem` | `$QA/tls/nope.key` | refuses `PRV-6104` naming the **key** path |
| 9 | `$QA/tls` (a directory) | `good.key` | refuses `PRV-6104` |
| 10 | `good.pem` (chmod 000) | `good.key` | refuses; known to arrive as `PRV-3010 LANE_FAILED` rather than `PRV-6104` — `isFile()` is true for a file the process cannot read |
| 11 | `good.key` | `good.pem` (swapped) | refuses; `IllegalArgumentException: … not contain valid certificates`, no `PRV-` code |
| 12 | `good.pem` | `other.key` (mismatched pair) | see SECX-060 |
**Expected:** exactly cells 1, 2 and 3 start. **Cell 3 is the security finding**: the operator asked
for TLS, the node reports `PLAINTEXT`, and the only warning is the one every plaintext node prints.
Cells 4, 5 and 11 fail closed but with no code, no named property and no guidance — the only
configuration errors on this node that produce a Java stack trace instead of a sentence.
**Vacuity:** cells 1 and 2 are the poles. If cell 2 does not produce a genuinely TLS port, every
"refuses" above is a node that cannot do TLS at all and the grid measures nothing.

## SECX-059 — a TLS port is TLS end to end, and a plaintext client is dropped
**Intent:** the control the whole group rests on, re-established on this fixture rather than
inherited. It must carry **rows**, not a handshake: SEC-048 passed vacuously for a whole round
because the port answered nobody.
**Falsifier:** a plaintext client completing any call on the TLS port; or a TLS client completing a
handshake and then failing to fetch rows.
**Setup:** `N-tls` (18623/19623), cells 2's configuration, the five fixture views registered.
**Steps:** with a harness client whose **only** trust anchor is `good.pem`: `doAction` LIST,
`getFlightInfo` for `SELECT order_id, region, amount FROM sales_view`, `getStream` of it. Then
`openssl s_client -connect 127.0.0.1:19623 -alpn h2 -servername localhost`. Then the same harness in
plaintext mode. Then the same harness with `other.pem` as the trust anchor.
**Expected:** the TLS run returns 4 rows summing `100 + 250 + 150 + 300 = 800`; `s_client` reports a
completed handshake with ALPN `h2`; the plaintext run fails at the transport; the `other.pem` run
fails in the SSL handler. Four outcomes, one port.
**Vacuity:** the row count is the vacuity guard. A handshake proves a socket; `800` proves the
session carried the query.

## SECX-060 — certificates the node should refuse and clients should refuse
**Intent:** validity, hostname and pair-matching are three separate checks and the node performs none
of them — it checks `isFile()`. Establish which failures land on the server and which are pushed to
every client.
**Falsifier:** a node that starts with a certificate no client can use, without saying so.
**Setup:** node on 18633/19633.
**Steps:** start with each of: (a) `expired.pem`/`expired.key`; (b) `wronghost.pem`/`wronghost.key`;
(c) `good.pem`/`other.key` (a key that does not match the certificate). For each that starts, run the
harness TLS client with that certificate as the trust anchor, connecting by `127.0.0.1` and by
`localhost`.
**Expected:** (a) the node **starts** and logs `transport=TLS`; every client fails with a certificate
expiry error. The node never reads `notAfter`. (b) starts; a client connecting to `localhost` fails
hostname verification; record whether connecting by IP differs. (c) the failure surfaces at
`builder.build()` or at the first handshake — record which, and whether the node stays bound with a
transport nobody can use. Each is a class of outage whose only symptom is an unexplained client-side
`io exception`.
**Vacuity:** `good.pem` on the same node in the same run reaches rows.

## SECX-061 — what a TLS node advertises to its clients
**Intent:** `getFlightInfo` returns endpoints carrying a `Location`. The producer is built with the
`requested` location, and `PravahaFlightServer.start` then stores
`Location.forGrpcInsecure(host, started.getPort())` for its own accessor. A Flight client is entitled
to follow the advertised location; if it advertises plaintext, TLS is one obedient client away from
being bypassed.
**Falsifier:** an endpoint location whose scheme is not `grpc+tls` on a TLS node, or whose port is
not the bound port.
**Setup:** `N-tls`, plus a second node started with `--pravaha.flight.port=0`.
**Steps:** call `getFlightInfo` on the TLS node and print
`info.getEndpoints().get(0).getLocations()`. Repeat on the port-0 node. Repeat on `N-perm`
(plaintext) for comparison. Also call `PravahaFlightServer.address()`/`location()` from the harness on
the TLS node.
**Expected:** the endpoint location on the TLS node is `grpc+tls://<host>:19623`. On the port-0 node
the location must carry the **bound** port, not `0` — the producer is constructed with `requested`,
whose port is `0`, so a client that follows the endpoint dials port 0. And the server's own
`location` field is built with `forGrpcInsecure` regardless of TLS, so any surface that reports the
node's address reports a plaintext URL for a TLS node. Record all three.
**Vacuity:** the plaintext node's endpoint (`grpc://…`) is the comparison that makes a `grpc://` on
the TLS node a defect rather than the only thing this code can print.

## SECX-062 — a bearer token over plaintext, on every client that can send one
**Intent:** `ClientOptions` refuses to send a token over `grpc://` unless `allowInsecureToken` is
set, and `ServerCommand.connect` sets it unconditionally for every `--token`. So the guard exists
and the shipped CLI disables it, silently, on every command.
**Falsifier:** any of the seven CLI commands warning, refusing, or otherwise telling the operator the
credential left in clear text.
**Setup:** `N-perm` (plaintext, 19621), a token whose text is `SECXPLAIN-aaaa`.
**Steps:** run `queries`, `register`, `query`, `subscribe`, `drop`, `pause`, `resume` against
`grpc://127.0.0.1:19621 --token SECXPLAIN-aaaa`, capturing stdout and stderr. In parallel capture the
loopback traffic (`tcpdump -i lo -A -s0 'tcp port 19621'` or an equivalent the environment allows)
and grep for `SECXPLAIN`. Then repeat the SDK path **without** the CLI: `ClientOptions.builder(
"grpc://127.0.0.1:19621").token("SECXPLAIN-aaaa").build()`.
**Expected:** all seven CLI commands succeed with no warning of any kind, and `SECXPLAIN` appears in
the capture as plain bytes. The raw SDK build **throws**, naming `grpc+tls://` and
`allowInsecureToken` — so the library is right and the tool that ships with it opts out for the user
on every invocation. Record that there is no flag to opt back in.
**Vacuity:** the SDK refusal is the control. Without it this would be "no such guard exists"; with
it, it is "the guard is disabled by the only client most users will run".

## SECX-063 — a private-CA node and the shipped client, measured as an operator would meet it
**Intent:** SEC-063 established that no client can present a trust anchor. Measure the consequence:
what the operator sees, what they can do about it, and what the path of least resistance is.
**Falsifier:** any documented or discoverable way to point `bin/pravaha` at `good.pem`.
**Setup:** `N-tls` (19623) with `good.pem`.
**Steps:** (a) `pravaha queries --url grpc+tls://localhost:19623 --token ann-token-aaaa`;
(b) the same with `-Djavax.net.ssl.trustStore` pointing at a JKS containing `good.pem`;
(c) the same with `good.pem` imported into the JVM's `cacerts`;
(d) `grep -rn "trustedCertificates\|--tls-ca\|--cacert" pravaha-cli/src/main/java sdk/`;
(e) search `docs/operations/SECURITY.md`, `docs/operations/OPERATIONS.md` and `docs/guides/QUICKSTART.md` for any instruction.
**Expected:** (a) fails with `PRV-1041 io exception` and a netty pipeline dump naming
`ProtocolNegotiators$ClientTlsHandler` — a message from which no operator can deduce "certificate not
trusted". (b) records whether the JVM system properties reach the Flight client at all (they are the
only remaining lever). (c) succeeds but is not a deployment procedure. (d) zero hits. (e) nothing.
The operational conclusion to record: the only reachable fix is to turn TLS off, which is the failure
mode SEC-047 was filed about, displaced one layer.
**Vacuity:** (c) succeeding is what proves the failure is trust and not a cipher, a protocol or a
missing native library — it is the control that keeps this from being misdiagnosed a second time.

## SECX-064 — the HTTP surface has no TLS at all
**Intent:** `pravaha.flight.tls.*` encrypts Flight. The bearer token also travels on HTTP, and
nothing in the repository configures TLS for the servlet container.
**Falsifier:** a documented or bound key that gives the HTTP listener TLS.
**Setup:** `N-auth` (18622), `$QA/tls/good.pem` and `good.key`, and a loopback capture on port 18622.
**Steps:** `grep -rn "server.ssl" --include='*.yaml' --include='*.md' .`; then start `N-auth` with
`--server.ssl.enabled=true --server.ssl.certificate=$QA/tls/good.pem
--server.ssl.certificate-private-key=$QA/tls/good.key` and try `https://127.0.0.1:18622/api/v1/status`
and `http://…`; then capture loopback traffic on 18622 while authenticating with `SECXPLAIN-aaaa`.
**Expected:** zero hits in the repository — Spring Boot's own `server.ssl.*` keys work because Boot
binds them, and **no Pravaha document mentions them**, so the documented posture for a hardened node
leaves the HTTP credential in clear text. The capture contains `SECXPLAIN`. Record: the same token
that is protected on one transport by a documented key is unprotected on the other by an undocumented
one.
**Vacuity:** if `server.ssl.*` does work when set, the finding is documentation; if it does not, the
finding is worse. The case distinguishes them, which is why both are run.

## SECX-065 — TLS changes no authorization outcome, and must not
**Intent:** the crossing of the TLS dimension with the policy dimension. Encryption is confidentiality
on the wire and must not become an authentication or authorization signal — and there is no client
certificate path, so it cannot be one.
**Falsifier:** any verb whose outcome differs between the plaintext and the TLS node for the same
principal and the same data.
**Setup:** two nodes, identical but for TLS: `N-auth` (19622, plaintext) and `N-tls` (19623). The same
three tokens, the same fixture, the same five views.
**Steps:** for each of the four principal classes, run LIST, read `sales_view`, read `payroll_view`,
`register`, `subscribe --limit 1`, `drop` against both nodes and tabulate.
**Expected:** every cell identical across the two nodes. `anon` is refused everywhere on both;
`carol` reads `secret_pay` on both. TLS is orthogonal, which is the correct design and is worth
having as a measured fact rather than an assumption — it means no TLS finding in this group can be
argued away as "but authorization compensates".
**Vacuity:** the anon row differing from the ann row on both nodes proves the table is not uniformly
blank.

## SECX-066 — what a plaintext node puts on the wire, beyond the token
**Intent:** the token is the obvious secret. The query text carries `'ACC-0007'`, the results carry
salaries, and `application.yaml` says both are to be permissioned like data.
**Falsifier:** any of the three markers absent from the capture — which would mean the capture is not
working, not that the data is protected.
**Setup:** `N-perm` (plaintext, 19621) and `N-tls` (19623), the same capture technique as SECX-062.
**Steps:** on each node, as `ann`: register `secret_pay`, LIST, read it, subscribe to it with a row
appended mid-stream. Grep the capture for `ACC-0007`, `99000`, `SECXPLAIN`, and `payroll`.
**Expected:** on the plaintext node all four appear as plain bytes — the SQL literal, the salary
value, the credential and the stream name. On the TLS node none does. This is the positive control
for the whole group: it establishes that the capture can see what is there, so SECX-059's "the
plaintext client fails" and SECX-064's HTTP result are measurements rather than absences.
**Vacuity:** the TLS run is the negative control; without it, four greps returning nothing would be
indistinguishable from a broken capture filter.

---

## Group E — row filters: enforceability, unremovability, and the ways one can be lost

Eight cases, `SECX-067`–`SECX-074`. All on `N-qa` (19624), the only node where a filter exists.
Round 1 had three row-filter cases and all three used `region = 'EU'` on a view carrying `region`.
This group is about the other shapes.

## SECX-067 — the enforceability refusal, and whether its advice works
**Intent:** `PRV-7003` exists because a filter naming a column the view aggregated away cannot
separate rows that are already mixed. Verify the refusal on every shape that loses a column, and then
verify the remedy the message prescribes — a refusal that recommends something the engine also
refuses is a defect in its own right (see Y-5).
**Falsifier:** a view that aggregated `region` away being served to `bob` at all, filtered or not.
**Setup:** `N-qa`. `ann` registers `by_employee` = `SELECT employee, SUM(amount) AS total FROM sales
GROUP BY employee`-shaped equivalents over the fixture: concretely
`order_count` = `SELECT region, COUNT(*) AS n FROM sales GROUP BY region` (keeps `region`) and
`grand_total` = `SELECT SUM(amount) AS total FROM sales` (keeps nothing).
**Steps:** `bob` reads (a) `order_count`; (b) `grand_total`; (c) a projection that drops `region`:
`ann` registers `ids_only` = `SELECT order_id, amount FROM sales`, and `bob` reads it. Then follow the
message's advice: `ann` registers `eu_total` = `SELECT SUM(amount) AS total FROM sales WHERE region =
'EU'`, the policy grants `bob` unconditional access to it, and `bob` reads it.
**Expected:** (a) allowed and filtered — `region` is in the view, so the answer is 1 row, `EU, 2`.
(b) `PRV-7003 … cannot be applied to it … Register a view that applies the filter before
aggregating` — and crucially **no rows**, because `grand_total` holds `800`, which mixes `250` of
`bob`'s entitlement with `550` of everyone else's. (c) `PRV-7003` — a plain projection that drops the
filter column is the same hazard with a less obvious shape, and it must refuse for the same reason.
(d) the advice works: `bob` reads `eu_total` and gets one row, `100 + 150 = 250`.
**Vacuity:** `ann` reads `grand_total` in the same run and gets `800`. Without that, `bob`'s zero rows
could be an empty view.

## SECX-068 — a filter in the plan cannot be removed by the caller's SQL
**Intent:** ADR-031's central claim and §25's: the predicate is injected above the scan rather than
concatenated into the text, so no SQL the caller writes can reach it. Attack it.
**Falsifier:** a single non-EU row reaching `bob` from any of these queries.
**Setup:** `N-qa`, `sales_view` (4 rows).
**Steps:** as `bob`, read each of:
(a) `SELECT order_id, region, amount FROM sales_view WHERE 1 = 1 OR region = 'US'`;
(b) `SELECT order_id, region, amount FROM sales_view WHERE NOT (region = 'EU')`;
(c) `SELECT order_id, region, amount FROM sales_view WHERE region <> 'EU' OR region = 'EU'`;
(d) `SELECT order_id, region, amount FROM sales_view /* ' OR '1'='1 */`;
(e) `SELECT order_id, region, amount FROM sales_view WHERE region IS NULL`;
(f) `SELECT COUNT(*) AS n FROM sales_view`;
(g) `SELECT SUM(amount) AS t FROM sales_view`;
(h) `SELECT MAX(amount) AS m FROM sales_view`.
**Expected:** (a) 2 rows, `100 + 150 = 250`. (b) **0 rows** — the security filter and the user's
negation are ANDed, and `region <> 'EU' AND region = 'EU'` is unsatisfiable. (c) 2 rows, `250`.
(d) 2 rows, `250` — the comment is text and never becomes predicate. (e) 0 rows. (f) `n = 2`, not 4.
(g) `t = 250`, not `800`. (h) `m = 150`, not `300`. The aggregate cases are the ones that matter:
an aggregate is the cheapest way to read a value out of rows one may not see, and each answer here is
hand-computable from the EU subset alone.
**Vacuity:** `ann` running (f), (g), (h) returns `4`, `800`, `300`. Every expected value above is a
different number from `ann`'s, so none of them can be produced by a filter that was silently dropped.

## SECX-069 — a filter that plans to no `FilterOperator` is silently discarded
**Intent:** `withRowFilter` plans `SELECT * FROM <source> WHERE <filter>`, then `predicateOf` walks
the tree for the **first** `FilterOperator` and `injectAboveScan` returns the plan **unchanged** when
it finds none. A predicate the planner folds away therefore becomes no restriction at all, and the
read is allowed — not refused. This is a filter that fails *open*.
**Falsifier:** none of the filter forms below producing an unrestricted read. (That would mean the
null path is guarded.)
**Setup:** `N-qa`, with `QaPolicy` parameterised so `bob`'s filter text can be set per run.
**Steps:** for each filter text, have `bob` read `SELECT order_id, region, amount FROM sales_view` and
count rows: (a) `region = 'EU'` (control); (b) `TRUE`; (c) `1 = 1`; (d) `'EU' = 'EU'`;
(e) `region = region`; (f) `region IN (SELECT region FROM sales)`; (g) `region = 'EU' AND TRUE`;
(h) `NOT FALSE`; (i) `region LIKE 'E%'`; (j) `amount > 0 AND region = 'EU'`.
**Expected:** (a) 2 rows / `250`. Whichever of (b)–(e), (h) Calcite reduces to a constant leaves the
plan with no `FilterOperator`: those runs return **4 rows / `800`** — `bob` reads everything, with no
error, no audit distinction and a decision recorded as "allowed with a row filter". (f) is likely
`PRV-7003` at the planner, which is the safe outcome. (g), (j) and (i) must return 2 rows / `250`.
Record precisely which texts fail open; the finding is the **class**, not the example, because a
policy author writing a filter that is tautological for one tenant and restrictive for another gets
silent over-service for the first.
**Vacuity:** (a), (g), (i), (j) returning `250` in the same run proves the machinery is working and
the failures are specific to the folded forms.

## SECX-070 — the filter under every operator `injectAboveScan` knows, and the one it does not
**Intent:** `injectAboveScan` has an arm for `Scan`, `Filter`, `Project`, `Compute` and `Aggregate`
and throws `PRV-7003` for anything else. Enumerate the arms, and reach the `default`.
**Falsifier:** an unfiltered result from any shape, or a shape that runs the query without the filter
instead of refusing.
**Setup:** `N-qa`; `ann` registers one view per shape over `sales`.
**Steps:** `bob` reads each:
(a) `plain` = `SELECT * FROM sales` (scan);
(b) `proj` = `SELECT order_id, region FROM sales` (project over scan);
(c) `filt` = `SELECT * FROM sales WHERE amount > 120` (filter over scan);
(d) `comp` = `SELECT order_id, region, amount * 2 AS d FROM sales` (compute);
(e) `agg` = `SELECT region, SUM(amount) AS t FROM sales GROUP BY region` (aggregate);
(f) `win` = a `TUMBLE` windowed aggregate over `sales` carrying `region` in the group key;
(g) `joined` = a join of `sales` with itself on `region`, if one can be registered at all.
**Expected:** (a) 2 rows / `250`; (b) 2 rows, `order_id` 1 and 3; (c) 1 row (`order_id 3`, the only EU
row with `amount > 120`); (d) 2 rows, `d` = `200`, `300`; (e) 1 row `EU, 250`. (f) and (g) reach the
`default` arm — `PRV-7003 cannot place a row filter under <label>; refusing rather than running the
query without it` — which is the correct behaviour and must be **verified rather than assumed**,
because the alternative is the failure mode of SECX-069 on a whole operator class. If (g) cannot be
registered at all (W-4: joins are unreachable from a server), record it as untestable through
configuration and reach it through the harness.
**Vacuity:** `ann`'s answers — 4, 4, 2, 4, 3 rows — are the comparison column for (a)–(e).

## SECX-071 — filters and the fingerprint: who shares one copy of the state
**Intent:** `QueryFingerprint.of(plan, rowFilters)` with the filters **sorted**. Sharing is the
feature that makes two registrations one computation; a filter in the key is what stops a restricted
principal's data sitting in an unrestricted principal's state. Enumerate who shares with whom.
**Falsifier:** any two principals with different entitlements sharing a fingerprint, or any two with
identical entitlements not sharing one.
**Setup:** `N-qa` with five principals: `ann` (no filter), `bob` (`region = 'EU'`), `bob2`
(`region = 'EU'` — identical), `bob3` (`region = 'EU' ` — trailing space), `dee` (two filters,
`region = 'EU'` and `amount > 0`, returned as separate decisions across two source streams).
**Steps:** each registers the byte-identical SQL `SELECT order_id, region, amount FROM sales` under
its own name. Record the twelve-hex fingerprint of each and the count of `RegisteredQuery` objects.

| pair | Expected |
|---|---|
| ann vs bob | different |
| bob vs bob2 | **same** |
| bob vs bob3 | record — a trailing space is a different string and therefore a different fingerprint, which is a sharing *miss* rather than a leak, but it doubles the state for an invisible reason |
| ann vs dee | different |
| bob vs dee | different |
| dee registered twice with the filters returned in the opposite order | **same** — this is what `Collections.sort` is for |
**Expected:** the table, plus: reading each principal's own view returns its own row set —
`ann` 4 / `800`, `bob` 2 / `250`, `dee` 2 / `250`.
**Vacuity:** `bob` vs `bob2` being the same is the half that fails if the filter were simply hashed
into every fingerprint indiscriminately; `ann` vs `bob` being different is the half that fails if the
filter were omitted. Both are required, and a case with only one of them is not a test of this.

## SECX-072 — a filtered principal's *registration* computes over every row
**Intent:** at registration the filter is put in the fingerprint and **not** into the plan. So the
view `bob` registers is computed over all four sales rows and filtered only when `bob` reads it back.
Ask who else can read that view, and what an aggregate registered by `bob` actually aggregates.
**Falsifier:** `bob`'s registered aggregate returning a value computed only over EU rows — which
would mean the filter *is* applied at registration and this case is wrong about the mechanism.
**Setup:** `N-qa`.
**Steps:** `bob` registers `bob_total` = `SELECT SUM(amount) AS total FROM sales` with `--keys 0`.
Then: (a) `bob` reads `bob_total`; (b) `ann` reads `bob_total`; (c) `carol` reads `bob_total`.
**Expected:** the view holds one row whose `total` is **`800`** — computed over all four rows,
including the 550 `bob` may not see. (a) is `PRV-7003`: the view does not carry `region`, so `bob`'s
own filter cannot be enforced on his own view. (b) `ann` reads `800`. (c) `carol` reads `800`. So a
restricted principal can cause an unrestricted aggregate over data they are restricted from to be
computed, materialised and served to others — and cannot read it themselves. Record whether that is
intended; it follows directly from filters being a read-time concern and registration being a
standing read.
**Vacuity:** `ann` registering the identical SQL produces a *different* fingerprint (SECX-071) and the
same `800`, which shows the number is the data's and not an artefact of `bob`'s registration.

## SECX-073 — NULL, and the three-valued logic a row filter inherits
**Intent:** a filter is a SQL predicate, and SQL predicates are three-valued. A row whose filtered
column is NULL matches nothing — including `<>`. Whether that is fail-open or fail-closed depends on
which side the row lands.
**Falsifier:** a NULL-`region` row reaching a principal filtered to a specific region.
**Setup:** `$QA/sales_null.csv` adds a fifth row with an empty region field: `5,,500`. The `sales`
schema declares `region` nullable for this case.
**Steps:** `ann` and `bob` read `sales_view`; then a third principal `nel` whose filter is
`region <> 'US'`; then a fourth `nil` whose filter is `region IS NULL`.
**Expected:** `ann` 5 rows, `100 + 250 + 150 + 300 + 500 = 1300`. `bob` (`region = 'EU'`) 2 rows,
`250` — the NULL row is excluded, fail-closed, correct. `nel` (`region <> 'US'`) **2 rows,
`100 + 150 = 250`**, not 3 and not `550`: `NULL <> 'US'` is UNKNOWN, so the APAC row (`300`) is
included and the NULL row is not — recompute: `100 + 150 + 300 = 550` over 3 rows. Assert `550`.
`nil` (`region IS NULL`) 1 row, `500`. The case exists because a policy author who writes an
exclusion filter (`<> 'US'`) rather than an inclusion filter gets different NULL behaviour, and
nothing in the SPI's documentation says so.
**Vacuity:** `ann`'s `1300` and the four different subset sums make every row's fate individually
determined; no two expected answers are the same number.

## SECX-074 — a decision is not cached, and must not be
**Intent:** `SecurityPolicy`'s javadoc: "a policy that has just revoked someone's access expects that
to take effect, and a cache Pravaha owned would decide the revocation window without asking." Verify
there is no cache anywhere on the read path — including `ViewQuery`'s **plan** cache, which is keyed
on SQL and is shared between principals.
**Falsifier:** a second read returning the first read's entitlement.
**Setup:** `N-qa` with a mutable `QaPolicy` whose rule table can be edited between calls from the
harness.
**Steps:** (a) `bob` reads `sales_view` → expect 2 / `250`; change `bob`'s filter to `region = 'US'`;
`bob` reads again → expect 1 / `250`; change to no filter; read again → 4 / `800`; change to a deny;
read again → `PRV-7002`. (b) the plan-cache crossing: `ann` reads
`SELECT order_id, region, amount FROM sales_view` (warming the cache), then `bob` reads the identical
SQL, then `carol` (denied `sales_view` for this run) reads it.
**Expected:** (a) four different answers from four consecutive reads with no restart —
`250` over 2 rows, `250` over 1 row, `800` over 4 rows, then a refusal. Note the first two sums are
equal by coincidence (`100 + 150` and `250`); the **row counts** discriminate, which is why both are
asserted. (b) `ann` 4 rows, `bob` 2 rows, `carol` refused — the cached plan is re-authorized per
caller and the filter re-injected per caller.
**Vacuity:** (a) with the policy left unchanged returns the same answer four times; run that as the
control so "the answer changed" is attributable to the policy and not to the data.

---

## Group F — lineage, laundering and ownership

Six cases, `SECX-075`–`SECX-080`. The registry computes a query's source streams in order to
authorize the registration and then discards them. Everything in this group follows from that one
fact, and the cases are shaped to measure how far it reaches rather than to restate it.

## SECX-075 — how far a restricted stream can be laundered, and what it costs the attacker
**Intent:** SEC-057 showed one hop. Establish the shape of the whole attack: what an entitled
principal must do, whether a denied principal can do any of it alone, and whether the laundered view
can itself be laundered.
**Falsifier:** `carol` obtaining a payroll row through any path that does not require an entitled
principal to act first. (That would raise the severity from HIGH to CRITICAL.)
**Setup:** `N-qa`.
**Steps:** as `carol`, attempt in order:
(a) read `payroll` directly (a base stream, not a view);
(b) register `SELECT employee, region, salary FROM payroll` under any name;
(c) register `SELECT employee, region, salary FROM hr_summary` — chaining off an existing view;
(d) read `hr_summary` (registered earlier by `ann`);
(e) subscribe to `hr_summary`;
(f) prepare-and-execute against `hr_summary`;
(g) `getFlightInfo` for `SELECT COUNT(*) FROM hr_summary`.
**Expected:** (a) refused — `payroll` is not in the view catalog, so this is `PRV-4023`/`PRV-2002`,
not an authorization decision; record that the refusal is incidental. (b) `PRV-7002 … because it
reads 'payroll'`. (c) `PRV-2002 Object 'hr_summary' not found. Known streams: [sales, payroll]` — a
registration plans against **streams**, not views, so a view cannot be chained; this is what bounds
the attack, and it is an accident of the planner rather than a control. (d), (e), (f), (g) all
**succeed**: 3 rows summing `99000 + 250000 + 55000 = 404000`, a live subscription, a bound
execution, and a count of `3`. So the laundering needs exactly one act by an entitled principal —
registering a derived view — and after that every read path is open to the denied principal.
**Vacuity:** (b) and (c) failing in the same run as (d) succeeding is the whole result; either half
alone is a different (and wrong) story.

## SECX-076 — the lineage exists at registration and is thrown away
**Intent:** show that the fix is cheap, by showing the information is already computed. This is a
measurement case, not a defect case, and it is here so the report can say what the remedy costs.
**Falsifier:** the registry not having the source set at registration time — which would make the
remedy a planner change rather than a plumbing change.
**Setup:** `N-qa` plus a harness that reflects on `RegisteredQuery` after registration.
**Steps:** register `hr_summary` as `ann`; capture the `register:source` audit events; then inspect
the `RegisteredQuery` and the `ViewCatalog` entry for any retained source-stream set; then inspect
the journal entry for the same registration.
**Expected:** exactly one `register:source` event naming `payroll` — so `sourceStreams(plan)` ran and
returned `[payroll]`. Neither `RegisteredQuery`, `ServedView` nor the journal entry retains it: the
journal records name, SQL, key columns, owner, retention and parameters. The SQL text is retained,
so the lineage is recoverable by re-planning — which is the shape of the fix, and is worth recording
next to the defect.
**Vacuity:** the audit event is the proof the set was computed; without it this case would be an
assertion about source code.

## SECX-077 — the owning principal is recorded, journalled, replayed, and never consulted
**Intent:** four places hold the owner and no decision reads it. Establish all four in one run so the
finding is "the data is there" rather than "there is no ownership model".
**Falsifier:** any verb whose outcome depends on who registered the query.
**Setup:** `N-qa`, journal enabled at `$QA/journal`.
**Steps:** `ann` registers `owned_v` = `SELECT order_id, region, amount FROM sales`. Then:
(a) read the journal file and find the owner field; (b) `carol` drops `owned_v`; (c) re-register and
have `bob` pause it; (d) re-register and have `ann` drop it; (e) grep `requireAdministrable` and
`AuthenticatedOnlyPolicy` for any use of the registration's owner.
**Expected:** (a) the journal line carries `ann`. (b) `dropped owned_v` — the owner is `ann` and the
dropper is `carol`, and nothing compared them. (c) `pauseped`. (d) `dropped`. (e) zero uses: the
policy is handed `(principal, view)` and there is no overload that takes the owner, so **a policy
author cannot implement an ownership rule even if they write their own policy**. That is the part
worth reporting — the gap is in the SPI, not only in the default.
**Vacuity:** (d) is the control that the verb works for its owner; without it (b) could be "drop is
broken for everyone".

## SECX-078 — a shared computation with two owners, and what a later revocation does to it
**Intent:** sharing by fingerprint means one computation, two names, two owners. Authorization was
taken once per registrant, at registration. Nothing re-runs it.
**Falsifier:** the second registrant's revocation affecting the first's access, or the first's view
disappearing when the second's name is dropped.
**Setup:** `N-qa`, mutable `QaPolicy`.
**Steps:** `ann` registers `shared_a` = `SELECT order_id, region, amount FROM sales`; `carol`
registers the byte-identical SQL as `shared_c`; confirm one fingerprint and one `RegisteredQuery`.
Then (a) revoke `carol`'s access to `sales` in the policy; (b) `carol` reads `shared_c`; (c) `ann`
reads `shared_a`; (d) `carol` drops `shared_c`; (e) `ann` reads `shared_a` again; (f) restart and
recover.
**Expected:** one fingerprint, two names. (b) refused — reads are re-authorized, so revocation takes
effect on the read path. (c) 4 rows / `800`. (d) records what `drop` of one name of a shared
computation does: the name is removed, and if the computation is torn down, **`ann`'s view dies from
`carol`'s call** — a revoked principal destroying an entitled principal's query. (e) is the
measurement. (f) checks that the journal replays both names and re-authorizes each against the
current policy: `shared_a` recovers and `shared_c` is refused.
**Vacuity:** (c) before the drop and (e) after it are the same read; a difference is caused by (d)
and nothing else, because no data changed between them.

## SECX-079 — view names are a first-come namespace, and the policy's rules are keyed on them
**Intent:** if authorization is decided on a name, then who gets to choose names is an authorization
question. `requireName` validates a regex and nothing else.
**Falsifier:** a principal taking a name in a way that grants them, or denies another, access.
**Setup:** `N-qa`.
**Steps:** (a) `carol` registers `SELECT order_id, region, amount FROM sales` as `payroll_view` — a
name already held by `ann`'s payroll query; (b) as `innocuous_2024`; (c) `carol` registers a view
named `sales` — the name of a **base stream**; (d) `carol` registers `SALES_VIEW` (case);
(e) `carol` registers a name of 300 characters and one containing `../`; (f) after (c), `ann`
registers `SELECT order_id, region, amount FROM sales` and sees which `sales` the planner resolves.
**Expected:** (a) records the collision behaviour — a second registration under an existing name
either refuses, replaces, or aliases; replacing would let any principal substitute their own query
for another's under a name others read by. (b) succeeds. (c) is the interesting one: a view named
`sales` shadowing a stream named `sales` makes `SELECT … FROM sales` ambiguous, and (f) shows which
wins — if the view wins, a principal can redefine what everyone else's future registrations read.
(d) succeeds and is a *different* view from `sales_view`, so a policy rule matching `sales_view`
exactly does not match it — case sensitivity is an entitlement boundary. (e) refused by
`requireSayableName`; record that a null name throws a bare `NullPointerException` (a known
regression) rather than the message two lines below it.
**Vacuity:** (b) succeeding proves `carol` may register, so every refusal above is about the name.

## SECX-080 — the same data under two names, and the policy's inability to see it
**Intent:** the closing measurement of the group. Count, on one node, how many distinct authorization
answers the same three payroll rows can produce.
**Falsifier:** all names producing the same answer for the same principal.
**Setup:** `N-qa` with five views over `payroll`: `payroll_view`, `payroll_audit`, `secret_pay`,
`hr_summary`, `comp_q3` — all registered by `ann`, all reading the same three rows.
**Steps:** `carol` reads each of the five and lists.
**Expected:** two refusals (`payroll_view`, `payroll_audit`) and three successes carrying the same
`ACC-0007` row and the same `404000` total for the ones that select all rows. **Three of five names
for one row set are readable by the principal denied that row set: 60%.** The listing shows the same
split. The number is the point: it is not a corner case, it is the median outcome of naming things
the way people name things.
**Vacuity:** the two refusals prove the policy is installed and denying; the three successes prove
the denial is about the string.

---

## Group G — the lifetime of an authorization decision

Eight cases, `SECX-081`–`SECX-088`. Recorded in round 1 as **"never tested"** (S-9), and now with a
second decision point on the same objects (`mayAdminister`). Every case here is stateful or timed, so
every one carries a vacuity argument that names what would have to be true for it to pass with the
feature removed.

Two harness instruments are needed and are built once in the scratch directory, compiled against the
released jars:

* **`MutablePolicy`** — a `SecurityPolicy` delegating to a rule table that can be rewritten from the
  test thread between calls, with a latch so a rewrite can be sequenced against a specific call.
* **`ExpiringVerifier`** — a `TokenVerifier` that accepts a token until a wall-clock instant and
  throws `PRV-7001` afterwards. It exists because **no verifier in the repository has expiry,
  rotation or revocation**, which is itself the first finding of this group.

## SECX-081 — access revoked while a subscription is open
**Intent:** the central untested question. A subscription is a standing grant checked exactly once,
at `getStream`. Nothing re-checks it, and the stream can outlive the entitlement by any amount of
time.
**Falsifier:** a change committed *after* the revocation reaching the revoked subscriber.
**Setup:** `N-qa` with `MutablePolicy`; `ann` subscribed to `sales_view`; the source file appended to
on demand.
**Steps:** (1) `ann` subscribes with no `--limit`; (2) append `9,EU,900` and confirm it arrives;
(3) revoke `ann`'s read of `sales_view` in `MutablePolicy`; (4) confirm the revocation is live by
having `ann` **read** the view on a second connection — expect `PRV-7002`; (5) append `10,EU,1000`;
(6) wait 10s; (7) record whether it arrived; (8) `ann` reconnects and subscribes again.
**Expected:** step 2 delivers `9 EU 900`. Step 4 refuses. **Step 7 is the finding**: the change
arrives, because the only `mayRead` call on this path ran at step 1. The engine holds the revoked
principal's stream open indefinitely — until the client disconnects or the query terminates. Step 8
is refused, so the revocation is real and applies to *new* subscriptions only. Record the elapsed
time between revocation and last delivered row as the exposure window; it is bounded by nothing.
**Vacuity:** step 4 is the vacuity guard: without it, "the row arrived" could mean the revocation was
never applied. With it, the revocation is provably live on one path and provably ignored on another,
in the same process, at the same second.

## SECX-082 — revocation between planning and fetching
**Intent:** Flight splits a read into `getFlightInfo` (plan, authorize) and `getStream` (execute,
authorize again). The ticket travels on the client in between. Establish that the second call
re-authorizes, and measure the window if it does not.
**Falsifier:** rows delivered on a ticket whose principal was denied between the two calls.
**Setup:** `N-qa` with `MutablePolicy`; the harness holds a ticket rather than letting the client
follow it immediately.
**Steps:** (a) `ann` calls `getFlightInfo` for `SELECT order_id, region, amount FROM sales_view` and
the harness keeps the ticket; (b) revoke `ann`; (c) the harness calls `getStream` with the ticket;
(d) repeat with a **prepared statement** handle across `createPreparedStatement` → revoke →
`doPut` → `getStream`; (e) repeat with the ticket presented by a *different* principal (`carol`'s
connection carrying `ann`'s ticket).
**Expected:** (c) `PRV-7002` — `getStreamStatement` calls `queries.execute(sql, principalOf(context))`
and the policy runs again. (d) the same at every leg, because `getStreamPreparedStatement` calls
`queries.prepare` and `queries.execute` and both authorize. (e) refused or allowed **according to
`carol`'s** entitlement, not `ann`'s — the ticket carries the SQL and no identity, which is the
correct design and must be confirmed rather than assumed, because a ticket that carried identity
would be a bearer credential with no expiry.
**Vacuity:** run each leg without the revocation as the control; the same ticket must deliver 4 rows
/ `800`. Otherwise a refusal proves only that a stale ticket is unusable.

## SECX-083 — there is no revocation, rotation or expiry, and what an operator must do instead
**Intent:** `StaticTokenVerifier` says so in its own javadoc. Measure the operational consequence
precisely, because "restart the node" has a cost that is the actual finding.
**Falsifier:** any mechanism — a signal, an endpoint, a file reload, an actuator refresh — that
removes a token from a running node.
**Setup:** `N-auth` (18622/19622) with three tokens and a registered, running query accumulating
rows.
**Steps:** (a) remove `carol-token-cccc` from the YAML and `kill -HUP` the process; (b) POST
`/actuator/refresh`; (c) touch the config file and wait 60s; (d) call any admin surface for a token
list; (e) restart the node with the reduced table and measure how long `sales_view` is unanswerable
and whether its accumulated state survives; (f) confirm `carol` is refused afterwards.
**Expected:** (a)–(d) all no-ops or 404s: the token table is read once at startup. (e) is the number
to record — the node is down for the restart, and (given D-1/A-2, checkpoints are written and never
read) the query's accumulated state does not come back; it is recomputed from whatever the source can
replay. **Revoking one credential therefore costs every query on the node its state.** (f) `carol`
refused.
**Vacuity:** (f) is the control that the restart did revoke. Without it the whole case measures a
restart.

## SECX-084 — a token that expires mid-stream
**Intent:** with `ExpiringVerifier` the credential is checked per call by `PrincipalMiddleware` — but
a subscription is one call that lasts for hours. Establish where expiry does and does not bite.
**Falsifier:** an expired credential producing rows on a *new* call.
**Setup:** `N-qa` with `ExpiringVerifier`, `ann`'s token valid for 20 seconds.
**Steps:** (1) at t=0 `ann` subscribes to `sales_view`; (2) at t=5s append `11,EU,1100` — expect
delivery; (3) at t=25s (expired) append `12,EU,1200`; (4) at t=35s record whether it arrived;
(5) at t=40s `ann` attempts a fresh `queries` call; (6) at t=45s `ann` attempts a fresh subscribe.
**Expected:** (2) delivered. **(4) delivered** — the middleware ran once, at t=0, and the stream is
not re-authenticated. (5) and (6) `PRV-7001`. So expiry governs call *initiation* and not call
*duration*, and the longest-lived call on the system is the one that carries data continuously.
Record it beside SECX-081: authentication and authorization both expire at the same boundary, and it
is the wrong boundary for a subscription.
**Vacuity:** (5) refusing proves the expiry fired. Without it, (4) arriving would mean nothing had
expired.

## SECX-085 — `drop` racing `register` on the same name and the same fingerprint
**Intent:** `register` is `synchronized` on the registry and so are `drop`, `pause` and `resume` — but
the *authorization* inside `requireAdministrable` and the registry call are not one atom on the
producer side, and sharing adds a path where a name attaches to an existing computation.
**Falsifier:** any interleaving that leaves a name in `byName` with no view, a view with no name, or
a `RegisteredQuery` in `byFingerprint` that is `DROPPED`.
**Setup:** `N-qa`, two harness threads, 200 iterations of each interleaving, with the registry's maps
inspected between iterations.
**Steps:** run these pairs concurrently, tight-looped:
(a) `ann` registers `race_v`; `ann` drops `race_v`;
(b) `ann` registers `race_a` and `carol` registers the byte-identical SQL as `race_c` (sharing); a
third thread drops `race_a`;
(c) `ann` registers `race_v`; `carol` drops `race_v` (the authorization on the dropper races the
registration that creates the thing being authorized);
(d) two threads register the identical SQL under the identical name simultaneously.
**Expected:** no exception escapes to a client except `PRV-7002` or `PRV-8002`; after each iteration
the registry is consistent — every name in `byName` resolves in `ViewCatalog`, every fingerprint maps
to a non-terminal query, and the journal's replayed set equals the live set. In (c), the interesting
outcome is a `drop` authorized against a name that did not exist when `mayAdminister` was called and
did by the time `required.drop` ran. In (b), dropping one name of a shared computation must not
remove the other's view (cross-check SECX-078).
**Vacuity:** the same loops run single-threaded must produce a consistent registry every time; any
inconsistency found there is not a race and must be reported separately, or the concurrent result is
uninterpretable.

## SECX-086 — `drop` under an open subscription and an in-flight read
**Intent:** dropping destroys accumulated state while someone is reading it. The subscription loop
polls `query.state().isTerminal()`; the read loop holds a `ViewCatalog` entry.
**Falsifier:** a subscriber receiving a change from a dropped query, or a reader receiving a partial
row set reported as complete.
**Setup:** `N-qa`, `sales_view` with 200,000 rows loaded so a read takes measurable time.
**Steps:** (a) `ann` subscribes; `carol` drops the view mid-stream; record what the subscriber sees.
(b) `ann` starts a read of all 200,000 rows; `carol` drops mid-read; record the row count and the
status the client is given. (c) repeat (b) with `pause` instead of `drop`.
**Expected:** (a) the subscription terminates — cleanly, with a status the client can distinguish from
"the query ended normally". (b) either the full 200,000 rows or an error; **never a short count under
a success status**, which is round 1's characteristic failure (C-4, I-1). Assert the count exactly.
(c) a pause must not truncate a read in progress.
**Vacuity:** the same read with no concurrent drop returns exactly 200,000 rows; that control is run
first, and if it does not, C-4 invalidates the case and it is recorded as blocked rather than passed.

## SECX-087 — an entitlement narrows while a query is registered under it
**Intent:** the fingerprint was computed from the filters the principal held **at registration**. If
the principal's filter changes, the registered computation does not — and a newly-registering
principal with the new filter gets a different fingerprint and a second computation.
**Falsifier:** the old view serving the old entitlement to the narrowed principal.
**Setup:** `N-qa` with `MutablePolicy`; `bob` (`region = 'EU'`) has registered
`bob_sales` = `SELECT order_id, region, amount FROM sales`.
**Steps:** (1) `bob` reads `bob_sales` → 2 rows / `250`; (2) narrow `bob`'s filter to
`region = 'EU' AND amount > 120`; (3) `bob` reads `bob_sales` again; (4) `bob` registers the identical
SQL as `bob_sales2` and compares fingerprints with `bob_sales`; (5) widen `bob` to no filter and read
`bob_sales`; (6) restart the node and replay the journal; (7) `bob` reads `bob_sales`.
**Expected:** (3) 1 row (`order_id 3`, `amount = 150`) — the read path re-asks, so narrowing takes
effect. (4) a **different** fingerprint from `bob_sales`, so the node now runs two identical
computations for one principal, and `bob_sales` is keyed to an entitlement `bob` no longer has;
the state is a fingerprint nobody's current entitlement maps to. (5) 4 rows / `800` — widening also
takes effect on read, so the fingerprint's filter component is *not* an enforcement mechanism at read
time; it only partitions state. (7) after recovery, `bob_sales` is re-registered with `bob`'s
**current** filters, so its fingerprint changes across a restart — record whether the old state
directory is orphaned.
**Vacuity:** (1), (3) and (5) are three different row counts from one view with no data change; the
case cannot pass with the policy uninstalled, which would give `4 / 4 / 4`.

## SECX-088 — recovery reconstructs an owner nobody can authorize
**Intent:** `principalNamed` returns `new Principal(id, "unknown", Set.of(), Map.of())`. The design —
re-authorize on replay — is right; the principal it re-authorizes with has no tenant and no roles.
Measure what survives a restart under each policy.
**Falsifier:** a query recovering under a policy that would have refused its owner, or a query being
refused under a policy that would have allowed them.
**Setup:** a node with a journal at `$QA/journal` and the five fixture views; `N-qa` for the
rule-bearing half.
**Steps:** (a) restart under `PERMISSIVE` — expect all five recovered; (b) restart under
`AuthenticatedOnlyPolicy` — the reconstructed principal is non-anonymous, so `mayRead` allows;
(c) restart under a `QaPolicy` variant that keys on **tenant** (`ann` is `hr`) — the reconstructed
`ann` is in tenant `unknown`; (d) restart under a variant that keys on **roles** (`analyst`) — the
reconstructed `ann` has none; (e) hand-edit one journal entry's owner to a blank string and restart;
(f) hand-edit one to an id no verifier knows and restart; (g) after (c), have the real `ann` read the
view.
**Expected:** (a) `recovered 5 of 5`. (b) 5 of 5. (c) and (d) **0 of 5** — every query on the node is
refused at recovery and the refusal names each one; the log says so and the node starts empty. (e)
refused with "there is nobody to authorize it as", the other entries still recovering — and note
`PRV-8007` is declared for this and never thrown. (f) recovers as a role-less `unknown`-tenant
principal with that id, because `principalNamed` invents one for any non-blank string: **the journal's
owner field is trusted input**, so an operator (or anything that can write the journal file) chooses
the identity a recovery runs as. (g) `ann` reads normally, because reads use the live principal —
so the damage of (c)/(d) is confined to what recovers, which is everything.
**Vacuity:** (a) and (b) recovering 5 of 5 in the same session is what makes (c)/(d)'s 0 of 5 a
property of the reconstructed principal rather than of the journal or the restart.

---

## Group H — audit, oracles, and paths that reach data without asking

Seven cases, `SECX-089`–`SECX-095`.

## SECX-089 — is every allow *and* every deny recorded? — the audit completeness matrix
**Intent:** `AuditEvent`'s javadoc: "emitted for **allowed** decisions as well as denied ones — an
audit log that only contains refusals answers 'who was stopped' and not 'who read the salary view'."
Check that claim against every decision the node takes, on both transports.
**Falsifier:** any decision — allow or deny — that changes what a caller receives and leaves no audit
record.
**Setup:** `N-qa` with `AuditSink.InMemory`, drained and cleared before each row. `N-auth` with
`audit: memory` for the HTTP half, where the sink is reachable only by the harness.
**Steps:** perform each action below, then read the sink and record the event count, action string,
target and `allowed` flag.

| # | Action | Expected record |
|---|---|---|
| 1 | `ann` reads `sales_view` | `query`/`sales_view`/ALLOW |
| 2 | `carol` reads `payroll_view` | `query`/`payroll_view`/DENY |
| 3 | `bob` reads `sales_view` (filtered) | `query`/`sales_view`/ALLOW, reason `allowed with a row filter` |
| 4 | `bob` reads `grand_total` (`PRV-7003`) | **record whether the unenforceable-filter refusal is audited at all** — it is thrown from `withRowFilter`, after the ALLOW was already recorded, so the log says "allowed" for a read that was refused |
| 5 | `ann` prepares / executes a prepared statement | `prepare` + `query`, one each per call |
| 6 | `ann` calls `getFlightInfo` | `schema`/…/ALLOW |
| 7 | `ann` registers | `register` + one `register:source` per stream |
| 8 | `carol` registers over `payroll` | `register`/ALLOW then `register:source`/`payroll`/DENY — two events, and the first says allowed |
| 9 | `ann` drops / pauses / resumes | one event each |
| 10 | `carol` LISTs and is filtered | **expected: no event at all** — the `LIST` arm calls `mayRead` and does not audit it, so the disclosure decision taken for every view on the node is unrecorded |
| 11 | `ann` subscribes; batches are dropped for slowness | `subscribe` + `subscribe.dropped` |
| 12 | **an unauthenticated Flight call** | **expected: no event** — `PrincipalMiddleware` never touches the sink |
| 13 | **a bad token on Flight** | no event |
| 14 | **an unauthenticated HTTP call** | no event — `BearerTokenFilter` never touches the sink |
| 15 | **a bad token on HTTP** | no event |
| 16 | any authenticated HTTP call (SECX-043…057) | no event — no controller has a principal |
| 17 | recovery refuses a journalled query | record whether the refusal is audited |
**Expected:** rows 1–3, 5–9 and 11 produce records. **Rows 10 and 12–16 produce none.** The
consequence to state plainly: the audit log cannot answer "who tried to authenticate and failed",
"who listed this node", or anything at all about the HTTP surface — which is three of the four
questions an investigation opens with. Row 4 is the subtler one: a record saying ALLOW for a request
that returned no rows.
**Vacuity:** rows 1 and 2 producing an ALLOW and a DENY establish the sink is receiving; every empty
row is then an absence rather than a broken harness.

## SECX-090 — does the audit sink reach anything?
**Intent:** an audit record that no one can read is not an audit. Follow the only two configured
sinks to their ends.
**Falsifier:** any shipped surface — endpoint, CLI command, log line, file — that returns an audit
event on a node configured `audit: memory`.
**Setup:** `N-auth` with `--pravaha.security.audit=memory`; perform ten decisions (five allows, five
denies) on the Flight surface.
**Steps:** (a) `grep -rn "AuditSink\|audit" pravaha-server/src/main/java` for any reader of the
`InMemory` sink beyond the field that holds it; (b) request `/api/v1/status`, `/status`,
`/actuator/*` and every REST path for anything audit-shaped; (c) `pravaha --help` and every
subcommand for an audit verb; (d) grep the server log for the `AuditEvent.toString` shape
(`<instant> ALLOW <id> <action> <target>`); (e) start with `--pravaha.security.audit=log` — the value
`SecurityProperties`' own javadoc documents; (f) restart the node and ask for the ten events;
(g) push 10,001 events through the sink and check which are retained.
**Expected:** (a) the sink is a private field with no getter on any bean; (b) nothing; (c) nothing;
(d) nothing — `AuditSink.InMemory` writes to a `CopyOnWriteArrayList` and never logs; (e) refused at
startup with `PRV-7002 … use 'none' or 'memory'`; (f) empty — the events died with the process;
(g) the oldest are dropped at 10,000, via `events.remove(0)` on a `CopyOnWriteArrayList`, which
copies the whole array per removal: **the audit path degrades quadratically exactly when a node is
under attack and generating the most events.** Record (g) with a timing: 10,000 extra events past the
limit against a baseline of 10,000 under it.
**Vacuity:** (g) needs the baseline in the same run, or a slow number proves nothing.

## SECX-091 — the existence oracle: five channels, one question
**Intent:** a denied principal must not be able to determine whether a name exists. Test every channel
that could answer, including the ones that answer by not answering.
**Falsifier:** any channel that distinguishes "exists but denied" from "does not exist".
**Setup:** `N-qa`. `carol` is denied `payroll_view` (exists) and knows nothing of `zzz_absent`.
**Steps:** for each of `payroll_view` (exists, denied), `sales_view` (exists, allowed) and
`zzz_absent` (absent), as `carol`, measure: (a) the error **code**; (b) the error **text**;
(c) the gRPC/HTTP **status**; (d) the **latency**, 500 iterations, median and p99; (e) whether the
name appears in any other response's enumeration.
**Expected:**
* `payroll_view` → `PRV-7002`, "carol may not read 'payroll_view'", `PERMISSION_DENIED`.
* `zzz_absent` → `PRV-2002`/`PRV-4023`, "not found. Known streams: [...]", `NOT_FOUND`/`INVALID_ARGUMENT`.
* Three of the four channels therefore answer the question outright. (d) is the one worth measuring
  properly: a denial is decided before planning and an absence after the catalogue lookup, so the two
  should differ measurably; report the medians and whether the gap exceeds run-to-run noise, since a
  timing oracle survives a fix to the message and the code.
* (e) the absent-name error enumerates every view, so a single probe replaces the oracle entirely.
**Vacuity:** the `sales_view` row is the control: it must return rows, so the other two are refusals
rather than a broken connection.

## SECX-092 — disclosure through everything that is not the data
**Intent:** the policy governs rows. Counts, timings, schemas, plans, states and page text are not
rows and are governed by nothing.
**Falsifier:** none of the channels below carrying information about data the principal is denied.
**Setup:** `N-qa` and `N-auth`, the fixture loaded, `payroll_view` denied to `carol`.
**Steps:** as `carol`, collect: (a) `pravaha queries` — the `ROWS IN` column for every listed view;
(b) the fingerprint short form of every listed view; (c) `getFlightInfo` for a query over a denied
view — does the **schema** come back before the refusal? (d) `explain` over `payroll` on HTTP
(SECX-052); (e) `/api/v1/streams/payroll`'s field list on HTTP; (f) `/status`'s stream count;
(g) `/actuator/health`'s body; (h) the *state* (`RUNNING`/`PAUSED`) of a denied view via a
`pause`-then-error probe; (i) the latency of a read of `hr_summary` as a function of how many payroll
rows exist (append 10,000 and re-measure).
**Expected:** (a) row counts for views `carol` may not read (SECX-017). (b) fingerprints are a hash of
the plan **and the row filters**: two principals can compare fingerprints to learn whether their
entitlements match, which is a disclosure about the *policy* rather than the data — record it.
(c) `getFlightInfo` calls `schemaOf`, which authorizes before returning, so the schema must **not**
come back; verify. (d), (e) schemas and plans over a denied stream, on HTTP, by design. (f) the
stream count. (i) a coarse cardinality oracle over a denied stream, through an allowed derived view.
**Vacuity:** for (i), the baseline measurement before the 10,000 rows are appended; without it a
latency is a number and not an oracle.

## SECX-093 — every message built from the whole registry, enumerated by verb
**Intent:** known that three codes enumerate (SEC-058). Enumerate the **verbs**, so the fix list is
complete rather than exemplary.
**Falsifier:** a verb whose not-found message is built from what the principal may read.
**Setup:** `N-qa` with five views, two of which `carol` is denied.
**Steps:** as `carol`, ask each verb for the name `zzz_absent`, and separately for a name that exists
and is denied: `queries` (LIST — no name to ask for; record that it is the only correct one),
`register` over an unknown stream, `query`/`getFlightInfo` over an unknown view, `subscribe`, `drop`,
`pause`, `resume`, `createPreparedStatement`, the HTTP `GET /api/v1/streams/{name}`, the HTTP
`POST /api/v1/queries/validate` over an unknown stream, and `POST /api/v1/queries/explain`.
**Expected:** a table of verb → code → whether the message enumerates. `PRV-2002` on the read,
register, prepare and validate/explain paths ("Known streams: [...]"); `PRV-8002` on drop, pause,
resume and subscribe ("this node has [...]"); `PRV-4023` from `ViewQuery` ("this server serves
[...]"); `PRV-2003` on `GET /api/v1/streams/{name}` ("Registered: [...]"). **`LIST` is the only verb
that filters**, and it is the only verb whose output was designed to be a list. Every other verb
returns a list by accident, in an error, to a principal who asked for one name.
**Vacuity:** `ann` asking the same verbs for the same absent names must receive the same
enumerations, which proves the messages are not principal-aware in either direction — the fix is
uniformly missing, not partially applied.

## SECX-094 — every path that reaches a row, and whether a policy stands in front of it
**Intent:** the closing question of the area: is there any way to get data out of this node without
passing a policy check? Answer it by enumeration, not by assertion.
**Falsifier:** one entry point that returns a row, a schema or a plan without a `mayRead`,
`mayAdminister` or `mayRegisterQuery` call on the same call stack.
**Setup:** static enumeration of every public entry point, then a live probe of each.
**Steps:** for each entry point, trace the call to the nearest policy call and then probe it as
`carol` against `payroll_view`:

| Entry point | Expected gate |
|---|---|
| Flight `getFlightInfoStatement` | `schemaOf` → `mayRead` |
| Flight `getStreamStatement` | `execute` → `mayRead` |
| Flight `createPreparedStatement` | `prepare` → `mayRead` |
| Flight `getFlightInfoPreparedStatement` | `prepare` → `mayRead` |
| Flight `getStreamPreparedStatement` | `prepare` + `execute` → `mayRead` ×2 |
| Flight `acceptPutPreparedStatementQuery` (DoPut of bound values) | **none** — it decodes the caller's parameters and returns a bound handle with no policy call; verify whether the handle it returns is usable and by whom |
| Flight `getStream` on a subscription ticket | `mayRead`, **after** `require(viewName)` |
| Flight `doAction` REGISTER / DROP / PAUSE / RESUME / LIST | `mayRegisterQuery`+`mayRead` / `mayAdminister` ×3 / `mayRead` per entry |
| Flight `getSchema` | `UNIMPLEMENTED` — record it as unreachable rather than ungated |
| Flight SQL metadata (`getTables`, `getCatalogs`, `getSqlInfo`, …) | `getFlightInfo` succeeds, `getStream` throws `Not implemented.` — check whether the **`getFlightInfo`** leg discloses anything before failing |
| Flight `doAction` for any non-`pravaha.` type | falls through to `super.doAction` — enumerate what the Flight SQL base class answers and to whom |
| HTTP, all six paths | **none** |
| `/status` HTML page | **none**, and it is outside `OPEN_PREFIXES` so only the filter gates it |
| `ServedView.scan()` / `get(Consistency,…)` in-process | none — embedded only, and `get(…)` has no transport caller at all (I-6) |
| `RegisteredQuery.subscribe(...)` in-process | none — embedded only |
**Expected:** the table, completed with a live result per row. The two to report are
`acceptPutPreparedStatementQuery` and the Flight SQL metadata `getFlightInfo` legs: both are reachable
by any authenticated caller and neither has been looked at. Everything the HTTP surface answers is in
the "none" column, which is SECX-042–057 restated as a structural fact.
**Vacuity:** every gated row must actually refuse `carol` in the probe, or the table is a reading of
the source rather than a test of the build.

## SECX-095 — the resource dimension: what one authenticated caller can take from everybody
**Intent:** requirement 2 is about who reaches data; this is about who keeps everyone else from it.
`ReadAdmission` exists, has per-tenant quotas, and **is never wired on the server** — `PravahaNode`
never calls `admitting(...)`, so every server node runs `ReadAdmission.UNLIMITED`.
**Falsifier:** a server node refusing a read with `PRV-` `TENANT_QUOTA_EXCEEDED` or `READ_REJECTED`.
**Setup:** `N-auth` with a view of 200,000 rows; three tokens in three tenants.
**Steps:** (a) `grep -rn "admitting\|ReadAdmission" pravaha-server/src/main/java`; (b) 64 concurrent
full reads as `carol` alone, while `ann` attempts one read and one registration, timing both;
(c) one subscriber that never reads from its socket, with the query committing continuously, for
five minutes — watch `SUBSCRIPTION_HANDOVER_BATCHES` fill and `subscribe.dropped` climb, and measure
whether any *other* subscriber or the query itself slows; (d) 64 KiB and 1 MiB bearer tokens against
both transports; (e) 1,000 registrations of distinct SQL by one token holder.
**Expected:** (a) zero hits — the quota code is unreachable from a server, so a tenant's share is
`Integer.MAX_VALUE`. (b) `ann`'s read and registration are delayed by `carol`'s load with no refusal
and no diagnostic; record the p99. (c) the handover queue is `offer`-not-`put` by design, so the slow
subscriber must lose its own batches and must **not** slow the query or any other subscriber — assert
that the other subscriber's delivery latency is unchanged, since that is the property the design
claims. (d) refused by the filter or the container, never 200, never a hang. (e) record whether
anything bounds the number of standing computations one principal may create; `mayRegisterQuery` is
described as "the decision a resource quota hangs off" and neither shipped policy hangs one off it.
**Vacuity:** (b) and (c) both need a single-caller baseline measured first in the same session, or
"slower" has no referent. (c)'s control subscriber must be receiving rows throughout, or "unchanged
latency" is the latency of nothing.

---

## Coverage note

**95 cases, the budget, and it is short.** The honest cost of the grid this area names is closer to
**150**. What was compressed, and why, so the gaps are chosen rather than discovered:

1. **The Flight matrix is eight verbs, not eleven.** `getSchema`, the Flight SQL metadata commands
   and `acceptPut` are folded into SECX-094 rather than given four principal classes each, because
   two of the three are `UNIMPLEMENTED` and the third returns no rows. That is twelve cells traded
   for one enumeration. If `getSchema` is ever implemented, those cells come back.
2. **The HTTP matrix is four endpoint groups, not seven.** `GET /api/v1/streams` and
   `GET /api/v1/streams/{name}` share a case, as do `validate` and `explain`, and `/status`,
   `/api/v1/status` and the actuator share one. Twelve cells traded for six. Defensible only because
   the finding is uniform across the surface — no controller has a principal — and a uniform finding
   does not need twenty-eight witnesses.
3. **The TLS dimension does not cross the principal dimension.** SECX-065 establishes orthogonality
   with a single 4 × 6 table rather than as 24 cases. If SECX-065 finds *any* cell where TLS changes
   an authorization outcome, the remaining 23 must be written.
4. **The Python SDK and the console are not covered here** and are `SDKX`'s. They are the two clients
   a non-Java user actually holds, and neither has ever been exercised against a policy-bearing node.
5. **Not covered by anyone, and it should be**: a second node. Every case in this file is one process.
   Cluster modes are unreachable from configuration (S-3, E-6), so a cross-node authorization
   question — does a principal's entitlement mean the same thing on two nodes sharing a journal? —
   cannot be asked yet. Recorded so that it is a known gap rather than an oversight.
6. **The largest untestable area remains the same as round 1's**: a deployment with real rules on a
   real node. SECX-004 is the case that pins it, and until it is fixed, every rule-bearing case in
   this file runs against `QaPolicy` in a harness. The execution wave should tag those verdicts
   `[harness]` so that a later reader can tell which results describe a shipped configuration and
   which describe the engine's capability.

**Ordering note for the execution wave.** Run Group A first (it decides which nodes can exist), then
D (it decides which transport the rest run on), then B, C, E, F. Groups G and H mutate policy and
registry state and must run last or on an isolated node — SECX-085 and SECX-086 in particular leave
the registry in a state later cases would misread, and SECX-083 requires a restart that destroys
every other group's fixture.
