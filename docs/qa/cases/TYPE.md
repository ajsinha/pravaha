# Types and expressions — test cases

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Area code `TYPE`, IDs `TYPE-001`–`TYPE-150`. **Written before execution. Nothing here has been run.**

The grid this file is built from is *type × position*. The sixteen types are the constants of
[`TypeName`](../../../pravaha-api/src/main/java/com/ash/messaging/pravaha/api/data/TypeName.java):
`BOOLEAN INT8 INT16 INT32 INT64 FLOAT32 FLOAT64 DECIMAL DATE TIME TIMESTAMP_LTZ STRING BYTES ARRAY
MAP ROW`. The positions are: **declaration**, **projection**, **WHERE predicate**, **aggregate
argument**, **join key**, **GROUP BY key**, **window boundary**, **ORDER (refused — verified, not
assumed)**, **wire serialisation to a client**, **NULL**, **minimum value**, **maximum value**,
**overflow/underflow**. Sections 13–19 then cover the expression layer that sits on top of those
types, because a type is only as correct as the arithmetic performed on it.

## What the code says before anything is run

Read first, so that a case pins the product rather than a generic engine. Each of these is the
source of several cases below.

1. **Only ten of the sixteen types can be declared at all.** `FilesystemSourcePlugin.typeFor` — the
   parser behind `pravaha.streams.<n>.schema`, `POST /api/v1/streams`, `--schema` and `--out-schema`
   — accepts `BOOLEAN INT8 INT16 INT32 INT64 FLOAT32 FLOAT64 STRING BYTES TIMESTAMP` and nothing
   else. `DECIMAL`, `DATE`, `TIME`, `ARRAY`, `MAP`, `ROW` have **no configured declaration path**.
   They are reachable only by building a `StreamSchema` in Java.
2. **`ARRAY`/`MAP`/`ROW` become Calcite's `ANY`** (`TypeMapping.baseType`), and `ANY` has no inverse
   (`baseFromCalcite` throws a bare `IllegalArgumentException`, not a `PRV-` code).
3. **Aggregates accumulate through `row.getLong` and write through `writer.setLong`**
   (`GlobalAggregate`). Floating point is refused at plan time; the narrow integers are not, and
   `BinaryRowWriter.setLong` → `RowLayout.checkType` then throws at *runtime* naming an internal
   schema (`txn_aggregated`) and an internal column (`EXPR$0`).
4. **The compute stage truncates on write**: `InterpretedPipeline.writeComputed` does
   `writer.setInt(ordinal, (int) expression.evaluateLong(row))` for `INT32`, `(short)` for `INT16`,
   `(byte)` for `INT8`. `Expression.Arithmetic.evaluateLong` uses `Math.addExact` on `long`, so
   64-bit overflow throws and 32-, 16- and 8-bit overflow **wraps silently under exit 0**.
5. **`Constant.OfLiteral.asLong` calls `RexLiteral.getValueAs(BigDecimal.class)`**, which Calcite
   answers for exact numerics and for which it throws a raw `AssertionError` on a temporal literal.
6. **`ExpressionCompiler.literal` casts `getValue4()` to `BigDecimal`** for everything that is not
   text or an interval, which is a `ClassCastException` for the Java-form temporal and `DOUBLE`
   values Calcite hands back.
7. **`ServedView.value` ends `default -> row.getString(ordinal)`**, so `BYTES`, `DECIMAL`, `ARRAY`,
   `MAP` and `ROW` are all read as if their slot held an (offset, length) pair.
8. **`ArrowSchemas` maps `TIME` to `Timestamp(NANOSECOND, "UTC")`** — the same Arrow type as
   `TIMESTAMP_LTZ` — and refuses `DECIMAL`, `ARRAY`, `MAP`, `ROW` with `FlightErrors.UNSUPPORTED_TYPE`.
9. **`JoinKeys.checkJoinable` guards only `FLOAT32`, `FLOAT64` and `DECIMAL`.** `BYTES`, `ARRAY`,
   `MAP` and `ROW` pass planning and die in `JoinKeys.fieldHash` on the first row.
10. **Window boundaries are `Types.timestamp()` and NOT NULL** (`PhysicalPlanBuilder.appendBoundaries`).

## Fixtures

All files are plain delimited text with the default delimiter `,` and the default
`null.literal` — **the empty string**, so an empty field is NULL and a NOT NULL column fed an empty
field is a decode failure.

**`types.csv`** — schema
`id:INT64,b:BOOLEAN?,i8:INT8?,i16:INT16?,i32:INT32?,i64:INT64?,f32:FLOAT32?,f64:FLOAT64?,s:STRING?,bin:BYTES?,ts:TIMESTAMP?`

```
1,true,127,32767,2147483647,9223372036854775807,3.4028235E38,1.7976931348623157E308,zed,cafe,1700000000000000000
2,false,-128,-32768,-2147483648,-9223372036854775808,1.4E-45,4.9E-324,ann,,0
3,,,,,,,,,,
4,true,0,0,0,0,0.0,0.0,,beef,1700000000000000001
5,false,1,1,16777217,9007199254740993,16777217,9007199254740993,  pad  ,ff,-1
```

Row 3 is eleven fields: `3` and ten empties, so every nullable column is NULL. Row 2's `bin` and
row 4's `s` are NULL for the same reason. `bin` is decoded as the **UTF-8 bytes of the text**
(`raw.getBytes(UTF_8)`), not as hex: `cafe` is the four bytes `63 61 66 65`.

**`num.csv`** — schema `id:INT64,a:INT64?,b:INT64?,x:FLOAT64?,y:FLOAT64?,r:FLOAT32?`

```
1,17,5,17.0,5.0,17.0
2,-17,5,-17.0,5.0,-17.0
3,17,-5,17.0,-5.0,17.0
4,-17,-5,-17.0,-5.0,-17.0
5,7,0,7.0,0.0,7.0
6,,5,,5.0,
7,9223372036854775807,1,0.49999999999999994,4503599627370497.0,1.0
8,-9223372036854775808,-1,-0.4,2.5,-2.5
```

**`small.csv`** — schema `id:INT64,p:INT8?,q:INT8?,m:INT16?,n:INT16?,u:INT32?,v:INT32?`

```
1,127,1,32767,1,2147483647,1
2,-128,-1,-32768,-1,-2147483648,-1
3,7,3,7,3,7,3
4,-7,3,-7,3,-7,3
```

**`text.csv`** — schema `id:INT64,s:STRING?,t:STRING?`

```
1,hello world,HELLO
2,  padded  ,x
3,,y
4,a.com,z
5,straße,ß
6,👍ok,e
7,100%,%
8,axcom,q
```

Row 2's `s` has two leading and two trailing spaces. Row 3's `s` is NULL. Row 6's `s` is the emoji
U+1F44D followed by `ok` — **three code points in five Java `char`s**.

**Server fixture `S`** — one node, HTTP 18400, Flight 19400, `pravaha.security.allow-anonymous:
true`, and

```yaml
pravaha:
  streams:
    types: { schema: "id:INT64,b:BOOLEAN?,i8:INT8?,i16:INT16?,i32:INT32?,i64:INT64?,f32:FLOAT32?,f64:FLOAT64?,s:STRING?,bin:BYTES?,ts:TIMESTAMP?" }
    ordl:  { schema: "oid:INT64,k:STRING?,amt:INT64?,ts:TIMESTAMP" }
    ordr:  { schema: "rid:INT64,k:STRING?,tag:STRING?,ts:TIMESTAMP" }
  sources:
    types: { plugin: filesystem, options: { path: ./types.csv, schema: "<as above>", event.time: ts } }
    ordl:  { plugin: filesystem, options: { path: ./ordl.csv,  schema: "<as above>", event.time: ts } }
    ordr:  { plugin: filesystem, options: { path: ./ordr.csv,  schema: "<as above>", event.time: ts } }
```

`event.time` matters: `QueryRunner` (behind `pravaha run`) passes only `path` and `schema` to the
plugin, so **no window can ever fire under `pravaha run`** — every row carries event time 0. Every
windowed case below therefore uses fixture `S`.

**`ordl.csv`** — `1,k1,100,1700000000000000000` / `2,k2,200,1700000001000000000` /
`3,,300,1700000002000000000` / `4,k1,400,1700000300000000000`
**`ordr.csv`** — `1,k1,west,1700000000500000000` / `2,k3,east,1700000001500000000` /
`3,,north,1700000002500000000` / `4,k1,south,1700000300500000000`

**Three vehicles, chosen per case.**
`pravaha validate --sql … --schema … [--stream …]` plans and prints the **output column types** and
nothing else — the cheapest way to pin a refusal or an output type with no data at all.
`pravaha run --sql … --schema … --in … --out-schema … --out …` drives the real lane runtime over a
file, so a scalar answer can be read out of the result CSV.
`pravaha register` / `pravaha query` / `pravaha subscribe` against fixture `S` drive the server,
the view path and the Flight wire.

**Exit codes.** `0` ok, `1` a `PravahaException` or any other `RuntimeException`, `2` a usage
mistake. An `AssertionError` is an `Error`, **not** a `RuntimeException`, so `PravahaCli` does not
catch it: it escapes `main` with a stack trace, and inside the server it kills the thread it is on.

**Anti-vacuity rule used throughout.** Every case that could pass on an empty result asserts a row
count as well as the values, and every refusal case asserts *which* refusal — a `PRV-` code and the
sentence — never merely "it failed". A case that expects zero rows is always paired with the
inverted condition, which must return the complement.

---

## 1. Declaration — can the type be named at all? (TYPE-001 … TYPE-008)

The first position in the grid, and the one nobody writes down. A type that cannot be declared
cannot occupy any other position, and six of the sixteen cannot be declared.

## TYPE-001 — the ten declarable types round-trip through the schema parser
**Intent:** establish the baseline for every later case: that `types.csv` decodes and re-encodes
with each of the ten types the `name:TYPE` grammar accepts, so a failure further down is about the
position under test and not about getting the row into the engine.
**Falsifier:** any of the ten types rejected by `parseSchema`; or a decoded value that does not
re-encode to the text it came from, for the values named in **Expected**.
**Setup:** `types.csv` and its schema, as in Fixtures.
**Steps:**
```
pravaha run --stream types --schema "<types schema>" --in types.csv \
  --sql "SELECT id,b,i8,i16,i32,i64,f32,f64,s,bin,ts FROM types" \
  --out-schema "<types schema>" --out out.csv
```
**Expected:** exit 0, `ok  5 in, 5 out`. `out.csv` equals `types.csv` line for line **except** the
two values that are not representable in their declared width, which must come back as the nearest
representable value, printed by `Float.toString`/`Double.toString`:
- row 5 `f32`: `16777217` is 2²⁴+1; a 24-bit significand cannot hold it, so it decodes to
  16777216 and re-encodes as `1.6777216E7`.
- row 5 `f64`: `9007199254740993` is 2⁵³+1; it decodes to 9007199254740992 and re-encodes as
  `9.007199254740992E15`.
Everything else is byte-identical, including `3.4028235E38`, `1.4E-45`, `1.7976931348623157E308`,
`4.9E-324`, `9223372036854775807`, `-9223372036854775808`, `  pad  ` with its four spaces, and
row 3's ten empty fields.
**Vacuity:** 5 in / 5 out is asserted alongside the content. A codec that dropped row 3 (all
nullable columns empty) would still produce a file whose surviving lines matched.

## TYPE-002 — DECIMAL cannot be declared by any configured path
**Intent:** `TypeName.DECIMAL` exists, `RowLayout` gives it 16 bytes, `RowDebug` renders it and
`Decimals` converts it — and `FilesystemSourcePlugin.typeFor` has no case for it. Pin whether a
deployment can reach the type at all, and whether the refusal says so.
**Falsifier:** `DECIMAL`, `DECIMAL(10,2)` or `NUMERIC` accepted by any of the four surfaces that
parse the grammar; or a refusal whose message does not name the supported set.
**Setup:** none beyond a built CLI, and fixture `S` for the server surfaces.
**Steps:**
1. `pravaha validate --stream d --schema "id:INT64,amt:DECIMAL" --sql "SELECT id FROM d"`
2. the same with `amt:DECIMAL(10,2)` and with `amt:NUMERIC`
3. `POST /api/v1/streams` with body `{"name":"d","schema":"id:INT64,amt:DECIMAL"}`
4. start a node with `pravaha.streams.d.schema: "id:INT64,amt:DECIMAL"`
**Expected:** all four refuse. Steps 1–2 exit 1 with `FILESYSTEM_DECODE_FAILED` (5040) and the
message `unknown type 'DECIMAL'. Supported: BOOLEAN, INT8, INT16, INT32, INT64, FLOAT32, FLOAT64,
STRING, BYTES, TIMESTAMP. Suffix with ? for nullable.` Step 3 returns a 4xx carrying that sentence,
not a 500. Step 4 refuses to start and names the stream `d` and the column `amt`.
**Expected, recorded as a finding regardless of pass/fail:** the message is accurate and the type is
unreachable, so `SQL_SUPPORT.md`'s claim that DECIMAL is "refused rather than sent as a
floating-point number" describes a refusal that no configured deployment can ever provoke.

## TYPE-003 — DATE cannot be declared by any configured path
**Intent:** same as TYPE-002 for `DATE`, which is worse than DECIMAL because `DATE` is not refused
anywhere else: `TypeMapping`, `Predicate.CompareInt`, `BinaryRowWriter.setInt`, `JoinKeys`,
`KeyedAggregate` and `ArrowSchemas` all handle it. Every one of those paths is dead code from a
configured node's point of view.
**Falsifier:** `DATE` accepted by the grammar; or accepted by one surface and refused by another.
**Setup/Steps:** the four steps of TYPE-002 with `d:DATE`.
**Expected:** all four refuse with `unknown type 'DATE'` and the same supported list. Record that
`ArrowSchemas` maps `DATE` to `Date(DateUnit.DAY)` and `BinaryRowWriter.setInt` accepts it — both
correct, both unreachable.

## TYPE-004 — TIME cannot be declared by any configured path
**Intent:** as TYPE-003 for `TIME`, and TIME carries an extra hazard worth recording while it is
unreachable: `ArrowSchemas` sends it as `Timestamp(NANOSECOND, "UTC")`, the *same Arrow type as
TIMESTAMP_LTZ*, so a client cannot tell them apart (see TYPE-074).
**Falsifier:** `TIME` accepted by the grammar.
**Setup/Steps:** the four steps of TYPE-002 with `t:TIME`.
**Expected:** all four refuse with `unknown type 'TIME'` and the supported list.

## TYPE-005 — ARRAY, MAP and ROW cannot be declared, and have no SQL type either
**Intent:** the three nested types. Establish both halves: they cannot be declared, and if one is
built programmatically it becomes Calcite's `ANY`, whose inverse throws a bare
`IllegalArgumentException` with no `PRV-` code.
**Falsifier:** any of `ARRAY`, `ARRAY<INT64>`, `MAP`, `MAP<STRING,INT64>`, `ROW`, `ROW(a INT64)`
accepted by the grammar; or a programmatic `Types.array(Types.int64())` column that plans without
either an error or a defined meaning.
**Setup:** the CLI for the grammar half; a JUnit-style harness or the embedded SDK for the
programmatic half, building `StreamSchema.builder("n").field("id", Types.int64()).field("arr",
Types.array(Types.int64())).build()` and planning `SELECT id FROM n` and `SELECT arr FROM n`.
**Steps:**
1. `pravaha validate --stream n --schema "id:INT64,arr:ARRAY" --sql "SELECT id FROM n"`, and the
   same for `MAP` and `ROW` and for their parameterised spellings
2. programmatic: plan `SELECT id FROM n` — the ARRAY column present but unprojected
3. programmatic: plan `SELECT arr FROM n`
4. programmatic: plan `SELECT * FROM n`
**Expected:** step 1 refuses six times with `unknown type '<spelling>'`. Step 2 **plans**: the
`ANY`-typed column is never asked for a Pravaha type, so an unprojected nested column is harmless.
Steps 3 and 4 fail in `TypeMapping.baseFromCalcite` with `IllegalArgumentException: no Pravaha type
for SQL type ANY; the supported set is in TypeMapping` — **no `PRV-` code, no column name, no
mention of ARRAY**. That is a dishonest refusal: it names the internal class that holds the mapping
and not the thing the user wrote.

## TYPE-006 — the `?` nullable suffix on each of the ten declarable types
**Intent:** `Types.*` produces NOT NULL by design ("nullability should be a decision someone made").
The only way to relax it through configuration is the `?` suffix. Enumerate it, because a type
whose `?` is silently ignored produces a NOT NULL column that then fails at the first empty field.
**Falsifier:** `?` accepted on some types and ignored on others; or `BOOLEAN?` parsed as the unknown
type `BOOLEAN?` rather than as nullable BOOLEAN.
**Setup:** none.
**Steps:** for each of `BOOLEAN INT8 INT16 INT32 INT64 FLOAT32 FLOAT64 STRING BYTES TIMESTAMP`,
run `pravaha validate --stream n --schema "id:INT64,c:<TYPE>?" --sql "SELECT id, c FROM n"`, and
again without the `?`.
**Expected:** twenty runs, all exit 0. `validate` prints the output columns; the `?` form must
report the column as nullable and the bare form as not nullable, for all ten. Also check the
aliases resolve to the same type and the same nullability: `BOOL?`, `BYTE?`, `SHORT?`, `INT?`,
`LONG?`, `FLOAT?`, `DOUBLE?`, `VARCHAR?`, `TEXT?`, `BINARY?`.
**Note for the executor:** the suffix is stripped *after* upper-casing, so `int64?` and `INT64?`
must behave identically; assert both.

## TYPE-007 — a NOT NULL column fed an empty field
**Intent:** the interaction between the default `null.literal` (the empty string) and a NOT NULL
declaration. This is the commonest way a real file meets a real schema, and the failure must name
the column and the line.
**Falsifier:** an empty field written as zero, or as an empty string, into a NOT NULL column; or a
failure that names neither the line number nor the column.
**Setup:** `bad.csv` containing exactly `1,alpha` and `2,` with schema `id:INT64,s:STRING`
(no `?` on `s`).
**Steps:** `pravaha run --stream bad --schema "id:INT64,s:STRING" --in bad.csv --sql "SELECT id, s
FROM bad" --out-schema "id:INT64,s:STRING" --out out.csv`
**Expected:** exit 1. The message is `line 2 has null in NOT NULL column 's'` with error 5040.
`out.csv` must **not** contain a row `2,`. Record separately whether row 1 — decoded before the
failure — reaches the output or is lost with the batch; losing it is finding I-2, not a new one,
but the count belongs in the log.
**Vacuity:** the same run with `s:STRING?` must succeed with 2 in / 2 out and row 2 written as
`2,`. Without that control, a case that exits 1 for any reason at all would look like a pass.

## TYPE-008 — the refusal's own list of supported types is complete and correct
**Intent:** the `unknown type` message enumerates ten types. A list that is wrong in either
direction is worse than no list, because it is the only place a user learns what may be written.
**Falsifier:** a type named in the message that the parser then rejects, or a type the parser
accepts that the message omits.
**Setup:** none.
**Steps:** read the ten names out of the message produced by TYPE-002 step 1; declare each one;
then declare each of the six aliases (`BOOL BYTE SHORT INT LONG FLOAT DOUBLE VARCHAR TEXT BINARY`).
**Expected:** all ten named types are accepted. The ten aliases are also accepted but are **not**
in the message — record that as a documentation gap, not a defect. No accepted spelling is missing
from the supported set in a way that changes meaning.

---

## 2. Projection — the type in the SELECT list (TYPE-009 … TYPE-020)

`SELECT col` for a plain column takes `InterpretedPipeline.copyField`, which switches on the type
and ends `default -> to.setString(toOrdinal, from.getString(fromOrdinal))`. `SELECT <expression>`
takes `writeComputed`, which is a different switch with different truncation. Both are exercised
here: every case projects the bare column *and* an expression over it, because the two paths agree
for no type by construction.

## TYPE-009 — BOOLEAN in a projection
**Intent:** BOOLEAN occupies one byte, is written by `setBoolean`, read by `getBoolean`, and in a
computed position is produced by `expression.evaluateLong(row) != 0`. Pin all three.
**Falsifier:** `true` returning `false` or an empty field; a computed BOOLEAN taking the value of
some other column's byte; `b` in row 3 (NULL) rendered as `false`.
**Setup:** `types.csv`.
**Steps:**
1. `SELECT id, b FROM types` with `--out-schema "id:INT64,b:BOOLEAN?"`
2. `SELECT id, CASE WHEN i64 > 0 THEN true ELSE false END AS c FROM types` with
   `--out-schema "id:INT64,c:BOOLEAN?"`
**Expected:** step 1, 5 rows: `1,true` / `2,false` / `3,` / `4,true` / `5,false`. Row 3 is the empty
field, not `false` — "we do not know" and "it is nothing" are different answers.
Step 2, by hand: `i64` is 9223372036854775807 > 0 → `true`; −9223372036854775808 > 0 → `false`;
NULL → the comparison is UNKNOWN, `Predicate.test` returns false, the ELSE branch is taken → `false`
(**not** NULL — `CASE` falls to ELSE on UNKNOWN, which is SQL-correct and surprises people);
0 > 0 → `false`; 9007199254740993 > 0 → `true`. So `1,true` / `2,false` / `3,false` / `4,false` /
`5,true`.
**Vacuity:** the two steps disagree on row 3 (`` vs `false`). A build that returned `false` for both
has lost the null bitmap; a build that returned empty for both has broken CASE's fall-through.

## TYPE-010 — INT8 in a projection
**Intent:** INT8 is one byte, packed adjacent to BOOLEAN in the fixed region with no padding, so a
read that is one byte wide in the wrong place is invisible until the neighbouring byte differs.
**Falsifier:** `127` returning `-1` or `0`; `-128` returning `128`; `i8` picking up `b`'s byte.
**Setup:** `types.csv`. Note the layout deliberately places `b` (BOOLEAN, offset 48) immediately
before `i8` (INT8, offset 49) — see TYPE-093.
**Steps:**
1. `SELECT id, i8 FROM types` with `--out-schema "id:INT64,i8:INT8?"`
2. `SELECT id, i8 + 0 AS c FROM types` — establishes the computed path and its output type
**Expected:** step 1, 5 rows: `1,127` / `2,-128` / `3,` / `4,0` / `5,1`.
Step 2: run `pravaha validate` first and record the printed type of `c`. Calcite widens
`TINYINT + INTEGER-literal` to `INTEGER`, so `c` is expected to be `INTEGER` and the values are
`127` / `-128` / `` / `0` / `1` unchanged. If `c` prints as `TINYINT`, the case still expects those
values — `(byte) 127` is 127 — but record the type, because TYPE-096 depends on it.

## TYPE-011 — INT16 in a projection
**Intent:** two bytes, aligned to two, read by `getShort`.
**Falsifier:** `32767` returning `-1`; `-32768` returning `32768`; sign extension lost.
**Setup:** `types.csv`.
**Steps:** `SELECT id, i16 FROM types`, `--out-schema "id:INT64,i16:INT16?"`; then
`SELECT id, i16 + 0 AS c FROM types`.
**Expected:** 5 rows: `1,32767` / `2,-32768` / `3,` / `4,0` / `5,1`. The computed form gives the
same five values; record the printed type of `c`.

## TYPE-012 — INT32 in a projection
**Intent:** four bytes. INT32 is the width at which `writeComputed`'s `(int)` truncation starts to
matter and at which `GlobalAggregate`'s `row.getLong` reads four bytes it does not own — both
tested elsewhere; here, the plain path must be exact.
**Falsifier:** `2147483647` returning `-1`; `-2147483648` returning `2147483648`; `16777217`
(2²⁴+1, exact in INT32 and *not* in FLOAT32) coming back as `16777216`, which would mean the value
went through a float.
**Setup:** `types.csv`.
**Steps:** `SELECT id, i32 FROM types`, `--out-schema "id:INT64,i32:INT32?"`.
**Expected:** 5 rows: `1,2147483647` / `2,-2147483648` / `3,` / `4,0` / `5,16777217`. Row 5 is the
load-bearing one: `16777217` exactly, never `1.6777216E7` and never `16777216`.

## TYPE-013 — INT64 in a projection
**Intent:** the engine's native integer width, and the one type whose extremes cannot be lost by a
narrowing write.
**Falsifier:** either extreme returning a different value; `9007199254740993` (2⁵³+1) returning
`9007199254740992`, which would mean it passed through a double.
**Setup:** `types.csv`.
**Steps:** `SELECT id, i64 FROM types`, `--out-schema "id:INT64,i64:INT64?"`.
**Expected:** 5 rows: `1,9223372036854775807` / `2,-9223372036854775808` / `3,` / `4,0` /
`5,9007199254740993`. Row 5 proves the value never visited a double: 2⁵³+1 is the smallest positive
integer a `double` cannot represent.

