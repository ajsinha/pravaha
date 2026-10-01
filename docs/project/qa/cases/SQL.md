# SQL surface and query semantics — test cases

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Area code `SQL`. Written before execution. The contract under test is
[`docs/SQL_SUPPORT.md`](../../../guides/CONTINUOUS_QUERIES.md) (since folded into `CONTINUOUS_QUERIES.md`); the question is not whether each construct *plans*
(`SqlSupportMatrixTest` already asserts that) but whether the engine **computes the right answer**
and **refuses honestly** what it cannot do.

## Fixtures

**`num.csv`** — schema `id:INT64,n:INT64?,d:FLOAT64?,s:STRING?`

```
1,10,2.5,hello
2,0,3.5,"  spaced  "
3,-7,-2.5,ann
4,,,                      (n, d, s all NULL)
5,9007199254740993,0.0,annabel
```

**`txn.csv`** on the server — schema
`txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP` (epoch nanoseconds),
seven rows across `u1`/`u2`/`u3` at t+1s … t+300s. The last row is 300 s out so the watermark
passes every earlier window and they close.

**Server** — one node, HTTP 18400, Flight 19400, `security.allow-anonymous: true`, streams `txn`,
`other`, `dim` declared, `txn` bound to the filesystem source above.

**Two execution paths are used deliberately.** `pravaha run` drives the real lane runtime and the
real physical operators over a file, so a scalar answer can be read directly; `pravaha register` /
`pravaha query` drive the same planner and operators inside the server process. Where the contract
concerns an answer, the answer is computed by hand first and written into **Expected** below.

**Anti-vacuity rule used throughout.** Every case that could pass on an empty result asserts a row
count as well as row contents, and — where the risk is real — is paired with a negative control: the
same query with the condition inverted, which must return the complementary rows.

---

## Arithmetic

## SQL-001 — integer arithmetic produces the arithmetic answer
**Intent:** the expression layer is days old. Before anything subtle, prove `+ - * /` over a BIGINT
column returns the number a human computes, not a truncated, widened or re-typed one.
**Setup:** `num.csv`.
**Steps:** `SELECT id, n * 2 + 1 AS a, n - 3 AS b FROM txn` over `num.csv`.
**Expected:** 5 rows. `id=1 → 21, 7`; `id=2 → 1, -3`; `id=3 → -13, -10`; `id=4 → NULL, NULL`;
`id=5 → 18014398509481987, 9007199254740990`. Row 5 matters: `2*9007199254740993+1` is above 2^53
and is only correct if the multiply stayed in `long`.

## SQL-002 — integer division truncates towards zero, including for negatives
**Intent:** `/` on integers must not silently become floating division, and must truncate the way
SQL says (towards zero), not floor.
**Setup:** `num.csv`.
**Steps:** `SELECT id, n / 3 AS q FROM txn`.
**Expected:** `10/3 = 3`, `0/3 = 0`, `-7/3 = -2` (**not** -3), NULL, `9007199254740993/3 = 3002399751580331`.

## SQL-003 — modulo of a negative takes the sign of the dividend
**Intent:** SQL's `%` follows the dividend's sign. A `Math.floorMod` would give 2 for `-7 % 3` and
be wrong.
**Setup:** `num.csv`.
**Steps:** `SELECT id, n % 3 AS m FROM txn`.
**Expected:** `10%3 = 1`, `0%3 = 0`, `-7%3 = -1`, NULL, `9007199254740993%3 = 0`.

## SQL-004 — floating division by zero is IEEE, not an error
**Intent:** `SQL_SUPPORT.md` and the runtime comment both say floating division by zero yields
infinity rather than failing. Prove the documented behaviour is what the engine does, and that it is
distinguishable from a dropped row.
**Setup:** `num.csv`.
**Steps:** `SELECT id, d / 0.0 AS q FROM txn`.
**Expected:** 5 rows out. `2.5/0.0 = Infinity`, `3.5/0.0 = Infinity`, `-2.5/0.0 = -Infinity`,
NULL, `0.0/0.0 = NaN`. Whatever the textual rendering, no row is lost and no zero appears.

## SQL-005 — integer division by zero does not produce a number
**Intent:** the runtime throws `ArithmeticException` and the comment claims the record is routed to
a DLQ. The question for a user is what they actually see: a wrong answer, a dropped row, a failed
query, or a diagnosable error. Anything that silently yields 0 is a correctness defect.
**Setup:** `num.csv`, whose row 2 has `n = 0`.
**Steps:** `SELECT id, 100 / n AS q FROM txn`.
**Expected:** the run must not report `ok` with a value for row 2. Either the command fails with a
message naming division by zero, or the row is visibly excluded and the reason is reported. A `0`,
or a silent `ok  5 in, 5 out` with a fabricated value, is a FAIL.

