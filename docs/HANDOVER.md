# Handover

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
Proprietary and confidential; see [`LICENSE`](../LICENSE).

**Written 2026-09-09, at the end of a long working session.** Everything the design says lives in
[`system_design.md`](system_design.md) and the [ADRs](adr/) — this file deliberately does *not*
repeat it. What is here is the state, the working practices, and the things a fresh session would
otherwise have to rediscover the hard way.

---

## 1. Where things stand

| | |
|---|---|
| `main` | `fe2717e`, tags `M1` `M2` — Waves 1 and 2 complete |
| `develop` | 6 commits ahead, **green**, all pushed |
| Modules | 19 |
| Java tests | **654** (plus 28 Python) |
| Design doc | v3.10, 33 sections |
| ADRs | 24 |

**Waves 1 and 2 are done and gated.** Evidence packs and retrospectives are in
[`docs/gates/wave-1`](gates/wave-1/) and [`docs/gates/wave-2`](gates/wave-2/) — read the
retrospectives, they are the honest part.

**Wave 3 (E2, the performance core) is roughly half done.** See §3.

### What actually works today

SQL runs end to end. `docs/QUICKSTART.md` is accurate and every command in it is executed by
`ExamplesTest`, so it cannot silently rot.

```
SQL → Calcite (parse, validate, optimise) → PhysicalPlanBuilder → Pravaha's operator tree
    → interpreted execution over off-heap binary rows → filesystem sink
```

Plus: the `pravaha` CLI (`validate`, `explain`, `run`, `version`), a Spring Boot server with the
public REST API and a plain `/status` page, the Java and Python SDKs, and whole-stage code
generation measured at ~10× the interpreted path.

---

## 2. Working practices — please keep these

These were learned the hard way in this session and each one has already paid for itself.

### Seed a bug before trusting a test

**Five times** a test passed while proving nothing. Every one was found by deliberately breaking the
code and checking the test noticed:

| What passed vacuously | How it was caught |
|---|---|
| ArchUnit suite | Imported zero classes; every rule passed on an empty set |
| Row round-trip property | Wrote every row at offset 0, where absolute and relative offsets are identical |
| MPSC contention test | Asserted a timing-dependent property; flaky on an idle machine |
| Differential codegen test | Never compared against a *nullable* column, so a dropped null check went undetected |
| The quickstart | Documented a plausible number (1243 µs) that was wrong by 1600× |

**The practice: for anything non-trivial, plant a bug, confirm the test fails, restore.** It costs
minutes. Each of the last three defects was found *by* the practice rather than despite it.

### Run the full verify before pushing

`develop` was pushed red **twice**, both times by reading a summary line before `./mvnw clean verify`
finished. Wait for the exit status.

### Correct the design when the code disagrees with it

Three times the build contradicted a written claim, and each was corrected **in place** rather than
quietly dropped:

- Agrona is not flag-free on Java 21 (needs `--add-exports`); it therefore cannot be the default,
  for a product reason — an embedded engine inherits its host's launch arguments.
- Calcite's default type system silently truncates `DECIMAL` beyond 19 digits and rounds `TIMESTAMP`
  to milliseconds.
- `Predicate` as a functional interface could be evaluated but not *inspected*, so the code
  generator had nothing to generate from.

### Documentation rot is a build failure

`DocumentationFreshnessTest` and `ExamplesTest` check mechanically. If you change a module name, a
wave, or a documented output, the build tells you.

---

## 3. Wave 3 — what is done and what is next

**Gate:** Profile A ≥ 1.2 M rec/s **per lane**, ≥ 90 % scaling 1→8 lanes, differential tests green,
no metaspace leak over 10 000 register/drop cycles.

| Story | State |
|---|---|
| P2-01 expression compiler | ✅ `PredicateSource` |
| P2-02 fusion + templates | ✅ `FilterProjectGenerator` |
| P2-03 Janino pipeline | ✅ `StageCompiler`, per-stage classloader |
| P2-05 differential rig | ✅ and verified non-vacuous |
| **P2-06 lane model** | ❌ **next, and the critical path** |
| **P2-07 hash exchange** | ❌ next |
| P2-04 method splitting | ❌ threshold exists, splitting does not |
| P2-08 adaptive batching | ❌ |
| P2-09 backpressure | ❌ |
| P2-10 false-sharing audit | ❌ |
| P2-11 `EXPLAIN codegen` | ❌ |

### Start here

