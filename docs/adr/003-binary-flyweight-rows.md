# ADR-003: binary flyweight rows

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Binary flyweight rows over an arena

## Alternatives considered

`Map<String,Object>`; POJOs + reflection; Arrow internally

## Rationale and consequences

~10× lower per-record cost; Arrow retained as the *wire* format where its columnar layout pays (§8, §20.2)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
