# TIME — event time, watermarks, partition quiet time

The surface under test is the clock. `pravaha-runtime/.../time/WatermarkTracker.java` and
`WatermarkGenerator.java`; `exec/QueryExecution.java` (`generatingWatermarks`, `trackEventTimeOf`,
`advanceWatermarkQuietly`, `advanceWatermark`); `ingest/IngestPump.java`,
`PartitionedIngestPump.java` and the `DelegatingRowWriter` that both use to see a row's event time;
`pravaha-api/.../data/StreamSchema.java` (`eventTimeOrdinal`, `outOfOrderness`);
`pravaha-server/.../PravahaNode.java` (`withEventTime`, `withDeclaredEventTime`, the startup
validation of `pravaha.watermark.idle-after`); `pravaha-registry/.../QueryRegistry.start`; and the
`pravaha.watermark` block of `pravaha-server/src/main/resources/application.yaml`.

Watermarks arm **every** bound in this engine: `WindowedAggregate.advanceWatermark` fires and
releases windows, `SymmetricHashJoin.advanceWatermark` evicts at `watermark − matchWithin`, and
`ServedView.evict` forgets behind a committed frontier that only moves when rows are emitted. A
clock that does not advance is not a quiet query; it is unbounded state and a silent one.

**Ports:** HTTP 18300, Flight 19300. **Scratch:** `$QA = .../scratchpad/qa-time`.

## Facts this file assumes, each read out of the code

These are stated once so the cases can be terse. An executor who finds one of them false has found
something more interesting than the case that referenced it.

1. `StreamSchema.DEFAULT_OUT_OF_ORDERNESS` is **10s** (`StreamSchema.java:53`).
2. `QueryExecution.DEFAULT_IDLE_AFTER` is **30s**, `DEFAULT_TICK` is **1s**
   (`QueryExecution.java:367,370`).
3. `WatermarkTracker.MINIMUM_IDLE_TIMEOUT` is **1s**, `MAXIMUM_IDLE_TIMEOUT` is **10m**, and the
   constructor **refuses** rather than clamps (`WatermarkTracker.java:69,85,92`).
4. The server validates `idle-after` **once, at startup**, by constructing a throwaway
   `WatermarkTracker` (`PravahaNode.java:378-388`), then calls
   `registry.generatingWatermarks(idleAfter, tick)`. It does **not** validate `tick <= idle-after`;
   that check lives in `QueryExecution.generatingWatermarks` (`QueryExecution.java:339`) and
   therefore fires **per registration**.
5. `pravaha.watermark.out-of-orderness` **has no reader anywhere in the repository.** Round 1 proved
   this by experiment (`docs/project/qa/logs/DOC.md:1362-1385`, table row `DOC.md:1688`). The per-stream key
   `pravaha.streams.<n>.out-of-orderness` is read by `StreamDeclarationProperties` and applied by
   `PravahaNode.withEventTime` — but **only when `event-time` is also declared**, because
   `withEventTime` returns early when it is not (`PravahaNode.java:232-235`).
6. Lateness reaches the tracker per stream:
   `QueryExecution.trackEventTimeOf` reads
   `pipelines.get(lane).inputSchema(stream).outOfOrderness()` (`QueryExecution.java:288`), and the
   scan's schema is the catalog's, unmodified (`PhysicalPlanBuilder.java:502`).
7. **The event time of a row is whatever the plugin passes to `RowWriter.eventTimestampNanos`.**
   `DelegatingRowWriter.eventTimestampNanos` is the only hook (`DelegatingRowWriter.java:121-128`);
   a plugin that never calls it leaves the partition's high-water at `Long.MIN_VALUE` for ever.
8. `advanceWatermarkQuietly` runs on a daemon thread named `pravaha-watermark`, re-reads every
   partition's high-water **on every tick**, calls `watermarks.observe(partition, seen, now)` for
   each one whose high-water is not `Long.MIN_VALUE`, then `advance(now)`, then
   `QueryExecution.advanceWatermark` — and swallows any `RuntimeException` with a `WARN` line
   (`QueryExecution.java:408-429`).
9. `WatermarkTracker.observe` sets `lastActivityNanos = now` and `idle = false`
   (`WatermarkTracker.java:129-139`). Read fact 8 and fact 9 together before writing off any case
   in the quiet-partition section.
10. `SlicedWindows.windowsCompletedBetween` fires window ends `<= watermark`; a window whose end
    equals the watermark **has** fired (`SlicedWindows.java:116`).
11. `PhysicalPlanBuilder.DEFAULT_ALLOWED_LATENESS_NANOS` is **0** (`PhysicalPlanBuilder.java:858`),
    so `WindowedAggregate.process` sends a row to the late output the moment
    `lastWindowEndFor(windowStart) <= watermark` (`WindowedAggregate.java:144`).
12. `InterpretedPipeline.lateOutput` is wired to **nothing** on the server path; `lateRecords()` is
    exposed on `QueryExecution` and is published by **no** metric and **no** API field
    (`PravahaMetrics.java:120-135` lists the seven gauges there are).
13. The only watermark an operator can see is `pravaha_query_watermark_lag_seconds`, computed as
    `(now − watermark)/1e9` and `NaN` when there is no watermark (`PravahaMetrics.java:145-151`).
    It is fed from `RegisteredQuery.watermarkNanos`, which is written **only** by
    `RegisteredQuery.advanceWatermark` (`RegisteredQuery.java:225`) — a method the internal
    watermark clock never calls, because `QueryExecution.advanceWatermarkQuietly` goes straight to
    `QueryExecution.advanceWatermark`.
14. `FilesystemPartitionReader` latches `exhausted = true` on the first `readLine() == null` and
    never reads that file again (`FilesystemPartitionReader.java:78-80,71`). A regular file
    therefore cannot go quiet **and then resume**; a FIFO can, at the price that `readLine` blocks
    the shared `pravaha-feed-<query>` thread and starves the query's other partitions
    (`PumpingFeed.java:105-107` polls pumps in a sequential loop).
15. Window **assignment** reads the `DESCRIPTOR` column by ordinal
    (`WindowAssign.java:62`); the **watermark** is derived from the stamped event time. They are two
    different things and nothing checks that they are the same column.

## Standing setup

`$QA/conf/time.yaml` — HTTP 18300, Flight 19300, checkpoint directory `$QA/ckpt`, journal
`$QA/journal`, and:

```yaml
pravaha:
  streams:
    ev:    { schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP", event-time: event_time, out-of-orderness: 10s }
    noet:  { schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP" }
    small: { schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP", event-time: event_time, out-of-orderness: 2s }
    busy:  { schema: "id:INT64,k:STRING,amount:INT64,event_time:TIMESTAMP", event-time: event_time, out-of-orderness: 0s }
    quiet: { schema: "id:INT64,k:STRING,amount:INT64,event_time:TIMESTAMP", event-time: event_time, out-of-orderness: 0s }
  sources:
    ev:    { plugin: filesystem, options: { path: $QA/data/evB.csv,  schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP" } }
    noet:  { plugin: filesystem, options: { path: $QA/data/evB.csv,  schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP" } }
    small: { plugin: filesystem, options: { path: $QA/data/evA.csv,  schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP" } }
    busy:  { plugin: filesystem, options: { path: $QA/fifo/busy,     schema: "id:INT64,k:STRING,amount:INT64,event_time:TIMESTAMP" } }
    quiet: { plugin: filesystem, options: { path: $QA/fifo/quiet,    schema: "id:INT64,k:STRING,amount:INT64,event_time:TIMESTAMP" } }
  watermark:
    out-of-orderness: 10s
    idle-after: 30s
    tick: 1s
```

**T0 = 1767225600000000000 ns = 2026-01-01T00:00:00Z.** Every timestamp below is `T0 + n` seconds,
written `T0+n`.

**Data.**

| File | Rows | Shape |
|---|---|---|
| `evB.csv` | 121 | `k,u{k mod 5},k,T0+k` for k = 0…120. `amount = id = k`; one row per second |
| `evA.csv` | 12 | `k,u{k mod 5},k+1,T0+k` for k = 0…11. `amount = k+1` |
| `evRev.csv` | 121 | `evB.csv` with the lines in exactly reverse order |
| `evShuf.csv` | 121 | `evB.csv` shuffled with a fixed seed, recorded in the log |
| `evDup.csv` | 121 | `evB.csv` with every `event_time` rounded down to a multiple of 10s |
| `evSame.csv` | 121 | `evB.csv` with every `event_time` set to `T0+60` |
| `evLate.csv` | 122 | `evB.csv` plus a final line `900,u0,900,T0+3` |
| `evFuture.csv` | 122 | `evB.csv` plus a final line `901,u0,901,T0+86400` |
| `evPast.csv` | 122 | `evB.csv` plus a final line `902,u0,902,0` |

**The canonical arithmetic, computed once.** `Q10` is

```sql
SELECT window_start, window_end, COUNT(*) AS n, SUM(amount) AS total
FROM TABLE(TUMBLE(TABLE ev, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
GROUP BY window_start, window_end
```

registered with `--keys 0` (window_start is unique per window, so no row can hide inside a key).
Over `evB.csv`, window *i* (i = 1…12) covers ids 10(i−1)…10i−1, so `n = 10` and

`total(i) = Σ_{k=10i-10}^{10i-1} k = 10·(10i−10) + (0+1+…+9) = 100i − 100 + 45`

| i | window | n | total |
|---|---|---|---|
| 1 | [T0+0, T0+10) | 10 | 0+1+…+9 = 45 |
| 2 | [T0+10, T0+20) | 10 | 10+…+19 = 145 |
| 3 | [T0+20, T0+30) | 10 | 245 |
| 4 | [T0+30, T0+40) | 10 | 345 |
| 5 | [T0+40, T0+50) | 10 | 445 |
| 6 | [T0+50, T0+60) | 10 | 545 |
| 7 | [T0+60, T0+70) | 10 | 645 |
| 8 | [T0+70, T0+80) | 10 | 745 |
| 9 | [T0+80, T0+90) | 10 | 845 |
| 10 | [T0+90, T0+100) | 10 | 945 |
| 11 | [T0+100, T0+110) | 10 | 1045 |
| 12 | [T0+110, T0+120) | 10 | 1145 |
| 13 | [T0+120, T0+130) | 1 | 120 |

The highest event time in the file is T0+120. With out-of-orderness *d* the final watermark is
`T0+120−d`, and the windows that fire are those with `end <= T0+120−d`:

| d | final watermark | windows fired | last total |
|---|---|---|---|
| 0 | T0+120 | 12 (ends 10…120) | 1145 |
| 1ms | T0+119.999 | 11 (ends 10…110) | 1045 |
| 10s (default) | T0+110 | 11 (ends 10…110) | 1045 |
| 60s | T0+60 | 6 (ends 10…60) | 545 |
| 1m…10m ≥ 120s | ≤ T0+0 | 0 | — |

`Q5` is the same query over `small` with `INTERVAL '5' SECOND` over `evA.csv`:
W1 = [T0+0,T0+5) = 1+2+3+4+5 = **15**; W2 = [T0+5,T0+10) = 6+7+8+9+10 = **40**;
W3 = [T0+10,T0+15) = 11+12 = **23**, never complete. Highest event time T0+11, so `d = 2s` gives a
final watermark of T0+9 and fires **W1 only**; `d = 0` gives T0+11 and fires **W1 and W2**.

## Two standard procedures, so the cases can be terse

**S1 — the standard run.** `bin/pravaha-server --spring.config.location=$QA/conf/<file>`; wait for
the `watermarks: idle-after=…, tick=…` line; `pravaha register --name w --sql "<the case's query,
Q10 unless it names another>" --keys 0`; poll `pravaha queries` until `ROWS IN` stops changing, then
wait five ticks; `pravaha query --sql "SELECT * FROM w ORDER BY window_start"`. Record the row
count, every `n`, every `total`, and the `ROWS IN` the query finished on.

**S2 — the standard refusal.** `bin/pravaha-server --spring.config.location=$QA/conf/<file>`.
Record: the exit status, the complete error text including any stack trace, whether 18300 or 19300
ever accept a connection, and `/actuator/health` if anything answers. Then correct the offending
line and repeat, to prove the rest of the configuration is sound.

## Two levels, and why both

Cases marked **[E2E]** run against a started server through `bin/pravaha` (Flight, 19300), the HTTP
API (18300) and `/actuator/prometheus`. Cases marked **[UNIT]** run in
`pravaha-runtime/src/test/java/.../time/` against `WatermarkTracker` and `QueryExecution` directly,
because the tracker takes `nowNanos` as an argument precisely so a test can drive time
(`WatermarkTracker.java:120`). Anything about the *minimum-with-idle-exclusion* rule at more than
two partitions, or about a partition going quiet and resuming, is [UNIT]: on the server a
filesystem partition that reaches EOF can never speak again (fact 14), so the E2E surface cannot
express the question. Where a [UNIT] case has an E2E shadow, both are written.

## Vacuity, stated once

Every case in this file whose expected result is "some windows fired" is protected the same way: the
same `Q10` over the same `evB.csv` in the same server run must produce the full 11 rows with totals
45…1045 under the standing configuration. A case reporting "0 windows" is meaningful only when the
control produced 11 in the same process. Cases whose expected result is "nothing fired" carry the
opposite control: the same file, the same query, with the one setting under test returned to its
standing value, must produce 11. Both controls are cheap and neither is optional — round 1 shipped a
pause case that passed because the source had run dry.

---

## Event-time declaration

## TIME-001 — A correctly declared event-time column closes windows [E2E]
**Intent:** The control every other case in this file leans on, and the headline claim of the
feature: `pravaha.streams.ev.event-time` reaches the plugin, rows are stamped, the watermark
advances, windows fire.
**Falsifier:** Fewer than 11 window rows, or any total that is not the hand-computed one, or a view
that stays empty while `ROWS IN` reaches 121.
**Setup:** Standing setup. `ev` bound to `evB.csv`, `event-time: event_time`,
`out-of-orderness: 10s`, `idle-after: 30s`, `tick: 1s`.
**Steps:** Start the server. `pravaha register --name w10 --sql "<Q10>" --keys 0`. Poll
`pravaha queries` until `ROWS IN` reaches 121 and stops moving; wait a further 5s (5 ticks).
`pravaha query --sql "SELECT * FROM w10 ORDER BY window_start"`.
**Expected:** Exactly **11** rows. `n = 10` on every one. Totals in order:
45, 145, 245, 345, 445, 545, 645, 745, 845, 945, 1045. Window 12 (total 1145) and window 13
(total 120) are absent: the file's last event time is T0+120, `120 − 10 = 110`, and window 12 ends
at T0+120 > T0+110. Startup log contains `sources bound: [ev <- filesystem[event.time, path,
schema]]` and `watermarks: idle-after=PT30S, tick=PT1S`.
**Vacuity:** With the event-time declaration removed the same query emits nothing at all
(TIME-002), so a passing result here cannot be produced by a query that merely ingests.

## TIME-002 — No event-time declaration: RUNNING, ingesting, emitting nothing, for ever [E2E]
**Intent:** The brief's first question. `noet` is the same file and the same query with
`event-time:` left out. `withEventTime` returns the parsed schema unchanged
(`PravahaNode.java:232`), the plugin gets no `event.time` option, `DelimitedCodec.lastEventTimeNanos`
stays `Long.MIN_VALUE`, `FilesystemPartitionReader` never calls `eventTimestampNanos`, the
partition's high-water stays `Long.MIN_VALUE`, `advanceWatermarkQuietly` never observes it, and
`WatermarkTracker.advance` returns `current` for ever. Nothing errors.
**Falsifier:** Any window row at all in the view would falsify the analysis. So would a registration
refusal — which is what *should* happen and is TIME-003.
**Setup:** Standing setup.
**Steps:** `pravaha register --name w10n --sql "<Q10 with FROM noet>" --keys 0`. Wait for `ROWS IN`
= 121. Wait a further 120s — four times `idle-after`. `pravaha query --sql "SELECT * FROM w10n"`.
Read `pravaha_query_watermark_lag_seconds{query="w10n"}` from `/actuator/prometheus`. Grep the
server log for any WARN or ERROR mentioning `w10n`, `watermark` or `event time`.
**Expected:** Registration succeeds. State RUNNING. `ROWS IN` = 121. View holds **0 rows**. The
gauge is `NaN` (fact 13 — and it is `NaN` for *every* query on this node, see TIME-120, so it does
not distinguish this case). No log line anywhere says why. Record the exact elapsed time waited, so
"for ever" is a measurement and not a figure of speech.
**Vacuity:** `ev` and `noet` are byte-identical files under the same plugin in the same server run;
the only difference is the one configuration line. TIME-001 must show 11 rows in the same process.

