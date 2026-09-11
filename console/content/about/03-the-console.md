---
title: This console
slug: this-console
section: About
order: 30
icon: window-sidebar
summary: A separate process that reaches the engine only through the published SDK, and why that boundary is enforced by a process rather than by a test.
---

## Why it is a separate process

ADR-024. The console reaches the engine **only through the published Python
SDK** — the same client an integrator uses.

A boundary enforced by a test can be waived by whoever is under deadline
pressure that week. A boundary enforced by a process cannot: this is Python, the
engine is Java, and there is no way to reach past the API even carelessly.

The second benefit is that the console is the SDK's first serious consumer. An
awkward corner of the client API becomes an awkward corner of the console, where
somebody notices, instead of being found by an integrator.

## Why it is its own artefact

ADR-033. The engine is pinned lane threads and microsecond latency; a console is
bursty request handling whose latency nobody measures. Colocated, a burst of
console traffic competes for CPU and heap with lane threads, and the symptom is
p99 in the query engine — the one number a customer measures.

Restarting a console should cost nothing. Restarting the engine costs a warm-up
and a registry recovery pass.

## How it is put together

Three layers. **Services** are typed calls over the SDK and hold no per-browser
state, which is what lets the console scale sideways. **`/api/v1`** is versioned
JSON — everything a screen can do goes through it. **Screens** are rendered by
the server and then made live, so a page works before its JavaScript does.

One engine subscription serves every browser watching the same view. Ten
analysts on one dashboard are ten browser connections and *one* subscriber on
the engine: the same claim the engine makes about queries, kept by the console
rather than quietly broken by it.
