# Intraday counterparty exposure — a finance case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** PostgreSQL · **Time to first result:** about fifteen minutes · **Setup:** [`../SETUP.md`](../SETUP.md)

## The problem

A treasury desk pays out all day. Each payment is small against the balance sheet; the question that
matters is how much has gone to *one counterparty* since the day opened, against the limit that
counterparty was granted. Answer it a minute late and you have already breached.

Almost every bank answers this with a batch job over the payments table, which means the number on
the risk screen is as old as the last run. The interesting part is not that batch is slow — it is
that the job re-reads rows it has already read, every run, forever. Pravaha reads each payment once
and keeps the total current.

This study also shows Pravaha reading a **relational** source. The engine does not care that the
previous study read a key-value store; the SQL is the same shape.

## What you will build

```
   PostgreSQL                      Pravaha                     risk screen
  ┌────────────┐   poll by id    ┌──────────────────┐   SQL   ┌──────────────┐
  │ settlement │ ──────────────► │ continuous query │ ──────► │ "how exposed │
  │ table      │   incremental   │ keeps            │         │  are we to   │
  └────────────┘                 │ counterparty_    │         │  ACME today?"│
  ┌────────────┐   lookup join   │ exposure current │         └──────────────┘
  │ counter-   │ ──────────────► │                  │
  │ party      │                 └──────────────────┘
  └────────────┘
```

## The data model

### `settlement` — the stream

One row per outgoing or incoming payment. Rows are **appended**, never updated, and `payment_id`
increases — that is what lets Pravaha poll incrementally instead of rescanning.

| Column | Type | Meaning |
|---|---|---|
| `payment_id` | `BIGSERIAL` | Monotonic. **The cursor the source polls on** |
| `counterparty_id` | `TEXT` | Who we paid. The grouping key |
| `currency` | `TEXT` | ISO code — `GBP`, `USD`, `EUR` |
| `amount_minor` | `BIGINT` | Minor units. Never a float, and never `NUMERIC` on the wire |
| `direction` | `TEXT` | `PAY` or `RECEIVE` |
| `value_time` | `TIMESTAMPTZ` | **Value date/time — event time, not insert time** |

> **`value_time`, not `created_at`.** A payment booked at 16:05 with a value time of 09:00 belongs in
> the 09:00 hour. Using insert time would put late-booked payments in the wrong hour and quietly
> understate the morning's exposure — which is exactly the sort of error that survives review because
> the totals still add up.

### `counterparty` — the reference data

| Column | Type | Meaning |
|---|---|---|
| `counterparty_id` | `TEXT` | Primary key |
| `legal_name` | `TEXT` | For the screen |
| `rating` | `TEXT` | `AAA` … `D` |
| `limit_minor` | `BIGINT` | Agreed intraday limit |

Machine-readable, and checked by the build: [`schema/streams.properties`](schema/streams.properties).

## Step 1 — start PostgreSQL

```bash
docker run -d --name pravaha-postgres \
  -e POSTGRES_PASSWORD=pravaha -e POSTGRES_USER=pravaha -e POSTGRES_DB=pravaha \
  -p 5432:5432 postgres:16

docker exec pravaha-postgres pg_isready -U pravaha   # expect: accepting connections
```

## Step 2 — create the schema

```bash
docker exec -i pravaha-postgres psql -U pravaha -d pravaha < data/schema.sql
```

Or paste it yourself — it is [`data/schema.sql`](data/schema.sql):

```sql
CREATE TABLE counterparty (
  counterparty_id TEXT PRIMARY KEY,
  legal_name      TEXT   NOT NULL,
  rating          TEXT   NOT NULL,
  limit_minor     BIGINT NOT NULL
);

CREATE TABLE settlement (
  payment_id      BIGSERIAL PRIMARY KEY,
  counterparty_id TEXT        NOT NULL REFERENCES counterparty,
  currency        TEXT        NOT NULL,
  amount_minor    BIGINT      NOT NULL,
  direction       TEXT        NOT NULL,
  value_time      TIMESTAMPTZ NOT NULL
);

-- The source polls on payment_id. Without this index the poll degrades into a table scan as the
-- table grows, and it degrades slowly, so nobody notices until it matters.
CREATE INDEX settlement_by_id ON settlement (payment_id);
```

## Step 3 — load reference data

```bash
docker exec -i pravaha-postgres psql -U pravaha -d pravaha <<'SQL'
INSERT INTO counterparty VALUES
  ('cp-acme',   'ACME Clearing Ltd',   'AA',  50000000),
  ('cp-borealis','Borealis Bank NV',   'BBB', 20000000),
  ('cp-cygnus', 'Cygnus Securities SA','A',   35000000);
SQL
```

## Step 4 — the continuous query

Checked by the build, in
[`sql/01-continuous-hourly-exposure.sql`](sql/01-continuous-hourly-exposure.sql):

```sql
SELECT STREAM
  TUMBLE_END(s.value_time, INTERVAL '1' HOUR) AS window_end,
  s.counterparty_id,
  s.currency,
  c.rating,
  COUNT(*)              AS payment_count,
  SUM(s.amount_minor)   AS outgoing_minor,
  MAX(s.amount_minor)   AS largest_minor
FROM settlement AS s
LEFT JOIN counterparty FOR SYSTEM_TIME AS OF s.value_time AS c
       ON s.counterparty_id = c.counterparty_id
WHERE s.direction = 'PAY'
GROUP BY TUMBLE(s.value_time, INTERVAL '1' HOUR), s.counterparty_id, s.currency, c.rating
```

