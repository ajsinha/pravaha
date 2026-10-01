# Documentation

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

The documents are in six folders, by who reads them and when:

| Folder | Holds |
|---|---|
| [`guides/`](guides/) | Using Pravaha: the quickstart, concepts, the user guide, SQL, the CLI, the SDK, connectors, the tutorials |
| [`operations/`](operations/) | Running it: deployment, Docker, operations, security, compatibility |
| [`development/`](development/) | Changing it: building, testing, the IDE, the handover and what is left |
| [`design/`](design/) | Why it is the way it is: architecture, the execution model, the specification, the decisions (ADRs) |
| [`publications/`](publications/) | Written about it: the research paper, the deck, the Medium post, the competitive landscape |
| [`project/`](project/) | Its record: release notes, the QA record and findings register, the gate evidence packs |

## Start here

| | | Read when |
|---|---|---|
| [Quickstart](guides/QUICKSTART.md) | Clone to a running continuous query | First |
| [Concepts](guides/CONCEPTS.md) | The eight ideas everything follows from | Second, and it is the highest-value page here |
| [User guide](guides/USER_GUIDE.md) | The whole surface, task by task, in Java / Python / shell | When you start building |
| [Building and testing with Docker](development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md) | Step by step from a fresh clone with only Docker: images, the compose stack, a query end to end, every test suite in containers | When you want it running without installing a JDK or Python |
| [Running from an IDE](development/DEVELOPING_IN_AN_IDE.md) | The engine in IntelliJ IDEA and the console in PyCharm: run configurations, debugging, tests | When you change the code |
| [Python integration guide](guides/PYTHON_API_GUIDE.md) | Every Python SDK call and every REST endpoint, with a verified sample each | When you connect an application |
| [The assistant](guides/ASSIST.md) | Plain English to and from continuous SQL through any model, with the engine as the judge: configuration, providers, runtime switching, plugins, security | When you want a query or a refusal explained |
| [Case studies](../examples/case-studies/) | Worked systems with stores, data and code to copy | When you want a template |

## Guides — using it

| | |
|---|---|
| [Quickstart](guides/QUICKSTART.md) | Clone to a running continuous query |
| [Concepts](guides/CONCEPTS.md) | The eight ideas everything follows from |
| [User guide](guides/USER_GUIDE.md) | The whole surface, task by task, in Java / Python / shell |
| [Streams, queries and SQL](guides/CONTINUOUS_QUERIES.md) | A stream becomes a query becomes a view, with worked examples — and every SQL construct that runs or is refused, checked by a test. The single source of truth for what you write |
| [The command line](guides/CLI.md) | `pravaha` for a running engine, `pravaha-engine` for SQL with no server |
| [Python integration guide](guides/PYTHON_API_GUIDE.md) | Every Python SDK call and every REST endpoint, with a verified sample each |
| [The assistant](guides/ASSIST.md) | Plain English to and from continuous SQL through any model |
| [Connectors](guides/CONNECTORS.md) | The plugin SPI, a worked example, the TCK, cross-source joins, and change-data-capture. The single source of truth for writing a connector |
| [TLS](guides/CONNECTOR_TLS.md) | Every encrypted connection Pravaha makes or accepts — connector, server and SDK — and the configuration that turns each one on. The single source of truth for TLS |
| [Troubleshooting](guides/TROUBLESHOOTING.md) | Every `PRV-` code, and the five you will actually meet |
| [Known limits](guides/LIMITS.md) | Everything not built, sorted into deferred, buildable and boundary |
| Tutorials | [1. Your first maintained view](guides/tutorials/01-your-first-maintained-view.md) · [2. Following a view](guides/tutorials/02-following-a-view.md) · [3. Changing a running query](guides/tutorials/03-changing-a-running-query.md) · [4. Investigating an incident](guides/tutorials/04-investigating-an-incident.md) · [Aerospike fulfilment](guides/tutorials/aerospike-fulfilment.md) |

## Operations — running it

| | |
|---|---|
| [Deployment](operations/DEPLOYMENT.md) | The container image, the Helm chart, the volumes, the ports, the environment, upgrading a node, the release procedure, and what the chart deliberately does not do |
| [Running in Docker](operations/RUNNING_IN_DOCKER.md) | The two images, the `/opt/pravaha` layout path by path, the compose stack and its profiles, ports, ownership, backup, troubleshooting |
| [Operations](operations/OPERATIONS.md) | Memory, disk, admission, what to watch, what is not solved |
| [Security](operations/SECURITY.md) | Authentication, authorization, row filters, audit |
| [Compatibility](operations/COMPATIBILITY.md) | What 2.0 changes (Java 25), what 2.x keeps stable, what is experimental, which clients work with which nodes, upgrading from 0.2.x |

