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

## §3 — WHERE, the predicate compiler (SQLX-049 … SQLX-096)

### §3.1 — one comparison per type, Fixture S2 / `all.csv`

SQLX-049 (BOOLEAN) — **PASS**. `b=TRUE`→2 (alpha,gamma); `b=FALSE`→1 (beta); `b<>TRUE`→1 (beta);
bare `WHERE b`→2 (alpha,gamma). `2+1=3`.

SQLX-050 (INT8) — **PASS**. `i8>0`→1; `i8<0`→1; `i8=0`→1; `i8>=-1`→3; `i8>200`→**0 rows with a
success status** (`ok 3 in, 0 out`), not an error.

SQLX-051 (INT16) — **PASS**. `i16=100`→1; `i16<>100`→2; `i16 BETWEEN -100 AND 0`→2 (beta,gamma).

SQLX-052 (INT32) — **PASS**. `i32>=1000`→1; `i32<=-1000`→1; `i32=0`→1; `1000=i32` (flipped) → 1,
same row as the first.

SQLX-053 (INT64 extremes) — **PASS**. Extended `all.csv` to 5 rows with `i64 = Long.MAX_VALUE` and
`Long.MIN_VALUE`. `i64=MAX`→1; `i64=MIN`→1 (no `(int)` truncation collapsing both to the same row);
`i64>0`→2; `i64<0`→2.

SQLX-054 (FLOAT32) — **PASS**. `f32=1.5`→1; `f32<0`→1; `f32=0.0`→1; `f32<>1.5`→2.

SQLX-055 (FLOAT64) — **PASS**. `f64>=2.5`→1; `f64<=-2.5`→1; `f64>-2.5 AND f64<2.5`→1 (gamma).

SQLX-056 (STRING) — **PASS**. `s='alpha'`→1; `s<>'alpha'`→2; `s='ALPHA'`→0 (case-sensitive);
`s=''`→0.

SQLX-057 (BYTES) — **PASS**. H-VAL `WHERE bin = X'51'` → `PRV-2021  cannot compare column 'bin' of
type BYTES against a constant yet`. `WHERE bin IS NULL` plans (`[s VARCHAR NOT NULL]`) — the refusal
is about comparison, not about the column's existence, exactly as the case distinguishes.

SQLX-058 (DATE) — **PASS**. `d = DATE '2022-01-08'`→1 (alpha; 19000 days after epoch is confirmed
2022-01-08); `d > DATE '2022-01-08'`→2 (beta,gamma); `d = 19000` (bare int against DATE) →
`PRV-2002  Cannot apply '=' to arguments of type '<DATE> = <INTEGER>'` — refuses with a code, one of
the two outcomes the case allows.

SQLX-059 (TIME) — **FAIL — new finding, HIGH.** `WHERE tm = TIME '01:00:00'` → **0 rows**, not the 1
row the case requires (row 1 has `tm = 3600000`, documented as milliseconds-of-day for 01:00:00).
`WHERE tm = 3600000` (bare int) → `PRV-2002` (Calcite refuses TIME = INTEGER outright, so the two
forms cannot even be compared as the case intends). Root-caused: `ExpressionCompiler.literal` converts
a Calcite TIME literal as `getValueAs(Integer.class) * 1_000_000L` (ms → ns), but
`DelimitedCodec.setField`'s `TIME` case is `Long.parseLong(raw)` with **no** ms→ns conversion — the
column value is stored as raw milliseconds mislabelled as nanoseconds. Confirmed directly:
`WHERE tm < TIME '00:00:01'` (1 second = 1e9 ns as a literal) returns **all 3 rows**, even though two
of them are supposed to represent 1 and 2 *hours*. Any `WHERE <TIME col> <op> TIME '...'` predicate
against a column ingested through the filesystem plugin is silently wrong by a factor of 1,000,000,
under a success status, no error. This is new — not in `FINDINGS.md` under any existing key — recorded
below.

SQLX-060 (TIMESTAMP) — **PASS on the core regression claim; BLOCKED on the H-VIEW leg by Y-2.**
H-RUN: `ts > TIMESTAMP '1970-01-01 00:00:01'` → 2 rows (beta, gamma), correct. The two bare-int forms
(`ts > 1000000000`, `ts = 1000000000`) the case's "Expected: 2 rows; 1 row" calls for are instead
refused at plan time with `PRV-2002` (`<TIMESTAMP_WITH_LOCAL_TIME_ZONE(9)> > <INTEGER>`) — a deviation
from the case's literal expectation, but **not** an `AssertionError`, not a thread death, and not
"0 rows where 2 are expected" (the case's own Falsifier) — it is a clean, coded refusal. Recorded as a
partial deviation rather than a FAIL: the round-2 regression this case exists to catch (an
`AssertionError` killing a Flight worker) did not reproduce.
H-VIEW leg (`v_allt` registered over a second server, 18701/19701): **every** query against the view
failed identically — even `SELECT b FROM v_allt` with no `ts` reference at all —
`PRV-1041  class java.lang.String cannot be cast to class [B`. This is `FINDINGS.md`'s **Y-2**
(`ServedView.value` defaults BYTES to `row.getString`, then `ArrowSchemas.write` casts to `byte[]`),
already `OPEN`, reconfirmed: any view over a stream carrying a non-null BYTES column (Fixture S2's
`bin` column) cannot be queried over Flight **at all**, regardless of what the query asks for — so the
"a fourth query still answers" check cannot be performed independently of Y-2. **BLOCKED**, not FAIL,
on that leg; cited against Y-2 rather than opened as new.

### §3.2 — AND / OR / NOT, and their nesting (SQLX-061 … SQLX-071)

All **PASS**, D1 fixture, `txn_id` sets exactly as hand-computed:

| case | query | rows |
|---|---|---|
| 061 | `amount>0 AND flagged` | {1,6} |
| 062 | `amount>100 OR flagged` | {1,2,4,6} |
| 063 | `NOT (amount>0)` | {3,4} |
| 064 | `NOT (amount>0 AND flagged)` | {2,3,4,5} |
| 065 | `NOT (amount>100 OR flagged)` | {3,5} |
| 066 | `status='ok'` / `NOT NOT (…)` / `NOT (…)` | {1,3,5,6} / {1,3,5,6} / {4} |
| 067 | 8-level nested AND/OR/NOT | {1,2,5}, complement {3,4,6} |
| 068 | `WHERE 1=1` | all 6 |
| 069 | `WHERE 1=0` | 0, `6 in` confirmed |
| 070 | bare `TRUE`/`FALSE` | 6 / 0 |
| 071 | `flagged` / `NOT flagged` | {1,4,6} / {2,3,5} |

SQLX-067 matches the case's own row-by-row hand computation exactly, including the UNKNOWN→FALSE
collapse noted for row 2.

### SQLX-072 — PASS
Nullable `flagged` over a 3-row file (true, false, NULL): `WHERE flagged`→1, `WHERE NOT flagged`→1,
`WHERE flagged IS NULL`→1. `1+1=2≠3`: the NULL row is dropped by both `flagged` and `NOT flagged`.

### SQLX-073 — PASS
`WHERE amount > txn_id` → {1,2,5,6}, complement `amount <= txn_id` → {3,4}. `4+2=6`.

### SQLX-074 — PASS, confirms the document's caveat is incomplete
H-VAL `WHERE status > user_id` → `PRV-2021` ("compares text inside a larger expression"). H-VAL
`WHERE status = user_id` (equality between two **columns**, not against a literal) → **also**
`PRV-2021`, same message. `SQL_SUPPORT.md` says "`=` and `<>` on text work" with no caveat that this
means "against a literal only" — the case's own predicted finding is confirmed, not new.

### SQLX-075 — PASS
`WHERE amount * 2 > 100` → {1,2} (2 rows); control `WHERE amount > 100` → {2} (1 row) in the same
session. `2 ≠ 1` proves the multiplication ran.

### §3.3 — three-valued logic (SQLX-076 … SQLX-079) — all PASS

`status = 'ok'` → 4 ({1,3,5,6}). `status <> 'ok'` → 1 ({4}). `NOT (status = 'ok')` → 1 ({4}),
identical to `<>`. Census: `4+1+1=6` exactly with `status IS NULL` → 1 ({2}). Row 2's NULL is
UNKNOWN under `=`, `<>` and `NOT(=)` alike, found only by `IS NULL`.

### §3.4 — IN (SQLX-080 … SQLX-086)

SQLX-080 — **PASS**. `WHERE user_id IN ()` → `PRV-2001  Encountered ")" at line 1, column 42`,
Calcite's own parse-error shape.

SQLX-081 — **PASS**. `IN ('u1')` → {1,3}, identical to `= 'u1'` in the same session.

SQLX-082 — **PASS**. `IN ('u1','u2','u3','nobody')` → {1,2,3,4,5} (5 rows); `NOT IN (...)` → {6}
(1 row). `5+1=6`.

SQLX-083 — **PASS**. `IN ('u1','u1','u1')` → {1,3}, `6 in, 2 out` — no duplicate emission.

SQLX-084 — **PASS**. A 19-term `IN` list (`u1` plus 18 non-matches) plans and returns {1,3}.

SQLX-085 — **PASS, with a correction to the case's own predicted mechanism.** 20 terms already
refuses: `PRV-2021  cannot compile the expression 'IN($1, {LogicalValues(tuples=[[...]])})' (IN) yet`
— **not** `(SEARCH)` as the case's Intent predicts (Calcite builds a `LogicalValues`/`IN` subplan at
this arity, not a `Sarg`). This matches round 1's own recorded finding (`SQL-054`, `docs/qa/logs/SQL.md`
line 1011) precisely, including the `(IN)` wording — the case file's "SEARCH" text is itself the
inaccuracy, not the engine. Message length grows with term count: 465 bytes at 20 terms, 2546 bytes at
200 — the whole value list is interpolated into the error, confirming round 1's finding still holds.
19 terms (SQLX-084) plans in the same session as the control.

