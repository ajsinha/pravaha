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
related: [zset-weights, late-data, point-reads, consistency, client-snippets]
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

Stop after a number of rows, which is what a script wants:

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
| **A running total of your own** | Add `weight × value` for every row. The `-1` is what cancels the value being corrected; counting rows double-counts it |

Python, keeping a total across every merchant:

```python
from pravaha import connect

total = 0
with connect("grpc://localhost:9090") as client:
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
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090");
     Subscription subscription = client.subscribe("spend_by_merchant", batch -> {
         for (Row row : batch) {
             total[0] += row.weight() * row.getLong("spend");
         }
         System.out.println("commit of " + batch.size() + " rows; total " + total[0]);
     })) {
    subscription.run();   // blocks this thread until closed
}
```

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
| Server, per Flight subscription | 64 commits waiting to be written to your connection | Further commits are **dropped whole** for this subscriber; when the subscription ends the number dropped is recorded in the audit trail (`N batches dropped for a slow subscriber`) |
| Server, per Flight **snapshot** subscription | the same 64 commits | Nothing is dropped: the stream **ends with `PRV-6105`**, because a copy missing a commit is silently wrong. Subscribe again to start from a fresh snapshot |
| Server, between the view and the tap | 10,000 rows, conflated by key | The latest value per key wins |
| Embedded engine | `SubscriptionOptions.of(bufferRows, Overflow)` — `CONFLATE`, `DROP_OLDEST` or `FAIL` | `FAIL` ends *your* subscription, never the query |

The SDKs' `subscriberBufferRows` / `subscriber_buffer_rows` and `conflateOnOverflow` options are
declared but not yet sent to the server; the server's own bounds above are what apply over Flight.

!!! warning "Pitfall: a lost commit is silent to the client"
    Over Flight, a plain subscriber that falls more than 64 commits behind loses commits and is not
    told on the stream; the audit trail records it. For a ledger or anything that must not miss a
    change, subscribe from a snapshot: it is ended with `PRV-6105` instead of skipped past a commit,
    and subscribing again starts from a fresh snapshot. Either way, keep up by handing the work to
    your own queue.

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

The consumer runs **on the committing thread**: keep it short or hand the work on. In Spring,
`@PravahaListener(query = "large_payments")` does the hand-off for you — see
[Spring Boot starter](/help/topics/spring-boot-starter).

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

## Where next

- [Z-set weights and retractions](/help/topics/zset-weights) — the algebra behind `+1` and `-1`.
- [Late data and corrections](/help/topics/late-data) — where retractions come from.
- [Consistency](/help/topics/consistency) — how a subscription and a read relate.
