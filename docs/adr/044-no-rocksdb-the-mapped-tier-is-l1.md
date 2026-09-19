# ADR-044: no RocksDB — the memory-mapped overflow tier is L1

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **supersedes the RocksDB tier of [ADR-006](006-tiered-state.md) and design D5**. The mapped tier exists (ADR-037 B2); the four improvements below are built and the measurement is recorded, with its recommendation: the tier stays off by default |
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
| Compaction: space reclaimed as keys churn | Freed blocks are reused within their size class and never defragmented, so a churning query's files can outgrow its live state | **Slab compaction**: move live blocks out of sparse slabs and release them | **Built** 2026-09-19: `RowStore.compactOverflow`, driven by each state's owner between batches, triggered by `pravaha.state.spill.compaction-threshold` |
| A disk budget in bytes | A ceiling in slabs (`max-overflow-slabs`); a full disk surfaces as an I/O failure | **A byte quota**, and a coded refusal before the directory runs out rather than after | **Built** 2026-09-19: `pravaha.state.spill.max-bytes`, the node's budget across every query (`PRV-4005`), and a free-space check before every slab (`PRV-4006`); `max-overflow-slabs` kept as the per-store ceiling |
| Every state shape spills | `COUNT(DISTINCT)` keeps an on-heap set per group and is refused with the tier on (`PRV-3023`) | **Distinct sets in `RowStore`**, so they spill like everything else | **Built** 2026-09-19: one off-heap entry per `(group, slice, column, value)` with a count, in a `RowStore` that takes the overflow tier (`DistinctValueCounts`); `PRV-3023` retired |
| Years of production measurement | Correctness-tested, never measured with state much larger than RAM | **A measurement** at several multiples of RAM, and a decision from it on whether the tier should be on by default | **Done** 2026-09-19: `SpillTierMeasurementIT`, 1x–16x a 64 MiB ceiling (below); recommendation: stays off by default |

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

### Slab compaction

A moved block has a new handle, and a store cannot know where its handles are kept — so compaction
is driven by the **owner** of the handles, which is the only thing that can rewrite them.
`RowStore.compactOverflow(threshold, owner)` picks the overflow slabs no more than `1 - threshold` full (never
the one being carved), rethreads the free lists without them, and calls the owner with a relocation
function; the owner presents every handle it holds once and stores what comes back. A handle into a
slab being emptied is moved to a same-class block elsewhere (a free one first, RAM before overflow,
else freshly carved), the payload copied whole; any other is returned unchanged. Slabs left with no
live block are released: the region is closed, and its file **truncated** before it is deleted, because
Java cannot unmap a `MappedByteBuffer` and a deleted file's blocks stay allocated while any mapping of
it lives. A released index is reused by the next overflow slab, so no surviving handle changes meaning.
A block that cannot be placed — the tier's ceiling, its quota or the disk refused a new slab — stays
where it is and its slab is kept: compaction can fail to free a slab, never lose a block.

The owners: `VariableKeyStateMap` rewrites its slot (the fingerprint does not change, so no slot
moves), which covers a windowed aggregate's accumulators and distinct values; a join walks each
side's chains from the bucket head, rewriting the head or the previous entry's `next` link. It runs
from `InterpretedPipeline.endOfBatch()` — the end of an input batch, a watermark advance, an emission —
on the lane thread, the one moment no operator holds a handle anywhere but in its own index, which is
the rule a checkpoint's `writeTo` already lives by. Triggered when a store has at least two overflow
slabs, something was freed since its last pass, and `1 - live / carved` over its overflow slabs reaches
`pravaha.state.spill.compaction-threshold` (default 0.5). Exposed per query as
`pravaha_query_spill_{bytes,live_bytes,fragmentation,compactions,slabs_released}`.

Proven by `RowStoreCompactionTest`: 20,000 blocks in three size classes spilled over ~140 overflow
slabs, nine in ten released at random, one compaction — every surviving block reads back its key and
check value through its rewritten handle, the overflow slabs held come to within two of the live bytes
rounded up to slabs, the directory holds exactly one file per slab held, and the store carries on
allocating into released indices; four fixed seeds. The operators' own tests show the owners do their
part: a join with four rows per key, seven in eight retracted from heads, middles and tails of chains,
compacts, checkpoints byte-for-byte as it did before, and every surviving row is matched from the
other side; a windowed aggregate with distinct values fires identically and checkpoints identically
after compacting away its discarded slices.

### A byte quota, and a refusal before the disk is full

`pravaha.state.spill.max-bytes` (a size, `20GB`; default `0`, no quota) is the **node's** budget for
spilled state. There is one `MappedFileMemoryAccess` per node, so every query's overflow slab counts
against it from the moment it is mapped until its region closes — compaction's releases included.
A slab past it is refused with `PRV-4005 STATE_SPILL_QUOTA_REACHED` before a file exists; the store
that asked is unchanged and can still reuse its own free blocks. The reservation is a compare-and-set
on one counter, because lanes share the access.

