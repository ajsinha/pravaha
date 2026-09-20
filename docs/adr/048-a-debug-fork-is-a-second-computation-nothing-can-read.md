# ADR-048: a debug fork is a second computation nothing can read, stepped by hand

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `DebugSessions` in `pravaha-registry`, `OperatorStateReader` in `pravaha-runtime`, `PluginReplaySource` in `pravaha-bindings`; per-operator counts come from ADR-039 item 3's `OperatorMetrics` |
| Date | 2026-09-19 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-008 (aligned checkpoints), ADR-038 (which moved the debugger to the roadmap), ADR-046 (the shadow computation a replacement runs), B6's per-operator measurement, design §16.4 and §23.9 |

## Decision

A time-travel debug session is **a second computation of the same plan, on lanes of its own,
restored from a retained checkpoint, reading the same sources from the offsets that checkpoint
recorded, and driven one step at a time by the caller.** Nothing about the live query changes, and
nothing outside the session can reach the fork.

Design §16.4 describes the feature and leaves seven things open. Each is decided here, because each
is a place where the obvious implementation is wrong in a way that would not show up as a failure.

### 1. Isolation is three absences, not a mode

The design says "sinks are hard-disabled and the isolation is enforced at the lane-allocation
level, not by convention". A `DEBUG` flag consulted before each write would be a convention with a
stronger name. So there is no flag:

- **No sink is attached.** A `SinkDelivery` is opened by `QueryRegistry` when a *registration* names
  one. A fork is not a registration and the fork path never calls `openDelivery`. There is nothing
  to switch off and therefore nothing to leave on.
- **No reader can reach the fork's view.** It is a `ServedView` built for the session, named
  `debug:<id>`, and never given to the `ViewCatalog`. Nothing resolves a name to it and nothing can
  subscribe to it. `DebugSession.requireNotTheLiveView` refuses the one mistake that would undo
  this — passing the live query's view in — and a test makes that mistake to prove the refusal.
- **The lanes are its own.** A fork is started with `QueryExecution.start`, never `startOn`, so it
  never joins a multiplexed lane group. A fork of a query hosted on a shared lane would otherwise
  share an inbox and an arena with every other query on it.

### 2. The replay pulls; it is not a feed

A `SourceFeed` is a set of pumps on threads, delivering rows as fast as backpressure allows. That is
what a running query wants and exactly what "step by one row" cannot use. So the debugger reads
through `ReplaySource`, which is pull: `next()` returns one row when the caller asks for one, and
nothing runs on a thread the caller does not control.

It opens **readers of its own**, never the shared reader `SRC-3` introduced. A shared reader fans
one poll out to every query on it, and a debugger pulling a row at a time from it would either
starve the live queries or take rows they were about to be handed — which is the sharpest way a
fork could affect the query it forked from.

It reads **without pushdown**. A live query lets the source evaluate what it can, so the rows
reaching the engine may be a projection of what the source holds; a debugger has to show the row as
the source has it, because that is most of what somebody opens one for. The cost is bytes on a path
where one person is stepping.

### 3. Determinism is arranged, and the interleaving is a choice with a cost

Design §28.3 invariant 1 says execution is deterministic *given the same input and watermark
sequence*. A live query does not have that: its partitions are read by separate pumps and its event
time moves on a wall-clock tick, so "the same input sequence" is whatever the schedulers did that
second. Four things give a fork what the invariant needs.

1. **The same state** — both runs restore the same checkpoint's bytes.
2. **The same input** — the same partitions from the same offsets, interleaved **round-robin in a
   fixed order**.
3. **The same time** — event time moves only when a step says so. No watermark generator is armed
   on a fork and no idle-partition tick can fire a window in one run and not the other.
4. **The same observation point** — every step drains the lane and commits the view before it
   reports, so nothing is read half-applied.

Point 2 is the one with a cost, and it is stated rather than buried: **a bug that depends on one
particular interleaving of two partitions may not reproduce in a fork.** The alternative — recording
the live interleaving and replaying it — needs a per-row trace on the ingest path of every query in
case one is later debugged, which is a permanent cost for an occasional need. Reproducibility was
worth more than fidelity to one scheduling.

### 4. A predicate is one comparison, not a language

The design's sketch shows a conditional breakpoint reading `group = "user_42" AND SUM(amount) < 0`.
The obvious next step is a small expression grammar, and a small expression grammar that is
*nearly* SQL's — with its own coercions, its own null semantics and its own corner where it
disagrees — is a source of wrong answers in the one tool somebody is using because they already
have a wrong answer.

So `ViewPredicate` is one column of the view against one value, with `= != < <= > >=`, null never
satisfying anything, and an unknown column refused **by name** with the columns there are. It holds
when any row of the view satisfies it, which is what "stop when user_42's sum goes negative" means,
since the group is a column of the view. Anything more is a query, and the way to ask it is to step
to the row and read the view.

### 4a. The operator counters are B6's, not a second set

The first cut of this carried its own per-edge counters. B6's per-operator measurement landed on
`develop` while it was being written and does the same thing better -- rows in *and* out per plan
node, ids from `PlanNodes`, a sampled self time, state bytes and a watermark -- so the debugger
reports **those**, and the rebase deleted the duplicate.

What that buys is not only one mechanism but one set of numbers: a step's operator lines and
`GET /api/v1/queries/{name}/plan` are keyed by the same node ids and counted once, so an operator
comparing the debugger against the plan graph cannot be shown two answers.

