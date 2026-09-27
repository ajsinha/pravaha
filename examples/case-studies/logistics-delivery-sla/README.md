# Delivery SLA breaches — a logistics case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** none — two CSV files the node follows · **Time to first result:** about fifteen minutes ·
**Shows:** an interval join between two streams, a `LEFT JOIN` with a time bound to find what did
*not* happen, a sink, an hourly tumbling window

## The problem

An express parcel is promised within two hours of leaving the depot. Two things go wrong, and the
second is the harder one to see:

- **It arrives late.** The delivery scan comes in, but more than two hours after the dispatch scan.
- **It never arrives.** There is no delivery scan at all — and "no scan yet" is not an event anything
  can react to. A system that only processes the scans it receives never notices the parcel that
  produced none.

Customer service wants late parcels in a file their ticketing system picks up, and a list of parcels
that are four hours out with no delivery, so they can call the customer before the customer calls
them. Operations wants dispatch volume per depot per hour beside it.

## What you will build

```
  data/dispatches.csv ──┐        Pravaha
                        ├──►  late_deliveries   (delivered 2–4 h after dispatch) ──► data/sla_breaches.csv
  data/deliveries.csv ──┤     undelivered       (no delivery within 4 h)
                        └──►  depot_dispatches  (dispatches per depot per hour)
```

## The data model

`dispatch` — one scan as a parcel leaves a depot:

| Column | Type | Meaning |
|---|---|---|
| `shipment_id` | STRING | The parcel |
| `depot` | STRING | `LHR1`, `MAN2`, `BRS3` |
| `service` | STRING | `express` (and `heartbeat` for the generator's clock rows) |
| `dispatch_time` | TIMESTAMP | When it left — **this stream's event time** |

`delivery` — one scan as a parcel is handed over:

| Column | Type | Meaning |
|---|---|---|
| `shipment_id` | STRING | The parcel |
| `courier` | STRING | The van |
| `delivered_time` | TIMESTAMP | When — **this stream's event time** |

Both are sources in [`schema/streams.properties`](schema/streams.properties) and
[`conf/application.yaml`](conf/application.yaml), each with thirty seconds of out-of-orderness for
scanners that sync late. The configuration also binds the `sla_breaches` sink, a CSV file.

## Step 1 — generate the morning

From this directory:

```bash
python3 data/generate_shipments.py
# wrote 25 dispatches and 23 deliveries to .../data
```

Twenty-four express parcels leave the three depots, one every five minutes from 06:00. Most arrive
within two hours. `P1005`, `P1009`, `P1013` and `P1017` take between two and four; `P1002` takes four
hours ten minutes; `P1020` and `P1023` are never delivered. Each file ends with a heartbeat scan at
12:30, which is what lets the engine say — in event time — that four hours have passed since the
last dispatch.

## Step 2 — start the node

From this directory:

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

```text
stream dispatch: event-time=dispatch_time, out-of-orderness=PT30S, allowed-lateness=PT0S
stream delivery: event-time=delivered_time, out-of-orderness=PT30S, allowed-lateness=PT0S
```

## Step 3 — the continuous queries

### Late deliveries: an interval join

[`sql/01-continuous-late-deliveries.sql`](sql/01-continuous-late-deliveries.sql):

```sql
SELECT d.shipment_id, d.depot, v.courier, d.dispatch_time, v.delivered_time
FROM dispatch AS d
JOIN delivery AS v
  ON v.shipment_id = d.shipment_id
 AND v.delivered_time BETWEEN d.dispatch_time + INTERVAL '2' HOUR AND d.dispatch_time + INTERVAL '4' HOUR
```

The time bound *is* the SLA: a pair matches only if the delivery came between two and four hours
after the dispatch. It also bounds the state — a dispatch can be forgotten four hours after it
happened. Registered with `sink="sla_breaches"`, so every breach is appended to
`data/sla_breaches.csv` as it is matched. An inner join of two append-only streams only ever adds
rows, which is why an append-only file can hold its answer.

### Undelivered: a `LEFT JOIN` that reports what did not happen

[`sql/02-continuous-undelivered.sql`](sql/02-continuous-undelivered.sql):

```sql
SELECT d.shipment_id, d.depot, d.dispatch_time
FROM dispatch AS d
LEFT JOIN delivery AS v
  ON v.shipment_id = d.shipment_id
 AND v.delivered_time BETWEEN d.dispatch_time AND d.dispatch_time + INTERVAL '4' HOUR
WHERE v.shipment_id IS NULL
  AND d.service = 'express'
```

This is the query that sees an absence. A `LEFT JOIN` emits every dispatch: matched ones when their
delivery arrives, and unmatched ones **padded with nulls, once, when event time passes the end of
the bound** — four hours after dispatch — because only then can the engine know no delivery is
coming. `WHERE v.shipment_id IS NULL` keeps only those. `d.service = 'express'` is an ordinary
filter on the left stream's own columns; here it keeps the generator's heartbeat rows out.

The time bound is not optional: a `LEFT JOIN` between streams without one is refused (`PRV-2020`),
because there would be no moment at which a row could be declared unmatched.

### Dispatches per depot per hour

[`sql/03-continuous-depot-dispatches.sql`](sql/03-continuous-depot-dispatches.sql):

```sql
SELECT STREAM
  TUMBLE_START(dispatch_time, INTERVAL '1' HOUR) AS hour_start,
  depot,
  COUNT(*) AS dispatched
FROM dispatch
GROUP BY TUMBLE(dispatch_time, INTERVAL '1' HOUR), depot
```

### Register them

```bash
python3 python/run.py --url grpc://localhost:19090
```

[`python/run.py`](python/run.py) registers the three — the first with `sink="sla_breaches"` —
waits, and reads. Java: [`java/DeliverySlaExample.java`](java/DeliverySlaExample.java). From a
shell: `pravaha register --name late_deliveries --sql-file sql/01-continuous-late-deliveries.sql --keys 0 --sink sla_breaches`.

```text
registered late_deliveries RUNNING 75fd3124b5a1 sla_breaches
registered undelivered RUNNING 51f8d0c539b4 None
registered depot_dispatches RUNNING ac5647f068c3 None
```

## Step 4 — ask questions

Everything below is what `python/run.py` printed on a real node.

### Late deliveries

```text
-- late_deliveries
   {'shipment_id': 'P1005', 'depot': 'BRS3', 'courier': 'van-11', 'dispatch_time': '2026-10-05T06:25:00+00:00', 'delivered_time': '2026-10-05T08:55:00+00:00'}
   {'shipment_id': 'P1013', 'depot': 'MAN2', 'courier': 'van-31', 'dispatch_time': '2026-10-05T07:05:00+00:00', 'delivered_time': '2026-10-05T09:25:00+00:00'}
   {'shipment_id': 'P1009', 'depot': 'LHR1', 'courier': 'van-32', 'dispatch_time': '2026-10-05T06:45:00+00:00', 'delivered_time': '2026-10-05T09:50:00+00:00'}
   {'shipment_id': 'P1017', 'depot': 'BRS3', 'courier': 'van-21', 'dispatch_time': '2026-10-05T07:25:00+00:00', 'delivered_time': '2026-10-05T10:15:00+00:00'}
```

and the same four in the sink's file, `data/sla_breaches.csv`, times as epoch nanoseconds:

```text
P1005,BRS3,van-11,1791181500000000000,1791190500000000000
P1013,MAN2,van-31,1791183900000000000,1791192300000000000
P1009,LHR1,van-32,1791182700000000000,1791193800000000000
P1017,BRS3,van-21,1791185100000000000,1791195300000000000
```

Each breach was written as soon as its delivery scan was read — the join has no window to wait for.

[`sql/04-read-breaches-by-depot.sql`](sql/04-read-breaches-by-depot.sql):

```sql
SELECT depot, COUNT(*) AS late_deliveries
FROM late_deliveries
GROUP BY depot
```

```text
-- late deliveries by depot
   {'depot': 'BRS3', 'late_deliveries': 2}
   {'depot': 'MAN2', 'late_deliveries': 1}
   {'depot': 'LHR1', 'late_deliveries': 1}
```

[`sql/05-read-one-shipment.sql`](sql/05-read-one-shipment.sql), bound to `P1009` — what a customer
service agent looks up:

```sql
SELECT shipment_id, depot, courier, dispatch_time, delivered_time
FROM late_deliveries
WHERE shipment_id = ?
```

```text
-- shipment P1009
   {'shipment_id': 'P1009', 'depot': 'LHR1', 'courier': 'van-32', 'dispatch_time': '2026-10-05T06:45:00+00:00', 'delivered_time': '2026-10-05T09:50:00+00:00'}
```

### Undelivered after four hours

```text
-- undelivered after four hours
   {'shipment_id': 'P1023', 'depot': 'BRS3', 'dispatch_time': '2026-10-05T07:55:00+00:00'}
   {'shipment_id': 'P1020', 'depot': 'BRS3', 'dispatch_time': '2026-10-05T07:40:00+00:00'}
   {'shipment_id': 'P1002', 'depot': 'BRS3', 'dispatch_time': '2026-10-05T06:10:00+00:00'}
```

Three parcels, all from Bristol — the depot with two late deliveries as well. `P1002` *was*
delivered, at 10:20, four hours and ten minutes after dispatch: outside the bound, so the `LEFT
JOIN` found no match within it and reported it. Past four hours it is treated as lost, which is the
rule this query encodes.

### Dispatch volume

[`sql/06-read-depot-dispatches.sql`](sql/06-read-depot-dispatches.sql):

```sql
SELECT depot, SUM(dispatched) AS dispatched
FROM depot_dispatches
GROUP BY depot
```

```text
-- dispatched per depot
   {'depot': 'LHR1', 'dispatched': 8}
   {'depot': 'BRS3', 'dispatched': 8}
   {'depot': 'MAN2', 'dispatched': 8}
```

Bristol dispatches no more than the others. Its problem is not volume.

## Step 5 — follow missing parcels

```bash
python3 python/run.py --url grpc://localhost:19090 --watch
```

In another terminal: `P1020`'s delivery scan finally syncs at 12:40, a new parcel `P2000` leaves
Heathrow at 12:35, and both streams move on to 16:40:

```bash
echo "P1020,van-11,2026-10-05T12:40:00Z"                 >> data/deliveries.csv
echo "P2000,LHR1,express,2026-10-05T12:35:00Z"           >> data/dispatches.csv
echo "HB-DISPATCH-2,none,heartbeat,2026-10-05T16:40:00Z" >> data/dispatches.csv
echo "HB-DELIVERY-2,none,2026-10-05T16:40:00Z"           >> data/deliveries.csv
```

```text
following undelivered; Ctrl-C to stop
-- snapshot
   +1 {'shipment_id': 'P1023', 'depot': 'BRS3', 'dispatch_time': '2026-10-05T07:55:00+00:00'}
   +1 {'shipment_id': 'P1020', 'depot': 'BRS3', 'dispatch_time': '2026-10-05T07:40:00+00:00'}
   +1 {'shipment_id': 'P1002', 'depot': 'BRS3', 'dispatch_time': '2026-10-05T06:10:00+00:00'}
-- commit
   +1 {'shipment_id': 'P2000', 'depot': 'LHR1', 'dispatch_time': '2026-10-05T12:35:00+00:00'}
```

`P2000` appears the moment both streams pass 16:35 — four hours after it left — with no delivery.
`P1020` does **not** leave the view: its delivery arrived five hours after dispatch, outside the
bound, and a null-padded row is emitted once and never withdrawn.

## Making this yours

| To change | Do this |
|---|---|
| The SLA | `INTERVAL '2' HOUR` in `01`; one query per service level, each with its own `WHERE d.service = …` |
| When a parcel counts as lost | `INTERVAL '4' HOUR` in `02` (and the upper bound in `01`, so the two agree) |
| Breaches somewhere else | Another sink binding — JDBC, Kafka — on the same `sink=` |
| Per-courier views | Group the late deliveries by `v.courier` in a windowed query over the join |
| Real feeds | Kafka topics for the two scan streams; the SQL does not move |

## Pitfalls

- **"Not delivered" is only known when both streams have moved on.** The `LEFT JOIN`'s watermark is
  the lesser of the two streams'. If the delivery scanners go quiet, no parcel can ever be declared
  undelivered. Heartbeats — here, the last row of each file — keep both moving.
- **A null-padded row is final.** A delivery that turns up after the bound does not withdraw it,
  as `P1020` shows. Choose the bound as "when we act", not "when we give up hope".
- **The bound must be written on both sides of the SLA.** `01` is 2–4 hours and `02` is 0–4 hours;
  a delivery after four hours is in neither `late_deliveries` nor gone from `undelivered`.
- **Heartbeat rows are rows.** Filter them out, as `02` does with `service = 'express'`; the
  hourly `depot_dispatches` window that holds a heartbeat has a `none` depot in it.
- **A `LEFT JOIN` without a time bound, or a `RIGHT`/`FULL` join, is refused** (`PRV-2020`). Swap
  the inputs to turn a right join into a left one.

The full list of what runs and what is refused is
[`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md).
