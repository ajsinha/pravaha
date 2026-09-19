# ADR-044: no RocksDB — the memory-mapped overflow tier is L1

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **supersedes the RocksDB tier of [ADR-006](006-tiered-state.md) and design D5**. The mapped tier exists (ADR-037 B2); the four improvements below are built, a key index's slot table spills too, and the tier is measured twice — page-cached, and with the process capped below its state. Recommendation unchanged: the tier stays off by default, and is sized as survival rather than capacity |
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
| Years of production measurement | Correctness-tested, never measured with state much larger than RAM | **A measurement** at several multiples of RAM, and a decision from it on whether the tier should be on by default | **Done** 2026-09-19, twice: `SpillTierMeasurementIT`, 1x–16x a 64 MiB ceiling with the files page-cached, and `SpillBeyondRamMeasurementIT`, state 1x–16x the memory the process may use at all (cgroup v2, swap off). Both below; recommendation: stays off by default |
| An index that does not grow the heap or the RAM as keys arrive | The key index's slot table stayed in RAM at 16 bytes a slot, and could not pass 2<sup>26</sup> slots | **Segments, and the tier for the ones past a RAM budget** | **Built** 2026-09-19: `SlotTable`, 16 MiB segments, RAM up to the store's own ceiling and mapped past it, to 2<sup>30</sup> slots |

Those four were the work this ADR committed to; the fifth row is what the first measurement found
and the second closed. None of them needs a key-value store.

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

### A key index's slot table spills too

`VariableKeyStateMap` — a join side's key index, a windowed aggregate's accumulators, the distinct
values — is a slot table (sixteen bytes a slot, fingerprint and handle, at most 0.7 full) over a
`RowStore`. The store spilled; the table did not, so a spilled map's RAM still grew with its keys, and
the table was one `int`-addressed region, which stopped it at 2<sup>26</sup> slots (about 47 million
keys) — the next doubling computed `capacity * 16` as a negative size. Both are gone:

- **Segments.** The table is held as segments of 2<sup>20</sup> slots (16 MiB) (`SlotTable`), so it
  reaches 2<sup>30</sup> slots, 16 GiB of table, about 750 million keys. A growth past that is
  refused with `PRV-4001` by message rather than overflowing.
- **Mapped past a budget.** With the tier on, segments up to the store's own RAM ceiling
  (`storeMaxSlabs × storeSlabBytes`: 64 MiB for a join side) are RAM and the rest are mapped files
  from the same `MappedFileMemoryAccess`, counted against `max-bytes` like any slab. A spilled
  map's RAM is now bounded — store ceiling plus table budget — whatever its key count. The handle
  word is stored complemented, so an empty slot is zero: a fresh sparse segment needs no write, and
  its disk is taken only where keys land.
- **A refusal leaves the map as it was.** A growth is decided before the entry is created, and the
  new table is built whole before the old one is released, so a segment the quota or the disk
  refuses (`PRV-4005`, `PRV-4006`) leaves every key in place and the asking key absent. While a table
  grows the old and new are both held: a growth needs room for both, 1.5 times the new table.
- **Counted.** The mapped segments are files in the spill directory beside the slabs, and
  `spillStatistics()` (hence `pravaha_query_spill_bytes`) counts them as held and live.

Proven by `SlotTableSpillPropertyTest` (7): 200,000 random creates, updates, removals and lookups
over 40,000 variable-width keys against a `HashMap`, with 4 KiB segments and a two-segment RAM budget
so the table is mostly mapped, the store compacted every 10,000 operations, and the table's RAM
asserted inside its budget after every operation — four seeds; a table of many RAM segments against
a `HashMap`; a growth refused by a 64 KiB quota with the map unchanged. And by
`JoinIndexSpillPropertyTest` (four seeds): a join whose left slot table is mapped, driven with random
inserts, duplicate rows, retractions and probes against an on-heap model, compacted between batches,
checkpointed and restored into a fresh join over a fresh spill directory halfway, and every key
probed at the end. Seed-proven: with every segment kept in RAM 9 of the 11 fail; with the growth
after the insert, the quota test fails on the asking key; with every slot addressed in segment 0, 6
fail; with compaction not rewriting slots, all four map seeds fail.

