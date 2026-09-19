# The execution model: lanes, inboxes, arenas

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential; see [`../LICENSE`](../LICENSE).

> **This is the single source of truth for how Pravaha executes.** [`ARCHITECTURE.md`](ARCHITECTURE.md)
> links here rather than repeating it, [`OPERATIONS.md`](OPERATIONS.md) owns the settings and their
> values, and [`system_design.md`](system_design.md) holds the design-stage reasoning. If those and
> this disagree, this one is wrong until proven otherwise — it is the one kept next to the code.

Read this if you are sizing a deployment, debugging a stall, or about to change anything in
`pravaha-runtime`.

---

## 1. A lane

**One thread, one inbox, one arena, one processor, one loop.** That sentence is the class comment on
`Lane` and it is the whole model.

```
   ingest (virtual threads, I/O-bound)        ┌─────────── one lane ────────────┐
                                              │                                 │
   read → decode → hash(key) ──── offer ─────►│  inbox    ──drain(512)──► loop   │──► downstream
                                              │  (ring of off-heap cells)       │
                                              │  arena    (its own slabs)       │
                                              │  processor (its own instance)   │
                                              └─────────────────────────────────┘
```

Four things, all exclusively owned:

| | What it is | Where it lives |
|---|---|---|
| **Driver thread** | One *at a time*. A platform thread, never virtual — the loop is CPU-bound and a virtual thread would trade a warm carrier for a scheduler tuned for the opposite workload | — |
| **Inbox** | A bounded ring of fixed-size cells. Many producers write, exactly one lane reads | **Off-heap** |
| **Arena** | Bump-pointer slabs holding the rows the operators *produce*, rewound in one assignment per batch | **Off-heap** |
| **Processor** | The compiled operator pipeline, built per lane by a factory so its mutable state is confined by construction | On-heap |

### The loop, and the two orderings that are load-bearing

```java
n = inbox.drain(batch, batchSize);          // 512 by default
processor.onBatch(inbox.region(), batch, n);
inbox.release();        // the input cells were live flyweights until this line
arena.resetTo(mark);    // the batch's output, reclaimed in one assignment
```

Rows are **flyweights**: a row is a view over bytes in a cell, not a copy. So "still reading" lasts
the whole batch.

- **`release()` after the processor, never after the drain.** Releasing early hands a producer
  permission to overwrite a row the lane is mid-way through reading. The resulting corruption would
  be rare, load-dependent, and close to unattributable.
- **`resetTo(mark)` after the processor**, for the same hazard on the output side.

**Anything that must outlive a batch is copied, explicitly.** That is the rule the whole memory
story rests on.

---

## 2. Why there are no locks

**Thread confinement is the correctness model, not an optimisation.** A lane's arena, operator state
and inbox cursors have no synchronisation because exactly one thread touches them. Not *fast* locks —
none: no `synchronized`, no `ConcurrentHashMap`, no CAS on a shared aggregate anywhere in the steady
state.

Concurrency comes from **partitioning** instead. A key is hashed into one of 1024 virtual partitions,
and partitions are assigned to lanes, so a given key is only ever touched by one thread. The
indirection looks like ceremony on one node and is what makes rescaling tractable: moving work means
reassigning partitions, never rehashing a key, so a key's state moves as one piece and its ordering
survives.

This is also why the performance gate is stated **per lane** (≥ 1.2 M rec/s) with a *separate*
scaling clause (≥ 90 % from 1 to 8 lanes). The first measures what the loop costs; the second
measures what lanes share, and the intended answer to the second is **nothing**.

### A lane fails alone

A processor throwing is caught by the runner, recorded on the lane that threw, and **that lane alone
is dropped**. Sibling lanes never learn of it. That property was free when a lane owned a thread — a
throw killed that thread and that query — and had to be made explicit once lanes began sharing
threads, because an escaping throw would have turned one bad row into an outage for every query the
runner carried.

---

## 3. Three things people conflate

### Lane vs *thread* — decoupled

A lane owned a platform thread until [ADR-027](adr/027-lane-multiplexes-queries.md), so a thousand
queries meant a thousand threads. `LaneRunner` broke that:

> **Confinement does not require a thread per lane. It requires one thread per lane at a time.**

One thread per core now steps many lanes in turn — the event-loop shape, and the same reason Netty
carries thousands of sockets on a handful of threads. A lane belongs to one runner thread from the
moment it is hosted until it is removed, and a runner steps its lanes sequentially, so every ordering
rule above still holds.

Nothing rebalances lanes between threads. A lane's cost is not knowable at registration, and a scheme
that guessed would have to *move* a lane to correct itself — the one thing confinement forbids.

### Lane vs *query* — one to one by default

One lane runs one query unless `pravaha.lane.multiplex.enabled` is set. Then `LaneMultiplexer` puts
up to `max-queries-per-lane` pipelines on each of a fixed set of shared lanes, dispatching by the
route in each row's header so idle queries are never consulted (W9-8). Each hosted input has a route
of its own, so what a query is fed alone reaches it alone, and a reader shared by several queries
writes each row into a lane once for every query on it that reads it (LANE-2). Any query may share
a lane, joins included. See §7 and `OPERATIONS.md`, *Sharing lanes between queries*.

### Lane vs *partition* — one query, several lanes

A query may span lanes, with rows routed by hash of the join or group key so the lane owning a key
sees every row for it. A plain pump on a multi-lane join is refused rather than silently misrouted.

---

## 4. The inbox

A ring of **fixed-size cells**, allocated off-heap and 64-byte aligned.

```
depth = inbox.cells × inbox.cell-bytes
      = 2048 × 512 = 1,024 KiB per lane   (defaults)
```

Three properties worth knowing before tuning it:

