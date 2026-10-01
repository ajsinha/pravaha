# ADR-040: the remote connector — an application becomes a source

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — design settled, build scheduled after cluster mode |
| Date | 2026-09-16 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-013 (Z-sets), ADR-008 (aligned checkpoints), ADR-030 (scope tiers), ADR-039 (GA order), `../CONNECTORS.md` |

## Decision

A **remote connector** is a small library — Java or Python — that an application embeds, and which
streams rows to a Pravaha node over the network. The application becomes a source without Pravaha
having a plugin for it.

Three decisions, each of which forks the build, are settled here.

1. **The transport is Arrow Flight `DoPut`.** Not a bespoke TCP protocol.
2. **The guarantee is at-least-once, with server-side deduplication on `(agent_id, sequence)`.**
3. **It is built after cluster mode, before GA**, per ADR-039's order. This document exists now so
   the design is settled while it is fresh; the code is not started.

## Why this feature and not another connector

Every connector built so far answers one source. This one answers the long tail: any application
that can import a library becomes a source, and the set of systems Pravaha can ingest from stops
being the set somebody wrote a plugin for.

That is also the shape the engine's position argues for. A single node holding thousands of
continuous queries is an operating point the distributed-first engines serve badly, and an agent
embedded inside the application that owns the data is the same argument carried one step further:
the data does not have to reach a broker before it can be asked a question.

## Why Flight `DoPut` rather than a socket

The obvious design is a length-prefixed protocol over TCP, and it is the wrong one, because a
transport is not the framing. It is the framing **plus** TLS, authentication, schema negotiation,
flow control, version skew between an agent deployed eighteen months ago and the node it reconnects
to, and a client library in each language. Flight has all of it, `DoPut` is precisely "a client
streams record batches to a server", and this repository already runs a Flight server and already
speaks Arrow.

**What it costs, stated before it is agreed to.** The agent pulls Arrow into the host application —
tens of megabytes of jars inside somebody else's process, with whatever version conflicts that
implies. That is a real cost and it is the one argument for the minimal protocol.

It is accepted anyway, because the alternative is not "a smaller dependency", it is "hand-rolled
TLS and authentication on an inbound write path". The dependency-free framed protocol stays on the
roadmap behind the same SPI, to be built when a concrete embedder cannot take Arrow — not before.

## Why at-least-once, and why deduplication is not optional

**A duplicate row is not a transient error in this engine.** Elsewhere at-least-once delivery is a
tolerable default because a repeated row is overwritten or re-reduced. Here a row carries a weight,
a duplicate insert is a real `+1`, and an aggregate that absorbs one is not briefly wrong — it is
wrong for ever, with nothing in the answer to say so.

So the guarantee and its mechanism are one decision, not two. The agent numbers every row; the
server deduplicates on `(agent_id, sequence)` inside a replay window; and that window is checkpointed
with the lane state it belongs to, so recovery does not re-admit what a reconnect already replayed.
An at-least-once remote source without this is a correctness defect wearing a delivery guarantee's
clothes.

**Exactly-once is not offered by default**, because it cannot be honoured from this side. It requires
the *sender* to hold a durable, replayable buffer and to honour a resume-from-sequence on reconnect —
a disk, a flush policy and a file to operate, inside an application that did not ask for one. It is
opt-in, declared through `SourceCapabilities`, and — per the TCK gap already recorded — a claim that
nothing verifies today.

## What the design has to get right

**Backpressure blocks the sender, and the SDK says so in its first paragraph.** Nothing spills; a
dropped row whose retraction arrives later leaves an answer that can never be corrected. So when the
inbox passes its high-water mark the server stops reading and the agent's write blocks. The agent
runs inside somebody else's request path, where a blocking send is an incident, so the embedder
chooses the policy explicitly — block, buffer-then-block, or fail fast with an error they handle.
**Silently dropping is not one of the choices.**

**An idle agent must not freeze the watermark.** Each agent is a partition and the stream's watermark
is the minimum across partitions, so one agent whose host application goes quiet overnight closes no
windows on any query reading that stream — the query reports `RUNNING` and the view stays empty,
which is `CONTINUOUS_QUERIES.md` §2's silent failure triggered by a machine the operator does not
own. An agent that has not sent for a configured interval leaves the minimum, and rejoins on
reconnect with its late rows arriving as ordinary corrections.

**The push/pull inversion stays behind the existing SPI.** A `PartitionReader` drains a bounded
per-agent buffer the endpoint fills. The lane machinery, the TCK and every guarantee in
`../CONNECTORS.md` are untouched, and a remote source is a source like any other from the engine's
side.

## Security: this is the first inbound write path

Until now Pravaha read from stores it was configured to read. An agent **writes**, and a compromised
or careless one forges rows into a view somebody is making decisions on. Enforced in Pravaha, not
delegated:

- **Authentication** per agent — mTLS or a per-agent token, never a shared secret.
- **Authorization** naming exactly which streams an identity may write. An agent that can write
  anywhere is a hole regardless of how well it is authenticated.
- **Schema pinned at the handshake**, compared against the declared stream and refused at connect —
  not at the first row that does not fit.
- **Rate limits per agent**, so one misbehaving embedder cannot starve a node its own queries share.
- **Audited**: connect, refusal and disconnect, like every other refusal.

## What would make this wrong

An embedder who cannot take the Arrow dependency and needs the framed protocol first; or a workload
where the durable-log burden of exactly-once is acceptable and at-least-once is not, which would make
the opt-in the default. Neither is true of any known user today.
