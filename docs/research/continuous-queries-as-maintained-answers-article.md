# Continuous Queries as Maintained Answers

### Exact cuts, exact seams and lossless cutover — what "ask once, answer always" has to mean in a single-node streaming SQL engine

> **What carries each claim.** The system this is written against is **Pravaha**, a single-node,
> embeddable continuous-SQL engine in Java 21. Where a section below says the engine does something,
> it names the test in the repository that shows it — or says plainly that the claim is argued from the
> code and no test asserts it. [The ledger](#the-ledger-what-is-tested-and-what-is-argued) collects them:
> of thirty-five claims, twenty-three run as stated, two run with one clause argued, three run in part,
> three hold only of a reference module the engine does not execute, two are argued, and one thing the
> design describes is not built. An article that read as a brochure while the paper it accompanies reads
> as an audit would be worse than either alone, so the uncomfortable parts are in here too: the algebra
> the design rests on is tested in a module nothing imports, one invariant of the served view has an
> open finding against it, and the engine is one node.

> **This article follows the paper's first edition (28 September 2026).** The paper's second edition,
> for Pravaha 1.0.0 (the PDF beside this file), supersedes it where they differ: the served-view finding
> (VIEWW-1), the change-feed replacement (CDCREPL-1), the Kafka seam and shared-lane fate are now closed
> by tests; the ledger has forty-five claims, thirty-five of them running as stated; and it adds queries
> on queries and alerts (a seam with no position), governance, the release and the test tiers.

---

Here is a question that sounds like a solved problem:

**How do you keep an answer current?**

Not "how do you compute it" — any database can do that. How do you keep the answer to a question
*current* while the data underneath it changes, and hand it to whoever asks, without ever being a little
bit wrong in a way nobody notices?

The usual answer is that you don't. You ask again. A dashboard polls every five seconds. A fraud rule
runs the query per decision. A nightly job rebuilds a summary table. Between two askings the answer is
stale, every asking pays for the whole question, and ten desks asking the same thing cost ten
executions.

The other answer is a **continuous query**. You register the question once, with a name and the columns
its answer is keyed by:

```sql
CREATE CONTINUOUS QUERY txn_volume KEYED BY (window_end, user_id) AS
  SELECT TUMBLE_END(event_time, INTERVAL '10' SECOND) AS window_end,
         t.user_id, p.tier, COUNT(*) AS txn_count, SUM(t.amount) AS total_volume
  FROM txn_stream AS t
  LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
    ON t.user_id = p.user_id
  WHERE t.status = 'COMPLETED'
  GROUP BY TUMBLE(t.event_time, INTERVAL '10' SECOND), t.user_id, p.tier
```

and the engine keeps the answer current from then on. It reads each change once, applies it to the
state the question needs, and publishes the difference. Reading the answer is a lookup by key. A
consumer that wants the changes subscribes, and gets one batch per commit with a weight on every row:
`+1` for a row appearing, `−1` for one being withdrawn.

Pravaha's slogan for this is *ask once, answer always*. This article is about what "always" has to mean
for that to be worth saying.

---

## The arithmetic is the easy part

None of the idea is new. Continuous queries over append-only databases were described in 1992.
Incremental view maintenance has a forty-year literature. And DBSP (Budiu, Chajed, McSherry, Ryzhyk and
Tannen, VLDB 2023) gave the whole thing a small, clean algebra: represent every table and every change as
a bag of rows with integer weights, and the incremental version of a query falls out by rule.

That part is genuinely easy to get right, because it can be written as an equation and tested:

> Q(state + change) = Q(state) + Q<sup>Δ</sup>(change, state)

Generate a random state and a random change, compute both sides, compare. If your incremental join is
wrong, a few hundred random cases will find it.

What can't be found that way — and where every streaming system I've looked at has had its subtlest
bugs — is somewhere else entirely.

## The hard part is the hand-overs

The failures practitioners actually meet happen at the moments when **one stream of records has to take
over from another**. There are four of them in a continuous-query engine, and each is a place where a
system can be almost right in a way no error reports.

**1. A subscriber joins a running view.** A client wants a local copy of the answer. It reads the
current rows, then subscribes to changes. Or subscribes, then reads. Either way, a commit can land
between the two steps, and the client's copy is then missing that commit — or holding it twice — for
ever, with nothing in either stream to say so.

**2. A restart resumes from a checkpoint.** The engine saves its state and a bookmark in each source,
and after a crash restores the state and reads on from the bookmarks. If the bookmarks were read a moment
after the state was copied, the restart skips the rows in between, or counts them twice. The answer is
then merely a little wrong, which is the most expensive kind of wrong.

**3. A query joins a reader others are sharing.** A thousand queries over one Kafka topic should not
read it a thousand times, so one reader is shared. But queries don't start together. A query registered
later, or resumed after a pause, or restored from an older checkpoint, is at a different position from
the shared reader, and has to be brought to it. Catch it up by reading "to the end" and the overlap
arrives twice. Attach it at once and it skips what it missed.

**4. A query is replaced by a new version.** You fix a filter. The new version needs to rebuild its
state, so it runs beside the old one and takes over "when it has caught up". But "caught up at 14:00"
isn't a definition. Two queries running at slightly different speeds, swapped at an instant, have
consumed different input, and the records between them are in neither answer or in both.

## One answer, three parts

Here is the thesis of the paper, and the thing I'd most like a reader to take away even if they never
touch this engine:

**The four hand-overs are one problem, and they have one answer: a seam is a position, not a moment.**

The answer has three parts.

1. **Name the seam with a position.** A position is a bookmark a source handed out — a Kafka offset, a
   line number, a log sequence number — and within a partition, positions are totally ordered and
   replayable: open a reader at a bookmark and it delivers exactly what comes after. A record *count* is
   not a position (Kafka's offsets have gaps, because transaction markers take up offsets). A wall-clock
   time is not a position. "The reader looks idle" is not a position.
2. **Read positions and state together, under a freeze.** Wherever a seam depends on what a computation
   has consumed, hold the sources between rows while you read the bookmarks, and drop a marker into the
   computation's input at exactly that point. Take the state at the marker. What the bookmarks exclude
   and what the state includes are then the same rows.
3. **Refuse when you can't show equality.** A replay that runs out before it reaches its seam stops. Two
   versions that can't be brought to equal positions aren't swapped. A catch-up that makes no progress
   toward its seam stops the group. A subscriber too far behind is ended rather than written past the
   gap. Every refusal has a named error code.

Stated that way, each of the four hand-overs has a short proof. The rest of this article walks through
them.

---

## Rows with weights — and the honest bit

The model underneath is DBSP's. A **Z-set** is a bag of rows where each row carries a whole number. A
table is a Z-set where every weight is `+1`. A batch of changes is a Z-set with mixed signs. They're the
same kind of object, so applying a change is addition, and an update isn't a third kind of message: it's
`−1` of the old row plus `+1` of the new.

From there the useful facts are short:

- **Filters and projections need no memory.** They're *linear* — applying them to a sum is the sum of
  applying them — so you apply them to the change and get the change in the result. A query that only
  filters and projects costs nothing between rows.
- **`DISTINCT` isn't linear**, so its incremental form has to remember the running total and only emit a
  row when its presence actually changes. A row going from weight 3 to weight 4 is still present, so
  nothing propagates.
- **A join has three terms, and people drop the third.** When both sides change in the same batch, the
  change in the result is new-left against old-right, plus old-left against new-right, plus new-against-new.
  Drop the last one and you lose exactly the matches that arrive together — a bug invisible at low traffic,
  because batches of one row contain no pairs, and which shows up as quietly missing output when traffic
  rises.
- **A maintained view *is* the running total of its changes.** Serving the answer is reading that total,
  not keeping a second copy in step with it.

Pravaha has a module, `pravaha-algebra`, that implements exactly this, and a generated-property oracle
(`IncrementalOracleTest`, `IncrementalJoinTest`, `ZSetTest`) that tests every law as stated — including
tests that the oracle *fails* on a deliberately broken lift, so you know it has teeth.

**Now the honest bit.** That module is imported by no other module in the engine. The engine runs its own
operators over off-heap binary rows, with filters and projections compiled to Java and run as generated
code, and no test holds those operators against the algebra's oracle. The project's own decision record
for this (ADR-013) says so in its status line.

What the running operators *are* held to is a set of differential tests: generated code against
interpreted code, pushed-down filters against local ones, queries on shared lanes against the same
queries on lanes of their own, a recovered run against an uninterrupted one, and windowed answers against
numbers worked out by hand. Two implementations that agree are evidence that neither has drifted from the
other. They are not evidence that either satisfies the equation.

I think this is the single largest distance between what the design says and what the tests show, and
closing it — run the engine's operators and the reference algebra on the same generated changes and
compare — is the first item of future work. It's also why the ledger below marks those laws "reference
algebra only" rather than "runs".

### A view is keyed, and that's where a real bug lived

The answer a query maintains isn't a Z-set; it's a map from the declared key to one row. The mapping
matters. A key is present exactly while its weights *sum* to something positive — and until a fix
recorded in the code, the view decided presence by the sign of the *last* weight, so a single `−1`
removed a key that three inserts had put there. That's fixed and tested (`ServedViewTest`).

What's *not* settled is which row a present key shows. If a key is inserted as row A, then as row B, and
then A is retracted, the view keeps A's values while the row still present is B. The project's findings
register has it as **VIEWW-1**: open, found by reading, not reproduced. The engine's own operators always
send a retraction before its replacement, in one batch, so the case needs a query that holds two rows
under one key at once — which is exactly what declaring a key is supposed to rule out. The paper states
the stronger property as conditional on that, and marks it argued.

### Time, and what "same rows, same answer" really means

Every window is measured in the timestamp *in the data*, not the clock. A **watermark** is the engine's
claim that nothing earlier is coming: per source partition it's the latest event time seen minus an
out-of-orderness, and a query's watermark is the minimum over its partitions — with partitions that have
gone quiet *excluded*, because one idle Kafka partition would otherwise freeze every window in the query.
That's the most common streaming production incident there is, and it looks like a hang rather than a
bug. (`WatermarkTrackerTest` covers all of it, including that the watermark never goes backwards.)

A window fires when the watermark passes its end. A record that arrives after that has three possible
fates, not two: within the stream's *allowed lateness* it's a **correction** — the old answer is retracted
at `−1` and the corrected one emitted at `+1`, in one batch — and after that it's **too late**, and goes to
a late output where it's counted, never silently dropped (`LateDataTest`).

The documentation says that loading the same rows in any order gives the same answer. That's true of the
rows that arrive on time, because a window's aggregate is a sum. It's *not* true across the lateness
boundary: whether a row is a correction or too late depends on when it arrives relative to the watermark,
and the watermark depends on arrival order and — through the idle timeout — on the wall clock. No engine
that closes windows on a watermark can remove that. What it can do is count what it dropped, and this one
does.

---

## Hand-over 1: joining a running view

The fix for the subscriber problem is to make "give me the view, then every change after it" **one step,
under the same lock that publishes changes**.

Two facts about how Pravaha commits make this work. A commit is atomic to readers: changes land in a
pending overlay and a commit moves the whole overlay into the visible map in one critical section, so no
read ever sees half a batch. And a commit's **audience is fixed when its first batch arrives** — a
subscriber that attaches mid-commit is not in that commit's audience at all. (That second one was a real
defect before: the audience was decided per *row*, so a subscriber attaching mid-commit got the commit's
tail delivered as though it were a whole commit. On a windowed query that's a half-closed window
presented as a closed one.)

With those, a snapshot subscription has only two cases:

- **Nothing is half-published.** Everything applied is committed, so the committed rows *are* the
  snapshot. Take them, and register the subscriber, in the same critical section. The next commit's first
  batch will find it in the audience.
- **A commit is in flight.** Its audience was fixed without you. So you wait on a side list, and the
  commit — inside its own critical section, after publishing — takes your snapshot (which now includes the
  rows that were in flight) and registers you. The *next* commit is your first.

Either way there's exactly one commit C where the snapshot stops and the changes start, and you receive
every commit after C exactly once and none before it. The paper states it as a theorem and proves it from
the lock structure. The tests (`SnapshotHandoffTest`, `SubscribeFromSnapshotTest`,
`EmbeddedSnapshotSubscriptionTest`) attach subscribers during concurrent commits and check that snapshot
plus changes equals the view — and `subscribingThenReadingLosesTheCommitInFlight` demonstrates the loss the
two-step approach suffers.

Two assumptions are stated rather than hidden. The subscriber mustn't overflow its buffer — and if it
does, it's ended with `PRV-6105` rather than written past the gap (`SnapshotSubscriberBehindTest`). And
successive commits must reach it in order; delivery happens outside the lock, and that ordering is argued
from the call sites rather than asserted by a test. (For a consumer that adds up weights, order doesn't
matter anyway; for one that overwrites by key, it does.)

When the engine restarts, the Java and Python SDKs can reopen a snapshot subscription automatically — and
the first thing they receive is a **fresh snapshot**, not a stream of deltas to splice onto a copy the
client can no longer trust (`JavaSdkReconnectTest`).

What's *not* offered: a read of several views at the same point. Each view commits on its own schedule. You
can wait for each to reach a frontier, but not get both at exactly one. And an "as of four o'clock" read is
refused outright, because a view holds the present and answering an audit question with the current value
would be the worst possible response.

---

## Hand-over 2: restarting without counting twice

This is the oldest of the four and the best studied — it's Chandy and Lamport's consistent snapshot, in
the form Flink made standard. I'll keep it short, because the interesting thing is the assumptions.

A Pravaha checkpoint runs in two phases. In the first, every source pump is **frozen** — held between two
rows it has fully written into the query's inbox — and inside the freeze the checkpoint reads every
source's offset and drops a **marker** into the query's input at exactly that point. Nothing is waited for
inside the freeze. In the second phase the sources run again, and the query's lane, when it reaches the
marker, saves its state and the view's committed contents — **at** the marker, not a batch later.

That "not a batch later" was an actual bug: the lane used to finish the batch it was in, putting rows into
the snapshot that the recorded offset said would be replayed. So did "read the offsets after the
snapshot", and so did "forget to record a shuffling source's offset at all". All three are in the
decision record, and all three are pinned by tests (`ControlTaskBarrierTest`,
`AlignedCheckpointBarrierTest`) that take checkpoints with the sources still running.

The result: the state reflects exactly the rows up to the recorded offsets, and a reader reopened at an
offset delivers exactly the rows after it. Every record is counted once. `CheckpointRecoveryTest` checks
that an interrupted-and-recovered run gives the same answers as an uninterrupted one;
`StateRestoreTest` shows the other side — restore without rewinding the sources, and everything since the
checkpoint is counted twice.

**What it rests on**, stated as assumptions rather than folded into the result:

- **Replayable positions.** A reader opened at a bookmark delivers what comes after, and the source still
  has it. An Aerospike scan can't do this, which caps anything sourced from it at at-least-once whatever
  the engine does — and the registration says so.
- **Deterministic operators.** Same input, same state.
- **One lane per registered query, no rows in flight between lanes.** A multi-lane checkpoint across the
  engine's exchange would not be a cut, and forwarding the marker along each ring isn't built — so such a
  checkpoint is *refused*. No pipeline the engine builds for a registered query sends on the exchange, so
  the refusal is unreachable today; it's there so the first one that does gets an error instead of a wrong
  answer.
- **Durable, all-or-nothing checkpoint storage.**

And one thing it *doesn't* mean: that a resumed run is bit-for-bit the run that would have happened.
After a restart, rows from different partitions can interleave differently, and the watermark can advance
at different moments. "Exactly once" here is about *effect* — each record's contribution is in the state
once — and for windowed aggregates that's the same answer. For anything sensitive to interleaving it's
*an* execution of the same input.

### Exactly once to a sink

State that's exactly once isn't output that's exactly once. Pravaha's sink interface uses two-phase
commit tied to checkpoints: at the checkpoint's marker the sink **prepares** (durable, not yet visible);
once the checkpoint is durable, it **commits**; after a restart, the engine commits whatever the restored
checkpoint recorded — again, harmlessly, because commit must be idempotent — and aborts everything after
it, which the replay is about to write again. `TransactionalSinkDeliveryTest` crashes between prepare and
commit and checks nothing is lost or repeated; `KafkaSinkBrokerTest` does it against a real broker.

The cost varies by sink, and it's worth knowing. Kafka has no prepare that a *restarted* producer can
commit, and a Delta commit is visible the moment its log entry lands — so the Kafka, Delta and (by
default) JDBC sinks **stage** each checkpoint's changes and apply them in one transaction once it's
durable. Every change is written twice and output trails the view by up to a checkpoint interval.
`kafka-sink` is exactly once only to a consumer reading `read_committed`. And the guarantee a registration
states is always the **weakest link** in its chain — source, engine, sink — computed, not assumed.

---

## Hand-over 3: sharing a reader, exactly

This is the one I think is new in its particular shape.

Pravaha's first form of reader sharing attached a late query to the shared stream at once and gave it a
private catch-up reader for the history it missed. The catch-up read to the *end* of the source, not to
where the shared reader stood, so the overlap arrived twice. For a source that only promises at-least-once
and no order — an Aerospike or Cassandra scan — that's within the promise, and it's still how those are
shared. For Kafka, it isn't, so until recently every query over a topic had its own reader: a thousand
queries, a thousand reads.

The fix is two things a source can **declare**:

1. **Its positions are ordered.** Given two bookmarks it handed out, it can say which comes first. That
   tells a query joining *behind* the shared reader from one joining *ahead* of it — and after a restart,
   both happen, because every query restores from a checkpoint taken at its own moment.
2. **Its readers can stop at a position.** `pollBefore(bound)`: read only records before the bound, and
   once none remain, report *exactly the bound* as your position. The source implements this with
   knowledge only it has — the file reader knows its line count; the Kafka reader skips transaction
   markers, and a marker spanning the bound moves the position to exactly the bound.

With both, a query in the group is in one of three states:

| State | What it receives | Its checkpoint's offset |
|---|---|---|
| **Attached** | every record the shared reader reads | the shared reader's position |
| **Catching up** (joined behind) | only its private reader, read with `pollBefore(shared position)` | its private reader's position |
| **Waiting** (joined ahead) | nothing, until the shared reader lands on its position | its own position |

A catching-up query attaches the moment its position *equals* the shared one. A waiting query attaches when
the shared reader — told to stop exactly at the earliest waiting position — lands on it. Everything runs on
one feed thread under one lock, so nothing moves while positions are compared.

The paper proves, by induction over the protocol's steps, that **every query receives every record exactly
once and in order, in all three states**, and that its checkpoint offset is always exactly what it's been
handed. One piece of the argument is worth pulling out: the shared reader only ever reads *up to* the
earliest waiting position while anyone is waiting, so it can never pass a waiting query without stopping
on it.

Here's what that looks like after a restart. Three queries over one Kafka partition restore at offsets
1,000, 1,400 and 1,250. The first back has nobody else live, so the shared reader starts at 1,000. The
other two are ahead of it: they wait. The reader is told to stop exactly at 1,250 — landing there even if
1,250 is a commit marker rather than a record — and the third query attaches. Then exactly at 1,400, and the
second attaches. From then on one reader feeds all three, and none of them was handed a record its
checkpoint already reflected.

Two design choices fall out. A **slow catch-up doesn't stall the group** — attached queries keep reading.
But a **full inbox does** stall the group: the alternative is to drop the slow query's copy, which turns
backpressure into silent loss for whichever query happened to be slowest. And change-data-capture sources
stay a reader per query, because a replication slot is confirmed at checkpoints, and a shared slot could
only be confirmed up to the slowest member's durable position — which needs its own design.

**Evidence, honestly:** `ExactSharingTest` covers late joiners, pause/resume and a query restored ahead of
the reader, over an ordered test source that counts every record any reader hands out. The file reader's
bounded read is tested. **Kafka's `pollBefore` is not tested directly against a broker** — there's no test
with transaction markers sitting right at the seam — and that's the missing piece the paper names.

---

## Hand-over 4: replacing a query without taking the answer away

You've been counting `PENDING` transactions as well as `COMPLETED` ones for a week. You fix it:

```sql
CREATE OR REPLACE CONTINUOUS QUERY txn_volume ... WITH (cutover = 'manual')
```

Here's what has to be true. The old answer keeps being served while the new version rebuilds. No reader
ever sees a mixture. And if the fix turns out to be wrong, you can go back.

**The splice.** The new version reads each partition's history from the beginning, *one record at a time*,
until its position is exactly where the running version has reached — then opens a live reader *at* that
position. One record at a time, because a batch that straddles the seam can't be stopped in the middle of
itself. A history that runs out without reaching the seam stops with `PRV-4013` rather than reading past it
and delivering the overlap twice (`OffsetSplicedReaderTest`).

**The cutover.** Both versions' feeds are paused, each is allowed to settle — sources frozen, lanes
drained, view committed — and their positions are compared **token for token, per partition, for every
stream they both read**. Equal: the name moves. Not equal: both run on briefly and it's tried again. Not
equal within a budget: refused, `PRV-4014`. "Close enough" is not a condition.

What follows from equal positions:

- Every read of the name returns the old view or the new view, never a mixture — the answer to the old
  question up to the seam, the answer to the new question after it, over *the same input*.
- The new version's view is what a from-scratch registration would hold (`QueryReplacementTest`,
  `BackfillSpliceEquivalenceTest`).
