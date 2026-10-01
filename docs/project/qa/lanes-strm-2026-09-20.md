# LANE-6, STRM-4 and STRM-8, against the code of 2026-09-20

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

> **What this is.** Three open findings from [`FINDINGS.md`](FINDINGS.md), worked in the order the
> batch set them. It **does not edit `FINDINGS.md`**, which is the lead's; the verdicts here are
> what the lead applies from.

| Finding | Verdict | Commit |
|---|---|---|
| `LANE-6` | **FIXED, engine.** Reproduced deterministically, not a timing artefact: the engine handed a resuming query twenty-five rows twice. The model is right | `646cbb90` |
| `STRM-4` | **CLOSED as "the ADR was wrong".** The code encodes per subscriber and will on this carrier; measured, that is exactly where the cost is, and the ADR now says so where capacity is planned from | `6d64ed7b` |
| `STRM-8` | **FIXED, engine.** A consumer runs on its subscription's own thread; the bounded buffer now bounds a backlog | `f1be95a1` |

---

## 1. LANE-6 — the model was right and the engine was wrong

### Reproduction

The property's own script space was swept 460 times with no failure — 60 seeds at idle, then 400
under twenty-four spinner threads at load average 77 — so the defect is not a rare interleaving. It
is a *shape* of script, and it is deterministic once you have the shape:

```
Insert 20, Pause all_n, Insert 25, Pause big_n, Insert 25, Resume all_n
```

`all_n` answered `[95, 18395]` where the store says `[70, 13545]` — the last twenty-five rows
counted twice — and held that answer for the whole thirty seconds `awaitAnswer` waits, because an
overcount never settles. That is the same failure the finding records (`[74, 13750]` for
`[49, 9325]`, twenty-five rows, `awaitAnswer` exhausted), and the reason the *shared* side is
always the one that reports it is that `settle` checks the shared engine first: both engines are on
the same `SharedPartitionFeed` and were equally wrong.

### The defect

With every member of a shared reader paused, the reader stops where the last of them left it and
the source goes on without it. Those rows have been read for nobody. A member that then resumes was
given a catch-up from its own recorded row — and a catch-up reads to the **end of the source**, not
to where the shared reader stands, so the reader covered the same rows a second time as soon as
that member made the group live again. A query *registering* into the same state was handed its
whole history twice for the same reason.

The class's own contract is that the handover overlap is empty when the source is still. Across
this resume the source was still, and the overlap was twenty-five rows. So: the engine.

### The fix

`SharedPartitionFeed.resume` and `SharedPartitionFeed.join`, when `anyoneLive()` is false, move the
reader to the row the resuming or joining query wants and start no catch-up at all. Nothing is
given up by moving it: every other member is paused, and a paused member resumes from the row it
recorded rather than from where the reader stands. A catch-up a member was part way through when it
paused is closed on the path where the fan-out takes over at that same row, for the same reason.

### Proof

`SharedLaneIngestTest` gains `aQueryResumedAfterEveryQueryWasPausedCountsTheGapOnce` and
`aQueryJoiningWhileEveryQueryIsPausedCountsTheGapOnce`. Seed-proven by forcing `anyoneLive()` to
return `true`: `pravaha-bindings` runs **68 tests, 2 failures**, both of them these two; restored,
**68 tests, 0 failures**.

---

## 2. STRM-8 — a slow subscriber now falls behind alone

### The decision, and why

Three endings were available for a subscriber the engine has outrun: drop it with a named refusal,
disconnect it, or let it block alone. **Blocked alone** is the one that was already promised
everywhere — `SubscriptionOptions`, `CONCEPTS.md` §8, `USER_GUIDE.md` all say a subscriber that
cannot keep up buffers to a bound and then conflates, drops or fails — and the other two would have
been new behaviour invented to avoid implementing the documented one. Dropping or disconnecting is
still what happens, but it happens *because the buffer filled*, which is where the subscriber's own
declared policy decides it, and not because the engine ran out of patience.

