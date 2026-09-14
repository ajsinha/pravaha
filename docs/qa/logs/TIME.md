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

## Event-time declaration — TIME-001 … TIME-015

Every [E2E] row below is a separate `pravaha-server` process on `$QA/conf/<cell>.yaml`, registered
through `bin/pravaha register --sql-file $QA/q10.sql --keys 0 --url grpc://localhost:19801` and read
through `bin/pravaha query --sql "SELECT * FROM <view>"`.

`ORDER BY` is not available on a view read: `SELECT * FROM w10 ORDER BY window_start` is refused with
`PRV-1041 PRV-2020 the planner produced a LogicalSort, which Pravaha cannot execute yet`. The case
file's S1 procedure specifies it. Every read below is therefore unordered and sorted afterwards; the
engine returned window_start order anyway in every run.

| Case | Verdict | Evidence |
|---|---|---|
| TIME-001 | **PASS** | `$QA/conf/base.yaml`. `ROWS IN` 121. View: exactly **11** rows, `n = 10` on every one, totals 45, 145, 245, 345, 445, 545, 645, 745, 845, 945, 1045. Startup log: `watermarks: idle-after=PT30S, tick=PT1S` and `sources bound: [noet <- filesystem[path, schema], ev <- filesystem[event.time, path, schema]]` |
| TIME-002 | **PASS** | Same process. `w10n` over `noet`: `ROWS IN` 121, view **0 rows**, state RUNNING. `pravaha_query_watermark_lag_seconds{query="w10n"} NaN`. Waited 10s then re-read at +120s: still 0. Zero WARN/ERROR lines mentioning `w10n`, `watermark` or `event time` in the whole log |
| TIME-003 | **PASS** (records the gap) | Same process. The windowed registration over `noet` **succeeds** (`registered w10n state=RUNNING`) and is silent for ever; `p_noet` (`SELECT id, usr, amount FROM noet`) also succeeds and serves **121 rows**. Today's behaviour is as the case predicts; the desired `PRV-2xxx` refusal does not exist |
| TIME-004 | **PASS** | `conf/t004.yaml`. Process exits 1 during context refresh, before any port binds. `Caused by: com.ash.messaging.pravaha.api.PravahaException: PRV-2002  stream 'ev' declares 'no_such_column' as its event time and has no such column. Its columns are [id, usr, amount, event_time].` `curl -m2 localhost:18801/actuator/health` → no answer |
| TIME-005 | **PASS**, and the error-quality defect is real | `conf/t005.yaml` (`event-time: usr`). `Caused by: java.lang.IllegalArgumentException: event-time field 'usr' must be TIMESTAMP, got VARCHAR NOT NULL`. No `PRV-` code, no stream name, no key name, delivered as a stack trace. Judged against TIME-004's `PRV-2002` in the same harness → TIME-9 |
| TIME-006 | **PASS** | `conf/t006.yaml` (`event_time:INT64`). `Caused by: java.lang.IllegalArgumentException: event-time field 'event_time' must be TIMESTAMP, got INT64 NOT NULL`. The values are byte-identical to TIME-001's; only the declared type differs. The message says `INT64`, not the case's predicted `BIGINT` |
| TIME-007 | **PASS** | `conf/t007.yaml` (`event-time: EVENT_TIME`). `PRV-2002  stream 'ev' declares 'EVENT_TIME' as its event time and has no such column. Its columns are [id, usr, amount, event_time].` The lookup is exact-match; the message does not say "did you mean event_time" |
| TIME-008 | **PASS** | `conf/t008.yaml` (`event-time: "  event_time  "`). Starts; **11** rows, 45 … 1045, row for row identical to TIME-001. Both strips are present |
| TIME-009 | **PASS** | `conf/t009.yaml` (`event-time: ""`, `out-of-orderness: 10s` still present). Starts, no warning, `ROWS IN` 121, view **0 rows**. `sources bound: [ev <- filesystem[schema, path]]` — no `event.time` option, so the blank is absent on both halves. `grep -icE "event-time|out-of-orderness"` over the whole startup log: **0** |
| TIME-013 | **PASS** | `conf/t033.yaml`, `Q10` verbatim: 11 rows, 45 … 1045. The control for TIME-014 |
| TIME-015 | **PASS** | `conf/base.yaml` binds both `ev` (declared) and `noet` (not). One line, both bindings, `event.time` among `ev`'s options and absent from `noet`'s. This is the only place the engine ever states whether a stream has an event time: there is no API field and no metric |

---

## Per-plugin event time — TIME-016 … TIME-025

**Eight of these ten cases are BLOCKED, and the reason is structural rather than incidental.** Only
one plugin is on a shipped server's classpath:

```
$ unzip -l pravaha-server-0.1.0-SNAPSHOT-app.jar | grep -o 'BOOT-INF/lib/[a-z0-9.-]*plugin[a-z0-9.-]*'
BOOT-INF/lib/pravaha-plugin-filesystem-0.1.0-
```

