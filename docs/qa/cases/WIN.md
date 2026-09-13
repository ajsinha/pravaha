# WIN — windowing: TUMBLE, HOP, SESSION, CUMULATE

The surface under test is `pravaha-runtime/.../runtime/window/` (`WindowSpec`, `SlicedWindows`,
`SlicedAggregateState`, `SessionWindows`), the two plan nodes
(`runtime/plan/WindowAssignOperator`, `runtime/plan/WindowedAggregateOperator`), their executors
(`runtime/exec/WindowAssign`, `runtime/exec/WindowedAggregate`), the SQL that reaches them
(`pravaha-sql/.../plan/PhysicalPlanBuilder` — `buildWindowAssign`, `buildGroupedWindow`,
`buildAggregate`, `windowBelow`, `windowBoundaryOrdinals`), and the four rows in
`docs/SQL_SUPPORT.md` §Aggregation that claim what is supported.

**Ports:** HTTP 18400, Flight 19400. **Scratch:** `$QA = <scratchpad>/qa-win`.

---

## 0. Standing setup

Everything below is driven through shipped surfaces — `bin/pravaha-server`, `bin/pravaha` over
Flight SQL on 19400, HTTP on 18400 — except where a case says it uses the **embedded harness** and
says why. The embedded harness is `QueryExecution.start(plan, 1, …)` plus `InterpretedPipeline`,
driven exactly as `pravaha-it/.../WindowedQueryEndToEndTest.run(...)` drives it; it is needed
because three things the brief asks for cannot be reached from the server at all (§0.6).

### 0.1 Server configuration — `$QA/conf/win.yaml`

```yaml
server: { port: 18400 }
pravaha:
  flight: { port: 19400 }
  security: { policy: permissive }
  checkpoint: { directory: "", interval: 1m, keep: 3 }
  watermark: { idle-after: 1s, tick: 100ms }
  streams:
    s0:                       # zero lateness: the watermark is the highest event time seen
      schema: "txn_id:INT64,user_id:INT64,amount:INT64,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 0s
    s10:                      # the engine default: watermark = highest event time - 10s
      schema: "txn_id:INT64,user_id:INT64,amount:INT64,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  sources:
    s0:  { plugin: filesystem, options: { path: "$QA/data/a.csv", schema: "txn_id:INT64,user_id:INT64,amount:INT64,event_time:TIMESTAMP" } }
    s10: { plugin: filesystem, options: { path: "$QA/data/a.csv", schema: "txn_id:INT64,user_id:INT64,amount:INT64,event_time:TIMESTAMP" } }
```

`idle-after: 1s` is the enforced minimum (`WatermarkTracker.MINIMUM_IDLE_TIMEOUT`); `tick: 100ms`
satisfies the guard in `QueryExecution.generatingWatermarks` that the tick must be the finer of the
two. Both are read on this path — `PravahaNode.start()` calls
`registry.generatingWatermarks(watermarkIdleAfter, watermarkTick)` and validates the bounds by
constructing a throwaway `WatermarkTracker` first. `pravaha.watermark.out-of-orderness` has **no
reader anywhere in the repository**; per-stream `out-of-orderness` above is the only one that works,
which is why both streams declare it explicitly rather than relying on the block.

The `filesystem` plugin is the only source that honours event time at all: `feedfile` and `delta`
both call `.eventTimestampNanos(0L)` unconditionally, so every window over them is the window
containing the epoch. Every case here therefore uses `filesystem`. One file is one partition
(`FilesystemSourcePlugin.partitions`), and a file is read once and never followed
(`FilesystemPartitionReader.poll` sets `exhausted` and returns 0 forever after).

### 0.2 Canonical queries

```sql
-- Q_T(S): tumbling, size S
SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total
FROM TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL 'S' SECOND))
GROUP BY window_start, window_end, user_id

-- Q_H(D,S): hopping, slide D, size S. In the SQL text the FIRST interval is the slide and the
-- SECOND is the size -- Calcite's order, which PhysicalPlanBuilder reverses with
-- `WindowSpec.hopping(intervals.get(1), intervals.get(0))`.
SELECT window_start, window_end, user_id, COUNT(*) AS n, SUM(amount) AS total
FROM TABLE(HOP(TABLE s0, DESCRIPTOR(event_time), INTERVAL 'D' SECOND, INTERVAL 'S' SECOND))
GROUP BY window_start, window_end, user_id
```

Output schema is `(window_start, window_end, user_id, n, total)`, so registration is
`--keys 0,1,2`. **Registering with anything less is a defect in the test, not in the engine** —
see §0.5.

### 0.3 Datasets

Times are written `s.mmm` meaning seconds after the Unix epoch. `amount` is chosen so any
misassignment changes the sum rather than nudging it.

**Dataset A** (`a.csv`, 6 rows) — the shape `WindowedQueryEndToEndTest` already asserts green, so a
failure here is a regression against a passing unit test, not a new claim.

| txn_id | user_id | amount | event_time |
|---|---|---|---|
| 1 | 100 | 10 | 1.000 |
| 2 | 100 | 20 | 5.000 |
| 3 | 200 | 30 | 7.000 |
| 4 | 100 | 40 | 11.000 |
| 5 | 200 | 50 | 19.000 |
| 6 | 100 | 60 | 25.000 |

**Dataset B** (`b.csv`, 8 rows, one user) — boundaries. Amounts are powers of two so every possible
mis-assignment produces a sum that occurs nowhere else.

| txn_id | user_id | amount | event_time |
|---|---|---|---|
| 1 | 100 | 1 | 0.000000000 |
| 2 | 100 | 2 | 9.999999999 |
| 3 | 100 | 4 | 10.000000000 |
| 4 | 100 | 8 | 19.999999999 |
| 5 | 100 | 16 | 20.000000000 |
| 6 | 100 | 32 | 20.000000000 |
| 7 | 100 | 64 | 29.999999999 |
| 8 | 100 | 128 | 30.000000000 |

**Dataset C** (`c.csv`, 3 rows, one user) — the hop case the unit test asserts: amounts 10, 20, 30
at 5.000, 15.000, 25.000.

**Dataset D** (`d.csv`, 2 rows, one user) — amounts 1 and 2 at 0.000 and 2.000, for the
non-dividing hop (size 10s, slide 3s).

**The pusher convention.** A `filesystem` source is read once and never followed, and when every
partition has gone idle `WatermarkTracker.advance` returns the watermark **unchanged** rather than
jumping to infinity ("Everything is idle. The watermark stays where it is"). So on the server path
the watermark stops at the highest event time the file contained, minus the stream's lateness, and
every window past that point stays open forever. A **pusher row** is therefore appended to any
dataset whose later windows a case needs closed: one extra row with `user_id = 999`, `amount = 0`,
and an event time chosen per case. The pusher's own windows never close by construction, so it never
appears in any expected result; if a row with `user_id = 999` ever does appear, the case is wrong
about which windows fired. The pushed variants used below are:

| file | = | pusher | watermark on `s0` | window ends that fire |
|---|---|---|---|---|
| `a_plus.csv` | A + | `7,999,0,40.000` | 40.000 | 10, 20, 30, 40 |
| `b_plus.csv` | B + | `9,999,0,50.000` | 50.000 | 10, 20, 30, 40, 50 |
| `c_plus.csv` | C + | `4,999,0,60.000` | 60.000 | 0, 10, 20, 30, 40, 50, 60 |
| `d_plus.csv` | D + | `3,999,0,30.000` | 30.000 | 0, 3, 6, … , 30 |

**Dataset V(N, K)** (`v_<N>_<K>.csv`) — the volume convention, and the one that reproduces the
numbers in the known blocker. Row *i* for *i* = 1…N carries `txn_id=i`, `user_id = i mod K`,
`amount = 1`, `event_time = i milliseconds`. With `TUMBLE 10 SECOND` the windows are
`[10j, 10j+10)` seconds and window *j* holds rows *i* ∈ [10000·*j*, 10000·*j*+9999]:

- window 0 holds *i* = 1…9,999 → **9,999 rows** (there is no row 0),
- every window *j* ≥ 1 holds **10,000 rows**,
- the final partial window holds whatever is left.

**Dataset W(N)** (`w_<N>.csv`) — the state-ceiling convention: row *i* carries `user_id = i`,
`amount = 1`, `event_time = 1 millisecond` for every *i* = 1…N, plus one final row *N+1* at
`event_time = 20.000`. Every one of the N rows is a distinct key in the single window `[0,10)`, so
live `(key, slice)` accumulators = N; the last row is what pushes the watermark past 10.000 so the
window can fire.

### 0.4 Reference arithmetic — what closes, and what does not

For dataset V(N,K) the highest event time is *N* ms, so the watermark settles at *N* ms (stream
`s0`) or *N* − 10,000 ms (stream `s10`). `SlicedWindows.windowsCompletedBetween` fires window ends
that are multiples of 10 s and **≤** the watermark, so:

| N | windows that fire on `s0` | rows in them | windows on `s10` | rows in them |
|---|---|---|---|---|
| 1 | 0 | 0 | 0 | 0 |
| 1,000 | 0 | 0 | 0 | 0 |
| 10,000 | 1 | 9,999 | 0 | 0 |
| 100,000 | 10 | 99,999 | 9 | 89,999 |
| **60,000** | 6 | 59,999 | **5** | **49,999** |
| **200,000** | 20 | 199,999 | **19** | **189,999** |
| 210,000 | 21 | 209,999 | 20 | 199,999 |
| 220,000 | 22 | 219,999 | 21 | 209,999 |
| 230,000 | 23 | 229,999 | 22 | 219,999 |
| 1,000,000 | 100 | 999,999 | 99 | 989,999 |

Rows in the *k* fired windows = 9,999 + 10,000·(*k*−1). Two of these rows are the numbers in the
known blocker and are not a coincidence: at N = 60,000 on `s10` exactly **10,001 of 60,000 rows are
never emitted** (the 10,000 in `[50,60)` plus the single row at 60.000 in `[60,70)`), and at
N = 200,000 on `s10` exactly **19 windows should be served**. "13 served where 19 exist" is
therefore a deficit of 6 windows = 60,000 rows, measured against this table.

### 0.5 Vacuity, standing

Three failure modes make a windowing case pass while windowing is broken. Every stateful case below
names which of the three its Vacuity paragraph is defending against.

1. **Key collapse.** Round 1 asserted a row count while 200,000 rows collapsed into 500 keys. A
   `ServedView` is keyed by the ordinals given to `--keys`; with `--keys 2` (user_id alone) every
   window overwrites the previous window's row for that user and the view holds *K* rows however
   many windows ran. The standing control is `v_collapse`: the same SQL registered with `--keys 2`,
   which must hold strictly fewer rows than the `--keys 0,1,2` registration whenever more than one
   window fired. If the two agree, no window boundary is in the key and the case proves nothing.
2. **A dry source.** Round 1's pause case passed because the source ran dry. `pravaha queries`
   ROWS IN must equal the file's row count *before* any window assertion is believed; a windowing
   case that asserts on an empty view is asserting on an empty view.
3. **An unarmed watermark.** With no watermark nothing ever fires and "no rows" looks like "no
   windows closed". The standing control is `pravaha.query.watermark.lag.seconds` on
   `http://localhost:18400/actuator/prometheus`: it must be finite and must move.

Two things are **not** observable on any shipped surface and no case may assert on them:
`WindowedAggregate.lateRecords()` and `.corrections()`. Both exist, both are documented as the
numbers that make late data visible ("never silently dropped"), and neither is exposed by
`PravahaMetrics` — which registers exactly `rows.in`, `view.size`, `view.evicted`, `view.updates`,
`view.removals`, `watermark.lag.seconds` and `running` — nor by `/status`, nor by any CLI verb.
WIN-172 pins that as a defect rather than working around it.

### 0.6 What the server cannot reach, and why the embedded harness appears

- **End of input.** `InterpretedPipeline.finish()` — which fires every still-open window — is called
  only from `QueryExecution$LanePipelineProcessor.close()`, i.e. at lane shutdown. A registered
  query's lane shuts down when the query is dropped, by which time the sink is going away. There is
  no "the source ended" signal on the server path at all.
- **More than one lane.** `QueryRegistry.start` calls `QueryExecution.start(plan, 1, …)` — always
  one lane. And `refuseUnpartitionedAggregate` refuses any windowed aggregate on more than one lane
  outright, because `containsKeyedAggregate` returns true for every `WindowedAggregateOperator`.
  Lane count is therefore not a free variable for this area; WIN-138 records that rather than
  pretending to sweep it.
- **SESSION.** `SessionWindows` is reachable from no plan node. There is no `SessionAssignOperator`,
  and `PhysicalPlanBuilder` refuses the syntax. §3 is written against the class directly.

---

## 1. Kind — TUMBLE

### WIN-001 — A tumbling window produces one row per (window, key) with the hand-computed numbers
**Intent:** The base case the whole area rests on: SQL text in, one correct result row per window
per key out, through the server rather than through a unit test harness.
**Falsifier:** Any group count other than 4, any sum other than the four below, or any row for a
(window, user) pair not listed.
**Setup:** `win.yaml`; `s0` bound to `a.csv` (dataset A).
**Steps:** `pravaha register --name v_t10 --sql-file qt10.sql --keys 0,1,2` where `qt10.sql` is
Q_T(10) over `s0`; wait until `pravaha queries` shows ROWS IN 6 and stops changing; then
`pravaha query --sql "SELECT * FROM v_t10 ORDER BY window_start, user_id"`.
**Expected:** Exactly 4 rows. Windows are `[0,10)`, `[10,20)`, `[20,30)`; the watermark on `s0` is
the highest event time, 25.000, and `windowsCompletedBetween` fires ends ≤ 25.000, i.e. 10.000 and
20.000 only:

| window_start | window_end | user_id | n | total |
|---|---|---|---|---|
| 0.000 | 10.000 | 100 | 2 | `10 + 20 = 30` |
| 0.000 | 10.000 | 200 | 1 | `30` |
| 10.000 | 20.000 | 100 | 1 | `40` |
| 10.000 | 20.000 | 200 | 1 | `50` |

`[20,30)` (user 100, amount 60) does **not** appear; WIN-165 owns that.
**Vacuity:** Defends against key collapse. Register `v_collapse` with `--keys 2` in the same run: it
must hold 2 rows (users 100 and 200) against `v_t10`'s 4. If both hold 4, the window boundaries are
not in the view key and every "one row per window" assertion in this file is meaningless.

