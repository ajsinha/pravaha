# Tutorial 2 — Following a view

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

In [Tutorial 1](01-your-first-maintained-view.md) you asked for the answer each time you wanted it.
Here you have every change pushed to you instead: a subscription that starts with a snapshot of the
view, a copy of a view kept exact by applying weights, filters applied at the server, what happens
when a subscriber falls behind, and every way a subscription ends. About fifteen minutes.

Every output shown is what a node printed when this tutorial was run straight after Tutorial 1 on a
fresh QA install. Frontiers and ids on your run will differ; the rows will not.

## Before you start

- Tutorial 1 done, so `big_payments` and `spend_per_minute` are registered and `txn.csv` holds
  eight rows. (Starting here instead? Run Tutorial 1's Steps 3 and 6; the rows you see will differ.)
- The same `client` as Tutorial 1, created the same way.
- **Two terminals**: a subscription blocks while it waits for changes, so run the Python in one and
  append rows from the other. Each step says which rows to append.

## Step 1 — a snapshot, then every change

```python
for n, batch in enumerate(client.subscribe("big_payments", snapshot=True)):
    print("snapshot" if batch.snapshot else "commit", batch.frontier, batch.dropped_before)
    for row in batch:
        print("  ", row.weight, row.to_dict())
    if n == 1:
        break
```

It prints the snapshot at once and then waits. In the other terminal:

```bash
echo "9,u5,acme,2500,USD,OK,2026-09-26T09:02:20Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```text
snapshot 1790413286000000000 0
   1 {'txn_id': 3, 'user_id': 'u1', 'amount': 3000}
   1 {'txn_id': 4, 'user_id': 'u3', 'amount': 12000}
   1 {'txn_id': 5, 'user_id': 'u2', 'amount': 5000}
   1 {'txn_id': 6, 'user_id': 'u1', 'amount': 7000}
   1 {'txn_id': 7, 'user_id': 'u4', 'amount': 8000}
commit 1790413340000000000 0
   1 {'txn_id': 9, 'user_id': 'u5', 'amount': 2500}
```

**What just happened.** With `snapshot=True` the first batch is the whole view, and every batch
after it is one **commit** — the unit in which a view changes; you never receive half of one. The
snapshot and the commits are cut at the same point, so nothing is missed between them and nothing
is counted twice. Subscribing *without* a snapshot and reading the view separately can lose the
commit in flight between the two, silently — `snapshot=True` exists to close that gap.

`frontier` is the commit's position in event time, in nanoseconds; `dropped_before` is how many
changes the server had to drop before this batch, which Step 4 is about. Every row carries a
**weight**: `+1` a row appeared, `-1` a row was withdrawn.

## Step 2 — keep a copy by weights

A filter's answer only grows, so every weight so far has been `+1`. An answer that *changes* shows
what weights are for. Register the biggest payment per user — a maintained top-N:

```python
client.register("top_payment",
    "SELECT user_id, txn_id, amount, rn FROM ("
    "  SELECT user_id, txn_id, amount,"
    "         ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY amount DESC) AS rn"
    "  FROM txn"
    ") WHERE rn = 1",
    key_columns=[0])
```

Now keep a copy of it. **Apply the weights; never count rows:**

```python
from collections import Counter

copy = Counter()
for n, batch in enumerate(client.subscribe("top_payment", snapshot=True, overflow="FAIL")):
    for row in batch:
        copy[tuple(row.to_dict().items())] += row.weight
        print("  ", "snapshot" if batch.snapshot else "commit", row.weight, row.to_dict())
    live = sorted((dict(k) for k, c in copy.items() if c > 0), key=lambda r: r["user_id"])
    if n == 1:
        break
for r in live:
    print(r)
```

While it waits, u2 makes a bigger payment than their 5000:

```bash
echo "10,u2,globex,9500,USD,OK,2026-09-26T09:02:30Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```text
   snapshot 1 {'user_id': 'u1', 'txn_id': 6, 'amount': 7000, 'rn': 1}
   snapshot 1 {'user_id': 'u2', 'txn_id': 5, 'amount': 5000, 'rn': 1}
   snapshot 1 {'user_id': 'u3', 'txn_id': 4, 'amount': 12000, 'rn': 1}
   snapshot 1 {'user_id': 'u4', 'txn_id': 7, 'amount': 8000, 'rn': 1}
   snapshot 1 {'user_id': 'u5', 'txn_id': 9, 'amount': 2500, 'rn': 1}
   commit -1 {'user_id': 'u2', 'txn_id': 5, 'amount': 5000, 'rn': 1}
   commit 1 {'user_id': 'u2', 'txn_id': 10, 'amount': 9500, 'rn': 1}
{'user_id': 'u1', 'txn_id': 6, 'amount': 7000, 'rn': 1}
{'user_id': 'u2', 'txn_id': 10, 'amount': 9500, 'rn': 1}
{'user_id': 'u3', 'txn_id': 4, 'amount': 12000, 'rn': 1}
{'user_id': 'u4', 'txn_id': 7, 'amount': 8000, 'rn': 1}
{'user_id': 'u5', 'txn_id': 9, 'amount': 2500, 'rn': 1}
```

**What just happened.** An update is a `-1` of the old row and a `+1` of the new one, in one commit.
The copy that sums weights holds exactly the view: u2's top payment is now 9500. A consumer that
treated each row as an insert would now hold two "top" payments for u2. That is the whole reason to
apply weights — and a commit can contain a row that appears and disappears within it, as the
[telecom study](../../examples/case-studies/telecom-cdr-fraud/README.md) shows, which only a sum
gets right.

> A top-N holds every row of each partition, because withdrawing the top row needs the next one.
> Here that is every payment per user, up to 1,000,000 rows across users, past which the query stops
> with `PRV-4001`. On a real feed, put a window column in the `PARTITION BY`.

## Step 3 — filter at the server

A subscriber that wants one user's changes asks for them; the rest never cross the network:

```python
for batch in client.subscribe("big_payments", {"user_id": "u1"}, buffer_rows=1000, overflow="FAIL"):
    print([(row.weight, row.to_dict()) for row in batch])
    break
```

Append two payments in one go, one from u1 and one from u3:

```bash
printf '%s\n' "11,u1,acme,1200,USD,OK,2026-09-26T09:02:40Z" "12,u3,initech,4000,EUR,OK,2026-09-26T09:02:45Z" \
  | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```text
[(1, {'txn_id': 11, 'user_id': 'u1', 'amount': 1200})]
```

u3's payment was in the same commit and was not sent. Filters are equality on the view's columns.
A column the view does not have is refused rather than ignored:

```python
from pravaha.client import QueryError

try:
    for batch in client.subscribe("big_payments", {"merchant": "acme"}):
        break
except QueryError as e:
    print(e.engine_code)
    print(str(e))
```

```text
PRV-8002
PRV-1041  PRV-8002  this view has no column 'merchant', so that filter cannot be applied to it. Its columns are [txn_id, user_id, amount]. A filter that was quietly ignored would leave you receiving everything while believing you asked for a slice
```

## Step 4 — falling behind: `buffer_rows` and `overflow`

The server buffers changes for each subscriber. When a subscriber reads too slowly and the buffer
fills, `overflow` says what the server does:

| `overflow` | What happens | Use it when |
|---|---|---|
| `"CONFLATE"` (the default, 10 000 rows) | Keep only the latest change per key | You want the current state and can skip intermediate values — a dashboard |
| `"DROP_OLDEST"` | Drop the oldest buffered changes | Recent changes matter more than old ones |
| `"FAIL"` | End the subscription with `PRV-6105` | You keep a copy, as in Step 2 — a dropped weight is a copy gone wrong |

With `CONFLATE` or `DROP_OLDEST`, `batch.dropped_before` (or `batch.missed_anything()`) tells you it
happened. With `FAIL`, the stream ends and you **subscribe again with `snapshot=True`**, which gives
you a fresh, exact starting point. That is why Step 2 asked for `overflow="FAIL"`: a copy built from
weights must see every weight or none. This tutorial does not provoke an overflow — it takes a
subscriber much slower than a Python loop — so there is no output to show for it.

## Step 5 — following a windowed view

A subscription to a windowed view receives each window's rows when the window closes, as one commit:

```python
for batch in client.subscribe("spend_per_minute"):
    for row in batch:
        print(row.weight, row["user_id"], row["minute_start"].isoformat(), row["total"], row["txns"])
    break
```

The 09:02 minute has been filling up since Tutorial 1 (payments 8 to 12). A payment from 09:03:30
moves the watermark past 09:03:00 and closes it:

```bash
echo "13,u4,hooli,300,USD,OK,2026-09-26T09:03:30Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```text
1 u5 2026-09-26T09:02:00+00:00 2500 1
1 u1 2026-09-26T09:02:00+00:00 1200 1
1 u3 2026-09-26T09:02:00+00:00 4000 1
1 u2 2026-09-26T09:02:00+00:00 9540 2
```

u2's two payments in that minute — 40 and 9500 — arrive already summed. Nothing about the minute was
sent until it was final.

## Step 6 — how a subscription ends

A subscription does not end on its own. It ends when:

| What happened | How you see it |
|---|---|
| You stopped iterating (`break`) or closed the client | Nothing: that is the normal ending |
| The view was dropped | `QueryError` with `PRV-8018` |
| A replacement cut over to a new version of the query | `QueryError` with `PRV-4019` — [Tutorial 3](03-changing-a-running-query.md) shows it |
| You fell behind with `overflow="FAIL"` | `QueryError` with `PRV-6105` |

Drop `top_payment` from a second client while the first follows it:

```python
import threading

other = connect(options=ClientOptions.create(
    os.environ["PRAVAHA_URL"], token=os.environ["PRAVAHA_TOKEN"],
    http_url=os.environ["PRAVAHA_HTTP_URL"], allow_insecure_token=True))
threading.Thread(target=lambda: (time.sleep(2), other.drop("top_payment")), daemon=True).start()

try:
    for batch in client.subscribe("top_payment"):
        print("got", len(batch))
except QueryError as e:
    print(e.engine_code)
    print(str(e))
```

(`import time` first, if your shell does not have it.)

```text
PRV-8018
PRV-1041  PRV-8018  'top_payment' has been dropped and no longer exists on this node, so there are no more changes to it. What you received up to here is complete.
```

Each ending other than your own says what you received is complete up to that point, so a consumer
can tell "the stream ended" from "the network dropped" and act on each: resubscribe after a
cutover or an overflow, stop after a drop, retry after a `ConnectError`.

## The same thing without Python

The `pravaha` CLI prints each change with its weight, and closes each commit with a `-- commit` line:

```bash
pravaha subscribe --url grpc://qa-vm:19090 --token "$PRAVAHA_TOKEN" --insecure-token \
                  --view big_payments --snapshot --filter user_id=u1 --limit 2
```

```text
subscribed to big_payments {user_id=u1}; the view's rows print first, then changes print as they are committed. Ctrl-C to stop.
WEIGHT	txn_id	user_id	amount
+1	3	u1	3000
+1	6	u1	7000
-- snapshot at frontier 1790413460000000000, 2 rows
```

(That output was taken after Tutorial 3, which is why u1's 1200 is not there: by its end
`big_payments` answers `amount > 2000`. Run straight after Step 3 it lists 3, 6 and 11.)

## Where to read more

[The Python guide, §12](../PYTHON_API_GUIDE.md#12-subscribing) is the reference for `subscribe` and
`ChangeBatch`; [§5](../PYTHON_API_GUIDE.md#5-patterns-that-hold-up) has the copy-keeping pattern in
the form to paste into an application.

## Next

[Tutorial 3 — Changing a running query safely](03-changing-a-running-query.md): change what
`big_payments` computes without taking the answer away from anyone reading it.