## TYPE-014 — FLOAT32 in a projection
**Intent:** four bytes, written by `setFloat`, and in a computed position produced by
`(float) expression.evaluateDouble(row)` — a double round trip that must not change the value.
**Falsifier:** `3.4028235E38` returning `Infinity`; `1.4E-45` (the smallest subnormal) returning
`0.0`; the computed form differing from the plain form for any row.
**Setup:** `types.csv`.
**Steps:**
1. `SELECT id, f32 FROM types`, `--out-schema "id:INT64,f32:FLOAT32?"`
2. `SELECT id, f32 * 1 AS c FROM types` (record `c`'s printed type first)
**Expected:** step 1, 5 rows: `1,3.4028235E38` / `2,1.4E-45` / `3,` / `4,0.0` / `5,1.6777216E7`.
Row 5 is `1.6777216E7` because 2²⁴+1 is not representable in 24 significand bits and decoding
rounded it to 2²⁴; that loss happened at `Float.parseFloat`, before the engine, and the engine must
not add a second one.
Step 2 must produce the identical five values. `Column.evaluateDouble` widens the float to a double
exactly, multiplying by 1 is exact, and `(float)` narrows it back to the same float.

## TYPE-015 — FLOAT64 in a projection
**Intent:** eight bytes, and the type on which every aggregate is refused — so the projection path
is the only place a FLOAT64 answer can be got at all.
**Falsifier:** `1.7976931348623157E308` returning `Infinity`; `4.9E-324` returning `0.0`;
`9.007199254740992E15` returning an odd number, which would mean it went through a long.
**Setup:** `types.csv`.
**Steps:** `SELECT id, f64 FROM types`, `--out-schema "id:INT64,f64:FLOAT64?"`; then
`SELECT id, f64 * 1 AS c FROM types`.
**Expected:** 5 rows: `1,1.7976931348623157E308` / `2,4.9E-324` / `3,` / `4,0.0` /
`5,9.007199254740992E15`. The computed form is identical: `l * r` with `r == 1.0` is exact in IEEE
754 for every finite operand, including the subnormal `4.9E-324`.

## TYPE-016 — STRING in a projection
**Intent:** STRING is variable-width: the slot holds `(int offset, int length)` and the payload is
appended. The projection copies it with `copyField`'s `setString`, which decodes to a Java `String`
and re-encodes — so an encoding fault shows here and nowhere else.
**Falsifier:** `  pad  ` returning `pad`; `straße` returning `straÃŸe` (UTF-8 read as Latin-1) or
`stra?e`; the empty field in row 4 returning the *string* `""` rather than NULL; the emoji in
`text.csv` row 6 returning two replacement characters.
**Setup:** `types.csv` for the padded and NULL cases; `text.csv` for unicode.
**Steps:**
1. `SELECT id, s FROM types`, `--out-schema "id:INT64,s:STRING?"`
2. `SELECT id, s FROM text`, `--out-schema "id:INT64,s:STRING?"` over `text.csv`
**Expected:** step 1, 5 rows: `1,zed` / `2,ann` / `3,` / `4,` / `5,  pad  ` with both pairs of
spaces intact. Rows 3 and 4 are indistinguishable in the output — that is correct and is why
step 2 exists.
Step 2, 8 rows, byte-identical to the `s` column of `text.csv`: `hello world`, `  padded  `,
`` (NULL), `a.com`, `straße`, `👍ok`, `100%`, `axcom`. Compare the output **as bytes**: `straße`
is 7 bytes (`73 74 72 61 C3 9F 65`) and `👍ok` is 6 bytes (`F0 9F 91 8D 6F 6B`).
**Vacuity:** rows 3 and 4 of step 1 both render empty, so a null-bitmap failure is invisible there.
TYPE-080 asserts the distinction through `IS NULL` instead; this case must not be read as covering
it.

## TYPE-017 — BYTES in a projection
**Intent:** BYTES shares the variable-width machinery with STRING but has no text meaning. Establish
what actually happens: the codec decodes it from UTF-8 text, `copyField` falls to
`default -> setString(getString(...))`, and the encoder renders it with `getString`. So BYTES is,
end to end through a file, a string wearing a different type tag.
**Falsifier:** a BYTES column that does not round-trip its four UTF-8 bytes; or one that round-trips
while claiming to be binary. Both outcomes are findings; the case decides which.
**Setup:** `types.csv`, plus `bin2.csv` containing one line `1,` followed by the raw bytes
`FF FE 00 41` with schema `id:INT64,bin:BYTES?` — bytes that are **not valid UTF-8**.
**Steps:**
1. `SELECT id, bin FROM types`, `--out-schema "id:INT64,bin:BYTES?"`
2. the same over `bin2.csv`
**Expected:** step 1, 5 rows: `1,cafe` / `2,` / `3,` / `4,beef` / `5,ff`. The payload is the UTF-8
bytes of the text, four for `cafe`, two for `ff` — not two bytes `CA FE` and not one byte `FF`.
Step 2 is the real question. `new String(bytes, UTF_8)` replaces each invalid byte with U+FFFD and
re-encoding produces `EF BF BD` per replacement, so the round trip is expected to be **lossy**:
four bytes in, more than four out, and `FF FE 00 41` never recoverable. Record the exact output
bytes. A BYTES column that cannot carry arbitrary bytes is a type in name only.
**Vacuity:** step 1 alone would pass on a pure-ASCII fixture with any implementation; step 2 is what
makes the case falsifiable.

## TYPE-018 — TIMESTAMP_LTZ in a projection
**Intent:** eight bytes of nanoseconds since the epoch, UTC (ADR-012). Through a file it is written
and read as a plain long, so the projection must be exact to the nanosecond and must not round to
milliseconds anywhere.
**Falsifier:** `1700000000000000000` returning `1700000000000` or `1.7E18`; the two adjacent
timestamps in rows 1 and 4 (one nanosecond apart) becoming equal; `-1` (one nanosecond before the
epoch) returning `0` or a large positive number.
**Setup:** `types.csv`.
**Steps:** `SELECT id, ts FROM types`, `--out-schema "id:INT64,ts:TIMESTAMP?"`.
**Expected:** 5 rows: `1,1700000000000000000` / `2,0` / `3,` / `4,1700000000000000001` /
`5,-1`. Rows 1 and 4 differ by exactly 1; row 2 is the epoch itself and must not be confused with
NULL; row 5 is negative and must stay negative.

## TYPE-019 — DECIMAL, DATE and TIME reached programmatically
**Intent:** TYPE-002/003/004 showed the three cannot be configured. They *can* be built in Java,
and several code paths handle them, so establish exactly how far each one gets before something
refuses — and whether that refusal is honest.
**Falsifier:** any of the three silently producing a value; or a refusal that is an internal
exception type rather than a `PRV-` code.
**Setup:** a programmatic `StreamSchema` `n` with `id INT64`, `amt DECIMAL(10,2)`, `d DATE`,
`t TIME`, rows written with `setDecimal(1, 0L, 12345L)`, `setInt(2, 19723)` (2024-01-01 as days
since epoch), `setLong(3, 3_600_000_000_000L)` (01:00:00 as nanoseconds since midnight).
**Steps:** plan and run `SELECT id, amt FROM n`, `SELECT id, d FROM n`, `SELECT id, t FROM n`, and
`SELECT id, d + 1 AS c FROM n`.
**Expected, by path:**
- `amt` — `copyField` has an explicit `case DECIMAL -> to.setDecimal(...)`, so the plain projection
  is expected to **succeed** and carry both words. Assert the two words come back as
  `high = 0, low = 12345`, i.e. `123.45` at scale 2.
- `d` — `copyField` has `case INT32, DATE -> setInt`, so the projection succeeds and returns
  `19723`.
- `t` — `copyField` handles `TIME` with the long path; returns `3600000000000`.
- `d + 1` — `ExpressionCompiler.typeOf` maps `DATE` to `TypeName.DATE`, but Calcite types
  `DATE + INTEGER` as something it will not hand over as a plain arithmetic call; whatever comes
  back must carry `PRV-2021` and name the expression. An `IllegalArgumentException` or a
  `ClassCastException` here is a finding.
- any arithmetic on `amt` — `PRV-2021` with the DECIMAL sentence: *"is DECIMAL arithmetic, which
  Pravaha refuses rather than approximates… Cast to DOUBLE explicitly if approximate is genuinely
  acceptable."*

## TYPE-020 — ARRAY, MAP and ROW reached programmatically
**Intent:** the second half of TYPE-005: what happens to a nested column that is *projected*.
**Falsifier:** a nested column projected without error and without a defined value; or a refusal
carrying no `PRV-` code.
**Setup:** programmatic schema `n` with `id INT64`, `arr ARRAY<INT64>`, `m MAP<STRING,INT64>`,
`r ROW(a INT64, b STRING)`; each written through `setBytes` with a 4-byte payload.
**Steps:** plan `SELECT id FROM n`; then `SELECT arr FROM n`, `SELECT m FROM n`, `SELECT r FROM n`,
`SELECT * FROM n`; then `SELECT id FROM n WHERE arr IS NULL`.
**Expected:** `SELECT id FROM n` plans and runs — an unprojected nested column costs nothing.
The four projections fail in `TypeMapping.baseFromCalcite` with
`IllegalArgumentException: no Pravaha type for SQL type ANY; the supported set is in TypeMapping`.
**This is the dishonest refusal named in the brief**: it has no `PRV-` code, does not say ARRAY, MAP
or ROW, does not name the column, and points the reader at a class rather than at their query. The
expected *correct* behaviour is `PRV-2021` naming the column and its type.
`WHERE arr IS NULL` compiles to `Predicate.IsNull`, which never looks at the type, so it is expected
to **plan and run** — a nested column can be tested for null while it cannot be selected.

---

## 3. WHERE predicate — the type as a filter operand (TYPE-021 … TYPE-032)

`PredicateCompiler.compare` switches the *column's* type into one of five shapes: `CompareInt`
(INT8, INT16, INT32, DATE), `CompareLong` (INT64, TIME, TIMESTAMP_LTZ), `CompareDouble` (FLOAT32,
FLOAT64), `CompareString` (STRING, `=` and `<>` only), `CompareBoolean` (BOOLEAN). Everything else
falls to `default ->` `PRV-2021`. Every one of these is tested with all six operators, because
`Op.negated()` and `flip()` are separate tables and either can be wrong on its own.

## TYPE-021 — BOOLEAN as a predicate: bare, `= TRUE`, `= FALSE`, `<>`, and `NOT`
**Intent:** four spellings reach `CompareBoolean` by three different routes —
`compile(INPUT_REF)`, `compare(..., EQ/NE, constant)` with the value folded into the flag, and
`negate(INPUT_REF)`. A sign error in `constant.asBoolean() == (op == EQ)` inverts the filter.
**Falsifier:** `WHERE b` and `WHERE b = true` returning different row sets; `WHERE b <> true`
returning the rows `WHERE b = true` returns; row 3 (`b` NULL) appearing in any of them.
**Setup:** `types.csv`. `b` is true for ids 1 and 4, false for 2 and 5, NULL for 3.
**Steps:** run `SELECT id FROM types WHERE <p>` for `p` ∈ { `b`, `b = true`, `b = false`,
`b <> true`, `b <> false`, `NOT b`, `b IS NULL`, `b IS NOT NULL` }, `--out-schema "id:INT64"`.
**Expected:** by hand — `b` → `1,4`; `b = true` → `1,4`; `b = false` → `2,5`; `b <> true` → `2,5`;
`b <> false` → `1,4`; `NOT b` → `2,5`; `b IS NULL` → `3`; `b IS NOT NULL` → `1,2,4,5`.
**Vacuity:** `b` and `NOT b` together return 4 of 5 rows, not 5. The missing row is id 3, and that
absence is the assertion — a two-valued implementation would return all five between them.

## TYPE-022 — INT8 against a literal, all six operators
**Intent:** INT8 goes to `CompareInt`, which reads with `row.getInt(ordinal)` — a **four-byte read
of a one-byte field**. That is the case's real target: if the read is wrong, it is wrong by picking
up `i16`'s bytes, and it will still return plausible rows.
**Falsifier:** any of the six operators returning a set that is not the hand-computed one; `= 127`
returning nothing while `SELECT i8` returns 127.
**Setup:** `types.csv`; `i8` is 127, −128, NULL, 0, 1 for ids 1–5.
**Steps:** `SELECT id FROM types WHERE i8 <op> 0` for the six operators, then `WHERE i8 = 127`,
`WHERE i8 = -128`, and the flipped forms `WHERE 0 < i8` and `WHERE 127 = i8`.
**Expected:** `= 0` → `4`; `<> 0` → `1,2,5`; `< 0` → `2`; `<= 0` → `2,4`; `> 0` → `1,5`;
`>= 0` → `1,4,5`; `= 127` → `1`; `= -128` → `2`; `0 < i8` → `1,5` (identical to `i8 > 0`, which is
what `flip` must produce); `127 = i8` → `1`. Id 3 appears in none of the ten.
**Vacuity:** `< 0`, `= 0` and `> 0` partition the non-null rows: 1 + 1 + 2 = 4 = 5 − 1 NULL. Assert
the three counts add up, so a predicate that silently drops rows cannot pass all three.

## TYPE-023 — INT16 against a literal, all six operators
**Intent:** as TYPE-022 for the two-byte width; `CompareInt` reads four bytes here too.
**Falsifier:** as TYPE-022. Additionally: `= 32767` returning rows where `i16` is 32767 *and*
rows where the adjacent four bytes happen to match.
**Setup:** `types.csv`; `i16` is 32767, −32768, NULL, 0, 1.
**Steps:** the ten runs of TYPE-022 with `i16` and the literals `0`, `32767`, `-32768`.
**Expected:** `= 0` → `4`; `<> 0` → `1,2,5`; `< 0` → `2`; `<= 0` → `2,4`; `> 0` → `1,5`;
`>= 0` → `1,4,5`; `= 32767` → `1`; `= -32768` → `2`; `0 < i16` → `1,5`; `32767 = i16` → `1`.

## TYPE-024 — INT32 against a literal, all six operators, including the 32-bit extremes
**Intent:** INT32 is the natural width for `CompareInt`, and the one place the literal itself can
overflow: `compare` does `(int) constant.asLong()`, so a literal outside the 32-bit range is
**silently truncated into the comparison**.
**Falsifier:** `WHERE i32 = 4294967296` matching row 4 (whose `i32` is 0), which is what
`(int) 4294967296L == 0` would do.
**Setup:** `types.csv`; `i32` is 2147483647, −2147483648, NULL, 0, 16777217.
**Steps:** the six operators against `0`; then `= 2147483647`, `= -2147483648`, `= 16777217`; then
the overflow probes `WHERE i32 = 4294967296`, `WHERE i32 = 2147483648`, `WHERE i32 > 4294967295`.
**Expected:** `= 0` → `4`; `<> 0` → `1,2,5`; `< 0` → `2`; `<= 0` → `2,4`; `> 0` → `1,5`;
`>= 0` → `1,4,5`; `= 2147483647` → `1`; `= -2147483648` → `2`; `= 16777217` → `5`.
The three probes are the finding. `4294967296` is 2³², so `(int) 4294967296L` is `0` and the
predicate would match **row 4**. `2147483648` is 2³¹, `(int)` of it is `-2147483648`, matching
**row 2**. `4294967295` is 2³²−1, `(int)` of it is `-1`, so `i32 > -1` matches `1,4,5`.
The correct answer for all three is **zero rows** (no INT32 value can equal 2³²) or a refusal.
Whichever the build does, record it; matching rows 4, 2 and `1,4,5` respectively is a wrong answer
under exit 0. Calcite may reject the literal during validation with `PRV-2002` before the truncation
can happen — that is the acceptable outcome, and the case must say which of the two occurred.

## TYPE-025 — INT64 against a literal, and the CompareInt/CompareLong split
**Intent:** INT64 goes to `CompareLong`, which reads eight bytes with `Long.compare`. The split
between the two records is by *column* type, not literal type, and this is where a 64-bit literal
must survive.
**Falsifier:** `= 9223372036854775807` returning nothing; `> 9007199254740992` returning row 5
only if the comparison stayed in `long` — a `double` comparison would make 2⁵³+1 equal to 2⁵³.
**Setup:** `types.csv`; `i64` is 9223372036854775807, −9223372036854775808, NULL, 0,
9007199254740993.
**Steps:** the six operators against `0`; then `= 9223372036854775807`,
`= -9223372036854775808`, `> 9007199254740992`, `= 9007199254740993`.
**Expected:** `= 0` → `4`; `<> 0` → `1,2,5`; `< 0` → `2`; `<= 0` → `2,4`; `> 0` → `1,5`;
`>= 0` → `1,4,5`; `= 9223372036854775807` → `1`; `= -9223372036854775808` → `2`;
`> 9007199254740992` → `1,5` (9007199254740993 > 9007199254740992 by exactly 1, and
9223372036854775807 is larger still); `= 9007199254740993` → `5`. The last two fail if anything on
the path is a double.

## TYPE-026 — FLOAT32 against a literal
**Intent:** `CompareDouble` reads with `row.getDouble(ordinal)` — an **eight-byte read of a
four-byte FLOAT32 field**. That is not a subtlety, it is a read of the neighbouring column's bytes
as the low half of a double. The case exists to find out whether FLOAT32 comparison works at all.
**Falsifier:** `WHERE f32 > 0` returning a set that is not `{1,2,5}`; `WHERE f32 = 0.0` not
returning row 4; any FLOAT32 predicate whose result changes when an unrelated adjacent column
changes.
**Setup:** `types.csv`; `f32` is 3.4028235E38, 1.4E-45, NULL, 0.0, 1.6777216E7. In the fixture
layout `f32` (offset 56) is immediately followed by `f64` (offset 64) — so an eight-byte read at
offset 56 takes `f32`'s four bytes and the four padding bytes before `f64`.
**Steps:** `WHERE f32 > 0`, `>= 0`, `= 0`, `< 0`, `<= 0`, `<> 0`; then
`WHERE f32 > 1000000`, `WHERE f32 < 1`.
**Expected, if FLOAT32 comparison is correct:** `> 0` → `1,2,5` (the smallest subnormal 1.4E-45 is
strictly greater than zero); `>= 0` → `1,2,4,5`; `= 0` → `4`; `< 0` → none; `<= 0` → `4`;
`<> 0` → `1,2,5`; `> 1000000` → `1,5`; `< 1` → `2,4`.
**Expected, and the more likely outcome:** the eight-byte read makes these answers arbitrary. Record
the observed sets exactly. A control makes the diagnosis unambiguous: add a sixth row
`6,true,0,0,0,0,1.5,0.0,x,y,0` and re-run `WHERE f32 = 1.5`; if row 6 is not returned while
`SELECT f32` returns `1.5`, the predicate is reading bytes that are not the column.
**Vacuity:** `< 0` expecting zero rows is paired with `>= 0` expecting four; the pair cannot both
pass on an empty result.

## TYPE-027 — FLOAT64 against a literal, and against an integer literal
**Intent:** `CompareDouble` on an eight-byte field is the one width it fits. The second half is the
mixed case: `WHERE f64 > 1` puts an integer literal against a double column, and
`Constant.asDouble` goes through `BigDecimal`, which is exact — so this must not lose precision.
**Falsifier:** `> 0` not returning `{1,2,5}`; `WHERE f64 = 4.9E-324` (the smallest positive
subnormal) returning nothing; `WHERE f64 > 9007199254740992` returning nothing for row 5, whose
value *is* 9007199254740992.0 and so must **not** match.
**Setup:** `types.csv`; `f64` is 1.7976931348623157E308, 4.9E-324, NULL, 0.0, 9.007199254740992E15.
**Steps:** the six operators against `0`; then `= 4.9E-324`, `= 1.7976931348623157E308`,
`> 9007199254740992`, `>= 9007199254740992`, `> 1`.
**Expected:** `> 0` → `1,2,5`; `>= 0` → `1,2,4,5`; `= 0` → `4`; `< 0` → none; `<= 0` → `4`;
`<> 0` → `1,2,5`; `= 4.9E-324` → `2`; `= 1.7976931348623157E308` → `1`;
`> 9007199254740992` → `1` only — row 5's stored value is exactly 9007199254740992.0 after the
2⁵³+1 rounding at decode, so it is **not** greater; `>= 9007199254740992` → `1,5`; `> 1` → `1,5`.
The `>` / `>=` pair is the assertion: they must differ by exactly row 5.

## TYPE-028 — STRING with `=` and `<>`
**Intent:** `CompareString` materialises the column and compares with `String.equals`, and folds
the negation into `op == Op.EQ == equal`. Unicode must compare by code point, not by encoded byte
length or by a locale-dependent collator.
**Falsifier:** `s = 'straße'` not matching row 5 of `text.csv`; `s <> 'straße'` matching it;
`s = ''` matching the NULL row; `s = 'STRASSE'` matching anything.
**Setup:** `text.csv`.
**Steps:** `WHERE s = 'hello world'`, `s = '  padded  '`, `s = 'padded'`, `s = 'straße'`,
`s = '👍ok'`, `s = '100%'`, `s <> 'a.com'`, `s = ''`.
**Expected:** → `1`; → `2` (the literal must carry its four spaces through the parser); → **none**
(`padded` is not `  padded  `, which is what proves no implicit trimming); → `5`; → `6`; → `7`
(a `%` in an `=` literal is a character, not a wildcard); `<> 'a.com'` → `1,2,5,6,7,8` — six rows,
*not* seven: row 3 is NULL and is dropped by `<>` as well as by `=`; `s = ''` → none, because the
only empty-looking row is NULL and `isNull` short-circuits before the comparison.
**Vacuity:** `= 'a.com'` returns exactly `4` and `<> 'a.com'` returns exactly six rows; 1 + 6 = 7 =
8 − 1 NULL. Assert the arithmetic, so a `<>` that kept the NULL row cannot pass.

## TYPE-029 — STRING with an ordering operator is refused, at plan time, by name
**Intent:** `<`, `<=`, `>`, `>=` on text need a collation and are refused in two places
(`PredicateCompiler.compare` and the `CompareString` constructor). The refusal must arrive at
registration, not at the first row, and must name the column.
**Falsifier:** any of the four planning successfully; a refusal that arrives at run time; a refusal
that does not name the column or does not carry `PRV-2021`.
**Setup:** `text.csv`.
**Steps:** `pravaha validate` on `SELECT id FROM text WHERE s < 'm'` and the other three operators;
then the flipped form `WHERE 'm' > s`; then `WHERE s > t` (column against column).
**Expected:** the first five all exit 1 with `PRV-2021` and
`only = and <> are supported on text column 's'; < needs a collation, and assuming one gives wrong
answers that look right` — with the operator symbol matching the one written, and `'m' > s`
reported as `<` because `flip` turned it into one. `WHERE s > t` takes a different route:
`compareExpressions` compiles both sides, then `rejectText` throws `PRV-2021` with
`compares text inside a larger expression, which Pravaha cannot do; only = and <> between a text
column and a literal are supported`. Both messages are actionable; assert the text, not just the
code.

## TYPE-030 — a TIMESTAMP literal in WHERE kills the thread
**Intent:** the known defect, written as a case so its blast radius is measured rather than
asserted. `Constant.OfLiteral.asLong` calls `RexLiteral.getValueAs(BigDecimal.class)`; Calcite has
no `TIMESTAMP`→`BigDecimal` conversion and ends its `getValueAs` with
`throw new AssertionError(...)`. An `AssertionError` is not a `RuntimeException`, so nothing in the
CLI or the server catches it.
**Falsifier:** the query returning rows (the defect is fixed — record it); or an
`AssertionError` that is caught and reported as a `PRV-` refusal (also fixed). The defect is
confirmed by an uncaught `AssertionError` and, on the server, by a worker thread that does not come
back.
**Setup:** `types.csv` for the CLI half; fixture `S` for the server half.
**Steps:**
1. `pravaha validate --stream types --schema "<types schema>" --sql "SELECT id FROM types WHERE ts >
   TIMESTAMP '2023-11-14 22:13:20'"`
2. the same under `pravaha run`
3. against fixture `S`: `pravaha register --name tsq --sql "SELECT id FROM types WHERE ts >
   TIMESTAMP '2023-11-14 22:13:20'" --keys 0`, then `pravaha queries`, then a second unrelated
   registration, then `pravaha query --sql "SELECT * FROM tsq"`
4. the workarounds: `WHERE ts > 1700000000000000000` and `WHERE ts > CAST(1700000000000000000 AS
   BIGINT)`
**Expected:** steps 1 and 2 terminate with an uncaught `java.lang.AssertionError` and a stack trace
whose top frame is inside Calcite's `RexLiteral.getValueAs` — no `PRV-` code, no line of SQL, no
suggestion. Step 3 is the measurement that matters: record whether the Flight or HTTP worker thread
survives, whether `pravaha queries` still answers afterwards, and whether the **second, unrelated**
registration still succeeds. A thread that does not return is a denial of service reachable by one
ordinary `WHERE` clause.
Step 4: `WHERE ts > 1700000000000000000` goes through `CompareLong` and must return `4` — id 4 is
one nanosecond later than the literal, id 1 is exactly equal and is excluded by `>`. That result is
the proof the column and the comparison are fine and only the literal is broken.
**Vacuity:** step 4 returning row 4 and *only* row 4 is what makes step 1's failure attributable to
the literal rather than to the column type.

## TYPE-031 — BYTES in a WHERE clause
**Intent:** BYTES has no case in `PredicateCompiler.compare`, so it must hit `default ->`. Confirm
the refusal is the honest one and that `IS NULL` still works, since `IsNull` never consults the type.
**Falsifier:** `WHERE bin = 'cafe'` planning successfully (which would compare something that is not
the bytes); or a refusal without `PRV-2021`; or `WHERE bin IS NULL` refused.
**Setup:** `types.csv`. `bin` is NULL for ids 2 and 3.
**Steps:** `WHERE bin = 'cafe'`, `WHERE bin <> 'cafe'`, `WHERE bin > 'cafe'`, `WHERE bin IS NULL`,
`WHERE bin IS NOT NULL`.
**Expected:** the first three refuse at plan time with `PRV-2021` and
`cannot compare column 'bin' of type BYTES against a constant yet`. `bin IS NULL` → `2,3`;
`bin IS NOT NULL` → `1,4,5`. 2 + 3 = 5, which is the anti-vacuity check.

## TYPE-032 — DATE, TIME, DECIMAL, ARRAY, MAP and ROW in a WHERE clause
**Intent:** complete the predicate row of the grid for the six types that cannot be configured. Two
of them (DATE, TIME) have working cases in `compare`; four do not.
**Falsifier:** DATE or TIME refused (they are implemented and should work); DECIMAL, ARRAY, MAP or
ROW accepted, or refused by an exception type other than `PravahaException`.
**Setup:** the programmatic schema of TYPE-019 and TYPE-020.
**Steps:** for each of `d` (DATE), `t` (TIME), `amt` (DECIMAL), `arr` (ARRAY), `m` (MAP),
`r` (ROW): plan `SELECT id FROM n WHERE <col> = <literal of the right SQL type>` and
`WHERE <col> IS NULL`.
**Expected:**
- `d = DATE '2024-01-01'` — `compare` sends DATE to `CompareInt` with `(int) constant.asLong()`.
  Calcite's `getValueAs(BigDecimal.class)` for a DATE literal is the same hazard as TYPE-030;
  record whether it is an `AssertionError`, a value, or a refusal. If it works, the value must be
  19723 and the row must match.
- `t = TIME '01:00:00'` — `CompareLong`, same hazard; if it works the value is 3600000000000.
- `amt = 123.45` — the column reaches `default ->` and must refuse with `PRV-2021`
  `cannot compare column 'amt' of type DECIMAL against a constant yet`.
- `arr`, `m`, `r` — comparison against any literal must refuse with `PRV-2021`. `IS NULL` on all
  six must plan and run, because `IsNull` reads only the null bitmap.

---

## 4. Aggregate argument — the type inside SUM / MIN / MAX / AVG / COUNT (TYPE-033 … TYPE-043)

`GlobalAggregate` accumulates with `row.getLong(argumentOrdinal)` and writes with
`writer.setLong(i, value)`, **whatever the column's type**. `BinaryRowWriter.setLong` accepts only
`INT64`, `TIME` and `TIMESTAMP_LTZ` output fields; everything else reaches `RowLayout.checkType` and
throws. Calcite's `SUM`, `MIN`, `MAX` and `AVG` return the *operand's* type, so the output field is
`INTEGER` for `SUM(i32)` and `TINYINT` for `SUM(i8)`. `COUNT` always returns `BIGINT`.
`refuseFloatingPointAggregate` intercepts `FLOAT32`/`FLOAT64` at plan time and exempts `COUNT` and
`COUNT(DISTINCT)`. Nothing intercepts the narrow integers.

Every case here is a **global** aggregate (no `GROUP BY`), because a keyed aggregate over a stream
is refused for unbounded state and would mask the type question behind `PRV-2050`.

## TYPE-033 — INT64: the working baseline, all five aggregate kinds
**Intent:** establish that the aggregate path produces the right numbers for the one type it was
built for, so every failure below is attributable to the type and not to the operator.
**Falsifier:** any of the five returning a number other than the hand-computed one; `COUNT(i64)`
returning 5 (which would mean it counted the NULL row and is `COUNT(*)` in disguise — finding Q-3,
fixed in `GlobalAggregate`, unverified in the other two operators).
**Setup:** `types.csv`. `i64` = 9223372036854775807, −9223372036854775808, NULL, 0,
9007199254740993.
**Steps:** five runs, each `SELECT <agg> FROM types`, `--out-schema "v:INT64?"`:
`COUNT(*)`, `COUNT(i64)`, `SUM(i64)`, `MIN(i64)`, `MAX(i64)`, `AVG(i64)`.
**Expected, by hand.** The four non-null values are 9223372036854775807, −9223372036854775808, 0 and
9007199254740993.
- `COUNT(*)` = 5 (rows, nulls included).
- `COUNT(i64)` = 4.
- `SUM(i64)`: 9223372036854775807 + (−9223372036854775808) = −1; −1 + 0 = −1;
  −1 + 9007199254740993 = **9007199254740992**. Assert that exact value. Note the intermediate never
  overflows, so this must not throw.
- `MIN(i64)` = −9223372036854775808. `MAX(i64)` = 9223372036854775807.
- `AVG(i64)`: 9007199254740992 / 4 = **2251799813685248** exactly (the numerator is 2⁵³, divisible
  by 4). Record whether the engine divides by 4 (non-null count) or by 5 (row count); dividing by 5
  gives 1801439850948198.4 and, truncated, 1801439850948198 — a wrong answer that looks reasonable.
**Vacuity:** `COUNT(*)` = 5 and `COUNT(i64)` = 4 differ by exactly the NULL row. If both return 5,
`COUNT(col)` is `COUNT(*)`; if both return 4, `COUNT(*)` is dropping a row.

## TYPE-034 — INT32 in an aggregate dies at run time, naming an internal schema
**Intent:** the defect the brief names, pinned in all four value-reading kinds. `SUM(INTEGER)` is
`INTEGER` in Calcite, so the output field is `INT32`, and `writer.setLong` refuses it — *after* the
query has planned, started, reported healthy and consumed the input.
**Falsifier:** the query returning a number (the defect is fixed — verify the number too); or being
refused at *plan* time with a `PRV-` code, which is the correct behaviour and would also close this.
**Setup:** `types.csv`. `i32` = 2147483647, −2147483648, NULL, 0, 16777217.
**Steps:** four runs — `SELECT SUM(i32) FROM types`, `MIN(i32)`, `MAX(i32)`, `AVG(i32)` — with
`--out-schema "v:INT32?"`; then `SELECT COUNT(i32) FROM types` with `--out-schema "v:INT64?"`.
**Expected:** the four value-reading kinds fail with
`java.lang.IllegalArgumentException: field 0 ('EXPR$0') is INT32, not INT64 in schema
types_aggregated` — reported by the CLI as `IllegalArgumentException: …`, exit 1, **no `PRV-` code**.
Two separate things are wrong and both must be recorded: (a) the failure is at run time for a
condition knowable at plan time, and (b) the message leaks `EXPR$0` and `types_aggregated`, neither
of which appears in the user's query.
`COUNT(i32)` = **4** and must succeed, because `COUNT` never reads the value and returns `BIGINT`.
**And the answer that is being denied, computed by hand so the fix can be checked against it:**
`SUM(i32)` = 2147483647 + (−2147483648) + 0 + 16777217 = **16777216**. `MIN` = −2147483648,
`MAX` = 2147483647, `AVG` = 16777216 / 4 = **4194304**.
**Second defect to record while here:** before the write ever fails, `row.getLong(ordinal)` on a
four-byte `INT32` field reads **eight** bytes. Even if the write is fixed, the accumulation is
reading `i64`'s first four bytes as the high half. A fix that only changes the output type produces
a green build and wrong sums; this case must re-check `SUM(i32) = 16777216` after any fix.
**Vacuity:** `COUNT(i32)` succeeding in the same run proves the plan, the source and the operator
are all working, so the four failures are about the type and nothing else.

## TYPE-035 — INT16 in an aggregate
**Intent:** the neighbour nobody tried. Same mechanism as TYPE-034 one width down, and the
hand-computed sum overflows 16 bits, so a "fix" that narrows the accumulator instead of widening
the output would pass TYPE-034 and fail here.
**Falsifier:** a returned value; a refusal naming a different type than `INT16`.
**Setup:** `types.csv`. `i16` = 32767, −32768, NULL, 0, 1.
**Steps:** `SUM(i16)`, `MIN(i16)`, `MAX(i16)`, `AVG(i16)`, `COUNT(i16)`.
**Expected:** the four value kinds fail with `field 0 ('EXPR$0') is INT16, not INT64 in schema
types_aggregated`. `COUNT(i16)` = 4.
The denied answers: `SUM` = 32767 + (−32768) + 0 + 1 = **0**; `MIN` = −32768; `MAX` = 32767;
`AVG` = 0 / 4 = **0**. Note `SUM` is 0, which is also what a broken implementation returns from an
empty accumulator — so when the fix lands, re-run with a sixth row `6,…,i16=100,…` and assert
`SUM = 100`, not 0.

## TYPE-036 — INT8 in an aggregate
**Intent:** the narrowest width, where the sum most obviously does not fit the output type Calcite
chose. `SUM(TINYINT)` is `TINYINT`, and the true sum here is 0 — but `MAX` alone is 127, which does
fit, so a partial fix could make `MAX` pass while `SUM` stays wrong.
**Falsifier:** a returned value; `MAX` succeeding while `SUM` fails after a fix that claimed to
cover both.
**Setup:** `types.csv`. `i8` = 127, −128, NULL, 0, 1.
**Steps:** `SUM(i8)`, `MIN(i8)`, `MAX(i8)`, `AVG(i8)`, `COUNT(i8)`.
**Expected:** four failures with `field 0 ('EXPR$0') is INT8, not INT64 in schema
types_aggregated`; `COUNT(i8)` = 4.
Denied answers: `SUM` = 127 + (−128) + 0 + 1 = **0**; `MIN` = −128; `MAX` = 127; `AVG` = 0.
**The neighbour that matters after a fix:** if the output is widened to `BIGINT`, `SUM(i8)` over a
file of 200 rows each holding 127 must be **25400**, not `(byte) 25400 = 56`. Add `i8sum.csv` —
200 lines of `n,127` with schema `id:INT64,i8:INT8` — and assert 25400.

## TYPE-037 — FLOAT32 in an aggregate is refused at plan time
**Intent:** the fix for finding Q-2, verified as a *refusal* rather than assumed. The message must
arrive at registration, name the column, name the type, and give the workaround.
**Falsifier:** the query planning; the query returning 0 rows under a successful status (the
original defect); a refusal that names the wrong column or the wrong type.
**Setup:** `types.csv`.
**Steps:** `pravaha validate` on `SELECT SUM(f32) FROM types`, `MIN(f32)`, `MAX(f32)`, `AVG(f32)`.
**Expected:** all four exit 1 with `PRV-2020` and
`<KIND>(f32) is over a FLOAT32 column, and this engine's aggregates accumulate in 64-bit integers
only. It is refused rather than answered, because the alternative was no rows and a successful
status. Cast the column to an integer if the rounding is acceptable -- SUM(CAST(price AS BIGINT)) --
or aggregate it outside the engine.` — with `<KIND>` being `SUM`, `MIN`, `MAX`, `AVG` respectively
and the column named as `f32`.
**Vacuity:** the refusal is at `validate`, which reads no data at all. A case run only through
`pravaha run` could not distinguish "refused" from "the file was empty".

## TYPE-038 — FLOAT64 in an aggregate is refused at plan time
**Intent:** as TYPE-037 for the eight-byte width. Kept separate because
`refuseFloatingPointAggregate` tests the two constants in one condition and a typo affects one.
**Falsifier:** as TYPE-037; additionally, a message naming `FLOAT32` for a `FLOAT64` column.
**Setup:** `types.csv`.
**Steps:** `pravaha validate` on `SUM(f64)`, `MIN(f64)`, `MAX(f64)`, `AVG(f64)`; then
`SELECT SUM(f64), COUNT(*) FROM types` — a mixed select list, to confirm one refused call refuses
the whole query rather than being dropped.
**Expected:** four refusals with `PRV-2020` naming `FLOAT64` and the column `f64`. The mixed select
list is refused too.
**The answers being denied, for whoever builds the double accumulator:** `SUM(f64)` =
1.7976931348623157E308 + 4.9E-324 + 0.0 + 9.007199254740992E15. Adding 4.9E-324 to 1.797…E308
changes nothing (the addend is ~600 orders of magnitude below the ulp), and so does adding
9.007…E15, so `SUM` = **1.7976931348623157E308**. `MIN` = 0.0, `MAX` = 1.7976931348623157E308,
`AVG` = 1.7976931348623157E308 / 4 = **4.494232837155789E307**.

## TYPE-039 — COUNT and COUNT(DISTINCT) over floating point are exempt from the refusal
**Intent:** the exemption is deliberate and narrow: `COUNT` reads no value. Enumerate both spellings
against both float widths, because `kindOf` distinguishes them by `call.isDistinct()` and the
exemption tests two constants.
**Falsifier:** `COUNT(f64)` refused; `COUNT(DISTINCT f64)` refused; either returning a count that
includes the NULL row.
**Setup:** `types.csv`.
**Steps:** `COUNT(f32)`, `COUNT(f64)`, `COUNT(DISTINCT f32)`, `COUNT(DISTINCT f64)`, `COUNT(*)`.
**Expected:** `COUNT(f32)` = 4, `COUNT(f64)` = 4, `COUNT(*)` = 5.
`COUNT(DISTINCT f32)`: the four non-null values are 3.4028235E38, 1.4E-45, 0.0, 1.6777216E7 — all
different — so **4**. `COUNT(DISTINCT f64)` likewise **4**.
Finding Q-6 says `COUNT(DISTINCT)` hangs for five minutes over a stream and is refused over a view;
if the distinct forms hang, record the wall-clock time and treat the case as blocked by Q-6 rather
than as a type failure — but the two plain `COUNT`s must still pass, and that is the part this case
owns.
**Vacuity:** `COUNT(*)` = 5 against `COUNT(f64)` = 4 is the null-aware check; without it, a `COUNT`
that ignored its argument would pass.

## TYPE-040 — STRING in an aggregate
**Intent:** `MIN`/`MAX` over text is valid SQL and Calcite types it as `VARCHAR`. The accumulator
does `row.getLong` on a **variable-width slot**, reading the (offset, length) pair as a 64-bit
number, and then writes it into a `STRING` output field. Establish which of the two failures
arrives first, and that `COUNT` still works.
**Falsifier:** `MIN(s)` returning a string (verify which one); `SUM(s)` planning; `COUNT(s)`
failing.
**Setup:** `types.csv`; `s` = `zed`, `ann`, NULL, NULL, `  pad  `.
**Steps:** `SELECT MIN(s) FROM types`, `MAX(s)`, `COUNT(s)`, `COUNT(DISTINCT s)`, `SUM(s)`,
`AVG(s)`.
**Expected:** `SUM(s)` and `AVG(s)` are rejected by Calcite's validator with `PRV-2002` before
Pravaha sees them — a type mismatch, not an unsupported feature; assert the code.
`MIN(s)` and `MAX(s)` plan (nothing refuses them) and fail at run time in
`BinaryRowWriter.setLong` with `field 0 ('EXPR$0') is STRING, not INT64 in schema
types_aggregated`. Record it as the same class of defect as TYPE-034: run-time, internal names, no
`PRV-` code.
`COUNT(s)` = **3** (`zed`, `ann`, `  pad  `; ids 3 and 4 are NULL).
`COUNT(DISTINCT s)` = **3** — the three are distinct.
**The denied answers, for the record:** with byte-wise ordering `MIN(s)` = `  pad  ` (leading space,
U+0020, sorts below any letter) and `MAX(s)` = `zed`. Note that these are exactly the answers the
codebase refuses to guess at for `<` on text (TYPE-029) — so a future `MIN(s)` must decide the same
collation question, and this case is where that decision gets written down.

## TYPE-041 — BOOLEAN in an aggregate
**Intent:** completes the row. `COUNT` must work; `SUM`/`AVG` are a validator error; `MIN`/`MAX`
over BOOLEAN is accepted by Calcite and must then meet the same `setLong` wall.
**Falsifier:** `COUNT(b)` = 5; `SUM(b)` planning and returning 2.
**Setup:** `types.csv`; `b` = true, false, NULL, true, false.
**Steps:** `COUNT(b)`, `COUNT(DISTINCT b)`, `MIN(b)`, `MAX(b)`, `SUM(b)`, `AVG(b)`.
**Expected:** `COUNT(b)` = **4**; `COUNT(DISTINCT b)` = **2** (`true` and `false`).
`SUM(b)` / `AVG(b)` → `PRV-2002` from the validator.
`MIN(b)` / `MAX(b)` → run-time `field 0 ('EXPR$0') is BOOLEAN, not INT64 in schema
types_aggregated`. Denied answers: `MIN` = false, `MAX` = true.

## TYPE-042 — BYTES in an aggregate
**Intent:** completes the row for the second variable-width type, where `row.getLong` on the slot is
the same hazard as TYPE-040.
**Falsifier:** `COUNT(bin)` returning 5; `MIN(bin)` returning a value.
**Setup:** `types.csv`; `bin` = `cafe`, NULL, NULL, `beef`, `ff`.
**Steps:** `COUNT(bin)`, `COUNT(DISTINCT bin)`, `MIN(bin)`, `MAX(bin)`, `SUM(bin)`.
**Expected:** `COUNT(bin)` = **3**; `COUNT(DISTINCT bin)` = **3**; `SUM(bin)` → `PRV-2002`;
`MIN(bin)` / `MAX(bin)` → run-time `field 0 ('EXPR$0') is BYTES, not INT64 in schema
types_aggregated`.
**Note:** `COUNT(DISTINCT bin)` compares rows for distinctness. If that comparison reaches
`RowValues.equal`, BYTES has no case there and raises
`UnsupportedOperationException: cannot compare 'bin' of type BYTES for row equality yet`. Record
which of the two exceptions arrives; either is a finding, and they point at different code.

## TYPE-043 — TIMESTAMP_LTZ in an aggregate: the one non-INT64 type that should work
**Intent:** `setLong` accepts `TIME` and `TIMESTAMP_LTZ` output fields, and `MIN`/`MAX` of a
timestamp returns a timestamp — so `MIN(ts)` is the single combination in this section that must
produce a correct answer. It is the positive control for the whole section.
**Falsifier:** `MIN(ts)`/`MAX(ts)` failing (which would mean `setLong`'s timestamp exemption does
not reach the aggregate path); or returning a millisecond value.
**Setup:** `types.csv`; `ts` = 1700000000000000000, 0, NULL, 1700000000000000001, −1.
**Steps:** `MIN(ts)`, `MAX(ts)`, `COUNT(ts)`, `COUNT(DISTINCT ts)`, `SUM(ts)`, `AVG(ts)` with
`--out-schema "v:TIMESTAMP?"` for the first two and `"v:INT64?"` for `COUNT`.
**Expected:** `MIN(ts)` = **−1** (one nanosecond before the epoch; it must beat 0, which is the
trap — a comparison that treated the epoch as "unset" would return 0). `MAX(ts)` =
**1700000000000000001**, one nanosecond above id 1's value, which is the nanosecond-precision
assertion. `COUNT(ts)` = **4**. `COUNT(DISTINCT ts)` = **4**.
`SUM(ts)` / `AVG(ts)` → `PRV-2002` from the validator (summing instants is not meaningful).
**Vacuity:** `MIN` = −1 and `MAX` = 1700000000000000001 are both values that exist in exactly one
row, and the two rows are different. An accumulator stuck on its first or last input cannot produce
both.

---

## 5. Join key — the type on both sides of an equality (TYPE-044 … TYPE-052)

`JoinKeys.checkJoinable` refuses `FLOAT32`, `FLOAT64` and `DECIMAL` **at plan time**, with a `PRV-`
code and a suggested rewrite. `JoinKeys.fieldHash` and `sameValue` then handle `BOOLEAN`, `INT8`,
`INT16`, `INT32`, `DATE`, `INT64`, `TIME`, `TIMESTAMP_LTZ` and `STRING`, and throw for everything
else — `BYTES`, `ARRAY`, `MAP`, `ROW` — **at run time, on the first row**. That gap is the subject of
TYPE-051.

A stream-to-stream join needs two declared streams and therefore fixture `S`; `pravaha run` takes
one `--stream` and cannot express one. Every case here registers a continuous query and reads the
view. All use an equi-join with a time bound, which is the supported shape
(`SQL_SUPPORT.md` — "Equi-join with a time bound ✅"), because the bound is what arms eviction.

**Extended join fixtures.** Add to fixture `S`:

```yaml
    jl: { schema: "id:INT64,kb:BOOLEAN?,k8:INT8?,k16:INT16?,k32:INT32?,k64:INT64?,kf:FLOAT64?,ks:STRING?,kbin:BYTES?,ts:TIMESTAMP" }
    jr: { schema: "id:INT64,kb:BOOLEAN?,k8:INT8?,k16:INT16?,k32:INT32?,k64:INT64?,kf:FLOAT64?,ks:STRING?,kbin:BYTES?,tag:STRING,ts:TIMESTAMP" }
```

`jl.csv`
```
1,true,7,700,70000,7000000000,1.5,alpha,cafe,1700000000000000000
2,false,-8,-800,-80000,-8000000000,2.5,beta,beef,1700000001000000000
3,,,,,,,,,1700000002000000000
4,true,7,700,70000,7000000000,1.5,alpha,cafe,1700000003000000000
```
`jr.csv`
```
1,true,7,700,70000,7000000000,1.5,alpha,cafe,L,1700000000500000000
2,true,9,900,90000,9000000000,3.5,gamma,dead,M,1700000001500000000
3,,,,,,,,,N,1700000002500000000
4,false,-8,-800,-80000,-8000000000,2.5,beta,beef,O,1700000003500000000
```

The join condition throughout is
`ON l.<k> = r.<k> AND l.ts BETWEEN r.ts - INTERVAL '10' SECOND AND r.ts + INTERVAL '10' SECOND`,
which puts every pair inside the window so the *key* decides the result and nothing else.

## TYPE-044 — STRING join key
**Intent:** the commonest key type and the one with the only non-trivial hash (`stringHash`, an
FNV-1a over Java `char`s). Establish that equal strings on two sides land in the same bucket and
that the candidate is confirmed by value.
**Falsifier:** zero rows from a join whose keys plainly match (the classic symptom of a hash that
picked up an ordinal — `ks` is ordinal 7 on the left and ordinal 7 on the right here, so the case
adds a second registration with the right-hand columns reordered to make the ordinals differ);
or a pair emitted whose keys are not equal.
**Setup:** fixture `S` with `jl`/`jr`.
**Steps:**
1. register `jks`: `SELECT l.id AS lid, r.id AS rid, r.tag FROM jl AS l JOIN jr AS r ON l.ks = r.ks
   AND l.ts BETWEEN r.ts - INTERVAL '10' SECOND AND r.ts + INTERVAL '10' SECOND`, `--keys 0`
2. `pravaha query --sql "SELECT * FROM jks"`
3. register the same join against a `jr2` whose schema lists `ks` at a different ordinal, and
   compare
**Expected:** by hand, the left `ks` values are `alpha`, `beta`, NULL, `alpha`; the right are
`alpha`, `gamma`, NULL, `beta`. Matching pairs: l1–r1 (`alpha`), l4–r1 (`alpha`), l2–r4 (`beta`).
NULL never matches NULL, so l3 and r3 pair with nothing. **3 rows**:
`1,1,L` / `4,1,L` / `2,4,O`. Step 3 must return the identical three rows.
**Vacuity:** three rows and not four is the assertion — a join that matched NULL to NULL would
return four, and one that ignored the value after hashing would return more. Also register the
inverted control `ON l.ks = r.tag`, which must return **0 rows**; a join returning rows for both is
not joining on anything.

## TYPE-045 — INT64 join key
**Intent:** the widest integer key, hashed directly from `row.getLong`.
**Falsifier:** a value above 2³² colliding with one below it in a way that produces a wrong pair;
zero rows.
**Setup:** as TYPE-044, joining on `k64` (7000000000, −8000000000, NULL, 7000000000 on the left;
7000000000, 9000000000, NULL, −8000000000 on the right). All four magnitudes are outside the 32-bit
range, deliberately.
**Steps:** register `jk64` on `l.k64 = r.k64` with the same time bound; read the view.
**Expected:** **3 rows**: `1,1,L` / `4,1,L` / `2,4,O` — the same pairing as TYPE-044, because the
columns encode the same grouping. Matching pairings across two different key types is itself the
check: if `jk64` and `jks` disagree, one of the two hashes is wrong.

## TYPE-046 — INT32 join key
**Intent:** `fieldHash` reads `INT32` with `row.getInt` — the correct width — while the aggregate
path reads the same column with `getLong`. Confirm the join path is the one that is right.
**Falsifier:** a pairing that differs from TYPE-044/045.
**Setup:** join on `k32` (70000, −80000, NULL, 70000 / 70000, 90000, NULL, −80000).
**Steps:** register `jk32`; read.
**Expected:** **3 rows**: `1,1,L` / `4,1,L` / `2,4,O`.

## TYPE-047 — INT8 and INT16 join keys
**Intent:** the two narrow widths, read by `getByte` and `getShort`. A sign-extension error shows
here and nowhere else: −8 read as an unsigned byte is 248, which hashes differently on the two sides
only if one side sign-extends and the other does not.
**Falsifier:** the `−8` pair (l2–r4) missing while the `7` pairs are present, or the reverse.
**Setup:** join on `k8` (7, −8, NULL, 7 / 7, 9, NULL, −8), then on `k16` (700, −800, NULL, 700 /
700, 900, NULL, −800).
**Steps:** register `jk8` and `jk16`; read both.
**Expected:** each returns **3 rows**: `1,1,L` / `4,1,L` / `2,4,O`. The `2,4,O` row is the
sign-extension assertion in both.

## TYPE-048 — BOOLEAN join key
**Intent:** a one-bit key space. It is legal, it is nearly always a mistake, and the engine must
still be correct: `fieldHash` maps it to 1 or 0 and `sameValue` compares the booleans.
**Falsifier:** `false` matching `true`; the NULL row matching either.
**Setup:** join on `kb` (true, false, NULL, true / true, true, NULL, false).
**Steps:** register `jkb`; read.
**Expected:** by hand — left `true` rows are l1, l4; right `true` rows are r1, r2; left `false` is
l2; right `false` is r4. But the time bound is ±10 s and every row is within 4 s of every other, so
**every** true-true and false-false pair is inside the window. Pairs: l1–r1, l1–r2, l4–r1, l4–r2,
l2–r4 = **5 rows**: `1,1,L` / `1,2,M` / `4,1,L` / `4,2,M` / `2,4,O`. l3 and r3 (NULL) pair with
nothing.
**Vacuity:** five rows, not six and not three. A boolean key produces a near-cross-product, and that
is the point: if the count is three, the join is collapsing duplicates it must not collapse; if it
is six, NULL matched NULL.

## TYPE-049 — TIMESTAMP_LTZ join key
**Intent:** joining on an instant, which `fieldHash` reads with `getLong` alongside `INT64` and
`TIME`. Distinct from the *time bound*, which is a different mechanism on the same column.
**Falsifier:** two timestamps one nanosecond apart treated as equal; the join matching nothing.
**Setup:** add a column `kts:TIMESTAMP?` to `jl`/`jr`, with left values 1700000000000000000,
1700000000000000001, NULL, 1700000000000000000 and right values 1700000000000000000,
1700000000000000002, NULL, 1700000000000000001.
**Steps:** register `jkts` on `l.kts = r.kts` with the same `ts` time bound; read.
**Expected:** l1 (…000) matches r1 (…000); l4 (…000) matches r1; l2 (…001) matches r4 (…001). l3
and r3 are NULL. **3 rows**: `1,1,L` / `4,1,L` / `2,4,O`. If `…001` matched `…000` or `…002`, the
comparison lost nanosecond resolution.

## TYPE-050 — FLOAT32 and FLOAT64 join keys are refused at plan time
**Intent:** `checkJoinable`'s refusal, verified as a refusal with an actionable rewrite — not merely
as "it failed".
**Falsifier:** the join planning; a refusal arriving at run time rather than registration; a message
that does not name the column or does not suggest the rewrite.
**Setup:** fixture `S`; `kf` is FLOAT64 on both sides. Add a FLOAT32 column `kf32` to both for the
second half.
**Steps:** register a join on `l.kf = r.kf`; then on `l.kf32 = r.kf32`; then the suggested rewrite
`ON CAST(l.kf AS BIGINT) = CAST(r.kf AS BIGINT)`.
**Expected:** both refusals exit non-zero at **registration** with the `UNSUPPORTED_JOIN` code and
`cannot join on 'kf' (left): it is FLOAT64, and floating-point equality drops rows that differ only
by rounding. Round or cast to an integer type, or join on a different column.` — the side named
(`left`/`right`) must match the side that carries the column, and the type named must be `FLOAT32`
for the second.
The rewrite must then **work**: `CAST(1.5 AS BIGINT)` is 1 and `CAST(2.5 AS BIGINT)` is 2
(truncation toward zero), so left keys are 1, 2, NULL, 1 and right keys are 1, 3, NULL, 2 — pairs
l1–r1, l4–r1, l2–r4 = **3 rows**. A refusal whose suggested rewrite does not work is not actionable.

## TYPE-051 — a BYTES join key plans successfully and dies on the first row
**Intent:** the gap between `checkJoinable` (three types) and `fieldHash` (nine types). `BYTES`,
`ARRAY`, `MAP` and `ROW` are in neither list, so the query registers, reports `RUNNING`, and throws
when a row arrives. This is the dishonest refusal for the join position.
**Falsifier:** the join refused at plan time (fixed — record it); or the join returning pairs.
**Setup:** fixture `S`; `kbin` is BYTES on both sides, values `cafe`, `beef`, NULL, `cafe` /
`cafe`, `dead`, NULL, `beef`.
**Steps:**
1. register `jkbin` on `l.kbin = r.kbin` with the time bound
2. `pravaha queries` immediately afterwards — record the state
3. wait for the source to deliver, then `pravaha queries` again and `pravaha query --sql "SELECT *
   FROM jkbin"`
4. repeat for the programmatic `ARRAY`, `MAP` and `ROW` columns of TYPE-020
**Expected:** step 1 succeeds — **this is the defect**. Step 2 reports `RUNNING`. Step 3 is the
measurement: `JoinKeys.fieldHash` raises `UNSUPPORTED_JOIN` with `cannot hash a join key of type
BYTES`, and the question is where it surfaces. Record (a) whether `pravaha queries` shows the query
as failed or still `RUNNING`, (b) whether the view answers, with how many rows, and (c) whether the
lane thread survives. A query that reports `RUNNING` for ever while its first row killed the lane is
the worst of the three outcomes and is what finding I-6 predicts.
**The answer being denied:** with byte equality the pairs would be l1–r1, l4–r1, l2–r4 = 3 rows.
**Vacuity:** the identical join on `ks` (TYPE-044) returns three rows from the same two files, so a
zero-row result here is attributable to the key type.

## TYPE-052 — a NULL key joins with nothing, on every key type
**Intent:** `equal` returns false the moment either side is null, and `fieldHash` gives nulls a
stable constant so the index does not leak. Both halves matter: the first is correctness, the second
is a slow memory failure.
**Falsifier:** l3 paired with r3; a null-keyed row pairing with a non-null one; the join's state
growing with the number of null-keyed rows.
**Setup:** fixture `S`. `jl` row 3 and `jr` row 3 have every key column NULL.
**Steps:** for each of the seven working key types (`ks`, `k64`, `k32`, `k16`, `k8`, `kb`, `kts`),
read the registered view and check l3/r3; then append 10,000 further all-NULL rows to each side and
re-read.
**Expected:** for all seven, **no output row has `lid = 3` or `rid = 3`**. After the 10,000 rows,
the row count of every view is unchanged and the node's heap does not grow monotonically across
three consecutive reads.
**Vacuity:** the 10,000-row extension is the anti-vacuity device. A join that dropped null keys at
ingest rather than at match would pass the first half and fail the second, and the first half alone
cannot tell them apart.

---

## 6. GROUP BY key — the type as a grouping column (TYPE-053 … TYPE-059)

Two operators, two type switches. `KeyedAggregate.read` (the bounded, view-read path) handles
`BOOLEAN INT8 INT16 INT32 DATE INT64 TIME TIMESTAMP_LTZ FLOAT32 FLOAT64 STRING` and refuses the rest
with `UNSUPPORTED_AGGREGATE` at run time. Note what that list contains that the *aggregate argument*
list does not: **FLOAT32 and FLOAT64 are legal group keys while being illegal aggregate arguments**.
That asymmetry is TYPE-055.

A keyed aggregate over a stream is refused for unbounded state (`PRV-2050`) unless it is windowed,
so the cases here use the bounded shape `SQL_SUPPORT.md` documents: a `GROUP BY` over a **read of a
maintained view**. Fixture `S` with a registered pass-through view `tv`
(`SELECT id,b,i8,i16,i32,i64,f32,f64,s,bin,ts FROM types`, `--keys 0`) supplies it.

## TYPE-053 — STRING group key over a bounded view read
**Intent:** the baseline, and the case that proves the vehicle works before any type is doubted.
**Falsifier:** a group count other than the hand-computed one; two distinct strings merged.
**Setup:** fixture `S`, view `tv`. `s` = `zed`, `ann`, NULL, NULL, `  pad  `.
**Steps:** `pravaha query --sql "SELECT s, COUNT(*) AS n FROM tv GROUP BY s"`.
**Expected:** **4 groups**: `zed`→1, `ann`→1, `  pad  `→1, NULL→2. The counts sum to 5, which is the
row count. NULL is a group, not a discarded row — that is SQL's rule and `SQL_SUPPORT.md` states it.
**Vacuity:** the counts summing to 5 is the assertion. A grouping that dropped the NULL rows would
give three groups summing to 3 and would still look like a plausible answer on its own.

## TYPE-054 — the four integer widths as group keys
**Intent:** `read` returns a boxed `Byte`, `Short`, `Integer` or `Long` per width, and `Key` compares
with `Arrays.equals`, which is `Object.equals` per element. **A `Byte(7)` is not equal to an
`Integer(7)`.** If any width is read at the wrong size, groups either merge or split, and both look
like data.
**Falsifier:** a group count other than the hand-computed one for any width; the same query giving
different group counts on two runs.
**Setup:** view `tv`. `i8` = 127, −128, NULL, 0, 1. `i16` = 32767, −32768, NULL, 0, 1.
`i32` = 2147483647, −2147483648, NULL, 0, 16777217. `i64` = 9223372036854775807,
−9223372036854775808, NULL, 0, 9007199254740993.
**Steps:** four queries, `SELECT <col>, COUNT(*) FROM tv GROUP BY <col>` for each width.
**Expected:** each returns **5 groups** of 1 — every non-null value in every column is distinct, and
NULL is its own group. Counts sum to 5 in all four.
Then the merge check: add `6,true,1,1,1,1,0.0,0.0,x,y,0` to `types.csv` and re-run. `i8` now has
`1` twice, so it must report **5 groups** with the `1` group at count 2 and the others at 1; the
same for `i16`, `i32` and `i64`. A width read too wide would put row 5 and row 6 in different groups
for `i8` (127/1 vs a two-byte read picking up `i16`) and the count would stay 6.

## TYPE-055 — FLOAT32 and FLOAT64 are legal group keys and illegal aggregate arguments
**Intent:** the asymmetry, written as one case because it is only visible when both halves are in
front of you. `SUM(f64)` is refused at plan time on the grounds that the accumulators are integer;
`GROUP BY f64` is accepted and boxes a `Double`. Grouping floats has the same rounding hazard the
join refusal cites — `0.1 + 0.2` is not `0.3` — and is not refused.
**Falsifier:** `GROUP BY f64` refused (the asymmetry is closed — record it); or `GROUP BY f64`
merging two distinct doubles; or `-0.0` and `0.0` landing in different groups while comparing equal
everywhere else.
**Setup:** view `tv`, plus rows exercising the hazard: append
`7,true,0,0,0,0,0.1,0.1,g,h,0`, `8,true,0,0,0,0,0.2,0.2,g,h,0`, `9,true,0,0,0,0,0.3,0.30000000000000004,g,h,0`
and `10,true,0,0,0,0,-0.0,-0.0,g,h,0` to `types.csv`.
**Steps:**
1. `SELECT f64, COUNT(*) FROM tv GROUP BY f64`
2. `SELECT SUM(f64) FROM tv` — to have both answers side by side
3. `SELECT f64, COUNT(*) FROM tv GROUP BY f64 HAVING COUNT(*) > 1`
**Expected:** step 1 succeeds. Step 2 is refused with `PRV-2020` and the floating-point message.
Both facts belong in the log as one finding: the engine will group by a value it will not add.
Step 1's groups, by hand over the ten rows: `1.7976931348623157E308`→1, `4.9E-324`→1, NULL→1,
`0.0`→1 (row 4), `9.007199254740992E15`→1, `0.0`→row 6, `0.1`→1, `0.2`→1,
`0.30000000000000004`→1, `-0.0`→1.
The two questions the case decides: (a) do rows 4 and 6 (both `0.0`) merge into one group of 2 — they
must, and if they do the group total is **9 groups**; (b) does `-0.0` join them?
`Double.valueOf(-0.0).equals(Double.valueOf(0.0))` is **false**, so `-0.0` is a separate group and
the answer is 9 groups with `0.0` at count 2 and `-0.0` at count 1 — even though `-0.0 == 0.0` is
true in every comparison the engine performs elsewhere. Record it: the group key and the predicate
disagree about whether two values are the same value.
Step 3 must return exactly the `0.0`→2 row.
**Vacuity:** row 9's value is `0.30000000000000004`, the actual double result of `0.1 + 0.2`. If a
future build computes `f64` sums and groups them, that row is what shows whether the grouping
matches the arithmetic.

## TYPE-056 — BOOLEAN group key
**Intent:** three groups from a two-valued type, because NULL is a group.
**Falsifier:** two groups (NULL folded into `false`); or `true` and `false` merged.
**Setup:** view `tv`; `b` = true, false, NULL, true, false.
**Steps:** `SELECT b, COUNT(*) FROM tv GROUP BY b`; then `SELECT b, COUNT(b) FROM tv GROUP BY b`.
**Expected:** **3 groups**: `true`→2, `false`→2, NULL→1; counts sum to 5.
The second query differs in exactly one place: `COUNT(b)` over the NULL group is **0**, not 1,
because `COUNT(col)` does not count nulls. A group present with a count of zero is correct and is
the assertion — a build that omitted the group entirely, or reported 1, has confused the two counts.

## TYPE-057 — TIMESTAMP_LTZ group key
**Intent:** grouping by an instant at nanosecond resolution. Distinct from grouping by a window,
which is a different mechanism.
**Falsifier:** the two timestamps one nanosecond apart merging; the epoch (`0`) merging with NULL.
**Setup:** view `tv`; `ts` = 1700000000000000000, 0, NULL, 1700000000000000001, −1.
**Steps:** `SELECT ts, COUNT(*) FROM tv GROUP BY ts`.
**Expected:** **5 groups** of 1. Rows 1 and 4 differ by one nanosecond and must not merge; row 2
(the epoch) and row 3 (NULL) must not merge; row 5 is negative.
**Vacuity:** five groups over five rows is what a *broken* grouping also produces if it hashes the
row rather than the key. Pair it: add `11,true,0,0,0,0,0.0,0.0,x,y,0` (`ts` = 0, same as row 2) and
re-run — the answer must become 5 groups with the `0` group at count 2, not 6 groups.

## TYPE-058 — BYTES and DECIMAL group keys are refused at run time
**Intent:** `read`'s `default ->` branch. The refusal has a `PRV-` code and names the type, which is
better than the join path, but it still arrives when the first row is read rather than at planning.
**Falsifier:** `GROUP BY bin` returning groups; a refusal with no code or no type name; a refusal at
plan time (fixed — record it).
**Setup:** view `tv` for BYTES; the programmatic schema of TYPE-019 for DECIMAL, DATE and TIME.
**Steps:** `SELECT bin, COUNT(*) FROM tv GROUP BY bin`; then the same for the programmatic `amt`
(DECIMAL), `d` (DATE) and `t` (TIME).
**Expected:** `GROUP BY bin` fails with `UNSUPPORTED_AGGREGATE` and `cannot group by a column of
type BYTES yet`. `amt` fails the same way naming `DECIMAL`. `d` and `t` **succeed** — `DATE` and
`TIME` are in `read`'s switch — and must produce one group per distinct value; that pair is the
positive control proving the switch, not the operator, is what refuses BYTES.
**The denied answer:** `bin` = `cafe`, NULL, NULL, `beef`, `ff` → 4 groups, NULL at count 2.

## TYPE-059 — NULL is a group, and a multi-column key with NULLs in it
**Intent:** SQL's rule that `GROUP BY` treats NULL as a value while a comparison treats it as
UNKNOWN, extended to a composite key where `Arrays.equals` must treat two `null` elements as equal.
**Falsifier:** rows with a NULL in any key column vanishing; two rows with NULLs in *different*
columns grouped together.
**Setup:** view `tv`. Composite key `(b, s)`: row 1 `(true,'zed')`, row 2 `(false,'ann')`,
row 3 `(NULL,NULL)`, row 4 `(true,NULL)`, row 5 `(false,'  pad  ')`.
**Steps:**
1. `SELECT b, s, COUNT(*) FROM tv GROUP BY b, s`
2. `SELECT COUNT(*) FROM tv` — the control total
3. `SELECT b, s, COUNT(s) FROM tv GROUP BY b, s`
**Expected:** step 1 gives **5 groups** of 1. The load-bearing pair is rows 3 and 4: both have a
NULL `s`, and they must *not* merge, because their `b` differs (NULL vs true). `Arrays.equals`
compares `null` to `Boolean.TRUE` and finds them different — assert that the two appear separately.
Step 2 = 5. Step 3: `COUNT(s)` is 0 for the row-3 group and 0 for the row-4 group, 1 for the other
three; the five counts sum to 3, which is `COUNT(s)` over the whole table.
**Vacuity:** 5 groups summing to 5 rows, and `COUNT(s)` summing to 3. Two independent totals that a
grouping which silently drops NULL-keyed rows cannot both satisfy.

---

## 7. Window boundary — the type of the event-time column, and of window_start/window_end (TYPE-060 … TYPE-064)

`PhysicalPlanBuilder.appendBoundaries` adds `window_start` and `window_end` as
`Types.timestamp()` — `TIMESTAMP_LTZ`, precision 9, **NOT NULL**. `WindowAssign` reads the event-time
column with `row.getLong(eventTimeOrdinal)`, unconditionally, so a window over a column that is not
eight bytes reads bytes it does not own.

Every case here needs a firing window, which needs a watermark, which needs `event.time` on the
source binding — so all of them use fixture `S` and **none** can be reproduced with `pravaha run`.

## TYPE-060 — TIMESTAMP as the event-time column: the supported shape
**Intent:** the positive control for the section, and the regression guard for finding Q-1 (windowed
aggregation emitted nothing, ever, silently).
**Falsifier:** zero rows out of a window whose data is complete and whose watermark has passed —
which is exactly how Q-1 presented, under a `RUNNING` status.
**Setup:** fixture `S`, stream `types` bound with `event.time: ts`. To make the arithmetic simple,
use `win.csv` — schema `id:INT64,u:STRING,amt:INT64,ts:TIMESTAMP` — with
`1,u1,100,1700000000000000000` / `2,u1,102,1700000001000000000` /
`3,u2,7,1700000002000000000` / `4,u1,1,1700000305000000000`.
**Steps:** register `wv`:
`SELECT STREAM TUMBLE_END(ts, INTERVAL '10' SECOND) AS window_end, u, COUNT(*) AS n, SUM(amt) AS
total FROM win GROUP BY TUMBLE(ts, INTERVAL '10' SECOND), u`, `--keys 1`; then
`pravaha query --sql "SELECT * FROM wv"`.
**Expected:** the first three rows are at t+0 s, t+1 s and t+2 s, inside the ten-second window
[1700000000000000000, 1700000010000000000). Row 4 is at t+305 s, which advances the watermark past
that window's end and closes it. **2 groups** from the first window:
`u1` → n = 2, total = 100 + 102 = **202**; `u2` → n = 1, total = **7**.
Row 4's own window has not closed and must not appear.
**Vacuity:** the case cannot pass with windowing removed, because without a close nothing is emitted
at all and the view is empty — which is precisely the failure mode being guarded. Assert **2 rows**
present and `total = 202`, not merely "some rows".

## TYPE-061 — an INT64 column as the event-time column
**Intent:** epoch nanoseconds in a `BIGINT` column is what a lot of real data looks like. `TUMBLE`
takes a `DESCRIPTOR`/time column that Calcite validates as temporal, so this must be refused — and
the refusal must not be a wrong-window answer.
**Falsifier:** `TUMBLE(n, INTERVAL '10' SECOND)` over a `BIGINT n` planning and producing windows.
**Setup:** fixture `S` with `winl` — schema `id:INT64,u:STRING,amt:INT64,n:INT64` — the same four
rows with `n` holding the same nanosecond values.
**Steps:** register the TYPE-060 query with `n` in place of `ts`.
**Expected:** refused at registration. Record which code arrives: Calcite's validator (`PRV-2002`,
a type mismatch on `TUMBLE`) is the expected and correct outcome. `PRV-2020` naming the windowing
function is also acceptable. **Planning successfully is the failure**, because `WindowAssign` would
then read the column with `getLong`, get the right number by accident, and produce windows over a
column nothing declared as time — a correct-looking answer that no watermark arms.

