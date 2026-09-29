---
title: The time-travel debugger
slug: time-travel-debugger
category: operating
order: 55
icon: bug
summary: "A query is answering wrongly. Fork it from a checkpoint into a second copy nothing can read, step it one row at a time, watch every operator, and export the incident as a JUnit test that compiles and passes."
audience: Operators, developers
keywords: [debug, debugger, time travel, fork, checkpoint, step, replay, breakpoint, predicate, operator state, fixture, regression test, session, sinks disabled, PRV-8011, PRV-8012, PRV-8013, PRV-8014, PRV-8015, PRV-8016, pravaha.debug.sessions.max, pravaha.debug.session.ttl, pravaha.debug.session.max-rows, pravaha.debug.step.max-rows]
guide: user-guide#11-the-time-travel-debugger
related: [checkpoints-recovery, query-lifecycle, backfill-cutover, errors-registry, zset-weights, observability]
---

A query is producing a row that is wrong, and the log says nothing, because nothing went wrong —
the engine did exactly what the query asked. The question is *which* row caused it and *which*
operator turned it into the answer you are looking at.

A **debug session** answers that by running the query a second time under inspection. It is forked
from one of the query's retained checkpoints, so it starts with the state the query actually had,
and it reads the same sources from the offsets that checkpoint recorded — so it sees the same rows,
in order, from that moment forward.

## What a fork is, and what it cannot touch

> **DEBUG — sinks disabled.** Every surface says so permanently, and it is not a mode that can be
> left on by mistake.

Three absences, rather than a flag:

- **No sink is attached.** A sink is opened for a *registration*; a fork is not one, and the fork
  path never opens one. There is nothing to switch off.
- **Nothing can read the fork's view.** It is a view of its own, named `debug:<session id>`, and it
  is never put in the view catalogue — so no name resolves to it, no `SELECT` finds it and no
  subscription can attach.
- **Its lanes are its own.** A fork never joins a shared lane, even when the live query is on one.

So the live query goes on running, its view does not move, and its subscribers hear nothing. If a
session is open and the query looks wrong, the session is not the cause.

It needs two things: `pravaha.checkpoint.directory` set, because a fork starts from a checkpoint
([Checkpoints and recovery](/help/topics/checkpoints-recovery)), and a **rewindable source** — the
same constraint a backfill has.

## Opening one

```bash
# Which positions this node can still start from — the newest pravaha.checkpoint.keep of them.
pravaha debug checkpoints --name user_volume
4471
4470
4469

# The last line of `fork` is the session id, so a shell can capture it.
SESSION=$(pravaha debug fork --name user_volume --checkpoint 4471 | tail -1)
```

Every debug call takes the **administer** permission on the query — the same one dropping it takes.
A fork exposes the query's SQL, its input rows and its operator state, which is more than reading
its view exposes.

## Stepping

| Step | What it does |
|---|---|
| `row` | One input row, whichever partition offers it next |
| `rows:N` | Up to N of them |
| `commit` | Rows until the view actually changes, or until `pravaha.debug.step.max-rows` |
| `watermark:<nanos>` | No rows: event time advances, and any window it closes fires. It does not go backwards |
| `until:<column>:<op>:<value>` | Rows until any row of the view satisfies the comparison |

```bash
pravaha debug step --session "$SESSION" --step row
pravaha debug step --session "$SESSION" --step rows:10
pravaha debug step --session "$SESSION" --step until:total:<:0
```

Each step answers four questions at once, which is what makes a wrong answer explainable:

```text
step 11 (UNTIL)  the view satisfies total < 0
  in   +1 txn#0@8842 [user_42, -160]
  op   n0 Aggregate(group=[user_id], [SUM(total)])  in=1 out=2
  op   n1 Filter(amount <> 0)  in=1 out=1
  op   n2 Scan(txn)  in=1 out=1
  view -1 [user_42, 120]
  view +1 [user_42, -40]
  rows consumed 11, view 3 rows, watermark 1740000000000000000
```

**The operator lines are the part a view alone cannot give you.** A filter that rejected the row
and an aggregate that produced a zero delta look identical from the outside; here one reads
`in=1 out=0` and the other `in=1 out=2`.

