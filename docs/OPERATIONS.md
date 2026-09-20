# Operations

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Running Pravaha: what bounds what, what to watch, and what is honestly not solved yet.

This page tries to be useful rather than reassuring. Where something is unfinished it says so, with
the consequence, because an operations guide that only describes the happy path is how an incident
becomes a surprise.

---

## What holds memory, and what bounds it

| Holder | Bounded by | On exceeding |
|---|---|---|
| Windowed aggregate state | the window — it closes and releases | — |
| **Unwindowed keyed aggregate** | nothing | **refused at planning** (`PRV-2050`) |
| Stream-to-stream join state | the **match window** (1 h event time, default) | rows released as the watermark passes |
| …and as a backstop | `maxRowsPerSide` | **fails** (`PRV-3021`) — never evicts, see below |
| A served view | **retention** — forever unless the registration sets one (`RETAIN FOR`, `--retain`) | oldest rows forgotten |
| …and as a backstop | `maxKeys` | **fails** (`PRV-4022`) |
| Subscriber buffers | `SubscriptionOptions` | conflate / drop / fail, per the subscriber's choice -- which over Flight it now genuinely is: the choice rides on the subscription ticket, where until STRM-16 it went nowhere and every remote subscriber was `(10 000, CONFLATE)` |
| Concurrent reads | `ReadAdmission` | refuse (`PRV-4026`–`4028`) |
| Readable audit trail (`GET /api/v1/audit`) | `pravaha.security.audit-recent` decisions (10,000 default), only with `audit: memory` or `file` | oldest dropped from the readable window; the response says how many, and a `file` sink still has them |

The distinction that runs through all of it:

> **A bound that changes the answer belongs in the query's meaning. A bound that protects the machine
> belongs in configuration, and should fail rather than quietly alter results.**

So a view *forgets* (retention is a cache policy) and a join's row ceiling *fails* — evicting a join
to fit would silently lose matches the query asked for, and a wrong answer is worse than an error.

## What to watch

| Signal | Where | Means |
|---|---|---|
| `evicted()` on a view | `ServedView` | Retention is working. **Rising fast** means the window may be shorter than the questions being asked of it |
| `joinRowsEvicted()` | `InterpretedPipeline` | The match window is releasing join state, as intended |
| `dropped()` / `conflated()` | `Subscription` | A consumer is falling behind. Never silent — this is why it is counted. Over Flight the count reaches the client too, on every batch after a loss (`ChangeBatch.droppedBefore`), where it used to reach an audit sink once and the subscriber never (STRM-10) |
| `rejectedCount()`, `queueTimedOutCount()`, `tenantRejectedCount()` | `ReadAdmission` | Read load exceeding capacity, and which of the three ways |
| `avoidableForks()` | `RegisteredQuery` | You are running N computations where one would do |
| Shared fingerprints | `pravaha queries`, console | The sharing claim holding — or not |
| `subscriberCount()` | `RegisteredQuery` | Consumers attached. Zero on a query somebody expects to be watched is a clue, and it does return to zero -- a drop used to leave every subscriber attached for ever and the number permanently wrong (STRM-12) |

These are on the objects and through the SDK. They are **also** published to Prometheus by the
server — see *Watching a running node* below, which is the part that was added later and left this
sentence contradicting it. Per-query **state** is published too, since ADR-037 B1 — see the table
under *Watching a running node*. What is still absent is engine-internal metrics (lane throughput,
backpressure); wiring those to Micrometer belongs to the control-plane wave. Saying so beats implying
a dashboard exists.

## Disk

**Checkpoints are pruned automatically.** `PeriodicCheckpointer` takes a checkpoint on
`pravaha.checkpoint.interval` and then calls `FileCheckpointStore.prune(keep)`, keeping the newest
`pravaha.checkpoint.keep` (default 3) per query; `QueryRegistry` constructs one for every
registration when `pravaha.checkpoint.directory` is set. Nothing has to be pruned by hand, and this
section used to say the opposite.

There is **no RocksDB**, by decision ([ADR-044](adr/044-no-rocksdb-the-mapped-tier-is-l1.md)): a
native library is the one dependency this bundle refuses. State is off-heap plus checkpoint files,
and optionally a memory-mapped overflow tier, which is the on-disk tier:

```yaml
pravaha:
  state:
    spill:
      directory: /var/lib/pravaha/spill   # a directory alone switches it on
      max-overflow-slabs: 512             # per state store: the most overflow slabs it holds at once
      compaction-threshold: 0.5           # compact a store once this much of its overflow is free
      max-bytes: 20GB                     # the node's disk budget for spilled state; 0 = none
      # enabled: false                    # explicit, and wins in both directions
```

**A byte quota, and a disk that is checked before it is full.** `pravaha.state.spill.max-bytes` is the
node's budget for spilled state, in the unit a disk is sized in, across every query on the node
(default `0`: no quota). A query whose state needs an overflow slab past it stops with `PRV-4005`,
before anything is written. It sits beside `max-overflow-slabs`, which is kept and still bounds
**one** state store in its own slab size, so that one runaway join cannot take the whole budget.
Independently, before every slab is created the spill directory's filesystem is asked how much space
it has, and a slab that would not fit is refused with `PRV-4006`. Slabs are sparse files that take
their disk as state is written into them, so without that check a full disk would surface as a fault
inside a write to mapped memory rather than as an error with a name. The check is per slab, not a
reservation: another process filling the same filesystem between two slabs can still get there
first, which is why `max-bytes` below what the filesystem holds is the setting to rely on. Watch
`pravaha_state_spill_bytes_mapped` against it.

**Compaction.** Released state is reused within its size class, but a slab is never handed back
by reuse alone, so a churning query — windows expiring, join rows retracted — would hold every file it
ever carved. When `pravaha.state.spill.compaction-threshold` of a store's carved overflow space is free
(default `0.5`), the live blocks in its sparse slabs are moved into the rest, and those slabs' files are
truncated and deleted. It runs at the end of a batch on the query's own lane thread — the one moment no
operator holds a handle into its state — so it costs that lane a pause proportional to what it moves,
and nothing on any other lane. A slab that cannot be emptied (no room elsewhere under the tier's
ceiling) is simply kept; compaction never loses a block. Per query, `pravaha_query_spill_bytes`,
`_live_bytes`, `_fragmentation`, `_compactions` and `_slabs_released` (below) show whether it is
keeping up.

**Should you turn it on?** Yes on a node with a local disk whose queries could surprise it — with
`max-bytes` below what the disk holds, and the directory on a **real disk** (a tmpfs `/tmp` is RAM,
and spilling there only moves the out-of-memory to the kernel). But turn it on knowing what it is
for: **surviving state you cannot hold, not serving it.** ADR-044 measured it twice.

*While the page cache holds the spilled files* — the state is bigger than the query's memory ceiling
but smaller than the node's free RAM — spilled state ran at 0.44–1.15x of the same load all in RAM
(inserts the worst, probes and window firing nearly unaffected), at 1–16x a 64 MiB ceiling, and
compaction brought the files back down to the live state in about a second per gigabyte freed.

*Past that* — the second measurement caps the whole process, page cache included (cgroup v2
`MemoryMax`, swap off), so the state really is larger than the memory it may use. On the same NVMe
laptop, a join at 1–4x the cap and a windowed aggregate at 1–4x:

| | inside the page cache | past it |
|---|---|---|
| Join insert (sequential) | 1.2–2.2 M rows/s | 1.3 M/s until the index no longer fits, then 5–6 k/s |
| Join probe at a random key | 160 k–540 k /s | 1,400–2,600 /s |
| Join retract at a random key | 75 k–1.4 M /s | 1,000–1,100 /s |
| Aggregate update at a random key | 480 k–1 M /s | 1,300–4,700 /s |
| Latency, p50 / p99 | 0.3–13 µs / 1–25 µs | 0.05–1 ms / 0.8–6 ms, with maxima of 0.7–1.6 s |

Each random lookup past the cache is two or three major faults — the index's slot table, its block,
then the row — and each fault reads `read_ahead_kb` (128 KiB here) to use 4 KiB of it, so the device
is saturated at a few thousand operations a second rather than by its IOPS. Lowering
`/sys/block/<device>/queue/read_ahead_kb` for the spill device is the lever that exists today; the
engine cannot ask for `MADV_RANDOM` on JDK 21. The maxima are the kernel reclaiming (and writing
dirty pages back) inside an operation, not the device.

**So size the page cache for the index.** Roughly 100 bytes per distinct key per join side (the slot
table, 16 bytes a slot at a load of at most 0.7, plus its key/value block) and about 340 bytes per
windowed accumulator, plus whatever rows the workload actually touches; keep that inside the node's
free RAM and the first set of numbers holds. A key index spills whole now — its slot table included,
in 16 MiB segments — so a spilled query's memory stops growing with its key count, at the cost of
those page faults once the table is not cached. A table is bounded at 2<sup>30</sup> slots (about 750
million keys), past which a growth is refused with `PRV-4001`.

**And leave room for the RAM tier itself.** The ceiling is per state *store*, and a store's key index
has a slot table budgeted at the same ceiling, so one spilling query's floor is *up to twice* the
ceiling per store — a windowed aggregate with `COUNT(DISTINCT)` has two stores, so about four times.
In the measurement a query with a 64 MiB ceiling held ~480 MiB of anonymous memory (including a
160 MiB heap) before anything spilled, and under a 512 MiB cgroup limit the kernel OOM-killed it
rather than the tier saving it. Budget RAM for that floor, page cache for the index, and only then
count on the disk. Firing a window is separate again: `fire` builds the window's groups on the
**heap**, one entry per accumulator, so a query with millions of live accumulators needs hundreds of
megabytes of heap at every watermark advance (1.6 M accumulators threw `OutOfMemoryError` with a
160 MiB heap; the same window fired at ~375,000 groups/s with 1 GiB).

With it, join and windowed-aggregate state that outgrows memory is written to mapped files and the
query keeps running, slower, instead of dying with `PRV-4001`. It is off by default, and stays so
(ADR-044): there is no directory it could safely assume. Every state
shape spills: an aggregate containing `COUNT(DISTINCT)` keeps its per-value counts off-heap in the
same kind of store as everything else, and spills with it (ADR-044; it used to be refused with a
code that is now retired). Without the tier, the failure mode is memory, not disk.

## Capacity: the two numbers that interact

Retention says what a view *means*; the ceiling says what the node can *afford*. When they disagree
the view refuses and the message names both — for a view registered to retain 24 hours:

> `PRV-4022 … 24 hours of this data is more than 1,000,000 rows. Shorten the window, or provision for
> the volume.`

A view with no retention keeps everything, so the same refusal says instead that its key space keeps
growing, and to give it a retention, bound the key, or raise the ceiling deliberately.

Sizing rule of thumb: **rows ≈ arrival rate × retention window × distinct keys touched**. If that
exceeds the ceiling, one of the two numbers is wrong, and which one is a product question rather than
an engineering one.

## Sizing a node for many queries

The defaults were chosen for **one** query with a source that never stops: latency mattering more
than a core, a wide row, a batch that never spans a slab. A node holding hundreds of small
continuous queries is the opposite workload, and until ADR-036 it inherited sizes nobody had chosen
for it — and could not change them, which is why eleven error messages told operators to change
`arena.slab.size` or `lane.inbox.cell.size`, neither of which existed (PF-3). They name the real
keys now.

```yaml
pravaha:
  lane:
    batch-size: 512            # rows drained from the inbox per step
    wait-strategy: BACKOFF_PARK
    inbox:
      cells: 2048              # ring cells; one cell holds one row
      cell-bytes: 512          # a row that does not fit is refused at ingest, not buffered
    arena:
      slab-bytes: 4194304      # 4 MiB
      max-slabs: 8
```

> **What these settings are settings *for*: [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md).** It explains
> why a claim is one cell whatever the row's size, why `cell-bytes` is a hard ceiling rather than a
> hint, and why a full inbox backpressures instead of spilling.


| Key | Default | What it decides |
|---|---|---|
| `pravaha.lane.batch-size` | 512 | Rows drained from the inbox per step |
| `pravaha.lane.wait-strategy` | `BACKOFF_PARK` | How a lane waits when its inbox is empty |
| `pravaha.lane.inbox.cells` | 2048 | Ring cells, so how deep the buffer is |
| `pravaha.lane.inbox.cell-bytes` | 512 | The widest row that can be ingested at all |
| `pravaha.lane.arena.slab-bytes` | 4194304 | Off-heap slab size, and the largest single output row |
| `pravaha.lane.arena.max-slabs` | 8 | The lane arena's ceiling, `slab-bytes × max-slabs` |
| `pravaha.lane.backpressure.high-watermark` | 0.8 | Inbox fill at which the source feeding a query is paused |
| `pravaha.lane.backpressure.low-watermark` | 0.5 | Fill at which it is let go again; must be below the high one, or the node refuses to start naming both keys |
| `pravaha.lane.multiplex.enabled` | `false` | Whether registered queries share lanes, and so share inboxes (below) |
| `pravaha.lane.multiplex.lanes` | 0 | How many shared lanes; 0 means one per available processor |
| `pravaha.lane.multiplex.max-queries-per-lane` | 300 | The ceiling on queries one shared lane carries |

**The arithmetic, measured rather than guessed** — and the obvious guess is wrong. `NodeScaleTest`
reports off-heap per query:

| | Bytes | Measured |
|---|---|---|
| Inbox | `inbox.cells × inbox.cell-bytes` | 2048 × 512 = **1,024 KiB** |
| Pipeline arena | sized from the plan, not a constant | **284 KiB** once rows move; 0 before |
| Lane arena | up to `arena.slab-bytes × arena.max-slabs`, allocated on first use | **0** for a projection *and* for a windowed aggregate |

So an **idle** query holds **1,024 KiB** — its inbox and nothing else — and an **active** one
**1,328 KiB**, the same for both shapes that were measured. Before this wave an idle query held about
5 MiB, so a thousand of them is about a gigabyte rather than five.

**The lane arena is not where the money is.** Its first slab is allocated by the first `allocate()`
rather than at registration (W9-6), and for both a projection and a windowed aggregate it is never
allocated at all, because operator output goes to the view rather than through the lane's scratch. It
stays settable because a plan that *does* write through it — joins, wide fan-out — will, and because
the messages that say "raise `pravaha.lane.arena.slab-bytes`" have to be able to mean it.