## SQL-006 — integer overflow at Long.MAX_VALUE is not wrapped
**Intent:** `Math.addExact` should refuse to wrap. Wrapping is the classic streaming-aggregate
silent corruption: a running total that goes negative overnight.
**Setup:** a one-row file with `n = 9223372036854775807`.
**Steps:** `SELECT n + 1 AS a FROM txn`.
**Expected:** no row carrying `-9223372036854775808`. Either a reported failure naming overflow, or
the row excluded with the reason reported.

## SQL-007 — NULL propagates through arithmetic instead of becoming zero
**Intent:** the single most expensive arithmetic bug in a streaming engine: NULL treated as 0, then
summed.
**Setup:** `num.csv` row 4 (all NULL).
**Steps:** `SELECT id, n + 1 AS a, n * 0 AS b FROM txn` and, separately,
`SELECT SUM(n) AS s, COUNT(n) AS c, COUNT(*) AS t FROM txn`.
**Expected:** row 4 gives NULL for both `a` and `b` (`n * 0` is NULL, not 0). The aggregate gives
`SUM = 9007199254740996` (10 + 0 − 7 + 9007199254740993), `COUNT(n) = 4`, `COUNT(*) = 5`.

## SQL-008 — unary minus
**Intent:** Calcite normalises `-x` to `0 - x`; the compiler's two-operand check must not refuse it.
**Setup:** `num.csv`.
**Steps:** `SELECT id, -n AS neg FROM txn`.
**Expected:** `-10, 0, 7, NULL, -9007199254740993`. Five rows.

---

## Scalar numeric functions

## SQL-009 — ABS, FLOOR and CEIL over integers and doubles
**Intent:** the four functions the contract lists. `FLOOR`/`CEIL` of an integer must be the integer
and must keep its type, and of a double must round the right way for negatives.
**Setup:** `num.csv`.
**Steps:** `SELECT id, ABS(n) AS an, FLOOR(d) AS fd, CEIL(d) AS cd, ABS(d) AS ad FROM txn`.
**Expected:** `id=1 → 10, 2.0, 3.0, 2.5`; `id=2 → 0, 3.0, 4.0, 3.5`;
`id=3 → 7, -3.0, -2.0, 2.5` (floor of −2.5 is −3, ceil is −2); `id=4 → NULL,NULL,NULL,NULL`;
`id=5 → 9007199254740993, 0.0, 0.0, 0.0`.

## SQL-010 — ABS(Long.MIN_VALUE)
**Intent:** `Math.abs(Long.MIN_VALUE)` is negative. An absolute value that is negative is a wrong
answer with no warning attached; the contract says nothing about it, so what happens matters.
**Setup:** a one-row file with `n = -9223372036854775808`.
**Steps:** `SELECT ABS(n) AS a FROM txn`.
**Expected:** either a positive answer, or a reported failure. `-9223372036854775808` returned as
an absolute value is a FAIL.

## SQL-011 — FLOOR and CEIL above 2^53 keep every bit
**Intent:** the comment in `Expression.Unary` claims integers are not round-tripped through a double
precisely so that `FLOOR` of a large id is still that id. This is the case that proves it.
**Setup:** `num.csv` row 5, `n = 9007199254740993` (2^53 + 1, not representable in a double).
**Steps:** `SELECT FLOOR(n) AS f, CEIL(n) AS c, ROUND(n) AS r FROM txn WHERE id = 5`.
**Expected:** all three are exactly `9007199254740993`. `9007199254740992` would show the value went
through a double.

## SQL-012 — ROUND of a half-way value follows SQL, not the JVM's default
**Intent:** SQL `ROUND` rounds halves away from zero. `Math.rint` rounds halves to even. If the
engine uses `rint`, `ROUND(2.5)` is 2 — a number that is wrong, looks right, and disagrees with
every other database a user will compare against.
**Setup:** `num.csv` (`d` = 2.5, 3.5, −2.5, NULL, 0.0).
**Steps:** `SELECT id, ROUND(d) AS r FROM txn`.
**Expected:** `2.5 → 3.0`, `3.5 → 4.0`, `-2.5 → -3.0`, NULL, `0.0 → 0.0`.

## SQL-013 — ROUND to decimal places is refused by name
**Intent:** the contract says `ROUND(x, 2)` is refused. A refusal must say what is wrong and what to
do, not merely fail.
**Setup:** any schema.
**Steps:** `pravaha validate --sql "SELECT ROUND(d, 2) FROM txn" …`.
**Expected:** `PRV-2021`, naming `ROUND`, stating that rounding to decimal places is not built.

