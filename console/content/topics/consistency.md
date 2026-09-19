---
title: What a read is consistent with
slug: consistency
category: reading
order: 30
icon: check2-square
summary: "A read sees a view at its last commit, never half of one. What a commit is, how it relates to the watermark and to retention, what two reads of two views can and cannot promise, and what a read sees during a correction."
audience: Developers
keywords: [commit, frontier, committed, watermark, retention, evict, snapshot, stale, reconcile, isolation, Consistency, CONSISTENT, LATEST, AS_OF]
guide: architecture#what-a-lane-is
related: [point-reads, subscriptions, event-time-watermarks, late-data, views-and-keys]
---

"Is this number right?" has three parts for a streaming engine: *right as of when*, *complete up to
where*, and *consistent with what else*. Pravaha answers each of them precisely, and this page says how,
so a reader can tell a number that is still being revised from one that is final, and a view that is
behind from one that is wrong.

## The short version

| Question | Answer |
|---|---|
| Can a read see half a commit? | **No.** A view keeps its committed and pending rows apart; a read and a subscription see only committed ones |
| Can a read see an update half-applied — the old row gone, the new one not yet there? | **No.** A retraction and its insert are committed together |
| Is a value final once I have read it? | **Not while its window can still change.** A window emits when the watermark passes its end, and a late row within the lateness allowance corrects it |
| Will two reads of one view a second apart agree? | Not necessarily: the view is live, and a commit may land between them |
| Will reads of two different views agree on the same input? | Each is consistent with itself. Two views on different lanes commit independently, so there is no promise that both reflect exactly the same input prefix at the same instant |
| Can rows disappear with no retraction? | Yes, by **retention**: rows older than the view's retention in event time are evicted |
| Does a failed query's view keep answering? | **No.** It refuses reads with PRV-8004 rather than present a frozen snapshot as current |

## Commits: the unit of visibility

Rows reach a query in batches through its lane. After the lane has run a batch through every operator,
the changes the batch made to the view are **committed** at once. Until then they are *pending* and
invisible. That single rule gives three guarantees:

1. **A read sees whole commits.** It is answered from the committed rows — the view's *committed
   frontier* — and planned by the same planner and operators a continuous query uses.
2. **A subscription delivers whole commits**, as one batch each, in order. A subscriber attaching
   mid-commit gets the next one whole (STRM-11).
3. **An update is atomic.** A commit ends only where the engine finished a batch, so the `-1` of an
   old row and the `+1` of its replacement are always in the same commit (VIEW-1). A reader never sees
   a key vanish between the two.

```text
          lane processes a batch                commit                    next batch
  pending: [-1 old spend=5400][+1 new spend=6650] ──────► visible to reads and subscribers together
  reads before the commit see spend=5400; reads after see spend=6650; no read sees neither.
```

## Watermarks: complete up to where

A commit makes rows *visible*; the **watermark** decides when a windowed result *exists* at all. The
watermark is the engine's statement that "no row earlier than this is still coming", derived from the
event times arriving minus the stream's declared out-of-orderness. A window's result is emitted when the
watermark passes the window's end.

So for a windowed view such as

```sql
CREATE CONTINUOUS QUERY merchant_minutes
    KEYED BY (merchant, window_end)
AS
SELECT merchant, window_start, window_end, COUNT(*) AS payments, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end;
```

a read returns only windows the watermark has passed:

<!-- sql: read -->
```sql
SELECT merchant, window_end, payments, spend
FROM merchant_minutes
WHERE merchant = ?
```

```text
merchant	window_end	payments	spend
ACME-GROCERY	2026-09-19T09:30:00Z	41	5400
ACME-GROCERY	2026-09-19T09:31:00Z	37	4980
2 rows
```

With `txn` declared with 10 seconds of out-of-orderness, the 09:31–09:32 window appears once rows with
event times past 09:32:10 have arrived — not at 09:32 on the wall clock. A windowed view is therefore
always *behind real time by the out-of-orderness plus however long the input takes to arrive*, and that
lag is the price of a result that does not have to be revised every time a straggler turns up.

A join across two streams takes the **minimum** of their watermarks, so it is only as current as its
slowest input. [Event time and watermarks](/help/topics/event-time-watermarks) has the whole mechanism,
including idle sources.

## Corrections: final is a matter of degree

A row that arrives late — after its window emitted, but within the allowed lateness — does not produce
a second answer. It produces a **correction**: the old row retracted, the new one inserted, in one
commit. What a reader sees:

