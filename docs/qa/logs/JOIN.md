# JOIN — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/JOIN.md`](../cases/JOIN.md). Executed 2026-09-14 on branch `develop`, against the
`pravaha-runtime`/`pravaha-sql` sources as built by `./mvnw install -DskipTests`.

**Route.** §1 and §2's operator-arithmetic cases (row/key mechanics and the match window/eviction)
run as HJ5 — the case file's own harness for weights other than `+1`, chosen NULL columns, and the
package-private counters (`pairsEmitted()`, `outsideWindow()`, `evicted()`, `rowsHeldLeft/Right()`,
`keysHeldLeft/Right()`, `unmatchedEmitted()`) that only a test inside
`com.ash.messaging.pravaha.runtime.exec` can read. That harness is
`pravaha-runtime/src/test/java/com/ash/messaging/pravaha/runtime/exec/SymmetricHashJoinBehaviorTest.java`,
new this round, built directly against `JoinOperator`/`SymmetricHashJoin` rather than through SQL —
`JoinOperator` can be constructed with explicit ordinal lists, so no `pravaha-sql` dependency is
needed for §1/§2's semantics.

Every load-bearing arithmetic assertion in that file was seed-proven in four rounds: (1) the
inclusive time-bound comparisons in `JoinOperator.matchesInTime` (`>=`/`<=` weakened to `>`/`<`) —
broke exactly the seven boundary-sensitive tests (JOIN-023, 024, 025, 026, 028, 056, 057) and no
others; (2) the ceiling's off-by-one (`>` to `>=` in `SymmetricHashJoin.checkCeiling`) — broke
JOIN-006's test with the ceiling firing a row early; (3) the null-key storage skip removed from
`JoinSide.add` — broke the three null-key tests (JOIN-020, 021, 057); (4) the output ordinal
arithmetic in `emit` (`width + i` weakened to `width - 1 + i`) — broke JOIN-003's discriminating-row
assertion with a shifted, corrupted row. All four were reverted and the suite re-confirmed green
(`mvn -pl pravaha-runtime verify`, exit 0) before anything below was recorded PASS.

§4's concurrency/caching/staleness cases run the same way against `LookupJoinOperator`/`LookupJoin`,
in `LookupJoinBehaviorTest.java` in the same package.

Cases already covered by pre-existing tests are recorded as such and were **re-run**, not rewritten:
`StreamJoinTest`, `JoinOnLanesTest`, `JoinRetentionTest`, `TemporalJoinTest`, `OuterJoinTest`,
`LookupJoinTest`, `JoinRecoveryTest` (all `pravaha-it`), and `JoinKeysTest` (`pravaha-runtime`).

**This is a partial round.** §0's CLI/registry-reachability cases (JOIN-001, 002) and §3's routing,
§4's lookup-join detail cases, and §5's SQL-level refusals need SQL planning or a live node/registry
and were not reached this session; they are left off this log rather than guessed at, per the file's
own rule that a skip is not a pass. They will be picked up in a following batch under this same log
file — see the running tally at the end.

---

## §0 — What is reachable (JOIN-001 … JOIN-006)

