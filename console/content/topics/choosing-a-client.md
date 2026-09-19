---
title: Choosing a client
slug: choosing-a-client
category: start
order: 40
icon: signpost-split
summary: "Seven ways in — the CLI, the Java and Python SDKs, any Flight SQL driver, psql and the PostgreSQL gateway, the HTTP API, and the embedded engine — what each can do, and a minimal working snippet for each."
audience: Developers
keywords: [client, sdk, java, python, cli, jdbc, adbc, flight sql, psql, postgres, http, rest, curl, embedded, grafana, dbeaver]
guide: user-guide
related: [client-snippets, point-reads, subscriptions, pgwire, http-api, embedded-engine]
---

Pravaha has one client protocol, **Arrow Flight SQL** ([ADR-030](/help/decisions/030-flight-sql-as-the-client-protocol)),
and everything that talks to an engine over the network uses it — except two deliberate side doors:
the **PostgreSQL gateway**, for the tools that only speak Postgres, and the **HTTP API**, for the
catalogue, validation, plans, status and audit. Anything one Flight client can do, the others can.

## Which one

| You are… | Use | Reads | Subscribes | Registers / drops | Catalogue, plans, status |
|---|---|---|---|---|---|
| At a shell, trying things | **`pravaha` CLI** | yes | yes (prints each commit) | yes | `validate`, `explain` |
| A JVM service | **Java SDK** (`pravaha-sdk-java-flight`) | yes | yes, with weights | yes | via the HTTP API |
| A Python service, a notebook, a dataframe | **Python SDK** (`pravaha`) | yes, `.to_table()` | yes, with weights | yes | yes (`http_url`) |
| A BI tool or an existing Flight SQL / JDBC / ADBC driver | **Flight SQL driver** | yes | no | the `CREATE`/`DROP` statements | no |
| `psql`, DBeaver, Grafana, any Postgres driver | **PostgreSQL gateway** (port 5432) | yes | no | **no** — read-only (PRV-6211) | `\d` |
| An operator's script, a dashboard's health check | **HTTP API** (port 8080) | no rows | no | no | yes |
| Pravaha inside your own process, no network | **Embedded engine** | yes | yes | yes | yes |

Two rules hold for every network client:

- **`grpc://` is plaintext and has to be spelled out.** A URL without a scheme means TLS, which is
  the right default; `grpc+tls://host:9090` is the explicit form.
- **A token is never sent over plaintext** unless you say so (`allowInsecureToken(true)`,
  `allow_insecure_token=True`, `--insecure-token`). That switch exists for loopback tests and
  sidecar-terminated TLS; the right fix is almost always `grpc+tls://`.

All the snippets below read the same view — `hourly_spend`, keyed by `user_id` and `window_end` —
with the same point read:

<!-- sql: read -->
```sql
SELECT window_end, spend FROM hourly_spend WHERE user_id = ?
```

## The CLI

```bash
pravaha query --url grpc://localhost:9090 \
    --sql "SELECT window_end, spend FROM hourly_spend WHERE user_id = ?" --params u1
```

```text
window_end           spend
1789808400000000000  1650
1 row
```

Timestamps print as nanoseconds since the epoch, UTC — the value as Arrow carries it. Other commands:
`register`, `queries`, `pause`, `resume`, `drop`, `subscribe --view <name> [--filter col=value]`,
and the offline `run`, `validate` and `explain`. With a credential add `--token "$PRAVAHA_TOKEN"`.

## The Java SDK

```xml
<dependency>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha-sdk-java-flight</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090");
     QueryResult result = client.query(
             "SELECT window_end, spend FROM hourly_spend WHERE user_id = ?", "u1")) {
    for (Row row : result) {
        System.out.println(row.getString("window_end") + " " + row.getLong("spend"));
    }
}
```

`getLong` on a null column throws rather than returning zero — check `isNull` first, or read with
`get`. Rows are flyweights over the Arrow buffer that carried them: copy what you keep. With a
credential: `ClientOptions.builder("grpc+tls://pravaha:9090").token(token).build()`.

## The Python SDK

```bash
cd sdk/python && make install && . .venv/bin/activate
```

```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    for row in client.query("SELECT window_end, spend FROM hourly_spend WHERE user_id = ?", ["u1"]):
        print(row["window_end"], row["spend"])
    frame = client.query("SELECT * FROM hourly_spend").to_table()   # pyarrow; to pandas/Polars from there
```

With a credential and the HTTP surface (for `streams()`, `validate()`, `describe_query()` and the
rest):

```python
from pravaha import ClientOptions, connect

client = connect(options=ClientOptions.create(
    "grpc+tls://engine:9090", token=token, http_url="https://engine:8080"))
```

## Any Flight SQL driver (JDBC, ADBC)

Pravaha is a Flight SQL server, so the Apache Arrow Flight SQL JDBC driver and the ADBC Flight SQL
drivers connect directly. JDBC, plaintext on loopback:

```text
jdbc:arrow-flight-sql://localhost:9090/?useEncryption=false
```

A driver's `executeQuery` reads views; its `executeUpdate` runs the management statements —
`CREATE`, `DROP`, `PAUSE`, `RESUME CONTINUOUS QUERY` — and is answered with a row count. A generic
driver has no way to subscribe; use an SDK for that.

## psql and the PostgreSQL gateway

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
password that is your **token** — verified by the same verifier as Flight — so give it a certificate
before it crosses a network. It is **read-only**: the management statements are refused with
PRV-6211 (SQLSTATE `25006`). `BYTES` and `TIME` columns are refused by name rather than encoded.

## The HTTP API

The engine's HTTP surface, on port 8080, is for everything *about* the data rather than the data:

```bash
curl -s http://localhost:8080/api/v1/views/hourly_spend        # schema, key, retention, sink
curl -s http://localhost:8080/api/v1/queries/hourly_spend      # a query in full
curl -s -X POST http://localhost:8080/api/v1/queries/validate \
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

## The embedded engine

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

## Pitfalls

!!! warning "The wrong port"
    Flight is **9090**. The HTTP API is **8080**; the gateway **5432**; this console **8090**. An SDK
    pointed at 8080 fails to connect with a protocol error, not a helpful message.

!!! warning "Building SQL strings"
    Bind values with `?` (in `WHERE` and `HAVING` only). A bound value is never parsed as SQL, and
    the server plans the statement once however many values you ask about.

!!! tip "Subscribing needs an SDK"
    Only the Java and Python SDKs, the CLI and the embedded engine can subscribe. A dashboard over
    the gateway polls; a dashboard over an SDK is told.

## Where next

- [Client snippets for every language](/help/topics/client-snippets)
- [Point reads](/help/topics/point-reads) and [subscriptions](/help/topics/subscriptions)
- [The PostgreSQL gateway](/help/topics/pgwire) and [the HTTP API](/help/topics/http-api)
- The long form: [User guide](/help/user-guide) and [Python SDK](/help/python-sdk)
