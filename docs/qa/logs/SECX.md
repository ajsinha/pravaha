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

