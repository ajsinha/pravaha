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

### SQLX-006 — a qualified column produces a **bare** output name
**Intent:** The document states it explicitly: "`SELECT t.amount` produces a column called `amount`,
not `t.amount`". A client keying on the column name breaks if this drifts.
**Falsifier:** the output schema names the column `t.amount`.
**Setup:** S.
**Steps:** H-VAL, `SELECT t.amount FROM txn AS t`.
**Expected:** `[amount INT64 NOT NULL]`.

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

### SQLX-016 — boolean literals in the select list
**Intent:** `typeOf` maps `BOOLEAN`, but `literal()` reaches `(BigDecimal) literal.getValue4()` for
anything that is not interval, char or varchar — and a boolean's `getValue4` is not a `BigDecimal`.
Round 2 found exactly this class of raw `ClassCastException` for narrow-type literals.
**Falsifier:** a raw `ClassCastException` reaching the user.
**Setup:** S, D1.
**Steps:** H-VAL then H-RUN, `SELECT TRUE FROM txn`, `--out-schema 'b:BOOLEAN'`.
**Expected:** six lines of `true`, **or** `PRV-2021`. A `ClassCastException` is a FAIL against the
document's promise that every refusal carries a code.

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
**Expected (correct behaviour):** `-100 / -250 / 50 / 0 / -7 / -7`, summing to `-314`, the negation
of D1's `314`.
**Expected (current, to be confirmed):** `PRV-2021` whose text asserts unary minus is included. That
is a **FAIL** of the message, recorded against Q-11.
**Vacuity:** C1 on the working branch; C3 on the refusing one.

### SQLX-039 — integer division by zero
**Intent:** Q-5, **OPEN**: "hangs five minutes, loses the whole batch, and swallows the
`ArithmeticException`". `QueryRunner` waits `Duration.ofMinutes(5)` on `awaitQuiescent` and then
throws "the query did not finish within five minutes".
**Falsifier:** the command returns `ok` with rows, i.e. the divide-by-zero row silently vanished.
**Setup:** S, D1 (row 4 has `amount = 0`).
**Steps:** H-RUN, `SELECT 100 / amount FROM txn`, `--out-schema 'v:INT64'`. Time it.
**Expected (correct):** a prompt failure naming division by zero and the row, non-zero exit, in well
under five minutes.
**Expected (current, to be confirmed):** a five-minute stall then "the query did not finish within
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
**Expected (correct):** ten identical failures naming overflow. `9223372036854775807 * 2` wraps to
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
**Expected (a):** `100 → 2` (not `3`: `100 > 100` is false), `250 → 3`, `-50 → 0`, `0 → 0`,
`7 → 1`, `7 → 1`. Column `2 / 3 / 0 / 0 / 1 / 1`, summing to `7`.
**Expected (b):** row 2 is `3`; the other five are NULL (empty fields), **not** `0`.
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
**Expected (a):** output type is `VARCHAR`/`STRING`, not `INTEGER`.
**Expected (b):** `big / big / 0 / 0 / 0 / 0` — the last four are the **one-character string** `0`.
**Expected (c):** a refusal. `kindOf` accepts `SUM`, so the refusal comes from the accumulators or
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
**Expected (a):** exactly `padded` — 6 characters, no leading or trailing space. The input is 10
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
**Expected (first):** `u1-ok / <NULL> / u1-ok / u3-flagged / u2-ok / ünïcødé-ok`. Row 2 is an empty
field **meaning NULL**, and the case must distinguish that from the string `u2-`: read the output
back with `--schema 'v:STRING?'` and assert `IS NULL` returns exactly 1 row.
**Expected (second):** `u1-u1-u1 / u2-u2-u2 / u1-u1-u1 / u3-u3-u3 / u2-u2-u2 /
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
**Falsifier:** `WHERE i32 >= 1000` misses r1.
**Setup:** S2, `all.csv` (`i32` = 1000, -1000, 0).
**Steps:** H-RUN `WHERE i32 >= 1000`, `WHERE i32 <= -1000`, `WHERE i32 = 0`, `WHERE 1000 = i32`.
**Expected:** 1, 1, 1, 1 row. The fourth is the `flip(op)` path — `literal OP column` must mean the
same as `column flip(OP) literal`.
**Vacuity:** C2 — `1 + 1 + 1 = 3`; and queries one and four return the identical row.

### SQLX-053 — INT64 in a predicate, including the extremes
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
**Expected, computed row by row:**
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
**Expected (if the limit is real):** `PRV-2021` from `unsupported(node)`, whose text is "cannot
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
