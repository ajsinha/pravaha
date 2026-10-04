# Hosts: the server, the embedded engine, the starter, the CLI, and the cluster

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Part of [the architecture](../ARCHITECTURE.md). A *host* assembles the engine — a registry, views,
bindings, a policy — and gives it a lifecycle. Four ship. Using each is the
[user guide](../../guides/USER_GUIDE.md) (§9 embedded, §10 Spring Boot) and the [CLI guide](../../guides/CLI.md);
running a node is [`OPERATIONS.md`](../../operations/OPERATIONS.md) and [`DEPLOYMENT.md`](../../operations/DEPLOYMENT.md).

| | `pravaha-server` | `pravaha-embedded` | `pravaha-spring-boot-starter` | `pravaha-cli` |
|---|---|---|---|---|
| Spring | Spring Boot | none (enforced) | the host application's | none |
| Network | HTTP, Flight SQL, PostgreSQL | none | none | none |
| Authorization | policy, catalogue, identity, audit | permissive, anonymous | permissive, anonymous | none |
| Generated code | yes (`pravaha.codegen.enabled`) | no — interpreted only | no | yes |
| Persistence | journal, checkpoints, catalogue, identity, alerts | journal and checkpoints, opt-in | as embedded | none |
| Sources and sinks | every shipped plugin, plus `plugins/` | what is on the classpath (`filesystem` always) | as embedded | `filesystem` |

---

## `pravaha-server`

**Purpose.** The engine node: a Spring Boot application whose `PravahaNode` assembles the engine and
whose controllers publish the REST API.

| Package | Key types | Role |
|---|---|---|
| `…server` | `PravahaServerApplication`, `PravahaNode`, `PravahaMetrics`, `EngineHealthIndicator`, `NodeCatalog`, `NodeAlerts`, `CodegenSwitch`, `NativeCodecs`, `RecoveryOwners`, `PolicyCheck` | `PravahaNode` is a `SmartLifecycle`: coordinator first, then the registry built, sized and **recovered** before anything can reach it, then alerts, then Flight and the PostgreSQL gateway last; shutdown reverses it. It also owns the one `SecurityPolicy` and the one `AuditSink` every transport uses |
| `…server.api` | `QueryController`, `ViewController`, `StreamController`, `SinkController`, `PluginController`, `ReplacementController`, `DeadLetterController`, `DebugController`, `LaneController`, `AlertController`, `CatalogController`, `PolicyController`, `IdentityController`, `AuditController`, `PermissionsController`, `TenancyController`, `StatusController`, `ApiExceptionHandler` | `/api/v1/*` and the plain-HTML `/status` page that works when the console is down. The API is locked by `api/openapi.lock.json`; the live document is `/api/v1/openapi.json` |
| `…server.security` | `BearerTokenFilter`, `HttpAuthorizer`, `RequestLimitFilter`, `SecurityProperties`, `AuthenticatedOnlyPolicy` | The same credentials and the same policy as Flight; bounded request bodies (`pravaha.http.*`) |
| `…server.ingest`, `…server.egress`, `…server.catalog` | `SourceBindingProperties`, `SinkBindingProperties`, `StreamDeclarationProperties`, `LaneProperties`, `StreamCatalog` | `pravaha.sources.*`, `pravaha.sinks.*`, `pravaha.streams.*`, `pravaha.lane.*` bound and validated |
| `…server.identity`, `…server.governance`, `…server.tenancy`, `…server.alerts`, `…server.state` | `IdentityProperties`, `NodeCredentials`, `CatalogProperties`, `TenancyProperties`, `AlertProperties`, `PersistenceProperties`, `StateSpillProperties`, `StandbyWatch` | The settings of each subsystem; `StandbyWatch` is a process that holds no lanes until the primary's claim on the state directory lapses (`pravaha.standby.enabled`) |
| `…server.observe` | `CorrelationFilter`, `EngineTracing`, `NodeFlightObservation`, `FeatureMeters`, `ObservabilityEnvironment` | Correlation ids, spans, Flight call timers, meters ([observability](observability-and-packaging.md)) |

