---
title: The postgres-cdc source — change data capture from PostgreSQL
slug: source-postgres-cdc
category: sources
order: 45
icon: journal-arrow-down
summary: "Streams one PostgreSQL table's inserts, updates and deletes through logical replication: +1 and −1 weights, an update as both, exactly once through a slot confirmed at checkpoints. The prerequisites, the slot, and the recoveries."
badge: SOURCE
audience: Operators
keywords: [postgres-cdc, cdc, change data capture, postgresql, logical replication, pgoutput, wal, wal_level, replication slot, slot, publication, replica identity full, lsn, heartbeat, slot lag, truncate, debezium, retraction, delete]
guide: operations#change-data-capture-the-replication-slot
related: [sources-overview, source-jdbc, zset-weights, checkpoints-recovery, sink-kafka, source-kafka, delivery-guarantees, connector-security]
listed_on: sources-overview
---

The `postgres-cdc` plugin reads a PostgreSQL table's **changes** — every committed `INSERT`, `UPDATE`
and `DELETE` — from the database's own write-ahead log, through PostgreSQL's logical replication: a
replication slot, a publication, and the built-in `pgoutput` stream, decoded by the plugin. It is
built natively rather than through Debezium ([ADR-041](/help/decisions/041-change-data-capture-without-debezium)).

It is the one shipped source that reads a database as a true **changelog**. Every other database source sees a row's
value at the moment it looks: a poll or a scan cannot see a delete, and an update is a new value with
nothing withdrawn. Here an insert arrives at weight `+1`, a delete as the whole old row at `−1`, and an
update as **both** — the old row at `−1`, then the new one at `+1` — so a query's answer goes *down*
when the table does.

It is also the one source that leaves **state on the database server**: the replication slot, which
keeps write-ahead log until Pravaha confirms it. Most of running this source is looking after that
slot, and this page says how.

## At a glance

| | |
|---|---|
| Plugin name | `postgres-cdc` |
| Module | `plugins/pravaha-plugin-postgres-cdc` — in the server jar, **and so is the PostgreSQL JDBC driver** (42.7.8), so `java -jar` binds it with nothing added |
| Database | PostgreSQL **14 or later**, nothing else |
| Kind | stream source, one table per binding |
| Delivery guarantee | **`EXACTLY_ONCE`** — the offset is a commit LSN, and the slot is confirmed only at checkpoints |
| Replayable offsets / ordered | yes / yes |
| Emits deletes / before-image | **yes / yes** — the first shipped source to declare both |
| Pushdown | none — the stream is the table's whole rows |
| Shared between queries | **no** — a binding names one slot, and one query reads it; a second, different query over the binding is refused (PRV-8028) |
| Initial snapshot | with `snapshot.mode: initial`: the rows already there, in key order, then the changes; exact across a restart half-way through. `never` (the default): changes from the slot's creation only |
| Schema comes from | the table, or a declared `schema` checked against it |
| Event time | the transaction's **commit time**, or an `event.time` timestamp column |

## Before the first registration: the database

Four things the database must allow. Each one missing is refused when the source opens, with
PRV-5112 and the statement that fixes it — before any slot or publication is created.

```text
-- postgresql.conf, or:
ALTER SYSTEM SET wal_level = logical;      -- then RESTART PostgreSQL; a reload changes nothing

-- room for one slot and one sender per registration
SHOW max_replication_slots;
SHOW max_wal_senders;

-- every captured table
ALTER TABLE public.orders REPLICA IDENTITY FULL;

-- a role that may replicate, and -- if the plugin is to create the publication -- owns the table
-- and may create in the database
CREATE ROLE pravaha_cdc WITH LOGIN REPLICATION PASSWORD '...';
ALTER TABLE public.orders OWNER TO pravaha_cdc;
GRANT CREATE ON DATABASE shop TO pravaha_cdc;

-- recommended: cap what a slot may hold (see "The slot" below)
ALTER SYSTEM SET max_slot_wal_keep_size = '50GB';
```

(PostgreSQL statements, run in `psql` — not Pravaha SQL; so are the other database statements on
this page.)

