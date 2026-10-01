# The STRM and TIME clusters, closed against the code of 2026-09-19

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../../LICENSE`](../../../LICENSE).

> **What this is.** Batch B14's streams-and-event-time slice: the ten open `STRM-` findings and the
> six open `TIME-` findings in [`FINDINGS.md`](FINDINGS.md), each reproduced before it was touched
> and each fix seed-proven. It **does not edit `FINDINGS.md`**, which is the lead's; the verdicts
> here are what the lead applies from.
>
> Its starting point is [`verification-2026-09-19.md`](verification-2026-09-19.md), whose evidence
> for several of these had already moved — `SharedClock` is the scheduler, the tick clamp lives in
> `SharedClock.every`, `GET /api/v1/queries` exists. Each section below says where the mechanism is
> **today**.

One section per finding: verdict, cause, fix, test, seed-proof, commit.

## The sixteen, and where each ended

| Finding | Verdict | Commit |
|---|---|---|
| `STRM-1` | Reproduced, **fixed** — a zero-weight change is no longer delivered | `85c9214` |
| `STRM-4` | Structural half reproduces, **left open, its own batch**. The measured half does not support its attribution and should not be re-filed with it; ADR-026's rationale corrected | `a9f5c20` |
| `STRM-8` | Reproduces, **left open, its own batch**. The three surfaces that promised the opposite corrected | `d6fd7b7` |
| `STRM-10` | Reproduced, **fixed** — the loss count rides on every batch, both SDKs | `a9f5c20` |
| `STRM-12` | Reproduced, **fixed** (all three halves) — `PRV-8018`, `PRV-8019`, and `subscriberCount()` returns to zero | `5721e6c` |
| `STRM-14` | Reproduced, **fixed** — a subscription knows the name it was opened under | `5721e6c` |
| `STRM-15` | Reproduced, **fixed** — the handover is bounded in rows as well as batches | `a9f5c20` |
| `STRM-16` | Reproduced, **fixed** — the preference rides on the ticket, and is refused rather than defaulted | `a9f5c20` |
| `STRM-17` | Reproduced, **fixed** — the refusal names the caller's own word | `5721e6c` |
| `STRM-18` | Confirmed (the case file is wrong, not the code), **fixed in the case file** | `d6fd7b7` |
| `TIME-3` | Reproduced, **fixed** — a unitless lateness is seconds, both keys | `a0a2d9a` |
| `TIME-5` | Reproduced, **fixed** — one bad value is one startup failure | `0da7e8a`, `a0a2d9a` |
| `TIME-6` | Reproduced, **HALF CLOSED and staying so**: the node states the lateness in force per stream (all four causes). The plan-time refusal is backed out and **scheduled as its own batch** — the lead has ruled it correct under the owner's no-leniency rule. Do not close on the startup line alone | `a0a2d9a`, `a4f49aa` |
| `TIME-8` | First clause reproduces and is **fixed**; the other two claims are already false and the entry should be narrowed | `51ba5c1` |
| `TIME-9` | Reproduced, **fixed** — one shape for every event-time refusal | `a0a2d9a` |
| `TIME-11` | Reproduced (the clamp had moved to `SharedClock.every`), **fixed** — refused, not clamped | `0da7e8a`, `a0a2d9a` |

**Thirteen closed, two left open and one half-closed**, each with what it needs written down below. One **new finding** is filed below the `TIME-6` section: four of the five case studies ship a windowed query that can never emit, with a README paragraph explaining the symptom away. Three findings
carried claims that are no longer true and should be narrowed rather than closed whole: `TIME-8`'s
second clause and its `registeredQueries` observation, and `STRM-4`'s measurement.

**Found on the way, and worth a line in another entry.** `TIME-2`'s guard was only ever on one of
the two spellings of a window: Calcite lowers `GROUP BY TUMBLE(...)` through `buildGroupedWindow`,
which had no check at all, so `GROUP BY TUMBLE(other_time, ...)` over a stream declaring
`event_time` cut its windows from a column no watermark tracks. That is `TIME-2`'s
wrong-rather-than-late answer, reachable through the spelling most queries use. Fixed in
`a0a2d9a`; see `TIME-6` below, which also says why that finding's plan-time refusal is the lead's call.

---

## `STRM-1` — a weight-0 change is delivered as a positive change and applies nothing

**Verdict: REPRODUCED, FIXED.**

**Cause.** Two mechanisms, one of them right. `ServedView.applyWeighted` returning on `weight == 0`
is **correct** Z-set arithmetic and not the defect: a row is present while its weights sum positive,
and adding zero moves that sum nowhere, so the view keeps the row it held. The defect is one level
up. `ViewSink.apply` staged the zero-weight row into `pending` like any other, so the change stream
said something had happened that had not; and `ViewChange.isRetraction()` is `weight < 0`, so the
change reported itself as an *insertion*. The consumption model `ViewChange`'s own javadoc and
`CONCEPTS.md` §4 both recommend — "ignore negative weights and overwrite by key" — therefore wrote
the zero-weight row's values into a copy of a view that had not changed. That is the divergence: the
finding's own note that the view and a replaying consumer "agree by accident" holds only for a
consumer that applies weights, and the documented alternative diverges.

**Fix.** `ViewSink.apply` stages nothing for `weight == 0`, so the change stream and the view agree
by construction rather than by both happening to keep the old row. `ViewChange` gains `isInsertion()`
(`weight > 0`) so a consumer has the positive question to ask instead of negating `isRetraction()`,
and its javadoc records why the negation is a trap. `CONCEPTS.md` §4 gains the paragraph.

**Test.** `ViewSinkTest.strm1AZeroWeightChangeIsNotDeliveredAndTheViewKeepsWhatItHeld` — commits
`[u1, 1]` at weight 1, then `[u1, 9]` at weight 0, and asserts the subscriber is told nothing, the
view still reads `1`, and a hand-built zero-weight `ViewChange` answers false to both questions.

**Seed-proof.** `if (weights[i] != 0)` put back to `if (true)`: 12 run, **1 failure** —
`Expecting empty but was: [+0[u1, 9]]`. Restored, 12 run, 0 failures.

**Commit.** `85c9214`.

---

## `TIME-5` — `tick` longer than `idle-after` starts a healthy node on which every registration fails

**Verdict: REPRODUCED, FIXED.**

**Cause.** The rule existed and was in the wrong place. `QueryExecution.generatingWatermarks`
checked `tick.compareTo(idleAfter) > 0` and is called once per registration, so `tick: 5m` with
`idle-after: 30s` started a node that logged both settings as in force, reported `UP`, recovered its
journal and then refused every query. `PravahaNode.start` validated `idle-after` only, and its own
comment states the rule this violated: *"one bad value is one startup failure, rather than at
registration where it is every query failing separately"*.

**Fix.** The bound moved to `WatermarkTracker.requireTick(tick, idleAfter)`, beside the idle
timeout's, so there is one copy of it. `QueryExecution.generatingWatermarks` calls it (an embedder
is still refused) and `PravahaNode.start` calls it too, wrapping it as `PRV-2002` in the same shape
as `idle-after`'s refusal — naming the key, the value and what it would do to a running node.

**Test.** `WatermarkTrackerTest.time5ATickLongerThanTheIdleTimeoutIsRefusedByTheSameCheck` (the
bound, including that the 31 s / 30 s boundary is live) and
`WatermarkSettingsTest.time5ATickLongerThanTheIdleTimeoutRefusesTheNodeRatherThanEveryRegistration`
(the node refuses to start, with the key in the message).

**Seed-proof.** The `requireTick` call removed from `PravahaNode.start`, leaving the bound itself
intact: `WatermarkSettingsTest` 6 run, **2 failures** — `time5ATickLongerThanTheIdleTimeout...` and
`time11ATickTheTimerCannotCount...`, which is exactly the finding (the rule still exists; nothing
applies it at startup). Restored, 6 run, 0 failures.


**Commit.** `0da7e8a` (the bound) and `a0a2d9a` (the node applying it).
---

## `TIME-11` — the watermark tick is clamped where every neighbouring duration is refused

**Verdict: REPRODUCED, FIXED. The evidence had moved** — the clamp is no longer
`QueryExecution.java:367`; W9-3's shared clock carried it to `SharedClock.every:83`, which was
`Math.max(1L, period.toMillis())` and served the checkpointer as well as the watermark.

**Cause.** A clamp where the rest of the engine refuses. `0s`, `PT0.0005S` and `-1s` were all
accepted and all became one millisecond, and the negative slipped through because
`tick.compareTo(idleAfter) > 0` is false for a negative and nothing else looked at the sign. The log
line printed the configured duration, so the only surface that mentions the tick actively
misreported the engine.

**Fix.** Three layers, no second copy of the bound. `WatermarkTracker.MINIMUM_TICK` is one
millisecond — the floor the timer can actually count — and `requireTick` refuses a non-period and a
sub-millisecond period with a sentence each. `PravahaNode.start` applies it as `PRV-2002`, so a
configured node fails once instead of running a clock nobody asked for. `SharedClock.every` refuses
rather than clamps, which covers the checkpointer and any future caller. The log line is now the
effective settings **by construction**: nothing between it and the clock can change either value,
because a value the engine would have had to change was refused.

**Test.** `WatermarkTrackerTest.time11ATickThisTimerCannotCountIsRefusedRatherThanClamped`,
`SharedClockTest.time11APeriodThisTimerCannotCountIsRefusedRatherThanClampedToAMillisecond`,
`WatermarkSettingsTest.time11ATickTheTimerCannotCountRefusesTheNodeRatherThanBecomingAMillisecond`.

**Seed-proof.** `SharedClock.every`'s refusal restored to `Math.max(1L, period.toMillis())` and
`requireTick`'s non-period and minimum checks deleted, leaving the ordering check: **3 failures**,
one in each of `WatermarkTrackerTest` (13 run), `SharedClockTest` (2 run) and
`WatermarkSettingsTest` (6 run). Restored, all green.


**Commit.** `0da7e8a` and `a0a2d9a`.
---

## `TIME-3` — `out-of-orderness` has no unit bound, so `60` is sixty milliseconds

**Verdict: REPRODUCED, FIXED.**

**Cause.** Spring's relaxed binding reads a unitless number into a `Duration` as milliseconds unless
a `@DurationUnit` says otherwise, and `StreamDeclarationProperties.Declaration.outOfOrderness`
carried none. `60` was sixty milliseconds, gave 11 windows and a last total of 1045 — the same two
numbers the ten-second default gives, so the misconfiguration was invisible at the only surface that
could show it. Unlike `idle-after`, **no bound can catch it**: sixty milliseconds is a legitimate
out-of-orderness.

**Fix.** `@DurationUnit(SECONDS)` on the field, so a unitless number means the unit lateness is
discussed in, and every explicit spelling (`60s`, `PT1M`, `60ms`) is unchanged. Its twin one key
over, `allowed-lateness`, had the same defect — `30` bound as thirty milliseconds, indistinguishable
from the zero default — and gets the same annotation. The other half is `TIME-6`'s startup line: a
setting nothing states cannot be checked.

**Test.** `WatermarkSettingsTest.time3AUnitlessOutOfOrdernessIsSecondsRatherThanMilliseconds` —
`60` → `PT1M`, `60ms` → `PT0.06S`, `PT1M` → `PT1M`, and `allowed-lateness: 30` → `PT30S`.

**Seed-proof.** Both `@DurationUnit` annotations removed: `WatermarkSettingsTest` 6 run,
**1 failure** — `[a unitless number means the unit lateness is discussed in] expected PT1M`.
Restored, 6 run, 0 failures.


**Commit.** `a0a2d9a`.
---

## `TIME-6` — a windowed query that can never emit is indistinguishable from one that is working

**Verdict: REPRODUCED. HALF CLOSED, and it stays half closed.** The diagnosability half is fixed
here — the node states the lateness in force per stream, which covers all four causes. The
plan-time refusal was written, measured and backed out, the decision was handed up, and **the lead
has ruled that the refusal is correct and goes in as its own batch**, under the owner's standing
rule of **no leniency**: "leniencies create silent and hard to find bugs". A windowed query over a
stream with no declared event time ingests everything and serves nothing, for ever, under a success
status, which is that defect class exactly. **Do not close this finding on the strength of the
startup line.**

**Cause.** Four configurations each produced `state=RUNNING`, a climbing `ROWS IN`, an empty view, a
`NaN` lag gauge and not one log line, and nothing on any surface told them apart from a query that
was working. Two are a stream with no usable event-time declaration; two are a lateness larger than
the data's span.

**Fixed: the node states what is in force.** `PravahaNode.registerDeclaredStreams` logs one line
per stream — `stream txn: event-time=event_time, out-of-orderness=PT10M, allowed-lateness=PT30S`,
and `event-time=none -- no window over this stream can ever close` for a stream with none — read
from the schema the node built rather than from the file, so a default shows as the default. This
is the remedy the finding names first and it covers **all four** causes, where a refusal covers
two: an out-of-orderness larger than the data's span is a legitimate setting that simply empties
the view, and nothing can refuse it. Before this, `grep -icE "out-of-orderness"` over a whole
startup log was 0 on every configuration tried.

**Written, measured and backed out: the plan-time refusal.** The finding's second remedy is one
line — `PhysicalPlanBuilder.requireDeclaredEventTime` returning early when the stream declares no
event time, made to refuse with `PRV-2002` for an unbounded input. It works, and it is right on the
merits: no watermark advances over such a stream, so no window the query opens can ever close.

It also **fails 54 tests in `pravaha-it`**. Most are fixtures carrying a timestamp column without
declaring it, which is a day's mechanical work. Several are **case studies whose SQL would
therefore never emit on a real node** — a genuine find, and one worth filing. And two assert the
current behaviour *by name*:

* `WindowAnswerTest.win005_tumbleOverAStreamWithNoDeclaredEventTimeNeverFiresRatherThanRefusing`
* `EventTimeTest.time002And009_aStreamWithNoDeclaredEventTimeIngestsEverythingAndServesNothing`

Those are QA cases with outcomes recorded against them, and `requireDeclaredEventTime`'s own
javadoc said the case was left alone on purpose, owned by `TIME-002`/`TIME-003`. Reversing a
recorded decision and rewriting two documented case outcomes is not something to do inside a
findings batch, so it went up.

**The lead's answer, recorded here so the next person does not re-litigate it: the refusal is
correct and is scheduled as its own batch.** None of the 54 is an argument against it — the
fixtures declaring their event time *is* the fix, not a workaround, and the two cases get rewritten
outcomes with the reason. What that batch does, in order:

1. `PhysicalPlanBuilder.requireDeclaredEventTime` refuses `declared.isEmpty()` for an unbounded
   input — the diff is in this batch's history, at `a0a2d9a`, and was reverted by `a4f49aa`, so it
   can be lifted rather than rewritten.
2. The `pravaha-it` fixtures declare their event time (54 tests, one shared schema constant in
   several of them).
3. The case studies — see the finding filed immediately below, which is the same defect reaching
   users rather than tests.
4. New outcomes for `WindowAnswerTest.win005` and `EventTimeTest.time002And009`, each saying why
   the old one was recorded and what replaced it.

The javadoc on `requireDeclaredEventTime` records all of that where the next person will look, so
the early return there is a decision with a date on it rather than an oversight.

**Kept, and found on the way.** Three things came out of writing the refusal, and all three are
unambiguous improvements that cost nothing:

* **`TIME-2`'s guard was only ever on one of the two spellings.** Calcite lowers
  `GROUP BY TUMBLE(...)` through `buildGroupedWindow`, which had no check at all, so
  `GROUP BY TUMBLE(other_time, ...)` over a stream declaring `event_time` cut its windows from a
  column no watermark tracks — `TIME-2`'s wrong-rather-than-late answer, through the spelling most
  queries use. Both spellings now answer the same. **Worth a line in `TIME-2`'s entry.**
* The check ran *before* the `switch` that refuses `SESSION`, so a SESSION query whose second
  `DESCRIPTOR` names the partitioning column was refused for its event time and never reached the
  sentence saying SESSION is not wired to SQL. It now runs after.
* And the refusal names the **source stream** rather than the derived schema, so a message telling
  an operator to set `pravaha.streams.ev_projected.event-time` — a stream that does not exist —
  cannot happen.

**Test.** `WatermarkSettingsTest.time6TheLatenessInForceIsStatedPerStreamAtStartup` (the startup
line, through a real node, captured) and
`WindowedPlanTest.theGroupByFormOfTheWrongTimestampColumnIsRefusedToo`.

**Seed-proof.** The startup line deleted: `WatermarkSettingsTest` 5 run, **1 failure**. The
grouped-window guard removed: `WindowedPlanTest` 1 failure. Restored, green.

**Commit.** `a0a2d9a`, with the backing-out in the commit that follows it.

---

## New finding for the lead: **four of the five case studies ship a windowed query that can never emit**

Found by writing `TIME-6`'s plan-time refusal and watching what it refused. Filed here because a
case study is what somebody copies at the moment they do not know better, and this one comes with a
paragraph explaining the symptom away.

**Not one of the five case studies declares an event-time column anywhere.** Not in
`schema/streams.properties`, not in `SETUP.md`, not in any README, and there is no shipped
`application.yaml` in `examples/case-studies/` at all — `grep -rn "event-time\|eventTime\|event\.time"`
over the whole tree returns exactly one hit, and it is prose. Four of them window over their source
stream regardless:

| Study | Continuous query | Source stream | The column it windows on |
|---|---|---|---|
| `banking-card-velocity` | `sql/01-continuous-card-velocity.sql` | `card_auth` | `auth_time` |
| `biology-sequencing-qc` | `sql/01-continuous-coverage-qc.sql` | `read_metric` | `called_at` |
| `finance-counterparty-exposure` | `sql/01-continuous-hourly-exposure.sql` | `settlement` | `value_time` |
| `trading-order-flow` | `sql/01-continuous-new-order-rate.sql` | `order_event` | `event_time` |
| `trading-order-flow` | `sql/02-continuous-cancel-rate.sql` | `order_event` | `event_time` |

(`trade-processing` is unaffected: its continuous queries are unwindowed, and its README only
suggests `TUMBLE` as a variation.)

**Why this is worse than a missing line of configuration.** Without the declaration no watermark
advances over the stream, so **no window any of these opens can ever close**, whatever timestamps
arrive. The engine reports `RUNNING`, `ROWS IN` climbs, and the view stays empty for ever — and
each study tells the reader that is expected. `banking-card-velocity`'s README, at the exact moment
it happens:

> **Nothing appears yet, and that is correct.** The minute starting `1767225600` closes when the
> engine is told nothing earlier is coming. Insert one authorisation with an `auth_time` past the
> end of the minute and the window closes … This is the single most confusing thing about
> event-time streaming the first time you meet it. The engine is not slow; it is refusing to
> publish an answer it might have to retract.

**It will not close.** The remedy the README gives — insert a row past the end of the window —
cannot work, because nothing is reading `auth_time` as event time. `SETUP.md` §"A note on time"
says the same thing study-wide: "If you load ten rows and see no output, that is usually correct
and not a bug." A reader who follows the instruction, sees nothing, and reads the paragraph
concludes the product is working.

**The fix is one key per study**, and it is the fix rather than a workaround:
`pravaha.streams.card_auth.event-time: auth_time` and its four siblings, plus the matching
`event.time` option on each source binding so the plugin stamps the rows (`PravahaNode.
withDeclaredEventTime` does that from the same declaration). `CaseStudySqlTest`'s
`schema/streams.properties` fixture needs an `event-time` key of its own and `schemaOf` needs to
call `StreamSchema.Builder.eventTime`, or the test will go on planning what a node would not run.

**Severity, for triage.** It is documentation plus example configuration, not engine code — but the
symptom is a wrong answer under a success status on the product's most-copied path, and the
documentation actively explains it away. It is also the thing `TIME-6`'s refusal would have caught
at registration, which is the argument for that refusal in one sentence.

**No test here.** `CaseStudySqlTest` plans the SQL against a fixture that has the same gap, so it
is green today and would stay green after a fix that only touched the studies. Whatever closes this
should make the fixture declare an event time first, so the test can tell.

---

## `TIME-8` — nothing reports a partition's idle state, its exclusions or its regressions

**Verdict: the first clause REPRODUCES and is FIXED. The second clause and the second observation
are both already false — the entry should be narrowed to the first.**

**Second clause, false.** `GET /api/v1/queries` and `/api/v1/queries/{name}` exist
(`QueryController:260,279`), with `/plan` beside them and `/api/v1/views`, `/sinks`, `/plugins`,
`/audit` and `/permissions` too. The six-path OpenAPI list the finding quotes is far out of date,
and a reader sent looking for a 404 finds a 200.

**Second observation, also false now.** `/api/v1/status`'s `registeredQueries` is
`registry.names().size()` — it was `catalog.size()`, the *stream* count under a name that says
queries, which `HLP-8` fixed. It and `pravaha queries` now count the same thing, by name, and the
`stoppedFeeds` beside it says so in a comment precisely so the two can be read against each other.

**First clause, true and fixed.** `WatermarkTracker.isIdle`, `idleExclusions()` and
`regressions()` each existed, each were documented — the second as "the metric that explains a
moving watermark" — and none of them had a caller outside the class. This is the register's own
commonest defect: built, correct, and connected to nothing.

**Fix.** `WatermarkTracker.diagnostics()` returns all three with the partition count, in one call,
because they mislead apart: an exclusion count with no idle count says a partition went quiet at
some point, and the pair says whether it is quiet now. `Partition.idle`, `idleExclusions` and
`regressions` are `volatile` — one writer, the clock's thread, and a reader on whichever thread is
scraping; a lock here would sit on the path a watermark tick takes through every lane.
`QueryExecution.watermarkDiagnostics()` and `RegisteredQuery.watermarkDiagnostics()` carry it, and
`PravahaMetrics` publishes four meters: `pravaha_query_watermark_partitions` and
`..._partitions_idle` as gauges, `..._idle_exclusions_total` and `..._regressions_total` as
counters — counters because a partition excluded once and back a second later is invisible in a
gauge, and is the reason a window fired early and a row then arrived late.

**Test.** `WatermarkTrackerTest.time8TheThreeNumbersThatExplainAStuckWatermarkAreReadableInOneCall`
(deterministic, driving time by hand: none idle, one excluded and counted, it rejoins and the gauge
forgets while the counter does not, and a regression by the route an operator actually meets — a
partition that goes quiet, the lane running on without it, and it coming back behind) and
`PravahaMetricsTest.time8TheThreeNumbersThatExplainAStuckWatermarkArePublished`.

**Seed-proof.** `diagnostics()` returned to all zeroes and the four meters removed: 2 failures, one
in each class. Restored, 14 and 8 green.


**Commit.** `51ba5c1`.
---

## `TIME-9` — the event-time refusals that are not `idle-after`'s carry no code, no key and no diagnosis

**Verdict: REPRODUCED, FIXED.**

**Cause.** `StreamSchema.Builder.build` and `.outOfOrderness` throw bare
`IllegalArgumentException`s, and `StreamSchema` is in `pravaha-api`, which cannot know the
configuration key or which surface the value arrived through. So the refusals could not be fixed
where they are raised — which is why they had gone unfixed while a *misspelt* column one line away
in `StreamCatalog.withEventTime` already carried `PRV-2002`, the stream name and the column list.

**Fix.** `StreamCatalog.withEventTime` is the one implementation behind both the configuration path
and `POST /api/v1/streams`, and it now gives every event-time refusal the same shape through one
helper: code, stream, the rejected value, what it would do, and **both** spellings of the setting
(`pravaha.streams.ev.out-of-orderness`, or `outOfOrderness` on `POST /api/v1/streams`). A negative
out-of-orderness is checked there beside the allowed lateness that already was, and the builder's
`TIMESTAMP` refusal is wrapped. The HTTP status is unchanged: `PRV-2002` is in the SQL category and
maps to 400, as the handler's `PRV-0400` fallback did.

**Test.** `WatermarkSettingsTest.time9TheEventTimeRefusalsCarryTheirCodeTheStreamAndTheKey`.
`StreamEventTimeTest.latenessWithoutAnEventTimeIsRefusedAndSoIsAColumnTheStreamDoesNotHave` was
updated from `IllegalArgumentException` to the coded refusal, which is the finding being closed.

**Seed-proof.** The `try`/`catch` around `builder.build()` and the negative-out-of-orderness check
removed: `WatermarkSettingsTest` 6 run, **1 failure** (`Expecting actual throwable to be an instance
of PravahaException but was IllegalArgumentException`). `StreamEventTimeTest` still passed, because
the "no event-time column" arm was left in place — which is the right shape for a seed: it isolates
the two refusals this finding is about. Restored, 6 run and 5 run, 0 failures.


**Commit.** `a0a2d9a`.
---

## `STRM-12` — every way a subscription ends reaches the client as a clean completion, and `subscriberCount()` never returns to zero

**Verdict: REPRODUCED, FIXED (all three halves).**

**Cause.** `QueryRegistry.drop` set the state and the Flight loop noticed via
`query.state().isTerminal()` and called `listener.completed()` — the same signal as the client's own
`close()`, and as a graceful shutdown, which drains in-flight Flight calls on the way out. In process
it was worse: `RegisteredQuery.close()` touched no subscription and removed none from the sink's
listener list, so `Subscription` objects reported `isClosed() == false` with an empty `failure()`,
attached to a closed computation, and `subscriberCount()` — which `OPERATIONS.md` offers as the
operator's signal that a query nobody is watching is a clue — never returned to zero after any drop.

**Fix.** Two codes and one delivery path. `RegistryErrors.QUERY_DROPPED` (`PRV-8018`) and
`NODE_STOPPING` (`PRV-8019`), and `FlightErrors.statusFor` maps them to the statuses a client acts
on before reading anything: `NOT_FOUND` for a name that is not coming back, `UNAVAILABLE` for a node
that is. `RegisteredQuery.close()` calls the `endSubscriptions` that ADR-046's cutover already
built, **after** the final commit — so a subscriber sees every change the computation ever made and
is then told it is gone. `QueryRegistry.closeQueries` ends them first with `NODE_STOPPING`, and
`endBecause` is a no-op on a subscription that has ended, so the first reason wins: a shutdown is
reported as a shutdown, a cutover as `VIEW_REPLACED`. The Flight loop's `listener.error` on a
failure is no longer restricted to snapshot subscriptions.

**Test.** `SubscriptionTest.strm12ADropClosesItsSubscriptionsAndSaysWhySoTheCountReturnsToZero`,
`.strm12ClosingTheRegistrySaysTheNodeIsStoppingRatherThanThatTheQueryIsOver`, and over a real Flight
server `SubscriptionEndingTest.strm12ADropReachesTheClientAsADropRatherThanAsACleanCompletion`,
`.strm12ANodeShuttingDownIsToldApartFromAQueryThatIsOver`.

**Seed-proof.** In the registry, the three `endSubscriptions*` calls disabled: `SubscriptionTest` 24
run, **3 failures**. Over Flight, `listener.error` restricted to `fromSnapshot` again:
`SubscriptionEndingTest` 3 run, **3 failures**, the first reporting exactly the finding's own
evidence — `completed normally, no error`. Restored, 24 and 3 green.

**Left open.** The finding's last paragraph — `subscriberCount()` is reachable from no remote
surface — is not closed here. It is a field on `QueryDetail` and on the SDK's
`RegisteredQueryInfo`, which is API shape rather than subscription correctness, and `STRM-051` is
already blocked on it.


**Commit.** `5721e6c`.
---

## `STRM-14` — a subscriber keeps being streamed under a name the server says does not exist

**Verdict: REPRODUCED, FIXED.**

**Cause.** A subscription did not know which name it was opened under. `RegisteredQuery.subscribe`
stamped it with `anyName()`, which is *a* name of the computation; `drop` removed the name and the
view, `removeName` returned false because another registration held the computation, and nothing
terminal happened. The subscriber's loop tested only `query.state().isTerminal()`, which was still
`RUNNING`. Two server answers to one name at one instant contradicted each other, and the
re-authorization loop went on asking the policy about a name it could no longer have an opinion on.

**Fix.** `RegisteredQuery.subscribeAs(underName, …)` records the name the caller asked for, and the
Flight producer passes the name from the ticket. `QueryRegistry.drop` calls
`endSubscriptionsUnder(name, …)` — **before** `removeName`, because after it the name is not one the
computation knows — with `PRV-8018`. By name and not wholesale: a subscriber on the surviving name
is untouched, which is `STRM-067`'s mirror case and the point of sharing.

**Test.** `SubscriptionTest.strm14DroppingOneNameEndsOnlyTheSubscriptionsOpenedUnderIt` (both
subscribers fed, then one name dropped, then fed again: the dropped one receives nothing more and
the surviving one receives everything) and
`SubscriptionEndingTest.strm14ASubscriberUnderADroppedNameStopsAndOneUnderTheSurvivingNameDoesNot`.

**Seed-proof.** With `STRM-12`'s, above: the `endSubscriptionsUnder` call disabled is one of the
three registry failures and one of the three Flight ones.


**Commit.** `5721e6c`.
---

## `STRM-17` — the refusal to subscribe to a dropped query names a fingerprint the caller has never seen

**Verdict: REPRODUCED, FIXED.**

**Cause.** `RegisteredQuery.anyName()` returns `fingerprint.shortForm()` once `removeName` has
emptied the name set, and both `subscribe` overloads build their refusal from it — so the one
message whose job is to say which query you cannot subscribe to said
`PRV-8003 cannot subscribe to 'a740dfd20964': it is DROPPED`.

**Fix.** `removeName` keeps the last name it removed, and `anyName()` falls back to it before the
fingerprint. It is not a name the computation can be reached by — the registry has forgotten it —
and it exists for exactly this: a refusal about the word the caller used.

**Test.** `SubscriptionTest.strm17TheRefusalNamesTheQueryTheCallerAskedAboutRatherThanAFingerprint`
— asserts the message contains `cannot subscribe to 'q'` **and** does not contain the fingerprint's
short form.

**Seed-proof.** The fallback removed: `SubscriptionTest` 24 run, **1 failure**. Restored, green.

**Not done.** The finding's `ERRC` note — the same event over Flight is `PRV-8002 NO_SUCH_QUERY`,
because `require(viewName)` fails first, so one user-visible event has two codes — is a code
question for `ERRC` and is left alone. Both messages name the caller's own word, which is what this
finding was about.


**Commit.** `5721e6c`.
---

## `STRM-15` — the handover bound is stated in batches, so it is not a bound on memory

**Verdict: REPRODUCED (structurally), FIXED.**

**Cause.** `SUBSCRIPTION_HANDOVER_BATCHES = 64` was the only thing between a stalled client and the
node's heap, and it counts the wrong noun. A batch is one commit; a commit under a real feed was
measured at up to 2830 rows, so the queue held up to 64 × that in `ViewChange` objects, their
`Object[]` payloads and their string contents. The measurement stands: 20 × 50 000-row appends with
one stalled subscriber took the server's RSS from 1577 MB to 3262 MB.

**Fix.** A second bound, in rows: `SUBSCRIPTION_HANDOVER_ROWS = 250_000`, tracked as batches are
offered and as they are drained, and the first bound reached drops the batch. 250 000 is a number
somebody can reason about — tens of megabytes per stalled subscriber at a hundred bytes a row — and
is far above anything a subscriber that is keeping up will ever have queued. The decision is
`handoverHasRoomFor(queuedRows, batchRows)`, which is where the test looks. The drop is now
reported to the subscriber as well (`STRM-10`).

**Test.** `SubscriptionOverflowTest.strm15TheHandoverIsBoundedInRowsAndNotOnlyInBatches` — asserts
an ordinary commit is admitted, that 64 commits of the *measured* size still fit (so the bound does
not cost a subscriber that is merely slow), and that 64 commits of 50 000 rows — the 1.7 GB — do
not. Deterministic: it is the admission decision, not a stalled reader and a stopwatch.

**Seed-proof.** `SubscriptionHandover.hasRoomFor` made `return true`, seeded together with
`STRM-10`'s and `STRM-16`'s: `SubscriptionOverflowTest` 4 run, **3 failures**, one per finding, and
`strm15TheHandoverIsBoundedInRowsAndNotOnlyInBatches` is this one. Restored, 4 run, 0 failures.

**Not re-measured.** The 1.7 GB number was not reproduced on this machine; it is another session's
measurement and the fix is a bound, not a measurement. What a re-measurement would show is left for
`B13`, which owns measured numbers.


**Commit.** `a9f5c20`.
---

## `STRM-16` — `CONFLATE` corrupts a weight-maintaining consumer, is the default, and is the only policy a remote subscriber can have

**Verdict: REPRODUCED (the structural half; the corruption is `CONFLATE` working as documented),
FIXED.**

**Cause.** `ControlWire.subscribeTicket` encoded `["subscribe", view, pairs…]` and nothing else,
`streamSubscription` passed `SubscriptionOptions.DEFAULT`, and `ClientOptions.subscriberBufferRows`
and `conflateOnOverflow` had **no reader** anywhere in `pravaha-flight` or the Java SDK. So every
remote subscriber was `(10 000, CONFLATE)` whatever it asked for, a client that set
`subscriberBufferRows(1)` had configured a setting with no reachable effect, and `OPERATIONS.md`
described the overflow policy as "per the subscriber's choice". The corruption itself —
`+1 10, -1 10, +1 30, -1 30, +1 60` delivering a weighted sum of 50 where the truth is 60 — is
`CONFLATE` doing exactly what its javadoc says it does; what was wrong is that it could not be
declined.

**Fix.** `ControlWire.SubscriberPreference` rides **last** on the ticket, and that is what makes it
safe: everything from field 2 on is alternating filter column and value, so their count is even and
one more field makes it odd. Unambiguous in both directions, no version bump, and no chance of a
filter column being read as a policy. The producer builds `SubscriptionOptions` from it and
**refuses** an overflow it does not know rather than defaulting — a client that asked for `FAIL`
and was quietly given `CONFLATE` is the corrupted total this setting exists to avoid. The Java SDK
sends what `ClientOptions` already had (`conflateOnOverflow(false)` means `FAIL`, not
`DROP_OLDEST`: a client that says "do not conflate" is keeping its own total, and dropping the
oldest corrupts it the same way); the Python SDK gains `buffer_rows=` and `overflow=`.

**Test.** `ControlWireTest.strm16ASubscriberPreferenceRidesLastAndIsToldApartFromAFilterByParity`
(the framing, including that a ticket without one is byte-for-byte what it was) and, over a real
Flight server, `SubscriptionOverflowTest.strm16TheOverflowPolicyOnTheTicketIsTheOneTheSubscription-
Gets` — a buffer of one row and a commit of three, so `FAIL` has to decide — with
`.strm16TheSameOverflowUnderTheDefaultPolicyKeepsTheSubscriptionRunning` as the control, which is
what stops the first from passing on a server that failed every subscriber.

**Seed-proof.** `optionsOf` pinned to `SubscriptionOptions.DEFAULT`, seeded together with
`STRM-10`'s and `STRM-15`'s: `SubscriptionOverflowTest` 4 run, **3 failures**, and this one is
`strm16TheOverflowPolicyOnTheTicketIsTheOneTheSubscriptionGets` — the stream stays up where `FAIL`
should have ended it. Restored, 4 run, 0 failures.


**Commit.** `a9f5c20`.
---

## `STRM-10` — a subscriber that falls behind loses whole batches and has no way to find out

**Verdict: REPRODUCED, FIXED.**

**Cause.** The choice to drop rather than block is right, and `STRM-050` confirmed it is done
cleanly — every delivered batch is a whole commit, never a fragment. What was missing is the
channel: a plain subscription's batches carried **no application metadata at all**, so there was
nowhere for a loss count to be. `droppedBatches` reached an `AuditSink` once, when the subscription
ended, and only with `audit: memory|log` configured, and the SDK's `Subscription` exposed
`rows()`, `batches()` and `isClosed()` and nothing else. A dashboard that had lost 58 400 of 59 700
rows looked exactly like one that had received everything.

**Fix.** `BatchMark` — the mechanism SUB-1 already built — gains a third component, and a plain
subscription's batches now carry a mark: `pravaha:commit:<frontier>:<dropped>`, with the count
appended only when there is something to say, so the common mark is byte-for-byte what it was. The
parser splits on every colon rather than the last one, because a fourth component moves where "the
last colon" is and `commit:42` would decode as a kind — a plausible wrong answer rather than a
failure. The Java SDK gains `Subscription.dropped()` and `ChangeBatch.droppedBefore` /
`missedAnything()`; the Python SDK gains `batch.dropped_before` and `batch.missed_anything()`. The
frontier a plain subscription never carried is still not carried.

**Test.** `ControlWireTest.strm10ABatchMarkCarriesWhatThisSubscriberHasLost` (the encoding, the
appended-only-when-needed rule and the split) and
`SubscriptionOverflowTest.strm10APlainSubscriptionsBatchesSayWhatItHasLost`, which asserts over a
real server that a plain batch now carries a mark — which it never did — with `dropped() == 0` for
a subscriber that kept up. The Python `_mark_of` case gains the three-component form.

**Deliberately not tested by stalling a reader.** Making the handover overflow needs a client that
does not read while the server pushes hundreds of thousands of rows, which is a stopwatch dressed
as an assertion on a busy machine. The two things that can be wrong — the count not being carried,
and the mark being misparsed — are both decidable without one.

**Seed-proof.** The plain path's mark set back to `null`, seeded together with `STRM-15`'s and
`STRM-16`'s: `SubscriptionOverflowTest` 4 run, **3 failures**, and this one is
`strm10APlainSubscriptionsBatchesSayWhatItHasLost` — "a plain batch now carries a mark, which it
never did". Restored, 4 run, 0 failures.


**Commit.** `a9f5c20`.
---

## `STRM-18` — the case file's `H-EA` harness cannot be registered, and three details of `H-S` are wrong

**Verdict: CONFIRMED — the case file is wrong, not the code. FIXED in the case file.**

**Cause.** `docs/project/qa/cases/STRM.md` was written against a build that did not exist.

**Fix.** Four corrections in `docs/project/qa/cases/STRM.md`, each with a note saying what was there and
what it cost:

* **`H-EA`** was `SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id`, refused with
  `PRV-2050` because a keyed `GROUP BY` is admitted only over a window. The harness is now the
  windowed form the eight cases naming it were actually run against. `STRM-013` stays BLOCKED: its
  assertion is about the unwindowed shape and is unreachable on any shape the build admits.
* **`H-S`'s schema key** is `schema:` in `name:TYPE` form, not `fields: "user_id STRING, …"`. A
  node configured the old way refuses to start.
* **There is no `DoPut` path for stream rows.** A node is fed through `pravaha.sources.*`, and the
  harness now shows a `filesystem` binding; "DoPut N rows" in a case means "write N rows where the
  bound source reads them".
* **`event-time: ""`** is not a way to say "no event time" — as of `TIME-6` a windowed query over
  such a stream is refused at registration — so the harness declares a real column.
* **`pravaha.security.policy` has no `tenant` value**, so section G cannot run against a configured
  node at all; that is recorded where a reader meets it, and is what `STRM-100` is BLOCKED on.

**Test.** None, and none is possible: `DocumentationFreshnessTest` fails on a broken reference and
cannot fail on a sentence that is merely untrue, which is this finding's own observation.

**Seed-proof.** Not applicable — there is no defect in the code to put back.


**Commit.** `d6fd7b7`.
---

## `STRM-8` — a slow in-process subscriber blocks the engine, and the buffer bounds a commit rather than a subscriber

**Verdict: REPRODUCED. LEFT OPEN — it is its own batch. The documentation that promised otherwise
is corrected here.**

**It reproduces, unchanged.** `ViewSink.commit` still iterates its audience serially on the
committing thread (outside the publish lock, but on that thread), and `Subscription.onCommit` still
drains the buffer before returning. Both halves follow from those two lines, and neither has moved.

**What the finding gets exactly right, and what it costs.** The promise in
`SubscriptionOptions`' javadoc, `CONCEPTS.md` §8 and `USER_GUIDE.md` — "Blocking is not on the
list: a subscriber that blocks the engine applies backpressure to the *query*" — is false in
process, and in a configured node the blocked thread is `PumpingFeed`'s publish timer, which drives
every query on that feed. The second half follows from the first: nothing is ever left in the
buffer between commits, so `bufferRows` bounds one commit's size rather than a subscriber's lag,
which is why the slow consumer conflated 0 and the fast one conflated 90.

**One thing the finding does not say, and it changes the blast radius.** Over Flight — where most
subscribers are — the consumer is the gateway's lambda, which calls `handover.offer(...)` and
returns. The network and the client are already off the engine's thread, and were before this
batch. What is exposed is an **in-process** subscriber: an embedder, the Spring starter's listener
container without an executor of its own, the console's own consumer.

**Why it is its own batch.** Closing it means delivering off the committing thread — a per-
subscription serial executor, the buffer drained by it rather than by `onCommit`, and the batch's
frontier carried through. That changes the delivery contract for **every** in-process subscriber:
delivery becomes asynchronous, so every test that commits and then asserts on what arrived has to
await instead, across `pravaha-registry`, `pravaha-it`, `pravaha-embedded`, the Spring starter and
the SDK's own tests. It also needs a decision this batch is not the place to take — whether a
drained batch may span two commits (whole commits, so no partial window, but a coarser boundary
than "a batch is a commit"), and what a `Subscription` should expose so a test can be deterministic
without sleeping.

**What it needs.** One batch in `pravaha-registry` plus the test sweep: a `SubscriptionDelivery`
with a shared executor and per-subscription serial ordering; `Subscription.onCommit` admitting and
scheduling rather than draining; a deterministic `awaitQuiet(Duration)` so no test has to sleep;
and the same measurement the finding made — `commit()` with one and three 2 s consumers — as the
falsifier, which should then be ~0 ms rather than 2001 ms and 6002 ms.

**Done here instead.** The three surfaces that promised the opposite now say what is true and where
it is true: `SubscriptionOptions`' javadoc, `CONCEPTS.md` §8, `USER_GUIDE.md`'s "When you cannot
keep up", and the console's subscriptions topic. A promise that is false is worse than a gap,
because it is the reason nobody looks.

**No test.** Adding one that asserts today's behaviour would pin the defect, and the batch above
will bring the one that falsifies it.


**Commit.** `d6fd7b7` (the documentation). The engine change is unassigned.
---

## `STRM-4` — subscription encoding is per subscriber, ADR-026 says it is not, and one stalled subscriber costs 69 % of ingest

**Verdict: the structural half REPRODUCES and is LEFT OPEN — its own batch. The measured half does
not support the attribution, and should not be re-filed with it. The ADR is corrected here.**

**Structural half, unchanged.** `streamSubscription` allocates a `VectorSchemaRoot` per
subscription and writes each committed batch on that call's own thread, so N subscribers on one
query are N serialisations of every batch — the alternative ADR-026 names and rejects.

**The measurement does not demonstrate it, and the shape is the argument.**

```
 0 stalled ->  65861 rows/s   baseline
 1 stalled ->  20625 rows/s   -69 %
 5 stalled ->  13904 rows/s   -79 %
