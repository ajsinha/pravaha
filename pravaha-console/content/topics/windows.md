---
title: Windows
slug: windows
category: concepts
order: 50
icon: bounding-box
summary: "A window turns an unbounded stream into finite groups, which is what lets an aggregate keep bounded state. Tumbling and hopping, when each emits, what it costs, the two ways to write one — and both worked row by row."
audience: Analysts
keywords: [window, tumble, tumbling, hop, hopping, sliding, session, window_start, window_end, TUMBLE_END, HOP_END, descriptor, bounded state, watermark, out-of-orderness, late row, allowed lateness, epoch aligned, worked, row by row, PRV-2050]
guide: continuous-queries#5-windows-worked
related: [event-time-watermarks, aggregation, views-and-keys, joins, sql-refusals]
---

A stream never ends, so an aggregate over "all of it" never finishes and its state never stops
growing. A **window** cuts the stream into finite groups by event time — this minute, this hour, the
last five minutes every thirty seconds — and each group *closes*. When it closes its answer is
published and its state is released. That is the whole reason windowing exists in this engine:
**a windowed aggregate's state is (keys × open windows), not (keys × history).**

Windowed aggregation is the engine's primary use, not a feature bolted onto it. A continuous query
that only filters is a `grep` with extra steps; the value is in maintaining `COUNT`, `SUM`,
`COUNT(DISTINCT)` over windows and keeping them correct as data arrives.

## The kinds

| Kind | SQL | Each row belongs to | Use for |
|---|---|---|---|
| **Tumbling** | `TUMBLE(TABLE s, DESCRIPTOR(event_time), size)` | exactly one window | per-minute / per-hour totals |
| **Hopping** (sliding) | `HOP(TABLE s, DESCRIPTOR(event_time), slide, size)` | size ÷ slide windows | "the last 5 minutes, every 30 seconds" |
| Session | — | — | implemented in the runtime, **no SQL surface yet**: refused with PRV-2020 |

```text
tumbling, 1 minute:   [09:00, 09:01)  [09:01, 09:02)  [09:02, 09:03)
hopping, slide 30s, size 1 minute:
                      [09:00:00, 09:01:00)
                           [09:00:30, 09:01:30)
                                [09:01:00, 09:02:00)
```

Windows are half-open — a row at exactly 09:01:00 is in `[09:01, 09:02)`, not `[09:00, 09:01)` —
and aligned to the epoch in UTC, so every minute window starts on a whole minute.

## Tumbling, as a table function

The window-table-valued-function form adds two columns, `window_start` and `window_end`, to every
row; group by them:

```sql
SELECT symbol, window_start, window_end,
       COUNT(*)              AS fills,
       SUM(qty)              AS shares,
       SUM(qty * price_cents) AS notional_cents
FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY symbol, window_start, window_end
```

With four fills of `ACME` between 09:30:00 and 09:30:59 (100 at 1025, 200 at 1030, 50 at 1024,
150 at 1031 cents), the row published when that minute closes is:

```text
symbol  window_start          window_end            fills  shares  notional_cents
ACME    2026-09-19 09:30:00   2026-09-19 09:31:00   4      500     514350
```

(100 × 1025 + 200 × 1030 + 50 × 1024 + 150 × 1031 = 102500 + 206000 + 51200 + 154650.)

**Both `window_start` and `window_end` must be in the `GROUP BY`.** Leave one out and the aggregate
spans every window at once — the unbounded case wearing a window's clothes — and it is refused:

<!-- sql: refused PRV-2050 -->
```sql
SELECT symbol, window_end, SUM(qty) AS shares
FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY symbol, window_end
```

## Hopping

```sql
SELECT site, window_start, window_end,
       COUNT(*)          AS readings,
       MAX(battery_pct)  AS best_battery,
       MIN(battery_pct)  AS worst_battery
FROM TABLE(HOP(TABLE readings, DESCRIPTOR(event_time), INTERVAL '30' SECOND, INTERVAL '5' MINUTE))
GROUP BY site, window_start, window_end
```

Five minutes of history, recomputed every thirty seconds: **each row belongs to ten windows**, so a
hopping window costs roughly size ÷ slide times the state and work of a tumbling one. Pick the
coarsest slide that answers the question. There is a ceiling: a hop where a row would land in more
than `pravaha.lane.max-windows-per-row` windows (100,000 by default), or a window would combine
more slices, is refused at registration, [PRV-3026](/help/codes/PRV-3026) — one row of
`HOP(INTERVAL '0.001' SECOND, INTERVAL '1' DAY)` used to hold its lane for good (FINEHOP-1).

## The group-window form

