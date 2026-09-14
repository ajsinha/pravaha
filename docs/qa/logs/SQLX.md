# SQLX — the documented SQL surface, checked against answers rather than plans — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/SQLX.md`](../cases/SQLX.md). Executed starting 2026-09-14 on branch `develop`,
against a build of the working tree (`./mvnw -q -o -T1C install -DskipTests`, exit 0). The tree at
the time of this run carries one uncommitted change from a concurrent agent,
`pravaha-runtime/.../exec/WindowedAggregate.java` (net-zero window-key withdrawal on correction) —
not touched here, built as-is because that is the actual state of `develop`.

**Harnesses used exactly as SQLX.md defines them.** H-VAL = `pravaha validate`. H-RUN = `pravaha run`
over the fixtures below. H-VIEW = a real `pravaha-server` (HTTP 18700 / Flight 19700,
`allow-anonymous: true`, one stream `txn` bound to the filesystem plugin over `in.csv`), `v_txn`
registered as `SELECT * FROM txn`, read only after `pravaha queries` shows `v_txn` at `ROWS IN 6`.
H-MTX cases are executed in `SqlAnswerTest` (`pravaha-it`), which already carries a
`PhysicalPlanBuilder().overBoundedInput()` harness structurally identical to `ViewQuery.physicalOf`'s
bounded path, feeding D1 in-process.

**Fixtures.** S, D1, D2 (`edge.csv`) and S2 (`all.csv`) are transcribed byte-for-byte from SQLX.md
into `$SCRATCH/sqlx/*.csv` (scratch working directory; not committed). Case-specific files noted
inline.

---

## §1 — Projection (SQLX-001 … SQLX-023)

### SQLX-001 — PASS
```
$ pravaha run --sql "SELECT * FROM txn" --stream txn --schema S --in in.csv --out-schema S
ok  6 in, 6 out
```
`diff in.csv out.csv` → identical, including row 2's empty `status` field and row 6's `ünïcødé`.

### SQLX-002 — PASS
`SELECT txn_id, amount FROM txn` → `1,100 / 2,250 / 3,-50 / 4,0 / 5,7 / 6,7`, `6 in, 6 out`. Matches.

### SQLX-003 — PASS
`SELECT amount, txn_id FROM txn` → `100,1 / 250,2 / -50,3 / 0,4 / 7,5 / 7,6`. Column order follows
the select list, not the schema.

### SQLX-004 — PASS
H-VAL `SELECT amount AS a FROM txn` → `valid  output: [a INT64 NOT NULL]`. H-RUN same, six rows
`100/250/-50/0/7/7`.

### SQLX-005 — PASS
H-VAL `SELECT amount a FROM txn` → identical output schema `[a INT64 NOT NULL]`.

### SQLX-006 — PASS
H-VAL `SELECT t.amount FROM txn AS t` → `[amount INT64 NOT NULL]` — bare name, not `t.amount`.

### SQLX-007 — PASS
`SELECT t.* FROM txn AS t` → byte-identical to SQLX-001's `out.csv`.

### SQLX-008 — PASS
`SELECT txn_id FROM txn AS t` → `1,2,3,4,5,6`, `6 in, 6 out`.

### SQLX-009 — NOT RUN
Requires H-MTX with `TXN`, `OTHER`, `THIRD` registered and an alias shadow. Deferred to the JUnit
extension pass (see "Deferred to JUnit" below); not reached this session.

### SQLX-010 — PASS
H-VAL `SELECT amount AS a, txn_id AS a FROM txn` → `[a INT64 NOT NULL, a0 INT64 NOT NULL]` — Calcite
disambiguates rather than erroring. H-RUN: `100,1 / 250,2 / -50,3 / 0,4 / 7,5 / 7,6`. One of the two
outcomes the case allows; no raw `IllegalArgumentException`.

### SQLX-011 — PASS
H-VAL `SELECT amount, amount FROM txn` → `[amount INT64 NOT NULL, amount0 INT64 NOT NULL]`. H-RUN:
each row's two fields equal — `100,100 / 250,250 / -50,-50 / 0,0 / 7,7 / 7,7`.

### SQLX-012 — NOT RUN
Requires H-MTX with a two-stream join (`TXN`, `OTHER`). Deferred with SQLX-009.

### SQLX-013 — PASS
`SELECT 1 FROM txn` → six lines of `1`, `6 in, 6 out`.