## TYPE-062 — window_start and window_end are TIMESTAMP and NOT NULL
**Intent:** the boundary columns are synthesised, not user-declared, so their type and nullability
are decided in one line of `appendBoundaries` and are never checked by anything the user writes.
Downstream, `BinaryRowWriter.setNull` **throws** for a NOT NULL field, so a boundary that is ever
null is a crash rather than an empty cell.
**Falsifier:** either column reported as nullable; either reported as anything but a
nanosecond-precision timestamp; `window_end − window_start` differing from the declared size.
**Setup:** fixture `S` with `win.csv` as TYPE-060.
**Steps:**
1. `pravaha validate --stream win --schema "<win schema>" --sql "<the TYPE-060 SQL>"` and read the
   printed output column types
2. register, then `SELECT window_end FROM wv`
3. register a variant selecting `TUMBLE_START` as well, and compute the difference
**Expected:** step 1 prints `window_end` as a timestamp type, not nullable. Step 2 returns
**1700000010000000000** — the exclusive end of the first ten-second window,
1700000000000000000 + 10 × 1 000 000 000 = 1700000010000000000. Step 3: `window_end − window_start`
= 10000000000 ns exactly, for every emitted row.
**Vacuity:** the value 1700000010000000000 is not present in any input row; it can only have been
computed by the window assigner. A case asserting merely "a timestamp appears" would pass on a
copied event time.

