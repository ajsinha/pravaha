# ADR-056: a query over a query follows its answer, and carries what it has consumed

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `QueryChains`, `UpstreamReader` and `UpstreamFeed` in `pravaha-registry`, `ServedView.followAnswer` in `pravaha-serving`, the continuous `KeyedAggregate` in `pravaha-runtime`, `MaintainedViews` in `pravaha-sql` |
| Date | 2026-09-28 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-008 (a checkpoint is one cut), ADR-046 (a replacement meets the running version at a position), ADR-050 (tenancy), ADR-054 (an exact seam), SUB-1 (snapshot, then every commit), SX-11 (authorization follows the data) |

## Context

People layer answers: a cleaned feed, an aggregate over the cleaned feed, an alert condition over
the aggregate. Until now a continuous query could only read streams, and a `FROM` naming a
registered query's view was a **read**: `CONTINUOUS_QUERIES.md` §13 said "the scan ends", which is
true and is exactly the problem. The answer is computed once, over whatever the view held at that
moment, and never again. The only way to layer was to copy the upstream's SQL into the downstream,
which computes the upstream twice and — as §1 below shows — does not even compute the same thing.

The engine already has the two halves of a continuous read of a view: a snapshot of the committed
answer, and every commit after it with no gap between the two (SUB-1, the paper's Theorem 1). What
is missing is feeding that into a lane as a source, and saying what "exactly once" means when the
input is itself a computation that restarts, pauses, fails and is dropped.

## Decision

`CREATE CONTINUOUS QUERY d ... AS SELECT ... FROM u ...`, where `u` is a registered query, registers
a separate computation `d` that **follows the answer of `u`**: its snapshot, then every change to
it, fed through an ordinary ingest pump into `d`'s own lane, with weights and retractions. Reading
`u` with a `SELECT` stays what it was — a bounded read of the view.

### 1. What a downstream is fed: the answer's changes, not the upstream's changelog

The changelog a subscriber receives (`ViewSink.onCommit`) is what the upstream's lanes *applied*,
and for a keyed view that is not the same as how the answer changed. A second insert for a key the
view already holds replaces the row in the view — "a second row with the same key supersedes the
first" — while the changelog carries only the `+1`. A client keeping a copy overwrites by key and
is right. A query summing that changelog counts both rows and is wrong.

So a downstream is fed what a reader of the view sees: **the rows of the answer, each once, and for
every commit the rows that left it (weight −1) and the rows that entered it (+1)**. `ServedView`
computes this in its own commit, from the row it held under each touched key and the row it holds
after — the same before-and-after ADR-055's index is maintained from — including rows retention
evicts. `followAnswer` hands a listener the committed rows and registers it in one critical section
of the view's monitor, and every later commit hands its change to the listener inside that commit's
critical section. The snapshot and the first change therefore meet exactly (SUB-1's seam), and the
changes arrive in commit order by construction rather than by argument (the paper's S3).

The listener only queues. A registry-internal reader (`UpstreamReader`) drains the queue into the
downstream's inbox through an `IngestPump`, so backpressure, the checkpoint freeze, shared lanes,
watermark tracking and the feed's status are the ones every source has. Each row is written at the
upstream's committed frontier as its event time.

Over such an input the planner accepts what can be maintained exactly under retractions and refuses
the rest with **`PRV-2075` (`SQL_VIEW_INPUT_UNSUPPORTED`)**, naming the construct:

- **accepted**: filter, projection and computed columns, and unwindowed aggregates — `COUNT`, `SUM`
  and `AVG`, global or with a `GROUP BY`. A keyed `GROUP BY` is refused over a stream (`PRV-2050`)
  because its key space is unbounded; over a view it is bounded by the upstream's key ceiling, and
  the continuous `KeyedAggregate` re-publishes each changed group as a retraction and an insert,
  releasing a group whose rows have all been retracted;
- **refused**: `MIN` and `MAX` (a retraction of the extreme needs an ordered multiset per group),
  `COUNT(DISTINCT)` (its value sets are not weighted), windows (§6), joins with a stream, a lookup
  table or another view, top-N, and a `GROUP BY` over a column type the keyed aggregate does not hold.

### 2. Exactly once across the chain: the position is the consumed answer

A checkpoint of `d` is one cut of `d` (ADR-008). What it must record about `u` is where `d`'s input
stands, and a sequence number of `u`'s commits cannot say that: commits follow a timer, so after `u`
restarts and replays its sources, commit *n* is a different set of changes. A position in a
sequence that is renumbered on every restart cannot be resumed from.

What `d` has consumed is an **answer** — the rows of `u` it has been handed, net of retractions —
and an answer can be compared with another answer. So the reader keeps that image (one entry per
upstream row, keyed by the upstream's key, sharing the upstream's own row arrays rather than
copying values), and the checkpoint carries it:

- it is read **under the ingest freeze** that cuts every source (ADR-008), so it names exactly the
  rows the lane had been handed at the checkpoint's marker; changes the reader hands over between
  the freeze and the marker are undone from the copy written, so the image and `d`'s operator state
  describe one position;
- it is stored in `d`'s checkpoint beside the view (`upstream-input`), and the source offset records
  the upstream and the frontier consumed, for an operator to read;
- **on restore**, `d` restores its own state and its image, follows `u` again, and is fed the
  difference between `u`'s snapshot and the image as its first batch — retractions for rows the
  image has and the answer does not, insertions for the reverse. Then every change after it.

That is exact whichever of the two checkpointed first, whichever restored first, and whether either
checkpoints at all: the difference is between two answers, not two positions. Both restarting,
`u` restores (or replays) to its answer, `d` restores to its image, and the difference is what `d`
has not seen — possibly retractions of rows `d` saw and `u`, restored from an older cut, has not
recomputed yet; they come back as insertions when `u`'s replay reaches them.

**Refused, because it cannot be made exact**: a restored checkpoint of `d` whose view holds rows and
which carries no image, or an image of another shape (`PRV-8026`). Starting from an empty image
beside a restored view would feed the whole answer again on top of what the view already counted.

The queue between the listener and the reader is bounded (65,536 changes). A reader that falls that
far behind — a paused downstream, or one slower than its upstream — drops the queue and
re-snapshots, and the difference against its image is fed instead. That is conflation without
loss: the image is what makes a gap recoverable rather than fatal.

### 3. Start-up order and the journal

A registration can read only a view that exists, and a query with dependants can be neither dropped
nor replaced (§4), so the journal's order is already topological: upstreams before downstreams.
Recovery replays it in that order, and each downstream follows an upstream that has already restored
its view. A downstream whose upstream did not come back is refused like any registration whose SQL
names nothing that exists, and reported in `Recovery.refused`.

### 4. Dropping, pausing, failing and replacing an upstream

- **DROP** of a name that another query reads is refused with **`PRV-8024`
  (`REGISTRY_QUERY_HAS_DEPENDANTS`)**, naming the dependants; drop them first. There is no
  `CASCADE`. A cascade drops queries other people registered, and ADR-025 already chose the
  opposite for sharing: dropping one name must not take an answer away from somebody who has no
  idea the dropper exists. A shared computation is refused per name — the dependant reads a name.
- **PAUSE** of an upstream is allowed. Its answer stops moving, so its downstreams keep answering
  at the frontier they reached; nothing is lost, because nothing happened. **RESUME** carries on.
- **FAILED** upstream: the downstream's feed stops with `PRV-8004` naming the upstream, and the
  downstream stays `RUNNING`, its view correct up to the frontier it reached — a stopped source, as
  FEED-1 reports every other one.
- **CREATE OR REPLACE** (ADR-046) is refused with **`PRV-8026` (`REGISTRY_CHAIN_UNSUPPORTED`)** for a
  query with dependants, for a query that reads a view, and for a new version that would read one;
  and a downstream is refused over a name that is being replaced. A cutover moves a name from one
  computation to another while a follower is attached to the first one's answer; handing the
  follower across at the cutover's position is future work, and until then refusing is the honest
  answer.

### 5. Cycles, depth, security and tenancy

- **Cycles** are refused with **`PRV-8025` (`REGISTRY_QUERY_CYCLE`)**, naming the loop. A plain
  `CREATE` cannot make one — it can only read what exists — so the check guards the replacement
  path, and runs before any other refusal there so the loop is what the caller is told about.
- **Depth**: a chain is at most **8** levels (**`PRV-8027` `REGISTRY_CHAIN_TOO_DEEP`**). Each level
  adds a commit interval of latency and an image of its input; a chain longer than that is a design
  worth looking at before it is a deployment.
- **Security**: registering `d` over `u` needs `mayRead` on `u` **and** on every stream `u` derives
  from, transitively — SX-11's rule that authorization follows the data rather than the name. `d`'s
  view is recorded as derived from `u` and from those streams, so every read of `d` is judged
  against the base streams, and a row filter on one applies to `d` exactly as to any view: applied
  if `d` carries the column, refused (`PRV-7003`) if it aggregated it away (CONCEPTS §6). The
  collected row filters are in `d`'s fingerprint, as for any registration.
- **Tenancy** (ADR-050): the planner is offered only views registered by the caller's own tenant. A
  view of another tenant plans as a name that does not exist, so the refusal discloses nothing.

### 6. Lanes, fingerprints, time

- `d` is a computation of its own: its own lane, or a shared lane by the node's rules (ADR-036),
  its own checkpoints, its own sink. Its fingerprint gains, for each upstream, the upstream's name
  and fingerprint, so identical SQL over the same name shares one computation only while that name
  answers the same computation.
- **Time.** Rows arrive at the upstream's committed frontier, so `d`'s view frontier follows `u`'s,
  and an `AtLeast` read of `d` waits for `u`'s frontier to have reached it. Windows over a view are
  refused (`PRV-2075`): the upstream's frontier is not a watermark over any column of its rows, and
  a correction inside the upstream's allowed lateness arrives below it, where a window already
  closed on it would drop it. `RETAIN FOR` on `d` is refused (`PRV-8026`) for the same reason
  applied to eviction: a row unchanged upstream keeps its old frontier and would be evicted from `d`
  while still in `u`. Retention belongs to the upstream.

## Alternatives considered

**Subscribe to the upstream's changelog (SUB-1) and feed it in.** The obvious one, and wrong for any
keyed upstream whose query emits a new row for an existing key without retracting the old one: the
view replaces, the changelog adds, and a `SUM` downstream double-counts. It looks right in every
test whose upstream is a filter over inserts.

**Expand the upstream's SQL into the downstream.** Two computations of the upstream, two copies of
its state, and not the same answer: the upstream's view replaces by key, and the expanded plan does
not.

**Record the upstream's commit sequence and refuse a restore where they disagree.** They almost
always disagree — the two checkpoint on independent timers — so a restart would refuse routinely.
Aligning them needs a barrier that crosses from one computation's view into another's inbox, and
still fails when a crash lands between the two stores.

**Cascade a drop.** Discussed in §4: it drops other people's queries.

**Leave it a one-off scan.** It is bounded and correct, once. An alert condition evaluated once is
not an alert.

## Consequences

- A downstream costs one entry per upstream row for its image, on the heap, bounded by the
  upstream's key ceiling (1,000,000 by default); the row arrays are the upstream's own.
- An unwindowed keyed `GROUP BY` is now continuous over a view, and checkpointed with the lane
  (`InterpretedPipeline` snapshot version 7; version 6 is still read into a plan without one).
- `GET /api/v1/queries/{name}` reports `readsFrom` and `dependants`; the console's query page links
  them.
- Not built, and refused rather than approximated: windows, joins, `MIN`/`MAX` and
  `COUNT(DISTINCT)` over a view; replacing a chain member; a time-travel debug fork of a downstream
  (its input has no history to replay, `PRV-8012`); chains across nodes (ADR-045 is design only — a
  downstream on a node without its upstream plans as an unknown name).

## Notes

ADRs are amended, never rewritten. If this decision is superseded, the file keeps its number and
gains a `Superseded by ADR-NNN` line at the top rather than being deleted -- the reasoning behind a
decision that was later reversed is usually the most useful thing in the directory.
