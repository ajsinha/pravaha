---
title: The jdbc sink — a transactional, exactly-once table
slug: sink-jdbc
category: sinks
order: 30
icon: table
summary: "jdbc-sink maintains a query's answer in a relational table you create — upserting and deleting by key — and is transactional: exactly once on a node that checkpoints."
badge: SINK
audience: Engineers
keywords: [jdbc sink, jdbc-sink, transactional, exactly once, exactly-once, postgresql, h2, upsert, staging table, transaction.id, key.columns, "ON CONFLICT", MERGE]
guide: operations#one-engine-and-what-the-server-still-lacks
related: [sinks-overview, delivery-guarantees, sink-kafka, checkpoints-recovery, source-jdbc, connector-security]
---

`jdbc-sink` keeps a relational table **equal to the query's view**. Each row the query commits is
upserted by its key; each retraction deletes the row its key names. Readers of the table see a
maintained answer — current totals per customer, per window, per symbol — in the database they
already have, with the SQL and tools they already use.

It is **transactional**, as [`kafka-sink`](/help/topics/sink-kafka) is. On a node that takes
checkpoints, the jdbc sink is **exactly once**: every change the engine commits reaches the table exactly once, a
checkpoint's changes become visible together, and a crash neither loses nor repeats a row. The price
is that the table trails the view by up to one checkpoint interval.

## At a glance