SQLX-086 — **PASS.** `IN ('u1', NULL)` → {1,3} (2 rows). `NOT IN ('u1', NULL)` → **0 rows** — every
row is UNKNOWN under SQL's three-valued `NOT IN`-with-NULL rule, exactly as standard SQL requires.
The two-valued-IR risk the case worried about (returning 4 instead) did **not** materialise.

### §3.5 — IS NULL, BETWEEN, LIKE (SQLX-087 … SQLX-092)

SQLX-087 — **PASS**. `IS NULL`→1 ({2}), `IS NOT NULL`→5 ({1,3,4,5,6}). `1+5=6`.

SQLX-088 — **PASS**. H-VAL, all four (`(user_id||status) IS NULL`, `(amount*2) IS NULL`,
`UPPER(status) IS NULL`, `CASE … END IS NULL`) → `PRV-2021  cannot compile the expression '...'
(IS_NULL) yet. Supported: AND, OR, NOT, comparisons against a literal, IS [NOT] NULL, LIKE against a
literal pattern, and boolean columns.` — confirms round-1 SQL-028: `IS NULL` only works on a bare
column, and `SQL_SUPPORT.md`'s ✅ row needs that caveat.

SQLX-089 — **PASS**. `BETWEEN -50 AND 100` → 5 rows {1,3,4,5,6}, both boundary rows present.
`BETWEEN 7 AND 7` (degenerate) → 2 rows {5,6}, not empty. `NOT BETWEEN` → 1 ({2}); `5+1=6`.

SQLX-090 — **PASS**. Inverted `BETWEEN 100 AND -50` → `ok 6 in, 0 out` — empty, not a swap-and-match.

SQLX-091 — **PASS.** All eight patterns over `u1,u2,user,a.com,axcom,ünïcødé`: `u%`→3;
`%1`→1(u1); `%se%`→1(user); `u_`→2(u1,u2); `%.com`→1(**a.com only**, not `axcom` — the `.` is a
literal); `ü%`→1(ünïcødé); `u1`→1; `%`→6.

SQLX-092 — **PASS on the NULL half; the astral-character half does not reproduce the predicted
disagreement — recorded as a correction, not a defect.** `status LIKE 'o%'`→4, `NOT LIKE 'o%'`→1
(`4+1=5≠6`, the missing row is the NULL, as expected). But `'😀x' LIKE '_x'` → **matches** (1 row) and
`LIKE '__x'` → **0 rows** — the reverse of the case's prediction. Java's `Pattern`-based `.` (which
`toRegex` maps `_` to) already treats a supplementary character's surrogate pair as **one** match unit
by default in this JDK, so `_` and `SUBSTRING` in fact **agree** on code points here; the case's
Intent, reasoning from the source comment alone, guessed UTF-16-code-unit behaviour that the regex
engine does not exhibit. Good news, and worth fixing the case's own claim.

### §3.6 — the four documented WHERE refusals (SQLX-093 … SQLX-096) — all PASS

SQLX-093: `LIKE 'u!%' ESCAPE '!'` → `PRV-2021  ... uses LIKE with an ESCAPE clause, which is not
built. Without ESCAPE, % and _ are always wildcards...`.
SQLX-094: `LIKE status` and `LIKE ?` → both `PRV-2021  ... uses a pattern that is not a literal...`.
Confirms ADR-032's claim that "`LIKE 'u%'` is refused too" is stale (SQLX-091 shows it works).
SQLX-095: `status > 'ok'`, `>=`, `<`, `<=` → all four `PRV-2021  only = and <> are supported on text
column 'status'; <op> needs a collation...`, each with its own operator interpolated correctly. The
two controls (`=`, `<>`) plan.
SQLX-096: control `amount > txn_id` plans; `amount > user_id`, `user_id = 1`, `status < 5` → all
three `PRV-2021` naming the STRING/numeric conversion.

---

## §4 — Aggregation, GROUP BY, HAVING, and the stream/view split (SQLX-097 … SQLX-120)

SQLX-100, 101, 102, 104, 105, 110, 111, 116, 117, 118 are already made executable in
`pravaha-it/.../qa/sql/SqlAnswerTest.java` (`everyWindowedCaseProducesItsHandComputedWindows`,
`everyBoundedReadCaseProducesItsHandComputedGroups`), which I re-ran to confirm green
(`./mvnw -o -pl pravaha-it test -Dtest=SqlAnswerTest`, exit 0) rather than duplicate by hand; those
tests assert the exact hand-computed values these cases describe and are cited as their evidence.
The rest run directly below.

### SQLX-097 — PASS
`SELECT COUNT(*) AS n FROM txn` → one row, `6`. `ok 6 in, 1 out`.

### SQLX-098 — PASS
`SELECT COUNT(*) AS a, COUNT(status) AS b, COUNT(user_id) AS c FROM txn` → one row `6,5,6`.