### What changed

`Subscription.onCommit` is still called by the committing thread and now does nothing but filter
the commit's changes into this subscriber's buffer and wake its delivery thread. The consumer runs
on that thread: one virtual thread per subscription, started by the first change admitted, ended
when the subscription closes.

The buffer became a queue of **commits** rather than one list of changes, because a batch is a
commit: a subscriber four commits behind receives four batches, not one merged batch of a commit
that never happened. `bufferRows` bounds the changes across all of them, and overflow reaches
across them, so the oldest change goes first whichever commit it arrived in.

Two new methods, because delivery is no longer finished when `commit` returns: `pending()` is how
far behind a subscriber is, in the unit `bufferRows` bounds, and `awaitQuiet(timeout)` is what a
caller that steps the engine by hand waits on. No part of the engine calls either.

Sinks are deliberately **not** changed. A sink is part of a query's output contract, and a query
whose sink cannot keep up should slow down; a subscriber is a reader, and a reader should not.

### Proof

Two new cases in `SubscriptionTest`:

- `aSlowSubscriberDoesNotHoldTheCommit` — three consumers held inside their callback, and the
  commit that handed them their batch returns in under a second. Before: 2001 ms for one and
  6002 ms for three.
- `theBufferBoundsHowFarBehindASlowSubscriberFalls` — the consumer is held inside its first batch
  and nineteen more commits arrive behind it against a bound of four. `pending()` is 4 and
  `dropped()` is 15. Before, both were 0, because the buffer was drained inside every commit and
  nothing was ever left in it.

Seed-proven: with `onCommit` made to wait for its own delivery again — the old synchronous
behaviour in one line — `pravaha-registry` runs **197 tests, 2 failures**, both of them these two;
restored, **197 and 0**.

Callers that assert on delivery immediately after a commit were given the wait they now need:
`SubscriptionTest`, `SubscribeFromSnapshotTest`, `SinkDeliveryTest`,
`EmbeddedSnapshotSubscriptionTest`, and in `pravaha-it` the twenty-three QA cases of
`SubscriptionAnswerTest` and two of `ContinuousQueryAnswerTest`. The Spring starter's
`ListenerContainer.awaitDelivered` waits the subscription out before queueing its marker, which is
what `PravahaTester.awaitListeners` is built on, so no Spring test needed touching.

**Suites.** `pravaha-registry` 197/0, `pravaha-flight` 99/0, `pravaha-embedded` 27/0,
`pravaha-spring-boot-starter` 41/0, `pravaha-server` 58/0, `sdk/pravaha-sdk-java-flight` 63/0,
`pravaha-it` **829 tests, 0 failures, 3 errors** — the three errors are `ErrcClientTest`'s, which
needs `mvnw -pl pravaha-cli package` first and errors identically on `develop`.

---

## 3. STRM-4 — the ADR was wrong, and the 69 % is exactly the encoding

### Which way it went, and why

Sharing the serialised batch between subscribers is not available on the built carrier. A batch
reaches a client through its own `ServerStreamListener`, which serialises the `VectorSchemaRoot`
the call started with; Arrow Flight's server API has no call that hands an already-serialised
record batch to a second listener. Building one means a Flight transport of our own, and the reason
ADR-026 chose Flight is that we do not want one. So the ADR is what changes — and because the cost
it was wrong about is real, the correction is not a softening: it names the bound.

It now says what the code does: **a commit is staged once per query and serialised once per
subscriber socket.** Everything above the wire format is shared and is flat in N — `ViewSink`
stages one change log for the whole audience and each `Subscription` holds references into it —
and the Arrow serialisation is per subscriber and is the one cost that scales with N. The ADR now
states, in the paragraph a reader plans capacity from, that **a node's subscriber capacity is
bounded by Arrow serialisation and the bound is linear in subscriber count**, and points at the
hierarchical fan-out it already provides for past that bound. "Sharing the serialised batch across
sockets" is recorded as a rejected alternative with the reason, where "encoding per subscriber"
used to be recorded as one falsely.

