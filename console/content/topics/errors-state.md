---
title: State and serving codes (PRV-4xxx)
slug: errors-state
category: errors
order: 50
icon: exclamation-triangle
summary: "PRV-4001 to PRV-4090: state past its ceiling, spill quota and disk, unreadable checkpoints, directories another node owns, the dead-letter queue, backfill, and every way a view read is refused."
badge: PRV-4XXX
audience: Operators, developers
keywords: [state too large, ceiling, spill, quota, disk full, checkpoint, snapshot, ownership, owner, allow-shared, dlq, dead letter, backfill, view too large, no such view, admission, tenant, deadline, consistency, frontier]
guide: troubleshooting#it-ran-out-of-memory-the-disk-filled
related: [state-spill, checkpoints-recovery, point-reads, consistency, errors-overview]
---

The 4xxx range is about **held rows**: an operator's state, the state tier that spills it, the
checkpoints that preserve it, the directories it lives in, and the maintained views that serve it. A
served view *is* state, so its read failures live here too.

The codes split into two families that call for different people:

- **4001–4090: the node's state** — an operator's problem, fixed with settings, disk or retention.
- **4020–4029: reading a view** — a client's problem, fixed with a different read or a retry.

| Code | Name | In one line |
|---|---|---|
| PRV-4001 | STATE_TOO_LARGE | An operator's state reached its ceiling; the lane stops |
| PRV-4002 | STATE_UNREADABLE | A stored state image cannot be read back |
| PRV-4003 | STATE_NOT_OURS | Another node owns this state directory |
| PRV-4004 | STATE_OWNERSHIP_UNREADABLE | The ownership marker exists and cannot be read |
| PRV-4005 | STATE_SPILL_QUOTA_REACHED | The node's spill budget is used up |
| PRV-4006 | STATE_SPILL_DISK_FULL | The spill disk has no room for the next slab |
| PRV-4010 | BACKFILL_BUFFER_FULL | Changes piled up faster than a snapshot finished |
| PRV-4011 | BACKFILL_MISSING_VERSION | A row with no version to compare |
| PRV-4012 | BACKFILL_UNSUPPORTED_KEY | A key type the splice cannot compare |
| PRV-4013 | BACKFILL_SPLICE_MISSED | A backfill ran out of history without reaching its seam |
| PRV-4014 | BACKFILL_NOT_CAUGHT_UP | A cutover asked for too early, or at a position the two versions do not share |
| PRV-4015 | BACKFILL_SEAM_WENT_BACKWARDS | A cutover seam at or before the last one |
| PRV-4016 | BACKFILL_NO_REPLACEMENT | Nothing is replacing that name |
| PRV-4017 | BACKFILL_REPLACEMENT_IN_PROGRESS | A second replacement of one name, or a drop during one |
| PRV-4018 | BACKFILL_SOURCE_UNSUPPORTED | A stream that cannot be backfilled, or an option that is not built |
| PRV-4019 | BACKFILL_VIEW_REPLACED | The view a subscription followed was replaced at a cutover |
| PRV-4020 | SERVING_NO_HISTORY | A read asked for the past |
| PRV-4021 | SERVING_READ_TIMED_OUT | A read waited for a frontier that did not arrive |
| PRV-4022 | SERVING_VIEW_TOO_LARGE | A view past its key ceiling |
| PRV-4023 | SERVING_NO_SUCH_VIEW | No view by that name |
| PRV-4024 | SERVING_RESULT_TOO_LARGE | One read produced more rows than a response may carry |
| PRV-4025 | SERVING_UNSUPPORTED_QUERY | A view with a column this engine cannot serve |
| PRV-4026 | SERVING_READ_REJECTED | The node is at its read limit and its queue is full |
| PRV-4027 | SERVING_READ_QUEUE_TIMED_OUT | A read waited for a permit and gave up |
| PRV-4028 | SERVING_TENANT_QUOTA_EXCEEDED | This tenant is using its whole share |
| PRV-4029 | SERVING_READ_DEADLINE_EXCEEDED | A read ran past its deadline mid-scan |
| PRV-4090 | STATE_DLQ_UNUSABLE | The configured dead-letter directory cannot be written |