## Development — changing it

| | |
|---|---|
| [Building and testing with Docker](development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md) | Step by step from a fresh clone with only Docker |
| [Building and testing without Docker](development/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md) | The same walkthrough with a local JDK and Python |
| [Running from an IDE](development/DEVELOPING_IN_AN_IDE.md) | The engine in IntelliJ IDEA and the console in PyCharm: run configurations, debugging, tests |
| [Testing](development/TESTING.md) | Every test tier — unit, in-process, container-backed plugins, SDK, console, deck, performance — with and without Docker, what skips, measured times, and CI guidance |
| [Handover](development/HANDOVER.md) | State of the work, what is done, what is not, what a fresh session will not guess |
| [What is left](development/REMAINING.md) | The build strategy for every buildable gap, in tranches and slots |

## Design — how it works, and why

| | |
|---|---|
| [Architecture](design/ARCHITECTURE.md) | The shape, in two pages |
| [Execution model](design/EXECUTION_MODEL.md) | Lanes, inboxes, arenas, confinement, backpressure, and what actually bounds a node. The single source of truth for how the engine runs |
| [System design](design/system_design.md) | The full specification |
| [Implementation plan](design/implementation_plan.md) | The wave roadmap and its gates |
| [Initial requirements](design/initial_req.md) | The requirements specification the project started from |
| [Decisions (ADRs)](design/adr/) | Every architectural decision, with the reasoning and the alternatives rejected |
| [Spikes (RFCs)](design/rfc/) | Time-boxed questions answered by measurement, such as [S2: memory access parity](design/rfc/S2-memory-access-parity.md) |

## Publications — written about it

| | |
|---|---|
| [Research paper](publications/research/continuous-queries-as-maintained-answers.pdf) · [article](publications/research/continuous-queries-as-maintained-answers-article.md) | *Continuous Queries as Maintained Answers*: Z-sets, and the four hand-overs made exact at a position — a subscription from a snapshot, a checkpoint, a shared reader, a replacement — with the test behind each claim, or "argued" where there is none. LaTeX source beside it; CC BY-NC-ND 4.0 |
| [The deck](publications/Pravaha-Continuous-SQL-Engine-Design-and-Evidence.pptx) | *A continuous SQL engine: design and evidence*, 91 slides, generated from `tools/deck/` |
| [The Medium post](publications/medium/) | *Keeping the Answer: Inside Pravaha*: the design decisions, what each costs, and the alternatives turned down |
| [Competitive landscape](publications/COMPETITIVE_LANDSCAPE.md) | Where Pravaha stands among the products that do part of its job: scored by category, dated, and plain about where it loses |

## Project — the record

| | |
|---|---|
| [Release notes](project/RELEASE_NOTES.md) | What each release changed, newest first |
| [QA record](project/qa/) | Test cases, execution logs, the [findings register](project/qa/FINDINGS.md) and the [summary](project/qa/SUMMARY.md) |
| [Gate records](project/gates/) | Evidence packs. The retrospectives are the honest part |

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
  the README's status badge, status line and roadmap table agree about which wave this is
- `MarkdownLinksTest` — every relative link and image in every markdown file in the repository
  resolves to a file that is there, and every anchor to a heading that is there. A document moved
  or renamed fails the build in each place that still points at its old path
- `OPERATIONS.md` — every `pravaha.lane.*` key `application.yaml` declares must be named there, and
  every per-query gauge `PravahaMetrics` registers must appear in its metric table. A setting or a
  gauge that ships undocumented fails the build (DOCS-3, DOCS-4)
- Error messages — a message telling an operator to "raise `pravaha.x.y`" fails the build unless
  `application.yaml` declares that key. Eleven of them named settings that did not exist for a whole
  wave (PF-3)
- `design/adr/` — every ADR file has a row in its index, and `HANDOVER.md`'s ADR count is held against
  the directory
- `FINDINGS.md` — every finding carries a recognised status with evidence, identifiers are unique,
  and the file's own header totals are held against the register beneath them (`FindingsRegisterTest`)

That is deliberate. Documentation that drifts is worse than none, because a reader has no way to tell
which half is true.
