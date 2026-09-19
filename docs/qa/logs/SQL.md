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

---

## Re-QA 2026-09-12

Re-run after the remediation commit `7e0de33` ("Remediate what QA found: 18 defects, 4 of them
silent-wrong"), against the rebuilt artefacts. Server on HTTP **18400** / Flight **19400**, PID
recorded and killed by number; no `pkill`. Streams `w`, `ws`, `wni`, `lt`, `lt2`, `wn`, `agg`, `nt`
declared, all bound to the filesystem plugin; `w`, `ws`, `lt`, `lt2`, `wn` carry
`event-time: et` and `out-of-orderness: 1s`. `pravaha.watermark.idle-after: 2s`, `tick: 200ms`.

**The tree moved while this pass ran, and that qualifies every verdict below.** The remediation is
still in progress: at the time of writing the working tree carries uncommitted changes to
`QueryRunner`, `QueryExecution`, `PravahaNode`, `QueryRegistry`, `ViewCatalog` and `StreamSchema`,
and the CLI artefact was rebuilt underneath this pass at 22:03. Where a verdict depends on which
build produced it, the section says so. Every server-side result below came from the application jar
as delivered; the CLI results split across the rebuild and the affected case (SQL-005) is re-run and
re-timed against the current build.


**Windowing data, and why the numbers below are checkable by hand.** Stream `w` is nine rows at
`T0 = 1757700000000000000` ns, a multiple of both 5 s and 10 s, so every window boundary is a round
number:

```
id  k  amt  event time
 1  a    1  T0+0s      4  a    4  T0+9s      7  a  200  T0+19s
 2  a    2  T0+3s      5  a  100  T0+11s     8  a    7  T0+25s
 3  b   10  T0+5s      6  b   50  T0+12s     9  z    0  T0+95s
```

The file is written with its **lines shuffled** (4, 2, 7, 1, 6, 3, 5, 8, 9) so that correct output
also proves the engine buckets by event time and not by arrival. `ws` is the identical data in
timestamp order, as the control. Row 9 exists only to push the watermark to T0+94 s so that the
windows ending at +10, +20 and +30 close; its own window `[T0+90, T0+100)` must **not** close, which
is what stops an "everything fired" result from passing vacuously.

Hand-computed TUMBLE(10 s) answer: `[0,10) a` = 3 rows, 1+2+4 = **7**; `[0,10) b` = 1, **10**;
`[10,20) a` = 2, 100+200 = **300**; `[10,20) b` = 1, **50**; `[20,30) a` = 1, **7**. Five rows.

---

### SQL-039 — PARTIALLY FIXED

The server path, with an event-time column declared, now works and the answers are right.

```
$ pravaha register --name tum --sql-file tumble.sql --keys 0,1,2 --url grpc://localhost:19400
registered tum  state=RUNNING  fingerprint=a1488f03f6d8
$ pravaha queries
tum	RUNNING	a1488f03f6d8	9
$ pravaha query --sql "SELECT * FROM tum"
window_start	window_end	k	c	total
1757700000000000000	1757700010000000000	b	1	10
1757700000000000000	1757700010000000000	a	3	7
1757700010000000000	1757700020000000000	b	1	50
1757700010000000000	1757700020000000000	a	2	300
1757700020000000000	1757700030000000000	a	1	7
5 rows
```

Every value matches the hand computation above. The `[T0+90, T0+100)` window is absent, as it must
be. The identical query over `ws` (the same rows in timestamp order) returns byte-identical output,
so window assignment is by event time, not arrival order — the out-of-order case passes.

The `GROUP BY TUMBLE(...)` form documented in `QUICKSTART.md` §4 also works and agrees:

```
$ pravaha query --sql "SELECT * FROM gbw"     (SELECT STREAM ... GROUP BY TUMBLE(et, INTERVAL '10' SECOND), k)
window_end	k	c	total
1757700010000000000	b	1	10
1757700010000000000	a	3	7
1757700020000000000	b	1	50
1757700020000000000	a	2	300
1757700030000000000	a	1	7
```

**What is still broken.** Three of the four ways a user reaches this feature still emit nothing:

1. **`pravaha run` is unchanged.** The CLI has no way to name an event-time column — the `name:TYPE`
   schema spec still has no syntax for it, there is no `--event-time` flag, and `QueryRunner` still
   never calls `generatingWatermarks`. Both were named as causes in the original SQL-039 and only
   the server half was addressed:

   ```
   $ pravaha run --sql "SELECT window_start, window_end, k, COUNT(*) AS c, SUM(amt) AS total
                        FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(et), INTERVAL '10' SECOND))
                        GROUP BY window_start, window_end, k" \
                 --schema 'id:INT64,k:STRING,amt:INT64,et:TIMESTAMP' --in wsorted.csv ...
   ok  9 in, 0 out
   --- out.csv --- (empty)
   ```

   Exit 0, `ok`, no output, no warning. The original defect, intact, on the path the documentation
   opens with.

2. **A stream with no `event-time:` line behaves exactly as before the fix.** See SQL-071.

3. **Only the filesystem plugin honours the new option.** See SQL-076.

And the server path that does work breaks above roughly 210 000 rows — see **SQL-066**, which is a
blocker in its own right.

### SQL-040 — VERIFIED FIXED (was BLOCKED)

```
$ pravaha query --sql "SELECT * FROM hp"    (HOP, 5 s slide, 10 s size, over w)
window_start	window_end	k	c	total
1757699995000000000	1757700005000000000	a	2	3
1757700000000000000	1757700010000000000	b	1	10
1757700000000000000	1757700010000000000	a	3	7
1757700005000000000	1757700015000000000	b	2	60
1757700005000000000	1757700015000000000	a	2	104
1757700010000000000	1757700020000000000	b	1	50
1757700010000000000	1757700020000000000	a	2	300
1757700015000000000	1757700025000000000	a	1	200
1757700020000000000	1757700030000000000	a	1	7
1757700025000000000	1757700035000000000	a	1	7
10 rows
```

Hand-checked against the property this case exists for — **each row lands in exactly two windows**.
Row `a@+9s (amt 4)` is in `[0,10)` and `[5,15)` and in no others; `[5,15) a` = 4 + 100 = **104**,
which is the arithmetic that proves the overlap rather than merely asserting it. `b@+5 (10)` and
`b@+12 (50)` share `[5,15)` = **60** and appear singly in `[0,10)` and `[10,20)`. Every one of the
ten rows was computed independently before the query was run and every one matches.

### SQL-037 — VERIFIED FIXED for floating point, and the same hole is open one type away

```
$ pravaha query --sql "SELECT SUM(d)  AS v FROM av"   (d is FLOAT64)
PRV-1041  PRV-2020  SUM(d) is over a FLOAT64 column, and this engine's aggregates accumulate in
64-bit integers only. It is refused rather than answered, because the alternative was no rows and a
successful status. Cast the column to an integer if the rounding is acceptable --
SUM(CAST(price AS BIGINT)) -- or aggregate it outside the engine.
```

Identical refusals for `AVG(d)`, `MIN(d)`, `MAX(d)`, and for `SUM/AVG/MIN/MAX(f)` where `f` is
FLOAT32 — eight of eight. It fires on every path that reaches an aggregate:

```
over a view                SELECT SUM(d) FROM av                     refused
keyed over a view          SELECT g, SUM(d) FROM av GROUP BY g       refused
in HAVING                  ... GROUP BY g HAVING SUM(d) > 0          refused
registered/streaming       register TUMBLE ... SUM(d) over wn        refused at registration
```

`COUNT` is exempt and still answers, and the count is now right:

```
$ pravaha query --sql "SELECT COUNT(d) AS cd, COUNT(f) AS cf, COUNT(*) AS t FROM av"
cd	cf	t
4	4	5          (one NULL in each float column, five rows)
```

Integer aggregates are unaffected and every value is correct:

```
$ pravaha query --sql "SELECT SUM(n) AS s, AVG(n) AS a, MIN(n) AS mn, MAX(n) AS mx, COUNT(n) AS c FROM av"
s	a	mn	mx	c
28	7	-7	20	4      (n = 10, NULL, -7, 20, 5: sum 28, avg 28/4 = 7)
```

**Adversarial probes — the refusal holds.** Eleven attempts to get a floating-point aggregate past
it, all refused or otherwise stopped: `SUM(d * 2)`, `SUM(d + 0)`, `SUM(CAST(n AS DOUBLE))`,
`AVG(CAST(n AS DOUBLE))`, `MIN(ABS(d))`, `SUM(ROUND(d))`, `HAVING SUM(d) > 0`, `SUM(n / 2.0)`
(caught earlier by the DECIMAL refusal), `SUM(d)` in a window. The guard reads the aggregate's
**input schema**, so an expression pushed into the projection below is still a FLOAT64 column when
the check runs, which is why none of the rewrites get through.

The workaround the message recommends is real and works, which is what makes the message
actionable rather than merely polite:

```
$ pravaha query --sql "SELECT SUM(CAST(d AS BIGINT)) AS v FROM av"
v
3          (2.5, 3.5, -2.5, NULL, 0.0 truncated toward zero: 2 + 3 - 2 + 0)
```

**Two things the fix did not cover.**

* When the argument is an expression the message names Calcite's synthetic field —
  `SUM($f0) is over a FLOAT64 column` — not the user's `SUM(d * 2)`. Same family as SQL-014/048;
  low severity, and the rest of the sentence still gets the user unstuck.
* **`INT8`, `INT16` and `INT32` were left out, and for those the original SQL-037 symptom is
  unchanged.** See **SQL-070**. The guard tests for `FLOAT32` and `FLOAT64` only.

### SQL-007 — PARTIALLY FIXED: one of the three aggregate operators

`GlobalAggregate` is fixed, on both the file path and the server path:

```
$ pravaha run --sql "SELECT SUM(n) AS s, COUNT(n) AS c, COUNT(*) AS t, MIN(n) AS mn, MAX(n) AS mx, AVG(n) AS a FROM txn" --in num.csv
ok  5 in, 1 out
9007199254740996,4,5,-7,9007199254740993,2251799813685249
                 ^ COUNT(n) is 4 where it was 5; COUNT(*) is still 5
$ pravaha query --sql "SELECT COUNT(*) AS t, COUNT(n) AS cn, COUNT(s) AS cs, SUM(n) AS sn FROM av"
t	cn	cs	sn
5	4	4	28
```

Both columns have exactly one NULL in five rows, `COUNT(*)` counts all five, `SUM` is unchanged and
correct. Not vacuous: the two counts now differ, which is precisely what they could not do before.

**`KeyedAggregate` and `WindowedAggregate` have the same bug and were not touched.** Logged as
**SQL-067** and **SQL-068**. The remediation commit's own message says "the SUM and MIN/MAX branches
beside it had the check all along" — that is true of all three operators, and the check was added
to one of them.

### SQL-012 — PARTIALLY FIXED: the reported cases are right, two new wrong answers arrived with the fix

```
$ pravaha run --sql "SELECT id, d, ROUND(d) AS r FROM txn" --in round.csv
ok  12 in, 12 out
1,2.5,3.0                    <- was 2.0
2,-2.5,-3.0                  <- was -2.0
3,0.5,1.0
4,-0.5,-1.0
5,2.4,2.0
6,3.5,4.0
7,-3.5,-4.0
8,-0.4,-0.0
12,1.5,2.0
```

Every case named in the brief is correct, and integer `ROUND` is untouched and still exact above
2^53:

```
$ pravaha run --sql "SELECT FLOOR(n) AS f, CEIL(n) AS c, ROUND(n) AS r FROM txn WHERE id = 5" --in num.csv
9007199254740993,9007199254740993,9007199254740993
```

**But the new implementation, `Math.signum(v) * Math.floor(Math.abs(v) + 0.5)`, is wrong on two
inputs that `Math.rint` got right.** Both are in the same output above:

```
9,0.49999999999999994,1.0        <- correct answer: 0
10,4.503599627370497E15,4.503599627370498E15   <- correct answer: itself
```

`0.49999999999999994` is the largest double **below** one half; it must round to 0. Adding 0.5 to it
produces exactly `1.0` in binary, and the floor of that is 1. This is the textbook `Math.round`
defect the JDK itself fixed in Java 7. `4503599627370497.0` is 2^52 + 1, an exact whole number;
`ROUND` of a whole number is itself. Adding 0.5 lands exactly halfway between two representable
doubles, ties-to-even rounds it up, and `ROUND` now **changes an integer value**. Every double at or
above 2^52 with an odd mantissa is affected.

Logged as **SQL-072**. The verdict on the fix: it corrects the reported symptom and reproduces its
shape — an off-by-one in a plausible-looking number that nobody checks — on a different set of
inputs. `BigDecimal.setScale(0, RoundingMode.HALF_UP)` has neither problem, and was named as the
alternative in the original report.

### SQL-010 — VERIFIED FIXED, and the failure is visible

```
$ pravaha run --sql "SELECT ABS(n) AS a FROM txn" --in min.csv   (n = -9223372036854775808)
PRV-3010  lane 0 stopped after a failure: java.lang.ArithmeticException: ABS(-9223372036854775808)
has no representable result: the range of a 64-bit integer is asymmetric, so the magnitude of its
smallest value is one larger than its largest.
  https://docs.pravaha.io/errors/PRV-3010
EXIT=1                            ELAPSED 4.80 s
```

On the brief's question — what the user actually sees: **a clear error**. A `PRV-` code with a
documentation link, the real exception message naming the value and the reason, exit 1, in under
five seconds. Nothing is swallowed and nothing hangs. `ABS` of ordinary values is unchanged
(SQL-009 re-run below).

### SQL-005, SQL-006, SQL-043 (stream half) — FIXED, by a change that is not in the commit under test

All three produced a five-minute stall in the original run. All three now fail in about a second
with the real reason:

```
$ pravaha run --sql "SELECT id, 100 / n AS q FROM txn" --in num.csv        (one row has n = 0)
PRV-3010  lane 0 stopped after a failure: java.lang.ArithmeticException: division by zero in a
projection; the record is routed to the DLQ rather than given a value that could be mistaken for an
answer
exit=1   ELAPSED 1.04 s      (three consecutive runs: 1.04, 1.03, 1.03 s, exit 1, 0 rows out)

$ pravaha run --sql "SELECT n + 1 AS a FROM txn" --in max.csv              (n = Long.MAX_VALUE)
PRV-3010  lane 0 stopped after a failure: java.lang.ArithmeticException: long overflow
exit=1

$ pravaha run --sql "SELECT COUNT(DISTINCT n) AS d FROM txn" --in num.csv
PRV-3010  lane 0 stopped after a failure: com.ash.messaging.pravaha.api.PravahaException:
PRV-3020  COUNT(DISTINCT ...) over an unwindowed stream is unbounded state: one entry per distinct
value, kept forever. Put it in a window.
exit=1
```

Five further runs of each of the first two, to check the non-determinism SQL-006 reported: ten of
ten failed loudly, none produced the `ok  1 in, 0 out` / exit 0 outcome, none took anywhere near
five minutes.

**Where the fix is matters, and it is not in `7e0de33`.** That commit touches two files in the whole
runtime and CLI:

```
$ git show --name-only --format="" 7e0de33 | grep -E "pravaha-runtime|pravaha-cli"
pravaha-runtime/src/main/java/com/ash/messaging/pravaha/runtime/exec/GlobalAggregate.java
pravaha-runtime/src/main/java/com/ash/messaging/pravaha/runtime/plan/Expression.java
```

The change that actually does this is **uncommitted in the working tree** as this log is written —
`QueryExecution.awaitQuiescent` now polls in fifty-millisecond slices and calls `checkHealth()`
inside the loop instead of waiting out the whole timeout, and `QueryRunner` calls `checkHealth()`
after `close()`. Its own comment names the defect exactly: "the real cause, an
`ArithmeticException` from a division by zero or an overflow, sat in the lane's `failure` field the
whole time, unread."

So: the diagnosis in SQL-005 was right, the fix is right, and the fix is in flight rather than in the
release under test. Verified against the build of 22:03, which carries it.

**What the fix does not address, and what therefore still stands:**

* **Every row in the batch is still lost**, not just the offending one. Rows 1, 3 and 5 of
  `num.csv` have perfectly good answers and `out.csv` is empty.
* **The message still says "the record is routed to the DLQ"** and no record is routed anywhere. The
  lane stops, the run dies, nothing is quarantined. The sentence describes a design, not what
  happens.
* The contract's promise — "A query Pravaha cannot run is refused when it is planned … It is never
  accepted and then approximated" — is still not kept for `COUNT(DISTINCT)`: it plans, it registers,
  it runs, and then it throws. It now throws quickly and legibly, which is a large improvement and
  not the thing that was promised.

### SQL-043 (view half) — STILL FAILING, and a new error alongside it

```
$ pravaha query --sql "SELECT COUNT(DISTINCT g) AS d FROM av"
PRV-1041  PRV-3020  COUNT(DISTINCT ...) over an unwindowed stream is unbounded state: one entry per
distinct value, kept forever. Put it in a window.
```

`av` is a view and the read is bounded, exactly the shape `SQL_SUPPORT.md` says is supported
("`KeyedAggregate` answers it — including … `COUNT(DISTINCT)`"). Unchanged, message still wrong about
what the input is. The keyed form now fails differently — see **SQL-078** — and the windowed form,
which the refusal above tells the user to reach for, returns wrong numbers — see **SQL-069**.

### SQL-008 — STILL FAILING

```
$ pravaha validate --sql "SELECT id, -n AS neg FROM txn"
PRV-2021  '-($1)' has 1 operands; only the two-operand form is supported (unary minus included,
which Calcite normalises to 0 - x).
$ pravaha validate --sql "SELECT id, 0 - n AS neg FROM txn"        -> valid
```

Unchanged, including the refusal's claim that the thing it is refusing is supported.

### SQL-055 — STILL FAILING

```
depth  100: valid
depth 1000: PRV-2001  null
depth 2000: PRV-2001  null
```

### SQL-056 — STILL FAILING, both halves

```
$ pravaha validate --sql "SELECT * FROM nosuchstream" --stream txn
PRV-2002  Object 'nosuchstream' not found. Known streams: [txn]          (PRV-2003 still unused)

$ pravaha query --sql "SELECT * FROM w"                                  (w IS a declared stream)
PRV-1041  PRV-2002  Object 'w' not found. Known streams: [wnw, av, lwc, lw]
$ pravaha query --sql "SELECT * FROM agg"
PRV-1041  PRV-2002  Object 'agg' not found. Known streams: [wnw, av, lwc, lw]
```

The node logged `streams declared in configuration: [w, ws, wni, lt, lt2, wn, agg, nt, fw]` at
startup. "Known streams" still names the registered views and omits every one of them.

### SQL-060 — STILL FAILING

```
$ pravaha validate --sql "$(printf -- '-- a comment\nSELECT id FROM txn')"
PRV-2001  Non-query expression encountered in illegal context
$ pravaha validate --sql "$(printf 'SELECT id\n-- middle\nFROM txn')"      -> valid
```

Any `--sql` value beginning with `--` is still replaced by the string `true` and planned as that.

### SQL-061 — STILL FAILING

```
$ cd examples/01-filter-and-project
$ pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
              --schema "user_id:STRING,amount:INT64" --stream txn --in transactions.csv --out out.csv
--out-schema is required. Supplied: [sql, schema, stream, in, out]
```

All three errors stand: the missing `--out-schema`, the `--schema` that does not describe
`transactions.csv` (`txn_id,user_id,amount,status`), and the documented three-line output that
omits `carol,900`.

### SQL-062 — STILL FAILING

`SqlSupportMatrixTest.java` is not in `7e0de33`'s file list and `outcomeOf` is unchanged: every case
still reduces to `"OK"` or an eight-character code, and no value is ever compared. Of the defects
this pass found, `ROUND(0.49999999999999994) = 1`, `COUNT(col)` counting NULLs in two of three
operators, windowed `COUNT(DISTINCT)` over a string returning 1, and INT32 arithmetic wrapping
would all still plan, build and compile perfectly, and none would fail the build.

### SQL-065 — STILL FAILING

```
$ pravaha run --sql "SELECT id, n % 3 AS m FROM txn" --out-schema 'id:INT64,m:INT64?'
ok  5 in, 5 out
3,4294967295        <- -7 % 3, read at the wrong width, under a green ok
```

### SQL-047 — STILL BLOCKED

`grep -rn "lookups\|registerLookup" pravaha-server/src/main/java/` returns nothing. There is still
no configuration surface for declaring a lookup table, so the documented ✅ is still unreachable from
a configured node.

---

## Regression hunt

Chosen because they run through the two files the remediation changed —
`Expression.java` (every projection and predicate in the engine) and `GlobalAggregate.java` (every
unkeyed aggregate) — plus the narrow types and DATE/TIME the original log flagged as untested.

| Re-run | Result |
|---|---|
| SQL-001 arithmetic projection | identical output to the original, including 2^53+1 |
| SQL-009 `ABS`/`FLOOR`/`CEIL` over INT64 and FLOAT64 | byte-identical to the original; the `ABS` change affects only `Long.MIN_VALUE` |
| SQL-007 projection half (NULL propagation, `n * 0` over NULL) | identical |
| SQL-011 integer `FLOOR`/`CEIL`/`ROUND` above 2^53 | identical, still exact |
| SQL-038 global `COUNT(*)` | one group, one row, 5 |
| text functions: `UPPER`, `LOWER`, `TRIM`, `SUBSTRING`, `\|\|` | identical, including the untrimmed interior spaces of `"  spaced  "` |
| `TRIM` over tab and mixed whitespace | identical — tabs still not trimmed, as before |
| `LIKE 'ann%'` | 2 of 5 rows, correct |
| `LIKE '%.com'` over `mail.com` / `mailxcom` / `a+b` / `ab` | 1 row; `.` still literal, not a metacharacter |
| `CASE WHEN` over integers and text with an `IS NULL` arm | identical |
| integer `SUM`/`MIN`/`MAX`/`AVG` on the stream path | identical; only `COUNT(col)` changed, and changed correctly |

No regression found in any of them. The two changed files did what they say and nothing more.

**Narrow integer types and DATE/TIME, tested for the first time**, because the original log said
"given what turned up in `FLOAT64`, the narrower integer types deserve the same treatment". They
did. Four new defects came out of it: **SQL-070**, **SQL-073**, **SQL-074**, and the fact that
`DATE` and `TIME` cannot be declared in a schema spec at all
(`PRV-5040  unknown type 'DATE'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, FLOAT32, FLOAT64,
STRING, BYTES, TIMESTAMP`) while `SQL_SUPPORT.md` lists both as supported types.

---

## New defects

Numbered continuing the original sequence.

### SQL-066 — FAIL, **blocker**: a windowed query above ~210 000 rows corrupts itself and reports RUNNING

The windowing fix holds on nine rows and on two hundred thousand. Past roughly 210 000 it breaks, in
one of two ways, and the query goes on saying `RUNNING` either way.

All rows are `k='f', amt=1`, one per millisecond of event time, so every answer is arithmetic:
a 10-second window holds exactly `rows / span_seconds * 10` rows and its `SUM` equals its `COUNT`.

```
rows     event-time span  windows  ROWS IN reached   SELECT * FROM <view>
 60 000   60 s             6        60 000            5 rows, correct
200 000  200 s             20       200 000           19 rows, correct
200 000  1200 s            120      200 000           119 rows, correct
210 000  200 s             20       210 000           19 rows, correct
230 000  200 s             20       149 388  STALLED  13 rows -- SILENTLY SHORT, no error
262 000  200 s             20       262 000           PRV-1041  field 0 ('window_start') is NOT NULL and cannot be set null
262 000  262 s             27       262 000           same error
270 000  270 s             27       263 822  STALLED  same error
300 000  300 s             30       263 647  STALLED  same error
600 000  600 s             60       263 810  STALLED  same error
600 001  600 s + 1 late    60       263 830  STALLED  same error
```

Two hundred thousand rows spread over 120 windows is fine and two hundred and thirty thousand over
20 windows is not, so **the trigger is the row count, not the window count, the window width or the
event-time span.** The threshold is between 210 000 and 230 000.

The 230 000-row case is the worst shape in the table, because nothing at all says it went wrong:

```
$ pravaha queries
th230000	RUNNING	e7665d9a86bd	149388          <- of 230 000; frozen, still frozen 30 s later
$ pravaha query --sql "SELECT * FROM th230000"
... 13 rows ...
1757700120000000000	1757700130000000000	f	9439	9439    <- last window: 9 439 rows where 11 500 belong
```

Nineteen windows should have closed and thirteen did; the thirteenth is short by 2 061 rows. The
read succeeds, returns a number, and the number is wrong. A dashboard would show it without a mark.

The server log carries one line per failed query and nothing else:

```
WARN [pravaha-watermark] c.a.m.p.runtime.exec.QueryExecution : could not advance the watermark:
    java.lang.IndexOutOfBoundsException: Index 59 out of bounds for length 59
WARN [pravaha-watermark] c.a.m.p.runtime.exec.QueryExecution : could not advance the watermark:
    java.lang.IndexOutOfBoundsException: Index 47 out of bounds for length 47
WARN [pravaha-watermark] c.a.m.p.runtime.exec.QueryExecution : could not advance the watermark:
    java.lang.IndexOutOfBoundsException: Range [-1897170280, -1897170280 + 409246480) out of bounds for length 1048576
WARN [pravaha-watermark] c.a.m.p.runtime.exec.QueryExecution : could not advance the watermark:
    java.lang.IndexOutOfBoundsException: Range [-1508780288, -1508780288 + 409246438) out of bounds for length 1048576
WARN [pravaha-watermark] c.a.m.p.runtime.exec.QueryExecution : could not advance the watermark:
    java.lang.IndexOutOfBoundsException: Range [-422436344, -422436344 + 409246475) out of bounds for length 1048576
```

**A negative offset into a 1 MiB arena, and a length of 409 million.** That is a signed 32-bit
offset that has wrapped, computed against an off-heap buffer. The `Range [...]` form is the one that
leaves `window_start` null on read, which is consistent: a row was written at a nonsense offset and
what comes back is not the row that was written.

Exactly one warning is logged per query and then the watermark for that query never advances again —
`advanceWatermarkQuietly` catches it, logs, and the clock is dead. Its comment says "Never let the
clock die: a watermark that stops advancing stops every window in the query, and it does it
silently." The catch achieves the opposite of what the comment intends: it converts a crash into
exactly the silent stop it warns about.

**Blast radius is one query, not the node** — a windowed query registered afterwards emits correctly,
and the `pravaha-watermark` threads are still alive. But the affected query reports `RUNNING`
for ever, its `ROWS IN` stops, and there is no way for a client to learn any of this: Flight's
listing exposes name, state, fingerprint and rows-in only, and the feed's `describe()` — which the
remediation extended to record a dead feed — is not on the wire at all. In this failure the feed did
not die, so `describe()` would have said nothing regardless.

Severity: **blocker**. This is the engine's headline feature, on a volume any real deployment passes
inside a minute, producing a wrong answer under a green status. It was unreachable before this
release because no window ever closed; it is reachable now.

### SQL-067 — FAIL, high: `KeyedAggregate` still counts NULLs in `COUNT(col)`

```
$ pravaha query --sql "SELECT g, COUNT(*) AS t, COUNT(n) AS cn, SUM(n) AS sn FROM av GROUP BY g"
g	t	cn	sn
k1	3	3	15          <- k1's n values are 10, NULL, 5.  COUNT(n) must be 2.
k2	2	2	13
```

`SUM(n) = 15` from two values while `COUNT(n) = 3` on the same row: the operator's own output
contradicts itself. `KeyedAggregate.accumulate` line 258 is `case COUNT -> counts[i] += weight;`,
unchanged, while the `SUM, AVG`, `MIN, MAX` and `COUNT_DISTINCT` branches immediately below it all
test `row.isNull(call.argumentOrdinal())`. This is the keyed path — the one
`SQL_SUPPORT.md` points a dashboard at.

### SQL-068 — FAIL, high: `WindowedAggregate` still counts NULLs in `COUNT(col)`

Stream `wn`, window `[T0, T0+10s)`, key `a`, holds `amt` = 10, NULL, 20:

```
$ pravaha query --sql "SELECT * FROM wnw"
window_start	window_end	k	t	ca	sa
1757700000000000000	1757700010000000000	b	1	1	5
1757700000000000000	1757700010000000000	a	3	3	30      <- COUNT(amt) must be 2; SUM is 30, from two values
1757700010000000000	1757700020000000000	a	2	2	7       <- amt = 7, NULL: COUNT(amt) must be 1
```

And over a FLOAT64 column, where `COUNT` is the one aggregate still allowed:

```
$ pravaha query --sql "SELECT * FROM wfw2"    (COUNT(d); W1/a holds d = 1.5, 2.5, NULL; W2/a holds NULL, NULL)
1757700000000000000	1757700010000000000	a	3      <- must be 2
1757700010000000000	1757700020000000000	a	2      <- must be 0
```

`SlicedAggregateState` line 195 is `case COUNT -> accumulator.values[i] += weight;`. Same defect,
same shape, third operator. The exemption written into `refuseFloatingPointAggregate` — "COUNT is
exempt: it counts rows and nulls, and never reads the value" — describes the bug as though it were
the specification. `COUNT(col)` is not supposed to count nulls.

### SQL-069 — FAIL, high: windowed `COUNT(DISTINCT col)` over a non-integer column always returns 1

This is the construct the `COUNT(DISTINCT)` refusal explicitly tells the user to use ("Put it in a
window") and the one `SQL_SUPPORT.md` §windowing says the feature exists for.

```
$ pravaha query --sql "SELECT * FROM cdw2"
window_start	window_end	damt	dk	c
1757700000000000000	1757700010000000000	4	1	4     <- k = a,a,b,a : 2 distinct.  damt over INT64 is right.
1757700010000000000	1757700020000000000	3	1	3     <- k = a,b,a   : 2 distinct
1757700020000000000	1757700030000000000	1	1	1     <- k = a       : 1, right by accident
```

`COUNT(DISTINCT amt)` over the INT64 column is correct in all three windows (4, 3, 1 — hand-checked
against 1/2/10/4, 100/50/200, 7). `COUNT(DISTINCT k)` over the STRING column is 1 in every window,
whatever the data. `SlicedAggregateState` keys its multiset on `values[i]`, a `long` the window
operator fills from `row.getLong(ordinal)`, so every string collapses to the same key. A silent
wrong answer, always low, in the one shape the documentation recommends.

### SQL-070 — FAIL, high: every aggregate over `INT8`, `INT16` or `INT32` dies with a raw internal error

The SQL-037 fix covers `FLOAT32` and `FLOAT64`. The narrow integers have the same defect and no
guard:

```
$ pravaha run --sql "SELECT SUM(t8)  AS v FROM txn"   (t8 is INT8)
PRV-3010  lane 0 stopped after a failure: java.lang.IllegalArgumentException: field 0 ('v') is INT8,
not INT64 in schema txn_projected_aggregated
```

Identical for `SUM(t16)`, `SUM(t32)`, `MIN(t8)`, `MAX(t16)`, `MIN(t32)`, `AVG(t32)` — seven of
seven. `COUNT(t8)` works and is correct (4, one NULL skipped). `validate` reports the plan as
`valid  output: [s INT16, mn INT32, mx INT8, c INT64 NOT NULL, t INT64 NOT NULL]`, so this is
accepted at plan time and killed at run time — the opposite of what the float fix established as the
right behaviour one type away, and a leaked generated schema name in the message.

`SQL_SUPPORT.md` lists `TINYINT`/`SMALLINT`/`INTEGER` among the supported types and
`COUNT/SUM/MIN/MAX/AVG` as ✅ with no type restriction.

### SQL-071 — FAIL, high: a window over a stream with no `event-time` declared is accepted and never fires

Stream `wni` is the same file and the same schema as `ws`, with the `event-time:` line omitted —
the single mistake the new configuration key invites.

```
$ pravaha register --name tumni --sql-file tumble_wni.sql --keys 0,1,2
registered tumni  state=RUNNING  fingerprint=eba7b2e2b2af
$ pravaha queries
tumni	RUNNING	eba7b2e2b2af	9
$ pravaha query --sql "SELECT * FROM tumni"     (at +4 s and again at +30 s)
0 rows
```

This is SQL-039's original symptom, unchanged, reachable by forgetting one line. **It should be
refused at plan time.** The query says `DESCRIPTOR(et)` and the planner holds the stream's schema; it
can see that `et` is not the stream's event-time column and that no watermark can ever advance. The
information needed for the refusal is on hand at the moment the plan is built.

What it does instead is accept, register, ingest and stay empty for ever — and `QUICKSTART.md`'s
note at §6, "Nothing appearing? Almost certainly correct", is the guidance a user will find when
they go looking.

### SQL-072 — FAIL, medium: the new `ROUND` is wrong on two inputs the old one got right

Detailed under SQL-012 above. `ROUND(0.49999999999999994)` returns 1 where the correct answer is 0,
and `ROUND(4503599627370497.0)` returns 4503599627370498 where the correct answer is the input
itself. Both are the documented failure modes of `floor(abs(x) + 0.5)`.

### SQL-073 — FAIL, high: `INT32` and `INT16` arithmetic wraps silently, where `INT64` refuses

```
$ pravaha run --sql "SELECT id, t8 + 1 AS a8, t16 + 1 AS a16, t32 + 1 AS a32 FROM txn" --in nt.csv
ok  5 in, 5 out
1,128,32768,-2147483648        <- t32 = 2147483647.  2147483647 + 1 reported as -2147483648
$ pravaha run --sql "SELECT id, t32 * 2 AS m FROM txn" --in nt.csv
ok  5 in, 5 out
1,-2                           <- 2147483647 * 2 reported as -2
2,0                            <- -2147483648 * 2 reported as 0
```

Control, the same expression in 64 bits:

```
$ pravaha run --sql "SELECT id, CAST(t32 AS BIGINT) + 1 AS wide FROM txn" --in nt.csv
1,2147483648                   <- the right answer
```

`SELECT n + 1` on an INT64 column at `Long.MAX_VALUE` throws (SQL-006 above, `Math.addExact`). The
same expression on an INT32 column at `Integer.MAX_VALUE` returns a negative number under `ok`. The
engine's stated policy — an overflow must never be mistaken for an answer, which is why `ABS` was
made to throw in this very release — is enforced for one integer width and not the other three.

### SQL-074 — FAIL, high: a typed literal throws a raw JDK exception, and on the server it kills a Flight worker thread

`ExpressionCompiler` line 101 is `BigDecimal value = (BigDecimal) literal.getValue4();`, reached for
every literal that is not an interval or a string. Calcite hands back a `Double` for a DOUBLE
literal, an `Integer` for DATE and TIME, and a `Long` for TIMESTAMP.

```
$ pravaha validate --sql "SELECT CASE WHEN n > 0 THEN d ELSE 0.0 END AS x FROM agg"
ClassCastException: class java.lang.Double cannot be cast to class java.math.BigDecimal
$ pravaha validate --sql "SELECT CASE WHEN n > 0 THEN d ELSE CAST(0 AS DOUBLE) END AS x FROM agg"
ClassCastException: class java.lang.Double cannot be cast to class java.math.BigDecimal
$ pravaha validate --sql "SELECT DATE '2026-09-12' AS d FROM txn"
ClassCastException: class java.lang.Integer cannot be cast to class java.math.BigDecimal
$ pravaha validate --sql "SELECT TIME '12:34:56' AS t FROM txn"
ClassCastException: class java.lang.Integer cannot be cast to class java.math.BigDecimal
$ pravaha validate --sql "SELECT TIMESTAMP '2026-09-12 12:34:56' AS ts FROM txn"
ClassCastException: class java.lang.Long cannot be cast to class java.math.BigDecimal
```

`SELECT d + 0.0` is fine, because there the literal stays DECIMAL; it is the branches of a `CASE`,
an explicit `CAST`, and the typed literal syntaxes that force the type and hit the cast.

Worse in a `WHERE` clause, where the same value goes through `Constant$OfLiteral.asLong`:

```
$ pravaha validate --sql "SELECT id FROM txn WHERE et > TIMESTAMP '2020-01-01 00:00:00'"
Exception in thread "main" java.lang.AssertionError: cannot convert TIMESTAMP literal to class java.math.BigDecimal
	at org.apache.calcite.rex.RexLiteral.getValueAs(RexLiteral.java:1228)
	at com.ash.messaging.pravaha.sql.plan.Constant$OfLiteral.asLong(Constant.java:65)
```

The CLI does not refuse it; it crashes with a stack trace. Over Flight the same statement takes a
server worker thread with it:

```
$ pravaha query --sql "SELECT window_start FROM wnw WHERE window_end > TIMESTAMP '2020-01-01 00:00:00'"
PRV-1041  Application error processing RPC

server log:
Exception in thread "flight-server-default-executor-4" java.lang.AssertionError: cannot convert
TIMESTAMP literal to class java.math.BigDecimal
	at com.ash.messaging.pravaha.sql.plan.Constant$OfLiteral.asLong(Constant.java:65)
	at com.ash.messaging.pravaha.sql.plan.PredicateCompiler.compare(PredicateCompiler.java:226)
	at com.ash.messaging.pravaha.serving.ViewQuery.schemaOf(ViewQuery.java:273)
```

An `AssertionError` escaping onto a server executor thread is not a refusal, it is an unhandled
error on a shared pool; the node survived because the pool replaces the thread. The client gets
`Application error processing RPC` with no `PRV-` code. `SELECT CAST(1 AS DOUBLE)` over Flight gives
`PRV-1041  There was an error servicing your request.` and the server logs nothing at all.

This is not a regression — nothing in `7e0de33` touches literal compilation — but it means
`WHERE event_time > TIMESTAMP '...'`, the most ordinary predicate a streaming engine is asked for,
crashes rather than refuses. With windowing now working, users will write it.

### SQL-075 — FAIL, high: one malformed source row kills the whole run with "Report this"

```
$ printf '1,a,notanumber,5\n' > bad.csv
$ pravaha run --sql "SELECT id FROM txn" --schema 'id:INT64,k:STRING,amt:INT64,et:TIMESTAMP' --in bad.csv
UnsupportedOperationException: a plugin aborted a row mid-write, which the ingest path cannot yet
undo: the claimed inbox cell would stay unpublished and stall this lane. Report this -- it needs a
cancel path on RowInbox, not a workaround here.
```

Identical for a file with more columns than the declared schema, and for one with fewer. The codec's
own message — `line 1, column 'amt' (INT64): 'notanumber' is not a number` — never reaches the user,
and `FilesystemPartitionReader`'s comment ("One malformed line must not cost the batch. The engine's
DLQ handles the record") is not what happens: the run dies and no record is quarantined. A user who
mistypes a schema gets an internal error asking them to file a bug. Overlaps the INGEST area;
recorded here because it is what a `--schema` typo produces.

### SQL-076 — FAIL, high (code read, not executed): `event-time` is honoured by one source plugin of four

`pravaha.streams.<n>.event-time` is forwarded into the source binding by `PravahaNode`, and
`FilesystemSourcePlugin` was taught to use it. No other file-shaped plugin was:

```
plugins/pravaha-plugin-feedfile/.../FeedFilePartitionReader.java:128    .eventTimestampNanos(0L)
plugins/pravaha-plugin-delta/.../DeltaPartitionReader.java:272          .eventTimestampNanos(0L)
```

`QUICKSTART.md` line 127 offers "filesystem, feedfile, jdbc or delta" as the choice of plugin. Two
of those four still stamp every row with event time zero, which is the exact condition the original
SQL-039 identified as making windows never close. A deployment that reads dropped files — the
feedfile plugin's whole purpose, and the realistic shape of the thing — still cannot window.

Not executed: the server's application jar ships only the filesystem plugin
(`PRV-5090  no source plugin named 'feedfile' is on the classpath ... Available: [filesystem]`), so
this is a code reading and is labelled as one. It should be cheap to confirm or refute.

### SQL-077 — FAIL, medium: the last window of a stream that stops never closes, silently

Every windowed query in this pass is missing its final window, consistently and by design:

```
stream w   rows to T0+95s, windows to [T0+90,T0+100)   -> 5 rows; [90,100) never appears, ever
lwc        rows to T0+60s, 6 windows                   -> 5 rows
tg         rows over 1200 s, 120 windows               -> 119 rows
```

`WatermarkTracker.advance` excludes an idle partition, but when **every** partition is idle it
returns `current` unchanged — "the watermark stays where it is rather than jumping to infinity". A
filesystem source is one partition, so once the file is exhausted the watermark freezes for ever and
the last window's rows are held and never emitted.

The conservatism is defensible for a live stream in a lull. The consequence is not defensible as it
stands: over a finite source the tail of the data is silently withheld, and there is nothing in the
product — no log line, no state, no metric a client can read — that says "one window is still open
and will never close". On the brief's question about idle-partition exclusion: with a single source
partition it cannot close a window at all, because all-idle is the case the tracker deliberately
does not act on. The exclusion only helps a query whose other partitions are still moving.

### SQL-078 — FAIL, medium: a keyed `COUNT(DISTINCT)` returns the error message `-1`

```
$ pravaha query --sql "SELECT g, COUNT(DISTINCT n) AS d FROM av GROUP BY g"
PRV-1041  -1
```

A transport code and the two characters `-1`. Worse than SQL-055's `PRV-2001  null`: there is not
even an engine code to look up.

### SQL-079 — FAIL, medium: a second name for a shared computation is listed but cannot be read

```
$ pravaha register --name lwbig --sql-file late.sql --keys 0,1,2
registered lw  state=RUNNING  fingerprint=82d8e513bdad      <- prints the FIRST name
a query with the same fingerprint is the same computation, shared
$ pravaha queries
lwbig	RUNNING	82d8e513bdad	60001                        <- listed as RUNNING
$ pravaha query --sql "SELECT * FROM lwbig"
PRV-1041  PRV-2002  Object 'lwbig' not found. Known streams: [wnw, cdw2, av, cdw, lwc, lw, gbw]
```

Still not found ten minutes later. `7e0de33` says "a shared computation's second name is journalled
and registered as a view"; in this shape it is listed and not registered, so the name exists
everywhere except where a user would use it. Also note the confirmation line prints `registered lw`
when the user asked for `lwbig`. Primarily the registry area's finding; recorded because it was hit
here and it defeats a documented feature. `QueryRegistry` has further uncommitted work in the tree
as this is written, so this one is worth re-checking against the next build before it is actioned.

### SQL-080 — FAIL, medium (documentation): the release's own new configuration is undocumented, and the type table is now wrong

* `pravaha.streams.<n>.event-time` and `pravaha.streams.<n>.out-of-orderness` — without which no
  window ever fires — appear in **no** user-facing document. `grep -rn "event-time" docs/*.md`
  finds only three unrelated prose uses. `QUICKSTART.md` §3's stream declaration has no event-time
  column and no `event-time:` key, and §4's `velocity.sql` groups by `TUMBLE(t.event_time, ...)`, so
  the documented end-to-end flow still cannot produce a row.
* `SQL_SUPPORT.md` line 112 still lists `COUNT, SUM, MIN, MAX, AVG` as ✅ with no type restriction,
  and line 234 lists `REAL` and `DOUBLE` among the supported types. Aggregates over both are now
  refused at plan time. The refusal is the right behaviour; the document now describes an engine
  that no longer exists.
* Lines 51 and 54 (`CAST(x AS DOUBLE)` ✅, `ROUND` ✅) carry no note about the rounding mode or about
  `DATE`/`TIME` being undeclarable in a schema spec.

---

## Re-QA summary

### The 15 FAILs and 2 BLOCKED, re-run

| Case | Verdict | What remains |
|---|---|---|
| SQL-039 `TUMBLE` | **PARTIALLY FIXED** | Works on the server with `event-time:` declared, hand-checked. `pravaha run` still emits nothing silently; a stream without `event-time:` still emits nothing silently (SQL-071); only one plugin of four honours it (SQL-076); breaks above ~210 000 rows (SQL-066) |
| SQL-040 `HOP` | **VERIFIED FIXED** | — ten windows, every value hand-checked, overlap proved |
| SQL-037 float aggregates | **VERIFIED FIXED** | Refusal fires for SUM/AVG/MIN/MAX over FLOAT32 and FLOAT64 on stream, view, keyed, windowed and HAVING paths; unbypassable by eleven rewrites; COUNT exempt and correct; integers unaffected; the recommended workaround works. `INT8/16/32` were left out and still fail with a raw internal error (SQL-070); the message names `$f0` for an expression argument |
| SQL-007 `COUNT(col)` | **PARTIALLY FIXED** | `GlobalAggregate` fixed and hand-checked. `KeyedAggregate` (SQL-067) and `WindowedAggregate` (SQL-068) have the identical bug, untouched |
| SQL-012 `ROUND` | **PARTIALLY FIXED** | 2.5→3, −2.5→−3, 0.5→1, −0.5→−1, 2.4→2 all correct; integer ROUND still exact above 2^53. Two new wrong answers introduced (SQL-072) |
| SQL-010 `ABS(Long.MIN_VALUE)` | **VERIFIED FIXED** | Clear `PRV-3010`, real message, exit 1, under 5 s. Nothing hung and nothing was swallowed |
| SQL-005 integer `/ 0` | **FIXED, but not by `7e0de33`** | Fails in ~1 s with the real `ArithmeticException` and exit 1. The fix is an uncommitted working-tree change to `QueryExecution.awaitQuiescent`/`QueryRunner`. Whole-batch loss and the false "routed to the DLQ" claim remain |
| SQL-006 overflow | **FIXED, but not by `7e0de33`** | Same fix; ten of ten runs failed loudly, the `ok` / exit 0 variant never appeared |
| SQL-043 `COUNT(DISTINCT)` | **PARTIALLY FIXED** | Stream half now fails fast with the real `PRV-3020` reason (same in-flight fix), though still at run time rather than at plan time; view half unchanged and still contradicts the document; keyed form now returns `PRV-1041  -1` (SQL-078); the windowed form it recommends is wrong (SQL-069) |
| SQL-008 unary minus | **STILL FAILING** | Unchanged, message still claims the feature is present |
| SQL-055 deep nesting | **STILL FAILING** | `PRV-2001  null` |
| SQL-056 unknown stream | **STILL FAILING** | `PRV-2003` still unused; "Known streams" still lists views and omits every configured stream |
| SQL-060 `--sql` starting `--` | **STILL FAILING** | Unchanged |
| SQL-061 QUICKSTART §2 | **STILL FAILING** | All three errors unchanged |
| SQL-062 matrix compares no value | **STILL FAILING** | Test file untouched; none of this pass's answer defects would fail the build either |
| SQL-065 `--out-schema` mismatch | **STILL FAILING** | Unchanged |
| SQL-047 lookup join | **STILL BLOCKED** | No `pravaha.lookups` anywhere in the server |

**3 verified fixed, 2 fixed by an in-flight change outside the commit under test, 4 partially
fixed, 7 still failing, 1 still blocked.**

### New defects, worst first

| | Case | What is wrong | Severity |
|---|---|---|---|
| 1 | SQL-066 | A windowed query above ~210 000 rows corrupts an off-heap offset, wedges ingestion and either becomes unreadable or returns a **silently short answer**, while reporting `RUNNING` for ever | **Blocker** |
| 2 | SQL-067, SQL-068 | `COUNT(col)` still counts NULLs in the keyed and windowed operators — the fix went into one of the three. Each row contradicts its own `SUM` | **High** |
| 3 | SQL-069 | Windowed `COUNT(DISTINCT col)` over any non-integer column always returns **1**, in the one shape the documentation recommends | **High** |
| 4 | SQL-070 | Every `SUM`/`MIN`/`MAX`/`AVG` over `INT8`/`INT16`/`INT32` dies at run time with a raw type error and a leaked schema name — SQL-037's defect, one type family away from the fix | **High** |
| 5 | SQL-071 | A window over a stream that forgot `event-time:` is accepted and never fires: SQL-039's symptom, one missing config line away, refusable at plan time | **High** |
| 6 | SQL-073 | `INT32`/`INT16` arithmetic wraps silently where `INT64` throws; `2147483647 + 1` is reported as `-2147483648` under `ok` | **High** |
| 7 | SQL-074 | A DOUBLE, DATE, TIME or TIMESTAMP literal throws a raw `ClassCastException`; in a `WHERE` clause an `AssertionError` crashes the CLI and kills a Flight worker thread | **High** |
| 8 | SQL-075 | One malformed source row kills the entire run with `UnsupportedOperationException ... Report this`; the codec's real diagnosis never reaches the user | **High** |
| 9 | SQL-076 | `event-time` is honoured by the filesystem plugin only; feedfile and delta still stamp zero, so windowing still cannot work for the plugin built for arriving files (code read) | **High** |
| 10 | SQL-072 | The new `ROUND` returns 1 for `0.49999999999999994` and changes the whole number 2^52+1 | **Medium** |
| 11 | SQL-077 | The last window of a stream that stops producing never closes and nothing says so; with one source partition idle-exclusion cannot fire at all | **Medium** |
| 12 | SQL-078 | A keyed `COUNT(DISTINCT)` fails with the message `-1` | **Medium** |
| 13 | SQL-079 | A shared computation's second name is listed as `RUNNING` and is not resolvable in `SELECT` | **Medium** |
| 14 | SQL-080 | The two configuration keys this release added are in no user-facing document, and `SQL_SUPPORT.md` still advertises the aggregates the release now refuses | **Medium** (doc) |

### On the fixes themselves

Three of the seven code fixes are clean and hold up under attack: the float-aggregate refusal, the
`ABS` throw, and the windowing work on the server path — that last one genuinely closed four defects
in a row, and the numbers it now produces are right.

Two are **partial in a way the commit message does not acknowledge**. `COUNT(col)` was fixed in
`GlobalAggregate` while the same three-line bug sat in `KeyedAggregate` and `SlicedAggregateState`;
the commit message even says the neighbouring branches "had the check all along", which is true of
all three operators. The float refusal was written to cover the types QA happened to report and not
the types the same accumulator mishandles — the guard's own Javadoc says the operators "accumulate
through `row.getLong` and write through `writer.setLong`, whatever the column's type", which is
exactly why `INT16` fails too.

One **papers over the symptom**: `ROUND`. `floor(abs(x) + 0.5)` is the implementation with the known
half-ulp and tie-to-even defects, and it introduced both while curing the reported one. The original
report named `BigDecimal.setScale(0, HALF_UP)` as the alternative.

And one **fix opened a blocker**. Windowing could not be wrong before, because it never emitted. Now
it emits, and above a couple of hundred thousand rows it emits a wrong answer under a green status
(SQL-066). That is not an argument against the fix; it is an argument that the fix arrived without a
volume test, and that `SqlSupportMatrixTest`, which never compares a value (SQL-062), would not have
caught it at any volume.

### What this pass could not cover

* **The precise threshold in SQL-066**, beyond bounding it between 210 000 and 230 000 rows, and
  whether it depends on key cardinality, lane count, or the row width. Every test here used one key
  and a four-column row.
* **`out-of-orderness` as a value.** Rows arriving out of order are handled correctly, but a row
  arriving *past* the declared tolerance could not be produced: a filesystem source is one partition
  that reads its file faster than the watermark tick, and once it is exhausted the watermark freezes
  (SQL-077), so there is no window in which a row can be late. Every attempt either landed before
  the first watermark or ran into SQL-066. Late-data handling — the side output, the lateness
  counter, `allowedLateness` — remains untested through the product.
* **SQL-076** is a code reading. The server's application jar ships only the filesystem plugin, so
  feedfile and delta could not be exercised.
* **Session windows**, `CUMULATE`, and windowed joins: not reached.
* **Codegen.** Everything above is the interpreted pipeline, as before.
* **The matrix test was read, not run**, for the same reason as last time.
