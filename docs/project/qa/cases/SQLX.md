# SQLX — the documented SQL surface, checked against answers rather than plans

**Area:** `SQLX` · **IDs:** SQLX-001 … SQLX-190 · **Budget:** 190 · **Status:** authored, not executed.

## Why this area exists in the shape it does

`docs/SQL_SUPPORT.md` opens with a claim:

> Every construct below is **checked by a test**, not by someone's memory:
> [`SqlSupportMatrixTest`] plans each statement in this page, builds it, **and compiles it into a
> runnable pipeline**. If a construct starts working, or stops, the build fails and names this file.

The claim is true about *planning* and false about *answers*. `SqlSupportMatrixTest.messageOf`
compiles the pipeline with

```java
InterpretedPipeline.compile(plan, () -> {
    throw new UnsupportedOperationException("the matrix builds pipelines but never runs rows through them");
})
```

— the row output is a bomb, so **no row is ever fed and no value is ever compared**. `outcomeOf`
then reduces every statement to the string `"OK"` or the first eight characters of a `PRV-` code.
A construct can therefore plan, build, compile, be recorded as ✅ on that page, and compute the
wrong number with the build green. That is finding **Q-8**, still **OPEN**, and round 1 proved it is
not hypothetical: **Q-2** (every float aggregate returned zero rows under a success status),
**Q-3** (`COUNT(col)` counted nulls), **Q-4** (`ROUND(2.5) = 2`), **Q-7** (overflow dropped rows
silently) all lived under a green matrix.

So the rule for this file: **a supported construct is checked by an answer, not by an outcome
string.** Every ✅ case below names rows and numbers. Every ❌ case checks four separate things —
that it is refused, with the documented `PRV-` code, **at plan time rather than at the first row**,
and with a message a user can act on.

### What is being covered

`docs/SQL_SUPPORT.md` has **77 table rows**: 64 carry ✅ or ❌ (42 ✅, 22 ❌), 7 are the error-code
table, 6 are table headers. All 64 constructs and all 7 codes appear below. Where the document and
the code disagree, the case pins the disagreement rather than choosing a side.

---

## The harnesses

Four, and which one a case uses is load-bearing rather than a convenience.

| | What | What it proves that the others cannot |
|---|---|---|
| **H-VAL** | `pravaha validate --sql <q> --schema <S> [--stream txn]` | Plans and stops. It opens no file and reads no row, so a refusal here **cannot** be "at the first row". Every ❌ case runs through H-VAL first. |
| **H-RUN** | `pravaha run --sql <q> --stream txn --schema <S> --in in.csv --out out.csv --out-schema <O>` | Plans **and executes** over a real file on the real lane runtime. This is where answers come from. Note `QueryRunner.run` does **not** call `overBoundedInput()`, so a finite file is a **stream** here — that asymmetry is itself SQLX-119. |
| **H-VIEW** | a server with `pravaha.streams.txn` bound to the filesystem source over `in.csv`, `pravaha register --name v_txn --sql-file q.sql` where `q.sql` is `SELECT * FROM txn`, then `pravaha query --sql "<q over v_txn>"` | The **bounded** read path: `ViewQuery.physicalOf` calls `.overBoundedInput()`. This is the only surface on which `SELECT DISTINCT` and an unwindowed keyed `GROUP BY` are legal. |
| **H-MTX** | a JUnit harness in the shape of `SqlSupportMatrixTest.messageOf`, but with a **collecting** `RowOutput` instead of the throwing one: `new PhysicalPlanBuilder().build(SqlPlanner.withStreams(TXN, OTHER, THIRD).plan(sql))` → `InterpretedPipeline.compile(plan, collector::begin)` → feed **D1** → assert on `collector.rows()`. | In-process, no server, no file codec. Used where a case needs a weight, a type, or a row count the CLI cannot express. It is also the thing §10 asks the project to adopt. |

**A refusal case that is only run through H-RUN is reported `INCONCLUSIVE`, not `PASS`.** H-RUN
plans before it reads, so it cannot distinguish a plan-time refusal from a first-row one.

## Standing fixtures

### Fixture S — the stream schema

Deliberately byte-identical to `SqlSupportMatrixTest.TXN`, so any case here can be transplanted into
that test without a second schema to keep in step:

```
--stream txn
--schema 'txn_id:INT64,user_id:STRING,amount:INT64,price:FLOAT64,status:STRING?,flagged:BOOLEAN,event_time:TIMESTAMP'
```

`status` is the only nullable column. `FilesystemSourcePlugin` decodes an empty field as NULL
(`null.literal` defaults to the empty string) and `TIMESTAMP` as a plain `Long.parseLong` of
nanoseconds — `DelimitedCodec` line 117, `case INT64, TIME, TIMESTAMP_LTZ -> Long.parseLong(raw)`.

### Fixture D1 — `in.csv`, six rows, comma-delimited, no header

```
1,u1,100,2.5,ok,true,1000000000
2,u2,250,4.0,,false,2000000000
3,u1,-50,1.0,ok,false,3000000000
4,u3,0,0.5,flagged,true,4000000000
5,u2,7,1.5,ok,false,12000000000
6,ünïcødé,7,0.25,ok,true,13000000000
```

Row 2's fifth field is empty: `status` is NULL there and nowhere else.
Event times are 1s, 2s, 3s, 4s, 12s, 13s (1 s = 1 000 000 000 ns).

**Hand-computed truths for D1, used throughout and computed once here:**

| | |
|---|---|
| `COUNT(*)` | 6 |
| `COUNT(status)` | 5 — row 2 is NULL. **This is the Q-3 discriminator: 5 ≠ 6.** |
| `SUM(amount)` | `100 + 250 + (-50) + 0 + 7 + 7 = 314` |
| `MIN(amount)` / `MAX(amount)` | `-50` / `250` |
| `AVG(amount)` | `314 / 6 = 52` remainder `2` — 52 under integer division, 52.333… under floating |
| distinct `user_id` | `{u1, u2, u3, ünïcødé}` = 4 |
| distinct `status` | `{ok, NULL, flagged}` = 3 |
| `status IS NULL` | 1 row (r2) |
| `status = 'ok'` | 4 rows (r1, r3, r5, r6) |
| `flagged` | 3 rows (r1, r4, r6) |
| `amount > 100` | 1 row (r2, 250) |
| `amount BETWEEN 1 AND 9` | 2 rows (r5, r6 — both 7) |
| `user_id IN ('u1','u2')` | 4 rows (r1, r2, r3, r5) |
| **W1** = TUMBLE 10 s over `[0s, 10s)` | r1, r2, r3, r4 → `COUNT 4`, `SUM 100+250-50+0 = 300`, `MIN -50`, `MAX 250`, `COUNT(DISTINCT user_id) = |{u1,u2,u3}| = 3` |
| **W2** = TUMBLE 10 s over `[10s, 20s)` | r5, r6 → `COUNT 2`, `SUM 7+7 = 14`, `MIN 7`, `MAX 7`, `COUNT(DISTINCT user_id) = |{u2,ünïcødé}| = 2` |
| W1 keyed by `user_id` | u1 → `SUM 100 + (-50) = 50`, `COUNT 2`; u2 → `SUM 250`, `COUNT 1`; u3 → `SUM 0`, `COUNT 1` |
| W2 keyed by `user_id` | u2 → `SUM 7`, `COUNT 1`; ünïcødé → `SUM 7`, `COUNT 1` |

### Fixture D2 — `edge.csv`, the boundary and hostile values

Same schema S. Used only where D1's values are too polite:

```
7,u1,9223372036854775807,1.7976931348623157E308,,false,0
8,u1,-9223372036854775808,-1.7976931348623157E308,,true,9999999999
9,,0,0.0,,false,5000000000
10,😀x,1,NaN,ok,true,6000000000
11,  padded  ,2,0.49999999999999994,  sp  ,false,7000000000
```

Row 9's `user_id` is the empty string. `FilesystemSourcePlugin` cannot represent an empty
string distinctly from NULL on a non-nullable column (round-1 SQL-028): `user_id` is declared
non-nullable, so this must decode as `''`. If it decodes as NULL on a non-nullable column, that is a
finding in itself and the case that touches it says so.

### Fixture S2 — every type in a WHERE clause

For SQLX-049…060 only. `TYPE` owns types exhaustively; SQLX owns them **in a predicate**:

```
--stream allt
--schema 'b:BOOLEAN,i8:INT8,i16:INT16,i32:INT32,i64:INT64,f32:FLOAT32,f64:FLOAT64,s:STRING,bin:BYTES,d:DATE,tm:TIME,ts:TIMESTAMP'
```

`all.csv`, three rows:

```
true,1,100,1000,10000,1.5,2.5,alpha,QQ==,19000,3600000,1000000000
false,-1,-100,-1000,-10000,-1.5,-2.5,beta,Ug==,19001,7200000,2000000000
true,0,0,0,0,0.0,0.0,gamma,Uw==,19002,0,3000000000
```

### Fixture V — the view

`H-VIEW`'s `v_txn` is `SELECT * FROM txn`, so it carries all seven columns of S with D1's six rows
and D1's truths hold over it unchanged. **A view shows its committed frontier**, so every H-VIEW
case waits for `pravaha queries` to report `v_txn` with `rowsOut = 6` before reading. A case that
reads early and finds fewer rows is `INCONCLUSIVE`, not `FAIL`.

## Vacuity kit — the three controls

Round 1 shipped a row-count assertion that passed while 200 000 rows collapsed into 500. These run
in the **same command or the same process** as the case they guard, and a case that cannot show all
three that apply to it is reported `INCONCLUSIVE`.

- **C1 — the rows arrived.** `pravaha run` prints `ok  6 in, N out`. The `6 in` is asserted
  separately from the contents of `out.csv`. A case expecting zero output rows is meaningless
  without it — round 1's Q-2 returned zero rows under a success status for exactly this reason.
- **C2 — the complement.** Every filter case runs its complement over the same fixture in the same
  session. `kept + dropped` must equal 6, **except** where three-valued logic is the point, and
  there the shortfall is the assertion (SQLX-079).
- **C3 — the refusal is at plan time.** Every ❌ case is run through **H-VAL**, which opens no file.
  A refusal that only appears under H-RUN is a first-row refusal and fails the case's own claim.

---

## §1 — Projection: what `SELECT` may contain

### SQLX-001 — `SELECT *` returns every column, in schema order, with every value intact
**Intent:** The most-used construct in the document's first table, and the one whose breakage is
least likely to be noticed by a plan-shaped test: `*` expands correctly and still loses a column's
value.
**Falsifier:** `out.csv` has other than 7 columns or 6 rows, or any field differs from `in.csv`.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT * FROM txn`, `--out-schema` identical to `--schema`.
**Expected:** `ok  6 in, 6 out`. `out.csv` equals `in.csv` byte for byte, including row 2's empty
fifth field and row 6's `ünïcødé`.
**Vacuity:** C1. A projection that dropped every row would also produce a 0-byte file that a
"columns match" assertion would pass.

### SQLX-002 — a two-column projection emits exactly those two, in the order written
**Intent:** `ProjectOperator` is built from ordinals (`PhysicalPlanBuilder.buildProject`); an
off-by-one in that list yields plausible values from the wrong column.
**Falsifier:** three columns, or `user_id` values under the `amount` heading.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT txn_id, amount FROM txn`, `--out-schema 'txn_id:INT64,amount:INT64'`.
**Expected:** `1,100 / 2,250 / 3,-50 / 4,0 / 5,7 / 6,7`, six rows.
**Vacuity:** C1 (`6 in, 6 out`).

### SQLX-003 — a projection in an order the schema does not have
**Intent:** The ordinal list must follow the SELECT list, not the schema. SQLX-002 cannot catch a
"project columns 0..n in schema order" bug because it asks for them in schema order.
**Falsifier:** output is `txn_id,amount` rather than `amount,txn_id`.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT amount, txn_id FROM txn`, `--out-schema 'amount:INT64,txn_id:INT64'`.
**Expected:** `100,1 / 250,2 / -50,3 / 0,4 / 7,5 / 7,6`.
**Vacuity:** C1.

### SQLX-004 — `AS` renames the output column and nothing else
**Intent:** Documented ✅ ("Column aliases — `amount AS a`"). The alias must reach the output schema,
which is what a client displays.
**Falsifier:** `pravaha validate` reports the output column as `amount`, or `EXPR$0`.
**Setup:** S.
**Steps:** H-VAL, `SELECT amount AS a FROM txn`. Then H-RUN the same with `--out-schema 'a:INT64'`.
**Expected:** `valid   output: [a INT64 NOT NULL]`; six rows `100 / 250 / -50 / 0 / 7 / 7`.
**Vacuity:** C1 on the run half.

### SQLX-005 — an alias without `AS`
**Intent:** Documented ✅ "With or without `AS`". Two syntaxes, one meaning; the matrix has the
table-alias form and not the column-alias form.
**Falsifier:** `PRV-2001`, or an output column named `amount`.
**Setup:** S.
**Steps:** H-VAL, `SELECT amount a FROM txn`.
**Expected:** identical to SQLX-004: `[a INT64 NOT NULL]`.
**Vacuity:** not applicable — H-VAL plans and stops; no state, no timing.

### SQLX-006 — a qualified column produces a **bare** output name
**Intent:** The document states it explicitly: "`SELECT t.amount` produces a column called `amount`,
not `t.amount`". A client keying on the column name breaks if this drifts.
**Falsifier:** the output schema names the column `t.amount`.
**Setup:** S.
**Steps:** H-VAL, `SELECT t.amount FROM txn AS t`.
**Expected:** `[amount INT64 NOT NULL]`.
**Vacuity:** not applicable — no state, no timing.

### SQLX-007 — `t.*` expands to exactly what `*` does
**Intent:** Documented ✅. Two spellings must agree; the matrix asserts each plans and never that
they agree.
**Falsifier:** a different column count or order from SQLX-001.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT t.* FROM txn AS t`, `--out-schema` = `--schema`.
**Expected:** `out.csv` identical to SQLX-001's, byte for byte.
**Vacuity:** C1.

### SQLX-008 — an unqualified column while an alias is in scope
**Intent:** Documented ✅: "columns may be written qualified or bare while an alias is in scope".
**Falsifier:** `PRV-2002` "Column 'txn_id' not found".
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT txn_id FROM txn AS t`, `--out-schema 'txn_id:INT64'`.
**Expected:** `1,2,3,4,5,6` on six lines. `ok  6 in, 6 out`.
**Vacuity:** C1.

### SQLX-009 — an alias shadowing another registered stream's name
**Intent:** `Case.ok("alias shadowing another stream's name", …)` is in the matrix as a plan. The
answer matters more: if the shadow leaked, the query would read `other` and return its rows.
**Falsifier:** the run returns rows from a stream other than `txn`, or zero rows.
**Setup:** H-MTX with `TXN`, `OTHER`, `THIRD` registered; feed D1 to `txn` only.
**Steps:** `SELECT other.txn_id FROM txn AS other`, fed D1.
**Expected:** six rows, `1..6` — D1's `txn_id` values. **Not** rows from the `other` stream, which
has no `txn_id` column at all.
**Vacuity:** C1 in the H-MTX form — assert `rowsIn == 6` on the collector. Zero rows out with zero
rows in would pass a "no rows from `other`" assertion trivially.

### SQLX-010 — two output columns with the same name, both aliased to it
**Intent:** SQL permits duplicate output names; `StreamSchema.Builder` may not. Nothing in the
matrix asks.
**Falsifier:** an internal exception, or an output with one column instead of two.
**Setup:** S.
**Steps:** H-VAL, `SELECT amount AS a, txn_id AS a FROM txn`.
**Expected:** either `[a INT64 NOT NULL, a INT64 NOT NULL]` and a run producing `100,1 / 250,2 / …`,
**or** a `PRV-` refusal naming the duplicate. A raw `IllegalArgumentException` or an
`IllegalStateException` out of `StreamSchema.Builder` is a **FAIL**: a duplicate column name is a
query-shaped mistake and must carry a code.
**Vacuity:** not applicable — no state, no timing. The two outcomes are distinguished by the
exception type, so neither can pass by the query never running.

### SQLX-011 — the same column selected twice, unaliased
**Intent:** Calcite disambiguates to `amount` and `amount0`, or does not. The document's naming
paragraph ("an output column takes … the column's own name") does not say what happens when two
columns claim it.
**Falsifier:** a value appears once, or the two columns hold different values.
**Setup:** S, D1.
**Steps:** H-VAL then H-RUN, `SELECT amount, amount FROM txn`, `--out-schema 'a:INT64,b:INT64'`.
**Expected:** two columns, six rows, each row's two fields equal: `100,100 / 250,250 / -50,-50 /
0,0 / 7,7 / 7,7`. Record the names H-VAL reports; they are the answer to the document's silence.
**Vacuity:** C1.

### SQLX-012 — duplicate column names arriving from two sides of a join
**Intent:** `schemaOf(join, left + "_" + right)` builds one flat schema from both row types.
`txn.user_id` and `other.user_id` collide in it.
**Falsifier:** the joined output has one `user_id` where it should have two, or the second one holds
the first's values.
**Setup:** H-MTX, `TXN` and `OTHER` registered. Feed `txn` D1; feed `other` one row
`u1,emea,1000000000`.
**Steps:** `SELECT t.user_id, o.user_id FROM txn t JOIN other o ON t.user_id = o.user_id`.
**Expected:** two rows (r1 and r3 are the `u1` rows), each `u1,u1`. Two output columns, not one.
**Vacuity:** C1 — assert the join saw 6 left rows and 1 right row. A join that dropped both sides
would emit nothing and pass "no wrong values".

### SQLX-013 — an integer literal in the select list
**Intent:** Documented ✅ ("Literals — `SELECT 1`"). One literal per row, six rows — not one row.
**Falsifier:** one row, or a column of `0`.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT 1 FROM txn`, `--out-schema 'one:INT32'`.
**Expected:** six lines, each `1`. `ok  6 in, 6 out`.
**Vacuity:** C1.

### SQLX-014 — a string literal in the select list
**Intent:** Documented ✅ ("String literals — `SELECT 'flagged'`"). `ExpressionCompiler.literal` uses
`getValue2` specifically so the apostrophes do not land in the row; that is a real bug it is
guarding against and nothing runs a row to check.
**Falsifier:** `out.csv` contains `'flagged'` with quotes.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT 'flagged' FROM txn`, `--out-schema 'tag:STRING'`.
**Expected:** six lines, each exactly `flagged` — seven characters, no apostrophes.
**Vacuity:** C1.

### SQLX-015 — `SELECT NULL`
**Intent:** `Expression.Literal.ofNull(typeOf(...))` calls `typeOf` on the literal's SQL type, which
for a bare `NULL` is `NULL` — not in `typeOf`'s switch. This is a refusal or a null column, and
nothing says which.
**Falsifier:** an internal exception with no `PRV-` code.
**Setup:** S, D1.
**Steps:** H-VAL then H-RUN, `SELECT NULL FROM txn`.
**Expected:** either six empty fields, or `PRV-2021` naming the expression. Anything without a code
is a FAIL.
**Vacuity:** C1 on the run half (`6 in`), so "six empty fields" cannot come from six absent rows.

### SQLX-016 — boolean literals in the select list
**Intent:** `typeOf` maps `BOOLEAN`, but `literal()` reaches `(BigDecimal) literal.getValue4()` for
anything that is not interval, char or varchar — and a boolean's `getValue4` is not a `BigDecimal`.
Round 2 found exactly this class of raw `ClassCastException` for narrow-type literals.
**Falsifier:** a raw `ClassCastException` reaching the user.
**Setup:** S, D1.
**Steps:** H-VAL then H-RUN, `SELECT TRUE FROM txn`, `--out-schema 'b:BOOLEAN'`.
**Expected:** six lines of `true`, **or** `PRV-2021`. A `ClassCastException` is a FAIL against the
document's promise that every refusal carries a code.
**Vacuity:** C1 on the run half (`6 in, 6 out`).

### SQLX-017 — integer arithmetic, computed per row
**Intent:** Documented ✅ `amount * 2 + 1`. The matrix plans it; the value has never been checked.
**Falsifier:** any row's value differs from the arithmetic below.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT amount * 2 + 1 FROM txn`, `--out-schema 'v:INT64'`.
**Expected:** `100*2+1 = 201`, `250*2+1 = 501`, `-50*2+1 = -99`, `0*2+1 = 1`, `7*2+1 = 15`,
`7*2+1 = 15`. Six lines: `201 / 501 / -99 / 1 / 15 / 15`.
**Vacuity:** C1, and a control `SELECT amount FROM txn` in the same session returning the six inputs.

### SQLX-018 — floating arithmetic, computed per row
**Intent:** Documented ✅ `price / 2`. Division on doubles, where a "promote to long" bug returns
plausible integers.
**Falsifier:** any value is an integer where the arithmetic says otherwise.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT price / 2 FROM txn`, `--out-schema 'v:FLOAT64'`.
**Expected:** `2.5/2 = 1.25`, `4.0/2 = 2.0`, `1.0/2 = 0.5`, `0.5/2 = 0.25`, `1.5/2 = 0.75`,
`0.25/2 = 0.125`.
**Vacuity:** C1. Note `1.25` and `0.125` are exact in binary; no tolerance is needed and none is
allowed.

### SQLX-019 — `CAST(x AS DOUBLE)` between numeric types
**Intent:** Documented ✅ "Between numeric types".
**Falsifier:** `-50` comes back as `-50` typed INT64, or as a truncated `0.0`.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT CAST(amount AS DOUBLE) FROM txn`, `--out-schema 'v:FLOAT64'`.
**Expected:** `100.0 / 250.0 / -50.0 / 0.0 / 7.0 / 7.0`.
**Vacuity:** C1.

