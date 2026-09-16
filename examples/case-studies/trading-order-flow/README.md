# Order flow surveillance — a trading case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** Aerospike · **Time to first result:** about fifteen minutes · **Setup:** [`../SETUP.md`](../SETUP.md)

## The problem

A trader posts fifty orders in a minute and cancels forty-eight of them. Each order is legal. The
*ratio* is what a surveillance desk cares about, and it only exists across a window.

Two things make this harder than the previous studies, and both are the point of including it:

1. **Windows must overlap.** A one-minute tumbling window resets on the minute, so a burst spanning
   12:00:58 to 12:01:03 is split into two halves that each look ordinary. A **hopping** window —
   sixty seconds wide, advancing every ten — sees the burst whole.
2. **You need two counters from one stream**, orders and cancels, and there is no `CASE` expression
   to get them in one pass. This study shows what you do instead, and it is not a workaround so much
   as an honest consequence of the engine refusing to hide state.

## What you will build

Two continuous queries over one stream, and an application that reads both.

```
   Aerospike                    Pravaha                          surveillance
  ┌───────────┐               ┌──────────────────────┐          ┌─────────────┐
  │ order     │ ────────────► │ order_rate  (NEW)    │ ───────► │ ratio, per  │
  │ event set │  filter       │ cancel_rate (CANCEL) │   SQL    │ trader, per │
  └───────────┘  pushed down  └──────────────────────┘          │ instrument  │
  ┌───────────┐  lookup join                                    └─────────────┘
  │ instrument│ ────────────►
  └───────────┘
```

## The data model

### `order_event` — the stream

One record per lifecycle event. An order that is placed and cancelled produces **two** records.

| Bin | Type | Meaning |
|---|---|---|
| `order_id` | integer | The order this event belongs to |
| `trader_id` | string | Who. A grouping key |
| `instrument_id` | string | What. The other grouping key |
| `side` | string | `BUY` or `SELL` |
| `qty` | integer | Units |
| `price_minor` | integer | Limit price in minor units |
| `event_type` | string | `NEW`, `CANCEL`, `FILL` |
| `event_time` | integer | Event time, epoch nanoseconds |

> **`event_time` must be the exchange's timestamp, not yours.** Surveillance conclusions drawn from
> the time your process happened to see a message are conclusions about your network. Every venue
> gives you a timestamp; use it.

### `instrument` — the reference data

| Bin | Type | Meaning |
|---|---|---|
| `instrument_id` | string | Primary key |
| `symbol` | string | `VOD.L`, `AAPL` |
| `asset_class` | string | `EQUITY`, `FUTURE`, `FX` |
| `tick_minor` | integer | Minimum price increment |

Machine-readable and build-checked: [`schema/streams.properties`](schema/streams.properties).

## Step 1 — start Aerospike

```bash
docker run -d --name pravaha-aerospike --network host aerospike/aerospike-server:latest
docker exec pravaha-aerospike asinfo -v status   # expect: ok
```

Read the `--network host` warning in [`../SETUP.md`](../SETUP.md) before you skip it.

## Step 2 — load instruments

```bash
docker exec -it pravaha-aerospike aql
```
```sql
INSERT INTO test.instrument (PK, instrument_id, symbol, asset_class, tick_minor)
  VALUES ('i-1', 'i-1', 'VOD.L', 'EQUITY', 1);
INSERT INTO test.instrument (PK, instrument_id, symbol, asset_class, tick_minor)
  VALUES ('i-2', 'i-2', 'AAPL',  'EQUITY', 1);
INSERT INTO test.instrument (PK, instrument_id, symbol, asset_class, tick_minor)
  VALUES ('i-3', 'i-3', 'ESZ6',  'FUTURE', 25);
```

## Step 3 — the two continuous queries

### Orders, [`sql/01-continuous-new-order-rate.sql`](sql/01-continuous-new-order-rate.sql)

```sql
SELECT STREAM
  HOP_END(o.event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE) AS window_end,
  o.trader_id,
  i.symbol,
  i.asset_class,
  COUNT(*)         AS new_orders,
  SUM(o.qty)       AS total_qty,
  MAX(o.price_minor) AS high_price_minor
FROM order_event AS o
LEFT JOIN instrument FOR SYSTEM_TIME AS OF o.event_time AS i
       ON o.instrument_id = i.instrument_id
WHERE o.event_type = 'NEW'
GROUP BY HOP(o.event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE), o.trader_id, i.symbol, i.asset_class
```

### Cancels, [`sql/02-continuous-cancel-rate.sql`](sql/02-continuous-cancel-rate.sql)

```sql
SELECT STREAM
  HOP_END(o.event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE) AS window_end,
  o.trader_id,
  i.symbol,
  COUNT(*) AS cancels
FROM order_event AS o
LEFT JOIN instrument FOR SYSTEM_TIME AS OF o.event_time AS i
       ON o.instrument_id = i.instrument_id
WHERE o.event_type = 'CANCEL'
GROUP BY HOP(o.event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE), o.trader_id, i.symbol
```

**`HOP(event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE)`** reads as *slide ten seconds, span one
minute*. Every event falls into six windows, and the engine slices rather than duplicating: one
accumulator per record whatever the overlap, so a sixty-fold overlap does not cost sixty times the
memory.