- **A claim is one cell whatever the row's size.** A 40-byte row still occupies 512. That waste buys
  a constant-time claim with no allocator on the hot path.
- **`cell-bytes` is a hard ceiling on row width.** A row that does not fit is refused with
  `PRV-3001`, naming the setting. Sizing down is safe only against a known widest row.
- **A join gets two inboxes, not one buffer with tagged rows.** A tag would let a burst on the left
  fill the shared buffer and starve the right — and a join starved on one side does not slow down,
  it stops producing while still reading. Separate buffers are the only arrangement that survives
  one source being faster than the other.

### Backpressure, not overflow

When an inbox fills, the producer is **paused** — at 80 % by default, resuming at 50 %. Nothing
overflows, nothing is dropped, and an inbox never spills to disk. A slow query becomes a slow *read of its source*,
which is the correct shape: the alternative is dropping rows, which is a wrong answer rather than a
slow one.

### How a lane waits when its inbox is empty

| Strategy | Behaviour | Use |
|---|---|---|
| `BUSY_SPIN` | `Thread.onSpinWait()` | Lowest latency, burns a core |
| `SPIN_THEN_YIELD` | Spin, then yield | `LaneConfig.defaults()`, so a lane built directly through the runtime API. Nothing that registers a query uses it |
| `BACKOFF_PARK` | Escalates to parking | **The default for every registered query** — `QueryRegistry`'s own, `pravaha.lane.wait-strategy` on a node, and `pravaha run`. Many lanes per thread; the runner parks **once for the whole runner** rather than once per lane, so a thousand idle queries do not wake a thousand times to discover they are still idle |

Spinning costs a core per handful of idle queries — nine idle registrations once burned 92 % of one —
which is why nothing that registers queries spins by default. A node running one latency-critical
query sets `pravaha.lane.wait-strategy: BUSY_SPIN` deliberately.

---

## 5. The arena

Bump-pointer slabs, off-heap, **4 MiB** each by default. Operators allocate output rows by advancing
a pointer; the whole batch is reclaimed by one assignment (`resetTo(mark)`). There is no free list
and no per-row deallocation.

The first slab is allocated **lazily** — an idle query used to reserve 4 MiB it had never written to,
which at a thousand queries was most of the memory a node spent on being idle (W9-6).

The sizing rule: `batch-size × widest output row` must fit one slab. Exceeding it is `PRV-3001`
naming `pravaha.lane.arena.slab-bytes`.

---

## 6. Capacity: how many lanes, and what actually bounds it

**Memory, and it did not used to be.** Before ADR-027 the ceiling was the scheduler. Measured on 24
cores with a thousand registered queries:

| | Default sizing | Sized as [`OPERATIONS.md`](OPERATIONS.md) advises |
|---|---|---|
| Platform threads added | **+24** | +24 |
| Off-heap total | 1,000 MiB | **61 MiB** |
| Off-heap per lane | 1,024 KiB | 62 KiB |
| Heap | 66 MiB | — |
| Registration | 3.7 ms each | — |

So "how many lanes" is a division rather than a constant. At the advised sizing, tens of thousands
fit in a few gigabytes. **Threads stopped being the constraint and the inbox became it**, which is
why the sizing advice matters more than it looks.

### The inbox and the arena are RAM. Operator state can spill, when a node says so.

The inbox and the arena are off-heap: allocated directly, invisible to the garbage collector, and
**never paged to disk by this engine**. One level up, a query's *operator state* — stream-to-stream
join state, windowed-aggregate state, and the per-value counts of `COUNT(DISTINCT)` — has a
memory-mapped overflow tier ([ADR-037](adr/037-state-that-degrades-instead-of-dying.md) B2,
[ADR-044](adr/044-no-rocksdb-the-mapped-tier-is-l1.md)): once a query's memory ceiling is reached,
further slabs are carved from mapped files under `pravaha.state.spill.directory`, and the query slows
down instead of being refused. **It is off by default.** Without it, state that outgrows its ceiling
is refused with `PRV-4001`, not degraded; with it, the refusal moves out to the tier's own limits
(`max-overflow-slabs` per store, `max-bytes` per node). [`OPERATIONS.md`](OPERATIONS.md) has the
settings and what the tier costs.

---

## 7. Control tasks, and the aligned barrier

A lane also carries a **control queue**: work that must run at a *specific position* in the stream
rather than whenever convenient. Checkpoints and watermark advances both use it.

The barrier works by **clamping**: a lane sizes each batch against the head of its control queue, so
a task sees the stream exactly at the position it was submitted at. That is what makes a checkpoint
mean something — the recorded offsets and the stored state name the same rows.

**The clamp costs a short batch**, and that is why multiplexing is not simply a matter of wiring
`LaneMultiplexer` in. Three hundred queries on one lane, each advancing a watermark every second,
would cut that lane's batches short several hundred times a second — the lane would spend its budget
on barriers rather than rows (W9-10).

The distinction that resolves it: **a checkpoint is a cut; a watermark is a level.** A watermark is
monotonic and the next tick carries whatever a skipped one would have, so it does not need the
guarantee the clamp provides. Checkpoints are rare; watermark advances are per-query-per-second. That
asymmetry is the design being built on.

---

## 8. Where to go next

| You want | Read |
|---|---|
| The settings and their values | [`OPERATIONS.md`](OPERATIONS.md) |
| How this fits the rest of the engine | [`ARCHITECTURE.md`](ARCHITECTURE.md) |
| Why it was designed this way | [`system_design.md`](system_design.md) §5, §8, §13, §21 |
| What an error code means | [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) |
| The measurements behind §6 | [ADR-036](adr/036-one-node-thousands-of-queries.md), `ThousandQueryTest` |