| When you read | You see |
|---|---|
| Before the window closes | No row for the window |
| After it closes, before the late row | `spend = 5400` |
| After the late row's commit | `spend = 6650` |

A subscriber sees both halves (`-1` then `+1`); a reader sees only the current state. Neither ever sees
a total that was never true. The view's rows are **the answer so far**, and the retraction is how the
engine says "that was the answer, this is the answer now" without inventing a second message type. See
[Late data and corrections](/help/topics/late-data).

!!! tip "Telling a final number from a provisional one"
    A window's result can no longer change once the watermark has passed its end plus the allowed
    lateness. The console's query page and `GET /api/v1/queries/{name}/plan` report the query's
    watermark; compare it with `window_end` to know whether a row can still move.

## Retention: what a view forgets

A view keeps rows until they fall further behind the committed frontier, **in event time**, than its
retention. Retention is chosen at registration (`RETAIN FOR P7D`, `--retain PT24H`, `retention=` in the
SDKs) or left out, which keeps rows for ever: a server has no setting that changes that default.

```sql
CREATE CONTINUOUS QUERY recent_spend
    KEYED BY (user_id, window_end)
    RETAIN FOR PT24H
AS
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

Two things follow for readers:

- **Eviction is not a retraction.** A row that ages out simply stops being in the view; subscribers are
  not sent a `-1` for it. A downstream copy that must mirror the view exactly applies the same
  retention itself.
- **Eviction is by event time, not wall clock.** A view whose input has stopped keeps its rows, because
  its frontier has stopped too.

`pravaha_query_view_evicted{query=...}` on the node's Prometheus endpoint counts what retention has
removed. Retention is part of the query's fingerprint: the same SQL kept for different lengths of time
is two computations. See [Views and keys](/help/topics/views-and-keys).

## Two views, one question

Each registered query has its own lane (or shares one, but commits on its own), and each view is
consistent **with itself**. Reading `hourly_spend` and then `big_txn` gives each at its own latest
commit. They agree on the input only to the extent that both have processed it:

<!-- sql: read -->
```sql
SELECT user_id, SUM(spend) AS spend FROM hourly_spend WHERE user_id = ? GROUP BY user_id
```

<!-- sql: read -->
```sql
SELECT user_id, SUM(amount) AS large_spend FROM big_txn WHERE user_id = ? GROUP BY user_id
```

If the second is momentarily ahead of the first, `large_spend` can exceed `spend` for an instant. When
two numbers must reconcile exactly, compute them **in one continuous query**, so they are committed
together:

```sql
CREATE CONTINUOUS QUERY spend_breakdown
    KEYED BY (user_id, window_end)
AS
SELECT user_id, window_start, window_end,
       SUM(amount) AS spend,
       SUM(CASE WHEN amount > 1000 THEN amount ELSE 0 END) AS large_spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

!!! note "Declared, not yet selectable per read"
    Both SDKs define a `Consistency` enumeration — `CONSISTENT` (the committed frontier, the default),
    `LATEST`, `AS_OF` and `AT_LEAST` — and `ClientOptions` carries a default. The server does not yet
    accept a per-read choice: every read is answered at the committed frontier, which is `CONSISTENT`.
    The other modes are the design's next step, and a knob that did nothing would be worse than none.

## Pausing, failing, restarting

| State | What a read sees |
|---|---|
| `RUNNING` | The committed frontier, advancing |
| `PAUSED` | The frontier it reached when paused — reads succeed, rows stop changing; rows arriving meanwhile are dropped, not buffered |
| `FAILED` | Nothing: reads are refused with PRV-8004, naming the failure and saying the held rows were correct as of it |
| After a restart | The state restored from the last checkpoint, then the source replayed from there; a reader may see the view briefly behind where it was before the restart, never ahead or wrong |

## Pitfalls

!!! warning "Pitfall: reading a windowed view as if it were real time"
    A window's row appears when the watermark passes its end, so the newest window is always missing
    from a read. A dashboard that expects a row for the current minute will show a gap that is
    correct.

!!! warning "Pitfall: summing across two views"
    Numbers from two views are each correct and may not reconcile at the same instant. Put numbers
    that must reconcile in one query.

!!! warning "Pitfall: a mirror that ignores retention"
    A downstream copy built from a subscription keeps rows the view has already evicted, because
    eviction sends no retraction. Apply the same retention to the copy.

## Where next

- [Point reads](/help/topics/point-reads) and [Subscriptions](/help/topics/subscriptions).
- [Event time and watermarks](/help/topics/event-time-watermarks).
- [Late data and corrections](/help/topics/late-data).
