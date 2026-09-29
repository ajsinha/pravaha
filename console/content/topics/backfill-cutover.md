---
title: Backfill and cutover
slug: backfill-cutover
category: operating
order: 105
icon: arrow-left-right
summary: "Replacing a running query with a new version without a gap and without counting anything twice: what the backfill reads, why there is no ETA, what a cutover moves, and how long you can roll it back."
badge: BLUE/GREEN
audience: Operators
keywords: [blue green, backfill, cutover, rollback, replace, candidate, seam, frontier, replay, throttle, rate limit, PRV-4014, PRV-4016, PRV-4017, PRV-4018, PRV-4019, ADR-046]
guide: operations
related: [query-lifecycle, compare-versions, sharing, views-and-keys, metrics-alerts]
---

A registered query cannot be edited. Its answer is a maintained view, and a view whose definition
changed underneath it would be neither the old answer nor the new one. So a new version runs
**beside** the old one: it reads the history its sources still hold, splices onto the live stream
where the running version has reached, and — when both have consumed exactly the same input — the
name moves across in one step.

That is the whole of it, and each half is where the risk is. The backfill is a long read against
somebody's production storage. The cutover is the moment every reader of the name starts seeing a
different answer.

## The two versions, and the seam

| | What it is |
|---|---|
| The **running version** | What the name answers now. It never stops, and it goes on answering until the cutover |
| The **candidate** | The new version, reading the same sources from the beginning. It answers nothing to anybody |
| The **seam** | The position in the input where the candidate stops replaying history and starts following the live stream |
| The **cutover** | The name stops pointing at the running version and points at the candidate. Only offered once both have consumed the same input |

Each version has its own fingerprint, and the console shows both: the candidate's and the one it is
replacing. Two fingerprints mean two computations with two copies of the state, which is what a
replacement costs while it runs.

## Why there is no ETA and no percentage

**A source does not say how much history it holds.** A file source knows how many bytes are left
and not how many records; a Kafka topic knows its end offset and not how many of those records this
query will accept; a table scan knows nothing at all until it finishes. A percentage needs a
denominator, and every denominator available here is a guess.

So the console shows what is measured and nothing else:

| What the screen shows | What it means |
|---|---|
| Records of history read | Exact, to the last batch boundary |
| Records read from the live stream | Records taken after the seam |
| Reading at | Records a second over the last sample |
| Partitions on the live stream | How many of the candidate's partitions have reached the seam, out of how many it has. **This is the closest thing to progress there is**, and it is a count, not a fraction of unknown work |
| Candidate behind the running version | How far behind in *event time* the candidate is. `not known yet` before it has read anything — never `0 s`, which would say it had caught up exactly |

A progress bar would be believed. There is nothing behind it to believe, so there is not one.

## Throttling, pausing, and what each costs

The backfill is competing with production traffic for the same storage. The ceiling is in records a
second:

```bash
pravaha throttle --name hourly_spend --rate 2000
```

It may be **lowered while the backfill runs and never raised** above the ceiling the replacement was
started with. That is deliberate: an operator turning a backfill down in an incident must not be
able to turn it back up past what capacity planning agreed to. The engine refuses a raise with
`PRV-4018` rather than the console quietly clamping the number typed into it.

Pausing stops the reading and keeps everything read so far. Abandoning ends a replacement that has
not cut over and releases the candidate; the running version never noticed.

## The cutover

Offered only when every partition has reached the seam. Ask for it earlier and the engine refuses
with `PRV-4014`: at different positions, the records between the two would land in neither
version's output or in both, and the whole point of the mechanism is that neither can happen.

At the cutover:

- The name starts answering the candidate.
- **Every open subscription to the name ends** with `PRV-4019`. A subscriber subscribes again, and a
  snapshot subscription starts from a fresh snapshot of the new version — which is what it needs,
  because the change from the old answer to the new one is not a delta anybody could apply.
- The replaced version is kept, not dropped, for as long as the replacement's `rollback.retention`
  says.

The console asks for the query's name to be typed before it will do any of this, exactly as
dropping a query does, and for the same reason: a dialog with an OK button is one a hurried click
clears without reading.

## Rolling back, and the window closing

While the replaced version is retained, rolling back puts it back — the same single step in
reverse, and every subscription ends again.

The console shows the window as a time, and **says so when it has passed**. A screen that simply
stopped offering the button would leave an operator guessing whether the feature was missing or the
window was gone. Past it, the replaced version is no longer retained: going back means registering
the old SQL again and backfilling it, which is a new replacement and not a rollback.

`finish` ends the replacement early and releases the replaced version. After it there is no
rollback, and the screen says that rather than showing a button that would answer `PRV-4016`.

## From the console

The screen is at **Queries → the query → Replacement**, and everything on it needs the administer
permission on that name — the same permission that pauses, resumes and drops it. An identity that
does not have it sees the whole screen, read-only, with each control disabled and the engine's own
reason beside it.

Only one replacement of a name at a time: a second is refused with `PRV-4017`, as is a candidate
whose plan normalises to the computation already running — a cutover to itself.

## What goes wrong

| Symptom | Usually |
|---|---|
| The cutover button will not enable | Partitions on the live stream has not reached the total. The candidate is still reading history |
| `PRV-4014` from a cutover that looked ready | The source is busy enough that the two versions never stop at the same record. Try again, or quieten it. Nothing changed |
| `PRV-4018` when starting | The stream's source cannot replay, or its positions do not order its records. A backfill needs both. Or it is `postgres-cdc` or `mysql-cdc`: the running version is the slot's (or replica id's) one reader, so drop and register the new version instead |
| Readers see `PRV-4019` and stop | That is the cutover. They subscribe again |
| The rollback window says *closed* | The retention ran out. Registering the old SQL again is the way back, and it is a fresh backfill |
