# ADR-036: one node, thousands of continuous queries

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — scope decision, not yet built |
| Date | 2026-09-15 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-004 (partitioned lanes), ADR-027 (lane multiplexing), ADR-029 (Aerospike scan-only), ADR-034 (distribution deferred), ADR-035 (Wave 8) |

## Decision

The wave after Wave 8 is **scale-out and hardening on one node**, not Wave 9's control-plane features.
The target is stated as a number so it can be missed: **one instance holding thousands of
Aerospike-backed continuous queries**, on the hardware that exists.

Waves 9 and 10 follow it. They are not cancelled; they are behind it, because building a time-travel
debugger on top of an unmeasured foundation puts a floor above a hole.

## What a query costs today, measured

`NodeScaleTest` registers 200 distinct continuous queries and reports. On the development machine:

| | |
|---|---|
| Platform threads | **1.00 per query** — the lane |
| Heap | ~65 KiB per query |
| Off-heap | ~5 MiB per query idle — one 4 MiB arena slab, eager, plus a 2048 × 512B inbox |
| Registration | ~16 ms per query, dominated by planning |

Before this there was no number at all. `QueryRegistry` said "fine at tens", which came from a CPU
measurement and was silent about memory and threads — and the CPU part of it was fixed in Wave 7 by
`BACKOFF_PARK`, so the one number anybody could quote was measuring something that no longer applied.

Extrapolated to the target, 1,000 queries cost **1,000 platform threads and about 5 GB off-heap**
before a row moves. The memory is the harder wall and it is the less obvious one.

## The four things in the way, in the order they bind

### 1. Off-heap per query, ~5 GB at a thousand

The arena's first 4 MiB slab is allocated eagerly and the inbox is 2048 × 512 bytes. Both defaults
were chosen for the case this engine was first built for: one query, a source that never stops,
latency that matters more than a core. A thousand small continuous queries is the opposite case, and
it inherits sizes nobody chose for it.

Neither is configurable — `PRV-4003`'s sibling problem, recorded as PF-3: five error messages tell an
operator to raise `arena.slab.size`, and no such setting exists. So the first work is to make them
settings and to pick defaults per registration rather than per build.

This is the cheapest large win in the wave and it is where it starts.

### 2. A lane thread per query

ADR-027 wants a lane to multiplex several queries and it was never built. A thousand parked
`BACKOFF_PARK` threads is survivable but it is a thousand stacks and a scheduler run queue nobody
sized for it, and it is the ratchet `NodeScaleTest` now guards.

Multiplexing is the architectural item of this wave. It is also the one that can break everything
else, because thread confinement is what makes the engine's state safe without locks: several queries
on one lane must not become several queries sharing one arena.

### 3. Aerospike scans, one per query

ADR-029 makes Aerospike scan-only, and each registration gets its reader. A thousand queries over the
same set is a thousand scans of that set — the load lands on the Aerospike cluster, which is exactly
where the owner does not want it. Fingerprint sharing helps only when the SQL is identical.

What is wanted is one scan feeding many queries over the same set. That is a real piece of design and
it is the item most specific to the stated target.

### 4. Two schedulers per query where the node is a server

W9-3. A shared watermark clock and a small checkpointer pool. Recorded, sized, and not started.

## What is explicitly not in this wave

Multi-node anything, which stays with ADR-034. The Wave 9 and 10 feature list. And **any claim about
throughput at scale**: the PERF section that would measure it is 52 of 60 cases unexecuted for want
of homogeneous hardware, and this machine's heterogeneous cores cannot produce a per-lane number
anybody should quote. This wave can honestly report *resource cost per query*, which is a count and
not a rate, and it should not be read as more than that.

## How it will be known to be done

`NodeScaleTest` is the instrument. It reports cost per query and ratchets the worst of it, and the
wave is done when it can register thousands rather than hundreds inside a test JVM — which is the
same statement as the target, made falsifiable.

The 138 findings still open are triaged into must-fix-before-GA and won't-fix in the same wave.
Nobody has done that, and a release cannot be argued for against an untriaged list.
