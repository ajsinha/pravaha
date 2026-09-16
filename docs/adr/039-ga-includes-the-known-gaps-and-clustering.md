# ADR-039: GA includes the seven known gaps, and then clustering

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **supersedes [ADR-038](038-one-node-ga.md)** |
| Date | 2026-09-16 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-034 (distribution deferred), ADR-035 (wave 8 is survival), ADR-036 (one node, thousands of queries), ADR-037 (state that degrades), ADR-038 (one-node GA — superseded) |

## Decision

ADR-038 cut GA to a single node and moved everything else to a roadmap. That is no longer the plan.
**GA now requires the seven known gaps closed, and then cluster mode**, in that order. GA is the last
step rather than the next one.

ADR-038 is superseded rather than deleted: it records the reasoning for a smaller GA, and that
reasoning is still sound if the scope is ever cut again. What it must not do is read as current,
which is why this ADR exists rather than an edit to that one.

## The order, and why it is this order

Sequenced by **what a wrong answer costs**, not by what is quickest.

| | Item | Why here |
|---|---|---|
| 1 | **SRC-3** — one reader feeding many queries | The only gap whose cost lands on *somebody else's* infrastructure. A thousand queries over one Aerospike set are a thousand scans of it today; the node side of that question was answered by W9-11 and this is the half that was not |
| 2 | **W9-8 / W9-10** — wire `LaneMultiplexer` | Built, tested, wired to nothing. W9-9 unblocked it; the aligned checkpoint barrier is the substance — three hundred queries on one lane each advancing a watermark every second would cut that lane's batches short several hundred times a second |
| 3 | **ADR-037 B2** — spill to disk | A query that reaches its ceiling is refused rather than degraded. Eviction is not an option: a retraction whose insert was evicted leaves a row that can never be withdrawn, so spilling is the only correct degradation |
| 4 | **W8-12** — off-heap state with variable-width keys | Same area as B2, which is why it follows it. The deleted `L0StateMap` could not key a `GROUP BY` containing a string |
| 5 | **W8-13** — sink bindings | What makes changelog negotiation reachable at all. Nothing binds a query to a sink today, so there is nothing for it to refuse |
| 6 | Pushdown, and a Cassandra plugin | The plugin SPI is proven across five plugins, so the connector is the more tractable of the two |
| 7 | The console as the §23.20 surface | Last: a separate discipline, and the only item on this list carrying no correctness risk |
| 8 | **Cluster mode** | Membership, partition assignment, rebalance, handoff, consensus. E7's original scope, deferred by ADR-034 |

## One consequence to notice before it surprises somebody

**S-3's refusal is temporary and its removal condition is here.** `CoordinatorFactory` now refuses
`PARTITIONED` outright, because the mode started, reported itself partitioned, and partitioned
nothing — `PartitionAssignment`, `Rebalancer` and `PartitionHandoff` are referenced from no running
path. The refusal cites ADR-034, which is correct today and becomes wrong the moment item 8 lands.

Whoever builds clustering removes that refusal **as part of the same change that makes the mode
real**, and not before. The test `partitionedModeIsRefusedEvenOnACoordinatorThatCouldSupportIt` is
what will fail and say so; `state106`'s reachability check over those three types is the other half,
and it fails the moment any of them appears on a running path. Both were written to fail in exactly
this situation, which is the point of them.

The split-brain guard in `CoordinatorFactory` is ordered *before* the S-3 refusal deliberately, so
that removing S-3's refusal restores the more specific diagnosis rather than leaving a gap.

## What has not changed

The blocker list is still not being waived and the gate packs still say what is unproven — P4 partly,
M6 not passed because its demo has never been performed. A longer road to GA is not a reason to start
rounding anything up.

**Progress against that rule, recorded 2026-09-16.** `SX-1` is **fixed** — it was already fixed in
code when this ADR was written and its status line said otherwise; what it lacked was a test pinning
the order of the authorization and the lookup. `SX-5` has lost two of its three channels, enumeration
and now the refusal codes, and **stays a GA-BLOCKER on the latency channel alone** rather than being
counted as narrowed. Item 1 (`SRC-3`) is closed, and GA-REQUIRED is empty. That leaves items 2
through 8 and one blocker on one channel.