**Sizing down means the inbox.** 2048 × 512 is a megabyte of burst capacity per query, and a query
fed by an Aerospike scan running once a second will never use it:

```yaml
pravaha:
  lane:
    inbox: { cells: 256, cell-bytes: 256 }
```

That megabyte becomes **64 KiB** — about 64 MB for a thousand queries rather than a gigabyte. One
rule, and it fails loudly rather than quietly: a **cell must fit the widest row the query will see**,
because a row that does not fit is refused at ingest rather than buffered (`PRV-3001`, and the
message names `pravaha.lane.inbox.cell-bytes`). The equivalent rule on the arena, where a plan uses
one, is that `batch-size × widest output row` must fit a slab.

The node logs its lane sizing at startup, so what it is actually running with is in the log rather
than inferred from the file.

### Sharing lanes between queries

The other way to take the inbox out of the per-query arithmetic is to stop giving each query a lane:

```yaml
pravaha:
  lane:
    multiplex:
      enabled: true
      lanes: 0                   # one per available processor
      max-queries-per-lane: 300
```

With it on, registered queries run as pipelines on a fixed set of shared lanes, and a lane's inbox
and arena serve every query on it. **It is off by default, deliberately.** A shared lane shares its
fate: a query whose pipeline throws — including one refused with `PRV-4001` for its state — kills
the lane, and every query on it with it, where a lane per query loses one. A node already reaches
its query target without sharing (W9-11), so this is a memory trade a deployment chooses.

A registration is placed by one rule: **the least loaded shared lane below
`max-queries-per-lane` wins**, by query count, lowest number on a tie. What the query reads does not
matter — two queries over `txn` share a lane, and so does a join (LANE-2).

**A registration that fits on no shared lane is not refused — it gets a lane of its own**, exactly
as with sharing off. Turning a memory setting on must never make a node accept fewer queries than
it does with it off. What that costs is the inbox sharing exists to save, and it is visible:

- `pravaha_lane_shared_queries{lane=}` — queries on each shared lane, against the ceiling
- `pravaha_lane_own_queries` — queries holding a lane of their own. **Rising on a node with sharing
  on** means the shared lanes are full: raise `lanes` or `max-queries-per-lane`, or accept the inbox
  each costs
- `pravaha_lane_shared_bytes` — the off-heap the shared lanes hold between them, once
- `pravaha_debug_sessions_open` — debug sessions open on this node, each a second copy of a query
- the node's status and its startup log carry one line: `lanes: shared, queries per lane [..] of at
  most 300; N on lanes of their own`

**What is shared, and what is not.** Each row on a shared lane carries a *route*: every hosted query
has its own, and what it is fed alone — a reader of its own, rows pushed by an embedder, a catch-up
read — reaches that query and no other on the lane. A reader shared by several queries (SRC-3, a
source that declares at-least-once and no order: Aerospike and Cassandra today) writes each row into
a shared lane **once**, and every query on that lane reading it is handed the one copy. Measured with
1,000 queries over one source on 8 shared lanes: 8 lanes and 8 copies of each row, where lanes of
their own are 1,000 lanes and 1,000 copies — 8 MiB of inboxes at the defaults against about 1 GB
(`SharedLaneDensityTest`). A source promising exactly-once or order (a file, Kafka, JDBC, CDC,
Delta) still gets a reader per query, so its queries share the lane and its inbox but each writes its
own copy into it.

**What a slow neighbour costs.** Nothing is dropped for anybody. A query that is slow for its share
of a lane slows the lane's drain for every query on it, and when the lane's inbox fills, everything
feeding the lane is held: a reader of its own is paused at the high watermark as usual, and a shared
reader stalls — for every query it feeds, on any lane — because it writes a row to all of them or
none. A lane that frees no cell for 30 seconds fails the feed writing into it with `PRV-3002`, naming
the lane as not draining, rather than hanging it.

**Pause, drop and checkpoints on a shared reader.** A query paused off a shared reader stops at an
exact row and records that row as its position, so a checkpoint taken while it is paused resumes it
where it stopped — not where the others have got to. Resuming catches it up through its own route,
**unless nothing else was reading**: with every query on the reader paused the reader stands still
while the source moves on, so a resume — or a fresh registration — moves the reader to that query's
own row and lets it read the gap once, rather than catching it up over a gap the reader would then
read again (LANE-6). Nothing is given up by moving it: every other query on that reader is paused,
and a paused query resumes from the row it recorded, not from where the reader stands. A
drop takes the query off the lane at the row it left and leaves the rest reading. A checkpoint of
any query holds the shared reader between rows, so its state and its offset name one point, exactly
as on a lane of its own; each query restores from its own checkpoint.

**Threads do not enter this arithmetic.** A query costs no platform thread of its own: lanes share a
fixed runner pool of one thread per core, the periodic work shares one process-wide clock, and the
feed loop and Flight's call executor are virtual. The Aerospike plugin was the one exception — an
`AerospikeClient` and so a `tend` thread per registration — and since SRC-2 it shares one client per
cluster **per credential**, so that is one thread for all of them however many register. **No
per-query platform thread is left on the node.**

## File descriptors

**The ceiling a node holding many sources reaches first**, and until SRC-4 nothing here read,
checked, reported or documented it. One bound source costs about **one descriptor**, held for the
life of the query. At the common default of `ulimit -n 1024` that lands somewhere under a thousand
sources — the same order of magnitude as the target, not a distant ceiling.

The node now says so at startup:

```
file descriptors: 148 of 1024 file descriptors in use, 876 remaining -- one bound source costs
about one, so this is roughly the ceiling on how many sources this node can hold
```

and a source that fails to open while the process is near that ceiling gets an extra sentence naming
`ulimit -n` and `LimitNOFILE` rather than sending the reader to the network. Measured under a real
`ulimit -n 300`: silent at 6 of 300, and saying so at 280 of 300.

Two failures still name the wrong thing when they are really exhaustion, and the message is what you
will see first:

| | |
|---|---|
| `PRV-5040 FILESYSTEM_DECODE_FAILED cannot open <path>` | A decode code for a resource exhaustion. The path it names is correct; the category is not (SRC-4, code unchanged) |
| `PRV-5080 AEROSPIKE_CONNECT_FAILED ... Check the host list, that the cluster is up, ...` | Every remedy in that sentence is wrong when the cause is descriptors. The Aerospike client's exception carries no cause, so this cannot be improved by catching it — the descriptor line above is the diagnosis |

## Admission control

Reads and continuous queries share a machine. An unbounded read path is how a client with a loop
stops a continuous query keeping up with its input — and the continuous query is the one with a
service level.

```java
new ReadAdmission(maxConcurrent, maxQueued, tenantShare, queueTimeout)
ReadAdmission.of(16)    // 16 concurrent, 32 queued, half per tenant, 2s wait
```

**Refusal is the feature.** A queue longer than the client's timeout is work nobody is waiting for,
which the server will nevertheless do at the expense of work somebody is. The per-tenant share is the
part that is easy to leave out: the fairest possible global limit still lets one tenant hold every
permit, and what the other tenants report is "Pravaha is down".

Refusals reach clients as `RESOURCE_EXHAUSTED`, which drivers retry with backoff — not as
`INVALID_ARGUMENT`, which they give up on.

## Clustering: choosing a coordinator

Pluggable, selected in configuration — and the mechanisms are **not interchangeable**, which is why
the deployment declares two separate things:

```yaml
pravaha:
  cluster:
    mode: REPLICATED        # what you are asking of the cluster  (a correctness question)
    mechanism: socket       # which coordinator to use            (an operational one)
    socket:
      peers: "a=host1:9070,b=host2:9070,c=host3:9070"
      heartbeat.millis: 1000
      timeout.millis: 5000
```

| Mechanism | Excludes split-brain | External service | For |
|---|---|---|---|
| `single` | ✅ (there is no second node) | no | One node. The default, and what embedded always is |
| `socket` | ❌ | no | Development, and `REPLICATED` where a split brain costs duplicated work |
| `zookeeper` | ✅ | yes | `PARTITIONED`, once a node can serve it (below). Needs `plugins/pravaha-cluster-zookeeper` on the classpath |

| Mode | Means | Needs consensus |
|---|---|---|
| `SINGLE` | One node | no |
| `REPLICATED` | Several nodes, each holding the whole state | no |
| `PARTITIONED` | Partitions owned by particular nodes — **refused by a node today** | **yes** |

**A node refuses to start `PARTITIONED` on any coordinator** (`PRV-9002`). Membership and partition
assignment are built and tested, but nothing in the node asks which partitions it owns before
reading, so it would serve every partition while reporting itself partitioned (S-3). The refusal
lifts with ADR-039 item 8's consumer. Separately, and checked first, **`PARTITIONED` on a coordinator
without consensus is refused** for split-brain, not warned about. Two nodes each believing they own a partition means two nodes writing the same aggregate, and
the damage is silent, durable, and found later by whoever reconciles the numbers. §21.2 rejected a
store-backed CAS lease for exactly this reason.

The socket coordinator elects "the lowest id among peers I can reach", which each side of a partition
computes for itself — so a partition produces two leaders, each correct from where it is standing.
That is survivable when leadership only decides who does redundant work. It is not survivable when it
decides who owns state.

The startup log says what was chosen and what it promises:

```
cluster mode REPLICATED on socket (NO consensus — cannot exclude split-brain), self-contained, development only
```

A deployment that runs etcd or Consul can supply its own coordinator through `CoordinatorProvider`
and `ServiceLoader`, without the engine knowing about it.

**Raft is not implemented.** §21.2 and ADR-009 choose embedded Raft (Ratis) as the eventual default —
consensus without a mandatory external service. Until it exists, `PARTITIONED` — once a node serves
it — means ZooKeeper.

## Rebalancing: what happens when the membership changes

> **Not wired into a node.** What follows is how `Rebalancer` and `PartitionHandoff` behave as a
> library, tested with real threads and a real ZooKeeper ensemble. No running node constructs either,
> nor a `PartitionAssigner`, so a node never rebalances, and a node refuses to start `PARTITIONED`
> at all (S-3; ADR-039 item 8). Read this as the design that ships next, not as what a
> node does today.

A node joins or leaves, the assignment is recomputed, and the partitions whose owner changed are
handed over one at a time. Each handoff runs a fixed sequence, and the sequence *is* the correctness
argument:

```
source pauses -> source snapshots (state + offsets) -> target restores
              -> ownership flips -> target resumes -> source releases
