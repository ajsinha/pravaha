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
    journal: /opt/pravaha/data/registry.journal
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
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

### A. Checkpoints are written, and written on a schedule (STATE-001–012)

`PeriodicCheckpointer` exists because nothing was calling `QueryExecution.checkpoint`. These cases
check the schedule itself: that it runs, at the configured rate, with the configured id sequence,
and that it stops when it is told to.

## STATE-001 — a stateful query writes its first checkpoint one interval after start, not immediately
**Intent:** `start()` uses `scheduleWithFixedDelay(task, interval, interval, MILLISECONDS)`
(`PeriodicCheckpointer.java:127`), so the initial delay equals the period. A checkpoint at t=0 would
capture an empty query and is not what the class does; an executor who expects one will mis-read
every later case.
**Falsifier:** a `checkpoint-1.bin` exists in the directory before 200 ms have elapsed, or none
exists 1 s after `start()`.
**Setup:** `H-REG`. Register `w`. Note the wall-clock instant `T0` immediately after `register`
returns (registration is what calls `startCheckpointing`, `QueryRegistry.java:549`).
**Steps:** 1. At `T0 + 100 ms`, list `root/w/`. 2. At `T0 + 1000 ms`, list `root/w/`.
**Expected:** step 1 lists **zero** files matching `checkpoint-*.bin`. Step 2 lists at least one, and
the lowest id present is `1` (`nextId` starts at 1, `PeriodicCheckpointer.java:76`), so
`checkpoint-1.bin` is either present or has already been pruned by a later id — at 1000 ms with a
200 ms interval at most `1000 / 200 = 5` checkpoints have been taken and `keep=3`, so ids 3, 4, 5 is
the expected worst case and ids 1 and 2 may be gone.
**Vacuity:** with `start()` never called the directory stays empty at both observations, so step 2
fails. Checking at 100 ms as well as at 1 s means a store that writes on *every* commit rather than
on a timer also fails.

## STATE-002 — checkpoints keep being taken, at roughly the configured interval
**Intent:** `scheduleWithFixedDelay` is delay-between-completions, not fixed rate; the case pins the
observable count rather than a phase.
**Falsifier:** `stats().taken()` is still 1 after 2 s at a 200 ms interval, i.e. the schedule fired
once and stopped.
**Setup:** `H-REG`, but construct the `PeriodicCheckpointer` directly so `stats()` is reachable:
`new PeriodicCheckpointer(execution, new FileCheckpointStore(dir), Duration.ofMillis(200), 3,
Duration.ofSeconds(5), log::add)` over `H-WIN`'s execution. Feed the standard payload first.
**Steps:** 1. `start()`. 2. Sleep 2000 ms. 3. Read `stats()`.
**Expected:** `stats().taken()` is between `2000 / 200 - 2 = 8` and `2000 / 200 + 1 = 11`.
`stats().failed() == 0`. `log` contains at least 8 lines matching
`checkpoint <n> stored, <m> bytes`.
**Vacuity:** a single-shot `schedule` instead of `scheduleWithFixedDelay` gives `taken() == 1`, below
the floor of 8. Asserting a range rather than "> 0" is what makes the case non-vacuous — round 1's
equivalent would have passed on one checkpoint.

## STATE-003 — `checkpointNow()` takes exactly one checkpoint and returns it
**Intent:** the manual path, which every deterministic case below uses instead of the timer.
**Falsifier:** two files appear, or the returned `Checkpoint.id()` does not match the file written.
**Setup:** `H-WIN` + a directly constructed checkpointer, `interval=1h` so the timer never fires,
`keep=3`. Feed the standard payload. Do **not** call `start()`.
**Steps:** 1. `Checkpoint c = checkpointer.checkpointNow()`.
**Expected:** `c.id() == 1`. `dir` contains exactly one file, named `checkpoint-1.bin`.
`stats().taken() == 1`, `stats().failed() == 0`, `stats().pruned() == 0` (one file, `keep=3`, so
`1 - 3 < 0` and nothing is removed). `store.load(1)` is present and equals `c` field by field.
**Vacuity:** `interval=1h` with `start()` never called means the timer cannot be the thing that wrote
the file; only `checkpointNow` can have.

## STATE-004 — ids are monotonic within a run
**Intent:** `nextId.getAndIncrement()` (`:135`), and ids are how `latest()` decides what is newest.
**Falsifier:** any id repeats, or ids are not strictly increasing in call order.
**Setup:** `H-WIN` + direct checkpointer, `interval=1h`, `keep=10`.
**Steps:** 1. `checkpointNow()` five times, collecting the returned ids.
**Expected:** ids are exactly `[1, 2, 3, 4, 5]`. `availableIds()` is `[5, 4, 3, 2, 1]` — newest
first, `Comparator.reverseOrder()` at `FileCheckpointStore.java:127`.
**Vacuity:** `keep=10` and five checkpoints means pruning cannot be the reason an id is missing.

## STATE-005 — ids resume above the highest already on disk after a restart
**Intent:** the constructor does `store.availableIds().stream().max(...).ifPresent(h ->
nextId.set(h + 1))` (`PeriodicCheckpointer.java:112`), so "checkpoint 4" means one thing for the life
of the directory. Reusing ids would have `latest()` pick an old file over a new one.
**Falsifier:** the first checkpoint after the restart is `checkpoint-1.bin`, overwriting history.
**Setup:** `H-CS` seeded with `store.store(cp(7))` and `store.store(cp(9))`, then a fresh
`PeriodicCheckpointer` over `H-WIN`'s execution and that store, `interval=1h`, `keep=10`.
**Steps:** 1. `checkpointNow()`.
**Expected:** the returned id is `9 + 1 = 10`. `availableIds()` is `[10, 9, 7]`.
**Vacuity:** without the resume line the id would be `1`, and `availableIds()` would be `[9, 7, 1]`,
which is a different list — the assertion distinguishes them.

## STATE-006 — the checkpoint thread is a daemon and is named `pravaha-checkpointer`
**Intent:** `PeriodicCheckpointer.java:139`–`:145` sets both. A non-daemon scheduler would keep a JVM
alive after `main` returns, which for a CLI that registers and exits is a hang with no message.
**Falsifier:** a thread dump after `start()` shows no thread named `pravaha-checkpointer`, or shows
one with `isDaemon() == false`.
**Setup:** `H-WIN` + direct checkpointer, `interval=200ms`.
**Steps:** 1. `start()`. 2. Sleep 300 ms. 3. Walk `Thread.getAllStackTraces().keySet()`.
**Expected:** exactly one thread whose name is `pravaha-checkpointer`; its `isDaemon()` is `true`.
**Vacuity:** the sleep guarantees the scheduler has actually created its thread — `Executors`
creates lazily on first submit, so asserting immediately after construction would pass for the wrong
reason.

## STATE-007 — `close()` stops the schedule
**Intent:** `close()` sets `running=false` and calls `scheduler.shutdownNow()` (`:176`). A
checkpointer that keeps firing after its query is gone writes state for a computation that no longer
exists.
**Falsifier:** `stats().taken()` increases after `close()`.
**Setup:** `H-WIN` + direct checkpointer, `interval=100ms`, `keep=100`.
**Steps:** 1. `start()`. 2. Sleep 500 ms. 3. `close()`. 4. Record `n = stats().taken()`. 5. Sleep
1000 ms. 6. Record `m = stats().taken()`.
**Expected:** `n` is between `500 / 100 - 2 = 3` and `500 / 100 + 1 = 6`. `m == n` exactly — 1000 ms
at a 100 ms interval would have added about 10 more had the schedule survived.
**Vacuity:** the 1000 ms window is ten intervals wide, so "no increase" cannot be luck. `keep=100`
stops pruning masking the file count.

## STATE-008 — `start()` twice does not start two schedules
**Intent:** `running.compareAndSet(false, true)` guards it (`:124`). Two schedules would double the
rate and interleave ids.
**Falsifier:** `stats().taken()` after 1 s at 200 ms is near `2 × 5 = 10` rather than near 5.
**Setup:** `H-WIN` + direct checkpointer, `interval=200ms`, `keep=100`.
**Steps:** 1. `start()`. 2. `start()` again. 3. Sleep 1000 ms. 4. Read `stats()`.
**Expected:** `stats().taken()` is between `1000 / 200 - 2 = 3` and `1000 / 200 + 1 = 6`, not between
8 and 11. Exactly one thread named `pravaha-checkpointer` exists.
**Vacuity:** the two expected ranges do not overlap, so the assertion actually separates the two
behaviours.

## STATE-009 — `keep` below 1 is refused at construction, with a reason
**Intent:** `PeriodicCheckpointer.java:96` throws rather than clamping. Keeping zero checkpoints is a
configuration that silently makes recovery impossible.
**Falsifier:** `new PeriodicCheckpointer(..., keep=0, ...)` constructs.
**Setup:** `H-CS` and any `QueryExecution`.
**Steps:** 1. Construct with `keep = 0`. 2. Construct with `keep = -1`.
**Expected:** both throw `IllegalArgumentException`. The message for `keep=0` contains
`at least one checkpoint must be kept, asked to keep 0` and
`Keeping none means every restart starts from nothing`.
**Vacuity:** not a stateful case; no vacuity clause required.

## STATE-010 — a non-positive interval is refused at construction
**Intent:** `:100`. `scheduleWithFixedDelay` with a zero delay spins a core; with a negative one it
throws from inside the scheduler, far from the configuration that caused it.
**Falsifier:** any of the three constructs.
**Setup:** as STATE-009.
**Steps:** 1. `interval = null`. 2. `interval = Duration.ZERO`. 3. `interval =
Duration.ofSeconds(-1)`.
**Expected:** all three throw `IllegalArgumentException` whose message contains
`checkpoint interval must be positive, got` followed by the value (`null`, `PT0S`, `PT-1S`).
**Vacuity:** n/a.

## STATE-011 — `pravaha.checkpoint.*` is read exactly as `from()` says
**Intent:** `PeriodicCheckpointer.from` reads three keys and defaults each (`:118`–`:125`).
`application.yaml` documents `directory`, `interval` and `keep` and **does not mention
`pravaha.checkpoint.timeout`**, which the code reads. That gap is the case.
**Falsifier:** a configuration setting `interval: 250ms` produces checkpoints one minute apart, or
`timeout` set in YAML has no effect on the abandonment deadline.
**Setup:** `H-WIN`. Build three checkpointers with `PeriodicCheckpointer.from`:
(a) empty configuration; (b) `interval=250ms, keep=2, timeout=7s`; (c) `keep=2` only.
**Steps:** 1. For each, `start()`, sleep 1200 ms, read `stats()` and `availableIds()`.
**Expected:** (a) `taken() == 0` — the default interval is `DEFAULT_INTERVAL = 1m` and 1200 ms is
less than one minute. (b) `taken()` between `1200 / 250 - 2 = 2` and `1200 / 250 + 1 = 5`, and
`availableIds().size() == 2`. (c) `taken() == 0`, because only `keep` was overridden and the interval
is still 1 m. Separately: `grep -n 'timeout' pravaha-server/src/main/resources/application.yaml`
under the `checkpoint:` block returns **nothing**, which is the documentation defect this case
records.
**Vacuity:** (a) and (c) are the controls — they show the 1200 ms window is genuinely shorter than
the default interval, so (b)'s count cannot come from a default schedule.

## STATE-012 — a checkpoint is taken while rows are arriving, and does not lose or duplicate them
**Intent:** the snapshot runs as a lane control task between batches (`QueryExecution.java:700`).
Taking it mid-batch would capture an operator that has seen part of a batch and an offset past all
of it.
**Falsifier:** the view's total after the checkpoint is not the sum of everything fed, or the query
reports a lane failure.
**Setup:** `H-WIN` + direct checkpointer, `interval=1h`, `keep=10`. A feeder thread pushing
`feedAt("u1", 1, t = n × 1_000_000L)` for `n = 0 .. 4999`, all inside window `[0s,10s)` because
`4999 ms < 10 s`.
**Steps:** 1. Start the feeder. 2. Call `checkpointNow()` five times, 50 ms apart, while it runs.
3. Join the feeder. 4. `w.awaitApplied(10s)`, `w.commit()`. 5. Read the view.
**Expected:** the open window for `u1` holds `5000 × 1 = 5000`. `execution.checkHealth()` does not
throw. Five files exist minus pruning: `availableIds().size() == 5` (`keep=10`).
**Vacuity:** 5000 single-weight rows into one key means a lost or double-counted row moves the total
by exactly 1 and is visible; a count-of-rows assertion would not have been — round 1's 200 000 rows
collapsing into 500 keys is the failure this shape avoids. Checking `checkHealth()` separately stops
a dead lane presenting as a merely smaller total.

### B. Pruning: to `keep`, never below one (STATE-013–022)

`prune` was written and called only from its own test until `PeriodicCheckpointer` existed. Retention
here is counted, not timed, and the reason is written into the class: an age rule deletes the last
fallback precisely when nothing is happening.

## STATE-013 — with `keep=3` and six checkpoints, the newest three survive
**Intent:** the base pruning contract, by id rather than by mtime.
**Falsifier:** four files remain, or the surviving set is not `{4, 5, 6}`.
**Setup:** `H-CS`. `store.store(cp(1))` .. `store.store(cp(6))`.
**Steps:** 1. `int removed = store.prune(3)`. 2. `store.availableIds()`.
**Expected:** `removed == 6 - 3 = 3`. `availableIds()` is exactly `[6, 5, 4]`. `dir` contains exactly
three files: `checkpoint-6.bin`, `checkpoint-5.bin`, `checkpoint-4.bin`. `store.load(1)`,
`load(2)`, `load(3)` are all `Optional.empty()`.
**Vacuity:** asserting the surviving *set* and not just the count is what rules out "kept the oldest
three", which a count-only assertion passes.

## STATE-014 — pruning is by id, not by file modification time
**Intent:** `prune` sorts `availableIds()`, which parses the id out of the filename
(`FileCheckpointStore.java:124`–`:128`). A restored-from-backup directory has mtimes in any order.
**Falsifier:** the surviving set follows mtime.
**Setup:** `H-CS`. Store ids in the order `5, 1, 4, 2, 3` so that mtime order and id order disagree:
by mtime the newest three are `4, 2, 3`; by id they are `5, 4, 3`.
**Steps:** 1. `store.prune(3)`. 2. `availableIds()`.
**Expected:** `[5, 4, 3]`. Not `[4, 3, 2]`.
**Vacuity:** the two candidate answers differ in two of three elements, so the case cannot pass under
the wrong rule.

## STATE-015 — `prune(keep)` with fewer than `keep` files removes nothing
**Intent:** `for (int i = keep; i < ids.size(); i++)` (`:184`) never runs when `ids.size() <= keep`.
**Falsifier:** a file disappears, or `removed` is non-zero.
**Setup:** `H-CS` with ids `1, 2`.
**Steps:** 1. `store.prune(3)`.
**Expected:** returns `0`. `availableIds()` is `[2, 1]`, unchanged.
**Vacuity:** n/a (no timing); the before/after id lists are compared explicitly.

## STATE-016 — `prune(0)` is refused, and refused *before* deleting anything
**Intent:** `FileCheckpointStore.java:180` throws `IllegalArgumentException`. The order matters: the
check is the first statement, so nothing has been deleted when it throws.
**Falsifier:** `prune(0)` empties the directory, or returns 0 quietly.
**Setup:** `H-CS` with ids `1, 2, 3`.
**Steps:** 1. `store.prune(0)`. 2. `store.prune(-5)`. 3. `availableIds()`.
**Expected:** both throw `IllegalArgumentException` containing
`at least one checkpoint must be kept, asked to keep 0` / `asked to keep -5`. After both,
`availableIds()` is still `[3, 2, 1]` and all three files load.
**Vacuity:** step 3 is the whole point — an implementation that deleted two files and then threw
would pass a throws-only assertion.

## STATE-017 — `keep=1` keeps exactly one, which is the newest
**Intent:** the documented floor. `PeriodicCheckpointer` allows `keep=1`; the javadoc warns it is one
chance at recovery, and that is a choice a deployment may make.
**Falsifier:** zero files remain, or the survivor is not the highest id.
**Setup:** `H-CS` with ids `10, 11, 12`.
**Steps:** 1. `store.prune(1)`.
**Expected:** returns `12 - ... ` — three files, keep one, so `3 - 1 = 2` removed. `availableIds()`
is `[12]`. `load(12)` is present; `load(11)` and `load(10)` are empty.
**Vacuity:** n/a.

## STATE-018 — the checkpointer prunes after every checkpoint, and the count accumulates
**Intent:** `checkpointNow` calls `store.prune(keep)` on every pass and adds to `pruned`
(`PeriodicCheckpointer.java:138`–`:142`).
**Falsifier:** `availableIds().size()` grows past `keep`, or `stats().pruned()` stays 0 while files
disappear.
**Setup:** `H-WIN` + direct checkpointer, `interval=1h`, `keep=2`.
**Steps:** 1. `checkpointNow()` six times.
**Expected:** after call 1: 1 file, `pruned()==0`. After 2: 2 files, `pruned()==0`. After 3: 2 files
(`[3,2]`), `pruned()==1`. After 4: `[4,3]`, `pruned()==2`. After 5: `[5,4]`, `pruned()==3`. After 6:
`[6,5]`, `pruned()==4`. Final: `taken()==6`, `pruned() == 6 - 2 = 4`.
**Vacuity:** the per-call ladder means a store that pruned only at the end, or only once, diverges at
call 3 rather than at the last assertion.

