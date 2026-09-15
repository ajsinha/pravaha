# ADR-022: console as a product surface

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **partly built** — the console exists and is a functional admin console, not the flagship surface with its own design system this decides. No Storybook, no visual-regression baseline, no WCAG 2.2 AA audit, no Monaco, no plan DAG, no time-travel debugger; §23.20's release gate is unmet. Its shape is now ADR-024 and ADR-033 |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

The console is a flagship product surface with its own design system, built as a continuous workstream from Phase 3

## Alternatives considered

A late control-plane admin UI; CLI-only; a thin metrics page

## Rationale and consequences

For most users the console *is* the product, and W10 (the time-travel debugger) exists nowhere else. A polished UI cannot be produced in one late phase, so it is resourced with a dedicated frontend engineer and shipped alongside each engine capability (§23.1)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
