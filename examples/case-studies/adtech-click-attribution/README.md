# Click attribution — an ad-tech case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** none — two CSV files the node follows · **Time to first result:** about fifteen minutes ·
**Shows:** a join between two streams with a time bound, a window over a join, joining two views in
the reader

## The problem

An ad server records every impression it serves; a click tracker records every click. They are
different systems on different machines, and a click is only worth paying for if it follows an
impression of the same ad, to the same user, within an attribution window — ten minutes here.
Advertisers are billed on attributed clicks and judge campaigns on click-through rate, so both
numbers must be right and, during a campaign launch, current.

The usual answer is an hourly batch that loads both logs and joins them. It is correct and an hour
late, and a campaign that launched with a broken creative has spent its morning budget before
anyone sees a click-through rate of zero.

This study keeps the attribution itself as a maintained view, and click and impression counts per
campaign in five-minute windows beside it.

## What you will build

```
  data/impressions.csv ──┐        Pravaha
                         ├──►  attributed_clicks      (impression ⋈ click, within 10 minutes)
  data/clicks.csv ───────┤     campaign_clicks        (5-minute windows over the join)
                         └──►  campaign_impressions   (5-minute windows over impressions)
                                        │
                                        ▼
                         click-through rate = clicks / impressions, joined by the reader
```

## The data model

Two streams:

`ad_impression` — one row per ad served:

| Column | Type | Meaning |
|---|---|---|
| `impression_id` | STRING | Unique per impression; the click carries it back |
| `campaign_id` | STRING | `summer-sale`, `new-phone`, `travel` |
| `user_id` | STRING | Who saw it |
| `placement` | STRING | Where on the page: `news-top`, `sports-side`, `video-pre` |
| `cost_micros` | INT64 | What the impression cost, in **millionths** of the currency: `1500` is 0.0015 |
| `impression_time` | TIMESTAMP | When it was served — **this stream's event time** |

`ad_click` — one row per click:

| Column | Type | Meaning |
|---|---|---|
| `click_id` | STRING | Unique per click |
| `impression_id` | STRING | The impression the click claims to follow |
| `user_id` | STRING | Who clicked |
| `click_time` | TIMESTAMP | When — **this stream's event time** |

Both are declared as sources, each with its own event time, in
[`schema/streams.properties`](schema/streams.properties) and
[`conf/application.yaml`](conf/application.yaml).

## Step 1 — generate the launch

From this directory:

```bash
python3 data/generate_events.py
# wrote 61 impressions and 17 clicks to .../data
```

Sixty impressions ten seconds apart from 12:00:00, rotating through three campaigns and three
placements, to twenty users. Every fourth is clicked, 30 to 90 seconds later — fifteen clicks, less
one. Two clicks are there to be *refused*: one on `imp-008` arrives twelve minutes after it was
served, and one names `imp-999`, which was never served. Each file ends with a heartbeat at 12:25:00;
why both need one is Step 3's pitfall.

## Step 2 — start the node

From this directory:

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

```text
stream ad_impression: event-time=impression_time, out-of-orderness=PT5S, allowed-lateness=PT0S
stream ad_click: event-time=click_time, out-of-orderness=PT5S, allowed-lateness=PT0S
```

## Step 3 — the continuous queries

### Attribution: a join between two streams

[`sql/01-continuous-attributed-clicks.sql`](sql/01-continuous-attributed-clicks.sql):

```sql
SELECT c.click_id, i.impression_id, i.campaign_id, i.placement, i.user_id, i.cost_micros,
       i.impression_time, c.click_time
FROM ad_impression AS i
JOIN ad_click AS c
  ON c.impression_id = i.impression_id
 AND c.user_id = i.user_id
 AND c.click_time BETWEEN i.impression_time AND i.impression_time + INTERVAL '10' MINUTE
```

Both sides are streams, so the engine keeps state for each side and emits a row when a pair
matches. The `BETWEEN` is doing two jobs at once:

- **It is the business rule.** A click counts only if it follows its impression within ten minutes.
- **It bounds the state.** An impression can be forgotten ten minutes (plus out-of-orderness) after
  it was served, because no click after that could match it. Without a time bound a join between two
  streams would hold every row for ever; with only a time bound and no equality it would be a cross
  product — both are refused (`PRV-2020`).

The equality on `user_id` as well as `impression_id` is a multi-column key; it refuses a click whose
impression id was lifted from someone else's page.

