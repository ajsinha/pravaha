# ADR-045: cluster mode assigns queries, not rows

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **design only, not built**. Multi-node execution is on hold by the owner's decision; this records what ADR-039 item 8's consumer will be when it resumes |
| Date | 2026-09-19 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-034 (distribution deferred), ADR-036 (one node, thousands of queries), ADR-039 item 8, ADR-042 (the throughput bar), S-3 |

## Decision

In `PARTITIONED` mode the unit a node owns is a **computation** — one registered query's fingerprint,
with every name that shares it — not a slice of rows. A computation's owner is
`PartitionAssignment.ownerOf(hash(fingerprint) mod partitions)`, held under a fenced
`PartitionLease`. The owner runs the whole computation: it opens every source partition the query
reads, holds all its state, serves its view and writes its sinks. Nothing is shuffled between nodes.

## Why not partition rows

The obvious design — each node reads the source partitions it owns and processes only those rows —
is wrong for every query that aggregates or joins by a key. Source partitions (an Aerospike partition,
a Cassandra token range, a file) are not aligned with a query's `GROUP BY` or join key, so each node
would hold a partial total for every key it happened to see, and each would serve a wrong answer
with nothing to say so. Making it right needs a network exchange that routes every row to the node
owning its key — a distributed shuffle, with its own barriers, backpressure and checkpoint
alignment across nodes. That is the most expensive thing a streaming engine builds, and it buys
scale for a *single* query.

This engine's workload does not need that. ADR-036 is thousands of queries per node, and ADR-042 puts
the requirement at about 1,000 rows per second — four orders of magnitude below what one lane does
(ADR-042 cites 21 M rows/s for the lane machinery on the development laptop). What runs out first is
the number of queries a node can hold, and that scales by placing whole queries on more nodes.

An uncommitted agent draft, a per-row ownership gate in `PartitionedIngestPump`, is the rejected
design in miniature: it dropped rows a node could not prove it owned (lost, once the offset moved
past them), guarded a pump no registered query uses, and asked ZooKeeper twice per row. It is not
to be merged.

## How each existing piece is used

| Piece (built) | Role |
|---|---|
| `PartitionAssigner` / `PartitionAssignment` | Which node owns which virtual partition, recomputed on every membership change |
| `PartitionLeaseCoordinator` (ZooKeeper, fenced) | The owner proves ownership before it opens a computation's sources, and at every checkpoint |
| `PartitionHandoff` | Moving a partition: pause the computations in it, checkpoint, transfer the lease, restore on the target, resume |
| Checkpoints (ADR-008) | The state that moves. They must be readable by the target, so checkpoint and journal storage becomes shared (a mounted volume, or an object store behind the existing `CheckpointStore` interface, which `FileCheckpointStore` implements) |
| `StateOwnership` | Today it claims a node's whole checkpoint root; on shared storage the claim moves down to each computation's directory, taken only under that computation's lease |
| Registry journal | Becomes cluster-wide: every node must know every registration to know which it owns. Stored in the coordinator (ZooKeeper) rather than a local file in this mode |

## Reads and writes from any node

A client may connect to any node. Registration goes to whichever node receives it and is recorded
cluster-wide; the owner starts the computation. A read of a view is answered by its owner: Flight
SQL's `getFlightInfo` returns an endpoint whose `Location` is the owner's advertised Flight address,
so a Flight client fetches from the owner directly, with no proxying. The PostgreSQL gateway, which
has no redirect, proxies the read to the owner. A subscription attaches at the owner.

## What this does not give

- **Scale for one query beyond one node.** A single query's state and rate stay within one node's.
  Key-partitioned execution of one query across nodes needs the shuffle above, and is post-GA.
- **Zero-downtime moves.** A computation moving between nodes pauses for a checkpoint and a restore:
  seconds, with the view answering its last committed frontier meanwhile (the standby path already
  reports this as recovery time, not continuity).

## Consequences for S-3

`PravahaNode.refusePartitionedServing` is removed in the change that makes a node open a
computation's sources only while it holds that computation's lease, and nothing else — which is
the "same change that makes the mode real" ADR-039 requires. The test
`PravahaNodeTest#partitionedModeIsRefusedByANodeEvenOnACoordinatorThatExcludesSplitBrain` is what
fails and is inverted then.
