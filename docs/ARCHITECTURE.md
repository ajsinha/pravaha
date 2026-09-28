# Architecture

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

How Pravaha is put together, and why each part is shaped the way it is.

This is the engineering view. If you want the *ideas* rather than the machinery, read
[`CONCEPTS.md`](CONCEPTS.md) first — it is shorter and it is what most questions turn out to be
about. The full specification is [`system_design.md`](system_design.md); every decision has an
[ADR](adr/).

---

## Two processes

```
      ┌───────────────────────────────┐        ┌──────────────────────────────┐
      │   pravaha-server   (Java 21)  │        │   Pravaha Console  (Python)  │
      │                               │        │                              │
      │   engine + public REST API    │◄───────│   FastAPI + Jinja templates  │
      │   /status  (plain HTML,       │  SDK   │   built on the pravaha SDK   │
      │    works when console is down)│        │                              │
      └───────────────┬───────────────┘        └──────────────────────────────┘
                      │
        ┌─────────────┴─────────────┬──────────────┐
        ▼                           ▼              ▼
   Aerospike                   Cassandra        Kafka
```

The console is a **separate runtime on purpose** (ADR-024). A test enforcing "the console may only
use the public API" can be weakened or waived; a Python process simply cannot reach into a Java
engine. And building the console on the published SDK makes it the first real consumer of the
integration story a closed-source product depends on — a proof rather than an assertion.

The cost is two runtimes to deploy, stated plainly in design §23.2a rather than glossed.

## Inside the engine

```
  SQL text
     │  Apache Calcite: parse, validate, cost-based optimise
     ▼  RelNode
     │  PhysicalPlanBuilder            ← the boundary: nothing below imports Calcite
     ▼  PhysicalOperator tree
     ├──────────────────────┬───────────────────────┐
     │ generated (Janino)   │ interpreted fallback  │  differentially tested
     ▼                      ▼                       │  against each other
  fused stage           RowProcessor chain          │
     │                                              │
     ▼  binary rows in an off-heap arena, MPSC rings between lanes
   sinks
```

Calcite is a **compiler, not a runtime** (ADR-002). The 1.0 draft executed through Calcite's
`Enumerable` convention — pull-based and row-at-a-time — and could not have met its own throughput
targets. Everything expensive happens once at registration; the steady state allocates nothing.

## The life of a query

One path, end to end. Everything else in this document is a detail of one of these steps.

```
  SQL ──► SqlPlanner ──► PhysicalPlanBuilder ──► InterpretedPipeline ──► ServedView
        (Calcite)        (Pravaha's plan IR)      (lanes, operators)     (the answer)
                                                          │                   │
                                                          │                   ├──► query  (Flight SQL)
                                                          └──► subscribers ───┘    (SQL, bound params)
```

1. **Register.** SQL is parsed, validated and planned once. The normalised plan becomes a
   **fingerprint**; if a computation with that fingerprint already exists, the new name joins it
   rather than starting a second one.
2. **Run.** Rows arrive from a source plugin, through an ingest pump, into a lane's off-heap inbox,
   through the operator pipeline.
3. **Maintain.** Output lands in a **served view** — committed and pending kept apart, so a
   consistent read never sees half a batch.
4. **Serve.** Reads are answered from the view by the *same* planner and operators a continuous
   query uses, so a `WHERE` means exactly what it means in a continuous query rather than nearly.
5. **Push.** Subscribers attached to the query receive each commit as a batch of weighted changes.

The two things worth noticing: **planning happens once** and is reused, and **registration and
subscription are separate objects** — many consumers share one computation, and it outlives all of
them.

## What a lane is

> **Connectors: [`CONNECTORS.md`](CONNECTORS.md).** The SPI, a worked example and the TCK.

> **Full treatment: [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md).** This section is the summary a
> reader of the architecture needs; that document is the one kept next to the code, and it wins if
> the two ever disagree.

A **lane** is the engine's unit of execution: one *driver* and the memory only that driver may
touch. It is not a cache, not a staging area clients read from, and not a queue — it is a slice of
the engine that owns its work end to end.

```
   ingest threads                    ┌──────────── one lane ────────────┐
   (virtual, I/O-bound)              │                                  │
   decode → hash(key) ──── offer ───►│  inbox   ──drain(512)──►  loop   │──► sink / tap ring
                                     │  (bounded, off-heap cells)       │
                                     │  arena   (its own slabs)         │
                                     │  processor (its own instance)    │
                                     └──────────────────────────────────┘
```

Four things, all exclusively owned:

| | |
|---|---|
| **One driver thread at a time** | A platform thread, never a virtual one — see the next section. Since W9-4 it is *shared*: a `LaneRunner` of one thread per core steps each of its lanes in turn, the event-loop shape. Confinement needs one thread per lane **at a time**, not one thread per lane, and a lane never moves between runner threads once hosted. |
| **One inbox** | A bounded ring of fixed-size off-heap cells. Many ingest threads write; exactly one lane reads. This is the only cross-thread handoff on the data path. |
| **One arena** | Bump-pointer off-heap slabs holding the rows the operators *produce*, rewound in a single assignment at the end of every batch. |
| **One processor** | Built per lane by a factory, so its mutable state is thread-confined by construction rather than by convention. |

The loop is four lines, and two orderings in it are load-bearing:

```java
n = inbox.drain(batch, 512);
processor.onBatch(inbox.region(), batch, n);
inbox.release();        // the input cells were live flyweights until this line
arena.resetTo(mark);    // the batch's output, reclaimed in one assignment
```

Releasing the input cells *before* the processor runs would let a producer overwrite a row the lane
is still reading — rows are flyweights (design §8.4), so "still reading" lasts the whole batch. The
resulting corruption would be rare, load-dependent and close to unattributable. The arena reset is
the same hazard on the output side. **Anything that must outlive a batch is copied, explicitly.**