### SQLX-020 — `CAST` narrowing a double to an integer, and where it rounds
**Intent:** The float-aggregate refusal tells users to write `SUM(CAST(price AS BIGINT))`. That
advice is only sound if the cast does what they expect. Nothing checks it.
**Falsifier:** `2.5` becomes `3` where the engine elsewhere rounds half away from zero, or the run
throws.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT CAST(price AS BIGINT) FROM txn`, `--out-schema 'v:INT64'`.
**Expected:** SQL `CAST` truncates toward zero: `2.5→2`, `4.0→4`, `1.0→1`, `0.5→0`, `1.5→1`,
`0.25→0`. Record the answer either way; a cast that rounds while `ROUND` also rounds makes the
refusal's own advice change the number.
**Vacuity:** C1.

### SQLX-021 — `CAST` to text is refused by name
**Intent:** `ExpressionCompiler.cast` refuses when either side is non-numeric, and `typeOf` has no
`VARCHAR` case at all.
**Falsifier:** it plans, or the message does not name the types.
**Setup:** S.
**Steps:** H-VAL, `SELECT CAST(amount AS VARCHAR) FROM txn`.
**Expected:** `PRV-2021`, message containing `VARCHAR` and either "Pravaha evaluates numeric
conversions only" or "which Pravaha cannot compute with yet". Refused under H-VAL, so plan time.
**Vacuity:** C3.

### SQLX-022 — a user column named `window_start`
**Intent:** `windowBoundaryOrdinals` matches group keys **by name**, case-insensitively. A stream
with its own `window_start` column and no window is the collision that comment is afraid of.
**Falsifier:** an unwindowed `GROUP BY window_start` is admitted as bounded — `windowBelow` returns
null so it must not be — or a windowed query silently groups by the user's column.
**Setup:** S plus a column: `--schema '…,window_start:INT64'`, D1 with a seventh field `0`.
**Steps:** H-VAL, `SELECT window_start, COUNT(*) FROM txn GROUP BY window_start`.
**Expected:** `PRV-2050`, naming `window_start` as the unbounded key. `windowBelow(input)` is null
because there is no `WindowAssignOperator`, so the bounded branch is unreachable and the state
refusal is correct.
**Vacuity:** C3, plus the control `SELECT COUNT(*) FROM txn` planning in the same session — a global
aggregate must still be admitted.

### SQLX-023 — `SELECT DISTINCT` over a stream is `PRV-2050`, not `PRV-2020`
**Intent:** The document puts DISTINCT in the projection table with `PRV-2050` and explains it as "a
`GROUP BY` over an unbounded key space". Getting the code right is the whole claim: a user told
`PRV-2020` would ask when it is coming, and `PRV-2050` tells them to add a window.
**Falsifier:** `PRV-2020`, or it plans.
**Setup:** S.
**Steps:** H-VAL, `SELECT DISTINCT user_id FROM txn`.
**Expected:** `PRV-2050`. Message names `user_id` (`namesOf` renders group ordinals as names) and
says "Bound it with a window". Longer than 40 characters.
**Vacuity:** C3.

### SQLX-024 — `SELECT DISTINCT` over a **view** is supported and returns the right set
**Intent:** The document's asymmetry — "Same SQL, different answer, and the difference is the input
rather than the query". `ViewQuery.physicalOf` calls `.overBoundedInput()`; nothing runs a row
through that path in the matrix, which never constructs a bounded builder at all.
**Falsifier:** `PRV-2050` over the view, or a result with 6 rows rather than 4, or 3.
**Setup:** H-VIEW, `v_txn` committed at 6 rows.
**Steps:** `pravaha query --sql "SELECT DISTINCT user_id FROM v_txn"`.
**Expected:** exactly 4 rows: `u1`, `u2`, `u3`, `ünïcødé`. `u1` appears in D1 twice (r1, r3) and
`u2` twice (r2, r5); both collapse to one. `6 - 2 = 4`.
**Vacuity:** C1 (`pravaha queries` shows `v_txn` at 6 rows in), plus the control
`SELECT user_id FROM v_txn` in the same session returning 6. A DISTINCT that returned 4 because the
view held 4 rows would be indistinguishable without it.

### SQLX-025 — `DISTINCT` treats NULL as a value, not as a row that vanishes
**Intent:** The document is explicit for `GROUP BY`: "**NULL is a group**, not a row that vanishes".
DISTINCT is the same operator and nothing states it there.
**Falsifier:** 2 rows instead of 3, or a NULL row that is dropped.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT DISTINCT status FROM v_txn"`.
**Expected:** 3 rows: `ok` (r1, r3, r5, r6), the empty/NULL rendering (r2), `flagged` (r4).
`|{ok, NULL, flagged}| = 3`.
**Vacuity:** C2 — `SELECT DISTINCT status FROM v_txn WHERE status IS NOT NULL` must return 2 in the
same session. `3 - 1 = 2` is the arithmetic that makes the NULL group real.

### SQLX-026 — multi-column `DISTINCT` over a view
**Intent:** `Aggregate.getGroupSet()` with two ordinals and no aggregate calls. A single-column
DISTINCT cannot distinguish "distinct on the tuple" from "distinct on the first column".
**Falsifier:** 4 rows (distinct `user_id` only) instead of 5.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT DISTINCT user_id, status FROM v_txn"`.
**Expected:** the six pairs are `(u1,ok)`, `(u2,NULL)`, `(u1,ok)`, `(u3,flagged)`, `(u2,ok)`,
`(ünïcødé,ok)`. Rows 1 and 3 are identical, so `6 - 1 = 5` rows:
`(u1,ok) (u2,NULL) (u3,flagged) (u2,ok) (ünïcødé,ok)`.
**Vacuity:** C2 — `SELECT DISTINCT user_id FROM v_txn` returns 4 in the same session. `5 ≠ 4` proves
the second column participates.