**P2-06, the lane model.** Everything needed already exists: `RowArena`, `MpscLongRing`,
`WaitStrategy`, `FusedStage`, `InterpretedPipeline`. The lane is the thing that assembles them:
one pinned thread, one input ring, one arena, one processor chain, a batch loop.

That is also what converts the current *operator* number into the *pipeline* number the gate asks
for. **The 292 M rows/s in `benchmarks/README.md` is the fused operator alone over pre-materialised
rows — it is explicitly not the gate figure**, and that distinction is written down in two places so
it does not get quietly promoted.

### Deferred from Wave 2, on purpose

`P1-11` Kafka plugin. The filesystem plugin exercises every part of the SPI the slice needed; Kafka
is breadth rather than proof.

---

## 4. Things a fresh session will not guess

### Environment

| | |
|---|---|
| **Always `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`** | `java` on `PATH` resolves to 25 and `javac` to 21. Unexported, the wrapper picks the wrong one and `--release 21` fails confusingly. |
| Python | 3.13 under `~/.local/share/uv/python/cpython-3.13.15-linux-x86_64-gnu/bin` |
| Maven | Use `./mvnw`. A system Maven exists but the wrapper is the contract. |
| JMH | Never run `clean` while a benchmark is running — it deletes the jar mid-flight. Clear `/tmp/jmh.lock` if a run was killed. |
| Benchmarks | This is a shared 24-core box. Throughput numbers are meaningful; p99.9 is noise. Absolute SLO validation needs dedicated hardware. |

### Conventions that matter

- **Cross-references:** bare `§N` means *this* document; `design §N` means the other one. A CI check
  resolves both directions.
- **Error codes** are `PRV-nnnn`, ranged by subsystem, never renumbered. Add to the relevant
  `*Errors` class.
- **Commit messages** explain *why*, including what was wrong and what was learned. No Claude
  attribution anywhere — the history was rewritten once to remove it; do not reintroduce it.
- **Waves:** `develop` moves continuously; `main` moves **once per wave**, at a gate, with an
  evidence pack and a retrospective. Never merge to `main` mid-wave.

### Places where the obvious thing is wrong

| | |
|---|---|
| `MpscLongRing.EMPTY` is `Long.MIN_VALUE`, not `-1` | `-1` is `ArenaHandle.NULL` |
| Row payload offsets are **relative to the row** | So a row survives being copied — which is what checkpointing does |
| Rows are flyweights into arena memory | Valid only while the arena lives. Anything outliving it must copy. |
| `pravaha-sql` depends on `pravaha-runtime`, not the reverse | The plan IR is the contract; Calcite must not reach the runtime |
| Byte order is pinned little-endian | The layout is a persisted format |

---

## 5. Open questions for the owner

Unchanged from design Appendix B, and none of them block Wave 3:

1. **Which Aerospike edition** is available in the target deployment. Community Edition has no
   change feed at all; this determines whether the flagship exactly-once path exists. It has
   procurement lead time — worth settling early. (R1)
2. **Scope versus schedule.** 62 weeks for 7.5 people. If that is not acceptable, pick from the
   descope ladder now rather than in month nine. (R14)
3. **Staffing the console.** With 6 rather than 7.5 people it degrades to a functional admin UI and
   design §23.20 is not met. That is a legitimate trade to make deliberately.

Two waves in, the plan has **under-counted adjacent work both times** rather than over-estimated.
Wave 3 contains the code generator, the highest-risk item (R2). Do not revise its estimate down.

---

## 6. Recent decisions worth knowing about

Made late in the session, so they may not be reflected everywhere yet:

- **ADR-024:** the console is a **separate Python FastAPI process** built on the published SDK. A
  different runtime makes the API boundary unviolable, and it makes the console a continuously-
  exercised proof of the integration story.
- **ADR-025:** registration and subscription are separate objects; sharing is by canonical
  fingerprint including *security predicates*, never by SQL text — text-hash sharing leaks data
  across security contexts.
- **ADR-026:** one subscription model behind three carriers (gRPC, WebSocket, SSE); **encode once,
  write N times**.
- **Licensing is proprietary**, wholly owned. Not Apache 2.0 — an earlier revision proposed that and
  design §30.4 was rewritten rather than word-swapped.

### Not yet built from those decisions

The FastAPI console. The order agreed was **API first, then the console** — the API exists
(`pravaha-server`, contract locked in `api/openapi.lock.json`), so the console is unblocked. It
should be built on the Python SDK, which currently has only the connection contracts and will need
the client calls added first. **That ordering is the point:** if the console can reach past the SDK,
the dogfooding benefit evaporates.

---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**</sub>