Keys reach the right lane by hashing into a fixed number of **virtual partitions** (1024, set at
registration) which are then assigned to lanes (design §21.1). The indirection looks like ceremony
on one node and is what makes rescaling tractable: moving work means reassigning partitions, never
rehashing a key, so a key's state moves as one piece and its ordering survives.

A step that throws is caught by the runner, recorded on the lane that threw, and **that lane alone
is dropped** — which is what a thread dying used to do when a lane owned one. On a shared thread an
escaping throw would have turned one bad row into an outage for every query the runner carried.

What a lane deliberately does *not* have is a lock. Concurrency comes from partitioning: a given key
is only ever touched by one thread, so there is no `synchronized`, no `ConcurrentHashMap` and no CAS
on a shared aggregate anywhere in the steady state. That is also why the gate is stated *per lane*
(≥ 1.2 M rec/s) with a separate scaling clause (≥ 90 % from 1 to 8 lanes) — the first measures what
the loop costs, the second measures what the lanes share, and the intended answer to the second is
nothing.

### How many lanes, how deep the inbox, and where it all lives

Short answers, because [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) owns the long ones:

- **The ceiling is memory, not threads** — it stopped being threads at
  [ADR-027](adr/027-lane-multiplexes-queries.md). A thousand queries on 24 cores add **24** platform
  threads and **61 MiB** off-heap at the advised sizing.
- **Inbox depth is `cells × cell-bytes`** — 2048 × 512 = 1,024 KiB by default.
- **RAM, unless a spill tier is configured.** A full inbox backpressures; state that outgrows its
  ceiling is refused with `PRV-4001` — or, with `pravaha.state.spill.*` set, join and
  windowed-aggregate state spills to memory-mapped files and the query slows instead (ADR-037 B2).

## Threads: what is an OS thread here, and what is not

Connection count must never become thread count. Five tiers, chosen by what the work actually does
— and the last two are Wave 9's, where a query's own threads went from three to none:

| Work | Thread | Count scales with |
|---|---|---|
| Lane workers — the hot loop | **Platform**, optionally pinned, and shared between lanes | `availableProcessors`, fixed at construction — never with load and, since W9-5, never with the query count |
| Control plane (validate, explain, register), plugin and source I/O, async lookup joins | **Virtual (Loom)** | unbounded — they cost nothing while parked |
| Streaming subscriber fan-out | **Netty event loops**, roughly 8 | nothing; thousands of sockets share them |
| Flight call handling, source feed loops | **Virtual (Loom)** since W9-1/W9-2 | nothing. A parked subscription and a napping feed each cost a continuation, not a thread |
| Watermark advance, periodic checkpoints | one **`SharedClock`** daemon thread for the process, each tick on a virtual thread | nothing. These were two `newSingleThreadScheduledExecutor`s *per query* before W9-3 |

Virtual threads are Java 21's, and the baseline is Java 21 precisely because of them (design §4.5).
They are already on for the server — `spring.threads.virtual.enabled` in `application.yaml` — since
the control plane is short, blocking, I/O-bound requests in large numbers, which is exactly the
workload Loom exists for.

**Lanes stay platform threads for the inverse reason, and now share them.** A lane loop is
CPU-bound and never blocks, so it has nothing to gain from unmounting and everything to lose: a
virtual thread migrates between carriers, and migration discards the L1/L2 warmth the binary-row layout exists to exploit. Loom is
for work that blocks; a lane never does. True core affinity needs a native call the JDK does not
expose, so it is left to `taskset`, `numactl` or an affinity library in the host — the lane exposes
its thread so a deployment can apply one. Claiming the JVM pins threads would not be true.

**A thousand subscribers is a serialisation problem, not a thread problem — and the serialisation
is the bound.** The rule is *stage once, serialise per socket*
([ADR-026](adr/026-one-subscription-model-three-carriers.md), design §20.3b): a commit assembles
its change log **once per query**, whatever its audience, and every subscriber holds references
into that one log. Measured, twenty in-process subscribers cost a query's ingest nothing measurable
against none; twenty over Flight cost it about four fifths, and every difference between the two is
the carrier — a `VectorSchemaRoot` per subscription and a serialisation of every batch on its own
call thread (`SubscriptionIngestCostTest`). Flight's server API offers no way to hand an
already-serialised record batch to a second listener, so **a node's subscriber capacity is bounded
by Arrow serialisation and the bound is linear in subscriber count**; past it the answer is
hierarchical fan-out, not a larger node. A lane, meanwhile, never sees a subscriber: it writes to a
conflating, drop-oldest tap ring and returns, so **lane cost is O(1) in subscriber count**. A slow
browser can never backpressure a production query; it falls behind in its own buffer and conflates,
reports `dropped_count`, or is disconnected (STRM-4, STRM-8).

## Ten thousand queries on one node

The target: **10 000 continuous queries running concurrently on a single node**, 64 GB heap,
Aerospike behind it. That is a capacity statement about *query count*, and it constrains the design
in a different direction from throughput — which is why it is written down separately rather than
assumed to follow from being fast.

The governing rule is one sentence:

> **Nothing whose cost is per-query may be a thread, a ring buffer, an arena, or a timer wheel.**

Every one of those is megabyte-scale or OS-scale. At 10 000 of anything, megabyte-scale per unit is
tens of gigabytes and OS-scale is a dead machine. A per-query 1 MB inbox is 10 GB; a per-query 4 MB
arena slab is 40 GB; a per-query thread is 10 000 threads. So all four are **per lane**, and a lane
is sized by cores — roughly 30 of them on this class of box, whatever the query count.

### The budget

