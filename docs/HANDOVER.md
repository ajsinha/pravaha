# Handover

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
Proprietary and confidential; see [`LICENSE`](../LICENSE).

**Written 2026-09-09, updated 2026-09-10 after an overnight autonomous session.** Everything the design says lives in
[`system_design.md`](system_design.md) and the [ADRs](adr/) — this file deliberately does *not*
repeat it. What is here is the state, the working practices, and the things a fresh session would
otherwise have to rediscover the hard way.

---

## 1. Where things stand

| | |
|---|---|
| `main` | `fe2717e`, tags `M1` `M2` — Waves 1 and 2 complete |
| `develop` | **60 commits ahead**, green, *not yet pushed* |
| Modules | **25** |
| Java tests | **1069** (plus 28 Python) — 13 of them against real Aerospike and PostgreSQL servers in Docker |
| Design doc | 33 sections + §11.1a, §13.7, §19.7–19.10 |
| ADRs | **28** |

**Session of 2026-09-09/10 — what changed.** Waves 3 and 4 are **complete** in scope, Wave 5 (E4) is
complete except for what needs hardware or a cluster, and **Wave 6 (E5) has started**.

Wave 5 delivered: computed projections and expressions in `WHERE`; **stream-to-stream joins** end to
end — SQL, runtime, off-heap state with real reclamation, checkpointed on both sides, recovery
proven under a simulated crash, and running across several lanes by routing rows on the join key at
ingest; **filter pushdown** into sources with an equivalence property; an **idempotent sink**; and
**lookup joins** (`JOIN dim FOR SYSTEM_TIME AS OF`) with a JDBC dimension table and lookups
overlapped on virtual threads.

Wave 6 has the **snapshot→CDC splice**, its **adaptive throttle**, and **blue/green cutover with
rollback**, all in the new `pravaha-backfill` module.

Two pre-existing bugs surfaced along the way and are fixed: `WHERE NOT (nullable > 1)` kept rows SQL
says to drop, and a null text column threw in the JDBC decoder — which the polling source shared,
and which no test had ever fed a null string.

**Gate P2 is blocked on hardware, not on code** — see [`gates/wave-3`](gates/wave-3/), read that
first. Three connectors were also built out of wave, at the owner's request: Delta Lake, feed files
(CSV + Parquet drop directories) and JDBC. The JDBC source, its filter pushdown and its dimension
table are now also proven against a real PostgreSQL rather than only H2, which folds identifiers the
other way and hides dialect assumptions.

**A windowed `GROUP BY` now runs end to end**, which is the first time a keyed aggregate has been
allowed at all — every one before this was refused for unbounded state.

**Waves 1 and 2 are done and gated.** Evidence packs and retrospectives are in
[`docs/gates/wave-1`](gates/wave-1/), [`docs/gates/wave-2`](gates/wave-2/) and
[`docs/gates/wave-3`](gates/wave-3/) — read the retrospectives, they are the honest part.

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

Since 2026-09-10 there is also a **runnable lane runtime** — pinned threads, per-lane arenas and
inboxes, a hash exchange between lanes, backpressure that reaches the source plugin, many queries
multiplexed onto one lane, and queries that start interpreted and upgrade to generated code behind
themselves — and **four source connectors**: filesystem, Delta Lake, feed files (CSV and Parquet),
and JDBC.

**The two halves are now joined.** `QueryExecution` compiles a SQL plan onto N lanes, one pipeline
and one arena per lane, fed through the ingest pump from plugin readers — `QueryOnLanesTest` runs a
query across four lanes and checks every row arrives and every lane does some of the work. Before
that, the lane runtime and the SQL path both worked and neither was the engine.

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

**Wave 3 added five more, and three were in the tests rather than the code:**

| What passed vacuously | How it was caught |
|---|---|
| Lane scaling benchmark | Released each round through a phaser, so it measured the slowest thread; reported 33 % efficiency and looked exactly like lane contention |
| Stage-splitting test | Computed its expectation from the constant it was testing; raising the constant raised the expectation |
| Metaspace leak test | 64 MB ceiling guessed from an estimate; seeding a 29.5 MB leak passed. Now calibrated from both measured outcomes |
| Lane exchange, twice | A seeded bug **hung** the build instead of failing it — `@Timeout` interrupts, and a loop spinning on `onSpinWait` never observes an interrupt |
| `AdaptiveStage`'s row-safety claim | The claim was wrong, not the code: atomicity of the assignment provides it, and the double read is a nanosecond misattribution no test here catches |

