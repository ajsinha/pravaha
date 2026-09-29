# Case studies

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential.** A fixed fixture for the visual tests (ABOUTBASE-1).

| Study | Domain | Store | Shows |
|---|---|---|---|
| [Trade processing](trade-processing/) | Trading | Aerospike | **Start here.** A pass-through feed, many filtered subscribers on one computation, and SQL over the same data |
| [Card authorisation velocity](banking-card-velocity/) | Banking | Aerospike | Tumbling windows, a temporal lookup join, filter pushdown, `COUNT(DISTINCT)` |
| [Intraday counterparty exposure](finance-counterparty-exposure/) | Finance | PostgreSQL | A relational source, incremental polling, money as minor units |
| [Order flow surveillance](trading-order-flow/) | Trading | Aerospike | **Hopping** windows, and what to do when you want `CASE` and cannot have it |