**Why two queries and not one.** The natural thing to write is:

```sql
-- NOT SUPPORTED. Shown so you recognise it when you reach for it.
SUM(CASE WHEN o.event_type = 'CANCEL' THEN 1 ELSE 0 END) AS cancels
```

Pravaha has no `CASE`, so you register two queries. They have different plans, so they are different
computations with separate state — the engine will not accidentally share them. The cost is real:
the stream is read once per registration. The benefit is that neither query hides work from you.

Register both, through the SDK:

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090")) {
    client.register("order_rate",  Files.readString(Path.of("sql/01-continuous-new-order-rate.sql")), List.of(1));
    client.register("cancel_rate", Files.readString(Path.of("sql/02-continuous-cancel-rate.sql")), List.of(1));
}
```

Or from a shell:

```bash
pravaha register --name order_rate  --sql-file sql/01-continuous-new-order-rate.sql --keys 1
pravaha register --name cancel_rate --sql-file sql/02-continuous-cancel-rate.sql --keys 1
pravaha queries
```

They have different plans, so they are **two computations with two copies of state** — which
`pravaha queries` shows as two different fingerprints. That is the cost of not having `CASE`, stated
where you can see it rather than hidden.

## Step 4 — stream order events

```sql
INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, price_minor, event_type, event_time)
  VALUES ('e-1', 1, 't-7', 'i-1', 'BUY', 500, 7412, 'NEW',    1767225600000000000);
INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, price_minor, event_type, event_time)
  VALUES ('e-2', 2, 't-7', 'i-1', 'BUY', 500, 7413, 'NEW',    1767225601000000000);
INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, price_minor, event_type, event_time)
  VALUES ('e-3', 1, 't-7', 'i-1', 'BUY', 500, 7412, 'CANCEL', 1767225601500000000);
INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, price_minor, event_type, event_time)
  VALUES ('e-4', 2, 't-7', 'i-1', 'BUY', 500, 7413, 'CANCEL', 1767225602000000000);
INSERT INTO test.order_event (PK, order_id, trader_id, instrument_id, side, qty, price_minor, event_type, event_time)
  VALUES ('e-5', 3, 't-9', 'i-2', 'SELL', 100, 19050, 'NEW',  1767225603000000000);
```

`t-7` posts two and cancels two — a 100 % cancel ratio. `t-9` posts one and leaves it. To generate a
realistic burst:

```bash
python3 data/generate_orders.py --traders t-7,t-9 --seconds 180 --cancel-ratio 0.9 | \
  docker exec -i pravaha-aerospike aql
```

## Step 5 — ask questions

### One trader

[`sql/03-read-trader-activity.sql`](sql/03-read-trader-activity.sql):

```sql
SELECT trader_id, symbol, new_orders, total_qty
FROM order_rate
WHERE trader_id = ?
```

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    orders  = {(r["trader_id"], r["symbol"]): r["new_orders"]
               for r in client.query(open("sql/03-read-trader-activity.sql").read(), ["t-7"])}
    cancels = {(r["trader_id"], r["symbol"]): r["cancels"]
               for r in client.query("SELECT trader_id, symbol, cancels FROM cancel_rate WHERE trader_id = ?", ["t-7"])}

    for key, placed in orders.items():
        pulled = cancels.get(key, 0)
        if placed and pulled / placed > 0.8:
            print("high cancel ratio", key, pulled, "/", placed)
```

The ratio is computed in the application, which is where the two-query design lands it.

### Where the activity is

[`sql/04-read-busiest-by-asset-class.sql`](sql/04-read-busiest-by-asset-class.sql):

```sql
SELECT asset_class, COUNT(*) AS lines, SUM(new_orders) AS orders, SUM(total_qty) AS qty
FROM order_rate
GROUP BY asset_class
```

## Step 6 — a symbol change mid-session

```sql
UPDATE test.instrument SET symbol = 'VOD.LN' WHERE PK = 'i-1';
```

Windows already closed keep `VOD.L`. Surveillance evidence must say what the instrument was called
at the time, not what it is called when somebody opens the report.

## Making this yours

| To change | Do this |
|---|---|
| Sensitivity to bursts | Shorten the hop: `'10' SECOND` → `'2' SECOND`. More windows, more state |
| Longer memory | Widen the span: `'1' MINUTE` → `'5' MINUTE` |
| Per-instrument rather than per-trader | Drop `o.trader_id` from both `GROUP BY` lists |
| Fills as well | A third registration, `WHERE o.event_type = 'FILL'` |

## Limits you will meet

Full list: [`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md).

- **No `CASE`** — the reason this study has two queries.
- **No joining two views**, so the ratio is computed client-side rather than in SQL.
- **No `ORDER BY` / `LIMIT`** — "worst ten traders" is sorted by your application.
- **No window functions** (`ROW_NUMBER() OVER (...)`), so ranking within a window is client-side too.
- **`SESSION` windows exist in the runtime but have no SQL surface yet.** For "a burst is activity
  with no thirty-second gap", use a short `HOP` and detect the gap yourself.