```

Two windows are worth understanding, because one is acceptable and the other never is.

**Between the pause and the resume, nobody is processing that partition.** Those keys are
unavailable — queries touching them wait — and the budget is 5 s (NFR-6). Input is not lost: it
accumulates at the source and is read from the handed-over offsets. Everything else in the query
carries on.

**At no point are both nodes processing it.** Starting the target before stopping the source would
shorten the pause and mean two nodes updating one aggregate. The pause is a cost you can measure;
double ownership is a corruption you find later.

Handoffs run **one at a time**. Ten in parallel makes ten slices of the key space unavailable
together, on a node that has just lost a peer and is already the busiest it has been all day. Serial
handoff turns a cliff into a ramp.

The first failure **stops the run**. A rolled-back handoff leaves the partition with its original
owner, which is a fine place for it to be; the next membership change decides afresh. Grinding on
after the first failure turns one problem into N.

| You see | It means |
|---|---|
| `rolled back, a still owns it` | Safe. The source kept state and offsets. Retried on the next change |
| `PRV-9006 ... must not be handed back` | Failed *past* the flip. That partition needs checkpoint recovery — do not move it back |
| `PRV-9006 ... is not being served` | Rollback itself failed. A partition is paused with no owner serving it. Investigate now |
| `PRV-9007 ... flapping` | Cooldown (default 1 min). A node is appearing and disappearing; fix that before rebalancing |
| `over the 5000ms budget` | The handoff completed but ran long. Those keys were unavailable for that long |

To see what *would* happen before doing it, the plan is inspectable: which partitions move, and to
which node.

Partition count is fixed at registration (default 1024) and immutable for the query's life —
changing it would rehash every key, which is what virtual partitions exist to avoid. Assignment uses
rendezvous hashing, so adding a fourth node to three moves about a quarter of the state rather than
three quarters, and it is a pure function of the membership: every node that agrees on who is in the
cluster computes the same owners without asking. That is also *why* `PARTITIONED` needs consensus —
nodes that disagree about membership confidently compute different owners.

## Files that hold data

Three files hold what a customer would call their data, and all three are now created **owner-only**
(`rw-------`), in a directory narrowed the same way, before the first byte is written:

| File | Holds |
|---|---|
| The registry journal | Query text and the values clients filtered on — account numbers, customer ids |
| Checkpoints | Serialised operator state, which is the aggregated data itself |
| The dead-letter queue | The raw bytes of every record that failed |

The dead-letter queue is written only when something asks for one: `pravaha run --dlq <file>`, or
`pravaha.dlq.directory` on a server (one `<query>.dlq` per query). Without it a record that cannot be
decoded still fails loudly rather than being discarded: the `run` command exits non-zero naming the
line, the column and the value, and on a server the source feed for that query stops — the query goes
on reporting `RUNNING`, and the stop is shown with its code wherever the query is described (*A
source that stopped*, below). With it, the run finishes,
the good rows are written, and every rejected record is one JSON object per line — timestamp, query,
correlation id, source offset, reason, and the original bytes in Base64 — meant to be read with
`grep` and `jq` during an incident. The count of rejects is printed next to the row counts, and if
the queue itself could not write, that count goes to stderr: a run that reports `ok` while having
quietly discarded input is the thing the queue exists to prevent, not something it may cause.

**The queue is bounded, and it evicts rather than refuses.** `pravaha.dlq.max-bytes` defaults to
256 MiB a query, with `pravaha.dlq.max-entries` and `pravaha.dlq.max-age` off unless set. Past a
bound the *oldest* entries go: refusing the newest would hand the writer a queue that has stopped
accepting, whose only remaining moves are to fail the poll — stopping the source, which is what the
queue exists to prevent — or to drop the record, which is the silent loss it exists to prevent. The
loss is recorded three ways so it is never silent: a line in `<query>.dlq.evicted` that outlives the
process, a `WARNING` in the log naming the bound, and an `evicted` count on every surface that shows
the queue. A depth without that count cannot be read: a queue steady at two thousand is either one
bad afternoon or a bound throwing two thousand a minute away.

### Running with a dead-letter queue

The file is no longer the only way in. A containerised operator has a browser and a token, not a
shell on the pod, and the same entries are on every surface, each deciding by the **view's own**
authorization rules:

| Where | What it offers |
|---|---|
| `GET /api/v1/queries/{name}/dead-letters` | A page, newest first, with the queue's totals and its retention bound |
| `GET /api/v1/queries/{name}/dead-letters/count` | The depth, the bytes and what retention took — the call a dashboard polls, with none of the records in it |
| `GET /api/v1/queries/{name}/dead-letters/{id}` | One entry whole: its bytes, its reason with the `PRV-` code, its stream and offset, and when |
| `POST /api/v1/queries/{name}/dead-letters/replay` | `{"ids": [...]}` — feeds chosen records back through the query |
| `pravaha dlq list \| show \| replay` | The same three over Flight, through the published SDK |
| Both SDKs | `deadLetters` / `deadLetter` / `replayDeadLetters` (Java), `dead_letters` / `dead_letter` / `replay_dead_letters` (Python) |
| `/actuator/pravaha` | Per query, `deadLetters`: whether a directory is configured at all, how many are waiting, the bytes, what was evicted, and the bound. Counts, never records |
| The console | **Dead letters** on the query's page: the queue newest first, each code linked to its help, the record legible, and a checkbox per entry to replay it |

**Who may see a record.** A dead letter's bytes are a row of the source, and a record that failed to
decode has *no row* for a row filter to be evaluated against — so the choice is all or nothing. A
caller whose read of the view is row-filtered (the same flag SX-18 withholds a view's counts on) is
shown the count, the id, the stream, the offset, the code, the size and when, and **not** the record
or the decoder's sentence, which quotes the value it choked on. The refusal is stated in the answer
rather than left as an empty field, so nobody reads "withheld" as "empty".

**Who may replay.** A replay puts a row into a view other people read, so it is authorized as `DROP`,
`PAUSE` and `RESUME` are — `mayAdminister`, which already refuses a row-filtered principal by name.
Reading a dead letter and replaying it are two different rights, and a deployment that separates
operators from readers gets to separate them here. Every decision is audited: `dlq.list`,
`dlq.show`, `dlq.count`, `dlq.replay`.

**What a replay is.** A **new row at the query's current frontier, not a rewind.** The recorded bytes
go back through the same decoder that refused them, and the row is applied to the state the query has
now: nothing is re-read, no source offset moves, and no earlier answer is recomputed — a query that
has already emitted a window the record belongs to will not emit it again, and the record lands as
late data. A record that fails to decode again **returns to the queue** as a fresh entry naming the
id it was a replay of, rather than being retried, so a client walks forwards through the queue
instead of round one entry. It is not idempotent, and there is no "replay everything": a queue is
usually a mix of causes and most of it is still malformed.

A replay is refused with `PRV-4092` where it could not be correct, and the message says which: the
stream's schema has changed since the record was rejected (each entry records the schema it was
rejected against); the source promises `EXACTLY_ONCE` and has **not** read past the record's offset,
so it is going to deliver the record again itself and feeding it in now would count it twice; the
source cannot decode a record outside its own read; or the query is gone or reads a different stream
now. Where the source *will* send the record again, correcting it at the source is better, because
it then arrives in order.

**What to watch.**

| Meter | Is |
|---|---|
| `pravaha_query_dead_letters{query=}` | How many are waiting now — the one to alert on. The running total keeps rising for a queue somebody is on top of; the depth does not |
| `pravaha_query_dead_letters_bytes{query=}` | How large the file is, against `max-bytes` |
| `pravaha_query_dead_letters_evicted_total{query=}` | What retention has thrown away and will not give back |
| `pravaha_query_dead_letters_write_failures_total{query=}` | Records the queue itself could not write. Non-zero means the queue needs attention *before* the records in it do: those records are gone and nothing else says so |
| `pravaha_query_dead_letters_fraction{query=}` | The share rejected in the current window |
| `pravaha_query_dead_letters_degraded{query=}` | 1 once that share passes the threshold |

The last two are the rate, and the rate is what matters: every real feed produces some rejects, so a
queue that alerts on the first one is a queue whose alerts get muted in week two. Past the threshold
the node's health indicator reports `DEGRADED` (design 15.6) rather than stopping the query —
stopping would discard the records that *are* valid, which is the bigger loss. `/actuator/health`
also carries `deadLetters` (how many are waiting across the node) and `deepestDeadLetterQueue`.

Two of the three previously carried a comment telling the operator to permission them like data.
An instruction to somebody who may never read it is not a control.

A fourth exists when an `aerospike` or `cassandra` source runs `deletes: detect`: the rows the source
has emitted, under `deletes.state.dir`, created owner-only the same way (*How hard a source is
polled*, below).

Best effort where POSIX permissions are unsupported — Windows, some network mounts — and it says so
at `WARNING` rather than failing the write, because refusing to run there would trade a
confidentiality problem for an availability one. **Nothing is encrypted at rest**; if that is
required, put the directory on an encrypted volume.

## Running against a source that does not end

Event time has to be generated, and the engine does not assume it for you:

```java
execution.generatingWatermarks();   // each stream uses the lateness it declared
```

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"
      event-time: event_time    # without this no watermark advances and a windowed query is refused
      out-of-orderness: 10s     # how late this stream's rows may be
      allowed-lateness: 0s      # how long a closed window still takes a correction; 0 = final
  watermark:
    idle-after: 30s
    tick: 1s
```

**Three settings, and every one of them decides whether memory is bounded at all** — not one of
them is a matter of taste.

*Out-of-orderness* is how late a row may be and still be waited for. Larger tolerates messier
sources and holds every window open longer, so state is larger. Smaller closes sooner and treats
more rows as late corrections.

*Allowed lateness* is what happens to a row later than that. At zero, the default, a window is
final when the watermark passes it and a later row is dropped. Above zero the window's state is
kept that much longer, and a late row within it corrects the published answer: a retraction of the
old result and the corrected one (see [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §6). That
makes every windowed query over the stream one that revises, so it can no longer write to an
append-only sink (`PRV-2041`), and it holds each window's state for the extra time. Until HLP-7 no
server setting reached it — `pravaha.streams.<name>.allowed-lateness` and `POST /api/v1/streams`'s
`allowedLateness` are the two ways now; an embedder sets `StreamSchema.allowedLateness`. It needs
an `event-time` and cannot be negative.

**A unitless number is seconds** for both keys. `out-of-orderness: 60` is a minute, not sixty
milliseconds, and `allowed-lateness: 30` is thirty seconds (TIME-3). Until this was annotated,
Spring's relaxed binding read them as milliseconds, and `60` produced a view identical to the
ten-second default -- the misconfiguration was invisible at the only surface that could show it,
and no bound can catch it, because sixty milliseconds is a legitimate out-of-orderness. Every
explicit spelling (`60s`, `PT1M`, `60ms`) means what it says.

**The node states what is in force, per stream, at startup.** One line each:

```
stream txn: event-time=event_time, out-of-orderness=PT10M, allowed-lateness=PT30S
stream ref: event-time=none -- no window over this stream can ever close
```

Read from the schema the node built rather than from the file, so a default shows as the default.
Before TIME-6 nothing anywhere mentioned out-of-orderness: `grep -icE "out-of-orderness"` over a
full startup log was 0 on every configuration, and four separate ways of arriving at "RUNNING,
ingesting, serving nothing" were indistinguishable from a query that was working.

Set it **per stream** -- lateness is a property of the source, and a query reading three streams
should get three tolerances rather than the worst of them. Configuration does that with
`pravaha.streams.<name>.out-of-orderness`; an embedder does it with `StreamSchema.outOfOrderness`.

> **`pravaha.watermark.out-of-orderness` is the node's default for the same thing**, applied to a
> stream that declares an event time and no lateness of its own. It was read by nothing until
> 2026-09-20 (DOCX-6) -- present in the shipped `application.yaml`, described there and in two
> documents as the engine-wide default, and setting it changed no answer. It has a reader now, and
> because its default is the 10 seconds a stream already took, a deployment that had not set it
> sees no change. It does **not** apply to a stream with no declared event time: there is nothing
> for the lateness to be about, and such a stream declaring `out-of-orderness` is now refused at
> startup rather than having it silently dropped (T-6).

*Event time* is `pravaha.streams.<name>.event-time`, naming the column that carries each row's own
time. Without it no watermark advances over the stream at all, so a windowed query over one is
**refused when it is registered** (`PRV-2002`, TIME-6) instead of reporting `RUNNING` over an empty
view for ever; the refusal names the stream and the key to set. An unwindowed query over the same
stream is unaffected, and so is a windowed query over a bounded read, whose windows are fired by
the end of the scan.

The node hands that column to the stream's source as its `event.time` option, and each source
stamps a row with the column's value. `feedfile` and `delta` did not until HLP-6: they ignored the
option and stamped every row zero, so the watermark stayed at 1970 and no event-time window over a
feed or a Delta table ever closed. Both use it now; the column must be a `TIMESTAMP` (for `delta`, a
Delta `TIMESTAMP`, whose microseconds become nanoseconds), a row whose value is null is stamped zero,
and a column that is missing or of another type is refused when the source is configured.

### How hard a source is polled

**Aerospike: `scan.interval.ms`, one second by default.** The plugin is scan-only (ADR-029), and
until SRC-8 there was no interval at all: `LutScanReader.scan()` started a new scan whenever `poll()`
found its buffer empty, and the feed polls every millisecond. Measured against a real Aerospike
Community node, **one** continuous query over a 200-record set produced **43–153 scans per second**
across runs and took the cluster's own `process_cpu_pct` from 1% to **203%** — with nothing changing
in the set. Four queries took it to 388–579%.

```yaml
pravaha:
  sources:
    txn:
      plugin: aerospike
      options:
        scan.interval.ms: 1000     # the default; 0 restores the old flat-out behaviour
```

It is **1.0 scans per second** now, and four queries are 3.8 — linear, where the rate previously
fell per query because the cluster was saturated. `records.per.second` is a different knob: it
throttles records *within* a scan and never throttled how often one started. Raise the interval on a
shared cluster; the cost of raising it is staleness, bounded by the interval.

**Aerospike with `deletes: detect` reads the whole set every pass.** A deleted record never matches
a last-update-time filter, so delete detection drops it and each pass is a full scan of the
partition range: size `scan.interval.ms` to the set, not to the rate of change. Cassandra's passes
were always full scans, so `detect` changes nothing it reads.

**Delete-detection state, on disk and in memory.** Each reader of a `deletes: detect` source keeps
every row it has emitted: about 150 bytes of heap a row plus the row's encoded size (measured: 145
bytes for a three-column row on Aerospike, 136 on Cassandra — a million rows is about 150 MB),
bounded per partition or token range by `deletes.max.keys`, which refuses by code (`PRV-5120` /
`PRV-5122`) rather than forgetting rows. On disk, under
`deletes.state.dir/<source>/<partition>/<reader>/`, a checksummed snapshot of those rows and a log of
the changes since it; a new snapshot is written when the log passes a quarter of the rows, so a
quiet source writes nothing. Put the directory on durable local disk, back it up with the
checkpoints — a restore that cannot find the rows its checkpoint names is refused (`PRV-5121` /
`PRV-5123`) — and expect `position()` to force the log to disk at every checkpoint. A durable
checkpoint deletes what it supersedes; the directory of a **dropped** query is not deleted, and can
be removed by hand once its checkpoint directory is gone.

**Filesystem with `follow: true` costs about 13 ms of CPU per second per source while completely
idle** — a `stat` and a `read` a thousand times a second, whether or not anything was written.
Measured at 100 followed files: 12.8 ms/s each; at 50: 16.9 ms/s each. A hundred idle followed files
is 1.8 cores. This is open (SRC-6), and it is the number to remember before binding hundreds of
followed files to one node.

### Idle timeout: how long a partition may say nothing

A query's watermark is the **minimum across its partitions** — necessary, because if one is behind,
the query cannot claim completeness past it. The failure is that a partition producing *nothing*
keeps its last watermark forever, so the minimum stays pinned to it and every window in the query
stops closing. Nothing errors. It presents as a hang.

Idle exclusion drops a silent partition out of the minimum, and lets it rejoin the moment it speaks.

| Getting it wrong | What happens |
|---|---|
| **Too long** | A desk that trades 09:00–17:00 goes quiet at 17:00 and the watermark freezes until 09:00. Sixteen hours of growing state and no output — from the *whole query*, not just that region |
| **Too short** | A source that batches every 60s, or a consumer pausing 20s to rebalance, is excluded while it still had rows coming. The watermark jumps, its rows land behind it, and **on-time data becomes a late correction** — right, but bought for nothing |

**The rule:** comfortably longer than the longest normal gap on your quietest partition, and
comfortably shorter than how long you can afford windows not to close. If you cannot say what the
longest normal gap is, that is the number to go and measure.

```yaml
pravaha:
  watermark:
    idle-after: 30s     # default. Minimum 1s, maximum 10m, both enforced
```

The bounds are **refused, not clamped** — a value quietly changed to something you did not ask for
is how a tuned number becomes a mystery six months later. Below a second, ordinary jitter excludes
partitions that were merely slow. Above ten minutes, a broken partition is indistinguishable from a
quiet one for longer than anyone can operate, and state grows throughout; a source whose normal gap
exceeds that is a batch, and a batch should not be holding a stream's watermark.

The **tick must be finer than the idle timeout** — idleness is detected on the tick, so a coarser
one cannot notice until long after the fact — and it **is** validated at startup now (TIME-5). A
node with `tick: 5m` and `idle-after: 30s` used to start, log both values as in force, report `UP`,
recover its journal and then refuse every registration, because the rule lived in
`QueryExecution.generatingWatermarks` and therefore fired once per query. It is now
`PRV-2002 pravaha.watermark.tick ...` at startup, where one bad value costs one failure.

The tick has its own floor of **one millisecond**, refused rather than clamped (TIME-11).
`tick: 0s`, `PT0.0005S` and `-1s` were each accepted and each silently became a millisecond — a
zero tick is a thousand passes over every lane a second, for ever, on a daemon thread — while the
log line printed the value you asked for. Because nothing between that line and the clock can
change either number any more, `watermarks: idle-after=…, tick=…` is the **effective**
configuration by construction.

Watch `pravaha_query_watermark_lag_seconds`. Lag that climbs without bound means event time is not
keeping up with arrival, and every bound downstream is measured against event time — so a stuck
watermark shows up as growing memory, not as a stopped query.