`max-overflow-slabs` is **kept**, not replaced, and not deprecated: it bounds one state store in its
own slab size, so a single runaway join cannot take the node's whole budget, where `max-bytes` is the
budget in the unit a disk is sized in. Both keys are declared in `application.yaml` and documented in
OPERATIONS.

Independently of the quota, the spill directory's filesystem is asked for its usable space before
every slab, and a slab that would not fit is refused with `PRV-4006 STATE_SPILL_DISK_FULL`. Slabs are
sparse files that take disk page by page as state is written, so the failure this replaces was not an
`IOException` but a fault inside a write to mapped memory. The check is per slab and not a
reservation — another process can fill the filesystem between two slabs — which is why `max-bytes`
below what the filesystem holds is the setting to rely on. Exposed as
`pravaha_state_spill_bytes_mapped`.

Proven by `SpillQuotaTest`: three slabs fit a three-slab quota and the fourth is refused with
`PRV-4005` and no file; closing one gives its bytes back and the next fits; with the filesystem's free
space stubbed below a slab, the slab is refused with `PRV-4006`, no file, and its quota reservation
returned; a `RowStore` over a two-slab quota stops with `PRV-4005` holding exactly two overflow slabs
and carries on through its free list.

## Measurement, 2026-09-19

`SpillTierMeasurementIT` (`pravaha-runtime`, `exec`; excluded from the default build as `*IT`, run with
`-Dtest=SpillTierMeasurementIT`). Each operator is driven directly, single-threaded as a lane drives
it, to 1, 2, 4, 8 and 16 times a **64 MiB** RAM ceiling (the join's real one, `MAX_JOIN_STATE_SLABS`;
for the windowed aggregate, per store, via its slice ceiling), once with the tier and once with the
same load held entirely in RAM. Phases: insert; 500,000 probes (join) or updates (aggregate) at
uniformly random keys, which is the pattern that finds cold pages; firing every window (aggregate);
then the churn — three rows in four retracted at random (join), the watermark past three slices of
four (aggregate) — and one compaction. "On disk" is `du` of the spill directory (sparse files: blocks
written), with slab bytes mapped beside it.

**The machine, and what that limits.** A heterogeneous 12-core laptop (AMD Ryzen AI 9 HX 370: Zen 5
and Zen 5c cores, 24 threads) with 61 GiB of RAM, ~38 GiB of it free, a Crucial P310 NVMe SSD, JDK 21,
nothing pinned: the single lane thread may land on either core type, and repeated runs vary by
±20–25 % — the 1x rows, where nothing spills, show the noise (the tier's run beats the RAM run there
more than once). The page cache was **not** dropped: at most ~2 GiB was spilled against ~38 GiB free,
so the mapped files stayed resident and these numbers are the cost of the mapped tier's indirection,
page-table work and write-back, **not** of reading cold state from the device. State larger than free
RAM would cost more and was not measured; it is the case in *What would make this wrong*.

The spill directory was `pravaha-runtime/target/…` on the NVMe drive, on purpose: `/tmp` on this
machine is a tmpfs, i.e. RAM, and a tier pointed there measures nothing about a disk — which is also
the argument below against any default directory.

Windowed aggregate (`COUNT`, `SUM`, `COUNT(DISTINCT)`; four slices; a 128-byte block per accumulator
and another per distinct value, so two stores of equal size, each with a 64 MiB RAM tier; from the
first run, which the join-index change below does not touch):

| state / ceiling | accumulators | tier | update/s | random update/s | fired/s | on disk before compaction | on disk after | compaction |
|---|---|---|---|---|---|---|---|---|
| 1x | 524,288 | RAM | 1,330,671 | 1,389,568 | 645,918 | — | — | — |
| 1x | 524,288 | spill | 1,923,487 | 1,400,082 | 695,880 | 0 | 0 | — |
| 2x | 1,048,576 | RAM | 1,540,125 | 1,283,330 | 575,652 | — | — | — |
| 2x | 1,048,576 | spill | 999,078 | 1,058,322 | 533,682 | 128.1 MiB | 64.1 MiB | 124 ms, 1,024 slabs |
| 4x | 2,097,152 | RAM | 1,480,591 | 1,044,747 | 503,816 | — | — | — |
| 4x | 2,097,152 | spill | 807,563 | 904,662 | 419,735 | 384.2 MiB | 128.2 MiB | 201 ms, 4,096 slabs |
| 8x | 4,194,304 | RAM | 1,309,980 | 1,084,626 | 363,495 | — | — | — |
| 8x | 4,194,304 | spill | 570,841 | 647,084 | 360,480 | 896.5 MiB | 256.5 MiB | 374 ms, 10,240 slabs |
| 16x | 8,388,608 | RAM | 1,422,373 | 753,003 | 410,422 | — | — | — |
| 16x | 8,388,608 | spill | 739,149 | 863,412 | 375,208 | 1,921.0 MiB | 513.0 MiB | 713 ms, 22,528 slabs |

After compaction the files equal the live state exactly (the one surviving slice of four, 64 KiB
slabs); before it, the discarded three quarters were still on disk.