- **Subscribers are told**, with `PRV-4019`, rather than quietly switched. A client adding up changes
  would otherwise end with a total that's half one query and half another, with nothing in the stream to
  say so.
- A **sink** follows the name at a checkpoint boundary and is sent only the difference between what it
  holds and the new view — one batch (`ReplacementSinkHandoverTest`).

**The rollback window.** The replaced version keeps running — fed and committing — for an hour by default.
That's what makes rollback *the same alignment with the roles swapped*, rather than a second backfill.
After the window closes, it's released, and "roll back" is answered with "there's nothing to roll back
to". A replacement in flight also survives a restart: the journal records it, and the candidate resumes
from its own checkpoints.

One caveat is on the record: replacing a query over a PostgreSQL change feed isn't refused, and the new
version's backfill probably contends for the running version's replication slot (**CDCREPL-1**, open, not
reproduced).

---

## Lanes: a room of your own, or a shared room

The four hand-overs are about correctness. This one is about the trade an operator is actually making.

A **lane** is what runs a query: an inbox (a ring of fixed-size off-heap cells), an arena, the operators'
state, and exactly one thread at a time from a pool of one thread per core. That single-writer rule is the
whole concurrency model — no locks in steady state, because nothing else touches a lane's state.

A lane of its own costs a query about **a megabyte off-heap while idle** (the inbox). A thousand queries
on lanes of their own is a gigabyte before a row moves. So lanes can be shared: by default the first
**64** queries on a node each get their own, and after that they share a fixed set of lanes. Measured, a
thousand queries over one source on eight shared lanes hold eight inboxes, and each source row is written
into them eight times rather than a thousand (`SharedLaneDensityTest`).

