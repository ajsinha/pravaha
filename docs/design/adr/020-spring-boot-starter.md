# ADR-020: spring boot starter

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **built** in `pravaha-spring-boot-starter` — `PravahaAutoConfiguration` (engine bean from `pravaha.*`, started with the context and closed with it), `PravahaProperties`, `PravahaTemplate`, `PravahaEngineCustomizer`, `@PravahaListener(query, concurrency, errorHandler)`, `PravahaListenerErrorHandler` (default: log with the query and the change, keep delivering; `pravaha.listener.on-error=stop` stops the listener), the `@PravahaTest` slice with `PravahaTester`, and with Actuator present a `pravaha` health indicator and a read-only `pravaha` endpoint created only once exposed. The Boot 3.2–3.5 matrix is profiles `boot-3.2`…`boot-3.5` in the starter's pom with `BootVersionTest` as its witness; only 3.5.16 has been run (the others were not in the offline repository). **From 2.0 the floor is Boot 3.4** (profiles `boot-3.4`, `boot-3.5`): Boot 3.2 and 3.3 cannot read the starter's Java 25 class files (ADR-061). *Amended by ADR-062 (2026-10-04): the class files are Java 21 again, but Boot 3.4 and 3.5 remain the tested lines.* |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Ship a `pravaha-spring-boot-starter` with `@PravahaListener` and `PravahaTemplate`

## Alternatives considered

Documentation only; a bare `PravahaEngine` bean

## Rationale and consequences

Lets a team add continuous SQL to a service they already run, in the idiom they already use. Modelled on `@KafkaListener` so the mental model transfers (§22.4)

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
