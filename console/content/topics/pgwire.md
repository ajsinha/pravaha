---
title: The PostgreSQL gateway
slug: pgwire
category: reading
order: 40
icon: server
summary: "Reading maintained views from psql, DBeaver, Grafana, Power BI or any PostgreSQL driver: turning the gateway on, connecting, the types it sends, what it refuses (writes, PRV-6211, BYTES and TIME), and TLS on the same port."
badge: GATEWAY
audience: Developers
keywords: [psql, postgres, postgresql, pgwire, dbeaver, grafana, jdbc, pgjdbc, npgsql, psycopg, power bi, transaction, begin, autocommit, "25P02", 5432, sslmode, "25006", read-only, "\\d", "select 1", hikari, "connectionTestQuery", "search_path", copy, PRV-6201, PRV-6211, PRV-6200, PRV-6216, PRV-6217, PRV-6218, PRV-6219, PRV-6221, allow-plaintext, "53300", max-connections, idle-timeout, revoked]
guide: architecture
related: [views-and-keys, clients, power-bi, authentication, tls, consistency]
---

Almost nothing a person already has installed speaks Arrow Flight SQL; almost everything speaks
PostgreSQL. The PostgreSQL gateway is a second door onto the same rooms: it speaks the PostgreSQL v3
wire protocol so that `psql`, DBeaver, Grafana, [Power BI](/help/topics/power-bi), an ORM or a notebook can **read** a maintained view with
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
| Catalogue | A minimal read-only `pg_catalog` (`pg_class`, `pg_namespace`, `pg_attribute`, `version()`, `current_schema()`), Npgsql's type loading (`pg_type`) and the `information_schema` questions Npgsql's `GetSchema` and Power BI's navigator ask — all filtered by what you may read — so `\d`, a driver's `getTables()` and Power BI's navigator work |
| Tested clients | `psql`, pgjdbc 42.7 (simple and extended protocol, autocommit on or off), **Npgsql 4.0.17** — the driver inside Power BI — and psycopg 3 in its default (non-autocommit) mode, each driven by the module's own tests. DBeaver connects through pgjdbc |
| Transactions | `BEGIN`, `COMMIT`, `ROLLBACK`, savepoints and `SET TRANSACTION` are accepted as no-ops with PostgreSQL's tags and transaction status; reads in a block are `READ COMMITTED` — see [Transactions](#transactions) |
| Authentication | The **password** is the node's credential (a bearer token under `authentication: token`); with the engine's own accounts on, an **API key** or a **session token** — never the account's own password (PGWIREPASS-1). The user name is informational |
| Writes | None. `INSERT`/`UPDATE`/`DELETE` are refused by the planner; continuous-query statements with PRV-6211 (SQLSTATE `25006`) |
| TLS | `pravaha.pgwire.tls.certificate` and `pravaha.pgwire.tls.key` (PEM chain, PKCS#8 key): the gateway then answers `SSLRequest` on the same port. **Off until both are set** — see below |
| Limits | 100 connections, 32 of them still signing in, a 10 s handshake, 16 KiB before sign-in and 1 MB per message after it, no idle timeout — `pravaha.pgwire.limits.*`, see [Limits and revocation](#limits-and-revocation) |
| Revocation | The credential is verified again at **every statement**: a revoked key, an ended session or a disabled user ends the open connection with `FATAL 28000` (PRV-6218) |

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
handshake, so the token is always inside it. A client that does not ask for TLS — `sslmode=disable`,
`sslmode=prefer` against a client without TLS support, Npgsql 4's default — is refused
`FATAL 28000` with [PRV-6221](/help/codes/PRV-6221) before it is asked for anything: PostgreSQL's
`hostssl`. `pravaha.pgwire.tls.allow-plaintext: true` (off by default) accepts such clients as well,
for a migration window; their token then crosses the wire in the clear (PGTLSONLY-1).

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

**With the engine's own accounts on** ([authentication](/help/topics/authentication)), the password
is one of two things, and both are verified exactly as Flight verifies them: an **API key**
(`prv_k_…`, from `pravaha key create` or Admin · Keys) — the one to give a BI tool, since it lives for
its days rather than a sign-in's hours — or a **session token** (`prv_s_…`, from `pravaha login`),
which stops working when the session ends. The account's own password is **not** accepted here, nor is
a revoked or expired key; each fails the sign-in with `28P01`. A session held back to change its
password is refused too (PRV-7018): the gateway has no way to change it. `PgWireSignInTest` signs in
with a real PostgreSQL driver both ways.

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

## Limits and revocation

The gateway is meant to be exposed (with TLS), so what an unauthenticated peer can make it hold is
bounded, and so is what one credential can hold (PGPREAUTH-1). Every limit is a setting:

| Setting | Default | What happens past it |
|---|---|---|
| `pravaha.pgwire.limits.max-connections` | `100` | A new connection is answered `FATAL 53300` too_many_connections ([PRV-6216](/help/codes/PRV-6216)) at once, before its startup packet is read, and closed |
| `pravaha.pgwire.limits.max-unauthenticated` | `32` | The same, counting only connections still in their handshake — the cheap kind for a stranger to multiply |
| `pravaha.pgwire.limits.authentication-timeout` | `10s` | One deadline for the whole handshake, startup to `AuthenticationOk`; a peer trickling a byte at a time does not renew it. The socket is closed |
| `pravaha.pgwire.limits.max-connections-per-principal` | `0` (no share smaller than the whole) | A credential already holding that many is answered `FATAL 53300` (PRV-6216) after signing in |
| `pravaha.pgwire.limits.max-message-size` | `1MB` | A message declaring more is refused `FATAL 54000` ([PRV-6217](/help/codes/PRV-6217)) on its length, before anything is read or allocated. Before sign-in the cap is a fixed 16 KiB, enough for any token or key |
| `pravaha.pgwire.limits.idle-timeout` | `0s` (never) | A signed-in connection that sends nothing for this long is ended `FATAL 57P05` idle_session_timeout ([PRV-6219](/help/codes/PRV-6219)) |

A value out of range stops the node at startup with [PRV-6220](/help/codes/PRV-6220). Even an
accepted message is allocated as its bytes arrive, not on the size a client declared.

**Revocation is immediate here too.** The credential a connection signed in with is verified again,
through the same verifier, before every statement — a `Query`, or a `Parse`, `Bind`, `Describe` or
`Execute`. Revoke the key (`DELETE /api/v1/keys/{id}`), sign the session out, or disable the user, and
the connection's next statement is answered `FATAL 28000` ([PRV-6218](/help/codes/PRV-6218)) and the
connection closes — exactly when HTTP starts answering `401` and a Flight subscription ends. A role
taken away applies from the next statement. A connection that sends nothing holds no data; set
`idle-timeout` if idle connections themselves should go.

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

Every read on [Point reads](/help/topics/views-and-keys#point-reads) works here unchanged. At the prompt:

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

Results go out in **text** unless a client's `Bind` asks for **binary**, which Npgsql (so Power BI)
does for every query; the gateway then sends PostgreSQL's own binary format for each type above. One
difference follows from that format: a binary `timestamptz` is a count of **microseconds**, so a
value's sub-microsecond digits are truncated (toward the past) where the text form keeps all nine.
A binary **parameter** is decoded for the fixed-width types, `numeric` and text; any other binary
parameter is refused with PRV-6209. A parameter compared with a `DECIMAL` column takes a `numeric`, text
or integer value and is compared exactly; a `float4`/`float8` there is refused with PRV-2062 rather
than rounded (DECPARAM-1). A binary number is read as the type the client declared for it in `Parse` and
widened to the column it is compared with, as PostgreSQL does: pgjdbc's `setInt`/`setShort`,
psycopg's `%b` with a small `int` and Npgsql's `int` parameters send `int4`/`int2`, and against a
`BIGINT` they are read as such; a `float4` widens to `DOUBLE`. A wider integer than the column is
accepted only when its value is in the column's range (otherwise PRV-2062), and bytes that do not
match the declared width are PRV-6202 (`08P01`).

## What it refuses, and the SQLSTATE a client sees

| You send | Refused with | SQLSTATE |
|---|---|---|
| `CREATE`/`DROP`/`PAUSE`/`RESUME CONTINUOUS QUERY`, `SHOW CONTINUOUS QUERIES` | PRV-6211 | `25006` read_only_sql_transaction |
| `INSERT`, `UPDATE`, `DELETE` | PRV-2020, by the planner — the same answer Flight gives | `42000` |
| A column of type `BYTES` or `TIME` | PRV-6200 | `0A000` |
| `COPY`, `DECLARE`/`FETCH`/`MOVE`/`CLOSE` cursors, `LISTEN`/`NOTIFY`, `SELECT STREAM` | Refused by name as unsupported (PRV-6201) — subscribe over Flight instead of `SELECT STREAM` | `0A000` |
| `DISCARD ALL` | Accepted: forgets the session's named statements and portals, which is all the session state there is. Npgsql sends it whenever it reuses a pooled connection. Inside a transaction block it is refused with PRV-6215, as PostgreSQL refuses it | `25001` inside a block |
| Anything but `ROLLBACK`, `COMMIT` or `ROLLBACK TO SAVEPOINT` after an error inside a transaction block | PRV-6212 | `25P02` in_failed_sql_transaction |
| `SAVEPOINT`, `RELEASE`, `ROLLBACK TO` or `COMMIT AND CHAIN` outside a block | PRV-6213 | `25P01` no_active_sql_transaction |
| `RELEASE` or `ROLLBACK TO` a savepoint the block never set | PRV-6214 | `3B001` |
| `SET TRANSACTION SNAPSHOT` | PRV-6204: the gateway keeps no snapshots to import | `0A000` |
| A `SET` outside the accepted list | PRV-6204 | `0A000` |
| A catalogue query shape the shim does not recognise | PRV-6205 | `0A000` |
| A view that does not exist | PRV-4023, the serving layer's own code | `42P01` undefined_table. It was PRV-2002 and the generic `42000` until finding L-3: the serving layer answered PRV-4023 only over an empty catalogue and let the planner's SQL-validation failure through otherwise. An unknown **column** of a view that does exist is still PRV-2002 and `42000`, deliberately — a confident "no such table" would send you looking in the wrong place |
| A read joining two views | PRV-4025 | `0A000` |
| A view you may not read | PRV-7002 | `42501` insufficient_privilege |
| A wrong password | — | `28P01` invalid_password |
| A connection past `max-connections`, `max-unauthenticated` or `max-connections-per-principal` | PRV-6216 | `53300` too_many_connections |
| A message larger than `max-message-size` (or 16 KiB before sign-in) | PRV-6217 | `54000` |
| Any statement after the connection's credential was revoked, signed out or its user disabled | PRV-6218, and the connection closes | `28000` |
| Nothing sent for `idle-timeout` | PRV-6219, and the connection closes | `57P05` idle_session_timeout |
| The node's read admission is full | PRV-4026 / PRV-4027 / PRV-4028 | `53000` |
| A read past its deadline | PRV-4029 | `57014` query_canceled |
| More than 1,000,000 result rows | PRV-4024 | `54000` |

The one `SET` family accepted is the one whose effect is provably the same as honouring it:
`extra_float_digits`, `application_name`, `client_min_messages`, `client_encoding`/`DateStyle` for
the single value each already matches, and `search_path` for a path that keeps `public` on it (made of
`public`, `"$user"` and `pg_catalog`, or `DEFAULT`). pgjdbc sends `SET extra_float_digits = 3` before
anything else, which is why the list exists.

## Connection checks and probes

Connection pools, BI tools and health checks validate a connection with a query that reads nothing.
The gateway answers these itself, with PostgreSQL's column names and types (PGVALIDATE-1), so
HikariCP's `connectionTestQuery`, DBeaver's "Test connection", Grafana's data-source check and
SQLAlchemy's `pool_pre_ping` work unchanged:

| You send | Answer |
|---|---|
| `SELECT 1` (any integer, decimal or `'string'` literal, `TRUE`/`FALSE`, several items, with or without `AS alias`) | One row; a literal's column is named `?column?`, as PostgreSQL names it |
| A literal cast: `'a'::text`, `1::int8`, `CAST('7' AS bigint)` — to `text`, `varchar`, `int2`/`int4`/`int8`, `bool`, `numeric` | The cast value, in a column named after the type |
| `now()`, `current_timestamp`, `transaction_timestamp()`, `statement_timestamp()`, `clock_timestamp()`, `current_date` | The node's clock (UTC) |
| `current_user`, `session_user`, `user`, `current_role` | The principal your credential stands for |
| `version()`, `current_schema()`, `current_database()`, `current_catalog`, `pg_backend_pid()` | As announced at connect |
| `SHOW search_path` | `"$user", public` |
| An empty query, `;`, `-- ping`; pgjdbc's `isValid()` | `EmptyQueryResponse` |

A `SELECT` with no `FROM` that is anything else — arithmetic, another function — is not a probe; it
reaches the planner and is refused as before, never approximated.

Registering a query goes through Flight instead:

<!-- sql: read-refused PRV-2020 -->
```sql
SELECT user_id, spend FROM hourly_spend ORDER BY spend DESC
```

```text
pravaha=> CREATE CONTINUOUS QUERY big KEYED BY (txn_id) AS SELECT txn_id, amount FROM txn WHERE amount > 1000;
ERROR:  PRV-6211  the PostgreSQL gateway is read-only: it does not register, drop, pause, resume or list continuous queries, or show or change the catalogue's grants (ADR-059). Send the statement over Flight SQL instead -- an SDK's query(), `pravaha query --sql`, or the console's workbench -- where it runs as your principal.
```

Anything the gateway does not map to a more specific SQLSTATE arrives as `42000` — a name that does not
resolve, a shape the planner refuses, a type error. The message always begins with the engine's own
PRV code, which is the part to search for.

## Transactions

Most drivers wrap reads in a transaction unless told not to: psycopg outside `autocommit`, pgjdbc
with `setAutoCommit(false)`, Npgsql's `BeginTransaction`, and most ORMs. The gateway writes nothing,
so there is nothing for a transaction to make atomic; it accepts the statements as no-ops and keeps
the part a driver depends on — the command tag and the transaction status every `ReadyForQuery`
carries (`I` idle, `T` in a block, `E` failed), on the simple and the extended protocol alike.

| You send | Tag | Status after |
|---|---|---|
| `BEGIN`, `BEGIN WORK`/`TRANSACTION`, with any `ISOLATION LEVEL`, `READ ONLY`/`READ WRITE`, `[NOT] DEFERRABLE` | `BEGIN` | `T` (a second `BEGIN` warns `25001` and stays `T`) |
| `START TRANSACTION` with the same options | `START TRANSACTION` | `T` |
| `COMMIT`, `END` | `COMMIT` — or `ROLLBACK` if the block had failed | `I` (outside a block: a `25P01` warning, still `I`) |
| `ROLLBACK`, `ABORT` | `ROLLBACK` | `I` |
| `COMMIT AND CHAIN`, `ROLLBACK AND CHAIN` | as above | `T`: the next block opens at once |
| `SAVEPOINT x` | `SAVEPOINT` | `T` |
| `RELEASE [SAVEPOINT] x` | `RELEASE` | `T` |
| `ROLLBACK TO [SAVEPOINT] x` | `ROLLBACK` | `T`, even from a failed block |
| `SET TRANSACTION …`, `SET SESSION CHARACTERISTICS AS TRANSACTION …` | `SET` | unchanged |

After an error inside a block — a view that does not exist, a refused write — the block is failed:
every statement but `ROLLBACK`, `COMMIT` and `ROLLBACK TO SAVEPOINT` is refused with `25P02`
(PRV-6212) until one of them ends or rewinds it, exactly as PostgreSQL behaves. An error outside a
block leaves the session idle.

**Reads in a block are `READ COMMITTED`.** Each statement reads the views as they are when *that
statement* runs, not as of a snapshot taken at `BEGIN`, so two reads in one block can see two
different moments of a view that is being maintained between them. `REPEATABLE READ` and
`SERIALIZABLE` are accepted — refusing them would break drivers' isolation-level setters — and
answered with a `NOTICE` saying they run as `READ COMMITTED`. `SHOW transaction_isolation` (and `SHOW
TRANSACTION ISOLATION LEVEL`) always answers `read committed`, and `SHOW transaction_read_only` answers
`on`, because that is what the gateway does whatever the block asked for. `READ WRITE` is accepted;
writes are still refused.

The other settings a driver probes are answered from what the gateway announced at connect:
`SHOW standard_conforming_strings`, `server_version`, `client_encoding`, `DateStyle`, `TimeZone` and
the rest of that list.

## Pitfalls

!!! warning "Pitfall: port 5432 is taken"
    The default port is PostgreSQL's own. On a host that already runs PostgreSQL the gateway fails to
    bind; set `pravaha.pgwire.port` to something else rather than fight over it.

!!! warning "Pitfall: `ORDER BY` from a BI tool"
    Tools add `ORDER BY` to preview or rank a table. It is refused (PRV-2020) because a read names a
    total order over a live view; configure the tool to preview without it, or rank in the continuous
    query. A `LIMIT n` that ends the outermost `SELECT` — a preview's `LIMIT 1000`, Power BI's
    `LIMIT 1000001` — is taken off by the gateway and applied to the answer, which is exact for a
    `LIMIT` with no `ORDER BY`: it asks for *any* `n` rows. A `LIMIT` anywhere else, or with `OFFSET`,
    reaches the planner and is refused (PRV-2020).

!!! tip "`public.` names a view"
    Every view is a table in schema `public`, so `FROM public.hourly_spend` and
    `FROM "public"."hourly_spend"` read `hourly_spend`, as the catalogue says. No other schema exists.

!!! warning "Pitfall: a join between two views"
    A read names exactly one view; a tool that joins two is refused with PRV-4025. Do the join in a
    continuous query and read its view.

!!! tip "Timestamps keep their nanoseconds"
    A `TIMESTAMP` value with sub-microsecond detail prints more than PostgreSQL's six fractional
    digits. Most clients round it; the digits are there because the engine holds nanoseconds.

## Where next

- [Point reads](/help/topics/views-and-keys#point-reads) — what every read here does underneath.
- [Authentication](/help/topics/authentication) — where the token the password carries comes from.
- [TLS everywhere](/help/topics/tls) — encrypting every other connection.