Sharing doesn't change answers — every hosted input gets its own route in the row header, so two queries
over one stream on one lane don't count each other's rows (`SharedLaneIngestPropertyTest`). And turning
sharing on never admits *fewer* queries: one that fits on no shared lane gets a lane of its own rather
than a refusal.

But a shared room is a shared room, and the paper states it as a property rather than letting it be
discovered as an incident:

- A query on its **own lane fails alone.** Tested.
- Queries on a **shared lane share its fate**: one pipeline that throws takes the lane, and every query on
  it, down.
- They **share its backpressure**: one slow query slows the lane's drain for everyone on it, and a full
  inbox holds every writer feeding the lane — including a shared reader, which then stalls every query it
  feeds, on any lane.
- **Nothing is dropped** for anybody. A slow query becomes a slow read of its source.

The first is tested. The other three are argued from how a lane works; there's no test yet that fails one
query on a shared lane and checks its neighbours stop. And note this: **a tenant doesn't own a lane**. Shared
lanes are shared across tenants, so one tenant's failing query can take down another tenant's queries.
Per-tenant CPU scheduling isn't built. Today's answer for isolation is `WITH (lane = 'dedicated')`.

**Moving a query is a replacement.** Nothing rebalances lanes automatically — a lane's cost isn't knowable
at registration, and moving a running lane is exactly what the single-writer rule forbids. So when an
administrator rebalances, each move is a blue/green replacement **with the SQL unchanged** and `lane =
'own'`, one at a time, oldest first. Which means every move inherits the cutover's guarantee: nothing lost,
nothing counted twice (`LaneRebalanceTest`). I like this a lot. There's no second mechanism for moving state
between lanes to get wrong; the one administrative operation that changes where a query runs reduces to
the one operation that already had a lossless seam.

