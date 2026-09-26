# Card authorisation velocity — a banking case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** Aerospike · **Time to first result:** about fifteen minutes · **Setup:** [`../SETUP.md`](../SETUP.md)

## The problem

A card is used four times in ninety seconds, at four different merchants, in a country the
cardholder has never transacted in. Each authorisation on its own is unremarkable. The *pattern* is
the fraud, and it exists only in the relationship between events that arrive milliseconds apart.

The usual architecture answers this badly. Authorisations land in a store; a job wakes up every few
minutes, scans what is new, recomputes per-card counters, and writes them to a second store that the
decision engine reads. The fraud is detected four minutes after the fourth authorisation cleared.

Pravaha's claim is that the counter should be maintained as the authorisations arrive, and read
directly. This case study is that claim, small enough to run on a laptop.

## What you will build

```
   Aerospike                      Pravaha                     your application
  ┌──────────┐   scan + filter   ┌─────────────────┐   SQL   ┌────────────────┐
  │ auth     │ ────────────────► │ continuous      │ ──────► │ "is this card  │
  │ set      │   pushed down     │ query keeps     │         │  running hot?" │
  └──────────┘                   │ card_velocity   │         └────────────────┘
  ┌──────────┐   lookup join     │ current         │
  │ holder   │ ────────────────► │                 │
  │ set      │                   └─────────────────┘
  └──────────┘
```

One continuous query maintains a view called `card_velocity`. Your application queries that view
with ordinary SQL, including parameters. There is no second store, no cache to invalidate, and no
job to schedule.

## The data model

Two Aerospike sets in one namespace (`test`, which the default image creates for you).

### `auth` — the stream

One record per card authorisation. This is the thing that arrives constantly.

| Bin | Type | Meaning |
|---|---|---|
| `auth_id` | string | Unique per authorisation |
| `card_id` | string | The card. **This is the key you group by** |
| `merchant_id` | string | Where it was presented |
| `amount_minor` | integer | Amount in **minor units** — pence, cents. Never a float; see below |
| `mcc` | integer | Merchant category code (5411 grocery, 5812 restaurant, 7995 gambling) |
| `status` | string | `APPROVED`, `DECLINED`, `PENDING` |
| `auth_time` | integer | Event time, **epoch nanoseconds** |

> **Money is an integer of minor units, always.** £12.34 is `1234`, not `12.34`. A floating-point
> amount cannot represent 0.10 exactly, so a sum of a million of them is wrong in a way that is
> tedious to discover and embarrassing to explain. Pravaha refuses `DECIMAL` on the wire for the
> same reason — the rounding decision belongs to whoever owns the ledger.

> **Time is epoch nanoseconds.** The engine is built on event time throughout, and nanoseconds is
> what it holds internally. A millisecond timestamp multiplied by 1 000 000 is fine.
>
> **And `auth_time` has to be *declared* as this stream's event time**, in
> [`conf/application.yaml`](conf/application.yaml) — a `TIMESTAMP` column is not assumed to be one.
> That single key is what makes a watermark advance and a window close; the engine refuses a
> windowed query over a stream without it.

### `holder` — the reference data

One record per card. This changes rarely and is joined onto the stream.

| Bin | Type | Meaning |
|---|---|---|
| `card_id` | string | Primary key |
| `customer_id` | string | The person |
| `risk_band` | string | `LOW`, `MEDIUM`, `HIGH` — from your existing risk model |
| `daily_limit_minor` | integer | Their agreed daily limit |

The machine-readable version of this model, which the build checks the SQL against, is
[`schema/streams.properties`](schema/streams.properties); the node's own copy of it, with the
event-time declaration that makes the window able to close, is
[`conf/application.yaml`](conf/application.yaml).

## Step 1 — start Aerospike

Follow [`../SETUP.md`](../SETUP.md) first. In short:

