# Pravaha tutorials

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Hands-on, one worked example each. Every tutorial runs a node of its own on the small files in
[data/](data/), and each step says what to do, gives the real command and the real output it printed,
shows a real screenshot of the console at that moment, names the error codes the step can raise, and
tells you what you should see. Each ends with next steps and a table of what to do when something
goes wrong.

They are written for someone who has never used Pravaha. The reference documents —
[CONTINUOUS_QUERIES.md](../guides/CONTINUOUS_QUERIES.md), [USER_GUIDE.md](../guides/USER_GUIDE.md),
the [CLI reference](../../pravaha-console/content/topics/cli-reference.md) and the rest of
[guides/](../guides/) — say everything; the tutorials say what to do first. In the console they are
under **Help → Tutorials** (`/tutorials`).

## The tutorials

| # | Tutorial | You will | Time | Status |
|---|---|---|---|---|
| 1 | [Your first 15 minutes](01-first-15-minutes.md) | scaffold a project with `pravaha init`, start a node with sign-in on, sign in to the console as `pravaha-dev-admin`, read a stream, register a query in the Workbench and one from the CLI, watch a view change in Live and with `subscribe`, run `pravaha doctor`, read the view from Python | 15 min | written |
| 2 | [Windows, event time and late data](02-windows-and-late-data.md) | read a stream's clock, cut readings into tumbling and hopping windows (and meet the session window that has no SQL yet), close a window with a later reading, correct a published window with a late one (−1 and +1), drop one too late, and see what lateness costs | 30 min | written |
| 3 | [Joins and lookups](03-joins-and-lookups.md) | join orders and payments within five minutes, find the orders nobody paid with a `LEFT` join, see a join wait for its slower stream, enrich orders from a JDBC table (H2), choose a view's key, and change the table under a running query | 35 min | written |
| 4 | [Following a view](04-following-a-view.md) | subscribe with a snapshot, see a correction as −1 and +1, filter at the server, keep a copy from Python by weights, follow from Java, read through the PostgreSQL gateway with `psql` and an API key, reconnect across a restart, and end a subscription | 30 min | written |
| 5 | [Changing a running query safely](05-changing-a-running-query.md) | dry-run a replacement and a drop, backfill a new version beside the running one, cut over under a subscriber, roll back without losing a row, abandon one replacement and finish another | 25 min | written |
| 6 | [Investigating an incident](06-investigating-an-incident.md) | ask `health`, `status`, `top` and the metrics, see where queries run, find a malformed payment on the dead-letter queue, replay it and correct it at the source, fork the query from a checkpoint and step it in the time-travel debugger, export a test, take a thread dump | 30 min | written |

The [case studies](../../examples/case-studies/README.md) come after: complete systems, one domain each,
built from the moves of these six. [Joining two Aerospike sets and a CSV file](../guides/tutorials/aerospike-fulfilment.md)
is a worked case study against a real Aerospike.

## What you need once

Java 21 or later, Python 3.11 or later, and the `pravaha` command (`pip install './sdk/python[flight]'`
from a checkout); the server jar from a build (`./mvnw -q -DskipTests install`) or the Docker images.
Tutorial 1 starts a node with the `dev` and `users` profiles and signs in as the development account
`pravaha-dev-admin` (password `pravaha-dev-admin123`, for your own machine only); every later tutorial
starts a node of its own the same way, with its own `PRAVAHA_HOME`, so they can be done in any order.
Tutorial 3 needs the H2 jar, tutorial 4 the `psql` client. The default ports are 18080 (HTTP),
19090 (Flight SQL), 17070 (the console) and, in tutorial 4, 5433 (the PostgreSQL gateway).

## The sample data

| File | What it is | Used by |
|---|---|---|
| what `pravaha init` writes | a stream `txn`, twelve payments and a first windowed query | tutorial 1 |
| [`data/t01/read_view.py`](data/t01/read_view.py) | reading a view from Python | tutorial 1 |
| [`data/t02/`](data/t02/) | two smart meters' readings, the node with allowed lateness and an append-only sink, a tumbling and a hopping query | tutorial 2 |
| [`data/t03/`](data/t03/) | orders, payments, the customer table as an H2 script, and three queries | tutorial 3 |
| [`data/t04/`](data/t04/) | payments with an operation column (corrections), the node with the PostgreSQL gateway on, and `follow.py` | tutorial 4 |
| [`data/t05/`](data/t05/) | payments, and the second version of a query | tutorial 5 |
| [`data/t06/`](data/t06/) | payments, the node with a dead-letter queue and frequent checkpoints, and the query the incident is about | tutorial 6 |

## How the tutorials are kept true

The output is captured, not typed. `sdk/python/tools/tutorial_captures.py` plays each tutorial's
scenario (`sdk/python/tools/tutorials/tNN.py`: its node, its data, every command in order) against a
scratch node built from this checkout, and writes what each command printed between the
`<!-- capture: tNN-... -->` markers, with what differs between runs normalised (ports to the defaults,
wall-clock times to `<time>`, ids to `<id>`, keys and tokens to `<key>` and `<token>`, durations to
`N`); `--check` fails when a tutorial no longer shows what the product prints. The pictures are made,
not drawn: `pravaha-console/tools/docs_screenshots.py --tutorial tNN` plays the same scenario under a
console and a real browser and takes each picture where the scenario says, into `img/tNN/`. The
console's `tests/test_tutorials.py` holds the format, every link and every picture; pravaha-it's
`TutorialSqlTest` plans every SQL statement against the engine. When the product changes:

```bash
cd sdk/python && .venv/bin/python tools/tutorial_captures.py --only t02          # rewrite one tutorial's output
cd pravaha-console && .venv/bin/python tools/docs_screenshots.py --tutorial t02  # and its pictures
```
