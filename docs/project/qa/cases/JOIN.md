# JOIN — inner equi-join, lookup join, lane routing, eviction, and the refusals

**Area:** `JOIN` · **IDs:** JOIN-001 … JOIN-060 · **Budget:** 60 · **Status:** authored, not executed.

Two operators, and they are not variants of one thing. `SymmetricHashJoin` holds both sides in state
and grows with both; `LookupJoin` holds nothing and asks a store one key at a time. Everything below
— which refusals apply, whether a `LEFT` is legal, how state is bounded, what a watermark does —
follows from that split.

Surface under test: `pravaha-runtime/.../exec/SymmetricHashJoin.java`, `JoinSide.java`,
`JoinKeys.java`, `LookupJoin.java`; `pravaha-runtime/.../plan/JoinOperator.java`,
`LookupJoinOperator.java`; `pravaha-sql/.../plan/PhysicalPlanBuilder.buildJoin`,
`collectEquiKeys`, `collectTimeBound`, `buildLookupJoin`, `collectLookupKeys`;
`QueryExecution.pumpPartitionedInto`, `joinKeyOrdinalsFor`, `mapDownToScan`,
`refuseUnpartitionedJoin`; `InterpretedPipeline`'s self-join check.
Documentation under test: `docs/SQL_SUPPORT.md` §Joins (lines 169–199) and `docs/guides/USER_GUIDE.md:212`.

---

## Read before running anything: six facts about what is reachable

Established by reading the sources at the commit under test. Each is a case below. Together they
decide the harness for every other case in this file, and two of them are findings in their own
right.

1. **`pravaha run` cannot run a join at all.** `RunCommand` takes one `--stream`, one `--schema`,
   one `--in`; `QueryRunner.run` opens one `FilesystemSourcePlugin` and calls `pumpInto` once.
   A two-stream query planned against a single-stream catalog fails to resolve the second table.
   (JOIN-001)
2. **`SqlPlanner.withLookups` has no production caller.** Every shipped surface builds its catalog
   with `withStreams`: `QueryRunner:98`, `ValidateCommand:52`, `ExplainCommand:59`,
   `ViewQuery:302`, and `QueryController.plannerFor():122`
   (`SqlPlanner.withStreams(catalog.all().toArray(...))`). `PravahaSchema.registerLookup` is called
   from `SqlPlanner.withLookups` and nowhere else. So `table.isLookup()` is false for every table a
   configured node knows, and `buildLookupJoin` always reaches the "registered as a stream, not as a
   lookup table" refusal. `FOR SYSTEM_TIME AS OF` is a documented ✅ that cannot be planned on any
   shipped surface — not merely unconfigurable, unreachable. (JOIN-044, JOIN-045)
3. **Even a planned lookup join could not be started by the registry.**
   `QueryRegistry:530` calls `QueryExecution.start(plan, 1, laneConfig, access, sink)` — the
   five-argument overload, whose `lookups` map is `Map.of()`. `InterpretedPipeline.compile` fails at
   start-up for a plan naming a table that map does not hold, which its javadoc says is deliberate.
   Two independent blocks, either of which alone makes the feature unreachable. (JOIN-046)
4. **Multi-lane joins are programmatic-only, for the same shape of reason.**
   `QueryRegistry:530` hard-codes **1** lane, and `PluginSourceFeeds:116` calls
   `execution.pumpInto(0, stream, reader, policy)` — lane 0, never `pumpPartitionedInto`. So the
   routing machinery in §3 is reachable from tests and embedded use and from no configured node.
   The refusal that exists to protect it (`refuseUnpartitionedJoin`) therefore cannot fire on a
   server either. (JOIN-035)
5. **The default match window is one hour of event time and is invisible in the query.**
   `JoinOperator.DEFAULT_MATCH_WITHIN_NANOS = 3_600e9`, applied whenever the ON condition states no
   time bound, giving bounds `[-1h, +1h]`. It decides which pairs are in the answer, not only how
   long state is kept — `emit` returns false and increments `outsideWindow()` for a pair outside it.
   (JOIN-023, JOIN-024)
6. **A NULL join key is never stored and never probed.** `JoinSide.add` returns early on
   `!JoinKeys.isMatchable(row, keyOrdinals)`, and `forEachMatch` returns early on the same test. For
   an inner join that is exactly SQL. For a `LEFT` join it is not: a null-keyed left row is not in
   state when eviction runs, so it is never emitted null-padded, and SQL says it must be.
   (JOIN-020, JOIN-021)

## Harnesses

| | What | Why it is needed |
|---|---|---|
| **HJ1** | In-process, the shape of `pravaha-it/.../JoinOnLanesTest`: `new PhysicalPlanBuilder().build(SqlPlanner.withStreams(orders(), users()).plan(SQL))`, then `QueryExecution.start(plan, N, config(), MemoryAccess.best(), () -> collector)`, `pumpInto(lane, "orders", reader, policy)` or `pumpPartitionedInto("orders", reader, policy)`, `advanceWatermark(t)`, `awaitQuiescent`. | The only harness in which a stream-to-stream join runs at all (facts 1 and 4), the only one that can set a lane count, and the only one that can drive a watermark to an exact instant. |
| **HJ2** | HJ1 with `SqlPlanner.withLookups(orders(), users())` and `QueryExecution.start(plan, 1, config, access, sink, Map.of("users", plugin))` — the six-argument overload. | The only harness in which a lookup join can be planned (fact 2) and started (fact 3). |
| **HJ3** | A node with two streams declared under `pravaha.streams.*` and bound under `pravaha.sources.*`, then `pravaha register --name v --sql <join> --keys K` and `pravaha query`. | The shipped surface. Used to establish what a *user* gets, which for several cases is a refusal the programmatic path never sees. |
| **HJ4** | Plan-only: `pravaha explain --sql S --schema T`, or `SqlPlanner.plan` + `PhysicalPlanBuilder.build` directly. | Every refusal. `explain` takes one `--schema`, so a two-stream refusal is tested through the planner API rather than the CLI; record that limitation where it bites. |
| **HJ5** | Operator-level: `new SymmetricHashJoin(plan, arena, downstream, maxStateSlabs)` fed by a `BinaryRowWriter` per row, with `advanceWatermark` called by hand. | Needed for weights other than `+1`, for NULL keys in a chosen column, and for reading `pairsEmitted()`, `outsideWindow()`, `evicted()`, `rowsHeldLeft/Right()`, `keysHeldLeft/Right()`, `unmatchedEmitted()`. |

## Standing fixtures

```
stream orders : order_id INT64, user_id STRING, region STRING, amount INT64, event_time TIMESTAMP
                -- 5 columns, so a right-side column i is output column 5 + i
stream users  : user_id STRING, region STRING, tier STRING, event_time TIMESTAMP
                -- 4 columns; joined output is 9 columns wide
lookup dim    : user_id STRING, tier STRING      -- reachable only via registerLookup
SECOND = 1_000_000_000 nanos.   HOUR = 3_600 × SECOND = the default match window.
```

**J1** — canonical `orders`, all weight `+1`:

| ref | order_id | user_id | region | amount | event_time |
|---|---|---|---|---|---|
| o1 | 1 | u1 | eu | 100 | 1s |
| o2 | 2 | u2 | eu | 50 | 2s |
| o3 | 3 | u1 | us | 200 | 3s |
| o4 | 4 | u9 | eu | 7 | 4s |

**J2** — canonical `users`, all weight `+1`:

| ref | user_id | region | tier | event_time |
|---|---|---|---|---|
| x1 | u1 | eu | gold | 0s |
| x2 | u2 | eu | silver | 0s |
| x3 | u3 | us | bronze | 0s |

**Hand-computed truth.** `SQL_J1` = `SELECT * FROM orders o JOIN users u ON o.user_id = u.user_id`:
o1×x1, o2×x2, o3×x1 — **3 pairs**. o4 (u9) and x3 (u3) match nothing. Every delta is within the
default hour (`|1s - 0s| = 1s`, `|2s - 0s| = 2s`, `|3s - 0s| = 3s`), so all three survive
`matchesInTime`.

`SQL_J2` = the same with `AND o.region = u.region`: o1×x1 (u1/eu = u1/eu ✓), o2×x2 (u2/eu ✓),
o3×x1 (u1/us vs u1/eu ✗) — **2 pairs**.

Duplicate-key variants: **J2d** adds x1b = `(u1, eu, platinum, 0s)`; **J1d** adds
o1b = `(5, u1, eu, 100, 5s)`.

## Vacuity kit

Run in the same process as every case. A case that cannot show all four is `INCONCLUSIVE`, not
`PASS`.

- **W1 — both sides arrived.** `rowsHeldLeft()` and `rowsHeldRight()` (HJ5), or the two pumps'
  counts (HJ1), are non-zero and equal what was fed. An empty output from a join whose right side
  never arrived is the single commonest false pass in this area: it looks exactly like "no rows
  matched", which is JOIN-015's expected result.
- **W2 — the join ran.** `pairsEmitted()` is asserted as a number, never as "> 0". A case expecting
  3 pairs must fail on 2 and on 4.
- **W3 — the non-matching rows are present.** o4 and x3 exist in J1/J2 precisely so that "3 pairs"
  is a statement about selection rather than about how many rows were fed. Assert their presence.
