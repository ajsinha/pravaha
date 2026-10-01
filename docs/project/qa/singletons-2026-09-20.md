# The singleton findings, worked one at a time — 2026-09-20

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../../LICENSE`](../../../LICENSE).

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
what `docs/guides/CONTINUOUS_QUERIES.md`'s `LIMIT` / `OFFSET` row already promised.

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
`PRV-2021`. `docs/guides/CONTINUOUS_QUERIES.md`'s table, `SqlSupportMatrixTest` and the console's
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

**Docs.** `docs/guides/CONTINUOUS_QUERIES.md`'s `LEFT` join section and the console's `joins.md` both say
what happens to a null-keyed left row, which neither did.

**Commit.** Same commit as C-5.

---

## L-3 — a read racing a drop-then-re-register reported the wrong code

**Verdict: reproduced, and wider than the race.** The entry describes a race; the mechanism under
it is not racy at all. `ViewQuery.relFor` answered `PRV-4023` only when the catalogue was
**empty**, and otherwise handed the SQL to the planner, which answered `PRV-2002 Object 'v1' not
found`. So the code a caller got for one missing view depended on whether some *unrelated* view
happened to be registered — deterministically, and measurable without any concurrency at all. The
race is how it was found: a read landing between a `drop` and its `re-register` sees a non-empty
catalogue without that name in it, which is the same state, and `LifeReRegisterTest#life081` hit it
3 times in 71,823 iterations against 20 cycles.

**Cause.** `PRV-2002` is an SQL-validation code: it sends a reader who asked for a view to the SQL
documentation, and it is not the code a retry loop around `PRV-4023` can act on.

**Fix.** `relFor` catches the planner's refusal and, when it is a validation failure over a table
name this server does not currently serve, answers `unknownView(name)` — the serving layer's own
`PRV-4023`. Decided *after* the failure rather than by checking the catalogue before planning:
checking first has the same race one step earlier, while asking afterwards asks "is this name a
view **now**", and whatever the interleaving that answer is true when it is given. Narrow: only a
validation failure is reconsidered, and only when the statement names a table the catalogue does
not hold — so an unknown *column* of a real view keeps `PRV-2002`, which is right for it.

