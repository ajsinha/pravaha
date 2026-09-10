# Examples

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

Each directory is self-contained and runnable. The commands in every `README.md` here are executed
by `pravaha-it`'s `ExamplesTest`, so an example that stops working fails the build rather than
quietly misleading the next person who tries it.

| | |
|---|---|
| [01 — filter and project](01-filter-and-project/) | The smallest useful query, end to end |
| [02 — aggregate](02-aggregate/) | A global aggregate, and a keyed one the engine refuses |
| [03 — embedded Java](03-embedded-java/) | The engine inside an ordinary application |

Start with [`../docs/QUICKSTART.md`](../docs/QUICKSTART.md).

## Case studies

The examples on this page are deliberately tiny — one idea each, a CSV and a command. When you want
a **worked system** instead, with a store to stand up, a data model, a continuous query and the
application code that reads it, go to [`case-studies/`](case-studies/):

| Study | Domain | Store |
|---|---|---|
| [Card authorisation velocity](case-studies/banking-card-velocity/) | Banking | Aerospike |
| [Intraday counterparty exposure](case-studies/finance-counterparty-exposure/) | Finance | PostgreSQL |
| [Order flow surveillance](case-studies/trading-order-flow/) | Trading | Aerospike |
| [Sequencing run QC](case-studies/biology-sequencing-qc/) | Biology | Aerospike |

Each is a template meant to be copied, in both Java and Python, and every SQL statement in them is
planned against the real engine by the build.
