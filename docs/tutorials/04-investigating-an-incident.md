# Tutorial 4 — Investigating an incident

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

A customer says u5 paid acme twice in the minute from 09:04, 155.47 in all, and one of the two
payments is missing. In this tutorial you find out why. A malformed payment went to the
**dead-letter queue**; you inspect it, try to replay it, and correct it at the source. Then you use
the **time-travel debugger**: fork
the running query from a checkpoint, step it row by row, move its event time yourself to close a
window, look at its state — and export what you stepped through as a regression test. About twenty
minutes, one of which is waiting for a checkpoint.

Every output shown is what a node printed when this tutorial was run straight after Tutorials 1 to 3
on a fresh QA install. Ids, sequence numbers and timestamps on your run will differ.

## Before you start

- Tutorial 1 done, so `spend_per_minute` is registered. Tutorials 2 and 3 add rows; with or without
  them the steps are the same, but some outputs below include their rows.
- The QA host's configuration sets `pravaha.dlq.directory` (a record no source can decode is set
  aside rather than stopping the source) and takes a **checkpoint** of every query once a minute,
  keeping three. The debugger forks from checkpoints.
- The same `client` as Tutorial 1. The debugger needs the *administer* permission on the query,
  which the `qa` token has.

## Step 1 — mark a point before the incident

In a real incident the checkpoint you want already exists: the last one before things went wrong.
To stage one here, wait for the next checkpoint of `spend_per_minute` and note it:

```python
import time

seen = client.debug_checkpoints("spend_per_minute")          # newest first
while client.debug_checkpoints("spend_per_minute")[:1] == seen[:1]:
    time.sleep(5)
before = client.debug_checkpoints("spend_per_minute")[0]
print(seen, before)
```

```text
[] 1
```

(On this run the query had no checkpoint yet, so the loop waited for its first. If yours already has
some, you wait for the next one — at most a minute.)

## Step 2 — the incident

Two payments from u5 arrive. The second came from a system that sends amounts in major units:

```bash
printf '%s\n' "17,u5,acme,7777,USD,OK,2026-09-26T09:04:10Z" "18,u5,acme,77.70,USD,OK,2026-09-26T09:04:20Z" \
  | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

`amount` is an integer of minor units; `77.70` is not one. The source cannot decode the line, so it
sets it aside and reads on. Ask the dead-letter queue — first the totals, which is the call a
dashboard polls, then the entries:

```python
print(client.dead_letter_count("spend_per_minute"))
page = client.dead_letters("spend_per_minute", limit=10)
print(page.total, page.bytes, page.retention, page.has_more)
for e in page.entries:
    print(e.id, e.offset, e.code, e.replay)
    print("   ", e.reason)
    print("   ", e.raw)
```

```text
{'query': 'spend_per_minute', 'total': 2, 'bytes': 1435, 'evicted': 0, 'evictedBytes': 0, 'replayed': 0, 'failedAgain': 0, 'oldest': '2026-09-27T04:14:01.621Z', 'newest': '2026-09-27T04:14:41.760Z', 'retention': '268435456 bytes', 'configured': True}
2 1435 268435456 bytes False
7fb32ff8-bbb6-4c13-82db-dc901963011d line 19 PRV-5040 NEW
    PRV-5040  line 19, column 'amount' (INT64): '77.70' is not a number
    b'18,u5,acme,77.70,USD,OK,2026-09-26T09:04:20Z'
7e72f1b0-82f3-4ace-9fbb-c34b6a8183d7 line 16 PRV-5040 NEW
    PRV-5040  line 16, column 'amount' (INT64): 'twelve-hundred' is not a number
    b'15,u7,acme,twelve-hundred,USD,OK,2026-09-26T09:03:50Z'
```

Newest first. The second entry is the row from [Tutorial 3](03-changing-a-running-query.md)'s
Step 7: each query has its own queue, and every query reading `txn` set that line aside. Each entry
says where it was in the source's own terms (`line 19` of the file), which column failed and why,
and carries the raw bytes — which is what makes it fixable.

One entry whole, by id:

```python
e = page.entries[0]
one = client.dead_letter("spend_per_minute", e.id)
print(one.id, one.stream, one.offset, one.size, one.at, one.replay)
```

```text
7fb32ff8-bbb6-4c13-82db-dc901963011d txn line 19 44 2026-09-27T04:14:41.760Z NEW
```

## Step 3 — replay it, and why that is not the fix here

A **replay** feeds a dead letter back through the query as a new row at its current position:

```python
r = client.replay_dead_letter("spend_per_minute", e.id)
print(r.outcome, r.new_id)
print(r.detail)
```

```text
FAILED_AGAIN 323b4327-9063-413c-9d53-c331ab759ffb
the record failed to decode again and has gone back on the queue as a new entry. It is not retried: replaying this id again would decode the same bytes with the same decoder.
```

A replay decodes the **same bytes with the same decoder**, so a malformed record fails again. It
went back on the queue as a new entry and is not retried. Replay is for a record that failed for a
reason that has since gone away — a decoder or schema fixed and redeployed. A record that is simply
wrong is corrected **at the source**: the sender resends it as it should have been.

> Replay is also not idempotent: replaying an id twice puts the row in twice. And a replay is
> refused (`PRV-4092`) when the source promises exactly-once delivery and has not read past the
> record yet — it will deliver it again itself.

So the sender corrects it, and a later payment moves event time past the minute:

```bash
printf '%s\n' "19,u5,acme,7770,USD,OK,2026-09-26T09:04:20Z" "20,u8,initech,50,EUR,OK,2026-09-26T09:05:30Z" \
  | sudo tee -a /opt/pravaha/data/incoming/txn.csv >/dev/null
