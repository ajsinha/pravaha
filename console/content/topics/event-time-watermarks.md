---
title: Event time and watermarks
slug: event-time-watermarks
category: concepts
order: 60
icon: clock-history
summary: "Windows, join bounds and retention run on the time in the data; the watermark closes windows and releases state. How it is computed and tuned, how to spot a stuck one — and what happens to a row that arrives behind it."
audience: Everyone
keywords: [watermark, watermarks, event time, event-time, out-of-orderness, idle-after, tick, lateness, lag, pravaha_query_watermark_lag_seconds, pravaha_query_watermark_partitions_idle, idle exclusions, regressions, stuck watermark, empty view, clock, late, late data, allowed lateness, allowed-lateness, allowedLateness, correction, retraction, reopen, dropped, count went down]
guide: operations#running-against-a-source-that-does-not-end
related: [windows, streams, zset-weights, subscriptions, observability, joins]
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
it is late, and what happens to it is the subject of [late data](/help/topics/event-time-watermarks#late-data).

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

When **every** partition is idle the watermark does not jump ahead to release every open window; it
stands at the lowest watermark among the partitions that delivered rows -- what they themselves
said, never past it. That is also what a burst into one partition of several gets: the Docker seed's
twelve orders all land in one of the topic's three partitions, the other two never produce, and all
three fall idle together `idle-after` later; the windows the burst's own watermark has passed then
close (SEEDWINDOW-1 -- before 2.0.1 the watermark stayed unset and `spend_per_minute` stayed empty).

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

## Late data and corrections {#late-data}

A row is **late** when it arrives after the watermark has passed its event time — after the engine
has already declared "nothing earlier is coming" and published the windows that row belongs to.
Every streaming system has to decide what to do with one. Pravaha's answer has two parts, controlled
by two different properties of the stream, and they are easy to conflate.

| Property | Decides | Default | Set by |
|---|---|---|---|
| **out-of-orderness** | how long the engine *waits* before calling a window complete | 10 s | `pravaha.streams.<name>.out-of-orderness`, or `StreamSchema.outOfOrderness` |
| **allowed lateness** | whether a row arriving *after that* still corrects the published answer | **zero** | `pravaha.streams.<name>.allowed-lateness`, `allowedLateness` on `POST /api/v1/streams`, or `StreamSchema.allowedLateness` |

Out-of-orderness is about **waiting**; allowed lateness is about **revising**. A row inside the
out-of-orderness is not late at all — it is counted before the window is published. A row behind
the watermark but within allowed lateness produces a **correction**. A row beyond both is dropped.

```text
          ◄── counted normally ──►◄── corrected (if allowed) ──►◄── dropped ──►
event time ──────────────────────┬─────────────────────────────┬──────────────
                              watermark              watermark − allowed lateness
```

### By default: late rows are dropped

A stream that declares no allowed lateness has zero, and a windowed query over it treats a row for a
window that has already been published as late and **drops** it; the published answer stands. That
is the default on purpose: a window that can be corrected is a query that revises its answer, and a
revising query needs a sink that can take a retraction.

Worked with `txn` at 10 s of out-of-orderness, no allowed lateness, and this query:

```sql
CREATE CONTINUOUS QUERY minute_takings
    KEYED BY (merchant, window_end)
AS
SELECT merchant, window_start, window_end,
       COUNT(*) AS payments, SUM(amount) AS takings
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY merchant, window_start, window_end;
```

| Row | Event time | Watermark after | Effect |
|---|---|---|---|
| m-1, 500 | 09:00:10 | 09:00:00 | counted in `[09:00, 09:01)` |
| m-1, 700 | 09:00:40 | 09:00:30 | counted |
| m-1, 200 | 09:01:15 | 09:01:05 | `[09:00, 09:01)` published: 2 payments, 1200 |
| m-1, 900 | 09:00:50 | 09:01:05 | **late**: dropped; the published 1200 stands |

<!-- sql: read -->
```sql
SELECT window_end, payments, takings FROM minute_takings WHERE merchant = 'm-1'
```

```text
window_end            payments  takings
2026-09-19 09:01:00   2         1200
```

If late rows matter, there are two remedies. Raise the stream's out-of-orderness until they are not
late: the window then waits longer before publishing and the stragglers are counted the first time,
at the cost of latency. Or declare allowed lateness, below: the window publishes on time and is
**corrected** when a straggler arrives, at the cost of state and of a revising query.

### On a server: declare allowed lateness on the stream

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
      allowed-lateness: 5m
```

or `"allowedLateness": "PT5M"` beside `eventTime` in `POST /api/v1/streams`. It needs `event-time`
(refused without one) and is refused negative — both with `PRV-2002`, naming the stream and both
spellings of the key. **A unitless number is seconds**: `allowed-lateness: 30` is thirty seconds,
where it used to bind as thirty *milliseconds* and be indistinguishable from the zero default.
`GET /api/v1/streams` reports it. Every windowed query
planned over the stream afterwards gets it. With it, the fourth row above — 09:00:50, behind the
watermark but within five minutes — **reopens** `[09:00, 09:01)`: the published `2, 1200` is
retracted and `3, 2100` published, in one commit, and the read above returns `3  2100`.



### Embedded: the same, in code

An embedder declares the stream with allowed lateness, and a row within it **reopens** the window:
the engine retracts the result it published (`−1`) and publishes the corrected one (`+1`), in one
commit.

```java
StreamSchema txn = StreamSchema.builder("txn")
        .field("merchant", Types.string())
        .field("amount", Types.int64())
        .field("event_time", Types.timestamp())
        .eventTime("event_time")
        .outOfOrderness(Duration.ofSeconds(10))
        .allowedLateness(Duration.ofMinutes(5))
        .build();

try (PravahaEngine engine = PravahaEngine.createDefault()) {
    engine.declareStream(txn);
    engine.start();
    engine.query("CREATE CONTINUOUS QUERY minute_takings KEYED BY (merchant, window_end) AS "
            + "SELECT merchant, window_start, window_end, COUNT(*) AS payments, SUM(amount) AS takings "
            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)) "
            + "GROUP BY merchant, window_start, window_end");
    engine.subscribe("minute_takings", changes -> changes.forEach(c ->
            System.out.println((c.isRetraction() ? "-1 " : "+1 ")
                    + c.get("merchant") + " " + c.get("payments") + " " + c.get("takings"))));

    engine.push("txn", new Object[] {"m-1", 500L, Instant.parse("2026-09-19T09:00:10Z")},
                       new Object[] {"m-1", 700L, Instant.parse("2026-09-19T09:00:40Z")});
    engine.advanceEventTime("txn", Instant.parse("2026-09-19T09:01:15Z"));    // publishes [09:00, 09:01)
    engine.push("txn", new Object[] {"m-1", 900L, Instant.parse("2026-09-19T09:00:50Z")});   // late, within 5 min
    engine.advanceEventTime("txn", Instant.parse("2026-09-19T09:01:20Z"));
}
```

The subscriber sees the first answer, then the correction as a pair:

```text
+1 m-1 2 1200
-1 m-1 2 1200
+1 m-1 3 2100
```

One `+1` when the window is first published, then a `−1` and a `+1` in the same commit when the
late row corrects it. A reader of the view sees only the current state, because
the retraction and the insert are applied before the commit is visible: 1200 before, 2100 after,
never nothing.

Allowed lateness has a price: the window's state is kept for that long after it is published, so it
can be reopened. Five minutes of allowed lateness on one-minute windows keeps about six windows of
state per key instead of one.

### Consuming corrections

A consumer that keeps **current values** overwrites by key and ignores the negatives — the `+1`
that follows in the same commit replaces the row. A consumer that keeps its **own aggregate** must
apply the weight:

```python
takings_today = 0
for batch in client.subscribe("minute_takings"):
    for row in batch:
        takings_today += row.weight * row["takings"]
