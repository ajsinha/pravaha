# Gate M6 — end of Wave 6 (E5 Backfill & serving)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 6 of 11 — E5 Backfill & serving |
| Gate | **M6 "First defensible demo"** — an external/customer demo, not a P-gate |
| Written | 2026-09-15, retrospectively |
| Verdict | **Wave complete in scope. Gate M6 not passed** — the demo has never been performed, and two of its three claims are unmeasured |

> **Written after the fact, from evidence in the repository.** See the note in
> [`../wave-5`](../wave-5/README.md); the same caveat applies.

## The gate

M6 is different in kind from the P-gates around it. It is not a build criterion but an **external
demo**: *"Incremental compute over Aerospike with pushdown; 3 years backfilled safely; point queries
in µs — no other system involved."* The wave's own exit criterion adds **"W3 point lookup ≤ 200 µs"**.

| Claim | Status |
|---|---|
| Incremental compute over Aerospike with pushdown | **SUPPORTED.** `AerospikeContinuousQueryIT` runs the README's own SQL against a real Aerospike server with the `WHERE` pushed into the store. This is the one claim that has been demonstrated end to end |
| No other system involved | **SUPPORTED.** The pipeline is Aerospike → Pravaha → served view. No Kafka, no Flink, no external state store |
| **3 years backfilled safely** | **NOT DEMONSTRATED.** `pravaha-backfill` is built — `SplicedReader`, phase-explicit offsets, a bounded off-heap change buffer that fails loudly, dedup keyed on *changed* keys rather than every key in the snapshot, which is what would make it survivable at that size. Nobody has run three years of anything through it. The largest backfill in any test is a fixture |
| **Point queries in µs / W3 ≤ 200 µs** | **NOT MEASURED.** No latency benchmark exists for `ServedView.get`. The same hardware confound as P2/P3 applies to any figure this machine would produce, but the more basic problem is that the benchmark has not been written |
| **The demo itself** | **NOT PERFORMED.** No demo script, no recorded run, no audience |

So: the wave built what E5 scoped, and the milestone it exists to reach is a demo that has not
happened. Both statements are true. Recording it this way follows the precedent set by Gate P6 — a
gate quietly redefined to match what was built is not a gate.

## What Wave 6 delivered

| Piece | Where |
|---|---|
| Snapshot→CDC splice, phase-explicit offsets | `pravaha-backfill`: `SplicedReader` |
| Adaptive throttling of the history scan only | `BackfillThrottle` |
| Blue/green cutover and rollback on a frontier, not a moment | `ShadowDeployment` |
| Served views; committed and pending kept apart | `pravaha-serving`: `ServedView` |
| Consistency modes `LATEST`, `CONSISTENT`, `AT_LEAST`; every answer carries its staleness | `ServedView.get` |

**`AS_OF` is refused** because a view holds the present — a deliberate refusal, not a gap.

## What it did not do

Range indexes and read replicas: not built. The gRPC/Avatica surface was superseded by
[ADR-030](../../adr/030-flight-sql.md) — one Flight SQL surface replaces both, which is a scope
change rather than a shortfall.

**Known open defect against this wave's serving path:** `I-6` (GA-BLOCKER) — three of the four
consistency modes listed above never leave the client. `ViewQuery.run` reads `view.scan()` only, and
`ServedView.get(Consistency, …)` has no transport caller. The modes are implemented in the view and
unreachable through the wire, so the row above describes the engine and not the product.

## What would close this gate

A written demo script, a `ServedView.get` latency benchmark, and one backfill run at a size worth
quoting. The demo is the gate; the other two are what make it defensible.
