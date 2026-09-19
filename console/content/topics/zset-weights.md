---
title: Z-set weights and retractions
slug: zset-weights
category: concepts
order: 40
icon: plus-slash-minus
summary: "Every row carries a weight — +1 appearing, −1 withdrawn — and every operator does the same arithmetic on both. Why that makes incremental maintenance correct, what a subscriber sees, and how to consume it without drifting."
audience: Developers
keywords: [z-set, zset, weight, retraction, "-1", "+1", insert, delete, update, correction, dbsp, changelog, incremental, subscriber, op.column, cdc, tombstone]
guide: concepts#4-changes-carry-weights-and-a-correction-is-a-retraction-plus-an-insert
related: [late-data, subscriptions, views-and-keys, consistency, sinks-overview]
---

Rows in Pravaha do not simply arrive; they arrive with a **weight**. `+1` is a row appearing. `−1`
is a row being withdrawn. A table with weights on its rows is a **Z-set** — a multiset whose
multiplicities may be negative — and it is the algebra the whole engine runs on
([ADR-013](/help/decisions/013-zsets-and-dbsp)).

The point is that **there is one kind of change, not three**. An insert is `+1`. A delete is `−1`.
An update is `−1` for the old row and `+1` for the new one. A late correction to a published window
is the same `−1`/`+1` pair. Every operator — filter, projection, join, aggregate — does the same
arithmetic on both signs, so there is no separate "retract" code path to get wrong, and an
aggregate can be **maintained** as rows come and go instead of recomputed.

## The arithmetic, operator by operator

| Operator | Given a row with weight *w* | So a retraction… |
|---|---|---|
| Filter | passes it with weight *w* if the predicate holds | withdraws exactly the row the insert added |
| Projection | emits the projected row with weight *w* | withdraws the projected row |
| Join | each match is emitted with the product of the two weights | withdraws every match the row had made |
| `COUNT(*)` | adds *w* to the count | subtracts one |
| `SUM(x)` | adds *w × x* | subtracts *x* |
| A view | *w* = +1 inserts or replaces by key; *w* = −1 removes | removes the row |

This is also why `SUM` over a floating-point column is refused (PRV-2020): adding and then
subtracting in a different order does not return you to where you were in floating point, so an
incrementally maintained float sum would depend on arrival order. `SUM(CAST(price AS BIGINT))` —
integer cents — works.

## Seeing weights: a filter over a deletable source

A file source becomes a legitimate Z-set source with `op.column`: rows whose operation column holds
`D`, `DELETE`, `-` or `-1` (configurable with `op.delete.values`) arrive with weight `−1`.

```yaml
pravaha:
  sources:
    txn:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP,op:STRING"
        event.time: event_time
        follow: "true"
        op.column: op
        op.delete.values: "D,DELETE"
```

Register a filter:

```sql
CREATE CONTINUOUS QUERY large_payments
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, amount
FROM txn
WHERE amount >= 1000;
```

Subscribe from Python, printing each row's weight:

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    for batch in client.subscribe("large_payments"):
        for row in batch:
            print(f"{row.weight:+d}", row["txn_id"], row["user_id"], row["amount"])
        print("-- commit")
```

Append an insert, then its deletion:

```text
41,u7,m-2,1800,GBP,COMPLETED,2026-09-19T10:00:03Z,I
41,u7,m-2,1800,GBP,COMPLETED,2026-09-19T10:00:03Z,D
```

```text
+1 41 u7 1800
-- commit
-1 41 u7 1800
-- commit
```

(If both lines are read in one batch they cancel inside the commit, and a subscriber may see
nothing at all — which is correct: the net change is zero.)

## Seeing weights: an update to an aggregate

A global aggregate has one row, and each change to its input **replaces** that row. Keyed by its
count, each new count is a new key, so an update is visibly a retraction and an insertion:

```sql
CREATE CONTINUOUS QUERY large_totals
    KEYED BY (payments)