## SQL-014 — a numeric function outside the four is refused, and the message names something the user typed
**Intent:** the contract says numeric functions beyond `ABS/FLOOR/CEIL/ROUND` are `PRV-2021`. Calcite
rewrites `SQRT(x)` into `POWER(x, 0.5)` before Pravaha sees it, so the refusal names `POWER` — a
function the user never wrote. This case records whether that is acceptable UX.
**Setup:** any schema.
**Steps:** `validate` each of `SQRT(amount)`, `MOD(amount, 3)`, `EXP(amount)`, `LN(amount)`.
**Expected:** every one refused with `PRV-2021` and no plan built. Separately assessed: whether the
message lets a user find `SQRT` in their own SQL.

---

## CASE WHEN

## SQL-015 — branch selection, and a missing ELSE is NULL not zero
**Intent:** the commonest CASE there is. A missing ELSE that produced 0 would corrupt every SUM
downstream.
**Setup:** `num.csv`.
**Steps:** `SELECT id, CASE WHEN n > 5 THEN 1 WHEN n = 0 THEN 2 END AS c FROM txn`.
**Expected:** `id=1 → 1`; `id=2 → 2`; `id=3 → NULL`; `id=4 → NULL` (`n` is NULL so both tests are
UNKNOWN); `id=5 → 1`. Five rows.

## SQL-016 — CASE is lazy: the guarded branch does not divide by zero
**Intent:** `SQL_SUPPORT.md` states this in as many words. It is the one property of CASE that
cannot be checked by planning — both arms compile either way — so it has to be run.
**Setup:** `num.csv`, whose row 2 has `n = 0`.
**Steps:** `SELECT id, CASE WHEN n = 0 THEN 0 ELSE 100 / n END AS c FROM txn`.
**Expected:** 5 rows, no error. `id=1 → 10`; `id=2 → 0`; `id=3 → -14` (100/−7 truncated towards
zero); `id=4 → NULL`; `id=5 → 0`.
**Negative control:** SQL-005 shows the same division *does* fail when unguarded, so a pass here is
laziness rather than a division-by-zero that silently yields something.

## SQL-017 — nested CASE
**Intent:** Calcite flattens a chain; an explicitly nested CASE in a branch is a different tree
shape and exercises the recursive rebuild.
**Setup:** `num.csv`.
**Steps:** `SELECT id, CASE WHEN n > 0 THEN CASE WHEN n > 100 THEN 2 ELSE 1 END ELSE 0 END AS c FROM txn`.
**Expected:** `id=1 → 1`; `id=2 → 0` (0 is not > 0); `id=3 → 0`; `id=4 → 0` (NULL fails the guard,
so the ELSE is taken and the answer is 0, not NULL); `id=5 → 2`.

## SQL-018 — CASE producing text
**Intent:** the contract says a CASE may produce text as readily as a number.
**Setup:** `num.csv`.
**Steps:** `SELECT id, CASE WHEN n > 5 THEN 'big' ELSE 'small' END AS label FROM txn`.
**Expected:** `big, small, small, small, big`. Five rows, no NULLs.

## SQL-019 — CASE mixing text and a number is accepted and produces text
**Intent:** the contract documents this surprise explicitly — Calcite coerces the `0` to `'0'`
before Pravaha sees it, and the column is text. If it were refused, or produced a number, the
contract is wrong.
**Setup:** `num.csv`.
**Steps:** `SELECT id, CASE WHEN n > 5 THEN 'big' ELSE 0 END AS label FROM txn` read back as STRING.
**Expected:** accepted; values `big, 0, 0, 0, big`. A `SUM` over the same expression must not plan.

---

## Text functions

## SQL-020 — UPPER and LOWER, and independent of the machine's locale
**Intent:** the contract promises root-locale conversion so the answer does not depend on which
machine a lane runs on. The Turkish dotless-i is the case that separates root from default.
**Setup:** `num.csv`, plus a one-row file containing the string `i`.
**Steps:** `SELECT UPPER(s) AS u, LOWER(s) AS l FROM txn`; then re-run `SELECT UPPER(s)` over the
`i` row with `-Duser.language=tr -Duser.country=TR` in `PRAVAHA_JAVA_OPTS`.
**Expected:** `HELLO/hello`, `  SPACED  /  spaced  `, `ANN/ann`, NULL/NULL, `ANNABEL/annabel`. Under
the Turkish locale `UPPER('i')` is still `I`, not `İ`.

