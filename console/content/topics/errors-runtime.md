---
title: Runtime codes (PRV-3xxx)
slug: errors-runtime
category: errors
order: 40
icon: cpu
summary: "PRV-3001 to PRV-3102: a query that planned and could not keep running — memory too small, a lane that died, an aggregate or join the runtime will not do, a total past 64 bits, a bad event time, a 65th column, codegen."
badge: PRV-3XXX
audience: Operators
keywords: [arena, slab, inbox, cell, lane, backpressure, window span, epoch, 64 columns, codegen, generated code, lane failed, min max retraction, overflow, sum overflow, PRV-3025, hop, window too fine, PRV-3026, max-windows-per-row]
guide: troubleshooting#it-ran-out-of-memory-the-disk-filled
related: [lanes, errors-state, event-time-watermarks, query-lifecycle, errors-overview]
listed_on: errors-overview
---

A 3xxx code means the query **planned** — the SQL was fine — and something went wrong while it ran:
the memory it was given was too small for the rows it met, a lane died, the data carried an event
time that makes no sense, or a row was wider than a row can be. Most of these are sizing or data
problems, and each message names the setting to change or the value to look at.

When a lane fails, the query on it moves to `FAILED`, and **its view refuses reads** (PRV-8004)
rather than serving a snapshot frozen at the failure. The failure line on the query's page in this
console shows the code that stopped it.

| Code | Name | Usually means |
|---|---|---|
| PRV-3001 | RUNTIME_ARENA_EXHAUSTED | A batch's output did not fit the lane's scratch memory |
| PRV-3002 | RUNTIME_BACKPRESSURED | A row does not fit an inbox cell, or the inbox filled when it should not have |
| PRV-3010 | RUNTIME_LANE_FAILED | The lane running the query stopped; the cause follows in the message |
| PRV-3020 | RUNTIME_UNSUPPORTED_AGGREGATE | An aggregate the runtime cannot maintain for this input |
| PRV-3021 | RUNTIME_UNSUPPORTED_JOIN | A join key of a type that cannot be compared exactly |
| PRV-3022 | RUNTIME_WINDOW_SPAN_IMPLAUSIBLE | One row's event time is far from the rest |
| PRV-3024 | RUNTIME_RETRACTED_UNHELD_ROW | A top-N was asked to retract a row it does not hold |
| PRV-3025 | RUNTIME_AGGREGATE_OVERFLOW | A `SUM`, `COUNT` or `AVG` total left the 64-bit range |
| PRV-3026 | RUNTIME_WINDOW_TOO_FINE | A hop so fine each row lands in too many windows; refused at registration |
| PRV-3030 | ROW_FIELD_LIMIT_EXCEEDED | A row with more than 64 columns |
| PRV-3100 | CODEGEN_COMPILATION_FAILED | Generated code did not compile |
| PRV-3101 | CODEGEN_UNSUPPORTED_OPERATOR | A stage the generator does not emit; the interpreter runs it |
| PRV-3102 | CODEGEN_STAGE_TOO_LARGE | A generated stage too large for the JIT |

## Memory the lane was given

