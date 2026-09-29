---
title: The jdbc source
slug: source-jdbc
category: sources
order: 40
icon: table
summary: "Polls any relational table — or any SELECT — on a monotonic column, pushing your WHERE, the columns you read and a running COUNT/SUM into the database. What polling can and cannot see, said plainly."
badge: SOURCE
audience: Operators
keywords: [jdbc, PRV-2042, repeats rows, watermark.moves.on.update, watermark.unit, event time, postgres, postgresql, mysql, oracle, sql server, h2, polling, watermark.column, key.column, page.clause, fetch.size, keyset, pushdown, projection, partial aggregate, pushdown.partial.aggregate, collation.binary]
guide: continuous-queries#21-every-source-type-configured
related: [sources-overview, source-postgres-cdc, lookups, connector-security, sink-jdbc, delivery-guarantees]
listed_on: sources-overview
---

The `jdbc` plugin turns a database table into a stream by **polling** it: each poll asks for the rows
beyond the highest value of a monotonic column it has already seen, in order, a page at a time. One
connector serves PostgreSQL, MySQL, SQL Server, Oracle, H2 and anything else with a JDBC driver,
because it uses nothing but standard JDBC — and that breadth is the point: most databases have no
change feed that is available, affordable or permitted, and the choice in practice is between polling
and not ingesting at all.

Polling has limits no connector can remove, and this page is as much about those as about the options.
A poll sees a row's value *at poll time*: it cannot see a delete, and a row changed twice between
polls is seen once.

## At a glance

| | |
|---|---|
| Plugin name | `jdbc` |
| Module | `plugins/pravaha-plugin-jdbc` (also holds `jdbc-lookup` and `jdbc-sink`) — in the server jar |
| Driver | supplied by the deployment, on the same classpath; the plugin ships none |
| Kind | stream source |
| Delivery guarantee | `AT_LEAST_ONCE` |
| Replayable offsets | **only with `key.column`** — see below |
| Emits deletes / before-image | no / no |
| Repeats rows | **yes**, unless `key.column` is set and `watermark.moves.on.update: false` — an update that moves the watermark brings the row back at `+1`. An aggregate, a join or an append-only sink over a stream that repeats is refused (PRV-2042) |
| Pushdown | `FILTER` (a bound `WHERE`), `PROJECT` (the `SELECT` list) and, **with `key.column` only**, `PARTIAL_AGGREGATE` (a continuous `COUNT`/`SUM` taken by the database) |
| Shared between queries | no — each query gets its own reader and its own statement |
| Schema comes from | **the database**, read from the result set's metadata. There is no `schema` option |
| Partitions | one reader |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `url` | yes | — | The JDBC URL. TLS goes here, in the driver's own spelling — see [connector security](/help/topics/connector-security) |
| `table` | one of `table`/`query` | — | Poll a whole table |
| `query` | one of `table`/`query` | — | Poll an arbitrary `SELECT`, wrapped as a derived table — where a join or a cast belongs when it is cheaper in the database |
| `watermark.column` | yes | — | The column each poll advances on. Must be in the result, or configuration fails with PRV-5074 naming the columns that are |
| `key.column` | no (strongly advised) | none | A unique integer column that breaks ties on the watermark. With it: keyset pagination and replayable offsets. Without it: a fallback that is exact only when the database returns tied rows in a stable order |
| `user` / `password` | no | empty | Passed to the driver as properties |
| `fetch.size` | no | `500` | Rows per page — the value bound into `page.clause` |
| `page.clause` | no | `LIMIT ?` | The paging clause, the one dialect-specific option: `LIMIT ?` for PostgreSQL, MySQL and H2; `FETCH FIRST ? ROWS ONLY` for Oracle and Db2; `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY` for SQL Server |
| `stream` | no | the table name, or the binding's name with `query` | The stream name the plugin reports |
| `pushdown.partial.aggregate` | no | `true` | Whether a continuous `COUNT`/`SUM` may be taken by the database. Has effect only with `key.column`; `false` always reads rows |
| `collation.binary` | no | `false` | Set it only when the database compares and groups text **byte for byte, case-sensitively**, as the engine does. It is what lets a partial aggregate filter or group on a text column |
| `watermark.moves.on.update` | no | `true` | Whether an update can move the watermark column, so that a poll reads the row again. Set `false` only when the column is written once, on insert — a sequence, a `created_at` — and never by an update. With `key.column` the source then declares that it never repeats a row, and an aggregate or a join over it can be registered; otherwise they are refused with PRV-2042. Anything but `true` or `false` is PRV-5074 |
| `watermark.unit` | no | `none` | What the watermark column's numbers mean, so a row's event time can be built from them: `none`, `nanos`, `micros`, `millis` or `seconds`. **`none` says the column is a cursor and not a time**, and rows then carry no event time at all — so no window over this stream can close. This defaulted to nothing and the column was stamped raw, so an `updated_at BIGINT` of epoch milliseconds gave an event time out by a factor of a million: a watermark stuck in 1970, windows that never closed, and nothing said (T-5). A value that overflows its declared unit is refused rather than wrapped — reading epoch milliseconds as `seconds` does exactly that |
| `share.reader` | no | `true` | Read by the binding layer; this source is not shared (it is ordered within its partition), so it has no effect |
| `tls.*` | — | — | **Refused** with PRV-5074, except `tls.enabled: false`. A driver takes TLS in the URL, and accepting `tls.*` would leave a plaintext connection behind a configuration that looks encrypted |