| Cost | Per query | × 10 000 | Where it lives |
|---|---|---|---|
| Generated stage + its classloader | ~40 KB | ~400 MB | Metaspace, off-heap |
| Plan IR, schema, catalog entry, subscription record | 50–100 KB | 0.5–1 GB | Heap |
| Operator state (windows, aggregates) | **budget ≤ 4 MB** | ≤ 40 GB | *Designed* as off-heap L0 then RocksDB; RocksDB dropped by ADR-044. **As built: off-heap, all of it.** Row payloads live in `RowStore` blocks, and a join's per-side index and a windowed aggregate's accumulators are `VariableKeyStateMap`s — off-heap tables of fingerprints and handles, keys of any width, which replaced the deleted fixed-width `L0StateMap` (W8-12). `COUNT(DISTINCT)` keeps one off-heap entry per group, slice and value with a count (`DistinctValueCounts`, ADR-044), so it spills like the rest. There is no RocksDB; the optional overflow tier is memory-mapped files (ADR-037 B2), which takes join rows and their key index — the index's slot table included, in 16 MiB segments past a RAM budget — accumulators and distinct values, compacts churned slabs and budgets its disk in bytes. Measured twice (ADR-044): within about 2x of RAM throughput at 1–16x a 64 MiB ceiling while page-cached, and 1,000–4,700 random operations a second with the process capped below its state. Off by default |

> **Aggregates are single-lane.** The lane model parallelises joins: `pumpPartitionedInto` routes
> each row to the lane owning its join key. There is no equivalent for a grouping key, so a keyed
> aggregate spread across lanes would have every lane keeping its own partial total for any key it
> happened to see — one group emitted as several rows of partial answers, with nothing to say so.
> That combination is refused (`PRV-3020`) rather than left to be discovered in the numbers. Run a
> keyed aggregate on one lane until key-partitioned ingestion exists.

| Inbox, arena, timer wheel, thread | **0** *designed* | ~150 MB total | Per lane, ~30 of them. **As built on a node: the thread is shared and the inbox and arena are not.** `LaneRunner` gives the node one thread per core whatever the query count (W9-5), but by default a node's lane still runs one query — `pravaha.lane.multiplex.enabled` shares lanes between any queries (W9-8, LANE-2) — so its inbox is per query — 1,024 KiB by default, and the arena's first slab is allocated on the first row rather than at registration (W9-6). Measured: **1,024 KiB per idle query, 1,328 KiB active**, down from ~5 MiB — and the lane's arena is **zero** for both a projection and a windowed aggregate, because operator output goes to the view rather than through the lane's scratch. The inbox is the whole of the idle cost; sized down (`inbox.cells: 256`, `cell-bytes: 256`) a thousand idle queries is about 64 MB |
| Aerospike connections | **0** | one pool | Node-wide, shared |

The 64 GB heap is therefore *not* where the money goes, and that is deliberate: the heap holds
control-plane objects, and the steady-state data path allocates approximately nothing on it (NFR-2c
caps it at 5 MB/s/lane). State is the term that actually scales, it is off-heap and tiered by
design §14, and it is bounded per query by the planner refusing what it cannot bound (design §9.6).
"Ten thousand queries" is a memory statement about state, not about heap.

### What the number really means

Capacity is **query count × per-query rate**, and only the product is bounded:

| Shape | Aggregate | Verdict |
|---|---|---|
| 10 000 queries at 100 rec/s | 1 M rec/s | Comfortable on ~30 lanes |
| 10 000 queries at 1 000 rec/s | 10 M rec/s | Plausible; lane-bound, needs measurement |
| 10 000 queries at 10 000 rec/s | 100 M rec/s | **Not on one node.** Nothing about the design changes this |

Quoting a query count without a rate is how capacity claims become untrue. The honest form is *10 000
queries at a stated aggregate ingest rate and a stated state budget*.

### The four things that make it work

**1. A lane multiplexes queries.** 10 000 queries over ~30 lanes is ~300 pipelines per lane. The lane
loop must therefore be driven by an *activity list* — pipelines with pending input — and never by a
scan over registered pipelines, or 300 idle checks per iteration become the dominant cost at low
rates.

**2. Fan-out inside the lane is zero-copy.** Many queries read the same stream. A row is copied into
a lane's inbox **once** and every pipeline subscribed to that stream reads the same flyweight. Copying
per query would make ingest cost O(queries), which is the same mistake as encoding per subscriber.

**3. Sharing collapses the count.** Queries with the same canonical fingerprint are one computation
with many subscriptions (design §11.8, ADR-025). Ten thousand *subscriptions* are frequently far fewer
*computations*, and the fingerprint includes security predicates, so the collapse is safe.

**4. Cold start is interpreted first, generated second.** Registering 10 000 queries means 10 000
Janino compilations — a few minutes of CPU if done naively and serially at the worst possible moment,
node restart. The interpreted path already exists as the correctness fallback (design §12.4), so a query
**runs interpreted immediately** and is swapped to generated code as a bounded compile pool works
through the backlog. The fallback stops being only a safety net and becomes the admission strategy.

> **As built (2026-09-26, C-7), point 4 is not what happens.** Generated code is compiled when a
> lane's pipeline is built, before its first row, and the background swap (`AdaptiveStage`,
> `StageUpgradeService`) is deleted: nothing reached it, and its adapter wrote a stage's output over
> its own input. The interpreter remains the fallback for every chain the generator refuses.
> Compiled stages are shared between lanes and identical queries, so the restart cost is one Janino
> compile per *distinct* filter-and-projection chain, paid serially at registration. See ADR-005's
> amendment.

### What is not built yet

Points 1 and 2 above are **still not in the code**, and the half of point 1 that *is* built is worth
separating from the half that is not.

**Built (W9-4, W9-5):** the thread is no longer per query. `LaneRunner` drives many lanes from a
fixed pool of one thread per core and `QueryRegistry` owns one runner, so a node's thread count
follows its cores — `NodeScaleTest` measures 200 queries adding 24 platform threads where the same
workload cost 400 before. `SharedClock` did the same for the watermark and checkpoint schedulers.