## SQL-021 — TRIM strips spaces and only spaces
**Intent:** the contract is explicit that this is not `String.strip()`. A TRIM that also ate tabs
would be a different function wearing the same name, and would quietly change join keys.
**Setup:** a file whose `s` values are `"  spaced  "`, `"\tTABBED\t"`, `" mixed\t"`.
**Steps:** `SELECT '[' || TRIM(s) || ']' AS t FROM txn`.
**Expected:** `[spaced]`; `[\tTABBED\t]` — tabs survive; `[mixed\t]` — the leading space goes, the
trailing tab stays.

## SQL-022 — TRIM(LEADING …) and a non-space trim character are refused
**Intent:** the contract says these are refused rather than silently given the default, which would
return the input unchanged and look like it worked.
**Setup:** any schema.
**Steps:** `validate` `TRIM(LEADING ' ' FROM s)` and `TRIM('x' FROM s)`.
**Expected:** `PRV-2021` for both, naming TRIM and saying what is not built.

## SQL-023 — SUBSTRING is 1-based
**Intent:** an off-by-one here silently shifts every extracted field by one character.
**Setup:** `num.csv`, `s = 'hello'`.
**Steps:** `SELECT SUBSTRING(s FROM 1 FOR 2) AS a, SUBSTRING(s FROM 2) AS b FROM txn WHERE id = 1`.
**Expected:** `he` and `ello`. `el` for the first would mean 0-based.

## SQL-024 — SUBSTRING counts code points, not chars
**Intent:** the contract promises a substring never splits an emoji. A `String.substring` on char
indices returns half a surrogate pair, which is not a string at all.
**Setup:** a one-row file with `s = '😀😁abc'` (two astral code points, four chars).
**Steps:** `SELECT SUBSTRING(s FROM 1 FOR 2) AS a, SUBSTRING(s FROM 3 FOR 1) AS b FROM txn`.
**Expected:** `😀😁` (both emoji whole) and `a`. `😀` alone, or a replacement character, is a FAIL.

## SQL-025 — a start below 1 contributes nothing rather than shifting the window
**Intent:** the contract states the standard's rule and names the obvious-looking wrong fix
(clamping start to 1), which would return four characters instead of two.
**Setup:** `num.csv`, `s = 'hello'`.
**Steps:** `SELECT SUBSTRING(s FROM -1 FOR 4) AS a, SUBSTRING(s FROM 0 FOR 2) AS b FROM txn WHERE id = 1`.
**Expected:** `he` (positions −1 and 0 contribute nothing, leaving positions 1 and 2) and `h`.

## SQL-026 — a length past the end clamps, and an empty window is an empty string
**Intent:** boundary behaviour at the far end, including a length large enough to overflow a naive
`start + length`.
**Setup:** `num.csv`, `s = 'hello'`.
**Steps:** `SELECT SUBSTRING(s FROM 4 FOR 100) AS a, SUBSTRING(s FROM 9 FOR 2) AS b,
SUBSTRING(s FROM 1 FOR 0) AS c FROM txn WHERE id = 1`.
**Expected:** `lo`, empty, empty. No exception.

## SQL-027 — `||` chains any number of parts
**Intent:** the contract says any length of chain; the compiler flattens nested pairs.
**Setup:** `num.csv`.
**Steps:** `SELECT s || '-' || UPPER(s) || '!' AS j FROM txn WHERE id = 1`.
**Expected:** `hello-HELLO!`.

## SQL-028 — NULL concatenated with anything is NULL, not an empty string
**Intent:** the contract calls this out because it surprises people, and because the wrong answer
(an empty string) is indistinguishable from a real value downstream.
**Setup:** `num.csv` row 4 (`s` is NULL).
**Steps:** `SELECT id, 'x' || s || 'y' AS j FROM txn`.
**Expected:** rows 1,2,3,5 concatenate normally; row 4 is NULL. Because the filesystem codec writes
NULL as an empty field, the check is done by counting `IS NULL` as well as by reading the file:
`SELECT COUNT(*) FROM txn WHERE ('x' || s || 'y') IS NULL` — or, if that shape is refused, by
comparing against `'xy'` which must match nothing.

---

## LIKE

## SQL-029 — `%` and `_` wildcards
**Intent:** the base case. `%` is any run, `_` is exactly one character.
**Setup:** `num.csv` (`s` ∈ hello, "  spaced  ", ann, NULL, annabel).
**Steps:** `WHERE s LIKE 'ann%'`, `WHERE s LIKE 'ann_bel'`, `WHERE s LIKE '_ello'`.
**Expected:** 2 rows (ann, annabel); 0 rows (`ann_bel` needs one character between `ann` and `bel`,
and `annabel` has two: `a` and `b`… precisely, `annabel` = a-n-n-a-b-e-l, so `ann` + `_` + `bel`
would need `annXbel`, 7 chars with X at position 4 — `annabel` has `a` at 4 and `bel` at 5..7, so it
**does** match: expect 1 row, `annabel`); 1 row (hello).