### SQLX-014 — PASS
`SELECT 'flagged' FROM txn` → six lines of exactly `flagged`, no quotes.

### SQLX-015 — PASS
H-VAL and H-RUN `SELECT NULL FROM txn` → `PRV-2021  a bare NULL has no type, so there is no column
this could be. Say which kind of nothing you mean -- CAST(NULL AS BIGINT), CAST(NULL AS VARCHAR) --
and the column gets a type a reader can decode.` Refused at plan time with a code, as the case allows.

### SQLX-016 — PASS
H-VAL `SELECT TRUE FROM txn` → `valid  output: [EXPR$0 BOOLEAN NOT NULL]`, no `ClassCastException`.
H-RUN → six lines of `true`.

### SQLX-017 — PASS
`SELECT amount * 2 + 1 FROM txn` → `201 / 501 / -99 / 1 / 15 / 15`, matching hand arithmetic exactly.

### SQLX-018 — PASS
`SELECT price / 2 FROM txn` → `1.25 / 2.0 / 0.5 / 0.25 / 0.75 / 0.125`, exact binary values, no
integer promotion.

### SQLX-019 — PASS
`SELECT CAST(amount AS DOUBLE) FROM txn` → `100.0 / 250.0 / -50.0 / 0.0 / 7.0 / 7.0`.

### SQLX-020 — PASS
`SELECT CAST(price AS BIGINT) FROM txn` → `2 / 4 / 1 / 0 / 1 / 0` — truncates toward zero
(`2.5→2`, `1.5→1`), disagreeing with `ROUND`'s half-away-from-zero. Recorded: the refusal's own
advice ("`SUM(CAST(price AS BIGINT))`") changes the number relative to `ROUND`.

### SQLX-021 — PASS
H-VAL `SELECT CAST(amount AS VARCHAR) FROM txn` → `PRV-2021  'CAST($2):VARCHAR NOT NULL' converts
between INT64 and STRING; Pravaha evaluates numeric conversions only`. Names `VARCHAR`, gives the
"numeric conversions only" reason, refused at plan time.

### SQLX-022 — PASS
H-VAL `SELECT window_start, COUNT(*) FROM txn GROUP BY window_start` (schema extended with a
`window_start:INT64` column) → `PRV-2050  GROUP BY window_start has no bound on its key space…`,
naming the user's own column. Control `SELECT COUNT(*) FROM txn` in the same session → `valid`.

### SQLX-023 — PASS
H-VAL `SELECT DISTINCT user_id FROM txn` → `PRV-2050`, not `PRV-2020`. Message names `user_id` and
says "Bound it with a window".

---

## §1b — DISTINCT and bounded GROUP BY over a view (SQLX-024 … SQLX-028)

Run through H-VIEW: server on 18700/19700, `v_txn` registered, `pravaha queries` confirmed
`v_txn  RUNNING  <fp>  6` before every read below.

### SQLX-024 — PASS
```
$ pravaha query --sql "SELECT DISTINCT user_id FROM v_txn"
u1
u2
u3
ünïcødé
4 rows
```
Control `SELECT user_id FROM v_txn` in the same session → 6 rows. `6 - 2 = 4` (u1 and u2 each
duplicated once).

### SQLX-025 — PASS
`SELECT DISTINCT status FROM v_txn` → `ok`, `NULL`, `flagged`, 3 rows. Control
`... WHERE status IS NOT NULL` → 2 rows in the same session. `3 - 1 = 2`.

### SQLX-026 — PASS
`SELECT DISTINCT user_id, status FROM v_txn` → 5 rows: `u1|ok`, `u2|NULL`, `u3|flagged`, `u2|ok`,
`ünïcødé|ok`. Matches hand-computed set exactly.

### SQLX-027 — PASS
`SELECT DISTINCT txn_id, user_id FROM v_txn` → all 6 rows distinct, in D1 order.

### SQLX-028 — PASS
`SELECT DISTINCT user_id FROM v_txn WHERE amount > 0` → `u1`, `u2`, `ünïcødé`, 3 rows (excludes u3,
whose only row has `amount = 0`).

---

## §2 — Expressions and scalar functions (SQLX-029 … SQLX-048)

### SQLX-029 — PASS
`SELECT ABS(amount) FROM txn` → `100 / 250 / 50 / 0 / 7 / 7`. Sum `414` vs input sum `314`;
difference `100 = 2×50` confirms the sign flip on row 3.