Giving both `table` and `query`, or neither, is refused at configuration with a message saying which
does what.

## The statements it runs

With `table: orders`, `watermark.column: event_ns`, `key.column: order_id` and the defaults, the
first poll and every later poll are:

```text
SELECT * FROM orders ORDER BY event_ns, order_id LIMIT ?
SELECT * FROM orders WHERE (event_ns > ? OR (event_ns = ? AND order_id > ?)) ORDER BY event_ns, order_id LIMIT ?
```

The second is **keyset pagination**: the pair (watermark, key) is a total order, so a page boundary
that falls in the middle of a thousand rows sharing one timestamp resumes exactly. Without
`key.column` the resume predicate is `event_ns >= ?` and the reader skips the boundary rows it has
already emitted — correct only if the database returns tied rows in the same order every time, which
SQL does not promise; so the plugin then declares its offsets **not** replayable.

With `query`, the statement becomes `SELECT * FROM (<your query>) AS src …`, so `watermark.column` and
`key.column` must name columns your `SELECT` projects.

Before the first poll the plugin reads the schema with `SELECT * FROM <source> LIMIT 0` (your
`page.clause` with the `?` replaced by 0) — metadata without moving a row.

## Types

| JDBC type | Pravaha type |
|---|---|
| `BOOLEAN`, `BIT` | `BOOLEAN` |
| `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT` | `INT8`, `INT16`, `INT32`, `INT64` |
| `REAL` / `FLOAT`, `DOUBLE` | `FLOAT32` / `FLOAT64` |
| `NUMERIC`, `DECIMAL` | `DECIMAL(38,9)` — never a float; `+ - *`, `SUM`, `MIN` and `MAX` over it are exact, and `/` and `AVG` are refused `PRV-2021` rather than rounded |
| `CHAR`, `VARCHAR`, `LONGVARCHAR` and the `N` forms | `STRING` |
| `BINARY`, `VARBINARY`, `LONGVARBINARY` | `BYTES` |
| `DATE` | `DATE` |
| `TIMESTAMP`, `TIMESTAMP WITH TIME ZONE` | `TIMESTAMP` (nanoseconds since the epoch) |
| anything else | refused with PRV-5072, naming the column — cast it in `query` |

## A complete binding

The table has an integer watermark column holding epoch nanoseconds, maintained by the database:

```yaml
pravaha:
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP,change_ns:INT64"
      event-time: event_time
      out-of-orderness: 30s
  sources:
    orders:
      plugin: jdbc
      options:
        url: "jdbc:postgresql://db-1.internal:5432/sales?ssl=true&sslmode=verify-full&sslrootcert=/opt/pravaha/conf/tls/pg-ca.pem"
        user: pravaha
        password: "${PRAVAHA_DB_PASSWORD}"
        query: >
          SELECT o.order_id, o.customer_id, o.region,
                 CAST(o.amount * 100 AS BIGINT) AS amount,
                 o.status, o.created_at AS event_time, o.change_ns
          FROM orders o
        watermark.column: change_ns
        key.column: order_id
        fetch.size: "1000"
        page.clause: "LIMIT ?"
```

Three things this binding does on purpose:

- **The cast to integer cents happens in the database.** `NUMERIC` would arrive as a decimal, and
  decimal division and `AVG` are refused in the engine, so the conversion belongs where the decimal lives.
- **The watermark is a database-maintained `BIGINT`** (`change_ns`, set by a trigger or a sequence),
  not an application clock. See the pitfalls.
