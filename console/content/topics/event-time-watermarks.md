---
title: Event time and watermarks
slug: event-time-watermarks
category: concepts
order: 60
icon: clock-history
summary: "Windows, join bounds and retention run on the time in the data; the watermark — nothing earlier is coming — closes windows and releases state. How it is computed, the settings that govern it, and how to spot a stuck one."
audience: Everyone
keywords: [watermark, watermarks, event time, event-time, out-of-orderness, idle-after, tick, lateness, lag, pravaha_query_watermark_lag_seconds, pravaha_query_watermark_partitions_idle, idle exclusions, regressions, stuck watermark, empty view, clock]
guide: operations#running-against-a-source-that-does-not-end
related: [windows, late-data, streams, metrics-alerts, joins]
---

Pravaha measures time by the **timestamp in the data**, never the wall clock. Every window, every
join's match bound, every view's retention is expressed in event time. That is what makes an answer
reproducible: load the same rows in any order, at any speed, replay them tomorrow, and you get the
same result — which a system consulting the wall clock cannot promise, and which anything an auditor
will look at requires.

The **watermark** is how event time moves forward. A watermark at *T* is the engine's claim that
**no row earlier than *T* is still coming**, so everything ending at or before *T* is complete and
can be published. Watermarks close windows, release join state, and evict view rows past their
retention. Almost everything time-shaped in the engine is downstream of this one idea.

> A window does not close because time passed. It closes because the data said so.

## How the watermark is computed

For each source partition, the watermark trails the **newest event time seen** by the stream's
**out-of-orderness**:

```text
watermark(partition) = max(event_time seen) − out-of-orderness
watermark(query)     = min over its partitions that are not idle
```

It advances on a timer (`pravaha.watermark.tick`, 1 s), not only when rows arrive — which is also
what makes a quiet partition detectable at all: a watermark derived only from arriving rows cannot
notice that rows have stopped.

A worked timeline for `txn`, out-of-orderness 10 s, one-minute tumbling windows:

| Row arrives with event time | Newest seen | Watermark | Windows published |
|---|---|---|---|
| 09:00:05 | 09:00:05 | 08:59:55 | — |
| 09:00:41 | 09:00:41 | 09:00:31 | — |
| 09:00:38 (out of order, fine) | 09:00:41 | 09:00:31 | — |
| 09:01:12 | 09:01:12 | 09:01:02 | `[09:00, 09:01)` |
| 09:00:50 (behind the watermark) | 09:01:12 | 09:01:02 | — this row is **late** |
| 09:02:15 | 09:02:15 | 09:02:05 | `[09:01, 09:02)` |

The row at 09:00:38 arrived after 09:00:41 but ahead of the watermark, so it was simply counted —
that is what out-of-orderness buys. The row at 09:00:50 arrived after its window had been published:
it is late, and what happens to it is the subject of [late data](/help/topics/late-data).

## The three settings

Every one of them decides whether memory is bounded at all — not one is a matter of taste.

| Setting | Default | What it decides |
|---|---|---|
| `pravaha.streams.<name>.event-time` | none | which `TIMESTAMP` column is the stream's time. **Without it no watermark advances, and a windowed query over the stream is refused** (`PRV-2002`) |
| `pravaha.streams.<name>.out-of-orderness` | `10s` | how far the watermark trails the newest row: how long a window waits for stragglers |
| `pravaha.watermark.idle-after` | `30s` | how long a partition may produce nothing before it stops holding the watermark back (1 s to 10 min, refused outside that, never clamped) |
| `pravaha.watermark.tick` | `1s` | how often event time advances and idleness is checked; **must** be finer than `idle-after`, and at least `1ms`. Both refused at startup, never clamped |

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  watermark:
    idle-after: 30s
    tick: 1s