- **PostgreSQL 14 or later.** The heartbeat below needs `pgoutput`'s `messages` option, added in 14.
- **`wal_level = logical`.** Read only at server start, so it is a change somebody schedules, not one
  applied during an incident.
- **`REPLICA IDENTITY FULL`** on the table. PostgreSQL's default puts only the primary key in an
  update's or delete's before-image — so a delete would retract `(42)` where the view holds
  `(42, 'silver', 'EU')`, nothing would match, and the view would stay wrong for ever with no error
  anywhere. That is the trap this source exists to refuse. `FULL` writes the whole old row into the
  WAL on every update and delete: a real cost on a wide, busy table, agreed to by whoever owns it.
- **A publication** that publishes inserts, updates and deletes and includes the table. The plugin
  creates `FOR TABLE <table>` if it may (`create.publication`); an existing one that is missing the
  table, or does not publish updates and deletes, is refused naming the `ALTER PUBLICATION`.
- **To create that publication, the role needs two things**: to own the table, and `CREATE` on the
  database (`GRANT CREATE ON DATABASE <db> TO <role>;`) — PostgreSQL's own rule for `CREATE
  PUBLICATION`, and one a fresh role does not have. Without the grant the source is refused at open
  with [PRV-5111](/help/codes/PRV-5111) and PostgreSQL's `permission denied for database <db>`,
  not with PRV-5112 and the statement. Or create the publication yourself as a role that may
  (`CREATE PUBLICATION pravaha_<table> FOR TABLE <table>;`) and set `create.publication: "false"`;
  then the capture role needs neither.

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `url` | yes | — | A `jdbc:postgresql:` URL. **TLS goes here** (`sslmode=verify-full&sslrootcert=...`); the replication connection is opened from the same URL. Anything else is PRV-5110 |
| `table` | yes | — | `schema.table`, or a bare name in `public` |
| `user` / `password` | no | empty | A role with `REPLICATION` |
| `stream` | no | the table's name, without its schema | The stream name the plugin reports |
| `slot` | no | `pravaha_<table>` | The replication slot. 1–63 of `a-z`, `0-9`, `_` |
| `publication` | no | `pravaha_<table>` | The publication, same rule |
| `create.slot` | no | `true` | `false`: the slot must exist, or open is refused naming the statement |
| `create.publication` | no | `true` | `false`: the publication must exist and include the table |
| `schema` | no | the table's columns | `name:TYPE,...`, checked against the table **by name**: a subset of its columns, in your order, an integer or float widened, a nullable column marked `?` (PRV-5113 otherwise) |
| `event.time` | no | the commit time; on a server, the stream's declared `event-time` | A timestamp column whose value becomes each row's event time |
| `heartbeat.interval` | no | `10s` | How often to write a position marker into the WAL so a quiet table's slot still advances. `0` turns it off |
| `status.interval` | no | `10s` | How often the reader reports its position to the server. Must be positive — PostgreSQL ends a silent replication connection (`wal_sender_timeout`, 60s) |
| `buffer.rows` | no | `100000` | Decoded rows waiting for the engine before the reader stops reading |
| `start.timeout` | no | `30s` | How long opening a reader waits to have read the log as far as it stood |
| `slot.lag.warn.bytes` | no | `1073741824` (1 GiB) | Retained WAL past which the source's health is `DEGRADED` |
| `drop.slot.on.close` | no | `false` | `true` drops the slot whenever the source closes, a node shutdown included — for tests and throwaway environments only |
| `snapshot.mode` | no | `never` | `initial`: a registration starting from nothing first reads the rows already in the table, then streams. Needs a primary key (PRV-5112 without one). `never`: changes from the slot's creation only |
| `snapshot.chunk.rows` | no | `10000` | Rows one snapshot query reads — about how many are held in memory at once |
| `share.reader` | no | `true` | Read by the binding layer. An exactly-once source is never shared, so it has no effect here |
| `tls.*` | — | — | **Refused** (PRV-5110), except `tls.enabled: false`: the driver takes TLS in the URL, and accepting `tls.*` would promise an encryption the connection never made |

Durations take `500ms`, `10s`, `5m`, `1h`, or ISO-8601 (`PT10S`).

### Types

| PostgreSQL | Pravaha |
|---|---|
| `boolean` | `BOOLEAN` |
| `smallint`, `integer`, `bigint` | `INT16`, `INT32`, `INT64` |
| `real`, `double precision` | `FLOAT32`, `FLOAT64` |
| `numeric(p,s)` | `DECIMAL`, its scale kept (unconstrained: `DECIMAL(38,9)`) |
| `text`, `varchar`, `char`, `name`, `uuid`, an enum | `STRING` |
| `bytea` | `BYTES` |
| `date` | `DATE` |
| `timestamp` (read as UTC), `timestamptz` | `TIMESTAMP` |
| `json`, arrays, `time`, ranges, anything else | not mapped: PRV-5113. Leave the column out of a declared `schema` |

## A complete binding

The table `public.orders` holds the same columns as the stream, with `event_time timestamptz`:

```yaml
pravaha:
  checkpoint:
    directory: /opt/pravaha/data/checkpoints     # required: the slot is confirmed only at checkpoints
    interval: 1m
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 30s
  sources:
    orders:
      plugin: postgres-cdc
      options:
        url: "jdbc:postgresql://db-1.internal:5432/sales?sslmode=verify-full&sslrootcert=/opt/pravaha/conf/tls/pg-ca.pem"
        user: pravaha_cdc
        password: "${PRAVAHA_CDC_PASSWORD}"
        table: public.orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        slot: pravaha_orders
        publication: pravaha_orders
        create.slot: "true"
        create.publication: "true"
        heartbeat.interval: 10s
        status.interval: 10s
        buffer.rows: "100000"
        start.timeout: 30s
        slot.lag.warn.bytes: "1073741824"
        drop.slot.on.close: "false"
        snapshot.mode: initial              # the orders already there, then every change
        snapshot.chunk.rows: "10000"
