# Order revenue into Iceberg — a lakehouse case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** none to install — a CSV file in, an Apache Iceberg table out · **Time to first result:**
about ten minutes · **Shows:** `iceberg-sink` in upsert mode, a continuous aggregate written to a
table, late data correcting a row already written, exactly once per checkpoint

## The problem

A web shop's analysts live in the lakehouse: Spark notebooks, Trino dashboards, a finance model that
reads Iceberg tables. The number they ask for most is revenue per region per hour, and today a batch
job rebuilds it every night from the raw order lines. By mid-morning the analysts are looking at
yesterday.

Streaming it into the lake is the obvious fix, and the obvious way to do it is wrong: append each
hour's total when the hour ends, and a late order line — a web server's upload held in a retry queue
for ten minutes — either vanishes or arrives as a *second* row for the same hour. Every downstream
`SUM` is now wrong, silently. What the analysts need is a table that holds **one row per region per
hour, corrected in place** when a late line lands, and never half a checkpoint.

## What you will build

```
  data/order_lines.csv        Pravaha                                 the lake
 ┌───────────────────────┐   ┌──────────────────────────────────┐   ┌──────────────────────────────┐
 │ one line per order    │   │ hourly_revenue   (1-hour window) ─┼─► │ data/lake/hourly_revenue     │
 │ line, appended by the │─► │                                  │   │ Iceberg v2, upsert by        │
 │ web tier              │   │ category_revenue (1-hour window) │   │ (window_end, region)         │
 └───────────────────────┘   └──────────────────────────────────┘   └──────────────────────────────┘
                                   ▲ read with SQL, subscribe            ▲ Spark, Trino, your tools
```

Two continuous aggregates over one stream; the first also maintains an Iceberg table. Nothing to
install but the node: `iceberg-sink` is iceberg-core and iceberg-parquet, not Spark, and the table
is a directory whose metadata files are its catalog — no metastore, no catalog service.

## The data model

One stream, `order_line`:

| Column | Type | Meaning |
|---|---|---|
| `order_id` | STRING | The order |
| `customer_id` | STRING | Who placed it |
| `region` | STRING | `EU`, `UK` or `US` |
| `category` | STRING | `books`, `garden`, `toys`, `games` |
| `amount_minor` | INT64 | The line's value in minor units: `1999` is 19.99 |
| `ordered_at` | TIMESTAMP | When it was placed — **the stream's event time** |

The Iceberg table, `data/lake/hourly_revenue`, has the columns of `hourly_revenue`:
`window_end TIMESTAMP, region STRING, orders INT64, revenue_minor INT64, customers INT64`, format
version 2, with `window_end` and `region` as its identifier fields.

Checked by the build from [`schema/streams.properties`](schema/streams.properties); the node reads
[`conf/application.yaml`](conf/application.yaml), which declares `event-time: ordered_at`, a minute
of out-of-orderness and **fifteen minutes of allowed lateness**, and binds the sink.

## Step 1 — generate a morning

From this directory:

```bash
python3 data/generate_orders.py > data/order_lines.csv
```

10:00 to 12:00 UTC on a Monday: an order line every five minutes, twenty-four in all, regions and
categories in turn. The last line, at 12:10, is in the next hour; less a minute of out-of-orderness
it is past the end of both hours, so it closes them. The output is identical on every run.

## Step 2 — start the node

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

```text
stream order_line: event-time=ordered_at, out-of-orderness=PT1M, allowed-lateness=PT15M
```

The sink's table does not exist yet. `create` (on by default) makes it on first write, at format
version 2 with the key columns as identifier fields; an existing table whose columns, types or format
version differ from the binding is refused (`PRV-5141`) rather than written into.

## Step 3 — the continuous queries

### Revenue per region per hour — and into Iceberg

[`sql/01-continuous-hourly-revenue.sql`](sql/01-continuous-hourly-revenue.sql):

```sql
SELECT STREAM
  TUMBLE_END(ordered_at, INTERVAL '1' HOUR) AS window_end,
  region,
  COUNT(*)                    AS orders,
  SUM(amount_minor)           AS revenue_minor,
  COUNT(DISTINCT customer_id) AS customers
FROM order_line
GROUP BY TUMBLE(ordered_at, INTERVAL '1' HOUR), region
```