---

## Reading the answer: indexes that can't change it

Reads go through one reader for Flight SQL, REST and the PostgreSQL wire protocol alike. The whole key by
equality is a hash probe on every view. A key prefix plus a range on its last column walks an ordered index.
A non-key column you declared with `INDEX (column)` is an equality probe. Anything else scans.

Two rules make sure an index can make a read faster or slower but **never change what it says**:

1. **The filter always runs.** An access path only has to return *at least* the right rows; when it can't
   prove that, it scans. Tested by loading the same rows into two views that differ only in their key —
   so the index applies to one and not the other — and throwing generated predicates at both
   (`ViewIndexEquivalenceTest`).
2. **The index is kept from the row the view held, never from the change message.** An earlier design
   decision declined non-key indexes precisely because a retraction might not carry the old value
   correctly. The fix is that the index is never told what changed: at each commit it's handed the row the
   view *currently* holds for that key and removes the entry filed under *that* — so a retraction with a
   wrong value can't mislead it. What the index holds is a function of what the view holds, by
   construction (`SecondaryIndexTest`, which fails if you seed the obvious bug).

And a small piece of mathematics decides which columns can be indexed. A probe by *stored* equality returns
a superset of what the filter wants **if and only if** "equal to the filter" implies "equal as stored".
`DECIMAL` fails it (`1.0` and `1.00` are equal to SQL and stored differently). `FLOAT` fails it (`0.0` and
`−0.0`). `BYTES` fails it (arrays compare by identity). So those columns are refused an index at
registration, with `PRV-2074`, rather than given one that could silently miss rows.

