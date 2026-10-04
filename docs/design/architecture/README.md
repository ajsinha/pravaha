# Architecture, component by component

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

The overview — the system diagram, the module graph and the two end-to-end traces — is
[`../ARCHITECTURE.md`](../ARCHITECTURE.md). Each page here takes one layer of it and describes every
component in the same order: **what it is for**, **its key types**, **what it talks to**, **threads
and lifecycle**, **extension points**, **invariants**, **failure codes**, and **a worked example**.

| Page | Components |
|---|---|
| [Foundations](foundations.md) | `pravaha-api` (the SPI, the data model, error codes, the wire framing), `pravaha-common` (memory, rows, rings, configuration), `pravaha-algebra` (Z-sets and the incremental lift) — and what the build enforces |
| [Planning](planning.md) | `pravaha-sql` (Calcite, the statements, the physical plan, the refusals), `pravaha-codegen` (generated stages) |
| [Runtime](runtime.md) | `pravaha-runtime` (lanes, operators, windows, watermarks, checkpoints, dead letters), `pravaha-state` (off-heap state, spill, checkpoint files) |
| [Registry](registry.md) | `pravaha-registry` (registration, fingerprints, the journal, checkpoint cuts, recovery, subscriptions, sinks, replacement, queries on queries, alerts, the debugger, tenancy), `pravaha-backfill` |
| [Ingest and egress](ingest-and-egress.md) | `pravaha-connect` (discovery), `pravaha-bindings` (feeds, shared readers, dead letters, sinks, lookups), and the plugin families |
| [Serving](serving.md) | `pravaha-serving` (views, reads, admission, retention), `pravaha-flight` (Flight SQL and the control actions), `pravaha-pgwire` (the PostgreSQL gateway) |
| [Governance](governance.md) | `pravaha-security` (the three seams), `pravaha-identity` (users, keys, sessions), `pravaha-catalog` (grants, row filters, masks, tenancy) |
| [Hosts](hosts.md) | `pravaha-server`, `pravaha-embedded`, `pravaha-spring-boot-starter`, `pravaha-cli`, `pravaha-cluster` (and why multi-node is on hold) |
| [Clients and console](clients-and-console.md) | the Java SDKs, the Python SDK and `pravaha` CLI, the console, the assistant |
| [The console's screens](console-screens.md) | thirteen screenshots of the console, taken with its browser-test harness |
| [Observability and packaging](observability-and-packaging.md) | metrics, logs, traces, health; the image, the Helm chart, the distribution; `pravaha-testkit`, `pravaha-benchmarks`, `pravaha-it`, `pravaha-bom` |

To change one of these, start from the matching developer guide in
[`../../development/guides/`](../../development/guides/README.md).
