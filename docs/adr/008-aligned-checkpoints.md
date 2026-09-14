# ADR-008: aligned checkpoints

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Aligned checkpoints; exactly-once state, effectively-once output

## Alternatives considered

Unaligned only; no checkpoints; per-record acks

## Rationale and consequences

Matches proven practice; honest about what sinks can guarantee (§14.4)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.

## Implementation status — as of 2026-09-11

**Not built.** Checkpointing is **per-lane**: each lane snapshots its own state on its own thread,
independently and not simultaneously. That is sound only while lanes share no state and each lane's
sources are partitioned to it — which is true of every query the engine currently accepts, and stops
being true the moment rows cross the exchange, because a row in flight belongs to neither lane's
snapshot. The limitation is written into `QueryExecution.checkpoint`'s javadoc.

Two further gaps follow from this:

* ~~**Registered continuous queries are not checkpointed at all.**~~ **Fixed.** `QueryRegistry`
  now constructs a `QueryExecution` and a `PeriodicCheckpointer` per registration, calls
  `store.latest()`/`execution.restore(...)` before the feed opens, and prunes to
  `pravaha.checkpoint.keep`. `PluginSourceFeedsTest` asserts that a restart over the same
  checkpoint directory resumes rather than replaying the file or starting empty.
* **`DeduplicatingSink` is not wired into the checkpoint path**, so effectively-once output is
  available as a class and not as a guarantee.

**Until aligned barriers exist, do not claim exactly-once state.** The honest shipped guarantee is
at-least-once, and ADR-029 independently caps anything sourced from Aerospike at at-least-once
regardless of what the engine does.
