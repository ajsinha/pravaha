# ADV-JDK21 — adversarial pass over the Java 21 move, 2.2's read admission and security beans, and the NullAway sweep (2.3.1-SNAPSHOT)

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

IDs `J21-C01`–`J21-C34`. Execution record: [`../logs/ADV-JDK21.md`](../logs/ADV-JDK21.md).

**Target:** `develop` at `f58bfffc` (2.3.1-SNAPSHOT), **JDK 21** (`/usr/lib/jvm/java-21-openjdk-amd64`),
the engine image on `eclipse-temurin:21-jre`.

**Stance.** Targeted and adversarial: the code that changed most recently and is hardest to test —
ADR-062's monitor-to-`ReentrantLock` conversion — first, then 2.2's new surfaces, then the
`Objects.requireNonNull` calls the NullAway sweep added. A FAIL needs a reproduction.

## A. ADR-062's lock conversion (review, then load)

| ID | Case |
|---|---|
| J21-C01 | Every `lock()`/`lockInterruptibly()` in production code is followed by `try` with the unlock in `finally` (mechanical scan of all modules) |
| J21-C02 | No early return or throw between a `lock()` and its `try` |
| J21-C03 | `RegistryLock.call`/`run`: the 54 converted registry methods keep the same guarded section (diff review of `b6f04003`) |
| J21-C04 | `multiplexingLanes` validation moved into `RegistryLanes.configure`: same refusals, no half-applied state |
| J21-C05 | No converted class keeps a `synchronized` method guarding the same fields as its new lock (partial conversion) |
| J21-C06 | No caller still synchronizes on a converted object from outside (`synchronized (registry)` and the like), and no `Thread.holdsLock` assertion survives a conversion |
| J21-C07 | `Condition.await*` only in a loop and only holding its lock; `signalAll` wherever `notifyAll` was (`PravahaTester`, `Subscription`) |
| J21-C08 | Lock order: replacement → registry; rebalance → registry; alert service → registry; alert service → each alert's monitor → journal; registry → sink delivery / commit lock; `StreamCatalog.declaring` → registry. Any reverse edge? |
| J21-C09 | `ViewSink.Handoff`: a snapshot handed over under the hand-off lock while a commit races it; `PluginSourceFeeds`' serialised publish; `SharedSourceGroup` watcher vs `grow` vs `close` |
| J21-C10 | Many concurrent register / drop / pause+resume on shared and distinct computations, from virtual threads (in-process, seeded, iteration-capped) |
| J21-C11 | Subscribe / unsubscribe (plain and from a snapshot) storms while the view's query is dropped or replaced |
| J21-C12 | Replace, cut over, abandon and finish against concurrent drops of the same name |
| J21-C13 | Lane rebalances started while registrations and commits run (multiplexed lanes, share-from 2) |
| J21-C14 | Checkpoints cut (1 s interval) during drops and replacements |
| J21-C15 | Debug sessions forked during commits and drops |
| J21-C16 | Read-only listings (`queries`, `tenantUsage`, `pipelinesPerSharedLane`, replacement and rebalance status) under all of the above |
| J21-C17 | `-Djdk.tracePinnedThreads=full` over C10–C16: any virtual thread parked while pinned |
| J21-C18 | `ThreadMXBean.findDeadlockedThreads` after the run (it sees `ReentrantLock` cycles too) |

## B. 2.2's read admission and security extension points

| ID | Case |
|---|---|
| J21-C19 | `ReadAdmission` boundaries: max-concurrent 1, tenant-share rounding to zero, max-queued 0, queue-timeout 0, `UNLIMITED` |
| J21-C20 | A permit is released on every exit of a read: error, deadline, refusal (every acquire is try-with-resources) |
| J21-C21 | `ReadLimits` refuses negative / out-of-range settings with `PRV-1026` naming the key |
| J21-C22 | A custom `TokenVerifier` that returns `null` — HTTP, Flight, pgwire |
| J21-C23 | A custom `TokenVerifier` that returns `Principal.ANONYMOUS` — HTTP, Flight, pgwire |
| J21-C24 | A custom `TokenVerifier` that throws something other than `PravahaException` — HTTP, Flight, pgwire |
| J21-C25 | A custom `SecurityPolicy` that throws: the HTTP answer still carries a PRV code (`ApiErrorController`) |
| J21-C26 | `FileAuditSink`: rotation failure and recovery, a disk-full write, the close-time drain (review) |

## C. The NullAway sweep's `requireNonNull` additions

| ID | Case |
|---|---|
| J21-C27 | All 203 `requireNonNull` calls added since `v2.1.0` under `src/main`, each paired with the line it replaced: does any turn a previously handled `null` into an NPE? |
| J21-C28 | Externally reachable ones: `PolicyController` bindings with neither/both of object and tag |
| J21-C29 | Debug `until:` steps with missing parts (HTTP and Flight both parse with `DebugStep.Request.parse`) |
| J21-C30 | pgwire `RELEASE` / `ROLLBACK TO` without a savepoint name; a protocol failure before the backend exists |
| J21-C31 | Alert conditions and keys replayed from the alert journal (`IS NULL` conditions, encoded keys) |
| J21-C32 | Kafka: an Avro schema text that is the JSON `null`; protobuf without a descriptor and without a registry |

## D. The Java 21 image end to end

| ID | Case |
|---|---|
| J21-C33 | Build the image; run it; four churning SDK clients (register / drop / pause+resume / read) and four subscribers (plain and snapshot) against six names for 150 s while a followed CSV grows; then: no stall, no uncoded error, platform-thread count before / during / after, `jcmd Thread.print` from a sidecar sharing the pid namespace |
| J21-C34 | `PRAVAHA_JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"` starts cleanly and serves |
