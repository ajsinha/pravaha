# Case studies

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Five worked systems, each one a template you can copy into a real application. Every study has a
business problem, a data model, the commands to stand up its store, data to load, the continuous
query, and the SQL an application uses to read the answers — in **both Java and Python**.

**Read [`SETUP.md`](SETUP.md) first.** It covers Java, Docker, the two stores and the Python
virtualenv, and it explains the one Aerospike networking flag that otherwise costs people an
afternoon.

| Study | Domain | Store | Shows |
|---|---|---|---|
| [Trade processing](trade-processing/) | Trading | Aerospike | **Start here.** No aggregation at all: a pass-through feed, many differently-filtered subscribers on one computation, and SQL over the same data |
| [Card authorisation velocity](banking-card-velocity/) | Banking | Aerospike | Tumbling windows, temporal lookup join, filter pushdown, `COUNT(DISTINCT)` |
| [Intraday counterparty exposure](finance-counterparty-exposure/) | Finance | PostgreSQL | A relational source, incremental polling, money as minor units, value-time vs insert-time |
| [Order flow surveillance](trading-order-flow/) | Trading | Aerospike | **Hopping** windows, and what to do when you want `CASE` and cannot have it |
| [Sequencing run QC](biology-sequencing-qc/) | Biology | Aerospike | The same engine on a non-financial domain; integer `AVG`; breadth as well as depth |

## What they have in common

Every one is driven through the **published SDK** — the same Java and Python clients an application
uses — or from the `pravaha` command line. None of them reaches into the engine. That matters for a
template: the client in these studies holds no schemas, no plugins and no engine, so copying one
into your application does not drag the engine in with it.

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
pravaha drop      --name card_velocity
```

They also share a shape that is worth copying: a high-volume **stream**, a slow-moving **lookup
table** joined with `FOR SYSTEM_TIME AS OF`, a **window** to bound the state, and a `WHERE` that gets
**pushed into the store** so filtered rows never cross the network.

## Every statement here is checked by the build

The SQL in these studies is not illustrative. Each study declares its streams in
`schema/streams.properties`, and
[`CaseStudySqlTest`](../../pravaha-it/src/test/java/com/ash/messaging/pravaha/it/CaseStudySqlTest.java)
plans every `.sql` file against the real engine, runs the read queries through the same path a client
uses, and asserts that each README quotes the file rather than a retyping of it.

That matters more than it sounds. A case study is a template somebody will paste into production, so
the worst thing it can contain is SQL that reads plausibly and the engine refuses — and `ORDER BY`,
`LIKE`, `CASE` and an unwindowed `GROUP BY` all look unremarkable and are all rejected. Writing these
pages caught one immediately: `reads` is a reserved word, and `COUNT(*) AS reads` would have shipped
broken.

## What you will not find here

Stated up front so you can decide before investing an afternoon. The complete list is
[`docs/SQL_SUPPORT.md`](../../docs/SQL_SUPPORT.md).

- **No `CASE`**, so conditional aggregation is two registrations. The trading study needs exactly
  this and shows the shape.
- **No `ORDER BY` or `LIMIT`.** "Top ten" is sorted by your application over a narrowed result.
- **No outer joins between two streams**, and no self-joins. A lookup join —
  `LEFT JOIN … FOR SYSTEM_TIME AS OF` — is supported and is what the studies with a dimension table use.
- **No unwindowed keyed `GROUP BY` over a stream.** It is refused, deliberately, because its state
  would grow with the number of distinct keys forever. Over a *view* it works, and the studies use it
  for their summary queries.
