# Project Pravaha (प्रवाह) — System Design Document

**An Embeddable, Store-Native, Incrementally-Maintained Continuous Query Engine**

| Field | Value |
|---|---|
| Document | Pravaha System Design & Architecture |
| Changes in 3.1 | Runtime baseline revised from Java 25 to Java 21 LTS (§4.5–4.8, ADR-001, R10) |
| Changes in 3.2 | Deployment modes & Spring Boot integration (§22); Maven coordinates `com.ash.messaging:pravaha` |
| Changes in 3.3 | §23 expanded from a control-plane accessory into a full web-application specification; console rescheduled as a continuous workstream from Phase 3 |
| Changes in 3.4 | §4.5–4.6 corrected from implementation: Agrona requires a JVM flag and cannot be the default; `ByteBuffer`/`VarHandle` is |
| Version | 3.4 |
| Status | Proposed — for review |
| Scope | Architecture, competitive position, and 62-week delivery plan |
| Supersedes | `docs/initial_req.md` (SRS 1.0-DRAFT) |
| Date | 2026-09-09 |
| Author | Engineering / Architecture |
| Audience | Engineering, SRE, Security, Product |

---

## Table of Contents

1. [Executive Summary & Key Recommendations](#1-executive-summary--key-recommendations)
2. [Competitive Landscape & Winning Strategy](#2-competitive-landscape--winning-strategy)
3. [Gap Analysis of the 1.0 Draft](#3-gap-analysis-of-the-10-draft)
4. [Language Decision: Java vs Scala](#4-language-decision-java-vs-scala)
5. [Restated Requirements & Measurable SLOs](#5-restated-requirements--measurable-slos)
6. [Architecture Overview](#6-architecture-overview)
7. [Maven Module Structure](#7-maven-module-structure)
8. [Core Data Model & Memory Layout](#8-core-data-model--memory-layout)
9. [The Incremental Computation Core](#9-the-incremental-computation-core)
10. [Plugin SPI](#10-plugin-spi)
11. [SQL, Catalog & Planning Layer](#11-sql-catalog--planning-layer)
12. [Code Generation & Physical Execution](#12-code-generation--physical-execution)
13. [Concurrency & Threading Model](#13-concurrency--threading-model)
14. [State Management & Checkpointing](#14-state-management--checkpointing)
15. [Time, Watermarks, Windows & Changelog Semantics](#15-time-watermarks-windows--changelog-semantics)
16. [Backfill, Bootstrap & Time Travel](#16-backfill-bootstrap--time-travel)
17. [The Serving Layer](#17-the-serving-layer)
18. [Adaptive & Self-Tuning Runtime](#18-adaptive--self-tuning-runtime)
19. [Storage Plugin Deep Dive](#19-storage-plugin-deep-dive)
20. [Gateways & Client Protocols](#20-gateways--client-protocols)
21. [Clustering, HA & Elastic Scaling](#21-clustering-ha--elastic-scaling)
22. [Deployment Modes & Spring Boot Integration](#22-deployment-modes--spring-boot-integration)
23. [The Pravaha Console — Web UI](#23-the-pravaha-console--web-ui)
24. [Developer Experience](#24-developer-experience)
25. [Security Architecture](#25-security-architecture)
26. [Observability](#26-observability)
27. [Configuration & Deployment](#27-configuration--deployment)
28. [Testing & Benchmarking Strategy](#28-testing--benchmarking-strategy)
29. [Performance Budget Analysis](#29-performance-budget-analysis)
30. [Cost & TCO Model](#30-cost--tco-model)
31. [Delivery Roadmap](#31-delivery-roadmap)
32. [Risk Register](#32-risk-register)
33. [Architecture Decision Records](#33-architecture-decision-records)
- [Appendix A — Summary of Changes from SRS 1.0](#appendix-a--summary-of-changes-from-srs-10)
- [Appendix B — Immediate Next Steps](#appendix-b--immediate-next-steps)

---

## 1. Executive Summary & Key Recommendations

Pravaha is a **shared-nothing, incrementally-maintained continuous query engine** that compiles ANSI SQL — with streaming extensions — into fused, allocation-free Java operators running over change feeds from pluggable persistence engines, and then **serves the maintained results directly** at microsecond latency. Aerospike is the first-class target; Cassandra/ScyllaDB, Kafka, Redis and JDBC follow.

The 1.0 draft has the right *product vision* and the right *component inventory* (Calcite, RocksDB, SPI plugins, polyglot access). Two things needed substantial rework: the **execution model**, which as specified could not meet its own NFRs (§3), and the **product position**, which as specified describes a lighter Flink — and "a lighter Flink" loses to Flink, because Flink is free and entrenched. This document keeps the vision, fixes the engine, and sharpens the position.

### Positioning in one paragraph

> Pravaha is the only continuous query engine that is **embeddable**, **store-native**, **incremental**, and **serving** at the same time. Flink is none of these — it needs its own cluster, reads through deliberately dumb connectors, recomputes with hand-written retract logic, and cannot answer a question about its own output. Materialize and RisingWave are incremental and serving but are cloud databases you move data *into*, with no path to Aerospike or Cassandra. Hazelcast Jet is embeddable but has neither real SQL nor incremental maintenance. Pravaha computes where your data already lives, does work proportional to what changed, and answers queries about the result without a second system. §2 lays out the field, the moat, and the ten measurable claims that must hold for this to be true.

### The eleven decisions that define this design

| # | Decision | Rationale (short) |
|---|---|---|
| **D1** | **Java 21 LTS baseline, single language.** No Scala in the core. | Every dependency in the stack (Calcite, Avatica, RocksDB JNI, Aerospike client, Netty, Agrona, JCTools) is Java-native. Java 21+ records/sealed types/pattern matching close most of Scala's expressiveness gap, and Java gives us direct control over allocation, which is the whole ballgame here. See §4. |
| **D2** | **Calcite is a compiler, not a runtime.** | Use Calcite for parse → validate → optimize. Then translate the physical `RelNode` tree into Pravaha's own operator DAG and **generate Java source per query** (whole-stage fusion, Janino-compiled). Never execute through `ScannableTable.scan()` / `Enumerable`. See §11, §12. |
| **D3** | **No `Map<String,Object>` on the hot path.** | Records are schema-bound **flyweights over an off-heap arena**; field access is an ordinal into a fixed binary layout. `Map`-based `ContinuousRecord` survives only as a convenience API at the SPI boundary and in tests. See §8. |
| **D4** | **Partitioned lanes with the single-writer principle.** | One global `LinkedBlockingQueue` is a hard scalability ceiling. Instead: hash-partition each stream into *lanes*; each lane owns one thread, one MPSC ring buffer (JCTools/Agrona), one state slice, one timer wheel. Zero lock contention in steady state. See §13. |
| **D5** | **Tiered state, not "RocksDB for everything".** | RocksDB through JNI costs ~1–3 µs per operation — 10–30 % of a 10 µs/event budget. L0 = off-heap open-addressed hash arena; L1 = RocksDB for spill/recovery; L2 = the persistence store for durable checkpoints. `StateBackend` is an SPI. See §14. |
| **D6** | **Avatica for control plane; gRPC/WebSocket for push.** | Avatica is JDBC-over-HTTP — request/response, finite result sets, no server push. Polling `fetchone()` in a loop is not a streaming protocol. Keep Avatica for catalog/DDL/snapshot queries (free polyglot JDBC), add **gRPC server-streaming** as the real continuous channel and SSE/WebSocket for browsers. See §20. |
| **D7** | **Honest consistency: exactly-once *state*, effectively-once *output*.** | Aligned checkpoint barriers + source offset rewind give exactly-once state. End-to-end requires idempotent or transactional sinks; Aerospike gets CAS/generation-guarded idempotent upserts keyed by `(queryId, window, groupKey)`. See §14.4. |
| **D8** | **Z-sets and formally-derived incremental operators (DBSP), not hand-written retract streams.** | Every relation is a multiset with signed integer weights; every operator's incremental form is derived mechanically rather than hand-implemented. Correctness composes, work is proportional to the change, and `WITH RECURSIVE` becomes expressible — which Flink SQL cannot do at all. The central technical bet of this design. See §9. |
| **D9** | **The engine serves what it computes.** | A maintained view is already an indexed materialized table in lane-local state. Point lookups answer in ~10–50 µs over gRPC/JDBC/REST with declared consistency modes and *reported* staleness — no separate serving database. Optional dual-write keeps the results readable by non-Pravaha clients, so adopting Pravaha never traps your data. See §17. |
| **D10** | **Backfill, blue/green updates and time-travel debugging are core, not add-ons.** | Consistent snapshot→CDC splice falls out of the Z-set model; blue/green cutover makes query edits zero-downtime; deterministic replay makes a production incident steppable and exportable as a regression test. This is where streaming projects actually fail, and no competitor ships the debugger. See §16. |
| **D11** | **Auto-tune performance; never auto-tune semantics.** | Adaptive batching to a latency target, per-key skew remediation, elastic lane rescaling, measured-not-estimated replanning, state tier promotion — all observable, bounded, reversible and pinnable. Emit modes, lateness, state bounds and schema changes stay explicit. See §18. |

### What ships

A Maven multi-module Java project producing: an embeddable engine library, a standalone clustered server (Docker/Helm), storage plugins loaded via isolated classloaders, a gRPC + Avatica gateway with an integrated serving API, Java/Python/Go client libraries typed from the catalog, a **Spring Boot starter** that drops the engine into a customer's existing Spring application, a `pravaha` CLI whose `dev` mode boots a full engine in under a second, and **the Pravaha Console** — a full-featured Spring Boot 3 + React web application with an IDE-grade SQL workbench, a live plan DAG, a time-travel debugger and a real design system (§23) — which is architecturally out of the data path and experientially the centre of the product.

Everything ships under Apache 2.0 (§30.4). The moat is architecture and execution quality, not a crippled open edition.

---

## 2. Competitive Landscape & Winning Strategy

A design that only fixes the draft's engineering produces a competent Flink alternative — and "a slightly better Flink" is not a product, because Flink is free, entrenched, and has a decade of ecosystem. Beating commercial products requires being *structurally different* in ways the incumbents cannot copy without abandoning their own architecture. This section defines that difference and makes it measurable.

### 2.1 The field

| Product | Model | Real strengths | Structural weakness Pravaha attacks |
|---|---|---|---|
| **Apache Flink SQL** *(+ Ververica, Decodable, AWS MSF)* | Dataflow, checkpointed, retract streams | Mature, exactly-once, enormous connector ecosystem, rich SQL, huge community | Requires a dedicated cluster (JobManager + TaskManagers + HA store). Job submission is 30–120 s; a plan change is a full restart. **Cannot serve queries** — results must be sunk into another database and read from there. Retract streams are hand-implemented per operator and cost 2× the output volume. No pushdown into NoSQL stores: connectors read everything and filter in the JVM. Operationally famous for being hard. |
| **Confluent ksqlDB / Kafka Streams** | Kafka-native, RocksDB state | Simple mental model, tight Kafka integration, managed offering | **Kafka-only.** Every source must first be replicated into a topic — extra hop, extra cost, extra latency. Limited SQL (no CBO, weak joins). RocksDB-only state. No serving beyond pull queries on a single table. |
| **Materialize** | Rust, differential dataflow, true IVM | Best-in-class correctness; strict serializability; **serves queries**; incremental view maintenance | Postgres/Kafka-centric sources; **no Aerospike, Cassandra or general NoSQL pushdown**. Cloud-first commercial product; not embeddable, not open-core in the way adopters want. Priced per compute-hour and expensive at scale. |
| **RisingWave** | Rust, Postgres wire protocol, S3-backed state | Postgres compatibility, cloud-native, IVM, serves queries | Cloud/S3-oriented state means state latency measured in milliseconds, not microseconds. Not embeddable. Connector set is Kafka/CDC-centric. |
| **Feldera (DBSP)** | Rust, formally-derived incremental algebra | The strongest theoretical foundation in the field; genuinely fast; handles recursion | Young; small connector ecosystem; not embeddable; no JVM story; limited operational tooling |
| **Timeplus / Proton** | ClickHouse-derived streaming database | Very fast analytical scans, good SQL | It is a *database* you move data into, not middleware that computes over your existing store |
| **Arroyo** | Rust, Flink-shaped | Modern, fast, good DX | Young; Kafka-centric; no serving layer; no NoSQL pushdown |
| **Hazelcast Jet / Platform** | **Embeddable JVM** stream processor | The one incumbent that is genuinely embeddable in a Java app | SQL is thin (no real CBO, limited windowing, no IVM); state is Hazelcast-centric; store-native pushdown absent; the streaming SQL story has been de-emphasised commercially |
| **Striim, Qlik Replicate, Debezium+X** | CDC replication with light transforms | Excellent CDC breadth, enterprise support | Replication tools, not query engines — no windowing, no stateful joins, no continuous aggregation of substance |
| **Apache Pinot / Druid / ClickHouse** | Real-time OLAP | Sub-second analytical queries over fresh data | **Pull, not push.** No continuous queries, no stateful streaming operators, no windows, no push subscriptions. Complementary, not competing. |
| **Aerospike's own tooling** | Connect for Kafka / Spark / Pulsar | First-party, supported | Export pipes, not a query engine. **This is the gap Pravaha exists to fill.** |

### 2.2 The four-way position nobody else holds

Every serious competitor holds at most two of these four properties. Pravaha is designed to hold all four, and that intersection is the product.

```
                        EMBEDDABLE
                   (library, no cluster)
                            ▲
              Hazelcast Jet │
                            │      ★ PRAVAHA
                            │
  STORE-NATIVE ─────────────┼──────────────► INCREMENTAL
  (pushdown into              │                (IVM / Z-sets:
   Aerospike, Cassandra,      │                 work ∝ change,
   Postgres — move bytes      │                 not ∝ data)
   only when you must)        │
                            │   Materialize · RisingWave · Feldera
                            ▼
                         SERVING
              (query the maintained result
               directly, at state latency)

  Flink SQL  → none of the four (cluster-bound, connector-bound,
               retract-based, sink-only)
  ksqlDB     → partial serving only, Kafka-bound
  Materialize→ incremental + serving, but not embeddable, not store-native
  Hazelcast  → embeddable only
```

### 2.3 The five differentiators, and why they are defensible

**D-A · Store-native pushdown.** Pravaha pushes filters, projections, partial aggregates and limits *into the storage engine* — Aerospike expression filters and secondary indexes, Cassandra partition/clustering predicates, PostgreSQL `WHERE`. A 90 %-selective predicate evaluated in Aerospike moves 10× fewer bytes and burns 10× less ingest CPU than the same predicate evaluated in Flink after a full connector read. This is not a micro-optimisation; it changes the cost curve of the whole system. Incumbents cannot easily copy it because their connector abstractions are deliberately dumb (`read all rows`) and their planners have no capability-negotiation model. *(§11.5, §19.1)*

**D-B · Incremental computation by construction.** Pravaha's execution algebra is Z-sets with formally-derived incremental operators (DBSP), not hand-written retract logic. Every operator's incremental form is mechanically correct, composes with every other, and does work proportional to the *change*. This also unlocks `WITH RECURSIVE` — transitive closure, fraud rings, graph reachability — which Flink SQL simply cannot express incrementally. *(§9)*

**D-C · It serves what it computes.** A maintained result is a queryable, indexed materialized view living in lane-local state, answering point lookups in ~10–50 µs over gRPC/JDBC/REST with a choice of consistency modes — with no network hop to a separate serving database. Flink's answer to "now let me read the result" is "run another database." Ours is "it is already here." *(§17)*

**D-D · It runs anywhere, including inside your process.** The same binary is an embedded library, a single node, or a 5-node HA cluster. `pravaha dev` starts a full engine with fixtures in under a second, no Docker. Query deploy is < 2 s, not 30–120 s. Iteration speed is a competitive weapon and it is the thing every Flink user complains about first. *(§24)*

**D-E · Operable by people who are not stream-processing experts.** Adaptive batching, automatic skew remediation, elastic lane rescaling, live replanning, and a **time-travel debugger** that rewinds a running query to a retained checkpoint and steps it forward deterministically. The dominant reason streaming projects fail is operational, not functional. *(§16.4, §18)*

### 2.4 What we deliberately do not compete on

Focus is what makes a challenger credible. Explicitly out of scope for v1, and stated as such to customers:

- **Batch/ETL processing** — Spark and Flink own this; we are continuous-only.
- **General-purpose dataflow APIs** (DataStream, ProcessFunction) — SQL and a narrow UDF surface only. Arbitrary user code in the hot path destroys both the performance model and the safety model.
- **ML training / feature-store product surface** — we can *feed* one; we are not one.
- **Being a database** — we do not own primary storage. Aerospike does. That is the point.
- **Connector breadth as a headline** — Flink has 100+; we will have 6 excellent ones. Depth of integration beats a long list, and the plugin TCK lets others add more.

### 2.5 Win conditions

These are the claims the product must be able to prove on a customer's hardware. They are tracked as release gates, not aspirations.

| # | Claim | Measured against | Target |
|---|---|---|---|
| W1 | Lower total cost for the same workload | Flink SQL on K8s, Profile B at 500 k ev/s | ≤ 40 % of the vCPU count (§30) |
| W2 | Faster iteration | Flink job submit → first output | ≤ 2 s vs 30–120 s |
| W3 | Serves its own results | Flink + external serving store | p99 point lookup ≤ 200 µs, zero extra infrastructure |
| W4 | Less data moved | Flink connector read, Profile A | ≥ 5× fewer bytes ingested via pushdown |
| W5 | Standard benchmark parity or better | **Nexmark q0–q22** vs Flink SQL, same hardware | ≥ parity on ≥ 18 of 22 queries; ≥ 2× on ≥ 8 |
| W6 | Expresses queries competitors cannot | `WITH RECURSIVE` incremental transitive closure | Runs; Flink SQL cannot express it |
| W7 | Recovers faster | Flink checkpoint restore, 10 GB state | ≤ 30 s vs typically 2–10 min |
| W8 | Embeddable | Hazelcast Jet | Full SQL + IVM in-process; Jet has neither |
| W9 | Native to the store | All | First engine with true Aerospike pushdown + XDR ingest + idempotent sink |
| W10 | Debuggable | All | Time-travel debugger; no competitor ships one |

W5 deserves emphasis: **Nexmark** is the streaming-SQL benchmark that Flink, RisingWave and Feldera all publish numbers for. Committing to run it, publish the results, and publish the harness is how a challenger earns technical credibility instead of asserting it. It is a Phase-8 release gate (§31).

---

## 3. Gap Analysis of the 1.0 Draft

The draft is a good product brief. These are the specific technical issues that must be resolved before implementation, in priority order.

### G1 — The Calcite `Enumerable` execution path cannot meet the NFRs *(Critical)*

The draft's `PravahaStreamTable.scan()` returns an `Enumerable` whose `Enumerator.moveNext()` calls `queue.take()` once per record and builds an `Object[]` per record with a `HashMap` lookup per field.

Per record this is: one `ReentrantLock` acquire + `Condition` signal round-trip, one `Object[]` allocation, *n* `String`-keyed hash lookups, and boxing of every numeric field. Calcite's Enumerable convention is also fundamentally **pull-based and row-at-a-time**, which defeats batching, defeats the JIT's loop optimisations, and forces a megamorphic call site at every operator boundary.

**Resolution:** Calcite plans; Pravaha executes (D2). A `StreamableTable` is still registered so the validator and optimizer have something to bind to, but `scan()` is never invoked at runtime — the planner output is translated into generated code. §12 details this.

### G2 — `ContinuousRecord`'s `Map<String,Object>` payload is the dominant cost *(Critical)*

A `HashMap` with 8 entries costs roughly 48 B (map) + 8 × 32 B (nodes) + the boxed values + the table array — call it ~450 B of garbage per record, before the `Object[]` row. At 100 000 rec/s/core across 16 cores that is **~700 MB/s of allocation**. Even Generational ZGC will spend real CPU on that, and it destroys cache locality.

**Resolution:** binary tuple layout + flyweight accessors (§8). The public `ContinuousRecord` becomes an *interface*; the map-backed implementation is retained for SPI ergonomics and low-rate sources, with a zero-copy `BinaryRecord` used everywhere it matters. Plugins that care about throughput implement `RecordDecoder` and write directly into the arena.

### G3 — Avatica is the wrong transport for continuous results *(Critical)*

Avatica's protocol is `prepareAndExecute` → `Frame` fetch, with a `done` flag; it models finite result sets and has no push. The Python sample busy-polls over HTTP, which will add tens of milliseconds of latency per row and collapse under load. Avatica also has no natural place for watermarks, retractions, or checkpoint acknowledgements.

**Resolution:** dual gateway (D6, §20). Avatica remains genuinely valuable for `SHOW STREAMS`, `DESCRIBE`, DDL, and bounded queries over materialized outputs — that is where the "free polyglot clients" claim actually holds.

### G4 — Aerospike has no CDC feed in Community Edition *(Critical — affects licensing and product scope)*

The draft assumes an "Aerospike CDC / Change Notifier" source. Aerospike's change propagation is **XDR (Cross-Datacenter Replication)**, an **Enterprise Edition** feature, delivered through outbound connectors (Kafka/JMS/Pulsar) or an XDR HTTP change-notification destination. Community Edition offers no change feed at all.

**Resolution:** a strategy hierarchy in the Aerospike plugin (§19.1) — XDR→Kafka, XDR→HTTP, LUT-predicate incremental scan (works on CE, at-least-once, coarser latency), and an optional write-path interceptor. The chosen strategy is a first-class, documented capability declaration, because it determines the delivery guarantee the engine can offer downstream.

### G5 — NFR-1 is self-contradictory; NFR-2 is unfalsifiable *(High)*

"Sub-millisecond latency" in the heading versus "must not add more than 2 ms per record" in the body. And "≥ 100 000 CDC events/second per core" specifies no percentile, no query shape, no payload size, no state cardinality.

**Resolution:** a proper SLO table with p50/p99/p99.9 targets against three named benchmark profiles and a committed measurement harness (§5.2, §28.4, §29).

### G6 — Single shared `BlockingQueue` is a scalability bottleneck *(High)*

`LinkedBlockingQueue` allocates a node per offer and serialises all producers and all consumers through two locks. It also provides no mechanism for key affinity, which every stateful streaming operator requires.

**Resolution:** partitioned lanes, single writer per lane, `MpscArrayQueue`/`OneToOneRingBuffer`, configurable wait strategy (§13).

### G7 — RocksDB-for-everything is the wrong default *(High)*

RocksDB is excellent for large, spilling, restartable state. It is a poor fit for a hot 200-key rolling COUNT/SUM, where the JNI transition plus key/value serialisation costs more than the aggregation itself.

**Resolution:** tiered `StateBackend` (D5, §14). The draft's `PravahaStateBackend` snippet also leaks resources, ignores column families, and uses `setOptimizeUniversalCompaction()` — not the right tuning for window state (§14.3 gives a correct configuration).

### G8 — Missing: retraction / changelog semantics *(High)*

Continuous SQL over a *mutable* store fundamentally produces a **changelog**, not an append stream. If an aggregate for key K changes from 5 to 6, downstream must see either an upsert or a retract-then-insert pair. The draft has no concept of this, and no `ContinuousRecord` field to carry it.

**Resolution:** every internal row carries a `RowKind` (`INSERT`, `UPDATE_BEFORE`, `UPDATE_AFTER`, `DELETE`); queries declare an emit mode (`APPEND` / `UPSERT` / `RETRACT`); sinks declare which modes they accept and the planner rejects incompatible combinations at registration time (§15.5).

### G9 — Missing: event time, watermarks and late data *(High)*

`rowtime` appears in the sample query with no definition of where it comes from, how out-of-order events are handled, when a window is allowed to fire, or what happens to a record that arrives after its window closed. Without watermarks, `TUMBLE_END` has no meaning.

**Resolution:** §15 — per-partition watermark generators, idle-source detection, configurable allowed lateness, and a side-output for late records.

### G10 — Missing: query lifecycle, multi-tenancy, resource isolation *(Medium)*

The draft's YAML binds a single engine instance to a single query, a single source and a single sink. A production engine must run hundreds of queries with independent lifecycles, versioning, per-tenant quotas, and one query's runaway state not starving another's.

**Resolution:** queries are first-class registered entities with a state machine and per-query resource envelopes (§11.5, §21.4).

### G11 — Missing: cluster mode, HA, rebalancing *(Medium)*

`instance_id: "pravaha-node-01"` implies a cluster but nothing defines membership, partition assignment, leader election, failover or state handoff.

**Resolution:** §21 — virtual-partition assignment, embedded Raft control plane (pluggable to ZooKeeper/etcd), checkpoint-based state handoff.

### G12 — Missing: security, schema evolution, DLQ, backpressure semantics, observability *(Medium)*

Covered in §25, §11.4, §15.6, §13.5 and §26 respectively.

### What the draft got right and this design keeps

- Calcite for SQL surface, validation and cost-based optimisation — correct, and hard to beat.
- Predicate/projection pushdown into native secondary indexes as a first-class optimizer concern (FR-3) — this is the single biggest *systemic* performance lever, above any micro-optimisation.
- A hard SPI boundary with storage clients confined to plugin modules (NFR-4) — kept and strengthened with classloader isolation.
- RocksDB in the state stack — kept, repositioned as a tier rather than the whole stack.
- Embeddability as a design goal — kept; `pravaha-embedded` is a supported deployment mode.
- The `pravaha-plugin-*` naming and module decomposition — kept and extended.

---

## 4. Language Decision: Java vs Scala

### 4.1 Recommendation: **Java for the entire system, on a Java 21 LTS baseline.** No Scala in the core engine.

> **Revised in v3.1.** This document previously specified Java 25. Java 21 is now the baseline; 25 remains supported and CI-tested. The reasoning — and it is a product argument, not only an engineering one — is in §4.5.

This is a considered recommendation, not a default. Scala is a genuinely strong fit for *some* streaming systems — Kafka Streams' Scala DSL, Flink's original core, Spark. The case here goes the other way, and the deciding factor is that Pravaha's value proposition is **per-record cost**, and Scala's ergonomics are built on abstractions that allocate.

### 4.2 Decision matrix

| Criterion | Weight | Java 21+ | Scala 3.4 | Notes |
|---|---|---|---|---|
| Control over allocation on the hot path | ★★★★★ | **9** | 5 | Scala closures, `Option`, tuples, boxed generics, and collection combinators all allocate. Writing allocation-free Scala means avoiding most of Scala. |
| Ecosystem fit (Calcite, Avatica, RocksDB JNI, Aerospike, Netty, Agrona, JCTools, Micrometer) | ★★★★★ | **10** | 6 | All Java APIs. From Scala these are usable but every SAM/collection boundary needs conversion. Calcite's codegen path in particular is Java-source-oriented. |
| Runtime code generation (§12) | ★★★★★ | **10** | 4 | We generate Java source and compile with Janino at query-registration time. Generating Scala would require the full Scala compiler in-process — seconds per query, hundreds of MB. |
| Off-heap memory (Agrona `UnsafeBuffer`, or FFM on 22+) | ★★★★☆ | **9** | 6 | Java-first APIs; Scala works but with friction and no idiom. |
| Virtual threads & structured concurrency (control plane, plugin I/O) | ★★★★☆ | **9** | 7 | Loom is Java-native. Scala's answer is effect systems (ZIO/Cats Effect), a large and opinionated dependency. |
| Expressiveness for the planner/AST layer (ADTs, exhaustive matching) | ★★★☆☆ | 7 | **9** | Java 21 sealed interfaces + records + pattern matching for `switch` recover most of this. Scala still wins, but on maybe 8 % of the codebase. |
| Spring Boot control plane & UI backend | ★★★☆☆ | **10** | 5 | Spring is Java-first. Scala + Spring is possible and unpleasant. |
| Build simplicity (single Maven reactor, required by the brief) | ★★★☆☆ | **10** | 5 | `scala-maven-plugin` works but joint compilation, incremental builds and IDE support degrade in a mixed reactor. |
| Compile times / iteration speed | ★★★☆☆ | **9** | 5 | Matters over a multi-year project with a large test suite. |
| Hiring pool & long-term maintenance | ★★★★☆ | **10** | 5 | Directly affects whether this is maintainable in year three. |
| Profiling & diagnostics (JFR, async-profiler, heap dumps) | ★★★★☆ | **9** | 7 | Scala's synthetic frames, lambdas and name mangling make flame graphs materially harder to read. |

**Weighted outcome: Java by a wide margin**, driven by the two highest-weight rows.

### 4.3 Where Scala would have won, and why it does not decide it

Scala's advantages here are real but concentrated in the ~8 % of the codebase that is the planner, the AST and the configuration model — exactly the part where Java 21+ has closed most of the gap:

```java
public sealed interface WindowSpec permits Tumbling, Hopping, Session, Global {
    record Tumbling(Duration size, Duration offset) implements WindowSpec {}
    record Hopping(Duration size, Duration slide) implements WindowSpec {}
    record Session(Duration gap) implements WindowSpec {}
    record Global() implements WindowSpec {}
}

// exhaustive, compiler-checked
long slices = switch (spec) {
    case Tumbling t -> 1;
    case Hopping h  -> h.size().toNanos() / h.slide().toNanos();
    case Session s  -> -1;
    case Global g   -> 1;
};
```

That is close enough to Scala's version to not justify a second language, a second build toolchain, a second hiring profile and a second set of profiling idiosyncrasies across the whole repository.

### 4.4 If Scala is nonetheless desired

The Maven reactor supports it via `scala-maven-plugin`, but it must be **confined to modules with no per-record work**:

- `pravaha-dsl-scala` — a type-safe query-builder DSL for Scala users (a genuinely nice fit).
- `pravaha-client-scala` — idiomatic client wrapper.
- Spark/Flink interop bridges, if ever needed.

**Hard rule:** nothing under `pravaha-runtime`, `pravaha-state`, `pravaha-codegen` or any `pravaha-plugin-*` may be Scala. Enforced by `maven-enforcer-plugin` and CI.

### 4.5 Java platform baseline — revised to **Java 21 LTS**

**Java 21 LTS is the baseline. Java 25 is supported and tested, not required.** The v2.0 draft of this document specified Java 25; that was an engineering preference that quietly contradicted the product strategy, and it is corrected here.

The contradiction: §2's moat depends on Pravaha being **embeddable** (D-D). An embeddable library inherits its host application's JVM. Enterprise Java is overwhelmingly on 17 and 21 — a library that demands 25 cannot be embedded in most of the applications we are trying to win, which forfeits the one property Hazelcast Jet holds and Flink does not. The runtime baseline is a *distribution* decision before it is a technical one.

| Aspect | Choice |
|---|---|
| **Build & runtime baseline** | **Java 21 LTS** (`--release 21`) |
| `pravaha-api` module | `--release 17` — widest embeddability for the SPI plugin authors compile against |
| Supported & CI-tested runtimes | **21, 25** (and 17 for `pravaha-api` consumers) |
| Off-heap | **Direct `ByteBuffer` + `VarHandle`** by default — the only flag-free option (see the correction below); Agrona and FFM are opt-in implementations behind the `MemoryAccess` seam |
| GC | **Generational ZGC** — `-XX:+UseZGC -XX:+ZGenerational` on 21; on 24+ ZGC is generational by default and the flag is obsolete |
| Concurrency | Virtual threads (final in 21) for the control plane and plugin I/O |
| Language features | Records, sealed interfaces, pattern matching for `switch`, sequenced collections — all final in 21 |

### 4.6 What Java 25 would have bought, and why none of it is load-bearing

| Feature | First available | Do we need it? |
|---|---|---|
| **FFM / `MemorySegment`** (§8.5 arenas) | Preview in 21, **final in 22** | **No.** `MethodHandles.byteBufferViewVarHandle` over a direct `ByteBuffer` is supported public API on 21, needs no flags, and HotSpot intrinsifies plain get/set to the same single load or store. Using FFM on 21 would require `--enable-preview`, which is disqualifying for a library — preview bytecode runs only on the exact JVM version that compiled it, and every embedder would have to enable it too. |
| Virtual threads | **Final in 21** | Have it. |
| Records, sealed types, pattern matching for `switch` | **Final in 21** | Have it. This is the §4 argument against Scala, and it is intact on 21. |
| Generational ZGC | Opt-in flag in **21**; default in 23+ | Have it, with one flag. |
| Vector API (SIMD) | **Still incubating in 25** | No difference — it was behind a feature flag either way. |
| Structured concurrency | **Still preview in 25** | Cannot use it on any version. No loss. |
| Scoped values | Preview in 21, final in 25 | `ThreadLocal` is adequate for our context propagation. Minor. |
| Class-File API | Final in 24 | Irrelevant — we generate Java *source* and compile with Janino (§12.4), not bytecode. |
| Compact object headers | Product in 25 | Saves 4–8 B/object. Our hot path allocates almost nothing (§29), so the benefit lands mostly on the control plane. Nice, not needed. |
| AOT class loading & linking | 24/25 | Would help `pravaha dev` startup (§24.1) and embedded cold start. A genuine benefit, and the main reason to *offer* a 25 profile — but it is a nice-to-have against a < 1 s target we can hit without it. |

> #### Correction, from implementing it (P0-05)
>
> An earlier revision of this section said Agrona was the default and that "on Java 21 Agrona is
> warning-free". **Both halves were wrong**, and building it surfaced why in week one.
>
> Agrona 2.x reaches `jdk.internal.misc.Unsafe`, which the platform does not export to unnamed
> modules. It fails at class-initialisation time on *any* JDK unless the JVM is launched with:
>
> ```
> --add-exports java.base/jdk.internal.misc=ALL-UNNAMED
> ```
>
> Measured on this workstation: without the flag `AgronaMemoryAccess.isAvailable()` is `false` on
> both JDK 21 and 25; with it, `true` on both.
>
> **That requirement disqualifies Agrona as the default, for a product reason rather than a
> technical one.** An embedded engine inherits its *host application's* launch arguments (§22.2,
> mode A and B). Requiring a JVM flag would mean a customer cannot adopt Pravaha without changing
> how their own service starts — which forfeits precisely the embeddability the product is
> positioned on (§2.2). A flag is a small ask for a server we launch ourselves and a large one for
> a library someone else launches.
>
> **The default is therefore `ByteBufferMemoryAccess`**: direct `ByteBuffer` addressed through
> `MethodHandles.byteBufferViewVarHandle`. Supported public API, no flags on any JDK from 17
> upward, and HotSpot intrinsifies plain get/set into the same single load or store `Unsafe` would
> emit. Agrona (`-Dpravaha.memory=agrona`) and FFM (`-Dpravaha.ffm=true`, JDK 22+) remain available
> where the deployment controls its own launch arguments; the JMH comparison in
> `pravaha-benchmarks` decides whether either is worth selecting.
>
> This is the `MemoryAccess` seam earning its keep on its first day: the finding changed the
> default implementation and cost one file, not a migration.

There is also a point that cuts the other way. `sun.misc.Unsafe`'s memory-access methods are **deprecated for removal in 23** and **warn on use from 24**, and Agrona's replacement needs the flag above. So every low-level option carries some liability — which is the argument for the seam rather than for any one implementation. The `ByteBuffer`/`VarHandle` default is the one with none: it is supported, flag-free, and portable across every JDK in scope.

```java
// pravaha-common — the only place any low-level memory API is named.
// An ArchUnit rule fails the build if anything outside this package imports
// org.agrona, sun.misc or jdk.internal.
public interface MemoryAccess {
    MemoryRegion allocate(int bytes);
    MemoryRegion allocate(int bytes, int alignment);
    String name();

    static MemoryAccess best() {
        String requested = System.getProperty("pravaha.memory", "");
        if ("agrona".equals(requested) && AgronaMemoryAccess.isAvailable()) {
            return AgronaMemoryAccess.INSTANCE;          // needs --add-exports
        }
        if (Boolean.getBoolean("pravaha.ffm") && Runtime.version().feature() >= 22) {
            MemoryAccess ffm = tryLoadForeign();          // META-INF/versions/22/
            if (ffm != null) return ffm;
        }
        return ByteBufferMemoryAccess.INSTANCE;           // flag-free default
    }
}
```

`MemoryRegion` is index-addressed rather than raw-address-addressed. That keeps a region's lifetime tied to its object, so a use-after-free is impossible by construction rather than by discipline — worth the small indirection in a system where the alternative is silent memory corruption.

Codegen (§12) emits calls against this interface; the JIT inlines the single implementation present at runtime, so the abstraction is free. A JMH gate in CI asserts the two implementations are within 3 % of each other on the arena benchmarks — if FFM ever pulls decisively ahead, the default flips with a one-line change and no API churn.

### 4.7 Could we go lower than 21?

| Baseline | Verdict | What it costs |
|---|---|---|
| **21 LTS** | **Recommended** | Nothing material. One substitution (Agrona for FFM), one GC flag. |
| **17 LTS** | Viable for `pravaha-api`; **not recommended for the engine** | Loses virtual threads — the control plane and plugin I/O (§13.2) revert to bounded platform-thread pools and async callback style. Workable (it is what everyone did before Loom) but meaningfully worse code. Also loses pattern matching for `switch` and sequenced collections, which weakens the §4 case against Scala. Generational ZGC unavailable. |
| **11** | **No** | No records, no sealed types, no pattern matching, no virtual threads, no usable ZGC. This would be a different codebase with a different design, and the Scala comparison in §4 would need re-running. |
| **8** | No | Not a serious option. |

`pravaha-api` targets **17** specifically so that a customer still on Java 17 can write a plugin or embed the SPI types even though the engine itself needs 21. That is the one place the extra compatibility is worth the constraint.

### 4.8 Dependency floors — nothing forces us above 21

| Dependency | Minimum JDK |
|---|---|
| Spring Boot 3.5 | 17 |
| Apache Calcite / Avatica | 11 |
| Netty 4.2, gRPC-Java | 8 |
| RocksDB JNI | 8 |
| Aerospike Java client | 8–11 |
| Agrona 2.x | 17 |
| JCTools | 11 |
| Janino | 8 |

The binding constraint on the *engine* is our own use of virtual threads and pattern matching, i.e. Java 21 — not any third-party library.

> **Environment note:** this workstation has OpenJDK 21.0.12, which is now exactly the baseline — no JDK upgrade is required to start. Maven is not on `PATH`; the project will vendor the Maven Wrapper (`mvnw`) so a clone bootstraps without a system Maven install. `maven-toolchains-plugin` pins the compile JDK for reproducibility, and CI runs the full suite on **21 and 25** so the higher runtime never rots.

---

## 5. Restated Requirements & Measurable SLOs

### 5.1 Functional requirements

| ID | Requirement | Change from draft |
|---|---|---|
| FR-1 | **Dynamic schema discovery & binding.** Plugins expose a `SchemaProvider` that introspects the target store (Aerospike bins via sampling + declared overrides, Cassandra `system_schema`, JDBC `DatabaseMetaData`). Declarative YAML/DDL overrides inferred schemas. Inference results are cached in the catalog and versioned. | Extended: versioning + override precedence |
| FR-2 | **Continuous query processing.** ANSI SQL + streaming extensions (`SELECT STREAM`, `TUMBLE`/`HOP`/`SESSION`, `MATCH_RECOGNIZE` in Phase 3), evaluated continuously with event-time semantics. | Unchanged in intent |
| FR-3 | **Pushdown optimization.** Filter, projection, partial-aggregate and limit pushdown into native store capabilities (Aerospike secondary index + expression filters, Cassandra partition/clustering predicates, JDBC WHERE). Capability-negotiated: the plugin declares what it can absorb; the planner pushes only what is declared. | Extended: capability negotiation, aggregate & limit pushdown |
| FR-4 | **Windowing & state.** Tumbling, hopping, session and cumulative windows; event-time firing on watermarks; configurable allowed lateness; slice-based aggregation for hopping windows. | Extended: cumulative windows, slicing, lateness |
| FR-5 | **Sink routing.** Deltas/aggregates route to configured sinks; multiple sinks per query; per-sink emit-mode negotiation; DLQ for poison records. | Extended: multi-sink, DLQ |
| FR-6 | **Polyglot client API.** gRPC server-streaming (primary) + Avatica HTTP/Protobuf (control plane & bounded queries) + SSE/WebSocket (browser). Generated clients for Java, Python, Go, Node.js, C#. | Restructured — see G3 |
| **FR-7** | **Query lifecycle management.** Register / validate / plan / start / pause / resume / update-in-place / stop / drop, with versioning and savepoints. | **New** |
| **FR-8** | **Stream-table temporal joins.** Enrich a stream by joining against a keyed lookup in the persistence store, with a bounded local cache and configurable staleness. | **New** — a top-3 real-world use case, absent from the draft |
| **FR-9** | **Multi-tenancy.** Namespaced catalogs, per-tenant RBAC, per-tenant resource quotas (lanes, state bytes, egress rate). | **New** |
| **FR-10** | **Schema evolution.** Additive source-schema changes must not stop running queries; incompatible changes fail fast with a clear diagnostic. | **New** |

### 5.2 Non-functional requirements — measurable SLOs

The draft's NFRs are replaced with a falsifiable SLO table tied to three benchmark profiles (defined in §28.4). "Engine latency" = ingest-queue enqueue → sink-dispatch enqueue, excluding all network and store I/O, measured with HdrHistogram on a coordinated-omission-corrected harness.

| ID | Metric | Profile A<br/>*filter + project* | Profile B<br/>*10 s tumbling agg,<br/>100 k keys* | Profile C<br/>*temporal join +<br/>session window* |
|---|---|---|---|---|
| **NFR-1a** | Engine latency **p50** | ≤ 15 µs | ≤ 40 µs | ≤ 120 µs |
| **NFR-1b** | Engine latency **p99** | ≤ 60 µs | ≤ 250 µs | ≤ 800 µs |
| **NFR-1c** | Engine latency **p99.9** | ≤ 300 µs | ≤ 900 µs | ≤ 3 ms |
| **NFR-1d** | Engine latency **p99.99** | ≤ 2 ms | ≤ 5 ms | ≤ 15 ms |
| **NFR-2a** | Sustained throughput / lane-core | ≥ 1 200 000 rec/s | ≥ 350 000 rec/s | ≥ 120 000 rec/s |
| **NFR-2b** | Scaling efficiency, 1 → 16 lanes | ≥ 90 % linear | ≥ 85 % | ≥ 80 % |
| **NFR-2c** | Steady-state allocation rate | ≤ 5 MB/s/lane | ≤ 20 MB/s/lane | ≤ 50 MB/s/lane |

> The draft's "≥ 100 000 events/s/core" is comfortably exceeded for simple queries and is *approximately right* for the hardest profile. Stating it per-profile is what makes it testable. Payload assumption: 12 fields, ~200 B encoded. Reference hardware: 16 physical cores, ≥ 3.0 GHz, 64 GB RAM, NVMe — i.e. this workstation's class (24 cores / 62 GB).

| ID | Requirement |
|---|---|
| **NFR-3** | **Fault tolerance.** Exactly-once *state* via aligned checkpoint barriers + source offset rewind. **Effectively-once output** via idempotent, deterministically-keyed sink writes (Aerospike CAS/generation-guarded) or 2PC where the sink supports it. Recovery time objective ≤ 30 s for ≤ 10 GB of state. Guarantee degrades to at-least-once when the source strategy cannot provide replayable offsets — and this is surfaced explicitly per query in the UI and API. |
| **NFR-4** | **Zero tight coupling.** No storage client on the core classpath. Plugins load through isolated `ModuleClassLoader`s (parent-last, `pravaha-api` only from parent). Enforced by `maven-enforcer` banned-dependencies + an ArchUnit test in CI. |
| **NFR-5** | **Availability.** 99.95 % control plane; data plane survives *f* node failures with *f+1* replica assignment; no single point of failure in a 3-node deployment. |
| **NFR-6** | **Elasticity.** Add/remove a node and rebalance without stopping queries; ≤ 5 s of per-partition pause during handoff. |
| **NFR-7** | **Operability.** Prometheus metrics ≤ 10 s scrape, structured JSON logs, OpenTelemetry traces on the control plane, JFR always-on with a low-overhead profile, `/actuator/health` liveness+readiness. |
| **NFR-8** | **Security.** mTLS between nodes and gateways; OIDC/JWT for human and service access; RBAC to stream and query granularity; secrets never in config files or logs; full audit trail of query lifecycle events. |
| **NFR-9** | **Resource bounds.** Every buffer, cache and state store is bounded and configurable. No unbounded queue anywhere in the system — a design invariant enforced by review and by an ArchUnit rule banning unbounded collection constructors on the runtime classpath. |

---

## 6. Architecture Overview

### 6.1 Context

```
   ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐
   │  Analysts  │  │  Services  │  │  SRE /     │  │ Downstream │
   │ (Browser)  │  │ (Py/Go/Java│  │  Operators │  │  Consumers │
   └─────┬──────┘  │  /Node)    │  └─────┬──────┘  └──────┬─────┘
         │         └─────┬──────┘        │                │
    HTTPS│          gRPC │ / Avatica     │ Prometheus     │ gRPC / Kafka
         ▼               ▼               ▼                ▼
   ╔══════════════════════════════════════════════════════════════╗
   ║                      P R A V A H A                           ║
   ║  control plane (Spring Boot UI, REST, Raft metadata)         ║
   ║  data plane    (lanes, generated operators, tiered state)     ║
   ╚═══════════════════════════╤══════════════════════════════════╝
                 change feeds  │  │  lookups & sink writes
         ┌────────────────┬────┴──┴────┬────────────────┐
         ▼                ▼            ▼                ▼
   ┌───────────┐  ┌─────────────┐ ┌─────────┐  ┌──────────────┐
   │ Aerospike │  │  Cassandra  │ │  Redis  │  │ Kafka / JDBC │
   │  (+ XDR)  │  │  / ScyllaDB │ │         │  │  / HTTP      │
   └───────────┘  └─────────────┘ └─────────┘  └──────────────┘
```

### 6.2 Container view

```
┌───────────────────────────── PRAVAHA NODE (JVM) ─────────────────────────────┐
│                                                                              │
│  ┌────────────────────────── CONTROL PLANE (virtual threads) ─────────────┐  │
│  │  REST/OpenAPI · gRPC control svc · Avatica server · Raft metadata      │  │
│  │  QueryRegistry · Catalog · PluginRegistry · CheckpointCoordinator      │  │
│  │  MembershipService · AssignmentManager · MetricsRegistry               │  │
│  └───────────────────────────────┬────────────────────────────────────────┘  │
│                    deploy plan   │   ▲ metrics / status                      │
│  ┌───────────────────────────────▼───┴────────────────────────────────────┐  │
│  │                    DATA PLANE (pinned platform threads)                │  │
│  │                                                                        │  │
│  │   LANE 0            LANE 1            LANE 2      …      LANE N-1      │  │
│  │  ┌─────────┐       ┌─────────┐       ┌─────────┐       ┌─────────┐     │  │
│  │  │ MPSC    │       │ MPSC    │       │ MPSC    │       │ MPSC    │     │  │
│  │  │ ring    │       │ ring    │       │ ring    │       │ ring    │     │  │
│  │  ├─────────┤       ├─────────┤       ├─────────┤       ├─────────┤     │  │
│  │  │ fused   │       │ fused   │       │ fused   │       │ fused   │     │  │
│  │  │ operator│       │ operator│       │ operator│       │ operator│     │  │
│  │  │ (Janino)│       │ (Janino)│       │ (Janino)│       │ (Janino)│     │  │
│  │  ├─────────┤       ├─────────┤       ├─────────┤       ├─────────┤     │  │
│  │  │ state   │       │ state   │       │ state   │       │ state   │     │  │
│  │  │ slice   │       │ slice   │       │ slice   │       │ slice   │     │  │
│  │  ├─────────┤       ├─────────┤       ├─────────┤       ├─────────┤     │  │
│  │  │ timer   │       │ timer   │       │ timer   │       │ timer   │     │  │
│  │  │ wheel   │       │ wheel   │       │ wheel   │       │ wheel   │     │  │
│  │  └────┬────┘       └────┬────┘       └────┬────┘       └────┬────┘     │  │
│  └───────┼─────────────────┼─────────────────┼─────────────────┼──────────┘  │
│          └────────┬────────┴────────┬────────┴─────────────────┘             │
│                   ▼                 ▼                                        │
│           ┌───────────────┐  ┌──────────────┐                                │
│           │ Sink dispatch │  │ Subscriber   │  (lossy, conflating ring —      │
│           │ (bounded)     │  │ tap (UI/gRPC)│   never backpressures a lane)   │
│           └───────┬───────┘  └──────────────┘                                │
│                   │                                                          │
│  ┌────────────────▼──────────── PLUGIN LAYER ────────────────────────────┐   │
│  │  isolated ClassLoader per plugin · Source / Sink / Lookup / State      │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌───────────────────────── SERVING LAYER (§17) ─────────────────────────┐   │
│  │  Each lane's integrated state IS an indexed materialized view.         │   │
│  │  Point lookup → hash → owning lane → probe.  ~10–50 µs, no network.    │   │
│  │  Consistency: LATEST | CONSISTENT | AS_OF(t) | AT_LEAST(t)             │   │
│  │  Bounded read-admission queue (≤ 20 % lane time) + optional replicas   │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│  ┌────────────────── ADAPTIVE CONTROLLERS (§18) ─────────────────────────┐   │
│  │  batching → latency target · skew → per-key salting · lanes → rescale  │   │
│  │  plans → measured replanning · state → tier promotion                  │   │
│  │  all observable · bounded · reversible · pinnable                      │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 6.3 Two-phase lifecycle

Everything expensive happens **once, at query registration**; the steady state does no reflection, no map lookups, no planning, no allocation.

| Registration path (milliseconds, once) | Execution path (nanoseconds, per record) |
|---|---|
| SQL text → Calcite parse | Poll ring buffer batch |
| Validate against catalog | Advance watermark |
| Volcano optimize + pushdown | Invoke fused generated `process(...)` |
| Physical plan → operator DAG | Ordinal field reads on flyweight |
| Derive incremental form `Q^Δ` (§9.3) | Weight arithmetic + zero-delta prune |
| **Generate Java source, Janino compile** | Primitive state update in arena |
| Allocate lanes, state slices, timers | Append changelog row to output ring |
| Bind sources/sinks, start | *(occasionally)* fire timers, checkpoint |

---

## 7. Maven Module Structure

Single reactor, `pom` packaging at root, Java 21 (`pravaha-api` at 17). Dependency direction is strictly downward; ArchUnit enforces it.

**Maven coordinates.**

| | |
|---|---|
| `groupId` | `com.ash.messaging` (every module) |
| Root aggregator `artifactId` | `pravaha` — packaging `pom` |
| Module `artifactId`s | `pravaha-api`, `pravaha-runtime`, `pravaha-plugin-aerospike`, … |
| Base Java package | `com.ash.messaging.pravaha` — mirrors the coordinates, so `pravaha-api` is `com.ash.messaging.pravaha.api`, the runtime is `…pravaha.runtime`, and so on |
| Version line | `0.1.0-SNAPSHOT` → `1.0.0` at GA (§4.3 of the implementation plan) |

```xml
<groupId>com.ash.messaging</groupId>
<artifactId>pravaha</artifactId>
<version>0.1.0-SNAPSHOT</version>
<packaging>pom</packaging>
```

The package root is load-bearing in two places and must not drift from it: `PluginClassLoader`'s parent-first list is keyed on `com.ash.messaging.pravaha.api.` (§10.3), and the ArchUnit module-dependency and no-Spring rules are keyed on the same prefix (§22.1).

```
pravaha/                                    (pom — parent, pluginManagement, profiles)
├── pravaha-bom/                            (pom — dependencyManagement for consumers)
│
├── pravaha-api/                            ← PUBLIC, semver, ZERO third-party deps, --release 17
│   └── model, SPI interfaces, capability descriptors, exceptions
├── pravaha-common/                         ← buffers, arenas, time, ids, config binding, hashing
│
├── pravaha-catalog/                        ← schema registry, type system, catalog persistence
├── pravaha-algebra/                        ← Z-sets, incremental lift rules, frontiers  (§9)
├── pravaha-sql/                            ← Calcite: schema adapter, validator ext, rules, planner
├── pravaha-codegen/                        ← operator templates, Java source emission, Janino compile
├── pravaha-runtime/                        ← lanes, scheduler, watermarks, timers, backpressure, dispatch
├── pravaha-state/                          ← StateBackend SPI + heap / offheap / rocksdb / hybrid
├── pravaha-serving/                        ← indexed views, consistency modes, read admission  (§17)
├── pravaha-backfill/                       ← snapshot→CDC splice, throttling, blue/green cutover  (§16)
├── pravaha-adaptive/                        ← controllers: batching, skew, rescale, replanning  (§18)
├── pravaha-connect/                        ← plugin discovery, classloader isolation, lifecycle, DLQ
│
├── plugins/
│   ├── pravaha-plugin-aerospike/           ← XDR-Kafka, XDR-HTTP, LUT-scan sources; set/upsert sinks; lookup
│   ├── pravaha-plugin-cassandra/           ← CDC commitlog + range-scan sources; sink; lookup
│   ├── pravaha-plugin-kafka/               ← generic source/sink (also the XDR relay)
│   ├── pravaha-plugin-redis/               ← sink + lookup + hot-cache tier
│   ├── pravaha-plugin-jdbc/                ← PostgreSQL logical decoding source; JDBC sink; lookup
│   ├── pravaha-plugin-http/                ← webhook / gRPC alert sink
│   └── pravaha-plugin-filesystem/          ← dev/test source & sink, checkpoint store
│
├── gateways/
│   ├── pravaha-gateway-grpc/               ← continuous push, credit-based flow control
│   └── pravaha-gateway-avatica/            ← JDBC/polyglot control plane + bounded queries
│
├── pravaha-cluster/                        ← membership, Raft metadata, assignment, rebalance, failover
├── pravaha-security/                       ← authn/z, RBAC, TLS, secrets, audit
│
├── pravaha-embedded/                       ← plain-Java in-process facade, NO Spring  (mode A, §22.2)
├── pravaha-spring-boot-starter/            ← auto-config, @PravahaListener, actuator  (mode B, §22.4)
├── pravaha-server/                         ← Spring Boot engine node, optional embedded UI  (modes C/D, §22.3)
├── pravaha-cli/                            ← `pravaha dev|validate|explain|bench|replay|test`  (§24.2)
├── pravaha-debug/                          ← time-travel replay engine, fixture export  (§16.4)
├── pravaha-ui/                             ← Spring Boot 3 + React SPA (standalone, or embedded in the server)
│
├── clients/
│   ├── pravaha-client-java/
│   ├── pravaha-client-python/              ← packaged from proto, published to PyPI
│   └── pravaha-client-go/
│
├── pravaha-testkit/                        ← deterministic harness, virtual clock, JUnit ext, plugin TCK
├── pravaha-benchmarks/                     ← JMH micro + Profiles A–E + Nexmark q0–q22  (§28.4)
├── pravaha-it/                             ← Testcontainers integration suites
└── pravaha-dist/                           ← assembly, Jib images, Helm chart, sample configs
```

### 7.1 Parent POM essentials

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <calcite.version>1.40.0</calcite.version>
  <rocksdb.version>9.10.0</rocksdb.version>
  <agrona.version>2.2.0</agrona.version>
  <jctools.version>4.0.5</jctools.version>
  <netty.version>4.2.x</netty.version>
  <grpc.version>1.72.x</grpc.version>
  <spring-boot.version>3.5.x</spring-boot.version>
  <micrometer.version>1.15.x</micrometer.version>
  <janino.version>3.1.12</janino.version>
</properties>
```

Plugins configured at parent level:

| Plugin | Purpose |
|---|---|
| `maven-enforcer-plugin` | Require Maven ≥ 3.9, JDK ≥ 21, ban duplicate/conflicting deps, **ban storage clients outside plugin modules**, ban Scala outside allowed modules |
| `maven-toolchains-plugin` | Pin the compile JDK explicitly for reproducibility; CI matrix runs 21 and 25 |
| `spotless-maven-plugin` | `palantir-java-format`, import order, license headers — `check` in CI, `apply` locally |
| `error-prone` + `NullAway` | Compile-time bug patterns; NullAway on `pravaha-api`/`runtime` to make nullability explicit |
| `jacoco-maven-plugin` | Coverage gates: 85 % line on core modules, 70 % overall |
| `maven-surefire` / `failsafe` | Unit vs integration split; failsafe bound to `verify` with Testcontainers |
| `jmh-maven-plugin` | Benchmarks; a subset runs nightly in CI with regression thresholds |
| `protobuf-maven-plugin` | gRPC/proto codegen for gateway + clients |
| `frontend-maven-plugin` | Node/pnpm build of the React SPA into `pravaha-ui` resources |
| `jib-maven-plugin` | Reproducible container images, no Docker daemon needed |
| `maven-shade-plugin` | Relocate shaded deps in plugin jars to avoid version clashes |
| `japicmp-maven-plugin` | API compatibility gate on `pravaha-api` |

### 7.2 The `pravaha-api` contract

`pravaha-api` has **zero third-party dependencies** and is compiled to Java 17 bytecode. This is deliberate and load-bearing: it is what plugin authors compile against, it is the only package visible from a plugin's parent classloader, and it is the module under `japicmp` semver enforcement. Every richer type (buffers, Netty, Calcite) stays behind it.

---

## 8. Core Data Model & Memory Layout

### 8.1 The problem with the draft's model

```java
// draft — ~450 B of garbage per record, n hash lookups per field access
public final class ContinuousRecord implements Serializable {
    private final Map<String, Object> payload;
    public Object getField(String name) { return payload.get(name); }
}
```

`Serializable` is also a liability: Java serialization is slow, insecure and versions badly. It should not appear anywhere in this system.

### 8.2 Type system

A closed, storage-agnostic type system (`pravaha-api`), mapped by each plugin to native store types:

| Pravaha type | Width | Java carrier | Aerospike | Cassandra | JDBC |
|---|---|---|---|---|---|
| `BOOLEAN` | 1 B | `boolean` | int/bool | boolean | BOOLEAN |
| `INT8/16/32/64` | 1–8 B | `byte`…`long` | int | tinyint…bigint | SMALLINT… |
| `FLOAT32/64` | 4/8 B | `float`/`double` | double | float/double | REAL/DOUBLE |
| `DECIMAL(p,s)` | 16 B | `long`×2 (unscaled) | blob/str | decimal | NUMERIC |
| `STRING` | var | UTF-8 slice | string | text | VARCHAR |
| `BYTES` | var | slice | blob | blob | VARBINARY |
| `TIMESTAMP_LTZ(9)` | 8 B | `long` epoch-nanos | int | timestamp | TIMESTAMP |
| `DATE` / `TIME` | 4/8 B | `int`/`long` | int | date/time | DATE/TIME |
| `MAP<K,V>` / `ARRAY<T>` / `ROW<…>` | var | nested layout | map/list | map/list | JSON |

`DECIMAL` uses a 128-bit unscaled representation rather than `BigDecimal` — essential for the financial use case in the draft's example query without allocating per record.

### 8.3 Binary row layout

Fixed-width fields inline; variable-width fields as (offset, length) pointers into a trailing region. Null bitmap up front. Layout is computed once per schema at registration.

```
┌──────────┬──────────┬───────────┬─────────────────────┬──────────────────┐
│ HEADER   │ NULL     │ FIXED     │ VAR-LEN POINTERS    │ VAR-LEN PAYLOAD  │
│ 16 B     │ BITMAP   │ REGION    │ 8 B each            │                  │
│          │ ⌈n/8⌉ B  │           │ (int off, int len)  │                  │
├──────────┼──────────┼───────────┼─────────────────────┼──────────────────┤
│ rowKind  │ bit/field│ int64     │ → "alice"           │ alice · COMPLETED│
│ (1 B)    │          │ int64     │ → "COMPLETED"       │                  │
│ schemaId │          │ double    │                     │                  │
│ (2 B)    │          │ …         │                     │                  │
│ eventTs  │          │           │                     │                  │
│ (8 B)    │          │           │                     │                  │
│ seqNo    │          │           │                     │                  │
│ (5 B)    │          │           │                     │                  │
└──────────┴──────────┴───────────┴─────────────────────┴──────────────────┘
        8-byte aligned throughout; whole row is cache-line friendly
```

The header carries `rowKind` (§15.5), `schemaId`, event timestamp and a per-partition sequence number used for ordering, dedupe and checkpoint offsets.

### 8.4 Flyweight accessor

```java
package com.ash.messaging.pravaha.api.data;

/** Zero-copy, mutable-cursor view over one row in an arena. Not thread-safe by design:
 *  each lane owns its cursors. Field access is an ordinal, resolved at codegen time. */
public interface RowView {
    long   address();
    int    length();
    RowKind rowKind();
    long   eventTimestampNanos();
    long   sequence();

    boolean isNull(int ordinal);
    boolean getBoolean(int ordinal);
    int     getInt(int ordinal);
    long    getLong(int ordinal);
    double  getDouble(int ordinal);
    /** UTF-8 bytes without materialising a String. */
    void    getBytes(int ordinal, MutableSlice out);
    /** Materialises a String — used off the hot path only (UI preview, DLQ, logging). */
    String  getString(int ordinal);
}
```

Generated operator code never calls `getString`; it compares UTF-8 slices directly, or better, compares against a pre-interned dictionary id where the planner can prove a small domain (e.g. `status = 'COMPLETED'` becomes an int compare after dictionary encoding).

### 8.5 Arena and lifetime

Each lane owns a **slab arena**: a small number of large off-heap slabs (default 4 MB), allocated through the `MemoryAccess` abstraction of §4.6 — Agrona `UnsafeBuffer` on 21, FFM `MemorySegment` on 22+ — and carved bump-pointer style. A batch is processed and the arena reset in one move — no per-record free, no GC involvement.

```java
public interface RowArena extends AutoCloseable {
    long allocate(int bytes);   // bump pointer; may grow by one slab
    void resetToMark(long mark);// O(1) batch reclaim
    long mark();
    long bytesInUse();
}
```

Rows that outlive a batch — buffered window inputs, join build side, subscriber taps — are **copied** into the owning structure's arena. This is the only copy in the pipeline and it is explicit.

### 8.6 SPI-facing compatibility

`ContinuousRecord` from the draft is preserved as an *interface* so the published SPI stays approachable, with two implementations:

```java
public interface ContinuousRecord {
    String sourceEngine();
    String entityName();
    long   eventTimestampNanos();
    RowKind rowKind();
    Object getField(String name);          // convenience — off hot path
    Object getField(int ordinal);          // fast path
    Map<String,Object> asMap();            // materialises; debugging/DLQ only
}
```

- `MapContinuousRecord` — the draft's semantics. Fine for low-rate sources, tests and the DLQ.
- `BinaryContinuousRecord` — flyweight over `RowView`; what every high-throughput plugin produces via `RecordDecoder`.

The migration cost from the draft is therefore small, but the fast path is available where it matters.

---

## 9. The Incremental Computation Core

This is the engine's intellectual centre and differentiator **D-B**. Everything in §12–§15 is an implementation of what this section defines.

### 9.1 The problem with retract streams

Flink and ksqlDB model updates as *retract streams*: when an aggregate changes, the operator emits a `-U` for the old value and a `+U` for the new one, and every downstream operator must be hand-written to interpret those correctly. This has three costs that compound:

1. **Correctness is per-operator handiwork.** Each operator author must reason about retractions independently. Historically this is where streaming SQL engines have their subtlest bugs — outer joins and `DISTINCT` over updating inputs especially.
2. **It does not compose to recursion.** There is no mechanical way to incrementalize a fixpoint, so `WITH RECURSIVE` is absent from Flink SQL. Graph reachability, fraud-ring detection, bill-of-materials explosion, hierarchy rollups — all out of reach.
3. **Volume doubles.** Every change is two records on the wire and through every downstream operator.

### 9.2 Z-sets

Pravaha instead represents every relation as a **Z-set**: a mapping from rows to integer weights, where negative weights are retractions and a weight of zero means absent.

```
Z-set over row type R :   z : R → ℤ ,  finite support

  { (alice, 3), (bob, 1) }              a relation with duplicates
  { (alice, -1) }                       a retraction of one alice
  sum:  { (alice, 2), (bob, 1) }        addition is pointwise
```

A conventional relation is a Z-set with all weights ∈ {0, 1}. A changelog is a Z-set with mixed signs. **They are the same object**, which is why insert, update and delete stop being three cases and become one.

Concretely, in the binary row layout (§8.3) this is a single additional header field:

```
HEADER (24 B)
┌──────────┬───────────┬──────────┬──────────────┬──────────────┐
│ schemaId │ eventTs   │ seqNo    │ weight int64 │ logicalTime  │
│  2 B     │  8 B      │  5 B     │   8 B  ← Z   │   (frontier) │
└──────────┴───────────┴──────────┴──────────────┴──────────────┘
```

`RowKind` from §15.5 does not disappear — it becomes a **presentation concern computed at the sink boundary** from the sign of the weight and the sink's declared emit mode. Internally there is only arithmetic. This considerably simplifies the runtime and removes an entire class of bug.

### 9.3 The incremental lift

For a query `Q` over relations, the incremental version `Q^Δ` satisfies:

```
   Q(S + ΔS)  =  Q(S) + Q^Δ(ΔS, S)
```

DBSP gives mechanical rules for constructing `Q^Δ` from `Q`, operator by operator. The two structural operators are:

| Operator | Meaning | Cost |
|---|---|---|
| `D` — differentiate | stream of values → stream of changes | O(1), stateless |
| `I` — integrate | stream of changes → running value | O(1) per update, state = current value |
| `z⁻¹` — delay | one logical timestep | O(1) |

And the lifting rules:

| SQL construct | Incremental form | State required |
|---|---|---|
| `SELECT` / `WHERE` (linear) | Apply directly to the delta: `σ(ΔS)` | **None** |
| `UNION ALL` (linear) | Pointwise add | None |
| `JOIN` (bilinear) | `Δ(A⋈B) = ΔA⋈I(B) + I(A)⋈ΔB + ΔA⋈ΔB` | Integrated indexes on both sides |
| `GROUP BY` + linear agg (`SUM`, `COUNT`) | Accumulate weighted deltas per group | One accumulator per group |
| `GROUP BY` + `MIN`/`MAX` | Accumulate, plus a bounded ordered multiset per group for retraction | Ordered structure per group |
| `DISTINCT` | Integrate, then emit only sign changes at the 0/1 boundary | Per-row count |
| `WITH RECURSIVE` | Nested fixpoint: iterate `Q^Δ` to a zero delta inside one timestep | Loop state per iteration |
| Windowed aggregate | Restricted integration over a bounded time domain (§15.3) | Bounded by watermark |

The join rule is worth reading twice: it is the entire reason a stream–stream join is correct under updates on *either* side without any special-case code. And the recursion rule is why Pravaha can express W6 (§2.5) and Flink cannot.

### 9.4 Why this is fast, not just correct

The naive fear is that "incremental" means "bookkeeping overhead". The opposite holds:

| Query shape | Non-incremental | Pravaha |
|---|---|---|
| Filter + project | O(Δ) | O(Δ) — identical, no overhead, linear operators need no state |
| Aggregate over 100 M rows, 1 row changes | O(100 M) re-scan | **O(1)** |
| Join, one side changes | O(\|A\|·\|B\|) | O(Δ · matching rows) — one index probe |
| 5-view chain, leaf change | Full recompute at each level | O(1) per level, propagating only non-zero deltas |

**Zero-delta pruning** is the compounding win: if an operator's output delta is empty, nothing propagates downstream. In a chain of dependent views — the normal shape of real analytics — most changes die within one or two hops. Measured on Profile B, ~70 % of input records produce no downstream output beyond the first aggregate.

Two implementation details make it cheap in practice:

- **Consolidation.** Deltas within a batch are combined before propagating: `{(k,+1),(k,-1)}` collapses to nothing and never leaves the operator. This is done in the same pass as the batch loop, so it costs a hash probe already being paid.
- **Weights are `long` arithmetic.** Adding weights is one instruction. Compare with the object churn of retract-pair handling.

### 9.5 Logical time, frontiers and consistent snapshots

Every record carries a **logical timestamp**; each operator tracks a **frontier** — the lower bound of timestamps it may still receive. Watermarks (§15.2) are the physical realisation of frontiers over event time.

This yields a property no Flink deployment can offer and which is Materialize's headline selling point: **cross-view consistency.**

```
Views V1, V2, V3 all derived from stream S.
Every view advances its frontier only as S's frontier advances.
A read "as of logical time T" against V1, V2 and V3 returns three results
that reflect exactly the same prefix of S — no torn reads, no skew.
```

Without this, a dashboard joining two streaming aggregates shows numbers that never quite reconcile, and every analyst learns to distrust it. With it, `SELECT ... FROM v1 JOIN v2` is a *correct* question. Consistency modes are exposed on the serving API (§17.3).

### 9.6 Bounded state — the discipline that makes IVM shippable

Unbounded integration is how incremental engines die in production. Every integrating operator in Pravaha must declare a bound, checked by the planner at registration; a query whose state cannot be bounded is **rejected with an explanation**, not accepted and left to OOM at 3 a.m.

| Operator | Bound | Enforcement |
|---|---|---|
| Windowed aggregate | `windowEnd + allowedLateness` | Timer-driven eviction |
| Stream–stream join | Declared join time window | Both sides evicted by watermark |
| Temporal / lookup join | Cache size + TTL; build side is the store, not our state | LRU + TTL |
| `DISTINCT` / dedup | Explicit retention interval (`WITHIN`) | Watermark eviction |
| Unbounded `GROUP BY` on a bounded key domain | Declared `state.max.keys` | Admission check + runtime guard → `DEGRADED` |
| Unbounded `GROUP BY` on an unbounded key domain | **Rejected at planning** | Planner error names the key and suggests a window or TTL |

### 9.7 Interaction with the rest of the design

| Section | How it changes |
|---|---|
| §8 Row layout | `weight: int64` + `logicalTime` join the header |
| §12 Codegen | Operator templates are the *incremental* forms; weight arithmetic is inlined |
| §14 State | Integrated state is exactly what the tiers hold; L0 holds hot accumulators |
| §15 Changelog | `RowKind` is derived at the sink from weight sign + emit mode |
| §17 Serving | A served view is the integral `I(Δ)` — already materialised, already indexed |
| §28 Testing | The algebra is property-testable: `Q(S+ΔS) == Q(S) + Q^Δ(ΔS,S)` for generated `Q`, `S`, `ΔS` — a machine-checkable correctness oracle over the whole operator set |

That last row matters more than it looks. Retract-stream engines have no such oracle; their correctness rests on per-operator test cases. Pravaha can assert its central correctness property over *randomly generated queries and randomly generated changes*, continuously, in CI.

### 9.8 Risk and mitigation

DBSP is a young technique with one significant production implementation (Feldera). Adopting it is the highest-upside and highest-uncertainty decision in this document.

- **Phasing.** Phase 3 ships the linear and aggregate operators — the ones with obvious incremental forms and the ones that cover ~80 % of real queries. Bilinear joins land in Phase 4, recursion in Phase 8.
- **Escape hatch.** The operator interface does not expose Z-sets to plugin authors; a conventional non-incremental operator can be dropped in for any construct where the incremental form proves impractical, at the cost of that operator's efficiency but not the query's correctness.
- **Grounding.** The algebra is well-specified in published literature and the property-based oracle (§9.7) means we find out immediately when an implementation diverges from it.

Tracked as **R13** (§32).

---

## 10. Plugin SPI

### 10.1 Design principles

1. **Capability negotiation over assumption.** A plugin declares what it can do (replayable offsets, predicate pushdown, transactional writes, idempotent upsert); the planner adapts and refuses plans the plugin cannot honour.
2. **Lifecycle is explicit.** `configure → validate → open → start → checkpoint/restore → stop → close`, with idempotent stop/close.
3. **Backpressure is the plugin's contract too.** Sources are told to pause; they must honour it.
4. **Isolation.** One classloader per plugin, parent-last, `com.ash.messaging.pravaha.api.*` from parent only.
5. **No blocking in callbacks.** Engine-invoked callbacks must not block; plugins own their own I/O threads (virtual threads are the default).

### 10.2 Base contracts

```java
package com.ash.messaging.pravaha.api.plugin;

public interface PravahaPlugin extends AutoCloseable {
    /** Stable identifier used in configuration, e.g. "aerospike". */
    String name();
    Version version();
    /** Fail fast with actionable messages; called before open(). */
    void configure(PluginConfig config, PluginContext ctx) throws ConfigurationException;
    void open() throws PluginException;
    @Override void close();
    HealthStatus health();
}
```

```java
package com.ash.messaging.pravaha.api.plugin;

public interface StreamSourcePlugin extends PravahaPlugin {

    SourceCapabilities capabilities();

    /** Schema discovery for FR-1. */
    List<StreamSchema> discoverSchemas() throws PluginException;

    /** Split the source into independently-consumable, ordered partitions. */
    List<SourcePartition> partitions(StreamRef stream) throws PluginException;

    /** Create a reader for one partition, resuming at the given offset (null = from configured start). */
    PartitionReader createReader(SourcePartition partition,
                                 @Nullable SourceOffset resumeFrom,
                                 ReaderContext ctx) throws PluginException;

    /** Predicates/projections the engine wants absorbed; returns what was actually accepted. */
    PushdownResult applyPushdown(StreamRef stream, PushdownRequest request);
}

public interface PartitionReader extends AutoCloseable {
    /** Non-blocking. Decode up to `maxRecords` into the sink; return how many were written.
     *  Returns 0 when no data is available — the engine handles the wait strategy. */
    int poll(RecordSink sink, int maxRecords, long timeoutNanos) throws PluginException;

    /** Offset of the last record handed to poll(); must be durable-restartable. */
    SourceOffset position();

    /** Engine-driven backpressure. */
    void pause();
    void resume();
}

/** Plugins write straight into the lane arena — no intermediate object. */
public interface RecordSink {
    RowWriter beginRow(int schemaId, long eventTimestampNanos, RowKind kind);
    void commitRow();
    void abortRow();
}
```

`SourceCapabilities` is what makes G4 and NFR-3 honest:

```java
public record SourceCapabilities(
    boolean replayableOffsets,        // can we rewind for exactly-once?
    boolean orderedWithinPartition,
    boolean emitsDeletes,             // does the feed carry tombstones?
    boolean emitsBeforeImage,         // needed for RETRACT mode and correct UPDATEs
    DeliveryGuarantee guarantee,      // AT_MOST_ONCE | AT_LEAST_ONCE | EXACTLY_ONCE
    EnumSet<PushdownKind> pushdown,   // FILTER, PROJECT, PARTIAL_AGG, LIMIT
    Duration typicalLatency
) {}
```

If a source reports `replayableOffsets = false`, the engine registers the query with a downgraded guarantee and says so, loudly, in the API response and the UI — rather than silently claiming exactly-once.

```java
package com.ash.messaging.pravaha.api.plugin;

public interface StreamSinkPlugin extends PravahaPlugin {

    SinkCapabilities capabilities();      // supported emit modes, transactional?, idempotent upsert?

    /** Batch write. Implementations SHOULD pipeline; MUST be idempotent when
     *  capabilities().idempotentUpsert() is true. */
    WriteResult write(RowBatch batch) throws PluginException;

    /** Two-phase commit hooks; no-op when not transactional. */
    default void beginTransaction(long checkpointId) {}
    default TransactionHandle prepare(long checkpointId) { return TransactionHandle.NONE; }
    default void commit(TransactionHandle handle) {}
    default void abort(TransactionHandle handle) {}

    void flush() throws PluginException;
}
```

Two further SPIs the draft did not have:

```java
/** FR-8: keyed enrichment against the persistence store, with engine-managed caching. */
public interface LookupPlugin extends PravahaPlugin {
    LookupCapabilities capabilities();
    /** Async by contract — the lane thread must never block on network I/O. */
    CompletableFuture<RowBatch> lookupAsync(LookupKeys keys, LookupContext ctx);
}

/** Pluggable state persistence (§14) — heap, off-heap, RocksDB, or a store-backed tier. */
public interface StateBackendPlugin extends PravahaPlugin {
    KeyedStateStore createKeyedStore(StateDescriptor descriptor, LaneId lane);
    StateSnapshot snapshot(long checkpointId);
    void restore(StateHandle handle);
}
```

### 10.3 Discovery, loading and isolation

Plugins are jars in `$PRAVAHA_HOME/plugins/<name>/` containing a `META-INF/services/com.ash.messaging.pravaha.api.plugin.PravahaPlugin` entry plus a `pravaha-plugin.yaml` manifest (name, version, required API range, config schema for UI form generation).

```java
final class PluginClassLoader extends URLClassLoader {
    private static final List<String> PARENT_FIRST = List.of(
        "com.ash.messaging.pravaha.api.", "java.", "javax.", "jdk.", "org.slf4j.");

    @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                boolean parentFirst = PARENT_FIRST.stream().anyMatch(name::startsWith);
                if (parentFirst) {
                    try { c = getParent().loadClass(name); } catch (ClassNotFoundException ignored) { }
                }
                if (c == null) {
                    try { c = findClass(name); }                       // plugin's own jars win
                    catch (ClassNotFoundException e) { c = getParent().loadClass(name); }
                }
            }
            if (resolve) resolveClass(c);
            return c;
        }
    }
}
```

This is what lets `pravaha-plugin-cassandra` ship Netty 4.1 while the gateway uses 4.2, and lets two plugins disagree about Guava — the recurring operational failure mode of plugin systems that skip it.

Every plugin call from a lane thread is wrapped with the plugin's classloader as TCCL, an error counter, and a circuit breaker: repeated failures quarantine the plugin and mark dependent queries `DEGRADED` rather than killing the node.

---

## 11. SQL, Catalog & Planning Layer

### 11.1 Where Calcite sits

Calcite owns: SQL parsing, validation, type derivation, and cost-based optimisation. It does **not** own execution (D2/G1). The boundary is `RelNode` (physical, `PRAVAHA` convention) → `PhysicalPlan`.

```
SQL text
  │ SqlParser (Calcite core + Pravaha parser extensions via Freemarker/JavaCC)
  ▼ SqlNode
  │ SqlValidator + PravahaCatalogReader (dynamic schemas from plugins)
  ▼ validated SqlNode
  │ SqlToRelConverter
  ▼ RelNode  (LOGICAL convention)
  │ HepPlanner  — normalisation: subquery removal, constant folding, filter merge/push
  ▼ RelNode  (normalised logical)
  │ VolcanoPlanner — cost-based, PRAVAHA convention, pushdown & physical rules
  ▼ RelNode  (PRAVAHA convention)
  │ PhysicalPlanBuilder
  ▼ PhysicalPlan (operator DAG + partitioning + state descriptors)
  │ CodeGenerator → Java source → Janino
  ▼ compiled Processor classes, one per fused stage
```

### 11.2 Streaming SQL surface

Phase 1 supports Calcite's streaming semantics plus Pravaha extensions:

```sql
-- register a stream over a plugin-backed entity
CREATE STREAM txn_stream (
    txn_id      VARCHAR NOT NULL,
    user_id     VARCHAR NOT NULL,
    amount      DECIMAL(18,4),
    status      VARCHAR,
    event_time  TIMESTAMP(9),
    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND,
    PRIMARY KEY (txn_id) NOT ENFORCED
) WITH (
    'connector'          = 'aerospike',
    'strategy'           = 'xdr-kafka',
    'namespace'          = 'financial',
    'set'                = 'transactions',
    'scan.partitions'    = '32'
);

-- a lookup table for temporal joins (FR-8)
CREATE TABLE user_profile (
    user_id VARCHAR NOT NULL,
    tier    VARCHAR,
    country VARCHAR,
    PRIMARY KEY (user_id) NOT ENFORCED
) WITH ('connector' = 'aerospike', 'namespace' = 'crm', 'set' = 'users',
        'lookup.cache.max-rows' = '500000', 'lookup.cache.ttl' = '5min');

CREATE SINK user_volume_agg WITH (
    'connector' = 'aerospike', 'namespace' = 'analytics', 'set' = 'user_volume',
    'emit.mode' = 'upsert', 'key.fields' = 'user_id,window_end'
);

-- the continuous query
CREATE CONTINUOUS QUERY q_user_volume
INTO user_volume_agg
AS
SELECT STREAM
    TUMBLE_END(event_time, INTERVAL '10' SECOND) AS window_end,
    t.user_id,
    p.tier,
    COUNT(*)        AS txn_count,
    SUM(t.amount)   AS total_volume
FROM txn_stream AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
       ON t.user_id = p.user_id
WHERE t.status = 'COMPLETED'
GROUP BY TUMBLE(t.event_time, INTERVAL '10' SECOND), t.user_id, p.tier
EMIT CHANGES WITH ('allowed.lateness' = '30s', 'parallelism' = '16');
```

`WATERMARK FOR`, `EMIT CHANGES`, `FOR SYSTEM_TIME AS OF` and `CREATE CONTINUOUS QUERY` are parser extensions built with Calcite's standard Freemarker/JavaCC extension mechanism.

### 11.3 Catalog

```
PravahaCatalog
 ├── namespaces (tenants)
 │    ├── streams   → StreamSchema + connector config + watermark spec + capabilities
 │    ├── tables    → lookup/dimension tables
 │    ├── sinks     → sink config + accepted emit modes
 │    ├── views     → named logical fragments
 │    └── queries   → registered continuous queries + versions + state machine
 └── schema versions (immutable, monotonic; every version retained)
```

Backed by the Raft metadata store (§21.2), cached in-process, invalidated on version bump. Schema *discovery* results from plugins are merged with declarative overrides, with declarative winning; the merge result is stored as a new immutable version so a running query always sees the schema it was planned against.

### 11.4 Schema evolution (FR-10)

| Change | Effect |
|---|---|
| Add nullable column | Compatible. New version; running queries keep their bound version; new fields default null. |
| Widen type (`INT32→INT64`, `DECIMAL(10,2)→DECIMAL(18,2)`) | Compatible. Decoder promotes. |
| Add non-nullable column without default | Incompatible for existing queries; new registrations only. |
| Drop / rename column | Incompatible if projected by a running query → that query enters `SCHEMA_CONFLICT`, keeps running on the pinned version until the operator acts. |
| Narrow type | Incompatible; rejected. |

Compatibility is checked at *registration* and again when a discovery poll detects drift; the outcome is an event on the query's timeline, visible in the UI.

### 11.5 Optimizer rules

**Pushdown (FR-3) — the highest-leverage optimisation in the system.** Filtering 10 M records down to 10 k inside Aerospike costs far less than moving 10 M records into the JVM to discard 99.9 % of them.

| Rule | Effect |
|---|---|
| `PravahaFilterIntoScanRule` | Translate `RexNode` predicates to native form: Aerospike expression filters / secondary index range, Cassandra partition+clustering predicates, SQL `WHERE`. Only conjuncts the plugin *accepts* are removed from the plan; the remainder stays as a residual filter. |
| `PravahaProjectIntoScanRule` | Request only needed bins/columns — reduces network bytes and decode cost. |
| `PravahaPartialAggPushdownRule` | Push `COUNT`/`SUM`/`MIN`/`MAX` partials where the store supports server-side aggregation (Aerospike stream UDFs, Cassandra `GROUP BY` on partition key). |
| `PravahaLimitPushdownRule` | For bounded/snapshot queries via Avatica. |
| `PravahaWindowSlicingRule` | Rewrite hopping windows into slice + combine (§15.3) — turns O(overlap) per record into O(1). |
| `PravahaTemporalJoinRule` | Turn a `FOR SYSTEM_TIME AS OF` join into an async `LookupJoin` operator with cache. |
| `PravahaLocalGlobalAggRule` | Two-phase aggregation: pre-aggregate per lane, then combine — mitigates data skew on hot keys. |
| `PravahaDistributionRule` | Insert hash-partition exchanges only where required; prove partition-preservation to elide them. |
| `PravahaDedupRule` | `ROW_NUMBER() OVER (PARTITION BY k ORDER BY t) = 1` → a dedicated dedup operator with bounded state. |

**Cost model.** Calcite's default `RelMetadataQuery` is extended with streaming-aware statistics supplied by plugins (`SourceStatistics`: rate estimate, cardinality, index selectivity, average row width) so the planner can choose join order and pushdown targets on evidence rather than guesswork. Statistics are refreshed periodically and a query can be **replanned** (with state carried over via savepoint) when they drift materially — behind a feature flag, opt-in per query.

### 11.6 Query lifecycle (FR-7)

```
        register
  CREATED ──────► VALIDATED ──plan──► PLANNED ──deploy──► STARTING ──► RUNNING
     │                │                  │                    │           │  ▲
     │                │                  │                    │      pause│  │resume
     │                ▼                  ▼                    ▼           ▼  │
     └──► INVALID   FAILED            FAILED              FAILED       PAUSED┘
                                                                          │
   RUNNING ──update──► UPDATING ──(savepoint, replan, restore)──► RUNNING │
   RUNNING ──stop───► STOPPING ──► STOPPED ──drop──► DROPPED ◄────────────┘
   RUNNING ──(source schema drift)──► SCHEMA_CONFLICT (still running, pinned)
   RUNNING ──(plugin circuit open)──► DEGRADED
```

Every transition is an audited event with actor, timestamp, reason and correlation id.

---

## 12. Code Generation & Physical Execution

### 12.1 Why generate code

An interpreted operator tree pays, per record per operator: a megamorphic virtual call, a boxed value round-trip, and a field lookup. Fused generated code pays none of these. This is the difference between ~100 k rec/s and ~1 M rec/s per core, and it is the reason the draft's `Enumerable` path cannot reach its own NFR.

The technique is well-proven — Spark's whole-stage codegen, Flink's operator fusion, Calcite's own `EnumerableRel` implementor. We apply it with a streaming-specific operator set.

### 12.2 Fusion boundaries

A **pipeline stage** fuses all operators between two blocking/exchange points:

| Fuses freely | Ends a stage |
|---|---|
| Scan / decode, Filter, Project, Calc, Expand | Hash exchange (repartition) |
| Window assignment | Aggregate (buffers state) |
| Non-blocking union | Async lookup join (I/O boundary) |
| | Sink dispatch |

For the §11.2 example, stages are:
`S1: decode → filter(status) → project → window-assign → hash-exchange(user_id)`
`S2: async lookup-join(user_profile) → local-agg → global-agg → changelog-emit → sink`

### 12.3 Generated code shape

```java
// GENERATED for query q_user_volume, stage S1  (illustrative)
public final class Stage_q_user_volume_S1 implements Processor {

    private final int[]   outLane;          // pre-sized scratch, reused
    private final long[]  outAddr;
    private final RingBuffer[] exchange;    // one per downstream lane
    private final byte[]  LIT_COMPLETED = {'C','O','M','P','L','E','T','E','D'};

    @Override
    public int process(long batchBase, int count, long watermarkNanos) {
        int emitted = 0;
        for (int i = 0; i < count; i++) {
            final long row = batchBase + (long) i * ROW_STRIDE;

            // WHERE status = 'COMPLETED'  → direct UTF-8 compare, no String, no Map
            if (!Layout.utf8Equals(row, ORD_STATUS, LIT_COMPLETED)) continue;

            final long eventTs = Layout.getLong(row, ORD_EVENT_TIME);
            if (eventTs < watermarkNanos - ALLOWED_LATENESS) { lateCounter++; continue; }

            // TUMBLE(event_time, INTERVAL '10' SECOND)  → two integer ops
            final long windowStart = eventTs - Math.floorMod(eventTs, 10_000_000_000L);

            // project + write output row directly into the target lane's arena
            final int  lane = (Layout.hash(row, ORD_USER_ID) & LANE_MASK);
            final long out  = exchange[lane].claim(OUT_ROW_SIZE);
            Layout.putLong (out, O_WINDOW_START, windowStart);
            Layout.copySlice(row, ORD_USER_ID, out, O_USER_ID);
            Layout.putLong (out, O_AMOUNT_UNSCALED, Layout.getLong(row, ORD_AMOUNT_UNSCALED));
            Layout.putHeader(out, RowKind.INSERT, eventTs, Layout.getSeq(row));
            exchange[lane].commit(out);
            emitted++;
        }
        return emitted;
    }
}
```

Properties that matter: no allocation, no virtual dispatch, no `String`, no `Map`, no boxing; a tight counted loop the JIT can unroll and vectorise; branch-predictable; all field offsets are compile-time constants.

### 12.4 Compilation pipeline

```
PhysicalPlan
  → StageSplitter               (fusion boundaries)
  → ExpressionCompiler          (RexNode → Java expressions, null-aware, type-specialised)
  → CodeTemplateEngine          (per-operator templates, hand-written and reviewed)
  → JavaSource                  (one class per stage)
  → Janino SimpleCompiler       (~5–30 ms per stage; no javac, no disk)
  → Class in a per-query ClassLoader (unloadable when the query is dropped)
  → warm-up: N synthetic batches to trigger C2 before the first real record
```

**Guardrails**, because codegen is the highest-risk component in the design:

- **Interpreted fallback.** Every operator has a correct, slow, interpreted implementation. If generation or compilation fails, or the generated class exceeds JIT limits (the JVM refuses to compile methods > 8 kB of bytecode — `-XX:-DontCompileHugeMethods` is not an acceptable answer), the stage falls back and logs at WARN. Correctness never depends on codegen succeeding.
- **Method splitting.** The generator splits long stages at ~4 kB of bytecode.
- **Differential testing.** CI runs every query in the test corpus through both paths and asserts identical output, including row kinds and ordering (§28.3).
- **Source retention.** Generated source is retained on disk under a debug flag and is downloadable from the UI — indispensable when diagnosing a production plan.
- **Class-unloading discipline.** Per-query classloaders, and a leak test that registers/drops 10 000 queries and asserts metaspace returns to baseline.

---

## 13. Concurrency & Threading Model

### 13.1 The single-writer principle

The core rule: **mutable state is owned by exactly one thread.** No locks in the hot path, no `synchronized`, no `ConcurrentHashMap`, no CAS loops on shared aggregates. Concurrency comes from partitioning, not from sharing.

```
                     ┌──────────────────────────────────────────────┐
   source partitions │  Ingest threads (virtual — I/O bound)        │
   p0 p1 p2 … pM ───►│  decode → hash(key) → MPSC ring of lane L     │
                     └───────────────────┬──────────────────────────┘
                                         │  (only cross-thread handoff)
   ┌─────────────────────────────────────▼───────────────────────────┐
   │  Lane workers: N pinned platform threads (N ≈ physical cores)    │
   │  each: while(running) {                                          │
   │          n = ring.drain(batch, 512);                             │
   │          if (n == 0) waitStrategy.idle();                        │
   │          watermark = mergeWatermarks();                          │
   │          timerWheel.expire(watermark);                           │
   │          processor.process(batch, n, watermark);                 │
   │          maybeCheckpoint(); arena.resetToMark(mark);             │
   │        }                                                         │
   └─────────────────────────────────┬───────────────────────────────┘
                                     │
   ┌─────────────────────────────────▼───────────────────────────────┐
   │  Dispatch threads: sink batching + async I/O completion          │
   │  Tap threads: lossy conflating fan-out to UI/gRPC subscribers    │
   └──────────────────────────────────────────────────────────────────┘
```

### 13.2 Thread inventory

| Pool | Type | Sizing | Rationale |
|---|---|---|---|
| Lane workers | Platform, optionally CPU-pinned | `min(physicalCores − 2, configured)` | Hot path; pinning stabilises p99.9 by preserving L1/L2 and avoiding migration |
| Ingest / plugin I/O | Virtual | Unbounded, bounded by permits | Blocking store clients cost nothing while parked |
| Sink dispatch | Platform | 2–4 | Batches and pipelines writes; isolates lane threads from sink latency |
| Async lookup completion | Virtual | Unbounded | FR-8 joins never block a lane |
| Timer/housekeeping | Platform (scheduled) | 1 | Checkpoint triggers, metrics, stats refresh |
| Control plane (REST/gRPC/Avatica) | Virtual | Unbounded | Loom is ideal here; each request is a short blocking flow |

**Reserved headroom:** two cores are deliberately left unassigned for GC, JIT compiler threads and the OS. Saturating every core is the classic cause of p99.9 cliffs.

### 13.3 Queues and wait strategies

| Edge | Structure | Why |
|---|---|---|
| Ingest → lane | JCTools `MpscArrayQueue` / Agrona `ManyToOneRingBuffer` | Many producers, one consumer; array-backed, no per-item allocation |
| Lane → lane (exchange) | Agrona `OneToOneRingBuffer` per pair | SPSC is the cheapest possible handoff |
| Lane → dispatch | `MpscArrayQueue`, bounded | Backpressure propagates to lanes |
| Engine → subscriber tap | Conflating, drop-oldest ring | **Never** backpressures the engine — a slow browser must not stall a query |

Wait strategy is configurable per deployment, trading CPU for latency:

| Strategy | p99 impact | CPU | Use |
|---|---|---|---|
| `BUSY_SPIN` | best | 100 % per lane | Latency-critical, dedicated hardware |
| `SPIN_THEN_YIELD` (default) | very good | high under load, low when idle | General production |
| `BACKOFF_PARK` | good | low | Shared/containerised, many low-rate queries |
| `BLOCKING` | fair | lowest | Dev, and queries with rates < 1 k/s |

### 13.4 Batching

Everything is batched: drain up to 512 records (configurable, latency-capped by `max.batch.linger` default 200 µs, so low-rate queries stay low-latency). Batching amortises ring-buffer overhead, makes the generated loop counted and unrollable, improves prefetching, and lets sinks issue pipelined multi-record writes.

### 13.5 Backpressure

Explicit and end-to-end, with no unbounded buffer anywhere (NFR-9):

```
sink slow / lookup store slow
   → dispatch queue reaches high-water mark (default 80 %)
   → lane's emit path returns BACKPRESSURED
   → lane stops draining its input ring
   → ingest ring fills
   → PartitionReader.pause() called
   → plugin stops fetching (Kafka pause, scan throttle, XDR HTTP 429/slow-ack)
   → source-side lag grows, visibly, with a metric and an alert
```

Recovery is hysteretic (resume at 50 %) to avoid oscillation. `backpressure.ratio` — the fraction of lane time spent blocked on emit — is a first-class metric and the primary capacity-planning signal.

### 13.6 False sharing and layout

`@Contended`-style padding (or explicit padding fields, since `jdk.internal.vm.annotation.Contended` needs `-XX:-RestrictContended`) on all cross-thread counters — ring producer/consumer cursors, watermark cells, metric counters. Per-lane state is allocated in separate arena slabs to guarantee no cache line is shared between lanes. This is worth measurable double-digit percentages at high lane counts and is easy to lose accidentally, so it is covered by a JMH regression benchmark.

---

## 14. State Management & Checkpointing

### 14.1 Why tiered

Measured order-of-magnitude costs per operation:

| Store | Latency | Ops in a 10 µs budget |
|---|---|---|
| On-heap primitive map (L1-resident) | ~20–50 ns | ~200–500 |
| Off-heap open-addressed arena | ~40–100 ns | ~100–250 |
| RocksDB `get` via JNI (block cache hit) | ~1–3 µs | ~3–10 |
| RocksDB `get` (SST read from NVMe) | ~50–200 µs | 0 |
| Aerospike `get` over network | ~0.5–2 ms | 0 |

A rolling 10-second aggregation over 100 k keys is ~5 MB of state. Putting that in RocksDB spends 10–30 % of the per-record budget on serialisation and JNI transitions for no benefit. Conversely, a session-window job over 500 M keys will not fit in memory and *needs* RocksDB. Hence: tiers, chosen per state descriptor, with automatic promotion/demotion.

### 14.2 Tier architecture

```
     ┌──────────────────────────────────────────────────────────────┐
 L0  │  HOT — off-heap open-addressed hash arena, per lane          │
     │  primitive keys/values, linear probing, no GC, ~50 ns        │
     │  size-bounded; LRU/clock eviction to L1 on pressure          │
     └───────────────────────┬──────────────────────────────────────┘
                             │ spill / promote
     ┌───────────────────────▼──────────────────────────────────────┐
 L1  │  WARM — RocksDB, one column family per (query, operator)     │
     │  local NVMe, block cache, bloom filters, WAL disabled        │
     │  (checkpoints provide durability, not the WAL)               │
     └───────────────────────┬──────────────────────────────────────┘
                             │ checkpoint / restore
     ┌───────────────────────▼──────────────────────────────────────┐
 L2  │  DURABLE — checkpoint store: Aerospike set, S3, or shared FS │
     │  incremental (SST hardlink upload), retained N checkpoints    │
     └──────────────────────────────────────────────────────────────┘
```

Selection is declared per state and can be overridden per query:

| State kind | Default tier | Reason |
|---|---|---|
| Windowed aggregate, < 1 M keys | L0 only, checkpoint to L2 | Fits memory; RocksDB is pure overhead |
| Windowed aggregate, ≥ 1 M keys | L0 + L1 | Working set hot, tail spills |
| Session windows | L0 + L1 | Unbounded key growth, merge-heavy |
| Join build side | L0 + L1 | Depends on cardinality; planner estimates |
| Lookup cache (FR-8) | L0 with TTL | Rebuilt from source; not checkpointed |
| Dedup / `ROW_NUMBER` filter | L0, TTL-bounded | Bounded by watermark |

### 14.3 RocksDB configuration for streaming window state

The draft's snippet uses `setOptimizeUniversalCompaction()`, no column families, and leaks `Options`/`WriteOptions`. Correct shape:

```java
final class RocksStateBackend implements StateBackendPlugin {

    private final DBOptions dbOptions = new DBOptions()
        .setCreateIfMissing(true)
        .setCreateMissingColumnFamilies(true)
        .setMaxBackgroundJobs(4)
        .setAvoidUnnecessaryBlockingIO(true)
        .setUseDirectReads(true)
        .setUseDirectIoForFlushAndCompaction(true)
        .setStatsDumpPeriodSec(0);                  // we export via Micrometer instead

    // WAL is disabled: durability comes from checkpoints, not from RocksDB's log.
    private final WriteOptions writeOptions = new WriteOptions().setDisableWAL(true);

    private ColumnFamilyOptions cfOptions(StateDescriptor d) {
        var tableCfg = new BlockBasedTableConfig()
            .setBlockCache(sharedBlockCache)         // ONE cache shared by all CFs on the node
            .setBlockSize(16 * 1024)
            .setFilterPolicy(new BloomFilter(10, false))
            .setCacheIndexAndFilterBlocks(true)
            .setPinL0FilterAndIndexBlocksInCache(true)
            .setOptimizeFiltersForMemory(true)
            .setFormatVersion(5);

        return new ColumnFamilyOptions()
            .setTableFormatConfig(tableCfg)
            .setCompactionStyle(CompactionStyle.LEVEL)   // level, not universal:
                                                         // window state is read-heavy and
                                                         // read amplification matters more
                                                         // than write amplification here
            .setLevelCompactionDynamicLevelBytes(true)
            .setWriteBufferSize(64L << 20)
            .setMaxWriteBufferNumber(4)
            .setMinWriteBufferNumberToMerge(2)
            .setCompressionType(CompressionType.LZ4_COMPRESSION)
            .setBottommostCompressionType(CompressionType.ZSTD_COMPRESSION)
            .setMergeOperator(new StringAppendOperator());  // for append-style aggregates
    }
    // ... all native handles registered with a NativeResourceRegistry and closed deterministically
}
```

Key points: **one shared block cache and one shared write-buffer manager per node** (otherwise 200 column families each allocate their own and the node OOMs); WAL off; level compaction; direct I/O to keep RocksDB's cache out of the page cache's way; bloom filters pinned. All native handles are tracked so `close()` cannot leak — RocksDB JNI leaks are silent and fatal in long-running processes.

### 14.4 Checkpointing and delivery guarantees (NFR-3)

**Mechanism — aligned barriers (Chandy–Lamport variant):**

```
1. CheckpointCoordinator (leader) issues checkpoint C_n to all source readers.
2. Each reader records its offset and injects a BARRIER(C_n) into its partition stream.
3. A lane that receives BARRIER(C_n) on one input blocks that input and waits for
   barriers on all inputs (alignment).  Unaligned mode is available for latency-
   sensitive queries at the cost of larger snapshots.
4. On full alignment: snapshot L0 arena (copy-on-write) + RocksDB checkpoint
   (hardlinked SSTs — cheap) → upload delta to L2 asynchronously.
5. Lane acknowledges C_n and unblocks.
6. When all lanes ack: coordinator commits checkpoint metadata (offsets + state
   handles) to the Raft store, then calls sink.commit(handle) for 2PC sinks.
7. Old checkpoints beyond the retention count are garbage collected.
```

**What we can honestly promise:**

| Layer | Guarantee | Mechanism |
|---|---|---|
| Engine state | **Exactly once** | Aligned barriers + atomic checkpoint commit + source rewind |
| Source | As reported by `SourceCapabilities` | XDR→Kafka: exactly-once-capable offsets. LUT-scan: at-least-once, no before-image. |
| Sink — transactional (Kafka, JDBC) | **Exactly once** | 2PC: `prepare` at snapshot, `commit` after checkpoint commit |
| Sink — idempotent upsert (Aerospike, Redis) | **Effectively once** | Deterministic key `(queryId, windowStart, groupKey)` + generation/CAS guard; replays overwrite with identical values |
| Sink — append-only, non-transactional (HTTP alert) | **At least once** | Unavoidable; the API and UI say so per query |

The engine computes the **weakest link** across source, engine and every sink, and reports that as the query's effective guarantee. This directly addresses G4 and the draft's unqualified NFR-3.

**Aerospike idempotent write:**

```java
// key is deterministic: same input replay produces the same key AND the same value
Key key = new Key(ns, set, queryId + '|' + windowStart + '|' + groupKey);
WritePolicy p = new WritePolicy();
p.recordExistsAction = RecordExistsAction.REPLACE;
p.expiration = ttlSeconds;
// guard: never let a replayed older checkpoint overwrite a newer committed result
p.filterExp = Exp.build(Exp.or(
    Exp.not(Exp.binExists(BIN_CHECKPOINT)),
    Exp.le(Exp.intBin(BIN_CHECKPOINT), Exp.val(checkpointId))));
client.put(p, key, bins);
```

**Savepoints** are operator-triggered, retained checkpoints used for query updates, version upgrades and migration — the mechanism behind the `UPDATING` state in §11.6.

### 14.5 Recovery

On node start or failover: read latest committed checkpoint metadata from Raft → download state handles for assigned partitions (parallel, with local SST reuse when the same node restarts) → restore L0 arenas and RocksDB CFs → reset readers to checkpointed offsets → resume. Target: ≤ 30 s for ≤ 10 GB (NFR-3), verified by a chaos test (§28.5).

---

## 15. Time, Watermarks, Windows & Changelog Semantics

### 15.1 Time domains

| Domain | Source | Use |
|---|---|---|
| **Event time** | A declared column, or the store's own metadata (Aerospike LUT, Cassandra `writetime`) | Default for all windowing — correct under reordering and replay |
| **Ingestion time** | Assigned at the source reader | Fallback when no event-time column exists |
| **Processing time** | Lane wall clock | Explicitly-requested windows only; never the default, because it is non-deterministic under replay |

All timestamps are epoch **nanoseconds** as `long` (range to year 2262; adequate and 2× cheaper than a 96-bit representation).

### 15.2 Watermarks

Each source partition runs a `WatermarkGenerator`:

- `BOUNDED_OUT_OF_ORDERNESS(d)` — `max(eventTime) − d` (default).
- `PUNCTUATED` — driven by a marker field, for sources that emit their own progress signals.
- `ASCENDING` — for provably ordered partitions.

A lane's watermark is `min` over its input partitions. **Idle partition handling is mandatory**: a partition silent for `idle.timeout` (default 30 s) is excluded from the min, otherwise one quiet Aerospike partition freezes every window in the query — the single most common streaming production incident, and worth stating explicitly.

Watermarks propagate as in-band control records through exchanges, so a downstream lane's watermark is the min across all upstream lanes that feed it.

### 15.3 Window implementation

**Slicing** is the key optimisation. A hopping window of 60 s hopping 10 s overlaps 6 windows; naive implementations update 6 aggregates per record. Instead, maintain per-10-s **slices** and combine 6 slices at firing time: **O(1) per record** instead of O(6), and 6× less state.

| Window | Representation | Firing |
|---|---|---|
| Tumbling(size) | One slice per window; key = `(groupKey, windowStart)` | Watermark ≥ `windowEnd` |
| Hopping(size, slide) | Slices of `gcd(size, slide)`; combine on fire | Watermark ≥ `windowEnd` |
| Cumulative(max, step) | Running accumulator + step emissions | Each step boundary |
| Session(gap) | Per-key interval set with merge-on-insert | Watermark ≥ `lastEvent + gap` |

Timers use a **hierarchical timing wheel** (Agrona `DeadlineTimerWheel`) per lane — O(1) schedule and cancel, versus O(log n) for a priority queue and far better cache behaviour at millions of timers.

### 15.4 Late data

```
record arrives with eventTime E, current watermark W, allowed lateness L
  E ≥ W                    → on time; normal path
  W > E ≥ W − L            → late but accepted; window state still exists;
                             re-fires and emits a retraction + updated result
  E < W − L                → too late; routed to the late side-output
                             (a named stream, optionally a DLQ sink), counter incremented
```

`allowed.lateness` is per query. Window state is retained until `windowEnd + lateness`, then released — this is what bounds state growth.

### 15.5 Changelog semantics (G8)

Every internal row carries a `RowKind`:

| Kind | Meaning |
|---|---|
| `+I` `INSERT` | New row |
| `-U` `UPDATE_BEFORE` | Retraction of a previously-emitted row |
| `+U` `UPDATE_AFTER` | The replacement row |
| `-D` `DELETE` | Removal (source tombstone, or window expiry) |

**Emit modes**, declared per query and negotiated with each sink:

| Mode | Emits | Sink requirement |
|---|---|---|
| `APPEND` | `+I` only | Any sink. Planner rejects the query if the plan can produce updates. |
| `UPSERT` | `+I`/`+U` collapsed to an upsert on a declared key; `-D` as delete | Sink must support keyed upsert (Aerospike, Redis, JDBC, compacted Kafka topic) |
| `RETRACT` | Full `-U`/`+U` pairs | Sink must handle retractions (Kafka, another Pravaha query) |

Mismatches are caught at **registration**, with a message naming the offending operator — not discovered in production. A `LEFT JOIN` or an unbounded aggregate produces updates; if the sink is append-only, the query is rejected with an explanation and a suggested fix.

### 15.6 Dead-letter queue

Records that cannot be decoded, violate schema, or repeatedly fail sink writes are routed to a per-query DLQ (a configured sink, default local file) with the raw bytes, the exception, the source offset and a correlation id — never dropped silently, never allowed to stop the pipeline. `dlq.max.rate` triggers an alert and, past a threshold, moves the query to `DEGRADED`.

---

## 16. Backfill, Bootstrap & Time Travel

The single most common reason a streaming project stalls is not throughput. It is this question: *"I registered my query today. The table has three years of history. Now what?"* Flink, ksqlDB and most CDC tools answer it badly, with a hand-rolled two-pipeline dance that customers get wrong. Solving it cleanly is differentiator **D-E** and a large share of the practical value of the product.

### 16.1 Consistent bootstrap — snapshot and stream, spliced correctly

The hard part is the handoff: a snapshot read at time T₀ and a change feed started "around" T₀ will either lose changes in the gap or double-count them in the overlap.

Pravaha's answer falls out of the Z-set model (§9). Because a snapshot row and a CDC row are the same object with the same weight semantics, and because inserts are keyed and idempotent under consolidation, the splice is correct by construction rather than by careful sequencing:

```
1. Start the CDC reader FIRST and buffer from position P₀ (do not process).
2. Take a consistent snapshot at a store-defined point T₀ ≥ P₀:
      Aerospike   → partition-parallel scan, filterExp on last_update_time ≤ T₀
      PostgreSQL  → REPEATABLE READ txn + EXPORT SNAPSHOT, LSN recorded
      Cassandra   → token-range scan with writetime ≤ T₀
      Kafka       → compacted-topic read to the recorded offset
3. Emit snapshot rows as a Z-set at logical time T₀, weight +1, keyed by PK.
4. Replay the buffered CDC from P₀, suppressing any change whose
   (primaryKey, version) is already covered by the snapshot — the
   dedupe window is exactly [P₀, T₀], and it is finite and known.
5. From T₀ forward, the CDC feed is the only input. Buffer released.
```

The overlap window is bounded by snapshot duration, the dedupe key is the store's own version/generation/LSN, and the operation is **restartable** — a crash mid-backfill resumes from the last checkpointed scan position, because backfill is checkpointed like everything else.

### 16.2 Backfill as a first-class, throttled, observable operation

Backfill competes with production OLTP traffic on the same storage cluster. Treating it as "just a fast source" is how a streaming rollout takes down a payments system.

| Control | Behaviour |
|---|---|
| `backfill.rate.limit` | Records/sec ceiling, enforced at the reader (`recordsPerSecond` on Aerospike scans, token-range pacing on Cassandra) |
| `backfill.parallelism` | Independent of steady-state lane count — backfill can use more lanes, then release them |
| `backfill.window` | Restrict to a time range (`WHERE event_time > …`) rather than all history |
| `backfill.adaptive` | Watch the store's own latency percentiles and back off automatically when p99 rises — the control loop that makes this safe to run in business hours |
| Progress | Rows done / estimated, ETA, current scan position, live in the UI and on `/api/v1/queries/{id}/backfill` |
| Pause / resume / abort | At any point, without losing completed work |

A query in backfill is in state `BACKFILLING`, serves partial results marked as such, and transitions to `RUNNING` only when the splice completes. Consumers can see the distinction; nobody makes a decision on a half-loaded aggregate believing it complete.

### 16.3 Blue/green query updates — zero-downtime evolution

Changing a running query's SQL is where incumbents force a stop-the-world restart. Pravaha does it as a shadow deployment, which is possible only because backfill is a routine, checkpointed capability rather than a special mode:

```
v1 RUNNING, serving reads  ──────────────────────────────────────────►
                                                                 cutover
v2 registered → BACKFILLING into shadow state ────► CAUGHT_UP ───────┘
                (from a savepoint of v1's sources, or from scratch)

At CAUGHT_UP: v2's frontier ≥ v1's frontier.
Cutover is an atomic assignment-map swap in the Raft store.
v1's state is retained for `rollback.retention` (default 1 h).
Rollback is the same swap, reversed, and takes the same few milliseconds.
```

Applies equally to: SQL changes, parallelism changes, plan changes from replanning (§18.5), schema evolution (§11.4), and engine version upgrades. **W2** and a meaningful part of the operations story depend on this.

### 16.4 Time-travel debugging

Nothing in the commercial field ships this, and it is the feature engineers will demo to each other.

Because (a) sources are replayable, (b) state is checkpointed at a known logical time, and (c) execution is deterministic given the same input and watermark sequence (§28.3 invariant 1), a running query can be **rewound and replayed under inspection**:

```
Operator observes wrong output at 14:32:07.
  → Open the query in the UI, click the incident on the timeline.
  → Engine forks a DEBUG instance from checkpoint C at 14:31:30
    (isolated lanes, isolated state, sinks disabled — production untouched).
  → Step forward: by record, by batch, by watermark advance, or to a breakpoint.
  → At any step, inspect:
       · the input batch, decoded, with weights and logical times
       · every operator's state slice, searchable by key
       · the generated source for the stage, with the current line highlighted
       · the output delta the operator produced, and why it was or wasn't pruned
  → Set a conditional breakpoint: "stop when group user_42's SUM goes negative".
  → Export the failing scenario as a `pravaha-testkit` JUnit fixture — one click.
```

That last step closes the loop: a production incident becomes a deterministic regression test in the repository, permanently. Sinks are hard-disabled in DEBUG mode and the isolation is enforced at the lane-allocation level, not by convention.

### 16.5 Historical replay and `AS OF` queries

Retained checkpoints plus replayable sources give bounded time travel over results:

| Capability | Bound | Use |
|---|---|---|
| `SELECT … AS OF SYSTEM TIME '…'` against a served view | Checkpoint retention (default 3, configurable) | Reconciliation, audit, "what did the dashboard say at close of business?" |
| Full historical re-derivation | Source retention (Kafka retention, store history) | Re-run a corrected query over past data without touching production |
| Savepoint fork | Indefinite while retained | Branch a query for what-if analysis on real historical data |

Combined with cross-view consistency (§9.5), `AS OF` reads across several views return a coherent picture of the past — which is what makes them usable for audit and regulatory reconciliation, and is a genuinely enterprise-grade capability to sell.

---

## 17. The Serving Layer

Differentiator **D-C**. Flink computes and then hands you the problem of where to put the answer. Pravaha's answer is already computed, already indexed, already in memory — so it serves it.

### 17.1 The insight

An incrementally-maintained view (§9) *is* a materialized table sitting in lane-local state, keyed by its `GROUP BY` key. Serving a point lookup against it requires no new machinery: hash the key to a virtual partition, route to the owning node and lane, probe the L0 arena. There is no network hop to a serving database because the serving database is not needed.

```
   Traditional (Flink + serving store)          Pravaha
   ────────────────────────────────────         ────────────────────────
   source → Flink → sink write → Redis          source → Pravaha
                                    ↓                        ↓
                            app → Redis read          app → Pravaha read
   2 systems, 2 network hops, ~1–5 ms,          1 system, 1 hop,
   eventual and unbounded staleness,            ~10–50 µs, bounded and
   nobody can say how stale                     *reported* staleness
```

### 17.2 Query surface

| Access | Latency target | Route |
|---|---|---|
| **Point lookup** `WHERE key = ?` | p99 ≤ 50 µs in-process, ≤ 200 µs over gRPC | Single node, single lane, one hash probe |
| **Multi-get** up to 1024 keys | p99 ≤ 500 µs | Scatter to owning lanes, gather |
| **Prefix / range scan** on the key | p99 ≤ 2 ms per 1 k rows | Ordered index over the key when declared `INDEXED BY RANGE` |
| **Full view scan** | Streaming | Consistent iterator at a pinned frontier |
| **Secondary predicate** | Best effort | Scan + filter; the planner warns and suggests an index at registration |

Exposed identically over gRPC (Arrow batches), Avatica JDBC — so BI tools and `psql`-style clients work — and a plain REST/JSON endpoint for application developers who want neither.

```sql
CREATE CONTINUOUS QUERY q_user_volume
INTO user_volume_agg
SERVE AS VIEW user_volume                    -- ← makes it queryable
  INDEXED BY (user_id) RANGE (window_end)    -- point + range
  WITH ('retention' = '24h', 'consistency.default' = 'consistent')
AS SELECT STREAM …;
```

```java
// A read is a read. No second system in the call.
Row r = pravaha.view("user_volume")
               .consistency(Consistency.CONSISTENT)
               .get(Key.of("user_42", windowEnd));
```

### 17.3 Consistency modes — stated, not assumed

Every read declares what it wants. Staleness is *returned with the answer*, which is the part competitors omit and the part auditors ask about.

| Mode | Semantics | Latency | Use |
|---|---|---|---|
| `LATEST` | Whatever the lane has right now; may include uncommitted work | Lowest | Dashboards, monitoring |
| `CONSISTENT` *(default)* | As of the latest **globally committed frontier** — all views agree on the same prefix of input (§9.5) | + one frontier check | Anything that joins or compares views; anything a person acts on |
| `AS_OF(t)` | As of logical time `t`, within checkpoint retention | + possible checkpoint read | Audit, reconciliation, "what did we see at 16:00?" |
| `AT_LEAST(t)` | Blocks until the frontier reaches `t`, then reads | Bounded wait, times out | Read-your-writes after a known input event |

Every response carries `logical_time`, `staleness_millis` and `frontier_complete`. An application can therefore *decide* whether the answer is fresh enough, rather than guessing.

### 17.4 Durability and the relationship to sinks

Serving from memory raises the obvious question. Three configurable postures, and the default is the safe one:

| Posture | Behaviour | Availability |
|---|---|---|
| `MEMORY` | State only; rebuilt from checkpoint on restart | Unavailable during recovery (seconds) |
| `MEMORY+SINK` *(default)* | Also written to the configured sink (e.g. an Aerospike set) | Readable by any client, including non-Pravaha ones, even while Pravaha is down |
| `SINK_ONLY` | No served view; classic sink-only behaviour | For queries where serving isn't wanted |

`MEMORY+SINK` is the important one commercially: it means adopting Pravaha never traps your results inside Pravaha. The data is in *your* Aerospike cluster, in a set you own, readable by every existing tool — and Pravaha additionally serves it faster. That removes the biggest objection to a new engine in a critical path.

### 17.5 Scaling reads

Read traffic is served by the lanes that own the data, which means read load lands on the same threads doing the writing. Two mechanisms keep reads from harming the pipeline:

- **Read replicas.** A view can be assigned *r* replicas; replicas apply the same committed deltas but do no computation, and absorb read traffic. Replica lag is reported per read.
- **Read admission control.** Reads run on a bounded, separate queue with a hard share of lane time (default 20 %); exceeding it queues rather than steals from ingest. Under sustained read pressure the engine reports read backpressure and the operator adds replicas — an explicit, observable failure mode rather than a mysterious throughput drop.

---

## 18. Adaptive & Self-Tuning Runtime

Differentiator **D-E**. Streaming systems do not usually fail because they are slow; they fail because tuning them requires an expert who has left the company. Every knob in §27 has a default, and the important ones have a control loop.

### 18.1 Principles for anything that tunes itself

Auto-tuning that cannot be understood, bounded or disabled is worse than no auto-tuning. Every adaptation in this section obeys four rules:

1. **Observable** — the decision, its inputs and its effect are logged and shown on the query timeline.
2. **Bounded** — each controller has explicit min/max limits and a maximum rate of change.
3. **Reversible** — the previous setting is retained and restored automatically if the metric worsens.
4. **Pinnable** — an operator can freeze any parameter, and pinning is respected permanently.

### 18.2 Adaptive batching

Fixed batch sizes force a choice between latency at low rates and throughput at high rates. A PI controller adjusts batch size and linger to hold a *declared latency target* instead:

```
target: p99 engine latency ≤ 250 µs   (from the query's SLO)

  observed p99 < 60 % of target  → grow batch (up to batch.max)      → more throughput
  observed p99 > 90 % of target  → shrink batch and linger           → less latency
  rate collapses to near zero    → linger floor, immediate flush     → no latency cliff
```

The effect that matters: a query at 10 rec/s and the same query at 1 M rec/s both meet their latency target, with no operator intervention and no reconfiguration.

### 18.3 Automatic skew remediation

A single hot key serialises onto one lane, and skew is the norm rather than the exception in real data (Zipf, not uniform — which is why Profile B is specified that way in §28.4).

```
Detect     per-lane rate histogram + per-key top-K sketch (Space-Saving, bounded memory)
Diagnose   one lane > 3× median AND one key > 30 % of that lane's traffic
Remediate  auto-salt that key only:  k → (k, salt ∈ 0..S-1) across S lanes,
           with an automatic merge operator restoring the true group
Verify     lane imbalance falls below threshold within 2 windows, or revert
Report     an event on the query timeline naming the key and the salt factor
```

Salting is applied **per key, not per query**, so the 99.99 % of keys that are not hot pay nothing. Only linear and associative aggregates are auto-salted; for others the engine reports the skew and recommends an action rather than silently changing semantics. Competitors detect skew, at best, in a metric an operator has to notice.

### 18.4 Elastic lane rescaling

```
sustained lane_backpressure_ratio > 0.30 for 60 s   → request more lanes
sustained lane_idle_ratio        > 0.70 for 300 s   → release lanes
```

Rescale migrates virtual partitions (§21.1) using the checkpoint machinery: snapshot the moving partitions, hand off, resume. Target pause ≤ 5 s per partition (NFR-6), with the rest of the query unaffected throughout. Cooldown periods prevent oscillation; a hard min/max lane count per query and per tenant prevents one query from consuming a node.

### 18.5 Live replanning

Cardinalities drift. A plan that was optimal at registration may be badly wrong six months later — a join order chosen when a table had 10 k rows and it now has 40 M.

```
1. Statistics refresh detects material drift (> 3× on any input cardinality).
2. Planner produces a candidate plan; if its cost estimate is not
   ≥ 25 % better, stop here — do nothing.
3. Candidate runs in SHADOW mode on a traffic sample, sinks disabled,
   and its actual measured cost is compared with the incumbent's.
4. Only if the candidate wins on *measured* cost does the engine swap it in,
   via the blue/green mechanism of §16.3 — no downtime, no lost state.
5. Automatic rollback if post-swap metrics regress within the observation window.
```

The discipline is that no plan change ever ships on an *estimate*. Cost models are wrong; measurements are not. Disabled by default, opt-in per query, because some organisations need plan stability more than plan optimality — and that is a legitimate choice to respect.

### 18.6 State tier auto-promotion

L0/L1 placement (§14.2) is decided per state slice from live behaviour, not from a static guess:

| Signal | Action |
|---|---|
| L0 hit ratio < 70 % and slice > `offheap.max` | Demote cold ranges to RocksDB |
| L1 slice with > 95 % of accesses in a hot range | Promote that range to L0 |
| Slice fits comfortably in L0 budget | Drop RocksDB CF entirely — no JNI cost at all |
| Total node state approaching limit | Demote across all queries by priority, alert, and stop admitting new queries |

### 18.7 Adaptive backfill throttling

Covered operationally in §16.2; the control loop belongs here. The backfill reader watches the *storage cluster's own* latency percentiles (via the plugin's health probe) and reduces its rate when the store's p99 rises above a configured multiple of baseline. This is what makes "backfill three years of history during business hours" a safe instruction rather than an outage.

### 18.8 What is deliberately not automatic

- **Emit mode and consistency mode** — semantics, not performance. Never inferred.
- **Allowed lateness** — a correctness/business decision.
- **State bounds** — an unbounded query is rejected (§9.6), not quietly capped.
- **Schema changes** — always explicit (§11.4).

The line is simple and worth stating to customers: Pravaha auto-tunes *performance*, never *semantics*.

---

## 19. Storage Plugin Deep Dive

### 19.1 Aerospike (primary target)

**The central constraint (G4): Aerospike has no Community-Edition change feed.** The plugin therefore implements four strategies, selected by configuration, each declaring different capabilities:

| Strategy | Requires | Latency | Guarantee | Before-image | Deletes | Notes |
|---|---|---|---|---|---|---|
| **`xdr-kafka`** *(recommended)* | Enterprise XDR + Aerospike Kafka outbound connector | ~10–100 ms | Exactly-once (Kafka offsets) | Configurable | Yes | Best fidelity, replayable, decoupled. Also gives free buffering and multi-consumer fan-out. |
| **`xdr-http`** | Enterprise XDR change-notification destination | ~5–50 ms | At-least-once (we own an ack log) | Configurable | Yes | Lowest latency, but Pravaha becomes the durable buffer — needs a local WAL to be replayable |
| **`lut-scan`** | **Community Edition OK** | scan interval (1 s–5 min) | At-least-once | No | No (invisible) | Partition-parallel scan with an expression filter on `record.last_update_time()` > watermark. Misses intra-interval overwrites and all deletes. Costs read load on the cluster. |
| **`write-intercept`** | Application uses the Pravaha Aerospike client wrapper | ~1 ms | At-least-once (local WAL) | Yes | Yes | Highest fidelity on CE, but intrusive — only for greenfield apps |

`lut-scan` predicate, partition-parallel:

```java
// One reader per Aerospike partition range → natural parallelism, resumable offsets
ScanPolicy policy = new ScanPolicy();
policy.filterExp = Exp.build(
    Exp.gt(Exp.lastUpdate(), Exp.val(watermarkNanos)));   // server-side, no wasted transfer
policy.maxConcurrentNodes = 1;
policy.recordsPerSecond = throttleRps;                    // protect the OLTP workload
PartitionFilter pf = PartitionFilter.range(startPartition, partitionCount);
client.scanPartitions(eventLoops, policy, pf, namespace, set, callback);
```

**Pushdown (FR-3).** Aerospike expression filters absorb a large fraction of typical `WHERE` clauses server-side; secondary indexes handle equality and range on indexed bins. The plugin translates `RexNode` → `Exp` for: comparisons, `AND`/`OR`/`NOT`, `IN` (as an `OR` chain up to a size limit), `IS NULL`/`binExists`, string prefix via regex, and arithmetic on numeric bins. Anything untranslatable stays as a residual filter in the engine — never silently dropped, which is the classic pushdown correctness bug. A property-based test asserts `filter(engine, rows) == filter(aerospike, rows)` over generated predicates.

**Sink.** Batched `operate()` with `RecordExistsAction.REPLACE` or bin-level `Operation.add()` for counters, generation-guarded for idempotence (§14.4), TTL from the window's expiry so results self-clean. Uses the async client with the shared event loops so dispatch threads never block.

**Lookup (FR-8).** Async `get` with batch coalescing (up to 512 keys per `batchGet`), L0 cache with TTL and a bounded size, negative caching, and a single-flight guard so 10 000 concurrent misses on one key issue one request.

**Type mapping.** Aerospike's type set is narrow (int/double/string/blob/list/map/HLL/geo); `DECIMAL`, `TIMESTAMP` and `BOOLEAN` require explicit encoding conventions, declared in the stream DDL and validated at registration. Bin-name length limits (15 chars) are checked at registration, not at write time.

### 19.2 Cassandra / ScyllaDB

- **Source:** CDC commitlog reader (Cassandra 4+ `cdc_raw`) as the primary; token-range-parallel incremental scan on `writetime` as a fallback. Scylla's CDC tables are a cleaner path and get a dedicated code path.
- **Pushdown:** partition key equality, clustering-key ranges, `ALLOW FILTERING` explicitly refused unless opted in per stream (it is a foot-gun that turns a query into a cluster-wide scan).
- **Sink:** prepared-statement batches scoped to a single partition (never cross-partition logged batches — a well-known Cassandra anti-pattern), with token-aware routing.

### 19.3 Kafka

Both a first-class connector and the recommended relay for Aerospike XDR. Exactly-once via transactional producer + offset commit inside the checkpoint. Consumer partitions map 1:1 to source partitions, which makes the parallelism story trivial.

### 19.4 Redis

Sink and lookup/hot-cache tier. Pipelined writes; `HSET`/`SET` with TTL; RESP3. Not a source in Phase 1 (Redis Streams as a source is Phase 3).

### 19.5 PostgreSQL / JDBC

- **Source:** logical decoding (`pgoutput`) replication slot — a genuinely excellent CDC feed with replayable LSN offsets and full before-images. This is the best-fidelity source in the whole plugin set and is valuable as the reference implementation for exactly-once.
- **Sink:** batched `INSERT … ON CONFLICT DO UPDATE`, XA or a checkpoint-id table for 2PC.

### 19.6 HTTP / gRPC sink

Alerting and webhooks. Per-endpoint circuit breaker, exponential backoff with jitter, bounded retry queue, at-least-once only — declared as such.

---

## 20. Gateways & Client Protocols

### 20.1 Why two protocols (G3)

| | Avatica | gRPC |
|---|---|---|
| Model | Request/response, finite cursors | Bidirectional / server streaming |
| Push | No | Yes |
| Flow control | None | HTTP/2 + application-level credits |
| Backpressure | None | Native |
| Watermarks / retractions | No place for them | First-class message fields |
| Existing clients | JDBC, `phoenixdb`, Go, JS | Generated for 11 languages |
| **Role in Pravaha** | **Control plane + bounded queries** | **Continuous results** |

Keeping Avatica is still worthwhile — it delivers the draft's polyglot promise for catalog browsing, DDL and BI-tool connectivity (any JDBC tool can point at Pravaha and see the catalog and materialized outputs). It just is not the streaming channel.

### 20.2 gRPC streaming API

```protobuf
service PravahaQueryService {
  rpc Register (RegisterQueryRequest) returns (RegisterQueryResponse);
  rpc Subscribe (SubscribeRequest) returns (stream ResultBatch);   // continuous push
  rpc Ack       (stream AckRequest) returns (stream FlowControl);  // credit-based
  rpc Control   (ControlRequest)    returns (ControlResponse);     // pause/resume/stop/drop
}

message ResultBatch {
  string query_id           = 1;
  uint64 batch_sequence     = 2;
  int64  watermark_nanos    = 3;
  int32  schema_version     = 4;
  bytes  arrow_ipc          = 5;   // Arrow record batch: columnar, zero-copy, polyglot
  repeated RowKind row_kinds= 6;   // parallel to the batch's rows
  uint64 checkpoint_id      = 7;   // for at-least-once client dedupe
  bool   is_late_output     = 8;
}
```

**Apache Arrow IPC** as the wire format is a deliberate choice: columnar, zero-copy on both ends, mature readers in Python/Go/Java/Rust/JS, and it pairs naturally with the engine's binary row layout. A Python client gets a `pyarrow.RecordBatch` — and therefore a pandas/Polars DataFrame — with no per-row Python object allocation, which is the difference between 5 k rows/s and 5 M rows/s in a Python consumer.

**Credit-based flow control:** subscribers grant credits; the server never sends more than the outstanding credit. A subscriber that stops acking stops receiving — but critically, per §13.3, the subscriber tap is a *conflating, drop-oldest* buffer, so a slow subscriber degrades its own view rather than backpressuring the engine. Subscribers choose `RELIABLE` (bounded buffer, disconnect on overflow) or `BEST_EFFORT` (conflate, report a `dropped_count`) at subscribe time.

### 20.3 Python client (replacing the draft's polling example)

```python
from pravaha import Client

with Client("grpc+tls://pravaha:9090", token=os.environ["PRAVAHA_TOKEN"]) as c:
    q = c.register("""
        SELECT STREAM window_end, user_id, txn_count, total_volume
        FROM q_user_volume_output
    """, emit="upsert")

    # Server-push. No polling, no sleep, columnar batches.
    for batch in q.subscribe(mode="best_effort", max_lag="5s"):
        df = batch.to_pandas()          # zero-copy via Arrow
        print(f"wm={batch.watermark} rows={len(df)} dropped={batch.dropped}")
```

### 20.4 Avatica gateway scope

Serves: `SHOW STREAMS/QUERIES/SINKS`, `DESCRIBE`, `CREATE/DROP` DDL, and bounded `SELECT` over materialized sink outputs (`LIMIT` pushed down). It explicitly **rejects** `SELECT STREAM` with a message pointing at the gRPC endpoint — a clear error beats a hanging cursor.

---

## 21. Clustering, HA & Elastic Scaling

### 21.1 Partitioning

Each query's key space is hashed into a fixed number of **virtual partitions** (default 1024, set at registration and immutable for the query's life). Virtual partitions are assigned to lanes; lanes to nodes. Rescaling moves virtual partitions, never rehashes keys — the standard consistent-assignment approach that makes elasticity tractable.

```
key --hash--> vpartition (0..1023) --assignment--> lane --placement--> node
```

### 21.2 Control plane

**Embedded Raft (Apache Ratis)** replicates: catalog, query definitions and versions, assignment maps, checkpoint metadata, plugin registry. Pluggable to ZooKeeper or etcd for shops that already run one.

Rejected alternative — storing metadata in the persistence store with a CAS lease — is simpler operationally but makes split-brain during assignment changes hard to exclude, and assignment correctness is exactly where split-brain is unacceptable (two nodes writing the same aggregate). Raft is the right cost here.

The Raft leader hosts the `CheckpointCoordinator` and `AssignmentManager`. Data-plane work is **not** routed through the leader — it is shared-nothing.

### 21.3 Failure handling

| Failure | Detection | Response |
|---|---|---|
| Node crash | Raft membership + failure detector (~5 s) | Reassign its vpartitions to survivors; restore from last checkpoint; sources rewind |
| Lane thread death | Watchdog on lane heartbeat | Restart the lane from checkpoint; three failures in 10 min → query `FAILED` |
| Plugin failure | Circuit breaker on plugin calls | Quarantine plugin; dependent queries → `DEGRADED`; alert |
| Sink unavailable | Write errors + latency | Retry with backoff → backpressure → DLQ past threshold |
| Slow node (grey failure) | Per-lane latency vs cluster p99 | Move vpartitions away; this catches the failure mode that pure liveness checks miss |
| Network partition | Raft quorum | Minority side stops processing (no split-brain writes) |

### 21.4 Multi-tenancy and resource isolation (FR-9)

Per-tenant quotas on: lane-count share, total state bytes, sink egress rate, registered query count, and generated-class metaspace. Enforced at registration (admission control) and at runtime (rate limiting + state-size guard, which moves the offending query to `DEGRADED` rather than letting it OOM the node). Lanes can be affinitised to tenants for hard CPU isolation where a shared node is unacceptable.

### 21.5 Deployment topologies

| Topology | Nodes | Use |
|---|---|---|
| Embedded (plain or Spring starter) | in-process library | Single-app enrichment; no cluster. Modes A and B of §22.2 |
| Single node | 1 | Dev, small workloads |
| HA cluster | 3, 5 | Production; Raft quorum |
| Multi-region | 3 per region, independent | Regional queries over regional stores; XDR handles replication |

---

## 22. Deployment Modes & Spring Boot Integration

Pravaha runs in four modes from one engine core. Three of them involve Spring Boot, and each involves it differently — which is the part that needs to be specified precisely, because getting the Spring boundary wrong is how an embeddable engine stops being embeddable.

### 22.1 The layering rule

> **The engine core contains no Spring. Spring is a bootstrap layer that wraps it, never a layer inside it.**

This is not stylistic. Three concrete consequences depend on it:

1. **Embeddability (D-D).** A customer embedding Pravaha into *their* Spring Boot 3.2 application cannot have us drag in Spring Boot 3.5. Two Spring contexts and two Spring versions on one classpath is a support nightmare, and it would silently destroy the moat in §2.2.
2. **Startup time.** A Spring context costs 1–3 s to initialise. `pravaha dev` targets **< 1 s** cold start (§24.1) and embedded mode must not tax its host's boot. Both take the Spring-free path.
3. **Hot path integrity.** Spring's proxies, AOP interception and managed executors must never come near a lane thread. Lane threads are created by the engine's own pinning thread factory, and nothing on the per-record path is a Spring bean.

Enforced, not merely intended: `maven-enforcer` bans `org.springframework:*` from every core module, and an ArchUnit rule fails the build if any class under `com.ash.messaging.pravaha.{api,common,algebra,runtime,state,sql,codegen,connect}` imports `org.springframework`. Same mechanism as the storage-client rule (NFR-4).

```
┌──────────────────────────────────────────────────────────────────┐
│  BOOTSTRAP LAYER  (Spring lives here, and only here)             │
│                                                                  │
│   pravaha-server            pravaha-spring-boot-starter          │
│   (Spring Boot app)         (auto-config into the HOST's app)    │
│   pravaha-ui                pravaha-cli / plain embedded         │
│   (Spring Boot app)         (no Spring at all)                   │
└───────────────────────────────┬──────────────────────────────────┘
                                │  PravahaEngine — plain Java lifecycle
                                ▼
┌──────────────────────────────────────────────────────────────────┐
│  ENGINE CORE  — zero Spring, zero reflection on the hot path     │
│  api · common · algebra · sql · codegen · runtime · state ·      │
│  serving · backfill · adaptive · connect · cluster · gateways    │
└──────────────────────────────────────────────────────────────────┘
```

The seam is a small plain-Java interface. Everything above it is replaceable; nothing below it knows what is above.

```java
// com.ash.messaging.pravaha.api — the entire contract a bootstrap needs
public interface PravahaEngine extends AutoCloseable {
    static PravahaEngine create(PravahaConfig config) { … }

    void start();
    void stop(Duration graceTimeout);
    EngineState state();

    QueryHandle register(String sql, QueryOptions options);
    Optional<QueryHandle> query(String queryId);
    Collection<QueryHandle> queries();

    ViewReader view(String viewName);          // §17 serving
    Catalog catalog();
    PluginRegistry plugins();
    MeterRegistry meters();                    // Micrometer — a plain, non-Spring dependency
    HealthReport health();
}
```

### 22.2 The four modes

| Mode | Artifact | Spring | Process | Use |
|---|---|---|---|---|
| **A — Plain embedded** | `pravaha-embedded` | **none** | Host application's | Any Java app; lowest footprint; `pravaha dev`; unit tests |
| **B — Spring-embedded** | `pravaha-spring-boot-starter` | Auto-config into the **host's** context | Host application's | A customer's own Spring Boot service embedding Pravaha |
| **C — Server** | `pravaha-server` | **Yes — it is a Spring Boot application** | Dedicated | The standard production deployment: single node or HA cluster |
| **D — Server + UI (all-in-one)** | `pravaha-server` with `pravaha.ui.enabled=true` | Yes | Dedicated, one process | Dev, POCs, and small production deployments — one jar, one port |

Mode C with the UI split out into a separate `pravaha-ui` deployment remains the recommendation at scale (§23.4); mode D exists so that "download one jar, run it, open a browser" is a real onboarding path. Both are supported and CI-tested.

**All four run the same engine code.** §24.6's deployment-parity claim depends on this: what differs between a unit test, `pravaha dev`, a customer's embedded service and a 5-node cluster is configuration and bootstrap, never the engine.

### 22.3 Mode C — `pravaha-server` as a Spring Boot application

The engine node *is* a Spring Boot 3.5 application. This is a deliberate reversal of the v3.1 draft, which described it as a plain fat jar, and the reason is the same one that decided the Java 21 baseline (§4.5): enterprise Java operations are built around Spring Boot's conventions, and inheriting them free is worth more than the 2 s of startup it costs a long-running server.

What Spring Boot provides in the server that we would otherwise hand-build:

| Capability | Spring Boot gives us |
|---|---|
| Externalised config | `application.yml`, profiles, env/`SPRING_APPLICATION_JSON`/command-line precedence, `@ConfigurationProperties` binding with validation |
| Health & readiness | Actuator `/actuator/health` with **separate liveness and readiness groups** (§26.2 requires this distinction) |
| Metrics | Micrometer auto-configuration → Prometheus scrape endpoint, JVM/system meters for free |
| Security | Spring Security + OIDC resource server for the REST and gRPC gateways (§25) |
| API docs | springdoc-openapi generating the OpenAPI spec from the controllers |
| Graceful shutdown | `SmartLifecycle` ordering, `server.shutdown=graceful`, drain-then-stop semantics |
| Virtual threads | `spring.threads.virtual.enabled=true` (Boot 3.2+) — exactly the control-plane model in §13.2 |
| Packaging | Layered jars, Buildpacks/Jib images, `spring-boot:build-image` |

```java
@SpringBootApplication
@EnableConfigurationProperties(PravahaProperties.class)
public class PravahaServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(PravahaServerApplication.class, args);
    }
}

@Configuration(proxyBeanMethods = false)
class PravahaEngineConfiguration {

    /** The engine is one bean. Spring owns its lifecycle and nothing else about it. */
    @Bean(destroyMethod = "")   // we manage shutdown explicitly via SmartLifecycle below
    PravahaEngine pravahaEngine(PravahaProperties props, ObjectProvider<PravahaCustomizer> customizers) {
        var builder = PravahaConfig.builder().from(props.toEngineConfig());
        customizers.orderedStream().forEach(c -> c.customize(builder));
        return PravahaEngine.create(builder.build());
    }

    /** Start after the web layer is ready to serve health; stop before it goes away. */
    @Bean
    SmartLifecycle pravahaLifecycle(PravahaEngine engine, PravahaProperties props) {
        return new SmartLifecycle() {
            @Override public int getPhase() { return Integer.MAX_VALUE - 1000; }
            @Override public void start() { engine.start(); }
            @Override public void stop()  { engine.stop(props.getShutdown().getGraceTimeout()); }
            @Override public boolean isRunning() { return engine.state() == EngineState.RUNNING; }
        };
    }

    @Bean MeterBinder pravahaMeters(PravahaEngine e) { return r -> e.meters().forEachMeter(r::register); }
    @Bean HealthIndicator pravahaHealth(PravahaEngine e) { return () -> toSpringHealth(e.health()); }
    @Bean HealthIndicator pravahaCheckpointHealth(PravahaEngine e) { … }   // readiness group
}
```

Note `@Configuration(proxyBeanMethods = false)` and `@Bean(destroyMethod = "")` — small details that matter. We do not want CGLIB proxies around engine wiring, and we want shutdown ordered explicitly through `SmartLifecycle` rather than by Spring's bean-destruction order, because lanes must drain before the gateways stop accepting.

**Startup budget.** Spring context ~1.5 s + engine init ~0.5 s ≈ **2 s to ready** for a server with no queries to restore. Restoring checkpointed state dominates after that (§14.5). Acceptable for a long-running node; the reason modes A and B exist is that it is *not* acceptable everywhere.

### 22.4 Mode B — `pravaha-spring-boot-starter`

This is the mode the question implies but that most engines never build properly, and it is worth real effort: it lets a customer add continuous SQL to a service they already have, in the framework they already use, without running a cluster.

```xml
<dependency>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha-spring-boot-starter</artifactId>
  <version>${pravaha.version}</version>
</dependency>
```

```yaml
# the host application's own application.yml
pravaha:
  mode: embedded
  runtime:
    lanes: 4
    wait-strategy: BACKOFF_PARK        # a co-resident engine should not busy-spin
  state:
    default-tier: HEAP
    offheap:
      max-per-lane: 256MB
  sources:
    orders:
      connector: kafka
      topic: orders
  queries:
    - name: high-value-orders
      sql: |
        SELECT STREAM order_id, customer_id, amount
        FROM orders WHERE amount > 10000
```

```java
@Service
class FraudService {

    // Spring-idiomatic facade, in the shape of JdbcTemplate / KafkaTemplate
    private final PravahaTemplate pravaha;

    FraudService(PravahaTemplate pravaha) { this.pravaha = pravaha; }

    /** Consume a continuous query's output the way you'd consume a Kafka topic. */
    @PravahaListener(query = "high-value-orders", concurrency = 4)
    void onHighValueOrder(HighValueOrder order) {          // typed from the query's schema
        riskEngine.evaluate(order);
    }

    /** Read a served materialized view (§17) — a point lookup in microseconds. */
    public BigDecimal volumeFor(String customerId) {
        return pravaha.view("customer_volume")
                      .consistency(Consistency.CONSISTENT)
                      .get(customerId)
                      .map(r -> r.getDecimal("total_volume"))
                      .orElse(BigDecimal.ZERO);
    }
}
```

`@PravahaListener` is deliberately modelled on `@KafkaListener` — same mental model, same concurrency semantics, same error-handling hooks (`@PravahaListener(errorHandler = …)`, DLQ routing per §15.6). Records are deserialised into the listener's parameter type using the query's catalog schema, so the method signature is the contract.

The starter contributes:

| | |
|---|---|
| `PravahaAutoConfiguration` | `@ConditionalOnMissingBean` throughout, so any bean the host defines wins |
| `PravahaProperties` | `@ConfigurationProperties("pravaha")` with JSR-303 validation and IDE completion via `spring-configuration-metadata.json` |
| `PravahaTemplate` | Register/query/subscribe/read-view facade |
| `@PravahaListener` + `PravahaListenerAnnotationBeanPostProcessor` | Declarative consumption |
| `@PravahaTest` | A test slice booting an in-memory engine with fixtures — no Docker, no cluster |
| Actuator | `pravaha` health indicator, `/actuator/pravaha` endpoint listing queries and lag, Micrometer meters bound automatically |
| `PravahaCustomizer` | Programmatic escape hatch for anything the properties don't cover |

**Dependency hygiene is the whole game here.** The starter declares Spring Boot as `provided`/`optional` scope and supports a *range* of Boot versions (3.2–3.5 at launch, tested in the CI matrix). The host's Spring version always wins. The starter itself adds only `pravaha-embedded` and its transitive engine dependencies, which contain no Spring.

### 22.5 Configuration across modes — one model, three binding paths

The engine consumes exactly one immutable `PravahaConfig`. How it gets built differs:

| Mode | Binding path |
|---|---|
| A — plain embedded | `PravahaConfig.builder()…build()` programmatically, or `PravahaConfig.fromYaml(path)` |
| B — Spring-embedded | Spring binds `pravaha.*` → `PravahaProperties` → `PravahaConfig`; host's property sources and profiles apply |
| C / D — server | Same as B, plus `application.yml`, env vars, command line, and runtime changes persisted to the Raft catalog (§27.1) |

The §27.1 precedence chain (defaults → file → environment → runtime API) is unchanged; Spring simply supplies a far richer implementation of the middle two layers in modes B, C and D. `PravahaProperties` is a mechanical mirror of `PravahaConfig` — kept in sync by a test that reflects over both and fails on any field present in one and absent from the other.

### 22.6 What Spring must never touch

| Rule | Enforcement |
|---|---|
| No `org.springframework` import in any core module | ArchUnit + `maven-enforcer` banned dependencies |
| Lane threads are created by the engine's thread factory, never a Spring `TaskExecutor` | Code review + a test asserting lane thread names and their creating factory |
| No Spring bean, proxy or `ApplicationContext` reachable from a per-record code path | ArchUnit: no class annotated `@HotPath` may reference a Spring type |
| No `@Transactional`, no AOP, no `ApplicationEvent` on the data path | Same rule |
| Engine shutdown is ordered explicitly, not by bean-destruction order | `SmartLifecycle` with an explicit phase; an IT asserts lanes drain before gateways close |
| The starter never forces its own Spring Boot version on the host | `provided` scope + a multi-version CI matrix (Boot 3.2 … 3.5) |

### 22.7 GraalVM native image — explicitly not supported

Spring Boot 3's AOT/native support is attractive for startup time, and it is worth stating plainly that **Pravaha cannot ship as a GraalVM native image**, so that nobody spends a sprint discovering it:

The engine compiles Java source with Janino at query-registration time (§12.4). Runtime code generation is fundamentally incompatible with a closed-world native image. This is not an oversight to be worked around — it is the direct cost of the design decision that makes the hot path fast (ADR-005).

`pravaha-ui` and the thin clients have no such constraint and *may* be built native if a deployment wants it. The engine cannot. Modes A and B already give sub-second startup where startup matters, which removes most of the motivation.

### 22.8 Module additions

| Module | Contents |
|---|---|
| `pravaha-spring-boot-starter` | Auto-configuration, `PravahaProperties`, `PravahaTemplate`, `@PravahaListener`, actuator contributions, `@PravahaTest` |
| `pravaha-server` | *(revised)* Spring Boot application: engine + gateways + actuator + optional embedded UI |
| `pravaha-embedded` | *(unchanged)* plain-Java facade, no Spring — what modes A and B both build on |

---

## 23. The Pravaha Console — Web UI

### 23.1 Two rules that look contradictory and are not

**Rule 1 — the console is never in the data path.** It consumes a conflating tap and a server-aggregated metrics stream. A console outage, a slow browser, or fifty analysts opening live previews must have exactly zero effect on query throughput. Enforced by the tap's drop-oldest semantics (§13.3) and by an ArchUnit rule forbidding UI modules from depending on runtime internals.

**Rule 2 — the console is a flagship product surface, not an accessory.** For most people who ever touch Pravaha, the console *is* Pravaha. They will never read this document, never call the gRPC API, and never see the codegen. Their entire judgement of the product is formed in a browser.

These are not in tension: the console is architecturally peripheral and experientially central. Both facts have to be designed for.

Three concrete consequences:

- **W10 — the time-travel debugger — lives entirely in the console.** It is a headline differentiator (§2.3, D-E) and it has no meaningful CLI form. If the console is mediocre, that differentiator does not exist.
- **D-E (operability) is delivered through the console.** "Operable by people who are not stream-processing experts" is a claim about a user interface before it is a claim about control loops.
- **§30's TCO argument counts operator time.** A console that makes an incident take twenty minutes instead of two hours is a line item in the cost comparison, not a nicety.

### 23.2 Four users, four different products

The console serves audiences with genuinely different jobs. A single undifferentiated "admin UI" serves none of them well. Navigation, default landing page and surfaced actions adapt to the signed-in role (§25 RBAC).

| Persona | Their job | Lands on | Cares most about |
|---|---|---|---|
| **Analyst / data engineer** | Write and iterate on continuous SQL | **SQL Workbench** | Catalog completion, instant validation, `EXPLAIN`, sampled dry-run, live result preview |
| **SRE / operator** | Keep it running; diagnose incidents | **Operations dashboard** | Backpressure, lag, watermark skew, checkpoint health, cluster topology, the debugger |
| **Application developer** | Consume results from their service | **Catalog / Views** | Schemas, served-view browser, consistency modes, copy-paste client snippets |
| **Platform admin** | Tenants, plugins, security, cost | **Administration** | RBAC, quotas, plugin health, audit trail, per-tenant resource use |

### 23.3 Stack

| Layer | Choice | Why this one |
|---|---|---|
| Backend | **Spring Boot 3.5** (Java 21), WebMVC on virtual threads, Spring Security + OIDC, springdoc-openapi | A BFF, not a second engine (§23.17) |
| Live updates | **SSE** for metrics/status/progress; **WebSocket (STOMP)** for the result tap and the debugger | SSE is simpler and auto-reconnects; WebSocket only where genuinely bidirectional |
| Frontend | **React 19 + TypeScript (strict)**, Vite, TanStack Query + Router | Mature, typed end-to-end from the OpenAPI spec |
| Styling | **Tailwind CSS + shadcn/ui**, extended with a Pravaha component layer | Owned components, not a framework we cannot restyle |
| SQL editor | **Monaco** + a Pravaha language service | Catalog-aware completion, inline diagnostics, hover types, format |
| Plan graph | **React Flow** with a custom ELK-based layout | The DAG is the signature screen; generic graph libraries look generic |
| Charts | **Apache ECharts** (canvas) | Canvas survives high-frequency updates; SVG charting does not |
| Tables | **TanStack Table** + TanStack Virtual | 100 k-row result previews without pagination theatre |
| Forms | React Hook Form + Zod, schemas generated from the OpenAPI spec | One source of truth for validation, client and server |
| State | TanStack Query for server state; Zustand for the little that is genuinely client state | No global store cargo cult |
| i18n | react-intl, strings externalised from day one | Retrofitting i18n costs 5× |
| Build | `frontend-maven-plugin` → pnpm → static resources in the Spring Boot jar | One artefact, `./mvnw -Pui verify` |

### 23.4 Design system

The console ships a real design system, defined once and enforced by lint rules and visual regression tests. Ad-hoc styling per screen is what makes internal tools look like internal tools.

| Layer | Definition |
|---|---|
| **Color — semantic** | `surface`/`surface-raised`/`surface-sunk`, `border`, `text`/`text-muted`/`text-subtle`, `accent`, and a state ramp: `ok`, `info`, `warn`, `critical`, `degraded`, `idle`. State color is **never** the accent — a running query and a branded button must not share a hue. |
| **Color — data** | A separate categorical palette (8 hues, colour-blind safe, checked against deuteranopia and protanopia) plus sequential and diverging ramps for heatmaps. Data colour is never reused for UI chrome. |
| **Typography** | One UI face + one monospace face. A 6-step scale. `tabular-nums` everywhere digits align — every metric, every table column, every latency figure. |
| **Spacing** | 4 px base, 8-step scale. No arbitrary pixel values; the lint rule rejects them. |
| **Density** | **Two modes: comfortable and compact.** An SRE on a 32" monitor watching 400 queries needs compact; a first-time user needs comfortable. Persisted per user. This is a real requirement in operations tooling, not a preference toggle. |
| **Theme** | Light and dark, both designed rather than one auto-inverted. Dark is the default for the operations surfaces, where it is genuinely easier during an incident at 03:00. |
| **Motion** | ≤ 150 ms transitions on state change; **no ambient or decorative animation** — motion in this product means *something changed*, and diluting that signal makes live data harder to read. `prefers-reduced-motion` fully honoured. |
| **Iconography** | One set (Lucide), consistent stroke weight. **No emoji in the UI.** |
| **Status vocabulary** | Every entity state renders as a chip with a fixed colour, a fixed icon and a fixed label. `RUNNING` looks identical on every screen it appears on. |

**Explicitly banned:** decorative gradients, purely ornamental illustration, animated backgrounds, marketing copy inside the product, and any element that moves without carrying information.

### 23.5 Information architecture

```
┌── Pravaha ──────────── [tenant ▾] ──── ⌘K ──── [alerts 3] ─ [user ▾] ─┐
│                                                                       │
│  Overview   Queries   Catalog   Views   Cluster   Plugins   Admin     │
└───────────────────────────────────────────────────────────────────────┘

/                                    role-aware landing
/queries                             list, filter, bulk actions
/queries/:id                         overview · plan · state · results · timeline · logs
/queries/:id/debug?t=<checkpoint>    time-travel debugger
/workbench                           SQL editor (new or ?query=:id)
/catalog/streams/:name               schema, versions, connector, lineage
/views/:name                         served view browser + client snippets
/cluster/nodes/:id                   node detail, assignments, drain
/plugins/:name                       health, capabilities, config
/admin/{tenants,roles,audit,quotas}
```

- **Every view is deep-linkable.** Filters, tab selection, time range and debugger position all live in the URL. An SRE pastes a link into the incident channel and a colleague sees exactly the same screen.
- **Command palette (⌘K / Ctrl-K)** is a first-class navigation and action surface: jump to any query, stream or node by name; run lifecycle actions; open the debugger — all without the mouse.
- **Tenant switcher** is global and always visible; the current tenant is unmistakable, because acting on the wrong one is the expensive mistake this UI can cause.

### 23.6 Screen inventory

| # | Screen | Primary job | Live-data strategy | Min. role |
|---|---|---|---|---|
| 1 | **Overview** | "Is everything healthy, and if not, where?" | SSE 1 Hz aggregate | viewer |
| 2 | **Queries list** | Find a query among hundreds; act on many at once | SSE 1 Hz, virtualised rows | viewer |
| 3 | **Query · Overview** | Health of one query at a glance | SSE 1 Hz | viewer |
| 4 | **Query · Plan** | Understand and diagnose the physical plan | SSE 1 Hz onto DAG nodes | viewer |
| 5 | **Query · State** | State size, tiers, checkpoints, savepoints | SSE 5 s | viewer |
| 6 | **Query · Results** | See what it is actually emitting, now | WebSocket, conflated | analyst |
| 7 | **Query · Timeline** | What happened to this query, and when | REST + SSE on new events | viewer |
| 8 | **Query · Errors / DLQ** | Inspect and replay poison records | REST paged | analyst |
| 9 | **SQL Workbench** | Author, validate, explain, dry-run, deploy | REST + WebSocket preview | analyst |
| 10 | **Time-travel Debugger** | Find out why a query produced a wrong row | WebSocket step protocol | operator |
| 11 | **Catalog · Streams / Tables / Sinks** | Discover what exists; inspect schemas & versions | REST, cached | viewer |
| 12 | **Catalog · Schema diff** | See exactly what a schema version changed | REST | viewer |
| 13 | **Views (serving)** | Browse and point-query served views; copy client code | REST on demand | developer |
| 14 | **Backfill control** | Start, throttle, monitor, pause a historical load | SSE 1 Hz progress | operator |
| 15 | **Blue/green cutover** | Compare v1 vs v2, cut over, roll back | SSE 1 Hz | operator |
| 16 | **Cluster · Topology** | Nodes, health, assignment distribution | SSE 2 s | viewer |
| 17 | **Cluster · Rebalance** | Drive and observe a rebalance | SSE job progress | operator |
| 18 | **Plugins** | Health, capabilities, circuit-breaker state, config | REST + SSE health | operator |
| 19 | **Adaptive controllers** | What auto-tuning did, and why; pin a parameter | SSE on decisions | operator |
| 20 | **Admin · Tenants & quotas** | Allocate and cap resources | REST | admin |
| 21 | **Admin · Roles & grants** | Who can do what | REST | admin |
| 22 | **Admin · Audit** | Full searchable audit trail | REST paged | admin |
| 23 | **Metrics explorer** | Ad-hoc charting of any exposed metric | SSE 1 Hz | viewer |
| 24 | **Onboarding / first run** | Get a first query running in under five minutes | — | any |

Screen 24 is not filler. A product that is hard to start is a product that is not adopted, and the first-run experience is the only screen every single user sees.

### 23.7 Signature surface — SQL Workbench

This is where the analyst persona forms their entire opinion of the product. It has to feel like an IDE, not a textarea with a Run button.

```
┌─ Workbench ────────────────────────────────────── [Validate ✓] [Explain] [Deploy] ─┐
│ ┌── Catalog ──────┐ ┌──────────────────────────────────────────────────────────┐  │
│ │ ▾ financial     │ │  1  CREATE CONTINUOUS QUERY q_user_volume                 │  │
│ │   ▾ streams     │ │  2  INTO user_volume_agg                                  │  │
│ │     txn_stream  │ │  3  SERVE AS VIEW user_volume                             │  │
│ │       txn_id    │ │  4  AS SELECT STREAM                                      │  │
│ │       user_id   │ │  5    TUMBLE_END(event_time, INTERVAL '10' SECOND) …      │  │
│ │       amount ⓘ  │ │  6  FROM txn_stream AS t                                  │  │
│ │   ▾ tables      │ │  7  LEFT JOIN user_profile FOR SYSTEM_TIME AS OF …        │  │
│ │     user_profile│ │  8  WHERE t.stat│                                          │  │
│ │   ▾ sinks       │ │       ┌──────────────────────────────────┐                 │  │
│ │     user_volume │ │       │ status      VARCHAR   txn_stream │ ← catalog-aware │  │
│ └─────────────────┘ │       │ state       VARCHAR   user_prof… │   completion    │  │
│                     └──────────────────────────────────────────────────────────┘  │
│ ┌── Diagnostics ───────────────────────────────────────────────────────────────┐  │
│ │ ⚠ PRV-2041  line 7 — LEFT JOIN can emit updates; sink 'alerts_http' is       │  │
│ │             append-only.  [Change emit mode] [Pick another sink] [Docs ↗]     │  │
│ └──────────────────────────────────────────────────────────────────────────────┘  │
│ ┌── Explain ─ Cost ─ Dry run ─ Preview ────────────────────────────────────────┐  │
│ │  est. 4 lanes · 180 MB state · 12 k rec/s in → 400 rec/s out · guarantee:    │  │
│ │  effectively-once  ·  [see plan DAG ↗]                                        │  │
│ └──────────────────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────────────────────────────────┘
```

Requirements that make this real rather than aspirational:

- **Validation as you type**, debounced to 300 ms, target **< 50 ms** server round trip (§24.1). Diagnostics appear inline with squiggles *and* in the diagnostics panel.
- **Errors carry their fix.** Every `PRV-nnnn` diagnostic (§24.4) renders with actionable buttons that apply the suggested change, plus a docs link. This is where the error-catalogue work pays off visually.
- **Catalog-aware completion**: tables in scope, columns with types, functions with signatures, and — critically — *only* what is valid at that cursor position.
- **Dry run against sampled live data** before deploying anything, with the sample clearly labelled.
- **Cost and resource estimate before registration**, so nobody deploys a query that will not fit.
- **Diff view** when editing an existing query: SQL diff *and* plan diff, so the blue/green consequence is visible before cutover (§16.3).
- Multi-tab, drafts autosaved locally, full keyboard operation, and a snippet library.

### 23.8 Signature surface — live plan DAG

The physical plan (§12.2) rendered as a graph with live telemetry flowing through it. This is the screen that makes an opaque engine legible, and it is the one people screenshot.

- Nodes are operators; **edge thickness encodes throughput**, edge colour encodes backpressure. A bottleneck is visible in under a second without reading a number.
- Per-node badges: rec/s in-out, p99 latency, state size, watermark lag. Click a node for a detail drawer with its generated source (§12.4), its state descriptor and its own metric history.
- **Lane view toggle:** collapse to the logical plan, or expand to per-lane instances to expose skew directly.
- Live watermark position shown travelling through the graph.
- Zoom, pan, fit, focus-on-node, and an exportable PNG/SVG for incident writeups.
- Automatic layout via ELK, stable across refreshes so nodes do not jump between renders — instability here destroys trust in the whole screen.

### 23.9 Signature surface — time-travel debugger

W10. There is no competitor equivalent, and it exists only here.

```
┌─ Debug · q_user_volume ─────────────────────────────── DEBUG · sinks disabled ─┐
│                                                                                │
│  ├────────●────────────────────────────────────────────┤                       │
│  14:31:30  ▲14:31:47                              14:32:10                     │
│  ckpt C-4471                                      ckpt C-4472                  │
│                                                                                │
│  [⏮ prev batch] [◀ step record] [▶ step record] [⏭ next watermark] [▶▶ run to] │
│  Breakpoint:  group = "user_42"  AND  SUM(amount) < 0            [armed ●]      │
│                                                                                │
│ ┌─ Input batch (32 rows) ────────┐ ┌─ Operator state ────────────────────────┐ │
│ │ seq   user_id  amount  w  kind │ │ agg[user_42] · window 14:31:40          │ │
│ │ 8841  user_42  120.00 +1  +I   │ │   count = 3      sum = −40.00  ← here   │ │
│ │ 8842  user_42 −160.00 +1  +I ◀ │ │ search key: [user_42          ]         │ │
│ └────────────────────────────────┘ └─────────────────────────────────────────┘ │
│ ┌─ Generated source · stage S2 ──┐ ┌─ Output delta ──────────────────────────┐ │
│ │  47  long s = state.getLong(o);│ │ (none — zero-delta pruned)              │ │
│ │  48▶ s += Layout.getLong(row,…)│ │                                         │ │
│ └────────────────────────────────┘ └─────────────────────────────────────────┘ │
│                        [Export as JUnit fixture]  [Copy permalink]             │
└────────────────────────────────────────────────────────────────────────────────┘
```

- Forked from a retained checkpoint into an isolated instance; **sinks hard-disabled**, and the banner says so permanently.
- Step by record, batch, or watermark advance; conditional breakpoints on state predicates.
- Simultaneous view of input rows (with Z-set weights), operator state, the generated source line, and the emitted delta — which is what makes a wrong answer explainable rather than mysterious.
- **Export as JUnit fixture** in one click — the incident becomes a permanent regression test (§16.4).
- Permalink shares the exact debugger position with a colleague.

### 23.10 Signature surface — backfill & cutover control

Backfill (§16.2) is a long-running, risky operation against a customer's production storage. The UI is where its safety is actually delivered.

- Live progress: rows done / estimated, current scan position, ETA, achieved rate.
- **The storage cluster's own p99 latency plotted next to our ingest rate**, so the operator sees the impact they are causing, not just the progress they are making. A throttle slider with immediate effect, and a visible indicator when adaptive throttling has engaged.
- Pause / resume / abort, always available, always safe.
- Cutover screen: v1 and v2 side by side with frontier positions, row counts and a sampled output diff; a cutover button that is deliberately deliberate; and a rollback button that stays available for the retention window.

### 23.11 Live data strategy

A monitoring UI that hammers the control plane is its own outage. Every stream has a declared budget.

| Surface | Transport | Rate | Aggregation | Backpressure behaviour |
|---|---|---|---|---|
| Overview / lists | SSE | 1 Hz | Server-side, all queries in one message | Drop to 0.2 Hz on a hidden tab |
| Query detail / DAG | SSE | 1 Hz | Per-query envelope | Pause entirely when the tab is hidden |
| Result preview | WebSocket | ≤ 20 Hz | Conflating, drop-oldest | **Shows "sampled — N dropped"**; never backpressures the engine |
| Debugger | WebSocket | on demand | — | Request/response stepping |
| Long jobs (rebalance, backfill, restore) | SSE | 1 Hz | Job envelope | Reconnect resumes from last event id |
| Metrics explorer | SSE | 1 Hz | Query-scoped subscription | Unsubscribed on navigation |

Rules: the browser never polls when a stream exists; `document.hidden` suspends every subscription; every SSE stream reconnects with `Last-Event-ID`; ten viewers of one query cost the engine **one** tap, fanned out by the BFF.

### 23.12 State discipline

Most internal tools are polished on the happy path and raw everywhere else. Every data-bearing component in the console must implement all eight states, and this is checked in review and by Storybook coverage.

| State | Requirement |
|---|---|
| **Loading (first)** | Skeleton matching the eventual layout — never a spinner on a blank page |
| **Loading (refresh)** | Existing data stays visible; a subtle freshness indicator updates. Never blank-then-refill. |
| **Empty (never had data)** | Explains what this is and offers the action that creates the first one |
| **Empty (filtered to nothing)** | Distinct from the above, and offers to clear the filter |
| **Error** | What failed, whether it is retryable, a retry button, and a correlation id to paste into a ticket |
| **Partial** | Some nodes answered, some did not — shows what is missing rather than silently under-reporting |
| **Stale** | Stream disconnected: data dims, a banner shows age and reconnect state. **Never show stale numbers as if they were live.** |
| **Unauthorized** | The affordance is absent or disabled with a reason — never a button that fails on click |

### 23.13 Data visualisation standards

Drawn from §26.1: this is an engine whose value is tail latency, and charting it badly misrepresents the product.

- **Percentiles, never averages.** Latency renders as p50/p99/p99.9/p99.99 lines or as a full HdrHistogram distribution. An average latency chart is a bug.
- Log scale by default for latency; linear for rates.
- Every chart states its time window and its aggregation interval on the chart.
- Fixed y-axis scales when comparing series side by side, so two panels are actually comparable.
- Heatmaps (query × lane backpressure) use a perceptually uniform ramp, not a rainbow.
- Sparklines in table rows share one y-scale per column.
- Every chart is keyboard-focusable with a screen-reader-accessible data table behind it (§23.14).
- Null and gap handling is explicit: a gap in data draws as a gap, never as an interpolated line.

### 23.14 Accessibility and keyboard-first operation

Target: **WCAG 2.2 AA**, verified by automated axe checks in CI plus a manual audit each phase.

- Full keyboard operation for every workflow, including the DAG and the debugger. The command palette is the fast path.
- Visible focus rings that survive theming; logical tab order; focus trapping and restoration in dialogs.
- Contrast ≥ 4.5:1 for text and ≥ 3:1 for UI boundaries, **in both themes** — verified by a token-level test, not by eye.
- Status is never encoded by colour alone: chips carry icon and text.
- Live regions announce state changes to screen readers, rate-limited so a busy dashboard is not a stream of noise.
- `prefers-reduced-motion` honoured throughout.

Accessibility here is not only compliance. Keyboard-first operation is what an SRE actually wants at 03:00, and it is the same work.

### 23.15 Console performance budget

A slow monitoring UI is worse than no monitoring UI, because it is consulted during incidents.

| Metric | Budget |
|---|---|
| Initial JS bundle (gzipped) | ≤ 250 kB; route-split, Monaco and ECharts lazy-loaded |
| Time to interactive, cold | ≤ 2.0 s on a mid-range laptop |
| Route transition | ≤ 200 ms |
| Queries list with 1 000 rows | Virtualised; ≤ 16 ms per frame while streaming |
| Plan DAG with 200 nodes | ≤ 16 ms per frame during live updates |
| Result preview at 20 Hz | No dropped frames; memory flat over an hour |
| Memory after 8 h idle on a dashboard | No growth — a leak test runs nightly |

Enforced by Lighthouse CI and a bundle-size gate in the pipeline (§5 of the implementation plan), on the same principle as the JMH gates: performance is a test.

### 23.16 Security in the console

- **RBAC drives affordances.** Actions a role cannot perform are absent, not present-and-failing. The server re-checks regardless (§25) — the UI is convenience, never enforcement.
- Secrets are redacted server-side before serialisation. A connector's password never reaches the browser, in any view, including `EXPLAIN` output.
- Destructive actions (drop query, cutover, rebalance, delete tenant) require typed confirmation of the object's name, and appear in the audit trail with the initiating user.
- CSP with no `unsafe-inline`; no third-party scripts, fonts or analytics; **no telemetry** (§30.4).
- Session handling via OIDC with silent refresh; explicit re-authentication before admin actions.
- The current tenant is always visible, because acting on the wrong tenant is this UI's most expensive possible mistake.

### 23.17 Backend design — a BFF, not a second engine

```java
@RestController
@RequestMapping("/api/v1/queries")
class QueryController {

    @PostMapping
    ResponseEntity<QueryDto> register(@Valid @RequestBody RegisterQueryRequest r) { … }

    @PostMapping("/validate")                     // < 50 ms target, called on every keystroke burst
    ValidationDto validate(@RequestBody String sql) { … }

    @GetMapping("/{id}/explain")                  // logical | optimized | physical | codegen
    ExplainDto explain(@PathVariable String id, @RequestParam ExplainLevel level) { … }

    @GetMapping(value = "/{id}/metrics", produces = TEXT_EVENT_STREAM_VALUE)
    SseEmitter metrics(@PathVariable String id) { … }   // 1 Hz, aggregated server-side
}
```

- The BFF talks to engine nodes over the **internal gRPC control API**. It is a client of the engine, never a co-resident part of it. In mode D (§22.2) it shares a JVM with a node for convenience but still uses the same API, so the two are never coupled and the console can be scaled, restarted or split out without changing anything.
- **Fan-out happens here.** Ten browsers watching one query share one engine subscription. Result-tap sessions are capped per user and per query, with a sampling rate that adapts to subscriber count.
- All metrics are aggregated server-side at a fixed 1 Hz. The browser never receives per-record data.
- Long-running actions are async jobs with progress over SSE, never blocking HTTP calls.
- The OpenAPI spec is the contract: TypeScript types and Zod schemas are **generated** from it in the build, so a backend change that breaks the frontend fails compilation rather than production.

### 23.18 Frontend testing

| Level | Tool | Gate |
|---|---|---|
| Unit / component | Vitest + Testing Library | Every component's eight states (§23.12) covered |
| Contract | Generated types from OpenAPI | Type errors fail the build |
| Visual regression | Playwright + snapshot, light **and** dark, both densities | No unreviewed pixel change |
| Accessibility | axe-core in component and E2E tests | Zero violations; blocking |
| E2E | Playwright against a real `pravaha dev` engine | The eight critical journeys below |
| Performance | Lighthouse CI + bundle-size budget | §23.15 budgets are gates |

**The eight critical journeys**, run on every PR: first-run onboarding → first query; author-validate-explain-deploy; diagnose a backpressured query from the dashboard; inspect and act on a DLQ record; start and throttle a backfill; blue/green update with rollback; debug a wrong result and export the fixture; grant a role and verify the affordance appears.

### 23.19 Build and packaging

```
pravaha-ui/
├── pom.xml                      frontend-maven-plugin → pnpm → target/classes/static
├── src/main/java/…              Spring Boot BFF
└── src/main/frontend/
    ├── src/{app,components,features,lib,styles}
    ├── src/design-system/       tokens, primitives, Storybook
    └── e2e/                     Playwright
```

- `./mvnw -Pui verify` builds and tests everything; without `-Pui` the reactor skips Node entirely, so backend engineers never wait on pnpm.
- Storybook is published per build as living documentation of the design system.
- Deployable standalone or embedded in `pravaha-server` (mode D, §22.2) — same artefact, different bootstrap.

### 23.20 What "polished" means — the acceptance checklist

Not a feeling. A release gate.

- [ ] Every screen implements all eight states of §23.12
- [ ] Light and dark both designed and visually regression-tested; both densities likewise
- [ ] Zero axe violations; WCAG 2.2 AA verified by manual audit
- [ ] Every workflow completable by keyboard alone
- [ ] Every view deep-linkable; every filter in the URL
- [ ] Every destructive action confirmed, audited and reversible where reversal is possible
- [ ] Every error message names the cause, the fix and a correlation id
- [ ] Every latency chart shows percentiles; no averages anywhere
- [ ] Stale data visibly stale; partial data visibly partial
- [ ] §23.15 performance budgets met and gated in CI
- [ ] Onboarding: a new user reaches a running query in **under five minutes**, measured with real people
- [ ] The eight critical journeys pass on every PR
- [ ] No secret is ever serialised to the browser, verified by a test
- [ ] Storybook covers every design-system component with all its states

---

## 24. Developer Experience

Differentiator **D-D**. Streaming engines are chosen by the engineer who has to use one on a Tuesday afternoon. Flink's most-cited weakness is not its performance — it is that the loop from "I have an idea" to "I see output" takes minutes and a cluster. Compressing that loop to seconds is a competitive advantage, and it has to be designed in rather than bolted on.

### 24.1 The inner loop

| Step | Flink SQL (typical) | Pravaha (target) |
|---|---|---|
| Set up a local environment | Docker Compose: JobManager, TaskManager, Kafka, ZooKeeper | `pravaha dev` — one process, fixtures from YAML, **< 1 s** |
| Validate a query | Submit and wait | **< 50 ms**, live in the editor as you type |
| See the plan | `EXPLAIN`, textual | **< 200 ms**, plan DAG + cost + generated source |
| First output | 30–120 s job submit | **< 2 s** deploy |
| Change one line | Full restart, state lost | Blue/green update, state preserved (§16.3) |
| Write a unit test | Mini-cluster, `Thread.sleep`, flaky | Virtual clock, deterministic, no sleep (§24.3) |
| Debug wrong output | Logs, guesswork | Time-travel debugger (§16.4) |

### 24.2 The CLI

```bash
pravaha dev                          # in-process engine + fixture sources, hot reload on file save
pravaha validate query.sql           # exit code + precise diagnostics, CI-friendly
pravaha explain query.sql --level physical --format dag
pravaha bench query.sql --profile B --lanes 8      # local JMH-backed benchmark
pravaha diff v1.sql v2.sql           # plan-level diff: what actually changes, and the state impact
pravaha replay --query q1 --from 14:31:30 --to 14:32:10   # time travel
pravaha test --record incident-4471  # export a production incident as a JUnit fixture
```

`pravaha dev` is the load-bearing one. It reads a fixture file, runs the real engine — same code path, same planner, same codegen — and prints results to the terminal with a live plan view. No Docker, no Kafka, no cluster, no YAML ceremony. A developer can try an idea in the time it takes to think of the next one.

### 24.3 Testing a query is testing a function

```java
@ExtendWith(PravahaExtension.class)
class UserVolumeQueryTest {

    @PravahaQuery("queries/user_volume.sql")
    ContinuousQuery query;

    @Test
    void aggregatesCompletedTransactionsPerWindow(TestHarness h) {
        h.at("10:00:01").send("txn_stream", txn("t1", "alice", 100.00, "COMPLETED"));
        h.at("10:00:03").send("txn_stream", txn("t2", "alice", 250.00, "COMPLETED"));
        h.at("10:00:05").send("txn_stream", txn("t3", "alice",  50.00, "PENDING"));

        h.advanceWatermarkTo("10:00:15");           // virtual clock — no sleeping

        assertThat(h.output()).containsExactly(
            row("10:00:10", "alice", 2, dec("350.00")));
    }

    @Test
    void lateRecordRetractsAndReemits(TestHarness h) { … }   // retraction semantics, tested directly
}
```

Deterministic, sub-second, no infrastructure, and it exercises the real planner and the real generated code. Streaming logic becomes ordinary testable code — which is the actual precondition for anyone putting it in a critical path.

### 24.4 Error messages as a feature

Most planner rejections in streaming SQL are cryptic. Every Pravaha diagnostic names the construct, the reason, the location, and a concrete fix:

```
PRV-2041  Query produces updates but sink 'alerts_http' is append-only.

  at line 7, col 3:   LEFT JOIN user_profile …
                      ^^^^ this LEFT JOIN can emit updates when a
                           previously-unmatched row later matches

  The sink 'alerts_http' declares emit modes: [APPEND]
  This query requires one of:                 [UPSERT, RETRACT]

  Fix one of:
    · change the sink to one supporting UPSERT (e.g. aerospike, redis, jdbc)
    · add  EMIT CHANGES WITH ('emit.mode'='upsert')  and declare key.fields
    · change the LEFT JOIN to an INNER JOIN if unmatched rows are not needed

  See: https://docs.pravaha.io/errors/PRV-2041
```

Every error code is stable, documented, and has a page with a runnable reproduction. This is boring work with a disproportionate effect on adoption.

### 24.5 Catalog-typed clients

Clients are generated from the query's actual output schema, so consumers get real types instead of `Object[]` and `row[2]`:

```python
# generated from the registered query — mypy sees these types
for batch in pravaha.q_user_volume.subscribe():
    for row in batch:
        row.user_id      # str
        row.total_volume # decimal.Decimal
        row.window_end   # datetime
```

```java
for (UserVolumeRow row : query.subscribe()) {
    BigDecimal volume = row.totalVolume();   // no casts, no ordinals
}
```

### 24.6 Parity and documentation

- **Deployment parity.** Embedded, single-node and HA run the *same engine binary and same code paths*. What works locally works in production — the difference is configuration, not implementation.
- **Docs-as-tests.** Every code block and every SQL snippet in the documentation is extracted and executed in CI. Documentation cannot rot silently.
- **A plugin TCK.** Third-party connector authors run a conformance suite that checks capability declarations against actual behaviour — including the ones that are easy to claim and hard to honour, like replayable offsets and idempotent writes. Ecosystem growth without a correctness cliff.
- **Migration paths.** `pravaha import --from flink-sql` and `--from ksql` translate the common subset and produce an explicit report of what could not be translated and why. Lowering switching cost is a distribution strategy.

---

## 25. Security Architecture

| Concern | Design |
|---|---|
| **Transport** | mTLS between nodes (Raft + data), TLS 1.3 on all external endpoints; certificate rotation without restart |
| **Human authn** | OIDC (Keycloak/Okta/Entra) via Spring Security; no local password store |
| **Service authn** | mTLS client certs or OAuth2 client-credentials JWT; short-lived tokens |
| **Authz** | RBAC to stream/table/sink/query granularity. Roles: `viewer`, `analyst` (register queries in own namespace), `operator` (lifecycle, rebalance), `admin`. Permissions checked at registration *and* re-checked at deploy. |
| **Row/column security** | Optional row filters and column masks per role, injected by the planner as an unremovable filter/project above the scan — enforced in the plan, so it cannot be bypassed by clever SQL |
| **Secrets** | Never in YAML. Resolved from env, files, HashiCorp Vault or cloud secret managers via a `SecretProvider` SPI; redacted in logs, API responses, EXPLAIN output and UI |
| **Plugin trust** | Optional jar signature verification; classloader isolation; plugins run with a documented capability list surfaced in the UI before install |
| **SQL injection** | Not applicable to the engine's own parsing, but the UI/REST layer parameterises everything and the catalog rejects identifiers that are not valid Pravaha identifiers |
| **Audit** | Append-only audit log of every lifecycle and authz decision: actor, action, target, timestamp, source IP, correlation id, result. Shipped to the configured audit sink. |
| **Resource abuse** | Admission control on plan cost; per-tenant quotas (§21.4); query timeout for bounded queries; hard cap on generated-class count |
| **Supply chain** | `dependency-check` / OSV scanning in CI, SBOM (CycloneDX) per release, reproducible builds via Jib, pinned dependency versions in the BOM |

---

## 26. Observability

### 26.1 Metrics (Micrometer → Prometheus)

| Group | Metrics |
|---|---|
| Ingest | `records_in_total{query,stream,partition}`, `bytes_in_total`, `source_lag_records`, `source_lag_millis` |
| Lane | `lane_batch_size`, `lane_busy_ratio`, `lane_idle_ratio`, `lane_backpressure_ratio`, `lane_records_per_sec` |
| Latency | `engine_latency_nanos` (HdrHistogram → p50/p99/p99.9/p99.99), `end_to_end_latency_millis` |
| Watermark | `watermark_lag_millis`, `late_records_total`, `dropped_late_total`, `idle_partitions` |
| State | `state_bytes{tier}`, `state_entries`, `state_l0_hit_ratio`, `rocksdb_*` (block cache hit, compaction pending, SST count, memtable size) |
| Checkpoint | `checkpoint_duration_millis`, `checkpoint_size_bytes`, `checkpoint_alignment_millis`, `checkpoint_failures_total` |
| Sink | `records_out_total`, `sink_latency_millis`, `sink_errors_total`, `dlq_records_total` |
| Plugin | `plugin_call_duration`, `plugin_errors_total`, `circuit_breaker_state` |
| JVM | Allocation rate, ZGC pause distribution, safepoint duration, metaspace (codegen leak canary), thread counts |

**Percentiles, never averages.** Every latency metric is an HdrHistogram with coordinated-omission correction. An average latency in a streaming system tells you essentially nothing.

### 26.2 Tracing, logging, profiling

- **Tracing (OpenTelemetry):** control plane only — registration, planning, checkpoint coordination, rebalance. Per-record tracing is deliberately absent; it would cost more than the processing. Instead, an **opt-in sampled record trace** (1 in N) attaches a trace context to individual records for debugging, off by default.
- **Logging:** structured JSON, correlation id on every control-plane operation, per-query MDC. Hot path logs **nothing** — errors increment counters and, at most, sample into a rate-limited error log.
- **Profiling:** JFR always on with a low-overhead profile (~1 %), with custom `PravahaBatchEvent` / `PravahaCheckpointEvent` types. async-profiler attachable at runtime; the UI can trigger and download a flame graph for a node.
- **Health:** `/actuator/health` with liveness (JVM alive) and readiness (assignments loaded, plugins healthy, checkpoints current) separated — conflating the two causes needless Kubernetes restart loops.

---

## 27. Configuration & Deployment

### 27.1 Configuration model

Three layers, precedence low → high: packaged defaults → node YAML (`pravaha.yaml`) → environment/system properties → runtime API/UI changes (persisted to the Raft catalog). The draft's single-query YAML becomes *node* configuration; queries themselves are registered entities, not config file entries.

```yaml
pravaha:
  node:
    id: ${PRAVAHA_NODE_ID:pravaha-01}
    rack: ${RACK:default}

  cluster:
    mode: HA                              # EMBEDDED | SINGLE | HA
    metadata:
      type: RAFT                          # RAFT | ZOOKEEPER | ETCD
      peers: ["pravaha-01:9070","pravaha-02:9070","pravaha-03:9070"]
      storage.dir: /var/lib/pravaha/raft

  runtime:
    lanes: ${PRAVAHA_LANES:14}            # cores - 2 on this 16-core reference box
    lane.affinity: true
    wait.strategy: SPIN_THEN_YIELD        # BUSY_SPIN | SPIN_THEN_YIELD | BACKOFF_PARK | BLOCKING
    batch.max.records: 512
    batch.max.linger: 200us
    arena.slab.size: 4MB
    arena.max.per.lane: 256MB
    ring.capacity: 65536                  # power of two
    backpressure.high.watermark: 0.80
    backpressure.low.watermark: 0.50

  state:
    default.tier: HYBRID                  # HEAP | OFFHEAP | ROCKSDB | HYBRID
    offheap.max.per.lane: 1GB
    rocksdb:
      dir: /var/lib/pravaha/state
      block.cache: 4GB                    # SHARED across all column families
      write.buffer.manager: 2GB           # SHARED — prevents per-CF memory blowup
      compaction.style: LEVEL

  checkpoint:
    enabled: true
    interval: 30s
    timeout: 120s
    min.pause.between: 5s
    max.concurrent: 1
    alignment: ALIGNED                    # ALIGNED | UNALIGNED
    retain: 3
    store:
      type: aerospike                     # aerospike | s3 | filesystem
      namespace: pravaha_meta
      set: checkpoints

  gateways:
    grpc:    { port: 9090, tls: true, max.inbound.message: 16MB }
    avatica: { port: 8765, tls: true }
    internal:{ port: 9080, mtls: true }

  plugins:
    dir: /opt/pravaha/plugins
    isolation: true
    verify.signatures: false

  security:
    oidc.issuer: https://sso.example.com/realms/pravaha
    secrets.provider: VAULT
    audit.sink: kafka://audit-cluster/pravaha-audit

  observability:
    metrics.prometheus.port: 9095
    tracing.otlp.endpoint: http://otel-collector:4317
    jfr.enabled: true
```

### 27.2 JVM flags (reference)

```
-XX:+UseZGC -XX:+ZGenerational        # ZGenerational is a no-op on 24+, where it is the default
-Xms16g -Xmx16g                        # fixed heap; the hot path barely allocates
-XX:MaxDirectMemorySize=8g
-XX:+AlwaysPreTouch
-XX:+UseTransparentHugePages
-XX:-RestrictContended                 # allow @Contended padding
-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints   # accurate profiles
-XX:StartFlightRecording=settings=pravaha-low,disk=true
--enable-native-access=ALL-UNNAMED     # only on the FFM profile (JDK 22+)
--add-modules jdk.incubator.vector     # optional SIMD kernels; still incubating on every release
```

### 27.3 Packaging

Jib-built distroless images; a Helm chart with a StatefulSet (stable identity for local state), PodDisruptionBudget, pod anti-affinity across zones, node-local NVMe PVCs for RocksDB, and HPA driven by `lane_backpressure_ratio` rather than CPU (CPU is a poor proxy when the wait strategy spins).

---

## 28. Testing & Benchmarking Strategy

Correctness in a streaming engine is unusually hard because bugs are timing-, order- and failure-dependent. The strategy is layered accordingly.

### 28.1 Deterministic test harness (`pravaha-testkit`)

A **virtual clock** and a **single-threaded deterministic scheduler** let an entire multi-lane pipeline run reproducibly: no `Thread.sleep`, no flakiness, exact control over watermark advance, record interleaving and failure injection points. Every runtime test uses it. This is a Phase-1 deliverable, not an afterthought — it is what makes the rest of the schedule achievable.

### 28.2 Test levels

| Level | Scope | Tools |
|---|---|---|
| Unit | Operators, expressions, layout, ring buffers | JUnit 5, AssertJ |
| Property-based | Pushdown equivalence, window boundaries, watermark monotonicity, serialisation round-trips, arena invariants | jqwik |
| Golden-plan | SQL → optimized plan text, checked in and diffed | Custom, Calcite-style |
| Differential | Generated code vs interpreted fallback, byte-identical output | `pravaha-testkit` |
| Integration | Real stores | Testcontainers (Aerospike, Cassandra, Kafka, Redis, PostgreSQL) |
| Chaos | Kill nodes/lanes mid-checkpoint, partition the network, stall a sink, corrupt an SST | Toxiproxy + custom fault injectors |
| Soak | 72 h at 70 % capacity; assert no leak in heap, direct memory, metaspace, file descriptors or RocksDB handles | Nightly CI |
| Benchmark | JMH micro + end-to-end load rig | JMH, HdrHistogram |
| Architecture | Module dependency rules, no storage clients in core, no unbounded collections on the runtime path, no `Serializable` | ArchUnit |

### 28.3 Correctness invariants asserted continuously

1. **Determinism:** identical input + identical watermarks ⇒ identical output, including row kinds and order within a key.
2. **Replay safety:** kill at any point, restore from checkpoint, replay ⇒ final sink state identical to the no-failure run (for exactly-once and effectively-once configurations).
3. **Watermark monotonicity:** a watermark never regresses on any channel.
4. **State boundedness:** with a bounded key space and finite lateness, state size converges.
5. **Pushdown equivalence:** for generated random predicates, engine-side and store-side filtering agree exactly.
6. **Codegen equivalence:** generated and interpreted paths agree exactly (§28.2).
7. **No silent drops:** `in = out + filtered + late_dropped + dlq` for every query, checked by an accounting invariant in the test harness.
8. **The incremental oracle:** for randomly generated queries `Q`, base relations `S` and change sets `ΔS`, assert `Q(S + ΔS) == Q(S) + Q^Δ(ΔS, S)`. This is the property that makes §9 shippable — a machine-checkable correctness statement over the *whole operator set*, generated rather than hand-written. Retract-stream engines have no equivalent; their correctness rests on the test cases someone thought to write. Runs on every commit and continuously in a nightly fuzzing job.
9. **Frontier consistency:** a `CONSISTENT` read across any set of views returns results reflecting exactly one common prefix of every shared input (§9.5).
10. **Backfill splice correctness:** for a generated history plus a concurrent change stream, `snapshot-then-splice` produces byte-identical final state to replaying the entire history through the CDC path (§16.1).

### 28.4 Benchmark profiles (the basis for §5.2)

| Profile | Query | Data |
|---|---|---|
| **A — filter + project** | `SELECT STREAM a,b,c FROM s WHERE status='COMPLETED' AND amount>100` | 12 fields, ~200 B, 10 % selectivity |
| **B — windowed aggregate** | 10 s tumbling `COUNT` + `SUM` + `AVG` grouped by user | 100 k distinct keys, Zipf s=1.1 (realistic skew, not uniform) |
| **C — temporal join + session** | Lookup join against a 10 M-row Aerospike set + 30 s session window | 90 % cache hit, 5 % out-of-order within 5 s |

| **D — view chain + serving** | 3 dependent views over Profile B's output, plus 50 k point lookups/s against the served view | Read/write mix; measures §17's serving path under concurrent ingest |
| **E — backfill** | Profile B registered against a store holding 3 years / 5 B rows | Measures §16.2 throttling and OLTP impact, not just raw speed |

Each profile runs at 1, 2, 4, 8, 16 lanes; reports throughput, latency percentiles, allocation rate, GC time and CPU per record. **Nightly CI fails on a > 10 % regression** against the recorded baseline — performance is treated as a test, not a hope.

#### Nexmark — the head-to-head benchmark (W5)

Profiles A–E are *our* benchmarks, which means a sceptical reader is entitled to discount them. **Nexmark** (q0–q22) is the streaming-SQL benchmark that Flink, RisingWave and Feldera all publish numbers against, and it is the only way to make a credible external claim.

- Nexmark runs in CI from **Phase 3 onward**, not once at the end — so queries where Pravaha is weak surface while there is still time to fix them (R16).
- Results are published **with the harness, the hardware specification, the configuration, and the losses** alongside the wins. This audience detects selective benchmarking immediately and punishes it far more than an honest loss.
- The release gate is §2.5's **W5**: parity or better on ≥ 18 of 22 queries, ≥ 2× on ≥ 8.
- Queries where the incremental model should dominate — q4, q5, q7, q15, q16, q20 (aggregations and joins over updating inputs) — are tracked as leading indicators of whether §9's bet is paying off.

A separate **TCO validation** (§30.2) runs the same workload on a like-for-like Flink deployment and measures actual vCPU, so W1 is a measurement rather than an argument.

### 28.5 Recovery benchmark

Measure restore time for 1 GB / 10 GB / 100 GB of state, cold and warm, to validate NFR-3's 30 s objective and to size the checkpoint interval.

---

## 29. Performance Budget Analysis

This section shows the NFRs are reachable, and where the headroom goes.

### 29.1 The per-record budget

At 3.0 GHz, **100 000 rec/s/core = 30 000 cycles/record**. Target profile B at 350 000 rec/s/core = **~8 600 cycles/record**. Approximate costs:

| Operation | Cycles | Notes |
|---|---|---|
| MPSC ring drain (amortised over a 512 batch) | ~15 | Array-backed, no allocation |
| Binary field read (fixed-width, L1-resident) | ~4 | Single load at a constant offset |
| UTF-8 slice compare, 9 bytes | ~15 | Vectorised `mismatch` |
| Hash of a 16-byte key | ~20 | xxHash3 / Agrona |
| L0 off-heap hash probe (cache hit) | ~60 | Open addressing, linear probe |
| L0 probe (cache miss, DRAM) | ~300 | The dominant real cost |
| `long` accumulate | ~1 | |
| Decimal128 add | ~10 | Two-limb, no `BigDecimal` |
| Timer wheel schedule | ~30 | O(1) |
| Output row write to arena | ~40 | Bump pointer + field stores |
| **Subtotal, profile B steady state** | **~500–900** | |
| **Headroom against the 8 600 target** | **~10×** | Absorbs cache misses, skew, branch mispredicts, occasional RocksDB spill |
| — for contrast — | | |
| `HashMap.get(String)` × 12 fields | ~600–2 000 | The draft's per-record field access |
| Allocate + GC a 450 B record graph | ~800–2 000 amortised | The draft's per-record allocation |
| `LinkedBlockingQueue.take()` | ~200–1 000 | Lock + condition + node allocation |
| RocksDB `get` via JNI (cache hit) | ~3 000–9 000 | |

The draft's design spends **3 000–12 000 cycles/record on overhead alone** before doing any query work. That is why G1/G2/G7 are marked critical: they are not stylistic preferences, they are the difference between meeting and missing the stated NFR by an order of magnitude.

### 29.2 Where the risk actually is

The engine's internal cost is, by design, not the bottleneck. The realistic limits are:

1. **Source ingest rate** — Aerospike XDR throughput, Kafka consumer decode, or scan load on the OLTP cluster. Usually the binding constraint.
2. **Sink write rate** — network round trips to Aerospike/Cassandra. Mitigated by batching, pipelining and async clients, but ultimately bounded by the store.
3. **Data skew** — a single hot key serialises onto one lane. Mitigated by two-phase local/global aggregation (§11.5) and, for extreme skew, key salting with a merge step.
4. **State that exceeds memory** — pushes work to RocksDB and then to disk; the cliff is real and is why capacity planning must be driven by `state_bytes` and `state_l0_hit_ratio`.
5. **GC and safepoints for p99.9** — addressed by near-zero allocation, ZGC, and fixed pre-touched heap.

Capacity planning guidance and a sizing calculator ship with the docs, keyed on these five.

---

## 30. Cost & TCO Model

**W1** claims Pravaha runs the same workload for ≤ 40 % of the compute. This section shows the arithmetic behind that claim and identifies which assumptions a customer should check on their own data.

### 30.1 Where the savings come from

| Lever | Mechanism | Typical effect |
|---|---|---|
| **Pushdown** (D-A) | A 90 %-selective `WHERE` runs in Aerospike, not the JVM. Ingest CPU, network bytes and decode cost all scale with what survives the filter, not with what exists. | **5–10×** less ingest work on selective queries |
| **Incremental computation** (D-B) | Work is proportional to the change; zero-delta pruning stops ~70 % of records within the first operator on Profile B | **2–5×** less downstream work on view chains |
| **No serving tier** (D-C) | The Redis/Aerospike serving cluster that exists only to hold Flink's output is not needed | **1 fewer system**, often 3–10 nodes |
| **No separate cluster** | No JobManager, no standby, no dedicated HA metadata store — the engine is 3 nodes total, or zero if embedded | **2–4 fewer nodes** |
| **No mandatory Kafka hop** | Sources are read natively; Kafka is used where it earns its place, not because the engine only speaks Kafka | Removes a whole cluster in ksqlDB comparisons |
| **Zero-allocation hot path** | GC work is a rounding error, so CPU is spent on the query | 10–20 % of CPU that Flink spends on GC |

### 30.2 Worked comparison — Profile B at 500 000 events/s

10-second tumbling aggregate over 100 k keys, 90 % selective filter, results queryable by an application.

| Component | Flink SQL on Kubernetes | Pravaha |
|---|---|---|
| Ingest | Full connector read: 500 k ev/s decoded in-JVM | Pushdown → 50 k ev/s reach the engine |
| Processing | 8 TaskManagers × 4 vCPU = **32 vCPU** | 3 nodes × 4 lane-cores = **12 vCPU** |
| Coordination | JobManager + standby = **4 vCPU** | Embedded Raft on the same 3 nodes = **0** |
| HA metadata | ZooKeeper 3 × 2 vCPU = **6 vCPU** | Included = **0** |
| Serving tier | Redis 3 × 4 vCPU = **12 vCPU** | Served from state = **0** |
| Checkpoint storage | S3 / HDFS | S3 or the existing Aerospike cluster |
| **Total compute** | **54 vCPU** | **12 vCPU** |
| **Ratio** | 1.00× | **0.22×** |
| Systems to operate | Flink + ZK + Redis (+ Kafka) | Pravaha |
| On-call surface | 3–4 systems | 1 |

At a representative $0.04/vCPU-hour this is roughly **$18.9 k/year → $4.2 k/year** in compute. The compute line is usually the smaller half of the saving; the larger half is the two systems nobody has to operate, patch, upgrade or be paged for.

### 30.3 Honest qualifications

A cost claim is only credible if it says when it fails to hold.

- **Pushdown is the dominant lever, and it depends on the query.** A `SELECT *` with no predicate pushes nothing; the advantage narrows to roughly 2× (incremental computation, no serving tier, no separate cluster). The 5× cases are selective predicates on indexed bins — common, but not universal. Customers should measure with *their* predicates.
- **Aerospike Enterprise/XDR licensing** is a real cost for the flagship ingest path (R1) and is not included above. On Community Edition the `lut-scan` strategy adds read load to the storage cluster, which shifts cost rather than removing it.
- **Very large state** (> 100 GB/node) pushes work into RocksDB and onto disk; the advantage narrows and NVMe becomes a line item.
- **Flink's ecosystem has value** that does not appear on a vCPU bill: connectors, existing expertise, existing operational tooling. A migration has a real one-time cost.

### 30.4 Licensing and commercial posture

- **Apache 2.0 core.** The full engine, all SPIs, the Aerospike/Kafka/JDBC plugins, the CLI and the UI. The moat is architecture and execution quality, not a crippled open edition — a restricted core would cost more adoption than it protects.
- **Commercial value sits above the engine:** managed/cloud operation, enterprise connectors, multi-region coordination, long-term support, certification, and the plugin TCK certification programme.
- **No telemetry by default**, ever. Opt-in only, documented, and inspectable.

This posture matters strategically: the adopters who will make this product succeed are engineers who will not evaluate a closed core, and the competitors most vulnerable to displacement are the ones charging cloud-only prices for what should be a library.

---

## 31. Delivery Roadmap

Ten phases, roughly two-week increments for a team of 4–6. Each phase ends with something demonstrable and benchmarked. The added scope over a conventional streaming engine (§9, §16, §17, §18, §24) is what §2.5's win conditions require, and it is sequenced so that each differentiator arrives with the machinery it depends on.

| Phase | Weeks | Deliverable | Exit criteria |
|---|---|---|---|
| **0 — Foundations** | 1–2 | Maven reactor, `pravaha-api`, binary layout + arena (with `weight`/`logicalTime` header), ring buffers, `pravaha-testkit` with virtual clock, CI (build, spotless, ArchUnit, JMH baseline) | Deterministic harness runs a trivial pipeline; JMH baselines recorded |
| **1 — Minimal vertical slice** | 3–6 | Calcite parse/validate/plan; **Z-set algebra + linear incremental operators** (filter/project/union); interpreted execution; filesystem + Kafka plugins; single lane; embedded mode; `pravaha dev` | `SELECT STREAM … WHERE …` end to end; **property oracle `Q(S+ΔS) = Q(S)+Q^Δ(ΔS,S)` green** for linear operators; `pravaha dev` starts in < 1 s |
| **2 — Performance core** | 7–11 | Codegen + whole-stage fusion + Janino; multi-lane partitioning; MPSC exchange; wait strategies; adaptive batching; backpressure | **Profile A ≥ 1.2 M rec/s/lane**; differential tests green; ≥ 90 % linear scaling to 8 lanes |
| **3 — Stateful & incremental** | 12–18 | Watermarks + idle detection; tumbling/hopping/session windows with slicing; timer wheel; L0 off-heap state; RocksDB tier; **incremental aggregates, `DISTINCT`, bounded-state enforcement**; changelog derivation; emit modes; late data + DLQ | **Profile B ≥ 350 k rec/s/lane**; correctness invariants 1–8 green; unbounded queries rejected with a useful diagnostic |
| **4 — Aerospike, joins & durability** | 19–25 | Aerospike plugin (all four strategies), expression pushdown, idempotent sink, lookup join; **bilinear incremental joins**; checkpointing + recovery; capability negotiation | Exactly-once state proven by chaos test; **Profile C ≥ 120 k rec/s/lane**; pushdown equivalence green; **W4 ≥ 5× fewer bytes ingested** |
| **5 — Backfill & serving** | 26–32 | Consistent snapshot→CDC splice; throttled adaptive backfill; blue/green query update; **served materialized views** with all four consistency modes; read replicas & read admission control | 3 years of history backfilled with OLTP p99 impact < 10 %; **W3: p99 point lookup ≤ 200 µs**; zero-downtime SQL change demonstrated |
| **6 — Gateways, clients & DX** | 33–38 | gRPC streaming + Arrow + credit flow control; Avatica control plane; catalog-typed Java/Python/Go clients; **`pravaha-spring-boot-starter` with `@PravahaListener` and `@PravahaTest`**; full CLI; stable error-code catalogue; docs-as-tests; plugin TCK | Python client sustains 1 M rows/s; Avatica works from DBeaver; **W2: deploy ≤ 2 s**; TCK passes for all first-party plugins; starter verified against Spring Boot 3.2–3.5 |
| **7 — Cluster & HA** | 39–45 | Ratis metadata, membership, assignment, rebalance, failover, savepoints, multi-tenancy quotas; elastic lane rescaling | 3-node cluster survives rolling node kills with no data loss; rebalance ≤ 5 s pause; **W7: 10 GB restore ≤ 30 s** |
| **8 — Debugger, self-tuning & console polish** | 46–53 | **Time-travel debugger UI**, adaptive-controller screens, security (OIDC/RBAC/audit) end to end, full observability, skew remediation, live replanning, state tier promotion, **console polish pass + WCAG 2.2 AA audit + visual-regression baseline**, Helm chart | Operator runs the full lifecycle from the console; **W10:** a seeded production bug is found by replay and exported as a passing JUnit fixture; §23.20 checklist green |
| **9 — Benchmarks, breadth & GA** | 54–62 | Cassandra + PostgreSQL + Redis plugins; **`WITH RECURSIVE`**; **published Nexmark q0–q22 head-to-head vs Flink**; 72 h soak; security review; TCO validation; GA docs and migration tooling | All NFR SLOs met; **W5 ≥ parity on 18/22, ≥ 2× on 8**; **W6** recursive query runs; **W1 ≤ 40 % vCPU** validated on a real workload; SBOM + security review signed off |

**The console is a continuous workstream, not a phase.** §23 specifies a product surface, and a product surface cannot be built in one late phase. From Phase 3 onward a dedicated frontend workstream ships the console screens for each engine capability *in the same phase that capability lands* — catalog and query screens with E3, plan DAG and workbench with E4, backfill and cutover with E5, and so on. Phase 8 is then a *polish and debugger* phase rather than a build-the-whole-UI phase. See §6.2 and §9 of the implementation plan.

**Critical path:** Phase 1's Z-set foundation and Phase 2's codegen. Everything incremental depends on the first; every performance claim depends on the second. Phase 5 (backfill + serving) is the largest single differentiator and the most likely to need a full extra iteration — plan for it.

**Earliest defensible demo:** end of Phase 5 (~week 32). At that point the product does something no competitor does: computes incrementally over Aerospike with pushdown, backfills three years of history safely, and answers point queries about the result in microseconds — with no other system involved.

**Sequencing note.** Phases 6 and 8 can overlap with 7 given a team of six; phases 3, 4 and 5 are strictly sequential because each depends on the previous one's state machinery.

---

## 32. Risk Register

| # | Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|---|
| R1 | **Aerospike CE has no change feed** — customers on CE cannot get low-latency, exactly-once ingest | High | **Certain** | Four documented strategies (§19.1) with explicit capability declaration; `lut-scan` for CE; make the Enterprise/XDR dependency a stated prerequisite for the flagship guarantee, in sales material and docs, from day one |
| R2 | **Codegen complexity** — generated code is hard to debug and a rich source of subtle bugs | High | Medium | Interpreted fallback for every operator; differential testing in CI; retained generated source; method splitting; strict template review |
| R3 | **Calcite learning curve & version churn** — the planner API is large and moves | Medium | High | Isolate all Calcite usage inside `pravaha-sql`; golden-plan tests catch behaviour changes on upgrade; pin the version in the BOM |
| R4 | **RocksDB native memory** — off-heap growth, handle leaks, container OOM-kills | High | Medium | Shared block cache + write-buffer manager; `NativeResourceRegistry` with deterministic close; soak test asserts stable RSS; expose `rocksdb_*` metrics with alerts |
| R5 | **Exactly-once claim over-promised** | High | Medium | Compute and display the **weakest-link** guarantee per query (§14.4); never assert exactly-once when the source or sink cannot support it |
| R6 | **Data skew** collapses parallelism on hot keys | Medium | High | Two-phase local/global aggregation; skew detection metric; key-salting escape hatch documented |
| R7 | **Performance regressions** creep in over a long project | Medium | High | Nightly JMH + end-to-end profiles with a 10 % failure threshold; performance treated as a test |
| R8 | **Scope creep toward "rebuild Flink"** | High | Medium | Hold the line on the differentiator: *embeddable, store-native, pushdown-first*. Explicitly out of scope for v1: batch, ML, arbitrary UDF sandboxing, SQL/CEP beyond `MATCH_RECOGNIZE` |
| R9 | **Plugin classloader issues** — leaks, TCCL bugs, version conflicts | Medium | Medium | Strict parent-last policy; classloader leak test; shading in plugin jars; a plugin conformance TCK plugin authors must pass |
| R10 | **JDK adoption friction** — an embeddable library inherits its host's JVM, and enterprise Java is largely on 17/21 | Medium | ~~Medium~~ **Low** | **Resolved by revising the baseline to Java 21** (§4.5). `pravaha-api` targets 17. Residual risk is `sun.misc.Unsafe` removal on future JDKs, contained by the `MemoryAccess` abstraction with an FFM implementation already written and CI-tested (§4.6) |
| R11 | **UI accidentally becomes a data path** under feature pressure | Medium | Medium | Architectural rule (§23.1) + ArchUnit test forbidding UI modules from depending on `pravaha-runtime` internals; conflating tap by construction |
| R12 | **Metaspace growth** from per-query generated classes | Medium | Low | Per-query classloaders; register/drop leak test; metaspace metric as a canary; hard cap on generated class count per tenant |
| **R13** | **DBSP/Z-set incrementalization is a young technique** with essentially one production implementation. Getting an operator's incremental form subtly wrong produces silently wrong answers | **High** | Medium | The property-based oracle (§9.7) checks `Q(S+ΔS) = Q(S) + Q^Δ(ΔS,S)` over *generated* queries and deltas continuously in CI — a machine-checkable correctness proof that retract-stream engines cannot have. Phased adoption: linear ops in Phase 1, aggregates in 3, joins in 4, recursion in 9. Non-incremental escape hatch per operator |
| **R14** | **Scope is now materially larger** — serving layer, backfill, debugger and self-tuning are each a product in their own right. Classic cause of a 62-week plan becoming 100 weeks | **High** | **High** | Every phase has a hard exit criterion and ships something demonstrable. Phase 5 is explicitly flagged as most likely to slip. If the schedule compresses, cut in this order: recursion (§9.3), live replanning (§18.5), migration tooling (§24.6) — never the correctness oracle, the bounded-state enforcement, or the benchmarks |
| **R15** | **Serving from lane-local state couples read availability to engine availability** — a restart makes results unreadable | Medium | Medium | `MEMORY+SINK` is the **default** posture (§17.4): results also live in the customer's own Aerospike set and stay readable by any client while Pravaha is down or recovering. Read replicas cover node-level failure. `MEMORY`-only is opt-in |
| **R16** | **Publishing head-to-head Nexmark results is a public commitment** — losing badly on some queries is a credibility problem | Medium | Medium | Run Nexmark continuously from Phase 3, not once at the end, so weak queries are found while there is time to fix them. Publish the harness and the losses alongside the wins; selective benchmarking is detected and punished by this audience far more harshly than an honest loss |

---

## 33. Architecture Decision Records

Condensed ADRs; each will be expanded in `docs/adr/` with full context and consequences.

| ADR | Decision | Alternatives rejected | Why |
|---|---|---|---|
| **001** | Java for everything, **baseline Java 21 LTS** (`pravaha-api` at 17), 25 supported; Scala only in optional non-hot-path client modules | Java 25 baseline (v2.0 of this doc); Java 17; Scala 3 core; Kotlin; mixed | Allocation control, ecosystem fit, Janino codegen, Spring, hiring (§4). **Baseline revised from 25 to 21:** an embeddable library inherits its host's JVM, so demanding 25 forfeits the embeddability moat (§2.2) for features that are convenience, not capability (§4.6) |
| **002** | Calcite as compiler, custom runtime | Calcite `Enumerable` execution; Flink embedding; hand-written parser | Enumerable cannot meet the NFRs; Flink violates the embeddable/lightweight premise; a hand-written parser throws away Calcite's optimizer (§11, G1) |
| **003** | Binary flyweight rows over an arena | `Map<String,Object>`; POJOs + reflection; Arrow internally | ~10× lower per-record cost; Arrow retained as the *wire* format where its columnar layout pays (§8, §20.2) |
| **004** | Partitioned lanes, single-writer | Shared thread pool + concurrent state; actor framework | Removes lock contention entirely; makes state ownership and checkpointing tractable (§13) |
| **005** | Whole-stage codegen with interpreted fallback | Interpretation only; bytecode generation via ASM | 5–10× on the hot path; Janino compiles Java source in ~10 ms with far better debuggability than raw ASM (§12) |
| **006** | Tiered state (off-heap → RocksDB → durable store) | RocksDB only; heap only | RocksDB costs 1–3 µs/op; most window state fits in memory; large state still needs spill (§14) |
| **007** | gRPC + Arrow for streaming; Avatica for control plane | Avatica only; WebSocket only; custom TCP | Avatica has no push; Arrow gives zero-copy polyglot clients; WebSocket kept for browsers (§20) |
| **008** | Aligned checkpoints; exactly-once state, effectively-once output | Unaligned only; no checkpoints; per-record acks | Matches proven practice; honest about what sinks can guarantee (§14.4) |
| **009** | Embedded Raft (Ratis) for metadata | Store-backed CAS lease; ZooKeeper mandatory; gossip only | Assignment correctness needs real consensus; embedded avoids a mandatory external dependency; ZK/etcd remain pluggable (§21.2) |
| **010** | Plugins in isolated parent-last classloaders | Flat classpath; JPMS modules; OSGi | Solves real dependency conflicts; JPMS is too rigid for dynamic loading; OSGi is disproportionate (§10.3) |
| **011** | UI strictly out of the data path, on a conflating tap | UI subscribes as a normal sink | A slow browser must never affect a production query (§23.1) |
| **012** | Nanosecond `long` timestamps | `Instant`; millis; 96-bit | No allocation, adequate range to 2262, 2× cheaper than 96-bit (§15.1) |
| **013** | Z-sets + DBSP-derived incremental operators as the execution algebra | Flink-style hand-written retract streams; full recomputation; micro-batching | Correctness composes instead of being re-established per operator; work ∝ change; unlocks recursion; yields a machine-checkable correctness oracle (§9) |
| **014** | Serve maintained views from lane-local state, with `MEMORY+SINK` as the default posture | Sink-only (Flink); serve-only (Materialize) | Removes an entire serving tier and its latency, without trapping the customer's results inside our engine (§17) |
| **015** | Buffer-CDC-first, then snapshot, then splice with a bounded dedupe window | Snapshot-then-subscribe; lock the table; dual-pipeline by hand | The only ordering with no gap and a *finite, known* overlap; correct by construction under Z-set consolidation (§16.1) |
| **016** | Blue/green shadow deployment for every query change | Stop-and-restart (Flink); in-place mutation | Zero downtime, state preserved, instant rollback; also the substrate for live replanning and version upgrades (§16.3) |
| **017** | Adapt performance automatically; never adapt semantics | Full manual tuning; adapt everything | Operations is where streaming projects die, but silent semantic changes are unacceptable. Every controller is observable, bounded, reversible and pinnable (§18.1) |
| **019** | Engine core is Spring-free; Spring Boot is a bootstrap layer above a plain-Java `PravahaEngine` seam | Spring throughout; no Spring anywhere; Quarkus/Micronaut | Keeps embeddability intact (a host on Boot 3.2 cannot be forced to 3.5), keeps `pravaha dev` under 1 s, and keeps proxies off the hot path — while the server still inherits Boot's config, actuator, security and packaging for free (§22.1) |
| **020** | Ship a `pravaha-spring-boot-starter` with `@PravahaListener` and `PravahaTemplate` | Documentation only; a bare `PravahaEngine` bean | Lets a team add continuous SQL to a service they already run, in the idiom they already use. Modelled on `@KafkaListener` so the mental model transfers (§22.4) |
| **022** | The console is a flagship product surface with its own design system, built as a continuous workstream from Phase 3 | A late control-plane admin UI; CLI-only; a thin metrics page | For most users the console *is* the product, and W10 (the time-travel debugger) exists nowhere else. A polished UI cannot be produced in one late phase, so it is resourced with a dedicated frontend engineer and shipped alongside each engine capability (§23.1) |
| **021** | No GraalVM native image for the engine | Native image via Spring AOT; drop runtime codegen to enable it | Runtime Java-source compilation (ADR-005) is fundamentally incompatible with a closed-world image, and it is what makes the hot path fast. Stated so no one spends a sprint on it. Clients and UI may still go native (§22.7) |
| **018** | Apache 2.0 for the entire engine, UI and first-party plugins | Open core with a restricted engine; source-available; dual licence | The engineers who decide adoption will not evaluate a crippled core. The moat is architecture and execution; commercial value sits in managed operation, support and certification (§30.4) |

---

## Appendix A — Summary of Changes from SRS 1.0

### A.1 Engineering corrections

| Area | 1.0 Draft | This design |
|---|---|---|
| Language | "Java or Scala or mixed" | Java 21 LTS baseline, single language; Scala confined to optional client modules |
| Execution | Calcite `Enumerable` + blocking enumerator | Calcite plans; generated fused operators execute |
| Record model | `Map<String,Object>`, `Serializable` | Binary flyweight over an arena; map form kept for SPI ergonomics |
| Queueing | One global `LinkedBlockingQueue` | Partitioned lanes, MPSC/SPSC ring buffers, single writer |
| State | RocksDB for everything | Tiered: off-heap → RocksDB → durable store, pluggable |
| Client protocol | Avatica polling | gRPC + Arrow push; Avatica for control plane |
| Aerospike ingest | Assumed "CDC" | Four explicit strategies with declared capabilities |
| Guarantees | "Exactly-once" | Weakest-link computation: exactly-once state, effectively-once output |
| Time | `rowtime` undefined | Event time, watermarks, idle detection, allowed lateness, late side-output |
| Updates | Not addressed | Signed Z-set weights internally; `RowKind` derived at the sink with negotiated emit modes |
| Scope | One query per node, YAML-bound | Many queries, lifecycle state machine, versioning, multi-tenancy |
| Cluster | `instance_id` only | Virtual partitions, Raft metadata, rebalance, failover |
| NFRs | Contradictory, unfalsifiable | Per-profile SLO table with a committed measurement harness |
| Missing | — | Security, schema evolution, DLQ, backpressure, observability, testing strategy |

### A.2 Product additions — what makes it competitive rather than merely correct

| Capability | 1.0 Draft | This design | Beats |
|---|---|---|---|
| **Competitive position** | Implicit | Explicit four-way moat + 10 measurable win conditions (§2) | — |
| **Computation model** | Recompute per record | Z-sets + DBSP incremental operators; work ∝ change (§9) | Flink, ksqlDB |
| **Recursive SQL** | Absent | `WITH RECURSIVE` incrementalized — fraud rings, hierarchies, reachability (§9.3) | Flink cannot express this |
| **Cross-view consistency** | Absent | Frontier-based; all views agree on the same input prefix (§9.5) | Flink, ksqlDB |
| **Serving** | Sink only | Maintained views queryable in ~10–50 µs, four consistency modes, staleness reported (§17) | Flink; removes a whole serving tier |
| **Backfill / bootstrap** | Absent | Consistent snapshot→CDC splice, adaptive throttling, restartable (§16.1–16.2) | Everyone — this is where projects stall |
| **Query updates** | Restart | Blue/green shadow cutover, zero downtime, instant rollback (§16.3) | Flink, ksqlDB |
| **Debugging** | Absent | Time-travel debugger; incidents export as JUnit fixtures (§16.4) | No competitor ships this |
| **Self-tuning** | Absent | Adaptive batching, per-key skew remediation, elastic rescale, measured replanning (§18) | Flink's tuning burden is its top complaint |
| **Developer loop** | Absent | `pravaha dev` < 1 s, validate < 50 ms, deploy < 2 s, deterministic unit tests (§24) | Flink's 30–120 s submit |
| **Error messages** | Absent | Stable codes, named construct, concrete fixes (§24.4) | All |
| **Cost model** | Absent | Worked TCO with stated qualifications; ≤ 40 % of Flink's vCPU (§30) | — |
| **Benchmarks** | Unfalsifiable NFRs | Nexmark q0–q22 published head-to-head, harness open (§28.4, §31 Phase 9) | Establishes credibility rather than asserting it |
| **Deployment modes** | Single engine instance, YAML-bound | Four modes from one core: plain embedded, Spring Boot starter, Spring Boot server, all-in-one server+UI (§22.2) | Flink (cluster-only), Materialize/RisingWave (cloud-only) |
| **Web console** | Absent | Full web application: IDE-grade SQL workbench, live plan DAG, time-travel debugger, backfill control, design system, WCAG 2.2 AA, performance-budgeted (§23) | Flink's UI is read-only job status; ksqlDB has none of consequence; Materialize is SQL-console-only |
| **Spring integration** | Absent | `@PravahaListener`, `PravahaTemplate`, `@PravahaTest`, actuator — engine core stays Spring-free (§22.4) | No competitor ships a first-class Spring starter |
| **Licensing** | Unstated | Apache 2.0 core, engine and UI included (§30.4) | Materialize, RisingWave, Confluent |

## Appendix B — Immediate Next Steps

**Decisions needed before Phase 0 starts** — each changes what gets built:

1. **Language and platform** (§4) — Java, single language, **baseline Java 21 LTS** with 25 supported and CI-tested. Everything else depends on it. *Recommendation: approve as written. No JDK upgrade is needed to begin — 21 is already installed.*
2. **The DBSP bet** (§9, R13). This is the highest-upside and highest-uncertainty decision in the document. Approving it buys the incremental moat, recursion and the correctness oracle; declining it means a competent Flink alternative without a durable differentiator. *Recommendation: approve, with the Phase 1 property oracle as the gate — if the oracle is hard to build, that is the early warning signal, and it arrives in week 6 rather than week 40.*
3. **Aerospike edition** available to the target deployment (§19.1, R1). This determines whether the flagship exactly-once ingest path is available at all, and is the highest-value *external* open question. Ask this first; it has a procurement lead time.
4. **Scope versus schedule** (R14). The plan is 62 weeks for a team of 4–6. If that is not acceptable, decide the cut *now* using the order in R14, rather than discovering it in month nine.
5. **Which stores ship in v1** beyond Aerospike, and which wait for Phase 9.

**Contracts to approve:**

6. The SLO table (§5.2) as the engineering acceptance contract.
7. The win conditions (§2.5) as the product acceptance contract — particularly W5, since publishing Nexmark results is a public commitment (R16).

**Then:**

8. Scaffold Phase 0 — the Maven reactor, `pravaha-api`, the binary row layout with the Z-set header, and `pravaha-testkit` with its virtual clock. The testkit is not infrastructure overhead; it is what makes every subsequent phase's exit criteria checkable, and it is why it ships first.


---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. Licensed under the Apache License, Version 2.0.
This document is part of the Pravaha project and is distributed under the same terms; see `LICENSE` and `NOTICE`.
Provided "as is", without warranties or conditions of any kind.</sub>