`n0`, `n1`, ... are the plan's own node ids, the same ones the
[plan endpoint](/help/topics/observability#every-metric) publishes per-operator numbers under — so a step and
the plan graph show the same counters rather than two of them.

The view lines carry their [Z-set weights](/help/topics/zset-weights): an update is the old row
withdrawn at −1 and the new one inserted at +1, which is why a single arriving row produces two
changes.

### A breakpoint is one comparison

`until:` takes **one column of the view, one of `= != < <= > >=`, and one value**. That is
deliberate. A second expression language that is nearly SQL's would have its own null rules and its
own coercions, and would disagree with SQL somewhere — in the one tool you opened because you
already had a wrong answer. Anything more than a comparison is a query: step to the row and read
the view.

An unknown column is refused by name, with the columns there are (`PRV-8015`).

## Reading an operator's state

```bash
pravaha debug state --session "$SESSION"
OPERATOR        KIND       WHAT               ENTRIES
aggregate#0     aggregate  groups             1284
join#0.left     join       txn ⋈ ref left     40213
join#0.right    join       txn ⋈ ref right    902

pravaha debug inspect --session "$SESSION" --operator aggregate#0 --key user_42
user_42    rows=3  n=3  total=-40
```

Bounded and paged (`--offset`, `--limit`); a page above the ceiling is refused rather than built,
because an unbounded page of a join holding ten million rows takes the node down. Reading changes
nothing: a join's index is walked without evicting from it, an aggregate is read without emitting
it, and the read happens on the lane that owns the state rather than beside it.

**A windowed aggregate shows the windows it has fired and is still retaining, not the ones still
filling.** That is a real limit rather than an oversight: the only way to read an open window's
answer is to fire it, and a debugger that published a window early would have changed the query it
was asked about. A window stays readable for as long as the stream's allowed lateness keeps it
correctable ([Late data](/help/topics/event-time-watermarks#late-data)).

## The same session twice gives the same answers

Two sessions forked from the same checkpoint and given the same steps report identically — the same
rows in the same order, the same operator counts, the same view changes, the same watermark.
Nothing drives a fork but you: no feed thread, no watermark clock, no periodic checkpointer.

One cost is worth knowing. The replay reads a query's partitions **round-robin in a fixed order**,
which the live query does not — its pumps are on separate threads and their interleaving is
whatever the schedulers did that second. The fixed order is what buys reproducibility, and it means
a bug that depends on one particular interleaving of two partitions may not appear in a fork.

## Exporting the incident as a test

```bash
pravaha debug fixture --session "$SESSION" --name "user 42 goes negative" \
    --out pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures/
wrote .../User42GoesNegativeFixtureTest.java
```

The generated file carries the schema of every stream the query reads, its SQL and key columns, the
rows the session consumed in order with their weights and event times, the watermarks it pushed,
and the view to expect. It registers the query the ordinary way and replays that script, so it
needs nothing from the node it came from and goes on working when the state format changes.

**What it asserts is the answer over those rows, from empty.** The session was forked from a
checkpoint, so the fork's view also held everything before it; the fixture does not. Its
expectation is *produced* at export time by running the script through an empty copy of the query,
rather than copied from the fork and hoped for — so the number in the file is one the engine has
actually reached from exactly the input the file carries. The generated file says so at the top.

## Sessions are resources

A session is a whole second copy of a query: its lanes, its arena, its operator state, and one
source reader per partition.

```yaml
pravaha:
  debug:
    sessions:
      max: 4
    session:
      ttl: 15m
      max-rows: 20000
    step:
      max-rows: 10000
```

| Setting | Default | What it bounds |
|---|---|---|
| `pravaha.debug.sessions.max` | 4 | Sessions open on this node at once. `PRV-8014` past it, naming the ones it holds |
| `pravaha.debug.session.ttl` | 15m | How long a session nobody has touched survives. Checked on the way in to the next call, so a node that debugs nothing runs nothing extra |
| `pravaha.debug.session.max-rows` | 20000 | How many rows one session may consume. It keeps every one, so that it can be exported |
| `pravaha.debug.step.max-rows` | 10000 | How far one `commit` or `until:` step will search before giving up |

Watch **`pravaha_debug_sessions_open`**. It is published whether or not anybody has opened a
session, so it reads zero the rest of the time and an alert on it is possible — a node quietly
holding four forks of a large query has four extra copies of its state and nothing else says so.

End one when you are finished:

```bash
pravaha debug end --session "$SESSION"
```

## Over the API and the SDKs

| | |
|---|---|
| HTTP | `POST /api/v1/queries/{name}/debug`, `GET /api/v1/queries/{name}/debug/checkpoints`, `POST /api/v1/debug/sessions/{id}/step`, `GET /api/v1/debug/sessions/{id}/state`, `GET /api/v1/debug/sessions/{id}/state/{operator}`, `GET /api/v1/debug/sessions/{id}/view`, `POST /api/v1/debug/sessions/{id}/fixture`, `DELETE /api/v1/debug/sessions/{id}` |
| Java SDK | `client.debugFork(...)`, `debugStep`, `debugState`, `debugInspect`, `debugView`, `debugExport`, `debugEnd` |
| Python SDK | `client.debug_fork(...)`, `debug_step`, `debug_state`, `debug_inspect`, `debug_view`, `debug_export`, `debug_end` |
| Flight | Nine actions under `pravaha.debug.*` |
| Console | `/queries/{name}/debug`, reached from the query's own page and from the command palette |

## In the console

The **Debugger** screen is the same session through a browser. It lists the positions this node
still retains, forks from the one you choose (or from the newest at the moment you press the
button, which is not necessarily the newest on the list — a node prunes while a page is open), and
then steps the fork: one row, ten rows, on to the next commit, or a spec typed in full for a
watermark or an `until:` comparison. Each step's report lands at the top of a log, so the sequence
reads as a sequence, and the step happens without the page navigating.

The controls are ordinary forms and work with no JavaScript at all — a step posts and the screen
answers with that step's report. It is the one place in the console that does not redirect after a
post, because the report *is* the answer and the engine has no call that hands back a step it has
already taken. Repeating a step is the cheapest mistake on the screen: nothing outside the fork can
be reached by it.

**DEBUG — sinks disabled** stays on the screen for as long as the session does, read from the
engine's own answer rather than written into the page.

After a step, the panels below follow the fork. **The fork's view** is redrawn from the step's own
report: the view read when the page loaded, plus the changes the step reports, is the view after
the step. That sum is checked against the view size the step reports before it is drawn, and when
the two disagree the panel says so and keeps what it had; reload to read the view again.
**What the operators are holding** is read again from the engine after each step, because a step
reports each operator's rows in and out, not how many entries it holds, and one cannot be worked
out from the other: a window fires and evicts, a join keeps what it has seen. An open page of one
operator's entries is not re-read; it says it is as the page loaded it. With scripting off, each
step is a page of its own and every panel is read at the new position.

Everything on the screen needs the **administer** permission, reading included. A reader sees the
screen with the fork control disabled and the policy's own reason beside it.

## Where next

- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what a fork starts from
- [Registry codes](/help/topics/errors-registry) — the six refusals, each by name
- [Comparing two versions](/help/topics/backfill-cutover#comparing-two-versions) — the other way to find out what changed