Keyed by `(window_end, region)` — `[0, 1]` — and registered **writing to** `hourly_revenue_table`.
The SQL does not mention Iceberg: where an answer goes is the registration's business, so the same
query could feed a view, a Kafka topic or a table without changing a word.

The sink's options are the part worth reading:

```yaml
  sinks:
    hourly_revenue_table:
      plugin: iceberg-sink
      options:
        path: ./data/lake/hourly_revenue
        schema: "window_end:TIMESTAMP,region:STRING,orders:INT64,revenue_minor:INT64,customers:INT64"
        mode: upsert
        key.columns: "window_end,region"
        transactional: "true"
```

**`mode: upsert`** keeps the table equal to the view, by key. The changes of one checkpoint are
collapsed by `(window_end, region)` — the last change to a key decides — and one Iceberg commit
writes an **equality delete** file naming every key it touched plus one data file of the rows that
survive. Iceberg applies an equality delete only to data older than it, so a key is replaced without
reading or rewriting the table: a commit costs what changed, not what the table holds.
**`key.columns` must be the key the query is registered with**, or two rows the view keeps apart
would be one row in the table.

**`transactional: "true"`** makes each checkpoint one Iceberg snapshot. The files are staged in the
table directory, invisible until the commit names them; the snapshot's summary records the
checkpoint, and a restore that repeats a commit the table already has changes nothing. So the table
is exactly once, and a reader never sees half a checkpoint.

### Revenue per category per hour

[`sql/02-continuous-category-revenue.sql`](sql/02-continuous-category-revenue.sql):

```sql
SELECT STREAM
  TUMBLE_END(ordered_at, INTERVAL '1' HOUR) AS window_end,
  category,
  COUNT(*)          AS lines,
  SUM(amount_minor) AS revenue_minor
FROM order_line
GROUP BY TUMBLE(ordered_at, INTERVAL '1' HOUR), category
```

A second aggregate, served as a view only. Not everything needs to be in the lake: the
merchandising dashboard reads this one over Flight SQL.

### Register them

```bash
python3 python/run.py --url grpc://localhost:19090
```

[`python/run.py`](python/run.py) registers both — `client.register(name, sql, keys, sink=...)` —
waits for the hours to close and reads the views. Java:
[`java/LakehouseOrdersExample.java`](java/LakehouseOrdersExample.java). From a shell:
`pravaha register --name hourly_revenue --sql-file sql/01-continuous-hourly-revenue.sql --keys 0,1
--sink hourly_revenue_table`.

## Step 4 — ask questions

The answers below are worked out from the generated morning, and the build checks them:
[`CaseStudyRunTest`](../../../pravaha-it/src/test/java/com/ash/messaging/pravaha/it/CaseStudyRunTest.java)
runs both queries over the same rows and compares. The UK figures are after Step 5's late line.

### One region, hour by hour

[`sql/04-read-one-region.sql`](sql/04-read-one-region.sql):

```sql
SELECT window_end, orders, revenue_minor, customers
FROM hourly_revenue
WHERE region = ?
```

Bound to `EU`:

```text
-- hourly_revenue for EU
   {'window_end': '2026-04-20T11:00:00+00:00', 'orders': 4, 'revenue_minor': 10678, 'customers': 4}
   {'window_end': '2026-04-20T12:00:00+00:00', 'orders': 4, 'revenue_minor': 28486, 'customers': 4}
```

The Iceberg table holds the same six rows, two per region, and nothing else. The 12:10 line's hour
is still open, so it is in neither.

### The morning, per region

[`sql/03-read-region-totals.sql`](sql/03-read-region-totals.sql):

```sql
SELECT region, SUM(orders) AS orders, SUM(revenue_minor) AS revenue_minor
FROM hourly_revenue
GROUP BY region
```

```text
-- the morning, per region
   {'region': 'EU', 'orders': 8, 'revenue_minor': 39164}
   {'region': 'UK', 'orders': 9, 'revenue_minor': 46332}
   {'region': 'US', 'orders': 8, 'revenue_minor': 45100}
```

