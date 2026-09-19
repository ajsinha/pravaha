# ADR-043: how a continuous query names its sink

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **built**: registrations name a sink and every commit reaches it (see "As built") |
| Date | 2026-09-16 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-025 (registration), ADR-030 (scope tiers), ADR-039 item 5, design §15.5, `W8-13` |

## Decision

A registration names its sink **as a registration argument**, not in the SQL: `register(name, sql,
keys, principal, sinkName)`, where `sinkName` selects a binding under `pravaha.sinks.<name>`.

`INSERT INTO <sink> SELECT …` is the form this should eventually take and is **not** what is built
first, for a reason given below that is about correctness rather than effort.

`ChangelogAnalysis.checkAgainst` runs **at registration, before a single row can reach the sink**.

## The three shapes considered

| | |
|---|---|
| **`INSERT INTO <sink> SELECT …`** | What every comparable engine uses, and what a user will try first. The sink is part of the query *text*, so it lands in the fingerprint without anyone arranging it |
| **A registration argument** | Explicit, needs no SQL surface, and keeps the sink out of the planner entirely |
| **Configuration** (`pravaha.queries.<name>.sink`) | Wrong shape: queries are registered dynamically at runtime and configuration is read at startup |

## Why not `INSERT INTO` first, despite it being the better form

The planner refuses `INSERT`, `UPDATE` and `DELETE` with `PRV-2020`, and `CONTINUOUS_QUERIES.md`
§15 gives the reason: *"Pravaha answers questions; sinks write results."* Making `INSERT INTO` mean
"attach this query to that sink" requires a DML surface that currently exists only as a refusal, and
a parser path that distinguishes "write these rows into a sink" from "write these rows into a table",
which this engine does not have and should not grow casually.

It is also, today, refused for the *wrong reason*: an `INSERT` dies in validation complaining about
column nullability or type assignment rather than saying writes are not supported, so a user is sent
to fix a column list on a table they can never write to. That refusal wants fixing before the
keyword is given a second meaning.

## The complication that is the real content of this ADR

**A fingerprint shares a computation; a sink is bound to a name.** Two registrations whose plans
match are one execution behind one fingerprint — that is ADR-025's sharing, and it is why a thousand
dashboards asking one question cost one computation. But `name` is per registration, and so is the
sink a registration names.

So `q1 → sink A` and `q2 → sink B`, identical SQL, are **one computation with two sinks**. Three ways
to resolve it, and the choice must be made before the code is written rather than discovered by it:

1. **Fan out.** One computation writes to every sink bound to any of its names. Preserves sharing;
   means a sink's failure is now a failure shared between registrations that have nothing to do with
   each other, and `checkAgainst` must pass for *every* bound sink or the registration is refused.
2. **Put the sink in the fingerprint.** Two registrations with different sinks stop sharing. Honest
   and simple, and it throws away exactly the sharing the engine exists for — the same trap I-3
   already records for `--keys`, where two registrations differing only in key are correctly two
   computations *because the key changes the answer*. A sink does not change the answer.
3. **Refuse the second sink.** A computation may have one. Simplest, and it makes a registration fail
   for a reason the user cannot see: their query is refused because somebody else's identical query
   already bound a different sink.

**Fan-out is the decision.** A sink does not change the answer, so it must not fork the computation;
and refusing on a collision punishes a user for a coincidence. The cost is stated rather than
discovered: `checkAgainst` must hold for every sink attached to a shared computation, and a failing
sink must not take down the query for the other names bound to it.

## What must be true before a row reaches a sink

`ChangelogAnalysis.checkAgainst(plan, capabilities, sinkName)` was written, tested, and called from
**nothing** when this ADR was accepted. Design §15.5 is the reason it exists: a query that revises its answer, pointed at a sink
that can only append, corrupts that sink **silently** — the rows arrive, none of them are wrong on
their own, and the total is wrong for ever.

So the check runs at registration, on the capabilities the plugin declares, **before the feed opens**.
`PluginSinks.capabilitiesOf` exists for exactly this and deliberately configures without opening, so
asking what a sink can promise costs no connection.

`ErrcSqlTest` held the tripwire for this moment, asserting that nothing attached a query to a sink.
It has been inverted, as it was written to be: it now asserts that `checkAgainst` has a production
caller, and that in `QueryRegistry` the check comes before both the sink is opened and the feed is.

## Where the registry gets a sink from

`PluginSinks` lives in `pravaha-server` and `QueryRegistry` in `pravaha-registry`, which does not
depend on it and must not. The registry takes an interface, exactly as it already does for sources
with `SourceFeedFactory` — `feedingFrom(factory)` has the shape to copy, including its `NONE`
default, so that an embedded engine with no sinks configured needs no null checks anywhere.

