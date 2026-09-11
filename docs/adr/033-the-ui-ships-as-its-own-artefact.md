# ADR-033: the UI ships as its own artefact, and talks to the engine through services

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-11 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-023 (API-first console), ADR-024 (console as a separate process), ADR-030 (Flight SQL as the client protocol), §23, §23.20 |

## Decision

The user interface is **a separately shipped, self-contained artefact** that reaches the engine only
through the public API, and it is structured as **a service layer with a browser client on top of
it** rather than as pages that call a driver.

Three parts, and they are separable decisions that have been bundled until now:

1. **Separate process.** Unchanged from [ADR-024](024-console-as-a-separate-process.md), and
   reaffirmed below for reasons that are specific to a streaming engine rather than generic
   frontend hygiene.
2. **Self-contained artefact.** The UI ships as one runnable thing with no runtime prerequisites on
   the operator's machine. This is new, and it amends ADR-024's packaging in practice: a separate
   process must not mean a source tree, a virtual environment and a `make install`.
3. **A service layer.** The browser talks to versioned JSON services (`/api/v1/...`); those services
   talk to the engine through the published SDK. The browser never holds an engine connection.

## Why separate, specifically for this engine

The usual arguments for splitting a frontend apply, but three are much stronger here than they would
be for an ordinary application.

**The two runtime profiles are opposites.** The engine is pinned lane threads, an off-heap arena and
latency measured in microseconds. A UI is I/O-bound request handling with bursty allocation and a GC
profile nobody cares about. Colocated, a burst of UI traffic competes for CPU and heap with lane
threads, and the symptom is p99 latency in the query engine — the one number a customer measures.
This is the same reasoning that moved Arrow serialisation off the commit thread: work whose latency
nobody measures must not share a thread with work whose latency everybody does.

**The blast radius is asymmetric.** Restarting the UI should cost nothing. Restarting the engine
costs a warm-up and, since the registry journal, a recovery pass. A leak in a template renderer or a
CVE in a web stack must not be able to take down a process that is maintaining state for every
registered query.

**The dependency argument is not theoretical.** Adding the Flight and registry modules to
`pravaha-server` immediately produced a Netty version conflict between Spring Boot's BOM and Arrow's
gRPC stack, which had to be pinned by hand. That is the coupling cost appearing in miniature, in the
one place where the two were already joined. An engine facing an SBOM and a security review wants a
dependency tree that is small and auditable, not one carrying a web framework's transitive closure.

And ADR-024's original argument stands: a boundary enforced by a test can be waived by whoever is
under deadline pressure that week; a boundary enforced by a process cannot.

## Why self-contained, which is the part that changes

"Separate process" and "separate runtime to install" are different things, and conflating them has a
cost that was found the way these things usually are — by someone following the quick start and
asking where the UI was. Bringing it up required Python, a virtual environment, a `pip install` and
a `make` target before a single screen appeared.

That friction is not inherent to separation. It is inherent to shipping a source tree instead of an
artefact. The requirement is therefore:

> Running the UI is **one command against one artefact**, configured by the engine's URL. No
> language runtime, package manager or build step on the operator's machine.

This matters more for the full UI than for the admin console, because the full UI's React islands
(ADR-023) need a Node build. Without this rule the deployment would need a JVM, a Python runtime and
Node to show a screen. With it, the build step happens once, where the artefact is produced, and the
operator installs nothing.

The evaluation objection — that engines people like ship a UI on a port with no ceremony — is real
and does not require colocation. A development command that starts both, and a container image that
bundles both, give frictionless evaluation while production keeps two processes.

## Why a service layer rather than pages over a driver

The console today renders HTML directly from SDK calls. That is right for four hundred lines and
wrong for the full surface, for reasons that show up as the surface grows:

**It is what makes the UI scalable horizontally.** The services hold no session state, so any
instance can answer any request and instances can be added behind a load balancer. A page that keeps
a live engine connection per browser tab cannot be scaled that way, because the connection is the
state.

**It is what lets the browser be replaced without touching the engine integration.** The islands,
and any future client, consume JSON. An SDK change lands in one layer, and the screens do not know
the SDK exists.

**It is what makes the surface testable without a browser.** A service returning a typed object can
be asserted on directly; a page that returns HTML can only be scraped.

**It keeps the security boundary in one place.** The browser never holds engine credentials and never
opens an engine connection: it presents a session to the service layer, and the service layer
presents a principal to the engine. Authorization stays where ADR-031 put it.

## The compatibility contract

Separation decays into coupling unless the contract is explicit, and the failure is quiet: someone
adds a private endpoint, and the two artefacts are welded together without anyone deciding to weld
them.

- The UI uses **only** the public API — Flight SQL and the published SDKs. No private endpoints, no
  reaching into engine internals, no shared database.
- The engine's API is versioned, and **the UI must tolerate an engine one minor version either
  side**, because the two are deployed separately and will therefore be at different versions in
  practice.
- Where the UI needs something the public API does not offer, the answer is to add it to the public
  API — which is the second benefit from ADR-024 working as intended: the UI is the API's first
  serious consumer, and what is awkward for it is awkward for every integrator.
- A version mismatch is reported in the UI, not worked around silently.

## Consequences

**Two things to deploy, and version skew is now a real state** rather than an impossible one. That is
the price, and the compatibility rule above is what makes it payable.

**The UI needs its own release cadence, health check and configuration.** Expected: it changes far
more often than the engine and should not drag an engine restart behind it.

**The UI's own service layer is a component with its own scaling story** — stateless, horizontally
scalable, and the place where per-browser fan-out of a subscription is handled so the engine sees one
subscriber rather than one per tab.

**§23.20's checklist is unaffected.** It is a release gate on the UI's quality and is not made
easier or harder by this decision.