**And watch the three beside it, which say *why*** (TIME-8). Lag alone cannot tell one quiet
partition holding everything back from a partition that keeps going quiet from a source whose time
runs backwards, and those need different responses:
`pravaha_query_watermark_partitions_idle` non-zero while lag climbs is the first;
`pravaha_query_watermark_idle_exclusions_total` rising steadily is the second;
`pravaha_query_watermark_regressions_total` non-zero at all is the third. `WatermarkTracker` has
answered all three since it was written and until now none of them reached any surface, so a
stalled query and a healthy one looked alike on a live node.

**Without this, state is unbounded.** Windows then close only when the input ends, joins never
evict, and views never forget. Correct over a file; fatal over a stream.

## Change data capture: the replication slot

A `postgres-cdc` source ([`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1) leaves state on the
**database** server: a logical replication slot. The slot retains write-ahead log from the last
position it was confirmed at, and **nothing else ever deletes that WAL**. A Pravaha node that stops
reading — dead, partitioned, or simply never checkpointing — does not fail; the database's disk
fills behind it, and a Pravaha outage becomes a PostgreSQL outage. Most of running this source is
watching that slot.

**Before the first registration.** `wal_level = logical` is read only at server start:

```sql
ALTER SYSTEM SET wal_level = logical;   -- then restart PostgreSQL; a reload changes nothing
SHOW max_replication_slots;             -- one slot per postgres-cdc registration, plus what else uses them
SHOW max_wal_senders;                   -- likewise, one connection per reader
ALTER TABLE public.customers REPLICA IDENTITY FULL;
ALTER SYSTEM SET max_slot_wal_keep_size = '50GB';  -- recommended: see below
```

The role needs the `REPLICATION` attribute, and to own the table if the plugin is to create the
publication (`create.publication`); otherwise create it yourself and set `create.publication: "false"`.
Each prerequisite that is missing is refused at open with the statement that fixes it (`PRV-5112`).

**Checkpointing is required, not optional.** The slot is confirmed only at positions a durable
checkpoint recorded — never at what was merely delivered, because a restore would need those changes
again. So a node without `pravaha.checkpoint.directory` never confirms anything, and the slot keeps
every byte of WAL written since it was created. The checkpoint interval (`pravaha.checkpoint.interval`,
one minute by default) is also roughly how far behind the slot's confirmed position trails the
reader.

**Watch the slot from the database, not only from Pravaha.** A node that is down cannot report its
own lag. On the database:

```sql
SELECT slot_name, active,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn))        AS retained,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS confirmed_lag,
       wal_status
FROM pg_replication_slots WHERE slot_name LIKE 'pravaha_%';
```

Alert on `retained` growing without bound, on `active = false` for longer than a restart takes, and
on `wal_status` leaving `reserved`. From the Pravaha side, the source's health reports the same
numbers — `slot 'pravaha_customers' active, retaining N bytes of WAL, confirmed position X/Y (M bytes
behind)` — and turns `DEGRADED` past `slot.lag.warn.bytes` (1 GiB by default), on `wal_status =
'unreserved'`, or while its reader is reconnecting; `UNHEALTHY` if the slot is gone or invalidated.

**The heartbeat.** The slot's position moves only when the reader sees something, and it sees only
the captured table. On a quiet table in a busy database — or a busy cluster, since WAL is shared by
every database in it — the slot stands still while WAL piles up behind it. Every `heartbeat.interval`
(10s) the reader writes a non-transactional `pg_logical_emit_message` into the WAL; it comes back
through the slot behind every transaction committed before it, the reader's position moves to it,
and the next checkpoint confirms it. The cost is one tiny WAL record per interval. Setting it to `0`
turns it off, and is right only for a table that is never quiet.

**Cap what a slot may hold.** `max_slot_wal_keep_size` (PostgreSQL 13 and later) invalidates a slot
that would retain more than the limit, which protects the disk at the price of the slot. An
invalidated slot (`wal_status = 'lost'`) cannot be resumed — the WAL it needed is gone — and the
source refuses it rather than resuming from wherever PostgreSQL now is. Choose the limit as "how long
a Pravaha outage may last" times "WAL written per hour".

**Dropping a slot nobody will read again.** A registration that is dropped for good, a node that is
decommissioned, a test environment torn down: the slot does not go with them. Drop it on the
database, after the reader has stopped (an active slot cannot be dropped):

```sql
SELECT pg_drop_replication_slot('pravaha_customers');
DROP PUBLICATION IF EXISTS pravaha_customers;   -- if nothing else uses it
```

A slot dropped under a running registration cannot be recovered from: the next restart refuses
(`PRV-5112`) and the changes since the last checkpoint are gone. The recovery for that — and for an
invalidated slot, and for a `TRUNCATE` of the captured table (`PRV-5116`) — is the same: stop the
registration, delete its checkpoint directory, drop the slot, and register it again. The view is
then rebuilt from the table's changes from that moment — and, with `snapshot.mode: initial`, from the
rows already in the table first; with `never` (the default) those rows are not replayed.

**An initial snapshot** (`snapshot.mode: initial`) adds three things to watch. It starts by creating
a **temporary** slot, `<slot>_snap_<random>`, which pins the snapshot to a point in the log and is
dropped as soon as the snapshot has been imported: `max_replication_slots` needs room for it, and
PostgreSQL creates it only once every transaction running at that moment has ended — a session left
idle in a transaction holds the start up, and past `start.timeout` the source is refused with
`PRV-5118`:

```sql
SELECT pid, usename, xact_start, state, left(query, 60)
FROM pg_stat_activity WHERE backend_xid IS NOT NULL OR backend_xmin IS NOT NULL ORDER BY xact_start;
```

While the table is read, a `REPEATABLE READ` transaction stays open on the database (application
name `pravaha-cdc <slot>`), holding back vacuum for the table's whole read, and the registration's
slot retains WAL from the snapshot's point until the last row is delivered and checkpointed: size
`max_slot_wal_keep_size` for the longest snapshot as well as the longest outage. Progress is in the
source's health — `initial snapshot in progress: N rows delivered of about M`. A restart part-way
through does not start over: the checkpoint records the last key delivered, and the rest is read
under a new snapshot, exactly once.

**A restore the slot has overtaken** is refused with `PRV-5115`. It happens when recovery falls back
to an older checkpoint than the one the slot was confirmed at — the newest being unreadable — or when
a slot was dropped and recreated under the same name. PostgreSQL would silently start after the
changes in between; the source says so instead. The recovery is the one above.

**Failover.** A replication slot lives on one server. After a promotion, whether the slot exists on
the new primary depends on the PostgreSQL version and on slot synchronisation being configured
(`sync_replication_slots`, PostgreSQL 17). Where it does not, the source refuses at the next open,
and the recovery is the one above.

**`drop.slot.on.close`** drops the slot whenever the source closes, a node shutdown included. It is
for tests and throwaway environments; in production it throws away the position every restart
resumes from.

## Kafka as a source

A `kafka` source ([`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1) leaves nothing on the
brokers: its position is each partition's next offset, and that lives in the query's checkpoint.
So there is no slot to drop and nothing to clean up after a registration is gone — and, the other
side of the same fact, **checkpointing is what makes it exactly once**. Without
`pravaha.checkpoint.*` a restart has no offsets to resume from and starts again from `start.from`.

**Lag.** The source's health reports each partition's lag in records — the brokers' end offset
minus what the engine has been handed — and goes `DEGRADED` past `lag.warn.records` (100 000),
`UNHEALTHY` when the brokers do not answer within two seconds or a reader has stopped. As with the
replication slot above, alert from the other side too: Kafka's own tooling sees the source only if
the binding sets `monitoring.group`: each durable checkpoint's offsets are then
committed to that group, and

```
kafka-consumer-groups --bootstrap-server kafka-1:9092 --describe --group pravaha-orders
```

shows where a restore would resume, one checkpoint interval behind the live position. The group is
never read back — resetting its offsets moves nothing — and no member ever joins it, so it shows as
`Empty` with offsets, which is correct.

**Retention is the outage budget.** A node down, or a query paused, for longer than the topic's
`retention.ms` comes back to a log that has deleted records its checkpoint never read. The source
refuses that restore with `PRV-5106` rather than silently starting from the log's new beginning.
Set retention comfortably beyond the longest outage you plan for; if it happens anyway, the records
are gone for every reader, and the recovery is to drop the checkpoint and re-register the query,
knowing the view starts without them. Compaction removes records too: on a compacted topic a
restore reads what compaction left, which for `format: json` is the latest value per key.

**Transactions.** `read_committed`, the default, never delivers an aborted transaction's records
and waits behind a transaction that is still open — so a producer that hangs mid-transaction holds
the source's position until `transaction.timeout.ms` aborts it, which shows here as lag.

**Threads.** One consumer and one fetch thread per partition per registration, since an exactly-once
source is never shared between queries: ten queries over a 12-partition topic are 120 consumers.
`buffer.records` (10 000 per partition) bounds what each holds decoded in memory.

## One engine, and what the server still lacks

A registered continuous query now runs on the engine proper: its own lane and thread, an off-heap
arena, backpressure, and watermarks when the registry is asked for them.

```java
new QueryRegistry(views, streams).generatingWatermarks();
```

Until this, the registry compiled a pipeline of its own and drove it on the caller's thread — so
everything the runtime offered belonged to the *other* path and the server ran this one. Registered
queries had no lane, no arena, no checkpointing and no watermarks.

**Rows are applied on the lane's thread.** `accept` copies into the inbox and returns, and returns
`false` when that inbox is full, which is backpressure rather than an error. Anything needing a row
reflected in the view before it reads waits with `awaitApplied`.

**One lane per query — but not one thread per query, since W9-4/W9-5.** `QueryRegistry` owns a
single `LaneRunner`: a fixed pool of platform threads, **one per core**, that drives every lane in
turn. Confinement is unchanged — a lane belongs to one runner thread from the moment it is hosted
until it is removed, and a runner steps its lanes sequentially — so the single-writer rules still
hold. What changed is the arithmetic: `NodeScaleTest` measures **200 queries adding 24 platform
threads**, 0.12 each, where the same workload cost 400 before this wave (a lane and a watermark
clock each). Ten times as many queries adds none.

By default a lane runs exactly *one* query's pipeline. `LaneMultiplexer` — which puts many
pipelines on one lane and so shares the inbox and arena as well as the thread — is turned on with
`pravaha.lane.multiplex.enabled` (an embedder: `QueryRegistry.multiplexingLanes(lanes, ceiling)`),
and the registry then places each registration on a shared lane under a per-lane ceiling (see
*Sharing lanes between queries*, W9-8). Keyed aggregates remain single-lane (ADR-034).

**The periodic work is one timer for the process.** `SharedClock` keeps time on a single daemon
thread and fires each query's watermark advance and each checkpoint on a *virtual* thread, so a slow
lane or a slow disk parks its own tick instead of stalling every other query's clock. Before this
each registration had two `newSingleThreadScheduledExecutor`s of its own.

**Sources feed it.** A binding under `pravaha.sources.<stream>` is opened per computation when the
first query naming that stream registers, and its rows go into the lane. A query whose streams have
no binding still registers and runs on rows an embedder or an SDK client pushes through `accept` —
and the node logs `no sources are bound, ...` at startup, because "zero rows" otherwise has two
causes that look identical.

**Sinks receive what a query names them for (W8-13, ADR-039 item 5, ADR-043).** A binding under
`pravaha.sinks.<name>` names a `StreamSinkPlugin` the same way `pravaha.sources.<stream>` names a
`StreamSourcePlugin` — `plugin:` and `options:`, resolved by `ServiceLoader` against the plugin's
own `name()`:

```yaml
pravaha:
  sinks:
    audit_trail:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/outgoing/audit_trail.csv
        schema: "id:INT64,user:STRING,amount:INT64"
```

Five plugins ship. `filesystem` appends delimited rows to a file and cannot take a retraction.
It keeps what the file already holds when it opens, a restart included, because the restored view
does not send again what it wrote before the checkpoint; `append: false` empties the file on every
open instead, and so throws that output away at each restart (HLP-2).
`aerospike-sink` upserts into a set by key and deletes on a retraction, so it takes a query that
revises its answer:

```yaml
pravaha:
  sinks:
    spend_by_user:
      plugin: aerospike-sink
      options:
        hosts: aerospike-1:3000
        namespace: analytics
        set: spend_by_user
        schema: "user_id:STRING,window_end:TIMESTAMP,total:INT64"
        key.bins: user_id,window_end   # must be the registration's --keys, as columns
        ttl.seconds: 86400             # optional; each record expires itself
```

A single key column becomes the record's key as itself; several become one blob key, and the key
columns are then also written as bins so a reader can see them. The connection takes the same
`tls.*` options as the Aerospike source ([`CONNECTOR_TLS.md`](CONNECTOR_TLS.md)).

`jdbc-sink` maintains the query's answer in a relational table — PostgreSQL, H2, or anything else
with a JDBC driver the deployment supplies — and is **transactional**, as `kafka-sink` below is:

```yaml
pravaha:
  sinks:
    spend_table:
      plugin: jdbc-sink
      options:
        url: "jdbc:postgresql://pg.internal:5432/analytics?sslmode=verify-full&sslrootcert=/etc/pravaha/tls/pg-ca.pem"
        user: pravaha
        password: ${PG_PASSWORD}
        table: spend_by_user              # or schema.table; you create it, the sink never does
        schema: "user_id:STRING,window_end:TIMESTAMP,total:INT64"
        key.columns: user_id,window_end   # must be the registration's --keys, as columns
        mode: upsert                      # default; `append` inserts every row and takes no retraction
        transactional: true               # default; false writes each batch straight to the table
        # transaction.id: spend_table     # default: this binding's name; one writer per id
        # staging.table: pravaha_sink_staging
        # dialect: auto                   # postgresql | h2 | portable, from the driver's product name
```

- **Upsert** is `INSERT ... ON CONFLICT (key) DO UPDATE` on PostgreSQL (the table needs a primary
  key or unique index on exactly `key.columns`, checked when the sink opens), `MERGE INTO ... KEY`
  on H2, and `UPDATE` then `INSERT` for the rows it did not find everywhere else. A retraction
  deletes the row its key names. Every statement is prepared, every identifier is found in the
  catalogue — so `orders` finds PostgreSQL's `orders` and H2's `ORDERS` — and quoted.
