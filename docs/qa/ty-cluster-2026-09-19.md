# TY cluster — verdicts, 2026-09-19

The eleven open `TY-` findings in [`FINDINGS.md`](FINDINGS.md), worked in id order. `FINDINGS.md`
itself is not edited here; this file is the evidence the lead needs to close or keep each one.

Starting point: the verdict and evidence line for each finding in
[`verification-2026-09-19.md`](verification-2026-09-19.md), which was current. Every one of the
eleven was reproduced again here before anything was changed, against a nine-column stream with one
column of each type the cluster touches.

Every finding was treated as a correctness question first. Four of the eleven could produce a wrong
answer under a success status (TY-4, TY-20, TY-22, TY-23); the rest were refusals that were correct
and unusable, which is a different problem and a smaller one.

| Finding | Verdict | Where |
|---|---|---|
| TY-4 | Reproduced, fixed (both halves) | `ExpressionCompiler.literal`, `ExpressionCompiler.caseWhen` |
| TY-5 | Reproduced, fixed | `PredicateCompiler.nullCheck`, `Predicate.IsNullExpression` |
| TY-8 | Reproduced, fixed | `DelimitedCodec.SCHEMA_MALFORMED` (`PRV-1027`) |
| TY-9 | Reproduced, fixed | `FilesystemSourcePlugin.parseSchema` |
| TY-10 | Reproduced, fixed | `TypeMapping.fromCalcite(type, columnName)` |
| TY-14 | Reproduced, fixed | `PredicateCompiler.refuseIncomparableColumn` |
| TY-16 | Reproduced, fixed | `PhysicalPlanBuilder.refuseNonNumericAggregate` |
| TY-20 | Reproduced, fixed | `SqlShapeRefusals` |
| TY-22 | Reproduced, fixed | `Expression.Substring.evaluateString` |
| TY-23 | Reproduced, fixed | `SqlShapeRefusals` |
| TY-24 | Reproduced; **partly fixed, left open** | `SqlPlanner.guidanceFor` |

Commits: `3106245f` (TY-4, TY-5, TY-14), `7b5138c3` (TY-10, TY-16), `22743bad` (TY-20, TY-23,
TY-24), `6313c28c` (TY-22), `76cf0a08` (TY-8, TY-9), `8dacec2a` (documents), `57a2a253` (a test
ordering so a seed proof lands on the finding's own shape), `eb864976` (two corrections to the
above, found reviewing the diff: see TY-5 and TY-24).

---

## TY-4 — two expression shapes crashed with a raw, uncoded Java exception

**Verdict: reproduced, both halves; fixed.** The finding's own update — a scientific-notation
literal inside a `WHERE` — no longer reproduces on its own; TY-13's fix to `Constant.OfLiteral`
closed the predicate path, and the projection path was still open. Both are now covered.

**Reproduction.** `SELECT id, r / 3.0E0 FROM types` →
`ClassCastException: class java.lang.Double cannot be cast to class java.math.BigDecimal`.
`SELECT CASE WHEN n > 5 THEN 1 ELSE 1.5 END FROM types` →
`IllegalArgumentException: a CASE must produce one type…`, unwrapped, no code.

**Cause.** (a) `ExpressionCompiler.literal` ended with `(BigDecimal) literal.getValue4()`. Calcite
carries an approximate literal as a `Double` when it is written with an exponent and as a
`BigDecimal` when it is written `3.0`; the cast assumed the second. An exponent is how a generated
query writes a double, so the crash was reachable from an ordinary division. (b) `caseWhen` built
`Expression.Case` from branches compiled independently and let the constructor's
`IllegalArgumentException` escape.

**Fix.** (a) An approximate literal is read with `getValueAs(Double.class)`, which handles both
spellings; a literal whose value is neither gets a coded refusal naming the class rather than a
class-cast trace. (b) `caseWhen` asks for the whole CASE's type first. Calcite types
`THEN 1 ELSE 1.5` as `DECIMAL`, exactly as it types `amount * 1.5`, so this routes to the decimal
refusal that already exists — following that precedent rather than inventing a second sentence. It
stays a refusal rather than a widening: a CASE that chose FLOAT64 would round the INT64 branch. The
constructor's exception is wrapped as a coded refusal as the net under that check.

**Test.** `TypeClusterTest.ty4_aDoubleLiteralWrittenWithAnExponentDividesRatherThanThrowingAClassCastException`,
`TypeClusterTest.ty4_aCaseWhoseBranchesDisagreeIsRefusedWithACodeRatherThanAnIllegalArgumentException`.
The first asserts the computed values, and that `r / 3.0E0` and `r / CAST(3.0 AS DOUBLE)` divide to
the same numbers — planning is not the claim, arithmetic is.

