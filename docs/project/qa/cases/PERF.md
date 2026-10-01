# PERF — idle cost, throughput, contention, limits, leaks

*Area `PERF`, IDs `PERF-001`–`PERF-060`, budget 60.*

Where the numbers, the knobs and the ceilings live:

| Thing | Where |
|---|---|
| Four JMH benchmarks | `pravaha-benchmarks/src/main/java/com/ash/messaging/pravaha/benchmarks/` |
| Recorded results, baselines, hardware caveats | `benchmarks/README.md`, `benchmarks/baselines/`, `benchmarks/results/` |
| Lane defaults: batch, inbox, arena, wait strategy, shutdown | `pravaha-runtime/.../lane/LaneConfig.java:60`, `:83`–`:95` |
| `Lane.run`'s `catch (Throwable)`, `State.FAILED`, `checkHealth` | `pravaha-runtime/.../lane/Lane.java:471`–`:483`, `:582`–`:588` |
| Slabs, `ArenaHandle.NULL`, row-too-big | `pravaha-common/.../arena/RowArena.java:42`, `:76`–`:93`, `:105` |
| PRV-3001 `ARENA_EXHAUSTED`, PRV-3010 `LANE_FAILED` | `pravaha-runtime/.../RuntimeErrors.java:23`, `:25` |
| Four wait strategies | `pravaha-common/.../queue/WaitStrategy.java:44`, `:52`, `:63`, `:75` |
| The registry's `BACKOFF_PARK` default and the 92 % note | `pravaha-registry/.../QueryRegistry.java:83`–`:98` |
| `visible`, `scan()`, `evict()`, the monitor | `pravaha-serving/.../ServedView.java:88`–`:113`, `:317`–`:320`, `:354`–`:372` |
| `ViewQuery.scan()`'s only production caller | `pravaha-serving/.../ViewQuery.java:221` |
| The 20 ms commit cadence | `pravaha-server/.../ingest/PumpingFeed.java:61`, `:158`–`:165` |
| Bounded polls, hysteresis, `BACKPRESSURED` | `pravaha-runtime/.../ingest/IngestPump.java:118`–`:163`, `BackpressurePolicy.java` |
| Subscriber buffer and overflow | `pravaha-registry/.../SubscriptionOptions.java:34`, `Subscription.java:51`, `:119`–`:136` |
| Round 1's measurements | `docs/project/qa/logs/INGEST.md:1199`, `:1213`, `:1651`; `docs/project/qa/FINDINGS.md:156` |
| Prior unexecuted cases this file supersedes | `docs/project/qa/cases/INGEST.md` INGEST-047/048/055; `docs/project/qa/cases/STRM.md` STRM-103/104 |

## Five facts this file is built on

1. **One lane thread per registered query, hard-coded.** `QueryRegistry` starts every execution with
   `QueryExecution.start(plan, 1, laneConfig, access, ...)`. The comment at `QueryRegistry.java:130`
   says so: "a query per lane means a thread per query — fine at tens, and the reason ADR-027 wants a
   lane to multiplex several queries before this reaches hundreds."
2. **There is no configuration key for any of it.** An exhaustive grep of `"pravaha.*"` literals in
   `src/main` finds `checkpoint.*`, `cluster.*`, `ffm`, `memory`, `node.id`, `security.*`,
   `sources.*`, `watermark.*` — and **no** `pravaha.lane.*` or `pravaha.arena.*`. Five error messages
   tell the operator to raise `arena.slab.size` or `lane.inbox.cell.size`, which are not settings.
   PERF-004.
3. **A lane that dies leaves the query reporting `RUNNING`.** `Lane.run`'s `catch (Throwable)` sets
   `State.FAILED` and exits the thread; `checkHealth()` is called by `QueryRunner` and by
   `QueryExecution.awaitQuiescent`, and by **nothing** in `pravaha-registry`, `pravaha-server` or
   `pravaha-flight`. `RegisteredQuery.fail()` is reachable only from `accept()` and
   `advanceWatermark()`, and a server-fed query uses neither. PERF-040, PERF-041.
4. **`ServedView.scan()` copies the whole committed map under the view's own monitor**, and
   `commit()` — which runs on the feed thread every 20 ms — needs that monitor. Round 1 measured
   eight readers taking ingestion from ~520 000 rows/s to ~2 000. Section D.
5. **`evict()` is an O(view) sweep inside `commit()`**, skipped only when retention is `forever()`,
   and the registry default is 24 hours. So the sweep runs 50 times a second over the whole view and
   normally evicts nothing. Section E.

## Method

**Every case in this file reports numbers, so every case must report its conditions.** A result
without them is not a result. Record, with every run:

```
CPU model, physical cores, logical cores, whether cores are heterogeneous
RAM, and whether the machine was otherwise idle
JDK vendor and version, GC in use, -Xmx, -Xms
git commit SHA of the build under test
JVM flags, including --add-opens and any -XX
wall-clock duration of the measured window, and the warm-up discarded
```

`benchmarks/README.md` records the reference machine as an **AMD Ryzen AI 9 HX 370 laptop, 12
physical / 24 logical, heterogeneous Zen 5 + Zen 5c**. Heterogeneous cores mean a thread migrated
between a Zen 5 and a Zen 5c core changes speed for reasons that have nothing to do with the change
under test, so every throughput case below pins threads where the harness allows it (`taskset -c`)
and says so.

**The reference figures.** `benchmarks/README.md` records Profile A at 570 510 batches/s generated
(= `570 510 * 512 = 292 101 120` ≈ 292 M rows/s, ±5 %) against 55 971 interpreted (= 28.7 M rows/s,
±28 %); false sharing at 453 M ops/s padded against 110 M shared; lane scaling 21.3 M → 26.8 M →
35.6 M → 57.8 M for 1, 2, 4, 8 lanes. Two caveats must be quoted whenever these are cited, because
the README states them: *"This is not the Profile A gate figure and must never be quoted as one"*
(`LaneScalingBenchmark.java:51`) and *"These are not evidence for or against the scaling gate"*
(`README.md:140`).

## Harnesses

**`H-JMH`** — the benchmark jar.
`./mvnw -Pbench -pl pravaha-benchmarks package`, then
`java -jar pravaha-benchmarks/target/benchmarks.jar <regex> -rf json -rff benchmarks/results/<name>.json`.

**`H-EMB`** — embedded, one registered query, rows pushed by the test thread through
`RegisteredQuery.accept` and published with `commit()`. `QueryRegistry` defaults apply:
`BACKOFF_PARK`, daemon threads named `pravaha-query-*`, one lane, `Retention.DEFAULT` (24 h),
`DEFAULT_MAX_KEYS = 1_000_000`.

**`H-SRV`** — a real node, one JVM, rows arriving through a bound source so the feed thread and the
20 ms `PumpingFeed` cadence are in play. This is the only harness in which `commit()` and therefore
`evict()` run on a timer.

```yaml
pravaha:
  streams:
    txn: { fields: "id INT64, user_id STRING, amount INT64, product_type STRING, ts TIMESTAMP", event-time: "ts" }
  sources:
    txn: { plugin: feedfile, options: { path: /opt/pravaha/data/in/txn, schema: "..." } }
  security: { authentication: none, policy: permissive, allow-anonymous: true }
```

**Standard payload.** Unless a case says otherwise: `N` rows, `user_id` drawn from `K` distinct
keys as `"u" + (i % K)`, `amount = 1`, `product_type` alternating `card`/`wire`, `ts` advancing 1 ms
per row. Two values of `K` matter and both are always stated, because round 1's row-count assertion
passed while 200 000 rows collapsed into 500 keys: **`K = N`** (every row a new key, so the view grows
with the feed) and **`K = 500`** (the view stops growing at 500 and the feed is pure update traffic).

---

### A. Method, tooling and the honesty of the existing numbers (PERF-001–004)

## PERF-001 — the benchmark jar builds and every benchmark runs
**Intent:** before any number is quoted, establish that the four benchmarks execute on this machine
and this JDK, and record their JMH configuration so later runs are comparable.
**Falsifier:** the jar does not build, or a benchmark errors, or the `@Param` sets differ from the
source.
**Setup:** `H-JMH`.
**Steps:** 1. `./mvnw -Pbench -pl pravaha-benchmarks package`. 2.
`java -jar pravaha-benchmarks/target/benchmarks.jar -l` (list). 3. Run each with one warm-up and one
measurement iteration as a smoke pass. 4. Record each benchmark's mode, time unit, fork count,
warm-up and measurement settings, and params.
**Expected:** step 2 lists at least `MemoryAccessBenchmark.{getLong, putLong,
readRowOfTwelveFields, equalsUtf8Literal}`, `LaneScalingBenchmark.oneRow`,
`FalseSharingBenchmark` (four methods in two groups), and
`ProfileABenchmark.{generated, interpreted, predicateOnly}`. Step 4 must match the source:
`MemoryAccessBenchmark` — `AverageTime`, `NANOSECONDS`, `@Fork(2, jvmArgsAppend = {"-XX:+UseZGC"})`
(`-XX:+ZGenerational` dropped in 2.0: JDK 25 ignores it), `@Measurement(5, time = 2)`, **no `@Warmup` annotation** so JMH's default
5×10 s applies, `@Param({"bytebuffer", "agrona"})`; `LaneScalingBenchmark` — `Throughput`, `SECONDS`,
`@Fork(1)` with the same ZGC flags, `@Warmup(3, 2)`, `@Measurement(5, 3)`,
`@Param({"1","2","4","8"}) lanes` and `@Param({"SPIN_THEN_YIELD"}) waitStrategy`;
`FalseSharingBenchmark` — `Throughput`, `SECONDS`, `@Fork(1)` **with no jvmArgs**, `@Warmup(3, 1)`,
`@Measurement(5, 2)`; `ProfileABenchmark` — `Throughput`, `SECONDS`, `@Fork(1)` with ZGC,
`@Warmup(3, 2)`, `@Measurement(5, 2)`, `BATCH = 512`, seed `20260909L`.
**Vacuity:** recording the annotations from the *source* and comparing with what JMH reports at
runtime is what catches a benchmark whose configuration was changed without its recorded results
being redone.

## PERF-002 — `-Pbench` changes nothing, and there is no regression gate
**Intent:** `pravaha-benchmarks/pom.xml:11` and `benchmarks/README.md:5`–`:7` claim CI fails on a
>10 % regression. The `benchmarks.skip` property is declared at `pom.xml:126` and flipped by the
`bench` and `all` profiles — and **no plugin anywhere references it**.
**Falsifier:** any plugin configuration reads `${benchmarks.skip}`, or a CI workflow runs a
benchmark or compares a baseline.
**Setup:** the repository.
**Steps:** 1. `grep -rn "benchmarks.skip" --include=pom.xml .`. 2. `grep -rn "bench\|baseline\|jmh"
.github/workflows/`. 3. `./mvnw -pl pravaha-benchmarks package` with and without `-Pbench` and diff
the produced artefacts. 4. Read `pravaha-benchmarks/pom.xml:11` and `benchmarks/README.md:5`–`:7`.
**Expected:** step 1 returns exactly three lines — the declaration and the two profile overrides —
and none of them is a plugin `skip` parameter. Step 2 returns nothing in `fast.yml`, `matrix.yml` or
`verify.yml`. Step 3 — identical artefacts, so the profile is inert. Step 4 — the claim is in the
documentation and not in the build. Record as a documentation defect and hand it to `DOCX`.
**Vacuity:** the byte-diff at step 3 is what turns "no plugin reads it" into "the flag does nothing",
which is the operative statement.

## PERF-003 — the recorded results are reproducible, and one of them names a method that no longer exists
**Intent:** `benchmarks/results/` and `benchmarks/baselines/` are the numbers the project quotes.
Re-run them and say whether they hold on the machine at hand — and record that
`benchmarks/results/lane-scaling.json` names `LaneScalingBenchmark.roundTrip`, a method the source no
longer has.
**Falsifier:** a re-run lands outside the recorded error bars *and* the discrepancy is not explained
by hardware.
**Setup:** `H-JMH`, `taskset`-pinned to physical cores where the benchmark is single-threaded.
**Steps:** 1. Run `MemoryAccessBenchmark` and compare against `benchmarks/baselines/memory-access.json`.
2. Run `ProfileABenchmark` and compare against `benchmarks/baselines/profile-a.json`. 3. Run
`LaneScalingBenchmark` with `-t N -p lanes=N` for N in 1, 2, 4, 8. 4. `grep benchmark
benchmarks/results/lane-scaling.json`. 5. Record the full environment block from **Method**.
**Expected:** step 1 — each accessor at or under the 2 ns acceptance in `MemoryAccessBenchmark.java:51`,
with zero bytes allocated per operation; report both `bytebuffer` and `agrona`. Step 2 — the
generated arm around `570 510` batches/s (`570 510 * 512 = 292 101 120` rows/s) ±5 %, the interpreted
arm around `55 971` (28.7 M rows/s) ±28 %, and a generated:interpreted ratio near
`292.1 / 28.7 = 10.2`. Step 3 — 1 lane ≈ 21.3 M, 2 ≈ 26.8 M (`26.8 / (2 * 21.3) = 63 %` of linear),
4 ≈ 35.6 M (`35.6 / (4 * 21.3) = 42 %`), 8 ≈ 57.8 M (`57.8 / (8 * 21.3) = 34 %`), and note the 8-lane
error bar of ±47 M, which is larger than the 1-lane figure and makes that point uninformative. Step 4
— the JSON says `roundTrip`; the source has `oneRow`. The recorded result was produced by a harness
that no longer exists, so it cannot be reproduced at all and must be re-recorded or deleted.
**Vacuity:** the ±28 % and ±47 M error bars are quoted deliberately: a case that asserted a point
value would fail or pass at random. The ratio (10.2×) is the stable quantity and is what a regression
gate should watch.