- **The table is checked against `schema` when the sink opens**, and a table or column that does
  not exist, a column whose type cannot hold the declared one (an `INTEGER` for `INT64`, a `REAL`
  for `FLOAT64`, a `NUMERIC(10,2)` for `DECIMAL(12,4)`), a nullable declaration over a `NOT NULL`
  column, or a `NOT NULL` column with no default that the schema does not write is refused with
  `PRV-5075`, naming the column. The shared `tls.*` options are refused (`PRV-5074`); TLS goes in
  `url`.
- **Exactly once, through a staging table.** A write goes to `staging.table` (created if missing:
  `sink_id`, `label`, `seq`, a byte-string `payload`), not to the target. Once the checkpoint that
  recorded it is durable, the commit applies everything staged for that checkpoint to the target
  table and deletes it from staging **in one database transaction** — so readers see a checkpoint's
  changes all at once, and a commit repeated after a crash finds nothing staged and does nothing. A
  restore deletes what was staged after the restored checkpoint, which the replay writes again. The
  cost: each row is written twice, and the table trails the view by up to one
  `pravaha.checkpoint.interval`. Not `PREPARE TRANSACTION`, which PostgreSQL disables by default
  (`max_prepared_transactions = 0`) and which would hold the target's row locks until each
  checkpoint is stored.
- **What that guarantee assumes:** one sink per `transaction.id` (two registrations naming one
  binding at once would share staging rows — give each its own binding), and nothing else writing
  the target's keys. Staged rows a failed or never-restored run left behind are never applied; they
  can be deleted by `sink_id`. With `transactional: false` an upsert sink is effectively once and
  an append sink at least once.
- **`PRV-5076`** is a write, staging, commit or abort the database refused; the delivery then
  detaches the sink with `PRV-8009`.

`kafka-sink` writes the query's changes to a Kafka topic, and is transactional too:

```yaml
pravaha:
  sinks:
    spend_topic:
      plugin: kafka-sink
      options:
        bootstrap.servers: "kafka-1.internal:9093,kafka-2.internal:9093"
        topic: spend-by-user              # you create it (cleanup.policy=compact for upsert); the sink never does
        schema: "user_id:STRING,window_end:TIMESTAMP,total:INT64"
        key.columns: user_id,window_end   # must be the registration's --keys; optional in changelog mode
        mode: upsert                      # default; `changelog` writes {"op","weight","row"} for every change
        transactional: true               # default; false sends each change straight to the topic
        # transactional.id: spend_topic   # default: this binding's name; one writer per id
        # staging.topic: pravaha-staging.spend_topic      # created if missing: 1 partition, delete policy
        # staging.retention.ms: 604800000                 # a week; must outlast the longest outage
        # commit.group: pravaha-sink.spend_topic          # where each commit's receipt is kept
        user: pravaha                     # SASL; sasl.mechanism PLAIN (default, TLS required) or SCRAM-SHA-256/512
        password: ${KAFKA_PASSWORD}
        tls.ca: /etc/pravaha/tls/kafka-ca.pem
        # kafka.linger.ms: 20             # any other Kafka client property, with its kafka. prefix
```

- **The record.** Upsert mode: the key is the key columns as a JSON object (`{"user_id":"u1",
  "window_end":"2026-09-19T14:00:00Z"}` — a `TIMESTAMP` is written as an ISO-8601 instant in UTC), the value the whole row by column name, and a retraction is a
  **tombstone** — the key with a null value — so a compacted topic holds the query's answer, one
  record per key, and Kafka Streams, ksqlDB or a Connect sink read it as a table. Changelog mode:
  the value is `{"op":"insert"|"delete","weight":n,"row":{...}}`, keyed by `key.columns` or, without
  them, by the whole row, so a row's insert and its retraction share a partition and stay in order.
  JSON only; `DECIMAL` is a JSON number with its exact digits, `BYTES` base64, dates and times ISO-8601
  in UTC.
- **Exactly once, through a staging topic** — to a consumer reading with
  `isolation.level=read_committed`. A write goes to `staging.topic`, not the target. Once the
  checkpoint that recorded it is durable, the commit reads that checkpoint's staged changes back and
  writes them to the target in **one Kafka transaction** under `transactional.id`, together with an
  offset for `commit.group` that marks them done — so a `read_committed` reader sees a checkpoint's
  changes all at once or not at all, and a commit repeated after a crash finds the mark and writes
  nothing. Opening the sink fences any earlier producer with the same `transactional.id` and aborts
  a commit it died inside. Not a Kafka transaction held open until the checkpoint: Kafka has no
  prepare, and a restarted producer aborts what its predecessor left open, which would lose the
  changes of a checkpoint that was durable when the process died. The cost: each change is written
  twice, and the topic trails the view by up to one `pravaha.checkpoint.interval`. A
  `read_uncommitted` consumer can see a commit that was aborted by a crash and redone: at least once
  for it.
- **What that guarantee assumes:** one sink per `transactional.id` — a second one fences the first,
  which is detached with `PRV-5102` and `PRV-8009`, so give each registration its own binding — and
  staged changes that outlive the gap between a checkpoint and its commit, restarts included
  (`staging.retention.ms`). A commit whose staged changes retention has deleted is refused with
  `PRV-5103` rather than skipped. The principal needs write on both topics, read on the staging
  topic, the `transactional.id`, and read on `commit.group`. With `transactional: false` an upsert
  sink is effectively once on a compacted topic and a changelog sink at least once.
- **Checked before a row moves:** `kafka.*` properties that would weaken the guarantee
  (`enable.idempotence=false`, `acks` other than `all`) or that the sink owns (`transactional.id`,
  serializers, `security.protocol`, `ssl.*`) are refused with `PRV-5100`, as is a misspelled one.
  The target topic must exist (`PRV-5101`); an upsert sink on a topic that is not compacted is
  logged, not refused. TLS is the shared `tls.*` options ([`CONNECTOR_TLS.md`](CONNECTOR_TLS.md)).
- **Compression:** `none` (the default) and `gzip`. The lz4, snappy and zstd codecs are native code
  and not shipped; asking for one is refused with `PRV-5100` unless its library is on the plugin's
  classpath.

`delta-sink` maintains the query's answer in a **Delta Lake table**, on Delta Kernel and not Spark,
and is transactional as the two above are:

```yaml
pravaha:
  sinks:
    spend_lake:
      plugin: delta-sink
      options:
        path: /warehouse/spend_by_user     # the table root; created with this schema if it is not there
        schema: "user_id:STRING,window_end:TIMESTAMP,total:INT64"
        key.columns: user_id,window_end    # must be the registration's --keys; refused in changelog mode
        mode: upsert                       # default; `changelog` appends every change with _op and _weight
        transactional: true                # default; false commits each batch on its own
        # transaction.id: spend_lake       # default: this binding's name; one writer per id
        # staging.dir: /warehouse/spend_by_user/_pravaha_sink   # default; inside the table, so it travels with it
        # create: "false"                  # refuse to write unless the table already exists
```

- **Upsert** keeps the table equal to the view: a row is written or replaced by `key.columns`, and a
  retraction removes the record its key names. A Delta table has no delete, so removing a row means
  **rewriting the file that holds it** — the sink reads the data files through their key columns
  alone to find the ones holding an affected key, rewrites those without the rows, and removes the
  old files in the same commit. Files holding no affected key are untouched. **A commit therefore
  costs in proportion to the table, not to the number of changes.** For a high-volume query bind
  `mode: changelog`, which only appends: every change as a row plus `_op` (`insert` or `delete`) and
  `_weight`, for a reader that folds them itself.
- **Exactly once, through a staging directory.** A Delta commit is visible the instant its log entry
  lands, so there is no prepared state to hold a checkpoint's changes in. They are written instead as
  files under `staging.dir/<transaction.id>/<label>/` — invisible to every reader of the table — and
  the commit applies the whole label as **one Delta commit** once the checkpoint recording it is
  durable. Each commit carries a Delta `txn` action (`transaction.id`, the label), which is what
  makes a commit repeated after a crash a no-op: the table itself records that the label is done.
  The staging directory begins with an underscore, which is what Delta's `VACUUM` skips.
- **Small files are the cost, and compaction is Delta's.** One commit per checkpoint, each writing at
  least one Parquet file plus one for every file it rewrote. Run Delta's `OPTIMIZE` (and `VACUUM`
  for the files the rewrites leave behind) from an engine that has them — Spark or `delta-rs`. This
  sink has neither: Delta Kernel exposes no compaction API, and a sink that pretended to one would
  be doing a table maintenance job without the table's owner asking. Plan for it the way you plan it
  for any streaming writer into a lakehouse.
- **What the guarantee assumes: no other writer of the table at all.** A writer that finishes before
  a commit begins is merged onto, and where it wrote a key this sink holds, this sink wins. A writer
  that commits *while* a commit is being built makes it fail with `PRV-5059` — deliberately not
  retried, because replaying this commit's file removals over the other writer's version would undo
  their change. The sink is then detached (`PRV-8009`) with the checkpoint's changes still staged.
- **Checked before a row moves:** a partitioned table, a table whose columns are not the binding's
  (by position, name and type), and `create: false` with no table are each refused with `PRV-5057`,
  naming the column. A `TIME` column is refused with `PRV-5051`, because Delta has no time-of-day
  type. At write time, a null key column and a `TIMESTAMP` that is not a whole number of
  microseconds are refused with `PRV-5058` — Delta stores microseconds and the engine nanoseconds,
  and rounding would put a value in the table that reads as true and is not. `PRV-5056` is a binding
  that cannot be honoured as written.

A registration names the sink, not the configuration: `pravaha register --name big_txn --sql-file
q.sql --sink audit_trail`, or the `sink` argument of either SDK's `register`. The query's view is
maintained exactly as before, and every commit of it is also written to the sink.

What an operator should know about that delivery:

- **The query must produce the sink's row shape.** The sink reads each row through its binding's
  `schema`, so a `SELECT` list in another order, or with another name or type, is refused with
  `PRV-8010`, as is a keyed sink whose `key.bins` (or `key.columns`) are not the registration's key
  columns. Refused
  before the sink is opened, because the alternative is plausible nonsense and no error.
- **A bad pair is refused before anything runs.** A query that revises its answer — a running
  aggregate, a window with allowed lateness, anything over a stream whose source emits deletes
  (`postgres-cdc`), a join over one included — pointed at a sink that can only append is refused
  with `PRV-2041` at registration, before the sink is opened. The node asks each bound source
  whether it emits deletes, from its configuration, without connecting. A `filesystem` sink is
  append-only, so it takes filters, projections, joins and tumbling windows without lateness over
  append-only sources, and nothing that retracts.
- **Rows arrive per commit**, retractions included as rows with a negative weight, never as half a
  window. The feed commits on its own timer, so a sink trails the source by about one commit.
- **The guarantee depends on the sink, and is logged at registration** as `query 'q' writes to sink
  's', <guarantee>: <why>` (INFO, logger `QueryRegistry`). A restart resumes from the last checkpoint
  and replays what came after it; what that does to the sink is:

  | Sink declares | Guarantee | Why |
  |---|---|---|
  | `transactional` (`jdbc-sink`, `kafka-sink`, `delta-sink`), and `pravaha.checkpoint.directory` is set | **exactly once** (for `kafka-sink`, to a `read_committed` consumer) | Writes between checkpoints go into a transaction, prepared at each checkpoint's cut and recorded in the checkpoint, committed once the checkpoint is durable. A restore commits what the checkpoint recorded and has the sink abandon the rest, which the replay writes again |
  | `transactional`, no checkpoint directory | at least once; effectively once when it also upserts (`jdbc-sink`, `kafka-sink` and `delta-sink` in their default `upsert` mode) | Nothing to tie a transaction to, so each commit is its own, and a restart repeats it; an upsert repeated rewrites the values already there |
  | `idempotentUpsert` (`aerospike-sink`) | effectively once | The replay rewrites records with the values they already hold |
  | neither (`filesystem`) | at least once | Expect duplicates in the file after a restart: the rows written after the last checkpoint are written again below them. A view commit carries no sequence a replay would repeat, so there is nothing to deduplicate on |

  `GET /api/v1/sinks` reports the same decision for each bound sink, as `EXACTLY_ONCE`,
  `EFFECTIVELY_ONCE` or `AT_LEAST_ONCE` for this node; until HLP-4 it showed the plugin's own
  claim, which calls an idempotent upsert exactly once and cannot know whether the node checkpoints,
  so `aerospike-sink` and an uncheckpointed `jdbc-sink` were both listed `EXACTLY_ONCE`.
  `jdbc-sink`, `kafka-sink` and `delta-sink` are transactional by default (`transactional: false`
  makes them idempotent upsert, or a plain append or changelog); `aerospike-sink` and `filesystem` are not. The source caps it too: one that cannot
  rewind to a checkpoint's offsets is at least once end to end.
- **A sink added to a running computation** (a second name for the same query) is first sent the
  view's whole contents — at the query's next change or checkpoint, not at once — inside its first
  transaction when it is transactional. After a restart, a second name whose sink the restored
  checkpoint recorded is sent only what changed since that checkpoint.
- **A drop commits; a shutdown does not.** Dropping a registration commits what its transactional
  sink was written, since nothing will replay it. Stopping the node leaves the tail since the last
  checkpoint uncommitted, as a crash would, and the restart writes it again — so a node that is
  stopped and never restarted leaves that tail out of the sink.
- **A sink that fails is detached, not retried.** The first refused batch stops that sink with
  `PRV-8009`, logged at `ERROR`; the query, its view and its subscribers carry on. Writing later
  batches past a lost one would leave the sink missing a change with nothing to say so. Drop and
  re-register the query to start the sink again from the view's contents — which, for any kind of
  sink, repeats what it already held: the guarantee above ends at `PRV-8009`.
- **The journal records the sink.** A restart re-attaches it — which is why sinks are bound before
  the registry recovers — and a journalled registration whose sink is no longer bound is refused by
  name in the recovery report rather than recovered writing to nothing.
- **Delivery runs on the query's commit.** A slow sink slows the query that feeds it and nothing
  else. A checkpoint also commits the view on the query's lane and prepares each transactional sink
  there, so a sink slow to prepare lengthens that query's checkpoint pause (and, on a shared lane,
  its neighbours'); `pravaha.checkpoint.timeout` bounds it.

## Starting a node

`pravaha-server` is the process. It brings up three things beyond the engine, in this order:

1. **The cluster coordinator**, first — whether this node may own partitions at all is a question to
   settle before it does any work, and the answer can be "no, refuse to start" (`PRV-9002`).