**Seed-proof.** (a) Removing the approximate branch: 2 of 9 tests fail, `PRV-2021 literal
'3.0E0:DOUBLE' … is carried as a Double`. (b) Removing the up-front `typeOf`: 1 of 9 fails, the
refusal reverting to the wrapped branch-mismatch message with an `IllegalArgumentException` cause.
Restored, 9/9 green.

---

## TY-5 — `WHERE (CASE … END) IS NULL` was refused

**Verdict: reproduced; fixed.**

**Reproduction.** `SELECT id FROM types WHERE (CASE WHEN n > 0 THEN tag ELSE 'x' END) IS NULL` →
`PRV-2021 cannot compile the expression 'IS NULL(CASE(...))' (IS_NULL)`.

**Cause.** `PredicateCompiler.nullCheck` required a `RexInputRef` and refused every other shape, so
the ordinary way to ask whether a computed value came out empty did not plan while the same CASE in
the `SELECT` list did. A second cause sat behind it: Calcite wraps the CASE's text branches in
`CAST($4):VARCHAR CHARACTER SET "UTF-8"` to settle a charset, and `ExpressionCompiler.cast` refused
that as "converts between STRING and STRING".

**Fix.** `Predicate.IsNullExpression` asks the compiled expression its own `isNull`, which the
expression tree has answered for every node since it was written. The column form stays its own
predicate, because the code generator emits it as one bitmap test; the generator refuses the new
one, as it already refuses `CompareExpressions`, so the query runs interpreted rather than through
two specifications of null propagation. `SourcePushdown` treats it as unattributable, which is the
conservative direction (a filter not pushed costs rows, a filter wrongly pushed loses them).

A cast of a type onto itself now passes through, with two guards. Identity is decided on Calcite's
types, so `CAST(s AS VARCHAR(3))` — which has to truncate, and this engine does not — is not
identity and stays refused rather than passing through and silently not truncating. And the type
still has to be one the expression tree can carry: a `DECIMAL` cast onto itself converts nothing
either, and the first version of this let it through, which would have put a 128-bit decimal behind
an expression that reads it as a long. Caught reviewing the diff, not by a test, and fixed in
`eb864976`.

**Test.** `TypeClusterTest.ty5_isNullOverAComputedCaseIsAnsweredRatherThanRefused`. It asserts the
rows, and that `IS NULL` and `IS NOT NULL` partition them: a null check that is not total is a
wrong answer, not a missing one.

**Seed-proof.** Restoring the `RexInputRef` requirement: 1 of 9 fails with the original message.
Restored, green.

---

## TY-8 — a schema-string mistake on `POST /api/v1/streams` returned HTTP 500

**Verdict: reproduced; fixed.**

**Reproduction.** `StreamController.register("d", "id:INT64,amt:DECIMAL")` throws
`ConfigurationException(PRV-5040)`, and `ApiExceptionHandler.statusFor` maps the PLUGIN category to
`INTERNAL_SERVER_ERROR`.

**Cause.** The `name:TYPE,name:TYPE` parser lives in the filesystem plugin and used that plugin's
`PRV-5040 FILESYSTEM_DECODE_FAILED`. The handler derives its status from the code's *category*,
which is the right design and is not what was wrong; the code was in the wrong series.

**Fix.** `PRV-1027 CONFIG_SCHEMA_MALFORMED`. A schema string is configuration on every surface that
writes one — `pravaha.streams.*.schema`, `--schema`, `--out-schema`, a plugin's `schema` option,
the REST body — so it belongs in the 1xxx series and answers `400`. `PRV-5040` still means a line
of data a file could not decode, and every site that raises it for that reason is untouched.

The constant is declared in `DelimitedCodec` rather than `ConfigErrors`, with the number reserved
in a comment there: a plugin depends on `pravaha-api` and nothing else, so it cannot see
`pravaha-common`'s registry. 1027 rather than 1030 because 1030 is already
`CLIENT_MALFORMED_ENDPOINT` in the Java SDK.

**Test.** `FilesystemPluginTest.ty8_anUnparseableSchemaIsAConfigurationCodeAndNotAPluginOne` (the
code and its category, at the parser every surface shares) and
`SchemaRefusalStatusTest.ty8_aMisspelledTypeInTheRequestBodyIsFourHundredAndNotFiveHundred` (the
status a caller sees). The second file keeps a row-decode failure as the control: it is still a
500, which is why this was not fixed by mapping the whole PLUGIN series to 400.

