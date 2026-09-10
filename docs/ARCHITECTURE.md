# Pravaha — architecture at a glance

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
Proprietary and confidential; see [`LICENSE`](../LICENSE).

The short version. The full treatment is [`system_design.md`](system_design.md); this page exists so
someone can hold the shape in their head before reading 3 000 lines.

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

## What a lane is

A **lane** is the engine's unit of execution: one thread and the memory only that thread may touch.
It is not a cache, not a staging area clients read from, and not a queue — it is a slice of the
engine that owns its work end to end.

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
| **One thread** | A dedicated platform thread, never a virtual one — see the next section. |
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

What a lane deliberately does *not* have is a lock. Concurrency comes from partitioning: a given key
is only ever touched by one thread, so there is no `synchronized`, no `ConcurrentHashMap` and no CAS
on a shared aggregate anywhere in the steady state. That is also why the gate is stated *per lane*
(≥ 1.2 M rec/s) with a separate scaling clause (≥ 90 % from 1 to 8 lanes) — the first measures what
the loop costs, the second measures what the lanes share, and the intended answer to the second is
nothing.

## Threads: what is an OS thread here, and what is not

Connection count must never become thread count. Three tiers, chosen by what the work actually does:

| Work | Thread | Count scales with |
|---|---|---|
| Lane workers — the hot loop | **Platform**, optionally pinned | `physicalCores − 2`, never with load |
| Control plane (validate, explain, register), plugin and source I/O, async lookup joins | **Virtual (Loom)** | unbounded — they cost nothing while parked |
| Streaming subscriber fan-out | **Netty event loops**, roughly 8 | nothing; thousands of sockets share them |

Virtual threads are Java 21's, and the baseline is Java 21 precisely because of them (design §4.5).
They are already on for the server — `spring.threads.virtual.enabled` in `application.yaml` — since
the control plane is short, blocking, I/O-bound requests in large numbers, which is exactly the
workload Loom exists for.

**Lanes stay platform threads for the inverse reason.** A lane loop is CPU-bound and never blocks,
so it has nothing to gain from unmounting and everything to lose: a virtual thread migrates between
carriers, and migration discards the L1/L2 warmth the binary-row layout exists to exploit. Loom is
for work that blocks; a lane never does. True core affinity needs a native call the JDK does not
expose, so it is left to `taskset`, `numactl` or an affinity library in the host — the lane exposes
its thread so a deployment can apply one. Claiming the JVM pins threads would not be true.

**A thousand subscribers is a serialisation problem, not a thread problem.** The load-bearing rule
is *encode once, write N times* (design §20.3b): a batch is encoded to Arrow once per query per
tick, and the same buffer is written to every subscriber socket. Twenty queries and a thousand
subscribers cost 20 serialisations a second and a thousand buffer copies — not a thousand
serialisations. And a lane never sees a subscriber: it writes to a conflating, drop-oldest tap ring
and returns, so **lane cost is O(1) in subscriber count**. A slow browser can never backpressure a
production query; it conflates and reports `dropped_count`, or it is disconnected.

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
| Operator state (windows, aggregates) | **budget ≤ 4 MB** | ≤ 40 GB | Off-heap L0, then RocksDB — **never heap** |
| Inbox, arena, timer wheel, thread | **0** | ~150 MB total | Per lane, ~30 of them |
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

### What is not built yet

Points 1 and 2 above are **not in the code today.** The lane implemented for Wave 3 runs exactly one
processor, which is right for a single-query pipeline and does not multiplex. Saying so here, rather
than letting the diagram imply otherwise, is the point: the lane's ownership model (its own inbox,
arena, thread and processor instance) is what makes multiplexing a change *inside* the lane rather
than a redesign, but the change is real work and it is scheduled, not done.

Also open at 10 000: per-query quotas so one hot query cannot starve the ~300 sharing its lane
(FR-9, design §21.4), and a metaspace measurement with 10 000 queries *live* — the existing leak test covers
10 000 register/drop **cycles**, which is a different question and a much easier one.

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

## The five ideas everything else follows from

