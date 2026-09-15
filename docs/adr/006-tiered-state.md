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

**Not built.** Only L2 exists (checkpoint files). **The RocksDB L1 spill tier does not exist and
RocksDB is not a dependency of this build** — the only mention of it in any POM is the SDK's
banned-dependency list.

L0 existed as a class and never as a tier. `L0StateMap` — an off-heap open-addressed hash arena —
was written, tested against `HashMap` over a million random operations, referenced by no production
file, and deleted in Wave 8 under [ADR-035](035-wave-8-is-survival-not-distribution.md) (W8-12). The
reason it was never wired is worth keeping: its keys are a **fixed width, chosen at construction**,
which is what lets a slot be `[key | value]` with no indirection. The state it was meant to hold is
the windowed aggregate's, whose group key can contain a `STRING` and so has no fixed width, and
whose accumulator is a variable-shaped object rather than a fixed run of bytes. The one structure in
the engine whose shape it does fit is `JoinSide`'s bucket index (`Map<Long, Long>` — eight-byte key,
eight-byte value, with a full key comparison already behind it), and that class records its own
decision that moving it off-heap wants a measurement first.

The consequence is not cosmetic. Without L1 there is no spill, so the defence against unbounded
state is *refusal at plan time* (`PRV-2050` for an unwindowed keyed `GROUP BY`) rather than
degradation onto disk. The failure mode under pressure is memory, not disk.

This ADR records the decision and remains accepted as a decision. It is not a description of the
build, and the design document's §14.3 RocksDB configuration is likewise a design, not a reference
to running code.
