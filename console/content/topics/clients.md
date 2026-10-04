---
title: Clients and SDKs
slug: clients
category: reading
order: 50
icon: signpost-split
summary: "Seven ways in — the CLI, the Java and Python SDKs, a Flight SQL driver, psql, the HTTP API and the embedded engine — which to choose, the same read, subscription and registration in each, and every SDK call with the errors it raises."
audience: Developers
keywords: [client, sdk, java sdk, python sdk, flight sql, jdbc, adbc, psql, http, curl, embedded, spring, cli, snippet, copy paste, connect, options, tls, query, register, subscribe, reconnect, catalog, errors, PravahaFlightClient, PravahaClient, connect, ClientOptions]
guide: user-guide
related: [views-and-keys, subscriptions, pgwire, http-api, cli-reference, embedded-engine]
---

Everything a program does with Pravaha goes through one of a handful of doors, and this page has
them in three parts: [which one to choose](#choosing-a-client), [the same operations written for
each](#snippets), and [every SDK call](#sdk-reference) with the errors it raises.

## Choosing a client {#choosing-a-client}

Pravaha has one client protocol, **Arrow Flight SQL** ([ADR-030](/help/decisions/030-flight-sql-as-the-client-protocol)),
and everything that talks to an engine over the network uses it — except two deliberate side doors:
the **PostgreSQL gateway**, for the tools that only speak Postgres, and the **HTTP API**, for the
catalogue, validation, plans, status and audit. Anything one Flight client can do, the others can.

### Which one

| You are… | Use | Reads | Subscribes | Registers / drops | Catalogue, plans, status |
|---|---|---|---|---|---|
| At a shell, a script, an operator's terminal | **`pravaha` CLI** (Python, on the Python SDK) | yes | yes (prints each commit) | yes | yes — `status`, `streams`, `describe`, `plan`, `lanes`, `audit`… |
| Trying SQL with no server at all | **`pravaha-engine`** (Java, offline) | over a file (`run`) | no | no | `validate`, `explain` against a `--schema` |
| A JVM service | **Java SDK** (`pravaha-sdk-java-flight`) | yes | yes, with weights | yes | via the HTTP API |
| A Python service, a notebook, a dataframe | **Python SDK** (`pravaha`) | yes, `.to_table()` | yes, with weights | yes | yes (`http_url`) |
| A BI tool or an existing Flight SQL / JDBC / ADBC driver | **Flight SQL driver** | yes | no | the `CREATE`/`DROP` statements | no |
| `psql`, DBeaver, Grafana, any Postgres driver | **PostgreSQL gateway** (port 5432) | yes | no | **no** — read-only (PRV-6211) | `\d` |
| An operator's script, a dashboard's health check | **HTTP API** (port 18080) | no rows | no | no | yes |
| Pravaha inside your own process, no network | **Embedded engine** | yes | yes | yes | yes |

Two rules hold for every network client:

- **`grpc://` is plaintext and has to be spelled out.** A URL without a scheme means TLS, which is
  the right default; `grpc+tls://host:19090` is the explicit form.
- **A token is never sent over plaintext** unless you say so (`allowInsecureToken(true)`,
  `allow_insecure_token=True`, `--insecure-token`). That switch exists for loopback tests and
  sidecar-terminated TLS; the right fix is almost always `grpc+tls://`.

All the snippets below read the same view — `hourly_spend`, keyed by `user_id` and `window_end` —
with the same point read:

<!-- sql: read -->
```sql
SELECT window_end, spend FROM hourly_spend WHERE user_id = ?
```

### The CLI

`pravaha` is the Python SDK as a command: `pip install "pravaha[flight]"`, or `bin/pravaha` from a
checkout.

```bash
pravaha query --url grpc://localhost:19090 \
    --sql "SELECT window_end, spend FROM hourly_spend WHERE user_id = ?" --params u1
```

```text
window_end           spend
1789808400000000000  1650
```

(`1 row` goes to stderr.) Timestamps print as nanoseconds since the epoch, UTC — the value as Arrow
carries it. `--json` makes any command's output JSON, and the exit code says what happened: `0` done,
`1` the engine refused (its code on stderr), `2` a usage error, `3` nothing answered. Other commands:
`register`, `queries`, `pause`, `resume`, `drop --yes`, `subscribe --view <name> [--filter col=value]
[--snapshot]`, the blue/green and dead-letter commands, and over the HTTP API (`--http`, port 18080)
`status`, `health`, `streams`, `views`, `describe`, `plan`, `validate`, `lanes`, `audit`, `tenants`,
`permissions`, and `login`/`user`/`key`/`session`. With a credential, `pravaha login --user <name>
--save` once, or pass `--token "$PRAVAHA_TOKEN"`. Planning or running SQL with no server is the Java
tool `pravaha-engine` (`validate`, `explain`, `run`). Every command:
[CLI reference](/help/topics/cli-reference).

### The Java SDK

```xml
<dependency>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha-sdk-java-flight</artifactId>
  <version>2.1.1-SNAPSHOT</version>
</dependency>
```

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:19090");
     QueryResult result = client.query(
             "SELECT window_end, spend FROM hourly_spend WHERE user_id = ?", "u1")) {
    for (Row row : result) {
        System.out.println(row.getString("window_end") + " " + row.getLong("spend"));
    }
}
```

`getLong` on a null column throws rather than returning zero — check `isNull` first, or read with
`get`. Rows are flyweights over the Arrow buffer that carried them: copy what you keep. With a
credential: `ClientOptions.builder("grpc+tls://pravaha:19090").token(token).build()`.

### The Python SDK

```bash
cd sdk/python && make install && . .venv/bin/activate
```

```python
from pravaha import connect

