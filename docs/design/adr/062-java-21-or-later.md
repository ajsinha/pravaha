# ADR-062: Java 21 or later

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **built**: every module, `pravaha-api` and the Java SDKs included, compiles to Java 21 class files (`maven.compiler.release=21`); the enforcer accepts JDK 21 or later to build; the launchers refuse a JVM older than 21; the engine image runs on `eclipse-temurin:21-jre`; CI builds and tests the whole reactor on 21 and on 25; the monitors a virtual thread blocks inside are `ReentrantLock`s (below) |
| Date | 2026-10-04 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-061 — **supersedes its baseline** (Java 25 only); ADR-001 (language and platform); ADR-047 and ADR-053 (the image's JRE); [COMPATIBILITY.md](../../operations/COMPATIBILITY.md) |

## Decision

The owner, 2026-10-04: "migrate everything to JDK21 so any java 21 onwards can run pravaha, this
includes server image as well … jre 22, 25 etc will be able to run pravaha as well".

- **Every module targets Java 21**: the engine, the server, the plugins, `pravaha-api`, the Java
  SDKs, the embedded engine, the Spring Boot starter, the CLI and the benchmarks. No Java 22+
  language feature or API is used from here on (`javac --release 21` enforces it).
- **Any JDK from 21 builds and runs it.** The enforcer requires `[21,)`; `tools/jdk.sh` (was
  `tools/jdk25.sh`) accepts 21 or later. CI runs the whole reactor on **21 and 25**, Error Prone on
  21, every other job on 21.
- **The image runs the floor**: `eclipse-temurin:21-jre`, so what is released is what the lowest
  supported JRE runs. One image, no JRE choice.
- **Launcher options valid on both**: `--enable-native-access=ALL-UNNAMED` always (accepted from
  21); `--sun-misc-unsafe-memory-access=allow` only when the JVM is 23 or later, because a JVM that
  does not know an option refuses to start rather than ignoring it.

## Context

ADR-061 made 25 the only JDK in 2.0 and said what it cost: an embeddable library inherits its
host's JVM, and much of enterprise Java is on 21. That cost turned out to be the one that mattered.
Nothing in the code used anything after 21 except 54 unnamed variables (`_`, a Java 22 feature) and
one call to `Console.isTerminal()` (22). No third-party dependency needs more than 21.

What 2.x *did* rely on, silently, is JEP 491 (JDK 24): a virtual thread that blocks inside a
`synchronized` monitor no longer pins its carrier. On JDK 21 it does, and so does one waiting to
enter a monitor. Pinned carriers are taken from a pool sized to the cores, and when they are all
taken every other virtual thread -- feeds, subscription deliveries, clock ticks, requests -- waits,
with no error. If the pinned threads are themselves waiting for one of those, the node stops.

## Virtual threads on JDK 21: the audit

What runs on virtual threads, what it blocks on, and what was done. "Converted" means the monitor
became a `ReentrantLock` (and `Condition` for wait/notify), taken and released exactly where the
monitor was: same reentrancy, same ordering, unfair as monitors are. A monitor around CPU work
only, or around file I/O (JDK 21 compensates for a pinned file read or write by adding a carrier
for its duration), is left: a thread blocked on it waits for a holder that is running.

| Runs on a virtual thread | Blocks on | Monitors entered, and what was done |
|---|---|---|
| **HTTP requests** (`spring.threads.virtual.enabled`) and **Flight calls** (`PravahaFlightServer`'s executor): the whole control plane | network I/O at registration (a sink's connection, a source's open or join), waits for lane control tasks, read admission, a subscription's handover queue | `QueryRegistry` (54 methods; a `RegistryLock`), `synchronized (registry)` in `ContinuousQueryStatements` and `DraftFingerprint` (the same lock), `QueryReplacements` (13), `LaneRebalance` (6), `AlertService` (8), `DebugSession` (11, steps lanes), `PravahaMetrics.sync` and `StreamCatalog`'s declaring lock (both call into the registry), `PluginSourceFeeds`' sharing lock (6), Kafka's metadata lock and Aerospike's client lock (connect under them): **converted**. `Catalog`, `IdentityService` (Argon2, file store), `ServedView`, `ViewSink.publishLock`, `RegisteredQuery`'s names, `QueryReplacement`, `Alert`, `RecoveryRefusals`, `RowNarrowing`, `ViewQuery.plans`: CPU or file only, **left** |
| **Source feeds**: `PumpingFeed` (one per query), `SharedPartitionFeed` (one per shared partition), `UpstreamFeed` (a query over a query) | the source's read (plugin I/O), the lane's inbox, and the commit they publish -- which hands rows to every listener, sinks writing over the network among them | `RegisteredQuery`'s commit lock, `ViewSink.Handoff` (a snapshot subscriber's listener is called under it), `SinkDelivery` (6: writes, prepares, commits), `PluginSourceFeeds`' serialised publish, postgres-cdc's `CdcStream` (SQL on the control connection): **converted**. `SharedPartitionFeed` and `Subscription` already used `ReentrantLock`. `UpstreamReader`, `QueryExecution`'s watermark tracker: CPU only, **left** |
| **`SharedSourceGroup`'s watcher** | listing the source's partitions and opening readers on new ones (network) | `SharedSourceGroup` (4): **converted** |
| **`SharedClock` work**: watermark ticks, periodic checkpoints | waits for lane control tasks (`parkNanos`), checkpoint files, a transactional sink's commit | `SinkDelivery` (converted, above); the tracker monitor is CPU only |
| **Subscription delivery** (`Subscription`) | the subscriber's listener | already a `ReentrantLock`; the listener is called outside it |
| **Lane rebalance worker** | `Thread.sleep` between polls, a replacement | sleeps outside any monitor; `QueryReplacements` converted |
| **`LookupJoin` lookups** | the lookup plugin's I/O; the lane (a platform thread) waits for them | Aerospike's client lock converted; nothing else of ours |
| **`SocketCoordinator`'s accept loop** | `accept()` and one heartbeat exchange | no monitor |
| **mysql-cdc `BinlogStream`** | a virtual thread only to disconnect | the binlog is read on a platform thread, whose `wait()` is the only wait; **left** |
| **`PravahaTester.awaitView`** (the starter's test helper; its caller may be virtual) | `Object.wait` inside a monitor | **converted** to a lock and a `Condition` |

**Measured.** With `-Djdk.tracePinnedThreads=full` on JDK 21 (it prints a stack whenever a virtual
thread parks while pinned), the test suites of runtime, serving, registry, bindings, flight,
cluster, catalog, identity, backfill, server, embedded, pgwire, the starter, the CLI and the Kafka,
postgres-cdc, mysql-cdc and Aerospike plugins ran twice: as they are, and with every test method
itself run on a virtual thread (a JUnit interceptor, outside the tree). Before the conversions and
after: **no pinned park**, and every test passed. The trace cannot see a thread blocked entering a
monitor, or `Object.wait`, and the unit suites do not open real sinks over the network; those are
what the static audit above is for, and the conversions are made for the paths the tests could not
show.

## Reasoning

- **The owner's decision**, and the cost ADR-061 accepted turned out to be the one that mattered:
  embedders on 21.
- **21 is an LTS and the floor of the current enterprise estate**; every JDK after it runs Java 21
  class files unchanged, so one jar serves 21, 22, 25 and later.
- **Shipping the floor** in the image means the configuration CI's 21 leg tests is the one that
  ships; a 25 JRE would leave the 21 path proven only by the matrix.
- **Converting the monitors, not avoiding virtual threads on 21**: request and feed threads stay
  virtual on every JDK, as design §13.2 wants; the locks cost nothing measurable on 24+, where a
  monitor would also have been fine.

## Consequences

- **Clients on Java 21 or later.** Embedders, plugin authors and Java SDK users need 21 (class-file
  version 65), not 25. The wire does not change.
- **The Spring Boot starter's tested lines stay 3.4 and 3.5.** Boot 3.2 and 3.3 could read Java 21
  class files again, but they are out of open-source support and their legs are not brought back.
- **JDK 21 runs ZGC non-generational by default** (generational from 23, the only mode from 24);
  the JMH benchmarks ask for `-XX:+UseZGC` and so measure different collectors on 21 and 25. The
  figures are in [the 21-against-25 pack](../../project/gates/measured-2026-10-04-jdk21/README.md).
- **New code**: no Java 22+ feature or API; a `synchronized` block that can block -- I/O, a lock, a
  queue, a future, a sleep, a lane -- on a path a virtual thread takes is a `ReentrantLock`.
- ADR-061's history stands: 2.0 to 2.2 were Java 25 only.
