---
title: Windows, worked
slug: windows-worked
category: sql
order: 50
icon: bounding-box
summary: "Tumbling and hopping windows row by row: which window each row lands in, when the watermark closes it, what the view holds afterwards — in both the TABLE(TUMBLE(...)) form and the GROUP BY TUMBLE form."
audience: Analysts
keywords: [tumble, hop, window_start, window_end, TUMBLE_END, HOP_END, watermark, out-of-orderness, late row, allowed lateness, sliding window, epoch aligned]
guide: continuous-queries#5-windows-worked
related: [windows, event-time-watermarks, late-data, aggregation, sql-refusals]
---

A window turns an unbounded stream into a sequence of finite groups, and that is what makes an
aggregate over a stream possible at all: a window **closes**, so its state is released. This page
works through both window shapes with real rows, shows exactly when each window is published, and
lists the details that trip people up. [Windows](/help/topics/windows) has the concept;
[event time and watermarks](/help/topics/event-time-watermarks) the clock that closes them.

## The two shapes

| | Tumbling | Hopping |
|---|---|---|
| Function | `TUMBLE(TABLE s, DESCRIPTOR(t), size)` | `HOP(TABLE s, DESCRIPTOR(t), slide, size)` |
| Windows | fixed width, non-overlapping | fixed width, overlapping, one starting every `slide` |
| A row belongs to | exactly one window | `size / slide` windows |
| Typical use | "per minute", "per hour" totals | "the last five minutes, updated every ten seconds" |

Windows are **aligned to the epoch** in event time: a one-hour window always runs from the top of an
hour to the next, whatever time the first row carried. The descriptor must name the stream's
**declared event-time column** — a window keyed to another column would be closed by a clock that
knows nothing about it, so it is refused (PRV-2002 when the column is not a timestamp).

Both expose `window_start` and `window_end` as columns. A windowed `GROUP BY` must include **both**,
or it is the unbounded case again (PRV-2050).

## Tumbling, row by row

The query: payments per ten seconds.

```sql
CREATE CONTINUOUS QUERY txn_per_10s
    KEYED BY (window_end)
AS
SELECT window_start, window_end,
       COUNT(*) AS txn_count,
       SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
GROUP BY window_start, window_end;
```

The `txn` stream declares `event-time: event_time` and `out-of-orderness: 10s`, so its watermark --
"nothing earlier than this is coming" — trails the latest event time it has seen by ten seconds. A
window is published when the watermark reaches its end.

Rows arrive in this order (the fifth is out of order, but within the ten seconds the stream allows):

| # | Arrives carrying `event_time` | amount | Lands in window | Watermark afterwards | What is published |
|---|---|---|---|---|---|
| 1 | 09:00:03 | 1200 | [09:00:00, 09:00:10) | 08:59:53 | nothing |
| 2 | 09:00:07 | 250 | [09:00:00, 09:00:10) | 08:59:57 | nothing |
| 3 | 09:00:12 | 50 | [09:00:10, 09:00:20) | 09:00:02 | nothing |
| 4 | 09:00:18 | 400 | [09:00:10, 09:00:20) | 09:00:08 | nothing |
| 5 | 09:00:05 | 70 | [09:00:00, 09:00:10) | 09:00:08 | nothing — the window is still open |
| 6 | 09:00:31 | 30 | [09:00:30, 09:00:40) | 09:00:21 | **[00,10)** and **[10,20)** close |

After row 6 the view holds:

<!-- sql: read -->
```sql
SELECT window_start, window_end, txn_count, spend FROM txn_per_10s
```

```text
window_start          window_end            txn_count  spend
2026-09-19 09:00:00   2026-09-19 09:00:10   3          1520
2026-09-19 09:00:10   2026-09-19 09:00:20   2          450
```

Three things to notice:

1. **Nothing was published for the first five rows.** That is correct, not slow: until the watermark
   passes a window's end, a late row could still change it, and publishing an answer the engine might
   have to retract is worse than publishing nothing yet.
2. **Row 5 counted.** It arrived after a later row, but its window was still open, so 1200 + 250 + 70
   = 1520.
3. **[09:00:20, 09:00:30) does not appear at all** — no row fell in it, and an empty window has no
   group to emit.

### A row that arrives too late

Now a row carrying 09:00:04 arrives after row 6. The watermark (09:00:21) is already past its
window's end. The stream declares no **allowed lateness** — zero, the default — so the row is
dropped rather than applied; the published 1520 stands. A stream that declares one
(`pravaha.streams.<name>.allowed-lateness`, or `allowedLateness` on `POST /api/v1/streams`) takes the
correction path instead: a `-1` for the old row and a `+1` for the new one.
[Late data and corrections](/help/topics/late-data) walks through it.

### Why a window "never closes"

If the stream has **no declared event time**, no watermark ever advances and no window ever closes:
the query plans, registers, reports `RUNNING`, ingests every row and emits nothing, for ever. Declare
`event-time` on the stream. The same symptom appears when a stream simply stops — the last window
closes only when a later row, or the idle-partition timer (`pravaha.watermark.idle-after`), moves the
watermark on.

## Hopping, row by row

A minute of history, recomputed every ten seconds — the slide comes first, then the size:

