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