### The morning, per category

[`sql/05-read-category-mix.sql`](sql/05-read-category-mix.sql):

```sql
SELECT category, SUM(lines) AS lines, SUM(revenue_minor) AS revenue_minor
FROM category_revenue
GROUP BY category
```

```text
-- the morning, per category
   {'category': 'books', 'lines': 6, 'revenue_minor': 28260}
   {'category': 'garden', 'lines': 6, 'revenue_minor': 30486}
   {'category': 'toys', 'lines': 7, 'revenue_minor': 36912}
   {'category': 'games', 'lines': 6, 'revenue_minor': 34938}
```

## Step 5 — a late line, corrected in the table

```bash
python3 python/run.py --url grpc://localhost:19090 --watch
```

In another terminal, append what the warehouse system uploads late — a UK toys line for 11:50,
held in a retry queue — and a line at 12:20 that moves event time on:

```bash
python3 data/generate_orders.py --phase late >> data/order_lines.csv
```

The 11:00-12:00 hour closed when the 12:10 line was read. Fifteen minutes of allowed lateness keep it
correctable until event time reaches 12:15, so the 11:50 line is not dropped: it corrects the hour,
and the correction is published when the 12:20 line next moves event time:

```text
-- commit
   -1 {'window_end': '2026-04-20T12:00:00+00:00', 'region': 'UK', 'orders': 4, 'revenue_minor': 29970, 'customers': 4}
   +1 {'window_end': '2026-04-20T12:00:00+00:00', 'region': 'UK', 'orders': 5, 'revenue_minor': 34170, 'customers': 4}
```

The sink receives that same commit. In upsert mode the `-1`/`+1` pair for one key collapses to its
last change, so the next Iceberg snapshot holds an equality delete for `(12:00, UK)` and a data file
with the new row. A reader of the table sees 5 orders and 34170 — **one row for the hour, not two**.
`customers` stays 4: the late line's customer, `c-5`, had already ordered in that hour.

An append-only sink could not have done this. Neither can `mode: changelog`, which is for when you
want the history: it appends every change with `_op` and the Z-set weight in `_weight`.

## Reading the table

The table is ordinary Iceberg v2 in `data/lake/hourly_revenue`, readable by anything that can read a
`HadoopTables` location. In Spark:

```text
spark.read.format("iceberg").load("/path/to/study/data/lake/hourly_revenue")
```

**Your reader must apply equality deletes.** Spark and Trino do. A reader that ignores delete files
shows every version of a corrected row, which is the double-counting this study exists to prevent.
The table's own engine compacts delete files away; `iceberg-sink` never compacts or expires
snapshots, and must not have its newest snapshot expired from under it, because that snapshot holds
its commit record.

## Making this yours

| To change | Do this |
|---|---|
| Daily rather than hourly | `INTERVAL '1' HOUR` → `'1' DAY`, and `window_end` stays the key |
| Longer correction | `allowed-lateness`. More is more state held and later finality in the table |
| Every change, not the latest | `mode: changelog` — appends with `_op` and `_weight`, no key needed |
| A second table | Another `sinks:` entry, and register the other query writing to it |
| A real feed | Kafka instead of the CSV file; the SQL and the sink do not move |

## Pitfalls

- **Local filesystem only.** `path` is a directory; `s3://` and other object stores are refused
  (`PRV-5140`), as are catalog services. Partitioned tables and schema evolution are not built.
- **The key is the table's key.** Register the query with a key that differs from `key.columns` and
  rows the view keeps apart collapse into one in the table.
- **Late beyond the lateness is dropped.** A line for 11:50 read once event time is past 12:15 never
  reaches the view or the table. Size `allowed-lateness` to how late your uploads really run.
- **Timestamps must be whole microseconds** (`PRV-5142`): Iceberg stores microseconds, and the sink
  refuses to round a nanosecond value rather than change it.
- **A windowed answer is not append-only.** That is why this is upsert: with allowed lateness, a
  closed hour can still change.

The connector's full description is the Iceberg section of
[`docs/CONNECTORS.md`](../../../docs/CONNECTORS.md); what runs and what is refused is
[`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md).