**Seed-proof.** Pointing `parseSchema` back at `DECODE_FAILED`: the plugin test fails on the
category, and the server test fails on `BAD_REQUEST`. Restored, both green.

---

## TY-9 — the node-startup type refusal did not name the stream or column

**Verdict: reproduced; fixed.**

**Reproduction.** `FilesystemSourcePlugin.parseSchema("d", "id:INT64,amt:DECIMAL")` →
`unknown type 'DECIMAL'. Supported: …`, naming neither `d` nor `amt`.

**Cause.** `parseSchema` holds both names at the point of failure and printed neither.

**Fix.** The refusal reads `stream 'd', column 'amt': unknown type 'DECIMAL'. …`. Fixed in the
parser rather than in `PravahaNode`, because the parser is what every surface shares — the node's
YAML, the CLI's `--schema`, the REST body and a plugin's own option all get it.

**Test.** `FilesystemPluginTest.ty9_anUnparseableSchemaNamesTheStreamAndTheColumn`, covering both
halves of the grammar (an unknown type, and an entry with no colon).

**Seed-proof.** Removing both prefixes: 1 of 34 fails. Restored, green.

---

## TY-10 — the `ARRAY`/`MAP`/`ROW` refusal named neither the type nor the column

**Verdict: reproduced; fixed.**

**Reproduction.** `SELECT tags FROM types` → `PRV-2021 no Pravaha type for SQL type ANY; the
supported set is in TypeMapping`.

**Cause.** `TypeMapping.baseFromCalcite` had only the Calcite type; its callers in
`PhysicalPlanBuilder.schemaOf` were walking a row type field by field and held the name.

**Fix.** `fromCalcite(RelDataType, String columnName)`. The message names the column and says which
Pravaha types arrive as `ANY`, because `ANY` is Calcite's word for all three and not a word the
person wrote. This is the pattern `ArrowSchemas` already uses for the equivalent wire refusal.

**Test.** `TypeClusterTest.ty10_projectingAnArrayColumnNamesTheColumnAndSaysWhichTypesArriveAsAny`,
with a control that the columns beside it still plan — which is what makes naming the column worth
anything.

**Seed-proof.** Dropping the column prefix: 1 of 9 fails. Restored, green.

---

## TY-14 — a BYTES-vs-literal refusal named no column

**Verdict: reproduced; fixed.**

**Reproduction.** `WHERE bin = 'cafe'` → `PRV-2021 'CAST('cafe'):VARBINARY NOT NULL' has SQL type
VARBINARY, which Pravaha cannot compute with yet`.

**Cause.** BYTES maps to a real Calcite `VARBINARY`, so Calcite inserts an implicit cast and the
generic expression refusal fires on it — naming a cast the person did not write. ARRAY maps to
`ANY`, takes no implicit cast, and reached `compare`'s column-naming branch. Two type families
refusing the same mistake two different ways.

**Fix.** `PredicateCompiler.comparison` checks both operands for a column of a type it cannot
compare — BYTES, ARRAY, MAP, ROW — before anything else, and refuses naming the column and the
type. The refusal itself is unchanged in substance: comparing a BYTES column is not built, and a
string literal is not a byte string.

**Test.** `TypeClusterTest.ty14_comparingABytesColumnNamesTheColumnAsTheArrayCaseAlreadyDid`, over
`=`, `<>` and the flipped operand order, with the ARRAY spelling as the control so this is one
message and not two.

**Seed-proof.** Removing both guard calls: 1 of 9 fails on the finding's own statement. Restored,
green.

---

## TY-16 — `SUM`/`AVG` over text was refused by the wrong code

**Verdict: reproduced; fixed.**

**Reproduction.** `SELECT SUM(s) FROM types` → `PRV-2021 'CAST($1):DECIMAL(38, 19) NOT NULL' is
DECIMAL arithmetic … a rounding error in a ledger`.

**Cause.** SQL does not reject a text operand to `SUM`: it inserts `CAST(s AS DECIMAL(38,19))`
underneath and leaves the refusal to whoever meets the cast, which was the decimal-arithmetic
guard. The person who asked for the sum of a text column was told about a cast they had not
written, and never told that summing text was the problem.

**Fix.** `PhysicalPlanBuilder.refuseNonNumericAggregate`, run *before* the aggregate's input is
built — because the input **is** the cast, so a check that runs afterwards never runs at all. The
argument ordinal is resolved through the `Project` underneath, stripping casts, so the column named
is the one in the query. `PRV-2020`, the same code `refuseFloatingPointAggregate` uses, because it
is the same kind of answer: the accumulator takes a number and this operand is not one. Following
that precedent was the instruction and is also the right call — a second code would be a second
thing to look up.