### The measurement, and what it attributes the 69 % to

`SubscriptionIngestCostTest` runs the ladder twice on one query, 100 000 distinct keys per run,
committing every 250 rows, at **load average 10.36 over 24 processors**, after a discarded warm-up
run. Two ladders, because the difference between them is the attribution:

```
over Flight, connected and never reading:      in process, a consumer that returns at once:
   0 -> 536 768 rows/s                            0 -> 680 474 rows/s
   1 -> 361 671 rows/s                            1 -> 706 444 rows/s
   5 -> 225 963 rows/s                            5 -> 759 380 rows/s
  20 -> 130 656 rows/s                           20 -> 663 204 rows/s
```

The in-process ladder is flat: twenty subscribers cost the engine no more than none. The Flight
ladder falls by about a factor of four over the same range. Every difference between the two is
the carrier — a `VectorSchemaRoot` per subscription and a serialisation of every batch on its own
call thread.

So the 69 % **is** the per-subscriber encoding, and it is not the change log a commit stages once
it has an audience: the in-process ladder stages exactly the same change log and does not move.
That retires one of STRM-087's two remaining candidate causes and confirms the other, and it is the
opposite of what the shape of the earlier figures suggested (`-69 %`, `-79 %`, flat) — those were
taken with the node ingesting through a different path and the flat tail is an artefact of N = 5
already saturating it, not evidence of a cost that does not scale.

The test asserts the half the engine owns — twenty in-process subscribers must not cost twenty
times what one costs — and carries both tables and the load average in the assertion's message,
because surefire swallows stdout and a throughput figure quoted without its load is not a
measurement. It deliberately does **not** assert on the Flight ladder: that ladder is measuring a
bound, and a test that asserted the bound stayed where it is would refuse an improvement.

### Documents corrected

`docs/adr/026-*.md` (decision, alternatives, rationale), `docs/adr/README.md`,
`docs/ARCHITECTURE.md`, `docs/system_design.md` (§20.3b's four rules and the ADR index row) and
`docs/HANDOVER.md` all carried "encode once, write N times" as a capacity statement.

**Not touched, and the lead's:** `docs/qa/logs/STRM.md` rows STRM-028, STRM-087 and STRM-105 all
reason from the old wording and from the assumption that the two candidate causes were still open.
STRM-028's verdict stands as written; STRM-087's attribution can now be narrowed to one cause.

For STRM-8 the documents corrected are `docs/CONCEPTS.md` §8, `docs/USER_GUIDE.md` (twice),
`console/content/topics/subscriptions.md`, `console/content/topics/embedded-engine.md` and
`console/content/topics/sdk-reference.md` — the last of which also still said
`subscriberBufferRows` and `conflateOnOverflow` were "not yet sent to the server", which STRM-16
made untrue and which is the option this change gives its meaning to.

---

## For the register

- **A pre-existing lock cycle around `ViewSink`, not introduced here and not closed here.** A
  subscription whose overflow policy is `FAIL` closes itself from inside `admit`, which detaches
  through `ViewSink` and takes the publish lock while holding the subscription's own lock — and
  when that subscription is a snapshot one, the `Handoff` monitor as well. A commit on another
  thread holds the publish lock and wants that same `Handoff` monitor in `promoteJoiners`. The
  orders cross. It was there before the delivery thread (the monitor was the buffer's) and it is
  still there; it wants a finding of its own.
- **`SharedPartitionFeed.resume` does not drain the reader to idle before attaching a member.**
  With a live member the reader is normally idle at the source's end, and the class's contract only
  promises exactness with the source still, so this is not a defect today. It is the same seam
  LANE-6 was, one case along: a reader part way through a scan still holds rows that both the
  catch-up and the fan-out would deliver. `drainToIdle`'s timeout is ten seconds, which is why it
  was not simply added to the resume path.