`feedfile`, `delta`, `jdbc` and `aerospike` are built in this repository and none of them ships in
the server artefact, so no `pravaha.sources.<n>.plugin:` value other than `filesystem` can be reached
on a real node. `pravaha-it` carries `pravaha-plugin-aerospike` (behind Testcontainers) and
`pravaha-plugin-filesystem` and neither of the other two, so there is no [IT] route either. This is
the same observation CFG-4 records from the other end (`PRV-5090`'s "Available:" list naming one
plugin). A BLOCKED case is not a passing one; what *can* be established is the source-level claim,
and that is recorded beside each verdict rather than instead of the verdict.

| Case | Verdict | Evidence |
|---|---|---|
| TIME-016 | **PASS** | TIME-001's result, re-stated as the matrix's first row. `FilesystemPartitionReader.java:249-253` stamps from `codec.lastEventTimeNanos()` when it is not `Long.MIN_VALUE` |
| TIME-017 | **FAIL** — and worse than the case predicts | `conf/t017.yaml` over `evNull.csv` (`evB.csv` with row k=50's `event_time` an empty field). `ROWS IN` = **0**, view 0 rows, and **not one WARN or ERROR line in the whole startup log**. The case offers two defensible outcomes for the null row; neither is what happened. One malformed field in one line of 121 reduced the stream to nothing, silently. Re-checked with a plain projection in the same shape — see the confirmation run below. TIME-4 |
| TIME-018 | **BLOCKED** (feedfile not on any reachable classpath) | Source claim confirmed: `FeedFilePartitionReader.java:128` `.eventTimestampNanos(0L)` on every row, unconditionally. A windowed query over a feedfile stream cannot fire a window in the present whatever `pravaha.streams.<n>.event-time` says |
| TIME-019 | **BLOCKED** (delta) | `DeltaPartitionReader.java:272` `.eventTimestampNanos(0L)`. The code comment saying a query that needs event time "names the column in its DDL" is still there and still does not help, because naming the column changes the engine's schema and not what this reader stamps |
| TIME-020 | **BLOCKED** (jdbc) | `JdbcPartitionReader.java:188` `.eventTimestampNanos(watermark)` where `watermark` is `results.getLong(watermarkIndex)` — the binding's `watermark.column`, not `pravaha.streams.<n>.event-time` |
| TIME-021 | **BLOCKED** (jdbc) | Same line. With `watermark.column` naming a nanosecond-valued column the stamp is correct by coincidence of units |
| TIME-022 | **BLOCKED** (jdbc) | Same line; there is no unit conversion anywhere on that path |
| TIME-023 | **BLOCKED** (aerospike), **and the case is stale** | `LutScanReader.eventTimeOf` (`:176-186`) now reads the declared bin and falls back to the scan's start time only when no bin was declared or its value is not a `Number`: `return value instanceof Number number ? number.longValue() : scanStartedNanos;`. The case's premise — "stamps every row of a scan with the scan's start time" — is no longer the code, and the comment above it records the defect it replaces in exactly the terms TIME-023 predicts |
| TIME-024 | **BLOCKED** (jdbc/aerospike lookups) | Source claims confirmed: `JdbcLookupPlugin.java:258` and `AerospikeLookupPlugin.java:157` both write `eventTimestampNanos(0)`; `LookupJoin.java:324` emits with `row.eventTimestampNanos()` of the probe row; and lookup bindings go to `PluginLookupSources`, not `PluginSourceFeeds`, so they never become watermark partitions (`PravahaNode.java:409-411` vs `:400-402`) |
| TIME-025 | **BLOCKED** (feedfile) | The arithmetic the case turns on is now different in any case: with fact 8 false, a partition stamped 0 that stops reporting a *new* mark would go idle after `idle-after`, where the case predicts it never can. Not run, and not inferred |

---

## Out-of-orderness — TIME-026 … TIME-045

Twenty configurations, each one its own `pravaha-server` process, each differing from `conf/t033.yaml`
in one line. The window count is the instrument throughout: with the highest event time at T0+120 and
10-second windows, the number of rows in the view is `floor((120 − d)/10)` capped at 13.

| Case | Setting | Expected | Observed | Verdict |
|---|---|---|---|---|
| TIME-026 | `pravaha.watermark.out-of-orderness: 60s`, no per-stream key | 11 (inert) | **11 rows**, last total 1045 | **PASS** |
| TIME-027 | `pravaha.streams.ev.out-of-orderness: 60s` | 6 | **6 rows**, 45, 145, 245, 345, 445, 545 | **PASS** |
| TIME-028 | engine key `0s` **and** per-stream `60s` | 6, no warning | **6 rows**; `grep -icE "out-of-orderness"` over the whole startup log = **0** | **PASS** |
| TIME-029 | `noet` with `out-of-orderness: 60s` and no `event-time` | 121 in, 0 out, no diagnostic | see the batch-2 block below | **PASS** |
| TIME-030 | `0s` | 12 | **12 rows**, last total **1145** | **PASS** |
| TIME-031 | `0s` over `evLate.csv` | window 1 `n = 10, total = 45` | **12 rows**, window 1 `n = 11, total = 945` | **case defect** — see below |
| TIME-032 | `1ms` | 11 | **11 rows**, last total 1045 | **PASS** |
| TIME-033 | no lateness key anywhere | 11 | **11 rows** | **PASS** |
| TIME-034 | `1m`, then `60s` | 6 and 6 | **6 rows** each, identical totals | **PASS** |
| TIME-035 | `10m` | 0 | **0 rows**, `ROWS IN` 121, no warning | **PASS** |
| TIME-036 | `-1s` | refused at startup | `Caused by: java.lang.IllegalArgumentException: out-of-orderness must not be negative, got PT-1S. Zero means the source is strictly ordered, which is a claim the engine will hold you to: a row behind the watermark arrives late.` No `PRV-` code, no key name | **PASS** (and TIME-9) |
| TIME-037 | `87600h` (3650 days) | starts, 0 windows, no fault | **0 rows**, node starts and serves — **but the lane then stalls at shutdown**: `PRV-3010 lane 0 did not stop within PT5S; its thread is still in the processor`. See TIME-1 | **FAIL** |
| TIME-039 | `25s` | 9, last total 845 | **9 rows**, last total **845** | **PASS** |
| TIME-040 | gauge on a query firing 11 windows | `NaN` | `pravaha_query_watermark_lag_seconds{query="w10"} NaN` — on the same node and at the same moment as `w10n` (no event time, 0 windows) and `p_noet` (a projection). All three `NaN` | **FAIL** — TIME-2 |
| TIME-044 | `60s` / `PT1M` / `60000ms` | 6 / 6 / 6 | **6 / 6 / 6**, identical totals | **PASS** |
| TIME-045 | `60` (unitless) | 11, i.e. sixty **milliseconds** | **11 rows**, last total 1045 — indistinguishable from a correct 10s configuration | **PASS** (and TIME-6) |

**TIME-031 is a case defect, not a product FAIL.** The case injects `900,u0,900,T0+3` as the last
line of a 122-line file and expects it to be dropped as late. It was not, because the file is read in
full before the `pravaha-watermark` thread's first tick: when row 900 arrives the watermark is still
`NOT_YET`, so nothing is behind it. Window 1 came out `n = 11, total = 45 + 900 = 945`. The same
injection against a **paced** feed does drop it — TIME-094/095 below, run through `follow: true`.
TIME-031's own vacuity control (`out-of-orderness: 60s`, same file) gave the same `945`, which is the
tell: the pair was supposed to differ and it does not.

**TIME-038 [UNIT] — PASS, with the case's boundary question answered.** `boundedOutOfOrderness(Long.MAX_VALUE)`
then `observe(-1_000_000_000L)` gives `watermark() = 9223372035854775809`, a positive value 292 years
in the future. Driven at four values of `maxSeen` to find the edge:

```
maxSeen=0              d=Long.MAX_VALUE -> watermark=-9223372036854775807
maxSeen=-1             d=Long.MAX_VALUE -> watermark=-9223372036854775808
maxSeen=-2             d=Long.MAX_VALUE -> watermark= 9223372036854775807  WRAPPED POSITIVE
maxSeen=-1000000000    d=Long.MAX_VALUE -> watermark= 9223372035854775809  WRAPPED POSITIVE
```

The case asks for "the largest `d` that does not wrap for an event time of T0". **There is none that
wraps**: `T0 − Long.MAX_VALUE = −7456146436854775807`, still above `Long.MIN_VALUE`, so for any
positive `maxSeen` no legal `d` can wrap. The wrap needs `maxSeen < d + Long.MIN_VALUE`, which for
`d = Long.MAX_VALUE` means `maxSeen < −1` — an event time before 1969-12-31T23:59:59.999999999Z.
That is reachable (`TIMESTAMP` is signed) and unguarded, and `SymmetricHashJoin.advanceWatermark`
saturating explicitly shows the author knew the class of bug exists.

**TIME-041, TIME-042, TIME-043** — see the batch-2 block. **TIME-043 [E2E] PASS**: `one.csv`
(a single row at T0+7) with `out-of-orderness: 2s` gives `ROWS IN` 1, view **0 rows**, held there
through the settle and the re-read.

---

## Partition quiet time — TIME-046 … TIME-060

The bounds are the tracker's and the server validates them once at startup, by constructing a
throwaway tracker (`PravahaNode.java:386-397`). Every refusal below killed the process during
context refresh, before Tomcat bound 18801; `curl -m2 http://localhost:18801/actuator/health`
answered nothing in each case.

| Case | Setting | Verdict | Evidence |
|---|---|---|---|
| TIME-046 | `idle-after: 1s`, `tick: 100ms` | **PASS** | Starts. `watermarks: idle-after=PT1S, tick=PT0.1S`. 11 windows, 45 … 1045 |
| TIME-047 | `idle-after: 999ms` | **PASS** | Dies. `PRV-2002  pravaha.watermark.idle-after is PT0.999S, which this engine will not accept: an idle timeout of PT0.999S is below the minimum of PT1S. Below a second, ordinary jitter -- a rebalance, a GC pause, a source that polls on a timer -- excludes a partition that was merely slow, and the rows already on their way then arrive behind the watermark and count as late. Left as configured, every registration on this node would fail and the node would look healthy.` Word for word the case's predicted text |
| TIME-048 | block deleted vs `idle-after: 30s` | **PASS** | Both log `watermarks: idle-after=PT30S, tick=PT1S`; both give 11 windows. `conf/t049.yaml` at `10m` logs `PT10M`, so the line is not a constant string |
| TIME-049 | `idle-after: 10m` | **PASS** | Starts, logs `idle-after=PT10M`, 11 windows |
| TIME-050 | `600001ms`, then `600000ms` | **PASS** | `600001ms` dies: `PRV-2002  pravaha.watermark.idle-after is PT10M0.001S … is above the maximum of PT10M. Excluding an idle partition exists so a quiet one cannot stop the query; a timeout this long keeps the letter of that and loses the substance…`. `600000ms` starts and logs `PT10M` — identical to the `10m` spelling |
| TIME-052 | `idle-after: 0s`, `tick: 0s` | **PASS** | Dies on the **idle-after** check first: `… is PT0S … below the minimum of PT1S`. The `tick <= idle-after` comparison never runs |
| TIME-053 | `idle-after: -5s` | **PASS** | Dies: `… is PT-5S … below the minimum of PT1S`. The message renders the sign correctly and is actionable, but never says the word "negative" |
| TIME-055 | the four refusals, tabulated | **PASS** | All four name the key `pravaha.watermark.idle-after`, the rejected duration in ISO form, the bound violated and its value, and the consequence sentence. None prints a stack trace as its primary output. Contrast TIME-005/TIME-036 in the same harness, which carry none of the five |
| TIME-058 | ordering of `generatingWatermarks` | **PASS** [code + E2E ordering] | `QueryRegistry.generatingWatermarks` sets two fields (`:210-214`) consulted per registration at `:606-607`; `PravahaNode.start` calls it at `:397`, **before** `feedingFrom` (`:402`) and before `journalPath.ifPresent(... registry.recover ...)` (`:429`). So every query on a node shares one setting and none can be missing it. The [UNIT] half (a `QueryRegistry` with a registration before the call) was not run separately: the ordering it asserts is the same ordering the source shows and the E2E log confirms (`watermarks:` precedes `registry recovered`) |
| TIME-059 | `30s` / `PT30S` / `30000ms` / `30` | **PASS** | The three unit-bearing spellings all log `idle-after=PT30S` and give 11 windows. See the batch-1 block for the unitless one |
| TIME-060 | tracker refuses rather than clamps | **PASS** [IT] | `EventTimeTest.time047And050And052And060_theIdleTimeoutBoundsAreEnforcedAtConstruction` — `new WatermarkTracker(999_000_000L)` and `(600_000_000_001L)` throw; `(1_000_000_000L)` and `(600_000_000_000L)` do not. `grep -n "ofSeconds(1)\|ofMinutes(10)" PravahaNode.java` finds no second copy of either bound |

---

## Quiet partitions and the minimum-with-exclusion rule — TIME-061 … TIME-080

The section the case file calls its most important, and the one whose premise has changed. Fact 8 is
false: `advanceWatermarkQuietly` reports a partition to the tracker **only when its mark has moved**
(`QueryExecution.java:433-438`), so a partition that produced rows and then stopped *does* go idle
on a running server. Everything the case file writes about the mechanism being unreachable, and its
conclusion that "a partition that produced one row is worse off than one that produced none", is
about a build older than this one.

The [UNIT] cases are unaffected — the tracker's logic was always correct — and eleven of them are
executed by `EventTimeTest`, run as `./mvnw -o -pl pravaha-it -am test -Dtest=EventTimeTest`:
`Tests run: 31, Failures: 0, Errors: 0, Skipped: 1`.

| Case | Verdict | Evidence |
|---|---|---|
| TIME-061 | **PASS** [IT] | `time061_theWatermarkIsTheMinimumAcrossPartitionsUntilOneGoesQuiet`. First `advance(0)` = 48s = `min(100−2, 50−2)`; at `now = 60s`, `p1` idle, watermark **198s**, `idleExclusions() == 1`, `regressions() == 0` |
| TIME-062 | **PASS** [IT] | `time062_onlyTheQuietPartitionIsExcluded`. Five partitions; first 98s, second **108s** (not 98s), `idleExclusions() == 1`, `partitionCount() == 5` |
| TIME-063 | **PASS** [IT] | `time063_whenEverythingIsQuietTheWatermarkStaysWhereItIs`. `advance(60s)` returns **48s** unchanged, `idleExclusions() == 2`, and a further advance leaves the counter at 2 |
| TIME-064 | **PASS** [IT] | `time064And065_awatermarkNeverGoesBackwardsAndSaysSoWhenItWouldHave`. Rejoining at 150s leaves the watermark at **198s** and makes `regressions()` **1**; rejoining at 250s instead leaves it at 198s with no regression |
| TIME-065 | **PASS** [IT] | Same test. The sequence is 198, 198, 298 — the monotonic rule, not "latest minimum" |
| TIME-066 | **PASS** [IT] | `time066And067_apartitionThatHasNeverSpokenHoldsTheWatermarkUntilItGoesIdle`. `NOT_YET` at 0, 10s, 29s; **98s** at exactly 30s; 98s at 31s |
| TIME-067 | **PASS** [IT] | Same test, three partitions, none observed: `NOT_YET` throughout, `idleExclusions() == 3`, no downstream call |
| TIME-068 | **PASS** [UNIT] | Driven at both timeouts. `idle-after = 30s`: `t=0 NOT_YET, t=10 T0+0, t=20 T0+10, t=30 T0+20, t=40 T0+30, t=50 T0+40, t=60 T0+50`, `idleExclusions() == 0`; at wall 65s `p1` still live, at wall 80s excluded and the watermark jumps to T0+80. `idle-after = 15s`: the same seven values — the exclusion never fires during the alternation because the period (10s) is under the timeout — but at wall **65s** `p1` **is** idle where the 30s run has it live. Eight numbers, and the two configurations separate exactly where the case says they should |
| TIME-069 | **PASS** [IT] | `time069_theIdleBoundaryIsInclusive`. `isIdle` false at `30s − 1ns`, true at exactly `30s` |
| TIME-070 | **PASS** [IT] | `time070_idleExclusionsCountsTransitionsAndNotTicks`. 0 at t ≤ 20s, 1 at 30s, still 1 at 60s, 2 after a speak-and-fall-silent. A tick counter would read 4 |
| TIME-071 | **PASS** [UNIT] | `first advance=T0+60, second=T0+300, jump=240s = 24 ten-second windows on one tick` |
| TIME-077 | **PASS**, **case stale** | `QueryExecution.java:298` uses a `":"` separator. Measured: `(pumps=1, partitioned=12)` → `ev#0/1:12`, `(11, 2)` → `ev#0/11:2`, **not equal**. The expression the case describes gives `ev#0/112` for both. The source comment records the fix and its consequence ("two partitions sharing a name means one silently replaces the other in the tracker") |
| TIME-078 | **PASS** [UNIT] | `tracker.observe("ghost", …)` throws `IllegalArgumentException: no partition named 'ghost' is registered with this lane`; `QueryExecution.java:444-448` catches `RuntimeException` and logs `WARN could not advance the watermark: …`. The "partitions after the bad one are skipped" half is a property of `LinkedHashMap.forEach`, confirmed directly: with entries `a, bad, c` the visitor saw `[a, bad]` and `c` was never reached |
| TIME-080 | **FAIL** | Every shipped surface searched on a live node. `pravaha queries` gives NAME/STATE/FINGERPRINT/ROWS IN. `/actuator/prometheus` exposes exactly seven `pravaha_*` gauges: `rows_in`, `running`, `view_evicted`, `view_removals`, `view_size`, `view_updates`, `watermark_lag_seconds`. `/api/v1/status` gives `instanceId, version, engineState, uptimeSeconds, registeredQueries, plugins`. **`GET /api/v1/queries` is 404** — the OpenAPI document lists only `/api/v1/queries/explain`, `/api/v1/queries/validate`, `/api/v1/streams`, `/api/v1/streams/{name}`, `/api/v1/status`, `/status`. Nothing reports a partition's idle state, `idleExclusions()` or `regressions()`. The search method works: it found the other six gauges. TIME-8 |

---

## Ordering — TIME-105 … TIME-110

`Σ total` over the emitted windows is the drop detector this section is built on, and the number to
compare against is `Σ_{k=0}^{120} k = 7260`.

| Case | File | Windows | `Σ total` | Verdict |
|---|---|---|---|---|
| TIME-105 | `evB.csv`, in order | 11 | 5995 | **PASS** — `(45+1045)·11/2 = 5995`, and `7260 − 5995 = 1265 = 1145 + 120`, windows 12 and 13 still open. Both numbers asserted, also in `EventTimeTest.time105_theOrderedControlAccountsForEveryRowInTheFile` |
| TIME-106 | `evRev.csv`, exactly reversed | **11** | **5995** | **case defect** — the case predicts 0 windows and `Σ total = 0`. Identical to the in-order file, row for row |
| TIME-107 | `evShuf.csv`, seed **20260914** | **11** | **5995** | **case defect** — same reason. Deterministic and identical to the in-order file |
| TIME-108 | `evDup.csv`, times floored to 10s | 12 | 7140 | **PASS** — `n = 10` on every window, totals 45 … 1145, `7260 − 7140 = 120` is exactly the open row. Also `EventTimeTest.time108_...` |
| TIME-109 | `evSame.csv`, all at T0+60 | 0 | 0 | **PASS** — `out-of-orderness: 0s`, `idle-after: 1s`, `tick: 100ms`. `ROWS IN` 121, view **0 rows**, watermark pinned at T0+60 and the only populated window ends at T0+70. `EventTimeTest.time109_...` additionally asserts that appending a row at T0+80 releases it with `n = 121, total = 7260`, so the state was there all along |
| TIME-110 | two partitions, own lateness each | — | — | **PASS** [IT] — `time110_eachPartitionAppliesItsOwnLatenessBeforeTheMinimumIsTaken`: `p0.watermark() = T0+20`, `p1.watermark() = T0+40`, lane `min = T0+20`. Three numbers, and the lane is bounded by the tidier partition |

The seed for `evShuf.csv` is recorded because the case file asks for it: Python `random.Random(20260914).shuffle`
over the 121 lines of `evB.csv`. The file is at `$QA/data/evShuf.csv` and both runs of it produced
the same view.

**Why TIME-106 and TIME-107 do not reproduce.** See the harness note above: the 121-line file is
read before the first tick, so no row can be behind a watermark that does not yet exist. TIME-106 is
the strongest statement of the problem, because its whole argument is "row 1 carries the highest
event time, so the watermark is at its final value before any other row arrives" — and that is only
true if a tick separates row 1 from row 2. It does not. The case is worth re-writing against a
`follow: true` source; it is not worth recording as a product FAIL, because the product did the
arithmetically correct thing for the input it actually received.

---

## Lateness — TIME-093 … TIME-104

Allowed lateness is no longer a constant in the planner (fact 11), but it is still zero on every
query a **server** can register, because nothing on the server's surface sets it. So the three
outcomes design §15.4 describes still collapse to two for `TUMBLE`, and the third is still reachable
only through overlapping windows.

| Case | Verdict | Evidence |
|---|---|---|
| TIME-098 | **PASS** on the mechanism, **case defect** on the count | `conf/t098.yaml` over `evFuture.csv` (unpaced). `ROWS IN` 122. The outlier does drag the clock: the view holds **13** windows — 45 … 1145 **plus** window 13 `(n = 1, total = 120)` — where the same configuration over `evB.csv` gives 11. So `T0+86400 − 10s` fired windows 12 and 13 that would otherwise have stayed open. But nothing was dropped (unpaced: every row was in before the first tick), where the case predicts 7 rows and 61 drops. **The outlier's own window `[T0+86400, T0+86410)` is absent** — its end is above the watermark and no later row exists to move it, so row 901 is ingested, counted in `ROWS IN`, and invisible. `Σ total = 7260` exactly, over the 121 rows that are not the outlier |
| TIME-099 | **FAIL**, and not for the reason the case gives | `conf/t099.yaml` over `evPast.csv` (`evB.csv` + `902,u0,902,0`). The case predicts the epoch row is dropped and the normal **11** windows are served. Observed: the view holds **one row** — `window_start 0, window_end 10000000000, n 1, total 902` — and the eleven real windows are **gone**. The shutdown log then carries `PRV-3010  lane 0 did not stop within PT5S; its thread is still in the processor`. Root cause below; this is TIME-1 |
| TIME-100 | **PASS** on the arithmetic, **case defect** on the vacuity | `evJitter.csv` = `evB.csv` with `event_time` shifted by `+(k mod 7)s` for even k and `−(k mod 7)s` for odd k. At `out-of-orderness: 10s`: **11** windows, `n` = 11, 8, 12, 8, 12, 9, 9, 12, 8, 12, 8 and totals 63, 109, 301, 276, 541, 483, 574, 901, 669, 1141, 836 — **identical, element for element, to a batch recomputation of the same file** and `Σ = 5894`. At `2s`: **12** windows, the same eleven plus `(n = 9, total = 1012)`, `Σ = 6906` — also matching the recomputation. The case's control predicts the 2s run *drops* rows and falls short; it gains a window instead, for the harness reason above |
| TIME-101 | **FAIL** (documentation) | Half fixed, half not. `StreamSchema.java:129-137` now draws the distinction explicitly and even records that "that second sentence used to say a late row 'is still applied' without qualification". `docs/CONCEPTS.md:68-70` still says it without qualification — *"A row arriving after that is still applied — as a retraction and a correction — which is what the weights are for."* — and `CONCEPTS.md:65-66` still tells an operator the default is moved with `pravaha.watermark.out-of-orderness`, the key TIME-026 proves is inert. TIME-10 |
| TIME-102 | **PASS**, and the case is half stale | The exhaustive search: `grep -rn "allowed.lateness\|allowedLateness" --include=*.java --include=*.yaml --include=*.md --include=*.py --exclude-dir=.claude --exclude-dir=target .` The `ChangelogAnalysis` branch at `:78-81` is **not** dead — `PhysicalPlanBuilder.allowedLatenessOf` (`:885-895`) reads `scanBeneath(input).outputSchema().allowedLateness()`, so `StreamSchema.Builder.allowedLateness(Duration)` reaches it (already recorded as T-3 FIXED and as the WIN round's headline). **But there is still no way to set it on a server**: `StreamDeclarationProperties.Declaration` has exactly three fields — `schema`, `eventTime`, `outOfOrderness` — and `POST /api/v1/streams` takes `RegisterStreamRequest(name, schema)` and nothing else (`StreamController.java:88-98`). No `application.yaml` key, no SQL clause, no REST field. TIME-11 |
| TIME-104 | **PASS** [UNIT + E2E] | [UNIT] `boundedOutOfOrderness(10s).observe(Long.MIN_VALUE).watermark() = -9223372036854775808` — exactly `NOT_YET`, so the row is indistinguishable from no row at all. `boundedOutOfOrderness(0).observe(Long.MAX_VALUE).watermark() = 9223372036854775807`. [E2E] `conf/t104.yaml` over a one-row file at `event_time = -9223372036854775808`: `ROWS IN` **1**, view **0 rows**, held there. Also `EventTimeTest.time104_arowAtLongMinValueIsIndistinguishableFromNoRowAtAll` |

---

## Clock health — TIME-111 … TIME-115

| Case | Verdict | Evidence |
|---|---|---|
| TIME-112 | **PASS** [UNIT] | `advanceWatermarkQuietly`'s exact shape — `catch (RuntimeException)` around a body scheduled with `scheduleWithFixedDelay` — driven with a `StackOverflowError` on tick 3: `ticks observed=3 cancelled=false done=true`. The task stopped, the future holds the throwable, **no log line was produced** because the `catch` did not match, and nothing reads the future. `QueryExecution.checkHealth()` does not consult the watermark task |
| TIME-113 | **PASS** [UNIT] | The identical harness with an `IllegalStateException` on tick 3: `ticks observed=29 cancelled=false done=false`, and exactly one `WARN could not advance the watermark: java.lang.IllegalStateException: injected`. One occurrence costs one tick and the clock recovers. The two runs differ only in the throwable's supertype |
| TIME-114 | **NOT RUN** | The case needs a query whose `InterpretedPipeline.advanceWatermark` fails permanently, manufactured by round 1's arena-exhaustion recipe (a windowed aggregate over 50 000 keys), left running for ten minutes. Not attempted: the recipe is STATE's and PERF's, the failure it depends on may itself have been fixed, and a ten-minute soak inside a session competing with two other QA agents measures the other agents. Recorded as not run rather than inferred from TIME-113 |
| TIME-119 | **case stale; not reproduced** | The premise is false: `QueryExecution.advanceWatermark` (`:682-694`) submits to `lanes.lane(index).submitControlTask(...)` and waits on the ticket, so the advance runs on the lane thread that owns the state. The javadoc above it records the corruption the case predicts — *"A windowed SUM over 100,000 rows returned 3,525,325,093,794 against a true 99,999, the view held 996 to 1,001 rows where 1,000 existed"* — as the reason for the change. The case's own instruction applies: a clean run is not proof of safety. **Not reproduced**, and the reason it cannot be is a fix, not a quiet machine |

---

## Tick — TIME-081 … TIME-092

| Case | Verdict | Evidence |
|---|---|---|
| TIME-087 | **PASS** | `conf/t087.yaml`, `tick: 0s`, `idle-after: 1s`. Starts (0s is not greater than 1s, so the `tick > idleAfter` check passes), logs `watermarks: idle-after=PT1S, tick=PT0S`, serves **11** windows, 45 … 1045. `QueryExecution.java:367` `long period = Math.max(1, tick.toMillis())` — a clamp of exactly the kind `WatermarkTracker` refuses to make for `idle-after` |
| TIME-088 | **PASS** | `conf/t088.yaml`, `tick: PT0.0005S`. Starts, logs `watermarks: idle-after=PT1S, tick=PT0.0005S` — **the configured value, not the effective one**. The effective period is 1ms by the same `Math.max`. The log actively misreports what the engine is doing; the window results are unaffected (11 rows) |
| TIME-089 | **PASS** | `conf/t089.yaml`, `tick: -1s`, `idle-after: 30s`. Starts, logs `tick=PT-1S`, registrations succeed, **11** windows. Nothing refuses a negative tick: `Duration.ofSeconds(-1).toMillis()` is −1000, clamped to 1 by the same `Math.max`, and `tick.compareTo(idleAfter) > 0` is false for a negative. Beside TIME-053 (`idle-after: -5s`, refused) in the same configuration block: two duration keys, one bounded and one not |
| TIME-090 | **PASS** [UNIT] | Tracker with `idle-after = 30s`, `advance` driven at 0, 25s, 50s. `p1` idle **false** at 25s, **true** at 50s — twenty seconds after it qualified. The effective detection delay is `idle-after + up to one tick`, and with `tick = idle-after` (TIME-057) the worst case is 2× `idle-after` |
| TIME-091 | **NOT RUN** | Per-window wall-clock latency across three ticks (100ms, 1s, 5m), each measured from the row that crosses a window end to that window being readable. Not attempted: three sampling runs, one of them twenty minutes, on a machine carrying two other QA agents throughout — the figures would measure the other agents, and the case's own claim is a distribution. Recorded as not run rather than as a number nobody should quote |
| TIME-092 | **NOT RUN** | 5ms polling around a tick boundary. Same reason as TIME-091, plus the measurement is below the scheduling noise of a loaded machine. The mechanism is confirmed at source level — `PumpingFeed.java:61` `PUBLISH_INTERVAL_NANOS = 20_000_000L`, gated at `:160`, and `QueryExecution.advanceWatermark` does not commit the `ViewSink` — but a confirmed mechanism is not an executed case |

---

## Interaction — the other bounds the watermark arms — TIME-116 … TIME-120

| Case | Verdict | Evidence |
|---|---|---|
| TIME-117 | **PASS** [IT], and the [E2E] half is unreachable by design | `EventTimeTest.time117_retentionEvictsInEventTimeAndCountsWhatItRemoved` drives it with `registry.retaining(Retention.ofAge(...))` and asserts eviction in **event time** with the counter's increments. The [E2E] half cannot be set up: `QueryRegistry.retaining(Retention)` has **no production caller** — `grep -rn "retaining(" --include=*.java` outside tests finds only the declaration at `QueryRegistry.java:232`. `PravahaNode` never calls it, there is no `pravaha.*.retention` key in `application.yaml`, and neither `pravaha register` nor `POST /api/v1/streams` carries one. So every view on every server uses `Retention.DEFAULT` = **24 hours of event time**, and the case's own fallback applies: over `evVeryLong.csv`'s two hours the default cannot be reached and `pravaha_query_view_evicted` stays 0. That is the finding the case anticipates, confirmed |
| TIME-118 | **BLOCKED** | Needs the `feedfile` plugin, which does not ship in the server artefact. The units claim is confirmed at source: `ViewSink.java:231,237` `Math.max(eventTime, sequence)`, and `Retention.horizonFor` (`:95-103`) subtracts a duration in nanoseconds from it — so for an event-time-zero source the frontier is a **row counter** and the horizon is `N − 86400·10^9`, hugely negative for any N a real node reaches. `Retention.horizonFor` *does* saturate (`committedFrontier - nanos > committedFrontier ? Long.MIN_VALUE : …`), so the arithmetic is safe; the units mismatch is not |
| TIME-119 | **case stale; not reproduced** | See Clock health above |

---

## Two streams in one query — TIME-010, TIME-011, TIME-012, TIME-041, TIME-074, TIME-076

An inner equi-join is the **only** shape on the server path that gives one `QueryExecution` two
watermark partitions. `PluginSourceFeeds.open` (`:112-140`) creates one pump per `SourcePartition`
of each stream the plan binds, all on lane 0, and a query that names one stream binds one stream.
That constrains what these cases can observe, and in one place it makes a case unanswerable.

| Case | Verdict | Evidence |
|---|---|---|
| TIME-010 | **PASS** (unpaced) | `conf/t010.yaml`: `jl` declared, `jr` not. `ROWS IN` **242** (121 + 121), view **0 rows** at an 8-second settle and **0** at a 50-second one, i.e. after `idle-after: 30s` has passed. The case predicts output once `jr`'s partition is excluded. It is not excluded and cannot be: `jr` **produces rows**, each row stamps the partition's high-water (at 0, since it has no event time), that mark moves from `Long.MIN_VALUE` once and is reported once, and thereafter the partition's watermark is `0 − 10s` — a real number, not `NOT_YET`, so the lane's minimum is pinned at `−10^10` for ever and nothing goes idle. Under fact 8's *old* behaviour the analysis in the case would have been right for a different reason; under the current one it is right for this reason. The row count a fully-matched join would give (121) is reached only when `jr` declares an event time — TIME-011 |
| TIME-011 | **PASS** on the three numbers, **case defect** on the join's | `conf/t011.yaml`, `jl` at `0s` and `jr` at `60s`, identical 121-row files. `Q10` over `jl` alone: **12** windows, last total 1145. `Q10` over `jr` alone: **6** windows, last total 545. The join: **121 rows** — every pair. The case predicts eviction at `min(T0+120, T0+60) − 5s` cuts that down; it does not, because both files are ingested before the first tick and every pair is formed before any watermark exists. The minimum rule itself is confirmed by the 12-vs-6 pair |
| TIME-012 | **PASS** | `conf/t012.yaml`: `ev.event-time: event_time` **and** the binding carrying `options: { event.time: id }`. The node **starts** — the disagreement is not caught at startup — and `sources bound: [ev <- filesystem[schema, event.time, path]]` shows the binding's own option survived, so `withDeclaredEventTime` (`PravahaNode.java:277-282`) did refuse to overwrite it. The failure surfaces at **first registration**, exactly where the case predicts: `PRV-1041  PRV-5091  the 'filesystem' plugin could not be opened for stream 'ev': java.lang.IllegalArgumentException: event-time field 'id' must be TIMESTAMP, got INT64 NOT NULL`. The case predicts `PRV-1xxx`; the code is `PRV-5091` |
| TIME-041 | **PASS** on the three numbers, **case defect** on the join's | `conf/t041.yaml`, `jl` at `0s` and `jr` at `30s`. `jl` alone **12** windows; `jr` alone **9** windows, last total **845** (`120 − 30 = 90`, ends 10 … 90). The join: **121 rows**, for TIME-011's reason. 12, 9, 121 |
| TIME-074 | **BLOCKED** — the E2E surface cannot express the question | `conf/t074.yaml` binds `jl` to `evB.csv` and `jr` to a **zero-byte** file, `idle-after: 5s`, and the join gives `ROWS IN` 121 and **0 rows** at 20s and at 45s. That is not evidence about idleness: an **inner** join with an empty right side emits nothing whatever the watermark does, so the observation cannot distinguish "the empty partition was never excluded" from "the join has nothing to match". The case's own setup note anticipates this (*"a join on a constant-true-ish equality that still parses, or a second stream feeding a projection — whichever produces two pumps"*) and neither alternative exists: a projection over a second stream does not put that stream in the query's plan, so it gets no pump. The mechanism the case is about **is** tested, decisively, by TIME-066 [IT] (`NOT_YET` until exactly `idle-after`, then the other partition's value) |
| TIME-076 | **PASS** [code + E2E] | `FilesystemSourcePlugin.partitions` (`:240-246`) returns exactly one `SourcePartition` per binding ("One file, one partition"), and `PluginSourceFeeds.open` creates one pump per partition and registers each with the tracker through `trackEventTimeOf`. So on a shipped server the partition count is the bound-stream count: 1 for every single-stream query in this log and 2 for every join. A binding whose plugin reports N > 1 is not reachable (see the per-plugin section), so the N > 1 half of the case is **not run**. Partition names are `<stream>#<lane>/<pumps>:<partitionedPumps>` and are distinct — TIME-077 |

---