## SQL-030 — LIKE is anchored at both ends
**Intent:** the difference between `matches()` and `find()`. If it were `find()`, `LIKE 'ann'` would
match `annabel` and every substring filter in production would be wrong.
**Setup:** `num.csv`.
**Steps:** `WHERE s LIKE 'ann'`, then the control `WHERE s LIKE '%ann%'`.
**Expected:** exactly 1 row (`ann`) for the first — **not** `annabel`. The control returns 2, which
proves the first was not empty for the wrong reason.

## SQL-031 — regex metacharacters in a pattern are literal
**Intent:** `LIKE '%.com'` must mean a dot. A leaked regex would match `mailxcom` and the user would
never know.
**Setup:** a file with `s` ∈ `mail.com`, `mailxcom`, `a+b`, `ab`.
**Steps:** `WHERE s LIKE '%.com'`, then `WHERE s LIKE 'a+b'`.
**Expected:** 1 row (`mail.com`) — not `mailxcom`. 1 row (`a+b`) — not `ab`.

## SQL-032 — NULL is dropped by LIKE *and* by NOT LIKE
**Intent:** SQL's three-valued logic. A naive `!like` keeps the null row under NOT LIKE, which is the
classic "the totals do not add up" bug: `LIKE` and `NOT LIKE` then sum to more than the input.
**Setup:** `num.csv`, one row with `s` NULL.
**Steps:** `SELECT COUNT(*) FROM txn WHERE s LIKE 'ann%'`;
`SELECT COUNT(*) FROM txn WHERE s NOT LIKE 'ann%'`; `SELECT COUNT(*) FROM txn`.
**Expected:** 2 and 2, against a total of 5. The two must **not** sum to 5 — the NULL row belongs to
neither.

## SQL-033 — a non-literal pattern and `ESCAPE` are refused
**Intent:** the contract says the pattern is compiled once at registration, so `LIKE status` is
refused; and `LIKE … ESCAPE` is `PRV-2021`.
**Setup:** `num.csv` schema.
**Steps:** `validate` `WHERE s LIKE s`, and `WHERE s LIKE 'a!%b' ESCAPE '!'`.
**Expected:** `PRV-2021` for both, each saying why.

---

## NULL semantics

## SQL-034 — three-valued logic in WHERE
**Intent:** a comparison with NULL is UNKNOWN, and a filter keeps only TRUE.
**Setup:** `num.csv`.
**Steps:** `WHERE n > 5`, `WHERE n <= 5`, `WHERE n = 10`, `SELECT COUNT(*)` for each.
**Expected:** 2, 2, 1. The first two must sum to 4 and not 5: the NULL row is in neither.

## SQL-035 — NOT over a null comparison does not resurrect the row
**Intent:** the contract says `NOT` is pushed down by De Morgan precisely so that
`WHERE NOT (n > 5)` over a NULL `n` does not return rows SQL says it must not.
**Setup:** `num.csv`.
**Steps:** `SELECT COUNT(*) FROM txn WHERE NOT (n > 5)` and
`SELECT COUNT(*) FROM txn WHERE NOT (n > 5 AND n < 100)`.
**Expected:** 2 for the first (rows 2 and 3; row 4 is UNKNOWN and stays out). For the second, rows
where the conjunction is FALSE: row 2 (0 < 5 is false for the first conjunct → FALSE), row 3, and
row 5 (n ≥ 100 → FALSE) = 3 rows; row 4 stays out.

## SQL-036 — IS NULL and IS NOT NULL partition the input exactly
**Intent:** a null check is total. The two must sum to the row count, unlike a comparison.
**Setup:** `num.csv`.
**Steps:** `COUNT(*)` with `WHERE n IS NULL`, `WHERE n IS NOT NULL`, and no filter.
**Expected:** 1 + 4 = 5.

## SQL-037 — aggregates skip NULL but COUNT(*) does not
**Intent:** `SUM(NULL)` contributing 0 to the count is the bug that makes an AVG wrong without
making it look wrong.
**Setup:** `num.csv`.
**Steps:** `SELECT COUNT(*) AS t, COUNT(d) AS c, SUM(d) AS s, AVG(d) AS a, MIN(d) AS mn, MAX(d) AS mx FROM txn`.
**Expected:** `t=5`, `c=4`, `s=3.5` (2.5+3.5−2.5+0.0), `a=0.875` (3.5/4, **not** 0.7 = 3.5/5),
`mn=-2.5`, `mx=3.5`.

