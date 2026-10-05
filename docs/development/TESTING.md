# Testing Pravaha

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Every test tier the repository has, what each needs, how to run it with and without Docker, and how
long it took on the development machine. This is the reference; for a numbered walkthrough from a
fresh clone see [Build and test without Docker](setup/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md), and for the
container route [Build and test with Docker](setup/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md).

**Where the numbers come from.** Every count and time below was taken on 2026-09-29 on the
development machine (24 cores, 61 GiB RAM, NVMe, Docker Engine 29.8, OpenJDK 21.0.12, Python
3.14.4), in a linked worktree of commit `3ea60cb6` plus the fixes this document was written beside,
with other builds running. Treat the times as an order of magnitude, not a benchmark. **Every tier
runs on JDK 21 or later** ([ADR-062](../design/adr/062-java-21-or-later.md); CI runs the whole reactor on 21 and 25); the
counts below were taken on 21. On 25 the whole reactor (`tools/worktree-build.sh -o clean install`
in a linked worktree, 2026-10-01) ran **4,832 tests, 0 failures, 12 skipped** in 19 min 14 s, and the
Python SDK's suite 426 passed. After the adversarial QA's three fix waves (2026-10-02) the main
checkout's full build ran **5,112 tests, 0 failures, 0 errors, 122 skipped** (summed from its
surefire and failsafe reports), the SDK suite collects 432 tests and the console's 1,957. A tier marked
**not run in this pass** is described from its own source and header, not from a run.

---

## The tiers at a glance

