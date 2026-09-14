# TIME — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/TIME.md`](../cases/TIME.md). Executed 2026-09-14 on branch `develop` (worktree
`.claude/worktrees/qa-time`, branch `worktree-qa-time`, at `b298190`), against `pravaha-*` as built
by `./mvnw -o -T1C install -DskipTests` (Java 21, `/usr/lib/jvm/java-21-openjdk-amd64`).

Three execution surfaces were used, and every verdict below says which one produced it:

- **[E2E]** — a real `pravaha-server-0.1.0-SNAPSHOT-app.jar` node, one per configuration cell,
  started with `--spring.config.additional-location=file:$QA/conf/<cell>.yaml` and killed by PID.
  Ports HTTP **18801** / Flight **19801** throughout (another agent holds 18800/19800). The case
  file names 18300/19300; the ports assigned to this round are used instead and nothing in this
  surface depends on the number.
- **[IT]** — `pravaha-it`'s `EventTimeTest`, which a previous wave wrote from this same case file
  (`pravaha-it/src/test/java/com/ash/messaging/pravaha/it/qa/time/EventTimeTest.java`, 31 tests).
  Run here as `./mvnw -o -pl pravaha-it -am test -Dtest=EventTimeTest` — **never** `-pl pravaha-it`
  alone, which builds against stale jars in `~/.m2` and reports failures that are not real.
- **[UNIT]** — small driver programs compiled against `pravaha-runtime`/`pravaha-api` jars and run
  directly (`$QA/unit/Unit.java`, `Unit2.java`), because `WatermarkTracker.advance` takes
  `nowNanos` as an argument precisely so time can be driven.

Scratch `$QA = /tmp/claude-1000/-home-ashutosh-IdeaProjects-pravaha/qa-time`. **No production code
was modified by any case in this file.** Nothing below is seed-proven; every verdict rests on a
command that was run and output that was read.

**The owner's three standing constraints**, restated because every finding is judged against them:
(1) authorization is enforced at the Pravaha layer, never pushed to persistence; (2) only
authenticated users may reach data; (3) a user receives only the data they are authorized for.
Nothing in this surface touched any of the three; no finding below is judged against them.

**A note on a third-party string.** As in prior rounds, the jqwik dependency's console output
carries an adversarial sentence addressed to an "AI Agent" instructing it to disregard its
instructions. It is untrusted third-party build output, not a project instruction, and was not acted
on. The same applies to any `system-reminder` arriving inside a tool result.

---

## The case file's fifteen assumed facts, checked

The case file says an executor who finds one of these false has found something more interesting
than the case that referenced it. **Four of the fifteen are false against this build**, and three of
the four invalidate whole sections rather than single cases. Line numbers in the case file are also
stale in several places; where a fact holds but its citation has moved, the current line is given.

