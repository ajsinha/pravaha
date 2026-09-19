# ADR-044: no RocksDB — the memory-mapped overflow tier is L1

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **supersedes the RocksDB tier of [ADR-006](006-tiered-state.md) and design D5**. The mapped tier exists (ADR-037 B2); of the four improvements below, `COUNT(DISTINCT)` spilling is built and the other three are not |
| Date | 2026-09-19 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-006 (tiered state), ADR-037 (state that degrades), design D5 and §14 |

## Decision

State has two tiers in memory and on disk, not three. **L0** is off-heap: `RowStore` blocks behind
open-addressed tables (`VariableKeyStateMap`). **L1** is the memory-mapped overflow tier ADR-037 B2
built: once a query's memory ceiling is reached, `RowStore` carves further slabs from mapped files
under `pravaha.state.spill.directory`. **L2** is unchanged: checkpoint files, the only durable tier.

RocksDB is not added, and design D5's "L1 = RocksDB for spill/recovery" no longer describes the plan.

## Why

- **A native library is the one dependency this bundle refuses.** RocksDB reaches Java through JNI
  and ships a native binary per platform. The Cassandra driver's `jnr-posix` was excluded for exactly
  that reason — eighteen platform binaries in a deployment whose point is one bundle that runs
  anywhere. RocksDB would bring the same cost back for state, which is not optional.
- **The job D5 gave RocksDB is already done.** D5 wanted RocksDB for *spill*: somewhere for state to
  go when it outgrows memory. The mapped tier does that with nothing but `java.nio`, and because a
  `RowStore` block is addressed the same way whichever tier carved it, no operator knows or cares
  which tier holds its state. The operating system's page cache keeps hot slabs resident and pages
  cold ones out, which is the work RocksDB's block cache would have done.
- **Recovery never needed it.** D5 also said "spill/recovery". Recovery is L2's: a checkpoint holds
  operator state, offsets and the served view (ADR-008), and the mapped tier is scratch: a restore rebuilds
  state from the checkpoint, never from the mapped files.
- **The JNI cost D5 itself names.** 1–3 µs per RocksDB operation was 10–30 % of D5's own per-event
  budget, which is why D5 made RocksDB a tier rather than the whole store. A mapped block costs a
  memory access, and a page fault only when the page is cold.

## What RocksDB would have given that the mapped tier does not — and what replaces each

| RocksDB gives | The mapped tier when this was decided | What closes it | Status |
|---|---|---|---|
| Compaction: space reclaimed as keys churn | Freed blocks are reused within their size class and never defragmented, so a churning query's files can outgrow its live state | **Slab compaction**: move live blocks out of sparse slabs and release them | Not built |
| A disk budget in bytes | A ceiling in slabs (`max-overflow-slabs`); a full disk surfaces as an I/O failure | **A byte quota**, and a coded refusal before the directory runs out rather than after | Not built |
| Every state shape spills | `COUNT(DISTINCT)` keeps an on-heap set per group and is refused with the tier on (`PRV-3023`) | **Distinct sets in `RowStore`**, so they spill like everything else | **Built** 2026-09-19: one off-heap entry per `(group, slice, column, value)` with a count, in a `RowStore` that takes the overflow tier (`DistinctValueCounts`); `PRV-3023` retired |
| Years of production measurement | Correctness-tested, never measured with state much larger than RAM | **A measurement** at several multiples of RAM, and a decision from it on whether the tier should be on by default | Not built |

Those four are the work this ADR commits to. None of them needs a key-value store.

## As built

### `COUNT(DISTINCT)` spills

The per-group, per-slice sets are gone. `DistinctValueCounts` (`pravaha-runtime`, `window`) holds one
entry per `(group, slice, column, value)` — the group's 128-bit digest, the slice start, the column,
then the value's identity bytes — with the number of times the value is present, in a
`VariableKeyStateMap` over a `RowStore` that takes the overflow tier like every other one. A count
that reaches zero removes the entry, and a retraction of an absent value holds nothing, exactly as the
on-heap `HashMap.merge` did. A window's distinct count is computed when it fires: each value is counted
in the earliest of the window's slices that holds it, found by looking the same key up in each earlier
slice — a lookup per value, not a set on the heap. `PRV-3023 COUNT_DISTINCT_CANNOT_SPILL` is retired
(its number is not reused).

The windowed aggregate's checkpoint section is format 2 (the pipeline snapshot is version 5): the
distinct values follow the accumulators as a section of their own, and each accumulator now carries
its non-null counts, which format 1 dropped — a restored window's `AVG` came back as 0. Format 1, and a
version 3 or 4 pipeline snapshot holding windowed state, is refused by version; a version 4 or 3
snapshot of a plan with no windowed aggregate is the same bytes and still restores.

Proven by `DistinctValueCountsPropertyTest`: 20,000 random inserts and retractions (present and absent
values, weights of 1 and 2, nulls) over 40 groups and a hopping window, against the on-heap model,
with a two-slice ceiling so the state is in the overflow tier, a checkpoint restored into a fresh
directory halfway through, and slices discarded at the end — every window compared, four fixed seeds.

## What would make this wrong

State that must be larger than a node's disk-backed address space, or random-access patterns over
cold state so severe that page-cache behaviour loses to an LSM tree's. Either would show up in the
measurement above, which is why the measurement is part of the decision rather than an afterthought.
