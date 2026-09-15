# ADR-037: state that degrades instead of dying

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — scope decision, B1 built, B2 not started |
| Date | 2026-09-15 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-006 (tiered state), ADR-008 (aligned checkpoints), ADR-013 (Z-sets), ADR-036 (one node, thousands of queries) |

## Decision

A query whose state outgrows its ceiling is **refused** today: `PRV-4001 STATE_TOO_LARGE` is thrown
from inside the lane, the lane dies, and the query is FAILED. The owner's requirement is that the
system be resilient instead — "fool proof", in his words — which means degrading rather than dying.

This is built in two stages, and the order is the decision.

**B1: make the ceiling visible before it is reached, and legible when it is.** Nothing in this
repository reports how much state a query holds. An operator learns the number for the first time in
the message that tells them their query is dead, and they cannot see the query that is at 90% at all.
Built now.

**B2: an on-disk tier, so a query that outgrows memory keeps running slower rather than stopping.**
Scoped here, not started.

## Why this order, given the measured workload

The owner's continuous queries hold **a few thousand keys each**. At roughly 150 bytes per windowed
accumulator that is about 450 KiB of heap per query, and a thousand of them about 450 MB — which
fits, and says plainly that **B2 is not what stands between this node and a thousand queries.**
ADR-036's per-query buffers were, and they are addressed.

So B2 is not capacity work. It is insurance against the case nobody planned: a `GROUP BY` on a column
whose cardinality was misjudged, an unwindowed join over two unbounded streams, a view that grows
because retention was never set. Those are real and they are surprises, which is precisely why the
instrument has to exist before the mechanism — an operator who cannot see state growing cannot act on
it, whether the engine spills or not.

Building B2 first would also be building it blind. There is no measurement of how much state a real
query holds, so there is nothing to size a cache against, nothing to say which tier a key should live
in, and no way to tell afterwards whether spilling helped.

## B1, as built

Per-query state size, reported by the thing that holds it: `SymmetricHashJoin` knows its rows per
side, `SlicedAggregateState` its accumulators, `ServedView` its keys. Each reaches an operator
through the registry and the metrics endpoint, and the ceiling stops being the first news.

## B2, as scoped

A query that reaches its ceiling spills the coldest part of its state to disk and keeps running.

**What must not change.** Exactly-once state is ADR-008's promise, and a checkpoint is already the
set of every lane's snapshot at one cut. A spilled tier is a second durable thing that has to be
consistent with that cut — so it is checkpointed with the state it belongs to, not alongside it.
Z-set semantics do not survive shedding: a retraction whose insert was evicted leaves a row that can
never be withdrawn, so **eviction is not an option and spilling is the only correct degradation.**

**What it costs, stated before it is agreed to.** A dependency (RocksDB or equivalent), a recovery
story that now has two sources, latency that becomes bimodal and therefore harder to reason about,
and a per-key decision about which tier it lives in. ADR-006 described this tiering and it has never
been built; that is not an accident of scheduling but a reflection of its size.

**What would make it urgent.** A workload whose steady state does not fit — which, on the numbers
above, this one does not have. Revisit when a measurement says otherwise, and B1 is what produces
that measurement.
