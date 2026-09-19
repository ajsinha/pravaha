---
title: Client snippets
slug: client-snippets
category: reading
order: 50
icon: code-square
summary: "The same read, subscription and registration in every client: the Java SDK, the Python SDK, the pravaha CLI, psql, the embedded engine, Spring, a Flight SQL driver, and curl for what HTTP can answer."
audience: Developers
keywords: [java, python, cli, psql, curl, jdbc, adbc, flight sql, snippet, connect, token, grpc, "grpc+tls", PravahaFlightClient, connect(), ClientOptions]
guide: user-guide#1-connect
related: [point-reads, subscriptions, sdk-reference, cli-reference, http-api, pgwire]
---

Every client reaches the same engine through one of three doors: **Arrow Flight SQL** on port 9090 (both
SDKs, the CLI, the console, any Flight SQL driver), the **PostgreSQL gateway** on 5432 when it is turned
on (`psql` and every PostgreSQL driver), and the engine's **HTTP API** on 8080 (the catalogue, validation,
plans and descriptions — but not reading a view's rows). An embedded engine is the fourth: no door at all,
the engine in your process. This page shows the same four operations through each, so you can copy the
one for your stack. The console's view pages generate these for the view and key you are looking at.

## The view and the question

Every snippet reads `hourly_spend` — per-user spend in one-hour windows — for user `u1`:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend
FROM hourly_spend
WHERE user_id = ?
```

and subscribes to `hourly_spend` filtered to `user_id = u1`. The registration each client can do is:

```sql
CREATE CONTINUOUS QUERY hourly_spend_copy
    KEYED BY (user_id, window_end)
    RETAIN FOR P7D
AS
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

With sample data, every read below prints the equivalent of:

```text
user_id  window_end             spend
u1       2026-09-19T09:00:00Z   4200
u1       2026-09-19T10:00:00Z   1350
```

## Connecting, and the three ports

| Door | Default | URL form | Token |
|---|---|---|---|
| Flight SQL | 9090 | `grpc://host:9090` (plaintext, spelled out) or `grpc+tls://host:9090`; no scheme means TLS | `--token` / `.token(...)` / `token=` |
| PostgreSQL gateway | 5432, **off** by default | `host=… port=… dbname=pravaha` | as the password |
| HTTP | 8080 | `http://host:8080` or `https://…` | `Authorization: Bearer …` |

Both SDKs and the CLI **refuse to send a token over plaintext** (`grpc://`, `http://`) unless told to by
name — `allowInsecureToken(true)`, `allow_insecure_token=True`, `--insecure-token` — which exists for a
loopback test or a sidecar that terminates TLS on the same host.

## Java SDK

Artifact `com.ash.messaging:pravaha-sdk-java-flight`. Read:

```java
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;

ClientOptions options = ClientOptions.builder("grpc+tls://engine:9090")
        .token(System.getenv("PRAVAHA_TOKEN"))
        .build();
try (PravahaFlightClient client = PravahaFlightClient.connect(options);
     QueryResult result = client.query(
             "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?", "u1")) {
    for (Row row : result) {
        System.out.println(row.getString("user_id") + " " + row.get("window_end") + " " + row.getLong("spend"));
    }
}
```

Subscribe (blocks in `run()` until closed; one callback per commit):

```java
try (PravahaFlightClient client = PravahaFlightClient.connect(options);
     Subscription subscription = client.subscribe("hourly_spend", Map.of("user_id", "u1"), batch -> {
         for (Row row : batch) {
             System.out.println(row.weight() + " " + row.getLong("spend"));
         }
     })) {
    subscription.run();
}
```

Register (the key as output-column ordinals, then sink and retention, either may be `null`):

```java
RegisteredQueryInfo info = client.register(
        "hourly_spend_copy", Files.readString(Path.of("hourly_spend.sql")), List.of(0, 2), null, "P7D");
System.out.println(info);
```

```text
hourly_spend_copy [RUNNING, 3f9c2a61d0b4, 0 rows]
```

## Python SDK

Package `pravaha` with the `flight` extra (it brings pyarrow). Read:

```python
import os
from pravaha import connect, ClientOptions

options = ClientOptions.create("grpc+tls://engine:9090", token=os.environ["PRAVAHA_TOKEN"])
with connect(options=options) as client:
    for row in client.query(
            "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?", ["u1"]):
        print(row.to_dict())
```

```text
{'user_id': 'u1', 'window_end': datetime.datetime(2026, 9, 19, 9, 0, tzinfo=datetime.timezone.utc), 'spend': 4200}
{'user_id': 'u1', 'window_end': datetime.datetime(2026, 9, 19, 10, 0, tzinfo=datetime.timezone.utc), 'spend': 1350}
```

Whole result as a dataframe-ready Arrow table: `client.query(sql, params).to_table()`.

Subscribe (a generator; one list per commit):

```python
with connect(options=options) as client:
    for batch in client.subscribe("hourly_spend", {"user_id": "u1"}):
        for row in batch:
            print(row.weight, row.to_dict())
```

Register:

```python
q = client.register("hourly_spend_copy", open("hourly_spend.sql").read(), [0, 2], retention="P7D")
print(q.name, q.state, q.fingerprint)
```

```text
hourly_spend_copy RUNNING 3f9c2a61d0b4
```

## The pravaha CLI

```bash
pravaha query --url grpc+tls://engine:9090 --token "$PRAVAHA_TOKEN" \
  --sql "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?" --params u1
```

```text
user_id	window_end	spend
u1	2026-09-19T09:00:00Z	4200
u1	2026-09-19T10:00:00Z	1350
2 rows
```