## Security, in one paragraph

Only where it changes the model. Authorization is enforced by the engine, on the derived rows, because a
view is data the source store has never seen. A filter from outside a query — a user's row filter, a
subscriber's tap — can be applied to a view **if and only if the view still carries every column it
names**; if the query aggregated the column away, no filter applied afterwards can unmix the rows. And
two registrations share one computation only if their **fingerprints** match, where the fingerprint covers
the plan *after* security predicates are injected, the tenant, the key columns and the retention — so
sharing never crosses an entitlement or a tenant (`TenancyTest`, `SharingIdentityTest`). Sharing is
deliberately conservative: `a > 0 AND b > 5` and `b > 5 AND a > 0` are two computations, because sharing too
little costs memory and sharing too much is two people reading each other's rows.

---

## The ledger: what is tested and what is argued

| Claim | State |
|---|---|
| Z-sets form a group | runs — `ZSetTest` |
| Linear lift, `DISTINCT` lift, recomputation, integrate/differentiate | reference algebra only |
| The three-term join rule | reference algebra only — the engine's join isn't compared with it |
| Chains of views | reference algebra only |
| The engine's operators satisfy the incremental equation | in part — differential tests, no oracle |
| A key is present iff its weights sum positive | runs — `ServedViewTest` |
| The keyed view shows the right row | argued — conditional; VIEWW-1 open |
| Watermark monotone; a quiet partition doesn't freeze it | runs — `WatermarkTrackerTest` |
| A correction consolidates to the difference; too-late is counted | runs — `LateDataTest` |
| A commit is atomic to readers | runs — `ServedViewTest` |
| A commit's audience is fixed at its first batch | runs — `SnapshotHandoffTest` |
| Subscribe-then-read loses the commit in flight | runs — `SubscribeFromSnapshotTest` |
| Snapshot, then every commit exactly once | runs; ordering argued |
| The freeze and the exact marker | runs — `ControlTaskBarrierTest` |
| A checkpoint is one cut; state exactly once | runs — `AlignedCheckpointBarrierTest`, `CheckpointRecoveryTest` |
| No checkpoint across the exchange | argued — the refusal is unreachable today |
| Exactly once to a transactional sink | runs — `TransactionalSinkDeliveryTest`, broker tests |
| The weakest link | runs — `CapabilitiesTest` |
| A waiting query attaches exactly at its position | runs — `ExactSharingTest` |
| Exact-seam sharing | in part — Kafka's bounded read untested against a broker |
| The splice | runs — `OffsetSplicedReaderTest` |
| Lossless cutover | runs; "never a mixture" argued |
| The rollback window | runs — `QueryReplacementTest` |
| A replacement survives a restart | runs — `ReplacementRestartTest` |
| Single writer per lane | runs — `LaneRunnerTest` |
| Placement doesn't change answers | runs — `SharedLaneIngestPropertyTest` |
| Sharing never admits fewer queries | runs — `SharedLanePlacementTest` |
| Fate-sharing on a shared lane | in part — own-lane isolation runs; the rest argued |
| Rebalancing preserves answers | runs — `LaneRebalanceTest` |
| An access path never changes the answer | runs — `ViewIndexEquivalenceTest` |
| The index is a function of the view | runs — `SecondaryIndexTest` |
| When a stored-equality probe is sound | proved; the refusal runs |
| A filter applies iff the view carries its columns | runs — `ViewQueryAuthorizationTest` |
| Fingerprints separate entitlements and tenants | runs — `TenancyTest`, `SharingIdentityTest` |
| A read across views at one point | not built |