The older form groups by a window function and reads its bounds with `TUMBLE_END` / `HOP_END`. The
case studies use it, and it plans the same way:

```sql
SELECT TUMBLE_END(event_time, INTERVAL '1' HOUR) AS hour_end,
       user_id,
       COUNT(*)    AS payments,
       SUM(amount) AS spend
FROM txn
GROUP BY TUMBLE(event_time, INTERVAL '1' HOUR), user_id
```

```sql
SELECT HOP_END(event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE) AS window_end,
       account,
       COUNT(*) AS orders
FROM trades
GROUP BY HOP(event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE), account
```

Do not mix the two: `TUMBLE_END(...)` over a `TABLE(TUMBLE(...))` input is refused (PRV-2002, "must
have matching call to group function"); in that form, select `window_end` itself.

!!! warning "hour is a reserved word"
    `AS hour` fails to parse (PRV-2001) — so do `year`, `value` and the other SQL keywords. In the
    group-window form, name the column `hour_end`. In the `TABLE(TUMBLE(...))` form, select
    `window_end` under its own name: renaming it (`window_end AS hour_end`) is refused with PRV-2050,
    whose message says why — the window is found by its columns' names — and to keep them.

## When a window emits

**When the watermark passes its end.** The watermark is the engine's statement that "nothing
earlier than this is still coming", derived from the newest event time seen minus the stream's
`out-of-orderness`. Until it passes `window_end`, the window is open and nothing is published for
it — publishing an answer the engine might have to retract would be worse than publishing nothing.

For `[09:30, 09:31)` over `trades` (5 s of out-of-orderness), the window is published once a row
stamped 09:31:05 or later has arrived. A subscriber receives it as one commit — never a partly closed
window. See [event time and watermarks](/help/topics/event-time-watermarks).

This is why **a windowed query over a stream with no `event-time` is refused** rather than
registered. It would not be slow; it would be waiting for a clock that never ticks, reporting
`RUNNING` and ingesting every row for ever, so the engine says so when you register it
(`PRV-2002`) and names the key to set.

A bounded input is different: when a file read with `follow: false` ends, every open window is
closed and published, because nothing more can arrive. So the same SQL over a bounded read is
accepted with no event-time declaration — its windows are fired by the end of the scan.

## The descriptor must name the stream's event time, and the stream must have one

```text
-- refused: 'placed_at' is a timestamp, but not the one the watermark tracks
TUMBLE(TABLE orders, DESCRIPTOR(placed_at), INTERVAL '10' SECOND)
```

Windows are closed by the watermark, and the watermark advances only on the declared event-time
column. A grid keyed to another column would be closed by a clock that knows nothing about it, and
the answer would be **wrong rather than late** (TIME-2).

The same check refuses a stream that declares **no** event-time column at all (TIME-6), because a
grid over it could never be closed by anything:

```text
PRV-2002  TUMBLE is given DESCRIPTOR(placed_at), but 'orders' declares no event-time column -- so
          no watermark advances over it and no window this query opens can ever close. It would
          register, report RUNNING, ingest every row and emit nothing, for ever.
            Declare the column: pravaha.streams.orders.event-time: placed_at, or 'eventTime' on
            POST /api/v1/streams. The column must be a TIMESTAMP.
```

## Keying a windowed view

A windowed aggregate's view key is its group columns **plus the window**:

```sql
CREATE CONTINUOUS QUERY symbol_minute
    KEYED BY (symbol, window_end)
    RETAIN FOR P1D
AS
SELECT symbol, window_start, window_end,
       COUNT(*) AS fills, SUM(qty) AS shares
FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY symbol, window_start, window_end;
```

Key it by `symbol` alone and each minute replaces the last — the view silently keeps only the
latest window per symbol. `RETAIN FOR` then decides how many closed windows the view keeps.

<!-- sql: read -->
```sql
SELECT window_end, fills, shares FROM symbol_minute WHERE symbol = ?
```

## Late rows

A row arriving for a window that has already been published is **late**. With the default allowed
lateness of zero it is counted as late and dropped. When a stream declares allowed lateness
(`pravaha.streams.<name>.allowed-lateness` on a server, `allowedLateness` over HTTP or embedded), a
late row within it reopens the window as a correction: the old result at `−1`, the new one at `+1`. See
[late data and corrections](/help/topics/event-time-watermarks#late-data).

## Pitfalls

!!! warning "RUNNING and empty"
    No newer rows to move the watermark past a window's end, or an `out-of-orderness` larger than
    the span of the data on hand. It is no longer a missing `event-time`: a windowed query over a
    stream that declares none is refused with `PRV-2002` when you register it.

!!! warning "A window closed later than expected"
    `out-of-orderness` is larger than the data needs, or — in a join — a slow input is holding the
    joint watermark back. A query is only as current as its laggiest input.

!!! warning "A month window"
    `INTERVAL '1' MONTH` is refused: a month is not a fixed length of event time.

!!! tip "SUM, AVG, MIN or MAX over a float column"
    Refused (PRV-2020); only `COUNT` of a float plans. Keep money in integer minor units
    (`price_cents`), or cast: `SUM(CAST(temperature AS BIGINT))`.

## Worked examples {#worked-examples}

A window turns an unbounded stream into a sequence of finite groups, and that is what makes an
aggregate over a stream possible at all: a window **closes**, so its state is released. This part
works through both window shapes with real rows, shows exactly when each window is published, and
lists the details that trip people up. The sections above have the concept;
[event time and watermarks](/help/topics/event-time-watermarks) the clock that closes them.

### The two shapes

| | Tumbling | Hopping |
|---|---|---|
| Function | `TUMBLE(TABLE s, DESCRIPTOR(t), size)` | `HOP(TABLE s, DESCRIPTOR(t), slide, size)` |
| Windows | fixed width, non-overlapping | fixed width, overlapping, one starting on every multiple of `slide` (as SQL's `HOP`; a size that is not a multiple of the slide included, since HOPALIGN-1) |
| A row belongs to | exactly one window | `size / slide` windows |
| Typical use | "per minute", "per hour" totals | "the last five minutes, updated every ten seconds" |

Windows are **aligned to the epoch** in event time: a one-hour window always runs from the top of an
hour to the next, whatever time the first row carried. The descriptor must name the stream's
**declared event-time column** — a window keyed to another column would be closed by a clock that
knows nothing about it, so it is refused (PRV-2002 when the column is not a timestamp).

Both expose `window_start` and `window_end` as columns. A windowed `GROUP BY` must include **both**,
or it is the unbounded case again (PRV-2050).

### Tumbling, row by row

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

#### A row that arrives too late

Now a row carrying 09:00:04 arrives after row 6. The watermark (09:00:21) is already past its
window's end. The stream declares no **allowed lateness** — zero, the default — so the row is
dropped rather than applied; the published 1520 stands. A stream that declares one
(`pravaha.streams.<name>.allowed-lateness`, or `allowedLateness` on `POST /api/v1/streams`) takes the
correction path instead: a `-1` for the old row and a `+1` for the new one.
[Late data and corrections](/help/topics/event-time-watermarks#late-data) walks through it.

#### Why a window "never closes"

If the stream has **no declared event time**, no watermark ever advances and no window could ever
close — so the query is **refused when you register it** (`PRV-2002`), rather than left reporting
`RUNNING` and ingesting every row for ever. Declare `event-time` on the stream and register it
again. The same symptom, from a query that did register, appears when a stream simply stops — the last window
closes only when a later row, or the idle-partition timer (`pravaha.watermark.idle-after`), moves the
watermark on.

### Hopping, row by row

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

### The `GROUP BY TUMBLE` form

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

### Sizes and units

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

### Windows with filters, joins and lookups

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

### Pitfalls

!!! warning "Pitfall: a window that could never emit"
    No `event-time` on the stream means no watermark means no window could ever close. That query
    used to register, report `RUNNING`, count rows in and serve an empty view for ever; it is now
    refused with `PRV-2002` at registration, naming the key to declare. A **bounded** read is still
    accepted, because its windows are closed by the end of the scan.

!!! warning "Pitfall: an out-of-orderness that is too generous"
    A 10-minute out-of-orderness holds every window open ten minutes past its end. Windows close
    correctly but lag real time badly. Set it to what the source actually needs.

!!! warning "Pitfall: renaming `window_end`"
    Selecting `window_end AS hour_end` in a windowed aggregate is refused with PRV-2050 today even
    though the `GROUP BY` names both columns. Keep the name and rename it in the reading query.

!!! note "Name your aggregates"
    `COUNT(*)` with no alias is `EXPR$2` in the view. `KEYED BY` and every reader want a real name.

## Where next

- [Event time and watermarks](/help/topics/event-time-watermarks) — the clock itself, and what happens to a row behind it
- [Aggregation](/help/topics/aggregation) — every aggregate function and its rules
- [Joins](/help/topics/joins) — windowed joins, and enriching a window from a table
- The long form: [Streams, queries and SQL §5](/help/continuous-queries#5-windows-worked)
- How it is built: [Architecture: windows and watermarks](/help/architecture-runtime#windows-and-watermarks)