## STATE-019 — pruning never removes the checkpoint `latest()` would return
**Intent:** the invariant the two methods have to share. `prune` keeps the highest ids; `latest`
walks the highest ids first.
**Falsifier:** after a prune, `latest()` is empty while files exist, or returns an id that is not
`max(availableIds())`.
**Setup:** `H-CS`. A loop of 50 iterations: `store.store(cp(i))` then `store.prune(3)`, asserting
after each.
**Steps:** 1. Run the loop, `i = 1 .. 50`.
**Expected:** at every iteration `i >= 1`, `latest().get().id() == i` and `availableIds().size() ==
Math.min(i, 3)`. At `i = 50`: `availableIds()` is `[50, 49, 48]`.
**Vacuity:** 50 iterations rather than one means an off-by-one that only bites when the directory is
exactly at the boundary is caught; the `min(i,3)` formula is checked at `i=1,2,3` as well as at 50.

## STATE-020 — `deleteQuietly` swallows an undeletable file and pruning still reports it removed
**Intent:** `deleteQuietly` catches `IOException` and does nothing (`:191`–`:197`), while the loop
increments `removed` regardless. So `prune`'s return value is "files I tried to remove", not "files
that are gone". Worth pinning because an operator reading `stats().pruned()` will read it as the
latter.
**Falsifier:** `prune` throws, or returns a number equal to the files actually deleted.
**Setup:** `H-CS` with ids `1, 2, 3, 4`. Make the *directory* read-only and non-writable for the
owner: `chmod 0500 dir` (POSIX). `checkpoint-1.bin` then cannot be unlinked.
**Steps:** 1. `store.prune(3)`. 2. `availableIds()`. 3. Restore `chmod 0700 dir`.
**Expected:** `prune` returns `4 - 3 = 1` and does not throw. `availableIds()` is still `[4, 3, 2,
1]` — four ids, because the file is still there. The mismatch between the returned `1` and the
unchanged listing is the finding.
**Vacuity:** step 2 distinguishes "deleted" from "claimed deleted"; the return value alone cannot.
Skip on filesystems without POSIX permissions and say so rather than passing.

## STATE-021 — a foreign file in the checkpoint directory does not confuse pruning
**Intent:** `availableIds()` filters on `checkpoint-` prefix and `.bin` suffix before parsing.
**Falsifier:** `README.txt` is deleted, or counted toward `keep`.
**Setup:** `H-CS` with ids `1, 2, 3, 4`, plus `dir/README.txt`, `dir/checkpoint-9.tmp` (a leftover
temporary from an interrupted `store`) and `dir/notes.bin`.
**Steps:** 1. `availableIds()`. 2. `store.prune(2)`. 3. List `dir`.
**Expected:** step 1 is `[4, 3, 2, 1]` — the three foreign names match neither filter.
Step 2 returns `4 - 2 = 2`. Step 3 lists `checkpoint-4.bin`, `checkpoint-3.bin`, `README.txt`,
`checkpoint-9.tmp`, `notes.bin` — five entries. The stale `.tmp` is **not** cleaned up by pruning,
which is worth recording: an interrupted `store` leaks one file per interruption for ever.
**Vacuity:** n/a.

## STATE-022 — a checkpoint file with a non-numeric id makes the whole directory unusable
**Intent:** `availableIds()` does `Long.parseLong(name.substring(...))` with no guard
(`FileCheckpointStore.java:126`). A file called `checkpoint-old.bin` — exactly what an operator
renames one to before deleting it — matches both filters and fails to parse. The exception escapes
`availableIds()`, and therefore escapes `latest()`, `prune()` and `PeriodicCheckpointer`'s
constructor.
**Falsifier:** the store skips the file and keeps working.
**Setup:** `H-CS` with ids `1, 2, 3`, plus a copy of `checkpoint-3.bin` named `checkpoint-old.bin`.
**Steps:** 1. `store.availableIds()`. 2. `store.latest()`. 3. `store.prune(2)`. 4. Construct
`new PeriodicCheckpointer(execution, store, Duration.ofSeconds(1), 3, Duration.ofSeconds(5), log)`.
**Expected:** all four throw `NumberFormatException` with message
`For input string: "old"`. In particular step 4 means a **restart of a node whose checkpoint
directory contains one such file fails to construct the checkpointer**, and since
`startCheckpointing` is called from inside `QueryRegistry.register`
(`QueryRegistry.java:549`), the registration itself fails — on `H-SRV` that is a query the journal
replays and `recover` then lists under `refused`.
**Vacuity:** the three good files are present throughout, so "no checkpoints" cannot explain the
failure; the store is unusable *because of* the fourth name.

### C. A directory per query, and names that fight over it (STATE-023–035)

`QueryRegistry.checkpointingTo` gives each computation its own store, because one shared store would
make pruning global and a busy query would evict a quiet one's only fallback. The name reaches the
filesystem, so it is encoded rather than trusted: `checkpointDirectoryFor`
(`QueryRegistry.java:455`–`:473`) keeps `[A-Za-z0-9-]` and rewrites every other byte as `_` plus two
lowercase hex digits. It is injective by construction, which the comment says was arrived at after a
`_`-substitution scheme collided on `a#!b` and `a"@b`.

Two things gate what can reach it. `requireSayableName` (`:745`) demands
`[A-Za-z_][A-Za-z0-9_]*` *and* that Calcite can parse `SELECT 1 FROM <name>`. So `..`, `.`, `a.b`,
`a b` and `a#!b` cannot arrive through `register` at all. The encoder cases below therefore reach
`checkpointDirectoryFor` by reflection, and STATE-031 establishes that the public door is shut.

## STATE-023 — each registered computation gets its own directory, created at registration
**Intent:** the per-query store, and that it exists before the first checkpoint (the
`FileCheckpointStore` constructor calls `Files.createDirectories`).
**Falsifier:** two queries share one directory, or no directory appears until the first checkpoint.
**Setup:** `H-REG` over `H-WIN`'s registry, `interval=1h` so no checkpoint fires. Register
`w` (`TUMBLE(ts, INTERVAL '10' SECOND)`) and `w2` with *different* SQL (`INTERVAL '20' SECOND`) so
the fingerprints differ and they are two computations.
**Steps:** 1. Register both. 2. List `root`.
**Expected:** `root` contains exactly two directories, named `w` and `w2` — both are already
`[A-Za-z0-9-]`-clean, so the encoding is the identity. Both are empty of `checkpoint-*.bin`.
**Vacuity:** using different SQL rather than different names is what makes them two computations; two
names on one fingerprint would share one directory legitimately (STATE-035).

## STATE-024 — the directory name is the encoding, not the raw name
**Intent:** pin the encoding on a name that survives `requireSayableName` and still contains a byte
the encoder rewrites: the underscore.
**Falsifier:** the directory is literally `my_view`.
**Setup:** `H-REG`, `interval=1h`. Register `my_view`.
**Steps:** 1. List `root`.
**Expected:** one directory named `my_5fview` — `m`,`y` pass through, underscore is byte `0x5f` so it
becomes `_5f`, then `v`,`i`,`e`,`w`. Not `my_view`, and not `myview`.
**Vacuity:** underscore is the only non-alphanumeric character `requireSayableName` permits, so this
is the only name shape that can distinguish encoded from raw through the public API.

## STATE-025 — `q_1` and `q1` do not share a directory
**Intent:** the encoder's own stated reason for encoding underscore as well: otherwise `a_b` and
`a b` would still meet. Through the public API the reachable version of that collision is a name
containing an underscore against the name with the underscore removed.
**Falsifier:** one directory exists after registering both, or the two directories are the same path.
**Setup:** `H-REG`, `interval=1h`. Register `q_1` and `q1`, with different SQL so they are two
computations.
**Steps:** 1. Register both. 2. List `root`.
**Expected:** two directories: `q_5f1` (`q`, underscore to `_5f`, `1`) and `q1`. They differ.
**Vacuity:** if underscore were passed through unencoded the first would be `q_1`, still distinct from
`q1` — so this case alone does not prove the encoder injective. STATE-027 does that; this one proves
the observable separation the product depends on.

## STATE-026 — `Q` and `q` are two directories on a case-sensitive filesystem and one on a case-insensitive one
**Intent:** `requireSayableName` permits both cases and `byName` is a case-sensitive map, so `Q` and
`q` are two registrations. The encoder preserves case, so they are `Q/` and `q/`. On APFS with the
default settings, or on NTFS, those are the **same directory** — two computations sharing one
checkpoint id sequence, each pruning the other's fallbacks. The encoder cannot fix this; recording it
is the point.
**Falsifier:** on Linux/ext4, one directory. On macOS default APFS, two.
**Setup:** `H-REG`, `interval=1h`. Register `Q` and `q` with different SQL.
**Steps:** 1. Register both. 2. `Files.list(root).count()`. 3. Force a checkpoint on each by calling
`checkpointNow()` on each query's checkpointer. 4. List each directory.
**Expected:** **on a case-sensitive filesystem (Linux, ext4/xfs)** — step 2 is `2`; step 4 shows
`Q/checkpoint-1.bin` and `q/checkpoint-1.bin` with independent id sequences.
**On a case-insensitive one (macOS APFS default, Windows NTFS)** — step 2 is `1`. Step 3's
second `checkpointNow()` writes `checkpoint-1.bin` into the same directory as the first and its
`store` call **replaces** it (`StandardCopyOption.REPLACE_EXISTING`, `FileCheckpointStore.java:107`),
so one computation's checkpoint is silently overwritten by the other's. Record which platform was
used.
**Vacuity:** the two expected results differ in the directory count, so the case reports a fact about
the platform rather than passing on either.

## STATE-027 — the encoder is injective across a hostile corpus
**Intent:** the comment claims injectivity and says a hash-suffix scheme was tried and collided. Prove
the claim rather than trusting it.
**Falsifier:** any two distinct inputs produce the same output.
**Setup:** reflective access to `QueryRegistry.checkpointDirectoryFor(String)` (`private static`).
Corpus, 24 entries: the empty string, `.`, `..`, `a`, `A`, `a.b`, `a_b`, `a b`, `a-b`, `ab`,
`a#!b`, `a"@b`, `../../etc/passwd`, `..\..\etc`, `a/b`, `a\b`, `a%2eb`, `a_2eb`, `q`, `q1`,
`q_1`, `q-1`, `é`, and a single space.
**Steps:** 1. Encode all 24. 2. Put them in a `Set`.
**Expected:** the set has 24 elements. Specific values, hand-computed from the rule (keep
`[A-Za-z0-9-]`, else underscore plus lowercase hex of the UTF-8 byte):

| name | directory |
|---|---|
| empty string | empty string |
| `.` | `_2e` |
| `..` | `_2e_2e` |
| `a.b` | `a_2eb` |
| `a_b` | `a_5fb` |
| `a b` | `a_20b` |
| `a-b` | `a-b` |
| `a#!b` | `a_23_21b` |
| `a"@b` | `a_22_40b` |
| `../../etc/passwd` | `_2e_2e_2f_2e_2e_2fetc_2fpasswd` |
| `a%2eb` | `a_252eb` |
| `a_2eb` | `a_5f2eb` |
| `é` (U+00E9, UTF-8 `c3 a9`) | `_c3_a9` |
| single space | `_20` |

Note `a_2eb` in column 2 arises from input `a.b`, and input `a_2eb` produces `a_5f2eb` — the escape
character is itself escaped, which is why the scheme is injective and a hash-suffix scheme was not.
**Vacuity:** the corpus deliberately contains the pair (`a.b`, `a_2eb`) whose images would collide
under any scheme that does not escape the escape, so a broken encoder fails here rather than passing
on 24 unrelated strings.

## STATE-028 — `..` and `.` cannot escape the checkpoint root
**Intent:** the stated purpose: "a query named `..` cannot write above the configured root".
**Falsifier:** `root.resolve(checkpointDirectoryFor(".."))` normalises to `root`'s parent.
**Setup:** reflection as STATE-027, `root = /tmp/ckroot/a/b`.
**Steps:** 1. For each of `.`, `..`, `../..`, `/etc`, `a/../..`: compute
`root.resolve(encoded).normalize()`.
**Expected:** every result starts with `/tmp/ckroot/a/b/` and has exactly one path element beyond it.
For `..` that element is `_2e_2e`; for `../..` it is `_2e_2e_2f_2e_2e`; for `/etc` it is `_2fetc`
(the leading separator is encoded, so `resolve` cannot treat it as absolute). `normalize()` changes
nothing, because no result contains a literal `.` or `..` element.
**Vacuity:** `normalize()` is applied after `resolve`, which is where a traversal would show up; a
case that only compared strings would miss an absolute-path resolve.

## STATE-029 — two names that sanitise alike keep separate directories and do not prune each other
**Intent:** the concrete harm the encoder prevents, demonstrated rather than argued: two stores on
one directory share an id sequence and `prune(keep)` counts across both.
**Falsifier:** with separate directories, one query's checkpoints disappear when the other prunes.
**Setup:** two `FileCheckpointStore`s. Arm A: both on the *same* directory `shared/`. Arm B: on
`enc("a.b") = a_2eb` and `enc("a_b") = a_5fb`.
**Steps:** For each arm: 1. Store ids 1, 2, 3 through store X. 2. Store ids 4, 5, 6 through store Y.
3. `X.prune(3)`. 4. `X.availableIds()` and `Y.availableIds()`.
**Expected:** **Arm A** — after step 2 the single directory holds `[6,5,4,3,2,1]`. `X.prune(3)`
returns `6 - 3 = 3` and leaves `[6, 5, 4]`: X has deleted **all three** of its own checkpoints and
kept all three of Y's. X now has zero fallbacks. **Arm B** — `X.availableIds()` is `[3, 2, 1]` before
and `[3, 2, 1]` after (`3 - 3 = 0` removed); `Y.availableIds()` is `[6, 5, 4]`, untouched.
**Vacuity:** Arm A is the control that shows the shared-directory failure is real and not theoretical,
so Arm B's pass is meaningful. Using disjoint id ranges (1–3 vs 4–6) means "X's checkpoints" is
decidable from the listing alone.

## STATE-030 — a unicode view name is refused before it reaches the filesystem
**Intent:** `requireSayableName`'s regex is ASCII-only (`[A-Za-z_][A-Za-z0-9_]*`), so `café` never
gets a directory. Worth a case because the encoder handles UTF-8 bytes and an executor may expect it
to be reachable.
**Falsifier:** a directory named `caf_c3_a9` appears under `root`.
**Setup:** `H-REG`.
**Steps:** 1. `registry.register("café", "SELECT user_id, amount FROM txn", List.of(0), DANA)`.
2. List `root`.
**Expected:** step 1 throws `PravahaException` with code **PRV-8001** (`REGISTRY_NAME_IN_USE` — the
code is reused for "not a sayable name", which ERRC should note) and a message containing
`cannot be used as a view name: a name is written in a FROM clause, so it must be a plain identifier`.
Step 2 lists zero new directories.
**Vacuity:** step 2 rules out the query being refused *after* `startCheckpointing` had already
created the directory — the ordering in `register` (`requireName` at `:274`, `startCheckpointing` at
`:549`) is what the listing confirms.

## STATE-031 — hostile names are refused at the public door, so the encoder is defence in depth
**Intent:** enumerate the refusals, so that the encoder cases above are understood as second-line and
not as the only guard. Also pins one ordering defect: `requireName` calls `requireSayableName(name)`
*before* its own null check (`:768`–`:770`), so a null name is an NPE rather than the intended
message.
**Falsifier:** any of these registers successfully, or a directory appears for one.
**Setup:** `H-REG`.
**Steps:** register each of: `..`, `.`, `a.b`, `a b`, `a#!b`, `../../etc`, `1q`, `q-1`, the empty
string, three spaces, `null`, `select`, `from`.
**Expected:** `..`, `.`, `a.b`, `a b`, `a#!b`, `../../etc`, `1q`, `q-1`, the empty string and the
three spaces all throw `PravahaException` PRV-8001 with the `cannot be used as a view name` message —
`1q` because a name must start with a letter or underscore, `q-1` because the hyphen is not in the
character class, and the two blank forms because they do not match at all. `select` and `from` match
the regex and are refused by the Calcite check with
`is a reserved word in SQL, so no query could read the view.` `null` throws
**`NullPointerException`** from `name.matches(...)`, *not* the `a registration needs a name`
`IllegalArgumentException` the line below it intends — record this. After all thirteen, `root`
contains zero directories.
**Vacuity:** the final listing is the non-vacuity: a refusal that happened after the directory was
created would leave evidence.

## STATE-032 — a hand-edited journal naming `../../etc` is refused at replay, not traversed
**Intent:** the journal is a file an operator can edit and a backup can carry. `recover` goes through
`registerWithoutJournalling` to `register` to `requireName`, so the same gate applies — but that is a
property to verify, not assume, because it is the one path where a name arrives from disk rather than
from a client.
**Falsifier:** a directory appears outside `root`, or the entry recovers.
**Setup:** write a journal by hand with
`new RegistryJournal(f).recordRegistration("../../etc", "SELECT user_id, amount FROM txn",
List.of(0), "dana", Retention.DEFAULT, List.of())`. Note `RegistryJournal.recordRegistration` itself
validates nothing. Then a fresh registry with `journalTo(f)` and `checkpointingTo(root, cfg)`.
**Steps:** 1. `Recovery r = registry.recover(id -> Optional.of(DANA))`. 2. List `root` and
`root.getParent().getParent()`.
**Expected:** `r.recovered()` is empty. `r.refused()` has one entry whose text begins
`../../etc: ` and contains `cannot be used as a view name`. `r.complete()` is `false`.
`root` contains zero directories; nothing was written above it.
**Vacuity:** `recordRegistration` accepting the name proves the journal is not the gate, so the
refusal must come from `register` — which is the thing being tested.

