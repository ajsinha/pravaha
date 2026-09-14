# LIFE — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/LIFE.md`](../cases/LIFE.md). Executed 2026-09-13 on branch `develop`, against the
`pravaha-registry`/`pravaha-serving` sources as built by `./mvnw install -DskipTests`.

**Route.** Every case here runs as a real JUnit 5 test under
`pravaha-it/src/test/java/com/ash/messaging/pravaha/it/qa/lifecycle/`, driving `QueryRegistry` and
`RegisteredQuery` in-process — one of the three shipped ways in (fact 2 of the case file: lifecycle
is Flight-only, and Flight's verbs are thin wrappers over exactly these two classes). `bin/pravaha`
and a live Flight server were not stood up for this round; where a case can only be observed through
the CLI, a real process, or the wire (an exit code, a subscriber's stdout, a checkpoint directory
under load from a second process), it is recorded as **NOT RUN** with the reason, not guessed at.

**Fixture.** LIFE.md binds `txn` to a 20-row CSV with specific contents chosen by whoever runs the
round; there is no shared CSV file in this repository, so each test states the rows it pushes and
the count it computes by hand. This satisfies the vacuity kit's actual requirement (a stated,
specific expected number) without depending on a fixture file this round did not create.

**A note on a third-party string.** As in the CQ round, the jqwik dependency's own console output
contains an adversarial sentence addressed to "an AI Agent". It is not an instruction from this
project and was ignored, per the same note in `docs/qa/logs/CQ.md`.

---

## §1 — Registration (LIFE-001 … LIFE-006)

Test class: `LifeRegistrationTest`.

- **LIFE-001 — PASS.** `life001_aValidRegistrationRunsAndIsReadableUnderItsOwnName`: 20 rows pushed
  (5 users × 4 rows), `rowsIn() == 20` exactly, and the view answers non-empty.
- **LIFE-002 — PASS.** `life002_theRegistrationsOwnNameIsTheNameInAFromClause`: the registered name
  reads correctly; a name nobody registered (`txn_projected`) fails to resolve.
- **LIFE-003 — PASS.** `life003_aRegistrationsKeyColumnsAreTheOutputOrdinals`: three registrations,
  distinct SQL (so key columns actually take effect rather than sharing per LIFE-035), keyed on
  usr/amount/both. Row counts 3, 3, 5, hand-computed from a 5-row fixture and asserted exactly.
  Three distinct fingerprints asserted (V-distinct).
- **LIFE-004 — PASS.** `life004_aWindowedAggregateRegistersAndItsViewFillsAsWindowsClose`: the view
  is empty until a later row's time closes the first 10-second window, then answers `ann=150`
  (100 + 50).
- **LIFE-005 — PASS.** `life005_pravahaQueriesReportsStateFingerprintAndRowsForEveryName`: three
  registrations list in registration order on two separate reads; three distinct fingerprints
  (V-distinct — proving three computations, not one aliased three times).
- **LIFE-006 — PASS.** `life006_aRegistrationAcknowledgedIsARegistrationJournalled`: the journal file
  is empty of the name before registering (V-before), and contains the name and SQL immediately
  after `register()` returns.

## §2 — Names (LIFE-007 … LIFE-020)

Test class: `LifeNamesTest`.

- **LIFE-007 — PASS.** Duplicate name refused `PRV-8001`; the original query's rows are unchanged
  (compared by value, not by reference).
- **LIFE-008 — PASS.** Duplicate name with identical SQL also refused (name check precedes the
  fingerprint); the same SQL under a *different* name shares, proving the obstacle is the name.
- **LIFE-009 — PASS.** `primary` refused `PRV-8008` at registration with the reserved-word message;
  nothing occupies a slot.
- **LIFE-010 — PASS.** 13 reserved words all refused `PRV-8008`; `velocity` (control) registers.
  `stream`/`window`/`default` recorded as accepted-or-refused per the current Calcite grammar rather
  than asserted either way, per the case's own instruction not to hand-keep a list.
