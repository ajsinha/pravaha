# ADR-024: console as a separate python process

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

The console is a **separate operating-system process, written in Python** — FastAPI with Jinja
templates — built on the published Pravaha Python SDK. The engine is Java and serves the public REST
and gRPC API plus a plain `/status` page that works when the console is down.

This supersedes the packaging half of [ADR-023](023-api-first-console.md), which chose one artefact
by default. The API-boundary half of ADR-023 is unchanged and remains the point.

## Alternatives considered

**One Java artefact serving the console** (ADR-023's default). Simplest to deploy — download one jar,
run it, open a browser — and that adoption lever is real. Rejected because the boundary it depends on
is enforced by a test, and a test can be weakened, waived, or quietly amended by whoever is under
deadline pressure that week.

**A JavaScript console.** Would keep one runtime for the front end and avoid Python in the stack, but
gives up the second benefit below: the console would exercise a browser client, not the SDK an
integrator uses from a server.

## Rationale and consequences

**A different runtime makes the API boundary unviolable rather than merely enforced.** A Python
process physically cannot reach into a Java engine's internals. There is no privileged call to add
under pressure, because there is no mechanism for one. For a proprietary engine — where nobody can
read the source and the API *is* the entire integration experience — that is worth a second runtime.

**The console becomes continuous proof of the integration story.** It is the SDK's first serious
consumer, so an SDK gap is discovered by us building our own product surface rather than by a
customer. A feature the console needs and the SDK cannot express is a bug in the SDK, found the day
it is written.

**The cost is honest and stated:** two runtimes to deploy, patch and certify; two dependency sets;
cross-origin authentication between them; and the loss of the single-jar demo, which is mitigated by
the `/status` page — the engine remains independently useful and observable with the console absent.

Consequence for ordering: **the API comes first, then the console.** If the console can be built
before the API it needs exists, it will grow around the gap and the dogfooding benefit evaporates.
