# Concepts

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

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
registration commits the node to memory and a share of every lane for as long as it exists. That is
why registering is authorized separately from reading, and why an anonymous caller may read but not
register.

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

> **Where watermarks come from.** Ask for them:
>
> ```java
> execution.generatingWatermarks(() -> WatermarkGenerator.boundedOutOfOrderness(Duration.ofSeconds(5)));
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

## 4. Changes carry weights, and a correction is a retraction plus an insert

Rows do not just arrive; they arrive with a **weight**. `+1` is a row appearing, `-1` is one being
withdrawn.

When late data corrects a window that was already published, the correction is a `-1` for the old row
followed by a `+1` for the new one — the same arithmetic as everything else, rather than a special
message type every consumer has to recognise. That is the Z-set idea from the design doc, and it is
why an aggregate can be maintained incrementally without a separate "retract" code path to get wrong.

If you consume a subscription and only want current values, ignore negative weights and overwrite by
key. If you maintain your own aggregate, **apply the weights** or your total drifts from the view's
the first time a window is corrected.

## 5. Sharing is by fingerprint, not by name or text

Two registrations whose plans normalise to the same thing are **one computation with two names**,
holding one copy of the state.

```sql
SELECT user_id FROM txn WHERE amount > 10          -- these two are
SELECT t.user_id FROM txn AS t WHERE t.amount > 10 -- one computation
```

The fingerprint is the normalised *plan*, so different whitespace, different aliases and reordered
`AND` operands all land on the same computation. Hashing the text instead would give each of them
separate state, which is exactly the duplication the architecture exists to remove.

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
rather than deployed to fail months later.

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

---

## Where to go next

| | |
|---|---|
| [Quickstart](QUICKSTART.md) | Run a query in ten minutes |
| [User guide](USER_GUIDE.md) | The whole surface, task by task |
| [What SQL it runs](SQL_SUPPORT.md) | Every construct, checked by a test |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code and what to do |
| [Case studies](../examples/case-studies/) | Four worked systems you can copy |
