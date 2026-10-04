# Foundations: the contract, the memory, the algebra

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Part of [the architecture](../ARCHITECTURE.md). Three modules that everything else stands on, none of
which knows what a query is.

---

## `pravaha-api`

**Purpose.** The public contract: the types a plugin, an embedder and a client compile against. It has
**no third-party dependencies at all** — the enforcer in [`pravaha-api/pom.xml`](../../../pravaha-api/pom.xml)
bans every compile, runtime and provided dependency — so a connector built against it drags nothing
into the engine, and nothing in the engine leaks into it.

| Package | Key types | What they are |
|---|---|---|
| `com.ash.messaging.pravaha.api` | `ErrorCode`, `PravahaException`, `ConfigurationException`, `EngineState`, `HelpUrls` | Every failure is a `PravahaException` carrying an `ErrorCode(number, name)`, rendered `PRV-nnnn`. The ranges (`ErrorCode.Category`: 1xxx configuration … 9xxx cluster) are listed in [`TROUBLESHOOTING.md`](../../guides/TROUBLESHOOTING.md). `HelpUrls` turns a code into a link when `pravaha.docs.base-url` is set |
| `…api.data` | `StreamSchema`, `Field`, `PravahaType`, `Types`, `RowView`, `RowWriter`, `RowKind`, `EmitMode` | The data model. A `StreamSchema` is a name, fields, an optional event-time field, out-of-orderness and allowed lateness, a primary key, and the `streamId` the registry assigns. A row is read through `RowView` and written through `RowWriter`; both carry a **weight**, an **event time** in nanoseconds and a **sequence** besides the fields |
| `…api.plugin` | `PravahaPlugin`, `StreamSourcePlugin`, `PartitionReader`, `BoundedPartitionReader`, `StreamSinkPlugin`, `LookupSourcePlugin`, `NotifierPlugin`, `SourceCapabilities`, `SinkCapabilities`, `SourceOffset`, `SourcePartition`, `ReadRequest`, `PluginContext`, `PluginManifest`, `PluginTls`, `Version` | The connector SPI. Described, with every signature, in the [connector developer guide](../../development/guides/CONNECTOR_DEVELOPMENT.md) |
| `…api.wire` | `ControlWire`, `ErrorWire` | The framing both ends of Flight speak: Pravaha's own actions (`pravaha.register`, `pravaha.list`, `pravaha.dlq.*`, `pravaha.debug.*` …) and subscription tickets as a magic number, a version and length-prefixed UTF-8 strings; and how a failure's code survives the trip (`x-pravaha-error-name`). See the [client developer guide](../../development/guides/CLIENT_DEVELOPMENT.md) |

**Talks to.** Nothing. Everything talks to it.

**Extension points.** All of `…api.plugin`. `Version.apiVersion()` is what a plugin's
`requiredApiVersion()` defaults to, and `PluginManifest.isCompatibleWith` is checked before a plugin's
classes are used.

**Invariants.** A code is never reused or renumbered (`ErrorCodeUniquenessTest` holds every
`new ErrorCode(n, …)` in the repository unique, and `ErrcCrossCuttingTest` holds the table in
`TROUBLESHOOTING.md` to the declarations in both directions). The API is compiled to Java 25 class files
from 2.0 ([ADR-061](../adr/061-jdk-25-is-the-baseline-from-2-0.md)).

**Example.** A plugin refusing a bad setting raises the API's own code, so the operator sees one
number whatever plugin they used:

```java
// pravaha-api/.../plugin/PluginContext.java
ErrorCode MISSING_SETTING = new ErrorCode(5001, "PLUGIN_MISSING_SETTING");

default String require(String key) {
    String value = config().get(key);
    if (value == null || value.isBlank()) {
        throw new ConfigurationException(
                MISSING_SETTING,
                "plugin '" + instanceName() + "' requires '" + key + "', which is not set. Available: "
                        + config().keySet());
    }
    return value;
}
```

---

## `pravaha-common`

**Purpose.** The machinery every engine module shares and none of them owns: off-heap memory, the
binary row, the rings between threads, and configuration.

| Package | Key types | Role |
|---|---|---|
| `…common.memory` | `MemoryAccess`, `MemoryRegion`, `ByteBufferMemoryAccess`, `AgronaMemoryAccess` | The **single seam** to a low-level memory API. `MemoryAccess.best()` picks the implementation; `ByteBufferMemoryAccess` (VarHandles over direct buffers) is the default because an embedded engine inherits its host's launch flags, and Agrona needs `--add-exports` |
| `…common.row` | `RowLayout`, `BinaryRowWriter`, `BinaryRowView`, `Decimals` | The row format, below. A writer builds a row in place in a region; a view is a flyweight over one |
| `…common.arena` | `RowArena`, `ArenaHandle` | Bump-pointer slabs, rewound in one assignment per batch. An `ArenaHandle` packs slab and offset into one `long` |
| `…common.queue` | `RowInbox`, `SpscRowRing`, `MpscLongRing`, `WaitStrategy` | `RowInbox` is the ingest-to-lane edge: many producers claim fixed-size off-heap cells, one lane reads them in place. `SpscRowRing` is the lane-to-lane edge |
| `…common.config` | `Configuration`, `ConfigurationBuilder`, `ConfigSource`, `ConfigResolver`, `Redaction` | Layered, typed configuration with `${key:default}` expansion and provenance; `Redaction` masks values whose keys look secret. Used by the embedded engine and the CLI; the server binds `pravaha.*` through Spring instead |
| `…common.io` | `StateOwnership`, `SensitiveFiles`, `FileDescriptors` | `StateOwnership` claims a state directory for one node id so two nodes cannot share one; `SensitiveFiles` narrows a data file to its owner |
| `…common.net`, `…common.observe` | `Endpoint`, `CertificateValidity`, `EngineSpans`, `CoverageAgent` | Host and port rendering, certificate expiry, the engine's span hook for tracing |

