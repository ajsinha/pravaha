# ADR-013: zsets and dbsp

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **partly built** — `ServedView` is weight-correct, but `pravaha-algebra`, the DBSP oracle this ADR rests on, is imported by no module outside itself |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Z-sets + DBSP-derived incremental operators as the execution algebra

## Alternatives considered

Flink-style hand-written retract streams; full recomputation; micro-batching

## Rationale and consequences

Correctness composes instead of being re-established per operator; work ∝ change; unlocks recursion; yields a machine-checkable correctness oracle (§9)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
