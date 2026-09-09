<div align="center">

<img src="brand/mark.svg" alt="" width="88" height="88">

# प्रवाह · Pravaha

### Ask once. Answer always.

**An embeddable, store-native, incrementally-maintained continuous query engine.**

*Pravaha* (Sanskrit: *continuous, uninterrupted flow*) · pronounced *pruh-VAA-huh*

[![Status](https://img.shields.io/badge/status-design%20phase-blue)](docs/system_design.md)
[![Java](https://img.shields.io/badge/Java-21%20LTS-orange)](docs/system_design.md#4-language-decision-java-vs-scala)
[![Build](https://img.shields.io/badge/build-Maven-C71A36)](docs/implementation_plan.md)
[![License](https://img.shields.io/badge/license-Apache%202.0-green)](LICENSE)

</div>

---

> **Project status: Wave 1 — foundations, in progress.**
> The architecture and delivery plan are complete ([`docs/`](docs/)). Implementation has started:
> the Maven reactor builds, `pravaha-api` and the binary row layout are in, and the build enforces
> its own rules. Nothing is runnable end to end yet — that arrives with Wave 2.
>
> The open decisions in [Appendix B](docs/system_design.md#appendix-b--immediate-next-steps) do not
> gate Wave 1, but the Aerospike edition question has procurement lead time and is worth settling
> early.

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

## Deployment modes

One engine core; four ways to run it.

| Mode | Artifact | Spring | Use |
|---|---|---|---|
| **A** Plain embedded | `pravaha-embedded` | none | Any Java app; unit tests; `pravaha dev` |
| **B** Spring-embedded | `pravaha-spring-boot-starter` | auto-config into *your* app | Add continuous SQL to a service you already run |
| **C** Server | `pravaha-server` | it *is* a Spring Boot app | Standard production deployment |
| **D** All-in-one | same jar + `pravaha.ui.enabled=true` | yes | One jar, one port, console included |

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

A full web application, not an admin page: an IDE-grade SQL workbench with catalog-aware
completion and sub-50 ms validation, a live plan DAG with per-operator telemetry, backfill and
blue/green cutover control, and a time-travel debugger that rewinds a running query and steps it
forward under inspection. Built on Spring Boot 3 + React 19 with a real design system, WCAG 2.2
AA, and performance budgets gated in CI.

It is architecturally out of the data path and experientially the centre of the product.
[Specification →](docs/system_design.md#23-the-pravaha-console--web-ui)

## Documentation

| Document | What's in it |
|---|---|
| **[System Design](docs/system_design.md)** | Architecture, competitive position, 33 sections. Start with §1–§2. |
| **[Implementation Plan](docs/implementation_plan.md)** | 31 sprints, epics E0–E9 + EU, sprints 1–5 at task level, staffing, risks, descope ladder |
| [Original SRS](docs/initial_req.md) | The 1.0-DRAFT requirements this design supersedes. Kept for provenance. |

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

All three work today. See [implementation plan §2](docs/implementation_plan.md) for the toolchain
and profiles; no system Maven is needed, the wrapper is vendored.

## Roadmap

| Phase | Weeks | Milestone |
|---|---|---|
| 0–1 | 1–6 | Foundations; minimal vertical slice; **go/no-go on the incremental core** |
| 2 | 7–11 | Codegen, lanes, exchange — Profile A ≥ 1.2 M rec/s/lane |
| 3–4 | 12–25 | Windows, tiered state, Aerospike, joins, exactly-once checkpointing |
| 5 | 26–32 | Backfill, blue/green, serving layer — **first defensible demo** |
| 6–7 | 33–45 | Gateways, clients, Spring starter, cluster & HA |
| 8–9 | 46–62 | Time-travel debugger, console polish, Nexmark published head-to-head, **GA** |

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

Copyright © 2026 **Ashutosh Sinha** <ajsinha@gmail.com>. All rights reserved.

Licensed under the **Apache License, Version 2.0**. You may not use this software except in
compliance with the License. A copy is distributed in [`LICENSE`](LICENSE), and is also available at
<https://www.apache.org/licenses/LICENSE-2.0>.

Unless required by applicable law or agreed to in writing, software distributed under the License is
distributed on an **"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND**, either express or
implied. See the License for the specific language governing permissions and limitations under it.

Third-party components and their licences are listed in [`NOTICE`](NOTICE). Storage-engine client
libraries are confined to their own plugin modules and are neither bundled with nor required by the
engine core.

The scope is deliberate: Apache 2.0 covers the entire engine, the console and all first-party
plugins. The moat is architecture and execution quality, not a crippled open edition.
[Rationale →](docs/system_design.md#304-licensing-and-commercial-posture)

"Pravaha" and the Pravaha flow mark are used as the identity of this project; see
[`brand/README.md`](brand/README.md) for usage.
