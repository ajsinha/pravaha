---
title: The mysql-cdc source — change data capture from MySQL
slug: source-mysql-cdc
category: sources
order: 46
icon: journal-arrow-down
summary: "Streams one MySQL table's inserts, updates and deletes from the row-based binary log, the plugin registered as a replica: +1 and −1 weights, an update as both, exactly once from a binlog file and offset. Changes only in this version."
badge: SOURCE
audience: Operators
keywords: [mysql-cdc, cdc, change data capture, mysql, binlog, binary log, binlog_format, binlog_row_image, row-based replication, replica, server.id, binlog_expire_logs_seconds, truncate, debezium, retraction, delete]
guide: connectors
related: [sources-overview, source-postgres-cdc, source-jdbc, zset-weights, checkpoints-recovery, delivery-guarantees, errors-plugins]
listed_on: sources-overview
---

The `mysql-cdc` plugin reads a MySQL table's **changes** — every committed `INSERT`, `UPDATE` and
`DELETE` — from the server's own binary log. It registers with the server as a replica and decodes
row events itself, on the model of `postgres-cdc` and without Debezium
([ADR-041](/help/decisions/041-change-data-capture-without-debezium)).

An insert arrives at weight `+1`, a delete as the whole old row at `−1`, and an update as **both** —
the old row at `−1`, then the new one at `+1` — so a query's answer goes *down* when the table does.
A transaction arrives whole, and only once committed.

## At a glance

| | |
|---|---|
| Plugin name | `mysql-cdc` |
| Module | `plugins/pravaha-plugin-mysql-cdc` — in the server jar; needs **no JDBC driver** |
| Database | MySQL 8 with the binary log on |
| Kind | stream source, one table per binding |
| Delivery guarantee | **`EXACTLY_ONCE`** — the offset is a binlog file and offset at a transaction boundary |
| Emits deletes / before-image | **yes / yes** |
| Initial snapshot | **not built**: `snapshot.mode: initial` is refused (PRV-5150). Changes from the moment the plugin opened only |
| Schema comes from | the table's columns in `information_schema`; a declared `schema` is refused |
| Event time | the transaction's **commit time**, or an `event.time` `DATETIME`/`TIMESTAMP` column |
| TLS | not yet — plaintext only, and `tls.*` is refused |
| Shared between queries | **no** — the binding reads as one replica `server.id`, and one query reads it; a second, different query over the binding is refused (PRV-8028). Bind the table again with a `server.id` of its own |

## Before the first registration: the server

Each of these missing is refused when the source opens, with PRV-5152 and the statement that fixes it.

```text
-- MySQL statements, not Pravaha SQL. ROW and FULL are MySQL 8's defaults.
SET PERSIST binlog_format = 'ROW';
SET PERSIST binlog_row_image = 'FULL';
SET PERSIST binlog_transaction_compression = OFF;

CREATE USER 'pravaha_cdc'@'%' IDENTIFIED BY '...';
GRANT REPLICATION SLAVE, REPLICATION CLIENT ON *.* TO 'pravaha_cdc'@'%';
GRANT SELECT ON sales.orders TO 'pravaha_cdc'@'%';
```

- **`binlog_row_image = FULL`.** With `MINIMAL`, a delete carries only the key, the retraction
  matches nothing, and the view stays wrong for ever with no error anywhere. That is the trap this
  source refuses.
- **The privileges are granted directly.** `SHOW GRANTS` does not expand roles, so a privilege held
  only through a role is not seen.
- **`binlog_expire_logs_seconds` longer than any outage.** MySQL deletes binlog files by age, not by
  what a replica has read. A checkpoint whose file is gone is refused at restart (PRV-5155).

## A binding

```yaml
pravaha:
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
  sources:
    orders:
      plugin: mysql-cdc
      options:
        host: db-1.internal
        port: "3306"
        user: pravaha_cdc
        password: "${PRAVAHA_CDC_PASSWORD}"
        table: sales.orders
        server.id: "6401"
        event.time: created_at
```

| Option | Required | Default | Meaning |
|---|---|---|---|
| `host`, `port` | host | port `3306` | the server |
| `user`, `password` | user | | a user with the grants above |
| `table` | yes | | `database.table` |
| `stream` | no | the table name | the stream's name |
| `server.id` | no | derived from the binding | the replica id; must be unique among the server's replicas |
| `event.time` | no | commit time | a `DATETIME` or `TIMESTAMP` column |
| `buffer.rows` | no | `100000` | decoded rows held ahead of the engine |
| `start.timeout` | no | `30s` | how long opening a reader waits for the binlog connection |
| `heartbeat.interval` | no | `10s` | how often the server sends a heartbeat on a quiet log; `0` turns it off |
| `snapshot.mode` | no | `never` | `never` only; `initial` is refused |

Types map exactly or are refused (PRV-5153): integers (unsigned ones widened; `BIGINT UNSIGNED` to
`DECIMAL(38,0)`), `FLOAT`, `DOUBLE`, `DECIMAL` up to 38 digits, `CHAR`/`VARCHAR`/`TEXT` in utf8mb4,
utf8mb3, latin1 or ascii, `BINARY`/`VARBINARY`/`BLOB`, `DATE`, `DATETIME` (read as UTC) and `TIMESTAMP`.
Not `JSON`, `ENUM`, `SET`, `BIT`, `TIME`, `YEAR` or spatial types.

## What stops it

- A `TRUNCATE` of the table — it names no rows, so there is nothing to retract — or an `ALTER`,
  `DROP` or `RENAME` of it: PRV-5156. Everything before it was delivered.
- The binlog connection failing ten times in a row: PRV-5157.

For both, and for PRV-5155: stop the registration, delete its checkpoint directory, register again.
Every code is on [the plugin codes page](/help/topics/errors-plugins).