### SQLX-099 — PASS
H-VIEW: `SELECT user_id, COUNT(*) AS a, COUNT(status) AS b FROM v_txn GROUP BY user_id` →
`u1 2,2` / `u2 2,1` / `u3 1,1` / `ünïcødé 1,1`. u2's `2 ≠ 1` is the assertion; `KeyedAggregate`
correctly excludes r2's NULL status from `COUNT(status)`.

### SQLX-103 — PASS
H-VAL: windowed `GROUP BY user_id` (omitting the boundaries) → `PRV-2050  this GROUP BY is over a
windowed stream but does not group by the window: add window_start and window_end to the GROUP BY...`

### SQLX-106 — PASS, and a second confirmed reproduction of Q-10's shape
`SELECT AVG(amount) AS av, SUM(amount) AS s, COUNT(*) AS n FROM txn` with `av:INT64` → `52,314,6`
(`314/6` truncated). With `av:FLOAT64` declared over the identical plan (whose real output type is
INT64, since the accumulators are integer throughout) → **`2.57E-322`** — not `52.333...`, not `52`:
the raw 8-byte long `52` reinterpreted as IEEE-754 double bits (`Double.longBitsToDouble(52L)` is
exactly this order of magnitude). A sharper reproduction of Q-10 than the case even predicted
("either 52 or 52.333... — if the FLOAT64 spelling differs, that is Q-10's shape"): the actual output
is neither candidate, it is bit-garbage. Same defect as SQLX-037, different aggregate.

### SQLX-107 — PASS, and a probable correction to Q-6's windowed half
`SELECT window_start, COUNT(DISTINCT user_id) AS d, COUNT(*) AS n FROM TABLE(TUMBLE(...))
GROUP BY window_start, window_end` → W1 `d=3, n=4` (**3≠4**, r1 and r3 both `u1`), W2 `d=2, n=2`.
Completed in 0.7 seconds (`time` wrapped), not a five-minute stall. Q-6 records this path as hanging
five minutes; could not reproduce — noted alongside the other corrections below.

### SQLX-108 — PASS, and a probable correction to Q-6's view half
H-VIEW: global `SELECT COUNT(DISTINCT user_id) AS d, COUNT(*) AS n FROM v_txn` → `4,6`. Keyed
`SELECT status, COUNT(DISTINCT user_id) AS d FROM v_txn GROUP BY status` → `ok→3`, `NULL→1`,
`flagged→1`. Neither refused, and `3+1+1=5≠4` (a per-group distinct count correctly does not sum to
the global one). Q-6 records the view path as refused; not reproduced.

### SQLX-109 — PASS
H-VIEW: `SELECT COUNT(DISTINCT status) AS d, COUNT(DISTINCT user_id) AS u FROM v_txn` → `2,4`.
`status` has 3 distinct values including NULL (SQLX-025); excluding NULL leaves 2 — `COUNT(DISTINCT)`
correctly does not count it.

### SQLX-112 — PASS
H-VAL, all three of `GROUPING SETS`, `CUBE(user_id,status)`, `ROLLUP(user_id,status)` →
`PRV-2020  GROUPING SETS, CUBE and ROLLUP are not supported yet` (44 characters, confirmed — no
alternative offered, unlike every other refusal in this engine).

### SQLX-113 — PASS
H-VAL: `SUM(price)`, `MIN(price)`, `MAX(price)`, `AVG(price)` (windowed) → all four
`PRV-2020  <FN>(price) is over a FLOAT64 column, and this engine's aggregates accumulate in 64-bit
integers only... Cast the column to an integer if the rounding is acceptable --
SUM(CAST(price AS BIGINT))...` — kind and column both named. Controls `COUNT(price)` and
`COUNT(DISTINCT price)` plan; over the view, `COUNT(price)=6`, `COUNT(DISTINCT price)=6`
(all six prices distinct).

### SQLX-114 — **FAIL — reconfirms a known round-2 finding (`FINDINGS.md` line 138)**
All twelve combinations (`{SUM,MIN,MAX,AVG} × {i8,i16,i32}`, windowed over Fixture S2) fail
identically in shape:
```
PRV-3010  lane 0 stopped after a failure: java.lang.IllegalArgumentException: field 2 ('EXPR$1')
is INT8, not INT64 in schema allt_projected_windowed_projected_aggregated
```
(and `INT16`/`INT32` respectively). Exactly the case's own Falsifier: an internal schema name
(`allt_projected_windowed_projected_aggregated`) leaked to the user, wrapped in `PRV-3010`'s generic
"lane stopped" framing rather than a targeted refusal with advice, unlike SQLX-113's FLOAT64 case.
Already known — round 2's "Float aggregates" table row in `FINDINGS.md` — reconfirmed here across
all 12 of the case's enumerated combinations rather than the general claim.

### SQLX-115 — PASS
H-VAL: `GROUP BY user_id` and `GROUP BY user_id, status` over a stream → both `PRV-2050`, naming
`GROUP BY user_id` / `GROUP BY user_id, status` by name (not `[1]`), each with the suggested rewrite
and the "Refusing now rather than exhausting memory later" closing line, length > 40.

### SQLX-119 — PASS (this *is* the documented finding, confirmed)
H-RUN `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id` over `in.csv` (six lines, as bounded as an
input gets) → `PRV-2050`, refused exactly as over an endless stream. The identical statement over
`v_txn` (SqlAnswerTest / H-VIEW, SQLX-116) answers correctly. Confirms `QueryRunner.run` never calls
`.overBoundedInput()`, so a finite file is a stream for refusal purposes — the asymmetry the case
exists to pin, not a new discovery.

### SQLX-120 — PASS
H-VAL: `TABLE(SESSION(...))` grouped by the boundaries → `PRV-2020  SESSION windows exist in the
runtime but are not wired to SQL yet: ... Use TUMBLE or HOP.` `GROUP BY SESSION(event_time,
INTERVAL '5' SECOND)` → `PRV-2020  GROUP BY SESSION is not supported; use TUMBLE or HOP`. Both name
the alternative.

## §5 — ORDER BY, LIMIT, OFFSET (SQLX-121 … SQLX-126)

### SQLX-121 — PASS
H-VAL and H-RUN, `SELECT txn_id FROM txn ORDER BY amount` → identical
`PRV-2020  the planner produced a LogicalSort, which Pravaha cannot execute yet. Supported: scan,
filter, project, compute, aggregate, windowing..., inner equi-joins..., and lookup joins...` on both
— H-RUN never opens the file (planning precedes ingestion in `QueryRunner`).

### SQLX-122 — PASS (records the message defect the case itself predicts, not a new one)
Verbatim message above contains `LogicalSort`, a Calcite class name, and **not** the words
`ORDER BY` — exactly the case's "Expected (current)" text. Confirmed still true on this build; the
one-clause fix the case names has not landed.

### SQLX-123 — PASS
`LIMIT 5`, `LIMIT 0`, `LIMIT 1000000` → all three the identical `LogicalSort` message; none mentions
`LIMIT`.

### SQLX-124 — PASS
`OFFSET 5 ROWS` and `OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY` → both the identical `LogicalSort` message.

### SQLX-125 — PASS
H-VIEW: `ORDER BY amount`, `LIMIT 3`, `ORDER BY amount LIMIT 3` over `v_txn` → all three
`PRV-1041  PRV-2020  the planner produced a LogicalSort...` — the operator code (`2020`), not the
unbounded-state one (`2050`), confirming the document's claim that this refusal does not soften over
a bounded read. The `PRV-1041` prefix is the server/Flight transport's generic wrapper around the
real `PRV-2020` refusal underneath it, present on every server-side refusal in this log (see
SQLX-060, SQLX-126) — not itself a separate finding, but worth naming once: a client parsing only the
leading code sees `1041`, not the operator code that actually explains the refusal.

