# Gate P6 — end of Wave 7 (E6 Gateways & DX)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 7 of 10 — E6 Gateways & developer experience |
| Date | 2026-09-11 |
| Verdict | **Wave complete. Gate P6 not passed** — its criteria are unmeasured, two of them for the same hardware reason as P2 and P3 |

> An earlier version of this file was a *merge record* for waves 5–7, written when Wave 7 was still
> in progress and `main` had been stale for 81 commits. Wave 7 has since finished; this is its gate
> record, and it replaces that note rather than sitting beside it.

## The gate, honestly

**M7 "It's usable"** asks for: the Python client at 1 M rows/s, DBeaver connecting, a 2-second deploy.

| Criterion | Status |
|---|---|
| Python client at 1 M rows/s | **NOT MEASURED.** No throughput benchmark exists for the client, and this machine could not produce a defensible figure if one did — the same confound as Gates P2 and P3 |
| DBeaver connects | **NOT VERIFIED.** The server speaks Flight SQL, so the upstream JDBC driver *should* work, and "should" is not a gate. Nobody has pointed DBeaver at it |
| Two-second deploy | **NOT MEASURED.** Registration is sub-second in tests, but "deploy" in the plan means something broader and nobody has timed it end to end |

So the wave is finished and the gate is not passed. Both statements are true and they are not in
tension: the work E6 scoped is built and tested, and the *evidence* the gate asks for has not been
gathered. Recording it this way is the point — a gate quietly redefined to match what was built is
not a gate.

## What Wave 7 delivered

| Piece | Where |
|---|---|
| Arrow Flight SQL gateway (ADR-030) | `pravaha-flight` |
| SQL over maintained views, with consistency and staleness | `pravaha-serving` |
| Prepared statements, bound at plan-build time (ADR-032) | `BoundParameters`, `StatementHandle` |
| Authentication, authorization, audit (ADR-031) | `pravaha-security` |
| Read admission control | `ReadAdmission` |
| **Query registration and lifecycle (ADR-025)** | `pravaha-registry` |
| **Subscriptions**, engine side and over the wire (ADR-026) | `Subscription`, Flight tickets |
| **Both SDKs**: connect, query, prepare, register, subscribe | `sdk/pravaha-sdk-java*`, `sdk/python` |
| **CLI against a server**: query, register, queries, subscribe, lifecycle | `pravaha-cli` |
| **The console** (ADR-024) | `console/` |
| Retention on views; a match window on joins | `Retention`, `JoinOperator` |
| Parameters for continuous queries, classified (ADR-032) | `ParameterPlacement` |

## What this wave changed its mind about

Worth recording, because each was a correction rather than an addition.

**Views grew without limit.** A pass-through view gained a row per event forever and answered that
by failing at a ceiling. "The node dies eventually, loudly" is not a design. Views now forget, by
event-time age, with a default applied unless a registration chooses otherwise.

**Retention was briefly expressed in rows as well as time.** Removed: "the last million rows" is four
hours on a quiet day and twenty minutes on a busy one, so the view's *meaning* would have depended on
throughput. Counting rows is a capacity ceiling, not a retention policy, and the view already had one.

**A join held every unmatched row forever.** Fixed differently, and the difference is the lesson: a
view can forget because forgetting is a cache policy, while a join cannot, because evicting to fit
would silently lose matches the query asked for. So the bound went into the query's *meaning* — a
match window — where releasing old rows honours the definition instead of breaking it.

**`SecurityPolicy.PERMISSIVE` said one thing and did another.** Being a lambda, it implemented only
`mayRead` and kept the default `mayRegisterQuery`, which refuses anonymous callers. A policy named
PERMISSIVE permitted every read and then refused registration on a server with no authentication.

**Closing a client left the server holding its subscription.** A subscription is a call that does not
return; the server learns nobody is listening from a *cancellation*, and dropping the transport
without one left it attached, assembling batches for a client that had gone.

## What is knowingly outstanding

| Gap | Wave |
|---|---|
| Gate P2, P3, P6 evidence — **needs the reference hardware** | 3, 4, 7 |
| A temporal predicate in SQL (`BETWEEN b.t - INTERVAL '1' HOUR AND b.t`), so a join's window can be chosen per query | 5 |
| Checkpoint retention: `FileCheckpointStore.prune` exists and nothing calls it | 5 |
| Aligned checkpoint barriers across the exchange | 5 |
| Self-joins; outer joins between streams | 5 |
| Range indexes, read replicas | 6 |
| Column masking, per-column policy — out of ADR-031 until asked for | 7 |
| Clustering, HA, Raft — **no module** | 8 |
| Operability, time-travel debug | 9 |
| GA: further plugins, `WITH RECURSIVE`, Nexmark, soak, security review | 10 |

## The honest summary

A streaming SQL engine you can now *give a query to*: register it, subscribe to it, query it, watch
it in a console, and drive all of that from Java, Python or a shell. Proven end to end against real
Aerospike and real PostgreSQL.

No clustering. No performance evidence. Roughly wave 7 of 10.
