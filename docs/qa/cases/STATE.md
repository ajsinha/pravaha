# STATE — checkpoints, the registry journal, recovery, cluster modes

*Area `STATE`, IDs `STATE-001`–`STATE-110`, budget 110.*

This area owns the three things that are supposed to make a restart survivable, and the honest
report of how far each of them actually goes on `develop`:

| Thing | Where |
|---|---|
| `store`, `latest`, `availableIds`, `load`, `prune`; atomic rename, trailer | `pravaha-state/src/main/java/com/ash/messaging/pravaha/state/checkpoint/FileCheckpointStore.java` |
| `Checkpoint(id, timestampNanos, offsets, operatorState)`, `sizeBytes()` | `pravaha-state/src/main/java/com/ash/messaging/pravaha/state/checkpoint/Checkpoint.java` |
| Schedule, `keep`, `timeout`, `checkpointNow`, `Stats`, failure reporting | `pravaha-runtime/src/main/java/com/ash/messaging/pravaha/runtime/exec/PeriodicCheckpointer.java` |
| `checkpoint(id, timeout)`, `restore(checkpoint, timeout)`, `abort()` | `pravaha-runtime/src/main/java/com/ash/messaging/pravaha/runtime/exec/QueryExecution.java:689`, `:729` |
| `isStateful()`, `snapshotState()`, `restoreState()`, `SNAPSHOT_MAGIC`, `SNAPSHOT_VERSION` | `pravaha-runtime/src/main/java/com/ash/messaging/pravaha/runtime/exec/InterpretedPipeline.java:364`–`:460` |
| `checkpointingTo`, `startCheckpointing`, `checkpointDirectoryFor`, `deleteCheckpointsOf` | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/QueryRegistry.java:163`–`:200`, `:423`–`:470` |
| `recordRegistration`, `recordDrop`, `replay`, `compact`, `encodeParameter` | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/RegistryJournal.java` |
| `journalTo`, `recover`, `Recovery`, `drop` ordering | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/QueryRegistry.java:560`–`:720` |
| `recordCheckpointFailure`, `lastCheckpointFailure()`, `checkpointFailures()` | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/RegisteredQuery.java:378`–`:406` |
| PRV-8001..8007 | `pravaha-registry/src/main/java/com/ash/messaging/pravaha/registry/RegistryErrors.java` |
| PRV-4001, PRV-4002 | `pravaha-state/src/main/java/com/ash/messaging/pravaha/state/StateErrors.java` |
| PRV-9001..9007 | `pravaha-cluster/src/main/java/com/ash/messaging/pravaha/cluster/ClusterErrors.java` |
| `create`, `modeOf`, `describe`, the consensus check | `pravaha-cluster/src/main/java/com/ash/messaging/pravaha/cluster/CoordinatorFactory.java` |
| `SINGLE`, `REPLICATED`, `PARTITIONED`, `needsConsensus()` | `pravaha-cluster/src/main/java/com/ash/messaging/pravaha/cluster/ClusterMode.java` |
| `Guarantees(name, excludesSplitBrain, requiresExternalService, suitableForProduction)` | `pravaha-cluster/src/main/java/com/ash/messaging/pravaha/cluster/Guarantees.java` |
| `single`/`socket` providers, ZooKeeper plugin provider | `pravaha-cluster/.../SingleNodeProvider.java`, `SocketProvider.java`, `plugins/pravaha-cluster-zookeeper/.../ZooKeeperProvider.java` |
| Startup wiring: coordinator, journal + `recover`, `checkpointingTo` | `pravaha-server/src/main/java/com/ash/messaging/pravaha/server/PravahaNode.java:328`, `:356`, `:400` |
| Owner-only modes, directory fsync | `pravaha-common/src/main/java/com/ash/messaging/pravaha/common/io/SensitiveFiles.java` |
| Config comments this area is measured against | `pravaha-server/src/main/resources/application.yaml:127`–`:190` |
| Existing (thin) coverage | `pravaha-state/.../FileCheckpointStoreTest.java`, `pravaha-it/.../PeriodicCheckpointerTest.java`, `pravaha-it/.../CheckpointRecoveryTest.java`, `pravaha-it/.../JoinRecoveryTest.java`, `pravaha-registry/.../RegistryJournalTest.java`, `pravaha-cluster/.../CoordinatorFactoryTest.java` |

## Three facts this file is built around

Each is established by a case rather than asserted here, but an executor should know them going in,
because several cases look strange until you do.

1. **`QueryExecution.restore` and `CheckpointStore.latest` are called from no shipped code path.**
   Every reference outside `pravaha-state/src/main` is a test: `CheckpointRecoveryTest:124`,
   `JoinRecoveryTest:155`, `FileCheckpointStoreTest`, `PeriodicCheckpointerTest`. `PravahaNode.start`
   wires `checkpointingTo` (`:360`) and `journalTo` + `recover` (`:401`–`:402`) and never wires a
   restore. So checkpoints are written and never read back by the product.
