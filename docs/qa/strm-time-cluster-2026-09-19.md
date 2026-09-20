# The STRM and TIME clusters, closed against the code of 2026-09-19

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

> **What this is.** Batch B14's streams-and-event-time slice: the ten open `STRM-` findings and the
> six open `TIME-` findings in [`FINDINGS.md`](FINDINGS.md), each reproduced before it was touched
> and each fix seed-proven. It **does not edit `FINDINGS.md`**, which is the lead's; the verdicts
> here are what the lead applies from.
>
> Its starting point is [`verification-2026-09-19.md`](verification-2026-09-19.md), whose evidence
> for several of these had already moved — `SharedClock` is the scheduler, the tick clamp lives in
> `SharedClock.every`, `GET /api/v1/queries` exists. Each section below says where the mechanism is
> **today**.

One section per finding: verdict, cause, fix, test, seed-proof, commit.

---

## `STRM-1` — a weight-0 change is delivered as a positive change and applies nothing

**Verdict: REPRODUCED, FIXED.**

**Cause.** Two mechanisms, one of them right. `ServedView.applyWeighted` returning on `weight == 0`
is **correct** Z-set arithmetic and not the defect: a row is present while its weights sum positive,
and adding zero moves that sum nowhere, so the view keeps the row it held. The defect is one level
up. `ViewSink.apply` staged the zero-weight row into `pending` like any other, so the change stream
said something had happened that had not; and `ViewChange.isRetraction()` is `weight < 0`, so the
change reported itself as an *insertion*. The consumption model `ViewChange`'s own javadoc and
`CONCEPTS.md` §4 both recommend — "ignore negative weights and overwrite by key" — therefore wrote
the zero-weight row's values into a copy of a view that had not changed. That is the divergence: the
finding's own note that the view and a replaying consumer "agree by accident" holds only for a
consumer that applies weights, and the documented alternative diverges.

**Fix.** `ViewSink.apply` stages nothing for `weight == 0`, so the change stream and the view agree
by construction rather than by both happening to keep the old row. `ViewChange` gains `isInsertion()`
(`weight > 0`) so a consumer has the positive question to ask instead of negating `isRetraction()`,
and its javadoc records why the negation is a trap. `CONCEPTS.md` §4 gains the paragraph.

**Test.** `ViewSinkTest.strm1AZeroWeightChangeIsNotDeliveredAndTheViewKeepsWhatItHeld` — commits
`[u1, 1]` at weight 1, then `[u1, 9]` at weight 0, and asserts the subscriber is told nothing, the
view still reads `1`, and a hand-built zero-weight `ViewChange` answers false to both questions.

**Seed-proof.** `if (weights[i] != 0)` put back to `if (true)`: 12 run, **1 failure** —
`Expecting empty but was: [+0[u1, 9]]`. Restored, 12 run, 0 failures.

**Commit.** _see below_
