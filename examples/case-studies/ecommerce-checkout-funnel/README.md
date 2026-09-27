# Checkout funnel — an e-commerce case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** none — a CSV file the node follows · **Time to first result:** about ten minutes ·
**Shows:** tumbling windows over several keys, `COUNT(DISTINCT)`, `HAVING`, conversion without `CASE`

## The problem

It is Black Friday. At 10:06 the payment provider's integration with the mobile app starts failing,
and every app shopper who reaches the payment page is turned away. The web shop is fine, so total
orders dip a little rather than fall off a cliff, and the dashboard — refreshed from the warehouse
every hour — shows nothing until eleven.

The questions the trading desk of a shop asks all day are funnel questions: how many baskets became
checkouts, how many checkouts became payments, how many payments became orders — and on which
device the funnel is leaking *now*. This study keeps that funnel current in five-minute windows,
and flags any window in which one device's payments failed three times or more.

## What you will build

```
  data/checkout_events.csv          Pravaha                                 readers
 ┌─────────────────────────┐     ┌────────────────────────────────┐   ┌─────────────────────┐
 │ CART, CHECKOUT, PAYMENT,│ ──► │ checkout_funnel                │ ─►│ funnel dashboard    │
 │ ORDER, PAYMENT_FAILED   │     │  (5 min × step × device)       │   │ conversion, by SQL  │
 │ one line per step       │     │ payment_failure_spikes         │ ─►│ on-call page        │
 └─────────────────────────┘     │  (5 min × device, HAVING ≥ 3)  │   └─────────────────────┘
                                 └────────────────────────────────┘
```

## The data model

One stream, `checkout_event`:

| Column | Type | Meaning |
|---|---|---|
| `event_id` | STRING | Unique per event |
| `session_id` | STRING | The shopping session — one shopper, one basket |
| `step` | STRING | `CART`, `CHECKOUT`, `PAYMENT`, `ORDER` or `PAYMENT_FAILED` |
| `device` | STRING | `web` or `app` |
| `basket_minor` | INT64 | Basket value in **minor units**: `1999` is 19.99 |
| `event_time` | TIMESTAMP | When the shopper took the step — **the stream's event time** |

Checked by the build from [`schema/streams.properties`](schema/streams.properties); the node reads
[`conf/application.yaml`](conf/application.yaml): `event-time: event_time`, ten seconds of
out-of-orderness.

## Step 1 — generate the morning

From this directory:

```bash
python3 data/generate_events.py > data/checkout_events.csv
```

Sixty sessions start ten seconds apart from 10:00:00. Every one adds to a basket; a quarter abandon
it; a fifth of the rest abandon at checkout; the others pay, twenty seconds per step. One session in
three is on the app. Between 10:06 and 10:09 every app payment fails. The last line, a new basket at
10:15:20, moves event time past the end of every window the sixty sessions touch. The output is the
same on every run.

## Step 2 — start the node

From this directory:

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

```text
stream checkout_event: event-time=event_time, out-of-orderness=PT10S, allowed-lateness=PT0S
```

## Step 3 — the two continuous queries

### The funnel

[`sql/01-continuous-checkout-funnel.sql`](sql/01-continuous-checkout-funnel.sql):

```sql
SELECT STREAM
  TUMBLE_END(event_time, INTERVAL '5' MINUTE) AS window_end,
  step,
  device,
  COUNT(*)                   AS events,
  COUNT(DISTINCT session_id) AS sessions,
  SUM(basket_minor)          AS basket_minor
FROM checkout_event
GROUP BY TUMBLE(event_time, INTERVAL '5' MINUTE), step, device
```

One row per five minutes, step and device, keyed by all three — key columns `[0, 1, 2]`.
`COUNT(DISTINCT session_id)` rather than `COUNT(*)`: a shopper who presses "pay" twice is one
session that reached payment, not two.

### Payment-failure spikes

[`sql/02-continuous-payment-failure-spikes.sql`](sql/02-continuous-payment-failure-spikes.sql):

```sql
SELECT STREAM
  TUMBLE_END(event_time, INTERVAL '5' MINUTE) AS window_end,
  device,
  COUNT(*)          AS failures,
  SUM(basket_minor) AS basket_at_risk_minor
FROM checkout_event
WHERE step = 'PAYMENT_FAILED'
GROUP BY TUMBLE(event_time, INTERVAL '5' MINUTE), device
HAVING COUNT(*) >= 3
```

`WHERE` narrows the rows before they are grouped; `HAVING` narrows the groups after. The view holds
only windows worth paging somebody about — so a subscriber to it is an alerting rule, with nothing
to filter on its side.

### Register them

```bash
python3 python/run.py --url grpc://localhost:9090
```

[`python/run.py`](python/run.py) registers both — keys `[0, 1, 2]` and `[0, 1]` — waits for the
three windows to close, and reads. Java: [`java/CheckoutFunnelExample.java`](java/CheckoutFunnelExample.java).
From a shell:
`pravaha register --name checkout_funnel --sql-file sql/01-continuous-checkout-funnel.sql --keys 0,1,2`.

```text
registered checkout_funnel RUNNING f18823923695
registered payment_failure_spikes RUNNING dc2710404090
```

## Step 4 — ask questions

Everything below is what `python/run.py` printed on a real node.

### The whole funnel

[`sql/03-read-funnel-totals.sql`](sql/03-read-funnel-totals.sql):

```sql
SELECT step, SUM(sessions) AS sessions, SUM(basket_minor) AS basket_minor
FROM checkout_funnel
GROUP BY step
```

