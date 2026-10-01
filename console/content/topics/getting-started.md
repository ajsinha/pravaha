---
title: Getting started
slug: getting-started
category: start
order: 10
icon: signpost-2
summary: "What Pravaha is and the three nouns it is built from; your first maintained view end to end, with the output of every step; and the console, screen by screen — the bar, the menus, the themes."
badge: START
audience: Everyone
keywords: [overview, introduction, what is pravaha, orientation, stream, view, continuous query, mental model, tutorial, quickstart, walkthrough, first run, csv, follow, psql, python, console, ui, workbench, catalog, operations, admin, palette, deep link, roles, ports, menu, mega menu, navigation, theme, crimson, dark, blue, green, maya]
guide: quickstart
related: [clients, streams, views-and-keys, event-time-watermarks, zset-weights, subscriptions]
---

This page has three parts: [what Pravaha is](#what-pravaha-is), [your first maintained
view](#your-first-maintained-view) — the whole loop on one machine, with what comes back at every
step — and [the console, screen by screen](#the-console-screen-by-screen).

## What Pravaha is {#what-pravaha-is}

Pravaha is a streaming SQL engine that **maintains answers**. You tell it a question once — as
SQL, under a name — and it keeps the answer current for as long as the name is registered. The
answer is a **view**: an indexed table you read by key, scan with SQL, or subscribe to for its
changes. Nothing is re-run when you read it; the reading is a lookup.

That is the inversion the rest of the product follows from. A database answers a question when you
ask it and forgets it afterwards. Pravaha is told the question in advance, so it can do the work as
rows arrive and have the answer ready before anybody asks. Its motto is the same sentence written
short: *ask once, answer always*.

## The problem it solves

Most systems that need a continuously correct number — a card's spend in the last minute, a
counterparty's open exposure, the orders per region in the current five minutes — end up with three
moving parts: a stream processor that computes, a database that stores what it computed, and code
that keeps the two consistent. Every one of those seams is a place for the number to be wrong.

Pravaha collapses them:

| You would otherwise build | In Pravaha |
|---|---|
| A job in a stream processor | A `CREATE CONTINUOUS QUERY` statement |
| A serving database the job writes into | The view the query maintains, readable by key over Arrow Flight SQL or the PostgreSQL wire protocol |
| A cache or materialised table refreshed on a schedule | The same view — it is updated per commit, not per schedule |
| Retry and de-duplication logic for corrections | Z-set weights: a correction is a `-1` for the old row and a `+1` for the new one, applied by the engine and delivered to subscribers |
| A second copy of the pipeline for each team asking the same question | Sharing by fingerprint: identical questions become one computation with several names |

It reads the stores you already have — files, directories of feed files, JDBC databases, a
PostgreSQL table's change log, Kafka topics, Delta tables, Aerospike, Cassandra — and can write each
answer back out to a sink (a file, a JDBC table, an Aerospike set, a Kafka topic) as well as serving
it.

## The three nouns

Everything in the help is about one of these, or about how two of them meet.

| | What it is | Lives for |
|---|---|---|
| **Stream** | A named, typed, unbounded sequence of rows, bound to a source. Declared once with its schema, the column that carries each row's own time, and how late its rows may be | The node's lifetime |
| **Continuous query** | SQL registered under a name, with a key. It reads one or more streams and keeps running | Until it is dropped |
| **View** | The query's answer, maintained incrementally, readable by SQL, subscribable per commit | As long as its query |

```text
source ──feeds──► stream ──read by──► continuous query ──maintains──► view ──read by──► SELECT / subscribe / sink
```

A continuous query is **not a request**. You do not ask it for an answer; you register it once and
it keeps one. Reading the view is a different, cheap, repeatable operation. That is why registering
is authorised separately from reading, and why registering costs something (memory, a share of a
lane) while reading does not.

## The mental model in five sentences

1. **Rows carry weights.** `+1` is a row appearing, `-1` one being withdrawn; every operator does
   the same arithmetic on both, which is what lets an aggregate be maintained instead of recomputed
   ([Z-set weights](/help/topics/zset-weights)).
2. **Time is the time in the data.** Windows close when the data says a period is over — when the
   watermark passes the window's end — not when the wall clock does
   ([event time and watermarks](/help/topics/event-time-watermarks)).
3. **Everything is bounded or refused.** A query whose state would grow for ever — an unwindowed
   `GROUP BY` over a stream — is refused when it is planned (PRV-2050), not deployed to fail months
   later ([what the engine refuses](/help/topics/sql-refusals)).
4. **The key decides what a row replaces.** A view is keyed; a second row with the same key
   supersedes the first ([views and keys](/help/topics/views-and-keys)).
5. **Same question, one computation.** Two registrations that plan to the same thing share their
   state, whatever they are called ([sharing](/help/topics/sharing)).

## A first look, in SQL

The shape nearly everybody writes: filter a stream, window it in event time, aggregate, keep the
answer keyed.

```sql
CREATE CONTINUOUS QUERY merchant_minute
    KEYED BY (merchant, window_end)
    RETAIN FOR P1D
AS
SELECT merchant, window_start, window_end,
       COUNT(*)    AS payments,
       SUM(amount) AS takings
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE status = 'COMPLETED'
GROUP BY merchant, window_start, window_end;
```

Once registered, reading one merchant's latest minutes is a lookup against the view, not a scan of
the stream:

<!-- sql: read -->
```sql
SELECT window_end, payments, takings
FROM merchant_minute
WHERE merchant = 'm-042'
```

With sample data, that read answers something like this (one row per closed minute):

```text
window_end            payments  takings
2026-09-19 09:31:00   14        52300
2026-09-19 09:32:00   9         18750
```

The engine answers with one row when the `CREATE` succeeds — the name, its state, the fingerprint
of the computation it landed on, and its sink:

```text
name             state    fingerprint    sink
merchant_minute  RUNNING  7c1e2a9b4f03
```

(The fingerprint is a hash of the normalised plan; yours will differ.)

## What it is not

- **Not a database you load and query ad hoc.** It answers questions asked in advance. Ad-hoc
  federated analytics is out of scope on purpose ([ADR-030](/help/decisions/030-flight-sql-as-the-client-protocol)),
  and every SQL refusal is a case of that decision rather than an unfinished corner.
- **Not a job runner.** There is no job to submit, no cluster to wait for, and no separate serving
  store to keep in step.
- **Not distributed.** It is one node, scaled to its cores; a standby can take over from the newest
  checkpoint. Multi-node execution is designed (ADR-045) and on hold by decision — see
  [standby and cluster mode](/help/topics/standby).
- **This console is not the product.** It is a separate process that reaches the engine only
  through the published Python SDK — the same API an integrator uses.

## Ten minutes, in order

| Minute | Do | Page |
|---|---|---|
| 0–3 | Read this part and [Concepts](/help/concepts) §1–§4 | this one |
| 3–8 | Start a node, declare a stream, register a query, read it, subscribe | [Your first maintained view](#your-first-maintained-view), below |
| 8–10 | Try a query the engine refuses, and read why | [What is refused, and why](/help/topics/sql-refusals) |

## Where to go by what you came to do

| You are | You want | Start with |
|---|---|---|
| An **analyst** writing SQL | What runs, what is refused, windows and joins | [The SQL reference](/help/topics/sql-reference), [windows](/help/topics/windows), [joins](/help/topics/joins) |
| A **developer** reading answers | Point reads, subscriptions, client code | [Choosing a client](/help/topics/clients), [point reads](/help/topics/views-and-keys#point-reads), [subscriptions](/help/topics/subscriptions) |
| An **operator** running a node | Configuration, sizing, metrics, recovery | [Configuration](/help/topics/configuration), [metrics and alerts](/help/topics/observability), [checkpoints](/help/topics/checkpoints-recovery) |
| Connecting a **data store** | Options, a complete binding, pitfalls | [Sources](/help/topics/sources-overview), [sinks](/help/topics/sinks-overview) |
| Responsible for **security** | Who may read what, and the audit trail | [Authentication](/help/topics/authentication), [authorization](/help/topics/authorization) |
| Holding an **error code** | What it means and what to do | [Every error code](/help/codes), or type the code in the help search |
| Embedding it in a **JVM application** | No server, no network | [The embedded engine](/help/topics/embedded-engine), [Spring Boot](/help/topics/spring-boot-starter) |

!!! tip "The single most common first-run surprise"
    A windowed query refused with `PRV-2002` saying the stream "declares no event-time column".
    Without that key no watermark advances and no window could ever close, so the engine refuses
    the query instead of running it empty for ever. Declare it and register again. If the query
    *did* register and the view is still empty, no row has yet arrived past a window's end plus the
    stream's `out-of-orderness`. See [event time and watermarks](/help/topics/event-time-watermarks).

!!! note "Three ports"
    The engine's Flight SQL endpoint is **19090**, its HTTP API and Prometheus endpoint **18080**, the
    PostgreSQL gateway (when enabled) **5432**, and this console **17070**. Confusing them is the
    commonest way a first connection fails.

## Your first maintained view {#your-first-maintained-view}

This part takes you from an empty directory to a view you can watch changing, and shows the exact
command and what comes back at every step. It uses one machine, one CSV file that you append to by
hand, and the three ways of reading an answer — the `pravaha` CLI, `psql`, and the Python SDK.

Budget fifteen minutes the first time, most of it the build. Everything here is local and
unauthenticated, which is what the `dev` profile is for; the last section says what to change before
anybody else can reach the node.

### Before you start

| Check | Command | If missing |
|---|---|---|
| Java 25 | `java -version` | Any OpenJDK 25. Pravaha 2.x needs 25; an older JVM is refused by name |
| `JAVA_HOME` points at it | `echo $JAVA_HOME` | `export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64` — distributions that ship several JDKs often leave `javac` and `java` on different versions |
| Python 3.11+ | `python3 --version` | For `pravaha`, the CLI, and the Python step |
| `psql` | `psql --version` | Only for the PostgreSQL step |

### 1. Build, and put the launchers on your PATH

From a checkout of the repository:

```bash
./mvnw -q -DskipTests install
export PATH="$PWD/bin:$PATH"
pravaha-engine --help
```

The build produces the engine node (`pravaha-server/target/pravaha-server-<version>-app.jar`,
launched by `bin/pravaha-server`) and `pravaha-engine` (`pravaha-cli/target/pravaha-cli-<version>-cli.jar`,
launched by `bin/pravaha-engine`), which validates, explains and runs a query with no server. The
first build takes a few minutes. `pravaha`, the command that talks to the running node, is the
Python CLI: it comes with the Python SDK (`pip install './sdk/python[flight]'`).

### 2. Describe the node in one file

Make a working directory with an empty input file whose only line is a header:

```bash
mkdir -p ~/pravaha-first && cd ~/pravaha-first
echo 'txn_id,user_id,merchant,amount,currency,status,event_time,op' > txn.csv
```

Then `application.yaml` beside it. Three blocks, and they are separate on purpose:
`pravaha.streams` says what the stream **is** (enough to plan a query), `pravaha.sources` says where
its rows **come from** (enough to run one), and `pravaha.pgwire` switches on the PostgreSQL gateway
so `psql` can read the answer.

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP,op:STRING"
      event-time: event_time          # without this no watermark advances and no window ever closes
      out-of-orderness: 10s           # how late this stream's rows may arrive and still be waited for
  sources:
    txn:
      plugin: filesystem
      options:
        path: /home/you/pravaha-first/txn.csv      # an absolute path
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP,op:STRING"
        event.time: event_time        # the source stamps each row with this column's time
        skip.header: "true"
        follow: "true"                # keep reading as the file grows, like tail -f
        op.column: op                 # a row whose op is D, DELETE, - or -1 is a retraction
  pgwire:
    enabled: true
```

Four things in that file are worth a second look:

- **The schema is written twice** — once for the catalogue to plan against, once for the plugin to
  parse rows with. They must agree. That is a known wart, not a design.
- **`status:STRING?`** — the `?` makes the column nullable.
- **`event-time` and `event.time`** — the first tells the catalogue which column is the stream's
  time; the second tells the source which column to stamp each row with. Leave either out and a
  windowed query stays empty for ever.
- **`op.column`** is what lets a plain file carry deletions. Without it every row is an insertion.

### 3. Start the node

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./application.yaml &
```

The `dev` profile is the acknowledgement the node demands before it will serve every view to
unauthenticated callers: without it (or real credentials) the node refuses to start. Check it is up:

```bash
pravaha queries
```

```text
no continuous queries are registered
```

### 4. Register two continuous queries

The first is the shape nearly everybody writes: filter, window in event time, aggregate, key by what
identifies a row. The second is a plain filter, which we will use to watch a retraction.

```sql
CREATE CONTINUOUS QUERY minute_spend
    KEYED BY (user_id, window_end)
    RETAIN FOR P1D
AS
SELECT user_id, window_start, window_end,
       COUNT(*)    AS payments,
       SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE status = 'COMPLETED'
GROUP BY user_id, window_start, window_end;

CREATE CONTINUOUS QUERY big_payments
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, merchant, amount
FROM txn
WHERE amount >= 1000;
```

Send each statement through anything that speaks SQL to the engine. From the CLI:

```bash
pravaha query --sql "CREATE CONTINUOUS QUERY minute_spend KEYED BY (user_id, window_end) RETAIN FOR P1D AS
  SELECT user_id, window_start, window_end, COUNT(*) AS payments, SUM(amount) AS spend
  FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
  WHERE status = 'COMPLETED' GROUP BY user_id, window_start, window_end"
pravaha query --sql "CREATE CONTINUOUS QUERY big_payments KEYED BY (txn_id) AS
  SELECT txn_id, user_id, merchant, amount FROM txn WHERE amount >= 1000"
pravaha queries
```

Each `CREATE` answers with one row — the name, its state, the fingerprint of the computation it
landed on, and the sink (none here). Then the listing:

```text
NAME          STATE    FINGERPRINT   ROWS IN  SINK
minute_spend  RUNNING  3b9f0c2e71a4  0        -
big_payments  RUNNING  c05d81e6a9f2  0        -
```

Fingerprints are hashes of the normalised plan, so yours will be different strings. `KEYED BY`
names output columns and the engine turns them into ordinals by planning the `SELECT`, so reordering
the select list cannot quietly change what the key is. The same registration as arguments is
`pravaha register --name big_payments --sql "..." --keys 0`.

### 5. Feed it, and see why nothing appears yet

Append seven payments across three minutes of event time:

```bash
cat >> txn.csv <<'CSV'
1,u1,m-7,1200,GBP,COMPLETED,2026-09-19T09:00:05Z,I
2,u2,m-7,300,GBP,COMPLETED,2026-09-19T09:00:20Z,I
3,u1,m-9,450,GBP,COMPLETED,2026-09-19T09:00:41Z,I
4,u1,m-9,80,GBP,DECLINED,2026-09-19T09:00:55Z,I
5,u2,m-3,2500,GBP,COMPLETED,2026-09-19T09:01:12Z,I
6,u1,m-7,90,GBP,COMPLETED,2026-09-19T09:01:40Z,I
7,u3,m-3,60,GBP,COMPLETED,2026-09-19T09:02:15Z,I
CSV
```

The newest event time is 09:02:15 and the stream tolerates 10 seconds of disorder, so the
**watermark** — "nothing earlier than this is still coming" — reaches 09:02:05. The windows that end
at or before it can be published: `[09:00, 09:01)` and `[09:01, 09:02)`. The window `[09:02, 09:03)`
is still open, because a row for 09:02:40 could arrive at any moment, and the engine does not
publish an answer it might have to take back. That is the rule of the whole engine: **a window
closes because the data said so, not because the clock moved.**

### 6. Read the view from the CLI

<!-- sql: read -->
```sql
SELECT user_id, window_end, payments, spend FROM minute_spend
```

```bash
pravaha query --sql "SELECT user_id, window_end, payments, spend FROM minute_spend"
```

```text
user_id  window_end           payments  spend
u1       1789808460000000000  2         1650
u2       1789808460000000000  1         300
u2       1789808520000000000  1         2500
u1       1789808520000000000  1         90
4 rows
```

Line by line: user `u1` in the first minute made two completed payments (1200 + 450; the declined
80 is filtered out), `u2` one of 300; in the second minute `u2` paid 2500 and `u1` 90. Row order is
not meaningful — there is no `ORDER BY` over a view.

The CLI prints a `TIMESTAMP` as it arrives over Arrow Flight: **nanoseconds since the epoch, UTC**.
`1789808460000000000` is 2026-09-19 09:01:00 UTC. The SDKs and `psql` render it as a time.

A point read binds its value rather than pasting it into the SQL — a bound value is never parsed as
SQL, and the server plans the statement once however many users you ask about:

<!-- sql: read -->
```sql
SELECT window_end, spend FROM minute_spend WHERE user_id = ?
```

```bash
pravaha query --sql "SELECT window_end, spend FROM minute_spend WHERE user_id = ?" --params u1
```

```text
window_end           spend
1789808460000000000  1650
1789808520000000000  90
2 rows
```

### 7. Read the same view from psql

The gateway speaks the PostgreSQL wire protocol on port 5432. With the `dev` profile it asks for no
password; the database name is not used for anything.

```bash
psql "host=localhost port=5432 user=dev dbname=pravaha" \
     -c "SELECT user_id, window_end, spend FROM minute_spend WHERE spend > 500"
```

```text
 user_id |     window_end      | spend
---------+---------------------+-------
 u1      | 2026-09-19 09:01:00 |  1650
 u2      | 2026-09-19 09:02:00 |  2500
(2 rows)
```

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM minute_spend WHERE spend > 500
```

A `TIMESTAMP` arrives as `timestamptz`, in UTC, with nanoseconds kept when a value has them. The
gateway is **read-only**: a `CREATE CONTINUOUS QUERY` sent to it is refused with PRV-6211.

### 8. Read and subscribe from Python

```bash
cd sdk/python && make install && . .venv/bin/activate     # from the repository root
```

```python
from pravaha import connect

with connect("grpc://localhost:19090") as client:
    for row in client.query("SELECT user_id, spend FROM minute_spend WHERE user_id = ?", ["u2"]):
        print(row["user_id"], row["spend"])
```

```text
u2 300
u2 2500
```

`grpc://` is plaintext and is spelled out on purpose; a URL without a scheme means TLS.

Now leave a subscriber running in a second terminal. A subscription starts **from now** — it
receives commits made after it attaches, one batch per commit — and every row carries a weight:

```python
from pravaha import connect

with connect("grpc://localhost:19090") as client:
    for batch in client.subscribe("big_payments"):
        for row in batch:
            print(f"{row.weight:+d}", row["txn_id"], row["user_id"], row["amount"])
        print("-- commit")
```

### 9. Watch a window close

In a third terminal, subscribe to the windowed view with the CLI:

```bash
pravaha subscribe --view minute_spend
```

```text
subscribed to minute_spend; changes print as they are committed. Ctrl-C to stop.
```

Then append one payment a minute later in event time:

```bash
echo '8,u3,m-3,40,GBP,COMPLETED,2026-09-19T09:03:20Z,I' >> txn.csv
```

The watermark moves to 09:03:10, which passes the end of `[09:02, 09:03)`, and the window that held
row 7 is published as one commit:

```text
WEIGHT	user_id	window_start	window_end	payments	spend
+1	u3	1789808520000000000	1789808580000000000	1	60
-- commit, 1 row
```

The CLI prints each change's weight — `+1`, a window's answer arriving — then the view's columns in
order, under a header printed with the first change, and marks the end of each commit. A subscriber never sees half a commit.

### 10. Watch a retraction

Append a deletion of payment 5 — the same values, with `op` set to `D`:

```bash
echo '5,u2,m-3,2500,GBP,COMPLETED,2026-09-19T09:01:12Z,D' >> txn.csv
```

The Python subscriber on `big_payments` (step 8) prints the withdrawal as a row with weight `-1`:

```text
-1 5 u2 2500
-- commit
```

and a read of the view no longer has it:

<!-- sql: read -->
```sql
SELECT txn_id, amount FROM big_payments
```

```text
txn_id  amount
1       1200
1 row
```

That `-1` is the whole correction model: the engine does not send a special "delete" message, it
sends the same row with the opposite weight, and every operator does the same arithmetic on both.
A consumer keeping its own total adds `weight × amount`; a consumer that only wants current values
overwrites by key and drops the negatives. See [Z-set weights](/help/topics/zset-weights).

### 11. And a row that arrives too late

Append a payment stamped 09:00:30 — inside the first minute, which closed long ago:

```bash
echo '9,u1,m-7,500,GBP,COMPLETED,2026-09-19T09:00:30Z,I' >> txn.csv
```

`big_payments` has no window, so a 500 does not pass its filter either way. `minute_spend` does not
change: `u1`'s first minute stays at 1650. The stream declares no **allowed lateness** — zero, the
default — so a row for a window that has already been published is counted as late and dropped
rather than reopening it. The same retraction arithmetic *can* correct a closed window — a `-1` for
the old total and a `+1` for the new one — when the stream declares one
(`pravaha.streams.txn.allowed-lateness`). The [late data](/help/topics/event-time-watermarks#late-data) page shows both paths.

The deletion in step 10 had the same fate in `minute_spend`: it is stamped 09:01:12, behind the
watermark, so the closed minute keeps its 2500. Only the unwindowed `big_payments` withdrew it.

### 12. Clean up

```sql
DROP CONTINUOUS QUERY minute_spend;
DROP CONTINUOUS QUERY big_payments;
```

```bash
pravaha drop --name minute_spend --yes
pravaha drop --name big_payments --yes
```

```text
dropped minute_spend
dropped big_payments
```

A drop removes a **name**; the computation goes when its last name goes.

### What goes wrong on a first run

!!! warning "The view is empty and the query says RUNNING"
    No `event.time` on the source, so nothing stamps the rows and the watermark never advances. Or
    every row so far is within `out-of-orderness` of the newest one — append a row later in event
    time. (A missing `event-time` on the *stream* does not get this far: the query is refused with
    `PRV-2002`.) See [event time and watermarks](/help/topics/event-time-watermarks).

!!! warning "Object 'txn' not found"
    The query names a stream the catalogue does not have. Check the `pravaha.streams` block — the
    source binding alone does not declare a stream.

!!! warning "Options at the wrong level"
    Plugin options live under `options:`. A key written one level too high is not read, not
    reported, and the node ingests nothing.

!!! warning "Connection refused on 18080 from the SDK"
    The SDKs and CLI speak Flight on **19090**. Port 18080 is the engine's HTTP API; 17070 is this
    console.

!!! danger "Before anybody else can reach it"
    The `dev` profile serves every view to anyone. Configure `pravaha.security.authentication`
    and `pravaha.security.policy` and TLS before binding to anything but loopback — see
    [authentication](/help/topics/authentication) and [TLS](/help/topics/tls).

## The console, screen by screen {#the-console-screen-by-screen}

The console is the browser product for a running engine: where an analyst writes, checks and
registers continuous SQL, where a developer finds a view and copies the code that reads it, where an
operator answers *"is everything healthy, and if not, where?"*, and where anyone watches a view
change as the engine commits. This part walks every screen.

It is a **separate process** (ADR-024) that reaches the engine **only through the published Python
SDK** — Arrow Flight on the engine's port 19090 for queries, registration, lifecycle and
subscriptions, and the engine's HTTP API on 18080 for the catalogue, validation, plans, status and
metrics. It holds no data of its own and cannot reach past the public API, so anything it can do,
an integrator's program can do too.

### How you get in

| Port | Process | What it is |
|---|---|---|
| **17070** | the console | this browser product |
| **19090** | the engine | Arrow Flight SQL — every SDK, the CLI |
| **18080** | the engine | its HTTP API and `/actuator/prometheus` |
| **5432** | the engine | the PostgreSQL gateway, when `pravaha.pgwire.enabled` is set |

The landing page, the help (including every `/help/codes/...` page), About and the health probes are
**public**: an operator opening the console during an incident needs it to load and say what is
wrong before they have signed in. **Everything that names a registered query, reads a view,
shows the catalogue or reaches the engine needs a session.** Sign in with your own username and
password: the console asks the engine, which is where accounts live (ADR-052), keeps the engine
session it answers with, and acts as you on every call — so everything you do is authorised and
audited as you. A new engine's first account is its bootstrap `admin`; an administrator adds
everyone else under **Admin · Users**. Your password, your API keys and your sessions are on
**Account**, in the account menu.

In the account menu, or on your account page, you choose a **persona**, and it decides where you
land, not what you may do:

| Role | Lands on | For |
|---|---|---|
| analyst | Workbench | writing and registering SQL |
| operator | Operations | health, lag, state, checkpoints |
| developer | Views | reading answers, copying client code |
| admin | Admin · Access | what you may do, the audit trail, and (for an administrator) people, keys and sessions |

The engine decides what you may see: the console reaches it **as you**, and every screen shows
exactly what the engine's policy lets you see. It is remembered for you on that browser.

### Finding your way: the bar, the menu, the themes

The console is drawn in the design language of MAYA, its sibling product. Signed in, the bar across
the top holds the mark and the three brand lines, then five menus, each opening a panel of columns:

| Menu | What is in it |
|---|---|
| **Catalog** | Streams, registered queries, sinks, views |
| **Workbench** | Design: the workbench, *Describe it*. Change safely: compare versions, replace a running query |
| **Operate** | Watch: operations, overview, alerts. Run: queries (each query's dead letters, replacement and debugger are on its page), lanes |
| **Admin** | For administrators: users, API keys, sessions, tenants, grants, row filters and masks; plugins; AI models; the audit trail and access |
| **Help** | Concepts and topics, guides, tutorials, case studies; get started, the command line, error codes, search; About |

On a wide screen a panel opens when you point at its menu; on a phone the whole menu sits behind
the ☰ button. On the right are the search (it opens the command palette, **Ctrl-K**), alerts, the
**theme menu** and your **user menu** — your account, your password, the persona you land as,
*Compact rows*, and Sign out. Signed out, the bar is the public one: Help, About, the theme menu
and Sign in.

**Themes.** Four, as MAYA has them: **Crimson**, **Dark**, **Blue** and **Green**, each shown with
a swatch in the theme menu (the palette button), or cycled with **t**. Your choice is kept in this
browser; until you choose, the console follows your system's light or dark setting. **d** switches
between comfortable and compact rows.

Under the bar, a line says where you are — the engine's address, the environment when one is
configured (`app.environment`), the console's version — and a warning takes its place when the
engine is not answering, or while the bootstrap `admin` still has its published password.

### Home and onboarding

| Route | What it does |
|---|---|
| `/` | What Pravaha is — answers even with the engine down |
| `/home` | Your role's landing. On an engine with nothing registered, `/start` instead |
| `/start` | First run: pick or declare a stream (its event time and lateness included), pick a question from templates written against that stream's own columns, register it with keys chosen by name, and watch the view change |

### Workbench — write, check, explain, run, register

`/workbench` is the analyst's screen. A Monaco editor with Pravaha's own SQL language:

- **Completion from the catalogue** — streams, their columns with types, functions with signatures,
  scoped to what the statement reads.
- **Validation as you type** (300 ms after you stop), with squiggles at the engine's own reported
  position and a diagnostics panel. Each `PRV-nnnn` links to its help page, and offers its fix when
  the fix is certain.
- **Explain** draws the engine's physical plan as a graph (operators as nodes; exportable).
- **Run** executes a one-off query over a view into a virtualised grid (up to `ui.query_row_limit`
  rows, 500 by default).
- **Register** names the query, picks the key columns by name, and optionally a sink and a
  retention. It is the same registration as `CREATE CONTINUOUS QUERY`.
- **Drafts** are kept per browser tab in local storage; a snippet library holds common shapes.
- **Compare** puts the draft beside a registered query (v1, picked by name) or another draft: the
  SQL in a diff editor, both plans with each operator marked added, removed or changed, and what the
  engine will do with the new version — [Comparing two versions](/help/topics/backfill-cutover#comparing-two-versions).

Deep links, so a colleague can be sent straight to the thing:

| Link | Opens |
|---|---|
| `/workbench?query=minute_spend` | a registered query's SQL |
| `/workbench?sql=SELECT+...` | a piece of SQL |
| `/workbench?template=<id>&stream=txn` | a library template written against that stream |
| `/workbench?panel=explain` | with the plan panel open |
| `/workbench?query=v1&panel=diff&against=v1` | a query compared with its registered self, ready to edit |

It also works as a plain form with JavaScript off: run and register still submit.

### Catalog — what exists

`/catalog` has four tabs, and the tab is in the URL (`?tab=streams`, `?tab=queries`, `?tab=sinks`,
`?tab=objects`):

| Tab | Shows |
|---|---|
| Streams | each stream's schema, event-time column, out-of-orderness, and the source plugin feeding it (the plugin's name only, never its options) |
| Queries | every registered query: state, fingerprint, key, retention, sink, and **the other names sharing its computation** |
| Sinks | every sink binding: plugin, row shape, key, emit modes, whether it accepts retractions, delivery guarantee, and who writes to it |
| Objects and grants | with the [catalogue](/help/topics/catalog-and-grants) on: every governed object — namespaces, views, streams, sinks — with its owner, description and tags. `/catalog/objects/{name}` is one of them: its grants, and the row filters and masks that reach it |

`/catalog/streams/{name}` is one stream: its schema, the queries that read it (from each query's
own description, not a text search), and templates written against it.

### Views — read an answer, copy the code

`/views` lists every view you may read. `/views/{name}` is one view: its schema, key, retention and
fingerprint, a **point query** (a plain GET form — `?key=user_id&value=u1` — bound as a parameter
and answered by the server), and **copy-paste client code** for exactly that view and key: Java SDK,
Python SDK, `psql`, and the CLI. The `psql` snippet uses `engine.pgwire` (default
`localhost:5432`) — the console itself never connects there.

### Live — watch a view change

`/views/{name}/live` subscribes to the view and shows each committed change as it arrives, **with
its weight** — `+1` for a row appearing, `−1` for one withdrawn. The current rows are the running
Z-set sum of the view read on connect plus every change since. A numeric column can be charted over
time; a tap filter narrows the stream at the engine. If the browser falls behind, the page says
"sampled — N dropped" rather than pretending (`ui.tail_buffer`, 256 changes per browser).

Ten people on one live view cost the engine **one** subscription: the console fans a single
subscription out to every browser.

### Alerts

`/alerts` lists every [alert](/help/topics/alerts) you may see — an alert watches a view: a key
fires when its row enters the answer and clears when it leaves — with what is firing now.
`/alerts/{name}` is one alert: its keys, its history and its channels, and acknowledge, snooze,
pause and resume. Pausing, snoozing and de-duplication change what is said, never what is true.

### Operations — is everything healthy?

`/operations` opens with a **verdict** and a list of **findings** per query with what to do about
each, then node status and plugin health, then per-query rows in, rate, state held against its
ceiling, view size, watermark lag, subscribers, checkpoint health and mean commit latency, with
charts. It updates once a second over one event stream, from one Prometheus scrape per second
however many operators are watching.

A watermark further behind than `ui.lag_warn_seconds` (300 by default) is a finding; so are
checkpoint failures and a stale checkpoint. A query whose source has stopped is a **critical**
finding, with the code linked to its help page, and the verdict names it. Commit latency is shown as a **mean**, labelled as one:
the engine publishes a count and a total, so a percentile would be invented.

A query whose lane had nowhere to put a row for a fifth of the time or more is a finding too, with
the share, how full its inbox is and — where the node counts operators — the operator most of its
time goes into, which the verdict then names beside the query. Below the table, **Shared lanes**
gives each lane's own blocked share and depth, which is how a query blocked *by* a neighbour is
told from one blocking itself. See [reading the numbers on a plan](/help/topics/reading-a-plan).

`/overview` is the compact version: is it up, what is registered, what is shared, the busiest
queries.

### Queries — the list and one query

`/queries` is filterable, sortable and paged, and **every filter is in the URL**
(`?search=spend&state=RUNNING&sort=-rows_in`). `/queries/{name}` is one query: its SQL, key,
retention, sink (and its PRV-8009 failure if the sink was detached), its **source** — receiving rows,
or stopped with the code, the stream#partition and the time (a stopped source leaves the query
`RUNNING`; the list marks it "source stopped") — the other names sharing it,
the streams it reads, lifecycle controls, and a raw live tail — with links into the workbench, its
plan, its view and its live page.

**Drop needs the query's name typed**, because it removes the name for everybody reading through it.
Pause and resume do not; they are reversible.

`/queries/{name}/dead-letters` is the records its feed could not decode, newest first, each
replayable. `/queries/{name}/replacement` is its **backfill and cutover**: a new version prepared
beside the running one, what the backfill has read — with no ETA and no percentage, because a
source does not say how much history it holds — a ceiling that may only be lowered, and a cutover
and a rollback each needing the name typed, as drop does. See
[backfill and cutover](/help/topics/backfill-cutover).

`/queries/{name}/debug` is its **time-travel debugger**: the query forked from a retained
checkpoint into a second computation nothing can read, stepped one row at a time, with every
operator's rows in and out beside each step and the view's changes with their weights. It exports
the session as a JUnit fixture. The live query is untouched throughout — if it looks wrong, an
open session is not why. See [the time-travel debugger](/help/topics/time-travel-debugger).

### Plugins

`/plugins` is every plugin the node can load, from the engine's `GET /api/v1/plugins`: its version,
the plugin API it needs and whether this engine can host it, whether its code can be a source, a
sink or a lookup, the capabilities it declares, the setting names in its manifest, and its health —
shown as *not reported* where no live instance said so, never as healthy. Its bindings are listed
as the engine reports them to this identity, never with their options (which may hold passwords).

### Admin

| Route | What it does |
|---|---|
| `/admin/access` | What the engine's policy lets you do: register, read the audit trail, and for every view and stream, full or row-filtered reading and whether you may drop, pause or resume. Read-only — the grants themselves are edited under Grants |
| `/admin/audit` | The engine's audit trail, newest first, 50 to a page, filtered by `principal`, `view`, `action`, `decision` and a UTC window (`since`, `until`) — all in the URL. When the engine refuses the console's identity, a designed **Not permitted** state with the engine's reason |
| `/admin/tenants` | Each tenant's names, computations and view keys against its quotas, and the registrations refused for each quota since the node started. A limit that is not set reads *no limit*; zero is a limit. Every tenant when you may read the audit trail, otherwise your own |
| `/admin/users` | The engine's accounts ([authentication](/help/topics/authentication)): add a person, their roles, a password reset, disable (never delete — the audit trail names owners by id), and their **attributes** (`region=EU`), which a [row filter](/help/topics/row-filters-and-masks) reads as claims |
| `/admin/keys`, `/admin/sessions` | Every API key the engine holds, by key id (the secret is never shown), with revoke; everyone signed in, with end-session. Your own keys and sessions are on **Account** |
| `/admin/grants` | The [catalogue's](/help/topics/catalog-and-grants) grants: who holds which privilege on which object, granted and revoked here. Anyone may open it; the engine decides what each person may change |
| `/admin/policies` | [Row filters and masks](/help/topics/row-filters-and-masks): define one, bind it to a stream, a view or a tag, unbind, drop |
| `/admin/lanes` | Which lane each query runs on — its own, dedicated, or shared lane *n* — how full each shared lane is, and **rebalance**: preview the plan, then move shared queries onto lanes of their own one at a time ([lanes](/help/topics/lanes#sharing-lanes)) |
| `/admin/ai-models` | The [assistant's](/help/topics/assistant#admin-ai-models) providers, models, fallback chains and budgets, switched while the console runs |

Reading the trail is itself audited.

### Help, everywhere

- Every screen has a **?** beside its title, opening the help topic for that screen, and up to three
  help cards at the bottom for the questions it usually raises.
- Every `PRV-nnnn` anywhere in the console is a link to that code's page.
- **Ctrl-K / ⌘K** opens a command palette on every page: jump to any query, view, stream or page;
  pause or resume a query (only the actions its state allows); open a query in the workbench;
  switch role. Drop is never run from the palette — it goes to the query's page, where the
  typed-name confirmation is.

### When the engine is down

The console starts anyway and says so on every page. Each screen renders what it can and names what
is missing: the workbench keeps your drafts and edits without validation, operations shows the
registry without metrics, a view's page says which call failed.

!!! note "What the engine does not publish yet"
    Commit-latency percentiles, the live health of a classpath plugin, per-plugin throughput, and
    grants to edit. Each screen says so where the number would be, rather than drawing a zero.
    Lane backpressure and per-operator numbers **are** published now — see
    [reading the numbers on a plan](/help/topics/reading-a-plan) — but per-operator counting is
    off by default because it costs about 8 %, and a node with it off says so rather than drawing
    an empty graph.

### Settings that change what you see

| Setting | Default | Effect |
|---|---|---|
| `engine.url` | `grpc://localhost:19090` | the engine's Flight endpoint |
| `engine.http_url` | `http://localhost:18080` | the engine's HTTP surface; without it validation and plans are unavailable |
| `engine.pgwire` | `localhost:5432` | shown in the `psql` snippets only |
| `ui.default_role` | `operator` | where a signed-in person lands until they choose |
| `ui.tail_buffer` | 256 | changes held per browser on a live view |
| `ui.query_row_limit` | 500 | rows a one-off or point query returns to a browser |
| `ui.lag_warn_seconds` | 300 | watermark lag that becomes a finding |

These are the console's own settings (in the console's `config/application.yaml`), not the
engine's.

## Where next

- [Clients and SDKs](/help/topics/clients) — the CLI, both SDKs, Flight SQL, psql, HTTP, embedded
- [Streams](/help/topics/streams) — every field of a stream declaration
- [Views, keys and point reads](/help/topics/views-and-keys) and [subscriptions](/help/topics/subscriptions)
- [Windows](/help/topics/windows) and [event time and watermarks](/help/topics/event-time-watermarks)
- [The filesystem source](/help/topics/source-filesystem) — every option, including `op.column`
- [Observability](/help/topics/observability) — the numbers behind Operations
- The long form: [Quick start](/help/quickstart) and [Concepts](/help/concepts)