---

## Aggregation and windows

## SQL-038 — global COUNT(*) over a stream is allowed and correct
**Intent:** the contract calls it bounded because it is one group.
**Setup:** `num.csv`.
**Steps:** `SELECT COUNT(*) AS c FROM txn`.
**Expected:** exactly one row, `5`.

## SQL-039 — a TUMBLE window produces the right buckets and the right sums
**Intent:** windowed aggregation is the point of the engine. Verified against buckets computed by
hand from the fixture.
**Setup:** the server, `txn` bound to `txn.csv`; rows at +1,+2,+3,+11,+12,+30,+300 s.
**Steps:** register
`SELECT window_start, window_end, user_id, COUNT(*) AS c, SUM(amount) AS total
 FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
 GROUP BY window_start, window_end, user_id`, then read the view back.
**Expected:** windows [0,10): u1 c=2 total=300, u2 c=1 total=300; [10,20): u2 c=1 total=400, u1 c=1
total=500; [30,40): u3 c=1 total=600; [300,310): u1 c=1 total=700. No row may appear in two windows
and no amount may be counted twice.

## SQL-040 — a HOP window overlaps, and each row lands in every window covering it
**Intent:** the contract lists HOP as supported. A sliding window with a 10 s size and a 5 s slide
must place each row in two windows; if it places it in one, the "sliding" is a tumble in disguise.
**Setup:** as SQL-039.
**Steps:** register the same aggregate over
`HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '5' SECOND, INTERVAL '10' SECOND)`.
**Expected:** the `COUNT(*)` total across all windows is twice the total for TUMBLE over the same
rows, because each row is in exactly two windows.

## SQL-041 — an unwindowed keyed GROUP BY over a stream is refused with PRV-2050
**Intent:** the headline refusal, and the one the quickstart advertises. It must be refused at
registration against the running server, not only by `validate`.
**Setup:** the server.
**Steps:** register `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id`; and register a windowed
aggregate that omits `window_start`/`window_end` from the GROUP BY.
**Expected:** `PRV-2050` both times, each naming the key and suggesting a window. Nothing registered
afterwards.

## SQL-042 — the same GROUP BY over a bounded view read is supported
**Intent:** the contract's one deliberate asymmetry. If it is also refused over a view, the document
is wrong; if it returns the wrong groups, worse.
**Setup:** a registered view holding per-user rows.
**Steps:** `pravaha query --sql "SELECT user_id, COUNT(*) AS c FROM <view> GROUP BY user_id"`.
**Expected:** one row per distinct `user_id`, counts matching the view's contents.

## SQL-043 — HAVING and COUNT(DISTINCT)
**Intent:** both are listed as supported; `COUNT(DISTINCT)` must not count NULL.
**Setup:** `num.csv` with a repeated and a NULL value in a group column.
**Steps:** `SELECT COUNT(DISTINCT s) AS d FROM txn`; and a global aggregate with
`HAVING COUNT(*) > 10` which must return nothing, paired with `HAVING COUNT(*) > 1` which must.
**Expected:** `d = 4` for five rows with one NULL and no duplicates; 0 rows then 1 row for HAVING.

---

## Joins

## SQL-044 — inner equi-join between two distinct streams
**Intent:** the shape the README puts on its front page.
**Setup:** the server, streams `txn` and `other`.
**Steps:** register `SELECT t.user_id, t.amount, o.note FROM txn t JOIN other o ON t.user_id = o.user_id`.
**Expected:** registers and runs. With `other` unbound it produces no rows, which is the documented
behaviour and is recorded as such rather than counted as a correctness pass.

## SQL-045 — a self join is refused, and the refusal says what is wrong
**Intent:** the contract admits this one arrives without a `PRV-` code and at pipeline build rather
than at planning. Both are worth confirming against the running server.
**Setup:** the server.
**Steps:** register `SELECT a.user_id FROM txn a JOIN txn b ON a.user_id = b.user_id`.
**Expected:** refused. The message must name the self join. Whether it carries a `PRV-` code is
recorded either way.

## SQL-046 — outer, cross and non-equi joins are refused with PRV-2020
**Intent:** each documented ❌ must actually refuse.
**Setup:** the server / `validate`.
**Steps:** `LEFT JOIN` with no time bound; `RIGHT JOIN`; `FULL OUTER JOIN`; `CROSS JOIN`;
`JOIN … ON t.amount > o.oid`.
**Expected:** `PRV-2020` for each, with a message saying what to write instead where there is one
(`RIGHT` → swap the inputs and use `LEFT`).

