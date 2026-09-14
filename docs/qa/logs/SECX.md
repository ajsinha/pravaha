# SECX — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

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

**A note on a third-party string.** As in prior rounds, the jqwik dependency's own console output
carries an adversarial sentence addressed to an "AI Agent". It is not a project instruction and was
not acted on.

---

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