The rows that should worry a reader most are the "reference algebra only" ones. The paper's own
contribution — the seam theorems — is better carried by tests than the algebra it sits on. That
asymmetry is the most useful thing I can report, and it's the first thing to fix.

---

## What's been measured — and the much longer list of what hasn't

Every number below is in the repository, with the test that produced it and the load average beside it.
They were taken on **one development laptop** — a 12-core AMD Ryzen AI 9 HX 370 with two kinds of core and
SMT — that was running other build agents throughout, at load averages up to 77 on 24 logical processors.
There's no reference hardware. The evidence pack's first section says these numbers are a floor for one
machine on one afternoon, not the engine's capability. I'm repeating that because it's true.

- **The requirement is about a thousand rows a second** (ADR-042). The design's original gates — 1.2 M
  rows/s per lane for filter-and-project, 350 k for a windowed aggregate — are kept as gates.
- Filter-and-project, one lane, warm cache: best passes around **30 M rows/s**. Through the SQL layer and a
  served view, cold cache, heavily loaded machine: best passes 5.9–18.5 M, and one pass at **1.04 M** — the
  one reading below the 1.2 M gate, at load 77. That miss is the most honest single number in the pack.
- Windowed aggregate, 100,000 Zipf-distributed keys: best **2.47–2.85 M rows/s**, worst of fifteen passes 1.1 M.
- **Scaling from one lane to eight: not reached.** 28–42 % of linear on the first day — with a code-coverage
  agent attached that nobody had noticed, whose shared probe arrays made eight lanes fight over cache lines.
  33–46 % after removing it. The machine itself manages 51–55 % of linear for sixteen threads that share
  nothing. Nothing here shows the software scales, or that it doesn't.
