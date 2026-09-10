# Waves 5–7 merge record — the state `main` is being moved to

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Waves | 5 (E4 Durable), 6 (E5 Backfill & serving), 7 (E6 Gateways & DX) |
| Date | 2026-09-10 |
| Verdict | **MERGED WITHOUT A PASSING PERFORMANCE GATE.** Deliberate, and recorded here so nobody has to reconstruct why |

## Why this record exists

`main` had been at Wave 2 for 81 commits while waves 3 through 7 were built on `develop`. The
convention — `main` moves once per wave, at a gate, with an evidence pack — was written on the
assumption that gates could be evaluated. Gates P2 and P3 cannot be, on this hardware, and no amount
of code changes that. Holding `main` at Wave 2 indefinitely was making the branch useless to release
or demo from without protecting anything.

So the convention is being **suspended for these three waves and the reason written down**, rather
than quietly abandoned. The gate debt is real, still outstanding, and named below.

## What is unmeasurable, and why

Gates P2 (Profile A ≥ 1.2 M rec/s per lane, ≥ 90 % scaling 1→8 lanes) and P3 (Profile B ≥ 350 k
rec/s per lane) need **16 physical homogeneous cores at ≥ 3.0 GHz, quiet**.

The development machine is a 12-physical-core heterogeneous laptop SoC — Zen 5 plus Zen 5c — with
SMT and frequency scaling, shared with an IDE and a browser. A one-lane and an eight-lane
measurement are taken at different clock speeds on cores of different sizes, so the ratio measures
the power envelope at least as much as the software. **The 2.7× at eight lanes recorded in the
benchmarks is not evidence of anything** and is written down only so nobody re-derives it and
believes it.

This is a purchase order, not an engineering task. It is the single oldest outstanding item in the
project.

## What was built, waves 5–7

| Piece | Where |
|---|---|
| Symmetric hash join, incremental both ways | `SymmetricHashJoin`, `JoinSide` |
| Key-partitioned ingest so a join can span lanes | `LaneExchange`, partitioned pump |
| Checkpoint and recovery across a join | `CheckpointRecoveryTest`, `JoinRecoveryTest` |
| Filter pushdown with capability negotiation | `pravaha-connect`, `PushdownEquivalenceTest` |
| Effectively-once output for non-transactional sinks | dedup sink |
| Lookup joins, async, on virtual threads, ordered | `LookupJoinOperator` |
| JDBC plugin, proven against real PostgreSQL | `plugins/pravaha-plugin-jdbc`, `PostgresJdbcIT` |
| Aerospike plugin, proven against a real server | `plugins/pravaha-plugin-aerospike`, `AerospikePluginIT` |
| Snapshot→CDC splice, throttle, blue/green cutover | `pravaha-backfill` |
| Served views with declared consistency | `pravaha-serving` |
| Keyed `GROUP BY` over a bounded view read | `KeyedAggregate` |
| Arrow Flight SQL gateway (ADR-030) | `pravaha-flight` |
| Java and Python SDKs — connect, query, iterate | `sdk/` |
| Authentication and authorization (ADR-031) | `pravaha-security` |
| Read admission control | `ReadAdmission` |
| Prepared statements (ADR-032) | `BoundParameters`, `StatementHandle` |

## What passes

| Criterion | Status |
|---|---|
| The README's query runs verbatim against real Aerospike | **PASS** — `AerospikeContinuousQueryIT`, 14.4 s, container, not a mock |
| Join survives a crash: interrupted run equals uninterrupted | **PASS** |
| Filter pushdown does not change results | **PASS** — `PushdownEquivalenceTest` |
| SQL surface documented and enforced by test | **PASS** — `SqlSupportMatrixTest`, `docs/SQL_SUPPORT.md` |
| Authorization enforced at the Pravaha layer, not the store | **PASS** — ADR-031 |
| Both SDKs against the real server, no fakes | **PASS** |
| Full build green | **PASS** — `./mvnw -Ppython clean verify`, 1 195 Java unit tests, 46 Python |
| Profile A / Profile B throughput | **NOT MEASURABLE** — see above |

## What is knowingly outstanding

Named so the merge does not read as completion:

| Gap | Wave |
|---|---|
| Query registration and lifecycle — **no surface exists** | 7 |
| Subscriptions over Flight | 7 |
| Parameters for continuous queries (ADR-032's deferred half) | 7 |
| The console — three ADRs, no code | 7 |
| Aligned checkpoint barriers across the exchange | 5 |
| Self-joins; outer joins between streams | 5 |
| Range indexes, read replicas | 6 |
| Clustering, HA, Raft — no module | 8 |
| Operability, time-travel debug | 9 |
| GA: further plugins, `WITH RECURSIVE`, Nexmark, soak, security review | 10 |

## The honest summary

A working streaming SQL engine with a client protocol, two SDKs, authentication, authorization and
five source plugins, proven end to end against real Aerospike and real PostgreSQL. **No user
interface, no clustering, no way yet to register a query as a persistent running thing.** Roughly
wave 7 of 10.
