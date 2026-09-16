# ADR-043: how a continuous query names its sink

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — decision recorded, the attachment not yet built |
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

`ChangelogAnalysis.checkAgainst(plan, capabilities, sinkName)` is written, tested, and called from
**nothing**. Design §15.5 is the reason it exists: a query that revises its answer, pointed at a sink
that can only append, corrupts that sink **silently** — the rows arrive, none of them are wrong on
their own, and the total is wrong for ever.

So the check runs at registration, on the capabilities the plugin declares, **before the feed opens**.
`PluginSinks.capabilitiesOf` exists for exactly this and deliberately configures without opening, so
asking what a sink can promise costs no connection.

`ErrcSqlTest.noProductionPathAttachesARegisteredQueryToASinkThatCouldReceiveARetraction` is the
tripwire and is pointed at this precise moment: it asserts `pravaha-registry` references no
`StreamSinkPlugin` and that `checkAgainst` has no production call site. **The commit that attaches a
query to a sink is the commit that must make that test fail, and must replace it with the assertion
that the check is called first.** It was written to fail here; that is what it is for.

## Where the registry gets a sink from

`PluginSinks` lives in `pravaha-server` and `QueryRegistry` in `pravaha-registry`, which does not
depend on it and must not. The registry takes an interface, exactly as it already does for sources
with `SourceFeedFactory` — `feedingFrom(factory)` has the shape to copy, including its `NONE`
default, so that an embedded engine with no sinks configured needs no null checks anywhere.

## What would make this wrong

A DML surface arriving for other reasons, which would make `INSERT INTO` cheap and the argument
form redundant; or a deployment that genuinely wants two sinks fed by one computation to fail
independently, which fan-out gives them but which nothing here yet reports per sink.