### SQLX-027 — `SELECT DISTINCT *` over a view
**Intent:** Every column in the group set, including the nullable one and the float. A group key over
FLOAT64 and over a NULL together is the combination nothing tests.
**Falsifier:** fewer than 6 rows (D1's rows are pairwise distinct on `txn_id`), an exception, or a
float key that collapses `2.5` and `4.0`.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT DISTINCT * FROM v_txn"`.
**Expected:** 6 rows, identical to `SELECT * FROM v_txn`, because `txn_id` is unique in D1.
**Vacuity:** C1 and C2 — the same session's `SELECT * FROM v_txn` also returns 6. Equal counts here
are the assertion, and they are only meaningful because SQLX-024 proved DISTINCT can reduce.

### SQLX-028 — `DISTINCT` composed with `WHERE`
**Intent:** The brief's "interaction with a second construct" for DISTINCT. Filter below aggregate;
`windowBelow` walks through a `FilterOperator`, and the bounded branch must survive it.
**Falsifier:** `PRV-2050`, or 4 rows (the filter ignored), or 1.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT DISTINCT user_id FROM v_txn WHERE amount > 0"`.
**Expected:** `amount > 0` keeps r1 (100, u1), r2 (250, u2), r5 (7, u2), r6 (7, ünïcødé) — 4 rows;
r3 (-50) and r4 (0) are dropped. Distinct over `{u1, u2, u2, ünïcødé}` = **3 rows**:
`u1, u2, ünïcødé`.
**Vacuity:** C2 — `SELECT DISTINCT user_id FROM v_txn WHERE amount <= 0` returns `{u1, u3}` = 2 in
the same session. The two sets overlap on `u1` by design, so `3 + 2 = 5 ≠ 4`, which is the check
that neither query is silently returning the unfiltered set.

---

## §2 — Expressions and scalar functions

The document lists four numeric functions as ✅, everything else numeric as `PRV-2021`, and five text
functions as ✅. `SqlSupportMatrixTest` has eight rows here and no values at all.

### SQLX-029 — `ABS` over positive, negative and zero
**Intent:** Documented ✅. `D1` has one of each, which is the point of `-50` and `0` being in it.
**Falsifier:** `ABS(-50)` is anything but `50`.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT ABS(amount) FROM txn`, `--out-schema 'v:INT64'`.
**Expected:** `100 / 250 / 50 / 0 / 7 / 7`. Sum of outputs `100+250+50+0+7+7 = 414`, against an input
sum of `314` — the `100` difference is `2 × 50` and is the arithmetic that proves the sign flipped.
**Vacuity:** C1, plus the control `SELECT amount FROM txn` returning `-50` in row 3.

### SQLX-030 — `ABS` of the most negative 64-bit integer
**Intent:** Q-12, fixed as "throws". `|Long.MIN_VALUE|` is not representable; the fix must still be
in place and must still be a stated failure rather than a negative absolute value.
**Falsifier:** the output holds `-9223372036854775808`, which is a wrong answer wearing a success
status.
**Setup:** S, `edge.csv` row 8 alone (`amount = -9223372036854775808`).
**Steps:** H-RUN, `SELECT ABS(amount) FROM txn`, `--out-schema 'v:INT64'`.
**Expected:** the command fails with a non-zero exit and a message naming the overflow. A negative
result is a **FAIL**; a silently dropped row under `ok  1 in, 0 out` is also a FAIL (Q-7's shape).
**Vacuity:** C1 — `1 in` must be printed. `0 in, 0 out` means the file was not read.

### SQLX-031 — `FLOOR` and `CEIL` over an integer column are identity
**Intent:** Documented ✅, one argument. Over INT64 both must return the input; a double round-trip
would show as `-50 → -50.0` or a lost sign.
**Falsifier:** any value differs from the input.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT FLOOR(amount), CEIL(amount) FROM txn`, `--out-schema 'f:INT64,c:INT64'`.
**Expected:** every row's two fields equal each other and equal `amount`:
`100,100 / 250,250 / -50,-50 / 0,0 / 7,7 / 7,7`.
**Vacuity:** C1.

### SQLX-032 — `FLOOR` and `CEIL` over a float column, including a negative
**Intent:** The direction of rounding for negatives is the half of this nobody gets right by
accident: `FLOOR(-1.5) = -2`, not `-1`.
**Falsifier:** `FLOOR(-1.5)` returns `-1`, or `CEIL(2.5)` returns `2`.
**Setup:** S, D1 plus `edge.csv` row 8 (`price = -1.7976931348623157E308`). Use a dedicated file
with prices `2.5, 4.0, 1.0, 0.5, -1.5, 0.25`.
**Steps:** H-RUN, `SELECT FLOOR(price), CEIL(price) FROM txn`, `--out-schema 'f:FLOAT64,c:FLOAT64'`.
**Expected:** `2.5 → 2.0, 3.0`; `4.0 → 4.0, 4.0`; `1.0 → 1.0, 1.0`; `0.5 → 0.0, 1.0`;
`-1.5 → -2.0, -1.0`; `0.25 → 0.0, 1.0`.
**Vacuity:** C1.

### SQLX-033 — `ROUND` is half away from zero, on both signs
**Intent:** Q-4 was banker's rounding — `ROUND(2.5) = 2` — fixed to half away from zero. The
regression is invisible to a plan-shaped matrix.
**Falsifier:** `ROUND(2.5)` is `2`, or `ROUND(-2.5)` is `-2`.
**Setup:** S, a file with prices `2.5, 3.5, -2.5, -3.5, 0.5, -0.5`.
**Steps:** H-RUN, `SELECT ROUND(price) FROM txn`, `--out-schema 'v:FLOAT64'`.
**Expected:** `3.0, 4.0, -3.0, -4.0, 1.0, -1.0`. Banker's rounding would give
`2.0, 4.0, -2.0, -4.0, 0.0, -0.0` — the four differences are the assertion.
**Vacuity:** C1, and the pair `3.5 → 4.0` must agree under both rules, which is the control proving
the query ran rather than the four disagreements all coming from one broken row.

### SQLX-034 — `ROUND` at the half-ulp boundary
**Intent:** Round 2 called the fix a symptom fix: `floor(abs(x)+0.5)` returns `1` for
`0.49999999999999994`, where the correct answer is `0`, because `0.49999999999999994 + 0.5 == 1.0`
in binary64. `BigDecimal.setScale(0, HALF_UP)` was named in the original report and has neither
problem.
**Falsifier:** the output is `1.0`.
**Setup:** S, `edge.csv` row 11 (`price = 0.49999999999999994`).
**Steps:** H-RUN, `SELECT ROUND(price) FROM txn`, `--out-schema 'v:FLOAT64'`.
**Expected:** `0.0`. `0.49999999999999994 < 0.5`, so half-away-from-zero rounds down.
**Vacuity:** C1 (`1 in, 1 out`), and a control row at `0.5` in the same file rounding to `1.0`. If
both come back `1.0` the case is still a FAIL, but the control proves the operator ran.

### SQLX-035 — `ROUND(x, 2)` is refused, with the reason
**Intent:** Documented ❌ `PRV-2021`: "rounding to decimal places is not built". The refusal comes
from the arity check in `ExpressionCompiler.call`, not from a name lookup.
**Falsifier:** it plans, or the message does not mention decimal places.
**Setup:** S.
**Steps:** H-VAL, `SELECT ROUND(amount, 2) FROM txn`.
**Expected:** `PRV-2021`, message contains "is supported with one argument and was given 2" and
"ROUND to a number of decimal places is not built; round the value and scale it, or cast it" — an
instruction the user can follow.
**Vacuity:** C3.

### SQLX-036 — a fifth numeric function is refused, and the message names Calcite's rewrite
**Intent:** Documented ❌ "Numeric functions beyond those four — `PRV-2021`". Round 1 (SQL-014) found
`SQRT` refused as `POWER`, a word the user did not type. The brief asks whether a refusal names
something the user wrote.
**Falsifier:** `SQRT` plans, or the code is not `PRV-2021`.
**Setup:** S.
**Steps:** H-VAL, `SELECT SQRT(price) FROM txn`; repeat for `POWER(amount, 2)`, `EXP(price)`,
`LN(price)`, `LOG10(price)`, `SIGN(amount)`, `TRUNCATE(price)`.
**Expected:** every one `PRV-2021`. Each message ends with the supported list
"+ - * / %, ABS, FLOOR, CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, SUBSTRING and || ". **Recorded
as a finding, not a failure:** `SQRT` is refused as `POWER($1, 0.5)`, so the user searches their own
query for a word that is not in it. The one-clause fix named in round 1 — "…`POWER`, which is how
the planner rewrote `SQRT`" — is still not present.
**Vacuity:** C3 for each.

### SQLX-037 — `MOD` works while the document says it does not
**Intent:** Round-1 SQL-014: `MOD(n, 3)` is compiled (`case "%", "MOD" -> Expression.Operator.MODULO`)
and `SQL_SUPPORT.md` lists every numeric function beyond the four as `PRV-2021`. The document and
the code disagree, and the matrix asserts neither.
**Falsifier:** `MOD` is refused (which would make the document right and this case's premise wrong —
record it either way).
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT MOD(amount, 3), amount % 3 FROM txn`, `--out-schema 'm:INT64,p:INT64'`.
**Expected:** it plans and runs. `100 % 3 = 1`, `250 % 3 = 1`, `-50 % 3 = -2` (Java/SQL remainder
takes the dividend's sign), `0 % 3 = 0`, `7 % 3 = 1`, `7 % 3 = 1`. Both columns identical.
**Finding:** `SQL_SUPPORT.md`'s "Numeric functions beyond those four ❌" row is wrong. Round 1's
Q-10 also saw `-7 % 3` written as `4294967295` through a mismatched `--out-schema`; declare
`m:INT64` and check `-2` appears, not `4294967294`.
**Vacuity:** C1.

### SQLX-038 — unary minus
**Intent:** Q-11, **OPEN**: `SELECT -n` is refused and the refusal claims unary minus is supported
("only the two-operand form is supported (unary minus included, which Calcite normalises to 0 - x)").
A message that contradicts itself is worse than a refusal.
**Falsifier:** neither of the two documented outcomes — it must either work or refuse, and the
refusal must not claim the thing it is refusing is supported.
**Setup:** S, D1.
**Steps:** H-VAL then H-RUN, `SELECT -amount FROM txn`, `--out-schema 'v:INT64'`.
**Expected:** *(correct behaviour)* `-100 / -250 / 50 / 0 / -7 / -7`, summing to `-314`, the negation
of D1's `314`.
**Expected:** *(current, to be confirmed)* `PRV-2021` whose text asserts unary minus is included. That
is a **FAIL** of the message, recorded against Q-11.
**Vacuity:** C1 on the working branch; C3 on the refusing one.

### SQLX-039 — integer division by zero
**Intent:** Q-5, **OPEN**: "hangs five minutes, loses the whole batch, and swallows the
`ArithmeticException`". `QueryRunner` waits `Duration.ofMinutes(5)` on `awaitQuiescent` and then
throws "the query did not finish within five minutes".
**Falsifier:** the command returns `ok` with rows, i.e. the divide-by-zero row silently vanished.
**Setup:** S, D1 (row 4 has `amount = 0`).
**Steps:** H-RUN, `SELECT 100 / amount FROM txn`, `--out-schema 'v:INT64'`. Time it.
**Expected:** *(correct)* a prompt failure naming division by zero and the row, non-zero exit, in well
under five minutes.
**Expected:** *(current, to be confirmed)* a five-minute stall then "the query did not finish within
five minutes". Record the wall-clock time; that number is the finding.
**Vacuity:** C1 — the control `SELECT 100 / 2 FROM txn` in the same session must return six rows of
`50` promptly. Without it, "it hung" is indistinguishable from "the source was empty", which is
exactly the round-1 pause-test failure the master list cites.

### SQLX-040 — arithmetic overflow in a projection
**Intent:** Q-7, **OPEN**: "drops the row silently under a success status, or hangs five minutes —
non-deterministically, same command, same input". Non-determinism means this case must be run ten
times, not once.
**Falsifier:** `ok  1 in, 1 out` with a wrapped value such as `-2` in the output.
**Setup:** S, `edge.csv` row 7 alone (`amount = 9223372036854775807`).
**Steps:** H-RUN, `SELECT amount * 2 FROM txn`, `--out-schema 'v:INT64'`. **Ten times.** Record the
outcome of each.
**Expected:** *(correct)* ten identical failures naming overflow. `9223372036854775807 * 2` wraps to
`-2` in two's complement, so `-2` in the output is the specific wrong answer to look for.
**Vacuity:** C1 on every run. Also run `SELECT amount * 1 FROM txn` on the same file: it must return
`9223372036854775807` ten times out of ten, which proves the row is readable and the value survives
the pipeline when no overflow occurs.

### SQLX-041 — `CASE WHEN … THEN … ELSE … END`, two branches, values checked
**Intent:** Documented ✅. Nothing runs one.
**Falsifier:** any row on the wrong branch.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT CASE WHEN amount > 50 THEN 1 ELSE 0 END FROM txn`, `--out-schema 'v:INT32'`.
**Expected:** `amount` is `100, 250, -50, 0, 7, 7`; `> 50` is true for the first two only.
Output `1 / 1 / 0 / 0 / 0 / 0`; the column sums to `2`.
**Vacuity:** C1, and C2 — the complement `CASE WHEN amount > 50 THEN 0 ELSE 1 END` must sum to `4`,
and `2 + 4 = 6`.

### SQLX-042 — `CASE` with several branches, and with no `ELSE`
**Intent:** Documented ✅ "Any number of branches, with or without `ELSE`". `caseWhen` rebuilds the
flattened chain recursively; a wrong stride of 2 would evaluate the wrong branch's value.
**Falsifier:** a row takes a later branch when an earlier one was true, or a no-`ELSE` miss produces
`0` rather than NULL.
**Setup:** S, D1.
**Steps:** (a) H-RUN `SELECT CASE WHEN amount > 100 THEN 3 WHEN amount > 10 THEN 2 WHEN amount > 0
THEN 1 ELSE 0 END FROM txn`, `--out-schema 'v:INT32'`. (b) H-RUN `SELECT CASE WHEN amount > 100 THEN
3 END FROM txn`, `--out-schema 'v:INT32?'`.
**Expected:** *(a)* `100 → 2` (not `3`: `100 > 100` is false), `250 → 3`, `-50 → 0`, `0 → 0`,
`7 → 1`, `7 → 1`. Column `2 / 3 / 0 / 0 / 1 / 1`, summing to `7`.
**Expected:** *(b)* row 2 is `3`; the other five are NULL (empty fields), **not** `0`.
`Calcite always supplies an ELSE, adding a null one where the SQL omitted it` — this is the case
that checks the null actually arrives as a null.
**Vacuity:** C1 on both.

### SQLX-043 — `CASE` evaluates only the branch taken
**Intent:** The document promises it in so many words: "`CASE WHEN n = 0 THEN 0 ELSE t / n END` does
not divide by zero". If the engine evaluated both arms, D1's `amount = 0` row would hit SQLX-039's
five-minute stall. This is the test that tells short-circuiting from luck.
**Falsifier:** the run stalls, throws, or drops the `amount = 0` row.
**Setup:** S, D1.
**Steps:** H-RUN, `SELECT CASE WHEN amount = 0 THEN 0 ELSE 100 / amount END FROM txn`,
`--out-schema 'v:INT64'`.
**Expected:** six rows, promptly. `100/100 = 1`, `100/250 = 0` (integer division), `100/-50 = -2`,
the zero row `→ 0` by the guard, `100/7 = 14`, `100/7 = 14`. Output `1 / 0 / -2 / 0 / 14 / 14`.
**Vacuity:** C1 (`6 in, 6 out`), and SQLX-039 as the paired control: the *unguarded* form must
misbehave on the same file in the same session. If `100 / amount` also succeeds, this case proves
nothing about short-circuiting.

### SQLX-044 — a `CASE` mixing text and a number produces text
**Intent:** The document's most surprising ✅, stated in prose: `CASE WHEN … THEN 'big' ELSE 0 END`
is accepted "because Calcite's validator coerces the `0` to the string `'0'`… The column is text. A
`SUM` over it will not plan." Two claims, and neither is checked by a row.
**Falsifier:** the run emits `0` as an integer, or emits an empty field where `'0'` is expected.
**Setup:** S, D1.
**Steps:** (a) H-VAL, `SELECT CASE WHEN amount > 50 THEN 'big' ELSE 0 END FROM txn` — record the
output type. (b) H-RUN the same, `--out-schema 'v:STRING'`. (c) H-VAL `SELECT SUM(c) FROM (SELECT
CASE WHEN amount > 50 THEN 'big' ELSE 0 END AS c FROM txn) x`.
**Expected:** *(a)* output type is `VARCHAR`/`STRING`, not `INTEGER`.
**Expected:** *(b)* `big / big / 0 / 0 / 0 / 0` — the last four are the **one-character string** `0`.
**Expected:** *(c)* a refusal. `kindOf` accepts `SUM`, so the refusal comes from the accumulators or
from validation; whichever it is, it must carry a `PRV-` code.
**Vacuity:** C1 on (b).

### SQLX-045 — `UPPER` and `LOWER` convert in the root locale
**Intent:** Documented ✅ "Converted in the root locale, so the answer does not depend on the machine
the lane runs on". `Expression.java:287` names the Turkish dotless-i as the reason. A lane started
under `-Duser.language=tr` must give the same answer.
**Falsifier:** `UPPER('i')` returns `İ` (U+0130) under a Turkish default locale.
**Setup:** S, a file with `user_id` values `i, ı, straße, ünïcødé, ABC, abc`.
**Steps:** H-RUN `SELECT UPPER(user_id), LOWER(user_id) FROM txn`, `--out-schema 'u:STRING,l:STRING'`,
**twice**: once normally, once with `JAVA_TOOL_OPTIONS='-Duser.language=tr -Duser.country=TR'`.
**Expected:** both runs byte-identical. `i → I, i`; `ı → I, ı`; `straße → STRASSE, straße`;
`ünïcødé → ÜNÏCØDÉ, ünïcødé`; `ABC → ABC, abc`; `abc → ABC, abc`.
**Vacuity:** C1 on both runs, and the two output files compared with `cmp`. Two runs that both
produced nothing would also compare equal.

### SQLX-046 — `TRIM` strips spaces from both ends; `LEADING` and a trim character are refused
**Intent:** Two matrix rows and one documented ✅. `trim()` refuses unless the flag contains `BOTH`
**and** the trim character is a literal space.
**Falsifier:** `TRIM(LEADING …)` plans, or `TRIM` fails to strip a trailing space, or it strips an
interior one.
**Setup:** S, `edge.csv` row 11 (`user_id = "  padded  "`, `status = "  sp  "`).
**Steps:** (a) H-RUN `SELECT TRIM(user_id) FROM txn`, `--out-schema 'v:STRING'`.
(b) H-VAL `SELECT TRIM(LEADING ' ' FROM user_id) FROM txn`.
(c) H-VAL `SELECT TRIM('x' FROM user_id) FROM txn`.
(d) H-VAL `SELECT TRIM(TRAILING ' ' FROM user_id) FROM txn`.
**Expected:** *(a)* exactly `padded` — 6 characters, no leading or trailing space. The input is 10
characters; `10 - 2 - 2 = 6`.
**Expected (b), (c), (d):** `PRV-2021` each, message containing "TRIM strips spaces from both ends,
and LEADING, TRAILING and a trim character other than a space are not built."
**Vacuity:** C1 on (a); C3 on (b)–(d).

### SQLX-047 — concatenation: chains flatten, and NULL concatenated with anything is NULL
**Intent:** Documented ✅ with an emphasised caveat: "**Null concatenated with anything is null**, not
an empty string". D1 row 2's NULL `status` is there for this.
**Falsifier:** row 2 produces `u2-` rather than NULL.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT user_id || '-' || status FROM txn`, `--out-schema 'v:STRING?'`; and
`SELECT user_id || '-' || user_id || '-' || user_id FROM txn`, `--out-schema 'v:STRING'`.
**Expected:** *(first)* `u1-ok / <NULL> / u1-ok / u3-flagged / u2-ok / ünïcødé-ok`. Row 2 is an empty
field **meaning NULL**, and the case must distinguish that from the string `u2-`: read the output
back with `--schema 'v:STRING?'` and assert `IS NULL` returns exactly 1 row.
**Expected:** *(second)* `u1-u1-u1 / u2-u2-u2 / u1-u1-u1 / u3-u3-u3 / u2-u2-u2 /
ünïcødé-ünïcødé-ünïcødé`. `concat()` flattens nested pairs, so a five-part chain must not produce a
truncated three-part result.
**Vacuity:** C1 on both; C2 — the count of non-NULL outputs in the first is 5, matching
`COUNT(status) = 5`.

### SQLX-048 — `SUBSTRING`: 1-based, counted in code points, and its boundaries
**Intent:** Documented ✅ with a specific promise — "Positions are 1-based and counted in code points,
so a substring never splits an emoji in half". `edge.csv` row 10's `user_id` is `😀x`, which is three
UTF-16 units and two code points.
**Falsifier:** `SUBSTRING('😀x' FROM 1 FOR 1)` returns a lone surrogate (a broken 3-byte sequence in
the output file) rather than the whole emoji.
**Setup:** S, `edge.csv` row 10, plus D1.
**Steps:** H-RUN, all on `--out-schema 'v:STRING'`:
(a) `SELECT SUBSTRING(user_id FROM 1 FOR 1) FROM txn` over row 10;
(b) `SELECT SUBSTRING(user_id FROM 2) FROM txn` over row 10;
(c) `SELECT SUBSTRING(user_id FROM 1 FOR 3) FROM txn` over D1;
(d) `SELECT SUBSTRING(user_id FROM 0 FOR 2) FROM txn` over D1;
(e) `SELECT SUBSTRING(user_id FROM 1 FOR 0) FROM txn` over D1;
(f) `SELECT SUBSTRING(user_id FROM 99) FROM txn` over D1.
**Expected:** (a) `😀` — 4 bytes of UTF-8, one code point, **not** a replacement character.
(b) `x`. (c) `u1 / u2 / u1 / u3 / u2 / üni` — `ünïcødé`'s first three code points are `ü`, `n`, `ï`,
so 6 bytes of UTF-8 from 3 code points; a code-unit implementation gives the same answer here, which
is why (a) is the discriminating case. (d) SQL says a start below 1 counts the shortfall against the
length, so `FROM 0 FOR 2` yields 1 character: `u / u / u / u / u / ü`. (e) six empty strings.
(f) six empty strings.
**Vacuity:** C1 on each. For (a), assert the output file's bytes are `F0 9F 98 80`, not `EF BF BD`.

---

## §3 — `WHERE`: the predicate compiler

### §3.1 — A comparison on every type

Twelve types, one case each, in the position `TYPE` does not own: a filter. `PredicateCompiler.compare`
switches on `TypeName` and has a distinct branch per family — `CompareInt` for INT8/INT16/INT32/DATE,
`CompareLong` for INT64/TIME/TIMESTAMP_LTZ, `CompareDouble` for FLOAT32/FLOAT64, `CompareString`,
`CompareBoolean`, and a `default` that refuses. Every branch and the default are exercised here.
All twelve use **Fixture S2** and `all.csv`, three rows.

### SQLX-049 — BOOLEAN in a predicate
**Intent:** `CompareBoolean` is built as `constant.asBoolean() == (op == EQ)`, which collapses `= TRUE`
and `<> FALSE` into one flag. An inversion returns exactly the complement and looks like data.
**Falsifier:** `WHERE b = TRUE` returns the `false` row.
**Setup:** S2, `all.csv` (`b` = true, false, true).
**Steps:** H-RUN four queries: `WHERE b = TRUE`, `WHERE b = FALSE`, `WHERE b <> TRUE`, `WHERE b`.
**Expected:** rows 1 and 3 (2 rows); row 2 (1 row); row 2 (1 row); rows 1 and 3 (2 rows).
**Vacuity:** C2 — `2 + 1 = 3`, and the unfiltered control returns 3.

### SQLX-050 — INT8 in a predicate
**Intent:** `compare` casts to `(int)` for INT8; a byte column compared against a literal outside a
byte's range must not wrap. Round 2 found narrow-type literals throwing raw `ClassCastException`.
**Falsifier:** `WHERE i8 > 200` matches a row (no INT8 value can exceed 127), or throws without a code.
**Setup:** S2, `all.csv` (`i8` = 1, -1, 0).
**Steps:** H-RUN `WHERE i8 > 0`, `WHERE i8 < 0`, `WHERE i8 = 0`, `WHERE i8 >= -1`, `WHERE i8 > 200`.
**Expected:** 1 row (r1); 1 row (r2); 1 row (r3); 3 rows; 0 rows. The last must be **0 rows with a
success status**, not an error and not 3.
**Vacuity:** C2 — `1 + 1 + 1 = 3`.

### SQLX-051 — INT16 in a predicate
**Intent:** Same branch as INT8, different width; a shared `(int)` cast makes a width bug invisible
in one and not the other.
**Falsifier:** `WHERE i16 = 100` misses r1.
**Setup:** S2, `all.csv` (`i16` = 100, -100, 0).
**Steps:** H-RUN `WHERE i16 = 100`, `WHERE i16 <> 100`, `WHERE i16 BETWEEN -100 AND 0`.
**Expected:** 1 row; 2 rows; 2 rows (r2 `-100` and r3 `0`; r1 `100` is above the upper bound).
**Vacuity:** C2 — `1 + 2 = 3`.

### SQLX-052 — INT32 in a predicate
**Intent:** `CompareInt` again at 32 bits, plus the `flip(op)` path where the literal is
written on the left.
**Falsifier:** `WHERE i32 >= 1000` misses r1.
**Setup:** S2, `all.csv` (`i32` = 1000, -1000, 0).
**Steps:** H-RUN `WHERE i32 >= 1000`, `WHERE i32 <= -1000`, `WHERE i32 = 0`, `WHERE 1000 = i32`.
**Expected:** 1, 1, 1, 1 row. The fourth is the `flip(op)` path — `literal OP column` must mean the
same as `column flip(OP) literal`.
**Vacuity:** C2 — `1 + 1 + 1 = 3`; and queries one and four return the identical row.

### SQLX-053 — INT64 in a predicate, including the extremes
**Intent:** `CompareLong`, the widest integer branch. A stray `(int)` cast anywhere on this path
truncates silently and matches the wrong rows rather than erroring.
**Falsifier:** `WHERE i64 > 9223372036854775806` matches nothing when a row holds `Long.MAX_VALUE`.
**Setup:** S2, `all.csv` plus a fourth row with `i64 = 9223372036854775807` and a fifth with
`i64 = -9223372036854775808`.
**Steps:** H-RUN `WHERE i64 = 9223372036854775807`, `WHERE i64 = -9223372036854775808`,
`WHERE i64 > 0`, `WHERE i64 < 0`.
**Expected:** 1 row each for the first two — a `(int)` cast anywhere on this path would truncate both
to `-1` and `0` and match the wrong rows. Then 2 rows (`10000`, `MAX`) and 2 rows (`-10000`, `MIN`).
**Vacuity:** C2 — `2 + 2 + 1` (the `0` row) `= 5`.

### SQLX-054 — FLOAT32 in a predicate
**Intent:** `CompareDouble` holds a `double`; a FLOAT32 column widened per row must compare against a
literal that was narrowed the same way, or `f32 = 1.5` fails on a value that prints as `1.5`.
**Falsifier:** `WHERE f32 = 1.5` returns 0 rows.
**Setup:** S2, `all.csv` (`f32` = 1.5, -1.5, 0.0). `1.5` is exact in both widths, which is why it
was chosen; a case at `0.1` belongs to `TYPE`.
**Steps:** H-RUN `WHERE f32 = 1.5`, `WHERE f32 < 0`, `WHERE f32 = 0.0`, `WHERE f32 <> 1.5`.
**Expected:** 1, 1, 1, 2 rows.
**Vacuity:** C2 — `1 + 2 = 3`.

### SQLX-055 — FLOAT64 in a predicate
**Intent:** `CompareDouble` at full width, and a two-sided range on a float column.
**Falsifier:** `WHERE f64 >= 2.5` misses r1.
**Setup:** S2, `all.csv` (`f64` = 2.5, -2.5, 0.0).
**Steps:** H-RUN `WHERE f64 >= 2.5`, `WHERE f64 <= -2.5`, `WHERE f64 > -2.5 AND f64 < 2.5`.
**Expected:** 1, 1, 1 row (r3, the zero).
**Vacuity:** C2 — `1 + 1 + 1 = 3`.

### SQLX-056 — STRING equality and inequality in a predicate
**Intent:** `=` and `<>` are the only text operators supported; both are `CompareString`.
**Falsifier:** `WHERE s = 'alpha'` matches `beta`, or is case-insensitive.
**Setup:** S2, `all.csv` (`s` = alpha, beta, gamma).
**Steps:** H-RUN `WHERE s = 'alpha'`, `WHERE s <> 'alpha'`, `WHERE s = 'ALPHA'`, `WHERE s = ''`.
**Expected:** 1; 2; **0** (comparison is case-sensitive, matching the planner's own case-sensitive
identifier handling); 0.
**Vacuity:** C2 — `1 + 2 = 3`.

### SQLX-057 — BYTES in a predicate
**Intent:** `TypeName.BYTES` reaches `compare`'s `default` branch, so it must refuse with a code —
"cannot compare column '…' of type BYTES against a constant yet". Nothing in the matrix has a
`VARBINARY` column, and `SQL_SUPPORT.md` lists `VARBINARY` under supported types.
**Falsifier:** a raw exception with no `PRV-` code, or it plans and returns arbitrary rows.
**Setup:** S2, `all.csv`.
**Steps:** H-VAL `SELECT s FROM allt WHERE bin = X'51'`; also `WHERE bin IS NULL`.
**Expected:** the comparison is `PRV-2021` naming `bin` and `BYTES`. `IS NULL` uses `Predicate.IsNull`
and is type-independent, so it must **plan** — the two together show the refusal is about comparison
and not about the column existing.
**Vacuity:** C3 on the refusal.
**Finding if it plans:** `SQL_SUPPORT.md` lists `VARBINARY` as a supported type with no caveat;
if the comparison refuses, the Types paragraph needs the caveat.

### SQLX-058 — DATE in a predicate
**Intent:** DATE shares `CompareInt` with the narrow integers — days since epoch held in an `int`.
A date literal must be converted to the same units, or every comparison is off by decades.
**Falsifier:** `WHERE d = DATE '2022-01-01'` returns 0 rows when `d = 19000` is 2022-01-08.
**Setup:** S2, `all.csv` (`d` = 19000, 19001, 19002; 19000 days after 1970-01-01 is **2022-01-08**).
**Steps:** H-RUN `WHERE d = DATE '2022-01-08'`, `WHERE d > DATE '2022-01-08'`, `WHERE d = 19000`.
**Expected:** 1 row; 2 rows; and the third is the interesting one — an integer literal against a
DATE column either plans and matches r1, or refuses with a code. Record which.
**Vacuity:** C2 — `1 + 2 = 3`.

### SQLX-059 — TIME in a predicate
**Intent:** TIME shares `CompareLong` with INT64 and TIMESTAMP. `all.csv` holds milliseconds
(`DelimitedCodec` parses TIME with `Long.parseLong`), and a `TIME` literal arrives from Calcite in
milliseconds too — but the engine works in nanoseconds throughout (ADR-012), so a units mismatch here
is a factor of 1 000 000.
**Falsifier:** `WHERE tm = TIME '01:00:00'` returns 0 rows while `tm = 3600000` returns 1.
**Setup:** S2, `all.csv` (`tm` = 3600000, 7200000, 0 — i.e. 01:00:00, 02:00:00, 00:00:00 in ms).
**Steps:** H-RUN `WHERE tm = TIME '01:00:00'`, `WHERE tm = 3600000`, `WHERE tm > TIME '00:00:00'`.
**Expected:** the first two must return **the same single row**. If they disagree, the units are
inconsistent between the codec and the literal and that is the finding. Third: 2 rows.
**Vacuity:** C2 — the disagreement between query one and query two is itself the assertion, so both
must be run in the same session over the same file.

### SQLX-060 — TIMESTAMP in a predicate
**Intent:** Round 2: "a `TIMESTAMP` comparison throws an `AssertionError` that kills a Flight worker
thread". A killed worker is not a refusal.
**Falsifier:** an `AssertionError`, or a thread death, or 0 rows where 2 are expected.
**Setup:** S2, `all.csv` (`ts` = 1000000000, 2000000000, 3000000000 ns = 1 s, 2 s, 3 s after epoch).
**Steps:** H-RUN `WHERE ts > 1000000000`, `WHERE ts = 1000000000`,
`WHERE ts > TIMESTAMP '1970-01-01 00:00:01'`. Then the same three through H-VIEW over a view of
`allt`, which is the Flight path round 2 saw die.
**Expected:** 2 rows; 1 row; and the third either agrees with the first (2 rows) or refuses with a
code. **No `AssertionError` on any path, and no worker thread death** — after the H-VIEW runs, a
fourth `pravaha query` in the same session must still be answered.
**Vacuity:** C1, plus the follow-up query after the H-VIEW runs, which is what proves the server
survived.

### §3.2 — Boolean structure: AND, OR, NOT and their nesting

### SQLX-061 — `AND` of two comparisons
**Intent:** `Predicate.And` over `compileAll`. Documented ✅.
**Falsifier:** the result is the union rather than the intersection.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE amount > 0 AND flagged`.
**Expected:** `amount > 0` is r1, r2, r5, r6; `flagged` is r1, r4, r6. The intersection is
`{1, 2, 5, 6} ∩ {1, 4, 6} = {1, 6}` — **2 rows**, `txn_id` 1 and 6.
**Vacuity:** C2 — the two halves return 4 and 3 in the same session; `2 < min(4, 3)` shows the
conjunction narrowed.

### SQLX-062 — `OR` of two comparisons
**Intent:** `Predicate.Or` over `compileAll`. Documented ✅ alongside AND and NOT.
**Falsifier:** the result is the intersection, or double-counts.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE amount > 100 OR flagged`.
**Expected:** `{2} ∪ {1, 4, 6} = {1, 2, 4, 6}` — **4 rows**. No `txn_id` appears twice.
**Vacuity:** C2 — the halves return 1 and 3; `1 + 3 = 4` and the sets are disjoint, so the union
count is exactly the sum here, which is the arithmetic.

### SQLX-063 — `NOT` over a single comparison
**Intent:** `negate` turns `NOT (a > b)` into `a <= b` rather than wrapping a Java `!`, precisely so
a null operand stays dropped.
**Falsifier:** `NOT (amount > 0)` returns 4 rows (the complement of the kept set including nulls).
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE NOT (amount > 0)`.
**Expected:** `amount` is non-null everywhere in D1, so this is the exact complement:
`6 - 4 = 2` rows, `txn_id` 3 (`-50`) and 4 (`0`).
**Vacuity:** C2 — `4 + 2 = 6`.

### SQLX-064 — De Morgan over `AND`
**Intent:** `NOT (A AND B)` is compiled as `Or(negate A, negate B)`. A missed inversion returns the
conjunction's complement of one side only.
**Falsifier:** the count is not 4.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE NOT (amount > 0 AND flagged)`.
**Expected:** SQLX-061 kept `{1, 6}`; over non-null columns this is the complement,
`{2, 3, 4, 5}` — **4 rows**. `6 - 2 = 4`.
**Vacuity:** C2 against SQLX-061 run in the same session.

### SQLX-065 — De Morgan over `OR`
**Intent:** `NOT (A OR B)` is compiled as `And(negate A, negate B)`. The mirror of SQLX-064, and
the half a single-sided De Morgan bug leaves working.
**Falsifier:** the count is not 2.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE NOT (amount > 100 OR flagged)`.
**Expected:** SQLX-062 kept `{1, 2, 4, 6}`; the complement is `{3, 5}` — **2 rows**. `6 - 4 = 2`.
**Vacuity:** C2 against SQLX-062.

### SQLX-066 — `NOT NOT`
**Intent:** `case NOT -> compile(operand)`. Double negation must be the identity, including over the
null-bearing column where a naive `!!` is also the identity and a wrongly-lowered one is not.
**Falsifier:** `NOT (NOT (status = 'ok'))` returns other than 4.
**Setup:** S, D1.
**Steps:** H-RUN `WHERE status = 'ok'` and `WHERE NOT (NOT (status = 'ok'))`.
**Expected:** both return **4 rows** (r1, r3, r5, r6) and the identical `txn_id` set. Row 2's NULL is
dropped by both.
**Vacuity:** C2 — the single-`NOT` form `WHERE NOT (status = 'ok')` returns **1** (r4, `flagged`),
not 2. That gap of one row is row 2's UNKNOWN and is what makes the double negation meaningful.

### SQLX-067 — eight levels of interleaved AND/OR/NOT
**Intent:** The brief asks for nesting. `compile`/`negate` recurse in tandem and a mistake deep in an
odd number of negations flips one leaf.
**Falsifier:** a count other than the hand-computed one.
**Setup:** S, D1.
**Steps:** H-RUN
`SELECT txn_id FROM txn WHERE NOT (amount < 0 OR (flagged AND NOT (user_id = 'u1' OR (amount > 200 AND NOT (status = 'ok')))))`.
**Expected:** *(computed row by row)*
inner₃ `= status = 'ok'`; inner₂ `= amount > 200 AND NOT inner₃`; inner₁ `= user_id = 'u1' OR inner₂`;
mid `= flagged AND NOT inner₁`; outer `= NOT (amount < 0 OR mid)`.
| row | amount | flagged | user_id | status | inner₃ | inner₂ | inner₁ | mid | amount<0 | **kept** |
|---|---|---|---|---|---|---|---|---|---|---|
| 1 | 100 | T | u1 | ok | T | F | T | F | F | **yes** |
| 2 | 250 | F | u2 | NULL | UNK | F(¹) | F | F | F | **yes** |
| 3 | -50 | F | u1 | ok | T | F | T | F | T | no |
| 4 | 0 | T | u3 | flagged | F | F | F | T | F | no |
| 5 | 7 | F | u2 | ok | T | F | F | F | F | **yes** |
| 6 | 7 | T | ünïcødé | ok | T | F | F | T | F | no |
(¹) `250 > 200` is TRUE and `NOT UNKNOWN` is UNKNOWN, so inner₂ is UNKNOWN; the two-valued IR drops
it to FALSE, which reaches the same answer here — that agreement is itself worth recording.
**Result: 3 rows — `txn_id` 1, 2, 5.**
**Vacuity:** C2 — the negation of the whole predicate must return the other 3 (`txn_id` 3, 4, 6) in
the same session, and `3 + 3 = 6`.

### SQLX-068 — a predicate that is always true
**Intent:** `case LITERAL -> Predicate.True()`. A constant-folded `1 = 1` must keep every row, and a
`Predicate.False` here silently empties the result.
**Falsifier:** fewer than 6 rows.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE 1 = 1`.
**Expected:** `ok  6 in, 6 out`, `txn_id` 1..6.
**Vacuity:** C1, and C2 against SQLX-069 — `6 + 0 = 6`.

### SQLX-069 — a predicate that is always false
**Intent:** The mirror. A `True` here returns everything, which looks like a working query.
**Falsifier:** any row is emitted.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE 1 = 0`.
**Expected:** `ok  6 in, 0 out`; `out.csv` empty.
**Vacuity:** **C1 is mandatory here.** `6 in` must be printed. `0 in, 0 out` means the file was never
read and the case proves nothing — this is precisely round 1's pause test that passed because the
source ran dry.

### SQLX-070 — bare `WHERE TRUE` and `WHERE FALSE`
**Intent:** The literal reaches `compile`'s `LITERAL` branch directly rather than through a folded
comparison, which is a different path from SQLX-068/069.
**Falsifier:** `PRV-2021`, or the two behave alike.
**Setup:** S, D1.
**Steps:** H-RUN `WHERE TRUE` then `WHERE FALSE`.
**Expected:** 6 rows then 0 rows, `6 in` on both.
**Vacuity:** C1 on both; C2 — `6 + 0 = 6`.

### SQLX-071 — a bare boolean column
**Intent:** Documented ✅ "A bare boolean column — `WHERE flagged`". `case INPUT_REF` builds
`CompareBoolean(idx, name, true)`.
**Falsifier:** `PRV-2021`, or the complement.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE flagged`.
**Expected:** 3 rows, `txn_id` 1, 4, 6.
**Vacuity:** C2 — `WHERE NOT flagged` returns `txn_id` 2, 3, 5 and `3 + 3 = 6`.

### SQLX-072 — `NOT` on a bare boolean column that may be NULL
**Intent:** The `negate` `INPUT_REF` branch says "NOT flagged is TRUE only where flagged is present
and false". `flagged` is non-nullable in S, so this needs a nullable one.
**Falsifier:** the NULL row is kept by both `WHERE f` and `WHERE NOT f`, which would mean 3 + 3 = 7.
**Setup:** S with `flagged:BOOLEAN?`; a file of 3 rows with `flagged` = true, false, empty(NULL).
**Steps:** H-RUN `WHERE flagged`, `WHERE NOT flagged`, `WHERE flagged IS NULL`.
**Expected:** 1, 1, 1 row. `1 + 1 = 2 ≠ 3`: the NULL row is dropped by **both**, and only
`IS NULL` finds it. That shortfall of one is the assertion.
**Vacuity:** C1 (`3 in` on each) and C2 (the census `1 + 1 + 1 = 3` closes only with `IS NULL`).

### SQLX-073 — column against column, both numeric
**Intent:** Documented ✅ "Column against column — `WHERE a > b` | Both numeric". Neither operand is
a literal, so this takes `compareExpressions`, a different code path from every case above.
**Falsifier:** `PRV-2021`, or the wrong row set.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE amount > txn_id`.
**Expected:** `amount` vs `txn_id`: `100>1` T, `250>2` T, `-50>3` F, `0>4` F, `7>5` T, `7>6` T —
**4 rows**, `txn_id` 1, 2, 5, 6.
**Vacuity:** C2 — `WHERE amount <= txn_id` returns 2 (`txn_id` 3, 4) and `4 + 2 = 6`.

### SQLX-074 — column against column where one side is text
**Intent:** `rejectText` refuses when either compiled side has `TypeName.STRING`. Documented ❌ "Text
ordering — `WHERE status > user_id` | `PRV-2021`".
**Falsifier:** it plans, and two texts are ordered by whatever the long underneath happens to be.
**Setup:** S.
**Steps:** H-VAL `SELECT txn_id FROM txn WHERE status > user_id`; and `WHERE status = user_id`.
**Expected:** the first is `PRV-2021` — expect the message "compares text inside a larger expression"
or "only = and <> are supported on text column". The second is the interesting half: `=` between two
**columns** also reaches `compareExpressions` (the fast path needs a literal), so it is refused too,
while `SQL_SUPPORT.md` says "`=` and `<>` on text do work". Record the outcome; if `=` between two
text columns is refused, the document's caveat is incomplete.
**Vacuity:** C3.

### SQLX-075 — arithmetic inside a predicate
**Intent:** Documented ✅ `amount * 2 > 100`. `compareExpressions` again, with a computed left side.
**Falsifier:** the row set matches `amount > 100` instead, i.e. the `* 2` was dropped.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE amount * 2 > 100`.
**Expected:** doubled amounts are `200, 500, -100, 0, 14, 14`; `> 100` is true for r1 and r2 —
**2 rows**, `txn_id` 1 and 2.
**Vacuity:** C2 — `WHERE amount > 100` returns **1** row in the same session. `2 ≠ 1` proves the
multiplication happened; that difference is the whole case.

### §3.3 — Three-valued logic

Four cases over one column, because the document's claim — "a comparison with NULL is UNKNOWN, and a
filter keeps only rows where the predicate is TRUE" — is only checkable as a set of counts that do
**not** add to six.

### SQLX-076 — `= 'ok'` drops the NULL row
**Intent:** The first of the three-valued pair. `compare` returns `Predicate.False()` for a null
constant and every comparison branch tests `!row.isNull(ordinal)` first, so the NULL row must be
dropped rather than compared.
**Falsifier:** 5 rows.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE status = 'ok'`.
**Expected:** 4 rows — `txn_id` 1, 3, 5, 6. Row 2 is NULL (UNKNOWN, dropped); row 4 is `flagged`.
**Vacuity:** C1 (`6 in`).

### SQLX-077 — `<> 'ok'` also drops the NULL row
**Intent:** The pair is the point. If `<>` were implemented as "not equal, nulls included", this
returns 2 and the two counts add to six, which is exactly the wrong behaviour.
**Falsifier:** 2 rows.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE status <> 'ok'`.
**Expected:** **1 row** — `txn_id` 4. Row 2's NULL is UNKNOWN under `<>` too.
**Vacuity:** C2 with SQLX-076 — `4 + 1 = 5 ≠ 6`. The missing row is row 2, and its absence from both
sides is the assertion.

### SQLX-078 — `NOT (status = 'ok')` is not the complement
**Intent:** `PredicateCompiler.negate`'s own javadoc: wrapping in a Java `!` "turns the dropped row
into a kept one". This is the case that catches that regression.
**Falsifier:** 2 rows.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE NOT (status = 'ok')`.
**Expected:** **1 row** — `txn_id` 4 only. Identical to SQLX-077, because `NOT (x = y)` is `x <> y`.
**Vacuity:** C2 with SQLX-076 — `4 + 1 = 5`, and the complement of 4 out of 6 would be 2.

### SQLX-079 — the census closes only when `IS NULL` is added
**Intent:** The affirmative statement of the three preceding cases, as one arithmetic identity.
**Falsifier:** the three counts add to anything but 6, or `IS NULL` returns other than 1.
**Setup:** S, D1.
**Steps:** H-RUN three queries in one session: `WHERE status = 'ok'`, `WHERE status <> 'ok'`,
`WHERE status IS NULL`.
**Expected:** `4 + 1 + 1 = 6`. Exactly.
**Vacuity:** C1 on each (`6 in` three times). This is the case that makes SQLX-076–078 non-vacuous:
without it, three small numbers could all be small because the source was empty.

### §3.4 — `IN`

### SQLX-080 — an empty `IN` list
**Intent:** The brief asks for it. `IN ()` is not valid SQL; the question is whether the refusal is a
clean `PRV-2001` with a position or something internal.
**Falsifier:** an exception with no code, or a plan.
**Setup:** S.
**Steps:** H-VAL `SELECT txn_id FROM txn WHERE user_id IN ()`.
**Expected:** `PRV-2001`, with Calcite's line and column preserved ("Encountered \")\" at line 1,
column …"). The document's error table promises exactly that for 2001.
**Vacuity:** C3.

### SQLX-081 — a one-element `IN` list
**Intent:** Documented ✅ "Expanded to a chain of equalities" — a chain of one.
**Falsifier:** `PRV-2021`, or a count other than 2.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE user_id IN ('u1')`.
**Expected:** 2 rows — `txn_id` 1 and 3.
**Vacuity:** C2 — `WHERE user_id = 'u1'` returns the same 2 rows in the same session. The two forms
must agree exactly.

### SQLX-082 — a many-element `IN` list
**Intent:** A chain of four equalities, including one term that matches nothing — the shape a
user writes from a spreadsheet.
**Falsifier:** a count other than 5.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE user_id IN ('u1','u2','u3','nobody')`.
**Expected:** `u1` → r1, r3; `u2` → r2, r5; `u3` → r4; `nobody` → none. **5 rows**, `txn_id`
1, 2, 3, 4, 5. Only r6 (`ünïcødé`) is excluded.
**Vacuity:** C2 — `WHERE user_id NOT IN ('u1','u2','u3','nobody')` returns 1 row and `5 + 1 = 6`.

### SQLX-083 — an `IN` list with duplicates
**Intent:** A chain of equalities with a repeated term must not emit a row twice.
**Falsifier:** more than 2 rows, or a `txn_id` appearing twice.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT txn_id FROM txn WHERE user_id IN ('u1','u1','u1')`.
**Expected:** 2 rows, `txn_id` 1 and 3, each once. Identical output to SQLX-081.
**Vacuity:** C1 (`6 in, 2 out`) — the `out` count is the assertion, so it must be read from the
command's own line and not only from the file.

### SQLX-084 — an `IN` list of 19 terms
**Intent:** Round-1 SQL-054 found `IN` lists of 20 or more terms refused, because Calcite converts a
long `IN` into a `SEARCH` over a `Sarg` and `PredicateCompiler` has no `SEARCH` branch — it falls to
`default -> throw unsupported(node)`. `SQL_SUPPORT.md` documents `IN` with no limit. 19 is the
boundary below.
**Falsifier:** 19 terms is refused.
**Setup:** S, D1.
**Steps:** H-RUN `WHERE user_id IN ('u1','x2','x3',…,'x19')` — `u1` plus 18 non-matching values.
**Expected:** it plans and returns **2 rows**, `txn_id` 1 and 3.
**Vacuity:** C1.

### SQLX-085 — an `IN` list of 20 and of 21 terms
**Intent:** The boundary itself. If it refuses, the document has an undocumented limit; the exact
number is what a user needs.
**Falsifier:** 20 behaves differently from 19 **and** the document still claims no limit.
**Setup:** S, D1.
**Steps:** H-VAL with 20 terms, then 21, then 50, then 200. Record the first term count that refuses.
**Expected:** *(if the limit is real)* `PRV-2021` from `unsupported(node)`, whose text is "cannot
compile the expression '<the whole SEARCH node>' (SEARCH) yet". **Two findings to record:** the
undocumented limit itself, and that the message interpolates the entire value list — round 1 saw
48 KB of SQL produce a **99 KB error message**. Measure the message length at 200 terms.
**Vacuity:** C3, and SQLX-084 as the control showing 19 works in the same session.

### SQLX-086 — `IN` with NULL among the terms
**Intent:** `IN (a, NULL)` is `x = a OR x = NULL`; the second disjunct is UNKNOWN for every row, and
`compare` returns `Predicate.False()` for a null constant. A non-matching row must therefore be
dropped rather than kept — which is where SQL's `NOT IN` with a NULL famously returns nothing.
**Falsifier:** `NOT IN ('u1', NULL)` returns 4 rows.
**Setup:** S, D1.
**Steps:** H-RUN `WHERE user_id IN ('u1', NULL)` and `WHERE user_id NOT IN ('u1', NULL)`.
**Expected:** the first returns 2 (r1, r3). The second, in standard SQL, returns **0** — every row is
UNKNOWN. If the two-valued IR returns 4 instead, that is a wrong answer, not a refusal, and is the
most valuable thing this section can find.
**Vacuity:** C1 on both (`6 in`), and C2 — `2 + 0 = 2 ≠ 6` is the expected shortfall.

### §3.5 — `IS NULL`, `BETWEEN`, `LIKE`

### SQLX-087 — `IS NULL` and `IS NOT NULL` on a bare column
**Intent:** Documented ✅. `Predicate.IsNull` is total — never UNKNOWN — so its two forms must
partition the input exactly.
**Falsifier:** the two counts do not add to 6.
**Setup:** S, D1.
**Steps:** H-RUN `WHERE status IS NULL` then `WHERE status IS NOT NULL`.
**Expected:** 1 row — `txn_id` **2**, the only NULL `status` in D1 — and 5 rows: `txn_id` 1, 3, 4,
5, 6. `1 + 5 = 6`.
**Vacuity:** C2, which is the `1 + 5 = 6` identity itself.

### SQLX-088 — `IS NULL` over an expression
**Intent:** Round-1 SQL-028: `nullCheck` requires `operands.get(0) instanceof RexInputRef`, so
`(a || b) IS NULL` is `PRV-2021` while `SQL_SUPPORT.md` lists `IS NULL` as ✅ with no caveat.
**Falsifier:** it plans (in which case the finding is closed and the document is right).
**Setup:** S.
**Steps:** H-VAL, each of: `WHERE (user_id || status) IS NULL`, `WHERE (amount * 2) IS NULL`,
`WHERE UPPER(status) IS NULL`, `WHERE CASE WHEN flagged THEN status END IS NULL`.
**Expected:** `PRV-2021` for each, from `unsupported(call)` — "cannot compile the expression … yet.
Supported: … IS [NOT] NULL …", a message that lists the construct it just refused. **Finding:** the
document needs "on a bare column" against the `IS NULL` row.
**Vacuity:** C3, with `WHERE status IS NULL` planning in the same session as the control.

### SQLX-089 — `BETWEEN` is inclusive at both ends
**Intent:** Documented ✅ "Expanded to `>= AND <=`". An exclusive end silently loses boundary rows,
which is the classic off-by-one nobody notices.
**Falsifier:** a boundary row is missing.
**Setup:** S, D1.
**Steps:** H-RUN `WHERE amount BETWEEN -50 AND 100`, and `WHERE amount BETWEEN 7 AND 7`.
**Expected:** first — `-50, 0, 7, 7, 100` are all in range; `250` is not. **5 rows**, `txn_id`
1, 3, 4, 5, 6. Both endpoints (`-50` at r3 and `100` at r1) are present, which is the assertion.
Second — **2 rows**, `txn_id` 5 and 6, a degenerate range that is not empty.
**Vacuity:** C2 — `WHERE amount NOT BETWEEN -50 AND 100` returns 1 (`txn_id` 2) and `5 + 1 = 6`.

### SQLX-090 — an inverted `BETWEEN`
**Intent:** `BETWEEN 100 AND -50` expands to `>= 100 AND <= -50`, which no row satisfies. It must be
an empty result rather than a swap-and-succeed.
**Falsifier:** rows are returned.
**Setup:** S, D1.
**Steps:** H-RUN `WHERE amount BETWEEN 100 AND -50`.
**Expected:** `ok  6 in, 0 out`.
**Vacuity:** **C1 mandatory** — `6 in` must appear, for the same reason as SQLX-069.

### SQLX-091 — `LIKE` patterns, each hand-evaluated
**Intent:** Documented ✅ against a literal pattern, compiled once at registration.
`Predicate.Like.toRegex` quotes everything that is not `%` or `_`, so a `.` in a pattern is a literal
dot — a promise nothing tests.
**Falsifier:** `LIKE '%.com'` matches `axcom`.
**Setup:** S, a file of 6 rows with `user_id` = `u1`, `u2`, `user`, `a.com`, `axcom`, `ünïcødé`.
**Steps:** H-RUN, one per pattern: `'u%'`, `'%1'`, `'%se%'`, `'u_'`, `'%.com'`, `'ü%'`, `'u1'`, `'%'`.
**Expected:** `'u%'` → `u1, u2, user` (3). `'%1'` → `u1` (1). `'%se%'` → `user` (1).
`'u_'` → `u1, u2` (2) — `user` is four characters and `u_` matches two. `'%.com'` → `a.com` only
(1), **not** `axcom`. `'ü%'` → `ünïcødé` (1). `'u1'` → `u1` (1). `'%'` → all 6.
**Vacuity:** C1 on each (`6 in`), and `'%'` returning 6 as the control that the filter is not simply
rejecting everything.

### SQLX-092 — `LIKE` with `_` against an astral character, and `NOT LIKE` against NULL
**Intent:** `toRegex` maps `_` to a regex `.`, which matches one **UTF-16 code unit**, while
`SUBSTRING` is documented as counting **code points**. An emoji is two code units, so `_` and
`SUBSTRING` disagree about what one character is. Separately, `Like.test` returns `false` when the
column is null — so `LIKE` and `NOT LIKE` both drop the NULL row, which is right and is not stated.
**Falsifier:** `'😀x' LIKE '_x'` returns true (one `_` consumed a surrogate pair), or
`status NOT LIKE 'z%'` keeps D1's row 2.
**Setup:** S, `edge.csv` row 10 (`user_id = 😀x`) and D1.
**Steps:** H-RUN `WHERE user_id LIKE '_x'`, `WHERE user_id LIKE '__x'` over row 10;
`WHERE status LIKE 'o%'` and `WHERE status NOT LIKE 'o%'` over D1.
**Expected:** `'_x'` → **0 rows** and `'__x'` → **1 row**, because `😀` is two code units. Record it:
`_` counts code units while `SUBSTRING` counts code points, and the document promises code points
only for `SUBSTRING`. Over D1: `LIKE 'o%'` → 4 (r1, r3, r5, r6); `NOT LIKE 'o%'` → **1** (r4), not 2.
`4 + 1 = 5 ≠ 6`, and the missing row is the NULL.
**Vacuity:** C1 and C2 (the `5 ≠ 6` shortfall).

### §3.6 — The four documented `WHERE` refusals

### SQLX-093 — `LIKE … ESCAPE` is refused
**Intent:** Documented ❌ `PRV-2021` with a reason: "Without it, `%` and `_` are always wildcards and
cannot be matched literally". The refusal fires on operand count (`operands.size() != 2`).
**Falsifier:** it plans, or the message does not explain the consequence.
**Setup:** S.
**Steps:** H-VAL `SELECT txn_id FROM txn WHERE user_id LIKE 'u!%' ESCAPE '!'`.
**Expected:** `PRV-2021`, message contains "uses LIKE with an ESCAPE clause, which is not built" and
the sentence about `%` and `_`. Length > 40 characters.
**Vacuity:** C3, with `WHERE user_id LIKE 'u%'` planning in the same session.

### SQLX-094 — `LIKE` against a non-literal pattern is refused
**Intent:** Documented ❌ — "the pattern is compiled once when the query is registered, not once per
row — so `LIKE status` is refused".
**Falsifier:** it plans.
**Setup:** S.
**Steps:** H-VAL `WHERE user_id LIKE status`; also `WHERE user_id LIKE ?` (ADR-032's row).
**Expected:** `PRV-2021` with "uses a pattern that is not a literal. The pattern is compiled once
when the query is registered; one that varies per row would be compiled per row." — a message that
says what to do instead (write a literal).
**Finding:** ADR-032's position table says of `WHERE col LIKE ?`: "LIKE is not implemented at all;
`LIKE 'u%'` is refused too". SQLX-091 shows `LIKE 'u%'` **works**. The ADR is stale.
**Vacuity:** C3.

### SQLX-095 — text ordering is refused, and `=` still plans
**Intent:** Documented ❌ `PRV-2021` with the reason stated: "`>` on text needs a collation, and
assuming one gives wrong answers that look right". A blanket ban on text predicates would be a
different, worse behaviour.
**Falsifier:** `status > 'ok'` plans, or `status = 'ok'` is refused alongside it.
**Setup:** S.
**Steps:** H-VAL `WHERE status > 'ok'`, `WHERE status >= 'ok'`, `WHERE status < 'ok'`,
`WHERE status <= 'ok'`, and the two controls `WHERE status = 'ok'`, `WHERE status <> 'ok'`.
**Expected:** the four ordering forms are `PRV-2021` — "only = and <> are supported on text column
'status'; > needs a collation…" with the **correct operator** in the message for each of the four.
The two controls plan.
**Vacuity:** C3 on the four; the two controls are the targeting proof.

### SQLX-096 — comparing text to a number is refused
**Intent:** Documented ❌ `PRV-2021`. Calcite inserts a `CAST` and `ExpressionCompiler.cast` refuses
it because one side is not numeric.
**Falsifier:** it plans, and a string is compared as whatever long sits underneath.
**Setup:** S.
**Steps:** H-VAL `WHERE amount > txn_id` is the **control** (both numeric, must plan);
then `WHERE amount > user_id`, `WHERE user_id = 1`, `WHERE status < 5`.
**Expected:** the control plans (SQLX-073's query). The three others are `PRV-2021` naming the
conversion: "converts between STRING and INT32; Pravaha evaluates numeric conversions only", or
"has SQL type VARCHAR, which Pravaha cannot compute with yet".
**Vacuity:** C3.

---

## §4 — Aggregation, `GROUP BY`, `HAVING` — and the stream/view split

The document's central asymmetry lives here: *the same SQL* is `PRV-2050` over a stream and supported
over a view, and the only difference is `PhysicalPlanBuilder.overBoundedInput()`, which
`SqlSupportMatrixTest` never calls. Every construct in the aggregation table is therefore checked on
**both** surfaces below, with the same hand-computed answer where both are legal.

### SQLX-097 — global `COUNT(*)` over a stream
**Intent:** Documented ✅ "One group, so bounded". Also the base case: if this is wrong, nothing else
in §4 means anything.
**Falsifier:** a count other than 6, or no rows at all.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT COUNT(*) AS n FROM txn`, `--out-schema 'n:INT64'`.
**Expected:** one row, `6`. Note `GlobalAggregate` emits on **end of input** (`InterpretedPipeline`
registers `aggregate::emit` as a finisher), and `QueryRunner` closes the execution before writing —
so the file case produces a row where a continuous registration over an endless stream would not.
**Vacuity:** C1 — `ok  6 in, 1 out`. `0 in, 1 out` would mean a zero counted as six.

### SQLX-098 — `COUNT(col)` versus `COUNT(*)`, global
**Intent:** Q-3: "`COUNT(col)` counted nulls — it was `COUNT(*)`" — **FIXED in `GlobalAggregate`**.
D1's single NULL `status` is the discriminator and the whole reason row 2 exists.
**Falsifier:** `COUNT(status)` returns 6.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT COUNT(*) AS a, COUNT(status) AS b, COUNT(user_id) AS c FROM txn`,
`--out-schema 'a:INT64,b:INT64,c:INT64'`.
**Expected:** one row, `6,5,6`. `6 - 1 = 5`, the one being row 2.
**Vacuity:** C1, and the `a` column being 6 in the same row — a single query, so the two counts
cannot come from different runs.

### SQLX-099 — `COUNT(col)` versus `COUNT(*)`, **keyed**, over a view
**Intent:** Round 2: "Fixed in `GlobalAggregate` only. `KeyedAggregate` and `WindowedAggregate` still
count nulls, each emitting a row whose COUNT contradicts its own SUM." This is the case that pins
`KeyedAggregate`, which is reachable **only** through the bounded view path.
**Falsifier:** for `u2`, `COUNT(status)` equals `COUNT(*)`.
**Setup:** H-VIEW, `v_txn` at 6 rows.
**Steps:** `pravaha query --sql "SELECT user_id, COUNT(*) AS a, COUNT(status) AS b FROM v_txn GROUP BY user_id"`.
**Expected:** four groups. `u1` → `2,2`; **`u2` → `2,1`** (r2's status is NULL, r5's is `ok`);
`u3` → `1,1`; `ünïcødé` → `1,1`. The `u2` row is the assertion: `2 ≠ 1`.
Totals: `2+2+1+1 = 6` and `2+1+1+1 = 5`, matching SQLX-098.
**Vacuity:** C1 (the view committed at 6) and C2 (the totals reconcile to SQLX-098's 6 and 5 in the
same session).

### SQLX-100 — `COUNT(col)` versus `COUNT(*)`, **windowed**
**Intent:** The third of the three operators round 2 says was never checked. `SlicedAggregateState`
has its own `COUNT` and `COUNT_DISTINCT` kinds.
**Falsifier:** W1's `COUNT(status)` is 4.
**Setup:** S, D1, `--stream txn`.
**Steps:** H-RUN `SELECT window_start, window_end, COUNT(*) AS a, COUNT(status) AS b FROM
TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start,
window_end`, `--out-schema 'ws:TIMESTAMP,we:TIMESTAMP,a:INT64,b:INT64'`.
**Expected:** two rows. W1 `[0, 10000000000)` → `a=4` (r1–r4), **`b=3`** (r2's status is NULL);
`4 - 1 = 3`. W2 `[10000000000, 20000000000)` → `a=2`, `b=2`.
**Vacuity:** C1 (`6 in, 2 out`) and C2 — `4 + 2 = 6` and `3 + 2 = 5`, reconciling with SQLX-098.

### SQLX-101 — `TUMBLE`: two windows, boundaries and sums
**Intent:** Documented ✅. Q-1 was "windowed aggregation emitted nothing, ever, silently" — fixed;
this is the case that keeps it fixed with numbers rather than a plan.
**Falsifier:** one window instead of two, or a sum that is not 300/14.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS s FROM
TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start,
window_end`, `--out-schema 'ws:TIMESTAMP,we:TIMESTAMP,n:INT64,s:INT64'`.
**Expected:** exactly two rows.
W1: `window_start = 0`, `window_end = 10000000000`, `n = 4`, `s = 100 + 250 + (-50) + 0 = 300`.
W2: `window_start = 10000000000`, `window_end = 20000000000`, `n = 2`, `s = 7 + 7 = 14`.
`300 + 14 = 314 = SUM(amount)` over the whole file, which is the reconciliation.
**Vacuity:** C1 (`6 in, 2 out`), and the global control `SELECT SUM(amount) FROM txn` returning 314
in the same session. A windowed sum of 300 is only meaningful against a total that is 314.

### SQLX-102 — `TUMBLE` keyed by a column
**Intent:** The shape the README puts on its front page. Key × window, which is the one keyed
aggregate the design admits.
**Falsifier:** fewer than five output rows, or a key's sum that is not the hand-computed one.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT window_start, window_end, user_id, SUM(amount) AS s, COUNT(*) AS n FROM
TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start,
window_end, user_id`.
**Expected:** five rows.
W1/`u1` → `s = 100 + (-50) = 50`, `n = 2`. W1/`u2` → `s = 250`, `n = 1`. W1/`u3` → `s = 0`, `n = 1`.
W2/`u2` → `s = 7`, `n = 1`. W2/`ünïcødé` → `s = 7`, `n = 1`.
Row-count reconciliation: `2 + 1 + 1 + 1 + 1 = 6`. Sum reconciliation: `50 + 250 + 0 + 7 + 7 = 314`.
**Vacuity:** C1 (`6 in, 5 out`) and the two reconciliations, which are what a "200 000 rows collapsed
into 500 keys" bug would break.

### SQLX-103 — a windowed `GROUP BY` that omits the boundaries
**Intent:** Documented explicitly: "Grouping by a windowed stream *without* putting `window_start`
and `window_end` in the `GROUP BY` is refused too — that is the unbounded case wearing a window's
clothes." `windowBoundaryOrdinals` returns null and `buildAggregate` raises `UNBOUNDED_STATE`.
**Falsifier:** it plans.
**Setup:** S.
**Steps:** H-VAL `SELECT user_id, COUNT(*) FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time),
INTERVAL '10' SECOND)) GROUP BY user_id`.
**Expected:** `PRV-2050`, message "this GROUP BY is over a windowed stream but does not group by the
window: add window_start and window_end to the GROUP BY" — an instruction, and it names the two
columns to add.
**Vacuity:** C3, with SQLX-102's query planning in the same session as the control.

### SQLX-104 — `HOP`: size and slide are not swapped, and a row lands in two windows
**Intent:** Documented ✅. `PhysicalPlanBuilder` has a comment admitting the hazard: "Calcite passes
HOP as (slide, size), which is the opposite of the order the SQL reads in. Getting this backwards
produces windows of the wrong width that still fire plausibly." Only an answer catches it.
**Falsifier:** a window whose `window_end - window_start` is 5 s rather than 10 s.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS s FROM
TABLE(HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '5' SECOND, INTERVAL '10' SECOND)) GROUP BY
window_start, window_end`.
**Expected:** slide 5 s, size 10 s, so **every window spans 10 s** and each row falls in two of them.
Windows starting at −5 s, 0 s, 5 s, 10 s:
`[-5s, 5s)` → r1(1s), r2(2s), r3(3s), r4(4s) → `n = 4`, `s = 300`.
`[0s, 10s)` → the same four → `n = 4`, `s = 300`.
`[5s, 15s)` → r5(12s), r6(13s) → `n = 2`, `s = 14`.
`[10s, 20s)` → the same two → `n = 2`, `s = 14`.
Total emitted rows `4 + 4 + 2 + 2 = 12 = 2 × 6`, which is the "each row in two windows" identity.
**Every `window_end - window_start` must equal `10000000000`.**
**Vacuity:** C1 (`6 in, 4 out`), the width assertion, and the `12 = 2 × 6` identity. A swapped
(size, slide) would give 5 s windows and each row in exactly one, i.e. a total of 6 — the two
totals are what tell them apart.