```

```python
n = client.dead_letter_count("spend_per_minute")
print(n["total"], n["failedAgain"])
for row in client.query("SELECT * FROM spend_per_minute WHERE user_id = ?", ["u5"]):
    print(row["user_id"], row["minute_start"].isoformat(), row["total"], row["txns"])
```

```text
3 1
u5 2026-09-26T09:02:00+00:00 2500 1
u5 2026-09-26T09:04:00+00:00 15547 2
```

u5's 09:04 minute is 155.47 over two payments — what the customer said. The correction had to arrive
**before** the minute closed: the stream allows no lateness, so a corrected row for a minute already
published would have been dropped as late.

## Step 4 — fork the query from the checkpoint

The view is right now, but the question an incident review asks is *what did the query do, row by
row?* Fork a copy of `spend_per_minute` from the checkpoint noted in Step 1:

```python
s = client.debug_fork("spend_per_minute", before)
print(s.id, s.checkpoint_id, s.view_size, s.sinks_disabled, s.streams)
```

```text
dbg-1a0e1128e51-1 1 10 True ('txn',)
```

**What just happened.** The fork restored the query's state as it was at checkpoint 1 — a view of
ten rows — and positioned its reader on `txn` at that checkpoint's offset. It reads the same source
as the live query, with **every sink disabled**, and nobody but you can read its view. The live
query, its view and its subscribers are untouched.

## Step 5 — step rows

```python
st = client.debug_step(s.id, "row")
print(st.rows_consumed, [(i.offset, i.values) for i in st.rows_in], st.view_changes, st.watermark_nanos, st.stopped)
st = client.debug_step(s.id, "rows:3")
print(st.rows_consumed, [(i.offset, i.values) for i in st.rows_in], st.view_changes, st.stopped)
for op in st.operators:
    print("   op", op.id, op.kind, op.label, op.rows_in, op.rows_out)
print(client.debug_state(s.id))
```

```text
1 [('18', ('17', 'u5', 'acme', '7777', 'USD', 'OK', '1790413450000000000'))] () None 1 row
3 [('20', ('19', 'u5', 'acme', '7770', 'USD', 'OK', '1790413460000000000')), ('21', ('20', 'u8', 'initech', '50', 'EUR', 'OK', '1790413530000000000'))] () the sources have no more rows
   op n0 Project Project[user_id, minute_start=$f0, total, txns] 0 0
   op n1 WindowedAggregate WindowedAggregate(TUMBLING 60000ms, keys=[0, 1], [SUM(amount)->total, COUNT(*)->txns]) 2 0
   op n2 Project Project[window_start, user_id, amount, window_end] 2 2
   op n3 WindowAssign WindowAssign(TUMBLING size=60000ms slide=60000ms on event_time) 2 2
   op n4 Scan Scan(txn) 2 2
[StateSlot(id='window#0', kind='window', label='windows retained', entries=0)]
```

**Reading it.** The first step read line 18 — payment 17. The next asked for three rows and got two,
lines 20 and 21, then stopped: *the sources have no more rows*. **Line 19 — the malformed payment —
never reached the query**; it was set aside before the scan, which is exactly what the dead-letter
queue said. `operators` shows each step's flow: two rows entered the windowed aggregate and nothing
left it, because no window has closed. `view_changes` is empty for the same reason.

The `step` argument takes:

| Step | Advances |
|---|---|
| `row` | one input row |
| `rows:N` | up to N rows |
| `commit` | to the next commit |
| `watermark:<nanos>` | event time, to that instant |
| `until:<column>:<op>:<value>` | until a row of the **view** satisfies it, e.g. `until:total:>:10000` |

## Step 6 — move event time yourself

**A fork's event time moves only when you say so.** Its watermark did not follow the rows you
stepped, so the 09:04 minute is still open in the fork. Close everything up to 09:06:

```python
from datetime import datetime, timezone