A lane has two pieces of memory an operator can size: an **inbox** of fixed-size cells that rows
arrive in, and an **arena** of slabs that operators write their output batches into. The settings
are `pravaha.lane.inbox.cells`, `pravaha.lane.inbox.cell-bytes`, `pravaha.lane.arena.slab-bytes`,
`pravaha.lane.arena.max-slabs` and `pravaha.lane.batch-size` — see
[Sizing and lanes](/help/topics/lanes#sizing-lanes).

### PRV-3001 — arena exhausted

An operator had no room in the lane's arena for the output it needed to write: "the compute stage's
arena is full; raise `pravaha.lane.arena.slab-bytes` or reduce `pravaha.lane.batch-size`", or the same
for a projection, a lookup join, a window result or an aggregate result. It is usually a batch far
larger than expected, or a slab sized for narrower rows than the query produces.

**The rule:** `batch-size × widest output row` must fit in one slab. With the defaults (512 rows,
4 MiB slabs) a row may be up to about 8 KiB. Either raise the slab or make batches smaller:

```yaml
pravaha:
  lane:
    batch-size: 256
    arena:
      slab-bytes: 8388608
```

These are real settings since ADR-036. Before that, eleven messages told operators to raise
`arena.slab.size` and `lane.inbox.cell.size`, neither of which existed (PF-3) — a test now fails the
build if any message names a setting `application.yaml` does not declare.

### PRV-3002 — backpressured

Two situations share this code. The one you will meet: **a row does not fit an inbox cell.** Checked
when a source is attached, not at the first row:

```text
PRV-3002  stream 'readings' needs at least 600 bytes a row but lane 3's inbox cells are 512. Raise
pravaha.lane.inbox.cell-bytes; a row that cannot fit is not a runtime condition.
```

(The byte counts are the ones the message computes for your stream and lane.) Raise
`pravaha.lane.inbox.cell-bytes` above the widest row the stream carries. This matters most when you
have **sized cells down** to save memory on a node with many queries — 256-byte cells hold only
narrow rows.

The other: the inbox filled during a poll that was sized to fit it. That means a second producer is
writing to a single-writer inbox, or the free-cell arithmetic is wrong — a defect worth reporting with
the message, not a setting to change.

### PRV-3010 — lane failed

The lane running a query stopped, and the message carries the cause: an operator threw, a sink
de-duplicator found an inconsistency, or a **checkpoint could not be restored** into this plan —
because the snapshot is not a Pravaha operator snapshot, because it is from another snapshot version
("replay the stream from a source offset instead"), or because the query changed since the checkpoint
was taken and restoring part of it would resume with some operators holding history and others empty.

The query is now `FAILED` and its view refuses reads with PRV-8004. **Do:** read the cause, fix it,
then drop and register the query again. A failed query's rows were correct as of the failure; the
engine will not hand them over as though they were current.

## What the runtime will not maintain

### PRV-3024 — a top-N asked to retract a row it does not hold

A `ROW_NUMBER() OVER (PARTITION BY ... ORDER BY ...)` filtered to `rn <= N` is maintained as a top-N:
it holds every row of each partition and emits a `-1` and a `+1` wherever the first N change. A
retraction of a row it never received means the stream upstream sent a `-1` with no matching `+1`,
which is a fault upstream and not something the top-N can repair — so it stops by name rather than
emitting an answer it cannot vouch for. Look at the source feeding the query: a source that emits
deletes must emit each row's insert first.

### PRV-3025 — a total left the 64-bit range

`SUM`, `COUNT` and `AVG` accumulate in 64 bits, and a total past `9223372036854775807` either way —
of a `BIGINT` column, or of a `DECIMAL` column's unscaled value, so `92233720368547758.07` at scale 2 —
is refused, naming the aggregate (`SUM(amount)`), rather than wrapped round to a wrong number that
looks like a right one. Every addition is checked: a row, a retraction (which subtracts), a
pushed-down partial, and a window's total when its slices are combined — each slice can fit and their
sum not. A batch is netted in 128 bits first, so a `SUM` that passes the range inside one batch and
comes back (`+MAX`, then `-MAX`) is answered; only a total the batch ends outside the range is refused.
A continuous query moves to `FAILED`; a read is refused. Aggregate a smaller quantity (scale
the column down, `SUM(amount / 1000)`), group by a key that splits the total, or filter out the rows
that carry it.

### PRV-3020 — unsupported aggregate

An aggregate the runtime cannot maintain incrementally for this input:

- **`MIN` or `MAX` meeting a retraction.** Restoring the previous extreme after the current one is
  withdrawn needs an ordered multiset per group, which is not built. Over an insert-only stream
  `MIN`/`MAX` are fine; fed by a source that carries deletes (a `filesystem` source with
  `op.column`), they are refused at the first retraction. Use `SUM` or `COUNT`, or keep retractions
  out of that query.
- **`COUNT(DISTINCT ...)` over a column type** it has no distinct encoding for.
- **A windowed aggregate at its accumulator ceiling** — it holds as many (key, slice) accumulators as
  it was allowed, and one more is needed. Either the key space is unbounded, which no window fixes,
  or the window is too wide for the key count: narrow the window or add a key predicate.

### PRV-3021 — unsupported join

A join on a key the engine cannot compare exactly. A column type with no key encoding is refused
("join on a scalar column"), and so is a **floating-point** key, because floating-point equality drops
rows that differ only by rounding. Round or cast the key to an integer type, or join on another column.

## Data that betrays itself

### PRV-3022 — window span implausible

One watermark advance would fire millions of windows. That is almost never a real workload: it is how
a **single row with a stale event time** announces itself. One row stamped 1970 in a stream of
present-day data makes the first window start there, and the emitter would then walk every slide from
then to now — for a one-second slide, about a billion iterations during which the lane does nothing
else and looks hung (TIME-1).

```text
PRV-3022  one watermark advance would fire 1789810201 windows, between 1970-01-01T00:00:01Z and
2026-09-19T09:30:00Z, at a slide of PT1S. That span almost always means a single row carried an event
time far outside the rest of the stream -- an unset ...
```

The limit is **10,000,000 windows per advance**, which a legitimate catch-up stays well inside: a day
of one-second windows is 86,400 and a year of hourly windows 8,760.

**Do:** look at the event-time column of the **earliest** row the query saw, not the latest — an
unset field read as the epoch, a value in the wrong unit (seconds read as milliseconds), or a parse
that silently produced zero. With `pravaha.dlq.directory` set, a record that cannot be decoded is
kept rather than read as a zero; see [Dead letters](/help/topics/dead-letters). If the span really is
intended, the window is too fine for it.

### PRV-3026 — window too fine

A row updates one slice of a window, so a fine `HOP` costs nothing on the way in and everything on the
way out: each row is published in `size / slide` windows, and each window that closes is combined from
`size / gcd(size, slide)` slices. `HOP(INTERVAL '0.001' SECOND, INTERVAL '1' DAY)` is 86.4 million of
each — one row and a minute of watermark held its lane for good and took gigabytes of heap, and every
later push to the stream timed out (FINEHOP-1). So a window where either count passes
`pravaha.lane.max-windows-per-row` is refused when it is registered, and never reaches a lane:

```text
PRV-3026  a window of PT24H sliding every PT0.001S puts each row in 86400000 windows of 86400000
slices each, past this node's bound of 100000 (pravaha.lane.max-windows-per-row). ...
```

The default, **100,000**, admits a day of one-second hops (86,400) and a week of one-minute ones
(10,080). **Do:** slide by more — a slide that divides the size keeps the slices equal to the windows
(`HOP(7 s, 1 day)` is 12,343 windows of 86,400 one-second slices; `HOP(10 s, 1 day)` is 8,640 of each).
If the work is intended and the node sized for it, raise the setting:

```yaml
pravaha:
  lane:
    max-windows-per-row: 500000
```

On an embedded engine it is the same key in the engine's configuration. A query already journalled is
checked at the start too, so lowering the bound refuses a recovered one by name.

### PRV-3030 — row field limit exceeded

A row cannot have more than **64 columns**. Every row is built by one row writer that tracks which
fields have been written in a single 64-bit mask, one bit per field, so a 65th cannot be represented.
It is architectural, not a setting, and it binds any row — a wide `SELECT *`, a wide join, a wide
aggregate.

```text
PRV-3030  BinaryRowWriter tracks written fields in a long bitmask and so supports at most 64 fields;
<stream> has 65
```

**When** it fires matters: `pravaha-engine validate` accepts a 1,000-column projection, because nothing
writes a row during validation. The ceiling is met when rows start moving. **Do:** project fewer
columns, or split a wide query into several narrower ones.

## Code generation

A query's filters and projections are compiled into Java at registration where the generator can
(whole-stage code generation, ADR-005), with an interpreted pipeline as the fallback. These three
codes are about that step. Two of them are **normally handled for you** — the stage falls back to the
interpreter, which evaluates the same SQL correctly, more slowly — and appear in logs and in
`EXPLAIN ... level=codegen` rather than as a refused query.

### PRV-3100 — codegen compilation failed

The generated source did not compile, or compiled and could not be instantiated. The message includes
the generated source with line numbers. This is a defect in the generator, not in your SQL: report it
with the message and the query.

### PRV-3101 — codegen unsupported operator

A stage the generator does not emit yet — a `LIKE` (matching needs a string, which the generated stage
exists to avoid making), a comparison between computed expressions, a projection of a type it cannot
generate, or an operator that ends a fused stage. **The interpreted path runs it**; nothing is
refused.

### PRV-3102 — codegen stage too large

A generated stage came out beyond 4,000 source lines. The JVM will not JIT-compile a method over 8 KB
of bytecode, so such a stage would run interpreted and be slower than the fallback. The engine first
splits a wide projection across stages; if a stage is still too large it is not generated, and the
interpreter runs it.

## Where next

- [Sizing and lanes](/help/topics/lanes#sizing-lanes) — what each lane setting costs, per query
- [State and serving codes](/help/topics/errors-state) — when state, not scratch memory, is full
- [Event time and watermarks](/help/topics/event-time-watermarks)
