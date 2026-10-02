# Concepts

> **How any of this actually runs — lanes, inboxes, arenas, and what bounds a node:**
> [`../design/EXECUTION_MODEL.md`](../design/EXECUTION_MODEL.md).
>
> **These ideas applied end to end — declaring a stream, registering a query, reading the view,
> and which SQL runs:** [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md).
>
> **Where the data comes from, and how to add a source:** [`CONNECTORS.md`](CONNECTORS.md).

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

The eight ideas everything else follows from. If you read one page before using Pravaha, this is it —
most surprises people hit are one of these working correctly.

---

## 1. A continuous query is a *computation*, not a request

You register SQL. It keeps running, and it keeps a **view** current until somebody drops it.

That is the whole inversion. A database answers a question when asked; Pravaha is told the question
in advance and maintains the answer, so asking is a hash probe rather than a scan.

```bash
pravaha register --name card_velocity --sql-file velocity.sql --keys 1
pravaha query    --sql "SELECT * FROM card_velocity WHERE card_id = ?" --params c-1002
```

The consequence worth internalising: **registering is expensive and querying is cheap.** A
registration commits the node to memory and to a share of a lane for as long as it exists. That is
why registering is authorized separately from reading, and why an anonymous caller may read but not
register.

What it costs, measured rather than asserted: about **1 MiB of off-heap while idle** and **1.3 MiB
once rows are moving**, ~65 KiB of heap, ~16 ms to register (mostly planning), and — since the lanes
were multiplexed onto a shared runner pool — **no platform thread of its own**. A node's thread count
follows its cores, not its query count. Sizing is under `pravaha.lane.*`; see
[Operations](../operations/OPERATIONS.md#sizing-a-node-for-many-queries).

**An answer can be the input of another question.** A continuous query whose `FROM` names a
registered query does not read that query's view once; it *follows its answer* — the rows it holds,
then every change to them as a row leaving (−1) and a row entering (+1) — so `cleaned` →
`by_region` → `big_regions` is three computations, each current, each exactly once across a restart,
because each carries what it has consumed of the one before it (§4 is why the retractions matter;
[ADR-056](../design/adr/056-queries-on-queries.md) and
[CONTINUOUS_QUERIES.md §3.1](CONTINUOUS_QUERIES.md#31-a-query-over-another-querys-answer) say what runs
over an answer and what is refused). A query other queries read cannot be dropped until they are.

## 2. Event time, not clock time

Every window, every watermark, every retention policy is measured in the timestamp *in the data*.

This is what makes results reproducible: load the same rows in any order, at any speed, replay them
tomorrow, and you get the same answer. A system that consulted the wall clock would give a different
answer on a reprocess, which is fatal for anything a regulator or an auditor will look at.

The practical consequence catches everyone once:

> **A window does not close because time passed. It closes because data said so.**

Load ten rows and see nothing, and that is usually correct — the engine is still willing to accept a
late row for that window, and publishing an answer it might have to retract would be worse than
publishing nothing. Send an event past the window's end and it closes.

## 3. Watermarks: "nothing earlier is coming"

A watermark is a claim about completeness. When the engine sees a watermark at *T*, it is being told
no row earlier than *T* will arrive, so everything ending at or before *T* can be published.

Watermarks are what close windows, what release join state, and what make "the answer is complete up
to here" a statement anyone can act on. Almost everything time-shaped in Pravaha is downstream of
this one idea.

> **How late is late** is declared by the stream, beside the column that carries its time:
>
> ```java
> StreamSchema.builder("orders")
>     .field("placed_at", Types.timestamp())
>     .eventTime("placed_at")
>     .outOfOrderness(Duration.ofSeconds(2))   // this source is quick and tidy
>     .build();
> ```
>
> Lateness belongs to the **source**, not the engine: a topic fed by mobile clients over a flaky
> network and a scan of data already at rest have nothing in common, and one engine-wide number has
> to be wrong for one of them. A stream that says nothing gets **10 seconds**, which a server
> deployment moves per stream with `pravaha.streams.<name>.out-of-orderness`, or for every stream
> at once with `pravaha.watermark.out-of-orderness`, which the per-stream key overrides. That
> engine-wide key was read by nothing until 2026-09-20 (DOCX-6); its default is the same 10 seconds
> a stream already took, so giving it a reader changed no deployment that had not set it.
>
> Note what this is *not*. It decides how long the engine waits before calling a window complete.
> **It is not allowed lateness**, which is what decides whether a row arriving after that is still
> applied as a retraction and a correction. Allowed lateness defaults to zero, so unless a stream
> declares it a row arriving after its window closed is counted as late and dropped. A server
> declares it per stream (`pravaha.streams.<name>.allowed-lateness`, or `allowedLateness` on
> `POST /api/v1/streams`), and an embedder on the stream's schema; either makes the correction
> path reachable.
>
> **Where watermarks come from.** Ask for them:
>
> ```java
> execution.generatingWatermarks();   // every stream uses the lateness it declared
> ```
>
> Each source partition then contributes its own watermark, the query takes the **minimum across
> them**, and a partition that has gone quiet is **excluded** rather than left holding everybody
> back — the failure that stops every window in a query and presents as a hang rather than an error.
> Event time advances on a timer, which is also what makes idleness detectable at all: a watermark
> derived only from arriving rows cannot notice that rows have stopped arriving.
>
> Without it, event time never advances: windows close only from `finish()` when the input ends,
> which is right for a file and never happens on a source that does not stop. **That is not merely
> "no output" — it is unbounded state**, because joins evict at `watermark − matchWithin` and views
> forget past the committed frontier, and neither moves. Every bound in this engine is armed by
> event time.
>
> **Which is why the declaration is required rather than encouraged.** A stream says which of its
> columns carries event time — `pravaha.streams.<name>.event-time`, `eventTime` on
> `POST /api/v1/streams`, `StreamSchema.Builder.eventTime` in code — and a windowed query over a
> stream that declares none is refused when it is registered, with `PRV-2002` naming the column to
> set. The engine can see at plan time that such a query could never emit; letting it run would be
> a query reporting `RUNNING`, ingesting everything and serving nothing, for ever (TIME-6). Over a
> **bounded** read the same query is accepted, because `finish()` closes its windows.

## 4. Changes carry weights, and a correction is a retraction plus an insert

Rows do not just arrive; they arrive with a **weight**. `+1` is a row appearing, `-1` is one being
withdrawn.

When late data corrects a window that was already published, the correction is a `-1` for the old row
followed by a `+1` for the new one — the same arithmetic as everything else, rather than a special
message type every consumer has to recognise. That is the Z-set idea from the design doc, and it is
why an aggregate can be maintained incrementally without a separate "retract" code path to get wrong.
The exception is `MIN` and `MAX`: the accumulator keeps the extreme, not the values under it, so it
cannot take a `-1`. Over a stream whose source retracts (a CDC table, a file with an operation column)
they are refused at registration, `PRV-2076`, rather than stopped at the first delete.

If you maintain your own aggregate from a subscription, **apply the weights** or your total drifts
from the view's the first time a window is corrected.

**A subscription hands you the changelog, and for a keyed view that upserts the changelog is not the
answer** (KEYEDWT-1). The changelog is what the query applied to its view, weights verbatim. For a
window correction that is the answer changing — `-1` old, `+1` new. For a view that keeps the latest
row per key over a stream that only inserts, a second row under a key arrives as `+1` for the new row
and **nothing for the one it replaced**: the view holds both and shows the newer (below). Summing the
changelog's weights then gives the view's Z-set — two rows under that key — while a reader sees one;
and a row that retention evicts leaves the view with no change delivered at all. To hold exactly
what a reader sees:

- **Follow the answer.** Register a continuous query over the view — `CREATE CONTINUOUS QUERY
  latest_copy KEYED BY (id) AS SELECT id, status FROM latest` — and subscribe to that. A query over a query is
  fed the upstream's answer as it changes, the rows that left it (`-1`) and the rows that entered it
  (`+1`) per commit, evictions included (§1, [ADR-056](../design/adr/056-queries-on-queries.md)), so its
  changelog is exactly the upstream's answer and weights summed over it are the view. Or subscribe to
  the view's answer directly: `changes="answer"` in the Python SDK, `subscribeToAnswer` in the Java
  SDK, `pravaha subscribe --answer`, `SubscriptionOptions.followingTheAnswer()` embedded — the same
  changes, over the wire since SUBANSWERWIRE-1.
- **Or subscribe from a snapshot and upsert by key**, where a key's row is only ever replaced: load
  the snapshot keeping each key's last row, then overwrite the key on each `+1`. This stays exact only
  while nothing withdraws a key's shown row — a `-1` for it brings an older row back, which a copy
  kept by key cannot know — and while retention is forever.

A row is present exactly while its weights **sum positive**, so a net weight of zero means the row is
not there. A *change* of weight zero is therefore not a change at all, and a subscription never
delivers one: it would move nothing in the view, and a consumer overwriting by key would overwrite
from a change that changed nothing (STRM-1). `ViewChange.isRetraction()` and
`isInsertion()` are both false for one built by hand — insertion is `weight > 0`, not
`!isRetraction()`.

**A view shows one row per key; its input may hold several.** Two different rows with the same key
can both be present — `A` inserted, then `B`, is weight 1 on each and 2 on the key. The view keeps
each of them with its own weight, and the key **shows the row that most recently gained weight** and
is still present. A retraction takes weight from the row it names, so withdrawing `A` leaves `B`
showing and withdrawing `B` brings `A` back; it never leaves the key showing the row just withdrawn
(VIEWW-1). The key is present while its rows' weights sum positive, as above. A subscription's
snapshot carries every row of such a key with its own weight, the shown one last, so a copy kept as a
Z-set matches the view's Z-set — every row it holds — and one that overwrites by key ends on the row
the view shows. Neither is what a reader sees once the key changes again; following the answer, above,
is. A key holding
one row at a time — nearly every key of a `GROUP BY` or a keyed projection — pays nothing for this.

## 5. Sharing is by fingerprint, not by name or text

Two registrations whose plans normalise to the same thing are **one computation with two names**,
holding one copy of the state, **as long as both come from the same tenant**. The tenant is part of
the fingerprint, as a principal's row filters are, so the same SQL from two tenants is two
computations (ADR-050).

```sql
SELECT user_id FROM txn WHERE amount > 10          -- these two are
SELECT t.user_id FROM txn AS t WHERE t.amount > 10 -- one computation
```

The fingerprint is the normalised *plan*, so different whitespace and different aliases land on the
same computation. Hashing the text instead would give each of them separate state, which is exactly
the duplication the architecture exists to remove.

**Sharing is conservative, and the boundary is worth knowing.** Normalisation goes as far as the
planner's own canonical form and no further, so two queries that a person would call identical can
still get separate computations. Reordered `AND` operands are the case you will meet first:

```sql
SELECT usr FROM txn WHERE id > 0 AND amount > 5   -- these two are
SELECT usr FROM txn WHERE amount > 5 AND id > 0   -- two computations, not one
```

The planner keeps predicates in the order the text gives them, so these fingerprint differently.
That costs a second copy of the state; it never costs a wrong answer. Sharing too little is a missed
efficiency, and sharing too much would be two queries reading each other's rows — so where the two
risks meet, this design takes the first. If you want two registrations shared, write the predicate
the same way in both.

The fingerprint includes the **security predicates** applied to the plan, which is what makes
implicit sharing safe rather than merely cheap: two principals with different entitlements produce
different plans and cannot land on each other's computation.

A computation is released when its **last** name is dropped. Neither registrant knows the other
exists, so dropping eagerly would be an outage caused by somebody tidying up their own query.

## 6. The soundness rule — one rule, three places

> **A filter supplied from outside a query can be applied to the view iff the view carries every
> column it names.**

If the query aggregated that column away, the view's rows already *mix* the values the filter is
meant to separate, and nothing applied afterwards can unmix them.

This single rule decides three different-looking things:

| Where | The question | If the rule fails |
|---|---|---|
| Security row filters | Can this be applied at read time? | Refuse the read (`PRV-7003`) |
| Subscription filters | Can this subscriber filter without forking? | Refuse the filter (`PRV-8002`) |
| Continuous-query parameters | Does this binding need its own computation? | It forks — one computation per value |

One rule showing up in three places is a sign it is real rather than convenient.

## 7. Bounds: what changes the answer, and what protects the machine

Two different kinds of limit, and Pravaha treats them differently on purpose:

| | Belongs in | When exceeded |
|---|---|---|
| **Retention** on a view, **match window** on a join | the query's *meaning* | old rows are forgotten — the policy working |
| **Ceilings**: max keys, max rows per join side, admission limits | the *configuration* | refuse, loudly — the capacity plan was wrong |

**A bound that changes the answer belongs in the query's meaning; a bound that protects the machine
belongs in configuration and should fail rather than quietly alter results.**

That is why a view *forgets* old rows (retention is a cache policy, and a view is a cache) while a
join's row ceiling *fails* (evicting to fit would silently lose matches the query asked for). Both
are bounded; only one is allowed to change what you get back.

And where the engine cannot bound something at all, it **refuses the query**: `GROUP BY user_id`
with no window keeps one accumulator per key forever, so it is rejected at planning (`PRV-2050`)
rather than deployed to fail months later. So is a `HOP` so fine that one row would land in more
windows than `pravaha.lane.max-windows-per-row` (`PRV-3026`): one such row once held its lane for good.
The same rule holds at the edges: what a client can make a node hold — PostgreSQL connections and
messages, HTTP bodies, concurrent sign-ins — is a configured ceiling refused by code, not a heap that
runs out ([LIMITS.md](LIMITS.md)).

A ceiling you cannot see coming is a ceiling you meet as an outage, so each query now reports what it
holds against what it may hold — `pravaha_query_state_held`, `_ceiling` and `_fraction`. Alert on the
fraction; `PRV-4001` used to be the first news anybody had of a query's state, and the query at nine
tenths of the way there was invisible.

## 8. Registration and subscription are separate

Registering creates a computation. Subscribing attaches a consumer to one. Many subscribers may
share a computation, and the computation outlives all of them.

That separation is why a dashboard reconnecting costs nothing — the state is warm because it belongs
to the *query*, not to whoever was watching. And it is what lets ten desks watch ten different slices
of one feed: each subscribes with its own **tap filter**, and there is still one read of the source
and one copy of the state.

A subscriber that cannot keep up never blocks the engine. Its buffer is bounded and overflow is a
declared choice — conflate, drop the oldest, or fail — and whatever is lost is **counted**, because a
subscriber silently missing data is the failure the mechanism exists to make visible.

**A consumer runs on its own subscription's thread**, in process as well as over Flight (STRM-8).
A commit hands each subscriber the batch and returns; the subscriber's own thread calls the
consumer. That is what makes the bound a bound: while your consumer is busy, the commits behind it
accumulate in *your* buffer against `bufferRows`, and your overflow policy decides what happens
when it fills. Until this, an in-process consumer was called on the committing thread and the
commit waited for it — a consumer that took two seconds made the commit take two seconds, on the
feed's publish timer, which drives every query on that feed.

The one thing that changes for you: a commit no longer ends with your consumer having been called.
A caller that steps the engine by hand — a test, an embedder — waits with
`Subscription.awaitQuiet(timeout)`, and `Subscription.pending()` says how far behind a subscriber
is right now.

## 9. Who may do what is kept with the answer (the Pravaha Catalog)

A view is an answer still being computed, so governing it means governing more than a scan: who may
**read** it, who may **subscribe** to its changes, who may **build** another query on it, who may
change it. With the catalogue on (ADR-059), every governed object — a namespace, a query and its
view, a stream, a sink — has an owner, a description, tags and a version, and the grants on it live
in the engine beside the registrations, journalled the same way. Names are `tenant.namespace.object`;
a query registered as `revenue` lands in its registrant's tenant's `default` namespace, owned by the
registrant. A grant on a namespace reaches everything in it, now and later.

Two rules carry the weight. **Reading a view needs the view's grant, not its sources'** — that is
what a view is for. **Building on a view needs `BUILD_ON` on it**, so nobody derives from what they
could not read. And subscribing is its own right, because a live stream is not a query: revoking it
ends an open subscription within seconds.

A grant says whether you may read; a **policy** says what you are shown. A **row filter** keeps some
rows (`region = session_attribute('region')`), a **mask** replaces one column's value (`'XXXX-' ||
RIGHT(card, 4)`); both are catalogue objects bound to a stream, a view or a tag, and apply to
everyone but their `EXCEPT ROLE`s. They travel with the data: a query registered over a filtered,
masked input computes over what its registrant was shown, so a view built on it can never show more,
and a masked column can be shown but never compared — grouped, joined, sorted or filtered on — by
someone it is masked for.

## 10. An alert says when a row enters an answer, and when it leaves

An answer that is being computed can also be *watched*. An **alert** (ADR-057) follows one view's
answer and, for each key, says when its row **enters** — inserted, or updated across the view's
condition — and when it **leaves** — deleted, or updated back. The second half is the one that is
usually missing, and it is honest here because of §4: the update that tops a stock line up arrives as
the old row at −1, the row leaves the view, and the alert is told the line has recovered rather than
inferring it from silence. It follows the *answer*, not the changelog, so a keyed upsert is a row
leaving and another entering, as a reader of the view sees it.

Each key has two states, kept apart: **what is true** (firing or not) and **what the receivers were
last told**. Pausing, snoozing and de-duplicating change only the second, and whenever they allow the
receivers are told the difference once — so a flap folds into its end state and a clear is never
lost. The state is **exactly once** (every decision is journalled before anything is sent, and a
restart neither re-fires a firing key nor forgets a clear it owed); delivery is **at least once**,
under an idempotency key a receiver de-duplicates on. An alert is a catalogue object, and a view an
alert follows cannot be dropped from under it.

---

## Where to go next

| | |
|---|---|
| [Quickstart](QUICKSTART.md) | Run a query in ten minutes |
| [User guide](USER_GUIDE.md) | The whole surface, task by task |
| [Streams, queries and SQL](CONTINUOUS_QUERIES.md) | What you write, end to end, and every construct checked by a test |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code and what to do |
| [Case studies](../../examples/case-studies/) | Five worked systems you can copy |