## PERF-004 — none of the performance-relevant knobs is configurable, and five error messages say otherwise
**Intent:** the whole of this file's tuning surface is Java API. An operator hitting PRV-3001 is told
to "raise `arena.slab.size`", which does not exist.
**Falsifier:** any `pravaha.lane.*` or `pravaha.arena.*` key is read anywhere in `src/main`.
**Setup:** the repository, plus `H-SRV`.
**Steps:** 1. Grep every `"pravaha.` string literal in every `src/main` tree and list the distinct
keys. 2. Grep `application.yaml` for lane, arena, batch, inbox, wait or thread settings. 3. Grep for
the strings `arena.slab.size` and `lane.inbox.cell.size`. 4. Set
`pravaha.lane.count: 4` and `pravaha.arena.slab.size: 16MB` in `application.yaml` and start `H-SRV`.
**Expected:** step 1 — the key set is `pravaha.checkpoint.{interval,keep,timeout}`,
`pravaha.cluster.*`, `pravaha.ffm`, `pravaha.memory`, `pravaha.node.id`, `pravaha.security.*`,
`pravaha.sources.*`, `pravaha.watermark.{idle-after,tick}`, plus metric and wire names. No lane or
arena key. Step 2 — nothing. Step 3 — five sites tell an operator to change a non-existent setting:
`RowArena.java:87` (`raise arena.slab.size for this query`), `IngestPump.java:104` and
`PartitionedIngestPump.java:107` (`Raise lane.inbox.cell.size`), `WindowAssign.java:68` and
`LookupJoin.java:308` (`raise arena.slab.size`). Step 4 — the node starts and **ignores both keys**
without comment; confirm by reading `LaneMetrics` and seeing one lane and a 4 MiB slab. So every
number in sections C through I is a property of hard-coded defaults an operator cannot change.
**Vacuity:** step 4 is the non-vacuity: a key that is unread is different from one that is rejected,
and only the former silently produces the wrong deployment.

---

### B. Idle cost per registered query (PERF-005–012)

`QueryRegistry.java:83`–`:98` records the measurement and the fix: *"three idle queries at 26 % of a
core, nine at 92 %, with the source dry and no rows arriving. A two-core container saturates at about
twenty idle registrations."* The fix is
`LaneConfig.defaults().withWaitStrategy(BACKOFF_PARK).withThreads("pravaha-query", true)`, and
`docs/project/qa/FINDINGS.md:156` claims "Fixed and measured at 0 % since." These eight cases measure it, and
measure what else an idle query still costs.

## PERF-005 — nine idle registered queries cost approximately no CPU
**Intent:** the headline regression. This is the case the fix exists for.
**Falsifier:** total process CPU attributable to the nine lane threads exceeds 5 % of one core over a
60-second idle window.
**Setup:** `H-SRV`. Nine distinct queries registered (nine distinct SQL texts, so nine computations
and nine lanes). **No source bound at all**, so nothing can arrive and every lane is genuinely idle.
Machine otherwise quiet; confirm with 60 s of baseline `top` before starting.
**Steps:** 1. Start the node, register nine queries, wait 30 s to settle. 2. Record per-thread CPU
for 60 s: `top -H -b -n 60 -d 1 -p <pid>`, and `/proc/<pid>/task/*/stat` fields 14 and 15 (utime,
stime) at the start and end of the window. 3. Sum the delta for threads named `pravaha-query-*`.
4. Sum the delta for **all** threads. 5. Repeat with 3 queries and with 20.
**Expected:** step 3 — the nine `pravaha-query-*` threads together consume under `0.05 * 60 = 3.0`
CPU-seconds over the 60-second window, i.e. under 5 % of one core. The pre-fix behaviour was
`0.92 * 60 = 55.2` CPU-seconds for nine, so the two are an order of magnitude apart and the assertion
is not marginal. Step 4 is the number to report alongside it (see PERF-006). Step 5 — 3 queries and 20
queries both stay under 5 % of one core for the lane threads, against the recorded pre-fix `26 %` and
"saturates at about twenty".
**Vacuity:** measuring **per thread** rather than per process is the whole point: PERF-006 shows the
process total is not near zero, and a process-level assertion would fail for the wrong reason and be
"fixed" by loosening it. The three query counts (3, 9, 20) match the recorded pre-fix data points so
the comparison is like for like.

## PERF-006 — an idle query still costs a feed wake-up loop, a commit timer and a watermark tick
**Intent:** "measured at 0 %" is true of the lane thread and not of the query. Per registered query
there is still: a `pravaha-feed-<name>` platform thread napping `IDLE_NAP_NANOS = 1_000_000L`
(1 ms) per empty poll — about **1 000 wake-ups a second**; a `commit()` every
`PUBLISH_INTERVAL_NANOS = 20_000_000L` (20 ms) — **50 a second**, whether or not anything changed;
and a `pravaha-watermark` scheduled tick every `DEFAULT_TICK = 1 s`.
**Falsifier:** the per-thread accounting shows no `pravaha-feed-*` or `pravaha-watermark` CPU at all.
**Setup:** `H-SRV` with nine queries and **a source bound but empty** (an existing, zero-length feed
file), so the feed threads exist and find nothing. This is the realistic idle case; PERF-005's
unbound variant is the floor.
**Steps:** 1. Settle 30 s. 2. Per-thread CPU over 60 s, grouped by thread-name prefix. 3. Count
`commit` invocations over the window (instrument `ViewSink.commit`, or read a counter). 4. Count
watermark ticks. 5. Record the total process CPU.
**Expected:** per query over 60 s, roughly `60 * 1000 = 60 000` feed wake-ups, `60 * 50 = 3 000`
commits, and `60 * 1 = 60` watermark ticks. Across nine queries that is `540 000`, `27 000` and
`540`. Report the CPU-seconds each thread group consumed. The assertion is not a threshold — it is
that these three costs exist, scale linearly with the number of registered queries, and are
**invisible** in the "0 %" claim because that claim is about `pravaha-query-*` only.
**Vacuity:** the empty-but-bound source is what distinguishes this from PERF-005; with no source the
feed threads do not exist and the cost would not appear.

## PERF-007 — the four wait strategies, measured for idle cost and for wake-up latency
**Intent:** enumerate the dimension rather than gesture at it. `WaitStrategy` has four constants and
the registry hard-codes one of them; the trade between them is the whole subject.
**Falsifier:** `BACKOFF_PARK` is not materially cheaper than `SPIN_THEN_YIELD` when idle, or is not
materially slower to wake.
**Setup:** a lane harness (not the registry) allowing the strategy to be chosen:
`LaneConfig.defaults().withWaitStrategy(k)` for each of `BUSY_SPIN`, `SPIN_THEN_YIELD`,
`BACKOFF_PARK`, `BLOCKING`. One lane, one thread, pinned with `taskset -c 2`.
**Steps:** For each of the four: 1. Start the lane with no input; measure its thread's CPU over 30 s.
2. Then publish a single row and measure the delay from publish to the processor seeing it, 10 000
times with 5 ms between, reporting p50/p99/max.
**Expected:** a table of eight numbers. Predicted shape, from the code:

| strategy | idle branch | expected idle CPU | expected wake latency |
|---|---|---|---|
| `BUSY_SPIN` | `Thread.onSpinWait()` always | ~100 % of a core | lowest |
| `SPIN_THEN_YIELD` | `<64` spin, `<128` yield, else `parkNanos(1L)` | ~100 % of a core — `parkNanos(1)` returns immediately on Linux, so the terminal branch is a busy loop | low |
| `BACKOFF_PARK` | `<8` spin, else `parkNanos(min(1 ms, 1 µs << min(10, n-8)))` | ~0 % | up to 1 ms |
| `BLOCKING` | `parkNanos(1_000_000L)` always | ~0 % | up to 1 ms |

The `SPIN_THEN_YIELD` row is the finding to confirm by measurement: its terminal branch *looks* like
a park and behaves like a spin, which is precisely why nine idle queries burned 92 % of a core under
the old default.
**Vacuity:** measuring latency as well as CPU is what makes this a trade-off table rather than a
"parking is better" assertion; `BUSY_SPIN` should win the latency column, and if it does not, the
measurement is wrong.

## PERF-008 — the registry's default really is `BACKOFF_PARK`, and nothing else picks it up
**Intent:** the fix is a hard-coded field initialiser in one class. Anything building on
`LaneConfig.defaults()` directly still spins, and there is no configuration key to change either
(PERF-004).
**Falsifier:** `LaneConfig.defaults().waitStrategy()` is `BACKOFF_PARK`, or the registry's is not.
**Setup:** the repository plus `H-EMB`.
**Steps:** 1. Read `LaneConfig.defaults().waitStrategy()`. 2. Read the registry's effective
`LaneConfig`. 3. `grep -rn "BACKOFF_PARK" --include=*.java . | grep "/src/main/"`. 4. `grep -rn
"LaneConfig.defaults()" --include=*.java .`.
**Expected:** step 1 — `SPIN_THEN_YIELD` (`LaneConfig.java:86`). Step 2 — `BACKOFF_PARK`, and the
thread prefix `"pravaha-query"` with `daemonThreads = true`. Step 3 — exactly two production sites:
`QueryRegistry.java:96` and `pravaha-cli/.../QueryRunner.java:116`. Step 4 — every other caller,
including `LaneScalingBenchmark` (which pins `SPIN_THEN_YIELD` explicitly) and every lane test in
`pravaha-it`, still spins. So the benchmark numbers in `benchmarks/README.md` were produced under a
strategy the product no longer uses — record that, because it affects how PERF-003's lane-scaling
figures should be read.
**Vacuity:** step 1 and step 2 disagreeing is the assertion; asserting only step 2 would pass on a
library whose default had also changed, which is a different (and better) fix.

## PERF-009 — idle cost is linear in the number of registered queries, and the slope is the number to quote
**Intent:** the useful form of the answer. "Fine at tens" (`QueryRegistry.java:130`) needs a slope.
**Falsifier:** the relationship is not linear, or the slope implies saturation below 100 queries.
**Setup:** `H-SRV` with an empty bound source.
**Steps:** 1. For `n` in 1, 3, 9, 20, 50, 100, 200: register `n` distinct queries, settle 30 s,
measure total process CPU over 60 s and RSS. 2. Also record thread count per `n`. 3. Plot and fit.
**Expected:** thread count is close to `3n + c`: one `pravaha-query-*` lane thread, one
`pravaha-feed-*` thread and — per query — a `pravaha-watermark` scheduled executor, plus a fixed
server complement. Report the fitted CPU slope in millicores per registered query and the RSS slope
in MiB per query. At `n = 200` report whether the process is still responsive: `pravaha queries`
must return in under a second. Compare the RSS slope with the arena ceiling —
`arenaMaxSlabs * arenaSlabBytes = 8 * 4 MiB = 32 MiB` per lane — and say whether arenas are allocated
eagerly (the first slab is, `RowArena.java:64`) or lazily, because `200 * 4 MiB = 800 MiB` of eager
first slabs is a different deployment from `200 * 32 MiB = 6.4 GiB` of ceilings.
**Vacuity:** seven points across two orders of magnitude, with a fit, is what makes "linear" a
measurement; three points near each other would not distinguish linear from quadratic.

## PERF-010 — a paused query costs the same as a running idle one
**Intent:** `RegisteredQuery.accept` returns `false` for a non-`RUNNING` query, but nothing stops its
lane, feed or watermark thread. So `pause` is not a resource-management tool, and an operator may
reasonably think it is.
**Falsifier:** pausing reduces thread count or CPU.
**Setup:** `H-SRV` with nine queries and an empty bound source.
**Steps:** 1. Measure threads, CPU and RSS with all nine `RUNNING`. 2. Pause all nine. 3. Measure
again after 30 s. 4. Resume; measure again.
**Expected:** thread count identical in all three states. CPU and RSS within measurement noise of
each other. Record this as the finding: `pause` stops answers changing and stops nothing else. If it
*does* reduce cost, that is worth knowing too and contradicts the code reading.
**Vacuity:** the resume arm is the control that shows the measurement is sensitive enough to detect a
state change at all — check that `pravaha queries` reports `PAUSED` then `RUNNING` across the three
measurements.