### SQLX-030 — PASS
`SELECT ABS(amount) FROM txn` over `edge.csv` row 8 alone (`amount = -9223372036854775808`):
```
PRV-3010  lane 0 stopped after a failure: java.lang.ArithmeticException: ABS(-9223372036854775808)
has no representable result: the range of a 64-bit integer is asymmetric...
```
Exit code 1. Not a negative result, not a silent drop under a success status. Q-12's fix holds.

### SQLX-031 — PASS
`SELECT FLOOR(amount), CEIL(amount) FROM txn` → every row's two fields equal `amount`:
`100,100 / 250,250 / -50,-50 / 0,0 / 7,7 / 7,7`.

### SQLX-032 — PASS
`SELECT FLOOR(price), CEIL(price) FROM txn` over prices `2.5,4.0,1.0,0.5,-1.5,0.25`:
`2.0,3.0 / 4.0,4.0 / 1.0,1.0 / 0.0,1.0 / -2.0,-1.0 / 0.0,1.0`. `FLOOR(-1.5) = -2`, not `-1`.

### SQLX-033 — PASS
`SELECT ROUND(price) FROM txn` over `2.5,3.5,-2.5,-3.5,0.5,-0.5` → `3.0,4.0,-3.0,-4.0,1.0,-1.0` —
half away from zero on both signs, not banker's rounding (`2.0,4.0,-2.0,-4.0,0.0,-0.0`). Q-4 fix
holds.

### SQLX-034 — PASS
`SELECT ROUND(price) FROM txn` over `{0.49999999999999994, 0.5}` → `0.0, 1.0`. The half-ulp value
rounds down (`< 0.5`), and the control at exactly `0.5` still rounds to `1.0` in the same run,
proving the operator ran on both rows.

### SQLX-035 — PASS
H-VAL `SELECT ROUND(amount, 2) FROM txn` → `PRV-2021  ROUND is supported with one argument and was
given 2. ROUND to a number of decimal places is not built; round the value and scale it, or cast
it.` Both required phrases present.

### SQLX-036 — PASS (documents a known finding, not a new one)
H-VAL for `SQRT(price)`, `POWER(amount,2)`, `EXP(price)`, `LN(price)`, `LOG10(price)`, `SIGN(amount)`,
`TRUNCATE(price)` → all seven `PRV-2021`, all ending in the same supported-function list.
`SQRT(price)` refuses as `'POWER' in 'POWER($3, 0.5:DECIMAL(2, 1))'` — the user searches their query
for "POWER" and finds nothing, because Calcite rewrote `SQRT` before the refusal ever saw it. This is
the same defect round 1 named; still present.

