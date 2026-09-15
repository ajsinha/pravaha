# Gate P4 — end of Wave 5 (E4 Aerospike, joins, durability)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 5 of 11 — E4 Aerospike, joins, durability |
| Gate | P4, for milestone **M5 "It's durable, on Aerospike"** |
| Written | 2026-09-15, retrospectively |
| Verdict | **Wave complete. Gate P4 partly passed** — exact recovery is proven in-process and has never been proven by killing an operating-system process; the `W4 ≥ 5×` figure is unmeasured for the same hardware reason as P2 and P3 |

> **Written after the fact, from evidence in the repository.** Waves 5 and 6 ran without gate packs
> and this reconstructs what the evidence supports today — it is not a record of a decision taken at
> the time, because no such decision was recorded. Where the criterion has not been evaluated it says
> so rather than reasoning from what was built.

## The gate

M5's criterion is **"Kill a node mid-checkpoint; exact recovery"**, and the wave's own exit criterion
in the plan adds **"exactly-once state proven by chaos test; W4 ≥ 5×"**.

| Criterion | Status |
|---|---|
| Exact recovery after interruption | **PASSED, with a caveat about scope.** `CheckpointRecoveryTest.aRunInterruptedAndRecoveredProducesTheSameAnswersAsAnUninterruptedOne` asserts equality against an uninterrupted control run rather than against expected output, which is the stronger form. `JoinRecoveryTest` does the same for a join, crash-not-shutdown, with a duplicate row's weight crossing the interruption |
| Negative control | **PASSED.** `restoringWithoutTheStateWouldLoseTheEarlyWindows` proves the test can fail — recovery that restored offsets without state is caught |
| **Kill a *node* mid-checkpoint** | **PARTLY PROVEN since 2026-09-15.** `NodeCrashRestartTest` now kills a real JVM with `SIGKILL` and restarts it onto the same directory, which closes the *ownership* half and found W8-15 doing it. The **mid-checkpoint** half is still untested: the killed child holds state but writes no checkpoint, so a `SIGKILL` between the write and the atomic publish remains unexercised. `FileCheckpointStore` publishes atomically and writes a trailer — the mechanism is built, that scenario is not run |
| Chaos test | **DOES NOT EXIST.** No test in the repository injects randomised faults. The recovery tests are deterministic and single-scenario |
| `W4 ≥ 5×` | **NOT MEASURED** — the same three confounds as Gates P2 and P3: heterogeneous cores on this machine, no isolated runner, no reference hardware |

## What Wave 5 delivered

| Piece | Where |
|---|---|
| Checkpoint storage, atomic publish, trailer | `FileCheckpointStore` |
| State snapshot/restore over the lane control path | `QueryExecution.checkpoint/restore` |
| Bilinear join lift, checked against recomputation | `IncrementalJoin` |
| Join in the runtime and in SQL | `SymmetricHashJoin`, `JoinOperator`, `PhysicalPlanBuilder.buildJoin` |
| Join state checkpointed and restored, both sides | `StreamJoinTest`, `JoinRecoveryTest` |
| Join across lanes, routed by join key | `pumpPartitionedInto` |
| `LEFT` join with a time bound | `JoinOperator` |
| The README's query against a real Aerospike server | `AerospikeContinuousQueryIT` |

## What it did not do

Self-joins are refused. `RIGHT`/`FULL` are not built. A *stated* temporal join predicate in SQL is
still unparsed, so the match window cannot be chosen per query — it has a default and that default is
what made the join survivable. The exchange is not cut by a checkpoint barrier; a row in flight
between lanes causes a refusal rather than a loss, which is correct and is not the same as a cut.

## What would close this gate

The process-kill harness now exists (`NodeCrashRestartTest`), so what remains is smaller than it was:
extend its child to register a query, feed rows and checkpoint continuously, then kill it *during* a
checkpoint write and assert the recovered answers equal an uninterrupted control run. Plus a
randomised fault-injection harness, which still does not exist at all.

Both are buildable on this hardware. `W4 >= 5x` is not, and remains the only part of this gate
blocked on procurement.
