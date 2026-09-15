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
| A served view | **retention** (24 h event time, default) | oldest rows forgotten |
| …and as a backstop | `maxKeys` | **fails** (`PRV-4022`) |
| Subscriber buffers | `SubscriptionOptions` | conflate / drop / fail, per the subscriber's choice |
| Concurrent reads | `ReadAdmission` | refuse (`PRV-4026`–`4028`) |

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
| `dropped()` / `conflated()` | `Subscription` | A consumer is falling behind. Never silent — this is why it is counted |
| `rejectedCount()`, `queueTimedOutCount()`, `tenantRejectedCount()` | `ReadAdmission` | Read load exceeding capacity, and which of the three ways |
| `avoidableForks()` | `RegisteredQuery` | You are running N computations where one would do |
| Shared fingerprints | `pravaha queries`, console | The sharing claim holding — or not |
| `subscriberCount()` | `RegisteredQuery` | Consumers attached. Zero on a query somebody expects to be watched is a clue |

These are on the objects and through the SDK. They are **also** published to Prometheus by the
server — see *Watching a running node* below, which is the part that was added later and left this
sentence contradicting it. What is still absent is engine-internal metrics (lane throughput,
backpressure); wiring those to
Micrometer is Wave 9 work. Saying so beats implying a dashboard exists.

## Disk

**Checkpoints are pruned automatically.** `PeriodicCheckpointer` takes a checkpoint on
`pravaha.checkpoint.interval` and then calls `FileCheckpointStore.prune(keep)`, keeping the newest
`pravaha.checkpoint.keep` (default 3) per query; `QueryRegistry` constructs one for every
registration when `pravaha.checkpoint.directory` is set. Nothing has to be pruned by hand, and this
section used to say the opposite.

There is **no RocksDB** in the build — the L1 spill tier is designed (D5) and unbuilt. State today is
off-heap plus checkpoint files, so the failure mode is memory, not disk.

## Capacity: the two numbers that interact

Retention says what a view *means*; the ceiling says what the node can *afford*. When they disagree
the view refuses and the message names both:

> `PRV-4022 … 24 hours of this data is more than 1,000,000 rows. Shorten the window, or provision for
> the volume.`

Sizing rule of thumb: **rows ≈ arrival rate × retention window × distinct keys touched**. If that
exceeds the ceiling, one of the two numbers is wrong, and which one is a product question rather than
an engineering one.

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
| `zookeeper` | ✅ | yes | Production `PARTITIONED`. Needs `plugins/pravaha-cluster-zookeeper` on the classpath |

| Mode | Means | Needs consensus |
|---|---|---|
| `SINGLE` | One node | no |
| `REPLICATED` | Several nodes, each holding the whole state | no |
| `PARTITIONED` | Partitions owned by particular nodes | **yes** |

**`PARTITIONED` on a coordinator without consensus is refused at startup** (`PRV-9002`), not warned
about. Two nodes each believing they own a partition means two nodes writing the same aggregate, and
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
consensus without a mandatory external service. Until it exists, production `PARTITIONED` means
ZooKeeper.

## Rebalancing: what happens when the membership changes

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

The dead-letter queue is written only when something asks for one, and `pravaha run --dlq <file>`
is the only thing that can today — a server has no `pravaha.dlq.*` key yet. Without it a record that
cannot be decoded still fails loudly rather than being discarded: the `run` command exits non-zero
naming the line, the column and the value, and on a server the source feed for that query stops and
says so in `describe()`, though the query goes on reporting `RUNNING`. With it, the run finishes,
the good rows are written, and every rejected record is one JSON object per line — timestamp, query,
correlation id, source offset, reason, and the original bytes in Base64 — meant to be read with
`grep` and `jq` during an incident. The count of rejects is printed next to the row counts, and if
the queue itself could not write, that count goes to stderr: a run that reports `ok` while having
quietly discarded input is the thing the queue exists to prevent, not something it may cause.

Two of the three previously carried a comment telling the operator to permission them like data.
An instruction to somebody who may never read it is not a control.

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
  watermark:
    out-of-orderness: 10s   # the default; a stream overrides it at creation
    idle-after: 30s
    tick: 1s
