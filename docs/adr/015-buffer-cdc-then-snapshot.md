# ADR-015: buffer cdc then snapshot

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **not built** — `SplicedReader` and `ChangelogAnalysis` are reachable from no `src/main`. `ChangelogAnalysis` was reviewed in Wave 8 and is kept unwired *on purpose*: nothing binds a query to a sink, so there is no capability mismatch for it to refuse ([ADR-035](035-wave-8-is-survival-not-distribution.md), W8-13) |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Buffer-CDC-first, then snapshot, then splice with a bounded dedupe window

## Alternatives considered

Snapshot-then-subscribe; lock the table; dual-pipeline by hand

## Rationale and consequences

The only ordering with no gap and a *finite, known* overlap; correct by construction under Z-set consolidation (§16.1)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
