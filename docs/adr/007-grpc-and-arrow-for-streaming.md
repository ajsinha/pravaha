# ADR-007: grpc and arrow for streaming

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; narrowed by [ADR-030](030-flight-sql-as-the-client-protocol.md) |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

gRPC + Arrow for streaming; Avatica for control plane

## Alternatives considered

Avatica only; WebSocket only; custom TCP

## Rationale and consequences

Avatica has no push; Arrow gives zero-copy polyglot clients; WebSocket kept for browsers (§20)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
