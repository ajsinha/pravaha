# ADR-023: api first console

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted on the API rule, which holds — the console reaches the engine only through the published SDK. **Its packaging half is superseded by [ADR-024](024-console-as-a-separate-process.md)**: the console is a separate Python process and its own artefact ([ADR-033](033-the-ui-ships-as-its-own-artefact.md)), not "one artefact by default" |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

The console uses **only the public, documented, versioned API** -- the same REST and gRPC surface a
third party would use. No privileged endpoints, no internal shortcuts.

Packaging is decided separately: **one artefact by default**, with separate deployment of the console
as a supported configuration.

## Alternatives considered

**A privileged internal API for the console.** Faster to build each screen, and the usual outcome is
that the public API lags behind whatever the console needed most recently. For a proprietary engine
that is fatal rather than untidy: nobody can read the source, so an integrator's entire experience of
the product is the API, and a console-first API is a second-class product.

**A mandatory second deployable.** Considered on modularity grounds and rejected on operational ones.
It buys team autonomy and essentially nothing else -- serving static assets and aggregating metrics
at 1 Hz is not a scaling axis, and the axes that are (lanes, state size, read replicas) are unaffected
by where the UI runs. It costs cross-origin authentication, two artefacts to patch and certify, and
the loss of "download one jar, run it, open a browser", which is a real adoption lever for software
that cannot say *just clone it*.

**A pure single-page application.** Rejected because it cannot render the theme into the markup, so
the page flashes on every load; on the amber-on-black theme that flash is a full white screen.

**A pure server-rendered application.** Rejected because Monaco, a live plan DAG and a stepping
debugger are genuinely application-like, and templates are the wrong tool for them.

## Rationale and consequences

The two halves of this decision have deliberately different strengths, and conflating them is the
mistake worth avoiding.

**The API boundary is strict and permanent.** It is the expensive-to-reverse direction: a console
that accretes privileged calls cannot be un-accreted cheaply, whereas a strict boundary costs nothing
to maintain once it exists. It is enforced by an architecture test, not by intention -- the same
mechanism that keeps storage clients out of the engine core and Spring out of the runtime.

**The packaging decision is deliberately reversible.** Because the boundary already exists, splitting
the deployable later is a packaging change rather than a re-architecture. Splitting late is cheap;
un-splitting is not.

**Rendering is chosen per page rather than per project.** Public pages and the app shell are
server-rendered, which removes the theme flash and keeps the accessibility scaffolding in one place;
the application surfaces are React islands mounted inside that shell. One architecture, two
rendering strategies, each used where it is actually right.

Consequence worth stating: the console can never be a shortcut. Any capability a screen needs must
first exist in the public API, which slows individual screens slightly and makes the integration
story a first-class concern by construction.

## Notes

This ADR also resolved a contradiction in the design document, where section 23.3 specified a React
SPA and section 23.4a specified a server-rendered shell with the theme in the markup. Both were
written; only one could be true.

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number and
gains a `Superseded by ADR-NNN` line at the top.
