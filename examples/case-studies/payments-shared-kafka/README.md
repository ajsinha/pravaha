# Many desks, one topic — a payments case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** Kafka · **Time to first result:** about fifteen minutes · **Shows:** several different
queries over one Kafka topic sharing **one reader**, exactly once
([ADR-054](../../../docs/adr/054-an-ordered-source-is-shared-at-an-exact-seam.md)); an **equality
index** on a column outside the view's key, `INDEX (merchant)`
([ADR-055](../../../docs/adr/055-an-equality-index-over-a-column-outside-the-key.md))

## The problem

A payment processor puts every card payment on one Kafka topic, `payments`. Three desks want three
different things from it:

- **merchant services** — each merchant's takings minute by minute, looked up by merchant all day
  long by the support team;
- **risk** — every declined payment, as it happens;
- **cross-border** — every large payment on a foreign card.

Three consumers means three reads of the topic today, and a fourth desk means a fourth. Each is a
consumer group to operate, each re-reads the same bytes from the brokers, and each keeps its own idea
of where it is. And the merchant desk's lookup — "show me m-coffee" — is a scan of every row the
view holds, because the view is keyed by the minute *and* the merchant.

## What you will build

```
  Kafka topic payments              Pravaha
 ┌──────────────────────┐        ┌─────────────────────────────────────────────┐
 │ one JSON record per  │        │  ONE reader ──┬─► merchant_minute  INDEX (merchant) ─► support
 │ card payment         │ ─────► │   per binding ├─► declines                         ─► risk desk
 └──────────────────────┘        │               └─► cross_border                     ─► x-border desk
                                 └─────────────────────────────────────────────┘
```

Three continuous queries, three different plans, one read of the topic.

## One reader for many queries, exactly once

Every query over the stream is fed by the same binding, and **the binding keeps one reader**. Each
record the reader takes from Kafka is handed to every query on it — once each, in the topic's order.

That was not always safe to do for Kafka. A query registered while the reader is already running
has missed the history before it, and has to catch up; if its private catch-up read overlapped the
shared reader, the overlap would be counted twice. So until ADR-054 a source that promised
exactly-once kept a reader *per query*. What changed is that Kafka's source now declares two things:
its positions are **ordered** (per partition, by offset), and its reader can **stop exactly at a
position** — skipping transaction markers rather than counting records, because offsets have gaps.
With both, a late query's catch-up reads up to exactly the offset where the shared reader stands,
and joins the fan-out there. No overlap, no gap. A query that restores from a checkpoint *ahead* of
the shared reader waits for the reader to reach it.

So: register the three queries in any order, at any time, and pause, resume or restart any of them.
The topic is read once, and each record reaches each query exactly once.

## The data model

One topic, one stream, `payment` — each record's value a JSON object of these columns
(`format: json`):

| Column | Type | Meaning |
|---|---|---|
| `payment_id` | STRING | Unique per payment |
| `merchant` | STRING | `m-coffee`, `m-books`, `m-grocer` |
| `card_country` | STRING | Where the card was issued |
| `amount_minor` | INT64 | Minor units: `1999` is 19.99 |
| `status` | STRING | `APPROVED` or `DECLINED` |
| `paid_at` | TIMESTAMP | When the card was charged — **the stream's event time** |

Checked by the build from [`schema/streams.properties`](schema/streams.properties); the node reads
[`conf/application.yaml`](conf/application.yaml), which binds the topic once.

## Step 1 — Kafka, and three minutes of payments

[`../SETUP.md`](../SETUP.md) starts a single-broker Kafka in Docker as `pravaha-kafka` and creates
the `payments` topic. Then produce three minutes of an afternoon:

```bash
python3 data/generate_payments.py | docker exec -i pravaha-kafka \
    /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic payments
```

[`data/generate_payments.py`](data/generate_payments.py) writes a payment every ten seconds from
14:00:00 to 14:02:50 UTC at the three merchants in turn. Payments 5, 11 and 16 are large and on
US, French and German cards; 3, 10 and 17 are declined. The last, at 14:03:10, closes the third
minute. The output is identical on every run.

## Step 2 — start the node

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