It costs one thing, and it is handled where it arises: B6's measurement is off by default behind
`pravaha.metrics.operators` (it was measured at 7.9-8.6% of a narrow query's throughput). A debug
fork turns it on **for itself**, through a per-compile override rather than the node-wide flag --
a session exists to say what each operator did, and that answer must not depend on a setting
somebody did not turn on before the incident. One lane, one person stepping: nobody is counting
the throughput.

### 5. Inspecting state must not change it, and a window cannot be looked into

Operator state is arena memory written by one thread, so it is read **on that thread**, as a control
task, exactly as restoring a checkpoint and advancing a watermark already are. It is rendered to
text while the flyweight still points at the bytes, because the memory is reused as soon as the walk
moves on. It is paged, and the page is bounded on the way out *and* on the way in: entries outside
the page are counted and discarded as the walk goes, so paging a join holding ten million rows costs
the walk and a page rather than ten million rendered rows.

A windowed aggregate reports **the windows it has fired and is still retaining**, not the ones still
filling. That is a real limit: the only way to read an open slice's answer is `fire()`, which emits
it, and a debugger that published a window early would have changed the query it was asked about. A
window is therefore inspectable for as long as the stream's `allowedLateness` keeps it correctable,
and not before it fires.

### 6. A fixture replays the input from empty, and its expectation is rehearsed

The export's purpose (§16.4) is that "a production incident becomes a deterministic regression test
in the repository, permanently". Two shapes were possible.

**Carry the checkpoint.** The fixture would restore the fork's starting state and be a faithful
continuation of the incident. It would also be a test of this engine's ability to read one
particular state file, pinned to a format that is free to change, and it would copy production
operator state into the repository. Rejected.

**Replay the input from empty.** The fixture registers the query the ordinary way and replays the
rows the session stepped, with the watermark advances in their place in the sequence. It needs
nothing from the node it came from. That is what is built — and it has a consequence that is
written into the generated file rather than left to be discovered: **the expectation is the answer
over those rows from empty**, not over the history that preceded the checkpoint.

Which means the expectation cannot be copied from the fork's view, because the fork's view holds the
answer to everything before the checkpoint too. So the export **rehearses**: it runs the script
through a second, empty execution of the same plan and writes what *that* ended with. The number in
the generated file is one this engine has actually produced from exactly the input the file carries,
rather than one asserted and hoped for. `DebugFixtureExportTest` then compiles the file with the
JDK's compiler and runs it, because "it compiles and passes" is worth nothing asserted.

### 7. A session is a resource, and it takes the administer permission

A session holds a whole second copy of a query — its lanes, its arena, its operator state, a reader
per partition. So: `pravaha.debug.sessions.max` bounds how many a node holds,
`pravaha.debug.session.ttl` releases one nobody has touched, `pravaha.debug.session.max-rows` bounds
what one may consume (it keeps every row it has read, so that it can be exported), and
`pravaha.debug.step.max-rows` bounds how far a "to the next commit" or "until this holds" step will
search. Expiry is checked on the way in to the next call rather than on a timer, so a node that
debugs nothing starts no thread.

It requires **administer**, not read. A fork exposes the query's SQL, its input rows and its
operator state — more than reading its view exposes, and more than reading its plan exposes — so it
takes the permission that dropping the query takes, as a replacement does (ADR-046). Reading a
session's status takes it too, because the status carries the SQL.

## Alternatives considered

**Instrument the live query instead of forking it.** A "step mode" on the running execution is
simpler to describe and it stops production. Every second the operator spends reading a panel is a
second the query is not answering, and a debugger that costs an outage is a debugger nobody opens
during an incident.

**Share the live query's source readers.** Cheaper, and it means the debugger competes with
production for rows. A fork must not be able to affect what it forked from.

**A WebSocket step protocol.** Design §23.11 reserves one for the debugger as "the one genuinely
bidirectional surface". Stepping is request/response — the client asks for a step and is told what
happened — so the actions are request/response, and a WebSocket can be added later without changing
what a step means. Not built.

**JSON on the control wire for the step report.** The report carries three variable-length lists and
a flat list of strings has to lay them out somehow. JSON would read better and would put a parser in
the Python SDK, which promises to install without one. Counts followed by fixed-width groups is what
the rest of this wire already does.

**A `pravaha-debug` module**, as design §7's tree shows. The session needs the registry's planner,
its security policy, its checkpoint store, its lane runner and its feed factory; a module below the
registry could not reach them and one above would need the registry to expose all five. It lives in
`pravaha-registry` beside `QueryReplacements`, which is its closest sibling.

## Consequences

- A fork needs a checkpoint, so it needs `pravaha.checkpoint.directory`. A node without one refuses
  with `PRV-8011`, and the refusal tells apart "this node does not checkpoint", "this query has not
  taken one yet" and "that id has been pruned".
- A source that cannot be rewound to a checkpoint's offsets cannot be debugged: `PRV-8012`, before
  anything opens, naming the stream and the plugin. That is the same question `backfillRefusal`
  already answers for a replacement, and it is answered by the same code.
- A fork runs on **one lane**: a keyed aggregate's groups are partitioned across lanes, and "the
  groups" only means something when there is one.
- Six new codes, `PRV-8011` to `PRV-8016`.
- The `.restore()` and `.latest()` call sites STATE-050 counts each gain one. That case's premise —
  that nothing shipped calls them — is now wrong twice over, and the test says so rather than being
  reconciled quietly.
- The console's debugger screen (§23.9) has everything it needs and is not built; that is B9.

## Notes

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number and
gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning behind a
decision that was later reversed is usually the most useful thing in the directory.