## STATE-033 — the checkpoint root is created if absent, and a file where it should be is a hard failure
**Intent:** `FileCheckpointStore`'s constructor throws `UncheckedIOException("cannot create the
checkpoint directory " + directory)` (`:65`). That happens inside `register`, so it fails the
registration.
**Falsifier:** registration succeeds and checkpoints are silently not written.
**Setup:** `H-REG` with `root = tmp/ck`. Arm A: `tmp/ck` does not exist. Arm B: `tmp/ck` exists as a
regular **file**. Arm C: `tmp/ck` is a directory with mode `0500`.
**Steps:** for each arm, register `w`, then list.
**Expected:** **A** — `register` succeeds, `tmp/ck/w/` exists. **B** — `register` throws
`UncheckedIOException` whose message contains `cannot create the checkpoint directory` and the path
`tmp/ck/w`; `registry.names()` does not contain `w`. **C** — same failure (cannot create a child in a
non-writable directory), same message prefix. Restore `0700` afterwards.
**Vacuity:** arm A is the control; without it, B and C could be failing for a reason unrelated to the
root.

## STATE-034 — dropping a query deletes its checkpoint directory
**Intent:** `deleteCheckpointsOf` (`QueryRegistry.java:448`) runs when the last name is removed. The
comment records the failure it fixes: "52 directories for 2 live queries, in a QA run of 50
register/drop cycles."
**Falsifier:** `root/w` still exists after `drop("w")`.
**Setup:** `H-REG` over `H-WIN`, `interval=1h`. Register `w`; force three checkpoints with the
query's checkpointer via `checkpointNow()`.
**Steps:** 1. Confirm `root/w/` holds `checkpoint-1.bin` through `checkpoint-3.bin`. 2.
`registry.drop("w")`. 3. List `root`.
**Expected:** step 3 lists zero entries. Both the three files and the directory itself are gone
(`Files.deleteIfExists` per entry, then on the directory, `:453`–`:457`).
**Vacuity:** step 1 establishes the files existed, so an empty listing at step 3 is a deletion and not
an absence.

## STATE-035 — dropping the last name of a *shared* computation deletes the wrong directory
**Intent:** `startCheckpointing(name, ...)` is called with the name that **created** the computation
(`:549`), so the directory is `enc(firstName)`. `drop(name)` calls
`deleteCheckpointsOf(name)` with whichever name happened to be dropped last (`:711`). When a second
registration shares the fingerprint, those are different names — so the drop removes a directory that
was never created and leaks the one that was. This is the same accumulation the comment at `:707`
says was fixed.
**Falsifier:** after dropping both names, `root` is empty.
**Setup:** `H-REG` over `H-WIN`, `interval=1h`. Register `alpha` with the windowed SQL. Register
`beta` with **byte-identical** SQL, same key columns, same principal — `QueryRegistry.register`
resolves it to the same `RegisteredQuery` via `byFingerprint` (`:330`–`:334`). Force three
checkpoints.
**Steps:** 1. List `root`. 2. `registry.drop("alpha")`. 3. List `root`. 4. `registry.drop("beta")`.
5. List `root`.
**Expected:** step 1 lists exactly one directory, `alpha`, holding three files — `beta` never got a
checkpointer because no new computation was created. Step 3 still lists `alpha` with three files:
`removeName("alpha")` returns `false` (one name left), so nothing is deleted. Step 5 lists **`alpha`,
still holding three files** — `deleteCheckpointsOf("beta")` resolved `root/beta`, which does not
exist, `Files.list` threw `NoSuchFileException`, and the `catch (IOException)` at `:458` swallowed it.
The computation is closed and its checkpoint directory outlives it.
**Vacuity:** step 1 proves only one directory was ever made, and it is named for the first
registrant; without that, step 5's leftover could be mistaken for a directory `beta` that was
correctly kept. Repeat the register/drop pair 50 times to reproduce the "52 directories" shape: expect
50 leaked directories, one per pair.

### D. Permissions, atomicity, and what "durable" actually means (STATE-036–042)

`SensitiveFiles` exists because "three files in this engine contain what customers would call their
data, and all three were created at whatever the process umask happened to be". Checkpoints are one
of the three: serialised operator state *is* the aggregated data.

## STATE-036 — a checkpoint file is mode 0600
**Intent:** `FileCheckpointStore.store` calls `SensitiveFiles.createOwnerOnly(temporary)` **before**
the first byte is written (`:86`), and `Files.move` preserves the mode. A file that is
world-readable for the duration of one write has been world-readable.
**Falsifier:** `checkpoint-1.bin` has any group or other bit set.
**Setup:** `H-CS` on a POSIX filesystem. `umask 0022` in the launching shell, so the default would be
`rw-r--r--`.
**Steps:** 1. `store.store(cpState(1, "lane-0", new byte[] {1,2,3}))`. 2.
`Files.getPosixFilePermissions(dir.resolve("checkpoint-1.bin"))`.
**Expected:** exactly `{OWNER_READ, OWNER_WRITE}` — the string form `rw-------`, octal `0600`. Not
`rw-r--r--`.
**Vacuity:** setting `umask 0022` first is what makes the assertion meaningful: under `umask 0077`
the file would be `0600` whether or not `createOwnerOnly` ran. Skip with a recorded reason on a
filesystem whose `supportedFileAttributeViews()` lacks `posix`, rather than passing.

## STATE-037 — the checkpoint directory is mode 0700
**Intent:** `createOwnerOnly` narrows the parent to `rwx------` too (`SensitiveFiles.java:63`). A
directory that can be listed leaks the query names and the checkpoint cadence even if the files
cannot be read.
**Falsifier:** `root/w` is `rwxr-xr-x`.
**Setup:** `H-REG` under `umask 0022`, `interval=1h`. Register `w`, then `checkpointNow()`.
**Steps:** 1. `Files.getPosixFilePermissions(root.resolve("w"))`.
**Expected:** `rwx------`, octal `0700`. Note the ordering: the `FileCheckpointStore` constructor
creates the directory with `Files.createDirectories` and does **not** narrow it; the narrowing
happens on the first `store()` via `createOwnerOnly`'s parent handling. So between registration and
the first checkpoint the directory is at the umask — assert that too: immediately after `register`
and before `checkpointNow`, expect `rwxr-xr-x` under `umask 0022`. That window is the finding.
**Vacuity:** the two observations, before and after the first checkpoint, cannot both come from the
same code path, so the case distinguishes "narrowed at creation" from "narrowed at first write".

## STATE-038 — a checkpoint is published by rename and is never readable half-written
**Intent:** the mechanism the class is built on: "written to a temporary name and renamed, the file
appears complete or not at all".
**Falsifier:** a reader listing the directory during a `store()` sees a `checkpoint-N.bin` that
`load(N)` returns empty for.
**Setup:** `H-CS`. A checkpoint with a large payload so the write is not instantaneous:
`cpState(1, "lane-0", new byte[64 * 1024 * 1024])` (64 MiB). A reader thread in a tight loop calling
`store.availableIds()` and, for every id it sees, `store.load(id)`.
**Steps:** 1. Start the reader. 2. `store.store(c)`. 3. Stop the reader. 4. Inspect what the reader
recorded.
**Expected:** the reader saw either no id, or id `1` with `load(1)` present and
`operatorState().get("lane-0").length == 64 * 1024 * 1024`. It never saw id `1` with `load(1)`
empty. Separately, during the write a file named `checkpoint-1.tmp` exists and is **not** matched by
`availableIds()` (the suffix filter is `.bin`).
**Vacuity:** 64 MiB is chosen so the write window is milliseconds rather than microseconds; with a
3-byte payload the race is unobservable and the case would pass without testing anything.

## STATE-039 — `store()` returns before the bytes are on the disk, contrary to the interface's contract
**Intent:** `CheckpointStore.store`'s javadoc says "Returns only once it is durable and complete."
The implementation writes through `Files.newOutputStream`, closes, and renames. **There is no
`channel.force(true)` on the file and no `SensitiveFiles.syncDirectory` on the parent** —
`FileCheckpointStore.java:87`–`:108`. `RegistryJournal` does both for its journal (`:230` and
`:265`), so the omission is a difference between two files in the same repository rather than a
house style.
**Falsifier:** a `strace`/`dtruss` of one `store()` shows an `fsync` or `fdatasync` on either the
file descriptor or the directory.
**Setup:** `H-CS`. Run under `strace -f -e trace=fsync,fdatasync,openat,renameat,renameat2`.
**Steps:** 1. `store.store(cpState(1, "lane-0", new byte[4096]))`. 2. Read the syscall trace.
**Expected:** the trace contains `openat` on `checkpoint-1.tmp`, writes, `close`, and a
`renameat`/`renameat2` to `checkpoint-1.bin`. It contains **zero** `fsync` or `fdatasync` calls for
either path. Contrast: the same trace around `new RegistryJournal(f).recordDrop("x")` contains one
`fsync` on the journal fd (from `channel.force(true)`, `RegistryJournal.java:230`).
**Vacuity:** the journal comparison in the same trace is the control — it proves the tracing is
catching `fsync` calls that do happen, so their absence for the checkpoint is a fact about the code
and not about the tooling. Consequence to record: after a power loss the newest checkpoint may be
absent even though `store()` returned, which is exactly the failure the `keep > 1` rule is there to
absorb, so this degrades rather than breaks — but the javadoc promises more than the code delivers.

## STATE-040 — a truncated checkpoint is skipped and the previous one is used
**Intent:** the trailer, "written last and read first. Its presence is the file's own statement that
it finished being written."
**Falsifier:** `latest()` returns the truncated checkpoint, or throws.
**Setup:** `H-CS`. Store ids 1, 2, 3, each `cpState(id, "lane-0", new byte[256])`. Then truncate
`checkpoint-3.bin` to `length - 4` bytes, removing the final `MAGIC` of the trailer. Separately, an
arm truncating to `length - 12` (whole trailer gone) and an arm truncating to 30 bytes (mid-header).
**Steps:** 1. `store.load(3)`. 2. `store.latest()`. 3. `store.availableIds()`.
**Expected:** all three arms — `load(3)` is `Optional.empty()`; `latest()` is present with
`id() == 2`; `availableIds()` is still `[3, 2, 1]`, because the listing is by filename and the file
is still there. The `-4` arm exercises the trailer's `MAGIC` check, the `-12` arm exercises the
`EOFException` path through `catch (IOException)`, and the 30-byte arm fails inside the header.
**Vacuity:** id 2 exists and is complete, so `latest()` returning 2 proves a fall-back rather than an
absence; if only one checkpoint existed, `latest()` being empty would be indistinguishable from the
file being fine and the store being broken.

## STATE-041 — a file whose magic is wrong is skipped, not read
**Intent:** `load` returns empty when the first int is not `0x50525643`. Bytes that are not a
checkpoint must not be interpreted as counts and lengths.
**Falsifier:** `load` throws, hangs, or returns a `Checkpoint`.
**Setup:** `H-CS` with ids 1 and 2 stored normally. Then overwrite the first four bytes of
`checkpoint-2.bin` with `0x00000000`. A second arm writes a `checkpoint-4.bin` consisting of 4096
random bytes from a fixed seed (`new Random(20260909L)`).
**Steps:** 1. `load(2)`. 2. `load(4)`. 3. `latest()`.
**Expected:** `load(2)` and `load(4)` are both `Optional.empty()`. `latest()` returns id `1` in arm
one; in arm two it returns id `2` (the random file is id 4, is skipped, and 2 is the next highest).
No exception, no allocation of a huge array from a random length field.
**Vacuity:** the fixed seed makes the random arm reproducible, so a failure is a bug rather than an
unlucky byte pattern.

## STATE-042 — a checkpoint written by a different format version throws out of `latest()` instead of being skipped
**Intent:** `load` handles the two failure modes inconsistently. A bad magic returns
`Optional.empty()`; a wrong `FORMAT_VERSION` throws `IllegalStateException` (`:146`–`:149`). Since
`latest()` calls `load` in a loop with no `try`, one file from a future version makes the **entire
store unusable**, including the older readable checkpoints it was supposed to fall back to. That is
the opposite of the class's own stated principle.
**Falsifier:** `latest()` skips the version-2 file and returns the version-1 one.
**Setup:** `H-CS`. Store ids 1 and 2 normally (both `FORMAT_VERSION = 1`). Then patch bytes 4..7 of
`checkpoint-2.bin` — the version int — from `00 00 00 01` to `00 00 00 02`.
**Steps:** 1. `store.load(2)`. 2. `store.load(1)`. 3. `store.latest()`. 4. `store.prune(1)`.
**Expected:** `load(2)` throws `IllegalStateException` with the message
`checkpoint 2 is format version 2 and this engine reads 1. Refusing to guess at the difference.`
`load(1)` is present. `latest()` **throws the same exception** rather than returning id 1 — it never
reaches id 1 because the loop starts at the highest id. `prune(1)` succeeds (it does not call
`load`), and after it `latest()` works again and returns id 2's file... which still throws. Record
the asymmetry: a corrupt newest file is survivable, a *newer-format* newest file is not.
**Vacuity:** `load(1)` succeeding is the control — it proves an older, readable checkpoint exists and
that `latest()`'s failure is the version check and not an empty directory.

---

### E. A failed checkpoint is reported, every time (STATE-043–049)

"A query that has silently not checkpointed for six hours looks exactly like one that has." The class
reports on every failure rather than the first, and `QueryRegistry` routes the reports onto the query
rather than discarding them — the comment at `QueryRegistry.java:438`–`:442` records that it used to
discard them, which produced exactly the condition the design was avoiding.

## STATE-043 — a store failure does not stop the schedule, and is counted every time
**Intent:** `checkpointQuietly` catches `RuntimeException`, increments `failed`, logs, and returns —
the fixed-delay schedule continues (`PeriodicCheckpointer.java:148`–`:160`).
**Falsifier:** `stats().taken()` and `stats().failed()` both stop increasing after the first failure.
**Setup:** `H-WIN` + a `CheckpointStore` decorator whose `store()` throws
`new UncheckedIOException("disk full", new IOException("ENOSPC"))` for every call, and whose
`availableIds()` returns `List.of()`. `interval=100ms`, `keep=3`, a `List<String> log`.
**Steps:** 1. `start()`. 2. Sleep 1000 ms. 3. Read `stats()` and `log`.
**Expected:** `stats().taken() == 0` — `taken` is incremented only *after* `store.store` returns
(`:137`), so a failing store never counts as taken. `stats().failed()` is between
`1000 / 100 - 2 = 8` and `1000 / 100 + 1 = 11`. `log.size() == stats().failed()`, and every line
matches `checkpoint failed (<n> so far): disk full. Recovery will fall back to the newest stored
checkpoint, which is getting older`, with `<n>` running `1, 2, 3, ...` in order.
**Vacuity:** a ten-interval window and a monotonically increasing `<n>` in the message means a
report-once implementation fails at `log.size() == 1`; asserting only "the log is non-empty" would
not.

## STATE-044 — the failure is recorded on the query, where an operator asks about it
**Intent:** `startCheckpointing` passes `query::recordCheckpointFailure` as the log consumer
(`QueryRegistry.java:443`), and `RegisteredQuery` keeps the last message and a count.
**Falsifier:** `query.checkpointFailures() == 0` after failures have demonstrably occurred.
**Setup:** `H-REG` over `H-WIN`, `interval=100ms`. Make the store fail by making `root/w` read-only
(`chmod 0500`) immediately after `register` returns.
**Steps:** 1. Register `w`. 2. `chmod 0500 root/w`. 3. Sleep 1000 ms. 4. Read
`q.checkpointFailures()` and `q.lastCheckpointFailure()`. 5. Restore `chmod 0700`.
**Expected:** `checkpointFailures()` is at least 8. `lastCheckpointFailure()` is present and its text
contains `checkpoint failed (` and `cannot store checkpoint`. The count and the message come from
two different fields (`RegisteredQuery.java:396`–`:397`), so both are asserted.
**Vacuity:** step 5 restores the mode; re-reading after another second should show the count stop
rising and a `checkpoint-N.bin` appear, which proves the failures were the permission and not a
permanently broken checkpointer.

## STATE-045 — a lane that will not snapshot within the timeout abandons the checkpoint rather than storing a partial one
**Intent:** `QueryExecution.checkpoint` throws `IllegalStateException("lane N did not take its
snapshot within T; a checkpoint that some lanes joined and others did not is worse than none...")`
(`:703`–`:706`). That throw is a `RuntimeException` and is caught by `checkpointQuietly`.
**Falsifier:** a file appears containing some lanes' state and not others'.
**Setup:** `H-WIN` whose lane processor is wrapped so that `onBatch` blocks on a `CountDownLatch`
held by the test, simulating a lane stuck inside the processor. Direct checkpointer with
`timeout = Duration.ofMillis(200)`, `interval=100ms`.
**Steps:** 1. Feed the standard payload and let it apply. 2. Take the latch (lane now blocked).
3. `start()`. 4. Sleep 1000 ms. 5. Read `stats()` and the directory. 6. Release the latch. 7. Sleep
500 ms and read again.
**Expected:** at step 5, `stats().taken() == 0`, `stats().failed() >= 5`, and `dir` contains **zero**
`checkpoint-*.bin` and zero `.tmp` files — the exception is thrown before `store.store` is reached,
so nothing is written at all. `lastCheckpointFailure()` contains `did not take its snapshot within
PT0.2S`. At step 7, `stats().taken() >= 1` and `checkpoint-*.bin` appears.
**Vacuity:** step 7 is the control — it shows the same checkpointer succeeds once the lane is free,
so the failures at step 5 are the timeout and not a broken store. Asserting zero `.tmp` files
distinguishes "abandoned before writing" from "wrote and failed to rename".