close = int(datetime(2026, 9, 26, 9, 6, tzinfo=timezone.utc).timestamp()) * 1_000_000_000
print(close)
st = client.debug_step(s.id, f"watermark:{close}")
print(st.watermark_nanos, [(d.weight, d.values) for d in st.view_changes])
```

```text
1790413560000000000
1790413560000000000 [(1, ('u4', '1790413380000000000', '300', '1')), (1, ('u6', '1790413380000000000', '1500', '1')), (1, ('u7', '1790413380000000000', '1200', '1')), (1, ('u5', '1790413440000000000', '15547', '2')), (1, ('u8', '1790413500000000000', '50', '1'))]
```

Three minutes closed at once: 09:03 (payments from Tutorials 2 and 3, which the checkpoint held as
open state), 09:04 — u5's 15547 over two payments, the same as the live view — and 09:05, u8's 50.
The live query has not published 09:05 yet: its watermark is 09:05:20. The fork is ahead of it,
because you moved its clock.

Values in a step are strings, and times are epoch nanoseconds: `1790413440000000000` is
09:04:00. The fork's whole view:

```python
print([(d.weight, d.values) for d in client.debug_view(s.id)][-4:])
print([(x.id, x.steps, x.rows_consumed) for x in client.debug_sessions()])
```

```text
[(1, ('u6', '1790413380000000000', '1500', '1')), (1, ('u7', '1790413380000000000', '1200', '1')), (1, ('u5', '1790413440000000000', '15547', '2')), (1, ('u8', '1790413500000000000', '50', '1'))]
[('dbg-1a0e1128e51-1', 4, 3)]
```

`debug_inspect(s.id, "window#0", key=None, offset=0, limit=50)` pages through one operator's state
without changing it, for when the question is *what did it hold*, not *what did it emit*.

## Step 7 — export the incident as a test, and end the session

```python
fx = client.debug_export(s.id, "u5 payment dead-lettered in minute four")
print(fx.class_name, fx.path, len(fx.source.splitlines()))
client.debug_end(s.id)
print(client.debug_session(s.id))
```

```text
U5PaymentDeadLetteredInMinuteFourFixtureTest pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures/U5PaymentDeadLetteredInMinuteFourFixtureTest.java 172
None
```

`fx.source` is a JUnit test: the query's SQL and key, the stream's schema, the rows you stepped —
payments 17, 19 and 20 — and the watermark you set, replayed from empty state, asserting the view
the session ended with:

```java
private static final List<String> EXPECTED = List.of(
        "+1[u5, 1790413440000000000, 15547, 2]",
        "+1[u8, 1790413500000000000, 50, 1]");
```

Save it at `fx.path` in a checkout of the repository and the incident is a regression test: if a
change to the engine ever makes u5's minute come out differently from these rows, the build says so.
It needs nothing from the node it came from.

> **On this version, two edits before it runs** (both reported as defects): the generated stream
> schema does not declare its event time, so the windowed query in it is refused with `PRV-2002` —
> add `.eventTime("event_time")` after the last `.field(...)`; and the file is not in the build's
> format — run `./mvnw -pl pravaha-it spotless:apply` once. With those, it passes.

`debug_end` releases the fork. Sessions hold a copy of the query's state and a reader on the
source, and the node allows only a few at once (`PRV-8014` past the limit) — end them.

## The same thing without Python

```bash
P="--url grpc://qa-vm:19090 --token $PRAVAHA_TOKEN --insecure-token"
pravaha dlq list          $P --name spend_per_minute
pravaha dlq show          $P --name spend_per_minute --id <id>
pravaha debug checkpoints $P --name spend_per_minute
pravaha debug fork        $P --name spend_per_minute --checkpoint <id>
pravaha debug step        $P --session <id> --step watermark:1790413560000000000
pravaha debug fixture     $P --session <id> --name "u5 payment dead-lettered in minute four" --out U5.java
pravaha debug end         $P --session <id>
```

`pravaha dlq list` after Step 3:

```text
ID	WHEN	CODE	STREAM	OFFSET	BYTES	STATE	REASON
323b4327-9063-413c-9d53-c331ab759ffb	2026-09-27T04:14:41.789Z	PRV-5040	txn	line 19	44	NEW	replay of 7fb32ff8-bbb6-4c13-82db-dc901963011d failed again: PRV-5040  line 19, column 'amount' (INT64): '77.70' is not a number
7fb32ff8-bbb6-4c13-82db-dc901963011d	2026-09-27T04:14:41.760Z	PRV-5040	txn	line 19	44	FAILED_AGAIN	PRV-5040  line 19, column 'amount' (INT64): '77.70' is not a number
7e72f1b0-82f3-4ace-9fbb-c34b6a8183d7	2026-09-27T04:14:01.621Z	PRV-5040	txn	line 16	53	NEW	PRV-5040  line 16, column 'amount' (INT64): 'twelve-hundred' is not a number

3 dead letters (2203 bytes), 0 replayed, 1 failed again; retention 268435456 bytes
```

In the console, a query's page links to its dead letters (`/queries/spend_per_minute/dead-letters`)
and to the debugger (`/queries/spend_per_minute/debug`).

## Where to read more

[The Python guide, §13](../PYTHON_API_GUIDE.md#13-dead-letters) and
[§15](../PYTHON_API_GUIDE.md#15-the-time-travel-debugger) are the reference for every dead-letter
and debugger call.

## Next

You have registered, followed, replaced and debugged a query. The
[case studies](../../examples/case-studies/README.md) are ten complete systems built from the same
moves — start with one in your own domain.