```bash
docker run -d --name pravaha-aerospike --network host aerospike/aerospike-server:latest
docker exec pravaha-aerospike asinfo -v status   # expect: ok
```

## Step 2 — load the reference data

```bash
docker exec -it pravaha-aerospike aql
```

Then paste:

```sql
INSERT INTO test.holder (PK, card_id, customer_id, risk_band, daily_limit_minor)
  VALUES ('c-1001', 'c-1001', 'cust-1', 'LOW',    500000);
INSERT INTO test.holder (PK, card_id, customer_id, risk_band, daily_limit_minor)
  VALUES ('c-1002', 'c-1002', 'cust-2', 'HIGH',   100000);
INSERT INTO test.holder (PK, card_id, customer_id, risk_band, daily_limit_minor)
  VALUES ('c-1003', 'c-1003', 'cust-3', 'MEDIUM', 250000);
```

Check it:

```sql
SELECT * FROM test.holder;
```

> `PK` is Aerospike's primary key and is separate from the bins. We set `card_id` as both, because
> the lookup join matches on the *bin* — the key is how Aerospike finds the record, the bin is what
> the query compares.

## Step 3 — start the node

The server is described by one file, shipped with this study:
[`conf/application.yaml`](conf/application.yaml). From this directory:

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
pravaha queries --url grpc://localhost:9090     # expect: no continuous queries are registered
```

The Aerospike connector ships inside the server jar, so there is nothing to build in — see
[`../SETUP.md`](../SETUP.md) for how to start the node and what the `dev` profile does.

Three settings in that file decide whether this study works at all — `event-time` and
`out-of-orderness` on the stream, `deletes` on the source binding:

- **`event-time: auth_time`** tells the catalogue which column carries an authorisation's own time,
  and the node passes the same column to the Aerospike binding so every record read is stamped with
  it. Without it no watermark advances over `card_auth`, so the minute the query groups by could
  never close — and the engine refuses to register a windowed query over such a stream rather than
  let you find out from an empty view.
- **`out-of-orderness: 10s`** is how late an authorisation may arrive, in event time, and still be
  waited for. It is why the minute closes ten seconds after its end rather than exactly at it.
- **`deletes: detect`** is what lets an *aggregate* read an Aerospike scan at all. A plain scan
  re-reads an updated record as a new row and retracts nothing, so `COUNT(*)` would count it twice;
  the engine refuses that with `PRV-2042`.

The node states what it is running on when it starts, which is the line to check if a view is ever
unexpectedly empty:

```text
stream card_auth: event-time=auth_time, out-of-orderness=PT10S, allowed-lateness=PT0S
```

## Step 4 — the continuous query

This is the whole thing. It is checked by the build, in
[`sql/01-continuous-card-velocity.sql`](sql/01-continuous-card-velocity.sql):

```sql
SELECT STREAM
  TUMBLE_END(a.auth_time, INTERVAL '1' MINUTE) AS window_end,
  a.card_id,
  h.risk_band,
  COUNT(*)                      AS auth_count,
  SUM(a.amount_minor)           AS total_minor,
  COUNT(DISTINCT a.merchant_id) AS distinct_merchants
FROM card_auth AS a
LEFT JOIN cardholder FOR SYSTEM_TIME AS OF a.auth_time AS h
       ON a.card_id = h.card_id
