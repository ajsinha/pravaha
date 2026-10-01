# Project Pravaha — Implementation Plan

**From approved design to running code on Java 21 LTS**

> **This document is the plan, not the record.** Its phases, acceptance criteria and
> “validated in CI” claims describe what was intended, and several were never built: there is no
> cross-reference validation script, no benchmark regression gate, no cold-start assertion, and no
> `pravaha-sql/src/test/resources/plans/` golden-plan directory. Gate evidence packs live under
> `docs/gates/wave-N/`, not `docs/gates/PN/`. For what is actually built and actually enforced,
> read [`HANDOVER.md`](HANDOVER.md) and `docs/qa/`.

| Field | Value |
|---|---|
| Document | Pravaha Implementation Plan |
| Version | 1.4 |
| Status | Proposed — for review |
| Companion to | [`system_design.md`](./system_design.md) v3.1 |
| Platform | **Java 21 LTS** (baseline), Maven 3.9+ via wrapper, Java 25 also CI-tested |
| Coordinates | `com.ash.messaging:pravaha` — base package `com.ash.messaging.pravaha` |
| Date | 2026-09-09 |
| Horizon | 31 sprints / 62 weeks, team of **7.5** (revised from 6 — see §6.1) |

---

## Table of Contents

1. [How to Use This Document](#1-how-to-use-this-document)
2. [Prerequisites & Environment Setup](#2-prerequisites--environment-setup)
3. [Engineering Standards & Definition of Done](#3-engineering-standards--definition-of-done)
4. [Repository, Branching & Release Model](#4-repository-branching--release-model)
5. [CI/CD Pipeline](#5-cicd-pipeline)
6. [Team Model & Workstreams](#6-team-model--workstreams)
7. [Work Breakdown Structure](#7-work-breakdown-structure)
8. [Critical Path & Dependency Graph](#8-critical-path--dependency-graph)
9. [Sprint Plan — Phase 0 & 1 in Detail](#9-sprint-plan--phase-0--1-in-detail)
10. [Bootstrap: The Concrete First Commits](#10-bootstrap-the-concrete-first-commits)
11. [Phases 2–9 — Epics & Acceptance Gates](#11-phases-29--epics--acceptance-gates)
12. [Risk-Driven Spikes](#12-risk-driven-spikes)
13. [Milestones, Demos & Go/No-Go Gates](#13-milestones-demos--gono-go-gates)
14. [Tracking & Metrics](#14-tracking--metrics)
15. [Descope Ladder](#15-descope-ladder)
- [Appendix A — Starter POMs](#appendix-a--starter-poms)
- [Appendix B — Sprint 1 Task Checklist](#appendix-b--sprint-1-task-checklist)

---

## 1. How to Use This Document

The design document says *what* Pravaha is and *why*. This one says *who builds what, in which order, and how we know it works*.

**Granularity is deliberately uneven.** Phases 0–2 (sprints 1–11) are specified at task level, because that work starts now and vagueness there is expensive. Phases 3–9 are specified at epic level with hard acceptance gates, because detailing sprint 27 today would be fiction — those epics get decomposed at the start of the phase that contains them.

**Every story has an acceptance criterion that a machine can check.** "Implement the ring buffer" is not a story. "`MpscRingBuffer` passes the JCTools conformance suite and sustains ≥ 50 M offers/s single-producer in JMH" is.

**Reading order for a new engineer:** §2 (get building), §3 (how we work), §9 or §11 (your current phase), then the design doc section your epic references.

> **Reference convention.** A bare `§N` points at a section of *this* document. A reference into the architecture document is always written `design §N` or `§N of the design`. Both documents are validated in CI: a script resolves every cross-reference against the target document's headings and fails the build on a dangling one, which is what keeps them honest as sections get renumbered.

---

## 2. Prerequisites & Environment Setup

### 2.0 Project coordinates

Fixed, and referenced by every POM, package declaration and enforcer rule:

| | |
|---|---|
| `groupId` | `com.ash.messaging` |
| Root `artifactId` | `pravaha` (packaging `pom`) |
| Module `artifactId`s | `pravaha-api`, `pravaha-common`, `pravaha-runtime`, … |
| Base package | `com.ash.messaging.pravaha` |
| Initial version | `0.1.0-SNAPSHOT` |

Two rules key off the base package and are checked in CI from Sprint 1: `PluginClassLoader`'s parent-first prefix list, and the ArchUnit module-dependency / no-Spring rules. Changing the package root later means touching both — so it is fixed here, before P0-01.

### 2.1 Required toolchain

| Tool | Version | Notes |
|---|---|---|
| JDK | **21 LTS** (Temurin recommended) | Already present on the reference workstation (21.0.12) |
| JDK (CI only) | 25 | Second matrix leg; not needed locally |
| Maven | 3.9.x | **Via the vendored wrapper** — do not install system Maven |
| Docker / Podman | recent | Testcontainers only; not needed for unit tests or `pravaha dev` |
| Node.js + pnpm | 20 LTS / 9.x | Only for `pravaha-ui`; the reactor skips it without the `ui` profile |
| Git | 2.40+ | |

### 2.2 One-time setup

```bash
# 1. Verify the JDK
java -version                      # expect 21.x
echo $JAVA_HOME

# 2. Bootstrap the Maven wrapper (done once, committed to the repo)
#    After this, everyone uses ./mvnw and nobody installs Maven.
mvn -N wrapper:wrapper -Dmaven=3.9.9      # requires a temporary system Maven, once
git add mvnw mvnw.cmd .mvn/ && git commit -m "Add Maven wrapper"

# 3. Full build
./mvnw clean verify

# 4. Fast inner loop (skips ITs, benchmarks, UI)
./mvnw -T1C -DskipITs -Dbenchmarks.skip=true test
```

> **Note on step 2:** Maven is not currently on `PATH` on the reference workstation. Bootstrapping the wrapper needs Maven exactly once — from a package manager, SDKMAN, or a downloaded tarball. After the wrapper is committed, no contributor ever needs a system Maven again. This is task **P0-01**.

### 2.3 Build profiles

| Profile | Activation | Effect |
|---|---|---|
| *(default)* | — | Core modules, unit tests |
| `-Pit` | explicit | Testcontainers integration tests (Aerospike, Kafka, …) |
| `-Pbench` | explicit | JMH benchmark modules |
| `-Pui` | explicit | pnpm build of the React SPA |
| `-Ppython` | explicit | Builds and tests the Python SDK, and produces its wheel. A failing Python test fails the Maven build. |
| `-Pffm` | explicit, JDK 22+ | Compiles and tests the FFM `MemoryAccess` implementation (§4.6 of the design) |
| `-Pall` | explicit | Everything; what CI runs on `main` |

**Run a substantial test batch through `tools/verify-clean.sh`, not `mvnw` directly.**
`./mvnw -pl <module> test` resolves that module's dependencies from `~/.m2` rather than from the
working tree, so a change in one module is invisible to a test in another until something reinstalls
it. That does not fail loudly — it produces a test run reporting results for code that is not the
code in front of you, and the reflex it triggers is to hunt for a defect in whatever was just
changed. It has cost four separate debugging sessions.

`tools/verify-clean.sh` deletes Pravaha's own artefacts from `~/.m2` before building, so there is no
stale jar left to resolve. It takes any Maven arguments; with none it runs the full verify. Running
Maven directly is fine for a single module with no cross-module change, and `-am` is the minimum
otherwise.

### 2.4 IDE

IntelliJ IDEA is the reference IDE. Committed config: `.editorconfig`, a shared code style matching `palantir-java-format`, and a run configuration for `pravaha dev`. **No IDE-specific build logic** — if it only works in IntelliJ, it is broken.

---

## 3. Engineering Standards & Definition of Done

### 3.1 Definition of Done — a story is not done until all of these hold

1. Code merged to `develop` via a reviewed PR.
2. Unit tests cover the new logic; **no new uncovered branch in `pravaha-runtime`, `pravaha-state`, `pravaha-algebra`, or `pravaha-codegen`**.
3. The story's stated acceptance criterion is asserted by an automated test, not by inspection.
4. `./mvnw -Pall verify` green — includes Spotless, Error Prone, NullAway, ArchUnit, JaCoCo gates.
5. No JMH benchmark regressed > 10 % against the recorded baseline (hot-path stories only).
6. Public API changes pass `japicmp`, or carry an explicit approved break.
7. Javadoc on every public type in `pravaha-api`; a one-paragraph "why" comment on any non-obvious hot-path optimisation.
8. Documentation updated if behaviour, configuration or an error code changed — and doc snippets still execute (docs-as-tests, from Phase 6).

### 3.2 Coding standards

| Rule | Enforcement |
|---|---|
| `palantir-java-format`, 120-col | `spotless:check` |
| No `null` in new APIs without `@Nullable` | NullAway |
| **No allocation in hot-path methods** — no boxing, no varargs, no lambdas capturing, no iterator allocation | Review + `JMH` allocation-rate assertions + an `@HotPath` marker annotation checked by ArchUnit |
| No unbounded collections or queues on the runtime classpath | ArchUnit rule (design NFR-9) |
| No `java.io.Serializable` anywhere | ArchUnit rule |
| **Source files stay under 1500 lines** (docs and UI code exempt) | `SourceFileSizeTest` walks the tree and fails the build; warns from 1200 so files get split deliberately rather than in a panic |
| **Every production type has JUnit coverage** | JaCoCo line gate per module, plus review |
| **Highly modular**: one public type per file, one responsibility per type | Review, and the file-size rule as a backstop |
| No storage-client imports outside `plugins/**` | ArchUnit + `maven-enforcer` banned dependencies |
| **No `org.springframework` import in any core module** — Spring lives only in `pravaha-server`, `pravaha-ui`, `pravaha-spring-boot-starter` (design §22.1) | ArchUnit + `maven-enforcer` banned dependencies |
| No Spring type reachable from an `@HotPath` method | ArchUnit |
| No Scala outside allowed modules | `maven-enforcer` |
| Every thrown exception carries a stable `PRV-nnnn` code from Phase 6 | Review; codegen'd error catalogue |
| No `Thread.sleep` in tests | ArchUnit rule on test sources |

### 3.3 Review policy

- One approving review for ordinary changes; **two for `pravaha-algebra`, `pravaha-codegen`, and anything touching checkpoint or consistency semantics.**
- The author does not merge their own PR.
- A PR that changes a hot path attaches before/after JMH numbers in the description. No numbers, no merge.

### 3.4 Test taxonomy and where each lives

| Kind | Module | Runs in |
|---|---|---|
| Unit | alongside source | every build |
| Property-based (jqwik) | alongside source | every build |
| Golden plan | `pravaha-sql/src/test/resources/plans/` | every build |
| Differential (codegen vs interpreted) | `pravaha-testkit` driven | every build |
| Architecture (ArchUnit) | `pravaha-it` | every build |
| Integration (Testcontainers) | `pravaha-it` | `-Pit`, on PR to `develop` |
| Chaos | `pravaha-it` | nightly |
| Soak (72 h) | `pravaha-it` | weekly |
| JMH micro | `pravaha-benchmarks` | nightly + on hot-path PRs |
| Nexmark | `pravaha-benchmarks` | nightly from Phase 3 |

---

## 4. Repository, Branching & Release Model

### 4.0 Waves

Work ships in **waves**. A wave is one epic's worth of work with a demonstrable outcome and a
machine-checkable gate; `develop` moves continuously within a wave, and `main` moves exactly once
at the end of one.

| | |
|---|---|
| **Within a wave** | Commit and push to `develop` freely — several times a day is normal. `develop` stays green; a red `develop` is fixed before anything else proceeds. |
| **End of a wave** | The gate's acceptance criteria are met and evidenced, then `develop` merges to `main` with `--no-ff` and the milestone is tagged. |
| **Never** | A merge to `main` mid-wave. `main` is the record of demonstrable milestones, not a mirror of `develop`. |

| Wave | Epic | Sprints | Gate |
|---|---|---|---|
| **1** | E0 Foundations | 1–2 | Clean clone builds green on the JDK 21 baseline; deterministic harness demonstrated; JMH baselines recorded |
| **2** | E1 Minimal vertical slice | 3–5 | Query runs end to end; property oracle green; **M2 go/no-go on the DBSP bet** |
| **3** | E2 Performance core | 6–11 | Profile A ≥ 1.2 M rec/s/lane; ≥ 90 % scaling to 8 lanes |
| **4** | E3 Stateful & incremental | 12–18 | Profile B ≥ 350 k rec/s/lane; invariants 1–8 green |
| **5** | E4 Aerospike, joins, durability | 19–25 | Exactly-once state proven by chaos test; W4 ≥ 5× |
| **6** | E5 Backfill & serving | 26–32 | **First defensible demo** — W3 point lookup ≤ 200 µs |
| **7** | E6 Gateways, clients, DX | 33–38 | W2 deploy ≤ 2 s; starter green on Spring Boot 3.2–3.5 |
| **8** | E7 Survival on one node ([ADR-035](adr/035-wave-8-is-survival-not-distribution.md)) | 39–45 | A killed node restarts onto its own state and nothing else's; a standby takes over and names what it lost |
| **9** | ES One node, thousands of queries ([ADR-036](adr/036-one-node-thousands-of-queries.md), [ADR-037](adr/037-state-that-degrades-instead-of-dying.md)) | — | `NodeScaleTest` registers thousands rather than hundreds inside a test JVM; a node's thread count follows its cores, not its query count |
| **10** | E8 Control plane & self-tuning | 46–53 | W10 debugger finds a seeded bug and exports the fixture |
| **11** | E9 Breadth, benchmarks, GA | 54–62 | All SLOs; W5 Nexmark published; **GA** |

**Wave 9 was inserted, not renamed.** [ADR-036](adr/036-one-node-thousands-of-queries.md) puts
scale-out and hardening on one node ahead of E8's control-plane features — "building a time-travel
debugger on top of an unmeasured foundation puts a floor above a hole" — so E8 and E9 keep their
content and their sprint estimates and move down one. The inserted wave's own length was never
estimated, which is why its Sprints cell is a dash rather than a guess.

Epic **EU** (the console) runs across waves 3–11 rather than owning one, because it ships a surface
alongside each engine capability (§6.2).

Each wave ends with an evidence pack under `docs/gates/` — benchmark output, test reports, and a
one-page retrospective on what the wave got wrong. The retrospective feeds the next wave's estimate.

### 4.1 Branching

```
main        ← always releasable; tagged releases; protected
  ▲ merge --no-ff, only from develop, only when the phase gate passes
develop     ← integration branch; CI green at all times; protected
  ▲ squash-merge from feature branches
feat/<epic>-<story>-<slug>     e.g. feat/E0-P0-07-mpsc-ring-buffer
fix/<issue>-<slug>
spike/<name>                   time-boxed, never merged, findings written up
```

The `develop` → `main` merge happens at **phase boundaries only** — that is what makes `main` a meaningful record of demonstrable milestones rather than a mirror of `develop`.

### 4.2 Commit convention

Conventional Commits, scoped by module:

```
feat(runtime): add MPSC ring buffer with configurable wait strategy
fix(state): close RocksDB column family handles on lane shutdown
perf(codegen): split generated stages at 4 kB bytecode to stay JIT-eligible
test(algebra): property oracle for linear incremental operators
docs(design): revise Java baseline to 21
```

### 4.3 Versioning

- `0.x.y` until Phase 6; `1.0.0` at GA (end of Phase 9).
- `pravaha-api` is under `japicmp` semver enforcement **from Sprint 3** — it is the contract plugin authors compile against, and breaking it late is far more expensive than constraining it early.
- Every phase boundary produces a tagged milestone build with release notes.

---

## 5. CI/CD Pipeline

### 5.1 Stages

| Stage | Trigger | Duration target | Blocking |
|---|---|---|---|
| **Fast** — compile, Spotless, Error Prone, unit + property tests, ArchUnit | every push | ≤ 6 min | yes |
| **Verify** — integration tests (Testcontainers), JaCoCo gates, `japicmp` | PR to `develop` | ≤ 20 min | yes |
| **Matrix** — full build on JDK **21** and **25**; from E6 also `pravaha-spring-boot-starter` against Spring Boot 3.2–3.5 | PR to `develop` | ≤ 25 min | yes |
| **Bench** — JMH subset, regression threshold 10 % | nightly + hot-path PRs | ≤ 45 min | yes on PR label `hot-path` |
| **Nexmark** — q0–q22 vs recorded baseline | nightly from Phase 3 | ≤ 60 min | reported, blocking from Phase 7 |
| **Chaos** — node kills, partitions, stalled sinks | nightly from Phase 4 | ≤ 40 min | yes |
| **Soak** — 72 h at 70 % capacity, leak assertions | weekly from Phase 4 | 72 h | yes |
| **Console** — Vitest, axe, Playwright visual regression (light/dark × both densities), Lighthouse + bundle budget | PR touching `pravaha-ui` | ≤ 18 min | yes, from sprint 12 |
| **Security** — OSV/dependency scan, SBOM | nightly | ≤ 10 min | yes on high severity |
| **Release** — Jib images, Helm chart, staged artifacts | tag on `main` | ≤ 15 min | — |

### 5.2 Performance is a test, not a hope

The Bench stage stores results in a committed `benchmarks/baselines/` directory keyed by benchmark name and hardware profile. A regression > 10 % fails the build. Improving a baseline requires an explicit commit that updates the recorded value, so every performance change is deliberate and reviewed.

This is set up in **Sprint 1**, before there is anything to benchmark. Retrofitting performance gates onto an existing codebase does not work — by then the regressions are already in and nobody knows which commit caused them.

---

## 6. Team Model & Workstreams

### 6.1 Composition

| Role | Count | Owns |
|---|---|---|
| Tech lead / architect | 1 | Design integrity, ADRs, cross-cutting review, the two-reviewer modules |
| Core runtime engineer | 2 | `common`, `runtime`, `state`, `codegen`, `algebra` — the hot path |
| SQL / planner engineer | 1 | `sql`, `catalog`, `algebra` (shared), optimizer rules |
| Connectors engineer | 1 | `connect`, all `plugins/*`, `backfill` |
| Platform engineer | 1 | `cluster`, `gateways`, `server`, `spring-boot-starter`, `cli`, CI/CD, release |
| **Frontend engineer** | **1** | **`pravaha-ui` frontend: design system, all screens, workbench, plan DAG, debugger UI** |
| **Product designer** | **0.5** | **Design system, IA, interaction design, usability testing, accessibility audits** |

**7.5 people, revised upward from 6.** The v1.1 plan folded the console into the platform engineer's remit and scheduled it entirely in Phase 8. That was wrong on both counts: design §23 specifies a full web application with an IDE-grade SQL workbench, a live plan DAG, a time-travel debugger and a real design system, and no part-time owner produces that in eight sprints at the end of a project.

The honest arithmetic: the console is roughly **20–24 engineer-sprints of frontend work plus ~12 sprints of design**. Resourcing it properly is what makes "highly polished" a plan rather than an aspiration.

**If the console must be built with 6 people** — no dedicated frontend engineer — then say so explicitly and accept the consequence: the console becomes a functional admin UI, design §23.20's checklist is not met, W10's debugger ships as a CLI (`pravaha replay`) instead of a UI, and the operability differentiator (D-E) weakens substantially. That is a legitimate trade to make deliberately. It is not a legitimate one to make by accident, which is what the previous plan would have done.

### 6.2 Parallel workstreams

The three workstreams below run concurrently from Sprint 3 onward, joining at phase gates. Sprints 1–2 are deliberately **single-stream** — everyone builds the foundations together so that everyone understands the row layout, the arena and the testkit, since every subsequent line of code touches them.

```
Sprint  1  2 │ 3  4  5 │ 6  7  8  9 10 11 │ 12 …
             │         │                  │
WS-A  ═══════╪═════════╪══════════════════╪═══  Runtime & performance
 (2 eng)     │ arena   │ lanes, rings,    │     state, windows, timers
             │ layout  │ codegen, fusion  │
             │         │                  │
WS-B  ═══════╪═════════╪══════════════════╪═══  SQL, algebra & planning
 (1–2 eng)   │ Calcite │ Z-sets, lift     │     aggregates, joins
             │ bind    │ rules, oracle    │
             │         │                  │
WS-C  ═══════╪═════════╪══════════════════╪═══  Plugins, platform & tooling
 (1–2 eng)   │ SPI     │ fs + kafka       │     Aerospike, CLI, CI
             │ + TCK   │ plugins, dev CLI │
             │         │                  │
WS-D         │         │      ════════════╪═══  Console (frontend + design)
 (1.5)       │         │      design sys, │     starts sprint 9, ships a
             │         │      shell, BFF  │     surface with every phase
   ▲         ▲         ▲                  ▲
 all-hands  gate P0   gate P1          gate P2
```

### 6.3 Coordination

- Daily 15-minute standup per workstream; twice-weekly 30-minute cross-workstream sync.
- **Design review before implementation** for any story touching `pravaha-api`, the algebra, or checkpoint semantics — a one-page RFC in `docs/rfc/`, reviewed within 48 h.
- ADRs are amended, never rewritten; a superseded ADR keeps its number and gains a "superseded by NNN" header.

---

## 7. Work Breakdown Structure

Epics map 1:1 to the design's phases and to the **waves** of §4.0 — one epic, one wave, one merge to `main`. Story IDs are stable and referenced by branch names and commits.

### E0 — Foundations *(Phase 0, sprints 1–2)*

| ID | Story | Est. | Depends | Acceptance |
|---|---|---|---|---|
| P0-01 | Maven wrapper + parent POM + BOM + 8 skeleton modules | 3 d | — | `./mvnw clean verify` green on a clean clone with no system Maven |
| P0-02 | Build plumbing: Spotless, Error Prone, NullAway, JaCoCo, enforcer, toolchains | 2 d | P0-01 | A deliberately mis-formatted commit fails CI |
| P0-03 | CI: Fast + Verify + Matrix (JDK 21, 25) stages | 2 d | P0-02 | Both matrix legs green; total ≤ 25 min |
| P0-04 | `pravaha-api`: `RowKind`, `PravahaType`, `StreamSchema`, exceptions, `Version` | 3 d | P0-01 | Compiles at `--release 17`; zero third-party deps asserted by enforcer |
| P0-05 | `MemoryAccess` abstraction + Agrona implementation | 3 d | P0-04 | JMH: get/put long ≤ 2 ns; allocation rate 0 B/op |
| P0-06 | Binary row layout: `RowLayout` computation, `RowView`, `RowWriter` | 5 d | P0-05 | Round-trip property test over generated schemas; ≥ 1 field of every `PravahaType` |
| P0-07 | `RowArena` slab allocator with mark/reset | 3 d | P0-05 | JMH: allocate+reset ≤ 5 ns/row amortised, 0 B/op heap |
| P0-08 | MPSC and SPSC ring buffers (wrapping JCTools/Agrona) + wait strategies | 3 d | P0-05 | JCTools conformance suite passes; ≥ 50 M offers/s SPSC in JMH |
| P0-09 | `pravaha-testkit`: virtual clock, deterministic scheduler, `TestHarness` | 5 d | P0-06 | A two-operator pipeline produces byte-identical output across 1 000 runs with randomised interleavings |
| P0-10 | JMH harness + `benchmarks/baselines/` + CI Bench stage | 3 d | P0-03 | A seeded 15 % regression fails the build |
| P0-11 | ArchUnit rule set (no `Serializable`, no unbounded collections, module deps, **no Spring in core**, no `Thread.sleep` in tests) | 2 d | P0-01 | Each rule has a deliberately-violating fixture that fails |
| P0-12 | `docs/adr/` seeded with ADRs 001–018 from the design doc | 1 d | — | Each ADR is one file with context/decision/consequences |

**Gate P0:** clean clone → `./mvnw clean verify` green on JDK 21 and 25 in under 25 minutes; deterministic harness demonstrated; JMH baselines recorded.

### E1 — Minimal vertical slice *(Phase 1, sprints 3–5)*

| ID | Story | Est. | Depends | Acceptance |
|---|---|---|---|---|
| P1-01 | `pravaha-algebra`: Z-set model, weight arithmetic, consolidation | 4 d | P0-06 | Property: consolidation is associative, commutative, and annihilates `+w`/`−w` pairs |
| P1-02 | Frontier & logical-time model | 3 d | P1-01 | Property: frontiers advance monotonically under arbitrary merge orders |
| P1-03 | Incremental lift rules for **linear** operators (filter, project, union) | 5 d | P1-01 | — |
| P1-04 | **The property oracle**: `Q(S+ΔS) == Q(S) + Q^Δ(ΔS, S)` over generated `Q`, `S`, `ΔS` | 5 d | P1-03 | Runs 10 000 generated cases per CI run; a seeded operator bug is caught within 100 cases |
| P1-05 | Calcite integration: `PravahaSchema`, catalog reader, type mapping | 5 d | P0-04 | `SELECT a,b FROM s WHERE c > 1` parses, validates and produces a `RelNode` |
| P1-06 | Interpreted operator set: scan, filter, project, union | 4 d | P1-03, P1-05 | Differential-test fixture ready for Phase 2's codegen |
| P1-07 | Physical plan builder: `RelNode` → `PhysicalPlan` | 4 d | P1-05 | Golden-plan tests for 10 representative queries |
| P1-08 | Single-lane executor loop with batching | 3 d | P0-08, P1-06 | End-to-end record flow, deterministic under the testkit |
| P1-09 | Plugin SPI + `PluginClassLoader` (parent-last) + `ServiceLoader` discovery | 4 d | P0-04 | Two plugins with conflicting Guava versions both load and work |
| P1-10 | Filesystem source & sink plugin | 2 d | P1-09 | CSV/JSON-lines in, out; used by every later test |
| P1-11 | Kafka source & sink plugin | 4 d | P1-09 | Testcontainers IT: 1 M records round-trip, offsets committed |
| P1-12 | `pravaha-embedded` facade + the `PravahaEngine` seam (design §22.1) | 3 d | P1-08 | A 15-line Java main runs a query in-process; ArchUnit confirms zero Spring on the module's classpath |
| P1-13 | `pravaha dev` CLI: fixture-driven in-process engine, hot reload | 4 d | P1-12 | **Cold start to first output < 1 s**, asserted in CI |
| P1-14 | Plugin TCK v1 (capability declarations vs actual behaviour) | 3 d | P1-09 | Filesystem and Kafka plugins pass; a plugin falsely claiming replayable offsets fails |

**Gate P1:** `SELECT STREAM … WHERE …` runs end to end Kafka → filesystem; property oracle green for linear operators; `pravaha dev` under 1 s. *This gate is the go/no-go on the DBSP bet — see §13.*

### E2 — Performance core *(Phase 2, sprints 6–11)*

| ID | Story | Est. | Acceptance |
|---|---|---|---|
| P2-01 | Expression compiler: `RexNode` → Java source, null-aware, type-specialised | 8 d | Every `RexNode` in the test corpus compiles or falls back explicitly |
| P2-02 | Operator code templates + whole-stage fusion + stage splitter | 8 d | Fused stage for filter+project+window-assign generates and runs |
| P2-03 | Janino compilation pipeline, per-query classloader, warm-up | 4 d | Compile ≤ 30 ms/stage; 10 000 register/drop cycles leave metaspace at baseline |
| P2-04 | Interpreted fallback + automatic method splitting at 4 kB bytecode | 4 d | A deliberately huge stage compiles via splitting, or falls back and logs |
| P2-05 | Differential test rig: generated vs interpreted, byte-identical | 3 d | Runs over the full query corpus every build |
| P2-06 | Lane model: pinned threads, per-lane arena, per-lane state slice | 5 d | 16 lanes, zero cross-lane sharing verified by a contention benchmark |
| P2-07 | Hash exchange between lanes (SPSC rings) | 4 d | ≥ 90 % linear scaling 1→8 lanes on Profile A |
| P2-08 | Adaptive batching controller (§18.2 of the design) | 4 d | Same query meets its latency target at 10 rec/s and 1 M rec/s |
| P2-09 | Backpressure: high/low watermarks, `pause`/`resume` propagation to plugins | 4 d | A stalled sink pauses the source within 200 ms; no unbounded growth anywhere |
| P2-10 | False-sharing audit + padding + JMH regression guard | 2 d | Padding removal is detected by the benchmark |
| P2-11 | Generated-source retention + `EXPLAIN codegen` | 2 d | Source downloadable for any running query under a debug flag. *Delivered as `explain --level codegen` on the CLI and `level=codegen` on the existing explain endpoint, plus a bounded opt-in retention registry. The "running query" half needs the query lifecycle (§11.6), which does not exist yet — a source can be shown for any query that can be planned, which is every query the node can run.* |
| **P2-12** | **Lane multiplexing: ready list, in-lane zero-copy fan-out, per-query fairness** | 6 d | 1 000 registered queries on 4 lanes; an idle query costs its lane no measurable time; per-query cost attributed by name (ADR-027, §13.7). *Corrected during implementation: a hot query **cannot** be held to a quota of lane batches without either dropping its rows — a wrong answer, not a slow one — or copying them into a per-query backlog, which is the per-query buffer the density budget rules out. What is enforceable is ordering: pipelines run in ascending order of lane time consumed, so a heavy query yields its position rather than accumulating an advantage. A hard ceiling needs admission control at registration, which belongs with the query lifecycle.* |
| **P2-13** | **Interpreted-first admission with background upgrade to generated code** | 3 d | 1 000 queries registered and producing output within 5 s of a cold start; each is observed to switch to its generated stage; the swap loses no rows |

**Gate P2:** **Profile A ≥ 1.2 M rec/s/lane**; ≥ 90 % scaling to 8 lanes; differential tests green; no metaspace leak over 10 000 query cycles.

> **The scaling clause needs hardware this project does not currently have.** The development machine is a 12-physical-core heterogeneous laptop SoC with SMT and aggressive frequency scaling, on which an all-core measurement is confounded by the power envelope before the software is reached (`benchmarks/README.md`). Single-lane throughput and structural cross-lane independence *are* measurable here and are asserted; the 1→8 figure must be taken on the reference hardware of design §5.2, and scheduling that is a gate prerequisite rather than a detail of it.

> **P2-12 and P2-13 are additions made in Wave 3**, not part of the original E2 scope. They follow from NFR-2d — 10 000 concurrent queries per node — which was stated after the wave began. Recording them here rather than absorbing them silently keeps the estimate honest: this is the third time the plan has under-counted adjacent work, and the pattern is worth more than any individual estimate.

### EU — The Console *(continuous workstream, sprints 9–62)*

Design §23 specifies the console. This epic runs **alongside** E3–E9 rather than inside any one of them, and each phase ships the console surfaces for the engine capability that phase delivers. Full stories are decomposed per phase; the shape is fixed here.

| ID | Story | Sprints | Depends on | Acceptance |
|---|---|---|---|---|
| U-01 | **Design system**: tokens, both themes, both densities, primitives, Storybook | 9–12 | — | Every primitive documented in Storybook with all eight states of design §23.12; contrast verified by a token-level test in both themes |
| U-02 | App shell, IA, routing, command palette, auth (OIDC), RBAC-driven navigation | 11–14 | U-01, E1 | Every route deep-linkable; full keyboard navigation; affordances absent (not disabled-and-failing) without permission |
| U-03 | BFF: OpenAPI contract, generated TS types + Zod, SSE fan-out, session caps | 11–15 | E1 | A backend contract change breaks the frontend build, not production |
| U-04 | Catalog screens: streams/tables/sinks, schema browser, version diff | 13–17 | E3 | Schema version diff renders correctly for every compatibility case in design §11.4 |
| U-05 | Queries list + query detail (overview, state, timeline, errors/DLQ) | 15–19 | E3 | 1 000-row virtualised list streams at 1 Hz within a 16 ms frame budget |
| U-06 | **SQL Workbench**: Monaco, catalog completion, live validate, EXPLAIN, dry run, cost estimate | 18–24 | E3, E4 | Validation round trip **< 50 ms**; every `PRV-nnnn` diagnostic renders with its actionable fix |
| U-07 | **Live plan DAG** with per-operator telemetry, lane expansion, node drawer | 21–26 | E4 | 200-node DAG updates within a 16 ms frame; layout stable across refreshes |
| U-08 | Live results tap with explicit sampling/drop indication; export | 24–27 | E5 | A slow browser provably cannot backpressure the engine (asserted by an E2E test) |
| U-09 | Views browser + client-snippet generation (Java/Python/Go) | 26–29 | E5 | Generated snippet compiles and runs against the live view |
| U-10 | **Backfill & blue/green cutover control** with storage-impact display | 27–32 | E5 | Storage p99 plotted alongside ingest rate; throttle takes effect within 2 s |
| U-11 | Cluster topology, assignment map, rebalance driver | 33–40 | E7 | Rebalance progress and per-partition handoff visible live |
| U-12 | Plugins, tenants, roles, quotas, audit | 36–42 | E6, E7 | Every admin action typed-confirmed and audited |
| U-13 | **Time-travel debugger UI** (W10) | 44–50 | E8 | A seeded production bug is diagnosed and exported as a passing JUnit fixture entirely from the browser |
| U-14 | Adaptive-controller screens; metrics explorer | 48–52 | E8 | Every auto-tuning decision explained with its inputs, and pinnable from the UI |
| U-15 | Onboarding / first-run experience | 50–54 | all | **A new user reaches a running query in under five minutes**, measured with five real people who have not seen the product |
| U-16 | **Polish pass**: design §23.20 checklist, WCAG 2.2 AA audit, visual-regression baseline, performance budgets | 52–58 | all | Design §23.20 fully green; zero axe violations; Lighthouse budgets gated in CI |

**Gate U:** design §23.20's acceptance checklist green, all eight critical journeys passing on every PR, and the five-minute onboarding measured rather than asserted.

### E3–E9 — summarised in §11

| Wave | Epic | Phase | Sprints | Theme |
|---|---|---|---|---|
| **4** | **E3** | 3 | 12–18 | Stateful & incremental: watermarks, windows, timers, tiered state, aggregates, `DISTINCT`, bounded-state enforcement |
| **5** | **E4** | 4 | 19–25 | Aerospike, bilinear incremental joins, checkpointing, recovery, pushdown |
| **6** | **E5** | 5 | 26–32 | Backfill, blue/green updates, serving layer, consistency modes |
| **7** | **E6** | 6 | 33–38 | gRPC + Arrow gateways, Avatica, typed clients, full CLI, error catalogue, TCK, docs-as-tests |
| **8** | **E7** | 7 | 39–45 | **Rescoped by [ADR-035](adr/035-wave-8-is-survival-not-distribution.md):** node ownership of durable state, aligned checkpoint barriers, standby + checkpoint failover, and wiring the dead-letter queue / L0 state map / changelog analysis. Ratis, membership, assignment, rebalance, multi-tenancy and elastic rescale stay deferred with ADR-034 |
| **9** | **ES** | 7b | — | **Inserted by [ADR-036](adr/036-one-node-thousands-of-queries.md):** lane multiplexing onto shared threads, a shared watermark/checkpoint clock, arena and inbox sizing as settings, an Aerospike scan interval, a descriptor ceiling the node reports, and state a query reports against its ceiling ([ADR-037](adr/037-state-that-degrades-instead-of-dying.md) B1). Built. Not built: one Aerospike reader feeding many queries, a lane that multiplexes pipelines, and B2's on-disk tier |
| **10** | **E8** | 8 | 46–53 | Control-plane UI, time-travel debugger, security, observability, self-tuning controllers |
| **11** | **E9** | 9 | 54–62 | Cassandra/PostgreSQL/Redis plugins, `WITH RECURSIVE`, Nexmark publication, soak, security review, TCO validation, GA |

---

## 8. Critical Path & Dependency Graph

```
 P0-05 MemoryAccess
   └─► P0-06 Row layout ──────────────┬─► P0-09 testkit ─────────┐
         └─► P0-07 arena              │                          │
 P0-08 rings ───────────────────┐     │                          │
                                ▼     ▼                          ▼
                          P1-08 single-lane executor      P1-04 PROPERTY ORACLE ★
                                ▲     ▲                          ▲
 P1-05 Calcite bind ─► P1-07 physical plan            P1-01 Z-sets ─► P1-03 lift rules
                                │
                                ▼
            ★ P2-01 expression compiler ─► P2-02 fusion ─► P2-03 Janino
                                                              │
                                                              ▼
                          P2-06 lanes ─► P2-07 exchange ─► GATE P2
                                                              │
                                                              ▼
        E3 windows + tiered state ─► E4 joins + checkpoints ─► E5 backfill + serving
                                                              │
                              ┌───────────────────────────────┤
                              ▼                               ▼
                    E6 gateways + DX                    E7 cluster + HA
                              └───────────────┬───────────────┘
                                              ▼
                                 E8 UI + debugger + self-tuning
                                              ▼
                                    E9 breadth + Nexmark + GA
```

★ = the two items that carry the most schedule risk.

**Critical path:** `P0-06 → P1-01/P1-03 → P1-04 → P2-01 → P2-02 → E3 → E4 → E5`.

Three observations that should shape day-to-day decisions:

1. **The row layout (P0-06) blocks everything.** Two engineers on it in sprint 1, reviewed by the whole team, and do not start dependent work until it is stable. A layout change in sprint 12 is a multi-week rewrite.
2. **The property oracle (P1-04) is the cheapest insurance in the plan.** It is the mechanism by which the DBSP bet is de-risked, and it must exist before the aggregate and join lift rules are written — not after.
3. **E6 and E7 can genuinely run in parallel** with six people; E8 partially overlaps E7. E3→E4→E5 cannot be parallelised because each consumes the previous one's state machinery.

---

## 9. Sprint Plan — Phase 0 & 1 in Detail

Two-week sprints. Capacity assumes ~8 productive days per engineer per sprint.

### Sprint 1 — "It builds" *(all-hands)*

| Story | Owner | Days |
|---|---|---|
| P0-01 wrapper + parent POM + module skeletons | Platform | 3 |
| P0-02 build plumbing | Platform | 2 |
| P0-03 CI Fast + Verify + Matrix | Platform | 2 |
| P0-04 `pravaha-api` core types | Lead + SQL | 3 |
| P0-05 `MemoryAccess` + Agrona impl | Runtime A | 3 |
| P0-06 binary row layout *(starts)* | Runtime A + B | 5 |
| P0-12 ADR files | Lead | 1 |

**Sprint goal:** a clean clone builds green on JDK 21 and 25 in CI, and `pravaha-api` compiles at `--release 17`.
**Demo:** CI dashboard; a deliberately mis-formatted PR failing.

### Sprint 2 — "It's deterministic"

| Story | Owner | Days |
|---|---|---|
| P0-06 row layout *(complete)* | Runtime A + B | 5 |
| P0-07 arena | Runtime A | 3 |
| P0-08 ring buffers | Runtime B | 3 |
| P0-09 testkit: virtual clock + scheduler | Lead + Runtime B | 5 |
| P0-10 JMH harness + baselines + Bench stage | Platform | 3 |
| P0-11 ArchUnit rules | Platform | 2 |
| P1-09 plugin SPI + classloader *(starts)* | Connectors | 4 |

**Sprint goal:** **Gate P0.** Deterministic harness runs a two-operator pipeline identically across 1 000 randomised interleavings; JMH baselines recorded.
**Demo:** run the same pipeline 1 000 times, diff all outputs, show zero differences. Show a seeded 15 % regression failing CI.

### Sprint 3 — "Z-sets and Calcite" *(workstreams split here)*

| Story | WS | Owner | Days |
|---|---|---|---|
| P1-01 Z-set model + consolidation | B | SQL + Lead | 4 |
| P1-02 frontiers & logical time | B | SQL | 3 |
| P1-05 Calcite schema binding *(starts)* | B | SQL | 5 |
| P0-05b FFM `MemoryAccess` impl + MR-JAR + `-Pffm` | A | Runtime A | 3 |
| P1-08 single-lane executor loop *(starts)* | A | Runtime B | 3 |
| P1-09 plugin SPI *(complete)* + P1-10 filesystem plugin | C | Connectors | 6 |
| `japicmp` enabled on `pravaha-api` | C | Platform | 1 |

**Sprint goal:** Z-set algebra with property-tested consolidation; a SQL string reaches a validated `RelNode`.

### Sprint 4 — "The oracle"

| Story | WS | Owner | Days |
|---|---|---|---|
| P1-03 linear lift rules | B | SQL + Lead | 5 |
| **P1-04 property oracle** | B | Lead | 5 |
| P1-06 interpreted operators | A | Runtime B | 4 |
| P1-07 physical plan builder | A/B | SQL | 4 |
| P1-08 executor loop *(complete)* | A | Runtime B | 3 |
| P1-11 Kafka plugin | C | Connectors | 4 |
| P0-05b/FFM parity JMH gate | A | Runtime A | 2 |

**Sprint goal:** the property oracle runs 10 000 generated cases per build and catches a seeded bug.
**Demo:** inject a deliberate off-by-one into the filter lift rule; watch the oracle find it and print the minimal counterexample.

### Sprint 5 — "End to end"

| Story | WS | Owner | Days |
|---|---|---|---|
| P1-12 embedded facade | A | Runtime B | 2 |
| P1-13 `pravaha dev` CLI | C | Platform | 4 |
| P1-14 plugin TCK v1 | C | Connectors | 3 |
| P1-11 Kafka IT hardening | C | Connectors | 2 |
| P2-01 expression compiler *(starts)* | A | Runtime A | 5 |
| Golden-plan corpus (20 queries) | B | SQL | 3 |
| Gate P1 evidence pack | — | Lead | 2 |

**Sprint goal:** **Gate P1.** Kafka → filter/project → filesystem, running from `pravaha dev` in under a second.
**Demo:** live — edit the SQL file, watch output change without a restart. Then the go/no-go conversation on §13's Gate P1.

### Sprints 6–11 — Phase 2

Decomposed at the Sprint 6 planning session from the E2 table. Indicative shape:

| Sprint | Focus |
|---|---|
| 6–7 | P2-01 expression compiler, P2-02 templates & fusion *(the hardest two stories in the plan)* |
| 8 | P2-03 Janino pipeline, P2-04 fallback + method splitting, P2-05 differential rig |
| 9 | P2-06 lane model, P2-10 false-sharing audit |
| 10 | P2-07 hash exchange, scaling benchmarks |
| 11 | P2-12 lane multiplexing, P2-13 interpreted-first admission |
| 11 | P2-08 adaptive batching, P2-09 backpressure, P2-11 EXPLAIN codegen, **Gate P2** |

---

## 10. Bootstrap: The Concrete First Commits

What exists at the end of Sprint 1.

### 10.1 Directory tree

```
pravaha/
├── mvnw  mvnw.cmd  .mvn/wrapper/
├── pom.xml                          ← parent (packaging: pom)
├── .editorconfig  .gitattributes  .gitignore
├── .github/workflows/{fast,verify,matrix,bench,nightly}.yml
├── config/
│   ├── spotless/pravaha.importorder
│   ├── spotless/license-header.txt
│   └── archunit/rules.md
├── docs/
│   ├── system_design.md
│   ├── implementation_plan.md
│   ├── adr/0001-language-and-platform.md … 0018-licensing.md
│   └── rfc/
├── benchmarks/baselines/            ← committed JMH baselines
├── pravaha-bom/pom.xml
├── pravaha-api/
├── pravaha-common/
├── pravaha-algebra/
├── pravaha-catalog/
├── pravaha-sql/
├── pravaha-runtime/
├── pravaha-state/
├── pravaha-connect/
├── pravaha-testkit/
├── pravaha-benchmarks/
└── pravaha-it/
```

Modules from the design's §7 that no story touches before Phase 3 are **not** created in Sprint 1. Empty modules are noise; they get created by the story that needs them — `pravaha-embedded` in Sprint 5, `pravaha-server` and `pravaha-spring-boot-starter` in E6.

### 10.2 The first types — `pravaha-api`

```java
// com.ash.messaging.pravaha.api.data
public enum RowKind { INSERT, UPDATE_BEFORE, UPDATE_AFTER, DELETE }

public sealed interface PravahaType permits PrimitiveType, DecimalType,
        StringType, BytesType, TimestampType, ArrayType, MapType, RowType {
    int fixedWidth();        // -1 for variable-width
    boolean isNullable();
    String sqlName();
}

public record Field(String name, PravahaType type, int ordinal) {}

public record StreamSchema(String name, List<Field> fields, int version,
                           OptionalInt eventTimeOrdinal, List<String> primaryKey) {
    public int indexOf(String field) { … }
}
```

```java
// com.ash.messaging.pravaha.api.data — the flyweight contract (design §8.4)
public interface RowView {
    long address();
    int  length();
    RowKind rowKind();
    long eventTimestampNanos();
    long sequence();
    long weight();                       // Z-set weight (design §9.2)

    boolean isNull(int ordinal);
    boolean getBoolean(int ordinal);
    int     getInt(int ordinal);
    long    getLong(int ordinal);
    double  getDouble(int ordinal);
    void    getBytes(int ordinal, MutableSlice out);
    String  getString(int ordinal);      // off hot path only
}
```

### 10.3 `MemoryAccess` — the Java 21 / 22+ seam

The one place either memory API is named (design §4.6).

```java
// com.ash.messaging.pravaha.common.memory
public interface MemoryAccess {
    long allocate(long bytes);
    void free(long address);
    long getLong(long base, int offset);
    void putLong(long base, int offset, long value);
    int  getInt(long base, int offset);
    void putInt(long base, int offset, int value);
    void copyMemory(long src, long dst, int bytes);
    boolean utf8Equals(long base, int offset, int len, byte[] literal);

    static MemoryAccess best() {
        if (Runtime.version().feature() >= 22
                && Boolean.parseBoolean(System.getProperty("pravaha.ffm", "false"))) {
            try { return (MemoryAccess) Class
                    .forName("com.ash.messaging.pravaha.common.memory.ForeignMemoryAccess")
                    .getField("INSTANCE").get(null);
            } catch (ReflectiveOperationException ignored) { /* fall through */ }
        }
        return AgronaMemoryAccess.INSTANCE;   // default on 21
    }
}
```

`ForeignMemoryAccess` lives under `src/main/java22/` and is packaged into `META-INF/versions/22/` by the `-Pffm` profile. On JDK 21 it is neither compiled nor loaded. **P0-05 delivers the Agrona implementation only**; the FFM one is P0-05b in Sprint 3, so the seam is proven early but does not block anything.

### 10.4 The first test that matters

```java
class DeterminismTest {
    @Test
    void identicalInputProducesIdenticalOutputAcrossInterleavings() {
        List<byte[]> reference = null;
        for (int seed = 0; seed < 1_000; seed++) {
            var h = TestHarness.builder()
                    .virtualClock()
                    .scheduler(DeterministicScheduler.withSeed(seed))  // varies interleaving
                    .pipeline(filter(c -> c.getInt(1) > 10).then(project(0, 2)))
                    .build();
            h.feed(FIXED_INPUT);
            h.runToCompletion();
            if (reference == null) reference = h.rawOutput();
            else assertThat(h.rawOutput()).isEqualTo(reference);   // byte-identical
        }
    }
}
```

This is the Sprint 2 demo and the foundation of every correctness claim in the design.

---

## 11. Phases 2–9 — Epics & Acceptance Gates

Each epic is one **wave** (§4.0). Epics are decomposed into stories at the start of their wave; what is fixed now is the **gate** — the machine-checkable condition for merging `develop` into `main`, tagging the milestone, and starting the next wave.

| Wave | Epic | Sprints | Key stories | **Gate** |
|---|---|---|---|---|
| **3** | **E2** Performance core | 6–11 | expression compiler, fusion, Janino, lanes, exchange, adaptive batching, backpressure | Profile A **≥ 1.2 M rec/s/lane**; ≥ 90 % scaling 1→8 lanes; differential tests green; no metaspace leak over 10 000 register/drop cycles |
| **4** | **E3** Stateful & incremental | 12–18 | watermarks + idle detection, timing wheel, tumbling/hopping/session with slicing, L0 off-heap state, RocksDB tier, incremental aggregates + `DISTINCT`, bounded-state enforcement, changelog derivation, DLQ | Profile B **≥ 350 k rec/s/lane**; correctness invariants 1–8 green; an unbounded `GROUP BY` is rejected at planning with a diagnostic naming the key |
| **5** | **E4** Aerospike, joins, durability | 19–25 | Aerospike plugin (4 strategies), expression pushdown, idempotent sink, lookup join, bilinear incremental join, aligned checkpoints, recovery, capability negotiation | Exactly-once state proven by chaos test; Profile C **≥ 120 k rec/s/lane**; pushdown equivalence property green; **W4 ≥ 5× fewer bytes ingested** |
| **6** | **E5** Backfill & serving | 26–32 | snapshot→CDC splice, adaptive throttling, blue/green cutover, served views, 4 consistency modes, read replicas, read admission control | 3 years backfilled with storage p99 impact **< 10 %**; **W3 p99 point lookup ≤ 200 µs**; a SQL change deployed with zero downtime and rolled back |
| **7** | **E6** Gateways, clients, DX | 33–38 | gRPC + Arrow + credit flow control, Avatica, typed Java/Python/Go clients, **`pravaha-server` as a Spring Boot app (modes C/D)**, **`pravaha-spring-boot-starter` (mode B)**, full CLI, `PRV-nnnn` error catalogue, plugin TCK v2, docs-as-tests | Python client sustains **1 M rows/s**; DBeaver connects via Avatica; **W2 deploy ≤ 2 s**; every first-party plugin passes the TCK; server reaches ready in ≤ 2 s; starter green against Spring Boot 3.2, 3.3, 3.4 and 3.5 in the CI matrix |
| **8** | **E7** Survival on one node | 39–45 | **Rescoped by [ADR-035](adr/035-wave-8-is-survival-not-distribution.md).** Node ownership of the checkpoint root and the registry journal (CFG-13, CFG-14); aligned checkpoint barriers, replacing the per-lane control task ADR-008 is currently served by; standby + checkpoint failover, no consensus; the dead-letter queue, L0 state map and changelog analysis made reachable or deleted. Ratis, membership, assignment, rebalance, savepoints, tenant quotas and elastic rescale stay deferred with ADR-034 | A node restarted beside a second node pointed at the same state comes up running its own registrations and no others; a standby takes over from the checkpoint and reports what the takeover lost rather than implying continuity; each of the three mechanisms is reachable from a supported path or gone |
| **9** | **ES** One node, thousands of queries | — | **Inserted by [ADR-036](adr/036-one-node-thousands-of-queries.md).** Lane multiplexing onto a shared runner pool; one watermark/checkpoint clock for the process; arena and inbox sizing as `pravaha.lane.*` settings; an Aerospike `scan.interval.ms`; a descriptor ceiling reported at startup; per-query state published against its ceiling ([ADR-037](adr/037-state-that-degrades-instead-of-dying.md) B1). Deferred within the wave: one Aerospike reader per binding, a lane that multiplexes pipelines, and B2's on-disk tier | `NodeScaleTest` registers thousands rather than hundreds inside a test JVM, and reports cost per query rather than a rate — **no throughput claim is made or accepted here**, because the hardware to measure one does not exist |
| **10** | **E8** Control plane & self-tuning | 46–53 | Spring Boot + React UI, all screens, time-travel debugger, OIDC/RBAC/audit, observability, skew remediation, live replanning, tier promotion | Full lifecycle driven from the UI; **W10** a seeded production bug is found by replay and exported as a passing JUnit fixture |
| **11** | **E9** Breadth, benchmarks, GA | 54–62 | Cassandra/PostgreSQL/Redis plugins, `WITH RECURSIVE`, Nexmark publication, 72 h soak, security review, TCO validation, migration tooling, GA docs | All NFR SLOs met; **W5** ≥ parity on 18/22 Nexmark queries and ≥ 2× on 8; **W6** recursive query runs; **W1 ≤ 40 % vCPU** validated; soak clean; SBOM + security sign-off |

`W1`–`W10` are the win conditions from design §2.5.

---

## 12. Risk-Driven Spikes

Time-boxed investigations that run *before* the story that depends on them, on `spike/*` branches that are never merged. Each produces a written finding in `docs/rfc/`.

| Spike | When | Time box | Question it answers |
|---|---|---|---|
| **S1 — Janino limits** | Sprint 5 | 3 d | How large a generated stage can Janino compile, and where exactly does the JVM stop JIT-compiling it? Sets the method-splitting threshold in P2-04 before we design around a guess. |
| **S2 — Agrona vs FFM parity** | Sprint 3 | 2 d | Are the two `MemoryAccess` implementations within 3 %? If FFM is decisively faster, the Java 25 profile becomes more attractive and §4.5 of the design gets revisited. |
| **S3 — Aerospike ingest reality** | Sprint 8 | 5 d | Measured throughput and latency of XDR→Kafka, XDR→HTTP and LUT-scan against a real cluster. Drives E4's strategy priority and validates the licensing conversation (R1). |
| **S4 — RocksDB native memory** | Sprint 12 | 3 d | Does a shared block cache + write-buffer manager actually bound RSS across 200 column families? Direct test of R4 before we build on the assumption. |
| **S5 — Incremental join state growth** | Sprint 18 | 4 d | Real state size for bilinear joins at target cardinalities. Determines whether E4's join work needs a spilling design from day one. |
| **S6 — Nexmark first look** | Sprint 14 | 3 d | Run whatever subset of Nexmark works at that point. Early bad news on W5 is cheap; late bad news is not (R16). |

---

## 13. Milestones, Demos & Go/No-Go Gates

| # | Wave | Milestone | Sprint | Demo | Decision |
|---|---|---|---|---|---|
| M0 | 1 | It builds | 1 | CI green on 21 and 25 | — |
| M1 | 1 | It's deterministic | 2 | 1 000 identical runs; seeded regression fails CI | **Gate P0 — Wave 1 ends, merge to `main`** |
| **M2** | 2 | **The DBSP bet is real** | **5** | Property oracle catches a seeded lift-rule bug; end-to-end query from `pravaha dev` in < 1 s | **GO/NO-GO on §9 of the design** |
| M3 | 3 | It's fast | 11 | Profile A ≥ 1.2 M rec/s/lane, live | Gate P2 |
| M4 | 4 | It's stateful | 18 | Windowed aggregation with retractions and late data | Gate P3 |
| M5 | 5 | It's durable, on Aerospike | 25 | Kill a node mid-checkpoint; exact recovery | Gate P4 |
| **M6** | 6 | **First defensible demo** | **32** | Incremental compute over Aerospike with pushdown; 3 years backfilled safely; point queries in µs — no other system involved | **External/customer demo** |
| M7 | 7 | It's usable | 38 | Python client at 1 M rows/s; DBeaver; 2 s deploy | Gate P6 |
| M8 | 8 | It survives itself | 45 | A killed node restarts onto its own state; a standby takes over and says what it lost | Gate P7 |
| M9 | 9 | It's operable | 53 | Time-travel debug of a seeded production bug | Gate P8 |
| M10 | 10 | **GA** | 62 | Nexmark numbers published head-to-head | Release 1.0.0 |

### The M2 decision, in detail

Sprint 5's gate is the one that can change the plan's shape, so its criteria are set now, before anyone is invested:

**GO** if all hold: linear lift rules implemented; the property oracle runs ≥ 10 000 generated cases per CI run in ≤ 90 s; a deliberately seeded bug in any lift rule is caught within 100 cases with a minimised counterexample; and the team's honest assessment is that aggregate and join lift rules are tractable within E3/E4's budget.

**NO-GO fallback** if not: fall back to conventional retract-stream operators (design §9.1). Cost: lose W6 (recursion), lose the correctness oracle, lose part of the incremental efficiency claim. Keep: everything else — pushdown, serving, backfill, embeddability, DX. The product remains differentiated on three of five axes. **The fallback costs roughly 3 sprints, taken in E3.** Discovering this in sprint 5 costs 3 sprints; discovering it in sprint 20 costs 15.

---

## 14. Tracking & Metrics

### 14.1 What we track weekly

| Metric | Target | Why |
|---|---|---|
| Sprint goal met | ≥ 80 % of sprints | Goals, not story points, are the unit of progress |
| CI Fast-stage duration | ≤ 6 min | Slow CI silently destroys iteration speed |
| CI flake rate | < 1 % | The determinism work in P0-09 exists to make this achievable |
| JMH baseline regressions | 0 unexplained | Performance is a test |
| Open ADR/RFC review latency | ≤ 48 h | Design review must not be the bottleneck |
| Coverage on core modules | ≥ 85 % line | Gate, not a vanity number |
| Nexmark queries at parity | rising from Sprint 14 | Leading indicator for W5 |

### 14.2 What we deliberately do not track

Story-point velocity as a productivity measure, and lines of code. Both reward the wrong behaviour on a project whose hardest work — the property oracle, the codegen templates, the row layout — is small in volume and large in consequence.

### 14.3 Phase-boundary artefact

Each gate produces a committed evidence pack under `docs/gates/PN/`: benchmark output, test reports, the demo recording, and a one-page retrospective on what the phase got wrong. The retrospective is the input to re-estimating the next phase.

---

## 15. Descope Ladder

If the schedule compresses, cut in this order — decided now, in the calm, rather than in month nine.

| Order | Cut | Saves | Cost |
|---|---|---|---|
| 1 | `WITH RECURSIVE` (E9) | ~2 sprints | Lose W6. Defer to 1.1. |
| 2 | Live replanning (E8, design §18.5) | ~1.5 sprints | Manual replanning via blue/green still works |
| 3 | Migration tooling `import --from flink-sql` (E9) | ~1.5 sprints | Higher switching cost for adopters |
| 4 | Cassandra + Redis plugins (E9) | ~3 sprints | Aerospike + Kafka + PostgreSQL + filesystem ship; others post-GA |
| 5 | Read replicas (E5) | ~1 sprint | Read scaling limited to admission control |
| 5b | All-in-one server+UI mode D (E6) | ~0.5 sprint | UI must be deployed separately; onboarding gets one step longer |
| 6 | Time-travel debugger **UI** (U-13) — ship `pravaha replay` on the CLI instead | ~2 sprints | **W10 survives but weakened.** The capability exists; the demo does not. Cut only under real pressure. |
| 7 | Multi-tenancy quotas (E7) | ~2 sprints | Single-tenant deployments only at 1.0 |

**Never cut, at any pressure:** the property oracle (P1-04), bounded-state enforcement (E3), differential codegen testing (P2-05), checkpoint correctness (E4), the benchmark regression gates, or the console's design system and state discipline (U-01, design §23.12). These are what separate a product from a demo, and every one of them is far cheaper to build than to retrofit.

**On the console specifically:** individual *screens* can be deferred (U-09, U-14, parts of U-12 are the candidates, in that order). The **design system, the shell, and the eight-state discipline cannot** — retrofitting consistency across twenty screens costs more than building them consistently in the first place, and a half-polished UI reads as an unfinished product in a way a missing screen does not.

**Staffing sensitivity.** With **7.5 people** the plan holds at 62 weeks. With **6** (no dedicated frontend engineer) the console degrades to a functional admin UI and design §23.20 is not met — state this openly rather than discovering it at Gate U. With **5**, GA moves to roughly week 72.

---

## Appendix A — Starter POMs

### A.1 Parent `pom.xml` (abridged)

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <properties>
    <maven.compiler.release>21</maven.compiler.release>
    <api.compiler.release>17</api.compiler.release>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <project.build.outputTimestamp>2026-01-01T00:00:00Z</project.build.outputTimestamp>

    <calcite.version>1.40.0</calcite.version>
    <agrona.version>2.2.0</agrona.version>
    <jctools.version>4.0.5</jctools.version>
    <rocksdb.version>9.10.0</rocksdb.version>
    <janino.version>3.1.12</janino.version>
    <slf4j.version>2.0.16</slf4j.version>
    <micrometer.version>1.15.0</micrometer.version>

    <junit.version>5.11.4</junit.version>
    <assertj.version>3.27.0</assertj.version>
    <jqwik.version>1.9.2</jqwik.version>
    <archunit.version>1.3.0</archunit.version>
    <testcontainers.version>1.20.4</testcontainers.version>
    <jmh.version>1.37</jmh.version>
  </properties>

  <modules>
    <module>pravaha-bom</module>
    <module>pravaha-api</module>
    <module>pravaha-common</module>
    <module>pravaha-algebra</module>
    <module>pravaha-catalog</module>
    <module>pravaha-sql</module>
    <module>pravaha-runtime</module>
    <module>pravaha-state</module>
    <module>pravaha-connect</module>
    <module>pravaha-testkit</module>
    <module>pravaha-benchmarks</module>
    <module>pravaha-it</module>
    <!-- added by the story that needs them:
         pravaha-embedded (Sprint 5), pravaha-server + pravaha-spring-boot-starter (E6),
         pravaha-serving/backfill/adaptive (E3–E5), plugins/* (E1, E4, E9), pravaha-ui (E8) -->
  </modules>

  <build>
    <pluginManagement>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-enforcer-plugin</artifactId>
          <executions><execution>
            <id>enforce</id><goals><goal>enforce</goal></goals>
            <configuration><rules>
              <requireMavenVersion><version>[3.9,)</version></requireMavenVersion>
              <requireJavaVersion><version>[21,)</version></requireJavaVersion>
              <banDuplicatePomDependencyVersions/>
              <dependencyConvergence/>
            </rules></configuration>
          </execution></executions>
        </plugin>

        <plugin>
          <groupId>com.diffplug.spotless</groupId>
          <artifactId>spotless-maven-plugin</artifactId>
          <configuration>
            <java>
              <palantirJavaFormat/>
              <importOrder><file>${maven.multiModuleProjectDirectory}/config/spotless/pravaha.importorder</file></importOrder>
              <removeUnusedImports/>
              <licenseHeader><file>${maven.multiModuleProjectDirectory}/config/spotless/license-header.txt</file></licenseHeader>
            </java>
          </configuration>
          <executions><execution><phase>validate</phase><goals><goal>check</goal></goals></execution></executions>
        </plugin>

        <plugin>
          <groupId>org.jacoco</groupId>
          <artifactId>jacoco-maven-plugin</artifactId>
          <executions>
            <execution><id>prepare</id><goals><goal>prepare-agent</goal></goals></execution>
            <execution>
              <id>check</id><goals><goal>check</goal></goals>
              <configuration><rules><rule>
                <limits><limit>
                  <counter>LINE</counter><value>COVEREDRATIO</value><minimum>0.85</minimum>
                </limit></limits>
              </rule></rules></configuration>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </pluginManagement>
  </build>

  <profiles>
    <profile>
      <id>ffm</id>
      <activation><jdk>[22,)</jdk></activation>
      <!-- compiles src/main/java22 into META-INF/versions/22 -->
    </profile>
  </profiles>
</project>
```

### A.2 `pravaha-api/pom.xml` — the strict one

```xml
<project>
  <parent>
    <groupId>com.ash.messaging</groupId><artifactId>pravaha</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </parent>
  <artifactId>pravaha-api</artifactId>

  <properties>
    <!-- widest embeddability: plugin authors and embedders may be on Java 17 -->
    <maven.compiler.release>${api.compiler.release}</maven.compiler.release>
  </properties>

  <!-- NO dependencies. Enforced below, and by review. -->
  <dependencies/>

  <build><plugins>
    <plugin>
      <artifactId>maven-enforcer-plugin</artifactId>
      <executions><execution>
        <id>no-third-party</id><goals><goal>enforce</goal></goals>
        <configuration><rules><bannedDependencies>
          <excludes><exclude>*:*</exclude></excludes>
          <includes><include>*:*:*:*:test</include></includes>
          <message>pravaha-api must have zero compile dependencies (design §7.2)</message>
        </bannedDependencies></rules></configuration>
      </execution></executions>
    </plugin>
    <plugin>
      <groupId>com.github.siom79.japicmp</groupId>
      <artifactId>japicmp-maven-plugin</artifactId>
      <!-- enabled from Sprint 3, once the first tag exists -->
    </plugin>
  </plugins></build>
</project>
```

---

## Appendix B — Sprint 1 Task Checklist

Copy into the tracker. Owner column filled at planning.

- [ ] **P0-01a** Install Maven once, run `mvn -N wrapper:wrapper -Dmaven=3.9.9`, commit `mvnw`, `mvnw.cmd`, `.mvn/`
- [ ] **P0-01b** Parent `pom.xml` — `com.ash.messaging:pravaha`, properties, `<modules>`, `pluginManagement` (Appendix A.1)
- [ ] **P0-01c** `pravaha-bom` with `dependencyManagement` for all third-party versions
- [ ] **P0-01d** 11 skeleton modules, each with a package-info and one placeholder test so the reactor is non-trivial
- [ ] **P0-01e** `.gitignore`, `.gitattributes`, `.editorconfig`, IntelliJ code style
- [ ] **P0-02a** Spotless + `palantir-java-format` + import order + license header
- [ ] **P0-02b** Error Prone + NullAway on `api`, `common`, `runtime`
- [ ] **P0-02c** `maven-enforcer` rules incl. `pravaha-api` zero-dependency rule and the banned-`org.springframework` rule for core modules
- [ ] **P0-02d** JaCoCo with the 85 % gate on core modules
- [ ] **P0-02e** `maven-toolchains-plugin` + `toolchains.xml` sample in `config/`
- [ ] **P0-03a** `fast.yml` — compile + unit tests, ≤ 6 min
- [ ] **P0-03b** `matrix.yml` — full build on JDK 21 and 25
- [ ] **P0-03c** Branch protection on `main` and `develop`; required checks wired
- [ ] **P0-04a** `RowKind`, `PravahaType` hierarchy, `Field`, `StreamSchema`
- [ ] **P0-04b** `RowView`, `RowWriter`, `MutableSlice` interfaces
- [ ] **P0-04c** Exception hierarchy + the `PRV-nnnn` code scaffold
- [ ] **P0-04d** Verify `pravaha-api` compiles at `--release 17` in CI
- [ ] **P0-05a** `MemoryAccess` interface + `AgronaMemoryAccess`
- [ ] **P0-05b** JMH: get/put long ≤ 2 ns, 0 B/op — recorded as the first baseline
- [ ] **P0-06a** `RowLayout`: null bitmap, fixed region, var-len pointers, alignment
- [ ] **P0-06b** `BinaryRowView` / `BinaryRowWriter` over `MemoryAccess`
- [ ] **P0-06c** jqwik round-trip property over generated schemas, all types
- [ ] **P0-12** 18 ADR files under `docs/adr/`, one per design §33 row

**Sprint 1 exit:** a clean clone of `develop` runs `./mvnw clean verify` green on JDK 21 and 25, with no system Maven installed.


---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**<br>
This document is the confidential property of Ashutosh Sinha. Unauthorised copying, disclosure or distribution is prohibited; see `LICENSE`.
Provided "as is", without warranty of any kind.</sub>
