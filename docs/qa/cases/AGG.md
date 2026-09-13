# AGG — five aggregate kinds × three operators × sixteen types × seven input shapes

**Area:** `AGG` · **IDs:** AGG-001 … AGG-110 · **Budget:** 110 · **Status:** authored, not executed.

The grid this area owns, stated once so every case below can be located in it:

| | |
|---|---|
| **Kinds** | `COUNT(*)`, `COUNT(col)`, `COUNT(DISTINCT col)`, `SUM`, `MIN`, `MAX` — and `AVG`, which is not an accumulator at all but `sums[i] / counts[i]` computed at emit |
| **Operators** | `GlobalAggregate` (no key, bounded by construction), `KeyedAggregate` (keyed, bounded *by the scan*, read path only), `WindowedAggregate` → `SlicedAggregateState` (keyed, bounded by the window) |
| **Types** | the 16 `TypeName` values: BOOLEAN, INT8, INT16, INT32, INT64, FLOAT32, FLOAT64, DECIMAL, DATE, TIME, TIMESTAMP_LTZ, STRING, BYTES, ARRAY, MAP, ROW |
| **Shapes** | no rows, one row, many rows, all NULL, some NULL, a retraction, a net-zero |

Surface under test: `pravaha-runtime/.../exec/GlobalAggregate.java`, `KeyedAggregate.java`,
`WindowedAggregate.java`; `pravaha-runtime/.../window/SlicedAggregateState.java`;
`pravaha-runtime/.../plan/AggregateOperator.java` and `WindowedAggregateOperator.java`;
`pravaha-sql/.../plan/PhysicalPlanBuilder.buildAggregate`, `refuseFloatingPointAggregate`, `kindOf`;
`pravaha-common/.../row/BinaryRowWriter.setLong` and `RowLayout.checkType`;
`QueryExecution.refuseUnpartitionedAggregate`. Documentation under test: `docs/SQL_SUPPORT.md`
§Aggregation (lines 105–167) and `docs/USER_GUIDE.md`.

---

## Read before running anything: nine facts that decide what each case can be run against

Established by reading the sources at the commit under test. Each is itself a case below, and each
is the reason a harness is specified rather than left to the executor.

1. **`GlobalAggregate::emit` and `KeyedAggregate::emit` are finishers.**
   `InterpretedPipeline.buildInput` registers them to run at end of input. A continuous
   registration over an endless stream never finishes, so an unwindowed aggregate registered as a
   continuous query accumulates for ever and publishes nothing — `RUNNING`, non-zero `rowsIn`, empty
   view. Every unwindowed case below therefore runs on a **bounded** input. (AGG-001)
2. **`COUNT(col)` counts NULLs in two of the three operators.** `GlobalAggregate.process` guards
   with `call.argumentOrdinal() < 0 || !row.isNull(...)`. `KeyedAggregate.Group.accumulate` has
   `case COUNT -> counts[i] += weight;` with no guard. `SlicedAggregateState.update` has
   `case COUNT -> accumulator.values[i] += weight;` with no guard and never consults
   `valueOrdinals` at all. (AGG-009 … AGG-032)
3. **`COUNT(DISTINCT)` is three different things in three operators.** `GlobalAggregate` throws
   `PRV-3020` from both `process` and `emit`. `KeyedAggregate` accumulates into a `HashSet` via
   `row.getString(call.argumentOrdinal())` — unconditionally, whatever the column's type.
   `WindowedAggregate` puts `row.getLong(ordinal)` into `scratch[i]` and `SlicedAggregateState`
   counts distinct **longs**. `SQL_SUPPORT.md` line 113 records one ✅ for all three.
4. **The floating-point refusal exempts COUNT and COUNT(DISTINCT).**
   `refuseFloatingPointAggregate` returns early for `Kind.COUNT` and `Kind.COUNT_DISTINCT`, so
   `COUNT(DISTINCT price)` over a FLOAT64 column passes the plan gate and reaches the two runtime
   paths in fact 3. It also returns early when `argument < 0 || argument >= fieldCount`.
5. **Narrow integers die at emit, not at plan.** Calcite types `SUM(INT32)` as INTEGER, so the
   aggregate's output field is INT32, and all three operators write through `writer.setLong`.
   `RowLayout.checkType` throws
   `field N ('EXPR$1') is INT32, not INT64 in schema <input>_aggregated` — a raw
   `IllegalArgumentException` with no `PRV-` code, naming an internal schema the user never wrote.
