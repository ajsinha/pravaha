---
title: Power BI
slug: power-bi
category: reading
order: 45
icon: bar-chart-line
summary: "Reading maintained views from Power BI Desktop and the Power BI service through the PostgreSQL gateway: connecting with a token, Import or DirectQuery, the SQL DirectQuery sends and what is refused, and real-time dashboards in Microsoft Fabric."
badge: GATEWAY
audience: Analysts
keywords: [power bi, powerbi, directquery, direct query, import, npgsql, fabric, eventstream, event hubs, on-premises data gateway, dashboard, navigator, "limit 1000001", PostgreSQL.Database, realtime, real-time]
guide: architecture
related: [pgwire, sink-kafka, tls, authentication, views-and-keys, zset-weights]
---

Power BI's built-in **PostgreSQL database** connector reads Pravaha views through the
[PostgreSQL gateway](/help/topics/pgwire). Nothing is installed on the Pravaha side beyond turning the
gateway on, and nothing on the Power BI side at all: Power BI Desktop has shipped the driver the
connector uses, **Npgsql 4.0.17**, since its October 2024 release, and the on-premises data gateway
since June 2025.

A maintained view is an answer that is always current. **DirectQuery** asks the gateway every time a
visual is drawn, so what the report shows is the view as it is now; **Import** copies the view into
the report at each refresh.

## At a glance

