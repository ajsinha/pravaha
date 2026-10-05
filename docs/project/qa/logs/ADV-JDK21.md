# ADV-JDK21 — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Cases: [`../cases/ADV-JDK21.md`](../cases/ADV-JDK21.md). Executed 2026-10-05 against **`f58bfffc`**
(2.3.1-SNAPSHOT) on **JDK 21** (`/usr/lib/jvm/java-21-openjdk-amd64`, 21.0.12), built with
`tools/worktree-build.sh` into the worktree's own `.m2-local`; the image on `eclipse-temurin:21-jre`.

**Result:** 34 cases, **3 FAIL**, all fixed on this branch — `J21-1` (MEDIUM), `J21-2` (LOW),
`J21-3` (LOW). No defect in ADR-062's lock conversion.

## A. ADR-062's lock conversion — PASS (C01–C18)

**Review.** Every `lock()` in production code is followed by `try`/`finally unlock()`; the two that
are not are `RegistryLock.lock()` (the delegate itself) and `IngestSources.freeze`, which is
deliberate and documented (released by `thaw`, which every caller runs in a `finally`, and by its own
catch). The 54 `RegistryLock.call`/`run` conversions keep their guarded sections; `RegistryLanes.configure`
keeps every refusal and, as a side benefit, no longer records `shareFrom` before validation fails. No
converted class still guards the same fields with a monitor (`RegisteredQuery`'s names, `StreamCatalog`'s
maps, `ViewSink.publishLock`, `Alert` and `AlertService.journalLock` remain monitors over CPU-only or
file-only sections, as the commit says). Nothing synchronizes on a converted object from outside, and
no `Thread.holdsLock` survives. `Condition.await` is looped and held in `Subscription` and `PravahaTester`.

**Lock order, as found** (an arrow is "held while taking"): replacement lock → registry lock;
`LaneRebalance` lock → registry lock; `AlertService` lock → registry lock and → every `Alert` monitor
→ `journalLock`; registry lock → `SinkDelivery` lock and → a query's commit lock (via `drop` /
`close`); `StreamCatalog.declaring` → registry lock; `PluginSourceFeeds.sharing` →
`SharedSourceGroup` lock. No path takes any of these in the reverse order: the registry never calls
into `QueryReplacements` under its lock except `isReplacing`, which reads a `ConcurrentHashMap` and
takes no lock; an `Alert`'s monitor calls only `audit`, `transition` and `journal` on the service, none
of which takes the service lock. The conversion kept every section, so the graph is the one the
monitors had.

**Load.** `RegistryLockStressTest` (committed, `pravaha-registry`): 12 virtual threads × 150 seeded
operations over six names and three SQL texts (two pairs share a computation) — register, drop,
pause+resume, subscribe and subscribe-from-snapshot, replace then cut over or abandon, finish or
abandon, lane rebalance (2 lanes, share from 2), debug fork, listings — while a feed appends and
checkpoints are cut every second. Asserts every worker finishes, `findDeadlockedThreads` is null, and
nothing threw except a `PravahaException`. Runs in ~3 s. Also run once at 2,500 iterations per worker
(30,000 operations, 13 s) and at 800 with `-Djdk.tracePinnedThreads=full`: **no deadlock, no stall, no
uncoded exception, no pinned-thread trace.** Every refusal was coded (PRV-8001/8002/8003, 4014/4016/
4017/4019, 8011/8012/8016).

## B. Read admission and security extension points — 1 FAIL (C19–C26)

C19–C21 by review: `ReadAdmission` rounds the tenant share up and never below one, every `acquire`
in `ViewQuery` is try-with-resources (so a refusal, a deadline or an error releases it), and
`ReadLimits` refuses out-of-range settings with `PRV-1026`. Two LOW observations, not fixed: the
queue-depth check is check-then-increment, so a burst can queue one or two past `max-queued`; and
`Lease.close` guards double release with a plain field, so two threads closing one lease at once
could release twice. Neither is reachable through the engine's own callers (one thread owns each
lease).