```text
-- funnel totals
   {'step': 'CART', 'sessions': 60, 'basket_minor': 380430}
   {'step': 'CHECKOUT', 'sessions': 45, 'basket_minor': 277740}
   {'step': 'PAYMENT', 'sessions': 36, 'basket_minor': 220392}
   {'step': 'ORDER', 'sessions': 31, 'basket_minor': 177049}
   {'step': 'PAYMENT_FAILED', 'sessions': 5, 'basket_minor': 43343}
```

Summing `sessions` across windows is safe here because each session takes each step once, so it
falls in exactly one window per step.

### Conversion, without `CASE`

A continuous query has no `CASE`, so `SUM(CASE WHEN step = 'ORDER' …) / SUM(CASE WHEN step = 'CART' …)`
is not available — and not needed. The funnel is already one row per step; the rate is a division
over two of them, done by the reader:

```python
by_step = {row["step"]: row["sessions"] for row in client.query(open("sql/03-read-funnel-totals.sql").read())}
print(by_step["ORDER"] / by_step["CART"])
```

```text
-- conversion
   CHECKOUT         45 of 60 baskets = 75%
   PAYMENT          36 of 60 baskets = 60%
   ORDER            31 of 60 baskets = 52%
   PAYMENT_FAILED    5 of 60 baskets = 8%
```

### One step over time

[`sql/04-read-one-step.sql`](sql/04-read-one-step.sql):

```sql
SELECT window_end, device, sessions, basket_minor
FROM checkout_funnel
WHERE step = ?
```

Bound to `ORDER`:

```text
-- ORDER, per window and device
   {'window_end': '2026-11-27T10:05:00+00:00', 'device': 'web', 'sessions': 10, 'basket_minor': 59008}
   {'window_end': '2026-11-27T10:05:00+00:00', 'device': 'app', 'sessions': 5, 'basket_minor': 34004}
   {'window_end': '2026-11-27T10:10:00+00:00', 'device': 'app', 'sessions': 1, 'basket_minor': 6109}
   {'window_end': '2026-11-27T10:10:00+00:00', 'device': 'web', 'sessions': 12, 'basket_minor': 53904}
   {'window_end': '2026-11-27T10:15:00+00:00', 'device': 'web', 'sessions': 2, 'basket_minor': 16016}
   {'window_end': '2026-11-27T10:15:00+00:00', 'device': 'app', 'sessions': 1, 'basket_minor': 8008}
```

App orders fall from five to one in the window the outage sits in, while web orders rise. Total
orders barely move — which is exactly why a total is the wrong alarm.

### One device's funnel

[`sql/05-read-device-funnel.sql`](sql/05-read-device-funnel.sql):

```sql
SELECT step, SUM(sessions) AS sessions
FROM checkout_funnel
WHERE device = ?
GROUP BY step
```

Bound to `app`:

```text
-- app funnel
   {'step': 'CART', 'sessions': 20}
   {'step': 'CHECKOUT', 'sessions': 15}
   {'step': 'PAYMENT', 'sessions': 12}
   {'step': 'ORDER', 'sessions': 7}
   {'step': 'PAYMENT_FAILED', 'sessions': 5}
```

Five of the app's twelve payments failed.

### The spike

```text
-- payment_failure_spikes
   {'window_end': '2026-11-27T10:10:00+00:00', 'device': 'app', 'failures': 5, 'basket_at_risk_minor': 43343}
```

One row: the 10:05–10:10 window, the app, five failures, 433.43 in baskets turned away. A
subscriber — `python3 python/run.py --url grpc://localhost:9090 --watch` — receives it the moment
the window closes:

```text
following payment_failure_spikes; Ctrl-C to stop
-- snapshot
   +1 {'window_end': '2026-11-27T10:10:00+00:00', 'device': 'app', 'failures': 5, 'basket_at_risk_minor': 43343}
```

Here the window had closed before the subscription started, so it arrived in the snapshot; on a
live feed it arrives as a commit, at 10:10 plus the ten seconds of out-of-orderness.

## Making this yours

| To change | Do this |
|---|---|
| How quickly a spike is seen | `INTERVAL '5' MINUTE` → `'1' MINUTE` in `02`. Smaller windows answer sooner and are noisier |
| What counts as a spike | `HAVING COUNT(*) >= 3`; or `HAVING SUM(basket_minor) >= …` for money at risk |
| A rolling funnel | `TUMBLE` → `HOP(event_time, INTERVAL '1' MINUTE, INTERVAL '15' MINUTE)` — see the [telecom study](../telecom-cdr-fraud/) |
| More dimensions | Add `country` or `payment_method` to the `GROUP BY` and the key |
| A real feed | A Kafka source for the storefront's events; the SQL does not move |

## Pitfalls

- **A session that crosses a window boundary is split.** A shopper who adds to the basket at 10:04:50
  and orders at 10:05:50 is a CART in one window and an ORDER in the next, so a per-window
  conversion rate can exceed 100% or dip for reasons that are not the shop's. Rates over several
  windows, as above, are steadier.
- **`COUNT(DISTINCT)` is per window.** The same session counted in two windows is two there; sum
  distinct counts across windows only when, as here, each step happens once per session.
- **No `CASE`.** Conditional counts are separate rows (group by the thing you would have tested) or
  separate queries (a `WHERE` each), and the ratio is the reader's.
- **A spike is seen when its window closes**, not when the third failure arrives. For "tell me at the
  third failure", register a filter on `step = 'PAYMENT_FAILED'` and count in the subscriber.
- **An unwindowed `GROUP BY step` is refused** in a continuous query (`PRV-2050`) — its state would
  never shrink. The same `GROUP BY` over the view, as in `03`, is fine.

The full list of what runs and what is refused is
[`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md).