### SQLX-126 — **FAIL against the case's own Falsifier ("a code outside {2020, 2063}")**
H-VIEW: `SELECT user_id FROM v_txn ORDER BY ? --params 1` →
`PRV-1041  PRV-2010  class org.apache.calcite.sql.SqlDynamicParam: ?` — a **third** code the case did
not enumerate, and its text is a raw Java class name, not a sentence a user can act on: worse than
either of the two outcomes the case allowed for. `SELECT user_id FROM v_txn LIMIT ? --params 3` →
`PRV-2063  ?1 is not in a WHERE clause. A placeholder stands for a value that selects rows, and
nothing else...` — matches the case's second anticipated outcome exactly, including the specific risk
it names: the message explains window sizes and group keys and says nothing about there being no sort
operator, so a user reading it concludes parameters are the obstacle and tries `ORDER BY amount`
instead — which SQLX-121 shows is also refused, for an unrelated reason. ADR-032's own prediction
("a test says so") is confirmed for the `LIMIT ?` form; the `ORDER BY ?` form is a distinct, new
observation (`PRV-2010`) the case did not anticipate.

## §6 — Set operations (SQLX-127 … SQLX-134)

Run as `SqlxMultiStreamTest` (`pravaha-it`, H-MTX: `TXN`, `OTHER`, `THIRD` registered in-process,
`PhysicalPlanBuilder().build(...)` then `InterpretedPipeline.compile` with a throwing `RowOutput` so
no row is ever fed while a refusal is checked — plan-time only, matching H-VAL's guarantee).
`./mvnw -o -pl pravaha-it test -Dtest=SqlxMultiStreamTest`, exit 0, 13/13 green. Seed-proven: changed
one assertion's expected substring to a value that cannot appear (`"LogicalUnion"` →
`"NoSuchClassAtAll"`), reran, confirmed the specific test failed (`Tests run: 1, Failures: 1`),
reverted, reran, confirmed green again — so the harness genuinely checks the message text rather than
passing vacuously.

### SQLX-127 / SQLX-128 — PASS
`UNION` and `UNION ALL` between `txn` and `other` → both `PRV-2020`, both name `LogicalUnion` — one
code for both spellings, not two.

### SQLX-129 — PASS
`INTERSECT` → `PRV-2020`, names `LogicalIntersect`.

### SQLX-130 — PASS (records the predicted message defect)
`EXCEPT` → `PRV-2020`, names `LogicalMinus`, does **not** contain `EXCEPT` — confirmed exactly as the
case's "Expected (current)" text describes; not fixed.

### SQLX-131 — PASS
`UNION ALL` of `txn` with itself → `PRV-2020`/`LogicalUnion` **at plan time**, before the pipeline is
ever compiled — unlike the self-*join*, which the harness's own `refusalOf` also exercises via
`InterpretedPipeline.compile` and which is the one refusal elsewhere in this file with no code.

### SQLX-132 — NOT RUN
Needs two registered *views* (`v_txn`, `v_u1`) over H-VIEW; the running server only has `v_txn`
registered. Deferred.

### SQLX-133 — PASS
All four (127–130) messages: length > 40, none mentions `ADR-030` or `out of scope` — confirms the
case's finding that the document's real rationale (ADR-030 tier 4, a scope decision) is absent from
the message a user actually sees, who reads "cannot execute yet" as a promise of a future release.

### SQLX-134 — PASS
Union inside a `WITH`: `PRV-2020`/`LogicalUnion`. `EXCEPT` inside a derived table:
`PRV-2020`/`LogicalMinus`. The same shape of union inside an `IN (...)` subquery:
`PRV-2021` — a different code for text that contains a union, because the predicate compiler refuses
the subquery before ever examining what is inside it.

## §7 — Derived tables, CTEs, VALUES, subqueries (SQLX-135 … SQLX-146)

### SQLX-135 — PASS
H-RUN `SELECT x.txn_id FROM (SELECT txn_id, amount FROM txn WHERE amount > 0) x WHERE x.amount < 100`
→ 2 rows, `txn_id` 5 and 6. Inner control (`amount > 0` alone) → 4 rows in the same session.

### SQLX-136 — PASS
Three-deep derived table (`c` over `b` over `a`) → 4 rows, `200, 0, 14, 14` (sum 228). Two-level
control in the same session → 5 rows summing to 728; `728 - 500 = 228` (the dropped `500` row).

### SQLX-137 — PASS
`WITH big AS (...) SELECT txn_id FROM big` (`amount >= 100`) → 2 rows, `1, 2` — the boundary row at
exactly 100 present. Control (`amount < 100`) → 4 rows in the same session; `2 + 4 = 6`.

### SQLX-138 — PASS
Two chained CTEs (`a` filters `amount > 0`, `b` filters `user_id = 'u2'` reading from `a`, outer
filters `amount > 100`) → 1 row, `txn_id 2`. Intermediate controls in the same session: stage `a`
alone → 4 rows; stage `a ∧ b`'s condition together → 2 rows. `4 → 2 → 1` narrowing confirmed.

### SQLX-139 — PASS
`SqlxMultiStreamTest`: a CTE referenced once, joined to a different stream, plans cleanly (no
self-join triggered by the CTE machinery). The same CTE joined to **itself** is refused with
`"... appears on both sides of this plan; self-joins are not supported yet"` and, confirmed directly,
`doesNotStartWith("PRV-")` — the one refusal in the file with no code, exactly as the document admits,
now via a CTE rather than a literal `JOIN txn AS a, txn AS b`.

### SQLX-140 — PASS
`WITH RECURSIVE r(n) AS (...) SELECT n FROM r` → refused with a `PRV-`-prefixed message (not a
`StackOverflowError`, not a hang — JUnit's own default per-test timeout, well under the case's
five-minute budget, was never approached; the whole 13-test suite runs in ~2 seconds).

### SQLX-141 — PASS
`SELECT * FROM (VALUES (1),(2)) AS v(x)` and bare `VALUES (1),(2)` → both `PRV-2020`, both name
`LogicalValues`.

### SQLX-142 — PASS
`WHERE user_id IN (SELECT user_id FROM other)` and the `NOT IN` form → both `PRV-2021`. Control
(`user_id IN ('a','b')`, an ordinary literal list) plans in the same build.

### SQLX-143 — PASS
`EXISTS (...)` and `NOT EXISTS (...)` → both `PRV-2021`, both name `EXISTS`.

### SQLX-144 — **FAIL — confirms the case's own anticipated worst case**
A correlated `EXISTS` → `PRV-2021`, refused by the predicate compiler as an unsupported `EXISTS`
expression. A correlated scalar subquery in the select list → `PRV-2021`, refused by the expression
compiler as an unsupported `$SCALAR_QUERY` function. **Neither** reaches `buildLookupJoin`'s
`Correlate` branch or its "the only correlated form Pravaha runs is a join against a lookup table..."
sentence — both are caught by an earlier, more generic refusal first. This is precisely the case's
own "Finding if neither does": the best-written refusal in the codebase is unreachable from any
correlated-subquery query a user would actually type.

### SQLX-145 — PASS
Uncorrelated scalar subquery in the select list → `PRV-2021`. In a predicate
(`WHERE amount > (SELECT COUNT(*) FROM other)`) → also `PRV-2021` (Calcite did not decorrelate it
into a join on this build).