- **The declared schema is the polled result, column for column.** The plugin decodes with the
  schema the database reports and queries are planned against `pravaha.streams.orders.schema`;
  nothing compares the two for you, so write the second from the first — same columns, same order,
  `change_ns` included.

## A query over it

Large orders, and both the filter and the column list reach the database:

```sql
CREATE CONTINUOUS QUERY big_orders
    KEYED BY (order_id)
AS
SELECT order_id, customer_id, region, amount
FROM orders
WHERE amount >= 100000 AND region = 'EU';
```

Both conjuncts compare a column with a literal directly above the scan, so the poll carries them as
bound parameters; and the query reads four of the seven columns, so the poll selects those — plus
the watermark and key columns, which the reader orders and resumes by whether or not the query reads
them:

```text
SELECT order_id, customer_id, region, amount, change_ns FROM (SELECT o.order_id, o.customer_id, o.region, CAST(o.amount * 100 AS BIGINT) AS amount, o.status, o.created_at AS event_time, o.change_ns FROM orders o) AS src WHERE (change_ns > ? OR (change_ns = ? AND order_id > ?)) AND (amount >= ? AND region = ?) ORDER BY change_ns, order_id LIMIT ?
```

<!-- sql: read -->
```sql
SELECT order_id, customer_id, amount FROM big_orders WHERE customer_id = ?
```

```bash
pravaha query --sql "SELECT order_id, customer_id, amount FROM big_orders WHERE customer_id = ?" --params c42
```

```text
order_id	customer_id	amount
90114	c42	250000
1 row
```

(The row is illustrative; the shape is the CLI's: a tab-separated header, the rows, a count.)

## Pushdown

Three kinds, and the engine keeps its own filter whatever the database does with them — pushdown
changes how many rows and bytes cross the network, never the answer.

### `FILTER` — your `WHERE`, bound

What is pushed:

- conjuncts (`AND`) of **one column compared with a literal**: `=`, `<>`, `<`, `<=`, `>`, `>=`,
  `IS NULL`, `IS NOT NULL`;
- only filters sitting **directly above this stream's scan** — a `WHERE` over a join or an aggregate
  refers to columns that are not the table's, and is not pushed;
- only columns the database's own metadata named. A filter on anything else is dropped (costing
  bandwidth, never rows).

What is never pushed from a query: an `OR` written in its `WHERE`, a `NOT`, a `LIKE`, a comparison
between two expressions — those stay with the engine. Values are **bound, never interpolated** — no
quoting, no injection, and the statement is cacheable.

### `PROJECT` — the columns you read

The poll's `SELECT` list is the stream's columns the query actually uses, in the table's order,
**plus the watermark and key columns** the reader orders and resumes by. A column nobody reads is
never sent. The engine works this out only through projections and filters directly over the scan;
a query whose path to the scan passes a computed column, an aggregate, a join or a window asks for
every column, and the poll is `SELECT *` — a missed saving, never a wrong answer.

### `PARTIAL_AGGREGATE` — a running `COUNT`/`SUM`, taken by the database

With `key.column` set, a continuous **global** aggregate over this stream need not read rows at all.
It can only be registered over a poll that never reads a row twice: the binding must also say
`watermark.moves.on.update: false`, which is true only if `change_ns` is set when a row is inserted
and never by an update. Where an update moves it — a trigger on `UPDATE`, an `updated_at` — the row
comes back as a second `+1`, a `COUNT` would count it twice, and the registration is refused with
PRV-2042:

```sql
CREATE CONTINUOUS QUERY big_order_totals
    KEYED BY (big_orders)
AS
SELECT COUNT(*) AS big_orders, SUM(amount) AS big_value
FROM orders
WHERE amount >= 100000;
```

Each poll asks the database for one **partial** per keyset page instead of the page's rows — in
outline, two statements:

```text
SELECT change_ns, order_id FROM (...) AS src WHERE <after the offset> AND (amount >= ?) ORDER BY change_ns, order_id LIMIT ?      -- where this page ends
SELECT COUNT(*), SUM(amount), ... FROM (...) AS src WHERE <after the offset> AND <up to that row> AND (amount >= ?)              -- the page's partial
```

and the engine adds the partials into its running total. The answer is the one the rows give: a
page's partial is the sum of exactly the rows the row poll would have sent, with the same offsets,
and a restore resumes from the same place. (An update the poll sees again would be counted again in
both, because a polled table is not a changelog either way — which is why the aggregate is admitted
only with `watermark.moves.on.update: false`.)