```sql
CREATE CONTINUOUS QUERY symbol_last_minute
    KEYED BY (symbol, window_end)
AS
SELECT symbol, window_start, window_end,
       SUM(qty) AS shares,
       COUNT(*) AS fills,
       MAX(price_cents) AS high_cents
FROM TABLE(HOP(TABLE trades, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '1' MINUTE))
GROUP BY symbol, window_start, window_end;
```

A single trade at 09:00:12 belongs to **six** windows — every one-minute window that starts on a
ten-second boundary and contains 09:00:12:

```text
[08:59:20, 09:00:20)   [08:59:30, 09:00:30)   [08:59:40, 09:00:40)
[08:59:50, 09:00:50)   [09:00:00, 09:01:00)   [09:00:10, 09:01:10)
```

Each is published when the watermark (here, five seconds behind the latest trade) passes its end --
so a steady stream of trades produces one closed window per symbol every ten seconds.

With two trades in `ACME` — 100 shares at 09:00:12 and 50 at 09:00:44 — and a later trade moving the
watermark past 09:01:10, the view holds:

<!-- sql: read -->
```sql
SELECT window_start, window_end, shares, fills FROM symbol_last_minute WHERE symbol = 'ACME'
```

```text
window_start   window_end   shares  fills
08:59:20       09:00:20     100     1
08:59:30       09:00:30     100     1
08:59:40       09:00:40     100     1
08:59:50       09:00:50     150     2
09:00:00       09:01:00     150     2
09:00:10       09:01:10     150     2
```

(Only windows that have closed appear; later ones holding the 09:00:44 trade arrive as the watermark
moves.) Cost: a hop of `size / slide = 6` holds each row's contribution six times over — the engine
slices windows internally so the work is shared, but choose the slide you need, not the smallest one
available.

## The `GROUP BY TUMBLE` form

The older group-window syntax plans too, and it is the one form in which the auxiliary functions
`TUMBLE_START` / `TUMBLE_END` / `HOP_END` work:

```sql
SELECT TUMBLE_START(event_time, INTERVAL '1' MINUTE) AS minute_start,
       TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS minute_end,
       merchant,
       COUNT(*) AS txn_count,
       SUM(amount) AS spend
FROM txn
GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), merchant
```

```sql
SELECT HOP_END(event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE) AS minute_end,
       symbol,
       SUM(qty) AS shares
FROM trades
GROUP BY HOP(event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE), symbol
```

`TUMBLE_END` does **not** work with the `TABLE(TUMBLE(...))` form — it needs a matching group
function in the `GROUP BY`, and there is none. With the table form, select `window_end`:

<!-- sql: refused PRV-2002 -->
```sql
SELECT user_id, TUMBLE_END(event_time, INTERVAL '1' HOUR) AS hour_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

```sql
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

## Sizes and units

Any day-time interval works — `SECOND`, `MINUTE`, `HOUR`, `DAY`, `WEEK`:

```sql
SELECT merchant, window_start, window_end, COUNT(*) AS txn_count
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' DAY))
GROUP BY merchant, window_start, window_end
```

```sql
SELECT region, window_start, window_end, SUM(amount) AS revenue
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end
```

Year-month intervals are the one family to avoid: a month is not a fixed length of event time.
A `RETAIN FOR INTERVAL '1' MONTH` and a join time bound in months are both refused; use days.

## Windows with filters, joins and lookups

A `WHERE` filters rows before they are windowed:

```sql
SELECT merchant, window_start, window_end, SUM(amount) AS usd_spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
WHERE currency = 'USD' AND status IS NOT NULL
GROUP BY merchant, window_start, window_end
```

`HAVING` filters the windows after they are aggregated — here, only busy minutes:

```sql
SELECT merchant, window_start, window_end, COUNT(*) AS txn_count
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end
HAVING COUNT(*) > 100
```

A window over a stream enriched from a lookup table groups by the looked-up column too:

```sql
SELECT t.user_id, p.tier, window_start, window_end, SUM(t.amount) AS total
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR)) AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
GROUP BY t.user_id, p.tier, window_start, window_end
```

## Pitfalls

!!! warning "Pitfall: a window that emits nothing, for ever"
    No `event-time` on the stream means no watermark means no window ever closes. The query is
    `RUNNING`, rows are counted in, and the view is empty. It is not slow — it is waiting for a
    clock that will never tick.

!!! warning "Pitfall: an out-of-orderness that is too generous"
    A 10-minute out-of-orderness holds every window open ten minutes past its end. Windows close
    correctly but lag real time badly. Set it to what the source actually needs.

!!! warning "Pitfall: renaming `window_end`"
    Selecting `window_end AS hour_end` in a windowed aggregate is refused with PRV-2050 today even
    though the `GROUP BY` names both columns. Keep the name and rename it in the reading query.

!!! note "Name your aggregates"
    `COUNT(*)` with no alias is `EXPR$2` in the view. `KEYED BY` and every reader want a real name.

## Where next

- [Late data and corrections](/help/topics/late-data) — what happens after a window closes.
- [Event time and watermarks](/help/topics/event-time-watermarks) — the clock itself.
- [Aggregation](/help/topics/aggregation) — every aggregate function and its rules.