**Threads.** Spring MVC on virtual threads; the engine's own threads as in the
[overview](../ARCHITECTURE.md#5-threads-at-a-glance).

**Invariants.** No Spring type reaches the engine: the server *configures* plain-Java objects. Every
`pravaha.lane.*` key in `application.yaml` and every per-query gauge must be documented in
`OPERATIONS.md` (`DocumentationFreshnessTest`); an error message may only name a setting that exists.

**Failure behaviour.** A node that cannot do its job reports `DEGRADED` on `/actuator/health`
(`EngineHealthIndicator`): a source feed has stopped (FEED-1), a registration was refused at recovery, or
a query's dead-letter queue is degraded. A node configured in a way it cannot honour refuses to start,
naming the setting.

**Extension points.** None by Spring bean: the policy is `permissive`, `authenticated` or the catalogue,
and the verifier is the static table or the identity store. A custom `SecurityPolicy` or `TokenVerifier`
needs a host of your own (see the [security extension guide](../../development/guides/SECURITY_EXTENSIONS.md)).
Plugins are added through `plugins/` ([ingest and egress](ingest-and-egress.md#pravaha-connect)).

---

## `pravaha-embedded`

**Purpose.** The engine in your process (mode A): `PravahaEngine` — declare streams and bindings,
register, push rows, read views with SQL, subscribe, persist — with no Spring and no network.

| Key type | Role |
|---|---|
| `PravahaEngine`, `DefaultPravahaEngine` | `create(Configuration)`, `createDefault()`; `declareStream`, `bindSource`, `bindLookup`, `bindSink`, `declareQuery`, `start`; `register`, `query`, `subscribe`, `subscribeFromSnapshot`, `push`, `retract`, `advanceEventTime`, `pause`, `resume`, `drop`, `close` |
| `Declarations` | Reads `pravaha.streams`, `pravaha.sources`, `pravaha.lookups`, `pravaha.sinks` and `pravaha.queries` from a `Configuration`, exactly as the server does |
| `ContinuousQuery`, `RowChange`, `RowChangeListener`, `RowMapping`, `RowEncoder`, `PushOutcome` | A declared query; one committed change readable by column name; rows to records; Java values to binary rows |
| `EmbeddedErrors` | `PRV-8101`–`PRV-8105`: an unknown stream, a row rejected, backpressure past `pravaha.embedded.push-timeout`, misconfiguration, a push partly applied |

**Invariants.** Not a singleton — several engines run in one JVM, and `@PravahaTest` gives each test its
own. Declarations are fixed at `start()`. Every call runs as the anonymous principal under
`SecurityPolicy.PERMISSIVE`. It does not depend on `pravaha-codegen`, so every stage runs interpreted.
Persistence uses the server's keys: `pravaha.registry.journal`, `pravaha.checkpoint.directory`.

**Example — executed.** Compiled and run against this branch's build (`pravaha-embedded` and its runtime
classpath):

```java
try (PravahaEngine engine = PravahaEngine.createDefault()) {
    engine.declareStream("txn", "txn_id:INT64,user_id:STRING,amount:INT64");
    engine.start();
    engine.register("big_txn", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000", "txn_id");
    engine.register("big_txn_again", "SELECT  txn_id , user_id, amount FROM txn  WHERE 1000 < amount", "txn_id");
    System.out.println("fingerprints: "
            + engine.find("big_txn").orElseThrow().fingerprint().shortForm() + " "
            + engine.find("big_txn_again").orElseThrow().fingerprint().shortForm()
            + " same computation: "
            + (engine.find("big_txn").orElseThrow() == engine.find("big_txn_again").orElseThrow()));
    var subscription = engine.subscribe("big_txn", changes -> changes.forEach(c -> System.out.println("change: " + c)));
    engine.push("txn", new Object[] {7L, "u4", 2500L}, new Object[] {8L, "u5", 40L});
    engine.find("big_txn").orElseThrow().awaitApplied(java.time.Duration.ofSeconds(5));
    engine.find("big_txn").orElseThrow().commit();
    subscription.awaitQuiet(java.time.Duration.ofSeconds(5));
    System.out.println("read: " + engine.query("SELECT user_id, amount FROM big_txn WHERE txn_id = ?", 7L).rows()
            .stream().map(java.util.Arrays::toString).toList());
    engine.retract("txn", new Object[] {7L, "u4", 2500L});
    engine.find("big_txn").orElseThrow().awaitApplied(java.time.Duration.ofSeconds(5));
    engine.find("big_txn").orElseThrow().commit();
    subscription.awaitQuiet(java.time.Duration.ofSeconds(5));
    System.out.println("after retraction: " + engine.query("SELECT COUNT(*) AS n FROM big_txn").rows()
            .stream().map(java.util.Arrays::toString).toList());
}
```

```
fingerprints: ffdc4688bdf2 ffdc4688bdf2 same computation: true
change: +{txn_id=7, user_id=u4, amount=2500}
read: [[u4, 2500]]
change: -{txn_id=7, user_id=u4, amount=2500}
after retraction: [[0]]
```

The two registrations, written differently, share one computation; the row under the filter is
delivered as `+1` and its retraction as `−1`; the read and the subscription see the same commits. (The
`commit()` and `awaitQuiet` calls make the steps deterministic for printing; a running engine commits on
its own every 20 ms.)

---

## `pravaha-spring-boot-starter`

**Purpose.** Mode B: an embedded engine as a Spring bean, configured from `pravaha.*`
([ADR-020](../adr/020-spring-boot-starter.md)). Depends on `pravaha-embedded`, never the reverse.

| Key type | Role |
|---|---|
| `PravahaAutoConfiguration`, `PravahaProperties`, `PravahaEngineCustomizer` | The engine bean, its properties, and a hook to declare streams or plugins in code before it starts |
| `PravahaTemplate` | `register`, `query`, `queryForList`, `subscribe`, `push`, `pause`, `resume`, `drop` |
| `PravahaListener`, `PravahaListenerProcessor`, `ListenerContainer`, `PravahaListenerErrorHandler` | `@PravahaListener(query = "...", concurrency = 1, errorHandler = "")` methods receive a query's committed changes — the `@KafkaListener` idiom pointed at a view |
| `spring.test.PravahaTest`, `PravahaTester` | A test slice with its own engine |
| `spring.actuate.PravahaHealthIndicator`, `PravahaEndpoint` | A `pravaha` health indicator and read-only endpoint when Actuator is present |

---

## `pravaha-cli`

**Purpose.** The `pravaha-engine` command — `validate`, `explain`, `run` (and `version`) with the engine
in-process and no server. The `pravaha` command that talks to a node is the Python CLI in `sdk/python`
([clients](clients-and-console.md#the-python-sdk-and-the-pravaha-cli)).

| Key type | Role |
|---|---|
| `PravahaCli` | Dispatch |
| `ValidateCommand`, `ExplainCommand`, `RunCommand`, `QueryRunner` | Plan without running; print the logical, physical and generated forms; run a query over a delimited file into another |
| `SchemaOption`, `Args`, `Ansi` | `--schema` and `--event-time` into a stream; options; colour only for a terminal |

Its output is shown, executed, on the [planning](planning.md) page.

---

## `pravaha-cluster` — and why multi-node is on hold

**Purpose.** Membership, leadership and partition assignment behind an SPI, so a deployment uses the
coordinator it already runs; each implementation **declares what it guarantees** and the engine refuses
what a coordinator cannot safely do (design §21.2).

| Key type | Role |
|---|---|
| `ClusterCoordinator`, `CoordinatorProvider`, `CoordinatorFactory`, `Guarantees`, `ClusterMode` | `pravaha.cluster.mode` (`SINGLE`, `REPLICATED`, `PARTITIONED`) is what is asked; `pravaha.cluster.mechanism` (`single`, `socket`, `zookeeper`) is how. `PARTITIONED` on a mechanism that does not exclude split-brain is refused, `PRV-9002` |
| `SingleNodeCoordinator`, `SocketCoordinator`, and `plugins/pravaha-cluster-zookeeper`'s `ZooKeeperCoordinator` | The mechanisms, found by `ServiceLoader` as `CoordinatorProvider`s. The ZooKeeper one is its own artefact and is not on the server's classpath unless added |
| `PartitionAssigner`, `PartitionAssignment`, `PartitionLease`, `PartitionLeaseCoordinator`, `PartitionHandoff`, `Rebalancer`, `PartitionSnapshot` | Membership becomes an assignment of the 1024 virtual partitions; a lease is a fenced, revocable claim; a handoff moves a partition's state |
| `ClusterErrors` | `PRV-9001`–`PRV-9007` |

**Status.** The libraries are built and tested — fenced leases against a real ZooKeeper ensemble — and
**no node consumes them**: execution is single-node, and a node refuses `PARTITIONED` (`PRV-9002`) rather
than serve every partition while claiming to own some. Wiring them is ADR-039 item 8, deferred by the
owner's decision of 2026-09-26 ([`LIMITS.md`](../../guides/LIMITS.md#deferred-by-decision),
[ADR-045](../adr/045-cluster-mode-assigns-queries-not-rows.md)). The Helm chart installs one node for the
same reason. What a single node does instead for availability is a standby (`StandbyWatch`).