**Built, `auto` by default (W9-8):** `LaneMultiplexer` puts many pipelines on one lane and so shares
the *inbox and arena* as well as the thread. With `pravaha.lane.multiplex.enabled` (`auto`: once a
node hosts `auto-from`, 64, queries; `true`: from the first; `false`: never), `QueryRegistry`
hosts registrations on a fixed set of shared lanes it owns (`QueryExecution.startOn`), so one query
closing no longer stops a lane serving the rest. `SharedLanes` is the admission control: each
registration goes to the least loaded shared lane below `max-queries-per-lane`, and one that fits
nowhere gets a lane of its own — never a refusal, because turning on a memory setting must not make
a node accept fewer queries. Off by default because a shared lane shares its fate: one pipeline
that throws stops every query on the lane.

**A shared lane takes any query, and one copy of a shared source (LANE-2).** The multiplexer
dispatched by stream, which assumed one ingest per stream per lane, while the feed layer gave each
registration its own feed — so two queries over one stream on one lane each received the other's
copy of every row (a count of 4 read 8), and placement had to keep them apart and never host a join
(LANE-1). Rows now carry a *route*: each hosted input has a private one, stamped on whatever that
query is fed alone, and a reader shared by several queries (SRC-3) writes each row into a shared
lane once under a route of its own that every query on the lane reading it listens to. Listening
starts and stops by control task, so at an exact row: a joiner is handed nothing from before it
joined, a paused query nothing after it paused. A thousand queries over one source on eight lanes
are eight inboxes and eight copies of each row. A source promising exactly-once or order keeps a
reader per query, each writing its own copy into the shared inbox.

Two things had to be true first, and both are. A row carries a `streamId` the registry assigns, and
the multiplexer refuses an unassigned one rather than guessing (W9-9) — before that every row of
every stream had the same id, and wiring it would have delivered one stream's rows to queries
subscribed to another. And the **aligned checkpoint barrier** no longer costs a short batch per
watermark (W9-10): a lane clamps each batch at a *checkpoint*, which must see the stream exactly
where it was submitted, but a *watermark* is a level that keeps its place in the queue without
clamping, because applying one further along the stream is never wrong, only less prompt. Three
hundred queries advancing a watermark every second no longer cut one lane's batches short.

So the per-query costs that remain are the inbox and the arena, and both are now settings
(`pravaha.lane.*`) rather than build-time constants.

Also open at 10 000: per-query quotas so one hot query cannot starve the ~300 sharing its lane
(FR-9, design §21.4), and a metaspace measurement with 10 000 queries *live* — the existing leak test covers
10 000 register/drop **cycles**, which is a different question and a much easier one.

## Registering a query

A registration turns SQL into a computation that keeps running and keeps a view current. It is the
surface everything else hangs off: a subscription attaches to a registered query, the console lists
them, a cluster assigns them to nodes, and a continuous query's parameters can only be classified
against one.

**Sharing is by fingerprint, never by name or by text** (ADR-025). The fingerprint is the normalised
plan, so two people who type the same question differently — different aliases, different
whitespace, operands of an `AND` in a different order — get one computation holding one copy of the
state. That is the mechanism behind "ten analysts on one dashboard cost one query", and it is
enforced in the registry rather than left to whoever writes the SQL.

The fingerprint includes the **security predicates** applied to the plan, which is what makes
implicit sharing safe rather than merely cheap: two principals with different entitlements produce
different plans, so a shared computation can never serve one of them rows filtered for the other.
Nobody has to remember the rule — it falls out of what is hashed.

A computation may answer to several names and is released when the **last** one is dropped. Dropping
on the first would take the answer away from everyone else who registered the same question and has
no idea the others exist.

Pausing is not dropping: a paused query stops advancing and its view keeps answering at the frontier
it reached, which is a far better failure mode for a dashboard than answers that disappear. Rows
arriving while paused are dropped rather than buffered — buffering would turn a pause into a memory
commitment of unknown size, and the operator paused it precisely to stop it doing work.

## Subscribing to a registered query

A subscription is not the query (ADR-025). Many attach to one computation, they come and go without
it noticing, and it outlives all of them — which is why a dashboard reconnecting costs nothing: the
state is warm because it belongs to the query, not to whoever was watching.

**Changes arrive per commit, never per row.** A commit is the point at which the engine says a prefix
of the input is fully processed; between commits the view holds a half-applied batch, and a
subscriber woken per row could act on a total still being assembled.

They carry **weights**. `-1` withdraws a row, so a late-data correction reaches a consumer as a
retraction followed by an insert — the same arithmetic as everything else in the engine rather than a
message type every client has to recognise. A consumer that only wants current values can ignore
negative weights and overwrite by key; one maintaining its own aggregate must apply them, or it
drifts from the view the first time a window is corrected.

What happens when a subscriber cannot keep up is the part that decides whether one slow consumer
degrades everybody. **Blocking is not an option offered**: a subscriber that blocks applies
backpressure to the *query*, so one slow dashboard would slow the computation for everyone keeping
up. Instead the buffer is bounded and overflow is a declared choice — `CONFLATE` (replace the waiting
change for a key; right for a dashboard, wrong for anything maintaining an aggregate from the
weights), `DROP_OLDEST`, or `FAIL` (for a ledger, where finding out beats carrying on with a gap).
Whatever is lost is **counted**, because a subscriber silently missing data is the failure the whole
mechanism exists to make visible.

A consumer that throws is detached rather than called again — otherwise one broken subscriber becomes
a stream of exceptions on the engine's own thread.

