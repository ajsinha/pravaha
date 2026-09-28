# Stock levels from MySQL — a retail case study

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

**Store:** MySQL 8, read through its binary log · **Time to first result:** about fifteen minutes ·
**Shows:** change data capture with `mysql-cdc`, updates as `-1`/`+1`, a filter whose rows leave as
well as arrive, deletes, an alert that clears itself

## The problem

A retailer keeps its stock in MySQL: one row per product per warehouse, with how many are on hand
and the level at which to reorder. The point-of-sale system, the goods-in scanner and the
merchandising team all write to that table. Buyers want to know **the moment a line falls to its
reorder point**, and — just as important — the moment it stops being a problem, because a delivery
arrived or the line was discontinued.

The usual answer is a job that polls `SELECT ... WHERE on_hand <= reorder_point` every few minutes.
It misses a line that dips and recovers between polls, it cannot say *when* anything changed, and it
puts a scan on the database that serves the tills. The table already records every change, in its
binary log. This study reads that instead.

## What you will build

```
  MySQL  inventory.stock          Pravaha                                 buyers
 ┌──────────────────────────┐   ┌───────────────────────────────┐    ┌────────────────────────┐
 │ INSERT / UPDATE / DELETE │   │ stock_levels  (the table)     │ ─► │ "how many kettles?"    │
 │  └─► row-based binlog ───┼─► │ low_stock     (a filter)      │ ─► │ alert raised / cleared │
 └──────────────────────────┘   └───────────────────────────────┘    └────────────────────────┘
        mysql-cdc: the node registers as a replica
```

Two continuous queries over one table's changes. Nothing polls the table.

## Why the changes have signs

`mysql-cdc` reads the binary log the way a replica does, so each row event arrives with its whole
row, **before and after**. The engine turns each into weighted rows:

| In MySQL | What the queries receive |
|---|---|
| `INSERT` | the new row at `+1` |
| `UPDATE` | the old row at `-1`, then the new row at `+1` |
| `DELETE` | the old row at `-1` |

That is what makes a view over a table *stay equal to the table*. A filter over an update does not
have to be told the row moved: the `-1` withdraws whatever the old row contributed and the `+1` adds
what the new one does. A line that goes under its reorder point enters `low_stock`; the delivery that
tops it up is an update whose `-1` takes it out again. A transaction arrives whole, at its commit, so
no reader sees half of one.

## The data model

One table, one stream — `stock`, every column of `inventory.stock` in the table's order:

| Column | Type in MySQL | In the stream | Meaning |
|---|---|---|---|
| `sku` | `VARCHAR(32)` | STRING | The product |
| `warehouse` | `VARCHAR(8)` | STRING | `LDN` or `MAN` |
| `on_hand` | `INT` | INT32 | Units in the warehouse |
| `reorder_point` | `INT` | INT32 | Reorder when `on_hand` falls to this |
| `updated_at` | `DATETIME` | TIMESTAMP | When the row last changed, in UTC — the stream's event time |

`(sku, warehouse)` is the table's primary key, and it is the key of both views. The DDL is
[`data/schema.sql`](data/schema.sql); the build checks the stream against
[`schema/streams.properties`](schema/streams.properties); the node reads
[`conf/application.yaml`](conf/application.yaml).

## Step 1 — MySQL, with a binary log

[`../SETUP.md`](../SETUP.md) starts MySQL 8 in Docker as `pravaha-mysql`. MySQL 8's defaults are
what `mysql-cdc` needs — `log_bin` on, `binlog_format = ROW`, `binlog_row_image = FULL` — and the
binding refuses to start without them (`PRV-5152`), naming the statement that fixes each. Create
the table and the replication user:

```bash
docker exec -i pravaha-mysql mysql -uroot -ppravaha < data/schema.sql
```

The user needs `REPLICATION SLAVE` and `REPLICATION CLIENT`: it connects as a replica, which is why
nothing has to be installed on the database host.