```bash
pravaha subscribe --url grpc+tls://engine:9090 --token "$PRAVAHA_TOKEN" \
  --view hourly_spend --filter user_id=u1

pravaha register --url grpc+tls://engine:9090 --token "$PRAVAHA_TOKEN" \
  --name hourly_spend_copy --sql-file hourly_spend.sql --keys 0,2 --retain P7D
```

```text
registered hourly_spend_copy  state=RUNNING  fingerprint=3f9c2a61d0b4  retain=P7D
a query with the same fingerprint is the same computation, shared
```

`pravaha query --sql "CREATE CONTINUOUS QUERY …"` registers too. Every flag is on
[CLI reference](/help/topics/cli-reference).

## psql, and any PostgreSQL driver

Reads only; the gateway must be turned on (`pravaha.pgwire.enabled`). An interactive session binds no
parameters, so the value is a quoted literal:

```bash
PGPASSWORD="$PRAVAHA_TOKEN" psql "host=engine port=5432 dbname=pravaha" \
  -c "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = 'u1'"
```

```text
 user_id |       window_end       | spend
---------+------------------------+-------
 u1      | 2026-09-19 09:00:00+00 |  4200
 u1      | 2026-09-19 10:00:00+00 |  1350
(2 rows)
```

A subscription or a registration is not possible here: the gateway is read-only, and a
`CREATE CONTINUOUS QUERY` is refused with PRV-6211. Details, drivers and types:
[The PostgreSQL gateway](/help/topics/pgwire).

## The embedded engine

No network: the engine is in your JVM, every call runs as the anonymous principal.

```java
try (PravahaEngine engine = PravahaEngine.createDefault()) {
    engine.declareStream("txn",
            "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP",
            "event_time");
    engine.start();
    engine.query("CREATE CONTINUOUS QUERY hourly_spend KEYED BY (user_id, window_end) AS "
            + "SELECT user_id, window_start, window_end, SUM(amount) AS spend "
            + "FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR)) "
            + "GROUP BY user_id, window_start, window_end");
    engine.subscribe("hourly_spend", changes -> changes.forEach(System.out::println));
    // push rows, advance event time ...
    var rows = engine.query("SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?", "u1").rows();
}
```

The whole loop, with pushing rows and closing windows: [Embedded engine](/help/topics/embedded-engine).

## Spring Boot

```java
@Service
class SpendReader {
    private final PravahaTemplate pravaha;
    SpendReader(PravahaTemplate pravaha) { this.pravaha = pravaha; }

    List<Map<String, Object>> forUser(String userId) {
        return pravaha.queryForList(
                "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?", userId);
    }

    @PravahaListener(query = "hourly_spend")
    void onChange(Map<String, Object> row, boolean retraction) { /* ... */ }
}
```

See [Spring Boot starter](/help/topics/spring-boot-starter).

## A Flight SQL driver (JDBC, ADBC)

Pravaha's client protocol *is* Arrow Flight SQL (ADR-030), so the Apache Arrow Flight SQL JDBC and ADBC
drivers speak to port 9090 directly. With the Arrow JDBC driver the URL takes the form:

```text
jdbc:arrow-flight-sql://engine:9090?useEncryption=true&token=<token>
```

and a Python ADBC connection is `adbc_driver_flightsql.dbapi.connect("grpc+tls://engine:9090", …)`.

!!! warning "Not driven by the project's tests"
    The engine's own tests drive `FlightSqlClient` — the library these drivers are built on — against
    a real server, and every call it makes returns correct results. No SQL tool or stock driver has
    been driven end to end; the last mile (a driver turning those answers into a `DatabaseMetaData` a
    tool accepts) is untested. Prefer an SDK, or the PostgreSQL gateway for off-the-shelf tools.

## curl: what HTTP can answer

The HTTP API describes things; it does **not** read a view's rows or subscribe. For the same view:

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" https://engine:8080/api/v1/views/hourly_spend
```

```json
{
  "name": "hourly_spend",
  "schema": [
    {"name": "user_id", "type": "VARCHAR", "nullable": false, "ordinal": 0},
    {"name": "window_start", "type": "TIMESTAMP", "nullable": false, "ordinal": 1},
    {"name": "window_end", "type": "TIMESTAMP", "nullable": false, "ordinal": 2},
    {"name": "spend", "type": "BIGINT", "nullable": false, "ordinal": 3}
  ],
  "keyColumns": [{"name": "user_id", "ordinal": 0}, {"name": "window_end", "ordinal": 2}],
  "retention": "P7D",
  "sink": null,
  "fingerprint": "3f9c2a61d0b4"
}
```

(Illustrative values.) Every endpoint is on [HTTP API](/help/topics/http-api).

## Pitfalls

!!! warning "Pitfall: the wrong port"
    9090 is Flight, 8080 is HTTP, 5432 is the PostgreSQL gateway, and 8090 is the console. An SDK
    pointed at 8080 fails to connect; `http_url` pointed at 9090 gets no HTTP answer.

!!! warning "Pitfall: a token over grpc://"
    Refused by both SDKs and the CLI unless you say `allow_insecure_token` / `--insecure-token`. The
    fix is almost always `grpc+tls://`, not the flag.

!!! warning "Pitfall: numbers quoted as text"
    Binding `"40"` where the column is a number compares text with a number and matches nothing, or is
    refused (PRV-2062). Bind numbers as numbers.

## Where next

- [SDK reference](/help/topics/sdk-reference) — every call in both SDKs.
- [CLI reference](/help/topics/cli-reference) — every command and flag.
- [HTTP API](/help/topics/http-api) — every endpoint.