### SQLX-146 — PASS
Control `SELECT SUM(amount) FROM txn` (a real global aggregate) plans. `ROW_NUMBER() OVER (...)` →
`PRV-2021`, names `ROW_NUMBER`. `RANK() OVER (...)` → `PRV-2021`, names `RANK`. `LAG(amount) OVER
(...)` → `PRV-2021`, names `LAG`. `SUM(amount) OVER (...)` → `PRV-2021` (the expression path, not the
aggregate path — recorded, since the case flags this as the one worth checking).

## §8 — Parameters (SQLX-147 … SQLX-162)

Run through H-VIEW (`--params` over the running `v_txn`) except SQLX-157, which needs a genuine
non-String Java value the CLI cannot bind — added as a fourth test in `SqlxMultiStreamTest`
(`bindingRefusalOf`, `PhysicalPlanBuilder.bind(BoundParameters.of(...))`); 14/14 green.

### SQLX-147 — PASS
`WHERE user_id = ?` bound to `u1` and the literal `WHERE user_id = 'u1'` both return `{1, 3}`.
`--params u2` returns `{2, 5}` in the same session — the parameter is read, not ignored.

### SQLX-148 — PASS
All six operators against `amount`, `--params 7`: `=`→2, `<>`→4, `<`→2, `<=`→4, `>`→2, `>=`→4.
Three-way partition `(< 7) + (= 7) + (> 7) = 2 + 2 + 2 = 6`.

### SQLX-149 — PASS
`? > amount` and its mirror `amount < ?`, `--params 7`, both return `{3, 4}` (`-50`, `0`) — not
`{1, 2}`. The reverse form `? < amount` returns `{1, 2}` in the same session, confirming `flip(op)`.

### SQLX-150 — PASS
`IN (?, ?) --params u1,u3` → `{1, 3, 4}` (3 rows). `IN (?, ?, ?) --params u1,u3,nobody` → the same
3 rows. `--params u1,u1` → `{1, 3}` (2 rows, no duplicate).

### SQLX-151 — PASS
`BETWEEN ? AND ? --params 0,100` → `{1, 4, 5, 6}` (4 rows). `--params 100,0` (swapped) → **0 rows**
— the asymmetry proves the positions are not swapped internally.

### SQLX-152 — PASS
`amount > ? AND flagged --params 0` → `{1, 6}` (2 rows). `amount > ? OR flagged --params 100` →
`{1, 2, 4, 6}` (4 rows). `NOT (amount > ?) --params 0` → `{3, 4}` (2 rows), not including r1.

### SQLX-153 — PASS
`GROUP BY user_id HAVING COUNT(*) > ?`: `--params 0` → 4 groups; `--params 1` → 2 (`u1`, `u2`);
`--params 2` → 0. `4 → 2 → 0` confirmed across three bindings in one session.

### SQLX-154 — PASS
`user_id = ? AND status = ? --params u1,ok` → `{1, 3}` (2 rows). `--params ok,u1` (swapped) →
**0 rows** — both STRING, so a reversed application would give 2 for both; it does not.

### SQLX-155 — PASS
Two `?` tokens, `--params 7,7` → `{5, 6}` (2 rows; `count() == 2` behaves as expected). One value
for two tokens is covered by SQLX-158 below (same query). `--params -50,250` → all 6 rows.

