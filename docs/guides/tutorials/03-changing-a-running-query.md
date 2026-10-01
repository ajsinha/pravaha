# Tutorial 3 — Changing a running query safely

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

`big_payments` answers "payments over 1000", and people are reading it. Now the threshold should be
5000. Dropping the query and registering it again would take the answer away from every reader and
start the new one empty. A **replacement** instead runs the new version beside the old one, fills it
from the source's history, and moves the name across only when you say so — with the old version
kept for an instant rollback. This tutorial walks the whole life of one, including what a `FAILED`
replacement means and what to do about it. About twenty minutes.

Every output shown is what a node printed when this tutorial was run straight after Tutorials 1 and
2 on a fresh QA install. Fingerprints, positions and timestamps on your run will differ.

## Before you start

- Tutorial 1 done, so `big_payments` is registered (Tutorial 2 adds rows 9 to 13; with or without
  them the steps are the same, and the row lists differ).
- The same `client` as Tutorial 1. Replacing needs the *administer* permission on the name, which
  the QA host's `qa` token has.

## Step 1 — look at what is running

```python
d = client.describe_query("big_payments")
print(d["sql"], d["fingerprint"], d["sharedWith"])
```

```text
SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000 5f53eca3121a []
```

`sharedWith` matters: a replacement moves **one name**, so it is refused (`PRV-8003`) while another
name shares the computation — its readers could not be told which version they were following.
Drop or replace the others first.

## Step 2 — start the replacement

```python
rep = client.replace("big_payments",
                     "SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000",
                     key_columns=[0], backfill="history", rate_limit=10000, cutover="manual")
print(rep.state, rep.candidate, rep.replacing)
```

```text
BACKFILLING 85c374b83ebf 5f53eca3121a
```

`candidate` is the new version's fingerprint, `replacing` the one serving the name now.

| Option | Here | Meaning |
|---|---|---|
| `backfill` | `"history"` | Replay the source's history into the new version. `"none"` starts it empty at the live position — only right when the answer does not depend on history |
| `rate_limit` | `10000` | A ceiling in records per second for the backfill, so it cannot starve the live query. It can be lowered while running (`throttle_backfill`), never raised |
| `cutover` | `"manual"` | Wait for you. `"auto"` cuts over as soon as it has caught up |
| `rollback_retention` | default `PT1H` | How long the old version is kept after cutover for an instant rollback |

## Step 3 — watch the backfill

```python
import time

while (r := client.replacement("big_payments")).state == "BACKFILLING":
    time.sleep(1)
print(r.state, r.history_rows, r.partitions_live, r.history_complete, r.lag_nanos)
print("old:", [row["txn_id"] for row in client.query("SELECT txn_id FROM big_payments")])
```

```text
CAUGHT_UP 13 1 True 15000000000
old: [3, 4, 5, 6, 7, 9, 10, 11, 12]
```

**What just happened.** The candidate read all thirteen rows of history (`history_rows`), reached
the live position on the source's one partition (`partitions_live`), and is now following the live
stream beside the old version: `CAUGHT_UP`. `lag_nanos` is how far its event time trails the live
query's, here fifteen seconds of event time, not of waiting. **The name still answers the old
version** — `amount > 1000` — and nobody reading it has noticed anything.

> **Poll for `CAUGHT_UP` or `FAILED`, never `CAUGHT_UP` alone.** A backfill that cannot finish
> becomes `FAILED` (Step 7). A loop waiting only for `CAUGHT_UP` would wait for ever.

## Step 4 — cut over

Have someone following `big_payments` while you cut over. In a second Python shell:

```python
for batch in client.subscribe("big_payments"):
    pass
```

Then, in the first:

```python
c = client.cut_over("big_payments")
print(c.state, c.rollback_available, c.rollback_until)
print("new:", [row["txn_id"] for row in client.query("SELECT txn_id FROM big_payments")])
print(client.replacement_http("big_payments")["history"])
```

```text
CUT_OVER True 2026-09-27T05:13:59.439642078Z
new: [4, 6, 7, 10]
['from the beginning: 5f53eca3121a', 'from 1790482439425: 85c374b83ebf']
```

And the subscriber in the second shell ends with:

```text
pravaha.client.QueryError: PRV-1041  PRV-4019  'big_payments' was replaced at a cutover and now answers a different query. Every change the version you were following made has been delivered; subscribe again to follow the new one from a fresh snapshot.
```

**What just happened.** The name moved to the new version at a point where both had consumed exactly
the same input, so a reader sees the old answer up to the seam and the new one after it — never a
mixture. `history` records every version that has served the name and where it took over. A
subscriber is told, because the view it was following now answers a different question; it
resubscribes with `snapshot=True` and carries on. `rollback_until` is an hour away.

## Step 5 — roll back

Both versions keep running during the rollback window. Add a payment of 1500 — above the old
threshold, below the new — and look:

