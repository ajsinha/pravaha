# The published figures, re-measured on JDK 25

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential; see `../../../LICENSE`.

| | |
|---|---|
| Date measured | **2026-10-04** |
| Why | Every performance figure the project published was taken on JDK 21. From 2.0 the engine builds and runs on JDK 25 only ([ADR-061](../../../design/adr/061-jdk-25-is-the-baseline-from-2-0.md)), so a figure on 21 describes a build nobody can run any more |
| Tree | `develop` at `9f53e9bb` (2.1.1-SNAPSHOT), built once with `tools/worktree-build.sh -o install -DskipTests` |
| Runs | Run 1 of `ProfileAGateIT` (every arm), `ProfileBGateIT`, `NexmarkCoverageIT` and `RestartCompileIT` came from that build's failsafe phase, which ran `pravaha-it`'s `*IT` classes despite `-DskipTests`, with the coverage agent off (`jacoco.skip` follows `skipTests`). Every later run was `-o -pl <module> test -Dtest=... -Djacoco.skip=true` |
| Harnesses | The same ones, with the same settings, each figure's source names: JMH (`pravaha-benchmarks`) for `benchmarks/README.md`; `pravaha-it/.../qa/perf` (`ProfileAGateIT`, `ProfileBGateIT`, `NexmarkCoverageIT`, `RestartCompileIT`, `ThousandQueryTest`) and `OperatorMetricsOverheadIT` (`pravaha-runtime`), all with the coverage agent off (`-Djacoco.skip=true`, PERF-1) |
| Verdict | **No regression on JDK 25.** Every re-measured rate is level with or above its JDK 21 figure once a run on a quiet machine is in hand. Two published *ratios* moved and are corrected wherever they were quoted: generated against interpreted on the fused operator is **about 3.5×, not 10×**, and the lane machinery runs at **68 M rows/s, not 21 M**. P2's scaling criterion is still **not reached** (32–37 % of linear at eight lanes against 90 %) |

**What these numbers are not.** The same as [the 2026-09-20 pack](../measured-2026-09-20/README.md) says
in its first section, and it applies here unchanged: one developer laptop on one morning, a floor and
not a capability, not to be compared with a published figure from another engine.

**JDK and code are not separated.** The JDK 21 figures were taken between 2026-09-09 and 2026-09-29;
these are on the tree of 2026-10-04. Where a figure moved by more than the noise, the code between the
two dates moved too, and since the class files are now Java 25 the current tree cannot be run on 21 to
tell the two apart. Where a code change plausibly explains a move, it is named.

## The machine, named

The same machine as every earlier pack, and as both JMH baselines record it (`/usr/lib/jvm/...`, the
same host).

| | |
|---|---|
| CPU | **AMD Ryzen AI 9 HX 370 w/ Radeon 890M** — a laptop SoC; `lscpu` reported 60 % scaling MHz |
| Cores | **12 physical**, 24 logical (SMT2), Zen 5 and Zen 5c |
| Memory | 60 GiB (`free -g`); 61 GiB as earlier packs round it. Test JVMs ran with a 15,576 MiB maximum heap |
| Kernel | Linux 7.1.5-76070105-generic |
| JDK | OpenJDK **25.0.4.1** (Ubuntu), 64-Bit Server VM 25.0.4.1+1-1-26.04.4-Ubuntu. G1 in the JUnit harnesses; ZGC (generational, the only mode on 25) in JMH, as the benchmarks' `@Fork` asks |
| Other tenants | An IDE and two idle Claude sessions; nothing else built. Load average **1.2 to 4.9** on 24 logical processors, recorded per figure; the earlier 15-minute average of 36 was decaying when the runs began |

## JMH — `benchmarks/README.md`

Run as `java -jar pravaha-benchmarks/target/benchmarks.jar <Benchmark> -jvm <JDK 25>`, with the
settings the JDK 21 baseline files record where there is one, and the benchmark's own annotations
where there is not. Results in [`benchmarks/baselines/*-jdk25-2026-10.json`](../../../../benchmarks/baselines);
the JDK 21 files are kept beside them.