## PERF-011 — a dropped query gives its CPU and threads back
**Intent:** the counterpart to PERF-010, and the precondition for the leak cases in Section G.
**Falsifier:** CPU or thread count does not return to the pre-registration baseline.
**Setup:** `H-SRV`, empty bound source.
**Steps:** 1. Baseline: threads, CPU over 60 s, RSS after a `System.gc()` and a 10 s settle.
2. Register nine; settle; measure. 3. Drop all nine; settle 30 s; measure. 4. Compare with baseline.
**Expected:** step 3's thread count equals step 1's exactly — no `pravaha-query-*`,
`pravaha-feed-*`, `pravaha-watermark` or `pravaha-checkpointer` threads remain. CPU returns to
baseline. RSS does not necessarily, and that is PERF-044's subject, not this one.
**Vacuity:** naming the four thread prefixes individually is what makes "returns to baseline"
checkable; a total count can coincide by accident when one thread leaks and another is not yet
started.

## PERF-012 — the watermark tick is per query, and at 1 s it is the floor on idle wake-ups
**Intent:** `QueryExecution.java:348`–`:355` creates a `newSingleThreadScheduledExecutor` named
`pravaha-watermark` per execution, `scheduleWithFixedDelay` at `DEFAULT_TICK = 1 s`. That is the
irreducible cost, because without it a watermark derived only from arriving rows cannot notice that
rows have stopped.
**Falsifier:** one shared scheduler serves all queries, or the tick does not fire when idle.
**Setup:** `H-EMB` with `generatingWatermarks(Duration.ofSeconds(30), Duration.ofSeconds(1))`, and
`H-SRV` with nine queries.
**Steps:** 1. Count threads named `pravaha-watermark` with 1, 3 and 9 queries. 2. Instrument the tick
and count firings over 60 s per query with no rows arriving. 3. Repeat with `tick: 100ms` and with
`tick: 10s` in `application.yaml`. 4. Measure the CPU of the watermark threads in each.
**Expected:** step 1 — one thread per execution, so 1, 3 and 9. Step 2 — about 60 firings per query
per minute. Step 3 — about 600 and about 6 at the two extremes, confirming the key is read
(`pravaha.watermark.tick` is one of the few that is, PERF-004). Step 4 — the CPU cost is small but
non-zero and scales with `queries × 1/tick`; report millicores. Cross-reference `TIME`, which owns
whether the tick is *correct*; this case owns only what it costs.
**Vacuity:** the three tick values are what prove the firings are driven by the configured tick and
not by an unrelated timer.

---

### C. Throughput by plan shape (PERF-013–022)

Five plan shapes, each measured twice — at `K = N` (every row a new key, the view grows) and at
`K = 500` (the view stops growing, the feed is update traffic). Ten cases, because the two key
regimes are different code paths through `ServedView.applyValues` and produce different answers, and
because round 1's row-count assertion passed while 200 000 rows collapsed into 500 keys.

Every case in this section reports: sustained rows/s over the measured window, p50/p99/max
end-to-end latency from `accept` to committed-and-readable, allocation rate, GC pause total, final
view size, and `LaneMetrics.idleCycles` against `rowsIn`. And every case asserts the **answer** as
well as the rate, because a fast wrong answer is the failure this file exists to avoid.

## PERF-013 — plain projection, every row a new key
**Intent:** the floor case: no state, no aggregation, view grows one row per input row.
**Falsifier:** the final view holds fewer than `N` rows, or the sum of `amount` is not `N`.
**Setup:** `H-SRV`. `SELECT id, user_id, amount FROM txn`, key column `[0]` (`id`, unique).
`N = 5 000 000`, `K = N`, `amount = 1`. Measure after 30 s of warm-up, over a 60 s window.
**Steps:** 1. Feed. 2. Sample `rowsIn` every second. 3. At the end, `pravaha query --sql "SELECT
COUNT(*), SUM(amount) FROM v"`.
**Expected:** report sustained rows/s. The correctness assertions: `COUNT(*) == 5 000 000` and
`SUM(amount) == 5 000 000 * 1 = 5 000 000`. The view holds 5 000 000 distinct keys — note this is
**five times** `DEFAULT_MAX_KEYS = 1_000_000`, so the run is expected to fail with
`ServingErrors.VIEW_TOO_LARGE` somewhere past the millionth row. **That is the result**: record the
row count at which it fires and confirm the number is `1 000 000`, and re-run at `N = 900 000` to get
a clean throughput figure under the ceiling. Report both.
**Vacuity:** `SUM(amount)` with `amount = 1` makes the sum equal the count, so a collapse of rows into
fewer keys moves both numbers together and neither can mask the other. The deliberate overshoot past
`DEFAULT_MAX_KEYS` is what finds the ceiling rather than assuming it.

