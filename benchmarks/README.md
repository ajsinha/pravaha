# Benchmarks and baselines

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential; see `../LICENSE`.

`baselines/` holds committed JMH results, and **nothing compares a run against them** (PF-2). This
page and `pravaha-benchmarks/pom.xml` both said CI fails the build on a regression greater than
10 %; it does not, and never has. No plugin reads the `benchmarks.skip` property the `bench` and
`all` profiles set, no workflow in `.github/workflows/` runs JMH, and `package` produces a
byte-identical artefact with and without `-Pbench`. A claim about a gate is worth less than no
claim: it stops the next person building the gate.

What does exist, and is run: the measured gates in [`../docs/project/gates/`](../docs/project/gates), whose
harnesses live in `pravaha-it` under `qa/perf` and print the machine's state beside every number.
They are run by hand, on this machine, and a target that is not reached is recorded as not reached.

The intent behind the original claim stands and is worth keeping: retrofitting performance gates
onto an existing codebase does not work, because by the time anyone notices, the regressions are
already in and nobody knows which commit caused them (implementation plan §5.2). Wiring a
comparison is the work PF-2 leaves; committing baselines first was the cheap half and it is done.

## Running

```bash
./mvnw -Pbench -pl pravaha-benchmarks test               # wiring check only, fast
./mvnw -Pbench -pl pravaha-benchmarks package
java -jar pravaha-benchmarks/target/benchmarks.jar -rf json -rff benchmarks/results/run.json
```

Add `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` to include the Agrona arm; without it
`AgronaMemoryAccess.isAvailable()` is false and that arm is skipped (design §4.6).

### Taking a figure: never under the coverage agent (PERF-1)