**Test.** `TypeClusterTest.ty16_sumOverTextIsRefusedAsAnAggregateOverTextRatherThanAsDecimalArithmetic`,
asserting the code, the column, and the *absence* of the ledger paragraph; with `SUM`, `COUNT`,
`MIN`/`MAX` over numbers and the float refusal as controls, so this did not widen into "aggregates
are refused".

**Seed-proof.** Removing the call: 1 of 9 fails. Restored, green.

---

## TY-20 — `ORDER BY` inside a non-limited derived table planned and ran

**Verdict: reproduced; fixed.**

**Reproduction.** `SELECT * FROM (SELECT id FROM types ORDER BY id) x` planned to a
`ProjectOperator`, exit 0.

**Cause.** A sort inside a derived table with no `FETCH` cannot change the answer, so Calcite drops
it before a `Sort` node reaches `PhysicalPlanBuilder`. The refusal was a property of the plan's
shape rather than a promise about SQL — it did not fire, and the person was told nothing.

**Fix.** `SqlShapeRefusals`, a walk of the statement between validation and optimisation. After
validation, so a genuine syntax or name error is reported first; before optimisation, because the
optimiser is what deletes the evidence. `PRV-2020`, and the message now names the clause instead of
saying "the planner produced a LogicalSort".

**Test.** `TypeClusterTest.ty20_orderByIsRefusedWhicheverShapeItIsWrittenIn`, six shapes with the
finding's own leading, plus a control that a window's own `ORDER BY` is still refused for its own
reason and that the same queries without the sort still plan.

**Seed-proof.** Removing the two ORDER BY branches: 1 of 9 fails —
`expected a coded refusal, and the statement planned: SELECT * FROM (SELECT id FROM types ORDER BY
id) x`, which is the finding verbatim. Restored, green.

---

## TY-22 — `SUBSTRING(… FOR <near Long.MAX_VALUE>)` returned an empty string

**Verdict: reproduced; fixed.** The most dangerous of the eleven: a wrong value in a row under exit
0, with nothing logged.

**Reproduction.** `SUBSTRING('hello' FROM 1 FOR 9223372036854775807)` → `""`.

**Cause.** `until = from + Math.max(0L, length)` in `long`, under a comment claiming it was
overflow-safe. `1 + Long.MAX_VALUE` wraps to `Long.MIN_VALUE`, the range comes out empty, and the
whole value is replaced by the empty string. `FOR <the largest length>` is how a generated query
spells "the rest of the string".

**Fix.** Saturate at `Long.MAX_VALUE`, which is the arithmetic the old comment described: a window
running past the end of the string is clamped to the end one line below, as the standard asks. The
`FROM`-only form goes through the same saturation rather than its own special case, so the two
spellings of "to the end" cannot drift apart.

**Test.** `SubstringRangeTest`, a property test against an on-heap model rather than examples,
because the defect lived at the arguments nobody picks by hand. The model computes the standard's
own set — the characters at 1-based positions `p` with `start <= p < start + length` that the string
has — in `BigInteger`, where the arithmetic cannot wrap, so a model that agreed with the bug is not
possible. 13 edge values crossed with 13 over 7 subjects (including a surrogate pair), 20,000 seeded
random pairs, and the finding's own two cases named.

**Seed-proof.** Restoring the wrapping addition: 2 of 4 tests fail, first at
`SUBSTRING('a' FROM 1 FOR 9223372036854775807)`. Restored, 4/4 green.

---

## TY-23 — `||` accepted a number written as a literal and refused it written as a column

**Verdict: reproduced; fixed. One documented behaviour is narrowed as a result, deliberately.**

**Reproduction.** `SELECT s || 5 FROM types` and `SELECT s || CAST(5 AS VARCHAR) FROM types` both
planned; `SELECT s || n FROM types` was refused `PRV-2021`.

**Cause.** SQL's own type coercion inserts the cast for the first, and the optimiser constant-folds
it away for both, before the compiler's text-only check sees anything. So whether a number could
become text depended on how it was spelled.

**Fix.** `SqlShapeRefusals` refuses a cast to a character type whose operand is a non-text literal.
One rule covers both halves, which is worth stating because it is not obvious: validation rewrites
the parse tree **in place**, so by the time the walk runs, `s || 5` and `s || CAST(5 AS VARCHAR)`
are the same tree — which is exactly the claim the finding makes about them. `CAST(NULL AS VARCHAR)`
is left alone: it converts nothing, and it is the rewrite the bare-`NULL` refusal recommends, so
refusing it would be advice that fails.