- **JOIN-001 — NOT RUN.** Needs `pravaha run` (the CLI, `RunCommand`) driven end-to-end against a
  two-stream join query; not reached this session. `RunCommand`'s single `--stream`/`--schema`
  signature was confirmed by reading (matches the case file's fact 1) but not exercised as a running
  process.
- **JOIN-002 — NOT RUN.** Needs HJ3 (a node with two streams registered and bound). Not reached this
  session; `LookupJoinTest.aRegisteredQueryCanReachALookupJoin` is the nearest existing evidence for
  the *registry* path working end-to-end, but it is a lookup join, not the stream-to-stream shape
  this case asks for.
- **JOIN-003 — PASS.** `SymmetricHashJoinBehaviorTest.outputColumnsAreTheLeftInputsThenTheRightInputsInOrder`.
  o3×x1 (the discriminating pair, since its two region columns differ) reads
  `[3, u1, us, 200, 3s, u1, eu, gold, 0s]` — orders' five columns verbatim, then users' four.
  Seed-proven (#4 above).
- **JOIN-004 — PASS.** `SymmetricHashJoinBehaviorTest.aJoinedRowsEventTimeIsTheLaterOfTheTwoInputs`.
  Two independent joins, one per direction: right-earlier gives `max(1s,0s)=1s`; right-later gives
  `max(1s,9s)=9s`. Both directions asserted so the `max` is demonstrably exercised, not merely one
  side's time passed through.
- **JOIN-005 — PASS.** `SymmetricHashJoinBehaviorTest.aPairsWeightIsTheProductOfTheTwoRowsWeights` —
  `+1×+1=+1`, `-1×+1=-1`, `+2×+1=+2`, `+2×+3=+6`; `pairsEmitted()==4`. A companion test,
  `aZeroWeightArrivalEmitsNothingBeforeAllocating`, confirms a cancelling arrival emits nothing extra.
- **JOIN-006 — PASS.** `SymmetricHashJoinBehaviorTest.theJoinCeilingRefusesLoudlyRatherThanEvicting`.
  `PRV-4001` on the fourth distinct left key, naming the side ("left side of Join[...]"), the count
  ("holds 4 rows") and the ceiling ("past the ceiling of 3"); `rowsHeldLeft()==3` asserted immediately
  before the throw (W1). Seed-proven (#2 above).

## §1 — Inner equi-join: keys and row shapes (JOIN-007 … JOIN-022)

- **JOIN-007 — PASS.** `oneKeyGivesThreePairsFromFourAndThreeRows`. 3 pairs; `rowsHeldLeft()==4`,
  `rowsHeldRight()==3`, `keysHeldLeft()==3`, `keysHeldRight()==3` — all four numbers, matching the
  file's hand computation exactly.
- **JOIN-008 — PASS.** `aSecondKeyNarrowsThreePairsToTwo`. Adding the region equality drops o3×x1
  (u1/us vs u1/eu): 2 pairs, controlled against JOIN-007's 3 in the same run.
- **JOIN-009 — NOT RUN.** Needs a third key column (`seg`) added to both sides; the harness supports
  it directly (arbitrary schemas) but the specific three-key fixture was not built this session.
- **JOIN-010 — NOT RUN.** Reversed-operand key order (`r.k = l.k`) is a `collectEquiKeys`/SQL-planning
  concern — HJ5's direct `JoinOperator` construction takes ordinal lists already resolved, so it
  cannot exercise which side of `=` a predicate's operands were written on. Needs HJ1 with
  `SqlPlanner`; deferred to the SQL-level batch.
- **JOIN-011 — PASS.** `aCompositeKeyOfTwoEqualColumnsDoesNotCollideWithItsSwap`. `(eu,eu)`, `(us,us)`,
  `(eu,us)`, `(us,eu)` give exactly 4 pairs and `keysHeldRight()==4` — the hash does not collapse a
  repeated or swapped value.
- **JOIN-012 — PASS.** `everyListedKeyTypeJoinsCorrectlyAndSelects`. All eight types (STRING, INT64,
  INT32, INT8, BOOLEAN, DATE, TIME, TIMESTAMP) join correctly: one pair each, and the non-matching
  right row is confirmed held (`rowsHeldRight()==2`) so the match is shown to be selective (W3), not
  merely present. No type behaved differently from the others, so the file's compression (one case
  covering all eight) stands.
- **JOIN-013 — NOT RUN.** The plan-time/run-time discrepancy needs both `pravaha-sql` (HJ4, to show it
  plans) and `pravaha-it` (HJ1, to show `InterpretedPipeline.compile` refuses) in the same case;
  `JoinKeysTest.aFloatingPointKeyIsRefusedWithTheReasonRatherThanQuietlyDroppingRows` (pre-existing,
  `pravaha-runtime`) confirms `JoinKeys.checkJoinable` throws `PRV-3021` with the rounding message,
  but does not establish the "plans fine, fails at compile" half. Deferred to the SQL-level batch.
- **JOIN-014 — NOT RUN.** Same shape as JOIN-013, for `DECIMAL`; deferred likewise.
- **JOIN-015 — PASS.** `noMatchingRowsAtAllIsDistinguishedFromAnIngestFailure`. 0 pairs, but
  `rowsHeldLeft()==3`, `rowsHeldRight()==3`, `keysHeldLeft/Right()==3` and `outsideWindow()==0` —
  proving the empty answer is a fact about selection and not a fact about ingestion (W1, the case's
  whole point).
- **JOIN-016 — PASS.** `everyRowMatchingProducesTheFullCrossProductOfAHotKey`. 3×3=9 pairs on one hot
  key. Seeding this test bare (three *identical* rows per side, no distinguishing column) gave 3, not
  9 — `JoinSide.add` consolidates rows with identical field values into one Z-set element, exactly as
  `StreamJoinTest.duplicateRowsAreCountedRatherThanCollapsed` documents, so the fixture needed a
  varying `tag` column to keep the three rows on each side distinct. Recorded in the test's own
  comment; not a product defect, a fixture-design fact the case file's own J1d/J2d convention already
  anticipated for the same reason (JOIN-022's note).
- **JOIN-017 — PASS.** `arrivalOrderDoesNotChangeTheAnswer`. Three arrival orders (right-then-left,
  left-then-right, interleaved) all give `pairsEmitted()==3`.
- **JOIN-018 — PASS.** `oneSideEmptyProducesNoPairsUntilTheOtherArrives`. Phase one: 0 pairs,
  `rowsHeldLeft()==2`, `rowsHeldRight()==0`. Phase two: 1 pair once the right side arrives — the
  two-phase structure is itself the W1 vacuity control per the case file.
- **JOIN-019 — PASS.** `bothSidesEmptyProducesNoOutputAndNoException`. `advanceWatermark` at two
  points over an empty join: 0 pairs, `evicted()==0`, no exception.
- **JOIN-020 — PASS.** `aNullKeyMatchesNothingIncludingAnotherNullKey`. 1 pair (u1×u1); the null-keyed
  rows are fed on both sides but `rowsHeldLeft()==1` and `rowsHeldRight()==1` — dropped entirely, not
  merely unmatched. Seed-proven (#3 above).
- **JOIN-021 — PASS.** `aCompositeKeyWithOneNullColumnIsAlsoUnmatchable`. `(u1,NULL)` is unmatchable
  even though its first column is real; 1 pair, `rowsHeldLeft/Right()==1`. Seed-proven (#3 above).
- **JOIN-022 — PASS.** `duplicateKeysMultiplyOnOneSideThenOnBoth`. Variant (a), right side duplicated:
  5 pairs, `keysHeldRight()==3`, `rowsHeldRight()==4`. Variant (b), both sides duplicated: 7 pairs,
  `rowsHeldLeft()==5`, `rowsHeldRight()==4`. Both match the file's hand computation exactly.

## §2 — The match window and eviction (JOIN-023 … JOIN-034)

- **JOIN-023 — PASS.** `theDefaultMatchWindowIsAnHourAndDecidesTheAnswer`. `+1s` and exactly `+1h`
  (inclusive) are emitted; `+1h+1s` is not. `pairsEmitted()==2`, `outsideWindow()==1`. Seed-proven
  (#1 above).
- **JOIN-024 — PASS.** `theDefaultWindowIsSymmetric`. `-1h` (inclusive) is emitted; `-1h-1s` is not.
  `pairsEmitted()==1`, `outsideWindow()==1`. Seed-proven (#1 above).
- **JOIN-025 — PASS.** `aStatedBoundReplacesTheDefaultAndIsDirectional`. Bounds `[-300s,0]`: `299s`
  before the right row rejected, `300s` and `600s` (the endpoints) accepted, `601s` rejected.
  `pairsEmitted()==2`, `outsideWindow()==2`, and the two rejected rows fall on opposite sides of the
  range — a symmetric implementation would not produce that. Seed-proven (#1 above).
- **JOIN-026 — PASS.** `aOneSidedBoundClosesAtZeroOnTheUnstatedSide`. Lower `-30s`, unstated upper
  falls back to `max(0,-30s)=0`, not an hour: `130s` (delta `+30s`) is rejected specifically because
  of that fallback. `pairsEmitted()==2`, `outsideWindow()==2`. Seed-proven (#1 above).
- **JOIN-027 — PASS.** `invertedBoundsAreRefusedAtConstruction`. `IllegalArgumentException` (no
  `PRV-` code, confirming the case's own finding) reading "...lower 60000000000ns is above upper
  -60000000000ns...".
- **JOIN-028 — PASS.** `aZeroWidthBoundStillGetsAPositiveMatchWindow`. Constructed with
  `matchWithinNanos=1`, `matchLower=0`, `matchUpper=0`; delta 0 emitted, delta `+1ns` outside;
  `evicted()==2` after `advanceWatermark(100s+2ns)` (the exact count: the right row and the matching
  left row, both at `100s`, are older than the `100s+1` horizon; the `100s+1ns` row is not). Seed-proven
  (#1 above, for the boundary half).
- **JOIN-029 — NOT RUN this session under HJ5** (it is inherently a SQL-parsing case —
  `collectTimeBound`/`collectEquiKeys` on a MONTH/YEAR interval — so it cannot be built from
  `JoinOperator` directly). Pre-existing `TemporalJoinTest.aWindowInMonthsIsNotAccepted` confirms the
  query fails to plan, but only asserts `PravahaException`, not the code or the exact message the case
  asks for (`PRV-2020`, "...neither an equality...nor a time bound...", with no mention of months).
  Recorded NOT RUN rather than PASS since the case's own falsifier ("the message explains the month
  problem") was not checked. Deferred to the SQL-level batch, where a message-content assertion will
  be added.
- **JOIN-030 — PASS.** `evictionReleasesRowsPastWatermarkMinusMatchWithin`. Three watermark advances,
  each read individually (not only at the end, per the case's own vacuity note): `evicted()` goes
  0 → 1 → 2, and `rowsHeldRight()` goes 3 → 2 → 1, matching the file's hand computation step for step.
- **JOIN-031 — PASS.** `anEvictedRowNoLongerMatchesAPartnerThatArrivesLater`. `evicted()==1` before
  the left row arrives; then 0 pairs and, critically, `outsideWindow()==0` — the pair was never a
  candidate (partner gone), not rejected by the time check. `rowsHeldLeft()==1`.
- **JOIN-032 — PASS.** `aLateArrivingRowStillMatchesInsideTheWindow`. A right row arriving after the
  watermark has passed its own event time still matches (1 pair, joined time `max(3s,0s)=3s`), and
  `evicted()==0` — the horizon at watermark `1800s` is negative and saturates below every row.
- **JOIN-033 — NOT RUN.** An enumeration case ("read every counter the operator exposes") rather than
  a falsifiable one; the counters themselves (`pairsEmitted`, `outsideWindow`, `evicted`,
  `unmatchedEmitted`, `rowsHeldLeft/Right`, `keysHeldLeft/Right`) are all exercised individually
  elsewhere in this file, but the specific diagnosability point — that a row whose partner was evicted
  and a row whose partner never existed are indistinguishable in every one of them — was not written
  up as its own assertion this session. Recorded NOT RUN rather than PASS; the finding itself is
  already on record as a fact in the case file's own Intent and is not disputed by anything found this
  round.
- **JOIN-034 — PASS.** `evictionSaturatesRatherThanWrappingAtTheBottomOfTheRange`. Both
  `advanceWatermark(Long.MIN_VALUE+1000)` and `advanceWatermark(Long.MIN_VALUE)` evict nothing;
  `rowsHeldRight()==3` throughout.

## §5 — the two outer-join eviction cases (JOIN-056, JOIN-057)

Executed out of file order because they share HJ5's harness and the match-window machinery just
covered; the remaining §5 refusals need SQL planning and are deferred with §3/§4 below.

- **JOIN-056 — PASS.** `aLeftOuterJoinEmitsTheNullPaddedRowOnceAtEviction`. Nothing emitted eagerly
  (`unmatchedEmitted()==0` immediately after feeding); nothing at the first, too-early watermark
  advance; at the second, the matched left row does **not** reappear null-padded
  (`unmatchedEmitted()==1`, `pairsEmitted()` stays 1) and `evicted()==3` (both left rows and the one
  right row). Seed-proven (#1 above, for the boundary half — a matched pair at delta exactly 0).
- **JOIN-057 — PASS (simplified fixture).** `aNullKeyedLeftRowIsNeverEmittedNullPadded`.
  `rowsHeldLeft()==1` before eviction (the null-keyed left row was never stored — confirmed by count,
  not by its absence from output, which is the case's W1). This run omits the case's third row (an
  o4-style non-null unmatched left row) that would additionally show "one null-padded row, from the
  non-null miss only" in the same run; that fuller three-row version is left for the next batch. The
  falsifier this case names — the null-keyed row appearing in the output — was directly checked
  (`unmatchedEmitted()==0`, no output at all) and does not occur. Seed-proven (#3 above).

## §4 — Lookup join, staleness, concurrency and cache bound (JOIN-049, 051, 052, 053)

New this batch: `pravaha-runtime/src/test/java/com/ash/messaging/pravaha/runtime/exec/LookupJoinBehaviorTest.java`,
a second HJ5-style harness, built directly against `LookupJoinOperator`/`LookupJoin` (package-private,
same reasoning as the join harness above) rather than through the registry or a live plugin.

Three assertions were seed-proven: (1) both guards on `cacheNanos > 0` around the in-flight lookup
map in `LookupJoin.process` (the mechanism that makes `cacheFor() == ZERO` refuse to share an
in-flight answer between concurrent records) — removing them broke exactly the two cases that depend
on cache being genuinely off (JOIN-049 and JOIN-052's zero-cache variant), and did not touch the
cache-on coalescing test; (2) the cache's access-order flag (`true` → `false` in the `LinkedHashMap`
constructor) — broke exactly the interleaved-access test (JOIN-053) and not the companion
not-revisited test; (3) the in-flight backpressure guard (`pending.size() >= maxInFlight`, disabled
outright) — broke JOIN-051's concurrency bound, observing all 100 lookups in flight at once instead
of 4. All three were reverted and the module re-verified green.

One seed did not distinguish: forcing `maxInFlight = source.maxConcurrency()` (removing the
`Math.max(1, …)` floor) did not change observed behaviour for a source reporting `0`, because the
`pending.size() >= 0` backpressure check is vacuously true before every record regardless of the
floor and so serialises lookups either way. Recorded as an unproven sub-assertion in JOIN-051's log
entry below rather than claimed as seed-proven; the case's core claim (a positive bound is respected)
is proven by the `maxConcurrency() == 4` test, which is seed-proven.

One test (`thePeriodTheSyntaxNamesIsNotHonoured`, JOIN-049) was flaky as first written: the plugin's
call count is incremented inside the async lookup, dispatched to a virtual thread, so without
draining after each feed the three lookups' *execution* order (and so which one sees "first call")
is a race against the *arrival* order the case is about. Fixed by draining after each feed, which is
what the case's own steps ("feed X, then feed Y") mean when the operator does the waiting internally
for every other JOIN-04x/05x case; confirmed stable over three repeated runs after the fix.

- **JOIN-049 — PASS.** `thePeriodTheSyntaxNamesIsNotHonoured`. Three lookups for one key, drained in
  arrival order: `(1,gold)`, `(3,platinum)`, `(5,platinum)` — the third order's event time (2s) sits
  *between* the first and second, and still gets the later value, because the lookup answers by
  wall-clock arrival, not by the event time `FOR SYSTEM_TIME AS OF` names. `dim.calls == 3` (W2:
  three separate lookups, not one cached answer, since `cacheFor()` is `ZERO`).
- **JOIN-050 — PASS (pre-existing).** `LookupJoinTest.outputStaysInArrivalOrderHoweverTheLookupsFinish`
  (30 records, one slow key among fast ones) and
  `LookupJoinTest.slowLookupsOverlapRatherThanQueueingBehindEachOther` (10×50ms lookups in <400ms,
  proving the overlap) together cover this case's two halves. Re-run this session; still PASS.
- **JOIN-051 — PASS.** `inFlightLookupsAreBoundedByTheSourcesMaxConcurrency`.
  `maxConcurrency()==4`, 100 distinct keys, 50ms each: the *source's own* independent counter (not
  the operator's) never exceeds 4, and the elapsed time (~1.25s at 4-way concurrency vs 5s serial) is
  asserted under a generous 3s bound as corroborating evidence. Seed-proven. A companion test for the
  `maxConcurrency() == 0` floor (`aZeroOrNegativeMaxConcurrencyFloorsAtOne`) passes but is **not**
  seed-proven — see above; recorded PASS on the strength of reading `Math.max(1, …)` in the
  constructor, not on a failing seed.
- **JOIN-052 — PASS.** Two tests. `aCacheableSourceCoalescesConcurrentRequestsForOneKey`
  (`cacheFor()=60s`, five same-key records faster than one 100ms lookup): `lookups==1`,
  `coalesced==4`, one call at the plugin. `aSourceRefusingCachingIsAskedOncePerRecordEvenForOneKey`
  (`cacheFor()=ZERO`): `lookups==5`, `coalesced==0`, `cacheHits==0`, five calls at the plugin — the
  plugin's own counter checked, not only the operator's, per the case's own vacuity note. Seed-proven.
- **JOIN-053 — PASS.** Two tests, cache bound 10. `aHotKeyKeptWarmByInterleavedAccessSurvivesAFloodOfColdKeys`:
  u1 revisited every third cold key stays warm, `callsPerKey(u1)==1`.
  `aHotKeyNotRevisitedIsEvictedByTwentyColdKeysPastABoundOfTen`: without interleaving, u1 is pushed
  out by the 20 cold keys and the final reference is a second miss, `callsPerKey(u1)==2`. Direct
  assertion of the cache's own size was not possible (`LookupJoin` does not expose it); the call-count
  evidence is the case's own falsifier and was checked directly. Seed-proven (the access-order half).

---

## §1/§2 revisited, §3 (lanes), §4 (reachability), §5 (SQL-level refusals)

New this batch: `pravaha-it/src/test/java/com/ash/messaging/pravaha/it/JoinPlanningAndReachabilityTest.java`,
covering everything in this round that needs `pravaha-sql` (a real plan built from SQL text) rather
than a hand-built `JoinOperator`.

Seed-proven: (1) the reversed-operand branch of `collectEquiKeys` (`leftKeys.add(second)` weakened to
`leftKeys.add(first)`) — broke JOIN-010's test with an `IndexOutOfBoundsException`, confirming the
branch is genuinely reached (not dead code Calcite's own canonicalisation makes unreachable — that
was checked directly, with a debug print, before concluding so) and load-bearing; (2) `mapDownToScan`'s
projection case (`project.sourceOrdinals().get(ordinal)` weakened to `ordinal`, i.e. ignoring the
projection's reordering) — broke JOIN-042's test, silently dropping 200 pairs to 50 on four lanes with
no exception, exactly the failure mode the case describes as "correct-looking, and wrong." Both
reverted; `pravaha-sql` and `pravaha-runtime` confirmed clean (`git status`) and `mvn verify` re-run
green on both modules afterward.

Two assertions in the case file's own quoted text turned out not to match the engine exactly, found
while writing the test and corrected with evidence rather than silently worked around:

- **JOIN-029.** The case says "nothing in the message mentions months, intervals or variable-length
  units." True of the *prose* — there is no sentence explaining the real reason — but the message
  also interpolates Calcite's own rendering of the rejected condition
  (`'>=($1, -($3, 1:interval month))'`), and that raw dump does spell out "month" (or "year").
  Recorded as: no explanatory phrase, but the raw unit name is present via the condition dump.
- **JOIN-043.** The case's quoted message reads `Compute(...)`; the engine's actual rendering, from
  `ComputeOperator.label()`, is `Compute[...]` with square brackets. Checked against the real string.

- **JOIN-009 — PASS.** `threeEquiKeysNarrowTheAnswerLikeTwoDoButForAThirdReason`. Three equalities
  (`user_id`, `region`, `seg`); 2 of 3 orders match, the third differs on both of the extra columns.
- **JOIN-010 — PASS.** `theEqualitysOperandsMayBeWrittenInEitherOrder`. `l.k = r.k` and `r.k = l.k`
  give identical output rows, compared by value. Seed-proven.
- **JOIN-013 — PASS.** `aFloat64KeyPlansFineAndFailsOnlyWhenThePipelineIsCompiled`. The plan itself
  succeeds (nothing in `buildJoin` checks key type); `InterpretedPipeline.compile` is where `PRV-3021`
  arrives, confirming the plan-time/run-time discrepancy the case's Intent names.
- **JOIN-014 — PASS.** `aDecimalKeyPlansFineAndFailsOnlyWhenThePipelineIsCompiledWithAThinnerMessage`.
  Same shape; message is one clause ("DECIMAL keys are not supported yet") with no "Round or cast"
  remedy, confirmed absent by assertion rather than by reading.
- **JOIN-029 — PASS** (superseding the earlier NOT RUN and the pre-existing `TemporalJoinTest` case,
  now with the exact code and message checked). See the correction above.
- **JOIN-035 — PASS (established by reading, reconfirmed).** `QueryRegistry:530` still hard-codes lane
  count 1 and `PluginSourceFeeds:116` still calls `pumpInto`, not `pumpPartitionedInto`; grep re-run
  this session, unchanged from the case file's own citation.
- **JOIN-036 — PASS (pre-existing).** `JoinOnLanesTest.feedingAMultiLaneJoinFromAnUnroutedPumpIsRefused`,
  re-run this session.
- **JOIN-037, 038 — PASS (pre-existing, partial).**
  `JoinOnLanesTest.aJoinAcrossFourLanesJoinsEveryPairWhenRowsAreRoutedByKey` establishes the count
  (200) and that more than one lane did work, but not the case's stronger per-key single-lane
  assertion, and not the explicit 1-lane-vs-4-lane equality as a comparison (038's own falsifier).
  Recorded PASS on the existing evidence's own terms; the per-key and explicit-equality assertions are
  additional rigor not yet written this round.
- **JOIN-039 — NOT RUN.** 2 and 8 lanes, and lanes exceeding key count, are not covered by any
  existing test at those specific counts.
- **JOIN-040 — PASS (pre-existing).** `JoinOnLanesTest.aPartitionedPumpAdvancesEventTimeLikeAnUnpartitionedOne`.
- **JOIN-041 — PASS.** `generatingWatermarksAfterAPumpHasAlreadyBeenCreatedIsRefused`.
  `IllegalStateException`, no `PRV-` code, confirmed absent by assertion.
- **JOIN-042 — PASS.** `theJoinKeyIsMappedDownThroughAReorderingProjectionRatherThanTakenAsIs`.
  200 pairs on 1 lane and on 4, through a projection that puts the join's key at a different ordinal
  than the scan's. Seed-proven.
- **JOIN-043 — PASS.** `aJoinKeyThatPassesThroughAComputedColumnCannotBeRoutedAndIsRefused`.
  `PRV-3021` naming `Compute[...]` (see correction above) and "Run this query on one lane"; the
  1-lane run of the same query is then confirmed to actually work (1 pair), so the suggested remedy
  is verified rather than assumed.
- **JOIN-044 — PASS (reconfirmed).** `aLookupJoinCannotBePlannedAgainstAStreamCatalog`, alongside the
  pre-existing `LookupJoinTest.joiningAStreamThisWayIsRefusedWithWhatToDoInstead`. The source-audit
  half of this case (enumerating every `SqlPlanner.with*` call site) was re-confirmed by grep, not
  re-run as a JUnit assertion — see JOIN-045 below for the same method.
- **JOIN-045 — PASS (source audit, not a JUnit case).** `grep -rn "lookup" pravaha-server/src/main/resources/application.yaml`
  found nothing; `StreamController`'s request handling (`pravaha-server/.../api/StreamController.java`)
  takes only a schema, no lookup flag. No JUnit test is the right instrument for a "this key does not
  exist anywhere" claim; recorded as HJ4/source-audit per the case's own Setup.
- **JOIN-046 — PASS.** `aValidLookupPlanCannotBeStartedThroughTheFiveArgumentOverloadTheRegistryUses`.
  `QueryExecution.start`'s five-argument overload (the one `QueryRegistry:530` calls) fails
  synchronously, from the `start()` call itself, not on the first record.
- **JOIN-047, 048 — PASS (pre-existing).** `LookupJoinTest.eachRecordIsEnrichedFromTheDimensionTable`
  / `.anInnerLookupJoinDropsARecordWithNoMatch` / `.aRegisteredQueryCanReachALookupJoin` (047);
  `.aLeftLookupJoinEmitsTheRecordWithNullsAndNeverRetractsIt` (048). Re-run this session.
- **JOIN-054 — PASS.** `rightAndFullJoinsBothSayToSwapTheInputsAndUseLeft`. Both `PRV-2020`, both name
  their own join type and "Swap the inputs and use LEFT" — the pre-existing `OuterJoinTest` only
  covered RIGHT; FULL is new evidence this session.
- **JOIN-058 — PASS.** Two tests. `aNonEquiConditionOnNonTimestampColumnsIsACrossProductAndIsRefused`
  covers (a) and (b) — an equality present elsewhere in the same `AND` does not rescue the inequality.
  `anInequalityBetweenTwoTimestampColumnsIsATimeBoundAndPlans` covers (c) — a plain `>` between two
  event-time columns is recognised and plans, unlike the non-timestamp case. The bounds/off-by-one
  observation the case also asks for (`>` recorded as `atLeast(0)`, admitting `delta == 0` through the
  inclusive `>=` test) was not separately asserted this session.
- **JOIN-059 — PASS.** `crossJoinIsRefused`: `CROSS JOIN` is rewritten by Calcite into a join whose
  condition is the literal `true`, which reaches `collectEquiKeys`' own fall-through (`PRV-2020`,
  "neither an equality... nor a time bound") — the specific one of the case's three candidate sites,
  confirmed by reading the thrown exception's type and message rather than assumed.
  `aTimeBoundWithNoEqualityIsRecognisedButHasNoKeyToIndexByAndIsRefused` covers the second shape.
- **JOIN-060 — PASS (a, b); NOT RUN (c).** `aSelfJoinPlansSuccessfullyAndFailsWithoutACodeOnlyWhenThePipelineIsCompiled`:
  the plan itself succeeds; `InterpretedPipeline.compile` throws an `UnsupportedOperationException`
  ("appears on both sides of this plan... self-joins are not supported yet") with no `PRV-` code,
  confirmed absent by assertion. The control (two distinct streams, same query shape) plans, compiles
  and produces one pair. Part (c) — the SDK/CLI wraps a registration refusal as `PRV-1041
  CLIENT_QUERY_REFUSED` (a real code, declared in `sdk/pravaha-sdk-java/.../ClientErrors.java`, that
  belongs to the *client's* refusal-reporting rather than to the *server's* actual reason) — needs a
  live node and the CLI/SDK round trip, not stood up this round; **NOT RUN**. This resolves what
  first read as a contradiction in the case's own title ("without a code") versus its body ("wrapped
  as PRV-1041"): both are true, at two different points in the same refusal's life.

---

## Running tally (JOIN-001 … JOIN-060)

| Verdict | Count | Cases |
|---|---|---|
| PASS | 55 | 003–032, 034–038, 040–059 |
| PASS (partial) | 1 | 060 — (a) and (b) PASS, (c) NOT RUN, needs a live node |
| NOT RUN | 2 | 033, 039 |
| Not yet reached | 2 | 001, 002 |

NOT RUN: 033 (an instrumentation enumeration — the counters it names are all exercised individually
elsewhere in this file, but the specific diagnosability point was not written up as its own assertion
this round) and 039 (the 2-lane, 8-lane and lanes-exceed-keys counts have no test yet, though the
1-lane/4-lane equality JOIN-038 asks for is established). **Not yet reached: 001, 002** — both need a
live node (HJ3) or the CLI (HJ4/CLI for 001 specifically, since `RunCommand` has no second `--stream`
flag to even attempt it); no node was stood up this round.

This closes three batches of the round. What remains for a future batch: standing up HJ3 (a real
`pravaha register`/`pravaha queries` node) for JOIN-001, 002 and JOIN-060(c); JOIN-039's lane-count
sweep; and JOIN-033's diagnosability enumeration as its own assertion.
