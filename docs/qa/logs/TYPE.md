# TYPE — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/TYPE.md`](../cases/TYPE.md). Executed 2026-09-14 on branch `develop`, against
`pravaha-*` sources as built by `./mvnw -o -T1C install -DskipTests` (Java 21). Route matches the
case file's own vehicles: `pravaha validate` / `pravaha run` / `pravaha explain` over the CLI jar for
plan-only and file-driven cases, `pravaha-server` + `pravaha register/query/subscribe` for fixture
`S`. Six sub-rounds ran as parallel isolated git worktrees, each against its own
`install -DskipTests` build, then were consolidated here; no case ID was run by more than one
sub-round.

**A note on a third-party string.** As in prior rounds, the jqwik dependency's own console output
carries an adversarial sentence addressed to an "AI Agent". It is not a project instruction and was
not acted on.

**Seed-proving.** Per the round's brief, full break/confirm-fail/revert/confirm-pass cycles were
applied to cases directly asserting one of the three recently-fixed behaviours named in the brief
(SQL string-literal charset above U+00FF, `PRV-6100` naming the column, `TIME` held as
nanoseconds-of-day), plus a small number of additional cases picked per sub-round where a refusal or
throw was cheap to falsify and easy to mistake for accidental. Each is marked **seed-proven** below.
Not every FAIL was independently seed-proven — most are pinned instead by direct, repeated
reproduction plus a source-level root cause, which is stated in place of a seed where that is what
was done.

---

## §13-15 — Arithmetic, Division/modulo/zero, CASE WHEN (TYPE-102 … TYPE-124)

Vehicle: `pravaha run`/`validate`/`explain` over `num.csv`, `case.csv` (per TYPE.md Fixtures).

- **TYPE-102 — PASS.** INT64×INT64, five operators, four sign combos over `num.csv` rows 1-4: exact
  match (`22,12,85,3,2 / -12,-22,-85,-3,-2 / 12,22,-85,-3,2 / -22,-12,85,3,-2`).
- **TYPE-103 — FAIL.** `x+y,x-y,x*y,x/y` (FLOAT64) match exactly. `x%y` alone is refused outright:
  `PRV-2021 'MOD(CAST($3):DECIMAL(30,15), CAST($4):DECIMAL(30,15))' is DECIMAL arithmetic...` —
  Calcite casts both DOUBLE operands to DECIMAL for `%`/`MOD` specifically. Diverges from Expected's
  computed `x%y` column. See Defect TY-1.
- **TYPE-104 — PASS.** INT32×INT32 five operators, rows 3-4 exact (`10,4,21,2,1` / `-4,-10,-21,-2,-1`).
  A deliberate out-schema mismatch (`INT64?` instead of `INT32`) reproduces the pre-existing finding
  Q-10 (`%` result prints as `4294967295`) — confirms Q-10, not a new defect.
- **TYPE-105 — PASS.** INT16 and INT8 widths give the identical table to TYPE-104 across both widths.
- **TYPE-106 — FAIL.** FLOAT32×FLOAT32 `+ - * /` match exactly; `%` refused identically to TYPE-103.
  `r/3.0E0` throws a raw `ClassCastException: class java.lang.Double cannot be cast to class
  java.math.BigDecimal` (exit 1, no PRV- code) — the exact mechanism preamble Fact #6 predicts,
  reached via division rather than a bare literal. See Defect TY-4.
- **TYPE-107 — PASS**, with a case-fixture caveat recorded, not a product defect. `a/y`, `a/b`,
  `a*y`, `a+y`, `a-y` all exact; `a%y` refused (same DECIMAL-MOD issue). `explain --level physical`
  shows no CAST rendered for `a/y`. TYPE.md's own Expected for the id=7 precision probe assumes
  `y=5.0`; the actual fixture has `y=4503599627370497.0`, and the engine's answer
  (`9.227875636482146E18`) is IEEE-correct for the real fixture value (verified independently) — a
  TYPE.md fixture/prose inconsistency, not an engine bug.
- **TYPE-108 — PASS.** INT32×INT64 mixed widths, rows 1-3, all five operators match exactly
  (BIGINT output type confirmed via `validate`).
- **TYPE-109 — FAIL.** FLOAT32×FLOAT64 `+ - / ` and `*` match to within 1 ULP of TYPE.md's own
  stated values (TYPE.md's `r*d` figure is itself off by 1 ULP — verified independently, a table
  typo not an engine bug); `r%d` refused, same systemic float-modulo issue as TYPE-103/106.
- **TYPE-110 — PASS, seed-proven.** Integer-vs-decimal-literal arithmetic refused with the exact
  `PRV-2021` DECIMAL sentence in all four forms tested; `x*2.5` (FLOAT64 column), `a*2` (int literal)
  and `CAST(a AS DOUBLE)*2.5` all succeed as the documented workarounds. **Seed:**
  `ExpressionCompiler.typeOf`'s `case DECIMAL -> refuseDecimalType(context)` changed to
  `-> TypeName.FLOAT64`; `a*2.5` then planned successfully (confirming the refusal is real);
  reverted, clean diff.
- **TYPE-111 — PASS.** Integer division truncates toward zero: `a/b` over the discriminating rows
  (including row 10, where `a<0,b<0` gives `0`, not `-1`) matches exactly.
