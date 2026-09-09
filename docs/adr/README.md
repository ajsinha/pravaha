# Architecture Decision Records

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential.

One file per decision, numbered and never renumbered. Amended, never rewritten: a
superseded ADR keeps its number and gains a pointer, because the reasoning behind a
decision that was later reversed is usually the most useful thing here.

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