A partial leaves no rows for the engine's own filter to run on, so it is asked for **only when it is
certainly exact**, and read as rows otherwise. It is declined:

| When | Why |
|---|---|
| there is no `key.column`, or `pushdown.partial.aggregate: false` | a page end is a position only a total order can name |
| any predicate cannot be carried into SQL — an `OR`, a `LIKE`, a comparison of two expressions | the partial would silently not apply it |
| a text column is filtered on, unless `collation.binary: true` | a case-insensitive collation matches `'done'` to `'DONE'`, where the engine does not |
| a `SUM` over anything but a `BIGINT` | the engine sums 64-bit integers |
| an aggregate other than `COUNT` and `SUM` — `MIN`, `MAX`, `AVG`, `COUNT(DISTINCT)` | only these two combine by adding |
| a windowed aggregate, or anything between the aggregate and the scan but filters and projections | the one shape proven equivalent is an aggregate over filters over the scan |

An unwindowed `GROUP BY` over a stream cannot be registered at all (PRV-2050), so a registered
query's partial is always the global one. (Were a group key ever offered, it would be declined unless
integral — or text under `collation.binary: true` — because a database may group values the engine
keeps apart.)

## Delivery guarantee

`AT_LEAST_ONCE`. With `key.column` the resume is exact — the offset is (watermark, key) — but a poll
still cannot see a delete or the intermediate values of a row updated twice between polls, so the
stream is not a faithful changelog however careful the offsets are. Without `key.column` even the
resume is only as reliable as the database's ordering of tied rows. Either way a row can arrive twice
with nothing retracting the first copy, and the source says so (it **repeats rows**) unless
`key.column` is set and `watermark.moves.on.update: false`: a keyed view of the rows is right, and
what would count the copies — an aggregate, a join, an append-only sink — is refused with PRV-2042.

## Pitfalls

!!! danger "Pitfall: the watermark column must never go backwards"
    Each poll asks for rows beyond the highest value already seen. An `updated_at` written from an
    application clock moves backwards on clock skew, and a backdated correction lands below the
    watermark — those rows are not late, they are **never seen**. A database-assigned value (a
    sequence, `now()` in a trigger) is far safer than anything an application supplies.

!!! danger "Pitfall: commits become visible out of order"
    A row written inside a long transaction takes its value when the statement runs and becomes
    visible when the transaction commits — possibly after the poller has moved past it. No care in
    the connector fixes this. Poll only up to a little behind now (a `query` with
    `WHERE change_ns < <now minus a margin>` computed in the database), or advance on a commit-ordered
    column.

!!! warning "Pitfall: the watermark column is also the event time"
    The reader reads `watermark.column` as a 64-bit integer and stamps it on every row as the row's
    event time **in nanoseconds**, unconverted. The watermark that closes windows is derived from
    those stamps. So for a windowed query the watermark column must hold epoch **nanoseconds**: an
    epoch-milliseconds column puts every row in 1970 and no window closes in the present (QA finding
    T-5, open). A `TIMESTAMP` column as the watermark column is not how the plugin is tested — every
    test uses a `BIGINT` — so give it one.

!!! warning "Pitfall: deletes do not reduce totals"
    The reader writes weight `+1` on every row; there is no equivalent of the filesystem source's
    `op.column`. A soft-delete flag arrives as an ordinary column a query can filter on, but an
    already-counted row is not withdrawn. Where deletes must reduce a total, a change feed is the
    right shape: on PostgreSQL, [postgres-cdc](/help/topics/source-postgres-cdc).

!!! note "Index the watermark column"
    Every poll orders by it. Without an index every poll is a full scan of the table — with
    `key.column`, index `(watermark, key)`.

!!! note "Two readers, two statements"
    This source is ordered within its partition, so it is never shared: two registered queries over one
    stream each get their own reader and their own statement, with their own pushed filters and
    columns, and a query nobody changed never inherits another's `WHERE`.

## Where next

- [The postgres-cdc source](/help/topics/source-postgres-cdc) — PostgreSQL's changes, deletes included,
  instead of polling
- [JDBC lookups](/help/topics/lookups) — asking a table as of each row's time, instead of streaming it
- [The jdbc sink](/help/topics/sink-jdbc) — maintaining a view's answer in a table, exactly once
- [Connector security](/help/topics/connector-security) — TLS in the URL, and credentials from the environment
- [Sources overview](/help/topics/sources-overview) — polling against scanning against feeds
