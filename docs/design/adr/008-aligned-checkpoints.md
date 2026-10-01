# ADR-008: aligned checkpoints

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — barriers built for the inboxes; the exchange is refused rather than cut (2026-09-14) |
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

## Implementation status — as of 2026-09-14

**Built, for every input the query reads. Not built for the exchange.**

A checkpoint is one cut across the query's input rather than one snapshot per lane. Three things make
it that, and each was a separate defect (`docs/qa/FINDINGS.md` W8-2, W8-3, W8-4):

* **Every source is held between rows for the length of the cut.** `IngestPump.pumpOnce` and
  `PartitionedIngestPump.pumpOnce` take a lock for the length of their poll, and
  `QueryExecution.checkpoint` takes it through `freezeIngest`. Inside that freeze it reads every
  source's offset and hands every lane its marker, and only then waits for any of them. The offsets
  and the state therefore name the same rows — which is what exactly-once state has to mean to be
  worth saying. Previously the offsets were read *after* the snapshots, so a restore resumed past
  rows nothing had counted.
* **A marker is honoured exactly, not approximately.** A lane cuts its batch at a queued marker and
  at the producer frontier, so a task sees the state at the position it names and not a batch's
  worth beyond it. Overshooting put rows into a snapshot that the recorded offset said would be
  replayed.
* **Every source's offset is recorded, including a shuffling one.** `partitionedPumps` — the only
  way to feed a multi-lane query — was absent from the offsets map entirely, so a multi-lane
  checkpoint could not be rewound to at all.

`AlignedCheckpointBarrierTest` (`pravaha-it`) and `ControlTaskBarrierTest` (`pravaha-runtime`) hold
these, both taking their checkpoints with the sources still running; all four are seed-proven against
the previous code.

**The exchange is not cut.** A row one lane has sent to another and the second has not yet drained
belongs to neither snapshot and to no source offset. Cutting it means forwarding the marker along
each ring and aligning on it, which is not built. No pipeline this engine compiles sends on the
exchange, so the case is unreachable today, and `QueryExecution.refuseWhileRowsCrossTheExchange`
refuses rather than storing a checkpoint that silently drops rows in flight — so the first pipeline
that does send finds an error and not a wrong answer.

**Output is cut at the same marker (amended 2026-09-19, ADR-043 "As built").** A registered query's
view is committed and snapshotted on the lane at the marker, and a transactional sink is prepared
there and committed once the checkpoint is durable, so output to a transactional sink is exactly
once; to an idempotent upsert sink effectively once; to a plain append sink at least once.
`DeduplicatingSink` is not wired and has nothing to work from: a view commit's changes carry no
sequence. ADR-029 independently caps anything sourced from Aerospike at at-least-once end to end,
regardless of what the engine does.

Earlier gap, since closed: registered continuous queries were not checkpointed at all. `QueryRegistry`
now constructs a `QueryExecution` and a `PeriodicCheckpointer` per registration, restores before the
feed opens, and prunes to `pravaha.checkpoint.keep`.
