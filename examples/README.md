# Examples

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

Each directory is self-contained and runnable. The `run` and `validate` commands in
`01-filter-and-project` and `02-aggregate` are executed by `pravaha-it`'s `ExamplesTest`, which
also asserts that each of those READMEs still contains the output it quotes — so those fail the
build rather than quietly misleading the next person who tries them. The `explain` commands and
all of `03-embedded-java` are **not** covered (DOCX-049).

| | |
|---|---|
| [01 — filter and project](01-filter-and-project/) | The smallest useful query, end to end |
| [02 — aggregate](02-aggregate/) | A global aggregate, and a keyed one the engine refuses |
| [03 — embedded Java](03-embedded-java/) | The engine inside an ordinary application |

Start with [`../docs/guides/QUICKSTART.md`](../docs/guides/QUICKSTART.md).

## Case studies

The examples on this page are deliberately tiny — one idea each, a CSV and a command. When you want
a **worked system** instead, with a store to stand up, a data model, a continuous query and the
application code that reads it, go to [`case-studies/`](case-studies/):

| Study | Domain | Store | Start here if |
|---|---|---|---|
| [Trade processing](case-studies/trade-processing/) | Trading | Aerospike | **You are new.** No aggregation at all: a feed, many filtered subscribers on one computation, and SQL over the same data |
| [Card authorisation velocity](case-studies/banking-card-velocity/) | Banking | Aerospike | You want windows, a temporal join and filter pushdown |
| [Intraday counterparty exposure](case-studies/finance-counterparty-exposure/) | Finance | PostgreSQL | Your source is a database, not a key-value store |
| [Order flow surveillance](case-studies/trading-order-flow/) | Trading | Aerospike | You need overlapping windows, or you reached for `CASE` |
| [Sequencing run QC](case-studies/biology-sequencing-qc/) | Biology | Aerospike | You want to see the same engine on a domain with no money in it |

Each is a template meant to be copied — driven through the published SDK in Java and Python, or from
the `pravaha` command line — and every SQL statement in them is planned and run against the real
engine by the build. Setup for the stores they need is in
[`case-studies/SETUP.md`](case-studies/SETUP.md).