```

Counting rows, or summing without the weight, double-counts the first correction and stays wrong.

### Sinks and corrections

A query that can revise its answer — a window with allowed lateness, any unwindowed aggregate, a
join that can withdraw a match — needs a sink that accepts updates. Pointed at an append-only sink
such as a file, it is refused at registration with PRV-2041: the alternative is a sink full of rows
that are each correct and a total that is wrong for ever. A tumbling window without lateness never
revises, and can go anywhere. See [sinks](/help/topics/sinks-overview).

### Pitfalls

!!! warning "A count went down"
    A correction, working as designed: a late row (or a retraction from the source) withdrew a
    result and replaced it.

!!! warning "None of this exists without `event-time`"
    Lateness is measured against the watermark, and the watermark advances on the stream's declared
    event-time column. A stream with none has no watermark, so no window over it could ever
    publish and nothing could ever be late for one — which is why `allowed-lateness` without
    `event-time` is refused (PRV-2002), and why a windowed query over such a stream is refused when
    it is registered rather than left ingesting for ever (TIME-6). See
    [event time and watermarks](/help/topics/event-time-watermarks).

!!! warning "Late rows vanish without a trace by default"
    With allowed lateness zero, a late row is dropped. If the numbers are consistently low, compare
    the source's real disorder with the stream's `out-of-orderness`, or declare `allowed-lateness`.

!!! warning "Allowed lateness changes which sinks a query may use"
    A windowed query over a stream with allowed lateness revises its answer, so registering it
    `WRITING TO` an append-only sink (a `filesystem` sink, `jdbc-sink` in `mode: append`) is refused
    with PRV-2041. Declare the lateness before registering, and pick the sink to match.

!!! warning "Idle exclusion can make on-time rows late"
    If `pravaha.watermark.idle-after` is shorter than a partition's normal gap, that partition is
    excluded, the watermark jumps past it, and its next rows arrive behind the watermark. See
    [event time and watermarks](/help/topics/event-time-watermarks).

## Where next

- [Windows](/help/topics/windows) — what the watermark closes
- [Z-set weights](/help/topics/zset-weights) — the arithmetic of `−1` and `+1`
- [Streams](/help/topics/streams) — `allowed-lateness` beside the other stream keys
- [Observability](/help/topics/observability) — alerting on watermark lag
- [The embedded engine](/help/topics/embedded-engine) — declaring lateness in code
- The long form: [Concepts §2–§3](/help/concepts#3-watermarks-nothing-earlier-is-coming) and [Operations](/help/operations#running-against-a-source-that-does-not-end)