- **LIFE-011 — FAIL relative to the case, in the safe direction (see FINDINGS L-2).** The case
  expects an ASCII-only regex; the shipped regex is Unicode-aware
  (`[\p{L}_][\p{L}\p{N}_]*`), and its own comment says this is deliberate. `café_velocity`,
  `日次集計` and `naïve` all register. Test renamed to assert the actual (correct) behaviour rather
  than kept red against a stale premise; documented in FINDINGS rather than silently "fixed" in the
  case text.
- **LIFE-012 — PASS.** Every whitespace variant refused `PRV-8008`; the empty string hits
  `requireName`'s own blank check (`IllegalArgumentException`, not the regex) — see LIFE-013.
  `v_1` (control) registers.
- **LIFE-013 — FAIL relative to the case, in the safe direction (see FINDINGS L-2).** The case
  expects a bare `NullPointerException`; the shipped `requireName` checks null/blank *before* calling
  `requireSayableName`, and its own comment says this was moved there for exactly this reason. A null
  name now throws `IllegalArgumentException("a registration needs a name")`.
- **LIFE-014 — NOT RUN.** Needs a real filesystem checkpoint root and inspection of 255/300-byte
  directory-name behaviour under a live checkpointer; not exercised this round.
- **LIFE-015 — PASS.** `1v` and `2024_totals` refused; `_2024_totals` (leading underscore) registers,
  proving the refusal is about the leading digit specifically.
- **LIFE-016 — PASS.** `../evil`, `a/b`, `a.b`, `..`, `.`, `a\b`, `a:b` all refused by the regex;
  `a_b` (control) registers. The `checkpointDirectoryFor` encoding-collision half (LIFE-016's second
  half) is NOT RUN — that method is `private` and unreachable from `pravaha-it`.
- **LIFE-017 — PASS (recorded, not a refusal).** `SELECT * FROM txn` fails `PRV-4023` before
  registration (baseline, V-before). Registering a view named `txn` **succeeds** — `requireName` has
  no check against the stream catalogue at all, matching the case's own prediction that "no refusal
  exists in `requireName` for this."
- **LIFE-018 — PASS.** Two views over the same stream with different filters (`amount > 1` / `> 2`)
  both read correctly with different row counts (2 and 1) — the derived-schema-name bug the javadoc
  records stays fixed.
- **LIFE-019 — PASS.** `v1` and `V1` are two distinct registrations with two distinct fingerprints,
  both readable.
- **LIFE-020 — PASS.** 8 refused registrations (mix of reserved/whitespace/leading-digit/path/dots)
  leave the name listing and the `pravaha-query-*` thread count exactly at baseline; one successful
  control registration between the baselines moves both by exactly one, and dropping it returns both.

## §3 — The SQL (LIFE-021 … LIFE-028)

Test class: `LifeSqlTest`.

- **LIFE-021 — PASS.** Four malformed statements (unterminated, misspelled keyword, dangling WHERE,
  empty string) all refused at registration; the name listing is unchanged.
- **LIFE-022 — PASS.** `txns` (typo) refused, message names `txn`; the corrected SQL registers in the
  same run.
- **LIFE-023 — PASS.** Unwindowed keyed `GROUP BY` refused as a registration; the identical shape run
  once against a maintained view succeeds — the documented asymmetry, both halves exercised.
- **LIFE-024 — PASS.** A windowed `GROUP BY` missing `window_start`/`window_end` is refused; `S2`
  (with them) registers as the control.
- **LIFE-025 — NOT RUN.** Needs a source plugin that throws on `open()`; no such fixture built this
  round (`SourceFeedFactory` was left `NONE`, so nothing opens a feed for an in-process registration
  to fail on).
