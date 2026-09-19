---
title: Your first maintained view
slug: first-view
category: start
order: 20
icon: play-circle
summary: "The whole loop on one machine: start a node, declare a stream, register a windowed query, read it from the CLI, psql and Python, subscribe, and watch a retraction arrive."
badge: WALKTHROUGH
audience: Everyone
keywords: [tutorial, quickstart, walkthrough, getting started, csv, follow, register, subscribe, psql, python, first run]
guide: quickstart
related: [start-here, streams, event-time-watermarks, zset-weights, source-filesystem, subscriptions]
---

This page takes you from an empty directory to a view you can watch changing, and shows the exact
command and what comes back at every step. It uses one machine, one CSV file that you append to by
hand, and the three ways of reading an answer — the `pravaha` CLI, `psql`, and the Python SDK.

Budget fifteen minutes the first time, most of it the build. Everything here is local and
unauthenticated, which is what the `dev` profile is for; the last section says what to change before
anybody else can reach the node.

## Before you start

| Check | Command | If missing |
|---|---|---|
| Java 21 | `java -version` | Any OpenJDK 21 |
| `JAVA_HOME` points at it | `echo $JAVA_HOME` | `export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` — distributions that ship several JDKs often leave `javac` and `java` on different versions |
| Python 3.11+ | `python3 --version` | Only for the Python step |
| `psql` | `psql --version` | Only for the PostgreSQL step |

## 1. Build, and put the launchers on your PATH

From a checkout of the repository:

```bash
./mvnw -q -DskipTests install
export PATH="$PWD/bin:$PATH"
pravaha --help
```

The build produces the engine node (`pravaha-server/target/pravaha-server-<version>-app.jar`,
launched by `bin/pravaha-server`) and the CLI (`pravaha-cli/target/pravaha-cli-<version>-cli.jar`,
launched by `bin/pravaha`). The first build takes a few minutes.

## 2. Describe the node in one file

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

## 3. Start the node

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

## 4. Register two continuous queries

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
NAME          STATE    FINGERPRINT   ROWS IN
minute_spend  RUNNING  3b9f0c2e71a4  0
big_payments  RUNNING  c05d81e6a9f2  0
```

Fingerprints are hashes of the normalised plan, so yours will be different strings. `KEYED BY`
names output columns and the engine turns them into ordinals by planning the `SELECT`, so reordering
the select list cannot quietly change what the key is. The same registration as arguments is
`pravaha register --name big_payments --sql "..." --keys 0`.

## 5. Feed it, and see why nothing appears yet

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

## 6. Read the view from the CLI

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

## 7. Read the same view from psql

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

## 8. Read and subscribe from Python

```bash
cd sdk/python && make install && . .venv/bin/activate     # from the repository root
```

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
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

with connect("grpc://localhost:9090") as client:
    for batch in client.subscribe("big_payments"):
        for row in batch:
            print(f"{row.weight:+d}", row["txn_id"], row["user_id"], row["amount"])
        print("-- commit")
```

## 9. Watch a window close

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
u3	1789808520000000000	1789808580000000000	1	60
-- commit, 1 row
```

The CLI prints the view's columns in order — `user_id`, `window_start`, `window_end`, `payments`,
`spend` — and marks the end of each commit. A subscriber never sees half a commit.

## 10. Watch a retraction

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

## 11. And a row that arrives too late

Append a payment stamped 09:00:30 — inside the first minute, which closed long ago:

```bash
echo '9,u1,m-7,500,GBP,COMPLETED,2026-09-19T09:00:30Z,I' >> txn.csv
```

`big_payments` has no window, so a 500 does not pass its filter either way. `minute_spend` does not
change: `u1`'s first minute stays at 1650. On a server-registered `TUMBLE` query the **allowed
lateness is zero**, so a row for a window that has already been published is counted as late and
dropped rather than reopening it. The same retraction arithmetic *can* correct a closed window —
a `-1` for the old total and a `+1` for the new one — when a stream declares allowed lateness, which
today only an embedder can do. The [late data](/help/topics/late-data) page shows both paths.

The deletion in step 10 had the same fate in `minute_spend`: it is stamped 09:01:12, behind the
watermark, so the closed minute keeps its 2500. Only the unwindowed `big_payments` withdrew it.

## 12. Clean up

```sql
DROP CONTINUOUS QUERY minute_spend;
DROP CONTINUOUS QUERY big_payments;
```

```bash
pravaha drop --name minute_spend
pravaha drop --name big_payments
```

```text
dropped minute_spend
dropped big_payments
```

A drop removes a **name**; the computation goes when its last name goes.

## What goes wrong on a first run

!!! warning "The view is empty and the query says RUNNING"
    No `event-time` on the stream, or no `event.time` on the source, so the watermark never
    advances. Or every row so far is within `out-of-orderness` of the newest one — append a row
    later in event time. See [event time and watermarks](/help/topics/event-time-watermarks).

!!! warning "Object 'txn' not found"
    The query names a stream the catalogue does not have. Check the `pravaha.streams` block — the
    source binding alone does not declare a stream.

!!! warning "Options at the wrong level"
    Plugin options live under `options:`. A key written one level too high is not read, not
    reported, and the node ingests nothing.

!!! warning "Connection refused on 8080 from the SDK"
    The SDKs and CLI speak Flight on **9090**. Port 8080 is the engine's HTTP API; 8090 is this
    console.

!!! danger "Before anybody else can reach it"
    The `dev` profile serves every view to anyone. Configure `pravaha.security.authentication`
    and `pravaha.security.policy` and TLS before binding to anything but loopback — see
    [authentication](/help/topics/authentication) and [TLS](/help/topics/tls).

## Where next

- [Streams](/help/topics/streams) — every field of a stream declaration
- [Windows](/help/topics/windows) and [event time and watermarks](/help/topics/event-time-watermarks)
- [Subscriptions](/help/topics/subscriptions) and [point reads](/help/topics/point-reads)
- [The filesystem source](/help/topics/source-filesystem) — every option, including `op.column`
- The long form: [Quick start](/help/quickstart)
