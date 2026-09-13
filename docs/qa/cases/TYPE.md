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