| | |
|---|---|
| Connector | Power BI's own **PostgreSQL database** (`PostgreSQL.Database` in M) |
| Server | `host:port` of the gateway (`pravaha.pgwire.host`/`port`) |
| Database | `pravaha` |
| User name | informational: yours, for your own records |
| Password | **a Pravaha token**: the credential decides who you are, as on every gateway connection. With the engine's own accounts on, an **API key** (`prv_k_…`) — or a session token, which ends with the sign-in — never the account's password |
| Modes | **Import** and **DirectQuery**, both tested with the real Npgsql 4.0.17 |
| Encryption | Power BI's "encrypted connection" needs the gateway's TLS pair configured ([below](#encryption)) |
| What you see | Every view your token may read, as a table in schema `public`, and nothing else |
| Writes | none; the gateway is read-only |

## Before you start

1. **Turn the gateway on, with TLS**, as [the gateway page](/help/topics/pgwire#turning-it-on) shows.
   The password Power BI sends *is* your token, so do not send it across a network in the clear.
2. **Have a token** for the principal the report should read as. The report sees exactly what that
   principal may read. For a published report, use a service principal's long-lived token rather than
   your own: when a token expires, every scheduled refresh and every DirectQuery visual fails at once.
   With the engine's own accounts on, that is an **API key** issued for a service account (`pravaha
   key create --for <service-account>`); a session token works too but ends with its sign-in, and the
   account's own password is refused (PGWIREPASS-1).

## Connecting from Power BI Desktop

1. **Home → Get data → More… → Database → PostgreSQL database → Connect.**
2. **Server:** the gateway's host and port, for example `node-1.internal:5432`. No `http://`, no
   trailing slash, no database name in this box.
3. **Database:** `pravaha`.
4. **Data Connectivity mode:** **DirectQuery** for an always-current view, or **Import** (see
   [below](#import-or-directquery)).
5. **OK.** On the credentials screen choose **Database**, then enter any **User name** and your
   **token** as the **Password**. Choose the level (the server address) the credential applies to.
6. The **Navigator** lists each view you may read under `public`. Tick the views, then **Load**,
   or **Transform Data** to shape them first.

If Power BI says the connection is not encrypted and offers to connect without encryption, the gateway
has no certificate configured. Refuse on anything but a laptop reading a local node, and configure
TLS instead.

A wrong or expired token fails the sign-in with SQLSTATE `28P01`, the one every PostgreSQL client
treats as a wrong password (the message is PRV-7001, *the credential presented was not accepted*);
Power BI asks for the credential again.

## Import or DirectQuery

| | **Import** | **DirectQuery** |
|---|---|---|
| What the report holds | a copy of each view, as of the last refresh | nothing; each visual queries the gateway when it is drawn |
| How current | as of the last refresh (scheduled in the service) | as of the moment the visual was drawn, or the last automatic page refresh |
| What runs on Pravaha | one `SELECT` of each view per refresh | one statement per visual, per interaction |
| DAX and Power Query | everything | whatever Power BI can turn into SQL the gateway accepts ([below](#the-sql-directquery-sends)) |
| Size | the view must fit in the report | 1,000,000 rows per statement, which is Power BI's own limit too |

**DirectQuery suits a maintained answer.** A Pravaha view is already the result — a total per region,
the open orders, a top-N — kept correct as events arrive, retractions included. DirectQuery reads that
result as it stands whenever the page is drawn, instead of copying it into a model that is stale by
the next event. Turn on **automatic page refresh** (the page's **Format → Page refresh** in Desktop) to
redraw on an interval; in the Power BI service the shortest interval allowed is set by your capacity
administrator.

**Import suits a view you want to slice every way DAX can**, or one read by a report whose visuals
use functions the gateway refuses in DirectQuery. It is a snapshot: a scheduled refresh reads the
whole view each time, and nothing between refreshes shows.

### Visuals that suit a live answer

Cards and KPIs over a view that is already one number; tables of a keyed view; bar and column charts
grouped by a column of the view; slicers over a column with a handful of values; a line chart of a
windowed view by `window_end`. Each one sends a filter, a `GROUP BY` or a `COUNT`, which the gateway
answers from the view's own rows.

Visuals that need the gateway to sort or rank — a **Top N** filter, a table sorted by a measure — send
`ORDER BY`, which reads refuse. Let the continuous query rank (`ROW_NUMBER() … <= 10` is maintained
incrementally; see [the SQL reference](/help/topics/sql-reference)) and show its view.

## The SQL DirectQuery sends

Power BI writes one statement per visual: the view under an alias `"_"`, qualified with the schema
`"public"`, usually wrapped in derived tables, and always ending in `LIMIT 1000001` — its way of
noticing a result over a million rows. A bar chart of revenue by region:

```text
select "rows"."region" as "region",
    sum("rows"."revenue") as "a0"
from
(
    select "_"."region",
        "_"."revenue"
    from "public"."region_revenue" "_"
) "rows"
group by "region"
limit 1000001
```

The gateway reads `"public"."region_revenue"` as `region_revenue` (every view is in `public`, as the
catalogue says), takes the trailing `LIMIT` off, runs the statement as a normal read — the same
planner, authorization and audit as any other — and returns at most the first `n` rows. That last step
is exact because a `LIMIT` with no `ORDER BY` asks for *any* `n` rows. A `LIMIT` anywhere else — inside
a derived table, or with `OFFSET` — still reaches the planner, which refuses it.

The same read, written as it runs:

<!-- sql: read -->
```sql
SELECT region, SUM(revenue) AS revenue
FROM region_revenue
GROUP BY region
```

What reads answer, and what they refuse, for the shapes Power BI generates (checked against the
gateway; Power BI itself generates the SQL, so treat this as the set of building blocks, not a list of
every statement it can write):

| Power BI sends | Gateway |
|---|---|
| `SELECT` of columns, `WHERE` with `=`, `<>`, `IN`, `BETWEEN`, `LIKE`, `IS NULL`, `AND`/`OR`, `LOWER`/`UPPER`, `COALESCE`, `\|\|` | answered |
| `GROUP BY` with `COUNT(*)`, `COUNT(DISTINCT …)`, `SUM`, `MIN`, `MAX`, `AVG` over integer, date and timestamp columns, and `SUM`, `MIN`, `MAX` over `DECIMAL` columns; `SELECT DISTINCT` (slicers); `HAVING` | answered |
| Derived tables (`from ( … ) "rows"`), `where not "_"."a0" is null` | answered |
| A trailing `LIMIT n` | answered: the gateway applies it |
| `ORDER BY` (Top N, sorted tables) | refused, PRV-2020. Rank in the continuous query |
| `LIMIT` inside a derived table, `OFFSET`, `FETCH FIRST` | refused, PRV-2020 |
| A join of two views — a relationship between two DirectQuery tables | refused, PRV-4025. Join in the continuous query and read its view |
| `SUM`, `AVG`, `MIN`, `MAX` of a `FLOAT`/`DOUBLE` column | refused, PRV-2020 ([aggregates are 64-bit integers](/help/topics/sql-reference); a documented refusal, not the DECIMAL defect). Keep the column integer (cents rather than dollars) or `DECIMAL`, whose `SUM`, `MIN` and `MAX` are exact |
| `SUM`, `MIN`, `MAX` of a `DECIMAL` (`numeric`) column | answered exactly, at the column's scale; a sum is a `numeric(38, s)` |
| `AVG` of a `DECIMAL` column | refused, PRV-2021: an average of decimals would be rounded at the column's scale. Use a `SUM` measure divided by a `COUNT` measure in DAX, where the rounding is Power BI's |
| `EXTRACT`, `date_trunc` — Power BI's date hierarchy | refused, PRV-2021 / PRV-2002. Turn off **Auto date/time** (File → Options → Current file → Data Load) and give the view the date parts it needs |
| A `CAST` that narrows (`bigint` to `numeric(19,4)`, `double` to `numeric`) | refused, PRV-2021 |
| A column of type `BYTES` or `TIME` | refused, PRV-6200; leave the column out of the model |
| More than 1,000,000 rows | refused, PRV-4024. Power BI stops at a million too |

**`AVG` of an integer column is a `numeric`**, as PostgreSQL answers it: the exact average, rounded
half away from zero at the sixteenth decimal place, typed `numeric` (AVGINT-1). The gateway asks for
it; Flight SQL, the HTTP API and the SDKs keep the engine's integer average (it keeps its argument's
type and truncates), so the same `SELECT AVG(revenue)` answers `933046.6666666666666667` here and
`933046` there. One answer still differs from what a PostgreSQL server would return: a timestamp
read the way Npgsql reads it (binary) carries **microseconds** — sub-microsecond digits are
truncated, which PostgreSQL itself never has.

## What Power BI asks when it connects

Power BI does not list tables by magic. Opening a connection and the Navigator send these, and the
gateway answers each from the view catalogue, filtered by what your token may read — a view you may not
read is absent, not marked:

| Asked | Answered with |
|---|---|
| Npgsql's type loading: `pg_type` (with `pg_proc`, `pg_range`), composite fields, enum labels | the ten types the gateway sends (`bool`, `int2`, `int4`, `int8`, `float4`, `float8`, `numeric`, `text`, `date`, `timestamptz`); no composites, no enums |
| `INFORMATION_SCHEMA.character_sets` | `UTF8` |
| `INFORMATION_SCHEMA.tables` | one `BASE TABLE` in `public` per readable view |
| `INFORMATION_SCHEMA.columns` for a view | its columns, positions, nullability and PostgreSQL type names |
| Foreign keys (two queries) and primary/unique keys | none |
| `DISCARD ALL` (a pooled connection reused) | accepted |

**No keys are reported.** A view's key is real — the engine enforces it — but reported as a primary key
it makes Power BI order its preview by the key, which reads refuse. The cost is that Power BI does not
propose relationships between views on its own; relationships would need joins, which reads refuse
anyway.

## Encryption

Power BI's **Encrypt connection** option (on by default in the data-source settings; **Use Encrypted
Connection** in the service) asks the gateway for TLS on the same port, as `sslmode=require` does. With
`pravaha.pgwire.tls.certificate` and `pravaha.pgwire.tls.key` set, the gateway accepts and the token
travels inside TLS. The driver then checks the certificate against Windows' trust store and the server
name you typed, so:

- the certificate must name the host exactly as it is entered in **Server**, and
- a certificate from a private CA needs that CA in **Trusted Root Certification Authorities** on the
  machine running Power BI Desktop or the on-premises data gateway.

Without the pair the gateway declines TLS, and Power BI offers to connect unencrypted. Refusing that
offer is the right answer across a network.

## Publishing: the on-premises data gateway

A published report reaches a Pravaha node inside your network through Microsoft's **on-premises data
gateway** (which ships Npgsql since June 2025):

1. Install the data gateway on a machine that can reach the Pravaha gateway's port, and register it.
2. In the Power BI service, **Settings → Manage connections and gateways → New → On-premises**: data
   source type **PostgreSQL**, server `host:port`, database `pravaha`, authentication **Basic**, user
   name anything, password the token, and **Use Encrypted Connection** ticked.
3. Map the published semantic model to that connection. An Import model refreshes on its schedule; a
   DirectQuery model sends each visual's statement through the data gateway as it is drawn.

The server and database must match what the report was built against, character for character, or the
service cannot map the model to the connection.

## Limits

- **Read-only.** `INSERT`, `UPDATE` and `DELETE` are refused with PRV-2020, and continuous-query
  statements with PRV-6211. Register queries over Flight (the console, the SDKs, `pravaha query`).
- **One view per statement.** Relationships between two DirectQuery tables become joins, refused with
  PRV-4025. Model each visual on one view, or build the joined view as a continuous query.
- **No ordering.** Top N and sorted visuals are refused (PRV-2020); rank in the continuous query.
- **Native queries** (**Advanced options → SQL statement**, or `Value.NativeQuery`) run as ordinary reads:
  anything a read accepts works. Power BI may fold further steps around it as a derived table
  (`select … from (…) "_"`), which reads answer.
- **A million rows per statement** (PRV-4024), the same ceiling Power BI applies to DirectQuery.
- The driver is Npgsql **4.0.17**, the only version Power BI runs; newer Npgsql versions send different
  type-loading queries, which the gateway does not recognise (PRV-6205).

## Real-time dashboards in Microsoft Fabric

!!! warning "Not verified against Azure"
    This section describes a configuration built from `kafka-sink`'s documented options and
    Microsoft's published Kafka-endpoint settings. No part of it has been run against Azure Event Hubs
    or Fabric Eventstream from this project; treat it as a starting point and check each step.

DirectQuery redraws when asked. For a dashboard that moves as the answer moves, push the view's
changes out instead: Pravaha's [`kafka-sink`](/help/topics/sink-kafka) writes each commit of a view to a
Kafka topic, and both **Azure Event Hubs** and a **Fabric Eventstream** custom endpoint accept Kafka
producers — over TLS on port 9093, with SASL `PLAIN`, the user name `$ConnectionString` and the
connection string as the password. `kafka-sink` supports exactly that combination (SASL `PLAIN` is
accepted only with TLS). From there, Eventstream routes the records to an Eventhouse (KQL database)
that a **Real-Time Dashboard** reads.

```yaml
pravaha:
  sinks:
    revenue_to_fabric:
      plugin: kafka-sink
      options:
        bootstrap.servers: "<namespace>.servicebus.windows.net:9093"
        topic: region-revenue            # the event hub, or the Eventstream endpoint's topic name
        schema: "region:STRING,window_start:TIMESTAMP,window_end:TIMESTAMP,revenue:INT64,orders:INT64"
        mode: changelog                  # every change with its weight
        transactional: "false"           # Event Hubs' Standard tier has no Kafka transactions
        user: "$ConnectionString"
        password: "${EVENTHUB_CONNECTION_STRING}"
        sasl.mechanism: PLAIN
        tls.enabled: "true"
```

Register the view with `WRITING TO revenue_to_fabric` and the `SELECT` list matching `schema`.

**Retractions must become updates** on the far side, or a dashboard totals every version of a row:

- **`mode: changelog`** writes `{"op","weight","row"}` for each change, a correction as a `-1` for the
  old row and a `+1` for the new. In KQL, weight every measure — `summarize revenue = sum(weight *
  toint(row.revenue)) by region = tostring(row.region)` — and the total is right after any number of
  corrections ([Z-set weights](/help/topics/zset-weights)). This is the safer choice here: every record
  has a body.
- **`mode: upsert`** writes one record per key, the latest row, and a **tombstone** (a record with no
  value) when a key is withdrawn. In KQL, keep the latest record per key (`arg_max(ingestion_time(),
  *) by key`). Whether Eventstream passes an empty-bodied tombstone through is not something this
  project has checked; if it drops them, a withdrawn key keeps its last value. Upsert mode also expects a
  compacted topic, which Event Hubs offers only on its Premium and Dedicated tiers.

`transactional: "false"` gives at-least-once delivery in changelog mode (a node restart can repeat a
change) and effectively-once in upsert mode; see
[the Kafka sink](/help/topics/sink-kafka#without-transactions).

## How this was tested

The module's `NpgsqlClientTest` drives the gateway with the real **Npgsql 4.0.17** from .NET: the
connection open with type loading on, `GetSchema("Tables")` and `GetSchema("Columns")`, Power BI's
navigator queries verbatim, `SELECT *` of a view with every column type read through Npgsql's binary
readers, DirectQuery-shaped filter, `GROUP BY` and `COUNT` statements with `LIMIT 1000001`, a bound
parameter, a refused top-N and a refused `INSERT`, a pooled reconnect (`DISCARD ALL`), a wrong token
and TLS with `SSL Mode=Require`. It skips on a machine with no dotnet SDK; `PowerBiGatewayTest`
replays the same texts as raw protocol everywhere. **Power BI Desktop itself was not run**: the
navigator queries are Power BI's own text as a PostgreSQL server logged them, and the DirectQuery
statements are the shapes its SQL generator produces.

## Where next

- [The PostgreSQL gateway](/help/topics/pgwire) — turning it on, TLS, and every refusal's SQLSTATE.
- [The Kafka sink](/help/topics/sink-kafka) — every option the Fabric section uses.
- [Authentication](/help/topics/authentication) — where the token comes from.
