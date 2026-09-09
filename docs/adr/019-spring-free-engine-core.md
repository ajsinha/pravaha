# ADR-019: spring free engine core

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Engine core is Spring-free; Spring Boot is a bootstrap layer above a plain-Java `PravahaEngine` seam

## Alternatives considered

Spring throughout; no Spring anywhere; Quarkus/Micronaut

## Rationale and consequences

Keeps embeddability intact (a host on Boot 3.2 cannot be forced to 3.5), keeps `pravaha dev` under 1 s, and keeps proxies off the hot path — while the server still inherits Boot's config, actuator, security and packaging for free (§22.1)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
