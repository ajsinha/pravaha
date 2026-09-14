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

---

## Running tally (JOIN-001 … JOIN-060)

| Verdict | Count | Cases |
|---|---|---|
| PASS | 27 | 003–008, 011, 012, 015–028, 030–032, 034, 056, 057 |
| NOT RUN | 6 | 001, 002, 009, 010, 013, 014, 029, 033 (8, corrected count below) |
| Not yet reached | 27 | 035–055, 058–060, and the reachability pair 001/002 counted above |

(NOT RUN count is 8: 001, 002, 009, 010, 013, 014, 029, 033.)

This is one batch of a multi-batch round. §3 (lanes/routing), §4 (lookup join detail) and the
remainder of §5 (SQL-level refusals), plus the deferred SQL-planning halves of JOIN-009/010/013/014/029,
are picked up in the next batch under this same log file.