## Step 2 — start the node

From this directory:

```bash
PRAVAHA_CDC_PASSWORD=pravaha \
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
```

The binding starts at the end of the binary log: it streams **changes only**. Rows already in the
table when the node first starts are not read — `snapshot.mode: initial` is not built yet — so start
the node before the morning is loaded. After a restart it resumes from the `file:position` its last
checkpoint recorded, exactly once.

## Step 3 — the two continuous queries

### The table, kept current

[`sql/01-continuous-stock-levels.sql`](sql/01-continuous-stock-levels.sql):

```sql
SELECT sku, warehouse, on_hand, reorder_point, updated_at
FROM stock
```

No filter and no aggregate: a keyed view of the table, `[0, 1]` — `(sku, warehouse)`. Every read of
it is answered from the node's memory, so the buyers' questions never reach the database that runs
the tills.

### Lines at or under their reorder point

[`sql/02-continuous-low-stock.sql`](sql/02-continuous-low-stock.sql):

```sql
SELECT sku, warehouse, on_hand, reorder_point, updated_at
FROM stock
WHERE on_hand <= reorder_point
```

A filter comparing two columns of the same row. There is no window and no state beyond the view:
because updates arrive as `-1`/`+1`, the view holds exactly the lines that are low *now*.

### Register them

```bash
python3 python/run.py --url grpc://localhost:19090
```

[`python/run.py`](python/run.py) registers both — `client.register(name, sql, [0, 1])` — and asks
the questions below. Java: [`java/InventoryExample.java`](java/InventoryExample.java). From a shell:
`pravaha register --name low_stock --sql-file sql/02-continuous-low-stock.sql --keys 0,1`.

## Step 4 — the morning

```bash
python3 data/generate_stock.py | docker exec -i pravaha-mysql mysql -uroot -ppravaha inventory
```

[`data/generate_stock.py`](data/generate_stock.py) opens six lines at 09:00 UTC and then, one
statement each:

| Time | Statement | What it means |
|---|---|---|
| 09:05 | `UPDATE` sku-200 LDN 12 → 7 | toasters sell; 7 is under the reorder point of 8 |
| 09:10 | `UPDATE` sku-300 LDN 6 → 2 | blenders sell; under 5 |
| 09:15 | `UPDATE` sku-100 MAN 25 → 22 | kettles sell in Manchester; nothing to report |
| 09:20 | `UPDATE` sku-200 LDN 7 → 27 | a delivery; the toaster alert should clear |
| 09:25 | `DELETE` sku-400 MAN | mixers, already under their reorder point, are discontinued |
| 09:30 | `UPDATE` sku-100 LDN 40 → 10 | a trade order takes thirty kettles; 10 is the reorder point |

`python3 data/generate_stock.py --changes` prints the same morning as the binary log delivers it —
each update a `-` line for the old row and a line for the new — which is the sample the build runs
these queries over.

## Step 5 — ask questions

Every answer below is worked out from that morning and checked by the build
([`CaseStudyRunTest`](../../../pravaha-it/src/test/java/com/ash/messaging/pravaha/it/CaseStudyRunTest.java)
runs both queries over the changes and compares).

### One product, every warehouse

[`sql/03-read-one-sku.sql`](sql/03-read-one-sku.sql):

```sql
SELECT warehouse, on_hand, reorder_point, updated_at
FROM stock_levels
WHERE sku = ?
```

Bound to `sku-100`:

```text
-- sku-100 in every warehouse
   {'warehouse': 'LDN', 'on_hand': 10, 'reorder_point': 10, 'updated_at': '2026-06-01T09:30:00+00:00'}
   {'warehouse': 'MAN', 'on_hand': 22, 'reorder_point': 10, 'updated_at': '2026-06-01T09:15:00+00:00'}
```

Bound to `sku-400`, nothing: the `DELETE` retracted the row, so the view no longer has it.

### Units per warehouse

[`sql/04-read-warehouse-totals.sql`](sql/04-read-warehouse-totals.sql):