with connect("grpc://localhost:19090") as client:
    for row in client.query("SELECT window_end, spend FROM hourly_spend WHERE user_id = ?", ["u1"]):
        print(row["window_end"], row["spend"])
    frame = client.query("SELECT * FROM hourly_spend").to_table()   # pyarrow; to pandas/Polars from there
```

With a credential and the HTTP surface (for `streams()`, `validate()`, `describe_query()` and the
rest):

```python
from pravaha import ClientOptions, connect

client = connect(options=ClientOptions.create(
    "grpc+tls://engine:19090", token=token, http_url="https://engine:18080"))
```

### Any Flight SQL driver (JDBC, ADBC)

Pravaha is a Flight SQL server, so the Apache Arrow Flight SQL JDBC driver and the ADBC Flight SQL
drivers connect directly. JDBC, plaintext on loopback:

```text
jdbc:arrow-flight-sql://localhost:19090/?useEncryption=false
```

A driver's `executeQuery` reads views; its `executeUpdate` runs the management statements —
`CREATE`, `DROP`, `PAUSE`, `RESUME CONTINUOUS QUERY` — and is answered with a row count. A generic
driver has no way to subscribe; use an SDK for that.

### psql and the PostgreSQL gateway

Off by default. Switch it on in the engine's configuration:

```yaml
pravaha:
  pgwire:
    enabled: true
    port: 5432
```

```bash
psql "host=localhost port=5432 user=ann dbname=pravaha" \
     -c "SELECT window_end, spend FROM hourly_spend WHERE user_id = 'u1'"
```

```text
     window_end      | spend
---------------------+-------
 2026-09-19 09:00:00 |  1650
(1 row)
```

It speaks the simple and extended query protocols, answers `psql`'s catalogue queries (`\d`), and
upgrades to TLS on `SSLRequest` when the node has a certificate. It authenticates with a cleartext
password that is your **token** — with the engine's own accounts on, an API key or a session token,
never the account's password (PGWIREPASS-1) — verified by the same verifier as Flight, so give it a certificate
before it crosses a network. It is **read-only**: the management statements are refused with
PRV-6211 (SQLSTATE `25006`). `BYTES` and `TIME` columns are refused by name rather than encoded.

### The HTTP API

The engine's HTTP surface, on port 18080, is for everything *about* the data rather than the data:

```bash
curl -s http://localhost:18080/api/v1/views/hourly_spend        # schema, key, retention, sink
curl -s http://localhost:18080/api/v1/queries/hourly_spend      # a query in full
curl -s -X POST http://localhost:18080/api/v1/queries/validate \
     -H 'Content-Type: application/json' \
     -d '{"sql": "SELECT user_id, amount FROM txn WHERE amount > 100"}'
