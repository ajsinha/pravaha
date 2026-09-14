# ADR-029: the Aerospike plugin ships scan-based ingest only

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; **declared, not enforced** — see ADR-028. `QueryRegistry` contains no reference to any capability type, so nothing is refused at registration |
| Date | 2026-09-10 |
| Deciders | Ashutosh Sinha |
| Amends | §19.1, which enumerated four strategies as the resolution to gap G4 |

## Decision

**The Aerospike plugin implements `lut-scan` and nothing else.** The three XDR and write-path
strategies described in §19.1 are not planned, not deferred.

This is a scope decision by the owner, and it is recorded because it changes what the product may
claim rather than only what it contains.

## What this costs, stated plainly

`lut-scan` is a scan with a server-side filter on last-update-time. Three of its properties are
consequences of scanning and no amount of implementation effort removes them:

**Deletes are invisible.** A deleted record is absent from the next scan, and absence is
indistinguishable from a record that never existed. **A maintained view over Aerospike will keep
serving deleted rows indefinitely.** For an aggregate this means counts and sums that only ever go
up. This is the sharpest edge of the decision and the one most likely to surprise somebody who has
not read this file.

**Intra-interval overwrites collapse.** Two writes between scans are seen as one, with the final
value only. A view that reads current state is right; anything that sums per-change deltas is not.

**No before-image**, so an update arrives as an insert with nothing to retract, and the delivery
guarantee ceiling for any query sourced from Aerospike is **at-least-once**.

The engine is told all of this through the capability declaration, so a query that needs more is
refused at registration rather than discovering it in production. That is the mechanism that makes
this decision safe to take: the limitation is not hidden, it is negotiated.

## What follows for anyone hitting those limits

Three routes, in the order they are usually right:

1. **Bound the query with a window.** A windowed aggregate expires its own state, so a deleted
   record stops being counted when its window closes rather than never.
2. **Write tombstones from the application** — a status bin rather than a record delete. The scan
   then sees the change, because it is a write.
3. **Enterprise XDR**, which is what the three unimplemented strategies were for. Should that
   licence ever exist, `AerospikeStrategy` is the seam: the capability declaration and the engine's
   guarantee computation already vary by strategy, so adding one is a plugin change and not an
   engine change.

## Alternatives considered

**Ship the XDR strategies unexercised.** They are described in enough detail in §19.1 to write.
Rejected, and this is the same rule as ADR-028: a connector that declares capabilities it has not
demonstrated is worse than no connector. `xdr-kafka` would declare exactly-once and replayable
offsets, and an untested exactly-once claim is the one that costs somebody a reconciliation months
later, with the original author gone. There is no Enterprise licence here to test against, so the
claim could not be earned.

**Leave them as "not implemented yet".** What the code said before this decision, and it implies a
schedule. Rejected: an indefinite "yet" is how a roadmap accumulates items nobody has decided
about. Not planned is a decision; not yet is a deferral, and this is the former.

**Drop the strategy enum and hard-code the scan.** Simpler by one type. Rejected because the enum is
what makes the refusal explain itself -- a user who configures `xdr-kafka` gets a message naming the
licence requirement and the alternative, rather than "unknown configuration value".

## Consequences

- §19.1's four-strategy table is now one implemented row and three recorded as out of scope.
- Aerospike-sourced queries are capped at at-least-once, and the Aerospike Connect comparison in §2
  should not claim change-feed parity.
- **M6, the first defensible demo**, is unaffected for incremental compute and pushdown over
  Aerospike, and *is* affected for anything demonstrating deletes. A demo dataset that deletes
  records will show stale rows, and the honest demo either avoids deletes or shows the window
  expiring them.
- The plugin is tested against a real Aerospike Community server in Docker, which is the edition
  this decision commits to.