20 stalled ->  14097 rows/s   -79 %
```

A **stalled** subscriber does no encoding: its `getStream` thread is blocked writing, and the
engine's thread only calls `handover.offer`. Per-subscriber encoding would be linear in N and would
not saturate; this is a fixed cost switched on at N = 1 plus a small one that flattens by 5. Two
things do switch on at N = 1, and neither is the encoder. `ViewSink` stages **no change log at all**
when nothing is listening ("a sink with no subscribers stages nothing"), so the first subscriber
turns on a `ViewChange` per row per commit for the whole audience. And the handover then retains up
to 64 commits of them per stalled subscriber, which is `STRM-15` — measured on the same node in the
same session at 1.7 GB.

So the 69 % is most likely the changelog path plus the heap, and `STRM-15`'s row bound is the change
that should move it. **A falsifier worth running before the encoder batch:** re-run the same
measurement with the row bound in place. If it recovers, the encoder is a capacity-planning item
rather than the cause of this number.

**Why the structural half is its own batch.** Encode-once-write-N needs a shared encoder *below*
the Flight listener: Arrow Flight's server API has no way to hand an already-serialised record
batch to a second `ServerStreamListener`, so it is not a change of loop. It also has to answer what
happens when two subscribers on one query have different filters or different overflow policies,
which is now reachable (`STRM-16`) — the shareable unit is a batch, a filter and a schema, not a
query.

**Done here.** ADR-026's rationale section said "Encode once, write N times" as though it were
built; its status line was already honest and its rationale was not, and the rationale is what a
reader plans capacity from. It now says what is built, what is not, and what the per-subscriber
cost actually is (the Arrow encode and the socket write, and nothing above them).

**No test.** A characterisation test of the current encoding would pin the rejected alternative.

**Commit.** `a9f5c20` (ADR-026). The encoder is unassigned.