WHERE a.status = 'APPROVED'
GROUP BY TUMBLE(a.auth_time, INTERVAL '1' MINUTE), a.card_id, h.risk_band
```

Reading it a line at a time:

- **`TUMBLE(a.auth_time, INTERVAL '1' MINUTE)`** — one bucket per card per minute, non-overlapping.
  The window is what makes this safe to run forever: state is released when a minute closes. Group
  by a card with no window and the engine refuses it (`PRV-2050`), because that aggregate would keep
  one counter per card that ever existed, for as long as the process lives.
- **`FOR SYSTEM_TIME AS OF a.auth_time`** — a *temporal* lookup. The risk band joined is the one that
  was true when the authorisation happened, not the one that is true now. Reprocess yesterday and you
  get yesterday's answer, which is what makes a result reproducible and an audit possible.
- **`WHERE a.status = 'APPROVED'`** — this is **pushed into Aerospike**. Declined authorisations are
  filtered by the server and never cross the network. Pravaha keeps its own copy of the filter too,
  so a store that cannot filter is still correct, just busier.
- **`COUNT(DISTINCT a.merchant_id)`** — four merchants in a minute is the signal. Distinct counting
  inside a window is bounded, because the window ends.

Register it through the SDK — Java:

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090")) {
    RegisteredQueryInfo registered = client.register(
            "card_velocity",                                    // the view your SQL will read
            Files.readString(Path.of("sql/01-continuous-card-velocity.sql")),
            List.of(1));                                        // key the view by card_id
}
```

Python:

```python
with connect("grpc://localhost:9090") as client:
    client.register("card_velocity", open("sql/01-continuous-card-velocity.sql").read(), [1])
```

Or from a shell, with no code at all:

```bash
pravaha register --url grpc://localhost:9090 \
  --name card_velocity --sql-file sql/01-continuous-card-velocity.sql --keys 1
pravaha queries --url grpc://localhost:9090
```

> **Notice what the client does not have.** No schemas, no engine, no plugin configuration — it
> sends SQL and reads answers. The stream definitions live on the server, where the data is. A client
> that had to know them would be a client you redeploy when somebody adds a column.

Runnable versions: [`java/CardVelocityExample.java`](java/CardVelocityExample.java) and
[`python/run.py`](python/run.py).

## Step 5 — stream authorisations in

Load some data that tells a story: `c-1002` goes on a spree, everyone else behaves.

```bash
python3 data/generate_auths.py --cards c-1001,c-1002,c-1003 --seconds 120 | \
  docker exec -i pravaha-aerospike aql
```

Or paste a few by hand to see the shape:

```sql
INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time)
  VALUES ('a-1', 'a-1', 'c-1002', 'm-91', 4500,  5812, 'APPROVED', 1767225600000000000);
INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time)
  VALUES ('a-2', 'a-2', 'c-1002', 'm-92', 8900,  7995, 'APPROVED', 1767225615000000000);
INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time)
  VALUES ('a-3', 'a-3', 'c-1002', 'm-93', 12000, 7995, 'APPROVED', 1767225630000000000);
INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time)
  VALUES ('a-4', 'a-4', 'c-1001', 'm-11', 2300,  5411, 'APPROVED', 1767225640000000000);
INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time)
  VALUES ('a-5', 'a-5', 'c-1002', 'm-94', 30000, 7995, 'DECLINED', 1767225650000000000);
```

`a-5` is declined, so the `WHERE` drops it — in Aerospike, before it is sent.

> **Nothing appears yet, and one more authorisation finishes it.** The minute starting
> `1767225600` ends at `1767225660`, and it is published once the engine has read an authorisation
> whose `auth_time` is past that end plus the stream's `out-of-orderness` — ten seconds, so
> `1767225670` or later. The five rows above are all inside the minute, so the window is still
> open. Insert one past it and the answer appears within a scan interval:
> ```sql
> INSERT INTO test.auth (PK, auth_id, card_id, merchant_id, amount_minor, mcc, status, auth_time)
>   VALUES ('a-9', 'a-9', 'c-1003', 'm-70', 100, 5411, 'APPROVED', 1767225700000000000);
> ```
> ```bash
> pravaha query --url grpc://localhost:9090 \
>   --sql "SELECT card_id, auth_count, distinct_merchants FROM card_velocity"
> ```
> This is the single most confusing thing about event-time streaming the first time you meet it: the
> engine is not slow, and it is not waiting on a clock — it is waiting to be told, by the data, that
> the minute is over, because an answer published early is an answer it might have to retract.
>
> **What makes that true is `event-time: auth_time` in `conf/application.yaml`.** Take it out and
> the minute is not slow to close, it can never close: nothing reads `auth_time` as time, no
> watermark advances, and no authorisation however late would publish anything. That is why the
> engine now refuses to register this query against a stream that does not declare its event time,
> instead of running it for ever with an empty view and a healthy status.