### SQLX-037 — **FAIL — reconfirms Q-10, with new detail**
```
$ pravaha run --sql "SELECT MOD(amount, 3), amount % 3 FROM txn" --out-schema 'm:INT64,p:INT64' ...
ok  6 in, 6 out
4294967297,1
4294967297,1
-4294967298,4294967294
0,0
4294967297,1
4294967297,1
```
`pravaha validate` on the same SQL reports the plan's real output type as `[EXPR$0 INT32 NOT NULL,
EXPR$1 INT32 NOT NULL]` — **not** INT64. `--out-schema` is not checked against it (Q-10, OPEN), and
here the mismatch does not throw `RowLayout.checkType` as fact 5 (AGG.md) predicts for an aggregate —
it silently reads garbage. Declaring the true type (`m:INT32,p:INT32`) gives the correct answer:
`1,1 / 1,1 / -2,-2 / 0,0 / 1,1 / 1,1`, both columns identical, matching hand arithmetic exactly
(`-50 % 3 = -2`, dividend's sign). The garbage values are explained exactly by the byte layout: two
adjacent 4-byte INT32 slots read as one 8-byte INT64 — row 1's `1,1` (bytes `00000001 00000001`)
reads back as `4294967297 = 0x100000001`; row 3's `-2,-2` (`FFFFFFFE FFFFFFFE`) reads back as
`m = -4294967298` and `p` reads the next 4 bytes past the row as zero, giving `4294967294`. **Finding
confirmed, with the exact corruption mechanism**: not a wraparound of one value, but an unguarded
cross-field byte read. `SQL_SUPPORT.md`'s claim that numeric functions beyond the documented four are
`PRV-2021` is also wrong for `MOD`/`%` themselves — both plan and, given the correct schema, compute
right — a documentation correction, recorded separately below.

### SQLX-038 — PASS, and a probable correction to Q-11
H-VAL `SELECT -amount FROM txn` → `valid  output: [EXPR$0 INT64 NOT NULL]`, no refusal. H-RUN →
`-100 / -250 / 50 / 0 / -7 / -7`, the exact negation of D1's `amount` column, summing to `-314`.
Q-11 records this as refused with a self-contradictory message; **on this build unary minus plans
and computes correctly**. Recorded as a correction below — could not reproduce Q-11.

### SQLX-039 — PASS, and a probable correction to Q-5
```
$ time pravaha run --sql "SELECT 100 / amount FROM txn" --out-schema 'v:INT64' ...   # row 4: amount=0
PRV-3010  lane 0 stopped after a failure: java.lang.ArithmeticException: division by zero in a
projection; the record is routed to the DLQ rather than given a value that could be mistaken for an
answer
real  0m1.4s
```
Control `SELECT 100 / 2 FROM txn` in the same session → six rows of `50`, `real 0m1.3s`. Q-5 records
a five-minute stall with a swallowed exception; **on this build the failure is prompt (~1.4s), named,
and carries a code**. Recorded as a correction below.

### SQLX-040 — PASS, and a probable correction to Q-7
`SELECT amount * 2 FROM txn` over `edge.csv` row 7 (`amount = 9223372036854775807`), run **ten
times**: all ten identical — `PRV-3010 ... java.lang.ArithmeticException: long overflow`, non-zero
exit, no `-2` (the two's-complement wraparound value) in any output. Control `SELECT amount * 1 FROM
txn` on the same file, ten times: all ten `ok  1 in, 1 out` / `9223372036854775807`, proving the row
is read and survives when no overflow occurs. Q-7 records non-deterministic silent drops or hangs;
**ten out of ten runs here are identical, prompt failures**. Correction recorded below.

### SQLX-041 — PASS
`SELECT CASE WHEN amount > 50 THEN 1 ELSE 0 END FROM txn` → `1,1,0,0,0,0`, sum 2. Complement
(`THEN 0 ELSE 1`) → sum 4. `2 + 4 = 6`.

### SQLX-042 — PASS
(a) three-branch CASE → `2,3,0,0,1,1`, sum 7 (`100 > 100` is false, so row 1 takes the `> 10` branch
giving `2`, not `3`). (b) no-`ELSE` CASE → row 2 is `3`; the other five fields are empty (NULL), not
`0`.

### SQLX-043 — PASS
`SELECT CASE WHEN amount = 0 THEN 0 ELSE 100 / amount END FROM txn` → `1,0,-2,0,14,14`, `6 in, 6 out`,
completing promptly. Paired against SQLX-039 (the unguarded `100 / amount` on the same file, same
session, fails) — the CASE's guard genuinely short-circuits rather than the query having gotten lucky.

### SQLX-044 — PASS
(a) H-VAL → output type `VARCHAR`, not `INTEGER`. (b) H-RUN → `big,big,0,0,0,0`; the last four are the
one-character string `"0"`. (c) H-VAL `SELECT SUM(c) FROM (...) x` → `PRV-2021`, refused as DECIMAL
arithmetic (`CAST(...):DECIMAL(38,19)`, not double) — a different mechanism than the case guessed
("the accumulators or validation"), but it does carry a code as required.

### SQLX-045 — PASS
`SELECT UPPER(user_id), LOWER(user_id) FROM txn` over `i, ı, straße, ünïcødé, ABC, abc` → run twice,
once plain and once with `JAVA_TOOL_OPTIONS='-Duser.language=tr -Duser.country=TR'`; `cmp` on the two
output files reports **identical**. `i → I,i`; `ı → I,ı`; `straße → STRASSE,straße`;
`ünïcødé → ÜNÏCØDÉ,ünïcødé`. No Turkish dotless-i leaked through.

### SQLX-046 — PASS (case as specified); see also the finding below
(a) `SELECT TRIM(user_id) FROM txn` over `edge.csv` row 11 alone (`"  padded  "`) → `padded`, 6
characters. (b)/(c)/(d) H-VAL for `TRIM(LEADING ' ' FROM …)`, `TRIM('x' FROM …)`,
`TRIM(TRAILING ' ' FROM …)` → all three `PRV-2021`, same message: "TRIM strips spaces from both ends,
and LEADING, TRAILING and a trim character other than a space are not built."

**Incidental finding while setting up (a):** running the same query over the *whole* of `edge.csv`
(not just row 11, which is what the case actually specifies) crashes with a raw
`UnsupportedOperationException` and no `PRV-` code: `"a plugin aborted a row mid-write, which the
ingest path cannot yet undo..."`. Isolated to row 9 (`user_id` is the empty string on a non-nullable
column): `DelimitedCodec.decode` throws `ConfigurationException(DECODE_FAILED, "... null in NOT NULL
column ...")`, `FilesystemPartitionReader`'s catch block calls `writer.abort()` meaning to let one bad
line fail without costing the batch, but `DelegatingRowWriter.abort()` is an unimplemented stub that
itself throws — masking the real diagnostic and **losing every row in the file**, not just the bad
one (confirmed with a bad row sandwiched between two good ones: zero rows out). This is exactly
`FINDINGS.md`'s **I-3**, already `OPEN`; reconfirmed here with the batch-loss extent, which I-3's own
one-line description does not state. Also reproduces on a wrong-field-count line, so it is general to
any `DECODE_FAILED`, not just the NULL/NOT-NULL case.