**Two rules follow, and they are cheap:**

1. **Never leave an unbounded spin in a test.** Deadline-bound every wait and make it say what it
   concluded. A hanging test in CI reads as an infrastructure problem and gets retried rather than
   read.
2. **Never derive a test's expectation from the thing under test**, and never guess a threshold —
   measure both outcomes and put the bar between them.

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

## 3. Wave 3 is done; Gate P2 is not

**Gate:** Profile A ≥ 1.2 M rec/s **per lane**, ≥ 90 % scaling 1→8 lanes, differential tests green,
no metaspace leak over 10 000 register/drop cycles.

| Story | State |
|---|---|
| P2-01 expression compiler, P2-02 fusion, P2-03 Janino, P2-05 differential rig | ✅ (Wave 3, earlier) |
| P2-06 lane model | ✅ `Lane`, `LaneGroup`, `RowInbox` |
| P2-07 hash exchange | ✅ `LaneExchange`, `SpscRowRing` |
| P2-04 method splitting + fallback | ✅ `StageCompilation`, 64 columns per generated method |
| P2-08 adaptive batching | ✅ `BatchingController` |
| P2-09 backpressure | ✅ `IngestPump`, pause/resume to the plugin |
| P2-10 false-sharing audit | ✅ test + benchmark, padding worth 4.1× |
| P2-11 `EXPLAIN codegen` | ✅ CLI `--level codegen`, API `level=codegen` |
| P2-12 lane multiplexing | ✅ `LaneMultiplexer` |
| P2-13 interpreted-first admission | ✅ `AdaptiveStage`, `StageUpgradeService` |

### The gate needs one thing, and it is not code

**Book the reference hardware.** 16 physical homogeneous cores, ≥ 3.0 GHz, quiet. This machine is a
12-physical-core heterogeneous laptop SoC (Zen 5 + Zen 5c) with SMT and frequency scaling, shared
with an IDE and browsers — earlier notes calling it "a shared 24-core box" overstated it, and that is
corrected in `benchmarks/README.md`. On it, a one-lane and an eight-lane measurement are taken at
different clock speeds, so the ratio measures the power envelope as much as the software. **The
2.7× at eight lanes recorded in the benchmarks is not evidence of anything** and is written down only
so nobody re-derives it and believes it.

It blocks Gate P3's Profile B figure too, so it is overdue rather than upcoming.

### Wave 4 (E3) — complete

| Piece | Where |
|---|---|
| Watermarks with **idle detection** | `WatermarkTracker` |
| Event-time timer wheel | `TimerWheel` |
| Window slicing (tumbling, hopping) | `SlicedWindows` |
| Session windows, merge-on-insert | `SessionWindows` — runtime only, no SQL surface |
| Incremental windowed aggregates, bounded | `SlicedAggregateState` |
| `COUNT(DISTINCT …)` in a window | same |
| Windowed `GROUP BY` end to end | `TABLE(TUMBLE(...))`, `HOP` |
| Late data: correct by retraction, or the late output | `WindowedAggregate` |
| Dead-letter queue and rate monitor | `FileDeadLetterQueue`, `DeadLetterRate` |
| Changelog analysis, emit-mode negotiation | `ChangelogAnalysis` |
| L0 off-heap state map | `L0StateMap` |

Gate P3 evidence is in [`gates/wave-4`](gates/wave-4/). **All eight correctness invariants are now
green** — the eighth went green with checkpointing, at the start of Wave 5.

### Wave 5 (E4) — where it is