```sql
SELECT warehouse, COUNT(*) AS lines, SUM(on_hand) AS units
FROM stock_levels
GROUP BY warehouse
```

```text
-- units per warehouse
   {'warehouse': 'LDN', 'lines': 3, 'units': 39}
   {'warehouse': 'MAN', 'lines': 2, 'units': 52}
```

39 is 10 + 27 + 2, today's numbers — not 40 + 12 + 6 + the changes, because each update withdrew
the row it replaced. A view fed by an append-only feed of the same updates would have counted every
version of every row.

### What is low in one warehouse

[`sql/05-read-low-stock-in-warehouse.sql`](sql/05-read-low-stock-in-warehouse.sql):

```sql
SELECT sku, on_hand, reorder_point
FROM low_stock
WHERE warehouse = ?
```

Bound to `LDN`:

```text
-- low stock in LDN
   {'sku': 'sku-100', 'on_hand': 10, 'reorder_point': 10}
   {'sku': 'sku-300', 'on_hand': 2, 'reorder_point': 5}
```

`sku-200` is not there: it went under at 09:05 and the 09:20 delivery took it out. Bound to `MAN`,
nothing — `sku-400` opened under its reorder point and its row was deleted.

## Step 6 — follow the alerts

```bash
python3 python/run.py --url grpc://localhost:19090 --watch
```

Run it before Step 4 and each statement arrives as its own commit. The ones that touch `low_stock`:

```text
-- commit        09:00, the opening INSERTs
   +1 {'sku': 'sku-400', 'warehouse': 'MAN', 'on_hand': 3, 'reorder_point': 4, ...}
-- commit        09:05
   +1 {'sku': 'sku-200', 'warehouse': 'LDN', 'on_hand': 7, 'reorder_point': 8, ...}
-- commit        09:10
   +1 {'sku': 'sku-300', 'warehouse': 'LDN', 'on_hand': 2, 'reorder_point': 5, ...}
-- commit        09:20, the delivery
   -1 {'sku': 'sku-200', 'warehouse': 'LDN', 'on_hand': 7, 'reorder_point': 8, ...}
-- commit        09:25, the DELETE
   -1 {'sku': 'sku-400', 'warehouse': 'MAN', 'on_hand': 3, 'reorder_point': 4, ...}
-- commit        09:30
   +1 {'sku': 'sku-100', 'warehouse': 'LDN', 'on_hand': 10, 'reorder_point': 10, ...}
```

The 09:15 update never appears: its old row and its new one are both above the reorder point, so
the filter passes neither half. An alert that *clears* is a `-1` — a consumer that only listened
for rows arriving would page the buyer about toasters for ever.

## Step 7 — let the engine page the buyers, and clear the page

Step 6 is a program you run and keep running. An **alert** ([ADR-057](../../../docs/adr/057-alerts.md))
is the engine doing it: it follows `low_stock`'s answer, and for each line says when its row
**enters** — the update that takes it to its reorder point — and when it **leaves** — the delivery
that tops it up, or the `DELETE`. The node's configuration binds a channel called `buyers` that writes
each notification to the node's log (`plugin: log`, in [`conf/application.yaml`](conf/application.yaml));
point it at a real receiver with the webhook shown below.

```bash
pravaha query --url grpc://localhost:19090 --sql \
  "CREATE ALERT low_stock_alert ON low_stock NOTIFY buyers
   WITH (severity = 'warning', include = (on_hand, reorder_point))"
```

Run it before Step 4, and the morning says, in order:

```text
FIRED   sku-400 MAN   on_hand 3 of 4     09:00, opened under its reorder point
FIRED   sku-200 LDN   on_hand 7 of 8     09:05, the update crosses it
FIRED   sku-300 LDN   on_hand 2 of 5     09:10
CLEARED sku-200 LDN                      09:20, the delivery -- its -1 takes the line out
CLEARED sku-400 MAN                      09:25, the DELETE
FIRED   sku-100 LDN   on_hand 10 of 10   09:30, <= counts
```