## What would make this wrong

A DML surface arriving for other reasons, which would make `INSERT INTO` cheap and the argument
form redundant; or a deployment that genuinely wants two sinks fed by one computation to fail
independently, which fan-out gives them but which nothing here yet reports per sink.

## As built

Recorded after the code, so it describes what exists.

- **The surface.** `QueryRegistry.registerWritingTo(name, sql, keys, principal, sinkName)`; the
  Flight `pravaha.register` action takes the sink as an optional fourth field, so a client that
  predates sinks sends three and is answered as before; `pravaha register --sink <name>`, and a
  `sink` argument on both SDKs' `register`. The node hands the registry its `PluginSinks`, which
  implements the registry's `SinkFactory`, before the journal is recovered.
- **Delivery is a listener on the view's commit** (`SinkDelivery`), not a second output beside the
  view. A sink therefore receives exactly what a subscriber receives — whole commits, inserts and
  retractions in the order they were applied — and is written on the commit's cadence rather than a
  batch size of its own. The first design, a `RowOutput` beside the view flushing when a batch
  filled, was reviewed and not merged: it would have held a quiet query's tail indefinitely, was not
  safe across lanes, and lived in a module the registry cannot depend on.
- **Fan-out** is one listener per name. A name that joins a computation already running is first
  sent the view's committed contents, read at the first commit it hears, so it misses nothing that
  happened before it existed. Dropping a name releases its sink alone.
- **A failing sink is detached** with `PRV-8009` and logged, and the query carries on. Writing later
  batches past a failed one would leave the sink missing a change with nothing to say so — the
  failure this ADR exists to prevent, arrived at by a different road.
- **The journal records the sink** in the registration's own record (a `W` record: an `R` with the
  sink name after the query name), so a restart re-attaches it and a registration whose sink is no
  longer bound is refused by name in the recovery report. A build that predates sinks refuses a `W`
  record rather than recovering the query without its sink.
- **The row shape is checked too.** A sink reads rows through the schema in its own binding, and the
  engine hands it rows laid out as the query produced them, so a registration whose output differs
  in order, name or type — or, for a keyed sink, whose key columns are not the sink's — is refused
  with `PRV-8010` before the sink opens. `StreamSinkPlugin.schema()` and `keyColumns()` are how a
  sink declares them (SINK-1).
- **The guarantee is the sink's, stated at registration.** Tying a sink's commit to a checkpoint
  was a change to what a checkpoint commits, and it has been made:
  - *Where the cut is.* The checkpoint used to snapshot the view from the checkpointing thread after
    the lanes had answered — whatever had been committed by then, which is not the cut: a row
    applied before the marker and not yet committed was in neither the snapshot nor the replay.
    Now the lane's control task, at the marker, also runs `RegisteredQuery.cutOutput`
    (`QueryExecution.cuttingOutputWith`): under a lock every view commit holds, it commits the view
    (so every sink is written exactly the rows before the marker), snapshots it, and prepares each
    transactional sink, beginning its next transaction before any later commit can reach it. One
    lane only, which every registered query is; more is refused rather than cut wrongly.
  - *The two phases.* Each sink's handles — the one just prepared and any left by a checkpoint that
    failed after its cut — go into the checkpoint under `sink:<registration>`. Once
    `CheckpointStore.store` has returned, `PeriodicCheckpointer.tellingWhenDurable` commits them.
  - *The restore* commits every handle the checkpoint recorded (the SPI now requires `commit` to be
    idempotent, since the crash may have followed it) and calls the new SPI default
    `abortAfter(checkpointId)`, since the handles of transactions prepared after that checkpoint
    died with the process. A sink the checkpoint recorded is not re-seeded; a second name registered
    after the restored computation has moved on is sent `ServedView.changesSince` the checkpoint's
    view rather than the whole view.
  - *So:* transactional on a checkpointed node, **exactly once**; transactional without checkpoints,
    each commit its own transaction, at least once; `idempotentUpsert`, effectively once; neither,
    at least once. `QueryRegistry` logs which at registration and `sinkGuarantee(name)` answers it.
    `DeduplicatingSink` is not the answer for the last case and stays unwired: a view commit's
    changes carry no sequence, and a commit's boundaries are not reproduced by a replay.
  - *Not covered:* only `jdbc-sink` is transactional among the shipped sinks; a `PRV-8009` detach ends the guarantee
    (re-attaching seeds the whole view); a node stopped and never restarted leaves its transactional
    sinks' tail since the last checkpoint uncommitted, where a drop commits it; and the view is only
    as right as the checkpoint — the sink matches the view, exactly.
    `TransactionalSinkDeliveryTest` holds the protocol, crash cases included.
