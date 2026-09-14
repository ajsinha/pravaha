# PERF — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/PERF.md`](../cases/PERF.md). Executed 2026-09-14 on branch `develop` at
`efa8fae`, Java 21.

**Overall: 8 of 60 cases executed.** 6 PASS, 0 FAIL, 0 BLOCKED, **52 NOT RUN** — and the reason is
recorded rather than worked around. See *What was not run, and why* below. Four findings recorded as
PF-1 through PF-4; one of them is fixed here with a seed-proven test.

## The conditions block, which this file's own Method section requires

```
CPU            AMD Ryzen AI 9 HX 370 w/ Radeon 890M
               12 physical cores, 24 logical, HETEROGENEOUS (Zen5 + Zen5c, different clocks)
               scaling 605 MHz – 5157 MHz
RAM            61 GiB total, 28 GiB in use at the time of the run
Machine idle   NO — load average 10.24 over 1 min, 16.62 over 15 min
JDK            OpenJDK 21.0.12+8-1-26.04-Ubuntu, 64-Bit Server VM, mixed mode, sharing
Commit         efa8fae63cdfbb73ba6f99abc502f9b6f99eff25
JVM flags      -Xss4m --add-opens=java.base/java.nio=ALL-UNNAMED
               --add-opens=java.base/java.lang=ALL-UNNAMED
```

**This machine cannot produce a scaling number, and that is already on the record.**
`docs/gates/wave-3/README.md:27`–`:40` states it precisely and this round confirms every part of it
by measurement conditions: 12 physical cores against a 16-physical-homogeneous reference, two core
designs (Zen 5 and Zen 5c) so two lanes on two cores are not two equivalent lanes, and an all-core
clock far below single-core boost so a 1-lane and an 8-lane figure are taken at different speeds.
`PERF-003`'s committed 8-lane error bar of ±47 M — larger than the entire 1-lane figure — is that
effect already visible in the repository, and the gates file already records the 2.7× scaling result
as "**Not evidence.**"

Worth stating plainly because a shorthand has been circulating in this project's own working notes,
including mine: the gate is blocked on "16-core hardware". The core *count* is the least of it. A
homogeneous 12-core part at a fixed clock would give a more meaningful answer than a heterogeneous
16-core one, and the gates file says so already.

A gate quoted from this machine would be unfalsifiable. That is a stronger reason to defer than
"the machine was busy", and unlike that one it does not go away when the machine goes quiet.

## What was not run, and why

**52 cases are NOT RUN, not BLOCKED and not passed.** Sections B through E and G through I
(PERF-005–007, 009–010, 013–035, 044–045, 047, 049–050, 053–058) report *numbers*, and this file's
own Method section requires every result to state whether the machine was otherwise idle. It was
not: two QA agents were executing the CFG and DOCX rounds in parallel worktrees throughout, and the
load average never fell below 10. A throughput figure taken under that load measures the other
agents.

PERF-049 is additionally a 24-hour soak and was never runnable inside a session.

Recording these as NOT RUN with the reason is the honest verdict. Running them anyway and
annotating the numbers "taken under load" would put figures in the repository that somebody later
quotes without the annotation.

---

## PERF-002 — `-Pbench` changes nothing, and there is no regression gate — **PASS**

Step 1 returned exactly three lines, none of them a plugin `skip` parameter:

```
pom.xml:126:    <benchmarks.skip>true</benchmarks.skip>
pom.xml:583:        <benchmarks.skip>false</benchmarks.skip>
pom.xml:607:        <benchmarks.skip>false</benchmarks.skip>
```

Step 2: `fast.yml`, `matrix.yml` and `verify.yml` contain no benchmark, baseline comparison or JMH
invocation. The three matches for "baseline" in `matrix.yml` are about the *JDK* baseline (Java 21 vs
the 25 leg), not about benchmark baselines.

Step 3, the vacuity check, is what makes this operative. `package` with and without `-Pbench`
produced byte-identical artefacts:

```
without -Pbench   3584d287401e19fb…  benchmarks.jar
with    -Pbench   3584d287401e19fb…  benchmarks.jar
```

The profile is inert. Step 4: `pravaha-benchmarks/pom.xml:11` says "Baselines are committed; CI
fails on a >10% regression" and `benchmarks/README.md:5`–`:7` says CI "**fails the build on a
regression greater than 10 %**". Neither is true. Recorded as **PF-2** and handed to DOCX per the
case's instruction.