**A subscription can start from the view's state (SUB-1).** A plain one joins at the next commit
boundary — a commit's audience is fixed when its first batch is applied (STRM-11) — and carries no
state, so a client that mirrors the view by reading it beside the subscription can lose the commit in
flight. `ViewSink.onCommitFromSnapshot` makes the two one step under the publish lock that every batch
and every commit take: with no commit in flight the committed rows are the snapshot and the listener
joins the audience of the next commit in the same critical section; with one in flight the listener
waits, and that commit takes the snapshot after publishing and registers the listener inside its own
critical section. Either way the snapshot is the view at a commit C and the listener hears every
commit after C and none before — no log, just a copy of the committed rows. `RegisteredQuery`,
Flight (the `subscribe.snapshot` ticket, batches marked `pravaha:<kind>:<frontier>`), both SDKs, the
CLI's `--snapshot`, the embedded engine, the starter's `awaitView` and the console's live page are
built on it.

## How a join stays incremental

A stream-to-stream join is where an incremental engine either earns its keep or falls over, so it is
worth following the whole path.

**The rule.** `Δ(A⋈B) = ΔA⋈I(B) + I(A)⋈ΔB + ΔA⋈ΔB`. Both sides are streams; both keep state; and an
update on either side is a retraction and an insert, so the operator has no update path in it at
all. The third term is the one implementations drop: without it, two rows that arrive together and
match each other are never joined, because each is compared against the other side *before* this
batch. The bug is invisible at low rates -- a batch of one contains no pairs to miss -- and shows up
as quietly missing output when traffic rises, which is exactly backwards from how anyone debugs.

Pravaha executes the rule a row at a time, and at that granularity the third term stops being a
separate case: rows that would have shared a batch arrive one after another, and the second finds
the first already in state. The batched form still exists, in `pravaha-algebra`, as the reference the
property oracle checks the engine against.

**The state.** Each side holds every row that could still match, as Z-set elements rather than as
events: two identical arrivals are one entry of weight 2, and a retraction cancels against it. That
distinction is what makes an update release memory instead of accumulating it -- an engine that
appends events keeps the retracted row forever and re-emits it on the next probe from the other
side.

**The memory.** Join state needed something the row arena deliberately is not. An arena allocates
for one batch and drops the whole thing in one assignment, which is why a row costs a pointer bump;
it has no per-row free, no free list and no fragmentation. State is the opposite shape -- rows held
across batches, released one at a time as retractions arrive -- and a bump pointer under that
workload grows to the high-water mark of everything the join has ever held. So `RowStore`: slabs,
bump-allocated within, with a free list per power-of-two size class. A released block is handed back
for the next request in its class. A join whose row count is flat reserves a flat amount of memory,
which is the property the whole class exists for and the one its tests assert.

**The bound.** All of the above is still unbounded in the only sense that matters: two unbounded
streams joined without a time bound accumulate for as long as the query runs. Until windowed and
time-versioned joins land, the protection is a row ceiling per side that fails the query, naming the
count and what to do about it, rather than letting the node die with nothing to point at. Outer
joins are refused for the same reason -- an unmatched row would have to be held for as long as a
match could still arrive, which without a time bound is forever.

## Bounds: the principle

Three mechanisms, one rule:

> **A bound that changes the answer belongs in the query's meaning. A bound that protects the machine
> belongs in configuration, and should fail rather than quietly alter results.**

| | Kind | On exceeding |
|---|---|---|
| A view's **retention** | meaning | forgets the oldest rows |
| A join's **match window** | meaning | releases rows that can no longer match |
| `maxKeys`, `maxRowsPerSide`, admission limits | machine | **refuses**, loudly |

And where the engine cannot bound something at all it refuses the *query* — an unwindowed keyed
`GROUP BY` is rejected at planning rather than deployed to fail months later.

The three sections that follow are this principle applied.

## What a retention window is actually for

Not primarily a memory knob. It is the statement that **a served view is a cache of a current
answer, not a system of record.**

That distinction is the whole point of the feature, and four things follow from it.

**It keeps the view from quietly becoming a second copy of the database.** A view over a query that
does not aggregate gains a row per event forever. Left alone it converges on holding the entire
source dataset in memory — which is precisely the second system this engine exists to remove. The
irony is worth naming: without retention, the serving layer reinvents the thing it replaced.

**It turns an unbounded liability into a sized resource.** Without retention, a view's memory is a
function of how many distinct keys the data produces over all time — a property of the world, not a
number anyone chose. With it, memory is bounded by a figure in a config file, and capacity planning
becomes arithmetic instead of hope.

**It is expressed in event time, and only in event time.** A row count was tried and removed: "the
last million rows" is four hours on a quiet day and twenty minutes on a busy one, so nobody can say
what the view contains without also knowing the throughput — exactly the property event-time
semantics exist to eliminate. Counting rows is still worth doing, but it is a *capacity ceiling*,
not a retention policy, and the view already has one. Retention says what the view **means**; the
ceiling says what the node can **afford**. When the ceiling is hit, the message says which of the two
is wrong rather than blaming the data.

**It states the relevance horizon of the question.** "Is this card running hot right now" is
meaningless about a card that last transacted six months ago; "what is this desk's exposure today"
is a question about today. A streaming answer has a useful lifetime, and retention is where that
lifetime is written down. If the window is shorter than the questions people are actually asking,
that is a design mismatch — and `evicted()` is how somebody notices it rather than discovering it
through a support call about missing rows.

**It draws the boundary with the store.** History lives where the data came from. A query about last
month goes to Aerospike or the warehouse; the view answers about now. Retention is where that line
is drawn explicitly rather than by whatever happens to still be in memory.

### What it does not solve

Worth being precise, because the name invites over-reading.

Retention is on the **view** — the published answer. It does nothing for **operator state**: the
accumulators inside the pipeline (`SlicedAggregateState`, `JoinSide`) are separate, and separately
bounded. A window bounds an aggregate because the window closes. Nothing yet bounds a
stream-to-stream join, and retention on its output view would not help — the join's liability is the
unmatched rows it is holding *upstream*, waiting for partners that may never arrive.