```bash
echo "14,u6,acme,1500,USD,OK,2026-09-26T09:03:40Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```python
print("new+14:", [row["txn_id"] for row in client.query("SELECT txn_id FROM big_payments")])
print(client.roll_back("big_payments").state)
print("old again:", [row["txn_id"] for row in client.query("SELECT txn_id FROM big_payments")])
```

```text
new+14: [4, 6, 7, 10]
ROLLED_BACK
old again: [3, 4, 5, 6, 7, 9, 10, 11, 12, 14]
```

The rollback is instant and loses nothing: the old version saw payment 14 while it was standing by,
so the answer it hands back is current, not the one it had at cutover.

## Step 6 — replace again, and finish

```python
client.replace("big_payments", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000", key_columns=[0])
while (r := client.replacement("big_payments")).state == "BACKFILLING":
    time.sleep(1)
print(r.state)
print(client.cut_over("big_payments").state)
f = client.finish_replacement("big_payments")
print(f.state, f.rollback_available)

from pravaha.client import QueryError
try:
    client.roll_back("big_payments")
except QueryError as e:
    print(e.engine_code)
    print(str(e))
```

```text
CAUGHT_UP
CUT_OVER
FINISHED False
PRV-8003
PRV-1041  PRV-8003  'big_payments' is FINISHED and there is nothing to roll back: the version it replaced has been released, which is what finishing a replacement means
```

`finish_replacement` confirms the cutover and releases the old version's state at once, rather than
at the end of the rollback window. After it there is no way back but another replacement.
`abandon_replacement` is the other exit: it ends a replacement that has not cut over, and the name
keeps its version.

## Step 7 — when a replacement fails

A backfill that cannot finish makes the replacement `FAILED`. The name goes on answering the version
it answered; the candidate is released; `failure_code` and `failure` say why.

> **Version note.** This step shows **0.1.2**, the build on QA hosts today, where one cause is easy to
> meet. From **0.1.3** it is fixed (REPL-2): the same replacement reaches `CAUGHT_UP`, and after the
> cutover the view holds every good row exactly once. On 0.1.3 and later, read this step for what
> `FAILED` means and what to do; the remaining causes are in the table below.

One cause on 0.1.2 is easy to meet: **the last record the running version read was
dead-lettered**. A malformed row — here, an amount in words — is set aside on the dead-letter queue
and the source reads on:

```bash
echo "15,u7,acme,twelve-hundred,USD,OK,2026-09-26T09:03:50Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```python
client.replace("big_payments", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 2000", key_columns=[0])
t0 = time.time()
while (r := client.replacement("big_payments")).state == "BACKFILLING":
    time.sleep(2)
print(round(time.time() - t0), r.state, r.failure_code)
print(r.failure)
print("still:", [row["txn_id"] for row in client.query("SELECT txn_id FROM big_payments")])
```

```text
30 FAILED PRV-4013
PRV-4013  the backfill read all the history this source has and never reached the position the running version is at (16). A source whose positions do not name the record they were taken after cannot be spliced at an offset: reading past the seam would deliver the overlap twice. The backfill is stopped rather than doubling it.
still: [4, 6, 7, 10]
```

**What `FAILED` means.** The running version's position is line 16 — the malformed row. The
backfill's decoder set that row aside too, so its history ends one record short of the seam, and
splicing at an offset it never reached could deliver rows twice. It waited thirty seconds for the
position to appear, then stopped rather than produce a wrong answer. Nothing else changed: the name
answers `amount > 5000` exactly as before.

**What to do.** Read `failure_code`:

| Code | Cause | Do this |
|---|---|---|
| `PRV-4013` | The backfill could not reach the running version's position — on 0.1.2, as here, when the record at that position was dead-lettered (fixed in 0.1.3) | Wait until a good record has arrived after it, then replace again |
| The source's own code | The backfill's source stopped — the file went away, the table was dropped, the credentials were revoked | Fix the source, then replace again |

Here, one good payment moves the position past the bad one:

```bash
echo "16,u7,acme,1200,USD,OK,2026-09-26T09:03:55Z" | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```python
client.replace("big_payments", "SELECT txn_id, user_id, amount FROM txn WHERE amount > 2000", key_columns=[0])
while (r := client.replacement("big_payments")).state == "BACKFILLING":
    time.sleep(1)
print(r.state)
print(client.cut_over("big_payments").state, client.finish_replacement("big_payments").state)
print("now:", [row["txn_id"] for row in client.query("SELECT txn_id FROM big_payments")])
```

```text
CAUGHT_UP
CUT_OVER FINISHED
now: [3, 4, 5, 6, 7, 9, 10, 12]
```

The dead-lettered row is still on the queue. [Tutorial 4](04-investigating-an-incident.md) is about
what to do with it.

## The same thing without Python

```bash
P="--url grpc://qa-vm:19090 --token $PRAVAHA_TOKEN --insecure-token"
pravaha replace      $P --name big_payments --sql-file new.sql --keys 0 --cutover manual --wait
pravaha replacements $P --name big_payments
pravaha cutover      $P --name big_payments      # or: rollback, finish, abandon
```

`pravaha replacements` prints a line per replacement; after Step 7 it reads:

```text
NAME	STATE	HISTORY	ROWS/S	LIVE	ROLLBACK
big_payments	FINISHED	15	0	1/1	-
```

In the console, a query's replacement screen — `/queries/big_payments/replacement` — starts one,
shows its backfill as it runs, and cuts over. `CREATE OR REPLACE CONTINUOUS QUERY` over Flight SQL
starts a replacement too.

## Where to read more

[The Python guide, §14](../PYTHON_API_GUIDE.md#14-changing-a-running-query-replacement) is the
reference for every replacement call and option;
[`CONTINUOUS_QUERIES.md`](../CONTINUOUS_QUERIES.md) §8.1 describes how the seam is found.

## Next

[Tutorial 4 — Investigating an incident](04-investigating-an-incident.md): the malformed row from
Step 7, and a debugger that replays a query row by row from a checkpoint.