| | |
|---|---|
| **Z-sets** | A relation and a changelog are the same object: a multiset with signed integer weights. An update is `−1` of the old row and `+1` of the new, so insert, update and delete stop being three cases an operator author must handle and become arithmetic. Design §9. |
| **Binary rows in arenas** | Rows are flyweights over off-heap memory; field access is a constant offset. A batch is processed and the arena rewound in one assignment. No `Map<String,Object>`, no boxing, no per-row allocation. Design §8. |
| **Single-writer lanes** | Concurrency comes from partitioning, not sharing. One thread, one ring, one state slice, one timer wheel per lane — no locks in steady state. Design §13. |
| **Capability declaration** | A source declares whether it can rewind, sees deletes, carries a before-image; a sink declares which changelog modes it accepts. The engine computes the *weakest link* and reports that, rather than promising exactly-once the plumbing cannot deliver. Design §10. |
| **Refuse rather than guess** | An unbounded `GROUP BY` is rejected at planning with a message saying what to do about it. Unbounded integration is how incremental engines die in production, and refusing is the only intervention that reliably works. Design §9.6. |

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
| `pravaha-embedded` | In-process engine. No Spring. |
| `pravaha-server` | Spring Boot node: public REST API and the plain `/status` page. |
| `pravaha-cli` | The `pravaha` command. |
| `pravaha-testkit` | Virtual clock, deterministic scheduler, plugin TCK. |
| [`plugins/pravaha-plugin-filesystem`](../plugins/pravaha-plugin-filesystem) | The reference source and sink. Delimited files, no external dependency. |
| [`plugins/pravaha-plugin-delta`](../plugins/pravaha-plugin-delta) | Delta Lake source, on Delta Kernel rather than Spark. Version diffs become Z-set weights. |
| [`plugins/pravaha-plugin-feedfile`](../plugins/pravaha-plugin-feedfile) | Drop-directory feeds. CSV and Parquet, completion detection, per-file replayable offsets. |
| [`plugins/pravaha-plugin-jdbc`](../plugins/pravaha-plugin-jdbc) | Incremental-poll source and dimension table for any JDBC database. Keyset pagination, filter pushdown into `WHERE`, driver supplied by the deployment. |
| [`plugins/pravaha-plugin-aerospike`](../plugins/pravaha-plugin-aerospike) | The primary target. Scan-based source with server-side filter pushdown, idempotent sink, and a lookup table. Tested against a real Aerospike server, not a mock. |
| [`sdk/pravaha-sdk-java`](../sdk/pravaha-sdk-java) | Java client. Depends on `pravaha-api` alone. |
| [`sdk/pravaha-sdk-python`](../sdk/pravaha-sdk-python) | Python client. The console is built on it. |

| `pravaha-state` | Off-heap state: the L0 map, the block store joins hold rows in, and checkpoints. |
| `pravaha-backfill` | Loading history without losing the present: the snapshot-to-changefeed splice, its throttle, and blue/green cutover. |
| `pravaha-serving` | Reading a query's answer directly, with consistency declared per read and staleness returned with it. Also SQL over a maintained view, planned and executed by the same engine a continuous query uses. |
| `pravaha-flight` | The client gateway: Arrow Flight SQL, serving request/response over the same views (ADR-030). One protocol, and its JDBC, Python and Go clients are maintained upstream. |
| `pravaha-security` | Who is asking, what they may read, and a record of both (ADR-031). Three SPIs and no implementation of an identity provider: deployments already have one. |
| [`sdk/pravaha-sdk-java`](../sdk/pravaha-sdk-java) | The Java client's types and connection strings. Dependency-free by enforcer rule: it is embedded in somebody else's application. |
| [`sdk/pravaha-sdk-java-flight`](../sdk/pravaha-sdk-java-flight) | The Java client's transport, kept separate so an application that only wants the types never sees Netty. |

`pravaha-catalog` is still a placeholder.

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

Types are inferred rather than declared: the planner works them out from the columns and sends the
parameter schema when a statement is prepared, so neither SDK guesses.

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

## Rules the build enforces

Not conventions — tests. Each one exists because the failure it prevents is silent.

| Rule | Enforced by |
|---|---|
| No Spring in the engine core | `ArchitectureRulesTest` |
| No Calcite outside `pravaha-sql` | module dependency graph + `ArchitectureRulesTest` |
| `pravaha-api` depends on nothing but the JDK | `maven-enforcer` + ArchUnit |
| Only one package names a low-level memory API | `ArchitectureRulesTest` |
| No `java.io.Serializable` as a transport | `ArchitectureRulesTest` |
| Source files under 1500 lines | `SourceFileSizeTest` |
| Every file carries the copyright notice | `LicenseHeaderTest` |
| The API surface matches its lock file | `OpenApiContractTest` |
| Documentation names only modules that exist | `DocumentationFreshnessTest` |

## Where to go next

- **Why does this exist?** — design [§1](system_design.md), [§2](system_design.md)
- **Is the engineering sound?** — design [§3](system_design.md), [§9](system_design.md), [§29](system_design.md)
- **How do I run it?** — [`QUICKSTART.md`](QUICKSTART.md)
- **What was decided and why?** — [`adr/`](adr/)
- **When does it ship?** — [implementation plan](implementation_plan.md)

---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**</sub>