## STATE-046 — a directory that becomes unwritable mid-life degrades recovery without ending it
**Intent:** the design statement: "a transient failure — a full disk that is later emptied — should
degrade recovery rather than end it." The concrete consequence is that `latest()` keeps returning an
increasingly old checkpoint.
**Falsifier:** the query fails, or the checkpointer stops, or the old checkpoints are deleted.
**Setup:** `H-REG` over `H-WIN`, `interval=200ms`, `keep=3`.
**Steps:** 1. Let it run 1 s so ids exist. 2. Record `latest().get().id()` as `A` and the file's
mtime. 3. `chmod 0500 root/w`. 4. Sleep 3 s. 5. Record `latest().get().id()` as `B`, plus
`checkpointFailures()`. 6. `chmod 0700 root/w`. 7. Sleep 1 s. 8. Record `latest().get().id()` as `C`.
**Expected:** `A` is between 2 and 6. `B == A` exactly — no new checkpoint was stored, and crucially
the three existing ones are **still there**, because `prune` is called after `store` and `store`
threw first. `checkpointFailures()` at step 5 is at least 12 (`3000 / 200 = 15`, minus scheduling
slack). `C > B`, and `availableIds().size() == 3`. The query's `state()` is `RUNNING` throughout and
`registry.find("w")` is present.
**Vacuity:** `B == A` is a statement that nothing was lost; `C > B` is a statement that nothing was
permanently broken. Either alone would be satisfiable by a checkpointer that had silently died.

## STATE-047 — the failure message says the fallback is getting older
**Intent:** the message is the only diagnosis an operator gets, and its wording is deliberate. ERRC
owns "is every error code actionable"; this case owns the one non-coded report.
**Falsifier:** the message is a bare stack trace or a generic "checkpoint failed".
**Setup:** as STATE-043.
**Steps:** 1. Capture the first three log lines.
**Expected:** each is exactly
`checkpoint failed (<n> so far): <cause message>. Recovery will fall back to the newest stored
checkpoint, which is getting older` with `<n>` = 1, 2, 3. Note what is **missing** and should be
recorded: the message does not name the query, so on a node with forty queries writing to one log
there is nothing in the line to say which one is failing. `QueryRegistry` routes it to
`query.recordCheckpointFailure`, which stores it per query, so the information exists — it is only
absent from the text.
**Vacuity:** n/a.

## STATE-048 — nothing on the client-facing surface reports a query that has stopped checkpointing
**Intent:** the counters exist on `RegisteredQuery`; the question is whether any client can see them.
**Falsifier:** `pravaha queries` or the Flight LIST action includes a checkpoint field.
**Setup:** `H-SRV` with `checkpoint.directory` set and `checkpoint.interval: 1s`. Register `w`. Then
`chmod 0500` the per-query checkpoint directory and wait 10 s.
**Steps:** 1. `pravaha queries --url grpc://localhost:9090`. 2. `GET /api/v1/queries` on the console
API, and the console's `/queries/{name}` page. 3. `GET /actuator/health`. 4. Grep the server log.
**Expected:** step 1 prints `w<TAB>RUNNING<TAB><fingerprint><TAB><rowsIn>` — four fields, none about
checkpointing (`ServerCommand.java:115`). Step 2 shows `state: RUNNING` and no checkpoint field.
Step 3 is `UP`. Only step 4 has anything, and only if the registry's log consumer is wired to a
logger — on `H-SRV` the consumer is `query::recordCheckpointFailure`, which stores and does **not**
log, so **step 4 is empty too**. Net: a query that has not checkpointed for ten seconds, or six
hours, is indistinguishable from one that has, from every interface a client or operator actually
uses. Record as a finding.
**Vacuity:** STATE-044 establishes that the counters really did increment in-process, so this case is
about the absence of a surface rather than the absence of the failure.

## STATE-049 — a shared computation has one checkpointer and one failure counter, reachable under either name
**Intent:** two names, one `RegisteredQuery`, one `checkpointFailures`. Worth pinning because an
operator investigating under the second name must see the same numbers.
**Falsifier:** `find("beta").get().checkpointFailures()` is 0 while `find("alpha")`'s is not.
**Setup:** `H-REG` over `H-WIN`. Register `alpha`, then `beta` with byte-identical SQL. `chmod 0500
root/alpha`; `interval=100ms`; wait 1 s.
**Steps:** 1. `registry.size()` and `registry.names()`. 2. Compare
`registry.find("alpha").get()` and `registry.find("beta").get()`. 3. Read both failure counts.
**Expected:** `size() == 1` (distinct computations), `names()` is `[alpha, beta]`. The two `find`
calls return the **same object identity**. Both `checkpointFailures()` values are the same number,
at least 8, because there is one counter. There is exactly one thread named `pravaha-checkpointer`
per computation, so a thread dump shows one, not two.
**Vacuity:** the identity assertion is what makes the equal counts meaningful; two separate objects
that happened to have equal counts would be a different, worse thing.

---

### F. Restore: what round-trips, and what does not (STATE-050–064)

This is the section the area exists for. `application.yaml:137` says checkpoints are "What a query had
accumulated, so a restart recovers answers and not only questions." These fifteen cases establish
exactly how much of that sentence is true.

## STATE-050 — no shipped code path calls `restore()` or `latest()`
**Intent:** the claim the rest of the section rests on, established statically so that an executor
does not spend a day looking for the recovery path.
**Falsifier:** any file under a `src/main` tree, outside `pravaha-state/src/main`, references
`CheckpointStore.latest`, `QueryExecution.restore`, or `InterpretedPipeline.restoreState`.
**Setup:** the repository at `develop`.
**Steps:** 1. `grep -rn "\.latest()" --include=*.java . | grep "/src/main/"`.
2. `grep -rn "\.restore(" --include=*.java . | grep "/src/main/"`.
3. `grep -rn "restoreState" --include=*.java . | grep "/src/main/"`.
4. `grep -n "checkpoint" pravaha-server/src/main/java/com/ash/messaging/pravaha/server/PravahaNode.java`.
**Expected:** step 1 returns only the declaration in `CheckpointStore.java:36` and the implementation
in `FileCheckpointStore.java:113`. Step 2 returns `PartitionHandoff.java:134` (`target.restore(snapshot)`
— a *partition* snapshot, an unrelated type, and `PartitionHandoff` is itself referenced by nothing
outside `pravaha-cluster`) and the declaration in `QueryExecution.java:729`. Step 3 returns the
declaration in `InterpretedPipeline.java:409` and its single caller inside `QueryExecution.restore`.
Step 4 returns lines `108`, `109`, `142`, `143`, `356`, `358`, `360`, `361`, `363` — `checkpointingTo`
and two log lines, and **no restore**. Conclusion to record: the write half is wired, the read half
is not.
**Vacuity:** n/a — a static property, checked by enumeration.

## STATE-051 — a projection's checkpoint contains no operator state at all
**Intent:** `checkpoint` skips every pipeline where `isStateful()` is false (`QueryExecution.java:695`),
and a projection has neither a windowed aggregate nor a join.
**Falsifier:** `operatorState()` is non-empty, or `sizeBytes()` is greater than 0.
**Setup:** `H-PRJ` + a direct checkpointer, `interval=1h`, one bound source so there is exactly one
pump. Feed 10 000 rows and commit, so the view demonstrably holds data.
**Steps:** 1. Confirm the view holds 10 000 rows. 2. `Checkpoint c = checkpointNow()`. 3. Inspect
`c` and the file.
**Expected:** `c.operatorState().isEmpty()` is `true`. `c.sizeBytes() == 0`. `c.offsets().size() == 1`
(one pump, key `partition-0`). `c.toString()` is
`Checkpoint[1, 1 partitions, 0 operators, 0 bytes]`.
File size: header `MAGIC(4) + FORMAT_VERSION(4) + id(8) + timestampNanos(8) = 24`, offset count
`+4 = 28`, one offset entry `writeUTF("partition-0") = 2 + 11 = 13` plus `writeUTF(token)`, state
count `+4`, trailer `4 + 4 + 4 = 12`. With a one-character offset token that is
`24 + 4 + 13 + 3 + 4 + 12 = 60` bytes. Assert the file size equals
`44 + 13 + (2 + tokenBytes)` and record both the measured size and the token the bound source
reported.
**Vacuity:** step 1 proves the query really had 10 000 rows of committed answers, so
`sizeBytes() == 0` is a statement about what a checkpoint captures rather than about an empty query.

## STATE-052 — a keyed non-windowed aggregate is also not stateful, so its running totals are not checkpointed
**Intent:** the case most likely to surprise. `isStateful()` is
`!windowed.isEmpty() || !joins.isEmpty()`. `KeyedAggregate` and `GlobalAggregate` are neither, so a
`SELECT user_id, SUM(amount) FROM txn GROUP BY user_id` — the canonical continuous query, and the one
`application.yaml` means by "what a query had accumulated" — checkpoints **nothing**.
**Falsifier:** `operatorState()` contains a `lane-0` entry for this plan.
**Setup:** `H-PRJ`'s registry but registering
`agg = registry.register("agg", "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id",
List.of(0), DANA)`. Feed `("u1", 100)` and `("u1", 102)`, commit.
**Steps:** 1. Read the view: expect `u1 -> 100 + 102 = 202`. 2. `checkpointNow()`. 3. Inspect.
4. Repeat for `SELECT COUNT(*) FROM txn` (a global aggregate). 5. Repeat for
`SELECT user_id, amount FROM txn WHERE amount > 50` (a filter).
**Expected:** step 1 gives `202`. Step 3: `operatorState().isEmpty()`, `sizeBytes() == 0`. Steps 4
and 5 the same. So of the five plan shapes this area cares about, three checkpoint nothing:

| plan | `windowed` | `joins` | `isStateful()` | `operatorState()` |
|---|---|---|---|---|
| projection | empty | empty | false | empty |
| filter | empty | empty | false | empty |
| global aggregate | empty | empty | false | empty |
| keyed aggregate | empty | empty | false | empty |
| windowed aggregate | 1 | empty | **true** | `lane-0` present |
| stream join | empty | 1 | **true** | `lane-0` present |

**Vacuity:** the `202` is hand-computed and read back from the view before the checkpoint, so the
empty state cannot be explained by the aggregate never having accumulated anything.

## STATE-053 — a windowed aggregate does write real bytes, and they are a versioned snapshot
**Intent:** the positive control for STATE-052, and the format check.
**Falsifier:** `operatorState()` is empty, or its bytes do not start with `SNAPSHOT_MAGIC`.
**Setup:** `H-WIN` + direct checkpointer. Feed the standard payload (`100` at 1 s, `102` at 2 s, one
open window).
**Steps:** 1. `Checkpoint c = checkpointNow()`. 2. Read `byte[] s = c.operatorState().get("lane-0")`.
**Expected:** `c.operatorState().keySet()` is exactly `["lane-0"]` (one lane; `QueryRegistry` starts
every execution with lane count 1). `s.length > 0` and `c.sizeBytes() == s.length`. The first four
bytes of `s` are `0x50 0x56 0x53 0x54` (`SNAPSHOT_MAGIC = 0x50565354`, `InterpretedPipeline.java:372`);
the next four are `0x00 0x00 0x00 0x02` (`SNAPSHOT_VERSION = 2`); the next four are the windowed
operator count, `0x00 0x00 0x00 0x01`.
**Vacuity:** the byte-level assertion distinguishes "some bytes were written" from "the snapshot the
restore path expects"; a length-only check would pass on any payload.

## STATE-054 — a join writes bytes through the other branch of `isStateful()`
**Intent:** `joins` non-empty with `windowed` empty is a separate code path through `snapshotState`
and through `restoreState`'s operator-count check (which compares against `windowed.size()` only —
see STATE-059).
**Falsifier:** empty `operatorState()` for a plan that has a symmetric hash join.
**Setup:** `H-JOIN`. Feed three `txn` rows and two `lkp` rows so both sides hold state, commit.
**Steps:** 1. `checkpointNow()`. 2. Inspect the `lane-0` bytes.
**Expected:** present and non-empty. First 8 bytes are `SNAPSHOT_MAGIC` then version `2`. Bytes 8..11
are the **windowed** count, which is `0x00000000` here, and the join count follows the (zero)
windowed entries.
**Vacuity:** contrast with STATE-051's projection over the same registry: same harness, same feed
volume, empty state. The difference isolates `joins`.

## STATE-055 — a windowed aggregate's open window survives a checkpoint/restore round trip
**Intent:** the thing that *does* work, done end to end so that the negative cases have something to
be measured against. This is `CheckpointRecoveryTest`'s shape, made explicit.
**Falsifier:** after restore the window total for `u1` is `102` (only the post-checkpoint row) or
`0`, rather than the pre-checkpoint `202`.
**Setup:** two `H-WIN` executions over the same `FileCheckpointStore`, built one after the other.
**Steps:** 1. In execution A, `feedAt("u1", 100, 1s)` and `feedAt("u1", 102, 2s)`; `awaitApplied`.
2. `Checkpoint c = checkpointNow()` — id 1. 3. `A.abort()` (**not** `close()`, see STATE-064).
4. Build execution B on the same plan. 5. `B.restore(store.latest().orElseThrow(),
Duration.ofSeconds(30))`. 6. `feedAt("u1", 5, 3s)`. 7. Advance the watermark past 10 s so the window
`[0s,10s)` closes. 8. Read the emitted window.
**Expected:** step 5 does not throw. Step 8 emits one row, `("u1", 100 + 102 + 5 = 207)`. Without the
restore it would emit `("u1", 5)`.
**Vacuity:** `5` is a deliberately distinctive third value: `207` can only arise if all three rows
were counted, `5` only if the restore did nothing, and `202` only if step 6 was lost. The three
outcomes are distinguishable, which a total of `0` versus "non-zero" would not be.

## STATE-056 — a join's unmatched rows survive a round trip
**Intent:** the join branch of the same property. `SNAPSHOT_VERSION` was bumped to 2 for "the per-row
matched flag that outer joins need", so the flag is part of what has to round-trip.
**Falsifier:** after restore, a row that arrived on the left before the checkpoint does not match a
row arriving on the right after it.
**Setup:** two `H-JOIN` executions over one store.
**Steps:** 1. In A, feed `txn = ("u1", 300)`. Do not feed `lkp` yet, so the row is held unmatched.
2. `checkpointNow()`. 3. `A.abort()`. 4. Build B, `restore`. 5. Feed `lkp = ("u1", "gold")`.
6. Commit and read.
**Expected:** one output row, `("u1", 300, "gold")`. Without the restore, B's join holds nothing on
the left and emits zero rows.
**Vacuity:** the left row is fed only before the checkpoint and the right row only after it, so a
match can only come from restored state. Feeding both after would pass with no restore at all.

## STATE-057 — restoring without rewinding the sources double-counts everything since the checkpoint
**Intent:** `restore`'s javadoc: "the caller is responsible for creating readers at the checkpoint's
offsets". `Checkpoint.offsets()` is captured, and nothing consumes it. This case measures the
consequence, so that "offsets are recorded" is not mistaken for "offsets are used".
**Falsifier:** the restored run produces the same total as a clean batch recomputation.
**Setup:** two `H-WIN` executions over one store, fed from a **bounded file source** of 100 rows for
`u1`, each `amount = 1`, timestamps `0 ms .. 99 ms`, all inside window `[0s,10s)`.
**Steps:** 1. In A, pump the first 60 rows; `awaitApplied`. 2. `checkpointNow()`; record
`c.offsets()`. 3. Pump the remaining 40 rows. 4. `A.abort()`. 5. Build B, `restore(c)`. 6. Replay the
source **from the beginning** — which is what B does, because nothing seeks to `c.offsets()`.
7. Close the window and read.
**Expected:** `c.offsets()` is a one-entry map, `partition-0 -> <token>`, and the token is
non-empty — the position *was* recorded. The restored total is `60 (restored) + 100 (replayed) = 160`,
not the correct `100`. Record the token's value and note that no shipped code reads it: a grep for
`offsets()` under any `src/main` outside `pravaha-state` returns only `QueryExecution.java:711`,
which writes it.
**Vacuity:** `160` and `100` are far apart and both are hand-computed, so the case reports a specific
over-count rather than "the number looked wrong". Running the same 100 rows with no checkpoint at all
gives `100`, which is the control.

## STATE-058 — a snapshot from a plan with a different number of stateful operators is refused
**Intent:** `restoreState` refuses when `operators != windowed.size()` (`InterpretedPipeline.java:427`):
"restoring part of it would resume with some operators holding history and others empty."
**Falsifier:** the restore succeeds, or restores what it can.
**Setup:** execution A over `H-WIN`'s one-window plan; execution B over a plan with two windowed
aggregates (`SELECT user_id, SUM(amount), TUMBLE_START(...) ... GROUP BY user_id, TUMBLE(ts, INTERVAL
'10' SECOND)` unioned with a `'20' SECOND` window, or any plan the planner gives two
`WindowedAggregate`s).
**Steps:** 1. Checkpoint A. 2. `B.restore(c, 30s)`.
**Expected:** throws `PravahaException` with code **PRV-3010** (`RUNTIME_LANE_FAILED`) and message
`the checkpoint holds 1 stateful operators and this plan has 2: the query changed since the
checkpoint was taken, and restoring part of it would resume with some operators holding history and
others empty.` B's windows remain empty afterwards.
**Vacuity:** asserting B is still empty separates "refused" from "refused after partially applying".