## PERF-014 — plain projection, 500 keys
**Intent:** the same plan where the view stops growing: every row after the first 500 is an update,
so `ServedView.applyValues` replaces rather than inserts and the map size is constant.
**Falsifier:** the view holds more than 500 rows, or throughput is indistinguishable from PERF-013's
(which would suggest the view size is not on the hot path — testable, and interesting either way).
**Setup:** `H-SRV`. Same SQL but keyed on `user_id` (`[1]`). `N = 5 000 000`, `K = 500`.
**Steps:** as PERF-013.
**Expected:** final view size exactly `500`. `SUM(amount)` over the view is `500 * 1 = 500` — the
*last* value per key, not the total, because this is a projection and not an aggregate. Report the
rate and compare with PERF-013's sub-ceiling figure: the difference is the cost of a growing map
(rehashing, cache misses, and `evict()`'s O(n) sweep — Section E).
**Vacuity:** asserting the view size is exactly 500 is the round-1 correction: `rowsIn = 5 000 000`
with `size = 500` is the shape that previously passed a row-count assertion while meaning something
else entirely, and here both numbers are asserted.

## PERF-015 — filter at 10 % selectivity, every row a new key
**Intent:** the predicate path, at the selectivity `ProfileABenchmark` uses, so the end-to-end figure
can be put beside the JMH one.
**Falsifier:** the surviving row count is not 10 % of the input within sampling error.
**Setup:** `H-SRV`. `SELECT id, user_id, amount FROM txn WHERE product_type = 'card' AND amount >
900`. Feed `N = 900 000` rows with `amount` uniform on `[1, 1000]` from seed `20260909L` and
`product_type` `card` for exactly half. Expected survivors: `900 000 * 0.5 * (100/1000) = 45 000`.
**Steps:** 1. Feed. 2. Measure input rows/s and output rows/s separately. 3. `COUNT(*)` on the view.
**Expected:** `COUNT(*) == 45 000` exactly, because the generator is seeded and the counts are
deterministic — compute the exact figure from the seed and assert it, not a tolerance. Report input
rows/s (the number that matters — the predicate runs on every row) and output rows/s. Compare the
input rate with `ProfileABenchmark.predicateOnly`, which measures the same predicate with no engine
around it; the ratio is the engine's overhead per row and is the useful quantity.
**Vacuity:** a seeded generator makes 45 000 an exact expectation rather than a band, so a predicate
that is inverted, short-circuited or ignored fails rather than passing within tolerance.

## PERF-016 — filter at 10 % selectivity, 500 keys
**Intent:** the same, with a bounded view.
**Falsifier:** as PERF-015.
**Setup:** as PERF-015 but keyed on `user_id` with `K = 500`.
**Steps:** as PERF-015.
**Expected:** the view holds at most 500 rows (fewer if some key never produced a surviving row —
compute the expectation and report the actual). Input rows/s reported and compared with PERF-015:
the delta isolates view growth from predicate cost, because the predicate work is identical.
**Vacuity:** the paired comparison with PERF-015 under an identical predicate is what attributes the
difference to the view.

## PERF-017 — keyed aggregate, one group per row
**Intent:** `KeyedAggregate` with `DEFAULT_MAX_GROUPS = 1_000_000`. At `K = N` the group count grows
with the feed and the ceiling is reachable.
**Falsifier:** `SUM` is not `1` per group, or the ceiling fires at a number other than 1 000 000.
**Setup:** `H-SRV`. `SELECT id, SUM(amount) AS total FROM txn GROUP BY id`, `N = 1 200 000`,
`K = N`, `amount = 1`.
**Steps:** 1. Feed. 2. Watch for the ceiling. 3. Re-run at `N = 900 000` for a clean rate.
**Expected:** the ceiling fires at `1 000 000` groups — record the exact row count and the error
(PRV-3001 or a `ServingErrors` code; identify which and report it, because
`KeyedAggregate.DEFAULT_MAX_GROUPS` and `QueryRegistry.DEFAULT_MAX_KEYS` are both 1 000 000 and
either could fire first, and knowing which is the diagnosis an operator needs). At `N = 900 000`:
`COUNT(*) == 900 000`, every `total == 1`, and `SUM(total) == 900 000 * 1 = 900 000`. Report rows/s.
**Vacuity:** asserting every group's total is exactly 1 — not just the grand total — is what catches
two rows landing in one group, which a grand total cannot distinguish from correct behaviour.

## PERF-018 — keyed aggregate, 500 groups, so retraction traffic dominates
**Intent:** the realistic aggregate. Every row after the first 500 produces a **retract plus an
insert** (`ViewChange` weight `-1` then `+1`), so the per-row work is double the insert-only case and
this is where the Z-set cost shows up.
**Falsifier:** the totals do not sum to `N`, or the view holds more than 500 rows.
**Setup:** `H-SRV`. `SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id`,
`N = 5 000 000`, `K = 500`, `amount = 1`.
**Steps:** 1. Feed. 2. Measure. 3. Read the view. 4. Count `ViewChange`s delivered to a subscriber.
**Expected:** view size exactly `500`. Each group's total is `5 000 000 / 500 = 10 000`.
`SUM(total) == 500 * 10 000 = 5 000 000`. The subscriber sees approximately `2 * (5 000 000 - 500) +
500 = 9 999 500 + 500 = 10 000 000` changes minus whatever conflation collapsed — report the
`conflated` and `dropped` counters alongside, so the arithmetic closes. Report rows/s and compare
with PERF-014 (projection, same key count): the difference is the aggregate plus the retraction pair.
**Vacuity:** `10 000` per group is hand-computed and identical for all 500 groups, so a single lost
or doubled row breaks exactly one group's total and is visible; the grand total alone would hide an
offsetting pair.

## PERF-019 — stream-to-stream join, growing state
**Intent:** `SymmetricHashJoin` with `STATE_SLAB_BYTES = 1 << 20` and
`MAX_JOIN_STATE_SLABS = 64`, i.e. a `64 * 1 MiB = 64 MiB` ceiling per join, and
`DEFAULT_MATCH_WITHIN_NANOS = 3 600 s`. With a one-hour match window and no watermark advance,
nothing evicts and the ceiling is reachable.
**Falsifier:** the join emits fewer than the computed number of matches, or the ceiling fires at a
size other than 64 MiB.
**Setup:** `H-SRV` with two streams. `SELECT t.id, t.amount, l.tier FROM txn t JOIN lkp l ON
t.user_id = l.user_id`. Feed `lkp` with 500 distinct `user_id`s first, then `txn` with
`N = 2 000 000` rows over the same 500 keys, `ts` advancing 1 ms per row so the whole feed spans
`2 000 000 ms = 2 000 s`, which is inside the one-hour match window.
**Steps:** 1. Feed. 2. Measure rows/s and the join's held-state size. 3. Count output rows.
**Expected:** every `txn` row matches exactly one `lkp` row, so the output is `2 000 000` rows.
Report rows/s. Report the point at which held state reaches `64 MiB` if it does — with 500 `lkp`
rows retained and 2 000 000 `txn` rows arriving, whether `txn` rows are retained depends on the join
implementation; measure and report the growth curve, and identify which side grows.
**Vacuity:** a one-to-one join makes the output count exactly equal the input count, so any dropped
or duplicated match is a visible integer difference.

## PERF-020 — stream-to-stream join, evicting at the watermark
**Intent:** the same join with the watermark advancing, so `advanceWatermark` evicts rows that can no
longer match. This is the configuration a continuous query actually runs in, and the one where
throughput is steady rather than degrading.
**Falsifier:** held state grows without bound despite an advancing watermark, or matches are lost
that should still have been possible.
**Setup:** as PERF-019, but with `pravaha.watermark.tick: 1s`, `out-of-orderness: 10s`, and a
`MATCH_WITHIN` of 60 s set on the join, and `ts` advancing 1 ms per row.
**Steps:** 1. Feed 2 000 000 rows. 2. Sample held-state size every 5 s. 3. Count output rows and
`lateRecords()`.
**Expected:** held state rises to a plateau and stays there. The plateau should be about
`60 s / 1 ms = 60 000` rows' worth of one side — compute the byte figure from the row width and
compare with the measurement. Output rows: every `txn` row still matches its `lkp` row, so `2 000 000`
again, unless `lkp` rows are evicted too — if the output is lower, report the shortfall and the
`lateRecords()` count, because that is the join's correctness cost of eviction and belongs to `JOIN`
as a defect. Report rows/s and compare with PERF-019: eviction costs work per watermark advance and
saves work per probe.
**Vacuity:** the plateau, rather than a single end-of-run size, is what distinguishes eviction from a
run that simply ended before the ceiling.

## PERF-021 — windowed aggregate, ten-second tumbling windows, 500 keys
**Intent:** the stateful shape that actually checkpoints (`InterpretedPipeline.isStateful()` is
`!windowed.isEmpty() || !joins.isEmpty()`), and the one with the lowest arena headroom (PERF-037).
**Falsifier:** a window's sum is not the number of rows that fell in it.
**Setup:** `H-SRV`. `SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id, TUMBLE(ts,
INTERVAL '10' SECOND)`, `amount = 1`, `K = 500`, `ts` advancing 1 ms per row, `N = 2 000 000` so the
feed spans `2 000 000 ms = 2 000 s = 200` windows.
**Steps:** 1. Feed. 2. Measure rows/s and open-window count over time. 3. Collect emitted windows.
4. Sum them.
**Expected:** `200` windows close. Each window covers 10 s = 10 000 rows spread over 500 keys, so each
window emits `500` rows each with `total = 10 000 / 500 = 20`. Total emitted rows
`200 * 500 = 100 000`; total summed `100 000 * 20 = 2 000 000`, which equals the input, so nothing was
lost. Open windows at any instant should be `1` plus whatever `out-of-orderness` keeps alive —
report the measured maximum. Report rows/s and compare with PERF-018 (same keys, no window).
**Vacuity:** `20` per emitted row is a small hand-computed integer repeated 100 000 times; one lost
row makes exactly one of them 19, which a total-only assertion would absorb.

## PERF-022 — windowed aggregate, one key per row, so open-window state grows
**Intent:** the same window with unbounded key cardinality inside each window, which is where open
windows become expensive and where PERF-037's arena threshold is measured.
**Falsifier:** the run completes with no ceiling and no degradation, which would contradict the
recorded ~264 000-row arena threshold.
**Setup:** as PERF-021 but `GROUP BY id, TUMBLE(...)` with `id` unique, `N = 500 000`.
**Steps:** 1. Feed, sampling rows/s every second. 2. Watch for PRV-3001 or a lane death. 3. If the
run survives, close the windows and check the answer.
**Expected:** each window holds `10 000` distinct keys. Report the rows/s curve — expect it to
degrade as the open window's key set grows within each 10 s span and recover at each window close.
Record whether the run reaches the arena threshold (PERF-037 says ~264 000 rows for this shape) and
if so, exactly at which row and with which error. If it completes: `50` windows, each emitting
`10 000` rows with `total = 1`, so `500 000` emitted rows summing to `500 000`.
**Vacuity:** the per-second curve, rather than one average, is what shows the sawtooth; an average
over the run hides both the degradation and the recovery.

---

### D. Reader/writer contention on `ServedView`'s monitor (PERF-023–030)

`scan()` is `synchronized` and its body is `new ArrayList<>(visible.values())` — a copy of the whole
committed map while holding the view's own monitor. `commit()` needs the same monitor and runs on the
feed thread every 20 ms. Round 1 measured the consequence at `docs/project/qa/logs/INGEST.md:1651`:

> `ServedView.scan()` copies the whole committed map under the view's monitor — **347 ms mean,
> 1 015 ms max on a 500 000-row view** — and `commit` on the feed thread waits behind it. **Eight
> readers take ingestion from ~520 000 rows/s to ~2 000 rows/s** and hold it there; **one reader
> costs 3×**. Proven by thread dumps: feed `BLOCKED` at `ServedView.commit:210`, monitor held by a
> Flight executor inside `ServedView.scan:319`.

These eight cases quantify it as a function of the two variables that drive it — reader count and
view size — and separate it from the eviction sweep in Section E.

## PERF-023 — baseline ingest rate with no readers
**Intent:** the control every case in this section is divided by. Without it none of the ratios mean
anything.
**Falsifier:** the baseline is not stable across three runs to within 10 %.
**Setup:** `H-SRV`, projection keyed on `id`, `K = N`, `N = 900 000` (under `DEFAULT_MAX_KEYS`).
**Steps:** 1. Feed with **no** client connected at all — confirm the Flight executor is idle.
2. Sample `rowsIn` every 100 ms. 3. Repeat three times.
**Expected:** report the sustained rate and its run-to-run spread. Round 1's figure on the reference
machine was ~520 000 rows/s (`INGEST.md:1199`, `t= 766ms rowsIn= 311806 rate= 520672/s`); report
whatever this machine gives. Also report the *shape*: the rate as a function of view size over the
run, because the view grows from 0 to 900 000 during it and Section E predicts the rate falls as it
grows even with no readers.
**Vacuity:** three runs with a stated spread is what makes a later 3× or 260× ratio a measurement
rather than noise; a single baseline run is not a baseline.

## PERF-024 — one concurrent reader costs about 3×
**Intent:** the first point on the curve, and the one that shows the problem is not a many-reader
edge case.
**Falsifier:** one reader costs less than 1.5× or more than 10×.
**Setup:** `H-SRV` as PERF-023, plus one client in a loop issuing
`SELECT * FROM v` (a full scan) with no delay between calls.
**Steps:** 1. Start the feed; let the view reach 500 000 rows. 2. Start the reader. 3. Measure the
ingest rate for 60 s with the reader running. 4. Stop the reader; measure for 60 s more.
5. Take three thread dumps during step 3.
**Expected:** the rate during step 3 is about one third of step 4's. Report the exact ratio. Step 5 —
at least one dump shows the `pravaha-feed-*` thread `BLOCKED` on the view's monitor at
`ServedView.commit`, with the monitor held by a Flight executor thread inside `ServedView.scan`.
Quote the two stack frames. Also measure the **reader's** latency: `scan()` on a 500 000-row view was
recorded at 347 ms mean / 1 015 ms max, so report p50/p99/max for the reader's own calls.
**Vacuity:** step 4 restores the rate, which attributes the drop to the reader and not to the growing
view (Section E's separate effect). The thread dump is what attributes it to the monitor rather than
to CPU competition.

## PERF-025 — the reader-count curve, 0 to 16
**Intent:** enumerate the dimension. The recorded data has two points (1 reader, 8 readers) and a
curve is what tells you whether it is contention or saturation.
**Falsifier:** the curve is flat, or is monotonic in a way that contradicts the two recorded points.
**Setup:** `H-SRV` as PERF-024, with a 500 000-row view held at that size (`K = 500 000`, feeding
updates so the size is constant — this is important, see PERF-026).
**Steps:** For readers in 0, 1, 2, 4, 8, 16: 1. Settle 10 s. 2. Measure ingest rows/s over 60 s.
3. Measure each reader's p50/p99 scan latency. 4. Record thread-dump evidence at 8.
**Expected:** a six-point table of ingest rate and reader latency. Anchors to compare against:
0 readers ≈ 520 000 rows/s, 1 reader ≈ 520 000 / 3 ≈ 173 000, 8 readers ≈ 2 000 — a fall of
`520 000 / 2 000 = 260×`. Report the measured ratios at every point. At 16 readers, report whether
ingestion stops entirely and whether the feed thread is ever scheduled; if the rate reaches zero,
say so, because that is a liveness failure and not merely a slowdown.
**Vacuity:** holding the view at a constant 500 000 rows is what separates the reader effect from the
view-growth effect; a growing view would confound every point on the curve.

## PERF-026 — `scan()` cost is linear in view size, and it is the whole of the lock hold
**Intent:** the mechanism. `new ArrayList<>(visible.values())` is O(n) with the monitor held; the
per-row encode-and-accept in `ViewQuery.java:221` happens **after** the copy returns and therefore
outside the lock.
**Falsifier:** scan latency is not linear in view size, or the lock hold is materially longer than
the copy.
**Setup:** `H-EMB` (no feed thread) with a view held at each of 1 000, 10 000, 100 000, 250 000,
500 000, 1 000 000 rows.
**Steps:** For each size: 1. Time `view.scan()` 1 000 times, reporting p50/p99/max. 2. Separately,
instrument the monitor hold time (a JFR `jdk.JavaMonitorEnter` recording, or a wrapper) and compare
with the scan time. 3. Time a full `ViewQuery` read at the same sizes and subtract.
**Expected:** scan latency roughly proportional to size. Anchor: 500 000 rows at 347 ms mean means
about `347 ms / 500 000 = 694 ns` per row, which for a reference copy is implausibly slow and worth
investigating — report the per-row figure at every size and say whether it is constant. At 1 000 000
rows the extrapolation is `694 ns * 1 000 000 = 694 ms` and the recorded max at 500 000 was 1 015 ms,
so report the measured max at 1 000 000 too. Step 2 — the monitor is held for approximately the scan
duration and not longer, confirming the encode loop is outside it. Step 3 — the full read is longer
than the scan, and the difference is the un-contended part.
**Vacuity:** six sizes across three orders of magnitude is what establishes linearity; two points
cannot distinguish O(n) from O(n log n) or from a fixed cost.

## PERF-027 — the commit that waits behind a scan delays every subscriber too
**Intent:** `ViewSink.commit` does `List.copyOf(pending)` and then delivers to every listener
**serially, on the feed thread**, after `ServedView.commit` has returned. So a blocked commit is a
blocked delivery, and the subscription path — which the index calls the product — inherits the read
path's contention.
**Falsifier:** subscriber delivery latency is unaffected by concurrent readers.
**Setup:** `H-SRV`, a 500 000-row view, one subscriber measuring arrival-to-delivery latency per
change, and readers scaling 0 → 8.
**Steps:** 1. Measure subscriber p50/p99/max delivery latency with 0 readers. 2. With 1. 3. With 8.
4. Correlate the worst delivery latencies with reader scan times.
**Expected:** with 0 readers, delivery latency is bounded by the 20 ms commit cadence — p99 around
20 ms. With 8 readers, p99 should rise toward the scan latency, i.e. hundreds of milliseconds to
seconds; report the measured figures and the ratio. The worst deliveries should coincide in time with
the longest scans; show the correlation.
**Vacuity:** the 20 ms cadence is the known floor, so a p99 near 20 ms at 0 readers is the control
that makes the 8-reader figure attributable.

## PERF-028 — the same contention through every read path
**Intent:** `ViewQuery.scan()` is the only production caller of `ServedView.scan()`, but several
client paths reach it. Enumerate them so a fix is known to cover all of them.
**Falsifier:** one path avoids the monitor.
**Setup:** `H-SRV`, 500 000-row view.
**Steps:** With one concurrent full-scan reader running, measure ingest rate while the *other*
reader uses each of: (a) `pravaha query --sql "SELECT * FROM v"` (CLI); (b) Flight SQL
`getStream` on a statement ticket; (c) the REST `POST /api/v1/query` path on the console;
(d) a point read `SELECT * FROM v WHERE id = ?`; (e) `SELECT COUNT(*) FROM v`; (f) a subscription
(no scan at all).
**Expected:** (a), (b) and (c) all go through `ViewQuery.scan()` and all depress ingestion similarly
— report the three rates and confirm they agree. (d) is the interesting one: a point read should be
able to use `readCommitted` (also `synchronized`, but O(1)) rather than a full scan; measure and
report which it uses, because if a `WHERE id = ?` still copies the whole map that is a finding in its
own right. (e) — does `COUNT(*)` scan? Measure. (f) — a subscriber takes no scan, so the ingest rate
should be near baseline; that is the control.
**Vacuity:** (f) is the control that proves the depression is caused by scanning and not by having a
client connected at all.

## PERF-029 — `readLatest`, `readCommitted` and `size` are on the same monitor
**Intent:** every public reader on `ServedView` is `synchronized` on `this` — `apply`, `applyValues`,
`commit`, `readLatest`, `readCommitted`, `scan`, `size`, `pendingChanges`. Only `awaitFrontier` is
not, and its javadoc explains why: "Holding the monitor there would wait for a commit that needs the
monitor: a deadlock in place of a race."
**Falsifier:** a cheap read is not affected by a concurrent expensive scan.
**Setup:** `H-EMB` with a 500 000-row view, one thread in a `scan()` loop.
**Steps:** 1. From another thread, time `readCommitted(key)` 10 000 times with the scanner running,
and 10 000 times with it stopped. 2. The same for `size()`. 3. The same for `pendingChanges()`.
4. Confirm `awaitFrontier` does **not** block behind a scan.
**Expected:** each O(1) operation's p99 with the scanner running is at least as large as the scan's
duration, because it must wait for the monitor — report the ratio against the scanner-stopped
baseline. Step 4 — `awaitFrontier` returns promptly throughout, because it reads a `volatile` and
takes no lock.
**Vacuity:** step 4 is the control: it shows the measurement rig can detect a *non*-blocking call,
so the blocking of the other three is real.

## PERF-030 — the ceiling failure fires from inside the monitor and kills the feed
**Intent:** `commit()` throws `ServingErrors.VIEW_TOO_LARGE` from inside the synchronized block,
after applying and evicting. That exception propagates up the feed thread, and `PumpingFeed` records
it and **returns**, ending the feed. So exceeding `DEFAULT_MAX_KEYS` does not shed load — it stops
ingestion permanently.
**Falsifier:** ingestion continues after the ceiling is hit, or the failure is visible in
`QueryState`.
**Setup:** `H-SRV`, projection keyed on `id`, `K = N`, feeding past `1 000 000`.
**Steps:** 1. Feed until the ceiling fires; record the row count. 2. Keep feeding for 60 s more.
3. `pravaha queries`. 4. `GET /actuator/health`. 5. Read the feed's `describe()`. 6. Read the view.
7. Restart the feed and see whether it recovers.
**Expected:** step 1 — the ceiling fires at `1 000 000` keys. Step 2 — `rowsIn` stops increasing;
the feed thread is gone. Step 3 — the query still reports **`RUNNING`** (PERF-041 is the general
case). Step 4 — `UP`, because `EngineHealthIndicator` reads `query.failure()`, the registry's field,
which is null. Step 5 — `describe()` returns `... -- stopped: <message>`, which is the **only**
surface that says anything; assert it names the view and the ceiling. Step 6 — the view still answers
with 1 000 000 rows, frozen, for ever. Step 7 — record whether a restart of the source recovers or
immediately re-fails.
**Vacuity:** steps 3 and 4 are the non-vacuity: "ingestion stopped" is only alarming if nothing
reports it, and those two steps are what establish that.

---

### E. The eviction sweep (PERF-031–035)

`evict()` is O(|visible|) with a `HashMap.get` per key, called from inside `commit()` — that is, from
inside the monitor, 50 times a second on the feed thread — and skipped only when retention is
`forever()`. `QueryRegistry`'s default is `Retention.DEFAULT`, 24 hours. So on a view of 500 000 rows
with a 24-hour retention, the engine sweeps half a million entries fifty times a second and evicts
nothing. Round 1's `INGEST` finding 15 recorded ingest decaying to a fifth as the view grew, with
`evicted = 0` on all 1 400+ commits measured.

## PERF-031 — the sweep runs on every commit, and normally evicts nothing
**Intent:** establish the mechanism before measuring its cost: the sweep is unconditional on the
horizon check passing, not on anything being evictable.
**Falsifier:** `evict()` is skipped when nothing is evictable, or runs fewer than 50 times a second.
**Setup:** `H-SRV`, projection keyed on `id`, `K = N`, default 24-hour retention, feeding steadily.
Instrument `ServedView.evict` to count invocations and entries examined, and read the `evicted`
counter.
**Steps:** 1. Feed for 60 s while the view grows to 500 000. 2. Read the invocation count, the total
entries examined, and `evicted`.
**Expected:** about `60 * 50 = 3 000` invocations. Entries examined is the integral of the view size
over the run — for a view growing linearly from 0 to 500 000 over 3 000 commits, that is about
`3 000 * 250 000 = 750 000 000` map entries visited, each with a `HashMap.get` on `writtenAt`.
`evicted == 0`, because the retention horizon is 24 hours behind a frontier that spans seconds.
Report all three numbers.
**Vacuity:** `evicted == 0` alongside 750 million entries visited is the entire finding, and it is
only legible because both are counted.

## PERF-032 — ingest rate as a function of view size, with retention on and off
**Intent:** the controlled experiment. `Retention.forever()` short-circuits at `ServedView.java:355`,
so running the identical feed twice with only that changed isolates the sweep's cost from everything
else.
**Falsifier:** the two curves are indistinguishable, which would mean the sweep is not the cause of
the decay.
**Setup:** `H-SRV`, projection keyed on `id`, `K = N`, `N = 900 000`. Arm A:
`Retention.DEFAULT` (24 h). Arm B: `Retention.forever()` via `registry.retainingFor(...)`.
**Steps:** For each arm: 1. Feed, sampling rows/s and view size every second. 2. Plot rate against
view size. 3. Report the rate at view sizes 10 000, 100 000, 250 000, 500 000 and 900 000.
**Expected:** Arm A decays as the view grows — round 1 recorded ingest falling to a fifth. Report the
ratio `rate(900 000) / rate(10 000)` for both arms. Arm B should be materially flatter; the gap
between the two curves at each view size **is** the sweep's cost, in rows/s. Report it as a table of
five differences. If Arm B decays similarly, the sweep is not the dominant cost and something else is
— say so, because the fix would then be wrong.
**Vacuity:** two arms differing in one flag, over an identical feed, is the whole design; a
single-arm measurement cannot attribute decay to anything.

## PERF-033 — the sweep with retention actually expiring
**Intent:** the case where the sweep does work. Here `evicted` is non-zero and the view reaches a
steady state, which is the regime retention is designed for — and the cost profile is different.
**Falsifier:** the view grows without bound despite an expiring retention, or evicts rows that are
still within the horizon.
**Setup:** `H-SRV`, projection keyed on `id`, `K = N`, `ts` advancing 1 ms per row,
`Retention.ofAge(Duration.ofSeconds(10))`. Feed 2 000 000 rows, so event time spans 2 000 s and the
retention horizon is ten seconds behind the committed frontier.
**Steps:** 1. Feed, sampling view size, `evicted` and rows/s every second. 2. Identify the steady
state.
**Expected:** the view plateaus at about `10 s / 1 ms = 10 000` rows. `evicted` grows to about
`2 000 000 - 10 000 = 1 990 000`. Report the plateau, the steady-state rate, and compare with
PERF-032 Arm A at the same view size (10 000 rows): the rates should be similar, because the sweep
cost depends on view size and not on how many entries it removes. That comparison is the point — it
shows retention helps by keeping the view small, not by making the sweep cheaper.
**Vacuity:** the plateau at a hand-computed 10 000 rows, rather than "the view stopped growing", is
what makes the retention arithmetic checkable.

## PERF-034 — the retention matrix: five settings by two key regimes
**Intent:** enumerate rather than gesture. Retention interacts with key cardinality, and the product
of the two is what an operator actually configures.
**Falsifier:** any cell's steady-state view size differs from the computed one.
**Setup:** `H-SRV`, projection, `ts` advancing 1 ms per row, `N = 2 000 000`. Ten runs.
**Steps:** for retention in `forever()`, `24h` (default), `60s`, `10s`, `1s`, and for `K = N` and
`K = 500`:
**Expected:**

| retention | `K = N` steady view | `K = 500` steady view | sweep runs? |
|---|---|---|---|
| `forever()` | grows to `DEFAULT_MAX_KEYS = 1 000 000` then PRV view-too-large | 500 | **no** — short-circuits |
| `24h` | same as above; horizon never reached in a 2 000 s feed | 500 | yes, evicts 0 |
| `60s` | `60 s / 1 ms = 60 000` | 500 | yes, evicts |
| `10s` | `10 s / 1 ms = 10 000` | 500 | yes, evicts |
| `1s` | `1 s / 1 ms = 1 000` | 500 | yes, evicts |

The `K = 500` column is 500 in every row because a key rewritten every 500 rows has its `writtenAt`
refreshed and never ages out — which is correct, and worth stating, because it means retention does
nothing at all for an update-heavy view. Report the rows/s for all ten cells.
**Vacuity:** the `K = 500` column being constant across five retentions is the assertion that catches
a retention implementation keyed on insertion time rather than on last write.

## PERF-035 — the sweep and the readers compound
**Intent:** Sections D and E are two consumers of one monitor. Measured separately they are two
costs; measured together the question is whether they add or multiply.
**Falsifier:** the combined rate is close to the worse of the two individual rates (they do not
compound), or far below the product (something else is happening).
**Setup:** `H-SRV`, view held at 500 000 rows. Four arms: (a) no readers, `forever()`;
(b) no readers, `24h`; (c) 8 readers, `forever()`; (d) 8 readers, `24h`.
**Steps:** 1. Measure the sustained ingest rate in each arm over 60 s. 2. Take thread dumps in (d).
**Expected:** report all four rates. (a) is the ceiling. (b) shows the sweep's cost alone; (c) the
readers' cost alone; (d) both. State whether `rate(d)` is closer to `min(rate(b), rate(c))`,
to `rate(a) * (rate(b)/rate(a)) * (rate(c)/rate(a))`, or to something else, and show the arithmetic.
Step 2 — the dumps should show the feed thread blocked on the monitor; report what fraction of dumps
find it blocked, as a crude duty-cycle estimate.
**Vacuity:** the four-arm factorial is the design; three arms cannot separate an additive from a
multiplicative interaction.

---

### F. Arena exhaustion, and the lane that dies without telling anyone (PERF-036–043)

Each lane owns a `RowArena` of `arenaMaxSlabs * arenaSlabBytes = 8 * 4 MiB = 33 554 432 bytes`, not
configurable (PERF-004). `allocate` returns `ArenaHandle.NULL` at the ceiling — "a backpressure
signal the lane handles, not an exceptional condition" — and every operator that receives `NULL`
throws `PravahaException(RuntimeErrors.ARENA_EXHAUSTED)`, PRV-3001. That exception is caught by
`Lane.run`'s `catch (Throwable)`, which sets `State.FAILED`, exits the thread, and closes the
processor and the arena. Nothing on the serving path polls `checkHealth()`.

## PERF-036 — a projection exhausts its arena at about 932 000 rows, and the number is a byte budget
**Intent:** turn the recorded threshold into an arithmetic statement about row width, so it can be
predicted for other schemas rather than re-measured each time.
**Falsifier:** the threshold is not reproducible to within 5 % across three runs, or does not scale
inversely with row width.
**Setup:** `H-SRV`, `SELECT id, user_id, amount FROM txn` keyed on `id`, `K = N`,
`Retention.forever()` so eviction cannot confound it, and **no readers**. Feed until it fails.
**Steps:** 1. Feed, recording `rowsIn` at the moment of failure. 2. Repeat three times. 3. Repeat
with a schema of twice the row width (double the string column's length) and with half.
4. Compute bytes per row.
**Expected:** failure at about `932 000` rows. Bytes per row:
`33 554 432 / 932 000 = 36.0` bytes. Report the measured figure to three significant digits. Step 3 —
at double the row width expect about `932 000 / 2 = 466 000` rows, at half about `1 864 000`; report
the measured thresholds and whether the inverse relationship holds. If it does, the threshold is a
byte budget and can be predicted; if it does not, something other than row bytes is accumulating and
that is the finding.
**Vacuity:** the three-way width sweep is what turns a single number into a model; one measurement
cannot distinguish a byte budget from a row count.

## PERF-037 — a windowed aggregate exhausts its arena at about 264 000 rows
**Intent:** the same budget spent on a heavier row. `264 000` against `932 000` is a factor of
`932 / 264 = 3.53`, which is the cost of carrying window state.
**Falsifier:** the threshold is the same as the projection's, or is not reproducible.
**Setup:** `H-SRV`, `SELECT id, SUM(amount) AS total FROM txn GROUP BY id, TUMBLE(ts, INTERVAL '10'
SECOND)`, `K = N`, `ts` advancing 1 ms per row so a single window holds 10 000 keys.
`Retention.forever()`, no readers.
**Steps:** 1. Feed until failure; record the row count. 2. Repeat three times. 3. Repeat with window
sizes of 1 s, 10 s and 60 s. 4. Repeat with `K = 500`.
**Expected:** failure at about `264 000` rows, i.e. `33 554 432 / 264 000 = 127.1` bytes per row —
`127.1 / 36.0 = 3.53×` the projection's. Step 3 — a longer window keeps more keys open at once, so a
60 s window should fail sooner than a 10 s one and a 1 s window later; report the three thresholds
and whether they scale with `windowSeconds`. Step 4 — at `K = 500` the open window holds at most 500
keys regardless of `N`, so the query should **not** exhaust the arena at all; run to 5 000 000 rows
and confirm. That is the control: arena exhaustion here is driven by open-window key cardinality, not
by row count.
**Vacuity:** the `K = 500` arm completing 5 000 000 rows while the `K = N` arm fails at 264 000 is
what identifies the variable. Without it the threshold could be mistaken for a row-count limit.

## PERF-038 — the arena threshold for every plan shape
**Intent:** the matrix. Five shapes, one number each, so an operator sizing a deployment has the
table rather than one anecdote.
**Falsifier:** any shape's threshold is not reproducible to within 5 %.
**Setup:** `H-SRV`, `Retention.forever()`, no readers, `K = N` throughout, identical input schema.
**Steps:** for each of: plain projection; filter at 10 % selectivity; global aggregate
(`SELECT COUNT(*) FROM txn`); keyed aggregate; stream join; windowed aggregate — feed until failure
and record the row count, the error code, the throwing operator and the message.
**Expected:** a six-row table of `rows at failure`, `bytes/row = 33 554 432 / rows`, `error code`,
`throw site`, `message`. Anchors: projection ≈ 932 000 (36.0 B/row), windowed ≈ 264 000
(127.1 B/row). Predicted messages, from the source:
`GlobalAggregate.java:151` `no room to emit the aggregate result`;
`KeyedAggregate.java:129` `no room to emit a grouped aggregate result`;
`WindowedAggregate.java:278` `no room to emit a window result for key <key>`;
`WindowAssign.java:68` `the window assigner's arena is full; raise arena.slab.size`;
`LookupJoin.java:308` `the lookup join's arena is full; raise arena.slab.size`;
`SymmetricHashJoin.java:169` and `:204`; `InterpretedPipeline.java:643` and `:705`. Every one is
PRV-3001. Note the two messages naming `arena.slab.size`, which is not a setting (PERF-004) — so the
only actionable message is the one that names the key, and it names a key that does not exist.
**Vacuity:** recording which operator threw, not just that PRV-3001 occurred, is what makes the table
useful; six identical "arena exhausted" lines would not be.

## PERF-039 — a row larger than one slab is refused at allocation, not at the ceiling
**Intent:** `RowArena.java:85`–`:88` throws `IllegalArgumentException("row of N bytes exceeds the
slab size of 4194304; raise arena.slab.size for this query")` — a different failure from exhaustion,
and one that fires on the first such row rather than after a million.
**Falsifier:** an over-large row is accepted, or produces PRV-3001 instead.
**Setup:** `H-SRV` with a stream whose schema includes a STRING column, feeding a single row whose
string is 5 MiB — larger than `DEFAULT_SLAB_BYTES = 4 * 1024 * 1024 = 4 194 304`.
**Steps:** 1. Feed one normal row; confirm it works. 2. Feed the 5 MiB row. 3. Feed another normal
row. 4. Read `pravaha queries` and the view.
**Expected:** step 2 throws `IllegalArgumentException` — **not** a `PravahaException`, so it carries
no PRV code — with `row of 5242880 bytes exceeds the slab size of 4194304`. It is caught by
`Lane.run`'s `catch (Throwable)`, so the lane dies on it. Step 3's row is never processed. Step 4 —
the query still reports `RUNNING` (PERF-041). Also test the boundary: a row of exactly
`4 194 304` bytes and one of `4 194 303`, and report which side of the comparison is inclusive.
**Vacuity:** step 1 is the control showing the pipeline worked immediately before; the boundary pair
is what pins the comparison rather than assuming it.

## PERF-040 — the lane thread dies and the arena is released, but the query keeps its state
**Intent:** `Lane.run`'s `finally` sets `running = false` and calls `closeQuietly()`, which closes
the processor and the arena. So after a lane death the off-heap memory is back and the view is
frozen at whatever was last committed.
**Falsifier:** the arena is not released, or the view keeps changing.
**Setup:** `H-SRV`, projection, fed until PERF-036's threshold.
**Steps:** 1. Record native memory (`jcmd <pid> VM.native_memory summary`, or RSS) before the feed,
at 900 000 rows, and 30 s after the lane dies. 2. Record the thread list before and after.
3. Read the view before and 60 s after. 4. `LaneMetrics` after.
**Expected:** the `pravaha-query-*` thread is gone after the death. Native memory attributable to the
arena returns — report the three figures and the delta. The view answers the same rows before and 60 s
after, unchanged, for ever. `LaneMetrics` reports the lane's final `rowsIn` and `idleCycles`, frozen.
Also assert the **feed** thread's fate: rows it publishes now go into an inbox nothing drains, so
report whether the feed blocks, spins, or reports `BACKPRESSURED` — the inbox fills at
`inboxCells = 2048` and `IngestPump.pumpOnce` then returns 0 forever (`freeCells() == 0`), so expect
the feed thread alive and doing nothing. That is a second silent failure on top of the first.
**Vacuity:** the native-memory triple is what distinguishes "released" from "the process happens not
to have grown"; the 60 s re-read of the view is what distinguishes "frozen" from "slow".

## PERF-041 — a dead lane is invisible on every operator surface
**Intent:** the finding this section exists for. `checkHealth()` has four callers in `src/main` and
none of them is on the server path; `RegisteredQuery.fail()` is reachable only from `accept()` and
`advanceWatermark()`, and a server-fed query uses neither — rows arrive through
`PumpingFeed` → `IngestPump.pumpOnce` → `lane.claim()/publish()`, and
`advanceWatermarkQuietly` swallows every `RuntimeException` ("Never let the clock die").
**Falsifier:** any of the surfaces below reports the failure.
**Setup:** `H-SRV` after PERF-036's arena exhaustion has killed the lane.
**Steps:** 1. `pravaha queries`. 2. Flight LIST through the Java SDK (`client.queries()`). 3.
`GET /api/v1/queries` and `/api/v1/queries/{name}` on the console; and the `/queries` page.
4. `GET /actuator/health`. 5. The Micrometer gauge `pravaha.query.running`. 6. `GET
/actuator/prometheus` and grep for anything lane-related. 7. The server log. 8. The feed's
`describe()`. 9. `registry.find(name).get().state()` in-process. 10. `execution.checkHealth()`
in-process.
**Expected:** steps 1, 2, 3 and 9 all report **`RUNNING`**. Step 4 is `UP`, because
`EngineHealthIndicator` reads `query.failure()` — the registry's field, never set on this path.
Step 5 is `1`, because the gauge is `q -> q.state().isTerminal() ? 0 : 1`. Step 6 — report whether
any lane metric exposes `State.FAILED`; `LaneMetrics` carries `idleCycles` and `rowsIn`, so a
**stalled `rowsIn` alongside a live feed** is the only inferable signal, and it requires a human to
notice. Step 7 — record whether anything is logged at all. Step 8 — the feed is *not* stopped (the
lane died, not the feed), so `describe()` does **not** carry the `-- stopped:` suffix; this is the
case `PumpingFeed`'s failure recording does not cover. Step 10 is the only thing that throws:
PRV-3010 `lane 0 stopped after a failure: <cause>`. Nine surfaces say healthy; one in-process call
that nothing invokes says otherwise.
**Vacuity:** enumerating ten surfaces rather than one is the case. A single "the CLI says RUNNING"
observation could be a CLI bug; ten agreeing surfaces establish that the information never leaves the
lane.

## PERF-042 — an arena exhaustion under the CLI *is* reported, which shows the gap is the server path
**Intent:** `QueryRunner.java:137` and `:155` call `checkHealth()`. So the same failure, driven
through `pravaha run` instead of a server, surfaces with its code and message. The contrast is what
makes PERF-041 a wiring defect rather than a design choice.
**Falsifier:** the CLI also swallows it.
**Setup:** the same plan and the same input, run through the CLI's local execution path rather than
through a server.
**Steps:** 1. Run the same projection over the same 1 000 000-row input with `pravaha run`. 2. Record
stdout, stderr, the exit code and the elapsed time. 3. Compare with PERF-041's server run.
**Expected:** the CLI fails with `PravahaException` PRV-3010,
`lane 0 stopped after a failure: ... PRV-3001 ...`, a non-zero exit code, and the message on stderr.
The server run, with the identical plan and data, reports `RUNNING` and exits nothing. Put the two
side by side in the result.
**Vacuity:** identical plan and identical input across the two paths is what isolates the difference
to the calling code.

## PERF-043 — `awaitQuiescent` is the only server-reachable health check, and nothing calls it either
**Intent:** complete the enumeration, so a fix has a known list of insertion points.
**Falsifier:** some server-path code calls `checkHealth` or `awaitQuiescent`.
**Setup:** the repository.
**Steps:** 1. `grep -rn "checkHealth" --include=*.java . | grep "/src/main/"`. 2. The same for
`awaitQuiescent`. 3. `grep -rn "LaneMetrics\|lanes.metrics()" --include=*.java . | grep "/src/main/"`.
4. Identify every place a server-fed query's health *could* be checked: the 20 ms commit
(`RegisteredQuery.commit`), the 1 s watermark tick, the LIST action, the metrics scrape
(`PravahaMetrics.SYNC_SECONDS = 15`), and the health indicator.
**Expected:** step 1 — `QueryRunner.java:137`, `:155`; `QueryExecution.java:654`, `:657`, `:661`,
`:736`. Step 2 — `QueryExecution` itself and the CLI. Step 3 — report who reads lane metrics on the
server path; if `PravahaMetrics` does, then the raw material for detection is already being collected
every 15 seconds and only the `State.FAILED` field is missing from it, which is a much smaller fix
than it looks. Step 4 — five natural insertion points, each with a stated cadence. Record the list;
it is the deliverable.
**Vacuity:** n/a — an enumeration, and the actionable output of Section F.

---

### G. Leaks over many register/drop cycles (PERF-044–049)

`drop` releases the lane thread, the feed thread, the watermark executor and (silently) the
checkpointer. It does not clear `ViewSink.listeners`, does not release the `ServedView`'s maps, and
`QueryRegistry.close()` never deletes checkpoint directories. `Lane.close()` throws PRV-3010 if the
lane will not stop within `shutdownTimeout = 5 s`, deliberately leaking the inbox and arena rather
than freeing memory under a live thread.

## PERF-044 — 1 000 register/drop cycles leave no threads behind
**Intent:** the thread half of the leak question, measured at a scale where one leaked thread per
cycle is unmistakable.
**Falsifier:** the thread count after the cycles exceeds the baseline by more than a handful.
**Setup:** `H-SRV`, one stream bound, no readers.
**Steps:** 1. Baseline thread list and count. 2. Loop 1 000 times: register a query with distinct SQL
(so each is a new computation), push 100 rows, `commit`, drop it. 3. Settle 60 s. 4. Thread list and
count; group by name prefix. 5. Repeat with each query fed 10 000 rows instead of 100.
**Expected:** step 4's count equals step 1's. Specifically zero threads named `pravaha-query-*`,
`pravaha-feed-*`, `pravaha-watermark` or `pravaha-checkpointer`. Any non-zero count is a leak of
exactly that many per 1 000 cycles — report the rate. Step 5 exists because a busier lane is more
likely to be inside the processor when `close()` arrives, which is the path that leaks (PERF-046).
**Vacuity:** 1 000 cycles with a per-prefix breakdown is what makes a one-per-hundred leak visible;
ten cycles and a total count would not.

## PERF-045 — 1 000 register/drop cycles leave no heap behind
**Intent:** the memory half. `ViewSink` has no `close()`, `listeners` is never cleared, and nothing
clears `ServedView.visible`, `pending`, `writtenAt` or `pendingTime`.
**Falsifier:** live-set heap after a full GC grows monotonically with cycle count.
**Setup:** as PERF-044, with `-XX:+HeapDumpOnOutOfMemoryError` and a modest `-Xmx` (say 512 MiB) so a
leak reaches a limit rather than hiding in headroom.
**Steps:** 1. Baseline: three full GCs, then live-set size from `jcmd GC.heap_info`. 2. After 100,
250, 500 and 1 000 cycles: three full GCs, record live set. 3. At 1 000, take a heap dump and count
instances of `ServedView`, `ViewSink`, `RegisteredQuery`, `QueryExecution`, `Subscription`,
`InterpretedPipeline` and `RowArena`. 4. For any class with a non-zero count, find the GC root path.
**Expected:** the live set is flat across the four measurements to within GC noise. Step 3 — zero
live instances of each class, or a constant small number explained by the still-registered set (which
is zero at the end of a cycle). Any retained `ServedView` holds its whole `visible` map, so one
retained view from a 10 000-row cycle is 10 000 rows of garbage — report the retained-size figure,
not just the instance count. Step 4's root path is the deliverable for any leak found.
**Vacuity:** three full GCs before each measurement, and retained size rather than instance count, is
what distinguishes a leak from uncollected garbage.

## PERF-046 — a lane that will not stop within 5 s leaks its inbox and arena, by design
**Intent:** `Lane.close()` throws PRV-3010 with `its thread is still in the processor, and the inbox
and arena it owns cannot be released while it is` — the off-heap memory is deliberately not freed.
The case measures what that costs and confirms the exception reaches the caller of `drop`.
**Falsifier:** the memory is freed anyway, or `drop` swallows the exception.
**Setup:** `H-EMB` with a processor deliberately blocked inside `onBatch` on a latch held by the
test, so the lane cannot reach its loop top.
**Steps:** 1. Register, feed, block the processor. 2. `registry.drop(name)`; time it and catch.
3. Record native memory before and after. 4. `registry.names()` and `registry.find(name)`.
5. Release the latch; wait 30 s; record native memory again. 6. Repeat 50 times without releasing
the latch and measure the native-memory slope.
**Expected:** step 2 blocks for `shutdownTimeout = 5 s` and then throws `PravahaException` PRV-3010
with that message. Step 3 — native memory unchanged: the arena (up to 32 MiB) and the inbox
(`inboxCells * inboxCellBytes = 2048 * 512 = 1 048 576` bytes) are still held. Step 4 — the name is
**already gone** from `byName` and the journal has already recorded the drop (see `STATE-084` arm C),
so the client got an exception for a drop that partly happened. Step 5 — record whether the released
lane then cleans up; if it does, the leak is transient and the case says so. Step 6 — 50 stuck lanes
leak `50 * (32 MiB + 1 MiB) = 1 650 MiB` at the ceiling; report the measured slope.
**Vacuity:** the native-memory measurement before and after, rather than heap, is essential — the
arena is off-heap and a heap-only measurement would show nothing.

## PERF-047 — a subscriber keeps a dropped query's view alive
**Intent:** `ViewSink.listeners` is a `CopyOnWriteArrayList` that `RegisteredQuery.close()` does not
clear, and the subscription's handle is removed only if the subscriber closes it. So a subscriber
that never closes pins the sink, the view and every `Object[]` in it.
**Falsifier:** the view is collected while a subscription is open.
**Setup:** `H-EMB`, `-Xmx512m`.
**Steps:** 1. Register a query; feed 200 000 rows across 200 000 keys; commit. 2. Subscribe and
**do not** close the subscription. 3. `registry.drop(name)`. 4. Three full GCs; heap dump. 5. Count
live `ServedView` instances and their retained sizes. 6. Repeat with the subscription closed first.
7. Repeat 20 times without closing and record the heap trend.
**Expected:** step 5 — one live `ServedView` retaining roughly `200 000 * <row bytes>`; report the
retained size and the GC root path, which should run through `ViewSink.listeners` to the
`Subscription` to the test's reference. Step 6 — zero live `ServedView`. Step 7 — the heap grows by
one view per iteration and reaches `OutOfMemoryError` at a computable cycle count; report where.
**Vacuity:** step 6 is the control that attributes the retention to the open subscription rather than
to the harness holding a reference.

## PERF-048 — checkpoint directories survive a server stop, and a shared computation leaks one
**Intent:** `QueryRegistry.close()` calls only `RegisteredQuery::close` and never
`deleteCheckpointsOf`, so a shutdown leaves every directory — which is correct, because they are the
recovery fallback. The leak is the `drop` path on a shared computation (`STATE-035`), and this case
measures it at scale.
**Falsifier:** directory count tracks live query count.
**Setup:** `H-SRV` with `pravaha.checkpoint.directory` set.
**Steps:** 1. Register 10 queries; stop the node; count directories; restart. 2. 100 register/drop
cycles of a single-name query; count directories. 3. 100 cycles of a *pair* of names sharing one
fingerprint, dropping the first name then the second; count directories. 4. Measure the disk used.
**Expected:** step 1 — 10 directories survive the stop, and the restart resumes checkpoint ids above
the stored maximum. Step 2 — zero directories after the cycles: `drop` deletes the directory of the
only name. Step 3 — **100 directories**, one per cycle, because `deleteCheckpointsOf` is called with
the second name while the directory was created under the first. That reproduces the "52 directories
for 2 live queries, in a QA run of 50 register/drop cycles" the code comment records. Step 4 — report
bytes leaked per cycle: each directory holds up to `keep = 3` checkpoint files.
**Vacuity:** step 2 is the control proving the delete works for the unshared case, which makes step
3 attributable to sharing rather than to a broken delete.

## PERF-049 — a soak: 24 hours of steady traffic with periodic register/drop
**Intent:** the cases above are short. Several of the mechanisms here — the journal growing for the
life of the deployment (`STATE-093`), file descriptors, checkpoint ids, the `LongAdder` counters,
metric cardinality — only show up over time.
**Falsifier:** any monitored quantity trends upward without bound.
**Setup:** `H-SRV`, steady feed at a rate comfortably below PERF-013's ceiling (say 50 000 rows/s),
`K = 500` so the view does not grow, `Retention.ofAge(Duration.ofMinutes(10))`, one register/drop
cycle per minute of a throwaway query, one long-lived subscriber, and one reader issuing a scan every
10 s.
**Steps:** Every 5 minutes for 24 hours, record: RSS; heap live set after a GC; native memory; thread
count by prefix; open file descriptors (`ls /proc/<pid>/fd | wc -l`); ingest rows/s; subscriber
delivery p99; scan p99; registry journal size; checkpoint directory count and total bytes; the
Micrometer metric count; GC pause total. Then plot each.
**Expected:** flat: RSS, heap live set, native memory, threads, file descriptors, ingest rate,
delivery p99, scan p99, directory count. Monotonically increasing and **expected**: the registry
journal, at `1 440` cycles × (one `R` plus one `D` record) — with `STATE-093`'s arithmetic that is
about `1 440 * 115 = 165 600` bytes a day, so about 60 MB a year, and nothing compacts it. Report the
measured daily growth. Anything else that trends is a finding; name it, give its slope, and project
the date it becomes a problem.
**Vacuity:** twelve monitored quantities over 288 samples is the design — a soak that watches only
RSS finds only the leaks that reach RSS, and the journal, the descriptors and the metric cardinality
are three that would not.

---

### H. Backpressure (PERF-050–054)

Source to lane is bounded and pauses the plugin: `IngestPump.pumpOnce` polls at most `freeCells()`
records, and `BackpressurePolicy.defaults()` is `(0.8, 0.5)` — pause the reader at 80 % inbox fill,
resume at 50 %. Lane to view has no queue at all (it is synchronous on the lane thread). View to
subscriber is bounded and conflates or drops; blocking is explicitly not on the list.

## PERF-050 — a source faster than the consumer pauses rather than dropping or growing
**Intent:** the central backpressure claim: "It never asks for more than it can hold... there is no
queue between the reader and the lane to grow."
**Falsifier:** memory grows without bound, rows are dropped, or the reader is never paused.
**Setup:** `H-SRV` with a source plugin that can produce far faster than the pipeline consumes — a
generator source at 5 000 000 rows/s feeding a query whose per-row cost is high (a windowed aggregate
over 500 keys). Instrument `IngestPump`'s `pauses`, `resumes` and `pausedNanos`.
**Steps:** 1. Run for 300 s. 2. Sample inbox fill, `pauses`, `resumes`, `pausedNanos`, RSS and native
memory every second. 3. Count rows produced by the source and rows that reached the view.
**Expected:** inbox fill oscillates between the two thresholds — pausing at
`0.8 * 2048 = 1638.4`, so 1 638 cells, and resuming at `0.5 * 2048 = 1024` cells. `pauses` and
`resumes` both grow and stay within 1 of each other. `pausedNanos` accumulates to a large fraction of
the run — report it as a percentage of 300 s, which is the honest measure of how much faster the
source is. RSS and native memory flat. **Rows produced equals rows reaching the view plus rows the
source still holds**: no row is dropped anywhere in this path; assert the three numbers close, and if
they do not, the difference is a silent loss and is the finding.
**Vacuity:** the closing arithmetic on row counts is the non-vacuity; "memory stayed flat" is also
satisfied by a pipeline that discards rows.

## PERF-051 — a rejected offer into a full inbox is a refusal, not a spin
**Intent:** `Lane.offer` returns `false` and increments `rejectedOffers`; that is "the caller's cue to
pause its source, not a reason to spin". And a claim that fails *inside a sized poll* throws
PRV-BACKPRESSURED, because it means the sizing was wrong.
**Falsifier:** `offer` blocks, or a sized poll silently drops.
**Setup:** `H-EMB` with a lane whose processor is slowed to a crawl, and a producer calling
`lane.offer` in a loop.
**Steps:** 1. Fill the inbox; count `offer` returning `false` and read `rejectedOffers`. 2. Measure
the producer thread's CPU during the rejections. 3. Drive the same overload through `IngestPump` with
a reader that reports more available records than it has, so a claim fails mid-poll. 4. Repeat step 3
through `PartitionedIngestPump` with a key distribution that fills one lane while others are empty.
**Expected:** step 1 — `offer` returns `false` immediately, never blocks; `rejectedOffers` equals the
count of `false` returns. Step 2 — a producer that respects the `false` and backs off uses little
CPU; one that retries immediately uses a core, which is why the javadoc says what it says — report
both. Step 3 — `PravahaException` `RuntimeErrors.BACKPRESSURED` with
`lane 0's inbox filled during a poll that was sized to fit.` Step 4 —
`lane N's input M filled during a poll sized to fit. With rows routed by key, one lane can fill while
others are empty; the free-cell estimate must be taken over the fullest lane, not the average.`
Confirm `PartitionedIngestPump.freeCells()` uses `fullestLane()` and not an average, by constructing
exactly the skew the message describes.
**Vacuity:** step 4's deliberate key skew is what exercises the `fullestLane()` logic; a uniform key
distribution never distinguishes it from an average.

## PERF-052 — the backpressure hysteresis is edge-triggered, and the thresholds are the defaults
**Intent:** `BackpressurePolicy.defaults()` is `(0.8, 0.5)` — "design section 13.5's defaults" — and
the constructor rejects `low >= high`. Edge-triggered means `pause()`/`resume()` reach the plugin
once per crossing, not once per poll.
**Falsifier:** the plugin sees a pause on every poll while full, or the thresholds differ.
**Setup:** `H-SRV` with a source plugin recording every `pause()` and `resume()` call with a
timestamp.
**Steps:** 1. Drive the inbox slowly from empty to full and back, three times. 2. Count `pause` and
`resume` calls and the fill at each. 3. Construct `new BackpressurePolicy(0.5, 0.5)` and
`(0.9, 0.95)`.
**Expected:** exactly three `pause` calls and three `resume` calls, at fills of about
`0.8 * 2048 = 1638` and `0.5 * 2048 = 1024` cells — not hundreds of calls. Step 3 — both constructions
throw, because `low >= high`; record the message. Also assert there is no configuration key for
either threshold (PERF-004).
**Vacuity:** three cycles rather than one is what proves it is edge-triggered; a single crossing gives
one call under either implementation.

## PERF-053 — a slow subscriber is conflated or dropped, and the engine does not slow down
**Intent:** `SubscriptionOptions.DEFAULT = (10_000, CONFLATE)`, with `DROP_OLDEST` and `FAIL` as the
alternatives. "Blocking is not on the list: a subscriber that blocks the engine applies backpressure
to the *query*."
**Falsifier:** ingest slows when a subscriber is slow, or rows are lost that conflation should have
preserved.
**Setup:** `H-SRV`, `K = 500` keyed aggregate so conflation has same-key changes to collapse. A
subscriber sleeping 100 ms per batch. Feed 2 000 000 rows.
**Steps:** For each of `CONFLATE`, `DROP_OLDEST` and `FAIL`, and for buffer sizes 10, 1 000 and
10 000 (nine runs): 1. Measure ingest rows/s with and without the slow subscriber. 2. Count changes
delivered, `conflated` and `dropped`. 3. For `FAIL`, record when and how it throws. 4. Read the final
view.
**Expected:** ingest rate unaffected in all nine runs — report the with/without ratio, which should be
within 10 %. `CONFLATE` — `delivered + conflated + dropped` accounts for every change produced; at a
buffer of 10 000 and 500 keys, conflation should absorb nearly everything and `dropped` should be
small; at a buffer of 10 it should be larger. `DROP_OLDEST` — `conflated == 0` and `dropped` carries
the whole overflow. `FAIL` — throws `subscriber on 'v' fell more than <bufferRows>` once the buffer
is exceeded; record the row count at which it fires and that it does not take the query down with it.
Step 4 — the final view is **identical in all nine runs**, `500` rows each totalling
`2 000 000 / 500 = 4 000`, because subscriber policy must not affect the answer.
**Vacuity:** the identical final view across nine runs is the correctness assertion; the arithmetic
closing on `delivered + conflated + dropped` is what makes the loss accounted rather than assumed.

## PERF-054 — the whole chain under sustained overload, and what gives first
**Intent:** put the three bounded stages together — source→lane (pauses), lane→view (synchronous),
view→subscriber (conflates) — and identify the binding constraint.
**Falsifier:** memory grows without bound anywhere, or the system does not reach a steady state.
**Setup:** `H-SRV`. Source at 5 000 000 rows/s. A windowed aggregate over 500 keys. Eight readers
scanning. One slow subscriber. `Retention.ofAge(Duration.ofMinutes(1))`. Run 30 minutes.
**Steps:** 1. Sample every second: source pause fraction, inbox fill, lane `rowsIn`, commit interval,
view size, `evicted`, subscriber `conflated`/`dropped`, reader scan p99, RSS, native memory, GC.
2. Identify the stage with the highest utilisation. 3. Remove that stage's constraint (drop the
readers) and repeat.
**Expected:** a steady state with flat memory. Report which stage is the bottleneck — Sections D and E
predict it is the view monitor, not the source or the lane, so expect the source paused most of the
time, the inbox mostly empty, and the feed thread blocked on `ServedView.commit`. Step 3 — removing
the readers should raise end-to-end throughput substantially; report the before and after and the
ratio, which quantifies what Section D costs in a realistic mix.
**Vacuity:** step 3 is the non-vacuity: naming a bottleneck is only meaningful if removing it moves
the number.

---

### I. Scale ceilings (PERF-055–060)

Four ceilings are hard-coded and documented above; several more are absent, which is itself the
answer. `QueryRegistry` has no cap on the number of queries; `ViewSink.listeners` has no cap on
subscribers; there is no cap on streams. The practical ceilings are derived: one lane thread plus one
feed thread plus one watermark executor per query, and 32 MiB of arena per lane.

## PERF-055 — how many registered queries one node holds
**Intent:** "fine at tens, and the reason ADR-027 wants a lane to multiplex several queries before
this reaches hundreds." Find the number.
**Falsifier:** the node degrades before 50, or survives 10 000, either of which contradicts the
comment.
**Setup:** `H-SRV`, `-Xmx4g`, an empty bound source so every query is idle, then a second run with
each query fed 1 000 rows/s.
**Steps:** For `n` in 10, 50, 100, 200, 500, 1 000, 2 000: 1. Register `n` distinct queries; record
the registration latency of the `n`th. 2. Record thread count, RSS, native memory, process CPU.
3. Time `pravaha queries` and a point read. 4. Note the first `n` at which anything fails or exceeds
a stated service level (registration over 1 s; `pravaha queries` over 1 s; CPU over 50 % idle).
5. Record the failure mode at the ceiling — `OutOfMemoryError`, thread creation failure, or
degradation.
**Expected:** a seven-row table. Threads ≈ `3n + c`. Native memory ≈ `n * 4 MiB` if first slabs are
eager (`1 000 * 4 MiB = 4 GiB`, which at `-Xmx4g` plus off-heap will bind before 1 000) — measure and
report which of the eager-first-slab or lazy behaviours holds, because it changes the ceiling by a
factor of eight. Report the number of queries at which each service level breaks. If the node reaches
2 000 without failing, say so and report the cost per query.
**Vacuity:** the stated service levels, decided before the run, are what make "degrades" a
measurement; without them the ceiling is wherever the tester loses patience.

## PERF-056 — how many streams one node holds
**Intent:** streams are declared in configuration and bound to sources. There is no cap; the cost is
per-stream schema and per-binding feed machinery.
**Falsifier:** startup time or memory is not linear in stream count, or there is an undocumented cap.
**Setup:** `H-SRV` with `n` declared streams, each with a 5-column schema, `n` in 10, 100, 500,
1 000, 5 000. Two arms: all streams declared but **unbound**, and all bound to a file source.
**Steps:** 1. Measure node startup time and RSS at each `n`. 2. Register one query per stream in the
bound arm and repeat PERF-055's measurements. 3. Record any refusal.
**Expected:** startup time and RSS linear in `n` in the unbound arm; report the slope in ms and KiB
per stream. In the bound arm, a feed thread per *query*, not per stream — confirm which. Report the
`n` at which startup exceeds 30 s, if any. If a cap or a refusal exists, name the code and message; if
none does, state that as the finding, because an unbounded configuration surface is a denial-of-
service surface for anyone who can edit the YAML.
**Vacuity:** the unbound arm isolates the schema cost from the feed cost, which the bound arm alone
conflates.

## PERF-057 — how many subscribers one view holds
**Intent:** `ViewSink.listeners` is a `CopyOnWriteArrayList` — so attaching `N` subscribers costs
`O(N²)` array copies — and `ViewSink.commit` delivers to all of them **serially on the feed thread**,
after `evict()`, inside the 20 ms budget. `STRM-. . .` exercises 1 000 subscribers for correctness;
this case measures the cost.
**Falsifier:** attach time is linear, or delivery latency is independent of subscriber count.
**Setup:** `H-SRV`, `K = 500` keyed aggregate, steady feed.
**Steps:** For `N` in 1, 10, 100, 500, 1 000, 5 000: 1. Time the attachment of the `N`th subscriber
and the total time to attach all `N`. 2. Measure ingest rows/s. 3. Measure p50/p99/max delivery
latency at subscriber 1 and at subscriber `N`. 4. Measure the commit interval actually achieved
against the 20 ms target. 5. Record memory.
**Expected:** total attach time quadratic in `N` — report the fit and the time to attach 5 000, which
at `O(N²)` copies of a growing array is the number that will surprise. Delivery latency rises with
`N` because the loop is serial: with `N` subscribers the last one waits for `N-1` deliveries; report
the gap between subscriber 1 and subscriber `N`. Commit interval exceeds 20 ms once
`N * perSubscriberCost > 20 ms`; report the `N` at which that happens, since past it the engine's
whole commit cadence is set by the subscriber count. Memory: one `Subscription` with an `ArrayDeque`
of up to `10 000` rows each, so `5 000 * 10 000 = 50 000 000` buffered rows at the ceiling — report
the actual.
**Vacuity:** measuring subscriber 1's latency as well as subscriber `N`'s is what demonstrates the
serial loop; an average across subscribers would hide it.

## PERF-058 — how many open windows one query holds
**Intent:** open windows are the state a windowed aggregate carries, and PERF-037 showed they are
what exhausts the arena. The ceiling is a function of window count × key cardinality.
**Falsifier:** the ceiling is not predictable from `openWindows * keysPerWindow * bytesPerEntry`.
**Setup:** `H-SRV`, `SELECT user_id, SUM(amount) FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL 'W'
SECOND)`, with out-of-orderness set high enough to keep several windows open at once.
**Steps:** Vary `W` in 1, 10, 60, 600 seconds and `out-of-orderness` in 1 s, 60 s, 600 s, and `K` in
500 and 50 000 — a 4 × 3 × 2 grid of 24 runs, each fed until failure or until 5 000 000 rows.
1. Record open-window count over time. 2. Record the row count at failure. 3. Compute
`openWindows * K` at failure.
**Expected:** a 24-cell table. Open windows at steady state should be about
`1 + ceil(outOfOrderness / W)`. The product `openWindows * K` at failure should be roughly constant
across cells and should equal the arena ceiling divided by the per-entry cost — from PERF-037,
`33 554 432 / 127.1 = 264 000` entries. Report the measured product for every cell and its spread. A
constant product across 24 cells is the model; a cell that deviates is where the model breaks and is
worth investigating.
**Vacuity:** 24 cells across three dimensions is what tests a model rather than a number; a single
configuration cannot distinguish "windows cost" from "keys cost".

## PERF-059 — the hard-coded ceilings, each reached deliberately
**Intent:** enumerate the constants and show each one firing, with its error, so that an operator
meeting one can identify which.
**Falsifier:** any ceiling fires at a different number, or fires with an indistinguishable error.
**Setup:** `H-SRV`, one run per ceiling.
**Steps:** reach each of these and record the exact triggering quantity, the error code and the
message:

| constant | value | where |
|---|---|---|
| `QueryRegistry.DEFAULT_MAX_KEYS` | `1 000 000` | `QueryRegistry.java:71` |
| `ViewQuery.MAX_RESULT_ROWS` | `1 000 000` | `ViewQuery.java:82` |
| `ViewQuery.MAX_CACHED_PLANS` | `256` | `ViewQuery.java:90` |
| `KeyedAggregate.DEFAULT_MAX_GROUPS` | `1 000 000` | `KeyedAggregate.java:61` |
| `InterpretedPipeline.MAX_JOIN_STATE_SLABS` | `64` (× 1 MiB = 64 MiB) | `InterpretedPipeline.java:69` |
| `InterpretedPipeline.MAX_LOOKUP_CACHE_ENTRIES` | `10 000` | `InterpretedPipeline.java:78` |
| `RowArena` ceiling | `8 × 4 MiB = 33 554 432` | `LaneConfig.java:90`–`:91` |
| `RowInbox` 2 GB cap | `cellCount × cellBytes` | `RowInbox.java:146` |
| `StatementHandle.MAX_PARAMETER_BYTES` | `1 048 576` | `StatementHandle.java:44` |
| `ControlWire` field count | `1 024` | `ControlWire.java:119` |

**Expected:** each fires at exactly its stated value — assert the triggering quantity, not a range.
Each produces a distinct, identifiable error; where two share a code (`DEFAULT_MAX_KEYS` and
`DEFAULT_MAX_GROUPS` are both 1 000 000 and may both be `ServingErrors`/`RuntimeErrors`), record how
an operator is meant to tell them apart, because that is the actionable question. `MAX_CACHED_PLANS`
at 256 is an eviction rather than a failure — confirm it evicts rather than refusing, and measure the
cost of a plan-cache miss so the ceiling's consequence is quantified.
**Vacuity:** hitting each ceiling exactly, rather than well past it, is what shows the constant is the
binding one; feeding ten times the limit could be failing for an unrelated reason.

## PERF-060 — the ceilings that do not exist
**Intent:** the complement of PERF-059, and the more useful half. An absent limit is a limit set by
whatever runs out first, discovered in production.
**Falsifier:** any cap is found for these.
**Setup:** the repository, plus the measurements from PERF-055, 056 and 057.
**Steps:** 1. Grep `pravaha-registry`, `pravaha-serving`, `pravaha-runtime` and `pravaha-server` for
size checks on: the number of registered queries; the number of names per computation; the number of
declared streams; the number of subscribers per view; the number of source bindings; the number of
concurrent Flight sessions. 2. For each with no cap, state the derived ceiling from Sections G and I
and the failure mode when it is reached.
**Expected:** step 1 — `byName` and `byFingerprint` are `LinkedHashMap`s with no size check;
`ViewSink.listeners` is an unbounded `CopyOnWriteArrayList`; `StreamCatalog` is in-memory with no
cap. Step 2 — the derived table:

| quantity | no cap because | derived ceiling | failure mode |
|---|---|---|---|
| registered queries | no check in `QueryRegistry` | threads and arena (PERF-055) | `OutOfMemoryError` or thread exhaustion |
| subscribers per view | unbounded `CopyOnWriteArrayList` | commit cadence and buffers (PERF-057) | commit interval grows past 20 ms; buffered rows exhaust heap |
| declared streams | in-memory catalog | startup time and heap (PERF-056) | slow start |
| source bindings | one feed thread per query, not per binding | as queries | as queries |

Report each derived ceiling as a number measured on the reference machine, with its environment
block. The deliverable is the table: it is what a capacity plan needs and what the product does not
currently state anywhere.
**Vacuity:** n/a — an enumeration whose numbers come from PERF-055 to PERF-058.

---

## Coverage note

**Budget: 60. Written: 60.** IDs `PERF-001`–`PERF-060`, contiguous.

**Four cases before any measurement (PERF-001–004), and they are not overhead.** Round 1's
performance findings are recorded in prose in `docs/project/qa/logs/INGEST.md` and `benchmarks/README.md`,
and two of those recorded results cannot currently be reproduced: `-Pbench` is inert (PERF-002) and
`benchmarks/results/lane-scaling.json` names a benchmark method the source no longer has (PERF-003).
A performance area that starts by quoting numbers it cannot regenerate is a performance area that
will be argued with rather than acted on. PERF-004 belongs with them because it determines what a
finding can even recommend: there is no configuration key for lanes, arenas, batch size, inbox size
or wait strategy, so "tune it" is not available as a remedy for anything in this file.

**Sections D and E are eight and five cases for what could be described as one bug.** They are
separated because they are two different costs on one monitor and a fix for either leaves the other:
`scan()`'s whole-map copy (D) and `evict()`'s whole-map sweep (E). PERF-032's `forever()` control and
PERF-035's four-arm factorial are what let a later wave say which fix bought what, instead of
measuring once after both and attributing the improvement to whichever was done last.

**Every throughput case in Section C asserts an answer as well as a rate.** That is the brief's
vacuity rule applied to a performance area, and it is the correction for round 1's row-count
assertion that passed while 200 000 rows collapsed into 500 keys. Each of the ten runs the two key
regimes `K = N` and `K = 500` explicitly and states the expected view size in both, because the two
produce identical `rowsIn` and completely different work.

**One case is a 24-hour soak (PERF-049) and one is a 24-run grid (PERF-058).** Both exceed what a
single QA session can run, and both are written anyway: the soak is the only case that can see the
registry journal's unbounded growth (`STATE-093`) in situ, and the grid is the only way to
distinguish "windows cost memory" from "keys cost memory". If a wave cannot run them, it should
record them as not-run with the reason, not narrow them until they fit.

**What this area does not own.** Whether a lane *should* die on arena exhaustion is a design
question, not a measurement — PERF-036 to PERF-043 measure the thresholds and establish that the
death is invisible, and the verdict belongs elsewhere. The correctness of eviction, windowing and
retraction belongs to `WIN`, `TIME` and `INCR`; this file asserts answers only to keep its own
throughput numbers honest.
