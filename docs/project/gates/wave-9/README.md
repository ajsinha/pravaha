# Gate — end of Wave 9 (one node, thousands of continuous queries)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 9 of 11 — inserted by [ADR-036](../../../design/adr/036-one-node-thousands-of-queries.md) |
| Gate | **None in the plan.** See "the numbering desynced" below |
| Written | 2026-09-15 |
| Verdict | **Wave goal met and measured. Two of its eleven items deferred by decision.** This is the first gate in the project whose criterion was both stated as a number and reached |

## The numbering desynced, and that matters more than it looks

`implementation_plan.md` maps wave 9 to milestone **M9 "It's operable"** and **Gate P8** —
*"time-travel debug of a seeded production bug."* That is not what wave 9 built.

ADR-036 **inserted** a scale wave ahead of the control-plane wave, on the argument that building a
time-travel debugger on an unmeasured foundation puts a floor above a hole. So the delivered wave 9
is the scale wave, M9/P8 moved to wave 10, and there are now **11 waves against a plan that names
10**. Nothing in the plan was updated to say so, and a reader matching wave numbers to gate numbers
after this point will match the wrong ones.

**The inserted wave therefore has no gate defined anywhere.** What it has instead is ADR-036's own
target, stated as a number so it could be missed, and that is what this pack records against.

## The target, and the measurement

ADR-036: **one instance holding thousands of Aerospike-backed continuous queries, on the hardware
that exists.**

| | Measured | Source |
|---|---|---|
| Queries registered on one node | **1,000** | `ThousandQueryTest` |
| Registration cost | **3.7 ms each** (3,736 ms total) | same |
| Platform threads added | **+24 on 24 cores** — follows cores, not queries | same |
| Off-heap, default sizing | 1,000 MiB (1,024 KiB/query, all inbox) | same |
| Off-heap, sized as `OPERATIONS.md` advises | **61 MiB** (62 KiB/query) | same |
| Heap | 66 MiB (53 KiB/query) | same |

Every query alive; first and last answer a fed row.

**One prediction was wrong in the good direction.** Registration measured ~16 ms each over 200
queries and is 3.7 ms over a thousand — the earlier figure was paying for JIT warm-up and charging it
to the engine. Recorded because it is the reason the wave ran the target rather than extrapolating
to it.

## What Wave 9 delivered

| Item | State |
|---|---|
| W9-1 the data plane ran a platform thread per parked subscriber | ✅ virtual threads |
| W9-2 a registered query cost three platform threads | ✅ the feed loop is virtual |
| W9-3 two per-query schedulers | ✅ `SharedClock` — one daemon timer, work on virtual threads |
| W9-4/W9-5 a lane owned a thread | ✅ `LaneRunner`: many lanes, one thread per core, per-lane failure isolation |
| W9-6 an idle query reserved a 4 MiB slab it never wrote to | ✅ lazy first slab |
| W9-7 unaccounted off-heap | ✅ attributed |
| W9-9 the row header's "schema id" was a schema *version* | ✅ `StreamSchema.streamId` |
| W9-11 the target, run rather than extrapolated to | ✅ `ThousandQueryTest` |
| **W9-8 / W9-10** wire `LaneMultiplexer` | ❌ **open by decision** when written; since then W9-10 is fixed and the registry uses the multiplexer, W9-8 open for a node setting |
| SRC-2, found by this wave's own question | ✅ one Aerospike client per cluster **per credential** — the last per-query platform thread |

## The two deferred items, and why that is a decision rather than a shortfall

W9-8 and W9-10 are both "wire the `LaneMultiplexer`". It is built, tested, and wired to nothing.

W9-11 is what demoted them. The multiplexer is the answer to the per-query inbox and arena — but
**sizing the inbox as `OPERATIONS.md` already advised takes the same thousand queries to 61 MiB
today**, with no code change and no barrier question to answer first. That reordered the wave's own
plan: the multiplexer stopped being what stands between this node and the target and became an
optimisation on a target already met.

Wiring it is wave-sized, and the aligned checkpoint barrier is the substance of it — a lane clamps
each batch at the nearest control marker, and three hundred queries each advancing a watermark every
second would cut that lane's batches short several hundred times a second. Lane ownership also has to
move from `QueryExecution` to the registry, or one query closing stops a lane serving the rest.

**Reopen it when a measurement demands it, not on the strength of a plan that predates the
measurement.**

*Since this pack was written:* ADR-039 reopened it as item 2 of the road to GA. The barrier cost
turned out to be one asymmetry, not a wave — a checkpoint is a *cut* and clamps the batch, a
watermark is a *level* and does not (W9-10, fixed) — and the registry now hosts queries on shared
lanes it owns. What is still open is W9-8's last part: no node setting turns it on.

## What Wave 9 did not do

Any throughput claim. The PERF section that would produce one is 52 of 60 cases unexecuted for want
of homogeneous hardware, and this machine's heterogeneous cores cannot produce a per-lane number
anybody should quote. **What this wave reports is resource cost per query — a count, not a rate.**

A followed file still costs about 13 ms of CPU per second while completely idle, so a hundred of them
is 1.8 cores (SRC-6, open). N queries over one Aerospike set were N scans at the time of this gate
(SRC-3, since fixed: one reader per source binding, four queries over one set measured at 1.0
scans/s between them).
