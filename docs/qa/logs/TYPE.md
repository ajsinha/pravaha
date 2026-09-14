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


## §10-12 — NULL and three-valued logic, Min/max values, Overflow/underflow (TYPE-080 … TYPE-101)

Vehicle: `pravaha run`/`validate` over `types.csv`, `num.csv`, plus a small `over.csv` fixture for
overflow-at-decode. A fixture-design flaw in `num.csv` (row 5 has a real, non-NULL `b=0`) breaks two
cases as literally scripted (TYPE-081, TYPE-097) whose Steps run an unguarded `a/b` over the whole
file; both were re-run isolated to the row(s) the case is actually about, noted below rather than
silently reconciled.

- **TYPE-080 — PASS.** All ten `IS [NOT] NULL` pairs over `types.csv` match exactly. One SDK-only
  sub-check (empty-string vs NULL via the Java SDK rather than the CLI) NOT RUN — no SDK vehicle used
  in this batch.
- **TYPE-081 — FAIL** (fixture flaw, not a product bug). The full unguarded `a+b,...,a%b` over
  `num.csv` throws `PRV-3010` at row 5's real `b=0`, contradicting Expected's "other rows
  unaffected." Every isolated sub-check (float half, `a*0`, `b/a` with a NULL divisor) matches
  Expected exactly, including the core NULL-propagation claim.
- **TYPE-082 — PASS.** All six operator/negation pairs over `i64` match, including double negation
  and `IS NULL`.
- **TYPE-083 — PASS.** All `= NULL`/`<> NULL`/`> NULL` forms return 0 rows; server-side bound-`NULL`
  vs literal-`NULL` equivalence confirmed against a live view (worked around the already-known Y-2/X-4
  BYTES-column defect by narrowing the view — not a new finding).
- **TYPE-084 — PASS.** `WHERE b`→1,4; `NOT b`→2,5; `b OR NOT b`→1,2,4,5 (row 3's NULL excluded both
  ways); `b IS NULL`→3.
- **TYPE-085 — PASS.** LIKE/NOT LIKE both drop the NULL row; `NOT(LIKE ...)` agrees with `NOT LIKE`.
- **TYPE-086 — FAIL.** Concat-with-NULL, three-part flattening and the CASE workaround all match.
  The anti-vacuity step (`WHERE s || '!' IS NULL`) is refused: `PRV-2021`, `IS NULL` only compiles
  over a bare column, not a general expression — case-design gap, not exercised as intended.
- **TYPE-087 — PASS.** All five AND/OR/NOT-UNKNOWN truth-table cases match exactly.
- **TYPE-088 — PASS/FAIL split.** Extremes round-trip and the eight WHERE-extreme checks (step 1-2)
  match. Step 3 (a genuinely corrupt decode) does **not** surface `PRV-5040`; it throws
  `UnsupportedOperationException: a plugin aborted a row mid-write ... Report this` instead —
  root-caused to `DelegatingRowWriter.abort()` unconditionally throwing when
  `FilesystemPartitionReader` catches the real decode exception. This is the pre-existing OPEN defect
  tracked in `docs/qa/FINDINGS.md`'s summary table as **I-3** (`DelegatingRowWriter.abort()` throws
  `UnsupportedOperationException`), reconfirmed here, not a new finding.
- **TYPE-089 — FAIL.** Extremes and the overflow/underflow-to-zero decode both match. But
  `WHERE f > 0` returns 6 rows including the NaN row (Expected 5), and `WHERE f = f` returns all 10
  rows including NaN (Expected 9) — see Defect TY-3, now confirmed at FLOAT32 as well as FLOAT64.
  Doc-only nit: TYPE-089's expected float32 min-normal string (`1.17549435E-38`) is itself wrong;
  plain `Float.toString` (and the engine) give `1.1754944E-38`.