Points specific to this domain:

- **Grouped by currency as well as counterparty.** Summing minor units across currencies produces a
  number with no meaning. The engine will happily do it if you ask; the schema is where you stop
  yourself.
- **`WHERE s.direction = 'PAY'`** is pushed into PostgreSQL, so receipts never leave the database.
  Net exposure needs receipts too — register a second query for `RECEIVE` and subtract in your
  application, because there is no `CASE` to do it in one pass. That is a real limitation and is
  listed at the bottom.
- **`MAX(s.amount_minor)`** gives you the largest single payment in the hour, which is the number
  that tells you whether a total is one big movement or a hundred small ones.

## Step 5 — stream payments in

```bash
docker exec -i pravaha-postgres psql -U pravaha -d pravaha <<'SQL'
INSERT INTO settlement (counterparty_id, currency, amount_minor, direction, value_time) VALUES
  ('cp-acme',    'GBP',  1250000, 'PAY',     '2026-01-05 09:04:00+00'),
  ('cp-acme',    'GBP',  4800000, 'PAY',     '2026-01-05 09:31:00+00'),
  ('cp-acme',    'USD',   900000, 'PAY',     '2026-01-05 09:47:00+00'),
  ('cp-borealis','EUR', 19500000, 'PAY',     '2026-01-05 09:52:00+00'),
  ('cp-cygnus',  'GBP',   150000, 'RECEIVE', '2026-01-05 09:55:00+00');
SQL
```

The `RECEIVE` row is filtered by PostgreSQL and never reaches the engine.

To keep them coming, the generator writes a payment every few hundred milliseconds:

```bash
python3 data/generate_payments.py --rate 5 --minutes 10
```

> **The 09:00 hour closes when a payment with a `value_time` at or after 10:00 arrives.** Until then
> the engine is still willing to accept a late-booked 09:xx payment, and publishing a total it might
> have to retract would be worse than publishing nothing. The generator crosses the boundary for you.

## Step 6 — ask questions

### One counterparty

[`sql/02-read-one-counterparty.sql`](sql/02-read-one-counterparty.sql):

```sql
SELECT counterparty_id, currency, rating, payment_count, outgoing_minor
FROM counterparty_exposure
WHERE counterparty_id = ?
```

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    for row in client.query(open("sql/02-read-one-counterparty.sql").read(), ["cp-acme"]):
        print(row["currency"], row["outgoing_minor"])
```

### Anything over a threshold, in one currency

[`sql/04-read-over-threshold.sql`](sql/04-read-over-threshold.sql):

```sql
SELECT counterparty_id, currency, outgoing_minor, largest_minor
FROM counterparty_exposure
WHERE currency = ? AND outgoing_minor > ?
```

```java
try (QueryResult result = client.query(sql, "GBP", 5_000_000L)) {
    for (Row row : result) {
        System.out.println(row.getString("counterparty_id") + " " + row.getLong("outgoing_minor"));
    }
}
```

Note the second parameter is a `long`. The server tells the client what type each placeholder needs
when the statement is prepared, so nothing guesses — pass an `int` and it converts, pass a string and
you are told which placeholder is wrong before the call leaves your process.

### Book-wide, by rating

[`sql/03-read-exposure-by-rating.sql`](sql/03-read-exposure-by-rating.sql):

```sql
SELECT rating, currency, COUNT(*) AS lines, SUM(outgoing_minor) AS total_minor
FROM counterparty_exposure
GROUP BY rating, currency
```

## Step 7 — a downgrade, and why history does not move

```bash
docker exec -i pravaha-postgres psql -U pravaha -d pravaha \
  -c "UPDATE counterparty SET rating = 'BB' WHERE counterparty_id = 'cp-borealis';"
```

Hours that have already closed keep `BBB` — the rating that was true when those payments settled.
Hours from now on carry `BB`. That is `FOR SYSTEM_TIME AS OF`, and for a regulated report it is the
difference between a number you can defend and a number that changed under you.

## Making this yours

| To change | Do this |
|---|---|
| Hourly → daily | `INTERVAL '1' HOUR` → `'1' DAY`. State grows with the window; a day of counterparties is still small |
| Net rather than gross | Register a second query filtered to `RECEIVE`, subtract in your application |
| Limit breaches in the engine | You cannot compare against `c.limit_minor` in the `HAVING` today; read both and compare in your application |
| A different database | Any JDBC source. The SQL does not move |

## Limits you will meet

The full list is [`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md).

- **No `CASE`**, so no `SUM(CASE WHEN direction = 'PAY' THEN ... END)`. Two registrations instead.
- **No `ORDER BY` / `LIMIT`.** Sort the largest exposures in your application.
- **No `NUMERIC`/`DECIMAL` on the wire.** Minor units as `BIGINT`, which is what you want anyway.
- **Only inner and lookup joins between streams.** `LEFT JOIN ... FOR SYSTEM_TIME AS OF` is a lookup
  and is supported; a `LEFT JOIN` between two *streams* is refused, because an unmatched row would
  have to be held forever in case its partner turns up.
