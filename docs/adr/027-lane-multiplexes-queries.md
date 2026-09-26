# ADR-027: the lane multiplexes queries

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **partly built** (2026-09-15) — the *thread* half shipped in Wave 9: `LaneRunner` drives many lanes from a fixed pool of one thread per core and `QueryRegistry` owns one, so a query no longer costs a platform thread (W9-4, W9-5). The *memory* half is built and off by default: `pravaha.lane.multiplex.*` (`QueryRegistry.multiplexingLanes`) hosts many pipelines on one lane through `QueryExecution.startOn`, a row carries the stream identity dispatch needs (W9-9), and a watermark is a level that does not clamp the batch (W9-10). The registry places each registration on the least loaded shared lane below a per-lane ceiling, whatever it reads (W9-8); rows carry a route, private per hosted input or shared by the queries on a lane reading one shared reader, which writes each row into the lane once (LANE-2) |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

**The lane, not the query, is the unit of resource ownership.** A lane owns one thread, one inbox,
one arena and one timer wheel, and **multiplexes many query pipelines** over them. A query owns its
plan, its generated stage, its state slice and its subscriptions, and nothing that is measured in
megabytes or in operating-system handles.

Consequences that follow directly, and are therefore part of the decision:

- the lane loop is driven by a **ready list** of pipelines with pending input, never by a scan over
  registered pipelines;
- a record is copied into a lane's inbox **once** and every pipeline subscribed to that stream reads
  the same flyweight — fan-out inside a lane is zero-copy;
- a newly registered query **runs interpreted immediately** and is swapped to its generated stage
  when a bounded compile pool reaches it. *(Not built that way: since 2026-09-26 a query's generated
  stage is compiled at registration, before its first row, and the swap is deleted — ADR-005's
  amendment.)*

## Alternatives considered

**A lane per query.** The simplest model and the one the Wave 3 implementation starts from, because
it is right for a single-query pipeline. It does not survive the density target: 10 000 queries would
be 10 000 threads, 10 GB of inboxes and 40 GB of arena slabs (NFR-2d, §13.7). It is not a matter of
tuning the numbers down — a per-query inbox small enough to afford at 10 000 queries is too small to
batch usefully at one.

**A shared thread pool with queries as tasks.** The conventional answer, and it forfeits the entire
design: work-stealing across threads means state is touched by whichever thread picked up the task,
which reintroduces locking on every aggregate and destroys the single-writer principle
([ADR-004](004-partitioned-lanes.md)) that the whole execution model rests on.

**Round-robin over registered pipelines.** Simpler than a ready list, and it makes idle queries
expensive: at 300 pipelines per lane, a lane would spend its budget asking 299 pipelines with nothing
to do whether they have anything to do. Low-rate queries are the common case at high density, so the
cost lands exactly where it hurts most.

## Rationale and consequences

The arithmetic decides this before taste does. Per-query cost must be **kilobytes**; anything
megabyte-scale must be per lane, and lanes are sized by cores regardless of query count. That single
constraint produces the ready list, the zero-copy fan-out and the interpreted-first admission — they
are not three independent optimisations but three consequences of one budget.

**What this buys beyond density:** the interpreted path stops being only a safety net and becomes the
admission strategy, which is a second, load-bearing reason it can never be deleted (§12.4). And
because a lane already owns everything it touches, multiplexing is a change *inside* the lane rather
than a redesign of the memory model.

**What it costs:** fairness stops being emergent. With hundreds of pipelines sharing a lane, one hot
query starves the rest, and the symptom is "the engine is slow" rather than the name of the query
responsible. Per-query quotas and a per-query share-of-lane-time metric are therefore requirements of
this decision, not enhancements to it (FR-9, §21.4).

**Status of the implementation.** Wave 3's lane runs a single processor. The ownership model — its
own inbox, arena, thread and processor instance, built per lane by a factory — is in place and is
what makes the multiplexing change tractable; the multiplexing itself is scheduled work and is not
built. This ADR is recorded now because the decision constrains every design choice around it, not
because the code already reflects it.