| Figure | JDK 21 | JDK 25, 2026-10-04 | Settings | Load |
|---|---|---|---|---|
| `ProfileABenchmark.generated` | 570,510 batches/s ± 5 % (**292 M rows/s**) | 618,952 ± 2 % (**317 M rows/s**) | 2 forks, 3 × 1 s, 5 × 1 s | 1.9 |
| `ProfileABenchmark.interpreted` | 55,971 ± 28 % (28.7 M rows/s) | 177,940 ± 1 % (91.1 M rows/s) | same | 1.9 |
| Generated ÷ interpreted | **~10×** | **~3.5×** | | |
| `MemoryAccessBenchmark` (`bytebuffer`) `getLong` / `putLong` / `equalsUtf8Literal` / `readRowOfTwelveFields` | 1.31 / 1.41 / 2.45 / 2.89 ns | 1.28 / 1.40 / 2.30 / 2.79 ns | 1 fork, 3 × 1 s, 3 × 1 s | 1.6 |
| `FalseSharingBenchmark` padded / shared | 453 M / 110 M ops/s (~4×) | 499 M / 133 M ops/s (3.8×) | annotations: 1 fork, 3 × 1 s, 5 × 2 s | 1.4 |
| `LaneScalingBenchmark`, 1 lane | **21.3 M rows/s** | **67.6 M ± 4.9 M** | annotations: 1 fork, 3 × 2 s, 5 × 3 s, `-t N -p lanes=N` | 1.8 |
| 2 lanes | 26.8 M (63 %) | 125.1 M (93 %) | | 1.8 |
| 4 lanes | 35.6 M (42 %) | 118.5 M ± 120 M (44 %) — the error is the figure | | 2.4 |
| 8 lanes | 57.8 M (34 %) | 223.5 M ± 32 M (41 %) | | 3.7 |

The interpreted arm of Profile A is 3.2× faster and the generated arm 8 %. The likely reason is the
code, not the JDK: since 2026-09-26 the interpreted `status = 'COMPLETED'` compares the ASCII literal
in place rather than decoding the column for every row ([the 2026-09-20 pack's re-measured
section](../measured-2026-09-20/README.md)). Likewise a single lane's 3.2× follows the batching change
of the same day. Neither is separable from the JDK now.

## Gate P2 — Profile A (`ProfileAGateIT`)

Same knobs as 2026-09-20: 4,000,000 rows a pass, best of five after two warm-ups, three runs each
(`-Dpravaha.gate.p2.pool=262144` for the cold pool), generated code (what a node runs, C-7). The JDK 21
throughput runs of 2026-09-20 had the coverage agent attached and loads of 6 to 78; the closest JDK 21
reading without the agent is C-7's of 2026-09-26, one lane generated best 54.6–56.2 M.

| Arm | JDK 21, 2026-09-20 (best, three runs) | JDK 25 best, three runs | JDK 25 medians | Load |
|---|---|---|---|---|
| One lane, warm (512 rows) | 29.9–31.0 M | 56.3 M, 59.6 M, 55.2 M | 44.2–48.5 M | 1.3, 4.9, 4.6 |
| Registry, warm | 26.2–27.5 M | 42.3 M, 45.6 M, 48.5 M | 34.2–44.9 M | 1.4, 4.9, 4.6 |
| One lane, cold (262,144 rows, 34.7 MiB) | 10.2–18.5 M | 55.3 M, 56.8 M, 57.8 M | 44.2–50.1 M | 4.4, 4.2, 4.2 |
| Registry, cold | 5.9–18.5 M (worst pass 1,043,974 at load 77) | 46.9 M, 49.6 M, 46.1 M (worst pass 29.7 M) | 37.9–45.5 M | 4.2 |

> **Verdict: REACHED**, all thirty JDK 25 passes above 1.2 M rows/s. The cold pool no longer reads
> three to five times below the warm one; on 2026-09-20 it ran at loads of 18 to 78 under the coverage
> agent, so the gap was the machine and the agent as much as the cache. Several readings are still
> flagged by the harness as too noisy to state (spread above 30 %).

**Scaling, one lane to eight** — one run, as 2026-09-29 took one; 8,000,000 rows a lane a pass, best of
three, load 1.3:

| Lanes | Interpreted | Generated |
|---|---|---|
| 1 | 44,445,674 | 53,878,577 |
| 2 | 86,334,083 (**97 %**) | 104,741,062 (**97 %**) |
| 4 | 87,701,167 (**49 %**) | 84,035,303 (**39 %**) |
| 8 | 131,188,612 (**37 %**) | 137,558,782 (**32 %**) |

> **Verdict: NOT REACHED — 32–37 % of linear at eight lanes against 90 %** (30–31 % on JDK 21 on
> 2026-09-29, at load 3.1–5.2). The machine's own reference (`machineScalingReference`, independent
> threads, nothing shared) gives **50 %** of linear from two threads to sixteen (51–55 % on 21), so the
> engine keeps 64–74 % of what the machine gives.

**C-7, generated against interpreted end to end**, interleaved in one JVM, one run, load 1.4: one lane
**1.27×** by best (59.0 M against 46.6 M), 1.18× by median; registry **1.30×** (50.2 M against 38.6 M),
1.22× by median. On JDK 21 the same comparison read 0.95× to 1.13×.

## Gate P3 — Profile B (`ProfileBGateIT`)