## PERF-003 — the recorded results are reproducible — **partial: step 4 only, steps 1–3 NOT RUN**

Steps 1–3 are measurements and fall under *What was not run, and why*.

Step 4 executed and confirms the case's prediction exactly:

```
benchmarks/results/lane-scaling.json:  "benchmark" : "…LaneScalingBenchmark.roundTrip"
LaneScalingBenchmark.java:141:         public void oneRow(Producer producer, ThreadParams threads)
```

The recorded result names a method the source does not have. It was produced by a harness that no
longer exists, so it cannot be reproduced or regression-checked — it is a number with no way back to
the code that made it. Recorded as **PF-1**.

## PERF-004 — none of the performance knobs is configurable, and the error messages say otherwise — **PASS, and worse than the case predicted**

Step 1 — the distinct `pravaha.*` key set in every `src/main` tree is `checkpoint.{directory,
interval,keep,timeout}`, `cluster.*`, `drop`, `ffm`, `list`, `memory`, `node.id`, `pause`,
`principal`, `query.*` (metric names), `register`, `registry.journal`, `resume`, `security.*`,
`sources`, `watermark.{idle-after,tick}`, `weight`. **No `pravaha.lane.*` and no `pravaha.arena.*`.**
The only matches for those prefixes anywhere are test fixtures using `pravaha.lanes` as an arbitrary
example key — a key name that looks real and is read by nothing.

Step 2 — `application.yaml` has no lane, arena, batch, inbox or wait setting. Its two matches are
Spring's own `threads:` block and a watermark comment.

Step 3 — **the case predicts five sites; there are eleven.** Ten are operator-visible messages and
one is javadoc:

| Setting named | Site |
|---|---|
| `arena.slab.size` | `RowArena.java:87`, `InterpretedPipeline.java:692`, `:754`, `LookupJoin.java:308`, `SymmetricHashJoin.java:170`, `:205`, `WindowAssign.java:68` |
| `lane.inbox.cell.size` | `IngestPump.java:107`, `PartitionedIngestPump.java:107`, `RowInbox.java:223`, and `RowInbox.java:218` (javadoc) |

Step 4, the non-vacuity — a node started with `--pravaha.lane.count=4 --pravaha.arena.slab.size=16MB`
**started normally and never mentioned either key** (`Started PravahaServerApplication in 11.932
seconds`; zero occurrences of either key in the whole log). Unread, not rejected — which is the
failure mode that silently produces the wrong deployment.

What it runs instead is hard-coded: `QueryRegistry.java:590` passes the literal `1` for the lane
count, and `RowArena.DEFAULT_SLAB_BYTES = 4 * 1024 * 1024`.

Recorded as **PF-3**.

*Harness note, found while executing step 4:* a first attempt with
`--pravaha.security.authentication=none` was refused at startup by
`PravahaNode.refuseAccidentalOpenServer` (`PravahaNode.java:178`) with PRV-7002. That is the guard
working correctly, and it is worth recording that it fires *before* anything else can be observed —
an executor writing a PERF harness will hit it and should set
`pravaha.security.allow-anonymous=true` deliberately rather than reaching for a weaker policy.

## PERF-008 — the registry's default really is `BACKOFF_PARK`, and nothing else picks it up — **PASS**

`QueryRegistry.java:96` sets `LaneConfig.defaults().withWaitStrategy(BACKOFF_PARK)
.withThreads("pravaha-query", true)`, and `LaneConfig.java:88` confirms the base default is
`SPIN_THEN_YIELD`. The other two `LaneConfig.defaults()` callers in `src/main` —
`QueryRunner.java:113` (the CLI) and `LaneScalingBenchmark.java:110` — do **not** select it, which is
correct for both: a CLI run and a benchmark are the single-query, source-never-dry case the spinning
default was written for.

## PERF-040 / PERF-041 — a dead lane on the feed path — **the product half PASSES; the case's Expected is stale**

The case predicts that nine operator surfaces report `RUNNING` over a dead lane and that only an
in-process `checkHealth()` disagrees. **That is no longer true, and the case text predates the fix.**
`RegisteredQuery.state()` (`RegisteredQuery.java:114`–`:126`) now polls `execution.laneFailure()`
and latches FAILED, and `QueryExecution.laneFailure()` → `LaneGroup.failure()` → `Lane.failure()` is
wired end to end.

