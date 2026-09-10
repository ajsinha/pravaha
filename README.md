<div align="center">

<img src="brand/mark.svg" alt="" width="88" height="88">

# प्रवाह · Pravaha

### Ask once. Answer always.

**An embeddable, store-native, incrementally-maintained continuous query engine.**

*Pravaha* (Sanskrit: *continuous, uninterrupted flow*) · pronounced *pruh-VAA-huh*

[![Status](https://img.shields.io/badge/status-design%20phase-blue)](docs/system_design.md)
[![Java](https://img.shields.io/badge/Java-21%20LTS-orange)](docs/system_design.md#4-language-decision-java-vs-scala)
[![Build](https://img.shields.io/badge/build-Maven-C71A36)](docs/implementation_plan.md)
[![License](https://img.shields.io/badge/license-Proprietary-red)](LICENSE)

</div>

---

> **Project status: Wave 7 of 10 — an engine with a client protocol; no UI, no clustering.**
>
> Waves 1–7 are merged to `main` (see [`docs/gates/wave-7`](docs/gates/wave-7/), which records why
> that merge happened without a passing performance gate). **SQL runs end to end today**, now
> across the lane runtime: Calcite parses and optimises, the plan becomes Pravaha's own operator
> tree, and rows travel from a plugin reader through an ingest pump into a lane's off-heap inbox and
> out to a sink. Try it in [`docs/QUICKSTART.md`](docs/QUICKSTART.md).
>
> Wave 3 added whole-stage code generation — roughly **10× the interpreted path** — the lane model,
> the hash exchange between lanes, backpressure that reaches the source plugin, and adaptive
> batching. Wave 4 added **windowed `GROUP BY`** end to end, watermarks with idle detection, window
> slicing, session windows, late-data correction by retraction, a dead-letter queue, changelog
> negotiation and the L0 state map.
>
> **Gates P2 and P3 are both blocked on the same thing, and it is not code.** The throughput and
> scaling figures need 16 physical homogeneous cores; the development machine is a 12-core
> heterogeneous laptop part. The evidence packs in [`docs/gates`](docs/gates/) say exactly what is
> and is not measurable, and no number from this machine is quoted as if it were.
>
> Checkpointing and joins (Wave 5), backfill and the serving layer (Wave 6), and the Flight SQL
> gateway with both SDKs, authentication, authorization and prepared statements (Wave 7) have all
> landed. The README's query runs verbatim against a real Aerospike, and there is a test that proves
> it rather than a claim that asserts it.
>
> **What is not built, stated plainly:** there is no way yet to register a query as a persistent
> running thing, no subscriptions, **no user interface** (three ADRs, no code), and no clustering.
> That is roughly wave 7 of 10. The Aerospike edition question in
> [Appendix B](docs/system_design.md#appendix-b--immediate-next-steps) has procurement lead time and
> is worth settling early.

---

## What it is

Pravaha runs continuous SQL over your existing databases — Aerospike first, then
Cassandra/ScyllaDB, Kafka, Redis and PostgreSQL — and keeps the answers up to date as the data
changes. It then **serves those answers back** at microsecond latency, so you do not need a
second database to hold the results.

```sql
CREATE CONTINUOUS QUERY q_user_volume
INTO   user_volume_agg
SERVE  AS VIEW user_volume INDEXED BY (user_id)
AS
SELECT STREAM
    TUMBLE_END(event_time, INTERVAL '10' SECOND) AS window_end,
    t.user_id, p.tier,
    COUNT(*)      AS txn_count,
    SUM(t.amount) AS total_volume
FROM  txn_stream AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
       ON t.user_id = p.user_id
WHERE t.status = 'COMPLETED'
GROUP BY TUMBLE(t.event_time, INTERVAL '10' SECOND), t.user_id, p.tier
EMIT CHANGES;
```

That query is not an aspiration. Everything from `SELECT STREAM` down -- the tumbling window, the
temporal lookup join, the filter, the aggregates -- parses, plans and **runs against a real Aerospike
server** in `AerospikeContinuousQueryIT`, with the `WHERE` clause evaluated inside Aerospike rather
than after the read, and the answer read back by key without a second system in the call.

What is not yet parsed is the statement *around* it: `CREATE CONTINUOUS QUERY`, `INTO`,
`SERVE AS VIEW` and `EMIT CHANGES` are registration and lifecycle (design section 11.2), and the
engine is driven through its API until they exist. `SELECT STREAM` is accepted and redundant -- every
Pravaha query is continuous, so there is no non-streaming mode to distinguish it from.

```java
// …and read the answer, from the same system, in microseconds
BigDecimal volume = pravaha.view("user_volume")
                          .consistency(Consistency.CONSISTENT)
                          .get("user_42")
                          .map(r -> r.getDecimal("total_volume"))
                          .orElse(BigDecimal.ZERO);
```

## Why it exists

Streaming SQL engines make you choose. Flink needs its own cluster, reads everything through
deliberately dumb connectors, and cannot answer a question about its own output. Materialize and
RisingWave are incremental and can serve queries, but they are cloud databases you move data
*into* — with no path to Aerospike or Cassandra. Hazelcast Jet embeds, but has neither real SQL
nor incremental maintenance.

**Pravaha is the only engine designed to hold all four properties at once:**

|  | What it means |
|---|---|
| **Embeddable** | A library in your Spring Boot service, or a clustered server. Same engine, same code paths. |
| **Store-native** | Filters, projections and partial aggregates are pushed *into* Aerospike and Cassandra. Move 10× fewer bytes. |
| **Incremental** | Z-sets and DBSP-derived operators: work is proportional to what changed, not to how much data exists. Recursive SQL becomes expressible. |
| **Serving** | The maintained view *is* an indexed table in memory. Point lookups in ~10–50 µs, with declared consistency and reported staleness. |

Full competitive analysis, including the ten measurable claims this has to satisfy:
[design §2](docs/system_design.md#2-competitive-landscape--winning-strategy).

## Design highlights

| | |
|---|---|
| **Language** | Java 21 LTS, single language. Calcite plans; generated fused operators execute. [Why not Scala →](docs/system_design.md#4-language-decision-java-vs-scala) |
| **Execution** | Whole-stage code generation (Janino) over binary flyweight rows in off-heap arenas. No `Map<String,Object>`, no boxing, no allocation on the hot path. |
| **Concurrency** | Partitioned lanes, single-writer principle. One thread, one ring buffer, one state slice, one timer wheel per lane. No locks in steady state. |
| **State** | Tiered — off-heap hash arena → RocksDB → durable checkpoint store. RocksDB is a tier, not the whole stack. |
| **Correctness** | Exactly-once state via aligned checkpoints; effectively-once output via idempotent sinks. The engine computes and reports the **weakest link** per query rather than over-promising. |
| **Operations** | Adaptive batching, automatic per-key skew remediation, elastic rescaling, blue/green query updates, and a **time-travel debugger** that turns a production incident into a JUnit fixture. |

## Shape

Two processes, on purpose.

```
   pravaha-server  (Java 21)            Pravaha Console  (Python)
   engine + public REST API      ◄───   FastAPI, built on the pravaha SDK
   /status  — plain HTML, works
   when the console is down
```

The console is a **separate runtime** so the API boundary cannot be violated: a test enforcing
"the console may only use the public API" can be waived under deadline pressure, and a Python
process simply cannot reach into a Java engine. It also makes the console the first real consumer of
the published SDK — a proof of the integration story rather than an assertion (ADR-024).

The engine itself also embeds. One core, several ways to run it:

| Mode | Artifact | Spring | Use |
|---|---|---|---|
| **A** Plain embedded | `pravaha-embedded` | none | Any Java app; unit tests; the CLI |
| **B** Spring-embedded | `pravaha-spring-boot-starter` | auto-config into *your* app | Add continuous SQL to a service you already run |
| **C** Server | `pravaha-server` | it *is* a Spring Boot app | Standard production deployment |

```java
@Service
class FraudService {

    @PravahaListener(query = "high-value-orders", concurrency = 4)
    void onHighValueOrder(HighValueOrder order) {     // typed from the query's schema
        riskEngine.evaluate(order);
    }
}
```

The engine core contains **no Spring** — it sits behind a plain-Java `PravahaEngine` seam, so
embedding Pravaha never dictates your Spring version.
[Details →](docs/system_design.md#22-deployment-modes--spring-boot-integration)

## The console

A full web application, not an admin page: an IDE-grade SQL workbench with catalog-aware completion
and sub-50 ms validation, a live plan DAG with per-operator telemetry, backfill and blue/green
cutover control, and a time-travel debugger that rewinds a running query and steps it forward under
inspection.

Built as a **Python FastAPI application on the published SDK**, with server-rendered templates for
the shell and public pages, and interactive islands for the workbench and debugger. Everything
vendored — no CDN, because air-gapped deployment is a precondition, not a nicety. WCAG 2.2 AA, with
performance budgets gated in CI.

Architecturally out of the data path and experientially the centre of the product.
[Specification →](docs/system_design.md#23-the-pravaha-console--web-ui)

## Documentation

| Document | What's in it |
|---|---|
| **[Quickstart](docs/QUICKSTART.md)** | Build it and run a query. Ten minutes. |
| **[Architecture](docs/ARCHITECTURE.md)** | The shape in two pages, before the 3 000-line version |
| **[What SQL it runs](docs/SQL_SUPPORT.md)** | Every construct that works and every one that does not, with the reason. Checked by a test, so it cannot rot |
| **[System Design](docs/system_design.md)** | Full architecture and competitive position, 33 sections |
| **[Implementation Plan](docs/implementation_plan.md)** | Waves, epics, staffing, risks, descope ladder |
| [Decision records](docs/adr/) | Every architectural decision and why, including the ones later reversed |
| [Examples](examples/) | Runnable, and executed by the build so they cannot rot |
| [Handover](docs/HANDOVER.md) | Current state, working practices, and what to pick up next |
| [Original SRS](docs/initial_req.md) | The 1.0-DRAFT this design supersedes. Kept for provenance. |

Good entry points:

- **What is this and why would I use it?** — design [§1](docs/system_design.md#1-executive-summary--key-recommendations), [§2](docs/system_design.md#2-competitive-landscape--winning-strategy)
- **Is the engineering sound?** — design [§3 (gap analysis)](docs/system_design.md#3-gap-analysis-of-the-10-draft), [§9 (incremental core)](docs/system_design.md#9-the-incremental-computation-core), [§29 (performance budget)](docs/system_design.md#29-performance-budget-analysis)
- **What does it cost to run?** — design [§30](docs/system_design.md#30-cost--tco-model)
- **When can I have it?** — [implementation plan §13](docs/implementation_plan.md)

## Project coordinates

```xml
<groupId>com.ash.messaging</groupId>
<artifactId>pravaha</artifactId>
<version>0.1.0-SNAPSHOT</version>
```

Base package `com.ash.messaging.pravaha`. Requires **JDK 21+**; builds with the vendored Maven
wrapper.

```bash
./mvnw clean verify                                  # full build
./mvnw -T1C -DskipITs -Dbenchmarks.skip=true test    # fast inner loop
./mvnw -Pall verify                                  # everything, as CI runs it
```

No system Maven needed — the wrapper is vendored. See
[implementation plan §2](docs/implementation_plan.md) for the toolchain and build profiles, and
[`docs/QUICKSTART.md`](docs/QUICKSTART.md) to actually run something.

Modules currently built: `pravaha-api`, `pravaha-common`, `pravaha-algebra`, `pravaha-runtime`,
`pravaha-codegen`, `pravaha-sql`, `pravaha-connect`, `pravaha-embedded`, `pravaha-server`,
`pravaha-cli`, `pravaha-testkit`, plus [`plugins/pravaha-plugin-filesystem`](plugins/pravaha-plugin-filesystem)
and the SDKs in [`sdk/pravaha-sdk-java`](sdk/pravaha-sdk-java) and
[`sdk/pravaha-sdk-python`](sdk/pravaha-sdk-python).

## Roadmap

| Wave | Weeks | Milestone | |
|---|---|---|---|
| 1 | 1–2 | Foundations; deterministic harness | ✅ `M1` |
| 2 | 3–5 | Vertical slice; **go/no-go on the incremental core** | ✅ `M2` |
| 3 | 6–11 | Codegen, lanes, exchange — Profile A ≥ 1.2 M rec/s/lane | in progress |
| 4–5 | 12–25 | Windows, tiered state, Aerospike, joins, exactly-once checkpointing | |
| 6 | 26–32 | Backfill, blue/green, serving layer — **first defensible demo** | |
| 7–8 | 33–45 | Gateways, SDKs, console, cluster and HA | |
| 9–10 | 46–62 | Time-travel debugger, Nexmark published head-to-head, **GA** | |

[Full roadmap with acceptance gates →](docs/system_design.md#31-delivery-roadmap)

## Contributing

Not yet open for outside contributions while the foundations settle. The standards that will apply
are already in force and enforced by the build — see
[implementation plan §3–§4](docs/implementation_plan.md) for the definition of done, the branching
model, and the rules the build checks mechanically:

- source files stay under 1500 lines (`SourceFileSizeTest`)
- every production type carries JUnit coverage, with JaCoCo gates per module
- no Spring in the engine core, no `Serializable`, no unbounded collections (`ArchitectureRulesTest`)
- every file carries the copyright and licence notice (`LicenseHeaderTest`)

## Legal

**Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.**

**PROPRIETARY AND CONFIDENTIAL.** Project Pravaha — its source code, design documents,
architecture, algorithms, data formats, documentation and brand marks — is the sole and exclusive
property of Ashutosh Sinha. No licence or right is granted by implication or otherwise.

Use, copying, modification, distribution and disclosure are prohibited except with the express prior
written permission of the copyright holder. See [`LICENSE`](LICENSE) for the full terms.

If you have obtained a copy of this software without written authorisation, you are not permitted to
retain it; destroy all copies and contact ajsinha@gmail.com.

Third-party open-source components used by Pravaha remain the property of their respective owners
and are governed by their own licences, which this project's terms do not affect. Their attribution
notices are reproduced in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

The Pravaha name, the flow mark, and the slogan "Ask once. Answer always." are proprietary; see
[`brand/README.md`](brand/README.md).