## STATE-059 — the operator-count check ignores joins, so a join-only plan change is not caught
**Intent:** `restoreState` compares `operators` against `windowed.size()` only
(`InterpretedPipeline.java:427`) and then reads the joins without a matching guard. So a checkpoint
from a plan with one join restored into a plan with two joins passes the count check and then
mis-parses. This is the exact failure the check was written to prevent, in the branch it does not
cover.
**Falsifier:** the two-join restore is refused with a clear message.
**Setup:** A = `H-JOIN` (one join). B = the same streams with a second join
(`txn JOIN lkp JOIN lkp2`). Both have `windowed.size() == 0`.
**Steps:** 1. Feed A both sides, checkpoint. 2. `B.restore(c, 30s)`.
**Expected:** the `operators != windowed.size()` check compares `0 != 0` and passes. The subsequent
reads then either throw an `EOFException`-derived `PravahaException` from inside `readFrom`, or
succeed and leave B's second join empty and its first join holding A's rows. Record which. Either
outcome is a finding: the first is an unhelpful message where a specific one exists for the windowed
case, the second is silent partial restore.
**Vacuity:** STATE-058 is the control — it shows the guard works when the difference is windowed, so
this case isolates the gap rather than reporting a generally broken check.

## STATE-060 — bytes that are not a snapshot are refused before they are parsed
**Intent:** `SNAPSHOT_MAGIC`. "Restoring it would read whatever bytes these are as rows and weights,
and the result would be believed rather than rejected."
**Falsifier:** `restore` accepts them, or throws an `ArrayIndexOutOfBoundsException` / `OutOfMemoryError`
rather than a `PravahaException`.
**Setup:** `H-WIN` execution B. Three arms, each a hand-built `Checkpoint(1, 0, Map.of(), Map.of(
"lane-0", bytes))`: (a) `new byte[] {0,0,0,0}`; (b) 4096 bytes from `new Random(20260909L)`;
(c) a real checkpoint's bytes with the first int flipped to `0x50565355`.
**Steps:** 1. `B.restore(c, 30s)` for each arm.
**Expected:** all three throw `PravahaException` PRV-3010 with the message
`this is not a Pravaha operator snapshot. Restoring it would read whatever bytes these are as rows
and weights, and the result would be believed rather than rejected.` No `OutOfMemoryError`, no hang.
Arm (b) in particular must not allocate an array from a random length field.
**Vacuity:** arm (c) differs from a valid snapshot by exactly one byte, so passing it proves the
magic is genuinely being checked rather than the payload being rejected for some other reason.

## STATE-061 — a version-1 snapshot is refused rather than read into version-2 layouts
**Intent:** `SNAPSHOT_VERSION = 2`; version 1 lacks the per-row matched flag, so its fields would
parse in the wrong places.
**Falsifier:** the restore succeeds.
**Setup:** take a real `H-WIN` snapshot and patch bytes 4..7 from `00 00 00 02` to `00 00 00 01`.
**Steps:** 1. `restore`.
**Expected:** `PravahaException` PRV-3010, message
`this snapshot is version 1 and this engine writes version 2. The layouts differ, so restoring it
would parse one field as another and resume from state that is wrong without being obviously wrong.
Replay the stream from a source offset instead.` Note the advice ("replay from a source offset") and
that STATE-057 establishes there is no mechanism to follow it.
**Vacuity:** patching one field of an otherwise valid snapshot means the refusal cannot come from
general corruption.

## STATE-062 — a checkpoint holding no entry for a lane is a silent skip, not a failure
**Intent:** `restore` does `if (state == null) continue;` (`QueryExecution.java:734`). For a
single-lane execution restoring a checkpoint taken from a stateless plan — exactly STATE-051's
60-byte file — that means `restore` is a no-op that reports success.
**Falsifier:** `restore` throws or logs when handed an empty `operatorState()`.
**Setup:** `H-PRJ` execution A, checkpointed (empty state). `H-WIN` execution B, fed nothing.
**Steps:** 1. `B.restore(c, 30s)`. 2. Read B's window state.
**Expected:** step 1 returns normally, in under a millisecond, with no exception and no log line.
Step 2: B holds nothing. So a caller that did wire recovery, and whose query was a keyed aggregate,
would get a successful-looking `restore` that restored nothing — which is the shape of
`application.yaml`'s claim as it stands.
**Vacuity:** the timing assertion (sub-millisecond, no lane control task submitted) distinguishes "it
skipped" from "it restored an empty state onto the lane". Instrument `Lane.submitControlTask` to
confirm it was called zero times.

## STATE-063 — a server restart recovers every definition and zero accumulated answers
**Intent:** the end-to-end version of the whole section, and the case that pins
`application.yaml:137` against the code.
**Falsifier:** after a restart the windowed query's open window still holds its pre-restart total.
**Setup:** `H-SRV` with both `registry.journal` and `checkpoint.directory` set, `checkpoint.interval:
1s`.
**Steps:** 1. Register `w` (windowed sum) and `agg` (keyed sum). 2. Push `("u1", 100)` at `t=1s` and
`("u1", 102)` at `t=2s` into both. 3. `pravaha query --sql "SELECT * FROM agg"` → expect
`u1, 100 + 102 = 202`. 4. Wait 3 s so checkpoints exist; confirm
`ls /opt/pravaha/data/checkpoints/w` and `.../agg` both list `checkpoint-*.bin`, and record each
file's size. 5. `kill -9` the server. 6. Start it again on the same paths. 7. `pravaha queries`.
8. `pravaha query --sql "SELECT * FROM agg"`. 9. Push `("u1", 5)` at `t=3s` and read again.
**Expected:** step 4 — `w`'s files are larger than 60 bytes (real window state);
`agg`'s are the 60-byte framing of STATE-051, because a keyed aggregate is not stateful. Step 7 —
both `w` and `agg` are listed, `RUNNING`, with `rowsIn = 0`: the **journal** brought the definitions
back (`PravahaNode.java:401`–`:402`) and the log line reads `registry recovered 2 of 2 queries from
/opt/pravaha/data/registry.journal`. Step 8 — **zero rows**. Not `202`. Step 9 — `u1, 5`, not
`100 + 102 + 5 = 207`. Also assert the startup log contains no line mentioning a restore, and that
`/opt/pravaha/data/checkpoints/w` now contains a `checkpoint-N.bin` with `N` greater than the
pre-restart maximum (ids resume, STATE-005) — so the node is writing checkpoints it will never read.
**Vacuity:** step 3 establishes `202` really was computed and served before the restart, and step 4
establishes the checkpoint files really existed, so `0` at step 8 is a lost answer and not an
un-taken checkpoint. The `w` versus `agg` file-size contrast additionally shows that even the query
whose state *was* captured does not get it back.

## STATE-064 — a crash simulated with `close()` instead of `abort()` invalidates a recovery test
**Intent:** `abort()` calls `abandon` on every pipeline and then `close()`; `close()` alone runs
`pipeline.finish()`, which emits held windows. The javadoc records a recovery test whose numbers were
all right and whose windows all appeared twice for exactly this reason. Any executor writing recovery
cases needs this pinned once.
**Falsifier:** the two shutdown modes produce the same output.
**Setup:** two identical `H-WIN` runs. Feed the standard payload, checkpoint, then in run 1 call
`abort()` and in run 2 call `close()`. Both capture everything the view emitted.
**Steps:** 1. Run 1 with `abort()`. 2. Run 2 with `close()`. 3. Compare the emitted rows. 4. For each,
restore into a fresh execution and close the window; compare again.
**Expected:** run 1 emits nothing at shutdown — the open window `[0s,10s)` was never closed and
`abandon` discards it. Run 2 emits `("u1", 100 + 102 = 202)` at shutdown, because `finish()` releases
the window. After the restore-and-close in step 4, both emit `("u1", 202)` — so run 2's total across
the whole exercise is `202` twice and run 1's is `202` once. The duplicate is the artefact.
**Vacuity:** the duplicate is a count of emissions, not of rows, so it is visible only if the harness
records every emission rather than reading the final view. Say so in the harness: read from a
`ViewChangeListener`, not from `pravaha query`.

### G. The registry journal: append and replay (STATE-065–076)

The journal records *registrations*, not state — "small facts" that cannot be recomputed because they
came from a client that may never speak again. Wire format: a big-endian `int` length, then a
`ControlWire` payload (`MAGIC 0x50525648`, `VERSION 1`, field count, then length-prefixed UTF-8
fields). A `REGISTER` is `["R", name, sql, keys, owner, retention, params...]`; a `DROP` is
`["D", name]`.

## STATE-065 — a registration is on disk, flushed, before `register` returns
**Intent:** "Each record is flushed before this returns. A registration acknowledged to a client and
then lost in a page cache is worse than one that failed outright." The append also happens *after*
the query is running, so one that cannot start is not recorded as if it had
(`QueryRegistry.java:373`–`:379`).
**Falsifier:** the file is empty, or absent, immediately after `register` returns.
**Setup:** `H-JRN` with `journalFile = tmp/registry.journal`, which does not exist beforehand.
**Steps:** 1. `registry.register("q", "SELECT user_id, amount FROM txn", List.of(0), DANA)`.
2. Before anything else, read `Files.size(journalFile)` and `Files.readAllBytes`.
3. Run the whole thing under `strace -e trace=fsync,fdatasync`.
**Expected:** step 2 — the file exists and holds exactly one record. Its first four bytes are the
payload length; bytes 4..7 are `50 52 56 48`; byte 8 is `01`. Step 3 — exactly one `fsync` on the
journal fd, from `channel.force(true)` (`RegistryJournal.java:230`).
**Vacuity:** reading the file before any other call rules out a later flush; the `strace` rules out
`force` being a no-op wrapper.

## STATE-066 — the record's fields are exactly what the format says
**Intent:** decode the bytes by hand once, so every later journal case can reason about content
rather than about framing.
**Falsifier:** the decoded field list differs in count, order or content.
**Setup:** `H-JRN`. `registry.register("q", "SELECT user_id, amount FROM txn", List.of(0, 1), DANA)`
with `registry.retainingFor(Retention.ofAge(Duration.ofHours(2)))` set beforehand, and no parameters.
**Steps:** 1. Read the file. 2. Skip the 4-byte length. 3. `ControlWire.decode` the remainder.
**Expected:** a list of exactly 6 strings:
`["R", "q", "SELECT user_id, amount FROM txn", "0,1", "dana", "7200000"]`.
Field 3 is `joinInts(List.of(0,1))` = `"0,1"`. Field 4 is `principal.id()` = `"dana"`. Field 5 is
`encodeRetention` = `Long.toString(Duration.ofHours(2).toMillis())` = `2 * 60 * 60 * 1000 =
7200000`. The record length int equals `4 + 1 + 4 + sum over fields of (4 + utf8Length)`:
`9 + (4+1) + (4+1) + (4+31) + (4+3) + (4+4) + (4+7) = 9 + 5 + 5 + 35 + 7 + 8 + 11 = 80` bytes, so the
file is `4 + 80 = 84` bytes. Compute the SQL's UTF-8 length for the exact text used and show the
arithmetic in the result.
**Vacuity:** n/a — a format assertion. The byte total is included so that an extra or missing field
changes the file size, not only the decode.

## STATE-067 — replay returns the registration verbatim
**Intent:** the round trip every other journal case assumes.
**Falsifier:** any field differs from what was registered.
**Setup:** as STATE-066.
**Steps:** 1. `List<Entry> e = new RegistryJournal(journalFile).replay()`.
**Expected:** `e.size() == 1`. `e.get(0).name() == "q"`; `.sql()` is byte-identical to the registered
text; `.keyColumns()` is `List.of(0, 1)`; `.owner()` is `"dana"`; `.retention().maxAge()` is
`Duration.ofMillis(7200000)`, i.e. `Duration.ofHours(2)`; `.parameters()` is empty.
**Vacuity:** asserting all six fields rather than the name alone is what makes the case catch a
field-order bug, which is the failure mode a 6-field positional format actually has.

## STATE-068 — every retention encoding round-trips
**Intent:** `encodeRetention`/`decodeRetention` (`RegistryJournal.java:306`–`:323`) has three
branches and one of them is the empty string, which is not a value any caller produces directly.
**Falsifier:** any arm comes back as a different retention.
**Setup:** `H-JRN`. Four registrations with distinct names and distinct SQL: (a) `Retention.DEFAULT`
(24 h), (b) `Retention.forever()`, (c) `Retention.ofAge(Duration.ofMillis(1))`, (d) a hand-written
record with field 5 set to the empty string (`recordRegistration(..., null, List.of())`).
**Steps:** 1. Replay. 2. Compare each `Entry.retention()`.
**Expected:** (a) `maxAge() == Duration.ofHours(24)`, encoded as `"86400000"` — `24 * 60 * 60 * 1000
= 86 400 000`. (b) `retention.isForever()` is true, encoded as `"forever"`. (c) `maxAge() ==
Duration.ofMillis(1)`, encoded as `"1"`. (d) a `null` retention encodes as `""` and decodes as
`Retention.DEFAULT` — so a record that said "no retention recorded" comes back as 24 hours, silently.
Record (d): it is the one arm where the round trip is lossy on purpose.
**Vacuity:** (c) at 1 ms and (a) at 86 400 000 ms cannot be confused with each other or with the
default, so a decoder that ignored the field would fail on (c).

## STATE-069 — every bound-parameter type tag round-trips
**Intent:** `encodeParameter`/`decodeParameter` (`:326`–`:359`). "so a journal is readable without the
engine" — seven tags, and the values are account numbers and customer ids, which is why the file is
0600.
**Falsifier:** any value comes back different, or as the wrong Java type.
**Setup:** `H-JRN` registering a parameterised query per arm, one arm per value:
`null`, `new byte[]{1,2,-1}`, `42L`, `42`, `(short) 42`, `3.5d`, `3.5f`, `Boolean.TRUE`,
`Boolean.FALSE`, `"hello"`, `""`, `"i:12"`, `"x:y"`, a 64 KiB string.
**Steps:** 1. Read the raw encodings from the file. 2. Replay and compare each decoded value.
**Expected:** encoding then decoding —

| in | encoded | decoded | type |
|---|---|---|---|
| `null` | `n:` | `null` | — |
| `byte[]{1,2,-1}` | `b:AQL/` | `byte[]{1,2,-1}` | `byte[]` |
| `42L` | `i:42` | `42L` | `Long` |
| `42` (Integer) | `i:42` | `42L` | **`Long`** |
| `(short) 42` | `i:42` | `42L` | **`Long`** |
| `3.5d` | `d:3.5` | `3.5d` | `Double` |
| `3.5f` | `d:3.5` | `3.5d` | **`Double`** |
| `TRUE` | `z:true` | `true` | `Boolean` |
| `FALSE` | `z:false` | `false` | `Boolean` |
| `"hello"` | `s:hello` | `"hello"` | `String` |
| `""` | `s:` | `""` | `String` |
| `"i:12"` | `s:i:12` | `"i:12"` | `String` |
| `"x:y"` | `s:x:y` | `"x:y"` | `String` |
| 64 KiB string | `s:` + 65 536 chars | identical | `String` |

`b:AQL/` is `Base64(0x01, 0x02, 0xFF)` = `AQL/` (four characters, no padding, because three bytes is
exactly one Base64 group). `byte[]` must be compared with `Arrays.equals`, not `equals`.
**Vacuity:** `"i:12"` and `"x:y"` are the adversarial entries — they contain a tag character in the
body position, so a decoder that split on `:` rather than taking `charAt(0)` and `substring(2)` gets
them wrong while getting the other twelve right.

