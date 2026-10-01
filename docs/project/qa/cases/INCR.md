# INCR — Z-set correctness: a continuous result must equal a batch recomputation

**Area:** `INCR` · **IDs:** INCR-001 … INCR-070 · **Budget:** 70 · **Status:** authored, not executed.

The governing property, and the only thing this area is about:

> After any sequence of inputs, a continuous query's result must equal what a batch recomputation
> over the same inputs would produce.

`ZSet`'s javadoc puts the claim at its strongest: an update is `-1` of the old row and `+1` of the
new, "which is why insert, update and delete stop being three cases an operator author must reason
about separately and become arithmetic", and "a row whose weight reaches zero is removed rather than
stored as a zero". `IncrementalQuery` states the machine-checkable form, `Q(S + dS) == Q(S) + Qd(dS,
S)`, and calls it "what a retract-stream engine cannot have". This area asks whether the *runtime*
operators — not the reference algebra — hold that property.

Surface under test: `pravaha-algebra/` (`ZSet`, `Lift`, `Integrate`, `Differentiate`,
`IncrementalJoin`, `IncrementalOracleTest`), `pravaha-runtime/.../exec/` (`GlobalAggregate`,
`KeyedAggregate`, `WindowedAggregate`, `SymmetricHashJoin`, `JoinSide`, `InterpretedPipeline`),
`pravaha-runtime/.../window/SlicedAggregateState`, and `pravaha-serving/.../ServedView`
(`apply`, `applyValues`, `commit`).

---

## How a retraction gets into this engine at all — read before running anything

Four facts, each established by a case below, decide what every other case in this file can even be
run against. They were found by reading, and they are the reason the harnesses are specified rather
than left to the executor.

1. **No shipped source plugin emits a negative weight except Delta.** `FeedFilePartitionReader`,
   `JdbcPartitionReader`, `LutScanReader` and `AerospikeLookupPlugin` all write `writer.weight(1L)`
   literally. Only `DeltaPartitionReader` does otherwise:
   `long weight = offset.phase() == DeltaOffset.Phase.REMOVES ? -1L : 1L`. So an end-to-end
   retraction through a registered query requires a Delta table with a commit containing
   `removeFile` entries. (INCR-003)
2. **The view read path stamps every row `+1`.** `ViewQuery.run` writes `writer.weight(1L)` for each
   row of `view.scan()`. A view already holds net state, so nothing with a negative weight is ever
   fed to a read pipeline. Every weighted branch in `KeyedAggregate` is therefore unreachable from
   any shipped surface. (INCR-018, INCR-024)
3. **`GlobalAggregate` and `KeyedAggregate` emit only on end of input.**
   `InterpretedPipeline.buildInput` registers `aggregate::emit` as a *finisher*. A continuous
   registration over an endless stream never finishes, so an unwindowed aggregate registered as a
   continuous query produces no rows at all, ever. (INCR-005)
4. **Allowed lateness is hard-wired to zero.** `PhysicalPlanBuilder.DEFAULT_ALLOWED_LATENESS_NANOS
   = 0L`, and `WindowedAggregate.process` rejects a row when
   `lastWindowEnd + allowedLateness <= watermark` — the *same* condition
   `SlicedWindows.windowsCompletedBetween` uses to fire. So through SQL, any record for a window
   that has fired is routed to the late output and dropped, and the retract-and-re-emit correction
   path (`emitted`, `dirty`, `corrections()`) is unreachable. (INCR-021, INCR-022)

### The harnesses

| | What | Why it is needed |
|---|---|---|
| **H1** | In-process operator harness, exactly the shape of `pravaha-it/.../ServedQueryTest`: `PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL))`, then `ServedView` + `ViewSink` + `InterpretedPipeline.compile(plan, (RowOutput) sink::begin)`, fed by a `BinaryRowWriter` whose `.weight(w)` is set per row. | The only way to inject a weight other than `+1` into an arbitrary operator. |
| **H2** | End-to-end: a Delta table bound under `pravaha.sources.<n>`, registered with `pravaha register`, read with `pravaha query`. Version 1 adds the rows; version 2 is a commit whose `removeFile` set is the row to retract. | Proves the property on the shipped path, not only in a harness. |
| **H3** | `pravaha-algebra`'s jqwik property oracle, extended: `assertAgrees(name, batch, incremental, state, delta)`. | The generated form of the property, over the reference algebra. |

### The batch oracle, procedure B

Every "Expected" below is the result of this procedure, computed by hand and shown as arithmetic.
An executor may also run it mechanically:

> Given the input as an ordered list of `(row, weight)` pairs *M*: (1) reduce *M* to its net form
> *N* by summing weights per identical row and discarding every row whose net weight is zero;
> (2) build a **fresh** H1 over the same plan; (3) feed *N*, expanding a row of weight *w* > 0 into
> *w* arrivals at weight `+1`; (4) advance the watermark and commit identically; (5) read the view.
> The case passes iff the incremental run's view equals *B(M)* key for key and value for value.

If *N* contains a negative weight, the batch answer is undefined and the case says so explicitly
rather than inventing one.

### Standing fixtures

```
stream txn : user_id STRING, amount INT64, event_time TIMESTAMP
SECOND = 1_000_000_000 nanos.  W1 = TUMBLE 10 SECOND over [0s, 10s).
```

Canonical input **D1**, in arrival order:

| ref | user_id | amount | event_time | weight |
|---|---|---|---|---|
| r1 | u1 | 100 | 1s | +1 |
| r2 | u2 | 50 | 2s | +1 |
| r3 | u1 | 200 | 3s | +1 |
| r4 | u3 | 7 | 30s | +1 |

Hand-computed W1 truth for D1: u1 → `SUM 100 + 200 = 300`, `COUNT 2`, `AVG 300 / 2 = 150`,
`MIN 100`, `MAX 200`. u2 → `SUM 50`, `COUNT 1`, `AVG 50`, `MIN 50`, `MAX 50`. r4 is in W4
(`[30s, 40s)`) and exists only to push the watermark past W1's end.

Retractions used throughout: **ρ3** = r3 at weight `-1`; **ρ1** = r1 at weight `-1`.

### Vacuity kit

Three controls are run in the *same process* as every stateful case below, and a case that cannot
show all three is reported `INCONCLUSIVE`, not `PASS`.

- **C1 — the input arrived.** `pipeline.rowsIn()` / `RegisteredQuery.rowsIn()` equals the number of
  rows fed, retractions included. A retraction that was silently discarded at the source looks
  identical to one the operator handled.
- **C2 — the operator ran.** The unretracted control query over the *same* rows in the same process
  returns the pre-retraction number (`u1 = 300`). A case asserting `100` is only meaningful when the
  control asserts `300`.
- **C3 — the key existed.** Before the retraction, `view.get("u1").found()` is `true`. A case
  asserting "the key is gone" passes trivially if it was never there.

---

## §1 — The property itself, and what can carry it

### INCR-001 — The oracle's own falsifiability, re-proved on this build
**Intent:** `IncrementalOracleTest.theOracleCatchesADeliberatelyBrokenLift` and
`...AnOffByOneInTheDistinctLift` are the only evidence that the eight jqwik properties can fail at
all. Re-establish it on the build under test before trusting anything else in this file.
**Falsifier:** Either seeded-bug test passes without throwing, i.e. `catchThrowable` returns null.
**Setup:** `pravaha-algebra`, unmodified.
**Steps:** Run `IncrementalOracleTest` and record the per-property try counts.
**Expected:** 8 properties × 1400 tries = **11 200** generated cases. Both seeded-bug tests report a
caught throwable. `theGeneratorProducesGenuinelyMixedInputs` reports `nonEmpty > 300`,
`withNegativeWeights > 100`, `withWeightsBeyondOne > 50` out of 400.
**Vacuity:** The generator guard *is* the vacuity check; record its three observed numbers, not just
that it passed. A generator emitting only empty Z-sets makes all 11 200 cases assert nothing.

### INCR-002 — The oracle covers no aggregate and no join
**Intent:** The suite is cited as proof that correctness is machine-checked "over the entire
operator set". Establish exactly which operators it actually generates over, because every gap is a
case in this file that has no automated backstop.
**Falsifier:** An `assertAgrees` call in the suite whose `Query` is a SUM, COUNT, MIN, MAX, AVG,
COUNT(DISTINCT) or join.
**Setup:** None.
**Steps:** Enumerate the `Query` passed to each `assertAgrees` call.
**Expected:** filter, project, filter→project, flatMap, distinct, a recomputation fallback over
filter→distinct, and a three-operator view chain. **Zero aggregates, zero joins.** `Lift`'s own
javadoc concedes it: "Wave 2 covers the linear operators. Aggregates arrive in Wave 4 and bilinear
joins in Wave 5." Record that the property is proven only for the linear subset.
**Vacuity:** n/a — this is an enumeration of source, not a behavioural run.

### INCR-003 — Only one shipped source can express a retraction
**Intent:** Decide whether the rest of this file can be run end-to-end or only in H1. The Z-set claim
is the product's headline; a retraction that no source can produce is a claim about an unreachable
code path.
**Falsifier:** Any source plugin other than Delta writing a weight other than `1L`.
**Setup:** Repository at the commit under test.
**Steps:** `grep -rn "writer.weight" plugins/*/src/main/java`.
**Expected:** `feedfile`, `jdbc`, `aerospike` (sink, lookup, LUT scan) all write `weight(1L)`
unconditionally. `DeltaPartitionReader` writes `-1L` when `offset.phase() == REMOVES`. Conclusion:
H2 is available only for Delta-sourced streams; every other end-to-end retraction case in this file
is **not runnable on a shipped surface** and must say so.
**Vacuity:** n/a.

### INCR-004 — A `+1` then `-1` of the same row leaves the pipeline emitting nothing net
**Intent:** The simplest possible statement of the property, and the control every later case is
measured against.
**Falsifier:** The view holds a row for `u1` after ρ1 has been applied and committed.
**Setup:** H1 over `SELECT user_id, SUM(amount) AS total FROM TABLE(TUMBLE(TABLE txn,
DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY user_id, window_start, window_end`, view
keyed on the `user_id` output ordinal.
**Steps:** Feed r1 (`+1`), then ρ1 (`-1`), then r4. `advanceWatermark(20s)`; `finish()`;
`sink.commit(sink.appliedFrontier())`; `view.get("u1")`.
**Expected:** *B(M)*: net form of `{r1:+1, r1:-1}` is empty, so W1 contains no u1 row and the view
has no `u1` key. `found() == false`.
**Vacuity:** C2 — the same harness fed r1 alone must yield `u1 → 100`, proving the key can appear.
C1 — two rows in, not one. If the `-1` row were dropped at the writer the view would show `100` and
this case would fail rather than pass, which is the right direction.

### INCR-005 — An unwindowed aggregate registered continuously never emits anything
**Intent:** `GlobalAggregate::emit` is a *finisher*. A registered continuous query has no end of
input, so the operator accumulates forever and publishes nothing. This makes every global-aggregate
case in §2 unrunnable as a continuous query, and it is a wrong-looking-right failure in its own
right: the query reports RUNNING with a non-zero `rowsIn` and an empty view.
**Falsifier:** `SELECT COUNT(*) AS n FROM txn` registered continuously returns a row.
**Setup:** Server with `txn` bound to a 20-row feed file. A global aggregate has no group key, so
register with `--keys 0` (the `n` column) — a view still needs a key.
**Steps:** `pravaha register --name v_global --sql "SELECT COUNT(*) AS n FROM txn" --keys 0`; wait
for `pravaha queries` to show ROWS IN 20; `pravaha query --sql "SELECT * FROM v_global"`.
**Expected:** ROWS IN `20`, state `RUNNING`, and the view returns **0 rows**. The batch answer is one
row, `n = 20`. Record whether the registration is refused at plan time instead; it is not expected to
be, because `PhysicalPlanBuilder` admits a global aggregate as "bounded by construction".
**Vacuity:** C1 — ROWS IN must be exactly 20. An empty view with ROWS IN 0 is an ingest failure and
proves nothing about the aggregate.

### INCR-006 — A long interleaved sequence does not drift from a fresh recomputation
**Intent:** `appliedDeltasAreEquivalentToRecomputingFromScratch` proves this for `filter→project`.
Extend it to the operator the product is actually sold on. Drift is the failure that never announces
itself.
**Falsifier:** After N deltas, the incremental view differs from *B(M)* in any key or value.
**Setup:** H1 over the windowed SUM+COUNT query. Deterministic generator, seed `20260909`: 2 000
steps over keys `{u1..u8}`, amounts `1..100`, event times spread across `[0s, 200s)`, each row's
weight drawn from `{+1, +1, +1, -1, +2}`, with the constraint that a `-1` only ever names a row
already fed (recorded in a side list).
**Steps:** Feed all 2 000; `advanceWatermark(400s)`; `finish()`; commit; `view.scan()`. Then run
procedure B in a fresh harness and compare.
**Expected:** Identical key sets and identical `(window_start, window_end, total, n)` per key. Report
the first differing key with both numbers, not a boolean.
**Vacuity:** C1 (2 000 rows in). Assert the generated stream actually contained retractions: at least
300 of the 2 000 weights negative and at least 150 rows whose net weight reached zero. A run with no
retractions is a run of INCR-004 two thousand times.

---

## §2 — A retraction per aggregate kind, per operator

Six aggregate kinds × three operators. Every cell is written out, including the ones that cannot be
reached, because "unreachable" is the finding.

`SQL_SUPPORT.md` lists `COUNT, SUM, MIN, MAX, AVG` and `COUNT(DISTINCT x)` as ✅ with no qualification
anywhere on the page. `GlobalAggregate`, `KeyedAggregate` and `SlicedAggregateState` all throw
`PRV-3020` on `MIN`/`MAX` under a negative weight. INCR-010, INCR-011, INCR-016, INCR-017, INCR-022
and INCR-023 pin that disagreement.

### Windowed (`WindowedAggregate` + `SlicedAggregateState`) — the only operator a continuous query reaches

### INCR-007 — Windowed SUM: a retraction subtracts its contribution
**Intent:** The core arithmetic. `SlicedAggregateState.update` does `accumulator.values[i] +=
values[i] * weight`.
**Falsifier:** W1's `u1` total is anything other than 100 after ρ3.
**Setup:** H1, windowed `SELECT user_id, SUM(amount) AS total FROM TABLE(TUMBLE(...)) GROUP BY
user_id, window_start, window_end`, view keyed on `user_id`.
**Steps:** Feed r1, r2, r3, **ρ3**, r4 — ρ3 *before* the watermark passes 10s, so the window has not
fired. `advanceWatermark(20s)`; `finish()`; commit; read.
**Expected:** `100 + 200 - 200 = 100`. u1 → `total = 100`. u2 → `total = 50`, untouched.
**Vacuity:** C2 — the same feed without ρ3 must give `300`. C3 — u1 present before. A case that
reads `100` from a harness that would also read `100` without the retraction proves nothing.

### INCR-008 — Windowed COUNT(*): a retraction decrements
**Intent:** `case COUNT -> accumulator.values[i] += weight`.
**Falsifier:** `n` is 2 after ρ3, or the row vanishes entirely.
**Setup:** As INCR-007 with `COUNT(*) AS n`.
**Steps:** r1, r2, r3, ρ3, r4; watermark 20s; finish; commit; read.
**Expected:** `2 - 1 = 1`. u1 → `n = 1`. u2 → `n = 1`.
**Vacuity:** C2 (control gives `n = 2`).

### INCR-009 — Windowed SUM and COUNT together stay mutually consistent under retraction
**Intent:** Round 2 found `KeyedAggregate` and `WindowedAggregate` "each emitting a row whose COUNT
contradicts its own SUM". Check the pair, not each alone.
**Falsifier:** A row where `n = 1` and `total = 300`, or `n = 2` and `total = 100`.
**Setup:** As INCR-007 with both `SUM(amount) AS total, COUNT(*) AS n`.
**Steps:** As INCR-007.
**Expected:** u1 → `total = 100`, `n = 1`. Both from the same accumulator, so a disagreement is a
per-kind branch bug and is reported as one.
**Vacuity:** C2 gives `total = 300, n = 2` — a consistent pair before and after.

### INCR-010 — Windowed MIN refuses a retraction with PRV-3020
**Intent:** `SlicedAggregateState.update` throws when `weight < 0` for `MIN`/`MAX`. Establish exactly
what a caller sees, because `SQL_SUPPORT.md` advertises MIN as supported without qualification.
**Falsifier:** ρ3 is accepted and `MIN` returns a number, or the query fails with an error other
than `PRV-3020`, or the lane dies with no error reaching the caller.
**Setup:** H1, windowed `MIN(amount) AS lo`.
**Steps:** Feed r1, r3, then ρ3.
**Expected:** `PravahaException`, code **PRV-3020** (`RUNTIME_UNSUPPORTED_AGGREGATE`), message
containing "cannot handle a retraction: restoring the previous extreme needs an ordered multiset per
group". The batch answer is `MIN(100) = 100`; the engine produces no answer at all. Record where the
exception surfaces on a *registered* query: `RegisteredQuery.accept` catches `PravahaException`,
calls `fail(e)` and rethrows, so the query's state becomes `FAILED` and its view stops advancing.
**Vacuity:** C2 — r1 and r3 without ρ3 must yield `lo = 100`, proving MIN works at all.

### INCR-011 — Windowed MAX refuses a retraction with PRV-3020
**Intent:** The MAX half of the same branch, written out rather than assumed symmetric.
**Falsifier:** MAX behaves differently from MIN — accepted, or a different code.
**Setup:** H1, windowed `MAX(amount) AS hi`.
**Steps:** r1, r3, ρ3.
**Expected:** `PRV-3020`, message naming `MAX`. Batch answer would be `MAX(100) = 100`.
**Vacuity:** C2 — control yields `hi = 200`.

### INCR-012 — Windowed AVG under retraction uses integer division on the corrected pair
**Intent:** `AVG` is `SUM`-kinded in the slice state and divided at emit: `counts[i] == 0 ? 0 :
sums[i] / counts[i]`. The division is integer, and the retraction changes both operands.
**Falsifier:** `avg` is 150 after ρ3, or anything other than 100.
**Setup:** H1, windowed `AVG(amount) AS avg`.
**Steps:** r1, r2, r3, ρ3, r4; watermark 20s; finish; commit.
**Expected:** sums `100 + 200 - 200 = 100`; counts `1 + 1 - 1 = 1`; `100 / 1 = 100`. u1 → `avg = 100`.
**Vacuity:** C2 — control gives `300 / 2 = 150`.

### INCR-013 — Windowed AVG truncates rather than rounds, and a retraction changes which way
**Intent:** Integer division is documented as matching "SQL's AVG over an integer column". A
retraction that changes the count changes the truncation, and an off-by-one here is invisible.
**Falsifier:** Either expected value below is off by one in either direction.
**Setup:** H1, windowed `AVG(amount) AS avg`, single key `u1`, three rows in W1: 10, 11, 11 at 1s,
2s, 3s.
**Steps:** (a) all three, no retraction. (b) all three, then retract the row `(u1, 11, 3s)`.
**Expected:** (a) `(10 + 11 + 11) / 3 = 32 / 3 = 10` (truncated from 10.67). (b) `(32 - 11) / (3 - 1)
= 21 / 2 = 10` (truncated from 10.5). Both 10 — so also assert the SUM and COUNT columns to prove
the retraction was applied at all: `total 32 → 21`, `n 3 → 2`.
**Vacuity:** The equal AVG values are why this case carries SUM and COUNT alongside. Without them it
passes with the retraction entirely ignored.

### INCR-014 — Windowed COUNT(DISTINCT) counts string *lengths*, not string values
**Intent:** `WindowedAggregate.process` fills the aggregate argument with
`scratch[i] = ordinal < 0 || row.isNull(ordinal) ? 0 : row.getLong(ordinal)`, with no type check.
`BinaryRowView.getLong` reads the fixed slot raw; for a variable-width column that slot holds
`(payloadOffset:int, length:int)` written by `BinaryRowWriter.setBytes`, and the payload offset is
the same for every row of a fixed schema. So the distinct key is a function of the string's *byte
length* alone.
**Falsifier:** `COUNT(DISTINCT user_id)` returns the number of distinct user ids.
**Setup:** H1, `SELECT window_start, window_end, COUNT(DISTINCT user_id) AS users FROM
TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start,
window_end`, view keyed on `window_end`. Rows in W1: `('alice',1,1s)`, `('bob',2,2s)`,
`('carol',3,3s)`, then `('zz',4,30s)` to advance.
**Steps:** Feed; watermark 20s; finish; commit; read W1's row.
**Expected by SQL:** three distinct ids → `users = 3`. **Expected by the code:** UTF-8 lengths are
`alice = 5`, `bob = 3`, `carol = 5`; two distinct length words → **`users = 2`**. Report the observed
number. A control run with `('u1',…), ('u2',…), ('u3',…)` — all 2 bytes — is expected to return
**`users = 1`** where SQL says 3.
**Vacuity:** Run the all-2-byte control in the same process. If it returns 3 the mechanism described
here is wrong and the case must be rewritten, not passed.

### INCR-015 — Windowed COUNT(DISTINCT) under retraction: the count is a multiset, not a set
**Intent:** `SlicedAggregateState.Kind.COUNT_DISTINCT` is documented as counted rather than flagged
— "a value seen three times and retracted once is still present" — via
`seen.merge(values[i], weight, Long::sum)` and `if (remaining <= 0) seen.remove(...)`. That is the
right design and is worth pinning independently of INCR-014's key defect.
**Falsifier:** A value seen twice and retracted once disappears from the count.
**Setup:** H1, `COUNT(DISTINCT user_id) AS users` over W1, with ids chosen to have **distinct byte
lengths** so INCR-014's defect does not mask this one: `('a',1,1s)`, `('bb',2,2s)`, `('a',3,3s)`.
**Steps:** Feed the three, then retract `('a',3,3s)` at `-1`; watermark 20s; finish; commit.
**Expected:** `'a'` count `1 + 1 - 1 = 1`, still present; `'bb'` count 1. `users = 2`. Then retract
`('a',1,1s)` too: `'a'` reaches 0 and is removed → `users = 1`.
**Vacuity:** C2 — without any retraction `users = 2` as well, so this case is only meaningful with
the *second* retraction included; both halves must be asserted or it passes vacuously.

### INCR-016 — Windowed COUNT(col) counts NULLs, contradicting its own SUM
**Intent:** Round 2 recorded the `COUNT(col)` fix as landing in `GlobalAggregate` only.
`SlicedAggregateState` does `accumulator.values[i] += weight` with no null test, and
`WindowedAggregate.process` has already turned a NULL argument into `0`. Enumerate the windowed cell
of that matrix, and check it under retraction rather than only under insert.
**Falsifier:** `n` equals the number of non-null amounts.
**Setup:** H1 over `txn` with `amount` nullable. W1 rows for u1: `(u1, 100, 1s)`, `(u1, NULL, 2s)`,
`(u1, 200, 3s)`. Query `SELECT user_id, COUNT(amount) AS n, SUM(amount) AS total FROM
TABLE(TUMBLE(...)) GROUP BY user_id, window_start, window_end`.
**Steps:** Feed; advance; finish; commit; read.
**Expected by SQL:** `COUNT(amount)` ignores NULL → `n = 2`, `total = 300`. **Expected by the code:**
`n = 3` with `total = 100 + 0 + 200 = 300` — a row whose COUNT and SUM cannot both be right. Then
retract `(u1, NULL, 2s)`: `n = 3 - 1 = 2`, `total` unchanged at 300, which is accidentally correct
and must not be recorded as a pass for the insert half.
**Vacuity:** Assert both columns of the same row. Asserting `total` alone passes while `n` is wrong.

### INCR-017 — Windowed MIN over a NULL first row returns 0, not the minimum
**Intent:** The same `scratch[i] = … ? 0 : …` line. For MIN, a NULL argument is not skipped — it is
fed as the value `0`, and `accumulator.count == weight` makes it the seed.
**Falsifier:** `lo` equals 100.
**Setup:** H1, W1 rows for u1 in this order: `(u1, NULL, 1s)`, `(u1, 100, 2s)`, `(u1, 200, 3s)`.
Query `MIN(amount) AS lo, MAX(amount) AS hi`.
**Steps:** Feed; advance; finish; commit.
**Expected by SQL:** `MIN` ignores NULL → `lo = 100`, `hi = 200`. **Expected by the code:** the first
row seeds `values = 0`, then `min(0, 100) = 0`, `min(0, 200) = 0` → **`lo = 0`**. `hi`:
`max(0,100) = 100`, `max(100,200) = 200` → `hi = 200`, unaffected. Reversing the order —
`(u1,100,1s)` first — is expected to give `lo = min(100, 0) = 0` as well, so assert both orders.
**Vacuity:** The all-non-null control must give `lo = 100`; if it gives 0 the harness is wrong.

### Global (`GlobalAggregate`) — reachable only over a bounded input

### INCR-018 — Global SUM: a retraction subtracts, over a bounded input
**Intent:** `GlobalAggregate.process` does `sums[i] += row.getLong(ord) * weight`. Establish the
arithmetic on the only input shape that makes this operator emit.
**Falsifier:** `total` is 350 after ρ3.
**Setup:** H1 over `SELECT SUM(amount) AS total, COUNT(*) AS n FROM txn`, view keyed on ordinal 0.
Bounded: `finish()` is called.
**Steps:** Feed r1, r2, r3, ρ3, r4. `finish()`; commit; read.
**Expected:** `100 + 50 + 200 - 200 + 7 = 157`; `n = 5 - 1 = 4`.
**Vacuity:** C2 — without ρ3, `total = 357`, `n = 5`. C1 — five `process` calls, not four.

### INCR-019 — Global COUNT(col) skips NULL, and the retraction of a NULL row does not
**Intent:** `GlobalAggregate` is the *one* operator carrying the `COUNT(col)` null check:
`if (call.argumentOrdinal() < 0 || !row.isNull(...)) counts[i] += weight`. Under a retraction the
guard runs on the *retracting* row, which is the case the fix was never checked against.
**Falsifier:** `n` counts the NULL row, or the retraction of a NULL row decrements `n`.
**Setup:** H1, global `COUNT(amount) AS n, COUNT(*) AS all`. Rows: `(u1,100,1s)`, `(u1,NULL,2s)`,
`(u1,200,3s)`, then retract `(u1,NULL,2s)`.
**Steps:** Feed the three rows in order, then the retraction of the NULL row. `finish()`; commit; read. Assert `n` and `all` after the third row and again after the retraction.
**Expected:** `n`: 100 counted, NULL skipped, 200 counted, retraction of a NULL row skipped →
`1 + 0 + 1 - 0 = 2`. `all`: `1 + 1 + 1 - 1 = 2`. Both 2, and they agree for the right reason. Also
assert the intermediate: before the retraction `n = 2`, `all = 3`.
**Vacuity:** The intermediate assertion is what stops this passing with the null check absent.

### INCR-020 — Global SUM over a fully-retracted input reports 0 where SQL says NULL
**Intent:** `emit()` is unconditional for a global aggregate — there is no `rowCount == 0` guard,
unlike `KeyedAggregate`. So a stream whose every row has been retracted still produces a row.
**Falsifier:** No row is emitted, or a NULL total is emitted.
**Setup:** H1, global `SUM(amount) AS total, COUNT(*) AS n, AVG(amount) AS avg`. Feed r1 then ρ1.
**Steps:** `finish()`; commit; read.
**Expected by the code:** one row, `total = 100 - 100 = 0`, `n = 1 - 1 = 0`,
`avg = (counts == 0 ? 0 : …) = 0`. **Expected by SQL:** `SUM` and `AVG` over an empty relation are
NULL; `COUNT(*)` is 0. Record all three. `rowCount()` is `0`, and the row is emitted anyway with
`weight(1L)`.
**Vacuity:** C1 — two rows in. C3 — assert the row exists before ρ1 with `total = 100`.

### INCR-021 — A retraction arriving after its window has fired is dropped, not applied
**Intent:** The headline consequence of `DEFAULT_ALLOWED_LATENESS_NANOS = 0L`. The view keeps a
number the input has withdrawn, permanently, and the only evidence is `lateRecords()`.
**Falsifier:** The view shows 100 for u1 after the late ρ3, or a `-1` change is delivered to a
subscriber.
**Setup:** H1, windowed SUM. Instrument `lateOutput` with a counting sink.
**Steps:** Feed r1, r2, r3, r4. `advanceWatermark(20s)` — W1 fires, u1 → 300. Commit and read
(assert 300). *Then* feed ρ3, whose `window_start = 0s` and `lastWindowEndFor(0s) = 10s`, against
`watermark = 20s`: `10s + 0 <= 20s` is true. `advanceWatermark(21s)`; finish; commit; read again.
**Expected:** ρ3 goes to the late output. `lateRecords() == 1`. `corrections() == 0`. The view still
shows `total = 300`. *B(M)* says 100. Record the divergence as permanent — no later watermark
recovers it.
**Vacuity:** C2 — the same ρ3 fed *before* the watermark (INCR-007) must give 100, proving the
retraction itself is well-formed and the difference is arrival time alone.

### INCR-022 — The documented retract-and-re-emit correction path is unreachable through SQL
**Intent:** `WindowedAggregate`'s class javadoc and `CONCEPTS.md` §4 both promise that a late record
"re-opens that window: the previous result is retracted with weight `-1` and the corrected one
emitted". `emitWindow` implements exactly that. With lateness fixed at zero, the `dirty` set is never
populated, because every row that would populate it has already been rejected by the guard above it.
**Falsifier:** Any SQL-planned query producing a non-zero `corrections()`, or any subscriber to a
SQL-planned windowed query receiving a change with a negative weight.
**Setup:** H2 where possible, else H1 on a SQL-built plan. Windowed SUM, a subscriber attached via
`RegisteredQuery.subscribe` recording every `ViewChange` and its weight.
**Steps:** Feed W1's rows; advance past 10s (window fires, subscriber sees `+1`); feed a late but
in-window row `(u1, 5, 4s)`; advance again; finish.
**Expected:** the late row is counted in `lateRecords()` and never reaches the accumulator;
`corrections() == 0`; the subscriber's recorded weights are **all `+1`**. Then run the identical
scenario in H1 with the operator constructed directly at
`allowedLatenessNanos = 5 * SECOND` and show the correction *does* occur — subscriber sees `-1` of
`300` then `+1` of `305` — proving the path works and only the default makes it dead.
**Vacuity:** The second half is the vacuity proof: without it, "no corrections observed" is
indistinguishable from a broken correction path.

### INCR-023 — Global MIN/MAX refuse a retraction, with the same code and a different message
**Intent:** The third copy of the MIN/MAX refusal. Three copies of one rule is three chances for them
to diverge; check they have not.
**Falsifier:** A different error code from `PRV-3020`, or acceptance.
**Setup:** H1, global `MIN(amount) AS lo`. Feed r1, r3, then ρ3.
**Steps:** Feed r1 (`amount 100`), then r3 (`amount 200`), then ρ3. Capture the exception at the third call.
**Expected:** `PRV-3020`. Message: "cannot yet handle a retraction: restoring the previous extreme
needs an ordered multiset per group, which arrives with the aggregate lift. Use SUM or COUNT for
now." — note `GlobalAggregate`'s wording differs from `SlicedAggregateState`'s (INCR-010) and from
`KeyedAggregate`'s (INCR-024). Record all three strings; one error code with three messages is an
`ERRC` finding.
**Vacuity:** C2 — r1 and r3 alone give `lo = 100`.

### Keyed, unwindowed (`KeyedAggregate`) — read path only

### INCR-024 — Every weighted branch in `KeyedAggregate` is unreachable from any shipped surface
**Intent:** `KeyedAggregate` carries the full retraction arithmetic: `rowCount += weight`,
`counts[i] += weight`, `sums[i] += value * weight`, the `if (group.rowCount == 0) continue` guard
that suppresses a fully-retracted group, and a `PRV-3020` MIN/MAX refusal. The only caller is
`ViewQuery.run`, which stamps `writer.weight(1L)` on every row of `view.scan()`. None of it can run.
**Falsifier:** Any shipped path that hands `KeyedAggregate` a row with `weight != 1`.
**Setup:** Repository at the commit under test, plus H1 for the reachable half.
**Steps:** (a) Enumerate constructors of `KeyedAggregate` across the tree. (b) Read the weight written
on the read path. (c) In H1, construct `KeyedAggregate` directly and feed `(tier='gold', 100, +1)`,
`(tier='gold', 100, -1)`, `(tier='silver', 5, +1)`, then `emit()`.
**Expected:** (a) one production caller, `InterpretedPipeline`; (b) `weight(1L)`, unconditional;
(c) direct construction behaves correctly — `gold` has `rowCount = 1 - 1 = 0` and is **not emitted**,
`silver` emits `total = 5`, `groupCount()` reports **2** (it counts zeroed groups). Conclusion:
correct code, zero reachability. Record it as such rather than as a pass.
**Vacuity:** (c) is the vacuity proof for (a) and (b): the branches work, so their absence from the
shipped path is a routing fact and not a masked bug.

---

## §3 — Net-zero: the key must disappear, not remain as a zero row

`ZSet`'s central invariant: "Zero weight means absent … a row whose weight reaches zero is removed
rather than stored as a zero." Seven places that has to hold.

### INCR-025 — A windowed group whose weights cancel is not emitted
**Intent:** `SlicedAggregateState.fire` skips a combined accumulator with `count != 0` false.
**Falsifier:** W1 contains a `u1` row with `total = 0` and `n = 0`.
**Setup:** H1, windowed `SUM, COUNT`. W1 rows: r1, r3, then ρ1 and ρ3, all before the watermark.
**Steps:** Feed r1, r3, ρ1, ρ3, r4; watermark 20s; finish; commit; `view.scan()`.
**Expected:** u1's count is `1 + 1 - 1 - 1 = 0` → no result row for u1 in W1. The view has **no**
`u1` key. *B(M)*: the net input for W1 is empty, so the batch answer has no u1 row either. u2 is
absent from this fixture; `scan()` returns only W4's row for u3.
**Vacuity:** C3 — assert `u1` present with `total = 300` in a control run without ρ1/ρ3.

### INCR-026 — A window that already published a key, then nets to zero, keeps the stale row forever
**Intent:** The most serious consequence of `emitWindow`'s shape. It iterates `state.fire(windowEnd)`
and writes `emitted.put(windowEnd, current)`. A key present in `previous` but absent from the new
`fire()` result — which is exactly what a net-zero group is — has **no retraction emitted for it**.
The published `+1` is never withdrawn.
**Falsifier:** A `-1` for the old `u1` row reaches the view or a subscriber.
**Setup:** H1, windowed SUM, operator constructed directly with
`allowedLatenessNanos = 30 * SECOND` so the correction path is reachable at all (see INCR-022).
A subscriber records every `ViewChange` and weight.
**Steps:** Feed r1, r3, r4. `advanceWatermark(20s)` → W1 fires, u1 → `100 + 200 = 300`, subscriber
sees `+1 [u1, 0s, 10s, 300]`. Commit, read, assert 300. Then feed ρ1 and ρ3 (within lateness),
`advanceWatermark(21s)`, finish, commit, read.
**Expected by the property:** the net input for W1 is empty, so *B(M)* has no u1 row; the view must
not hold one, which requires a `-1 [u1, 0s, 10s, 300]`. **Expected by the code:** `fire()` returns
nothing for u1, the `previous != null` branch is never entered for that key, no retraction is
emitted, and the view still answers `u1 → 300`. Record the subscriber's full weight sequence.
**Vacuity:** The same run with ρ3 only (not ρ1) must produce `-1 [300]` then `+1 [100]`, proving the
correction machinery is working and that only the *disappearing* key is mishandled.

### INCR-027 — A view key whose weight nets to zero disappears from the view
**Intent:** `ServedView.apply` on `row.weight() < 0` stages `null` — a tombstone — and `commit`
does `visible.remove(key)`.
**Falsifier:** `get("u1")` returns a row after the tombstone commits, or `scan()` includes it.
**Setup:** H1 + `ServedView("v", schema, List.of(0), 10_000)`, driven through `ViewSink` directly.
**Steps:** `applyValues({"u1", 300}, +1, 10s)`; `commit(10s)`; assert found. `applyValues({"u1",
300}, -1, 20s)`; assert still found (uncommitted); `commit(20s)`; assert not found; `size() == 0`.
**Expected:** exactly that sequence. The intermediate "still found" assertion is the committed/pending
separation and must be asserted, not skipped.
**Vacuity:** C3 — the first assertion. Without it the final `found() == false` is satisfied by a view
that was never populated.

### INCR-028 — `ServedView` treats a weight-zero row as an insert
**Intent:** `apply` branches on `row.weight() < 0` only. A consolidated row at weight `0` — which
`ZSet` defines as *absent* and which `GlobalAggregate`, `KeyedAggregate` and
`SlicedAggregateState` all explicitly skip — falls into the `else` and **sets the key's value**.
**Falsifier:** A zero-weight row leaves the view unchanged.
**Setup:** H1 + `ServedView` driven directly through `applyValues`.
**Steps:** `applyValues({"u1", 300}, +1, 10s)`; `commit(10s)`. Then `applyValues({"u1", 999}, 0,
20s)`; `commit(20s)`; `get("u1")`. Also `applyValues({"u2", 5}, 0, 20s)`; `commit(20s)`;
`get("u2")`.
**Expected by the invariant:** weight 0 contributes nothing; `u1` stays `300` and `u2` never
appears. **Expected by the code:** `u1 → 999` and `u2 → 5` is created from nothing.
Also record `updates()`, which is incremented for the zero-weight row.
**Vacuity:** The `u1` half proves the value was overwritten rather than merely unchanged; the `u2`
half proves a key was created. Either alone is weaker.

### INCR-029 — `ServedView` removes a key on one `-1` regardless of its accumulated weight
**Intent:** The view is a last-write-wins map, not a Z-set. `apply` does not net weights: a single
`-1` deletes a key whose Z-set weight is 2.
**Falsifier:** The key survives the first `-1` with weight 1 remaining.
**Setup:** H1 + `ServedView` keyed on ordinal 0.
**Steps:** `applyValues({"u1", 300}, +1, 1s)`; `applyValues({"u1", 300}, +1, 2s)`; `commit(2s)`;
assert found. `applyValues({"u1", 300}, -1, 3s)`; `commit(3s)`; `get("u1")`.
**Expected by the property:** net weight `1 + 1 - 1 = 1` → the row is still present.
**Expected by the code:** removed. Record `removals()` and `size()`.
**Vacuity:** C3 — assert found after the two inserts. The two inserts must also be *separate*
`applyValues` calls: a single call at weight 2 collapses into the same pending slot and the case
would be testing nothing about accumulation.

### INCR-030 — Order within one uncommitted batch decides whether an update survives
**Intent:** `apply`/`applyValues` write into a `LinkedHashMap` overlay keyed by the view key, so the
*last* change for a key in a batch wins outright. For an update (`-1` old, `+1` new) that is correct;
for the reverse arrival order the row disappears.
**Falsifier:** Both orders produce the same committed state.
**Setup:** H1 + `ServedView` keyed on ordinal 0, two batches.
**Steps:** Batch A: `applyValues({"u1",300}, -1, …)` then `applyValues({"u1",100}, +1, …)`;
`commit`. Batch B (fresh view, same prior state): `applyValues({"u1",100}, +1, …)` then
`applyValues({"u1",300}, -1, …)`; `commit`.
**Expected:** A → `u1 = 100`. B → `u1` absent. *B(M)* is identical for both (`{old:-1, new:+1}`
nets to the new row present), so B diverges from the batch answer. Record which order the runtime
actually produces for a windowed correction: `emitWindow` emits the `-1` before the `+1`, i.e.
order A, so the defect is latent rather than live — and any future reordering makes it live silently.
**Vacuity:** Both halves must be run; either alone is a single-order assertion.

### INCR-031 — A join entry whose weight cancels is unlinked, not left at zero
**Intent:** `JoinSide.add` unlinks on `updated == 0`, and `InterpretedPipeline.joinRowsHeld()`'s
javadoc names this exact leak: "An entry whose weight has cancelled to zero emits nothing and matches
nothing — the results stay correct — but if it is not unlinked it holds its block forever."
**Falsifier:** `joinRowsHeld()` does not return to its pre-insert value after the retraction, or
`keysHeldLeft()` retains an empty bucket.
**Setup:** H1 over an inner equi-join between two streams with a time bound.
**Steps:** Record `joinRowsHeld()` and `keysHeldLeft()`. Feed one left row; record again. Feed its
retraction; record again. Repeat the insert/retract pair 10 000 times.
**Expected:** held goes `0 → 1 → 0`; distinct keys `0 → 1 → 0` (the bucket is dropped when its last
row goes, per `unlink`). After 10 000 cycles both are still `0`, and `stateBytes()` has not grown
beyond the first slab.
**Vacuity:** The 10 000 cycles are the vacuity proof — a single cycle can pass while a block-level
leak accumulates at one entry per cycle. Record `stateBytes()` at cycle 1 and cycle 10 000.

---

## §4 — A retraction with no matching insert

### INCR-032 — A retraction arriving before its insert nets correctly in a window
**Intent:** DBSP's arithmetic does not require causal order. `SlicedAggregateState.update` will
happily create an accumulator at a negative count.
**Falsifier:** The final W1 total differs from the same rows fed in causal order.
**Setup:** H1, windowed `SUM, COUNT`, single key u1.
**Steps:** Feed **ρ3 first** (`(u1,200,3s)` at `-1`), then r1, then r3; watermark 20s; finish;
commit. Compare against a second harness fed r1, r3, ρ3.
**Expected:** both runs give `total = 100 + 200 - 200 = 100` and `n = 1`. Order-independence is the
assertion. Also record the intermediate state after ρ3 alone: `count = -1`, and `fire()` would emit
`count != 0` → a row with `n = -1`, `total = -200`, which is why the intermediate must not be
committed to a view.
**Vacuity:** Two runs, compared. A single run asserting 100 passes whatever the ordering behaviour.

### INCR-033 — A retraction of a key that was never inserted creates a negative group
**Intent:** `ZSet` permits negative cardinality — "a pure retraction has negative cardinality" — but
a *view* showing `n = -1` is not an answer anybody should be given.
**Falsifier:** The view holds no row for the never-inserted key, or holds one with `n = 0`.
**Setup:** H1, windowed `SUM(amount) AS total, COUNT(*) AS n`, view keyed on `user_id`.
**Steps:** Feed only `(u9, 42, 4s)` at weight `-1`, then r4 to advance. Watermark 20s; finish;
commit; `view.scan()`.
**Expected by the code:** the accumulator's `count = -1 != 0`, so `fire()` emits it; `emitRow` writes
`weight(1L)`; `ServedView.apply` sees a positive weight and **inserts** it. The view holds
`u9 → total = -42, n = -1`. *B(M)*: the net form contains a negative weight, so there is no batch
answer — a batch engine would have refused or produced nothing. Record the observed row verbatim.
**Vacuity:** C1 — one row in. Assert `u9` is absent before the feed, so its appearance is caused.

### INCR-034 — A retraction of an absent key in a join emits negative pairs
**Intent:** `SymmetricHashJoin.acceptLeft` emits `weight * storedWeight` with no sign check, and
`JoinSide.add` stores the unmatched retraction at weight `-1`.
**Falsifier:** No pair is emitted, or a pair with a positive weight is.
**Setup:** H1, inner equi-join `orders ⋈ shipments ON order_id`, time-bounded. Right side already
holds `(s1, o-1)` at `+1`.
**Steps:** Feed a left row `(o-1, …)` at weight `-1`.
**Expected:** one output pair at weight `-1 * +1 = -1`. Downstream, `ServedView.apply` sees
`weight < 0` and stages a tombstone for a key that was never in the view; `commit` calls
`visible.remove(key)` on an absent key, which is a no-op. So the observable result is: `removals()`
incremented, `size()` unchanged at 0. Record both.
**Vacuity:** C2 — the same left row at `+1` must produce a visible joined row, proving the join
matched at all.

### INCR-035 — Repeated retractions of the same row accumulate rather than saturating
**Intent:** Three `-1`s of one row must reach `-3`, not stop at `-1` or at `0`.
**Falsifier:** The third retraction is a no-op.
**Setup:** H1, windowed `SUM, COUNT`, key u1, W1.
**Steps:** Feed r3 three times (`+1` each), then ρ3 three times. Then feed ρ3 a **fourth** time.
Watermark 20s; finish; commit.
**Expected:** after three of each, `count = 3 - 3 = 0` → no row for u1 (INCR-025). After the fourth,
`count = -1`, `total = -200` → a row appears again with negative values, matching INCR-033. Assert
both stages.
**Vacuity:** The intermediate zero state must be asserted; without it, "a row with `n = -1`" is
reachable by many wrong routes.

### INCR-036 — A retraction of an absent key leaves no residue in the join index
**Intent:** The state-growth half of INCR-034. An unmatched retraction is *stored* — `JoinSide.add`
inserts it at `-1` and increments `distinctRows` — so a stream of retractions for keys that never
existed grows the join's state exactly as fast as inserts would.
**Falsifier:** `rowsHeldLeft()` stays at 0 while retractions are fed.
**Setup:** H1, inner equi-join, ceiling `maxRowsPerSide` set to 1 000 for the case.
**Steps:** Feed 1 001 distinct left rows, all at weight `-1`, none matching anything.
**Expected:** `rowsHeldLeft()` climbs `1, 2, … 1000`, and the 1 001st raises `PRV-4001`
(`STATE_TOO_LARGE`) naming the count — the same refusal an insert flood would raise. The batch answer
for this input is empty. Record whether any operator anywhere refuses a retraction of an absent key;
none is expected to.
**Vacuity:** Assert `rowsHeldLeft() == 0` before the feed and the exact climb, not just the final
exception. The exception alone could come from the insert path in a mis-wired harness.

### INCR-037 — Eviction of a retraction-only entry never emits a null-padded row
**Intent:** `JoinSide.evictOlderThan` calls the unmatched sink only when `weight > 0`. A left-outer
join whose left row exists only as a retraction must not manufacture an output row.
**Falsifier:** A null-padded row is emitted for the negative entry.
**Setup:** H1, `LEFT JOIN` with a time bound (unbounded `LEFT JOIN` is refused, `PRV-2020`).
**Steps:** Feed a left row at `-1` with event time 0s, nothing on the right. `advanceWatermark(10
minutes)` so the horizon passes it.
**Expected:** `evicted()` counts 1; `unmatchedEmitted() == 0`; no output row. *B(M)* is empty.
**Vacuity:** C2 — the same row at `+1`, same eviction, must produce exactly one null-padded row and
`unmatchedEmitted() == 1`.

### INCR-038 — A retract-then-insert pair straddling an eviction produces a phantom row
**Intent:** The composition of INCR-037 and the eviction rule. The retraction is stored, aged out
unmatched (emitting nothing, correctly), and then the insert arrives fresh, ages out unmatched, and
*does* emit — so the pair that nets to nothing produces one output row.
**Falsifier:** No row is emitted, or the emitted row is later retracted.
**Setup:** H1, time-bounded `LEFT JOIN`, match window 1 minute.
**Steps:** t=0s feed left row L at `-1`. `advanceWatermark(5 minutes)` → L's entry evicted, nothing
emitted. Then feed L at `+1` with the same event time 0s. `advanceWatermark(10 minutes)` → evicted
again, now with `weight > 0`.
**Expected:** exactly one null-padded row, `unmatchedEmitted() == 1`, and it is **never retracted** —
`emitNullPadded`'s javadoc says so explicitly: "Emitted once … and never retracted." *B(M)*: the net
input is empty, so the batch answer is no rows. Divergence of one row, permanently.
**Vacuity:** Assert `unmatchedEmitted() == 0` after the first eviction. Without it the single final
row is indistinguishable from the first eviction having emitted it.

---

## §5 — Weights greater than one

### INCR-039 — A windowed SUM scales with the weight
**Intent:** `values[i] * weight`. A weight of 3 must be worth three rows, not one.
**Falsifier:** `total` is 100 for a single arrival at weight 3.
**Setup:** H1, windowed `SUM, COUNT`, key u1, W1.
**Steps:** Feed `(u1, 100, 1s)` once at **weight 3**; r4; watermark 20s; finish; commit.
**Expected:** `total = 100 * 3 = 300`; `n = 3`. *B(M)* expands the weight-3 row into three arrivals
and gives the same: `100 + 100 + 100 = 300`, `n = 3`.
**Vacuity:** Run *B(M)* — three separate `+1` arrivals — in the same process and assert the two
results are equal. A single assertion of 300 is satisfied by a harness that ignores weight and
happens to be fed three rows.

### INCR-040 — A weight-3 insert partly retracted by a weight-2 retraction
**Intent:** Partial cancellation, which is where a "delete means remove the key" implementation
diverges from arithmetic.
**Falsifier:** The key disappears, or `n` is 0 or 3.
**Setup:** As INCR-039.
**Steps:** Feed `(u1,100,1s)` at `+3`, then the same row at `-2`; r4; advance; finish; commit.
**Expected:** `n = 3 - 2 = 1`; `total = 300 - 200 = 100`. *B(M)*: net weight `+1` → one arrival →
`total = 100`, `n = 1`.
**Vacuity:** C3 — after the `+3` and before the `-2`, assert `n = 3`, `total = 300`.

### INCR-041 — A weight-3 insert over-retracted by a weight-5 retraction
**Intent:** The negative side of partial cancellation.
**Falsifier:** The result is clamped at zero.
**Setup:** As INCR-039.
**Steps:** `+3` then `-5`; advance; finish; commit.
**Expected:** `n = 3 - 5 = -2`; `total = 300 - 500 = -200`; the group's `count != 0` so it **is**
emitted, and `ServedView` inserts it because `emitRow` writes `weight(1L)`. The view holds
`u1 → total = -200, n = -2`. No batch answer exists for a net-negative input; record the observation.
**Vacuity:** C3 as INCR-040.

### INCR-042 — A join pairs weights multiplicatively
**Intent:** `emit(row, stored, weight * storedWeight)`. `IncrementalJoin`'s batch form does the same
(`left.getValue() * right.getValue()`), so the two must agree.
**Falsifier:** The output weight is not the product.
**Setup:** H1, inner equi-join on `order_id`, time-bounded.
**Steps:** Feed right `(o-1, A)` at `+2`. Then left `(o-1, X)` at `+3`.
**Expected:** one output pair at weight `3 * 2 = 6`. Cross-check against `IncrementalJoin.step` in
`pravaha-algebra` with the same two Z-sets: `ZSet.of(X, 3)` and `ZSet.of(A, 2)` must yield a result
whose single entry has weight 6. Assert the two agree.
**Vacuity:** Assert both sides. The runtime alone could return 6 by any route; the algebra
cross-check is what makes it the *product*.

### INCR-043 — A join of two retractions produces a positive pair
**Intent:** `(-1) * (-1) = +1` is correct DBSP arithmetic for the `ΔA⋈ΔB` term and looks like a bug
to anybody reading the output. Pin it, and pin that the net result is still right.
**Falsifier:** The net pair weight over the whole sequence is anything but 0.
**Setup:** H1, inner equi-join, output collected with weights (not through a view, which nets
destructively — see INCR-029).
**Steps:** Feed left `L(o-1)` at `+1`, right `R(o-1)` at `+1` → pair at `+1`. Then left `L` at `-1`
→ pair at `-1`. Then right `R` at `-1`.
**Expected:** the third step probes the left index, which `unlink` has already emptied for that key,
so it emits **nothing**. Total emitted weights: `+1, -1` → net 0. *B(M)*: empty input, no pairs.
Now run the retractions in the *same step* order reversed (right `-1` first, then left `-1`): right's
retraction probes the left index, still holding `L` at `+1`, and emits `+1 * -1 = -1`; then left's
retraction probes the right index, now emptied, and emits nothing. Net 0 again. Assert both orders.
**Vacuity:** Assert the emitted weight *sequence*, not the sum. A join emitting nothing at all also
sums to zero.

### INCR-044 — Weight-3 rows in a `COUNT(DISTINCT)` window count once
**Intent:** `seen.merge(value, weight, Long::sum)` — three of the same value is one distinct value
with a multiplicity of 3.
**Falsifier:** `users` is 3 for one repeated value.
**Setup:** H1, windowed `COUNT(DISTINCT user_id) AS users`, ids of distinct byte lengths (`'a'`,
`'bb'`) to avoid INCR-014's defect.
**Steps:** Feed `('a', 1, 1s)` at `+3` and `('bb', 2, 2s)` at `+1`; advance; finish; commit.
Then feed `('a', 1, 1s)` at `-2` and re-fire.
**Expected:** first `users = 2` (`'a'` multiplicity 3, `'bb'` 1). After the `-2`, `'a'` has
multiplicity `3 - 2 = 1`, still present → `users = 2` still. After a further `-1`, `'a'` reaches 0,
is removed → `users = 1`.
**Vacuity:** All three stages asserted. Asserting only the last is satisfied by an implementation
that drops a value on the first retraction.

### INCR-045 — A left-outer null-padded row ignores the left row's weight
**Intent:** `emitNullPadded` writes `weight(1L)` unconditionally. A left row stored at weight 3 that
never matches produces **one** output row where the batch answer has three.
**Falsifier:** Three null-padded rows, or one row at weight 3.
**Setup:** H1, time-bounded `LEFT JOIN`, no matching right rows.
**Steps:** Feed left `L` at weight **3**, event time 0s. `advanceWatermark(10 minutes)`.
**Expected by the code:** `evictOlderThan` visits the single entry once, `weight > 0` is true, and
`emitNullPadded` emits one row at `+1`. `unmatchedEmitted() == 1`. **Expected by SQL:** three
null-padded rows, or one row of weight 3. Record which.
**Vacuity:** C2 — the same input at weight 1 also gives `unmatchedEmitted() == 1`, so this case is
only meaningful with the weight-3 feed *and* the weight-1 control asserted side by side.

---

## §6 — An update is a retraction plus an insert

The view must show the new value, not both, and not neither.

### INCR-046 — A windowed aggregate under an update shows only the new total
**Intent:** The end-to-end statement of "an update is not a special kind of record".
**Falsifier:** The view holds two rows for u1, or the old total.
**Setup:** H1, windowed `SUM(amount) AS total`, view keyed on `user_id` alone.
**Steps:** Feed r1, r3. Then the update of r3 from 200 to 250: `ρ3` (`-1`) followed by
`(u1, 250, 3s)` at `+1`. Both before the watermark. Advance; finish; commit; `view.scan()`.
**Expected:** `100 + 200 - 200 + 250 = 350`. Exactly **one** row for u1, `total = 350`.
*B(M)*: net input is `{r1:+1, (u1,250,3s):+1}` → `100 + 250 = 350`. Agreement.
**Vacuity:** C2 — without the update, `total = 300`. Assert `view.size()` is 1 for the u1 key, not
just that a 350 exists somewhere.

### INCR-047 — The same update, keyed on the value column, leaves two rows
**Intent:** A view keyed on a column the update changes cannot express an update at all: the `-1`
tombstones `(u1, 300)` and the `+1` creates `(u1, 350)` — but the `-1` carries the *old* aggregate
value only if the operator emitted it, which for a pre-fire correction it does not.
**Falsifier:** The view holds exactly one row for u1.
**Setup:** As INCR-046 but `ServedView` keyed on `List.of(userIdOrdinal, totalOrdinal)`.
**Steps:** As INCR-046.
**Expected:** the window fires once, after the update, emitting one row `(u1, 350)` at `+1`. So one
row — and the case's value is the *contrast*: repeat with the update arriving **after** the window
fires and within a non-zero lateness (operator constructed directly, per INCR-022) and record
whether `emitWindow`'s `-1` of `(u1, 300)` and `+1` of `(u1, 350)` leave one key or two under this
key definition. Expected: two keys are written and one is tombstoned, leaving `(u1, 350)`.
**Vacuity:** Both arrangements run; assert `view.size()` in each.

### INCR-048 — An update through a join retracts exactly the old pairs
**Intent:** `SymmetricHashJoin`'s javadoc: "An update — retract old, insert new — therefore retracts
precisely the old row's pairs and emits the new row's, with no update path in the code."
**Falsifier:** The old pair survives, or the new pair is missing, or a pair appears twice.
**Setup:** H1, inner equi-join `L(order_id, qty) ⋈ R(order_id, region)`, time-bounded. Right holds
`(o-1, EU)` and `(o-1, US)` — a fan-out of 2.
**Steps:** Feed left `(o-1, 5)` at `+1` → two pairs at `+1`. Then `(o-1, 5)` at `-1` and
`(o-1, 7)` at `+1`.
**Expected:** emitted weight sequence: `+1 (5,EU)`, `+1 (5,US)`, `-1 (5,EU)`, `-1 (5,US)`,
`+1 (7,EU)`, `+1 (7,US)`. Net: the two `(5, …)` pairs cancel to zero and the two `(7, …)` pairs
stand. `rowsHeldLeft()` returns to 1 (the old entry unlinked, the new one stored).
**Vacuity:** Assert the sequence of six emissions, not the final view. The final view is reachable
by an implementation that never emitted the retractions at all.

### INCR-049 — An update where the new row equals the old is a no-op on the wire
**Intent:** `ZSet.Builder.add` cancels a `+1`/`-1` pair inside the operator so it never propagates —
"that pruning is where the efficiency actually comes from". Check the runtime does the same.
**Falsifier:** Two rows appear downstream and consolidate to nothing only at the view.
**Setup:** H1, windowed SUM, a counting downstream `RowProcessor` between the aggregate and the sink.
**Steps:** Feed r3, then ρ3, then r3 again — a degenerate update to the same value. Advance; finish.
**Expected:** the accumulator ends at `count = 1`, `total = 200`, and the window fires **once**,
emitting one row. Downstream row count for W1 is 1, not 3. If the correction path is involved
(non-zero lateness, per INCR-022), `emitWindow` explicitly suppresses an unchanged re-emission:
"Emitting a retraction and an identical insertion would be two rows that consolidate to nothing,
which is arithmetically harmless and pure noise on the wire." Assert the downstream count.
**Vacuity:** C2 — the same three feeds with a *changed* value (200 → 250) must produce a different
downstream count in the correction case, proving the counter distinguishes them.

### INCR-050 — An update delivered to a subscriber arrives as `-1` then `+1`, in that order
**Intent:** `CONCEPTS.md` §4 promises the ordering, and `emitWindow` implements it — the `-1` is
emitted before the `+1` inside the same key's loop iteration. A subscriber applying changes in
arrival order depends on it.
**Falsifier:** The `+1` precedes the `-1`, or the two arrive in different commit batches.
**Setup:** H1, windowed SUM, operator constructed with `allowedLatenessNanos = 30 * SECOND`,
subscriber recording `(values, weight)` per `ViewChange` and the commit boundary between batches.
**Steps:** Feed r1, r3, r4; advance past 10s (batch 1: `+1 [u1, 300]`). Feed `(u1, 50, 4s)`;
advance; finish; commit.
**Expected:** batch 2 contains exactly two changes, in order: `-1 [u1, 0s, 10s, 300]` then
`+1 [u1, 0s, 10s, 350]` (`300 + 50 = 350`). Both in one batch — `ViewSink.commit` hands the whole
`pending` list to each listener at once, so a subscriber never sees the retraction without its
replacement.
**Vacuity:** Assert the batch *boundary*: two batches, sizes 1 and 2. A flat list of three changes
would satisfy an order assertion while breaking the atomicity promise.

### INCR-051 — Corrections fire before newer windows in the same advance
**Intent:** `advanceWatermark` drains `dirty` before `windowsCompletedBetween`, deliberately: "a
consumer applying results in arrival order should see the fix for an old window before the results of
newer ones."
**Falsifier:** W2's result is delivered before W1's correction.
**Setup:** H1, windowed SUM, `allowedLatenessNanos = 30 * SECOND`, subscriber recording order.
**Steps:** Feed W1 rows; advance to 10s (W1 fires). Feed a late W1 row **and** a W2 row
(`(u1, 9, 12s)`). Advance to 20s.
**Expected:** in the batch for the second advance, W1's `-1`/`+1` correction pair appears **before**
W2's `+1`. Assert the index positions.
**Vacuity:** Feed the W2 row *first* in the same step and assert the order is unchanged — proving the
ordering comes from the operator, not from arrival order.

### INCR-052 — An update that crosses a window boundary is two windows' changes, not one
**Intent:** An update to the event-time column moves a row between windows. Because
`WindowedAggregate` keys slices by `window_start`, the `-1` lands in one slice and the `+1` in
another, and both windows must change.
**Falsifier:** Only one window's total changes.
**Setup:** H1, windowed SUM, `allowedLatenessNanos = 30 * SECOND`. W1 = `[0s,10s)`, W2 = `[10s,20s)`.
**Steps:** Feed `(u1, 100, 3s)`. Advance to 10s → W1 fires with 100. Then the update: `(u1, 100, 3s)`
at `-1` and `(u1, 100, 13s)` at `+1`. Advance to 20s.
**Expected:** W1's slice count reaches 0 → per INCR-026, **no retraction of W1's published 100 is
emitted** and the view still shows `u1` at 100 for W1. W2 fires with `total = 100`. If the view is
keyed on `user_id` alone, the W2 row overwrites the W1 row and the answer is accidentally right; if
keyed on `(user_id, window_end)` it holds both, total 200 where the batch answer is 100. Run **both**
key definitions and record each.
**Vacuity:** Both key definitions must be run; the single-key run alone hides the defect entirely,
which is exactly how this class of bug survives.

---

## §7 — The join, term by term

### INCR-053 — The `ΔA⋈ΔB` term: two rows arriving together do match
**Intent:** `IncrementalJoin`'s javadoc calls this "the one people drop", invisible at low rates.
`SymmetricHashJoin` claims to get it for free from row-at-a-time ordering.
**Falsifier:** Two rows fed back to back with matching keys produce no pair.
**Setup:** H1, inner equi-join, both indexes empty.
**Steps:** Feed left `(o-1, X)`, then right `(o-1, A)`, with nothing previously in either side.
Then the reverse order in a fresh harness.
**Expected:** one pair in each case, weight `+1`. `pairsEmitted() == 1`.
**Vacuity:** Assert `rowsHeldLeft() == 0` and `rowsHeldRight() == 0` before the feed. With state
already present the pair could come from the `ΔA⋈I(B)` term instead, which is the term everybody
gets right.

### INCR-054 — The batched and row-at-a-time forms agree on the same input
**Intent:** The claim that `SymmetricHashJoin` and `IncrementalJoin` are "tested against each other".
**Falsifier:** Any input where the two produce different output multisets.
**Setup:** H1 plus `IncrementalJoin` from `pravaha-algebra`. 500 generated steps, seed `20260909`,
keys `{o-1 … o-20}`, weights from `{+1, +1, -1, +2}`, both sides changing in the same step.
**Steps:** For each step, call `IncrementalJoin.step(leftDelta, rightDelta)` and accumulate its
output Z-set; feed the same rows one at a time to `SymmetricHashJoin` and accumulate its emissions
as a Z-set. Compare after every step.
**Expected:** identical Z-sets at every one of the 500 steps. Report the first differing step with
both Z-sets printed.
**Vacuity:** Assert that at least 100 steps contained a non-empty delta on **both** sides
simultaneously — that is the only shape where the `ΔA⋈ΔB` term is exercised. A run where the sides
never change together tests nothing this case exists for.

### INCR-055 — A retraction whose partner has been evicted never withdraws its pair
**Intent:** Eviction is keyed on the watermark horizon, not on whether a pair was published. A
retraction arriving after its partner is gone finds nothing to match, emits nothing, and the pair
already in the view stands.
**Falsifier:** The pair is withdrawn.
**Setup:** H1, inner equi-join, match window 1 minute, output to a `ServedView` keyed on the join key.
**Steps:** t=0s feed left `L(o-1)` and right `R(o-1)` → pair emitted, committed, view holds it.
`advanceWatermark(5 minutes)` → both sides evicted (`evicted() == 2`). Then feed `L(o-1)` at `-1`
with event time 0s.
**Expected:** the retraction probes the right index, finds nothing, emits nothing; it is then
*stored* on the left at `-1` (INCR-036) until the next eviction. The view still holds the pair.
*B(M)*: the net input has no left row, so no pair. Permanent divergence of one row.
**Vacuity:** C3 — assert the pair is in the view before the retraction. C1 — assert the retraction
reached the operator (`rowsHeldLeft()` goes 0 → 1).

### INCR-056 — The time predicate is applied to retractions as well as inserts
**Intent:** `emit` checks `plan.matchesInTime(...)` before writing, for every weight. A retraction
outside the window is counted in `outsideWindow()` and emits nothing — which is correct only if the
original insert was also excluded.
**Falsifier:** An insert admitted by the predicate whose retraction is excluded, or the reverse.
**Setup:** H1, equi-join with `AND l.t BETWEEN r.t - INTERVAL '5' MINUTE AND r.t`.
**Steps:** Feed a pair inside the bound (`+1`, emitted) and its retraction (`-1`). Then a pair
outside the bound (`+1`, not emitted, `outsideWindow()` incremented) and its retraction.
**Expected:** inside: `+1` then `-1`, net 0, `outsideWindow()` unchanged. Outside: nothing emitted
either time, `outsideWindow()` goes `0 → 1 → 2`. The predicate is sign-blind, which is the required
property.
**Vacuity:** Assert `outsideWindow()` at each of the four steps. The final "nothing emitted" is also
what a join that dropped every retraction would report.

### INCR-057 — A NULL join key stores nothing and retracts nothing
**Intent:** `JoinSide.add` returns early on `!JoinKeys.isMatchable(...)` — "Rows whose key contains a
null are not stored at all: they can never match, so holding them is a leak with no possible
benefit." The retraction of such a row must be equally inert.
**Falsifier:** `rowsHeldLeft()` changes for a null-keyed row of either sign.
**Setup:** H1, equi-join, `order_id` nullable on the left.
**Steps:** Feed `(NULL, X)` at `+1`, then at `-1`. Then a right row with a NULL key, `+1` and `-1`.
**Expected:** `rowsHeldLeft()` and `rowsHeldRight()` stay at 0 throughout; `pairsEmitted() == 0`;
no exception. SQL agrees: NULL never equals NULL in an equi-join.
**Vacuity:** C2 — a non-null-keyed row in the same harness must move `rowsHeldLeft()` to 1.

### INCR-058 — A join under a chain of updates on both sides stays equal to a recomputation
**Intent:** The composed case, which is where per-operator errors stop cancelling.
**Falsifier:** The accumulated output Z-set differs from a recomputation over the net inputs.
**Setup:** H1, inner equi-join on `order_id`, both sides fed 300 changes, seed `20260909`: 40% pure
inserts, 40% updates (a `-1`/`+1` pair on one side), 20% pure retractions of rows previously fed.
Match window wide enough that nothing is evicted.
**Steps:** Accumulate every emission with its weight into a Z-set. At the end, compute the net left
and right relations and take their full relational join in plain Java.
**Expected:** the accumulated Z-set, consolidated, equals the plain join exactly — same pairs, all
weights `+1` where each net row is unique. Report the first differing pair.
**Vacuity:** Assert `rowsHeldLeft() + rowsHeldRight()` at the end equals the size of the net
relations. Equal outputs with a growing index is a leak this case would otherwise miss.

### INCR-059 — A lookup join passes the probe row's weight straight through
**Intent:** `LookupJoin` writes `writer.weight(row.weight())` — the dimension side is not a Z-set
participant, so a retraction of the probe must retract the enriched row.
**Falsifier:** The enriched output carries `+1` for a probe at `-1`.
**Setup:** H1, `LEFT JOIN dimension FOR SYSTEM_TIME AS OF a.event_time`.
**Steps:** Feed a probe row at `+1` (enriched row emitted at `+1`), then the same probe at `-1`.
**Expected:** the second emission carries weight `-1` with identical column values, so a view
tombstones the key and *B(M)* (empty net input) agrees.
**Vacuity:** C2 — assert the `+1` emission's values, so the `-1` is known to name the same row.

### INCR-060 — Join state restored from a checkpoint preserves weights and match flags
**Intent:** `JoinSide.writeTo/readFrom` carry `weightOf(entry)` and `matchedOf(entry)`. A restore
that loses the weight turns a weight-2 entry into a weight-1 one, and every subsequent retraction is
then off by one — silently, and only after a failure.
**Falsifier:** Post-restore `rowCount()` or `distinctRows()` differ from pre-checkpoint, or a
retraction that should cancel leaves a residue.
**Setup:** H1, equi-join. Left side loaded with: one row at `+1` that has matched, one at `+2` that
has not, one at `-1`.
**Steps:** `writeTo` to a byte array; construct a fresh join; `readFrom`; compare `rowsHeldLeft()`
and `rowCount()`. Then feed the `-2` retraction of the weight-2 row and assert it lands at `0` and
unlinks.
**Expected:** `distinctRows = 3` both sides of the restore; `rowCount = 1 + 2 - 1 = 2`. After the
`-2`: `distinctRows = 2`, `rowCount = 0`. The matched flag survives, so the matched row is not
null-padded at eviction (`unmatchedEmitted()` counts only the never-matched positive one).
**Vacuity:** Assert all three counts before the checkpoint and after. A restore into an empty join
also satisfies "no residue".

---

## §8 — Composition, the view, and drift

### INCR-061 — A chained view does not compound a per-operator error
**Intent:** `aChainOfViewsStaysCorrect` proves this for three linear operators in the algebra.
Do it across a real operator chain: window → aggregate → filter → project → view.
**Falsifier:** The chained result differs from the same predicate applied to *B(M)*.
**Setup:** H1, `SELECT user_id, total FROM (SELECT user_id, SUM(amount) AS total FROM
TABLE(TUMBLE(...)) GROUP BY user_id, window_start, window_end) WHERE total > 60`.
**Steps:** Feed D1 plus ρ3; advance; finish; commit; scan.
**Expected:** u1 `= 100` (passes `> 60`), u2 `= 50` (filtered out), u3 `= 7` in W4 (filtered out).
One row. *B(M)*: net input `{r1, r2, r4}` → u1 100, u2 50, u3 7 → same single row.
**Vacuity:** C2 — without ρ3, u1 is 300 and still passes, so assert the *value*, not the row count.

### INCR-062 — A filter is linear: applying it to a retraction needs no state
**Intent:** `Lift.linear` returns `(delta, state) -> query.evaluate(delta)`. The runtime's
`FilterOperator` must behave the same for a negative weight: pass it through unchanged if the
predicate holds, drop it if not.
**Falsifier:** A retraction is evaluated against the *stored* row rather than its own values.
**Setup:** H1, `SELECT user_id, amount FROM txn WHERE amount > 150`, view keyed on `user_id`,
bounded (`finish()`).
**Steps:** Feed r3 (`amount 200`, passes) at `+1`; then ρ3 at `-1`.
**Expected:** two rows reach the view — `+1 (u1,200)` then `-1 (u1,200)` — and the view ends empty.
Then feed r1 (`amount 100`, fails) at `+1` and ρ1 at `-1`: **neither** reaches the view;
downstream row count unchanged.
**Vacuity:** Assert the downstream count at each of the four feeds: 1, 2, 2, 2.

### INCR-063 — A projection that merges rows adds their weights
**Intent:** `ZSet.map`'s javadoc: "Weights of rows that map to the same output are added, which is
what makes projecting away a key column behave the way SQL expects rather than losing duplicates."
The runtime's `ProjectOperator` has no consolidation step; it copies `row.weight()` per row.
**Falsifier:** Two rows projecting to the same output emit one row of weight 2 (which would be the
algebra's behaviour) — or the runtime emits two rows and the downstream fails to add them.
**Setup:** H1, `SELECT user_id FROM txn` (projecting `amount` away), bounded, output to a counting
processor **and** to a `ServedView` keyed on `user_id`.
**Steps:** Feed r1 and r3 — both project to `(u1)`. Then ρ3.
**Expected:** downstream sees three separate rows: `+1 (u1)`, `+1 (u1)`, `-1 (u1)`. The algebra's
consolidated form is a single entry of weight `1 + 1 - 1 = 1`. The `ServedView`, which does not net
weights (INCR-029), ends with the key **removed** by the final `-1` — where the Z-set answer is one
`(u1)` still present. Record both.
**Vacuity:** Assert the three downstream emissions and the view's final state separately. They
disagree, and a case asserting only one of them will report a pass.

### INCR-064 — `SELECT DISTINCT` is refused, so the algebra's only non-linear lift is unreachable
**Intent:** `Lift.distinctIncremental` is the one non-linear operator the oracle checks, and the one
`IncrementalOracleTest` seeds a bug into. `SQL_SUPPORT.md` marks `SELECT DISTINCT` ❌ `PRV-2050`.
**Falsifier:** `SELECT DISTINCT` plans.
**Setup:** H1.
**Steps:** Plan `SELECT DISTINCT user_id FROM txn`.
**Expected:** `PRV-2050`, "it is a GROUP BY over an unbounded key space". Conclusion: the most
carefully tested lift in the algebra has no SQL surface, and `distinctIncremental` has no production
caller. Confirm by enumerating callers of `Lift.distinctIncremental` outside tests.
**Vacuity:** n/a — a refusal and a source enumeration.

### INCR-065 — A committed view equals the accumulated subscriber stream
**Intent:** Two independent renderings of the same answer. `ViewSink.commit` applies changes to the
view and hands the same batch to listeners. If they disagree, one of them is lying to somebody.
**Falsifier:** Folding the subscriber's `(values, weight)` stream into a map by key produces a
different map from `view.scan()`.
**Setup:** H1, windowed SUM over 500 generated rows (seed `20260909`) across 8 keys with 20%
retractions; subscriber attached before the first row.
**Steps:** After the final commit, fold the subscriber's stream: a `+1` sets the key, a `-1` removes
it (matching `ServedView.apply`'s own rule). Compare to `view.scan()`.
**Expected:** identical key sets and values.
**Vacuity:** Assert the subscriber received at least 100 changes and at least 10 with a negative
weight. A subscriber that received nothing folds to an empty map, which matches an empty view.

### INCR-066 — `ServedView` staleness is honest about an uncommitted retraction
**Intent:** `readCommitted` reports `staleness = appliedFrontier - committedFrontier`. A retraction
sitting in the overlay is precisely the case where a committed read is stale in a way that matters —
it is still returning a row the input has withdrawn.
**Falsifier:** `stalenessNanos()` is 0 while a retraction is pending.
**Setup:** H1 + `ServedView`.
**Steps:** `applyValues({"u1",300}, +1, 10s)`; `commit(10s)`. `applyValues({"u1",300}, -1, 20s)` —
no commit. Read with `Consistency.Consistent` and with `Consistency.Latest`.
**Expected:** Consistent → `found() == true`, values `[u1, 300]`, `frontier = 10s`,
`staleness = 20s - 10s = 10_000_000_000` nanos, `frontierComplete() == true`. Latest → `found() ==
false` (the overlay's tombstone wins), `frontier = appliedFrontier = 20s`, `staleness = 0`,
`frontierComplete() == false` (`pending` is non-empty). Both readings are correct and they disagree,
which is the design.
**Vacuity:** Assert all four fields of both results. `found()` alone is satisfied by a view that
never committed.

### INCR-067 — Retention eviction is not a retraction, and is silent to subscribers
**Intent:** `ServedView.evict` removes keys from `visible` and increments `evicted()` without
emitting anything. A subscriber maintaining its own copy will therefore diverge from the view — by
design, per `Retention`, but the divergence is the thing to measure.
**Falsifier:** An evicted key produces a `-1` on the subscription, or `evicted()` stays 0.
**Setup:** H1 + `ServedView` with `Retention.ofAge(Duration.ofSeconds(30))`, subscriber attached.
**Steps:** Apply and commit `(u1, 300)` at frontier 1s. Then apply and commit `(u2, 50)` at
frontier 100s, which moves the horizon to `100s - 30s = 70s`; `u1`'s `writtenAt` is 1s `< 70s`.
**Expected:** `evicted() == 1`; `view.get("u1").found() == false`; `size() == 1`; the subscriber
received `+1 [u1,300]` and `+1 [u2,50]` and **no** retraction. A subscriber folding that stream holds
two keys where the view holds one. Record the gap.
**Vacuity:** C3 — assert `u1` found after the first commit. Assert the subscriber's change count is
exactly 2, so "no retraction" is not "no messages at all".

### INCR-068 — The view ceiling is checked after eviction and refuses rather than dropping
**Intent:** `commit` runs `evict()` then throws `PRV-4022` if `visible.size() > maxKeys`. A
correctness area cares because the alternative — silently dropping keys — would make every
subsequent answer wrong with no error.
**Falsifier:** A commit that exceeds the ceiling succeeds, or drops keys silently.
**Setup:** H1 + `ServedView("v", schema, List.of(0), maxKeys = 100, Retention.forever())`.
**Steps:** Apply 100 distinct keys; commit. Apply the 101st; commit.
**Expected:** first commit succeeds, `size() == 100`. Second raises `PRV-4022` naming
`101` and `100` and containing "This view keeps everything". Note the throw happens **after**
`visible` has already been mutated and `committedFrontier` advanced, so the view is left holding 101
keys with the exception reported — assert `size()` after catching, and record whether the view is
usable afterwards.
**Vacuity:** Assert `size()` before and after. A refusal that also rolled back would give 100; a
refusal that did not gives 101, and the difference is the finding.

### INCR-069 — A frontier that goes backwards is refused
**Intent:** `commit` throws `IllegalArgumentException` on a regressing frontier, mirroring
`Frontier.advanceTo`'s refusal — "A frontier that could move backwards would let an operator
un-finalise a result it has already emitted."
**Falsifier:** A backwards commit succeeds, or throws a `PravahaException` with a PRV code (it
throws a bare `IllegalArgumentException`, which no client maps to an error code).
**Setup:** H1 + `ServedView`.
**Steps:** `commit(100s)`; then `commit(50s)`. Separately, `Frontier.at(100).advanceTo(50)`.
**Expected:** view: `IllegalArgumentException`, "frontier went backwards: 50 after 100". Frontier:
`IllegalArgumentException`, "a frontier cannot regress: at 100, asked for 50". Neither carries a
`PRV-` code; record that as an `ERRC` cross-reference. `commit(100s)` again — equal, not less — must
succeed.
**Vacuity:** The equal-frontier re-commit is the control: if it also threw, the case would be testing
`<=` rather than `<`.

### INCR-070 — Two views committed at the same frontier reconcile
**Intent:** The claim `Frontier`'s javadoc makes and calls "the property no Flink deployment offers":
reading several views as of one frontier reflects exactly the same prefix of the input. A retraction
that reaches one view and not the other breaks it.
**Falsifier:** `SUM` read from view A does not equal `SUM` recomputed from view B's rows, at the same
committed frontier.
**Setup:** H1, two registrations over the same `txn` stream committed from the same `ViewSink`
frontier: A = `SELECT user_id, SUM(amount) AS total FROM TABLE(TUMBLE(...)) GROUP BY user_id,
window_start, window_end`; B = the same with `COUNT(*) AS n` added.
**Steps:** Feed D1 and ρ3; advance; finish; commit both at the same frontier. Read both with
`Consistency.Consistent`.
**Expected:** both report `committedFrontier` equal; A's u1 total is 100 and B's u1 total is 100 with
`n = 1`; `100 / 1 = 100` matches A's AVG if a third view computes it. Any disagreement is a
frontier-alignment defect, reported with both frontiers.
**Vacuity:** Commit them at *different* frontiers in a second run — A at 20s, B at 15s — and assert
the reported `frontier` fields differ and the numbers may legitimately disagree. Without that, the
agreement in the first run could be coincidence.

---

## Coverage note

**70 cases, the budget, and the budget is right for the runtime — but this area found four structural
facts that change what the other areas should expect.** They are stated once here rather than
repeated in every case.

1. **Retractions barely have a way in.** One source plugin of five can emit a negative weight. The
   view read path stamps `+1` unconditionally. So the Z-set machinery — the architecture's central
   claim, ADR-013 — is exercised end-to-end only through a Delta table, and through nothing else a
   customer is likely to deploy first. `INGEST` and `STRM` should not assume a retraction is
   obtainable.
2. **Allowed lateness is zero and cannot be changed.** `DEFAULT_ALLOWED_LATENESS_NANOS = 0L` with no
   configuration key and no SQL clause, and the late-rejection guard uses the same comparison as the
   firing rule. The documented late-data correction — retract the old window result, emit the
   corrected one — is therefore dead through SQL, while `CONCEPTS.md` §4, `WindowedAggregate`'s
   javadoc and `USER_GUIDE.md` all describe it as the normal behaviour. `TIME` and `WIN` should
   expect `corrections() == 0` everywhere and treat any non-zero value as the surprise.
3. **A window key that nets to zero after publication is never withdrawn** (INCR-026). `emitWindow`
   retracts a key whose *values* changed and silently forgets a key that *disappeared*. This is the
   one place in the area where the engine can hold a row the input has withdrawn, indefinitely, with
   no counter recording it.
4. **`ServedView` is a last-write-wins map, not a Z-set** (INCR-028, INCR-029, INCR-063). It ignores
   accumulated weight, treats weight 0 as an insert, and deletes on a single `-1`. For the aggregates
   the engine currently ships this is indistinguishable from correct, because an aggregate emits one
   row per key. It stops being indistinguishable the moment a non-aggregating continuous query is
   served — `SELECT user_id FROM txn` keyed on `user_id` is already such a query.

**What a larger budget would buy.** The 16 types × 6 aggregate kinds × 3 operators × {insert,
retract, net-zero} matrix is 288 cells and this file writes 18 of them; the rest belong to `AGG` and
`TYPE` and should be written there with the weight dimension included, because round 1's FLOAT64
finding was exactly a missing cell of that shape. The generated cases (INCR-006, INCR-054, INCR-058,
INCR-065) are worth more per case than the enumerated ones and should be the first thing promoted
into the algebra module's jqwik suite, where they would run on every commit rather than once a round.