## The node's state

### PRV-4001 — state too large

An operator's state — a windowed aggregate's accumulators, a join's held rows — reached its
**ceiling**, and the lane running it stops with it. It is always a bounded-state problem, never a
transient one: retrying changes nothing.

**Do not meet this for the first time here.** Every query publishes how full its state is:

| Metric | Meaning |
|---|---|
| `pravaha_query_state_held{query=}` | What it holds now (accumulators or join rows, not bytes) |
| `pravaha_query_state_ceiling{query=}` | What it is refused at |
| `pravaha_query_state_fraction{query=}` | The ratio, 0 to 1 — **alert at 0.9** |

```text
# a Prometheus alert rule, as an example
- alert: PravahaQueryStateNearCeiling
  expr: pravaha_query_state_fraction > 0.9
  for: 5m
```

**Do:** narrow the window, add a key predicate, or — the degradation that keeps a query running —
turn on the **spill tier**. With `pravaha.state.spill.directory` set, join and windowed-aggregate
state (and `COUNT(DISTINCT)`, since ADR-044) overflows to memory-mapped files on disk instead of being
refused: slower, but running (ADR-037). Eviction is deliberately not an option: a retraction whose
insert was evicted leaves a row that can never be withdrawn. See [State and spill](/help/topics/state-spill).

```yaml
pravaha:
  state:
    spill:
      enabled: true
      directory: /var/lib/pravaha/spill
      max-bytes: 20GB
```

### PRV-4005 — spill quota reached

The node's spilled state, across **every** query, reached `pravaha.state.spill.max-bytes`, and the
query whose state needed one more overflow slab stopped before writing anything. A bound has to exist
somewhere, and eviction is not one a Z-set can have.