**C22–C24 FAIL → J21-1 (MEDIUM).** A custom `TokenVerifier` returning `null`: HTTP threw
`NullPointerException` in `mustChangePassword` (500, no PRV code); Flight and pgwire refused it.
Returning `Principal.ANONYMOUS`: HTTP **let the request through as the anonymous caller** — the
principal a node without authentication serves — while Flight and pgwire refused it. Throwing
`IllegalStateException`: HTTP 500; Flight `UNKNOWN` with no code (Arrow's interceptor catches only
`FlightRuntimeException` from a middleware factory); pgwire closed the socket with no ErrorResponse
(the sign-in caught only `PravahaException`). Fixed: each is `PRV-7001` — HTTP 401, Flight
`UNAUTHENTICATED`, pgwire `FATAL 28000` — with the verifier's failure logged and not sent.
`BearerTokenFilterVerifierContractTest`, `FlightVerifierFailureTest`, `PgVerifierFailureTest`.

C25 PASS: a non-coded exception on HTTP is answered by `ApiErrorController` with `PRV-1052`.
C26 PASS by review: `FileAuditSink` reopens after a failed write and writes an `audit.lost` marker,
retries rotation every 5 s, and drains the queue at close. (LOW, not fixed: `drainForever`'s
`streamBroken` runs outside the monitor that guards `out`.)

## C. The NullAway sweep's `requireNonNull` — 1 FAIL (C27–C32)

All 203 additions since `v2.1.0` were paired with the line each replaced. Almost all replace a
dereference that would have thrown the same NPE a line later; the externally reachable ones checked
in C28–C31 (`PolicyController`'s `tagged()` refuses neither/both first; `DebugStep.Request.parse`
refuses a short `until:`; pgwire's savepoint regexes require a name, and `handshake` answers its
own refusals before the backend exists; alert journal replay passes a null literal only for `IS_`
conditions and encodes a key as at least four bytes) are sound.

**C32 FAIL → J21-2 (LOW).** `AvroSchema.parse("null")` was `AvroSchema.Invalid` ("a schema must be
a name, an object or a union, not null") before the sweep and a `NullPointerException` after it —
which `SchemaRegistry`'s lookup, catching `Invalid` to dead-letter a record, does not catch. Fixed;
`AvroRowReaderTest.theJsonNullIsAnInvalidSchemaNotANullPointerException`.

## D. The Java 21 image — 1 FAIL (C33–C34)

`deploy/docker/build.sh` built the image (eclipse-temurin:21-jre, JDK 21.0.12.1). The JRE carries no
`jcmd`; it was attached from a sidecar sharing the node's pid namespace with the host JDK 21 mounted
(`docker run --pid container:<node> --user 10001 -v $JDK:/jdk --entrypoint /jdk/bin/jcmd <image> 1
Thread.print`), which works with `-XX:+PerfDisableSharedMem`.

C33: four churning SDK clients and four subscribers for 150 s against six names while a followed
CSV grew: 55,136 registrations, 55,132 drops, 51,816 pause+resume, 47,970 reads, 93 subscriptions
read to two commits; every refusal coded (the Python SDK reports `PRV-1041` wrapping the engine's
code — PRV-8001, 8002, 4023, 8018 on a rerun that classified by the inner code). No stall, no
deadlock in `Thread.print`, no client thread left hanging. Platform threads 55 idle → 100 under load
→ 103 after (the lane runner's 24 threads start at the first registration; the rest are carriers and
gRPC event loops) — no growth with the operation count.

**C33 FAIL → J21-3 (LOW).** The node's log held **110,292** `SensitiveFiles` WARNINGs: "could not set
rwx------ on /opt/pravaha/data", one per journal append and checkpoint, because the bind-mounted
volume is not the node's to `chmod` — as a Kubernetes `fsGroup` volume is not either. Fixed: once
per path per process, then DEBUG. `SensitiveFilesTest.aDirectoryThatCannotBeNarrowedIsReportedOnceNotOnEveryWrite`
(fails before the fix, passes after).

C34 PASS: `PRAVAHA_JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational"` starts, comes ready, `VM.flags` shows
both, and a 75 s soak against it ended with the same outcome (112 platform threads, no stall).

Every container, the image and the work directory were removed afterwards.