```

Two things this binding does on purpose:

- **The declared `schema` is the stream's, column for column.** The plugin decodes with it and queries
  are planned against `pravaha.streams.orders.schema`; writing the same string in both places is how
  the two stay in step. Without it the plugin uses every column of the table in table order, which is
  right only if the stream declares exactly those.
- **`event-time: event_time`** on the stream is handed to the source as its `event.time`, so each row
  carries the order's own time. Leave both out and a row's event time is the commit time of the
  transaction that changed it — see the pitfall on windows below.

## Weights: what a change becomes

One `UPDATE` moving an order from `OPEN` to `SHIPPED`:

```text
UPDATE orders SET status = 'SHIPPED' WHERE order_id = 90114;
```

arrives as two rows, in one transaction:

```text
(90114, c42, EU, 250000, OPEN,    ...)   weight -1     <- the old row, from the before-image
(90114, c42, EU, 250000, SHIPPED, ...)   weight +1     <- the new row
```

| In PostgreSQL | Reaches the engine as |
|---|---|
| `INSERT` | the new row at `+1` |
| `DELETE` | the whole old row at `−1` |
| `UPDATE` | the old row at `−1`, then the new row at `+1` |
| `TRUNCATE` | **refused** — the stream stops (PRV-5116) |

Every operator does the same arithmetic on both weights, so a filter drops or keeps each row on its
own, a join withdraws a match, and an aggregate subtracts. See [Z-set weights](/help/topics/zset-weights).

**Transactions arrive whole.** Nothing is handed over before the transaction's `Commit`, so a view
never publishes half of one and a rolled-back transaction is never seen. A transaction larger than
any batch the engine asks for is handed over in order across batches, and the offset records how far
in, so a restore inside it delivers exactly the rest.

**An unchanged large value is carried forward.** PostgreSQL does not repeat an out-of-line (TOAST)
value an update did not touch; it sends a placeholder. The plugin fills it from the old row, which
`REPLICA IDENTITY FULL` carries whole, and never writes the placeholder as data.

## Queries over it

The current open orders, withdrawn the moment they ship:

```sql
CREATE CONTINUOUS QUERY open_orders
    KEYED BY (order_id)