2. **The registry**, recovered from its journal *before anything can reach it*. A client that
   connected during recovery and was told "no such view" would re-register, and a duplicate
   registration of a query that was about to come back is a second computation of the same thing.
3. **Flight SQL**, last. Accepting connections is the final step, because a connection accepted
   before the views exist gets a wrong answer rather than no answer.

Shutdown reverses it: stop accepting, let go of the queries, then leave the cluster. A node that left
the cluster first would have its partitions reassigned while it was still serving them.

```yaml
pravaha:
  node:
    id: pravaha-node-01
  flight:
    enabled: true       # the wire protocol the SDKs and CLI speak; HTTP above is for operators
    host: 0.0.0.0
    port: 9090         # the shipped default, and what the CLI, both SDKs and the console assume
```

A node with no `registry.journal` starts and **says so** — a development run does not need
durability, but the cost of finding out at the next restart is every client's registrations.

**`port: 0` is supported and is reportable.** The operating system picks the port, and
`GET /api/v1/status` carries it in a `flight` field — `"flight":"127.0.0.1:44131"` — as does the
plain HTML page at `/status` and the startup log line. That is the only place the bound port is
readable from outside the process: `/actuator/health`'s components are suppressed by the shipped
`show-details: when-authorized` on a node with `authentication: none` (CFG-2). An IPv6 host is
written bracketed there, `[::1]:9090`, so the string can be pasted into a client.

**Two values in that block are refused rather than reinterpreted.** A port outside 0–65535 fails
with `PRV-3010` naming `pravaha.flight.port`, instead of gRPC's own `IllegalArgumentException: port
out of range` with no key and no code. And `host: 127` — legal input to `InetAddress`, meaning
`0.0.0.127` — is refused naming what it would have been read as. Write the address in full.

**What an operator can read back about the node itself.** The startup line for the cluster now names
the member this node joined as, `cluster mode SINGLE on single (consensus), self-contained, this
node pravaha-node-01@10.0.0.7:9090 (pravaha.node.id=pravaha-node-01)` — `pravaha.node.id` decides
which checkpoint directory and which registry journal this node may claim, and it used to reach one
served field and no log line at all (CFG-1). It is also a `node` tag on every metric, and
`GET /actuator/info` reports the name, the version and the id.

A node also logs, once, the off-heap implementation it chose: `off-heap access: bytebuffer
(-Dpravaha.memory, -Dpravaha.ffm=false)`. See [JVM flags](#jvm-flags).

## Watching a running node

Prometheus metrics are at `/actuator/prometheus`. **Every series this node publishes carries two
common tags** — `application`, from `spring.application.name`, and `node`, from `pravaha.node.id`
(CFG-19). Without them a fleet scraped into one Prometheus had nothing distinguishing Pravaha's own
series from any other application's, and nothing saying which node a number came from; the only
label was `query`. `GET /actuator/info` reports the same name, the version and the node id, and
answered `{}` until it did.

Per continuous query:

| Metric | Question it answers |
|---|---|
| `pravaha_query_running{query=}` | Is it alive — 1 running, 0 terminal |
| `pravaha_query_feed_stopped{query=}` | 1 while a source of the query has stopped mid-read. **Alert on it**: the query stays `RUNNING`, so the line above says 1, and its view has stopped moving (FEED-1) |
| `pravaha_query_feed_failures_total{query=}` | Distinct failures that stopped a source of the query — one failure on a feed thread reading four partitions counts once |
| `pravaha_query_rows_in{query=}` | Is anything arriving |
| `pravaha_query_state_held{query=}` | Accumulators and join rows the query holds **now** |
| `pravaha_query_state_ceiling{query=}` | What those are refused at. Zero means the plan has no bounded state at all, which is not the same as empty |
| `pravaha_query_state_fraction{query=}` | The ratio, 0 to 1. **The one to alert on** — `PRV-4001` used to be the first anybody heard of a query's state, and the query at nine tenths could not be seen at all (ADR-037 B1) |
| `pravaha_query_spill_bytes{query=}` | Bytes of overflow slab the query's state holds on disk now (ADR-044). Zero until it spills |
| `pravaha_query_spill_live_bytes{query=}` | How much of that is live state |
| `pravaha_query_spill_fragmentation{query=}` | `1 - live / held`, 0 to 1. Staying high while `_compactions` is flat means the threshold is set above what this query's churn reaches |
| `pravaha_query_spill_compactions{query=}` | Compaction passes that emptied at least one slab |
| `pravaha_query_spill_slabs_released{query=}` | Overflow slabs (files) compaction gave back |
| `pravaha_query_backpressure_waits_total{query=}` | Episodes in which one of this query's writers found nowhere to put a row. A count of **episodes**, not of rows or polls: a source held off for an hour is one |
| `pravaha_query_backpressure_wait_seconds_total{query=}` | How long those episodes lasted altogether, counting one still in progress. `rate()` of it against wall clock is the share of time this query could not be fed |
| `pravaha_query_backpressure_blocked_fraction{query=}` | The same share as the *lanes* see it, 0 to 1, counting every writer into those lanes. **The one to alert on** for "is this query the limit": near 1 means it is. On a shared lane it counts the neighbours' writers too, which is how a query blocked *by* a neighbour is told from one blocking itself — that one reads high here and low on the two rows above |
| `pravaha_query_inbox_depth{query=}` | Rows queued into the lane and not yet taken, right now. Sampled by whoever scrapes: it is an instantaneous gauge, so a burst between two scrapes is invisible |
| `pravaha_query_inbox_cells{query=}` | What that depth is out of, so the depth can be read as a fraction without knowing `pravaha.lane.inbox.cells` |
| `pravaha_query_view_size{query=}` | How many keys the view holds |
| `pravaha_query_view_evicted{query=}` | What retention has removed. **Flat at zero on a long-running query** means either nothing is old enough yet or retention is longer than anyone intended |
| `pravaha_query_view_updates` | Corrections applied |
| `pravaha_query_view_removals` | Retractions applied |
| `pravaha_query_watermark_lag_seconds{query=}` | How far behind **event time** it is |
| `pravaha_query_watermark_partitions{query=}` | Input partitions contributing to its watermark; 0 means it derives none |
| `pravaha_query_watermark_partitions_idle{query=}` | How many are excluded right now for having gone quiet |
| `pravaha_query_watermark_idle_exclusions_total{query=}` | How often one has been excluded since it started |
| `pravaha_query_watermark_regressions_total{query=}` | How often one reported a watermark below the lane's -- a source-side fault |
| `pravaha_query_subscribers{query=}` | How many subscribers are attached to the computation. A sink writing the query's changelog is **not** counted — it listens on the same commit and nobody is watching it. Two names on one computation report the same number, because they are one |
| `pravaha_query_checkpoint_last_success_timestamp_seconds{query=}` | When the query last **stored** a checkpoint, as Unix seconds. Alert on its age (`time() - ...`): that is how much recovery would now replay. `NaN` while the query is not checkpointing or has not stored one yet — never zero, which would read as 1970 |
| `pravaha_query_checkpoint_duration_seconds{query=}` | How long that last stored checkpoint took, snapshot to stored. `NaN` as above |
| `pravaha_query_checkpoint_failures_total{query=}` | Checkpoints that did not happen. Rising while the last-success age rises is a query whose recovery story is getting older by the minute |
| `pravaha_query_replacement_state{query=}` | Whether this name is being replaced, and where that has got to: 0 none, 1 backfilling, 2 caught up, 3 cut over and retaining the version it replaced, 4 rolled back, 5 abandoned, 6 failed, 7 finished (ADR-046). An ordinal rather than a tag per state, so a panel following one query keeps its history across the transitions |
| `pravaha_query_backfill_history_rows{query=}` | Records of history the backfill has read. Flat while it should be rising is a backfill that is paused, throttled to nothing, or blocked on the store |
| `pravaha_query_backfill_rows_per_second{query=}` | What it is reading at, over the last sample. **This is the number to plot beside the store's own p99 latency**: the impact you are causing, not just the progress you are making |
| `pravaha_query_backfill_rate_limit{query=}` | The ceiling in records a second, or 0 for none |
| `pravaha_query_backfill_partitions{query=}` | How many partitions the backfill has |
| `pravaha_query_backfill_partitions_live{query=}` | How many of them have reached the live stream. Equal to `..._partitions` means the seam is behind all of them and a cutover may be asked for |
| `pravaha_query_backfill_paused{query=}` | 1 while an operator has paused it |
| `pravaha_query_backfill_lag_seconds{query=}` | How far behind the running version the candidate's **event time** is. Falling towards zero is a replacement approaching its cutover |
| `pravaha_query_commit_latency_seconds_count{query=}`, `..._sum` | Commits that changed the view, and the total time they took: from applying the changes to the last subscriber **and sink** having them (a slow sink is on this thread, so it is in this number). The mean over a window is `rate(_sum) / rate(_count)`, exactly. **No percentiles are published**: the engine keeps a count and a total, not each commit's duration, and a p99 it did not measure would be invented. Idle commits — a watermark tick with nothing in it — are not timed |

Per-operator rows, state, watermark and a sampled self time are published **on the plan**, not as
meters: `GET /api/v1/queries/{name}/plan` carries them per node. See *Diagnosing backpressure*
below. They are off unless `pravaha.metrics.operators` is set, and
`pravaha_metrics_operators_enabled` says which it is on this node, so a dashboard that finds none
can say "switched off" rather than drawing zeros.

Still not published: latency percentiles. The engine keeps a count and a total per query, not each
commit's duration, so the mean is exact and a p99 would be invented.

And per node, for lane sharing (`pravaha.lane.multiplex.*`) and the spill tier:

| Metric | Question it answers |
|---|---|
| `pravaha_lane_shared_queries{lane=}` | How many queries each shared lane carries, against `max-queries-per-lane`. Absent with sharing off |
| `pravaha_lane_blocked_fraction{lane=}` | The share of time a writer into that shared lane had no room, 0 to 1. Per lane, not per query, because the lane is the thing being waited on: every query hosted on it queues behind one inbox. Absent with sharing off |
| `pravaha_lane_inbox_depth{lane=}` | Cells published into that shared lane and not yet drained. Absent with sharing off |
| `pravaha_lane_own_queries` | How many queries hold a lane — and an inbox — of their own. All of them with sharing off; with it on, the ones no shared lane would take |
| `pravaha_lane_shared_bytes` | Off-heap the shared lanes hold between them — inboxes and arenas, counted once however many queries they carry. Zero with sharing off |
| `pravaha_metrics_operators_enabled` | 1 when `pravaha.metrics.operators` is on, so per-operator numbers exist on this node's plans |
| `pravaha_state_spill_bytes_mapped` | Overflow slab mapped on the node, across every query — what `pravaha.state.spill.max-bytes` counts. Alert well before it reaches the quota: at the quota the next query to need a slab stops with `PRV-4005` |
| `pravaha_debug_sessions_open` | Debug sessions open on this node, against `pravaha.debug.sessions.max`. Each holds a whole second copy of a query's lanes, arena and operator state, so a forgotten one is a query running twice. Published as zero when nobody is debugging, which is what makes an alert on it possible |

`state_held` and `state_ceiling` are counted in the units the ceiling is expressed in —
accumulators for a windowed aggregate, rows for a join — **not in bytes**. They are what
`PRV-4001 STATE_TOO_LARGE` compares, so a query at `state_fraction` 0.9 is the one worth acting on
before it is refused.

Lag is event-time lag, not processing latency: a query can be fast and still far behind, because
this measures the data rather than the engine. A query that has never seen a row reports `NaN`, not
zero — zero would show it as perfectly up to date.

The eight replacement gauges are published for **every** query, not only the ones being replaced,
and read zero while nothing is happening. A gauge that appears when an operation starts is a gauge
nothing was alerting on when it did, and a dashboard panel has to exist before the cutover it is
watching.

Meters are removed when a query is dropped. That matters more than it sounds: a gauge registered per
query and never removed leaks the meter *and* the query state its reference keeps alive, and nothing
in Micrometer would complain.

### Diagnosing backpressure

A query that is "slow" is one of three things, and until B6 the engine could not tell them apart:
its source has stopped (above), it is keeping up and the data is late (`watermark_lag_seconds`), or
**it cannot be fed as fast as rows arrive**. This is the third.

**Is it backpressured at all.** `pravaha_query_backpressure_blocked_fraction` is the share of wall
clock a writer into this query's lanes spent with nowhere to put a row. Near zero is headroom; over
about 0.2 is worth looking at; near 1 means the lane is the limit and everything upstream is
waiting on it. `pravaha_query_inbox_depth` against `pravaha_query_inbox_cells` says how full the
queue is right now, which is what tells a lane that is *saturated* from one that is *bursty*.

**Whose fault is it.** On a lane a query owns, the query's own `backpressure_waits` and
`backpressure_wait_seconds` and the lane's `blocked_fraction` say the same thing. On a **shared**
lane (`pravaha.lane.multiplex.*`) they come apart, and the difference is the diagnosis:

| `backpressure_wait_seconds` | `blocked_fraction` | What it means |
|---|---|---|
| high | high | This query's own writer is waiting, and so is the lane. It is the one to look at |
| low | high | The lane is full and somebody else's writer is waiting on it. Look at `pravaha_lane_blocked_fraction{lane=}` and at the other queries on that lane (`pravaha_lane_shared_queries{lane=}`) |
| high | low | The source is being paused by the hysteresis rather than by a full inbox: the fill reached `pravaha.lane.backpressure.high-watermark` (0.8 by default) and the source stays paused until it falls to `.low-watermark` (0.5). Widen the gap if the pause itself is what costs — an Aerospike scan throttle is expensive to re-establish, a Kafka consumer pause is nearly free — or raise `pravaha.lane.inbox.cells` so the burst fits |

**Which operator.** With `pravaha.metrics.operators` on, `GET /api/v1/queries/{name}/plan` carries
a block per plan node, keyed by the same node ids the graph's `nodes` use:

```json
{ "nodes": [...], "edges": [...],
  "operatorMetrics": {
    "n0": {"rowsIn": 4213, "rowsOut": 4213, "stateBytes": null,  "watermark": "...", "selfNanos": 81234,  "sampledRows": 4, "selfTimeShare": 0.07},
    "n1": {"rowsIn": 4213, "rowsOut": 4213, "stateBytes": 8388608, "watermark": "...", "selfNanos": 990123, "sampledRows": 4, "selfTimeShare": 0.86},
    "n2": {"rowsIn": 4213, "rowsOut": 4213, "stateBytes": null,  "watermark": "...", "selfNanos": 80011,  "sampledRows": 4, "selfTimeShare": 0.07}
  },
  "bottleneck": "n1",
  "metricsNote": "..." }