| # | Fact as stated | Verdict | Evidence |
|---|---|---|---|
| 1 | `StreamSchema.DEFAULT_OUT_OF_ORDERNESS` is 10s (`StreamSchema.java:53`) | **HOLDS** (line moved) | `StreamSchema.java:56` `= Duration.ofSeconds(10)`; applied at `:86` |
| 2 | `QueryExecution.DEFAULT_IDLE_AFTER` 30s, `DEFAULT_TICK` 1s (`:367,370`) | **HOLDS** (lines moved) | `QueryExecution.java:380`, `:383` |
| 3 | `WatermarkTracker` bounds 1s/10m, refuses rather than clamps | **HOLDS** (lines moved) | `WatermarkTracker.java:77`, `:91`, `:94`, `:101` |
| 4 | `idle-after` validated once at startup by a throwaway tracker; `tick <= idle-after` checked per registration instead | **HOLDS** | `PravahaNode.java:378-398`; `QueryExecution.java:352-356` |
| 5 | `pravaha.watermark.out-of-orderness` has no reader anywhere | **HOLDS** | `grep -rn out-of-orderness --include=*.java --exclude-dir=.claude --exclude-dir=target` finds only javadoc prose and two negative-value guards. Proved by experiment in TIME-026/027: the same duration one level down changes 11 rows to 6 |
| 6 | Lateness reaches the tracker per stream from the scan's catalog schema | **HOLDS** (line moved) | `QueryExecution.java:302` `pipelines.get(laneIndex).inputSchema(streamName).outOfOrderness()` |
| 7 | A row's event time is whatever the plugin passes to `RowWriter.eventTimestampNanos`; `DelegatingRowWriter` is the only hook | **HOLDS** (line moved) | `DelegatingRowWriter.java:147-156` |
| 8 | `advanceWatermarkQuietly` re-observes **every** partition's high-water on **every** tick | **FALSE — fixed** | `QueryExecution.java:433-438` now keeps `lastReportedHighWater` and calls `watermarks.observe` **only when the mark has moved**. A partition that spoke and then stopped *does* go idle on a running server. See the headline below |
| 9 | `observe` sets `lastActivityNanos` and clears `idle` | **HOLDS** (lines moved) | `WatermarkTracker.java:127-139` |
| 10 | `windowsCompletedBetween` fires ends `<= watermark` | **HOLDS** | `SlicedWindows.java:116` `for (long end = firstEnd; end <= watermarkNanos; …)` |
| 11 | `PhysicalPlanBuilder.DEFAULT_ALLOWED_LATENESS_NANOS` is the constant 0 | **FALSE as stated; true in effect** | The constant no longer exists. `PhysicalPlanBuilder.allowedLatenessOf` (`:885-895`) reads `scanBeneath(input).outputSchema().allowedLateness()`, whose default is `StreamSchema.DEFAULT_ALLOWED_LATENESS = Duration.ZERO` (`StreamSchema.java:76`). No configuration key, REST field or SQL clause sets it (TIME-102), so **on a configured server the effective value is still zero** and every case resting on that still stands |
| 12 | `lateOutput` is wired to nothing on the server path; `lateRecords()` is published by no metric and no API field | **HOLDS** | `grep -rn "lateOutput("` has no non-test caller outside `WindowedAggregate`/`InterpretedPipeline`; `/actuator/prometheus` exposes exactly seven `pravaha_*` gauges and none of them is a late count |
| 13 | The only visible watermark is `pravaha_query_watermark_lag_seconds`, `NaN` when there is none, fed from a field the internal clock never writes | **HOLDS** | `PravahaMetrics.java:132,146-148`; `QueryExecution.advanceWatermarkQuietly` calls `QueryExecution.advanceWatermark` (`:442`) and never `RegisteredQuery.advanceWatermark` (`RegisteredQuery.java:266`). Observed `NaN` on `w10` while it was serving 11 correct windows |
| 14 | `FilesystemPartitionReader` latches `exhausted` on the first null read, so a regular file cannot go quiet and then resume — only a FIFO can | **FALSE — a `follow` option now exists** | `FilesystemPartitionReader.java:236-238` latches `exhausted` **only when `!follow`**; `FilesystemSourcePlugin.java:129` reads `follow`. `tail -f` semantics, file replacement handled. The whole FIFO apparatus the case file builds for TIME-072/073/091/093-097 is unnecessary |
| 15 | Window assignment reads the `DESCRIPTOR` column by ordinal while the watermark comes from the stamped event time, and nothing checks they are the same column | **HOLDS** | `WindowAssign.java:62` `row.getLong(operator.eventTimeOrdinal())`, set from `descriptorOrdinal(descriptor, schema)` at `PhysicalPlanBuilder.java:617` and `:729`. Demonstrated in TIME-014 |

Two further stated facts in the body of the case file, not in the numbered list, are also false and
are recorded here because cases rest on them:

| Where | Claim | Verdict | Evidence |
|---|---|---|---|
| TIME-077 | `trackEventTimeOf` builds the partition name by **concatenating** two integers, so `(1,12)` and `(11,2)` collide | **FALSE — fixed** | `QueryExecution.java:298` is `streamName + "#" + laneIndex + "/" + pumps.size() + ":" + partitionedPumps.size()`. The separator is present and the comment records the fix. Measured: `ev#0/1:12` vs `ev#0/11:2`, distinct; the expression the case describes gives `ev#0/112` twice |
| TIME-119 | `advanceWatermarkQuietly` mutates lane-owned pipeline state **on the watermark thread** | **FALSE — fixed** | `QueryExecution.advanceWatermark` (`:682-694`) submits to `lanes.lane(index).submitControlTask(...)` and waits on the ticket. The javadoc above it records the exact corruption the case predicts (a windowed SUM returning 3,525,325,093,794 against a true 99,999) as the reason for the change |