## TYPE-063 — the window boundaries on the wire: the zoned-vector regression
**Intent:** `ArrowSchemas` declares `Timestamp(NANOSECOND, "UTC")`, whose Arrow vector is
`TimeStampNanoTZVector`. The code once cast it to the *unzoned* vector, and the cast never fired
because no query had ever put a timestamp on the wire — one bug hid the other. This is the
regression case, and it is the only place `window_end` meets a client.
**Falsifier:** a `ClassCastException` mentioning `TimeStampNanoVector` or `TimeStampNanoTZVector` at
serialisation; a timestamp arriving at the client with the wrong magnitude (microseconds or
milliseconds instead of nanoseconds); a timestamp arriving with no timezone on the field.
**Setup:** fixture `S` with the TYPE-060 registration `wv`, and a Flight client (the Python SDK is
sufficient: `client.query("SELECT * FROM wv")`).
**Steps:**
1. `pravaha query --sql "SELECT * FROM wv"` through the CLI
2. the same through the Flight SDK, and print the Arrow schema of the result
3. `pravaha subscribe --view wv`, then deliver a row that closes a second window
**Expected:** step 1 returns the two rows of TYPE-060. Step 2's Arrow schema reports `window_end` as
`timestamp[ns, tz=UTC]`, and the value is **1700000010000000000** nanoseconds — in pandas,
`2023-11-14 22:13:30+00:00`. Step 3 delivers the same value on the streaming path.
**Vacuity:** the case is meaningless unless a window actually closes, which is why it is built on
TYPE-060 rather than on a view of raw rows. Assert two rows on the wire, not "no exception".

## TYPE-064 — a STRING, BOOLEAN or FLOAT column named as the window's time column
**Intent:** complete the window row of the grid for the types that are plainly not time. The refusal
must arrive at registration; `WindowAssign`'s unconditional `getLong` means anything that gets
through reads eight bytes of whatever is there.
**Falsifier:** any of the three planning.
**Setup:** fixture `S` with `winx` — schema `id:INT64,u:STRING,amt:INT64,s:STRING,b:BOOLEAN,f:FLOAT64,ts:TIMESTAMP`.
**Steps:** register the TYPE-060 query three times with `TUMBLE(s, …)`, `TUMBLE(b, …)` and
`TUMBLE(f, …)`; then the `TABLE(TUMBLE(TABLE winx, DESCRIPTOR(s), INTERVAL '10' SECOND))` spelling,
which reaches `descriptorOrdinal` — a **name match with no type check at all**.
**Expected:** the three `GROUP BY TUMBLE(col, …)` forms are refused by the validator with
`PRV-2002`. The `DESCRIPTOR` spelling is the one to watch: `descriptorOrdinal` resolves the name
case-insensitively against the schema and returns an ordinal **without consulting the type**, so if
Calcite's own validation does not stop it first, a `STRING` column becomes the event-time ordinal
and `WindowAssign` reads its (offset, length) slot as a nanosecond instant. Record exactly which
layer refuses it. If nothing does, this is a wrong-answer defect, not a missing feature.

---

## 8. ORDER — refused, and verified rather than assumed (TYPE-065 … TYPE-067)

`SQL_SUPPORT.md` lists `ORDER BY` and `LIMIT`/`OFFSET` as ❌ with `PRV-2020`, and explains why: a
total order over rows that have not all arrived is not a thing. The brief asks for this to be
*verified*, because a documented refusal that does not happen is worse than an undocumented one.

## TYPE-065 — ORDER BY is refused, on every type, with PRV-2020
**Intent:** verify the refusal exists, carries the documented code, and does not depend on the
column's type — a refusal implemented in the type switch rather than in the operator would let some
types through.
**Falsifier:** `ORDER BY` planning for any type; a code other than `PRV-2020`; a refusal that
arrives at run time.
**Setup:** `types.csv` and the CLI — no server needed, since this is a plan-time question.
**Steps:** `pravaha validate --stream types --schema "<types schema>" --sql "SELECT id FROM types
ORDER BY <col>"` for each of the ten declarable columns `id b i8 i16 i32 i64 f32 f64 s bin ts`;
then `ORDER BY id DESC`, `ORDER BY 1`, `ORDER BY s, id`, and `ORDER BY id` inside a derived table
(`SELECT * FROM (SELECT id FROM types ORDER BY id) x`).
**Expected:** all fifteen exit 1 with `PRV-2020`, and the message names the relational operator
(`Sort`) rather than the column. Assert the code on every one; a single type that plans is the
finding. The derived-table form is the one most likely to slip through, because Calcite may push the
`Sort` somewhere the builder does not look.
**Vacuity:** `pravaha validate` reads no rows, so "refused" cannot be confused with "no data".

## TYPE-066 — ORDER BY over a bounded read of a view
**Intent:** `SQL_SUPPORT.md` says ordering "is meaningful over a *bounded* read of a maintained
view, and that is where it would land if it is added". Check whether it has landed, and check the
weaker promise the same document makes: *"Row order is stable between identical reads."*
**Falsifier:** `ORDER BY` accepted over a view but producing an order that is not the one asked for
(worse than refusing); or two identical reads of the same unchanged view returning rows in different
orders.
**Setup:** fixture `S`, view `tv` (5 rows), quiesced.
**Steps:**
1. `pravaha query --sql "SELECT id FROM tv ORDER BY id"`
2. `pravaha query --sql "SELECT id FROM tv"` ten times in a row, with no writes in between
3. `pravaha query --sql "SELECT s, COUNT(*) FROM tv GROUP BY s ORDER BY 2 DESC"`
**Expected:** step 1 is refused with `PRV-2020` — the feature has not landed, and the message should
not claim it has. Step 2 must return the **same order all ten times**; record the order. Step 3 is
refused for the same reason. If step 1 succeeds, assert the order is genuinely ascending `1,2,3,4,5`
and reclassify the case as a feature check.
**Vacuity:** step 2 over five rows could pass by luck if the order were random but stable within a
process. Repeat it once after a node restart; the documented promise is between identical reads and
the restart tests whether it survives one.

## TYPE-067 — LIMIT, OFFSET, and the constructs that reach the same refusal
**Intent:** the neighbours of `ORDER BY` in the same documented table, which share a code path and
are individually unverified.
**Falsifier:** any of them planning; or a refusal carrying `PRV-2021` where the document promises
`PRV-2020` (they mean different things: an operator versus an expression).
**Setup:** `types.csv`, CLI.
**Steps:** `pravaha validate` on: `SELECT id FROM types LIMIT 3`; `… OFFSET 2`;
`… LIMIT 3 OFFSET 2`; `SELECT id FROM types UNION SELECT id FROM types`;
`… UNION ALL …`; `… INTERSECT …`; `… EXCEPT …`; `VALUES (1)`;
`SELECT ROW_NUMBER() OVER (ORDER BY id) FROM types`;
`SELECT id FROM types WHERE id IN (SELECT id FROM types)`.
**Expected:** ten refusals. Per `SQL_SUPPORT.md`: `LIMIT`, `OFFSET`, the four set operators and
`VALUES` carry **`PRV-2020`**; `ROW_NUMBER() OVER` and the `IN (subquery)` form carry **`PRV-2021`**.
Assert the exact code for each — this case is the only place the document's two-column promise is
checked against the build.

---

## 9. Wire serialisation to a client (TYPE-068 … TYPE-079)

Two switches in series, and they do not agree. `ServedView.value` turns a `RowView` field into a
Java object and ends `default -> row.getString(ordinal)`; `ArrowSchemas.write` then puts that object
into a typed Arrow vector and ends `default -> ((VarCharVector) vector).setSafe(…)`. A type handled
by neither default correctly — `BYTES` is the clearest — is a `ClassCastException` at serialisation.
`ArrowSchemas.toArrow` refuses `DECIMAL`, `ARRAY`, `MAP` and `ROW` outright with
`FlightErrors.UNSUPPORTED_TYPE`.

All of these use fixture `S` with view `tv`, read over Flight. `pravaha query` is the CLI's Flight
client; the Python SDK is used wherever the Arrow *schema* itself has to be inspected.

## TYPE-068 — BOOLEAN on the wire
**Intent:** `Bool` → `BitVector`, and the NULL row must arrive as a null, not as `false`.
**Falsifier:** `b` arriving as `0`/`1` integers, as the strings `"true"`/`"false"`, or with the NULL
row rendered `false`.
**Setup:** fixture `S`, view `tv`.
**Steps:** `client.query("SELECT id, b FROM tv")` through the Python SDK; print the Arrow schema and
the five values with their null mask.
**Expected:** field `b` has Arrow type `bool`, nullable. Values `True, False, None, True, False` in
id order. The null mask has exactly one bit clear, at id 3.
**Vacuity:** five rows asserted, with the null mask checked separately from the values. A client
library that renders `None` as `False` would pass a values-only assertion.

## TYPE-069 — INT8, INT16, INT32 and INT64 on the wire
**Intent:** four distinct Arrow widths from one switch. Each extreme is chosen so that a value
serialised at the wrong width is visibly wrong rather than plausibly wrong.
**Falsifier:** `i8 = 127` arriving as `-1` (unsigned/signed confusion); `i32 = 2147483647` arriving
as `-1`; `i64 = 9223372036854775807` arriving as `9.223372036854776e18` (a float in disguise);
any of the four declared with the wrong bit width in the Arrow schema.
**Setup:** fixture `S`, view `tv`.
**Steps:** `client.query("SELECT id, i8, i16, i32, i64 FROM tv")`; print the schema and the values.
**Expected:** the schema reports `int8`, `int16`, `int32`, `int64`, each **signed** and nullable —
`ArrowSchemas` builds `new ArrowType.Int(n, true)`, and the `true` is the signedness.
Values in id order:
`i8`: 127, −128, None, 0, 1.
`i16`: 32767, −32768, None, 0, 1.
`i32`: 2147483647, −2147483648, None, 0, 16777217.
`i64`: 9223372036854775807, −9223372036854775808, None, 0, 9007199254740993.
The last is the assertion that nothing on the wire is a double: 2⁵³+1 survives only as an integer.
`ServedView.value` boxes these as `Byte`, `Short`, `Integer`, `Long` and `ArrowSchemas.write`
narrows each with `((Number) value).byteValue()` and friends — so a boxing mistake in the view would
truncate here and show as a wrong value, not as an error.

## TYPE-070 — FLOAT32 and FLOAT64 on the wire
**Intent:** `SINGLE` → `Float4Vector`, `DOUBLE` → `Float8Vector`, via `((Number) value).floatValue()`.
A FLOAT64 routed through `floatValue()` would lose the exponent range silently.
**Falsifier:** `f64 = 1.7976931348623157E308` arriving as `inf` (which is what a `float` narrowing
produces); `f32 = 1.4E-45` arriving as `0.0`; either declared with the wrong precision.
**Setup:** fixture `S`, view `tv`.
**Steps:** `client.query("SELECT id, f32, f64 FROM tv")`; print schema and values with full repr.
**Expected:** schema reports `float` and `double`. Values:
`f32`: 3.4028235e38, 1.4e-45, None, 0.0, 16777216.0.
`f64`: 1.7976931348623157e308, 5e-324, None, 0.0, 9007199254740992.0.
`f64`'s first value is the one that matters: it is `Double.MAX_VALUE`, and it must not be `inf`.
Note `4.9E-324` prints as `5e-324` in Python — the same bit pattern, the minimum subnormal; compare
by `struct.pack` if the text form is ambiguous.

## TYPE-071 — STRING on the wire, including unicode above the BMP
**Intent:** `Utf8` → `VarCharVector`, filled from `String.valueOf(value).getBytes(UTF_8)`. The
double conversion (bytes → `String` in `ServedView`, `String` → bytes in `ArrowSchemas`) must be
lossless for every code point.
**Falsifier:** `straße` arriving as `straÃŸe` or `stra?e`; `👍ok` arriving as two replacement
characters or as a lone surrogate; `  pad  ` arriving trimmed; the NULL rows arriving as `""`.
**Setup:** fixture `S` with a second view `txv` over `text.csv`, and view `tv`.
**Steps:** `client.query("SELECT id, s FROM txv")` and `client.query("SELECT id, s FROM tv")`;
compare each value's **UTF-8 byte length** as well as its text.
**Expected:** from `txv`, eight rows: `hello world` (11 bytes), `  padded  ` (10), None,
`a.com` (5), `straße` (**7** bytes, not 6 — `ß` is two bytes), `👍ok` (**6** bytes, 3 code points,
5 UTF-16 units), `100%` (4), `axcom` (5).
From `tv`: `zed`, `ann`, None, None, `  pad  ` — with rows 3 and 4 both `None`, distinguished only
by the null mask being set for both, which is correct here.

## TYPE-072 — BYTES on the wire: a String is handed to a VarBinaryVector
**Intent:** the live defect this section exists to find. `ServedView.value` has **no `BYTES` case**
and falls to `default -> row.getString(ordinal)`, producing a `String`. `ArrowSchemas.write` has an
explicit `case BYTES -> ((VarBinaryVector) vector).setSafe(index, (byte[]) value)`. A `String` cast
to `byte[]` is a `ClassCastException`, every time, for every non-null BYTES value.
**Falsifier:** the query returning bytes (the defect is fixed — then assert the bytes are
`63 61 66 65` for `cafe`); or failing for a reason other than the cast.
**Setup:** fixture `S`, view `tv`; `bin` is `cafe`, NULL, NULL, `beef`, `ff`.
**Steps:**
1. `pravaha query --sql "SELECT id, bin FROM tv"`
2. `client.query("SELECT id, bin FROM tv")` through the SDK
3. `pravaha query --sql "SELECT id, bin FROM tv WHERE bin IS NULL"` — only the two NULL rows
4. `pravaha query --sql "SELECT id FROM tv"` — the control, no BYTES column
5. `pravaha subscribe --view tv` — the streaming path, same rows
**Expected:** steps 1 and 2 fail with
`java.lang.ClassCastException: class java.lang.String cannot be cast to class [B`, raised inside
`ArrowSchemas.write`. Record how it reaches the client: a Flight `INTERNAL` status, a generic error,
or a dropped connection — and whether the server thread survives.
Step 3 is the diagnostic: with only NULL values the `value == null` branch short-circuits before the
cast, so it is expected to **succeed** and return two rows. A BYTES column that works only while it
is empty is the signature of this defect.
Step 4 must succeed, proving the view and the transport are fine.
Step 5 establishes whether the streaming path shares the fault.
**The answer being denied:** `cafe` → `b'cafe'` (4 bytes `63 61 66 65`), `beef` → `b'beef'`,
`ff` → `b'ff'`.
**Vacuity:** step 4's success is what makes steps 1–2's failure attributable to the type. Without it
a broken server looks the same.

## TYPE-073 — TIMESTAMP_LTZ on the wire
**Intent:** the type whose serialisation was the fourth of the four defects in finding Q-1. It must
go out as nanoseconds, zoned UTC, through `TimeStampNanoTZVector`.
**Falsifier:** a `ClassCastException` naming a timestamp vector; the value divided by 1 000 or
1 000 000; the field arriving with `tz=None`; the two timestamps one nanosecond apart arriving equal.
**Setup:** fixture `S`, view `tv`.
**Steps:** `client.query("SELECT id, ts FROM tv")`; print the Arrow field and the raw integer values
(not the rendered datetimes).
**Expected:** field `ts` is `timestamp[ns, tz=UTC]`, nullable. Raw values:
1700000000000000000, 0, None, 1700000000000000001, −1.
Rows 1 and 4 differ by exactly 1; row 2 is the epoch and is **not** null; row 5 is negative —
1969-12-31T23:59:59.999999999Z — and a client that clamps it to the epoch has lost a nanosecond.
**Vacuity:** the pair (row 1, row 4) cannot both be right unless nanosecond resolution survived the
whole path. A single-timestamp case would pass at millisecond resolution.

## TYPE-074 — TIME on the wire is indistinguishable from TIMESTAMP
**Intent:** `ArrowSchemas.arrowTypeOf` maps `TIME` and `TIMESTAMP_LTZ` to the **same** Arrow type,
`Timestamp(NANOSECOND, "UTC")`. A `TIME` value is nanoseconds since *midnight*; a client reading it
as a timestamp sees 1970-01-01 plus that offset. The mapping is not a rounding choice, it is a
category error, and it is currently unreachable only because TIME cannot be declared (TYPE-004).
**Falsifier:** `TIME` mapped to an Arrow `Time` type (fixed — record it); or a `TIME` column that
serialises with a different magnitude from the same number held as a timestamp.
**Setup:** a programmatic schema with `t TIME` holding 3 600 000 000 000 (01:00:00) and
`ts TIMESTAMP_LTZ` holding the same number, exposed as a view over the embedded server.
**Steps:** query both columns; print the Arrow schema and the rendered values.
**Expected:** both fields report `timestamp[ns, tz=UTC]` and both render as
`1970-01-01 01:00:00+00:00`. The two columns are **identical on the wire** despite being different
types in the engine. Record it as a wire-contract defect: a client cannot recover a time of day, and
the correct mapping is `Time(NANOSECOND, 64)`.

## TYPE-075 — DATE on the wire
**Intent:** `Date(DateUnit.DAY)` → `DateDayVector`, written from `((Number) value).intValue()` —
correct, and unreachable from configuration (TYPE-003). Establish the mapping is right so that
making DATE declarable does not need this checked again.
**Falsifier:** DATE arriving as a timestamp; as milliseconds; as a string.
**Setup:** the programmatic schema of TYPE-019, `d` = 19723.
**Steps:** query `SELECT id, d FROM n`; print the Arrow field and the value.
**Expected:** field `d` is `date32[day]`, nullable; the value renders as `2024-01-01` and the raw
integer is **19723**. Check the arithmetic: 1970-01-01 to 2024-01-01 is 54 years with 13 leap days
(1972, 76, 80, 84, 88, 92, 96, 2000, 04, 08, 12, 16, 20) → 54 × 365 + 13 = 19710 + 13 = **19723**.

## TYPE-076 — DECIMAL on the wire is refused, and refused before anything is read
**Intent:** `ArrowSchemas.arrowTypeOf` throws `FlightErrors.UNSUPPORTED_TYPE` for DECIMAL, with a
message that explains the decision. The refusal must come from `toArrow` — i.e. when the *schema* is
built, before a row is touched — and must not be reachable only after the first row.
**Falsifier:** a DECIMAL column arriving as a float or a string; a refusal arriving per row rather
than per query; a refusal with no message.
**Setup:** the programmatic DECIMAL column of TYPE-019, exposed as a view.
**Steps:** `getFlightInfo` for `SELECT id, amt FROM n` (schema only, no data); then `getStream`;
then `SELECT id FROM n` as the control.
**Expected:** `getFlightInfo` already fails with `UNSUPPORTED_TYPE` and
`DECIMAL is not something Pravaha puts on the wire yet. DECIMAL in particular is refused rather than
sent as a float, because the rounding decision belongs to whoever owns the ledger and not to a
serialiser.` `SELECT id FROM n` succeeds.
**And the second failure hiding behind the first:** if `toArrow` is ever fixed, `ServedView.value`
still has no DECIMAL case and falls to `row.getString(ordinal)`, which reads the **16-byte decimal
slot as an (offset, length) pair**. The first eight bytes of `123.45` at scale 2 are the high word
`0`, so the offset reads as 0 and the length as 0 — an empty string — but any decimal with a
non-zero high word produces an arbitrary length and either a bounds failure from `MemoryRegion`, a
`NegativeArraySizeException`, or a multi-gigabyte allocation. **Test it directly**: write
`setDecimal(1, 1L, 0L)` (high = 1) and call `ServedView`'s value path through the REST read, which
does not go through `toArrow`. Record what happens. This is the most dangerous single line in the
type system and it is currently masked by a refusal one layer up.

## TYPE-077 — ARRAY, MAP and ROW on the wire are refused
**Intent:** complete the wire row of the grid. These three never reach `ArrowSchemas` under normal
operation because `TypeMapping` fails first (TYPE-020), so the case establishes which layer refuses
and whether the message is the useful one.
**Falsifier:** any of the three serialised; a refusal that names neither the type nor the column.
**Setup:** the programmatic nested schema of TYPE-020, exposed as a view.
**Steps:** `getFlightInfo` for `SELECT id, arr FROM n`, then `m`, then `r`; then `SELECT * FROM n`;
then `SELECT id FROM n` as the control.
**Expected:** each fails. Record **which** failure arrives: `TypeMapping.baseFromCalcite`'s bare
`IllegalArgumentException` (from planning), or `ArrowSchemas`' `UNSUPPORTED_TYPE`. The latter is the
honest one — it names the type and says it is not on the wire *yet*. The former names a class.
`SELECT id FROM n` succeeds.

## TYPE-078 — NULL of every type on the wire
**Intent:** `ArrowSchemas.write` short-circuits on `value == null` before the type switch, so NULL is
the one value whose path is type-independent — and therefore the one place a null-bitmap fault would
show identically for all types. Every field is declared `FieldType.nullable(...)` regardless of the
column's declared nullability, which is a deliberate choice worth pinning.
**Falsifier:** a NOT NULL column arriving as non-nullable in the Arrow schema (which would make a
future nullable value unrepresentable); a NULL arriving as a type default (0, `false`, `""`).
**Setup:** fixture `S`, view `tv`. Row 3 is NULL in all ten nullable columns; `id` is NOT NULL.
**Steps:** `client.query("SELECT * FROM tv")`; inspect the Arrow schema's nullability for every
field, and row 3's null mask for every column.
**Expected:** **every** field in the Arrow schema is nullable, `id` included — `toArrow` calls
`FieldType.nullable(...)` unconditionally. Row 3 has the null bit set for `b i8 i16 i32 i64 f32 f64
s bin ts` — ten nulls — and `id = 3` present. No column renders a type default.
Note the consequence and record it: the Arrow schema **cannot express** that `id` is NOT NULL, so a
client cannot distinguish a column that may be null from one that may not. That is a lossy contract,
not a defect, but it is undocumented.
**Vacuity:** ten null bits in one row, asserted individually. A single-column null check would pass
on nine broken columns.

