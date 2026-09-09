# ADR-021: no graalvm native image

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

No GraalVM native image for the engine

## Alternatives considered

Native image via Spring AOT; drop runtime codegen to enable it

## Rationale and consequences

Runtime Java-source compilation (ADR-005) is fundamentally incompatible with a closed-world image, and it is what makes the hot path fast. Stated so no one spends a sprint on it. Clients and UI may still go native (§22.7)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
