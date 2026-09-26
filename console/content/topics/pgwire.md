---
title: The PostgreSQL gateway
slug: pgwire
category: reading
order: 40
icon: server
summary: "Reading maintained views from psql, DBeaver, Grafana or any PostgreSQL driver: turning the gateway on, connecting, the types it sends, what it refuses (writes, PRV-6211, BYTES and TIME), and TLS on the same port."
badge: GATEWAY
audience: Developers
keywords: [psql, postgres, postgresql, pgwire, dbeaver, grafana, jdbc, pgjdbc, 5432, sslmode, "25006", read-only, "\\d", PRV-6211, PRV-6200]
guide: architecture
related: [point-reads, client-snippets, authentication, tls, consistency]
---

Almost nothing a person already has installed speaks Arrow Flight SQL; almost everything speaks
PostgreSQL. The PostgreSQL gateway is a second door onto the same rooms: it speaks the PostgreSQL v3
wire protocol so that `psql`, DBeaver, Grafana, an ORM or a notebook can **read** a maintained view with
the driver it already has. It is a transport and nothing more — every read goes through the same
planner, the same `ViewQuery` path, the same authorization and the same audit trail as a Flight read.
It is **read-only**, and **off by default**.

## At a glance

| | |
|---|---|
| Module | `pravaha-pgwire`, inside `pravaha-server` |
| Turned on by | `pravaha.pgwire.enabled: true` (default `false`) |
| Listens on | `pravaha.pgwire.host` (default `0.0.0.0`) : `pravaha.pgwire.port` (default `5432`) |
| Protocol | Simple **and** extended query protocol (Parse/Bind/Describe/Execute/Sync); `$1`-style parameters |
| Announces itself as | PostgreSQL `9.4.26 (Pravaha)` — the version whose catalogue queries `psql` sends and the shim answers |
| Catalogue | A minimal read-only `pg_catalog` (`pg_class`, `pg_namespace`, `pg_attribute`, `version()`, `current_schema()`), filtered by what you may read — so `\d` and a driver's `getTables()` work |
| Authentication | The **password** is the node's credential (a bearer token under `authentication: token`); the user name is informational |
| Writes | None. `INSERT`/`UPDATE`/`DELETE` are refused by the planner; continuous-query statements with PRV-6211 (SQLSTATE `25006`) |
| TLS | `pravaha.pgwire.tls.certificate` and `pravaha.pgwire.tls.key` (PEM chain, PKCS#8 key): the gateway then answers `SSLRequest` on the same port. **Off until both are set** — see below |

## Turning it on

In the node's `application.yaml`, or any Spring property source:

```yaml
pravaha:
  pgwire:
    enabled: true
    host: 127.0.0.1        # loopback until TLS is configured
    port: 5433             # 5432 is usually a real PostgreSQL on the same host
  security:
    authentication: token
    policy: authenticated
```

The node logs the gateway at startup:

```text
PostgreSQL wire protocol listening on 127.0.0.1:5433 -- NO TLS, the credential crosses the wire in the clear; use loopback or a terminator
```

!!! danger "Without TLS the credential crosses the wire in the clear"
    The PostgreSQL password *is* the node's bearer token. Until `pravaha.pgwire.tls.certificate` and
    `pravaha.pgwire.tls.key` are set, the gateway answers every `SSLRequest` with "no" and the token
    travels as it is — which is what that startup line says. Bind it to loopback, or configure TLS.

With the pair set, the gateway negotiates TLS on the same port and authenticates **after** the
handshake, so the token is always inside it:

```yaml
pravaha:
  pgwire:
    enabled: true
    host: 0.0.0.0
    port: 5432
    tls:
      certificate: /opt/pravaha/conf/tls/server-chain.pem
      key: /opt/pravaha/conf/tls/server-key.pem
```

```text
PostgreSQL wire protocol listening on 0.0.0.0:5432 over TLS
```

Connect with `sslmode=verify-full` (and `sslrootcert=` for a private CA): `require` encrypts and checks
nothing. One of the pair without the other, an unreadable file or a PKCS#1 key stops the node at
startup with [PRV-6206](/help/codes/PRV-6206) — never a gateway silently serving plaintext. See
[TLS everywhere](/help/topics/tls).

## Connecting

`psql`, with the token as the password:

```bash
PGPASSWORD="$PRAVAHA_TOKEN" psql "host=127.0.0.1 port=5433 dbname=pravaha user=ann"
```

```text
psql (16.4, server 9.4.26 (Pravaha))
Type "help" for help.

pravaha=>
```

The user name is informational: the **credential decides who you are**. If the token belongs to a
different principal from the name you typed, the gateway says so rather than silently running as
somebody else:

```text
NOTICE:  connected as 'ann', not 'dana': the credential determines the principal, not the user name in the startup packet
```

On a node with `authentication: none` every session runs as the anonymous principal and no password is
asked for.

A JDBC URL for the stock PostgreSQL driver (pgjdbc):

```text
jdbc:postgresql://127.0.0.1:5433/pravaha?user=ann&password=<token>
```

Plain connections, `DatabaseMetaData.getTables()`/`getColumns()` and a `PreparedStatement` with a bound
parameter are driven by the module's own tests through the real driver, without
`preferQueryMode=simple`. That is one gateway's worth of protocol coverage, not a claim about every
statement every ORM might send.

## Finding the views

```text
pravaha=> \d
          List of relations
 Schema |     Name      | Type  | Owner
--------+---------------+-------+-------
 public | big_txn       | table | pravaha
 public | hourly_spend  | table | pravaha
 public | region_revenue| table | pravaha
(3 rows)

pravaha=> \d hourly_spend
                Table "public.hourly_spend"
    Column    |           Type           | Modifiers
--------------+--------------------------+-----------
 user_id      | text                     | not null
 window_start | timestamp with time zone | not null
 window_end   | timestamp with time zone | not null
 spend        | bigint                   | not null
```

(Illustrative; the listing shows exactly the views your principal may read, computed from the node's
catalogue on every call.)

## Reading

Every read on [Point reads](/help/topics/point-reads) works here unchanged. At the prompt:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend
FROM hourly_spend
WHERE user_id = 'u1'
```

```text
 user_id |       window_end       | spend
---------+------------------------+-------
 u1      | 2026-09-19 09:00:00+00 |  4200
 u1      | 2026-09-19 10:00:00+00 |  1350
(2 rows)
```

A dashboard summary — Grafana's usual shape:

<!-- sql: read -->
```sql
SELECT region, SUM(revenue) AS revenue, SUM(orders) AS orders
FROM region_revenue
GROUP BY region
```

```text
 region | revenue | orders
--------+---------+--------
 EMEA   |  982300 |   4410
 APAC   |  611850 |   2982
 AMER   | 1204990 |   5120
(3 rows)
```

<!-- sql: read -->
```sql
SELECT symbol, window_end, shares, notional_cents
FROM symbol_volume
WHERE symbol IN ('ACME', 'GLOBX') AND shares > 10000
```

<!-- sql: read -->
```sql
SELECT txn_id, user_id, amount
FROM big_txn
WHERE merchant LIKE 'TRAVEL%' AND amount >= 2500
```

### Parameters

PostgreSQL's own placeholders, `$1`, `$2`, …, are accepted over the extended protocol (a driver's
prepared statement: `Parse`/`Bind`/`Execute`) and rewritten to the engine's positional `?` before
planning. From Java with pgjdbc — a shape the module's own tests drive:

```java
try (Connection db = DriverManager.getConnection(
        "jdbc:postgresql://127.0.0.1:5433/pravaha", "ann", System.getenv("PRAVAHA_TOKEN"));
     PreparedStatement read = db.prepareStatement(
        "SELECT window_end, spend FROM hourly_spend WHERE user_id = ?")) {
    read.setString(1, "u1");
    try (ResultSet rows = read.executeQuery()) {
        while (rows.next()) {
            System.out.println(rows.getTimestamp(1).toInstant() + " " + rows.getLong(2));
        }
    }
}
```

```text
2026-09-19T09:00:00Z 4200
2026-09-19T10:00:00Z 1350
```

From Python with psycopg:

```python
import psycopg

with psycopg.connect(host="127.0.0.1", port=5433, dbname="pravaha",
                     user="ann", password=token) as conn:
    rows = conn.execute(
        "SELECT window_end, spend FROM hourly_spend WHERE user_id = %s", ["u1"]).fetchall()
    print(rows)
```

```text
[(datetime.datetime(2026, 9, 19, 9, 0, tzinfo=datetime.timezone.utc), 4200), (datetime.datetime(2026, 9, 19, 10, 0, tzinfo=datetime.timezone.utc), 1350)]
```

(psycopg is not among the drivers the module's tests drive; pgjdbc and `psql` are.)

A `$n` that is reused, or used out of order, is refused with PRV-6210 rather than rewritten into
something that means a different thing. A parameter in a position the engine does not allow, the
wrong number of values, and a value of the wrong type are refused with the same codes as over Flight:
PRV-2063, PRV-2061 and PRV-2062.

## Types on the wire

Values are sent as text, with these PostgreSQL types in `RowDescription`:

| Pravaha type | PostgreSQL type (OID) | Text form |
|---|---|---|
| `BOOLEAN` | `bool` (16) | `t` / `f` |
| `INT8`, `INT16` | `int2` (21) | decimal |
| `INT32` | `int4` (23) | decimal |
| `INT64` | `int8` (20) | decimal |
| `FLOAT32` / `FLOAT64` | `float4` (700) / `float8` (701) | shortest round-trip |
| `DECIMAL` | `numeric` (1700) | plain, never exponent |
| `STRING` | `text` (25) | as is |
| `DATE` | `date` (1082) | ISO |
| `TIMESTAMP` | `timestamptz` (1184) | `2026-09-19 09:00:00+00`, with up to nine fractional digits where the value has them |
| `BYTES`, `TIME` | — | **refused** with PRV-6200, before any `RowDescription` is sent |

`BYTES` and `TIME` are carried correctly over Flight; the gateway refuses them by name rather than
guessing an encoding, and refuses *before* describing the result, so a client gets a clean error
rather than a truncated result set it might treat as complete. Select the other columns.

Binary parameter or result formats are refused with PRV-6209; the gateway speaks text.

## What it refuses, and the SQLSTATE a client sees

| You send | Refused with | SQLSTATE |
|---|---|---|
| `CREATE`/`DROP`/`PAUSE`/`RESUME CONTINUOUS QUERY`, `SHOW CONTINUOUS QUERIES` | PRV-6211 | `25006` read_only_sql_transaction |
| `INSERT`, `UPDATE`, `DELETE` | PRV-2020, by the planner — the same answer Flight gives | `42000` |
| A column of type `BYTES` or `TIME` | PRV-6200 | `0A000` |
| `COPY`, `DECLARE`/`FETCH` cursors | Refused by name as unsupported (PRV-6201) | `0A000` |
| A `SET` outside the accepted list | PRV-6204 | `0A000` |
| A catalogue query shape the shim does not recognise | PRV-6205 | `0A000` |
| A view that does not exist | PRV-4023, the serving layer's own code | `42P01` undefined_table. It was PRV-2002 and the generic `42000` until finding L-3: the serving layer answered PRV-4023 only over an empty catalogue and let the planner's SQL-validation failure through otherwise. An unknown **column** of a view that does exist is still PRV-2002 and `42000`, deliberately — a confident "no such table" would send you looking in the wrong place |
| A read joining two views | PRV-4025 | `0A000` |
| A view you may not read | PRV-7002 | `42501` insufficient_privilege |
| A wrong password | — | `28P01` invalid_password |
| The node's read admission is full | PRV-4026 / PRV-4027 / PRV-4028 | `53000` |
| A read past its deadline | PRV-4029 | `57014` query_canceled |
| More than 1,000,000 result rows | PRV-4024 | `54000` |

The one `SET` family accepted is the one whose effect is provably the same as honouring it:
`extra_float_digits`, `application_name`, `client_min_messages`, and `client_encoding`/`DateStyle` for
the single value each already matches. pgjdbc sends `SET extra_float_digits = 3` before anything else,
which is why the list exists.

Registering a query goes through Flight instead:

<!-- sql: read-refused PRV-2020 -->
```sql
SELECT user_id, spend FROM hourly_spend ORDER BY spend DESC
```

```text
pravaha=> CREATE CONTINUOUS QUERY big KEYED BY (txn_id) AS SELECT txn_id, amount FROM txn WHERE amount > 1000;
ERROR:  PRV-6211  the PostgreSQL gateway is read-only: it does not register, drop, pause, resume or list continuous queries. Send the statement over Flight SQL instead -- an SDK's query(), `pravaha query --sql`, or the console's workbench -- where it runs as your principal.
```

Anything the gateway does not map to a more specific SQLSTATE arrives as `42000` — a name that does not
resolve, a shape the planner refuses, a type error. The message always begins with the engine's own
PRV code, which is the part to search for.

## Pitfalls

!!! warning "Pitfall: port 5432 is taken"
    The default port is PostgreSQL's own. On a host that already runs PostgreSQL the gateway fails to
    bind; set `pravaha.pgwire.port` to something else rather than fight over it.

!!! warning "Pitfall: `ORDER BY` and `LIMIT` from a BI tool"
    Tools add `ORDER BY` and `LIMIT` to preview a table. Both are refused (PRV-2020) because a read
    names a total order over a live view; configure the tool to preview without them, or narrow with
    `WHERE`.

!!! warning "Pitfall: a join between two views"
    A read names exactly one view; a tool that joins two is refused with PRV-4025. Do the join in a
    continuous query and read its view.

!!! tip "Timestamps keep their nanoseconds"
    A `TIMESTAMP` value with sub-microsecond detail prints more than PostgreSQL's six fractional
    digits. Most clients round it; the digits are there because the engine holds nanoseconds.

## Where next

- [Point reads](/help/topics/point-reads) — what every read here does underneath.
- [Authentication](/help/topics/authentication) — where the token the password carries comes from.
- [TLS everywhere](/help/topics/tls) — encrypting every other connection.
