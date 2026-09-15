# ADR-034: distribution is deferred, and the rung below it is built instead

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-12 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-004 (partitioned lanes), ADR-009 (embedded Raft metadata), §21, §13 |

## Decision

Pravaha targets **one node, scaled to its cores**. Multi-node execution is deferred — not
abandoned, and not scheduled.

The work that would have gone into distribution goes instead into **key-partitioned ingestion for
aggregates**, which is the missing rung below it.

## The ladder, and where it stops

| | |
|---|---|
| One lane | Works |
| Many lanes, one node | Works for **joins**. Refused for keyed aggregates (`PRV-3020`) |
| Many nodes | Needs a networked exchange and partition-granular state; neither exists |

The middle rung is the point. `pumpPartitionedInto` routes each row to the lane owning its **join**
key and refuses a query that has no join, so there is no way to spread a keyed aggregate across
lanes. Attempting it anyway — one pump per lane — gave every lane its own partial total for any key
it happened to see, and emitted all of them: eighteen groups as thirty-six rows of half-answers,
with no error. That combination is now refused.

So the most common query shape in this engine is effectively single-lane. Distribution would put a
multi-node story on top of a single-node parallelism story that is not finished.

## Why the rung below is not throwaway work

Routing a row to the lane that owns its group **is** the shuffle distribution needs, performed
inside one process. Building it there first buys three things:

- **Keyed aggregates scale to cores**, which is immediate value on the shape most queries take.
- **The routing abstraction exists**, with its semantics worked out somewhere they can be debugged
  with a debugger rather than a packet capture.
- **The state layer is forced to become partition-aware.** Lanes would own key ranges, which is
  exactly the blocker §21 runs into: `PartitionOwner.snapshot(int partition)` demands a granularity
  `WindowedAggregate` and `JoinSide` cannot produce, because each stores every key in one structure
  with no partition boundary in it. (`L0StateMap` was named here too and was deleted in Wave 8 —
  W8-12 — having never held any state.)

What it does not commit anyone to: a networked exchange, membership, handoff, or split-brain.

## Why not distribute

**The data plane would be replaced, not extended.** `LaneExchange` is an `N×(N-1)` matrix of
single-producer rings in the process's own off-heap arena — a row crossing lanes is a copy and two
cursor writes, which is only true because sender and receiver share an address space. There is no
framing, no serialisation, no network path. Its own javadoc records that it is single-hop by
construction and that a multi-stage shuffle needs credit-based flow control that is not built.

**It is the wrong direction competitively.** The one position no competitor occupies is embeddable
plus incremental plus store-native — and *embeddable* means in-process. Multi-node is the ground
Flink, Materialize and RisingWave already hold, several engineer-years ahead, and still moving.
"Scales to the cores of one machine and embeds in your service, with no cluster to operate" is a
coherent product. "A smaller Flink" is not.

**Nobody has asked for it.** No workload has been presented whose state or throughput does not fit
one machine. Building for a constraint nobody has brought is how a project spends years on the part
of the problem it imagined.

## What happens to `pravaha-cluster`

It stays, and it gains an honest status note rather than being deleted. The membership, leadership,
rendezvous assignment, handoff ordering and the guarantees rule are sound work, and the protocol
layer would survive a data-plane rewrite unchanged. What it must not do is read as "clustering
exists": there is no production implementation of `PartitionOwner`, and `PartitionAssignment`,
`PartitionHandoff` and `Rebalancer` are tested against mocks.

## What this supersedes

The multi-node parts of §21 and ADR-009's embedded-Raft control plane are deferred with this. ADR-009
remains accepted as a decision about *how* metadata consensus would be reached if it were reached;
it is not a description of anything built, and it should carry that note.

## What would reverse this

One fact, and only one: **a real workload whose state or throughput genuinely does not fit a single
machine** — a design partner's measurements, not a projection. At that point the ladder is climbed
in order, the middle rung is already built, and the exchange is the piece to replace.

## Consequences

**The engine is single-node, and the documentation says so** rather than describing a cluster that
is one wiring change away. It is not one wiring change away.

**Throughput has a ceiling set by one machine's cores**, and after key-partitioned aggregates that
ceiling is a real number rather than one lane.

**`pravaha-cluster` is carried without being used.** That is a deliberate cost: deleting it would
discard correct work and the decisions inside it, and re-deriving the guarantees rule later would be
worse than carrying the code.
