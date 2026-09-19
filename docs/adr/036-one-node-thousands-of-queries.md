# ADR-036: one node, thousands of continuous queries

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **largely built** (2026-09-15). Shipped: the scan interval (§3, SRC-8), arena and inbox sizing as settings (§1, W9-6/W9-7), lane multiplexing onto shared threads (§2, W9-4/W9-5) and the shared schedulers (§4, W9-3), a descriptor ceiling the node reports (SRC-4), and one reader per source binding feeding many queries (§3's second half, SRC-3 — measured against a real cluster at 1.0 scans/s for four queries over one set, where it was 1.0 *each*). **Not reachable from a node:** `LaneMultiplexer`, which shares the inbox and arena as well as the thread — the registry uses it when an embedder asks (`multiplexingLanes`), and no node setting does (W9-8; W9-9 and W9-10 fixed). The measurements in the tables below are the *before* figures and are kept as the record of what the wave was scoped against; the *after* figures are in `HANDOVER.md` and the findings they cite |
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

Neither is configurable — `PRV-4003`'s sibling problem, recorded as PF-3: eleven error messages tell
an operator to change `arena.slab.size` or `lane.inbox.cell.size`, and neither setting exists. So the first work is to make them
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

## How source reading scales

The section above measures what a registered *query* costs. It says nothing about what reading costs,
because `NodeScaleTest` registers queries that are fed by nobody. The target is thousands of
**Aerospike-backed** continuous queries, so the reading half is the half the sentence is about, and
this is it. `SourceScaleTest` is the instrument for the node; `AerospikeSourceScaleIT` is the
instrument for the cluster, and its numbers come out of Aerospike's own counters rather than the
plugin's. Findings SRC-1 to SRC-7.

### What one bound source costs, measured

On the development machine, 24 cores, JDK 21, against the unbound baseline at the same query count:

| | |
|---|---|
| File descriptors | **1.00 per bound source** — one partition per binding, one reader per partition |
| Platform threads, filesystem | **0 per source** — the feed loop is virtual since W9-2; the eight added across a hundred sources are the scheduler's carriers, bounded by `availableProcessors` |
| Platform threads, Aerospike | **1 per source on top of the lane's** — `tend`, the client's cluster thread, one per client, one client per registration. *Fixed since (SRC-2): one shared client per cluster per credential, so **0 per source**.* |
| Heap | 60–380 KiB per source, and reported rather than asserted: heap after a GC is noisy enough that the two source counts disagree by a factor of two |
| Idle CPU, followed file | **12.8 ms per second per source** at 100 sources, 16.9 at 50 |
| Idle CPU, exhausted file | 3.0 ms per second per source — the feed loop's polling with no `stat` and no `read` |

The last two are the ones that do not appear in any count. A bound source is polled a thousand times
a second whether or not anything happened, because `PumpingFeed` naps one millisecond after a poll
that moved nothing. **A hundred idle followed files is 1.8 cores.**

### Aerospike: N queries over one set are N scans, and each one scans continuously

The answer to the question this wave was scoped around, measured against a real Aerospike Community
8.1.2.4 node rather than reasoned about:

| | one query | four queries |
|---|---|---|
| Scans of the set | 43–50 / second | 79–131 / second |
| Cluster CPU (`process_cpu_pct`) | 203% | 388–579% |
| Client connections | +1 | +8 |
| `tend` threads in the engine | 1 | 4 |

*The last two rows are what SRC-2 has since removed: registrations against one cluster and one
credential now share a single client, so both follow the cluster count rather than the query count.
The scan rows are what SRC-3 has since removed: one reader per (binding, stream, partition) fans each
decoded record into every lane that asked for it, so the same four queries now produce 1.0 scans a
second between them rather than 1.0 each. Both rows are kept as the before figures.*

Two separate things, and the second is the surprise:

**N queries are N scans.** Confirmed. The fingerprint shares a computation across *identical* SQL,
and a second registration of identical SQL opens no reader at all — that part works. Different SQL
over the same set shares nothing: different plan, different fingerprint, different execution,
different feed, different plugin instance, different scan — though since SRC-2 no longer a
different `AerospikeClient`. A thousand
different questions about one set is a thousand scans of it. Sharing by fingerprint is sharing at the
wrong level for this; what is needed is one reader per *binding*.

**Each of those scans runs flat out.** There is no scan interval. `LutScanReader.scan()` starts a new
scan whenever `poll()` finds its buffer empty, the feed polls every millisecond, and the rate is
bounded by how fast the cluster answers and by nothing else. `records.per.second` throttles records
within a scan; nothing throttles scans. The class's own javadoc describes a scan interval that was
never written, and `SourceCapabilities.typicalLatency` — declared by every source, one second for
this one — is read by no code in `src/main`.

That reorders this wave's §3. It is not "a thousand queries is a thousand scans", which is a problem
at a thousand. **It is that one query saturates a fair fraction of a single Aerospike node on its
own**, which is a problem at one, and a shared scan does not fix it — a shared scan running flat out
is still running flat out. A scan interval is the cheaper change, it is needed whether or not the
shared reader is built, and it should come first.

### Filesystem: a descriptor and a millisecond timer each

One descriptor per bound source, held for the life of the query, and `FilesystemSourcePlugin` returns
exactly one partition per binding so that is also one per query. Following costs a `stat` and a
`read` per poll, a thousand times a second, per source: 12.8 ms of CPU per second per followed file
with nothing being written to any of them.

Measured at two source counts to check it is a per-source constant rather than a constant of the
node — 16.9 ms/s each at 50, 12.8 at 100, roughly flat and slightly sublinear as the carriers begin
to contend.

**Those two figures come from running the test on its own, and that restriction is not a formality.**
Process CPU counts the whole JVM, so inside a full `mvn verify` the verify's own compilation and
garbage collection land in the same counter: the same test run that way reported *minus* 25.8 ms/s
per source at fifty and a heap delta of minus three gigabytes. The test therefore reports the CPU and
ratchets only the counts — descriptors and platform threads — which came out identical in both runs.
A CPU number from this engine is quotable only from a quiet machine. Hundreds of followed files is single-digit cores of pure polling. It does not fail; it
simply consumes the machine, and then the latency degrades instead of the CPU growing, because the
virtual scheduler's carriers are capped at `availableProcessors`.

### What breaks first, and with what error

**File descriptors, and the error names the wrong thing.** Nothing in this repository reads, checks,
reports or documents `ulimit -n`. Measured under `ulimit -n 300`:

- the 276th filesystem source fails with `PRV-5040 FILESYSTEM_DECODE_FAILED cannot open <path>` —
  a decode error code for a resource exhaustion, naming a file that is entirely correct;
- the 224th Aerospike source fails with `PRV-5080 AEROSPIKE_CONNECT_FAILED ... Check the host list,
  that the cluster is up, and that this process can reach the service port`. The cluster is up, the
  host list is right, the port is reachable, and every remedy in the sentence is wrong.

The Aerospike one cannot currently be improved by catching it: the client's
`AerospikeException$Connection` carries no cause, so the `SocketException` that said "Too many open
files" is gone by the time the plugin sees it. Diagnosing it needs a proactive check of the
descriptor headroom, not a better catch. The filesystem message is fixed (SRC-5); the code is not,
and the missing ceiling check is not.

At the common default of `ulimit -n 1024` this lands somewhere under a thousand sources — the same
order of magnitude as the target, not a distant ceiling.

### What is measured, what is extrapolated, and what is unknown

**Measured**, on one machine, and repeatable by running the two tests: every number in the tables
above. The CPU figures need the tests run on their own; see the note above on what a busy machine
does to them. The descriptor and thread counts are stable either way. The Aerospike figures are the cluster's own counters. The descriptor limits come from running
the real plugins under a real lowered `ulimit`.

**Extrapolated**, and to be read as arithmetic rather than as a result: anything multiplied up to a
thousand. A thousand followed files is "12.8 ms/s × 1000 = 13 cores" only until the carriers
saturate, after which the true statement is that the latency degrades instead. A thousand Aerospike
queries is not "1000 × 203% of a cluster" — the cluster saturates long before, and what happens past
that point is a queue nobody here has measured.

**Unknown, and not guessed at:**

- *Throughput.* Nothing here is a rate of rows. The same caveat as the section above applies and for
  the same reason.
- *What a real Aerospike cluster does under this load.* Every Aerospike number was taken against a
  single containerised Community node sharing a host with the engine. The shape of the answer — that
  scans scale with registrations and that each runs unthrottled — does not depend on the cluster
  size. The absolute CPU figures do, and should not be quoted as anything but this machine's.
- *How SRC-7's unbounded scan buffer behaves at a realistic set size.* It is read from the code:
  `scan()` collects the whole matched result into an `ArrayList` and the first scan of any fresh
  registration matches the entire set. Nobody has run it against ten million records, and until
  somebody does, the size of that heap spike is arithmetic.
- *Anything with more than one node in it*, which stays with ADR-034.

### What this changes about the four things in the way

§3 was fourth in the list of what to fix and third in the order they bind. It moves: **the scan
interval is the first item of the wave**, ahead of the arena sizing, because it is the only one that
is already a problem at a query count of one and the only one whose cost lands on somebody else's
machine. The shared reader stays where it is, and it is a larger piece of design than §3 implies —
the seam is the *binding*, not the fingerprint, and `QueryRegistry` opens feeds per computation.

And §2's budget is understated for the workload the target names: an Aerospike-backed query is two
platform threads, not one, because the plugin gives every registration its own client.