| | |
|---|---|
| Plugin name | `jdbc-sink` (under `pravaha.sinks.<name>`) |
| Ships in | the `pravaha-plugin-jdbc` jar, beside the `jdbc` source and `jdbc-lookup`. **The JDBC driver is yours**: put PostgreSQL's (or your database's) driver jar on the server's classpath |
| Databases | PostgreSQL and H2 with a native upsert; any other database with a JDBC driver through `UPDATE`-then-`INSERT` |
| Accepts | `mode: upsert` (default): `UPSERT`, `RETRACT` — any query. `mode: append`: `APPEND` only |
| Keyed | in upsert mode, by `key.columns`, which must be the view's key |
| Transactional | yes, by default (`transactional: true`) |
| Delivery | **exactly once** with `transactional: true` and `pravaha.checkpoint.directory` set; effectively once (upsert) or at least once (append) otherwise |
| Rows per batch | at most 1,000 per statement batch |
| TLS | in the JDBC `url`; the shared `tls.*` options are refused (PRV-5074) |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `url` | yes | — | The JDBC URL. TLS settings go here (`sslmode=verify-full` for PostgreSQL) |
| `user` | no | empty | Database user |
| `password` | no | empty | Its password. Use `${ENV_VAR}` rather than a literal |
| `table` | yes | — | The target table, `name` or `schema.name`. **You create it; the sink never creates or alters it** |
| `schema` | yes | — | The row shape, `name:TYPE,...` (`?` for nullable). Must match the registered query's `SELECT` list in order, name and type (PRV-8010), and the table's columns (PRV-5075) |
| `key.columns` | in upsert mode | — | Comma-separated key columns. Must be the registration's key; may not be floating-point or nullable. Refused in append mode |
| `mode` | no | `upsert` | `upsert` keeps the table equal to the view; `append` inserts every row and accepts only an append-only query |
| `transactional` | no | `true` | `true` stages writes and applies them per checkpoint; `false` writes each batch straight to the table in its own transaction |
| `transaction.id` | no | the binding's name | The id staged rows are filed under. 1 to 200 characters. **One writer per id** |
| `staging.table` | no | `pravaha_sink_staging` | Where writes are staged. A plain `name` or `schema.name` of letters, digits and underscores. Created if missing |
| `dialect` | no | `auto` | `auto` (from the driver's product name), `postgresql`, `h2` or `portable` |

The shared connector TLS options (`tls.ca`, `tls.certificate`, ...) are **refused** here with
PRV-5074, because a JDBC driver cannot be handed an `SSLContext` and accepting them would leave a
plaintext socket behind a configuration that claims otherwise. `tls.enabled: false` is accepted, as
a statement that plaintext is deliberate. See [TLS everywhere](/help/topics/tls).

## A complete example: hourly spend per user, in PostgreSQL

### 1. Create the table

The sink checks the table when it opens and never changes it. On PostgreSQL the table needs a
**primary key or unique index on exactly `key.columns`** — that is what `ON CONFLICT` resolves
against.

```text
CREATE TABLE spend_by_user (
    user_id       text        NOT NULL,
    window_start  timestamptz NOT NULL,
    window_end    timestamptz NOT NULL,
    spend         bigint      NOT NULL,
    PRIMARY KEY (user_id, window_end)
);
```

(That is PostgreSQL DDL, run in `psql` against your database — not Pravaha SQL.) The staging table,
`pravaha_sink_staging`, is created by the sink on first open if the user may create tables; otherwise
create it yourself with the columns `sink_id VARCHAR(200)`, `label BIGINT`, `seq INTEGER`,
`payload BYTEA`, primary key `(sink_id, label, seq)`.

### 2. Bind it

```yaml
pravaha:
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
    interval: 1m
    keep: 3
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  sources:
    txn:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        skip.header: "true"
        follow: "true"
  sinks:
    spend_table:
      plugin: jdbc-sink
      options:
        url: "jdbc:postgresql://pg.internal:5432/analytics?sslmode=verify-full&sslrootcert=/opt/pravaha/conf/tls/pg-ca.pem"
        user: pravaha
        password: "${PG_PASSWORD}"
        table: spend_by_user
        schema: "user_id:STRING,window_start:TIMESTAMP,window_end:TIMESTAMP,spend:INT64"
        key.columns: "user_id,window_end"
        mode: upsert
        transactional: "true"
```

### 3. Register the query

The `SELECT` list is the sink's `schema`, column for column, and `KEYED BY` is its `key.columns`:

```sql
CREATE CONTINUOUS QUERY spend_by_user
    KEYED BY (user_id, window_end)
    WRITING TO spend_table
    RETAIN FOR P7D
AS
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

The node logs the guarantee it will give:

```text
query 'spend_by_user' writes to sink 'spend_table', exactly-once: the sink is transactional, so what is written between checkpoints is prepared at each checkpoint's cut, recorded in the checkpoint, and committed once the checkpoint is durable
```

### 4. Read the table

After the hour ending 10:00 closes and the next checkpoint is durable (with the sample rows ann 250,
ann 1800 and bob 4200 in that hour):

```text
analytics=> SELECT user_id, window_end, spend FROM spend_by_user ORDER BY user_id;
 user_id |       window_end       | spend
---------+------------------------+-------
 ann     | 2026-09-19 10:00:00+00 |  2050
 bob     | 2026-09-19 10:00:00+00 |  4200
(2 rows)
```

The view holds the same rows the moment the window closes; the table holds them once the checkpoint
that recorded them is stored:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM spend_by_user WHERE user_id = 'ann'
```

## How exactly-once works: the staging table

1. **Write.** A batch is not written to the target table. It is stored, as bytes, in the staging
   table under this sink's `transaction.id` and the open transaction's label, and that is committed.
   Staged rows are durable and invisible to anyone reading the target.
2. **Prepare** at the checkpoint's cut: nothing left to do but name the label, which the checkpoint
   records.
3. **Commit**, once the checkpoint is durable: every batch staged under the label is applied to the
   target table **and** deleted from staging, in **one** database transaction. Readers see a
   checkpoint's changes all at once. A commit repeated after a crash finds nothing staged and does
   nothing; a commit that died half way was rolled back whole and is redone.
4. **Restore**: everything staged after the restored checkpoint is deleted, and the replay writes it
   again.

Why not the database's own `PREPARE TRANSACTION`: PostgreSQL disables it by default
(`max_prepared_transactions = 0`), and a prepared transaction holds every row lock it took until the
checkpoint is stored — a slow checkpoint store would block every other writer of the table. The
staging table holds no lock between calls and works on any database with a byte-string column.

**What it costs:** every row is written twice (to staging, then to the target), and the table lags
the view by up to one `pravaha.checkpoint.interval` — one minute by default.

**What it assumes:** one sink per `transaction.id` — two registrations naming the same binding at
the same time would share staging rows, so give each its own binding — and that nothing else writes
the target table's keys. Staged rows that a failed or never-restored run left behind are never
applied; delete them by `sink_id` when you retire a binding.

## The statements it runs

| Dialect | Upsert | Retraction |
|---|---|---|
| `postgresql` | `INSERT ... ON CONFLICT (key) DO UPDATE SET col = EXCLUDED.col, ...` | `DELETE ... WHERE key = ?` |
| `h2` | `MERGE INTO t (cols) KEY (key) VALUES (...)` | `DELETE ... WHERE key = ?` |
| `portable` (anything else) | `UPDATE ... WHERE key = ?`, then `INSERT` for the rows it did not find | `DELETE ... WHERE key = ?` |

Every statement is prepared, and every identifier is looked up in the database's catalogue and
quoted — so `spend_by_user` finds PostgreSQL's `spend_by_user` and H2's `SPEND_BY_USER`.

## What is checked when it opens — PRV-5075

Before a row moves, the table is compared with `schema`. Each refusal is PRV-5075 and names the
column:

- the table, or a declared column, does not exist;
- a column cannot hold its declared type — `INTEGER` for an `INT64`, `REAL` for a `FLOAT64`,
  `NUMERIC(10,2)` for a `DECIMAL(12,4)`;
- a nullable declaration over a `NOT NULL` column;
- a `NOT NULL` column with no default that the schema does not write;
- on PostgreSQL, no primary key or unique index on exactly `key.columns`.

| Declared | Accepted column types |
|---|---|
| `INT64` | `BIGINT`, or a numeric with at least 19 integer digits |
| `INT32` | `INTEGER`, `BIGINT` |
| `FLOAT64` | `FLOAT`, `DOUBLE` |
| `STRING` | `CHAR`, `VARCHAR`, `TEXT` and the other character types |
| `TIMESTAMP` | `TIMESTAMP` (written as the UTC wall clock) or `TIMESTAMP WITH TIME ZONE` (the instant) |
| `DATE`, `TIME` | `DATE`, `TIME` |
| `BOOLEAN` | `BOOLEAN`, `BIT` |

## Without transactions

`transactional: "false"` writes each batch straight to the target table in its own database
transaction. In upsert mode that is **effectively once** — a replay after a restart rewrites rows
with the values they already hold. In append mode it is **at least once**, and the table keeps the
repeats. Choose it when a minute of lag matters more than a checkpoint's atomicity, or on a node that
takes no checkpoints (where a transactional sink gives the same anyway: there is no checkpoint to tie
a transaction to, so it is effectively once in upsert mode and at least once in append mode).

```yaml
pravaha:
  sinks:
    big_payments_log:
      plugin: jdbc-sink
      options:
        url: "jdbc:postgresql://pg.internal:5432/analytics?sslmode=verify-full&sslrootcert=/opt/pravaha/conf/tls/pg-ca.pem"
        user: pravaha
        password: "${PG_PASSWORD}"
        table: big_payments
        schema: "txn_id:INT64,user_id:STRING,amount:INT64"
        mode: append
        transactional: "false"
```

```sql
CREATE CONTINUOUS QUERY big_payments_log
    KEYED BY (txn_id)
    WRITING TO big_payments_log
AS
SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000;
```

## When the database refuses — PRV-5076, then PRV-8009

A write, staging, commit or abort the database refuses is PRV-5076. The delivery then **detaches**
the sink with PRV-8009: the query and its view carry on, and nothing more is written to the table.
Drop and re-register the query to start again; the new registration sends the view's whole contents,
which the upsert makes harmless in upsert mode and which an append table receives twice.

## Pitfalls

!!! warning "Pitfall: the table trails the view"
    With `transactional: true`, a row appears in the table only after the checkpoint that recorded
    it is durable — up to `pravaha.checkpoint.interval` behind the view. A dashboard comparing the
    two will see the gap. Read the view when you need it now.

!!! warning "Pitfall: no checkpoint directory"
    Without `pravaha.checkpoint.directory` the sink is still transactional, but each commit is its
    own transaction and a restart delivers again: **effectively once** in upsert mode (the repeat
    rewrites rows with the values they hold), **at least once** in append mode. The registration's
    log line and `GET /api/v1/sinks` say which.

!!! warning "Pitfall: two registrations on one binding"
    Staging rows are found by `transaction.id`, which defaults to the binding's name. Two queries
    writing the same binding share them. Bind one sink per query.

!!! warning "Pitfall: a stop is not a commit"
    Dropping a registration commits what its sink was written. Stopping the node does not: the tail
    since the last checkpoint stays staged, as after a crash, and the restart writes it again. A node
    stopped and never restarted leaves that tail out of the table.

!!! note "Nothing else may write these keys"
    The sink deletes by key on a retraction. A row another application wrote under the same key is
    deleted, or overwritten, like any other.

## Where next

- [Delivery guarantees](/help/topics/delivery-guarantees) — how the five shipped sinks compare
- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what the exactly-once guarantee
  rests on
- [How a query writes to a sink](/help/topics/sinks-overview) — the shape check and detach
- [The jdbc source](/help/topics/source-jdbc) — reading the other way