The root POM attaches JaCoCo to every test JVM by default, and its per-class probe arrays are
written by every thread on every branch: under it, eight lanes measured 1 % of linear where the
same code measured 49 %. So every harness asks `CoverageAgent` (`pravaha-common`) first and
declines under the agent: the JUnit harnesses (`ProfileAGateIT`, `ProfileBGateIT`,
`NexmarkCoverageIT`'s timing half, `RestartCompileIT`, `OperatorMetricsOverheadIT`) skip, naming
it; the JMH benchmarks refuse in their trial setup; and the harnesses that assert counts rather
than times (`NodeScaleTest`, `SourceScaleTest`, `ThousandQueryTest`) keep asserting and mark the
times they print `NOT A FIGURE`. To take a figure, turn the agent off:

```bash
./mvnw -o -pl pravaha-it -am test -Dtest=ProfileAGateIT -Djacoco.skip=true \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -o -pl pravaha-runtime test -Dtest=OperatorMetricsOverheadIT -Djacoco.skip=true \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

and record the load average beside the number, as the harnesses print it.

## Reading the numbers

**What this machine actually is.** Earlier notes in this repository described it as "a shared 24-core
box". That is an overstatement worth correcting, because it changes how much these numbers are worth:

| | |
|---|---|
| CPU | AMD Ryzen AI 9 HX 370 — a **laptop** SoC |
| Cores | **12 physical**, 24 logical (SMT2). Not 24 cores. |
| Core types | **Heterogeneous** — Zen 5 and Zen 5c. Two lanes on two cores are not necessarily two equal lanes. |
| Clock | Frequency-scaled; `lscpu` reported 65 % of nominal while idle-ish |
| Other tenants | An IDE, browsers and other work. Load average 3–13 between runs. |

Consequences, stated once so nobody re-derives them: **single-threaded throughput comparisons are
sound** (same core, same conditions, and that is what the generated-versus-interpreted ratio below
rests on). **Multi-core scaling measurements are not.** An all-core workload on a laptop part drops
clock substantially against a single-threaded one, so a scaling curve here measures the power
envelope as much as the software. Add SMT and two different core designs and the confounds compound.


The reference hardware in the design is 16 physical cores at ≥ 3.0 GHz. Baselines recorded on a
developer workstation are useful as **relative regression detectors** and are not SLO evidence:
throughput numbers are meaningful, but p99.9 and p99.99 on a shared machine are noise. Absolute SLO
validation (design §5.2) needs dedicated hardware and is a Wave 3 activity.

## Files

| File | What it records |
|---|---|
| `memory-access.json` | `MemoryAccessBenchmark` — per-accessor cost for each `MemoryAccess` implementation; JDK 21.0.12, 2026-09-09. History |
| `profile-a.json` | `ProfileABenchmark` — generated against interpreted; JDK 21.0.12, 2026-09-09. History |
| `memory-access-jdk25-2026-10.json` | The same benchmark and settings (1 fork, 3 × 1 s warm-up, 3 × 1 s measured, `bytebuffer`), JDK 25.0.4.1, 2026-10-04 |
| `profile-a-jdk25-2026-10.json` | The same benchmark and settings (2 forks, 3 × 1 s warm-up, 5 × 1 s measured), JDK 25.0.4.1, 2026-10-04. Adds the `predicateOnly` arm, which the JDK 21 file predates |

The JDK 21 files are kept as history and not overwritten. From 2.0 the engine builds and runs on JDK
25 only (ADR-061), so the `-jdk25-` files are the ones to read; the two pairs were taken on the same
machine (below) and differ in the JDK **and** in a month of code, and the class files are Java 25, so
a run on 21 to separate the two is no longer possible.

**`LaneScalingBenchmark` deliberately has no committed baseline.** A baseline is a regression bar CI
enforces, and enforcing one against a number this hardware cannot measure reliably would fail builds
for reasons unrelated to the code. Its results live in this document until they can be taken on the
reference hardware.

## Profile A — generated versus interpreted

Design §28.4's Profile A shape: filter and project over twelve fields, 512-row batches. The
selectivity is **3-4 %, not the 10 % the design specifies and this page used to claim** (PF-13):
`status = 'COMPLETED'` passes one row in three and `amount > 900` one in ten, and 23 of the 512
rows pass both. A narrower filter means fewer rows downstream, so the figure below is flattered by
it. **Measured on JDK 25 (25.0.4.1, ZGC, which is generational only on 25) on 2026-10-04**, on this
machine -- a 12-core laptop part, not the "24-core workstation" this line said -- 2 forks, 3 × 1 s
warm-up, 5 × 1 s measured, load average 1.6–1.9 (`baselines/profile-a-jdk25-2026-10.json`):

| Arm | batches/s | rows/s | error |
|---|---|---|---|
| Generated (fused) | 618 952 | **317 M** | ±2 % |
| Interpreted | 177 940 | 91.1 M | ±1 % |
| Predicate only (interpreted `WHERE`, no projection) | 199 464 | 102 M | ±1 % |

**Roughly 3.5×.** The same benchmark on JDK 21 on 2026-09-09 (`baselines/profile-a.json`) gave
570 510 and 55 971 batches/s -- 292 M against 28.7 M rows/s, ±5 % and ±28 % -- and this page called it
roughly 10×. The generated arm is 8 % faster on 25; the interpreted arm is 3.2× faster, and most of
that is very likely the code rather than the JDK: since 2026-09-26 the interpreted `status =
'COMPLETED'` compares the ASCII literal in place instead of decoding the column for every row
(`CompareString`; gate pack 2026-09-20, re-measured section). The two cannot be separated now that
the class files are Java 25. Generation still wins on the fused operator, by less: the
ten-times figure is retired.

### What this is not

**It is not the Profile A gate figure.** The gate (design §5.2) is 1.2 M records per second per
*lane*, which includes source decode, arena management, ring handoff and sink dispatch. This
benchmark measures the fused operator alone, over rows already materialised in a warm region, on
fixed data that is perfectly branch-predicted. The real pipeline number will be far lower, and
claiming this as the gate would be dishonest.

What it does establish is the *ratio*, which is what the code-generation decision turns on, and both
arms process identical rows and are checked for identical output in setup.

### Two real defects this benchmark found

Neither would have been visible in a unit test.

1. **`setMemory` wrote one byte at a time.** It is called once per row to clear a header and null
   bitmap, so it cost ~50 iterations per row and was the dominant term. Now writes eight bytes at a
   time, with a regression test covering every tail length.
2. **The interpreted string comparison allocated a `String` per row**, and `And`/`Or` allocated an
   iterator per row. Both are now allocation-free. The hot path allocates nothing, and "nothing" has
   to include what the language does on your behalf.

The second matters beyond the number: fixing it made the comparison *fair*, so the two arms now
differ in dispatch cost rather than in algorithm.

## False sharing — what the padding is worth

`FalseSharingBenchmark` answers the question `FalseSharingAuditTest` cannot. The test fails if the
padding fields are deleted; only hardware can say whether they are still separating anything, since
declared padding is not laid-out padding and HotSpot arranges fields as it likes.

Two threads advancing two cursors, on one cache line and a line apart. Measured on JDK 25.0.4.1 on
2026-10-04, the benchmark's own settings (1 fork, 3 × 1 s warm-up, 5 × 2 s), load 1.4; the JDK 21
figures it replaces were 453 M and 110 M:

| Arm | ops/s |
|---|---|
| `padded` | 499 M ± 2 M |
| `shared` | 133 M ± 6 M |

**Roughly 4×** (3.8×), which is the cost of two cross-thread cursors sharing a line — paid on every single
operation of every ring in the engine. Error bars are wide on this hardware; the gap is not.

If the two arms ever converge, either HotSpot has changed its field layout or the padding has
stopped separating the cursors. Both are worth knowing before they surface as a lane count that
stops scaling for no visible reason.

## Lane scaling — the contention benchmark

`LaneScalingBenchmark` is P2-06's acceptance artefact. One JMH thread per lane, each posting rows
only into its own lane's inbox; the row is copied in, drained, batched and summed. The processor
deliberately does almost nothing, because the subject is the **lane machinery** — handoff, batch
loop, arena, counters — and not the operators on it.

Run it as `-t N -p lanes=N`:

```bash
java -jar pravaha-benchmarks/target/benchmarks.jar LaneScalingBenchmark -t 4 -p lanes=4
```

Measured here on JDK 25.0.4.1 on 2026-10-04, `SPIN_THEN_YIELD`, the benchmark's own settings
(1 fork, 3 × 2 s warm-up, 5 × 3 s), load 1.8–3.7, rows per second:

| Lanes | rows/s | vs 1 lane | Efficiency |
|---|---|---|---|
| 1 | 67.6 M ± 4.9 M | — | — |
| 2 | 125.1 M ± 5.2 M | 1.85× | 93 % |
| 4 | 118.5 M ± 120 M | 1.75× | 44 % |
| 8 | 223.5 M ± 32 M | 3.31× | 41 % |

The four-lane error bar is as wide as the figure and the row says nothing. The JDK 21 table this
replaces (wave 3, 2026-09) read 21.3 M, 26.8 M, 35.6 M and 57.8 M -- 63, 42 and 34 %. A single lane is
3.2× faster than it was; the JDK and the lane changes of 2026-09-26 (a lane that has caught up waits
for an eighth of a batch rather than draining six rows at a time; gate pack 2026-09-20) both lie
between the two runs and were not separated.

**These are not evidence for or against the scaling gate, and must not be quoted as either.** The
gate (design §5.2, NFR-2b) is ≥ 90 % efficiency from 1 to 16 lanes and it belongs to P2-07. Three
things make this hardware unable to answer it: an all-core clock well below the single-core boost
clock, two different core designs, and a harness that needs *two* threads per lane — a producer and
the lane — so eight lanes is sixteen busy threads on twelve physical cores. In production the
producers are I/O-bound virtual threads, not saturating spinners. **The scaling number needs the
reference hardware: 16 physical, homogeneous cores at ≥ 3.0 GHz, quiet.**

What the numbers *do* establish: a single lane moves **68 M rows/s** of pure machinery on JDK 25
(21 M on JDK 21 in wave 3), which is more than 50× the 1.2 M rec/s/lane gate. The lane loop is therefore not the thing that will make the
gate hard — the operators, the source decode and the sink dispatch are.

### What the first version of this benchmark got wrong

Worth recording, because it produced a plausible, specific, wrong number. The original harness
released a round of work through a `Phaser` and measured the time until every lane had drained it.
That measures the **slowest thread in each round**: with other work on the machine, one preempted
thread stalls the entire round, and more lanes means more chances to be unlucky. It reported 33 %
efficiency at four lanes and 26 % at eight, looked exactly like lane contention, and survived a
plausible hypothesis — that the per-lane reject counters were false-sharing — being tested and
refuted before the harness itself was suspected.

Removing the barrier changed the four-lane figure from 33 % to 42 % and the shape of the curve. The
lesson is the one this project keeps relearning: **a benchmark is a program and can be wrong in the
same ways as any other program**, and a number with a story attached is the easiest kind to believe.