```text
stream payment: event-time=paid_at, out-of-orderness=PT5S, allowed-lateness=PT0S
```

## Step 3 — three continuous queries

### Each merchant's minutes, with an index on the merchant

[`sql/01-continuous-merchant-minute.sql`](sql/01-continuous-merchant-minute.sql):

```sql
CREATE CONTINUOUS QUERY merchant_minute
  KEYED BY (window_end, merchant)
  INDEX (merchant)
AS
SELECT STREAM
  TUMBLE_END(paid_at, INTERVAL '1' MINUTE) AS window_end,
  merchant,
  COUNT(*)          AS payments,
  SUM(amount_minor) AS amount_minor
FROM payment
WHERE status = 'APPROVED'
GROUP BY TUMBLE(paid_at, INTERVAL '1' MINUTE), merchant
```

This one is a statement rather than a bare `SELECT`, because it declares an index, and an index is a
clause of the statement — there is no registration argument for it.

**`KEYED BY (window_end, merchant)`** — one row per merchant per minute. A read by the whole key is a
hash probe. A read by `merchant` alone, which is what the support desk does all day, is not: without
more, it walks every row of the view.

**`INDEX (merchant)`** keeps an equality index over that one column: for each merchant, the keys of
the rows that hold it, maintained in the same commit as the view. A read whose `WHERE` has
`merchant = …` or `merchant IN (…)` as one of its top-level conditions probes the index once per
value instead of scanning. The index is built before the registration is acknowledged and is written
to the registry's journal with it, so a restart keeps it. It holds one entry per row, on the heap,
bounded by the view's own row ceiling. It chooses rows and never an answer: the `WHERE` still runs
over what the probe returns.

### Declines

[`sql/02-continuous-declines.sql`](sql/02-continuous-declines.sql):

```sql
SELECT payment_id, merchant, card_country, amount_minor, paid_at
FROM payment
WHERE status = 'DECLINED'
```

### Large payments on foreign cards

[`sql/03-continuous-cross-border.sql`](sql/03-continuous-cross-border.sql):

```sql
SELECT payment_id, merchant, card_country, amount_minor, paid_at
FROM payment
WHERE card_country <> 'GB' AND amount_minor >= 50000
```

Two filters, keyed by `payment_id` — `[0]`. Different `WHERE` clauses make them different plans, so
they are separate computations with separate state. They are **not** separate reads of the topic.

### Register them

```bash
python3 python/run.py --url grpc://localhost:19090
```

[`python/run.py`](python/run.py) sends the `CREATE` statement as SQL — `client.query(statement)` —
and registers the two filters with `client.register(name, sql, [0])`. Java:
[`java/SharedPaymentsExample.java`](java/SharedPaymentsExample.java). From a shell:

```bash
pravaha query    --sql "$(cat sql/01-continuous-merchant-minute.sql)"
pravaha register --name declines     --sql-file sql/02-continuous-declines.sql     --keys 0
pravaha register --name cross_border --sql-file sql/03-continuous-cross-border.sql --keys 0
```

Register them a minute apart if you like: the second and third catch up on the history they missed,
privately, to exactly the offset the shared reader has reached, and then join it.

## Step 4 — ask questions

The answers below are worked out from the generated payments and checked by the build:
[`CaseStudyRunTest`](../../../pravaha-it/src/test/java/com/ash/messaging/pravaha/it/CaseStudyRunTest.java)
runs the three queries over the same rows, compares, and fails if no read went through the index.

### One merchant, by the indexed column

[`sql/04-read-one-merchant.sql`](sql/04-read-one-merchant.sql):

```sql
SELECT window_end, payments, amount_minor
FROM merchant_minute
WHERE merchant = ?
```

Bound to `m-coffee`:

```text
-- merchant_minute for m-coffee
   {'window_end': '2026-09-14T14:01:00+00:00', 'payments': 1, 'amount_minor': 350}
   {'window_end': '2026-09-14T14:02:00+00:00', 'payments': 2, 'amount_minor': 10210}
   {'window_end': '2026-09-14T14:03:00+00:00', 'payments': 2, 'amount_minor': 7018}
```

