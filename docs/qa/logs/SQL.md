# SQL surface and query semantics — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/SQL.md`](../cases/SQL.md). Executed 2026-09-12 on branch `develop`, commit
`2c287dd`, against the pre-built artefacts.

**Environment.** Java 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), `bin/pravaha` and
`bin/pravaha-server` from the tree. One server on HTTP **18400** / Flight **19400** with
`allow-anonymous: true`, streams `txn`, `other`, `dim`, `nulls`, `resv` declared and all five bound
to the filesystem source plugin. Server PID recorded at start and killed by number at the end; no
`pkill` was used.

**Two paths.** `pravaha run` executes on the real lane runtime and the real physical operators over
a file; `register` / `query` execute inside the server process over Flight. Both go through the same
`SqlPlanner` → `PhysicalPlanBuilder`. Refusal-only cases use `pravaha validate`, which plans and
builds without running.

**Reading the output.** `cat -A` is used on result files so a trailing space or a tab is visible;
`^I` is a tab. The filesystem codec's null literal is the empty string, so a NULL and an empty
string are the same three bytes in a CSV — where that mattered (SQL-028) the case was re-run through
the server, where the wire format distinguishes them.

---

### SQL-001 — PASS

```
$ pravaha run --sql "SELECT id, n * 2 + 1 AS a, n - 3 AS b FROM txn" --schema 'id:INT64,n:INT64?,d:FLOAT64?,s:STRING?' --in num.csv --out-schema 'id:INT64,a:INT64?,b:INT64?'
ok  5 in, 5 out
1,21,7
2,1,-3
3,-13,-10
4,,
5,18014398509481987,9007199254740990
```

**Verdict:** every value matches the hand computation, including row 5 where `2 × 9007199254740993 + 1`
exceeds 2^53 — proof the multiply stayed in `long` rather than going through a double. Not vacuous:
5 rows in, 5 out, each distinct.

### SQL-002 — PASS

```
$ pravaha run --sql "SELECT id, n / 3 AS q FROM txn" ...
ok  5 in, 5 out
1,3
2,0
3,-2
4,
5,3002399751580331
```

**Verdict:** integer division, truncating towards zero. `-7 / 3 = -2`, not the `-3` a floor division
would give. Row 5 is exact at 3002399751580331, so no double was involved.

### SQL-003 — PASS (and see SQL-065)

```
$ pravaha run --sql "SELECT id, n % 3 AS m FROM txn" ... --out-schema 'id:INT64,m:INT32?'
ok  5 in, 5 out
1,1
2,0
3,-1
4,
5,0
```

**Verdict:** `-7 % 3 = -1`, taking the sign of the dividend as SQL requires. A `Math.floorMod`
would have produced 2 and did not.

The first attempt of this case, with `--out-schema 'm:INT64?'`, printed `4294967295` for the same
row. That is not a modulo defect — Calcite types `MOD(BIGINT, INTEGER)` as `INTEGER`, so the plan's
output column is 32 bits and the declared 64-bit sink read `0xFFFFFFFF` as an unsigned value. It is
a defect in `pravaha run`, recorded separately as SQL-065.

### SQL-004 — PASS

```
$ pravaha run --sql "SELECT id, d / 0.0 AS q FROM txn" ...
ok  5 in, 5 out
1,Infinity
2,Infinity
3,-Infinity
4,
5,NaN
```

**Verdict:** IEEE semantics, as documented: no row lost, no zero substituted, `0.0/0.0` is NaN and
`-2.5/0.0` keeps its sign. Not vacuous — all five rows are present and four distinct values appear.

### SQL-005 — FAIL

```
$ /usr/bin/time -f 'ELAPSED %e s' pravaha run --sql "SELECT id, 100 / n AS q FROM txn" ... --out-schema 'id:INT64,q:INT64?'
IllegalStateException: the query did not finish within five minutes; the lane is still working or
stuck. Check its metrics: [LaneMetrics[laneId=0, rowsIn=5, rowsOut=0, batches=0, idleCycles=19,
rejectedOffers=0, arenaHighWaterBytes=0, inboxFill=0.001220703125, exchangedIn=0]]
--- out.csv ---
(empty)
ELAPSED 300.96 s
```

**Verdict:** **FAIL, high severity.** One row with `n = 0` in a five-row file causes:

1. the query to **hang for five minutes** and then fail with a message that says nothing about
   division by zero;
2. **all five rows to be lost**, not just the offending one — `rowsIn=5, rowsOut=0`. Rows 1, 3 and 5
   had perfectly good answers (10, −14, 5);