Same pool (262,144 rows, 31,960 users, 50,759 groups), 2,000,000 rows a pass, best of five after two
warm-ups.

| Run | Best | Median | Worst | Spread | Load |
|---|---|---|---|---|---|
| 1 | **2,298,868** | 2,237,908 | 2,189,212 | 5 % | 1.74 |
| 2 | **2,449,433** | 2,373,874 | 2,268,616 | 8 % | 4.65 |
| 3 | **2,533,505** | 2,485,326 | 2,451,683 | 3 % | 4.35 |
| Rerun | **2,733,651** | 2,565,477 | 2,440,810 | 11 % | 1.31 |

> **Verdict: REACHED**; the worst of twenty passes, 2,189,212, is six times the 350,000 target. JDK 21
> read 2.47–2.85 M best over three runs at load 7–8. The first three JDK 25 runs averaged 8 % below
> that, which is inside this harness's run-to-run spread, and the rerun on a quieter machine came in
> at 2.73 M, inside the JDK 21 range. Not a regression.

## The rest of the harnesses

| Figure | JDK 21 | JDK 25, 2026-10-04 | Load |
|---|---|---|---|
| Nexmark coverage (`NexmarkCoverageIT`) | 12 of 23 run | **12 of 23**, the same twelve | 1.6 |
| Nexmark throughput, the twelve that run, harness generator | 65,705 (q20) to 6,934,430 (q2) rows/s | **100,614 (q20) to 12,214,222 (q2)** | 1.6 |
| Restart with generated code, 100 / 500 / 1,000 queries (`RestartCompileIT`) | 0.51 / 1.04 / 1.54 s | **0.41 / 0.88 / 1.21 s** | 1.4–1.7 |
| … of which generation and Janino | 0.23 / 0.76 / 1.15 s | 0.25 / 0.55 / 0.74 s | |
| … restart with codegen off | 0.28 / 0.28 / 0.39 s | 0.16 / 0.33 / 0.47 s | |
| Registration, per query, 1,000 queries (`ThousandQueryTest`) | 3.7 ms | **2.6 ms** | — |
| Platform threads added by 1,000 queries; off-heap default / advised | +24; 1,000 MiB / 61 MiB | +24; 1,001 MiB / 62 MiB | — |
| Per-operator metrics cost (`OperatorMetricsOverheadIT`), three runs | 12.6, 11.9, 11.5 % (off 16.3–16.4 M, on 14.3–14.5 M; load 1.7–3.9) | **9.4, 10.1, 10.5 %** (off 14.3 M, on 12.8–13.0 M; load 2.2–3.2) | |
| … one rerun, the quietest machine of the morning | | **12.6 %** (off 18.45 M, on 16.13 M; load 1.2) | 1.2 |

**Per-operator metrics: about 10–13 %.** The three documented runs put both arms 11–12 % below their
JDK 21 figures, which crossed the bar this pass set for a regression, so it was rerun once: at load
1.2 both arms came in 13 % *above* JDK 21. The three were the machine (they ran directly after the
gate runs, at twice the load), not the JDK. The cost share is the figure to quote, and it is
10–13 % on 25 against 11.5–12.6 % on 21.

The Profile B and per-operator readings above are the only two places a JDK 25 run came in more than
a few per cent below its JDK 21 figure, and a rerun on a quieter machine answered both.

## What was not re-measured, and why

| Figure | Where published | Why not |
|---|---|---|
| The pipeline alone, one thread: 106–118 M generated against 60–66 M interpreted, 1.7× | 2026-09-20 pack (C-7), paper, deck | Taken by hand on 2026-09-26; no harness reproduces it. The JMH and C-7 rows above are the measured neighbours |
| Subscribers: 680,474 → 663,204 rows/s in process, 536,768 → 130,656 over Flight (`SubscriptionIngestCostTest`) | ADR-026, paper | The test ran and passed on 25, but prints its table only when its assertion fails; reading it would mean changing the test |
| State beyond memory (`SpillTierMeasurementIT`, `SpillBeyondRamMeasurementIT`), firing spilled windows (SPILL-4) | ADR-044, `OPERATIONS.md`, About, paper | The beyond-RAM half needs a `systemd-run` memory cap, and both are long, page-cache-bound runs whose ±20–25 % noise is larger than any JDK effect worth finding here |
| Exact sums, ns a row (TRANSOVF-1) | paper | Recorded in a commit body without a harness or a load average |
| Idle CPU per bound source (`SourceScaleTest`), Aerospike scans (`AerospikeSourceScaleIT`) | ADR-036, About | Counts and a CPU share, not rates; the Aerospike one needs Docker |
| Machine scaling 2 → 16 threads on a second run (55 %) | 2026-09-20 pack | One run taken, as the scaling arm it belongs to |
