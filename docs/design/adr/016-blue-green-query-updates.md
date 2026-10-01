# ADR-016: blue green query updates

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **built** 2026-09-19 — see [ADR-046](046-a-replacement-meets-the-running-version-at-a-position.md), which settles what this one left open |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Blue/green shadow deployment for every query change

## Alternatives considered

Stop-and-restart (Flink); in-place mutation

## Rationale and consequences

Zero downtime, state preserved, instant rollback; also the substrate for live replanning and version upgrades (§16.3)

## Amendment, 2026-09-19

Built. `ShadowDeployment` -- which was the whole of this decision's code and had no caller -- is now
the state and the audit trail of a replacement the registry runs: it refuses a cutover before the
candidate has caught up (`PRV-4014`) and a seam that would go backwards (`PRV-4015`), and it holds
who served the name from which seam. What this ADR did not settle, and what a cutover actually
turns on, is in [ADR-046](046-a-replacement-meets-the-running-version-at-a-position.md): the seam is
a source position rather than a moment, subscribers are told the view was replaced, the sink follows
the name at a checkpoint boundary, and a replacement in flight survives a restart.

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
