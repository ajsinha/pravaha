# ADR-037: state that degrades instead of dying

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — B1 built; B2 built for stream-to-stream join state and windowed-aggregate state, configured by `pravaha.state.spill.*` and off by default; `COUNT(DISTINCT)` spills too since ADR-044 (it was refused by name with a code now retired) |
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

## B2, first slice: a stream-to-stream join's state, and the dependency question answered

**No new dependency.** `RowStore` already has everything a key-value store would otherwise be
adopted to provide — size classes, a free list, block reuse — and every accessor
(`allocate`/`release`/`regionOf`) already addresses a slab by its index in one list, never by which
`MemoryAccess` carved it. The only thing missing was a `MemoryAccess` whose regions live on disk
instead of RAM, and `java.nio`'s `FileChannel.map` provides exactly that with no native library and
nothing this bundle would need to ship per platform — the cost this ADR named as real ("a
native-code dependency … as the Cassandra driver's JNI was"). `RowStore` now accepts an optional
second `MemoryAccess` and a ceiling on slabs carved from it (`pravaha-state`'s
`com.ash.messaging.pravaha.state.spill` package); once its primary ceiling is reached it carves the
next slab from the overflow tier instead of refusing, and every existing constructor and caller is
unchanged when no overflow tier is given.

**Why the join first.** Both sides of a stream-to-stream join keep every row that could still match,
with no eviction possible (Z-set semantics: a retraction whose insert was evicted can never be
withdrawn) — the exact "case nobody planned" this ADR opens with: an unwindowed join over two
unbounded streams. `SymmetricHashJoin` now accepts the same optional overflow tier, wired in
`InterpretedPipeline` behind a system property (`pravaha.state.spill.directory`, unset by default —
the same choice `pravaha.pgwire.enabled` makes for TLS, for the same reason: this has a real cost and
a deployment should choose it, not inherit it).

**The checkpoint invariant held without being touched.** `JoinSide.writeTo`/`readFrom` already walk
live entries by handle and ask each one's `MemoryRegion` for its bytes; they were never told which
tier a handle's slab came from, and still are not. A checkpoint taken while state is spilled is
therefore already the one thing this ADR insisted on — state on disk checkpointed *as part of* the
same snapshot as everything else, never a second durable thing beside it — proven by a test that
takes a snapshot from a join with spilled state and restores it into a fresh join, fresh arena, fresh
spill directory, with no shared object between the two.

**What was measured, not asserted.** Two numbers, both in `RowStoreSpillMeasurementTest`
(`pravaha-state`): per-operation latency in the overflow tier ran about 2x the RAM tier's, measured
after warming up both code paths so neither number is inflated by JIT order; and 4 RAM slabs plus 60
overflow slabs held 16x as many rows as 4 RAM slabs alone. Both numbers carry the same caveat, stated
in that test's own javadoc rather than left implicit: a few megabytes of mapped file, written and
read within milliseconds, mostly never leaves the operating system's page cache on the machine this
was run on, so the 2x figure is the cost of one more layer of indirection through `MappedByteBuffer`,
not a measurement of physical disk latency. A workload whose spilled state is large or old enough to
actually miss the page cache will cost more, and finding out how much is exactly the kind of
measurement a real deployment's metrics — not this test — should produce.

**What this slice did not cover, since covered.** Windowed aggregate state (`SlicedAggregateState`)
has an overflow tier now (its accumulators moved into a `RowStore`-backed `VariableKeyStateMap`),
and the spill directory is a deployment setting, `pravaha.state.spill.{enabled,directory,max-overflow-slabs}`,
read by the node at startup. What follows is the note as written for the first slice. Windowed and
keyed aggregate state did not have an overflow tier yet; the join was chosen as the one case this ADR names directly, not as a
claim that it is the only one that matters. There is no `application.yaml` key or `LaneProperties`
setting for the spill directory — it is a system property today, matching the level of
configurability `MAX_JOIN_STATE_SLABS` itself has (none), and a deployment-facing setting is a
follow-on decision, not one this slice made.
