# Documentation

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

## Start here

| | | Read when |
|---|---|---|
| [Quickstart](QUICKSTART.md) | Clone to a running continuous query | First |
| [Concepts](CONCEPTS.md) | The eight ideas everything follows from | Second, and it is the highest-value page here |
| [User guide](USER_GUIDE.md) | The whole surface, task by task, in Java / Python / shell | When you start building |
| [Case studies](../examples/case-studies/) | Four worked systems with stores, data and code to copy | When you want a template |

## Reference

| | |
|---|---|
| [What SQL it runs](SQL_SUPPORT.md) | Every construct that works and every one that does not — checked by a test, so it cannot rot |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code, and the five you will actually meet |
| [Operations](OPERATIONS.md) | Memory, disk, admission, what to watch, what is not solved |
| [Security](SECURITY.md) | Authentication, authorization, row filters, audit |

## How it works

| | |
|---|---|
| [Architecture](ARCHITECTURE.md) | The shape, in two pages |
| [System design](system_design.md) | The full specification |
| [Decisions (ADRs)](adr/) | Every architectural decision, with the reasoning and the alternatives rejected |

## Project

| | |
|---|---|
| [Handover](HANDOVER.md) | State of the work, what is done, what is not, what a fresh session will not guess |
| [Implementation plan](implementation_plan.md) | The wave roadmap and its gates |
| [Gate records](gates/) | Evidence packs. The retrospectives are the honest part |

## A note on these documents

Several of them are **checked by the build** rather than maintained by memory:

- `SQL_SUPPORT.md` — every statement in it is planned, built and compiled against the real engine
- `TROUBLESHOOTING.md` — the code table is hand-maintained and `ErrcCrossCuttingTest` fails the build
  if it and the `ErrorCode` declarations disagree in either direction
- The case studies — every `.sql` file is planned and run, and each README must quote the checked file
- `DocumentationFreshnessTest` — every module is described and every ADR a document cites exists.
  Its link check is narrower than it sounds: thirteen files, and only targets carrying a file
  extension, so about a third of the repository's internal links. Anchors are checked by nothing

That is deliberate. Documentation that drifts is worse than none, because a reader has no way to tell
which half is true.