## TYPE-079 — the parameter schema's types
**Intent:** `ArrowSchemas.parameterSchema` runs the same `arrowTypeOf` over a prepared statement's
placeholders, names them `param_1` upward, and declares them all nullable. A placeholder whose
column type is DECIMAL, ARRAY, MAP or ROW therefore refuses **at prepare time**, which is a different
moment from every other case in this section.
**Falsifier:** placeholders numbered from zero; a placeholder typed from the wrong column; a DECIMAL
placeholder accepted and then failing at bind.
**Setup:** fixture `S`, view `tv`, and the programmatic view `n` of TYPE-019.
**Steps:**
1. prepare `SELECT id FROM tv WHERE s = ? AND i64 > ?` and read the parameter schema
2. prepare `SELECT id FROM tv WHERE b = ?`, `… ts > ?`, `… bin = ?`
3. prepare `SELECT id FROM n WHERE amt = ?`
4. bind NULL to `?1` in step 1's statement and execute
**Expected:** step 1 reports two fields, `param_1` of type `utf8` and `param_2` of type `int64`,
both nullable. Step 2: `b` → `bool`; `ts` → `timestamp[ns, tz=UTC]`; `bin` — `bin` is BYTES, which
`arrowTypeOf` maps to `binary`, so the *schema* succeeds while `PredicateCompiler.compare` refuses
BYTES with `PRV-2021` (TYPE-031); record which error the client sees and at which call.
Step 3 refuses with `UNSUPPORTED_TYPE` naming DECIMAL, at **prepare**, not at execute.
Step 4: binding NULL makes the predicate `Predicate.False` by design (ADR-032's stated position),
so the result is **0 rows** — not an error, and not the rows where `s IS NULL`. Assert zero rows and
pair it with `WHERE s IS NULL`, which must return **2** rows (ids 3 and 4); the two differing is the
whole point of that design decision.

---

## 10. NULL and three-valued logic (TYPE-080 … TYPE-087)

The predicate IR is deliberately **two-valued**: every comparison returns `false` for a null
operand, and `PredicateCompiler.negate` pushes `NOT` into the comparisons at compile time so that a
Java `!` never turns a row SQL dropped into a row that passes. `Predicate.Not` exists but the SQL
compiler never emits it. These cases test that the compile-time reasoning is right, because it is
right once per query and wrong for every row.

## TYPE-080 — NULL in a projection is distinguishable from every type's zero value
**Intent:** the null bitmap versus the type default. For nine of the ten declarable types there is a
value that renders identically to NULL in a CSV, or nearly so, and only `IS NULL` can tell them
apart.
**Falsifier:** any column where `IS NULL` and the rendered output disagree; row 4's `s` (a NULL) and
a genuinely empty string behaving identically under `IS NULL`.
**Setup:** `types.csv` plus `empt.csv` — schema `id:INT64,s:STRING?` with `null.literal` left at the
default — containing `1,` and, as the contrast, a row written through the SDK with `setString(1,
"")` so an empty string exists that the codec cannot produce.
**Steps:**
1. `SELECT id FROM types WHERE <col> IS NULL` for each of `b i8 i16 i32 i64 f32 f64 s bin ts`
2. `SELECT id FROM types WHERE <col> IS NOT NULL` for the same ten
3. the empty-string contrast: `SELECT id FROM e WHERE s IS NULL` and `WHERE s = ''`
**Expected:** step 1 returns `3` for `b i8 i16 i32 i64 f32 f64 ts`; `3,4` for `s`; `2,3` for `bin`.
Step 2 returns the complements: `1,2,4,5` for the eight; `1,2,5` for `s`; `1,4,5` for `bin`.
Every pair sums to 5.
Step 3: the file-sourced row is NULL (`IS NULL` matches, `s = ''` does not, because the comparison
short-circuits on the null bit); the SDK-written row is an empty string (`IS NULL` does not match,
`s = ''` does). **The two are different rows with the same rendering**, and a build in which they
behave identically has lost the distinction the null bitmap exists for.
**Vacuity:** ten complementary pairs summing to 5 each. A build that returned everything for
`IS NOT NULL` and nothing for `IS NULL` would fail twenty assertions, not one.

## TYPE-081 — NULL propagates through arithmetic, for every operator and every numeric type
**Intent:** `Expression.Arithmetic.isNull` returns true if either side is null, and the compute stage
checks `expression.isNull(row)` **before** evaluating. A zero written here becomes a plausible
number in a downstream `SUM`.
**Falsifier:** `a + 1` over a NULL `a` producing `1`; `a * 0` producing `0`; `a / b` over a NULL `a`
and a zero `b` throwing a division-by-zero (which would mean `isNull` is checked after evaluation).
**Setup:** `num.csv` row 6 (`a` NULL, `b` = 5, `x` NULL, `y` = 5.0, `r` NULL).
**Steps:** `SELECT id, a+b, a-b, a*b, a/b, a%b FROM num`, then the same with `x` and `y`, then
`SELECT id, a*0 AS z FROM num`, then `SELECT id, b/a FROM num` (NULL as the **divisor**).
**Expected:** row 6 is NULL in all five integer results, all five floating results, in `a*0`, and in
`b/a`. Assert the CSV field is empty, not `0`. The other rows are unaffected: TYPE-102 and TYPE-103
hold their values.
The `b/a` case is the important one — `a` is NULL and would evaluate to 0, so an implementation that
evaluated before checking would divide 5 by 0 and throw. **Zero exceptions is part of the expected
result.**

## TYPE-082 — WHERE NOT (col > k) drops the NULL row
**Intent:** SQL says `NOT UNKNOWN` is `UNKNOWN` and a `WHERE` keeps only `TRUE`. `PredicateCompiler.
negate` implements this by flipping the operator at compile time rather than negating at run time.
Every one of the six operators has its own entry in `Op.negated()`.
**Falsifier:** the NULL row appearing under any `NOT`; `col > k` and `NOT (col > k)` together
returning every row.
**Setup:** `types.csv`; `i64` is NULL only at id 3.
**Steps:** for each of the six operators, run `WHERE i64 <op> 0` and `WHERE NOT (i64 <op> 0)`;
then `WHERE NOT NOT (i64 > 0)`; then `WHERE NOT (i64 IS NULL)`.
**Expected:**
| predicate | rows | `NOT` of it | rows |
|---|---|---|---|
| `i64 = 0` | `4` | `i64 <> 0` | `1,2,5` |
| `i64 <> 0` | `1,2,5` | `i64 = 0` | `4` |
| `i64 < 0` | `2` | `i64 >= 0` | `1,4,5` |
| `i64 <= 0` | `2,4` | `i64 > 0` | `1,5` |
| `i64 > 0` | `1,5` | `i64 <= 0` | `2,4` |
| `i64 >= 0` | `1,4,5` | `i64 < 0` | `2` |

In all six pairs the two sides sum to **4**, not 5: id 3 is in neither. `NOT NOT (i64 > 0)` returns
`1,5`, identical to `i64 > 0`. `NOT (i64 IS NULL)` returns `1,2,4,5` — a null check *is* total, so
its negation is the other one and the row is not lost twice.
**Vacuity:** the six sums of 4 are the assertion. A two-valued `!` would make every pair sum to 5,
and each individual result would still look reasonable.

## TYPE-083 — `= NULL` is not `IS NULL`, and `<> NULL` is not `IS NOT NULL`
**Intent:** `PredicateCompiler.compare` returns `Predicate.False` for a null constant, for every
operator and every type. That is the standard, it is what ADR-032 commits to for bound parameters,
and it is the behaviour most likely to be "helpfully" changed by someone who has not read this.
**Falsifier:** `WHERE s = NULL` returning the rows where `s` is null; `WHERE s <> NULL` returning
anything at all.
**Setup:** `types.csv`, and fixture `S` for the bound-parameter half.
**Steps:** `WHERE s = NULL`, `WHERE s <> NULL`, `WHERE i64 = NULL`, `WHERE i64 > NULL`,
`WHERE b = NULL`, `WHERE ts = NULL`; then `WHERE s IS NULL` as the contrast; then through the
server, `pravaha query --sql "SELECT id FROM tv WHERE s = ?"` with a NULL bound.
**Expected:** all six `NULL`-literal forms return **0 rows**. `WHERE s IS NULL` returns `3,4`. The
bound-NULL form returns **0 rows**, matching the literal form exactly — that equivalence is what
ADR-032 promises and this is where it is checked.
**Vacuity:** six zero-row results are individually vacuous; `s IS NULL` returning two rows from the
same file in the same run is what makes them meaningful.

## TYPE-084 — a NULL boolean is in neither `WHERE b` nor `WHERE NOT b`
**Intent:** the sharpest statement of two-valued-from-three-valued. Both spellings compile to
`CompareBoolean`, which begins `!row.isNull(ordinal) && …`, so the NULL row is excluded twice.
**Falsifier:** the two results together covering all five rows.
**Setup:** `types.csv`; `b` = true, false, NULL, true, false.
**Steps:** `WHERE b`, `WHERE NOT b`, `WHERE b OR NOT b`, `WHERE b IS NULL`.
**Expected:** `WHERE b` → `1,4`. `WHERE NOT b` → `2,5`. **`WHERE b OR NOT b` → `1,2,4,5` — four
rows, not five.** That is SQL-correct and is the single most counter-intuitive result in this file;
assert it explicitly so nobody "fixes" it. `WHERE b IS NULL` → `3`, and 4 + 1 = 5.
**Vacuity:** the tautology `b OR NOT b` returning fewer than every row is impossible in a
two-valued logic, so this case cannot pass with three-valued handling removed.

## TYPE-085 — LIKE and NOT LIKE both drop the NULL row
**Intent:** `Predicate.Like.test` returns `false` on a null column *before* applying `negated`, so
the row is dropped by both forms. A Java `!` around the match would keep it under `NOT LIKE`.
**Falsifier:** the NULL row appearing under `NOT LIKE`.
**Setup:** `text.csv`; `s` is NULL at id 3.
**Steps:** `WHERE s LIKE 'a%'`, `WHERE s NOT LIKE 'a%'`, `WHERE NOT (s LIKE 'a%')`,
`WHERE s LIKE '%'`, `WHERE s NOT LIKE '%'`, `WHERE s IS NULL`.
**Expected:** `LIKE 'a%'` → `4,8` (`a.com`, `axcom`). `NOT LIKE 'a%'` → `1,2,5,6,7` — five rows.
`NOT (s LIKE 'a%')` must return the **same five**, because Calcite's `NOT` over `LIKE` routes through
`negate`'s `case LIKE -> like(call, true)` and compiles to the same node. 2 + 5 = 7 = 8 − 1 NULL.
`LIKE '%'` → `1,2,4,5,6,7,8` — seven rows; the pattern becomes the regex `.*`, which matches every
string including an empty one, but **not** a null. `NOT LIKE '%'` → **0 rows**. `s IS NULL` → `3`.
**Vacuity:** `LIKE '%'` returning seven and `NOT LIKE '%'` returning zero, over eight rows, is only
possible if the null row is excluded from both.

## TYPE-086 — `||` with a NULL part is NULL, not an empty string
**Intent:** `Concat.isNull` scans every part. This is the SQL rule and it is the reason
`first || ' ' || last` produces NULL rather than a name with a trailing space — stated in the
javadoc, tested here.
**Falsifier:** `s || '!'` over a NULL `s` producing `!`; producing an empty field that is not
actually NULL (check with `IS NULL`, not by eye).
**Setup:** `text.csv`; `s` NULL at id 3, `t` non-null everywhere.
**Steps:**
1. `SELECT id, s || '!' AS c FROM text`, `--out-schema "id:INT64,c:STRING?"`
2. `SELECT id, s || '-' || t AS c FROM text`
3. `SELECT id FROM text WHERE s || '!' IS NULL` — proves it is NULL and not empty
4. `SELECT id, CASE WHEN s IS NULL THEN t ELSE s || '-' || t END AS c FROM text` — the documented
   workaround
**Expected:** step 1: `hello world!`, `  padded  !`, **NULL**, `a.com!`, `straße!`, `👍ok!`,
`100%!`, `axcom!`. Step 2 is NULL at id 3 and `hello world-HELLO`, `  padded  -x`, `a.com-z`,
`straße-ß`, `👍ok-e`, `100%-%`, `axcom-q` elsewhere — note the three-part form flattens to one
`Concat` node, so the NULL check is over all three parts, not two nested pairs.
Step 3 returns exactly `3`. Step 4 returns `y` at id 3 and the concatenated value elsewhere,
demonstrating the documented escape hatch works.
**Vacuity:** step 3 is the anti-vacuity device. A CSV field that is empty could be an empty string;
`IS NULL` returning that row is what proves it is not.

## TYPE-087 — AND and OR with an UNKNOWN operand
**Intent:** `Predicate.And` and `Predicate.Or` are plain boolean loops over two-valued parts, so
SQL's `UNKNOWN AND FALSE = FALSE` and `UNKNOWN OR TRUE = TRUE` fall out only if the compile-time
flattening was right. `negate` also maps `NOT (A AND B)` to `Or(negate A, negate B)`, which is
De Morgan — correct in two-valued logic and correct here **only** because each negated part is
already false-for-null.
**Falsifier:** `WHERE i64 > 0 OR id = 3` not returning id 3; `WHERE NOT (i64 > 0 AND id = 1)`
returning id 3.
**Setup:** `types.csv`; `i64` NULL at id 3 only.
**Steps:**
1. `WHERE i64 > 0 AND id = 3`
2. `WHERE i64 > 0 OR id = 3`
3. `WHERE NOT (i64 > 0 AND id = 1)`
4. `WHERE NOT (i64 > 0 OR id = 1)`
5. `WHERE (i64 > 0 AND id = 1) OR (i64 IS NULL)`
**Expected:**
1. → **0 rows**. For id 3 the left is UNKNOWN and the right is TRUE; `UNKNOWN AND TRUE` is UNKNOWN,
   which a `WHERE` drops. For every other id the right is FALSE.
2. → `1,3,5`. Id 3 is kept because `UNKNOWN OR TRUE` is TRUE — and the implementation gets it right
   by short-circuiting on the true part, which is the same answer for the right reason.
3. → `2,3,4,5` — **four rows**. De Morgan gives `i64 <= 0 OR id <> 1`. Id 1: `false OR false` →
   dropped. Id 2: `true OR true` → kept. Id 3: `i64 <= 0` is false (the column is null) and
   `id <> 1` is true → kept. Id 4: `0 <= 0` true → kept. Id 5: `i64 <= 0` false, `id <> 1` true →
   kept. SQL agrees: for id 3, `i64 > 0` is UNKNOWN, `UNKNOWN AND FALSE` is FALSE, and `NOT FALSE`
   is TRUE. The compile-time De Morgan and the standard reach the same four rows.
4. → `2,4`. `NOT (A OR B)` is `A' AND B'` = `i64 <= 0 AND id <> 1`. Id 2: true AND true → kept.
   Id 3: `i64 <= 0` is false (null) → dropped. Id 4: `0 <= 0` true, `4 <> 1` true → kept.
   Id 5: `i64 <= 0` false → dropped. **Two rows.** SQL agrees: for id 3 the OR is
   `UNKNOWN OR FALSE` = UNKNOWN, and `NOT UNKNOWN` = UNKNOWN → dropped.
5. → `1,3`. Id 1 satisfies the left conjunction; id 3 satisfies `IS NULL`.
**Vacuity:** cases 3 and 4 differ by exactly id 3 and id 5, which is only possible if UNKNOWN is
handled differently under `AND` and under `OR`. A build that treated null as false everywhere gives
3 → `2,3,4,5` (the same, by luck) and 4 → `2,3,4` (wrong), so case 4 is the one that discriminates.

---

## 11. Minimum and maximum values (TYPE-088 … TYPE-093)

The extremes of each type's domain, through the whole path: decode, layout, predicate, projection,
encode. Where a type has a subnormal or a signed asymmetry, that is the value used, because it is
the one a rounding or a sign error destroys.

## TYPE-088 — the four integer widths at both extremes
**Intent:** each width's minimum is one larger in magnitude than its maximum, and every path that
negates, absolutes or narrows can get that wrong in a way that is invisible for every other value.
**Falsifier:** any extreme that does not survive decode → predicate → projection → encode unchanged;
`Byte.parseByte("128")` accepted.
**Setup:** `types.csv` (ids 1 and 2 carry the extremes), plus `over.csv` with the out-of-range
literals `1,128,32768,2147483648,9223372036854775808` at schema
`id:INT64,i8:INT8,i16:INT16,i32:INT32,i64:INT64` and `2,-129,-32769,-2147483649,-9223372036854775809`.
**Steps:**
1. project all four columns from `types.csv` and compare bytes (this is TYPE-001 narrowed)
2. `WHERE i8 = 127`, `= -128`, `WHERE i16 = 32767`, `= -32768`, `WHERE i32 = 2147483647`,
   `= -2147483648`, `WHERE i64 = 9223372036854775807`, `= -9223372036854775808`
3. decode `over.csv`
**Expected:** step 1 returns the eight values unchanged. Step 2 returns `1` for the four maxima and
`2` for the four minima — eight single-row results.
Step 3 must **fail at decode** for every column, with
`line 1, column 'i8' (INT8): '128' is not a number` and the analogous messages — `Byte.parseByte`,
`Short.parseShort`, `Integer.parseInt` and `Long.parseLong` all throw `NumberFormatException` for a
value one past the limit, which `DelimitedCodec` catches and re-reports. The message says "is not a
number", which is **inaccurate for an out-of-range integer** — record that as a diagnostic finding:
`128` is a number, it is not an `INT8`.
**Vacuity:** the same file with `127` in place of `128` must decode, so the failure is attributable
to the range and not to the file.

## TYPE-089 — FLOAT32 at both extremes and at the subnormal boundary
**Intent:** four values that a 32-bit float can just represent, and two it cannot.
**Falsifier:** `3.4028235E38` decoding to `Infinity`; `1.4E-45` decoding to `0.0`; `1.17549435E-38`
(the smallest **normal**) decoding to a subnormal or to zero.
**Setup:** `f32e.csv` — schema `id:INT64,f:FLOAT32?` — with
`1,3.4028235E38` / `2,-3.4028235E38` / `3,1.4E-45` / `4,-1.4E-45` / `5,1.17549435E-38` /
`6,1.0E39` / `7,1.0E-46` / `8,NaN` / `9,Infinity` / `10,-Infinity`.
**Steps:** project `f`; then `WHERE f > 0` and `WHERE f = f` (which is false for NaN).
**Expected:** projected values, by `Float.toString`:
`3.4028235E38`, `-3.4028235E38`, `1.4E-45`, `-1.4E-45`, `1.17549435E-38`, **`Infinity`**,
**`0.0`**, `NaN`, `Infinity`, `-Infinity`.
Rows 6 and 7 are the findings and are covered again in TYPE-099/101: `Float.parseFloat("1.0E39")`
**silently returns `Infinity`** and `Float.parseFloat("1.0E-46")` silently returns `0.0` — neither
throws, so a value 10 orders of magnitude out of range enters the engine as a different value under
a successful decode.
`WHERE f > 0` → `1,3,5,6,9` (five rows: the three positive finites, the overflowed Infinity and the
declared Infinity; NaN is not > 0; the underflowed 0.0 is not > 0).
`WHERE f = f` → every row except id 8 — nine rows, because NaN ≠ NaN. Record whether
`CompareDouble`'s `Double.compare` agrees: `Double.compare(NaN, NaN)` is **0**, so if the predicate
compiles to a comparison against a NaN *literal* it would return TRUE where IEEE says FALSE. Test it:
`WHERE f = CAST('NaN' AS DOUBLE)` if the parser allows, else note it as untestable from SQL.

## TYPE-090 — FLOAT64 at both extremes and at the subnormal boundary
**Intent:** as TYPE-089 for the eight-byte width, where the range is wide enough that a value passed
through a `float` anywhere becomes `Infinity` immediately.
**Falsifier:** `1.7976931348623157E308` returning `Infinity` (the signature of a float round trip);
`4.9E-324` returning `0.0`; `2.2250738585072014E-308` (the smallest normal) returning a subnormal.
**Setup:** `f64e.csv` — schema `id:INT64,f:FLOAT64?` — with
`1,1.7976931348623157E308` / `2,-1.7976931348623157E308` / `3,4.9E-324` / `4,-4.9E-324` /
`5,2.2250738585072014E-308` / `6,1.0E309` / `7,1.0E-324` / `8,NaN` / `9,Infinity` / `10,-Infinity`.
**Steps:** project `f`; `WHERE f > 0`; `WHERE f < 1.0E308`.
**Expected:** projected, by `Double.toString`: `1.7976931348623157E308`,
`-1.7976931348623157E308`, `4.9E-324`, `-4.9E-324`, `2.2250738585072014E-308`, **`Infinity`**,
**`0.0`**, `NaN`, `Infinity`, `-Infinity`. Rows 6 and 7 overflow and underflow silently at
`Double.parseDouble`, as in TYPE-089.
`WHERE f > 0` → `1,3,5,6,9`. `WHERE f < 1.0E308` → `2,3,4,5,7,10` — six rows: the two negatives,
both subnormals, the underflowed zero and −Infinity. Id 1 (1.797…E308) is **not** less than 1.0E308.

## TYPE-091 — TIMESTAMP_LTZ range: the epoch, negative instants, and the year 2262 ceiling
**Intent:** `TypeName.TIMESTAMP_LTZ`'s own javadoc says "range to year 2262, which is adequate and
half the cost of 96 bits". That is a documented limit nobody has tested, and the failure past it is
a silent wrap in a column people will use for event time.
**Falsifier:** `Long.MAX_VALUE` nanoseconds rendering as a date before 1970; the epoch treated as
unset; a negative instant clamped to zero.
**Setup:** `tse.csv` — schema `id:INT64,ts:TIMESTAMP?` — with
`1,0` / `2,-1` / `3,9223372036854775807` / `4,-9223372036854775808` / `5,1700000000000000000` /
`6,253402300799000000000` (9999-12-31T23:59:59Z in nanoseconds).
**Steps:** project `ts`; `WHERE ts > 0`; `WHERE ts < 0`; then read the same values through the
Flight wire (TYPE-073's vehicle) and record the rendered datetimes.
**Expected:** row 6 **fails at decode**: 253402300799000000000 is about 2.5 × 10²⁰ and
`Long.parseLong` throws, reported as `line 6, column 'ts' (TIMESTAMP_LTZ): '253402300799000000000'
is not a number`. That is the 2262 ceiling in practice, and the message does not mention it.
Rows 1–5 project unchanged. `WHERE ts > 0` → `3,5`. `WHERE ts < 0` → `2,4`. 2 + 2 + 1 (id 1, which
is neither) = 5.
On the wire: `9223372036854775807` ns is **2262-04-11T23:47:16.854775807Z**
(9223372036854775807 / 1 000 000 000 = 9223372036 seconds; 9223372036 / 31 556 952 ≈ 292.27 years
after 1970). `-9223372036854775808` ns is 1677-09-21T00:12:43.145224192Z. Assert both render as
those dates and not as an error or a wrapped value.

## TYPE-092 — STRING and BYTES at the empty and long extremes
**Intent:** the variable-width types have no numeric range, but they do have a length, and the
length is an `int` in a slot alongside an `int` offset. Establish the empty case and a length large
enough to exercise the arena and the 512-byte payload reservation in the CLI's collector.
**Falsifier:** an empty string treated as NULL; a string longer than the collector's
`layout.rowSize(512)` reservation silently truncated rather than growing or failing loudly.
**Setup:** `len.csv` — schema `id:INT64,s:STRING?,bin:BYTES?` — with
`1,,` (both NULL) / `2,a,a` / a row 3 whose `s` is 511 `x` characters / a row 4 whose `s` is 1024
`x` characters / a row 5 whose `s` is 65536 `x` characters. Plus an SDK-written row with
`setString(1, "")` for the genuine empty string, as in TYPE-080.
**Steps:** project `s`; `SELECT id, CHAR_LENGTH(s)` if supported, otherwise assert the output field's
byte length externally; `WHERE s IS NULL`.
**Expected:** row 1 is NULL (`IS NULL` matches). Rows 2–5 project their exact content, with output
byte lengths 1, 511, 1024 and 65536. The 1024 and 65536 rows are the assertions: the CLI's
`Collector.begin` allocates `layout.rowSize(512)` and the writer appends the payload past that
reservation, so either the arena grows, or the write fails with a bounds error, or — the outcome to
look for — it **silently writes past the reservation into the next row's space**. Record which.
A truncated 65536-byte string, or a corrupted neighbouring row, is a blocker.
**Vacuity:** the 1-byte and 511-byte rows must pass in the same run, so a failure at 1024 is
attributable to the length and not to the fixture.

## TYPE-093 — the physical row layout for the fixture schema, computed by hand
**Intent:** `RowLayout` decides every offset once per schema, and every read in the engine trusts it.
The arithmetic is small enough to do by hand and is the foundation under every alignment hazard
named elsewhere in this file (the eight-byte read of a four-byte `FLOAT32` in TYPE-026, of a
four-byte `INT32` in TYPE-034).
**Falsifier:** any offset differing from the hand-computed one; `fixedEnd` not a multiple of 8; two
fields overlapping.
**Setup:** schema `L` = `id:INT64,b:BOOLEAN,i8:INT8,i16:INT16,i32:INT32,f32:FLOAT32,f64:FLOAT64,
s:STRING` — eight fields, all NOT NULL — read through `RowLayout.of(...)` directly or through
`RowLayout.toString()`, which prints header, null-bitmap size and offset, fixed size and offset, and
the variable-field count.
**Steps:** build the layout and read `offsetOf(0..7)`, `nullBitmapOffset()`, `nullBitmapBytes()`,
`fixedRegionOffset()`, `fixedEnd()`, `variableFieldCount()`, `rowSize(5)`.
**Expected, computed by hand.** `HEADER_BYTES` = 32. n = 8, so `nullBitmapBytes` =
align8(⌈8/8⌉) = align8(1) = **8**, `nullBitmapOffset` = **32**, `fixedRegionOffset` = 32 + 8 = **40**.
Then, aligning each field to `min(width, 8)`:

| ordinal | field | width | cursor in | aligned to | **offset** | cursor out |
|---|---|---|---|---|---|---|
| 0 | `id` INT64 | 8 | 40 | 8 | **40** | 48 |
| 1 | `b` BOOLEAN | 1 | 48 | 1 | **48** | 49 |
| 2 | `i8` INT8 | 1 | 49 | 1 | **49** | 50 |
| 3 | `i16` INT16 | 2 | 50 | 2 | **50** | 52 |
| 4 | `i32` INT32 | 4 | 52 | 4 | **52** | 56 |
| 5 | `f32` FLOAT32 | 4 | 56 | 4 | **56** | 60 |
| 6 | `f64` FLOAT64 | 8 | 60 | 8 | **64** | 72 |
| 7 | `s` STRING | 8 (var slot) | 72 | 8 | **72** | 80 |

`fixedEnd` = align8(80) = **80**. `variableFieldCount` = **1**. `rowSize(5)` = 80 + 5 = **85**.
Note the four bytes of padding at 60–63 before `f64`, and note that `f32` at 56 is followed by
padding — so the eight-byte read of `f32` in TYPE-026 picks up `f32`'s four bytes plus four
**uninitialised-then-zeroed** padding bytes, because `begin()` zeroes `0..fixedEnd`. That is why
TYPE-026 needs the explicit `f32 = 1.5` control rather than reasoning from garbage.
Add the same computation for a DECIMAL field: width 16, aligned to `min(16,8)` = 8, occupying 16
bytes — assert `offsetOf` advances by 16, not by 8.

---

## 12. Overflow and underflow (TYPE-094 … TYPE-101)

`Expression.Arithmetic.evaluateLong` uses `Math.addExact` / `subtractExact` / `multiplyExact`, which
throw on 64-bit overflow. `InterpretedPipeline.writeComputed` then narrows the result with a plain
Java cast for `INT32`, `INT16` and `INT8`. So the engine detects overflow at exactly one width and
wraps silently at three — and which of the four applies is decided by Calcite's type inference for
the expression, not by anything the user wrote.

## TYPE-094 — INT32 overflow wraps silently, under a success status
**Intent:** the defect the brief names, pinned with hand-computed arithmetic on both sides of the
wrap so the magnitude of the error is on the record.
**Falsifier:** the query throwing (the defect is fixed — then assert it names the column and carries
a code); or the query returning the mathematically correct 2147483648.
**Setup:** `small.csv`; `u` = 2147483647, −2147483648, 7, −7; `v` = 1, −1, 3, 3.
**Steps:**
1. `pravaha validate --stream small --schema "<small schema>" --sql "SELECT id, u + v AS c FROM
   small"` — read and record the printed type of `c`
2. `pravaha run` the same with `--out-schema "id:INT64,c:INT32?"`, and record the exit code
3. the same for `u - v`, `u * v`
4. the control: `SELECT id, CAST(u AS BIGINT) + CAST(v AS BIGINT) AS c FROM small` with
   `--out-schema "id:INT64,c:INT64?"`
**Expected:** step 1 prints `c` as `INTEGER`. Step 2 exits **0** with 4 in / 4 out and:
- id 1: 2147483647 + 1 = 2147483648; `(int) 2147483648L` = **−2147483648**. Off by 2³² = 4294967296.
- id 2: −2147483648 + (−1) = −2147483649; `(int) −2147483649L` = **2147483647**. Off by 2³².
- id 3: 7 + 3 = **10**. id 4: −7 + 3 = **−4**.
Step 3, `u - v`: id 1 → 2147483646; id 2 → −2147483647; id 3 → 4; id 4 → −10. No wrap.
`u * v`: id 1 → 2147483647 × 1 = 2147483647; id 2 → −2147483648 × −1 = 2147483648, `(int)` →
**−2147483648** — a negative product of two negatives, under exit 0; id 3 → 21; id 4 → −21.
Step 4 returns the correct 2147483648 and −2147483649, proving the values are reachable and only the
output width is losing them.
**Vacuity:** ids 3 and 4 give the right answers in the same run, so the case cannot pass by the
query failing wholesale. The assertion is on the two wrapped values specifically, with the exit code
asserted as 0 — "wrong answer, green status" is the finding, and an assertion that allowed a
non-zero exit would miss it.

## TYPE-095 — INT64 overflow throws, and the message names nothing useful
**Intent:** the contrast with TYPE-094. `Math.addExact` throws `ArithmeticException("long
overflow")` — a message with no column, no row, no value and no `PRV-` code.
**Falsifier:** the query wrapping instead of throwing; or the exception being swallowed and the row
silently dropped under exit 0 (finding Q-7 says this happens non-deterministically).
**Setup:** `num.csv`; row 7 has `a` = 9223372036854775807, `b` = 1; row 8 has `a` =
−9223372036854775808, `b` = −1.
**Steps:**
1. `SELECT id, a + b AS c FROM num` with `--out-schema "id:INT64,c:INT64?"`
2. `SELECT id, a - b AS c FROM num`
3. `SELECT id, a * b AS c FROM num`
4. run each **five times** and record the exit code and row count of every run
**Expected:** step 1 must fail on row 7 with `ArithmeticException: long overflow`, exit 1.
Step 2 must fail on row 8: −9223372036854775808 − (−1) is fine (= −9223372036854775807), but row 7
is 9223372036854775807 − 1 = 9223372036854775806, also fine — so step 2 **succeeds** and returns
row 7 → 9223372036854775806 and row 8 → −9223372036854775807. That asymmetry is deliberate: it shows
the throw is about the value, not about the column.
Step 3: row 7 is 9223372036854775807 × 1 = 9223372036854775807 (no overflow); row 8 is
−9223372036854775808 × −1 = 9223372036854775808, which does not fit → `ArithmeticException: long
overflow`.
Step 4 is the Q-7 check: **all five runs of each step must agree**. A step that exits 0 with 7 rows
on one run and 1 with 0 rows on another is non-determinism, which is worse than either outcome.
**Vacuity:** step 2 succeeding in the same fixture is what makes steps 1 and 3's failures
attributable to overflow rather than to the row or the file.

## TYPE-096 — INT16 and INT8 arithmetic: which width does the output take?
**Intent:** the neighbours of TYPE-094. Whether the narrow widths wrap depends entirely on whether
Calcite types `TINYINT + TINYINT` as `TINYINT` or promotes it to `INTEGER`, and nothing in Pravaha
decides it. Establish the fact before asserting the values.
**Falsifier:** an output type that differs between `validate` and the executed plan; a wrap under
exit 0 at either width.
**Setup:** `small.csv`; `p`/`q` are INT8 (127, 1 / −128, −1 / 7, 3 / −7, 3), `m`/`n` are INT16
(32767, 1 / −32768, −1 / 7, 3 / −7, 3).
**Steps:**
1. `pravaha validate` on `SELECT id, p + q AS c FROM small` and on `m + n`; record the printed type
   of `c` in both
2. run both, with `--out-schema` matching whatever step 1 reported
3. run `SELECT id, p + q + q AS c FROM small` — a three-term sum, to see whether the promotion
   happens once or per operator
**Expected, branching on step 1.**
*If `c` is `INTEGER`:* no wrap. `p + q` → 128, −129, 10, −4. `m + n` → 32768, −32769, 10, −4. All
four values fit an `INTEGER` output and the answers are mathematically correct. This is the good
outcome and the case passes.
*If `c` is `TINYINT` / `SMALLINT`:* `writeComputed` narrows with `(byte)` / `(short)` and the
answers become 127 + 1 = 128 → `(byte) 128` = **−128**; −128 + (−1) = −129 → `(byte) −129` =
**127**; 32767 + 1 = 32768 → `(short) 32768` = **−32768**; −32768 + (−1) = −32769 →
`(short) −32769` = **32767**. Exit 0. That is the same defect as TYPE-094 two widths down and must
be filed as such.
Step 3 distinguishes a one-time promotion from a per-operator one; record the type and the value for
id 1 (127 + 1 + 1 = 129).

## TYPE-097 — Long.MIN_VALUE / −1 overflows without throwing
**Intent:** the one arithmetic overflow Java's exact methods do not cover. `Arithmetic.evaluateLong`
routes `DIVIDE` to a plain `l / r` with only a zero check, and `Long.MIN_VALUE / -1L` is
`Long.MIN_VALUE` in Java — a negative quotient from two operands of opposite sign is impossible in
mathematics and is what the engine returns.
**Falsifier:** the expression returning 9223372036854775808 (impossible in 64 bits, so the real
falsifier is a throw or a refusal); or the case silently passing because the row was dropped.
**Setup:** `num.csv` row 8: `a` = −9223372036854775808, `b` = −1.
**Steps:**
1. `SELECT id, a / b AS c FROM num` with `--out-schema "id:INT64,c:INT64?"`
2. `SELECT id, a % b AS c FROM num`
3. `SELECT id, ABS(a / b) AS c FROM num`
**Expected:** step 1 exits 0 and row 8's `c` is **−9223372036854775808** — negative, from dividing a
negative by a negative. Mathematically the answer is 9223372036854775808, which no `INT64` holds, so
the correct behaviour is the `ArithmeticException` that `ABS` already raises for the same reason
(TYPE-126). Record the discrepancy: `ABS(Long.MIN_VALUE)` throws and `Long.MIN_VALUE / -1` does not,
for identical arithmetic.
Step 2: `Long.MIN_VALUE % -1L` is **0** in Java, which is correct.
Step 3 is the confirmation: `ABS` of step 1's result is `ABS(Long.MIN_VALUE)`, which **throws** —
so the engine refuses to take the absolute value of a number it was willing to compute.
Other rows for context: row 1 `17 / 5` = 3, row 7 `9223372036854775807 / 1` = 9223372036854775807.

## TYPE-098 — unary minus, and the refusal that contradicts itself
**Intent:** finding Q-11's neighbour. `SELECT -a` is refused with a message stating that unary minus
*is* supported. The case establishes what actually works, so the refusal can be fixed against a
known-good list, and checks whether the INT64 minimum survives each spelling.
**Falsifier:** `-a` planning (the defect is fixed — then check `-(-9223372036854775808)` throws);
`0 - a` and `a * -1` disagreeing on any row.
**Setup:** `num.csv`.
**Steps:** `SELECT id, -a AS c FROM num`; `SELECT id, 0 - a AS c FROM num`;
`SELECT id, a * -1 AS c FROM num`; `SELECT id FROM num WHERE -a > 0`.
**Expected:** `-a` is refused with `PRV-2021` and `'<expr>' has 1 operands; only the two-operand
form is supported (unary minus included, which Calcite normalises to 0 - x).` — a sentence that
says unary minus is included while refusing it. Record the message verbatim; it is the finding.
`0 - a`: row 1 → −17, row 2 → 17, row 3 → −17, row 4 → 17, row 5 → −7, row 6 → NULL,
row 7 → −9223372036854775807, row 8 → `Math.subtractExact(0, -9223372036854775808)` → **throws
`ArithmeticException: long overflow`**, which is correct.
`a * -1`: identical values for rows 1–7, and row 8 is
`Math.multiplyExact(-9223372036854775808, -1)` → **throws**, also correct. The two spellings must
agree, including on the throw.
`WHERE -a > 0` is refused for the same reason as `SELECT -a`.

## TYPE-099 — FLOAT32 overflow at decode is silent
**Intent:** `Float.parseFloat` returns `Infinity` for an out-of-range literal rather than throwing,
so `DelimitedCodec`'s `NumberFormatException` handler never fires and a value 10 orders of magnitude
out of range enters the engine as `Infinity` under a successful decode. The integer widths behave
the opposite way (TYPE-088) — the same file, the same codec, two different policies.
**Falsifier:** `1.0E39` throwing at decode (the inconsistency is closed — record it); or arriving as
`3.4028235E38` (clamped, which would be a third policy).
**Setup:** `f32e.csv` from TYPE-089.
**Steps:** decode and project `f`; then `WHERE f > 3.4028235E38`; then compare with `over.csv` from
TYPE-088, where `128` in an `INT8` column **does** throw.
**Expected:** `1.0E39` → `Infinity`, exit 0. `WHERE f > 3.4028235E38` → ids `6` and `9` — the
overflowed value and the explicitly declared `Infinity`, indistinguishable. The contrast with
`over.csv` is the finding: an integer one past its range is a decode error naming the line, and a
float ten orders past its range is `Infinity` with no diagnostic at all.

## TYPE-100 — FLOAT64 overflow at decode and in arithmetic
**Intent:** as TYPE-099 at the eight-byte width, plus the arithmetic case: IEEE overflow inside an
expression is also silent, by design (`l * r` with no exact-method equivalent).
**Falsifier:** `1.0E309` throwing at decode; `1.0E308 * 10` throwing rather than yielding `Infinity`;
`Infinity - Infinity` yielding anything but `NaN`.
**Setup:** `f64e.csv` from TYPE-090.
**Steps:** project `f`; `SELECT id, f * 10 AS c FROM f64e`; `SELECT id, f - f AS c FROM f64e`;
`SELECT id, f / f AS c FROM f64e`.
**Expected:** `1.0E309` decodes to `Infinity`.
`f * 10`: id 1 → 1.7976931348623157E308 × 10 overflows → **`Infinity`**, exit 0. id 3 →
4.9E-324 × 10 = **4.94E-323** (a subnormal times ten stays subnormal and loses bits; assert the
exact printed value, which is `4.94E-323`). id 5 → 2.2250738585072014E-307. id 8 → `NaN`.
id 9 → `Infinity`. id 10 → `-Infinity`.
`f - f`: **0.0** for every finite row; **`NaN`** for ids 8, 9 and 10 (`Infinity - Infinity` is NaN).
`f / f`: **1.0** for every finite non-zero row; `NaN` for ids 7 (0.0/0.0), 8, 9 and 10.
Every one of these is IEEE-correct and none throws; that is the documented position
("Floating point division by zero is infinity rather than an error, which is IEEE's answer and stays
IEEE's answer here") and this case is where it is verified rather than assumed.

## TYPE-101 — FLOAT32 and FLOAT64 underflow to zero, silently
**Intent:** the other end of TYPE-099/100, and the more dangerous one: `Infinity` is visible in a
result set and `0.0` is not. A price of `1.0E-46` becoming exactly zero is a value that will be
summed, divided by, and compared to zero without anybody noticing.
**Falsifier:** `1.0E-46` throwing at decode; arriving as `1.4E-45` (clamped); or arriving as a
non-zero value that is not a subnormal.
**Setup:** `f32e.csv` and `f64e.csv`.
**Steps:** project `f`; `WHERE f = 0`; `WHERE f <> 0`; then the arithmetic underflow
`SELECT id, f * 1.0E-300 AS c FROM f64e`.
**Expected:** in `f32e`, id 7 (`1.0E-46`) projects as **`0.0`** and is returned by `WHERE f = 0`
alongside no other row — one row that was not zero in the file. In `f64e`, id 7 (`1.0E-324`) likewise
projects as **`0.0`**: the smallest positive double is 4.9E-324, and 1.0E-324 is below half of it, so
it rounds to zero.
`f * 1.0E-300` over `f64e`: id 5 is 2.2250738585072014E-308 × 1.0E-300, whose true value is
~2.2E-608 — far below the subnormal floor — so the result is **0.0**, silently. Note this expression
also triggers the DECIMAL-literal hazard of TYPE-110 if `1.0E-300` is parsed as a decimal literal;
if it is refused, substitute `f * f` (id 5 → 4.95E-616 → **0.0**) and record both.
**Vacuity:** `WHERE f <> 0` returning the complement, and both counts summing to the row count, is
what distinguishes "underflowed to zero" from "the row was dropped".

---

## 13. Arithmetic over every numeric pair (TYPE-102 … TYPE-110)

Five operators over six numeric types. `Expression.Arithmetic` has exactly two evaluators —
`evaluateLong` and `evaluateDouble` — and which one runs is decided by the *output* field's type in
`writeComputed`, not by the operand types. So the pair matters twice: once for which evaluator runs,
and once for which narrowing is applied on the way out. Each case below enumerates all five
operators over all four sign combinations, with every value computed by hand.

## TYPE-102 — INT64 × INT64, five operators, four sign combinations
**Intent:** the integer baseline. Twenty hand-computed values, so that every later case can be
compared against a known-correct table.
**Falsifier:** any of the twenty differing; integer `/` producing a fraction; `%` taking the sign of
the divisor.
**Setup:** `num.csv` rows 1–4: `(a,b)` = (17,5), (−17,5), (17,−5), (−17,−5).
**Steps:** `SELECT id, a+b AS s, a-b AS d, a*b AS m, a/b AS q, a%b AS r FROM num` with
`--out-schema "id:INT64,s:INT64?,d:INT64?,m:INT64?,q:INT64?,r:INT64?"`, restricted to rows 1–4 by
`WHERE id <= 4`.
**Expected:**

| id | a | b | `a+b` | `a-b` | `a*b` | `a/b` | `a%b` |
|---|---|---|---|---|---|---|---|
| 1 | 17 | 5 | **22** | **12** | **85** | **3** | **2** |
| 2 | −17 | 5 | **−12** | **−22** | **−85** | **−3** | **−2** |
| 3 | 17 | −5 | **12** | **22** | **−85** | **−3** | **2** |
| 4 | −17 | −5 | **−22** | **−12** | **85** | **3** | **−2** |

Division truncates **towards zero** (−17/5 = −3, not −4 as floor division would give) and `%` takes
the sign of the **dividend** (−17 % 5 = −2, 17 % −5 = +2). Check: 3 × 5 + 2 = 17 ✓;
−3 × 5 + (−2) = −17 ✓; −3 × −5 + 2 = 17 ✓; 3 × −5 + (−2) = −17 ✓. The identity
`(a/b)*b + a%b = a` must hold for all four rows and is the cheapest way to catch a sign error.
**Vacuity:** all four sign combinations in one run. A build that got the signs right only for
positives would pass one row of four.

## TYPE-103 — FLOAT64 × FLOAT64, five operators, four sign combinations
**Intent:** the floating baseline, over the same magnitudes so the contrast with TYPE-102 is exactly
the type and nothing else.
**Falsifier:** `x/y` returning 3 instead of 3.4 (integer division leaked into the double path); `%`
returning a different sign convention from the integer case.
**Setup:** `num.csv` rows 1–4: `(x,y)` = (17.0,5.0), (−17.0,5.0), (17.0,−5.0), (−17.0,−5.0).
**Steps:** `SELECT id, x+y, x-y, x*y, x/y, x%y FROM num WHERE id <= 4` with a FLOAT64 out-schema.
**Expected:**

| id | x | y | `x+y` | `x-y` | `x*y` | `x/y` | `x%y` |
|---|---|---|---|---|---|---|---|
| 1 | 17.0 | 5.0 | **22.0** | **12.0** | **85.0** | **3.4** | **2.0** |
| 2 | −17.0 | 5.0 | **−12.0** | **−22.0** | **−85.0** | **−3.4** | **−2.0** |
| 3 | 17.0 | −5.0 | **12.0** | **22.0** | **−85.0** | **−3.4** | **2.0** |
| 4 | −17.0 | −5.0 | **−22.0** | **−12.0** | **85.0** | **3.4** | **−2.0** |

`x/y` is the assertion against TYPE-102's `a/b`: **3.4 here, 3 there**, from the same numbers. Java's
`%` on doubles is `fmod`, which takes the dividend's sign — the same convention as the integer case,
which is worth asserting because IEEE's `remainder` operation does not.
All of `17.0`, `5.0`, `22.0`, `85.0` and `2.0` are exactly representable; `3.4` is not, and
`Double.toString` of the nearest double prints exactly `3.4` — compare as text or as a `double`, not
against a decimal expansion.

## TYPE-104 — INT32 × INT32, five operators
**Intent:** the narrower integer pair, where `writeComputed`'s `(int)` narrowing applies. Rows 3–4 of
`small.csv` are small enough that nothing wraps, so this case isolates the *arithmetic*; TYPE-094
isolates the *wrap*.
**Falsifier:** any value differing from TYPE-102's table for the same operands; `u/v` producing a
FLOAT.
**Setup:** `small.csv` rows 3 and 4: `(u,v)` = (7,3), (−7,3).
**Steps:** `SELECT id, u+v, u-v, u*v, u/v, u%v FROM small WHERE id >= 3`, with the out-schema
matching whatever `validate` reports for the five columns.
**Expected:** id 3: 7+3 = **10**, 7−3 = **4**, 7×3 = **21**, 7/3 = **2** (truncating, not 2.333),
7%3 = **1**. id 4: −7+3 = **−4**, −7−3 = **−10**, −7×3 = **−21**, −7/3 = **−2**, −7%3 = **−1**.
Identity check: 2 × 3 + 1 = 7 ✓; −2 × 3 + (−1) = −7 ✓.
**Note:** finding Q-10 reports `-7 % 3` written to an `INT64` output column as `4294967295`. That is
`(int) -1` reinterpreted as an unsigned 32-bit value by a mismatched `--out-schema`. Run this case
**twice** — once with `--out-schema` matching the plan's real type, once with it deliberately
declared `INT64?` — and record both. The first must give −1; the second is the Q-10 defect and its
value is the finding.

## TYPE-105 — INT16 × INT16 and INT8 × INT8, five operators
**Intent:** the two narrowest pairs, completing the integer column of the grid. As TYPE-104, chosen
so nothing wraps, so the arithmetic is isolated from TYPE-096's width question.
**Falsifier:** any value differing from TYPE-104's; a result that changes when an adjacent column
changes (a read of the wrong width).
**Setup:** `small.csv` rows 3 and 4: `(m,n)` = (7,3), (−7,3) for INT16; `(p,q)` = (7,3), (−7,3) for
INT8.
**Steps:** the five operators for each pair, on rows 3 and 4.
**Expected:** both pairs give exactly TYPE-104's table: **10, 4, 21, 2, 1** for row 3 and
**−4, −10, −21, −2, −1** for row 4. Ten values per width, twenty in all.
**Vacuity:** the three integer widths agreeing on the same twenty numbers is the assertion. A width
read incorrectly would disagree with the other two, and any one width alone could not show it.

## TYPE-106 — FLOAT32 × FLOAT32, five operators
**Intent:** the four-byte float pair, where `writeComputed` narrows with `(float)` after evaluating
in `double`. That round trip is exact for these magnitudes and must stay exact.
**Falsifier:** `r/3` returning a value that differs from `(float)(17.0/3.0)`; the output column
typed `DOUBLE` while declared `FLOAT32`, or the reverse.
**Setup:** `num.csv`, column `r` = 17.0, −17.0, 17.0, −17.0 for rows 1–4.
**Steps:** `pravaha validate` on `SELECT id, r+r, r-r, r*r, r/r, r%r FROM num` and record the five
printed types; then run with a matching out-schema; then `SELECT id, r/3.0E0 AS c FROM num` (the
`E0` form forces a DOUBLE literal rather than the DECIMAL one of TYPE-110).
**Expected:** for rows 1–4: `r+r` = **34.0 / −34.0 / 34.0 / −34.0**; `r-r` = **0.0** everywhere;
`r*r` = **289.0** everywhere (17² = 289, exactly representable); `r/r` = **1.0** everywhere;
`r%r` = **0.0** everywhere. Row 5 (`r` = 7.0): 14.0, 0.0, 49.0, 1.0, 0.0. Row 6 (`r` NULL): NULL in
all five.
`r/3.0E0`: 17.0/3.0 in `double` is 5.666666666666667; narrowed to `float` it is **5.6666665**, and
if the output column is `DOUBLE` it is **5.666666666666667**. Record which, because the answer
depends entirely on the type Calcite chose and nothing in the query says.
**Vacuity:** `r-r = 0.0` and `r/r = 1.0` would pass on almost any implementation; `r*r = 289.0` and
the `r/3.0E0` value are the discriminating ones.

## TYPE-107 — INT64 × FLOAT64: the mixed pair, and the cast Calcite inserts
**Intent:** `rate * 2 > amount` over a DOUBLE and a BIGINT is the case `Expression.Cast` exists for.
The integer side is wrapped in a `CAST` by Calcite, `Cast.evaluateDouble` takes
`source.evaluateLong` and widens it, and the result is the *floating* answer — which is a different
number from the integer answer for the same operands.
**Falsifier:** `a / x` returning 3 (the integer answer); the cast being visible in `EXPLAIN` (it is
deliberately invisible — `Cast.describe` returns the source's description); a loss of precision
above 2⁵³.
**Setup:** `num.csv` rows 1–4 (`a` = ±17, `x` = ±17.0, `y` = ±5.0) and row 7 (`a` =
9223372036854775807).
**Steps:**
1. `SELECT id, a/y AS c FROM num WHERE id <= 4`, out-schema FLOAT64
2. `SELECT id, a/b AS c FROM num WHERE id <= 4`, out-schema INT64 — the contrast
3. `SELECT id, a*y, a+y, a-y, a%y FROM num WHERE id <= 4`
4. `pravaha explain --level physical` on step 1's SQL
5. `SELECT id, a + y AS c FROM num WHERE id = 7` — the precision probe
**Expected:** step 1: **3.4, −3.4, −3.4, 3.4** (id 3's `y` is −5.0, id 4's is −5.0). Step 2:
**3, −3, −3, 3**. The two differ on every row, and that difference is the case.
Step 3: `a*y` = 85.0, −85.0, −85.0, 85.0; `a+y` = 22.0, −12.0, 12.0, −22.0; `a-y` = 12.0, −22.0,
22.0, −12.0; `a%y` = 2.0, −2.0, 2.0, −2.0 — identical to TYPE-103, which is the point: the mixed
pair must agree with the all-double pair.
Step 4: the `EXPLAIN` output shows `(a / y)`, with **no `CAST` rendered** — assert its absence, since
`Cast.describe` deliberately delegates.
Step 5: `a` is 9223372036854775807 and `y` is 5.0. `Cast.evaluateDouble` converts the long to a
double, which rounds it to 9223372036854775808.0 (2⁶³), and adding 5.0 leaves it unchanged at that
magnitude, so `c` = **9.223372036854776E18**. The integer value's last three digits are lost by the
cast — correct IEEE behaviour and a real precision hazard; assert the exact printed value.

## TYPE-108 — INT32 × INT64: mixed integer widths
**Intent:** the pair where Calcite promotes to `BIGINT` and the 32-bit wrap of TYPE-094 should
therefore **not** happen. That is the documented workaround for TYPE-094 and it must work.
**Falsifier:** `u + k` wrapping; the output typed `INTEGER`.
**Setup:** `mix.csv` — schema `id:INT64,u:INT32?,k:INT64?` — with `1,2147483647,1` /
`2,-2147483648,-1` / `3,7,3`.
**Steps:** `pravaha validate` on `SELECT id, u+k AS c FROM mix` and record `c`'s type; then run with
`--out-schema "id:INT64,c:INT64?"`; then the five operators.
**Expected:** `c` is `BIGINT`. `u+k`: id 1 → **2147483648** (no wrap — the number TYPE-094 could not
produce), id 2 → **−2147483649**, id 3 → **10**. `u-k`: 2147483646, −2147483647, 4.
`u*k`: 2147483647, 2147483648, 21 — note id 2 is −2147483648 × −1 = **2147483648**, positive and
correct, where TYPE-094's same-width version gave −2147483648. `u/k`: 2147483647, 2147483648, 2.
`u%k`: 0, 0, 1.
**Vacuity:** id 3 gives small correct answers in the same run, so a wholesale failure cannot be
mistaken for a pass; ids 1 and 2 are the assertions, and their values are exactly the ones TYPE-094
loses.

## TYPE-109 — FLOAT32 × FLOAT64: mixed floating widths
**Intent:** the remaining mixed pair. Calcite promotes `REAL` to `DOUBLE`, and `Column.evaluateDouble`
widens the float exactly — so the result is the double computed from the float's *actual* stored
value, which is not the decimal text that was in the file.
**Falsifier:** the result computed as if the FLOAT32 held its decimal text exactly; the output typed
`REAL`, which would narrow the answer.
**Setup:** `mixf.csv` — schema `id:INT64,r:FLOAT32?,d:FLOAT64?` — with `1,0.1,0.1` /
`2,16777217,16777217` / `3,17.0,5.0`.
**Steps:** `validate` for the type of `r+d`, `r-d`, `r*d`, `r/d`, `r%d`; then run.
**Expected:** the output type is `DOUBLE`.
Id 1: `r` decoded by `Float.parseFloat("0.1")` is 0.1f, whose exact double value is
**0.10000000149011612**; `d` is 0.1 as a double, **0.1000000000000000055511151231257827**.
`r-d` = 0.10000000149011612 − 0.1 = **1.4901161138336505E-9**, *not* 0.0. That non-zero difference is
the case: it proves the float was widened rather than re-parsed.
`r+d` = **0.20000000149011612**. `r/d` = **1.0000000149011612**. `r*d` = **0.010000000149011613**.
`r%d` = 0.10000000149011612 % 0.1 = **1.4901161138336505E-9**.
Id 2: `r` is 1.6777216E7 (the 2²⁴+1 rounding), `d` is 1.6777217E7 exactly. `r-d` = **−1.0**.
Id 3: `r+d` = **22.0**, `r/d` = **3.4**, matching TYPE-103.
**Vacuity:** id 3 reproduces the all-double answers, so ids 1 and 2's non-zero differences are
attributable to the FLOAT32 storage and not to a broken subtraction.

## TYPE-110 — an integer column against a decimal-looking literal is refused as DECIMAL arithmetic
**Intent:** the neighbour of the DECIMAL refusal that nobody writes down. A literal spelled `2.5`
arrives from Calcite as `DECIMAL(2,1)`. `ExpressionCompiler.literal` is careful — it turns a
zero-scale decimal into a long and a non-zero one into a double — but the **call's** type is what
`typeOf` sees, and `BIGINT × DECIMAL` is `DECIMAL`, which `refuseDecimalType` rejects. So
`SELECT amount * 2.5` over a `BIGINT` column is refused while `SELECT rate * 2.5` over a `DOUBLE`
column is not. Neither query mentions decimals.
**Falsifier:** `a * 2.5` planning and producing an approximate answer (silently doing what the
refusal exists to prevent); or `x * 2.5` over a DOUBLE column being refused (a false positive that
would make the engine unusable for ordinary arithmetic).
**Setup:** `num.csv`.
**Steps:** `pravaha validate` on each of:
1. `SELECT id, a * 2.5 AS c FROM num` (BIGINT × decimal literal)
2. `SELECT id, a / 2.5 AS c FROM num`
3. `SELECT id, a + 2.5 AS c FROM num`
4. `SELECT id, a * 2.0 AS c FROM num` — trailing-zero decimal, which `literal` strips to a long
5. `SELECT id, x * 2.5 AS c FROM num` (DOUBLE × decimal literal)
6. `SELECT id, a * 2 AS c FROM num` — integer literal, the control
7. `SELECT id, CAST(a AS DOUBLE) * 2.5 AS c FROM num` — the suggested rewrite
**Expected:** 1–3 are refused with `PRV-2021` and the DECIMAL sentence: *"is DECIMAL arithmetic,
which Pravaha refuses rather than approximates. Evaluating it in double would pass every test
anybody writes and produce a rounding error in a ledger… Cast to DOUBLE explicitly if approximate is
genuinely acceptable."* Record the message: it is accurate about the policy and misleading about the
query, because nothing in `a * 2.5` is decimal in any sense the user intended.
4 is the interesting one — `2.0` strips to scale 0, so the *literal* becomes a long, but the
**call's** type is still `DECIMAL` and the refusal is expected to fire anyway. If it does not,
record the inconsistency: `a * 2.0` works and `a * 2.5` does not, for reasons invisible in the SQL.
5 must **succeed**: `x` is DOUBLE, so the call type is DOUBLE. Values for rows 1–4: 17.0 × 2.5 =
**42.5**, −42.5, 42.5, −42.5.
6 must succeed: 34, −34, 34, −34.
7 must succeed and give 42.5, −42.5, 42.5, −42.5 — the same numbers as 5, which is what makes the
refusal's advice actionable.
**Vacuity:** cases 5, 6 and 7 succeeding in the same run is what proves 1–3's refusals are about the
literal's type and not about the file, the column or the operator.

---

## 14. Division, modulo and zero (TYPE-111 … TYPE-116)

Integer division by zero **throws** (`ArithmeticException`, with the record routed to the DLQ);
floating division by zero **is IEEE** (`Infinity`, `NaN`). Both are deliberate, both are documented
in the source, and the asymmetry is the thing to verify — a user writing `a / b` and `x / y` over the
same data gets an exception from one and an infinity from the other.

## TYPE-111 — integer division truncates towards zero, not towards negative infinity
**Intent:** the SQL standard says truncation; Java agrees; a "helpful" `Math.floorDiv` would disagree
for exactly the negative cases and agree everywhere else.
**Falsifier:** `−17 / 5` returning −4; `17 / −5` returning −4.
**Setup:** `num.csv` rows 1–4, plus `1,1,2` style rows where the quotient is between −1 and 1: add
`9,1,2,1.0,2.0,1.0` and `10,-1,2,-1.0,2.0,-1.0`.
**Steps:** `SELECT id, a/b AS q FROM num`.
**Expected:** rows 1–4: **3, −3, −3, 3**. Row 9: 1/2 = **0**. Row 10: −1/2 = **0**, not −1. Rows 9
and 10 are the discriminating pair — floor division gives −1 for row 10 and 0 for row 9, so a build
that disagrees on row 10 alone is using the wrong rule.

## TYPE-112 — modulo takes the sign of the dividend
**Intent:** the companion rule. `%` in SQL and in Java takes the dividend's sign; a modulo that
returned a non-negative result (Python's rule) would differ on exactly two of the four sign
combinations.
**Falsifier:** `−17 % 5` returning 3; `17 % −5` returning −3.
**Setup:** `num.csv` rows 1–4 and 9–10.
**Steps:** `SELECT id, a%b AS r FROM num`; then `SELECT id, (a/b)*b + a%b AS ident FROM num`; then
the `MOD(a, b)` spelling.
**Expected:** rows 1–4: **2, −2, 2, −2**. Row 9: 1 % 2 = **1**. Row 10: −1 % 2 = **−1**.
`ident` = `a` for every row — 17, −17, 17, −17, 1, −1. That identity holding across all six rows is
the strongest single assertion in this section.
`MOD(a, b)` must produce the identical column: `ExpressionCompiler` maps both `%` and `MOD` to
`Operator.MODULO`, so a difference between them is a compiler bug.

## TYPE-113 — integer division by zero throws, and what happens to the batch
**Intent:** the documented decision — *"throwing is the one that cannot be mistaken for an answer —
the record goes to the dead-letter queue with the reason"*. Finding Q-5 says it instead hangs for
five minutes and loses the whole batch. Measure it.
**Falsifier:** the division returning a value; the command hanging for five minutes; rows before the
failing one silently disappearing under exit 0.
**Setup:** `num.csv` row 5 (`a` = 7, `b` = 0). Rows 1–4 precede it and rows 6–8 follow it.
**Steps:**
1. `SELECT id, a/b AS q FROM num` with an INT64 out-schema; **time the command**
2. `SELECT id, a%b AS r FROM num`; time it
3. `SELECT id, a/b AS q FROM num WHERE b <> 0` — the guarded form
4. `SELECT id, CASE WHEN b = 0 THEN 0 ELSE a/b END AS q FROM num` — the guarded form the source
   javadoc recommends
**Expected:** steps 1 and 2 must fail with `ArithmeticException: division by zero in a projection;
the record is routed to the DLQ rather than given a value that could be mistaken for an answer`, and
must do so **in well under five seconds**. Record the wall-clock time; anything near five minutes is
the `awaitQuiescent` timeout in `QueryRunner` and is finding Q-5.
Record also how many rows reached `out.csv`: the message promises a DLQ and the loss of only the
offending record, so rows 1–4 and 6–8 should survive. If `out.csv` is empty or absent, the whole
batch was lost and the message is inaccurate.
Step 3 must succeed with rows 1–4 → 3, −3, −3, 3, row 6 → NULL, row 7 → 9223372036854775807,
row 8 → −9223372036854775808 (TYPE-097) — **seven rows, row 5 excluded**.
Step 4 must succeed with all eight rows and row 5 → **0**; this is TYPE-120's laziness case and is
included here to show the recommended workaround works.
**Vacuity:** steps 3 and 4 succeeding on the same file is what makes steps 1 and 2's failure
attributable to the zero and not to the fixture.

## TYPE-114 — floating division by zero is Infinity, not an error
**Intent:** the asymmetry with TYPE-113, stated in the source as a deliberate choice. Three distinct
IEEE results have to come out right: `+x/0.0`, `−x/0.0` and `0.0/0.0`.
**Falsifier:** `7.0/0.0` throwing; returning 0; or returning `NaN`.
**Setup:** `num.csv` row 5 (`x` = 7.0, `y` = 0.0), plus rows `11,0,0,-7.0,0.0,0.0` and
`12,0,0,0.0,0.0,0.0`.
**Steps:** `SELECT id, x/y AS q FROM num` with a FLOAT64 out-schema; then `WHERE x/y > 0`.
**Expected:** row 5 → **`Infinity`**, exit 0. Row 11 → **`-Infinity`**. Row 12 → 0.0/0.0 = **`NaN`**.
Rows 1–4 → 3.4, −3.4, −3.4, 3.4. Row 6 → NULL.
`WHERE x/y > 0` returns rows 1, 4 and 5 — the two positive quotients and the `Infinity`; `NaN` is
not greater than 0 and `-Infinity` is not.
**Vacuity:** the three different zero results (`Infinity`, `-Infinity`, `NaN`) in one run cannot all
come from a single wrong constant.

## TYPE-115 — floating modulo by zero is NaN
**Intent:** completes the zero row. Java's `%` on doubles with a zero divisor is `NaN`, not an
exception and not `Infinity`.
**Falsifier:** `7.0 % 0.0` throwing, or returning 0.0, or returning `Infinity`.
**Setup:** as TYPE-114.
**Steps:** `SELECT id, x%y AS r FROM num`; then `WHERE x%y = x%y` (false for NaN);
then `WHERE x%y IS NULL` (must be false — NaN is a value, not a null).
**Expected:** rows 5, 11 and 12 → **`NaN`**. Rows 1–4 → 2.0, −2.0, 2.0, −2.0. Row 6 → NULL.
`WHERE x%y = x%y` excludes rows 5, 11 and 12 **if** the comparison respects NaN ≠ NaN; note that
`Predicate.CompareExpressions` uses `Double.compare`, for which `Double.compare(NaN, NaN)` is **0**
— so this predicate is expected to **include** them, disagreeing with IEEE and with every other SQL
engine. Record which happens; a `Double.compare`-based equality that calls NaN equal to itself is a
real defect and this is the case that exposes it.
`WHERE x%y IS NULL` returns only row 6. NaN is not NULL, and a build that conflated them would
return four rows.

## TYPE-116 — `%` and `MOD`, and the operators that are not supported
**Intent:** `ExpressionCompiler` maps `%` and `MOD` to one operator and refuses everything else by
name. The refusal's list of supported functions is a user-facing contract and must be accurate.
**Falsifier:** `MOD(a,b)` and `a % b` differing; a supported function named in the refusal that is
then refused; `POWER`, `SQRT` or `MOD` with three arguments producing a value.
**Setup:** `num.csv`.
**Steps:** `SELECT id, MOD(a,b) FROM num WHERE id <= 4`; `SELECT id, a % b FROM num WHERE id <= 4`;
then `pravaha validate` on `POWER(a,2)`, `SQRT(a)`, `EXP(a)`, `LN(a)`, `MOD(a,b,2)`,
`ROUND(x, 2)`, `a ^ b`.
**Expected:** the two modulo spellings give the identical column **2, −2, 2, −2**.
`POWER`, `SQRT`, `EXP`, `LN` and `^` are refused with `PRV-2021` and
`function '<NAME>' in '<expr>' is not supported in a projection. Supported: + - * / %, ABS, FLOOR,
CEIL, ROUND, CASE WHEN, UPPER, LOWER, TRIM, SUBSTRING and || .` — assert that every function in
that list is in fact supported (it is the list this file's sections 15–17 cover), and that nothing
supported is missing from it.
`MOD(a,b,2)` and `ROUND(x, 2)` reach the arity check instead and are refused with
`ROUND is supported with one argument and was given 2. ROUND to a number of decimal places is not
built; round the value and scale it, or cast it.` — a different, more useful message. Assert both
messages, because they come from different branches and only one of them names a workaround.

---

## 15. CASE WHEN (TYPE-117 … TYPE-124)

Calcite flattens a chain into one call — condition, value, condition, value, …, else — and
`ExpressionCompiler.caseWhen` rebuilds it as nested two-way `Expression.Case` nodes. The constructor
enforces one output type, with a special rule for a null branch. Only the taken branch is evaluated,
which is load-bearing rather than an optimisation.

**Fixture `case.csv`** — schema `id:INT64,n:INT64?,d:FLOAT64?,s:STRING?`
```
1,10,10.0,alpha
2,0,0.0,beta
3,-7,-7.0,gamma
4,,,
5,100,100.0,delta
```

## TYPE-117 — branch selection: the first TRUE branch wins
**Intent:** the base semantics, over a three-branch chain, with a row matching each branch and a row
matching none.
**Falsifier:** a later branch taken while an earlier one is true; the ELSE taken while a branch is
true; the result depending on the order the conditions were evaluated in rather than on which is
first-true.
**Setup:** `case.csv`.
**Steps:** `SELECT id, CASE WHEN n > 50 THEN 1 WHEN n > 5 THEN 2 WHEN n >= 0 THEN 3 ELSE 4 END AS c
FROM cse` with `--out-schema "id:INT64,c:INT64?"`.
**Expected:** by hand — id 1 (`n` = 10): not > 50; > 5 → **2**. id 2 (`n` = 0): not > 50; not > 5;
>= 0 → **3**. id 3 (`n` = −7): none true → ELSE → **4**. id 4 (`n` NULL): every comparison is
UNKNOWN, which `Predicate.test` returns as false, so ELSE → **4**. id 5 (`n` = 100): > 50 → **1**.
Result: `1,2` / `2,3` / `3,4` / `4,4` / `5,1`.
The overlapping branches are deliberate: id 1 satisfies both `> 5` and `>= 0`, and id 5 satisfies all
three. If id 5 returns 2 or 3, the chain is not evaluating in written order.

## TYPE-118 — a CASE with no ELSE is NULL, not a type default
**Intent:** Calcite always supplies an ELSE, adding a null one where the SQL omitted it, and the
`Case` constructor retypes that null to the THEN branch's type. The observable result must be NULL,
which is the difference between "no rule applied" and "the rule said zero".
**Falsifier:** a no-match row returning 0, `false` or an empty string; the query being refused with
`this CASE has no ELSE branch and no value to fall through to`, which would mean the null-branch
retyping did not fire.
**Setup:** `case.csv`.
**Steps:**
1. `SELECT id, CASE WHEN n > 50 THEN 1 END AS c FROM cse`, out-schema `id:INT64,c:INT64?`
2. `SELECT id FROM cse WHERE (CASE WHEN n > 50 THEN 1 END) IS NULL` — proves NULL, not zero
3. `SELECT id, CASE WHEN n > 50 THEN d END AS c FROM cse`, out-schema `id:INT64,c:FLOAT64?`
4. `SELECT id, CASE WHEN n > 50 THEN s END AS c FROM cse`, out-schema `id:INT64,c:STRING?`
5. `SELECT id, CASE WHEN n > 50 THEN NULL ELSE 1 END AS c FROM cse` — the null on the *other* side
**Expected:** step 1 → `1,` / `2,` / `3,` / `4,` / `5,1`: four NULLs and one 1.
Step 2 returns `1,2,3,4` — four rows. Assert this, not the rendering; an empty CSV field could be a
zero-length string.
Steps 3 and 4 exercise the retyping for FLOAT64 and STRING: id 5 → 100.0 and `delta`, the rest NULL.
Step 5 exercises `isNullLiteral(then)`: id 5 → NULL, the rest → 1. Both directions of the constructor's
null-branch rule are then covered.

## TYPE-119 — nesting, and a CASE inside a CASE
**Intent:** `caseWhen` recurses `from + 2`, so a chain becomes right-nested pairs. An explicitly
nested CASE in the SQL is a different tree with the same meaning, and the two must agree.
**Falsifier:** the chained and nested forms disagreeing on any row; a five-branch chain losing its
last branch.
**Setup:** `case.csv`.
**Steps:**
1. the chain: `CASE WHEN n > 50 THEN 1 WHEN n > 5 THEN 2 ELSE 3 END`
2. the equivalent nesting: `CASE WHEN n > 50 THEN 1 ELSE CASE WHEN n > 5 THEN 2 ELSE 3 END END`
3. a five-branch chain: `CASE WHEN n > 50 THEN 1 WHEN n > 5 THEN 2 WHEN n = 0 THEN 3 WHEN n < 0
   THEN 4 ELSE 5 END`
4. `pravaha explain --level physical` on both 1 and 2
**Expected:** 1 and 2 give the identical column: **2, 3, 3, 3, 1**. 3 gives: id 1 → 2; id 2 → 3
(`n = 0`); id 3 → 4 (`n < 0`); id 4 → **5** (all four comparisons UNKNOWN → ELSE); id 5 → 1. The
fifth branch being reached by exactly one row is the assertion that the chain did not lose its tail.
Step 4: both plans render as
`CASE WHEN … THEN … ELSE CASE WHEN … THEN … ELSE … END END` — `Case.describe` nests explicitly, so
the two spellings should produce the same `EXPLAIN` text. If they differ, record the difference; two
identical queries with different fingerprints would not share one computation.

## TYPE-120 — only the taken branch is evaluated
**Intent:** *"`CASE WHEN n = 0 THEN 0 ELSE total / n END` divides by zero if both arms are evaluated,
and a reader is entitled to assume the guard guards."* The guard is the case.
**Falsifier:** the query throwing `ArithmeticException: division by zero`; the query throwing
`ABS(-9223372036854775808) has no representable result` from an untaken branch.
**Setup:** `case.csv` (id 2 has `n` = 0) and `num.csv` (row 8 has `a` = Long.MIN_VALUE).
**Steps:**
1. `SELECT id, CASE WHEN n = 0 THEN 0 ELSE 100 / n END AS c FROM cse`
2. `SELECT id, CASE WHEN n <> 0 THEN 100 / n ELSE 0 END AS c FROM cse` — the guard the other way up
3. `SELECT id, CASE WHEN a > -9223372036854775808 THEN ABS(a) ELSE 0 END AS c FROM num`
4. `SELECT id, CASE WHEN n IS NULL THEN 0 ELSE 100 / n END AS c FROM cse` — the NULL guard
**Expected:** step 1 exits 0. By hand: id 1 → 100/10 = **10**; id 2 → guard true → **0**; id 3 →
100/(−7) = **−14** (truncating toward zero: −14.28…); id 4 → `n` is NULL so `n = 0` is UNKNOWN → ELSE
→ `100 / NULL`, whose `isNull` is true **before** any division → **NULL**; id 5 → 100/100 = **1**.
Step 2 gives the same column: id 2's guard `n <> 0` is false → ELSE → 0. Id 4's guard is UNKNOWN →
ELSE → 0. Note the difference from step 1: **id 4 is 0 here and NULL in step 1**, because the NULL
row falls to the opposite arm. Both are correct; the pair is what shows the reader that "the guard
guards" does not mean "the guard covers NULL".
Step 3 exits 0 with row 8 → 0, and does **not** throw — the untaken `ABS` branch is never evaluated.
Step 4: id 4 → **0**, everything else as step 1.
**Vacuity:** every step must exit 0 **and** return 5 rows. A build that evaluated both arms would
throw, and a build that silently dropped the failing row would return 4 — the row count catches the
second.

## TYPE-121 — text branches
**Intent:** `Case.evaluateString` and `writeComputed`'s `case STRING` path. A CASE whose branches are
strings is the commonest business-rule shape there is, and the expression tree only learned to carry
text recently.
**Falsifier:** a text CASE refused; the branches' bytes written as a long (the failure the
`writeComputed` javadoc records); unicode mangled through the branch.
**Setup:** `case.csv`, and `text.csv` for the unicode half.
**Steps:**
1. `SELECT id, CASE WHEN n > 50 THEN 'big' WHEN n > 5 THEN 'medium' ELSE 'small' END AS c FROM cse`,
   out-schema `id:INT64,c:STRING?`
2. `SELECT id, CASE WHEN n > 50 THEN s ELSE 'none' END AS c FROM cse` — a column in a branch
3. `SELECT id, CASE WHEN id = 5 THEN '👍' ELSE 'ß' END AS c FROM txt` over `text.csv`
4. `SELECT id, n, CASE WHEN n > 5 THEN 'yes' ELSE 'no' END AS c FROM cse` — text beside a number,
   which is the mixed-projection shape that used to fail at the writer
**Expected:** 1 → `medium, small, small, small, big`. 2 → id 5 → `delta`, the rest `none`; note id 4
takes the ELSE and gets `none`, **not** NULL, even though `s` is NULL there — the branch that was
taken has no null in it. 3 → `ß` for ids 1–4 and 6–8, `👍` for id 5; compare byte lengths (2 and 4).
4 → five rows with both a number and a string per row: `1,10,yes` / `2,0,no` / `3,-7,no` /
`4,,no` / `5,100,yes`.
**Vacuity:** step 4 mixing a numeric and a text output column in one projection is the
anti-vacuity device; steps 1–3 would pass with a text-only writer.

## TYPE-122 — a CASE whose branches have different types
**Intent:** `Case`'s constructor throws unless both branches agree: *"A row whose type depends on its
own values has no schema."* Calcite may coerce first, so the case establishes which layer refuses and
whether the refusal is a `PRV-` code or a raw `IllegalArgumentException`.
**Falsifier:** a mixed-type CASE planning and producing a column whose type varies by row; a refusal
with no code and no explanation.
**Setup:** `case.csv`.
**Steps:** `pravaha validate` on each of:
1. `CASE WHEN n > 5 THEN 1 ELSE 'x' END` — number / text
2. `CASE WHEN n > 5 THEN s ELSE 0 END` — text / number
3. `CASE WHEN n > 5 THEN 1 ELSE 1.5 END` — integer / decimal literal
4. `CASE WHEN n > 5 THEN n ELSE d END` — BIGINT / DOUBLE columns
5. `CASE WHEN n > 5 THEN true ELSE 1 END` — boolean / number
**Expected:** 1, 2 and 5 are expected to be rejected by Calcite's validator with **`PRV-2002`**
(no common type), before Pravaha's constructor is reached. 4 is expected to be **coerced** by
Calcite to DOUBLE with a CAST around `n`, and to **succeed**: id 1 → 10.0, id 2 → 0.0, id 3 → −7.0,
id 4 → NULL, id 5 → 100.0. 3 is the interesting one — Calcite coerces to `DECIMAL`, which
`ExpressionCompiler.typeOf` refuses with `PRV-2021` and the DECIMAL sentence, so an ordinary
`CASE … THEN 1 ELSE 1.5 END` is refused for reasons that mention ledgers.
Record, for each of the five, whether the refusal came from Calcite (`PRV-2002`), from
`ExpressionCompiler` (`PRV-2021`) or from the `Case` constructor (a raw `IllegalArgumentException`
reported as `IllegalArgumentException: a CASE must produce one type, and this one produces …`). The
third is a finding: it has no code and reaches the user as a bare Java exception name.

## TYPE-123 — a CASE whose condition is UNKNOWN takes the ELSE
**Intent:** the single most surprising interaction in this file. `Predicate.test` is two-valued, so a
NULL condition is indistinguishable from a false one and the ELSE branch runs. SQL agrees — but a
reader expecting "NULL in, NULL out" does not.
**Falsifier:** a NULL condition producing NULL; a NULL condition producing the THEN branch.
**Setup:** `case.csv`; id 4 has `n`, `d` and `s` all NULL.
**Steps:**
1. `SELECT id, CASE WHEN n > 5 THEN 1 ELSE 0 END AS c FROM cse`
2. `SELECT id, CASE WHEN n IS NULL THEN -1 WHEN n > 5 THEN 1 ELSE 0 END AS c FROM cse` — the
   explicit form
3. `SELECT id, CASE WHEN n = n THEN 1 ELSE 0 END AS c FROM cse` — a tautology that is UNKNOWN for
   NULL
4. `SELECT id, CASE WHEN s = 'alpha' THEN 1 ELSE 0 END AS c FROM cse` — the text comparison route
**Expected:** 1 → **1, 0, 0, 0, 1** — id 4 is **0**, not NULL. 2 → **1, 0, 0, −1, 1**, which is what
the author almost always meant. 3 → **1, 1, 1, 0, 1**: `n = n` is true for every non-null row and
UNKNOWN for id 4, so the tautology is false there. 4 → **1, 0, 0, 0, 0**: `CompareString` returns
false for the null row.
**Vacuity:** steps 1 and 2 differ on exactly one row, and that row is the whole point. A case
asserting only step 1 would pass under an implementation that had no concept of UNKNOWN at all.

## TYPE-124 — a CASE used as a WHERE operand, and a text comparison inside one
**Intent:** two structural limits. `PredicateCompiler.compile` has no `CASE` kind, so a bare CASE in
a `WHERE` must reach `comparison`/`compareExpressions` or be refused; and `rejectText` forbids text
inside a larger comparison expression, which a CASE returning text would be.
**Falsifier:** `WHERE CASE … END` planning and filtering on something that is not the CASE's value;
a text-valued CASE compared to a literal producing a comparison of the underlying long.
**Setup:** `case.csv`.
**Steps:**
1. `SELECT id FROM cse WHERE (CASE WHEN n > 5 THEN 1 ELSE 0 END) = 1`
2. `SELECT id FROM cse WHERE (CASE WHEN n > 5 THEN 1 ELSE 0 END) > 0`
3. `SELECT id FROM cse WHERE CASE WHEN n > 5 THEN true ELSE false END` — a bare boolean CASE
4. `SELECT id FROM cse WHERE (CASE WHEN n > 5 THEN 'y' ELSE 'n' END) = 'y'`
5. `SELECT id, CASE WHEN s = 'alpha' THEN 1 ELSE 0 END AS c FROM cse` — a text comparison *inside*
   a CASE, which is the supported direction
**Expected:** 1 and 2 go through `compareExpressions` and must return **1,5**.
3 has no `CASE` kind in `PredicateCompiler.compile`, so it is expected to be refused with `PRV-2021`
and `cannot compile the expression '…' (CASE) yet. Supported: AND, OR, NOT, comparisons against a
literal, IS [NOT] NULL, LIKE against a literal pattern, and boolean columns.` Record it: an ordinary
boolean CASE in a WHERE is not supported and the message says so, which is acceptable — but the same
predicate written as form 1 works, and that difference is undocumented.
4 must be refused by `rejectText` with `PRV-2021` and `compares text inside a larger expression,
which Pravaha cannot do; only = and <> between a text column and a literal are supported`.
5 must **succeed** — a text comparison as a CASE's *condition* is a `Predicate`, not an expression
operand — giving **1, 0, 0, 0, 0**. The contrast between 4 and 5 is the case: text may be compared
to decide a branch, and may not be compared to decide a filter.

---

## 16. ABS, FLOOR, CEIL and ROUND (TYPE-125 … TYPE-132)

`Expression.Unary.type()` returns **the argument's type, unchanged** — so `FLOOR` of an integer is
that integer and of a double is a double, and the integer path deliberately never round-trips through
a `double`. `evaluateLong` returns the value unchanged for `FLOOR`, `CEIL` and `ROUND`, and throws
for `ABS(Long.MIN_VALUE)`. `evaluateDouble` implements `ROUND` as
`Math.signum(v) * Math.floor(Math.abs(v) + 0.5)` — half away from zero, which is what SQL means, and
which has a half-ulp defect that TYPE-130 and TYPE-131 exist to expose.

**Fixture `rnd.csv`** — schema `id:INT64,d:FLOAT64?,n:INT64?`
```
1,2.5,2
2,-2.5,-2
3,0.5,0
4,-0.5,0
5,0.49999999999999994,0
6,4503599627370497.0,4503599627370497
7,-0.4,0
8,1.5,1
9,-1.5,-1
10,0.0,0
11,,
12,9007199254740993.0,9007199254740993
13,-17.0,-17
14,-9223372036854775808.0,-9223372036854775808
```
`Double.parseDouble("9007199254740993.0")` is 2⁵³+1, which rounds to **9007199254740992.0**; the
`n` column holds the exact integer 9007199254740993. That pairing is deliberate.

## TYPE-125 — ABS over integers and over doubles
**Intent:** the ordinary cases, and the two zero cases where a sign can survive an absolute value.
**Falsifier:** `ABS(-17)` returning −17; `ABS(-0.0)` returning `-0.0`; `ABS(NULL)` returning 0.
**Setup:** `rnd.csv`, plus rows `15,-0.0,0` and `16,NaN,0` and `17,-Infinity,0`.
**Steps:** `SELECT id, ABS(n) AS a FROM rnd` (out-schema INT64) and `SELECT id, ABS(d) AS a FROM rnd`
(out-schema FLOAT64).
**Expected:** integer column: id 13 → **17**; id 1 → 2; id 2 → 2; id 9 → 1; id 11 → **NULL** (not 0
— `Unary.isNull` delegates to the argument); id 12 → 9007199254740993 (**exact**, because the
integer path never visits a double); id 14 → **throws**, see TYPE-126.
Double column: id 2 → 2.5; id 4 → 0.5; id 7 → 0.4; id 11 → NULL; id 15 → **0.0**, positive zero, not
`-0.0` (`Math.abs(-0.0)` clears the sign bit — assert the printed text, since `-0.0 == 0.0`
compares equal and only the rendering distinguishes them); id 16 → **NaN**; id 17 → **Infinity**.
**Vacuity:** id 11 being NULL in both columns is the null-propagation assertion, and id 12's exact
9007199254740993 is what proves the integer path stayed integral.

## TYPE-126 — ABS(Long.MIN_VALUE) throws rather than returning a negative absolute value
**Intent:** finding Q-12's fix, verified. `Math.abs(Long.MIN_VALUE)` is `Long.MIN_VALUE` — negative,
from a function whose job is to return something that is not — and the engine now refuses it.
**Falsifier:** the expression returning −9223372036854775808; the exception carrying no explanation;
the whole batch lost so that the other thirteen rows never appear.
**Setup:** `rnd.csv` row 14 (`n` = −9223372036854775808).
**Steps:**
1. `SELECT id, ABS(n) AS a FROM rnd` — time the command
2. `SELECT id, ABS(n) AS a FROM rnd WHERE n > -9223372036854775808` — the guarded form
3. `SELECT id, ABS(d) AS a FROM rnd` — the same magnitude as a double
4. `SELECT id, ABS(0 - n) AS a FROM rnd WHERE id = 14`
**Expected:** step 1 fails with
`ArithmeticException: ABS(-9223372036854775808) has no representable result: the range of a 64-bit
integer is asymmetric, so the magnitude of its smallest value is one larger than its largest.` —
exit 1, in well under five seconds. The message is exemplary; assert its text, because it is the
model the other run-time failures in this file are measured against.
Step 2 must succeed over the remaining rows.
Step 3 must **succeed**: `ABS(-9.223372036854776E18)` is `9.223372036854776E18`, because a double has
no asymmetry. The same number is representable as a double and not as a long, and the engine gives
two different answers for it — that contrast is worth recording.
Step 4: `0 - n` already throws (`Math.subtractExact`, TYPE-098), so the failure moves one operator
earlier and reports `long overflow` instead — a much worse message for the same underlying fact.
**Vacuity:** step 2 succeeding is what makes step 1's failure attributable to the value.

## TYPE-127 — FLOOR, CEIL and ROUND over an integer column are the identity
**Intent:** the deliberate short-circuit: *"Already whole. Returning it unchanged rather than
round-tripping through a double, which loses precision above 2⁵³ and would make FLOOR of a large id
a different id."* Row 12 is exactly that id.
**Falsifier:** `FLOOR(9007199254740993)` returning 9007199254740992; any of the three changing any
integer value; a negative integer being floored downward.
**Setup:** `rnd.csv`, column `n`.
**Steps:** `SELECT id, FLOOR(n), CEIL(n), CEILING(n), ROUND(n) FROM rnd` with an INT64 out-schema.
**Expected:** all four columns equal `n`, row for row: 2, −2, 0, 0, 0, 4503599627370497, 0, 1, −1,
0, **NULL**, **9007199254740993**, −17, −9223372036854775808.
Row 12 is the assertion — 2⁵³+1 survives — and row 14 shows that the integer path does **not** throw
for `Long.MIN_VALUE` the way `ABS` does. `CEILING` must produce the identical column to `CEIL`
(`unaryFunction` maps both).
**Vacuity:** rows 1, 2, 8 and 9 have fractional-looking doubles beside them in `d`; if any integer
result matches the `d`-column answer instead (3, −3, 2, −2), the integer short-circuit was bypassed.

## TYPE-128 — FLOOR and CEIL over a double column, including negatives and negative zero
**Intent:** `Math.floor` and `Math.ceil`, whose behaviour for negatives between −1 and 0 produces a
negative zero that renders differently from zero.
**Falsifier:** `FLOOR(-2.5)` returning −2.0; `CEIL(-2.5)` returning −3.0; `CEIL(-0.4)` returning
`0.0` rather than `-0.0` (or the reverse — the case records which, and either way it must be
consistent with `Math.ceil`).
**Setup:** `rnd.csv`, column `d`.
**Steps:** `SELECT id, FLOOR(d) AS f, CEIL(d) AS c FROM rnd` with a FLOAT64 out-schema.
**Expected, by hand:**

| id | `d` | `FLOOR(d)` | `CEIL(d)` |
|---|---|---|---|
| 1 | 2.5 | **2.0** | **3.0** |
| 2 | −2.5 | **−3.0** | **−2.0** |
| 3 | 0.5 | **0.0** | **1.0** |
| 4 | −0.5 | **−1.0** | **−0.0** |
| 5 | 0.49999999999999994 | **0.0** | **1.0** |
| 6 | 4503599627370497.0 | **4503599627370497.0** | **4503599627370497.0** |
| 7 | −0.4 | **−1.0** | **−0.0** |
| 8 | 1.5 | **1.0** | **2.0** |
| 9 | −1.5 | **−2.0** | **−1.0** |
| 10 | 0.0 | **0.0** | **0.0** |
| 11 | NULL | **NULL** | **NULL** |
| 12 | 9007199254740992.0 | **9.007199254740992E15** | **9.007199254740992E15** |
| 13 | −17.0 | **−17.0** | **−17.0** |

Ids 4 and 7 are the assertions: `Math.ceil` of a value in (−1, 0) is **negative zero**, printed by
`Double.toString` as `-0.0`. A result of `0.0` there means something re-normalised the value on the
way out, which matters because `CEIL(d)` feeding a `CASE WHEN … = 0` would then behave differently
from `CEIL(d)` printed.
Ids 6 and 12 must be returned unchanged, because both are already integral doubles.

## TYPE-129 — ROUND is half away from zero, not banker's rounding
**Intent:** finding Q-4's fix, verified on both signs. `Math.rint` would give 2 for `ROUND(2.5)` and
−2 for `ROUND(-2.5)`, disagreeing with Calcite, Postgres, MySQL and Oracle. Every value it produced
was plausible, which is why nobody noticed until an invoice was out by a penny.
**Falsifier:** `ROUND(2.5)` = 2; `ROUND(-2.5)` = −2; `ROUND(0.5)` = 0; `ROUND(1.5)` = 2 **and**
`ROUND(2.5)` = 2 (the signature of half-to-even, where one of the two is right by accident).
**Setup:** `rnd.csv`, column `d`.
**Steps:** `SELECT id, ROUND(d) AS r FROM rnd` with a FLOAT64 out-schema.
**Expected:** id 1 (2.5) → **3.0**; id 2 (−2.5) → **−3.0**; id 3 (0.5) → **1.0**; id 4 (−0.5) →
**−1.0**; id 8 (1.5) → **2.0**; id 9 (−1.5) → **−2.0**; id 10 (0.0) → **0.0**; id 11 → NULL;
id 13 (−17.0) → **−17.0**.
The 1.5/2.5 pair is the discriminator: half-to-even gives 2.0 for both, half-away-from-zero gives
2.0 and 3.0. Assert both in the same run.
**Vacuity:** four ties of each sign, so a build that special-cased one value cannot pass.

## TYPE-130 — ROUND(0.49999999999999994) — the half-ulp defect
**Intent:** the implementation adds 0.5 and floors. For the double immediately below 0.5, that
addition **rounds up to exactly 1.0** in IEEE 754, and the floor then returns 1 — a value strictly
less than a half rounding to one. This is a known defect of the `floor(x + 0.5)` idiom and the brief
names it; this case is the written proof.
**Falsifier:** `ROUND(0.49999999999999994)` returning 0.0 — which is the correct answer and means the
defect is fixed. Any other result, including 1.0, is the defect.
**Setup:** `rnd.csv` row 5. The value is 0.5 − 2⁻⁵⁴ = 0.499999999999999944488848768742172, the
largest double strictly below 0.5.
**Steps:**
1. `SELECT id, ROUND(d) AS r FROM rnd WHERE id = 5`
2. `SELECT id, d FROM rnd WHERE id = 5` — confirm the stored value
3. `SELECT id, FLOOR(d + 0.5E0) AS r FROM rnd WHERE id = 5` — the idiom, spelled out
4. the negative twin: add `18,-0.49999999999999994,0` and run `ROUND(d)`
**Expected, with the arithmetic shown:**
`Math.abs(0.499999999999999944488848768742172)` is the value itself.
Adding 0.5 gives the exact real 0.999999999999999944488848768742172, which is **exactly halfway**
between the two nearest doubles, 0.99999999999999988897769753748 (= 1 − 2⁻⁵³) and 1.0. IEEE 754
round-to-nearest-**even** picks the one with the even final significand bit, which is **1.0**.
`Math.floor(1.0)` = 1.0. `Math.signum(0.4999…)` = 1.0. So the result is **1.0**.
**The correct answer is 0.0**, because the input is strictly less than one half.
Step 2 must print `0.49999999999999994`, proving the input was not itself rounded to 0.5 on the way
in. Step 3 must give the same 1.0, proving the defect is in the idiom and not in `ROUND`'s dispatch.
Step 4 must give **−1.0** by the mirror argument, where the correct answer is −0.0 or 0.0.
**Vacuity:** step 2 is the anti-vacuity device. Without it, a decoder that turned the literal into
0.5 would make `ROUND` = 1.0 look correct.
**The fix to test against when it lands:** `BigDecimal.valueOf(v).setScale(0, RoundingMode.HALF_UP)`,
or a comparison-based implementation that checks `v - floor(v)` against 0.5 exactly. After any fix,
re-run TYPE-129 in full — half-away-from-zero must survive.

## TYPE-131 — ROUND(4503599627370497.0) — exactness above 2⁵²
**Intent:** the second face of the same defect, at the other end of the range. Above 2⁵² a double's
spacing is 1.0, so `x + 0.5` is not representable and rounds to even — which changes an integer that
needed no rounding at all.
**Falsifier:** `ROUND(4503599627370497.0)` returning 4503599627370497.0 — the correct answer, meaning
the defect is fixed.
**Setup:** `rnd.csv` row 6 (`d` = 4503599627370497.0 = 2⁵² + 1, exactly representable) and row 12.
**Steps:**
1. `SELECT id, ROUND(d) AS r FROM rnd WHERE id IN (6, 12)` — or two separate `WHERE id = …` runs if
   `IN` is unsupported
2. `SELECT id, d FROM rnd WHERE id = 6` — confirm the stored value
3. `SELECT id, ROUND(n) AS r FROM rnd WHERE id = 6` — the same number as an INT64
4. `SELECT id, FLOOR(d) AS f, CEIL(d) AS c FROM rnd WHERE id = 6` — the neighbours, which must be
   exact
**Expected, with the arithmetic shown:** 4503599627370497.0 + 0.5 = 4503599627370497.5 exactly in
the reals. Doubles in [2⁵², 2⁵³) are spaced 1.0 apart, so 4503599627370497.5 is **exactly halfway**
between 4503599627370497.0 and 4503599627370498.0. Round-to-nearest-even picks **4503599627370498.0**
(the even one). `Math.floor` leaves it. So `ROUND(4503599627370497.0)` = **4.503599627370498E15**,
one more than the input, for a value that was already a whole number.
Row 12 (`d` = 9007199254740992.0 = 2⁵³): 9007199254740992.5 lies between 9007199254740992.0 and
9007199254740994.0 (spacing 2.0 above 2⁵³), a quarter of the way, so it rounds **down** to
9007199254740992.0 and `ROUND` returns it unchanged — **correct**. The two rows together show the
defect is spacing-dependent and not a blanket off-by-one.
Step 2 must print `4.503599627370497E15`. Step 3 must return **4503599627370497** exactly, because
`evaluateLong` returns the value unchanged — so the *same number* rounds to itself as an INT64 and to
itself-plus-one as a FLOAT64. Step 4: `FLOOR` and `CEIL` must both return 4.503599627370497E15,
proving only `ROUND` is affected.
**Vacuity:** step 3 and step 4 both returning the input is what isolates `ROUND`.

## TYPE-132 — ROUND of a negative fraction produces negative zero; ROUND with two arguments is refused
**Intent:** two loose ends. `Math.signum(v) * Math.floor(...)` multiplies −1.0 by 0.0 and gets
**−0.0** for any `v` in (−0.5, 0), and the arity check refuses `ROUND(x, 2)` with a message naming a
workaround.
**Falsifier:** `ROUND(-0.4)` rendering as `0.0` (then record it — something normalised the sign);
`ROUND(x, 2)` planning; the arity refusal naming the wrong argument count.
**Setup:** `rnd.csv` row 7 (`d` = −0.4) and rows 15/16/17 from TYPE-125.
**Steps:**
1. `SELECT id, ROUND(d) AS r FROM rnd WHERE id = 7`
2. `SELECT id FROM rnd WHERE ROUND(d) = 0 AND id = 7` — does `-0.0` compare equal to 0?
3. `SELECT id, ROUND(d) FROM rnd WHERE id IN (16, 17)` — NaN and −Infinity
4. `pravaha validate` on `SELECT ROUND(d, 2) FROM rnd`, `ROUND(d, 0)`, `ABS(d, 1)`, `FLOOR(d, 1)`
**Expected:** step 1 → **`-0.0`**. `Math.signum(-0.4)` is −1.0; `Math.abs(-0.4) + 0.5` is
0.9000000000000000222; `Math.floor` of that is 0.0; −1.0 × 0.0 = −0.0. The printed field is `-0.0`,
which is a different four characters from `0.0` and will differ in a byte-compared expected file.
Step 2 must return row 7: `Double.compare(-0.0, 0.0)` is **−1**, not 0 — so if `CompareExpressions`
uses `Double.compare`, the predicate is **false** and the row is **not** returned, while
`-0.0 == 0.0` is true in Java and in SQL. Record which happens. A `-0.0` that is not equal to `0.0`
is a real defect and this is its cheapest reproduction.
Step 3: `ROUND(NaN)` = `Math.signum(NaN) * Math.floor(NaN + 0.5)` = NaN × NaN = **NaN**.
`ROUND(-Infinity)` = −1.0 × `Math.floor(Infinity)` = −1.0 × Infinity = **−Infinity**.
Step 4: all four are refused with `PRV-2021` and
`ROUND is supported with one argument and was given 2. ROUND to a number of decimal places is not
built; round the value and scale it, or cast it.` — with the function name and the count matching
what was written in each case. `ROUND(d, 0)` is refused too, even though a scale of zero is exactly
what the one-argument form does; record that as a usability finding.

---

## 17. UPPER, LOWER, TRIM, SUBSTRING and `||` (TYPE-133 … TYPE-139)

`TextFunction` uses `Locale.ROOT` deliberately — *"correct for text and wrong for an engine whose
answer must not depend on which machine a lane happens to run on"*. `TRIM` strips **spaces only**,
not whitespace. `Substring` counts **code points**, is 1-based, and defines a start below 1 as
contributing nothing rather than being clamped. `Concat` is null-propagating and text-only.

## TYPE-133 — UPPER and LOWER do not depend on the JVM's default locale
**Intent:** the Turkish dotted/dotless `i` is the standard test, and it is the one that makes an
engine's answer depend on which machine a lane runs on. `Locale.ROOT` must win over `user.language`.
**Falsifier:** `UPPER('i')` returning `İ` (U+0130) under a Turkish default locale; `LOWER('I')`
returning `ı` (U+0131); the same query giving different bytes on two machines.
**Setup:** `loc.csv` — schema `id:INT64,s:STRING?` — with `1,i` / `2,I` / `3,istanbul` /
`4,TITLE` / `5,ß` / `6,İ`.
**Steps:** run `SELECT id, UPPER(s) AS u, LOWER(s) AS l FROM loc` **three times**:
1. with the default locale
2. with `JAVA_TOOL_OPTIONS="-Duser.language=tr -Duser.country=TR"`
3. with `JAVA_TOOL_OPTIONS="-Duser.language=lt -Duser.country=LT"` (Lithuanian, which adds combining
   dots in lowercasing)
**Expected:** **all three runs produce byte-identical output.** The values:
id 1 → `I` / `i`; id 2 → `I` / `i`; id 3 → `ISTANBUL` / `istanbul`; id 4 → `TITLE` / `title`;
id 5 → see TYPE-134; id 6 (`İ`, U+0130) → `İ` / and its ROOT lowercase, which is **`i̇`** — U+0069
followed by U+0307, **two code points from one**. Assert the byte length of that field is 3
(`69 CC 87`), not 1.
**Vacuity:** running once proves nothing about locale independence. The three runs, byte-compared
against each other, are the case.

## TYPE-134 — UPPER can make a string longer
**Intent:** `String.toUpperCase` expands `ß` to `SS` and a few other characters similarly, so a
function documented as "one string in, one string out" changes the length. That matters for a
`VARCHAR(n)` column and for anything that pre-sizes a buffer.
**Falsifier:** `UPPER('ß')` returning `ß` or `S`; a bounded `STRING(1)` output column silently
truncating the result rather than refusing.
**Setup:** `loc.csv` id 5, plus `1,ﬁ` (U+FB01, the fi ligature, which uppercases to `FI`) and
`1,ŉ` (U+0149, which uppercases to two code points).
**Steps:**
1. `SELECT id, UPPER(s) AS u FROM loc` and compare byte lengths
2. `SELECT id, LOWER(UPPER(s)) AS r FROM loc` — the round trip
3. declare the output column as `u:STRING` and check nothing truncates; then, through the
   programmatic path, as `Types.string(1)` and see whether the length bound is enforced at all
**Expected:** id 5 → `SS`, **2 bytes from 1**. `ﬁ` → `FI`, 2 bytes from 3. `ŉ` → `ʼN`, 3 bytes from 2.
Step 2: `LOWER(UPPER('ß'))` = `ss`, **not** `ß` — case conversion is not invertible, and asserting
the round trip fails is the point.
Step 3: `StringType.maxLength` is carried in the type but nothing in `BinaryRowWriter.setString`
consults it, so a bounded column is expected to accept an over-long value silently. Record it: the
bound is documentation, not a constraint.

## TYPE-135 — TRIM strips spaces and only spaces
**Intent:** *"SQL's default trim character is `' '`, not 'whitespace' — Java's `strip()` would also
take tabs and newlines, which is a different function wearing the same name."* Verify the narrower
behaviour, because the wider one looks more helpful and is wrong.
**Falsifier:** a tab, newline, carriage return, form feed or non-breaking space being stripped;
interior spaces being collapsed; a string of only spaces not becoming empty.
**Setup:** `trm.csv` — schema `id:INT64,s:STRING?` — written so the whitespace is exact:
id 1 `··a·b··` (· = space), id 2 `→a→` (→ = U+0009 tab), id 3 `\na\n` (real newlines are impossible
in a line-delimited file — use the SDK for this row, or a delimiter other than `,` and a file with
the row written via `setString`), id 4 `··` (two spaces only), id 5 `a`, id 6 NULL,
id 7 ` a ` (non-breaking space).
**Steps:** `SELECT id, TRIM(s) AS t FROM trm`, out-schema `id:INT64,t:STRING?`; compare byte lengths.
**Expected:** id 1 → `a·b`, 3 bytes — leading and trailing spaces gone, the interior space kept.
id 2 → `→a→` **unchanged**, 3 bytes — tabs are not spaces. id 3 → unchanged. id 4 → the **empty
string**, 0 bytes, and `IS NULL` must be **false** for it. id 5 → `a`. id 6 → **NULL**. id 7 →
unchanged, 5 bytes — U+00A0 is two UTF-8 bytes and is not U+0020.
**Vacuity:** id 4 becoming a zero-length string rather than NULL is the anti-vacuity assertion;
without an `IS NULL` check the two are indistinguishable in the output file.

## TYPE-136 — TRIM's unsupported forms are refused by name
**Intent:** `ExpressionCompiler.trim` checks the normalised three-operand form for `BOTH` and a space
literal and refuses anything else — *"rather than silently given the default, which would return the
input unchanged for anything that has no leading spaces, and look like it worked."*
**Falsifier:** `TRIM(LEADING ' ' FROM s)` planning and behaving as `TRIM(BOTH …)`;
`TRIM('x' FROM s)` planning and stripping spaces instead.
**Setup:** `trm.csv`.
**Steps:** `pravaha validate` on `TRIM(LEADING ' ' FROM s)`, `TRIM(TRAILING ' ' FROM s)`,
`TRIM(BOTH 'x' FROM s)`, `TRIM('x' FROM s)`, `LTRIM(s)`, `RTRIM(s)`; and `TRIM(s)` and
`TRIM(BOTH ' ' FROM s)` as the controls.
**Expected:** the six unsupported forms are refused with `PRV-2021` and
`'<expr>' is not supported: TRIM strips spaces from both ends, and LEADING, TRAILING and a trim
character other than a space are not built.` — except `LTRIM`/`RTRIM`, which are not `TRIM` at all
and will reach the general function refusal with the *"Supported: + - * / %, ABS, FLOOR, CEIL,
ROUND, CASE WHEN, UPPER, LOWER, TRIM, SUBSTRING and ||"* list. Record which message each produces;
the second list does not mention that `TRIM` is restricted to the `BOTH ' '` form, so a user reading
it will write `TRIM(LEADING …)` next and be refused again by a different message.
The two controls must plan and must produce TYPE-135's answers.

## TYPE-137 — SUBSTRING is 1-based and counts code points, not chars
**Intent:** *"Counting chars is the one-line version and it cuts a surrogate pair in half:
`SUBSTRING(emoji FROM 1 FOR 1)` would return half of an emoji, which is not a string at all."*
**Falsifier:** `SUBSTRING(s FROM 1 FOR 1)` over `👍ok` returning a 3-byte lone surrogate, a
replacement character, or an empty string; `FROM 1` returning the second character (0-based).
**Setup:** `text.csv` (id 6 is `👍ok`: three code points, five UTF-16 units, six UTF-8 bytes;
id 5 is `straße`: six code points, seven UTF-8 bytes).
**Steps:** `SELECT id, SUBSTRING(s FROM 1 FOR 1) AS a, SUBSTRING(s FROM 2 FOR 1) AS b,
SUBSTRING(s FROM 2) AS c, SUBSTRING(s FROM 1 FOR 3) AS d FROM txt`, out-schema all `STRING?`;
compare byte lengths.
**Expected, by hand.**
Id 6 (`👍ok`): `a` = **`👍`** (4 bytes) — position 1 is the whole emoji;
`b` = **`o`**; `c` = **`ok`** (2 bytes); `d` = **`👍ok`** (6 bytes, all three code points).
Id 5 (`straße`): `a` = `s`; `b` = `t`; `c` = `traße` (6 bytes — `ß` is 2); `d` = `str`.
Id 1 (`hello world`): `a` = `h`; `b` = `e`; `c` = `ello world`; `d` = `hel`.
Id 2 (`  padded  `): `a` = a single space (1 byte); `b` = a single space; `c` = ` padded  `;
`d` = `  p`.
Id 3 (NULL): all four **NULL** — `Substring.isNull` propagates from the source.
**Vacuity:** the `a` column for id 6 is the whole case; asserting only its *length* (4 bytes) and not
its bytes would pass on any 4-byte garbage, so compare the bytes `F0 9F 91 8D`.

## TYPE-138 — SUBSTRING with a start below 1, a length past the end, and a negative length
**Intent:** *"A start below 1 is not an error… `SUBSTRING(s FROM -1 FOR 4)` returns the first two
characters: positions −1 and 0 contribute nothing. Clamping start to 1 instead — the
obvious-looking fix — would return four characters and quietly disagree with every other database."*
**Falsifier:** `FROM -1 FOR 4` returning four characters; `FROM 0 FOR 3` returning three; a length
past the end throwing; a huge length overflowing into an empty result.
**Setup:** `text.csv` id 1 (`hello world`, 11 code points) and id 6 (`👍ok`, 3 code points).
**Steps:** for id 1, run each of:
`FROM -1 FOR 4`, `FROM 0 FOR 3`, `FROM 0`, `FROM 1 FOR 0`, `FROM 1 FOR -2`, `FROM 5 FOR 100`,
`FROM 12`, `FROM 12 FOR 5`, `FROM 1 FOR 9223372036854775807`, `FROM -9223372036854775808 FOR 3`.
**Expected, with the arithmetic from `Substring.evaluateString` shown** (`total` = 11,
`until = from + max(0, length)`, `first = max(1, from)`, `last = min(total+1, until)`, empty if
`first >= last`):

| form | from | until | first | last | result |
|---|---|---|---|---|---|
| `FROM -1 FOR 4` | −1 | 3 | 1 | 3 | **`he`** (2 chars) |
| `FROM 0 FOR 3` | 0 | 3 | 1 | 3 | **`he`** (2 chars) |
| `FROM 0` | 0 | 12 | 1 | 12 | **`hello world`** (11) |
| `FROM 1 FOR 0` | 1 | 1 | 1 | 1 | **empty** |
| `FROM 1 FOR -2` | 1 | 1 | 1 | 1 | **empty** (`max(0,-2)` = 0) |
| `FROM 5 FOR 100` | 5 | 105 | 5 | 12 | **`o world`** (7) |
| `FROM 12` | 12 | 12 | 12 | 12 | **empty** |
| `FROM 12 FOR 5` | 12 | 17 | 12 | 12 | **empty** |
| `FROM 1 FOR Long.MAX_VALUE` | 1 | overflow-safe in `long` | 1 | 12 | **`hello world`** |
| `FROM Long.MIN_VALUE FOR 3` | MIN | MIN+3 | 1 | MIN+3 | **empty** (`first >= last`) |

The `Long.MAX_VALUE` row is the overflow guard the source calls out — `until` is computed in `long`
so a huge length cannot wrap negative and turn a valid query into an empty string. Assert the full
string, not an empty one.
Repeat `FROM -1 FOR 4` for id 6 (`👍ok`): from −1, until 3, first 1, last 3 → the first **two code
points**, `👍o`, **5 bytes**. A char-counting implementation would return `👍` and half of nothing.
**Vacuity:** four of the ten forms expect an empty string, which is what a wholly broken
`SUBSTRING` also returns. The six non-empty expectations in the same run are what make them
meaningful.

## TYPE-139 — `||`: flattening, arity, NULL, and a non-text operand
**Intent:** `Concat` requires at least two parts, all of type STRING, flattens nested chains, and is
null if any part is. Each of those is a separate branch.
**Falsifier:** `a || b || c` producing an intermediate string (observable as a different `EXPLAIN`
shape, or as a different fingerprint for two spellings of the same query); a numeric operand
silently stringified; NULL behaving as an empty string (covered in TYPE-086, restated here as the
arity/type case).
**Setup:** `text.csv`.
**Steps:**
1. `SELECT id, s || '-' || t AS c FROM txt` and `pravaha explain --level physical` on it
2. `SELECT id, (s || '-') || t AS c FROM txt` — the explicitly nested spelling; compare the plans
3. `SELECT id, CONCAT(s, '-', t) AS c FROM txt` — the function spelling Calcite rewrites
4. `SELECT id, s || 1 AS c FROM txt` — a numeric operand
5. `SELECT id, s || CAST(1 AS VARCHAR) AS c FROM txt` — the suggested rewrite
6. `SELECT id, id || s AS c FROM txt` — a BIGINT column on the left
**Expected:** 1, 2 and 3 produce the **identical column**: `hello world-HELLO`, `  padded  -x`,
NULL, `a.com-z`, `straße-ß`, `👍ok-e`, `100%-%`, `axcom-q`. Their `EXPLAIN` output must render as a
single `a || b || c`, not as a nested pair — `concat` flattens at compile time, and two spellings of
the same query producing different plan text would give them different fingerprints and therefore
separate computations, which is the sharing this engine exists to do.
4 and 6 are refused. Record where: if Calcite inserts a `CAST(1 AS VARCHAR)` first, the refusal comes
from `ExpressionCompiler.typeOf` as `PRV-2021` with `'…' has SQL type VARCHAR, which Pravaha cannot
compute with yet` — a message about VARCHAR for a query about a number. If it reaches the `Concat`
constructor instead, the refusal is a raw
`IllegalArgumentException: || joins text, and one side produces INT64. Wrap it in CAST(… AS VARCHAR)
if that is what you meant` — good advice with no code, and advice that **case 5 will show does not
work**.
5 must be refused for the same VARCHAR reason. That makes the constructor's suggested rewrite
un-followable, which is the finding: *a refusal that recommends a construct the engine also refuses*.

---

## 18. LIKE and NOT LIKE (TYPE-140 … TYPE-144)

`Predicate.Like` translates the SQL pattern to a regex once per query, quoting everything that is
not `%` or `_`, and matches with `Pattern.matches` semantics (whole string) under `DOTALL`. There is
no escape character, and `ESCAPE` is refused at compile time.

## TYPE-140 — LIKE is anchored at both ends
**Intent:** `matches()` requires the whole string, so `LIKE 'abc'` is equality and `LIKE 'a%'` is a
prefix test. An implementation using `find()` would make every pattern a substring search — which
returns more rows and looks like it works.
**Falsifier:** `s LIKE 'ello'` matching `hello world`; `s LIKE 'a'` matching `a.com`.
**Setup:** `text.csv`.
**Steps:** `WHERE s LIKE 'a.com'`, `'a'`, `'a%'`, `'%com'`, `'%o%'`, `'ello'`, `'%'`, `''`.
**Expected:** `'a.com'` → `4`. `'a'` → **0 rows**. `'a%'` → `4,8`. `'%com'` → `4,8`.
`'%o%'` → **`1,4,6,8`**. The eight `s` values are `hello world`, `  padded  `, NULL, `a.com`,
`straße`, `👍ok`, `100%`, `axcom`; those containing the letter `o` are ids 1, 4, 6 and 8.
`'ello'` → **0 rows** — the anchoring assertion.
`'%'` → `1,2,4,5,6,7,8` (seven; NULL excluded). `''` → **0 rows** — no string in the fixture is
empty; add an SDK-written empty-string row and assert `LIKE ''` matches it and nothing else.
**Vacuity:** `'ello'` and `'a'` returning zero while `'%o%'` returns four in the same run is what
makes the zeros meaningful.

## TYPE-141 — regex metacharacters in a pattern are literal
**Intent:** *"a user writing `LIKE '%.com'` means a dot."* `toRegex` quotes every literal run with
`Pattern.quote`, so `.`, `*`, `+`, `[`, `(`, `\`, `$`, `^`, `|` and `?` are characters.
**Falsifier:** `LIKE '%.com'` matching `axcom`; `LIKE '100%'`'s `%` being taken literally (it is not
— see TYPE-144); a pattern containing `\E` breaking the quoting and being interpreted as a regex.
**Setup:** `text.csv`, plus rows `9,a+b,q` / `10,x|y,q` / `11,c\Ed,q` / `12,(z),q`.
**Steps:** `WHERE s LIKE '%.com'`, `'a.c%'`, `'a+b'`, `'x|y'`, `'(z)'`, `'c\Ed'`, and the control
`'a_com'`.
**Expected:** `'%.com'` → **`4` only**. `axcom` (id 8) must **not** match: a regex `.` would match
the `x`. That single exclusion is the case.
`'a.c%'` → `4` only, for the same reason.
`'a+b'` → `9`; `'x|y'` → `10`; `'(z)'` → `12` — each matching exactly its own row and nothing else,
which a regex interpretation would not do (`a+b` would match `aab`, `x|y` would match any row
containing `x` or `y`).
`'c\Ed'` → `11`. This is the `Pattern.quote` boundary case: `quote` wraps the literal in `\Q…\E`,
and a literal containing `\E` terminates the quoting early unless `quote` escapes it — it does, so
the row must match and no exception may be thrown. A `PatternSyntaxException` at registration here is
a finding.
`'a_com'` → **`8`** (`axcom`) and **not** `4` — `_` matches exactly one character, and `a.com` has a
`.` in that position so it matches too; recompute: `a_com` is `a` + any + `com`, which matches both
`a.com` and `axcom` → **`4,8`**. That pair is the control proving `_` is still a wildcard while `.`
is not.

## TYPE-142 — `_` matches exactly one character, including a newline
**Intent:** `DOTALL` is set *because* SQL's `_` matches any character and a regex `.` does not match
a newline by default. Also: `_` counts UTF-16 code units, not code points, so it will cut an
astral character in half — establish which.
**Falsifier:** `_` matching zero or two characters; `_` failing to match a newline; a pattern of
three `_` matching `👍ok` (three code points but **four** UTF-16 units).
**Setup:** `text.csv` plus an SDK-written row `13` whose `s` is `a\nb` (a real newline).
**Steps:** `WHERE s LIKE 'a_b'` against row 13; `WHERE s LIKE '___'` (three underscores);
`WHERE s LIKE '____'` (four); `WHERE s LIKE '_ok'`; `WHERE s LIKE '__ok'`;
`WHERE s LIKE 'stra_e'`.
**Expected:** `'a_b'` → row **13**; the newline is matched, which requires `DOTALL`.
`'___'` matches strings of exactly three UTF-16 units: none of the fixture's values are three units
(`hello world` 11, `  padded  ` 10, `a.com` 5, `straße` 6, `👍ok` **4**, `100%` 4, `axcom` 5,
`a+b` 3, `x|y` 3, `c\Ed` 4, `(z)` 3, `a\nb` 3) → **`9,10,12,13`**.
`'____'` → **`6,7,11`** — `👍ok` is four UTF-16 units, so it matches here and **not** under `'___'`.
That is the finding: `SUBSTRING` counts code points (TYPE-137) and `LIKE`'s `_` counts UTF-16 units,
so the two functions disagree about the length of the same string. Record it.
`'_ok'` → **0 rows** (`👍ok` needs two units for the emoji); `'__ok'` → **`6`**.
`'stra_e'` → **`5`** — `ß` is one UTF-16 unit.
**Vacuity:** the `'___'` / `'____'` pair differing by exactly id 6 is the assertion; either query
alone would be uninformative.

## TYPE-143 — NULL is dropped by LIKE and by NOT LIKE
**Intent:** restated from TYPE-085 as a LIKE-section case, with the additional check that
`NOT LIKE` compiles to the `negated` flag rather than to a wrapping `NOT`.
**Falsifier:** the NULL row appearing under `NOT LIKE`; `NOT (s LIKE p)` and `s NOT LIKE p` returning
different sets.
**Setup:** `text.csv`; `s` is NULL at id 3.
**Steps:** `WHERE s LIKE 'a%'`; `WHERE s NOT LIKE 'a%'`; `WHERE NOT (s LIKE 'a%')`;
`WHERE s NOT LIKE 'a%' OR s IS NULL`; `pravaha explain --level physical` on the second and third.
**Expected:** `LIKE 'a%'` → `4,8`. Both `NOT` spellings → the **same five rows** `1,2,5,6,7`.
2 + 5 = 7 = 8 − 1. The fourth query → `1,2,3,5,6,7` — six rows, which is how the NULL row is
recovered when that is what the author wanted.
The two `EXPLAIN` outputs must be **identical**, rendering as `s NOT LIKE 'a%'` — a single
`Like` node with `negated = true`, not a `Not` wrapping a `Like`. Different plan text would mean
different fingerprints for the same query and separate computations for one question.

## TYPE-144 — ESCAPE is refused, and a literal `%` is therefore unmatchable
**Intent:** the honest consequence of having no escape character, stated in the source: *"Without
ESCAPE, `%` and `_` are always wildcards and there is no way to match them literally."* The case
proves both halves — the refusal, and the gap it leaves.
**Falsifier:** `ESCAPE` planning; the refusal message not mentioning why; a pattern with a literal
`%` silently matching only the literal (which would mean an undocumented escape exists).
**Setup:** `text.csv` id 7 is `100%`; add `14,100,q` and `15,1000,q`.
**Steps:**
1. `pravaha validate` on `WHERE s LIKE '100\%' ESCAPE '\'`
2. `WHERE s LIKE '100%'`
3. `WHERE s LIKE '%\%%'` — trying to find rows containing a percent sign
4. `WHERE s = '100%'` — the workaround
5. `pravaha validate` on `WHERE s LIKE t` (a pattern that is not a literal)
6. `pravaha validate` on `WHERE UPPER(s) LIKE 'A%'` (a left side that is not a column)
**Expected:** 1 is refused with `PRV-2021` and `'<expr>' uses LIKE with an ESCAPE clause, which is
not built. Without ESCAPE, % and _ are always wildcards and there is no way to match them
literally.`
2 → **`7,14,15`** — `100%` is `100` followed by anything, so it matches `100`, `1000` **and**
`100%`. The row the author wanted is one of three, and there is no pattern that selects it alone.
3 → the `\` is a literal backslash and `%` are wildcards, so the pattern is `anything + \ + anything`
→ **0 rows** (no value contains a backslash except `c\Ed`, id 11, which **does** → **`11`**). Record
the actual result; either way it is not "the rows containing a percent sign".
4 → **`7`** only. That is the workaround, and it only works for an exact match.
5 is refused with `'<expr>' uses a pattern that is not a literal. The pattern is compiled once when
the query is registered; one that varies per row would be compiled per row.`
6 is refused with `'<expr>' matches something other than a column. LIKE is supported as column LIKE
'pattern'.`
All three refusals are honest and each names its own reason; assert the text of each, since they come
from three different branches of `PredicateCompiler.like`.

---

## 19. CAST (TYPE-145 … TYPE-150)

`ExpressionCompiler.cast` accepts a conversion only when **both** the source and the target are in
`isNumeric` — `INT8 INT16 INT32 INT64 FLOAT32 FLOAT64`. `BOOLEAN`, `STRING`, `BYTES`, `DATE`,
`TIME`, `TIMESTAMP_LTZ`, `DECIMAL` and the nested types are all refused, in either direction.
Narrowing truncates towards zero. `Cast.describe` is deliberately invisible in `EXPLAIN`.

## TYPE-145 — CAST between the six numeric types: the thirty ordered pairs
**Intent:** the full numeric cast matrix. Six sources × five targets is thirty conversions, plus six
identity casts that `cast` short-circuits (`source.type() == target ? source : new Cast(...)`).
**Falsifier:** any pair refused; any identity cast producing a `Cast` node (visible as a change in
`EXPLAIN` or in the fingerprint); a widening conversion losing a value.
**Setup:** `cast.csv` — schema
`id:INT64,i8:INT8?,i16:INT16?,i32:INT32?,i64:INT64?,f32:FLOAT32?,f64:FLOAT64?` — with
`1,100,100,100,100,100.0,100.0` / `2,-100,-100,-100,-100,-100.0,-100.0` /
`3,0,0,0,0,0.0,0.0` / `4,,,,,,` (all NULL).
**Steps:** for each of the six source columns, project
`CAST(<col> AS TINYINT)`, `SMALLINT`, `INTEGER`, `BIGINT`, `REAL`, `DOUBLE` — thirty-six
projections in all, sixteen of which can be batched into six queries of six columns each.
Then `pravaha explain --level physical` on `SELECT CAST(i64 AS BIGINT) FROM cst`.
**Expected:** all thirty-six plan. Row 1 gives **100** (or `100.0` for the float targets) from every
source; row 2 gives **−100** / `−100.0`; row 3 gives **0** / `0.0`; row 4 gives **NULL** from all
thirty-six — `Cast.isNull` delegates to the source.
The magnitudes are chosen to fit every target, so any value other than ±100 or 0 identifies the
failing pair precisely.
The `EXPLAIN` of the identity cast must render as `i64` alone, with no `CAST` — proving the
short-circuit fired. If a `Cast` node appears, `CAST(x AS BIGINT)` and `x` have different plan text
and therefore different fingerprints, and the same question registered two ways gets two
computations.
**Vacuity:** thirty-six results asserted individually. A single "casts work" assertion on one pair
would leave thirty-five untested, which is exactly the failure the authoring contract names.

## TYPE-146 — narrowing a float to an integer truncates towards zero
**Intent:** `Cast.evaluateLong` does `(long) source.evaluateDouble(row)` — a Java narrowing cast,
which truncates rather than rounds, and truncates towards zero rather than towards negative
infinity. Three different wrong answers are plausible here and only one is right.
**Falsifier:** `CAST(-3.9 AS BIGINT)` returning −4 (floor) or −3.9 rounded to −4 (round-half);
`CAST(3.9 AS BIGINT)` returning 4.
**Setup:** `trunc.csv` — schema `id:INT64,f:FLOAT64?` — with
`1,3.9` / `2,-3.9` / `3,3.5` / `4,-3.5` / `5,0.9` / `6,-0.9` / `7,-0.0` / `8,NaN`.
**Steps:** `SELECT id, CAST(f AS BIGINT) AS c FROM trunc` with an INT64 out-schema; then
`SELECT id, CAST(f AS INTEGER) AS c FROM trunc`; then `SELECT id, FLOOR(f), ROUND(f) FROM trunc` for
the contrast.
**Expected:** `CAST(f AS BIGINT)`: id 1 → **3**; id 2 → **−3**; id 3 → **3**; id 4 → **−3**;
id 5 → **0**; id 6 → **0**; id 7 → **0**; id 8 → **0** (`(long) NaN` is 0 in Java — a silent and
very surprising conversion; assert it and record it as a hazard).
Contrast in the same run: `FLOOR(f)` gives 3.0, **−4.0**, 3.0, −4.0, 0.0, **−1.0**, −0.0, NaN;
`ROUND(f)` gives 4.0, −4.0, 4.0, −4.0, 1.0, −1.0, −0.0, NaN. Three functions, three different
answers for id 2 (−3, −4, −4) and for id 6 (0, −1, −1). That table is the case.
**Vacuity:** ids 2 and 6 discriminate truncation from flooring; ids 1 and 3 discriminate truncation
from rounding. All four in one run.

## TYPE-147 — CAST from a float too large for the target saturates silently
**Intent:** Java's `(long)` narrowing of an out-of-range double **clamps** to `Long.MAX_VALUE` /
`Long.MIN_VALUE` rather than wrapping or throwing. A price of 1e300 becomes 9223372036854775807,
which is a number somebody will then add up.
**Falsifier:** the cast throwing (the honest behaviour — record it as fixed); the cast wrapping to an
arbitrary value; `CAST(NaN AS BIGINT)` returning anything but 0.
**Setup:** `sat.csv` — schema `id:INT64,f:FLOAT64?` — with
`1,1.0E300` / `2,-1.0E300` / `3,Infinity` / `4,-Infinity` / `5,NaN` / `6,9.3E18` /
`7,1.0E10`.
**Steps:** `SELECT id, CAST(f AS BIGINT) AS c FROM sat`; then `CAST(f AS INTEGER)`;
then `CAST(f AS TINYINT)`.
**Expected:** `AS BIGINT`: id 1 → **9223372036854775807**; id 2 → **−9223372036854775808**;
id 3 → **9223372036854775807**; id 4 → **−9223372036854775808**; id 5 → **0**; id 6 → 9.3e18 is above
`Long.MAX_VALUE` (9.223e18) → **9223372036854775807**; id 7 → **10000000000**, correct.
Exit 0 throughout — every one of those is a silently wrong answer under a success status, and id 7
proves the mechanism works for in-range values.
`AS INTEGER` then applies `writeComputed`'s `(int)` on top: `(int) 9223372036854775807L` is
**−1**, so id 1 becomes **−1** and id 2 becomes **0**. Two narrowings in series, each silent.
`AS TINYINT`: `(byte)` of the clamped long — id 1 → **−1**, id 2 → **0**.
**Vacuity:** id 7's correct 10000000000 in the same run rules out a wholesale failure.

## TYPE-148 — CAST from a wide integer to a narrow one wraps
**Intent:** the integer half of TYPE-147. `Cast.evaluateLong` returns the source's long unchanged for
an integer source — the **narrowing happens only in `writeComputed`**, as a plain Java cast. So the
cast node itself is a no-op and the loss occurs at the write, which is why it cannot be detected by
inspecting the expression.
**Falsifier:** the cast throwing; the value surviving (which would mean the output column was wider
than declared).
**Setup:** `cast.csv` extended with `5,0,0,0,4294967296,0.0,0.0` and
`6,0,0,0,257,0.0,0.0` and `7,0,0,0,65537,0.0,0.0`.
**Steps:** `SELECT id, CAST(i64 AS INTEGER) AS c FROM cst` (out-schema INT32);
`CAST(i64 AS SMALLINT)` (INT16); `CAST(i64 AS TINYINT)` (INT8).
**Expected:** row 5 (`i64` = 4294967296 = 2³²): `AS INTEGER` → `(int) 4294967296L` = **0**;
`AS SMALLINT` → `(short)` of it = **0**; `AS TINYINT` → **0**.
Row 6 (`i64` = 257): `AS INTEGER` → 257; `AS SMALLINT` → 257; `AS TINYINT` → `(byte) 257` = **1**.
Row 7 (`i64` = 65537 = 2¹⁶+1): `AS INTEGER` → 65537; `AS SMALLINT` → `(short) 65537` = **1**;
`AS TINYINT` → `(byte) 65537` = **1**.
Rows 1–3 (±100, 0) survive every narrowing unchanged, which is the control.
Exit 0 throughout. Every wrapped value is a silently wrong answer; record the three.

## TYPE-149 — CAST to or from a non-numeric type is refused, by name
**Intent:** `isNumeric` excludes `BOOLEAN`, `STRING`, `BYTES`, `DATE`, `TIME`, `TIMESTAMP_LTZ`,
`DECIMAL` and the nested types. Several of those are casts every other SQL engine performs, and the
refusal is the engine's honest position — but it has to *be* a refusal, with a code, naming both
types.
**Falsifier:** any of them planning; a refusal that is a `ClassCastException` or an
`IllegalArgumentException` rather than `PRV-2021`; a refusal that names neither type.
**Setup:** `types.csv`.
**Steps:** `pravaha validate` on each of:
1. `CAST(s AS BIGINT)` — text to number
2. `CAST(i64 AS VARCHAR)` — number to text
3. `CAST(b AS INTEGER)` — boolean to number
4. `CAST(i64 AS BOOLEAN)`
5. `CAST(ts AS BIGINT)` — timestamp to epoch nanoseconds
6. `CAST(i64 AS TIMESTAMP)` — epoch nanoseconds to timestamp
7. `CAST(i64 AS DECIMAL(20,2))`
8. `CAST(bin AS VARCHAR)`
9. `CAST(s AS VARCHAR)` — the identity, which the short-circuit should accept
**Expected:** 1, 3, 4 and 8 are refused with `PRV-2021` and
`'<expr>' converts between <SOURCE> and <TARGET>; Pravaha evaluates numeric conversions only`, with
both type names present. 2, 6 and 8 may instead be refused earlier by `typeOf`, as
`'<expr>' has SQL type VARCHAR, which Pravaha cannot compute with yet` — record which branch each
takes, because only the first message explains the policy.
5 is refused: `TIMESTAMP_LTZ` is not numeric, so **there is no supported way to get an instant out
of the engine as a number**. That is worth recording on its own: a user who wants epoch nanoseconds
has no expression that produces them.
7 is refused with the DECIMAL sentence from `refuseDecimalType`, which speaks of "DECIMAL
arithmetic" for what is a pure conversion — an inaccurate message for a correct refusal.
9 should be accepted by the `source.type() == target` short-circuit and return `s` unchanged; if it
is refused, the short-circuit is not reached for text and an identity cast is an error.
**Vacuity:** case 9 succeeding is what proves the refusals are about the type pair and not about
`CAST` being unsupported wholesale.

## TYPE-150 — `SUM(CAST(price AS BIGINT))`: the workaround the refusal recommends
**Intent:** TYPE-037 and TYPE-038 refuse floating-point aggregates and tell the user to write
`SUM(CAST(price AS BIGINT))`. A refusal whose suggested rewrite does not work is worse than no
suggestion. This is the case that closes the loop, and it is the last one because it depends on
almost everything above it.
**Falsifier:** the rewrite being refused; the rewrite planning and dying at run time the way
TYPE-034 does; the rewrite producing a number that is not the hand-computed truncated sum.
**Setup:** `price.csv` — schema `id:INT64,u:STRING,price:FLOAT64?,pf:FLOAT32?` — with
```
1,u1,10.5,10.5
2,u1,20.4,20.4
3,u2,-3.7,-3.7
4,u2,,
5,u1,0.5,0.5
```
**Steps:**
1. `SELECT SUM(price) FROM price` — the refusal, to capture the advice
2. `SELECT SUM(CAST(price AS BIGINT)) FROM price` with `--out-schema "v:INT64?"`
3. `SELECT MIN(CAST(price AS BIGINT)), MAX(CAST(price AS BIGINT)), AVG(CAST(price AS BIGINT))
   FROM price`
4. `SELECT SUM(CAST(pf AS BIGINT)) FROM price` — the FLOAT32 source
5. `SELECT COUNT(CAST(price AS BIGINT)) FROM price`
6. `SELECT u, SUM(CAST(price AS BIGINT)) FROM price GROUP BY u` over a **view**, to check the keyed
   path too
7. `pravaha explain --level physical` on step 2 — is there a `ComputeOperator` below the aggregate?
**Expected:** step 1 is refused with the message quoted in TYPE-038.
Step 2 must **succeed**. Hand-computed: `CAST` truncates towards zero, so the values become
10.5 → **10**, 20.4 → **20**, −3.7 → **−3**, NULL → NULL, 0.5 → **0**.
Sum = 10 + 20 + (−3) + 0 = **27**. Note what the rewrite costs: the true sum of the prices is
10.5 + 20.4 − 3.7 + 0.5 = **27.7**, so the advice loses 0.7 — and the refusal's own wording
("if the rounding is acceptable") says so. Assert **27**, and record 27.7 beside it so the size of
the compromise is on the record.
Step 3: `MIN` = **−3**, `MAX` = **20**, `AVG` = 27 / 4 = **6** (truncating) or **6.75** if the
average is computed in a wider type — record which, and which divisor was used.
Step 4 must succeed with the same **27**.
Step 5 = **4**.
Step 6 must give `u1` → 10 + 20 + 0 = **30**, `u2` → **−3**; 30 + (−3) = 27, matching step 2, which
is the cross-check between the global and keyed paths.
Step 7 must show a `ComputeOperator` feeding the aggregate, with the aggregate's argument column
typed `BIGINT`. That is *why* the rewrite works — `refuseFloatingPointAggregate` inspects the
aggregate's **input schema**, which is the compute stage's output, not the original column.
**Vacuity:** step 6's two group totals summing to step 2's global total is the anti-vacuity device:
two independently computed numbers that a broken accumulator cannot make agree by accident.

---

## Coverage note

**150 cases, the budget the index gives this area, and the budget is too small by roughly a third.**
This is what had to be compressed to fit, and what a later wave should expand.

### What the grid actually costs

The index describes `TYPE` as "16 types × 11 positions". Written out literally that is 176 cases
before a single expression is tested, and the brief also requires the expression layer in full:
five arithmetic operators over every numeric pair, `CASE WHEN` in six aspects, four numeric
functions including two known rounding defects, five text functions, `LIKE` in five aspects, and
`CAST` between every pair of types. Costed honestly that is about **215 cases**. Sections 1–12 here
carry 101 of them and sections 13–19 carry 49.

### Where enumeration was compressed, and how

The authoring contract says a combination is a case. Where a position's sixteen types share **one
line of code** and differ only in the value, the sixteen have been enumerated **inside** one case —
as a table or an explicit list, with each type's own value and its own expected result — rather than
gestured at. The enumeration is still written down; it just costs one ID instead of sixteen. That
applies to:

- **TYPE-069** (four integer widths on the wire), **TYPE-070** (two float widths),
  **TYPE-047** (INT8 and INT16 join keys), **TYPE-054** (four integer group keys),
  **TYPE-105** (INT16 and INT8 arithmetic), **TYPE-145** (thirty-six numeric CAST pairs).
- **ARRAY, MAP and ROW** are treated as one type throughout, in TYPE-005, TYPE-020, TYPE-032,
  TYPE-051 and TYPE-077. They share `TypeMapping`'s single `case ARRAY, MAP, ROW -> ANY` and there
  is no code path on which they differ. If one is ever implemented separately, this collapses and
  the three need splitting.

### What is not covered here at all, and who should own it

- **DECIMAL arithmetic semantics** — precision, scale, rounding mode, the 128-bit two-word
  representation, `Decimals.toBigInteger`. Everything decimal in this file stops at a refusal,
  because that is where the product stops. When the arithmetic is built it needs its own area.
- **The code-generated path.** Every case here exercises `InterpretedPipeline`.
  `PredicateSource`/`FilterProjectGenerator` emit Java from the same IR, and the differential tests
  that compare the two are the thing that makes the IR's dual-consumer design worth having. A
  generated path that disagrees with the interpreted one on a narrow integer or on `-0.0` would pass
  every case in this file. **This is the single largest gap** and it is worth an area of its own.
- **Checkpoint and restore round trips per type.** `SlicedAggregateState.writeKeyValues` serialises
  boxed key values; whether a `Byte` key survives a checkpoint as a `Byte` and not as an `Integer`
  decides whether groups merge after a restart. That belongs to `STATE`, but nobody will write it
  there unless it is named here.
- **Per-type behaviour under retraction.** `RowValues.equal` refuses `BYTES`, `ARRAY`, `MAP` and
  `ROW` for row equality, so a retraction of a row containing any of them cannot cancel its insert
  and state grows for ever. `INCR` owns it; TYPE-042 touches the edge of it.
- **The JDBC, Aerospike and Delta source plugins' own type mappings.** `JdbcTypes` has a `DECIMAL`
  case that the filesystem plugin does not, so the reachable type set differs by plugin. `AERO` and
  `INGEST` own that; it means the answer to "which types can be declared" in TYPE-002 through
  TYPE-005 is *plugin-specific* and this file measures only the filesystem plugin.
- **`STRING(n)` and `BYTES(n)` length bounds.** TYPE-134 establishes that nothing enforces them;
  a full treatment (are they carried through a projection? through the wire schema? through a
  checkpoint?) needs about six more cases.

### Two things worth flagging to whoever schedules the execution wave

1. **TYPE-030 should be run last, or in an isolated node.** If the `AssertionError` from a
   `TIMESTAMP` literal does kill a Flight worker thread, every case scheduled after it on the same
   node is invalidated, and the invalidation will look like unrelated failures.
2. **Six of the sixteen types cannot be reached without writing Java.** TYPE-019, TYPE-020,
   TYPE-032, TYPE-058, TYPE-074, TYPE-075, TYPE-076 and TYPE-077 all need a programmatic
   `StreamSchema` and an embedded node. If that harness is not built, those eight cases are blocked
   and the coverage of `DECIMAL`, `DATE`, `TIME`, `ARRAY`, `MAP` and `ROW` drops to "cannot be
   declared" — which is a true finding but a thin one.
