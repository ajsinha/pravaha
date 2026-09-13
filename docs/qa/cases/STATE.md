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
**Expected (Linux, ext4/xfs):** step 2 is `2`. Step 4: `Q/checkpoint-1.bin` and `q/checkpoint-1.bin`,
independent id sequences. **Expected (macOS APFS default, Windows NTFS):** step 2 is `1`. Step 3's
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