```

The validation answer has four fields — `valid`, `diagnostics` (each with its `PRV` code, message
and source range), `outputFields` (the columns the query would produce) and `elapsedMicros`. A
refusal is a successful answer with `valid: false`, not an HTTP error: the question was "is this
valid?" and "no, and here is why" answers it.
It also serves `GET /api/v1/streams`, `POST /api/v1/streams`, `/api/v1/sinks`, `/api/v1/plugins`,
`/api/v1/audit`, `/api/v1/me/permissions`, `/api/v1/status` and `/actuator/prometheus`. It does
**not** return a view's rows: reading is Flight's job (or the gateway's). With authentication on,
send `Authorization: Bearer <token>`.

### The embedded engine

No server, no network, no Spring — the whole loop inside your JVM:

```java
try (PravahaEngine engine = PravahaEngine.createDefault()) {
    engine.declareStream("txn", "user_id:STRING,amount:INT64");
    engine.start();
    engine.register("big_txn", "SELECT user_id, amount FROM txn WHERE amount > 100", "user_id");
    engine.push("txn", new Object[] {"u1", 300L});
    Object amount = engine.query("SELECT amount FROM big_txn WHERE user_id = ?", "u1").rows().get(0)[0];
}
```

Every call runs as the anonymous principal: an embedded engine assumes your application has already
decided who may call it. See [the embedded engine](/help/topics/embedded-engine) and
[the Spring Boot starter](/help/topics/spring-boot-starter).

### Pitfalls

!!! warning "The wrong port"
    Flight is **19090**. The HTTP API is **18080**; the gateway **5432**; this console **17070**. An SDK
    pointed at 18080 fails to connect with a protocol error, not a helpful message.

!!! warning "Building SQL strings"
    Bind values with `?` (in `WHERE` and `HAVING` only). A bound value is never parsed as SQL, and
    the server plans the statement once however many values you ask about.

!!! tip "Subscribing needs an SDK"
    Only the Java and Python SDKs, the CLI and the embedded engine can subscribe. A dashboard over
    the gateway polls; a dashboard over an SDK is told.

## The same thing in every client {#snippets}

Every client reaches the same engine through one of three doors: **Arrow Flight SQL** on port 19090 (both
SDKs, the CLI, the console, any Flight SQL driver), the **PostgreSQL gateway** on 5432 when it is turned
on (`psql` and every PostgreSQL driver), and the engine's **HTTP API** on 18080 (the catalogue, validation,
plans and descriptions — but not reading a view's rows). An embedded engine is the fourth: no door at all,
the engine in your process. This part shows the same four operations through each, so you can copy the
one for your stack. The console's view pages generate these for the view and key you are looking at.

### The view and the question

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

### Connecting, and the three ports

| Door | Default | URL form | Token |
|---|---|---|---|
| Flight SQL | 19090 | `grpc://host:19090` (plaintext, spelled out) or `grpc+tls://host:19090`; no scheme means TLS | `--token` / `.token(...)` / `token=` |
| PostgreSQL gateway | 5432, **off** by default | `host=… port=… dbname=pravaha` | as the password |
| HTTP | 18080 | `http://host:18080` or `https://…` | `Authorization: Bearer …` |

Both SDKs and the CLI **refuse to send a token over plaintext** (`grpc://`, `http://`) unless told to by
name — `allowInsecureToken(true)`, `allow_insecure_token=True`, `--insecure-token` — which exists for a
loopback test or a sidecar that terminates TLS on the same host.

### Java SDK

Artifact `com.ash.messaging:pravaha-sdk-java-flight`. Read:

```java
import com.ash.messaging.pravaha.sdk.ClientOptions;
import com.ash.messaging.pravaha.sdk.flight.PravahaFlightClient;
import com.ash.messaging.pravaha.sdk.flight.QueryResult;
import com.ash.messaging.pravaha.sdk.flight.Row;

ClientOptions options = ClientOptions.builder("grpc+tls://engine:19090")
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

### Python SDK

Package `pravaha` with the `flight` extra (it brings pyarrow). Read:

```python
import os
from pravaha import connect, ClientOptions

options = ClientOptions.create("grpc+tls://engine:19090", token=os.environ["PRAVAHA_TOKEN"])
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

### The pravaha CLI