- Generated code against interpreted: **1.7×** for the pipeline alone; **level** end to end, because one
  producer thread copying rows into the inbox is then the bound.
- **Nexmark: 12 of the 23 published queries run.** The head-to-head against Flink that the project set as a
  goal has not been run and can't be here. The eleven that don't run are missing SQL — unwindowed grouping,
  session windows, row-frame window functions — not missing speed.
- A query on its own lane: about **1 MiB off-heap idle**. A thousand queries add **24 threads** on 24 cores
  and register in **3.7 ms** each. Four queries over one Aerospike set with a shared reader: **1.0 scans a
  second in total**, where it was 1.0 each.
- Subscribers: in process, twenty subscribers cost the engine no more than one. Over Arrow Flight, ingest
  falls by about **4×** from none to twenty, because Flight serialises each batch per subscriber and offers
  no way to reuse one serialisation for two listeners.
- State spilled to memory-mapped files: within about **2×** of RAM while the page cache holds it; about
  **1,000–2,500 operations a second** once it doesn't. It's off by default and documented as survival, not
  capacity.

And what hasn't been measured, which for this article is the more important list: how long a checkpoint's
freeze holds the sources; how long a catch-up takes to converge on a moving seam; how much exact sharing
actually saves for Kafka; how long a replacement's alignment takes and how often it retries; what a slow
query on a shared lane does to its neighbours' latency; end-to-end latency from source write to visible
commit; recovery time as a function of state size; and anything with more than one node. A guarantee whose
price is unknown is a guarantee an operator can't plan around, and that's the gap I'd close second.

