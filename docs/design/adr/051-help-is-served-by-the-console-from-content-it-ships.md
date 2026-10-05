# ADR-051: help is served by the console, from content it ships; the engine supplies live facts

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `pravaha-console/core/content/library.py` (`include:`), `deploy/docker/console/Dockerfile` (the content it copies, and the build check that every include resolves) |
| Date | 2026-09-26 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-024 (the console is a separate process that reaches the engine only through its public API), IMG-1 |

## Context

The console's help — topics, long-form guides, tutorials, the code browser, decision records, the
About page — renders Markdown whose source of truth is the repository: `docs/`,
`examples/case-studies/`, `README.md`. The 0.1.1 console image shipped without those trees and every
page that included one was empty (IMG-1). That raised the question of where help should come from:
the console's own image, or a documentation service on the engine that the console reads.

## Decision

**Help is the console's, served from content bundled into the console at build time.** The engine
does not serve documentation. What the engine contributes is **live facts** about the node the
console is pointed at — its version, the plugins it loaded, the streams and sinks it binds, the
settings in force — read through the public API the console already uses (`/api/v1/status`,
`/plugins`, `/streams`, `/sinks`), and shown beside the static text where they matter.

The Markdown stays in the repository as the one source; a console page names it with `include:`
rather than copying it. The console image copies the included trees to the paths `include:`
resolves to, and its build fails if any include under `pravaha-console/content` does not resolve.

## Why not a documentation service on the engine

1. **Help is read when the engine is not answering.** A code page is most needed when the node
   refuses to start or a connection fails; the engine's own refusals link to the console's code
   pages (`pravaha.docs.base-url`). Documentation served by the engine is unavailable exactly then.
2. **Help is public; the engine's API is not.** The console shows `/help` and `/about` to a visitor
   who has not signed in. Under `policy: authenticated` every engine call needs a token, so an
   engine-served help system would either need an unauthenticated hole in the engine or would stop
   being public.
3. **ADR-024 keeps the engine's surface to its job.** A streaming engine that also serves a
   documentation site widens what it exposes and what can fail in it, for text that changes on a
   different cadence from the code that runs queries.
4. **Versions are already paired.** The console and engine are released together at one version
   (`deploy/release/set-version.sh`); the console reports the engine's version beside its own, so a
   mismatch is visible rather than a reason to fetch text from the engine.

## Consequences

- Adding a help page means adding Markdown under `docs/` or `pravaha-console/content/` and, for an
  `include:`, nothing else: the image carries it and the build proves it resolves.
- A console installed some other way than the image (from the wheel, outside the checkout) has no
  included content; the image is the supported way to run it outside the repository.
- Live facts are a follow-up where they help most: a connector's page saying whether this node
  loaded that plugin, a settings page showing this node's value beside the default.