## Step 6 — ask questions

### One card, by parameter

[`sql/02-read-one-card.sql`](sql/02-read-one-card.sql):

```sql
SELECT card_id, risk_band, auth_count, total_minor, distinct_merchants
FROM card_velocity
WHERE card_id = ?
```

Python:

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    for row in client.query(open("sql/02-read-one-card.sql").read(), ["c-1002"]):
        print(row["card_id"], row["auth_count"], row["distinct_merchants"])
```

Java:

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090");
        QueryResult result = client.query(sql, "c-1002")) {
    for (Row row : result) {
        System.out.println(row.getString("card_id") + " " + row.getLong("auth_count"));
    }
}
```

**Bind the card id; never build the string.** A bound value is never parsed as SQL — by the time it
reaches the server the statement is already planned — and the server plans a parameterised statement
once and reuses it, so a thousand cards are one query rather than a thousand.

### Which cards look like a spree

[`sql/04-read-velocity-breaches.sql`](sql/04-read-velocity-breaches.sql):

```sql
SELECT card_id, auth_count, distinct_merchants
FROM card_velocity
WHERE auth_count >= ? AND distinct_merchants >= ?
```

```python
hot = list(client.query(open("sql/04-read-velocity-breaches.sql").read(), [3, 3]))
```

### Exposure by risk band

[`sql/03-read-exposure-by-band.sql`](sql/03-read-exposure-by-band.sql):

```sql
SELECT risk_band, COUNT(*) AS cards, SUM(total_minor) AS exposure_minor
FROM card_velocity
GROUP BY risk_band
```

A keyed `GROUP BY` with no window — refused in a continuous query and fine here, because a read of a
view scans a finite set of rows and stops.

## Step 7 — update data and watch it follow

Change a cardholder's risk band and the *next* window reflects it, while windows already closed keep
the band that was true when they closed:

```bash
docker exec -it pravaha-aerospike aql
```
```sql
UPDATE test.holder SET risk_band = 'HIGH' WHERE PK = 'c-1001';
```

That is `FOR SYSTEM_TIME AS OF` doing its job. A conventional cache would have rewritten history the
moment the row changed.

## Making this yours

| To change | Do this |
|---|---|
| The window | `INTERVAL '1' MINUTE` → `'5' MINUTE`. Longer windows mean more state and later answers |
| Overlapping windows | Swap `TUMBLE` for `HOP` — see the [trading study](../trading-order-flow/) |
| The key | `GROUP BY ... a.card_id` → `a.merchant_id`, and change the view's key column |
| More reference data | Add another `LEFT JOIN ... FOR SYSTEM_TIME AS OF` |
| A different store | Change the plugin configuration. The SQL does not move |

## Limits you will meet

Stated so you do not find them in a demo. The complete list is
[`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md).

- **No `CASE`.** You cannot write `SUM(CASE WHEN status = 'DECLINED' THEN 1 ELSE 0 END)`. Register a
  second query filtered to declines — see the [trading study](../trading-order-flow/), which needs
  exactly this.
- **No `ORDER BY` or `LIMIT`.** "Top ten hottest cards" is sorted by your application, over a result
  the engine has already narrowed with a `WHERE`.
- **No `LIKE`**, and no scalar or string functions in a projection.
- **`AVG` over integers truncates**, as SQL says. Sum and divide yourself if you need otherwise.
- **An unwindowed `GROUP BY` over the stream is refused** (`PRV-2050`). That is the feature described
  in Step 4, not a limitation to work around.