What this does and does not buy is in the second measurement below: it bounds a spilled join's RAM
(at 4x the cap, 192 MiB of the left table was mapped; held in RAM it would have been anonymous
memory on top of the 431 MiB the cgroup measured, past a 512 MiB cap with swap forbidden), and it
does not make a probe of a mapped table cheap once the table is not in the page cache.

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
16 bytes a slot at a load of at most 0.7 — 256 MiB of the 320 MiB at 16x. This run called that the
floor of the design, on the grounds that a mapped table makes a cold probe a page fault. It was
moved to the tier anyway (*A key index's slot table spills too*, above), because the alternative is
RAM that grows without bound, and the measurement beyond RAM below shows that once state is not
cached a probe is a page fault with or without the table in RAM: the rows and the index store are
mapped either way.

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
  for. (Since closed: the slot tables spill too.)

**Recommendation: leave the tier off by default.** The numbers do not argue against the tier — its
cost is modest, bounded and flat across multiples — and a deployment with a local disk and state that
can surprise it should turn it on, with `max-bytes` set below what the disk holds. They do not justify
turning it on for everyone, for three reasons the measurement makes concrete: there is no directory
that is safe to assume (on this very machine the obvious default, the temp directory, is RAM, where
"spilling" would only move the out-of-memory to the kernel); the cost of state larger than free RAM —
the only case where the tier is doing its real job — is unmeasured here and will be worse than the
table (the measurement below says how much worse: two to three orders of magnitude for random
access); and a query that spills changes from failing loudly at its ceiling to running at half speed,
which an operator should choose knowing `pravaha_query_spill_bytes` is there to watch. The default
stays `enabled` unset with no directory, i.e. off.

## Measurement beyond RAM, 2026-09-19

The measurement above says what it did not measure: state larger than the machine's free RAM. This
one measures it, by capping the process rather than by finding a smaller machine.

**The method, and why it is honest.** `SpillBeyondRamMeasurementIT` (`pravaha-runtime`, `exec`; also
excluded from the default build as `*IT`) runs each workload in a JVM of its own under

```
systemd-run --user --scope -p MemoryMax=512M -p MemorySwapMax=0 java -Xmx160m … SpillBeyondRamWorkload …
```

— a cgroup v2 whose `memory.max` the kernel charges **everything** to: the heap, the off-heap RAM
tier, and the page cache of the mapped files, mapped pages included. Swap is forbidden, so anonymous
memory cannot leave and the only thing reclaim can take is file pages: once the mapped state passes
what is left, the kernel writes dirty pages back and faults pages in from the device, which is
precisely the condition being measured. Nothing in the engine is changed to fake it — no `madvise`,
no unmap-and-remap, no dropped caches — and the run proves the cap held by reading its own cgroup:
`memory.max`, `memory.peak` (512.0 MiB, i.e. pinned at the cap in every capped run), `memory.events`
`max` (79,810 reclaim events at 1x, 1,836,205 at 4x — at 0 there would have been no pressure), and
its own major faults and block-device bytes from `/proc/self/{stat,io}`. Each size is run twice: once
capped, once with no cap at all, where 35 GiB of free RAM holds the same files in the page cache.
That pair is the curve: the same code, the same state, cached and not. The cap was 512 MiB for the join and 1 GiB for the windowed aggregate, which does not fit in 512 MiB at all — see below.

The knobs (`pravaha.spill.beyond.*`) are in the test's javadoc. The test is **skipped, with the
reason, where a scope cannot be given a memory limit** — no systemd user instance, or the memory
controller not delegated to it; it checks by starting a scope with `MemoryMax=64M` and reading
`memory.max` back from inside it. What produced the numbers below, on a spill directory on the NVMe:

```
./mvnw -o -pl pravaha-runtime -am test -Dtest=SpillBeyondRamMeasurementIT \
    -Dsurefire.failIfNoSpecifiedTests=false -Dpravaha.spill.beyond.dir=<a real disk> \
    -Dpravaha.spill.beyond.kinds=join -Dpravaha.spill.beyond.multiples=1,2,4,16 \
    -Dpravaha.spill.beyond.timeoutMinutes=25
# and, for the aggregate, -Dpravaha.spill.beyond.kinds=aggregate -Dpravaha.spill.beyond.capMiB=1024
#   -Dpravaha.spill.beyond.multiples=1,2,4  (plus -Dpravaha.spill.beyond.heapMiB=1024 for the firing)
```

`tools/spill-beyond-ram.sh <a real disk>` is the same thing with those defaults filled in.

**The machine.** AMD Ryzen AI 9 HX 370 (Zen 5 + Zen 5c, 12 cores, 24 threads), 61 GiB RAM, Crucial
P310 1 TB NVMe (`nvme0n1p2`, ext4, `read_ahead_kb` 128, `none` scheduler), kernel 7.0.4, JDK
21.0.12. The spill directory was on that NVMe, never `/tmp` (a tmpfs here, i.e. RAM). Another
session was using the machine, which the uncapped runs show as ±2x noise between sizes; the capped
runs are limited by the device and are steadier. Single-threaded, as a lane is.

**Join** (`BIGINT` and a short string per row, distinct keys, 252 bytes of state per row including
its index; 64 MiB RAM tier; state is the sum of the RAM tier and every mapped byte). "capped" is the
512 MiB process, "cached" the same run with no cap:

| state / cap | state | rows | insert/s capped | cached | probe/s capped | cached | retract/s capped | cached |
|---|---|---|---|---|---|---|---|---|
| 1x | 457 MiB | 2,133,246 | 1,144,643 | 2,225,777 | 20,498 | 544,197 | 14,404 | 1,378,335 |
| 2x | 911 MiB | 4,266,493 | 1,276,012 | 699,311 | 2,556 | 160,234 | 1,086 | 118,805 |
| 4x | 1,820 MiB | 8,532,986 | 19,500 | 1,546,514 | 1,437 | 310,796 | 1,045 | 798,682 |
| 16x | 7,276 MiB | 34,131,944 | did not finish | 2,170,483 | — | 159,754 | — | 75,364 |

Latency of one operation, capped, in the same runs (a probe is a right row in and out again):

| state / cap | insert p50 / p99 / max | probe p50 / p99 / max | retract p50 / p99 / max |
|---|---|---|---|
| 1x | 0.4 µs / 14 µs / 191 ms | 3.8 µs / 393 µs / **1.25 s** | 4.6 µs / 360 µs / **1.63 s** |
| 2x | 0.4 µs / 21 µs / 9.3 ms | 459 µs / 918 µs / 7.7 ms | 852 µs / 5.8 ms / 27 ms |
| 4x | 0.5 µs / 590 µs / 27 ms | 786 µs / 1.3 ms / 12.7 ms | 1.05 ms / 1.7 ms / 7.9 ms |

Cached, for the same phases, p50 is 0.3–13 µs and p99 1.2–25 µs at every size.

**Windowed aggregate** (`COUNT`, `SUM`, `COUNT(DISTINCT)`, four slices, 341 bytes of state per
accumulator including its index and its distinct value; 64 MiB RAM tier per store). At the 512 MiB
cap only 1x ran: **4x and 16x were killed by the kernel OOM killer before any of this could be
measured** (`Failed with result 'oom-kill'` in the journal), because a windowed aggregate with a
distinct count has *four* RAM tiers — the accumulators' store and its slot table, the distinct
values' store and its slot table, each budgeted at the 64 MiB ceiling — and 256 MiB of those plus a
160 MiB heap and the JVM's own ~70 MiB is about 480 MiB of anonymous memory, which cannot be
reclaimed with swap off. The rest of the aggregate line was measured at a **1 GiB cap**:

| cap | state / cap | state | accumulators | update/s capped | cached | insert/s capped | cached |
|---|---|---|---|---|---|---|---|
| 512 MiB | 1x | 513 MiB | 1,575,384 | 4,119 | 1,028,136 | 745,279 | 832,473 |
| 512 MiB | 4x, 16x | — | — | *OOM-killed: the RAM tiers do not fit the cap* | | | |
| 1 GiB | 1x | 1,025 MiB | 3,150,768 | 4,701 | 491,143 | 328,997 | 485,806 |
| 1 GiB | 2x | 2,050 MiB | 6,301,536 | 1,312 | 609,641 | 80,484 | 607,376 |
| 1 GiB | 4x | 4,101 MiB | 12,603,076 | ~1,300 | 481,912 | ~11,800 | 681,958 |

(4x capped did not finish its 500,000-update phase inside the 20-minute budget; its insert took
1,065 s for 12.6 million accumulators and its updates ran at 1,300–1,400/s for 130 s.) Update
latency capped at the 1 GiB cap: p50 53 µs / p99 786 µs / max 677 ms at 1x, and p50 786 µs / p99 1.7
ms / max 8.8 ms at 2x, against p50 1.5–1.9 µs cached.

**Firing a window is heap-bound before it is disk-bound.** `SlicedAggregateState.fire` puts every
accumulator's handle in an `ArrayList<Long>`, then a `HashMap` entry and a `WindowResult` per group,
so its heap is the size of the *whole state*, not of the window: with a 160 MiB heap it threw
`OutOfMemoryError` at every size measured, including 1.58 million accumulators, capped and uncapped
alike. With a 1 GiB heap and the files cached it ran at 375,070 groups/s (394k groups) and 306,793/s
(1.58M groups). A node running windowed aggregates with millions of live accumulators has to be given
heap for the firing, whatever the spill tier does with the state itself — which is a separate finding
from this ADR's subject, recorded here because the measurement is what found it.

**Where it degrades, and why.**

- **The cliff is the index leaving the page cache, not the rows.** Inserting is sequential — fresh
  blocks carved in order — and stays above a million rows a second at 1x and 2x. At 4x it collapses
  to 19,500/s, and the per-second trace in the log says exactly where: the first 5.9 million rows go
  in at 1.4 M/s, and then the rate falls to 5–6 k/s. 5.9 million keys is where the slot table
  doubles from 2<sup>23</sup> to 2<sup>24</sup> slots — 128 MiB of table to 256 MiB, of which 64 MiB
  may be RAM — and the index (table plus its key/value store) no longer fits in the ~100 MiB of page
  cache the cap leaves. From that point every insert is a random write into a mapped page that is not
  resident. At 16x the run did not finish the insert in 25 minutes: 12.9 million rows of 34.1
  million, the last stretch at about 4.3 k/s.
- **Uniformly random probes are page faults, and each one costs far more than a page.** At 4x, 431,136
  probes took 1,043,427 major faults — 2.4 per probe, which is the shape of the structure: the slot
  table, then the index block, then the row, each a random address in a different mapped file. The
  process read **124 GiB** for those probes: 124 KiB per fault, which is this device's
  `read_ahead_kb` of 128 — the kernel's read-around for a file-backed fault. A 252-byte row is
  fetched by reading about 300 KiB. The phase sustained ~423 MB/s, i.e. **the tier beyond RAM is
  bandwidth-bound by read-around, not by the device's IOPS**: 1,043,427 faults of 4 KiB would have
  been 4 GiB, not 124 GiB. Java 21 cannot ask for `MADV_RANDOM` (no `madvise` without JNI or a
  preview API), so the levers available today are the device's `read_ahead_kb` and not letting the
  index leave RAM.
- **A probe writes.** Every match marks the stored row (`OFFSET_MATCHED`, which is what lets an
  outer join know whether a row ever matched), so a probe dirties the row's page: 1.6 GiB written
  during the 4x probe phase, which reclaim must write back before it can take the page.
- **The tail is reclaim, not the device.** A capped probe's p99 is 0.4–1.3 ms — one or two faults —
  but the maximum reached 1.25 s at 1x and a retraction 1.63 s, while the cgroup was at its limit:
  an allocation that has to reclaim, and possibly write back, waits for it. There is no such tail in
  any cached run (max 2.8–15 ms, those being the JIT and the table's growth).
- **Throughput at the cliff is ~1,000–2,500 operations a second on this NVMe**, against 160,000–540,000
  cached: 100–400x. That is the honest number for "state much larger than RAM, keys touched at
  random". The windowed aggregate behaves the same way — 4,700 random updates a second at 1x of a
  1 GiB cap, 1,312 at 2x, ~1,300 at 4x, against 480,000–610,000 cached — which is the point: the
  cost is the page fault, not the operator.
- **The RAM tiers are a floor the cap has to clear.** The ceilings are per *store*, and a map is a
  store plus a slot table budgeted at the same ceiling, so a windowed aggregate with a distinct
  count has four of them: at a 64 MiB ceiling its anonymous memory settles at about 256 MiB
  (measured: `anon` 465–488 MiB with a 160 MiB heap) before a byte spills. A node must have that
  much per such query *and* page cache for the index, or the kernel kills it rather than the tier
  saving it. This is better than it was — the slot tables used to be unbounded RAM, 512 MiB of table
  at the 4x point — but it is a floor, not nothing.

**What this changes.** Nothing about the decision — an LSM tree would be reading the same device
through the same page cache, with its own amplification — and everything about the sizing advice:
the spill tier is a way for a query to survive state it cannot hold, not a way to serve it. The
number to size is the *index*: about 100 bytes a key of slot table and index block per join side
(and it is now bounded, not growing in RAM), plus the rows a workload actually touches. Keep that
inside the node's free RAM and the first measurement's numbers hold; go past it with uniformly
random keys and throughput falls to the device's fault rate. OPERATIONS says so with the numbers.

## What would make this wrong

State that must be larger than a node's disk-backed address space, or random-access patterns over
cold state so severe that page-cache behaviour loses to an LSM tree's. The second measurement is the
closest this has come: uniformly random keys over state four times the memory the process may use
run at about a thousand operations a second, and most of the device's bandwidth goes on read-around
the workload never uses. An LSM tree would be reading the same device through the same page cache —
with its own write amplification — so this is not yet an argument for RocksDB. It would become one
if a deployment needed *sustained* random access over cold state at a rate a block cache with its
own admission policy, and reads that fetch a block rather than a page-cache window, could serve and
this cannot. The cheaper answers come first: `MADV_RANDOM` once the engine is on a JDK where it can
ask for it (JDK 22's FFM, no preview flag), a lower `read_ahead_kb` on the spill device, and sizing
the node's free RAM for the index.
