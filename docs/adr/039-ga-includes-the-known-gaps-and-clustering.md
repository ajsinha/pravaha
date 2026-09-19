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
| 2 | **W9-8 / W9-10** — wire `LaneMultiplexer` | Was built, tested, wired to nothing. W9-9 unblocked it; the aligned checkpoint barrier was the substance — three hundred queries on one lane each advancing a watermark every second would cut that lane's batches short several hundred times a second |
| 3 | **ADR-037 B2** — spill to disk | A query that reaches its ceiling is refused rather than degraded. Eviction is not an option: a retraction whose insert was evicted leaves a row that can never be withdrawn, so spilling is the only correct degradation |
| 4 | **W8-12** — off-heap state with variable-width keys | Same area as B2, which is why it follows it. The deleted `L0StateMap` could not key a `GROUP BY` containing a string |
| 5 | **W8-13** — sink bindings | What makes changelog negotiation reachable at all. When this was written nothing bound a query to a sink, so there was nothing for it to refuse |
| 6 | Pushdown, and a Cassandra plugin | The plugin SPI is proven across five plugins, so the connector is the more tractable of the two |
| 7 | The console as the §23.20 surface | Last: a separate discipline, and the only item on this list carrying no correctness risk |
| 8 | **Cluster mode** | Membership, partition assignment, rebalance, handoff, consensus. E7's original scope, deferred by ADR-034 |

## One consequence to notice before it surprises somebody

**S-3's refusal was temporary, and it has been removed before its condition was met.** `CoordinatorFactory`
refused `PARTITIONED` outright, because the mode started, reported itself partitioned, and
partitioned nothing. This ADR said the refusal should go *in the same change that makes the mode
real*. Item 8's first slice removed it when membership began producing a real assignment
(`PartitionAssigner`), and the second added fenced partition leases — but nothing in a running node
constructs a `PartitionAssigner` or asks for a lease before reading a partition. So **`PARTITIONED`
on a coordinator with consensus (`zookeeper`) now starts and serves every partition**, which is S-3's
original symptom again. Until item 8's consumer exists, a deployment must not read `PARTITIONED` as
anything more than a label.

The split-brain guard is unchanged and still ordered first: `PARTITIONED` on a mechanism that
cannot exclude split-brain (`socket`) is refused with `PRV-9002`.

## What has not changed

The blocker list is still not being waived and the gate packs still say what is unproven — P4 partly,
M6 not passed because its demo has never been performed. A longer road to GA is not a reason to start
rounding anything up.

**Progress against that rule, recorded 2026-09-19.** One GA-BLOCKER is open — `S-3`, reopened
because its refusal was removed early (above) — and no GA-REQUIRED finding. `SX-1` was already fixed in code and gained the test it lacked; `SX-5` lost its code channel to
authorizing the parsed name before planning, and its latency channel was then re-measured rather than
assumed — denied and absent reads cost 0.033 and 0.036 ms — and closed on the number. Item by item:

| | Where it stands |
|---|---|
| 1 | **Closed** — one reader per source binding (SRC-3) |
| 2 | **Closed in the registry, not reachable from a node.** A watermark no longer clamps a lane's batch (W9-10), and `QueryRegistry` hosts queries on shared lanes when asked to; no `pravaha.*` setting asks it to, and nothing decides which lane a registration lands on (W9-8) |
| 3 | **Closed, off by default** — `pravaha.state.spill.*` gives join and windowed-aggregate state a memory-mapped overflow tier; `COUNT(DISTINCT)` cannot spill and is refused by name with it on |
| 4 | **Closed except `COUNT(DISTINCT)`** — join indexes and windowed-aggregate accumulators are off-heap in `VariableKeyStateMap` |
| 5 | **Closed** — a registration names a sink, the changelog is checked before the sink opens, and every commit reaches it, at least once ([ADR-043](043-how-a-continuous-query-names-its-sink.md)) |
| 6 | **Built, and not yet used by a deployment** — projection and `COUNT`/`SUM` partial-aggregate pushdown exist in the planner and the engine, but no shipped plugin declares either and no ingest path delivers a partial. The Cassandra plugin is built, as a full `token()`-range scan |
| 7 | **Partly** — the console's read-side authorization gap and three literal §23.20 items are closed; the design-system surface is not built, by decision |
| 8 | **Two slices of several** — real partition assignment and fenced leases; no runtime consumer, so execution is single-node (see above) |
