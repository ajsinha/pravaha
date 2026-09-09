# ADR-009: embedded raft metadata

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Embedded Raft (Ratis) for metadata

## Alternatives considered

Store-backed CAS lease; ZooKeeper mandatory; gossip only

## Rationale and consequences

Assignment correctness needs real consensus; embedded avoids a mandatory external dependency; ZK/etcd remain pluggable (§21.2)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
