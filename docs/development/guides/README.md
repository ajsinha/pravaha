# Developer guides

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

One guide per extension surface. Each says how the piece is built, which files a change touches, what must
move with it, and which tests prove it — with diagrams, real signatures and examples that were run. The
rules they share are written once, in [`CONTRIBUTING.md`](CONTRIBUTING.md); how the pieces fit together is
[`../../design/ARCHITECTURE.md`](../../design/ARCHITECTURE.md).

| Guide | For | Component pages |
|---|---|---|
| [Contributing conventions](CONTRIBUTING.md) | Everyone: boundaries, error codes, licence headers, the 1,500-line limit, documentation that moves with code, building and testing, commits | [foundations](../../design/architecture/foundations.md#what-the-build-enforces) |
| [Connector development](CONNECTOR_DEVELOPMENT.md) | A source, sink, lookup table or alert channel: the SPI, lifecycle, capabilities, offsets and exactly-once, dead letters, TLS, packaging, the TCK — with a complete example plugin | [ingest and egress](../../design/architecture/ingest-and-egress.md) |
| [Engine development](ENGINE_DEVELOPMENT.md) | A SQL function, an operator, a refusal: planner → physical plan → runtime → generated-code parity → tests | [planning](../../design/architecture/planning.md), [runtime](../../design/architecture/runtime.md) |
| [Client development](CLIENT_DEVELOPMENT.md) | The wire (Flight SQL, the control actions and tickets, REST, PostgreSQL); adding a call to the server, both SDKs, the CLI and the console at once | [serving](../../design/architecture/serving.md), [clients and console](../../design/architecture/clients-and-console.md) |
| [Console development](CONSOLE_DEVELOPMENT.md) | Screens, engine calls, help topics, and the console's test tiers | [clients and console](../../design/architecture/clients-and-console.md#the-console) |
| [Security extensions](SECURITY_EXTENSIONS.md) | `SecurityPolicy`, `TokenVerifier`, `AuditSink`, and the catalogue's grants, row filters and masks | [governance](../../design/architecture/governance.md) |

Not here because they are covered elsewhere: **embedding the engine** in an application is
[`USER_GUIDE.md` §9–§10](../../guides/USER_GUIDE.md#9-embed-the-engine-in-your-application) (with the
component on [hosts](../../design/architecture/hosts.md#pravaha-embedded)); **an assistant provider** is
[`ASSIST.md`](../../guides/ASSIST.md#writing-a-provider-plugin); **building, testing and the IDE** are
[`TESTING.md`](../TESTING.md), the two build guides and [`RUNNING_IN_INTELLIJ_AND_PYCHARM.md`](../setup/RUNNING_IN_INTELLIJ_AND_PYCHARM.md).