## TIME-003 — A time-dependent plan over a stream with no event time should be refused at plan time
**Intent:** The case the brief asks for: the fix, written as a test. A `TUMBLE` over a stream whose
schema has no `eventTimeOrdinal` cannot ever produce a row, and the planner knows both facts —
`PhysicalPlanBuilder` holds the `StreamSchema` when it builds the `WindowAssignOperator`
(`PhysicalPlanBuilder.java:616`) and `StreamSchema.eventTimeOrdinal()` is one call away.
**Falsifier:** The registration succeeds (which is today's behaviour, TIME-002). A refusal that
names a *different* cause, or that also refuses a plain projection over the same stream, is also a
fail.
**Setup:** Standing setup.
**Steps:** As TIME-002, and separately
`pravaha register --name p_noet --sql "SELECT id, usr, amount FROM noet"`.
**Expected (desired):** The windowed registration is refused with a `PRV-2xxx` naming the stream,
naming the missing declaration, and naming the key to set
(`pravaha.streams.noet.event-time`). The projection registers and serves 121 rows: the refusal must
be scoped to plans that depend on event time, not to the stream.
**Expected (today):** Both succeed; the windowed one is silent for ever. Record this as the gap.
**Vacuity:** The projection half is the control. A refusal that swallowed both would be a
regression, not a fix.

## TIME-004 — Event-time column that does not exist is refused at startup [E2E]
**Intent:** The negative for the declaration. Round 1 saw the message (`INGEST-057`); this re-checks
it and checks the *timing* — startup, not first registration.
**Falsifier:** The node starts. Or it starts and fails later, per query. Or the message does not
list the real columns.
**Setup:** `conf/bad-column.yaml` = standing setup with `pravaha.streams.ev.event-time:
no_such_column`.
**Steps:** `bin/pravaha-server --spring.config.location=$QA/conf/bad-column.yaml`. Record the exit,
the elapsed seconds, and the full message. Then `curl -s localhost:18300/actuator/health` to confirm
nothing is listening.
**Expected:** The process dies. `PRV-2002 stream 'ev' declares 'no_such_column' as its event time
and has no such column. Its columns are [id, usr, amount, event_time].` Nothing on 18300 or 19300.
**Vacuity:** The same file with the column name corrected starts and passes TIME-001.

## TIME-005 — Event-time column of a non-temporal type is refused, but not as a Pravaha error [E2E]
**Intent:** `PravahaNode.withEventTime` checks only `hasField`; the type check is in
`StreamSchema.Builder.build` (`StreamSchema.java:276-280`) and throws a raw
`IllegalArgumentException` that nothing converts into a `PravahaException`. Two refusals for one
configuration mistake, with two different error surfaces.
**Falsifier:** The node starts (silently windowing on a STRING). Or the message carries a `PRV-`
code and names the key — in which case the analysis is wrong and this is simply a good refusal.
**Setup:** `conf/bad-type.yaml` = standing setup with `ev.event-time: usr` (STRING).
**Steps:** Start. Record the exit and the complete stack trace.
**Expected:** The process dies with `event-time field 'usr' must be TIMESTAMP, got VARCHAR` (the
exact `sqlName()` is to be recorded). Judge it against TIME-004's message: no `PRV-` code, no stream
name, no key name, and a stack trace rather than a diagnosis. Record as an error-quality defect if
so; ERRC owns the code, this case owns the observation.
**Vacuity:** TIME-004 in the same shape produces a `PRV-2002`, so the difference is the code and not
the harness.

## TIME-006 — Event time on an INT64 column that holds perfectly good epoch nanos [E2E]
**Intent:** The temptation. `event_time` and an INT64 have the identical binary layout and
`DelimitedCodec` parses both with `Long.parseLong` (`DelimitedCodec.java:116-127`). If the type
check were on the storage rather than the declared type, this would work by accident.
**Falsifier:** The node starts and windows fire. That would mean the `TIMESTAMP_LTZ` check is not
doing what `StreamSchema.java:277` says.
**Setup:** `conf/int-et.yaml`: `ev.schema: "id:INT64,usr:STRING,amount:INT64,event_time:INT64"`,
`event-time: event_time`, same `evB.csv`.
**Steps:** S2, then S1 with the type token changed back to `TIMESTAMP`.
**Expected:** Refused at startup: `event-time field 'event_time' must be TIMESTAMP, got BIGINT`.
The values are identical to TIME-001's; only the declared type differs.
**Vacuity:** Switching that one type token back to `TIMESTAMP` gives TIME-001 and 11 windows.

## TIME-007 — Event-time column named in the wrong case is refused [E2E]
**Intent:** `withEventTime` uses `parsed.hasField(column)`, which is `Map.containsKey` on an
exact-match index (`StreamSchema.java:118-120`). SQL elsewhere in this engine is case-insensitive
(`JdbcPartitionReader.indexOf` uses `equalsIgnoreCase`), so the two halves disagree.
**Falsifier:** The node starts — meaning the lookup is case-insensitive after all, which contradicts
the code and would be worth knowing.
**Setup:** `ev.event-time: EVENT_TIME`.
**Steps:** S2, then S1 with `event-time: event_time`.
**Expected:** `PRV-2002 ... has no such column. Its columns are [id, usr, amount, event_time].`
Record it as a usability trap: the refusal is correct, and the message does not say "did you mean
event_time".
**Vacuity:** The lower-case spelling in the same file starts.

## TIME-008 — Event-time column with surrounding whitespace is accepted [E2E]
**Intent:** `withEventTime` calls `.strip()` before the lookup (`PravahaNode.java:238`) and
`withDeclaredEventTime` strips again before passing `event.time` to the plugin
(`PravahaNode.java:277`). Both must strip or the plugin and the engine disagree about the column
and every row is stamped zero.
**Falsifier:** Startup refusal, or startup success with an empty view — the latter meaning one of
the two strips is missing.
**Setup:** `ev.event-time: "  event_time  "`.
**Steps:** S1. Compare the output row for row with TIME-001's.
**Expected:** Starts. 11 windows, totals 45…1045, identical to TIME-001 row for row.
**Vacuity:** TIME-002 shows what a stream whose stamp never arrives looks like: 121 rows in, 0 out.

## TIME-009 — An empty event-time declaration is silently the same as none [E2E]
**Intent:** `event-time: ""` passes the `isBlank()` guard and is treated as absent
(`PravahaNode.java:232`). An operator who typed the key meant to declare something.
**Falsifier:** A refusal (good — record it as better than expected), or windows firing.
**Setup:** `ev.event-time: ""` with `out-of-orderness: 10s` still present.
**Steps:** S1, extended: after the view reads empty, wait 120s and read it again; grep the whole
startup log for `event-time` and for `out-of-orderness`.
**Expected:** Node starts, no warning, query RUNNING, `ROWS IN` = 121, view empty for ever — TIME-002
with a key present in the file. The presence of `out-of-orderness` beside it is also ignored
(TIME-029). Record whether any log line mentions the blank declaration.
**Vacuity:** The same file with the column name filled in gives 11 windows.

## TIME-010 — Event time declared on one side of a join and not the other [E2E]
**Intent:** The asymmetric case. The join's two streams are two watermark partitions of one query
(`PluginSourceFeeds.java:106-117` calls `pumpInto` per bound stream on lane 0), and
`WatermarkTracker.advance` returns `current` the moment any non-idle partition reports `NOT_YET`
(`WatermarkTracker.java:157-161`). So the undeclared side pins the whole query — until `idle-after`
excludes it, because a partition that has never produced a row keeps its registration-time
`lastActivityNanos` and genuinely does go idle.
**Falsifier:** The join produces results immediately (no pinning), or never produces them even after
`idle-after` has passed (the exclusion is not working for a never-active partition either).
**Setup:** `jl` declared with `event-time: event_time`, bound to `evB.csv`; `jr` declared **without**
`event-time`, bound to a 121-row `right.csv` with the same ids. `idle-after: 30s`, `tick: 1s`.
Query: `SELECT l.id, l.amount, r.tag FROM jl l JOIN jr r ON l.id = r.id AND l.event_time BETWEEN
r.event_time - INTERVAL '5' SECOND AND r.event_time`.
**Steps:** Register; sample the view every second for 90s, recording the first second at which any
row appears.
**Expected:** The state's own arithmetic is what to check against. Predicted: no output for the
first ~30s; after `jr`'s partition is excluded at 30s the watermark becomes `jl`'s alone
(T0+120−10 = T0+110) and eviction runs at `T0+110 − 5s = T0+105`, so left rows with event times
below T0+105 that had not yet matched are dropped. Record the exact row count and compare against
the 121 a fully-matched join would give. Any shortfall is the interaction between an undeclared side
and join eviction, and it is silent.
**Vacuity:** Declaring `event-time` on `jr` as well must give the full match count in the same
server run; that is TIME-011.

## TIME-011 — Both sides declared, with different out-of-orderness, and the minimum rule [E2E]
**Intent:** The documented behaviour — "a query over three streams gets three different tolerances"
(`QueryExecution.java:398`) — combined with the minimum rule. `jl` at 0s and `jr` at 60s over
identical data must give the query `jr`'s clock, not `jl`'s.
**Falsifier:** The query's watermark tracks the *faster* side, or tracks one engine-wide number.
**Setup:** As TIME-010 but `jl.out-of-orderness: 0s`, `jr.out-of-orderness: 60s`, both bound to
121-row files with event times T0+0…T0+120.
**Steps:** Register the join; also register `Q10` over `jl` and `Q10` over `jr` separately in the
same run.
**Expected:** `jl` alone fires 12 windows (final watermark T0+120, ends 10…120). `jr` alone fires 6
(T0+120−60 = T0+60, ends 10…60). The join's effective watermark is `min(T0+120, T0+60) = T0+60`, so
its eviction horizon is T0+55 and every pair whose left row is older than that has been evicted:
count the emitted pairs and compare with 121. Record all three numbers together.
**Vacuity:** The three registrations differ only in which stream they read; identical results across
them would mean lateness is not per stream at all.

## TIME-012 — A `pravaha.sources` `event.time` option and the stream declaration together [E2E]
**Intent:** `withDeclaredEventTime` refuses to overwrite an explicit binding option
(`PravahaNode.java:270`), so a binding that names one column and a declaration that names another
leave the plugin stamping from one column while the engine's schema believes another. The two copies
of the schema are the exact drift `withDeclaredEventTime`'s javadoc says it exists to prevent.
**Falsifier:** The two agree, or the node refuses the disagreement.
**Setup:** `ev.event-time: event_time` and the `ev` binding carrying
`options: { event.time: id, ... }`. `id` is INT64 — so the plugin's own
`StreamSchema.Builder.eventTime("id")` must itself refuse at `configure` time.
**Steps:** S2 (the node may start; record whether it does), then register `Q10` and record where the
failure surfaces — startup, registration, or first row. Then repeat with the two spellings agreeing.
**Expected:** Predicted: the plugin refuses during `configure`, and the failure surfaces as
`PRV-1xxx the 'filesystem' plugin could not be opened for stream 'ev'` at first registration rather
than at startup — because bindings are opened lazily in `PluginSourceFeeds.open`. Record whether the
node starts. Then repeat with `event.time: event_time` (agreeing) and confirm 11 windows.
**Vacuity:** The agreeing variant in the same run is the control.

## TIME-013 — `DESCRIPTOR` naming the declared event-time column [E2E]
**Intent:** The control for TIME-014: assignment column and watermark column are the same, which is
what every example in `docs/SQL_SUPPORT.md:150` shows.
**Falsifier:** Anything other than the TIME-001 result.
**Setup:** Standing setup.
**Steps:** `Q10` exactly as written.
**Expected:** 11 rows, 45…1045.
**Vacuity:** TIME-014 is the same query with one identifier changed and must differ.

## TIME-014 — `DESCRIPTOR` naming a column that is not the declared event time [E2E]
**Intent:** Fact 15. `WindowAssign.process` slices on `row.getLong(descriptorOrdinal)` while the
watermark comes from the stamped event time. Nothing checks they are the same column, so a query can
be assigned by one clock and fired by another.
**Falsifier:** A plan-time refusal (good, and then this case documents a guard that exists). Or
output identical to TIME-001, which would mean the descriptor is being ignored.
**Setup:** Standing setup. `ev.event-time: event_time`.
**Steps:** Register
`SELECT window_start, window_end, COUNT(*) AS n FROM TABLE(TUMBLE(TABLE ev, DESCRIPTOR(amount), INTERVAL '10' SECOND)) GROUP BY window_start, window_end`
— `amount = id = k`, so the assignment clock runs 0…120 **nanoseconds** while the firing clock runs
T0+0…T0+120 **seconds**.
**Expected:** Predicted: every row lands in the single window `[0, 10ns)`…`[120, 130ns)` near the
epoch — 13 windows in the first 130 nanoseconds of 1970 — and the first watermark tick
(≈ T0+110 ≈ 1.767×10^18) is astronomically past all of them, so all 13 fire on the first tick and
every subsequent row is too late (fact 11) and is dropped to the unwired late output. Count the
output rows and the rows that vanished: 121 in, `Σn` out, and the difference is invisible.
**Vacuity:** TIME-013 in the same run produces 11 correct windows from the same 121 rows.

## TIME-015 — The declaration reaches the plugin, observably [E2E]
**Intent:** `withDeclaredEventTime` is the bridge between the two schemas, and its only external
evidence is a startup log line. An operator diagnosing TIME-002 needs it.
**Falsifier:** The line does not name `event.time`, or names it for a stream that did not declare
one.
**Setup:** Standing setup, with both `ev` (declared) and `noet` (not) bound.
**Steps:** Start; grep the log for `sources bound:`.
**Expected:** One line listing both bindings, with `event.time` among `ev`'s options and absent from
`noet`'s. Record the exact text. Note for DOCX: this is the *only* place the engine ever says
whether a stream has an event time; there is no API field and no metric.
**Vacuity:** Removing `ev`'s declaration removes the token from that line.

---

## Per-plugin event time

One case per plugin, plus the combinations that only appear when two plugins feed one query. The
claim under test is fact 7: a plugin that does not call `eventTimestampNanos` with a real time has
no event time, whatever the configuration says.

## TIME-016 — filesystem honours the declared column [E2E]
**Intent:** The one plugin that does. `FilesystemPartitionReader.poll` stamps the row from
`DelimitedCodec.lastEventTimeNanos()` when it is not `Long.MIN_VALUE`
(`FilesystemPartitionReader.java:92-95`).
**Falsifier:** An empty view with a non-zero `ROWS IN`.
**Setup:** Standing setup, `ev` bound to `evB.csv` with `event-time: event_time`.
**Steps:** S1.
**Expected:** 11 windows, totals 45…1045 — TIME-001's result, re-stated here as the plugin matrix's
first row so the matrix can be read without leaving this section.
**Vacuity:** TIME-002.

## TIME-017 — filesystem with a declared column the codec never reaches [E2E]
**Intent:** `DelimitedCodec.setField` records `lastEventTimeNanos` only in the
`INT64, TIME, TIMESTAMP_LTZ` branch (`DelimitedCodec.java:116-127`). A NULL in that column takes the
`setNull` path (`DelimitedCodec.java:95-103`) and never reaches it, so the row keeps whatever the
*previous* row left in `lastEventTimeNanos` — the field is reset per `decode` call
(`DelimitedCodec.java:85`), so verify which it is: reset to `Long.MIN_VALUE` means the row is not
stamped at all and keeps the writer's default.
**Falsifier:** A NULL event time producing a row stamped with the previous row's time.
**Setup:** `evNull.csv` = `evB.csv` with row k=50's `event_time` replaced by the null literal (empty
field). `ev.schema` declares `event_time:TIMESTAMP` (nullable by default — confirm).
**Steps:** Register `Q10`; read the view; also read `SELECT COUNT(*) FROM ev` via a projection view.
**Expected:** 121 rows in. Window 6 ([T0+50,T0+60)) must hold `n = 9` and
`total = 50+…+59 − 50 = 545 − 50 = 495` if the null row is excluded from its window, or `n = 10` and
`total = 545` if it is assigned by a zero stamp elsewhere. Both are defensible; record which, and
record where row 50 went. The watermark must not regress: the highest event time seen is unchanged.
**Vacuity:** The same file without the null gives `n = 10`, `total = 545` for that window.

## TIME-018 — feedfile hard-codes event time zero [E2E]
**Intent:** `FeedFilePartitionReader.poll` writes `.eventTimestampNanos(0L)` on every row
(`FeedFilePartitionReader.java:128`), with a comment explaining why. The consequence is that a
windowed query over a feedfile stream can never fire a window in the present, whatever
`pravaha.streams.<n>.event-time` says.
**Falsifier:** A window in 2026 firing over a feedfile source.
**Setup:** Stream `ff` declared with `event-time: event_time` and bound with `plugin: feedfile` to a
121-record feed file carrying the same timestamps as `evB.csv`.
**Steps:** Register `Q10` over `ff`. Wait 120s. Read the view. Read the log.
**Expected:** `ROWS IN` = 121, view **empty**. Every row is stamped 0, so with `d = 10s` the
watermark is `0 − 10^10 = −10^10` and the only windows that could fire are those ending before
1970-01-01T00:00:00Z minus ten seconds — of which the query has none, because assignment uses the
*column* (T0-based) while firing uses the *stamp* (0). The declaration is accepted and inert. No
warning anywhere.
**Vacuity:** The identical data through the filesystem plugin gives 11 windows (TIME-001) in the
same run.

## TIME-019 — delta hard-codes event time zero [E2E]
**Intent:** `DeltaPartitionReader` writes `.eventTimestampNanos(0L)`
(`DeltaPartitionReader.java:272`) with a comment saying a query that needs event time "names the
column in its DDL" — which, per fact 7, does not help, because naming the column changes the
*engine's* schema and not what this reader stamps.
**Falsifier:** Windows firing; or the comment turning out to be true because some other path stamps
the row.
**Setup:** Stream `dl` on a Delta table of 121 rows with an `event_time` column holding T0+k;
`event-time: event_time` declared.
**Steps:** As TIME-018.
**Expected:** `ROWS IN` = 121, view empty, no warning. Record this as the same defect as TIME-018
with a second plugin, and note in the log that the code comment is actively misleading.
**Vacuity:** As TIME-018.

## TIME-020 — jdbc stamps from `watermark.column`, not from the declared event time [E2E]
**Intent:** `JdbcPartitionReader.emit` stamps every row with `results.getLong(watermarkIndex)`
(`JdbcPartitionReader.java:188`) — the column the *binding* named for incremental reads, ignoring
`pravaha.streams.<n>.event-time` entirely.
**Falsifier:** The declared column being used, or the two agreeing by construction (choose a table
where they cannot).
**Setup:** A table with `id BIGINT`, `amount BIGINT`, `event_time BIGINT` (epoch nanos, T0+k) and
`seq BIGINT` (1…121). Binding: `plugin: jdbc, options: { watermark.column: seq, ... }`. Stream
declared with `event-time: event_time`.
**Steps:** Register `Q10` over the jdbc stream. Read the view.
**Expected:** Rows are stamped 1…121 **nanoseconds**, not T0+k. Assignment (by the `event_time`
column, T0-based) and firing (by the stamp, 1…121ns) are in different eras, so the analysis of
TIME-014 applies: the first tick's watermark is ~10^18 behind the assignment clock, no window in
2026 fires, and the view stays empty. Record the observed result exactly.
**Vacuity:** The same table with `watermark.column: event_time` is TIME-021 and must behave
differently.

