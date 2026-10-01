# ADR-005: whole stage codegen

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **built for filter and projection** (2026-09-26, finding C-7) — a lane's pipeline offers each chain of filters and a projection over a scan to the generator when it is compiled, and runs the interpreter for any chain the generator refuses. `AdaptiveStage` and `StageUpgradeService`, the background swap nothing reached, are deleted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

Whole-stage codegen with interpreted fallback

## Alternatives considered

Interpretation only; bytecode generation via ASM

## Rationale and consequences

5–10× on the hot path; Janino compiles Java source in ~10 ms with far better debuggability than raw ASM (§12)

## Amendment, 2026-09-26 (C-7)

**Where it is wired.** `InterpretedPipeline.compile` offers every filter-and-projection chain that
stands on a scan to the `StageGenerator` installed with `GeneratedChains.install`; the node installs
`FilterProjectStageGenerator` at start-up unless `pravaha.codegen.enabled` is false (bound from
configuration since CODEGENPROP-1; `-D` wins over the YAML file, and is all a node without Spring reads). A chain the
generator refuses (`PRV-3101`: a text or decimal projection, a `LIKE`, a comparison of computed
expressions, a filter above a projection, a read whose width does not match its column) is built
interpreted. Each chain's outcome is one line of `GET /api/v1/queries/{name}`'s `execution`.

**The rule it is held to.** The generated stage gives the interpreter's answer or does not run: it
mirrors each interpreted predicate's own accessor and null check, writes the bytes `BinaryRowWriter`
writes, and refuses whatever it cannot mirror. `GeneratedPipelineEquivalenceTest` compares whole
lane pipelines with and without the generator over random schemas and chains, and byte-compares a
generated row with the writer's; `GeneratedQueryEquivalenceTest` compares a registered query's view.

**Compiled at registration, not swapped later.** The interpreted-first admission in design §12.4 and
ADR-027 swapped a running pipeline to generated code from a background pool. What is built compiles
before the first row instead: nothing has to be handed over and two implementations are never both
live. The cost is registration latency: each distinct chain is compiled once (the generator shares a
compiled stage between lanes and between identical queries), so a restart registering many distinct
queries compiles them serially.

**What it is worth.** 1.7× for the pipeline alone on Profile A (106–118 against 60–66 million rows a
second on one thread), and level end to end on the P2 harness, whose single producer thread is the
bound for either path — `docs/project/gates/measured-2026-09-20/README.md`, 2026-09-26. The design's 5–10×
was against an interpreter that decoded a String per text comparison, which it no longer does.

## Notes

This ADR is the durable record of a decision summarised in the system design's ADR table
(§33). Where the two differ, this file is authoritative for the reasoning and the design
document is authoritative for how the decision is applied.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number
and gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning
behind a decision that was later reversed is usually the most useful thing in the directory.