### SQLX-047 — PASS
`SELECT user_id || '-' || status FROM txn` → `u1-ok / <empty=NULL> / u1-ok / u3-flagged / u2-ok /
ünïcødé-ok`; row 2 is the empty field, confirmed as NULL (D1's only NULL `status`). Five-part chain
`user_id||'-'||user_id||'-'||user_id` → `u1-u1-u1 / u2-u2-u2 / u1-u1-u1 / u3-u3-u3 / u2-u2-u2 /
ünïcødé-ünïcødé-ünïcødé` — not truncated to three parts.

### SQLX-048 — PASS
(a) `SUBSTRING(user_id FROM 1 FOR 1)` over `edge.csv` row 10 (`user_id = 😀x`) → output bytes
`f0 9f 98 80` (confirmed with `xxd`) — the whole emoji, **not** `ef bf bd` (the UTF-8 replacement
character). (b) `SUBSTRING(user_id FROM 2)` → `x`. (c) `SUBSTRING(user_id FROM 1 FOR 3)` over D1 →
`u1/u2/u1/u3/u2/ünï` (bytes `c3bc 6e c3af`, i.e. `ü`,`n`,`ï` — three code points, six UTF-8 bytes).
(d) `FROM 0 FOR 2` → `u/u/u/u/u/ü` (a start below 1 counts the shortfall against the length). (e)
`FROM 1 FOR 0` → six empty strings. (f) `FROM 99` → six empty strings.

---

## Deferred to a JUnit extension pass

SQLX-009 and SQLX-012 need H-MTX with a second/third stream registered (`OTHER`, `THIRD`) or a join,
which the CLI cannot express. Not reached this session; recorded **NOT RUN** above rather than
guessed.

## §3 onward

Not executed this session. `docs/qa/cases/SQLX.md` §3 (WHERE, SQLX-049…096), §4 (aggregation,
SQLX-097…120), §5 (ORDER BY/LIMIT/OFFSET, SQLX-121…126), §6 (set operations, SQLX-127…134), §7
(derived tables/CTEs, SQLX-135…141), §7b (subqueries, SQLX-142…146), §8 (parameters, SQLX-147…162),
§9 (hostile SQL, SQLX-163…182) and §10 (the matrix's own self-checks, SQLX-183…190) are **NOT RUN**.
Continuing in the next session against this same harness set.

---

## Corrections to `FINDINGS.md`, pending confirmation

Three cases above (SQLX-038, 039, 040) ran the exact SQL each associated finding (Q-11, Q-5, Q-7)
describes as broken, and got the *fixed* behaviour every time, not intermittently. Recorded as
"probable corrections" rather than edited into `FINDINGS.md` directly, because those findings were
authored by a different round and I have not traced a commit that fixes them — it is possible the
build under test already carries fixes that round never saw. Whoever next touches `FINDINGS.md`
should either confirm and mark them fixed, or explain the discrepancy.

`SQL_SUPPORT.md`'s "Numeric functions beyond ABS/FLOOR/CEIL/ROUND ❌" row is wrong for `MOD` and `%`:
both plan and execute correctly (SQLX-037). This was already known (round-1 SQL-014) and recorded in
`FINDINGS.md`; not a new finding, reconfirmed here.
