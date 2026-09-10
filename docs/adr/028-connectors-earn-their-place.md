# ADR-028: connectors earn their place

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

**A connector earns its place by proving an SPI capability, or by being demanded by a named
deployment. Never by breadth.**

The portfolio is tiered on that rule (§19.8), and the implementation is organised around the
observation that nearly every connector is one of **three shapes** — file drop, log-based CDC, and
poll-with-a-watermark (§19.9). Each shape gets one reusable base implementation, so a new connector
is transport and configuration rather than a rediscovery of the same three problems.

Feed files and drop directories are **Tier 1**, not a convenience for demos.

## Alternatives considered

**Ship many connectors early, as the streaming incumbents do.** A long connector list is the most
visible form of maturity and the easiest to market. Rejected: each connector is permanent
maintenance against someone else's release cycle, and a shallow connector that declares capabilities
it does not have is worse than no connector — it produces silent data loss during a recovery, months
later, with the original author gone.

**Let each connector define its own semantics.** What happens by default when connectors are written
one at a time by different people. Rejected because the capability declaration (§10) is only worth
anything if it is uniform; three connectors' idea of "replayable" is not a contract.

**Treat file input as a test fixture.** The tempting reading of the filesystem plugin's role. Rejected
on the evidence: a large share of real integration is still a file landing in a directory, and a file
feed is the *easiest* source to make genuinely replayable — which makes it the best available proof
of the exactly-once path rather than a toy.

## Rationale and consequences

**Proof beats breadth while the SPI is young.** This is why Kafka was deferred out of Wave 2 (P1-11)
despite being Tier 1: the filesystem plugin already exercised every part of the SPI that slice
needed, so Kafka would have added surface without adding evidence. The same test applies to every
subsequent candidate.

**The three-shape kit is what makes the Tier 2 list affordable.** Completion detection, file identity
and record-index offsets are solved once for every file-drop transport; resumable log positions and
snapshot-then-stream splicing once for every CDC source. The Debezium-compatible envelope is the
clearest case — one connector delivers MySQL, MongoDB, SQL Server, Oracle and Db2 as a single
well-specified format.

**The TCK is the enforcement.** Declared capabilities are checked against actual behaviour (P1-14):
a source claiming replayable offsets that cannot rewind fails the TCK. Without that, this ADR would
be an aspiration, and a connector's declaration would be a comment.