### The row

```
┌────────────┬──────────────┬──────────────┬────────────────────┐
│ HEADER     │ NULL BITMAP  │ FIXED REGION │ VARIABLE PAYLOAD   │
│ 32 B       │ ceil(n/8) B, │ one slot per │ appended in        │
│            │ padded to 8  │ field        │ ordinal order      │
└────────────┴──────────────┴──────────────┴────────────────────┘
 +0 weight int64   +8 eventTime int64   +16 sequence int64
 +24 schemaId int32   +28 totalLength int32
```

That diagram is `RowLayout`'s own class comment. A field read is one load at an offset computed once per
schema; a variable-width field is an 8-byte `(offset, length)` slot relative to the row, so a row can be
copied byte for byte anywhere — which is what makes a checkpoint and a lane exchange a copy rather than a
re-encode.

**Example.** The Kafka record of [trace one](../ARCHITECTURE.md#3-trace-one-a-kafka-record-becomes-a-change-a-subscriber-sees-and-a-row-a-psql-user-reads),
`(txn_id 7, user_id 'u4', amount 2500, status 'OK', ts)`, is written by the reader through a
`RowWriter` straight into an inbox cell: header (`weight = 1`, the event time, the Kafka offset as
`sequence`), a one-byte null bitmap padded to eight, five fixed slots (two of them `(offset, length)`
pointers), then `u4` and `OK` as UTF-8. Nothing is allocated on the heap.

**Threads.** None of its own. `RowInbox.claim()` is the one multi-producer operation on the data path;
everything else is single-threaded by contract.

**Invariants.** Only `…common.memory` names a low-level memory API (`ArchitectureRulesTest`,
`onlyTheMemoryPackageNamesALowLevelMemoryApi`). A row never outlives its batch unless it is copied.

**Failure codes.** `RowErrors`, `ConfigErrors` and `StateOwnershipErrors` declare the 1xxx and row-layout
codes; a row wider than its cell is refused per row, never by failing the query
([`EXECUTION_MODEL.md` §4](../EXECUTION_MODEL.md#4-the-inbox)).

---

## `pravaha-algebra`

**Purpose.** The mathematics, as executable reference code: `ZSet` (a multiset with signed weights —
the one representation for a relation and for a change), `Integrate` (I), `Differentiate` (D), `Lift`
(a batch query into its incremental form, by rule), `IncrementalJoin` (Δ(A⋈B) = ΔA⋈I(B) + I(A)⋈ΔB +
ΔA⋈ΔB), and `Frontier`.

**Talks to.** Nothing at run time. The runtime executes the same rules on binary rows; this module is
the **oracle** it is tested against.

**Invariants.** Zero weight means absent, maintained on every operation rather than checked afterwards.

**Example.** `IncrementalOracleTest` generates random streams, runs the incremental form beside the
batch form at every step, and requires them equal — the property every operator in the runtime is held
to. Why a join keeps its third term (two rows that arrive together and match each other) is in
[runtime](runtime.md#joins).

---

## What the build enforces

Not conventions — tests and enforcer rules, each because the failure it prevents is silent. The
conventions a contributor follows are in [`CONTRIBUTING.md`](../../development/guides/CONTRIBUTING.md).

| Rule | Enforced by |
|---|---|
| `pravaha-api` depends on nothing but the JDK | the enforcer in `pravaha-api/pom.xml`, and `ArchitectureRulesTest.theApiModuleDependsOnNothingButTheJdk` |
| No Spring in the engine core, the embedded engine or its bindings | `ArchitectureRulesTest.engineCoreContainsNoSpring`, `theSpringFreeRuleSeesTheEmbeddedEngineAndItsBindings`; the enforcer's `enforce-no-spring` in `pravaha-embedded` and `pravaha-bindings` |
| Calcite stays out of execution | the module graph: `pravaha-runtime`, `pravaha-state`, `pravaha-algebra` and `pravaha-codegen` do not depend on `pravaha-sql`. Calcite is declared only by `pravaha-sql`; `pravaha-serving` uses its `RelNode` to plan reads, and the Java SDK's enforcer bans it outright. No test checks imports for it |
| Only one package names a low-level memory API | `ArchitectureRulesTest.onlyTheMemoryPackageNamesALowLevelMemoryApi` |
| No `java.io.Serializable` as a transport | `ArchitectureRulesTest.nothingIsJavaSerializable` |
| The Java SDK pulls in no engine, Netty, Calcite or Spring | the enforcer in `sdk/pravaha-sdk-java/pom.xml` |
| Source files under 1,500 lines | `SourceFileSizeTest` (Java, and the Python under `sdk/python` and `console/`), `console/tests/test_file_sizes.py` |
| Every source file carries the proprietary notice | `LicenceHeaderTest`, `LicenseHeaderTest` |
| Error codes are unique, and the troubleshooting table matches them | `ErrorCodeUniquenessTest`, `ErrcCrossCuttingTest` |
| No production class reachable only from tests | `OrphanedClassTest` |
| The REST API matches its lock file | `OpenApiContractTest`, `OpenApiLockTest` against `api/openapi.lock.json` |
| Documents name only modules that exist, links and anchors resolve | `DocumentationFreshnessTest`, `MarkdownLinksTest` |