**The narrowing.** `CASE WHEN amount > 5 THEN 'big' ELSE 0 END` answered `"0"` and was recorded as
working in two matrices (`SQLX-044`) and in `CONTINUOUS_QUERIES`. The same CASE with a numeric
*column* in the other branch (`ELSE amount`) was refused. That is TY-23 in a different clause, and
the accepted half was the wrong one; both refuse now, and both matrices carry the refusal with the
reason. This is a behaviour change beyond the finding's text and is called out here so the lead can
disagree with it in one place.

Also fixed: `Expression.Concat`'s type check advised "Wrap it in `CAST(… AS VARCHAR)`", which is
refused by the same compiler for the same reason — advice that sends the reader in a circle, the
HLP-14 pattern.

**Test.** `TypeClusterTest.ty23_aNumberIsRefusedInTextWhetherItIsWrittenAsALiteralOrAsAColumn`, five
shapes plus the column form as the reference, with text concatenation and `CAST(NULL AS VARCHAR)`
as controls.

**Seed-proof.** Disabling the rule: 1 of 9 fails —
`expected a coded refusal, and the statement planned: SELECT s || 5 FROM types`. Restored, green.

---

## TY-24 — refusals intercepted by Calcite's validator

**Verdict: reproduced for four of five sub-cases; the fifth no longer reproduces. Partly fixed and
LEFT OPEN — the rest is its own batch.**

**What reproduces.** `ABS(d,1)` and `ROUND(d,2,1)` → Calcite's arity check, `PRV-2002`.
`LTRIM`/`RTRIM` (and `CONCAT`) → Calcite's unknown-function check, `PRV-2002`. `CAST(<bool> AS
INTEGER)` → Calcite's cast-type check, `PRV-2002`. `FLOOR(d,1)` → a parse error, `PRV-2001`.

**What does not.** `CAST(<int> AS BOOLEAN)` is no longer refused at all. Calcite rewrites it to
`n <> 0`, and TY-11's fix gave a boolean-valued call a compiled path, so it plans and answers —
correctly, and with PostgreSQL's semantics for that cast. Pinned as an answer in
`TypeClusterTest.ty24_…`, because a sub-case that changed from a refusal to a result without anyone
noticing is worth a test either way.

**What is fixed.** `SqlPlanner.guidanceFor` appends this engine's own sentence to the validator's,
naming what *is* evaluated. It is correct whichever refusal won, costs nothing, and is pinned by a
test — so a Calcite upgrade that rewords one of these fails the build rather than quietly dropping
the sentence. `FLOOR(x, n)` gets nothing: multi-argument `FLOOR` is reserved SQL syntax, so the
parser stops before any validator or engine sees a function call, and no engine-level message can
precede the parser.

**What is left, and what it needs.** Making Pravaha's own message *win* the race means supplying
Calcite with a custom `SqlOperatorTable` and cast checker:

1. A Pravaha operator table composed over `SqlStdOperatorTable`, declaring the functions this engine
   intends to refuse by name (`LTRIM`, `RTRIM`, `CONCAT`, and the rest of the "refused" rows in
   `CONTINUOUS_QUERIES.md` §11) so that they validate and are then refused by `ExpressionCompiler`
   with its own sentence. Note that the finding cites `docs/SQL_SUPPORT.md` for that list; that
   file no longer exists, and §11 is where the list lives now.
2. Pravaha's own `ABS`/`ROUND`/`FLOOR`/`CEIL` operators with their own operand checkers, because the
   arity check belongs to the standard operator's `SqlOperandTypeChecker` and cannot be overridden
   without replacing the operator.
3. A decision on `CAST`: Calcite's cast-type check runs in the validator, and moving it means either
   a custom `SqlTypeCoercion` or accepting the cast at validation and refusing it in the compiler —
   the second widens what validates, which needs its own review.

That is a batch: it changes what the validator accepts, which is the widest blast radius in the SQL
layer, and every one of TY-24's sub-cases is already a clean coded refusal at exit 1. Half-building
it — a custom table for two functions — would leave the operator table inconsistent with itself,
which is worse than the message quality it would buy.

**Test.** `TypeClusterTest.ty24_aRefusalCalciteMakesFirstStillSaysWhatThisEngineEvaluates`.

**Seed-proof.** Disabling `guidanceFor`'s function branch: 1 of 9 fails on `SELECT LTRIM(s) FROM
types`. Restored, green.
