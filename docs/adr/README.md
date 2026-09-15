# Architecture Decision Records

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential.

One file per decision, numbered and never renumbered. Amended, never rewritten: a
superseded ADR keeps its number and gains a pointer, because the reasoning behind a
decision that was later reversed is usually the most useful thing here.

**The `Status` row says whether the decision is in the tree.** A bare `Accepted` means built.
Anything else qualifies it — `not built`, `partly built`, `declared, not enforced`,
`superseded in practice by ADR-nnn` — with one line of why. Without that, a decision that was
deferred and a decision that shipped look identical, and the set stops being usable as a
description of the system (DOCX-042, DOCX-046).

| # | Decision |
|---|---|
| [001](001-language-and-platform.md) | Java for everything, baseline Java 21 LTS (`pravaha-api` at 17), 25 supported; Scala only in... |
| [002](002-calcite-as-compiler.md) | Calcite as compiler, custom runtime |
| [003](003-binary-flyweight-rows.md) | Binary flyweight rows over an arena |
| [004](004-partitioned-lanes.md) | Partitioned lanes, single-writer |
| [005](005-whole-stage-codegen.md) | Whole-stage codegen with interpreted fallback |
| [006](006-tiered-state.md) | Tiered state (off-heap → RocksDB → durable store) |
| [007](007-grpc-and-arrow-for-streaming.md) | gRPC + Arrow for streaming; Avatica for control plane |
| [008](008-aligned-checkpoints.md) | Aligned checkpoints; exactly-once state, effectively-once output |
| [009](009-embedded-raft-metadata.md) | Embedded Raft (Ratis) for metadata |
| [010](010-isolated-plugin-classloaders.md) | Plugins in isolated parent-last classloaders |
| [011](011-ui-out-of-the-data-path.md) | UI strictly out of the data path, on a conflating tap |
| [012](012-nanosecond-timestamps.md) | Nanosecond `long` timestamps |
| [013](013-zsets-and-dbsp.md) | Z-sets + DBSP-derived incremental operators as the execution algebra |
| [014](014-serve-maintained-views.md) | Serve maintained views from lane-local state, with `MEMORY+SINK` as the default posture |
| [015](015-buffer-cdc-then-snapshot.md) | Buffer-CDC-first, then snapshot, then splice with a bounded dedupe window |
| [016](016-blue-green-query-updates.md) | Blue/green shadow deployment for every query change |
| [017](017-auto-tune-performance-not-semantics.md) | Adapt performance automatically; never adapt semantics |
| [019](019-spring-free-engine-core.md) | Engine core is Spring-free; Spring Boot is a bootstrap layer above a plain-Java `PravahaEngine` seam |
| [020](020-spring-boot-starter.md) | Ship a `pravaha-spring-boot-starter` with `@PravahaListener` and `PravahaTemplate` |
| [022](022-console-as-a-product-surface.md) | The console is a flagship product surface with its own design system, built as a continuous... |
| [021](021-no-graalvm-native-image.md) | No GraalVM native image for the engine |
| [018](018-proprietary-licence.md) | Proprietary, wholly owned by Ashutosh Sinha. All rights reserved |
| [023](023-api-first-console.md) | The console uses only the public API; one deployable by default, two supported |
| [024](024-console-as-a-separate-process.md) | The console is a separate Python FastAPI process built on the published SDK; supersedes ADR-023 on packaging |
| [025](025-registration-and-subscription-separated.md) | Registration and subscription are separate objects; sharing is by canonical fingerprint including security predicates |
| [026](026-one-subscription-model-three-carriers.md) | One subscription model behind gRPC, WebSocket and SSE; encode once, write N times |
| [027](027-lane-multiplexes-queries.md) | The lane, not the query, owns threads, inboxes, arenas and timer wheels; a lane multiplexes many query pipelines |
| [028](028-connectors-earn-their-place.md) | A connector earns its place by proving an SPI capability or by deployment demand, never by breadth; three shapes, one kit |
| [029](029-aerospike-scan-only.md) | The Aerospike plugin ships scan-based ingest only; XDR strategies are out of scope, and the cost is that deletes are invisible |
| [030](030-flight-sql-as-the-client-protocol.md) | Arrow Flight SQL is the one client protocol for subscriptions and request/response alike; amends ADR-007, drops Avatica |
| [031](031-authorization-at-the-pravaha-layer.md) | Authentication and authorization are enforced by Pravaha on every read, never delegated to the store; a row filter is sound only if the view carries its columns |
| [032](032-parameters-are-values-not-queries.md) | Prepared statements bind at plan-build time so a value is never parsed; a `?` may stand where a value goes and nowhere else |
| [033](033-the-ui-ships-as-its-own-artefact.md) | The UI ships as one self-contained artefact, reaches the engine only through the public API, and puts a stateless service layer between the browser and the SDK |
| [034](034-distribution-deferred.md) | One node scaled to its cores. Multi-node deferred; key-partitioned aggregates are the missing rung it names as the work to do instead — **and they are not built**: a keyed aggregate on more than one lane is refused (`PRV-3020`) |
| [035](035-wave-8-is-survival-not-distribution.md) | Wave 8 is survival on one node -- node ownership of durable state, real checkpoint barriers, a standby, and wiring three built-but-unreachable mechanisms. Not E7's cluster |
| [036](036-one-node-thousands-of-queries.md) | One node, thousands of continuous queries: arena and inbox sizing, lane multiplexing, one Aerospike scan feeding many queries, shared schedulers. Measured, not asserted — **scope accepted, not yet built** |