## SQL-047 — a lookup join against a dimension table
**Intent:** the second of the two join shapes the contract says cover nearly all real use.
**Steps:** register `SELECT t.user_id, d.tier FROM txn t JOIN dim FOR SYSTEM_TIME AS OF t.event_time
AS d ON t.user_id = d.user_id`.
**Expected:** plans and registers, or is refused with a `PRV-` code and a clear reason. Silent
acceptance followed by a runtime failure is a FAIL.

---

## The documented refusals

## SQL-048 — ORDER BY, LIMIT, OFFSET, UNION, INTERSECT, EXCEPT, VALUES, INSERT, SELECT DISTINCT
**Intent:** nine rows of the contract's ❌ table, each checked for a code and a message.
**Setup:** `validate` against the `num.csv` schema.
**Steps:** one `validate` per construct.
**Expected:** `PRV-2020` for ORDER BY / LIMIT / OFFSET / the set operations / VALUES / INSERT;
`PRV-2050` for `SELECT DISTINCT`. Every message names the construct.

## SQL-049 — subqueries, EXISTS and window functions
**Intent:** the contract says `PRV-2021` for these.
**Steps:** `validate` `WHERE id IN (SELECT id FROM txn)`, `WHERE EXISTS (SELECT 1 FROM txn)`,
`SELECT ROW_NUMBER() OVER (ORDER BY id) FROM txn`.
**Expected:** refused with a `PRV-` code; the code recorded against what the contract claims.

## SQL-050 — text ordering and text-versus-number comparison
**Intent:** `>` on text needs a collation; the contract refuses rather than assume one. Comparing
text to a number is likewise refused.
**Steps:** `validate` `WHERE s > 'ann'`, `WHERE s > id`, `WHERE s = 1`.
**Expected:** `PRV-2021` (or `PRV-2002` for the type error) with a message explaining the collation
argument. `=` and `<>` on text must still work — checked by a control that returns rows.

## SQL-051 — SESSION windows are refused with PRV-2020 and say why
**Intent:** implemented in the runtime, no SQL surface. A user should learn that from the message.
**Steps:** `validate` a SESSION window aggregate.
**Expected:** `PRV-2020`, naming SESSION.

## SQL-052 — DECIMAL arithmetic and year–month intervals are refused
**Intent:** both are deliberate refusals with a stated reason (a ledger's rounding; a month has no
fixed length), and both would otherwise produce plausible wrong numbers.
**Steps:** `validate` `SELECT CAST(n AS DECIMAL(10,2)) * 1.5 FROM txn`, and a join time bound of
`INTERVAL '1' MONTH`.
**Expected:** refused; the DECIMAL message must mention casting to DOUBLE if approximate is
acceptable.

---

## Malformed and hostile input

## SQL-053 — empty and whitespace-only SQL
**Intent:** the first thing a fuzzer and a tired operator both send.
**Steps:** `validate --sql ""`, `validate --sql "   "`, and the same through the server's `query`.
**Expected:** a clean `PRV-2001` (or an argument error) with no stack trace and no hang.

## SQL-054 — a 100 KB query
**Intent:** an unbounded parser is a denial of service. A very long but valid `IN` list must either
plan in reasonable time or be refused; it must not hang or exhaust memory.
**Steps:** `SELECT id FROM txn WHERE n IN (1, 2, …)` with ~10 000 terms, ≈100 KB of SQL.
**Expected:** completes inside 60 s with either rows or a refusal. No `OutOfMemoryError`, no hang.

## SQL-055 — deeply nested parentheses
**Intent:** recursive-descent parsers blow the stack on this, and a `StackOverflowError` escaping to
a user is a crash rather than a refusal.
**Steps:** `SELECT id FROM txn WHERE ((((…n=10…))))` with 2 000 levels.
**Expected:** a `PRV-` refusal or a successful plan. A raw `StackOverflowError` or a killed process
is a FAIL.

## SQL-056 — a stream that does not exist
**Intent:** `PRV-2003`, and a message that lists what *is* registered — the quickstart says the
message is "accurate and confusing", so the exact wording is worth recording.
**Steps:** `SELECT * FROM nosuchstream` through `validate` and through the server.
**Expected:** `PRV-2003` naming the stream, and listing the known streams.

## SQL-057 — a column that does not exist
**Intent:** `PRV-2002`, with Calcite's position preserved.
**Steps:** `SELECT nosuchcolumn FROM txn`.
**Expected:** `PRV-2002`, naming the column.

