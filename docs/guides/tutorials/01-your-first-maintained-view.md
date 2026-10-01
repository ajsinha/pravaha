# Tutorial 1 — Your first maintained view

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

In this tutorial you ask Pravaha one question, once, and watch it keep the answer current. You
register a query, read its answer, append a row to the source and see the answer move, then write a
windowed aggregate and learn why its rows appear only when a window closes. It takes about fifteen
minutes.

Every output shown is what a node printed when this tutorial was run against a fresh QA install.
Fingerprints, frontiers and timestamps of your own run may differ; the rows will not.

## Before you start

- **A fresh QA install**, started: `sudo ./install.sh` from the bundle, then
  `cd /opt/pravaha && docker compose up -d`. It declares one stream, `txn`, fed from
  `/opt/pravaha/data/incoming/txn.csv` with six rows, and a sink called `large_payments`.
- **The Python SDK**, installed from the bundle's wheel:
  `pip install "dist/pravaha-<version>-py3-none-any.whl[flight]"` — see
  [the Python guide, §2](../PYTHON_API_GUIDE.md#2-install-the-sdk).
- **The engine token** `install.sh` printed (`id: qa`), exported as `PRAVAHA_TOKEN`, and the host in
  `PRAVAHA_URL` and `PRAVAHA_HTTP_URL`:

```bash
export PRAVAHA_TOKEN=<the qa token>
export PRAVAHA_URL=grpc://qa-vm:19090 PRAVAHA_HTTP_URL=http://qa-vm:18080
```

Every step uses one `client`, created once in a Python shell and kept:

```python
import os
from pravaha import connect, ClientOptions

client = connect(options=ClientOptions.create(
    os.environ.get("PRAVAHA_URL", "grpc://qa-vm:19090"),        # Flight: queries, views, subscriptions
    token=os.environ["PRAVAHA_TOKEN"],
    http_url=os.environ.get("PRAVAHA_HTTP_URL", "http://qa-vm:18080"),   # HTTP: the catalogue, plans
    allow_insecure_token=True,     # the QA host serves plaintext; remove this once it serves TLS
))
```

`allow_insecure_token=True` is there because the QA host serves plaintext and the SDK otherwise
refuses to send a token over it. [The guide's §3](../PYTHON_API_GUIDE.md#3-connect) explains every
option.

## Step 1 — see what can be asked about

A query is written over a **stream**: a named, typed flow of rows the engine's configuration
declares. Ask the node which it has:

```python
for s in client.streams():
    print(s["name"], s["source"], s["eventTime"], s["outOfOrderness"])
    print([f"{f['name']} {f['type']}" for f in s["fields"]])
```

```text
txn filesystem event_time PT10S
['txn_id INT64 NOT NULL', 'user_id VARCHAR NOT NULL', 'merchant VARCHAR NOT NULL', 'amount INT64 NOT NULL', 'currency VARCHAR NOT NULL', 'status VARCHAR', 'event_time TIMESTAMP(9) WITH LOCAL TIME ZONE NOT NULL']
```

One stream, `txn`, fed by the `filesystem` plugin. `event_time` is its **event time** — the column
windows are cut on — and a row may arrive up to ten seconds (`PT10S`) out of order. Both matter in
Step 6. The file behind it starts with six payments:

```text
txn_id,user_id,merchant,amount,currency,status,event_time
1,u1,acme,150,USD,OK,2026-09-26T09:00:01Z
2,u2,globex,90,USD,OK,2026-09-26T09:00:05Z
3,u1,acme,3000,USD,OK,2026-09-26T09:00:30Z
4,u3,initech,12000,EUR,,2026-09-26T09:01:10Z
5,u2,globex,5000,USD,OK,2026-09-26T09:01:20Z
6,u1,acme,7000,USD,OK,2026-09-26T09:01:25Z
```

## Step 2 — check the question before you ask it

`validate` plans a query without running it, and says what its answer will look like:

```python
sql = "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000"
v = client.validate(sql)
print(v["valid"], [(f["name"], f["type"]) for f in v["outputFields"]])
```

```text
True [('txn_id', 'INT64 NOT NULL'), ('user_id', 'VARCHAR NOT NULL'), ('amount', 'INT64 NOT NULL')]
```

## Step 3 — ask once

```python
q = client.register("big_payments", sql, key_columns=[0])
print(q.name, q.state, q.fingerprint)
```

```text
big_payments RUNNING 5f53eca3121a
```

**What just happened.** The node planned the query, started a computation, and began reading `txn`
from the beginning of the file. From now until it is dropped it keeps an answer called
`big_payments` — a **view** — current. `key_columns=[0]` says the first column, `txn_id`, identifies
a row of the answer. The **fingerprint** identifies the question itself: register the same question
under another name and you get a second name on the same computation, not a second computation.

## Step 4 — read the answer

A view is read with ordinary SQL:

```python
for row in client.query("SELECT * FROM big_payments"):
    print(row.to_dict())
print(client.query("SELECT txn_id, amount FROM big_payments WHERE user_id = ?", ["u1"]).to_list())
```

```text
{'txn_id': 3, 'user_id': 'u1', 'amount': 3000}
{'txn_id': 4, 'user_id': 'u3', 'amount': 12000}
{'txn_id': 5, 'user_id': 'u2', 'amount': 5000}
{'txn_id': 6, 'user_id': 'u1', 'amount': 7000}
[{'txn_id': 3, 'amount': 3000}, {'txn_id': 6, 'amount': 7000}]
```

The read did not scan `txn`; it looked up the answer the computation already holds. Bind values with
`?` rather than formatting them into the SQL: a bound value can never be read as SQL, and the node
plans the statement once however many users you ask about.

## Step 5 — append a row and watch the answer move

On the QA host the source file belongs to the engine's user, so append with `sudo`:

```bash
echo "7,u4,hooli,8000,USD,OK,2026-09-26T09:01:26Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

Read again:

```python
for row in client.query("SELECT * FROM big_payments"):
    print(row.to_dict())
```

```text
{'txn_id': 3, 'user_id': 'u1', 'amount': 3000}
{'txn_id': 4, 'user_id': 'u3', 'amount': 12000}
{'txn_id': 5, 'user_id': 'u2', 'amount': 5000}
{'txn_id': 6, 'user_id': 'u1', 'amount': 7000}
{'txn_id': 7, 'user_id': 'u4', 'amount': 8000}
```

**What just happened.** The source follows the file as it grows. The new row went through the filter
once, and the view gained one row. Nothing re-ran the query over the six rows before it — that is
what *incremental* means, and it is why the answer is current within about a second however large
the history is.

### And write the answer somewhere as well

A query can also write its answer to a **sink** — a file, a table, a topic — bound in the engine's
configuration. The QA host binds one, `large_payments`, a CSV file of `txn_id,user_id,amount`:

```python
out = client.register("whale_payments",
                      "SELECT txn_id, user_id, amount FROM txn WHERE amount >= 10000",
                      key_columns=[0], sink="large_payments")
print(out.name, out.state, out.fingerprint, out.sink)
print(client.describe_query("whale_payments")["sink"])
```

```text
whale_payments RUNNING 0832259916d1 large_payments
{'name': 'large_payments', 'attached': True, 'failure': None, 'rowsWritten': 1}
```

```bash
sudo cat /opt/pravaha/data/outgoing/large_payments.csv
# 4,u3,12000
```

A filter's answer only ever grows, so an append-only file can hold it. A query whose answer can be
revised — Step 6's is one, once lateness is allowed — needs a sink that accepts retractions, and the
engine refuses the other combination at registration (`PRV-2041`) rather than write a file that is
wrong.

## Step 6 — a windowed aggregate

Now a question with arithmetic in it: how much did each user spend in each minute?

```python
client.register("spend_per_minute",
    "SELECT user_id, TUMBLE_START(event_time, INTERVAL '1' MINUTE) AS minute_start, "
    "SUM(amount) AS total, COUNT(*) AS txns FROM txn "
    "GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id",
    key_columns=[0, 1])

for row in client.query("SELECT * FROM spend_per_minute"):
    print(row["user_id"], row["minute_start"].isoformat(), row["total"], row["txns"])
print(client.query_plan("spend_per_minute")["query"]["watermark"])
```

```text
u2 2026-09-26T09:00:00+00:00 90 1
u1 2026-09-26T09:00:00+00:00 3150 2
2026-09-26T09:01:16Z
```

Seven payments have been read, but only the 09:00 minute is in the answer. The 09:01 minute — u3's
12000, u2's 5000, u1's 7000, u4's 8000 — is missing. That is not a delay; it is the design.

**Why the rows appear when the window closes.** A minute's total is only final once no more payments
for that minute can arrive. The engine tracks a **watermark**: "nothing earlier than this is still
coming", taken from the event times it has read *less the stream's ten seconds of out-of-orderness*.
The latest payment is at 09:01:26, so the watermark is 09:01:16 — past the end of 09:00, which is
published, and short of the end of 09:01, which is still open. Publishing 09:01 now would mean
publishing a number that the next row could make wrong.

Move event time on by appending a payment from the next minute:

```bash
echo "8,u2,globex,40,USD,OK,2026-09-26T09:02:15Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```python
for row in client.query("SELECT * FROM spend_per_minute"):
    print(row["user_id"], row["minute_start"].isoformat(), row["total"], row["txns"])
print(client.query_plan("spend_per_minute")["query"]["watermark"])
```

```text
u2 2026-09-26T09:00:00+00:00 90 1
u1 2026-09-26T09:00:00+00:00 3150 2
u1 2026-09-26T09:01:00+00:00 7000 1
u4 2026-09-26T09:01:00+00:00 8000 1
u3 2026-09-26T09:01:00+00:00 12000 1
u2 2026-09-26T09:01:00+00:00 5000 1
2026-09-26T09:02:05Z
```

The watermark passed 09:02:00, so the 09:01 minute closed and its four rows were published together.
Payment 8 itself sits in the 09:02 minute, which is now the open one. On a live feed this is
continuous: each minute appears about ten seconds after it ends.

## Step 7 — what the engine refuses, and why

Drop the window and ask for spend per user, for ever:

```python
v = client.validate("SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id")
print(v["valid"], v["diagnostics"][0]["code"])
print(v["diagnostics"][0]["message"])
```

```text
False PRV-2050
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of distinct keys and never shrinks. One row per key is fine at a thousand keys and fatal at a hundred million, and the failure arrives weeks after deployment.
  Bound it with a window -- GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id -- so state is released when each window closes.
Refusing now rather than exhausting memory later.
```

The window in Step 6 is not decoration: it is what lets the engine forget a minute once it is
published. `register` refuses the same query with the same code. The full list of what runs and what
is refused is [`CONTINUOUS_QUERIES.md`](../CONTINUOUS_QUERIES.md).

## The same thing without Python

- **The console.** *Workbench* validates and registers a query, and *Queries* lists `big_payments`
  with its state and fingerprint.
- **The command line.** The `pravaha` CLI takes the token with `--token` and, on a plaintext QA
  host, `--insecure-token`:

```bash
pravaha queries --url grpc://qa-vm:19090 --token "$PRAVAHA_TOKEN" --insecure-token
pravaha query   --url grpc://qa-vm:19090 --token "$PRAVAHA_TOKEN" --insecure-token \
                --sql "SELECT user_id, total, txns FROM spend_per_minute WHERE user_id = 'u2'"
```

## What you have now

Three registered queries — `big_payments`, `whale_payments` and `spend_per_minute` — and eight rows
in `txn.csv`. The next tutorials build on them, so leave them running.

## Next

[Tutorial 2 — Following a view](02-following-a-view.md): instead of asking again, have every change
pushed to you — and keep an exact copy of a view that way.
