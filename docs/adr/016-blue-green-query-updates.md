# ADR-016: blue green query updates

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **not built** — `ShadowDeployment` exists in `pravaha-backfill` with tests and no caller |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Blue/green shadow deployment for every query change

## Alternatives considered

Stop-and-restart (Flink); in-place mutation

## Rationale and consequences

Zero downtime, state preserved, instant rollback; also the substrate for live replanning and version upgrades (§16.3)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
