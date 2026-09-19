---
title: Windows
slug: windows
category: concepts
order: 50
icon: bounding-box
summary: "A window turns an unbounded stream into a sequence of finite groups, which is what lets an aggregate keep bounded state. Tumbling and hopping windows, when each one emits, what it costs, and the two ways to write one."
audience: Analysts
keywords: [window, tumble, tumbling, hop, hopping, sliding, session, window_start, window_end, TUMBLE_END, HOP_END, descriptor, bounded state, PRV-2050]
guide: continuous-queries#5-windows-worked
related: [windows-worked, event-time-watermarks, late-data, aggregation, views-and-keys]
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
coarsest slide that answers the question.

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

This is why **a windowed query over a stream with no `event-time` emits nothing, for ever**. It is
not slow; it is waiting for a clock that will never tick.

A bounded input is different: when a file read with `follow: false` ends, every open window is
closed and published, because nothing more can arrive.

## The descriptor must name the stream's event time

```text
-- refused: 'placed_at' is a timestamp, but not the one the watermark tracks
TUMBLE(TABLE orders, DESCRIPTOR(placed_at), INTERVAL '10' SECOND)
```

Windows are closed by the watermark, and the watermark advances only on the declared event-time
column. A grid keyed to another column would be closed by a clock that knows nothing about it, and
the answer would be **wrong rather than late** (TIME-2).

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
[late data and corrections](/help/topics/late-data).

## Pitfalls

!!! warning "RUNNING and empty"
    No `event-time` on the stream, or no newer rows to move the watermark past a window's end.

!!! warning "A window closed later than expected"
    `out-of-orderness` is larger than the data needs, or — in a join — a slow input is holding the
    joint watermark back. A query is only as current as its laggiest input.

!!! warning "A month window"
    `INTERVAL '1' MONTH` is refused: a month is not a fixed length of event time.

!!! tip "SUM, AVG, MIN or MAX over a float column"
    Refused (PRV-2020); only `COUNT` of a float plans. Keep money in integer minor units
    (`price_cents`), or cast: `SUM(CAST(temperature AS BIGINT))`.

## Where next

- [Windows, worked](/help/topics/windows-worked) — more complete examples with their output
- [Event time and watermarks](/help/topics/event-time-watermarks)
- [Aggregation](/help/topics/aggregation) — every aggregate function and its limits
- The long form: [Streams, queries and SQL §5](/help/continuous-queries#5-windows-worked)
