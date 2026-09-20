---
title: Late data and corrections
slug: late-data
category: concepts
order: 70
icon: arrow-counterclockwise
summary: "What happens to a row that arrives after its window was published: out-of-orderness versus allowed lateness, dropped by default, a −1/+1 correction once a stream declares allowed lateness, and how a consumer should apply one."
audience: Developers
keywords: [late, late data, lateness, allowed lateness, allowed-lateness, allowedLateness, correction, retraction, reopen, out-of-orderness, dropped, count went down]
guide: concepts#3-watermarks-nothing-earlier-is-coming
related: [event-time-watermarks, zset-weights, windows, subscriptions, embedded-engine]
---

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

## By default: late rows are dropped

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

## On a server: declare allowed lateness on the stream

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



## Embedded: the same, in code

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

## Consuming corrections

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

## Sinks and corrections

A query that can revise its answer — a window with allowed lateness, any unwindowed aggregate, a
join that can withdraw a match — needs a sink that accepts updates. Pointed at an append-only sink
such as a file, it is refused at registration with PRV-2041: the alternative is a sink full of rows
that are each correct and a total that is wrong for ever. A tumbling window without lateness never
revises, and can go anywhere. See [sinks](/help/topics/sinks-overview).

## Pitfalls

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

- [Z-set weights](/help/topics/zset-weights) — the arithmetic of `−1` and `+1`
- [Event time and watermarks](/help/topics/event-time-watermarks)
- [Streams](/help/topics/streams) — `allowed-lateness` beside the other stream keys
- [The embedded engine](/help/topics/embedded-engine) — declaring it in code
- The long form: [Concepts §3–§4](/help/concepts#3-watermarks-nothing-earlier-is-coming)
