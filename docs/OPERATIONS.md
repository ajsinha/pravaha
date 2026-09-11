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

## Deployment shapes

| Mode | Artefact | Use |
|---|---|---|
| Embedded | `pravaha-embedded` | Inside a Java application, unit tests, the CLI |
| Server | `pravaha-server` + `pravaha-flight` | Standard deployment |
| Console | `console/`, separate process | Operator UI, talks only to the public API |

There is **no Spring Boot starter** (ADR-020 planned one; it does not exist) and **no clustering**
(Wave 8). A deployment today is a single node.

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