**This also closes API-F6**, which recorded `API-062` and `API-152` contradicting each other about
the code. They no longer can: there is one code. `API-062`'s original `PRV-4023` turns out to be
what a caller gets, and `API-152`'s account of the split is stale. Both case entries are corrected,
`API-152` keeping its own falsifier ("both cases producing the same code — which would be an
improvement") as the record of what happened.

**Test.** `ViewQueryTest#l3_aNameThisServerDoesNotServeIsPrv4023WhetherOrNotOtherViewsExist` and
`#l3_anUnknownColumnOfARealViewIsStillAnSqlValidationFailure`;
`LifeReRegisterTest#life081_…` **un-disabled** and re-bounded — its drop/re-register loop now runs
until the reader has raced it at least 500 times rather than for a fixed 20 cycles, because a fixed
count on a loaded machine runs the race thinner rather than longer.
`ViewQueryAuthorizationTest#sx5_anAuthorizedCallerStillGetsAStraightAnswerAboutATypo` and
`Sx5LatencyMeasurementTest` updated to the new code.

**Seed-proof.** Removing the translation fails
`l3_aNameThisServerDoesNotServeIsPrv4023WhetherOrNotOtherViewsExist` (1 test, 1 failure); restored,
it passes.

**Docs.** `TROUBLESHOOTING.md`'s `PRV-4023` section, which also still claimed the message "names the
views the server does serve" — SX-5 removed that list and the section had not caught up.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## API-F6 — `API-062` and `API-152` disagree about the code

**Verdict: closed by L-3's fix rather than by choosing a side.** The finding asks which of two case
entries to correct. Both, as it turns out, and in the other direction from the one it proposed: the
executed evidence sided with `API-152` because the engine was wrong, and `API-062`'s `PRV-4023` is
now what happens. See L-3 above for the mechanism.

**Fix.** Case-file only. `API-062` is rewritten (it also still expected the message to list every
view, which SX-5 removed) and `API-152` is rewritten around its own falsifier.

**Test.** `ViewQueryTest#l3_…`, which is the product assertion behind both case entries.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## SX-13 — a filtered read under a shared view's alias

**Verdict: reproduced.** Two principals with byte-identical row filters share one computation by
fingerprint (ADR-025); the shared view carries the first registration's name and the second
principal reads it under an alias registered for them. A filtered read under the alias threw
`PRV-7003` wrapping `PRV-2002 Object 'bob2_sales' not found`, while the identical entitlement under
the primary name returned its rows.

**Cause.** `ViewQuery.withRowFilter` plans a throwaway `SELECT * FROM <name> WHERE <filter>` to
compile the filter into a predicate, against a one-entry catalogue holding *the view's own schema*
— which is named after the primary registration. It passed the alias as `<name>`, so the statement
selected from a table that catalogue did not contain.

**Fix.** The throwaway statement names `schema.name()`. The table name there is scaffolding: what
comes out is a predicate of ordinals and values, carrying no name at all, so making the two halves
agree by construction is the whole fix. The now-unused `source` parameter is gone.

**Test.** `ViewQueryAuthorizationTest#sx13_aFilteredReadUnderAnAliasOfASharedViewReturnsTheSameRowsAsUnderItsPrimaryName`,
which reads the same entitlement under both names and requires the same rows.

**Seed-proof.** Naming a table the one-entry catalogue does not hold fails it (1 test, 1 error);
restored, it passes.

**Docs.** `docs/operations/SECURITY.md`, under row filters and the fingerprint, which is where the promise
that sharing is invisible to the reader is made.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## SX-9 — `AuditSink.InMemory`'s eviction

**Verdict: reproduced, and the finding understates it.** `events.remove(0)` on a
`CopyOnWriteArrayList` is O(n), as recorded — and so is the `add` beside it, because a
copy-on-write array is copied whole on every append. The cost was linear in the limit on *every*
record, not only past it.

Measured directly, both implementations, same JVM, 200,000 appends: at a 1,000-event limit
copy-on-write 268 ms and a deque 5 ms; at 10,000, 986 ms against 1 ms.

**Fix.** An `ArrayDeque` under a monitor. Not obviously the faster choice and is: the reads here
are a snapshot copy taken by an investigator, while `record` runs on every authorization decision,
and copy-on-write optimises the wrong one of the two. A limit below 1 is now refused by name as
well — a sink that keeps nothing while reporting that it audits is the same class of defect.

**Test.** `AuditTrailTest#sx9_appendingFarPastTheLimitStaysCheap` (2,000,000 appends at the default
10,000-event limit, budget 3 s), `#sx9_evictionDropsTheOldestAndKeepsTheRestInOrder` and
`#sx9_aLimitOfZeroIsRefusedRatherThanSilentlyRecordingNothing`. A budget rather than a ratio: a
ratio between two short measurements on a machine running several agents measures the machine.

**Seed-proof.** Adding the copy-on-write list back beside the deque takes the budget test from
**22 ms to 7,460 ms** and fails it; restored, it passes. Two orders of magnitude of headroom on the
side that passes and a factor of three on the side that fails, so load does not decide it.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## SX-14 — two token-configuration edge cases

**Verdict: reproduced as described, and only half of it is catchable.** `SecurityProperties`
validated every token entry's `id` and nothing about the key — which *is* the bearer credential.

- **(a) A bare `yes:` / `on:`.** YAML 1.1 reads them as booleans. One of them binds as the key
  `"true"`: a node that starts, reports that it authenticates, and holds a credential no client can
  present. *Two* of them collapse to one key and fail the whole file's load with a duplicate-key
  error naming neither line — and nothing in Pravaha can catch that, because the file never loads.
  The single-key case is the one that ships a node quietly authenticating nobody, and it is the one
  fixed here.
- **(b) Whitespace around a key.** Confirmed: Spring discards it while binding, so `" tok "` and
  `"tok"` are one entry.

**Fix.** `SecurityProperties.validate()` refuses a key of `true`/`false` (naming `yes:`/`on:` and
the quoting that fixes it), a key that differs from its trimmed form, and an empty one — all
`PRV-7004`, all at startup, none of them printing the credential. **Refused rather than trimmed**:
the operator either meant the whitespace, in which case removing it silently changes who can
authenticate, or did not, in which case saying so costs one restart.

**Test.** `ServerSecurityTest#sx14_aTokenKeyYamlDidNotHandOverVerbatimIsRefused` (five shapes) and
`#sx14_anOrdinaryCredentialIsUnaffected`, which keeps whitespace *inside* a credential legal.

**Seed-proof.** Removing the `refuseAnUnusableCredentialKey` call fails
`sx14_aTokenKeyYamlDidNotHandOverVerbatimIsRefused`; restored, it passes.

**Docs.** `docs/operations/SECURITY.md` and the console's `authentication.md`.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## SX-16 — a Flight node's own reported address

**Verdict: reproduced, both halves.** One mistake in two places: describing the server by what was
*asked for* rather than by what happened.

- The producer is built with the `Location` the server was asked to bind, which is all that exists
  before `start()`. With `pravaha.flight.port: 0` — ask the OS for a free port — that location's
  port is literally `0`, and `getFlightInfo` handed clients an endpoint at port 0.
- `this.location` was `Location.forGrpcInsecure(...)` unconditionally, so a node genuinely serving
  TLS reported `grpc+tcp://`.

**Fix.** After `start()`, the server builds the location from `started.getPort()` and from whether
a certificate is configured, and hands it to the producer through a new
`PravahaFlightSqlProducer.servedFrom(Location)`; the producer's `location` field is `volatile`
rather than final for that.

**Test.** `FlightAddressAndTlsPairTest#sx16_anEphemeralPortIsReportedAsTheOneActuallyBound`
(the server's own `uri()`), `#sx16_theEndpointAClientIsHandedCarriesThePortThatWasBound` (the
endpoint a real `FlightSqlClient` gets back from `getFlightInfo` — a different field, set in a
different place, and the one that actually reaches a caller), `#sx16_aTlsNodeReportsATlsScheme`,
and `#sx16_aPlaintextNodeStillReportsAPlaintextScheme` so the fix cannot invert.

**Seed-proof.** Reverting the scheme branch fails `sx16_aTlsNodeReportsATlsScheme`; removing the
`servedFrom` call fails `sx16_theEndpointAClientIsHandedCarriesThePortThatWasBound`. Restored, all
eight pass. **The client-side test was written because the first seed did not fail**: the server's
`uri()` reads the server's own field, so it proved the half that was already easy and said nothing
about the producer's.

**Docs.** The console's `tls.md`.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## SX-17 — TLS certificate/key misconfigurations

**Verdict: two of three reproduce; one is stale.**

| Shape | Verdict |
|---|---|
| A certificate with no key throws a raw `NullPointerException` | **Stale.** CFG-6 fixed both halves: `encryptedWith` refuses either missing argument with `PRV-6104` naming the configuration key, and `PravahaNode` branches on the certificate *or* the key being set so the mirror case reaches it. `TlsPairTest` has covered this since |
| Swapped cert/key files throw a raw certificate-parsing exception, uncoded | **Reproduced.** `start()`'s catch was `IOException`-only, and the transport builder throws `IllegalArgumentException` for a file it cannot parse |
| A mismatched-but-individually-valid pair starts the node successfully | **Reproduced**, and it is the worst of the three: the startup summary says `transport=TLS` and every client fails its handshake with `tlsv1 alert internal error` — on the client, with nothing in the server's log |

**Fix.** A new `FlightTlsPair.requireMatching`, called before anything is built: it reads the leaf
certificate and the PKCS#8 key, signs a fixed nonce with the key and verifies it with the
certificate's public key. A signature round trip rather than a comparison of key material, because
it works for RSA and EC without knowing anything about either representation — and because it is
the same question TLS itself asks a moment later, which is the point. `start()` also catches
`RuntimeException` now and, when TLS is configured, answers `PRV-6104` naming both paths.

Deliberately **not** a second TLS implementation: Arrow still builds the transport from the same
two files. This only refuses a pair it would have accepted and then failed on.

**Test.** `FlightAddressAndTlsPairTest#sx17_aCertificateThatIsNotThisKeysIsRefusedAtStartRatherThanAtTheFirstHandshake`
(two independently generated self-signed pairs, crossed),
`#sx17_swappedCertificateAndKeyFilesAreRefusedWithACode`,
`#sx17_aKeyThatIsNotPkcs8SaysSoAndSaysHowToConvertIt`, and `#sx17_aMatchingPairStartsAsItAlwaysDid`
as the control.

**Seed-proof.** Removing the `requireMatching` call fails the mismatched-pair test; narrowing
`start()`'s catch back to `IOException` fails the swapped-files test. Restored, all seven pass.

**Docs.** The console's `tls.md` and `errors-gateway.md` (`PRV-6104`).

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## E-15 — `PRV-6101`'s case citation

**Verdict: the case correction stands; the product half is stale, and what was left has been
fixed.**

- The two throw sites `ERRC-090` cited are indeed not Flight SQL metadata calls: they are the
  `default` arm of `doAction`'s custom-Pravaha-action dispatch and the "no registry hosted" guard.
  Confirmed by reading both.
- "The class does not override any Flight SQL metadata method at all" is **no longer true**. P-6
  implemented every one of them — `getStreamPrimaryKeys`, `getStreamCrossReference`,
  `getStreamSqlInfo`, `getStreamTypeInfo` and the rest — and `FlightSqlMetadataTest` drives them
  over a real `FlightSqlClient`.
- What *was* left is the transaction family: `beginTransaction`, `endTransaction`,
  `beginSavepoint`, `endSavepoint`, falling through to Arrow's own `UNIMPLEMENTED "Not
  implemented."` — no code, no help URL, nothing saying whose server it came from.

**Fix.** The four are overridden and refuse with `PRV-6101`, saying why a continuous query is not a
transaction and listing what this server does answer. The gRPC status stays `UNIMPLEMENTED`, which
is the right one; what changes is that the description is the engine's.

**Test.** `FlightSqlMetadataTest#e15_theTransactionVerbsRefuseWithACodeRatherThanTheFrameworksDefault`.

**Docs.** `docs/project/qa/cases/ERRC.md`'s ERRC-090 is rewritten: the right throw sites, the right list of
unimplemented verbs, and a note that the "empty result" it named as the finding to watch for never
happened — the risk was the opposite, a refusal carrying nothing.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## PF-11 — the actionable hint is gone from three refusals

**Verdict: accurate, and it is a NOTE about a cost rather than a defect.** `QueryRegistry.require`'s
refusal ends with the name and nothing else, because the registry sits below the policy and holds
no principal, so it cannot decide whose names a caller may be told. STRM-9 showed what enumerating
unconditionally costs: one principal, denied read on every view, learned the whole catalogue by
misspelling a single name.

**Fix — the pointer, not the list.** The refusal now says where the answer *is*: `pravaha queries`
and the Flight LIST action both run under the caller's own principal and return what that principal
may read, which is the question this layer cannot ask. That is not the list coming back in another
form, and it costs the person who mistyped a name one command instead of an afternoon.

**Test.** No test of its own: what it adds is a sentence, and the existing refusal assertions across
the registry and lifecycle suites already pin the code and the name. Named here so the change is
findable from the finding.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## CKPT-3 — a closed continuous aggregate re-emitted its answer

**Verdict: reproduced, fixed, and then WITHDRAWN — another batch closed it first and is already
merged and drilled (`ClosedAggregateTest`).**

The reproduction stands and is worth keeping: `RegisteredQuery.close()` runs the lanes' finishers
and commits what they emit, and `GlobalAggregate.emit()` wrote the current answer with weight
`+1` having already published that identical answer incrementally on every commit for the life of
the query — so a drop doubled the view's per-key weight a moment before the view was discarded.

**One thing this round found that is worth carrying over even though the code is not.** The first
version of the test watched a *subscriber*, and it passed against the restored defect: a delivery
is handed to another thread, so "the subscriber received nothing" a millisecond after the drop is
also true of a spurious insert that has not arrived yet. The seed proof is what caught that. The
view's per-key weight, read through `committedRows()`, is the synchronous half — written inside
the commit, under the view's own monitor, and settled the moment `close()` returns. If
`ClosedAggregateTest` asserts on a subscriber, it is worth checking that it fails against the
defect.

**Withdrawn rather than kept.** My change to `GlobalAggregate.emit()` and its test are reverted to
the branch point, so this branch's diff does not touch that method at all and the merged fix
stands alone. Nothing to resolve by hand.

---

## CKPT-5 — a failed checkpoint leaves a hole in the id sequence

**Verdict: reproduced by reading and then by test.** `checkpointNow` took the id with
`nextId.getAndIncrement()` before cutting or storing anything, so an attempt that threw had spent
its number and written nothing. `prune` is strictly newest-K-by-id, so a directory ends up holding
`1,3,4` where `1,2,3` was the sequence — invisible, because recovery restores the newest readable
checkpoint and is correct over both, and `ls` cannot tell the two apart.

**Fix, both halves of the disposition.**

- **The id is given back** when the attempt fails, with a compare-and-set so it is only given back
  when nothing else has reserved one since. Concurrent checkpointing is not expected here, and
  silently rewinding past somebody else's reservation would be worse than the gap. `nextId`'s
  seeding from `availableIds().max() + 1` is unaffected and still holds across a restart.
- **A gap that already exists is surfaced.** `PeriodicCheckpointer.missingIds()` answers "is this
  directory healthy?" asked of the directory — empty for a healthy one, including a pruned one,
  because `prune` leaves a contiguous run. It is logged once at construction, which is the moment
  somebody is reading the log anyway.

**Test.** `StateFailureReportingTest#ckpt5_aFailedCheckpointDoesNotSpendItsIdAndLeavesNoHole` (a
store that refuses the second call: the directory ends `1,2,3`, not `1,3,4`) and
`#ckpt5_aDirectoryWithAHoleReportsIt`.

**Seed-proof.** Removing the rollback fails the first; removing the `missingIds` log fails the
second. Restored, both pass.

**Commit.** `Refusals that name the right layer: L-3, API-F6, SX-9, SX-13, SX-14, SX-16, SX-17, E-15, PF-11, CKPT-3, CKPT-5, SINK-3`.

---

## SINK-3 — any registrant may name any bound sink

**Verdict: reproduced, fixed, and then WITHDRAWN — another batch closed it first and is already
merged and drilled (`SecurityPolicy.mayWriteTo`, audited as `register:sink` and `replace:sink`,
with `PRV-7005` for a policy that allows the write but carries a row filter).**

The merged version is a superset of what was written here: it covers the replacement path as well
as registration, and it has a refusal this one did not think of. My `mayWriteTo`, the registry's
call site, the two tests and the `docs/operations/SECURITY.md` section are reverted to the branch point, so
this branch's diff does not touch `SecurityPolicy` or that part of `QueryRegistry` and the merged
fix stands alone.

The reproduction stands: `QueryRegistry.register` asked `mayRegisterQuery` and `mayRead` per
source stream and nothing about the sink, and the `register` audit event recorded the SQL — which
does not name the sink, because `WRITING TO`, `WITH (sink = ...)` and `--sink` are all outside the
statement.

---

## X-3 — `--out-schema` is not checked against the plan

**Verdict: reproduced, exactly as the entry traces it.** `QueryRunner.run` builds its `Collector`
and `BinaryRowWriter` from `plan.outputSchema()` and configures `FilesystemSinkPlugin` and
`DelimitedCodec` from the separate `--out-schema` string, and nothing compared them.
`RowLayout.checkType` is the **writer's** guard, and the writer is built from the true schema, so
it has nothing to complain about; the decoder reads the bytes back at the declared widths.

Confirmed on the new test's fixture before the fix: `SELECT MOD(amount,3), MOD(amount,3)` produces
`INT32,INT32`, and declaring `a:INT64` read eight bytes across both columns — `1,1` came back as
`4294967297`, `-2,-2` as `-4294967298`. Exit 0, a full CSV of plausible numbers.

**Fix.** `requireTheDeclaredOutputSchemaMatchesThePlan`, called immediately after planning and
before the sink is configured: column count, then type per column, and the refusal prints the
`--out-schema` string the plan would accept. **Count and type only, never names** — somebody
naming an output column `total` where the plan calls it `EXPR$1` has said something about the CSV
header and nothing about the bytes. `PRV-1031 CLIENT_INVALID_OPTIONS`: the CLI is a client and
these are its options.

This is the only place the two descriptions are ever both in scope, which is why the comparison
could not live anywhere lower.

**Test.** `PravahaCliTest#x3_anOutSchemaThatIsNotThePlansOutputIsRefusedRatherThanReadingTheWrongBytes`,
`#x3_anOutSchemaWithTheRightCountAndTheWrongWidthIsRefused`, and
`#x3_theShapeTheRefusalNamesIsAcceptedAndCorrect` — the control, which runs the shape the refusal
names and checks the answer is `1,1` and `-2,-2`.

**Seed-proof.** Removing the cross-check fails both refusal tests (2 tests, 2 failures); restored,
all three pass.

**Commit.** `A declared shape that is not the real one, and refusals that name where they came from`.

---

## API-F2 — the codegen happy path cannot be shown with `FILTERSQL`

**Verdict: accurate, and it is a case-file defect.** `API.md`'s shared `FILTERSQL` constant projects
`user_id`, a `STRING`, and the generator refuses a STRING projection (`PRV-3101`). So API-037 —
written to pin the *happy* path, numbered generated Java — exercises the fallback, which is
API-038's case, and the two cases differ by nothing.

**Fix.** Case-file only: a second constant, `NUMERICSQL = "SELECT txn_id, amount FROM txn WHERE
amount > 100"`, used by API-037 alone, with the reason written beside it. The other option the
finding offers — rewriting API-037 to say the surface cannot cover a string projection — would
have deleted the only case that pins the happy path at all.

**Test.** None: this is a case file, and `GeneratedStageTest` is where the product assertion lives.

**Commit.** `A declared shape that is not the real one, and refusals that name where they came from`.

---

## API-F7 — a dead-server refusal, and `subscribe`'s premature banner

**Verdict: reproduced, both halves.**

- Every server command against nothing listening failed with the bare stderr text
  `PRV-1041  io exception`. No host, no port, no scheme — an operator debugging "why did my script
  print `io exception` and exit 1" had nothing to go on, not even whether the default endpoint had
  been used because `--url` went into a different flag.
- `pravaha subscribe --view x` wrote `subscribed to x; changes print as they are committed.` to
  **stdout**, before the connection had been opened at all.

**Fix.** `ServerCommand` remembers the endpoint `connect` was pointed at, and `fail` appends it
when the message does not already carry it — the transport's own text is true of any socket
anywhere, and where this process was pointed is the one thing it knows and the message does not.
The banner moves to **stderr** and to after the subscription object exists: stdout carries the
rows, a note to a person does not, and `run()` is where a broken connection actually surfaces so
the banner is not a promise even then.

**Test.** `PravahaCliTest#apiF7_aDeadServerRefusalNamesTheAddressItWasTalkingTo` and
`#apiF7_subscribeWritesNoSuccessBannerToStdoutWhenItCannotConnect`, both against
`grpc://localhost:1`.

**Seed-proof.** Restoring both — the address suppressed and the banner back on stdout — fails both
(2 tests, 2 failures); restored, both pass.

**Commit.** `A declared shape that is not the real one, and refusals that name where they came from`.

---

## API-F8 — `?level=` treated as absent

**Verdict: reproduced, and the cause is one line of framework behaviour.** Spring's
`@RequestParam(defaultValue = ...)` is not the rule "when the parameter is absent": it substitutes
the default for an **empty** value too. So `?level=` — the parameter present, with nothing after
it — answered `200` with `level:"physical"`, the same as omitting it, while `?level=PHYSICAL` was
refused. The endpoint could not tell a caller who said nothing from one who said nothing usable,
and only one of those is a caller to answer.

**Fix.** `level` and `format` are `required = false` with no `defaultValue`; the controller decides
explicitly — `null` takes the default, anything else goes to the switch. Both refusals now say
that an empty parameter is this rather than an absent one, because that is the sentence that saves
the next reader the experiment.

**Test.** `ApiIntegrationTest#apiF8_anEmptyLevelIsARefusalRatherThanAnAbsentOne`, including the
control that omitting the parameter still takes the default.

**Seed-proof.** Restoring `defaultValue` fails it (1 test, 1 failure); restored, it passes.

**Commit.** `A declared shape that is not the real one, and refusals that name where they came from`.

---

## API-F10 — a lone unpaired UTF-16 surrogate

**Verdict: reproduced.** `{"sql":"\ud800"}` is well-formed JSON and is not text: a high surrogate
with no low surrogate after it encodes no character. Jackson decodes it rather than refusing the
body, and the lone `char` reached the SQL lexer, which failed cleanly with `PRV-2001` — an answer
about the query, for a request that never carried one.

The entry's own status line says the executed result was a `500`; the body says `200`
`valid:false`. Re-run today it is the `200`, as the body says. Either way the code is about SQL.

**Why it is worth refusing rather than leaving to the lexer.** Every UTF-8 encoder replaces an
unpaired surrogate with U+FFFD, so the statement this server logs, audits and quotes back in a
diagnostic is not the statement that was sent.

**Fix.** `requireSql` refuses an unpaired surrogate with `PRV-1051 API_INVALID_PARAMETER`, naming
the index and the code point. A complete pair is a character and is untouched — the test asserts
an emoji in a string literal still validates.

**Left, deliberately.** The wider fix is a Jackson deserializer that refuses an unpaired surrogate
in **any** string of any request body, and that is a change to every endpoint at once, including
registration names and the console's own traffic. This is the field the finding exercised and the
one whose text is echoed back; the rest is recorded here rather than half-built.

**Test.** `ApiIntegrationTest#apiF10_anUnpairedSurrogateInTheSqlIsRefusedAsABadRequest`, over
`/validate` and `/explain`, with the surrogate-pair control.

**Seed-proof.** Removing the check fails it (1 test, 1 failure); restored, it passes.

**Commit.** `A declared shape that is not the real one, and refusals that name where they came from`.

---

## E-8 — a 34-deep acyclic chain refused as circular

**Verdict: reproduced, and the finding's own measurement holds.** `ConfigResolver.MAX_DEPTH` was
32, and the guard shared `PRV-1011 CONFIG_CIRCULAR_REFERENCE` with the real cycle detector — the
`visiting` set, which works correctly on an actual loop. A 33-deep chain resolved; a 34-deep one
was refused as a circular reference that does not exist, and ERRC-004's own vacuity control (a
100-deep terminating chain, written to prove the cycle check was not firing on everything) tripped
it.