### SQLX-156 — PASS, with the CLI limitation the case itself asks to be recorded
`WHERE status = ?` cannot be bound to NULL through `--params` (a CLI string can't express NULL
distinctly from the empty string) — recorded, matching the case's own instruction. `WHERE status IS
NULL` (the contrast) → 1 row, `txn_id 2`, with the view confirmed at 6 rows immediately before.
The NULL-bound half is **NOT RUN** (no CLI surface); would need the Flight SDK's prepared-statement
API directly, not attempted this session.

### SQLX-157 — PASS
`SqlxMultiStreamTest.aBoundValueOfTheWrongTypeIsRefusedWithACodeNamingThePlaceholderAndTheType`:
a `Boolean` bound to a STRING placeholder, a `String` bound to an INT64 placeholder, and a `byte[]`
bound to an INT64 placeholder all refuse `PRV-2062`, each naming `?1`. Control: a boxed `Integer`
bound to a FLOAT64 placeholder is **accepted** (`checkAssignable` is a type rule, not a
class-identity rule) — confirms `SUM`-style numeric widening applies to parameter binding too.

### SQLX-158 — **FAIL — new finding, see X-8 below**
`WHERE user_id = ? AND amount > ?` with one value (`--params u1`) → `PRV-1041  this statement has 2
placeholders and 1 value was given` — **no `PRV-2061` anywhere in the message.** With **no**
`--params` at all → `PRV-1041  PRV-2060  this statement uses ?1 but only 0 values were bound...` —
here the code **is** present. The two sibling refusals behave differently through the same CLI path.

### SQLX-159 — **FAIL — same finding as SQLX-158**
`WHERE user_id = ? --params u1,extra` → `PRV-1041  this statement has 1 placeholder and 2 values
were given` — again no `PRV-2061`. Zero-placeholder query with `--params u1` →
`PRV-1041  this statement has 0 placeholders and 1 value was given` — same gap.

### SQLX-160 — **FAIL against its own "PRV-2063 on all three" Expected**
`SELECT amount * ? FROM v_txn --params 2` → `PRV-2063`, as expected (the placeholder sits inside an
arithmetic expression Calcite can type). `SELECT CASE WHEN amount > ? THEN 1 ELSE 0 END FROM v_txn`
→ also `PRV-2063`, as expected. But `SELECT ? FROM v_txn --params 1` (a **bare**, unadorned
placeholder as the sole select item) → `PRV-2002  Illegal use of dynamic parameter` — Calcite's own
validator refuses it before `ParameterMetadata` is ever reached, because a standalone `?` has no
local type context to infer from. Different code than the other two, and than the case's stated
"Expected" for all three.

### SQLX-161 — **FAIL against its own "(a),(b),(d) → PRV-2063" Expected, for the same reason as 160**
(a) `?` as a `TUMBLE` window size → `PRV-2002  Illegal use of dynamic parameter`, not `PRV-2063`.
Control (`INTERVAL '10' SECOND` literal) plans in the same session. (b) `GROUP BY ?` →
`PRV-2002  Illegal use of dynamic parameter`, not `PRV-2063`. (c) `SELECT * FROM ?` →
`PRV-2001` (a parse error, "Encountered \"?\" at line 1, column 15... Was expecting... TABLE...") —
matches the case's expectation exactly. (d) `SELECT ?, COUNT(*) FROM v_txn GROUP BY user_id` →
`PRV-2002  Illegal use of dynamic parameter`, not `PRV-2063`. **Consistent pattern across 160/161**:
a bare, standalone `?` with no surrounding operator is refused by Calcite's validator (`PRV-2002`)
before Pravaha's own `ParameterMetadata.collect` — which is what produces `PRV-2063` — ever runs.
`PRV-2063`'s own mechanism only fires for a `?` embedded inside an expression the validator *can*
type (arithmetic, comparison, `CASE` condition) but that sits outside a `WHERE`/`HAVING` filter.
The case's premise that all "not a WHERE clause" positions share one code does not hold.

### SQLX-162 — PARTIAL — the "reported, not silent" claim is confirmed false
Registered both of ADR-032's example shapes (`v_txn`-style TUMBLE with and without `user_id` in the
`GROUP BY`) via `pravaha register`. `pravaha queries` reports only `NAME/STATE/FINGERPRINT/ROWS IN`
— **no `TAP`/`REGISTRATION` classification appears anywhere**, not in the CLI output and not in the
server log. Confirms the case's own anticipated finding: ADR-032 promises the classification "is
reported rather than done quietly", and it reaches no shipped surface — the same shape as `FINDINGS`
I-6. Not independently re-verified via H-MTX that `ParameterPlacement.of` itself correctly
distinguishes the two internally (i.e. whether the *logic* is right, only that its *result* is never
surfaced) — that half is **NOT RUN**.

## §9 — Hostile, malformed and awkward SQL (SQLX-163 … SQLX-182)

### SQLX-163 — PASS, with a CLI arg-parsing wrinkle worth recording
`pravaha validate --sql ""` and `pravaha query --sql ""` never reach the planner at all: the CLI's
own arg parser treats an explicitly-empty `--sql` value as **absent**, printing
`--sql is required. Supplied: [...]` — a different, arguably more helpful outcome than the case's
predicted `PRV-2001`, but not what the case describes (the query text never reaches `SqlPlanner`).
`pravaha register --sql-file` with a genuinely empty file **does** reach the planner:
`PRV-1041  PRV-2001  Index 0 out of bounds for length 0` — a raw `IndexOutOfBoundsException` message,
not Calcite's own text and not "null" either, but equally unhelpful to a user — the same defect
family the case names (Q-14/SQLX-173).

### SQLX-164 — PASS, and reconfirms Q-9 is still live
Three spaces and a tab+two-newlines both hit the same "--sql is required" CLI short-circuit as
SQLX-163 (blank strings never reach the planner). A single U+00A0 (no-break space, whitespace to a
human, not to the CLI's blank check) **does** reach the planner: `PRV-2001  Lexical error at line 1,
column 2. Encountered: <EOF> after : ""` — a different, more specific message, as expected.
**`--sql "--x"` reproduces Q-9 exactly**: `PRV-2001  Non-query expression encountered in illegal
context` — byte-identical to `--sql "true"` and to `--sql "--select 1"`, confirming any value
starting with `--` is still silently replaced by the literal string `true` before planning. Q-9,
recorded `OPEN`, reconfirmed live on this build with a direct A/B comparison.

### SQLX-165 — PASS
`-- just a comment"` hits the same Q-9 substitution as SQLX-164 (starts with `--`). `/* block */`
→ `PRV-2001  Encountered "<EOF>" at line 1, column 11...`, a real Calcite parse error, not "null". A
genuine 3-line all-comment file through `pravaha register --sql-file` → `PRV-2001  Encountered
"<EOF>" at line 3, column 9...` — line 3, as the document's "preserves Calcite's line and column"
promise requires (this path does not go through the CLI's `--sql` arg, so Q-9 does not apply to it).

### SQLX-166 — PASS
(a) block comment mid-statement → 6 rows. (b) `-- trailing` comment followed by a newline and
`WHERE amount > 0` → **4 rows**, not 6 — the comment ends at the newline and the `WHERE` on the next
line is live SQL. (c) a comment spanning two lines inside `FROM /* ... */ txn` → 6 rows. (d) a
literal containing `a--b` as data → exactly the one row whose `status` is `a--b`. (e) a literal that
looks like a block comment (`'/* not a comment */'`) → 0 rows, no parse error, `6 in` (2 in for the
narrower fixture) confirmed on each.

### SQLX-167 — PASS
`SELECT txn_id FROM txn;` is refused identically (`PRV-2001`, names `;`) under H-VAL and H-RUN, and
the spaced form (`txn ;`) too. A file with **no** trailing semicolon (just a trailing newline, as
every file has) registers cleanly — confirming the refusal is specifically about the character `;`,
not about anything a file's own EOF newline could trigger.

### SQLX-168 — PASS — a clean result for a security-relevant case
(a) `SELECT txn_id FROM txn; SELECT user_id FROM txn` → `PRV-2001`, nothing plans. (b)
`SELECT txn_id FROM v_txn; DROP VIEW v_txn` over H-VIEW → `PRV-2001`; `pravaha queries` immediately
after still lists `v_txn` unchanged. (c) the `DELETE FROM v_txn` variant → same: refused, `v_txn`
unchanged. (d) the injection text supplied as a **bound parameter value**
(`WHERE user_id = ?` bound to `x'; DELETE FROM v_txn --`) → `0 rows`, and `v_txn` is still listed,
still at 6 rows, afterwards — the value is compared as an ordinary string and never reaches a parser.

### SQLX-169 — PASS
All-upper, all-lower, and mixed-case (`SeLeCt ... FrOm ... wHeRe`) spellings of the same query over
D1 produce byte-identical output files (`cmp` confirms), all 4 rows.

### SQLX-170 — PASS
`user_id` and `"user_id"` resolve. `USER_ID`, `User_Id` and `"USER_ID"` all
`PRV-2002  Column '<as typed>' not found in any table; did you mean 'user_id'?` — case preserved in
the message, plus an unprompted "did you mean" suggestion the case did not anticipate. `FROM TXN`
(uppercase stream name) → `PRV-2002  Object 'TXN' not found within 'pravaha'; did you mean 'txn'?`.

### SQLX-171 — **FAIL — new finding, HIGH: a raw, uncaught `StackOverflowError`, and a different failure on `pravaha run`**
A 102 411-byte query (`WHERE amount > 0` plus 6 087 redundant `OR amount > n` disjuncts) crashes
`pravaha validate` with an **uncaught `java.lang.StackOverflowError`** printed straight to the
console from `SqlValidatorImpl.performUnconditionalRewrites`'s own recursion — no `PRV-` code, no
graceful exit, the exact Falsifier the case names. The same query through `pravaha run` does **not**
crash the process; it returns `PRV-2001  null` instead — the Q-14 defect family (SQLX-173), meaning
the StackOverflowError is caught *somewhere* on the run path (probably a broad `catch (Throwable)`)
but propagates raw on the validate path. Both runs took ~1 second; this is not a timeout issue, it is
a genuine, prompt crash. New finding, recorded below as X-11.

### SQLX-172 — **FAIL — new finding, HIGH: a hard 64-column ceiling, uncoded**
`SELECT amount+0 AS c0, ..., amount+999 AS c999 FROM txn` fails immediately with
`IllegalArgumentException: BinaryRowWriter tracks written fields in a long bitmask and so supports
at most 64 fields; txn_projected has 1000` — a real, clear message naming the exact mechanism and
limit, but with **no `PRV-` code** and not documented anywhere. This is an architectural ceiling on
**any** row (a wide join, a wide projection, a wide aggregate), not specific to this case's
1 000-column select list. New finding, recorded below as X-11. (The case's own falsifier — "a
truncated output row, a silently dropped column" — did not happen; the failure is loud and clear,
just uncoded and previously unknown to this campaign.) The 100-nested-parentheses depth-100 control
(shared fixture with SQLX-173) confirmed separately: 4 rows, correct.

### SQLX-173 — **FAIL — confirms Q-14 exactly as predicted**
Depths 10/100/500 plan cleanly (`valid`); depths 900/1000/2000 all produce the literal text
`PRV-2001  null` — Q-14, `OPEN`, reproduced precisely, including the specific boundary (parses below
~600-900 nesting levels, fails above). Depth 100 through H-RUN returns the correct 4 rows, confirming
the parentheses are inert where they parse at all.

### SQLX-174 — **FAIL — new finding, HIGH: a third failure mode for many boolean terms, dumping the whole predicate into the message**
1 000-conjunct `AND` chain and 1 000-disjunct `OR` chain (over `amount` and `user_id` respectively)
**both** fail identically in shape: `PRV-2010  java.lang.RuntimeException: while converting <the
entire several-thousand-character predicate, verbatim>`. Neither hangs, neither StackOverflows —a
**third** distinct failure mode from SQLX-171's (StackOverflowError / "null") for what is
structurally the same kind of input (many boolean terms), and like SQLX-085/142/174(c), the message
interpolates the entire input rather than summarising it. The 1 000-term `IN` list (c) fails as
SQLX-085 predicts: `PRV-2021`, `(IN)`, not `(SEARCH)`. New finding, recorded below as X-11.

### SQLX-175 — PASS
`金额` (CJK), `naïve` and `Ωmega` (Greek) all resolve both quoted and unquoted, as column
**identifiers** in `--schema`. H-RUN over `金额` returns `100` correctly.

### SQLX-176 — **PASS on (a)(b)(c)(e)(f); FAIL on (d) and (g) — new finding, HIGH: unicode string *literals* outside Latin-1 are refused**
(a) identity, (b) filter, (c) `UPPER`, (e) `SUBSTRING`, (f) `LIKE` against the literal `'ünïcødé'`
all work correctly and byte-exactly — **but `ünïcødé`'s four accented characters are all within
ISO-8859-1 (Latin-1)**, U+00FC/U+00EF/U+00F8/U+00E9, all ≤ U+00FF. (d) concatenating `|| '✓'`
(U+2713, outside Latin-1) fails: `PRV-2010  Failed to encode '✓' in character set 'ISO-8859-1'`.
(g) `UPPER(user_id)` filtered on the **literal** `'😀x'` (astral, outside Latin-1) fails identically:
`PRV-2010  Failed to encode '😀x' in character set 'ISO-8859-1'`. Confirmed the boundary directly:
`WHERE user_id = 'café'` (Latin-1) plans; `WHERE user_id = '日本語'` (CJK, outside Latin-1) fails the
same way. **Column data** of the same characters flows through the engine perfectly (SQLX-048a wrote
raw `f0 9f 98 80` UTF-8 bytes for 😀 with no issue) — this is specifically about a character written
as a **SQL literal in the query text**, and the whole campaign missed it until now because the
standing "hostile unicode" fixture string, `ünïcødé`, happens to be entirely Latin-1-representable.
New finding, recorded below as X-12.

### SQLX-177 — PASS
`SELECT user FROM txn` (unquoted) → `PRV-2021  function 'USER' in 'USER' is not supported in a
projection...` — confirms round-1's still-unfixed message defect exactly (a column named `user`
looks to the user like a refused function call). `"user"` (quoted) resolves. Same pair confirmed for
the predicate position. `all`/`one`/`sensitive` unquoted → `PRV-2001` parse errors (different
keywords, different grammar positions); quoted, all resolve.