```

!!! note "A unitless number is seconds"
    `out-of-orderness: 60` is a minute and `allowed-lateness: 30` is thirty seconds. They used to
    bind as *milliseconds*, so `60` gave the same eleven windows and the same totals as the
    ten-second default and the mistake could not be seen in the answer. `60s`, `PT1M` and `60ms`
    all mean what they say.

!!! note "The node says what is in force"
    One line per stream at startup — `stream txn: event-time=event_time, out-of-orderness=PT10S,
    allowed-lateness=PT0S`, and `event-time=none -- no window over this stream can ever close` for
    a stream with none. Read it when a windowed query is `RUNNING` with a climbing `ROWS IN` and an
    empty view: it is the one place the effective lateness is stated.

A source must also **stamp** each row with that column: on the `filesystem` and `aerospike`
sources that is the `event.time` option. Without it every row carries the time it was *read*, the
watermark runs at wall-clock, and every row lands behind it — an empty view under a query reporting
`RUNNING`.

!!! note "pravaha.watermark.out-of-orderness is the node's default"
    A stream that declares an event time and no lateness of its own takes it;
    `pravaha.streams.<name>.out-of-orderness` overrides it. **It was read by nothing until
    2026-09-20** (DOCX-6), and its default is the same 10 seconds a stream already took.

### Choosing out-of-orderness

| Too small | Too large |
|---|---|
| on-time-but-disordered rows fall behind the watermark and are treated as late — **silently wrong** | windows wait longer before publishing and hold more state — **visibly slow** |

The second failure is visible and the first is not, which is why the default errs generous. Measure
how disordered the source really is — the spread between a row's event time and when it arrives —
and set it a little above the worst case you are willing to wait for.

### Choosing idle-after

A query's watermark is the minimum across its partitions. A partition producing **nothing** would
keep its last watermark for ever and pin the minimum — every window in the query stops closing, and
nothing errors. Idle exclusion drops a silent partition from the minimum and lets it rejoin the
moment it speaks.

| Too long | Too short |
|---|---|
| A desk trading 09:00–17:00 goes quiet at 17:00 and every window in the query freezes until 09:00 — sixteen hours of growing state and no output | A source that batches every 60 s is excluded while it still has rows coming; the watermark jumps, and its on-time rows arrive late |

The rule: comfortably longer than the longest normal gap on your quietest partition, comfortably
shorter than how long you can afford windows not to close.

## Joins and several streams

A query over two streams takes the **minimum of their watermarks**. It is only as current as its
laggiest input — correct, and surprising:

```sql
SELECT o.order_id, o.region, s.carrier, s.event_time AS shipped_at
FROM orders AS o
JOIN shipments AS s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' HOUR
```

The time bound decides which pairs match **and** how long each side's rows are kept: an order is
released once the joint watermark passes its time plus an hour. A lookup join
(`FOR SYSTEM_TIME AS OF`) never holds the watermark back — a lookup is asked, not consumed.

## Watching it

`pravaha_query_watermark_lag_seconds{query="…"}` is how far a query's event time is behind now, and
three gauges beside it say **why** (TIME-8): `pravaha_query_watermark_partitions` and
`..._partitions_idle` (how many inputs there are and how many are excluded right now),
`..._idle_exclusions_total` (how often one has been) and `..._regressions_total` (how often one
reported a watermark below the lane's, which is a source-side fault). Lag alone cannot tell those
three apart, and they need different responses.
Steady lag is the out-of-orderness plus the source's own delay. **Lag that climbs without bound means
event time is stuck**, and because every bound downstream is measured in event time, a stuck
watermark shows up as **growing memory**, not as a stopped query. The console's Operations screen
raises a finding past `ui.lag_warn_seconds` (300 s). A query that has not yet seen a row reports
`NaN`, not zero — "never seen a row" is not "perfectly up to date".

!!! note "Replaying history shows large lag, correctly"
    Lag is measured against the wall clock. A query replaying last month's file reports a month of
    lag while it catches up; that is not a fault.

## Without event time, state is unbounded

This is the part that makes watermarks a correctness concern rather than a latency one:

- windows close only when a bounded input ends — correct over a file, never over a stream;
- a join evicts at `watermark − match window`, which never moves;
- a view forgets past the committed frontier, which never moves.

Every bound in the engine is armed by event time.

## Pitfalls

!!! warning "RUNNING, rows arriving, view empty"
    The source is not stamping rows with the event-time column, or no row has yet arrived far
    enough past a window's end. Append a row later in event time and watch the window publish. A
    stream with no `event-time` at all is no longer one of the answers here: a windowed query over
    one is refused with `PRV-2002` when it is registered, so it never reaches `RUNNING`.

!!! warning "Everything stopped closing at 17:00"
    One partition went quiet and `idle-after` is longer than the gap. Shorten it, or check that the
    partition is genuinely idle and not broken.

!!! warning "A row stamped far in the future"
    One row with an event time years ahead drags the watermark with it, closing every window at
    once — and may be refused with PRV-3022 ("window span implausible") when a single advance would
    fire millions of windows. Check the event-time column of the earliest offending row: an unset
    field read as the epoch, a value in the wrong unit, a parse that produced zero.

## Where next

- [Windows](/help/topics/windows) — what the watermark closes
- [Late data and corrections](/help/topics/late-data) — what happens behind it
- [Metrics and alerts](/help/topics/metrics-alerts) — alerting on lag
- The long form: [Concepts §2–§3](/help/concepts#3-watermarks-nothing-earlier-is-coming) and [Operations](/help/operations#running-against-a-source-that-does-not-end)
