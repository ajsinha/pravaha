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
| `counterparty_id` | string | Who we traded with — **joins to `counterparty`** |
| `book_id` | string | Which trading book — **joins to `book`** |
| `trade_json` | string | The trade itself, as your source system emits it |

### `counterparty` — reference data

Slow-moving. One record per legal entity you trade with.

| Bin | Type | Meaning |
|---|---|---|
| `counterparty_id` | string | Primary key |
| `legal_name` | string | For screens and reports |
| `country` | string | ISO country of incorporation |
| `lei` | string | Legal Entity Identifier |

### `book` — reference data

| Bin | Type | Meaning |
|---|---|---|
| `book_id` | string | Primary key |
| `desk` | string | `RATES`, `EQUITIES`, `FX` |
| `region` | string | `EMEA`, `AMER`, `APAC` |
| `cost_centre` | string | For allocation |

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

Machine-readable and build-checked: [`schema/streams.properties`](schema/streams.properties). The
node's own copy, with `trade_time` declared as the stream's event time, is
[`conf/application.yaml`](conf/application.yaml).

## Step 1 — start Aerospike

```bash
docker run -d --name pravaha-aerospike --network host aerospike/aerospike-server:latest
docker exec pravaha-aerospike asinfo -v status   # expect: ok
```

Read the `--network host` note in [`../SETUP.md`](../SETUP.md) before skipping it; it is the one
thing that reliably costs people an afternoon.

## Step 2 — start the node

The server is described by [`conf/application.yaml`](conf/application.yaml), shipped with this
study. From this directory:

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
pravaha queries --url grpc://localhost:19090     # expect: no continuous queries are registered
```

The Aerospike connector ships inside the server jar, so there is nothing to build in — see
[`../SETUP.md`](../SETUP.md) for how to start the node and what the `dev` profile does.

Nothing here windows, so no view is waiting on a watermark — but the file still declares the
stream's event time:

```yaml
      event-time: trade_time
```

It earns that line twice. Retention is in event time and nothing else, so a view that keeps the
last hour of trades needs to know which column an hour is measured in; and
`FOR SYSTEM_TIME AS OF t.trade_time` asks each dimension table as of a trade's own time. Add a
`TUMBLE` later — the variation this study's last section suggests — and the same key is what lets
the window close at all, rather than leaving a query that reports `RUNNING` and never publishes.
The node states it at startup:

```text
stream trade: event-time=trade_time, out-of-orderness=PT10S, allowed-lateness=PT0S
```

## Step 3 — the continuous query

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
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:19090")) {
    RegisteredQueryInfo feed = client.register(
            "trade_feed",
            Files.readString(Path.of("sql/01-continuous-trade-feed.sql")),
            List.of(0));                  // key the view by trade_event_id
}
```

```bash
pravaha register --name trade_feed --sql-file sql/01-continuous-trade-feed.sql --keys 0
```

> **Nothing aggregates these rows away, so the view would grow with the feed — and it does not,
> because a retention policy applies whether or not you ask for one.** The default keeps a day, or a
> million rows, whichever binds first. Override it per registration:
>
> ```java
> registry.register("trade_feed", sql, List.of(0), principal, Retention.ofAge(Duration.ofHours(8)));
> ```
>
> or change the default for every registration on this node with `registry.retaining(...)`.
>
> Retention is in event time and nothing else. "Keep the last million rows" would make this view mean
> something different on a busy day than on a quiet one, and you could not say what it contained
> without knowing the volume. The row bound that does exist is the view's *capacity ceiling* — what
> the node can afford, not what the view means.
>
> **Eviction is forgetting, not retraction.** An evicted trade is not published to subscribers as a
> `-1` — it was not cancelled, it aged out of a cache. A consumer keeping its own copy from the
> change stream therefore keeps whatever *it* chose to keep and may legitimately hold more than the
> view does. Emitting retractions instead would tell every consumer the trade had been withdrawn,
> which would be a lie with consequences in this domain particularly.
>
> `view.evicted()` counts what has been forgotten. It is not an error count; it is how you notice a
> window shorter than the questions people are asking of it.

## Step 4 — load some trades

```bash
docker exec -it pravaha-aerospike aql
```
```sql
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, counterparty_id, book_id, trade_json)
  VALUES (1, 1, 'T-1001', 'SWAP',   'MUREX',   1767225600000000000, 'cp-1', 'bk-1', '{"notional":5000000,"ccy":"GBP"}');
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, counterparty_id, book_id, trade_json)
  VALUES (2, 2, 'T-1002', 'EQUITY', 'CALYPSO', 1767225601000000000, 'cp-2', 'bk-2', '{"qty":1200,"sym":"VOD.L"}');
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, counterparty_id, book_id, trade_json)
  VALUES (3, 3, 'T-1003', 'SWAP',   'CALYPSO', 1767225602000000000, 'cp-3', 'bk-1', '{"notional":250000,"ccy":"USD"}');
INSERT INTO test.trade (PK, trade_event_id, trade_id, product_type, source_system, trade_time, counterparty_id, book_id, trade_json)
  VALUES (4, 4, 'T-1001', 'SWAP',   'MUREX',   1767225603000000000, 'cp-1', 'bk-1', '{"notional":5500000,"ccy":"GBP","amend":1}');
```