- **TYPE-112 — PASS.** `a%b` takes the sign of the dividend; `MOD(a,b)` identical;
  `(a/b)*b + a%b == a` holds every row.
- **TYPE-113 — FAIL, seed-proven** (on the throw; the batch-loss half diverges from Expected).
  Integer division by zero throws `PRV-3010` naming the DLQ, exactly as documented. **But**
  `out.csv` has **0 rows**, not the 7 surviving rows Expected demands — `pravaha run`'s `Collector`
  buffers all output in memory and only flushes to the sink after `execution.close()` succeeds; a
  mid-stream lane failure discards everything already collected, contradicting the DLQ message's own
  promise. **Seed:** `Expression.Arithmetic.evaluateLong`'s zero-case changed from `divideByZero()`
  to `0L`; rebuilt; `a/b` then silently returned `0` at the zero row (exit 0) instead of throwing —
  confirmed the throw is real; reverted, clean diff. See Defect TY-2.
- **TYPE-114 — FAIL.** `x/y` produces `Infinity`/`-Infinity`/`NaN` correctly at the documented rows.
  But `WHERE x/y > 0` **wrongly includes** the row where `x/y = NaN` — `Predicate`
  (`pravaha-runtime/.../plan/Predicate.java`) implements ordering via `Double.compare(...)`, whose
  total-ordering rule treats NaN as greater than every other double. See Defect TY-3.
- **TYPE-115 — BLOCKED.** `x%y` is refused outright (`PRV-2021`, same systemic float-modulo issue),
  so none of the case's three NaN/NULL-modulo steps can be executed. TYPE-114 independently confirms
  the same `Double.compare` root cause misbehaves on `>` as well as the `=` case this case's own text
  already anticipated.
- **TYPE-116 — PASS**, one message-naming nuance. `MOD(a,b)`/`a%b` agree; `POWER`, `EXP`, `LN`
  refused by name exactly as documented; `SQRT(a)` is refused but the message names `'POWER'`
  because Calcite rewrites `SQRT` to `POWER(x,0.5)` before Pravaha sees it (message accuracy nuance,
  not a functional failure); `MOD(a,b,2)` refused by Calcite's own arity check
  (`PRV-2002`); `ROUND(x,2)` refused by Pravaha's arity check as documented; `a^b` is a parse error
  (`PRV-2001`).
- **TYPE-117 — PASS.** CASE branch selection: first TRUE branch wins, exact table match.
- **TYPE-118 — FAIL.** No-ELSE CASE is NULL, not a type default — matches. But
  `WHERE (CASE WHEN n>50 THEN 1 END) IS NULL` is **refused**: `PRV-2021 cannot compile the
  expression 'IS NULL(CASE(...))' (IS_NULL)...` — `PredicateCompiler` has no path for `IS NULL`
  wrapped around a CASE, contradicting Expected's four-row success. See Defect TY-5.
- **TYPE-119 — PASS.** Nested CASE and a CASE-inside-CASE chain both match; `explain --level
  physical` renders both spellings identically.
- **TYPE-120 — FAIL.** Steps 1-3 (guard variants) match exactly, including the ABS-guard step over
  8/8 rows with no throw. Step 4 (`n IS NULL` guard) **throws** `PRV-3010` (division by zero) because
  `case.csv`'s row 2 has `n=0` (not NULL), so it falls to the unguarded ELSE branch — a defect in
  TYPE-120's own case construction (the stated guard doesn't cover the row needing it), which
  independently reproduces TYPE-113's batch-loss behaviour (0 rows out).
- **TYPE-121 — PASS.** Text branches, including unicode (`straße`/`👍`) and mixed numeric+text
  guards, all exact.
- **TYPE-122 — FAIL** (case-file error, not a product defect, for the coercion half). Steps 1-2
  (`THEN 1 ELSE 'x'`, `THEN s ELSE 0`) both **succeed** via Calcite's VARCHAR coercion — this is
  exactly the behaviour `docs/SQL_SUPPORT.md` already documents correctly, and TYPE-122's own
  Expected (predicting a `PRV-2002` refusal) is out of step with it, not the product. Step 3
  (`1 ELSE 1.5`) throws a raw, uncoded `IllegalArgumentException` naming the mismatched types — the
  case's own alternate paragraph anticipated this exact outcome as "a finding." Step 4 (numeric
  coercion) and Step 5 (boolean/number refusal) both match exactly. See Defect TY-4.
- **TYPE-123 — PASS**, after correcting the out-schema type. `validate` shows the CASE's real output
  type is INT32, not INT64 as naively assumed; run with the correct out-schema matches exactly
  (`1,0,0,-1,1`). Text and tautology routes also match.
- **TYPE-124 — PASS.** CASE as a WHERE operand (`=`, `>`) matches; bare boolean CASE in WHERE is
  refused (`PRV-2021`) exactly as documented; text CASE compared in WHERE is refused by `rejectText`;
  text comparison *inside* a CASE condition succeeds — all four sub-cases match.

**Section tally:** 15 PASS, 7 FAIL, 1 BLOCKED, 0 NOT RUN (23 cases).

