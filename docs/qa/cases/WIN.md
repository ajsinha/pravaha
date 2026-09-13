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
