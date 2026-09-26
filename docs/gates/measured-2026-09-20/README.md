# Gates P2, P3 and ADR-038's Nexmark comparison — measured on the machine we have

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential; see `../../../LICENSE`.

| | |
|---|---|
| Date measured | **2026-09-20** |
| Why now | There is no reference hardware and there is not going to be any. The owner's instruction on 2026-09-19 was to measure on the machine that exists and report every number with the machine named |
| Harness | `pravaha-it/src/test/java/com/ash/messaging/pravaha/it/qa/perf/` — `ProfileAGateIT`, `ProfileBGateIT`, `NexmarkCoverageIT`, with `MachineState` and `GateReading` |
| Verdict | **P2's throughput criterion is reached. P2's scaling criterion is not reached: 28–42 % against a target of 90 %. P3's throughput criterion is reached. W5's Nexmark comparison is not reached and cannot be run here: 5 of 23 queries run at all** |

## The one thing a reader must not conclude

**These numbers are not this engine's performance.** They were taken on a developer laptop that was
running other build agents and containers throughout, and the load average is printed beside every
one of them because at times it was above 70 on 24 logical processors. A number taken that way is a
statement about one machine on one afternoon. It is a **floor** — the engine did at least this much,
under this much interference — and it is not a ceiling, not a capability, not an SLO and not
something to put in front of anybody outside this project.

Three specific things a reader must not do with them:

1. **Do not compare them with a published figure from Flink, RisingWave or Feldera.** Those are
   taken on quiet, dedicated, homogeneous hardware with the reference data generator. Comparing
   across those conditions is the selective benchmarking design §28.4 warns about.
2. **Do not read the scaling ratio as a property of the software.** This machine has twelve physical
   cores of two different designs, SMT2, and one frequency envelope shared across all of them; the
   harness needs two threads per lane, so eight lanes is sixteen busy threads on twelve cores before
   anything else is counted. Gate P2 has said this since wave 3 and it is still true.
3. **Do not quote the warm-cache Profile A figure as the Profile A figure.** It is three to five
   times what the same harness produces over a working set too large for cache. Both are recorded
   below for exactly that reason.

## The machine, named

