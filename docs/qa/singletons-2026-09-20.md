# The singleton findings, worked one at a time — 2026-09-20

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

> Every open finding in [`FINDINGS.md`](FINDINGS.md) that is not in the `CFG`, `TY`, `STRM` or
> `TIME` clusters — those four were closed on 2026-09-19 and have their own files. `CASE-1`,
> `TIME-6`, `LANE-6`, `STRM-4` and `STRM-8` are owned elsewhere and are not touched here.
>
> One section per finding, in register order: **verdict**, **cause**, **fix**, **test**,
> **seed-proof**, **commit**. A finding whose fix is genuinely its own batch is left open with what
> it needs written out, rather than half-built.
>
> `FINDINGS.md` itself is not edited here: the lead applies statuses so the counts stay
> trustworthy.

---

## W-6 — `CUMULATE`, and a window slide wider than its window

**Verdict: reproduced, in two of the sub-defects the entry lists; the rest are stale.** The entry
is a NOTE that gathers many small claims. Re-run against the tree on 2026-09-20:

| Claim | Verdict |
|---|---|
| `CUMULATE` "falls to a `default` arm and is refused with no `PRV` code" | **Half true, and worse than recorded.** It has a code, but never reaches that arm: Calcite refuses it first, and its answer is about Calcite. `GROUP BY CUMULATE(...)` gives `PRV-2002  No match found for function signature CUMULATE(<TIMESTAMP>, <INTERVAL_DAY_TIME>, <INTERVAL_DAY_TIME>)`, and `TABLE(CUMULATE(...))` gives `PRV-2002  Cannot apply '$SCALAR_QUERY' to arguments of type '$SCALAR_QUERY(<RECORDTYPE(BIGINT TXN_ID, …)>)'` — a sentence about a construct the person did not write |
| `slide > size` is refused by an `IllegalArgumentException` with no `PRV` code | **Reproduced exactly.** `TABLE(HOP(…, INTERVAL '60' SECOND, INTERVAL '10' SECOND))` threw `java.lang.IllegalArgumentException: a window slide of 60000000000ns is larger than …` straight out of `WindowSpec`'s compact constructor, unwrapped |
| Sub-millisecond intervals truncate to zero and are refused "as not positive" | **Reproduced**, through the same unwrapped `IllegalArgumentException` (`WindowAnswerTest.win027And054` asserted `IllegalArgumentException` by name) |
| Windowed `COUNT`/`COUNT(DISTINCT)`/`MIN`/`MAX` over NULL, empty aggregates | Stale — fixed in `4bc0a36` and `189890b`, as the status line already says |
| A null-keyed left row is never null-padded | Still true; it is J-1, and is handled in J-1's own section |

**Cause.** Two different ones. `CUMULATE` is a name nothing in the tree knows, so the first layer
that has an opinion is Calcite's validator, which is not this engine's vocabulary. `WindowSpec` is
a runtime value class: its compact constructor holds the invariant with an
`IllegalArgumentException`, which is right for a value class and wrong for what reaches a person,
and nothing between it and the SQL surface translated it.

**Fix.** `SqlShapeRefusals.checkBeforeValidation` — a new pass, run between `refuseDml` and
`planner.validate` — refuses `CUMULATE` by name with `PRV-2020`, saying why the slice grid does not
describe it and naming `TUMBLE` and `HOP`. `PhysicalPlanBuilder.windowSpec(...)` wraps every
`WindowSpec` construction on the SQL path and turns an `IllegalArgumentException` into the
planner's own `unsupported(...)` refusal, so the sentence `WindowSpec` writes now arrives with a
code and a help URL. `WindowSpec` is unchanged: its invariant still holds for every other caller,
the embedded builder included.

**Test.** `SingletonRefusalsTest#w6_cumulateIsRefusedByNameInBothSpellings`,
`#w6_aHopWiderThanItsWindowIsRefusedWithACode` (pravaha-sql). `WindowAnswerTest.win027And054_…`
updated: the sub-millisecond interval now asserts `PRV-2020` around the same sentence.

**Seed-proof.** Removing the `refuseUnbuiltWindowFunctions(node)` call fails
`w6_cumulateIsRefusedByNameInBothSpellings` (1 test, 1 failure); restored, it passes. Replacing the
`windowSpec` wrapper's `throw unsupported(...)` with `throw e` fails
`w6_aHopWiderThanItsWindowIsRefusedWithACode` (1 test, 1 failure); restored, it passes.