### SQLX-105 — `SUM`, `MIN`, `MAX`, `AVG` over one window
**Intent:** Documented ✅ as a single row covering four functions. Four accumulators, four chances to
be wrong, one matrix entry.
**Falsifier:** any of the four disagrees with the arithmetic.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT window_start, SUM(amount) AS s, MIN(amount) AS mn, MAX(amount) AS mx,
AVG(amount) AS av, COUNT(*) AS n FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time),
INTERVAL '10' SECOND)) GROUP BY window_start, window_end`.
**Expected:** W1 over `{100, 250, -50, 0}`: `s = 300`, `mn = -50`, `mx = 250`, `n = 4`,
`av = 300 / 4 = 75` exactly. W2 over `{7, 7}`: `s = 14`, `mn = 7`, `mx = 7`, `n = 2`,
`av = 14 / 2 = 7` exactly. Both averages are chosen to divide exactly so that SQLX-106 owns the
rounding question alone.
**Vacuity:** C1 (`6 in, 2 out`), and `s = mn + … ` is not assumed — the identity used is
`s / n = av` for both rows.

### SQLX-106 — what `AVG` does when the division is not exact
**Intent:** The accumulators are 64-bit integers throughout (`refuseFloatingPointAggregate`'s own
javadoc). `AVG` over integers therefore either truncates or is promoted, and the document says
neither. Whichever it is, a user adding an average to a dashboard needs to know.
**Falsifier:** the answer is neither `52` nor a value within 1e-9 of `52.333333333333336`.
**Setup:** S, D1, unwindowed and global so all six rows form one group.
**Steps:** H-RUN `SELECT AVG(amount) AS av, SUM(amount) AS s, COUNT(*) AS n FROM txn`,
`--out-schema 'av:INT64,s:INT64,n:INT64'`; then the same with `--out-schema 'av:FLOAT64,…'`.
**Expected:** `s = 314`, `n = 6`, `314 / 6 = 52` remainder `2`. `av` is `52` under integer division.
If the FLOAT64 spelling returns `52.333333333333336`, the value is type-dependent on `--out-schema`
— which is Q-10's shape (the out-schema is not checked against the plan's real type) and a finding.
**Vacuity:** C1, and `s` and `n` in the same row so the division can be checked against its own
operands.

### SQLX-107 — `COUNT(DISTINCT x)` over a windowed stream
**Intent:** Documented ✅. Q-6 says it "hangs five minutes over a stream and is refused over a view"
— **OPEN**. `SlicedAggregateState` has a `COUNT_DISTINCT` kind and `CountDistinctTest` exists, so
the operator is there; the question is whether SQL reaches it.
**Falsifier:** a five-minute stall, or a count that is the row count rather than the distinct count.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT window_start, COUNT(DISTINCT user_id) AS d, COUNT(*) AS n FROM
TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start,
window_end`. Time it.
**Expected:** W1 → `d = |{u1, u2, u3}| = 3`, `n = 4` — **`3 ≠ 4`** is the assertion, and it is only
available because r1 and r3 are both `u1`. W2 → `d = |{u2, ünïcødé}| = 2`, `n = 2`. Completes in
seconds.
**Vacuity:** C1 (`6 in, 2 out`) and the `3 ≠ 4` gap. A `COUNT_DISTINCT` silently implemented as
`COUNT` gives `4` and `2`, which looks entirely reasonable for W2.

