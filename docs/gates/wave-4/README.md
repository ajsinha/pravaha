# Gate P3 — end of Wave 4 (E3 Stateful & incremental)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 4 of 10 — E3 Stateful & incremental |
| Date | 2026-09-10 |
| Verdict | **BLOCKED on the same hardware as Gate P2.** Every criterion that can be evaluated here passes; the throughput figure cannot be measured on this machine |

## Criteria

| Criterion | Required | Status |
|---|---|---|
| Unbounded `GROUP BY` rejected at planning, **naming the key** | yes | **PASS** — names columns, not ordinals, for composite keys too |
| Windowed aggregate runs end to end | yes | **PASS** — `TABLE(TUMBLE(...))` and `HOP`, SQL to results |
| Correctness invariants | 1–8 green | **PARTIAL** — see below |
| Profile B throughput | ≥ 350 k rec/s/lane | **NOT MEASURABLE HERE** — same three confounds as Gate P2 |

## What was built

| Piece | Where |
|---|---|
| Watermarks, per-partition generators, **idle detection** | `WatermarkTracker` |
| Event-time timer wheel | `TimerWheel` |
| Window slicing — one accumulator per record whatever the overlap | `SlicedWindows`, `WindowSpec` |
| Session windows, merge-on-insert | `SessionWindows` *(runtime only; no SQL surface)* |
| Incremental windowed aggregates, weighted, bounded | `SlicedAggregateState` |
| `COUNT(DISTINCT …)` inside a window | same |
| Late data: correction with retraction, or the late output | `WindowedAggregate` |
| Dead-letter queue and its rate monitor | `FileDeadLetterQueue`, `DeadLetterRate` |
| Changelog analysis and emit-mode negotiation | `ChangelogAnalysis` |
| L0 off-heap state map | `L0StateMap` |
| **The lane runtime joined to the SQL path** | `QueryExecution` |

The last of those is the one that changed the shape of the project. Before it, the lane runtime and
the SQL path were two halves that both worked and neither was the engine; `pravaha run` now goes
plugin reader → ingest pump → lane inbox → lane thread → pipeline → sink.

## Correctness invariants — what is and is not covered

| # | Invariant | Status |
|---|---|---|
| 1 | A record contributes to every window containing it, exactly once | **Green** — checked exhaustively against independently-computed windows |
| 2 | Retraction is the inverse of insertion | **Green** — same arithmetic, asserted |
| 3 | Window state is released once no window can need it | **Green** |
| 4 | Late records correct rather than corrupt | **Green** — retract-and-replace, or routed to the late output |
| 5 | Arrival order does not change session boundaries | **Green** — 200 shuffles |
| 6 | Unbounded state is refused, not discovered | **Green** |
| 7 | Watermarks never regress; an idle partition never freezes them | **Green** |
| 8 | Checkpoint/restore round-trips state | **NOT YET** — checkpointing is Wave 5 |

Seven of eight. The eighth needs machinery that does not exist yet, and claiming it green would be
the kind of gate that means nothing.

## Retrospective

**Nineteen bugs were seeded across the wave; five of them passed.** Every one of those five was a
weakness in a test, and the pattern behind them is worth more than the individual fixes:

- **Unreachable guards absorb the mistake they appear to catch.** Two conditions in `SlicedWindows`
  could never be false, so seeding a broken boundary rule into them changed nothing and the tests
  went green. Both are gone; the invariants are enforced by where the loops start.
- **A loop that can only run once hides that nothing exercises it.** The session merge looped
  forward; sessions are maximal, so at most one merge is ever possible. Seeding "merge once" passed,
  and the loop is now a single step with the proof beside it.
- **Assert the mechanism, not a proxy.** The timer wheel's `size()` deduped by key and so could not
  see stale bucket entries accumulating; `pendingEntries()` exists because a test that cannot observe
  the mechanism asserts nothing.
- **Never derive a test's expectation from what it tests.** The stage-splitting test computed its
  expectation from the constant under test, so raising that constant raised the expectation.
- **A threshold nobody measured passes whatever happens.** The metaspace ceiling was guessed at
  64 MB; the seeded leak is 29.5 MB. Measured both outcomes, put the bar between them.

**Three seeded bugs hung the build instead of failing it** — twice in the lane suites, once in the
state map, where the probe loop had no bound at all. `@Timeout` interrupts, and nothing spinning on
`onSpinWait` or looping over an array observes an interrupt. Every wait in the suite is now
deadline-bounded, and `L0StateMap` throws with exact counts rather than spinning. A hanging test
reads in CI as an infrastructure problem, gets retried, and tells nobody anything.

**Three claims written in comments turned out to be false**, each found by seeding the thing the
comment said was load-bearing: `AdaptiveStage`'s row-safety came from atomicity rather than from
reading a field once; `L0StateMap`'s hash finaliser is defensive rather than necessary; and the
windowed aggregate's per-query quota, specified in the story, cannot exist without either dropping
rows or reintroducing a per-query buffer. All three now say what is true.

**The plan under-counted for the fourth consecutive wave.** Not through new stories this time but
through integration: joining the lane runtime to the SQL path was not a story anywhere, and it was
the most valuable day of the wave.

---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**</sub>
