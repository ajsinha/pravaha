# ADR-044: no RocksDB — the memory-mapped overflow tier is L1

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **supersedes the RocksDB tier of [ADR-006](006-tiered-state.md) and design D5**. The mapped tier exists (ADR-037 B2); the four improvements below are not built yet |
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

| RocksDB gives | The mapped tier today | What closes it |
|---|---|---|
| Compaction: space reclaimed as keys churn | Freed blocks are reused within their size class and never defragmented, so a churning query's files can outgrow its live state | **Slab compaction**: move live blocks out of sparse slabs and release them |
| A disk budget in bytes | A ceiling in slabs (`max-overflow-slabs`); a full disk surfaces as an I/O failure | **A byte quota**, and a coded refusal before the directory runs out rather than after |
| Every state shape spills | `COUNT(DISTINCT)` keeps an on-heap set per group and is refused with the tier on (`PRV-3023`) | **Distinct sets in `RowStore`**, so they spill like everything else |
| Years of production measurement | Correctness-tested, never measured with state much larger than RAM | **A measurement** at several multiples of RAM, and a decision from it on whether the tier should be on by default |

Those four are the work this ADR commits to. None of them needs a key-value store.

## What would make this wrong

State that must be larger than a node's disk-backed address space, or random-access patterns over
cold state so severe that page-cache behaviour loses to an LSM tree's. Either would show up in the
measurement above, which is why the measurement is part of the decision rather than an afterthought.
