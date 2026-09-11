# Trade processing — the simplest useful case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** Aerospike · **Time to first result:** about ten minutes · **Setup:** [`../SETUP.md`](../SETUP.md)

## The problem

Trades arrive all day from half a dozen source systems. A dozen teams want them: the rates desk wants
swaps, the equities desk wants equities, risk wants everything from one particular booking system,
and settlement wants one trade by id right now.

The usual answer is a topic per consumer, or a shared topic every consumer reads in full and filters
in their own process. The first multiplies the pipeline by the number of teams; the second makes
every consumer pay for every other consumer's data.

This study is the third answer: **one registration, many filtered taps**. The trades are read from
the store once. Each consumer subscribes with its own filter and sees only its slice. And the same
data is queryable by ordinary SQL for the "give me that one trade" question.

It is also the simplest case study here, because the continuous query does not aggregate at all.
That turns out to be exactly why the filtering is free — see [below](#why-the-filters-are-free).

## The data model

One Aerospike set. No reference table, no join.

### `trade`

| Bin | Type | Meaning |
|---|---|---|
| `trade_event_id` | integer | Monotonic per event. **The key** — a trade amended twice produces three events |
| `trade_id` | string | The trade. Several events share one |
| `product_type` | string | `SWAP`, `EQUITY`, `FX`, `BOND` — **a filter column** |
| `source_system` | string | `MUREX`, `CALYPSO`, `INHOUSE` — **a filter column** |
| `trade_time` | integer | Event time, epoch nanoseconds |
| `trade_json` | string | The trade itself, as your source system emits it |

> **Promote what you filter on; keep the rest as JSON.** `trade_json` is an opaque string to the
> engine — there are no JSON functions, so you cannot filter on a field inside it. That is the single
> most important design decision in this study. Anything a consumer will ever filter or group by has
> to be its own column, lifted out at ingest. Everything else rides along in the payload, costs
> nothing to carry, and is parsed by whoever actually needs it.
>
> Getting this wrong is cheap to fix early and expensive later: adding a column means re-ingesting.
> Err towards promoting one or two more than you think you need.

> **Keyed by `trade_event_id`, not `trade_id`.** An amendment is a new event, and keying on the trade
> would make the amendment overwrite the original — so the audit trail, which is the thing a trade
> store exists for, would quietly disappear.

Machine-readable and build-checked: [`schema/streams.properties`](schema/streams.properties).

## Step 1 — start Aerospike

```bash
docker run -d --name pravaha-aerospike --network host aerospike/aerospike-server:latest
docker exec pravaha-aerospike asinfo -v status   # expect: ok
```

Read the `--network host` note in [`../SETUP.md`](../SETUP.md) before skipping it; it is the one
thing that reliably costs people an afternoon.

## Step 2 — the continuous query

The whole thing, from [`sql/01-continuous-trade-feed.sql`](sql/01-continuous-trade-feed.sql):

```sql
SELECT STREAM
  t.trade_event_id,
  t.trade_id,
  t.product_type,
  t.source_system,
  t.trade_time,
  t.trade_json
FROM trade AS t
```

No window. No `GROUP BY`. No join. It is a pass-through, and that is deliberate.

**Why register a pass-through at all,** rather than letting each consumer read Aerospike directly?

- The store is read **once**, however many consumers there are.
- The result is a **served view**, so "give me trade T" is a hash probe rather than a scan.
- Consumers **filter at the tap**, and that filtering costs nothing (next section).

Register it:

```java
QueryRegistry registry = new QueryRegistry(views, tradeSchema());
RegisteredQuery feed = registry.register(
        "trade_feed",
        Files.readString(Path.of("sql/01-continuous-trade-feed.sql")),
        List.of(0),                       // key the view by trade_event_id
        principal);
```

> **This view grows with the number of trades**, because nothing aggregates them away. That is fine
> for an intraday feed and is not fine forever. `ServedView` takes a key ceiling and refuses rather
> than exhausting memory; size it for a day's events and drop the registration at end of day. A view
> that grows without a bound is the one mistake this engine refuses to let you make quietly, and a
> pass-through query is where you have to make that decision yourself.

## Step 3 — load some trades

```bash
docker exec -it pravaha-aerospike aql
```
```sql
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, trade_json)
  VALUES (1, 1, 'T-1001', 'SWAP',   'MUREX',   1767225600000000000, '{"notional":5000000,"ccy":"GBP"}');
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, trade_json)
  VALUES (2, 2, 'T-1002', 'EQUITY', 'CALYPSO', 1767225601000000000, '{"qty":1200,"sym":"VOD.L"}');
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, trade_json)
  VALUES (3, 3, 'T-1003', 'SWAP',   'CALYPSO', 1767225602000000000, '{"notional":250000,"ccy":"USD"}');
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, trade_json)
  VALUES (4, 4, 'T-1001', 'SWAP',   'MUREX',   1767225603000000000, '{"notional":5500000,"ccy":"GBP","amend":1}');
```

Event 4 amends trade `T-1001`. Both events are in the feed, which is the point of keying on the
event.

For a continuous flow:

```bash
python3 data/generate_trades.py --seconds 120 --rate 5 | docker exec -i pravaha-aerospike aql
```

> **No window here, so nothing is waiting on a watermark.** Unlike the other case studies, rows
> appear as soon as they are committed. That is the other side of having no aggregation: nothing has
> to wait to be sure it is complete.

## Step 4 — stream, with your own filter

This is the part the study exists for. The rates desk wants swaps from Murex and nothing else:

```java
SubscriptionFilter swapsFromMurex = SubscriptionFilter.matching(
        feed.outputSchema(),
        Map.of("product_type", "SWAP", "source_system", "MUREX"));

try (Subscription subscription = feed.subscribe(
        SubscriptionOptions.DEFAULT,
        swapsFromMurex,
        changes -> changes.forEach(change ->
                System.out.println(change.values()[5])))) {   // trade_json
    // ... runs until closed
}
```

The equities desk subscribes to the **same registration** with a different filter:

```java
SubscriptionFilter equities = SubscriptionFilter.matching(
        feed.outputSchema(), "product_type", "EQUITY");
```

Both see only their own trades. There is still one computation and one read of Aerospike.

A filter naming a column the view does not have is **refused**, not ignored:

```java
SubscriptionFilter.matching(feed.outputSchema(), "prodcut_type", "SWAP");
// PRV-5002: this view has no column 'prodcut_type' ...
```

That refusal matters more than it looks. A typo that was quietly dropped would leave a desk receiving
**every** trade while believing it had asked for a slice — and nothing about the data would look
wrong.

### Overflow

A consumer that falls behind does not slow the feed down for anybody else:

```java
feed.subscribe(
        SubscriptionOptions.of(50_000, SubscriptionOptions.Overflow.FAIL),
        equities,
        this::onTrade);
```

For a trade feed, **`FAIL` is usually the right choice.** `CONFLATE` is built for a dashboard that
wants the latest value per key; a consumer that must see every event — settlement, reporting, an
audit trail — should be told it fell behind rather than quietly handed a feed with holes in it.

## Why the filters are free

A filter supplied from outside a query can be applied at the tap **only if the view carries every
column it names.** When a query aggregates a column away, the view's rows already mix the values the
filter was meant to separate, and nothing applied afterwards can unmix them — so the filter has to go
*into* the query, which means a separate computation with its own state, per filter.

This query aggregates nothing, so every column survives, so **every filter over it is tap-applicable**
and one computation serves everybody. Ten desks with ten different filters are one read of Aerospike
and one copy of the state.

That is the whole reason registration and subscription are separate things (ADR-025), and a
pass-through feed is where it is most visible. The rule itself is
[ADR-031](../../../docs/adr/031-authorization-at-the-pravaha-layer.md); the same rule decides how a
security row filter and a continuous-query parameter are handled.

## Step 5 — query it with SQL

Streaming is for consumers that want everything as it happens. For "give me that one trade", ask.

### By product and source

[`sql/02-read-by-product-and-source.sql`](sql/02-read-by-product-and-source.sql):

```sql
SELECT trade_event_id, trade_id, product_type, source_system, trade_time, trade_json
FROM trade_feed
WHERE product_type = ? AND source_system = ?
```

Python:

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    for row in client.query(open("sql/02-read-by-product-and-source.sql").read(), ["SWAP", "MUREX"]):
        print(row["trade_id"], row["trade_json"])
```

Java:

```java
try (QueryResult result = client.query(sql, "SWAP", "MUREX")) {
    for (Row row : result) {
        System.out.println(row.getString("trade_id") + " " + row.getString("trade_json"));
    }
}
```

The filters are **bound, not concatenated**. A bound value is never parsed as SQL, and the server
plans this statement once however many product types you ask about.

### One trade, all its events

[`sql/04-read-one-trade.sql`](sql/04-read-one-trade.sql):

```sql
SELECT trade_event_id, trade_id, source_system, trade_json
FROM trade_feed
WHERE trade_id = ?
```

```python
events = list(client.query(open("sql/04-read-one-trade.sql").read(), ["T-1001"]))
# two rows: the original and the amendment
```

### What is arriving, and from where

[`sql/03-read-counts-by-product.sql`](sql/03-read-counts-by-product.sql):

```sql
SELECT product_type, source_system, COUNT(*) AS trades
FROM trade_feed
GROUP BY product_type, source_system
```

A keyed `GROUP BY` with no window — refused in a continuous query, and fine here, because a read of a
view scans a finite set of rows and stops.

## Making this yours

| To change | Do this |
|---|---|
| Another filter column | Promote it out of `trade_json` into its own bin, add it to the schema and the `SELECT` |
| Only booked trades | Add `WHERE t.status = 'BOOKED'` — it gets pushed into Aerospike |
| Per-desk feeds | One registration, one `SubscriptionFilter` per desk. Not one query per desk |
| Counts per minute rather than totals | Add a `TUMBLE` window — and note that filters on columns it groups away stop being tap-applicable |
| A different store | Change the plugin configuration. The SQL does not move |

## Limits you will meet

Full list: [`docs/SQL_SUPPORT.md`](../../../docs/SQL_SUPPORT.md).

- **No JSON functions.** `trade_json` is an opaque string. Promote anything you filter on into a
  column. This is the limit that shapes the design, so it is first.
- **Tap filters are equality only.** Ranges and text matching belong in the registered query, where
  the planner can see the cost. `SubscriptionFilter` is a tap, not a query language.
- **No `ORDER BY` or `LIMIT`.** "The last fifty trades" is sorted by your application over a result
  narrowed by a `WHERE`.
- **The view grows with the feed.** Nothing aggregates, so nothing is released until the registration
  is dropped. Size the key ceiling for a day and drop at end of day.
- **No `CASE`**, no `LIKE`, no scalar functions in a projection.