**Do:** check `pravaha_state_spill_bytes_mapped` (the node's total, what the quota counts) and
`pravaha_query_spill_bytes{query=}` (who holds it). Check `pravaha_query_spill_fragmentation` first —
a query sitting far above its live state is one whose `pravaha.state.spill.compaction-threshold` is
set higher than its churn reaches. Then raise the quota if the disk can hold it, or bound the query
holding the most with a window or a tighter key range.

### PRV-4006 — spill disk full

The spill directory's filesystem had less free space than the next overflow slab needs, so the slab
was **refused before it was created**. The alternative is worse than it sounds: a sparse mapped file
takes its disk page by page as it is written, so a full disk would surface as a fault inside a write to
mapped memory, from whichever operator touched the page — not an error with a name.

**Do:** free space, move `pravaha.state.spill.directory` to a larger filesystem, or set
`pravaha.state.spill.max-bytes` below what the disk holds, so the quota (PRV-4005) refuses first.

### PRV-4002 — state unreadable

A stored state image — a checkpoint, a view snapshot — cannot be read back: truncated, corrupt, or
from a format this engine does not read. The engine refuses rather than restoring bytes it does not
understand. For example, a view snapshot written before the format carried a version stored small
integers and `FLOAT32` values widened and `DECIMAL` truncated; restored, an `INT32`-keyed view held a
second row beside every updated key (VIEW-2). That snapshot is refused with this code, **before** any
operator state is restored, so the query resumes from its sources with empty operators: the answers
are rebuilt, not doubled. The next checkpoint is written in the current format.

**Do:** nothing, usually — the query rebuilds from its sources. If the sources can no longer supply
the history, what the checkpoint held is lost; `pravaha.checkpoint.keep` (default 3) keeps older
checkpoints precisely because the newest is the likeliest to be the one being written when a process
died. See [Checkpoints and recovery](/help/topics/checkpoints-recovery).

### PRV-4003 — state not ours

A node claims its checkpoint directory and its registry journal's directory at startup, writing a
`.pravaha-owner` marker naming its node id, host, port and pid, refreshed on a lease. This code means
**another node owns the directory**, or a second instance of this node is running. The message names
the holder's node id, host, port and how long ago it was last seen.

Two nodes sharing a checkpoint root prune each other's checkpoints; two sharing a journal replay each
other's registrations and each comes up running queries it never registered. Both used to be reachable
from two lines of configuration, silently (CFG-13, CFG-14).

**Do:** give each node its own directories, stop the other instance, or set
`pravaha.state.allow-shared` to `true` if sharing is genuinely intended. A node reclaiming **its own**
state after a crash does not hit this: an expired claim under the same node id is taken over
automatically — which is also how a [standby](/help/topics/standby) takes over.

### PRV-4004 — ownership marker unreadable

The `.pravaha-owner` file exists and cannot be read or written, or names no node. Refused rather than
assumed free, because a truncated marker and an absent one mean different things. **Delete it only if
the directory is genuinely unowned.**

### PRV-4090 — dead-letter queue unusable

`pravaha.dlq.directory` is set and this node cannot create it or write there, so it **refuses to
start**. Deliberately fatal: an operator who configured a dead-letter queue asked for undecodable
records to be kept, and starting without one would hand them the behaviour they configured it to
avoid — one bad field ending the poll and taking the rest of the file with it (TIME-4).

**Do:** fix the path and its permissions, or unset the key to go back to failing loudly on a bad
record. See [Dead letters](/help/topics/dead-letters).

## Backfill and blue/green replacement

These are the codes of a **replacement**: a new version of a registered query, started beside the
running one, backfilled from the source, spliced onto the live stream at the exact position the
running version has reached, and cut over to only when the two have consumed the same input
(ADR-046). `CREATE OR REPLACE CONTINUOUS QUERY`, `pravaha replace`, both SDKs and
`/api/v1/queries/{name}/replacement` all reach it.

Three of them — PRV-4010, PRV-4011 and PRV-4012 — belong to the *other* splice, the one that joins a
table snapshot to a change feed and deduplicates by the store's own version (ADR-015). That one is a
library in `pravaha-backfill` with no caller: no source plugin here exposes a snapshot read
separately from its change feed. They are met only by code that uses `pravaha-backfill` directly.

### PRV-4010 — backfill buffer full

Changes accumulated during the snapshot faster than the buffer could hold them: the snapshot is taking
longer than the change rate can be held for. Raise the buffer, or snapshot a smaller range at a time.

### PRV-4011 — backfill missing version

A row arrived with no value in the version column. The splice decides which of two rows is newer by
comparing versions; a null cannot be compared, and guessing would drop live changes.

### PRV-4012 — backfill unsupported key

A key column of a type the splice cannot compare. Use a scalar key.

### PRV-4013 — backfill splice missed

The backfill read all the history the source has and never reached the position the running version
is at. Its positions do not name the record they were taken after, so there is no offset the history
and the live stream can meet at — and reading past the seam would deliver the overlap twice. The
backfill stops instead of doubling it.

### PRV-4014 — backfill not caught up

A cutover was asked for before the new version had caught up, **or** the two versions could not be
brought to the same position in their input. Cutting over at different positions would leave the
records between them in neither version's output, or in both — permanently, and invisibly. Wait for
every partition to reach the live stream, and try again; if the source is busy enough that the two
never stop at the same record, quieten it. Nothing has changed either way.

### PRV-4015 — backfill seam went backwards

A cutover seam at or before the previous one. Seams only move forward: an overlapping seam would make
two versions both responsible for the same input, and both would emit it.

### PRV-4016 — no replacement

A cutover, rollback, throttle or status for a name nothing is replacing. Start one with
`CREATE OR REPLACE CONTINUOUS QUERY`, `pravaha replace`, or
`POST /api/v1/queries/{name}/replacement`. After a rollback or a finish there is none left to act on.

### PRV-4017 — a replacement is already in progress

One shadow at a time. Also raised when the new version's plan normalises to the computation already
serving the name — a cutover to itself — and when a query is dropped while its candidate is still
running. Cut over, roll back or abandon the first.

### PRV-4018 — this cannot be backfilled

A stream nothing is bound to, or whose source cannot be replayed or does not order the records within
a partition (a table scan reports where its pass began rather than the record it was taken after); an
option this engine does not build (`backfill.parallelism`, `backfill.window`, `backfill.adaptive`); a
rate above the ceiling the replacement was started with; or a `WRITING TO` or `RETAIN` that would
change the name's sink or retention while replacing it. The message names which.

### PRV-4019 — the view was replaced

The subscription you were holding ended because the view it followed was replaced at a cutover. Every
change the version you were following made was delivered first. Subscribe again — a snapshot
subscription then starts from a fresh snapshot of the new version, which is what it needs and what a
diff between two different queries could not give it.

## Reading a view

A read of a maintained view is ordinary SQL answered from the view, over Flight SQL, the PostgreSQL
gateway or the REST API. These are the ways the view refuses one.

### PRV-4023 — no such view

A read named a view this server does not serve. Usually the query was dropped, the name is misspelled,
or the client is pointed at a different server than you think:

```bash
pravaha queries --url grpc://localhost:9090
```

In SQL, an unknown name in `FROM` is usually reported by the validator first as PRV-2002; this code is
what the serving layer says on the paths that look a view up directly — `GET /api/v1/views/{name}`, a
subscription, a point read. It is `NOT_FOUND` over Flight, `42P01` over the PostgreSQL gateway, and
`404` over REST. A name you may not see answers exactly as a name that does not exist.

<!-- sql: read-refused PRV-2002 -->
```sql
SELECT * FROM hourly_spends
```

### PRV-4022 — view too large

The view holds more keys than it was given room for — with its **retention already applied**. The
message says which case you are in: a view that keeps everything, with a key space that keeps
growing, grows for ever; a view with a retention holds more keys inside that window than the ceiling.
Give the view a retention (`RETAIN FOR P7D` / `--retain P7D`), key it more coarsely, or split it.

### PRV-4024 — result too large

A single read produced more than **1,000,000 rows**, the ceiling for one response. Narrow it with a
`WHERE`, or **subscribe** to the view instead — a subscription streams without that bound. SQLSTATE
`54000` over the PostgreSQL gateway.

### PRV-4025 — unsupported query

The view has a column of a type this engine cannot serve over the read path, so the read is refused
with the column named, rather than producing a value of the wrong class on the wire that surfaced as an
internal error. SQLSTATE `0A000`.

### PRV-4020 — no history

A read asked for the view **as of a past frontier**. A view holds the present; the past lives in
checkpoints. Answering with the current value would be the worst possible response to an audit
question, so it is refused. Read the checkpoint at that frontier instead. See
[Consistency](/help/topics/consistency).

### PRV-4021 — read timed out

An *at-least* read — "answer once the view has committed through frontier N" — waited and the view did
not get there in time. The message says where the view is committed through and what was asked. The
source may be idle, or behind; check `pravaha_query_watermark_lag_seconds`. `TIMED_OUT` over Flight,
`57014` over the PostgreSQL gateway.

### PRV-4026, PRV-4027, PRV-4028 — the node is busy

**Admission control.** Reads and continuous queries share a machine, and an unbounded read path is how
a client in a loop stops a continuous query keeping up with its input. So reads are admitted — a
number concurrently, a bounded queue, and a share per tenant — and refused past that. The three are
separate because the fix differs:

| Code | What happened | Your move |
|---|---|---|
| PRV-4026 read rejected | Full now, queue full; the read **never waited** | Retry with backoff |
| PRV-4027 read queue timed out | Waited its turn for a permit and gave up | The node is saturated for longer than your patience |
| PRV-4028 tenant quota exceeded | Your tenant already has its whole share in flight | Capacity may still be free — this protects the other tenants |

All three arrive as `RESOURCE_EXHAUSTED` over Flight and `53000` over the PostgreSQL gateway, so a
driver **retries** them rather than treating them as a malformed query.

### PRV-4029 — read deadline exceeded

A read ran past its deadline and was stopped **mid-scan**; the message says how many rows it had
produced. Narrow the read, or read it again when the node is less busy. `TIMED_OUT` / `57014`.

## Where next

- [State and spill](/help/topics/state-spill) and [Checkpoints and recovery](/help/topics/checkpoints-recovery)
- [Point reads](/help/topics/point-reads), [Consistency](/help/topics/consistency) and
  [Subscriptions](/help/topics/subscriptions)
- [Metrics and alerts](/help/topics/metrics-alerts) — the numbers to watch before these codes appear
