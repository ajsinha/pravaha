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

**A commit is staged once per query and serialised once per subscriber socket.** Everything above
the wire format is shared and is flat in subscriber count: `ViewSink` stages one change log for the
whole audience of a commit, each `Subscription` holds references into it rather than copies of its
rows, and what a commit does per subscriber is filter those references into that subscriber's
buffer and wake its thread. **The Arrow serialisation is per subscriber**, on that subscriber's own
call thread, and it is the one cost that does scale with subscriber count. That is the alternative
an earlier revision of this ADR named and rejected; it is what the built carrier does, it is
measured below, and Arrow Flight's server API offers no way to do otherwise.

## Alternatives considered

**gRPC only.** Cleanest, and unusable from a browser without a proxy — which puts the console, the
most visible consumer, behind a translation layer that would inevitably acquire semantics of its own.

**A carrier-specific model per transport.** What happens by default when carriers are added one at a
time. Rejected because three subtly different subscription models is three sets of edge cases, three
sets of client bugs, and a fan-out node that cannot simply be another subscriber to the level above.

**Staging per subscriber.** The obvious implementation, and the one that would turn a thousand
clients into an outage: a thousand subscribers on one query assembling a thousand change logs from
one commit, on the thread that committed it.

**Sharing the serialised batch across sockets.** What this ADR used to claim it did, under
"encode once, write N times". Not available: a batch reaches a client through its own
`ServerStreamListener`, which serialises the `VectorSchemaRoot` the call started with, and Arrow
Flight has no call that hands an already-serialised record batch to a second listener. Building one
means a Flight transport of our own, and the reason this ADR chose Flight is that we do not want
one. Recorded as rejected rather than as achieved, because a reader plans capacity from this.

## Rationale and consequences

**Stage once, serialise per socket** puts the work above the wire format on the *query*, where it
is paid once however many are watching, and leaves the wire format on the subscriber, where it is
paid per socket. The first half holds and is measured. The second half is a real cost and this ADR
no longer pretends otherwise: **a node's subscriber capacity is bounded by Arrow serialisation, and
that bound is linear in subscriber count.**

> **This ADR used to say "encode once, write N times" and no carrier has ever done it** (STRM-4).
> The sentence was aspirational, it sat in the paragraph a reader plans capacity from, and it named
> the thing the code actually does as the mistake that "turns a thousand clients into an outage".
>
> **Measured on this machine** (`SubscriptionIngestCostTest`), one query, 100 000 distinct keys per
> run, committing every 250 rows, at load average 10.36 over 24 processors. Two ladders, and the
> difference between them is the attribution:
>
> ```
> over Flight, connected and never reading:
>    0 -> 536 768 rows/s      in process, a consumer that returns at once:
>    1 -> 361 671 rows/s         0 -> 680 474 rows/s
>    5 -> 225 963 rows/s         1 -> 706 444 rows/s
>   20 -> 130 656 rows/s         5 -> 759 380 rows/s
>                                20 -> 663 204 rows/s
> ```
>
> The in-process ladder is **flat**: twenty subscribers cost the engine no more than one, which is
> the half of this decision the engine owns and is what the test asserts. The Flight ladder falls
> by roughly a factor of four from none to twenty, and everything that differs between the two
> ladders is the carrier — a `VectorSchemaRoot` per subscription and a serialisation of every batch
> on each call thread.
>
> So STRM-087's `-69 %` at N = 1 is the carrier's, not the engine's, and it is not the change log a
> commit stages for its audience: the in-process ladder stages exactly the same change log and does
> not move. Two candidate causes were carried in that case; this retires one of them.
>
> **What follows for a deployment.** Subscribers on one node are bounded by serialisation, not by
> threads (`SubscriptionThreadCostTest`) and not by the engine. Past that bound the answer this ADR
> already gives is hierarchical fan-out — a fan-out node is another subscriber to the level above —
> and not a larger node.

**A lane never sees a subscriber.** It writes to a conflating, drop-oldest tap ring and returns, so
lane cost is O(1) in subscriber count ([ADR-011](011-ui-out-of-the-data-path.md)). A slow browser
conflates and reports `dropped_count`, or is disconnected under `RELIABLE`; it never backpressures a
production query.

**Because the model is defined once, hierarchical fan-out is free:** a fan-out node is simply another
subscriber to the level above it, and nothing in the model changes when subscribers outgrow a node.
