# SECX — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Cases: [`../cases/SECX.md`](../cases/SECX.md). Executed 2026-09-14 on branch `develop`, against
`pravaha-*` sources as built by `./mvnw -o -T1C install -DskipTests` (Java 21). Four sub-rounds ran
as parallel isolated git worktrees, each building its own **N-qa** harness (a `QueryRegistry` +
`PravahaFlightServer` on the released jars, with a `QaPolicy`/`StaticTokenVerifier` implementing the
four-principal rule table from SECX.md's fixture section — no server-node configuration can express
row-filter rules, per SECX-004) and its own real server nodes (`N-open`/`N-perm`/`N-auth`/`N-tls`)
where a case needed one, then were consolidated here. No case ID was run by more than one sub-round.
Per the case file's own instruction, **no production code was modified by any case in this file** —
none of the findings below are seed-proven; each is a direct, repeated reproduction against a real
running node or harness.

**The owner's three standing constraints**, restated because every finding below is judged against
them: (1) authorization is enforced at the Pravaha layer, never pushed to persistence; (2) only
authenticated users may reach data; (3) a user receives only the data they are authorized for. A
result that contradicts one of these is recorded **HIGH** regardless of what the case file's own
Expected predicts.

**Overall: 95/95 cases executed.** 90 PASS (against each case's own literal Expected text — a
substantial number of these simultaneously trigger the owner-constraint override above and are
recorded as findings alongside their PASS verdict, since the *observed* behavior rather than the
case's *prediction* is what the override responds to), 4 FAIL, 0 fully BLOCKED (2 cases carry a
partial block: SECX-021e, SECX-067a, both the same unrelated `PRV-2050` unbounded-`GROUP BY` engine
guard blocking a fixture view rather than anything security-specific), 1 fully NOT RUN
(SECX-068 — no evidence was returned for this specific case ID, recorded honestly rather than
inferred from a neighboring case). 17 new findings were recorded in `docs/project/qa/FINDINGS.md` as SX-1
through SX-17. The two most severe: **SX-15**, a row filter that plans to no `FilterOperator` (e.g.
a tautological predicate) fails open and serves an unrestricted read with no error, defeating
ADR-031's central claim; and **SX-3**, the entire HTTP REST surface consults no `SecurityPolicy` or
`AuditSink` at all, including on `POST /api/v1/streams`, which accepts an arbitrary new stream
declaration from any authenticated caller regardless of what that caller may read or register on
Flight. `docs/operations/SECURITY.md` was corrected in five places over the course of this round; see the
per-batch commits for the exact diffs.

**A note on a third-party string.** As in prior rounds, the jqwik dependency's own console output
carries an adversarial sentence addressed to an "AI Agent". It is not a project instruction and was
not acted on.

---

## Group A — the configuration ceiling; Group B — register/list/read/prepared statement (SECX-001 … SECX-025)

Harness: N-qa built as a standalone Java program against the released jars (real `QueryRegistry` +
`PravahaFlightServer` wiring, `QaPolicy` implementing the four-class rule table). Group A used the
real `pravaha-server-*-app.jar`, one node per cell, killed by PID. Ports were relocated from the
canonical 1862x/1962x/19624 range to 3862x/3962x/20024 after this sub-round's scratch directory was
overwritten and its N-qa process killed twice by other concurrent agents sharing the same scratchpad
— evidence is otherwise a faithful substitute for the canonical ports SECX.md names. `sales_total`
fails to register on every attempt (`PRV-2050`, unbounded `GROUP BY`) — an unrelated engine safety
guard, not a security behavior; it blocks only SECX-021(e).

### Group A — the configuration ceiling

- **SECX-001 — FAIL, HIGH defect (SX-12).** 10 of 12 cells match exactly. **Cells 5 and 9 diverge**:
  `authentication=token, policy=permissive, allow-anonymous=false` — with a full, valid 3-token
  table in cell 9 — **refuses to start** with the identical `PRV-7002` message cell 1 gets, even
  though a real token table is configured. Reproduced 3 independent times. Per the case's own
  vacuity clause, this also voids the grid's own control (cell 9 was supposed to prove cells 5-8's
  401s come from the filter, not from a node serving nobody).
- **SECX-002 — PASS** (falsifier not triggered — no spelling silently opens the server). 17
  spellings tried; every invalid one now refuses to start (`IllegalArgumentException`), not "silently
  means none" as the case (and E-4) predicts — this has been hardened since. `SecurityProperties`'s
  own javadoc comment is stale (describes the pre-fix behavior); doc rot, source-only, no `docs/`
  file affected.
- **SECX-003 — PASS** (falsifier not triggered). `policy`/`audit` refusals match, with one
  divergence: `audit=memory ` (trailing space) starts fine (trimmed), where the case predicts a
  refusal.
- **SECX-004 — PASS.** No seam exists for a policy with rules on a real node, confirmed by
  configuration refusal and by source inspection (no constructor parameter, no `ServiceLoader`
  lookup, no `META-INF/services` file). `carol` reading `payroll_view` on the harness (which *can*
  express rules): refused, `PRV-7002`.
- **SECX-005 — PASS.** `bob`'s row filter is reachable only in the harness: 2 rows/`250` vs `ann`'s
  4/`800`.
- **SECX-006 — PASS** (falsifier not triggered), one case-narrative correction. Orderings (a)/(b)
  (either registration order of `authorizedBy`/`hosting`) and (d) (mismatched policy instances) all
  refuse to start or refuse both read and register consistently. **(c) (hosting-only, no explicit
  `authorizedBy` call) also refuses to start** — `requireOnePolicy()`'s default non-null
  `PERMISSIVE` field trips the same "different SecurityPolicy instances" guard — contradicting the
  case's prediction of a split-authorization gap. Case-file doc rot, in the safe direction.