## STATE-070 — narrow integers widen on replay, which can split a shared computation
**Intent:** STATE-069's three bold rows are not cosmetic. Bound values are part of the plan and
therefore part of `QueryFingerprint` (`QueryRegistry.java:318`–`:326`: "two bindings of the same SQL
are two computations"). If a client bound an `Integer` and replay produces a `Long`, the
post-restart fingerprint may differ from the pre-restart one — and two registrations that shared one
computation before a restart may not share it after.
**Falsifier:** `registry.size()` is the same before and after a restart for a fingerprint-sharing
pair bound with narrow integers.
**Setup:** `H-JRN`. Register `a` and `b` with byte-identical parameterised SQL
(`SELECT user_id, amount FROM txn WHERE amount > ?`) bound to `Integer.valueOf(50)` for `a` and
`Long.valueOf(50)` for `b`.
**Steps:** 1. Before restart: `registry.size()` and `registry.names()`. 2. Replay into a second
registry. 3. `second.size()` and `second.names()`.
**Expected:** step 1 — record whether `size()` is `1` (the two bindings fingerprinted alike) or `2`.
Step 3 — `names()` is `[a, b]` either way; compare `second.size()` against step 1's. If they differ,
that is the finding: a restart silently changed how many copies of the state the node holds, and
therefore its memory footprint and its `pravaha queries` output. Also assert
`second.find("a").get() == second.find("b").get()` matches the pre-restart identity relation.
**Vacuity:** using one `Integer` and one `Long` of the *same numeric value* is what isolates the
widening; two different values would legitimately be two computations.

## STATE-071 — many registrations replay in registration order
**Intent:** `replay` uses a `LinkedHashMap` so "recovery re-registers in the order the queries were
created, which keeps a shared computation's first registrant stable across restarts". That matters
because the first registrant owns the checkpoint directory (STATE-035).
**Falsifier:** the replayed list is in a different order from the registration order.
**Setup:** `H-JRN`. Register `q00` .. `q49`, fifty distinct names with fifty distinct SQL texts, in
that order.
**Steps:** 1. Replay. 2. Compare `entries.stream().map(Entry::name).toList()`.
**Expected:** exactly `[q00, q01, ..., q49]`, in order, 50 elements. Not sorted lexically by
accident — use names where registration order and lexical order coincide, then repeat with names
registered in the order `q49 .. q00` and expect `[q49, ..., q00]`, which lexical sorting would not
produce.
**Vacuity:** the second, reversed run is what distinguishes insertion order from sorted order; the
first run alone passes under either.

## STATE-072 — a registration that cannot start is not journalled
**Intent:** `journalRegistration` is called after the execution is running (`:373`–`:379`). A query
recorded but unable to start would be refused at every subsequent restart, for ever.
**Falsifier:** the journal grows when a registration throws.
**Setup:** `H-JRN`. Three failing registrations: (a) SQL naming an unknown stream
(`SELECT * FROM nosuch`); (b) SQL that does not parse (`SELEKT 1`); (c) a name already in use.
**Steps:** 1. Record `Files.size(journalFile)` (or its absence). 2. Attempt all three. 3. Record the
size again. 4. Replay.
**Expected:** all three throw. The file's size is unchanged — for a fresh journal, the file does not
exist at all after step 3, because `append` is the only thing that creates it. `replay()` returns an
empty list.
**Vacuity:** a fourth step registering a *valid* query and seeing the file appear proves the journal
was wired at all; without it, "no growth" is satisfied by a registry with no journal.

## STATE-073 — an unwritable journal fails the registration rather than acknowledging it
**Intent:** `append`'s catch: PRV-8006, "The registration would be lost at the next restart, so it is
refused now rather than acknowledged and forgotten."
**Falsifier:** `register` returns a `RegisteredQuery` while nothing is on disk.
**Setup:** `H-JRN` with `journalFile` inside a directory at mode `0500`.
**Steps:** 1. `register("q", ...)`. 2. `registry.names()`. 3. `registry.find("q")`.
**Expected:** step 1 throws `PravahaException` code **PRV-8006** (`REGISTRY_JOURNAL_UNWRITABLE`)
whose message contains `cannot append to the registry journal at` and
`refused now rather than acknowledged and forgotten`. Record what step 2 and 3 show: the append
happens **after** the query is running and after `byName.put`, so the name may already be in the
registry when the exception propagates. If `names()` contains `q`, that is a finding — the client
got a failure for a registration the node is serving, and the next restart will not have it.
**Vacuity:** steps 2 and 3 are the whole case; a throws-only assertion cannot distinguish "refused"
from "refused after committing".

## STATE-074 — a name registered, dropped and registered again appears once, with the latest definition
**Intent:** `apply` does `live.remove(name); live.put(name, entry);` (`RegistryJournal.java:178`–
`:179`). The javadoc says the result is "in the order they were first registered"; the code moves a
re-registered name to the **end**. Pin both halves.
**Falsifier:** two entries for one name, or the older SQL winning.
**Setup:** `H-JRN`. In order: register `a` (SQL-1), register `b` (SQL-2), drop `a`, register `a`
(SQL-3).
**Steps:** 1. Replay. 2. Read names and SQL.
**Expected:** `entries.size() == 2`. `a` appears once, with SQL-3 not SQL-1. The order is
**`[b, a]`**, not `[a, b]` — because the second `R` for `a` removed it and re-inserted it at the
tail. Record the contradiction with the javadoc at `RegistryJournal.java:118`–`:120`; STATE-071's
order property survives only for names that were never re-registered.
**Vacuity:** the `[b, a]` assertion is the non-obvious half; asserting only `size() == 2` and SQL-3
would leave the order defect unrecorded.

## STATE-075 — an absent journal file replays as empty, not as an error
**Intent:** `replay` returns `List.of()` when the file does not exist (`:122`). A first start must
not fail because there is nothing to recover.
**Falsifier:** an exception, or a non-empty list.
**Setup:** `journalFile` pointing at a path that does not exist, inside a directory that does.
**Steps:** 1. `new RegistryJournal(journalFile).replay()`. 2. `registry.recover(...)`.
3. Also: a path whose *parent* does not exist.
**Expected:** step 1 returns an empty immutable list. Step 2 returns
`Recovery(recovered=[], refused=[])` with `complete() == true`. Step 3 is the same — `Files.exists`
is false for both.
**Vacuity:** n/a.

## STATE-076 — replay does not modify the journal, and is repeatable
**Intent:** recovery reads; `registerWithoutJournalling` suspends the journal so replay does not
re-append what it just read (`QueryRegistry.java:406`–`:415`). A replay that appended would double
the file at every restart.
**Falsifier:** the file grows across a replay, or a second replay returns a different list.
**Setup:** `H-JRN` with five registrations and two drops recorded.
**Steps:** 1. Record the file's size and SHA-256. 2. `second.recover(...)`. 3. Record size and hash
again. 4. `new RegistryJournal(f).replay()` twice and compare the two lists.
**Expected:** identical size and hash at steps 1 and 3. The two lists at step 4 are equal element by
element. A third restart cycle (recover, then replay again) leaves the hash unchanged.
**Vacuity:** the hash rather than the size is what catches an in-place rewrite of the same length.

---

### H. Re-authorization, drops, sharing, and ordering (STATE-077–084)

"A registration is not a standing permission." The journal replays through `register`, which
re-checks `mayRegisterQuery` and `mayRead` for every source stream, against the policy as it is
**now**.

## STATE-077 — a dropped name stays dropped across a restart
**Intent:** `recordDrop` exists for exactly this; the `D` record removes the name from `live`.
**Falsifier:** the dropped query is back after replay.
**Setup:** `H-JRN`. Register `a`, `b`, `c`; `drop("b")`.
**Steps:** 1. Replay into a second registry with `recover`. 2. `second.names()`.
**Expected:** `Recovery.recovered()` is `[a, c]` in that order; `refused()` is empty;
`second.names()` is `[a, c]`. The journal file still contains four records — three `R` and one `D` —
so the drop is remembered by being replayed over, not by being erased (STATE-092 is where that is
undone).
**Vacuity:** asserting the file still has four records separates "the drop was applied at replay"
from "the drop rewrote the journal".

## STATE-078 — register, drop, register again replays as present
**Intent:** the `D` then `R` order, which is the opposite of STATE-074's and must not leave the name
dropped.
**Falsifier:** `a` is absent after replay.
**Setup:** `H-JRN`. Register `a` (SQL-1); `drop("a")`; register `a` (SQL-2).
**Steps:** 1. Replay. 2. Recover.
**Expected:** one entry, name `a`, SQL-2. `recovered() == [a]`.
**Vacuity:** SQL-1 and SQL-2 differ, so "present with the right definition" is checkable, not just
"present".

## STATE-079 — register, drop, register, drop replays as absent
**Intent:** the last word wins, and the last word is a drop.
**Falsifier:** `a` comes back.
**Setup:** `H-JRN`. Register `a`; drop; register `a`; drop.
**Steps:** 1. Replay. 2. Recover.
**Expected:** the list is empty; `recovered()` and `refused()` are both empty; `complete()` is true.
The file holds four records.
**Vacuity:** four records on disk and zero entries replayed is the assertion; a truncated file would
give the same result for the wrong reason, so also assert `Files.size` matches the sum of the four
record lengths.

## STATE-080 — a principal who has lost read access does not get the query back
**Intent:** the whole point of re-authorization. "Replaying blindly would be a way to keep an
entitlement after it was revoked, by having registered before it was."
**Falsifier:** the query recovers, and its view answers, for a principal the current policy would
refuse.
**Setup:** `H-JRN` with a policy that allows `dana` to read `txn`. Register `q`
(`SELECT user_id, amount FROM txn`). Then build a second registry over the same journal with a
policy whose `mayRead(dana, "txn")` denies with reason `"region restriction"`.
**Steps:** 1. `second.recover(id -> Optional.of(DANA))`. 2. `second.names()`. 3. Attempt to read the
view `q`.
**Expected:** `recovered()` is empty. `refused()` has one entry, text beginning `q: ` and containing
`may not register 'q' because it reads 'txn', which they may not read: region restriction`.
`second.names()` does not contain `q`, so step 3 fails with PRV-8002 / no such view.
**Vacuity:** the same journal under the *permissive* policy must recover `q` — run that as the
control arm, so the refusal is attributable to the policy change and not to a broken journal.

## STATE-081 — the server's owner lookup gives every recorded id a role-less principal
**Intent:** `PravahaNode::principalNamed` (`PravahaNode.java:491`–`:495`) is
`Optional.of(new Principal(id, "unknown", Set.of(), Map.of()))` for any non-blank id. Two
consequences: the "owner is not a principal this deployment knows" branch in `recover`
(`QueryRegistry.java:599`–`:603`) is **unreachable** from the server, and every recovered query is
re-authorized as a principal with **no roles and tenant `"unknown"`**, regardless of what the owner
actually has.
**Falsifier:** a journal owner of `nosuchuser` is refused with "is not a principal this deployment
knows".
**Setup:** `H-SRV`, three arms: `security.policy: permissive`; `policy: authenticated`; a
role-based policy granting `analyst` read on `txn`. Journal seeded with two registrations owned by
`dana` and one owned by `nosuchuser`.
**Steps:** 1. Start the node in each arm. 2. Read the startup log lines
`registry recovered N of M queries` and each `registration not recovered -- ...`. 3.
`pravaha queries`.
**Expected:** **permissive** — `recovered 3 of 3`, including the one owned by `nosuchuser`, because
any non-blank id becomes a principal and the permissive policy allows everything. **authenticated**
and **role-based** — `recovered 0 of 3` and three `registration not recovered` lines, each saying
the principal may not read `txn`, because the reconstructed principal has `roles = {}` and tenant
`unknown`. So on a node with any real policy, **no query survives a restart**, and on a node with no
policy, a query survives under an owner that does not exist. Both are findings.
**Vacuity:** running all three arms against the same journal file is what attributes the difference
to the policy; each arm alone would look like correct behaviour.

## STATE-082 — an owner recorded as blank is the only way to reach the unknown-owner refusal
**Intent:** complete the branch coverage of `recover` and give the message a case.
**Falsifier:** a blank owner recovers.
**Setup:** a hand-written journal with `recordRegistration("q", sql, List.of(0), "", ...)` — the
empty-string owner, which `recordRegistration` writes as `""` (`RegistryJournal.java:100`) and which
`principalNamed` maps to `Optional.empty()`.
**Steps:** 1. `recover(PravahaNode::principalNamed)`.
**Expected:** `recovered()` empty; `refused()` has one entry reading
`q: its owner '' is not a principal this deployment knows, so there is nobody to authorize it as`.
Note that PRV-8007 `REGISTRY_REPLAY_UNAUTHORIZED` is declared (`RegistryErrors.java:65`) and **never
thrown anywhere in the repository** — `grep -rn REPLAY_UNAUTHORIZED --include=*.java` returns only
the declaration. ERRC owns "every error code is reachable"; this is the counter-example from this
area.
**Vacuity:** the empty-string owner is constructed deliberately because STATE-081 shows it is the
only input for which `principalNamed` returns empty.

## STATE-083 — both names of a shared computation replay, and share again
**Intent:** the journal records two `R` records; replay re-registers both; `byFingerprint` reunites
them. The order matters because the first replayed name becomes the checkpoint-directory owner.
**Falsifier:** two computations after replay where there was one before, or one name missing.
**Setup:** `H-JRN` over `H-WIN`'s registry with `checkpointingTo` also set. Register `alpha`, then
`beta` with byte-identical SQL. Confirm `registry.size() == 1`.
**Steps:** 1. Replay into a second registry with `checkpointingTo(root2, cfg)`. 2. `second.size()`,
`second.names()`, identity of the two `find` results. 3. List `root2`.
**Expected:** `size() == 1`; `names()` is `[alpha, beta]` in that order (registration order preserved,
STATE-071); `second.find("alpha").get() == second.find("beta").get()`. `root2` contains exactly one
directory, named `alpha` — the first replayed name. So the directory identity is stable across a
restart provided the order is, which is why STATE-071 and STATE-074 matter.
**Vacuity:** `size() == 1` on both sides of the restart is the assertion; `names().size() == 2` alone
would pass for two unshared computations.

## STATE-084 — a drop the client is told failed must not come back on restart, and one that succeeded must not survive
**Intent:** `drop` journals *first*, then releases (`QueryRegistry.java:703`–`:726`): "a drop the
client is told failed must not have destroyed the computation, and a drop that succeeded must
survive a restart. Recording it here instead meant neither was guaranteed." Both directions, plus the
hazard the chosen order creates.
**Falsifier:** either direction fails.
**Setup:** `H-JRN`. Three arms.
**Steps:**
- **Arm A, drop of an unregistered name.** `drop("nosuch")`. Expect `PravahaException` PRV-8002,
  message `no query named 'nosuch' is registered; this node has [...]`. The journal must be
  **unchanged** — `require(name)` runs before `recordDrop`. Assert the file's SHA-256 is the same
  before and after.
- **Arm B, drop whose journal write fails.** Register `q`; make the journal's directory `0500`;
  `drop("q")`. Expect PRV-8006, and `registry.find("q")` still **present** and still answering: the
  throw happens before `byName.remove`. Restore the mode, replay: `q` recovers.
- **Arm C, drop whose journal write succeeds and whose release fails.** Register `q`; arrange for
  `query.close()` to throw (a lane that will not stop within `LaneConfig.shutdownTimeout`, giving
  PRV-3010 `lane N did not stop within PT5S`). `drop("q")` throws. But `recordDrop` already ran, so
  the journal says dropped, and `byName.remove`/`views.remove` already ran, so the node no longer
  serves it. The client saw a failure for a drop that in fact happened. Assert: the journal contains
  a `D` record for `q`; `registry.find("q")` is empty; a replay does not bring `q` back.
**Expected:** as stated per arm. Arm C is the finding — the chosen order makes "told it failed" mean
"it partly succeeded" for the release half, which is the opposite trade from the one the comment
describes for the journal half.
**Vacuity:** Arm A's hash comparison and Arm B's post-restore replay are what make "unchanged" and
"survives" checkable rather than asserted.

---

### I. Corruption, truncation, and permissions (STATE-085–091)

## STATE-085 — a half-written final record is tolerated and everything before it survives
**Intent:** `replay`'s `break` on `length < 0 || length > buffer.remaining()` (`:139`–`:144`): "A
half-written final record is the expected result of a crash during an append, not a corrupt journal."
**Falsifier:** the whole journal is refused, or the truncated record is replayed as if complete.
**Setup:** `H-JRN` with three registrations (`a`, `b`, `c`). Then truncate the file. Four arms:
(a) remove 1 byte; (b) remove half of `c`'s payload; (c) remove all of `c`'s payload, leaving its
4-byte length; (d) leave exactly 4 bytes of a fourth, never-written record's length field, then
nothing (`remaining() == 4`, so `while (buffer.remaining() > 4)` exits without reading).
**Steps:** 1. `replay()` for each arm. 2. `recover` for each.
**Expected:** arms (a), (b) and (c) all return `[a, b]` — two entries, `c` lost, no exception. Arm
(d) returns `[a, b, c]` — three entries, because the trailing 4 bytes are not enough to enter the
loop. `recover` reports `recovered = [a, b]` (or `[a, b, c]` for arm d) and `refused` empty in every
arm. Record that a lost `c` is **silent**: nothing in the return value or the log says a record was
discarded, so a client that registered `c` and was acknowledged finds out at its next subscribe.
**Vacuity:** four arms at different truncation offsets is what separates "tolerates a short tail"
from "tolerates one specific offset".

## STATE-086 — an undecodable record in the middle refuses the whole journal, with the record number
**Intent:** `catch (RuntimeException unreadable)` → PRV-8005 (`:150`–`:157`). Refused rather than
skipped: "a skipped registration is a view a client expects and will not find."
**Falsifier:** the journal replays past the bad record.
**Setup:** `H-JRN` with three registrations. Corrupt record 2's payload in place, keeping its length
int correct: overwrite its `ControlWire` magic with `00 00 00 00`.
**Steps:** 1. `replay()`. 2. `recover()`.
**Expected:** both throw `PravahaException` code **PRV-8005** (`REGISTRY_JOURNAL_UNREADABLE`),
message containing `record 2 of the registry journal at <path> cannot be decoded` and
`replaying past it would silently drop whatever it said`. The record number is `2`, one-based and
counted in file order. Record 1 is **not** returned — the exception loses it too, so one corrupt
record costs the whole journal. On `H-SRV` that means the node fails to start.
**Vacuity:** corrupting record 2 rather than record 3 is what proves the exception is not simply the
truncated-tail path; assert record 1's bytes are intact first.

## STATE-087 — a record with an unknown kind, or an `R` with too few fields, is refused with the count
**Intent:** `apply`'s second throw (`:164`–`:171`). Both arms produce the same code and different
text.
**Falsifier:** the record is skipped.
**Setup:** hand-built journals, one record each, written by encoding a `ControlWire` payload directly
and prefixing its length. Arm (a) fields `["R", "q", "sql"]` — 3 fields. Arm (b) fields
`["X", "q"]`. Arm (c) fields `["D"]` — a drop with no name, which falls through the `DROP` branch's
`fields.size() >= 2` guard into the `R` check. Arm (d) an empty field list.
**Steps:** 1. `replay()` each.
**Expected:** (a) PRV-8005, message contains
`record 1 of the registry journal at <path> is a 'R' with 3 fields, which this version does not
understand` and `a skipped registration is a view a client expects and will not find`. (b) the same
with `is a 'X' with 2 fields`. (c) the same with `is a 'D' with 1 fields`. (d) **returns an empty
list without throwing** — `apply` returns early on `fields.isEmpty()` (`:160`). Record (d): a
zero-field record is the one malformed shape that is silently skipped, contradicting the class's own
"refuse rather than skip" rule.
**Vacuity:** four arms, three throwing and one not, is what makes the exception attributable to the
specific guard rather than to malformed bytes in general.

## STATE-088 — a journal that cannot be read at all is an `UncheckedIOException`, not PRV-8005
**Intent:** `Files.readAllBytes` failing is wrapped in `UncheckedIOException("cannot read the
registry journal at " + file)` (`:129`–`:133`) — no error code. So the two "journal is broken"
failures a node can hit report through two different mechanisms, and only one of them has a PRV code
a support conversation can start from.
**Falsifier:** a PRV code appears.
**Setup:** a journal file at mode `0000` (POSIX), or a directory where the file is expected.
**Steps:** 1. `replay()`. 2. Start `H-SRV` against it.
**Expected:** `UncheckedIOException` with `cannot read the registry journal at <path>` and an
`AccessDeniedException` cause. No `PRV-` string anywhere in the message. Step 2: the node fails to
start with that exception in the stack trace. ERRC should note the gap.
**Vacuity:** running as root defeats the `0000` mode; assert `Files.isReadable` is false before step
1, and skip with a recorded reason otherwise.

## STATE-089 — the journal file is 0600 and its parent 0700
**Intent:** the file holds "query text and bound parameter values — account numbers, customer ids".
`append` calls `SensitiveFiles.createOwnerOnly(file)` **before** opening the channel (`:216`), for the
same reason as the checkpoint: a file world-readable for one write has been world-readable.
**Falsifier:** any group or other bit is set on either.
**Setup:** `H-JRN` under `umask 0022` with `journalFile = tmp/newdir/registry.journal`, where
`newdir` does not exist.
**Steps:** 1. Register one query. 2. Read the permissions of both the file and `newdir`.
**Expected:** file `rw-------` (`0600`); directory `rwx------` (`0700`) — `createOwnerOnly` narrows
the parent it creates. Under `umask 0022` the unguarded defaults would be `rw-r--r--` and
`rwxr-xr-x`, so the assertion is meaningful.
**Vacuity:** the `umask 0022` is the control, as in STATE-036.

## STATE-090 — an existing journal with loose permissions is narrowed on the next append
**Intent:** `createOwnerOnly` runs on every `append`, not only on creation, and calls `narrow`
unconditionally. So a file that an operator chmod'd to 0644 is silently corrected at the next
registration. Worth pinning as intended rather than accidental.
**Falsifier:** the loose mode survives an append.
**Setup:** `H-JRN` with one registration already recorded; `chmod 0644 journalFile` and
`chmod 0755` its parent.
**Steps:** 1. Register a second query. 2. Read both modes.
**Expected:** `0600` and `0700` again. Also: the file's existing content is preserved — `replay()`
returns both entries, because `createOwnerOnly` only creates when `!Files.exists` (`:65`) and the
channel is opened `APPEND`.
**Vacuity:** replaying both entries is what proves the narrowing did not truncate; a mode-only
assertion would pass on a file that had been recreated empty.

## STATE-091 — a `.compacting` leftover is not mistaken for the journal, and is not cleaned up either
**Intent:** `compact` writes to `file + ".compacting"` and renames. Its failure path tries to delete
the temporary and logs a warning telling the operator to remove it by hand
(`RegistryJournal.java:274`–`:283`). The temporary lives beside the journal.
**Falsifier:** `replay()` reads the `.compacting` file, or a stale one changes what replay returns.
**Setup:** `H-JRN` with three registrations. Create `registry.journal.compacting` by hand, containing
a valid single-record journal for a query named `ghost`.
**Steps:** 1. `replay()`. 2. `recover()`. 3. Start `H-SRV` against the directory.
**Expected:** all three see exactly the three real registrations. `ghost` never appears — the journal
is addressed by an exact path, not by a glob. The `.compacting` file is still on disk afterwards:
nothing in the product removes it, and nothing warns about it at startup. Record as a
housekeeping gap, and note the warning text at `:279`–`:281` is only emitted when a compaction
*fails*, which (STATE-093) can never happen because nothing compacts.
**Vacuity:** the `ghost` name is chosen so that its presence in any result is unambiguous.

---

### J. Compaction, and what happens because nothing calls it (STATE-092–094)

## STATE-092 — `compact` rewrites the journal with only what is live
**Intent:** the method works; that is not in doubt and is covered by `RegistryJournalTest:343`–`:357`.
This case extends it to the properties the shipped code would need: atomicity, permissions, and the
directory fsync.
**Falsifier:** the compacted file replays differently from the original, or loses a live entry.
**Setup:** a journal built by hand: for `i` in `0..49`, `recordRegistration("q" + i, ...)`, and for
even `i`, `recordDrop("q" + i)`. So 50 registrations and 25 drops, 75 records, 25 live.
**Steps:** 1. `List<Entry> live = journal.replay()` — expect 25. 2. Record the file size as `before`.
3. `journal.compact(live)`. 4. `new RegistryJournal(f).replay()`. 5. Record size as `after`, the
file's mode, and whether `f + ".compacting"` exists. 6. `strace` for `fsync` on the parent directory.
**Expected:** step 1 gives 25 entries, `[q1, q3, ..., q49]` — the odd ones, in registration order.
Step 4 gives the same 25, same order, same SQL. `after < before`, and specifically `after` is the sum
of the 25 live records' framed lengths while `before` was the sum of all 75. The mode is `0600`
(compaction goes through `recordRegistration`, so `createOwnerOnly` runs). No `.compacting` file
remains. Step 6 shows one `fsync` on the parent directory, from `SensitiveFiles.syncDirectory`
(`:263`–`:266`) — contrast STATE-039, where the checkpoint store does not do this.
**Vacuity:** 50/25 rather than 2/1 means an off-by-one in the live set is visible in the count; the
before/after sizes are computed from the record framing rather than compared loosely.

## STATE-093 — nothing calls `compact`, so the journal grows for the life of the deployment
**Intent:** the orphan. `grep -rn "compact(" --include=*.java .` returns the declaration and
`RegistryJournalTest:353`. No server, registry, CLI or scheduled task calls it. So the file is a
permanent, append-only record of every registration and every drop the node has ever seen.
**Falsifier:** any `src/main` file calls `compact`, or the file shrinks during a run.
**Setup:** `H-SRV` with `registry.journal` set.
**Steps:** 1. `grep -rn "\.compact(" --include=*.java . | grep "/src/main/"`. 2. Run 1000
register/drop cycles of a query named `churn` against the node. 3. Record `Files.size(journalFile)`
after every 100 cycles. 4. Restart the node and measure startup time and `replay()` cost.
5. `pravaha queries`.
**Expected:** step 1 returns **nothing**. Step 3: size grows monotonically and linearly. With the
STATE-066 arithmetic, one `R` record for a 31-character SQL and a 5-character name is about 84 bytes
and one `D` record is `4 + 9 + (4+1) + (4+5) = 4 + 27 = 31` bytes, so each cycle adds roughly
`84 + 31 = 115` bytes and 1000 cycles add about `115 * 1000 = 115 000` bytes — measure and record the
exact figure. Step 4: replay reads 2000 records and returns **zero** live entries; the whole file is
history. Step 5 lists nothing. Extrapolate and record: at one register/drop per minute the file
passes 60 MB in a year, and every restart reads all of it. Also note there is no configuration key
for compaction anywhere in `application.yaml`.
**Vacuity:** the zero-live-entries result at step 4 is what makes the growth pathological rather than
merely large — the file is entirely made of information that `compact(replay())` would discard.

## STATE-094 — an interrupted compaction leaves a complete journal, old or new, never a partial one
**Intent:** `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` plus the directory fsync. Worth a case
even though nothing calls `compact`, because the method is the documented recovery for STATE-093 and
an operator following that advice will run it by hand.
**Falsifier:** a `replay()` after an interruption returns a partial list.
**Setup:** the 75-record journal of STATE-092. A reader thread looping on `new
RegistryJournal(f).replay()` and recording every result.
**Steps:** 1. Start the reader. 2. `compact(live)` on another thread. 3. Stop the reader. 4. Inspect
every recorded result. 5. Separately: `kill -9` the JVM mid-compaction (a `compact` whose
`recordRegistration` loop is slowed by a large SQL text), restart, `replay()`.
**Expected:** every result the reader recorded is either the 25-entry compacted list or the 25-entry
list derived from all 75 records — **the same 25 entries either way**, so the observable is that no
result has a different size. Step 5: after the kill, `replay()` returns the 25 entries derived from
the original 75-record file (the rename never happened), and `registry.journal.compacting` is left
behind — assert it exists, and that STATE-091's property holds so it does not affect the replay.
**Vacuity:** the reader has to run for the whole compaction, so drive it with a journal large enough
that the rewrite takes tens of milliseconds — 50 entries each with a 64 KiB SQL text.

### K. Cluster: three modes by three mechanisms (STATE-095–110)

`pravaha.cluster.mode` is a correctness question and `pravaha.cluster.mechanism` is an operational
one, and `CoordinatorFactory.create` checks the second against the first. The rule is one line:
`if (mode.needsConsensus() && !guarantees.excludesSplitBrain()) throw PRV-9002`. Only `PARTITIONED`
needs consensus; only `socket` lacks it. So the nine-cell matrix has exactly one refusal, and the
`application.yaml` sentence "PARTITIONED on a mechanism without consensus is refused at startup
(PRV-9002)" is precise rather than loose — `single` claims consensus truthfully, because with one
node there is nobody to disagree with.

**Guarantees as declared:**

| mechanism | `excludesSplitBrain` | `requiresExternalService` | `suitableForProduction` | declared at |
|---|---|---|---|---|
| `single` | true | false | true | `SingleNodeProvider.java:30` |
| `socket` | false | false | false | `SocketProvider.java:48` |
| `zookeeper` | true | true | true | `ZooKeeperProvider.java:55` |

**Requirements.** STATE-095, 096, 098, 099, 101, 102, 104, 105, 106, 107, 109 need **no second node
and no ZooKeeper** — they are `H-CFG`, a `Configuration` and a factory call. STATE-097, 100, 103 and
108 need the **ZooKeeper plugin on the classpath**, and 097/100/103 need a **running ZooKeeper**
(`H-ZK`). STATE-110 needs a **second node** (`H-2N`).

## STATE-095 — `SINGLE` × `single` starts, and is its own leader
**Intent:** the default, and the path embedded mode and every test takes. `H-CFG`, no second node.
**Falsifier:** `create` throws, or `isLeader()` is false after `start`.
**Setup:** `Configuration.builder().build()` — both keys absent, so the defaults `SINGLE` and
`single` apply (`CoordinatorFactory.java:59`, `:100`).
**Steps:** 1. `ClusterCoordinator c = CoordinatorFactory.create(cfg)`. 2.
`c.start(new Member("only", "localhost", 9070))`. 3. Read `mechanism()`, `isLeader()`, `members()`,
`leader()`, `guarantees()`.
**Expected:** `mechanism() == "single"`. `isLeader()` is `true`. `members()` has exactly 1 element,
equal to the `Member` passed to `start`. `leader()` is that same member. `guarantees()` is
`Guarantees("single", true, false, true)`. Before `start`, `members()` is empty and `isLeader()` is
`false` — assert that too, because `self` is null until then.
**Vacuity:** the before-`start` assertions are the control; without them an implementation that
hard-codes `isLeader() == true` passes.

## STATE-096 — `SINGLE` × `socket` starts, with no consensus and a peer list
**Intent:** a mode that needs no consensus on a mechanism that has none. Allowed, and the guarantee
must still be reported honestly. `H-CFG`.
**Falsifier:** `create` throws, or `excludesSplitBrain()` is true.
**Setup:** `mode=SINGLE`, `mechanism=socket`, `pravaha.cluster.socket.peers =
"a=localhost:19081,b=localhost:19082"`.
**Steps:** 1. `create`. 2. Read `mechanism()` and `guarantees()`. 3. `close()`.
**Expected:** `mechanism() == "socket"`. `guarantees()` is `Guarantees("socket", false, false,
false)`, so `excludesSplitBrain()` is `false`, `requiresExternalService()` is `false`,
`suitableForProduction()` is `false`. `create` does **not** throw: `SINGLE.needsConsensus()` is
`false` so the guard at `CoordinatorFactory.java:73` is not entered. Note this combination is odd on
its face — one node coordinating over sockets — and the factory permits it without comment. Record
it.
**Vacuity:** do not call `start()`; this case is about the factory's decision, and starting would
bind ports and make the result depend on the environment.

## STATE-097 — `SINGLE` × `zookeeper` starts and reports consensus and an external dependency
**Intent:** the third mechanism in the mode that needs the least. **Requires the ZooKeeper plugin on
the classpath and a running ZooKeeper (`H-ZK`).**
**Falsifier:** PRV-9001, meaning the plugin is not on the classpath — which is a valid outcome to
record but not a pass.
**Setup:** `plugins/pravaha-cluster-zookeeper` on the classpath; ZooKeeper at `127.0.0.1:2181`;
`mode=SINGLE`, `mechanism=zookeeper`, `pravaha.cluster.zookeeper.connect=127.0.0.1:2181`.
**Steps:** 1. `CoordinatorFactory.available()`. 2. `create`. 3. `start(new Member("n1", "127.0.0.1",
9090))`. 4. Read `mechanism()`, `guarantees()`, `isLeader()`, `members()`. 5. `close()`.
**Expected:** step 1's map contains the keys `single`, `socket` **and** `zookeeper`, in that order —
`available()` inserts the two built-ins first "so that a broken third-party provider cannot displace
them" (`:41`–`:44`) and then `putIfAbsent`s the `ServiceLoader` results. `mechanism() ==
"zookeeper"`. `guarantees()` is `Guarantees("zookeeper", true, true, true)`. `isLeader()` is `true`
(sole member). `members()` has one element. Check ZooKeeper for a node under
`/pravaha` (the default root, `ZooKeeperProvider.java:66`).
**Vacuity:** step 1 is the control: if `zookeeper` is absent from `available()`, the case is not
testing what it claims and must be reported as not-run rather than failed.

## STATE-098 — `REPLICATED` × `single` starts
**Intent:** `REPLICATED.needsConsensus()` is `false`, so a one-node coordinator serves it. `H-CFG`.
**Falsifier:** `create` throws.
**Setup:** `mode=REPLICATED`, `mechanism=single`.
**Steps:** 1. `create`. 2. `start`. 3. Read.
**Expected:** succeeds; `mechanism() == "single"`; `isLeader()` true; `members()` size 1. Record that
`REPLICATED` on one node is indistinguishable from `SINGLE` on one node at every interface the
coordinator exposes — `ClusterCoordinator` has `mechanism`, `guarantees`, `start`, `members`,
`leader`, `isLeader`, two listener registrations and `close`, and none of them mentions the mode.
**Vacuity:** the "indistinguishable" observation is the finding, and is made by enumerating the
interface rather than by inference.

## STATE-099 — `REPLICATED` × `socket` starts, and the trade is the operator's to make
**Intent:** the combination `CoordinatorFactory`'s error message recommends as the alternative to
`PARTITIONED` on `socket`: "a split brain costs duplicated work rather than wrong numbers". `H-CFG`
for the factory half; see STATE-110 for the two-node behaviour.
**Falsifier:** `create` throws.
**Setup:** `mode=REPLICATED`, `mechanism=socket`, peers `"a=localhost:19083,b=localhost:19084"`.
**Steps:** 1. `create`. 2. Read `guarantees().excludesSplitBrain()`.
**Expected:** succeeds. `excludesSplitBrain()` is `false`. This is the one cell where the engine
knowingly accepts a coordinator that cannot exclude split-brain, and the cost of that acceptance is
described in `ClusterMode.REPLICATED`'s javadoc rather than enforced.
**Vacuity:** paired with STATE-102, which is the same mechanism under the mode that does need
consensus — the two together show the check discriminates on the mode, not on the mechanism alone.

## STATE-100 — `REPLICATED` × `zookeeper` starts
**Intent:** consensus where none is required. Allowed; the cost is an external service. **Requires
the plugin and a running ZooKeeper (`H-ZK`).**
**Falsifier:** PRV-9001 or PRV-9002.
**Setup:** as STATE-097 with `mode=REPLICATED`.
**Steps:** 1. `create`. 2. `start`. 3. Read `guarantees()` and `isLeader()`.
**Expected:** succeeds. `guarantees().requiresExternalService()` is `true` and
`excludesSplitBrain()` is `true`. `isLeader()` is `true` for a sole member.
**Vacuity:** as STATE-097 — confirm `zookeeper` is in `available()` first.

## STATE-101 — `PARTITIONED` × `single` starts, because one node cannot disagree with itself
**Intent:** the cell most likely to be read as a bug. `SingleNodeCoordinator`'s javadoc says the
claim "is true rather than cheeky: with one node there is no second node to disagree with it. It is
therefore a legitimate choice for `PARTITIONED` mode, where it assigns every partition to itself."
`H-CFG`.
**Falsifier:** PRV-9002 is thrown.
**Setup:** `mode=PARTITIONED`, `mechanism=single`.
**Steps:** 1. `create`. 2. `guarantees().excludesSplitBrain()`. 3. `start`. 4. Look for any partition
assignment: check `ClusterCoordinator`'s interface and check whether anything in `PravahaNode`
consults the mode after `describe()`.
**Expected:** `create` succeeds — `PARTITIONED.needsConsensus()` is `true` and
`single.excludesSplitBrain()` is `true`, so the guard is not entered. Step 4: the interface has no
partition method, and `PravahaNode` reads the mode only through `CoordinatorFactory.create` and
`CoordinatorFactory.describe` (`PravahaNode.java:328`, `:333`). The phrase "where it assigns every
partition to itself" describes something that does not happen anywhere. See STATE-106.
**Vacuity:** step 4 is what turns "it starts" into a finding rather than a pass.

## STATE-102 — `PARTITIONED` × `socket` is refused at startup with PRV-9002
**Intent:** the rule the whole cluster design exists for, and the one `application.yaml:187` promises.
`H-CFG`.
**Falsifier:** `create` returns a coordinator.
**Setup:** `mode=PARTITIONED`, `mechanism=socket`, peers
`"a=localhost:19085,b=localhost:19086,c=localhost:19087"`.
**Steps:** 1. `CoordinatorFactory.create(cfg)`. 2. Also start a real node (`H-SRV`) with those three
settings and observe whether it binds its ports.
**Expected:** step 1 throws `PravahaException` with code **PRV-9002**
(`CLUSTER_INSUFFICIENT_GUARANTEE`). The message contains, in order:
`cluster mode PARTITIONED assigns partitions to particular nodes`; `'socket' cannot exclude
split-brain`; the guarantee summary `socket (NO consensus — cannot exclude split-brain),
self-contained, development only`; `Two nodes each believing they own a partition means two nodes
writing the same aggregate, and the damage is silent and durable`; the remedy
`Use a coordinator with consensus, or run mode REPLICATED`; and
`Refusing now rather than during a partition.` Step 2: the node does **not** start — `create` is the
first statement of `PravahaNode.start` (`:328`), before `refuseAccidentalOpenServer`,
before the Flight bind — so no port is opened and no query is recovered. Verify with `ss -ltn` that
neither 9090 nor the HTTP port is listening.
**Vacuity:** step 2 is the non-vacuity: a refusal that happened after the ports were bound would be a
different and much weaker guarantee, and the port check is what distinguishes them.

## STATE-103 — `PARTITIONED` × `zookeeper` starts
**Intent:** the only production-supportable partitioned configuration, per `docs/OPERATIONS.md:113`.
**Requires the plugin and a running ZooKeeper (`H-ZK`).**
**Falsifier:** PRV-9002 or PRV-9001.
**Setup:** as STATE-097 with `mode=PARTITIONED`.
**Steps:** 1. `create`. 2. `start`. 3. As STATE-101 step 4, look for partition assignment.
**Expected:** `create` succeeds. Step 3 finds none, for the same reason. So the matrix's conclusion is
that `PARTITIONED` is accepted on two of three mechanisms and implemented on none — the mode is a
startup-time assertion about the coordinator, not a runtime behaviour.
**Vacuity:** as STATE-097.

## STATE-104 — an unknown mechanism names what is available
**Intent:** PRV-9001, and the message that tells an operator the artefact is missing rather than the
name being wrong. `H-CFG`, and this is also what STATE-097/100/103 look like with the plugin absent.
**Falsifier:** a generic failure, or a silent fall-back to `single`.
**Setup:** four arms: `mechanism=zookeeper` **without** the plugin on the classpath;
`mechanism=raft`; `mechanism=""`; `mechanism="SOCKET"`.
**Steps:** 1. `create` for each.
**Expected:** all four throw `PravahaException` PRV-9001 (`CLUSTER_UNKNOWN_MECHANISM`). Each message
contains `no cluster coordinator called '<name>' is on the classpath. Available: [single, socket]`
and `A mechanism ships in its own artefact so that a deployment using one does not carry the
dependencies of the others`. The `""` arm proves the empty string is not treated as unset — the
default only applies when the key is absent. The `"SOCKET"` arm proves the mechanism name is
case-**sensitive**: `getString(...).strip()` is not uppercased (`CoordinatorFactory.java:59`),
unlike the mode.
**Vacuity:** the four arms are chosen so that a lenient implementation (trimming, case-folding,
defaulting) fails at least one of them.

## STATE-105 — an invalid mode is refused, with a code named for mechanisms
**Intent:** `modeOf` throws `PravahaException(ClusterErrors.UNKNOWN_MECHANISM, "'X' is not a cluster
mode; one of [SINGLE, REPLICATED, PARTITIONED]")` (`:104`–`:106`). PRV-9001 is
`CLUSTER_UNKNOWN_MECHANISM`, so one code now answers for two different configuration mistakes —
exactly the "which 5001?" problem `RegistryErrors` was renumbered to avoid. Also pins the parsing:
the mode **is** stripped and uppercased, the mechanism is only stripped.
**Falsifier:** an unknown mode is accepted, or a valid mode in lower case is refused.
**Setup:** `H-CFG`. Arms: `mode=sharded`; `mode=""`; `mode="partitioned"` (lower case);
`mode="  REPLICATED  "` (padded); `mode="Replicated"`; and for the mechanism, `mechanism="  socket  "`
with a peer list, and `mechanism="Socket"`.
**Steps:** 1. `CoordinatorFactory.modeOf(cfg)` and `create(cfg)` for each.
**Expected:** `sharded` and `""` throw PRV-9001 with `is not a cluster mode; one of [SINGLE,
REPLICATED, PARTITIONED]`. `partitioned`, `  REPLICATED  ` and `Replicated` all resolve —
`PARTITIONED`, `REPLICATED`, `REPLICATED` — because of `.strip().toUpperCase(Locale.ROOT)`.
`"  socket  "` resolves to the socket provider (stripped). `"Socket"` throws PRV-9001 as an unknown
*mechanism*. Record the asymmetry between the two keys and the code reuse: a support conversation
starting from PRV-9001 cannot tell which key is wrong without the message text.
**Vacuity:** the padded and lower-case arms are the controls; without them the two refusals would not
establish that the parsing is deliberate rather than absent.

## STATE-106 — `PARTITIONED` assigns nothing, because the partition machinery is reachable from nothing
**Intent:** the mode's whole meaning is "partitions are owned by particular nodes". Four classes
exist to do that — `PartitionAssignment`, `PartitionOwner`, `PartitionHandoff`, `Rebalancer` — and
none is referenced outside `pravaha-cluster`. `Rebalancer` is on `OrphanedClassTest`'s `KNOWN` list of
"built, tested, documented and unreachable".
**Falsifier:** any `src/main` file outside `pravaha-cluster` names one of the four, or
`ClusterCoordinator` exposes a partition-related method.
**Setup:** the repository at `develop`.
**Steps:** 1. `grep -rn "PartitionAssignment\|PartitionOwner\|PartitionHandoff\|Rebalancer\|
PartitionSnapshot" --include=*.java . | grep "/src/main/" | grep -v "pravaha-cluster/src/main"`.
2. List `ClusterCoordinator`'s methods. 3. Run `H-SRV` with `mode=PARTITIONED, mechanism=single`,
register three queries, push rows, and compare every observable against the same run with
`mode=SINGLE`.
**Expected:** step 1 returns **nothing**. Step 2 returns exactly nine methods: `mechanism`,
`guarantees`, `start`, `members`, `leader`, `isLeader`, `onLeadershipChange`, `onMembershipChange`,
`close` — none about partitions. Step 3: the two runs are identical in `pravaha queries` output,
query results, lane counts (one lane per query, `QueryExecution.start(plan, 1, ...)`), and every log
line except the one from `CoordinatorFactory.describe`. Conclusion: `PARTITIONED` changes one log
line and one startup check, and nothing else.
**Vacuity:** step 3's byte-for-byte comparison of the two runs is what makes "nothing else" checkable
rather than asserted.

## STATE-107 — the socket peer list is required, and parsed strictly
**Intent:** `SocketProvider.create` (`:52`–`:78`). "There is no discovery: discovery without consensus
is one more thing for two halves of a cluster to disagree about." `H-CFG`.
**Falsifier:** a malformed peer list produces a coordinator.
**Setup:** `mode=REPLICATED`, `mechanism=socket`, and these peer values:
(a) key absent; (b) `""`; (c) `"a=localhost:19091"` (one peer); (d) `"localhost:19091"` (no id);
(e) `"a=localhost"` (no port); (f) `"a=localhost:19091,"` (trailing comma);
(g) `"a=localhost:19091, b=localhost:19092"` (space after comma);
(h) `"a=localhost:notaport"`; (i) `"a:b=localhost:19091"` (colon in the id).
**Steps:** 1. `create` for each.
**Expected:**
- (a) PRV-9005 (`CLUSTER_BAD_MEMBERSHIP`), message `the socket coordinator needs
  pravaha.cluster.socket.peers, as 'id=host:port,id=host:port'` and `discovery without consensus is
  one more thing for two halves of a cluster to disagree about`.
- (b) the empty string is **present**, so the `orElseThrow` does not fire; `"".split(",")` gives one
  empty element, which the loop skips (`:62`–`:65`), producing a `SocketCoordinator` with an
  **empty peer list**. Record this: an empty `peers` value is accepted where a missing one is
  refused.
- (c) succeeds, one peer.
- (d) PRV-9005, `'localhost:19091' is not a peer; each is 'id=host:port'` — `indexOf('=')` is `-1`.
- (e) PRV-9005, same text for `'a=localhost'` — `lastIndexOf(':')` is `-1`, so `colon < equals`.
- (f) succeeds with one peer; the empty trailing element is skipped.
- (g) succeeds with two peers, ids `a` and `b` — each entry is `strip()`ed.
- (h) throws `NumberFormatException` (`For input string: "notaport"`) — **not** a `PravahaException`
  and **not** PRV-9005. Record: the id and host are validated and the port is not.
- (i) succeeds, id `a`, host `b=localhost`... no: `indexOf('=')` is 3 and `lastIndexOf(':')` is 13, so
  id is `a:b`, host is `localhost`, port `19091`. Record whether an id containing a colon is intended.

Also assert that `application.yaml` documents **none** of `pravaha.cluster.socket.peers`,
`.heartbeat.millis` or `.timeout.millis` — only `mode` and `mechanism` appear (`:177`–`:190`) — and
that `SocketProvider`'s own javadoc example writes `heartbeat: 1s` and `timeout: 5s` while the code
reads `pravaha.cluster.socket.heartbeat.millis` and `.timeout.millis` as longs
(`SocketProvider.java:75`–`:76`). A deployment copying the javadoc gets the 1 000 ms and 5 000 ms
defaults and never knows.
**Vacuity:** nine arms with four distinct outcomes (succeed, PRV-9005, `NumberFormatException`,
succeed-with-nothing) means no single lenient or strict implementation passes them all.

## STATE-108 — the ZooKeeper coordinator requires a connect string and defaults the rest
**Intent:** `ZooKeeperProvider.create` (`:58`–`:79`). **Requires the plugin on the classpath**; the
refusal arm needs no running ZooKeeper.
**Falsifier:** the coordinator is built without a connect string.
**Setup:** plugin on the classpath. Arms: (a) `mechanism=zookeeper` with no `connect`;
(b) `connect=127.0.0.1:2181` and nothing else; (c) `connect` plus `root=/pravaha-test`,
`session.timeout.millis=30000`, `connect.timeout.millis=20000`.
**Steps:** 1. `create` for each. 2. For (b) and (c), inspect the Curator client's configuration.
**Expected:** (a) `PravahaException` PRV-9005 (`CLUSTER_BAD_MEMBERSHIP`), message
`the ZooKeeper coordinator needs pravaha.cluster.zookeeper.connect, as
'host:2181,host:2181,host:2181'`. Note the code is `BAD_MEMBERSHIP` and the fault is a missing
address, which is the same code the socket provider uses for the same class of mistake — consistent,
and worth recording as such. (b) succeeds; root defaults to `/pravaha`, session timeout to
`15_000` ms, connect timeout to `10_000` ms (`:66`–`:68`); the retry policy is
`ExponentialBackoffRetry(1_000, 5)`. (c) succeeds with the configured values. Also assert
`application.yaml` documents none of the four `pravaha.cluster.zookeeper.*` keys.
**Vacuity:** (b) versus (c) is what proves the defaults are defaults and not ignored values.

## STATE-109 — the startup line says what was chosen and what it promises, for all nine cells
**Intent:** `CoordinatorFactory.describe` is the one line an operator may still read, and
`PravahaNode.start` logs it at `:333`. Enumerating all nine is the point.
**Falsifier:** any line omits the mode, the mechanism, or the consensus statement.
**Setup:** `H-CFG` for the six that construct without an external service; `H-ZK` for the three
`zookeeper` cells; STATE-102's cell never reaches `describe`.
**Steps:** 1. For each constructible cell, `CoordinatorFactory.describe(cfg, coordinator)`.
**Expected:** exactly these strings, from
`"cluster mode " + mode + " on " + guarantees.describe()`:

| mode | mechanism | line |
|---|---|---|
| SINGLE | single | `cluster mode SINGLE on single (consensus), self-contained` |
| SINGLE | socket | `cluster mode SINGLE on socket (NO consensus — cannot exclude split-brain), self-contained, development only` |
| SINGLE | zookeeper | `cluster mode SINGLE on zookeeper (consensus), external service required` |
| REPLICATED | single | `cluster mode REPLICATED on single (consensus), self-contained` |
| REPLICATED | socket | `cluster mode REPLICATED on socket (NO consensus — cannot exclude split-brain), self-contained, development only` |
| REPLICATED | zookeeper | `cluster mode REPLICATED on zookeeper (consensus), external service required` |
| PARTITIONED | single | `cluster mode PARTITIONED on single (consensus), self-contained` |
| PARTITIONED | socket | *never logged — PRV-9002 at `create`* |
| PARTITIONED | zookeeper | `cluster mode PARTITIONED on zookeeper (consensus), external service required` |

Note the em dash in the two `socket` lines and that `suitableForProduction=false` appends
`, development only` only there. Also confirm `PravahaNode.describe()` (`:498`) reports
`cluster: <mechanism name>` and **not** the mode, so the status endpoint is less informative than the
startup log.
**Vacuity:** the eight exact strings are compared character for character, so a line that merely
mentions the right words in the wrong shape fails.

## STATE-110 — two socket nodes elect the lowest reachable id, and a partition elects two leaders
**Intent:** `SocketCoordinator`'s honest description of itself: "Leadership here is 'the lowest id
among the peers I can currently reach', which is decided independently on each side of a partition —
so a partition produces two leaders, each correct from where it is standing." **Requires a second
node (`H-2N`).**
**Falsifier:** the two nodes disagree about the leader while both can see each other; or a partition
does *not* produce two leaders, which would mean the refusal in STATE-102 is protecting against a
risk the mechanism does not actually have.
**Setup:** `H-2N`: two processes, `mode=REPLICATED`, `mechanism=socket`,
`peers="a=127.0.0.1:19101,b=127.0.0.1:19102"`, `heartbeat.millis=200`, `timeout.millis=1000`. Node
`a` binds 19101, node `b` binds 19102.
**Steps:** 1. Start both; wait 2 s (ten heartbeats). 2. Read `leader()`, `isLeader()`, `members()` on
each. 3. Block traffic between the two listener ports in both directions
(`iptables -A INPUT -p tcp --dport 19101 -j DROP` and the mirror), so each sees only itself. 4. Wait
2 s (twice the 1 000 ms timeout). 5. Read again on each. 6. Remove the rules; wait 2 s; read again.
**Expected:** step 2 — both report `leader()` as member `a` (lowest id lexically, `Comparator`
on id); `a.isLeader()` is `true` and `b.isLeader()` is `false`; `members()` on each has 2 elements.
Step 5 — `a.leader()` is `a` and `a.isLeader()` is `true`; `b.leader()` is **`b`** and
`b.isLeader()` is **`true`**. Two leaders, simultaneously, each with `members()` of size 1. That is
the split brain, observed rather than argued, and it is why `PARTITIONED` is refused on this
mechanism. Step 6 — both converge back to `a` within 2 s, and `members()` returns to 2 on each.
Record the convergence time.
**Vacuity:** step 2 is the control (they agree when connected) and step 6 is the recovery control
(the partition was the cause). Without both, step 5's two leaders could be a startup race or a
permanently broken coordinator. Use the loopback firewall rather than killing a process: a killed
node is not a partition, and a stopped node produces one leader, which would pass a naive assertion
for the wrong reason.

---

## Coverage note

**Budget: 110. Written: 110.** IDs `STATE-001`–`STATE-110`, contiguous, no gaps.

Three notes on how the budget was spent, for whoever reviews this.

**Restore got 15 cases (STATE-050–064) and pruning got 10.** The index's description of this area
leads with "Checkpoints (write, prune, restore, ...)", which reads as three equal thirds. On this
code they are not. The write and prune paths are implemented, wired and load-bearing; the restore
path is implemented, tested, and called by nothing the product ships. Section F is therefore weighted
toward establishing *which specific claims fail* — a keyed aggregate is not stateful (STATE-052), a
restore with no matching lane key succeeds silently (STATE-062), the offsets are recorded and never
consumed (STATE-057), and the `application.yaml` sentence about recovering answers is false end to
end (STATE-063). Those are the cases a later wave can hand to whoever wires recovery, as the
definition of done.

**The cluster got 16 rather than the ~9 the 3×3 matrix implies.** The matrix itself is cheap — nine
`Configuration` objects and one factory call each, and eleven of the sixteen cluster cases need
neither a second node nor ZooKeeper. The extra seven are where the behaviour actually is: the
peer-list parser has four distinct failure shapes including one that throws a raw
`NumberFormatException` (STATE-107), PRV-9001 answers for two unrelated mistakes (STATE-105), and
`PARTITIONED` turns out to be a startup assertion with no runtime behaviour behind it (STATE-106).
STATE-110 is the only case in the area that genuinely needs two nodes, and it earns its place because
it observes the split brain that the whole `needsConsensus` design is built to prevent.

**Four cases are refusals of things the public API cannot express** (STATE-027, 028, 029, 032).
`requireSayableName` restricts names to `[A-Za-z_][A-Za-z0-9_]*`, so `..`, `a.b` and `a#!b` cannot
reach `checkpointDirectoryFor` through `register`. Rather than drop the hostile-name coverage the
brief asks for, those cases reach the encoder by reflection and STATE-031 establishes that the public
door is shut — because the encoder is the thing that will still be there if the name rules are ever
relaxed, and because STATE-026 shows one collision that *is* reachable today, on any
case-insensitive filesystem.

**Two cases are deliberately platform-conditional** and must report a skip with a reason rather than
a pass when the platform cannot run them: STATE-020, 036, 037, 088, 089 and 090 need POSIX
permissions, and STATE-026 reports a different expected result on a case-insensitive filesystem. A
silent skip on any of them is worse than a failure, because permissions on the two files that hold
customer data are exactly what nobody checks by hand.
