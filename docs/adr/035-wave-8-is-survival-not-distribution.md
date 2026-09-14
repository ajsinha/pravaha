# ADR-035: Wave 8 is survival on one node, not distribution across several

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — scope decision, not yet built |
| Date | 2026-09-14 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-034 (distribution deferred), ADR-008 (aligned checkpoints), ADR-009 (embedded Raft metadata), ADR-006 (tiered state) |

## Decision

`implementation_plan.md` §11 gives Wave 8 to **E7 — Ratis metadata, membership, assignment,
rebalance, failover, multi-tenancy, elastic rescale**, over sprints 39–45. That wave is not built and
will not be built next.

ADR-034 deferred distribution. E7 is the distribution wave, so the plan and the architecture have
been contradicting each other since — and the plan is the one that is wrong. Wave 8 becomes a short
wave about a single node **surviving**: its own restart, its own operator's mistakes, and its own
half-finished mechanisms.

Four items, in dependency order.

## 1. Node ownership of durable state

Two nodes pointed at one `pravaha.checkpoint.directory` share a per-query subdirectory with no
node-id namespacing, and each `prune(keep)` deletes whatever is oldest across both — so the
survivors are an unpredictable mix and a restart restores from the other node's state (CFG-13). Two
nodes sharing one `pravaha.registry.journal` take no lock, and the interleaved appends **replay
cleanly**, which is worse than corruption: a node restarts and comes up running a query only the
other node ever registered (CFG-14). Both are reachable from two lines of YAML and both are silent.

This is first because the other three items all write durable state, and each would inherit the same
hole.

The decision to make is what owns a node's state, and the hard part is not the lock — it is what a
**stale** lock means. A node that refuses to start because it crashed is a worse failure than the one
being prevented, and a lock that breaks itself automatically is not a lock. The shape that fits this
codebase is the one `refuseAccidentalOpenServer` already uses: refuse, explain, and offer a named
override the operator has to type on purpose.

Namespacing by node id is rejected as the primary fix: it silently orphans every checkpoint an
existing deployment holds, which is the same class of silent-wrong-answer being fixed.

## 2. Aligned checkpoint barriers

ADR-008 promises aligned checkpoints and exactly-once state. What exists is a periodic checkpointer
that submits a control task per lane and waits for each. That is correct for one lane, which is what
a keyed aggregate gets (ADR-034), and it is not a barrier: with several lanes, each snapshots at its
own point in its own stream.

Two defects found during QA are the argument for doing this properly rather than patching. A
control-task ticket counted completions rather than naming a task, so a checkpoint's wait could be
satisfied by a concurrent watermark advance and the checkpoint stored a `null` (PF-5); and a lane
recorded its failure after releasing the ticket, so a refused snapshot could be read as a successful
one (PF-7, PF-9). Both are fixed. Both were reachable *only* because the checkpoint path and the
watermark path share a mechanism with no identity in it.

A real barrier — a marker that flows with the rows rather than a control task racing them — removes
the class, not the three instances.

## 3. Standby and checkpoint failover

HA without distribution: a second process that holds no lanes, tails the checkpoint directory and the
registry journal, and can take over. No consensus, no membership protocol, no Ratis. ADR-009 stays
deferred with ADR-034.

The honest claim this buys is **recovery time**, not continuity: a takeover loses everything since
the last checkpoint and says so. That is worth building because the current answer to "the node
died" is "start it again and hope the checkpoint directory is intact" — and CFG-13 says the
checkpoint directory may not be.

Depends on item 1. A standby that tails the same directory as its primary *is* the two-node sharing
case, deliberately, and it cannot be built before the rules for it exist.

## 4. Wire the three mechanisms that are built and unreachable

`DeadLetterQueue`/`FileDeadLetterQueue`, `L0StateMap`, and `ChangelogAnalysis` are implemented and
tested, and no production code outside their own packages references any of them. Verified by grep at
`1df9b4d`: zero references for the first two, one javadoc mention for the third.

Unreachable code is worse than absent code. It reads as a capability in every document that describes
the system, it passes its own tests, and it is a decision nobody made — no one chose not to have a
dead-letter queue; it just never got wired.

Each is a small piece of work. The wave ends with each either reachable from a supported path or
deleted, and deletion is an acceptable outcome for any of the three.

## What is explicitly not in Wave 8

Membership, assignment, rebalance, elastic rescale, multi-tenancy, Ratis, and any form of multi-node
execution. These remain E7's, and E7 remains deferred under ADR-034.

Nexmark, the time-travel debugger, `CREATE CONTINUOUS QUERY` and the Spring Boot starter are waves 9
and 10.

## Consequences

`implementation_plan.md` §11 and the roadmap table in `README.md` both describe Wave 8 as "Cluster
and HA". Both now overstate it and must be corrected in the same change that accepts this ADR, or
this becomes another documented capability that does not exist — which is the failure mode item 4
exists to close.

The P2 and P3 performance gates stay unmet and unaffected; they need homogeneous reference hardware
(`docs/gates/wave-3/README.md`), and nothing in this wave changes that.

Wave 8 gets a gate pack, which waves 5 and 6 never got.