### SQLX-178 — PASS, and corrects the case's own predicted code
`pravaha register --name user ...` → `PRV-1041  PRV-8008  'user' is a reserved word in SQL, so no
query could read the view. Choose a name that can appear in a FROM clause unquoted.` — refused, and
`pravaha queries` never lists `user`. **Correction**: the case predicts the code would be
`PRV-8001` (`NAME_IN_USE`, "one code now means two things"); the actual code is a **dedicated**
`PRV-8008`, distinct from `NAME_IN_USE` — better than the case feared, not the finding it predicted.

### SQLX-179 — PASS, plus one prediction that does not hold and one new observation
All nine reserved words (`user, all, one, sensitive, value, year, count, table, select`) refused
with the identical `PRV-8008` message; all four controls (`v_txn2`, `txn_summary`, `_private`, `a1`)
register and read back 6 rows. **Registering a 500-character name of plain `a` characters is also
refused as "a reserved word"** — which is false; 500 `a`s is not a SQL keyword under any Calcite
conformance. `requireSayableName`'s probe-parse approach conflates *any* parse failure (here, likely
an identifier-length limit inside Calcite's lexer) with "reserved word", producing a misleading
diagnosis. New finding, recorded below as X-13.

### SQLX-180 — PASS on the sayable-name refusals; contradicts one of the case's two predicted findings
Empty/whitespace names hit the same CLI "--name is required" short-circuit as SQLX-163/164 (never
reach the server). `1abc`, `a-b`, `a.b`, `../escape`, `v_txn ` (trailing space) → all
`PRV-8008  '<name>' cannot be used as a view name: ... must be a plain identifier...`.
**`金额` (a valid identifier to the parser, per SQLX-175) registers and queries successfully** —
**not** refused by an ASCII-only regex as the case's second predicted finding claims; confirmed by
querying `SELECT COUNT(*) FROM 金额` and getting `6`. That specific prediction does not hold on this
build. The null-name NPE (needs the Java API directly, not the CLI) is **NOT RUN**.

### SQLX-181 — PASS, reconfirms Q-13
(a) `SELECT x FROM nosuchstream` → `PRV-2002  Object 'nosuchstream' not found. Known streams: [txn]`
— H-VAL's own catalogue, correctly includes `txn`. (b) `SELECT * FROM nosuchview` over H-VIEW →
`PRV-2002  Object 'nosuchview' not found. Known streams: [a1, trailtest, ..., 金额]` — a list of
**view** names. (c) `SELECT * FROM txn` (the base stream) over H-VIEW → `PRV-2002  Object 'txn' not
found...`, the identical view-only list, **which does not contain `txn`** even though `txn` is a
configured, live base stream — Q-13's exact claim ("Known streams lists views and omits every
configured base stream"), reconfirmed precisely. Never `PRV-2003`.

### SQLX-182 — PASS
(a)/(b) unknown column in the select list and in a predicate → `PRV-2002`, names the column, in both
positions. (c) injection via a bound parameter (`u1' OR '1'='1`) → 0 rows, view unaffected,
`COUNT(*)` still 6 immediately after.

## §9 finding — a registration confirmation echoes the wrong name under fingerprint sharing

Discovered while running SQLX-179's controls: `pravaha register --name freshname123 --sql-file q.sql`
(a brand-new name, identical SQL to the already-registered `v_txn`) prints
`registered v_txn  state=RUNNING  fingerprint=7c6f0450c154` — **the pre-existing name, not the one
just requested.** The registration itself is correct — `pravaha queries` lists `freshname123`
independently, and `SELECT COUNT(*) FROM freshname123` answers `6` — only the **confirmation
message** is wrong. A user who registers a new name under ADR-025's fingerprint-sharing and is told
"registered v_txn" has good reason to think their command used, or collided with, the wrong name.
New finding, recorded below as X-13.

## §10 — `SqlSupportMatrixTest` itself (SQLX-183 … SQLX-190)