```

**Two settings, and both bound memory rather than taste.**

*Out-of-orderness* is how late a row may be and still be waited for. Larger tolerates messier
sources and holds every window open longer, so state is larger. Smaller closes sooner and treats
more rows as late corrections.

Set it **per stream**, at creation, with `StreamSchema.outOfOrderness` — lateness is a property of
the source, and a query reading three streams should get three tolerances rather than the worst of
them. The configuration key is the default for streams that do not say.

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

The **tick should be finer than the idle timeout** — idleness is detected on the tick, so a coarser
one cannot notice until long after the fact. This is **not** validated at startup: a node with
`tick: 30s` and `idle-after: 5s` starts and logs both values without complaint. Only
`idle-after` itself is bounds-checked (1s to 10m, refused not clamped).

Watch `pravaha_query_watermark_lag_seconds`. Lag that climbs without bound means event time is not
keeping up with arrival, and every bound downstream is measured against event time — so a stuck
watermark shows up as growing memory, not as a stopped query.

**Without this, state is unbounded.** Windows then close only when the input ends, joins never
evict, and views never forget. Correct over a file; fatal over a stream.

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

**One lane per query, so one thread per query.** Fine at tens of queries. Keyed aggregates are
single-lane anyway (ADR-034), and ADR-027's plan for a lane to multiplex several queries is what
this wants before it reaches hundreds.

**What is still missing: nothing feeds it.** No source plugin is connected to a registered query, so
rows arrive only from whatever calls `accept` — an embedder, or a test. Connecting a source to a
registration is the remaining half of making the server a stream processor, and it is now a small
job rather than a structural one, because the engine underneath is the same engine the CLI uses.

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

## Watching a running node

Prometheus metrics are at `/actuator/prometheus`. Per continuous query:

| Metric | Question it answers |
|---|---|
| `pravaha_query_running{query=}` | Is it alive — 1 running, 0 terminal |
| `pravaha_query_rows_in{query=}` | Is anything arriving |
| `pravaha_query_view_size{query=}` | How many keys the view holds |
| `pravaha_query_view_evicted{query=}` | What retention has removed. **Flat at zero on a long-running query** means either nothing is old enough yet or retention is longer than anyone intended |
| `pravaha_query_view_updates` / `_removals` | Corrections and retractions applied |
| `pravaha_query_watermark_lag_seconds{query=}` | How far behind **event time** it is |

Lag is event-time lag, not processing latency: a query can be fast and still far behind, because
this measures the data rather than the engine. A query that has never seen a row reports `NaN`, not
zero — zero would show it as perfectly up to date.

Meters are removed when a query is dropped. That matters more than it sounds: a gauge registered per
query and never removed leaks the meter *and* the query state its reference keeps alive, and nothing
in Micrometer would complain.

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

So a restart costs a **warm-up, not an outage**: views exist immediately and fill as data arrives. A
windowed query's first window or two are partial. Plan restarts accordingly — this is the honest
cost, and it is not hidden.

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
```

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

Retention here would be counted rather than timed, and the reasoning is worth keeping for when it is
wired: a view holds data and streaming data is about what is true now, so age is the right unit
there; a checkpoint holds a *fallback*, and an idle system takes no new ones — so an age rule would
delete every checkpoint after a quiet night, precisely when recovery is most likely to be wanted.

**What this means for you.** Plan restarts as warm-ups, not as resumptions. A windowed query's first
window or two after a restart are partial. There is no RocksDB tier and no disk-based state — the
failure mode under pressure is memory, and the defence is plan-time refusal (`PRV-2050`), not spill.

## Deployment shapes

| Mode | Artefact | Use |
|---|---|---|
| Embedded | `pravaha-embedded` | Inside a Java application. A lifecycle seam only: it starts, stops and reports state, and cannot register or read a query. The CLI does **not** use it |
| Server | `pravaha-server` + `pravaha-flight` | Standard deployment |
| Console | `console/`, separate process | Operator UI, talks only to the public API |

There is **no Spring Boot starter** (ADR-020 planned one; it does not exist).

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

## Backup and recovery

Checkpoints are files; recovery restores from the newest complete one. A join's state survives a
crash — there is a test that an interrupted run equals an uninterrupted one.

**Views are not checkpointed.** They are rebuilt by the query, so a restart means a warm-up rather
than a restore. For a view with a 24-hour retention over a source that can be replayed, that is a
re-read of a day; for a pass-through feed it is whatever the source still holds.

## Upgrades

Blue/green is supported for a *query*: `ShadowDeployment` cuts over at a **frontier, not a moment**,
so every input record is reflected in exactly one version's output. Rollback is the same swap
reversed.

Node upgrades are a stop and start — there is no clustering to roll through.

## What is not solved

Listed because you will meet them, not to be thorough:

- **No engine-internal metrics.** Per-query gauges are published (see *Watching a running node*);
  lane throughput and backpressure are not
- **No clustering, no HA, no rebalance.** Single node (Wave 8)
- **Aligned checkpoint barriers across the exchange** are not implemented; checkpointing is per-lane,
  which is sound only while lanes share nothing
- **No performance evidence.** Gates P2, P3 and P6 are unmeasured for want of reference hardware, and
  no number from a developer laptop is quoted as if it were