---

## Where this sits

Briefly, and without pretending to rank anything:

- **DBSP** is the algebra; Pravaha follows it and claims nothing new there.
- **Differential dataflow and Materialize** maintain views incrementally, serve them, and offer
  subscriptions that start with a snapshot — and, unlike Pravaha, reads that are consistent across views.
  Materialize is distributed; Pravaha is one embeddable node that reads your existing stores directly.
- **Flink** made aligned checkpoints and two-phase sinks the standard; Pravaha's checkpoint is the same idea
  on one node. Flink upgrades a job from a savepoint, which is fast and needs the new job's state to be
  compatible with the old; Pravaha recomputes the new version from history and cuts over at a position,
  which works for any SQL change and costs a replay. Complementary, not competing.
- **Kafka Streams and ksqlDB** do exactly-once through Kafka's own transactions and serve local state; they're
  built around Kafka as source, state log and sink. Pravaha treats Kafka as one source and sink among several.
- **RisingWave** maintains and serves views too, distributed, with state in object storage.
- **Noria** evolves a running dataflow in place as queries are added and shares operators between them;
  Pravaha runs a second computation and cuts over instead.
- **Shared scans** with late joiners are old database technology. For an unordered set a late joiner just
  wraps around. For an ordered, exactly-once log it can't — and the order declaration plus the bounded read
  are the contract that lets it join exactly anyway.

## What I'd hold against it

If I were reviewing this, here's what I'd push on:

- **The algebra isn't an oracle for the engine yet.** Said three times now; it's the biggest gap.
- **It's one node.** Clustering is built as libraries that nothing uses; a node refuses to start in
  partitioned mode rather than pretend. Every theorem here is a single-node theorem. (Though the replacement
  theorem suggests what moving a query between nodes should look like: another cutover at a position.)
- **Two open findings sit against stated invariants** — VIEWW-1 and CDCREPL-1 — neither reproduced.
- **Some seam evidence is partial**: Kafka's bounded read against a broker; fate-sharing on a shared lane; a
  test that races reads against the cutover.
- **The scaling gate isn't met** on the only machine there is, and there's no comparison with anything.

And what would actually *refute* the account, as opposed to finding a bug in it: a generated input where the
engine's operators disagree with the reference algebra; a snapshot subscriber whose copy drifts without
being ended; a restore over a replayable source that counts a record twice; a Kafka partition with a marker
at the seam where a late joiner gets a record twice; a read of a replaced name that returns a row from
neither version; an index probe that disagrees with a scan. Each of those is a specific, checkable claim,
which is the point of stating them.

---

## Why "always" is the hard word

The inversion at the heart of a continuous query — register the question, maintain the answer — is decades
old, and its arithmetic is settled. What makes an engine built on it trustworthy isn't the arithmetic. It's
the hand-overs: where a subscriber's copy meets the live stream, where restored state meets the replay,
where a late query meets a shared reader, and where a new version meets the old one.

Each of those is a seam. Make the seam a position rather than a moment, read positions and state together,
and refuse when you can't show they're equal — and each has a short proof whose assumptions you can read
off and check against the sources and sinks around you.

*Ask once, answer always.* "Always" means: across a subscriber joining, a restart, a shared reader and a
change of question, the answer neither skips a record nor counts one twice. That's the claim. The ledger
says how much of it the tests currently carry.

---

*The technical treatment — the definitions, the snapshot-subscription theorem and its proof from the lock
structure, the consistent-cut and two-phase-sink theorems with their assumptions, the three-state
exact-seam protocol and its induction, the splice lemma and the cutover theorem, fate-sharing, the
soundness condition for stored-equality indexes, and the full register of what runs in Pravaha — is in the
accompanying paper,*
**[Continuous Queries as Maintained Answers: Exact Cuts, Exact Seams and Lossless Cutover in a Single-Node Streaming SQL Engine](continuous-queries-as-maintained-answers.pdf)**
*([LaTeX source](continuous-queries-as-maintained-answers.tex)).*

---

Copyright © 2026 Ashutosh Sinha <ajsinha@gmail.com>.
Licensed under [CC BY-NC-ND 4.0](LICENSE). The Pravaha software described here is proprietary and is not
covered by that licence; see [`../../LICENSE`](../../LICENSE).
