# ADR-054: an ordered, exactly-once source is shared by meeting at an exact seam

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; being built (tranche B1 of `../REMAINING.md`) |
| Date | 2026-09-27 |
| Deciders | Ashutosh Sinha |
| Relates to | SRC-3 (one reader per binding), LANE-2 (one copy per shared lane), ADR-008 (a checkpoint is one cut), ADR-046 (a replacement meets the running version at a position) |

## Context

SRC-3 made one reader per binding feed every query on it. It did so only for at-least-once, unordered
sources, and gave the reason. A query that joins a reader already running is attached to the fan-out
*first* and then given a private catch-up reader for the history it missed. The catch-up reads to the
end of the source, not to where the shared reader stands, so the overlap arrives twice, and history and
live records arrive interleaved. A source promising exactly-once or order cannot pay that, so Kafka,
files, JDBC, CDC and Delta each keep a reader per query. A thousand queries over one topic read it a
thousand times.

The two readers can meet without overlap. ADR-046's replacement already joins history to the live
stream at the exact position the running version has reached. What SRC-3 lacked was a way to stop the
catch-up *exactly* at the shared reader's position, and a way to tell a joiner behind the reader from
one ahead of it. A record count cannot bound the read: Kafka's offsets have gaps (transaction markers),
so reading "the difference in offsets" records overshoots the seam.

## Decision

A source can declare two things, and when it declares both, its readers are shared exactly:

1. **Its positions are ordered** (`OrderedPositions` on the plugin): given two positions it handed out,
   which is earlier. This tells a joiner behind the reader from one ahead of it. After a restart, members
   restore from checkpoints taken at different moments, so both cases happen.
2. **Its readers can stop at a position** (`BoundedPartitionReader.pollBefore(sink, max, bound)`): read
   only records that come before `bound`, and report `bound` as the position once nothing before it
   remains. The source implements this with knowledge only it has: the filesystem reader knows the line
   count, and the Kafka reader skips control records.

With both, the shared feed treats a member in one of three states:

| State | What it receives | Its checkpoint's offset |
|---|---|---|
| **attached** | every record the shared reader reads, through the fan-out | the shared reader's position |
| **catching up** (joined behind) | only its private catch-up, read with `pollBefore(the shared position)` | the catch-up's position |
| **waiting** (joined ahead) | nothing, until the shared reader reaches its position | its own position |

A catching-up member is attached the moment its catch-up's position *equals* the shared position. Both
are polled on the feed's one thread, so neither moves while the other is compared. A waiting member is
attached when the shared reader, reading with `pollBefore(its position)`, lands on it. If nobody is
attached, the reader simply moves forward to the earliest waiting member. In every case each record
reaches each query once, in the source's order.

The feed thread polls catch-ups several batches for every batch the shared reader takes, so a catch-up
converges on a moving seam. A catch-up that makes no progress towards its seam for the grace period
stops the group with a named failure. Its source's positions do not behave as declared, and reading past
the seam would duplicate records.

Sources without both declarations keep today's behaviour: shared at-least-once where SRC-3 allows it,
otherwise a reader per query.

### Which sources, and in what order

- **filesystem**: its position is the count of lines consumed (rejected lines included), so both
  declarations are exact and cheap. First, because it can be tested end to end without a broker.
- **kafka**: offsets are ordered per partition, and `pollBefore` compares each record's offset with the
  bound. Built after tranche A, which is changing the Kafka reader.
- **delta, jdbc**: to follow, each once its position's order is shown to be total.
- **postgres-cdc stays a reader per query.** Its slot is confirmed at each checkpoint
  (`PartitionReader.checkpointed`). A shared slot could only be confirmed up to the slowest member's
  durable position, and that needs its own design.

## Consequences

- N queries over one ordered source read it once. A member joining, pausing, resuming or restoring
  costs a private read of only the gap between its position and the shared one.
- A slow catch-up does not stall the group: the members already attached keep reading.
- A member's checkpoint offset is always exactly what that member has been handed, in every state.
- Kafka's monitoring commit (`monitoring.group`) reports the most advanced member's durable offset,
  because the shared reader receives every member's acknowledgement and commits the highest. It is a
  monitoring figure and never a restore point.