AS
SELECT COUNT(*) AS payments, SUM(amount) AS takings
FROM txn
WHERE amount >= 1000;
```

Feed it two large payments, in two separate appends:

```text
43,u2,m-1,1200,GBP,COMPLETED,2026-09-19T10:01:10Z,I
44,u5,m-8,3100,GBP,COMPLETED,2026-09-19T10:01:15Z,I
```

and a subscriber to `large_totals` sees:

```text
+1 1 1200        # txn 43 arrives: one payment, 1200
-- commit
-1 1 1200        # txn 44 arrives: the old total is withdrawn...
+1 2 4300        # ...and the new one inserted, in the same commit
-- commit
```

The retraction and its replacement are **always in the same commit** — a commit ends only at the
edge of a batch the engine has finished — so a reader never sees the total vanish between the two.

(A global aggregate is allowed over a stream because it has one group and so bounded state. A
*keyed* unwindowed aggregate — `GROUP BY user_id` — is refused with PRV-2050. Any unwindowed
aggregate revises its answer, which is why pointing one at an append-only sink is refused with
PRV-2041.)

## Consuming weights correctly

There are exactly two correct ways to consume a changelog:

**Keep current values — overwrite by key, drop the negatives.** Right for a dashboard or a cache. An
update's `−1` is followed in the same commit by the `+1` that replaces it:

```python
current = {}
for batch in client.subscribe("user_volume"):
    for row in batch:
        key = (row["user_id"], row["window_end"])
        if row.weight > 0:
            current[key] = row["total"]
        elif current.get(key) == row["total"]:
            del current[key]            # withdrawn and not (yet) replaced
```

**Keep your own aggregate — apply the weight.** Right for a running total, a ledger, anything that
sums:

```python
running = 0
for batch in client.subscribe("large_payments"):
    for row in batch:
        running += row.weight * row["amount"]
```

```java
batch.forEach(row -> running += row.weight() * row.getLong("amount"));
```

What is **wrong** is counting rows or summing values while ignoring the weight: the first correction
double-counts, and from then on your total drifts from the view's for ever.

## Where weights appear, and where they do not

| Surface | Weights? |
|---|---|
| A subscription (Java, Python, embedded, Spring `@PravahaListener`) | yes, on every row — `row.weight`, `row.weight()`, `RowChange.isRetraction()`, or the `boolean retraction` parameter |
| The console's live page | yes, shown as `+1` / `−1` beside each change |
| A sink | yes — retractions are written as rows with a negative weight, or applied as deletes by an upserting sink |
| A read of a view (`SELECT … FROM view`) | no — a read sees the current rows, every one of which is simply there |
| The CLI's `pravaha subscribe` | yes — each line leads with its weight, `+1` or `-1`, under a `WEIGHT` header |

The weight is **not one of the view's columns**: `row.columns()` lists what the query selected, and
a positional read gets the column it always got. It travels as a separate, metadata-marked column on
the wire, found by its mark rather than its name, so a view that selects a column called `weight`
is not confused with it.

## Pitfalls

!!! warning "A count went down"
    That is a correction, working as designed: a retraction removed a row the count had included.

!!! warning "Summing a subscription without the weight"
    Your total is right until the first correction and wrong for ever after. Multiply by the weight.

!!! warning "MIN and MAX of an unwindowed aggregate cannot take a retraction"
    `COUNT` and `SUM` invert exactly; `MIN` and `MAX` do not — knowing the current extreme does not
    tell you the previous one once it is withdrawn. A global `MIN`/`MAX` that receives a `−1` fails
    at runtime rather than guessing. Over a source that can delete, aggregate with `COUNT`/`SUM`, or
    window the query.

!!! note "A polled table never retracts"
    The `jdbc` source writes weight `+1` on every row: a deleted database row is simply never seen
    again. The same holds for scans of Aerospike and Cassandra. Where deletes must reduce a total,
    a source that sees them is the shape: [postgres-cdc](/help/topics/source-postgres-cdc), which
    turns a PostgreSQL `DELETE` into a `−1` and an `UPDATE` into a `−1` and a `+1`; Delta; the
    [Kafka source](/help/topics/source-kafka) reading `kafka-sink`'s changelog, weights and all; or a
    file with an operation column.

## Where next

- [Late data and corrections](/help/topics/late-data) — the `−1`/`+1` pair in windows
- [Subscriptions](/help/topics/subscriptions) — commits, filters, slow consumers
- [Delivery guarantees](/help/topics/delivery-guarantees) — what a sink does with a `−1`
- The long form: [Concepts §4](/help/concepts#4-changes-carry-weights-and-a-correction-is-a-retraction-plus-an-insert)
