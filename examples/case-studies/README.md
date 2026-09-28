# Case studies

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Thirteen worked systems, each one a template you can copy into a real application. Every study has a
business problem, a data model, the data to load, the continuous queries, and the SQL an application
uses to read the answers — in **both Java and Python**.

New to Pravaha? The four lessons in [`docs/tutorials/`](../../docs/tutorials/) teach the product
itself — registering, following, replacing and debugging a query — against a fresh install's
demonstration stream. These studies assume you have seen that much.

| Study | Domain | Store | Shows |
|---|---|---|---|
| [Trade processing](trade-processing/) | Trading | Aerospike | **Start here.** No aggregation at all: a pass-through feed, many differently-filtered subscribers on one computation, and SQL over the same data |
| [Card authorisation velocity](banking-card-velocity/) | Banking | Aerospike | Tumbling windows, temporal lookup join, filter pushdown, `COUNT(DISTINCT)` |
| [Intraday counterparty exposure](finance-counterparty-exposure/) | Finance | PostgreSQL | A relational source, incremental polling, money as minor units, value-time vs insert-time |
| [Order flow surveillance](trading-order-flow/) | Trading | Aerospike | **Hopping** windows, and what to do when you want `CASE` and cannot have it |
| [Sequencing run QC](biology-sequencing-qc/) | Biology | Aerospike | The same engine on a non-financial domain; integer `AVG`; breadth as well as depth |
| [Machine sensor anomalies](manufacturing-sensor-anomalies/) | Manufacturing / IoT | none (CSV) | Tumbling windows, a filter writing to a **sink**, **late data**: allowed lateness and the correction it publishes |
| [Checkout funnel](ecommerce-checkout-funnel/) | E-commerce | none (CSV) | Windows over several keys, `COUNT(DISTINCT)`, `HAVING` as an alert, conversion without `CASE` |
| [Click attribution](adtech-click-attribution/) | Advertising | none (CSV) | A **join between two streams** with a time bound, a window over a join, joining two views in the reader |
| [Call-detail-record fraud](telecom-cdr-fraud/) | Telecommunications | none (CSV) | **Sliding** windows, an `IN` filter, a maintained **top-N** (`ROW_NUMBER … rn <= 3`) |
| [Delivery SLA breaches](logistics-delivery-sla/) | Logistics | none (CSV) | An interval join to a **sink**, and a **`LEFT JOIN` with a time bound** that reports what did not happen |
| [Stock levels from MySQL](retail-inventory-mysql/) | Retail | MySQL (binlog) | **Change data capture** with `mysql-cdc`: an update is `-1` old and `+1` new, so a low-stock alert clears itself |
| [Order revenue into Iceberg](lakehouse-orders-iceberg/) | E-commerce / lakehouse | none (CSV) → Iceberg | A continuous aggregate maintained in an **Apache Iceberg table** by `iceberg-sink` in upsert mode; a late row corrects the table in place |
| [Many desks, one topic](payments-shared-kafka/) | Payments | Kafka | Several queries over one topic sharing **one reader**, exactly once; an **equality index** on a column outside the key, `INDEX (merchant)` |

**The first five need a store.** Read [`SETUP.md`](SETUP.md) first: it covers Java, Docker, the two
stores and the Python virtualenv, and it explains the one Aerospike networking flag that otherwise
costs people an afternoon.

**The last five need nothing but the node.** Their sources are CSV files the `filesystem` plugin
follows as they grow, and their sinks are CSV files it appends to — both inside the server jar. Each
generates its data with a script, starts a node from its own directory, and prints what the README
shows: every output in those five READMEs was taken from a real run.

**The last three show the newest connectors.** The MySQL and Kafka studies need a store —
[`SETUP.md`](SETUP.md) starts both — and the Iceberg study needs nothing but the node: a CSV file in,
an Iceberg table in a directory out. Their outputs are worked out by hand from the data their
generators write, and the build runs their queries over that data and checks every one.

## What they have in common

Every one is driven through the **published SDK** — the same Java and Python clients an application
uses — or from the `pravaha` command line. None of them reaches into the engine. That matters for a
template: the client in these studies holds no schemas, no plugins and no engine, so copying one
into your application does not drag the engine in with it.