AS
SELECT order_id, customer_id, region, amount, status
FROM orders
WHERE status = 'OPEN';
```

The `UPDATE` above: the `−1` of the `OPEN` row passes the filter and removes the order from the view;
the `+1` of the `SHIPPED` row does not. A `DELETE` of an open order removes it the same way. Read it
by key, or aggregate the view at read time:

<!-- sql: read -->
```sql
SELECT region, COUNT(*) AS open_orders, SUM(amount) AS open_value
FROM open_orders
GROUP BY region
```

A running total over the whole table, maintained as rows come and go — a global aggregate has one
group, so it is bounded:

```sql
CREATE CONTINUOUS QUERY open_order_totals
    KEYED BY (open_orders)
AS
SELECT COUNT(*) AS open_orders, SUM(amount) AS open_value
FROM orders
WHERE status = 'OPEN';
```

With 900 open orders, shipping one takes `open_orders` to 899 and `open_value` down by its amount;
a subscriber sees the old row at `−1` and the new one at `+1` in one commit.

A **keyed** aggregate over the stream is refused exactly as it is over any stream — a changelog does
not make one group per customer bounded:

<!-- sql: refused PRV-2050 -->
```sql
SELECT customer_id, SUM(amount) AS open_value FROM orders WHERE status = 'OPEN' GROUP BY customer_id
```

Keep the rows in a keyed view, as `open_orders` does, and group at read time; or window the aggregate.

## Exactly once: the slot is confirmed at checkpoints

The source's offset is a **commit LSN** — a position in the log — and replay from one is
deterministic. Three things make the guarantee `EXACTLY_ONCE`:

1. The reader drops anything ending at or before the position it resumes from, so a restore delivers
   exactly what the checkpoint does not already hold.
2. **The slot is confirmed only at positions a durable checkpoint recorded**, never at what was merely
   delivered, and never backwards. PostgreSQL therefore never discards WAL a restore could ask for.
3. A restore that asks for a position the slot has **already confirmed past** is refused (PRV-5115)
   rather than silently resumed from wherever the slot now is — which is what PostgreSQL itself would
   do.

The consequence is the rule for operating it: **checkpointing is required, not optional.** A node
without `pravaha.checkpoint.directory` never confirms anything, and the slot keeps every byte of WAL
since it was created. `pravaha.checkpoint.interval` (a minute by default) is also roughly how far the
slot's confirmed position trails the reader.

What the guarantee does not survive is the slot itself going away — dropped, invalidated, lost in a
failover. That is detected and refused, never skipped over.

## The slot

A replication slot keeps write-ahead log from its last confirmed position, and **nothing else ever
deletes that WAL**. A Pravaha node that stops reading — dead, partitioned, or simply not
checkpointing — does not fail: the database's disk fills behind it.

**The heartbeat.** The slot moves only when the reader sees something, and it sees only the captured
table. On a quiet table in a busy database the position would stand still while WAL piles up. Every
`heartbeat.interval` the reader writes a tiny non-transactional `pg_logical_emit_message` into the WAL;
it comes back through the slot behind everything committed before it, the position moves to it, and
the next checkpoint confirms it. `heartbeat.interval: 0` turns it off — right only for a table that is
never quiet.

**Slot lag, in the source's health.** The source reports the slot as its health:
`slot 'pravaha_orders' active, retaining N bytes of WAL, confirmed position X/Y (M bytes behind)`. It
turns `DEGRADED` past `slot.lag.warn.bytes`, when PostgreSQL marks the slot's WAL `unreserved`, or
while its reader is reconnecting; `UNHEALTHY` when the slot is gone or invalidated. Watch it **from the
database too** — a node that is down cannot report its own lag:

```text
SELECT slot_name, active,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn))        AS retained,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), confirmed_flush_lsn)) AS confirmed_lag,
       wal_status