### SQLX-108 — `COUNT(DISTINCT x)` over a view
**Intent:** The other half of Q-6. `KeyedAggregate` line 259 has a `COUNT_DISTINCT` branch; the
report says the view path refuses.
**Falsifier:** a `PRV-` refusal (which would confirm Q-6 and contradict the document's ✅), or a
wrong count.
**Setup:** H-VIEW, `v_txn` at 6 rows.
**Steps:** `pravaha query --sql "SELECT COUNT(DISTINCT user_id) AS d, COUNT(*) AS n FROM v_txn"`;
then keyed: `"SELECT status, COUNT(DISTINCT user_id) AS d FROM v_txn GROUP BY status"`.
**Expected:** global → `d = 4` (`u1, u2, u3, ünïcødé`), `n = 6`. Keyed → `ok` → `{u1, u1, u2,
ünïcødé}` distinct = **3**; NULL → `{u2}` = 1; `flagged` → `{u3}` = 1. `3 + 1 + 1 = 5`, which is
**not** 4 — a distinct count per group does not sum to the global distinct count, and asserting it
did would be the mistake this case exists to avoid.
**Vacuity:** C1 and C2 — `COUNT(*)` per group is `4, 1, 1` summing to 6.

### SQLX-109 — `COUNT(DISTINCT x)` does not count NULL
**Intent:** Stated in the document: "`COUNT(DISTINCT x)` does not count NULL."
**Falsifier:** the answer is 3 where 2 is correct.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT COUNT(DISTINCT status) AS d, COUNT(DISTINCT user_id) AS u FROM v_txn"`.
**Expected:** `status` takes values `{ok, NULL, flagged}`; excluding NULL leaves `{ok, flagged}` so
**`d = 2`**. `u = 4`. SQLX-025 showed `SELECT DISTINCT status` returns **3** rows because NULL *is* a
group — so `DISTINCT` and `COUNT(DISTINCT)` differ by exactly one here, `3 - 1 = 2`, and that
difference is the case.
**Vacuity:** C2 — SQLX-025's 3 must be reproduced in the same session.

### SQLX-110 — an aggregate over an expression
**Intent:** Documented ✅ `SUM(amount * 2)`. `PhysicalPlanBuilder`'s `windowBelow` had to be taught to
walk through a `ComputeOperator` for exactly this query; its comment calls the bug "a correct-looking
refusal for an entirely ordinary query".
**Falsifier:** `PRV-2050`, or a sum that is not twice SQLX-101's.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT window_start, SUM(amount * 2) AS s FROM TABLE(TUMBLE(TABLE txn,
DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start, window_end`.
**Expected:** W1 → `2×100 + 2×250 + 2×(-50) + 2×0 = 200 + 500 - 100 + 0 = 600 = 2 × 300`.
W2 → `2×7 + 2×7 = 28 = 2 × 14`.
**Vacuity:** C1 (`6 in, 2 out`) and SQLX-101 in the same session giving 300 and 14. The `× 2`
relation between the two runs is the assertion.

### SQLX-111 — `HAVING`, on an aggregate and without one
**Intent:** Documented ✅ twice — "A filter above the aggregate" and "`HAVING` on an aggregate".
**Falsifier:** `HAVING` is ignored (2 rows come back), or it filters before aggregating.
**Setup:** S, D1.
**Steps:** (a) H-RUN `SELECT window_start, COUNT(*) AS n FROM TABLE(TUMBLE(…10 SECOND)) GROUP BY
window_start, window_end HAVING COUNT(*) > 2`. (b) the same with `HAVING SUM(amount) > 100`.
(c) `… HAVING window_start >= 10000000000` — a HAVING with no aggregate in it.
**Expected:** (a) W1 has `n = 4 > 2` and W2 has `n = 2`, which is **not** `> 2`. **One row**, W1.
(b) `300 > 100` true, `14 > 100` false → **one row**, W1. (c) W1's start is 0 and W2's is
10000000000 → **one row**, W2 — the opposite window from (a) and (b), which is what proves the
HAVING clause is being read rather than a constant applied.
**Vacuity:** C1 on each, and the two-row control without `HAVING` (SQLX-101) in the same session.

### SQLX-112 — `GROUPING SETS`, `CUBE` and `ROLLUP`
**Intent:** `buildAggregate` refuses when `getGroupSets().size() > 1`. Not in the document at all and
not in the matrix — an undocumented refusal, which is the kind a user meets first.
**Falsifier:** any of the three plans, or is refused without a `PRV-` code.
**Setup:** S.
**Steps:** H-VAL, each of: `SELECT user_id, status, COUNT(*) FROM txn GROUP BY GROUPING SETS
((user_id),(status))`; `… GROUP BY CUBE(user_id, status)`; `… GROUP BY ROLLUP(user_id, status)`.
Then the same three through H-VIEW against `v_txn`.
**Expected:** `PRV-2020` "GROUPING SETS, CUBE and ROLLUP are not supported yet" on every one, on both
surfaces. **Finding:** the message is 44 characters and offers no alternative, where every other
refusal in this engine says what to write instead. `SQL_SUPPORT.md` should carry the row.
**Vacuity:** C3.

### SQLX-113 — an aggregate over a floating-point column is refused, naming the column
**Intent:** Q-2, **FIXED as a refusal**. The refusal exists because the alternative was "no rows and
a successful status". `refuseFloatingPointAggregate` exempts `COUNT` and `COUNT_DISTINCT`.
**Falsifier:** `SUM(price)` plans; or `COUNT(price)` is refused, which would over-reach.
**Setup:** S.
**Steps:** H-VAL, each of `SUM(price)`, `MIN(price)`, `MAX(price)`, `AVG(price)` in a windowed
aggregate; then `COUNT(price)` and `COUNT(DISTINCT price)` as the controls. Repeat all six through
H-VIEW against `v_txn`.
**Expected:** the four are `PRV-2020`, each naming the **kind and the column** —
`SUM(price) is over a FLOAT64 column`, `MIN(price) …`, and so on — and each carrying the advice
`SUM(CAST(price AS BIGINT))`. The two `COUNT` forms plan and, over the view, return `6` and
`|{2.5, 4.0, 1.0, 0.5, 1.5, 0.25}| = 6`.
**Vacuity:** C3 on the four; the two controls are the proof the refusal is targeted.
**Note:** the code is `PRV-2020` (UNSUPPORTED_OPERATOR) while the failure is about an expression's
type. `SQL_SUPPORT.md` does not list this refusal at all. Both are findings.

### SQLX-114 — an aggregate over a narrow integer column
**Intent:** Round 2: "it was one type family away from the real hole: SUM/MIN/MAX/AVG over
**INT8/INT16/INT32** die at runtime with a leaked internal schema name." The float refusal does not
cover them, and nothing else does.
**Falsifier:** any of the twelve combinations throws an exception naming an internal schema such as
`txn_windowed_aggregated`, or returns a wrong number under a success status.
**Setup:** S2 (`i8`, `i16`, `i32`), `all.csv` with a `ts` column for windowing. 3 types ×
{SUM, MIN, MAX, AVG} = **12 runs**, enumerated rather than gestured at.
**Steps:** H-RUN, for each type `T` in `{i8, i16, i32}` and each function `F`:
`SELECT window_start, F(T) FROM TABLE(TUMBLE(TABLE allt, DESCRIPTOR(ts), INTERVAL '10' SECOND))
GROUP BY window_start, window_end`.
**Expected:** `i8` = `{1, -1, 0}` → SUM `1 + (-1) + 0 = 0`, MIN `-1`, MAX `1`, AVG `0 / 3 = 0`.
`i16` = `{100, -100, 0}` → SUM `0`, MIN `-100`, MAX `100`, AVG `0`.
`i32` = `{1000, -1000, 0}` → SUM `0`, MIN `-1000`, MAX `1000`, AVG `0`.
All three rows fall in `[0, 10s)`, so there is one output row per run.
**Any of the twelve that fails must fail with a `PRV-` code**, as the FLOAT64 case now does. A raw
runtime exception is a FAIL.
**Vacuity:** C1 on each of the twelve (`3 in, 1 out`). The SUMs are all zero by construction, so the
MIN and MAX values carry the discrimination — a run returning `0, 0, 0, 0` for `i16` is wrong.

### SQLX-115 — unwindowed keyed `GROUP BY` over a **stream** is refused, and names the key
**Intent:** The document devotes a whole section to it. `namesOf` exists specifically so the message
says `user_id` rather than `[1]` — "a person debugging a rejected query at speed will map that
ordinal to the wrong column at least once".
**Falsifier:** it plans; or the message contains an ordinal such as `GROUP BY [1]`.
**Setup:** S.
**Steps:** H-VAL `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id`; then the two-key form
`GROUP BY user_id, status`.
**Expected:** `PRV-2050` on both. The first message contains the literal `GROUP BY user_id`, the
second `GROUP BY user_id, status`. Each contains the suggested rewrite
`GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id` and the closing line "Refusing now rather
than exhausting memory later." Length > 40.
**Vacuity:** C3, with `SELECT COUNT(*) FROM txn` (global, bounded) planning in the same session.

### SQLX-116 — the same SQL over a **view** is supported, and the answer is right
**Intent:** "Same SQL, different answer, and the difference is the input rather than the query." The
single most important pair in this file, and `SqlSupportMatrixTest` cannot express it at all because
it never builds a bounded plan.
**Falsifier:** `PRV-2050` over the view, or group sums that do not reconcile to 314.
**Setup:** H-VIEW, `v_txn` at 6 rows.
**Steps:** `pravaha query --sql "SELECT user_id, COUNT(*) AS n, SUM(amount) AS s, MIN(amount) AS mn,
MAX(amount) AS mx FROM v_txn GROUP BY user_id"`.
**Expected:** four rows.
`u1` → `n=2`, `s = 100 + (-50) = 50`, `mn = -50`, `mx = 100`.
`u2` → `n=2`, `s = 250 + 7 = 257`, `mn = 7`, `mx = 250`.
`u3` → `n=1`, `s = 0`, `mn = 0`, `mx = 0`.
`ünïcødé` → `n=1`, `s = 7`, `mn = 7`, `mx = 7`.
Reconciliation: `2+2+1+1 = 6` and `50 + 257 + 0 + 7 = 314`.
**Vacuity:** C1 (the view committed at 6), C2 (both reconciliations), and **the paired refusal**:
SQLX-115's identical SQL must be refused against the stream in the same build. The pair is the claim.

### SQLX-117 — NULL is a group over a view
**Intent:** "**NULL is a group**, not a row that vanishes — unlike a comparison, where NULL is
UNKNOWN". Two rules for NULL in one engine, one line apart in the document.
**Falsifier:** three groups instead of three including a NULL one, i.e. row 2 disappears.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT status, COUNT(*) AS n, SUM(amount) AS s FROM v_txn GROUP BY status"`.
**Expected:** three rows. `ok` → `n = 4`, `s = 100 + (-50) + 7 + 7 = 64`. NULL → `n = 1`, `s = 250`.
`flagged` → `n = 1`, `s = 0`. Reconciliation `4 + 1 + 1 = 6` and `64 + 250 + 0 = 314`.
**Vacuity:** C2 — the reconciliation only closes if the NULL group is present; drop it and the counts
are `4 + 1 = 5` and the sums `64`, missing 250.

### SQLX-118 — multiple group columns over a view
**Intent:** Documented ✅ in the prose list: "including `SUM`, `MIN`, `MAX`, `AVG`, `COUNT(DISTINCT)`,
multiple group columns, `HAVING`, and parameters". A composite key is where a key-encoding bug lives.
**Falsifier:** four groups (keyed on the first column only) or six (keyed on the row).
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT user_id, status, COUNT(*) AS n FROM v_txn GROUP BY user_id, status"`.
**Expected:** the six `(user_id, status)` pairs are `(u1,ok) ×2`, `(u2,NULL)`, `(u3,flagged)`,
`(u2,ok)`, `(ünïcødé,ok)`. **5 rows**: `(u1,ok) n=2`, `(u2,NULL) n=1`, `(u3,flagged) n=1`,
`(u2,ok) n=1`, `(ünïcødé,ok) n=1`. `2+1+1+1+1 = 6`.
**Vacuity:** C2 — SQLX-116 returns 4 groups and SQLX-117 returns 3 in the same session;
`5 > max(4, 3)` is the arithmetic that proves both columns are in the key.

### SQLX-119 — a bounded **file** is treated as a stream by `pravaha run`
**Intent:** `QueryRunner.run` builds `new PhysicalPlanBuilder().build(...)` with no
`.overBoundedInput()`, while `ViewQuery.physicalOf` calls it. So a query over a six-line file — as
bounded as an input gets — is refused by the unbounded-state rule, and the identical query over a
view of the same six rows is answered. The document's justification is "the difference is the input
rather than the query"; here the input is finite on both sides.
**Falsifier:** `pravaha run` accepts the unwindowed `GROUP BY`, which would make this a non-finding.
**Setup:** S, D1; and H-VIEW `v_txn` over the same file.
**Steps:** H-RUN `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id`; then the identical statement
through H-VIEW against `v_txn`.
**Expected:** H-RUN → `PRV-2050`. H-VIEW → the four rows of SQLX-116. **Finding:** the refusal's own
reasoning ("a stream has no end") is false of the input it is refusing, and `QUICKSTART` step 3 is
documented as teaching the `PRV-2050` lesson through exactly this command. Either `pravaha run`
should declare its input bounded, or the message should say the CLI treats a file as a stream.
**Vacuity:** C3 on the refusal; C1 on the view side.

### SQLX-120 — `SESSION` windows are refused with the reason and an alternative
**Intent:** Documented ❌ `PRV-2020` — "implemented in the runtime, no SQL surface yet".
**Falsifier:** it plans, or the message does not name a supported alternative.
**Setup:** S.
**Steps:** H-VAL, both spellings: `TABLE(SESSION(TABLE txn, DESCRIPTOR(event_time),
DESCRIPTOR(user_id), INTERVAL '5' SECOND))` grouped by the boundaries, and
`GROUP BY SESSION(event_time, INTERVAL '5' SECOND)`.
**Expected:** `PRV-2020` on the first — "SESSION windows exist in the runtime but are not wired to
SQL yet: their state is a per-key interval set rather than a slice grid, so they need the keyed state
store. **Use TUMBLE or HOP.**" The second reaches `buildGroupedWindow`'s `default` branch —
"GROUP BY SESSION is not supported; use TUMBLE or HOP". Both name the alternative.
**Vacuity:** C3.

---

## §5 — `ORDER BY`, `LIMIT`, `OFFSET`

Three documented ❌ rows, all `PRV-2020`, all reaching `PhysicalPlanBuilder.build`'s `default` branch
because Calcite produces a `LogicalSort` for each. The brief asks one question of them: **does the
message name something the user typed?**

### SQLX-121 — `ORDER BY` is refused at plan time
**Intent:** Documented ❌. The refusal must arrive from the planner, not from the first row.
**Falsifier:** it plans, or H-VAL succeeds and only H-RUN fails.
**Setup:** S.
**Steps:** H-VAL `SELECT txn_id FROM txn ORDER BY amount`; then H-RUN the same over D1.
**Expected:** `PRV-2020` from H-VAL, which reads no file. H-RUN fails identically and reads no rows
(`0 in`), because planning precedes ingestion in `QueryRunner`.
**Vacuity:** C3, and `SELECT txn_id FROM txn` planning in the same session.

### SQLX-122 — the `ORDER BY` refusal names a Calcite class, not the user's SQL
**Intent:** Round-1 SQL-048, carried forward: the message is "the planner produced a **LogicalSort**,
which Pravaha cannot execute yet". The brief asks explicitly that the message name something the
user typed. `LogicalSort` is not in the query.
**Falsifier:** the message contains the words `ORDER BY` — in which case this is fixed and the case
passes as an improvement.
**Setup:** S.
**Steps:** H-VAL `SELECT txn_id FROM txn ORDER BY amount`; capture the message verbatim.
**Expected:** *(current)* the message contains `LogicalSort` and does **not** contain `ORDER BY`. It
does end with the supported list — "Supported: scan, filter, project, compute, aggregate, windowing
… inner equi-joins … lookup joins" — which is what gets a user unstuck and is why round 1 graded
this "acceptable, barely". **FAIL against the brief's criterion**, recorded as a message defect, not
a behaviour defect.
**Vacuity:** C3.

### SQLX-123 — `LIMIT` is refused as a sort, in a query with no sort
**Intent:** The sharpest instance: `LIMIT 5` alone becomes a `LogicalSort` with no collation, so the
user is told about an `ORDER BY` they did not write.
**Falsifier:** the message mentions `LIMIT`.
**Setup:** S.
**Steps:** H-VAL `SELECT txn_id FROM txn LIMIT 5`; also `LIMIT 0` and `LIMIT 1000000`.
**Expected:** `PRV-2020` for all three, each naming `LogicalSort`. `LIMIT 0` is worth running
separately: a "limit to nothing" that was silently accepted would return an empty result that looks
like a working query with no matching rows.
**Vacuity:** C3.

### SQLX-124 — `OFFSET` is refused
**Intent:** Documented ❌ `PRV-2020`, and the `FETCH NEXT` spelling is the one a user copies from
a standards reference rather than from MySQL.
**Setup:** S.
**Steps:** H-VAL `SELECT txn_id FROM txn OFFSET 5 ROWS`; and `… OFFSET 2 ROWS FETCH NEXT 2 ROWS ONLY`.
**Falsifier:** either plans.
**Expected:** `PRV-2020`, `LogicalSort`, both forms.
**Vacuity:** C3.

### SQLX-125 — `ORDER BY` over a **view** is refused too
**Intent:** The document says sorting "is meaningful over a *bounded* read of a maintained view, and
that is where it would land if it is added". A user reading that sentence will try it. The refusal
must still arrive, and it must not claim the input is unbounded — the reason does not apply here.
**Falsifier:** it plans (which would make the document's ❌ wrong), or it is refused with `PRV-2050`.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT user_id FROM v_txn ORDER BY amount"`;
`… LIMIT 3`; `… ORDER BY amount LIMIT 3`.
**Expected:** `PRV-2020` on all three — the operator does not exist on either path, and the code must
be the operator code rather than the unbounded-state one. The document's ✅/❌ table is about
constructs, and this is the one place its stream/view asymmetry does **not** apply; record that the
text does not say so.
**Vacuity:** C3, with `SELECT user_id FROM v_txn` answering in the same session.

### SQLX-126 — `ORDER BY ?` and `LIMIT ?`: which refusal wins
**Intent:** ADR-032's table lists both as "Not yet", and is careful to say the reason is the missing
sort operator rather than a parameter decision. So two refusals are in play — `PRV-2063` (not a
value position) and `PRV-2020` (no sort operator) — and `ParameterMetadata.of` runs on the *planned*
tree, before `PhysicalPlanBuilder`. The code the user sees decides what they do next.
**Falsifier:** a raw exception, or a code outside {2020, 2063}.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `pravaha query --sql "SELECT user_id FROM v_txn ORDER BY ?" --params 1`;
`"SELECT user_id FROM v_txn LIMIT ?" --params 3`.
**Expected:** one of `PRV-2063` ("?1 is not in a WHERE clause…") or `PRV-2020` (`LogicalSort`).
Record which, for each. **Finding if `PRV-2063`:** the message explains window sizes and group keys
and says nothing about there being no sort operator, so the user concludes parameters are the
obstacle and writes `ORDER BY amount` — which is also refused. ADR-032 anticipates exactly this and
asks that "a test says so"; this is that test.
**Vacuity:** C3.

---

## §6 — Set operations

Four documented ❌ rows, one line in the document, four different Calcite node names in the messages.

### SQLX-127 — `UNION` is refused at plan time
**Intent:** Documented ❌ `PRV-2020` for all four set operations on one row. `LogicalUnion` reaches
`PhysicalPlanBuilder.build`'s `default` branch.
**Falsifier:** it plans.
**Setup:** H-MTX with `TXN` and `OTHER` registered (the CLI has one stream, so set operations need
two).
**Steps:** build `SELECT user_id FROM txn UNION SELECT user_id FROM other`.
**Expected:** `PRV-2020` naming `LogicalUnion`, raised by `PhysicalPlanBuilder.build`'s `default`
branch before any operator is constructed. Message > 40 characters and ending with the supported list.
**Vacuity:** C3 — the same harness must plan `SELECT user_id FROM txn` and `SELECT user_id FROM
other` separately, so the refusal is about the union and not about the catalog.

### SQLX-128 — `UNION ALL` is refused, and is not confused with `UNION`
**Intent:** `UNION` deduplicates and `UNION ALL` does not, so Calcite may or may not put an aggregate
above the union. If `UNION` were refused as `LogicalAggregate`/`PRV-2050` and `UNION ALL` as
`LogicalUnion`/`PRV-2020`, two spellings of one refusal would carry two codes.
**Falsifier:** the two forms produce different codes.
**Setup:** as SQLX-127.
**Steps:** plan both `UNION` and `UNION ALL` in the same harness; compare codes and messages.
**Expected:** both `PRV-2020`. If `UNION` yields `PRV-2050` instead, that is a finding: the document
lists all four set operations on one row with one code.
**Vacuity:** C3.

### SQLX-129 — `INTERSECT` is refused
**Intent:** Documented ❌ `PRV-2020`. Unlike `EXCEPT`, Calcite's node name here shares a word with
the SQL the user typed, which is the contrast SQLX-130 rests on.
**Falsifier:** it plans.
**Setup:** as SQLX-127.
**Steps:** `SELECT user_id FROM txn INTERSECT SELECT user_id FROM other`.
**Expected:** `PRV-2020` naming `LogicalIntersect` — a word that is at least recognisably the one the
user typed.
**Vacuity:** C3.

### SQLX-130 — `EXCEPT` is refused as "a LogicalMinus"
**Intent:** The brief names this one. `EXCEPT` is refused with a message naming a Calcite class whose
name shares no substring with the SQL keyword, so a user searching their query for "minus" finds
nothing. `INTERSECT`/`LogicalIntersect` and `UNION`/`LogicalUnion` at least share a word; `EXCEPT`
does not.
**Falsifier:** the message contains `EXCEPT`, in which case it is fixed.
**Setup:** as SQLX-127.
**Steps:** `SELECT user_id FROM txn EXCEPT SELECT user_id FROM other`; capture verbatim. Also
`MINUS`, Oracle's spelling, if the LENIENT conformance accepts it.
**Expected:** *(current)* `PRV-2020`, message contains `LogicalMinus` and not `EXCEPT`. **FAIL against
the brief's criterion**, as a message defect. The fix is one clause, the same one SQLX-122 needs.
**Vacuity:** C3.

### SQLX-131 — a set operation between a stream and itself
**Intent:** `UNION` of `txn` with `txn` is the set-operation analogue of the self-join, which is
refused only when the pipeline is built and is the one refusal with no `PRV-` code. If a set
operation is refused earlier, the two refusals disagree about when.
**Falsifier:** an `UnsupportedOperationException` with no code, as the self-join produces.
**Setup:** H-MTX, `TXN` only.
**Steps:** `SELECT user_id FROM txn UNION ALL SELECT user_id FROM txn`.
**Expected:** `PRV-2020` at `build`, before the self-join check in the pipeline builder is ever
reached — so this one **does** carry a code. Record the ordering; it is the evidence that the
self-join's missing code is about where that check lives, not about the input.
**Vacuity:** C3.

### SQLX-132 — set operations over a view
**Intent:** The document's only stated rationale for refusing set operations is ADR-030 tier 4
("a set difference … is what ADR-030 tier 4 puts out of scope on purpose"), which is about scope
rather than unbounded state — so unlike `GROUP BY`, the refusal must **not** soften over a bounded
read. A user who learned from `DISTINCT` that views are more permissive will try this.
**Falsifier:** a set operation plans over a view.
**Setup:** H-VIEW with two registered views, `v_txn` (`SELECT * FROM txn`) and `v_u1`
(`SELECT * FROM txn WHERE user_id = 'u1'`), both committed.
**Steps:** `pravaha query` for each of UNION, UNION ALL, INTERSECT, EXCEPT between
`SELECT user_id FROM v_txn` and `SELECT user_id FROM v_u1`.
**Expected:** `PRV-2020` on all four. The bounded flag changes only the keyed-aggregate refusal
(`PhysicalPlanBuilder.overBoundedInput`'s javadoc: "The only thing it changes is…"), so this is the
case that proves the flag's scope is as narrow as its documentation claims.
**Vacuity:** C3, with `SELECT user_id FROM v_txn` answering in the same session.

### SQLX-133 — every set-operation refusal says what to do instead
**Intent:** The document's own standard: "Every `PRV-` refusal says what is unsupported and, where
there is one, what to write instead." For set operations there is no alternative to write, so the
honest message points at the scope decision.
**Falsifier:** a message under 40 characters, or one that promises the feature is coming.
**Setup:** the four messages captured in SQLX-127–130.
**Steps:** assert on each: length > 40; contains the supported-operator list; contains neither "yet
to be implemented" as a promise nor a version number.
**Expected:** all four end with the same supported list from `build`'s `default` branch. **Finding:**
none of the four mentions ADR-030 or the phrase "out of scope", so a user reads "cannot execute yet"
and waits for a release that is not coming. The document is clearer than the message.
**Vacuity:** not applicable — no state, no timing.

### SQLX-134 — a set operation nested inside a CTE or a derived table
**Intent:** The refusal must not depend on where the operator sits. A union inside a `WITH` is a
different tree shape, and `build` recurses top-down — so the union is reached only after the outer
project and filter have been built.
**Falsifier:** it plans, or the refusal arrives with a different code than at the top level.
**Setup:** H-MTX, `TXN` and `OTHER`.
**Steps:** (a) `WITH u AS (SELECT user_id FROM txn UNION SELECT user_id FROM other) SELECT user_id
FROM u`. (b) `SELECT x.user_id FROM (SELECT user_id FROM txn EXCEPT SELECT user_id FROM other) x`.
(c) `SELECT user_id FROM txn WHERE user_id IN (SELECT user_id FROM other UNION SELECT user_id FROM
third)`.
**Expected:** (a) `PRV-2020`/`LogicalUnion`. (b) `PRV-2020`/`LogicalMinus`. (c) `PRV-2021` — the
subquery is refused by the predicate compiler before the union inside it is ever examined, which is a
**different code for the same query text depending on nesting**. Record it: a user who moves a union
into an `IN` clause is told about an expression rather than an operator.
**Vacuity:** C3 on each.

---

## §7 — Derived tables, CTEs, `VALUES`, subqueries

### SQLX-135 — a derived table returns the right rows
**Intent:** Documented ✅ "Derived tables — `FROM (SELECT …) x`". The matrix plans one; no values.
**Falsifier:** a count other than 4, or the inner filter ignored.
**Setup:** S, D1.
**Steps:** H-RUN `SELECT x.txn_id FROM (SELECT txn_id, amount FROM txn WHERE amount > 0) x
WHERE x.amount < 100`.
**Expected:** the inner keeps `amount > 0`: r1(100), r2(250), r5(7), r6(7) — 4 rows. The outer keeps
`< 100`: r5 and r6. **2 rows**, `txn_id` 5 and 6.
**Vacuity:** C1 (`6 in, 2 out`), and C2 — the inner query alone returns 4 in the same session, so the
outer filter is shown to have removed exactly 2.

### SQLX-136 — derived tables nested three deep
**Intent:** `build` recurses; three levels of `Project` over `Filter` over `Project` is where an
ordinal is renumbered three times. `schemaOf` derives a new schema at each level.
**Falsifier:** a wrong value, which means an ordinal was resolved against the wrong schema.
**Setup:** S, D1.
**Steps:** H-RUN
`SELECT c.v FROM (SELECT b.v AS v FROM (SELECT a.amount * 2 AS v FROM (SELECT amount FROM txn WHERE amount > -1) a) b WHERE b.v < 500) c`.
**Expected:** innermost `amount > -1` keeps 100, 250, 0, 7, 7 (r3's `-50` is dropped) — 5 rows.
Doubling: 200, 500, 0, 14, 14. `< 500` drops the 500. **4 rows**: `200, 0, 14, 14`, summing to 228.
**Vacuity:** C1 (`6 in, 4 out`) and the intermediate control — the two-level form returns 5 rows
summing to `200 + 500 + 0 + 14 + 14 = 728` in the same session; `728 - 500 = 228`.

### SQLX-137 — a single `WITH` clause
**Intent:** Documented ✅ "`WITH` (common table expressions)".
**Falsifier:** `PRV-2001`, or the CTE's filter is lost.
**Setup:** S, D1.
**Steps:** H-RUN `WITH big AS (SELECT txn_id, amount FROM txn WHERE amount >= 100) SELECT txn_id
FROM big`.
**Expected:** `amount >= 100` is r1 (100) and r2 (250) — **2 rows**, `txn_id` 1 and 2. The boundary
row r1 at exactly 100 must be present.
**Vacuity:** C1 and C2 — `WHERE amount < 100` returns 4 and `2 + 4 = 6`.

### SQLX-138 — two CTEs, the second reading the first
**Intent:** Chained CTEs are a different tree from one CTE; Calcite inlines them and a lost reference
shows as an unknown-table error rather than a wrong number.
**Falsifier:** `PRV-2002`/`PRV-2003`, or a count other than 1.
**Setup:** S, D1.
**Steps:** H-RUN `WITH a AS (SELECT txn_id, amount, user_id FROM txn WHERE amount > 0),
b AS (SELECT txn_id, amount FROM a WHERE user_id = 'u2') SELECT txn_id FROM b WHERE amount > 100`.
**Expected:** `a` keeps r1, r2, r5, r6. `b` keeps the `u2` rows among those: r2 (250) and r5 (7).
`amount > 100` keeps r2. **1 row**, `txn_id` 2.
**Vacuity:** C1, and the two intermediate counts (4 then 2) obtained by running the CTE bodies alone
in the same session. `4 → 2 → 1` is the narrowing that proves all three stages ran.

### SQLX-139 — one CTE referenced twice
**Intent:** ADR-025 shares computations by fingerprint. A CTE used twice within one query is a
different question — whether the planner materialises it once or expands it twice — and it decides
whether a join of a CTE to itself becomes the refused self-join.
**Falsifier:** the query is refused as a self-join ("stream 'txn' appears on both sides of this
plan"), which would be a surprising consequence of a CTE.
**Setup:** H-MTX, `TXN` and `OTHER`.
**Steps:** (a) `WITH a AS (SELECT user_id, amount FROM txn) SELECT x.amount FROM a x JOIN other o ON
x.user_id = o.user_id`. (b) `WITH a AS (SELECT user_id FROM txn) SELECT p.user_id FROM a p JOIN a q
ON p.user_id = q.user_id`.
**Expected:** (a) plans and joins — one reference, no self-join. (b) is refused, and the refusal must
be the self-join message: "stream 'txn' appears on both sides of this plan; self-joins are not
supported yet". **This is the refusal the document admits arrives without a `PRV-` code**, so record
the code the user actually sees: through a server it is wrapped as `PRV-1041` with no `2xxx` code at
all. A user who wrote a CTE, not a join, is told about a self-join.
**Vacuity:** C3 on (b); (a) planning is the control that CTEs are not simply broken.

### SQLX-140 — a recursive CTE
**Intent:** `WITH RECURSIVE` is in neither the document nor the matrix. An undocumented construct
must still be refused with a code rather than accepted or crashed.
**Falsifier:** anything without a `PRV-` code.
**Setup:** H-MTX, `TXN`.
**Steps:** `WITH RECURSIVE r(n) AS (SELECT 1 FROM txn UNION ALL SELECT n + 1 FROM r WHERE n < 5)
SELECT n FROM r`.
**Expected:** a `PRV-2001` (the parser rejects it) or `PRV-2020` (`LogicalRepeatUnion`). Either is
acceptable; a `StackOverflowError`, an infinite loop, or a five-minute stall is a **FAIL**. Bound the
run at 60 seconds.
**Vacuity:** C3, and a wall-clock bound so "it refused" is not confused with "it never returned".

### SQLX-141 — `VALUES`, bare and in a `FROM`
**Intent:** Documented ❌ `PRV-2020`. `LogicalValues` reaches `build`'s `default`.
**Falsifier:** either form plans.
**Setup:** H-MTX, `TXN`.
**Steps:** (a) `SELECT * FROM (VALUES (1), (2))`. (b) `VALUES (1), (2)`. (c) `SELECT txn_id FROM txn
WHERE amount IN (VALUES (1), (2))`. (d) `SELECT * FROM (VALUES (1)) AS v(x) JOIN txn ON txn.amount = v.x`.
**Expected:** `PRV-2020` naming `LogicalValues` for (a) and (b). (c) and (d) record which refusal
wins — an `IN (subquery)` is `PRV-2021` and a join against a values list may be either. The point is
that no spelling of `VALUES` is accepted anywhere, and every refusal carries a code.
**Vacuity:** C3 on each.

### SQLX-142 — `IN (subquery)` is refused
**Intent:** Documented ❌ `PRV-2021`. `PredicateCompiler.compile` has no `IN` branch once Calcite has
left the subquery in place, so it hits `unsupported(node)` — which interpolates the node.
**Falsifier:** it plans.
**Setup:** H-MTX, `TXN` and `OTHER`.
**Steps:** `SELECT txn_id FROM txn WHERE user_id IN (SELECT user_id FROM other)`; also
`NOT IN (SELECT …)`. Measure the message length.
**Expected:** `PRV-2021` both times. Round 1 recorded the message as "cannot compile the expression
'IN($0, { LogicalProject(id=[$0]) …" — **the whole sub-plan is dumped into the message**. Record its
length; with a wide inner query this is the seed of the 99 KB message SQLX-085 measures.
**Vacuity:** C3, with `WHERE user_id IN ('a','b')` planning in the same session.

### SQLX-143 — `EXISTS` is refused
**Intent:** Documented ❌ `PRV-2021`.
**Falsifier:** it plans.
**Setup:** H-MTX, `TXN` and `OTHER`.
**Steps:** `SELECT txn_id FROM txn WHERE EXISTS (SELECT 1 FROM other)`; and `NOT EXISTS (…)`.
**Expected:** `PRV-2021` on both, message beginning "cannot compile the expression 'EXISTS({".
**Vacuity:** C3.

### SQLX-144 — a **correlated** subquery is refused, and the message offers the lookup join
**Intent:** `buildLookupJoin` is where a `Correlate` lands, and its refusal is unusually good:
"correlated subqueries are not supported; the only correlated form Pravaha runs is a join against a
lookup table, written as 'JOIN dim FOR SYSTEM_TIME AS OF <time>'". That message is only reached when
the right side is not a `Snapshot`.
**Falsifier:** a message that does not name the alternative, or a raw `ClassCastException` from the
`Correlate` branch.
**Setup:** H-MTX, `TXN` and `OTHER`.
**Steps:** `SELECT txn_id FROM txn WHERE EXISTS (SELECT 1 FROM other WHERE other.user_id =
txn.user_id)`; and the correlated scalar form `SELECT txn_id, (SELECT COUNT(*) FROM other WHERE
other.user_id = txn.user_id) FROM txn`.
**Expected:** `PRV-2021` or `PRV-2020` with a code; record which reaches which branch. At least one
of the two must produce the lookup-join sentence. **Finding if neither does:** the best refusal in
the file is unreachable from any query a user would write.
**Vacuity:** C3.

### SQLX-145 — a scalar subquery is refused
**Intent:** Documented ❌ `PRV-2021`. An **uncorrelated** scalar subquery in the select list is a
different path from SQLX-144's correlated one.
**Falsifier:** it plans and returns a constant column.
**Setup:** H-MTX, `TXN` and `OTHER`.
**Steps:** `SELECT txn_id, (SELECT COUNT(*) FROM other) FROM txn`; and the same in a predicate,
`WHERE amount > (SELECT COUNT(*) FROM other)`.
**Expected:** `PRV-2021` for both, naming the expression. The predicate form is worth running because
Calcite may decorrelate it into a join, in which case the code changes to `PRV-2020` — record it.
**Vacuity:** C3.

### SQLX-146 — window functions (`OVER`) are refused
**Intent:** Documented ❌ `PRV-2021`. Round 1 saw "function 'ROW_NUMBER' in 'ROW_NUMBER() OVER (ORDER
BY $0)' is not supported in a projection" — a message that at least names the function the user wrote.
**Falsifier:** any of them plans.
**Setup:** S.
**Steps:** H-VAL, each of: `ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY event_time)`;
`RANK() OVER (ORDER BY amount)`; `SUM(amount) OVER (PARTITION BY user_id)`;
`LAG(amount) OVER (ORDER BY event_time)`.
**Expected:** `PRV-2021` on each, each naming the function by the name the user typed. The third is
the interesting one — `SUM … OVER` may reach the aggregate path instead and be refused as
`PRV-2020`; record which, because a user told "GROUP BY has no bound on its key space" for a window
function will add a window and still fail.
**Vacuity:** C3, with `SELECT SUM(amount) FROM txn` (global) planning in the same session.

---

## §8 — Parameters

ADR-032's rule is one sentence: **a `?` belongs in a WHERE clause and nowhere else**, with `HAVING`
qualifying because it is a filter above the aggregate. Binding happens when the *physical* plan is
built, so a bound value never passes through a parser.

Parameters are reachable from two surfaces: `pravaha query --sql "… ?" --params v1,v2` (H-VIEW), and
`new PhysicalPlanBuilder().bind(BoundParameters.of(…))` (H-MTX). `pravaha run` has no `--params`
flag at all, so every case here is H-VIEW or H-MTX. `SqlSupportMatrixTest` has **no parameter row of
any kind**, which is why this section is 16 cases against a document that gives the subject four
lines.

### SQLX-147 — a bound value and a written literal compile to the same predicate
**Intent:** ADR-032's strongest claim: "A bound value and a written literal compile to the same
predicate. Not equivalent — **equal**." The `PredicateCompiler` routes both through `compare(...)`.
**Falsifier:** the two forms return different rows, or the parameterised one is refused.
**Setup:** H-VIEW, `v_txn` at 6 rows.
**Steps:** `pravaha query --sql "SELECT txn_id FROM v_txn WHERE user_id = ?" --params u1`; then
`--sql "SELECT txn_id FROM v_txn WHERE user_id = 'u1'"`. Also compare `pravaha explain` output for
the two where the flag is available.
**Expected:** both return **2 rows**, `txn_id` 1 and 3. Identical row sets, identical order.
**Vacuity:** C2 — `--params u2` returns `txn_id` 2 and 5 in the same session. A parameter that was
being ignored would return the same 2 rows for both bindings, or all 6.

### SQLX-148 — a parameter in each of the six comparison operators
**Intent:** ADR-032's table lists `= <> < <= > >=` as one row. Six operators, six `Predicate.Op`
values, and `flip()` involved in half of them. Enumerated rather than gestured at.
**Falsifier:** any operator returns the wrong count.
**Setup:** H-VIEW, `v_txn`.
**Steps:** six queries, `--params 7` each:
`WHERE amount = ?`, `<> ?`, `< ?`, `<= ?`, `> ?`, `>= ?`.
**Expected:** `amount` is `{100, 250, -50, 0, 7, 7}`.
`= 7` → 2 (r5, r6). `<> 7` → 4. `< 7` → 2 (`-50`, `0`). `<= 7` → 4. `> 7` → 2 (100, 250).
`>= 7` → 4.
Identities: `2 + 4 = 6` twice over; `(< 7) + (= 7) + (> 7) = 2 + 2 + 2 = 6`.
**Vacuity:** C2 — the three-way partition summing to 6 is the assertion, and it fails if any one
operator is silently the same as another.

### SQLX-149 — the parameter on the left of the comparison
**Intent:** `PredicateCompiler.comparison` has a separate branch for `RexDynamicParam OP RexInputRef`
that applies `flip(op)`. A missing flip reverses the inequality and returns the complement.
**Falsifier:** `? > amount` with `--params 7` returns 2 rows instead of 2 — specifically, returns the
`{100, 250}` set rather than the `{-50, 0}` set.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE ? > amount --params 7`, and its mirror `WHERE amount < ? --params 7`.
**Expected:** both return the **same 2 rows**, `txn_id` 3 (`-50`) and 4 (`0`). Not `txn_id` 1 and 2.
**Vacuity:** C2 — the two spellings must agree row for row, and `WHERE ? < amount` must return the
other pair (`txn_id` 1, 2) in the same session.

### SQLX-150 — parameters inside an `IN` list
**Intent:** ADR-032: "Calcite expands IN into a chain of equalities, so it arrives on the same path."
**Falsifier:** `PRV-2063`, or a count that ignores one of the bindings.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE user_id IN (?, ?) --params u1,u3`; then `IN (?, ?, ?) --params u1,u3,nobody`.
**Expected:** first → r1, r3 (`u1`) plus r4 (`u3`) = **3 rows**. Second → the same 3 rows; the
unmatched third binding adds nothing.
**Vacuity:** C2 — `--params u1,u1` returns 2 rows (the duplicate collapses, as SQLX-083 showed for
literals) and `--params nobody,nobody` returns 0 with the view still at 6 rows.

### SQLX-151 — parameters at both ends of a `BETWEEN`
**Intent:** ADR-032: "Expanded to `>= AND <=`; both ends bind." Two placeholders in one construct,
which is where a positional off-by-one shows.
**Falsifier:** the bounds are swapped, or only one end binds.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE amount BETWEEN ? AND ? --params 0,100`; then `--params 100,0`.
**Expected:** first → `{0, 7, 7, 100}` are in `[0, 100]` → **4 rows**, `txn_id` 1, 4, 5, 6. Second →
`>= 100 AND <= 0` → **0 rows**. The asymmetry between the two bindings is what proves the positions
are not swapped.
**Vacuity:** C1 (the view at 6 rows before both) and C2 (`4` then `0`, from the same view).

### SQLX-152 — a parameter under `AND`, `OR` and a pushed-down `NOT`
**Intent:** ADR-032: "Including under a NOT that the compiler pushes down." `negate` rebuilds the
comparison with the opposite operator, and the bound constant has to survive that rebuild.
**Falsifier:** the `NOT` form returns the same rows as the un-negated one.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE amount > ? AND flagged --params 0`; `WHERE amount > ? OR flagged --params 100`;
`WHERE NOT (amount > ?) --params 0`.
**Expected:** first → `amount > 0` is r1, r2, r5, r6; `flagged` is r1, r4, r6; intersection
`{1, 6}` = **2 rows**. Second → `{2} ∪ {1,4,6}` = **4 rows**. Third → `amount <= 0` = r3, r4 =
**2 rows**, and it must not include r1.
**Vacuity:** C2 — first and third are complements within the `flagged`-free census: `4 + 2 = 6` for
`amount > 0` versus `NOT (amount > 0)`.

### SQLX-153 — a parameter in `HAVING`
**Intent:** ADR-032: "`HAVING agg(col) > ?` | Yes | A filter above the aggregate… that is not a
special case in the code, it falls out of the rule." `ParameterMetadata.collect` accepts a
placeholder in any `Filter`, and the `HAVING` filter is one.
**Falsifier:** `PRV-2063`, which would mean the rule does *not* fall out and `HAVING` needs a special
case after all.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `SELECT user_id, COUNT(*) AS n FROM v_txn GROUP BY user_id HAVING COUNT(*) > ? --params 1`;
then `--params 2`.
**Expected:** group counts are `u1=2, u2=2, u3=1, ünïcødé=1`. `> 1` → **2 rows** (`u1`, `u2`).
`> 2` → **0 rows**. The 2-then-0 pair is the assertion.
**Vacuity:** C1 (the ungrouped view at 6) and C2 (`--params 0` returns all 4 groups, so
`4 → 2 → 0` across three bindings).

### SQLX-154 — two parameters, and their order
**Intent:** "Placeholders are positional". Binding `(a, b)` where the query reads `?1` then `?2` must
not be applied in reverse, and the failure is silent when both are the same type.
**Falsifier:** swapping the two bindings returns the same rows.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE user_id = ? AND status = ? --params u1,ok`; then `--params ok,u1`.
**Expected:** first → r1 and r3 (both `u1`/`ok`) = **2 rows**. Second → `user_id = 'ok' AND status =
'u1'` = **0 rows**. Both are STRING, so a reversed application would return 2 for both and this is
the only way to see it.
**Vacuity:** C1 (view at 6) and the `2` vs `0` difference.

### SQLX-155 — the same placeholder index used twice
**Intent:** `ParameterMetadata.of` de-duplicates by index — "the same `?1` may appear twice in a
condition, and it is still one value the caller binds once". Whether Calcite even numbers two `?`
tokens the same way is the question; JDBC says it does not.
**Falsifier:** binding one value for a query with two `?` tokens is accepted (arity should be 2), or
binding two is refused.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE amount >= ? AND amount <= ?` with `--params 7,7` (two tokens, two values); record
`ParameterMetadata.count()` via `pravaha explain` or the Flight prepared-statement schema. Then try
`--params 7` and record the code.
**Expected:** two tokens are two parameters, so `count() == 2`; `--params 7,7` returns **2 rows**
(`txn_id` 5, 6) and `--params 7` is `PRV-2061` or `PRV-2060`. The de-duplication comment concerns one
index appearing twice in the *tree* after optimisation, not two `?` tokens — this case records which
behaviour the build actually has.
**Vacuity:** C1 and C2 (`--params -50,250` returns all 6).

### SQLX-156 — a parameter bound to NULL is an empty result, not `IS NULL`
**Intent:** `PredicateCompiler.boundValue`'s javadoc is explicit and the behaviour is surprising: "A
caller who binds NULL to `WHERE tier = ?` usually means 'the rows with no tier', and SQL does not…
Pravaha follows the standard — the predicate becomes `false`". A client library that quietly
rewrote this would change the meaning of a comparison.
**Falsifier:** binding NULL returns row 2, i.e. it was rewritten to `IS NULL`.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE status = ?` bound to NULL (through the Flight/SDK path, since `--params` on the CLI
cannot express a null distinctly from an empty string — record that limitation as a finding);
then `WHERE status IS NULL` as the contrast.
**Expected:** the bound-NULL query returns **0 rows**. `IS NULL` returns **1 row**, `txn_id` 2.
`0 ≠ 1` is the case.
**Vacuity:** **C1 mandatory** — the view must be shown at 6 rows immediately before, because "0 rows"
is exactly what an empty view also returns. `BoundParameters.checkAssignable` returns early for null,
so the query is otherwise well-formed and must not error.

### SQLX-157 — a value of the wrong type is refused with `PRV-2062`
**Intent:** `checkAssignable` names the placeholder number and the class bound. "Finding it at row
time gives them a class cast inside an operator."
**Falsifier:** a `ClassCastException`, or a silent coercion that returns rows.
**Setup:** H-VIEW / H-MTX (H-MTX is needed to bind a genuinely wrong Java type).
**Steps:** four bindings, each against `WHERE user_id = ?` (STRING) and `WHERE amount = ?` (INT64):
a `Boolean` to STRING; an `Integer` to STRING; a `String` to INT64; a `byte[]` to INT64.
**Expected:** `PRV-2062` on each, message of the shape "?1 is used where the query needs STRING, but
a Boolean was bound". Note `checkAssignable` deliberately accepts `Integer`/`Long` for a FLOAT
placeholder — so binding `1` to a FLOAT64 column must **succeed**; include it as the control that
the check is a type rule and not a class-identity rule.
**Vacuity:** C3 — the refusal comes from the plan build, before any row.

### SQLX-158 — too few values
**Intent:** Two codes are in play. `BoundParameters.at` raises `PRV-2060` when a placeholder index is
out of range; `requireArity` raises `PRV-2061` when the counts differ, and `ViewQuery.execute` calls
`requireArity` **first**. So the user should see 2061, and 2060 should be unreachable from the view
path — which makes it a documented code that may never be emitted, like Q-13's `PRV-2003`.
**Falsifier:** a raw `ArrayIndexOutOfBoundsException`, or no error at all.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE user_id = ? AND amount > ?` with `--params u1` (one value for two placeholders);
and with no `--params` at all.
**Expected:** `PRV-2061`, "this statement has 2 placeholders and 1 value was bound" — note the
singular/plural handling, which the code goes out of its way to get right. **Record whether
`PRV-2060` is reachable from any shipped surface**; the document's error table lists 2060–2063 as a
range and `ERRC` owns whether each is reachable.
**Vacuity:** C3.

### SQLX-159 — too many values
**Intent:** The mirror, and the one a client loop produces.
**Falsifier:** the extra value is silently ignored and rows come back.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `WHERE user_id = ?` with `--params u1,extra`; and a statement with **no** placeholders at
all, `SELECT txn_id FROM v_txn`, with `--params u1`.
**Expected:** `PRV-2061` both times — "this statement has 1 placeholder and 2 values were bound", and
"has 0 placeholders and 1 value was bound". The zero-placeholder case is the one a client most often
hits and the one most likely to be waved through.
**Vacuity:** C3.

### SQLX-160 — a parameter in the select list is refused
**Intent:** ADR-032: "Select list — `SELECT col * ?` | **No** | Computes a different answer from the
same rows." `ParameterMetadata.collect` visits every non-`Filter` node with a `RexShuttle` that
throws on sight.
**Falsifier:** it plans and multiplies.
**Setup:** H-VIEW, `v_txn`.
**Steps:** `SELECT amount * ? FROM v_txn --params 2`; `SELECT ? FROM v_txn --params 1`;
`SELECT CASE WHEN amount > ? THEN 1 ELSE 0 END FROM v_txn --params 50`.
**Expected:** `PRV-2063` on all three, message beginning "?1 is not in a WHERE clause" and explaining
that a window size or group key "decides what the query *is*". The third is worth listing separately:
the placeholder is inside a `CASE` in a projection, which is a value position in JDBC's sense and is
still refused here — the rule is one position accepted, not a list forbidden.
**Vacuity:** C3.

### SQLX-161 — a parameter as a window size, a group key, or a table name
**Intent:** The three positions ADR-032 argues about at length. A parameterised window size "is not
one query with a knob, it is a family of queries".
**Falsifier:** any of them plans, or any produces a code other than 2063/2001.
**Setup:** H-MTX (a windowed query needs the stream path) and H-VIEW.
**Steps:** (a) `SELECT window_start, COUNT(*) FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time),
?)) GROUP BY window_start, window_end`. (b) `SELECT user_id, COUNT(*) FROM v_txn GROUP BY ?`.
(c) `SELECT * FROM ?`. (d) `SELECT ?, COUNT(*) FROM v_txn GROUP BY user_id`.
**Expected:** (a) and (b) → `PRV-2063`. (c) → `PRV-2001`, a parse error: a table name is not a value
expression, so the parser rejects it before `ParameterMetadata` is reached, and the user gets a
syntax error rather than the explanation. Record it. (d) → `PRV-2063`.
**Vacuity:** C3 on each, with the literal form of (a) — `INTERVAL '10' SECOND` — planning in the same
session.

### SQLX-162 — parameter placement on a continuous query is classified, not assumed
**Intent:** `ParameterPlacement.of` decides `TAP` or `REGISTRATION` by whether the plan's output row
type carries the parameter's column. ADR-032: "The classification is reported, not silent", because
a `REGISTRATION` placement means one computation per distinct binding. Nothing in the matrix touches
it; nothing in `SQL_SUPPORT.md` mentions it.
**Falsifier:** both queries classify the same way, or no classification is surfaced anywhere a user
can see.
**Setup:** H-MTX over `TXN`, plus `pravaha register` for the reporting half.
**Steps:** classify both of ADR-032's own examples:
(a) `SELECT user_id, SUM(amount) FROM TABLE(TUMBLE(…)) GROUP BY window_start, window_end, user_id`
with `WHERE user_id = ?` — the view carries `user_id`.
(b) `SELECT window_start, SUM(amount) FROM TABLE(TUMBLE(…)) GROUP BY window_start, window_end` with
`WHERE user_id = ?` — the aggregate removed `user_id`.
**Expected:** (a) → `TAP`, reason "the view carries 'user_id', so subscribers can filter on it and
share one computation". (b) → `REGISTRATION`, reason "'user_id' is not in the view — the query
aggregates it away…". `allTappable` is true for (a) and false for (b).
**Finding to record:** whether `describe()` reaches any shipped surface — the CLI, the register
response, a log line. ADR-032 says the classification "is reported rather than done quietly"; if it
reaches nothing, that promise is unkept, which is the same shape as finding I-6.
**Vacuity:** the two classifications differing is the assertion; a classifier that always returned
`TAP` would pass (a) alone.

---

## §9 — Hostile, malformed and awkward SQL

The document's promise for this whole section is one line: "**A query Pravaha cannot run is refused
when it is planned, with a `PRV-` code and an explanation.** It is never accepted and then
approximated." These twenty cases are the adversarial reading of that sentence.

### SQLX-163 — the empty string
**Intent:** The degenerate input. `SqlPlanner.plan("")` reaches `planner.parse` and the
`SqlParseException` is wrapped as `PRV-2001` with Calcite's message — which for an empty input may be
null, and `rootMessage` only falls back to `toString()` for the *validation* path, not the parse one:
`new PravahaException(SqlErrors.PARSE_FAILED, e.getMessage(), e)` passes the message through raw.
**Falsifier:** a `NullPointerException`, or a message of literally `PRV-2001 null`, or a 500 with no
code.
**Setup:** S.
**Steps:** H-VAL `--sql ""`; then the same through H-VIEW (`pravaha query --sql ""`) and through
`pravaha register --sql-file` with an empty file.
**Expected:** `PRV-2001` with a non-empty explanation on every surface. **This is the same defect
family as Q-14** (SQLX-173): a parse failure whose message is null renders as the bare text
`PRV-2001  null`, which tells the user nothing at all.
**Vacuity:** C3.

### SQLX-164 — only whitespace
**Intent:** Distinct from the empty string: the parser gets tokens to skip and still finds no
statement. Also distinct in the CLI, where `Args` may strip it.
**Falsifier:** a code other than `PRV-2001`, or the CLI reporting "--sql is required" for a value
that was supplied.
**Setup:** S.
**Steps:** H-VAL with `--sql` set to, in turn: three spaces; a tab and two newlines; and a single
**U+00A0 no-break space** (whitespace to a human, not to a tokeniser).
**Expected:** `PRV-2001` for all three. The U+00A0 case should produce a *different* message naming
the character, since it is an unexpected token rather than an absent one.
**Vacuity:** C3.
**Related finding:** round 1's Q-9 — any `--sql` value starting with `--` is silently replaced by the
string `true` and planned as that. Run `--sql "--x"` here as a fourth input and record whether `Args`
still does it; if so, the message will be about the word `true` rather than about the query.

### SQLX-165 — only a comment
**Intent:** A file containing nothing but a comment is what a half-written `--sql-file` looks like.
**Falsifier:** it plans (a comment is not a statement), or the error names a line the user cannot see.
**Setup:** S.
**Steps:** H-VAL with `--sql "-- just a comment"`; `--sql "/* block */"`; and a three-line file of
`--` comments through `pravaha register --sql-file`.
**Expected:** `PRV-2001` each, with a line and column. The document promises 2001 preserves "Calcite's
line and column", so a three-line comment must report line 3 or 4, not line 1.
**Vacuity:** C3.

### SQLX-166 — comments in awkward positions
**Intent:** Comments that are *not* the whole input, placed where a naive pre-processor would break
the query: between a keyword and its operand, spanning lines, and — the one that matters — inside a
string literal, where `--` is data rather than a comment.
**Falsifier:** the string-literal case strips the text, changing the answer.
**Setup:** S, D1 with one row's `status` set to the literal `a--b`.
**Steps:** H-RUN each of:
(a) `SELECT /* c */ txn_id FROM txn`;
(b) `SELECT txn_id FROM txn -- trailing` then a newline then ` WHERE amount > 0`;
(c) `SELECT txn_id FROM /* multi` newline `line */ txn`;
(d) `SELECT txn_id FROM txn WHERE status = 'a--b'`;
(e) `SELECT txn_id FROM txn WHERE status = '/* not a comment */'`.
**Expected:** (a) 6 rows. (b) the `--` comment runs to end of line, so the `WHERE` on the next line
**is** part of the query: 4 rows (`amount > 0`). (c) 6 rows. (d) exactly the row whose status is
`a--b` — the comment marker inside a literal is data. (e) 0 rows, with `6 in`, and **no** parse error.
**Vacuity:** C1 on each; (b)'s 4 versus (a)'s 6 is the arithmetic that proves the comment ended at
the newline rather than swallowing the rest.

### SQLX-167 — a trailing semicolon
**Intent:** Every user pastes one. Calcite's `parseQuery` does not accept a statement terminator.
**Falsifier:** an unhelpful error for a harmless character, or an inconsistency between surfaces.
**Setup:** S, D1.
**Steps:** H-VAL and H-RUN `SELECT txn_id FROM txn;`; `SELECT txn_id FROM txn ;` (spaced); and the
same with a trailing newline, through `pravaha register --sql-file`.
**Expected:** consistent across all three and across H-VAL, H-RUN and H-VIEW: either all accepted
(and H-RUN returns 6 rows) or all `PRV-2001` naming the `;` and its column. **A surface that accepts
it while another refuses is a finding** — `pravaha register --sql-file` reads a file, and files end
with newlines.
**Vacuity:** C1 where it is accepted; C3 where it is not.

### SQLX-168 — two statements separated by a semicolon
**Intent:** The injection shape. Pravaha's defence is that a bound value never reaches a parser
(ADR-032), but SQL *text* does — from `--sql` and from `--sql-file`. Only the first statement may
ever run, and ideally neither.
**Falsifier:** the second statement is executed, or is silently discarded while the first runs.
**Setup:** S, D1; H-VIEW with `v_txn`.
**Steps:** (a) H-VAL `SELECT txn_id FROM txn; SELECT user_id FROM txn`.
(b) H-VIEW `pravaha query --sql "SELECT txn_id FROM v_txn; DROP VIEW v_txn"`.
(c) H-VIEW `pravaha query --sql "SELECT txn_id FROM v_txn WHERE user_id = 'x'; DELETE FROM v_txn"`.
(d) the same as (c) but with the second statement supplied as a **parameter value**:
`WHERE user_id = ?` bound to the 25-character string `x'; DELETE FROM v_txn --`.
**Expected:** (a)–(c) → `PRV-2001`; nothing is executed. (d) → a normal query returning **0 rows**,
with `v_txn` still registered and still at 6 rows afterwards: the bound value is compared as a string
and never parsed. **After (b) and (c), `pravaha queries` must still list `v_txn`** — that check is
the case.
**Vacuity:** C1 on (d) (`v_txn` at 6 rows before and after), and the post-condition listing after
(b) and (c). A refusal that also dropped the view would pass a "refused" assertion.

### SQLX-169 — keyword case
**Intent:** `SqlPlanner` sets `withUnquotedCasing(UNCHANGED)` and `withCaseSensitive(true)` for
*identifiers*. Keywords are a separate question and nothing states the answer.
**Falsifier:** `select … from` is refused while `SELECT … FROM` is accepted.
**Setup:** S, D1.
**Steps:** H-RUN the same query in four spellings: all upper (`SELECT … FROM … WHERE`), all lower,
mixed (`SeLeCt … FrOm … wHeRe`), and with `AS`/`and`/`Between` mixed within one statement.
**Expected:** all four plan and return the identical 4 rows for `WHERE amount > 0`. Keywords are
case-insensitive; identifiers are not (SQLX-170).
**Vacuity:** C1 on each (`6 in, 4 out` four times), and the four output files compared with `cmp`.

### SQLX-170 — identifier case is significant
**Intent:** The planner's javadoc explains the choice at length: Calcite's Oracle default would
"silently turn `user_id` into `USER_ID` and fail to resolve it". The consequence is that `USER_ID` is
a *different* identifier and must not resolve.
**Falsifier:** `SELECT USER_ID FROM txn` returns the column, which would mean the setting is not in
force.
**Setup:** S, D1.
**Steps:** H-VAL each of `SELECT user_id FROM txn`, `SELECT USER_ID FROM txn`,
`SELECT User_Id FROM txn`, `SELECT "user_id" FROM txn`, `SELECT "USER_ID" FROM txn`;
and `FROM TXN` versus `FROM txn`.
**Expected:** `user_id` and `"user_id"` resolve. `USER_ID`, `User_Id` and `"USER_ID"` are `PRV-2002`
"Column 'USER_ID' not found in any table", with the message preserving the case the user typed.
`FROM TXN` is `PRV-2002` or `PRV-2003` naming the stream. The document says nothing about
case-sensitivity anywhere — **a finding**, because it is the first thing a user from a Postgres
background gets wrong.
**Vacuity:** C3 on the refusals; the two resolving forms are the control.

### SQLX-171 — a 100 KB query
**Intent:** Size as an input. Round 1 saw a 48 KB query produce a 99 KB **error message**, so the
interesting measurements are planning time, memory, and the length of anything that comes back.
**Falsifier:** a `StackOverflowError`, an OOM, a hang beyond 60 s, or an error message larger than
the query.
**Setup:** S, D1. Build the query programmatically: `SELECT txn_id FROM txn WHERE amount > 0` followed
by `OR amount > n` repeated until the text exceeds 102 400 bytes (roughly 5 800 disjuncts at ~18
bytes each).
**Steps:** H-VAL, timing the call; then H-RUN.
**Expected:** either it plans in bounded time and H-RUN returns the **4 rows** of `amount > 0` — the
extra disjuncts are all weaker — or it refuses with a `PRV-` code. Record plan time and peak heap.
A refusal message must be **shorter than the query**, and certainly under 8 KB.
**Vacuity:** C1 (`6 in, 4 out`), and the simple `WHERE amount > 0` control returning the same 4 rows
in the same session — the assertion is that 5 800 redundant disjuncts do not change the answer.

### SQLX-172 — a thousand columns in the select list
**Intent:** Width rather than depth. `ComputeOperator` holds one compiled `Expression` per column and
`StreamSchema.Builder` one field per column; `--out-schema` must name 1 000 columns too, which is
itself a test of the schema-spec parser.
**Falsifier:** a truncated output row, a silently dropped column, or a schema-spec parse failure that
names no position.
**Setup:** S, D1. Generate `SELECT amount + 0 AS c0, amount + 1 AS c1, …, amount + 999 AS c999 FROM txn`
and a matching `--out-schema 'c0:INT64,…,c999:INT64'`.
**Steps:** H-RUN.
**Expected:** six rows of 1 000 fields. Row 1 (`amount = 100`) reads `100, 101, 102, …, 1099`; the
last field is `100 + 999 = 1099`. Row 3 (`amount = -50`) ends at `-50 + 999 = 949`. Assert the field
**count** is 1 000 on every line and the last field of every line equals `amount + 999`.
**Vacuity:** C1 (`6 in, 6 out`) and the last-field check, which is what a truncation at 256 or 512
columns would break while a "row present" assertion passed.

### SQLX-173 — one thousand nested parentheses
**Intent:** Q-14, **OPEN**: "A deeply nested predicate refuses as the literal text `PRV-2001  null`."
The brief names it. The cause is in `SqlPlanner.plan`: the parse branch passes `e.getMessage()`
straight through, and a failure with no message renders as the four characters `null`. The outer
`catch (Exception e)` does call `rootMessage`, which falls back to `t.toString()` — but the parse
path never reaches it.
**Falsifier:** the message is exactly `null`, or the process dies.
**Setup:** S. Build `SELECT txn_id FROM txn WHERE ` + 1 000 opening parentheses + `amount > 0` +
1 000 closing parentheses.
**Steps:** H-VAL at depths 10, 100, 500, 900, 1 000, 2 000. Record the first depth that fails and the
verbatim message at each.
**Expected:** *(correct)* shallow depths plan and H-RUN returns the 4 rows of `amount > 0` — 1 000
redundant parentheses change nothing. At whatever depth the parser gives out, the refusal must carry
a `PRV-` code **and a non-null explanation** naming nesting depth.
**Expected:** *(current, to be confirmed)* the literal text `PRV-2001  null`. That is a FAIL against
the document's "with a `PRV-` code **and an explanation**".
**Vacuity:** C1 at the depths that succeed — H-RUN at depth 100 must return the same 4 rows as depth
0, which proves the parentheses are inert rather than the query being silently emptied.

### SQLX-174 — a thousand conjuncts, and a thousand `IN` terms
**Intent:** Depth of a different kind: `Predicate.And` holds a list, and `compileAll` recurses per
operand. SQLX-085 established that `IN` gives out around 20 terms; a 1 000-term `AND` chain of
equalities is the rewrite a user reaches for next, so it must not give out too.
**Falsifier:** a `StackOverflowError`, or a wrong row count.
**Setup:** S, D1.
**Steps:** (a) H-RUN `WHERE amount > -1000 AND amount > -999 AND … AND amount > -1` — 1 000
conjuncts, each weaker than the last. (b) H-RUN `WHERE user_id = 'u1' OR user_id = 'x2' OR … OR
user_id = 'x1000'` — 1 000 disjuncts. (c) H-VAL the same 1 000 values as an `IN` list.
**Expected:** (a) the strongest conjunct is `amount > -1`, which keeps `100, 250, 0, 7, 7` —
**5 rows**, `txn_id` 1, 2, 4, 5, 6; only r3 at `-50` is dropped. (b) `u1` matches r1 and r3 →
**2 rows**. (c) `PRV-2021` (SEARCH), per SQLX-085 — and the message length is the finding.
**Vacuity:** C1 on (a) and (b); C2 — the one-term versions (`WHERE amount > -1` and `WHERE user_id =
'u1'`) return 5 and 2 in the same session, identically.

### SQLX-175 — unicode identifiers, quoted and unquoted
**Intent:** Round-1 SQL-059 passed: a CJK identifier resolves both ways. Re-establish it on this
build and extend to characters that stress the parser differently — a precomposed accent and a Greek
letter.
**Falsifier:** mojibake in the output schema, or a quoted form resolving while the unquoted one does
not (or vice versa) for the same name.
**Setup:** `--schema 'id:INT64,金额:INT64,naïve:STRING,Ωmega:INT64'`.
**Steps:** H-VAL each of `SELECT 金额 FROM txn`, `SELECT "金额" FROM txn`, `SELECT naïve FROM txn`,
`SELECT "naïve" FROM txn`, `SELECT Ωmega FROM txn`, `SELECT "Ωmega" FROM txn`. Then H-RUN
`SELECT 金额 FROM txn` over a file and compare bytes.
**Expected:** all six resolve, and each output schema reproduces the identifier byte for byte in
UTF-8 — `金额` is `E9 87 91 E9 A2 9D`. No `?` characters, no U+FFFD replacement characters.
**Vacuity:** C1 on the run; the byte-level comparison of the schema line, not just a visual check.

### SQLX-176 — unicode data through the whole pipeline
**Intent:** Identifiers were checked in round 1; **values** were not. D1's row 6 is `ünïcødé` for this
case, and `edge.csv` row 10 is an emoji followed by `x`.
**Falsifier:** a value that differs by one byte anywhere, or a comparison that fails on a
non-ASCII literal.
**Setup:** S, D1 and `edge.csv` row 10.
**Steps:** H-RUN each of:
(a) `SELECT user_id FROM txn` — identity;
(b) `SELECT user_id FROM txn WHERE user_id = 'ünïcødé'`;
(c) `SELECT UPPER(user_id) FROM txn WHERE user_id = 'ünïcødé'`;
(d) `SELECT user_id || '✓' FROM txn WHERE user_id = 'ünïcødé'`;
(e) `SELECT SUBSTRING(user_id FROM 1 FOR 2) FROM txn WHERE user_id = 'ünïcødé'`;
(f) `SELECT user_id FROM txn WHERE user_id LIKE 'ü%'`;
(g) (c) again over the emoji row.
**Expected:** (a) row 6 is `ünïcødé`, 10 bytes of UTF-8 for 7 code points. (b) 1 row. (c) `ÜNÏCØDÉ`.
(d) `ünïcødé✓`. (e) `ün` — 3 bytes, two code points. (f) 1 row. (g) the emoji unchanged followed by
`X`: `UPPER` must leave the astral character untouched and uppercase the `x`. A code-unit-wise
implementation that mangles the surrogate pair shows as the bytes `EF BF BD`.
**Vacuity:** C1 on each, and every assertion made on **bytes**, not on rendered text.

### SQLX-177 — a reserved word as a **column** name
**Intent:** Round-1 SQL-058. The important property held — unquoted `user` is neither silently
resolved to the session user nor to the column — but the message names a function the user did not
call, with no hint that quoting is the fix.
**Falsifier:** `SELECT user FROM txn` returns the column, or returns the session user, or returns
anything at all.
**Setup:** `--schema 'id:INT64,user:STRING,all:INT64,one:INT64,sensitive:STRING'`.
**Steps:** H-VAL each of: `SELECT user FROM txn`; `SELECT "user" FROM txn`;
`SELECT id FROM txn WHERE user = 'alice'`; `SELECT id FROM txn WHERE "user" = 'alice'`;
and the same four for `all`, `one`, `sensitive`.
**Expected:** every **quoted** form resolves and returns the column. Every **unquoted** form is
refused with a `PRV-` code — and the message is the finding: `PRV-2021 function 'USER' in 'USER' is
not supported in a projection. Supported: + - * / %, ABS, …`. A user with a column called `user` is
told about a function they never called. **The one-line fix named in round 1 — "`user` is a reserved
word; write the quoted form to mean the column" — is still not present.**
**Vacuity:** C3, and the quoted forms as the control that the column exists.

### SQLX-178 — a reserved word as a **view name** is refused at registration
**Intent:** The brief says this is now refused; `QueryRegistry.requireSayableName` is the mechanism,
and it does it the right way — by asking Calcite's parser to parse `SELECT 1 FROM <name>` rather than
keeping a hand-written keyword list that "is wrong the first time Calcite changes".
**Falsifier:** `pravaha register --name user …` is accepted, leaving a view no query can read.
**Setup:** a running server with `txn` bound; `q.sql` containing `SELECT * FROM txn`.
**Steps:** `pravaha register --name user --sql-file q.sql`. Then `pravaha queries`.
**Expected:** refused. Message: "'user' is a reserved word in SQL, so no query could read the view.
Choose a name that can appear in a FROM clause unquoted." `pravaha queries` lists nothing new.
**Finding to record:** the error code is `RegistryErrors.NAME_IN_USE` = **`PRV-8001`**, whose
documented meaning is "the name is already registered". A reserved word is not a name in use, so one
code now means two things — which is exactly what area `ERRC` exists to catch ("meaning one thing").
**Vacuity:** C1 — before the attempt, `pravaha queries` must be empty of `user`; after it, still
empty. A refusal that also left a half-registered query is the failure mode D-4 describes.

### SQLX-179 — `all`, `one`, `sensitive`, and a control
**Intent:** The brief names four words. `requireSayableName`'s parse probe decides each, and the set
of Calcite reserved words is version-specific — which is the reason the probe exists and the reason
this case enumerates rather than assumes.
**Falsifier:** any of the four is accepted, or the **control** is refused.
**Setup:** as SQLX-178.
**Steps:** `pravaha register --name <n> --sql-file q.sql` for each of:
`user`, `all`, `one`, `sensitive`, `value`, `year`, `count`, `table`, `select`;
then the controls `v_txn`, `txn_summary`, `_private`, `a1`.
**Expected:** each of the nine is refused with the reserved-word message; each of the four controls
is accepted and becomes readable with `pravaha query --sql "SELECT * FROM <n>"` returning 6 rows.
Record the exact set that refuses — it is version-specific and is the thing to re-run after a Calcite
upgrade. **Note the interaction with `pravaha.streams.<n>`**: finding I-12 says a *stream* may still
be declared with a reserved word as its name and nothing warns, because `requireSayableName` guards
registration and not configuration. Declare a stream named `empty` in `application.yaml` and confirm
whether I-12 is still open.
**Vacuity:** C1 — the four controls being readable afterwards is what proves the refusals are about
the names and not about the server.

### SQLX-180 — a name that is not an identifier at all
**Intent:** `requireSayableName` runs the regex `[A-Za-z_][A-Za-z0-9_]*` **before** `requireName`'s
null check — `requireName` calls `requireSayableName(name)` on its first line and tests
`name == null` on its second. A null name therefore throws a `NullPointerException` from
`name.matches(...)` and never reaches the message "a registration needs a name".
**Falsifier:** a bare `NullPointerException` reaching a client.
**Setup:** as SQLX-178.
**Steps:** attempt registration with each of: the empty string; three spaces; `1abc`; `a-b`; `a.b`;
`../escape`; `v_txn ` (a trailing space); a 500-character name; `金额` (a valid identifier to a human
and not to the regex); and — through the API rather than the CLI, which cannot express it — a null
name.
**Expected:** every one refused with `PRV-8001` and the "cannot be used as a view name: a name is
written in a FROM clause, so it must be a plain identifier" message. **Two findings:** (1) the null
case, which the code's own ordering makes an NPE rather than "a registration needs a name"; (2)
`金额` is refused by the ASCII regex although SQLX-175 shows the planner resolves it perfectly well
as a quoted identifier — so the regex is stricter than the parser probe it is paired with, and a
user with non-ASCII names cannot name a view.
**Vacuity:** C1 — `pravaha queries` unchanged after every attempt, and D-6's traversal check:
`../escape` must not create a directory above the checkpoint root.

### SQLX-181 — a query naming a stream that does not exist
**Intent:** The document's error table promises `PRV-2003` = "The query names a stream that is not
registered". Q-13, **OPEN**: `PRV-2003` is "documented and never emitted", and the "Known streams"
list "lists views and omits every configured base stream".
**Falsifier:** `PRV-2003` never appears **and** the document still lists it — or the "Known streams"
list names something that is not a queryable name.
**Setup:** S for H-VAL; H-VIEW with `v_txn` registered and `txn` configured as a base stream.
**Steps:** (a) H-VAL `SELECT x FROM nosuchstream`. (b) H-VIEW `pravaha query --sql "SELECT * FROM
nosuchview"`. (c) H-VIEW `pravaha query --sql "SELECT * FROM txn"` — a **base stream**, which is
configured but is not a view.
**Expected:** (a) and (b) → `PRV-2002` in practice ("Object 'nosuchstream' not found") with
`. Known streams: [...]` appended by `SqlPlanner`, **not** `PRV-2003`. (c) is the sharp one: the user
is told `txn` is not found and handed a list of view names, so the message asserts something false
about the catalog. `QUICKSTART.md` already calls this message "accurate, and confusing"; on the
server path it is no longer accurate.
**Vacuity:** C3, with `SELECT * FROM v_txn` answering in the same session so the catalog is shown to
be non-empty.

### SQLX-182 — an unknown column, and a value that looks like SQL
**Intent:** `PRV-2002` is documented as "Validation failed — an unknown column, a type mismatch".
Paired here with the injection-shaped *value*, because the two together are what a security reviewer
asks about: the text path validates and names, and the value path never parses at all.
**Falsifier:** a column error that does not name the column; or a bound value that changes the plan.
**Setup:** H-VIEW, `v_txn` at 6 rows.
**Steps:** (a) `SELECT nosuchcol FROM v_txn`. (b) `SELECT txn_id FROM v_txn WHERE nosuchcol > 1`.
(c) `SELECT txn_id FROM v_txn WHERE user_id = ?` bound to the 14-character string
`u1' OR '1'='1`. (d) the same bound to `'; DROP TABLE txn; --`. (e) the escaped-literal form, in
which the whole injection is written as one SQL string literal with doubled apostrophes.
**Expected:** (a) and (b) → `PRV-2002` naming the column, in both the select list and the predicate.
(c) and (d) → **0 rows** with the view still at 6, because the value is compared as a string and no
row holds it. (e) → 0 rows; the doubled apostrophes make one string literal, not a predicate.
**Vacuity:** C1 on (c)–(e) — `SELECT COUNT(*) FROM v_txn` returns 6 immediately before and after
each. "0 rows" from a dropped view and "0 rows" from a non-matching string are the same output.

---

## §10 — `SqlSupportMatrixTest` itself

The brief asks for cases against the test, not only against the engine. The test is the evidence for
a documented claim, so it is a deliverable with its own acceptance criteria. All eight run against
`pravaha-sql/src/test/java/com/ash/messaging/pravaha/sql/plan/SqlSupportMatrixTest.java`.

### SQLX-183 — the matrix compares outcome strings, never values — proved by seeding a wrong answer
**Intent:** Q-8, **OPEN**. An assertion about a test is only credible if the test can be shown to
miss something specific. This is the seeded-bug proof, in the shape `IncrementalOracleTest` already
uses for its own oracle (`theOracleCatchesADeliberatelyBrokenLift`).
**Falsifier:** the matrix fails after the seeded change. If it does, Q-8 is closed and this case
passes as a confirmation.
**Setup:** the tree under test, with **one** local modification, reverted afterwards: make
`Expression.Arithmetic` for `ADD` return `left + right + 1`.
**Steps:** run `SqlSupportMatrixTest` alone. Then run this file's SQLX-017 (`SELECT amount * 2 + 1`)
through H-RUN against the same modified tree.
**Expected:** `SqlSupportMatrixTest` **passes green** — `Case.ok("integer arithmetic", "SELECT amount
* 2 + 1 FROM txn")` still plans, builds and compiles, so `outcomeOf` returns `"OK"` and the expected
value is `"OK"`. SQLX-017 **fails**, because `201` comes back as `202`. The gap between the two is
the finding, stated as a reproducible experiment rather than as a reading of the source.
**Vacuity:** the unmodified tree must be run first and both must pass, so the single failure after
the change is attributable to the change.

### SQLX-184 — no row ever reaches a pipeline in the matrix
**Intent:** The direct reading, kept as a separate case because it is the cheap, deterministic one
that can run in CI while SQLX-183 needs a mutated tree.
**Falsifier:** the throwing `RowOutput` is ever invoked.
**Setup:** the tree under test, unmodified.
**Steps:** replace the lambda in `messageOf` with one that increments a counter **and** throws, run
the full matrix, and read the counter. (Equivalently: assert on the source that the supplier's body
is a bare `throw`.)
**Expected:** the counter is **0** across all ~90 matrix entries. The supplier's own message says so
— "the matrix builds pipelines but never runs rows through them" — and this turns a comment into an
assertion.
**Vacuity:** the same counter under H-MTX with a real collector must be greater than zero for the
same statements, which proves the counting mechanism works.

### SQLX-185 — every ✅ row of the document has a value assertion somewhere
**Intent:** The acceptance criterion for closing Q-8. 42 ✅ rows; the matrix has an outcome for each
and a value for none.
**Falsifier:** a ✅ row for which no test anywhere in the repository compares a computed value.
**Setup:** `docs/SQL_SUPPORT.md` and the test sources.
**Steps:** build the 42-row list mechanically (`grep '✅' docs/SQL_SUPPORT.md`), and for each one name
the test that asserts a **value**: a case in this file, a case in `WIN`/`AGG`/`TYPE`, or an existing
unit test.
**Expected:** a table with 42 rows and no blanks. Rows expected to be blank today, from the structure
of the matrix: the five text functions, `CAST`, the four scalar functions, `CASE`, the literals, and
every join row — the matrix's join block asserts planning only, and round 1 could not check joins at
all. Each blank is a coverage finding with an owner.
**Vacuity:** not applicable — no state, no timing.

### SQLX-186 — every ❌ row refuses at **plan** time, not at the first row
**Intent:** The document's central promise: "A query Pravaha cannot run is refused when it is
planned… It is never accepted and then approximated." The matrix cannot tell plan-time from
build-time from compile-time, because `messageOf` wraps all three in one `try`.
**Falsifier:** any ❌ construct that plans under H-VAL and fails only later.
**Setup:** the 22 ❌ rows of the document.
**Steps:** run each through **H-VAL**, which stops after `SqlPlanner.plan` and never builds a
physical plan. Record, for each, which of the three stages refuses: parse/validate (`SqlPlanner`),
build (`PhysicalPlanBuilder`), or compile (`InterpretedPipeline`).
**Expected:** the great majority refuse at parse/validate or build. **At least one does not**: the
self-join is refused "when the pipeline is built rather than when the query is planned", which the
document itself admits. `pravaha validate` will therefore report a self-join as **valid**, which is
a stronger statement of the same gap than the document makes — a user who validates in CI is told a
query is fine and it fails at registration.
**Vacuity:** C3 is this case.

### SQLX-187 — every refusal message is actionable, not merely long
**Intent:** `everyRefusalCarriesAnErrorCodeAndAnExplanation` asserts `message.length() > 40` and
nothing else. Length is a proxy, and SQLX-122, SQLX-130 and SQLX-177 are all messages well over 40
characters that name something the user did not write.
**Falsifier:** a message over 40 characters that names no construct from the user's own SQL and
offers no alternative.
**Setup:** the 22 ❌ rows, plus the extra refusals this file found that are in no table:
GROUPING SETS (SQLX-112), the float aggregate (SQLX-113), the reserved-word view name (SQLX-178).
**Steps:** for each message assert three properties, not one: (i) it carries a `PRV-` code; (ii) it
contains a token that appears in the user's SQL text, case-insensitively; (iii) it names an
alternative or states that there is none.
**Expected:** (i) holds everywhere except the self-join. (ii) is expected to **fail** for `ORDER BY`
(`LogicalSort`), `LIMIT` (`LogicalSort`), `OFFSET` (`LogicalSort`), `EXCEPT` (`LogicalMinus`),
`UNION` (`LogicalUnion`), `INTERSECT` (`LogicalIntersect`), `VALUES` (`LogicalValues`), `INSERT`
(`LogicalTableModify`) and `SQRT` (`POWER`) — nine of them, all one clause away from passing.
(iii) is expected to fail for GROUPING SETS. Each failure is a message defect with a named fix.
**Vacuity:** not applicable.

### SQLX-188 — the document, the matrix and this file are in three-way agreement
**Intent:** The document's promise is that the build fails and "names this file" when a construct
starts or stops working. That only holds if the three lists are the same list.
**Falsifier:** a documented construct with no matrix row, a matrix row for an undocumented
construct, or a construct in this file that is in neither.
**Setup:** `docs/SQL_SUPPORT.md` (64 ✅/❌ rows), `SqlSupportMatrixTest.MATRIX`, this file.
**Steps:** produce the three-way diff.
**Expected:** *(from reading the three today — every one of these is a finding)*
- **In the document, not in the matrix:** `SELECT DISTINCT` over a **view** (✅); unwindowed
  `GROUP BY` over a **view** (✅) — the matrix has no bounded-input entry at all; "Time bound in
  months or years" (❌); `SELECT 1` as a literal is present but `SELECT 'flagged'` and the
  `SUBSTRING` code-point promise carry no value check.
- **In the matrix, not in the document:** `ROUND` with one argument as ✅ (the document says "One
  argument" in a note, not a row); the alias-shadowing case; `CASE` with no `ELSE`.
- **In neither:** `GROUPING SETS`/`CUBE`/`ROLLUP` (SQLX-112); the float-aggregate refusal
  (SQLX-113); narrow-integer aggregates (SQLX-114); the reserved-word view name (SQLX-178);
  parameters in any position (§8) — ADR-032 documents them and the matrix has not one row.
- **Miscounts to check:** the document's aggregation table lists `COUNT(DISTINCT x)` ✅ while Q-6
  reports it refused over a view; and lists `GROUP BY` over a view ✅ while `pravaha run` refuses it
  over a bounded file (SQLX-119).
**Vacuity:** not applicable.

### SQLX-189 — the matrix never constructs a bounded plan, so half the document is untested
**Intent:** `messageOf` builds `new PhysicalPlanBuilder().build(...)`. `overBoundedInput()` appears
nowhere in the test. The document's entire stream/view asymmetry — a whole section, two ✅ rows and
the `SELECT DISTINCT` row — is therefore asserted by nothing.
**Falsifier:** `overBoundedInput` appears in the test, or a bounded case exists elsewhere that runs
rows.
**Setup:** the test source and `ViewQuery`.
**Steps:** grep the test module for `overBoundedInput`; then add the two paired entries the document
requires and confirm they behave differently: `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id`
must be `PRV-2050` unbounded and `"OK"` bounded, and `SELECT DISTINCT user_id FROM txn` likewise.
**Expected:** zero occurrences today. After the two pairs are added, the matrix would have a
`bounded` flag beside its existing `lookup` flag — the `Case` record already has exactly that shape
(`Case.lookupOk`), so the change is one field and two factory methods. This case specifies the fix as
much as it reports the gap.
**Vacuity:** the two pairs must disagree with each other; two entries that both say `"OK"` would
prove nothing.

### SQLX-190 — the document's opening claim, restated as an assertion
**Intent:** `SQL_SUPPORT.md` opens with "Every construct below is **checked by a test**, not by
somebody's memory", and the self-aware paragraph that follows says a planner-only matrix "reported it
as supported and this page repeated the claim" — a mistake the document has already made once, in
exactly this shape, one stage earlier in the pipeline.
**Falsifier:** the claim is true, i.e. every ✅ construct has a value assertion and every ❌ has a
plan-time refusal assertion.
**Setup:** the outcomes of SQLX-183 through SQLX-189.
**Steps:** state the document's claim as a predicate — *for every row of every table on this page,
some test observes the behaviour the row describes* — and evaluate it against those seven results.
**Expected:** **false**, and the honest repair is one sentence in the document rather than a rewrite:
the page should say the matrix checks that each construct **plans, builds and compiles**, and name
where the answers are checked. The engineering repair is SQLX-189's `bounded` flag plus a collecting
`RowOutput` and one expected-value column on each `Case.ok`. The document's own third paragraph is
the precedent: it was extended once already, when planning turned out not to be running.
**Vacuity:** not applicable.

---

## Coverage note

**190 cases, the full budget.** The distribution is deliberate and differs from a naive
"64 constructs × 4 dimensions":

| § | IDs | Cases | Why this size |
|---|---|---|---|
| 1 Projection | 001–028 | 28 | 11 documented rows, but `SELECT DISTINCT` alone needs six (one refusal over a stream, five answers over a view) and duplicate names need four. |
| 2 Expressions | 029–048 | 20 | 9 documented rows; four carry an **open** round-1 or round-2 finding (Q-4, Q-5, Q-7, Q-11, Q-12) that only a value can re-test. |
| 3 WHERE | 049–096 | 48 | The largest, and the brief asked for it: 12 types × a predicate, four three-valued cases, six `IN` boundary cases, four documented refusals. |
| 4 Aggregation | 097–120 | 24 | 11 documented rows, doubled because every one has a stream answer and a view answer, plus the three `COUNT(col)` operator paths round 2 left unchecked. |
| 5 Sorting | 121–126 | 6 | 2 documented rows; four extra because the *message* is the defect. |
| 6 Set operations | 127–134 | 8 | 1 documented row covering 4 operators; the `LogicalMinus` message and the view path need their own. |
| 7 Subqueries | 135–146 | 12 | 7 documented rows; CTE-to-self-join (SQLX-139) and the correlated form are not in the matrix at all. |
| 8 Parameters | 147–162 | 16 | `SQL_SUPPORT.md` gives parameters four lines and the matrix **zero** rows, while ADR-032 specifies nine positions. This is the largest documented-but-untested surface in the file. |
| 9 Hostile | 163–182 | 20 | All of the brief's list, plus the registration-name refusals, which are a SQL surface even though they live in `pravaha-registry`. |
| 10 The matrix itself | 183–190 | 8 | The brief asked for them; SQLX-183 is the only one that *proves* Q-8 rather than reading it. |

**What this area deliberately does not own.** Types in every position (`TYPE`), aggregate kinds ×
types × operators (`AGG`), window mechanics and late data (`WIN`, `TIME`), join semantics beyond
their refusals (`JOIN`), whether each `PRV-` code is reachable and unique (`ERRC`), and CLI
flag-handling defects such as Q-9 and Q-10 (`API`). Where a case here touches one of those it says so
and hands the finding over rather than duplicating the coverage.

**Two things an executor should read before starting.** First, the harness matters: a refusal
observed only through `pravaha run` proves nothing about *when* the refusal happened, because
`QueryRunner` plans before it opens the file. Second, `pravaha run` does **not** declare its input
bounded, so roughly a third of §4 can only be executed through a running server — budget for
H-VIEW rather than assuming the CLI covers it.

**The one case to run first.** SQLX-183. If the matrix passes green with `a + b` computing `a + b + 1`,
every "✅" in `docs/SQL_SUPPORT.md` is a statement about planning and none of them is a statement
about an answer — and that changes how much of this file is a re-test versus a first test.
