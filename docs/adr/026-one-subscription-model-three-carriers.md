# ADR-026: one subscription model behind three carriers

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **one carrier built** — Flight/gRPC. There is no WebSocket implementation anywhere; the SSE that exists is the Python console re-encoding the SDK stream (ADR-024), not an engine carrier |
| Date | 2026-09-09 |
| Deciders | Ashutosh Sinha |

## Decision

**One subscription model, three carriers.** gRPC, WebSocket and SSE are transports over the same
subscription semantics — the same lifecycle, the same delivery modes, the same conflation policy,
the same error taxonomy. A carrier may not invent semantics.

Within a tick, a batch is **encoded once per query and written N times**, once per subscriber socket.

## Alternatives considered

**gRPC only.** Cleanest, and unusable from a browser without a proxy — which puts the console, the
most visible consumer, behind a translation layer that would inevitably acquire semantics of its own.

**A carrier-specific model per transport.** What happens by default when carriers are added one at a
time. Rejected because three subtly different subscription models is three sets of edge cases, three
sets of client bugs, and a fan-out node that cannot simply be another subscriber to the level above.

**Encoding per subscriber.** The obvious implementation, and the one that turns a thousand clients
into an outage: a thousand subscribers at 20 Hz is 20 000 serialisations a second instead of 20.

## Rationale and consequences

**Encode once, write N times** makes the expensive work scale with *query* count and the cheap work
scale with *subscriber* count. A socket write of an already-encoded Arrow buffer is a copy; that is
the difference between a thousand subscribers being a capacity-planning line item and being an
incident.

**A lane never sees a subscriber.** It writes to a conflating, drop-oldest tap ring and returns, so
lane cost is O(1) in subscriber count ([ADR-011](011-ui-out-of-the-data-path.md)). A slow browser
conflates and reports `dropped_count`, or is disconnected under `RELIABLE`; it never backpressures a
production query.

**Because the model is defined once, hierarchical fan-out is free:** a fan-out node is simply another
subscriber to the level above it, and nothing in the model changes when subscribers outgrow a node.