3. the `ArithmeticException` raised in `Expression.Arithmetic.divideByZero()` to be **swallowed**.
   Its message ("the record is routed to the DLQ rather than given a value that could be mistaken
   for an answer") never reaches the user, and nothing is routed anywhere.

The engine does not produce a wrong number, which is the one thing it gets right here. Everything
else about the failure mode — the five-minute stall, the whole-batch data loss, the generic
message — is worse than a plain refusal. `SQL_SUPPORT.md` does not document the behaviour of `/ 0`
on integers at all; it should, and the behaviour should be fail-fast.

### SQL-006 — FAIL

```
# first attempt
$ pravaha run --sql "SELECT n + 1 AS a FROM txn" --in max.csv   (n = 9223372036854775807)
ok  1 in, 0 out
  plan 1055943 us, execute 42923 us
ELAPSED 1.20 s
--- out.csv ---
(empty)

# three further attempts, same command, same input
attempt 1 exit=1 : IllegalStateException: the query did not finish within five minutes; the lane is
still working or stuck. Check its metrics: [LaneMetrics[laneId=0, rowsIn=1, rowsOut=0, batches=0,
idleCycles=21, rejectedOffers=0, arenaHighWaterBytes=0, inboxFill=2.44140625E-4, exchangedIn=0]]
```

**Verdict:** **FAIL, high severity, and intermittent.** `Math.addExact` correctly refuses to wrap —
no `-9223372036854775808` was ever produced, which is the important half. But the consequence the
user sees is one of two things, non-deterministically:

* `ok  1 in, 0 out` in 1.2 s, **exit 0** — success reported, row silently gone, no reason given
  anywhere;
* the same five-minute stall as SQL-005, **exit 1**, with a message that never mentions overflow.

The retry above reproduced the second outcome; the first was seen once, on the same command and the
same one-row file. So the exit code of an overflowing query is not predictable either, which matters
for anything running `pravaha run` in a pipeline.

Two different outcomes from the same command on the same input is itself a defect. Either way an
overflow is an unlogged, unreported row loss under a green `ok`.

### SQL-007 — FAIL

```
$ pravaha run --sql "SELECT id, n + 1 AS a, n * 0 AS b FROM txn" ...
ok  5 in, 5 out
1,11,0
2,1,0
3,-6,0
4,,          <- row 4: n is NULL, both a and b are NULL
5,9007199254740994,0

$ pravaha run --sql "SELECT SUM(n) AS s, COUNT(n) AS c, COUNT(*) AS t FROM txn" ...
ok  5 in, 1 out
9007199254740996,5,5
```

**Verdict:** the projection half **passes** — NULL propagates, and `n * 0` over a NULL is NULL
rather than 0, which is the case that separates SQL semantics from Java's.

The aggregate half **FAILS**: `COUNT(n)` returns **5** where the column has four non-null values and
one NULL. `COUNT(*)` also returns 5, so the two are indistinguishable and `COUNT(<column>)` is
simply `COUNT(*)`. `SUM(n) = 9007199254740996` is right (10 + 0 − 7 + 9007199254740993).

Cause, from `GlobalAggregate.process`: `case COUNT -> counts[i] += weight;` — the branch never looks
at `call.argumentOrdinal()` or at `row.isNull(...)`, unlike the `SUM, AVG` and `MIN, MAX` branches
immediately below it, which both do. High severity: a wrong number, silently, from the most common
aggregate there is.

### SQL-008 — FAIL

```
$ pravaha run --sql "SELECT id, -n AS neg FROM txn" ...
PRV-2021  '-($1)' has 1 operands; only the two-operand form is supported (unary minus included,
which Calcite normalises to 0 - x).
```

**Verdict:** **FAIL, medium severity.** `SELECT -n` is refused. The refusal's own text asserts that
unary minus is included because Calcite normalises it to `0 - x`; the `RexCall` printed in the same
message shows that Calcite did no such thing — it handed over a one-operand `-($1)`. So the feature
is absent and the message says it is present, which is the worst combination for whoever reads it
next. `SQL_SUPPORT.md` lists "Integer and floating arithmetic" as ✅ without excluding negation.

Workaround exists (`0 - n`), which is why this is medium rather than high.

### SQL-009 — PASS

```
$ pravaha run --sql "SELECT id, ABS(n) AS an, FLOOR(d) AS fd, CEIL(d) AS cd, ABS(d) AS ad FROM txn" ...
ok  5 in, 5 out
1,10,2.0,3.0,2.5
2,0,3.0,4.0,3.5
3,7,-3.0,-2.0,2.5
4,,,,
5,9007199254740993,0.0,0.0,0.0
```

**Verdict:** all correct, including the negative case (`FLOOR(-2.5) = -3`, `CEIL(-2.5) = -2`) and
null-in-null-out on row 4.

### SQL-010 — FAIL

```
$ pravaha run --sql "SELECT ABS(n) AS a FROM txn" --in min.csv   (n = -9223372036854775808)
ok  1 in, 1 out
-9223372036854775808
```

**Verdict:** **FAIL, medium severity.** `ABS` of `Long.MIN_VALUE` returns a **negative absolute
value**, reported as `ok`. `Expression.Unary` uses `Math.abs(long)`, which is documented by the JDK
to return the argument unchanged for `Long.MIN_VALUE`.

This is inconsistent with the engine's own stated policy one method away: `Arithmetic.evaluateLong`
uses `Math.addExact` / `multiplyExact` specifically so that an overflow cannot be mistaken for an
answer, and then `ABS` produces exactly such an answer. Rarely reachable, wrong when it is, and
silent.

### SQL-011 — PASS

```
$ pravaha run --sql "SELECT FLOOR(n) AS f, CEIL(n) AS c, ROUND(n) AS r FROM txn WHERE id = 5" ...
ok  5 in, 1 out
9007199254740993,9007199254740993,9007199254740993
```

**Verdict:** 2^53 + 1 survives all three functions exactly. Had the integer path gone through a
double the answer would have been 9007199254740992, so this case distinguishes the two
implementations and confirms the one the comment in `Expression.Unary` claims.

### SQL-012 — FAIL

```
$ pravaha run --sql "SELECT id, ROUND(d) AS r FROM txn" ...
ok  5 in, 5 out
1,2.0      <- ROUND(2.5)
2,4.0      <- ROUND(3.5)
3,-2.0     <- ROUND(-2.5)
4,
5,0.0
```

**Verdict:** **FAIL, high severity.** `ROUND` is **banker's rounding** (half to even), not SQL's
round-half-away-from-zero. `ROUND(2.5)` is 2 where SQL, Calcite's own implementation, PostgreSQL,
MySQL, Oracle and SQL Server all give 3; `ROUND(-2.5)` is −2 where they give −3. `ROUND(3.5) = 4`
agrees only by coincidence — 4 is even.

Cause: `Expression.Unary.evaluateDouble` uses `Math.rint(value)`. The correct primitive is
`Math.round`-style half-away-from-zero, or `BigDecimal.setScale(0, RoundingMode.HALF_UP)`.

This is the most dangerous defect in the expression layer because every value is plausible. Half the
inputs round the way the user expects and the other half are off by one in a direction nobody
checks, and a reconciliation against any other database disagrees on exactly the `.5` rows.
`SQL_SUPPORT.md` lists `ROUND` as ✅ with no note about rounding mode.

### SQL-013 — PASS

```
$ pravaha validate --sql "SELECT ROUND(d, 2) FROM txn" ...
PRV-2021  ROUND is supported with one argument and was given 2. ROUND to a number of decimal
places is not built; round the value and scale it, or cast it.
```

**Verdict:** refused, correct code, and the message says what to do instead.

### SQL-014 — PASS, with a UX finding

```
$ pravaha validate --sql "SELECT SQRT(n) FROM txn" ...
PRV-2021  function 'POWER' in 'POWER($1, 0.5:DECIMAL(2, 1))' is not supported in a projection.
Supported: + - * / %, ABS, FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, SUBSTRING and || .

$ ... EXP(d)    -> PRV-2021  function 'EXP' in 'EXP($2)' is not supported ...
$ ... LN(d)     -> PRV-2021  function 'LN' in 'LN($2)' is not supported ...
$ ... POWER(n,2)-> PRV-2021  function 'POWER' in 'POWER($1, 2)' is not supported ...
$ ... MOD(n, 3) -> valid  736827 us
                     output: [EXPR$0 INT32]
```

**Verdict:** every unsupported function is refused with `PRV-2021` and the supported list is given,
so a user is never left guessing what they may use.

**On the `SQRT` → `POWER` question the brief asks about: it is acceptable, but only just, and it
should be improved.** The argument for acceptable: the message ends with the full list of what *is*
supported, `SQRT` is not on it, and a user therefore reaches the right conclusion even though the
function named is not the one they wrote. The argument against: the user searches their own SQL for
`POWER`, does not find it, and concludes the error is about a different query. A rewrite whose only
trace is `0.5:DECIMAL(2, 1)` is not something a user can be expected to decode. The same class of
problem appears in SQL-048, where `LIMIT 3` is refused as `LogicalSort`. The cheap fix is to keep
the original SQL text and, when the refused expression does not appear in it, say so: "…`POWER`,
which is how the planner rewrote `SQRT`".

**Undocumented supported function:** `MOD(n, 3)` plans and runs, because `ExpressionCompiler` maps
`MOD` alongside `%`. `SQL_SUPPORT.md` says numeric functions beyond `ABS/FLOOR/CEIL/ROUND` are
`PRV-2021`. Harmless, but the document is wrong and `SqlSupportMatrixTest` does not cover it.

### SQL-015 — PASS

```
$ pravaha run --sql "SELECT id, CASE WHEN n > 5 THEN 1 WHEN n = 0 THEN 2 END AS c FROM txn" ...
ok  5 in, 5 out
1,1
2,2
3,
4,
5,1
```

**Verdict:** branch selection correct, and a CASE with no ELSE is NULL rather than 0 for both the
row that matches nothing (id 3) and the row whose tests are UNKNOWN because `n` is NULL (id 4).
Not vacuous: three distinct outcomes appear across five rows.

### SQL-016 — PASS

```
$ pravaha run --sql "SELECT id, CASE WHEN n = 0 THEN 0 ELSE 100 / n END AS c FROM txn" ...
ok  5 in, 5 out
1,10
2,0       <- n = 0, guarded branch taken, no division performed
3,-14
4,
5,0
```

**Verdict:** the guard guards. Five rows out and no error, where row 2 would divide by zero if both
arms were evaluated.

**Why this is not a vacuous pass:** SQL-005 runs the *same* division unguarded over the *same* file
and hangs for five minutes producing nothing. So the division genuinely does fail on this data, and
the only thing separating the two cases is the CASE. `100 / -7 = -14` also confirms the ELSE arm was
really evaluated on the other rows rather than short-circuited to a default.

### SQL-017 — PASS

```
$ pravaha run --sql "SELECT id, CASE WHEN n > 0 THEN CASE WHEN n > 100 THEN 2 ELSE 1 END ELSE 0 END AS c FROM txn" ...
ok  5 in, 5 out
1,1
2,0
3,0
4,0
5,2
```

**Verdict:** correct, including the subtlety that row 4 (`n` NULL) takes the ELSE and yields 0 rather
than NULL — UNKNOWN fails the guard, and the ELSE is a non-null literal.

### SQL-018 — PASS

```
1,big
2,small
3,small
4,small
5,big
```

**Verdict:** text CASE, five rows, no NULLs.

### SQL-019 — PASS

```
$ pravaha validate --sql "SELECT id, CASE WHEN n > 5 THEN 'big' ELSE 0 END AS label FROM txn"
valid   output: [id INT64 NOT NULL, label VARCHAR NOT NULL]
$ pravaha run  (same SQL)
1,big
2,0
3,0
4,0
5,big
$ pravaha validate --sql "SELECT SUM(CASE WHEN n > 5 THEN 'big' ELSE 0 END) FROM txn"
PRV-2021  'CAST(CASE(>($1, 5), 'big':VARCHAR, '0':VARCHAR)):DECIMAL(38, 19) NOT NULL' is DECIMAL
arithmetic, which Pravaha refuses rather than approximates. …
```

**Verdict:** exactly what `SQL_SUPPORT.md` documents — accepted, the column is text, the `0` arrives
as `'0'`, and a `SUM` over it does not plan. The document is right about a genuinely surprising
corner.

### SQL-020 — PASS

```
$ pravaha run --sql "SELECT id, UPPER(s) AS u, LOWER(s) AS l FROM txn" ...
1,HELLO,hello
2,  SPACED  ,  spaced
3,ANN,ann
4,,
5,ANNABEL,annabel

$ PRAVAHA_JAVA_OPTS="-Duser.language=tr -Duser.country=TR" pravaha run --sql "SELECT UPPER(s) AS u FROM txn" --in turk.csv
ok  1 in, 1 out
I
```

**Verdict:** conversion is root-locale, so the answer does not depend on the machine.

**Negative control, so the Turkish half is not vacuous:**

```
$ java -Duser.language=tr -Duser.country=TR L.java
tr_TR -> İ
```

The same JVM flags, in the same JDK, make `"i".toUpperCase()` return `İ`. The engine returned `I`,
so it demonstrably did not use the default locale.

### SQL-021 — PASS

```
$ pravaha run --sql "SELECT '[' || TRIM(s) || ']' AS t FROM txn" --in trim.csv   (cat -A output)
[spaced]
[^ITABBED^I]
[mixed^I]
```

**Verdict:** spaces and only spaces. Tabs survive at both ends (row 2), and in row 3 the leading
space goes while the trailing tab stays — which is the case that separates `TRIM` from
`String.strip()`. Not vacuous: the brackets prove the function ran and returned a shorter string.

### SQL-022 — PASS

```
TRIM(LEADING ' ' FROM s)  -> PRV-2021  'TRIM(FLAG(LEADING), ' ', $3)' is not supported: TRIM strips
                              spaces from both ends, and LEADING, TRAILING and a trim character
                              other than a space are not built.
TRIM('x' FROM s)          -> PRV-2021  'TRIM(FLAG(BOTH), 'x', $3)' …
TRIM(TRAILING ' ' FROM s) -> PRV-2021  'TRIM(FLAG(TRAILING), ' ', $3)' …
```

**Verdict:** all three refused rather than silently given the default, which is what the contract
promises and the only safe behaviour — the default would return the input unchanged and look right.

### SQL-023 — PASS

```
$ SELECT SUBSTRING(s FROM 1 FOR 2) AS a, SUBSTRING(s FROM 2) AS b   (s = 'hello')
he,ello
```

**Verdict:** 1-based. A 0-based implementation would have given `el`.

### SQL-024 — PASS

```
$ SELECT SUBSTRING(s FROM 1 FOR 2) AS a, SUBSTRING(s FROM 3 FOR 1) AS b   (s = '😀😁abc')
(cat -A) M-pM-^_M-^XM-^@M-pM-^_M-^XM-^A,a
```

**Verdict:** the two output bytes sequences are `F0 9F 98 80` and `F0 9F 98 81` — both emoji whole,
neither surrogate pair split. Position 3 is `a`, so the counting is in code points and not in
`char`s: a char-index implementation would have returned `😀` for the first and half a surrogate
for the second.

### SQL-025 — PASS

```
$ SELECT SUBSTRING(s FROM -1 FOR 4) AS a, SUBSTRING(s FROM 0 FOR 2) AS b   (s = 'hello')
he,h
```

**Verdict:** positions below 1 contribute nothing rather than shifting the window. `FROM -1 FOR 4`
covers positions −1, 0, 1, 2, of which only 1 and 2 exist, so `he`. Clamping start to 1 — the
plausible wrong fix the contract warns about — would have returned `hell`.

### SQL-026 — PASS

```
$ SELECT SUBSTRING(s FROM 4 FOR 100), SUBSTRING(s FROM 9 FOR 2), SUBSTRING(s FROM 1 FOR 0)
lo,,
```

**Verdict:** a length past the end clamps; a start past the end is empty; a zero length is empty. No
exception, no negative-length arithmetic.

### SQL-027 — PASS

```
$ SELECT s || '-' || UPPER(s) || '!' AS j   (s = 'hello')
hello-HELLO!
```

**Verdict:** a four-part chain, flattened correctly, with a function in the middle.

### SQL-028 — PASS

The `run` path cannot answer this case: the filesystem codec writes NULL as the empty string, so
NULL and `''` are the same bytes in a CSV, and the two checks the case proposed are both refused
(`('x'||s||'y') IS NULL` → `PRV-2021`, since IS NULL only accepts a bare column; and comparing a
text *expression* to a literal → `PRV-2021`). Re-run through the server, where Arrow distinguishes
them:

```
$ pravaha register --name catnull --sql-file (SELECT id, 'x' || s || 'y' AS j FROM nulls) --keys 0
$ pravaha query --sql "SELECT * FROM catnull"
id	j
1	xhelloy
2	NULL        <- s is NULL on this row
3	xanny
3 rows

$ pravaha query --sql "SELECT id FROM catnull WHERE j IS NULL"        -> 2        (1 row)
$ pravaha query --sql "SELECT id FROM catnull WHERE j IS NOT NULL"    -> 1, 3     (2 rows)
```

**Verdict:** NULL concatenated with anything is NULL, not an empty string. Not vacuous — the
complementary query returns the other two rows, so the single-row result is a real partition of the
view rather than an empty answer.

**Two adjacent findings.** `IS NULL` is documented as ✅ with no caveat but only accepts a bare
column reference — `PredicateCompiler.nullCheck` throws unless the operand is a `RexInputRef`. And
the filesystem source cannot represent an empty string at all, since `null.literal` defaults to the
empty string and is not exposed by the CLI.

### SQL-029 — PASS

```
s LIKE 'ann%'      -> 3,ann  5,annabel        (2 rows)
s LIKE 'ann_bel'   -> 5,annabel               (1 row)
s LIKE '_ello'     -> 1,hello                 (1 row)
```

**Verdict:** `%` matches a run, `_` matches exactly one character. `ann_bel` matches `annabel`
(`a-n-n-[a]-b-e-l`) and nothing else, and `_ello` matches `hello` but not `annabel`.

### SQL-030 — PASS

```
s LIKE 'ann'   -> 3,ann                 (1 row)   <- annabel NOT matched
s LIKE '%ann%' -> 3,ann  5,annabel      (2 rows)  <- control
```

**Verdict:** anchored at both ends. The control proves the first result is not empty-for-the-wrong-reason:
the same column and the same substring return two rows when the anchors are removed.

### SQL-031 — PASS

```
s LIKE '%.com' -> 1,mail.com   (1 row)   <- mailxcom NOT matched
s LIKE 'a+b'   -> 3,a+b        (1 row)   <- ab NOT matched
```

**Verdict:** regex metacharacters are literal. The fixture contains `mailxcom` and `ab` precisely so
that a leaked regex would show up as an extra row, and it does not.

### SQL-032 — PASS

```
COUNT(*) WHERE s LIKE 'ann%'     -> 2
COUNT(*) WHERE s NOT LIKE 'ann%' -> 2
COUNT(*)                         -> 5
```

**Verdict:** 2 + 2 = 4 against 5 input rows. The NULL row belongs to neither side, which is SQL's
three-valued logic and the thing a naive `!like` gets wrong. This is the strongest single piece of
evidence in the LIKE section, because a wrong implementation makes the two sides sum to the total.

### SQL-033 — PASS

```
WHERE s LIKE s              -> PRV-2021  … uses a pattern that is not a literal. The pattern is
                                compiled once when the query is registered; one that varies per row
                                would be compiled per row.
WHERE s LIKE 'a!%b' ESCAPE '!' -> PRV-2021  … uses LIKE with an ESCAPE clause, which is not built.
                                Without ESCAPE, % and _ are always wildcards and there is no way to
                                match them literally.
```

**Verdict:** both refused, both messages explain the reason rather than just the fact.

### SQL-034 — PASS

```
COUNT(*) WHERE n > 5   -> 2
COUNT(*) WHERE n <= 5  -> 2
COUNT(*) WHERE n = 10  -> 1
```

**Verdict:** 2 + 2 = 4 against 5 rows; the NULL is UNKNOWN on both sides and kept by neither.

### SQL-035 — PASS

```
COUNT(*) WHERE NOT (n > 5)             -> 2
COUNT(*) WHERE NOT (n > 5 AND n < 100) -> 3
```

**Verdict:** both match the hand computation. The first keeps rows 2 and 3 and leaves the NULL row
out — a Java `!` over a two-valued comparison would have returned 3. The second keeps rows 2, 3 and
5 (row 5 fails `n < 100`), again without the NULL. De Morgan push-down is doing what
`PredicateCompiler.negate` claims.

### SQL-036 — PASS

```
COUNT(*) WHERE n IS NULL     -> 1
COUNT(*) WHERE n IS NOT NULL -> 4
```

**Verdict:** 1 + 4 = 5, an exact partition — the behaviour that distinguishes a null check (total)
from a comparison (SQL-034, which does not partition).

### SQL-037 — FAIL

```
$ pravaha run --sql "SELECT COUNT(*) AS t, COUNT(d) AS c, SUM(d) AS s, AVG(d) AS a, MIN(d) AS mn, MAX(d) AS mx FROM txn"
ok  5 in, 0 out
--- out.csv ---
(empty)
```

Decomposed:

```
SELECT SUM(d)   FROM txn  -> ok  5 in, 0 out   (nothing)
SELECT AVG(d)   FROM txn  -> ok  5 in, 0 out   (nothing)
SELECT MIN(d)   FROM txn  -> ok  5 in, 0 out   (nothing)
SELECT COUNT(d) FROM txn  -> 5                 (should be 4)
SELECT SUM(n)   FROM txn  -> 9007199254740996  (correct, INT64 column)
SELECT MIN(n)   FROM txn  -> -7                (correct)
SELECT MAX(n)   FROM txn  -> 9007199254740993  (correct)
SELECT AVG(n)   FROM txn  -> 2251799813685249  (= 9007199254740996 / 4, so AVG does skip NULLs)
SELECT SUM(d)   FROM txn  over a file with no NULLs at all -> ok  4 in, 0 out  (still nothing)
```

Confirmed through the server, where the same aggregate over a view fails loudly instead of silently:

```
$ pravaha register --name fv (SELECT txn_id, user_id, CAST(amount AS DOUBLE) / 2 AS half FROM txn)
$ pravaha query --sql "SELECT * FROM fv"
txn_id	user_id	half
1	u1	50.0
… 7 rows, all correct …

$ pravaha query --sql "SELECT SUM(half) AS s, MIN(half) AS mn, MAX(half) AS mx, AVG(half) AS a, COUNT(half) AS c FROM fv"
PRV-1041  field 0 ('s') is FLOAT64, not INT64 in schema fv_projected_aggregated

$ pravaha query --sql "SELECT user_id, SUM(half) AS s FROM fv GROUP BY user_id"
PRV-1041  field 1 ('s') is FLOAT64, not INT64 in schema fv_projected_aggregated
```

**Verdict:** **FAIL, high severity, two distinct defects.**

**(a) Every aggregate over a floating-point column is broken.** `SUM`, `AVG`, `MIN` and `MAX` over a
`FLOAT64` column produce **no rows at all** on the streaming path while reporting `ok`, and fail on
the view path with a raw internal type error that leaks a generated schema name and carries no
`PRV-` code of its own. It is not a null-handling artefact: a file with no nulls behaves identically.
Cause: `GlobalAggregate` accumulates in `long[] sums` via `row.getLong(...)` and emits via
`writer.setLong(i, value)` regardless of the column's type. `KeyedAggregate` (lines 271, 283) and
`WindowedAggregate` (line 160) read the same way, so the defect is systemic rather than local to one
operator. `SQL_SUPPORT.md` lists `COUNT/SUM/MIN/MAX/AVG` as ✅ with no type restriction, and lists
`REAL` and `DOUBLE` as supported in expressions.

Worth stating plainly: on the streaming path this returns **no answer under a green `ok`**, and on
the keyed path the output write *is* type-aware (`((Number) value).doubleValue()`), so if the read
side is ever reached with a double column the accumulated `long` bit pattern would be reinterpreted
as a double — a number rather than an absence. The current loud failure is the better of the two
outcomes.

**(b) `COUNT(<column>)` counts NULLs** — see SQL-007. `COUNT(d)` is 5 where four values are non-null.

### SQL-038 — PASS

```
SELECT COUNT(*) AS c FROM txn -> 5   (exactly one row)
```

**Verdict:** one group, one row, right number.

### SQL-039 — FAIL

```
$ pravaha register --name tum --sql-file tumble.sql --keys 0,1,2
registered tum  state=RUNNING  fingerprint=4eef1f8197ed

$ pravaha queries
NAME	STATE	FINGERPRINT	ROWS IN
tum	RUNNING	4eef1f8197ed	7

$ pravaha query --sql "SELECT * FROM tum"     (at +6 s, +40 s and +18 min after registration)
0 rows
```

Also through the CLI, both documented syntaxes:

```
$ pravaha run … TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) …
ok  7 in, 0 out
$ pravaha run … GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), user_id  (the QUICKSTART form)
ok  7 in, 0 out
```

**Verdict:** **FAIL, and the most serious finding in this area.** Windowed aggregation — which
`SQL_SUPPORT.md` calls "the point of the engine", "not a feature bolted onto it" — **emits nothing**
through either documented path. The query plans, registers, reports `RUNNING`, ingests all seven
rows, and produces no output, forever, with no error in the log and no state visible to a user other
than `ROWS IN` climbing and the view staying empty. That is the single worst shape a defect can
take: the system says it is working.

Two independent causes, both established from the source after the observation:

1. **No stream declared through a schema *spec string* has an event-time column.**
   `FilesystemSourcePlugin.parseSchema` — used by `pravaha run --schema` **and** by the server for
   `pravaha.streams.<name>.schema` — builds a `StreamSchema` and never calls `.eventTime(...)`. The
   `name:TYPE` grammar has no syntax for saying which column is the event time. `grep -rn eventTime
   pravaha-server/src/main/java` returns nothing. The `TIMESTAMP` field is decoded as data and is
   never the row's event timestamp, so the watermark has nothing to advance on and a window whose
   end is in 2026 never closes.
2. **The CLI `run` path generates no watermarks at all.** `QueryRunner.run` calls
   `QueryExecution.start(...)` and never `generatingWatermarks(...)`; the server does
   (`QueryRegistry` line 438), which is why the two paths fail for overlapping but different
   reasons.

The engine's windowing is not broken in itself — `pravaha-it/WindowedOnLanesTest` builds its schema
with `.eventTime("event_time")` in Java and passes. What is missing is any way to say that from the
product's own configuration surface, which makes the feature unreachable for every user who does not
write Java against the embedded API. It also means `QUICKSTART.md` §4–6, the whole
register-and-watch flow, cannot produce the output it prints.

### SQL-040 — BLOCKED

```
$ pravaha register --name hp --sql-file hop.sql --keys 0,1,2     (HOP, 5 s slide, 10 s size)
registered hp  state=RUNNING  fingerprint=959085b73a4b
$ pravaha queries  ->  hp  RUNNING  959085b73a4b  7
$ pravaha query --sql "SELECT * FROM hp"  ->  0 rows
```

**Verdict:** **BLOCKED by SQL-039.** HOP plans, registers and ingests, and emits nothing for the
same reason TUMBLE does. The property this case exists to check — that each row lands in two
overlapping windows — cannot be observed while no window closes. Not counted as a pass.

### SQL-041 — PASS

```
$ pravaha register --name g1 (SELECT user_id, COUNT(*) FROM txn GROUP BY user_id)
PRV-1041  PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the
number of distinct keys and never shrinks. One row per key is fine at a thousand keys and fatal at a
hundred million, and the failure arrives weeks after deployment.
  Bound it with a window -- GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id -- so state is
released when each window closes.
Refusing now rather than exhausting memory later.

$ pravaha register --name g2 (windowed, but window_start/window_end omitted from GROUP BY)
PRV-1041  PRV-2050  this GROUP BY is over a windowed stream but does not group by the window: add
window_start and window_end to the GROUP BY. Without them the aggregate spans every window at once,
which is the unbounded case wearing a window's clothes.

$ pravaha queries        (neither g1 nor g2 present)
NAME	STATE	FINGERPRINT	ROWS IN
catnull	RUNNING	c58a600296f7	3
tum	RUNNING	4eef1f8197ed	7
```

**Verdict:** both refused at registration against the live server, with the right code, the key
named, and a concrete rewrite offered. Nothing is left registered. This is the refusal the product
advertises and it is exactly right.

**Cosmetic finding:** the code is printed twice — `PRV-1041  PRV-2050`. `PRV-1041` is the transport
wrapper and `PRV-2050` is the real code. A user or a log scraper matching on the first code gets the
wrong one on every server-side refusal in this log.

### SQL-042 — PASS

```
$ pravaha query --sql "SELECT user_id, amount FROM j2"
u1 200 / u1 100 / u1 200 / u1 100 / u2 300 / u2 400 / u1 500 / u1 500 / u1 700 / u1 700   (10 rows)

$ pravaha query --sql "SELECT user_id, COUNT(*) AS c, SUM(amount) AS total, MIN(amount) AS mn, MAX(amount) AS mx FROM j2 GROUP BY user_id"
user_id	c	total	mn	mx
u1	8	3000	100	700
u2	2	700	300	400

$ pravaha query --sql "SELECT user_id, COUNT(*) AS c FROM j2 GROUP BY user_id HAVING COUNT(*) > 4"
u1	8      (1 row)
```

**Verdict:** the documented asymmetry holds — the same `GROUP BY` that is refused over a stream is
answered over a bounded view read, and answered correctly. Checked by hand against the raw view
printed above: u1's eight rows sum to 200+100+200+100+500+500+700+700 = 3000, min 100, max 700;
u2's two sum to 700. `HAVING` filters to the one qualifying group.

### SQL-043 — FAIL

```
$ /usr/bin/time pravaha run --sql "SELECT COUNT(DISTINCT n) AS d FROM txn" --in num.csv
IllegalStateException: the query did not finish within five minutes; the lane is still working or
stuck. Check its metrics: [LaneMetrics[laneId=0, rowsIn=5, rowsOut=0, batches=0, …]]
ELAPSED 300.96 s

$ pravaha query --sql "SELECT COUNT(DISTINCT user_id) AS d FROM j2"     (a bounded view read)
PRV-1041  PRV-3020  COUNT(DISTINCT ...) over an unwindowed stream is unbounded state: one entry per
distinct value, kept forever. Put it in a window.

$ pravaha run --sql "SELECT COUNT(*) AS c FROM txn HAVING COUNT(*) > 10"  -> ok 5 in, 0 out
$ pravaha run --sql "SELECT COUNT(*) AS c FROM txn HAVING COUNT(*) > 1"   -> 5
```

**Verdict:** `HAVING` **passes** — it filters the group out at `> 10` and keeps it at `> 1`, so the
empty result is a real filter rather than a broken aggregate.

`COUNT(DISTINCT x)` **FAILS**, twice over:

1. **Over a stream it hangs for five minutes** and dies with the same generic message as SQL-005.
   `GlobalAggregate` throws a `PravahaException` naming the real reason — "unbounded state … Put it
   in a window" — at **runtime**, and the exception is swallowed by the same path that swallows the
   `ArithmeticException`. This directly contradicts the contract's central promise: "A query Pravaha
   cannot run is refused when it is planned, with a `PRV-` code and an explanation. It is never
   accepted and then approximated." It is accepted, and then it stalls.
2. **Over a bounded view read it is refused**, and `SQL_SUPPORT.md` says in as many words that it is
   supported there: "`KeyedAggregate` answers it — including `SUM`, `MIN`, `MAX`, `AVG`,
   `COUNT(DISTINCT)`, multiple group columns, `HAVING`, and parameters." The refusal's text is also
   wrong for this case: it says "over an unwindowed **stream**" when the input is a finite view. And
   `PRV-3020` does not appear in the error-code table in `SQL_SUPPORT.md` or, as far as this log's
   scope goes, anywhere a user would look it up.

`SQL_SUPPORT.md` lists `COUNT(DISTINCT x)` as ✅ under Aggregation with no qualification at all.

### SQL-044 — PASS

```
$ pravaha register --name j2 (SELECT t.txn_id, t.user_id, t.amount, o.oid, o.note FROM txn t JOIN other o ON t.user_id = o.user_id) --keys 0,3
$ pravaha queries  ->  j2  RUNNING  972a9b52e48a  11
$ pravaha query --sql "SELECT * FROM j2"
txn_id	user_id	amount	oid	note
2	u1	200	1	alpha
1	u1	100	1	alpha
2	u1	200	4	delta
1	u1	100	4	delta
3	u2	300	2	beta
4	u2	400	2	beta
5	u1	500	4	delta
5	u1	500	1	alpha
7	u1	700	4	delta
7	u1	700	1	alpha
10 rows
```

**Verdict:** exactly the ten pairs computed by hand. `txn` holds four `u1` rows and two `u2` rows;
`other` holds two `u1` rows, one `u2` and one `u9`. So 4 × 2 + 2 × 1 = 10, with `u3` (only in `txn`)
and `u9` (only in `other`) contributing nothing — which also confirms this is an inner join and not
something more generous. Every pair appears once. Not vacuous by a wide margin.

### SQL-045 — PASS

```
$ pravaha register --name sj (SELECT a.user_id FROM txn a JOIN txn b ON a.user_id = b.user_id)
PRV-1041  stream 'txn' appears on both sides of this plan; self-joins are not supported yet
$ pravaha queries        (sj absent)
```

**Verdict:** refused against the live server, and the message names the actual problem. It carries
no `PRV-` code of its own — only the `PRV-1041` transport wrapper, which a user would reasonably
mistake for the real code — and it arrives at pipeline build rather than at planning. Both facts are
stated in `SQL_SUPPORT.md` as known gaps, so this is a pass against the contract rather than against
what a user deserves.

### SQL-046 — PASS

```
LEFT JOIN, no time bound -> PRV-2020  a LEFT join between streams needs a time bound in its ON
  condition. … Add a bound such as AND l.event_time BETWEEN r.event_time - INTERVAL '5' MINUTE AND
  r.event_time, which is also the point at which the null-padded row is emitted.
RIGHT JOIN               -> PRV-2020  … Swap the inputs and use LEFT.
FULL OUTER JOIN          -> PRV-2020  … Swap the inputs and use LEFT.
CROSS JOIN               -> PRV-2020  the join condition 'true' is neither an equality … nor a time
  bound … Write the equality … and move anything else into a WHERE clause.
ON t.amount > o.oid      -> PRV-2020  the join condition '>($2, $5)' is neither an equality … 
```

**Verdict:** all five refused with `PRV-2020` at registration, each with a concrete instruction.
These are the best-written refusals in the product.

### SQL-047 — BLOCKED

```
$ pravaha register --name lj (SELECT t.user_id, t.amount, d.tier FROM txn AS t
                              JOIN dim FOR SYSTEM_TIME AS OF t.event_time AS d ON t.user_id = d.user_id)
PRV-1041  PRV-2020  stream '[pravaha, dim]' is registered as a stream, not as a lookup table. A
stream is consumed and its rows are held in join state; a lookup table is asked one key at a time
and holds nothing. Register it with registerLookup to join against it this way.
```

**Verdict:** **BLOCKED, and the blockage is itself a finding.** The refusal is clear and correct, and
`registerLookup` is real — but it is a **programmatic API only**. `grep -rn "registerLookup\|lookup"
pravaha-server/src/main/java` finds one unrelated comment: the server has no `pravaha.lookups`
configuration block and no other way to declare a dimension table. So the lookup join, which
`SQL_SUPPORT.md` calls one of "the two shapes that make up nearly all" real joins and lists as ✅,
**cannot be used from a configured server at all**, and the message tells the user to call a method
they have no way to reach. The case could not be executed on its merits.

### SQL-048 — PASS, with a UX finding

```
ORDER BY id        -> PRV-2020  the planner produced a LogicalSort, which Pravaha cannot execute yet.
LIMIT 3            -> PRV-2020  the planner produced a LogicalSort, …
OFFSET 3 ROWS      -> PRV-2020  the planner produced a LogicalSort, …
UNION              -> PRV-2020  … LogicalUnion …
UNION ALL          -> PRV-2020  … LogicalUnion …
INTERSECT          -> PRV-2020  … LogicalIntersect …
EXCEPT             -> PRV-2020  … LogicalMinus …
VALUES             -> PRV-2020  … LogicalValues …
INSERT             -> PRV-2020  … LogicalTableModify …
SELECT DISTINCT s  -> PRV-2050  GROUP BY s has no bound on its key space … Bound it with a window …
```

**Verdict:** every code matches the contract, including `SELECT DISTINCT` being `PRV-2050` rather
than `PRV-2020` — which is the document's own claim and is easy to get wrong.

**UX finding, the same class as SQL-014.** The messages name Calcite's plan node rather than the SQL
the user wrote. `LIMIT 3` refused as "a LogicalSort" is actively confusing — there is no `ORDER BY`
in the query — and `EXCEPT` refused as "a LogicalMinus" makes a user search for a word they did not
type. Each message does end with the list of what *is* supported, which rescues it.

### SQL-049 — PASS

```
WHERE id IN (SELECT id FROM txn)   -> PRV-2021  cannot compile the expression 'IN($0, {
                                       LogicalProject(id=[$0]) …
WHERE EXISTS (SELECT 1 FROM txn)   -> PRV-2021  cannot compile the expression 'EXISTS({
                                       LogicalTableScan(table=[[pravaha, txn]]) …
ROW_NUMBER() OVER (ORDER BY id)    -> PRV-2021  function 'ROW_NUMBER' in 'ROW_NUMBER() OVER (ORDER
                                       BY $0)' is not supported in a projection. …
```

**Verdict:** all `PRV-2021` as documented. The first two dump the sub-plan into the message, which is
legible for a small query and is the seed of the problem in SQL-054.

### SQL-050 — PASS

```
WHERE s > 'ann'              -> PRV-2021  only = and <> are supported on text column 's'; > needs a
                                 collation, and assuming one gives wrong answers that look right
WHERE s > CAST(id AS VARCHAR)-> PRV-2021  'CAST($0):VARCHAR NOT NULL' has SQL type VARCHAR, which
                                 Pravaha cannot compute with yet
WHERE s = 1                  -> PRV-2021  'CAST($3):INTEGER' converts between STRING and INT32;
                                 Pravaha evaluates numeric conversions only
WHERE s = 'ann'              -> valid    (control)
WHERE s <> 'ann'             -> valid    (control)
```

**Verdict:** text ordering and text-versus-number are refused with the reason stated, and the two
controls confirm `=` and `<>` on text still plan — so the refusal is targeted rather than a blanket
ban on text predicates.

### SQL-051 — PASS

```
TABLE(SESSION(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
-> PRV-2020  SESSION windows exist in the runtime but are not wired to SQL yet: their state is a
   per-key interval set rather than a slice grid, so they need the keyed state store. Use TUMBLE or
   HOP.
```

**Verdict:** correct code, and the message tells the user both why and what to use instead.

### SQL-052 — PASS on the refusal, FAIL on one message

```
SELECT CAST(n AS DECIMAL(10,2)) * 1.5 FROM txn
-> PRV-2021  '*(CAST($1):DECIMAL(10, 2), 1.5:DECIMAL(2, 1))' is DECIMAL arithmetic, which Pravaha
   refuses rather than approximates. … Cast to DOUBLE explicitly if approximate is genuinely
   acceptable.

JOIN … AND t.event_time BETWEEN o.event_time - INTERVAL '1' MONTH AND o.event_time
-> PRV-1041  PRV-2020  the join condition '>=($4, -($8, 1:INTERVAL MONTH))' is neither an equality
   between one column of each side nor a time bound between them. Pravaha indexes both sides by the
   join key; a condition with no such key makes every row a candidate for every other … Write the
   equality …

# control: the same join with a day-time interval
JOIN … AND t.event_time BETWEEN o.event_time - INTERVAL '5' MINUTE AND o.event_time
-> registered dt  state=RUNNING  fingerprint=3411fd3373b2
```

**Verdict:** both are refused, which is what the contract requires, and the DECIMAL message is
excellent — it names the reason and the escape hatch.

The month-interval message is **wrong about the diagnosis**. The query *does* contain the equality
`t.user_id = o.user_id`; the problem is the month, and the message tells the user to write something
they already wrote. `ExpressionCompiler` has a good, specific message for year-month intervals ("a
month is 28 to 31 days. Use a day-time interval…") and it never fires here, because the join-condition
analyser rejects the predicate before the expression compiler sees the literal. The control proves
the difference is the interval unit and nothing else. Medium-low severity; the refusal is right and
the explanation sends the reader in the wrong direction.

### SQL-053 — PASS, with a message finding

```
$ pravaha validate --sql "" --schema … --stream txn
--sql is required. Supplied: [sql, schema, stream]
$ pravaha validate --sql "   " …
--sql is required. Supplied: [sql, schema, stream]
$ pravaha query --sql "" --url grpc://localhost:19400
--sql is required. Supplied: [sql, url]
```

**Verdict:** no crash, no hang, no stack trace, and nothing reaches the planner — which is the
substance of the case. The message contradicts itself: `--sql is required` followed by a list that
contains `sql`. `Args.require` tests `value.isBlank()` but reports `options.keySet()`, so a supplied
blank value reads as a missing one. Low severity on its own; it becomes the visible half of SQL-060.

### SQL-054 — PASS on robustness, with two findings

```
$ python3 -c "…"  ->  48925 bytes of SQL: SELECT id FROM txn WHERE n IN (1,2,…,10000)
$ time pravaha validate --sql "$(cat big.sql)" …
elapsed ms: 1542
error message size: 99202 bytes
PRV-2021  cannot compile the expression 'IN($1, {
LogicalValues(tuples=[[{ 1 }, { 2 }, { 3 }, … { 10000 }]])
…
```

**Verdict:** the robustness question passes — 1.5 s, no `OutOfMemoryError`, no hang, a clean refusal.
Two findings fall out of it:

**(a) `IN` stops working at 20 terms.** `SQL_SUPPORT.md` lists `IN (a, b, c)` as ✅, "Expanded to a
chain of equalities", with no limit:

```
IN with  3 terms -> valid
IN with  4 terms -> valid
IN with  5 terms -> valid
IN with 10 terms -> valid
IN with 15 terms -> valid
IN with 20 terms -> PRV-2021  cannot compile the expression 'IN($1, { …
IN with 21 terms -> PRV-2021
IN with 25 terms -> PRV-2021
```

Calcite converts an `IN` list at or above its `IN_SUB_QUERY_THRESHOLD` (default 20) into a
`LogicalValues` semi-join, and Pravaha then refuses it as a sub-query. A twenty-element `IN` list is
an entirely ordinary thing to write. Either the threshold should be raised in the planner
configuration or the document should state the limit.

**(b) The error message is twice the size of the query.** 48 KB of SQL produced a 99 KB refusal,
because the whole `LogicalValues` tuple list is interpolated into it. That goes to the terminal, to
the server log, and over the wire on a registration. A hostile caller sends 1 MB of `IN` list and
gets several MB of log amplification per attempt. The refused expression should be truncated.

### SQL-055 — FAIL

```
depth  100: valid  1425856 us
depth 1000: PRV-2001  null
depth 2000: PRV-2001  null
```

**Verdict:** **FAIL on the message, PASS on survival.** The process is not killed, nothing hangs, and
the exit is orderly — a `StackOverflowError` or a crash would have been far worse and neither
happened. But the refusal is the literal text `PRV-2001  null`: the underlying throwable's message
is null and it is printed as-is. `SQL_SUPPORT.md` says `PRV-2001` is a "Syntax error, with Calcite's
line and column preserved", and here there is no line, no column, and no sentence. A user gets a
code and the word "null". Recorded as FAIL because a refusal a user cannot act on is the failure
mode this whole area is about; the severity is low-medium because the input is pathological.

### SQL-056 — FAIL

```
$ pravaha validate --sql "SELECT * FROM nosuchstream" --schema … --stream txn
PRV-2002  Object 'nosuchstream' not found. Known streams: [txn]

$ pravaha query --sql "SELECT * FROM nosuchstream" --url grpc://localhost:19400
PRV-1041  PRV-2002  Object 'nosuchstream' not found. Known streams: [dt, j1, catnull, j2, tum]

$ pravaha query --sql "SELECT * FROM txn" --url grpc://localhost:19400
PRV-1041  PRV-2002  Object 'txn' not found. Known streams: [dt, j1, catnull, j2, tum]
```

**Verdict:** **FAIL, medium severity, two problems.**

**(a) `PRV-2003` is documented and not emitted.** The contract's error table defines `PRV-2003` as
"The query names a stream that is not registered". Naming a stream that is not registered produces
`PRV-2002`. `SqlErrors.UNKNOWN_STREAM` (2003) is referenced from exactly one place —
`StreamCatalog` — and not from the SQL path. Anyone writing a client that branches on `PRV-2003`
will find it never arrives.

**(b) "Known streams" names the wrong set on the server.** The list contains the registered *views*
and omits every base stream declared in configuration. The third command is the sharp end of it: a
user asks for `txn`, which the node logged at startup as `streams declared in configuration: [txn,
other, dim, nulls, resv]`, and is told `txn` is not found and given a list of things that are not
streams. That a raw stream is not readable as a view may well be correct behaviour, but the message
asserts something false about the catalog. `QUICKSTART.md` already warns that this message is
"accurate, and confusing"; on the server path it is no longer accurate.

### SQL-057 — PASS

```
SELECT nosuchcolumn FROM txn          -> PRV-2002  Column 'nosuchcolumn' not found in any table.
SELECT id FROM txn WHERE nosuchcol > 1 -> PRV-2002  Column 'nosuchcol' not found in any table.
```

**Verdict:** right code, column named, both in the SELECT list and in a predicate. The trailing
"Known streams: [txn]" is noise on a column error but harmless.

### SQL-058 — PASS, with a UX finding

```
$ pravaha validate --sql "SELECT user FROM txn" --schema "id:INT64,user:STRING,金额:INT64"
PRV-2021  function 'USER' in 'USER' is not supported in a projection. Supported: + - * / %, ABS,
FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, SUBSTRING and || .

$ pravaha validate --sql 'SELECT "user" FROM txn' …
valid     output: [user VARCHAR NOT NULL]

$ pravaha validate --sql "SELECT id FROM txn WHERE user = 'alice'" …
PRV-2021  function 'USER' in 'USER' is not supported in a projection. …
```

**Verdict:** the important property holds — unquoted `user` is **not** silently resolved to the
session user and is **not** silently resolved to the column either. It is refused, so nobody gets a
wrong answer. The quoted form works and returns the column.

The message is unhelpful for the actual mistake: a user with a column called `user` is told about a
function they did not call, with no hint that quoting the identifier is the fix. Given the team has
already been bitten by this, a special case — "`user` is a reserved word; write `\"user\"` to mean
the column" — would pay for itself.

### SQL-059 — PASS

```
$ pravaha validate --sql 'SELECT "金额" FROM txn' --schema "id:INT64,user:STRING,金额:INT64"
valid     output: [金额 INT64 NOT NULL]
$ pravaha validate --sql 'SELECT 金额 FROM txn' …
valid     output: [金额 INT64 NOT NULL]
```

**Verdict:** unicode identifiers work quoted and unquoted, and come back intact in the output schema
with no mojibake. The schema spec parser, the planner and the renderer all agree.

### SQL-060 — FAIL

```
$ pravaha validate --sql "$(printf -- '-- a comment\nSELECT id FROM txn')" …
PRV-2001  Non-query expression encountered in illegal context

# the same SQL with anything at all before the comment
$ pravaha validate --sql "$(printf '\n-- a comment\nSELECT id FROM txn')" …     -> valid
$ pravaha validate --sql "$(printf 'SELECT id\n-- middle\nFROM txn')" …         -> valid
$ pravaha validate --sql "$(printf 'SELECT id FROM txn\n-- trailing')" …        -> valid
$ pravaha validate --sql "SELECT /* inline */ id FROM txn" …                    -> valid

# the same leading-comment SQL through --sql-file, which does not go through the arg parser
$ cat cmt2.sql
-- a header comment
SELECT user_id, amount FROM txn WHERE amount > 250
$ pravaha register --name cmt --sql-file cmt2.sql --keys 0
registered cmt  state=RUNNING  fingerprint=ac261b9985b1
$ pravaha register --name cmt3 --sql-file cmt3.sql --keys 0    (same SQL, comment removed)
registered cmt  state=RUNNING  fingerprint=ac261b9985b1        (identical fingerprint)

# multiple statements
$ pravaha validate --sql "SELECT id FROM txn;" …               -> PRV-2001  Encountered ";" at line 1, column 19.
$ pravaha validate --sql "SELECT id FROM txn; SELECT n FROM txn" -> PRV-2001  Encountered ";" at line 1, column 19.
$ pravaha validate --sql "SELECT id FROM txn; DROP TABLE txn"  -> PRV-2001  Encountered ";" at line 1, column 19.
$ pravaha validate --sql "SELECT id FROM txn -- ; DROP TABLE txn" -> valid   (second statement is inside the comment)
```

**Verdict:** the multi-statement half **passes** and passes well: a `;`-separated pair is refused
with a line and column, a trailing `;` likewise, and a statement hidden behind a `--` comment is
correctly ignored rather than executed.

The comment half **FAILS, medium severity**, and the cause is in the CLI's argument parser rather
than the planner. `Args.parse` treats *any* argument beginning with `--` as a flag:

```java
} else if (i + 1 < arguments.size() && !arguments.get(i + 1).startsWith("--")) {
    args.options.put(body, arguments.get(++i));
} else {
    // A bare --flag is a boolean.
    args.options.put(body, "true");
}
```

So `--sql "-- a comment\nSELECT id FROM txn"` sets `sql` to the **string `"true"`**, files the user's
SQL as a second flag named after itself, and the planner then parses the literal text `true` — which
is exactly the error seen, "Non-query expression encountered in illegal context". The user's query is
never looked at, and nothing says so. The `--sql-file` path is unaffected, and the identical
fingerprints above prove the planner strips a leading comment correctly.

This is the same root cause as the contradictory message in SQL-053. Any `--sql` value beginning with
`--` is silently replaced. It is the only defect found in this area where the engine is asked one
question and answers a different one without saying so.

### SQL-061 — FAIL

```
# QUICKSTART.md section 2, typed verbatim
$ cd examples/01-filter-and-project
$ pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
              --schema "user_id:STRING,amount:INT64" \
              --stream txn --in transactions.csv --out out.csv
--out-schema is required. Supplied: [sql, schema, stream, in, out]

# corrected: --out-schema added, and the schema matched to the file's four columns
$ pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
    --schema "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING" \
    --stream txn --in transactions.csv --out out.csv --out-schema "user_id:STRING,amount:INT64"
ok  6 in, 4 out
alice,500
carol,900
dave,150
frank,1200
```

**Verdict:** **FAIL, medium severity — the first runnable command in the documentation does not run,
and its printed answer is wrong.** Three separate errors in one eight-line example:

1. `--out-schema` is required by the CLI and is missing from the documented command, so it exits
   before doing anything.
2. The documented `--schema "user_id:STRING,amount:INT64"` does not describe `transactions.csv`,
   which is `txn_id,user_id,amount,status`.
3. The documented output is three lines — `alice,500`, `dave,150`, `frank,1200`. The correct answer
   to `WHERE amount > 100` over that file is **four** rows: `carol,900` is missing from the document.
   Carol's row is `PENDING`, so the printed output looks like it came from a query that also filtered
   on status.

A reader who runs this, fixes the two argument problems themselves, and then sees an extra row has
no way to tell whether the engine or the document is wrong. `SQL_SUPPORT.md`'s examples are checked
by a test; `QUICKSTART.md`'s are not.

### SQL-062 — FAIL (documentation accuracy)

```
$ sed -n '274,330p' pravaha-sql/src/test/java/…/SqlSupportMatrixTest.java
  void theSupportMatrixIsWhatTheDocumentationSaysItIs() {
      … String actual = outcomeOf(testCase);        // "OK", or the first 8 chars of the message
        if (!actual.equals(testCase.expected())) { … }

  private static String outcomeOf(Case testCase) {
      String message = messageOf(testCase);
      if (message == null) return "OK";
      return message.startsWith("PRV-") ? message.substring(0, 8) : message;
  }

  /** The failure message, or null if the statement planned, built and compiled into a runnable
      pipeline. */
```

**Verdict:** recorded as a finding about the document rather than about the engine, and marked FAIL
because the document's claim is false as written.

`SQL_SUPPORT.md` opens with: *"Every construct below is **checked by a test**, not by someone's
memory … plans each statement in this page, builds it, **and compiles it into a runnable
pipeline**."* All three of those steps are real and the third is genuinely valuable — it is what
catches the self-join. What the matrix never does is **run a row or compare a value**. `outcomeOf`
reduces every case to "OK" or an eight-character error code.

Consequently none of the answer defects in this log would fail the build: `ROUND(2.5) = 2`,
`COUNT(<column>)` counting nulls, every floating-point aggregate returning nothing, `ABS` of
`Long.MIN_VALUE` staying negative, and `TUMBLE` emitting no rows at all. All five constructs are
listed as ✅ and all five plan, build and compile perfectly.

The document should say what the matrix checks — that each construct plans and compiles, and that
each refusal carries its code — and stop implying the answers are covered. The stronger fix is a
second matrix that asserts values.

### SQL-063 — PASS

```
SELECT CASE WHEN n > 5 THEN s ELSE n END AS x FROM txn
-> PRV-2021  'CAST($1):VARCHAR' has SQL type VARCHAR, which Pravaha cannot compute with yet
SELECT CASE WHEN n > 5 THEN s ELSE d END AS x FROM txn
-> PRV-2021  'CAST($2):VARCHAR' has SQL type VARCHAR, which Pravaha cannot compute with yet
```

**Verdict:** the bare `IllegalArgumentException` in `Expression.Case`'s compact constructor ("a CASE
must produce one type…") is not reachable this way — Calcite coerces both branches to VARCHAR and
Pravaha refuses the cast first, with a code. No stack trace escapes. The message is about the cast
rather than about the CASE, which is a mild loss of context but not a defect.

### SQL-064 — PASS

```
$ pravaha query --sql "SELECT user_id, amount FROM j2 WHERE user_id = ?" --params u2
user_id	amount
u2	300
u2	400
2 rows

$ pravaha query --sql "SELECT user_id FROM j2 WHERE user_id = ?"                    (no --params)
PRV-1041  PRV-2060  this statement uses ?1 but only 0 values were bound. Placeholders are positional
and every one must be given a value, including the ones bound to NULL

$ pravaha query --sql "SELECT user_id FROM j2 WHERE user_id = ?" --params "u2' OR '1'='1"
0 rows

$ pravaha query --sql "SELECT ? FROM j2" --params 1
PRV-1041  PRV-2002  Illegal use of dynamic parameter.
```

**Verdict:** the bound value selects exactly the two `u2` rows the view holds (amounts 300 and 400,
confirmed against the full listing in SQL-044), so the parameter is really being applied rather than
ignored — an ignored parameter would have returned all ten rows. A missing value is `PRV-2060` as
ADR-032 specifies. The injection string returns nothing, because it is compared as a value and no
user is called `u2' OR '1'='1`; it is never parsed. A `?` outside `WHERE`/`HAVING` is refused.

### SQL-065 — FAIL

```
$ pravaha run --sql "SELECT id, n % 3 AS m FROM txn" … --out-schema 'id:INT64,m:INT64?'
ok  5 in, 5 out
1,1
2,0
3,4294967295      <- -7 % 3
4,
5,0

$ pravaha run --sql "SELECT id, n % 3 AS m FROM txn" … --out-schema 'id:INT64,m:INT32?'
ok  5 in, 5 out
3,-1              <- the same expression, the correct answer

$ pravaha validate --sql "SELECT n % 3 FROM txn" …
valid     output: [EXPR$0 INT32]
```

**Verdict:** **FAIL, medium severity.** `pravaha run` takes the row layout from the plan and the sink
encoding from the user's `--out-schema`, and never checks that the two agree. Where they disagree the
sink reads a field at the wrong width and writes **a different number from the one the engine
computed**, under a green `ok`. Here a 32-bit `-1` read as 64 bits becomes `4294967295`.

The trap is well hidden, because `--out-schema` is a required argument the user must guess at and
the natural guess is wrong: `n` is `INT64`, so `n % 3` looks like an `INT64`, and Calcite types
`MOD` as the divisor's type — `INTEGER`. `validate` prints the true output schema and is the only
way to find out. The fix is to compare `plan.outputSchema()` against the parsed `--out-schema` and
refuse a mismatch, which costs one check at startup and removes a class of silently wrong output.

This is also a caution for the rest of this log: every `run`-based case above had its `--out-schema`
checked against `validate`'s reported output types where the type was not obvious.

---

## Summary

| Verdict | Count |
|---|---|
| PASS | 48 |
| FAIL | 15 |
| BLOCKED | 2 |
| NOT RUN | 0 |
| **Total** | **65** |

PASS: 001–004, 009, 011, 013–036, 038, 041, 042, 044–046, 048–054, 057–059,
063, 064. FAIL: 005, 006, 007, 008, 010, 012, 037, 039, 043, 055, 056, 060, 061, 062, 065.
BLOCKED: 040, 047.

Four of the PASSes carry a defect inside them that is not severe enough to overturn the verdict —
SQL-014, SQL-048, SQL-052 and SQL-054 — and each is listed under *Smaller findings* or in the FAIL
table's notes below.

### The FAILs, worst first


| | Case | What is wrong | Severity |
|---|---|---|---|
| 1 | SQL-039 | `TUMBLE` (and HOP, SQL-040) registers, ingests and **emits nothing, forever, silently**. No stream declared through a `name:TYPE` schema spec ever has an event-time column, so no watermark advances and no window closes. Unreachable from the CLI and from server configuration alike; `QUICKSTART.md` §4–6 cannot work | **Blocker** |
| 2 | SQL-037 | Every aggregate over a **floating-point** column is broken: `SUM`/`AVG`/`MIN`/`MAX` return **no rows** on the streaming path under a green `ok`, and fail with a raw internal type error on the view path | **High** |
| 3 | SQL-007, SQL-037 | `COUNT(<column>)` counts NULLs — it is identical to `COUNT(*)`. A wrong number, silently, from the commonest aggregate | **High** |
| 4 | SQL-012 | `ROUND` is banker's rounding (`Math.rint`), not SQL's round-half-away-from-zero. `ROUND(2.5) = 2`, `ROUND(-2.5) = -2` | **High** |
| 5 | SQL-005 | Integer division by zero **hangs for five minutes**, loses **every row in the batch** (not just the offending one), and reports a generic "did not finish" with no mention of division | **High** |
| 6 | SQL-043 | `COUNT(DISTINCT x)` — documented ✅ — hangs for five minutes over a stream (refused at runtime, exception swallowed) and is **refused over a view** where the document explicitly says it is supported. Error code `PRV-3020` is undocumented | **High** |
| 7 | SQL-006 | Integer overflow drops the row **silently under `ok`**, or hangs for five minutes — non-deterministically, same command, same input | **High** |
| 8 | SQL-062 | `SQL_SUPPORT.md` claims every construct is "checked by a test"; `SqlSupportMatrixTest` never runs a row or compares a value, so none of items 1–7 would fail the build | **High** (doc) |
| 9 | SQL-060 | Any `--sql` value beginning with `--` is silently replaced by the string `true` and planned as that. A `.sql` file's leading comment pasted on the command line is never looked at | **Medium** |
| 10 | SQL-061 | `QUICKSTART.md` §2 — the documentation's first runnable command — fails as written (missing `--out-schema`), has the wrong `--schema`, and prints an answer missing one of the four correct rows | **Medium** |
| 11 | SQL-065 | `--out-schema` disagreeing with the plan's real output type is not checked; the file gets a different number from the one computed, under `ok` | **Medium** |
| 12 | SQL-008 | `SELECT -n` is refused, and the refusal's own text claims unary minus is supported | **Medium** |
| 13 | SQL-010 | `ABS(Long.MIN_VALUE)` returns a negative absolute value, silently — inconsistent with the `addExact` policy one method away | **Medium** |
| 14 | SQL-056 | `PRV-2003` is documented for an unknown stream and never emitted (it is `PRV-2002`); on the server, "Known streams" lists views and omits every configured base stream, so asking for a declared stream is told it does not exist | **Medium** |
| 15 | SQL-055 | A 1 000-deep parenthesised predicate is refused as the literal text `PRV-2001  null` — a code and no sentence | **Low-Med** |

Two further defects were found inside cases whose stated Expected was met, so they are carried here
rather than in the table above: **SQL-054** — `IN` lists of 20 or more terms are refused, though
`SQL_SUPPORT.md` documents `IN` with no limit, and the refusal interpolates the whole value list, so
48 KB of SQL produced a 99 KB error message (**Low-Med**); **SQL-052** — the month-interval join
refusal misdiagnoses the query, telling the user to add an equality it already has (**Low-Med**).

### The BLOCKED

| Case | Why |
|---|---|
| SQL-040 | HOP cannot be checked for window overlap while no window ever closes (SQL-039) |
| SQL-047 | A lookup join needs `registerLookup`, which is a programmatic API; the server has no configuration for declaring a lookup table, so the documented ✅ cannot be reached from a configured node at all |

### Smaller findings recorded inside passing cases

* **SQL-014, SQL-048** — refusals name Calcite's rewrite rather than the user's SQL: `SQRT` refused as
  `POWER`, `LIMIT 3` as `LogicalSort`, `EXCEPT` as `LogicalMinus`. **On the brief's question: this is
  acceptable, barely.** Every one of these messages ends with the list of what *is* supported, which
  is what actually gets the user unstuck, so nobody is left without a route forward. It is still
  worth fixing, because the user searches their own query for a word that is not in it. The cheap fix
  is one clause: "…`POWER`, which is how the planner rewrote `SQRT`".
* **SQL-041 and every server-side refusal** — the code is printed twice, `PRV-1041  PRV-2050`. The
  first is the transport wrapper; anything matching the first code matches the wrong one.
* **SQL-045** — the self-join refusal carries no `PRV-` code of its own, only the `PRV-1041` wrapper.
  Documented as a known gap.
* **SQL-052** — the month-interval refusal misdiagnoses: it says the join has no equality when it has
  one, and never reaches the good year-month message that exists in `ExpressionCompiler`.
* **SQL-028** — `IS NULL` only accepts a bare column; `(a || b) IS NULL` is `PRV-2021`.
  `SQL_SUPPORT.md` lists `IS NULL` as ✅ with no caveat.
* **SQL-014** — `MOD(n, 3)` works and is documented as refused.
* **SQL-053** — `--sql is required. Supplied: [sql, …]`, a message that contradicts itself.
* **SQL-058** — an unquoted reserved word is refused (good) with a message about a function the user
  did not call (bad).
* **SQL-028** — the filesystem source cannot represent an empty string: `null.literal` defaults to
  the empty string and the CLI does not expose it, so `''` and NULL are the same three bytes.

### What could not be covered, and why

**Window correctness, entirely.** SQL-039 and SQL-040 mean no window ever produced a row, so nothing
downstream of window assignment could be checked at all: late-data correction, watermark idleness,
whether a row lands in exactly one TUMBLE window or two HOP windows, whether `window_start` and
`window_end` are right, whether a closed window is emitted once. The runtime has its own tests for
these (`WindowedOnLanesTest`, `LateDataTest`) that build a schema in Java with `.eventTime(...)`, so
the operators are probably fine — but *probably* is the word, and nothing in this log tests them
through the product.

**Floating-point aggregate answers.** Blocked by SQL-037: the values cannot be wrong or right when
there is no output. Whether the accumulate-as-`long` path would produce a *wrong number* rather than
no number on the keyed path is a code reading (`KeyedAggregate` writes back type-aware while reading
as `long`), not an observation, and is flagged as such.

**`COUNT(DISTINCT)` answers**, for the same reason as above.

**The lookup join**, for the reason in SQL-047 — no configuration surface exists to declare a lookup
table, so the operator could not be reached.

**`SqlSupportMatrixTest` was read, not run.** The brief forbids a full build and another agent is
working in the same tree, so whether the matrix is currently green was not verified — only what it
asserts when it runs.

**Concurrency and restart** are outside this area and were not attempted: no two clients were run
against one view simultaneously, and the server was restarted only to change its stream
configuration, not as a test.

**`TIME`, `DATE`, `VARBINARY`, `TINYINT` and `SMALLINT` expressions** were not exercised. The type
table lists them and the schema spec accepts `INT8`/`INT16`/`BYTES`, but every case here used
`INT64`, `FLOAT64`, `STRING`, `BOOLEAN` and `TIMESTAMP`. Given what turned up in `FLOAT64`, the
narrower integer types deserve the same treatment.

**Codegen.** Everything above ran on the interpreted pipeline. `explain --level codegen` exists and
the design says the generated path is asserted against the interpreted one by differential tests;
none of that was checked here, so every verdict is a verdict about the interpreted operators.