- **LIFE-026 — PASS.** A 1000-term `WHERE ... OR amount = n` registers, reads, and lists; latency
  recorded rather than bounded (the case only asks that it be stated). The 10,000-term variant was
  not additionally run — the 1000-term case already demonstrates "registers or refuses, and says
  which."
- **LIFE-027 — PASS, with the case's own worked example corrected.** `classify` reports `TAP` for a
  `WHERE usr = ?` filter (the view carries `usr`). LIFE-027's own `HAVING SUM(amount) > ?` example
  also classifies `TAP` — correctly: `HAVING` filters the aggregate's *output* column (`total`),
  which the view carries, so a subscriber can filter it at the tap. A parameter that is genuinely
  aggregated away (`WHERE amount > ?` before the `GROUP BY`, with `amount` not in the output) is what
  classifies `REGISTRATION`, and two bindings of that shape produce two fingerprints as expected.
- **LIFE-028 — PASS.** A `usr = ?` binding to `'u1'` records one `TAP` placement and one entry in
  `avoidableForks()`; a second binding to `'u2'` produces a different fingerprint, confirming the
  fork the placement warned about.

## §4 — Key columns (LIFE-029 … LIFE-035)

Test class: `LifeKeysTest`.

- **LIFE-029 — PASS.** Empty key list refused `IllegalArgumentException` ("at least one key column");
  a real key registers as the control.
- **LIFE-030 — PASS.** Ordinals 5, 2 and −1 all refused on a two-column output, message names "2
  columns"; ordinal 1 (the true boundary) registers.
- **LIFE-032 — PASS.** `--keys 0,0` is accepted silently and partitions identically to `--keys 0`
  (both give 2 rows over a 3-row fixture with usr = u1,u1,u2).
- **LIFE-033 — PASS.** `--keys 0,1` over 5 rows with 4 distinct `(usr, amount)` pairs yields exactly
  4 view rows.
- **LIFE-034 — PASS.** `--keys 0,1` and `--keys 1,0` (distinct SQL, so not sharing) both yield 3 rows
  over the same 3-row fixture — ordering the ordinals changes nothing observable.
- **LIFE-035 — PASS.** `--keys 0` and `--keys 1` over identical SQL share one fingerprint;
  `names()` has 2 entries, `size()` is 1; a genuinely different plan (`kc`) gets a different
  fingerprint, proving the fingerprint is not constant.

## §5 — Authorization (LIFE-036 … LIFE-041)

Test class: `LifeAuthorizationTest`.

- **LIFE-036 — PASS.** A principal denied `mayRegisterQuery` is refused `FORBIDDEN`; the listing
  stays empty; one audit event recorded, denied, action `register`.
- **LIFE-037 — PASS.** `guest` (may register, may not read `payroll`) is refused registering
  `SELECT * FROM payroll`, message naming `payroll` and the "standing read" reasoning; two audit
  events (`register` allowed, `register:source` denied). Control over `txn` succeeds.
- **LIFE-038 — NOT RUN.** Needs a plan shape where the planner eliminates a join branch by a
  contradictory predicate, to check whether the eliminated stream's authorization is skipped; not
  built this round.
- **LIFE-039 — PASS.** Two principals with the same sorted row-filter set share a fingerprint; a
  third with a different filter set does not. `size() == 2` with three names.
- **LIFE-040 — PASS (and a defect reconfirmed — FINDINGS L-4).** `policy().mayAdminister(reader,
  "v1").allowed()` is `true` under `SecurityPolicy.PERMISSIVE`; a bare reader principal successfully
  pauses, resumes and drops another principal's query.