### WIN-002 — Window boundaries are half-open and are emitted as the window's own, not the row's
**Intent:** `WindowAssign` writes *slice* boundaries into `window_start`/`window_end`;
`WindowedAggregate.emitRow` overwrites them with `result.windowStartNanos()`/`windowEndNanos()`.
For TUMBLE slice = window so the two agree, which is the control for WIN-016 where they do not.
**Falsifier:** A `window_end` that is not exactly `window_start + 10 s`, or a `window_start` that is
not a multiple of 10 s.
**Setup:** WIN-001 done.
**Steps:** `pravaha query --sql "SELECT window_start, window_end, window_end - window_start AS width FROM v_t10"`.
**Expected:** Every `width` is exactly 10,000,000,000 ns. Every `window_start` ∈ {0, 10 s}. No row
has `window_start = 1.000` (the row's own time) or `window_start = 25.000`.
**Vacuity:** An engine that echoed the row's event time into `window_start` would give four distinct
starts (1, 5, 7, 11…) rather than two, and `width` would be zero.

### WIN-003 — The same query in the grouped-function form plans to the same operator
**Intent:** `PhysicalPlanBuilder.buildGroupedWindow` claims `GROUP BY TUMBLE(...)` is "the same
window expressed differently". Two syntaxes that produce different answers is worse than one syntax.
**Falsifier:** `explain` showing a different operator tree, or any result row differing from WIN-001.
**Setup:** `qt10g.sql`: `SELECT TUMBLE_START(event_time, INTERVAL '10' SECOND), user_id, COUNT(*), SUM(amount) FROM s0 GROUP BY TUMBLE(event_time, INTERVAL '10' SECOND), user_id`.
**Steps:** `pravaha explain --sql-file qt10g.sql --schema "txn_id:INT64,user_id:INT64,amount:INT64,event_time:TIMESTAMP" --level physical`; then register as `v_t10g --keys 0,1` and read it.
**Expected:** Either (a) a physical plan containing `WindowAssign(TUMBLING size=10000ms slide=10000ms on event_time)` above `WindowedAggregate(TUMBLING 10000ms, …)` and the same four rows as WIN-001, or (b) a refusal. Record which. `buildGroupedWindow` rejects any projected expression that is not a `RexInputRef` beside the window call with `PRV-2020` and the text "sits beside a windowing function in the same projection" — `TUMBLE_START(...)` is such an expression, so (b) is the likely outcome and is the same shape `docs/QUICKSTART.md` §4 tells a first-time reader to type.
**Vacuity:** Not stateful. The falsifier is the plan text, which exists whether or not any row flows.

### WIN-004 — QUICKSTART §4's windowed registration, executed literally
**Intent:** `docs/QUICKSTART.md` §4 is the first windowed query a new operator writes. It uses
`SELECT STREAM`, `TUMBLE_END(...)` in the select list, `GROUP BY TUMBLE(...)`, and registers with
`--keys 1`. Every one of those four is a separate hazard and the document ships them together.
**Falsifier:** The command succeeding and `user_volume` holding one row per (window, user).
**Setup:** `s0` bound, `velocity.sql` copied verbatim from QUICKSTART §4 with `txn` → `s0` and
`status` dropped (the quickstart's own schema has no `event_time`, which DOC-011 already recorded).
**Steps:** `pravaha register --name user_volume --sql-file velocity.sql --keys 1`; `pravaha queries`;
`pravaha query --sql "SELECT * FROM user_volume"`.
**Expected:** Record the exact outcome. Three distinct failures are predicted and each is a separate
defect: `TUMBLE_END` in the projection hits the `PRV-2020` refusal above; `--keys 1` keys the view
on `user_id` alone so windows overwrite one another (§0.5.1); and `SELECT STREAM` is not in
`docs/SQL_SUPPORT.md`'s construct list at all.
**Vacuity:** Defends against a dry source: ROWS IN must be 6 before the view content is read, or the
empty view proves nothing about the syntax.

### WIN-005 — TUMBLE over a stream with no declared event-time column
**Intent:** `pravaha.streams.<n>.event-time` is what makes `StreamSchema.eventTime` non-negative;
`WindowAssignOperator`'s compact constructor throws `IllegalArgumentException` when the ordinal is
negative. Whether that surfaces as a plan-time refusal with a code or as a raw Java exception is the
difference between a fixable query and a support ticket.
**Falsifier:** Registration succeeding and the query never producing a row.
**Setup:** A third stream `snoevent` with the same schema and **no** `event-time` key, bound to `a.csv`.
**Steps:** `pravaha register --name v_noev --sql "…TUMBLE(TABLE snoevent, DESCRIPTOR(event_time), INTERVAL '10' SECOND)…" --keys 0,1,2`.
**Expected:** A refusal. `DESCRIPTOR(event_time)` names a real column so `descriptorOrdinal` resolves
it and `WindowAssignOperator` is constructible — meaning the likely outcome is that the query
registers, every row is stamped `eventTimestampNanos` = the column value by the filesystem plugin
only when `event.time` was pushed down (`PravahaNode.withDeclaredEventTime` pushes it only when the
declaration has one), so rows carry event time 0, the watermark sits at 0, and nothing ever fires.
Record whether a code is produced. A silent never-firing query is the `PRV`-less failure worth a
defect on its own.
**Vacuity:** The control is `v_t10` from WIN-001 in the same server run, which must hold 4 rows. Both
views empty means the server is broken, not the declaration.

### WIN-006 — TUMBLE naming a non-temporal column in DESCRIPTOR
**Intent:** `descriptorOrdinal` matches by ordinal or by name and does no type check at all, so
`DESCRIPTOR(amount)` resolves to the amount column and the engine windows on money.
**Falsifier:** Windows appearing at boundaries that are multiples of 10 seconds of *amount*.
**Setup:** `s0` bound to `a.csv`.
**Steps:** Register `…TUMBLE(TABLE s0, DESCRIPTOR(amount), INTERVAL '10' SECOND)…`.
**Expected:** A refusal naming the column and its type. If it is accepted, amounts 10…60 are read as
nanosecond timestamps, every row lands in slice `[0, 10 s)`, and one window is produced with
`n = 6`, `total = 10+20+30+40+50+60 = 210` for the combined users — an answer that is internally
consistent and meaningless. Record which.
**Vacuity:** Not state-dependent; the falsifier is the shape of the output schema and the boundary
values, both present in a single row of output.

### WIN-007 — TUMBLE with no interval argument
**Intent:** `requireIntervals("TUMBLE", intervals, 1)` is the guard; reaching it needs a call that
parses with no literal operand.
**Falsifier:** A `NullPointerException`, an `IndexOutOfBoundsException`, or a stack trace with no
`PRV` code.
**Steps:** `pravaha validate --sql "SELECT * FROM TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time)))" --schema "…"`.
**Expected:** Either Calcite's own arity error at validation, or `PRV-2020` with the text
`TUMBLE needs 1 interval argument(s); got 0`. Both are acceptable; a raw Java exception is not.
**Vacuity:** Not stateful.

### WIN-008 — TUMBLE with a zero interval
**Intent:** `WindowSpec`'s constructor throws `IllegalArgumentException("window size must be
positive, got 0")` — a plain Java exception, not a `PravahaException`, so it carries no `PRV` code
and `ERRC` cannot enumerate it.
**Falsifier:** A `PRV` code appearing (that would mean the guard moved and this case is stale), or a
window of width 0 being accepted.
**Steps:** `pravaha validate --sql "…TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '0' SECOND)…"`.
**Expected:** Refused. Record the exact rendering the CLI gives an `IllegalArgumentException` thrown
from inside the planner — whether it reaches the user as a message or as a stack trace is the point
of the case.
**Vacuity:** Not stateful.

### WIN-009 — TUMBLE with a negative interval
**Intent:** Same guard, the other side. `INTERVAL '-10' SECOND` gives `sizeNanos = -10e9`.
**Falsifier:** Acceptance. A negative size makes `Math.floorDiv(t, -10e9)` produce slice starts above
the event time and `slicesOfWindowEnding` loop zero times, so every window is empty and nothing errors.
**Steps:** `pravaha validate --sql "…INTERVAL '-10' SECOND…"`.
**Expected:** Refused with "window size must be positive, got -10000000000".
**Vacuity:** Not stateful.

### WIN-010 — Two registrations of the same windowed SQL share one computation and one window state
**Intent:** Registrations sharing a fingerprint share one computation. For a windowed aggregate that
means one `SlicedAggregateState`, so the second name must not double-count.
**Falsifier:** `v_t10b` showing `n = 4` for the `[0,10)` user-100 group where `v_t10` shows 2.
**Setup:** WIN-001 done, `v_t10` running.
**Steps:** `pravaha register --name v_t10b --sql-file qt10.sql --keys 0,1,2`; `pravaha queries`;
read both views.
**Expected:** Both views hold the identical 4 rows of WIN-001. `pravaha queries` shows the same
fingerprint for both names. ROWS IN for the shared computation is 6, not 12 —
`QueryRegistry.start` returns early on a fingerprint hit and never opens the feed twice.
**Vacuity:** Defends against key collapse *and* double-feeding. If ROWS IN reads 12 while the sums
still read 30, the feed is being drained twice and some other bug is cancelling it out.

### WIN-011 — Dropping one of two shared windowed registrations leaves the other's windows intact
**Intent:** "A computation is released when its last name is dropped." A windowed aggregate holds
slices; releasing them on the first drop would empty the surviving view at the next window.
**Falsifier:** `v_t10` losing rows, or its next window firing with numbers smaller than WIN-001's.
**Setup:** WIN-010 done.
**Steps:** `pravaha drop --name v_t10b`; `pravaha queries`; read `v_t10`.
**Expected:** `v_t10` still `RUNNING`, still holding the same 4 rows with the same sums.
**Vacuity:** Defends against a dry source: the source is already exhausted here, so this case proves
only that state survived — which is what it claims. It must be read alongside WIN-190, where a
second file is appended after the drop.

### WIN-012 — `EXPLAIN` names the window kind, size and slide
**Intent:** `WindowAssignOperator.label()` renders `size`/`slide` in **milliseconds**
(`spec.sizeNanos() / 1_000_000`). The engine's whole time base is nanoseconds (ADR-012), so the one
place a human reads the window back is the one place it is not in nanoseconds.
**Falsifier:** A label showing a size that does not match the SQL, or integer-truncating a
sub-millisecond size to `0ms`.
**Steps:** `pravaha explain --sql-file qt10.sql --schema "…" --level physical`.
**Expected:** `WindowAssign(TUMBLING size=10000ms slide=10000ms on event_time)` and
`WindowedAggregate(TUMBLING 10000ms, keys=[…], 2 aggregate(s))`. Compare with WIN-054, where a
100-microsecond size renders as `0ms`.
**Vacuity:** Not stateful.

---

## 2. Kind — HOP

### WIN-013 — A hop of 10 s over a 20 s window puts each row in exactly two windows
**Intent:** The defining property of a hop, proved by arithmetic rather than by a row count. The
number of windows containing an event at *t* is `floor((t+S)/D) - floor(t/D)`; for S = 20 s,
D = 10 s that is 2 for every *t*.
**Falsifier:** A total count over all windows other than 6, or any single window's `n` differing
from the table below.
**Setup:** `s0` bound to `c_plus.csv` (dataset C: amounts 10, 20, 30 at 5.000, 15.000, 25.000; plus
the pusher at 60.000).
**Steps:** Register Q_H(10, 20) as `v_h10_20 --keys 0,1,2`; wait for ROWS IN 4; read the view
ordered by `window_start`.
**Expected:** Exactly 4 rows for user 100, and none for user 999.

| window_start | window_end | n | total | arithmetic |
|---|---|---|---|---|
| −10.000 | 0.000 | — | — | window `[-20,0)` holds nothing and is not emitted |
| −10.000 | 10.000 | 1 | 10 | row at 5.000 only |
| 0.000 | 20.000 | 2 | `10 + 20 = 30` | rows at 5.000, 15.000 |
| 10.000 | 30.000 | 2 | `20 + 30 = 50` | rows at 15.000, 25.000 |
| 20.000 | 40.000 | 1 | 30 | row at 25.000 |

`SUM(n) = 1 + 2 + 2 + 1 = 6 = 3 rows × 2 windows each.` A row stored twice would give 12; a row
stored once and counted once would give 3.
**Vacuity:** Defends against key collapse. `--keys 2` on the same SQL would hold **one** row (user
100), because all four windows share the key — so the 4-vs-1 gap is what proves the boundaries are
in the key. Also defends against "hop silently degenerating to tumble", which would give
`SUM(n) = 3`.

### WIN-014 — A hop produces a window starting before the first row
**Intent:** `[-10, 10)` above starts ten seconds before any data exists. That is correct SQL:2016
hop semantics and it looks exactly like an off-by-one, so it is pinned deliberately rather than left
to be "fixed" by somebody who assumes the engine is wrong.
**Falsifier:** The `[-10, 10)` row being absent, which would mean the row at 5.000 was counted in
fewer windows than it belongs to.
**Setup:** WIN-013 done.
**Steps:** `pravaha query --sql "SELECT * FROM v_h10_20 WHERE window_start < 0"`.
**Expected:** Exactly one row: `window_start = -10.000`, `window_end = 0.000`… no — `window_end =
10.000`, `n = 1`, `total = 10`. The negative start is the correct answer, not a defect.
**Vacuity:** Defends against a dry source; ROWS IN must read 4 first.

### WIN-015 — `windowEndsContaining` and the slicing agree on every row of the dataset
**Intent:** `SlicedWindows.windowEndsContaining` exists specifically so the tests can check the two
halves against each other: "If a record's slice is not part of every window this returns, the
slicing is wrong." That check belongs in this file as an explicit case, not as a comment.
**Falsifier:** Any *t* in the dataset for which the slice `sliceStartFor(t)` is absent from
`slicesOfWindowEnding(e)` for some *e* in `windowEndsContaining(t)`.
**Setup:** Embedded harness — this is the arithmetic, below SQL. Spec `hopping(20 s, 10 s)`.
**Steps:** For each *t* ∈ {5 s, 15 s, 25 s, 0, 9.999999999 s, 10 s, −1 ns}: compute
`windowEndsContaining(t)`, and for each end compute `slicesOfWindowEnding(end)`; assert
`sliceStartFor(t)` is in it.
**Expected:** All hold. Worked examples: `sliceStartFor(5 s) = floorDiv(5e9, 10e9)·10e9 = 0`;
`windowEndsContaining(5 s) = [10 s, 20 s]`; `slicesOfWindowEnding(10 s) = [-10 s, 0]` and
`slicesOfWindowEnding(20 s) = [0, 10 s]` — slice 0 is in both. For `t = -1 ns`:
`sliceStartFor(-1) = floorDiv(-1, 10e9)·10e9 = -10e9`, and `windowEndsContaining(-1) =
[0, 10e9]` because `floorDiv(-1, 10e9)·10e9 + 10e9 = 0`; `slicesOfWindowEnding(0) = [-20e9, -10e9]`
contains it. Floor division, not truncation, is what makes the negative case work.
**Vacuity:** Pure arithmetic; there is no state to be empty.

### WIN-016 — A hop **without** an aggregate emits slice boundaries, not window boundaries
**Intent:** `WindowAssign.process` writes `sliceStart` and `sliceStart + sliceSizeNanos()` into the
two boundary columns. For TUMBLE slice = window, so nobody notices. For a hop, the slice is
`gcd(size, slide)` wide — narrower than the window — and nothing above corrects it unless a
`WindowedAggregate` fires, because only `emitRow` substitutes the real window boundaries. A bare
windowing table function therefore reports a window that does not exist.
**Falsifier:** `window_end - window_start` equalling the 20 s the SQL asked for.
**Setup:** `s0` bound to `c_plus.csv`.
**Steps:** Register `SELECT txn_id, user_id, window_start, window_end FROM TABLE(HOP(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '20' SECOND))` as `v_hraw --keys 0`; read it.
**Expected:** Four rows — one per input row, *not* the eight (4 rows × 2 windows) SQL:2016 requires —
each with `window_end - window_start = 10,000,000,000 ns`, the **slice** width, against a query that
asked for 20 s. Two defects in one output: the row is not replicated into every window it belongs
to, and the boundary columns name an interval the query never mentioned. `docs/SQL_SUPPORT.md` row
"`HOP` (sliding) windows ✅" carries no qualification about either.
**Vacuity:** Defends against a dry source: ROWS IN must be 4. The control is the same query with
TUMBLE, WIN-017, where the widths are correct — so an empty or malformed view cannot be blamed on
the bare table-function form in general.

### WIN-017 — The same bare table function over TUMBLE is correct, which isolates WIN-016 to hops
**Intent:** The control that turns WIN-016 from "windowing table functions are broken" into "slice
and window coincide for TUMBLE and diverge for HOP".
**Falsifier:** Widths other than 10 s, or a row count other than 7.
**Setup:** `s0` bound to `a_plus.csv`.
**Steps:** Register `SELECT txn_id, user_id, window_start, window_end FROM TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND))` as `v_traw --keys 0`; read it.
**Expected:** 7 rows, one per input row. Widths all 10 s. `window_start` values: row 1 (t=1) → 0;
row 2 (t=5) → 0; row 3 (t=7) → 0; row 4 (t=11) → 10 s; row 5 (t=19) → 10 s; row 6 (t=25) → 20 s;
pusher (t=40) → 40 s. Each is `floorDiv(t, 10 s)·10 s`.
**Vacuity:** Defends against a dry source. Row count 7 is the proof the file was read.

### WIN-018 — The interval order in `HOP(...)` is (slide, size) and getting it backwards is silent
**Intent:** `PhysicalPlanBuilder` comments that "getting this backwards produces windows of the
wrong width that still fire plausibly". A test that only checks totals cannot tell 20/10 from 10/20,
because a row count is symmetric under the swap in some datasets.
**Falsifier:** `EXPLAIN` reporting `size=10000ms slide=20000ms` for `HOP(…, INTERVAL '10' SECOND, INTERVAL '20' SECOND)`.
**Steps:** `pravaha explain --sql "SELECT window_start, window_end, COUNT(*) FROM TABLE(HOP(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '20' SECOND)) GROUP BY window_start, window_end" --schema "…" --level physical`.
**Expected:** `WindowAssign(HOPPING size=20000ms slide=10000ms on event_time)`. The first interval in
the SQL text is the slide, the second is the size.
**Vacuity:** Not stateful; the plan text exists without any rows.

### WIN-019 — Swapping the intervals is accepted and refused for the right reason
**Intent:** The reverse of WIN-018. `HOP(…, INTERVAL '20' SECOND, INTERVAL '10' SECOND)` means slide
20 over size 10, which `WindowSpec` refuses as gapped — so the swap is caught here by luck rather
than by validation. A user who swaps 10 and 20 gets an error about gaps, not about argument order.
**Falsifier:** Acceptance.
**Steps:** `pravaha validate --sql "…HOP(TABLE s0, DESCRIPTOR(event_time), INTERVAL '20' SECOND, INTERVAL '10' SECOND)…"`.
**Expected:** Refused with "a hop of 20000000000 ns over a window of 10000000000 ns leaves gaps:
records between windows would belong to none. Use a smaller slide, or express the gap as a filter."
An `IllegalArgumentException` from `WindowSpec`, so **no `PRV` code** — record how it renders.
**Vacuity:** Not stateful.

### WIN-020 — A hop whose slide does not divide the size still assigns correctly
**Intent:** "A hop that does not divide the size evenly still works; it just produces smaller slices
and more of them." Size 10 s, slide 3 s → `sliceSizeNanos = gcd(10, 3) = 1 s`,
`slicesPerWindow = 10`.
**Falsifier:** `slicesPerWindow` ≠ 10, or any window's total differing from the table.
**Setup:** `s0` bound to `d_plus.csv` — rows (amount 1, t = 0.000), (amount 2, t = 2.000), pusher at
30.000.
**Steps:** Register Q_H(3, 10) as `v_h3_10 --keys 0,1,2`; read it ordered by `window_start`.
**Expected:** Window ends are multiples of 3 s. The row at 0 belongs to ends 3, 6, 9
(`floor(10/3) - floor(0/3) = 3 - 0 = 3` windows); the row at 2 belongs to ends 3, 6, 9, 12
(`floor(12/3) - floor(2/3) = 4 - 0 = 4`). Four non-empty windows:

| window_start | window_end | n | total |
|---|---|---|---|
| −7.000 | 3.000 | 2 | `1 + 2 = 3` |
| −4.000 | 6.000 | 2 | `1 + 2 = 3` |
| −1.000 | 9.000 | 2 | `1 + 2 = 3` |
| 2.000 | 12.000 | 1 | `2` |

`SUM(n) = 2 + 2 + 2 + 1 = 7 = 3 + 4`, which is the two per-row window counts added. Windows ending
at 15, 18, 21, 24, 27 and 30 fire empty and emit nothing (§12).
**Vacuity:** Defends against key collapse (4 rows vs 1 under `--keys 2`) and against a degenerate
hop: if the slide were silently rounded to the size, `SUM(n)` would be 2.

### WIN-021 — ⌈size/slide⌉ is the maximum, not the constant — and both values occur in one dataset
**Intent:** The brief's "a row lands in ⌈size/slide⌉ windows" is true only when the slide divides
the size. For 10/3 the true count is `floor((t+10)/3) - floor(t/3)`, which is 3 for *t* = 0 and 4
for *t* = 2. Asserting the ceiling for every row would fail on a correct engine.
**Falsifier:** Both rows landing in the same number of windows.
**Setup:** WIN-020 done.
**Steps:** `pravaha query --sql "SELECT txn_id, COUNT(*) FROM v_h3_10 …"` is not expressible, so
read `v_h3_10` and count windows containing each amount: amount 1 appears in windows ending 3, 6, 9;
amount 2 in windows ending 3, 6, 9, 12.
**Expected:** amount 1 in 3 windows, amount 2 in 4. `⌈10/3⌉ = 4` and `⌊10/3⌋ = 3`, and both occur.
**Vacuity:** Follows WIN-020's controls.

### WIN-022 — Slice count is `size / gcd(size, slide)` for every pair under test
**Intent:** State size is `keys × slicesPerWindow`; if `slicesPerWindow` is wrong, every state-bound
claim in §7 and §8 is wrong.
**Falsifier:** Any disagreement with the table.
**Setup:** Embedded harness on `WindowSpec` directly.
**Steps:** Construct each spec and read `sliceSizeNanos()` and `slicesPerWindow()`.
**Expected:**

| size | slide | gcd | slice | slices/window |
|---|---|---|---|---|
| 10 s | 10 s | 10 s | 10 s | 1 |
| 20 s | 10 s | 10 s | 10 s | 2 |
| 10 s | 1 s | 1 s | 1 s | 10 |
| 100 s | 1 s | 1 s | 1 s | 100 |
| 1000 s | 1 s | 1 s | 1 s | 1000 |
| 10 s | 3 s | 1 s | 1 s | 10 |
| 60 s | 45 s | 15 s | 15 s | 4 |
| 1 h | 1 s | 1 s | 1 s | 3600 |

`slicesPerWindow()` returns `(int)(sizeNanos / sliceSizeNanos())` — note the cast. For size 1 d and
slide 1 ns the quotient is 86,400,000,000,000, which does not fit in an `int` and wraps; WIN-090
takes that.
**Vacuity:** Pure arithmetic.

### WIN-023 — A hop and a tumble of the same size agree on every row's slice
**Intent:** A record belongs to exactly one slice whatever the overlap. If TUMBLE 10 s and
HOP(1 s, 10 s) disagreed about which slice a row is in, the two would produce different sums for the
windows they share.
**Falsifier:** Any *t* where `SlicedWindows(tumbling(10 s)).sliceStartFor(t)` is not a multiple of
the hop's slice size containing the same *t*.
**Setup:** Embedded harness.
**Steps:** For *t* ∈ {0, 1 ns, 999,999,999 ns, 1 s, 9.999999999 s, 10 s, −1 ns, −10 s}: compare
`floorDiv(t, 10 s)·10 s` with `floorDiv(t, 1 s)·1 s`.
**Expected:** The hop's 1 s slice is always contained in the tumble's 10 s slice. E.g. *t* = 9.999999999 s
→ tumble slice 0, hop slice 9 s, and `0 ≤ 9 s < 10 s`. *t* = −1 ns → tumble slice −10 s, hop slice −1 s,
and `-10 s ≤ -1 s < 0`.
**Vacuity:** Pure arithmetic.

### WIN-024 — A hop over a single row
**Intent:** The smallest hop. One row must appear in exactly `⌈S/D⌉` or `⌊S/D⌋` windows and in no
others, and every one of those windows must hold exactly that one row.
**Falsifier:** Any window with `n ≠ 1`, or a window count other than 2.
**Setup:** `one_hop.csv`: `1,100,7,5.000` and pusher `2,999,0,60.000`.
**Steps:** Register Q_H(10, 20) as `v_h1 --keys 0,1,2`; read it.
**Expected:** Two rows for user 100: `[-10, 10)` `n=1 total=7` and `[0, 20)` `n=1 total=7`. The
same value 7 in two windows is correct: the row is counted once *per window*, and the slice holding
it is read twice.
**Vacuity:** Defends against key collapse: `--keys 2` would show one row and hide the duplication.

### WIN-025 — A hop over an empty stream
**Intent:** No rows, no windows, no error, no state.
**Falsifier:** Any row in the view, or a non-RUNNING state.
**Setup:** `empty.csv` — zero bytes. `sempty` declared with `event-time: event_time` and bound to it.
**Steps:** Register Q_H(10, 20) over `sempty` as `v_hempty --keys 0,1,2`; wait 10 s; `pravaha queries`;
read the view.
**Expected:** `RUNNING`, ROWS IN 0, zero rows. `firstWindowStart()` returns 0 because
`earliestWindowStart` is still `Long.MAX_VALUE`, so the first watermark advance walks from 0 — but
no watermark ever advances either, because the partition has no event times to observe and
`WatermarkTracker` returns `current` for a partition that has produced nothing. Nothing happens, and
nothing is meant to.
**Vacuity:** The control is `v_h10_20` from WIN-013 in the same run: it must hold 4 rows. Without it
this case passes on a server that is simply dead.

### WIN-026 — A hop where every row has the same event time
**Intent:** Degenerate but legal. Every row is in one slice and therefore in the same ⌈S/D⌉ windows;
it is the shape that exposes a slice map keyed only by time.
**Falsifier:** Fewer than 2 windows, or a window with `n ≠ 5`.
**Setup:** `same_ts.csv`: five rows, `user_id = 100`, amounts 1, 2, 4, 8, 16, all at `event_time = 5.000`;
pusher `6,999,0,60.000`.
**Steps:** Register Q_H(10, 20) as `v_hsame --keys 0,1,2`; read it.
**Expected:** Two rows: `[-10, 10)` and `[0, 20)`, each `n = 5`, `total = 1+2+4+8+16 = 31`.
`SUM(n) = 10 = 5 rows × 2 windows`.
**Vacuity:** Defends against key collapse and against a per-timestamp deduplication: if the engine
kept one row per (key, timestamp), each window would show `n = 1, total = 16`.

### WIN-027 — A hop whose slide equals one nanosecond is refused or is survivable
**Intent:** `slicesPerWindow` for size 10 s and slide 1 ns is 10,000,000,000 — and
`windowsCompletedBetween` steps by the slide, so a watermark advance of one second enqueues
1,000,000,000 window ends into an `ArrayList`. This is the smallest input that turns a tick into an
out-of-memory kill.
**Falsifier:** The query registering and the lane surviving a single tick.
**Setup:** `s0` bound to `a_plus.csv`.
**Steps:** `pravaha register --name v_hns --sql "…HOP(TABLE s0, DESCRIPTOR(event_time), INTERVAL '0.000000001' SECOND, INTERVAL '10' SECOND)…" --keys 0,1,2`.
**Expected:** Refused at plan time. The slide literal is sub-millisecond so
`((BigDecimal) literal.getValue4()).longValue()` truncates to 0 and `WindowSpec` refuses with
"window slide must be positive, got 0" — which happens to save the engine, for a reason unrelated
to the hazard. Record that the protection is accidental: `INTERVAL '0.001' SECOND` (1 ms) is **not**
truncated, gives a slide of 1,000,000 ns, and enqueues 10,000 ends per second of watermark advance
with 10,000 slices per window. WIN-088 runs that one.
**Vacuity:** Defends against an unarmed watermark: if the refusal does not happen and the query
registers, `watermark.lag.seconds` must be checked — a lane that died mid-tick leaves the gauge
frozen rather than absent.

### WIN-028 — Hop results are Z-set rows with weight +1 and no duplicate for one window
**Intent:** `emitWindow` writes one `+1` row per key per firing and a `-1` retraction only when a
correction changes a previously emitted value. A window that fires once must produce exactly one
insert per key.
**Falsifier:** A subscriber seeing two `+1` rows for the same (window, key), or seeing a `-1` at all.
**Setup:** `s0` bound to `c_plus.csv`.
**Steps:** `pravaha subscribe --view v_h10_20 --limit 20` started **before** registration; then
register; capture the stream.
**Expected:** Exactly 4 changes, one per (window, key) of WIN-013, each an insert. No retraction,
because no late record arrived and `dirty` stayed empty.
**Vacuity:** Defends against a dry source *and* against the subscription starting late: the
subscriber is attached first, and `pravaha subscribe` prints its "subscribed to …" banner, which
must appear before `register` is issued.

### WIN-029 — A hop over two keys keeps their windows independent
**Intent:** The composite key hashes only the data key ordinals — window boundaries are explicitly
excluded from the hash and carried by the slice dimension instead. Two keys in one window must be
two accumulators, and one key in two windows must be two slices' worth combined differently.
**Falsifier:** A single row per window, or a total equal to the sum across both users.
**Setup:** `c2.csv`: `1,100,10,5.000`; `2,200,100,5.000`; `3,100,20,15.000`; `4,200,200,15.000`;
pusher `5,999,0,60.000`.
**Steps:** Register Q_H(10, 20) as `v_h2k --keys 0,1,2`; read it.
**Expected:** Six rows. Windows `[-10,10)`, `[0,20)`, `[10,30)`:

| window | user | n | total |
|---|---|---|---|
| `[-10,10)` | 100 | 1 | 10 |
| `[-10,10)` | 200 | 1 | 100 |
| `[0,20)` | 100 | 2 | `10 + 20 = 30` |
| `[0,20)` | 200 | 2 | `100 + 200 = 300` |
| `[10,30)` | 100 | 1 | 20 |
| `[10,30)` | 200 | 1 | 200 |

No row has `total = 110`, `330` or `220`; those are what a collapsed key would give.
**Vacuity:** Defends against key collapse in both directions: `--keys 2` would give 2 rows and
`--keys 0,1` would give 3 with merged totals. Both must be run as controls.

### WIN-030 — `EXPLAIN` on a hop names HOPPING and both intervals
**Intent:** The window must be visible in the plan rather than buried in an aggregate's
configuration — that is the stated reason assignment and aggregation are separate operators.
**Falsifier:** A plan that names only the size, or that names TUMBLING.
**Steps:** `pravaha explain --sql-file qh10_20.sql --schema "…" --level physical`.
**Expected:** `WindowAssign(HOPPING size=20000ms slide=10000ms on event_time)` and
`WindowedAggregate(HOPPING 20000ms, keys=[…], 2 aggregate(s))`. Note the aggregate's label prints
the size and **not** the slide, so a hop and a tumble of the same size are indistinguishable from
that line alone — the assign line is the only place the slide appears.
**Vacuity:** Not stateful.

---

## 3. Kind — SESSION (implemented in the runtime, no SQL syntax)

`SessionWindows` is complete, tested, and reachable from no plan node: there is no session assign
operator, no session state in `SlicedAggregateState` (which refuses a session spec outright), and
`PhysicalPlanBuilder` refuses both syntaxes. Every case in this section is therefore
**blocked-by-syntax** on the server and is written against the class directly through the embedded
harness, so that the day a `SessionAssignOperator` lands the cases are already here. WIN-031 to
WIN-034 pin the refusals; WIN-035 to WIN-042 pin the semantics the refusal is deferring.

### WIN-031 — `TABLE(SESSION(...))` is refused with the documented reason — **blocked-by-syntax**
**Intent:** `docs/SQL_SUPPORT.md` says "`SESSION` windows ❌ `PRV-2020` — implemented in the runtime,
no SQL surface yet". Check that the code says the same thing, in the same code, with a reason.
**Falsifier:** Acceptance, a different code, or a message that does not say the runtime has it.
**Steps:** `pravaha validate --sql "SELECT window_start, window_end, user_id, COUNT(*) FROM TABLE(SESSION(TABLE s0, DESCRIPTOR(event_time), INTERVAL '30' SECOND)) GROUP BY window_start, window_end, user_id" --schema "…"`.
**Expected:** `PRV-2020` with "SESSION windows exist in the runtime but are not wired to SQL yet:
their state is a per-key interval set rather than a slice grid, so they need the keyed state store.
Use TUMBLE or HOP." Matches the documentation row exactly.
**Vacuity:** Not stateful.

### WIN-032 — `GROUP BY SESSION(...)` is refused with a *different* message — **blocked-by-syntax**
**Intent:** `isWindowFunction` accepts `SESSION`, so the grouped form reaches `buildGroupedWindow`,
whose `switch` has no SESSION arm and falls to `default`. Two syntaxes for one unbuilt feature
produce two different explanations, and only one of them tells the reader the runtime has it.
**Falsifier:** The two messages being identical (then this case is stale and should be deleted).
**Steps:** `pravaha validate --sql "SELECT user_id, COUNT(*) FROM s0 GROUP BY SESSION(event_time, INTERVAL '30' SECOND), user_id" --schema "…"`.
**Expected:** `PRV-2020` with "GROUP BY SESSION is not supported; use TUMBLE or HOP" — the same code,
a strictly less useful message, and no mention that the implementation exists. Defect: one refusal,
two texts.
**Vacuity:** Not stateful.

### WIN-033 — `WindowSpec.session(gap)` cannot be sliced — **blocked-by-syntax**
**Intent:** The two guards that make a session unreachable from the sliced path are the ones that
would have to be removed for SQL support, so they are the specification of what is missing.
**Falsifier:** Either call succeeding.
**Setup:** Embedded harness.
**Steps:** `WindowSpec.session(30 s).sliceSizeNanos()`; `new SlicedWindows(WindowSpec.session(30 s))`.
**Expected:** `UnsupportedOperationException("session windows are not sliced: their boundaries are
decided by the data, not by the clock, so there is no fixed interval that never straddles one")` and
`IllegalArgumentException("session windows are not sliced; use SessionWindows")` respectively.
**Vacuity:** Not stateful.

### WIN-034 — A session gap of zero or negative is refused — **blocked-by-syntax**
**Falsifier:** Acceptance. A zero gap makes every record its own session with `end == start`, so
`closedBy` fires them all immediately and the merge logic never runs.
**Steps:** `new SessionWindows(0)`; `new SessionWindows(-1)`; `WindowSpec.session(0)`.
**Expected:** `IllegalArgumentException("session gap must be positive, got 0")` from `SessionWindows`,
and "window size must be positive, got 0" from `WindowSpec`. Two different messages for the same
misuse, because the gap is carried in the `sizeNanos` slot.
**Vacuity:** Not stateful.

### WIN-035 — One key, three events inside the gap, is one session — **blocked-by-syntax**
**Intent:** The base case. Gap 30 s; events at 0, 10 s, 20 s.
**Falsifier:** More than one session for the key, or an end other than 50 s.
**Setup:** `SessionWindows(30 s)`.
**Steps:** `record(1, 0)`; `record(1, 10 s)`; `record(1, 20 s)`; then `sessionsOf(1)`.
**Expected:** One session `[0, 50 s)`. `record(1, 0)` creates `[0, 30 s)`; `record(1, 10 s)` finds
`floorEntry(10 s) = (0 → 30 s)` with `30 s ≥ 10 s` so it absorbs, giving `[0, max(40 s, 30 s)) = [0, 40 s)`;
`record(1, 20 s)` gives `[0, max(50 s, 40 s)) = [0, 50 s)`. `merges() == 2`.
**Vacuity:** With merging removed, `sessionsOf(1)` returns three sessions `[0,30)`, `[10,40)`,
`[20,50)` — a distinguishable, wrong answer rather than an empty one.

### WIN-036 — An event one nanosecond past the gap starts a second session — **blocked-by-syntax**
**Intent:** The boundary of "no gap longer than gap". The existing session ends at `last + gap`; an
event at exactly that instant must **merge** (the class documents touching sessions as merged,
because "the gap between them is zero and zero is not more than the gap"), and one nanosecond later
must not.
**Falsifier:** The 30 s event starting a new session, or the 30.000000001 s event merging.
**Setup:** `SessionWindows(30 s)`.
**Steps:** (a) `record(1, 0)`; `record(1, 30 s)`; `sessionsOf(1)`. (b) fresh instance:
`record(1, 0)`; `record(1, 30 s + 1 ns)`; `sessionsOf(1)`.
**Expected:** (a) one session `[0, 60 s)` — `floorEntry(30 s) = (0 → 30 s)` and `30 s ≥ 30 s` is true,
so it absorbs and `end = max(60 s, 30 s) = 60 s`. (b) two sessions, `[0, 30 s)` and
`[30 s + 1 ns, 60 s + 1 ns)` — `30 s ≥ 30 s + 1 ns` is false, so no absorption.
**Vacuity:** The two sub-cases differ by one nanosecond and give different session counts, so an
implementation that ignores the boundary fails exactly one of them.

### WIN-037 — A late event between two sessions merges them into one — **blocked-by-syntax**
**Intent:** "Merging is the whole problem." A record landing between two existing sessions joins
them to each other; an implementation that assigns to the nearest and stops produces two where there
is one, with no error.
**Falsifier:** Two sessions remaining after the bridging record.
**Setup:** `SessionWindows(30 s)`.
**Steps:** `record(1, 0)` → `[0, 30 s)`. `record(1, 55 s)` → `[55 s, 85 s)` (no overlap: `floorEntry(55 s)`
is `(0 → 30 s)` and `30 s ≥ 55 s` is false). Then `record(1, 25 s)`.
**Expected:** One session `[0, 85 s)`. Walk it: `start = 25 s`, `end = 55 s`.
`floorEntry(25 s) = (0 → 30 s)`, `30 s ≥ 25 s` → absorb: `start = 0`, `end = max(55 s, 30 s) = 55 s`,
first merge. `ceilingEntry(0) = (55 s → 85 s)`, `55 s ≤ 55 s` → absorb: `end = max(55 s, 85 s) = 85 s`,
second merge. `merges() == 2`, `openSessions() == 1`.
**Vacuity:** Defends against "merge once": an implementation doing only the `before` absorption
leaves two sessions, `[0, 55 s)` and `[55 s, 85 s)`, which look plausible and total the same duration.

### WIN-038 — At most one merge on each side, and the second one is reachable — **blocked-by-syntax**
**Intent:** The class removed a loop in favour of two absorptions and says so: "This was a loop until
a seeded 'merge only once' bug passed every test, which is what a loop that can only run once does."
WIN-037 is the test that makes the second absorption reachable; this case is the proof that a third
cannot be needed.
**Falsifier:** Any sequence of records producing a state in which a third absorption would be
required — i.e. two existing sessions both within one gap of a new record's *extended* interval.
**Setup:** `SessionWindows(30 s)`.
**Steps:** Build three sessions as far apart as possible while still bridgeable in principle:
`record(1, 0)` → `[0,30)`; `record(1, 40 s)` → `[40,70)`; `record(1, 80 s)` → `[80,110)`. Then
`record(1, 35 s)`.
**Expected:** Two sessions afterwards, not one: `record(1, 35 s)` gives `start = 35 s, end = 65 s`;
`floorEntry(35 s) = (0 → 30 s)` and `30 s ≥ 35 s` is false, so no `before` merge; `ceilingEntry(35 s) =
(40 s → 70 s)` and `40 s ≤ 65 s` → absorb, giving `[35 s, 70 s)`. `[80, 110)` is untouched because
`70 s < 80 s`. Existing sessions are maximal, so consecutive starts are at least one gap apart and
one record reaching one gap forward can meet at most one of them on each side — which is the proof,
and this is the case that exercises the side that is not the first.
**Vacuity:** Distinguishes correct (2 sessions) from over-merging (1) and under-merging (3).

### WIN-039 — Sessions are per key and never merge across keys — **blocked-by-syntax**
**Falsifier:** `sessionsOf(2)` reflecting records written under key 1.
**Setup:** `SessionWindows(30 s)`.
**Steps:** `record(1, 0)`; `record(2, 10 s)`; `record(1, 20 s)`; `sessionsOf(1)`; `sessionsOf(2)`;
`keyCount()`; `openSessions()`.
**Expected:** `sessionsOf(1) = [[0, 50 s)]`; `sessionsOf(2) = [[10 s, 40 s)]`; `keyCount() == 2`;
`openSessions() == 2`. `merges() == 1` — only key 1 merged.
**Vacuity:** A single shared interval tree would give one session `[0, 50 s)` and `keyCount() == 1`.

### WIN-040 — `closedBy` releases a session only when the watermark passes its end — **blocked-by-syntax**
**Intent:** "A session closes when the watermark passes its end, which is `lastEvent + gap` — so
closing is exactly the statement 'no further record can extend this one'." Removing on fire is what
bounds the state.
**Falsifier:** A session closing before its end, or `openSessions()` not dropping after it closes.
**Setup:** `SessionWindows(30 s)`; `record(1, 0)`; `record(1, 20 s)` → one session `[0, 50 s)`.
**Steps:** `closedBy(49 s)`; `openSessions()`; `closedBy(50 s)`; `openSessions()`; `keyCount()`.
**Expected:** `closedBy(49 s)` returns empty and `openSessions() == 1` — the test is
`session.getValue() <= watermarkNanos` and `50 s ≤ 49 s` is false. `closedBy(50 s)` returns
`[Session(1, 0, 50 s)]` with `durationNanos() = 50,000,000,000`; afterwards `openSessions() == 0` and
`keyCount() == 0`, because `byKey.entrySet().removeIf(empty)` drops the key entirely.
**Vacuity:** Without the removal, `openSessions()` stays 1 after the close — a leak that this case
detects and that a "the right sessions came out" assertion does not.

### WIN-041 — Closed sessions come out ordered by end, then by key — **blocked-by-syntax**
**Intent:** A consumer applying session results in arrival order needs a defined order; the class
sorts by `endNanos` then `key`.
**Falsifier:** Any other order.
**Setup:** `SessionWindows(10 s)`; `record(2, 0)` → key 2 `[0,10)`; `record(1, 5 s)` → key 1 `[5,15)`;
`record(3, 0)` → key 3 `[0,10)`.
**Steps:** `closedBy(20 s)`.
**Expected:** `[Session(2, 0, 10 s), Session(3, 0, 10 s), Session(1, 5 s, 15 s)]` — ends 10, 10, 15;
within the tie, keys 2 then 3.
**Vacuity:** A `HashMap` iteration order would give a different sequence on some runs; the case is
only meaningful if it is run more than once and the order is stable.

### WIN-042 — A session's state is bounded by open sessions, not by history — **blocked-by-syntax**
**Intent:** The bounded-state claim for sessions, which is the reason they need the keyed state store
before they can be wired to SQL.
**Falsifier:** `openSessions()` growing with the record count once sessions are being closed.
**Setup:** `SessionWindows(1 s)`.
**Steps:** For *i* = 1…100,000: `record(i mod 1000, i × 10,000,000 ns)` (10 ms apart), and every
1,000 records call `closedBy(i × 10,000,000 - 2,000,000,000)` (two seconds behind). Record
`openSessions()` and `keyCount()` after each call.
**Expected:** `keyCount()` reaches 1,000 and stays there. `openSessions()` stabilises at roughly
1,000 — one open session per key, since each key sees a record every 10 s (1,000 keys × 10 ms) which
is longer than the 1 s gap, so each key's previous session closes before its next record. It must
not approach 100,000. Record the observed plateau.
**Vacuity:** Defends against "nothing was ever recorded": `merges()` plus `closedBy` return sizes
must together account for all 100,000 records. A run where `closedBy` returns nothing every time
passes a naive "state stayed small" assertion trivially.

---

## 4. Kind — CUMULATE (does it exist at all?)

**Verdict from the source, to be confirmed by execution:** it does not. The string `CUMULATE` does
not appear anywhere in the repository — not in `WindowSpec.Kind` (which has exactly `TUMBLING`,
`HOPPING`, `SESSION`), not in `PhysicalPlanBuilder.isWindowFunction` (which matches exactly
`TUMBLE`, `HOP`, `SESSION`), not in `docs/SQL_SUPPORT.md`, not in the design, not in any ADR, not in
any test. Calcite 1.40 *does* define `CUMULATE` as a windowing table function, so the SQL parses and
is refused by Pravaha rather than by Calcite. The four cases below establish that in each of the
places a user would meet it.

### WIN-043 — `TABLE(CUMULATE(...))` is refused with a code
**Falsifier:** Acceptance, or a raw Java exception instead of `PRV-2020`.
**Steps:** `pravaha validate --sql "SELECT window_start, window_end, user_id, COUNT(*) FROM TABLE(CUMULATE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '2' SECOND, INTERVAL '10' SECOND)) GROUP BY window_start, window_end, user_id" --schema "…"`.
**Expected:** `PRV-2020` "unsupported windowing function CUMULATE" — the `default` arm of
`buildWindowAssign`'s switch. The message names the function but, unlike the SESSION arm, offers no
alternative and does not say whether it is unbuilt or unsupported-on-purpose.
**Vacuity:** Not stateful.

### WIN-044 — `GROUP BY CUMULATE(...)` produces a *worse* error than the table form
**Intent:** `isWindowFunction` does not list CUMULATE, so `groupedWindowCall` returns null and the
`$CUMULATE` call is handed to `ExpressionCompiler` as an ordinary scalar expression.
**Falsifier:** The same `PRV-2020` as WIN-043 (then the two paths agree and this case is stale).
**Steps:** `pravaha validate --sql "SELECT user_id, COUNT(*) FROM s0 GROUP BY CUMULATE(event_time, INTERVAL '2' SECOND, INTERVAL '10' SECOND), user_id" --schema "…"`.
**Expected:** Whatever `ExpressionCompiler` says about an unknown operator — record it verbatim. A
message about an unsupported *expression* for what is actually an unsupported *window* is a
misdiagnosis, and it is the third distinct rendering of "this window kind is not built" in this
file (WIN-031, WIN-032, WIN-043).
**Vacuity:** Not stateful.

### WIN-045 — `docs/SQL_SUPPORT.md` does not mention CUMULATE in either direction
**Intent:** The document's Aggregation table lists `TUMBLE ✅`, `HOP ✅`, `SESSION ❌ PRV-2020`. A
reader concludes those are the three window kinds that exist. CUMULATE is a SQL:2016 windowing table
function that Calcite accepts, so a user can type it, and the document has no row for it.
**Falsifier:** Finding a CUMULATE row.
**Steps:** `grep -ni cumulate docs/SQL_SUPPORT.md docs/USER_GUIDE.md docs/CONCEPTS.md docs/system_design.md`.
**Expected:** No matches. Defect: the supported-construct table is not closed over what the parser
accepts, so "not listed" and "refused" are not the same set. Recommend a `CUMULATE ❌ PRV-2020` row
beside the SESSION one.
**Vacuity:** Not stateful.

### WIN-046 — CUMULATE is not expressible as a HOP either
**Intent:** Before recommending a workaround, check there is one. CUMULATE's windows share a start
and grow — `[0,2)`, `[0,4)`, `[0,6)`, `[0,8)`, `[0,10)` — which no (size, slide) pair produces,
because every hop's windows are congruent.
**Falsifier:** Any `WindowSpec(size, slide)` whose `windowEndsContaining(t)` matches CUMULATE's
window set for all *t* in one cycle.
**Setup:** Embedded harness.
**Steps:** For *t* = 1 s, enumerate `windowEndsContaining` for `hopping(10 s, 2 s)` and compare with
CUMULATE(2 s step, 10 s max) for the same *t*.
**Expected:** `hopping(10 s, 2 s)` gives ends `{2, 4, 6, 8, 10}` — the same *ends* — but the windows
are `[-8,2)`, `[-6,4)`, `[-4,6)`, `[-2,8)`, `[0,10)`, each 10 s wide, whereas CUMULATE's are
`[0,2)`, `[0,4)`, `[0,6)`, `[0,8)`, `[0,10)`, of increasing width. The sums differ for any row before
*t* = 0 of the cycle. Conclusion: there is no rewrite, so WIN-043's refusal cannot suggest one and
correctly does not — but the absence of a suggestion should be explained in the message rather than
left blank.
**Vacuity:** Pure arithmetic.

### WIN-047 — A cumulative answer built by hand from HOP is not the same query
**Intent:** Completes WIN-046 with the observable difference, so the refusal's cost is quantified.
**Falsifier:** Identical totals.
**Setup:** `s0` bound to `b_plus.csv`.
**Steps:** Register Q_H(2, 10) as `v_cum_attempt --keys 0,1,2`; read the window ending at 10.000.
**Expected:** The window ending 10.000 is `[0, 10)` and holds rows 1 and 2 → `n = 2, total = 1 + 2 = 3`,
which happens to equal a CUMULATE `[0,10)` pane. But the window ending 2.000 is `[-8, 2)` and holds
row 1 only → `total = 1`, where CUMULATE's `[0,2)` also holds row 1 → `total = 1`. They agree here
only because the dataset has nothing before 0. Add a row `0,100,512,-1.000000000` and rerun: the hop's
`[-8,2)` becomes `total = 513` and CUMULATE's `[0,2)` stays 1. Record both.
**Vacuity:** The added row changes exactly one number, so a harness that ignores negative event times
is caught here.

### WIN-048 — `WindowSpec.Kind` has exactly three values
**Intent:** The enum is the engine's complete list of window kinds; anything else is a plan-time
refusal by construction.
**Falsifier:** A fourth constant.
**Steps:** Read `WindowSpec.Kind`.
**Expected:** `TUMBLING`, `HOPPING`, `SESSION`. No `CUMULATING`, no `SLIDING` alias.
**Vacuity:** Not stateful.

### WIN-049 — An unknown window function name is refused, not silently scanned
**Intent:** The `default` arm must catch anything Calcite lets through, including a user-defined
table function with a windowing-looking name.
**Falsifier:** A query with `TABLE(WINDOW(...))` or `TABLE(TUMBLING(...))` planning to a scan.
**Steps:** `pravaha validate` with `TABLE(TUMBLING(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND))`
and with `TABLE(SLIDE(...))`.
**Expected:** Refused — by Calcite as an unknown function, or by `PRV-2020` "unsupported windowing
function …". Record which layer answers, because a Calcite-layer error carries no `PRV` code.
**Vacuity:** Not stateful.

### WIN-050 — Lower-case and mixed-case window function names behave identically
**Intent:** `buildWindowAssign` upper-cases the operator name; `isWindowFunction` upper-cases and
strips `$`. A case difference must not change whether a window is recognised.
**Falsifier:** `tumble(...)` planning differently from `TUMBLE(...)`.
**Steps:** `pravaha explain` for `TABLE(tumble(...))`, `TABLE(Tumble(...))`, `TABLE(TUMBLE(...))`,
and for `GROUP BY hop(...)`.
**Expected:** Identical physical plans in all four. The upper-casing uses `Locale.ROOT`, so this also
holds under a Turkish locale — run one of the four with `-Duser.language=tr` to confirm the dotted-I
does not turn `TUMBLE` into something else.
**Vacuity:** Not stateful.

---

## 5. Size — 100 ms, 1 s, 1 m, 1 h, 1 d

One dataset shape at five scales, so a scale-dependent defect stands out against four controls.
**Dataset S(U)** for unit *U* ∈ {100 ms, 1 s, 1 m, 1 h, 1 d} is five rows for `user_id = 100` at
event times `0`, `U/2`, `U`, `2U − 1 ns`, `2U`, with amounts 1, 2, 4, 8, 16, plus a pusher
`6,999,0,10U`. The expected windows are always the same three, whatever *U*:

| window | rows | n | total |
|---|---|---|---|
| `[0, U)` | amounts 1, 2 | 2 | `1 + 2 = 3` |
| `[U, 2U)` | amounts 4, 8 | 2 | `4 + 8 = 12` |
| `[2U, 3U)` | amount 16 | 1 | `16` |

`3 + 12 + 16 = 31 = 2^5 − 1`, so any row assigned to the wrong window changes a total to a value
that occurs nowhere else in the expected set. The pusher at `10U` puts the watermark on `s0` at
`10U`, and `windowsCompletedBetween` fires ends `U, 2U, … , 10U` — ten windows, of which seven are
empty and emit nothing.

### WIN-051 — TUMBLE 100 ms assigns and totals correctly
**Falsifier:** Any total other than 3, 12, 16; any fourth non-empty window; any `window_end -
window_start ≠ 100,000,000 ns`.
**Setup:** `s0` bound to `s100ms.csv` = S(100 ms): rows at 0.000, 0.050, 0.100, 0.199999999, 0.200;
pusher at 1.000.
**Steps:** Register `TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '0.1' SECOND)` as
`v_100ms --keys 0,1,2`; wait for ROWS IN 6; read it.
**Expected:** Three rows: `[0, 0.1)` n=2 total=3; `[0.1, 0.2)` n=2 total=12; `[0.2, 0.3)` n=1
total=16. `sliceStartFor(0.199999999 s) = floorDiv(199,999,999, 100,000,000) × 100,000,000 =
1 × 100,000,000 = 100,000,000` — the 0.1 window, not the 0.2 one.
**Vacuity:** Defends against key collapse (`--keys 2` gives 1 row) and against a source that never
read (ROWS IN must be 6). The 2^5−1 property is the arithmetic guard: a sum of 31 in one window
means every row landed in one window.

### WIN-052 — `INTERVAL '0.1' SECOND` becomes exactly 100,000,000 ns
**Intent:** ADR-012 says the engine is nanoseconds throughout; the planner reads Calcite's
**millisecond** normalisation and multiplies by 1,000,000. That conversion is the only place a
window size can be silently wrong by a factor of a thousand.
**Falsifier:** `EXPLAIN` showing `size=0ms` or `size=100000ms`.
**Steps:** `pravaha explain --sql "…INTERVAL '0.1' SECOND…" --level physical`; also try the
equivalent spellings `INTERVAL '100' MILLISECOND` (if Calcite accepts it in this position) and
`INTERVAL '0.100' SECOND`.
**Expected:** `WindowAssign(TUMBLING size=100ms slide=100ms on event_time)` for every spelling that
is accepted, and identical result rows from WIN-051 for each.
**Vacuity:** Not stateful.

### WIN-053 — A 100 ms window against a 100 ms watermark tick
**Intent:** `pravaha.watermark.tick` is 100 ms in `win.yaml` and the window is also 100 ms, so a
window can only close on a tick and up to one whole window of results is always outstanding. The
plan's own note — "Tick > window size means windows close in batches" — needs the equal case stated
too.
**Falsifier:** A window's results appearing before the tick following its close, or more than one
tick's worth of windows arriving in a single commit when the input is dense.
**Setup:** `s0` bound to a 200-row file: `i,100,1,<i × 1 ms>` for *i* = 1…200, pusher at 10.000.
**Steps:** `pravaha subscribe --view v_100ms_dense --limit 40` before registering; register
`TUMBLE … INTERVAL '0.1' SECOND` with `--keys 0,1,2`; record the arrival wall-clock time of each
commit group.
**Expected:** Two windows of real data — `[0, 0.1)` holds *i* = 1…99 (`n = 99`, `total = 99`) and
`[0.1, 0.2)` holds *i* = 100…199 (`n = 100`, `total = 100`) — plus `[0.2, 0.3)` holding *i* = 200
(`n = 1`). `99 + 100 + 1 = 200` = every row. Because the whole file is read in one pass long before
the first tick, expect all three to arrive in **one** commit group rather than three, which is the
"close in batches" behaviour arriving at the equal case.
**Vacuity:** Defends against a dry source (ROWS IN 201 including the pusher) and against an unarmed
watermark (`watermark.lag.seconds` must be finite before the commit group is believed to be a close
rather than a shutdown flush).

### WIN-054 — A sub-millisecond window is truncated to zero, and the message does not say so
**Intent:** The brief calls 100 ms "the nanosecond path". It is not: `buildWindowAssign` reads
`((BigDecimal) literal.getValue4()).longValue() * 1_000_000L`, and `longValue()` on a BigDecimal
below 1 is **0**. The engine's finest expressible window is one millisecond, and asking for finer
gives an error about positivity rather than about precision.
**Falsifier:** A 100-microsecond window being accepted and producing 100 µs windows.
**Steps:** `pravaha validate --sql "…TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '0.0001' SECOND)…"`;
repeat with `'0.0005'`, `'0.000999'` and `'0.001'`.
**Expected:** `0.0001`, `0.0005` and `0.000999` all truncate to 0 ms and are refused with
"window size must be positive, got 0" — an `IllegalArgumentException` with no `PRV` code, naming a
zero the user never typed. `0.001` is accepted and gives `size=1ms`. Defect: the message should say
that intervals are taken to millisecond precision. `WindowAssignOperator.label()` compounds it — a
size of 999,999 ns would render as `0ms` in `EXPLAIN` even if it could be built.
**Vacuity:** Not stateful.

### WIN-055 — TUMBLE 1 s assigns and totals correctly
**Falsifier:** Any total other than 3, 12, 16; any width ≠ 1,000,000,000 ns.
**Setup:** `s0` bound to `s1s.csv` = S(1 s): rows at 0.000, 0.500, 1.000, 1.999999999, 2.000;
pusher at 10.000.
**Steps:** Register `TUMBLE … INTERVAL '1' SECOND` as `v_1s --keys 0,1,2`; read it.
**Expected:** `[0,1)` n=2 total=3; `[1,2)` n=2 total=12; `[2,3)` n=1 total=16. The row at
1.999999999 s is in `[1,2)`: `floorDiv(1,999,999,999, 1,000,000,000) = 1`.
**Vacuity:** As WIN-051.

### WIN-056 — Every spelling of a one-second interval gives the same window
**Intent:** A window size is the most consequential literal in a continuous query and there are four
ways to write it.
**Falsifier:** Any two spellings producing different `EXPLAIN` output or different rows.
**Steps:** `EXPLAIN` and register each of `INTERVAL '1' SECOND`, `INTERVAL '1.0' SECOND`,
`INTERVAL '1000' MILLISECOND`, `INTERVAL '0:01' MINUTE TO SECOND`.
**Expected:** `size=1000ms slide=1000ms` for every spelling Calcite accepts, and the same three rows
as WIN-055. Any spelling Calcite rejects is recorded as a parser limit, not an engine one.
**Vacuity:** Not stateful.

### WIN-057 — A 1 s window on the default 10 s lateness keeps ten windows permanently open
**Intent:** `s10` declares `out-of-orderness: 10s`, the engine default. The watermark is
`maxEventTime − 10 s`, so with 1 s windows there are always ten closed-by-data windows that have not
fired. This is the state cost of a default that was chosen for a source, applied to a window size
that was chosen for a question, and nothing connects the two.
**Falsifier:** The same number of windows appearing under `s0` and `s10`.
**Setup:** Two registrations of the same SQL, one over `s0` and one over `s10`, both against the same
20-row file `i,100,1,<i × 1 s>` for *i* = 1…20 with **no** pusher.
**Steps:** Register `v_1s_s0` and `v_1s_s10`, both `--keys 0,1,2`; wait for both to reach ROWS IN 20
and stop; read both; compare row counts.
**Expected:** `v_1s_s0`: watermark 20 s, ends 1…20 fire, windows `[0,1)` … `[19,20)` — but `[0,1)`
holds no row (the first row is at 1 s) so **19** rows appear, each `n=1, total=1`, for windows
`[1,2)` … `[19,20)`. `v_1s_s10`: watermark `20 − 10 = 10 s`, ends 1…10 fire, so **9** rows, windows
`[1,2)` … `[9,10)`. The difference is exactly 10 windows = 10 rows never emitted, from a setting
that mentions neither windows nor rows.
**Vacuity:** Defends against key collapse in both views (`--keys 2` would give 1 row each and hide
the whole effect) and against a dry source (both must read ROWS IN 20).

### WIN-058 — A thousand rows per second into 1 s windows sums exactly
**Intent:** A density check at a size where the per-row cost matters: 1,000 rows in each window, and
the sum must be the count, not an approximation.
**Falsifier:** Any window with `n ≠ 1000`, or a `total ≠ n`.
**Setup:** `dense1s.csv`: *i* = 1…10,000 with `user_id=100`, `amount=1`, `event_time = i ms`;
pusher `10001,999,0,60.000`.
**Steps:** Register `TUMBLE … INTERVAL '1' SECOND` as `v_dense1s --keys 0,1,2`; read it.
**Expected:** 11 rows. `[0,1)` holds *i* = 1…999 → `n = 999, total = 999`; `[1,2)` … `[9,10)` hold
1,000 each; `[10,11)` holds *i* = 10,000 → `n = 1`. `999 + 9×1000 + 1 = 10,000` — every row, once.
**Vacuity:** Defends against the exact round-1 failure: a row count of 10,000 collapsing into a
handful of keys. Here the key is the window boundary and there are 11 of them, so `SELECT
COUNT(*) FROM v_dense1s` must read 11 while `SUM(n)` reads 10,000. Both assertions are required;
either alone is passable with the feature broken.

### WIN-059 — TUMBLE 1 m assigns and totals correctly
**Falsifier:** Any total other than 3, 12, 16; any width ≠ 60,000,000,000 ns.
**Setup:** `s0` bound to `s1m.csv` = S(1 m): rows at 0, 30 s, 60 s, 119.999999999 s, 120 s; pusher at
600 s.
**Steps:** Register `TUMBLE … INTERVAL '1' MINUTE` as `v_1m --keys 0,1,2`; read it.
**Expected:** `[0,60)` n=2 total=3; `[60,120)` n=2 total=12; `[120,180)` n=1 total=16.
**Vacuity:** As WIN-051.

### WIN-060 — `INTERVAL '1' MINUTE` and `INTERVAL '60' SECOND` are the same window
**Intent:** Calcite normalises both to 60,000 ms. If they differed, a query rewritten by a reviewer
would change its answer.
**Falsifier:** Different `EXPLAIN` sizes, or different rows.
**Steps:** `EXPLAIN` both; register both; compare.
**Expected:** `size=60000ms` for both; identical three rows. Also confirm the two produce the same
**fingerprint** or not — if they do not, `pravaha queries` shows two computations for one
computation, which is a sharing defect rather than a windowing one; record which.
**Vacuity:** Not stateful.

### WIN-061 — A 1 m window loses its last window plus the lateness allowance
**Intent:** Quantify the tail deficit at the size the documentation uses, with no pusher — i.e. what
an operator actually sees.
**Falsifier:** Every window firing.
**Setup:** `s10` (10 s lateness) bound to `min60.csv`: *i* = 1…60, `user_id = 100`, `amount = 1`,
`event_time = i × 1 minute` — one row per minute for an hour. No pusher.
**Steps:** Register `TUMBLE … INTERVAL '1' MINUTE` as `v_1m_tail --keys 0,1,2`; wait for ROWS IN 60
and for the view to stop changing; count rows.
**Expected:** Highest event time 3,600 s; watermark `3,600 − 10 = 3,590 s`; ends that are multiples
of 60 s and ≤ 3,590 s run to 3,540 s, i.e. 59 ends. Windows `[0,60)` … `[3540,3600)`. `[0,60)` holds
nothing (the first row is at 60 s) so **58** rows appear, for `[60,120)` … `[3540,3600)`. Two of the
60 input rows — the ones at 3,540 s and 3,600 s — are never reported. Deficit: 2 of 60 rows, and it
is invisible: `ROWS IN` reads 60 and nothing says otherwise.
**Vacuity:** Defends against key collapse; `--keys 2` would show 1 row and no deficit at all.

### WIN-062 — The last window is not recovered by waiting
**Intent:** An operator's first reaction to WIN-061 is to wait. `WatermarkTracker.advance` returns
`current` unchanged when every partition is idle — "Everything is idle. The watermark stays where it
is rather than jumping to infinity" — so waiting is not a strategy and the number never completes.
**Falsifier:** The row count rising after the idle timeout.
**Setup:** WIN-061 done.
**Steps:** Wait 120 s (120 × the 1 s `idle-after`); re-read the view; read
`pravaha.query.watermark.lag.seconds` from `/actuator/prometheus` twice, 60 s apart.
**Expected:** Still 58 rows. The lag gauge **grows** by roughly 60 seconds over the 60 seconds of
waiting, because the watermark is frozen while wall-clock lag is measured against it — a rising lag
on a query with no input is the only signal available, and it is indistinguishable from a genuinely
slow query.
**Vacuity:** Defends against a dry source in reverse: here the source *is* dry and that is the
subject, so the control is `v_1m` from WIN-059 in the same run, which has a pusher and is complete.

### WIN-063 — TUMBLE 1 h assigns and totals correctly
**Falsifier:** Any total other than 3, 12, 16; any width ≠ 3,600,000,000,000 ns.
**Setup:** `s0` bound to `s1h.csv` = S(1 h): rows at 0, 1,800 s, 3,600 s, 7,199.999999999 s, 7,200 s;
pusher at 36,000 s.
**Steps:** Register `TUMBLE … INTERVAL '1' HOUR` as `v_1h --keys 0,1,2`; read it.
**Expected:** `[0,1h)` n=2 total=3; `[1h,2h)` n=2 total=12; `[2h,3h)` n=1 total=16. Widths
3,600,000,000,000 ns.
**Vacuity:** As WIN-051.

### WIN-064 — A sparse day of hourly windows walks every window, including the empty ones
**Intent:** `windowsCompletedBetween` steps by the slide from the previous watermark to the current
one and emits a window end for every step, whether or not any slice has data. `emitWindow` is then
called for each, and each call iterates `slicesOfWindowEnding` × the whole slice map. Twenty-four
windows a day is cheap; the cost model is what §5's last two cases and WIN-088 exercise.
**Falsifier:** Fewer window-end evaluations than `(watermark − firstWindowStart) / slide`.
**Setup:** `sparse_day.csv`: two rows only — `1,100,5,0.000` and `2,100,7,86,399.000` (one second
before the day ends) — plus pusher `3,999,0,90,000.000`.
**Steps:** Register `TUMBLE … INTERVAL '1' HOUR` as `v_sparse --keys 0,1,2`; read it; record wall
time from registration to the view settling.
**Expected:** Two rows: `[0, 3600)` n=1 total=5 and `[82800, 86400)` n=1 total=7. `86,399 s` is in
hour 23: `floorDiv(86,399, 3,600) = 23`, window `[82,800, 86,400)`. Twenty-five window ends are
walked (`firstWindowStart = 0 − 3600 = −3600`, `firstEnd = 0`, then 3,600 … 90,000 → 26 ends) and 24
of them fire empty. Settling should be immediate; record the figure as the baseline WIN-070 is
compared against.
**Vacuity:** Defends against key collapse (2 rows vs 1) and against a dry source (ROWS IN 3).

### WIN-065 — `emitted` grows by one entry per fired window and is released only when slices are
**Intent:** `WindowedAggregate.emitted` records what each window last emitted so a correction can
retract it exactly. `emitWindow` does `emitted.put(windowEnd, current)` for **every** window it
fires, including empty ones where `current` is an empty map — and the cleanup
`emitted.keySet().removeIf(...)` runs only `if (released > 0)`, i.e. only on an advance that also
discarded at least one slice. A stream of empty windows discards no slices, so the map grows.
**Falsifier:** `emitted.size()` staying bounded across a long run of empty windows.
**Setup:** Embedded harness — `emitted` is not observable on the server (§0.5). Build
`WindowedAggregate` with `tumbling(1 s)`; feed one row at *t* = 0; then call `advanceWatermark` at
1 s, 2 s, …, 100,000 s.
**Steps:** After each 1,000 advances, read `emitted.size()` by reflection or by a test-visible
accessor; also read `state.liveSlices()`.
**Expected:** `liveSlices()` drops to 0 after the first advance past 1 s (the single slice's
`lastWindowEndFor` is 1 s, so it is discarded at watermark 1 s). From then on every advance releases
**zero** slices, so the cleanup branch never runs and `emitted.size()` reaches 100,000 — one entry
per window, each holding an empty map, on an operator holding no data at all. Heap growth without
data is the defect; the retraction correctness the map exists for is unaffected.
**Vacuity:** Defends against "nothing ever fired": `emitted.size()` must be 0 before the first
advance and must equal the number of window ends walked afterwards. If both are 0, no window fired
and the case proves nothing.

### WIN-066 — A 1 h window and a 1 s watermark tick close in batches
**Intent:** The plan's note "Tick > window size means windows close in batches" with the inequality
the other way: a tick far finer than the window means 3,599 ticks in which nothing closes and one in
which a window does. The cost of the 3,599 is the thing to measure.
**Falsifier:** `advanceWatermark` doing work proportional to the tick rather than to the windows.
**Setup:** `s0` bound to `s1h.csv`; `pravaha.watermark.tick: 100ms`.
**Steps:** Register `v_1h` per WIN-063; sample process CPU from `/actuator/metrics/process.cpu.usage`
ten times over 60 s while the query is idle after settling.
**Expected:** Near-zero CPU. `advanceWatermark` returns immediately on `watermarkNanos <= watermark`,
and the watermark is frozen once the source is dry, so a fine tick over a coarse window costs one
comparison per tick. Record the figure; a non-trivial idle cost here is a `PERF` finding surfaced by
a `WIN` setup.
**Vacuity:** Defends against an unarmed watermark: if `watermark.lag.seconds` is absent, the tick is
not running and "low CPU" means "nothing is happening", which is not the claim.

### WIN-067 — TUMBLE 1 d assigns and totals correctly
**Falsifier:** Any total other than 3, 12, 16; any width ≠ 86,400,000,000,000 ns.
**Setup:** `s0` bound to `s1d.csv` = S(1 d): rows at 0, 43,200 s, 86,400 s, 172,799.999999999 s,
172,800 s; pusher at 864,000 s (ten days).
**Steps:** Register `TUMBLE … INTERVAL '1' DAY` as `v_1d --keys 0,1,2`; read it.
**Expected:** `[0,1d)` n=2 total=3; `[1d,2d)` n=2 total=12; `[2d,3d)` n=1 total=16.
`INTERVAL '1' DAY` → 86,400,000 ms → 86,400,000,000,000 ns, which is 5 orders of magnitude below
`Long.MAX_VALUE` and not at risk.
**Vacuity:** As WIN-051.

### WIN-068 — Day-long windows are evicted by the default retention almost as they arrive
**Intent:** `Retention.DEFAULT` is 24 hours **of event time**, and a window result carries
`eventTimestampNanos = windowEnd`, so `ViewSink` files it at frontier = `windowEnd` and
`ServedView.evict()` drops any visible key written before `committedFrontier − 24 h`. With 1 d
windows that horizon is one window wide: the view can hold at most two days' results and a third
day's arrival evicts the first. The window size and the retention are the same number, chosen
independently, and nothing warns.
**Falsifier:** All three windows of WIN-067 being visible at the same time.
**Setup:** WIN-067 done; `v_1d` read immediately after each commit.
**Steps:** Read `v_1d`; read `pravaha.query.view.evicted` from `/actuator/prometheus`.
**Expected:** Walk it. Committed frontier after all three windows = 259,200 s (`[2d,3d)`'s end).
Horizon = `259,200 − 86,400 = 172,800 s`. `[0,1d)` was written at 86,400 s and `86,400 < 172,800`
→ **evicted**. `[1d,2d)` was written at 172,800 s and `172,800 < 172,800` is false → kept.
`[2d,3d)` kept. So the view holds **two** rows, totals 12 and 16, and `view.evicted` reads 1. The
window with total 3 is gone and nothing in the view says it ever existed.
**Vacuity:** Defends against key collapse *and* against reading too late: the case must also read the
view between the second and third commits, when all of `[0,1d)` and `[1d,2d)` are present, to prove
the row existed before it was evicted. A view that never held three rows has not demonstrated
eviction.

### WIN-069 — Retention cannot be changed from any shipped surface
**Intent:** WIN-068 is only a defect if it cannot be configured away. `QueryRegistry.retaining` and
the five-argument `register` accept a `Retention`, and **no** shipped surface reaches either: there
is no `--retention` flag (`PravahaCli` usage lists `register --name --sql-file [--keys] [--url]`),
no field in the REST register body, and no `pravaha.*` key. Every registration gets `Retention.DEFAULT`.
**Falsifier:** Finding any of the three.
**Steps:** `pravaha register --help`; `grep -rn retention` over `pravaha-cli`, `pravaha-server`,
`pravaha-flight` main sources and over `application.yaml`; POST a register body with a `retention`
field and see whether it is honoured or rejected.
**Expected:** No flag, no field, no key — the only hits in server code are three comments in
`PravahaMetrics`. Defect: a per-query bound that the design treats as a correctness-relevant choice
("Defaulting to forever would make every registration a leak nobody had decided to accept") is
undecidable by the person registering the query.
**Vacuity:** Not stateful.

### WIN-070 — One row at event time zero beside present-day rows walks every window since 1970
**Intent:** The sharpest size-dependent hazard in the area, and a candidate mechanism for "ingest
frozen, no error". `firstWindowStart()` returns `earliestWindowStart − slide`, and
`windowsCompletedBetween` walks from there to the watermark **one slide at a time**, appending a
`Long` per step. One row with `event_time = 0` — which is exactly what the `feedfile` and `delta`
plugins stamp on every row, and what the `filesystem` plugin stamps when `event.time` was not pushed
down — beside rows in 2026 makes that walk `(now − 0) / slide` long.
**Falsifier:** The query settling in bounded time with both rows accounted for.
**Setup:** `epoch_mix.csv`: `1,100,5,0.000000000` and `2,100,7,1767225600.000000000`
(2026-01-01T00:00:00Z), plus pusher `3,999,0,1767312000.000000000`.
**Steps:** Register three times, as `v_epoch_1h` (`INTERVAL '1' HOUR`), `v_epoch_10s`
(`INTERVAL '10' SECOND`) and `v_epoch_100ms` (`INTERVAL '0.1' SECOND`), each `--keys 0,1,2`. For each,
record wall time to settle, heap from `/actuator/metrics/jvm.memory.used`, and whether ROWS IN
reaches 3.
**Expected, by arithmetic:** the number of window ends walked on the first advance is
`watermark / slide`, and each is a `Long` appended to an `ArrayList` **before** any of them is fired:

| window size | ends walked | list size at ~16 bytes per boxed Long |
|---|---|---|
| 1 h | `1,767,312,000 / 3,600 = 490,920` | ~8 MB — survivable, but 490,918 empty `emitWindow` calls |
| 10 s | `1,767,312,000 / 10 = 176,731,200` | ~2.8 GB — heap exhaustion |
| 0.1 s | `1,767,312,000 / 0.1 = 17,673,120,000` | ~283 GB — certain kill |

Expect `v_epoch_1h` to settle slowly with two correct rows (`[0,1h)` n=1 total=5 and the 2026 hour
n=1 total=7); expect `v_epoch_10s` and `v_epoch_100ms` to hang or die. **A dead lane is not
reported:** `Lane.run` catches the `Throwable`, sets `state = FAILED`, and siblings carry on;
`pravaha queries` still shows the query and `PravahaMetrics` exposes `pravaha.query.running` from
`RegisteredQuery.state()`, not from the lane's. Record exactly what a user sees.
**Vacuity:** Defends against "the file was never read": ROWS IN for `v_epoch_1h` must reach 3. The
control is `v_1h` from WIN-063, identical SQL over data with no epoch row, which must settle
immediately — so the difference is attributable to the one row and not to the window size.

---

## 6. HOP — slide versus size

The governing arithmetic, used by every case in this section. The windows containing an event at *t*
are those whose end *e* satisfies `t < e ≤ t + S`, and ends are the multiples of *D*, so

> **count(t) = floor((t + S) / D) − floor(t / D)**

which equals `S/D` exactly when *D* divides *S*, and otherwise alternates between `⌊S/D⌋` and
`⌈S/D⌉` depending on where *t* sits within a slide. The slice width is `gcd(S, D)` and the number of
slices per window is `S / gcd(S, D)` — which is `S/D` only when *D* divides *S*, and is larger
otherwise.

### 6a. slide < size (overlapping)

### WIN-071 — The per-row window count formula holds across a grid of (size, slide) and *t*
**Intent:** Establish the arithmetic before any of it is used to predict a result, so a later
mismatch is attributable to the engine and not to the prediction.
**Falsifier:** Any cell where `windowEndsContaining(t).size()` differs from the formula.
**Setup:** Embedded harness on `SlicedWindows` only.
**Steps:** For each (S, D) ∈ {(20,10), (20,5), (10,1), (100,1), (1000,1), (10,3), (7,2), (10,10)}
seconds, and each *t* ∈ {0, 1 ns, 1 s, 2 s, 5 s, S−1 ns, S, S+1 ns, −1 ns, −S}: compare
`windowEndsContaining(t).size()` with `floorDiv(t+S, D) − floorDiv(t, D)`.
**Expected:** Equal in every cell. Spot values, computed by hand:
`(20,10), t=5 s: floor(25/10) − floor(5/10) = 2 − 0 = 2`.
`(20,5), t=5 s: floor(25/5) − floor(5/5) = 5 − 1 = 4`.
`(10,3), t=0: floor(10/3) − 0 = 3`; `(10,3), t=2 s: floor(12/3) − floor(2/3) = 4 − 0 = 4`.
`(7,2), t=0: floor(7/2) − 0 = 3`; `(7,2), t=1 s: floor(8/2) − floor(1/2) = 4 − 0 = 4`.
`(10,10), any t: 1`.
`(20,10), t=−1 ns: floor((20 s−1 ns)/10 s) − floor(−1 ns/10 s) = 1 − (−1) = 2`.
**Vacuity:** Pure arithmetic, no state.

### WIN-072 — Slide 5 s over a 20 s window puts each row in exactly four windows
**Falsifier:** `SUM(n) ≠ 12`, or any window's total differing from the table.
**Setup:** `s0` bound to `c_plus.csv` (amounts 10, 20, 30 at 5.000, 15.000, 25.000; pusher 60.000).
**Steps:** Register Q_H(5, 20) as `v_h5_20 --keys 0,1,2`; read it ordered by `window_start`.
**Expected:** Eight rows.

| window | rows | n | total |
|---|---|---|---|
| `[-10, 10)` | 10 | 1 | 10 |
| `[-5, 15)` | 10 | 1 | 10 |
| `[0, 20)` | 10, 20 | 2 | `10 + 20 = 30` |
| `[5, 25)` | 10, 20 | 2 | 30 |
| `[10, 30)` | 20, 30 | 2 | `20 + 30 = 50` |
| `[15, 35)` | 20, 30 | 2 | 50 |
| `[20, 40)` | 30 | 1 | 30 |
| `[25, 45)` | 30 | 1 | 30 |

`SUM(n) = 1+1+2+2+2+2+1+1 = 12 = 3 rows × 4 windows` — and `count(5 s) = floor(25/5) − floor(5/5) =
4` confirms the 4. Slice width `gcd(20, 5) = 5 s`, so four slices per window and each row is stored
once.
**Vacuity:** Defends against key collapse (8 rows vs 1 under `--keys 2`) and against degeneration to
tumble (`SUM(n)` would be 3) or to a doubled store (`SUM(n)` would be 24 with halved totals).

### WIN-073 — Slide 1 s over a 10 s window: one row, ten windows, same total in each
**Intent:** The ratio-10 case, and the shape that makes "the same number appearing ten times" the
correct answer rather than a duplication bug.
**Falsifier:** Fewer or more than 10 windows; any window with `n ≠ 1` or `total ≠ 7`.
**Setup:** `one10.csv`: `1,100,7,5.000`; pusher `2,999,0,60.000`.
**Steps:** Register Q_H(1, 10) as `v_h1_10 --keys 0,1,2`; read it.
**Expected:** Exactly 10 rows, windows ending at 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 seconds — i.e.
`[-4,6)`, `[-3,7)`, … , `[5,15)` — each `n = 1, total = 7`. `count(5 s) = floor(15/1) − floor(5/1) =
15 − 5 = 10 = ⌈10/1⌉`. Windows ending at 5 s and at 16…60 s fire empty.
**Vacuity:** Defends against key collapse: `--keys 2` would collapse all ten to one row of `n=1`,
which is exactly the wrong answer this case exists to catch.

### WIN-074 — Slide 1 s over a 100 s window: one row, one hundred windows
**Falsifier:** A row count other than 100.
**Setup:** `one100.csv`: `1,100,7,50.000`; pusher `2,999,0,300.000`.
**Steps:** Register Q_H(1, 100) as `v_h1_100 --keys 0,1,2`; read it; `SELECT COUNT(*)`.
**Expected:** 100 rows, windows ending at 51…150 s, each `n=1 total=7`.
`count(50 s) = floor(150/1) − floor(50/1) = 100`. The view holds 100 keys for one input row —
a 100× amplification that is correct and that nothing in `docs/SQL_SUPPORT.md`'s "`HOP` (sliding)
windows ✅" row warns about.
**Vacuity:** As WIN-073.

### WIN-075 — Slide 1 s over a 1000 s window: one row, one thousand windows
**Falsifier:** A row count other than 1000, or the query failing.
**Setup:** `one1000.csv`: `1,100,7,500.000`; pusher `2,999,0,3000.000`.
**Steps:** Register Q_H(1, 1000) as `v_h1_1000 --keys 0,1,2`; read `SELECT COUNT(*)`; record the
wall time to settle and `pravaha.query.view.size`.
**Expected:** 1,000 rows, windows ending 501…1500 s, each `n=1 total=7`. State: slice width
`gcd(1000,1) = 1 s`, `slicesPerWindow = 1000`, and one key, so at most 1,000 live accumulators —
well under `DEFAULT_MAX_SLICES` of 2,000,000. Work: `windowsCompletedBetween(499 s, 3000 s)` walks
2,502 ends, and `fire()` is called for each, each call building a 1,000-element slice list and
scanning the whole slice map — so roughly `2,502 × 1,000 × liveSlices` comparisons. Record the
settle time; it is the baseline for WIN-090.
**Vacuity:** Defends against key collapse; `view.size` must read 1,000, not 1.

### WIN-076 — A slide that does not divide the size gives both ⌊S/D⌋ and ⌈S/D⌉ in one dataset
**Intent:** Size 7 s, slide 2 s. `⌈7/2⌉ = 4`, `⌊7/2⌋ = 3`, `gcd(7,2) = 1 s` so seven slices per
window. Asserting "⌈size/slide⌉ windows per row" as a universal would fail a correct engine here.
**Falsifier:** Both rows landing in the same number of windows, or `SUM(n) ≠ 7`.
**Setup:** `d72.csv`: `1,100,1,0.000`; `2,100,2,1.000`; pusher `3,999,0,30.000`.
**Steps:** Register Q_H(2, 7) as `v_h2_7 --keys 0,1,2`; read it.
**Expected:** Four rows.

| window | rows | n | total |
|---|---|---|---|
| `[-5, 2)` | 1, 2 | 2 | 3 |
| `[-3, 4)` | 1, 2 | 2 | 3 |
| `[-1, 6)` | 1, 2 | 2 | 3 |
| `[1, 8)` | 2 | 1 | 2 |

`count(0) = floor(7/2) − floor(0/2) = 3 − 0 = 3`; `count(1 s) = floor(8/2) − floor(1/2) = 4 − 0 = 4`.
`SUM(n) = 2+2+2+1 = 7 = 3 + 4`.
**Vacuity:** Defends against key collapse and against a rounded slide: `gcd` rounded to 2 s would
give 3 slices (7/2 truncated) and a different, plausible set of windows.

### WIN-077 — Slices per window follow `size / gcd(size, slide)`, not `size / slide`
**Intent:** State size is `keys × live slices`, and the two formulas differ for every non-dividing
pair — so a state estimate built on `size/slide` understates a 7/2 hop by 3.5×.
**Falsifier:** Any disagreement with the table.
**Setup:** Embedded harness on `WindowSpec`.
**Steps:** Read `sliceSizeNanos()` and `slicesPerWindow()` for each pair; compare with `size/slide`.
**Expected:**

| size | slide | gcd | slices/window | `size/slide` | understated by |
|---|---|---|---|---|---|
| 20 s | 10 s | 10 s | 2 | 2 | — |
| 20 s | 5 s | 5 s | 4 | 4 | — |
| 10 s | 1 s | 1 s | 10 | 10 | — |
| 1000 s | 1 s | 1 s | 1000 | 1000 | — |
| 10 s | 3 s | 1 s | **10** | 3 | 3.3× |
| 7 s | 2 s | 1 s | **7** | 3 | 2.3× |
| 60 s | 45 s | 15 s | **4** | 1 | 4× |
| 10 s | 9.999 s | 1 ms | **10,000** | 1 | 10,000× |

The last row is the cliff WIN-088 takes.
**Vacuity:** Pure arithmetic.

### WIN-078 — An overlapping hop stores each row once, whatever the overlap
**Intent:** "O(1) per record instead of O(6), and six times less state" is the entire justification
for slicing. A row in ten windows must produce one accumulator update, not ten.
**Falsifier:** `liveSlices()` growing with the overlap ratio for a fixed input.
**Setup:** Embedded harness: `SlicedAggregateState` over `hopping(S, 1 s)` for S ∈ {10 s, 100 s, 1000 s}.
**Steps:** For each S, feed 1,000 rows for one key at event times 0, 1 s, …, 999 s; read
`liveSlices()` and `peakSlices()` before any `discardSlicesEndingBefore`.
**Expected:** `liveSlices() == 1000` in all three cases — one accumulator per (key, 1 s slice),
independent of S. A per-window implementation would give 10,000, 100,000 and 1,000,000 respectively.
Then `fire(1000 s)` must combine `slicesOfWindowEnding(1000 s)` = the S/1 s slices below 1000 s and
return one result whose `count` equals the number of rows in those slices: for S = 10 s that is 10
(`n = 10, total = 10`), for S = 100 s it is 100, for S = 1000 s it is 1000.
**Vacuity:** Defends against "nothing was stored": `peakSlices()` must be 1,000 in each run before
`fire` is believed. Zero live slices and an empty result would otherwise pass a "not per-window"
assertion trivially.

### 6b. slide = size (degenerates to tumble)

### WIN-079 — `HOP(size, size)` returns exactly what `TUMBLE(size)` returns
**Intent:** The brief's requirement, stated as an equality rather than as a resemblance. `WindowSpec`
allows `slide == size` (only `slide > size` is refused), `gcd(S,S) = S`, `slicesPerWindow = 1`, and
every line of `SlicedWindows` reduces to the tumbling case.
**Falsifier:** Any row present in one view and absent from the other, or any differing value.
**Setup:** `s0` bound to `a_plus.csv`.
**Steps:** Register `v_t10p` = Q_T(10) and `v_h10_10` = Q_H(10, 10), both `--keys 0,1,2`; read both
ordered by `window_start, user_id`; diff.
**Expected:** Both hold exactly these 5 rows, and the diff is empty:

| window | user | n | total |
|---|---|---|---|
| `[0,10)` | 100 | 2 | `10 + 20 = 30` |
| `[0,10)` | 200 | 1 | 30 |
| `[10,20)` | 100 | 1 | 40 |
| `[10,20)` | 200 | 1 | 50 |
| `[20,30)` | 100 | 1 | 60 |

`SUM(n) = 6`, which is every row of dataset A and not the pusher (the pusher at 40.000 is in
`[40,50)`, which never fires because the watermark is 40.000 and the test is `end ≤ watermark` on an
end of 50.000).
**Vacuity:** Defends against key collapse in both views, and against the two views being the same
view: `pravaha queries` must show two names. If the fingerprints match, one computation is serving
both and the equality is trivially true — record that as the answer to WIN-083 rather than as a pass
here.

### WIN-080 — The degenerate hop agrees with tumble on the boundary dataset too
**Intent:** WIN-079 uses data away from boundaries. Equality that holds only away from boundaries is
the equality that matters least.
**Falsifier:** Any differing row.
**Setup:** `s0` bound to `b_plus.csv`.
**Steps:** Register `v_t10b` = Q_T(10) and `v_h10_10b` = Q_H(10, 10), both `--keys 0,1,2`; diff.
**Expected:** Both hold exactly four rows: `[0,10)` `n=2 total=1+2=3`; `[10,20)` `n=2 total=4+8=12`;
`[20,30)` `n=3 total=16+32+64=112`; `[30,40)` `n=1 total=128`. `3+12+112+128 = 255 = 2^8 − 1`, so
every one of the eight rows is counted once and only once. Diff empty.
**Vacuity:** As WIN-079, plus: `SUM(n)` must be 8, the exact row count of dataset B.

### WIN-081 — The degenerate hop agrees with tumble at 100,000 rows
**Intent:** Equality at volume, where a difference in slice handling would show as a handful of
mismatched windows rather than as a wrong shape.
**Falsifier:** Any window present in one and absent from the other, or any differing `n`.
**Setup:** `s0` bound to `v_100000_100.csv` — dataset V(100,000, 100).
**Steps:** Register `v_tvol` = Q_T(10) and `v_hvol` = Q_H(10, 10), both `--keys 0,1,2`; wait for both
to settle; compare `COUNT(*)`, `SUM(n)`, and a full row-by-row diff.
**Expected:** Both hold `10 windows × 100 users = 1,000` rows. `SUM(n) = 99,999` (window 0 holds
9,999 rows, windows 1…9 hold 10,000 each: `9,999 + 9 × 10,000 = 99,999`). Per row: window 0's users
each have `n = 99` or `100` (9,999 rows over 100 users: user *k* for *k* = 1…99 gets 100 rows and one
user gets 99 — specifically `i mod 100` over *i* = 1…9,999 gives user 0 ninety-nine occurrences and
every other user one hundred), windows 1…9 give every user exactly `n = 100`. `total = n` because
`amount = 1`. Diff empty.
**Vacuity:** Defends against the round-1 failure directly: `COUNT(*)` must be 1,000 **and**
`SUM(n)` must be 99,999. A collapse to 100 keys would keep `SUM(n)` right and `COUNT(*)` wrong; a
double-count would keep `COUNT(*)` right and `SUM(n)` wrong. Both are required.

### WIN-082 — `gcd(S, S) = S` so a degenerate hop holds one slice per window
**Falsifier:** `slicesPerWindow() ≠ 1` or `sliceSizeNanos() ≠ S`.
**Setup:** Embedded harness.
**Steps:** For S ∈ {100 ms, 1 s, 10 s, 1 m, 1 h, 1 d}: `WindowSpec.hopping(S, S).sliceSizeNanos()`
and `.slicesPerWindow()`; compare with `WindowSpec.tumbling(S)`.
**Expected:** Identical in every case: slice = S, one slice per window. Also
`slicesOfWindowEnding(e)` returns a single-element list `[e − S]` for both kinds.
**Vacuity:** Pure arithmetic.

### WIN-083 — The two forms differ only in what `EXPLAIN` and the fingerprint say
**Intent:** Having established the answers are identical, establish what is *not*: the plan label
and, consequently, whether the two share a computation.
**Falsifier:** `EXPLAIN` reporting TUMBLING for the hop form.
**Steps:** `pravaha explain` both forms; `pravaha queries` with both registered.
**Expected:** `WindowAssign(TUMBLING size=10000ms slide=10000ms …)` versus
`WindowAssign(HOPPING size=10000ms slide=10000ms …)` — same numbers, different kind. Two distinct
fingerprints, so two computations, two `SlicedAggregateState` instances and two copies of state for
one answer. Not a correctness defect; a cost one, and worth recording because "registrations sharing
a fingerprint share one computation" is a headline property.
**Vacuity:** Not stateful.

### WIN-084 — Each row lands in exactly one window when slide = size, including on boundaries
**Falsifier:** Any *t* with `count(t) ≠ 1`.
**Setup:** Embedded harness, `hopping(10 s, 10 s)`.
**Steps:** For *t* ∈ {0, 1 ns, 5 s, 9.999999999 s, 10 s, 10 s + 1 ns, −1 ns, −10 s, −10 s − 1 ns}:
`windowEndsContaining(t)`.
**Expected:** A single end in every case. `t = 0 → [10 s]`; `t = 9.999999999 s → [10 s]`;
`t = 10 s → [20 s]` (half-open: the window ending at 10 s does not contain 10 s);
`t = −1 ns → [0]`; `t = −10 s → [0]`; `t = −10 s − 1 ns → [−10 s]`. Floor division is what makes the
negative cases land below rather than above.
**Vacuity:** Pure arithmetic.

### 6c. slide > size (gapped — refused)

### WIN-085 — A hop whose slide exceeds its size is refused
**Intent:** `WindowSpec`'s constructor refuses it outright, with the reasoning that gaps mean some
records belong to no window: "That is almost always a typo — and when it is not, it is a filter
followed by a tumble, which says what it means."
**Falsifier:** Acceptance.
**Steps:** `pravaha validate --sql "SELECT window_start, window_end, COUNT(*) FROM TABLE(HOP(TABLE s0, DESCRIPTOR(event_time), INTERVAL '60' SECOND, INTERVAL '10' SECOND)) GROUP BY window_start, window_end"`.
**Expected:** Refused with "a hop of 60000000000 ns over a window of 10000000000 ns leaves gaps:
records between windows would belong to none. Use a smaller slide, or express the gap as a filter."
Thrown as `IllegalArgumentException` from a record's compact constructor, so it carries **no `PRV`
code** and `ERRC` cannot enumerate it. Record exactly how the CLI renders it — message only, or a
stack trace.
**Vacuity:** Not stateful.

### WIN-086 — The rows that would belong to no window, counted
**Intent:** The refusal's justification made concrete, so the decision can be judged rather than
taken on trust.
**Falsifier:** Finding any *t* in the gap that `windowEndsContaining` places in a window.
**Setup:** Embedded harness, constructing `SlicedWindows` around a `WindowSpec` built by reflection
or by a test-visible constructor that bypasses the guard — the arithmetic is what is under test,
not the guard.
**Steps:** For S = 10 s, D = 60 s, evaluate `windowEndsContaining(t)` for *t* = 0, 5 s, 9.999999999 s,
10 s, 30 s, 59.999999999 s, 60 s.
**Expected:** `t = 0` → `firstEnd = floorDiv(0, 60)·60 + 60 = 60 s`; the loop condition is
`end − S ≤ t`, i.e. `50 s ≤ 0`, false → **empty list**. Same for 5 s, 10 s, 30 s and 59.999999999 s.
Only `t = 60 s` is in a window: `firstEnd = 120 s`, `120 − 10 = 110 ≤ 60`? No — also empty. In fact
*every* *t* is in an empty list under this arithmetic, because `firstEnd` is the first multiple of
*D* strictly above *t* and `firstEnd − S ≤ t` requires `firstEnd ≤ t + 10 s`, which for D = 60 s
holds only when *t* is within 10 s below a multiple of 60 s. Enumerate: *t* ∈ [50 s, 60 s) is in the
window ending at 60 s; everything else is in none. **50 of every 60 seconds — 83.3 % of a uniform
stream — belongs to no window and would be silently discarded.** The refusal is correct.
**Vacuity:** Pure arithmetic.

### WIN-087 — The refusal's suggested workaround is executable and gives the intended answer
**Intent:** A refusal that names a workaround is only useful if the workaround works. "Express the
gap as a filter" means a `WHERE` on the event time followed by a tumble.
**Falsifier:** The rewrite being refused, or giving a different answer from the gapped hop's
intent.
**Setup:** `s0` bound to `gap.csv`: one row per second for 180 s — `i,100,1,<i s>` for *i* = 1…180 —
plus pusher `181,999,0,600.000`.
**Steps:** Register
`SELECT window_start, window_end, user_id, COUNT(*) FROM TABLE(TUMBLE(TABLE s0, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) WHERE MOD(CAST(event_time AS BIGINT) / 1000000000, 60) < 10 GROUP BY window_start, window_end, user_id`
as `v_gap --keys 0,1,2` — or whatever expression `docs/SQL_SUPPORT.md` supports for the same
predicate; record if none does.
**Expected:** Three windows survive the filter: `[0,10)` holds rows at 1…9 s → `n = 9`; `[60,70)`
holds 60…69 s → `n = 10`; `[120,130)` holds 120…129 s → `n = 10`. `9 + 10 + 10 = 29` of 180 rows,
which is the 10-in-60 the gapped hop intended. If the predicate is not expressible, that is the
finding: the refusal recommends something the SQL surface cannot do.
**Vacuity:** Defends against a dry source (ROWS IN 181) and against the filter matching everything
(the unfiltered control `v_gap_all` must show 18 windows and `SUM(n) = 180`).

### WIN-088 — Slide one millisecond below the size is accepted and costs 10,000 slices per window
**Intent:** The cliff beside the refusal. `slide > size` is refused; `slide = size − 1 ms` is
accepted, and `gcd(10,000 ms, 9,999 ms) = 1 ms` makes every window 10,000 slices wide. The guard
protects against the typo that loses data and not against the typo that exhausts memory.
**Falsifier:** `slicesPerWindow()` reporting anything but 10,000, or the query registering and
surviving a real key count.
**Setup:** `s0` bound to `keys200.csv`: 200 users × one row per second for 60 s = 12,000 rows,
`i,<i mod 200>,1,<(i/200) s>`; pusher at 600 s.
**Steps:** `pravaha explain` for `HOP(…, INTERVAL '9.999' SECOND, INTERVAL '10' SECOND)`; then
register it as `v_cliff --keys 0,1,2`; watch `pravaha queries` and the logs.
**Expected:** `EXPLAIN` gives `size=10000ms slide=9999ms`, and `slicesPerWindow() = 10,000,000,000 /
1,000,000 = 10,000`. State: 200 keys × 10,000 slices = **2,000,000**, exactly `DEFAULT_MAX_SLICES`,
so the 2,000,000th distinct `(key, slice)` is refused with `PRV-3020` naming the key and the event
time — "this windowed aggregate is holding 2000000 (key, slice) accumulators, its configured
ceiling…". Before that, `fire()` builds a 10,000-element slice list per window and scans the whole
2,000,000-entry map for each of the 10,000 slices — `2 × 10^10` comparisons per window fired.
Record whether the refusal or the wall-clock arrives first.
**Vacuity:** Defends against a dry source: ROWS IN must reach a non-trivial fraction of 12,001
before the refusal is attributed to state rather than to nothing having arrived.

### WIN-089 — The gap guard applies to HOPPING only, and the other kinds route around it
**Intent:** The guard is `if (kind == Kind.HOPPING && slideNanos > sizeNanos)`. `tumbling()` and
`session()` set slide = size so they cannot trip it — but a direct `new WindowSpec(TUMBLING, 10 s,
60 s)` is constructible, and `SESSION` is exempt from the positive-slide check entirely
(`if (kind != Kind.SESSION && slideNanos <= 0)`).
**Falsifier:** `new WindowSpec(Kind.TUMBLING, 10 s, 60 s)` throwing.
**Setup:** Embedded harness.
**Steps:** Construct `new WindowSpec(TUMBLING, 10 s, 60 s)`; read `sliceSizeNanos()` (= `gcd(10,60) =
10 s`) and `slicesPerWindow()` (= 1); build `SlicedWindows` on it and call `windowEndsContaining(30 s)`.
Separately construct `new WindowSpec(SESSION, 30 s, 0)` and `new WindowSpec(SESSION, 30 s, -1)`.
**Expected:** The TUMBLING/60 s-slide spec is **accepted** and behaves as the gapped hop WIN-086
proved loses 83 % of rows — `windowEndsContaining(30 s)` returns empty. No SQL reaches it today
(`tumbling()` is the only constructor the planner calls) so it is a latent hazard rather than a live
one; record it as such. The SESSION specs with slide 0 and slide −1 are both accepted, because the
positivity check exempts SESSION — harmless while sessions are unsliced, and a trap for whoever
wires them up.
**Vacuity:** Not stateful.

### WIN-090 — `slicesOfWindowEnding` pre-allocates one entry per slice, per fire
**Intent:** `new ArrayList<>(spec.slicesPerWindow())` allocates the whole list capacity before the
loop, and `fire()` then nests a full scan of the slice map inside the slice loop. Both costs are
linear in `slicesPerWindow`, which WIN-077 showed can be 10,000 for an innocuous-looking pair.
**Falsifier:** Fire cost independent of `slicesPerWindow`.
**Setup:** Embedded harness. For (S, D) ∈ {(10 s, 10 s), (10 s, 1 s), (10 s, 100 ms), (10 s, 1 ms)}
— slices per window 1, 10, 100, 10,000 — feed the same 10,000 rows for 100 keys and time
`fire(windowEnd)`.
**Steps:** Record wall time and allocation per `fire` call at each ratio.
**Expected:** Cost rising linearly in slices per window for the list, and as
`slicesPerWindow × slices.size()` for the scan, because `fire` iterates
`for (sliceStart : sliceStarts) for (entry : slices.entrySet())` rather than indexing. At 10,000
slices and 10,000 live accumulators that is 10^8 comparisons per window fired, on the lane thread,
with the ingest pump blocked behind it. Record the measured figures; this is the most plausible
mechanism for "ingest frozen, no error" in §9 that does not involve memory at all.
**Vacuity:** Defends against an empty state: `liveSlices()` must be non-zero at each ratio, or
`fire` is timing an empty map.

---

## 7. Open windows at once — 1, 10, 100, 1000

**Definition, and why it is `S/D`.** A slice starting at *s* is discarded when
`lastWindowEndFor(s) + lateness ≤ watermark`, and for a slide that divides the size
`lastWindowEndFor(s) = s + S`. So the live slices at watermark *W* are those with `s > W − S`, and
the window ends not yet fired are those in `(W, W + S]` — `S/D` of them. Open windows and live
slices per key are the same number, and it is set by the window geometry, not by the data.

**Dataset O** (`open.csv`): 120 rows, one per second — `i,100,1,<i seconds>` for *i* = 1…120 — plus
pusher `121,999,0,2000.000`. Used unchanged for all four configurations, so the only variable is the
(size, slide) pair.

### WIN-091 — One open window: TUMBLE 10 s
**Falsifier:** `view.size ≠ 13`, or `SUM(n) ≠ 120`.
**Setup:** `s0` bound to `open.csv`.
**Steps:** Register Q_T(10) as `v_open1 --keys 0,1,2`; wait for ROWS IN 121 and the view to settle;
read `COUNT(*)`, `SUM(n)`, and the first and last rows.
**Expected:** Watermark 2,000 s; ends 10, 20, …, 2,000 fire (200 of them), of which 13 are non-empty:

| window | rows | n |
|---|---|---|
| `[0,10)` | *i* = 1…9 | 9 |
| `[10,20)` … `[110,120)` | 10 each, 11 windows | 10 |
| `[120,130)` | *i* = 120 | 1 |

`COUNT(*) = 13`. `SUM(n) = 9 + 11×10 + 1 = 120` — every row of dataset O, once. The pusher at
2,000 s is in `[2000, 2010)`, whose end 2,010 s exceeds the watermark, so it never appears.
**Vacuity:** Defends against key collapse — `--keys 2` gives 1 row and `SUM(n) = 120` still, so
`COUNT(*) = 13` is the load-bearing assertion — and against a dry source (ROWS IN 121).

### WIN-092 — Ten open windows: HOP(1 s, 10 s)
**Falsifier:** `COUNT(*) ≠ 129` or `SUM(n) ≠ 1200`.
**Setup:** As WIN-091.
**Steps:** Register Q_H(1, 10) as `v_open10 --keys 0,1,2`; read `COUNT(*)` and `SUM(n)`.
**Expected:** `count(t) = floor((t+10)/1) − floor(t/1) = 10` for every row, so
`SUM(n) = 120 × 10 = 1,200`. Non-empty windows are those ending at *e* with some *t* ∈ [1,120]
satisfying `e − 10 ≤ t < e`, i.e. `2 ≤ e ≤ 130` → `COUNT(*) = 129`. The ends 1 and 131…2,000 fire
empty.
**Vacuity:** Both numbers are required. A degeneration to tumble gives `SUM(n) = 120`; a duplicated
store gives `COUNT(*) = 129` with `SUM(n) = 12,000`. Key collapse gives `COUNT(*) = 1`.

### WIN-093 — One hundred open windows: HOP(1 s, 100 s)
**Falsifier:** `COUNT(*) ≠ 219` or `SUM(n) ≠ 12000`.
**Steps:** Register Q_H(1, 100) as `v_open100 --keys 0,1,2`; read both.
**Expected:** `count(t) = 100` for every row → `SUM(n) = 120 × 100 = 12,000`. Non-empty ends are
`2 ≤ e ≤ 220` → `COUNT(*) = 219`. One hundred and twenty input rows become twelve thousand counted
memberships and two hundred and nineteen result rows; the amplification is `S/D` and is correct.
**Vacuity:** As WIN-092.

### WIN-094 — One thousand open windows: HOP(1 s, 1000 s)
**Falsifier:** `COUNT(*) ≠ 1119` or `SUM(n) ≠ 120000`, or the query failing.
**Steps:** Register Q_H(1, 1000) as `v_open1000 --keys 0,1,2`; read both; record settle time.
**Expected:** `count(t) = 1000` for every row → `SUM(n) = 120 × 1,000 = 120,000`. Non-empty ends are
`2 ≤ e ≤ 1,120` → `COUNT(*) = 1,119`. Two thousand ends are walked
(`firstWindowStart = 1 s − 1 s = 0`, `firstEnd = 1 s`, then 1…2,000 s), each building a
1,000-element slice list and scanning the slice map — so about `2,000 × 1,000 × 120 = 2.4 × 10^8`
comparisons for one hundred and twenty rows of input. Record the settle time.
**Vacuity:** As WIN-092. Additionally, `pravaha.query.view.size` must reach 1,119 and not 120: a
view keyed only on `user_id` would hold one row and the whole amplification would be invisible.

### WIN-095 — Live slices per key equals the open-window count, measured
**Intent:** The state claim, measured rather than inferred. `SlicedAggregateState.liveSlices()` and
`peakSlices()` are the numbers; neither is on a shipped surface, so this is the embedded harness.
**Falsifier:** Live slices scaling with the row count rather than with `S/D`.
**Setup:** Embedded harness. For each of TUMBLE 10 s, HOP(1,10), HOP(1,100), HOP(1,1000): feed
dataset O's 120 rows for one key, calling `advanceWatermark(t)` after each row, then read
`liveSlices()`.
**Expected:** After the last row (*t* = 120 s, watermark 120 s), live slices are those with
`s > W − S`:

| config | S | live slices | peak |
|---|---|---|---|
| TUMBLE 10 s | 10 s | 1 (slice starting 120 s) | 2 |
| HOP(1,10) | 10 s | 10 (slices 111…120 s) | 10 |
| HOP(1,100) | 100 s | 100 (slices 21…120 s) | 100 |
| HOP(1,1000) | 1000 s | 120 (all of them — fewer than 1,000 because only 120 s of data exists) | 120 |

The last row is the important one: the bound is `min(S/D, slices with data)`, so a wide window over
a short stream holds less than the geometry allows. Feeding 1,200 seconds of data instead of 120
must take HOP(1,1000) to exactly 1,000 live slices and hold it there.
**Vacuity:** `peakSlices()` must be non-zero before `liveSlices()` is believed; an operator that
never stored anything reports 0 for both and passes a "state stayed small" assertion trivially.

### WIN-096 — The open-window set is exactly the ends in `(watermark, watermark + S]`
**Intent:** The definition, checked against the implementation, so §8's state predictions rest on
something measured.
**Falsifier:** A window end outside that half-open interval still holding live slices.
**Setup:** Embedded harness, HOP(1 s, 10 s), watermark held at 50 s with data to 120 s.
**Steps:** Call `discardSlicesEndingBefore(50 s, 0)`; then for each remaining slice start *s*, compute
`lastWindowEndFor(s)` and check it is `> 50 s`; and enumerate `windowEndsContaining(120 s)`.
**Expected:** Remaining slices are *s* = 41…120 s — `lastWindowEndFor(41 s) = 51 s > 50 s`, while
`lastWindowEndFor(40 s) = 50 s ≤ 50 s` so slice 40 was discarded. Unfired ends run from 51 s
upwards; the newest row at 120 s is in ends 121…130, ten of them, `= S/D`.
**Vacuity:** The discard must actually remove something: `discardSlicesEndingBefore` returns a
non-zero count. A no-op discard makes every "the right slices survived" assertion vacuous.

### WIN-097 — A thousand windows closing on one watermark advance arrive as one commit
**Intent:** `advanceWatermark` fires every window completed since the last call in a single pass on
the lane thread, and `ViewSink` publishes a whole batch per commit. A subscriber must see one group,
not a thousand.
**Falsifier:** Partial windows visible to a consistent read, or a commit containing a window whose
end exceeds the watermark.
**Setup:** `s0` bound to `open.csv`; Q_H(1, 1000) registered as in WIN-094.
**Steps:** `pravaha subscribe --view v_open1000 --limit 5000` attached before registration; count
changes per blank-line-separated group.
**Expected:** The file is read in one pass, so the watermark goes from `NOT_YET` to 2,000 s in one or
two ticks and all 1,119 non-empty windows fire in those ticks. Expect one group of 1,119 changes, or
a small number of groups totalling 1,119, every change an insert with weight +1, and no group
containing a window whose end exceeds the watermark at that commit.
**Vacuity:** Defends against the subscription attaching late (the banner must print before
`register`) and against key collapse (1,119 distinct changes, not 1).

### WIN-098 — Windows fire in increasing end order and each fires exactly once
**Intent:** "Windows fire in order and each fires once, because a consumer applying two results for
one window in arrival order keeps whichever arrived last."
**Falsifier:** Any window end appearing twice in the change stream, or any end preceded by a larger
one.
**Setup:** WIN-097's capture.
**Steps:** Extract `window_end` from each change in arrival order; check monotonicity and
uniqueness per `(window_end, user_id)`.
**Expected:** Non-decreasing `window_end` across the whole stream, and exactly one change per
`(window_end, user_id)`. `windowsCompletedBetween` builds the list in increasing order and
`advanceWatermark` iterates it in order, so this is a property of the loop and not of the data.
Corrections, which `advanceWatermark` deliberately emits **before** the newly completed windows and
therefore out of end order, cannot occur here: no late record arrives, so `dirty` stays empty.
WIN-170 covers the ordering when it does not.
**Vacuity:** Defends against an empty stream; the change count must be 1,119.

### WIN-099 — A thousand open windows over one row of data
**Intent:** The bound is geometric. One row must make `S/D` windows exist, each holding that row.
**Falsifier:** Fewer than 1,000 result rows.
**Setup:** `one1000b.csv`: `1,100,7,1000.000`; pusher `2,999,0,3000.000`.
**Steps:** Register Q_H(1, 1000) as `v_one1000 --keys 0,1,2`; read `COUNT(*)` and `SUM(n)`.
**Expected:** 1,000 rows, ends 1,001…2,000 s, each `n = 1, total = 7`. `SUM(n) = 1,000`.
`count(1000 s) = floor(2000/1) − floor(1000/1) = 1,000`. Live slices: **one** — the single slice at
1,000 s — because the state is per slice and one row is one slice, whatever 1,000 windows read it.
That contrast, 1,000 output rows from 1 accumulator, is the slicing optimisation stated as an
observable.
**Vacuity:** Both `COUNT(*) = 1000` and `liveSlices() = 1` (embedded, on the same input) are
required; the first alone is satisfied by a per-window store and the second alone by an empty one.

### WIN-100 — A slice is released exactly one window-width after it stops being needed
**Intent:** "A slice's lifetime is exactly the width of one window plus whatever lateness the query
allows." Allowed lateness is hard-wired to 0 (`DEFAULT_ALLOWED_LATENESS_NANOS`), so the lifetime is
exactly one window.
**Falsifier:** A slice surviving past `sliceStart + S`, or being released before it.
**Setup:** Embedded harness, HOP(1 s, 10 s); one row at *t* = 0 for one key.
**Steps:** Call `discardSlicesEndingBefore(W, 0)` for `W` = 9.999999999 s, then 10 s; read
`liveSlices()` after each.
**Expected:** `lastWindowEndFor(0) = 10 s`. At `W = 9.999999999 s` the predicate
`10 s + 0 ≤ 9.999999999 s` is false → 1 live slice, discard returns 0. At `W = 10 s` it is true →
0 live slices, discard returns 1. One nanosecond apart, and the boundary is inclusive on the
watermark side.
**Vacuity:** The two calls must return different counts; a discard that returns 0 both times is
proving nothing about the boundary.

### WIN-101 — One thousand open windows against the slice ceiling
**Intent:** Where the geometric bound meets `DEFAULT_MAX_SLICES` = 2,000,000. At 1,000 open windows
the ceiling is reached at 2,000 keys, which is a small number for a production stream.
**Falsifier:** More than 2,000,000 live accumulators, or an out-of-memory kill instead of a refusal.
**Setup:** `s0` bound to dataset K(2000, 1200) — 2,000 keys × one row per second for 1,200 s =
2,400,000 rows; pusher at 4,000 s.
**Steps:** Register Q_H(1, 1000) as `v_ceiling --keys 0,1,2`; watch `pravaha queries`, the server
log, and `/actuator/prometheus`.
**Expected:** Live accumulators grow toward `2,000 keys × 1,000 slices = 2,000,000`. The 2,000,000th
distinct `(key, slice)` is admitted and the 2,000,001st is refused with `PRV-3020`:
"this windowed aggregate is holding 2000000 (key, slice) accumulators, its configured ceiling, and
key <high>:<low> at event time <nanos> needs another. Either the key space is unbounded — which no
window can fix — or the window is too wide for the key count. Raise the limit deliberately, narrow
the window, or add a key predicate." The message names the key as a **hash pair**, not as the
`user_id` the query was written with, so the advice "add a key predicate" cannot be acted on from
the message alone. Record that as a diagnostic defect beside the correct refusal.
**Vacuity:** Defends against the refusal arriving for the wrong reason: ROWS IN must exceed
2,000,000 before the refusal, and the same query at 1,000 keys (dataset K(1000, 1200), product
1,000,000) must complete — otherwise the refusal is about volume, not about the ceiling.

### WIN-102 — Open windows and view rows are different bounds, and only one of them is bounded
**Intent:** "State is bounded by keys times open windows" is a claim about the **operator**. The
`ServedView` above it accumulates one row per fired window per key and releases them only at the
24-hour retention horizon, so the memory a user actually observes is `keys × windows fired in the
last 24 h of event time`, which is not what the design sentence says.
**Falsifier:** `view.size` staying at `keys × open windows`.
**Setup:** `s0` bound to `open.csv`; Q_H(1, 10) registered as `v_open10`.
**Steps:** Read `pravaha.query.view.size` and, from the embedded harness on the same input,
`liveSlices()`.
**Expected:** `liveSlices()` settles at 10 (one key × 10 open windows); `view.size` settles at
**129**, and would keep growing with a live stream until the retention horizon. The two differ by
more than 12× on 120 rows of input. Both bounds should be documented; today only the operator one
is, in `WindowedAggregateOperator`'s javadoc.
**Vacuity:** Both numbers must be non-zero. A dead query reports 0 for both, which satisfies "they
are different" only in the trivial sense.

---

## 8. Key cardinality × open windows

`keys × open windows` is the state bound the design claims, and `DEFAULT_MAX_SLICES` = 2,000,000 is
where the claim becomes a refusal. The sixteen cells of {1, 100, 10⁴, 10⁶ keys} × {1, 10, 100, 1000
open windows} are written out below with the predicted product and the predicted outcome. Twelve
complete; four must refuse.

| keys \ open | 1 | 10 | 100 | 1000 |
|---|---|---|---|---|
| 1 | 1 (WIN-103) | 10 (WIN-104) | 100 (WIN-105) | 1,000 (WIN-106) |
| 100 | 100 (WIN-107) | 1,000 (WIN-108) | 10,000 (WIN-109) | 100,000 (WIN-110) |
| 10⁴ | 10,000 (WIN-111) | 100,000 (WIN-112) | 1,000,000 (WIN-113) | **10,000,000 ✗** (WIN-114) |
| 10⁶ | 1,000,000 (WIN-115) | **10,000,000 ✗** (WIN-116) | **10⁸ ✗** (WIN-117) | **10⁹ ✗** (WIN-118) |

**Dataset K(K, T)** (`k_<K>_<T>.csv`): `i, i mod K, 1, <(i div K) seconds>` for *i* = 0…(K·T − 1) —
K keys, one row per key per second, for T seconds, `K·T` rows. Plus a pusher at `(T + 2S)` seconds.
Live slices settle at `K × min(T, S/D)`. Where a cell's product exceeds 2,000,000 the dataset is
sized so the refusal is reached in a few million rows rather than by building the full product.

The window configurations are the four of §7: TUMBLE 10 s (1 open), HOP(1 s, 10 s), HOP(1 s, 100 s),
HOP(1 s, 1000 s).

### WIN-103 — 1 key × 1 open window
**Falsifier:** `COUNT(*) ≠ 13` or `SUM(n) ≠ 120` or `liveSlices() > 2`.
**Setup:** K(1, 120) = dataset O. TUMBLE 10 s.
**Steps:** Register as `v_k1w1 --keys 0,1,2`; read `COUNT(*)`, `SUM(n)`, `view.size`.
**Expected:** Identical to WIN-091: 13 rows, `SUM(n) = 120`. Predicted state 1 × 1 = 1 live
accumulator, measured at 1–2 (the filling slice and, briefly, the firing one).
**Vacuity:** Key collapse gives 1 row; a dry source gives 0. Both must be excluded.

### WIN-104 — 1 key × 10 open windows
**Falsifier:** `COUNT(*) ≠ 129` or `SUM(n) ≠ 1200`.
**Setup:** K(1, 120), HOP(1 s, 10 s). **Expected:** As WIN-092: 129 rows, `SUM(n) = 1,200`; state
`1 × 10 = 10` live accumulators.
**Vacuity:** As WIN-103.

### WIN-105 — 1 key × 100 open windows
**Falsifier:** `COUNT(*) ≠ 219` or `SUM(n) ≠ 12000`.
**Setup:** K(1, 120), HOP(1 s, 100 s). **Expected:** As WIN-093; state `1 × 100 = 100`.
**Vacuity:** As WIN-103.

### WIN-106 — 1 key × 1000 open windows
**Falsifier:** `COUNT(*) ≠ 1119` or `SUM(n) ≠ 120000`.
**Setup:** K(1, 120), HOP(1 s, 1000 s). **Expected:** As WIN-094; state is
`1 × min(120, 1000) = 120` because only 120 seconds of data exists — the geometry allows 1,000 and
the data supplies 120, and the smaller wins.
**Vacuity:** As WIN-103.

### WIN-107 — 100 keys × 1 open window
**Falsifier:** `COUNT(*) ≠ 1300` or `SUM(n) ≠ 12000`.
**Setup:** K(100, 120) = 12,000 rows, one row per key per second for 120 s; pusher at 140 s.
TUMBLE 10 s.
**Steps:** Register as `v_k100w1 --keys 0,1,2`; read `COUNT(*)`, `SUM(n)`.
**Expected:** Each key has one row per second, so each of the 13 non-empty windows of WIN-091 now
holds 100 keys: `COUNT(*) = 13 × 100 = 1,300`. Per key, `n` is 9 in `[0,10)`… — careful: with
`event_time = (i div 100)` seconds and *i* = 0…11,999, second *q* holds keys 0…99, so window `[0,10)`
holds seconds 0…9 → every key has `n = 10`. Windows `[0,10)` through `[110,120)` each hold
100 keys × `n = 10`. That is 12 windows; second 120 does not exist (the last row is at second 119).
So `COUNT(*) = 12 × 100 = 1,200` and `SUM(n) = 12,000` = every row. Predicted state `100 × 1 = 100`.
**Vacuity:** `COUNT(*) = 1,200` and `SUM(n) = 12,000` together. Key collapse gives 100 and 12,000;
window collapse gives 12 and 12,000; a lost window gives 1,100 and 11,000.

### WIN-108 — 100 keys × 10 open windows
**Falsifier:** `COUNT(*) ≠ 12800` or `SUM(n) ≠ 120000`.
**Setup:** K(100, 120), HOP(1 s, 10 s). Rows at seconds 0…119.
**Expected:** `count(t) = 10` per row → `SUM(n) = 12,000 × 10 = 120,000`. Non-empty ends: *e* with
some *t* ∈ [0,119] and `e − 10 ≤ t < e` → `1 ≤ e ≤ 129` → 129 ends, each holding 100 keys →
`COUNT(*) = 12,900`. Predicted state `100 × 10 = 1,000`.
**Vacuity:** As WIN-107, with both numbers.

### WIN-109 — 100 keys × 100 open windows
**Falsifier:** `COUNT(*) ≠ 21900` or `SUM(n) ≠ 1200000`.
**Setup:** K(100, 120), HOP(1 s, 100 s).
**Expected:** `SUM(n) = 12,000 × 100 = 1,200,000`. Non-empty ends `1 ≤ e ≤ 219` → 219, × 100 keys →
`COUNT(*) = 21,900`. Predicted state `100 × 100 = 10,000`. Note `COUNT(*)` is now 1.8× the input row
count: 12,000 rows in, 21,900 rows out.
**Vacuity:** As WIN-107.

### WIN-110 — 100 keys × 1000 open windows
**Falsifier:** `COUNT(*) ≠ 111900` or `SUM(n) ≠ 12000000`, or the view refusing.
**Setup:** K(100, 120), HOP(1 s, 1000 s); pusher at 2,200 s.
**Expected:** `SUM(n) = 12,000 × 1,000 = 12,000,000`. Non-empty ends `1 ≤ e ≤ 1,119` → 1,119,
× 100 keys → `COUNT(*) = 111,900`. Predicted state `100 × min(120, 1000) = 12,000`. The view holds
111,900 rows for 12,000 rows of input — a 9.3× amplification, still under the 1,000,000 `maxKeys`
ceiling.
**Vacuity:** As WIN-107. Also confirm `view.evicted` is 0: the whole run spans under 24 hours of
event time, so retention must not be what limits `view.size`.

### WIN-111 — 10⁴ keys × 1 open window
**Falsifier:** `COUNT(*) ≠ 120000` or `SUM(n) ≠ 120000`.
**Setup:** K(10000, 12) = 120,000 rows (10,000 keys × 12 seconds); pusher at 40 s. TUMBLE 10 s.
**Expected:** Seconds 0…11. Window `[0,10)` holds seconds 0…9 → every key `n = 10`; `[10,20)` holds
seconds 10…11 → every key `n = 2`. `COUNT(*) = 2 × 10,000 = 20,000`; `SUM(n) = 10,000×10 +
10,000×2 = 120,000` = every row. Predicted state `10,000 × 1 = 10,000`.
**Vacuity:** `COUNT(*) = 20,000` and `SUM(n) = 120,000`. The round-1 failure — 120,000 rows
collapsing into a few keys — is caught by `COUNT(*)`; a duplicated store is caught by `SUM(n)`.

### WIN-112 — 10⁴ keys × 10 open windows
**Falsifier:** `COUNT(*) ≠ 210000` or `SUM(n) ≠ 1200000`.
**Setup:** K(10000, 12), HOP(1 s, 10 s).
**Expected:** `SUM(n) = 120,000 × 10 = 1,200,000`. Non-empty ends `1 ≤ e ≤ 21` → 21, × 10,000 keys →
`COUNT(*) = 210,000`. Predicted state `10,000 × min(12, 10) = 100,000`.
**Vacuity:** As WIN-111.

### WIN-113 — 10⁴ keys × 100 open windows — at the ceiling
**Intent:** Product exactly 1,000,000, half the slice ceiling and exactly the view's `maxKeys`.
**Falsifier:** A refusal (the product is under the slice ceiling), or a view exceeding 1,000,000 keys
without the `ServedView` guard firing.
**Setup:** K(10000, 100) = 1,000,000 rows (10,000 keys × 100 s); pusher at 300 s. HOP(1 s, 100 s).
**Expected:** `SUM(n) = 1,000,000 × 100 = 100,000,000`. Non-empty ends `1 ≤ e ≤ 199` → 199, ×
10,000 keys → `COUNT(*) = 1,990,000`, which is **past** `ServedView`'s `maxKeys` of 1,000,000. So the
slice ceiling is not reached (state is `10,000 × 100 = 1,000,000`, half of 2,000,000) and the
**view** refuses instead, at commit, with "view '<name>' holds 1000001 keys, past its ceiling of
1000000…". Record which ceiling fires first and whether the message distinguishes them — two
different 10⁶-scale limits in one query, one on the operator and one on the view, and the user has
no way to tell them apart from the error alone.
**Vacuity:** Defends against the refusal being about volume: the same 1,000,000-row file under
TUMBLE 10 s (WIN-111's shape scaled) must complete, giving `COUNT(*) = 10 × 10,000 = 100,000`.

### WIN-114 — 10⁴ keys × 1000 open windows — refused
**Intent:** Product 10,000,000, five times the slice ceiling. The refusal must arrive before memory
does.
**Falsifier:** An out-of-memory kill, a silent truncation, or completion.
**Setup:** K(10000, 300) = 3,000,000 rows; pusher at 2,500 s. HOP(1 s, 1000 s).
**Steps:** Register as `v_k1e4w1000 --keys 0,1,2`; watch the log and `pravaha queries`.
**Expected:** `PRV-3020` when the 2,000,001st distinct `(key, slice)` is needed — which happens at
row `2,000,001` of the file, i.e. at second 200 of the data, because each second contributes 10,000
new slices and none are discarded until the watermark passes `slice + 1000 s`. The query's lane
enters `FAILED`. Confirm what `pravaha queries` reports: `RegisteredQuery.state()` is what
`pravaha.query.running` reads, and it is not the lane's state, so a failed lane may well still be
reported `RUNNING`.
**Vacuity:** Defends against a premature refusal: ROWS IN must reach ≈2,000,000 first. If the
refusal comes at a few thousand rows, it is not this ceiling.

### WIN-115 — 10⁶ keys × 1 open window
**Intent:** A million distinct keys inside one window. The product equals the slice ceiling's half
and equals the view's whole `maxKeys`, and it is also where the 128-bit group digest earns its keep.
**Falsifier:** Fewer than 1,000,000 distinct `user_id` values in the result for a window, or a
refusal from the slice ceiling.
**Setup:** K(1000000, 2) = 2,000,000 rows (1,000,000 keys × 2 seconds, both inside `[0,10)`);
pusher at 40 s. TUMBLE 10 s.
**Expected:** One non-empty window `[0,10)` holding 1,000,000 keys with `n = 2, total = 2` each.
`SUM(n) = 2,000,000` = every row. `COUNT(*) = 1,000,000` — exactly `maxKeys`, and the guard is
`visible.size() > maxKeys`, so 1,000,000 passes and 1,000,001 would not. State
`1,000,000 × 1 = 1,000,000`, half the slice ceiling.
**Vacuity:** `COUNT(*) = 1,000,000` is the whole case; `SUM(n) = 2,000,000` alone is satisfied by
one key counted two million times, which is precisely the round-1 collapse.

### WIN-116 — 10⁶ keys × 10 open windows — refused
**Falsifier:** Completion, or an out-of-memory kill.
**Setup:** K(1000000, 3) = 3,000,000 rows; pusher at 60 s. HOP(1 s, 10 s).
**Expected:** Each second adds 1,000,000 new slices and nothing is discarded until the watermark
passes `slice + 10 s`, so the ceiling is crossed during second 2: `PRV-3020` at the 2,000,001st
`(key, slice)`, i.e. at row 2,000,001. Product would have been 10,000,000.
**Vacuity:** ROWS IN ≈ 2,000,000 before the refusal; and the same dataset under TUMBLE 10 s
(product 1,000,000) must complete, which is WIN-115's shape.

### WIN-117 — 10⁶ keys × 100 open windows — refused
**Falsifier:** Completion.
**Setup:** K(1000000, 3), HOP(1 s, 100 s); pusher at 300 s.
**Expected:** Identical refusal at the same row, 2,000,001 — the refusal depends on the accumulation
rate, not on the eventual product, so 10⁸ and 10⁷ are indistinguishable from the message. That is
worth recording: the diagnostic cannot tell an operator how far over the ceiling they are.
**Vacuity:** As WIN-116.

### WIN-118 — 10⁶ keys × 1000 open windows — refused
**Falsifier:** Completion.
**Setup:** K(1000000, 3), HOP(1 s, 1000 s); pusher at 2,100 s.
**Expected:** The same `PRV-3020` at row 2,000,001, for a query whose full state would have been
10⁹ accumulators — five hundred times the ceiling. Together with WIN-114, WIN-116 and WIN-117 this
establishes that the ceiling is a **rate** guard rather than a sizing one: four queries whose true
requirements differ by a factor of 100 all fail at the same row with the same message.
**Vacuity:** As WIN-116.

---

## 9. Row volume, and the blocker between 210k and 230k

Every case here uses **dataset V(N, K)** unchanged and **no pusher**, so that the close trigger is
held constant and volume is the only variable. On `s0` the watermark settles at *N* ms, so
`floor(N / 10,000)` windows fire and they hold `9,999 + 10,000 × (windows − 1)` rows. With K = 100
every window holds all 100 keys, so `COUNT(*) = 100 × windows` and `SUM(n)` equals the row figure —
two independent assertions, one that catches key collapse and one that catches lost or duplicated
rows. The reference values, all computed from those two formulas:

| N | ROWS IN | windows on `s0` | rows emitted | `COUNT(*)` (K=100) | windows on `s10` | `COUNT(*)` on `s10` |
|---|---|---|---|---|---|---|
| 1 | 1 | 0 | 0 | 0 | 0 | 0 |
| 1,000 | 1,000 | 0 | 0 | 0 | 0 | 0 |
| 100,000 | 100,000 | 10 | 99,999 | 1,000 | 9 | 900 |
| **200,000** | 200,000 | 20 | 199,999 | 2,000 | **19** | 1,900 |
| **210,000** | 210,000 | 21 | 209,999 | 2,100 | 20 | 2,000 |
| **220,000** | 220,000 | 22 | 219,999 | 2,200 | 21 | 2,100 |
| **230,000** | 230,000 | 23 | 229,999 | 2,300 | 22 | 2,200 |
| 1,000,000 | 1,000,000 | 100 | 999,999 | 10,000 | 99 | 9,900 |

The reported symptom — "13 windows served where 19 exist" — is measured against the `s10` column at
N = 200,000. A deficit of 6 windows is 60,000 rows.

### WIN-119 — N = 1
**Intent:** The floor. One row, one key, and a 10 s window that cannot close because the stream's
highest event time is 1 ms.
**Falsifier:** Any row in the view, or an error.
**Setup:** `s0` bound to V(1, 1) — the single line `1,1,1,0.001`.
**Steps:** Register Q_T(10) as `v_n1 --keys 0,1,2`; wait 10 s; read ROWS IN and `COUNT(*)`.
**Expected:** ROWS IN 1, `COUNT(*)` 0. Correct and useless: a one-row stream under a ten-second
window produces nothing, forever. Repeat with `INTERVAL '0.1' SECOND` and a pusher at 1.000 s to
show the same row *can* be reported — one window `[0, 0.1)` with `n = 1, total = 1` — so the zero
above is the close trigger and not the volume.
**Vacuity:** Defends against a dry source in reverse: ROWS IN must read 1, proving the file was read
and the emptiness is the window's doing.

### WIN-120 — N = 1,000
**Falsifier:** Any row in the view.
**Setup:** `s0` bound to V(1000, 100).
**Steps:** Register Q_T(10) as `v_n1k --keys 0,1,2`; wait; read.
**Expected:** ROWS IN 1,000, `COUNT(*)` 0 — the data spans 1 second and the window is 10. With
`INTERVAL '0.1' SECOND` instead: windows `[0, 0.1)` … `[0.9, 1.0)`; the watermark is 1.000 s so ends
0.1 … 1.0 fire, i.e. 10 windows; window `[0, 0.1)` holds *i* = 1…99 (99 rows) and the rest hold 100
each, so `SUM(n) = 99 + 9 × 100 = 999` and `COUNT(*) = 10 × 100 keys = 1,000`. One row — *i* = 1,000
at t = 1.000 s — is in `[1.0, 1.1)` and is not emitted.
**Vacuity:** ROWS IN 1,000 and `SUM(n)` 999 together; either alone is satisfied by the wrong engine.

### WIN-121 — N = 100,000
**Falsifier:** `COUNT(*) ≠ 1000` or `SUM(n) ≠ 99999`.
**Setup:** `s0` bound to V(100000, 100).
**Steps:** Register Q_T(10) as `v_n100k --keys 0,1,2`; wait for ROWS IN 100,000 and the view to
settle; read `COUNT(*)`, `SUM(n)`, `MIN(window_start)`, `MAX(window_start)`, `view.size`.
**Expected:** `COUNT(*) = 1,000` (10 windows × 100 keys); `SUM(n) = 99,999`;
`MIN(window_start) = 0`; `MAX(window_start) = 90 s`. Per-key detail: in window `[0,10)` user 0 has
`n = 99` and users 1…99 have `n = 100` (`99 + 99 × 100 = 9,999`); in every later window all 100 users
have `n = 100`.
**Vacuity:** This is the round-1 case, restated. `SUM(n) = 99,999` alone passes while 100,000 rows
collapse into 100 keys; `COUNT(*) = 1,000` alone passes while every window holds one row. Both, plus
`MAX(window_start) = 90 s`, are required.

### WIN-122 — N = 200,000 — the reference point for the reported defect
**Intent:** The exact configuration the blocker was reported against, run on both streams so the
"19 windows exist" figure is established before any deficit is measured.
**Falsifier:** On `s10`, anything other than 19 windows and `COUNT(*) = 1900`.
**Setup:** V(200000, 100), bound to **both** `s0` and `s10`.
**Steps:** Register Q_T(10) over `s0` as `v_200k_s0` and over `s10` as `v_200k_s10`, both
`--keys 0,1,2`. For each: watch ROWS IN every second and record the final value and the wall time it
stopped changing; then read `COUNT(*)`, `SUM(n)`, `COUNT(DISTINCT window_start)`,
`MIN(window_start)`, `MAX(window_start)`.
**Expected:** Both reach ROWS IN 200,000.
`v_200k_s0`: 20 windows, `MIN(window_start) = 0`, `MAX(window_start) = 190 s`, `COUNT(*) = 2,000`,
`SUM(n) = 199,999`.
`v_200k_s10`: 19 windows, `MAX(window_start) = 180 s`, `COUNT(*) = 1,900`, `SUM(n) = 189,999`.
If `v_200k_s10` shows 13 distinct `window_start` values, the reported defect reproduces and the
deficit is `19 − 13 = 6` windows = 60,000 rows; record the highest `window_start` actually served,
because that is the row number at which whatever froze, froze.
**Vacuity:** ROWS IN must reach exactly 200,000 for both. If it stops short, the defect is in ingest
and the window count is a consequence; if it reaches 200,000 and the window count is short, the
defect is downstream of ingest. Distinguishing the two is the whole point of watching ROWS IN, and
round 1's version of this case did not.

### WIN-123 — N = 210,000
**Falsifier:** On `s0`, anything other than 21 windows and `COUNT(*) = 2100`.
**Setup:** V(210000, 100) on `s0` and `s10`.
**Steps:** As WIN-122.
**Expected:** `s0`: 21 windows, `MAX(window_start) = 200 s`, `COUNT(*) = 2,100`, `SUM(n) = 209,999`.
`s10`: 20 windows, `MAX(window_start) = 190 s`, `COUNT(*) = 2,000`, `SUM(n) = 199,999`. ROWS IN
210,000 in both.
**Vacuity:** As WIN-122.

### WIN-124 — N = 220,000
**Falsifier:** On `s0`, anything other than 22 windows and `COUNT(*) = 2200`.
**Setup:** V(220000, 100).
**Expected:** `s0`: 22 windows, `MAX(window_start) = 210 s`, `COUNT(*) = 2,200`, `SUM(n) = 219,999`.
`s10`: 21 windows, `COUNT(*) = 2,100`, `SUM(n) = 209,999`. This is the midpoint of the reported
bracket; if the deficit appears anywhere it should appear here.
**Vacuity:** As WIN-122.

### WIN-125 — N = 230,000
**Falsifier:** On `s0`, anything other than 23 windows and `COUNT(*) = 2300`.
**Setup:** V(230000, 100).
**Expected:** `s0`: 23 windows, `MAX(window_start) = 220 s`, `COUNT(*) = 2,300`, `SUM(n) = 229,999`.
`s10`: 22 windows, `COUNT(*) = 2,200`, `SUM(n) = 219,999`.
**Vacuity:** As WIN-122.

### WIN-126 — N = 1,000,000
**Falsifier:** `COUNT(*) ≠ 10000` or `SUM(n) ≠ 999999` on `s0`.
**Setup:** V(1000000, 100). The data spans 1,000 seconds of event time in a file read in one pass.
**Steps:** As WIN-122, plus record peak heap from `/actuator/metrics/jvm.memory.used` and wall time.
**Expected:** `s0`: 100 windows, `MAX(window_start) = 990 s`, `COUNT(*) = 10,000`,
`SUM(n) = 999,999`. `s10`: 99 windows, `COUNT(*) = 9,900`, `SUM(n) = 989,999`. State: 100 keys × at
most 2 live slices = 200 accumulators, nowhere near the 2,000,000 ceiling — so a failure here is not
the ceiling.
**Vacuity:** As WIN-122.

### WIN-127 — Windows served is monotonic in N
**Intent:** The single assertion the whole bracket rests on. Whatever the engine does, serving fewer
windows for more rows is a defect by inspection, with no reference implementation needed.
**Falsifier:** `windows(N₁) > windows(N₂)` for any `N₁ < N₂` in the sweep.
**Setup:** WIN-119 through WIN-126 run in one server session, in ascending N, each registered under
its own name and left running.
**Steps:** Read `COUNT(DISTINCT window_start)` from all eight views; tabulate against the reference.
**Expected:** 0, 0, 10, 20, 21, 22, 23, 100 on `s0` — strictly non-decreasing, and each equal to
`floor(N / 10,000)`. Any dip is the blocker, and the N at which it dips is its threshold.
**Vacuity:** All eight ROWS IN values must equal their N. A view that served fewer windows because
its file was never fully read is not evidence of this defect.

### WIN-128 — The deficit, quantified per N
**Intent:** Turn "13 where 19 exist" into a measured function of N, so the threshold has a number.
**Falsifier:** A deficit that is constant in N (which would mean a fixed-size limit, not a
wrapping one) — record either way, because the shape of the deficit is what identifies the
mechanism.
**Setup:** WIN-127's eight views.
**Steps:** For each, compute `expected − served` for both windows and rows; also record
`MAX(window_start)` and derive the highest input row that reached a fired window
(`(MAX(window_start) + 10 s) / 1 ms`).
**Expected:** All deficits zero if the blocker has been fixed. If not, the table of
`(N, served windows, highest row reached)` is the deliverable: a **constant** highest-row-reached
across N ≥ some threshold means ingest stopped at a fixed row and the window count is a consequence;
a highest-row-reached that keeps rising while the window count falls means the loss is in firing,
not in ingest. The two mechanisms need different fixes and the current report does not distinguish
them.
**Vacuity:** Requires WIN-127's ROWS IN checks; without them "highest row reached" is unattributable.

### WIN-129 — Does ingest actually freeze, and at which row
**Intent:** "Ingest frozen" is a claim about ROWS IN, not about the view. Test it directly.
**Falsifier:** ROWS IN reaching N at every volume in the sweep.
**Setup:** V(230000, 100) on `s0`.
**Steps:** Poll `pravaha queries` every 500 ms from registration, logging (wall time, ROWS IN) until
ROWS IN is unchanged for 60 s. Also poll `/actuator/prometheus` for `pravaha.query.rows.in` and
`pravaha.query.running`, and tail the server log.
**Expected:** ROWS IN rises to 230,000 and stops. If it plateaus below 230,000, record the exact
plateau value and repeat the run three times: a **reproducible** plateau at the same row is a
deterministic limit (an arena, a ring, a ceiling); a plateau that varies run to run is a race or a
timing-dependent backpressure stall. `Lane.run` catches any `Throwable`, sets `state = FAILED` and
returns, and nothing polls the lane's state — so a frozen ROWS IN with `pravaha.query.running = 1`
is the expected presentation of a dead lane.
**Vacuity:** The control is V(100000, 100) in the same server run, which must reach ROWS IN 100,000.
A server that ingests nothing is not demonstrating a volume threshold.

### WIN-130 — Is the threshold a row count or a byte count
**Intent:** A "wrapped signed-32-bit arena offset" is a **byte** limit; 2³¹ bytes over 220,000 rows
is about 9,760 bytes per row, which no row in dataset V is close to. If the threshold moves when the
row width changes and the row count does not, it is bytes; if it does not move, it is rows.
**Falsifier:** The threshold being identical for both widths (that falsifies the byte hypothesis) or
scaling exactly inversely with width (that falsifies the row hypothesis).
**Setup:** Two files at the same row count: V(230000, 100) as usual (~30 bytes/row), and
V-wide(230000, 100) with a 1,000-character `user_id` string — which needs a widened schema
`txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP` on a fourth stream `swide`.
**Steps:** Run WIN-129's polling for both; compare the plateau row numbers.
**Expected:** Record both. Note the arena constraints this bears on: `RowArena` refuses any single
row larger than `slabBytes` outright ("row of N bytes exceeds the slab size of 4194304"), and
`WindowAssign` allocates `layout.rowSize(1024)` per row while `WindowedAggregate.emitRow` allocates
`layout.rowSize(256)` — so a 1,000-character key is within one allocation and the widened file is a
legitimate test of bytes-per-row at constant rows.
**Vacuity:** Both files must reach the same ROWS IN if neither hypothesis holds; a control at
V(100000) for both widths must complete.

### WIN-131 — Does the threshold depend on key cardinality — K = 1
**Intent:** The brief asks explicitly. K controls the number of accumulators and therefore the arena
traffic on the emit side, while leaving ingest identical.
**Falsifier:** The same plateau at every K (which would exonerate cardinality) or a plateau moving
with K.
**Setup:** V(220000, 1) — every row is user 1.
**Steps:** WIN-129's polling; record ROWS IN plateau, `COUNT(*)`, `SUM(n)`.
**Expected:** 22 windows on `s0`, `COUNT(*) = 22` (one key per window), `SUM(n) = 219,999`. State: 1
key × ≤2 slices. Emit traffic: 22 result rows for the whole query.
**Vacuity:** `COUNT(*) = 22` and `SUM(n) = 219,999` together — this is the maximum-collapse
configuration and `SUM(n)` is the only thing standing between it and round 1's failure.

### WIN-132 — Key cardinality K = 100
**Setup:** V(220000, 100). **Expected:** WIN-124's values: 22 windows, `COUNT(*) = 2,200`,
`SUM(n) = 219,999`. Emit traffic 2,200 result rows.
**Falsifier / Vacuity:** As WIN-131.

### WIN-133 — Key cardinality K = 10⁴
**Setup:** V(220000, 10000). **Expected:** 22 windows on `s0`. Window 0 holds *i* = 1…9,999 → 9,999
distinct keys, `n = 1` each; windows 1…21 hold 10,000 distinct keys each, `n = 1` each. So
`COUNT(*) = 9,999 + 21 × 10,000 = 219,999` — the result row count equals the input row count, which
is the worst case for emit traffic and for `ServedView` (219,999 keys, under the 1,000,000 ceiling).
`SUM(n) = 219,999`. State: ≤ 20,000 accumulators.
**Falsifier:** `COUNT(*) ≠ 219999`.
**Vacuity:** Here `COUNT(*)` and `SUM(n)` coincide, so they are not independent: add
`COUNT(DISTINCT window_start) = 22` and `MAX(n) = 1` as the third and fourth assertions.

### WIN-134 — Key cardinality K = 10⁶
**Setup:** V(220000, 1000000) — `i mod 10⁶ = i` for every row, so every row is a distinct key.
**Expected:** Identical to WIN-133 in shape: 22 windows, `COUNT(*) = 219,999`, `SUM(n) = 219,999`,
`MAX(n) = 1`, and `COUNT(DISTINCT user_id) = 219,999`. The difference is the group digest: 219,999
distinct 128-bit keys, where `SlicedAggregateState` claims a collision probability around 10⁻²⁷.
A collision would present as two `user_id` values merged into one row with `n = 2` — so
`MAX(n) = 1` is also the collision assertion.
**Falsifier:** Any row with `n > 1`, or `COUNT(DISTINCT user_id) < 219,999`.
**Vacuity:** As WIN-133.

### WIN-135 — Does the threshold depend on the window size
**Intent:** Window size changes the number of windows walked, the number of `fire` calls, and the
`emitted` map size, while leaving ingest identical. If the threshold moves with size, the mechanism
is in firing (WIN-090's nested scan or WIN-065's map growth); if it does not, it is in ingest.
**Falsifier:** A threshold independent of size (exonerates firing) or proportional to the window
count (implicates it).
**Setup:** V(220000, 100) on `s0`, registered four times: `INTERVAL '0.1' SECOND` (2,200 windows),
`INTERVAL '1' SECOND` (220), `INTERVAL '10' SECOND` (22), `INTERVAL '1' MINUTE` (3).
**Steps:** WIN-129's polling for each, one at a time in a fresh server so the runs do not interact.
**Expected:** Reference answers — 0.1 s: `COUNT(*) = 2,200 × 100 = 220,000`… careful: windows are
`[0, 0.1)` … and window 0 holds *i* = 1…99 so it has 99 keys… restate: for `INTERVAL '0.1' SECOND`
there are 2,200 windows, each holding 100 rows over 100 keys except the first which holds 99 rows
over 99 keys, so `COUNT(*) = 99 + 2,199 × 100 = 219,999` and `SUM(n) = 219,999`. For 1 s: 220 windows
× 100 keys, `COUNT(*) = 22,000`, `SUM(n) = 219,999`. For 10 s: WIN-124. For 1 minute: 3 windows
(`floor(220 s / 60 s) = 3`), `COUNT(*) = 300`, `SUM(n) = 9,999 + … ` — window `[0,60)` holds
*i* = 1…59,999, `[60,120)` and `[120,180)` hold 60,000 each, so `SUM(n) = 59,999 + 120,000 =
179,999`. Record the plateau for each.
**Vacuity:** Each run must reach ROWS IN 220,000 or the size comparison is comparing failures.

### WIN-136 — Does the threshold depend on hop versus tumble
**Intent:** A hop multiplies emit traffic by `S/D` without changing ingest at all — the cleanest
separation of the two sides available.
**Falsifier:** The same plateau for TUMBLE 10 s and HOP(1 s, 10 s) (exonerates emit) or a plateau
`S/D` times lower for the hop (implicates it).
**Setup:** V(220000, 100) on `s0`, registered as TUMBLE 10 s and as HOP(1 s, 10 s).
**Expected:** TUMBLE: 22 windows, `COUNT(*) = 2,200`, `SUM(n) = 219,999`. HOP(1,10): every row in 10
windows → `SUM(n) = 2,199,990`; non-empty ends `1 ≤ e ≤ 229` seconds → 229 windows × 100 keys →
`COUNT(*) = 22,900`. Emit traffic is 10.4× the tumble's. Record both plateaus.
**Vacuity:** Both must reach ROWS IN 220,000. Also `SUM(n)` for the hop must be exactly ten times
the tumble's `SUM(n)` plus the boundary correction — `2,199,990` against `219,999` is exactly 10×,
which is itself a strong check that no row was lost on either side.

### WIN-137 — Which of the four candidate mechanisms it is
**Intent:** Four things in this engine produce "stops making progress, no error". Distinguish them,
because the fix differs and the report names only one.
**Falsifier:** None of the four signatures matching — that would mean a fifth mechanism.
**Setup:** The N at which WIN-127 dips, from a fresh server, with the server started under
`-XX:+HeapDumpOnOutOfMemoryError -Xlog:gc` and with `jcmd <pid> Thread.print` taken at the plateau.
**Steps:** At the plateau, capture: a thread dump; `jvm.memory.used`; the server log; and
`pravaha queries`.
**Expected:** Match against these four signatures.

| mechanism | thread dump | log | heap | view |
|---|---|---|---|---|
| Arena exhaustion (`WindowAssign` / `emitRow` get `ArenaHandle.NULL`) | no `pravaha-lane-*` thread | `PRV-3001 RUNTIME_ARENA_EXHAUSTED`, "the window assigner's arena is full" — but only if the lane's `Throwable` is logged | flat | frozen |
| Slice ceiling | no lane thread | `PRV-3020`, naming a key hash | flat | frozen |
| Window walk (`windowsCompletedBetween` × `fire`'s nested scan, WIN-090) | lane thread **alive**, stack in `SlicedAggregateState.fire` or `SlicedWindows.windowsCompletedBetween` | silent | rising or flat | frozen |
| Backpressure stall (`IngestPump` paused at the high watermark, never resumed) | lane thread parked in `WaitStrategy.idle` | silent | flat | frozen |

The third is the only one where the lane is alive and busy, and it is the only one where the
symptom would be "ingest frozen, no error" with no exception anywhere — which is what was reported.
`Lane.run`'s catch block records the `Throwable` in a field read only by `Lane.failure()`; nothing
on the server path calls it, so the first two mechanisms are also silent unless the lane logs on the
way down. Record whether it does.
**Vacuity:** The thread dump must show `pravaha-lane-*` threads *before* the plateau for the
"thread gone" signatures to mean anything; take one at ROWS IN ≈ N/2 as the baseline.

### WIN-138 — Lane count is not a variable for a windowed query
**Intent:** The brief asks whether the blocker depends on lane count. It cannot: every registered
query runs on exactly one lane, and a windowed aggregate on more than one is refused outright.
**Falsifier:** A windowed query running on more than one lane, from any surface.
**Setup:** Read `QueryRegistry.start` — `QueryExecution.start(plan, 1, laneConfig, …)`, the literal 1
— and `QueryExecution.refuseUnpartitionedAggregate`, whose `containsKeyedAggregate` returns true for
every `WindowedAggregateOperator` regardless of its group keys.
**Steps:** Confirm by grep that no configuration key, CLI flag or REST field sets a lane count. Then,
from the embedded harness, call `QueryExecution.start(windowedPlan, 4, …)`.
**Expected:** No surface sets lane count. The embedded 4-lane start is refused with `PRV-3020`:
"this query groups by a key and runs on 4 lanes, and nothing routes a row to the lane that owns its
group. Every lane would keep its own partial total for a key it happens to see, and emit it — so one
group comes out as several rows of partial answers, with no error to say so. Partitioning by a
grouping key is not built…". Conclusion for the blocker: lane count is fixed at 1 and cannot be a
factor, and the arena in question is therefore the single lane's — `config.arenaSlabBytes()` ×
`config.arenaMaxSlabs()`, defaulting to 4 MB × 8 = **32 MB**.
**Vacuity:** Not stateful.

### WIN-139 — What a user is told when it happens
**Intent:** "With no error" is the most serious half of the report. Enumerate every surface and
record what each says at the plateau.
**Falsifier:** Any surface reporting the failure clearly — that would falsify "no error" and is the
outcome to hope for.
**Setup:** The plateau state from WIN-129.
**Steps:** Check all of: `pravaha queries`; `GET /status`; `GET /actuator/prometheus`
(`pravaha.query.running`, `rows.in`, `view.size`, `watermark.lag.seconds`); `GET /actuator/health`;
the server log at INFO and at DEBUG; `pravaha subscribe --view <name>`; the process exit code.
**Expected, predicted from the code:** `pravaha queries` shows `RUNNING` with a frozen ROWS IN —
`pravaha.query.running` reads `RegisteredQuery.state()`, not `Lane.state()`, so a dead lane is
invisible to it. `watermark.lag.seconds` rises without bound, which is the **only** moving signal
and is indistinguishable from a slow source. `view.size` is frozen. The subscription goes quiet. No
metric exists for lane state, arena usage, live slices, late records or corrections. Health is up.
Exit code unchanged. If that is what is observed, the defect to file is not the freeze but the
silence, and the minimum remedy is a `pravaha.query.lane.state` gauge plus logging `Lane.failure()`
when it is set.
**Vacuity:** Not stateful; the falsifier is the content of each surface at a known-bad moment.

### WIN-140 — Bisect to the exact threshold row
**Intent:** Close the bracket. "Between 210k and 230k" is a 20,000-row interval; a defect is
reproducible when its threshold is a number.
**Falsifier:** A threshold that does not reproduce across three runs.
**Setup:** Binary search on N over [the largest N that completes, the smallest that does not] from
WIN-127, using V(N, 100) on `s0` with TUMBLE 10 s, each run in a fresh server.
**Steps:** Eight to fifteen runs to a single-row resolution, or to the resolution at which the
threshold stops being stable; then three repeat runs at `threshold` and at `threshold − 1`.
**Expected:** A single N at which the last complete run becomes the first incomplete one, reproduced
three times. Report the threshold row number, the corresponding event time
(`threshold ms`), the corresponding window index (`floor(threshold / 10,000)`), and the bytes
ingested to that point — so the next session can check it against 2³¹, against 32 MB (the lane's
whole arena), and against 4 MB (one slab), which are the three numbers a "wrapped signed-32-bit
offset" could plausibly be.
**Vacuity:** Each run must reach ROWS IN = N or plateau reproducibly; a run whose plateau varies is
excluded from the search and recorded as evidence of a race rather than a limit.
