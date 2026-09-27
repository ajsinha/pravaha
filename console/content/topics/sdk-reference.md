---
title: SDK reference
slug: sdk-reference
category: reference
order: 40
icon: braces
summary: "Every public class and call in the Java and Python SDKs — connecting, options and TLS, querying, registering, subscribing, the HTTP-backed catalogue calls — and the errors each raises, with their codes."
badge: REFERENCE
audience: Developers
keywords: [sdk, java, python, PravahaFlightClient, ClientOptions, TlsOptions, QueryResult, Row, Subscription, ChangeBatch, RegisteredQueryInfo, connect, QueryError, ConnectError, ApiError, PravahaClientException, Endpoint, Consistency, from_config]
guide: python-sdk
related: [client-snippets, http-api, cli-reference, point-reads, subscriptions]
---

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

## Installing

| | |
|---|---|
| Java | `com.ash.messaging:pravaha-sdk-java-flight` (brings `pravaha-sdk-java`: options, endpoints, TLS, errors) |
| Python | the `pravaha` package with the `flight` extra (`pip install ./sdk/python[flight]`), which brings `pyarrow>=15`. `import pravaha` works without it — the options, endpoint parser and errors are usable alone — and `connect()` imports the transport lazily |

## Connecting

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

### `ClientOptions`

| Java builder | Python field | Default | |
|---|---|---|---|
| `builder(endpoint)` | `endpoint` / `create(endpoint, …)` | — | `grpc://`, `grpc+tls://`, or `host:port` (TLS) |
| `.token(String)` | `token` | none | Bearer token; never printed by `toString` |
| `.allowInsecureToken(boolean)` | `allow_insecure_token` | `false` | Permit a token over plaintext — loopback tests, a TLS-terminating sidecar |
| `.connectTimeout(Duration)` | `connect_timeout_seconds` | 10 s | |
| `.requestTimeout(Duration)` | `request_timeout_seconds` | 30 s | |
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

### `TlsOptions`

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

### Configuration keys (`applyConfig` / `from_config`)

`endpoint` (or `hosts` plus `tls.enabled`), `token`, `allow-insecure-token`, `application-name`,
`http-url` (Python), and `tls.ca-certificate`, `tls.client-certificate`, `tls.client-key`,
`tls.trust-store`, `tls.trust-store-password`, `tls.trust-store-type`, `tls.key-store`,
`tls.key-store-password`, `tls.key-store-type`, `tls.override-hostname`,
`tls.disable-hostname-verification-insecure`. In Python, `pravaha.config.layered(base)` overlays
`PRAVAHA_*` environment variables on a map, so one key can be overridden without editing a file.

## Querying

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

## Registering and managing

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

## Subscribing

| Java | Python |
|---|---|
| `Subscription subscribe(view, Consumer<ChangeBatch>)` | `for batch in client.subscribe(view): …` |
| `Subscription subscribe(view, Map<String,String> filters, Consumer<ChangeBatch>)` | `client.subscribe(view, {"col": "value"})` |

| `Subscription` (Java) | |
|---|---|
| `run()` | Delivers batches on this thread until closed. **Blocks** |
| `batches()`, `rows()` | Commits and rows delivered so far |
| `isClosed()`, `close()` | |

A `ChangeBatch` is one commit: iterate its `Row`s, `size()`, `isEmpty()`. Java rows are flyweights over
the Arrow buffer, reused for the next commit — copy what you keep. The Python generator yields one
`list[Row]` per commit and never ends by itself. See [Subscriptions](/help/topics/subscriptions).

## The catalogue calls (Python, over HTTP)

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

## Errors

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

## Pitfalls

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

- [Client snippets](/help/topics/client-snippets) — the same operations in every client.
- [HTTP API](/help/topics/http-api) — the endpoints behind the Python catalogue calls.
- [Python SDK guide](/help/python-sdk) — the SDK's own README, in full.
