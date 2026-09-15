# Gate P7 — end of Wave 8 (E7 Survival on one node)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 8 of 11 — E7, rescoped by [ADR-035](../../adr/035-wave-8-is-survival-not-distribution.md) |
| Gate | P7, for milestone **M8 "It survives itself"** |
| Written | 2026-09-15, retrospectively |
| Verdict | **Wave complete. Gate P7 passed on mechanism, not passed on demonstration** — both criteria are built, tested at unit level, and neither has been exercised against a real killed process |

> ADR-035 says "Wave 8 gets a gate pack, which waves 5 and 6 never got." It did not get one at the
> time. This is that pack, written retrospectively from the evidence in the repository.

## The gate

M8: **"A killed node restarts onto its own state and nothing else's; a standby takes over and says
what it lost."** Two criteria, and unlike P2/P3/P4 neither of them needs reference hardware — they
are correctness properties, which is why this gate is closable on the machine that exists.

| Criterion | Status |
|---|---|
| A killed node restarts onto **its own** state | **MECHANISM PASSED.** `StateOwnership` claims the checkpoint root and the registry journal directory with a `.pravaha-owner` marker naming node id, host, Flight port and pid, refreshed on a 30-second lease. `PRV-4003` refuses another node or a second live instance of this one; `PRV-4004` refuses an *unreadable* marker rather than assuming the directory free, which is the failure that would otherwise be silent. `StateOwnershipTest` covers both |
| …and **nothing else's** | **PASSED.** The path is keyed on node id, so two nodes cannot share a directory. `pravaha.state.allow-shared` is the named override and has to be typed |
| A crash restart is not mistaken for a conflict | **PASSED.** An *expired* claim under the same node id is reclaimed automatically — which is exactly what a crash restart looks like from the outside (W8-1, closing CFG-13 and CFG-14) |
| A standby takes over | **MECHANISM PASSED.** `StandbyWatch`, `pravaha.standby.enabled`, configured with the **same** `pravaha.node.id` as the primary on purpose: `StateOwnership` already distinguishes "our id, claim expired" from "our id, claim live", so one mechanism decides ownership rather than two that can disagree. Refused at startup without `pravaha.checkpoint.directory`. A standby watching another node's directory never promotes and says so |
| …and **says what it lost** | **PASSED.** `Takeover.describe()` states the gap explicitly: the promotion names **recovery time, not continuity**. `StandbyWatchTest` |
| **Demonstrated against a real kill** | **NOT DONE.** As with Gate P4, no test kills an operating-system process. The lease, the marker and the promotion are exercised in-process. A `SIGKILL` that leaves a marker mid-refresh is the case the design is *for* and the case nobody has run |

## What Wave 8 delivered beyond the gate

| Piece | State |
|---|---|
| Aligned checkpoint barriers, every input | ✅ `freezeIngest` holds every source between rows while each lane is handed a marker, so offsets and state name the same rows; a lane cuts its batch *at* the marker. `AlignedCheckpointBarrierTest`, `ControlTaskBarrierTest`, all four seed-proven (W8-2…W8-5) |
| Aligned barriers, the exchange | ❌ still not cut. A row in flight between lanes causes a refusal, not a loss |
| Dead-letter queue | ✅ `pravaha run --dlq <file>`; a server still has no `pravaha.dlq.*` key (W8-11, open) |
| `L0StateMap` | 🗑️ deleted — fixed-width keys against state whose group key can be a `STRING`, never referenced from any `src/main` (W8-12) |
| `ChangelogAnalysis` | ⚠️ kept, unwired, deliberately. Nothing binds a query to a sink, so there is nothing to refuse; `ErrcSqlTest` asserts that precondition and fails the moment a sink binding appears (W8-13) |
| The register could not see a `W8-` finding | ✅ fixed (W8-7) |

## What it did not do

Membership, assignment, rebalance, elastic rescale, multi-tenancy, Ratis, any multi-node execution —
all still E7's and still deferred by [ADR-034](../../adr/034-distribution-deferred.md).
`DeduplicatingSink` is not wired, so output is effectively-once rather than exactly-once. The
windowed aggregate still keys state by a 64-bit digest (W8-14, open, and no test can prove a fix).

## What would close this gate

One integration test that starts a node as a real process, `SIGKILL`s it, restarts it onto the same
directory, and asserts both the reclaim and the answers. The same harness closes Gate P4's missing
criterion, which is why it is the highest-value untaken test in the repository.
