# Documentation

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

## Start here

| | | Read when |
|---|---|---|
| [Quickstart](QUICKSTART.md) | Clone to a running continuous query | First |
| [Concepts](CONCEPTS.md) | The eight ideas everything follows from | Second, and it is the highest-value page here |
| [User guide](USER_GUIDE.md) | The whole surface, task by task, in Java / Python / shell | When you start building |
| [Python integration guide](PYTHON_API_GUIDE.md) | Every Python SDK call and every REST endpoint, with a verified sample each | When you connect an application |
| [Case studies](../examples/case-studies/) | Five worked systems with stores, data and code to copy | When you want a template |

## Reference

| | |
|---|---|
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code, and the five you will actually meet |
| [Operations](OPERATIONS.md) | Memory, disk, admission, what to watch, what is not solved |
| [Deployment](DEPLOYMENT.md) | The container image, the Helm chart, the volumes, the ports, the environment, upgrading a node, the release procedure, and what the chart deliberately does not do |
| [Security](SECURITY.md) | Authentication, authorization, row filters, audit |

## How it works

| | |
|---|---|
| [Architecture](ARCHITECTURE.md) | The shape, in two pages |
| [Execution model](EXECUTION_MODEL.md) | Lanes, inboxes, arenas, confinement, backpressure, and what actually bounds a node. The single source of truth for how the engine runs |
| [Streams, queries and SQL](CONTINUOUS_QUERIES.md) | A stream becomes a query becomes a view, with worked examples — and every SQL construct that runs or is refused, checked by a test. The single source of truth for what you write |
| [Connectors](CONNECTORS.md) | The plugin SPI, a worked example, the TCK, cross-source joins, and change-data-capture. The single source of truth for writing a connector |
| [TLS](CONNECTOR_TLS.md) | Every encrypted connection Pravaha makes or accepts — connector, server and SDK — and the configuration that turns each one on. The single source of truth for TLS |
| [System design](system_design.md) | The full specification |
| [Decisions (ADRs)](adr/) | Every architectural decision, with the reasoning and the alternatives rejected |

## Project

| | |
|---|---|
| [Handover](HANDOVER.md) | State of the work, what is done, what is not, what a fresh session will not guess |
| [Implementation plan](implementation_plan.md) | The wave roadmap and its gates |
| [What is left](REMAINING.md) | Every remaining requirement, in batches that can be built at once, and how they are scheduled |
| [Gate records](gates/) | Evidence packs. The retrospectives are the honest part |

## A note on these documents

Several of them are **checked by the build** rather than maintained by memory:

- `CONTINUOUS_QUERIES.md` — every statement in it is planned, built and compiled against the real engine
- `TROUBLESHOOTING.md` — the code table is hand-maintained and `ErrcCrossCuttingTest` fails the build
  if it and the `ErrorCode` declarations disagree in either direction
- The case studies — every `.sql` file is planned and run, and each README must quote the checked file
- `QUICKSTART.md` — `QuickstartCommandsTest` runs the four command blocks that need no server,
  straight out of the document, and checks what they print against what the document says they
  print. The blocks that need a running server are skipped, not covered
- `DocumentationFreshnessTest` — every module is described, every ADR a document cites exists, and
  the README's status badge, status line and roadmap table agree about which wave this is. Its link
  check is narrower than it sounds: thirteen files, and only targets carrying a file extension, so
  about a third of the repository's internal links. Anchors are checked by nothing
- `OPERATIONS.md` — every `pravaha.lane.*` key `application.yaml` declares must be named there, and
  every per-query gauge `PravahaMetrics` registers must appear in its metric table. A setting or a
  gauge that ships undocumented fails the build (DOCS-3, DOCS-4)
- Error messages — a message telling an operator to "raise `pravaha.x.y`" fails the build unless
  `application.yaml` declares that key. Eleven of them named settings that did not exist for a whole
  wave (PF-3)
- `docs/adr/` — every ADR file has a row in its index, and `HANDOVER.md`'s ADR count is held against
  the directory
- `FINDINGS.md` — every finding carries a recognised status with evidence, identifiers are unique,
  and the file's own header totals are held against the register beneath them (`FindingsRegisterTest`)

That is deliberate. Documentation that drifts is worse than none, because a reader has no way to tell
which half is true.