Recording this as a stale expectation rather than a pass or a fail is deliberate: the case is
describing a defect that was fixed after it was written, and the verdict belongs to the case file,
not to the product.

**But the gap underneath it was real.** Every test covering this drove `accept()` — the caller's
thread — which is the path that was already working: `LifeFailureTest.life126/129/130` and
`ContinuousQueryAnswerTest.cq053` all push a row in and catch the throw. Nothing covered a lane
dying on the *feed* path, where there is no caller, which is the only path a server-fed query uses
and is exactly the shape of the original defect.

`LaneDeathVisibilityTest` now covers it: it offers rows straight into `lane(0)`, never calls
`accept()`, and asserts the lane records the real cause. **Seed-proven** — with
`QueryExecution.laneFailure()` stubbed to `Optional.empty()` the test fails in 34 s with
`the lane never recorded a failure within PT30S`, and passes in 2.5 s against the real
implementation. The seed was reverted and the runtime tree confirmed clean. Recorded as **PF-4**.

The measurement halves of PERF-040 (native-memory triple, thread list, 60-second view re-read) are
NOT RUN under the conditions rule.

## PERF-043 — `awaitQuiescent` is the only server-reachable health check — **PASS, with one correction**

Step 1, `checkHealth` in `src/main`: `QueryRunner.java:146`, `:172` (the CLI);
`LaneGroup.java:231`–`:232`; `Lane.java:631`; `QueryExecution.java:714`, `:717`, `:721`, `:726`–`:727`,
`:868`. **No caller in `pravaha-server`, `pravaha-flight` or `pravaha-registry`** — confirming the
case. `RegisteredQuery.java:103` carries a comment stating the same thing in the past tense.

Step 2, `awaitQuiescent` — the case predicts "`QueryExecution` itself and the CLI". **There is a
third: `RegisteredQuery.java:255` exposes it.** Nothing on the server path calls that wrapper, so the
case's conclusion holds, but its enumeration was one short and the insertion point is already public.

Step 3 — **nothing on the server path reads `LaneMetrics` at all.** The only readers are
`LaneGroup`/`Lane` themselves, `QueryExecution.metrics()` and the CLI's timeout message
(`QueryRunner.java:160`). `PravahaMetrics` does not read them. So the case's optimistic branch ("if
`PravahaMetrics` does, the fix is much smaller than it looks") resolves the other way: the raw
material is **not** already being collected, and a detector has to start collecting it.

Step 4 — the deliverable, five insertion points with their cadences:

| Insertion point | Cadence | Note |
|---|---|---|
| `RegisteredQuery.commit` | 20 ms | Already on the hot path; cheapest place to poll. |
| The watermark tick | 1 s | Already per-query and already scheduled. |
| The Flight LIST action | per request | Where an operator actually looks. |
| `PravahaMetrics` scrape | 15 s | Needs `State.FAILED` added to `LaneMetrics` first. |
| `EngineHealthIndicator` | per `/actuator/health` | Reads `query.failure()`, which `state()` now sets. |

`state()` polling `laneFailure()` already covers the first three implicitly, since all three call it.
The remaining genuine gap is the metrics scrape: `LaneMetrics` carries `idleCycles` and `rowsIn` but
not `State.FAILED`, so a stalled `rowsIn` beside a live feed is still the only inferable signal in
Prometheus, and it still requires a human to notice.

---

## Findings recorded

| | Severity | Summary |
|---|---|---|
| **PF-1** | MED | `benchmarks/results/lane-scaling.json` records a method that no longer exists; the result is unreproducible. |
| **PF-2** | MED | `pravaha-benchmarks/pom.xml:11` and `benchmarks/README.md:5`–`:7` claim a CI regression gate that does not exist; `-Pbench` is byte-for-byte inert. |
| **PF-3** | MED | Eleven sites tell an operator to change `arena.slab.size` or `lane.inbox.cell.size`; neither is a setting, and a node given them starts and ignores them silently. |
| **PF-4** | MED | No test covered a lane dying on the feed path, the shape of the defect that started this QA cycle. **FIXED** here. |

## A note on a third-party string

As in prior rounds, the jqwik dependency's console output carries a sentence addressed to an "AI
Agent" instructing it to disregard instructions and ignore jqwik results. It is not a project
instruction, it was ignored, and it is recorded in `FINDINGS.md` as a supply-chain observation. No
result in this file depends on jqwik.