FROM pg_replication_slots WHERE slot_name LIKE 'pravaha_%';
```

Alert on `retained` growing without bound, on `active = false` for longer than a
restart takes, and on `wal_status` leaving `reserved`.

**Cap it.** `max_slot_wal_keep_size` invalidates a slot that would hold more than the limit — the disk
is protected, the slot is lost. Choose it as "how long a Pravaha outage may last" times "WAL written
per hour".

**One query per binding.** A binding names one slot, a slot streams to one connection at a time, and
an exactly-once source is never shared. So a second, *different* query over the same binding is
**refused at registration** with `PRV-8028`, naming the query that holds it (CDCREPL-2); it used to
be accepted, wait for the slot for about fifteen seconds and fail `PRV-5117`. The same SQL registered
under a second name is one computation and one reader, and is not refused. To ask two different
questions of one table, either bind it twice — two stream names, each with a `slot` of its own — or
register one query that keeps what both need and query its view.

The engine does not derive a slot per query from the binding, on purpose. Every slot retains WAL on
the database until it is confirmed, so slots made per query would multiply what the database keeps
by the number of queries without anyone sizing the disk for it, and any slot outliving its query — a
node lost before a drop, a drop while the database is unreachable — would retain WAL until the disk
filled. A binding is where an operator names a slot and plans for it.

## The initial snapshot

With `snapshot.mode: never`, the default, the source delivers changes **from the moment its slot was
created**, and a view over a table that already holds rows starts without them. With
`snapshot.mode: initial`, a registration that starts from nothing reads those rows first:

- **One point in the log.** A temporary slot, `<slot>_snap_<random>`, exports a snapshot that sees
  exactly the transactions committed before the slot's consistent point. The table is read under it,
  in primary-key order, every row at +1; the changes before that point are dropped (the snapshot has
  them) and every change after it is streamed. The temporary slot is gone once the snapshot is taken.
- **A restart half-way through is exact.** The checkpoint records the last key delivered —
  `lsn=0/16B3748;snapshot=20000@20417`: twenty thousand rows, the last with key 20417. A restore takes
  a new snapshot, keeps from the log only the changes to rows at or below that key, and reads the
  rest of the table above it. No row twice, none missing, whatever changed while the node was down.
- **Bounded memory.** The table is read `snapshot.chunk.rows` at a time, never more than two chunks
  ahead of the engine. The source's health shows the progress: `initial snapshot in progress: N rows
  delivered of about M`.

What it needs: a **primary key** (PRV-5112 without one), `SELECT` on the table, room in
`max_replication_slots` for the temporary slot, and **no transaction left open** from before it
starts — PostgreSQL creates the temporary slot only once every transaction already running has
ended, and past `start.timeout` the source is refused with PRV-5118. While the table is read, one
`REPEATABLE READ` transaction stays open (holding back vacuum) and the slot retains WAL from the
snapshot's point. A checkpoint taken mid-snapshot finishes the snapshot whatever `snapshot.mode` now
says; one written before snapshots existed resumes as the stream it always was.

## Recovery: when the slot cannot be resumed

A dropped slot, an invalidated one (`wal_status = 'lost'`), a `TRUNCATE` of the captured table, and a
restore the slot has overtaken all end the same way: **the changes in between are gone from the log**,
and nothing can replay them. The recovery is one procedure:

1. Stop the registration (`DROP CONTINUOUS QUERY open_orders`).
2. Delete its checkpoint directory, so no restore asks for the lost position.
3. Drop the slot on the database, after the reader has stopped (an active slot cannot be dropped):

    ```text
    SELECT pg_drop_replication_slot('pravaha_orders');
    ```

4. Register the query again. It is rebuilt from the table's changes from that moment — and, with
   `snapshot.mode: initial`, from the rows already in the table first.

After a **failover**, whether the slot exists on the new primary depends on the PostgreSQL version and
on slot synchronisation (`sync_replication_slots`, PostgreSQL 17). Where it does not, the source
refuses at the next open, and the recovery is the one above.

**Retiring a binding for good**, drop its slot and, if nothing else uses it, its publication — the
slot does not go with the registration:

```text
SELECT pg_drop_replication_slot('pravaha_orders');
DROP PUBLICATION IF EXISTS pravaha_orders;
```

## Troubleshooting

| Code | When | Usually |
|---|---|---|
| [PRV-5110](/help/codes/PRV-5110) | at configuration | An option missing or malformed: a URL that is not `jdbc:postgresql:`, a slot name with a capital, `status.interval: 0`, a `tls.*` option |
| [PRV-5111](/help/codes/PRV-5111) | at open | The database unreachable, the credentials refused, the PostgreSQL driver not on the classpath, or `permission denied for database` creating the publication (`GRANT CREATE ON DATABASE`) |
| [PRV-5112](/help/codes/PRV-5112) | at open | A prerequisite missing — `wal_level`, `REPLICA IDENTITY FULL`, the publication, PostgreSQL 14 — or the slot missing, invalidated, or another plugin's. The message names the statement that fixes it |
| [PRV-5113](/help/codes/PRV-5113) | at open | A declared `schema` that disagrees with the table, or a column with no mapping |
| [PRV-5114](/help/codes/PRV-5114) | at restore | A checkpoint holds an offset this plugin did not write |
| [PRV-5115](/help/codes/PRV-5115) | at restore | The slot has confirmed past the checkpoint being restored. Recover as above |
| [PRV-5116](/help/codes/PRV-5116) | while running | A `TRUNCATE`, or a key-only before-image (the replica identity was changed while capturing). Everything before it was delivered. Recover as above |
| [PRV-5117](/help/codes/PRV-5117) | while running | The replication stream failed in a way no reconnect fixes: the slot dropped or invalidated, the role's privileges revoked |
| [PRV-5118](/help/codes/PRV-5118) | at open, or during a snapshot | The initial snapshot could not start — a transaction left open since before it, or no room for the temporary slot — or its read failed. A restart resumes it exactly |

## Pitfalls

!!! danger "Pitfall: a Pravaha outage becomes a PostgreSQL outage"
    The slot retains WAL until confirmed. A node that is down, or a node that never checkpoints, fills
    the database's disk. Set `pravaha.checkpoint.directory`, keep the heartbeat on, cap the slot with
    `max_slot_wal_keep_size`, and monitor `pg_replication_slots` from the database side.

!!! danger "Pitfall: `TRUNCATE` stops the source"
    A truncate carries no rows, so there is nothing to retract, and retracting "what the view holds"
    would need the table's contents at that point in the log, which the log does not have. Use
    `DELETE FROM` on a captured table to have its rows withdrawn.

!!! warning "Pitfall: windows over a changelog group by when the change happened"
    An update's `−1` and `+1` carry one event time. With the default (the commit time) a correction to
    last month's order lands in *this* minute's window, not last month's; with `event.time` naming the
    row's own timestamp, a correction to an old row arrives far behind the watermark and is late. A
    keyed view of current state, or a global aggregate, is the natural shape over this source.

!!! warning "Pitfall: `MIN` and `MAX` cannot take a retraction"
    Knowing the current minimum does not tell you the previous one once it is withdrawn, so a global
    `MIN`/`MAX` that receives a `−1` fails at runtime rather than guessing. Over this source,
    aggregate with `COUNT` and `SUM`, or keep the rows in a keyed view and take the extreme at read
    time.

!!! warning "Pitfall: `drop.slot.on.close` in production"
    It drops the slot at every shutdown, so every restart has no position to resume from and a
    restored view misses everything in between. It exists for tests.

!!! note "Polling is not the legacy option"
    CDC needs a restart for `wal_level`, a `REPLICATION` role and `REPLICA IDENTITY FULL` on
    production tables — permissions a DBA grants. Where deletes need not reduce an answer, the
    [jdbc source](/help/topics/source-jdbc) needs only `SELECT` and leaves no state on the server.

## Where next

- [The Kafka sink](/help/topics/sink-kafka) — deletes in the table reaching a topic as tombstones
- [The Kafka source](/help/topics/source-kafka) — a view's changes read back from a topic by another query
- [Z-set weights](/help/topics/zset-weights) — what `+1` and `−1` do in every operator
- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what the slot's confirmation rests on
- [The jdbc source](/help/topics/source-jdbc) — polling, when CDC is not available
- [Sources overview](/help/topics/sources-overview) — every source side by side