```bash
pravaha query --url grpc+tls://engine:19090 --token "$PRAVAHA_TOKEN" \
  --sql "SELECT user_id, window_end, spend FROM hourly_spend WHERE user_id = ?" --params u1
```

```text
user_id	window_end	spend
u1	2026-09-19T09:00:00Z	4200
u1	2026-09-19T10:00:00Z	1350
2 rows
```

```bash
pravaha subscribe --url grpc+tls://engine:19090 --token "$PRAVAHA_TOKEN" \
  --view hourly_spend --filter user_id=u1

pravaha register --url grpc+tls://engine:19090 --token "$PRAVAHA_TOKEN" \
  --name hourly_spend_copy --sql-file hourly_spend.sql --keys 0,2 --retain P7D
```

```text
registered hourly_spend_copy  state=RUNNING  fingerprint=3f9c2a61d0b4  retain=P7D
a query with the same fingerprint is the same computation, shared
```

`pravaha query --sql "CREATE CONTINUOUS QUERY …"` registers too. Every flag is on
[CLI reference](/help/topics/cli-reference).

### psql, and any PostgreSQL driver

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
`CREATE CONTINUOUS QUERY` is refused with PRV-6211. A driver's own transactions — psycopg's default
mode, pgjdbc with autocommit off, an ORM's session — work as they do against PostgreSQL, with no
`autocommit` setting needed; each read in one sees the view as it is when that read runs
([Transactions](/help/topics/pgwire#transactions)). Details, drivers and types:
[The PostgreSQL gateway](/help/topics/pgwire).

### The embedded engine

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

### Spring Boot

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

### A Flight SQL driver (JDBC, ADBC)

Pravaha's client protocol *is* Arrow Flight SQL (ADR-030), so the Apache Arrow Flight SQL JDBC and ADBC
drivers speak to port 19090 directly. With the Arrow JDBC driver the URL takes the form:

```text
jdbc:arrow-flight-sql://engine:19090?useEncryption=true&token=<token>
```

and a Python ADBC connection is `adbc_driver_flightsql.dbapi.connect("grpc+tls://engine:19090", …)`.

!!! warning "Not driven by the project's tests"
    The engine's own tests drive `FlightSqlClient` — the library these drivers are built on — against
    a real server, and every call it makes returns correct results. No SQL tool or stock driver has
    been driven end to end; the last mile (a driver turning those answers into a `DatabaseMetaData` a
    tool accepts) is untested. Prefer an SDK, or the PostgreSQL gateway for off-the-shelf tools.

### curl: what HTTP can answer

The HTTP API describes things; it does **not** read a view's rows or subscribe. For the same view:

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" https://engine:18080/api/v1/views/hourly_spend
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

### Pitfalls

!!! warning "Pitfall: the wrong port"
    19090 is Flight, 18080 is HTTP, 5432 is the PostgreSQL gateway, and 17070 is the console. An SDK
    pointed at 18080 fails to connect; `http_url` pointed at 19090 gets no HTTP answer.

!!! warning "Pitfall: a token over grpc://"
    Refused by both SDKs and the CLI unless you say `allow_insecure_token` / `--insecure-token`. The
    fix is almost always `grpc+tls://`, not the flag.

!!! warning "Pitfall: numbers quoted as text"
    Binding `"40"` where the column is a number compares text with a number and matches nothing, or is
    refused (PRV-2062). Bind numbers as numbers.

## SDK reference {#sdk-reference}

Two SDKs, one surface: the Python client mirrors the Java one deliberately — the same concepts under the
same names — so a team running both holds one mental model. Both speak **Arrow Flight SQL** to the
engine's port 19090 for reads, subscriptions, registration and lifecycle. The Python SDK also wraps the
engine's **HTTP API** (port 18080) for the calls Flight has no form for; in Java those are plain HTTP (see
[HTTP API](/help/topics/http-api)).

Every example uses the example deployment's view `hourly_spend(user_id, window_start, window_end, spend)`:

<!-- sql: read -->
```sql
SELECT window_end, spend FROM hourly_spend WHERE user_id = ?
```

### Installing

| | |
|---|---|
| Java | `com.ash.messaging:pravaha-sdk-java-flight` (brings `pravaha-sdk-java`: options, endpoints, TLS, errors) |
| Python | the `pravaha` package with the `flight` extra (`pip install ./sdk/python[flight]`), which brings `pyarrow>=15`. `import pravaha` works without it — the options, endpoint parser and errors are usable alone — and `connect()` imports the transport lazily |

### Connecting

| Java | Python | |
|---|---|---|
| `PravahaFlightClient.connect("grpc://host:19090")` | `connect("grpc://host:19090")` | Plaintext, spelled out |
| `PravahaFlightClient.connect(ClientOptions)` | `connect(options=ClientOptions…)` | Everything else |
| — | `connect(url, http_url="http://host:18080")` | Adds the HTTP API for the catalogue calls |
| `client.close()` / try-with-resources | `client.close()` / `with` | Releases the channel |

**The scheme decides TLS.** `grpc://` is plaintext; `grpc+tls://` — or no scheme at all — is TLS, which
is why TLS is the terse default and plaintext has to be asked for by name.

```java
ClientOptions options = ClientOptions.builder("grpc+tls://engine:19090")
        .token(System.getenv("PRAVAHA_TOKEN"))
        .requestTimeout(Duration.ofSeconds(10))
        .tls(TlsOptions.builder().caCertificate(Path.of("/opt/pravaha/conf/ca.pem")).build())
        .build();
try (PravahaFlightClient client = PravahaFlightClient.connect(options)) { /* ... */ }
```

```python
import os
from pravaha import connect, ClientOptions, TlsOptions

options = ClientOptions.create(
    "grpc+tls://engine:19090",
    token=os.environ["PRAVAHA_TOKEN"],
    request_timeout_seconds=10,
    tls=TlsOptions.create(ca_certificate="/opt/pravaha/conf/ca.pem"),
    http_url="https://engine:18080",
)
with connect(options=options) as client:
    ...
```

#### `ClientOptions`

| Java builder | Python field | Default | |
|---|---|---|---|
| `builder(endpoint)` | `endpoint` / `create(endpoint, …)` | — | `grpc://`, `grpc+tls://`, or `host:port` (TLS) |
| `.token(String)` | `token` | none | Bearer token; never printed by `toString` |
| `.allowInsecureToken(boolean)` | `allow_insecure_token` | `false` | Permit a token over plaintext — loopback tests, a TLS-terminating sidecar |
| `.connectTimeout(Duration)` | `connect_timeout_seconds` | 10 s | Deprecated since 2.1.1 and read by nothing: the connection is made inside the first request, so `requestTimeout` bounds it |
| `.requestTimeout(Duration)` | `request_timeout_seconds` (or `connect(…, timeout=)`) | 30 s | The deadline of one request: a query up to its first batch, every action (register, list, pause, drop, replace, dead letters, debug), and in Python every HTTP call. A subscription gets no total deadline; only its opening is bounded. Past it: PRV-1045 |
| `.tls(TlsOptions)` | `tls` | defaults | How to verify the server, and mTLS; refused on a `grpc://` endpoint |
| `.applicationName(String)` | `application_name` | `pravaha-java-sdk` / `pravaha-python-sdk` | |
| `.defaultConsistency(Consistency)` | `default_consistency` | `CONSISTENT` | Declared; the server answers every read at the committed frontier today |
| `.subscriberBufferRows(int)` | `subscriber_buffer_rows` | 10,000 | Rides on the subscription ticket. How far behind this subscriber may fall, in changes |
| `.conflateOnOverflow(boolean)` | `conflate_on_overflow` | `true` | Rides on the subscription ticket. `false` means `FAIL` rather than conflate |
| — | `http_url` | none | The engine's HTTP port, for the catalogue calls |
| `.applyConfig(Map)` | `ClientOptions.from_config(map)` | | From a flat config map (keys below) |

Refused at construction, not at first use: a non-positive timeout, a blank application name, TLS
material on a plaintext endpoint, a token over plaintext without `allow_insecure_token`, an `http_url`
that is not `http(s)://` (and, with a token, `http://` without `allow_insecure_token`).

#### `TlsOptions`

| Java builder | Python field | |
|---|---|---|
| `.caCertificate(Path)` | `ca_certificate` | A PEM CA bundle to trust |
| `.clientCertificate(Path)`, `.clientKey(Path)` | `client_certificate`, `client_key` | mTLS, PEM; one without the other is refused, naming which is missing |
| `.trustStore(path, password, type)` | `trust_store`, `trust_store_password`, `trust_store_type` | JKS or PKCS12 instead of PEM |
| `.keyStore(path, password, type)` | `key_store`, `key_store_password`, `key_store_type` | mTLS from a keystore |
| `.overrideHostname(String)` | `override_hostname` | Check the certificate against this name instead (e.g. `localhost` while dialling `127.0.0.1`) |
| `.disableHostnameVerificationInsecure(true)` | `disable_hostname_verification=True` | Named so nobody enables it by accident |

PEM and a keystore for the same purpose together are refused: two settings claiming to say whom to
trust, with no rule for which wins.

#### Configuration keys (`applyConfig` / `from_config`)

`endpoint` (or `hosts` plus `tls.enabled`), `token`, `allow-insecure-token`, `application-name`,
`http-url` (Python), and `tls.ca-certificate`, `tls.client-certificate`, `tls.client-key`,
`tls.trust-store`, `tls.trust-store-password`, `tls.trust-store-type`, `tls.key-store`,
`tls.key-store-password`, `tls.key-store-type`, `tls.override-hostname`,
`tls.disable-hostname-verification-insecure`. In Python, `pravaha.config.layered(base)` overlays
`PRAVAHA_*` environment variables on a map, so one key can be overridden without editing a file.

### Querying

| Java | Python | Returns |
|---|---|---|
| `client.query(sql)` | `client.query(sql)` | `QueryResult` |
| `client.query(sql, Object... params)` | `client.query(sql, [params])` | `QueryResult` |

```java
try (QueryResult result = client.query(
        "SELECT window_end, spend FROM hourly_spend WHERE user_id = ?", "u1")) {
    for (Row row : result) {
        System.out.println(row.get("window_end") + " " + row.getLong("spend"));
    }
}
```

```python
result = client.query("SELECT window_end, spend FROM hourly_spend WHERE user_id = ?", ["u1"])
print(result.columns)          # ['window_end', 'spend']
table = result.to_table()      # a pyarrow.Table -> .to_pandas(), polars.from_arrow(...)
```

| `QueryResult` (Java) | `QueryResult` (Python) | |
|---|---|---|
| `columns()` | `columns` | Column names, in order |
| iterate `Row`s | iterate `Row`s | Rows in order; a result is consumed once (a second read raises `ReadError`) |
| `toList()` | `to_list()` | Every row: `Object[]` in Java, a `dict` in Python |
| — | `schema`, `to_table()` | The Arrow schema; the whole result as a `pyarrow.Table` |
| `close()` | — | Releases the Arrow buffers |

| `Row` (Java) | `Row` (Python) | |
|---|---|---|
| `getString(i\|name)`, `getLong(…)`, `getDouble(…)`, `get(…)` | `row[i]`, `row["name"]`, `row.get(name, default)` | A value by ordinal or name |
| `isNull(…)` | `row.is_null(key)` | |
| `columns()` | `row.columns` | The view's columns — never the weight |
| `weight()`, `isRetraction()` | `row.weight`, `row.is_retraction` | `+1`/`-1` on a subscription row; `1` on a query row |
| `toArray()` | `row.to_dict()` | A copy |

**Bind values; never concatenate.** Parameters go in `WHERE` and `HAVING` only. In Java a `TIMESTAMP`
placeholder takes epoch **nanoseconds** as a `Number`, a `VARBINARY` one a `byte[]`; a value of the wrong
kind is refused before the call leaves the process ("?2 needs a number, but a String was given").

### Registering and managing

| Java | Python | |
|---|---|---|
| `register(name, sql, List<Integer> keys)` | `register(name, sql, [keys])` | Key = output-column **ordinals** |
| `register(name, sql, keys, sink)` | `register(…, sink="audit_sink")` | Also write every commit to a bound sink |
| `register(name, sql, keys, sink, retention)` | `register(…, retention="P7D")` | ISO-8601 or `forever`; `null`/`None` keeps for ever |
| `queries()` | `queries()` | Every query you may see: `RegisteredQueryInfo` / `RegisteredQuery` |
| `pause(name)`, `resume(name)`, `drop(name)` | the same | Idempotent where the end state is reachable |

`RegisteredQueryInfo` is a record: `name`, `state`, `sql`, `fingerprint`, `rowsIn` (`-1` when withheld
from a row-filtered reader), `keyColumns`, `sink`, `retention`, and `isRunning()`. The Python
`RegisteredQuery` has the same fields in snake case.

```python
q = client.register("spend_by_hour", open("spend.sql").read(), [0, 2], retention="P7D")
print(q.name, q.state, q.fingerprint)
```

```text
spend_by_hour RUNNING 3f9c2a61d0b4
```

Registering in SQL through `query()` is equivalent — `client.query("CREATE CONTINUOUS QUERY … KEYED BY
(…) AS SELECT …")` answers one row: `name`, `state`, `fingerprint`, `sink`.

### Subscribing

| Java | Python |
|---|---|
| `Subscription subscribe(view, Consumer<ChangeBatch>)` | `for batch in client.subscribe(view): …` |
| `Subscription subscribe(view, Map<String,String> filters, Consumer<ChangeBatch>)` | `client.subscribe(view, {"col": "value"})` |
| `Subscription subscribeFromSnapshot(view, filters, Consumer<ChangeBatch>)` — the view's rows first, then every commit | `client.subscribe(view, snapshot=True)` |
| `ReconnectingSubscription subscribe(view, filters, Reconnect, Consumer)` and `subscribeFromSnapshot(view, filters, Reconnect, Consumer)` | `client.subscribe(view, reconnect=True, reconnect_timeout=300)` |

| `Subscription` (Java) | |
|---|---|
| `run()` | Delivers batches on this thread until closed. **Blocks** |
| `batches()`, `rows()` | Commits and rows delivered so far |
| `isClosed()`, `close()` | |

A `ChangeBatch` is one commit: iterate its `Row`s, `size()`, `isEmpty()`. Java rows are flyweights over
the Arrow buffer, reused for the next commit — copy what you keep. The Python generator yields one
`ChangeBatch` (a list of rows, each with its `weight`) per commit and never ends by itself.

**Reconnecting.** Asked for, a subscription that a server restart ended — or that broke with no
diagnosis, or fell behind with `PRV-6105` — is opened again, with backoff from 250 ms to 10 s, for
up to `reconnect_timeout` seconds (300 by default; `None` in Python or `0` on the CLI for ever). A
refusal that will not change, such as a dropped name, is still raised at once. Pair it with a
snapshot: the first batch after reopening is then a fresh snapshot to replace what you hold, and
nothing committed while the node was down is lost. A plain subscription resumes at the next commit;
Python marks that batch `batch.reconnected`, Java calls `onReconnected` before it. See
[Subscriptions](/help/topics/subscriptions#surviving-a-restart-reconnect).

### The catalogue calls (Python, over HTTP)

Each needs `http_url`; without it the call raises `ApiError` saying so.

| Call | Endpoint | Returns |
|---|---|---|
| `streams()`, `stream(name)` | `GET /api/v1/streams[/{name}]` | Stream summaries |
| `declare_stream(name, schema, event_time=, out_of_orderness=)` | `POST /api/v1/streams` | The declared stream |
| `validate(sql)` | `POST /api/v1/queries/validate` | `valid`, `diagnostics`, `outputFields` |
| `explain(sql, level="physical", graph=False)` | `POST /api/v1/queries/explain` | The plan, text and optionally a graph |
| `describe_queries()`, `describe_query(name)`, `query_plan(name)` | `GET /api/v1/queries…` | Full descriptions, running plan |
| `describe_view(name)` | `GET /api/v1/views/{name}` | Schema, key, retention, sink, fingerprint |
| `replacement_http(name)` | `GET /api/v1/queries/{name}/replacement` | The blue/green status, plus the `history` the Flight row cannot carry |
| `sinks()`, `plugins()`, `status()` | `GET /api/v1/sinks`, `/plugins`, `/status` | |
| `permissions()` | `GET /api/v1/me/permissions` | What you may do |
| `audit(since=, until=, principal=, view=, action=, decision=, limit=, cursor=)` | `GET /api/v1/audit` | One page, newest first |
| `metrics_text()` | `GET /actuator/prometheus` | Prometheus text |

```python
check = client.validate("SELECT user_id, COUNT(*) FROM txn GROUP BY user_id")
print(check["valid"], check["diagnostics"][0]["code"])
```

```text
False PRV-2050
```

The SQL it checked:

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) FROM txn GROUP BY user_id
```

### Errors

Every client failure carries a code, the same number in both languages, so a team finds one page per
code.

| Java | Python | Code | Means |
|---|---|---|---|
| `PravahaClientException` (base) | `PravahaError` (base: `code`, `retryable`, `help_url`) | | |
| thrown by `Endpoint.parse` | `MalformedEndpointError` | PRV-1030 | A connection string that does not parse |
| thrown by `ClientOptions.build()` | `InvalidOptionsError` | PRV-1031 | Options refused at construction |
| thrown by `TlsOptions.build()` | `InvalidTlsOptionsError` | PRV-1032 | TLS options that contradict each other |
| `ClientErrors.CONNECT_FAILED` | `ConnectError` (retryable) | PRV-1040 | The server could not be reached |
| `ClientErrors.QUERY_REFUSED` | `QueryError` | PRV-1041 | The server refused; the message is the server's, PRV code first |
| `ClientErrors.READ_FAILED` | `ReadError` | PRV-1042 | A result could not be read, or was read twice |
| `ClientErrors.CLOSED` | — | PRV-1043 | The client was used after `close()` |
| `ClientErrors.TLS_UNREADABLE` | — | PRV-1044 | Certificate or keystore material that cannot be read |
| `ClientErrors.DEADLINE_EXCEEDED` (retryable) | `DeadlineExceededError` (retryable; `call`, `deadline`) | PRV-1045 | A call was not answered within the request timeout; the message names the call and the deadline |
| `ClientErrors.TLS_HANDSHAKE_FAILED` | `ConnectError` (PRV-1040, "certificate verify failed") | PRV-1046 | The node's certificate is not trusted, has expired or does not name the host; not retryable |
| — | `ApiError` (`status`, `engine_code`, `message`) | the engine's own, else 1040 / 1041 | An HTTP call failed; `status` is the HTTP status (`0` when nothing answered), `code` the engine's number when it gave one (`7002` for PRV-7002), retryable when nothing answered or the status was 5xx |

Every Python error's `str()` starts with its own code (`PRV-1041  …`). A refusal from the engine over
Flight arrives as `QueryError` / `QUERY_REFUSED` with the engine's code next — `PRV-4023 …`, `PRV-7002 …` — which is the part to branch on or search for.

```python
from pravaha.client import QueryError

try:
    client.query("SELECT * FROM no_such_view")
except QueryError as e:
    print(e.code, str(e)[:60])
```

```text
1041 PRV-1041  PRV-2002  Object 'no_such_view' not found. Th
```

### Pitfalls

!!! warning "Pitfall: keys are ordinals in `register`, names in SQL"
    `register(name, sql, [0, 2])` keys by output-column position; `CREATE CONTINUOUS QUERY … KEYED BY
    (user_id, window_end)` by name. Reordering the `SELECT` list silently changes an ordinal key, never a
    named one — prefer the SQL form.

!!! warning "Pitfall: an option that is declared but not yet honoured"
    `defaultConsistency` validates and is carried, but the server does not read it yet: every read is
    answered at the committed frontier. Do not build on it. `subscriberBufferRows` and
    `conflateOnOverflow` *are* honoured — they ride on the subscription ticket (STRM-16) — and the
    buffer they size is a bound on how far behind the subscriber may fall, across as many commits as
    that takes (STRM-8).

!!! warning "Pitfall: holding a Java Row"
    A `Row` from a subscription or a result is a view over a reused buffer. Keep `toArray()` or the
    values you read, never the `Row`.

## Where next

- [Views, keys and point reads](/help/topics/views-and-keys#point-reads) and [subscriptions](/help/topics/subscriptions)
- [CLI reference](/help/topics/cli-reference) — every command and flag
- [The PostgreSQL gateway](/help/topics/pgwire) and [the HTTP API](/help/topics/http-api) — every endpoint
- [The embedded engine](/help/topics/embedded-engine) and [the Spring Boot starter](/help/topics/spring-boot-starter)
- The long form: [User guide](/help/user-guide), [Python SDK](/help/python-sdk) and the [Python integration guide](/help/python-api-guide)