| | |
|---|---|
| CPU | **AMD Ryzen AI 9 HX 370 w/ Radeon 890M** — a laptop SoC |
| Cores | **12 physical**, 24 logical (SMT2), two designs (Zen 5 and Zen 5c), frequency-scaled |
| Memory | 61 GiB; the test JVM ran with a 15,640 MiB maximum heap (surefire's default) |
| JDK | OpenJDK **21.0.12** (Ubuntu), OpenJDK 64-Bit Server VM 21.0.12+8, **G1** |
| Other tenants | Other build agents, the lead's full Maven gate and containers. Load average between **5.8 and 77.8** across the runs below, recorded per measurement |

## Gate P2 — Profile A

Design §5.2 NFR-2a: **≥ 1,200,000 rec/s per lane-core** on Profile A (§28.4: filter and project,
twelve fields, ~200 B). Design §5.2 NFR-2b as gate P2 states it: **≥ 90 % scaling efficiency from
one lane to eight**.

**The targets are design §5.2's and have not been moved.** ADR-042 restated the *product
requirement* to about a thousand rows a second and kept both figures on the page; it explicitly did
not close P2. Measuring against the smaller number would be the thing this project keeps saying it
will not do, so the harness carries 1.2 M and prints `NOT REACHED` with the figure when a run misses.

### Throughput — reached

Best of five timed passes after two warm-ups; three independent runs. The verdict is taken against
the **best** sample deliberately: it is the most generous reading repeats admit, so a miss cannot be
answered with "the machine was busy".

**Warm pool** — 512 distinct rows, about 100 KiB, which lives in L2 and never leaves it:

| Arm | Run | Best | Median | Worst | Spread | Load |
|---|---|---|---|---|---|---|
| One lane | 1 | **30,972,947** | 26,136,705 | 12,538,319 | 71 % | 6.46 |
| One lane | 2 | **29,974,176** | 29,694,542 | 19,935,507 | 34 % | 9.19 |
| One lane | 3 | **29,928,223** | 29,660,201 | 29,113,120 | 3 % | 8.40 |
| Registry (SQL + served view) | 1 | **27,214,330** | 21,947,666 | 8,976,500 | 83 % | 6.75 |
| Registry | 2 | **27,528,965** | 25,517,405 | 20,156,236 | 29 % | 8.62 |
| Registry | 3 | **26,219,229** | 23,242,339 | 10,303,932 | 68 % | 7.97 |

**Cold pool** — 262,144 distinct rows, 34.7 MiB, which does not fit in any cache on this part. Runs
2 and 3 were taken while the lead's full Maven gate was running and the load average was above 45:

| Arm | Run | Best | Median | Worst | Spread | Load |
|---|---|---|---|---|---|---|
| One lane | 1 | **11,076,061** | 7,868,414 | 4,585,547 | 82 % | 18.64 |
| One lane | 2 | **10,247,591** | 3,679,223 | **1,213,619** | 246 % | 77.80 |
| One lane | 3 | **18,481,136** | 9,658,172 | 6,912,454 | 120 % | 55.11 |
| Registry | 1 | **5,864,020** | 3,820,341 | 3,585,301 | 60 % | 23.54 |
| Registry | 2 | **17,919,181** | 11,827,459 | 1,043,974 | 143 % | 77.04 |
| Registry | 3 | **18,508,045** | 10,863,002 | 9,743,504 | 81 % | 46.50 |

> **Verdict: REACHED**, and the verdict survives the noise even though the figures do not. Most of
> these readings are flagged by the harness as too noisy to state as a figure — a 246 % spread is a
> statement about the machine and nothing else — but all thirty warm passes and twenty-nine of the
> thirty cold ones came in above 1.2 M rows a second, and the thirtieth was a near miss at load 77.
> **The one figure worth carrying forward is that miss: 1,043,974 rows/s, on the cold pool, through
> the registry, on a machine at load 77.** That is where the target actually sits relative to this
> machine when the machine is fully occupied, and it is a more useful number than the best one.

**What the arms mean.** The one-lane arm is the gate's own sentence: inbox handoff, batch loop,
arena, the interpreted filter and project, sink dispatch. The registry arm adds the planner's plan,
the registry and a served view, and is what a deployment sees.

### Scaling, one lane to eight — not reached

One single-lane query per lane with its own producer thread; 8,000,000 rows per lane per pass; best
of three passes per lane count. Efficiency is the rate at *N* lanes over *N* times the rate at one.

**Runs 1–3, lane counts in a fixed order** (loads 6.9, 9.8, 8.9):

| Lanes | Run 1 | Run 2 | Run 3 |
|---|---|---|---|
| 1 | 28,641,186 | 30,678,741 | 25,392,109 |
| 2 | 35,806,517 (**63 %**) | 36,350,238 (**59 %**) | 40,816,897 (**80 %**) |
| 4 | 66,937,234 (**58 %**) | 50,391,734 (**41 %**) | 54,587,315 (**54 %**) |
| 8 | 85,906,168 (**37 %**) | 68,729,791 (**28 %**) | 80,497,004 (**40 %**) |

**Runs 4–6, lane counts interleaved** (loads 26.3, 22.7, 19.0 — the lead's full gate was running):

| Lanes | Run 4 | Run 5 | Run 6 |
|---|---|---|---|
| 1 | 27,159,343 | 22,879,117 | 28,434,371 |
| 2 | 42,296,959 (**78 %**) | 32,335,085 (**71 %**) | 50,196,363 (**88 %**) |
| 4 | 46,188,158 (**43 %**) | 38,578,848 (**42 %**) | 47,656,268 (**42 %**) |
| 8 | 87,841,193 (**40 %**) | 72,345,222 (**40 %**) | 64,985,705 (**29 %**) |

**Runs 7–8, interleaved, on the quietest machine the day offered** (loads 11.4 and 11.2, below the
threshold at which the harness withholds its verdict — these are the only two readings here it did
not withhold):

| Lanes | Run 7 | Run 8 |
|---|---|---|
| 1 | 21,386,670 | 21,805,404 |
| 2 | 27,542,597 (**64 %**) | 38,847,526 (**89 %**) |
| 4 | 44,134,035 (**52 %**) | 42,811,673 (**49 %**) |
| 8 | 58,728,550 (**34 %**) | 74,087,170 (**42 %**) |

> **Verdict: NOT REACHED. Eight runs put eight-lane efficiency at 37, 28, 40, 40, 40, 29, 34 and
> 42 % of linear, against a target of 90 %, and the two taken on the quietest machine available are
> 34 % and 42 % — in the middle of that range, not above it.** Recorded as measured. It is not restated, and it is not
> excused either: the three confounds gate P2 named in wave 3 are all still present and every one of
> them pushes this number down, so the figure is a lower bound for this machine rather than a verdict
> on the lane. What it is not is evidence that the software scales — there is no measurement here
> that could be.

#### The harness was biased, and the bias produced a 131 %

Runs 1–3 ran every pass of one lane count before starting the next. That makes a load spike a
**fixed bias** rather than noise: whichever lane count happened to run while the machine was busy
loses, and the ratio between two lane counts is then a ratio between two different machines. A
verification run of the committed harness on a box at **load 110** reported **131 % efficiency at
eight lanes** — a number produced entirely by a depressed one-lane baseline, and a pass where a
gate would have been declared reached on nothing at all.

Two changes followed, and runs 4–6 above are after both:

- **Lane counts are interleaved** — passes on the outside, lane counts on the inside, which is the
  arrangement `OperatorMetricsOverheadIT` arrived at for exactly this reason. It cannot make a
  scaling figure trustworthy on a shared machine, and nothing can; it removes the one bias that was
  systematic.
- **The verdict is withheld above half a runnable task per processor.** The harness prints the
  number and then says in as many words that the verdict must not be read in either direction,
  because a depressed baseline pushes the ratio above 100 % as easily as contention pushes it below.

Interleaving did not change the answer — 40, 40, 29, 34 and 42 % against 37, 28 and 40 % — which is
worth saying, because the check was worth running whichever way it came out.

The wave-3 pack recorded 2.7× (34 %) at eight lanes for the *lane machinery alone*
(`LaneScalingBenchmark`). Eight runs of a whole Profile A pipeline now put the same figure at
28–42 %. That the two agree is mildly interesting and is not evidence for anything: they share the
hardware confound that makes both untrustworthy.

## Gate P3 — Profile B

Design §5.2 NFR-2a: **≥ 350,000 rec/s per lane-core** on Profile B (§28.4: ten-second tumbling
`COUNT` + `SUM` + `AVG` grouped by user, 100,000 distinct keys, Zipf s = 1.1).

Run through the whole SQL path, because Profile B's plan is a window assigner, a sliced aggregate
and a keyed state map, and assembling those by hand would measure a pipeline the planner never
produces. Pool: 262,144 rows drawing **31,960 distinct users** out of 100,000 Zipf ranks, forming
**50,759 distinct (user, window) groups** over four ten-second windows. 2,000,000 rows a pass, best
of five after two warm-ups.

| Run | Best | Median | Worst | Spread | Load |
|---|---|---|---|---|---|
| 1 | **2,468,162** | 2,324,219 | 1,096,560 | 59 % | 7.18 |
| 2 | **2,845,647** | 2,648,608 | 2,544,051 | 11 % | 8.09 |
| 3 | **2,601,750** | 2,566,583 | 2,364,952 | 9 % | 7.69 |

> **Verdict: REACHED.** Every one of the fifteen timed passes exceeded 350,000 rows a second, and the
> worst of them — 1,096,560, taken during a load spike — is three times the target. Runs 2 and 3 are
> repeatable to within about 10 %, which is as steady as anything gets on this machine. **This is the
> first time gate P3's throughput criterion has been measured at all**; the wave-4 pack has said NOT
> MEASURABLE HERE since 2026-09-10.

## ADR-038's Nexmark comparison — what could honestly be run

Win condition W5 (design §2.5, §28.4) is **Nexmark q0–q22 against Flink SQL on the same hardware,
parity or better on ≥ 18 of 22 and ≥ 2× on ≥ 8**. ADR-038 moved it to the roadmap as a competitive
claim rather than a deployment requirement.

**The head-to-head cannot be run here and was not attempted.** It needs a Flink deployment to
compare against, one quiet machine to run both on, and Nexmark's own data generator. None of the
three exists in this repository or on this machine. Running it on a subset and publishing that is
precisely the selective benchmarking §28.4 says this audience detects and punishes.

What *can* be answered, and what nobody had asked, is **how many of the published queries this
engine runs at all** — the precondition for any comparison being worth setting up.

### Coverage — 5 of 23

| Verdict | Count | Queries |
|---|---|---|
| **Runs** | **5** | q0, q2, q3, q8, q20 |
| Refused by the planner or the pipeline | 14 | q1, q4, q5, q6, q7, q9, q11, q15, q16, q17, q18, q19, q21, q22 |
| Cannot be written against this surface at all | 4 | q10 (partitioned file sink + `DATE_FORMAT`), q12 (`PROCTIME()`), q13 (needs a registered lookup source; the harness registers none), q14 (a user-defined function) |

| Code | Refuses | What it is |
|---|---|---|
| `PRV-2002` | 5 | `DATE_FORMAT` (q15, q16, q17), `REGEXP_EXTRACT` (q21), `SPLIT_INDEX` (q22) — no such function |
| `PRV-2020` | 4 | q4, q6, q9 — the comma join; q11 — `GROUP BY SESSION` |
| `PRV-2021` | 3 | q1 — DECIMAL arithmetic; q18, q19 — `ROW_NUMBER() OVER` |
| `PRV-2050` | 1 | q5 — unwindowed `GROUP BY` on a derived window start |
| *(uncoded)* | 1 | q7 — `stream 'bid' appears on both sides of this plan; self-joins are not supported yet` |

> **Verdict: W5 is NOT REACHED, by a margin that has nothing to do with speed.** 5 of 23 run. The
> eighteen that do not are missing SQL, not missing throughput: `OVER` windows, session windows in
> SQL, self-joins, three scalar functions, processing time and user functions. No performance work
> changes that number.

Two transcription notes, because a refusal caused by the transcriber is not a result: q4's and q6's
published alias `final` is renamed `final_price` (Calcite reserves `FINAL`), and q15/q16/q17's alias
`day` is renamed `bid_day` (`DAY` is reserved). Nothing else in any query was changed.

### What one minimal rewrite buys — and the finding inside it

Three refusals are for the **comma join** Nexmark writes (`FROM auction A, bid B WHERE A.id =
B.auction AND B.date_time BETWEEN ...`). The planner sees a cartesian product with a filter above it
and refuses with *"the join condition 'true' is neither an equality between one column of each side
nor a time bound"*. Written as `INNER JOIN ... ON` with the same condition:

| Query | Rewritten as | Result |
|---|---|---|
| q4 | `INNER JOIN ... ON` | `PRV-2050` — the outer unwindowed `GROUP BY` is the real blocker |
| q6 | `INNER JOIN ... ON` | `PRV-2050` — same, and `OVER` still above it |
| q9 | `INNER JOIN ... ON`, `ROW_NUMBER` filter dropped | **RUNS** |

So the interval join itself works; the planner does not derive it from a comma join. That is a
narrow, fixable planner gap rather than a missing capability, and it is worth separating from the
rest because it is the only one of the eighteen that is cheap.

*Since this was measured:* SQL-13 closed that gap. The planner moves every `WHERE` condition that
reads both sides of a comma join into the join's condition, where `ON` would have put it, so q9's
join registers as Nexmark writes it (`NexmarkCommaJoinTest`); q4 and q6 now stop at `PRV-2050`
without a rewrite, and q9 at `PRV-2021` for its `ROW_NUMBER`. The table above is the measurement
of the day and is left as it was.

### Throughput of the five that run

200,000 rows into each of the query's source streams; three runs; load 5.85–6.27. Rows taken is the
total the registry accepted, which is 400,000 for a two-stream query.

| Query | Run 1 | Run 2 | Run 3 | Rows taken |
|---|---|---|---|---|
| q0 — pass-through | 1,365,700 | 1,319,651 | 1,640,124 | 200,000 |
| q2 — selection | 6,808,553 | 4,769,955 | 6,884,838 | 200,000 |
| q3 — local item suggestion | 466,483 | 377,571 | 449,225 | 400,000 |
| q8 — monitor new users | 1,025,983 | 1,165,120 | 1,292,326 | 400,000 |
| q20 — expand bid with auction | 107,650 | 81,832 | 102,489 | 400,000 |

> **These are not Nexmark numbers and must never be presented as any.** The rows come from this
> harness's generator rather than Nexmark's, so the ratios of persons to auctions to bids and the key
> skew are not the benchmark's; the machine is a loaded laptop; and the queries missing from the list
> are the expensive ones, so even the shape of the result is biased towards the cheap end.

## What the harness does to stop measuring nothing

A benchmark that silently measures an empty stream reports a magnificent number. Every measurement
here fails loudly instead:

- **Profile A** knows exactly how many rows its pool emits for any number fed — whole cycles times
  the pool's yield plus the yield of the partial cycle — and a pass that emits a different number
  throws. The first version of this check rounded to whole cycles and failed a real run by six rows,
  which is precisely the near-miss that invites somebody to replace an assertion with a tolerance.
- **Profile B** counts its pool's distinct (user, window) groups while encoding it and asserts that
  the served view holds exactly that many once the watermark has passed every window. A windowed
  query that drops rows, refuses keys at a ceiling or never closes a window fails.
- **Nexmark** asserts that each query took rows at all before printing a rate for it.
- **`GateReading`** refuses to be constructed with no samples, and prints target and verdict next to
  every number so that a bare figure cannot leave the harness.

## Three things the measurement itself found

**1. A scaling harness with a fixed arm order can report a gate as reached on nothing.** 131 % at
eight lanes, on a machine at load 110, from a one-lane baseline the load had crushed. Recorded in
full above, because it is the fourth time in this project's history that a benchmark has been the
program with the bug in it, and the first time one has produced a *pass* rather than a plausible
failure. A harness that can only be wrong downwards is a harness somebody eventually trusts.

**2. A registered query runs interpreted; nothing upgrades it to generated code.** `AdaptiveStage`
and `StageUpgradeService` are reachable from no production caller — finding `C-5` already records
this, and `OrphanedClassTest`'s own debt list carries `StageUpgradeService`. So the roughly 10×
`ProfileABenchmark` measures for generated code is **not available to a query anybody registers
today**, and every figure on this page is the interpreted path. The figures reach the gate anyway,
which is worth knowing before anybody spends a wave wiring the upgrade service in.

**3. Profile A's data is about 3–4 % selective, not the 10 % design §28.4 specifies.** `status =
'COMPLETED'` is one row in three and `amount > 900` is about one in ten, so the conjunction passes
23 of 512 rows (4.5 %) in the small pool and 3.3 % in the large one. `ProfileABenchmark` has carried
the same data since wave 2 with the comment "about 10 % selectivity on this data". Lower selectivity
means less projection work, so it flatters the figure — slightly, since the filter runs on every row
either way. The harness prints the measured selectivity beside the rate rather than quietly
inheriting the claim.

## How to reproduce

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

./mvnw -o -pl pravaha-it test -Dtest=ProfileAGateIT \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Dpravaha.gate.p2.rows=20000000 -Dpravaha.gate.p2.scaling.rows=8000000

./mvnw -o -pl pravaha-it test -Dtest=ProfileAGateIT \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false \
    -Dpravaha.gate.p2.rows=20000000 -Dpravaha.gate.p2.pool=262144   # the cold-pool arm

./mvnw -o -pl pravaha-it test -Dtest=ProfileBGateIT \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false

./mvnw -o -pl pravaha-it test -Dtest=NexmarkCoverageIT \
    -DfailIfNoSpecifiedTests=false -Dsurefire.failIfNoSpecifiedTests=false
```

All four are named `*IT`, which the root POM's surefire configuration excludes, because a throughput
measurement that gates a pull request is a flaky test.

## What is still unmeasured, and why

| | Why not here |
|---|---|
| **P2's scaling criterion, as evidence** | The measurement exists and is recorded above; what does not exist is hardware on which the ratio would mean anything. 16 physical homogeneous cores at ≥ 3.0 GHz, quiet |
| **Latency — NFR-1a to NFR-1d** | No harness, and p99.9/p99.99 on a machine at load 40 are noise whatever the harness. The wave-6 pack already records the serving-latency half of this as NOT MEASURED |
| **Profile C, D, E** | Profile C needs an Aerospike lookup at 10 M rows; D needs a concurrent read/write mix; E needs a 5 B-row store |
| **Nexmark head-to-head (W5)** | No Flink deployment, no quiet machine, no reference generator — and 18 of 23 queries do not run, so there is nothing to compare on |
| **NFR-2c, allocation rate per lane** | Not measured by any harness here |

---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**</sub>