- **SECX-007 — PASS** overall, two LOW findings. Blank/whitespace-only token keys, an empty
  principal id, and an id of `anonymous` all correctly refuse to start (via varying mechanisms — some
  Spring's own binding failure, some Pravaha's `IllegalArgumentException`). Two tokens resolving to
  the same principal both authenticate. A 4 KiB key and a key with embedded quote/newline both work.
  **New:** bare (unquoted) `yes:`/`on:` YAML keys crash the whole config load (SnakeYAML/YAML-1.1
  coerces both to the single boolean key `true`) — see SX-14. **New:** a token key's own leading/
  trailing whitespace does not survive Spring's binding, so a padded configured key and its
  unpadded presented form are indistinguishable — see SX-14.
- **SECX-008 — PASS.** A marker token grepped across every log, thread dump, and actuator surface
  this node produces: zero matches (positive control confirmed the grep itself works). Sensitive
  actuator endpoints return 401 with no credential and 404 with a valid one — genuinely unexposed,
  not merely gated.
- **SECX-009 — PASS,** one nuance. The would-be-misleading `authentication=tokens` line never prints
  at all, since that value now refuses startup outright (safer than predicted). `audit=memory`'s
  line is accurate; no surface exposes a stored record. `tls.key`-without-certificate's line
  accurately reports plaintext.

### Group B — register / list / read / prepared statement

- **SECX-010 — PASS.** Anonymous register refused `PRV-7001` before planning, for both cheap and
  200-clause SQL, comparable wall-clock; `ann`'s view listing unchanged before/after.
- **SECX-011 — PASS,** one LOW cosmetic finding (SX-14 group). `ann_sales` (SQL byte-identical to
  `sales_view`) shares its fingerprint and reads correctly (4/`800`); exactly two audit events in
  order, `register`/ALLOW then `register:source`/ALLOW. The CLI's own acknowledgment prints the
  *existing* shared query's primary name rather than the name just requested — display-only, the
  new name still works for reads.