**Commit.** `SQL refusals that name what the person wrote: W-6, X-6, X-7, X-9, Y-5, Y-6`.

---

## X-6 — `ORDER BY ?`

**Verdict: does not reproduce — and reproducing it found a worse one beside it, which is fixed.**
`SELECT amount FROM txn ORDER BY ?` no longer throws `PRV-2010  class
org.apache.calcite.sql.SqlDynamicParam: ?`. It is refused by `SqlShapeRefusals`'s `ORDER BY` rule
(TY-20's work), with `PRV-2020` and the full explanation of why a continuous view has no order —
which is one of the two codes ADR-032's table names as the candidate, and the better one.

**What reproducing it found.** `SELECT amount FROM txn LIMIT 5` and `SELECT amount FROM txn OFFSET
5 ROWS` were refused with:

```
PRV-2020  ORDER BY is not executed by this engine, and 'ORDER BY ' is refused rather than ignored. …
```

A clause the statement does not contain, with nothing between the quotes. Calcite parses `LIMIT`
and `OFFSET` into the same `SqlOrderBy` node as a sort, with an **empty** order list, and the
refusal took the node's presence as the sort's presence.

**Cause.** `SqlShapeRefusals.check`'s first arm was `if (node instanceof SqlOrderBy orderBy) throw
orderByRefusal(orderBy.orderList)`, with no test of whether the order list has anything in it. The
`SqlSelect` arm beside it did test, and so did not fire either — `SqlSelect.getFetch()` was never
looked at.

**Fix.** The `SqlOrderBy` arm refuses a sort only when there is one, and otherwise raises
`rowLimitRefusal`, which names `LIMIT`, `OFFSET` or both and gives the row limit its own reason: a
view is maintained rather than returned, so "the first five" is whichever five were held at the
instant of the read. The `SqlSelect` arm gained the same check for the spelling that carries
`fetch`/`offset` without an `SqlOrderBy` wrapper. The code is unchanged at `PRV-2020`, which is
what `docs/CONTINUOUS_QUERIES.md`'s `LIMIT` / `OFFSET` row already promised.

**Test.** `SingletonRefusalsTest#x6_aRowLimitIsRefusedAsItselfAndNotAsAnEmptyOrderBy` and
`#x6_orderByAPlaceholderIsTheOrderByRefusalAndNotARawClassName`.

**Seed-proof.** Restoring the unconditional `SqlOrderBy` arm (and disabling the `SqlSelect` one)
fails `x6_aRowLimitIsRefusedAsItselfAndNotAsAnEmptyOrderBy` (1 test, 1 failure); restored, it
passes.

**Commit.** Same commit as W-6.

---

## X-7 — the lookup-join refusal was unreachable

**Verdict: reproduced, then fixed.** A correlated `EXISTS` was refused by `PredicateCompiler` with
`PRV-2021  cannot compile the expression 'EXISTS({…})' (EXISTS) yet. Supported: AND, OR, NOT,
comparisons against a literal, IS [NOT] NULL, …` — a list of boolean primitives offered to somebody
who wrote a subquery. A correlated scalar subquery was refused by `ExpressionCompiler` with
`PRV-2021  function '$SCALAR_QUERY' … is not supported in a projection`, naming Calcite's internal
spelling. Neither reached `PhysicalPlanBuilder.buildLookupJoin`'s `Correlate` message, which names
`JOIN dim FOR SYSTEM_TIME AS OF <time>`.

**Cause.** Both compilers' generic arms are reached before the plan has a `Correlate` node in it,
and neither asked whether the thing it could not compile was correlated.

**Fix.** A new `CorrelatedSubqueries` holds one test and one sentence.
`isCorrelated(RexNode)` asks Calcite which correlation variables the subquery's own tree uses
(`RelOptUtil.getVariablesUsed`) rather than looking for `$cor` in rendered text, which is a
debugging aid whose spelling has changed between Calcite versions. Both generic arms consult it
first. The refusal explains why a correlated subquery is one query per row rather than one
computation, names the lookup join, and — new — tells the reader that an *uncorrelated* subquery is
a separate question they can register as its own continuous query and join.

**The code changes for the correlated shapes**, from `PRV-2021` to `PRV-2020`: a subquery that
reads the outer row is a plan shape this engine does not build, not an expression it cannot
evaluate, and `PRV-2020` is what `buildLookupJoin` already answered. The uncorrelated forms stay
`PRV-2021`. `docs/CONTINUOUS_QUERIES.md`'s table, `SqlSupportMatrixTest` and the console's
`sql-refusals.md` are updated, and the matrix gains a correlated-scalar-subquery row it did not
have.

**Test.** `SingletonRefusalsTest#x7_bothCorrelatedShapesReachTheLookupJoinRefusal`. The QA test that
recorded the defect, `SqlxMultiStreamTest.aCorrelatedSubqueryIsRefusedButNeitherReachableFormOffers
TheLookupJoinAlternative`, is renamed `…AndBothReachableFormsOffer…` and its assertion inverted.

**Seed-proof.** Negating both `isCorrelated` calls fails
`x7_bothCorrelatedShapesReachTheLookupJoinRefusal` (1 test, 1 failure); restored, it passes.

**Commit.** Same commit as W-6.

---

## X-9 — one rule, three codes

**Verdict: reproduced, and there were three codes rather than the two the entry names.**

| Statement | Before | After |
|---|---|---|
| `SELECT ? FROM txn` | `PRV-2002  Illegal use of dynamic parameter` (Calcite's validator) | `PRV-2063` |
| `SELECT COUNT(*) FROM txn GROUP BY ?` | `PRV-2002  Illegal use of dynamic parameter` | `PRV-2063` |
| `SELECT amount * ? FROM txn` | `PRV-2021  expression '?0' is a RexDynamicParam, which Pravaha cannot evaluate yet` — the entry predicted `PRV-2063`, and that only fires if `ParameterMetadata.of(plan)` is separately invoked | `PRV-2063` |
| `SELECT amount FROM txn WHERE amount > ?` | accepted | accepted |
| `… GROUP BY user_id HAVING COUNT(*) > ?` | accepted | accepted |

**Cause.** ADR-032's rule is about *where in the statement* a placeholder stands, and it was being
enforced on the plan — where a group key and a projection are both a `RexNode` under a `Project`,
and where Calcite's validator has already refused the ones it cannot type. `ParameterMetadata.of`
does hold the rule correctly, and nothing on the registration path called it before the expression
compiler had already refused.

**Fix.** `SqlShapeRefusals.refusePlaceholdersOutsideAFilter`, run before validation on the parse
tree, where the clause is still a clause. A `SqlSelect`'s `WHERE` and `HAVING` are walked as
filters and everything else is not; a subquery's own clauses are judged on their own, so a `?` in
the select list of a subquery written inside a `WHERE` is still in a select list. `ORDER BY`,
`LIMIT` and `OFFSET` subtrees are skipped deliberately: those clauses are refused whole, and "this
engine has no sort operator" is a better answer to `ORDER BY ?` than "a placeholder is not a value
here". `ParameterMetadata.of` keeps its own copy of the rule — it is the API a caller asking for
metadata uses — and the two agree.

**Test.** `ParameterBindingTest#x9_everyPlaceholderOutsideAFilterAnswersWithOneCode`;
`#aPlaceholderInTheSelectListIsRefused` updated, because the refusal now comes out of `plan` itself
rather than waiting for `ParameterMetadata.of` to be invoked. The console's `sql-parameters.md` is
corrected, and `HelpExamplesSqlTest` holds it to the new code.

**Seed-proof.** Disabling the `SqlShapeRefusals.checkBeforeValidation(written)` call fails
`x9_everyPlaceholderOutsideAFilterAnswersWithOneCode` (1 test, 1 failure); restored, it passes.

**Commit.** Same commit as W-6.

---

## Y-5 — two refusals that gave advice the engine rejects

**Verdict: half stale, half reproduced.**

- **The `||` message no longer recommends `CAST(… AS VARCHAR)`.** `Expression.Concat`'s constructor
  was corrected under TY-23 and now says "This engine has no conversion from a number to text — not
  through CAST either". *But* that message is unreachable, as DOCX-20 also records: SQL's own type
  coercion inserts the cast, so `name || amount` is refused one layer earlier by
  `ExpressionCompiler.cast()`, whose message was `'CAST($3):VARCHAR …' converts between INT64 and
  STRING; Pravaha evaluates numeric conversions only` — no advice at all, and nothing telling the
  reader that writing the cast by hand is refused identically.
- **`amount * 2.5` versus `price * 2.5` reproduces exactly.** The first is `PRV-2021  … is DECIMAL
  arithmetic`; the second plans. The asymmetry is SQL's own typing and is correct — `2.5` is a
  `DECIMAL(2,1)` literal, and beside a `BIGINT` it makes the expression `DECIMAL`, while beside a
  `DOUBLE` it does not — so the defect is the message, which asserted "DECIMAL arithmetic" over a
  query mentioning no decimal.

**Fix.** Both messages, in `ExpressionCompiler`. The cast refusal now says that this engine
converts between numbers only, that writing the `CAST` out by hand is refused identically "so it is
not a spelling to look for", that SQL's coercion inserted the cast if the person did not, and what
to do instead. The decimal refusal now explains that a literal with a decimal point is a `DECIMAL`
literal in SQL, that the same literal beside a `DOUBLE` column plans, and names two rewrites that
work: `2.5e0`, and `CAST(col AS DOUBLE)`. Both rewrites are asserted to plan, so the advice cannot
rot into advice the engine rejects — which is the whole of what this finding is about.

**Test.** `SingletonRefusalsTest#y5_theTextCastRefusalSaysThatWritingTheCastOutDoesNotHelp` and
`#y5_theDecimalRefusalNamesTheLiteralAndARewriteThatPlans` (which plans both suggested rewrites and
the accepted half of the asymmetry). `ComputedProjectionTest.decimalArithmeticIsRefusedRatherThan
Approximated` updated to the new wording.

**Seed-proof.** Restoring "Pravaha evaluates numeric conversions only" fails the cast test (1 test,
1 failure). Restoring "is a DECIMAL literal in SQL" to nothing fails the decimal test (1 test, 1
failure). Both pass when restored.

**Commit.** Same commit as W-6.

---

## Y-6 — `LIKE` and `SUBSTRING` and the length of a string

**Verdict: does not reproduce. The premise is wrong.** The finding, and the 2026-09-19
verification line, say `Predicate`'s `LIKE`-to-regex translator "maps `_` to a bare regex `.`
iterating by Java `char`", so `LIKE '_ok'` should fail to match `👍ok` while `SUBSTRING` treats the
emoji as one character.

It does not. Java's `java.util.regex` advances by **code point**, not by `char`:
`Pattern.compile(".", DOTALL).matcher("👍").matches()` is `true`. Measured directly, and
then through the engine:

| | |
|---|---|
| `WHERE first LIKE '_ok'` over `👍ok` | matches |
| `WHERE first LIKE '__ok'` over `👍ok` | does not match |
| `SUBSTRING(first FROM 1 FOR 1)` over `👍ok` | `👍` |
| `SUBSTRING(first FROM 2)` over `👍ok` | `ok` |

The two agree. What the translator does walk by `char` is the **pattern text**, and that is
harmless: `%` and `_` are both in the basic plane, so a surrogate pair in the pattern is copied
whole into the literal buffer and quoted by `Pattern.quote`. The other two places `LIKE` appears —
`pravaha-codegen`'s `PredicateSource` and `pravaha-registry`'s `ViewPredicate` — have no `_`
handling of their own and go through `Predicate.Like`.

**No fix.** A test is added anyway, because the agreement is load-bearing and nothing pinned it:
an edit that walked the *subject* by `char` would break this and nothing else in the suite.

**Test.** `StringExpressionTest#y6_likeAndSubstringAgreeAboutTheLengthOfAStringWithASurrogatePair`
(pravaha-it).

**Seed-proof.** Reverting `Expression.Substring`'s `codePointCount(0, value.length())` to
`value.length()` — the char-based form the two were supposed to disagree over — fails the new test
(1 test, 1 error); restored, it passes. So the test does discriminate a divergence, it is simply
the other side that was wrong.

**Commit.** Same commit as W-6.

---

## C-5 — the generated projection dropped every null bit

**Verdict: reproduced.** `FilterProjectGenerator.emitProjection` emitted the value of each selected
column and nothing else. The output row's null bitmap is zeroed once per row (the `setMemory` at
the top of the loop), so **every column a generated stage produced read back as NOT NULL**, and a
NULL arrived at the reader as the zero bytes underneath it: `isNull=false, value=0`.
`InterpretedPipeline.copyField` has always preserved the bit, so the two paths disagreed — on the
path that only runs once a query is hot.

**And the differential property could not see it**, for the reason the status line gives: its
projection was `List.of(0, 1)` over two NOT NULL columns. The one nullable column in the fixture,
`note`, is a `STRING`, which the generator refuses outright — so no projection the property could
build carried a null at all.

**Fix.** The generator copies the null bit beside the value, for a column the **input** schema
declares nullable and only for one: that is the rule the predicate side already follows, so a NOT
NULL column still costs one load and the generated source for one still contains no bit test
(`aNullableColumnGetsANullCheckAndANotNullColumnDoesNot` asserts exactly that and is unchanged).
The value is copied whether or not the column is null — the bytes come from a real input row, every
reader consults `isNull` first, and a branch to skip the copy would cost more than the copy.

**And the property is widened so it could have caught this.** The input schema gains `bonus
BIGINT NULL`; the differential projection is `(id, amount, bonus)` into an output schema whose
third column is nullable; the comparison string carries each row's null state; and `populate` makes
roughly a third of them null, never writing zero as a value, so "the bit was lost" and "the value
was right" cannot be confused.

**Test.** `GeneratedStageTest#c5_aNullProjectedThroughAGeneratedStageIsStillNull`, plus
`#generatedCodeAgreesWithTheInterpreter` (300 tries) which now exercises a nullable projected
column, and `#theDifferentialTestCatchesAGeneratedBug` on the same projection.

**Seed-proof.** Disabling the null-bit emission fails
`c5_aNullProjectedThroughAGeneratedStageIsStillNull` **and**
`generatedCodeAgreesWithTheInterpreter` (10 tests, 2 failures); restored, all 10 pass. The property
failing as well is the point: before this change it could not.

**Commit.** `A generated projection keeps its nulls, and a null-keyed left row keeps its place`.

---

## J-1 — a null-keyed left row was never emitted null-padded

**Verdict: reproduced, as the entry's own status line says, and fixed.** `JoinSide.add` returned
early for any row whose key contains a null, so the row was never stored, was not in state when
eviction ran the outer-join callback, and left no trace anywhere — the only visible consequence was
`rowsHeldLeft()` being one lower than the number of rows fed.
`SymmetricHashJoinBehaviorTest#aNullKeyedLeftRowIsNeverEmittedNullPadded` asserted the drop.

**Cause.** The guard is right for every side whose unmatched rows are never read: a null-keyed row
can never match, so holding one is a leak. It is wrong for the left side of a `LEFT` join, where
SQL says that row must appear, null-padded, precisely *because* it matched nothing.

**Fix.** `JoinSide.keepNullKeyedRows(boolean)`, off by default and switched on by
`SymmetricHashJoin` for the left side of a `LEFT` join and nowhere else. Nothing else had to
change, and that is worth saying: `JoinKeys.hash` already hashes a null to a stable constant,
`JoinKeys.equal` already returns false when either side is null, and `forEachMatch` already refuses
a null-keyed probe — so a stored null-keyed row is unreachable as a match from either direction,
and `evictOlderThan`'s unmatched callback picks it up like any other.

**Test.** `SymmetricHashJoinBehaviorTest#j1_aNullKeyedLeftRowIsEmittedNullPaddedLikeAnyOther
UnmatchedLeftRow` (the renamed and inverted assertion) and
`#j1_aNullKeyedRowStillJoinsWithNothingIncludingAnotherNullKey`, which holds the other half: NULL
is still not equal to NULL, and the right side still keeps none of them.

**Seed-proof.** Passing `false` instead of `plan.leftOuter()` fails both (30 tests, 2 failures);
restored, all 30 pass.

**Docs.** `docs/CONTINUOUS_QUERIES.md`'s `LEFT` join section and the console's `joins.md` both say
what happens to a null-keyed left row, which neither did.

**Commit.** Same commit as C-5.
