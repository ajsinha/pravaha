# Pravaha — architecture at a glance

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
Proprietary and confidential; see [`LICENSE`](../LICENSE).

The short version. The full treatment is [`system_design.md`](system_design.md); this page exists so
someone can hold the shape in their head before reading 3 000 lines.

## Two processes

```
      ┌───────────────────────────────┐        ┌──────────────────────────────┐
      │   pravaha-server   (Java 21)  │        │   Pravaha Console  (Python)  │
      │                               │        │                              │
      │   engine + public REST API    │◄───────│   FastAPI + Jinja templates  │
      │   /status  (plain HTML,       │  SDK   │   built on the pravaha SDK   │
      │    works when console is down)│        │                              │
      └───────────────┬───────────────┘        └──────────────────────────────┘
                      │
        ┌─────────────┴─────────────┬──────────────┐
        ▼                           ▼              ▼
   Aerospike                   Cassandra        Kafka
```

The console is a **separate runtime on purpose** (ADR-024). A test enforcing "the console may only
use the public API" can be weakened or waived; a Python process simply cannot reach into a Java
engine. And building the console on the published SDK makes it the first real consumer of the
integration story a closed-source product depends on — a proof rather than an assertion.

The cost is two runtimes to deploy, stated plainly in design §23.2a rather than glossed.

## Inside the engine

```
  SQL text
     │  Apache Calcite: parse, validate, cost-based optimise
     ▼  RelNode
     │  PhysicalPlanBuilder            ← the boundary: nothing below imports Calcite
     ▼  PhysicalOperator tree
     ├──────────────────────┬───────────────────────┐
     │ generated (Janino)   │ interpreted fallback  │  differentially tested
     ▼                      ▼                       │  against each other
  fused stage           RowProcessor chain          │
     │                                              │
     ▼  binary rows in an off-heap arena, MPSC rings between lanes
   sinks
```

Calcite is a **compiler, not a runtime** (ADR-002). The 1.0 draft executed through Calcite's
`Enumerable` convention — pull-based and row-at-a-time — and could not have met its own throughput
targets. Everything expensive happens once at registration; the steady state allocates nothing.

## The five ideas everything else follows from

| | |
|---|---|
| **Z-sets** | A relation and a changelog are the same object: a multiset with signed integer weights. An update is `−1` of the old row and `+1` of the new, so insert, update and delete stop being three cases an operator author must handle and become arithmetic. Design §9. |
| **Binary rows in arenas** | Rows are flyweights over off-heap memory; field access is a constant offset. A batch is processed and the arena rewound in one assignment. No `Map<String,Object>`, no boxing, no per-row allocation. Design §8. |
| **Single-writer lanes** | Concurrency comes from partitioning, not sharing. One thread, one ring, one state slice, one timer wheel per lane — no locks in steady state. Design §13. |
| **Capability declaration** | A source declares whether it can rewind, sees deletes, carries a before-image; a sink declares which changelog modes it accepts. The engine computes the *weakest link* and reports that, rather than promising exactly-once the plumbing cannot deliver. Design §10. |
| **Refuse rather than guess** | An unbounded `GROUP BY` is rejected at planning with a message saying what to do about it. Unbounded integration is how incremental engines die in production, and refusing is the only intervention that reliably works. Design §9.6. |

## Modules

| Module | What it is |
|---|---|
| `pravaha-api` | The public SPI. Zero third-party dependencies, Java 17 bytecode. |
| `pravaha-common` | Memory access, arenas, rings, row layout, configuration. |
| `pravaha-algebra` | Z-sets, frontiers, the incremental lift and its property oracle. |
| `pravaha-runtime` | The plan IR and interpreted execution. **No Calcite.** |
| `pravaha-codegen` | Whole-stage generation, compiled with Janino. |
| `pravaha-sql` | Calcite integration. The only module that imports it. |
| `pravaha-connect` | Plugin discovery, classloader isolation, registry. |
| `pravaha-embedded` | In-process engine. No Spring. |
| `pravaha-server` | Spring Boot node: public REST API and the plain `/status` page. |
| `pravaha-cli` | The `pravaha` command. |
| `pravaha-testkit` | Virtual clock, deterministic scheduler, plugin TCK. |
| [`plugins/pravaha-plugin-filesystem`](../plugins/pravaha-plugin-filesystem) | The reference source and sink. |
| [`sdk/pravaha-sdk-java`](../sdk/pravaha-sdk-java) | Java client. Depends on `pravaha-api` alone. |
| [`sdk/pravaha-sdk-python`](../sdk/pravaha-sdk-python) | Python client. The console is built on it. |

`pravaha-catalog` and `pravaha-state` exist as placeholders; their content arrives in Wave 4.

## Rules the build enforces

Not conventions — tests. Each one exists because the failure it prevents is silent.

| Rule | Enforced by |
|---|---|
| No Spring in the engine core | `ArchitectureRulesTest` |
| No Calcite outside `pravaha-sql` | module dependency graph + `ArchitectureRulesTest` |
| `pravaha-api` depends on nothing but the JDK | `maven-enforcer` + ArchUnit |
| Only one package names a low-level memory API | `ArchitectureRulesTest` |
| No `java.io.Serializable` as a transport | `ArchitectureRulesTest` |
| Source files under 1500 lines | `SourceFileSizeTest` |
| Every file carries the copyright notice | `LicenseHeaderTest` |
| The API surface matches its lock file | `OpenApiContractTest` |
| Documentation names only modules that exist | `DocumentationFreshnessTest` |

## Where to go next

- **Why does this exist?** — design [§1](system_design.md), [§2](system_design.md)
- **Is the engineering sound?** — design [§3](system_design.md), [§9](system_design.md), [§29](system_design.md)
- **How do I run it?** — [`QUICKSTART.md`](QUICKSTART.md)
- **What was decided and why?** — [`adr/`](adr/)
- **When does it ship?** — [implementation plan](implementation_plan.md)

---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**</sub>