It is also not durability. Retention decides what is kept hot, never what survives a restart. What
survives is the checkpoint, and **the view is in it** — `QueryRegistry` snapshots and restores it
through `checkpointingViewWith`, alongside the operator state and the source offsets. It has to be:
a filter or a projection has no accumulators, so the view *is* the whole answer, and a restore that
rewound the offsets without it served an empty view under a query reporting `RUNNING`. With no
checkpoint directory configured there is no checkpoint, and then a restart is a warm-up: the journal
brings back the questions and the view fills again as data arrives.

## Why a join has a clock

A stream-to-stream join would, left alone, hold every unmatched row for as long as the process
lives: a partner could arrive at any moment, so nothing is ever safe to forget. That is not a
tuning problem, it is the shape of the operation.

The engine's answer is that **the bound is part of what the join means**. With a match window of
`T`, the join means "rows that match and whose event times are within `T` of each other", and a row
older than `watermark − T` cannot be part of any match it promises — because a watermark is the
statement that nothing earlier is still to come, so every partner yet to arrive is later than that.
Releasing such a row is not losing data; it is the definition being honoured. An hour of event time
is the default, because a join with no window at all is the thing that cannot be allowed.

That is why eviction here is safe and a size-based eviction would not be. Dropping the oldest rows
to stay under a ceiling would silently lose matches the query *did* ask for — so the row ceiling
does not evict. It fails, loudly, and exists only as a backstop for a key space that is wrong rather
than merely large. **A bound that changes the answer belongs in the query's meaning; a bound that
protects the machine belongs in the configuration, and it should fail rather than quietly alter
results.**

## How many reads at once

ADR-030 put continuous queries and request/response on the same engine, which makes an unbounded
read path a way for a client with a loop to stop a continuous query from keeping up with its input.
The continuous query is the one with a service level; the read is the one that can be told to come
back. `ReadAdmission` bounds three things separately, because they fail differently:

- **concurrency** — the work happening at once. Reads run on the calling thread, never on lane
  threads, so this bounds memory and CPU contention rather than lanes;
- **queue depth** — the work *waiting*. A queue longer than the client's timeout is work nobody is
  waiting for any more, which the server will nevertheless do, at the expense of work somebody is;
- **per-tenant share** — any one tenant's use of the first two. Without it the fairest possible
  global limit still lets one tenant hold every permit, and what the other tenants report is
  "Pravaha is down".

Refusal is the feature. Queueing without limit turns a load problem into a latency problem and then
into a memory problem; refusing gives the client something to retry or shed and the operator a
number that rises before anything breaks. Metadata calls are admitted too — a client asking only for
schemas, in a loop, uses the same planner and the same CPU, and an unmetered path is an unmetered
path.

The refusal reaches the client as `RESOURCE_EXHAUSTED`, not `INVALID_ARGUMENT`, because that is the
difference between a driver that backs off and retries and one that reports a bug.

## Who may read what

Enforced here, not in the store the data came from, and the reason is structural rather than a
preference for defence in depth (ADR-031). A served view is derived: the row "u4's gold-tier total
for the window ending 12:05" exists only inside Pravaha, and there is no record in Aerospike whose
permissions correspond to it. A change feed is read once and shared by every query registered
against it (ADR-027), so per-principal enforcement at the source would mean reading it once per
principal — the read amplification the architecture exists to remove — or reading it as a superuser,
which enforces nothing. And a continuous query runs for months with nobody connected, so there is no
session to push down even in principle.

Three seams, deliberately separate:

- **`TokenVerifier`** — a credential in, a `Principal` out. Pravaha stores no passwords; this is
  where a deployment plugs in the identity provider it already runs.
- **`SecurityPolicy`** — `(Principal, view)` in, an `AccessDecision` out: allow, allow with a row
  filter, or deny. On the path of every read, so an implementation that calls a remote service per
  query will be felt.
- **`AuditSink`** — every decision, allow and deny alike. A log of refusals answers "who was
  stopped" and not "who read the payroll view", which is the question that gets asked.

A row filter goes into the **plan**, immediately above the scan and below any aggregate — never
concatenated into the SQL text, because `WHERE total > 0 OR total <= 0` is enough to neutralise an
appended `AND tier = 'gold'`, and a predicate in the plan has no syntax for the caller to reach.
Below the aggregate, so a `SUM` over rows a principal may not see is never computed in the first
place.

The rule that governs whether a filter can be applied at all: **it is sound iff every column it
names is present in the view.** If the column was aggregated away, each row already mixes values
this principal may and may not see, and no filter applied afterwards separates them — so the read is
refused with `PRV-7003` and the message names the fix, a view that applies the filter before
aggregating. Forking state per principal is available and explicit; it is not a default, because a
policy with a per-user filter would otherwise multiply the engine's state by the number of users and
the operator would learn that from a memory alarm.

## Parameters

`WHERE user_id = ?` means two different things depending on who is asking, and the syntax hides the
difference (ADR-032).

For a **request/response** query it is what everyone expects: plan once, bind per call. Binding
happens when the *physical plan* is built rather than when the SQL is parsed, and two things follow
from that. A bound value never passes through a parser — by the time it exists there is none left to
reach, which is a stronger guarantee than escaping. And a bound value compiles to the *same
predicate* as a written literal, not an equivalent one, so there is a single code path that filter
pushdown cannot tell apart.

For a **continuous query** the same text is ambiguous, because the computation holds state and lives
for months. Either the binding is part of what the computation is — a separate computation with its
own state per distinct value — or it is a filter at the tap, and one computation serves every
binding. The decision is made by the soundness rule of ADR-031, unchanged: a binding applies at the
tap iff the view carries every column it names. That is not a coincidence — a security row filter
and a query parameter are the same object, a predicate supplied from outside the query text and
applied to a shared computation.

A `?` belongs in a **WHERE clause and nowhere else** — `HAVING` too, since it is a filter above the
aggregate. A parameter selects rows; every other position is a different query rather than a
different binding of one. A window size is the clearest case: five-minute and hourly windows have no
rows in common, so a parameterised window is not one query with a knob but a family of queries, and
a deployment knows its windows when it writes them. The rule is one position accepted rather than a
list forbidden, so the next place a placeholder could appear is refused by default.