### Clicks per campaign, per five minutes: a window over a join

[`sql/02-continuous-campaign-clicks.sql`](sql/02-continuous-campaign-clicks.sql):

```sql
SELECT STREAM
  TUMBLE_END(c.click_time, INTERVAL '5' MINUTE) AS window_end,
  i.campaign_id,
  COUNT(*)                  AS clicks,
  COUNT(DISTINCT i.user_id) AS clicking_users
FROM ad_impression AS i
JOIN ad_click AS c
  ON c.impression_id = i.impression_id
 AND c.user_id = i.user_id
 AND c.click_time BETWEEN i.impression_time AND i.impression_time + INTERVAL '10' MINUTE
GROUP BY TUMBLE(c.click_time, INTERVAL '5' MINUTE), i.campaign_id
```

The same join, grouped into five-minute windows of click time. It is written over the two streams
rather than over `attributed_clicks`, because a continuous query is written over declared streams.

### Impressions per campaign, per five minutes

[`sql/03-continuous-campaign-impressions.sql`](sql/03-continuous-campaign-impressions.sql):

```sql
SELECT STREAM
  TUMBLE_END(impression_time, INTERVAL '5' MINUTE) AS window_end,
  campaign_id,
  COUNT(*)         AS impressions,
  SUM(cost_micros) AS spend_micros
FROM ad_impression
GROUP BY TUMBLE(impression_time, INTERVAL '5' MINUTE), campaign_id
```

### Register them

```bash
python3 python/run.py --url grpc://localhost:19090
```

[`python/run.py`](python/run.py) registers the three — keys `[0]`, `[0, 1]`, `[0, 1]` — waits for
the windows to close, and reads. Java: [`java/ClickAttributionExample.java`](java/ClickAttributionExample.java).
From a shell:
`pravaha register --name attributed_clicks --sql-file sql/01-continuous-attributed-clicks.sql --keys 0`.

```text
registered attributed_clicks RUNNING d6eff2fb0555
registered campaign_clicks RUNNING e582fbd16fe6
registered campaign_impressions RUNNING da9ecdcb466b
```

> **Why each file ends with a heartbeat.** A query over two streams is only as far on in event time
> as the laggier of them: its watermark is the *minimum* of the two. If the click tracker goes
> quiet, no window over the join can close however many impressions arrive — the engine cannot
> know that a click for them is not still on its way. In production each stream needs traffic or a
> periodic heartbeat; here the last line of each file is one.

## Step 4 — ask questions

Everything below is what `python/run.py` printed on a real node.

### One campaign's attributed clicks

[`sql/05-read-campaign-clicks.sql`](sql/05-read-campaign-clicks.sql):

```sql
SELECT click_id, impression_id, placement, user_id, click_time
FROM attributed_clicks
WHERE campaign_id = ?
```

Bound to `travel`:

```text
-- attributed clicks for travel
   {'click_id': 'clk-005', 'impression_id': 'imp-020', 'placement': 'news-top', 'user_id': 'u-00', 'click_time': '2026-07-01T12:03:50+00:00'}
   {'click_id': 'clk-009', 'impression_id': 'imp-032', 'placement': 'sports-side', 'user_id': 'u-12', 'click_time': '2026-07-01T12:06:20+00:00'}
   {'click_id': 'clk-012', 'impression_id': 'imp-044', 'placement': 'video-pre', 'user_id': 'u-04', 'click_time': '2026-07-01T12:08:50+00:00'}
   {'click_id': 'clk-015', 'impression_id': 'imp-056', 'placement': 'news-top', 'user_id': 'u-16', 'click_time': '2026-07-01T12:10:05+00:00'}
```

Fourteen clicks are attributed in all. The late click on `imp-008` and the click on `imp-999` are
not in the view: one fell outside the time bound, the other matched nothing.

### Clicks by placement

[`sql/06-read-clicks-by-placement.sql`](sql/06-read-clicks-by-placement.sql):

```sql
SELECT placement, COUNT(*) AS clicks, SUM(cost_micros) AS spend_on_clicked_micros
FROM attributed_clicks
GROUP BY placement
```

```text
-- clicks by placement
   {'placement': 'news-top', 'clicks': 5, 'spend_on_clicked_micros': 7500}
   {'placement': 'sports-side', 'clicks': 5, 'spend_on_clicked_micros': 7500}
   {'placement': 'video-pre', 'clicks': 4, 'spend_on_clicked_micros': 6000}
```