| Piece | State |
|---|---|
| Checkpoint storage, atomic publish, trailer | ✅ `FileCheckpointStore` |
| State snapshot/restore, lane control path | ✅ `QueryExecution.checkpoint/restore` |
| **Recovery proven**: interrupted run == uninterrupted run | ✅ `CheckpointRecoveryTest` |
| Bilinear join lift, checked against recomputation | ✅ `IncrementalJoin` (algebra) |
| Join in the runtime and SQL | ✅ `SymmetricHashJoin`, `JoinOperator`, `PhysicalPlanBuilder.buildJoin` |
| Join state checkpointed and restored | ✅ both sides in the snapshot; `StreamJoinTest` |
| **Recovery proven for a join**: interrupted run == uninterrupted run | ✅ `JoinRecoveryTest`, crash-not-shutdown, with a duplicate row's weight crossing the interruption |
| Join on the lane runtime, two sources | ✅ lanes have one inbox per input; `JoinOnLanesTest` |
| Join across lanes | ✅ `pumpPartitionedInto` hashes each row's join key and routes it to the lane that owns it, with the same hash the join looks it up with. A plain pump on a multi-lane join is refused, naming the right one |
| Expressions in `WHERE` (`amount * 2 > 100`) | ✅ `Predicate.CompareExpressions` |
| Aligned barriers across the exchange | ❌ — checkpointing is per-lane, which is sound only while lanes share no state; the limitation is written into `QueryExecution.checkpoint` |
| Windowed / time-versioned joins | ❌ — the unwindowed join is bounded only by a row ceiling, which fails the query rather than the node |
| Outer joins | ❌ — refused with the reason: an unmatched row must be held for as long as a match could arrive |
| Self-joins | ❌ — both sides would read one stream and a stream name cannot say which side a row is for |
| Aerospike plugin | ✅ `lut-scan` source with server-side filter pushdown, idempotent sink, lookup table — **tested against a real Aerospike Community server in Docker**, skipped when docker is absent. The three XDR/intercept strategies are refused by name: they need Enterprise XDR, cannot be exercised against Community, and shipping an untested change-feed path would be worse than not shipping one |
| Filter pushdown to sources | ✅ `Pushdown` extracts the pushable conjunction, `ReadRequest` carries it, the JDBC plugin turns it into a bound `WHERE`. The engine keeps its own filter regardless, which is what makes a plugin's partial or absent support harmless |
| Pushdown equivalence, as a property | ✅ `PushdownEquivalenceTest` — a source honouring every pushed filter must return exactly what one honouring none returns |
| Lookup join (enrichment) | ✅ `JOIN dim FOR SYSTEM_TIME AS OF t.ts`, `LookupJoinOperator`, `LookupJoin`, with `JdbcLookupPlugin` so it works against any database with a driver. Lookups overlap on virtual threads, in-flight bounded by what the source declares, output kept in arrival order |
| Idempotent sink | ✅ `DeduplicatingSink` — remembers the highest sequence written, stores it in the checkpoint, drops replays at or below it. Turns an at-least-once sink into effectively-once without asking the sink for anything. **Not yet wired into `QueryExecution`'s checkpoint** |
| Projection / partial-aggregate pushdown | ❌ |

`abort()` versus `close()` is worth knowing before writing any recovery test: `close()` is a
shutdown and emits everything held, `abort()` is what a crash does and emits nothing. A recovery
test that "crashes" by closing gracefully will see every pre-checkpoint window twice, with all the
right numbers — which is how that distinction was discovered.

### Wave 6 (E5) — started

| Piece | State |
|---|---|
| Snapshot→CDC splice | ✅ `pravaha-backfill`: `SplicedReader`, phase-explicit offsets, off-heap change buffer bounded and failing loudly. The dedup is keyed on *changed* keys, not on every key in the snapshot — which is what makes it survivable on a table nobody could hold in memory |
| Adaptive throttling | ✅ `BackfillThrottle`: ceiling, floor, back off fast / recover slowly, pinnable. Governs the history scan only — throttling the change feed would make the query fall behind the present to protect the store from the past |
| Blue/green cutover and rollback | ✅ `ShadowDeployment`: the seam is a frontier, not a moment, so every input record is reflected in exactly one version's output. Rollback is the same swap reversed. Decides *which version's output counts*; it does not run the queries |
| Served views and consistency modes | ✅ `pravaha-serving`: `ServedView` with committed and pending kept apart so a consistent read never sees half a batch; `LATEST`, `CONSISTENT` and `AT_LEAST` implemented, `AS_OF` refused because a view holds the present. Every answer carries its own staleness |
| Read admission control, gRPC/Avatica surface, range indexes | ❌ |

### Deferred, on purpose

`P1-11` Kafka plugin — still deferred, still for the same reason (ADR-028: breadth is not proof).
Aerospike, Cassandra and Redis remain Wave 5 and Wave 10 as planned.

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
