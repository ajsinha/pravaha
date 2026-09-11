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

There is **no Prometheus endpoint yet**. These are on the objects and through the SDK; wiring them to
Micrometer is Wave 9 work. Saying so beats implying a dashboard exists.

## Disk

**The honest state: checkpoint files accumulate.** `FileCheckpointStore.prune(keep)` exists and
**nothing calls it automatically.** Until that is wired, prune on a schedule or watch the directory.
This is the one genuinely unbounded disk path today.

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

## Deployment shapes

| Mode | Artefact | Use |
|---|---|---|
| Embedded | `pravaha-embedded` | Inside a Java application, unit tests, the CLI |
| Server | `pravaha-server` + `pravaha-flight` | Standard deployment |
| Console | `console/`, separate process | Operator UI, talks only to the public API |

There is **no Spring Boot starter** (ADR-020 planned one; it does not exist).

Clustering has its coordination layer — membership, leadership, the guarantee rule above — and its
assignment, handoff and rebalancing machinery, all tested. What it does **not** yet have is the
engine wiring: nothing implements `PartitionOwner` against real lane state, and no checkpoint
coordinator cuts aligned barriers across nodes. A deployment today is still a single node. The
machinery is the part that is hard to get right; the wiring is the part that is left.

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

- **No automatic checkpoint pruning.** The known disk-growth path
- **No metrics endpoint.** Counters exist on objects; nothing scrapes them
- **No clustering, no HA, no rebalance.** Single node (Wave 8)
- **Aligned checkpoint barriers across the exchange** are not implemented; checkpointing is per-lane,
  which is sound only while lanes share nothing
- **No performance evidence.** Gates P2, P3 and P6 are unmeasured for want of reference hardware, and
  no number from a developer laptop is quoted as if it were
