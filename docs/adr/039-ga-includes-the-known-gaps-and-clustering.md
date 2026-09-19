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

**S-3's refusal is temporary, and its removal condition is here.** `CoordinatorFactory` refused
`PARTITIONED` outright, because the mode started, reported itself partitioned, and partitioned
nothing. This ADR said the refusal should go *in the same change that makes the mode real*. Item 8's
first slice removed it from the factory when membership began producing a real assignment — true of
the cluster layer, and not of a node, which still constructs no `PartitionAssigner` and takes no
lease before reading. For three days a `PARTITIONED` node started and served every partition, and
S-3 was reopened on 2026-09-19. The refusal now lives in `PravahaNode` (`refusePartitionedServing`,
`PRV-9002`), where the claim would be made: the factory builds the coordinator, so the library can be
tested as what it is, and a node refuses to serve on its strength. **Whoever builds item 8's consumer
removes that node-side refusal in the same change**, and
`PravahaNodeTest#partitionedModeIsRefusedByANodeEvenOnACoordinatorThatExcludesSplitBrain` is what will
fail and say so.

The split-brain guard is unchanged and still ordered first: `PARTITIONED` on a mechanism that
cannot exclude split-brain (`socket`) is refused with `PRV-9002`.

## What has not changed

The blocker list is still not being waived and the gate packs still say what is unproven — P4 partly,
M6 not passed because its demo has never been performed. A longer road to GA is not a reason to start
rounding anything up.

**Progress against that rule, recorded 2026-09-19.** No GA-BLOCKER and no GA-REQUIRED finding is
open — `S-3` was reopened and closed again the same day (above). `SX-1` was already fixed in code and gained the test it lacked; `SX-5` lost its code channel to
authorizing the parsed name before planning, and its latency channel was then re-measured rather than
assumed — denied and absent reads cost 0.033 and 0.036 ms — and closed on the number. Item by item:

| | Where it stands |
|---|---|
| 1 | **Closed** — one reader per source binding (SRC-3) |
| 2 | **Closed, off by default** — `pravaha.lane.multiplex.*` puts registered queries on a fixed set of shared lanes, each registration on the least loaded one below a per-lane ceiling, and one no lane will take gets a lane of its own rather than a refusal; `pravaha_lane_shared_queries` and `pravaha_lane_own_queries` show where they went (W9-8). A watermark no longer clamps a lane's batch (W9-10). **Narrower than the item hoped:** a shared lane carries one query per stream, because each query is fed separately and two over one stream on one lane each counted the other's rows — measured at 8 where 4 was right. One ingest per stream per lane would lift that and is not built |
| 3 | **Closed, off by default** — `pravaha.state.spill.*` gives join and windowed-aggregate state a memory-mapped overflow tier; `COUNT(DISTINCT)` cannot spill and is refused by name with it on |
| 4 | **Closed except `COUNT(DISTINCT)`** — join indexes and windowed-aggregate accumulators are off-heap in `VariableKeyStateMap` |
| 5 | **Closed** — a registration names a sink, the changelog is checked before the sink opens, and every commit reaches it, at least once ([ADR-043](043-how-a-continuous-query-names-its-sink.md)) |
| 6 | **Built, and used by a deployment** — projection is pushed into JDBC (the `SELECT` list), Aerospike (the scan's bins) and Cassandra (the CQL `SELECT` list); a continuous `COUNT`/`SUM` is pushed into JDBC as one partial per keyset page and folded into the aggregate through the lane (`PartitionReader.deliversPartialAggregate`), proven equal to the rows against H2 through inserts, re-read updates and unseen deletes, and against Postgres; a shared reader pushes the OR of its queries' filters and is rebuilt only when idle as they join and leave. Not claimed: partials from Aerospike or Cassandra, partials for windowed aggregates, Cassandra filters, and pushdown in `EXPLAIN`. The Cassandra plugin is built, as a full `token()`-range scan |
| 7 | **Partly** — the console's read-side authorization gap and three literal §23.20 items are closed; the design-system surface is not built, by decision |
| 8 | **Two slices of several** — real partition assignment and fenced leases; no runtime consumer, so a node refuses to serve `PARTITIONED` (see above). The consumer is designed in [ADR-045](045-cluster-mode-assigns-queries-not-rows.md) — a node owns whole computations, not rows — and is on hold |