| Tier | Where | Needs | Command | Measured |
|---|---|---|---|---|
| Unit and module tests | every module's `src/test` (`*Test`) | JDK 21 or later | `tools/worktree-build.sh -o test -pl <module>` | `pravaha-algebra` 42 tests, 3 s; `pravaha-registry` 344, 44 s; `pravaha-server` 323, 54 s |
| Property tests (jqwik) | `pravaha-algebra`, `-common`, `-serving`, `-codegen`, `-backfill`, `-bindings`, `-it` | JDK 21 or later | part of the unit run | included above |
| In-process integration (`pravaha-it`) | `pravaha-it/src/test` | JDK 21 or later | `tools/worktree-build.sh -o verify -pl pravaha-it` | 960 tests (2 skipped), 3 min 00 s |
| Documentation and register checks | `DocumentationFreshnessTest`, `MarkdownLinksTest`, `FindingsRegisterTest`, `QuickstartCommandsTest`, `ExamplesTest` … in `pravaha-it`; `ErrcCrossCuttingTest`, `ContinuousQueriesClaimsTest`, `DocumentedLimitsTest` in `pravaha-cli` | JDK 21 or later | `-Dtest=DocumentationFreshnessTest,MarkdownLinksTest,FindingsRegisterTest` | 28 tests, 7 s (the first two) |
| Adversarial suites | `pravaha-it/.../it/qa/adversarial` (`Adv*Test`, 110 tests); `tests/qa/adv_surface` (21, Python, against a running node) | JDK 21 or later; a node for the surface suite | `-Dpravaha.qa.adversarial=true`; `PRAVAHA_QI_HTTP=… pytest tests/qa/adv_surface` | opt-in, not in any gate; see [below](#the-adversarial-suites) |
| Container-backed plugin tests | `plugins/*` (`*IT` and `@Testcontainers` `*Test`) | Docker | `sg docker -c 'tools/worktree-build.sh -o verify -Pit -pl <plugin>'` | 211 container tests across 6 plugins and 3 in `pravaha-it`, green; see [below](#with-docker) |
| Container-backed `pravaha-it` | `AerospikeContinuousQueryIT`, `AerospikeSourceScaleIT` | Docker | as above, `-pl pravaha-it` | 3 tests, 56 s |
| Performance gates | `pravaha-it/.../qa/perf/*GateIT`, `RestartCompileIT`, `NexmarkCoverageIT`; `pravaha-runtime/.../*MeasurementIT` | JDK 21 or later, a quiet machine, no coverage agent | see [Performance and measurement](#performance-and-measurement) | skip themselves under the coverage agent; **re-measured on JDK 25 on 2026-10-04** ([gate pack](../project/gates/measured-2026-10-04-jdk25/README.md)), all but the spill tier's |
| Python SDK | `sdk/python/tests` | Python ≥ 3.9 venv; built `pravaha-flight` test classes | `make -C sdk/python test` | 426 passed, 0 skipped, 1 min 37 s (432 collected on 2026-10-02) |
| Console | `pravaha-console/tests` | Python ≥ 3.11 venv; Chrome or Chromium for the browser suites | `make -C pravaha-console test` / `make -C pravaha-console test-fast` | 1,937 passed, 1 skipped, 31 min 27 s with Chrome ([Console](#console)); 1,957 collected on 2026-10-02 |
| SDK, standalone | `tools/sdk-standalone-check.sh`, after `tools/build-sdk.sh` | Docker (or a running node), Maven, Python with venv or uv | `sg docker -c "tools/sdk-standalone-check.sh --docker pravaha/pravaha-server:local"` | four clients outside the repository (Maven, `-all` jar, wheel with and without `[flight]`), green on 2026-09-30 ([below](#the-sdks-on-their-own)) |
| Deck | `tests/deck` | `tools/deck/.venv` (python-pptx) | `tools/deck/.venv/bin/python -m pytest -q tests/deck` | 5 passed, 1 s |
| The gate | whole reactor | JDK 21 or later, the shared `~/.m2` | `tools/verify-clean.sh` | **not run in this pass**; its header records 5 min 50 s for 2,274 tests |
| A running stack | a real node, the console, the CLI, pgwire | the built jars (or Docker) | [End to end](#end-to-end-against-a-running-stack) | walked through, outside Docker |

---

## Prerequisites

| | Check | Notes |
|---|---|---|
| JDK 21 or later | `$JAVA_HOME/bin/java -version` | The build enforces `[21,)` (`enforce-build-environment`), and refuses a dependency with classes newer than Java 21 (`enforce-java-21-bytecode`). The build scripts source `tools/jdk.sh`: with `JAVA_HOME` unset it takes the first of `/usr/lib/jvm/java-21-openjdk*` and `/usr/lib/jvm/java-25-openjdk*` (21 first: production runs 21), then a `javac` 21 or later on `PATH`, and refuses an older one by name |
| The Maven wrapper | `./mvnw -v` | vendored; the builds below run offline (`-o`) once `~/.m2` is populated |
| Python | `python3 --version` | SDK ≥ 3.9, console ≥ 3.11. Measured with 3.14.4 |
| `venv` / `ensurepip` | `python3 -m venv /tmp/x` | Debian and Ubuntu ship it separately (`python3.X-venv`). Without it `make install` fails with *"ensurepip is not available"*. Workaround used here: `uv venv --seed .venv`, then `make install` |
| Chrome or Chromium | `google-chrome --version` | only for the console's browser suites; `PRAVAHA_CHROME=<path>` to name one. The console drives it over the DevTools protocol itself — Playwright is **not** used, and its browser downloads are not needed |
| Docker | `docker ps` | only for the container tier. Testcontainers pulls its own images; see [With Docker](#with-docker) |
| Disk | | a built reactor plus a worktree `.m2-local` is a few GiB; container images are listed below |

**Environment.** The runs below used

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64   # or any JDK 21 or later, e.g. java-25-openjdk-amd64
export TMPDIR=$HOME/.cache/pravaha-tmp
export MAVEN_OPTS=-Djava.io.tmpdir=$HOME/.cache/pravaha-tmp
```

`TMPDIR` is moved off `/tmp` because `/tmp` is a RAM-backed tmpfs on many machines and the
spill, checkpoint and state tests write real files; `MAVEN_OPTS` does the same for the Maven JVM and
the test JVMs it forks.

---

## Building

```bash
./mvnw -o -q install -DskipTests                    # the main checkout
tools/worktree-build.sh -o -q install -DskipTests   # a linked git worktree
```

78 s for the whole reactor from an empty `target/` (warm `~/.m2`). `-DskipTests` still compiles
the test classes, which the Python SDK's live-server fixture needs (`pravaha-flight/target/test-classes`).

### Static analysis: Error Prone and NullAway (`-Pep`)

```bash
./mvnw -Pep clean test-compile        # or: tools/worktree-build.sh -Pep clean test-compile
```

Runs [Error Prone](https://errorprone.info) 2.50 as a javac plugin over every module, main and test
code, with NullAway (`AnnotatedPackages=com.ash.messaging.pravaha`); generated sources are left
out. javac is forked with `jdk.compiler`'s internals opened, so the default build is unchanged. Not
part of the default build or of `-Pall` (`-Pall,ep` for both).

**What fails the build.** Every check Error Prone enables at WARNING is raised to ERROR by the
`errorprone.ratchet` property in the root POM (one `-Xep:<Check>:ERROR` per check, generated from
Error Prone's `BuiltInCheckerSuppliers.ENABLED_WARNINGS` — regenerate it when `errorprone.version`
moves), so in every module that is clean a new Error Prone warning is a compile error. `pravaha-api`,
`sdk/pravaha-sdk-java` and `sdk/pravaha-sdk-java-flight` are not clean yet and set the property empty;
removing that override is how one joins. NullAway is not on the list; its level is `ep.nullaway.level`,
ERROR for the whole reactor since every module reached zero (2.2.0), so a NullAway finding fails `-Pep`
as well. The
`fast` workflow's `errorprone` job runs `./mvnw -Pep -DskipTests clean test-compile` (main and test
code) on every push. A finding the code is right about is suppressed at the narrowest member that
holds it, with a `// reason` on the same line; never module-wide.

**Counts** (`-Pep clean test-compile`, unique warnings, whole reactor):

| | NullAway | Error Prone WARNING checks | javac `-Xlint` | total |
|---|---|---|---|---|
| 2026-10-02, ERRORPRONE-1 | 2,833 | 600 (`StringSplitter` 100, `ArrayRecordComponent` 61, `NotJavadoc` 54, `MissingOverride` 42, `UnusedVariable` 39, …) | 207 (`try` 109, `dangling-doc-comments` 57, `deprecation` 31, …) | 3,642 |
| 2026-10-03, ERRORPRONE-2 | 2,826, counted before `pravaha-api` and the SDKs were cleared (those three are 0 and held there by `failOnWarning`) | 0 | 0 | 2,826 at most |
| 2026-10-04, the engine core, Flight and pgwire gated | 1,802 (2,698 the same day before the sweep), in 17 modules; 21 modules at 0 and held there | 0 | 0 | 1,802 |
| 2026-10-04, the hosts and front ends gated | 1,051, in 10 modules (`pravaha-it` 276 and nine plugins); 28 modules at 0 and held there | 0 | 0 | 1,051 |
| 2026-10-04, the plugins and `pravaha-it` gated | 0; all 38 modules at 0 and held there, and `ep.nullaway.level` is ERROR for the reactor | 0 | 0 | 0 |

The ERRORPRONE-1 run counted 3,570 because it compiled main and test code in separate passes. Of the
roughly 800 cleared, most were fixed in place; the suppressions are mainly `ArrayRecordComponent` on
records whose arrays are compared by content already or never compared at all (48),
`try` on test methods holding an Arrow resource whose `close()` declares `InterruptedException`, and
`ReferenceEquality` and `NonAtomicVolatileUpdate` where identity or a single writer is the design.
The `StringSplitter` sweep found four parsers that accepted a trailing separator (SPLITTRAIL-1..4),
`SystemConsoleNull` a CLI that coloured redirected output (ANSICONSOLE-1), and `ObjectToString` an
error naming an object's identity (FEEDGONE-1).

**The client modules are gated (2.1).** `pravaha-api`, `pravaha-sdk-java` and
`pravaha-sdk-java-flight` were brought to zero — NullAway, Error Prone's WARNING checks and javac's
`-Xlint`, main and test code — and each module's own `ep` profile holds them there: NullAway at ERROR
(`ep.nullaway.level`, which the parent set to `WARN` until every module was at zero) and `failOnWarning`, so any new warning in
them fails `-Pep`. Before: 72, 6 and 61 warnings; after: 0. Nullness there is a contract, written
with [JSpecify](https://jspecify.dev)'s `@Nullable` (`org.jspecify:jspecify`, the version in
`jspecify.version`): a parameter, return or record component without it is never null, and the
javadoc says what null means where one is allowed. `pravaha-api` and `pravaha-sdk-java` take the jar
compile-only (`provided`), so they stay dependency-free — `pravaha-api`'s enforcer rule admits that
one artifact in that scope and nothing else — and `pravaha-sdk-java-flight` takes it at compile scope,
which adds nothing for a client because Guava, through Arrow, already depends on the same jar. A
test that passes null on purpose, to pin a refusal or a tolerance, says so with
`@SuppressWarnings("NullAway")` and a comment. To bring another module to zero, fix it under `-Pep`,
then copy the `ep` profile from one of these three poms.

**The engine core and two surfaces are gated (2.2.0).** Eighteen more modules were brought to zero
and carry the same `ep` profile: `pravaha-common`, `-algebra`, `-catalog`, `-sql`, `-runtime`,
`-state`, `-security`, `-backfill`, `-codegen`, `-testkit`, `-cluster`, `-connect`, `-serving`,
`-registry`, `-flight`, `-pgwire`, `plugins/pravaha-cluster-zookeeper` and
`plugins/pravaha-plugin-iceberg` (before: 13, 3, 39, 113, 176, 19, 21, 11, 14, 7, 31, 4, 51, 205, 86,
85, 6 and 10). `pravaha-common` takes jspecify at compile scope, so every engine module sees it;
`pravaha-security` takes it `provided`, staying dependency-free. Most findings were a method or field
that is null by design saying so; where an invariant the analysis cannot follow makes a value
non-null (a fused chain ends at a scan, a described sink was named, a stopped source has its stop),
the read is `Objects.requireNonNull(value, "the reason")` at the place the code already dereferenced
it, so nothing behaves differently. `PravahaException.getMessage()` is declared non-null, which it
always was. One finding was a bug (NULLREFUSAL-1): a recovery refusal built from a message-less
exception threw from inside the catch meant to keep one bad journal entry from stopping the rest.
Suppressions in main code are 17, each on one member with its reason: 9 `NullAway.Init` on fields a
lifecycle sets before use (a row cursor's region, a lane's batch arrays, a plugin's settings from
`configure()`), and 8 where the analysis cannot see the contract (an expression's `evaluateString`,
read only when `isNull` is false; `ViewNames.localName`/`shown`, null only for a null name; a
probe-only null principal; an unreachable sink factory; a record constructor that defaults a null).
Test code adds 83 nulls-on-purpose and 55 `NullAway.Init` on fields a test's own setup assigns.

**The hosts and front ends are gated (2.2.0).** Seven more modules carry the `ep` profile:
`pravaha-server`, `pravaha-identity`, `pravaha-bindings`, `pravaha-spring-boot-starter`,
`pravaha-embedded`, `pravaha-cli` and `pravaha-benchmarks` (before: 424, 122, 91, 35, 26, 16 and 15).
The same rules held: what a node or engine builds at start and drops at stop is `@Nullable` and read
through an accessor that requires it; every `@RequestParam`/`@RequestBody(required = false)` is
`@Nullable`, as are the API's DTO components that are absent until they exist and the optional
settings of a `@ConfigurationProperties` bean (field, getter and setter); a few signatures below
them (`QueryRegistry.checkpointingTo`, `PravahaFlightServer`/`PravahaPgWireServer.encryptedWith`,
`IdentityService.changePassword`, `PolicyService.create`) now say they take the null they already
handled. No bug was found. Suppressions in main code are 21: 15 `NullAway.Init` on JMH `@Param` and
`@Setup` state, 3 on a shared reader's member and route (set by `join` before use), 2 on fields
Spring sets through an `Aware` callback, and one `NullAway` where a builder hands a missing SQL to the
constructor that refuses it. Tests add 6 nulls-on-purpose and 2 `NullAway.Init`.

**The plugins and `pravaha-it` are gated, and NullAway is ERROR everywhere (2.2.0).** The last ten
modules carry the `ep` profile: the `feedfile`, `filesystem`, `delta`, `mysql-cdc`, `postgres-cdc`,
`aerospike`, `cassandra`, `jdbc` and `kafka` plugins and `pravaha-it` (before: 18, 27, 36, 83, 85, 97,
99, 136, 194 and 276), and the root POM's `ep.nullaway.level` is now ERROR, so a module added later
starts gated. Every `createReader` takes the `@Nullable` resume offset the API declares; what a
plugin builds in `open()` and drops in `close()` (a client, a session, a connection, a pool) is
`@Nullable` and read through the `requireOpen()` that already guarded it, now returning it; optional
settings (a registry, TLS, a reader schema, an upsert statement) and documented "or null" answers say
so; and a value an earlier branch guarantees (an Avro array's items, a pushed plan's reads, a decoded
snapshot row's key) is `Objects.requireNonNull` where the code dereferenced it anyway. No bug was
found. Suppressions in main code: 113 `NullAway.Init` on settings `configure()` or `open()` assigns
before use, and 3 `NullAway`: the JDBC and Kafka sinks' `closeQuietly()`, which let go of clients
that `requireOpen()` guards at every one of their uses, and Delta's `ColumnVector.getString`, which
returns null for a null cell through Kernel's unannotated signature. Plugin tests add 4
nulls-on-purpose and 8 `NullAway.Init`. `pravaha-it`, test code only, annotates its row records and
helpers with `@Nullable`, has 21 `NullAway.Init` on fields a case's setup assigns, and 118
suppressions at the narrowest member (often one local declaration), each with its reason -- nulls
passed into production APIs on purpose, or a lookup the case has just made. Its `ep` profile also
turns off `-Xlint:path`, since a module without main sources has no `target/classes` to point at.

### Which Maven to call: stale jars (MAVENRACE-1)

`./mvnw -pl <module> test` resolves the module's Pravaha dependencies from `~/.m2`, not from the
working tree. A change in `pravaha-registry` is invisible to a test in `pravaha-it` until something
reinstalls it, and several checkouts sharing one `~/.m2` install each other's half-finished jars.

| You are in | Use | Why |
|---|---|---|
| a linked git worktree | `tools/worktree-build.sh <maven args>` | a Maven repository of the worktree's own, `<worktree>/.m2-local`, seeded from `~/.m2` by hard links without Pravaha's artefacts. Install the modules you change (`install -pl <module>`) before testing a module that depends on them |
| the main checkout, iterating | `./mvnw -o -pl <module> -am test` or `tools/check.sh <module>` | `-am` rebuilds the dependencies in the reactor. `check.sh` skips spotless and is not a gate |
| the main checkout, before a commit | `tools/verify-clean.sh` | deletes Pravaha's artefacts from `~/.m2` first, then `clean install` and a full `verify`. **Never from a worktree while another checkout is building** — it deletes shared jars |

### The SDKs on their own

The client SDKs are built and shipped apart from the server. `tools/build-sdk.sh` builds only what a
client needs, `pravaha-api`, `pravaha-sdk-java`, `pravaha-sdk-java-flight` (thin jar and `-all`
jar) and the Python wheel and sdist, into `target/sdk-dist/`, with a `README.txt` saying what each
file is. It never builds the server, and refuses to continue if Maven's reactor holds anything but
those three modules and the parent POM:

```bash
tools/build-sdk.sh                  # 7 s warm; --install also puts the Java SDK in the Maven repository
tools/build-sdk.sh --java-only      # or --python-only; `-- -o` passes -o to Maven
```

It passes `-Dsdk.standalone`, which drops the Flight SDK's test-scope server dependencies (its own
tests start a real server) so `-am` does not follow them into the engine, and skips that module's
tests; every other build runs them. `tools/sdk-standalone-check.sh` then proves the artefacts work
with nothing else: a Maven client whose only dependency is `pravaha-sdk-java-flight`, resolved from
a repository holding no other Pravaha artefact (its dependency tree is printed and checked), the
same client on the `-all` jar with plain `javac`/`java`, and the wheel installed with and without
`[flight]` into fresh virtualenvs, all outside the repository and against a throwaway node. It
starts a container, so it is not part of any gate. `SdkIndependenceTest` in `pravaha-it` is the
part that is: it fails if an SDK module reaches a server module, a server module reaches an SDK,
the server's executable jar carries SDK classes, or the Python package needs more than the
standard library to import.

---

## Without Docker

Everything except the container tier runs with no Docker at all, and that tier does not fail —
it **skips**. Measured by running the six container-backed plugins with the daemon unreachable
(`verify -Pit`, 31 s, `BUILD SUCCESS`):

| Plugin | Surefire run / skipped | Failsafe run / skipped |
|---|---|---|
| aerospike | 60 / 0 | 40 / **40** |
| jdbc | 89 / 0 | 13 / **13** |
| kafka | 229 / **47** | none |
| postgres-cdc | 64 / **50** | none |
| mysql-cdc | 17 / 0 | 9 / **9** |
| cassandra | 52 / 0 | 39 / **39** |

and in `pravaha-it`, `AerospikeContinuousQueryIT` (2) and `AerospikeSourceScaleIT` (1) skip.

**A green build without Docker has not tested any connector against its store.** The skips are
deliberate — `@Testcontainers(disabledWithoutDocker = true)` on the Kafka and Postgres-CDC classes,
`assumeThat(DockerClientFactory.instance().isDockerAvailable())` in the `*IT` classes — and the
report says `Skipped`, which is easy not to read. Read it.

What does run without Docker, all of it green in this pass:

```bash
# one test class, or one method
tools/worktree-build.sh -o test -pl pravaha-registry -Dtest=UpsertSinkKeyRowsTest
tools/worktree-build.sh -o test -pl pravaha-registry -Dtest='UpsertSinkKeyRowsTest#anUpsertThatReplacesAKeysRowArrivesAsTheNewRowAlone'

# one module
tools/worktree-build.sh -o test -pl pravaha-algebra                    # 42 tests, 3 s

# a few modules at once, continuing past a failure
tools/worktree-build.sh -o verify -fae -pl pravaha-server,pravaha-it   # 323 + 960 tests, 3 min 54 s

# the documentation and findings-register checks alone
tools/worktree-build.sh -o test -pl pravaha-it -Dtest='DocumentationFreshnessTest,FindingsRegisterTest'
```

Add `-Dsurefire.failIfNoSpecifiedTests=false` when a `-Dtest` pattern names classes only some of
the modules in `-pl` contain.

**Mocked connectors still run.** Each plugin also has tests over fakes that need no store: Kafka's
`MockProducer`/`MockConsumer` (`KafkaUpsertKeyRowsTest`, `KafkaSourceFakeTckTest`), H2 for
`jdbc-sink`, local files for Delta and Iceberg, an embedded ZooKeeper (`curator-test`) for the
cluster plugin. They are the non-skipped numbers in the table above.

### `pravaha-it` runs its ITs even without `-Pit`

`pravaha-it/pom.xml` binds failsafe itself, so `verify -pl pravaha-it` runs its `*IT` classes
whether or not `-Pit` is given. Without Docker the Aerospike ones skip, and the performance ones
skip under the coverage agent (below), so a plain `verify` reported *Tests run: 12, Skipped: 11*.

---

## With Docker

### Enabling it

1. The daemon must be reachable by the user running Maven. If your account was added to the
   `docker` group after your session started, the session does not have it yet; either log in
   again or run the build inside the group:

   ```bash
   sg docker -c 'tools/worktree-build.sh -o verify -Pit -pl plugins/pravaha-plugin-kafka'
   ```

2. `-Pit` binds failsafe's `integration-test` and `verify` goals in every module (the root
   `pom.xml`'s `it` profile). The `@Testcontainers` `*Test` classes in Kafka and Postgres-CDC run in
   the ordinary `test` phase and need only the daemon.

3. **Count what ran.** A container test that cannot reach Docker skips, and the build stays green.
   After a run, check that the container classes report `Skipped: 0`, or use the CI helper:

   ```bash
   deploy/ci/assert-suite-ran.sh failsafe 20
   ```

   It counts executed failsafe tests (run minus skipped) under every `target/failsafe-reports`
   and fails below the floor. It does not see the Kafka and Postgres-CDC surefire classes; check
   those by their own `Skipped` counts.

The Docker API version is pinned for Testcontainers (`api.version` 1.44 for surefire, 1.40 for the
modules' failsafe runs): its client otherwise asks for 1.32, Docker 25 and later refuse anything
below 1.40, and Testcontainers reports that refusal as "no Docker" — every container test skipped on a
machine that had one.

### Per plugin

```bash
sg docker -c 'tools/worktree-build.sh -o verify -Pit -pl plugins/pravaha-plugin-kafka'
sg docker -c 'tools/worktree-build.sh -o verify -Pit -fae -pl plugins/pravaha-plugin-jdbc,plugins/pravaha-plugin-postgres-cdc,plugins/pravaha-plugin-mysql-cdc,plugins/pravaha-plugin-delta'
sg docker -c 'tools/worktree-build.sh -o verify -Pit -fae -pl plugins/pravaha-plugin-aerospike,plugins/pravaha-plugin-cassandra'
sg docker -c 'tools/worktree-build.sh -o verify -Pit -pl pravaha-it -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false'   # pravaha-it's ITs only
```

One IT class: `-Dit.test=CassandraPluginIT` (with `-Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false`
to skip the unit tests).

Measured, all green after the fixes listed under [What this pass found](#what-this-pass-found):

| Module | Container tests | Classes | Image(s) | Module time |
|---|---|---|---|---|
| kafka | 50 (of 232) | `KafkaSource*BrokerTest`, `KafkaSink*BrokerTest`, `KafkaSourceTckTest`, `Kafka*RegistrationTest`, `KafkaCompressedBrokerTest`, `KafkaExactSharingBrokerTest`, `KafkaPartitionGrowthBrokerTest` | `confluentinc/cp-kafka:7.6.0`, `confluentinc/cp-schema-registry:7.6.0` | 2 min 38 s |
| postgres-cdc | 60 (of 74) | `PostgresCdc*Test`, `WalLevelRefusalTest` | `postgres:16-alpine` | 27 s |
| jdbc | 13 ITs | `PostgresJdbcIT`, `PostgresJdbcSinkIT` | `postgres:16-alpine` | 11 s |
| mysql-cdc | 9 ITs | `MySqlCdcIT`, `MySqlCdcGtidIT`, `MySqlCdcFindingsIT` | `mysql:8.0` | 32 s |
| aerospike | 40 ITs | `AerospikePluginIT`, `AerospikeSourceTckIT`, `AerospikeDeleteDetectionIT`, `AerospikeDeleteDetectionTckIT` | `aerospike/aerospike-server:latest` (Community) | 1 min 12 s |
| cassandra | 39 ITs | `CassandraPluginIT`, `CassandraSourceTckIT`, `CassandraPushdownIT`, `CassandraDeleteDetectionIT`, `CassandraDeleteDetectionTckIT` | `cassandra:4.1` | 2 min 41 s |
| pravaha-it | 3 ITs | `AerospikeContinuousQueryIT`, `AerospikeSourceScaleIT` | `aerospike/aerospike-server:latest` | 56 s |

Delta (82), Iceberg (47), filesystem, feedfile and cluster-zookeeper have no container tests; they
ran green in the same pass.

Testcontainers also starts `testcontainers/ryuk:0.12.0`, its reaper, which removes every container
the run started when the JVM exits — including after a crash or a `kill`. Pull the images once
beforehand on a slow link; the first pull of `cassandra:4.1` alone is several hundred MB.

### Resources

Each module starts its containers per test class and stops them after it, so a single-module run
holds one store at a time. Cassandra is the heaviest (a JVM, about a minute to accept connections
per class); Kafka starts a broker per class, plus a schema registry for
`KafkaSinkRegistryBrokerTest`. Several modules in one `-pl` run sequentially. Leave 4 GiB of RAM and
a couple of cores free for a Cassandra run.

### Running the suites in Docker

`tools/docker-test.sh unit|it|sdk|console|all|mvn [--docker]` — one runner image (Maven, JDK 21,
Python 3), runs as the invoking user, caches in `~/.cache/pravaha-docker`, Testcontainers via the
host socket with `TESTCONTAINERS_HOST_OVERRIDE=localhost`; walkthrough and measured results in
[Build and test with Docker](setup/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md) step 8. (Added beside this
document; the measurements on this page were taken on the host, not through it.)

### Your own containers are safe

The tests connect only to the containers Testcontainers started for them, on ports Docker mapped
at random — never to `localhost:9092`, `5432`, `3000` or any other fixed port. The one exception
by necessity is Aerospike, which needs **host networking** (a bridged node advertises its
container-internal address and partition-routed reads hang); its test server now takes three free
host ports for its service, fabric and heartbeat and is configured to listen on them
(`AerospikeContainer`, `AerospikeTestServer`), so an Aerospike of your own on 3000–3002 is neither
in the way nor touched. Tests do not stop, reuse or reconfigure containers they did not start.

The Kafka unit tests that name `localhost:9092` hand it to a `MockProducer`; nothing connects.

### Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Every container test `Skipped`, build green | Docker not reachable by this user; or the daemon refused the API version | `docker ps` as the same user; `sg docker -c '…'`; do not remove the `api.version` pins |
| `permission denied … /var/run/docker.sock` | not in the `docker` group in this session | `sg docker -c '…'` or log in again |
| `Could not find a valid Docker environment` after a Docker upgrade | Testcontainers' cached strategy in `~/.testcontainers.properties` | check `docker.client.strategy` there (this machine: `UnixSocketClientProviderStrategy`) |
| A container left running after a killed build | ryuk normally reaps it within seconds | `docker ps -a --filter label=org.testcontainers=true` and remove only those |
| Aerospike IT: container exits at start | a port it was configured for was taken between choosing and binding (rare) | re-run; the ports are chosen fresh each time |
| A test fails only after pulling your change in another checkout, or passes when it should not | a stale Pravaha jar in the shared `~/.m2` (MAVENRACE-1) | `tools/worktree-build.sh`; install the changed modules first; `tools/verify-clean.sh` in the main checkout |
| `Address already in use` from a test | a fixed port in a test | a defect: tests take port 0 or a Testcontainers mapping; report it |

---

## Python SDK

```bash
make -C sdk/python install      # .venv with the 'dev' extras (pytest, pyarrow for Flight, ruff, mypy)
make -C sdk/python test         # 426 passed, 1 min 37 s
make -C sdk/python lint typecheck
```

The transport tests start a **real** Flight server —
`com.ash.messaging.pravaha.flight.TestFlightServerMain` from `pravaha-flight/target/test-classes`,
with `JAVA_HOME`'s `java` — on a free port, once per module; `test_authentication.py` starts it with
`--authenticated`. Build `pravaha-flight` (its test classes included) first, or those tests skip
saying *"pravaha-flight is not built"*. The rest run against in-process fakes (`engine_support.py`).

`test_the_packaged_card_is_current_with_the_guide` fails when `docs/guides/CONTINUOUS_QUERIES.md` changed
and the assistant's packaged dialect card did not: rebuild it with
`sdk/python/.venv/bin/python sdk/python/tools/build_dialect_card.py` and commit the card.

`./mvnw -Ppython verify` runs the same pytest invocations for the SDK and the console from the last
module of the reactor (`pravaha-it`), against this build's classes; it needs both venvs to exist.

**The gate runs the SDK suite.** `tools/verify-clean.sh` (full verify) adds `-Ppython
-Dpravaha.console.tests.skip=true`, so the SDK's pytest suite runs in `pravaha-it`'s verify phase
against the Flight server this build just compiled, adding about 2.5 minutes. It uses
`sdk/python/.venv`, or `PRAVAHA_SDK_PYTHON=<interpreter>`; a missing interpreter, or one without
pytest and pyarrow, fails the gate by name before anything is built. `PRAVAHA_GATE_SDK=0` leaves the
suite out and says so. The console's suite, whose browser tests need Chrome and about half an hour,
is not gated: run it with `./mvnw -Ppython verify` or from `pravaha-console/` directly.

## Console

```bash
make -C pravaha-console install         # .venv, the SDK from ../sdk/python with 'flight', the console's dev extras
make -C pravaha-console test-fast       # everything except the browser suites (PRAVAHA_BROWSER_TESTS=0)
make -C pravaha-console test            # all of it; needs Chrome or Chromium
```

| Suite | Files | What it proves |
|---|---|---|
| routes, services, help | `test_console.py`, `test_product*.py`, `test_navigation.py`, `test_identity.py`, … | every page and JSON service against a fake engine (`fake_engine.py`) |
| help accuracy | `test_help.py`, `test_help_accuracy.py` | the help topics render, their includes resolve, and what they claim about the engine matches it |
| browser journeys, states, performance | `test_browser_journeys.py`, `test_browser_states.py`, `test_browser_performance.py` | real headless Chrome over the DevTools protocol (`tests/cdp.py`), keyboard paths, the eight states, a page-weight budget |
| accessibility | `test_browser_accessibility.py` | zero axe-core 4.13 violations (vendored under `tests/vendor/axe-core`) on every page, in every theme |
| visual | `test_browser_visual.py` | screenshots against `tests/visual` baselines; `make -C pravaha-console baselines` retakes them, **only after reviewing** `tests/visual/failures/*.diff.png` |
| lint, types, contrast, file sizes, i18n | `test_lint.py`, `test_typecheck.py`, `test_contrast.py`, `test_file_sizes.py`, `i18n_scan.py` | ruff, mypy, WCAG contrast of the themes, size budgets, untranslated strings |

A browser test **skips** when no Chrome is found. CI's `suites` workflow runs the `browser`-marked
tests on their own and fails if any skipped (`pytest -m browser -rs`), for the same reason as the
container floor above.

Measured: `make -C pravaha-console test`'s pytest, browser suites included, headless Google Chrome —
**1,937 passed, 1 skipped (`promtool` not installed) in 31 min 27 s** once the query page's
baselines were retaken (the first run had 16 visual failures, below). The visual suite alone:
790 passed, 13 min 24 s. The browser suites are most of the time; `test-fast` leaves them out.

## Deck

```bash
uv venv tools/deck/.venv --python 3.12
uv pip install -p tools/deck/.venv -r tools/deck/requirements.txt
tools/deck/.venv/bin/python -m pytest -q tests/deck     # 5 passed, 1 s
```

The audit asserts the slide count, that every slide has a source, and the geometry (nothing off the
page, nothing overlapping). [`tools/deck/GUIDE.md`](../../tools/deck/GUIDE.md) has the rest.

## Performance and measurement

| Test | Where | What |
|---|---|---|
| `ProfileAGateIT`, `ProfileBGateIT` | `pravaha-it/.../qa/perf` | the P2/P3 gate profiles; up to an hour each (`@Timeout(3600)`) |
| `RestartCompileIT`, `NexmarkCoverageIT` | same | restart with generated code; Nexmark query coverage |
| `AerospikeSourceScaleIT` | same | the cost of N queries over one Aerospike set (Docker) |
| `SpillTierMeasurementIT`, `SpillBeyondRamMeasurementIT`, `OperatorMetricsOverheadIT` | `pravaha-runtime/.../exec` | ADR-044's spill tier; `tools/spill-beyond-ram.sh <dir-on-the-disk>` runs the beyond-RAM one under a `systemd-run` memory cap and skips where a scope cannot be capped |

They skip themselves when the JaCoCo agent is attached — a timing under the agent is the agent's
(PERF-1) — and the build attaches it by default, so a normal `verify` skips them. Run them with
coverage off on a quiet machine, and record the results as a gate in [`../project/gates/`](../project/gates/). Last run on
JDK 25 on 2026-10-04, with the JMH benchmarks: [`measured-2026-10-04-jdk25`](../project/gates/measured-2026-10-04-jdk25/README.md),
which also lists what was not re-measured and why (the spill tier among it).

## The findings register and the documents

`FindingsRegisterTest` holds `docs/project/qa/FINDINGS.md` to its own rules (a recognised status with
evidence on every finding, unique identifiers, header totals equal to the entries).
`DocumentationFreshnessTest` checks that every module is described, every cited ADR exists, the
README's status agrees with itself, the release notes' defect counts are the register's, every
setting an engine message names exists, every `pravaha.lane.*` key and per-query gauge is in
OPERATIONS.md, and the ADR index and HANDOVER's ADR count match the directory. `MarkdownLinksTest`
resolves every relative link and anchor in every tracked markdown file. `ErrcCrossCuttingTest`
(`pravaha-cli`) holds TROUBLESHOOTING.md's code table to the `ErrorCode` declarations in both
directions, so a new `PRV-` code without a row fails the build, as does a row for a code that does
not exist. All run in their module's normal test phase; the first two alone take 7 s.

## The adversarial suites

The adversarial QA of 2.0.0 (2026-10-01, [summary](../project/qa/SUMMARY.md)) left its
reproductions in the tree, and Waves 1 to 3 switched every one of them on with its fix. They are
**opt-in** — long, randomised or destructive — and no gate runs them:

```bash
# engine, data and security: differential windows, predicates and arithmetic against an oracle,
# aggregates, chains, durability under SIGKILL, resources, security (110 tests)
tools/worktree-build.sh -o test -pl pravaha-it -Dtest='Adv*Test' -Dpravaha.qa.adversarial=true

# surfaces against a running node (pgwire, HTTP, Flight, CLI): 21 checks
PRAVAHA_QI_HTTP=http://127.0.0.1:<http> PRAVAHA_QI_PGWIRE=127.0.0.1:<pgwire> \
PRAVAHA_QI_FLIGHT=grpc://127.0.0.1:<flight> PRAVAHA_QI_ADMIN_PASSWORD=<admin password> \
  sdk/python/.venv/bin/python -m pytest tests/qa/adv_surface -q -rs
```

The surface suite needs a node with the `users` profile and `pravaha.pgwire.enabled`; its
destructive checks (heap and connection exhaustion) run only with `PRAVAHA_QI_DESTRUCTIVE=1`, against
a scratch node. `PRAVAHA_QI_REPRODUCE=1` runs a check marked as an open defect as a strict xfail;
none is marked now. The cases and logs are under [`../project/qa/cases`](../project/qa/cases/) and
[`../project/qa/logs`](../project/qa/logs/), `ADV-ENGINE` and `ADV-SURFACE`.

---

## End to end against a running stack

### Outside Docker

The walkthrough in [Build and test without Docker](setup/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md) does
this step by step with the output it printed. In short:

1. `bin/pravaha-server --spring.profiles.active=dev,users --spring.config.additional-location=file:$PRAVAHA_HOME/conf/application.yaml`
   with a `filesystem` source that follows a CSV, a registry journal and a checkpoint directory.
2. `pravaha login --user admin --password-stdin --save`, then `pravaha register`, `pravaha query`.
3. The Python SDK with the saved token; pgwire with the token as the password.
4. `make -C pravaha-console run` (or `run_pravaha_web.py` with `PRAVAHA_ENGINE`, `PRAVAHA_ENGINE_HTTP`,
   `CONSOLE_PORT`), sign in as `admin`.
5. Stop the node, append rows, start it again: the registry journal restores the query, the
   checkpoint restores the view, and the source resumes from its offset.

`tools/qa-smoke.sh` does the node-plus-console part against a container image in one command.

### In Docker

The compose stack, its `PRAVAHA_HOME` layout and a seeded walkthrough are described in
[Running in Docker](../operations/RUNNING_IN_DOCKER.md) and [Build and test with Docker](setup/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md).
Point the CLI, the SDK and a PostgreSQL client at the ports that stack publishes, exactly as above;
nothing in the steps differs except the URLs. `deploy/docker/smoke.sh --image <tag>` is the image's
own check ([Deployment](../operations/DEPLOYMENT.md#tests)).

---

## CI: what to run when

| When | Run | Time here |
|---|---|---|
| Every commit, locally | the changed modules' tests (`tools/worktree-build.sh -o test -pl <module>`), plus the modules that depend on what changed | seconds to a few minutes |
| Every push (the `fast` workflow) | `./mvnw -B -T1C clean verify -DskipITs` — unit, property, `pravaha-it` in-process, the documentation checks | a few minutes |
| Every push touching `sdk/` or `pravaha-console/` (the `suites` workflow) | `make -C sdk/python test lint typecheck`; `make -C pravaha-console test` with a browser, and the browser suites alone with `-rs`, failing on any skip | SDK 1.5 min; console 31 min with the browser |
| Before a merge to `develop` | `tools/verify-clean.sh` in the main checkout | ~6 min (its header) |
| Nightly (the `verify` workflow) | `./mvnw -B clean verify -Pit` with Docker, then `deploy/ci/assert-suite-ran.sh failsafe 20`; also check the Kafka and Postgres-CDC classes report no skips | ~10 min for the six container plugins run one after another |
| Before a release | the nightly set, `tools/qa-smoke.sh`, `deploy/docker/smoke.sh`, `deploy/helm/test.sh` | |
| On reference hardware, by hand | the performance gates with coverage off | up to hours |

The workflows in `.github/workflows` exist and parse; [Deployment](../operations/DEPLOYMENT.md#ci) says which of
them have ever run as workflows (none had, at the time of writing).

---

## What this pass found

The first run of every container suite, the SDK suite and the console's browser suites on this
machine found five kinds of failure, none of them an environment problem:

| Where | What | Kind | Fix |
|---|---|---|---|
| `KafkaSinkRegistrationTest` (broker) | a keyed view replacing a key's row reached `kafka-sink` as a tombstone and then the value, so consumers saw the key deleted | product (SINKKEYROWS-2) | `SinkDelivery` drops the withdrawal of a key that re-enters in the same commit, for upsert sinks |
| `CassandraPluginIT` | expected a resumed token-range pass to read only the remainder; CASS-1 made it re-read the partition it stopped in | test | the IT now expects the remainder plus that one partition |
| plugin and `pravaha-it` Aerospike ITs | host networking on 3000–3002 collided with a developer's own Aerospike; `pravaha-it`'s skipped themselves | test | free ports, chosen per container |
| `sdk/python` `test_authentication.py` (3), dialect card (1) | the test Flight server's principals were in tenant `acme` while its views are the default tenant's (ADR-060); the packaged dialect card was stale | test | fixed on `develop` as SDK-AUTH and SDK-CARD, which also put the SDK suite in the gate |
| console `test_browser_visual.py` (16) | the query page gained an Owner row (view ownership) and its baselines were not retaken | test | reviewed the diffs, retook `query-*` only |

A defect noticed on the way, and fixed since as PGWIRE-TX-1: the pgwire gateway refused `BEGIN`, so a
client that opens a transaction before its first statement — psycopg by default, many ORMs — failed
with `PRV-2001 … near the keyword 'BEGIN'`. Transaction control is now accepted as a no-op with
PostgreSQL's tags and transaction status, and `autocommit=True` is not needed.
`pravaha-pgwire`'s `PsycopgClientTest` drives psycopg in its default mode; it runs when
`$PRAVAHA_PSYCOPG_PYTHON` (or `python3`) can `import psycopg` and is skipped otherwise — neither the
SDK's nor the console's virtualenv carries psycopg.