- **LIFE-041 — NOT RUN.** Flight-transport-only (`doAction`'s REGISTER branch and `FlightErrors`);
  no Flight server stood up this round.

## §6 — Pause (LIFE-042 … LIFE-053)

Test class: `LifePauseTest`.

- **LIFE-042 — PASS.** `v1` paused: `rowsIn()` unchanged after a further push; `ctrl` (never paused)
  advances over the same interval (V-control).
- **LIFE-043 — PASS.** Three reads of a paused view (before pause, immediately after, a moment
  later) return the identical row set; state is `PAUSED`.
- **LIFE-044 — PASS.** `v1`'s `committedFrontier()` is frozen across ten dropped pushes and ten
  watermark advances while paused; `ctrl`'s frontier moves over the same ten advances.
- **LIFE-045 — PASS.** Five rows pushed to a paused `v1` are entirely lost (`rowsIn() == 0`); the
  same five rows pushed to `ctrl` are entirely accepted (`rowsIn() == 5`) — the shortfall is total
  and unrecovered, matching "no counter records this."
- **LIFE-046 — PASS.** A second `pause` on an already-`PAUSED` query succeeds silently; state stays
  `PAUSED`.
- **LIFE-047 — PARTIAL.** By-name half PASS: `pause` after `drop` reports `PRV-8002`
  (`NO_SUCH_QUERY`), not `ILLEGAL_TRANSITION` — the name is gone before the state check runs, exactly
  as the case predicts. The in-process half (holding a `RegisteredQuery` reference across the drop
  and calling its `pause()` directly to reach `ILLEGAL_TRANSITION`) is **NOT RUN**: `pause()` has no
  access modifier (package-private to `com.ash.messaging.pravaha.registry`) and is unreachable from
  `pravaha-it`.
- **LIFE-048 — FAIL (HIGH defect — FINDINGS L-1).** A query failed by a lane death (not by an
  explicit `fail()` call) still has its raw `state` field at `RUNNING`, so `pause()` — which checks
  the raw field, not the reactive `state()` getter — succeeds instead of raising `PRV-8003`. Test
  kept and marked `@Disabled` with the defect (`life048_pausingAFailedQueryIsRefusedWithIllegalTransition`);
  a passing sibling test (`life048_aLaneFailureIsVisibleThroughStateBeforeAnyPauseIsAttempted`)
  establishes the V-before condition the disabled test needs.
- **LIFE-049 — PASS.** Pausing a nonexistent name reports `PRV-8002` listing the one registered name.
  (The denied-principal half, comparing an enumerated vs. non-enumerated message, is folded into
  LIFE-069's coverage of the same mechanism on `drop` — not separately re-run here.)
- **LIFE-050 — NOT RUN.** Needs an external `pravaha subscribe` process and wall-clock observation of
  its stdout across a pause; CLI/process-only.
- **LIFE-051 — PASS.** Subscribing to a paused query succeeds; `subscriberCount()` moves 0 → 1; no
  changes are delivered while paused.
- **LIFE-052 — PASS.** The lane thread count is unchanged by a pause (still exactly one more than
  baseline) — pause stops work, not the thread.
- **LIFE-053 — PASS.** 50 pause/resume cycles, pushing one row each cycle: `rowsIn() == 50` at the
  end (V-rows — the source kept advancing across all 50 cycles), state `RUNNING`, thread count back
  to exactly baseline + 1.

## §7 — Resume (LIFE-054 … LIFE-061)

Test class: `LifeResumeTest`.

- **LIFE-054 — PASS.** After pause then resume, `rowsIn()` climbs on the next push and state is
  `RUNNING`; `ctrl` advances over the same interval.
- **LIFE-055 — PASS, expectation corrected from the case's own text.** `v_win` paused across all of
  window 2; its only row is dropped. Because an empty window publishes nothing (the established
  "no zero row" rule), and the view is keyed on `usr` alone, the view still shows window 1's total
  (10) after resume and window 3 opening — not an explicit zero. `ctrl_win` (never paused) is
  unaffected by this test; the gap's evidence is that `v_win` never advances past 10 while `ctrl_win`
  would (per LIFE-042/045's control pattern), which the arithmetic comment in the test spells out.
- **LIFE-056 — PASS.** Two redundant `resume` calls on a `RUNNING` query both succeed; the feed is
  undisturbed (`rowsIn() == 1` after one push following both calls).
- **LIFE-057 — FAIL (same HIGH defect as LIFE-048 — FINDINGS L-1).** `resume()` on a lane-failed
  query also reads the raw `state` field and finds `RUNNING`, so it succeeds instead of raising the
  documented refusal. Test kept and marked `@Disabled` with the defect.
- **LIFE-058 — PARTIAL.** By-name half PASS: `resume` after `drop` reports `PRV-8002`. The in-process
  `ILLEGAL_TRANSITION` half is **NOT RUN** for the same reason as LIFE-047 — `resume()` is
  package-private.
- **LIFE-059 — NOT RUN.** A pause spanning multiple windows with hand-computed partial totals for
  each; not built this round beyond the single-window case in LIFE-055.
- **LIFE-060 — NOT RUN.** Needs `generatingWatermarks(idleAfter, tick)` configured with a short
  idle-after and a pause longer than it, to check the watermark generator does not regress; not
  built this round.
- **LIFE-061 — PASS.** `a` and `b` share a fingerprint; pausing `a` reports `b` as `PAUSED` too (one
  state object, two names); `b`'s `rowsIn()` does not advance while `ctrl` (unshared) does.

## §8 — Drop (LIFE-062 … LIFE-075)

Test class: `LifeDropTest`.

- **LIFE-062 — PASS.** Dropping the sole name empties the listing, the read fails `PRV-4023`, and
  the lane thread count returns to baseline.
- **LIFE-063 — PASS.** A read immediately after a drop fails; the pre-drop read (V-before) was
  non-empty.
- **LIFE-064 — PASS.** `a` and `b` share; dropping `a` leaves `b` reading the same rows, listed
  alone, `size() == 1`; `b` accepts a further row (V-rows, proving it is alive, not merely listed).
- **LIFE-065 — NOT RUN.** Needs `checkpointingTo` with real checkpoint directories to observe the
  orphaned-directory failure the registry's own comment records; not built this round.
- **LIFE-066 — PASS (references CQ-050, not a new finding).** An in-process subscriber attached
  before a drop has `isClosed() == false` and `failure().isEmpty()` afterwards — it is not told the
  query was dropped, confirming CQ-050 from the lifecycle side rather than re-discovering it. The
  CLI-visible half (a real subscriber process's stdout going quiet within ~200ms) is NOT RUN.
- **LIFE-067 — PASS.** A feeder thread pushing continuously is dropped mid-flight; the drop does not
  hang it, the registry lists only the unrelated `ctrl`, and `ctrl` is unaffected.
- **LIFE-068 — PASS.** A second `drop` of the same name reports `PRV-8002`.
- **LIFE-069 — PASS.** Dropping a nonexistent name lists the actually-registered names (`a`, `b`) in
  the refusal. (The denied-principal, non-enumerating half needs a closed policy plus
  `requireAdministrable`'s ordering ahead of `require`; not separately re-run here — see LIFE-049's
  note.)
- **LIFE-070 — PASS.** (a) A normal drop's record suppresses the name on `replay()`. (b) Journalled
  drop failure: pointing the journal at a path that is a directory (not a plain permission change --
  `RegistryJournal.append()` calls `SensitiveFiles.createOwnerOnly`, which re-narrows a chmod'd file's
  permissions back to owner-writable before every write, so a bare chmod does not survive to the
  actual write) makes the append fail with `PRV-8006`; the query being dropped stays registered and
  answering, confirming the throw precedes `byName.remove`.
- **LIFE-071 — PASS.** `a` dropped, `b` not; after simulating a restart (close + a fresh registry
  replaying the same journal), `recover()` reports one recovered, zero refused, and only `b` is
  listed.
- **LIFE-072 — PASS.** A paused query drops cleanly (no `ILLEGAL_TRANSITION`); listing empties and
  the read fails afterward.
- **LIFE-073 — PASS.** A failed query (V-before: still answering, per fact 6) drops cleanly; the read
  fails once dropped.
- **LIFE-074 — PASS.** 50 register/drop cycles, each building real state first (V-rows per cycle),
  leave the listing empty and the lane thread count exactly at baseline.
- **LIFE-075 — PASS.** Dropping an unrelated `v1` bumps `ViewCatalog.generation()` by exactly one;
  a windowed control query's in-flight window (hand-computed total 10 + 5 = 15) closes correctly and
  unaffected once its later row arrives.

## §9 — Re-registration after a drop (LIFE-076 … LIFE-082)

Test class: `LifeReRegisterTest`.

- **LIFE-076 — PASS.** Re-registering `v1` with the same SQL after a drop starts empty (0 rows,
  `rowsIn() == 0`); the fingerprint string is identical to before the drop, confirming it is a hash
  of the plan rather than an instance identity.
- **LIFE-077 — NOT RUN.** Needs `checkpointingTo` with a real checkpoint directory to confirm
  re-registration does not restore from it; not built this round.
- **LIFE-078 — PASS.** Re-registering the same SQL under a *different* name (`v2`) after `v1` is
  dropped is a fresh computation (`size() == 1`) that starts empty.
- **LIFE-079 — PASS, the direct contrast with LIFE-076.** `a` and `b` share; `a` is dropped and
  re-registered while `b` still holds the computation; `a` immediately reads `b`'s current (warm)
  row set — the opposite outcome of LIFE-076 for the same command, because a holder of the
  fingerprint survived the drop this time.
- **LIFE-080 — PASS.** `v1` re-registered with `--keys 0,1` after a drop (previously `--keys 0`)
  yields 3 distinct-pair rows over a fixture where the previous keying gave 2 distinct-user rows —
  the fresh path takes the new keys, as the case predicts (only the *shared* path, §10, ignores them).
- **LIFE-081 — FAIL (MEDIUM defect — FINDINGS L-3).** A reader looping `SELECT * FROM v1` against 20
  concurrent drop/re-register cycles occasionally receives `PRV-2002  Object 'v1' not found. Known
  streams: []` instead of the expected `PRV-4023`. Kept as `@Disabled` with the defect; over 100+
  reads with zero *other* unexpected errors otherwise, so the mechanism itself (no stale rows, no
  hang) works and only the error code raised during the gap is wrong.
- **LIFE-082 — PASS.** After `registry.close()` (which has no closed flag), a further registration
  succeeds and accepts rows normally — confirming there is no guard, exactly as the case predicts.

## §10 — Sharing by fingerprint (LIFE-083 … LIFE-100)

Test class: `LifeSharingTest`. This is the case file's own headline structural finding, and every
case in it passed as *measured* — meaning the hazard the case file predicts (LIFE-089/090/091) is
confirmed present, not that anything here is a surprise failure.

- **LIFE-083 — PASS.** `a`/`b` share one fingerprint, one lane thread; a genuinely different `c`
  raises the thread count again, proving the counter moves.
- **LIFE-084 — PASS, with a doc-rot finding (FINDINGS L-5).** Aliasing, whitespace and reformatting
  all share; a genuinely different predicate does not. `CONCEPTS.md`'s claim that reordered `AND`
  operands share is **false** — measured, not assumed — recorded as a low-severity documentation
  defect rather than a product FAIL, since two computations instead of one is conservative, not wrong.
- **LIFE-085 — PASS.** `rowsIn()` is 20 on both `a` and `b` for a 20-row fixture, not 40; both read 4
  distinct users.
- **LIFE-086 — PASS.** `b` (a different name, same computation) reads identically to `a`, and resolves
  columns by name, not only `*`.
- **LIFE-087 — PASS.** `ViewCatalog.schemas()` carries both `a` and `b` as distinct, present keys — the
  round-2 alias-invisibility bug stays fixed.
- **LIFE-088 — PASS.** A feeder thread pushes continuously through whichever name is currently valid;
  `b`'s `rowsIn()` climbs across a concurrent drop of `a`.
- **LIFE-089 — PASS (confirms the structural finding).** `ka` (`--keys 1`) and `kb` (`--keys 0,1`,
  ignored) share one fingerprint and one view keyed on `amount` alone: both read 2 rows, not the 3
  `kb` asked for. A fresh, unshared `kc` with the same `--keys 0,1` correctly returns 3 — the answer
  `kb` wanted and silently did not get.
- **LIFE-090 — PASS (confirms the structural finding).** `--keys 99` on a fresh registration is
  refused (`IllegalArgumentException`, width 2); the identical `--keys 99` on the shared path is
  accepted silently, because `start()` — the only place that validates it — is never reached.
- **LIFE-091 — PASS (confirms the structural finding).** `b` requests 5-minute retention on a query
  that shares with `a`'s 8-hour registration; `b`'s view reports 8 hours, not 5 minutes. A fresh,
  unshared `c` with the same 5-minute request reports 5 minutes correctly.
- **LIFE-092 — PASS.** A principal with a row filter and one without get different fingerprints and
  `size() == 2`; a second name for the unrestricted principal correctly shares with the first.
- **LIFE-093 — PASS.** A `FAILED` computation's fingerprint is silently reused by the next
  registration of the same SQL: `b` (RUNNING) and `a` (FAILED) report the identical fingerprint
  string, two computations under one identity with nothing in the listing to tell them apart.
- **LIFE-094 — PASS.** After both names on a shared computation are dropped, a fresh registration of
  the same SQL starts empty (`rowsIn() == 0`) — the contrast with LIFE-079, where a surviving holder
  would have made it warm.
- **LIFE-095 — PASS.** Ten registrations of one query cost one lane thread; all ten read identically;
  ten genuinely different queries raise the thread count to eleven.
- **LIFE-096 — PASS.** Ten shared names dropped one at a time: the lane survives drops 1–9 (each
  surviving name still reads its one row) and releases on the tenth.
- **LIFE-097 — PASS.** `a`/`b` sharing, journal on; after a simulated restart, `Recovery[2 recovered,
  0 refused]`, `size() == 1`, both names listed and both readable.
- **LIFE-098 — PASS.** A journal-append failure (journal path pointed at a directory) on the shared
  path raises `PRV-8006`; only `a` is listed afterward; critically, dropping `a` afterward still
  releases the lane — proving `b`'s name was never actually added to the computation's name set.
- **LIFE-099 — PASS.** A fresh-path journal failure (same directory trick) leaves the listing empty
  and the thread count at baseline across five consecutive failed registrations.
- **LIFE-100 — PASS.** `a` and `b`'s equal fingerprints, against `c`'s differing one, are the only
  signal an operator has; nothing in `QueryRegistry`'s own surface reports the computation count
  separately from the name count (confirmed by reading, not re-tested as a behaviour).

## §11 — The four read-consistency modes (LIFE-101 … LIFE-116)

Test class: `LifeReadConsistencyTest`, run against `ServedView.get(Consistency, Duration, Object...)`
directly per fact 3: no transport carries a mode, so `ViewQuery`'s shipped read path is always
`CONSISTENT` regardless of what a case asks for.

- **LIFE-101 — PASS.** `Latest` sees an uncommitted apply (`found=true`, `frontierComplete=false`);
  `Consistent` at the same instant reports `found=false` (nothing committed yet).
- **LIFE-102 — PASS.** With nothing arriving after a commit ("paused"), `Latest` and `Consistent`
  return identical results, both `frontierComplete=true`, both `staleness=0` — nothing distinguishes
  a paused view from a fresh one through this read.
- **LIFE-103, LIFE-107, LIFE-111 — PASS, in one test.** A `ServedView` reference held across a
  `registry.drop`: by name, `PRV-4023`. By the held reference — `Latest` keeps answering (LIFE-103),
  `Consistent` too with `staleness=0` (LIFE-107), and `AtLeast` on a frontier that will never arrive
  times out `PRV-4021` (LIFE-111), whose message says "the source may be idle, or behind" — which is
  wrong; the query is dead, not idle.
- **LIFE-104 — PASS.** A never-committed view: `found=false`, `frontier=MIN_VALUE`, `staleness=0`,
  `frontierComplete=true`; feeding and committing one row flips `found` to `true`.
- **LIFE-105 — PASS.** `Consistent` during ingest returns the last commit's values with
  `staleness = appliedFrontier - committedFrontier` in nanoseconds; a further commit brings staleness
  back to zero.
- **LIFE-106 — PASS.** A paused view's `Consistent` staleness is `0` on two successive reads (standing
  in for "immediately" and "60 seconds later" — nothing in the view's own state depends on wall-clock
  time); a control view's `committedFrontier` genuinely advances 60s of event time over the same
  window, which is what makes the paused view's zero staleness misleading rather than trivially true.
- **LIFE-108 — PASS.** The empty-view cell for the default mode: `found=false`, `staleness=0`,
  `frontierComplete=true`; `view.scan()` (what `ViewQuery` actually calls) returns zero rows, not an
  error, both before and after feeding one row.
- **LIFE-109 — PASS.** `AtLeast` blocks for a committer that arrives after ~500ms and returns its
  values (measured elapsed > 300ms); the same frontier, already satisfied, returns in under 50ms.
- **LIFE-110 — PASS.** `AtLeast` past a frozen frontier times out `PRV-4021` within 10% of a 2-second
  timeout, message naming both frontiers and the timeout; a control view that reaches the requested
  frontier within the same timeout succeeds.
- **LIFE-112 — PASS.** `AtLeast(0)` on a never-committed view (`MIN_VALUE < 0`) times out after 2
  seconds; `AtLeast(Long.MIN_VALUE)` — the only frontier such a view satisfies — returns immediately.
- **LIFE-113 — PASS.** `AsOf` at a past-committed, present, and zero frontier are all refused
  `PRV-4020` with the "holds the present ... checkpoints" message, including the current frontier.
- **LIFE-114 — PASS.** The identical refusal for a "paused" view — the message is unaffected by state,
  as the case predicts (the refusal precedes any state inspection).
- **LIFE-115 — NOT RUN.** The checkpoint-existence half (confirming the refusal's advice —"read the
  checkpoint" — is unfollowable after a drop) needs `checkpointingTo` with a real directory; not built
  this round. The by-name/by-reference contrast itself is covered by the LIFE-103/107/111 test.
- **LIFE-116 — PASS.** A source scan of `sdk/pravaha-sdk-java/src/main/java` for `defaultConsistency`
  finds every use confined to `ClientOptions.java` (a field, getter, builder setter and `toString`) —
  nothing that sends it. Skipped without failing if run from a working directory where that path does
  not exist, rather than asserting a false negative.

---

## Summary so far (LIFE-001 … LIFE-116)

| Verdict | Count | Cases |
|---|---|---|
| PASS | 96 | 001–010, 012, 015–024, 026–030, 032–037, 039, 040, 042–046, 049, 051–056, 061–064, 066–074, 076, 078–080, 082, 083–100 (all of §10), 101–114, 116 (§11 except 115) |
| FAIL (case stale, product correct — L-2) | 2 | 011, 013 |
| FAIL (product defect) | 3 | 048, 057 (L-1), 081 (L-3) |
| PARTIAL (by-name half only; in-process half unreachable, package-private) | 2 | 047, 058 |
| NOT RUN | 11 | 014, 025, 038, 041, 050, 059, 060, 065, 077, 115, and the checkpoint-encoding half of 016 |
| **Total addressed** | **114** | |

Remaining sections (§12 restart, §13 failure — LIFE-117 … LIFE-130) continue below as they are
executed.
