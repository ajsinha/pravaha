# JDK 21 against JDK 25, the same jar

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential; see `../../../LICENSE`.

| | |
|---|---|
| Date measured | **2026-10-04**, 17:40–17:42 |
| Why | [ADR-062](../../../design/adr/062-java-21-or-later.md) makes Java 21 the floor again. The JDK 25 pack ([measured-2026-10-04-jdk25](../measured-2026-10-04-jdk25/README.md)) could not separate the JDK from a month of code; with Java 21 class files one jar runs on both, so this one can |
| Tree | The ADR-062 branch at `4c1e6ee1` (2.2.1-SNAPSHOT, Java 21 class files), built once on JDK 21 with `tools/worktree-build.sh -o install -DskipTests`; one `pravaha-benchmarks/target/benchmarks.jar` for every run below |
| Runs | `java -jar benchmarks.jar <Benchmark> -jvm <JDK>` with the settings the `-jdk25-2026-10` baselines record: Profile A 2 forks, 3 × 1 s warm-up, 5 × 1 s measured; MemoryAccess `-p implementation=bytebuffer`, 1 fork, 3 × 1 s, 3 × 1 s. JDK 21 first, then JDK 25, back to back |
| JDKs | OpenJDK **21.0.12.1** and **25.0.4.1** (Ubuntu). `-XX:+UseZGC`, as the benchmarks' `@Fork` asks: **non-generational ZGC on 21** (its default), generational on 25 (the only mode) |
| Machine | The one every pack names: AMD Ryzen AI 9 HX 370, 12 cores / 24 threads, 60 GiB. Load average 1.2–2.4 throughout |
| Files | [`benchmarks/baselines/*-jdk21-2026-10.json`](../../../../benchmarks/baselines) (the JDK 21 runs). The JDK 25 runs of this jar are quoted here and not committed: the `-jdk25-2026-10` files stay the 25 baseline |
| Verdict | **No regression that this harness can see.** The generated (fused) path, which is what a node runs, is level: 631 k batches/s on 21 against 586 k on 25 this run and 619 k in the 25 baseline. The interpreted arms read 16 % lower on 21 with a ±31 % error, so the difference is inside the noise of the 21 runs. The memory accessors are level or faster on 21 |

**What these numbers are not.** One laptop, one afternoon, a few minutes per benchmark: a regression
detector, not a capability, as every earlier pack says. No gate is set on them.

## Profile A (`ProfileABenchmark`), batches of 512 rows, batches/s

| Arm | JDK 21 (this jar) | JDK 25 (this jar) | JDK 25 baseline, 2026-10-04 (`9f53e9bb`) |
|---|---|---|---|
| Generated (fused) | **630,864 ± 1 %** (323 M rows/s) | 586,418 ± 10 % (300 M rows/s) | 618,952 ± 2 % (317 M rows/s) |
| Interpreted | 149,966 ± 31 % (76.8 M rows/s) | 179,003 ± 1 % (91.6 M rows/s) | 177,940 ± 1 % (91.1 M rows/s) |
| Predicate only | 169,099 ± 31 % (86.6 M rows/s) | 200,947 ± 0.3 % (103 M rows/s) | 199,464 ± 1 % (102 M rows/s) |

The interpreted arms on 21 came with a 31 % error -- one of the two forks read well below the other --
so "16 % slower" is a reading, not a measurement; a longer run would be needed to call it. The
generated arm on 25 this time carried a 10 % error and the baseline's 2 %, so its 7 % below 21 is noise
as well. What holds is that the path a node runs is not slower on 21.

## MemoryAccess (`MemoryAccessBenchmark`, `bytebuffer`), ns/op, lower is better

| Accessor | JDK 21 (this jar) | JDK 25 (this jar) | JDK 25 baseline |
|---|---|---|---|
| `getLong` | 1.06 ± 0.24 | 1.29 ± 0.24 | 1.28 |
| `putLong` | 1.08 ± 0.25 | 1.37 ± 0.13 | 1.40 |
| `equalsUtf8Literal` | 2.13 ± 0.15 | 2.27 ± 0.25 | 2.30 |
| `readRowOfTwelveFields` | 2.38 ± 0.87 | 2.80 ± 0.61 | 2.79 |

Level or faster on 21, within the errors of single-fork runs.

## Not re-run

The JUnit gates of the JDK 25 pack (`ProfileAGateIT`, `ProfileBGateIT`, `NexmarkCoverageIT`,
`RestartCompileIT`, `ThousandQueryTest`) and the lane-scaling JMH arms were not repeated on 21: this
is the reduced run, the two JMH baselines the brief named. Those the whole-reactor build runs (the
perf gates skip themselves under the coverage agent) ran on 21 as ordinary tests, which says they
pass there and nothing about their figures.