The first minute has one payment, not two: `p-003` was declined, and the `WHERE status =
'APPROVED'` kept it out. There is no `EXPLAIN` for a view read; the evidence that the index answered
is the view's own counters — `indexLookups` moves and `scans` does not.

### Two merchants

[`sql/05-read-two-merchants.sql`](sql/05-read-two-merchants.sql):

```sql
SELECT merchant, SUM(payments) AS payments, SUM(amount_minor) AS amount_minor
FROM merchant_minute
WHERE merchant IN ('m-coffee', 'm-books')
GROUP BY merchant
```

```text
-- coffee and books
   {'merchant': 'm-coffee', 'payments': 5, 'amount_minor': 17578}
   {'merchant': 'm-books', 'payments': 5, 'amount_minor': 81250}
```

`IN (…)` is a probe per value. Widen it with an `OR` on another column — `merchant = 'm-coffee' OR
payments > 1` — and no single column bounds the answer any more, so the read scans. The answer is the
same either way; only the work differs.

### Declines, per merchant

[`sql/06-read-declines-by-merchant.sql`](sql/06-read-declines-by-merchant.sql):

```sql
SELECT merchant, COUNT(*) AS declines, SUM(amount_minor) AS declined_minor
FROM declines
GROUP BY merchant
```

```text
-- declines by merchant
   {'merchant': 'm-coffee', 'declines': 1, 'declined_minor': 4052}
   {'merchant': 'm-books', 'declines': 1, 'declined_minor': 3690}
   {'merchant': 'm-grocer', 'declines': 1, 'declined_minor': 3328}
```

### Cross-border, from one country

[`sql/07-read-cross-border-by-country.sql`](sql/07-read-cross-border-by-country.sql):

```sql
SELECT payment_id, merchant, amount_minor
FROM cross_border
WHERE card_country = ?
```

Bound to `FR`:

```text
-- cross-border from FR
   {'payment_id': 'p-011', 'merchant': 'm-grocer', 'amount_minor': 72000}
```

The view holds three rows — `p-005` (US), `p-011` (FR), `p-016` (DE). `card_country` has no index
here, so this read scans them; at three rows that is the right choice. Add `INDEX (card_country)`
when the view is large and the read is frequent, not before.

## Step 5 — follow a desk

```bash
python3 python/run.py --url grpc://localhost:19090 --watch
```

Produce another afternoon and the risk desk sees each decline as one commit. The merchant desk,
reading `merchant_minute`, and the cross-border desk, following `cross_border`, are served by the
same read of the topic.

## Making this yours

| To change | Do this |
|---|---|
| Another desk | Register another query over `payment`. It costs its own state, not another reader |
| Index a different column | `INDEX (card_country)` on another `CREATE`. One column per clause; up to four per view |
| Spell the index as an option | `WITH (index = 'merchant')` in the statement is the same as `INDEX (merchant)`; neither is an SDK registration argument |
| Replace the query behind a name | `CREATE OR REPLACE CONTINUOUS QUERY` keeps the name's indexes; `INDEX` on it is refused (`PRV-2072`) |
| Avro or Protobuf values | `format: avro` or `protobuf` on the binding; the queries do not change |
| A changelog topic | `format: changelog` reads `kafka-sink`'s envelope, retractions included |

## Pitfalls

- **Sharing is per binding.** Two bindings of the same topic are two readers. Bind a topic once and
  register everything against that stream.
- **An index is an allocation.** One entry per row, on the heap, until restart even if the name that
  declared it is dropped while another shares the computation. Declare it for a read you actually
  make often.
- **What cannot be indexed is refused, not ignored** (`PRV-2074`): a `FLOAT` or `DECIMAL` column
  (equality there is not stored-value equality), `BYTES`, or the view's whole key — a read by the key
  is already a hash probe. `INDEX (a, b)` is refused (`PRV-2070`): it is not a composite index.
- **A consumer group's offset is not a position.** `monitoring.group` is committed for dashboards; a
  restore always resumes from the engine's checkpoint.
- **Windows close on event time.** If the topic goes quiet, the last minute waits for the next
  payment; a heartbeat record keeps answers prompt.

The full list of what runs and what is refused is
[`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md); the Kafka source is described in
[`docs/CONNECTORS.md`](../../../docs/CONNECTORS.md).