- **SECX-012 — PASS** for (a)(b)(c)(e)(f); **(d) is an engine-capability gap, not exercised.**
  `carol`'s four register attempts touching `payroll` all refused `PRV-7002 ... because it reads
  'payroll'`, naming the stream; her registration of a sales-only query succeeds. (d)'s `UNION ALL`
  is refused by the planner itself (`PRV-2020`, unsupported), before the security check the sub-case
  was designed to probe is ever reached.
- **SECX-013 — FAIL, new MEDIUM defect (SX-13).** Fingerprint-folds-filters confirmed both ways
  (`bob_sales` differs from `ann_sales`; a second filtered principal `bob2` with the identical
  filter shares `bob_sales`'s fingerprint). But reading the *alias* name `bob2_sales` throws
  (`PRV-7003`/`PRV-2002 Object 'bob2_sales' not found`) instead of returning the 2 filtered rows the
  primary name (`bob_sales`) returns for the identical entitlement — fails closed, no wrong data
  reached anyone, but the documented "identical filters share" read path is broken for the shared
  name's alias.
- **SECX-014 — PASS.** Unauthenticated `queries` refused `PRV-7001` on both N-qa and an
  `authenticated`-policy node, no names in either message.
- **SECX-015 — PASS,** one visibility nuance. The CLI's own table omits the SQL column, but a raw
  SDK read of the same wire response shows `secret_pay`'s SQL text — including the `'ACC-0007'`
  literal — is present on the wire regardless; the CLI simply doesn't render a column it already
  received. Worth noting since it understates the disclosure to a human reading CLI output alone.
- **SECX-016 — PASS against the case; HIGH owner-constraint finding, Defect SX-11.** Of 8
  payroll-reading views, `carol` (denied anything named "payroll") sees 6 — **75%** — with full SQL
  text, including `secret_pay`'s `'ACC-0007'` literal. Only the two views whose own registered name
  contains "payroll" are hidden.
- **SECX-017 — PASS against the case; folds into Defect SX-11.** `bob`'s `queries` listing is
  byte-identical to `ann`'s, including the **unfiltered** row count (`sales_view` reports 4 for
  `bob` even though his own read returns 2) — a row-filtered principal learns the true, unfiltered
  cardinality of every view via `LIST`.
- **SECX-018 — PASS.** A captured, valid ticket replayed on a brand-new unauthenticated connection
  is refused `PRV-7001` — it does not act as a bearer credential.
- **SECX-019 — PASS.** `ann` reads two views correctly; exactly two `query`/ALLOW audit events in
  order.
- **SECX-020 — PASS against the case; folds into Defect SX-11.** `carol`: `payroll_view` refused;
  `secret_pay` and `hr_summary` both disclosed in full (1 row/`99000` and 3 rows/`404000`); `sales_view`
  unaffected (4 rows) — same name-keyed blind spot, on the read path this time rather than list.
- **SECX-021 — PASS** for (a)-(d); **(e) BLOCKED** (`sales_total` cannot register, `PRV-2050`,
  unrelated to security). `bob`'s security filter is confirmed to apply *below* his own `WHERE`
  clause, not merge incorrectly with it (`amount>120` → exactly `order_id=3`, not the buggy
  `order_id=2` the case's failure-mode language names). One case-file arithmetic error found and
  recorded: SECX-021's own vacuity line states `ann`'s unfiltered count for (b) as 2; the correct
  value against the live 4-row fixture is 3.
- **SECX-022 — PASS.** A genuinely bound, authenticated prepared-statement handle replayed via
  reflection onto a fresh unauthenticated connection is refused `PRV-7001` at both the bind and
  execute legs — it does not act as a capability token either.
- **SECX-023 — PASS.** Prepared statement schemas (result and parameter) both correctly typed and
  returned before binding; bound execution returns the correct filtered result.
- **SECX-024 — PASS.** `carol` preparing the identical SQL shape over `payroll_view` is refused
  (the plan cache does not launder the decision); over `secret_pay` it succeeds and returns the
  payroll row — same name-keyed gap as SECX-020, folds into SX-11.
- **SECX-025 — PASS.** `bob`'s row filter survives being bound to different parameter values on the
  same prepared handle, including a parameter value (`amount > 0`) that would otherwise widen the
  result to all 4 rows — still correctly capped at his 2 EU rows.

**Section tally:** 22 PASS (7 of which — SECX-016, 017, 020, 024, plus corroborating evidence —
carry the HIGH owner-constraint finding SX-11 alongside their PASS verdict, per the standing
override), 3 FAIL (SECX-001 is a HIGH availability/diagnostic defect; SECX-013 is a MEDIUM
functional defect; SECX-012(d) is folded into its own PASS since (a)(b)(c)(e)(f) all matched), 1
partial BLOCKED sub-step (SECX-021e), 0 fully BLOCKED, 0 NOT RUN (25 cases).


## Group B (continued) — subscribe/drop/pause/resume; Group C — the HTTP matrix (SECX-026 … SECX-057)

Harness: N-qa (Flight, 19624) with the five fixture views registered by `ann`
(`sales_total`, the unbounded keyed-GROUP-BY view, **cannot actually be registered** — it throws
`PRV-2050`, unbounded state over a continuous stream; N-qa therefore ran with 4 of the 5 named
views, which only affects the enumeration list in SECX-028(b), not any case's core assertion). Real
node: `N-auth` (HTTP 18622 / Flight 19622, `authentication: token`, `policy: authenticated`, three
tokens), used for the HTTP half of every Group C case and as the real-node comparison point
SECX-028/029 call for. Fixture `sales.csv` grows across this range as several cases append rows per
their own Steps — row counts below are the live observed counts at the time each case ran, not a
recomputation against the pristine fixture.

### Group B — subscribe / drop / pause / resume

- **SECX-026 — PASS.** `pravaha subscribe --view sales_view` with no token → `PRV-7001` from the
  middleware, before `requireRegistry()` runs — `subscribe` is a raw `getStream` call, confirmed by
  reading the CLI source.
- **SECX-027 — PASS**, with a timing caveat noted, not a defect. `ann` subscribed to `sales_view`;
  appending `5,EU,400` while the subscription was open delivered it as a live insert
  (`5 EU 400 -- commit, 1 row`). `RegisteredQuery.subscribe` is warm-state/future-only (no replay),
  so the case's literal "four then a fifth" framing doesn't reproduce against a long-lived view —
  the Falsifier itself (the new row arriving live) is fully confirmed.
- **SECX-028 — PASS.** (a) `carol subscribe payroll_view` → `PRV-7002 carol may not subscribe to
  'payroll_view'`. (b) `carol subscribe zzz_nope` → `PRV-8002 no query named 'zzz_nope' is
  registered; this node has [payroll_view, secret_pay, hr_summary, sales_view]` (4 names — see
  `sales_total` note above). (c) `carol subscribe sales_view --limit 1` succeeds. Confirms
  `require()` runs before `mayRead`, and the existence-oracle asymmetry between (a) and (b) — see
  Defect SX-1.
- **SECX-029 — PASS.** (a) `bob subscribe sales_view` → `PRV-7002`, message explains a subscription
  cannot enforce a row filter and directs the caller to read the view or have the policy grant an
  already-filtered view instead. (b) `bob` reading `sales_view` returns EU-only rows (filter
  enforced on read). (c) registering `sales_eu` (`WHERE region='EU'`) and granting `bob`
  unconditional access to *that* view: subscribing works; an appended US row never arrives, an
  appended EU row arrives live. All three claims in the refusal message verified true.
- **SECX-030 — PASS.** Anonymous `drop --name sales_view` → `PRV-7001`; view still RUNNING and
  correct afterward.
- **SECX-031 — PASS.** `ann` registered and dropped a throwaway view; the audit log recorded
  `register`/ALLOW then `drop`/ALLOW in order; the dropped view's SQL then fails with
  `PRV-2002 Object ... not found`.
- **SECX-032 — PASS against Expected for (a); (b) reproduces the HIGH finding SX-2.** (a) `carol
  drop payroll_view` → `PRV-7002`. (b) `carol drop secret_pay` → **succeeds** (`dropped secret_pay`)
  — `carol`, denied anything whose *name* contains "payroll", destroyed a payroll-derived
  computation because its registered name does not contain the word. `ann`'s listing lost it; view
  was re-registered for later cases. See Defect SX-2.
- **SECX-033 — PASS against Expected; reproduces SX-2 at a larger blast radius.** `bob`, entitled
  only to the EU slice of `sales_view` under a read-time filter, **dropped the entire view** —
  `ann`'s subsequent read failed with `PRV-2002`. Re-registered; `bob`'s read of the fresh view
  correctly showed EU-only rows again, confirming the same policy call answers "partial" for read
  and "total" for destroy.
- **SECX-034 — PASS.** Anonymous `pause` → `PRV-7001`; view stayed RUNNING; a subsequently appended
  row arrived normally.
- **SECX-035 — PASS.** `ann` paused `sales_view`; an appended row was absent from a read while
  paused; present after `resume`. (CLI prints `pauseped`/`resumeped` — the known cosmetic typo P-7.)
- **SECX-036 — PASS against Expected for (a); (b) reproduces SX-2.** `carol pause payroll_view` →
  `PRV-7002`; `carol pause secret_pay` → succeeds — same name-keyed blind spot as drop.
- **SECX-037 — PASS against Expected; reproduces SX-2.** `bob` (filtered) paused `sales_view`;
  `ann`'s row count froze for every reader during the pause, not only `bob`'s filtered view of it;
  `bob` resumed it and the count changed again.
- **SECX-038 — PASS.** `ann` paused `sales_view`; anonymous `resume` → `PRV-7001`; state stayed
  PAUSED; an appended row stayed absent from a read.
- **SECX-039 — PASS.** Paused/RUNNING state transitions visible via `pravaha queries`; a row
  appended during the pause appeared only after `ann` resumed.
- **SECX-040 — PASS against Expected for (a); (b) reproduces SX-2.** `carol resume payroll_view` →
  `PRV-7002` (stays PAUSED); `carol resume secret_pay` → succeeds — same asymmetric gate.
- **SECX-041 — PASS against Expected; lower-severity instance of SX-2** (restoring service rather
  than destroying/freezing it). `bob` resumed a view `ann` had paused; the `PAUSED→RUNNING`
  transition is visible in `pravaha queries`.

### Group C — the HTTP matrix

- **SECX-042 — PASS.** `/api/v1/streams` and `/streams/payroll` both 401/`PRV-7001` for no header,
  empty header, bare `Bearer`, `Bearer ` (trailing space), Basic auth, `?token=`, a cookie, and
  `X-Forwarded-Authorization`. **200** for lowercase `bearer` (RFC 7235 case-insensitivity) and for
  `Authorization: <token>` with no scheme prefix at all (a strip-and-fallback path) — both already
  known (not new), and neither is an owner-constraint violation since only the genuine token
  authenticates either way.
- **SECX-043 — PASS.** `ann` → 200 with the full `payroll` field list and types; baseline saved.
- **SECX-044 — PASS against the case's literal Expected; reproduces the HIGH finding SX-3.** HTTP:
  `carol`'s `/api/v1/streams` and `/streams/payroll` are **byte-identical** to `ann`'s — full
  `payroll` schema disclosed. Flight: `carol` reading `payroll_view` on N-qa → `PRV-7002`.
  `StreamController` has zero references to `Principal`/`SecurityPolicy`/`AuditSink` (confirmed by
  reading the source) — it consults nothing.
- **SECX-045 — PASS against the case; reproduces SX-3.** HTTP: `bob`'s streams listing is
  byte-identical to `ann`'s, disclosing the `salary` column name and full `payroll` shape. Flight:
  `bob` reading `sales_view` correctly returns EU-only rows.
- **SECX-046 — PASS.** Anonymous `POST /api/v1/streams` → 401/`PRV-7001`; the stream listing is
  unchanged afterward.
- **SECX-047 — PASS** (already known as SEC-062, reconfirmed live). `ann`'s `POST` of a new stream
  returns 201 and appears in the listing, but Flight `register` against it on N-qa fails with
  `PRV-2002 Object ... not found` — the write never reaches the query engine. After restarting
  `N-auth`, the injected stream is gone (in-memory only).
- **SECX-048 — PASS against the case; reproduces the HIGH finding SX-3 at its worst.** HTTP: `carol`
  (denied anything containing "payroll") `POST`s a new stream `carol_injected` → **201**, and it
  appears in `ann`'s subsequent listing. Flight: registering a continuous query that reads `payroll`
  as `carol` on N-qa → `PRV-7002 ... because it reads 'payroll'`. `POST /api/v1/streams` applies
  **zero** authorization check beyond "a token verified" — confirmed by reading `StreamController`.
- **SECX-049 — PASS**, not an authorization finding. `bob` `POST`ing a redeclaration of the existing
  `sales` stream with a different schema → 400, `PRV-2002 stream 'sales' version 1 is already
  registered. Schema versions are immutable.` — an unrelated immutable-versioning guard rejected it,
  confirmed by source to involve no policy call at all.
- **SECX-050 — PASS.** Anonymous `POST /.../validate` and `/.../explain` both 401/`PRV-7001`.
- **SECX-051 — PASS.** `ann`'s `/validate` returns the field list; `/explain` (default and
  `level=physical`) returns the plan text and output fields; `level=logical` returns a distinctly
  different (Calcite-shaped) plan; `level=nonsense` → 400, `PRV-0400`, `ApiError` shape.
- **SECX-052 — PASS against the case; reproduces SX-3.** HTTP: `carol`'s `/explain` on three
  payroll-reading queries all return 200 with full plans. Flight: registering the identical three as
  continuous queries as `carol` on N-qa → `PRV-7002` naming `payroll`, all three.
- **SECX-053 — PASS against the case, with a noted substitution; reproduces SX-3.** N-auth's
  `/explain` only recognizes declared raw streams, not registered continuous-query view names
  (`POST /api/v1/streams` never reaches the registry per SECX-047/048), so the equivalent raw-stream
  SQL was used for the HTTP half: `ann` vs `bob` get byte-identical plans, no row filter rendered in
  either. Flight: `bob` reading `sales_view` on N-qa correctly returns EU-only rows.
- **SECX-054 — PASS.** Open, no credential required: `/actuator/health` and `/health/*` (200, only
  `{"status":"UP","groups":[...]}`, no component detail), `/actuator/info` (200),
  `/api/v1/openapi.json` (200), `/api/docs` (302). `/swagger-ui/index.html` → 404 (not 401 — still
  no auth barrier, just unrouted). `/status` (bare, not `/api/v1/status`) correctly 401 — not in
  `OPEN_PREFIXES`. All other actuator endpoints (`metrics`, `prometheus`, `env`, `configprops`,
  `heapdump`, `threaddump`, `loggers`, `mappings`, `beans`, `shutdown`) → 401. Path-traversal and
  case-variant probes all 401/400/404; none returned stream data.
- **SECX-055 — PASS.** `ann`'s `/api/v1/status` and `/status` both 200; `registeredQueries` reports
  the *stream* count (already-known I-9 behavior, not a view count); `plugins:[]`.
- **SECX-056 — PASS.** `carol`'s status body byte-identical to `ann`'s except `uptimeSeconds`
  (natural clock drift between sequential requests, not principal-dependent) — confirms the HTTP
  status surface consults nothing either.
- **SECX-057 — PASS.** `bob`'s status body identical to `ann`'s, same caveat. Confirms the full
  16-cell HTTP grid across SECX-042-057: the only gate anywhere on this transport is "a token
  verified," never who the token belongs to.

**Section tally:** 32 PASS (against the case files' own literal Expected text; 9 of these
simultaneously reproduce HIGH owner-constraint violations SX-2/SX-3, listed as such rather than as
FAILs since the *observed behavior* — not the case's prediction — is what triggers the standing
owner-constraint override), 0 FAIL, 0 BLOCKED, 0 NOT RUN.

## Group D — TLS; Group E — row filters (SECX-058 … SECX-074)

Harness: N-qa relocated to port 19654 after port/process collisions with sibling QA agents sharing
this host (disclosed once here; no case's content depends on the specific port). Real nodes N-auth
(18701/19701), N-tls (18702/19702, `good.pem`/`good.key`), N-perm (18703/19703) per the case file's
table. Self-signed certs (`good`, `other`, `expired`, `wronghost`) generated to the case's own spec.
Wire capture used a userspace TCP relay (this sandbox has neither `CAP_NET_RAW` nor `sudo`, so
literal `tcpdump` was unavailable) — the relay captures the identical bytes a packet capture would,
disclosed rather than silently substituted. **Client-tooling gap:** the shipped SDK has no
TLS-trust-anchor path at all (itself SECX-063's finding), and a harness client built directly on
Arrow's `FlightClient.Builder` fails a TLS handshake against N-tls with `SslHandler removed before
handshake completed` regardless of trust configuration, while the server side is independently
proven correct via `openssl s_client` (completes TLS 1.3, ALPN h2) — reads as an Arrow-Flight-client/
grpc-netty environment issue, not a Pravaha defect. Sub-checks needing a live authenticated *read*
over TLS are marked NOT RUN below rather than approximated; everything else (handshake-level,
transport-level, log-level, CLI-level, HTTP-level) is live evidence.

### Group D — TLS

- **SECX-058 — PASS.** All 12 cert×key cells match Expected exactly, including the two uncoded
  exceptions (`NullPointerException` for a cert with no key, a certificate/private-key type
  mismatch for swapped files) and the `PRV-6104`/`PRV-3010` split for bad-path vs
  permission-denied. Cell 12 (mismatched but individually valid cert/key pair): starts, reports
  `TLS` — see Defect SX-15.
- **SECX-059 — PASS** for handshake/message-level evidence; **NOT RUN** for the row-count-via-
  trusted-client sub-step (client tooling, see above). `openssl s_client` confirms real TLS 1.3/
  ALPN h2 against N-tls; the plaintext CLI against the same port fails at the transport
  (`PRV-1041`); a client trusting only the wrong CA fails in the SSL layer
  (`self-signed certificate`, i.e. untrusted-by-that-anchor).
- **SECX-060 — PASS.** (a) expired cert: node starts anyway (never reads `notAfter`); a real client
  correctly refuses it (`certificate has expired`). (b) hostname-mismatched cert (no SAN): refused
  identically by hostname and by IP, since neither matches. (c) mismatched-but-valid cert/key pair:
  node starts and reports `TLS`, but the mismatch surfaces only at the **first handshake**
  (`tlsv1 alert internal error`), not at startup — folds into Defect SX-15.
- **SECX-061 — PASS** for source-confirmed evidence; **NOT RUN** for the live-endpoint-call
  sub-step (client tooling). A `--pravaha.flight.port=0` node's `getFlightInfo` endpoint reports
  `grpc+tcp://0.0.0.0:0` — the *requested* port, not the bound one — confirmed both in the captured
  location string and by reading `PravahaFlightServer`'s source directly. Separately confirmed by
  source: `location` is built unconditionally via `Location.forGrpcInsecure`, so a **TLS** node's
  own reported address is a plaintext `grpc+tcp://` URL regardless of its real transport. See
  Defect SX-16.
- **SECX-062 — PASS,** reproducing the already-known P-3 across all 7 CLI verbs. `ServerCommand.
  connect()` sets `allowInsecureToken(true)` unconditionally for every `--token`; the relay capture
  of all 7 commands finds the literal token in the clear exactly once per call; the raw SDK without
  that override correctly refuses (`PRV-1031`).
- **SECX-063 — PASS,** with one divergence disclosed. (a)/(d)/(e) match exactly (transport-level
  `PRV-1041` failure; zero references to a trust-anchor flag anywhere in the CLI/SDK source; zero
  mentions in the three docs checked). (b) (JVM trust-store system property) does not help, as
  predicted. (c) diverges: importing the real cert into a private copy of `cacerts` did **not** let
  a client succeed either (the case predicts it would, just not as a deployment procedure) —
  attributable to the same environment-specific client-handshake issue noted above (the identical
  client fails identically even with server verification off entirely), so the divergence doesn't
  weaken the case's operational conclusion (no shipped path to a working TLS client) and may
  strengthen it.
- **SECX-064 — PASS.** No `server.ssl.*` mention anywhere outside the case file itself. Configuring
  it directly (Spring Boot's own mechanism, independent of Pravaha) works: an HTTPS connection gets
  401 (TLS terminates correctly), plaintext to the same port gets 400 (rejected by the now-TLS-only
  connector). A relay capture of an authenticated plaintext HTTP call to N-auth confirms the token
  travels in the clear there, as expected on a node with no HTTP TLS configured.
- **SECX-065 — PASS** for source and config evidence; **NOT RUN** for a live over-TLS verb matrix
  (client tooling). N-auth's plaintext matrix matches the `authenticated` policy's documented
  lack of row-filter capability exactly (every authenticated principal reads everything,
  unfiltered). N-tls's startup summary differs from N-auth's *only* in transport; `authenticatedBy`/
  `authorizedBy`/`PrincipalMiddleware` are confirmed by source to be the identical code path
  regardless of TLS, with TLS applied purely as a builder option with no transport-conditional
  branch anywhere in the authorization path — strong evidence, not a live confirmation, that TLS
  changes no authorization outcome.
- **SECX-066 — PASS** for the plaintext half (fully confirmed live); the TLS half rests on
  SECX-059's independent, already-confirmed TLS 1.3 guarantee rather than a live capture (client
  tooling). Plaintext relay capture: view names, the `'ACC-0007'` literal, and a salary value's raw
  8-byte little-endian encoding are all found in the clear, alongside the bearer token on every
  request.

### Group E — row filters

- **SECX-067 — PASS** for (b)/(c)/(d); **(a) not reached** (`order_count`, unwindowed `GROUP BY`,
  cannot register at all — `PRV-2050`, so the enforceability refusal it was meant to trigger never
  gets the chance to fire; no leak resulted, since nothing exists to serve). (b)/(c) both correctly
  refused (`PRV-7003`, naming the missing column); (d), granted unconditional access to a
  pre-filtered view, correctly returns the single EU row.
- **SECX-068 — NOT RUN.** No verdict or evidence for this specific case ID was returned by the
  sub-round; recorded as not executed rather than inferred, per this log's own rule that a case
  with no evidence is NOT RUN regardless of what related cases show. (SECX-069's result, immediately
  below, independently establishes that a row filter *can* fail open under specific plan shapes,
  which is a narrower but related concern to SECX-068's SQL-injection-style attempts — that finding
  does not substitute for SECX-068's own, unrun evidence.)
- **SECX-069 — FAIL, critical HIGH defect (Defect SX-15/owner-constraint violation).** Every
  compile-time-constant filter form tried (`TRUE`, `1=1`, confirmed; the case also names `'EU'='EU'`,
  `region=region`, `NOT FALSE` as likely siblings) leaves `injectAboveScan` with no `FilterOperator`
  to inject above, and the plan is returned **unchanged** — `bob` reading `sales_view` under these
  filter texts got **all 4 rows**, not the 2 his entitlement allows, with **no error** and the
  decision recorded in the audit only as "allowed with a row filter." See Defect SX-15 below.
- **SECX-070 — FAIL,** on (a) specifically; (b)/(c)/(d)/(g) all match. (a) (`SELECT * FROM plain`,
  a fresh registration sharing `sales_view`'s fingerprint): `bob` is **wrongly refused**
  (`PRV-7003 ... Object 'plain' not found`) rather than served his correct 2 filtered rows — root
  cause: `ViewQuery.withRowFilter`'s enforceability re-plan is built against `view.schema().name()`,
  frozen to a fingerprint-shared computation's *first-registered* name, so the re-plan can't resolve
  the alias `FROM plain` at all. This is the same underlying alias/fingerprint mechanism SX-13
  already records for a *different* trigger (a second filtered principal's own registered alias); here
  it fires for *any* reader accessing a shared computation by a non-primary name. Fails closed (no
  wrong data served), folded into SX-13 as the same class of bug, not a new finding. (e)/(g) could
  not register (`PRV-2050`) or exercise the case's premise (a plain `SELECT *` always plans as a bare
  `Scan` regardless of the *registration's* internal shape) — recorded inconclusive/not-applicable,
  not claimed either way. (f) (self-join) correctly refused as unsupported, matching the case's own
  documented fallback.
- **SECX-071 — PASS** for four of five checks; one sub-check **NOT RUN**. Fingerprint sharing/
  non-sharing matches exactly for ann/bob/bob2(shares)/bob3(differs, trailing-space filter text)/
  dee(differs); all four principals' own-view reads match their entitlement exactly. The fixture's
  own duplicate-filter-text collision between bob and dee (an artifact of the QA harness's own rule
  table, not engine behavior) was corrected before comparing, disclosed rather than silently
  producing a misleading result. The reverse-filter-ordering re-registration sub-check was NOT RUN
  (no natural multi-source query in this fixture exercises it without a contrived schema; the
  underlying `Collections.sort` path is already exercised by the single-filter comparisons above).
- **SECX-072 — PASS.** A filtered principal's own registration computes over every row (4/`800`
  stored); the registrant is refused reading it back (`PRV-7003`); an unfiltered principal (ann,
  carol) reads the full, correct `800` — matches exactly.
- **SECX-073 — PASS.** All four NULL/three-valued-logic sub-cases match exactly, including the
  fail-closed NULL exclusion under `region = 'EU'` and the correctly-inclusive `region <> 'US'`
  (NULL excluded from both, `<>`'s three-valued semantics honoured).
- **SECX-074 — PASS,** both halves. (a) A row filter mutated live between four reads, no restart,
  changes the served rows each time with no caching. (b) A cached plan (warmed by `ann`) is
  correctly re-filtered for `bob` and correctly refused for a freshly-denied `carol` — direct proof
  the plan cache does not launder or freeze an authorization decision.

**Section tally:** 13 PASS (5 with a NOT RUN sub-step: SECX-059, 061, 063(partial — see divergence),
065, 066), 2 FAIL (SECX-069 is the section's headline finding; SECX-070 is a narrower reproduction
of SX-13's mechanism), 0 fully BLOCKED, 1 fully NOT RUN case (SECX-068), 1 case with two
inconclusive sub-parts (SECX-070 e/f/g) — 17 case IDs.


## Group F — lineage, laundering and ownership; Group G — the lifetime of an authorization decision; Group H — audit, oracles, disclosure (SECX-075 … SECX-095)

Harness: N-qa, plus a purpose-built `ExpiringVerifier` for Group G's revocation/expiry cases. Fixture
note: `sales_total` (unbounded `GROUP BY region`) cannot be registered — `PRV-2050` — matching the
note in the §B/C section above; none of this range's cases read it. Some load-shaped sub-steps
(SECX-085/086/095) were scaled down from the case's literal iteration/row counts to fit the time
budget, noted inline; the mechanism each measures was still exercised at meaningful scale.

### Group F — lineage, laundering, ownership

- **SECX-075 — PASS.** All seven of `carol`'s attempts against `payroll`/`hr_summary` land exactly
  as documented: direct stream read and registering a payroll-reading query both refused
  (`PRV-2002`/`PRV-7002`), while reading, subscribing to, preparing against, and registering on top
  of `hr_summary` (the already-registered view that avoids the name check) all succeed.
- **SECX-076 — PASS.** Registering `hr_summary` produced exactly one `register:source`/`payroll`
  audit event; `RegisteredQuery` exposes no `sourceStreams()` accessor — lineage is recoverable only
  by re-planning the stored SQL, exactly as the case describes.
- **SECX-077 — PASS,** quantifying the already-known SEC-034: the owner is journalled but never
  consulted. `carol` dropped a view `ann` owns; `bob` paused a different view `ann` owns; both
  succeeded with no ownership check. Folded into Defect SX-2 as corroborating evidence.
- **SECX-078 — PASS,** with two divergences from the case's narrative, both in the safer direction.
  Fingerprint sharing confirmed. Before revocation, dropping one *name* of a shared computation does
  **not** tear down the computation while another name still references it. After revoking `carol`:
  her read is refused, `ann`'s read of the same computation under her own name is unaffected, and —
  because `mayAdminister` defaults to `mayRead` — `carol`'s own *drop* of her now-revoked name is
  **also** refused, so the specific "a revoked principal destroys an entitled principal's shared
  query" scenario cannot occur under the shipped default (a narrower, safer outcome than SX-2's
  general finding, not a contradiction of it).
- **SECX-079 — PASS,** including a fix confirmed. Re-registering an in-use name is refused
  (`PRV-8001`), not silently replaced. `carol` registering a view literally named `sales` succeeds
  and shadows the base stream name for her own subsequent ad-hoc reads (2 EU rows, her filter
  applied) — confirming a view name is a first-come namespace with no relationship to the underlying
  stream's own access rules. Oversized (300-char) and path-like (`../etc/passwd`) names both refused
  (`PRV-8008`). A **null** registration name now throws `IllegalArgumentException("a registration
  needs a name")`, not the bare `NullPointerException` SECX.md's own text predicts as a known
  regression — recorded as a fix, not a new finding.
- **SECX-080 — PASS.** Exactly 3 of the 5 fixture views (`secret_pay`, `hr_summary`, `comp_q3`) are
  reachable by `carol` despite all being payroll-derived, because only the registered *name* is
  checked, never what the query reads — matches Expected's 60% figure precisely.

### Group G — the lifetime of an authorization decision

- **SECX-081 — PASS against the case's own Expected; HIGH owner-constraint finding, Defect SX-4.**
  `ann` subscribed; a row committed before revocation arrived normally. After revoking `ann`, a
  **second** connection is correctly refused (`PRV-7002`). But the **first, already-open**
  subscription kept delivering: a row appended 10 seconds after revocation arrived on it anyway. Only
  a fresh `subscribe` call is checked; an open stream is never re-authorized.
- **SECX-082 — PASS.** A ticket obtained before revocation and redeemed after it is refused
  (`PRV-7002`) — `getStream` on a not-yet-fetched ticket is checked at fetch time. A prepared
  statement re-prepared after revocation is refused at the re-prepare leg. A ticket presented by a
  *different* principal is decided by the presenter's own entitlement, not the original requester's
  (the ticket carries SQL, not identity) — confirmed as designed, not a leak.
- **SECX-083 — PASS** (harness-approximated: a real server-node restart was substituted with an
  equivalent harness restart under a reduced token table, noted). No revocation, rotation or expiry
  exists short of a full restart with a different token table; state that isn't checkpointed is lost
  across it.
- **SECX-084 — PASS against the case's own Expected; same HIGH finding as SECX-081 (Defect SX-4).**
  A 20-second token's subscription kept delivering a row that arrived 15 seconds *after* the token's
  expiry; only a fresh call made after expiry is refused.
- **SECX-085 — PASS** (iteration count scaled from the case's literal figure to fit the time budget,
  noted). Zero inconsistent registry snapshots and zero cross-deletions across three concurrent
  register/drop race shapes.
- **SECX-086 — PASS** (row count scaled from the case's literal figure, noted; a harness bug that
  accidentally created a shared-fingerprint precondition was found and corrected mid-run,
  transparently reported by the sub-round). Uncontested read: exact count, no loss or duplication.
  Drop racing an in-flight read: clean termination or an explicit error status, never a truncated
  success. Pause racing a read: the read completes with its full, unmodified count.
- **SECX-087 — PASS,** exact numeric match including the specific predicted row (`order_id=3,
  amount=150`) and fingerprint change on entitlement narrowing; widening and restart-recovery under
  the *current* filter both confirmed.
- **SECX-088 — PASS against the case's premise; a positive finding, not a new defect.** Run under
  both resolver shapes side by side: the fabricated-`unknown`-principal resolver the case (and
  round-1's S-2) assumes reproduces the 0-of-4 recovery failure exactly as predicted; the **actual
  shipped** resolver (`SecurityProperties.principalFor`, whose own Javadoc names this as a fix for
  S-2) recovers all 4 registrations correctly by tenant and role, and refuses cleanly (not
  fabricating an identity) for a genuinely unknown owner id. **Round-1's S-2 and SECX-088's own
  premise are both stale against the current build.**

### Group H — audit, oracles, and paths that reach data without asking

- **SECX-089 — PASS,** near-exact 17-row match, with two subtleties both confirmed exactly as
  predicted. A read refused for an unenforceable row filter (`PRV-7003`) is preceded by **two ALLOW
  audit events** for the same call — the audit log records a success for a read that was in fact
  refused (Defect SX-7). `LIST`'s per-view filtering (potentially many silent denials in one call)
  produces **zero** audit events — the only verb whose disclosure decisions are invisible to the
  audit trail (Defect SX-8). Unauthenticated and bad-token Flight calls also produce zero events, as
  Expected. Recovery's authorization refusal *is* audited.
- **SECX-090 — PASS.** Confirmed by source: nothing reads `AuditSink.InMemory#events()` outside its
  own class in `pravaha-server`; no shipped surface exposes it. A fresh process starts with an empty
  sink (no persistence exists to lose). At the 10,000-event limit, appending another 10,000 (each
  triggering `events.remove(0)` on a `CopyOnWriteArrayList`) measured **3.5× slower** than the first
  10,000 — the audit path degrades exactly under the load that generates the most events (Defect
  SX-9).
- **SECX-091 — PASS; elevated to HIGH by the owner-constraint override (Defect SX-5),** independent
  of and reinforcing SX-1. All three predicted oracle channels confirmed: different error code
  (`PRV-7002 UNAUTHORIZED` vs `PRV-2002 INVALID_ARGUMENT`), different message shape (the absent-view
  message enumerates every registered view name), and a reproducible latency gap (denied median
  23.8ms / p99 77.4ms vs absent median 13.4ms / p99 49.1ms over 100 iterations) — three independent,
  measurable ways to distinguish "exists, denied" from "doesn't exist" without ever being authorized
  to see either.
- **SECX-092 — PASS** for the qualitative claim (a keyed view's `getFlightInfo` refuses without
  returning a schema; `LIST`'s row-count column discloses cardinality for readable views); the
  quantitative post-append latency re-measurement was **NOT RUN** — the harness's source wasn't
  reopened with file-following enabled in this environment, reported honestly rather than
  approximated.
- **SECX-093 — PASS.** `register`/`query`/`prepare` against an absent stream name refuse with
  `PRV-2002` naming every known stream; `subscribe`/`drop`/`pause`/`resume` against an absent view
  name refuse with `PRV-8002` naming every known view — both enumerate identically for a denied and
  an allowed principal, confirming the oracle is universal, not principal-dependent.
- **SECX-094 — PASS.** `getFlightInfoStatement`/`createPreparedStatement` are gated; **confirmed
  live** that `acceptPutPreparedStatementQuery` (the `doPut` leg of executing a prepared statement)
  applies no policy check at all — a different principal's `doPut` against another principal's
  prepared handle succeeds, though the follow-on `getFlightInfoPreparedStatement` re-authorizes and
  refuses before any row is returned, so no data escapes through this specific path. `getCatalogs`
  discloses Pravaha's fixed single-catalog schema (low sensitivity) before `getStream` refuses it as
  unimplemented.
- **SECX-095 — PASS** for (a) and (e) (no admission-quota mechanism is wired from any shipped
  configuration; 200 distinct registrations by one principal all accepted with zero refusals,
  scaled down from the case's literal figure). **(b), (c) and (d) NOT RUN** — each requires
  sustained concurrent load or a multi-minute duration beyond the round's time budget; reported as
  not executed rather than approximated.

**Section tally:** 20 PASS (5 of these — SECX-081, 084, 091, plus corroborating evidence in 077/078
— carry HIGH owner-constraint findings SX-4/SX-5 alongside their PASS verdict, per the standing
override), 0 FAIL, 0 BLOCKED, 1 partial NOT RUN (SECX-092's quantitative sub-step) plus 3 fully NOT
RUN sub-steps within SECX-095 (b/c/d) — 21 case IDs.