Event 4 amends trade `T-1001`. Both events are in the feed, which is the point of keying on the
event.

And the reference data the enriched feed joins to:

```sql
INSERT INTO test.counterparty (PK, counterparty_id, legal_name, country, lei)
  VALUES ('cp-1', 'cp-1', 'ACME Clearing Ltd',    'GB', '213800AAAAAAAAAAAA01');
INSERT INTO test.counterparty (PK, counterparty_id, legal_name, country, lei)
  VALUES ('cp-2', 'cp-2', 'Borealis Bank NV',     'NL', '213800BBBBBBBBBBBB02');
INSERT INTO test.counterparty (PK, counterparty_id, legal_name, country, lei)
  VALUES ('cp-3', 'cp-3', 'Cygnus Securities SA', 'FR', '213800CCCCCCCCCCCC03');

INSERT INTO test.book (PK, book_id, desk, region, cost_centre)
  VALUES ('bk-1', 'bk-1', 'RATES',    'EMEA', 'CC-1100');
INSERT INTO test.book (PK, book_id, desk, region, cost_centre)
  VALUES ('bk-2', 'bk-2', 'EQUITIES', 'EMEA', 'CC-1200');
INSERT INTO test.book (PK, book_id, desk, region, cost_centre)
  VALUES ('bk-3', 'bk-3', 'FX',       'APAC', 'CC-1300');
```

For a continuous flow:

```bash
python3 data/generate_trades.py --seconds 120 --rate 5 | docker exec -i pravaha-aerospike aql
```

> **No window here, so nothing is waiting on a watermark.** Unlike the other case studies, rows
> appear as soon as they are committed. That is the other side of having no aggregation: nothing has
> to wait to be sure it is complete.

## Step 4b — the same feed, enriched

A trade id and a book id are not what a person reads. The desk wants the desk name; a regulatory
report wants the counterparty's legal name and country. Both live in reference tables, and joining
them is a second registration over the same stream — from
[`sql/05-continuous-enriched-trades.sql`](sql/05-continuous-enriched-trades.sql):

```sql
SELECT STREAM
  t.trade_event_id,
  t.trade_id,
  t.product_type,
  t.source_system,
  c.legal_name,
  c.country,
  b.desk,
  b.region,
  t.trade_time,
  t.trade_json
FROM trade AS t
LEFT JOIN counterparty FOR SYSTEM_TIME AS OF t.trade_time AS c
       ON t.counterparty_id = c.counterparty_id
LEFT JOIN book FOR SYSTEM_TIME AS OF t.trade_time AS b
       ON t.book_id = b.book_id
```

Two lookups, chained. Each trade is enriched from both tables as it passes; nothing is buffered and
no window is needed, because a lookup join asks a question of a table rather than waiting for a
matching event to arrive.

Three things worth understanding before you copy this:

- **`LEFT`, not inner, and it matters here more than anywhere.** Reference data arrives late — a new
  counterparty is often onboarded after its first trade. An inner join would make that trade
  *disappear from the feed entirely*, and the trade nobody can find is the one the regulator asks
  about. With `LEFT`, the trade flows through with `legal_name` and `country` null, which is
  visible, alarming and correct.
- **`FOR SYSTEM_TIME AS OF t.trade_time`** on both joins. The name and desk attached are the ones
  that were true when the trade happened, not when you looked. Re-run today's feed tomorrow and you
  get today's answer. A cached join would have rewritten history the moment somebody renamed a book.
- **It is a second registration, not a replacement.** `trade_feed` and `enriched_trade` are separate
  computations with separate state, because their plans differ. Consumers that only need the raw feed
  should stay on `trade_feed` and not pay for lookups they do not use.

```java
client.register("enriched_trade", Files.readString(Path.of("sql/05-continuous-enriched-trades.sql")), List.of(0));
```

## Step 5 — stream, with your own filter

This is the part the study exists for. The rates desk wants swaps from Murex and nothing else:

```java
try (Subscription rates = client.subscribe(
        "trade_feed",
        Map.of("product_type", "SWAP", "source_system", "MUREX"),
        batch -> batch.forEach(row -> System.out.println(row.getString("trade_json"))))) {
    rates.run();   // parks this thread until closed
}
```

Python:

```python
for batch in client.subscribe("trade_feed", {"product_type": "SWAP", "source_system": "MUREX"}):
    for row in batch:
        handle(row["trade_json"])
```

Or watch it from a shell:

```bash
pravaha subscribe --view trade_feed --filter product_type=SWAP,source_system=MUREX
```

The equities desk subscribes to the **same registration** with a different filter:

```java
client.subscribe("trade_feed", Map.of("product_type", "EQUITY"), this::onTrade);
```

Both see only their own trades. There is still one computation and one read of Aerospike.

A filter naming a column the view does not have is **refused**, not ignored:

```java
client.subscribe("trade_feed", Map.of("prodcut_type", "SWAP"), this::onTrade).run();
// PRV-5002: this view has no column 'prodcut_type'. Its columns are [...]
```

That refusal matters more than it looks. A typo that was quietly dropped would leave a desk receiving
**every** trade while believing it had asked for a slice — and nothing about the data would look
wrong.

### Filtering on a column that came from a join

The enriched feed aggregates nothing either, so **every joined column survives into the view and is
therefore filterable at the tap**. The EMEA rates desk can ask for its own trades without anyone
writing a query for it:

```java
try (Subscription desk = client.subscribe(
        "enriched_trade", Map.of("desk", "RATES", "region", "EMEA"), this::onTrade)) {
    desk.run();
}
```

`desk` and `region` are not in the trade record at all — they arrived from `book`. That they are
filterable is a consequence of the join being a *projection* rather than an aggregation: nothing was
collapsed, so nothing was lost.

Change the enriched query to count trades per desk per minute and this stops being true for any
column the `GROUP BY` drops. The rule does not change; what changes is whether the view still carries
the column.

### Overflow

A consumer that falls behind does not slow the feed down for anybody else:

The server bounds every subscriber's buffer and decides what happens when it fills — conflate, drop
the oldest, or fail. For a trade feed **fail is usually right**: conflation is built for a dashboard
that wants the latest value per key, and a consumer that must see every event — settlement,
reporting, an audit trail — should be told it fell behind rather than quietly handed a feed with
holes in it.

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
[ADR-031](../../../docs/design/adr/031-authorization-at-the-pravaha-layer.md); the same rule decides how a
security row filter and a continuous-query parameter are handled.

## Step 6 — query it with SQL

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

with connect("grpc://localhost:19090") as client:
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

### Everything a desk did

[`sql/06-read-enriched-by-desk.sql`](sql/06-read-enriched-by-desk.sql):

```sql
SELECT trade_id, product_type, legal_name, country, desk, trade_json
FROM enriched_trade
WHERE desk = ?
```

```python
for row in client.query(open("sql/06-read-enriched-by-desk.sql").read(), ["RATES"]):
    print(row["trade_id"], row["legal_name"], row["country"])
```

### A regulatory slice — one country, one product

[`sql/07-read-enriched-by-country-and-product.sql`](sql/07-read-enriched-by-country-and-product.sql):

```sql
SELECT trade_id, legal_name, country, desk, region, trade_json
FROM enriched_trade
WHERE country = ? AND product_type = ?
```

```java
try (QueryResult result = client.query(sql, "GB", "SWAP")) {
    for (Row row : result) {
        System.out.println(row.getString("legal_name") + " " + row.getString("trade_id"));
    }
}
```

### Where the volume is

[`sql/08-read-desk-totals.sql`](sql/08-read-desk-totals.sql):

```sql
SELECT region, desk, product_type, COUNT(*) AS trades
FROM enriched_trade
GROUP BY region, desk, product_type
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
| Another reference table | A third `LEFT JOIN … FOR SYSTEM_TIME AS OF`. They chain |
| A different store | Change the plugin configuration. The SQL does not move |

## Limits you will meet

Full list: [`docs/guides/CONTINUOUS_QUERIES.md`](../../../docs/guides/CONTINUOUS_QUERIES.md).

- **No JSON functions.** `trade_json` is an opaque string. Promote anything you filter on into a
  column. This is the limit that shapes the design, so it is first.
- **Tap filters are equality only.** Ranges and text matching belong in the registered query, where
  the planner can see the cost. `SubscriptionFilter` is a tap, not a query language.
- **No `ORDER BY` or `LIMIT`.** "The last fifty trades" is sorted by your application over a result
  narrowed by a `WHERE`.
- **Retention is a cache policy, not an archive.** The view keeps a day by default; the store the
  trades came from is where history lives. A query for last month goes to Aerospike, not here.
- **Only lookup joins and inner equi-joins.** `LEFT JOIN … FOR SYSTEM_TIME AS OF` is a lookup and is
  what both joins here are. A `LEFT JOIN` between two *streams* is refused, because an unmatched row
  would have to be held forever in case its partner turned up. So is a self-join.
- **A lookup join costs a lookup per trade per table**, cached by the plugin. Two joins is two
  caches. If a reference table is small and static, that is nothing; if it is large and changing, it
  is the thing to measure first.
- **No `CASE`**, no `LIKE`, no scalar functions in a projection.