Each also ships the node's own configuration as `conf/application.yaml` — what its streams are,
where their rows come from, and **which column is each stream's event time**. That last key is not
optional decoration: without it no watermark advances over the stream, so no window a query opens
could ever close, and the engine refuses to register such a query rather than run it for ever with
an empty view. [`SETUP.md`](SETUP.md) explains the file once.

All of them are the same three moves, which is the point of having several:

1. **Register a continuous query.** It runs until dropped, maintaining a named view.
2. **Let data arrive.** The view stays current. There is no job, no cache and no second store.
3. **Read the view with ordinary SQL**, binding parameters rather than building strings — or
   **subscribe** and have changes pushed as they are committed, filtered at the tap so rows you did
   not ask for never cross the network.

Each shows all three in Java, in Python, and from the shell:

```bash
pravaha register  --name card_velocity --sql-file sql/01-continuous-card-velocity.sql --keys 1
pravaha queries
pravaha query     --sql "SELECT card_id, auth_count FROM card_velocity WHERE card_id = ?" --params c-1002
pravaha subscribe --view card_velocity --filter risk_band=HIGH
pravaha drop      --name card_velocity --yes
```

The store-backed studies share a shape worth copying: a high-volume **stream**, a slow-moving
**lookup table** joined with `FOR SYSTEM_TIME AS OF`, a **window** to bound the state, and a `WHERE`
that gets **pushed into the store** so filtered rows never cross the network. The file-backed ones
add the other half: **two streams joined to each other** with a time bound, what happens to a
**late** row, a **top-N**, and answers written to a **sink**.

## Every statement here is checked by the build

The SQL in these studies is not illustrative. Each study declares its streams in
`schema/streams.properties`, and
[`CaseStudySqlTest`](../../pravaha-it/src/test/java/com/ash/messaging/pravaha/it/CaseStudySqlTest.java)
plans every `.sql` file against the real engine, runs the read queries through the same path a client
uses, and asserts that each README quotes the file rather than a retyping of it.

Planning is not the same as being right, so every study also **runs**.
[`CaseStudyRunTest`](../../pravaha-it/src/test/java/com/ash/messaging/pravaha/it/CaseStudyRunTest.java)
starts an embedded engine — no server, no store, no Docker — registers each study's continuous
queries, pushes in the rows of its `data/sample/`, and compares every read query's answer with
`data/sample/answers.txt`, the answers worked out by hand. Where a study's source needs a store
(Aerospike, PostgreSQL, MySQL, Kafka) the same rows are pushed straight into the stream, a change-data
capture update as its `-1` and its `+1`, and dimension tables are served from an in-memory database
through the `jdbc-lookup` plugin: what is proved is that the SQL gives the right answers to the rows
the source would deliver. The test lists the directories here, so a study without a sample fails it.
Running it found wrong view keys in four studies, and a defect in the engine's windowing that a
filtered windowed query would have met on its first row; both are fixed.

That matters more than it sounds. A case study is a template somebody will paste into production, so
the worst thing it can contain is SQL that reads plausibly and the engine refuses — and `ORDER BY`,
`LIKE`, `CASE` and an unwindowed `GROUP BY` all look unremarkable and are all rejected. Writing these
pages caught one immediately: `reads` is a reserved word, and `COUNT(*) AS reads` would have shipped
broken.

## What you will not find here

Stated up front so you can decide before investing an afternoon. The complete list is
[`docs/CONTINUOUS_QUERIES.md`](../../docs/CONTINUOUS_QUERIES.md).

- **No `CASE`**, so conditional aggregation is two registrations, or rows the reader divides. The
  trading and checkout-funnel studies show both shapes.
- **No `ORDER BY` or `LIMIT` over a stream.** A maintained **top-N** —
  `ROW_NUMBER() OVER (PARTITION BY … ORDER BY …) … WHERE rn <= N` — is supported, and the telecom
  study uses it; anything else is sorted by your application.
- **No `RIGHT` or `FULL` outer join, and no join between two streams without a time bound.** A
  `LEFT JOIN` between streams *with* a time bound runs (the logistics study), and so does a lookup
  join — `LEFT JOIN … FOR SYSTEM_TIME AS OF` — which is what the studies with a dimension table use.
- **No join of two views in one read** (`PRV-4025`). A read names one view;
  the click-attribution study joins two in the reader, on a key they share.
- **No unwindowed keyed `GROUP BY` over a stream.** It is refused, deliberately, because its state
  would grow with the number of distinct keys forever. Over a *view* it works, and the studies use it
  for their summary queries.
