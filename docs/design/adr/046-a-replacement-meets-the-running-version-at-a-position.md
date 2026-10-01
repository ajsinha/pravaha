# ADR-046: a replacement meets the running version at a position, not at a moment

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `QueryReplacements` in `pravaha-registry`, `OffsetSplicedReader` in `pravaha-backfill` |
| Date | 2026-09-19 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-016 (blue/green updates), ADR-015 (buffer CDC, then snapshot), ADR-043 (a query's sink), design §16.1–16.3 |

## Decision

A blue/green replacement cuts over when the two versions of a query have consumed **exactly the
same input**, compared as source positions per partition, with both feeds stopped. Not at a
wall-clock instant, not when the candidate "looks caught up", and not after a duration.

Four decisions follow from it and are recorded here because the design document does not settle
them.

**1. The seam of a backfill is an offset, not a key-and-version deduplication.** Design §16.1 and
ADR-015 describe a table snapshot spliced onto a change feed, with a bounded dedupe window keyed by
the store's own version. `SplicedReader` implements that and stays unwired: no source plugin here
exposes a snapshot read separately from its change feed, and `postgres-cdc` does its own initial
snapshot behind its own offset. What every plugin here does provide is an ordered log with
replayable positions, so the replacement reads the history to the exact position the running version
has reached and then opens a reader **at** that position — `OffsetSplicedReader`. The history is
polled one record at a time, because a batch that straddles the seam cannot be stopped in the middle
of itself; a history that runs out without reaching the seam stops with `PRV-4013` rather than
reading past it and delivering the overlap twice.

**2. What readers and subscribers see at the cutover.** A reader of the name resolves it through the
view catalogue at the moment of its read, so it reads one version's view or the other's and never a
mixture; because both have consumed the same input, neither is behind. A **subscriber** — plain or
from a snapshot — is delivered every commit the replaced version ever made, the last of them taken
while it was paused at the seam, and is then ended with `PRV-4019`. It is told rather than quietly
handed the new version's changes: a subscriber applying changes by weight would otherwise hold a copy
that is half one query's answer and half another's, with nothing in the stream to say so, and a
snapshot subscription's next snapshot is the thing it actually needs and cannot be expressed as a
diff.

**3. The sink follows the name, at a checkpoint boundary.** After the cutover the table a sink
maintains is the new version's answer. The replaced version checkpoints with its feed stopped, which
commits everything its sink was written; the new version's checkpoint ids continue above that one's,
because the SPI's transaction labels only increase across a change of computation as well as across a
restart; what the sink holds is recorded on the new version as a carried section, and the delivery
that attaches is owed the **difference** between that and the new version's view, written as one
batch. The journal's cutover record is written between those two steps, which is what makes a crash
recoverable in either direction.

**4. A replacement survives a restart.** A backfill can run for hours. The journal records a pending
replacement (`P`), the name's change of version (`C`, one record for a cutover or a rollback) and the
end of one that never took the name (`E`). A restart brings the name back as the version that was
serving it — the journal never said otherwise — and starts the candidate again, resuming from its own
checkpoints. A version that took its name at a cutover **keeps the directory it backfilled into**:
the directory travels in the registration rather than the files being moved, because a move leaves a
window in which a crash has the name pointing at a directory holding the other version's state, and
restoring one version's operator state into another's plan is the kind of wrong nothing reports.

## Alternatives considered

**Swap at a wall-clock instant.** What "cut over at 14:00" means for two queries running at slightly
different speeds is either records dropped between them or records emitted from both. The failure is
invisible: the numbers are merely a little off.

**Cut over when the candidate has read as many rows, or has run for long enough.** Both are proxies
for the thing that matters, and both are wrong exactly when the input rate changes — which is when
somebody is most likely to be deploying.

**Take the name at once and let the new version warm up.** This is what the old `PRV-2072` refusal of
`CREATE OR REPLACE` was protecting against: it takes the answer away from whoever is reading it, and
the view they get back is an aggregate missing its history.

**Move the checkpoint files at the cutover.** Simpler to describe, and it has a window where a crash
leaves the name pointing at the wrong version's state. The directory travelling with the registration
has no such window.

**Let a shared computation be replaced.** Refused (`PRV-8003`). A replacement moves one name, and a
computation answering to several cannot tell which name a subscriber arrived through — so some
subscribers would go on following the old version under a name that now answers the new one.

## Consequences

- `CREATE OR REPLACE CONTINUOUS QUERY` is built, with `WITH (backfill, backfill.rate.limit, cutover,
  rollback.retention)`. The design's `backfill.parallelism`, `backfill.window` and
  `backfill.adaptive` are refused by name with `PRV-4018`: a backfill reads each partition once,
  from the beginning, at the rate an operator sets, and there is no probe of the store's own latency
  to adapt to.
- A replacement requires the **administer** permission on the name — reading its status included,
  because a candidate's SQL and its progress describe a query the caller may not be allowed to read.
- A source that cannot be replayed, or whose positions do not order the records within a partition
  (a table scan reports where its pass began), cannot be backfilled: `PRV-4018`, before anything
  starts.
- `SplicedReader` stays in `pravaha-backfill`, tested and unwired, for the first plugin that exposes
  a snapshot read separately from its change feed. That is a known debt and is recorded as one.
- The rollback window costs a retained computation: the replaced version keeps running, fed and
  committing, for `rollback.retention` (an hour by default) or until somebody confirms the cutover.
  That is what makes a rollback one swap rather than a second backfill.

## Notes

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number and
gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning behind a
decision that was later reversed is usually the most useful thing in the directory.