## TIME-021 — jdbc with `watermark.column` = the event-time column [E2E]
**Intent:** The configuration that accidentally works, and the unit question. With
`watermark.column: event_time` holding epoch **nanos**, the stamp is correct and windows must fire
exactly as in TIME-001.
**Falsifier:** Anything other than 11 windows with totals 45…1045.
**Setup:** As TIME-020, `watermark.column: event_time`.
**Steps:** S1 against the jdbc binding.
**Expected:** 11 windows, 45…1045 — the jdbc plugin's only correct event-time configuration, and it
is correct by coincidence of units.
**Vacuity:** TIME-020 in the same run is empty.

## TIME-022 — jdbc with an epoch-millis watermark column: 10^6 too small, silently [E2E]
**Intent:** The realistic version of TIME-021. Every timestamp column in every real database is
millis or micros, not nanos, and `eventTimestampNanos(watermark)` does no conversion.
**Falsifier:** A conversion happening, or a refusal.
**Setup:** As TIME-021 but `event_time_ms` = `(T0 + k·10^9)/10^6` = 1767225600000 + k·1000, and
`watermark.column: event_time_ms`. The stream's `event-time` names the nanos column so assignment is
unchanged.
**Steps:** S1 against the jdbc binding, then TIME-021's configuration in the same session.
**Expected:** Stamps around 1.767×10^12 ns = 1970-01-01T00:29:27Z. Assignment is in 2026. No window
fires; the view is empty; nothing warns. Record the two clocks side by side — this is the shape of
"an event-time bug that looks like a hang".
**Vacuity:** TIME-021, same table, same query, one column name different, produces 11 windows.

## TIME-023 — aerospike stamps every row of a scan with the scan's start time [E2E/AERO]
**Intent:** `LutScanReader` stamps `scanStartedNanos = System.currentTimeMillis()·10^6`
(`LutScanReader.java:156,187`) — processing time wearing event time's clothes. Every record in one
scan carries an identical timestamp, so the watermark advances in scan-sized steps and every record
in a scan lands in whichever window the scan happened to start in.
**Falsifier:** Distinct timestamps within one scan, or a stamp derived from a record bin.
**Setup:** Aerospike set of 500 records written over 5 minutes with a `ts` bin holding their true
times. Stream declared with `event-time: ts`. Scan interval configured short enough for at least
three scans during the case.
**Steps:** Register `Q10`-shaped query over the aerospike stream with `DESCRIPTOR(ts)`. Record the
emitted windows and, separately, the scan count.
**Expected:** Assignment by `ts` spreads rows across ~30 ten-second windows; firing is driven by a
watermark that jumps from scan-start to scan-start, i.e. "now". So on the first tick after scan 1
the watermark is ≈ now and **every** window built from historical `ts` values is already past —
all fire at once, and any record arriving in scan 2 for those windows is too late and dropped
(fact 11). Count records in versus `Σn` out. Record the difference.
**Vacuity:** The same data through the filesystem plugin with the same `ts` values produces the
windows in order, with no drops.

## TIME-024 — lookup sources stamp zero, and the join output takes the probe's time [E2E]
**Intent:** `JdbcLookupPlugin` and `AerospikeLookupPlugin` both write
`eventTimestampNanos(0)` (`JdbcLookupPlugin.java:258`, `AerospikeLookupPlugin.java:157`), but a
lookup join emits with `row.eventTimestampNanos()` of the *probe* row (`LookupJoin.java:324`). So
the zero never reaches the watermark — provided lookup sources are not registered as watermark
partitions.
**Falsifier:** The lookup side appearing in `partitionHighWater` (it would pin the watermark at
`0 − d` for ever), or the enriched output carrying event time 0.
**Setup:** `ev` (filesystem, declared) joined `FOR SYSTEM_TIME AS OF` to a jdbc lookup table.
**Steps:** Register a windowed aggregate over the enriched stream. Also read the `sources bound:`
line and count how many pumps the query has.
**Expected:** One watermark partition (the probe stream only). 11 windows, totals as TIME-001 scaled
by whatever the enrichment multiplies. Confirm the enriched rows' event times equal the probe's by
inspecting the window each lands in.
**Vacuity:** TIME-025 shows what a genuine second partition stamped 0 does to the same query.

## TIME-025 — A query over one real clock and one zero clock: the minimum wins [E2E]
**Intent:** The combination that makes fact 7 operational. `ev` (filesystem, real times) and `ff`
(feedfile, all zeros) as the two sides of one join are two partitions of one tracker;
`advance` takes the minimum, so the query's watermark is `0 − d`, for ever, and `ev`'s perfectly
good clock is worth nothing.
**Falsifier:** The query's windows firing at all before `idle-after` excludes `ff`. Or firing after
it — see the prediction.
**Setup:** `ev` bound to `evB.csv`; `ff` bound to a feedfile with 121 matching records.
`idle-after: 30s`, `tick: 1s`. Join on `id`.
**Steps:** Register; sample the view every second for 180s.
**Expected:** No output at any point. Note the subtlety that makes this case worth writing: `ff`
*does* produce rows, so its high-water is `0`, not `Long.MIN_VALUE`; `advanceWatermarkQuietly`
re-observes that `0` on **every tick** (fact 8), which refreshes `lastActivityNanos` (fact 9), so
`ff` is **never idle** and is never excluded. The idle exclusion cannot rescue this query. Sample
`idleExclusions()` if reachable; predicted 0.
**Vacuity:** Replacing `ff`'s binding with a second filesystem file in the same run must produce
output, proving the join itself works.

---

## Out-of-orderness

Seven values × two config positions, plus the interactions. The A/B in TIME-026/027 is the case the
brief asks for: the two keys differ only in tree position, one is live and one is inert, and the
experiment that separates them is a single number in the output.

## TIME-026 — `pravaha.watermark.out-of-orderness` is inert: the decisive A/B [E2E]
**Intent:** The engine-level key is documented in `application.yaml:160-167`, `OPERATIONS.md:222`,
`CONCEPTS.md:66` and the javadoc of `StreamSchema.java:51`, and **no code reads it** (fact 5). This
case proves it with a number rather than with a grep, because a grep can be argued with.
**Falsifier:** The window count changing when the key changes. That would mean the key is live and
the analysis is wrong.
**Setup:** Two configurations differing in exactly one line.
- **A:** `pravaha.watermark.out-of-orderness: 60s`; `pravaha.streams.ev.out-of-orderness` **absent**;
  `ev.event-time: event_time`.
- **Control:** `pravaha.watermark.out-of-orderness: 10s` (the shipped default), same otherwise.
**Steps:** Start on A, register `Q10`, wait for `ROWS IN` = 121 plus 5 ticks, count the rows and
record the last `total`. Restart on Control and repeat.
**Expected:** **Both give 11 rows, last total 1045.** A's 60s never reaches the tracker: the stream
falls back to `StreamSchema.DEFAULT_OUT_OF_ORDERNESS` = 10s, so `120 − 10 = 110` and windows ending
10…110 fire. If the key were live, A would give **6** rows (`120 − 60 = 60`, ends 10…60, last total
545). 11 vs 11 is the proof of inertness; 11 vs 6 would be the proof of correctness.
**Vacuity:** TIME-027 performs the same experiment one level down the tree and must give 6. If both
cases give 11 the harness is broken, not the engine.

## TIME-027 — `pravaha.streams.ev.out-of-orderness` is live: the other half of the A/B [E2E]
**Intent:** Same experiment, same value, one level down the configuration tree.
**Falsifier:** 11 rows.
**Setup:** `pravaha.streams.ev.out-of-orderness: 60s`, `event-time: event_time`,
`pravaha.watermark.out-of-orderness` left at 10s.
**Steps:** As TIME-026.
**Expected:** **6** rows, totals 45, 145, 245, 345, 445, 545. Arithmetic: highest event time T0+120,
watermark T0+60, windows with end ≤ T0+60 are those ending at T0+10…T0+60 — six of them.
**Vacuity:** TIME-026's A configuration carries the identical duration in the identical spelling and
gives 11. The pair is the case; neither half is worth much alone.

## TIME-028 — The two keys set to contradictory values [E2E]
**Intent:** What an operator who read `OPERATIONS.md` and then read `CONCEPTS.md` will actually
write. No warning is emitted about the dead key.
**Falsifier:** A warning at startup naming the ignored key (which would be the fix).
**Setup:** `pravaha.watermark.out-of-orderness: 0s` **and**
`pravaha.streams.ev.out-of-orderness: 60s`.
**Steps:** Start; grep the whole startup log for `out-of-orderness`; register `Q10`.
**Expected:** 6 rows (the per-stream value wins because the other is not read at all). The log
mentions `out-of-orderness` **nowhere** — `PravahaNode` logs only `watermarks: idle-after=…,
tick=…`. Record that the engine never states the lateness in force for any stream, on any surface.
**Vacuity:** Swapping the two values (60s engine-level, 0s per-stream) must give 12 rows, not 6 —
that is TIME-030 and it is the same experiment read backwards.