```

`bottleneck` is the node most of the query's own time went into. It is **measured, not inferred
from row counts**: a filter that drops 99 % of its input is not the bottleneck for dropping them,
and an engine that guessed from selectivity would say it was.

`operatorMetrics` is `null` when nothing was counting, and `metricsNote` says which nothing it was
— SQL that is not registered has nothing running; a node with `pravaha.metrics.operators` off has
counters that were never built. Those are different answers and the API does not blur them into
zeros. A caller entitled only to a row-filtered slice of the view gets `null` too: rows past a
filter it may not see is still a count of rows it may not see.

#### How exact these numbers are

Rows in, rows out and state bytes are **counts**, exact to the last batch boundary — the lane
publishes them once per batch, so a reader never sees half of one batch's work.

The watermark is the query's, repeated on every node. An advance reaches every operator of a plan
in one call on the lane thread, so the nodes cannot hold different watermarks, and the field is
per node only so a console can draw it beside the operator somebody is looking at.

Blocked time is measured in **episodes**, not per row. A writer opens one the first time it finds
no room and closes it the first time it finds room again, so the two clock reads are paid once per
stall rather than once per row. Its error has three named parts:

- A pump notices the inbox only when it polls, so an episode's start and its end are each rounded
  to the poll that found them. A stall shorter than the gap between two polls is not seen at all,
  and one that is seen is right to within one poll interval at each end.
- Several writers can be stalled at once — a join has one pump per side — so their episodes overlap
  and their times add. `blocked_fraction` is clamped at 1; the raw sum in
  `backpressure_wait_seconds` can exceed the elapsed time on a query with more than one input.
- Inbox depth is instantaneous. Whoever scrapes is the one sampling it, so a burst between two
  scrapes leaves no trace. `blocked_fraction` is the number that integrates over the gap.

Self time is **sampled**, and the sample is regular rather than random: one row in every 1,024 that
enters the pipeline is timed at every operator on its path, children subtracted, so the figure is
that operator's own work and not its subtree's. `sampledRows` is published beside it, so a share
computed from four samples can be recognised as one. Two consequences worth stating: a workload
whose cost beats in step with a period of 1,024 rows would be measured wrong, and an operator that
only runs on a watermark tick — a continuous aggregate's emission — is never on a sampled row's
path and reports no self time at all.

#### What it costs, and why it is off

Measured on the reference machine (an AMD Ryzen AI 9 HX 370 laptop, 12 cores / 24 threads, 61 GiB
RAM — the owner's decision of 2026-09-19 that the development machine is the reference), with
`OperatorMetricsOverheadIT`: a three-operator plan, 20 million rows a pass, the arms interleaved,
best of five passes after three warm-ups.

**On an idle machine, three runs:**

| `pravaha.metrics.operators` | Rows a second |
|---|---|
| off | 11.4 – 12.6 M |
| on | 10.5 – 11.6 M |

**Cost: 7.9 %, 8.2 %, 8.6 % — call it 8 %.**

**This measurement needs an idle machine, and says so.** Repeated while another build was running
(load 8 to 14 of 24 threads) the same harness gave 6.1 %, 13.8 %, 14.1 % and 17.1 % — an eleven-point
spread that is the other build, not the engine. The harness prints the load average beside its
result for exactly this reason; a figure taken above about half the processor count is partly a
measurement of whatever else was running. Re-measure on a quiet machine before quoting a new
number, and prefer the spread to a point estimate when you cannot get one.

Two footnotes on the idle figures, so they are not read as more precise than they are. They were
taken before the harness interleaved its arms, when `off` always ran first; on a quiet machine the
bias is small, and it penalises `off`, so if anything the true cost is at or above 8 %. And the
plan is deliberately the worst case — the narrowest plan and tightest loop the engine has, with no
lane, no inbox and no view commit beside it — so the wrappers are the largest share of a total they
will ever be.

Every measurement taken, idle or loaded, is well above "a few percent". So the detail is
**switchable and off by default**:

```yaml
pravaha:
  metrics:
    operators: true
```

Backpressure measurement is *not* behind the switch and is always on: it costs one branch per poll,
which is per batch of up to a few hundred rows.

The switch is read when a query compiles its stages, so turning it on does not give counters to a
query that is already running. Set it, restart the node or re-register the query, and read the plan
again. `pravaha_metrics_operators_enabled` says which state a node is in.

Reproduce the measurement:

```
./mvnw -o -pl pravaha-runtime -am test -Dtest=OperatorMetricsOverheadIT \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

### A source that stopped

A source that fails mid-read — a file deleted or truncated, a credential revoked, a topic deleted, a
line it cannot decode with no dead-letter queue configured — stops its feed and is **not retried**:
retrying a reason like that produces a log line a millisecond and no progress. The query stays
`RUNNING`, its view answers at the frontier it reached, and nothing about its state says it has
stopped moving. What does:

| Where | What it shows |
|---|---|
| The node's log | One `ERROR` line when it happens: the query, `stream#partition`, the code, the message |
| `GET /api/v1/queries/{name}` | `feed.state` `STOPPED`; per partition `state`, `failure` (code, message, help URL), `stoppedAt`, whether its reader is `shared` and whether this partition's own read raised it (`origin`). A row-filtered caller gets the code and not the text |
| `pravaha queries` | `RUNNING (source stopped)` in the state cell, and a line with the code, `stream#partition`, the time and the message. A detached sink is the same shape in the `SINK` column: `<name> (detached)` and a line with `PRV-8009`. `--verbose` adds a `FEED` column for every query |
| Both SDKs | `feed()` / `feedStop()` in Java, `feed` / `feed_stop` in Python, on `queries()` |
| `pravaha_query_feed_stopped{query=}` | 1 |
| `/status`, `/api/v1/status` | `stoppedFeeds`: how many queries, not which |
| `/actuator/health` | The `engine` indicator is `DEGRADED` (not `DOWN` — every view is still served) with `stoppedFeeds` and the first stop's code and place; `DEGRADED` is ordered between `OUT_OF_SERVICE` and `UP`, so the probe answers 200 |
| The console | The query page's **Source** row, a mark in the lists, a critical finding on Operations with the code linked to its help |

The code is the source's own when it has one — `PRV-5040` for a file it could not decode, `PRV-5107`
for a Kafka read — and `PRV-5092` when the feed stopped for a
reason that was not the source's (an uncoded exception, a commit that threw). A reader shared by
several queries (SRC-3) stops all of them at once, and each reports the same stop with `shared`.
A binding's option values are struck out of the message before it is recorded, as a sink's are.

To recover: fix the cause, then drop the query and register it again, or restart the node — either
opens a new feed, which resumes from the last checkpoint if the query checkpoints. For a source whose
records sometimes cannot be decoded, set `pravaha.dlq.directory` and the bad records go there while
the rest keep flowing — and can then be read and replayed (*Running with a dead-letter queue*,
above).

## Replacing a running query: the operator's side

A blue/green replacement (ADR-046, design §16.3) changes the query behind a name without taking the
answer away from anybody reading it. What an operator does, and what to watch while it happens.

**Start it.** Any of these; they are the same operation.

```bash
pravaha replace --name spend --sql-file v2.sql --keys 0 \
    --backfill history --rate-limit 5000 --cutover manual
```

```sql
CREATE OR REPLACE CONTINUOUS QUERY spend KEYED BY (user_id)
    WITH (backfill = 'history', backfill.rate.limit = 5000) AS SELECT ...
```

```bash
curl -XPOST $NODE/api/v1/queries/spend/replacement \
  -d '{"sql":"SELECT ...","backfill":"history","rateLimit":5000}'
```

The name goes on answering the version it answers now. What has started is a shadow: its own state,
its own checkpoints, its own readers, unreachable by any reader.

**Set the rate before you start, and lower it while it runs.** `backfill.rate.limit` is a ceiling —
`pravaha throttle --name spend --rate 500` may lower it and may not raise it above the number the
replacement was started with, so a rate somebody chose during an incident cannot be undone by
somebody else's typo. `pravaha pause-backfill` and `resume-backfill` stop and start it without
giving up what it has read. **A backfill competes with production traffic on the same storage**:
plot `pravaha_query_backfill_rows_per_second` next to the store's own p99 latency, and if the store
suffers, lower the rate. Nothing here does that for you — `backfill.adaptive` is refused by name
(`PRV-4018`) rather than pretended, because nothing probes the store's latency.

**Watch it.** `pravaha replacements`, or `GET /api/v1/queries/spend/backfill`. What to look for:

- `history_rows` rising. Flat is paused, throttled to nothing, or a store that is not answering.
- `partitions_live` reaching `partitions`. That, and only that, is "caught up": every partition has
  read its history and is on the live stream. Rows read and time elapsed are proxies that are wrong
  exactly when the input rate changes.
- `lag_nanos` falling. How far behind the running version the candidate's event time is.

**Cut over.** `pravaha cutover --name spend`. It happens only when the two versions have consumed
*exactly* the same input, compared position by position with both feeds stopped; if they cannot be
brought together inside thirty seconds the cutover is **refused** with `PRV-4014` and nothing has
changed. Try again, or quieten the source. `cutover = 'auto'` does it for you as soon as the
candidate is caught up.

What changes at that instant: readers of the name get the new version's view — no gap, nothing
counted twice, and no read that mixes the two. Every subscription ends with `PRV-4019` and the
clients subscribe again. A sink follows the name and is sent only the difference between the old
answer and the new one, at a checkpoint boundary.

**Keep the way back open.** The replaced version keeps running for `rollback.retention` (an hour by
default), so `pravaha rollback --name spend` is one swap and a few milliseconds rather than a second
backfill. It costs a retained computation — its state, its lanes, its feed — which is the price of
an instant rollback and the reason the window is not indefinite. `pravaha finish --name spend`
releases it early when you are satisfied; after that there is nothing to roll back to, and the
status says so (`rollback_available: false`).

**If the node restarts mid-backfill**, the replacement comes back: the name is the version that was
serving it, and the candidate starts again from its own checkpoints. If it restarts after a cutover,
the name is the new version and **the rollback window is gone** — the replaced version was a running
computation, not a durable one. Confirm or roll back before a planned restart.

**What it will not do.** Change the sink the name writes to, or the view's retention (`PRV-4018`);
replace a query whose computation is shared with another name (`PRV-8003`); or replace a query whose
streams cannot be replayed — a table scan's position names where its pass began rather than the
record it was taken after, so there is no offset a history and a live stream could meet at
(`PRV-4018`, before anything starts).

## Debugging a live query: the operator's side

