---
title: Changing a running query — compare, backfill, cut over
slug: backfill-cutover
category: operating
order: 105
icon: arrow-left-right
summary: "Replacing a running query with a new version without a gap or a double count: comparing the two first (SQL, plans, what the engine will do), what the backfill reads, why there is no ETA, what a cutover moves, and rolling back."
badge: BLUE/GREEN
audience: Operators
keywords: [blue green, backfill, cutover, rollback, replace, candidate, seam, frontier, replay, throttle, rate limit, PRV-4014, PRV-4016, PRV-4017, PRV-4018, PRV-4019, ADR-046, compare, diff, versions, workbench compare, plan diff, operators added removed changed]
guide: operations
related: [query-lifecycle, create-continuous-query, sharing, lanes, upgrades]
---

A registered query cannot be edited. Its answer is a maintained view, and a view whose definition
changed underneath it would be neither the old answer nor the new one. So a new version runs
**beside** the old one: it reads the history its sources still hold, splices onto the live stream
where the running version has reached, and — when both have consumed exactly the same input — the
name moves across in one step.

That is the whole of it, and each half is where the risk is. The backfill is a long read against
somebody's production storage. The cutover is the moment every reader of the name starts seeing a
different answer. Before either, [compare the two versions](#comparing-two-versions), at the end
of this page: the workbench shows the SQL side by side, both plans, and what the engine will do with
the new one before you register it.

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

## Comparing two versions {#comparing-two-versions}

A registered query is never edited in place: a new version is a new registration beside the old
one ([Upgrades](/help/topics/upgrades)). Before you register it, the workbench's **Compare** panel
shows what the change is and what it will cost — the SQL diff *and* the plan diff design §23.7 asks
for, so the consequence is visible before anything runs.

### Opening it

| From | How |
|---|---|
| A registered query | its page's **Open in workbench**, edit, then the **Compare** panel — it compares with the query the draft came from |
| Any draft | the **Compare** panel, then pick a registered query by name or another open draft |
| A link | `/workbench?query=txns_per_user&panel=diff&against=txns_per_user` opens the query, the panel, and compares at once |

The draft on the right is always the one in the editor. Change it, or pick something else to compare
with, and the comparison already on screen is greyed and fenced as **out of date** until you press
**Compare again** — it is never shown as if it described the new text.

The panel needs JavaScript; the plain form the workbench falls back to runs and registers only.

### What it shows

| Part | What it is |
|---|---|
| What the engine will do | the consequences that can be known before registering — below |
| SQL | Monaco's diff editor, the earlier version on the left. **Unified view** puts both in one column (a narrow screen always does). Tab reaches both editors; they are read only |
| Plan | both plans as graphs, the registered one first. An operator on one side only is marked **+** (added, heavy border) or **−** (removed, dashed); one that changed, **~**. The mark is in the node's accessible name too |
| Measured totals | under the **registered** plan only: rows in, state against its ceiling, view size, subscribers, watermark. A draft has not run, so its side says so rather than showing numbers |
| Operators | the plan diff in words, differences only; **Show unchanged operators** lists the rest as `=` |

#### How operators are matched

Operators are matched by **where they sit and what kind they are**, never by their position in the
list — an operator inserted near the root would otherwise renumber everything beneath it:

1. Each plan is read from its root as **branches**: down through one-input operators to a scan, or
   to an operator with several inputs (a join), whose inputs are paired left with left, right with
   right.
2. Along a pair of branches, operators of the **same kind** (`Filter`, `WindowedAggregate` — the
   engine's own operator names) are paired in order, as many as possible, preferring pairs whose
   labels are identical.
3. A pair is **changed** when its label (the engine's rendering, arguments included), its output
   columns, or whether it keeps state differ; otherwise it is the same. Anything unpaired is added
   or removed — and an operator never "changes" into another kind: `Project` becoming `Aggregate`
   is one removed and one added.

Where an aggregate's label carries key ordinals (`keys=[0, 1, 2]`), they are read by name through
its input's columns, so the change says `keys window_start, window_end, user_id → …` rather than
two lists of numbers.

#### What the engine will do, and what nobody can tell yet

| Finding | Where it comes from |
|---|---|
| **Same or separate computation** | when both versions are registered (a draft whose text is exactly a registered query's counts), their fingerprints decide it. Otherwise plans that differ cannot share; identical plans share only if registered with the same keys and retention and the same row filters — the fingerprint the engine answers with settles it ([Sharing](/help/topics/sharing)) |
| **Output columns** | each side's validated schema: columns added, removed, retyped, or reordered |
| **Keys** | whether the registered version's key columns are still in the new output, so it can be keyed the same way and a point query by that key works on both |
| **State shape** | stateful operators added, removed or changed — a changed one holds its state in another shape, so none of v1's state can serve v2 |
| **Not determinable** | how long the new version takes to fill and how much state it will hold: the engine does not estimate either before registration, and the panel says so rather than guessing |

### Worked example

`txns_per_user` is running:

```sql
CREATE CONTINUOUS QUERY txns_per_user KEYED BY (user_id, window_end)
AS SELECT window_start, window_end, user_id, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end, user_id
```

Open it in the workbench and change it to count per merchant as well, and only transactions over
100:

```sql
SELECT window_start, window_end, user_id, merchant, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE amount > 100
GROUP BY window_start, window_end, user_id, merchant
```

**Compare** answers, for the plans the engine gives these two:

```text
What the engine will do with this version
- The plans differ, so the fingerprints will too: registered, this version is a computation of its
  own, filling its own state from its sources. txns_per_user keeps running untouched.
- Output columns: added merchant.
- txns_per_user's key (user_id, window_end) is in this version's output: it can be keyed the same
  way, and a point query by that key works on both.
- The state changes shape — changed WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2, 3],
  1 aggregate(s)) — so none of txns_per_user's state could serve this version.
- Not determinable here: how long this version takes to fill, and how much state it will hold.

Operators
changed: WindowedAggregate keys window_start, window_end, user_id → window_start, window_end, user_id, merchant; now emits merchant
changed: Project[window_start, window_end, user_id] → Project[window_start, window_end, user_id, merchant]; now emits merchant
+ Filter(amount > 100)
```

`WindowAssign`, the input `Project` and `Scan(txn)` are the same on both sides; **Show unchanged
operators** lists them. The filter sits between the window assignment and the projection — it is
one added operator, and nothing beneath it is reported as moved.

Register it as `txns_per_user_v2` from the Register panel, compare again, and the first finding
becomes the engine's own answer: two fingerprints, two computations, each dropped independently.

### Pitfalls

- **Identical plans are not a promise of sharing.** Keys, retention and row filters are part of the
  fingerprint too; only the fingerprint the registration answers with settles it.
- **The totals are v1's, and so are the numbers on its operators.** They describe the registered
  query's running plan and say nothing about how the new version will behave. The draft's side of
  the comparison carries no numbers at all, because nothing has run it — the shapes are comparable
  and the measurements are not.
- **Comparing two drafts** works the same way, without the registered-query findings: there are no
  fingerprints, keys or totals until one is registered.
- **A side the engine will not plan** (a refusal) or will not show you (the policy) is said on that
  side — *partly compared* or *not permitted* — and the rest of the comparison still stands.

## Where next

- [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query) — `CREATE OR REPLACE` and its options
- [Continuous queries and their lifecycle](/help/topics/query-lifecycle)
- [Lanes](/help/topics/lanes#sharing-lanes) — moving a query onto a lane of its own is a replacement
- [Upgrades](/help/topics/upgrades)
- [Tutorial 3 — changing a running query safely](/tutorials/changing-a-running-query)
- How it is built: [Architecture: replacement](/help/architecture-registry#replacement-bluegreen)