- **W4 — the timing bound was reached.** For every eviction and window case, assert
  `outsideWindow()` or `evicted()` explicitly. A pair that is absent because the source ran dry and
  a pair that is absent because the watermark evicted it are the same observation without this.

---

## §0 — What is reachable (JOIN-001 … JOIN-006)

### JOIN-001 — `pravaha run` cannot run a join
**Intent:** Establish, before anything else, that the cheapest CLI surface is not available here —
so no later case may quietly assume it.
**Falsifier:** A two-stream join runs and writes pairs.
**Setup:** HJ4/CLI. `orders.csv` holding J1.
**Steps:** `pravaha run --sql "SELECT o.order_id, u.tier FROM orders o JOIN users u ON
o.user_id = u.user_id" --stream orders --schema <orders schema> --in orders.csv
--out-schema "order_id:INT64,tier:STRING" --out o.csv`.
**Expected:** a planning failure — `users` is not in the catalog, which
`SqlPlanner.withStreams(sourceSchema)` built from the single `--stream`. Record the code and whether
the message says "Known streams: [orders]"; FINDINGS Q-13 has that list omitting configured streams
elsewhere. `RunCommand` has no second `--stream` flag, so there is no invocation that would work.
**Vacuity:** n/a — an unreachability result.

### JOIN-002 — A two-stream join registered on a node does run
**Intent:** The counterpart. `PluginSourceFeeds` opens one feed per distinct stream the plan reads,
so HJ3 is a real surface even though HJ1 is the one most cases use.
**Falsifier:** Registration is refused, or the view stays empty with both feeds reporting rows.
**Setup:** HJ3. `orders` and `users` declared and bound to two feedfiles holding J1 and J2.
**Steps:** `pravaha register --name v_join --sql SQL_J1 --keys 0`; wait for `pravaha queries` to
show ROWS IN 7; `pravaha query --sql "SELECT * FROM v_join"`.
**Expected:** ROWS IN `4 + 3 = 7`. The view holds **3** rows: o1×x1, o2×x2, o3×x1. Each row is 9
columns: orders' five then users' four, so `o.order_id` is column 0 and `u.tier` is column 7.
**Vacuity:** W1 — ROWS IN 7, not 4 and not 3. W3 — assert o4 and x3 were among the seven fed.

### JOIN-003 — The output schema is left columns then right columns
**Intent:** `JoinOperator.leftWidth()` is the whole ordinal contract downstream: "a right-side column
`i` is output column `leftWidth + i`". A transposition here produces rows that are individually
plausible and wrong in every column.
**Falsifier:** Any output column holds a value from the other side.
**Setup:** HJ1, J1/J2, `SELECT * FROM orders o JOIN users u ON o.user_id = u.user_id`.
**Steps:** Read the first pair's nine columns.
**Expected:** o1×x1 → `[1, u1, eu, 100, 1s, u1, eu, gold, 0s]`. Columns 0–4 are o1 verbatim;
columns 5–8 are x1 verbatim. Column 2 (`o.region`) and column 6 (`u.region`) both hold `eu` — use
o3×x1 as the discriminating row instead: `[3, u1, us, 200, 3s, u1, eu, gold, 0s]`, where column 2 is
`us` and column 6 is `eu`.
**Vacuity:** W2 — three pairs; assert the o3 row specifically, since it is the only one whose two
region columns differ.

### JOIN-004 — A joined row's event time is the later of the two
**Intent:** `emit` writes `Math.max(left.eventTimestampNanos(), right.eventTimestampNanos())`
"because a pair is not complete until both halves have arrived and claiming otherwise would let a
window close over a row it had not yet seen". Sequence likewise.
**Falsifier:** The pair carries the left row's time when the right row is later, or vice versa.
**Setup:** HJ5, J1/J2.
**Steps:** Emit o1×x1 (left 1s, right 0s) and a second pair with the right side later: feed
x1' = `(u1, eu, gold, 9s)` and o1 at 1s.
**Expected:** o1×x1 carries `max(1s, 0s) = 1s`. o1×x1' carries `max(1s, 9s) = 9s`. Sequence is
`max(left.sequence, right.sequence)` on both.
**Vacuity:** W2 — both pairs emitted; the two cases must give different answers, or the `max` is
not being exercised.

### JOIN-005 — A pair's weight is the product of the two rows' weights
**Intent:** "An arriving row carries a weight; a stored row carries a weight; the pair's weight is
their product." This is what makes a retraction need no retract path, and it is one multiplication
that can be an addition without anybody noticing at weight 1.
**Falsifier:** A `-1` left row against a `+1` stored right emits `+1`, or `2 × 3` emits 5.
**Setup:** HJ5. Feed x1 at weight `+1`; then o1 at `+1`, `-1`, `+2`, and a right row at `+3`.
**Steps:** Record each emitted pair's weight.
**Expected:** `+1 × +1 = +1`; `-1 × +1 = -1`; `+2 × +1 = +2`; and with the right side at `+3`,
`+2 × +3 = +6`. A weight of 0 on either side emits nothing — `emit` returns false on
`weight == 0` before allocating.
**Vacuity:** W2 — `pairsEmitted()` counts only the non-zero ones; assert it is 4, not 5.

### JOIN-006 — The join ceiling refuses loudly rather than evicting
**Intent:** `checkCeiling` throws `PRV-4001` past `maxRowsPerSide` (1 000 000 from
`MAX_JOIN_ROWS_PER_SIDE`). "Evicting to stay under a row ceiling would drop rows the query did ask
about, so that ceiling fails loudly instead" — the distinction between a semantic bound and an
operational one, which is the whole design of §2.
**Falsifier:** Rows past the ceiling are silently dropped and the join continues.
**Setup:** HJ5, `JoinOperator` constructed with `maxRowsPerSide = 3`.
**Steps:** Feed four distinct left keys with no matching right rows.
**Expected:** `PRV-4001` on the fourth: "the left side of Join[user_id = user_id, left - right in [-3600s, 3600s]] holds 4 rows, past
the ceiling of 3. Both sides of a stream-to-stream join keep every row that could still match…".
The message names the side, the count and the ceiling. Note the check runs *after* the row is added,
so the reported count is ceiling + 1.
**Vacuity:** W1 — `rowsHeldLeft() == 3` before the fourth row.

---

## §1 — Inner equi-join: keys and row shapes (JOIN-007 … JOIN-022)

### JOIN-007 — One key: three pairs from four and three rows
**Intent:** The base case every other case in §1 is a variation of, with the arithmetic written out.
**Falsifier:** Any count other than 3, or any pair not in the hand-computed set.
**Setup:** HJ1, J1 and J2, `SQL_J1`, one lane.
**Steps:** Feed all of J2, then all of J1; `awaitQuiescent`; collect.
**Expected:** 3 pairs: (o1,x1), (o2,x2), (o3,x1). o4's key `u9` matches no stored user; x3's key
`u3` is matched by no order. `pairsEmitted() == 3`, `rowsHeldLeft() == 4`, `rowsHeldRight() == 3`,
`keysHeldLeft() == 3` (u1, u2, u9), `keysHeldRight() == 3` (u1, u2, u3).
**Vacuity:** W1, W2, W3 all four.

### JOIN-008 — Two keys: the second key removes a pair
**Intent:** `collectEquiKeys` recurses through `AND` and appends a pair per equality. Two keys must
narrow the answer, and the pair they remove must be the hand-identified one.
**Falsifier:** Three pairs, or two pairs that are not (o1,x1) and (o2,x2).
**Setup:** HJ1, J1/J2, `SQL_J2`.
**Steps:** As JOIN-007.
**Expected:** 2 pairs. o3 is `(u1, us)` and x1 is `(u1, eu)`, so the composite keys differ and the
pair that JOIN-007 emitted is gone. `leftKeys = [1, 2]`, `rightKeys = [0, 1]` — assert those in
`explain`, because a mis-paired list joins `user_id` to `region` and returns nothing, which looks
exactly like no data matching.
**Vacuity:** W2 — exactly 2; the JOIN-007 control in the same session gives 3, so the extra key is
observed to remove one pair.

### JOIN-009 — Three keys
**Intent:** Beyond the two-key case a demo uses. `leftKeys.size() != rightKeys.size()` is refused in
the record's compact constructor; equal sizes at three is untested territory.
**Falsifier:** Anything other than the hand-computed pair set.
**Setup:** HJ1 with an extra INT64 column `seg` on both streams. J1 rows carry `seg` 1,1,2,1;
J2 rows carry 1,1,1.
**Steps:** `ON o.user_id = u.user_id AND o.region = u.region AND o.seg = u.seg`.
**Expected:** o1(u1,eu,1)×x1(u1,eu,1) ✓; o2(u2,eu,1)×x2(u2,eu,1) ✓; o3(u1,us,2) matches nothing.
**2 pairs**, same as JOIN-008 but for a different reason — assert `leftKeys` has three entries.
**Vacuity:** W2 — assert the key list length, since the answer coincides with JOIN-008's.