### SQLX-183 — **the matrix's own claim has changed since the case was authored; Q-8 is substantially, not fully, fixed**
Seed-proven directly: `Expression.Arithmetic.evaluateLong`'s `case ADD -> Math.addExact(l, r);`
changed to `Math.addExact(l, r) + 1`, rebuilt (`-Dspotless.check.skip=true`), and both
`SqlSupportMatrixTest` and SQLX-017 (H-RUN) run against the seeded tree; then reverted, rebuilt, and
both re-run to confirm green again.
```
$ ./mvnw -o -pl pravaha-sql test -Dtest=SqlSupportMatrixTest    # seeded
[ERROR] everyConstructWithADocumentedAnswerProducesIt FAILED
  "integer arithmetic: expected [201, 501, 101, 801] and produced [202, 502, 102, 802]"
  "a function inside arithmetic: expected [...] and produced [...]"
$ pravaha run --sql "SELECT amount * 2 + 1 FROM txn" ...        # seeded
202 / 502 / -98 / 2 / 16 / 16    # should be 201 / 501 / -99 / 1 / 15 / 15
```
**This contradicts the case's own "Expected" text**, which assumed the matrix would stay green.
**Since this file was authored, `SqlSupportMatrixTest` gained a second test method,
`everyConstructWithADocumentedAnswerProducesIt`, and a `Case.answers(...)` factory that feeds `D1`
through a real, collecting pipeline and asserts rendered row values** — not merely `"OK"` outcome
strings. Counted directly in the source: of 122 `Case` entries, **55 use `Case.answers(...)`** (real
value assertions), 49 use `Case.refused(...)` (refusal-code assertions, unaffected by this question),
and 17 use plan-only `Case.ok(...)`/1 `Case.lookupOk(...)` (still no value check). **Q-8, as stated
("no row is ever fed and no value is ever compared"), is no longer true of this file — a wrong
answer in most of the matrix's covered arithmetic, string, predicate, and aggregate constructs would
now fail the build.** The residual gap (SQLX-189's territory: `overBoundedInput()` still does not
appear anywhere in this file, so `SELECT DISTINCT` and unwindowed `GROUP BY` over a **view** remain
unchecked by it; and 17 plan-only `Case.ok` entries) is real but materially smaller than Q-8
describes. Recorded as a correction to `FINDINGS.md` below (X-14) rather than edited into Q-8
directly, since I did not trace which commit made this change or whether the round that recorded Q-8
saw an earlier version of this file.

### SQLX-184 — PASS as literally specified, superseded in spirit by SQLX-183's discovery
The original `messageOf`/`Case.ok`/`Case.refused` machinery (49+17+1 = 67 of 122 cases) still never
feeds a row — confirmed by reading `messageOf`'s `RowOutput` supplier, unchanged: a bare
`throw new UnsupportedOperationException("the matrix builds pipelines but never runs rows through
them")`. True of the plan-only two-thirds of the file; no longer true of the 55 `Case.answers` cases,
which run through the *different*, collecting `answerOf` helper SQLX-183 exercised.

### SQLX-185 — PARTIAL
Not run as a full 42-row mechanical audit (out of session budget), but SQLX-183's count answers the
shape of the question directly: the document's numeric, string, predicate and single-row aggregate
✅ rows are now backed by `Case.answers`; the exceptions the case itself predicts as blank (joins,
CAST, several scalar functions) were spot-checked against the 55-entry list and several **are**
present (`Case.answers("CAST", ...)` at line 184, `Case.answers("text functions", ...)` at line 171)
— so the case's own list of expected blanks is itself partly stale. A full row-by-row 42-entry table
is **NOT RUN**.

### SQLX-186 — PASS
Spot-checked five ❌ constructs from §5/§6/§9 through H-VAL directly in this session (`ORDER BY`,
`UNION`, `VALUES`, `SESSION`, reserved-word registration) — every one refuses before any file is
opened or any row is read. The self-join remains the one documented exception (confirmed via
`SqlxMultiStreamTest`: it plans and compiles, refused only when the pipeline itself is instantiated,
with no `PRV-` code) — `pravaha validate` would indeed report it `valid`, exactly as the case warns.

### SQLX-187 — PARTIAL
Not run as the full three-property table across every refusal (out of session budget). The
nine-message "does not name a token from the user's SQL" list is independently confirmed by this
session's own findings for four of the nine: `ORDER BY`/`LIMIT`/`OFFSET` (SQLX-121-124, all
`LogicalSort`) and `EXCEPT` (SQLX-130, `LogicalMinus`) — matching the case's prediction exactly.
`SQRT`/`POWER` (SQLX-036) also confirmed. `UNION`/`INTERSECT`/`VALUES`/`INSERT` not independently
re-checked against this specific criterion this session.

### SQLX-188 — NOT RUN
A full three-way diff (document × matrix × this file) was not built as a table this session — out of
budget. Individual gaps the case predicts were independently confirmed as real in passing:
`GROUPING SETS`/`CUBE`/`ROLLUP` (SQLX-112), the float-aggregate refusal (SQLX-113), narrow-integer
aggregates (SQLX-114) and the reserved-word view name (SQLX-178) are all, confirmed, in neither the
document nor (by construction, since they are refusals this file discovered) the original matrix.

### SQLX-189 — PASS, and still the sharpest single finding in this section
`grep -c overBoundedInput pravaha-sql/src/test/java/.../SqlSupportMatrixTest.java` → **0**, confirmed
directly in the source read for SQLX-183 above. The matrix — even after gaining
`everyConstructWithADocumentedAnswerProducesIt` — has no bounded-plan path at all, so the document's
entire stream/view asymmetry (SQLX-023 vs SQLX-024, SQLX-115 vs SQLX-116, `SELECT DISTINCT`) remains
untested by it; every one of those pairs in this log came from H-VIEW or `SqlAnswerTest`'s own
`overBoundedInput()` harness, not from `SqlSupportMatrixTest`.

### SQLX-190 — PASS as an evaluation of the predicate, with a materially different premise than the case assumed
*For every row of every table on this page, some test observes the behaviour the row describes* is
still **false** — but for a narrower reason than the case states. It is no longer false because "no
row is ever compared" (SQLX-183); it is false because (a) the bounded/view half of the document is
untested by this specific file (SQLX-189) and (b) a residual ~14% of the matrix's ✅-shaped rows
(17 of 122) are still plan-only. The honest repair the case proposes — name where the answers are
checked, and add a `bounded` flag — is *more* achievable now than the case assumed: most of the
engineering it asks for already landed.

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

## New finding — SQLX-059, HIGH, not yet in `FINDINGS.md`

**TIME column predicates against a `TIME` literal are wrong by a factor of 1,000,000, silently.**
`ExpressionCompiler.literal` converts a Calcite `TIME` literal to nanoseconds
(`getValueAs(Integer.class) * 1_000_000L`, since Calcite carries `TIME` in milliseconds-of-day and the
engine holds everything in nanoseconds per ADR-012). `DelimitedCodec.setField`'s `TIME` branch is
`case INT64, TIME, TIMESTAMP_LTZ -> writer.setLong(ordinal, Long.parseLong(raw))` — no scaling at all,
so a CSV field meant as milliseconds-of-day (the only representation a person would hand-write; the
`SQLX.md` fixture itself uses `3600000` to mean `01:00:00`) is stored as if it were already
nanoseconds. Every `WHERE <TIME col> <op> TIME '...'` comparison over data ingested through the
shipped filesystem plugin is therefore silently wrong for any time other than midnight — not refused,
not an error, a wrong row set under a success status. Reproduced with `WHERE tm < TIME '00:00:01'`
matching all three rows of `all.csv`, including two meant to represent 1 and 2 hours after midnight.
Whoever owns `FINDINGS.md`'s numbering should give this a key; recorded here with full reproduction
so it is not lost. Comparing a `TIME` column to a bare integer is itself refused by Calcite
(`PRV-2002`, "TIME(0) = INTEGER"), so a user cannot work around this by matching the codec's own
units — there is no way to write a `TIME` predicate against this codec's output that is both legal
SQL and correct.