### Click-through rate: two views, joined by the reader

A read of a view names **one** view. `SELECT … FROM campaign_impressions JOIN campaign_clicks …`
is refused:

```text
PRV-4025  a request/response query reads exactly one view; this one reads [campaign_impressions,
campaign_clicks]. Joining views in a single request is analytics, which this server deliberately
does not do (ADR-030) ...
```

The two views share a key — `(window_end, campaign_id)` — so the reader reads each and joins on it.
[`sql/04-read-campaign-impressions.sql`](sql/04-read-campaign-impressions.sql):

```sql
SELECT window_end, campaign_id, impressions, spend_micros
FROM campaign_impressions
```

and `SELECT window_end, campaign_id, clicks FROM campaign_clicks`, joined in `python/run.py`:

```text
-- click-through rate, per campaign and five minutes
   12:05 new-phone     2 clicks / 10 impressions = 20.0%  cost per click 0.0091
   12:05 summer-sale   2 clicks / 10 impressions = 20.0%  cost per click 0.0094
   12:05 travel        1 clicks / 10 impressions = 10.0%  cost per click 0.0187
   12:10 new-phone     3 clicks / 10 impressions = 30.0%  cost per click 0.0064
   12:10 summer-sale   3 clicks / 10 impressions = 30.0%  cost per click 0.0063
   12:10 travel        2 clicks / 10 impressions = 20.0%  cost per click 0.0094
```

Each view is maintained by its own query and read in one round trip; the join over a dozen rows is
the reader's, which is where the server design (ADR-030) puts it.

## Step 5 — follow attribution live

```bash
python3 python/run.py --url grpc://localhost:19090 --watch
```

and in another terminal, serve an impression and click it forty seconds later:

```bash
echo "imp-100,travel,u-05,video-pre,2000,2026-07-01T12:26:00Z" >> data/impressions.csv
echo "clk-100,imp-100,u-05,2026-07-01T12:26:40Z"               >> data/clicks.csv
```

```text
following attributed_clicks; Ctrl-C to stop
-- snapshot (14 rows)
-- commit (1 rows)
   +1 {'click_id': 'clk-100', 'impression_id': 'imp-100', 'campaign_id': 'travel', 'placement': 'video-pre', 'user_id': 'u-05', 'cost_micros': 2000, 'impression_time': '2026-07-01T12:26:00+00:00', 'click_time': '2026-07-01T12:26:40+00:00'}
```

The join has no window, so a match is emitted as soon as both halves have been read — it does not
wait for event time to pass. Only the windowed views wait.

## Making this yours

| To change | Do this |
|---|---|
| The attribution window | `INTERVAL '10' MINUTE` in both `01` and `02`. Longer windows hold more state |
| Last-touch across impressions | Join on `user_id` and `campaign_id` instead of `impression_id`, and keep the latest with a top-N — see the [telecom study](../telecom-cdr-fraud/) for `ROW_NUMBER` |
| Unclicked impressions | A `LEFT JOIN` with the same time bound emits each unmatched impression once the bound passes — see the [logistics study](../logistics-delivery-sla/) |
| Conversions, not clicks | A third stream joined to the clicks with its own time bound: three-way joins between distinct streams run |
| Real feeds | Kafka sources for both topics; the SQL does not move |

## Pitfalls

- **The join's event time is the slower stream's.** One quiet input holds back every window over
  the join. Heartbeats, or a source that emits periodic watermarks, keep it moving.
- **Clicks are windowed by click time, impressions by impression time.** A click at 12:05:10 on an
  impression served at 12:04:50 is in the 12:10 clicks window and the 12:05 impressions window, so
  per-window rates wobble at the edges; the 12:15 clicks window here has no impressions window at all.
  Rates over several windows are steadier.
- **A read names one view** (`PRV-4025`). Give views that will be combined the same key columns, so
  the reader's join is a dictionary lookup.
- **A continuous query is written over streams.** A window over a join repeats the join, as `02`
  does, rather than reading the view `01` maintains.
- **A late click is not attributed.** It is outside the bound by definition — that is the rule, not
  data loss — but a click tracker that batches for minutes needs its `out-of-orderness` set to
  match, or on-time clicks will look late.

The full list of what runs and what is refused is
[`docs/guides/CONTINUOUS_QUERIES.md`](../../../docs/guides/CONTINUOUS_QUERIES.md).