### JOIN-010 — The keys may be written in either order
**Intent:** `collectEquiKeys` accepts `l.k = r.k` and `r.k = l.k` — "either order: they are the same
join". The ordinal arithmetic (`second - leftWidth`) differs between the two branches and only one
of them is exercised by the obvious test.
**Falsifier:** The reversed form returns a different pair set, or is refused.
**Setup:** HJ1, J1/J2.
**Steps:** Run `ON o.user_id = u.user_id`, then `ON u.user_id = o.user_id`, then the mixed
two-key form `ON u.user_id = o.user_id AND o.region = u.region`.
**Expected:** 3, 3 and 2 pairs respectively — identical to JOIN-007 and JOIN-008. Assert the
emitted rows are identical row for row, not merely equal in count.
**Vacuity:** W2 on each; a comparison of counts alone would miss a swapped ordinal that happens to
produce the same number of pairs.

### JOIN-011 — A composite key whose two columns are the same value
**Intent:** `JoinKeys.hash` mixes field hashes in sequence; a key of `(eu, eu)` is where a
commutative or self-cancelling mix would collapse. `SlicedAggregateState` records exactly this
failure mode for XOR ("a test that passes the same value twice found every group under the key
zero"), so the neighbouring hash is worth the case.
**Falsifier:** `(eu, eu)` and `(us, us)` collide, or `(eu, us)` matches `(us, eu)`.
**Setup:** HJ5. Left rows keyed `(eu, eu)`, `(us, us)`, `(eu, us)`, `(us, eu)`; right rows the same
four.
**Steps:** Feed all right rows, then all left; count pairs.
**Expected:** exactly **4** pairs, one per key — not 16, not 8. `keysHeldRight() == 4`.
**Vacuity:** W2 — four distinct keys must be held; if `keysHeldRight()` is 2 or 3 the hash has
collapsed and the pair count is meaningless.

### JOIN-012 — Key types: STRING, INT64, INT32, INT8, BOOLEAN, DATE, TIME, TIMESTAMP
**Intent:** `JoinKeys.fieldHash` names eight type arms plus a `default ->` that throws
`PRV-3021` "cannot hash a join key of type X". Enumerated, because a join on a narrow integer is
ordinary and untested.
**Falsifier:** Any listed type fails, or any unlisted type is accepted.
**Setup:** HJ1, a pair of streams carrying one column of each type.
**Steps:** Eight joins, one per key type, each over two rows that match and one that does not.
**Expected:** all eight join correctly: 1 pair each, the non-matching row unpaired. Record the
observed behaviour per type in an eight-row table.
**Vacuity:** W3 — the non-matching row must be fed each time; a join returning 1 pair from 1 left
row proves nothing about selection.

### JOIN-013 — A FLOAT32 or FLOAT64 key is refused, at construction
**Intent:** `JoinKeys.checkJoinable` is called from `SymmetricHashJoin`'s constructor, not from the
planner — so the refusal arrives when the pipeline is compiled, which is later than
`checkJoinable`'s own javadoc claims ("Both are refused at plan time, where the query can be
rewritten, rather than at run time, where it cannot"). Pin the disagreement between comment and code.
**Falsifier:** The refusal arrives from `pravaha explain`, or does not arrive at all.
**Setup:** HJ4 then HJ1, streams with a `FLOAT64` key column.
**Steps:** (a) `SqlPlanner.plan` + `PhysicalPlanBuilder.build` — does it succeed? (b)
`InterpretedPipeline.compile` / `QueryExecution.start` on that plan.
**Expected:** (a) plans successfully — nothing in `buildJoin` checks the key type. (b) `PRV-3021`:
"cannot join on 'price' (left): it is FLOAT64, and floating-point equality drops rows that differ
only by rounding. Round or cast to an integer type, or join on a different column." Record that
`explain` shows a plan for a query that cannot be compiled, and that the javadoc says "plan time".
**Vacuity:** n/a.

### JOIN-014 — A DECIMAL key is refused with a thinner message
**Intent:** The second arm of `checkJoinable`. Its message is one clause where the float message is
three, and it offers no remedy — worth recording as an `ERRC` cross-reference.
**Falsifier:** It is accepted, or shares the float message.
**Setup:** As JOIN-013 with a `DECIMAL(18,2)` key column on both sides.
**Steps:** Build the plan, then compile it into a pipeline.
**Expected:** `PRV-3021`: "cannot join on 'price' (left): DECIMAL keys are not supported yet".
Record that it says what is not supported and not what to do instead.
**Vacuity:** n/a.

### JOIN-015 — No matching rows at all
**Intent:** The empty answer, and the case W1 exists to distinguish from a feed that never arrived.
**Falsifier:** Any pair.
**Setup:** HJ1. Left rows keyed `u7, u8, u9`; right rows keyed `u1, u2, u3`.
**Steps:** Feed both; quiesce; collect.
**Expected:** **0** pairs. `rowsHeldLeft() == 3`, `rowsHeldRight() == 3`, `keysHeldLeft() == 3`,
`keysHeldRight() == 3`, `outsideWindow() == 0` — nothing matched on the key, so nothing reached the
time check.
**Vacuity:** W1 is the entire case: assert both sides hold 3 rows. `0 pairs` with
`rowsHeldRight() == 0` is an ingest failure and must be reported as `INCONCLUSIVE`.

### JOIN-016 — Every row matching
**Intent:** The other extreme, and where the cross-product shape of a single hot key shows.
**Falsifier:** Fewer than the hand-computed 9 pairs.
**Setup:** HJ1. Three left rows and three right rows, all keyed `u1`, all within the hour.
**Steps:** Feed the three right rows, then the three left.
**Expected:** `3 × 3 = 9` pairs. Each left row probes three stored right rows.
`keysHeldLeft() == 1`, `keysHeldRight() == 1`, `rowsHeldLeft() == 3`, `rowsHeldRight() == 3`.
**Vacuity:** W2 — exactly 9. Six would mean the third right row arrived after the left rows and the
symmetric half of the rule is missing; see JOIN-017.

### JOIN-017 — Arrival order does not change the answer
**Intent:** The `ΔA⋈ΔB` term is "a consequence of the ordering… whichever lands second finds the
first already in state". Two rows that would have been one batch must match whichever arrives first.
**Falsifier:** The two orders give different pair sets.
**Setup:** HJ1, J1/J2.
**Steps:** Run A: all of J2, then all of J1. Run B: all of J1, then all of J2. Run C: interleaved
o1, x1, o2, x2, o3, x3, o4.
**Expected:** all three give the same 3 pairs, row for row. Note the joined rows' *event times* are
identical across runs (`max` of the two, order-independent) but the emission order differs; compare
as a set.
**Vacuity:** W2 — three pairs in each of the three runs, and the sets compared element-wise.

### JOIN-018 — One side empty
**Intent:** A join whose right side has produced nothing yet is the ordinary state of a freshly
started query, and it must produce no pairs and no error.
**Falsifier:** Any pair, or an exception.
**Setup:** HJ1, J1 on the left, nothing on the right.
**Steps:** Feed J1; quiesce; collect. Then feed J2 and quiesce again.
**Expected:** after the first phase: 0 pairs, `rowsHeldLeft() == 4`, `rowsHeldRight() == 0`. After
the second: **3** pairs — the left rows were held and matched when their partners arrived, which is
the point of holding them.
**Vacuity:** W1 — the two-phase structure is itself the vacuity control: a harness that returned 0
in phase one *and* 0 in phase two would show the left rows were never stored.

### JOIN-019 — Both sides empty
**Intent:** The degenerate case. `evictOlderThan` returns 0 on `buckets.isEmpty()`, and
`advanceWatermark` must not throw over an empty join.
**Falsifier:** Any output, or an exception on `advanceWatermark`.
**Setup:** HJ1, no rows fed.
**Steps:** `advanceWatermark(10s)`; `advanceWatermark(2 × HOUR)`; quiesce; collect.
**Expected:** 0 pairs, `evicted() == 0`, no exception. Assert the run terminates.
**Vacuity:** W1 — assert both pumps reported 0 rows, so the absence of output is a fact about the
join and not about an exception that was swallowed.

### JOIN-020 — A NULL key matches nothing, including another NULL key
**Intent:** SQL's rule, and `JoinKeys.equal`'s javadoc states it: "NULL is not equal to NULL, which
is what SQL says an equi-join means: a row whose key is null joins with nothing, including other
null-keyed rows."
**Falsifier:** Two null-keyed rows produce a pair.
**Setup:** HJ5. Left rows: `(u1)`, `(NULL)`. Right rows: `(u1)`, `(NULL)`.
**Steps:** Feed both right rows, then both left.
**Expected:** **1** pair — u1 × u1. The null-keyed left row is not probed (`forEachMatch` returns
early on `isMatchable`) and not stored (`add` returns early on the same). So
`rowsHeldLeft() == 1` and `rowsHeldRight() == 1`, not 2 each: the null rows are dropped entirely,
which the comment calls "a leak with no possible benefit".
**Vacuity:** W1 — assert two rows were fed to each side while only one is held on each. W3 — the
null rows are the discriminating input and their arrival must be confirmed at the pump.

### JOIN-021 — A composite key with one NULL column is also unmatchable
**Intent:** `isMatchable` tests every ordinal, so `(u1, NULL)` is unmatchable even though its first
column is a real value. Worth a case because "the key is null" and "part of the key is null" read
differently.
**Falsifier:** `(u1, NULL)` matches `(u1, NULL)` or `(u1, eu)`.
**Setup:** HJ5, two-key join. Left: `(u1, eu)`, `(u1, NULL)`. Right: `(u1, eu)`, `(u1, NULL)`.
**Steps:** As JOIN-020.
**Expected:** 1 pair, `(u1, eu) × (u1, eu)`. `rowsHeldLeft() == 1`, `rowsHeldRight() == 1`.
**Vacuity:** W1 as JOIN-020.

### JOIN-022 — Duplicate keys on one side, then on both
**Intent:** The bucket chain. `forEachMatch` walks the chain and calls back per stored row, so
duplicates multiply. Both variants, with the arithmetic.
**Falsifier:** A pair count other than the hand-computed one.
**Setup:** HJ1. Variant (a): J1 + J2d — the right side has two `u1` rows. Variant (b): J1d + J2d —
both sides duplicated.
**Steps:** Run both, single lane, `SQL_J1`.
**Expected (a):** o1 × {x1, x1b} = 2, o2 × x2 = 1, o3 × {x1, x1b} = 2, o4 × {} = 0 → **5** pairs.
`keysHeldRight() == 3`, `rowsHeldRight() == 4`.
**Expected (b):** u1 orders are {o1, o3, o1b} = 3, u1 users are {x1, x1b} = 2 → `3 × 2 = 6`;
plus o2 × x2 = 1 → **7** pairs. `rowsHeldLeft() == 5`, `rowsHeldRight() == 4`.
**Vacuity:** W2 — the base run (JOIN-007, 3 pairs) in the same session, so 5 and 7 are known to be
changes. W1 — the held-row counts distinguish "duplicates arrived" from "duplicates were
consolidated away", which `add` does for *identical* rows by summing weights: assert the duplicate
right rows differ in `tier` so they are distinct rows and not one row of weight 2.

---

## §2 — The match window and eviction (JOIN-023 … JOIN-034)

### JOIN-023 — The default match window is an hour, and it decides the answer
**Intent:** Fact 5. `JoinOperator.DEFAULT_MATCH_WITHIN_NANOS` gives bounds `[-1h, +1h]` when the ON
condition states no time bound, and `emit` drops pairs outside it before writing anything. A user who
wrote a plain equi-join has an hour-wide temporal predicate they never wrote and cannot see.
**Falsifier:** A pair two hours apart is emitted.
**Setup:** HJ5, plain `SQL_J1`. Right row x1 at `0s`; left rows at `1s`, `3600s` (exactly 1h) and
`3601s`.
**Steps:** Feed x1, then the three left rows; read `pairsEmitted()` and `outsideWindow()`.
**Expected:** `1s` → delta `+1s`, inside → emitted. `3600s` → delta `+3 600s = +1h`, and the test is
`delta <= matchUpperNanos` inclusive → emitted. `3601s` → delta `+1h + 1s` → **not** emitted.
`pairsEmitted() == 2`, `outsideWindow() == 1`.
**Vacuity:** W4 — `outsideWindow() == 1` is what distinguishes "rejected by the time bound" from
"never matched on the key"; both give two pairs otherwise.

### JOIN-024 — The default window is symmetric
**Intent:** `[-matchWithin, +matchWithin]`, so a left row an hour *earlier* than its partner also
matches. One-sided testing of a symmetric bound leaves half of it unexercised.
**Falsifier:** A left row 1h before its partner is rejected.
**Setup:** HJ5, plain `SQL_J1`, default hour window. Right row at `7200s`; left rows at `3600s`
(delta `-1h`) and `3599s` (delta `-1h - 1s`).
**Steps:** Feed the right row, then the two left rows; read `pairsEmitted()` and `outsideWindow()`.
**Expected:** `3600s` emitted (`-3 600s >= -3 600s`); `3599s` not. `pairsEmitted() == 1`,
`outsideWindow() == 1`.
**Vacuity:** W4.

### JOIN-025 — A stated `BETWEEN` bound replaces the default and is directional
**Intent:** `collectTimeBound` normalises to `left - right` and `JoinOperator.withinRange` keeps the
direction "because it is part of the answer. 'The payment came after the order' and 'the two were
within five minutes' are different questions."
**Falsifier:** The bound is treated as symmetric.
**Setup:** HJ1/HJ5. `ON o.user_id = u.user_id AND o.event_time BETWEEN u.event_time -
INTERVAL '5' MINUTE AND u.event_time`. Right x1 at `600s`. Left rows at `299s`, `300s`, `600s`,
`601s`.
**Steps:** Feed; read.
**Expected:** bounds are `[-300s, 0]`. `299s` → delta `-301s` → out. `300s` → `-300s` → in.
`600s` → `0` → in. `601s` → `+1s` → out. `pairsEmitted() == 2`, `outsideWindow() == 2`.
**Vacuity:** W4 — `outsideWindow() == 2`, and the two rejected rows are on *opposite* sides of the
range, which a symmetric implementation would not produce.

### JOIN-026 — A one-sided bound closes at zero on the unstated side
**Intent:** `TimeBounds.lower()`/`upper()` fall back to `Math.min(0, upper)` / `Math.max(0, lower)`.
`SQL_SUPPORT.md` line 176 documents it; the arithmetic is two ternaries and deserves the case.
**Falsifier:** The unstated side is unbounded, or defaults to an hour.
**Setup:** HJ5. `ON o.user_id = u.user_id AND o.event_time >= u.event_time - INTERVAL '30' SECOND`.
Right at `100s`; left at `69s`, `70s`, `100s`, `130s`, all keyed `u1`.
**Steps:** Feed the right row, then the four left rows; read `pairsEmitted()` and `outsideWindow()`;
read the resolved bounds from `explain`.
**Expected:** lower is `-30s`, upper falls back to `max(0, -30s) = 0`. `69s` → `-31s` out.
`70s` → `-30s` in. `100s` → `0` in. `130s` → `+30s` **out**, because the unstated upper is 0 and not
an hour. `pairsEmitted() == 2`, `outsideWindow() == 2`.
**Vacuity:** W4 — the `130s` row is the discriminating one; a run without it cannot tell 0 from ∞.

### JOIN-027 — Inverted bounds are refused at construction
**Intent:** `withinRange` throws when `upperNanos < lowerNanos`: "no pair of rows can satisfy them
and the join can only ever return nothing". A query that can only return nothing should say so, not
return nothing.
**Falsifier:** The join is built and returns zero pairs silently.
**Setup:** HJ4. A condition producing lower `+60s` and upper `-60s` — e.g.
`AND o.event_time >= u.event_time + INTERVAL '1' MINUTE AND o.event_time <= u.event_time -
INTERVAL '1' MINUTE`.
**Steps:** Plan it and build the physical operator; catch what is thrown.
**Expected:** `IllegalArgumentException` — note, not a `PRV-` coded `PravahaException` — reading
"a join's time bounds are inverted: lower 60000000000ns is above upper -60000000000ns…". Record the
missing code as an `ERRC` item, and record the units: nanoseconds, in a message a user reads.
**Vacuity:** n/a.

### JOIN-028 — A zero-width bound still gets a positive match window
**Intent:** `withinRange` computes `span = max(|lower|, |upper|)` and passes `span == 0 ? 1 : span`,
because `JoinOperator`'s constructor refuses `matchWithinNanos <= 0`. So `o.t = u.t` — exact
equality — becomes a 1-nanosecond retention window while keeping bounds `[0, 0]`.
**Falsifier:** The construction throws, or a pair 1ns apart is emitted.
**Setup:** HJ5. `ON o.user_id = u.user_id AND o.event_time >= u.event_time AND o.event_time <=
u.event_time`. Right at `100s`; left at `100s` and `100s + 1ns`, all keyed `u1`.
**Steps:** Read the constructed `JoinOperator`'s three time fields; feed the rows; read
`pairsEmitted()` and `outsideWindow()`; then `advanceWatermark(100s + 2ns)` and read `evicted()`.
**Expected:** constructed with `matchWithinNanos = 1`, `matchLowerNanos = 0`, `matchUpperNanos = 0`.
`100s` → delta 0 → emitted. `100s + 1ns` → delta `+1ns` → `outsideWindow()`. So retention is 1ns
while the predicate is exact, and eviction is effectively immediate: assert `evicted()` after
`advanceWatermark(100s + 2ns)`.
**Vacuity:** W4 — both `outsideWindow()` and `evicted()` asserted as numbers.

### JOIN-029 — A time bound in months or years is not recognised as a time bound
**Intent:** `SQL_SUPPORT.md` line 177 says these are refused because "a month has no fixed length".
`intervalMillis` returning null makes `asOffset` return null, which makes `collectTimeBound` return
false — so the conjunct falls through to `collectEquiKeys`, which refuses it as "neither an equality
… nor a time bound". The refusal is right; the *reason* in the message is not the documented one.
**Falsifier:** The query plans, or the message explains the month problem.
**Setup:** HJ4. `ON o.user_id = u.user_id AND o.event_time BETWEEN u.event_time - INTERVAL '1'
MONTH AND u.event_time`; then the same with `INTERVAL '1' YEAR`.
**Steps:** Plan both; record the code and the full message of each.
**Expected:** `PRV-2020`, message beginning "the join condition '…' is neither an equality between
one column of each side nor a time bound between them". Record that nothing in the message mentions
months, intervals or variable-length units, so a user reading it will add an equality rather than
change the unit.
**Vacuity:** n/a.

### JOIN-030 — Eviction releases rows past `watermark - matchWithin`
**Intent:** The horizon, and the claim that releasing is "the definition being honoured" rather than
data loss.
**Falsifier:** A row older than the horizon is still held, or one newer is released.
**Setup:** HJ5, default hour window. Feed right rows at `0s`, `1800s`, `3600s`. No left rows.
**Steps:** `advanceWatermark(3600s)` → horizon `3600s - 3600s = 0s`; then `advanceWatermark(5400s)`
→ horizon `1800s`; then `advanceWatermark(7200s)` → horizon `3600s`.
**Expected:** after the first, horizon 0s and the test is `eventTime < horizon`, so nothing is
released — `evicted() == 0`, `rowsHeldRight() == 3`. After the second, the `0s` row goes:
`evicted() == 1`, `rowsHeldRight() == 2`. After the third, the `1800s` row goes: `evicted() == 2`,
`rowsHeldRight() == 1`. The `3600s` row survives, because `3600 < 3600` is false.
**Vacuity:** W4 — `evicted()` asserted at each step as 0, 1, 2. A single final assertion cannot tell
"evicted on schedule" from "evicted all at once".

### JOIN-031 — An evicted row no longer matches a partner that arrives later
**Intent:** The consequence of JOIN-030, and the honest statement of what a match window costs.
**Falsifier:** A left row matches a right row that was already evicted.
**Setup:** HJ5, default hour.
**Steps:** Feed x1 at `0s`. `advanceWatermark(7200s)` — horizon `3600s`, so x1 is released. Then
feed o1 with event time `0s`.
**Expected:** `evicted() == 1` before the left row; then **0** pairs. The left row is stored (there
is nothing to match) and `outsideWindow()` stays 0 — the pair was never a candidate, because the
partner was gone, not because the time check rejected it. That distinction is what W4 is for and it
is the difference between "the window is too narrow" and "the data never matched" in a diagnosis.
**Vacuity:** W4 — assert `outsideWindow() == 0` and `evicted() == 1`. W1 — `rowsHeldLeft() == 1`.

### JOIN-032 — A late row on one side, still inside the window
**Intent:** A stream-to-stream join has no window close, so a row arriving out of order is ordinary
as long as the watermark has not passed its horizon. The case exists to establish that "late" means
something different here than it does for a window.
**Falsifier:** An out-of-order row is dropped or routed anywhere.
**Setup:** HJ5, plain `SQL_J1`, default hour window.
**Steps:** Feed o3 (event time 3s); `advanceWatermark(1800s)`; then feed x1 (event time 0s) — late by
arrival and earlier by event time. Read `pairsEmitted()`, `evicted()` and the pair's event time.
**Expected:** 1 pair, `(o3, x1)`, event time `max(3s, 0s) = 3s`. Horizon at watermark `1800s` is
`1800s - 3600s`, which is negative and saturates below every row's time, so nothing was evicted:
`evicted() == 0`. There is no late output on this operator at all — `SymmetricHashJoin` has no
`lateOutput`, unlike `WindowedAggregate`.
**Vacuity:** W4 — `evicted() == 0` proves the row was still in state when its partner arrived.

### JOIN-033 — A late row past the horizon is lost with no count of its own
**Intent:** The gap JOIN-032 implies. `WindowedAggregate` counts `lateRecords()` and routes them to
a named side output "because a missing record is not a diagnosis". The join has `evicted()` — a count
of rows released — and no count of rows that arrived too late to match anything.
**Falsifier:** A counter exists that separates "arrived after its partner was evicted" from
"matched nothing".
**Setup:** HJ5, JOIN-031's sequence, extended: after the failed match, feed a left row whose partner
was never fed at all.
**Steps:** Read every counter the operator exposes.
**Expected:** `pairsEmitted()`, `outsideWindow()`, `evicted()`, `unmatchedEmitted()`,
`rowsHeldLeft/Right()`, `keysHeldLeft/Right()`, `stateBytes()`. The two left rows — one whose
partner was evicted, one whose partner never existed — are indistinguishable in all of them.
Record it as a diagnosability finding: the symptom is an under-count with a green status, and
`outsideWindow()`'s own javadoc ("an empty result that looks exactly like no data") names the same
hazard for the case it *does* cover.
**Vacuity:** n/a — an enumeration of the operator's instrumentation.

### JOIN-034 — Eviction saturates rather than wrapping at the bottom of the range
**Intent:** `advanceWatermark` has `if (horizon > watermarkNanos) return;` — "a watermark near the
bottom of the range must not wrap into the future and evict everything". One subtraction, one guard,
and the failure it prevents is total data loss.
**Falsifier:** A watermark near `Long.MIN_VALUE` evicts every stored row.
**Setup:** HJ5, default hour window, three rows held.
**Steps:** `advanceWatermark(Long.MIN_VALUE + 1000)`; then `advanceWatermark(Long.MIN_VALUE)`.
**Expected:** both return without evicting. `Long.MIN_VALUE` is handled by the explicit
`== Long.MIN_VALUE` guard; `MIN_VALUE + 1000` by the wrap check, since
`MIN_VALUE + 1000 - 3_600e9` overflows to a large positive number. `evicted() == 0`,
`rowsHeldRight() == 3` after both.
**Vacuity:** W1 — three rows held before and after.

---

## §3 — Lanes and routing (JOIN-035 … JOIN-043)

### JOIN-035 — Multi-lane joins are unreachable from a configured node
**Intent:** Fact 4. Everything in §3 is programmatic; the case that says so comes first so the rest
are read correctly.
**Falsifier:** A registered query runs a join on more than one lane.
**Setup:** HJ3 plus source reading.
**Steps:** Register `SQL_J1`; read `pravaha queries` for a lane count if one is reported; then
confirm by reading `QueryRegistry:530` and `PluginSourceFeeds:116`.
**Expected:** `QueryExecution.start(plan, 1, …)` — the lane count is the literal `1`, not a
configuration value. `PluginSourceFeeds` calls `pumpInto(0, stream, reader, policy)`, never
`pumpPartitionedInto`. So `refuseUnpartitionedJoin` — which exists precisely to catch an
unpartitioned multi-lane join — can never fire on a server, and the routing it protects is never
used there. Record both line numbers.
**Vacuity:** n/a — a reachability result, established by reading and confirmed by the absence of any
`lanes` key in `application.yaml`.

### JOIN-036 — An unpartitioned pump into a multi-lane join is refused
**Intent:** `refuseUnpartitionedJoin`. The failure it prevents is the worst kind: not an error but
"most pairs unformed and the query quietly short of output".
**Falsifier:** `pumpInto` is accepted on a 4-lane join.
**Setup:** HJ1, `QueryExecution.start(plan, 4, …)`.
**Steps:** `execution.pumpInto(0, "orders", reader, policy)`.
**Expected:** `PRV-3021`: "this query contains a join and runs on 4 lanes, so a row and the rows it
can match must land on the same lane. A plain pump writes to whichever lane it was given, which
would leave most pairs unformed and the query quietly short of output. Use pumpPartitionedInto,
which routes by the join key."
**Vacuity:** W2 — the same plan on 1 lane must accept `pumpInto` and produce JOIN-007's 3 pairs, so
the refusal is about lanes and not about the plan.

### JOIN-037 — Both halves of a key reach the same lane
**Intent:** The core of the shuffle. `pumpPartitionedInto` hashes with `JoinKeys.hash` — "there is
one key hash in this engine and this is it" — and routes with `lanes::laneFor`. Routing by one hash
and probing by another puts the two sides of a key on different lanes and the join returns nothing
while every component looks correct.
**Falsifier:** A pair whose two halves landed on different lanes, or any missing pair.
**Setup:** HJ1, 4 lanes, `pumpPartitionedInto` for both streams. J1 and J2 enlarged: 8 distinct
users, 200 orders spread over them (25 each).
**Steps:** Feed both; quiesce; collect; and for each of the 8 keys record which lane emitted it.
**Expected:** `8 × 25 = 200` pairs. Every pair for a given `user_id` comes from **one** lane; no
`user_id` appears in two lanes' output. Assert the per-key lane is single-valued, not merely that
200 pairs arrived.
**Vacuity:** W2 — exactly 200. W1 — both pumps report their full counts. A run producing 200 pairs
from 1 lane would also pass a count-only assertion, so assert the 4 lanes each did work:
`execution.metrics()` shows non-zero rows on more than one lane.

### JOIN-038 — One lane and four lanes give identical results
**Intent:** The property the whole shuffle exists to preserve, stated as an equality rather than as
two separate correct-looking runs.
**Falsifier:** The two result sets differ in any row.
**Setup:** HJ1. The same enlarged J1/J2 as JOIN-037.
**Steps:** Run A: 1 lane, `pumpInto`. Run B: 4 lanes, `pumpPartitionedInto`. Sort both outputs by
`(order_id, user_id)` and compare element-wise.
**Expected:** identical multisets of 200 rows, nine columns each, including event times and weights.
Emission *order* differs — four lanes interleave by thread scheduling — so compare as sorted
multisets and say so.
**Vacuity:** W2 — 200 in each run, asserted separately before the comparison. Two empty results are
also identical.

### JOIN-039 — Two lanes and eight lanes, and a lane count that exceeds the key count
**Intent:** JOIN-038 at two more points, plus the degenerate case where lanes outnumber keys — some
lanes receive nothing, and a lane that never sees a row must still quiesce and must not hold the
watermark down.
**Falsifier:** Any lane count gives a different answer, or a run with idle lanes does not terminate.
**Setup:** HJ1, the JOIN-038 data at 2 and 8 lanes; then 8 lanes with only 3 distinct users.
**Steps:** Three runs, each with `pumpPartitionedInto` for both streams; quiesce; collect; compare
each against the single-lane answer computed in JOIN-038.
**Expected:** 200 pairs at 2 lanes and at 8. With 3 users on 8 lanes, at most 3 lanes emit anything
and the run still quiesces; the pair count equals the single-lane answer for that input.
**Vacuity:** W2 on each run. For the idle-lane run assert `awaitQuiescent` returns true rather than
timing out — a hang here is the failure mode, not a wrong number.

### JOIN-040 — A partitioned pump advances event time; an unpartitioned one used not to
**Intent:** `trackEventTimeOf` is shared by both pump types now, and the comment records the
asymmetry that made it a bug neither class could show: "event time did not advance from a
partitioned source, so windows never closed and join state never evicted". Re-establish it, because
eviction in §2 depends on it.
**Falsifier:** `evicted()` stays 0 on a partitioned multi-lane join whose rows are old enough.
**Setup:** HJ1, 2 lanes, both streams via `pumpPartitionedInto`, with
`generatingWatermarks(...)` installed **before** the pumps.
**Steps:** Feed rows at `0s`; wait for the watermark clock to advance past `0s + 1h`; read
`evicted()` per lane.
**Expected:** non-zero eviction on the lanes that hold rows. Assert the watermark partition was
registered: the partition name is `streamName + "#" + laneIndex + "/" + pumps.size() +
partitionedPumps.size()` with `laneIndex` fixed at 0 for a partitioned pump, so two partitioned
pumps for two streams produce two distinct partition names — assert they are distinct, since a
collision would silently merge two sources into one watermark partition.
**Vacuity:** W4 — `evicted()` as a number per lane, and the partition names enumerated.

### JOIN-041 — `generatingWatermarks` after a pump is refused
**Intent:** The ordering rule, whose message explains the consequence: a pump created earlier "would
not be a partition of the watermark, and its stream would advance event time for everybody else
while contributing nothing of its own".
**Falsifier:** The call is accepted.
**Setup:** HJ1.
**Steps:** `pumpInto(...)`, then `generatingWatermarks(...)`.
**Expected:** `IllegalStateException` with that message. Record that it carries no `PRV-` code.
**Vacuity:** n/a.

### JOIN-042 — The join key is mapped down through a projection, not taken as-is
**Intent:** `mapDownToScan` walks `ProjectOperator.sourceOrdinals()` — "taking the join's ordinals as
the scan's would route rows by whatever column happens to sit at that position — correct-looking,
and wrong".
**Falsifier:** Rows are routed by the wrong column, producing fewer pairs on 4 lanes than on 1.
**Setup:** HJ1, 4 lanes. A query with a projection between the scan and the join that *reorders*
columns: `SELECT ... FROM (SELECT region, user_id, order_id, amount, event_time FROM orders) o
JOIN users u ON o.user_id = u.user_id`. The join sees `user_id` at ordinal 1; the scan has it at 1
too, so make the projection non-trivial: put `region` first, so `user_id` is at the join's ordinal 1
and the scan's ordinal 1 — reorder further to force a difference, e.g. project
`(amount, region, user_id, ...)` so the join's key ordinal is 2 and the scan's is 1.
**Steps:** Read `joinKeyOrdinalsFor(0)`; run on 4 lanes; compare against 1 lane.
**Expected:** `joinKeyOrdinalsFor(0)` returns `[1]` — the *scan's* ordinal — not `[2]`. Pair counts
identical on 1 and 4 lanes. A run returning fewer pairs on 4 lanes is the failure, and it is silent.
**Vacuity:** W2 — the 1-lane count computed first and asserted; the 4-lane run compared to it.

### JOIN-043 — A key that passes through an operator `mapDownToScan` cannot walk is refused
**Intent:** The method handles `ScanOperator`, `ProjectOperator` and `FilterOperator` and throws for
anything else — "which changes what a column means". A `ComputeOperator` (any computed column in the
select list) or a `LookupJoinOperator` below the join reaches the throw.
**Falsifier:** The pump is created and routes by a meaningless ordinal.
**Setup:** HJ1, 4 lanes, a query with a computed column below the join:
`SELECT ... FROM (SELECT user_id, amount * 2 AS doubled, event_time FROM orders) o JOIN users u ON
o.user_id = u.user_id`.
**Steps:** `pumpPartitionedInto("orders", reader, policy)`.
**Expected:** `PRV-3021`: "cannot work out which source column feeds this join key: it passes
through Compute(...), which changes what a column means. Run this query on one lane, where no
partitioning is needed." Then confirm the 1-lane run of the same query succeeds and gives the
hand-computed pairs — the suggested remedy must actually work.
**Vacuity:** W2 — the 1-lane run's pair count asserted, so the remedy is verified and not assumed.

---

## §4 — Lookup join, `FOR SYSTEM_TIME AS OF` (JOIN-044 … JOIN-053)

### JOIN-044 — A lookup join cannot be planned on any shipped surface
**Intent:** Fact 2, and the case the brief asks for. `SQL_SUPPORT.md` line 180 records
"Lookup join against a dimension table ✅ — Async, on virtual threads, ordered output", and four of
the five case studies build their central query on it. `registerLookup` is reachable only through
`SqlPlanner.withLookups`, which no production code calls.
**Falsifier:** Any shipped entry point produces a `LookupJoinOperator`.
**Setup:** HJ3 and HJ4.
**Steps:** (a) Declare `users` under `pravaha.streams.users`, bind it, and
`pravaha register --name v_enriched --sql "SELECT o.order_id, u.tier FROM orders o LEFT JOIN users
FOR SYSTEM_TIME AS OF o.event_time AS u ON o.user_id = u.user_id" --keys 0`.
(b) `pravaha validate` and `pravaha explain` on the same SQL.
(c) Enumerate every `SqlPlanner.with*` call site in `*/src/main/java`.
**Expected:** (a) and (b) refuse with `PRV-2020`: "stream 'users' is registered as a stream, not as
a lookup table. A stream is consumed and its rows are held in join state; a lookup table is asked
one key at a time and holds nothing. Register it with registerLookup to join against it this way."
(c) six call sites, all `withStreams`: `QueryRunner:98`, `ValidateCommand:52`, `ExplainCommand:59`,
`ViewQuery:302`, `ViewQuery:514`, `QueryController:122`. **Zero** callers of `withLookups`.
Conclusion: the message names an API the user cannot call, on a feature four case studies document
as their central mechanism.
**Vacuity:** n/a — a reachability result. Record the exact grep and its output.

### JOIN-045 — There is no configuration key that would register a lookup table
**Intent:** JOIN-044's remedy is "register it with registerLookup"; this establishes that a node
operator has no way to do so. FINDINGS Q-15 records the conclusion; this is the audit that supports
it in both directions.
**Falsifier:** Any key under `pravaha.*` declares a lookup table, or `StreamController` accepts a
lookup flag.
**Setup:** HJ4 / source audit.
**Steps:** Grep `application.yaml` and `SecurityProperties`-style binding classes for `lookup`;
enumerate `POST /api/v1/streams`' request body fields.
**Expected:** `application.yaml` contains the word "lookup" nowhere. `pravaha.streams.<n>` takes
`schema`, `event-time` and `out-of-orderness`; `pravaha.sources.<n>` takes `plugin` and `options`.
`StreamController.create` calls `catalog.register(schema)` — the stream path. No surface exists.
**Vacuity:** n/a.

### JOIN-046 — Even a planned lookup join could not be started by the registry
**Intent:** Fact 3, the second independent block. If JOIN-044's planning gap were fixed tomorrow,
this would still refuse.
**Falsifier:** `QueryRegistry` passes a non-empty lookups map.
**Setup:** HJ2 and source reading.
**Steps:** (a) Read `QueryRegistry:530`. (b) In HJ2, build a valid lookup plan and call the
five-argument `QueryExecution.start` — the overload the registry uses.
**Expected:** (a) `QueryExecution.start(plan, 1, laneConfig, access, () -> (RowOutput) sink::begin)`
— five arguments, so `lookups = Map.of()`. (b) `InterpretedPipeline.compile` fails at start-up, not
on the first record, which its javadoc says is deliberate: "a lookup join that discovers its table
is missing after an hour of running has already produced an hour of output that should not exist".
Record the exact message and whether it names the missing table.
**Vacuity:** W1 — the same plan started through the six-argument overload with the plugin supplied
must succeed (JOIN-047), so the failure is the empty map and not the plan.

### JOIN-047 — A lookup join, run programmatically, enriches every record
**Intent:** Having established the feature is unreachable, establish that it *works* — so the finding
is "a working operator with no way in" rather than "an unbuilt feature".
**Falsifier:** Any record is unenriched, or the pair set differs from the hand computation.
**Setup:** HJ2. `SqlPlanner.withLookups(orders(), dim())`; a `LookupSourcePlugin` over
{u1 → gold, u2 → silver, u3 → bronze}; `QueryExecution.start(plan, 1, config, access, sink,
Map.of("dim", plugin))`.
**Steps:** `SELECT o.order_id, d.tier FROM orders o JOIN dim FOR SYSTEM_TIME AS OF o.event_time AS d
ON o.user_id = d.user_id`. Feed J1.
**Expected:** inner form, so o4 (u9, no match) is dropped: **3** rows — `(1, gold)`, `(2, silver)`,
`(3, gold)`. `lookups` counter 3 for the first pass over three distinct keys, `cacheHits` 1 for o3
reusing u1 (if `cacheFor()` is non-zero).
**Vacuity:** W3 — o4 must be fed; 3 rows from 3 inputs proves nothing about the miss.

### JOIN-048 — `LEFT JOIN … FOR SYSTEM_TIME AS OF` emits nulls for a miss and never retracts
**Intent:** `LEFT` is accepted for a lookup and refused for stream-to-stream without a time bound,
"which is not the inconsistency it looks like: a lookup answers definitively at the moment it is
asked". The README's own example is this shape.
**Falsifier:** o4 is dropped, or a retraction is emitted later.
**Setup:** HJ2 as JOIN-047, with `LEFT JOIN dim FOR SYSTEM_TIME AS OF o.event_time`.
**Steps:** Feed J1; collect; read the `unmatched` counter and the null-ness of o4's `tier` column.
**Expected:** **4** rows: `(1, gold)`, `(2, silver)`, `(3, gold)`, `(4, NULL)`. `unmatched` counter
1. No retraction is ever emitted for o4, at any watermark.
**Vacuity:** W3 — the null row is the case; assert its `tier` column is null rather than the empty
string. FINDINGS records the Aerospike sink cannot write a row with a null column, so note that this
row is exactly what breaks that sink.

### JOIN-049 — The period the syntax names is not honoured
**Intent:** `LookupJoinOperator`'s javadoc states it plainly and the documentation does not:
"the lookup is as of *now*, so a replay of last month's stream is enriched with today's dimension
rows… it matters most exactly when somebody is rebuilding history, which is when they are least
likely to be reading the manual." `SQL_SUPPORT.md` line 180 says ✅ with no qualification;
`examples/case-studies/banking-card-velocity/README.md:295` says the opposite — "That is
`FOR SYSTEM_TIME AS OF` doing its job. A conventional cache would have rewritten history".
**Falsifier:** The value joined is the one that was current at `o.event_time`.
**Setup:** HJ2. A plugin whose answer for u1 changes between calls: `gold` on the first lookup,
`platinum` on every later one, with `cacheFor()` = ZERO so nothing is cached.
**Steps:** Feed o1 (event time `1s`), then o3 (event time `3s`), then a third order for u1 with
event time `2s` — between the two.
**Expected:** `(1, gold)`, `(3, platinum)`, `(5, platinum)`. The third order's event time is
*earlier* than o3's and it still gets the later value, because the lookup is by wall clock. Record
the case-study README's claim as contradicted by the operator's own javadoc and by this result.
**Vacuity:** W2 — the plugin's call count must be 3, proving three separate lookups and not one
cached answer.

### JOIN-050 — Output stays in arrival order when lookups complete out of order
**Intent:** "Results queue and the queue drains from the front: a record's output waits for every
earlier record's." Emitting as they finish would move sequence numbers backwards and break the
deduplicating sink and every downstream window.
**Falsifier:** Any output row's sequence is lower than its predecessor's.
**Setup:** HJ2. A plugin that sleeps 200ms for key `u1` and returns instantly for every other key.
Feed 10 orders alternating `u1` and `u2`.
**Steps:** Collect output; read sequences in emission order.
**Expected:** 10 rows in input order: order_ids 1…10. Sequences monotonically non-decreasing. The
fast `u2` lookups complete first and wait. Total wall time ≈ 5 × 200ms if the slow lookups serialise,
or ≈ 200ms if they overlap — record which, since overlapping waits are the operator's whole claim.
**Vacuity:** W2 — 10 rows; and assert at least one `u2` lookup *completed* before its predecessor's
`u1` lookup, by instrumenting the plugin, so the reordering opportunity demonstrably existed.

### JOIN-051 — In-flight lookups are bounded by the source's `maxConcurrency`
**Intent:** Backpressure "in the only form available here — the alternative is an unbounded queue of
parked records". `maxInFlight = max(1, source.maxConcurrency())`, and `awaitHead()` blocks the lane.
**Falsifier:** `maxObservedInFlight` exceeds `maxConcurrency`.
**Setup:** HJ2. Plugin with `maxConcurrency() == 4`, each lookup sleeping 50ms, 100 distinct keys.
**Steps:** Feed 100 orders; read `maxObservedInFlight`.
**Expected:** `maxObservedInFlight <= 4`. 100 lookups at 4 concurrent × 50ms ≈ 1.25s, versus 5s
serialised — record the elapsed time as evidence the waits overlapped. With `maxConcurrency() == 0`
the floor applies and `maxInFlight == 1`.
**Vacuity:** W2 — 100 output rows; a run that dropped records would also show a low in-flight count.

### JOIN-052 — `cacheFor() == ZERO` disables both caching and coalescing
**Intent:** The subtle half: a source refusing caching is also refusing to have one answer shared
between two concurrent records, "which is a cache with a lifetime of 'however long that lookup
took'". The code honours it — `cacheNanos > 0 ? inFlight.get(key) : null`.
**Falsifier:** Two concurrent records for one key produce one lookup when `cacheFor()` is ZERO.
**Setup:** HJ2, two plugins differing only in `cacheFor()`: ZERO and 60s, each lookup sleeping
100ms so all five overlap.
**Steps:** Two runs. In each, feed 5 orders all keyed `u1`; read `lookups`, `coalesced` and
`cacheHits` on the operator, and the plugin's own call counter.
**Expected:** with `cacheFor() = 60s`: `lookups == 1`, `coalesced == 4`, `cacheHits` counting any
that arrived after the first completed. With `cacheFor() = ZERO`: `lookups == 5`, `coalesced == 0`,
`cacheHits == 0`. Five separate calls to the plugin, asserted at the plugin.
**Vacuity:** W2 — 5 output rows in both; assert the plugin's own call counter, not only the
operator's.

### JOIN-053 — The lookup cache is bounded and access-ordered
**Intent:** "An unbounded cache over an unbounded key space is the memory leak this operator was
supposed to avoid." `removeEldestEntry` evicts past `maxCacheEntries`, and the map is
access-ordered, so a hot key survives a flood of cold ones.
**Falsifier:** The cache grows past its bound, or a hot key is evicted by cold traffic.
**Setup:** HJ2 with `maxCacheEntries = 10` and a plugin counting calls per key.
**Steps:** Run A: feed `u1`, then 20 distinct cold keys with `u1` interleaved every third record,
then `u1`. Run B: feed `u1`, then the same 20 cold keys with no interleaving, then `u1`. Read the
cache size and the plugin's per-key call count after each.
**Expected:** cache size never exceeds 10. The interleaved `u1` produces `cacheHits` rather than
`lookups` throughout, because each access moves it to the front. Then repeat *without* interleaving:
`u1` after 20 cold keys is a miss. Both variants asserted.
**Vacuity:** W2 — the plugin's call count for `u1`: 1 in the interleaved run, 2 in the other.

---

## §5 — Refusals (JOIN-054 … JOIN-060)

Each with its code, its layer, and whether its message tells the reader what to do instead.

### JOIN-054 — `RIGHT` and `FULL OUTER` between streams
**Intent:** `SQL_SUPPORT.md` line 184 records `PRV-2020` and "swap the inputs and use LEFT". The
message must actually say that.
**Falsifier:** Either plans, or the message omits the remedy.
**Setup:** HJ4.
**Steps:** Plan `RIGHT JOIN` and `FULL JOIN` forms of `SQL_J1`.
**Expected:** two `PRV-2020`s: "a RIGHT join between streams is not supported. A LEFT join is, when
the condition states a time bound; RIGHT and FULL would need the same treatment on the other side
and are not built. Swap the inputs and use LEFT." and the same with FULL. Actionable: names the
alternative and its precondition.
**Vacuity:** n/a.

### JOIN-055 — `LEFT JOIN` between streams without a time bound
**Intent:** `SQL_SUPPORT.md` line 183. The refusal is conditional — a `LEFT` *with* a bound is
supported — so the message has to carry the condition, and it does.
**Falsifier:** It plans, or the message reads as an unconditional "not supported".
**Setup:** HJ4.
**Steps:** Plan `SELECT * FROM orders o LEFT JOIN users u ON o.user_id = u.user_id`.
**Expected:** `PRV-2020`: "a LEFT join between streams needs a time bound in its ON condition.
Without one there is no moment at which an unmatched left row can be declared unmatched, so every
one is held for as long as the process lives. Add a bound such as AND l.event_time BETWEEN
r.event_time - INTERVAL '5' MINUTE AND r.event_time, which is also the point at which the
null-padded row is emitted." Note the example uses `l.` and `r.` aliases the user's query does not
have — record that as a message defect: an example a reader cannot paste.
**Vacuity:** n/a.

### JOIN-056 — `LEFT JOIN` *with* a time bound: the null-padded row, once, at eviction
**Intent:** The supported form, and the one behaviour in this area that depends on eviction being
correct. `emitNullPadded` fires from `evictOlderThan` — "the one moment at which 'has not matched'
and 'will not match' mean the same thing" — at weight `+1`, never retracted.
**Falsifier:** The null-padded row is emitted eagerly, twice, or not at all.
**Setup:** HJ5, `asLeftOuter()` with bounds from `AND o.event_time BETWEEN u.event_time -
INTERVAL '5' MINUTE AND u.event_time`, so `matchWithinNanos = 300s`.
**Steps:** Feed x1 at `600s`; feed o1 (u1, `600s`) and o4 (u9, `600s`). `advanceWatermark(700s)` —
horizon `400s`, nothing evicted. `advanceWatermark(1000s)` — horizon `700s`, both left rows evict.
**Expected:** at feed time, one pair `(o1, x1)` — delta 0, inside `[-300s, 0]`. At the second
advance, o4 evicts unmatched and produces **one** null-padded row `[4, u9, eu, 7, 600s, NULL, NULL,
NULL, NULL]` at weight `+1`; o1 evicts *matched* and produces nothing, because `markMatched` was set.
`unmatchedEmitted() == 1`, `pairsEmitted() == 1`, `evicted() == 3` (o1, o4, x1).
**Vacuity:** W4 — `evicted()` and `unmatchedEmitted()` as numbers, and the first advance asserted to
emit nothing, so the row is known to arrive at eviction rather than at arrival.

### JOIN-057 — A null-keyed left row is never emitted null-padded
**Intent:** Fact 6's consequence, and a genuine outer-join defect: `add` drops null-keyed rows
entirely, so they are not in state when eviction runs the `unmatched` callback. SQL says a left row
whose key is NULL matches nothing and must appear with nulls on the right.
**Falsifier:** The null-keyed left row appears in the output.
**Setup:** HJ5, JOIN-056's outer join with bounds `[-300s, 0]`.
**Steps:** Feed x1 at `600s`; feed o1 (u1, `600s`) and o5 = `(6, NULL, eu, 9, 600s)`; read
`rowsHeldLeft()`; `advanceWatermark(1000s)`; collect every output row and read `unmatchedEmitted()`.
**Expected (SQL):** two output rows — the o1 pair, and o5 null-padded.
**Expected (this build):** one pair and **one** null-padded row, from o4-style unmatched *non-null*
keys only; o5 produces nothing at all. `rowsHeldLeft()` is 1, not 2, before the advance — o5 was
never stored. The row is not dropped as "unmatched"; it is dropped as "unstorable", and nothing
counts it.
**Vacuity:** W1 — assert two left rows were fed and one is held. W3 — confirm o5's key is null at
the writer.

### JOIN-058 — A non-equi join on non-timestamp columns
**Intent:** `SQL_SUPPORT.md`'s last join row: "An inequality between *timestamp* columns is a time
bound and is supported; between anything else it is a cross product." Both halves in one case.
**Falsifier:** `ON o.amount > u.threshold` plans, or an inequality between two timestamp columns is
refused.
**Setup:** HJ4, `users` extended with `threshold INT64`.
**Steps:** (a) `ON o.amount > u.threshold`. (b) `ON o.user_id = u.user_id AND o.amount > u.threshold`.
(c) `ON o.user_id = u.user_id AND o.event_time > u.event_time`.
**Expected:** (a) `PRV-2020` from `collectEquiKeys`: "the join condition '>(…)' is neither an
equality between one column of each side nor a time bound between them… Add the equality the two
streams correlate on." (b) the *same* refusal — an equality present elsewhere in the AND does not
rescue the inequality, because every conjunct must be one or the other. (c) plans: `collectTimeBound`
accepts it, giving `lower = 0` open-above with `upper = max(0, 0) = 0`… assert the resolved bounds in
`explain`, since a `>` with no interval yields `delta > 0` recorded as `atLeast(0)`, and the
inclusive test `delta >= 0` then admits `delta == 0` — an off-by-one between `>` and `>=` that the
bounds record cannot express. Report the observed bounds for `>` and for `>=` separately.
**Vacuity:** n/a for (a) and (b); for (c) feed two rows with `delta == 0` and record whether they
pair.

### JOIN-059 — `CROSS JOIN` and an equality-free `ON`
**Intent:** Two shapes reaching two different refusals. `CROSS JOIN` produces no condition at all; a
time bound with no equality produces one that is recognised but keyless.
**Falsifier:** Either plans.
**Setup:** HJ4.
**Steps:** (a) `SELECT * FROM orders o CROSS JOIN users u`. (b) `... JOIN users u ON o.event_time
BETWEEN u.event_time - INTERVAL '5' MINUTE AND u.event_time`.
**Expected:** (a) `PRV-2020` — record which of the three sites fires: `buildJoin`'s `leftKeys
.isEmpty()` check, `collectEquiKeys`' fall-through, or `JoinOperator`'s constructor
(`IllegalArgumentException`, no code). Calcite may also rewrite a cross join into a filter-over-join
first; record the shape it produced. (b) `PRV-2020`: "the join condition states a time bound but no
equality, so there is no join key to index either side by. A window narrows which pairs count; it
does not stop every row being a candidate for every other inside it…". Actionable.
**Vacuity:** n/a.

### JOIN-060 — A self-join is refused late, and without a code
**Intent:** The one refusal reachable from SQL that carries no `PRV-` code
(`docs/development/HANDOVER.md:244`, `SQL_SUPPORT.md:196`). It plans perfectly and fails when the pipeline is
built, which is a different moment from every other refusal in this file and a different moment
again from where a user expects one.
**Falsifier:** It is refused at plan time, or carries a code of its own, or runs.
**Setup:** HJ4 then HJ1.
**Steps:** (a) `SqlPlanner.withStreams(orders()).plan("SELECT a.order_id, b.order_id FROM orders a
JOIN orders b ON a.user_id = b.user_id")` then `PhysicalPlanBuilder.build`. (b)
`InterpretedPipeline.compile` on the resulting plan. (c) `pravaha register` of the same SQL on a
node.
**Expected:** (a) plans and builds successfully — a `JoinOperator` whose two sides are the same scan.
(b) fails: "stream 'orders' appears on both sides of this plan; self-joins are not supported yet",
wrapped as `PRV-1041` with no code of its own. (c) the registration is refused at start-up with that
message. Record the three moments, and record that `PluginSourceFeeds` already handles the shape it
would need — "a self-join names one stream twice and opening two feeds for it would…" — so the
refusal sits above machinery that anticipated it.
**Vacuity:** W2 — the same query with two *distinct* streams must plan, compile and produce
JOIN-007's 3 pairs, so the refusal is about the repeated name and not about the query shape.

---

## Coverage note

The budget of 60 is met exactly. Three things about how it was spent.

**The reachability cases earn their place.** Six of the sixty (JOIN-001, 035, 044, 045, 046, plus
JOIN-013's plan-time/run-time discrepancy) establish that the two headline join features —
`FOR SYSTEM_TIME AS OF` and multi-lane routing — cannot be used from any configured node, and that
a third, `pravaha run`, cannot run a join at all. Without them, §3 and §4 read as forty cases about
a working system; with them, they read as "here is what works, and here is why nobody can reach it".
Four of the five case studies build their central query on the feature JOIN-044 pins.

**Two dimensions were compressed deliberately.**

- *Key types* (JOIN-012) is one case covering eight types rather than eight cases, because
  `JoinKeys.fieldHash` is one switch over one value and the interesting boundaries are the two
  refused families (JOIN-013, JOIN-014) and the NULL path (JOIN-020, JOIN-021), which get four cases
  between them. If the eight-row table in JOIN-012 shows any type behaving differently, it should be
  expanded to eight.
- *Lane counts* (JOIN-037 … JOIN-039) cover 1, 2, 4 and 8 plus the lanes-exceed-keys degenerate
  case, rather than enumerating each against every §1 shape. The routing decision is made once, by
  `JoinKeys.hash` and `lanes::laneFor`, and is independent of what the join then does with the pair
  — so JOIN-038's equality between the 1-lane and 4-lane result sets is the property, and running
  every §1 case at four lanes would re-test the hash sixteen times.

**One thing this area cannot establish and hands on.** Whether join state survives a restart.
`SymmetricHashJoin.writeTo`/`readFrom` round-trip both sides including the per-row `matched` flag —
which exists precisely so a restored left row that had already been emitted is not emitted a second
time null-padded — and `JoinSide.readFrom` re-adds each row through `add`, so a null-keyed row in a
checkpoint would be silently dropped on restore. Both belong to `STATE`; this area asserts only what
a single run produces. FINDINGS D-1 and A-2 record that nothing calls `restore()` outside tests, so
that handover may be shorter than it looks.