**Fix, and why it is safe.** The depth guard is a backstop against the **stack**, not a cycle
detector, and it was numbered as though it were a statement about configuration. It is now 256 —
resolution recurses about three frames per level, so that is well inside a default thread's stack
and far past anything a person writes — and it refuses under a code of its own,
`PRV-1012 CONFIG_REFERENCE_TOO_DEEP`, whose message says "this is depth, not a cycle" and points at
`PRV-1011` for the other one. Two problems with two different fixes: a cycle is broken, a chain is
flattened.

The finding's own caution — "changing `MAX_DEPTH` is a real behavioural change to a shared
recursion guard, not a small, obviously-safe one" — is right about the number and wrong about the
risk here: raising it *widens* what is accepted and cannot refuse anything that used to resolve.
The cycle detector is untouched, and its three tests still pass unchanged.

**Test.** `ConfigResolverTest#e8_aLongAcyclicChainResolvesRatherThanBeingCalledCircular` (34 deep,
the exact depth that used to fail, and ERRC-004's 100) and
`#e8_pastTheBackstopItSaysDepthRatherThanCycle` (400 deep).

**Seed-proof.** Putting `MAX_DEPTH` back to 32 under `CIRCULAR_REFERENCE` fails both (2 tests, 1
failure and 1 error); restored, both pass.

**Docs.** `TROUBLESHOOTING.md` gains `PRV-1012` in the code table and a remedy row; the console's
`errors-config.md` gains its section, and `PRV-1011`'s loses the "or references nest deeper than
the resolver allows" clause that made the two one thing.

**Commit.** `A declared shape that is not the real one, and refusals that name where they came from`.

---

## T-5 — per-plugin event time

**Verdict: three of the five rows are stale; one was already fixed; one reproduces and is fixed
here.** The entry is a table of five plugins. Re-read against the tree on 2026-09-20:

| Plugin | The entry says | Verdict |
|---|---|---|
| filesystem | honours the declared column | Still true |
| feedfile | hard-codes `eventTimestampNanos(0L)` | **Stale.** `FeedFilePartitionReader` reads `decoder.lastEventTimeNanos()` and falls back to 0 only when the stream declares no column (HLP-6) |
| delta | hard-codes `eventTimestampNanos(0L)` | **Stale**, same fix: `DeltaPartitionReader` reads the declared ordinal and converts micros to nanos |
| jdbc | uses `watermark.column` raw and unconverted | **Reproduces exactly.** `results.getLong(watermarkIndex)` straight into `eventTimestampNanos` |
| aerospike | stamps the scan's start time | Fixed in `7402a5b`, as the status line already says |

**Cause of the jdbc row.** The watermark column is a *monotone cursor*. It need not be a time at
all — a sequence, an id — and where it is one it is in whatever unit the table keeps, while the
engine counts nanoseconds (ADR-012). So an `updated_at BIGINT` of epoch **milliseconds** produced
an event time out by a factor of a million: a watermark stuck in 1970, windows that never close,
and nothing anywhere saying so. The comment beside the line called using it "a statement of fact
rather than a guess", and the unit was the guess.

**Fix.** A `watermark.unit` option — `none`, `nanos`, `micros`, `millis`, `seconds` — defaulting to
**`none`**, which says the column is a cursor and carries no event time. That is what `feedfile`
and `delta` already do for a stream that declares no event-time column. An unknown value is
refused by name at configuration; a value that **overflows** its declared unit is refused at the
row, because that is what reading epoch milliseconds as `seconds` does and wrapping it would put
the watermark before the epoch and close every window at once.

**A behaviour change, said out loud.** A deployment whose watermark column really did hold epoch
nanoseconds must now write `watermark.unit: nanos`, and gets exactly what it had. Every other
deployment was getting an event time wrong by a factor of 1,000 or 1,000,000, or one that was not
a time at all, and now gets none. There is no unit this plugin can infer, and every guess is wrong
for somebody — which is the standing rule, applied.

**Test.** `JdbcSourcePluginTest#t5_theWatermarkColumnIsAnEventTimeOnlyWhenItsUnitIsDeclared`
(`millis` and `nanos` conversions), `#t5_aWatermarkThatOverflowsItsDeclaredUnitIsRefusedRatherThan
Wrapped`, and `#readsRowsInWatermarkOrderAndPreservesNulls`, whose assertion is inverted — it
pinned the raw stamp, which was the defect.

**Seed-proof.** Restoring `.eventTimestampNanos(watermark)` fails all three (3 tests, 3 failures);
restored, all pass.

**Docs.** `docs/guides/CONNECTORS.md`'s jdbc binding example and the console's `source-jdbc.md` option
table.

**Commit.** `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## T-6 — the two out-of-orderness keys

**Verdict: reproduced, both halves, and the second one is a single missing condition.**

**(b) A stream-level `out-of-orderness` with no `event-time` was read and dropped.**
`StreamCatalog.withEventTime` has always refused that combination by name — lateness needs an
event time to be about — and `PravahaNode.withEventTime` returned early before reaching it, because
its guard asked about `event-time` and `allowed-lateness` and not about `out-of-orderness`. So the
operator wrote a number, the node started, and nothing said the number had been discarded. Fixed
by adding the third clause.

**(a) `pravaha.watermark.out-of-orderness` was read by nothing.** Same finding as DOCX-6; see
below.

**Test.** `WatermarkSettingsTest#t6_anOutOfOrdernessWithNoEventTimeIsRefusedRatherThanDropped`.

**Seed-proof.** Restoring the two-clause guard fails it (1 test, 1 failure); restored, it passes.

**Left open, and it is TIME-6's:** the entry's four smaller items — an `Error` rather than a
`RuntimeException` cancelling the scheduled tick silently, `boundedOutOfOrderness` not saturating,
a push-only query's windows firing into a sink nothing commits, and view retention comparing a
sequence number against an event-time horizon. Those are TIME-6's territory and TIME-6 is owned by
another agent this round.

**Commit.** `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## DOCX-6 — `pravaha.watermark.out-of-orderness`, shipped and read by nothing

**Verdict: reproduced, and the finding's own experiment is the right one.** The literal string
appears in `src/main` exactly once, in a javadoc. It ships in `application.yaml:182` with a default
of `10s`, is documented in `CONCEPTS.md` and `OPERATIONS.md`, and is named in `StreamSchema`'s
javadoc as the way a deployment moves this.

**The decision the entry asks for: give it a reader.** Removing it would mean editing three
documents to delete a feature readers have been told they have, and leaving a node with no way to
set lateness once for every stream. Wiring it makes all three true.

**Why it is safe.** Its default is `StreamSchema.DEFAULT_OUT_OF_ORDERNESS`, which is the same 10
seconds a schema already took and the same 10 seconds the file already showed. A deployment that
has not set it sees no change; one that has been setting it and wondering why nothing happened now
gets what it asked for.

**Semantics, stated because they are a decision.** A stream's own `out-of-orderness` wins. A stream
that declares an **event time** and no lateness takes the node default. A stream with **no event
time** takes nothing — there is nothing for the lateness to be about, and attaching one would put
an out-of-orderness on a schema whose watermark never moves. That last case is T-6(b)'s refusal.

**Test.** `WatermarkSettingsTest#docx6_theNodeWideOutOfOrdernessReachesAStreamThatDeclaresNoneOfIts
Own` and `#docx6_aStreamsOwnOutOfOrdernessOverridesTheNodeDefault`, which also pins that a node
setting nothing still gets `DEFAULT_OUT_OF_ORDERNESS`.

**Seed-proof.** Passing `null` where the default is chosen fails them (2 tests, 1 failure);
restored, both pass.

**Docs.** `application.yaml`'s own block (which said "NOT READ BY ANYTHING" in capitals),
`CONCEPTS.md`, `OPERATIONS.md`, `StreamSchema`'s javadoc, and four console pages —
`settings-index.md`, `streams.md`, `event-time-watermarks.md` and `configuration.md`, the last of
which carried a `!!! danger` pitfall box about it.

**Commit.** `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## SRC-6 — a followed file costs ~13 ms of CPU per second while idle

**Verdict: reproduced, and the mechanism is exactly as described.** `PumpingFeed` napped
`IDLE_NAP_NANOS` — a flat **one millisecond** — after any poll that moved nothing, so every bound
source was polled a thousand times a second whether or not anything had happened. In follow mode
each of those polls is a `Files.readAttributes` plus a `read`. The constant is package-private and
compile-time, and no source's declared latency or any setting reaches it.

**Fix.** The nap doubles while a source stays quiet and resets to a millisecond the moment a poll
moves a row. A stream that is merely *slow* rather than empty pays the millisecond it always paid;
one that has been quiet costs fifty wake-ups a second instead of a thousand. A **paused** feed
naps the maximum immediately, because it is not waiting for anything.

**The ceiling is the feed's own publish interval (20 ms), and that is the argument rather than a
round number.** `PumpingFeed` already publishes its applied frontier only every 20 ms, so a row's
visibility latency is already that; a nap that cannot exceed it cannot become the dominant term in
anything. That is the property the flat millisecond was chosen for — kept, and paid for once
instead of a thousand times a second.

**Measured, on the finding's own test.** `SourceScaleTest`, this machine, load average 96:

| | before (the finding) | after |
|---|---|---|
| 100 followed sources | 12.8 ms/s per source (1,782 against a 504 baseline) | **2.0 ms/s per source** (370 against a 170 baseline) |
| 50 followed sources | 16.9 ms/s per source | **3.4 ms/s per source** (294 against 124) |

About six times cheaper. Both figures are baseline-subtracted inside one run, which is what makes
them comparable on a machine running several agents; the absolute numbers are not comparable with
the finding's, and the per-source ones are.

**Test.** `IdleFeedBackoffTest` (pravaha-bindings), three cases, **asserted as arithmetic rather
than as a duration**: a test counting wake-ups over a second would measure this machine, and would
fail in the *safe* direction for the old code, because a loaded machine polls fewer times too.
`SourceScaleTest` reports the CPU figure that follows and ratchets only the counts, as before.

**Seed-proof.** Making `nextIdleNap` return its argument fails 2 of the 3 (3 tests, 2 failures);
restored, all pass.

**Left.** `SharedPartitionFeed` has the same constant and the same loop. It is LANE-2's shared
reader and LANE-6 — an intermittent property failure in exactly that code — is being investigated
by another agent this round, so it is left alone rather than edited underneath them. The change is
the same six lines when that lands.

**Commit.** `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## S-5 — the idle-CPU fix was partial

**Verdict: the measurement holds, the mechanism named in the status line is stale, and the
dominant term is fixed by SRC-6.**

The status line says "`QueryExecution` still runs a per-query `ScheduledExecutorService`
(`watermarkClock`, daemon thread `pravaha-watermark`) on a fixed-delay schedule". It does not.
W9-3 replaced it: `QueryExecution.watermarkClock` is a `ScheduledFuture` from `SharedClock`, which
keeps time on **one** thread for the whole JVM and fires each tick on a virtual thread. There is no
`pravaha-watermark` thread and no per-query scheduler. What remains true is the substance: a tick
per query per interval, whether or not the query is idle.

**The number the finding is about was the other term.** Its own measurement is "~1,000 feed
wake-ups per second, 50 commits per second and a watermark tick" — and the thousand wake-ups are
`PumpingFeed`'s flat one-millisecond nap, which is SRC-6, measured from the other side. Fixing
SRC-6 takes that term from 1,000/s to about 50/s and the measured per-source idle CPU from 12.8
ms/s to 2.0 ms/s.

**Left, deliberately.** The watermark tick itself is one wake-up per query per second on a shared
timing thread, and the 50 commits/s are the publish interval. Skipping a tick for an idle query is
not free: the tick is what makes *idleness* detectable, so a query that stops ticking because it is
idle can no longer notice that it is idle. That is a design question about `pravaha.watermark.tick`
and `idle-after` together, and it belongs with whoever changes those, not with a CPU measurement.

**No code change under this finding's own name.** Its correction — "the headline number was right
and the conclusion was too strong" — stands as written and is now less true by a factor of six.

**Commit.** `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file` (the verdict; SRC-6 carries the code).

---

## DOCX-20 — documented remedies the engine then refuses

**Verdict: three claims, two fixed here, one stale.**

- **The `||` refusal recommending `CAST(… AS VARCHAR)`.** Fixed under **Y-5**: `Expression.Concat`
  had already stopped recommending it (TY-23), but that message is unreachable — SQL's own coercion
  inserts the cast, so `ExpressionCompiler.cast()` answers first, and *that* message now says the
  cast written by hand is refused identically.
- **Eight messages naming a configuration key that does not exist.** **Stale**: PF-3 fixed all of
  them. `grep` over every `src/main` finds `arena.slab.size` and `state.slab.size` only inside
  comments recording the fix.
- **"Swap the inputs and use LEFT", which produces a second refusal.** Reproduced: a `RIGHT` or
  `FULL` join is refused with that advice, and taking it yields a `LEFT` join which is refused in
  turn for having no time bound. Two attempts where one message would have done. The message now
  says both steps in one sentence and gives the `BETWEEN` form that satisfies the second.

**Test.** Y-5's two tests cover the first; the LEFT-join wording is covered by the existing join
refusal assertions, which run over the changed message.

**Commit.** `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## C-7 — built and unreachable

**Verdict: half stale, half still true — and the half that is true is a debt list, not a defect.**

| Claim | Verdict |
|---|---|
| `pravaha-algebra` is referenced by no file outside itself | **Still true.** The only references outside the module are three tests: `OrphanedClassTest`, `ArchitectureRulesTest` and `KeyedAggregatePartialEquivalenceTest` |
| `AdaptiveStage` and `StageUpgradeService` reachable from no `src/main` | **Still true.** `StageUpgradeService` is on `OrphanedClassTest`'s own `KNOWN` list, with `Lift`, `Frontier`, `IncrementalJoin` and `Differentiate` |
| "`pravaha-embedded` has nine methods and cannot register or read a query" | **Stale, and by a long way.** `PravahaEngine` now declares `declareStream`, `bindSource`, `bindLookup`, `bindSink`, `declareQuery`, `register` (two forms), `query` (two forms), `subscribe` (two), `subscribeFromSnapshot` (two), `push` (three), `advanceEventTime`, `find`, `queries`, `streams`, `plugins`, `state`, `configuration`, `instanceId`, `start`, `stop` — the embedded engine was built after this finding was written |

**Left open, and here is what it needs.** What remains is a decision about `pravaha-algebra` and
four operators, and it is not a fix an agent should take on its own initiative:

- **`pravaha-algebra` is the Z-set model the engine's own answers are checked against** —
  `KeyedAggregatePartialEquivalenceTest` uses it as the oracle. That is a *use*, and a valuable
  one, but it is a test dependency, so the module is either "the specification, deliberately not on
  the runtime path" or dead code. Somebody has to say which, and the answer belongs in an ADR.
- **`Lift`, `Frontier`, `IncrementalJoin`, `Differentiate`, `StageUpgradeService`** are each either
  wired to something or deleted. Wiring `StageUpgradeService` is the interpreted→generated upgrade,
  which is a batch of its own; the other four are algebra classes and go with the decision above.

`OrphanedClassTest` already fails the build on a *new* orphan, so nothing here can get worse
silently. That is why this is a decision rather than a defect.

**Commit.** Verdict only, in `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## W-5 — corrections to earlier entries

**Verdict: both corrections verified accurate; the third claim beside them is now stale.**

| Claim | Verdict |
|---|---|
| `WindowSpec.slicesPerWindow()` is `S/gcd(S,D)`, not `S/D` | **Accurate.** `slicesPerWindow()` returns `sizeNanos / sliceSizeNanos()` and `sliceSizeNanos()` is `gcd(sizeNanos, slideNanos)` |
| The "wrapped signed-32-bit arena offset" does not correspond to code | **Accurate.** `SpscRowRing` refuses above 2 GB (`"an exchange ring of … exceeds 2 GB"`), `RowInbox` refuses above 2 GB (`"an inbox of … exceeds 2 GB"`), and `ArenaHandle` packs a slab index in the high 32 bits and a **per-slab** offset in the low 32, which cannot exceed a 4 MB slab |
| "The four silent-stop mechanisms surface with no PRV code beyond a thread-dump" | **Stale.** Arena exhaustion is `PRV-3001 RUNTIME_ARENA_EXHAUSTED`, backpressure is `PRV-3002 RUNTIME_BACKPRESSURED`, and an implausible window span is `PRV-3022 RUNTIME_WINDOW_SPAN_IMPLAUSIBLE`. B6 additionally publishes `pravaha_query_backpressure_waits`, `_wait_seconds`, `_blocked_fraction` and `pravaha_query_inbox_depth`, so a backpressure stall is now a number rather than a thread-dump |

**No code change.** This entry is a NOTE correcting two earlier entries, and the corrections are
right. Recorded here so the third claim is not quoted as current.

**Commit.** Verdict only, in `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## Y-8 — the honest coverage gap

**Verdict: accurate, and corroborated rather than closed.** `pravaha explain --level codegen`
emits generated Java only for a numeric-only projection: `FilterProjectGenerator.emitProjection`'s
`default` arm refuses a `STRING` with `PRV-3101`, so the fallback is what a query projecting a text
column reaches. API-F2 is the same fact seen from the case file.

**What changed under it this round, and what did not.** C-5's fix closes the largest *correctness*
hole the entry warns about — "would pass every case in this file while disagreeing on a narrow
integer or on `-0.0`" — for nulls specifically, and widens the differential property so a nullable
projected column is compared, which it never was. What remains true is the coverage statement:
the generated path still cannot project a `STRING`, so the interpreter/codegen comparison cannot be
run over text at all.

**Left open, and what it needs:** a `STRING` projection in the generator — a variable-width copy
into the output row's payload region, with the offset/length pair the layout expects — and then the
differential property widened to include a text column. That is a batch in `pravaha-codegen`, and
it is the batch that would let this entry be closed rather than corroborated a third time.

**Commit.** Verdict only, in `Event time that is a time: T-5, T-6, DOCX-6, and a feed that stops polling an empty file`.

---

## W8-14 — the windowed aggregate keys state by a digest

**Left open. Not started, and here is exactly what it needs.**

**Verdict on the state of it:** accurate and already narrowed by the entry itself. W8-8 removed the
64-bit fold in `WindowedAggregate.emitted`. What remains is `SlicedAggregateState`'s
`SliceKey(keyHigh, keyLow, sliceStart)` — 128 bits of hash with no comparison of the key values
behind it, on the per-row hot path, where a collision merges two groups' sums.

**What closing it needs.** `KeyedAggregate` one operator over already does the honest thing
(`record Key(Object[] values)`, `Arrays.equals`) and its javadoc says why. The windowed path cannot
simply copy that: it allocates a `SliceKey` per row per slice, and boxing a key's values there is
the allocation the slicing optimisation exists to avoid. So the work is:

1. carry the key's **bytes** beside the digest — the accumulator already carries the values for
   output, so the storage exists — and compare them on a digest hit;
2. keep the per-row path allocation-free, which means comparing against the stored row's key slice
   in the arena rather than materialising an `Object[]`;
3. a property test that constructs a genuine 128-bit collision (by feeding `SliceKey` directly, not
   by finding one) and requires the two groups' sums to stay apart.

**Why it is its own batch rather than a line here:** it is the windowed aggregate's hot path, the
change is a data-layout change in `SlicedAggregateState`, and the test that proves it needs a
constructed collision — none of which is separable from the rest of that operator.

---

## SRC-7 — `LutScanReader` buffers an entire scan on heap

**Left open. Verdict: accurate as read, and the entry says so itself — "read from the code, not
measured; the arithmetic below is arithmetic, not a measurement."** Confirmed by reading: `scan()`
collects the whole result into an `ArrayList<Record>` through a `found::add` callback and drains it
into an unbounded `ArrayDeque`; `poll(sink, maxRecords)` bounds what it *emits* and not what it
fills; no `maxRecords` is set on the `ScanPolicy`.

**What closing it needs.** The Aerospike client's scan callback is push-based, so bounding it means
either (a) `ScanPolicy.maxRecords` plus resumption from the last key — which changes the offset
model, because a resumed scan is a different scan — or (b) a bounded handover queue that blocks the
callback thread, which needs care: blocking inside an Aerospike callback holds one of its own
threads, and the reader must still honour `close()` without deadlocking. Either way it needs a
measurement against a real Aerospike (`AerospikeSourceScaleIT`, under Docker) to show the heap
ceiling actually holds, and a test that a resumed scan neither repeats nor skips.

**Why it is its own batch:** it is a change to the source's offset semantics, not only to its
buffering, and the proof is a container measurement.

---

## SPILL-3 — firing a large window builds it on the heap

**Left open, as its own disposition already says.** Nothing to add to the verdict: reproduced by
the beyond-RAM measurement (`496314e`), 1.6 M accumulators throw `OutOfMemoryError` against a
160 MiB heap with the spill tier on and off alike, and at 1 GiB the same window fires at about
375,000 groups a second.

**What closing it needs**, restating the disposition with what this round can add: fire in bounded
batches straight into the output rather than materialising the window first. Concretely,
`SlicedAggregateState.fire` currently builds one boxed handle per accumulator plus an entry per
group before emitting anything; it needs to walk its slot table in bounded runs, emit each run, and
release it — which interacts with the output arena's own sizing, because a run that is too large
exhausts the arena instead of the heap and a run that is too small pays a commit boundary per run.
That is the batch: a bounded fire, its interaction with the arena, and the beyond-RAM measurement
re-run to show the ceiling holds.