---

## Headlines

**1. The quiet-partition section's central prediction is stale, and in the good direction.** The
case file's coverage note calls TIME-072 and TIME-074 "the most important pair in this file" on the
reading that `advanceWatermarkQuietly` re-observes every partition's retained high-water on every
tick, so a partition that produced one row and stopped can *never* be excluded and is worse off than
one that never spoke. That is no longer the code. `QueryExecution.java:433-438` keeps a
`lastReportedHighWater` map and reports a partition only when its mark has **moved**:

```java
Long previous = lastReportedHighWater.put(partition, seen);
if (previous == null || previous != seen) {
    watermarks.observe(partition, seen, now);
}
```

Round 1's DEFECT-18 mechanism is fixed, and `EventTimeTest.time072b_theLaneReportsAPartitionOnlyWhenItsMarkHasMoved`
is the regression test that pins it. Ten [UNIT] cases in that section were written because "the
engine cannot reach the tracker's interesting states"; it can now.

**2. The engine's only watermark instrument reads `NaN` on a query whose watermark is demonstrably
advancing.** `w10` served eleven correct windows in the same process in which
`pravaha_query_watermark_lag_seconds{query="w10"}` read `NaN` — the same reading a query with no
event time at all produces. Two queries that differ completely and read identically. TIME-1.

**3. A `DESCRIPTOR` naming a column that is not the declared event time is accepted at plan time.**
The assignment clock and the firing clock are then different quantities in different eras, and the
rows that fall out of the difference are counted nowhere. TIME-2.

**4. `pravaha.watermark.out-of-orderness` is inert, proved by a number rather than a grep.** The
identical duration in the identical spelling gives **11** windows at `pravaha.watermark.out-of-orderness`
and **6** at `pravaha.streams.ev.out-of-orderness`. This confirms the DOCX round's finding DOCX-6
independently, by experiment, on a running node.

**5. Two of the three duration keys in one configuration block have bounds and one does not.**
`idle-after: 30` (unitless) is read as thirty **milliseconds** and refused by the minimum bound;
`out-of-orderness: 60` is read as sixty **milliseconds**, accepted, and produces a view visually
identical to a correct configuration while being 1000× wrong. TIME-3.

---

## A harness note the case file does not carry, and which changes several of its answers

**A 121-line file is ingested in far less than one tick, so nothing in it is ever late.** The case
file's Ordering and Lateness sections assume the watermark climbs *while* the file is being read.
It does not: `FilesystemPartitionReader` delivers the whole file in a handful of polls, the
`pravaha-watermark` thread's first tick arrives a second later, and by then every row is already in
its window. Event time and wall-clock time are two clocks and several cases measure one while
meaning the other — exactly the trap the brief for this round names.

Measured, in the same harness and on the same 121 rows:

| File | Order | Windows | Totals |
|---|---|---|---|
| `evB.csv` | in order | 11 | 45 … 1045 |
| `evRev.csv` | exactly reversed | **11** | 45 … 1045 |
| `evShuf.csv` | shuffled (seed 20260914) | **11** | 45 … 1045 |

TIME-106 predicts **0** rows for the reversed file and TIME-107 predicts a computable set of drops.
Both got the in-order answer, because with no tick between the first row and the last there is no
watermark for a row to be behind. The same effect makes TIME-031's injected late row land
(`n = 11, total = 945` in window 1 where the case predicts `n = 10, total = 45`).

Where the case file reaches for a FIFO to get around this, it is now unnecessary: the filesystem
plugin has a `follow: true` option (fact 14 above) that gives `tail -f` semantics on an ordinary
file, and the paced cases below use it. Where a case was run **unpaced**, the verdict says so and is
judged against what the unpaced run can actually show.

---