- **TYPE-090 — FAIL,** same NaN-comparison root cause as TYPE-089 at FLOAT64: `WHERE f > 0` wrongly
  includes the NaN row (6 rows, not 5); `WHERE f < 1.0E308` is unaffected (NaN correctly fails `<`
  under `Double.compare`'s ordering) and matches.
- **TYPE-091 — FAIL.** Row 6's genuine decode failure hits the same I-3 masking as TYPE-088. More
  significantly, `WHERE ts > 0`/`WHERE ts < 0` are refused outright for every literal form tried
  (bare integer, `CAST(... AS BIGINT)`, and the full-width literal): `PRV-2002 Cannot apply '>' to
  arguments of type '<TIMESTAMP_WITH_LOCAL_TIME_ZONE(9)> > <INTEGER>'` — consistent with the
  already-documented X-1 mechanism (`TIME(0) = INTEGER` refused the same way), now reconfirmed for
  `TIMESTAMP_LTZ`; doc-rot in TYPE-091's Expected, not a new mechanism. Wire/Flight sub-step NOT RUN
  (needs the Python SDK, not exercised in this batch).
- **TYPE-092 — FAIL, new HIGH defect (TY-6).** Lengths preserved exactly for 0/1/511/1024/65536-byte
  strings (no truncation). But the 1024-byte string comes back with **56 corrupted bytes at exactly
  offset 512–567**, reproduced identically across two independent runs and two schema variants, under
  exit 0 with the correct row count reported. See Defect TY-6.
- **TYPE-093 — PASS.** `RowLayout` offsets for the fixture schema match the hand computation exactly
  (`offsetOf`, `nullBitmapOffset/Bytes`, `fixedRegionOffset/End`, `variableFieldCount`, `rowSize(5)`);
  DECIMAL's 16-byte fixed footprint confirmed directly.
- **TYPE-094 — PASS** (not independently seed-proven; corroborated by control comparison, not a
  production-code mutation). INT32 overflow wraps silently under exit 0: `u+v`→
  `-2147483648,2147483647,10,-4` exactly as predicted; a BIGINT control column over the same
  expressions shows the true unwrapped values, confirming only the INT32 write narrows.
- **TYPE-095 — PASS.** INT64 `+`/`*` overflow throws `ArithmeticException: long overflow` (exit 1);
  `-` does not overflow at the documented extreme and matches the stated asymmetry. No
  non-determinism observed across 5 repeated runs of each form (contradicts the OPEN Q-7 claim,
  corroborating FINDINGS.md's existing X-2 note).
- **TYPE-096 — PASS, seed-proven.** `validate` shows the narrower output type is taken for both
  INT8+INT8 and INT16+INT16; `run` reproduces the exact predicted silent wraps under exit 0. A
  three-term chain (`p+q+q`) confirms narrowing happens once at the end (`writeComputed`), not
  per-operator, matching preamble Fact 4 exactly.
- **TYPE-097 — FAIL as scripted / underlying claim confirmed.** The literal all-rows query fails
  wholesale at `num.csv` row 5's real zero divisor (same fixture flaw as TYPE-081). Isolated to the
  `Long.MIN_VALUE / -1` row: division returns `-9223372036854775808` silently (exit 0, no throw);
  `a%b`→0; `ABS(a/b)` throws `ArithmeticException: ABS(-9223372036854775808) has no representable
  result` — all three match Expected exactly, including the division-doesn't-throw /
  ABS-does asymmetry.
- **TYPE-098 — FAIL against the case, in the "already fixed" direction.** `SELECT -a` is **not**
  refused: it plans, executes, agrees with `0-a` and `a*-1` for every row, and overflows identically
  to both alternate spellings at the documented extreme. This reconfirms FINDINGS.md's existing
  **X-2** note (unary minus works correctly) and contradicts the still-OPEN `Q-11` row in the
  round-1 summary table — TYPE-098's own premise is stale, not the product.
- **TYPE-099 — FAIL.** `WHERE f > 3.4028235E38` throws `ClassCastException: class java.lang.Double
  cannot be cast to class java.math.BigDecimal` (exit 1) instead of returning the two rows Expected
  — preamble Fact 6's `ExpressionCompiler.literal` BigDecimal cast reaching a WHERE-clause float
  literal for the first time in this round. Folded into Defect TY-4.
- **TYPE-100 — PASS.** Overflow-to-Infinity, underflow-to-subnormal and `f-f`/`f/f` over the
  infinities all match (`NaN` results included). Doc-only nit: TYPE-100's expected `4.9E-324×10`
  value (`4.94E-323`) is itself arithmetically wrong; Java (and the engine) give `4.9E-323`.
- **TYPE-101 — PASS.** `f=0`/`f<>0` partition exactly (row 7 vs the other 9) at both widths;
  `f*1.0E-300` hits the same Fact-6 cast issue TYPE-099 does, exactly as the case's own text
  anticipated, and the documented `f*f` fallback matches.

**Section tally:** 13 PASS, 8 FAIL (5 are fixture/case-design issues rather than product defects: TYPE-081,
086, 091(doc-rot half), 097(as-scripted half), plus TYPE-088/091's I-3 recurrence), 0 BLOCKED, 1
partial NOT RUN sub-check (22 cases).