The 09:15 update to `sku-100 MAN` says nothing: it is above its reorder point before and after.
Nothing here polls the table and nothing here guesses that a line recovered: the clear is the
retraction the binary log delivered.

```bash
pravaha alerts show low_stock_alert --http http://localhost:18080
pravaha alerts snooze low_stock_alert 2h        # a stock-take: say nothing, catch up after
pravaha alerts ack low_stock_alert --key "sku=sku-300, warehouse=LDN"
```

**What it promises.** The alert's state is exactly once — each decision is journalled beside the
registry journal before anything is sent — so a node restarted mid-morning does not page about lines
that were already low, and a delivery that arrived while it was down is still announced as a clear.
Delivery is at least once, with the same `Idempotency-Key` on every attempt, so a receiver that must
page once de-duplicates on it. A line that flaps around its reorder point during a busy hour is what
`WITH (clear_after = '15m')` and `dedupe = '30m'` are for. `pravaha-it`'s
[`RetailLowStockAlertEndToEndTest`](../../../pravaha-it/src/test/java/com/ash/messaging/pravaha/it/alerts/RetailLowStockAlertEndToEndTest.java)
replays this morning on a real node through a signed webhook, restarting the node after 09:15.

**A webhook instead of the log.** In `conf/application.yaml`, replace the `buyers` channel:

```yaml
  notifiers:
    buyers:
      plugin: webhook
      options:
        url: https://hooks.example.com/buyers
        secret-env: PRAVAHA_BUYERS_HOOK_SECRET   # the HMAC key; never written into this file
```

Each notification is then a signed JSON `POST` (`X-Pravaha-Signature: sha256=` HMAC of
`<timestamp>.<body>`), retried with backoff. How a receiver verifies it is in
[`OPERATIONS.md`](../../../docs/OPERATIONS.md), *Alerts and notifier channels*.

## Making this yours

| To change | Do this |
|---|---|
| The alert threshold | The `WHERE`. `on_hand <= reorder_point / 2` for "critically low" is a second registration |
| Who is paged, and when | The alert: `WHERE warehouse = 'LDN'` for one warehouse's buyers, `fire_after = '10m'` to ignore a till's brief dip, `severity = 'critical'` on the "critically low" view |
| Another table | Another source binding, one table each. The stream takes the table's name |
| A replica rather than the primary | Point `host` at it; it needs its own binary log (`log_replica_updates`, on by default in 8.0) |
| Rows already in the table | Not yet: `snapshot.mode: initial` is refused (`PRV-5150`). Start the node first, or touch each row once |
| Movements per hour | A window over `updated_at` — mind that an update's `-1` carries the old row's time |

## Pitfalls

- **Changes only.** A node started after the table was loaded sees only what changes next; the rows
  nobody touches are not in the view. This is the biggest difference from a polling source.
- **A purged binary log cannot be resumed from.** If `binlog_expire_logs_seconds` removes the file a
  checkpoint names, the restore is refused (`PRV-5155`) rather than read on from wherever the log now
  starts. Keep the logs longer than the node can be down.
- **`TRUNCATE`, `ALTER`, `DROP` or `RENAME` of the table stops the stream** (`PRV-5156`). The row
  mapping was typed from `information_schema` at start, and reading on past a schema change would
  decode rows against the wrong columns. Re-register after the change.
- **`binlog_transaction_compression` must be off**, and `binlog_row_image` must be `FULL`: a
  `MINIMAL` image has no old row to retract.
- **`DATETIME` has no zone.** It is read as UTC. Write UTC, or use `TIMESTAMP`.

The full list of what runs and what is refused is
[`docs/CONTINUOUS_QUERIES.md`](../../../docs/CONTINUOUS_QUERIES.md); the connector's options are in
[`docs/CONNECTORS.md`](../../../docs/CONNECTORS.md).
