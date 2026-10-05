---
title: Subscriptions
slug: subscriptions
category: reading
order: 20
icon: broadcast
summary: "Being told instead of asking: every committed change to a view, whole commits only, each row with its +1/-1 weight, filtered at the tap — from the CLI, both SDKs and the embedded engine."
badge: STREAM
audience: Developers
keywords: [subscribe, tail, changes, changelog, weight, retraction, filter, tap, commit, conflate, slow subscriber, snapshot, mirror, "--filter", "--limit", "--snapshot", PRV-6105]
guide: user-guide#4-subscribe
related: [zset-weights, event-time-watermarks, views-and-keys, consistency, clients]
---

A point read asks a view for its rows now. A **subscription** is told about every change to the view
from the moment it attaches: each commit the engine makes arrives as one batch of rows, and each row
carries a **weight** — `+1` for a row appearing, `-1` for a row being withdrawn. Starting from the
view's rows and then applying every change since is how a live dashboard, a cache, or a downstream
service stays exactly in step with the engine without ever re-reading the view — and a subscription
**from a snapshot** hands you both, with nothing between them (see
[Keeping a full copy](#keeping-a-full-copy-subscribe-from-a-snapshot)).

## At a glance

| | |
|---|---|
| What you name | A view (a registered continuous query's name), and optionally equality filters |
| What you receive | One batch per **commit**, never a partial one, in commit order |
| Each row | The view's columns, plus a weight: `+1` inserted, `-1` retracted |
| Starts | At the next commit after you attach — or, subscribing **from a snapshot**, with the view's rows at a commit and then every commit after it |
| Filters | `column = value` pairs, applied on the server at the tap; an unknown column is refused |
| Carriers | Flight (`pravaha subscribe`, `client.subscribe` in both SDKs, the console's live page), the embedded engine's `subscribe`, a Spring `@PravahaListener` |
| Slow subscriber | Loses whole commits on the server side rather than slowing the query; the loss is recorded in the audit trail |
| Authorization | Checked when you attach and re-checked every two seconds while it runs; a revoked credential or a withdrawn grant ends it |

## Your first subscription

A view of every card payment over 1,000 — the shape of the example deployment's `big_txn`:

```sql
CREATE CONTINUOUS QUERY large_payments
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, merchant, amount
FROM txn
WHERE amount > 1000;
```

From the shell:

```bash
pravaha subscribe --view large_payments
```

```text
subscribed to large_payments; changes print as they are committed. Ctrl-C to stop.
WEIGHT	txn_id	user_id	merchant	amount
+1	9001	u1	ACME-GROCERY	1250
+1	9004	u7	TRAVELCO	4800
-- commit, 2 rows
+1	9012	u3	ACME-GROCERY	1100
-- commit, 1 row
```

(Sample rows.) Each change leads with its weight, always signed — `+1` a row arriving, `-1` a row
withdrawn — under a `WEIGHT` header printed with the first change. Each group ending in
`-- commit, N rows` is one commit. Nothing prints until the next
commit after you attach: a subscription is the changes from *now*, not a replay of the view.

Stop after a number of rows, which is what a script wants — at the end of the commit that reaches
it, so a commit is never cut in half (at least 1; leave `--limit` off to follow until Ctrl-C):

```bash
pravaha subscribe --view large_payments --limit 100
```

!!! note "Read the weight column"
    For an append-only view like `large_payments` every line is `+1`. For a view that corrects
    itself, a withdrawn row prints at `-1` and its replacement at `+1` in the same commit — the same
    thing the SDKs and the console's live page (`/views/<name>/live`) show.

## Weights, and why a consumer must apply them

A view that revises its answer — any windowed aggregate that accepts late data, any correction —
changes a row by **withdrawing the old one and inserting the new one in the same commit**:

```sql
CREATE CONTINUOUS QUERY spend_by_merchant
    KEYED BY (merchant, window_end)
AS
SELECT merchant, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end;
```

A late payment for a window that already emitted produces, in one commit:

```text
merchant      window_end             spend   weight
ACME-GROCERY  2026-09-19T09:31:00Z   5400    -1     <- the old answer, withdrawn
ACME-GROCERY  2026-09-19T09:31:00Z   6650    +1     <- the corrected one
```

Two correct ways to consume that:

| You keep | Do this |
|---|---|
| **Current values by key** (a cache, a dashboard tile) | Overwrite by key on `+1`; delete by key on `-1` when no `+1` for the key follows in the same commit — or simply skip `-1` rows, since the `+1` replaces them |
| **A running total of your own** | Add `weight × value` for every row. The `-1` is what cancels the value being corrected; counting rows double-counts it. Over a keyed view that upserts, subscribe to the answer instead — below |

Python, keeping a total across every merchant:

```python
from pravaha import connect

total = 0
with connect("grpc://localhost:19090") as client:
    for batch in client.subscribe("spend_by_merchant"):
        for row in batch:
            total += row.weight * row["spend"]
        print("commit of", len(batch), "rows; total spend now", total)
```

```text
commit of 3 rows; total spend now 18250
commit of 2 rows; total spend now 19500
```

Java, the same:

```java
long[] total = {0};
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:19090");
     Subscription subscription = client.subscribe("spend_by_merchant", batch -> {
         for (Row row : batch) {
             total[0] += row.weight() * row.getLong("spend");
         }
         System.out.println("commit of " + batch.size() + " rows; total " + total[0]);
     })) {
    subscription.run();   // blocks this thread until closed
}
```

### A keyed view that upserts: the changelog is not the answer

A subscription hands you the **changelog** — what the query applied to its view, weights verbatim.
For a correction that is also how the answer changed. For a view that keeps the **latest row per
key** over a stream that only inserts, it is not: a second row under a key arrives as `+1` for the
new row and **nothing for the row it replaced**, because the view holds both and shows the newer. A
consumer summing weights then holds two rows where a reader sees one, and a row retention evicts
leaves the view with nothing delivered (KEYEDWT-1). To hold exactly what a reader sees, **follow the
answer**: register a continuous query over the view and subscribe to that one.

```text
CREATE CONTINUOUS QUERY latest_status_copy KEYED BY (order_id)
AS SELECT order_id, status FROM latest_status;
```

A query over a query is fed its upstream's answer as it changes — per commit, the rows that left it
at `-1` and the rows that entered it at `+1`, evictions included — so its changelog is the upstream's
answer and weights summed over it are exactly the view
([queries on queries](/help/topics/create-continuous-query#queries-on-queries)). Or ask a
subscription for those changes directly: `client.subscribe(view, changes="answer")` in Python,
`subscribeToAnswer` / `subscribeToAnswerFromSnapshot` in the Java SDK, `pravaha subscribe --answer`,
and `SubscriptionOptions.DEFAULT.followingTheAnswer()` embedded (SUBANSWERWIRE-1). A server older than
the SDK refuses such a ticket as one it does not know, rather than handing over the changelog.
Overwriting by key from a plain subscription is exact only while nothing withdraws a key's newest row
(a `-1` for it brings an older row back) and retention is forever.

The weight is **not one of the view's columns**: `row.columns()` lists what the query selected, and a
positional read gets the column it always got. `row.isRetraction()` (Java) and `row.is_retraction`
(Python) are `weight < 0`.

## Filters: a slice, at the tap

A filter is a set of `column = value` equalities applied on the server, so rows you did not ask for
never cross the network — and every subscriber, each with its own filter, reads the **same
computation**:

```bash
pravaha subscribe --view large_payments --filter merchant=TRAVELCO
pravaha subscribe --view spend_by_merchant --filter merchant=ACME-GROCERY
```

```python
for batch in client.subscribe("large_payments", {"merchant": "TRAVELCO"}):
    ...
```

```java
client.subscribe("large_payments", Map.of("merchant", "TRAVELCO"), batch -> { /* ... */ });
```

| Filter rule | |
|---|---|
| Equality only | Ranges and expressions belong in the continuous query's `WHERE` |
| Several pairs | All must match (`--filter a=1,b=2`) |
| A column the view does not have | **Refused**, before a single batch — a filter silently dropped would leave you receiving everything while believing you had a slice |
| Values | Compared exactly with the value the view holds. A value travels as text and is read as its **column's type**: `--filter txn_id=9001` on an `INT64` column matches the number 9001, a `BOOLEAN` takes `true`/`false`, a `DECIMAL` is read at the column's scale, and `DATE`, `TIME` and `TIMESTAMP` take their stored numbers (days since the epoch, nanoseconds of the day, nanoseconds since the epoch). Text that is not a value of the column's type is **refused** when you subscribe, like an unknown column |

## Whole commits, and what that guarantees

**A batch is a commit.** A commit ends only where the engine finished a batch of input, so:

- an update's retraction and its insert always arrive **together** — a subscriber never sees a key
  vanish for one commit between the two;
- a window is never delivered half-closed;
- a subscriber attaching in the middle of a commit receives the **next** one whole, not the tail of
  this one.

This is why a consumer can act on each batch as a consistent step of the view: after applying batch
*n*, its copy equals the view as of commit *n*.

## Keeping a full copy: subscribe from a snapshot

A plain subscription starts from now, and **reading the view beside it does not close the gap**.
Subscribe then read, or read then subscribe: either way the commit in flight at that moment can
reach you by neither path — it is not in the rows you read, and your subscription was not in its
audience — and nothing says so (SUB-1).

Subscribe **from a snapshot** instead. The first batch is the view as a commit left it — every row,
filtered, each with its multiplicity as its weight, sent even when there are none — and every batch
after it is a commit after that one. Load the first, apply the rest by weight: nothing is missed and
nothing counted twice.

```bash
pravaha subscribe --view large_payments --snapshot
```

```text
WEIGHT	txn_id	user_id	merchant	amount
+1	9001	u1	ACME-GROCERY	1250
-- snapshot at frontier 41, 1 row
+1	9012	u3	ACME-GROCERY	1100
-- commit, 1 row
```

```python
for batch in client.subscribe("large_payments", snapshot=True):
    if batch.snapshot:
        copy = {row["txn_id"]: row.to_dict() for row in batch}
    else:
        for row in batch:
            ...   # apply by weight
```

```java
client.subscribeFromSnapshot("large_payments", batch -> {
    if (batch.isSnapshot()) { /* load: its rows are copies and may be kept */ }
    else { /* apply by weight */ }
});
```

In the embedded engine, `engine.subscribeFromSnapshot(name, listener)` calls `onSnapshot` once and
then `onCommit` per commit. The console's live page starts from the same kind of snapshot: the stream
it opens sends the view first and then the changes.

A server older than the client refuses a snapshot subscription with `PRV-6102`; a plain subscription
still works there, with the gap above.

## When you cannot keep up

A subscriber must never slow the query, because the query is shared by everyone else reading it. So
the server hands each commit to your connection without blocking, through a bounded hand-over of 64
commits:

| Where | Bound | What happens past it |
|---|---|---|
| Server, per Flight subscription | 64 commits **or** 250,000 rows across them, whichever comes first | Further commits are **dropped whole** for this subscriber, and the running count rides on every later batch (`batch.droppedBefore`, `batch.dropped_before`) as well as being recorded in the audit trail when the subscription ends |
| Server, per Flight **snapshot** subscription | the same 64 commits | Nothing is dropped: the stream **ends with `PRV-6105`**, because a copy missing a commit is silently wrong. Subscribe again to start from a fresh snapshot |
| Server, between the view and the tap | what you asked for, or 10,000 rows conflated by key | Your policy decides: `CONFLATE`, `DROP_OLDEST` or `FAIL` |
| Embedded engine | `SubscriptionOptions.of(bufferRows, Overflow)` | `FAIL` ends *your* subscription, never the query |

**Two bounds on the hand-over, and the second one is why** (STRM-15). 64 is small in *batches*, and
a batch is one commit: a commit under a real feed has been measured at 2,830 rows, so the queue
could hold hundreds of thousands of rows and one stalled subscriber was measured taking 1.7 GB of
the server's heap. The row bound is the one that says what the queue costs.

**Your overflow policy now reaches the server** (STRM-16). `subscriberBufferRows` and
`conflateOnOverflow` in the Java SDK, and `buffer_rows=` / `overflow=` on the Python
`subscribe(...)`, ride on the subscription ticket. Until they did, every remote subscriber was
`(10000, CONFLATE)` whatever it set — and `CONFLATE` is, by its own definition, wrong for anything
maintaining its own aggregate from the weights, because it drops the intermediate weights that
aggregate is built from. Ask for `FAIL` if a gap is worse than a stop. An overflow policy the
server does not recognise is refused, not defaulted.

!!! note "A lost commit is no longer silent"
    Over Flight, a plain subscriber that falls past either bound loses whole commits — deliberately,
    so one slow client cannot slow the query — and every batch after that carries how many
    (`batch.droppedBefore` in Java, `batch.dropped_before` in Python, `subscription.dropped()` for
    the running total). Non-zero means the rows you hold are not the view. For a ledger or anything
    that must not miss a change, subscribe from a snapshot: it is ended with `PRV-6105` instead of
    skipped past a commit, and subscribing again starts from a fresh snapshot. Either way, keep up
    by handing the work to your own queue.

## Rows are flyweights (Java)

In the Java SDK each `Row` points into the Arrow buffer that carried its commit, and that buffer is
reused for the next commit. Copy anything you keep past the callback:

```java
client.subscribe("large_payments", batch -> {
    for (Row row : batch) {
        cache.put(row.getLong("txn_id"), row.toArray());   // a copy, not the Row
    }
});
```

## In the embedded engine and Spring

```java
engine.subscribe("large_payments", changes -> changes.forEach(c ->
        System.out.println((c.isRetraction() ? "- " : "+ ") + c.values())));
```

```text
+ {txn_id=9001, user_id=u1, merchant=ACME-GROCERY, amount=1250}
```

The consumer runs **on the subscription's own thread, and the commit does not wait for it**
(STRM-8). A commit files the batch in your buffer and returns, so a consumer that takes two seconds
costs the engine nothing and costs *you* two seconds of backlog: the commits behind it accumulate
against `bufferRows` and your overflow policy decides what happens when that fills. Each delivery
is one whole commit — four commits behind is four batches, never one merged batch. It used to be
called on the committing thread, which in a configured node is the feed's publish timer that drives
every query on that feed, and the buffer then bounded *one commit* rather than a backlog.

Because delivery is no longer finished when the commit returns, a caller that steps the engine by
hand waits with `Subscription.awaitQuiet(timeout)`. In Spring,
`@PravahaListener(query = "large_payments")` and `PravahaTester.awaitListeners(...)` do this for
you — see [Spring Boot starter](/help/topics/spring-boot-starter).

## Pitfalls

!!! warning "Pitfall: nothing arrives from a windowed view"
    A window emits when the watermark passes its end — when *data* says the window is over, not when
    the clock does. With no new events past the window's end, nothing is committed, so nothing is
    sent. See [Event time and watermarks](/help/topics/event-time-watermarks).

!!! warning "Pitfall: counting rows instead of applying weights"
    A consumer that does `count += len(batch)` or `total += row["spend"]` double-counts every
    correction. Multiply by the weight.

!!! warning "Pitfall: a paused or failed query"
    A paused query commits nothing, so its subscribers hear nothing. A failed one ends every
    subscription to it. Check the query's state before debugging the subscriber.

## How a subscription ends

Four endings, and they are told apart on the wire (STRM-12). Until they were, an administrative
drop, a node shutting down and your own `close()` all arrived as a clean completion — "this stream
is finished" — and only the last of them is.

| Ending | On the wire | What to do |
|---|---|---|
| You closed it | the stream ends normally | nothing |
| The name was **dropped** | `PRV-8018`, Flight status `NOT_FOUND` | stop. The name does not exist any more, and what you received is complete up to the drop |
| The **node is shutting down** | `PRV-8019`, Flight status `UNAVAILABLE` | reconnect. The query is journalled and comes back `RUNNING`; read the view to catch up on the commits you missed |
| Your **entitlement** was withdrawn, or the credential expired | `PRV-7002` / `PRV-7001` | re-authenticate, or ask for the grant back |

`PRV-8018` also ends a subscription on a name that was **sharing** a computation. Two registrations
over the same question are one computation with two names; dropping one leaves the other running,
and a subscriber on the dropped name used to go on receiving rows under a name a read of the view
refused as nonexistent (STRM-14). A subscriber on the surviving name is unaffected, which is the
point of sharing.

In process it is the same event: the `Subscription` is closed, `failure()` carries the reason, and
the query's `subscriberCount()` goes back to zero — it never used to after a drop, so the number an
operator reads as "nobody is watching this" was permanently wrong.

## Surviving a restart: reconnect

The SDKs can do the "reconnect" in the table above for you. Ask for it when you subscribe, and a
stream that a restart ended -- or that broke with no diagnosis, or ended with `PRV-6105` for falling
behind -- is opened again, with backoff from 250 ms to 10 s, for up to five minutes without a stream
(configurable; or for ever). A refusal that will not change, such as a dropped name, is still raised
at once.

```python
for batch in client.subscribe("large_payments", snapshot=True, reconnect=True):
    if batch.snapshot:
        copy = {}                      # a fresh snapshot, first and after every reconnect: replace
    apply(copy, batch)
```

```java
ReconnectingSubscription s = client.subscribeFromSnapshot("large_payments", Map.of(),
        ReconnectingSubscription.Reconnect.defaults().onReconnected(copy::clear), copy::apply);
s.run();                               // blocks; s.close() from another thread ends it
```

**Pair it with a snapshot subscription.** The first batch after reopening is then a fresh snapshot
of the view -- replace what you hold with it, and nothing committed while the node was down is lost
or counted twice. A plain subscription resumes at the next commit, and what was committed in between
is not delivered; Python marks that batch `batch.reconnected`, Java calls `onReconnected` before it.
Calls other than subscriptions need nothing: they fail with `PRV-1040` (retryable) while the node
is down and work again as soon as it is back.

## Where next

- [Z-set weights and retractions](/help/topics/zset-weights) — the algebra behind `+1` and `-1`.
- [Late data and corrections](/help/topics/event-time-watermarks#late-data) — where retractions come from.
- [Consistency](/help/topics/consistency) — how a subscription and a read relate.
- How it is built: [Architecture: subscriptions](/help/architecture-registry#subscriptions)