6. **DECIMAL has no aggregate refusal of its own.** `refuseFloatingPointAggregate` tests only
   FLOAT32/FLOAT64. Whatever refuses DECIMAL does so elsewhere (`ExpressionCompiler.refuseDecimalType`,
   `KeyedAggregate.read`/`writeKey`'s `default ->` arm, or Calcite), and which one fires — and with
   what code — is unestablished. (AGG-049 … AGG-051)
7. **AVG is `counts[index] == 0 ? 0 : sums[index] / counts[index]`** in all three unwindowed paths,
   and in `WindowedAggregate` it is mapped to `SlicedAggregateState.Kind.SUM` — so a *windowed* AVG
   emits the **sum**, never divided. (AGG-093 … AGG-098)
8. **A keyed aggregate on more than one lane is refused.** `QueryExecution.refuseUnpartitionedAggregate`
   throws `PRV-3020` when `laneCount() > 1 && containsKeyedAggregate(plan)`, and `containsKeyedAggregate`
   returns true for *every* `WindowedAggregateOperator`, keyed or not. So every windowed case below
   is single-lane. (AGG-007)
9. **Allowed lateness is hard-wired to zero** (`DEFAULT_ALLOWED_LATENESS_NANOS = 0L`), and
   `WindowedAggregate.process` rejects on `lastWindowEnd + 0 <= watermark` — the same condition
   that fired the window. Through SQL the retract-and-re-emit correction path is unreachable, so
   windowed retraction cases run in HA with a hand-driven watermark. (AGG-085 … AGG-088)

## Harnesses

| | What | Why it is needed |
|---|---|---|
| **HA** | In-process operator harness, the shape of `pravaha-it/.../ServedQueryTest`: `new PhysicalPlanBuilder().build(SqlPlanner.withStreams(schema()).plan(SQL))`, then `ServedView` + `ViewSink` + `InterpretedPipeline.compile(...)`, rows written by a `BinaryRowWriter` with `.weight(w)` and `.setNull(ordinal)` set per row, watermark driven by `execution.advanceWatermark(t)`. | The only way to inject a weight other than `+1`, a NULL in a chosen column, or a watermark at a chosen instant. |
| **HB** | `pravaha run --sql S --stream txn --schema T --in f.csv --out-schema U --out o.csv`. One stream, bounded, one lane, no watermark generator — `WindowedAggregate.finish()` fires every open window at `highestEventTime + size + slide`. | The cheapest shipped surface on which an unwindowed aggregate emits at all (fact 1), and the one a user actually types. |
| **HC** | A node with `pravaha.streams.txn.schema` and `pravaha.sources.txn` bound to a `feedfile`, then `pravaha register --name v --sql S --keys K` and `pravaha query --sql "SELECT * FROM v"`. | The only surface for the keyed-over-a-view path (`KeyedAggregate`), which requires a materialised view to read. |
| **HD** | Plan-only: `pravaha explain --sql S --schema T`, or `SqlPlanner.plan(S)` directly. No data, no server. | Every refusal case. A refusal that needs a running node to observe is a refusal nobody has tested. |

## Standing fixtures

```
stream txn      : txn_id INT64, user_id STRING, amount INT64 NULL, tier STRING NULL,
                  event_time TIMESTAMP     -- pravaha.streams.txn.event-time: event_time
stream narrow   : id INT64, b INT8, s INT16, i INT32, event_time TIMESTAMP
stream floaty   : id INT64, f32 FLOAT32, f64 FLOAT64, dec DECIMAL(18,2), event_time TIMESTAMP
stream temporal : id INT64, d DATE, t TIME, ts TIMESTAMP, flag BOOLEAN, label STRING,
                  blob BYTES, event_time TIMESTAMP
SECOND = 1_000_000_000 nanos.   W1 = TUMBLE 10 SECOND over [0s, 10s).   W4 = [30s, 40s).
```

Canonical input **D1** on `txn`, in arrival order, every row weight `+1`:

| ref | txn_id | user_id | amount | tier | event_time | window |
|---|---|---|---|---|---|---|
| r1 | 1 | u1 | 100 | gold | 1s | W1 |
| r2 | 2 | u2 | 50 | *NULL* | 2s | W1 |
| r3 | 3 | u1 | 200 | gold | 3s | W1 |
| r4 | 4 | u1 | *NULL* | silver | 4s | W1 |
| r5 | 5 | u3 | 7 | silver | 30s | W4 |

**Hand-computed truth for D1.** Every `Expected` below quotes from this table; nothing says "the sum".

*Global, all five rows:*
`COUNT(*) = 5` · `COUNT(amount) = 4` (r4 excluded) · `COUNT(DISTINCT user_id) = |{u1,u2,u3}| = 3` ·
`SUM(amount) = 100 + 50 + 200 + 7 = 357` · `MIN(amount) = 7` · `MAX(amount) = 200` ·
`AVG(amount) = 357 / 4 = 89` (exact 89.25; truncated).

*W1 as one group (`GROUP BY window_start, window_end`), rows r1–r4:*
`COUNT(*) = 4` · `COUNT(amount) = 3` · `COUNT(DISTINCT user_id) = |{u1,u2}| = 2` ·
`COUNT(DISTINCT tier) = |{gold, silver}| = 2` (NULL not counted) ·
`SUM(amount) = 100 + 50 + 200 = 350` · `MIN = 50` · `MAX = 200` ·
`AVG = 350 / 3 = 116` (exact 116.666…; truncated).

*W1 keyed by `user_id`:*

| user_id | rows | COUNT(*) | COUNT(amount) | SUM | AVG | MIN | MAX |
|---|---|---|---|---|---|---|---|
| u1 | r1, r3, r4 | 3 | **2** | 100 + 200 = 300 | 300 / 2 = 150 | 100 | 200 |
| u2 | r2 | 1 | 1 | 50 | 50 / 1 = 50 | 50 | 50 |

*W4 keyed by `user_id`:* u3 → `COUNT(*) = 1`, `SUM = 7`, `AVG = 7`, `MIN = MAX = 7`.

**u1 in W1 is the whole area's tell.** `COUNT(amount) = 2` and `SUM = 300` are consistent:
`300 / 2 = 150 = AVG`. A row reporting `COUNT = 3` alongside `SUM = 300` and `AVG = 150` is
arithmetically self-contradictory — `300 / 3 = 100 ≠ 150` — and needs no external oracle to be
recognised as wrong.

Retractions used throughout: **ρ3** = r3 at weight `-1`; **ρ1** = r1 at weight `-1`;
**ρ5** = r5 at weight `-1`.

## Vacuity kit

Run in the *same process* as every stateful case. A case that cannot show all three is reported
`INCONCLUSIVE`, not `PASS`.

- **V1 — the rows arrived.** `pipeline.rowsIn()` (HA), `ok N in` (HB) or `pravaha queries` ROWS IN
  (HC) equals the number of rows fed, NULL-bearing and retracting rows included. An aggregate over
  an input that never arrived returns 0 for COUNT and 0 for SUM, which is indistinguishable from an
  aggregate that ran and found nothing.
- **V2 — the aggregate ran.** A control query differing only in the aggregate must return a
  different number in the same run: `COUNT(*)` alongside `COUNT(amount)`, `SUM` alongside `MIN`.
  Two identical numbers from two different functions is the signature of a stubbed operator.
- **V3 — the discriminating row is present.** Where a case turns on NULL, on a retraction, or on a
  second distinct value, assert that row's presence independently — by a `COUNT(*)` in the same
  result row, or by reading the view before the retraction. "The NULL was excluded" passes trivially
  if the NULL row never arrived.

---

## §0 — What can be observed at all (AGG-001 … AGG-008)

### AGG-001 — An unwindowed aggregate registered continuously emits nothing, for ever
**Intent:** Decide whether §1–§6's unwindowed cases can be run on the continuous path at all.
`GlobalAggregate::emit` is registered as a *finisher*; an endless stream never finishes.
**Falsifier:** `SELECT COUNT(*) AS n FROM txn` registered against an endless source returns a row.
**Setup:** HC. `txn` bound to a feedfile holding D1's five rows; the feed does not close.
**Steps:** `pravaha register --name v_global --sql "SELECT COUNT(*) AS n FROM txn" --keys 0`; wait
for `pravaha queries` to show ROWS IN 5; `pravaha query --sql "SELECT * FROM v_global"`.
**Expected:** ROWS IN `5`, state `RUNNING`, view returns **0 rows**. The batch answer is one row,
`n = 5`. Record also whether registration is refused at plan time; it is not expected to be, because
`buildAggregate` admits a global aggregate as "bounded by construction".
**Vacuity:** V1 — ROWS IN must be exactly 5. An empty view with ROWS IN 0 is an ingest failure and
says nothing about the aggregate.

### AGG-002 — The same aggregate over a bounded input does emit
**Intent:** Establish HB as a working surface before every unwindowed case leans on it.
**Falsifier:** `pravaha run` over a five-row file prints `0 out` for `SELECT COUNT(*)`.
**Setup:** HB. `d1.csv` holding D1's five rows; `--schema "txn_id:INT64,user_id:STRING,amount:INT64,tier:STRING,event_time:TIMESTAMP"`.
**Steps:** `pravaha run --sql "SELECT COUNT(*) AS n FROM txn" --stream txn --schema <T> --in d1.csv --out-schema "n:INT64" --out o.csv`.
**Expected:** `ok 5 in, 1 out`; `o.csv` holds the single value `5`.
**Vacuity:** V1 — `5 in`. V2 — the same run with `COUNT(amount)` must print `4`, not `5` (AGG-009).
If both print 5 the aggregate is not reading the column and AGG-009 is the reason.

### AGG-003 — A windowed aggregate fires on end of input with no watermark generator
**Intent:** `pravaha run` installs no watermark clock; `WindowedAggregate.finish()` advances to
`highestEventTime + sizeNanos + slideNanos`. Establish that both W1 and W4 fire, because every
windowed case in this file depends on it.
**Falsifier:** Fewer than two output rows, or a window whose numbers are partial.
**Setup:** HB, D1.
**Steps:** `pravaha run` with `SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS total
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start,
window_end`.
**Expected:** `ok 5 in, 2 out`. W1 → `n = 4`, `total = 100 + 50 + 200 = 350`. W4 → `n = 1`,
`total = 7`. `finish()` advances to `30s + 10s + 10s = 50s`, which is past W4's end of 40s.
**Vacuity:** V1 — `5 in`. Assert **two** rows: one row of `n = 5, total = 357` would mean the window
boundaries are not in the key and the "unbounded case wearing a window's clothes" reached execution.

### AGG-004 — `KeyedAggregate` is reachable only over a view read
**Intent:** Pin the one path on which a keyed unwindowed aggregate executes, since §1's keyed column
has nowhere else to run. `buildAggregate` refuses `!groupKeys.isEmpty() && !boundedInput` with
`PRV-2050`.
**Falsifier:** `SELECT user_id, COUNT(*) FROM txn GROUP BY user_id` plans against a stream.
**Setup:** HD, then HC.
**Steps:** (a) `pravaha explain --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"`.
(b) Register `v_txn` as `SELECT * FROM txn`, then
`pravaha query --sql "SELECT user_id, COUNT(*) AS n FROM v_txn GROUP BY user_id"`.
**Expected:** (a) `PRV-2050`, message naming the key as `user_id` — not `GROUP BY [1]`. (b) succeeds,
returning u1 → 3, u2 → 1, u3 → 1 (five rows of D1: r1, r3, r4 are u1).
**Vacuity:** V1 — the view must hold 5 rows before the grouped read; a view of 0 rows returns 0
groups and proves nothing.

### AGG-005 — The `PRV-2050` refusal names columns, not ordinals
**Intent:** `namesOf` exists for exactly this, and the wave gate asks for it. A refusal naming
`[1]` costs the reader a schema lookup and they will get it wrong at least once.
**Falsifier:** The message contains `[1]`, `[1, 3]`, or `column 1`.
**Setup:** HD.
**Steps:** `pravaha explain` for `GROUP BY user_id`, then for `GROUP BY user_id, tier`.
**Expected:** `GROUP BY user_id has no bound on its key space…` and `GROUP BY user_id, tier has no
bound…`. Both suggest `GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id[, tier]`.
**Vacuity:** n/a — a message assertion.

### AGG-006 — A windowed GROUP BY without the boundaries is refused, and says why
**Intent:** The "unbounded case wearing a window's clothes" check in `buildAggregate`.
**Falsifier:** The query plans, producing a `WindowedAggregateOperator` spanning every window.
**Setup:** HD.
**Steps:** `pravaha explain --sql "SELECT user_id, COUNT(*) FROM TABLE(TUMBLE(TABLE txn,
DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY user_id"`.
**Expected:** `PRV-2050`, message asking for `window_start` and `window_end` in the GROUP BY.
**Vacuity:** n/a.

### AGG-007 — Any windowed aggregate on more than one lane is refused, keyed or not
**Intent:** `containsKeyedAggregate` returns `true` for every `WindowedAggregateOperator`, including
one grouped only by the window boundaries. The refusal is therefore wider than its own message
("this query groups by a key") describes, and the message will mislead.
**Falsifier:** `QueryExecution.start(plan, 4, …)` accepts a plan whose only aggregate is
`GROUP BY window_start, window_end`.
**Setup:** HA. Plan from AGG-003's SQL — no data key at all.
**Steps:** `QueryExecution.start(plan, 4, config(), MemoryAccess.best(), …)`.
**Expected:** `PRV-3020`, message beginning "this query groups by a key and runs on 4 lanes". Record
that the query groups by no data key, so the message is inaccurate for the case that triggers it.
Then confirm `laneCount() == 1` is accepted and produces AGG-003's two rows.
**Vacuity:** V2 — the 1-lane run must return the two correct rows, proving the plan is executable and
the refusal is about lanes.

### AGG-008 — A global aggregate on four lanes is *not* refused, and is wrong
**Intent:** The mirror of AGG-007. `containsKeyedAggregate` requires
`!aggregate.groupKeyOrdinals().isEmpty()`, so `SELECT COUNT(*) FROM txn` passes the guard. Four lanes
each hold their own `GlobalAggregate` and each emits at finish.
**Falsifier:** One output row of `n = 5`.
**Setup:** HA, plan from AGG-002's SQL, `QueryExecution.start(plan, 4, …)`, unpartitioned pumps.
**Steps:** Feed D1's five rows round-robin across the four lanes; close; collect.
**Expected:** **Four** output rows whose `n` values sum to 5 — e.g. `2, 1, 1, 1` — rather than one
row of 5. Record the actual distribution. A consumer taking "the" row gets a fraction of the answer
with no error. Compare against the 1-lane run, which returns one row of `5`.
**Vacuity:** V1 — 5 rows in across all lanes. V2 — the 1-lane control returns exactly one row of 5.

---

## §1 — `COUNT(*)` vs `COUNT(col)` vs `COUNT(DISTINCT col)` × three operators (AGG-009 … AGG-032)

The 3 × 3 core of the area, plus the null/empty/distinct-cardinality neighbours each one implies.

### AGG-009 — Global `COUNT(amount)` excludes the NULL row
**Intent:** The fixed branch, re-established on this build so that AGG-013 and AGG-017 are read as
regressions in the two unfixed operators rather than as a difference of opinion.
**Falsifier:** `COUNT(amount)` returns 5.
**Setup:** HB, D1.
**Steps:** `pravaha run --sql "SELECT COUNT(*) AS rows_all, COUNT(amount) AS rows_amount FROM txn" …
--out-schema "rows_all:INT64,rows_amount:INT64"`.
**Expected:** `rows_all = 5`, `rows_amount = 4`. r4's amount is NULL; `5 - 1 = 4`.
**Vacuity:** V2 — the two columns must differ. V3 — a third column `SUM(amount) = 357` confirms four
non-null values were summed, so `rows_amount = 4` is consistent with it.

### AGG-010 — Global `COUNT(tier)` excludes a NULL in a STRING column
**Intent:** The guard is `row.isNull(ordinal)` and is type-independent, but "type-independent" is a
claim about code, not an observation. STRING is a variable-width field with a different null
representation in the layout than a fixed-width one.
**Falsifier:** `COUNT(tier)` returns 5.
**Setup:** HB, D1.
**Steps:** `SELECT COUNT(*) AS a, COUNT(tier) AS b FROM txn`.
**Expected:** `a = 5`, `b = 4`. r2's tier is NULL; `5 - 1 = 4`.
**Vacuity:** V2 — a ≠ b. V3 — `COUNT(amount) = 4` in the same row, excluding a *different* row (r4),
proves the two nulls are independent and neither column is simply returning 4 by accident.

### AGG-011 — Global `COUNT(*)` over a column list that is entirely NULL
**Intent:** `COUNT(*)` has `argumentOrdinal() == -1`, taking the `< 0` arm. It must count rows
whatever the data holds.
**Falsifier:** `COUNT(*)` returns anything but 5.
**Setup:** HB. Input **D2**: D1 with `amount` and `tier` NULL in every row.
**Steps:** `SELECT COUNT(*) AS a, COUNT(amount) AS b, COUNT(tier) AS c FROM txn`.
**Expected:** `a = 5`, `b = 0`, `c = 0`.
**Vacuity:** V1 — `5 in`. A file that failed to parse gives `a = 0` and `b = 0`, which would also
"pass" a test asserting only that b is 0.

### AGG-012 — Global `COUNT(DISTINCT user_id)` is refused, from both `process` and `emit`
**Intent:** `GlobalAggregate` throws `PRV-3020` in `process` *and* again in `emit` — two throw sites
with two different messages. Establish which one is reached and that the message is actionable.
`SQL_SUPPORT.md` line 113 records `COUNT(DISTINCT x)` as ✅ with no qualification.
**Falsifier:** The query returns a number.
**Setup:** HB, D1.
**Steps:** `pravaha run --sql "SELECT COUNT(DISTINCT user_id) AS n FROM txn" …`.
**Expected:** `PRV-3020`. The `process` site fires first, on r1, with "COUNT(DISTINCT ...) over an
unwindowed stream is unbounded state: one entry per distinct value, kept forever. Put it in a
window." Record the exit code and whether the failure is reported at all — FINDINGS Q-6 has this
hanging for five minutes rather than failing. The `emit` message is then dead code; record that too.
**Vacuity:** V1 — the run must read rows before failing; a failure at plan time is a different
finding and must be recorded as such.

### AGG-013 — Keyed `COUNT(amount)` counts the NULL, contradicting its own SUM
**Intent:** The central defect of this area. `KeyedAggregate.Group.accumulate` has
`case COUNT -> counts[i] += weight;` with no null guard, while the `SUM` branch beside it has one.
The emitted row is internally inconsistent and no external oracle is needed to see it.
**Falsifier:** u1's row reports `n = 2`.
**Setup:** HC. `v_txn` registered as `SELECT * FROM txn`, fed D1, all 5 rows committed.
**Steps:** `pravaha query --sql "SELECT user_id, COUNT(amount) AS n, SUM(amount) AS total,
AVG(amount) AS mean FROM v_txn GROUP BY user_id"`.
**Expected (correct):** u1 → `n = 2`, `total = 300`, `mean = 150`.
**Expected (this build):** u1 → `n = 3`, `total = 100 + 200 = 300`, `mean = 300 / 2 = 150`.
`AVG` keeps its own `counts[]` slot, guarded, so it stays right while `COUNT` does not:
`300 / 3 = 100 ≠ 150`. u2 → `n = 1`, `total = 50`, `mean = 50` (no NULL, so unaffected).
u3 → `n = 1`, `total = 7`, `mean = 7`.
**Vacuity:** V3 — assert `COUNT(*) = 3` for u1 in the same row. `n = 3` is only a defect if
`COUNT(*)` is also 3 *and* r4 is present with a NULL amount; read the view directly to confirm r4 is
there with `amount IS NULL`. V1 — the view holds 5 rows.

### AGG-014 — Keyed `COUNT(tier)` counts the NULL
**Intent:** AGG-013 over a STRING column, grouped so the NULL lands in a group with a non-null
sibling. Confirms the defect is in the COUNT branch and not in fixed-width null handling.
**Falsifier:** u2's `COUNT(tier)` returns 0.
**Setup:** HC, D1, `v_txn`.
**Steps:** `SELECT user_id, COUNT(*) AS a, COUNT(tier) AS b FROM v_txn GROUP BY user_id`.
**Expected (correct):** u2 → `a = 1`, `b = 0` (r2's tier is NULL). u1 → `a = 3`, `b = 3`.
**Expected (this build):** u2 → `a = 1`, `b = 1`.
**Vacuity:** V2 — u1's `a` and `b` are both 3 legitimately; u2 is the discriminating group and the
case must report u2's numbers, not u1's.

### AGG-015 — Keyed `COUNT(col)` where every value in one group is NULL
**Intent:** The extreme of AGG-013: a group whose column is entirely NULL should report 0 and will
report its row count. This is the shape a dashboard divides by.
**Falsifier:** The all-NULL group reports 0.
**Setup:** HC. Input **D3**: D1 plus r6 (`txn_id 6, user_id u4, amount NULL, tier NULL, 5s`) and r7
(`7, u4, NULL, NULL, 6s`).
**Steps:** `SELECT user_id, COUNT(amount) AS n, SUM(amount) AS total FROM v_txn GROUP BY user_id`.
**Expected (correct):** u4 → `n = 0`, `total` = NULL in SQL. **Expected (this build):** u4 → `n = 2`,
`total = 0` — a group reporting two values summing to zero where it has no values at all. Record
both halves: the COUNT defect and the SUM-of-no-rows-is-0 disagreement (AGG-071).
**Vacuity:** V3 — assert `COUNT(*) = 2` for u4 and that both rows are present in `v_txn`.

### AGG-016 — Keyed `COUNT(*)` is unaffected
**Intent:** Bound the defect. `argumentOrdinal() == -1` for `COUNT(*)`, but `KeyedAggregate` never
looks at the ordinal, so `COUNT(*)` is right by accident rather than by check. Worth stating because
a fix that adds the guard must not change this.
**Falsifier:** `COUNT(*)` per group differs from the row counts in D1.
**Setup:** HC, D1, `v_txn`.
**Steps:** `SELECT user_id, COUNT(*) AS n FROM v_txn GROUP BY user_id`.
**Expected:** u1 → 3, u2 → 1, u3 → 1. `3 + 1 + 1 = 5`.
**Vacuity:** V1 — the three group counts must sum to the view's row count.

### AGG-017 — Windowed `COUNT(amount)` counts the NULL
**Intent:** The third operator. `SlicedAggregateState.update` does
`case COUNT -> accumulator.values[i] += weight;` and never consults `valueOrdinals` — so the
argument is not merely unguarded, it is never read.
**Falsifier:** u1's W1 row reports `n = 2`.
**Setup:** HB, D1.
**Steps:** `pravaha run --sql "SELECT window_start, window_end, user_id, COUNT(amount) AS n,
SUM(amount) AS total, AVG(amount) AS mean FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time),
INTERVAL '10' SECOND)) GROUP BY window_start, window_end, user_id" …`.
**Expected (correct):** W1/u1 → `n = 2`, `total = 300`, `mean = 150`.
**Expected (this build):** W1/u1 → `n = 3`, `total = 300`. `mean` is **also** 300, not 150, because
`WindowedAggregate` maps `AVG` to `SlicedAggregateState.Kind.SUM` and nothing divides (AGG-096). So
the windowed row is wrong in two independent ways and only the COUNT one is in FINDINGS.
W1/u2 → `n = 1`, `total = 50`. W4/u3 → `n = 1`, `total = 7`.
**Vacuity:** V3 — `COUNT(*) = 3` for W1/u1 in the same row, and r4 present with a NULL amount. V1 —
`5 in`, `3 out` (W1/u1, W1/u2, W4/u3).

### AGG-018 — Windowed `COUNT(tier)` counts the NULL
**Intent:** AGG-017 over STRING, and over a group where the NULL is the only row.
**Falsifier:** W1/u2 reports `COUNT(tier) = 0`.
**Setup:** HB, D1.
**Steps:** As AGG-017 with `COUNT(tier) AS b` and `COUNT(*) AS a`.
**Expected (correct):** W1/u2 → `a = 1`, `b = 0`. **Expected (this build):** `a = 1`, `b = 1`.
**Vacuity:** V2 — W1/u1 must show `a = 3, b = 3` in the same run, so the two columns are known to be
computed separately.

### AGG-019 — Windowed `COUNT(*)` against hand-counted rows per window
**Intent:** The control that makes AGG-017 and AGG-018 legible, and a check that slices combine —
`SlicedAggregateState.fire` merges every slice of the window, and a bug there gives partial counts
that look plausible.
**Falsifier:** W1 reports anything but 4 for the whole window.
**Setup:** HB, D1.
**Steps:** `SELECT window_start, window_end, COUNT(*) AS n FROM TABLE(TUMBLE(…10 SECOND…))
GROUP BY window_start, window_end`.
**Expected:** two rows. W1 `[0s, 10s)` → `n = 4` (r1, r2, r3, r4). W4 `[30s, 40s)` → `n = 1` (r5).
`4 + 1 = 5 = rows in`.
**Vacuity:** V1 — the per-window counts must sum to `rows in`. Two rows of `n = 2` and `n = 2` would
sum to 4 and reveal a lost row; one row of `n = 5` would reveal the boundaries missing from the key.

### AGG-020 — Windowed `COUNT(DISTINCT user_id)` over a STRING column returns 1
**Intent:** The shape `SQL_SUPPORT.md` §"Should a continuous query aggregate at all?" recommends as
*the* bounded alternative to the refused unwindowed form — "the value is in maintaining `SUM`,
`COUNT` and `COUNT(DISTINCT)` over a window". `WindowedAggregate.process` fills
`scratch[i] = ordinal < 0 || row.isNull(ordinal) ? 0 : row.getLong(ordinal)`, so what reaches
`SlicedAggregateState`'s distinct map is a raw 8-byte read of a variable-width field's
`(offset, length)` slot, not the string.
**Falsifier:** W1 reports `COUNT(DISTINCT user_id) = 2`.
**Setup:** HB, D1. All four W1 user_ids are 2 characters (`u1`, `u2`), so every row's slot holds the
same offset and the same length.
**Steps:** `SELECT window_start, window_end, COUNT(*) AS n, COUNT(DISTINCT user_id) AS d
FROM TABLE(TUMBLE(…10 SECOND…)) GROUP BY window_start, window_end`.
**Expected (correct):** W1 → `n = 4`, `d = |{u1, u2}| = 2`.
**Expected (this build):** W1 → `n = 4`, `d = 1`. Every row hashes to one distinct long, so the map
holds one entry. W4 → `n = 1`, `d = 1` (correct by coincidence, one row).
**Vacuity:** V2 — `n = 4` in the same row proves four rows reached the accumulator, so `d = 1` is not
"one row arrived". V3 — the same query with `COUNT(DISTINCT tier)` must also return 1 where the truth
is 2 (`gold`, `silver` — both 4 and 6 characters, so this one additionally tests whether differing
lengths change the answer; record it separately).

### AGG-021 — Windowed `COUNT(DISTINCT)` over strings of differing lengths
**Intent:** Isolate the mechanism in AGG-020. If the slot read is `(offset, length)`, strings of
different lengths give different longs and the count becomes "distinct *lengths*", not 1 and not the
truth. Which of the two it is decides how the defect will be described and fixed.
**Falsifier:** `d` equals the true distinct count, 3.
**Setup:** HB. Input **D4**, all in W1: `(1, a, 10, 1s)`, `(2, bb, 20, 2s)`, `(3, ccc, 30, 3s)`,
`(4, a, 40, 4s)` — user_ids of length 1, 2, 3, 1. True distinct = `|{a, bb, ccc}| = 3`.
**Steps:** `SELECT window_start, COUNT(*) AS n, COUNT(DISTINCT user_id) AS d FROM TABLE(TUMBLE(…))
GROUP BY window_start, window_end`.
**Expected:** `n = 4`. `d` is **3** only if the slot happens to encode length distinctly; record the
observed value. If `d = 3` here and `d = 1` in AGG-020, the count is of distinct lengths, and a data
set with three 4-character user ids will return 1. Add that confirmation: input **D5** with user_ids
`alph`, `beta`, `gama` (all 4 characters, true distinct 3) must then return `d = 1`.
**Vacuity:** V2 — `n = 4` in every variant. V3 — read the view/output to confirm the four distinct
strings really were fed.

### AGG-022 — Windowed `COUNT(DISTINCT)` over an INT64 column
**Intent:** The one column type for which `row.getLong` is the right read. If this is correct, the
defect is confined to non-INT64 arguments and the fix is a typed read, not a redesign.
**Falsifier:** `d` differs from the hand-counted distinct value count.
**Setup:** HB. Input **D6**, all in W1: amounts `100, 50, 100, 200` at 1s…4s.
**Steps:** `SELECT window_start, COUNT(*) AS n, COUNT(DISTINCT amount) AS d FROM TABLE(TUMBLE(…))
GROUP BY window_start, window_end`.
**Expected:** `n = 4`, `d = |{100, 50, 200}| = 3`.
**Vacuity:** V2 — `n = 4 ≠ d = 3`, so the two columns are demonstrably different computations.

### AGG-023 — Windowed `COUNT(DISTINCT)` and NULL
**Intent:** `SQL_SUPPORT.md` line 163 states "`COUNT(DISTINCT x)` does not count NULL". In
`WindowedAggregate.process` a NULL becomes `scratch[i] = 0`, which is fed to the distinct map as the
value zero — indistinguishable from a real zero.
**Falsifier:** `d` excludes the NULL rows.
**Setup:** HB. Input **D7**, all in W1: amounts `0, NULL, 5, NULL` at 1s…4s.
**Steps:** `SELECT window_start, COUNT(*) AS n, COUNT(DISTINCT amount) AS d FROM TABLE(TUMBLE(…))
GROUP BY window_start, window_end`.
**Expected (correct):** `n = 4`, `d = |{0, 5}| = 2` — the two NULLs excluded, the literal 0 counted.
**Expected (this build):** `d = 2` as well, but for the wrong reason: `{0 (from the real zero), 0
(from both NULLs), 5}` collapses to `{0, 5}`. Discriminate with **D8**: amounts `NULL, 5` only —
true `d = 1`, this build gives `|{0, 5}| = 2`.
**Vacuity:** V2/V3 — run both D7 and D8; a case that runs only D7 cannot tell the two explanations
apart and must be reported `INCONCLUSIVE`.

### AGG-024 — Keyed `COUNT(DISTINCT)` over a STRING column
**Intent:** The one `COUNT(DISTINCT)` path written with a typed read
(`row.getString(call.argumentOrdinal())`), on the read path `SQL_SUPPORT.md` line 159 says supports
it. FINDINGS Q-6 records it as "refused over a view", which contradicts the source; establish which.
**Falsifier:** Either the result differs from the hand count, or the query is refused.
**Setup:** HC, D1, `v_txn`.
**Steps:** `pravaha query --sql "SELECT tier, COUNT(DISTINCT user_id) AS d, COUNT(*) AS n
FROM v_txn GROUP BY tier"`.
**Expected:** three groups. `gold` → rows r1, r3 → `n = 2`, `d = |{u1}| = 1`. `silver` → r4, r5 →
`n = 2`, `d = |{u1, u3}| = 2`. NULL → r2 → `n = 1`, `d = |{u2}| = 1`. If instead refused, record the
code and the message verbatim and which layer refused.
**Vacuity:** V2 — `silver`'s `d = 2 ≠ n = 2` is not discriminating; use `gold` where `d = 1 ≠ n = 2`.
V1 — five rows in the view.

### AGG-025 — Keyed `COUNT(DISTINCT)` over an INT64 column calls `getString` on it
**Intent:** `KeyedAggregate` reads *every* `COUNT_DISTINCT` argument with `row.getString(...)`,
whatever the schema says. Over an INT64 column that is a variable-width read of a fixed-width field.
This is the neighbour AGG-024 implies and nobody has written down.
**Falsifier:** `COUNT(DISTINCT amount)` returns the hand-counted 4.
**Setup:** HC, D1, `v_txn`.
**Steps:** `pravaha query --sql "SELECT COUNT(DISTINCT amount) AS d FROM v_txn GROUP BY tier"`.
**Expected (correct):** `gold` → `|{100, 200}| = 2`; `silver` → `|{7}| = 1` (r4's amount is NULL and
is excluded); NULL tier → `|{50}| = 1`. Record what actually happens: an exception, a garbage count,
or a correct answer. A thrown `IllegalArgumentException` or `IndexOutOfBoundsException` naming a
layout offset is the expected shape, and it will carry no `PRV-` code.
**Vacuity:** V1 — five rows in the view; V3 — `COUNT(DISTINCT tier)` in the same query must succeed,
proving the failure is about the *type* of the argument.

### AGG-026 — Keyed `COUNT(DISTINCT)` over BOOLEAN, DATE and TIMESTAMP
**Intent:** Three more non-STRING arguments through the same `getString` read. Enumerated rather
than gestured at, because AGG-025 establishes only that INT64 is affected.
**Falsifier:** Any of the three returns the hand-counted value.
**Setup:** HC. `temporal` fed **D9**: `(1, 2026-01-01, 09:00:00, 1s, true, x, …)`,
`(2, 2026-01-01, 10:00:00, 2s, false, y, …)`, `(3, 2026-01-02, 09:00:00, 3s, true, x, …)`.
Registered as `v_temporal`.
**Steps:** Three reads: `SELECT COUNT(DISTINCT flag) …`, `… COUNT(DISTINCT d) …`,
`… COUNT(DISTINCT ts) …`, each `GROUP BY label`.
**Expected (correct):** grouped by `label`: `x` → flag `|{true}| = 1`, d `|{01-01, 01-02}| = 2`,
ts `|{1s, 3s}| = 2`; `y` → 1, 1, 1. Record the observed behaviour per type in a three-row table.
**Vacuity:** V2 — `COUNT(DISTINCT label)` in the same query is the STRING control and must be right.

### AGG-027 — Keyed `COUNT(DISTINCT)` and NULL
**Intent:** The one documented NULL rule for DISTINCT (`SQL_SUPPORT.md` line 163), on the one
operator that implements DISTINCT with a guard (`!row.isNull(call.argumentOrdinal())`).
**Falsifier:** A NULL appears as a distinct value.
**Setup:** HC, D1, `v_txn`.
**Steps:** `SELECT COUNT(*) AS n, COUNT(DISTINCT tier) AS d FROM v_txn`.
**Expected:** This is a *global* aggregate over a bounded read, so it goes to `GlobalAggregate`, not
`KeyedAggregate` — and `GlobalAggregate` refuses `COUNT_DISTINCT` outright (AGG-012). Record that the
NULL rule for DISTINCT is therefore only observable with a GROUP BY: rerun as
`SELECT user_id, COUNT(DISTINCT tier) AS d FROM v_txn GROUP BY user_id` and expect u1 →
`|{gold, silver}| = 2` (r1, r3 gold; r4 silver), u2 → `|{}| = 0` (r2's tier is NULL),
u3 → `|{silver}| = 1`.
**Vacuity:** V3 — u2 must be present with `COUNT(*) = 1`; a group that produced *no* row would also
show no `d = 0`.

### AGG-028 — Keyed `COUNT(DISTINCT)` at a cardinality that matters
**Intent:** `distincts.get(i)` is an unbounded `HashSet` per group per call, described as "bounded by
the scan, exactly like the group map itself". `maxGroups` caps groups; nothing caps set size.
**Falsifier:** The read completes with the set bounded by something.
**Setup:** HC. `v_wide` holding 200 000 rows across 4 groups, 50 000 distinct strings per group.
**Steps:** `SELECT g, COUNT(DISTINCT s) AS d FROM v_wide GROUP BY g`; record peak heap.
**Expected:** four rows, each `d = 50000`. Peak heap grows with `4 × 50 000` entries. Record whether
the read completes, and at what cost, and whether a read of 4 000 000 distinct values refuses or dies
— `DEFAULT_MAX_GROUPS` is 1 000 000 *groups*, which does not apply here.
**Vacuity:** V1 — 200 000 rows in the view. V2 — `COUNT(*) = 50 000` per group, so the distinct count
equalling it is a real measurement rather than a collapsed one. A test asserting only "4 rows" passes
while 200 000 rows collapse into 4 — the round-1 failure this contract names.

### AGG-029 — `KeyedAggregate` refuses past a million groups rather than growing
**Intent:** `DEFAULT_MAX_GROUPS = 1_000_000`, checked *inside* `computeIfAbsent` as
`groups.size() >= maxGroups`. A check inside the mapping function of the map being mutated is worth
running rather than reading.
**Falsifier:** The read exhausts the heap, or admits group 1 000 001.
**Setup:** HA, constructing `KeyedAggregate` with `maxGroups = 4` directly, feeding 5 distinct keys.
**Steps:** Feed keys k1…k5; observe.
**Expected:** `PRV-3020` on k5, message "this GROUP BY has produced 4 distinct groups, which is the
ceiling for a single read. Narrow it with a WHERE clause, or group by fewer columns". Record whether
the exception escapes `computeIfAbsent` cleanly or leaves the map in a half-updated state — the
fifth `Group` may or may not be present; assert `groupCount()` afterwards.
**Vacuity:** V1 — four groups must exist before the fifth is refused.

### AGG-030 — `COUNT(*)` and `COUNT(col)` in one row, all three operators side by side
**Intent:** The summary case. Run the same pair of aggregates through global, keyed and windowed over
the same D1 and put the three answers in one table, so the inconsistency is a single observation
rather than three separate reports.
**Falsifier:** All three agree on `COUNT(amount)`.
**Setup:** HB for global and windowed; HC for keyed.
**Steps:** Three queries over D1, each selecting `COUNT(*)` and `COUNT(amount)`.
**Expected:**

| operator | scope | `COUNT(*)` | `COUNT(amount)` correct | `COUNT(amount)` this build |
|---|---|---|---|---|
| `GlobalAggregate` | all 5 rows | 5 | 4 | 4 |
| `KeyedAggregate` | u1 | 3 | 2 | 3 |
| `WindowedAggregate` | W1/u1 | 3 | 2 | 3 |

**Vacuity:** V1 on each of the three runs independently; V2 — the global row proves the correct
behaviour exists in this build, so the other two are not "the engine does not do this".

### AGG-031 — `COUNT(DISTINCT)` over a stream is refused; over a view it is documented ✅
**Intent:** Pin the documentation disagreement directly. `SQL_SUPPORT.md` line 113 is an unqualified
✅; the refusal in `GlobalAggregate` is unconditional for an unwindowed aggregate; and FINDINGS Q-6
says the view path is *refused*, which AGG-024 tests.
**Falsifier:** Both paths behave as the table says.
**Setup:** HD + HB + HC.
**Steps:** (a) `pravaha explain` `SELECT COUNT(DISTINCT user_id) FROM txn` — does the refusal happen
at plan time or run time? (b) HB run of the same. (c) HC read of
`SELECT tier, COUNT(DISTINCT user_id) FROM v_txn GROUP BY tier`.
**Expected:** (a) plans successfully — the refusal is in `GlobalAggregate.process`, not in
`buildAggregate`, so `explain` shows an `Aggregate(group=[], [COUNT_DISTINCT(...)])` for a query that
cannot run. (b) `PRV-3020` at run time. (c) per AGG-024. Conclusion for the doc: line 113's ✅ is
true of exactly one of the three operators.
**Vacuity:** n/a for (a); V1 for (b) and (c).

### AGG-032 — `COUNT(DISTINCT)` with `GROUP BY` over a *windowed* stream, the documented shape
**Intent:** The exact query `SQL_SUPPORT.md` puts forward as the bounded alternative, run end to end
with a hand-computed answer. AGG-020 tests it whole-window; this tests it keyed, which is what the
documentation's example shows.
**Falsifier:** Any group's `d` differs from the hand count.
**Setup:** HB, D1.
**Steps:** `SELECT window_start, window_end, tier, COUNT(*) AS n, COUNT(DISTINCT user_id) AS d
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
GROUP BY window_start, window_end, tier`.
**Expected (correct):** W1/gold → r1, r3 → `n = 2`, `d = |{u1}| = 1`. W1/NULL → r2 → `n = 1`,
`d = 1`. W1/silver → r4 → `n = 1`, `d = 1`. W4/silver → r5 → `n = 1`, `d = 1`.
Four rows. **Expected (this build):** every `d` is 1 by AGG-020's mechanism — here indistinguishable
from correct, which is why AGG-020 uses a group with two distinct values and this one does not.
Record that agreement and do not read it as a pass.
**Vacuity:** V2 — this case cannot discriminate; it exists to show the *documented* shape agrees with
the defect, which is why the defect survived. Report it as `NOT DISCRIMINATING` with AGG-020 as the
case that does discriminate.

---

## §2 — Types × kinds (AGG-033 … AGG-062)

The plan gate refuses two of the sixteen types and lets the rest through to three different
outcomes: correct, wrong, or a raw exception naming an internal schema. Enumerated.

### AGG-033 — `SUM` over FLOAT64 is refused at plan time
**Intent:** FINDINGS Q-2's fix, re-established, and the anchor for AGG-034…AGG-040.
**Falsifier:** The query plans.
**Setup:** HD, stream `floaty`.
**Steps:** `pravaha explain --sql "SELECT SUM(f64) AS t FROM floaty"`.
**Expected:** `PRV-2020`: "SUM(f64) is over a FLOAT64 column, and this engine's aggregates accumulate
in 64-bit integers only… Cast the column to an integer if the rounding is acceptable —
SUM(CAST(price AS BIGINT)) — or aggregate it outside the engine." Message names the column.
**Vacuity:** n/a.

### AGG-034 — `MIN`, `MAX` and `AVG` over FLOAT64 are refused
**Intent:** `refuseFloatingPointAggregate` exempts only COUNT and COUNT_DISTINCT, so three more
kinds must refuse. Enumerated because "SUM over FLOAT64" was the round-1 case and the others were
never written down.
**Falsifier:** Any of the three plans.
**Setup:** HD.
**Steps:** Three `pravaha explain` runs: `MIN(f64)`, `MAX(f64)`, `AVG(f64)`.
**Expected:** three `PRV-2020` refusals, each naming its own kind: "MIN(f64) is over a FLOAT64
column…", "MAX(f64)…", "AVG(f64)…".
**Vacuity:** n/a.

### AGG-035 — The same four kinds over FLOAT32
**Intent:** The other float. The guard tests `FLOAT32 || FLOAT64`; both must be covered.
**Falsifier:** Any of SUM/MIN/MAX/AVG over `f32` plans.
**Setup:** HD.
**Steps:** Four `pravaha explain` runs against `f32`.
**Expected:** four `PRV-2020`, each saying "over a FLOAT32 column".
**Vacuity:** n/a.

### AGG-036 — `COUNT(f64)` is exempt and reaches the runtime
**Intent:** The exemption is correct in principle — COUNT never reads the value — but it is what lets
AGG-037 through. Establish that COUNT over a float column works.
**Falsifier:** `COUNT(f64)` is refused, or counts NULLs.
**Setup:** HB, `floaty` fed `(1, 1.5, 1.5, …), (2, NULL, NULL, …), (3, 2.5, 2.5, …)`.
**Steps:** `SELECT COUNT(*) AS a, COUNT(f64) AS b FROM floaty`.
**Expected:** `a = 3`, `b = 2`. Global aggregate, so the fixed COUNT branch applies.
**Vacuity:** V2 — a ≠ b.

### AGG-037 — `COUNT(DISTINCT f64)` is exempt and reaches the two broken DISTINCT paths
**Intent:** The neighbour fact 4 implies and nobody has checked. A FLOAT64 argument passes the plan
gate, then `KeyedAggregate` reads it with `getString` and `WindowedAggregate` with `getLong`.
**Falsifier:** The query is refused at plan time, or returns the hand-counted distinct.
**Setup:** HB (windowed) and HC (keyed). `floaty` fed f64 values `1.5, 2.5, 1.5, 3.5` in W1.
**Steps:** (a) windowed: `SELECT window_start, COUNT(DISTINCT f64) AS d FROM TABLE(TUMBLE(…))
GROUP BY window_start, window_end`. (b) keyed, over a view of `floaty`, grouped by `id % 2`.
**Expected (correct):** (a) `d = |{1.5, 2.5, 3.5}| = 3`. (b) per group, hand-counted.
**Expected (this build):** (a) `getLong` over a FLOAT64 field reads the IEEE-754 bit pattern as a
long — distinct doubles give distinct bit patterns, so `d = 3` may come out *right*, by accident,
while `+0.0` and `-0.0` (distinct bit patterns, equal values) count as two and `NaN` payloads count
as many. Add **D10**: f64 values `0.0, -0.0` → true distinct 1, expected observed 2. (b) `getString`
over a fixed-width field: record the exception.
**Vacuity:** V2 — `COUNT(*)` in the same row. V3 — D10 is the discriminating input; a run of only the
first input is `NOT DISCRIMINATING`.

### AGG-038 — `SUM(i)` over INT32 dies at emit with a leaked internal schema name
**Intent:** FINDINGS' "one type family away from the real hole". The plan gate passes, every row
accumulates, and the failure arrives at `writer.setLong` against an INT32 output field.
**Falsifier:** The query returns a number, or fails with a `PRV-` coded message.
**Setup:** HB, stream `narrow` fed `(1, 1, 1, 10, 1s), (2, 2, 2, 20, 2s), (3, 3, 3, 30, 3s)`.
**Steps:** `pravaha run --sql "SELECT SUM(i) AS t FROM narrow" --stream narrow --schema
"id:INT64,b:INT8,s:INT16,i:INT32,event_time:TIMESTAMP" --in n.csv --out-schema "t:INT32" --out o.csv`.
**Expected:** correct answer `10 + 20 + 30 = 60`. Actual: `java.lang.IllegalArgumentException:
field 0 ('t') is INT32, not INT64 in schema narrow_aggregated` — raw, uncoded, naming
`narrow_aggregated`, a schema the user never wrote and cannot find in any configuration file. Record
the exact string and the exit code. Note the failure is at **emit**, so `rows in` is 3 and the three
values were accumulated correctly before being thrown away.
**Vacuity:** V1 — `3 in`. A plan-time refusal would be a different (better) finding and must be
recorded as such.

### AGG-039 — `SUM` over INT8 and INT16
**Intent:** The other two narrow integers. Calcite widens `SUM(TINYINT)` and `SUM(SMALLINT)`
differently from `SUM(INTEGER)` in some versions; whether the output field is INT8/INT16 or widened
decides whether this fails like AGG-038 or succeeds.
**Falsifier:** Untested — this case exists to determine the behaviour, and its falsifier is "the two
types behave differently from each other without that being recorded".
**Setup:** HB, `narrow` as AGG-038.
**Steps:** `SELECT SUM(b) AS t FROM narrow`, then `SELECT SUM(s) AS t FROM narrow`, with
`--out-schema` matching the planned output type (take it from `pravaha explain` first).
**Expected:** correct answers `1 + 2 + 3 = 6` for both. Record per type: the planned output
`TypeName`, and whether the run succeeds or throws `field 0 ('t') is INT8, not INT64 in schema
narrow_aggregated`.
**Vacuity:** V1 — `3 in` on each.

### AGG-040 — `MIN`, `MAX` and `AVG` over INT8/INT16/INT32 — nine combinations
**Intent:** AGG-038 covers one kind over one type. The grid is 3 kinds × 3 narrow types and every
cell writes through `setLong`, so all nine are expected to fail identically — which is worth
establishing, because a partial fix that widens only SUM's output would leave six.
**Falsifier:** Any cell behaves differently from its row and column neighbours without that being
recorded.
**Setup:** HB, `narrow`.
**Steps:** Nine runs. Record a 3 × 3 table of outcome and message.
**Expected:** correct answers: `MIN(b) = 1`, `MAX(b) = 3`, `AVG(b) = (1+2+3)/3 = 2`;
`MIN(s) = 1`, `MAX(s) = 3`, `AVG(s) = 2`; `MIN(i) = 10`, `MAX(i) = 30`,
`AVG(i) = (10+20+30)/3 = 60/3 = 20`. Expected actual: nine
`IllegalArgumentException`s naming `narrow_aggregated`.
**Vacuity:** V1 on each. V2 — the same nine over `amount` (INT64) must all succeed with the values
above, proving the failure is the type and not the kind.

### AGG-041 — `COUNT` over the narrow integers is fine
**Intent:** COUNT's output is BIGINT regardless of the argument, so `setLong` matches. Bound the
defect to the value-carrying kinds.
**Falsifier:** `COUNT(i)` throws.
**Setup:** HB, `narrow` with one NULL `i`.
**Steps:** `SELECT COUNT(*) AS a, COUNT(b) AS b_n, COUNT(s) AS s_n, COUNT(i) AS i_n FROM narrow`.
**Expected:** with 3 rows and `i` NULL in one: `a = 3`, `b_n = 3`, `s_n = 3`, `i_n = 2`.
**Vacuity:** V2 — `i_n ≠ a`.

### AGG-042 — `SUM`/`MIN`/`MAX`/`AVG` over INT64, the type everything is written for
**Intent:** The control for all of §2. If this is not right, nothing else in §2 is interpretable.
**Falsifier:** Any value differs from the hand computation.
**Setup:** HB, D1.
**Steps:** `SELECT SUM(amount) AS t, MIN(amount) AS lo, MAX(amount) AS hi, AVG(amount) AS mean,
COUNT(amount) AS n FROM txn`.
**Expected:** `t = 100 + 50 + 200 + 7 = 357`, `lo = 7`, `hi = 200`, `n = 4`,
`mean = 357 / 4 = 89` (exact 89.25).
**Vacuity:** V1 — `5 in`, `n = 4` proves the NULL was excluded.

### AGG-043 — `MIN`/`MAX` over TIMESTAMP_LTZ
**Intent:** `setLong` accepts INT64, TIME and TIMESTAMP_LTZ, so `MIN(event_time)` is expected to work
where `MIN(d)` over DATE is not. The asymmetry follows from three names in one `if` and is worth
pinning.
**Falsifier:** `MIN(event_time)` throws, or returns a value that is not r1's timestamp.
**Setup:** HB, D1.
**Steps:** `SELECT MIN(event_time) AS first, MAX(event_time) AS last FROM txn`.
**Expected:** `first = 1s` (r1), `last = 30s` (r5), both as nanosecond epochs
(1 000 000 000 and 30 000 000 000).
**Vacuity:** V2 — `first ≠ last`.

### AGG-044 — `MIN`/`MAX` over TIME
**Intent:** The second of the three types `setLong` accepts. TIME is 8 bytes here.
**Falsifier:** Throws, or returns a value outside the input set.
**Setup:** HB, `temporal` fed D9 (`09:00:00`, `10:00:00`, `09:00:00`).
**Steps:** `SELECT MIN(t) AS lo, MAX(t) AS hi FROM temporal`.
**Expected:** `lo = 09:00:00`, `hi = 10:00:00`.
**Vacuity:** V2 — lo ≠ hi.

### AGG-045 — `MIN`/`MAX` over DATE
**Intent:** DATE is 4 bytes and `setLong` does *not* accept it, so this is expected to fail exactly
like INT32 — while `MIN(event_time)` beside it succeeds. Two temporal columns, two outcomes.
**Falsifier:** It succeeds, or it fails with a `PRV-` code.
**Setup:** HB, `temporal`, D9.
**Steps:** `SELECT MIN(d) AS lo, MAX(d) AS hi FROM temporal`.
**Expected:** correct answers `lo = 2026-01-01`, `hi = 2026-01-02`. Expected actual:
`field 0 ('lo') is DATE, not INT64 in schema temporal_aggregated`.
**Vacuity:** V2 — run AGG-043's `MIN(event_time)` in the same session; it must succeed, proving the
failure is DATE-specific.

### AGG-046 — `MIN`/`MAX` over BOOLEAN
**Intent:** Calcite permits `MIN(BOOLEAN)`. The runtime reads it with `getLong` and writes with
`setLong` into a BOOLEAN field.
**Falsifier:** It returns `false`/`true` correctly.
**Setup:** HB, `temporal`, D9 (flags true, false, true).
**Steps:** `SELECT MIN(flag) AS lo, MAX(flag) AS hi FROM temporal`.
**Expected:** correct `lo = false`, `hi = true`. Expected actual: `field 0 ('lo') is BOOLEAN, not
INT64 in schema temporal_aggregated`, or a plan-time refusal from Calcite. Record which.
**Vacuity:** V2 — `COUNT(flag) = 3` in the same query proves the column is readable.

### AGG-047 — `MIN`/`MAX` over STRING
**Intent:** `MIN(varchar)` is ordinary SQL and Calcite types it as VARCHAR. The runtime does
`row.getLong` on a variable-width field — reading the `(offset, length)` slot as a number and then
comparing *that* — and writes it back with `setLong` into a STRING field.
**Falsifier:** `MIN(user_id)` returns `u1`.
**Setup:** HB, D1.
**Steps:** `SELECT MIN(user_id) AS lo, MAX(user_id) AS hi FROM txn`.
**Expected:** correct `lo = u1`, `hi = u3`. Expected actual: `field 0 ('lo') is STRING, not INT64 in
schema txn_aggregated`. Note the comparison itself is also meaningless — extremes of a slot offset,
not of a string — so a fix to the writer alone would turn an exception into a wrong answer.
**Vacuity:** V2 — `COUNT(user_id) = 5` proves the column reads.

### AGG-048 — `SUM` over STRING, BOOLEAN, DATE, TIME and TIMESTAMP is refused by validation
**Intent:** Five types for which `SUM` has no meaning. Where the refusal comes from — Calcite's
validator or Pravaha — decides what the user sees, and a raw Calcite message is a different
experience from a `PRV-` one.
**Falsifier:** Any of the five plans.
**Setup:** HD, `txn` and `temporal`.
**Steps:** Five `pravaha explain` runs: `SUM(user_id)`, `SUM(flag)`, `SUM(d)`, `SUM(t)`,
`SUM(event_time)`.
**Expected:** five refusals. Record the code and the first line of each message; expect
`PRV-2021`-shaped validation errors from Calcite rather than the aggregate's own refusals. Flag any
that plans.
**Vacuity:** n/a.

### AGG-049 — `SUM`/`MIN`/`MAX`/`AVG` over DECIMAL
**Intent:** `SQL_SUPPORT.md` and `refuseFloatingPointAggregate`'s own javadoc both say DECIMAL is
refused "for the reason DECIMAL arithmetic is" — but the guard tests only FLOAT32/FLOAT64. Which
layer actually refuses, and with what code, is unestablished (fact 6).
**Falsifier:** Any of the four returns a number.
**Setup:** HD then HB, `floaty` with `dec DECIMAL(18,2)` fed `1.00, 2.50, 3.25`.
**Steps:** Four `pravaha explain` runs, then an HB run of whichever plans.
**Expected:** correct `SUM = 1.00 + 2.50 + 3.25 = 6.75`, `MIN = 1.00`, `MAX = 3.25`,
`AVG = 6.75 / 3 = 2.25`. Record per kind: refused at plan (which code, which message), refused at
run, or answered. A DECIMAL is 16 bytes and `getLong` reads the high word only, so an answer — if one
comes — is expected to be the high 8 bytes of the two-word form.
**Vacuity:** V1 for any run that reaches execution.

### AGG-050 — `COUNT` and `COUNT(DISTINCT)` over DECIMAL
**Intent:** The exemption again. `COUNT(dec)` should be fine; `COUNT(DISTINCT dec)` reaches
`getString`/`getLong` over a 16-byte field.
**Falsifier:** `COUNT(dec)` is refused.
**Setup:** HB and HC, `floaty`.
**Steps:** `SELECT COUNT(*) AS a, COUNT(dec) AS b FROM floaty` (global); then
`COUNT(DISTINCT dec)` keyed and windowed.
**Expected:** `a = 3`, `b = 3`; with one NULL `dec`, `b = 2`. For DISTINCT: correct is
`|{1.00, 2.50, 3.25}| = 3`; record the observed value, and note that `getLong` reads only the high
word, so two decimals differing only in the low word count as one.
**Vacuity:** V2 — `a ≠ b` in the NULL variant.

### AGG-051 — `GROUP BY` a DECIMAL column
**Intent:** `KeyedAggregate.read` and `writeKey` both have a `default ->` arm throwing `PRV-3020`
"cannot group by a column of type DECIMAL yet". Distinct from AGG-049, which is about the aggregate's
argument.
**Falsifier:** The read succeeds, or fails with a different code.
**Setup:** HC, a view over `floaty`.
**Steps:** `pravaha query --sql "SELECT dec, COUNT(*) AS n FROM v_floaty GROUP BY dec"`.
**Expected:** `PRV-3020`, "cannot group by a column of type DECIMAL yet". Record whether it comes
from `read` (during accumulation, on the first row) or `writeKey` (at emit) — the two differ in
whether the whole scan ran first.
**Vacuity:** V1 — rows must be in the view.

### AGG-052 — `GROUP BY` each of the eight supported key types
**Intent:** `KeyedAggregate.read` handles BOOLEAN, INT8, INT16, INT32, DATE, INT64, TIME,
TIMESTAMP_LTZ, FLOAT32, FLOAT64, STRING. Ten named types and everything else refused. Enumerated
because grouping by FLOAT64 is *permitted* here while aggregating over it is refused — an asymmetry
that will surprise somebody.
**Falsifier:** Any listed type is refused, or any unlisted type is accepted.
**Setup:** HC. Views over `narrow`, `floaty`, `temporal` and `txn`.
**Steps:** Eleven reads, one per type: `SELECT <col>, COUNT(*) AS n FROM v GROUP BY <col>`.
**Expected:** all eleven succeed, each returning one row per distinct value with hand-counted `n`.
Specifically `GROUP BY f64` succeeds where `SUM(f64)` is refused — record that pair as a documented
inconsistency in the float story.
**Vacuity:** V1 on each; V2 — the group counts must sum to the view's row count.

### AGG-053 — `GROUP BY` BYTES, ARRAY, MAP and ROW
**Intent:** The four types with no arm in `read`. Four refusals, one per type, each expected to name
its own type rather than a generic one.
**Falsifier:** Any of the four is accepted, or the message names the wrong type.
**Setup:** HC, a view whose schema declares each.
**Steps:** Four reads.
**Expected:** four `PRV-3020` refusals: "cannot group by a column of type BYTES yet", and the same
for ARRAY, MAP, ROW.
**Vacuity:** n/a.

### AGG-054 — Aggregates over BYTES, ARRAY, MAP and ROW arguments
**Intent:** The other half of AGG-053: 4 types × {COUNT, COUNT_DISTINCT, SUM, MIN, MAX, AVG}. Most
will be refused by Calcite's validator; `COUNT` will not be.
**Falsifier:** Any cell returns a value without that being recorded.
**Setup:** HD, then HB for anything that plans.
**Steps:** Up to 24 `pravaha explain` runs. Record a 4 × 6 table of code and layer.
**Expected:** `COUNT(blob)` plans and returns the non-null row count. Everything else refuses;
record which refusals come from Calcite (no `PRV-` code) and which from Pravaha.
**Vacuity:** V1 for any that runs.

### AGG-055 — The windowed operator's group-key types
**Intent:** `WindowedAggregate.readKey` and `writeKey` have a `default ->` that falls through to
`getLong`/`setLong` rather than refusing — so an unsupported type is silently misread here where
`KeyedAggregate` refuses. Different operator, different failure for the same query.
**Falsifier:** A windowed `GROUP BY` over BYTES refuses like the keyed one.
**Setup:** HB, a stream with a `blob BYTES` column, windowed.
**Steps:** `SELECT window_start, blob, COUNT(*) AS n FROM TABLE(TUMBLE(…)) GROUP BY window_start,
window_end, blob`.
**Expected:** correct behaviour is a refusal. Expected actual: `readKey`'s `default` reads the slot
as a long, `compositeKey`'s `default` likewise, and `writeKey`'s `default` writes it back with
`setLong` into a BYTES field — so either a wrong grouping or `field N is BYTES, not INT64 in schema
…_aggregated`. Record which.
**Vacuity:** V1 — rows in; V2 — the same query grouped by `user_id` must work.

### AGG-056 — `GROUP BY` a NULL-bearing column: NULL is a group, not a discarded row
**Intent:** `SQL_SUPPORT.md` line 162 and `KeyedAggregate.Key`'s javadoc both promise it. It is the
one place NULL behaves unlike a comparison and is easy to get backwards.
**Falsifier:** The NULL rows vanish, or each NULL forms its own group.
**Setup:** HC, D1, `v_txn`.
**Steps:** `SELECT tier, COUNT(*) AS n FROM v_txn GROUP BY tier`.
**Expected:** three rows. `gold` → r1, r3 → `n = 2`. `silver` → r4, r5 → `n = 2`. NULL → r2 →
`n = 1`. `2 + 2 + 1 = 5`.
**Vacuity:** V1 — the counts must sum to 5. Two rows summing to 4 means the NULL group vanished.

### AGG-057 — Two NULL rows group together under one key
**Intent:** `Key.equals` uses `Arrays.equals`, so `{null}` equals `{null}`. One NULL row cannot
distinguish "NULL is a group" from "NULL is its own group each time".
**Falsifier:** Two rows come back for NULL.
**Setup:** HC, D3 (adds r6, r7 with NULL tier).
**Steps:** `SELECT tier, COUNT(*) AS n FROM v_txn GROUP BY tier`.
**Expected:** the NULL group has `n = 3` (r2, r6, r7), not three rows of `n = 1`.
**Vacuity:** V3 — assert three rows with `tier IS NULL` exist in the view first.

### AGG-058 — Windowed `GROUP BY` a NULL-bearing column
**Intent:** `WindowedAggregate.compositeKey` uses a distinct constant `0xD1B54A32D192ED03L` for NULL
"because NULL and 0 are different groups". That claim needs the discriminating input: a row whose
value *is* zero alongside a row whose value is NULL.
**Falsifier:** The NULL group and the zero group merge.
**Setup:** HB. Input **D11**, all in W1: `(1, u1, 0, 1s)`, `(2, u2, NULL, 2s)`, `(3, u3, 0, 3s)`
grouping by `amount`.
**Steps:** `SELECT window_start, amount, COUNT(*) AS n FROM TABLE(TUMBLE(…)) GROUP BY window_start,
window_end, amount`.
**Expected:** two rows in W1: `amount = 0` → `n = 2`; `amount = NULL` → `n = 1`. One row of `n = 3`
falsifies.
**Vacuity:** V1 — `3 in`, and `2 + 1 = 3`.

### AGG-059 — Windowed group keys are 128 bits and must not collide at a million groups
**Intent:** `SlicedAggregateState`'s javadoc puts the 64-bit collision probability at 3 × 10⁻⁸ and
the 128-bit one at 10⁻²⁷, and the class comment on `WindowedAggregate` still describes the 64-bit
design. Establish that the two-hash path is the one in use.
**Falsifier:** Two distinct keys in one window produce one output row.
**Setup:** HA. One window, 1 000 000 distinct STRING keys, one row each, `maxSlices` raised.
**Steps:** Feed, advance past the window end, count output rows.
**Expected:** exactly 1 000 000 rows, each `COUNT(*) = 1`. Report the row count, not a boolean.
**Vacuity:** V1 — 1 000 000 rows in. V2 — sum of all `COUNT(*)` equals 1 000 000; 999 999 rows with
one showing `n = 2` is the collision this case is for and must be reported as the pair of keys.

### AGG-060 — The windowed slice ceiling refuses rather than evicting
**Intent:** `maxSlices` (2 000 000 by default) throws `PRV-3020` naming the key and the event time.
"Exceeding it is a refusal, not an eviction" is the claim; an eviction would make answers wrong
instead of stopping.
**Falsifier:** Rows past the ceiling are silently dropped and the query continues.
**Setup:** HA, `SlicedAggregateState` constructed with `maxSlices = 3`, four distinct keys in one
slice.
**Steps:** Feed k1…k4.
**Expected:** `PRV-3020` on k4: "this windowed aggregate is holding 3 (key, slice) accumulators, its
configured ceiling, and key <high>:<low> at event time <t> needs another…". Record that the key is
reported as two hash words, not as the user's value — a diagnostic naming `-4881...:8823...` does not
let anybody find the offending key.
**Vacuity:** V1 — three accumulators exist before the fourth is refused.

### AGG-061 — `maxSlices` below 1 is refused at construction
**Intent:** The boundary of the ceiling itself.
**Falsifier:** `new SlicedAggregateState(w, kinds, 0)` constructs.
**Setup:** HA.
**Steps:** Construct with 0, with -1, and with 1.
**Expected:** 0 and -1 throw `IllegalArgumentException` "the slice limit must be at least 1, got 0".
1 constructs and admits exactly one accumulator.
**Vacuity:** n/a.

### AGG-062 — The type grid, summarised
**Intent:** One table so the area's type story is a single observation. Sixteen types × six kinds is
96 cells; most are refusals and the interesting ones are the six that are not.
**Falsifier:** Any cell in the table disagrees with the case that produced it.
**Setup:** The results of AGG-033 … AGG-055.
**Steps:** Tabulate.
**Expected:** a 16 × 6 table whose cells are one of: `✓` (correct, hand-checked), `plan PRV-2020`,
`plan Calcite`, `run PRV-3020`, `run raw IAE (schema leak)`, `wrong answer`. Every `wrong answer`
and every `run raw IAE` cell names the case that established it.
**Vacuity:** n/a — a summary of executed cases; it may not be filled in from reading.

---

## §3 — Input shapes: no rows, one row, many rows, all NULL, some NULL (AGG-063 … AGG-078)

### AGG-063 — Global aggregate over zero rows emits one row of zeros
**Intent:** SQL says `SELECT COUNT(*), SUM(x), MIN(x), MAX(x), AVG(x) FROM empty` returns one row:
`0, NULL, NULL, NULL, NULL`. `GlobalAggregate.emit` has no guard on `rowCount`, and every accumulator
starts at 0, so it emits `0, 0, 0, 0, 0`. Four of the five values disagree with SQL.
**Falsifier:** Zero output rows, or `SUM` reported as NULL.
**Setup:** HB. `empty.csv` with a header and no data rows.
**Steps:** `pravaha run --sql "SELECT COUNT(*) AS n, SUM(amount) AS t, MIN(amount) AS lo,
MAX(amount) AS hi, AVG(amount) AS mean FROM txn" … --in empty.csv`.
**Expected (SQL):** one row `n = 0, t = NULL, lo = NULL, hi = NULL, mean = NULL`.
**Expected (this build):** one row `n = 0, t = 0, lo = 0, hi = 0, mean = 0` — `AVG` takes the
`counts[i] == 0 ? 0` arm, `MIN`/`MAX` never set `seen[i]` so `sums[i]` stays at its initial 0.
Also record `eventTimestampNanos = 0` on the emitted row, since `lastTimestamp` was never assigned.
**Vacuity:** V1 — `ok 0 in, 1 out`. A run of `0 in, 0 out` is a different (and arguably better)
behaviour and must be recorded as such, not as a pass.

### AGG-064 — Keyed aggregate over zero rows emits nothing
**Intent:** The correct SQL behaviour, and the opposite of AGG-063 in the same engine. `emit`
iterates `groups`, which is empty.
**Falsifier:** Any output row.
**Setup:** HC, a view with no rows.
**Steps:** `SELECT user_id, COUNT(*) AS n FROM v_empty GROUP BY user_id`.
**Expected:** zero rows. This is right — `GROUP BY` over nothing produces no groups — and the
contrast with AGG-063 is itself the finding: the same engine has both behaviours and both are
correct for their own shape, which is worth recording so a fix to one does not "harmonise" the other.
**Vacuity:** V1 — the view must exist and be readable; an error masquerading as zero rows fails this.

### AGG-065 — Windowed aggregate over zero rows emits nothing
**Intent:** `finish()` returns immediately when `highestEventTime == Long.MIN_VALUE`, so no window
fires at all.
**Falsifier:** Any output row, or a window fired at the epoch.
**Setup:** HB, `empty.csv`, AGG-003's SQL.
**Steps:** Run.
**Expected:** `ok 0 in, 0 out`. Note `firstWindowStart()` would return 0 if reached, which would walk
every window since 1970 — the case this guard prevents; assert the run completes in under a second.
**Vacuity:** V1 — `0 in`; assert the run terminates rather than spinning.

### AGG-066 — Global aggregate over exactly one row
**Intent:** The `seen[i]` initialisation path in `MIN`/`MAX`, and the `counts == 1` divisor in `AVG`.
One row is where "first value" logic either works or is never exercised.
**Falsifier:** Any value differs from the single row's.
**Setup:** HB. `one.csv` = r1 alone.
**Steps:** `SELECT COUNT(*) AS n, SUM(amount) AS t, MIN(amount) AS lo, MAX(amount) AS hi,
AVG(amount) AS mean FROM txn`.
**Expected:** `n = 1`, `t = 100`, `lo = 100`, `hi = 100`, `mean = 100 / 1 = 100`.
**Vacuity:** V1 — `1 in, 1 out`.

### AGG-067 — Windowed aggregate over exactly one row per window
**Intent:** `SlicedAggregateState.update`'s `accumulator.count == weight` test is how MIN/MAX seed
the first value, and it is subtly different from `GlobalAggregate`'s `seen[]` flag: it compares the
*running* count to this row's weight, so a row of weight 2 arriving first also seeds.
**Falsifier:** `lo`/`hi` differ from the row's own value.
**Setup:** HB. One row in W1 (amount 100, 1s), one in W4 (amount 7, 30s).
**Steps:** AGG-003's SQL plus `MIN(amount) AS lo, MAX(amount) AS hi`.
**Expected:** W1 → `n = 1, t = 100, lo = 100, hi = 100`. W4 → `n = 1, t = 7, lo = 7, hi = 7`.
**Vacuity:** V1 — `2 in, 2 out`.

### AGG-068 — A row of weight 2 seeds MIN/MAX in the windowed operator
**Intent:** The `accumulator.count == weight` test above. Feed a first row with weight 2 and confirm
it seeds rather than being compared against the initial zero.
**Falsifier:** `MIN` comes back as 0 where the only value is 100.
**Setup:** HA, one window, one key. Feed `(amount 100, weight 2)` then `(amount 50, weight 1)`.
**Steps:** Advance past the window end; read.
**Expected:** `COUNT(*) = 2 + 1 = 3`, `SUM = 100 × 2 + 50 × 1 = 250`, `MIN = 50`, `MAX = 100`.
On the first row `accumulator.count` becomes 2 and `weight` is 2, so the test holds and 100 seeds.
**Vacuity:** V3 — a run with the weight-2 row *second* (`50` at weight 1 first) must give the same
`MIN`/`MAX`; if the two orders disagree the seeding test is order-dependent, which is the finding.

### AGG-069 — Many rows: 200 000 into 500 keys, counted per key
**Intent:** The contract names this: round 1 had a row-count assertion that passed while 200 000 rows
collapsed into 500. The assertion has to be per key and hand-derivable.
**Falsifier:** The per-key counts do not each equal 400, or they do not sum to 200 000.
**Setup:** HB. 200 000 rows, `user_id` cycling `k000`…`k499`, all event times inside W1, amount = 1.
**Steps:** Windowed query grouped by `window_start, window_end, user_id`.
**Expected:** 500 output rows. Each `COUNT(*) = 200 000 / 500 = 400`, each `SUM(amount) = 400 × 1 =
400`. Sum of counts `500 × 400 = 200 000`.
**Vacuity:** V1 — `200000 in`. V2 — assert **each** of the 500 counts is 400, not that there are 500
rows; the round-1 failure passed the second and would fail the first.

### AGG-070 — Many rows past the arena's 210 000-row wall
**Intent:** FINDINGS records an `IndexOutOfBoundsException` with a wrapped signed-32-bit offset past
roughly 210 000 rows, and at 230 000 a frozen ingest returning 13 windows instead of 19 with no
error. Establish whether an aggregate is affected at the same threshold.
**Falsifier:** The run completes with all windows and all counts correct.
**Setup:** HB. 230 000 rows across 19 tumbling windows, 500 keys.
**Steps:** Windowed grouped query; record output window count and per-window row counts.
**Expected:** 19 windows. Record the observed count and whether the run reports success. A result of
13 windows under `ok` is the failure; report the last window that appeared and the row index at
which ingest stopped.
**Vacuity:** V1 — `rows in` must be compared against 230 000 and the difference reported. A run
reporting `149388 in` under `ok` is the finding.

### AGG-071 — `SUM` over a column that is entirely NULL returns 0, not NULL
**Intent:** SQL says `SUM` of no non-null values is NULL. All three operators return 0, and 0 is a
number somebody divides by or compares to a threshold.
**Falsifier:** `t` comes back NULL.
**Setup:** HB, D2 (every `amount` NULL).
**Steps:** `SELECT COUNT(*) AS n, COUNT(amount) AS c, SUM(amount) AS t FROM txn`.
**Expected (SQL):** `n = 5`, `c = 0`, `t = NULL`. **Expected (this build):** `n = 5`, `c = 0`,
`t = 0`. The pair `c = 0, t = 0` is at least self-consistent; the pair `c = 5, t = 0` from the keyed
and windowed operators (AGG-015) is not.
**Vacuity:** V1 — `5 in`; V3 — `c = 0` proves the nulls were seen and excluded by SUM's guard.

### AGG-072 — `MIN`/`MAX` over a column that is entirely NULL return 0, not NULL
**Intent:** `seen[i]` is never set, so `sums[i]` stays 0 and 0 is emitted as the minimum of no values.
Distinct from AGG-071 because a 0 minimum is worse than a 0 sum: it is a value smaller than every
real value in the column.
**Falsifier:** `lo`/`hi` come back NULL.
**Setup:** HB, D2.
**Steps:** `SELECT COUNT(amount) AS c, MIN(amount) AS lo, MAX(amount) AS hi FROM txn`.
**Expected (SQL):** `c = 0`, `lo = NULL`, `hi = NULL`. **Expected (this build):** `c = 0`, `lo = 0`,
`hi = 0`.
**Vacuity:** V3 — `c = 0` in the same row.

### AGG-073 — `MIN`/`MAX` where all values are negative and some are NULL
**Intent:** AGG-072's consequence made visible. If `MIN` seeds from an unset 0, a column of negative
values reports a maximum of 0 that is not in the data.
**Falsifier:** `hi` equals the largest actual value.
**Setup:** HB. Input **D12**: amounts `-5, NULL, -2, -9`, four rows.
**Steps:** `SELECT COUNT(amount) AS c, MIN(amount) AS lo, MAX(amount) AS hi FROM txn`.
**Expected:** `c = 3`, `lo = -9`, `hi = -2`. `seen[i]` is set by the first non-null row (`-5`), so
this should be correct — the case exists to prove the seeding works and that AGG-072's 0 comes only
from *never* seeding. If `hi = 0` here, the defect is larger than AGG-072 describes.
**Vacuity:** V2 — `lo ≠ hi`; V3 — `c = 3` proves the NULL was excluded.

### AGG-074 — Keyed `MIN`/`MAX` where one group is entirely NULL and another is not
**Intent:** Per-group `seen[]`. A group with values must not seed a group without, and a group
without must be distinguishable from one whose minimum really is 0.
**Falsifier:** The all-NULL group reports the other group's minimum.
**Setup:** HC. **D13**: `(u1, 100), (u1, 200), (u2, NULL), (u2, NULL), (u3, 0)`.
**Steps:** `SELECT user_id, COUNT(amount) AS c, MIN(amount) AS lo FROM v_txn GROUP BY user_id`.
**Expected (SQL):** u1 → `c = 2, lo = 100`; u2 → `c = 0, lo = NULL`; u3 → `c = 1, lo = 0`.
**Expected (this build):** u2 → `lo = 0`, indistinguishable from u3. And u2's `c` is 2, not 0, by
AGG-013. So u2's row reads `c = 2, lo = 0` where the truth is `c = 0, lo = NULL`.
**Vacuity:** V3 — u3 must be present, because u2 and u3 reporting the same `lo` is the observation.

### AGG-075 — Some NULL, some not, across all five kinds in one row
**Intent:** The mixed case is the realistic one and the one where the COUNT defect is visible without
any external comparison. One query, five columns, internal consistency as the check.
**Falsifier:** The row is internally consistent on a build with the defect.
**Setup:** HB (windowed, keyed by user) and HC (keyed over a view), D1.
**Steps:** `SELECT user_id, COUNT(*) AS a, COUNT(amount) AS c, SUM(amount) AS t, AVG(amount) AS m,
MIN(amount) AS lo, MAX(amount) AS hi …` for u1.
**Expected (correct):** `a = 3, c = 2, t = 300, m = 150, lo = 100, hi = 200`, and `t / c = m`.
**Expected (this build, keyed):** `a = 3, c = 3, t = 300, m = 150` — `t / c = 100 ≠ m`.
**Expected (this build, windowed):** `a = 3, c = 3, t = 300, m = 300` (AVG not divided, AGG-096).
**Vacuity:** V2 — the invariant `t / c == m` is the check and needs no oracle; assert it explicitly
and report the three numbers when it fails.

### AGG-076 — NULL in the aggregated column of a windowed operator becomes 0 in `scratch`
**Intent:** `scratch[i] = ordinal < 0 || row.isNull(ordinal) ? 0 : row.getLong(ordinal)` — for SUM
this is harmless (adding 0), for MIN/MAX it is not: 0 participates in the comparison.
**Falsifier:** `MIN` over a NULL-bearing column of positive values returns the smallest real value.
**Setup:** HB. **D14**, all in W1: amounts `100, NULL, 200`.
**Steps:** `SELECT window_start, COUNT(*) AS n, MIN(amount) AS lo, MAX(amount) AS hi,
SUM(amount) AS t FROM TABLE(TUMBLE(…)) GROUP BY window_start, window_end`.
**Expected (correct):** `n = 3`, `lo = 100`, `hi = 200`, `t = 100 + 200 = 300`.
**Expected (this build):** `t = 100 + 0 + 200 = 300` (unaffected), `hi = 200` (unaffected),
`lo = min(100, 0, 200) = 0` — a minimum that is not in the column. Report `lo` as the finding.
**Vacuity:** V3 — `n = 3` with `COUNT(amount)` also 3 (AGG-017) proves the NULL row reached the
accumulator. V2 — `t = 300` proves SUM's path is unaffected, so `lo = 0` is specific to MIN.

### AGG-077 — The same over MAX with negative values
**Intent:** AGG-076's mirror: a NULL becoming 0 raises the maximum of an all-negative column.
**Falsifier:** `hi` equals the largest real value.
**Setup:** HB. **D15**, all in W1: amounts `-5, NULL, -2`.
**Steps:** As AGG-076.
**Expected (correct):** `n = 3`, `lo = -5`, `hi = -2`, `t = -7`.
**Expected (this build):** `hi = max(-5, 0, -2) = 0`, `lo = -5`, `t = -5 + 0 + -2 = -7`.
**Vacuity:** V3 — `n = 3`; V2 — `t = -7` is right, isolating the defect to MAX.

### AGG-078 — Keyed and global MIN/MAX are *not* affected by the same NULL
**Intent:** Bound AGG-076/077 to the windowed operator. `GlobalAggregate` and `KeyedAggregate` guard
MIN/MAX with `!row.isNull(...)`; only the windowed path pre-flattens to zero. Establishing the
asymmetry is what tells a fixer which file to open.
**Falsifier:** The global answer for D14 is also 0.
**Setup:** HB (global) and HC (keyed), D14.
**Steps:** `SELECT MIN(amount) AS lo FROM txn`; then the same grouped over a view.
**Expected:** `lo = 100` in both. Combined with AGG-076's `lo = 0`, the three operators disagree on
one input, which is the single most compact statement of this area's finding.
**Vacuity:** V3 — the NULL row must be in the input for both (`COUNT(*) = 3`, `COUNT(amount) = 2`
globally).

---

## §4 — Retraction and net-zero (AGG-079 … AGG-092)

### AGG-079 — Global `SUM` under a retraction is the same arithmetic as an insert
**Intent:** `sums[i] += row.getLong(...) * weight`. The Z-set claim, at its simplest, on the operator
whose javadoc makes it: "there is no separate retract path to get wrong".
**Falsifier:** The total after ρ3 differs from the total without r3.
**Setup:** HA, global `SUM(amount)` over `txn`, bounded input.
**Steps:** Feed r1, r2, r3, r5 at `+1`, then ρ3 at `-1`. Finish; read.
**Expected:** `100 + 50 + 200 + 7 + (200 × -1) = 157`. Control run without r3 and without ρ3:
`100 + 50 + 7 = 157`. Equal.
**Vacuity:** V1 — five rows in, one of them negative. V2 — the run *with* r3 and *without* ρ3 must
give `357`, so 157 is a change and not a constant.

### AGG-080 — Global `COUNT(*)` under a retraction
**Intent:** `counts[i] += weight` with `argumentOrdinal() < 0`.
**Falsifier:** `n` does not decrease.
**Setup:** HA, as AGG-079.
**Steps:** Feed r1, r2, r3, r5, then ρ3.
**Expected:** `n = 1 + 1 + 1 + 1 - 1 = 3`. `rowCount()` likewise 3.
**Vacuity:** V2 — without ρ3 the same harness gives 4.

### AGG-081 — Global `COUNT(col)` under a retraction of a NULL row
**Intent:** The fixed branch under a negative weight. Retracting r4 (`amount` NULL) must not
decrement `COUNT(amount)`, because it never incremented it.
**Falsifier:** `COUNT(amount)` drops by one.
**Setup:** HA, global harness over `txn`, bounded input.
**Steps:** Feed r1, r3, r4 at `+1`, then r4 at `-1`; finish; read.
**Expected:** `COUNT(*) = 1 + 1 + 1 - 1 = 2`; `COUNT(amount) = 2` throughout —
`+1` for r1, `+1` for r3, nothing for r4's insert (NULL), nothing for r4's retraction (still NULL).
`SUM = 100 + 200 = 300`. The pair `(2, 300)` is consistent: `300 / 2 = 150 = AVG`.
**Vacuity:** V2 — the keyed operator over the same sequence gives `COUNT(amount) = 1 + 1 + 1 - 1 = 2`
by a different route (counting the NULL both times), which *also* reads 2 — so this case cannot
discriminate keyed from global and must not be used to claim the keyed path is correct. Use
AGG-013.

### AGG-082 — Keyed aggregate: a group whose weights cancel is not emitted
**Intent:** `emit` skips `group.rowCount == 0` — "emitting it would report a group that is no longer
there". The net-zero case, on the operator that handles it.
**Falsifier:** A row comes back for the cancelled group.
**Setup:** HA, `KeyedAggregate` directly. Feed `(u1, 100, +1)`, `(u2, 50, +1)`, `(u1, 100, -1)`.
**Steps:** `emit()`; collect.
**Expected:** one row, u2 → `COUNT(*) = 1`, `SUM = 50`. u1 absent. `groupCount()` reports **2** — the
map still holds u1 with `rowCount == 0`, which is state for a group with no rows; record it.
**Vacuity:** V3 — before `emit`, `groupCount() == 2` and u1's accumulator exists, so "absent from the
output" is a decision and not a row that never arrived.

### AGG-083 — Global aggregate: net-zero still emits a row
**Intent:** The asymmetry with AGG-082. `GlobalAggregate.emit` has no `rowCount == 0` guard, so a
fully cancelled input emits `COUNT = 0, SUM = 0`. For a global aggregate this is *correct* SQL
(`SELECT COUNT(*)` over an empty table is one row of 0); the point is to record that the two
operators differ deliberately.
**Falsifier:** No row is emitted.
**Setup:** HA. Feed `(100, +1)`, `(100, -1)`.
**Steps:** Finish; collect.
**Expected:** one row, `COUNT(*) = 1 - 1 = 0`, `SUM = 100 - 100 = 0`, `rowCount() = 0`.
**Vacuity:** V1 — two rows in. V2 — without the retraction, `COUNT(*) = 1, SUM = 100`.

### AGG-084 — A weight-0 row is ignored by all three operators
**Intent:** Each has an early `if (weight == 0) return;` with the same reasoning ("a consolidated row
contributes nothing and must not be counted"). Three implementations of one rule.
**Falsifier:** `COUNT(*)` counts the weight-0 row, or an accumulator is created for it.
**Setup:** HA, three harnesses. Feed r1 at `+1`, a row at weight 0 with a *new* key, r3 at `+1`.
**Steps:** Emit; collect; for the windowed case assert the slice count.
**Expected:** `COUNT(*) = 2` in all three; no group and no `(key, slice)` accumulator for the
weight-0 key — `SlicedAggregateState.update` returns before `slices.put`, which its comment says is
the point. Assert `groupCount() == 1` (keyed) and the slice count unchanged (windowed).
**Vacuity:** V1 — three rows in, two counted; a harness that dropped the weight-0 row at the writer
would also show 2 and must be excluded by asserting `rowsIn == 3`.

### AGG-085 — `MIN` under a retraction is refused, in all three operators
**Intent:** `SQL_SUPPORT.md` lists `MIN`/`MAX` as ✅ with no qualification; all three operators throw
`PRV-3020` on a negative weight. Establish what actually happens — the brief's exact question — and
that the three messages differ.
**Falsifier:** Any of the three returns a value.
**Setup:** HA × 3. Feed `(100, +1)`, `(200, +1)`, then `(200, -1)` with `MIN(amount)` in the query.
**Steps:** Observe.
**Expected:** three `PRV-3020` refusals. `GlobalAggregate`: "MIN cannot yet handle a retraction:
restoring the previous extreme needs an ordered multiset per group, which arrives with the aggregate
lift. Use SUM or COUNT for now." `KeyedAggregate`: the same without the last sentence.
`SlicedAggregateState`: "…Use SUM or COUNT, or drop the retraction." Record all three verbatim; three
messages for one rule is a maintenance hazard and an `ERRC` cross-reference.
**Vacuity:** V1 — the two inserts must be accepted first, so the refusal is about the retraction and
not about MIN.

### AGG-086 — `MAX` under a retraction is refused identically
**Intent:** The kind is interpolated into the message (`call.kind() + " cannot yet handle…"`), so MAX
must produce a MAX message and not a MIN one.
**Falsifier:** The message says MIN.
**Setup:** As AGG-085 with `MAX(amount)` in place of `MIN(amount)`.
**Steps:** Feed `(100, +1)`, `(200, +1)`, then `(200, -1)`, in each of the three harnesses.
**Expected:** three `PRV-3020`s, each beginning "MAX cannot…".
**Vacuity:** V1 as AGG-085.

### AGG-087 — The refusal fires even when the retraction cannot change the extreme
**Intent:** The guard is `if (weight < 0)` before any value comparison, so retracting a row that is
neither the minimum nor the maximum is refused too. That is conservative and defensible — and it
means a query with a `MIN` beside a `SUM` fails on *any* retraction anywhere in the group.
**Falsifier:** The retraction of a middle value is accepted.
**Setup:** HA. Feed `(100, +1)`, `(150, +1)`, `(200, +1)`, then `(150, -1)`.
**Steps:** Observe.
**Expected:** `PRV-3020` on the fourth row. The correct answer — `MIN = 100`, `MAX = 200`, unchanged
— is computable without an ordered multiset, and the operator refuses anyway. Record it as a
usability finding, not a correctness one.
**Vacuity:** V3 — the three inserts must have been accepted; assert `MIN = 100` is readable before
the retraction if the harness allows an intermediate read.

### AGG-088 — A query mixing `MIN` and `SUM` fails wholly on a retraction
**Intent:** The blast radius of AGG-085. The kinds share one loop over `calls`, so `MIN`'s throw
aborts the row before `SUM` has accumulated it — and the exception escapes mid-row, leaving the
already-processed calls updated and the rest not.
**Falsifier:** `SUM` reflects the retraction while `MIN` refuses, or the query survives.
**Setup:** HA. `SELECT SUM(amount) AS t, MIN(amount) AS lo FROM …` with calls ordered SUM first.
Feed `(100, +1)`, `(200, +1)`, then `(200, -1)`.
**Steps:** Catch the exception; then inspect the accumulators if the harness exposes them.
**Expected:** `PRV-3020`. `sums[0]` (the SUM) has already been decremented to
`100 + 200 - 200 = 100` because SUM's arm ran before MIN's threw; `sums[1]` (the MIN) still holds
100. The operator is left in a half-applied state. Record whether the lane's failure handling
discards the operator or leaves it serving. Then reorder to `MIN` first and confirm SUM is *not*
decremented — same query, different partial state, decided by column order.
**Vacuity:** V2 — the two orderings must be run and compared; one ordering alone cannot show the
partial application.

### AGG-089 — Windowed net-zero: a key whose weights cancel inside a window is not emitted
**Intent:** `SlicedAggregateState.fire` skips `accumulator.count != 0` — "a key whose weights cancel
to zero within the window has no rows in it".
**Falsifier:** A row is emitted for the cancelled key.
**Setup:** HA, one tumbling window, keyed by `user_id`.
**Steps:** Feed `(u1, 100, +1)`, `(u2, 50, +1)`, `(u1, 100, -1)`; `advanceWatermark` past the window
end; collect every emitted row and read the slice count.
**Expected:** one output row, u2 → `COUNT(*) = 1`, `SUM = 50`. u1 absent. Note the `(u1, slice)`
accumulator still exists in `slices` until `discardSlicesEndingBefore` runs; assert the slice count
is 2 at fire time.
**Vacuity:** V3 — assert u1's accumulator existed before the cancelling row, so the absence is a
decision.

### AGG-090 — A windowed retraction after the window fired is routed to the late output
**Intent:** Fact 9. Allowed lateness is 0, and `process` rejects on
`lastWindowEnd + 0 <= watermark` — the same condition that fired the window. So the correction path
(`dirty`, `emitted`, `corrections()`) cannot be reached through SQL, and a retraction arriving after
the fire is counted as *late* rather than applied.
**Falsifier:** A retraction after the fire produces a `-1` output row.
**Setup:** HA, W1 = TUMBLE 10 SECOND, default allowed lateness of 0.
**Steps:** Feed r1, r3 into W1; `advanceWatermark(10s)` — W1 fires; feed ρ3; `advanceWatermark(20s)`; read
`lateRecords()` and `corrections()`.
**Expected:** W1 fires once with `COUNT(*) = 2`, `SUM = 300`. ρ3 is rejected: `lateRecords()` becomes
1, `corrections()` stays 0, no retraction is emitted, and the standing answer of 300 is now wrong by
200 with nothing on the wire to say so.
**Vacuity:** V1 — three rows fed. V2 — `lateRecords() == 1` distinguishes "rejected as late" from
"accepted and produced nothing".

### AGG-091 — The correction path, reached with a non-zero allowed lateness
**Intent:** The path AGG-090 proves unreachable through SQL is reachable by constructing
`WindowedAggregateOperator` directly. Establish that it works, so the finding is "the default hides
a working mechanism" rather than "the mechanism is broken".
**Falsifier:** No retraction row, or a retraction carrying the wrong previous values.
**Setup:** HA, `WindowedAggregateOperator` built with `allowedLatenessNanos = 5s`.
**Steps:** Feed r1, r3; `advanceWatermark(10s)`; feed ρ3; `advanceWatermark(12s)`.
**Expected:** first fire emits `(W1, n = 2, total = 300)` at weight `+1`. The second advance sees W1
in `dirty` and emits `(W1, n = 2, total = 300)` at weight `-1` followed by
`(W1, n = 2 - 1 = 1, total = 300 - 200 = 100)` at weight `+1`. `corrections() == 1`,
`lateRecords() == 0`. Downstream consolidation leaves one row of `(1, 100)`.
**Vacuity:** V2 — `corrections() == 1` and `lateRecords() == 0`, the exact inverse of AGG-090.

### AGG-092 — An unchanged correction emits nothing
**Intent:** `emitWindow` compares `Arrays.equals(before, result.values())` and skips — "two rows that
consolidate to nothing, which is arithmetically harmless and pure noise on the wire". A late row for
a *different* key must not re-emit the unchanged keys of the same window.
**Falsifier:** Every key of the window is re-emitted when one key changes.
**Setup:** HA, `WindowedAggregateOperator` built with `allowedLatenessNanos = 5s`, keyed by `user_id`.
**Steps:** Feed `(u1, 100)` and `(u2, 50)` into W1; `advanceWatermark(10s)` to fire; feed `(u1, 25, +1)`
at 4s; `advanceWatermark(12s)`; count every emitted row with its weight.
**Expected:** first fire: two rows, u1 → 100, u2 → 50. Correction: u1 retracted at `(1, 100)` and
re-inserted at `(2, 100 + 25 = 125)`; **u2 not emitted at all**, because its values are unchanged.
Total output rows after the correction: 2, not 4.
**Vacuity:** V2 — count the emitted rows exactly; a case asserting only "u1 is 125" passes whether or
not u2 was re-emitted.

---

## §5 — AVG (AGG-093 … AGG-098)

### AGG-093 — Unwindowed `AVG` over integers is truncating division
**Intent:** `sums[i] / counts[i]` in Java truncates toward zero. `GlobalAggregate`'s comment claims
this is "integer division, matching SQL's AVG over an integer column". Verify against SQL's rule and
state which is intended.
**Falsifier:** `mean` comes back as 89.25, or as 90.
**Setup:** HB, D1.
**Steps:** `SELECT SUM(amount) AS t, COUNT(amount) AS c, AVG(amount) AS mean FROM txn`.
**Expected:** `t = 357`, `c = 4`, `mean = 357 / 4 = 89` (exact 89.25, truncated). The SQL standard
says `AVG` over an exact-numeric type is exact numeric and *implementation-defined* in how it
rounds; Postgres returns `89.2500000000000000` (numeric), MySQL `89.25` (decimal), Oracle `89.25`.
Calcite's own `AVG` over INTEGER returns INTEGER and truncates, so Pravaha agrees with Calcite and
disagrees with every database a user is likely to be migrating from. Record that as the finding: the
behaviour is defensible, the documentation says nothing about it, and `SQL_SUPPORT.md` line 112
lists `AVG` as ✅ without qualification.
**Vacuity:** V3 — `t` and `c` in the same row make the arithmetic checkable: `357 / 4` with
truncation is 89, with half-up rounding 89, with ceiling 90 — so pick a case where the three differ.
Use **D16**: amounts `1, 2` → `t = 3`, `c = 2`, truncation 1, half-up 2, ceiling 2. Run D16 as well
and report both.

### AGG-094 — `AVG` over negative integers truncates toward zero, not down
**Intent:** The discriminating case for "truncation". Java's `/` rounds toward zero; a floor
division would give a different answer, and SQL engines disagree with each other here.
**Falsifier:** `mean` equals -2.
**Setup:** HB. **D17**: amounts `-1, -2`.
**Steps:** `SELECT SUM(amount) AS t, COUNT(amount) AS c, AVG(amount) AS mean FROM txn`.
**Expected:** `t = -3`, `c = 2`, `mean = -3 / 2 = -1` in Java (toward zero). Floor division would
give -2; exact is -1.5. Report the observed value.
**Vacuity:** V3 — `t = -3` and `c = 2` in the same row.

### AGG-095 — `AVG` over an empty or all-NULL group returns 0, not NULL
**Intent:** `counts[index] == 0 ? 0 : …`. SQL says `AVG` of no rows is NULL. Zero is the value a
dashboard shows as "no activity" when it means "no data", and the two are different.
**Falsifier:** `mean` comes back NULL.
**Setup:** HB, D2 (all `amount` NULL); and HC, D13's u2 group.
**Steps:** `SELECT COUNT(amount) AS c, AVG(amount) AS mean FROM txn`; then the keyed form.
**Expected (SQL):** `c = 0`, `mean = NULL`. **Expected (this build):** `c = 0`, `mean = 0` globally;
and in the keyed form u2 reads `c = 2` (AGG-013) with `mean = 0` — a group reporting two values
averaging zero where it has no values.
**Vacuity:** V3 — `COUNT(*)` in the same row proves the group is non-empty in rows while empty in
values.

### AGG-096 — Windowed `AVG` emits the sum, undivided
**Intent:** `WindowedAggregate`'s kind mapping is `case SUM, AVG -> SlicedAggregateState.Kind.SUM`,
and `SlicedAggregateState` has no AVG kind and no divisor. Nothing anywhere divides. This is a
silently wrong number in the operator the product is sold on.
**Falsifier:** W1/u1's `mean` comes back 150.
**Setup:** HB, D1.
**Steps:** `SELECT window_start, window_end, user_id, COUNT(amount) AS c, SUM(amount) AS t,
AVG(amount) AS mean FROM TABLE(TUMBLE(…10 SECOND…)) GROUP BY window_start, window_end, user_id`.
**Expected (correct):** W1/u1 → `c = 2`, `t = 300`, `mean = 300 / 2 = 150`.
**Expected (this build):** W1/u1 → `t = 300` and `mean = 300`. W1/u2 → `t = 50`, `mean = 50` —
correct by coincidence, because `c = 1`. W4/u3 → `t = 7`, `mean = 7`, also coincidentally right.
**Only the multi-row group discriminates**, which is why a demo with one row per key would never
show it.
**Vacuity:** V2 — `t == mean` for a group with `c = 2` is the observation; assert the equality
explicitly. V3 — u1 must have two non-null amounts, confirmed by `SUM = 300 = 100 + 200`.

### AGG-097 — Windowed `AVG` alone, with no `SUM` beside it
**Intent:** AGG-096 uses `SUM` as its control, which raises the question of whether the two calls
share a column. They do not — `kinds[]` has one entry per call — so `AVG` alone must still emit the
sum.
**Falsifier:** `mean` is correct when `SUM` is not also selected.
**Setup:** HB, D1.
**Steps:** `SELECT window_start, user_id, AVG(amount) AS mean FROM TABLE(TUMBLE(…)) GROUP BY
window_start, window_end, user_id`.
**Expected:** W1/u1 → `mean = 300`, where the correct answer is 150.
**Vacuity:** V3 — cross-check against AGG-096's run, which establishes `SUM = 300` for the same group.

### AGG-098 — `AVG` and `COUNT(col)` in one windowed row: two independent defects
**Intent:** The compound case. W1/u1 carries a COUNT that counts NULLs (AGG-017) and an AVG that is
not divided (AGG-096), and the two together produce a row where no pair of columns is consistent.
**Falsifier:** Any two of the four columns are mutually consistent.
**Setup:** HB, D1.
**Steps:** As AGG-096, with `COUNT(*) AS a` added.
**Expected:** W1/u1 → `a = 3`, `c = 3`, `t = 300`, `mean = 300`. Correct: `a = 3, c = 2, t = 300,
mean = 150`. Check: `t / c = 100`, `mean = 300`, `t / a = 100` — three different candidate averages
in one row, none of them the right one.
**Vacuity:** V2 — report all four numbers. A case reporting only "AVG is wrong" loses the fact that
`COUNT` is wrong in the same row and by a different mechanism.

---

## §6 — Query shape (AGG-099 … AGG-110)

### AGG-099 — Five aggregates in one query, one row, all hand-checked
**Intent:** `calls` is a list and every accumulator is indexed by position. A mis-indexed call writes
one aggregate's value into another's column, and every number is individually plausible.
**Falsifier:** Any column holds another column's value.
**Setup:** HB, D1.
**Steps:** `SELECT COUNT(*) AS a, SUM(amount) AS b, MIN(amount) AS c, MAX(amount) AS d,
AVG(amount) AS e FROM txn`.
**Expected:** `a = 5`, `b = 357`, `c = 7`, `d = 200`, `e = 89`. Five distinct values by design — no
two of them equal — so a transposition is visible.
**Vacuity:** V2 — the five values must be pairwise distinct; a fixture where two coincide cannot
detect a swap.

### AGG-100 — Ten aggregates in one query
**Intent:** Depth beyond the two or three a demo uses. `sums`, `counts` and `seen` are sized from
`operator.aggregates().size()`; the output layout from `outputSchema`. A mismatch between the two is
an off-by-one that only appears past a certain width.
**Falsifier:** Any column is wrong or the row fails to write.
**Setup:** HB, D1.
**Steps:** `SELECT COUNT(*) AS c1, COUNT(amount) AS c2, COUNT(tier) AS c3, SUM(amount) AS s1,
SUM(txn_id) AS s2, MIN(amount) AS m1, MAX(amount) AS m2, MIN(txn_id) AS m3, MAX(txn_id) AS m4,
AVG(amount) AS a1 FROM txn`.
**Expected:** `c1 = 5`, `c2 = 4`, `c3 = 4`, `s1 = 357`, `s2 = 1+2+3+4+5 = 15`, `m1 = 7`, `m2 = 200`,
`m3 = 1`, `m4 = 5`, `a1 = 89`.
**Vacuity:** V2 — ten distinct values where possible; report every one.

### AGG-101 — The same aggregate written twice
**Intent:** `SELECT COUNT(*) AS a, COUNT(*) AS b` — Calcite may collapse the two into one
`AggregateCall` and project it twice, or keep two. Which it does decides whether `aggregates().size()`
is 1 or 2 and whether the output layout has two columns for one accumulator.
**Falsifier:** The two columns differ.
**Setup:** HB then HD, D1.
**Steps:** Run it; then `pravaha explain` to see how many calls the aggregate holds.
**Expected:** `a = 5`, `b = 5`. `explain` shows `Aggregate(group=[], [COUNT(a)])` — one call — with a
projection duplicating it, or two calls. Record which; if two, the accumulator arrays are sized 2 and
both are updated identically.
**Vacuity:** V2 — add `COUNT(amount) AS c = 4` so the row is not three identical numbers.

### AGG-102 — The same aggregate twice with different aliases over different columns
**Intent:** Two calls of the same kind over *different* arguments, which cannot be collapsed. The
per-call `argumentOrdinal` must be honoured independently.
**Falsifier:** Both columns report the same count.
**Setup:** HB, D1.
**Steps:** `SELECT COUNT(amount) AS a, COUNT(tier) AS b, COUNT(*) AS c FROM txn`.
**Expected:** `a = 4` (r4 excluded), `b = 4` (r2 excluded), `c = 5`. `a` and `b` are equal by
coincidence and exclude *different* rows; add `SUM(amount) AS d = 357` and confirm.
**Vacuity:** V3 — the two excluded rows are different, which the per-group form (AGG-010, AGG-014)
makes visible; run the grouped variant too.

### AGG-103 — An aggregate over an expression: `SUM(amount * 2)`
**Intent:** The expression becomes a `ComputeOperator` below the aggregate. `windowBelow` walks
through `ComputeOperator` specifically because omitting it made `SUM(amount * 2) … GROUP BY
window_start` refuse as unbounded — a correct-looking refusal for an ordinary query. Re-establish it.
**Falsifier:** The windowed form is refused with `PRV-2050`.
**Setup:** HB, D1.
**Steps:** (a) global: `SELECT SUM(amount * 2) AS t FROM txn`. (b) windowed:
`SELECT window_start, SUM(amount * 2) AS t FROM TABLE(TUMBLE(…)) GROUP BY window_start, window_end`.
**Expected:** (a) `t = (100 + 50 + 200 + 7) × 2 = 357 × 2 = 714`. (b) W1 →
`(100 + 50 + 200) × 2 = 700`; W4 → `7 × 2 = 14`. Both plan.
**Vacuity:** V2 — the unmultiplied query in the same session gives 357 / 350 / 7, so the doubling is
observed and not assumed.

### AGG-104 — An aggregate over an expression that can be NULL
**Intent:** `amount * 2` where `amount` is NULL is NULL, so the computed column carries the null
through and the aggregate's guard must see it on the *computed* ordinal, not the original.
**Falsifier:** `COUNT(amount * 2)` differs from `COUNT(amount)`.
**Setup:** HB, D1.
**Steps:** `SELECT COUNT(amount) AS a, COUNT(amount * 2) AS b, SUM(amount * 2) AS c FROM txn`.
**Expected:** `a = 4`, `b = 4`, `c = 714`. r4's NULL propagates through the multiplication.
**Vacuity:** V2 — `COUNT(*) = 5` in the same row.

### AGG-105 — An aggregate over an expression spanning two columns
**Intent:** `SUM(amount + txn_id)`, where one operand is NULL in one row. Three-valued arithmetic
inside the aggregate's argument.
**Falsifier:** The NULL row contributes the non-null operand.
**Setup:** HB, D1.
**Steps:** `SELECT COUNT(amount + txn_id) AS n, SUM(amount + txn_id) AS t FROM txn`.
**Expected:** r4 has `amount` NULL so `amount + 4` is NULL and is excluded. `n = 4`;
`t = (100+1) + (50+2) + (200+3) + (7+5) = 101 + 52 + 203 + 12 = 368`.
**Vacuity:** V3 — `COUNT(*) = 5` and `n = 4` prove one row was excluded.

### AGG-106 — An aggregate with no `GROUP BY` over a stream: admitted, not refused
**Intent:** The brief's "an aggregate with no GROUP BY over a stream (refused)" is the *expected*
behaviour; the code admits it (`groupKeys.isEmpty()` skips the `PRV-2050` check) and then never
emits (AGG-001). Establishing which of the two is true is the case.
**Falsifier:** The registration is refused at plan time.
**Setup:** HD then HC.
**Steps:** `pravaha explain --sql "SELECT COUNT(*) AS n FROM txn"`; then register it against an
endless source.
**Expected:** plans successfully as `Aggregate(group=[], [COUNT(n)])`, "bounded by construction —
one row, whatever the input volume". Registered, it runs and emits nothing for ever (AGG-001). The
refusal the brief anticipates does not exist; what exists is a silent non-answer. Record that as the
finding and cross-reference AGG-001.
**Vacuity:** V1 for the registered run — ROWS IN must be non-zero.

### AGG-107 — `HAVING` on a windowed aggregate
**Intent:** `SQL_SUPPORT.md` lines 96 and 115 list `HAVING` as ✅, "a filter above the aggregate".
A filter above the aggregate sees the aggregate's output row, so it filters whole groups.
**Falsifier:** A group failing the predicate appears, or a passing group is dropped.
**Setup:** HB, D1.
**Steps:** `SELECT window_start, user_id, SUM(amount) AS t FROM TABLE(TUMBLE(…10 SECOND…))
GROUP BY window_start, window_end, user_id HAVING SUM(amount) > 100`.
**Expected:** W1/u1 has `t = 300 > 100` → kept. W1/u2 has `t = 50` → dropped. W4/u3 has `t = 7` →
dropped. **One** output row.
**Vacuity:** V2 — the same query without `HAVING` must return three rows, so the filter is observed
to remove two and not to have found nothing.

### AGG-108 — `HAVING` on a keyed read, including on `COUNT`
**Intent:** `HAVING COUNT(*) > 1` over a view read, on the operator whose `COUNT` counts NULLs
(AGG-013). A wrong COUNT changes which groups survive a HAVING, so the defect propagates from a
value into a row set.
**Falsifier:** The group set matches the correct-COUNT answer.
**Setup:** HC, D1, `v_txn`.
**Steps:** `SELECT user_id, COUNT(amount) AS n FROM v_txn GROUP BY user_id HAVING COUNT(amount) > 1`.
**Expected (correct):** u1 only (`COUNT(amount) = 2 > 1`); u2 and u3 have 1 each.
**Expected (this build):** u1 only as well — `3 > 1` is still true — so this input does not
discriminate. Use **D18**: `(u1, 100), (u1, NULL), (u2, 50)`. Correct: u1 → `COUNT(amount) = 1`, u2
→ 1, so `HAVING COUNT(amount) > 1` returns **no rows**. This build: u1 → 2, so it returns **one
row**, and a filter designed to exclude single-value groups admits one. Report D18's result.
**Vacuity:** V2 — run without `HAVING` first and record all groups and counts; V3 — confirm u1's
NULL row is in the view.

### AGG-109 — `HAVING` with a parameter
**Intent:** `SQL_SUPPORT.md` line 221: `?` placeholders are supported in `WHERE` and `HAVING` and
nowhere else, and line 159 lists parameters as supported on the keyed read path.
**Falsifier:** The parameter is refused in `HAVING`, or bound as a literal query fragment.
**Setup:** HC, D1, `v_txn`.
**Steps:** `pravaha query --sql "SELECT user_id, SUM(amount) AS t FROM v_txn GROUP BY user_id
HAVING SUM(amount) > ?" --param 100`.
**Expected:** u1 → `t = 300 > 100` kept. u2 → 50, u3 → 7, dropped. One row. Re-run with `--param 0`:
three rows. Re-run with `--param 1000`: zero rows.
**Vacuity:** V2 — the three parameter values must give 1, 3 and 0 rows respectively; one run cannot
show the parameter is bound at all.

### AGG-110 — `HAVING` referring to an aggregate not in the `SELECT` list
**Intent:** Ordinary SQL, and it forces Calcite to add an aggregate call the projection then drops —
so `aggregates().size()` exceeds the visible column count and the output layout must still line up.
**Falsifier:** The wrong groups survive, or the output row carries the hidden aggregate's value.
**Setup:** HB, D1.
**Steps:** `SELECT window_start, user_id, COUNT(*) AS n FROM TABLE(TUMBLE(…10 SECOND…))
GROUP BY window_start, window_end, user_id HAVING SUM(amount) > 100`.
**Expected:** W1/u1: `SUM = 300 > 100`, so kept with `n = 3`. W1/u2: `SUM = 50`, dropped.
W4/u3: `SUM = 7`, dropped. One row: `(0s, u1, 3)`. The `n` column must be 3, not 300 — a
transposition would put the hidden SUM where the COUNT belongs and 300 is a plausible-looking count.
**Vacuity:** V2 — the same query without `HAVING` returns three rows with `n` of 3, 1, 1, so the
surviving row's `n` is known to be 3 independently.

---

## Coverage note

The budget of 110 is met exactly, and the grid it was set against is larger than 110: 6 kinds × 3
operators × 16 types × 7 shapes is 2 016 cells. Three compressions were made, each recorded so a
later wave can expand the right one.

1. **Types are enumerated where the code branches and collapsed where it does not.** `getLong` and
   `setLong` treat INT64, TIME and TIMESTAMP_LTZ identically and everything else as an error, so §2
   is organised by the *four* outcomes the code can produce rather than by sixteen type names —
   with every type named in at least one case, and AGG-062 tabulating all 96 kind × type cells from
   the cases that established them.
2. **The seven shapes are enumerated fully for INT64 and sampled for the rest.** The shape dimension
   interacts with the *operator*, not with the type: `seen[]`, `rowCount == 0` and the weight-0
   guard behave the same whatever the column holds. §3 and §4 therefore run all seven shapes against
   INT64 on all three operators — 21 combinations, covered by AGG-063 … AGG-092 — and §2 runs one
   shape per type.
3. **`COUNT(DISTINCT)` is given 13 cases rather than the 3 its share of the grid implies**
   (AGG-012, AGG-020 … AGG-028, AGG-031, AGG-032, AGG-037, AGG-050). It has three different
   implementations across three operators, one of which is a refusal and two of which read the
   argument with the wrong accessor, and it is the aggregate the documentation puts forward as the
   reason to use a window at all. Three cases would have found the refusal and missed both wrong
   readers.

Two things this area cannot establish and hands on:

- **Whether any of this is observable on the continuous path.** Facts 1 and 9 mean an unwindowed
  aggregate emits nothing continuously and a windowed one cannot be corrected, so every unwindowed
  case here runs on a bounded input and the streaming delivery of an aggregate result belongs to
  `STRM`. `AGG-001` is the handover point.
- **Whether the numbers survive a restart.** `WindowedAggregate.writeTo`/`readFrom` round-trip the
  accumulators *and* the `emitted` map, and `SlicedAggregateState` refuses a checkpoint whose kinds
  or column count differ. Both belong to `STATE`; this area asserts only what a single run produces.

Finally, one case in this file is deliberately marked `NOT DISCRIMINATING` (AGG-032) rather than
dropped. It is the query the documentation recommends, it agrees with the defect, and recording that
agreement is the explanation for why AGG-020's defect was never found.
