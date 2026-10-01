# ADR-001: language and platform

> **Baseline superseded by [ADR-061](061-jdk-25-is-the-baseline-from-2-0.md)** (2026-10-01): from
> Pravaha 2.0 the baseline is Java 25 for every module, `pravaha-api` and the Java SDKs included,
> and 21 is no longer built or supported. The language decision (Java, one language) stands.

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Java for everything, **baseline Java 21 LTS** (`pravaha-api` at 17), 25 supported; Scala only in optional non-hot-path client modules

## Alternatives considered

Java 25 baseline (v2.0 of this doc); Java 17; Scala 3 core; Kotlin; mixed

## Rationale and consequences

Allocation control, ecosystem fit, Janino codegen, Spring, hiring (§4). **Baseline revised from 25 to 21:** an embeddable library inherits its host's JVM, so demanding 25 forfeits the embeddability moat (§2.2) for features that are convenience, not capability (§4.6)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