Join (a `BIGINT` and a short string per row, 147 bytes of row store per row, distinct keys; the key
index's RAM is its slot table plus whatever of its store has not spilled):

| state / ceiling | rows | tier | insert/s | probe/s | retract/s | key index in RAM | on disk before compaction | on disk after | compaction |
|---|---|---|---|---|---|---|---|---|---|
| 1x | 443,428 | RAM | 2,470,912 | 507,693 | 1,430,992 | 44 MiB | — | — | — |
| 1x | 443,428 | spill | 3,021,547 | 487,682 | 1,416,571 | 44 MiB | 0 | 0 | — |
| 2x | 886,857 | RAM | 3,184,917 | 536,436 | 1,409,246 | 87 MiB | — | — | — |
| 2x | 886,857 | spill | 1,898,046 | 461,721 | 1,187,170 | 87 MiB | 44.3 MiB | 0.3 MiB | 114 ms, 44 slabs |
| 4x | 1,773,714 | RAM | 2,664,991 | 508,797 | 1,201,916 | 173 MiB | — | — | — |
| 4x | 1,773,714 | spill | 2,047,511 | 487,600 | 1,309,172 | 128 MiB | 196.9 MiB | 0.9 MiB | 287 ms, 196 slabs |
| 8x | 3,547,428 | RAM | 2,493,509 | 477,642 | 1,235,229 | 345 MiB | — | — | — |
| 8x | 3,547,428 | spill | 2,039,487 | 464,943 | 1,247,525 | 192 MiB | 521.7 MiB | 45.0 MiB | 625 ms, 521 slabs |
| 16x | 7,094,857 | RAM | 2,367,978 | 452,373 | 1,166,246 | 690 MiB | — | — | — |
| 16x | 7,094,857 | spill | 2,009,177 | 437,435 | 1,009,228 | 320 MiB | 1,171.2 MiB | 197.0 MiB | 1,180 ms, 1,171 slabs |

After the churn a quarter of the rows is live; compaction moves what fits into the RAM tier's freed
blocks and the rest into the fewest slabs, so the files come down to the live overflow (0.1 MiB at
2x, 196.8 MiB at 16x) plus at most one partly carved slab.

**What the first run found, and what changed because of it.** The first run of the join had the
key index entirely in RAM: 690 MiB of index at 16x beside a 64 MiB row ceiling — about 100 bytes a key,
bounded only by a four-gigabyte backstop, so a "spilled" join's memory still grew with its key count.
The index's store now takes the tier too, under the same RAM ceiling as the rows (`JoinSide`, and it is
compacted with them); the table above is the second run. What stays in RAM is the index's slot table,
16 bytes a slot at a load of at most 0.7 — 256 MiB of the 320 MiB at 16x. That is the floor of this
design: a probe walks the slot table, and moving it to disk would make every probe a page fault. A
join with tens of millions of keys should be sized with it in mind.

**What the numbers show.**

- **Throughput under spill stays within about 2x of RAM, and mostly much closer, while the page
  cache holds the files.** Windowed-aggregate inserts are the worst case, 0.44–0.65x of the all-RAM run
  from 2x to 16x (fresh blocks carved in mapped memory, first-touch page faults, write-back); random
  updates to spilled accumulators run 0.6–1.15x and window firing 0.83–0.99x. The join loses less:
  inserts 0.6–0.85x, probes 0.86–0.97x, retractions 0.84–1.09x. The spilled runs do not get
  steadily slower from 2x to 16x — the cost is the tier's, not the size's, as long as it is cached.
- **Compaction costs 50–260 ms per 100 MiB freed**, less the more it frees (0.7 s for 1.4 GiB, 1.2 s for 1 GiB at 16x; one pause on the
  lane that owns the state), and gives the disk back: 1,921 MiB to 513 MiB (aggregate), 1,171 MiB to
  197 MiB (join) — to the live state, as the unit tests assert.
- **The memory ceiling now holds for everything but index slot tables**, which is what the tier is
  for.

**Recommendation: leave the tier off by default.** The numbers do not argue against the tier — its
cost is modest, bounded and flat across multiples — and a deployment with a local disk and state that
can surprise it should turn it on, with `max-bytes` set below what the disk holds. They do not justify
turning it on for everyone, for three reasons the measurement makes concrete: there is no directory
that is safe to assume (on this very machine the obvious default, the temp directory, is RAM, where
"spilling" would only move the out-of-memory to the kernel); the cost of state larger than free RAM —
the only case where the tier is doing its real job — is unmeasured here and will be worse than the
table; and a query that spills changes from failing loudly at its ceiling to running at half speed,
which an operator should choose knowing `pravaha_query_spill_bytes` is there to watch. The default
stays `enabled` unset with no directory, i.e. off.

## What would make this wrong

State that must be larger than a node's disk-backed address space, or random-access patterns over
cold state so severe that page-cache behaviour loses to an LSM tree's. Either would show up in the
measurement above, which is why the measurement is part of the decision rather than an afterthought.