Within a WHERE or HAVING clause the supported positions are the ones JDBC's `PreparedStatement`
allows that a read-only engine has: comparisons, `IN (?, ?)`, `BETWEEN ? AND ?`, and any combination
under `AND`, `OR` and `NOT`. `LIKE ?` and `LIMIT ?` are not supported, and neither is a decision
about parameters — `LIKE 'u%'` and `LIMIT 5` are refused too, so a placeholder there would only
accept a statement the engine cannot run.

Types are inferred rather than declared: the planner works them out from the columns and sends the
parameter schema when a statement is prepared, so neither SDK guesses.

## Modules

| Module | What it is |
|---|---|
| `pravaha-api` | The public SPI. Zero third-party dependencies, Java 17 bytecode. |
| `pravaha-common` | Memory access, arenas, rings, row layout, configuration. |
| `pravaha-algebra` | Z-sets, frontiers, the incremental lift and its property oracle. |
| `pravaha-runtime` | The plan IR and interpreted execution. **No Calcite.** |
| `pravaha-codegen` | Whole-stage generation, compiled with Janino. |
| `pravaha-sql` | Calcite integration. The only module that imports it. |
| `pravaha-connect` | Plugin discovery, classloader isolation, registry. |
| `pravaha-bindings` | Plugin bindings a registry is fed and written through: `PluginSourceFeeds` (shared readers, pushdown, dead letters), `PluginLookupSources`, `PluginSinks`, resolved by plugin name through `ServiceLoader`. Plain Java, moved out of `pravaha-server` so the server and the embedded engine share one copy. Enforcer-banned from Spring. |
| `pravaha-embedded` | In-process engine, mode A. `PravahaEngine` declares streams and bindings, registers continuous queries, takes pushed rows, reads views with SQL, subscribes to committed changes, and persists through the journal and checkpoints. No Spring: an enforcer rule and `ArchitectureRulesTest` both refuse it. |
| `pravaha-server` | Spring Boot node: public REST API and the plain `/status` page. |
| `pravaha-spring-boot-starter` | Mode B: an embedded engine as a bean from `pravaha.*`, `PravahaTemplate`, and `@PravahaListener` methods receiving a query's committed changes, with a `PravahaListenerErrorHandler` for the ones that throw. `@PravahaTest` and `PravahaTester` for applications' tests; a `pravaha` health indicator and read-only endpoint when Actuator is present. Depends on `pravaha-embedded`, never the reverse. |
| `pravaha-cli` | The `pravaha-engine` command: `validate`, `explain` and `run` with the engine in-process. The `pravaha` command, which talks to a node, is the Python CLI in `sdk/python`. |
| `pravaha-testkit` | Virtual clock, deterministic scheduler, plugin TCK. |
| [`plugins/pravaha-plugin-filesystem`](../plugins/pravaha-plugin-filesystem) | The reference source and sink. Delimited files, no external dependency. |
| [`plugins/pravaha-plugin-delta`](../plugins/pravaha-plugin-delta) | Delta Lake source, on Delta Kernel rather than Spark. Version diffs become Z-set weights. |
| [`plugins/pravaha-plugin-iceberg`](../plugins/pravaha-plugin-iceberg) | `iceberg-sink`: a query's answer in an Apache Iceberg table on the local filesystem, on iceberg-core and iceberg-parquet rather than Spark. Upsert by key through equality deletes (format v2), or a changelog with the weight as a column. One snapshot per checkpoint, exactly once: files staged unreferenced at prepare, and the snapshot summary carries the transaction id and label so a repeated commit is skipped. |
| [`plugins/pravaha-plugin-feedfile`](../plugins/pravaha-plugin-feedfile) | Drop-directory feeds. CSV and Parquet, completion detection, per-file replayable offsets. |
| [`plugins/pravaha-plugin-jdbc`](../plugins/pravaha-plugin-jdbc) | Incremental-poll source and dimension table for any JDBC database. Keyset pagination, filter pushdown into `WHERE`, projection into the `SELECT` list, and a continuous `COUNT`/`SUM` pushed as one partial per page, driver supplied by the deployment. And `jdbc-sink`: a query's answer maintained in a table by key, transactional through a staging table. |
| [`plugins/pravaha-plugin-aerospike`](../plugins/pravaha-plugin-aerospike) | The primary target. Scan-based source with server-side filter and projection (bin) pushdown, an idempotent upsert sink (`aerospike-sink`, composite keys, deletes on a retraction), and a lookup table. Tested against a real Aerospike server, not a mock. |
| [`plugins/pravaha-plugin-cassandra`](../plugins/pravaha-plugin-cassandra) | A full periodic scan of a table's assigned `token()` range (ADR-039 item 6). Projection pushdown into the CQL `SELECT` list only; no client-pullable change log to follow -- Cassandra's CDC is a per-node agent problem, a different project ([`CONNECTORS.md`](CONNECTORS.md) section 5). Tested against a real Cassandra server, not a mock. |
| [`plugins/pravaha-plugin-postgres-cdc`](../plugins/pravaha-plugin-postgres-cdc) | `postgres-cdc`: change data capture from one PostgreSQL table through native logical replication (ADR-041) — `pgoutput` decoded by hand, an insert at +1, a delete and an update's before-image at −1, whole transactions, `REPLICA IDENTITY FULL` required. Exactly once: the slot is confirmed only at durable checkpoints. Uses the deployment's PostgreSQL driver. An initial snapshot with `snapshot.mode: initial`, under an exported snapshot and exact across a restart half-way through it. Tested against a real PostgreSQL (Testcontainers). |
| [`plugins/pravaha-plugin-mysql-cdc`](../plugins/pravaha-plugin-mysql-cdc) | `mysql-cdc`: change data capture from one MySQL table through the row-based binary log, on ADR-041's model without Debezium — the plugin registers as a replica (`mysql-binlog-connector-java`, no JDBC driver), an insert at +1, a delete and an update's before-image at −1, whole transactions, `binlog_format = ROW` and `binlog_row_image = FULL` required. Exactly once from a binlog file and offset at a transaction boundary; a purged file is refused. No initial snapshot yet. Tested against a real MySQL 8 (Testcontainers). |
| [`plugins/pravaha-plugin-kafka`](../plugins/pravaha-plugin-kafka) | A source, `kafka`: a topic read as a stream, one reader per partition, each assigned its partition and seeked to the offset the checkpoint holds -- exactly once, `read_committed` by default, never positioned by a consumer group; JSON rows by column name, or `kafka-sink`'s changelog with its weights; a fetch thread per reader so `poll` never blocks. A sink, `kafka-sink`: a query's changes written to a topic as keyed JSON upserts with a tombstone for a retraction, or as an explicit changelog. Exactly once to a `read_committed` consumer through a staging topic and one Kafka transaction per checkpoint -- Kafka has no prepare a restarted producer could commit. Ships no native compression codec. Tested against a real broker (Testcontainers), not only Kafka's mocks. |
| `pravaha-cluster` | Membership, leadership and assignment behind an SPI, so a deployment uses the mechanism it already runs. Each implementation **declares what it guarantees**, and the engine refuses the work a coordinator cannot safely do. |
| [`plugins/pravaha-cluster-zookeeper`](../plugins/pravaha-cluster-zookeeper) | A ZooKeeper-backed coordinator. Its own artefact, so a deployment using sockets or a single node carries no ZooKeeper client. |
| [`sdk/python`](../sdk/python) | Python client. The console is built on it. |
| [`console`](../console) | The operator console: a separate Python process, its own artefact (ADR-033). `core/` holds configuration, the engine adapter and the services; `routes/` defines the pages and `/api/v1`; `web/` holds the Jinja templates and the vendored assets; `content/` holds help topics that **include** this documentation rather than copying it. |
| `pravaha-state` | Durable and off-heap state: the block store joins and aggregates hold state in, its memory-mapped overflow tier (`spill`) with slab compaction that gives churned-out files back and a node-wide byte quota that refuses by code before the disk fills (ADR-044), and checkpoints. The L0 off-heap map that was meant to be the first tier is **gone** — deleted in Wave 8 (W8-12), because its keys are a fixed width and a `GROUP BY` key containing a string is not. |
| `pravaha-backfill` | Loading history without losing the present: the snapshot-to-changefeed splice, its throttle, and blue/green cutover. |
| `pravaha-serving` | Reading a query's answer directly, with consistency declared per read and staleness returned with it. Also SQL over a maintained view, planned and executed by the same engine a continuous query uses. |
| `pravaha-pgwire` | The PostgreSQL wire protocol, read half: simple and extended query protocol, `psql`'s catalogue queries, TLS on `SSLRequest`. Off by default (`pravaha.pgwire.enabled`); answered by the same `ViewQuery` and authorization as Flight. |
| `pravaha-flight` | The client gateway: Arrow Flight SQL, serving request/response over the same views (ADR-030). One protocol, and its JDBC, Python and Go clients are maintained upstream. |
| `pravaha-security` | Who is asking, what they may read, and a record of both (ADR-031). Three SPIs: a verifier turns a credential into a principal, a policy decides, an audit sink records. |
| `pravaha-identity` | Users, passwords, API keys and sessions kept by the engine (ADR-052), behind the same verifier SPI: Argon2id hashes, lockout, key scopes, rotation and revocation, all in an append-only journal holding nothing reversible. No Spring. Off unless `pravaha.identity.enabled`. |
| `pravaha-registry` | Where SQL becomes a computation with a name, a state and an end (ADR-025). Sharing is by fingerprint, so the same question asked twice is one computation with two names. |
| [`sdk/pravaha-sdk-java`](../sdk/pravaha-sdk-java) | The Java client's types and connection strings. Dependency-free by enforcer rule: it is embedded in somebody else's application. |
| [`sdk/pravaha-sdk-java-flight`](../sdk/pravaha-sdk-java-flight) | The Java client's transport, kept separate so an application that only wants the types never sees Netty. |