2. **`InterpretedPipeline.isStateful()` is `!windowed.isEmpty() || !joins.isEmpty()`**
   (`InterpretedPipeline.java:458`). A plain `GROUP BY` aggregate is neither. `QueryExecution.checkpoint`
   skips every pipeline that is not stateful (`:695`–`:697`), so for a projection, a filter *or a
   keyed non-windowed aggregate* the checkpoint's `operatorState()` is empty and the file is pure
   framing.
3. **`application.yaml:137` says checkpoints exist "so a restart recovers answers and not only
   questions".** Points 1 and 2 are what that sentence is worth. Cases STATE-054 to STATE-069 pin
   exactly which half of it is true.

## Harnesses

Every case names one rather than restating it.

**`H-CS` — the store alone.** A JUnit temporary directory `dir`, `CheckpointStore store = new
FileCheckpointStore(dir)`, and checkpoints built by hand:

```
Checkpoint cp(long id, String... kv)   // offsets from pairs, operatorState empty
Checkpoint cpState(long id, String op, byte[] bytes)
```

No engine, no lanes. Used wherever the case is about the file format, the directory, or pruning.

**`H-PRJ` — embedded, stateless plan.** Exactly `SubscriptionTest.setUp`, i.e.

```
StreamSchema TXN = user_id STRING, amount INT64
Principal DANA = ("dana", "acme", roles={analyst})
ViewCatalog views = new ViewCatalog();
QueryRegistry registry = new QueryRegistry(views, TXN);
RegisteredQuery q = registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA);
```

`feed(user, amount)` writes one binary row with `weight(1)`, `eventTimestampNanos(0)`, `sequence(0)`,
hands it to `q.accept(...)`, then `q.awaitApplied(Duration.ofSeconds(10))`. `q.commit()` publishes.
`InterpretedPipeline.isStateful()` is **false** here.

**`H-WIN` — embedded, windowed aggregate, genuinely stateful.** `H-PRJ` but

```
StreamSchema TXN_T = user_id STRING, amount INT64, ts TIMESTAMP   (event-time column ts)
RegisteredQuery w = registry.register(
    "w",
    "SELECT user_id, SUM(amount) AS total FROM txn "
      + "GROUP BY user_id, TUMBLE(ts, INTERVAL '10' SECOND)",
    List.of(0), DANA);
```

with `registry.generatingWatermarks(Duration.ofSeconds(30), Duration.ofSeconds(1))`.
`isStateful()` is **true** (`windowed` is non-empty), so `snapshotState()` writes real bytes.
The standard payload, used everywhere a number has to be hand-checked, is

```
feedAt("u1", 100, t=1_000_000_000L)     // 1s  -> window [0s,10s)
feedAt("u1", 102, t=2_000_000_000L)     // 2s  -> window [0s,10s)
```

so the open window for `u1` holds `100 + 102 = 202` and the window has not closed.

**`H-JOIN` — embedded, stream-to-stream join, stateful by the other branch.** `H-WIN`'s registry
plus a second stream `LKP = user_id STRING, tier STRING` and

```
RegisteredQuery j = registry.register(
    "j", "SELECT t.user_id, t.amount, l.tier FROM txn t JOIN lkp l ON t.user_id = l.user_id",
    List.of(0), DANA);
```

`joins` non-empty, `windowed` empty.

**`H-REG` — registry checkpointing to a root.** `H-WIN`, plus before any `register`:

```
registry.checkpointingTo(root, Configuration.builder()
    .set("pravaha.checkpoint.interval", "200ms")
    .set("pravaha.checkpoint.keep", "3")
    .set("pravaha.checkpoint.timeout", "5s")
    .build());
```

200 ms rather than the 1 m default so a case finishes; `PeriodicCheckpointer.from` reads exactly
these three keys (`PeriodicCheckpointer.java:118`–`:125`).

**`H-JRN` — registry journalling to a file.** `H-PRJ`, plus `registry.journalTo(new
RegistryJournal(journalFile))` before any `register`, and a second registry over the same file for
the replay half, recovered with `second.recover(id -> Optional.of(new Principal(id, "acme",
Set.of("analyst"), Map.of())))` unless the case says otherwise.

**`H-SRV` — a real node.** `pravaha server` with

```yaml
pravaha:
  streams:
    txn: { fields: "user_id STRING, amount INT64, ts TIMESTAMP", event-time: "ts" }
  registry:
    journal: /var/lib/pravaha/registry.journal
  checkpoint:
    directory: /var/lib/pravaha/checkpoints
    interval: 1s
    keep: 3
  security: { authentication: none, policy: permissive, allow-anonymous: true }
```

Clients speak to `grpc://localhost:9090`. "Restart" means `stop()` then `start()` of a new process
against the same two paths.

**`H-CFG` — configuration only, no I/O.** `CoordinatorFactory.create(Configuration.builder()...)`
called directly. Every cluster case that does not say otherwise is this, and needs no second node.

**`H-2N` — two nodes.** Two `pravaha server` processes on one host, Flight ports 9090 and 9091,
`pravaha.cluster.socket.peers: "a=127.0.0.1:9090,b=127.0.0.1:9091"`. **Requires a second node.**

**`H-ZK` — ZooKeeper.** `H-SRV` with `plugins/pravaha-cluster-zookeeper` on the classpath and a
single-node ZooKeeper at `127.0.0.1:2181`. **Requires ZooKeeper.**

---