## SQL-058 — a reserved word as an identifier
**Intent:** `user` is reserved in SQL and has already bitten this team. Both the quoted and the
unquoted form must behave predictably: unquoted refused with a syntax error, `"user"` accepted.
**Setup:** a schema with a column literally called `user`.
**Steps:** `SELECT user FROM txn` and `SELECT "user" FROM txn`.
**Expected:** the first refused with `PRV-2001` or `PRV-2002` — not accepted as something else, and
not a crash. The second returns the column.

## SQL-059 — unicode identifiers
**Intent:** a stream or column named in a non-Latin script must either work or be refused cleanly;
mojibake in an error message is its own defect.
**Steps:** a schema with a column `金额`, `SELECT "金额" FROM txn`.
**Expected:** accepted and returning the column, or refused with a legible message containing the
identifier intact.

## SQL-060 — comments and multiple statements
**Intent:** `--` and `/* */` are normal in SQL a user pastes from a file. A `;`-separated pair must
be refused rather than silently running the first (or, worse, the second).
**Steps:** `-- comment\nSELECT id FROM txn`; `SELECT /* inline */ id FROM txn`;
`SELECT id FROM txn; DROP TABLE txn`; and a single trailing `;`.
**Expected:** comments are ignored and the query runs. The two-statement form is refused; the
refusal must not have executed either statement.

---

## Contract consistency

## SQL-061 — the QUICKSTART example reproduces exactly
**Intent:** the documentation's first runnable command is what every new user types. If it does not
work as printed, nothing after it is trusted.
**Steps:** run `docs/guides/QUICKSTART.md` §2 verbatim and compare against the printed output.
**Expected:** the command as written succeeds and prints the three lines the document shows.

## SQL-062 — the support matrix is a plan check, not an answer check
**Intent:** the contract's own claim is that every construct is "checked by a test". Establish what
that test actually asserts, so the reader knows what is and is not covered by it.
**Steps:** read `SqlSupportMatrixTest` and determine, for each construct, whether any value is
compared.
**Expected:** recorded as a finding, not a pass/fail of the engine: whether a wrong *answer* (as
opposed to a failure to plan) would be caught by the build.

## SQL-063 — a CASE whose branches disagree in type fails with a PRV code
**Intent:** `Expression.Case` throws a bare `IllegalArgumentException` for mismatched branch types.
If that escapes to a user it is a stack trace rather than a refusal, which the contract says never
happens.
**Steps:** a CASE whose branches are text and a number in a shape Calcite does *not* coerce — e.g.
`CASE WHEN n > 5 THEN s ELSE n END`.
**Expected:** either Calcite refuses it with `PRV-2002`, or Pravaha refuses it with `PRV-2021`. A
raw `IllegalArgumentException` or a Java stack trace is a FAIL.

---

## Added during execution

Two cases written after the first pass, because executing the earlier ones exposed a surface that
was worth a case of its own. They are listed here, after the original set, rather than renumbered
into it — a case file that silently grows backwards is not a record of what was planned.

## SQL-064 — bound parameters carry values, never SQL
**Intent:** `SQL_SUPPORT.md` and ADR-032 put `?` in `WHERE` and `HAVING` and nowhere else, and the
whole argument for that is that a bound value never reaches a parser.
**Setup:** the server, with a view holding known rows.
**Steps:** `query --sql "… WHERE user_id = ?" --params u2`; the same with no `--params`; the same
with `--params "u2' OR '1'='1"`; and `SELECT ? FROM <view>`.
**Expected:** the bound query returns exactly the rows for `u2`. A missing value is `PRV-2060`. The
injection string matches nothing — it is a value, not SQL. A `?` in the SELECT list is refused.

## SQL-065 — `--out-schema` disagreeing with the query's real output type
**Intent:** found while checking SQL-003. `pravaha run` takes the output schema from the user and
the row layout from the plan. If the two disagree the sink reads a field at the wrong width, and the
number written to the file is not the number the engine computed.
**Setup:** `num.csv`. `n % 3` is typed `INTEGER` by Calcite (MOD takes the divisor's type), so the
plan's output column is 32-bit while `--out-schema 'm:INT64'` says 64.
**Steps:** run `SELECT id, n % 3 AS m FROM txn` with `--out-schema 'id:INT64,m:INT64?'`, then with
`--out-schema 'id:INT64,m:INT32?'`.
**Expected:** either the mismatch is refused, or both produce `-1` for `-7 % 3`. Producing a
different number under each declaration, with `ok` reported, is a FAIL.