`pravaha-catalog` is still a placeholder.

## Rules the build enforces

Not conventions — tests. Each one exists because the failure it prevents is silent.

| Rule | Enforced by |
|---|---|
| No Spring in the engine core, the embedded engine, or anything it assembles (registry, serving, security, bindings) | `ArchitectureRulesTest` + `maven-enforcer` in `pravaha-embedded` and `pravaha-bindings` |
| No Calcite outside `pravaha-sql` | module dependency graph + `ArchitectureRulesTest` |
| `pravaha-api` depends on nothing but the JDK | `maven-enforcer` + ArchUnit |
| Only one package names a low-level memory API | `ArchitectureRulesTest` |
| No `java.io.Serializable` as a transport | `ArchitectureRulesTest` |
| Source files under 1500 lines | `SourceFileSizeTest` |
| Every file carries the copyright notice | `LicenseHeaderTest` |
| The API surface matches its lock file | `OpenApiContractTest` |
| Documentation names only modules that exist | `DocumentationFreshnessTest` |

## Where to go next

| | |
|---|---|
| **What are the ideas?** — [`CONCEPTS.md`](CONCEPTS.md), the eight this is all built on |
| **How do I use it?** — [`USER_GUIDE.md`](USER_GUIDE.md), task by task |
| **How do I run it?** — [`OPERATIONS.md`](OPERATIONS.md) |
| **What SQL can I write?** — [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md), streams to views to every construct, with a test behind it |
| **Why is it like this?** — [`adr/`](adr/), every decision with its alternatives |
| **The whole specification** — [`system_design.md`](system_design.md) |