## TIME-029 — Per-stream out-of-orderness without an event-time declaration is dropped [E2E]
**Intent:** `withEventTime` returns before it reads `getOutOfOrderness()`
(`PravahaNode.java:232-235`), so `out-of-orderness` under a stream that declares no `event-time` is
parsed, validated by Spring, and thrown away. Two keys under one stream, one of which silently
depends on the other.
**Falsifier:** A startup refusal (the fix), or the value taking effect.
**Setup:** `noet: { schema: ..., out-of-orderness: 60s }` with no `event-time`.
**Steps:** Start; register `Q10` over `noet`; also confirm via a projection that rows arrive.
**Expected:** Node starts, no warning. 121 rows in, 0 out (TIME-002's behaviour), and the 60s is
inert twice over. Record that neither key produces any diagnostic.
**Vacuity:** Adding `event-time: event_time` to the same block gives 6 windows (TIME-027).

## TIME-030 — Out-of-orderness 0: the strictly-ordered claim, honoured [E2E]
**Intent:** `WatermarkGenerator.ascending()` is `boundedOutOfOrderness(0)`, and the watermark equals
the highest event time seen. Over an in-order file this fires everything the data supports.
**Falsifier:** Fewer than 12 windows, or a 13th.
**Setup:** `ev.out-of-orderness: 0s`, `evB.csv` in order.
**Steps:** S1.
**Expected:** **12** rows. Watermark T0+120; windows ending T0+10…T0+120 all satisfy `end <=
watermark` (fact 10), so window 12 (total 1145) fires and window 13 (the lone row k=120, total 120)
does not — its end is T0+130.
**Vacuity:** The default 10s on the same file gives 11. One window is exactly the difference the
setting buys.

## TIME-031 — Out-of-orderness 0 against a source that is not ordered [E2E]
**Intent:** The cost of the claim. One row out of order behind a zero-tolerance watermark is late by
definition, and with allowed lateness 0 it is dropped to an unwired output (facts 11, 12).
**Falsifier:** The row appearing in its window's total.
**Setup:** `ev.out-of-orderness: 0s`; `evLate.csv` (`evB.csv` + a final row `900,u0,900,T0+3`).
**Steps:** Register `Q10`; read the view; read `QueryExecution.lateRecords()` if reachable by any
means, and record what an operator could have seen.
**Expected:** 12 windows. Window 1 total is **45**, not `45 + 900 = 945`: by the time the extra row
arrives the watermark has passed T0+10, the window has fired, and `lastWindowEndFor(T0+0) = T0+10 <=
watermark`, so the row goes to the late output. `Σn` over the view is 120, the file has 122 lines,
and nothing anywhere reports the discrepancy.
**Vacuity:** With `out-of-orderness: 60s` the same file gives window 1 a total of 945 — the row is
not lost, it is lost *to this setting*. Run both in the same session.

## TIME-032 — Out-of-orderness 1ms [E2E]
**Intent:** The smallest non-zero value anyone would type, and the boundary of "does a sub-second
duration survive parsing and reach the tracker as nanos".
**Falsifier:** 12 windows (the 1ms was rounded to zero) or 11 (it was rounded to a second).
**Setup:** `ev.out-of-orderness: 1ms`.
**Steps:** S1, then TIME-030's configuration in the same session, and compare the two counts.
**Expected:** Watermark T0+119.999s. Window 12 ends at exactly T0+120 > T0+119.999, so it does
**not** fire: **11** rows, last total 1045. The distinction from TIME-030 is one millisecond and one
window.
**Vacuity:** TIME-030 (0s) on the same file gives 12.

## TIME-033 — Out-of-orderness unset: the 10s default arrives from the schema, not the config [E2E]
**Intent:** `StreamSchema.DEFAULT_OUT_OF_ORDERNESS` is applied in the constructor when the builder
was given nothing (`StreamSchema.java:63`). With the engine key inert (TIME-026) this is the *only*
source of the ten seconds everybody quotes.
**Falsifier:** Any window count other than 11.
**Setup:** `ev.event-time: event_time` with no `out-of-orderness` anywhere in the file — remove the
`pravaha.watermark.out-of-orderness` line too.
**Steps:** S1.
**Expected:** 11 rows, last total 1045, identical to TIME-001.
**Vacuity:** TIME-027 shows the value is settable, so 11 here is a default and not a constant.

## TIME-034 — Out-of-orderness 1m [E2E]
**Intent:** A value larger than several windows, which is the regime where lateness starts costing
output rather than memory.
**Falsifier:** More than 6 windows.
**Setup:** `ev.out-of-orderness: 1m`.
**Steps:** S1, then repeat with `60s` and confirm the two spellings agree.
**Expected:** **6** rows, 45…545. Same arithmetic as TIME-027 (60s), written with the other
spelling: confirm `1m` and `60s` parse identically.
**Vacuity:** TIME-033's 11 in the same session.

## TIME-035 — Out-of-orderness 10m: the whole file is inside the tolerance [E2E]
**Intent:** The regime where a query that is ingesting perfectly produces nothing, which is
indistinguishable from TIME-002 from the outside.
**Falsifier:** Any window firing.
**Setup:** `ev.out-of-orderness: 10m`.
**Steps:** S1, then wait a further 300s and read the view again.
**Expected:** **0** rows. Watermark `T0+120 − 600 = T0−480`, which is before every window this
query has. `ROWS IN` = 121. No warning. Write down how an operator distinguishes this from a missing
event-time declaration: predicted answer, they cannot.
**Vacuity:** TIME-033 in the same session gives 11 from the identical file.

## TIME-036 — Negative out-of-orderness is refused [E2E]
**Intent:** `StreamSchema.Builder.outOfOrderness` refuses a negative (`StreamSchema.java:256-263`)
and `WatermarkGenerator.boundedOutOfOrderness` refuses one again (`WatermarkGenerator.java:55`).
Which fires, and does the operator get a `PRV-` code?
**Falsifier:** The node starting.
**Setup:** `ev.out-of-orderness: -1s`.
**Steps:** S2.
**Expected:** Startup failure from `StreamSchema.Builder` (it is reached first, inside
`PravahaNode.withEventTime`): `out-of-orderness must not be negative, got PT-1S. Zero means the
source is strictly ordered…`. As in TIME-005, record whether it carries a `PRV-` code and names the
configuration key; predicted: neither.
**Vacuity:** `0s` in the same position starts and gives 12 windows (TIME-030).

## TIME-037 — Absurdly large out-of-orderness: 3650 days [E2E]
**Intent:** No upper bound is enforced on lateness anywhere — unlike `idle-after`, which has one.
Ten years of tolerance is accepted, and the result is a query that will fire its first window in
2036.
**Falsifier:** A refusal, or a clamp, or an arithmetic fault.
**Setup:** `ev.out-of-orderness: 87600h`.
**Steps:** S1, and record the computed watermark if any surface exposes it.
**Expected:** Node starts. `ROWS IN` = 121. 0 windows. `maxSeen − d` = `1767225720·10^9 − 3.1536×10^17`
= about `1.4519×10^18` ns = 2016-01-04 — still a positive, sane long, so no overflow and no error.
Record the case for a maximum on lateness by the same argument `WatermarkTracker.MAXIMUM_IDLE_TIMEOUT`
makes for idleness.
**Vacuity:** TIME-033 in the same session.

## TIME-038 — Out-of-orderness large enough to underflow the watermark arithmetic [UNIT]
**Intent:** `watermark() = maxSeen − outOfOrdernessNanos` (`WatermarkGenerator.java:73`) has no
saturation. With a small `maxSeen` and a huge `d` the subtraction can wrap past `Long.MIN_VALUE` and
produce a *large positive* watermark — which would fire every window in the query at once. Compare
with `SymmetricHashJoin.advanceWatermark`, which explicitly saturates (`SymmetricHashJoin.java:311-317`)
and therefore shows the author knew this class of bug exists.
**Falsifier:** No wrap, i.e. the arithmetic saturates or the value is refused.
**Setup:** [UNIT] `boundedOutOfOrderness(Long.MAX_VALUE)`, then `observe(-1_000_000_000L)` (an event
time one second before the epoch, which is legal — `TIMESTAMP` is signed).
**Steps:** Assert on `watermark()`.
**Expected:** `−10^9 − (2^63−1)` wraps to `+9223372035854775809`, a watermark 292 years in the
future. Every open window fires; every subsequent row is late. Also record the boundary: the largest
`d` that does not wrap for an event time of T0.
**Vacuity:** With `d = 10s` the same observation gives `−1.1×10^10`, plainly negative. The wrap is
the whole finding.

## TIME-039 — Out-of-orderness larger than the window size [E2E]
**Intent:** The operational rule of thumb nobody writes down: lateness delays *every* window by its
own length, so `d` larger than the window means at least one whole window is always open.
**Falsifier:** The number of open (unfired) windows not matching `ceil(d / size)`.
**Setup:** `ev.out-of-orderness: 25s`, `Q10` (10s windows).
**Steps:** S1, then repeat at `10s` and `0s` in the same session.
**Expected:** Watermark T0+95; windows ending 10…90 fire, so **9** rows, last total 845. Windows
10, 11, 12, 13 stay open — `ceil(25/10) = 3` fully-populated open windows plus the partial one.
**Vacuity:** `d = 10s` gives 11, `d = 0s` gives 12, on the identical file.

## TIME-040 — Out-of-orderness is visible in the lag gauge, or it is visible nowhere [E2E]
**Intent:** `pravaha_query_watermark_lag_seconds` is the operator's only window onto the clock
(fact 13), and it is fed from `RegisteredQuery.watermarkNanos`, which the internal clock never
writes (also fact 13). If that reading is right the gauge is `NaN` on every query on the node,
whatever the watermark is really doing.
**Falsifier:** A finite lag value that tracks the data — which would mean something does call
`RegisteredQuery.advanceWatermark`, and the analysis is wrong.
**Setup:** Standing setup, `Q10` registered and 11 windows confirmed.
**Steps:** `curl -s localhost:18300/actuator/prometheus | grep watermark`.
**Expected:** `pravaha_query_watermark_lag_seconds{query="w10"} NaN`, on a query whose windows are
demonstrably firing. Record both facts together: the clock works and the only instrument that
reports it says "no watermark". If instead a number appears, record it and compare with
`T0+110` against wall clock — the data is from 2026-01-01, so the honest lag is enormous and the
gauge would be the one place that says so.
**Vacuity:** TIME-035 (a query whose watermark genuinely never advances) must show the same reading
as TIME-001 (a query whose watermark advances 11 times). Two queries that differ completely and read
identically is the finding.

## TIME-041 — Three streams, three tolerances, one query [E2E]
**Intent:** The claim in `QueryExecution.generatingWatermarks()`'s javadoc, stated as "a query over
three streams gets three different tolerances". The engine supports two-input joins, so this is
tested as a join of two plus a lookup, and the minimum rule is checked explicitly.
**Falsifier:** The query's watermark following anything but the minimum of the two stream clocks.
**Setup:** `jl.out-of-orderness: 0s`, `jr.out-of-orderness: 30s`, both fed 121 rows T0+0…T0+120.
**Steps:** Register the join with a windowed aggregate on top; separately register `Q10` on each
side alone.
**Expected:** `jl` alone: 12 windows. `jr` alone: 9 windows (`120 − 30 = 90`, ends 10…90, last total
845). The join: bounded by `min = T0+90`, so **9** windows of joined rows. 12, 9, 9 — and the third
number is the case.
**Vacuity:** Setting both to 0s must give 12 on all three.

## TIME-042 — Changing a stream's out-of-orderness does not affect a running query [E2E]
**Intent:** `StreamSchema` is immutable and a running query keeps the version it planned against
(`StreamSchema.java:29-31`). The tracker's generator is constructed once per partition at
`trackEventTimeOf` time. So a re-declaration mid-flight must be inert for the running query and live
for the next one.
**Falsifier:** The running query's window count changing without a restart.
**Setup:** Standing setup with `ev.out-of-orderness: 10s`; `Q10` registered as `w_before`.
**Steps:** With `w_before` running and settled at 11 windows, `POST /api/v1/streams` re-declaring
`ev` with a different lateness (or edit and restart, recording which surface actually permits it).
Register `Q10` again as `w_after`. Compare.
**Expected:** `w_before` unchanged at 11 windows. `w_after` reflects whichever schema the catalog
now holds; if the REST surface cannot set lateness at all, record that as the finding — the only
way to change the number is a file edit and a restart.
**Vacuity:** `w_before` and `w_after` read the same file; a difference between them is the point and
identical results mean the re-declaration did nothing.

## TIME-043 — Out-of-orderness on a stream that delivers exactly one row [E2E]
**Intent:** The degenerate case for `boundedOutOfOrderness`: `maxSeen` is set by the first
`observe`, so a single row gives an immediate finite watermark at `t − d`, and `advance` then has
`any = true` from one partition.
**Falsifier:** `NOT_YET` persisting after a row has arrived, or a window firing that the single row
cannot support.
**Setup:** `one.csv` — a single line `7,u2,7,T0+7`. `out-of-orderness: 2s`.
**Steps:** Register `Q10` over it; wait 60s.
**Expected:** Watermark `T0+7 − 2 = T0+5`, which is below the only window's end (T0+10), so **0**
rows out and 1 row in, for ever. With `out-of-orderness: 0s` the watermark is T0+7 and still below
T0+10 — still nothing. This is the smallest possible demonstration that a stream that has stopped
never emits its last window (see TIME-075).
**Vacuity:** Appending a second line at T0+20 to the same file and restarting gives one window
(total 7) — the row only becomes visible because a *later* row arrived.

## TIME-044 — `Duration` spellings: `10s`, `PT10S`, `10000ms` [E2E]
**Intent:** `out-of-orderness` is bound by Spring as a `java.time.Duration`, `idle-after` and `tick`
by `@Value` into `Duration`. Three spellings of one value must give one behaviour, or the
configuration surface has a trap in it.
**Falsifier:** Two spellings of the same duration producing different window counts.
**Setup:** Three runs: `ev.out-of-orderness:` `60s`, then `PT1M`, then `60000ms`.
**Steps:** S1 three times, once per spelling, recording the window count each time.
**Expected:** All three give **6** windows, 45…545. Record any spelling Spring rejects — a rejection
is acceptable, a silent misparse (e.g. `60` read as 60 **ms**) is not.
**Vacuity:** A fourth run at `10s` gives 11 in the same harness.

## TIME-045 — A bare number as a duration [E2E]
**Intent:** `out-of-orderness: 60` with no unit. Spring's relaxed binding reads a unitless number as
**milliseconds** unless a `@DurationUnit` says otherwise, and nothing here says otherwise.
**Falsifier:** It being read as seconds, or refused.
**Setup:** `ev.out-of-orderness: 60`.
**Steps:** S1, then S1 with `60s`, and compare.
**Expected:** Predicted: 60ms, so the watermark is `T0+119.94` and **11** windows fire — visually
identical to the correct configuration and 1000× off. An operator who meant 60 seconds gets 11
windows instead of 6 and has no way to tell. If instead startup fails, record that as the better
outcome.
**Vacuity:** `60s` in the same position gives 6 (TIME-027). The pair is the case.

---

## Partition quiet time — `pravaha.watermark.idle-after`

The bounds are the tracker's (fact 3) and the server validates them once at startup (fact 4). The
first six cases walk the bound; the rest check *where* the refusal happens, which is what round 1's
DEPLOY-053 got wrong before the fix.

## TIME-046 — `idle-after: 1s`, exactly the minimum, is accepted [E2E]
**Intent:** The bound is `< MINIMUM` (`WatermarkTracker.java:93`), so exactly one second must pass.
An off-by-one here refuses the documented minimum, which `OPERATIONS.md:258` promises is legal.
**Falsifier:** Startup failure.
**Setup:** `pravaha.watermark.idle-after: 1s`, `tick: 100ms` (it must be the finer of the two,
fact 4).
**Steps:** Start; grep for `watermarks: idle-after=`; register `Q10`.
**Expected:** Starts. Log says `watermarks: idle-after=PT1S, tick=PT0.1S`. 11 windows, 45…1045 —
the idle timeout does not change the arithmetic on a single well-behaved partition.
**Vacuity:** TIME-047 refuses at one millisecond less, in the same harness.

## TIME-047 — `idle-after: 999ms`, one millisecond below the minimum, is refused [E2E]
**Intent:** The other side of the same boundary, and the message.
**Falsifier:** The node starting, or clamping to 1s, or failing with a message that does not name
the bound.
**Setup:** `idle-after: 999ms`, `tick: 100ms`.
**Steps:** S2.
**Expected:** The process dies before opening a port.
`PRV-2002 pravaha.watermark.idle-after is PT0.999S, which this engine will not accept: an idle
timeout of PT0.999S is below the minimum of PT1S. Below a second, ordinary jitter -- a rebalance, a
GC pause, a source that polls on a timer -- excludes a partition that was merely slow, and the rows
already on their way then arrive behind the watermark and count as late. Left as configured, every
registration on this node would fail and the node would look healthy.` Check the exact text; it is
assembled from two places (`PravahaNode.java:381-386` wrapping `WatermarkTracker.java:93-99`).
**Vacuity:** 1s starts (TIME-046).

## TIME-048 — `idle-after: 30s`, the default, is what an unset key gives [E2E]
**Intent:** Two defaults must agree: `@Value("${pravaha.watermark.idle-after:30s}")`
(`PravahaNode.java:120`) and `QueryExecution.DEFAULT_IDLE_AFTER` (`QueryExecution.java:367`). They
are written in two files and nothing ties them together.
**Falsifier:** The logged value differing between "key absent" and "key set to 30s".
**Setup:** Run A with the whole `pravaha.watermark` block deleted; run B with `idle-after: 30s`.
**Steps:** Start each run (both should start); then S1 in each, comparing the logged duration and
the window count.
**Expected:** Both log `watermarks: idle-after=PT30S, tick=PT1S`. Both give 11 windows.
**Vacuity:** Run C at `idle-after: 5m` logs `PT5M`, so the log line is not a constant string.

## TIME-049 — `idle-after: 10m`, exactly the maximum, is accepted [E2E]
**Intent:** `> MAXIMUM` is the refusal (`WatermarkTracker.java:100`), so exactly ten minutes passes.
**Falsifier:** Startup failure.
**Setup:** `idle-after: 10m`, `tick: 1s`.
**Steps:** S1.
**Expected:** Starts, logs `idle-after=PT10M`, 11 windows.
**Vacuity:** TIME-050 refuses at one millisecond more.

## TIME-050 — `idle-after: 600001ms`, one millisecond above the maximum, is refused [E2E]
**Intent:** The upper boundary, exactly.
**Falsifier:** The node starting.
**Setup:** `idle-after: 600001ms`.
**Steps:** S2, then repeat at `600000ms` and at `10m`.
**Expected:** Dies with `PRV-2002 … is above the maximum of PT10M …` carrying the tracker's full
explanation (`WatermarkTracker.java:100-108`).
**Vacuity:** `600000ms` in the same harness starts (TIME-049 in its millisecond spelling — run both
spellings and confirm `10m` and `600000ms` behave identically).

## TIME-051 — `idle-after: 1h` is refused at startup, not per registration [E2E]
**Intent:** Round 1's DEPLOY-053: with this value the node **started**, logged the setting as in
force, recovered **0 of 3** journalled queries and reported itself healthy. The startup validation
at `PravahaNode.java:378-388` is the fix; this case is its regression test.
**Falsifier:** The node reaching `UP`. Or reaching `UP` with zero recovered queries and no error —
which is the original defect exactly.
**Setup:** `idle-after: 1h`, on a journal already holding three registered queries (register them in
a prior run under the standing configuration, then stop, then edit the one line).
**Steps:** Start. Record: exit status, whether 18300 ever listens, `/actuator/health`,
`/api/v1/queries`, and the log's `recovered N of M` line.
**Expected:** The process dies during startup, before the journal is replayed (the validation is
above `journalPath.ifPresent(...)` in `PravahaNode.start`). No port listens. No `recovered` line at
all. The three queries are untouched on disk and come back when the value is corrected.
**Vacuity:** Correcting the value to `30s` and restarting must recover 3 of 3 — proving the journal
was intact and the refusal was the only thing that stopped it.

## TIME-052 — `idle-after: 0s` is refused [E2E]
**Intent:** Zero is the value a person writes when they mean "never exclude", which is the opposite
of what it would do.
**Falsifier:** The node starting; or a different error from TIME-047's.
**Setup:** `idle-after: 0s`, `tick: 0s` (a 0 tick would otherwise exceed it and hit a different
check — record which check fires first).
**Steps:** S2.
**Expected:** The same `below the minimum of PT1S` refusal as TIME-047, naming `PT0S`.
**Vacuity:** TIME-046.

## TIME-053 — Negative `idle-after` is refused [E2E]
**Intent:** `Duration.toNanos()` of a negative is negative, so it fails the `< MINIMUM` test — the
same path, but check the message does not print something absurd like `PT-5S is below the minimum`
without saying it is negative.
**Falsifier:** The node starting, or a `NumberFormatException`/parse failure that hides the real
problem.
**Setup:** `idle-after: -5s`.
**Steps:** S2.
**Expected:** Refused with the minimum message naming `PT-5S`. Record whether the message is
actionable for someone who typed a minus sign by accident.
**Vacuity:** TIME-046.

## TIME-054 — The refusal costs one failure, not one per registration [E2E]
**Intent:** The design point stated in `PravahaNode.java:374-377`: "one bad value is one startup
failure, rather than at registration where it is every query failing separately". Prove the property,
not just the message.
**Falsifier:** A node that starts and then fails each registration individually.
**Setup:** `idle-after: 1h`; the journal holding three queries; and a client script that attempts
five further registrations after startup.
**Steps:** S2, with the five extra registrations attempted from a script that logs each attempt and
its error. Count the total number of distinct failures the system produced.
**Expected:** Zero registrations are attempted because nothing is listening. Exactly one error is
produced by the whole system. Compare and contrast with TIME-056, where the *other* watermark bound
behaves the old way.
**Vacuity:** TIME-056 is the control: the same class of bad value, validated in the other place,
produces N failures and a healthy-looking node.

## TIME-055 — The refusal names the key, the value and the remedy [E2E]
**Intent:** ERRC owns error codes; this case owns whether the *watermark* refusals are actionable.
**Falsifier:** A message that omits the key name (`pravaha.watermark.idle-after`), the offending
value, or the legal range.
**Setup:** The four refusing values from TIME-047, TIME-050, TIME-052, TIME-053.
**Steps:** S2 four times, once per value, and tabulate which of the five required elements each
message contains.
**Expected:** All four messages contain the key name, the rejected duration in ISO form, the bound
that was violated, its value, and the consequence sentence. Record any that do not. None of them
should print a stack trace as the primary output.
**Vacuity:** TIME-005's message (the non-temporal event-time column) fails all of these tests in the
same harness, so the bar is demonstrably not met everywhere.

## TIME-056 — `tick` longer than `idle-after`: refused per registration, node healthy [E2E]
**Intent:** The check at `QueryExecution.java:339-343` is correct and is in the wrong place. The
server validates `idle-after` at startup (fact 4) and does **not** validate `tick <= idle-after`, so
a node with `tick: 5m, idle-after: 30s` starts, logs both settings as in force, reports `UP`, and
fails **every** registration — precisely the failure mode the `idle-after` validation was added to
prevent.
**Falsifier:** The node refusing at startup (the fix), or the registrations succeeding.
**Setup:** `pravaha.watermark.tick: 5m`, `idle-after: 30s`, journal holding three queries.
**Steps:** Start. Record `/actuator/health`, the `watermarks:` log line, the `recovered N of M`
line, then attempt a fresh registration and record the error verbatim.
**Expected:** Node starts and reports UP. Log says `watermarks: idle-after=PT30S, tick=PT5M`.
`recovered 0 of 3`. Every registration fails with
`the watermark tick (PT5M) is longer than the idle timeout (PT30S), so a partition could not be
noticed idle until long after it was. Idleness is detected on the tick; the tick has to be the finer
of the two.` — an `IllegalStateException`/`IllegalArgumentException` with no `PRV-` code, arriving
once per registration. This is DEPLOY-053 reincarnated one key across.
**Vacuity:** `tick: 1s` on the same journal recovers 3 of 3.

## TIME-057 — `tick` exactly equal to `idle-after` is accepted [E2E]
**Intent:** The comparison is `tick.compareTo(idleAfter) > 0`, so equality passes. Equality is also
the worst legal setting — a partition is noticed idle up to one whole idle-period late — so check
whether anything warns.
**Falsifier:** A refusal at equality, or a warning where the code has none.
**Setup:** `tick: 30s`, `idle-after: 30s`.
**Steps:** S1, recording the wall-clock delay between `ROWS IN` settling and the first row appearing
in the view; then `tick: 31s` and record the registration failure.
**Expected:** Accepted, no warning, registrations succeed. With `Q10` the first watermark advance
happens 30s after registration, so windows appear in one burst; record the delay to first output.
**Vacuity:** `tick: 31s` must be refused per registration (TIME-056's shape), proving the comparison
is live.

## TIME-058 — `idle-after` applies to queries registered after the call, not before [UNIT]
**Intent:** `QueryRegistry.generatingWatermarks` sets fields consulted in `start()`
(`QueryRegistry.java:531`), so it is a per-registration decision. `PravahaNode` calls it before
recovery, which is why recovered queries get watermarks at all — an ordering worth pinning.
**Falsifier:** A query registered before the call acquiring a watermark, or a recovered query
lacking one.
**Setup:** [UNIT] A `QueryRegistry` with a feed factory. Register `q_before`; call
`generatingWatermarks(30s, 1s)`; register `q_after`.
**Steps:** [UNIT] as in Setup; assert on both executions. Then confirm the E2E ordering by reading
`PravahaNode.start` and the startup log: `watermarks:` must precede `recovered N of M`.
**Expected:** `q_before.execution.watermarkNanos()` is `OptionalLong.empty()`; `q_after`'s is
present. Then assert the E2E consequence: on the server the call precedes both journal recovery and
`feedingFrom`, so every query on a node shares one setting and no query can ever be missing it.
**Vacuity:** Reversing the two registrations must reverse the result.

## TIME-059 — `idle-after` accepts ISO-8601 and millisecond spellings identically [E2E]
**Intent:** As TIME-044, for the key that has bounds — a misparse here is refused rather than silent,
which makes it a better-behaved key than `out-of-orderness`.
**Falsifier:** `PT30S` and `30s` logging different values.
**Setup:** Three runs: `30s`, `PT30S`, `30000ms`.
**Steps:** S1 for the three legal spellings, recording the logged duration; S2 for the unitless one.
**Expected:** All three log `idle-after=PT30S`. Also run `idle-after: 30` (unitless): predicted 30ms,
which is **below the minimum** and therefore **refused** — the bound catches the unit trap that
TIME-045 shows `out-of-orderness` has no defence against. Record that contrast; it is the argument
for bounding lateness too.
**Vacuity:** The four runs differ only in that one token.

## TIME-060 — The tracker refuses rather than clamps, and the refusal is the tracker's own [UNIT]
**Intent:** `PravahaNode` validates by *constructing* a throwaway tracker
(`PravahaNode.java:380`) so the bounds live in one place. Prove there is no second copy that could
drift: the message the server prints must be the tracker's own string.
**Falsifier:** A clamp anywhere, or a bound expressed as a literal in `PravahaNode`.
**Setup:** [UNIT] `new WatermarkTracker(999_000_000L)` and `new WatermarkTracker(600_000_000_001L)`.
**Steps:** Assert both throw `IllegalArgumentException`; assert `new
WatermarkTracker(1_000_000_000L)`
and `new WatermarkTracker(600_000_000_000L)` do not; assert `idleTimeoutNanos` is stored unmodified
by checking that a partition goes idle at exactly that boundary (TIME-069).
**Expected:** All five assertions hold. Grep `PravahaNode.java` for the literals `1` second and
`10` minutes: there must be none.
**Vacuity:** A clamping implementation would pass the two constructor assertions and fail the
boundary one.

---

## Quiet partitions and the minimum-with-exclusion rule

`WatermarkTracker.advance` is twenty lines and has five distinct behaviours: a `NOT_YET` partition
short-circuits the whole computation; an idle partition is skipped; all-idle holds the watermark
where it is; a minimum below the current is a counted regression; and the exclusion transition is
counted once. Each gets a case, and then the combinations do.

**Read fact 8 and fact 9 together before predicting any E2E result here.** `advanceWatermarkQuietly`
re-observes every partition's retained high-water on every tick, and `observe` clears `idle` and
resets `lastActivityNanos`. On that reading a partition that has ever produced a row can **never**
be excluded on a running server, and the entire idle mechanism is reachable only by partitions that
have produced nothing at all. The [UNIT] cases test the tracker's logic, which is correct; the
[E2E] cases test whether the engine can ever reach it, which is the defect.

## TIME-061 — One quiet partition of two: the exclusion, in the tracker [UNIT]
**Intent:** The case `WatermarkTrackerTest.oneQuietPartitionDoesNotFreezeEveryWindow` exists for,
restated with hand arithmetic so the boundary is explicit.
**Falsifier:** The watermark staying at the quiet partition's last value after the timeout.
**Setup:** `new WatermarkTracker(30·10^9)`; partitions `p0`, `p1`, both
`boundedOutOfOrderness(2s)`, both added at `now = 0`.
**Steps:** `observe("p0", 100s, 0)`; `observe("p1", 50s, 0)`; `advance(0)`. Then
`observe("p0", 200s, 60s)`; `advance(60s)`.
**Expected:** First `advance` returns `min(100−2, 50−2) = min(98s, 48s) = 48s`. At `now = 60s`,
`p1`'s last activity was `0` and `60 − 0 = 60 ≥ 30`, so `p1.idle = true`, `idleExclusions() == 1`,
and the watermark is `p0` alone: `200 − 2 = 198s`. `watermark()` == 198s, `isIdle("p1")` true,
`regressions() == 0`.
**Vacuity:** Without the exclusion the second `advance` returns 48s — the value the first one
already returned. A test that only asserts "the watermark advanced" would pass on a single-partition
tracker; this one names both numbers.

## TIME-062 — One quiet partition of five [UNIT]
**Intent:** The minimum is over the *remaining* partitions, not over "all but the first" or "the
last one seen". With five partitions the difference between those readings is visible.
**Falsifier:** The result equalling any partition's value other than the minimum of the four active
ones.
**Setup:** Five partitions `p0`…`p4`, `d = 2s`, added at 0.
**Steps:** `observe(p_i, (100 + 10i)s, 0)` for i = 0…4 → 100, 110, 120, 130, 140.
`advance(0)`. Then re-observe `p1`…`p4` at `now = 60s` with their same times; leave `p0` silent;
`advance(60s)`.
**Expected:** First: `min(98, 108, 118, 128, 138) = 98s`. Second: `p0` idle (60 ≥ 30), minimum over
`p1`…`p4` is `110 − 2 = 108s`. `idleExclusions() == 1`, `partitionCount() == 5`.
**Vacuity:** If the exclusion were skipped the answer would still be 98s — the same number as
before, which is exactly the "hang" signature. The case asserts 108, not "greater than 98".

## TIME-063 — Every partition quiet: the watermark holds rather than jumping to infinity [UNIT]
**Intent:** The `!any` branch (`WatermarkTracker.java:167-173`). Firing every open window because
the source went quiet would turn a lull into a flood of premature results.
**Falsifier:** `advance` returning `Long.MAX_VALUE`, or any value above the last real minimum.
**Setup:** Two partitions, `d = 2s`, both observed at `now = 0` with 100s and 50s.
**Steps:** `advance(0)` → 48s. `advance(60s)` with no intervening observations.
**Expected:** Both idle, `any == false`, return **48s** exactly — unchanged. `idleExclusions() == 2`
(one transition each, counted on the same tick). A further `advance(120s)` still returns 48s and
`idleExclusions()` is still 2: the counter counts transitions, not ticks.
**Vacuity:** A `Long.MAX_VALUE` here would fire every window in the query at once; 48s is the only
value that is not a bug.

## TIME-064 — A partition goes quiet and then speaks again with a newer time [UNIT]
**Intent:** "Idleness is a temporary exclusion rather than a removal"
(`WatermarkTracker.java:35-36`). The rejoining partition must be back in the minimum immediately.
**Falsifier:** The rejoined partition being ignored, so the watermark stays at the other partition's
value.
**Setup:** As TIME-061, ending with the watermark at 198s and `p1` idle.
**Steps:** `observe("p1", 150s, 70s)`; `advance(70s)`.
**Expected:** `p1.idle` false, `lastActivityNanos = 70s`. Minimum is `min(198, 148) = 148s` — which
is **below** the current 198s, so the `minimum < current` branch runs: the watermark stays **198s**
and `regressions()` becomes **1**. This is the case people get wrong: rejoining does not pull the
watermark back, it is counted as a fault.
**Vacuity:** If `p1` rejoins with 250s instead, the minimum is `min(198, 248) = 198s`, unchanged and
with no regression — so the two sub-cases distinguish "held" from "not applicable".

## TIME-065 — A partition rejoins with an older time: never backwards, and counted [UNIT]
**Intent:** The explicit contract (`WatermarkTracker.java:38-42`): the tracker reports the highest
watermark it has ever reached, and a genuine regression is countable and reportable.
**Falsifier:** `watermark()` decreasing, or `regressions()` staying at zero while it happens.
**Setup:** TIME-064's second sub-case, extended: after the regression, `observe("p1", 300s, 80s)`;
`advance(80s)`.
**Steps:** As in Setup, asserting `watermark()` and `regressions()` after each `advance`.
**Expected:** Minimum `min(198, 298) = 198s` — still `p0`. Watermark 198s. `regressions()` stays 1;
a repeat below the current counts again only when it happens again. Then `observe("p0", 400s, 90s)`;
`advance(90s)` → `min(398, 298) = 298s`, watermark 298s, regressions still 1.
**Vacuity:** A tracker that simply took the latest minimum would report 148s at the first step and
298s at the last; the sequence 198, 198, 298 is only produced by the monotonic rule.

## TIME-066 — A partition that has never produced a row holds everything back until it goes idle [UNIT]
**Intent:** The short-circuit at `WatermarkTracker.java:155-162`: a `NOT_YET` partition returns
`current` for the whole lane, discarding the other partitions' progress entirely. It is the
conservative choice and it is also the only path by which the idle mechanism is reachable on a
running server (fact 8).
**Falsifier:** The lane watermark advancing while a registered partition has produced nothing.
**Setup:** Two partitions `p0`, `p1` added at `now = 0`, `d = 2s`. Only `p0` ever observes.
**Steps:** `observe("p0", 100s, 0)`; `advance(0)`; `advance(10s)`; `advance(29s)`; `advance(30s)`;
`advance(31s)`.
**Expected:** `advance(0)`, `advance(10s)`, `advance(29s)` all return `NOT_YET`
(`Long.MIN_VALUE`) — `current` is still `NOT_YET` because the loop returned early each time.
`advance(30s)`: `p1` is idle (`30 − 0 >= 30`), skipped; `p0` gives `98s`; watermark becomes **98s**.
`advance(31s)` returns 98s. Note the ordering inside the loop — idleness is evaluated **before** the
`NOT_YET` check for that partition — so the exclusion wins.
**Vacuity:** With `p1` removed entirely the first `advance` returns 98s; the case is the 30-second
delay, not the final value.

## TIME-067 — Every partition has produced nothing [UNIT]
**Intent:** The degenerate start-up state of every query. `NOT_YET` must be returned, not 0 and not
`Long.MAX_VALUE`, and `QueryExecution.advanceWatermarkQuietly` must not push it downstream
(`QueryExecution.java:421` guards on `Long.MIN_VALUE`).
**Falsifier:** A watermark of `Long.MIN_VALUE` reaching `WindowedAggregate.advanceWatermark` — it
would set `watermark` to `MIN_VALUE + 1` semantics and start firing from `firstWindowStart()`.
**Setup:** Three partitions, none observed.
**Steps:** `advance(0)`, `advance(29s)`, `advance(31s)`.
**Expected:** `NOT_YET` at 0 and 29s. At 31s all three are idle, `any == false`, so the `!any`
branch returns `current`, which is still `NOT_YET`. `idleExclusions() == 3`. No downstream call.
**Vacuity:** Observing any one partition at 31s and advancing again must produce a real number.

## TIME-068 — Partitions alternating: each one quiet in turn [UNIT]
**Intent:** The realistic shape — two sources that speak in alternation, each one silent while the
other talks. With `idle-after` longer than the alternation period neither is ever excluded and the
minimum is genuinely the older one; with it shorter, they take turns being excluded and the
watermark advances in steps of the alternation period.
**Falsifier:** The watermark advancing smoothly in the first configuration (it must lag by one
alternation period), or freezing in the second.
**Setup:** Two partitions, `d = 0`. `p0` observes at wall times 0, 20s, 40s, 60s with event times
T0+0, T0+20, T0+40, T0+60; `p1` observes at wall times 10s, 30s, 50s with event times T0+10, T0+30,
T0+50. Run once with `idle-after = 30s`, once with `idle-after = 15s`.
**Steps:** `advance` after each observation.
**Expected:** With `idle-after = 30s`: no partition is ever silent for 30s, so the watermark is
always the minimum — after `p0` speaks at 60s it is `min(T0+60, T0+50) = T0+50`. With
`idle-after = 15s`: at wall 20s, `p1`'s last activity was 10s → `20 − 10 = 10 < 15`, still active,
watermark `min(T0+20, T0+10) = T0+10`; at wall 40s, `p1` last spoke at 30s → 10 < 15, watermark
T0+30. The exclusion never actually fires because the alternation period (10s) is below the timeout.
Then extend `p0` alone to wall 80s and confirm `p1` is excluded at wall 65s and the watermark jumps
to `p0`'s value. Record all eight numbers.
**Vacuity:** The two configurations read the same observation sequence; identical results across
them would mean `idle-after` is not consulted.

## TIME-069 — The idle boundary is `>=`, tested at exactly the timeout [UNIT]
**Intent:** `nowNanos - lastActivityNanos >= idleTimeoutNanos` (`WatermarkTracker.java:148`).
Exactly-at-the-boundary is idle. One case per side, because an off-by-one here is a partition
excluded a whole tick early or late and nothing would ever show it.
**Falsifier:** Idleness at `timeout − 1ns`, or activity at exactly `timeout`.
**Setup:** Two partitions, `p0` observed at 0, `p1` observed at 0; `idle-after = 30s`.
**Steps:** `advance(30s − 1)`; then `advance(30s)` after re-observing only `p0` at the same wall
times.
**Expected:** At `29.999999999s` neither is idle. At exactly `30s` both are idle unless re-observed
on that same call. Assert `isIdle("p1")` false then true across the one-nanosecond step.
**Vacuity:** The two calls differ by 1ns of driven clock and nothing else.

## TIME-070 — `idleExclusions` counts transitions, not ticks [UNIT]
**Intent:** The counter is documented as "the metric that explains a moving watermark"
(`WatermarkTracker.java:206`). A per-tick counter would read 3600 an hour into a quiet partition and
mean nothing.
**Falsifier:** The counter rising on a tick where no partition changed state.
**Setup:** Two partitions, `p0` active throughout, `p1` silent from 0.
**Steps:** `observe(p0, 100s, t)` and `advance(t)` for t = 0, 10s, 20s, 30s, 40s, 50s, 60s.
**Expected:** `idleExclusions()` is 0 at t = 0…20s, becomes **1** at t = 30s, and is still **1** at
60s. Then `observe(p1, 120s, 70s)`, `advance(70s)` → not idle, counter unchanged at 1; then silence
until `advance(100s)` → counter **2**.
**Vacuity:** A tick-counting implementation would read 4 at t = 60s.

## TIME-071 — A partition that goes idle while it held the minimum: the size of the jump [UNIT]
**Intent:** The operational consequence nobody computes in advance. When the laggard drops out, the
watermark leaps to the next-lowest — and every row already in flight below the new watermark is now
late (`OPERATIONS.md:245`, "on-time data becomes a late correction").
**Falsifier:** The jump being smaller than the gap between the two partitions' watermarks.
**Setup:** `p0` at event time T0+300, `p1` at T0+60, `d = 0`, `idle-after = 30s`. Both observed at
wall 0, then `p0` alone re-observed at wall 40s with T0+300.
**Steps:** `advance(0)`; `advance(40s)`.
**Expected:** First: `min(T0+300, T0+60) = T0+60`. Second: `p1` excluded, watermark **T0+300** — a
jump of **240 seconds of event time in one tick**. Any window ending in (T0+60, T0+300] fires on
that single tick; with 10s windows that is 24 windows at once. Compute and assert the count.
**Vacuity:** Without the exclusion the second advance returns T0+60 and no window fires. 24 vs 0 is
the case.

## TIME-072 — One quiet partition of two, on a running server [E2E]
**Intent:** The E2E shadow of TIME-061, and the case that decides whether the idle mechanism is
reachable at all through the shipped engine. Per fact 8, `advanceWatermarkQuietly` re-observes the
retained high-water of every partition on every tick, which per fact 9 resets its activity clock —
so a partition that has produced rows and then stopped is predicted **never** to be excluded.
**Falsifier:** The query's windows resuming after `idle-after` elapses. That would mean the
re-observation reading is wrong and the mechanism works.
**Setup:** `busy` and `quiet` bound to FIFOs (fact 14). `idle-after: 5s`, `tick: 1s`, both streams
`out-of-orderness: 0s`. Query: `Q10` over a join of `busy` and `quiet` on `k`, or — simpler and
sufficient — a two-input query whose output depends only on `busy`:
`SELECT window_start, window_end, COUNT(*) AS n FROM TABLE(TUMBLE(TABLE busy, DESCRIPTOR(event_time), INTERVAL '10' SECOND)) GROUP BY window_start, window_end`
registered in the same execution as a projection over `quiet`, so both streams are pumps of one
query. (Record which shapes actually produce two pumps in one `QueryExecution`; if only a join does,
use the join.)
**Steps:** Write 30 rows to `quiet` with event times T0+0…T0+29, then stop writing (hold the FIFO
open). Write to `busy` continuously, event times T0+0 upward at one row per 100ms, for 120s. Sample
the view every second and record the highest `window_end` present at each sample.
**Expected (desired):** `quiet` is excluded 5s after its last row, and `busy`'s windows continue to
fire — the view keeps growing for the full 120s.
**Expected (predicted):** The view stops at `window_end = T0+30` and never moves again, because
`quiet`'s high-water of T0+29 is re-observed on every tick and it is never idle. Record the last
window emitted and the wall-clock time it stopped. That is DEFECT-18's mechanism, one level deeper
than round 1 diagnosed it.
**Vacuity:** A run with `quiet` unbound (one pump only) must keep firing for the whole 120s, proving
the stall is caused by the second partition and not by the FIFO harness.

## TIME-073 — All partitions quiet, on a running server [E2E]
**Intent:** The E2E shadow of TIME-063. No premature flood.
**Falsifier:** Windows firing for event times beyond the last row seen, or the view gaining rows
after both sources stop.
**Setup:** As TIME-072; stop writing to both FIFOs after 30 rows each (T0+0…T0+29).
**Steps:** Record the view; wait 120s (24× `idle-after`); record it again.
**Expected:** Identical. With `d = 0` the watermark is T0+29, so windows ending T0+10, T0+20 have
fired (n = 10 each) and the window ending T0+30 has not — it holds ids 20…29 and never emits.
**Vacuity:** Writing one more row at T0+35 to either FIFO must immediately release the T0+30 window
with `n = 10`, proving the rows were there all along.

## TIME-074 — A partition quiet before it ever produced a row, on a running server [E2E]
**Intent:** The E2E shadow of TIME-066, and — per fact 8 — the *only* idle transition the server can
actually reach. A partition whose high-water is still `Long.MIN_VALUE` is never observed, so its
`lastActivityNanos` stays at registration and it genuinely goes idle.
**Falsifier:** The query never producing output, which would mean even this path does not work.
**Setup:** `ev` bound to `evB.csv` and `empty` bound to a zero-byte file, both inputs of one query
(join on a constant-true-ish equality that still parses, or a second stream feeding a projection —
whichever produces two pumps). `idle-after: 5s`, `tick: 1s`, `ev.out-of-orderness: 0s`.
**Steps:** Register; sample the view every second for 60s; record the first second at which any row
appears.
**Expected:** Nothing for the first ~5s (the `NOT_YET` short-circuit holds everything), then the
empty partition is excluded and windows fire from `ev` alone. First output at ≈ 5s ± one tick, then
12 windows. Compare directly with TIME-072, where the partition *did* produce rows and is predicted
never to be excluded: **a partition that produced one row is worse off than one that produced none.**
That sentence is the finding.
**Vacuity:** With `idle-after: 30s` the first output must move to ≈ 30s. Two runs, one number.

## TIME-075 — A bounded source never closes its last window [E2E]
**Intent:** Round 1's DEFECT-18, re-stated with exact arithmetic and re-checked. There is no
end-of-stream watermark: `FilesystemPartitionReader` latches `exhausted` and the tracker has no
"this partition is finished, ignore it for ever" state distinct from idle.
**Falsifier:** The final window appearing at any point.
**Setup:** Standing setup, `ev.out-of-orderness: 0s`, `idle-after: 1s`, `tick: 100ms`.
**Steps:** Register `Q10`; wait for `ROWS IN` = 121; wait a further 300s; read the view.
**Expected:** **12** windows, totals 45…1145. Window 13 — `[T0+120, T0+130)`, holding id 120 alone,
`total = 120` — never appears, because the watermark is `T0+120` and `T0+130 > T0+120`. One row of
121 is permanently invisible. With the standing `out-of-orderness: 10s` it is **11** windows and ids
110…120 — **11 rows of 121, 9%** — that are ingested, counted in `ROWS IN`, and never emitted.
Record both numbers.
**Vacuity:** Appending a row at T0+200 and restarting (the file is re-read from BEGINNING with no
checkpoint directory) must release windows 13 through 20, proving the data was retained and only the
clock was missing.

## TIME-076 — Two partitions of the same stream [E2E]
**Intent:** `PluginSourceFeeds.open` creates one pump per `SourcePartition`
(`PluginSourceFeeds.java:108-117`), and `trackEventTimeOf` registers each as its own watermark
partition. The filesystem plugin returns exactly one partition per binding
(`FilesystemSourcePlugin.java:193-199`), so this needs either a plugin that partitions or two
streams. Establish which, because every multi-partition claim in `OPERATIONS.md` depends on it.
**Falsifier:** More or fewer watermark partitions than source partitions.
**Setup:** Standing setup; a binding whose plugin reports N > 1 partitions (jdbc keyed mode, or
aerospike's partition ranges).
**Steps:** Register a query over it; count `pravaha-feed` pumps via `describe()` ("reading X (N
partitions)") and infer the tracker's `partitionCount`.
**Expected:** N pumps, N tracker partitions, one lane. Record the partition names the engine
generates — see TIME-077.
**Vacuity:** A single-partition binding in the same run reports 1.

## TIME-077 — Watermark partition names can collide [UNIT]
**Intent:** `trackEventTimeOf` builds the name as
`streamName + "#" + laneIndex + "/" + pumps.size() + partitionedPumps.size()`
(`QueryExecution.java:284`) — string **concatenation** of two integers, not addition. `(1, 12)` and
`(11, 2)` both render `"…/112"`. `WatermarkTracker.addPartition` does not check for duplicates and
`observe` returns after the first match (`WatermarkTracker.java:130-137`), so the second partition
of a colliding pair is never observed: its generator stays `NOT_YET`, it holds the whole lane back
until `idle-after`, and then it is excluded — silently, for ever.
**Falsifier:** Distinct names for every `(pumps, partitionedPumps)` pair.
**Setup:** [UNIT] A `QueryExecution` over a two-input plan with `generatingWatermarks()` called
first, then 1 `pumpInto` and 12 `pumpPartitionedInto` calls; separately 11 and 2.
**Steps:** Read the tracker's partition names (reflectively or via a test hook) and compare.
**Expected:** A duplicate name in at least one arrangement. Assert the consequence directly:
`tracker.observe(dup, t, now)` updates only the first, and the second's generator never leaves
`NOT_YET`.
**Vacuity:** The intended expression (a separator between the two counts, or a single counter)
produces 13 distinct names for 13 pumps in every arrangement.

## TIME-078 — Observing an unregistered partition throws, and the clock swallows it [UNIT]
**Intent:** `WatermarkTracker.observe` throws `IllegalArgumentException` for an unknown name
(`WatermarkTracker.java:138`), and `advanceWatermarkQuietly` catches `RuntimeException` and logs a
`WARN` (`QueryExecution.java:424-428`). So a bookkeeping mistake becomes a log line and a stopped
clock rather than a failure.
**Falsifier:** The exception escaping, or the watermark advancing anyway.
**Setup:** [UNIT] A `QueryExecution` with watermarks, with `partitionHighWater` containing an entry
whose name the tracker does not know (reachable through TIME-077's collision, or injected).
**Steps:** Drive one tick.
**Expected:** One `WARN could not advance the watermark: java.lang.IllegalArgumentException: no
partition named '…' is registered with this lane`. `watermarkNanos()` unchanged. Because
`partitionHighWater.forEach` throws part-way, the partitions *after* the bad one are not observed on
that tick either — assert that too, using three partitions with the bad one in the middle.
**Vacuity:** Removing the bad entry makes the same tick advance the watermark.

## TIME-079 — Registration while the clock is ticking: the `ConcurrentModificationException` [E2E]
**Intent:** Round 1's DEFECT-17. `partitionHighWater` is a plain `LinkedHashMap`
(`QueryExecution.java:94`), iterated by the `pravaha-watermark` thread while `trackEventTimeOf` puts
into it from the registering thread. The exception is caught and logged, so the clock survives — but
that tick's advance is lost and nothing counts how often it happens.
**Falsifier:** No exception under sustained concurrent registration (which would be good, and would
mean the map was made concurrent).
**Setup:** Standing setup, `tick: 100ms`. A client loop registering and dropping 200 distinct
queries over `ev` while 20 others run.
**Steps:** Run for 5 minutes. `grep -c 'could not advance the watermark' server.log`. Record the
exception classes seen and the maximum gap between successive window emissions on a steady query.
**Expected:** At least one `ConcurrentModificationException`, caught and logged. Zero is a pass and
must be reported with the registration count that produced it. Record whether any metric would have
told an operator: predicted no — there is no counter for swallowed advances.
**Vacuity:** A run with no concurrent registrations must produce zero such lines.

## TIME-080 — Idleness is not observable from outside the JVM [E2E]
**Intent:** `isIdle`, `idleExclusions` and `regressions` exist on the tracker and nothing exposes
them. An operator diagnosing TIME-072's stall has: the row count, an empty view, and a `NaN` gauge.
**Falsifier:** Any surface reporting a partition's idle state, the exclusion count or the regression
count.
**Setup:** TIME-072's stalled query.
**Steps:** Search every shipped surface: `pravaha queries`, `pravaha query`, `/api/v1/queries`,
`/api/v1/status`, `/actuator/prometheus`, `/actuator/metrics`, the log at INFO and DEBUG.
**Expected:** Nothing anywhere. Write out the complete list of what an operator *can* see for a
query whose watermark has frozen, and note that it is identical to what they see for a healthy one
except for the view's contents. This is the diagnosis gap that makes every case in this section
present as a hang.
**Vacuity:** The same search finds `pravaha_query_view_evicted` and six other gauges, so the search
method works.

---

## Tick

Three tick values against windows both smaller and larger than each. The tick bounds how late a
window can be relative to its own event time, and — because the same thread does both — how often
idleness could in principle be noticed.

The six combinations are `tick ∈ {100ms, 1s, 5m}` × `window {smaller, larger}`. Note the constraint
that couples this to the previous section: `tick <= idle-after` is required (fact 4), so the 5m rows
need `idle-after` between 5m and 10m.

## TIME-081 — tick 100ms, window 50ms (window smaller than the tick) [E2E]
**Intent:** A window finer than the clock that fires it. Every tick crosses two or more window ends,
so windows arrive in batches and the first one's latency is up to a full tick.
**Falsifier:** Windows missing, or a window firing twice.
**Setup:** `tick: 100ms`, `idle-after: 1s`, `ev.out-of-orderness: 0s`; query
`TUMBLE(TABLE ev, DESCRIPTOR(event_time), INTERVAL '0.05' SECOND)`.
**Steps:** Register. If Calcite refuses a fractional-second interval, record the refusal verbatim
and treat "a window smaller than the finest tick cannot be expressed" as the finding, then repeat
with the smallest interval the parser accepts.
**Expected:** `evB.csv` has one row per second, so with 50ms windows each row is alone in its own
window and 2419 of every 2420 windows are empty. Emitted rows = one per non-empty window = **120**
(ids 0…119; id 120's window ends at T0+120.05 > watermark T0+120). `total(k) = k`. Confirm no empty
window is emitted — `windowsCompletedBetween` walks every end in the range, and
`WindowedAggregate.emitWindow` must skip the ones with no state. Record the cost: 2420 window-end
iterations per second of event time, per tick.
**Vacuity:** The 10s window over the same data gives 12 rows; 120 vs 12 cannot be confused.

## TIME-082 — tick 100ms, window 1s (window larger than the tick) [E2E]
**Intent:** The normal relationship, at the fine end. A window's result must appear within one tick
of its end being crossed by the watermark.
**Falsifier:** Latency to first output above one tick plus the 20ms publish interval.
**Setup:** `tick: 100ms`, `idle-after: 1s`, `ev.out-of-orderness: 0s`, `INTERVAL '1' SECOND`.
**Steps:** S1 with the 1s window, and record the delay from each window end being crossed to its row
being readable.
**Expected:** **120** windows (ends T0+1…T0+120), `n = 1`, `total(i) = i−1` for window ending
`T0+i`. Window `[T0+120, T0+121)` does not fire.
**Vacuity:** TIME-081's 50ms window and this 1s window read the same file and differ in count.

## TIME-083 — tick 1s, window 500ms (window smaller than the tick) [E2E]
**Intent:** The default tick against a sub-tick window; two window ends per tick.
**Falsifier:** One of each pair of windows missing.
**Setup:** `tick: 1s`, `idle-after: 30s`, `ev.out-of-orderness: 0s`,
`INTERVAL '0.5' SECOND` (or the parser's nearest legal spelling, recorded).
**Steps:** S1 with the sub-second window; record the parser's response to the interval spelling
before anything else.
**Expected:** 240 window ends in the file's span, 120 of them non-empty (one row per second lands in
the `[T0+k, T0+k+0.5)` half), so **120** rows with `n = 1`. The other 120 are empty and must not be
emitted.
**Vacuity:** TIME-084 over the same data emits 12.

## TIME-084 — tick 1s, window 10s (window larger than the tick) [E2E]
**Intent:** The shipped defaults. This is TIME-030 read as a tick case: with 10 ticks per window,
each window fires on the tick immediately after its end is crossed.
**Falsifier:** Anything but 12 windows, or a first-output latency above ~1.02s after the watermark
crosses T0+10.
**Setup:** Standing setup with `ev.out-of-orderness: 0s`.
**Steps:** S1, plus the latency measurement described in Expected.
**Expected:** 12 rows, 45…1145. Measure and record the delay between a window's end being crossed
and its row being readable: bounded by `tick (1s) + PUBLISH_INTERVAL (20ms)`.
**Vacuity:** TIME-086's 5m tick over the same data must show a delay three hundred times larger.

## TIME-085 — tick 5m, window 1m (window smaller than the tick) [E2E]
**Intent:** The coarse clock. Five windows' worth of event time passes between ticks, so results
arrive in bursts of five and nothing is readable in between — for a query that is ingesting the
whole time.
**Falsifier:** Results appearing between ticks.
**Setup:** `tick: 5m`, `idle-after: 10m` (required by fact 4), `ev.out-of-orderness: 0s`,
`INTERVAL '1' MINUTE`. `evB.csv` spans only 121 seconds, so use `evLong.csv` — 1801 rows at one per
second, ids 0…1800, `amount = id`, event times T0+0…T0+1800.
**Steps:** Register; sample the view every 10s for 20 minutes; record the sample at which each
window first appears.
**Expected:** Windows end at T0+60, T0+120, …, T0+1800 — 30 of them, each `n = 60`, each
`total(i) = Σ_{k=60i-60}^{60i-1} k = 60·(60i−60) + (0+…+59) = 3600i − 3600 + 1770`. Window 1 =
1770, window 2 = 5370, window 30 = 3600·30 − 3600 + 1770 = **106170**. All 30 appear at the tick
after ingestion completes, in one burst, and the view is empty until then. Record the wall-clock
gap between "ROWS IN = 1801" and "first row readable": predicted up to 5 minutes.
**Vacuity:** The same file at `tick: 1s` makes the windows appear progressively; the two sampling
traces are the case.

## TIME-086 — tick 5m, window 10m (window larger than the tick) [E2E]
**Intent:** The coarse clock against a coarser window: the tick is no longer the limiting factor and
latency is bounded by the window instead.
**Falsifier:** Output arriving more than one tick after a window's end is crossed.
**Setup:** `tick: 5m`, `idle-after: 10m`, `INTERVAL '10' MINUTE`, and `evVeryLong.csv` — 7201 rows,
one per second, ids 0…7200, event times T0+0…T0+7200 (two hours).
**Steps:** S1 over `evVeryLong.csv`, sampling the view every 30s for 20 minutes.
**Expected:** 12 windows, each `n = 600`, `total(i) = 600·(600i−600) + (0+…+599) =
360000i − 360000 + 179700`. Window 1 = 179700; window 12 = 360000·12 − 360000 + 179700 =
**4139700**. Ingestion of 7201 rows from a file completes in well under a tick, so all 12 fire on
the first or second tick.
**Vacuity:** TIME-085's 30 windows of 60 rows and this case's 12 windows of 600 come from the same
generator with two parameters changed.

## TIME-087 — tick 0 becomes 1ms [E2E]
**Intent:** `long period = Math.max(1, tick.toMillis())` (`QueryExecution.java:353`). A zero tick
does not fail and does not busy-spin at zero delay; it becomes a one-millisecond scheduled delay —
a thousand passes over every lane per second, on a daemon thread, for ever.
**Falsifier:** Startup refusal, a zero-delay spin, or a 1s fallback.
**Setup:** `tick: 0s`, `idle-after: 1s`.
**Steps:** Start; register `Q10`; measure the `pravaha-watermark` thread's CPU time over 60s with
`jcmd <pid> Thread.print` sampling or `/proc/<pid>/task/*/stat`.
**Expected:** Starts (0s is not greater than 1s, so the `tick > idleAfter` check passes). Windows
fire correctly — 11 rows. The watermark thread runs ~1000 times a second; record its CPU share on an
otherwise idle node. Judge whether a zero tick should be refused; predicted finding: it should, and
`Math.max(1, …)` is a clamp of exactly the kind `WatermarkTracker` refuses to make for `idle-after`.
**Vacuity:** `tick: 1s` on the same node gives one pass per second; the CPU measurement is the
difference.

## TIME-088 — A sub-millisecond tick is floored, silently [E2E]
**Intent:** `tick: 500us` → `toMillis()` = 0 → clamped to 1ms. The operator asked for 2000 ticks a
second and got 1000, with no warning.
**Falsifier:** A refusal, or 2000 ticks a second.
**Setup:** `tick: 500us` (record whether Spring parses `us`/`µs`; if not, `PT0.0005S`).
**Steps:** S1; record the logged `tick=` value and, as in TIME-087, the watermark thread's CPU time
over 60s.
**Expected:** Starts, logs `tick=PT0.0005S` — the *logged* value is the configured one, not the
effective one, so the log actively misreports what the engine is doing. That discrepancy is the
finding; the window results are unaffected (11 rows).
**Vacuity:** TIME-087 and this case differ only in the configured value and produce the same
effective period.

## TIME-089 — A negative tick [E2E]
**Intent:** `Duration.ofSeconds(-1).toMillis()` is −1000, clamped to 1 by the same `Math.max`; and
`tick.compareTo(idleAfter) > 0` is false for a negative, so nothing refuses it.
**Falsifier:** A refusal (better), or a `scheduleWithFixedDelay` failure.
**Setup:** `tick: -1s`, `idle-after: 30s`.
**Steps:** S1; record whether startup, registration or the scheduler complains.
**Expected:** Starts, registrations succeed, the clock runs at 1ms. Record it beside TIME-053
(`idle-after: -5s`, refused): two duration keys in the same block, one bounded and one not.
**Vacuity:** TIME-056 shows the tick *is* validated, but only against `idle-after` — so the
validation exists and does not cover sign.

## TIME-090 — The tick is what makes idleness detectable, and a coarse tick delays it [UNIT]
**Intent:** The javadoc's central claim (`QueryExecution.java:316-319`). Idleness is evaluated only
inside `advance`, which runs only on a tick, so the effective detection delay is
`idle-after + up to one tick`.
**Falsifier:** Detection at exactly `idle-after` with a coarse tick.
**Setup:** [UNIT] tracker with `idle-after = 30s`; drive `advance` at 0, 25s, 50s (a 25s tick).
**Steps:** [UNIT] drive `advance` at 0, 25s and 50s with no observations after 0, asserting
`isIdle("p1")` at each.
**Expected:** `p1` becomes idle only at the `advance(50s)` call — 20 seconds after it qualified.
Assert `isIdle` false at 25s and true at 50s, and note that with `tick = idle-after` (TIME-057) the
worst-case detection delay is 2× `idle-after`.
**Vacuity:** Driving at 1s intervals detects it at 30s exactly.

## TIME-091 — Window-close latency as a function of the tick [E2E]
**Intent:** One measurement across the three ticks, so the operator's question ("how late will my
results be?") has a number rather than an adjective.
**Falsifier:** Latency not scaling with the tick.
**Setup:** Three runs of TIME-084's query (`Q10`, `out-of-orderness: 0s`) with `tick` = 100ms, 1s,
5m and `idle-after` = 1s, 30s, 10m respectively. Feed `busy` through a FIFO at one row per 100ms so
the watermark advances continuously rather than in one gulp.
**Steps:** For each run record, per window, the wall-clock delay between writing the row whose event
time crosses the window end and that window being readable.
**Expected:** Mean delay ≈ `tick/2 + 20ms` and maximum ≈ `tick + 20ms`: ~70ms/120ms, ~520ms/1020ms,
~150s/300s. Record the actual figures. The 20ms is `PumpingFeed.PUBLISH_INTERVAL_NANOS` and is
additive, not hidden inside the tick.
**Vacuity:** Three runs, one variable, three separated distributions.

## TIME-092 — A window fires on a tick and becomes readable on a publish [E2E]
**Intent:** Two clocks, not one. `advanceWatermarkQuietly` fires the window into the `ViewSink`; the
sink's committed frontier moves only when `PumpingFeed.publishPeriodically` calls
`RegisteredQuery.commit` (`PumpingFeed.java:117`, `PluginSourceFeeds.java:127`). A row that has been
computed is not yet a row that can be read — the distinction `MASTER_TEST_CASES.md` insists on.
**Falsifier:** A window becoming readable in the same instant it fires, with no publish in between —
or never becoming readable at all (that is TIME-120).
**Setup:** `tick: 5m`, `idle-after: 10m`, `INTERVAL '1' MINUTE`, feeding through a FIFO so the feed
thread stays alive and publishing.
**Steps:** Poll the view every 5ms around the moment of a tick; record the first poll that sees the
new window.
**Expected:** The window appears between 0 and 20ms after the tick, never before it. Record the
distribution. With the feed thread alive the publish is what makes it visible, and the gap is
bounded by `PUBLISH_INTERVAL_NANOS`.
**Vacuity:** TIME-120 removes the feed and the window never becomes readable at all.

---

## Lateness

Allowed lateness is **0** for every query the SQL planner builds (fact 11), so the three outcomes
design section 15.4 describes — on time, late-but-correctable, too late — collapse to two for
`TUMBLE`, and the third is reachable only through overlapping windows. That collapse is itself the
subject of several cases.

## TIME-093 — An on-time row lands in its window [E2E]
**Intent:** The baseline the other lateness cases are measured against, with the row identified
individually rather than as part of a total.
**Falsifier:** The row's amount missing from its window's total.
**Setup:** Standing setup, `ev.out-of-orderness: 10s`; `evB.csv` fed in order through a FIFO at one
row per 50ms so the watermark trails the data.
**Steps:** Register `Q10`; read window `[T0+80, T0+90)`.
**Expected:** `n = 10`, `total = 80+81+…+89 = 845`. Row 85 is inside it and on time: the watermark
when row 85 arrives is at most `T0+85 − 10 = T0+75`, well below the window's end of T0+90.
**Vacuity:** TIME-095 removes exactly one row from exactly this window by making it late.

## TIME-094 — "Late but within the allowance" does not exist for TUMBLE [E2E]
**Intent:** Design section 15.4 and `WindowedAggregate.java:52-58` describe three outcomes. With
`allowedLatenessNanos = 0` and a tumbling window, `lastWindowEndFor(windowStart)` is the window's own
end, so the row is droppable the instant the window becomes fireable: the middle outcome has zero
width. Prove it rather than infer it.
**Falsifier:** A `TUMBLE` window ever being re-emitted with a correction. That would mean the middle
band exists.
**Setup:** `Q10`, `out-of-orderness: 0s`, FIFO-paced feed. After row T0+100 has been written and one
tick has passed, write `902,u0,500,T0+95`.
**Steps:** Read the view before and after; watch for a retraction on `pravaha subscribe`.
**Expected:** Window `[T0+90, T0+100)` holds `n = 10, total = 945` before and **945** after. No
retraction, no correction, no second emission. The 500 is gone. `QueryExecution.lateRecords()`
is 1 and is visible nowhere (fact 12).
**Vacuity:** TIME-096 performs the same injection against a HOP window and *does* get a correction,
using the same harness and the same row.

## TIME-095 — Late past the allowance on TUMBLE: dropped, counted, unobservable [E2E]
**Intent:** The brief's question — dropped or corrected, and is it observable? Answer both with
numbers.
**Falsifier:** The row appearing in any output, or any surface reporting the drop.
**Setup:** As TIME-094; inject `903,u0,700,T0+85` after the watermark has passed T0+90.
**Steps:** Read window `[T0+80, T0+90)`. Then search every surface for a late-record count:
`pravaha queries`, `/api/v1/queries`, `/actuator/prometheus`, the log.
**Expected:** Total stays **845**, not `845 + 700 = 1545`. `ROWS IN` counts the row (it reached the
engine). No metric, no field, no log line mentions it. The only counter that exists —
`WindowedAggregate.lateRecords` → `InterpretedPipeline.lateRecords()` →
`QueryExecution.lateRecords()` — stops there. Record the full chain and where it ends: that is the
gap to file.
**Vacuity:** The same row injected before the watermark passes T0+90 gives 1545, in the same run.

## TIME-096 — Late within the correctable band on HOP: retract and re-emit [E2E]
**Intent:** The third outcome, reachable because a HOP row belongs to several windows and
`lastWindowEndFor` is the *last* of them (`SlicedWindows.java:129-138`). A row can therefore be too
late for one window it belongs to and in time for another, and the fired one is marked `dirty` and
re-emitted (`WindowedAggregate.java:175-185, 200-205`).
**Falsifier:** No correction, or a correction without a retraction (a `+1` with no matching `−1`).
**Setup:** `HOP(TABLE ev, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '20' SECOND)` —
slide 10s, size 20s. `out-of-orderness: 0s`, FIFO-paced. Subscribe before injecting.
**Steps:** Feed T0+0…T0+105 in order. Wait one tick (watermark T0+105). The window ending T0+100
has fired; the window ending T0+110 has not. Inject `904,u0,500,T0+95`.
**Expected:** Hand-computed: window `[T0+80, T0+100)` = ids 80…99, `n = 20`,
`total = (80+99)·20/2 = 1790`. `windowEndsContaining(T0+95)` = {T0+100, T0+110};
`lastWindowEndFor(T0+90)` = T0+110 > watermark T0+105, so the row is **accepted**. T0+100 is in
`emitted`, so it is marked dirty and re-emitted on the next tick as
`(window_start T0+80, n 21, total 2290)` preceded by a retraction of `(T0+80, 20, 1790)`:
`1790 + 500 = 2290`, `20 + 1 = 21`. The subscription must show weight `−1` then `+1`;
`corrections()` becomes 1.
**Vacuity:** TIME-094 injects an equivalent row into a TUMBLE and gets nothing. The two cases differ
only in the window function.

## TIME-097 — Too late even for HOP: past every window it belongs to [E2E]
**Intent:** The far side of TIME-096's band. Once the watermark passes `lastWindowEndFor`, the same
row is dropped.
**Falsifier:** A correction of a window whose slices have been released.
**Setup:** As TIME-096; inject `905,u0,500,T0+95` after the watermark has reached T0+115.
**Steps:** As TIME-096, with the injection delayed until the watermark has reached T0+115; subscribe
throughout and record every change delivered.
**Expected:** No correction. Windows ending T0+100 (1790) and T0+110 (ids 90…109,
`(90+109)·20/2 = 1990`) are both unchanged. `lateRecords` 1. Note that
`state.discardSlicesEndingBefore(watermark, 0)` has already released the slice, so the correction is
not merely refused — it is impossible, which is what the code comment at
`WindowedAggregate.java:145-146` says.
**Vacuity:** TIME-096, same row, 10 seconds of watermark earlier, produces the correction.

## TIME-098 — One row far in the future drags the watermark with it [E2E]
**Intent:** `boundedOutOfOrderness` takes the **maximum** event time seen; there is no outlier
rejection anywhere. A single corrupt timestamp closes every open window in the query and makes every
subsequent legitimate row late.
**Falsifier:** The watermark ignoring the outlier, or the subsequent rows still landing.
**Setup:** `out-of-orderness: 10s`, FIFO-paced at one row per 50ms. Feed T0+0…T0+59, then inject
`906,u0,1,T0+86400` (one day ahead), then continue T0+60…T0+120.
**Steps:** Sample the view after the injection and again at the end.
**Expected:** Immediately after the injection the watermark is `T0+86400 − 10 = T0+86390`, so
**every** window up to that point fires: windows ending T0+10…T0+60 with their true totals
(45, 145, 245, 345, 445, 545) plus the window holding the outlier
(`[T0+86400, T0+86410)`, `n = 1`, `total = 1`) — and every window in between is empty and must not
be emitted. Every subsequent row (T0+60…T0+120) is then dropped as too late:
`lastWindowEndFor` for all of them is ≤ T0+130 ≪ T0+86390. Final view: **7** rows. 61 of the 122
rows fed are silently discarded.
**Vacuity:** The same feed without the injected row gives 11 windows and drops nothing.

## TIME-099 — One row far in the past is dropped [E2E]
**Intent:** The mirror of TIME-098, and the one with no effect on the clock: the maximum is
unchanged, so the only consequence is the row itself.
**Falsifier:** The watermark regressing, or the row landing in a 1970 window that then fires.
**Setup:** As TIME-098 but inject `907,u0,1,0` (the epoch) after T0+59.
**Steps:** As TIME-098, with the past row in place of the future one; sample the view after the
injection and at the end.
**Expected:** Watermark unchanged (`observe` keeps the maximum). The row's window is
`[0, 10·10^9)` in 1970, whose `lastWindowEndFor` is 10^10 ns, far below the watermark of ≈T0+49, so
it is dropped immediately. Final view: the normal **11** windows, 45…1045. `lateRecords` 1.
The remaining rows are unaffected — the contrast with TIME-098 is the whole point: a timestamp
wrong in one direction costs one row, wrong in the other it costs the query.
**Vacuity:** TIME-098 in the same harness loses 61 rows.

## TIME-100 — Non-monotonic timestamps within one partition [E2E]
**Intent:** The ordinary case out-of-orderness exists for: a source that jitters by a few seconds
without being wrong.
**Falsifier:** Any row lost while the jitter stays inside the declared tolerance.
**Setup:** `evJitter.csv` — `evB.csv` with each event time shifted by a deterministic
`±(k mod 7)` seconds, ids and amounts unchanged, so every row is within 6s of its true position.
`ev.out-of-orderness: 10s`.
**Steps:** Register `Q10`; compare with TIME-001's totals.
**Expected:** Totals differ from 45…1045 because rows move between windows — compute the expected
totals from the shifted file directly and assert them exactly; the invariant to check is
`Σ total` over all emitted windows plus the still-open tail = `Σ_{k=0}^{120} k = 7260`. No row is
dropped: the maximum jitter (6s) is inside the tolerance (10s).
**Vacuity:** With `out-of-orderness: 2s` the same file drops rows and `Σ total` falls short of 7260
by exactly the dropped amounts. Run both.

## TIME-101 — The documentation says corrections; the code says drops [DOCX-adjacent]
**Intent:** `CONCEPTS.md:71-73` — "A row arriving after that is still applied — as a retraction and
a correction — which is what the weights are for." `StreamSchema.java:105-108` says the same. For a
`TUMBLE` query built by the SQL planner this is false (fact 11 and TIME-094), and the `late output`
seam it would arrive through is wired to nothing (fact 12). Pin the disagreement.
**Falsifier:** A TUMBLE correction being produced, which would make the documentation right.
**Setup:** TIME-094's result and TIME-095's search.
**Steps:** Quote both documents, quote `PhysicalPlanBuilder.java:858` and
`WindowedAggregate.java:144-149`, and record the observed behaviour.
**Expected:** The claim is true for HOP inside a bounded band (TIME-096) and false for TUMBLE
always. The documentation states it unconditionally. File against the documentation, not the code —
the code's behaviour is defensible, its description is not.
**Vacuity:** TIME-096 proves the mechanism exists, so this is a scoping error rather than a missing
feature.

## TIME-102 — There is no allowed-lateness setting to reach [E2E]
**Intent:** `WindowedAggregateOperator` takes `allowedLatenessNanos`, `SlicedAggregateState` honours
it, `ChangelogAnalysis` reasons about it (`ChangelogAnalysis.java:78-81`) — and the only construction
site passes the constant 0. Search every surface for a way to set it.
**Falsifier:** Any config key, SQL clause or API parameter that changes it.
**Setup:** Full-text search of `application.yaml`, `docs/`, the CLI's flags, the REST request DTOs
and the SQL grammar.
**Steps:** `grep -rn "allowed.lateness\|allowedLateness" --include=*.java --include=*.yaml
--include=*.md .`, then read the CLI flags, the REST DTOs and the SQL grammar for anything that
reaches `WindowedAggregateOperator`'s last argument.
**Expected:** None. Record that the engine has a working late-data correction path and no way to
turn it on, and that `ChangelogAnalysis` contains a branch (`allowedLatenessNanos() > 0`) that is
therefore dead. This is the missing key that makes TIME-094 through TIME-101 unavoidable.
**Vacuity:** The same search finds `pravaha.watermark.idle-after`, so the method works.

## TIME-103 — A row behind the watermark but before its window's end is accepted [E2E]
**Intent:** The distinction `StreamSchema.java:100-108` draws: out-of-orderness decides when a
window is *complete*, not what happens to a row. A row that is behind the watermark but whose window
has not yet closed is perfectly ordinary data.
**Falsifier:** Such a row being treated as late.
**Setup:** `out-of-orderness: 10s`, FIFO-paced. Feed up to T0+105 (watermark T0+95), then inject
`908,u0,500,T0+92`.
**Steps:** Feed to T0+105, inject, then let the feed continue to T0+120 so the window fires; read
window `[T0+90, T0+100)`.
**Expected:** The row's window is `[T0+90, T0+100)`, whose end T0+100 > watermark T0+95, so it is
accepted. When the window later fires: `n = 11`, `total = 945 + 500 = 1445`. No retraction is
needed, because the window had not fired yet.
**Vacuity:** TIME-095 injects into a window that *has* fired and loses the row.

## TIME-104 — `Long.MIN_VALUE` and `Long.MAX_VALUE` as event times [UNIT + E2E]
**Intent:** `Long.MIN_VALUE` is the `NOT_YET` sentinel (`WatermarkGenerator.java:26`) and also a
legal `TIMESTAMP` value and also the initial value of `partitionHighWater`
(`QueryExecution.java:296`). A row carrying it is invisible to the clock in three places at once.
**Falsifier:** A partition whose only row is at `Long.MIN_VALUE` producing a watermark.
**Setup:** [UNIT] `boundedOutOfOrderness(0)`, `observe(Long.MIN_VALUE)`, read `watermark()`.
[E2E] a one-row file with `event_time = -9223372036854775808`.
**Steps:** [UNIT] observe each sentinel value and assert `watermark()`. [E2E] S1 over the one-row
file, then wait 120s and read the view again.
**Expected:** [UNIT] `maxSeen` is assigned `Long.MIN_VALUE` and `watermark()` returns `NOT_YET` —
the row is indistinguishable from no row at all, so the partition holds the whole lane back
(TIME-066) and is eventually excluded as never-active. [E2E] `ROWS IN` = 1, view empty for ever, no
warning. Then `Long.MAX_VALUE`: `watermark()` = `MAX_VALUE − d`, which fires every window the query
has ever opened and makes every later row too late — TIME-098 at the limit. Record both.
**Vacuity:** A row at T0 in the same harness produces a finite watermark.

---

## Ordering

Six arrangements of the same 121 rows. The totals are invariant under permutation only when nothing
is dropped, which is what makes this section a drop detector: `Σ total` over all emitted windows,
plus whatever is still open, must be `Σ_{k=0}^{120} k = 7260` in every case where the declared
tolerance covers the disorder.

## TIME-105 — In order [E2E]
**Intent:** The control. `evB.csv` as written.
**Falsifier:** Anything but TIME-001's result.
**Setup:** Standing setup, `evB.csv` as written, `ev.out-of-orderness: 10s`.
**Steps:** S1.
**Expected:** 11 windows, 45…1045, `Σ total = 45+145+…+1045 = (45+1045)·11/2 = 5995`; the missing
`7260 − 5995 = 1265` is windows 12 (1145) and 13 (120), still open. Both numbers are asserted.
**Vacuity:** The arithmetic ties every ordering case below to the same total.

## TIME-106 — Exactly reversed [E2E]
**Intent:** The worst case for a bounded-out-of-orderness watermark: the **first** row carries the
**highest** event time, so the watermark is at its final value before any other row arrives, and
every subsequent row is behind it.
**Falsifier:** The reversed file producing the same output as the in-order one.
**Setup:** `evRev.csv`, `out-of-orderness: 10s`, fed through a FIFO at one row per 50ms so ticks
interleave with rows.
**Steps:** S1 over `evRev.csv` through the FIFO, sampling the view every second and recording the
highest `window_end` ever present.
**Expected:** Row 1 is `120,…,T0+120` → watermark T0+110 on the first tick. Windows ending
T0+10…T0+110 fire **empty** (they hold no state yet, so nothing is emitted) and are released. Every
row from T0+109 downward then has `lastWindowEndFor <= T0+110` and is dropped. Only rows whose
window end exceeds T0+110 survive: ids 110…120, in windows `[T0+110, T0+120)` (ids 110…119,
`total = (110+119)·10/2 = 1145`) and `[T0+120, T0+130)` (id 120). The first fires when the watermark
is still T0+110 — it does not, `end 120 > 110` — so the final view is **empty** and `Σ total = 0`
against an input of 7260. 121 rows in, 0 out, and `ROWS IN` says 121.
**Vacuity:** The same 121 rows in the other order give 5995. The permutation is the only difference.

## TIME-107 — Shuffled [E2E]
**Intent:** The realistic disorder. With a fixed seed the expected answer is computable in advance,
and it is not "the same as in order".
**Falsifier:** Results that vary between two runs of the same seed.
**Setup:** `evShuf.csv`, seed recorded in the log, `out-of-orderness: 10s`, FIFO-paced at 50ms.
**Steps:** Run twice. Compare row for row.
**Expected:** Deterministic between runs given the same pacing (record whether it is; if not, that
non-determinism is itself the finding, since the engine's whole premise is that a replay reproduces
the answer). Compute the expected drops directly from the shuffled sequence: a row is dropped iff
the running maximum event time at its arrival exceeds `its window end + d`. Assert the exact set of
dropped ids and the resulting per-window totals, and check `Σ total + Σ dropped + Σ open = 7260`.
**Vacuity:** Sorting the same file restores 5995.

## TIME-108 — Duplicate timestamps [E2E]
**Intent:** Many rows sharing one event time is normal, not an error. The generator takes a maximum,
so duplicates neither advance nor hold back the clock.
**Falsifier:** Rows lost, or windows firing differently from the in-order case.
**Setup:** `evDup.csv` — every event time floored to a multiple of 10s, so exactly ten rows share
each of T0+0, T0+10, …, T0+120. Ids and amounts unchanged. `out-of-orderness: 0s`.
**Steps:** S1 over `evDup.csv`.
**Expected:** Window `[T0+10i−10, T0+10i)` now holds the ten rows stamped `T0+10i−10`, so
`n = 10` and the totals are unchanged — each window still holds ids 10(i−1)…10i−1. 12 windows,
45…1145. Row 120 alone is stamped T0+120 and stays in the open window.
**Vacuity:** `Σ total = 45+145+…+1145 = (45+1145)·12/2 = 7140`, and `7260 − 7140 = 120` is exactly
the open row. The identity is the assertion.

## TIME-109 — All timestamps identical [E2E]
**Intent:** The degenerate clock. Every row at T0+60 means the watermark reaches T0+60 on the first
tick and never moves again, so exactly one window can ever fire — and it cannot, because its own end
is T0+70.
**Falsifier:** Any window firing.
**Setup:** `evSame.csv` — all 121 rows at T0+60, amounts unchanged. `out-of-orderness: 0s`,
`idle-after: 1s`, `tick: 100ms`. Wait 300s.
**Steps:** S1 over `evSame.csv`; wait 300s; read the view; then append a row at T0+80 and read it
again.
**Expected:** Watermark T0+60, constant. The only populated window is `[T0+60, T0+70)` with
`n = 121` and `total = 7260`, and it never fires because `T0+70 > T0+60`. View empty for ever;
`ROWS IN` = 121. This is TIME-075 with the whole file in one window, and it is the cleanest possible
statement of "there is no end-of-stream watermark".
**Vacuity:** Appending one row at T0+80 releases the window with `n = 121, total = 7260` — assert
that, so the case proves the state was there.

## TIME-110 — Two partitions interleaved out of order with each other [UNIT]
**Intent:** Per-partition generation is the design's central claim
(`WatermarkGenerator.java:24-28`): combining first and generating afterwards "would take the worst
behaviour of any partition and impose it on all of them". Prove the per-partition property.
**Falsifier:** One partition's disorder affecting the other's watermark.
**Setup:** [UNIT] `p0` strictly ascending T0+0, T0+10, T0+20 with `d = 0`; `p1` wildly out of order
T0+100, T0+5, T0+50 with `d = 60s`. Observe them alternately.
**Steps:** [UNIT] observe the six values in the stated order, asserting each generator's
`watermark()` and the tracker's `advance` after the last one.
**Expected:** After all six observations: `p0.watermark() = T0+20` (ascending, no allowance);
`p1.watermark() = T0+100 − 60 = T0+40` (maximum seen, minus its own tolerance). Lane watermark
`min(T0+20, T0+40) = T0+20`. `p1`'s disorder costs `p1` forty seconds and costs `p0` nothing; the
lane is bounded by the slower, which here is the *tidier* one. Assert all three numbers.
**Vacuity:** A single-generator implementation would give one number for both partitions, and the
lane value would be the same as whichever was computed — the three-number assertion is what
distinguishes them.

---

## Clock health

## TIME-111 — The watermark thread exists, is named, and is a daemon [E2E]
**Intent:** One thread per `QueryExecution` (`QueryExecution.java:348-352`), which is one per
registered *computation*. Round 1 counted nine `pravaha-watermark` threads for nine queries
(`docs/project/qa/logs/DEPLOY.md:1748`). Establish the count, the name and the daemon flag, because every
other case in this section depends on being able to find the thread.
**Falsifier:** No such thread on a node with registered queries; a non-daemon thread; or a count
that does not match the number of distinct fingerprints.
**Setup:** Standing setup; register five queries, two of which share a fingerprint (identical SQL,
different names).
**Steps:** `jcmd <pid> Thread.print | grep pravaha-watermark`.
**Expected:** **4** threads for 5 registrations (the shared fingerprint has one execution), each
daemon, each named exactly `pravaha-watermark` — note they are indistinguishable from one another in
a thread dump, which is a diagnosis problem worth recording. Dropping a query must remove its
thread: `close()` calls `watermarkClock.shutdownNow()` before stopping the lanes
(`QueryExecution.java:766-769`).
**Vacuity:** A node with no registrations has none of these threads.

## TIME-112 — An `Error` on the watermark thread cancels the clock silently [UNIT]
**Intent:** `advanceWatermarkQuietly` catches `RuntimeException`
(`QueryExecution.java:424`). `ScheduledExecutorService.scheduleWithFixedDelay` **cancels the task**
if it throws anything at all, and an `Error` — `OutOfMemoryError`, `StackOverflowError`, an
`ExceptionInInitializerError` from a lazily-loaded class — is not a `RuntimeException`. The clock
stops, the future holds the throwable, nobody reads the future, and the query reports RUNNING.
**Falsifier:** The clock continuing to tick after an `Error`, or the query failing.
**Setup:** [UNIT] A `QueryExecution` whose pipeline's `advanceWatermark` throws `new
StackOverflowError()` on the third tick.
**Steps:** Drive four ticks' worth of real time; read `watermarkNanos()` before and after; assert
the executor's task is cancelled.
**Expected:** Ticks 1 and 2 advance the watermark; tick 3 throws; tick 4 never runs. `watermarkNanos()`
is frozen at tick 2's value. No log line — the `catch` did not match. `QueryExecution.checkHealth()`
does not consult the watermark task, so nothing reports it. Windows stop closing for ever.
**Vacuity:** With a `RuntimeException` instead, tick 4 runs and the clock recovers (TIME-113). The
two differ only in the throwable's supertype.

## TIME-113 — A swallowed `RuntimeException` costs one tick, and nothing counts it [UNIT + E2E]
**Intent:** The known defect the brief names. `advanceWatermarkQuietly` logs at WARNING and returns;
the next tick tries again. So one occurrence is survivable and a *persistent* one is a stopped clock
that logs once per tick and is reported nowhere (TIME-114).
**Falsifier:** The clock dying after one exception, or the exception escaping.
**Setup:** [UNIT] a pipeline whose `advanceWatermark` throws `IllegalStateException` on tick 3 only.
[E2E] TIME-079's concurrent-registration load, which produces the real thing.
**Steps:** [UNIT] drive four ticks and assert the watermark after each. [E2E] TIME-079's load,
counting WARN lines and correlating each with a missed advance.
**Expected:** [UNIT] ticks 1, 2 advance; tick 3 logs `WARN could not advance the watermark:
java.lang.IllegalStateException: …` and does not advance; tick 4 advances and the watermark catches
up in one step (no event time is lost, because the high-waters are retained). [E2E] the log count
from TIME-079, with no corresponding metric. Record: there is no counter of swallowed advances, no
`lastSuccessfulTick` timestamp, and no health signal.
**Vacuity:** A run with no injected exception shows a monotone tick cadence; the missing tick is
visible only by comparing the two traces.

## TIME-114 — A persistently failing advance: RUNNING for ever, one WARN per tick [E2E]
**Intent:** The shape that matters operationally. A permanent failure inside
`InterpretedPipeline.advanceWatermark` — a closed pipeline, an exhausted arena, a lane that has
already died — produces an unbounded stream of identical WARN lines and a query that reports
RUNNING, has a growing `ROWS IN`, and will never emit again.
**Falsifier:** The query transitioning to FAILED, or the log rate-limiting itself, or any metric
moving.
**Setup:** Standing setup with a query whose lane is killed by an arena exhaustion (round 1's
DEFECT-13 recipe: a windowed aggregate over 50 000 keys), `tick: 100ms`, left running for 10
minutes.
**Steps:** Count `could not advance the watermark` lines; read state, `ROWS IN`, the view and every
gauge at 1-minute intervals.
**Expected:** ≈ 6000 WARN lines in ten minutes (10 per second). State RUNNING throughout. View
frozen. `pravaha_query_running` = 1. `pravaha_query_rows_in` still climbing. The log volume is the
only signal, and it is the kind that gets filtered. Record the exact line so a log alert could be
written, since that is the only mitigation available today.
**Vacuity:** A healthy query in the same process produces zero such lines while its view grows.

## TIME-115 — Shutdown order: the clock stops before the lanes do [E2E + UNIT]
**Intent:** `close()` shuts the watermark clock down first, so a tick cannot arrive at a closed
pipeline (`QueryExecution.java:765-769`) — which would be exactly TIME-114's persistent failure,
manufactured by the shutdown path.
**Falsifier:** Any `could not advance the watermark` line emitted during a drop or a clean shutdown.
**Setup:** Standing setup; 20 queries registered and ingesting; `tick: 10ms` to maximise the race.
**Steps:** Drop all 20 in a tight loop; then stop the node. Grep the log for watermark warnings and
for exceptions naming closed lanes or arenas.
**Expected:** Zero. `shutdownNow()` interrupts the in-flight tick, so assert also that an interrupted
tick does not log a spurious warning (an `InterruptedException` wrapped as a `RuntimeException`
would). Record anything that appears.
**Vacuity:** TIME-114 shows what such a line looks like when it is real, so a clean log here is a
meaningful negative.

---

## Interaction — the other bounds the watermark arms

Windows are the visible consumer of event time. Joins and views are the expensive ones, and both are
driven from the same `advanceWatermark` call (`InterpretedPipeline.java:329-344`).

## TIME-116 — The watermark evicts join state at `watermark − matchWithin` [E2E]
**Intent:** `SymmetricHashJoin.advanceWatermark` computes `horizon = watermark − window` and evicts
both sides below it (`SymmetricHashJoin.java:306-320`). Without a clock this never runs and the join
grows until a ceiling fails the query — the claim in `CONCEPTS.md:84-88` that unbounded state, not
"no output", is the real cost of a stopped watermark.
**Falsifier:** Join state not shrinking as the watermark advances; or rows being evicted before the
horizon.
**Setup:** Two streams `jl`, `jr`, each 121 rows T0+0…T0+120, joined on `id` with
`AND jl.event_time BETWEEN jr.event_time - INTERVAL '5' SECOND AND jr.event_time`. Both
`out-of-orderness: 0s`, `tick: 1s`. Feed `jl` fully, then feed `jr` slowly (one row per second) so
the left side accumulates and is then evicted underneath the arriving right rows.
**Steps:** Sample the emitted pair count and, if reachable, `SymmetricHashJoin.evicted()`; otherwise
infer from the pairs that stop appearing.
**Expected:** With the watermark at `T0+t`, the horizon is `T0+t−5`, so left rows older than that
are gone. A right row arriving at T0+t can only match left rows in `[T0+t−5, T0+t]` — six ids — and
every earlier left row is unmatchable by then. Count the pairs: the first right rows match, and from
the point where the watermark overtakes the left side the match count per right row drops to the
window's width. Assert the exact per-row counts for the first ten right rows and the last ten.
**Vacuity:** The identical setup on a stream with no event-time declaration (so the watermark never
advances) must produce **every** pair and a monotonically growing state — run it and record the
memory difference. That comparison is the case; the pair counts alone could be produced by a broken
join.

## TIME-117 — The watermark drives view retention through the committed frontier [E2E]
**Intent:** `Retention` is event time (`Retention.java:68-77`), the frontier comes from the emitted
rows' event times (`ViewSink.java:229-238`), and a windowed result is stamped with its window end
(`WindowedAggregate.java:299`). So retention is armed by the watermark at one remove, and a query
whose watermark is frozen never evicts anything.
**Falsifier:** Eviction happening on wall-clock time, or not happening as the frontier advances.
**Setup:** A query over `evVeryLong.csv` (two hours of event time, TIME-086) registered with
`Retention.ofAge(10 minutes)` — record how a client actually sets this; if the Flight `register` has
no retention parameter, the default of 24h applies and the case becomes "prove the default is
unreachable within the data's span", which is itself worth recording.
**Steps:** Read `pravaha_query_view_evicted` and the view size as the watermark crosses each
10-minute boundary of event time.
**Expected:** With a 10-minute retention and 1-minute windows, the view holds at most 10 rows once
steady: as the frontier reaches `T0+t`, rows written at a frontier below `T0+t−600s` are removed.
Assert the view size at three sample points and the `evicted` counter's increments (10 per 10
minutes of event time). With the default 24h retention over two hours of data, `evicted` stays **0**
and the view holds all 120 windows — round 1 observed exactly that (`evicted=0` on 1400+ commits).
**Vacuity:** A wall-clock implementation would evict on a replay of old data differently from a live
run; run the same file twice, once with the rows' event times shifted forward by a year, and the
evicted counts must be identical.

## TIME-118 — With no event time, the frontier is a sequence number compared against an event-time horizon [E2E]
**Intent:** `ViewSink.commit` uses `Math.max(eventTime, sequence)` as the frontier
(`ViewSink.java:231,237`). For a feedfile or delta source (event time 0, sequence 1…N) the frontier
is a **row counter**, and `Retention.horizonFor` then subtracts a duration in **nanoseconds** from
it. The two are not the same quantity.
**Falsifier:** The units agreeing, or a refusal.
**Setup:** Stream `ff` (feedfile, event time 0) with 1 000 000 rows and a projection view keyed on a
unique id, registered with the default 24h retention.
**Steps:** Read the view size and `pravaha_query_view_evicted` as the row count climbs.
**Expected:** Frontier after N rows = N. Horizon = `N − 86400·10^9`, hugely negative for any N a
real node will reach, so **nothing is ever evicted** and the view grows to the `maxKeys` ceiling and
then throws `VIEW_TOO_LARGE`. Compute the crossover: retention would begin to bite at
`N = 8.64×10^13` rows. Record it as "retention is inoperative on any source without event time".
**Vacuity:** The same query over `ev` (real event times) evicts as TIME-117 shows.

## TIME-119 — The watermark thread mutates pipeline state the lane thread owns [UNIT + E2E]
**Intent:** The single-writer principle is the engine's stated foundation
(`QueryExecution.java:55-68`: "the cost is that a stateful query's state is partitioned across
lanes"), and restore goes through `lane.submitControlTask` for exactly that reason
(`QueryExecution.java:732`). `advanceWatermarkQuietly` instead calls
`QueryExecution.advanceWatermark` → `pipelines.forEach(InterpretedPipeline::advanceWatermark)`
directly on the `pravaha-watermark` thread, which mutates `WindowedAggregate.watermark`,
`emitted`, `dirty` and the slice map while the lane thread is inside `process(row)` on the same
objects.
**Falsifier:** The advance being marshalled onto the lane (it is not, on this reading), or a lock
protecting the state.
**Setup:** [UNIT] A `QueryExecution` with one lane, `tick: 1ms`, fed 2 000 000 rows across 100 000
windows from the test thread while the clock ticks. [E2E] the same shape on a server, run for 30
minutes with `tick: 10ms`.
**Steps:** Watch for `ConcurrentModificationException`, `ArrayIndexOutOfBoundsException`, arena
corruption, lost windows, and window totals that do not reconcile with a batch recomputation of the
same file.
**Expected:** Any of the above is the finding. A clean run is **not** proof of safety and must be
reported as "not reproduced in N rows over M minutes", with N and M stated — this is a data race,
and the visible-window check is the real assertion: every emitted window's total must equal the
hand-computed one for that file, and `Σ total + Σ open = Σ input`.
**Vacuity:** Feeding the identical file with the clock disabled and calling `advanceWatermark`
manually from the feeding thread must reconcile exactly; the two runs differ only in which thread
advances time.

## TIME-120 — A query with no bound source: windows fire and are never published [E2E]
**Intent:** The last link. `advanceWatermarkQuietly` calls `QueryExecution.advanceWatermark`, which
does **not** commit the `ViewSink` — `RegisteredQuery.advanceWatermark` (which does, and which also
updates the lag gauge) is never called by the clock, and the only other committer is
`PumpingFeed.publishPeriodically`, which exists only when a source is bound
(`PluginSourceFeeds.open` returns `SourceFeed.NONE` for an unbound stream). So a query fed by
`accept()` — a client pushing rows over Flight `DoPut` or the REST ingest path — has a watermark
that fires windows into a sink that is never published.
**Falsifier:** The view showing rows after a push-only ingest and a tick. That would mean something
else commits.
**Setup:** Stream `push` declared under `pravaha.streams` with `event-time: event_time` and **no**
`pravaha.sources` entry. Register `Q10` over it. Push 121 rows through whichever client path reaches
`RegisteredQuery.accept`, with the same timestamps as `evB.csv`.
**Steps:** Wait 60s (60 ticks). Read `ROWS IN`, the view, and
`pravaha_query_watermark_lag_seconds`.
**Expected:** `ROWS IN` = 121. `appliedFrontier` has moved (the sink's writer ran) but
`committedFrontier` has not, so the view holds **0** rows — computed, correct, and unreadable. The
lag gauge is `NaN` because `RegisteredQuery.watermarkNanos` was never written. Confirm by then
binding a source to the same stream and observing the identical rows become visible.
**Vacuity:** The same query with `ev` bound (TIME-001) shows 11 rows in the same process, so the
computation and the read path both work; the missing piece is the commit.

---

## Coverage note

120 cases, the budget exactly. Four remarks on how they are distributed and what a later wave should
expect.

**The quiet-partition section is split deliberately.** Ten of its twenty cases are [UNIT], not
because the tracker is easier to test there but because the engine cannot reach the tracker's
interesting states. `advanceWatermarkQuietly` re-observes every partition's retained high-water on
every tick, and `WatermarkTracker.observe` resets that partition's activity clock — so on a running
server a partition that has produced at least one row can never be excluded for idleness, and the
mechanism `WatermarkTracker`'s class comment calls "mandatory" is reachable only by partitions that
have produced nothing at all. If that reading survives execution, TIME-072 and TIME-074 together are
the most important pair in this file: a partition that produced one row and stopped is *worse off*
than one that never spoke, and round 1's DEFECT-18 is a symptom of it rather than the disease.

**The two `out-of-orderness` keys are separated by a single number.** TIME-026 and TIME-027 set the
identical duration in the identical spelling one level apart in the configuration tree and expect
11 windows and 6. Neither case is worth running alone. Note also that the engine-level key has no
bounds, no reader and no warning, while `idle-after` — the key one line below it in
`application.yaml` — has all three; TIME-045 and TIME-059 show what that difference costs an
operator who writes a unitless number.

**Lateness is thinner than the brief implies, and the reason is a constant.**
`DEFAULT_ALLOWED_LATENESS_NANOS = 0` with no way to change it (TIME-102), so "late but within the
allowance" exists only in the geometry of overlapping windows (TIME-096). Six of the twelve lateness
cases are about what happens to the row instead, and one (TIME-101) is filed against the
documentation rather than the code, because the code's behaviour is defensible and its description
is not.

**What is deliberately not here.** Session windows (`PRV-2020`, no SQL surface) are WIN's. Checkpoint
and restore of `WindowedAggregate.watermark`/`lastFiredWatermark` are STATE's, though TIME-042's
immutability check touches the edge of it. The per-error-code audit of every message quoted above is
ERRC's; this file records message text as evidence and judges only whether it is actionable.
`PartitionedIngestPump` appears only in TIME-077 because the server never calls
`pumpPartitionedInto` — every registered query is compiled onto one lane
(`QueryRegistry.java:528`) and fed through `pumpInto`; the partitioned path is reachable from an
embedder alone, and a later wave that switches lanes on will need this section re-run rather than
re-read.
