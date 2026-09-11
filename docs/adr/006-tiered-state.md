# ADR-006: tiered state

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Tiered state (off-heap → RocksDB → durable store)

## Alternatives considered

RocksDB only; heap only

## Rationale and consequences

RocksDB costs 1–3 µs/op; most window state fits in memory; large state still needs spill (§14)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.

## Implementation status — as of 2026-09-11

**Not built.** L0 exists (`L0StateMap`, an off-heap open-addressed hash arena) and L2 exists
(checkpoint files). **The RocksDB L1 spill tier does not exist and RocksDB is not a dependency of
this build** — the only mention of it in any POM is the SDK's banned-dependency list.

The consequence is not cosmetic. Without L1 there is no spill, so the defence against unbounded
state is *refusal at plan time* (`PRV-2050` for an unwindowed keyed `GROUP BY`) rather than
degradation onto disk. The failure mode under pressure is memory, not disk.

This ADR records the decision and remains accepted as a decision. It is not a description of the
build, and the design document's §14.3 RocksDB configuration is likewise a design, not a reference
to running code.