A query is answering wrongly and nothing in the log says why. Fork it (ADR-048, and
[`USER_GUIDE.md`](USER_GUIDE.md#11-the-time-travel-debugger) for the walk-through).

A debug session is a **second copy of the query**, restored from one of its retained checkpoints
and reading the same sources from the offsets that checkpoint recorded. What matters operationally
is what it does *not* do: no sink is attached to it, its view is in no catalogue so nothing can
read or subscribe to it, and its lanes are its own rather than shared with the live query's. The
running query goes on running. A fork cannot make it worse; if a session is open and the query
looks wrong, end the session and read `pravaha queries`, because the session is not the cause.

**What it costs.** Everything the query costs, again: its lanes, its arena, its operator state, and
one source reader per partition. That is the whole reason for the bounds below, and it is why a
session on a query holding forty gigabytes of join state is a decision rather than a click.

```yaml
pravaha:
  debug:
    sessions:
      max: 4          # PRV-8014 past it, naming the sessions this node holds
    session:
      ttl: 15m        # released when nobody has touched it for this long
      max-rows: 20000 # a session keeps every row it read, so it can be exported
    step:
      max-rows: 10000 # how far "to the next commit" or "until this holds" will search
```

Expiry is checked on the way in to the next call rather than on a timer of its own, so a node that
debugs nothing runs nothing extra. Watch **`pravaha_debug_sessions_open`**: it is published whether
or not anybody has ever opened a session, so it reads zero the rest of the time and an alert on it
is possible. A node quietly holding four forks of a large query has four extra copies of its state
and nothing else says so.

**It needs checkpoints.** No `pravaha.checkpoint.directory` means no position to fork from, and the
refusal (`PRV-8011`) says which of the three it is. **And it needs a rewindable source**: the same
constraint a backfill has, refused the same way and for the same reason (`PRV-8012`).

**It needs the administer permission**, not read. A fork exposes the query's SQL, its input rows
and its operator state, which is more than reading its view exposes. Everything in the session,
including reading its status, takes that permission, and an export is recorded in the audit.

## Restarts: what survives

Registered continuous queries are written to a journal, so a restart does not lose them:

```yaml
pravaha:
  registry:
    journal: /var/lib/pravaha/registry.journal
```

**What is written down is the registration, not the state.** A registration — name, SQL, key
columns, owner, retention, bound values — is small, changes rarely, and *cannot be recomputed*,
because it came from a client that may never connect again. State is large, changes constantly, and
can be rebuilt by reading the stream.

So a restart of a node with **no checkpoint directory** costs a **warm-up, not an outage**: views
exist immediately and fill as data arrives, and a windowed query's first window or two are partial.
With a checkpoint directory the state comes back too — see *Checkpoints: what is actually true*
below. Plan restarts accordingly; this is the honest cost, and it is not hidden.

**Owners are re-checked on replay.** A registration is not a standing permission. If the principal
who registered a query has since lost access, the query does not quietly come back — replay refuses
it and names it. Recovery reports both lists, and *the refused list is the one to read*: each entry
is a view some client expects to find and will not.

| You see | It means |
|---|---|
| `PRV-8005 ... this version does not understand` | A journal record from a newer version. Refused, not skipped — skipping would silently drop a registration |
| `PRV-8006` on register | The journal could not be written. The registration is **refused**, because acknowledging one that will not survive a restart tells the client something untrue |
| `refused: ... contract ended` | The owner lost the permission they registered under. Working as intended |
| `refused: ... not a principal this deployment knows` | The owner no longer resolves. Recovering it as nobody would run a query under an authority it was never granted |

A crash mid-append leaves a truncated final record; replay keeps everything before it and ignores the
tail. Compaction rewrites the journal with only what is live, via an atomic move.

The journal holds **query text and bound parameter values** — account numbers, customer ids,
whatever clients filtered on. Permission it like data, not like configuration.

### Checkpoints: what is actually true

**Registered continuous queries are checkpointed when `pravaha.checkpoint.directory` is set, and
not otherwise.** Unset is the default, and the node says so at startup rather than leaving it to be
found during a recovery:

```yaml
pravaha:
  checkpoint:
    directory: /var/lib/pravaha/checkpoints
    interval: 1m
    keep: 3
    timeout: 30s
```

**Write the unit on `interval`.** Spring reads a bare number on a duration as *milliseconds*, while
the engine's own parser refuses one outright — so the two dialects met in one file and
`interval: 2`, written meaning two seconds, produced 6,409 checkpoints in twenty seconds with no
warning anywhere. A bare number is now refused at startup with `PRV-1023` (CFG-15). `2s`, `500ms`,
`1m` and `PT2S` are all fine, and the effective settings are logged once when the node starts:

```
checkpointing registered queries under /var/lib/pravaha/checkpoints every PT1M, keeping the newest 3, timing out at PT30S
```

Three more values in this block are checked when the node starts rather than when the first query
registers, because the bound used to live in the checkpointer's constructor — which runs *per
registration*, so one bad number produced a node that passed every probe, advertised itself as
checkpointing, and refused every query, once per client (CFG-16, CFG-7):

- `keep` below 1 → `PRV-1026`. Keeping none means every restart starts from nothing, which is what
  leaving `directory` unset already means.
- `directory` naming something that exists and is not a directory, or under a parent that does not
  exist → `PRV-4093`. Pointing it at a CSV file used to log "checkpointing registered queries under
  …/txnA.csv" and then fail every registration.
- `pravaha.registry.journal` inside a directory that does not exist → `PRV-8006`. It used to be
  created on the first append, so a typo produced a node journalling correctly to the wrong place
  while the real journal stayed empty.

`timeout` bounds one checkpoint. It is bound here, forwarded to the engine's configuration in
nanoseconds and read by the checkpointer — a claim worth stating because a stale case file had it
recorded as a key with a reader and no writer (CFG-17).

Two different durability questions, easy to confuse. The **journal** remembers which queries exist;
replaying it re-registers them and re-authorizes each against the policy as it is now.
**Checkpoints** remember what those queries had accumulated. A node with a journal and no checkpoint
directory comes back knowing every question and none of the answers.

Each query checkpoints into its own directory beneath that root. One shared store would make pruning
global — the newest three across the node rather than the newest three of each query — so a busy
query would evict a quiet one's only fallback.

Retention is **counted, not timed**, and that is deliberate. An age rule would delete the last
fallback precisely when nothing is happening: an idle system takes no new checkpoints, so after a
quiet night every checkpoint is old and a time rule removes them all. More than one is kept because
the newest is the likeliest to be unreadable — it is the one that was being written when the process
died.

**What this means for you.** With a checkpoint directory, a restart is a resumption: operator state,
source offsets and the view come back together, and the sources rewind to the point the state
describes. **Without one it is a warm-up** — the journal restores the questions, the views start
empty and fill as data arrives, and a windowed query's first window or two are partial. Either way
there is no RocksDB tier. The defence under pressure is plan-time refusal (`PRV-2050`), plus the
memory-mapped overflow tier when `pravaha.state.spill.*` is configured.

### Who owns the state, and the standby

A node **claims** the directories it writes durable state into — the checkpoint root and the
directory holding the registry journal — by writing a `.pravaha-owner` marker naming its node id,
host, Flight port and pid, refreshed on a 30-second lease by a daemon thread.

It exists because two nodes pointed at one root is two lines of YAML and used to be silent: they
prune each other's checkpoints, and each replays the other's registrations and comes up running
queries it never registered (CFG-13, CFG-14). Now the second one refuses to start:

```
PRV-4003  the state in /var/lib/pravaha/checkpoints belongs to node 'pravaha-node-01'
          (pravaha-node-01 at 10.0.0.4:9090 (pid 8123)), and this node is 'pravaha-node-02'.
          ... The other node refreshed its claim 3s ago, so it is running now.
```

| | |
|---|---|
| `pravaha.node.id` | Who the claim is made by. The directory is namespaced by **node id, not by address**, so a node restarting on a new pod IP still finds its own checkpoints; the address lives in the marker, where it answers the question the id cannot — whether the holder is still running |
| `pravaha.state.allow-shared` | `false`. Skips every check, for an operator who has read the refusal and meant it |
| `PRV-4003` | Held by another node, or by a second live instance of this one |
| `PRV-4004` | A marker exists and cannot be read or written. Refused rather than assumed free, because a truncated marker and an absent one mean different things |

**A crash restart is not this.** An expired claim under the *same* node id is taken over
automatically, with a log line saying so. The lock refuses, explains, and offers a named override;
it never breaks itself.

**The standby** is the same mechanism asked from outside:

```yaml
pravaha:
  node:
    id: pravaha-node-01     # the SAME id as the primary, on purpose
  checkpoint:
    directory: /var/lib/pravaha/checkpoints
  standby:
    enabled: true
```

It holds no lanes and serves nothing. It polls the marker every two seconds and promotes itself when
the claim has gone unrefreshed for the lease. `pravaha.standby.enabled=true` without
`pravaha.checkpoint.directory` is refused at startup — there would be nothing to watch and nothing
to resume from. A standby watching a directory owned by a *different* node id never promotes, and
says so rather than waiting silently.

**What a takeover buys is recovery time, not continuity**, and the promotion line says which:

```
promoted from standby: pravaha-node-01 at 10.0.0.4:9090 (pid 8123) last refreshed its claim 41s
ago. Whatever the previous owner processed after its last checkpoint is not in the state this node
resumes from; it is replayed from the source offsets that checkpoint carries, and anything the
source can no longer supply is lost.
```

No consensus, no membership protocol, no Ratis (ADR-035, ADR-034). Two processes and a directory.

## Deployment shapes

| Mode | Artefact | Use |
|---|---|---|
| Embedded | `pravaha-embedded` | Inside a Java application. A lifecycle seam only: it starts, stops and reports state, and cannot register or read a query. The CLI does **not** use it |
| Server | `pravaha-server` + `pravaha-flight` | Standard deployment |
| Container | `deploy/docker/` | The same server, packaged: a non-root image on a JDK 21 Alpine base, built from artefacts the reactor already produced ([ADR-047](adr/047-the-image-is-a-dockerfile-over-built-artefacts.md)) |
| Kubernetes | `deploy/helm/pravaha/` | **One** node as a StatefulSet, because of the state claim below. More replicas are refused at render time |
| Console | `console/`, separate process | Operator UI, talks only to the public API |

> **Getting a node running somewhere — the image, the chart, the volumes, the ports, the
> environment, upgrading a node, the release procedure — is
> [`DEPLOYMENT.md`](DEPLOYMENT.md).** This page is what happens once it is running.

The **Spring Boot starter** (ADR-020) is built: `pravaha-spring-boot-starter` gives an application
`@PravahaListener`, `PravahaTemplate`, a `@PravahaTest` slice, an optional actuator endpoint and
health contributor, and a listener error handler. The engine underneath it stays Spring-free.

Clustering has its coordination layer — membership, leadership, the guarantee rule above — and its
assignment, handoff and rebalancing machinery, all tested against mocks. It has no engine wiring:
nothing implements `PartitionOwner` against real lane state.

**Multi-node execution is deferred** ([ADR-034](adr/034-distribution-deferred.md)), and not because
the wiring is hard. The rung below it is missing: keyed aggregates are single-lane, because nothing
routes a row to the lane owning its group. Distribution would build a multi-node story on top of a
single-node one that is not finished. The engine targets **one node, scaled to its cores**, and the
coordination code is carried unused because the protocol layer would survive a data-plane rewrite
and re-deriving it later would cost more than keeping it.

## JVM flags

Arrow allocates off-heap through `java.nio` internals the module system closes by default:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
```

Without them a Flight server fails *inside* `putNext` and cancels the stream; the client sees
`RST_STREAM` and nothing explains why. On Java 24+ add `--sun-misc-unsafe-memory-access=allow` — it is
**not** a valid option on 21, where the JVM refuses to start rather than ignoring it.

### Choosing the off-heap implementation

Two settings, and they are **system properties rather than configuration keys** — they are read
before any Spring context exists, so they cannot live in `application.yaml`:

| Property | Values | Effect |
|---|---|---|
| `-Dpravaha.memory` | `agrona`, `foreign`, `bytebuffer` | Asks for one implementation by name. Unset means "choose for me", which is `bytebuffer` |
| `-Dpravaha.ffm` | `true` | Opts into the Foreign Function and Memory implementation, which needs JDK 22 or later |

**All of these produce byte-identical results.** The switch is about how memory is reached, not
about what is computed, and that was measured across all four selections. So the reason to set one
is to be *certain* which is running — which is why a name that does not exist is now refused at
startup rather than falling through to the default (CFG-22). An implementation that exists and is
merely unavailable still falls through silently and deliberately: `-Dpravaha.ffm=true` on Java 21
is a launcher that starts working on an upgrade, not a mistake.

Either way the node logs the answer once, at startup:

```
off-heap access: bytebuffer (-Dpravaha.memory, -Dpravaha.ffm=true)
```

Before that line existed, a deployment that set `-Dpravaha.ffm=true`, upgraded its JDK expecting
the switch to take effect, or typed `-Dpravaha.memory=agrone` had no way to find out what it was
running.

## Backup and recovery

Checkpoints are files; recovery restores from the newest complete one. A join's state survives a
crash — there is a test that an interrupted run equals an uninterrupted one.

**The view is in the checkpoint**, alongside the operator state and the source offsets
(`QueryRegistry` snapshots and restores it through `checkpointingViewWith`). It has to be: a filter
or a projection has no operator accumulators, so the view *is* the entire answer, and a restore that
took the offsets without it resumed the source past every row it had read and served an empty view
under a query reporting `RUNNING`.

**Without a checkpoint directory a restart is a warm-up, not a restore.** The journal brings back
every question and none of the answers, and the view fills as data arrives. For a view with a
24-hour retention over a source that can be replayed, that is a re-read of a day; for a pass-through
feed it is whatever the source still holds.

## Upgrades

Blue/green is supported for a *query*: `ShadowDeployment` cuts over at a **frontier, not a moment**,
so every input record is reflected in exactly one version's output. Rollback is the same swap
reversed.

Node upgrades are a stop and start — there is no clustering to roll through. What that costs in
Kubernetes, step by step, and why `terminationGracePeriodSeconds` is 60 rather than the default 30,
is in [`DEPLOYMENT.md`](DEPLOYMENT.md), "Upgrading a node".

**A checkpoint written by an older engine may be refused.** Formats inside a checkpoint carry a
version and a different one is refused with `PRV-4002`, never guessed at. The served view's snapshot
went to version 2 when it began keeping every value's exact type (VIEW-2); a checkpoint whose view is
version 1 is refused, before any operator state is restored, and that query resumes from the start of
its sources — reprocessing, visible in the numbers while it catches up, never a double count in its
answers (a sink that is not transactional is written the replayed rows again). The operator
snapshot went to version 4 when unwindowed aggregates (`SELECT COUNT(*), SUM(x) FROM s`) began
carrying their accumulators and last published answer in it (CKPT-2) — before that a restart
restored such a query's view and resumed its aggregate from zero, and the next answer appeared beside
the old one. A version 3 checkpoint of a windowed or join query is still restored; one of an
unwindowed aggregate is refused, and that query resumes from the start of its sources. Every
checkpoint written after the upgrade is readable by it. Plan an upgrade across such a change for a
time when replaying the sources is affordable, or accept the warm-up.

## What is not solved

Listed because you will meet them, not to be thorough:

- **No engine-internal metrics.** Per-query gauges are published (see *Watching a running node*),
  including state against its ceiling; lane throughput and backpressure are not
- **State is refused unless you configure the spill tier.** Without `pravaha.state.spill.*`, a query
  that reaches its ceiling dies with `PRV-4001` and takes its lane with it; with it, join and
  windowed-aggregate state spills to mapped files and the query slows instead (ADR-037 B2),
  `COUNT(DISTINCT)` included (ADR-044). The ceiling is visible before it arrives either way (B1)
- **By default a lane runs one query on a node.** The thread is shared (`LaneRunner`); the inbox
  and the arena are not, so per-query off-heap is ~1 MiB idle. `pravaha.lane.multiplex.enabled`
  shares them (off by default, because a shared lane shares its fate), for any query, joins included.
  A shared reader writes each row into a shared lane once for every query on it (LANE-2); a source
  promising exactly-once or order still reads once per query
- **N Aerospike-backed queries over one set are one scan**, throttled to `scan.interval.ms` and
  shared: one reader per *source binding* fans each record into every lane bound to it (SRC-3). The
  shared reader pushes the **OR** of its queries' filters and the union of their columns, and is
  rebuilt — at a point where it has handed over all it read, so no row is lost or repeated — when a
  query joins or leaves (ADR-039 item 6). A join that cannot get there within ten seconds, because
  a member's lane stays full, rebuilds it anyway and re-reads that scan: a duplicate, never a loss. Set `share.reader=false` on a binding to go back to a
  reader per query, which pushes that query's own, narrower filter at the cost of its own scan. A
  query's feed says what was pushed: `reading txn (1 partition; pushed 1 filter, 2 columns, shared)`.
  Sources declaring exactly-once or ordering within a partition — filesystem, Delta, JDBC — are never
  shared
- **No clustering, no rebalance, no multi-node execution.** Deferred under
  [ADR-034](adr/034-distribution-deferred.md); Wave 8 bought survival on one node, not
  distribution across several ([ADR-035](adr/035-wave-8-is-survival-not-distribution.md)). The
  HA that exists is a standby that takes over from the newest checkpoint — recovery time, not
  continuity
- **Aligned checkpoint barriers stop at the exchange.** A checkpoint is one cut across every input a
  query reads — the sources are frozen between rows, every lane is handed a marker, and the offsets
  and the state name the same rows (ADR-008). A row *in flight between two lanes* is not cut; the
  checkpoint is refused rather than silently dropping it, and no pipeline this engine compiles sends
  on the exchange, so the case is unreachable today
- **No performance evidence.** Gates P2, P3 and P6 are unmeasured for want of reference hardware, and
  no number from a developer laptop is quoted as if it were
