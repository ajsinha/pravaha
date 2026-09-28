# Pravaha — Python integration guide and API reference

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

> **Who this is for.** A developer connecting a Python application to a Pravaha engine. Part I is
> the user guide: what you are integrating with, how to connect, and the patterns that hold up in
> production. Part II is the API reference: **every call the Python SDK makes**, with its
> parameters, what it returns, what it refuses, and a sample. Part III is the REST API from any HTTP
> client, for code that cannot use the SDK.
>
> **Every sample on this page was run** against a 0.1.1 node configured as a QA host is
> (`deploy/qa/server.application.yaml`), and every output shown is what it printed, shortened with
> `…` where it is long. On a fresh QA install they run as written. Identifiers that differ from node
> to node — fingerprints, session and dead-letter ids, timestamps — will differ on yours, and the
> node id is shown as a QA host's (`pravaha-qa-01`).

**Contents**

- Part I — User guide
  1. [What you are integrating with](#1-what-you-are-integrating-with)
  2. [Install the SDK](#2-install-the-sdk)
  3. [Connect](#3-connect)
  4. [A first integration, end to end](#4-a-first-integration-end-to-end)
  5. [Patterns that hold up](#5-patterns-that-hold-up)
- Part II — API reference
  6. [Conventions](#6-conventions)
  7. [Connection](#7-connection)
  8. [The catalogue: streams](#8-the-catalogue-streams)
  9. [Planning: validate and explain](#9-planning-validate-and-explain)
  10. [Asking: query](#10-asking-query)
  11. [Continuous queries](#11-continuous-queries)
  12. [Subscribing](#12-subscribing)
  13. [Dead letters](#13-dead-letters)
  14. [Changing a running query: replacement](#14-changing-a-running-query-replacement)
  15. [The time-travel debugger](#15-the-time-travel-debugger)
  16. [The node, access and quotas](#16-the-node-access-and-quotas)
  17. [Errors](#17-errors)
- Part III — The REST API from any HTTP client
  18. [REST conventions](#18-rest-conventions)
  19. [Every endpoint](#19-every-endpoint)
  20. [What REST cannot do](#20-what-rest-cannot-do)
- [Appendix: states and outcomes](#appendix-states-and-outcomes)

---

# Part I — User guide

## 1. What you are integrating with

Pravaha keeps the answer to a SQL question current as data arrives. You **register** a query once;
the engine computes it incrementally from then on, and its answer is a **view** you can read at any
moment or **subscribe** to as it changes.

| Term | What it is |
|---|---|
| **Stream** | A named, typed flow of rows: `txn` below. Declared in the engine's configuration (or by [`declare_stream`](#declare_stream)) and fed by a **source** — a file, a Kafka topic, a database. |
| **Continuous query** | A `SELECT` registered under a name, with **key columns** that identify a row of its answer. It runs until dropped. |
| **View** | The query's current answer, under the query's name. Read it with `SELECT … FROM <name>`. |
| **Commit** | The unit in which a view changes. A subscriber receives one batch per commit, never a half-applied one. |
| **Weight** | Every change carries one: `+1` a row appeared, `-1` a row was withdrawn. An update is a `-1` of the old row and a `+1` of the new. |
| **Sink** | Somewhere a query's answer is written as well as to its view — a table, a topic, a file. |

**Two ports, two protocols.** This matters more than anything else on this page:

| Port | Protocol | What travels on it |
|---|---|---|
| **19090** | Arrow Flight SQL (gRPC) | Registering, pausing, resuming and dropping queries; **reading views**; **subscribing**; dead letters; replacements; the debugger |
| **18080** | HTTP / JSON | The catalogue, validation and plans, descriptions of queries and views, status, plugins, sinks, permissions, quotas, the audit trail, metrics — and the same dead-letter, replacement and debugger calls |

The SDK speaks both. It needs the Flight address to connect, and the HTTP address (`http_url`) for
the calls that live on HTTP. **There is no HTTP endpoint that registers a query or returns a view's
rows** — those need Flight ([§20](#20-what-rest-cannot-do)).

The stream every sample uses is the QA host's demonstration stream:

```
txn(txn_id INT64, user_id STRING, merchant STRING, amount INT64, currency STRING,
    status STRING nullable, event_time TIMESTAMP)        -- event time: event_time, 10s out of order
```

fed from `/opt/pravaha/data/incoming/txn.csv`, which starts with six rows. Appending a line to that
file is how the samples make data arrive.

## 2. Install the SDK

The QA bundle carries the wheel in `dist/`. It needs Python 3.9 or later; the samples here were run
on 3.14.

```bash
python -m venv .venv && . .venv/bin/activate
pip install "dist/pravaha-0.1.1-py3-none-any.whl[flight]"
```

The `[flight]` extra brings `pyarrow`, which the Flight calls need. The HTTP calls use only the
standard library. A `tls-keystore` extra adds JKS/PKCS#12 support; PEM files need nothing extra.

## 3. Connect

```python
import os
from pravaha import connect, ClientOptions

client = connect(options=ClientOptions.create(
    os.environ.get("PRAVAHA_URL", "grpc://qa-vm:19090"),        # Flight
    token=os.environ["PRAVAHA_TOKEN"],                          # the key of a pravaha.security.tokens entry
    http_url=os.environ.get("PRAVAHA_HTTP_URL", "http://qa-vm:18080"),
    allow_insecure_token=True,     # the QA host serves plaintext; remove this once it serves TLS
))
print(client.uri)                  # grpc://qa-vm:19090
```

- **`grpc://` is plaintext and must be written out.** A connection string with no scheme means TLS.
- **The SDK will not send a token over plaintext** unless you pass `allow_insecure_token=True`.
  Without it, `ClientOptions.create` raises `InvalidOptionsError` `PRV-1031`. The flag exists for
  loopback, for TLS ended by a local sidecar, and for a QA host; on anything else, serve TLS.
- **The token** is a key under `pravaha.security.tokens` in the engine's
  `/opt/pravaha/conf/application.yaml`. `install.sh` generated one for you (`id: qa`) and printed it.
- **Connecting does not contact the server.** The first call does; if nothing answers, that call
  raises `ConnectError` ([§17](#17-errors)).
- A `Client` is safe to keep for the life of the process, and is a context manager:

```python
with connect(options=options) as client:
    print(len(client.queries()), "queries")
```

**Over TLS**, point at the engine's CA (PEM), and drop `allow_insecure_token`:

```python
from pravaha import TlsOptions
options = ClientOptions.create(
    "grpc+tls://pravaha.example:19090",
    token=os.environ["PRAVAHA_TOKEN"],
    tls=TlsOptions.create(ca_certificate="/etc/ssl/pravaha-ca.pem"),
    http_url="https://pravaha.example:18080",
)
```

**From configuration**, so an operator can change the endpoint, token or TLS without a code change.
`layered` overlays `PRAVAHA_*` environment variables onto a base map (`PRAVAHA_TOKEN` sets `token`,
`PRAVAHA_TLS_ENABLED` sets `tls.enabled`):

```python
from pravaha.config import layered

config = layered({
    "endpoint": "grpc://qa-vm:19090",          # or "hosts": "qa-vm:19090" plus tls.* keys
    "http-url": "http://qa-vm:18080",
    "allow-insecure-token": "true",
})                                              # token comes from PRAVAHA_TOKEN
client = connect(options=ClientOptions.from_config(config))
```

Keys `from_config` reads: `endpoint` *or* `hosts`, `token`, `http-url`, `allow-insecure-token`,
`application-name`, `tls.enabled`, `tls.ca-certificate`, `tls.client-certificate`,
`tls.client-key`, `tls.trust-store`, `tls.key-store`.

## 4. A first integration, end to end

Register a question, read its answer, and follow it as it changes — the whole product in one script.

```python
import os, threading, time
from pravaha import connect, ClientOptions

client = connect(options=ClientOptions.create(
    "grpc://qa-vm:19090", token=os.environ["PRAVAHA_TOKEN"],
    http_url="http://qa-vm:18080", allow_insecure_token=True))

# 1. Ask once.
client.register("big_payments",
                "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000",
                key_columns=[0])

# 2. Read the answer as it stands.
time.sleep(2)
print(client.query("SELECT * FROM big_payments").to_list())
# [{'txn_id': 3, 'user_id': 'u1', 'amount': 3000}, {'txn_id': 4, 'user_id': 'u3', 'amount': 12000},
#  {'txn_id': 5, 'user_id': 'u2', 'amount': 5000}, {'txn_id': 6, 'user_id': 'u1', 'amount': 7000}]

# 3. Follow it. The first batch is everything the view holds; every batch after is a commit.
for batch in client.subscribe("big_payments", snapshot=True):
    for row in batch:
        print("snapshot" if batch.snapshot else "change", row.weight, row.to_dict())
    # ... and a row arriving at the source arrives here, as {"txn_id": 7, ...} with weight +1
```

Append `7,u4,hooli,8000,USD,OK,2026-09-26T09:01:26Z` to the source file while the loop runs and the
change arrives within the second. That is the pattern everything else is built on.

## 5. Patterns that hold up

### Keeping a copy of a view

Subscribe with `snapshot=True` and **apply weights**; never count rows. The snapshot and the
commits after it are cut at the same point, so the sum of weights is the view — nothing missed,
nothing counted twice:

```python
from collections import Counter

copy = Counter()
for batch in client.subscribe("big_payments", snapshot=True, overflow="FAIL"):
    for row in batch:
        copy[tuple(row.to_dict().items())] += row.weight
    live = [dict(k) for k, n in copy.items() if n > 0]
```

Subscribing and then reading the view separately **can lose the commit in flight between the two**,
silently; `snapshot=True` exists to close that gap. Ask for `overflow="FAIL"` when you keep a copy:
the server's default under pressure is to *conflate* (keep the latest value per key), which drops
the intermediate weights a copy is built from. With `FAIL`, a subscriber that falls too far behind
has its stream ended with `PRV-6105` — subscribe again and start from a fresh snapshot.

**Or let the SDK do it: `reconnect=True`.** A stream ended by a server restart, by a broken
connection or by `PRV-6105` is then opened again, with backoff from 0.25 s to 10 s, for up to
`reconnect_timeout` seconds (default 300; `None` for ever) without a stream. With `snapshot=True`
the first batch after reopening is a fresh snapshot (`batch.snapshot` and `batch.reconnected` both
set): start the copy again from it.

```python
for batch in client.subscribe("big_payments", snapshot=True, overflow="FAIL", reconnect=True):
    if batch.snapshot:
        copy = Counter()
    for row in batch:
        copy[tuple(row.to_dict().items())] += row.weight
```

A refusal that will not change — the name was dropped (`PRV-8018`), a filter names no column — is
raised at once rather than retried.

### Polling instead of subscribing

For a dashboard that refreshes every few seconds, `query()` is simpler and costs one round trip. A
view read is **consistent**: it sees every view as of its last commit. Bind values with `?` rather
than formatting them into the SQL — a bound value can never be read as SQL, and the server reuses
the plan:

```python
rows = client.query("SELECT txn_id, amount FROM big_payments WHERE user_id = ?", ["u3"]).to_list()
```

### Registering idempotently

Registering a name that exists is refused with `PRV-8001`. An integration that starts often should
look before it registers:

```python
from pravaha.client import QueryError

def ensure(client, name, sql, keys):
    if any(q.name == name for q in client.queries()):
        return
    try:
        client.register(name, sql, key_columns=keys)
    except QueryError as e:
        if e.engine_code != "PRV-8001":        # someone else registered it between the two calls
            raise
```

To change what a running name computes, **replace** it ([§14](#14-changing-a-running-query-replacement))
rather than dropping and re-registering: a drop takes the answer away from everyone reading it.

### What counts as the same question

Two registrations of the same question share **one computation** under two names: the engine
matches the normalised plan, and the returned `fingerprint` says whether they matched. Measured on
0.1.1:

| Difference between two registrations | Shared? |
|---|---|
| Case, spacing, line breaks | yes |
| A mirrored comparison: `amount > 1000` and `1000 < amount` | yes |
| A different sink | yes — the sink is not part of the question |
| Different aliases (`AS total` vs `AS t`) | no — the answer's shape differs |
| `GROUP BY` in a different order | no — the key's order differs |
| A different `retention` | no |

`describe_query(name)["sharedWith"]` lists the other names on the same computation. Dropping your
name never stops theirs.

### Handling errors and retrying

Every failure is a subclass of `PravahaError` carrying a stable `PRV-nnnn` code. Branch on
`engine_code` (the engine's diagnosis) and retry on `retryable`:

```python
import time
from pravaha import PravahaError
from pravaha.client import ConnectError, QueryError
from pravaha.rest import ApiError

def with_retry(call, attempts=5):
    for attempt in range(attempts):
        try:
            return call()
        except PravahaError as e:
            if not e.retryable or attempt == attempts - 1:
                raise
            time.sleep(min(2 ** attempt, 30))

try:
    with_retry(lambda: client.register("spend", "SELECT user_id, SUM(amount) FROM txn GROUP BY user_id", [0]))
except QueryError as e:
    print(e.engine_code)     # PRV-2050 -- an unbounded GROUP BY; the fix is a window (§11)
```

`ConnectError` (nothing answered — the engine is down or restarting) is retryable. A `QueryError`
or an `ApiError` below 500 is the engine's considered refusal and will be refused again. On the
0.1.1 wheel, see the note in [§17](#17-errors).

### Permissions, as the built-in policies grant them

Under the QA host's `policy: authenticated`, **every verified caller may register queries, declare
streams, and read and administer every view**. Roles are carried on the token and recorded in the
audit trail, but the only thing they gate in the built-in policies is reading the audit trail
(`admin`). Finer control — per-view read, row filters, who may administer what — is a custom
`SecurityPolicy` on the engine. Ask the node what *you* may do with [`permissions()`](#permissions).

---

# Part II — API reference

## 6. Conventions

- All samples assume `client` from [§3](#3-connect).
- **Protocol** says which port a call uses. A Flight call needs only the Flight address; an HTTP
  call also needs `http_url`, and without it raises `ApiError` status 0 saying so.
- Names of queries, views and streams are case-sensitive strings. **Key columns** are column
  ordinals in the query's `SELECT` list, starting at 0.
- Durations are ISO-8601 (`PT10S`, `PT24H`, `P7D`); instants are ISO-8601 UTC strings.
- Methods that describe things over HTTP return plain `dict`s/`list`s in the API's JSON shape
  (camelCase keys). Methods that travel over Flight return small typed objects (snake_case
  attributes).

**Every call, at a glance**

| Area | Calls |
|---|---|
| Connection | `connect`, `ClientOptions.create`/`from_config`, `TlsOptions.create`, `layered`, `Client.uri`, `close` |
| Catalogue | `streams`, `stream`, `declare_stream` |
| Planning | `validate`, `explain` |
| Asking | `query` → `QueryResult`, `Row` |
| Continuous queries | `register`, `queries`, `describe_queries`, `describe_query`, `describe_view`, `query_plan`, `pause`, `resume`, `drop` |
| Subscribing | `subscribe` → `ChangeBatch` |
| Dead letters | `dead_letters`, `dead_letter`, `dead_letter_count`, `dead_letters_http`, `replay_dead_letters`, `replay_dead_letter`, `replay_dead_letters_http` |
| Replacement | `replace`, `replacement`, `replacements`, `replacement_http`, `cut_over`, `roll_back`, `abandon_replacement`, `finish_replacement`, `throttle_backfill`, `pause_backfill`, `resume_backfill` |
| Debugger | `debug_checkpoints`, `debug_fork`, `debug_step`, `debug_state`, `debug_inspect`, `debug_view`, `debug_sessions`, `debug_session`, `debug_export`, `debug_end` |
| Node and access | `status`, `plugins`, `sinks`, `permissions`, `tenants`, `audit`, `metrics_text` |

## 7. Connection

### `connect`

```python
connect(connection_string=None, *, options=None, http_url=None) -> Client
```

Give a connection string (`"grpc://host:19090"`, `"grpc+tls://host:19090"`, or several hosts
comma-separated) **or** `options`, not both. `http_url` may be given here or in `options`.

```python
client = connect("grpc://localhost:19090", http_url="http://localhost:18080")   # no token
```

### `ClientOptions`

`ClientOptions.create(endpoint, **fields)` builds options from a connection string;
`ClientOptions.from_config(mapping)` from configuration ([§3](#3-connect)).

| Field | Default | Meaning |
|---|---|---|
| `endpoint` | — | Parsed from the connection string |
| `token` | `None` | Bearer token sent on every call, Flight and HTTP |
| `http_url` | `None` | The engine's HTTP address, needed for the HTTP calls |
| `allow_insecure_token` | `False` | Permit a token over plaintext (`PRV-1031` otherwise) |
| `tls` | `TlsOptions()` | Certificates, for a `grpc+tls://` endpoint and an `https://` `http_url` |
| `connect_timeout_seconds` | `10.0` | |
| `request_timeout_seconds` | `30.0` | Per HTTP call |
| `subscriber_buffer_rows` | `10000` | Reserved; a subscription's buffer is set per call with `buffer_rows` |
| `conflate_on_overflow` | `True` | Reserved; set per call with `overflow` |
| `application_name` | `"pravaha-python-sdk"` | Carried in the options; 0.1.1 does not send it |
| `default_consistency` | `CONSISTENT` | Reads see each view at its last commit |

### `TlsOptions.create`

```python
TlsOptions.create(ca_certificate=None, client_certificate=None, client_key=None,
                  trust_store=None, key_store=None, ...)
```

PEM paths for the CA and, for mutual TLS, the client's certificate and key. Keystores need the
`tls-keystore` extra. Certificate material given for a plaintext endpoint is refused
(`InvalidTlsOptionsError`) rather than ignored.

### `Client.uri`, `Client.close`

`uri` is the Flight URI in use. `close()` releases the connection; `with connect(...) as client:`
calls it for you.

## 8. The catalogue: streams

<a id="streams"></a>
### `streams() -> list[dict]` — HTTP `GET /api/v1/streams`

Every stream this caller may read.

```python
for s in client.streams():
    print(s["name"], s["source"], s["eventTime"], [f["name"] for f in s["fields"]])
# txn filesystem event_time ['txn_id', 'user_id', 'merchant', 'amount', 'currency', 'status', 'event_time']
```

Each stream: `name`, `version`, `fieldCount`, `fields` (each `name`, `type`, `nullable`,
`ordinal`), `eventTime`, `outOfOrderness`, `allowedLateness`, `source` (the plugin feeding it, or
`None`).

### `stream(name) -> dict` — HTTP `GET /api/v1/streams/{name}`

One stream, in the same shape.

```python
client.stream("txn")["outOfOrderness"]           # 'PT10S'
client.stream("no_such_stream")                  # ApiError 400 PRV-2003
```

<a id="declare_stream"></a>
### `declare_stream(name, schema, *, event_time=None, out_of_orderness=None) -> dict` — HTTP `POST /api/v1/streams`

Declares a stream from `name:TYPE,...` (`?` after a type makes it nullable). It then exists in the
catalogue and queries can be planned against it; **rows reach it only once a source is bound** in
the engine's configuration.

```python
client.declare_stream("fx_rates", "currency:STRING,rate:DOUBLE,event_time:TIMESTAMP",
                      event_time="event_time", out_of_orderness="PT5S")
# {'name': 'fx_rates', 'version': 1, 'fieldCount': 3, 'fields': [...], 'eventTime': 'event_time',
#  'outOfOrderness': 'PT5S', 'source': None, 'allowedLateness': 'PT0S'}
```

Types: `INT8` `INT16` `INT32` `INT64` `FLOAT32` `FLOAT64`/`DOUBLE` `DECIMAL(p,s)` `STRING`
`BOOLEAN` `DATE` `TIME` `TIMESTAMP` `BYTES`.

## 9. Planning: validate and explain

### `validate(sql) -> dict` — HTTP `POST /api/v1/queries/validate`

Plans a query without running it. **An invalid query is an answer, not an exception.** Use it to
check SQL before registering, or to power an editor.

```python
client.validate("SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000")
# {'valid': True, 'diagnostics': [], 'outputFields': [{'name': 'txn_id', 'type': 'INT64 NOT NULL', ...}, ...],
#  'elapsedMicros': 5262}

r = client.validate("SELECT user_id, SUM(amount) FROM txn GROUP BY user_id")
r["valid"], r["diagnostics"][0]["code"]
# (False, 'PRV-2050')
print(r["diagnostics"][0]["message"])
# PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of
# distinct keys and never shrinks. ...
#   Bound it with a window -- GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id -- ...
```

Each diagnostic: `code`, `message`, `severity`, `helpUrl` (a page on the console when the engine's
`pravaha.docs.base-url` is set, otherwise empty), and `range` — 1-based line and column, when the
parser knew where.

### `explain(sql, level="physical", *, graph=False) -> dict` — HTTP `POST /api/v1/queries/explain`

The plan as text, at `level` `physical`, `logical` or `codegen`; with `graph=True` also as nodes and
edges.

```python
print(client.explain("SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000")["plan"])
# Project[txn_id, user_id, amount]
#   Filter(amount > 1000)
#     Scan(txn)

client.explain(sql, level="codegen")["plan"]
# '-- no generated form: PRV-3101  cannot generate a projection of STRING yet (column 'user_id'). ...
#  -- this query runs on the interpreted path, which is correct and slower'

client.explain(sql, graph=True)["graph"]["nodes"][0]
# {'id': 'n0', 'operator': 'Project', 'detail': 'Project[txn_id, user_id, amount]', 'stateful': False,
#  'fields': ['txn_id', 'user_id', 'amount']}
```

## 10. Asking: query

### `query(sql, parameters=None) -> QueryResult` — Flight

Runs one statement and returns its rows. The usual statement is a read of a view; `?` placeholders
take `parameters`.

```python
result = client.query("SELECT * FROM big_payments")
result.columns                                      # ['txn_id', 'user_id', 'amount']
for row in result:
    print(row["txn_id"], row.get("user_id"), row["amount"])
# 3 u1 3000
# 4 u3 12000

client.query("SELECT txn_id, amount FROM big_payments WHERE user_id = ?", ["u3"]).to_list()
# [{'txn_id': 4, 'amount': 12000}]
```

A parameter may be `None`; `WHERE x = ?` bound to `None` matches nothing (SQL's NULL rules) —
`IS NULL` finds the empty ones. Reading a view that does not exist raises `QueryError` with
`engine_code == "PRV-4023"`.

**Registering through SQL.** `query()` also accepts the SQL form of registration, which is what a
Flight SQL JDBC or ADBC driver can send:

```python
client.query("CREATE CONTINUOUS QUERY eur_payments KEYED BY (txn_id) RETAIN FOR P1D AS "
             "SELECT txn_id, user_id, amount FROM txn WHERE currency = 'EUR'").to_list()
# [{'name': 'eur_payments', 'state': 'RUNNING', 'fingerprint': 'e83f8a7cf718', 'sink': None}]
```

`CREATE OR REPLACE CONTINUOUS QUERY` replaces a running one; without `OR REPLACE`, an existing name
is `PRV-8001`.

### `QueryResult`

Iterate it once for `Row`s, or take it whole. A result is a stream from the server and **can be
read once**; a second read raises `ReadError` (`PRV-1042`).

| Member | Returns |
|---|---|
| `columns` | list of column names |
| `schema` | the Arrow schema |
| `iter(result)` | `Row` objects |
| `to_list()` | list of `dict` |
| `to_table()` | a `pyarrow.Table` — hand it to pandas with `.to_pandas()` |

```python
print(client.query("SELECT * FROM spend_per_minute").to_table())
# pyarrow.Table
# user_id: string not null
# minute_start: timestamp[ns, tz=UTC] not null
# total: int64 not null
# txns: int64 not null
# ----
# user_id: [["u2","u1"]]
# minute_start: [[2026-09-26 09:00:00.000000000Z,2026-09-26 09:00:00.000000000Z]]
# total: [[90,3150]]
# txns: [[1,2]]
```

### `Row`

| Member | |
|---|---|
| `row["col"]`, `row[0]` | a value by name or position |
| `row.get("col", default)` | |
| `row.is_null("col")` | |
| `row.columns` | |
| `row.to_dict()` | |
| `row.weight` | `+1` or `-1` on a subscription's row; `1` on a query's |
| `row.is_retraction` | `weight < 0` |

## 11. Continuous queries

### `register(name, sql, key_columns, sink=None, retention=None) -> RegisteredQuery` — Flight

Starts a computation that keeps `name`'s view current until it is dropped.

| Parameter | |
|---|---|
| `name` | The view's name. Refused with `PRV-8001` if it exists. |
| `sql` | A `SELECT` over declared streams. |
| `key_columns` | Ordinals in the `SELECT` list that identify a row of the answer. |
| `sink` | A binding under the engine's `pravaha.sinks`; the answer is written there as well. Refused before anything runs (`PRV-2041`) when the query revises its answer and the sink can only append. |
| `retention` | How much event time the view keeps: `"PT24H"`, `"P7D"`, `"forever"`; `None` for the server default. |

```python
q = client.register("big_payments",
                    "SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000",
                    key_columns=[0], sink="large_payments", retention="P7D")
q.name, q.state, q.fingerprint, q.sink, q.retention
# ('big_payments', 'RUNNING', '501000393d97', 'large_payments', 'P7D')
```

**Aggregates need a bound.** A `GROUP BY` over a key that grows without limit is refused
(`PRV-2050`) rather than allowed to exhaust memory weeks later. Window it:

```python
client.register("spend_per_minute",
    "SELECT user_id, TUMBLE_START(event_time, INTERVAL '1' MINUTE) AS minute_start, "
    "SUM(amount) AS total, COUNT(*) AS txns FROM txn "
    "GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id",
    key_columns=[0, 1])
```

A window's row appears **when the window closes** — when event time (the watermark) passes its end
plus the stream's out-of-orderness — not row by row. [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md)
lists the SQL that runs.

### `queries() -> list[RegisteredQuery]` — Flight

Every continuous query this caller may see.

```python
for q in client.queries():
    print(q.name, q.state, q.rows_in, q.feed, q.sink_state, q.retention)
# big_payments RUNNING 4 RUNNING ATTACHED PT168H
# spend_per_minute RUNNING 4 RUNNING NONE forever
```

`RegisteredQuery` attributes: `name`, `state` ([appendix](#appendix-states-and-outcomes)), `sql`,
`fingerprint`, `rows_in`, `key_columns`, `sink`, `retention`, `feed` (the source's state),
`feed_stop` (`FeedStop` with `code`, `message`, `where`, `at` when the source stopped),
`sink_state` (`ATTACHED`, `DETACHED` or `NONE`), `sink_failure` (`SinkFailure` with `code`,
`message`). Helpers: `is_running`, `is_source_stopped`, `is_sink_detached`.

### `describe_queries() -> list[dict]`, `describe_query(name) -> dict` — HTTP `GET /api/v1/queries[/{name}]`

The full description: keys by name, retention, sink and whether it is attached, rows in, the names
sharing the computation, each source partition's state, and which parts of the query run generated
code.

```python
client.describe_query("big_payments")
# {'name': 'big_payments', 'state': 'RUNNING', 'sql': '...', 'fingerprint': '501000393d97',
#  'sharedWith': [], 'keyColumns': [{'name': 'txn_id', 'ordinal': 0}], 'retention': 'PT168H',
#  'sink': {'name': 'large_payments', 'attached': True, 'failure': None, 'rowsWritten': 2},
#  'rowsIn': 4, 'countsWithheld': False, 'registeredAt': '2026-09-26T22:17:27.639765012Z',
#  'failure': None, 'reads': ['txn'],
#  'feed': {'state': 'RUNNING', 'description': 'reading txn (1 partition)',
#           'sources': [{'stream': 'txn', 'partition': 0, 'state': 'RUNNING', ...}], ...},
#  'execution': ['interpreted: Project[...] <- Filter(amount > 1000) <- Scan(txn) -- not generated: PRV-3101 ...',
#                'generated: Filter(amount > 1000) <- Scan(txn) -- generated: 35 lines, compiled in 4 ms']}
```

### `describe_view(name) -> dict` — HTTP `GET /api/v1/views/{name}`

A view's schema, key, retention, sink and fingerprint, **without reading it**.

```python
client.describe_view("big_payments")
# {'name': 'big_payments', 'schema': [{'name': 'txn_id', 'type': 'INT64 NOT NULL', ...}, ...],
#  'keyColumns': [{'name': 'txn_id', 'ordinal': 0}], 'retention': 'PT168H',
#  'sink': 'large_payments', 'fingerprint': '501000393d97'}
```

### `query_plan(name) -> dict` — HTTP `GET /api/v1/queries/{name}/plan`

The running plan as `nodes` and `edges`, the query's own numbers under `query`, and — when the
engine runs with `pravaha.metrics.operators` on — per-operator `operatorMetrics` and the
`bottleneck` node. When they are off, `metricsNote` says so.

```python
p = client.query_plan("spend_per_minute")
[n["operator"] for n in p["nodes"]]
# ['Project', 'WindowedAggregate', 'Project', 'WindowAssign', 'Scan']
p["query"]
# {'rowsIn': 4, 'stateHeld': 1, 'stateCeiling': 2000000, 'viewSize': 2, 'watermark': '2026-09-26T09:01:00...', ...}
```

### `pause(name)`, `resume(name)`, `drop(name)` — Flight

`pause` stops the computation without releasing it; the view keeps answering at the point it
reached. `resume` carries on. `drop` removes **your name**: the computation stops when its last
name goes.

```python
client.pause("whales");  [q.state for q in client.queries() if q.name == "whales"]   # ['PAUSED']
client.resume("whales"); [q.state for q in client.queries() if q.name == "whales"]   # ['RUNNING']
client.drop("whales")
```

## 12. Subscribing

### `subscribe(view, filters=None, *, snapshot=False, buffer_rows=None, overflow=None, batch_size_hint=None) -> Iterator[ChangeBatch]` — Flight

Yields one `ChangeBatch` per commit for as long as you iterate. It does not end on its own: `break`
when you have had enough, or close the client.

| Parameter | |
|---|---|
| `filters` | `{"column": value}` equality filters, applied at the server so rows you did not ask for never cross the network. A column the view lacks is refused (`PRV-8002`), never ignored. |
| `snapshot` | `True`: the first batch is the whole view (`batch.snapshot` set), then every commit after it. |
| `buffer_rows`, `overflow` | What the server does when you fall behind: `"CONFLATE"` (keep the latest per key; the default, 10 000 rows), `"DROP_OLDEST"`, or `"FAIL"` (end the stream rather than lose a change). |

```python
for n, batch in enumerate(client.subscribe("big_payments", snapshot=True)):
    print(batch.snapshot, batch.frontier, batch.dropped_before,
          [(r.to_dict(), r.weight) for r in batch])
    if n == 1:
        break
# True 1790413270000000000 0 [({'txn_id': 3, 'user_id': 'u1', 'amount': 3000}, 1),
#                             ({'txn_id': 4, 'user_id': 'u3', 'amount': 12000}, 1)]
# False 1790413280000000000 0 [({'txn_id': 5, 'user_id': 'u2', 'amount': 5000}, 1)]

for batch in client.subscribe("big_payments", {"user_id": "u1"}, buffer_rows=1000, overflow="FAIL"):
    print([(r.to_dict(), r.weight) for r in batch]); break
# [({'txn_id': 6, 'user_id': 'u1', 'amount': 7000}, 1)]      -- u4's row, in the same commit, was not sent
```

**A subscription to a windowed view** receives each window's rows when it closes:

```python
for batch in client.subscribe("spend_per_minute"):
    print([(r.to_dict(), r.weight) for r in batch]); break
# [({'user_id': 'u1', 'minute_start': datetime(2026, 9, 26, 9, 1, tzinfo=utc), 'total': 7050, 'txns': 2}, 1),
#  ({'user_id': 'u4', ...,  'total': 8000, 'txns': 1}, 1), ...]
```

### `ChangeBatch`

A `list` of `Row` with:

| Attribute | |
|---|---|
| `snapshot` | `True` for the first batch of a `snapshot=True` subscription |
| `frontier` | The commit's position in event time (nanoseconds), where the server sends one |
| `dropped_before` | How many changes the server's buffer dropped before this batch (conflation or `DROP_OLDEST`) |
| `missed_anything()` | `dropped_before > 0` |

How a subscription ends: you stop iterating; the view is dropped (`PRV-8018`); a replacement cuts
over (`PRV-4019` — subscribe again); or, with `overflow="FAIL"`, you fell too far behind
(`PRV-6105`). Each ending other than your own `break` arrives as a `QueryError`.

## 13. Dead letters

When a source cannot decode a record and the engine has `pravaha.dlq.directory` set (the QA host
does), the record is set aside on the query's **dead-letter queue** and the source keeps reading.

### `dead_letters(name, *, offset=0, limit=50) -> DeadLetterPage` — Flight

A page, newest first, with the queue's totals.

```python
page = client.dead_letters("big_payments", limit=10)
page.total, page.bytes, page.retention, page.has_more
# (1, 718, '268435456 bytes', False)
e = page.entries[0]
e.id, e.offset, e.code, e.reason, e.raw
# ('fbfdc272-a6ec-463f-a195-ae2347f00ee0', 'line 11', 'PRV-5040',
#  "PRV-5040  line 11, column 'amount' (INT64): 'not-a-number' is not a number",
#  b'10,u5,acme,not-a-number,USD,OK,2026-09-26T09:02:40Z')
```

`DeadLetter`: `id` (the handle), `sequence`, `stream`, `offset` (in the source's terms), `code`,
`reason`, `at`, `size`, `raw` (bytes), `withheld` (why `raw` is empty, when your access to the view
is row-filtered), `replay` (`NEW`, `REPLAYED`, `FAILED_AGAIN`), `replayed_at`.
`DeadLetterPage`: `entries`, `offset`, `total`, `bytes`, `evicted`, `evicted_bytes`, `replayed`,
`failed_again`, `retention`, `configured`, `has_more`.

### `dead_letter(name, letter_id) -> DeadLetter` — Flight

One entry whole. An unknown id is `QueryError` `PRV-4091`.

### `dead_letter_count(name) -> dict` — HTTP `GET /api/v1/queries/{name}/dead-letters/count`

The totals without the records — the call a dashboard polls.

```python
client.dead_letter_count("big_payments")
# {'query': 'big_payments', 'total': 1, 'bytes': 718, 'evicted': 0, 'evictedBytes': 0, 'replayed': 0,
#  'failedAgain': 0, 'oldest': '2026-09-26T22:19:35.737Z', 'newest': '2026-09-26T22:19:35.737Z',
#  'retention': '268435456 bytes', 'configured': True}
```

### `dead_letters_http(name, *, offset=0, limit=50) -> dict` — HTTP `GET /api/v1/queries/{name}/dead-letters`

The same page in JSON; `raw` is base64.

### `replay_dead_letters(name, ids)`, `replay_dead_letter(name, letter_id)` — Flight; `replay_dead_letters_http(name, ids)` — HTTP `POST .../dead-letters/replay`

Feeds chosen entries back through the query **as new rows at its current position** — not a
rewind, and not idempotent: replaying an id twice puts the row in twice. A record that fails again
goes back on the queue as a new entry (`new_id`) and is not retried.

```python
r = client.replay_dead_letter("big_payments", e.id)
r.outcome, r.new_id
# ('FAILED_AGAIN', '35221331-3840-4c41-a59b-6abe57cae77d')
r.detail
# 'the record failed to decode again and has gone back on the queue as a new entry. It is not retried: ...'
```

A replay can be refused `PRV-4092` (HTTP 409): when the source promises exactly-once delivery and
has not yet read past the record, it will deliver it again itself, and a replay would count it
twice. Correct the record at the source, or wait for the source to pass it.

## 14. Changing a running query: replacement

A replacement runs a new version **beside** the running one, fills it from the source's history,
and moves the name across only when both have consumed exactly the same input — so a reader sees the
old answer up to the seam and the new one after it. Needs the administer permission on the name.

### `replace(name, sql, key_columns, *, backfill=None, rate_limit=None, cutover=None, rollback_retention=None) -> Replacement` — Flight

| Option | |
|---|---|
| `backfill` | `"history"` (default: replay it) or `"none"` (start empty, at the live position — only for a query whose answer does not depend on history) |
| `rate_limit` | A ceiling in records per second; it can be lowered while running, never raised |
| `cutover` | `"manual"` (default) or `"auto"` |
| `rollback_retention` | How long the old version is kept for an instant rollback (default `PT1H`) |

An unknown option value is refused by name (`PRV-4018`).

```python
rep = client.replace("big_payments",
                     "SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000",
                     key_columns=[0], backfill="history", rate_limit=10000, cutover="manual")
rep.state, rep.candidate, rep.replacing
# ('BACKFILLING', '42e561cb2556', '501000393d97')
```

### `replacement(name) -> Replacement | None`, `replacements() -> list[Replacement]` — Flight

Where it has got to. Wait for `CAUGHT_UP` before cutting over:

```python
import time
while (r := client.replacement("big_payments")).state == "BACKFILLING":
    time.sleep(1)
if r.state == "FAILED":
    raise RuntimeError(f"backfill failed: {r.failure_code} {r.failure}")
r.state, r.history_rows, r.partitions_live, r.history_complete, r.lag_nanos
# ('CAUGHT_UP', 6, 1, True, 0)
```

`Replacement`: `name`, `state`, `sql`, `candidate`, `replacing`, `sink`, `options`, `owner`,
`started_at`, `cut_over_at`, `rollback_until`, `rollback_available`, `history_rows`, `live_rows`,
`rows_per_second`, `partitions`, `partitions_live`, `history_complete`, `rate_limit`, `paused`,
`lag_nanos`, `failure_code`, `failure`; helper `active`.

> **If a backfill cannot finish.** A backfill that stops — its source failed, or it cannot reach the
> running version's position — makes the replacement `FAILED`, with the source's code in
> `failure_code` and the candidate released; the name goes on answering the version it answered.
> Poll for `CAUGHT_UP` **or** `FAILED`. On the 0.1.1 engine such a replacement instead goes on
> reporting `BACKFILLING` with no failure (REPL-1, fixed after 0.1.1), so bound your wait there.
> On 0.1.2 one more cause fails it (REPL-2, fixed in 0.1.3): if the record at the running
> version's position was dead-lettered, the backfill cannot reach it and fails with `PRV-4013`;
> replace again once a good record has arrived.

### `replacement_http(name) -> dict` — HTTP `GET /api/v1/queries/{name}/replacement`

The same, plus `history`: every version that has served the name and where it took over.

```python
client.replacement_http("big_payments")["history"]
# ['from the beginning: e0b5f710d628', 'from 1790461496882: 673407962606']
```

### `cut_over(name)`, `roll_back(name)`, `finish_replacement(name)`, `abandon_replacement(name)` — Flight

```python
client.query("SELECT * FROM big_payments").to_list()     # the old version: amount > 1000
# [{'txn_id': 3, ...}, {'txn_id': 4, ...}, {'txn_id': 5, ...}, {'txn_id': 6, ...}]
client.cut_over("big_payments").state                     # 'CUT_OVER'
client.query("SELECT * FROM big_payments").to_list()     # the new version: amount > 5000
# [{'txn_id': 4, 'user_id': 'u3', 'amount': 12000}, {'txn_id': 6, 'user_id': 'u1', 'amount': 7000}]
client.roll_back("big_payments").state                    # 'ROLLED_BACK' -- the old answer again
```

| Call | Does | Refused when |
|---|---|---|
| `cut_over` | Moves the name to the new version; every subscription to it ends with `PRV-4019` | Not caught up, or the two versions are not at the same position (`PRV-4014`) |
| `roll_back` | Puts the old version back | The rollback window (`rollback_until`) has passed |
| `finish_replacement` | Confirms the cutover and releases the old version; no rollback after | |
| `abandon_replacement` | Ends a replacement that has not cut over | |

### `throttle_backfill(name, rps)`, `pause_backfill(name)`, `resume_backfill(name)` — Flight

```python
client.throttle_backfill("big_payments", 500).rate_limit   # 500 (never above the starting ceiling)
client.pause_backfill("big_payments").paused               # True
client.resume_backfill("big_payments").paused              # False
```

## 15. The time-travel debugger

Fork a copy of a running query from one of its checkpoints and step it row by row. The fork reads
the same sources from the checkpoint's offsets with **every sink disabled**; the live query, its view
and its subscribers are untouched. Needs the administer permission, and checkpoints (the QA host
takes one a minute).

### `debug_checkpoints(query) -> list[int]` — Flight

```python
client.debug_checkpoints("spend_per_minute")      # [5, 4, 3]   newest first
```

### `debug_fork(query, checkpoint_id=None) -> DebugSession` — Flight

`None` forks from the newest. Refused `PRV-8011` (no checkpoint), `PRV-8012` (a source cannot be
rewound to it), `PRV-8014` (the node's session limit).

```python
s = client.debug_fork("spend_per_minute")
s.id, s.checkpoint_id, s.view_size, s.sinks_disabled, s.streams
# ('dbg-1a0dfd36efb-3', 13, 7, True, ('txn',))
```

### `debug_step(session_id, step="row") -> DebugStep` — Flight

`step` is one of:

| Step | Advances |
|---|---|
| `row` | one input row |
| `rows:N` | N rows |
| `commit` | to the next commit |
| `watermark:<nanos>` | event time to that instant — **a fork's event time moves only when you say so**, so this is how a window is made to close |
| `until:<column>:<op>:<value>` | until a row of the **view** satisfies it (`op` one of `=`, `!=`, `<`, `<=`, `>`, `>=`) |

```python
st = client.debug_step(s.id, "rows:2")
st.rows_consumed, st.view_changes                  # (2, ())     rows went in; no window has closed yet
st = client.debug_step(s.id, "watermark:1790413560000000000")        # 2026-09-26T09:06:00Z
[(d.weight, d.values) for d in st.view_changes]
# [(1, ('u2', '1790413380000000000', '40', '2')), (1, ('u5', '1790413440000000000', '7777', '1')),
#  (1, ('u5', '1790413500000000000', '10', '1'))]
```

`DebugStep`: `session`, `sequence`, `kind`, `rows_in` (`InputRow`: `stream`, `partition`, `offset`,
`weight`, `event_time_nanos`, `values`), `operators` (`OperatorFlow`: `id`, `kind`, `label`,
`rows_in`, `rows_out`), `view_changes` (`ViewDelta`: `weight`, `values`), `watermark_nanos`,
`rows_consumed`, `view_size`, `exhausted`, `stopped` (why a step ended early — "the sources have no
more rows"). An `until:` naming a column the view lacks is `PRV-8015`.

### `debug_state(session_id)`, `debug_inspect(session_id, operator, key=None, offset=0, limit=50)`, `debug_view(session_id)` — Flight

What state the fork holds, one page of one operator's state (read without changing it; bounded, so
a huge store cannot be dumped), and the fork's own view.

```python
client.debug_state(s.id)
# [StateSlot(id='window#0', kind='window', label='windows retained', entries=0)]
page = client.debug_inspect(s.id, "window#0", limit=5)
page.total, page.entries, page.has_more                       # (0, (), False)
[(d.weight, d.values) for d in client.debug_view(s.id)][:2]
# [(1, ('u2', '1790413200000000000', '90', '1')), (1, ('u1', '1790413200000000000', '3150', '2'))]
```

### `debug_sessions()`, `debug_session(id)`, `debug_export(id, name)`, `debug_end(id)` — Flight

```python
[(x.id, x.steps, x.rows_consumed) for x in client.debug_sessions()]   # [('dbg-1a0dfd36efb-3', 4, 4)]
fx = client.debug_export(s.id, "u1 spends in minute two")
fx.class_name, fx.path
# ('U1SpendsInMinuteTwoFixtureTest',
#  'pravaha-it/src/test/java/com/ash/messaging/pravaha/it/fixtures/U1SpendsInMinuteTwoFixtureTest.java')
client.debug_end(s.id)
client.debug_session(s.id)                                              # None
```

`debug_export` turns the session into a JUnit test (`fx.source`) that replays the stepped rows from
empty state and asserts the view — an incident becomes a regression test. Sessions hold resources;
end them.

## 16. The node, access and quotas

### `status() -> dict` — HTTP `GET /api/v1/status`

```python
client.status()
# {'instanceId': 'pravaha-qa-01', 'version': '0.1.1', 'engineState': 'RUNNING', 'uptimeSeconds': 136,
#  'registeredQueries': 2, 'plugins': [], 'streams': 1, 'stoppedFeeds': 0, 'flight': '0.0.0.0:19090'}
```

For a load balancer or orchestrator, the unauthenticated probes are `GET /actuator/health/liveness`
and `/actuator/health/readiness` (`{"status":"UP"}`).

### `plugins() -> list[dict]` — HTTP `GET /api/v1/plugins`

```python
[(p["name"], p["kinds"], p["loaded"]) for p in client.plugins()]
# [('aerospike', ['source'], True), ('aerospike-lookup', ['lookup'], True), ('aerospike-sink', ['sink'], True),
#  ('cassandra', ['source'], True), ('delta', ['source'], True), ('delta-sink', ['sink'], True),
#  ('feedfile', ['source'], True), ('filesystem', ['sink', 'source'], True), ('jdbc', ['source'], True),
#  ('jdbc-lookup', ['lookup'], True), ('jdbc-sink', ['sink'], True), ('kafka', ['source'], True),
#  ('kafka-sink', ['sink'], True), ('postgres-cdc', ['source'], True)]
```

Each also has `version`, `requiredApiVersion`, `compatible`, `capabilities` (guarantee, pushdown,
whether offsets replay…), `settings`, `health` and the `bindings` you may see — never a binding's
options, which can hold credentials.

### `sinks() -> list[dict]` — HTTP `GET /api/v1/sinks`

```python
client.sinks()
# [{'name': 'large_payments', 'plugin': 'filesystem', 'fields': [...], 'keyColumns': [],
#   'emitModes': ['APPEND'], 'acceptsRetractions': False, 'guarantee': 'AT_LEAST_ONCE',
#   'writers': ['big_payments'], 'problem': None}]
```

`acceptsRetractions: False` is why a query that revises its answer cannot write to this sink
(`PRV-2041` at registration).

<a id="permissions"></a>
### `permissions() -> dict` — HTTP `GET /api/v1/me/permissions`

What the engine's policy lets **this caller** do — check before offering a user an action.

```python
client.permissions()
# {'principal': 'ann', 'tenant': 'public', 'roles': ['admin', 'operator'], 'anonymous': False,
#  'policy': 'authenticated', 'register': {'allowed': True, 'reason': None},
#  'readAudit': {'allowed': True, 'reason': None},
#  'views': [{'name': 'big_payments', 'read': 'full', 'administer': {'allowed': True, 'reason': None}}, ...],
#  'streams': [{'name': 'txn', 'read': 'full', 'administer': {'allowed': True, 'reason': None}}]}
```

For a token without `admin`, `readAudit` is
`{'allowed': False, 'reason': 'reading the audit trail needs one of the roles [admin]'}`.

### `tenants() -> dict` — HTTP `GET /api/v1/tenants`

The admission quotas and each tenant's use. A caller who may read the audit trail sees every tenant
(`scope: all`); anyone else sees their own.

```python
client.tenants()
# {'scope': 'all', 'defaults': {'maxQueries': None, 'maxStateKeys': None},
#  'tenants': [{'tenant': 'public', 'queries': 2, 'computations': 2, 'stateKeys': 16,
#               'limits': {'maxQueries': None, 'maxStateKeys': None}, 'queryRefusals': 0, 'stateRefusals': 0}]}
```

A registration over quota is refused `PRV-8020`–`PRV-8023`, naming the quota. Identical SQL from
two tenants is two computations: a tenant shares only with itself.

### `audit(*, since=None, until=None, principal=None, view=None, action=None, decision=None, limit=None, cursor=None) -> dict` — HTTP `GET /api/v1/audit`

One page of recorded authorization decisions, newest first. Needs `admin`; anyone else gets
`ApiError` 403 `PRV-7002` — and the attempt is recorded.

```python
page = client.audit(limit=3, decision="allow")
page["events"][0]
# {'sequence': 116, 'at': '2026-09-26T22:26:54.241867156Z', 'principal': 'ann', 'tenant': 'public',
#  'roles': ['admin', 'operator'], 'action': 'http.audit.read', 'target': 'audit',
#  'decision': 'ALLOW', 'reason': 'allowed', 'detail': None}
more = client.audit(limit=3, cursor=page["nextCursor"])          # the next page; nextCursor is None on the last
```

Also `recording`, `sink`, `capacity`, `retained`, `evicted`, `oldestRetained` and `actions` (the
action names in the window).

### `metrics_text() -> str` — HTTP `GET /actuator/prometheus`

The Prometheus exposition, unparsed:

```python
print("\n".join(l for l in client.metrics_text().splitlines() if l.startswith("pravaha_query_backfill")))
# pravaha_query_backfill_history_rows{application="pravaha",node="pravaha-qa-01",query="big_payments"} 6.0
# pravaha_query_backfill_lag_seconds{application="pravaha",node="pravaha-qa-01",query="big_payments"} 0.0
```

## 17. Errors

```
PravahaError                    code (int), retryable (bool), help_url
├── ConnectError       1040     nothing answered -- retryable
├── QueryError         1041     the engine refused; engine_code, message
├── ReadError          1042     a result could not be read, or was read twice
├── ApiError           (engine's code, or 1040/1041)   an HTTP call; status, engine_code, message
├── MalformedEndpointError 1030 a connection string or endpoint config that cannot be parsed
├── InvalidOptionsError    1031 options that contradict each other -- a token over plaintext
├── InvalidTlsOptionsError 1032 certificate material that cannot be used as given
├── InvalidDocsBaseUrlError 1029 PRAVAHA_DOCS_BASE_URL is set to something that is not a URL
└── MalformedTextError     1053 text that is not valid Unicode (a lone surrogate), refused before sending
```

> **Which SDK has these.** `QueryError.engine_code` and `.message`, and `ConnectError` for an engine
> that does not answer (PYSDK-1, PYSDK-2), are in the SDK **after** 0.1.1. The 0.1.1 wheel in the QA
> bundle raises `QueryError` for both, not retryable, with the engine's code inside the text — read
> it with `re.search(r"PRV-(?!1041)\d{4}", str(e))`, and treat a message containing
> `failed to connect` as retryable.

`QueryError` and `ApiError` both carry **`engine_code`** (`"PRV-8002"`) and **`message`** (the
engine's own sentence). `str(error)` prefixes the client's code:

```python
from pravaha.client import QueryError
try:
    next(client.subscribe("big_payments", {"no_such_column": "x"}))
except QueryError as e:
    e.code, e.engine_code, e.retryable        # (1041, 'PRV-8002', False)
    e.message
    # "PRV-8002  this view has no column 'no_such_column', so that filter cannot be applied to it. Its columns
    #  are [txn_id, user_id, amount]. A filter that was quietly ignored would leave you receiving everything
    #  while believing you asked for a slice"

from pravaha.rest import ApiError
try:
    client.stream("no_such_stream")
except ApiError as e:
    e.status, e.code, e.engine_code, e.retryable     # (400, 2003, 'PRV-2003', False)
```

`help_url` is the code's page on the console when `PRAVAHA_DOCS_BASE_URL` is set in the client's
environment (on a QA host, `http://<host>:17070/help/codes/`), otherwise `""`.

**Codes an integration meets most**

| Code | Means | Do |
|---|---|---|
| `PRV-1031` | Token over plaintext | Use `grpc+tls://`, or `allow_insecure_token=True` where that is acceptable |
| `PRV-1040` | Nothing answered | Retry with backoff |
| `PRV-2003` | No such stream | Check `streams()` |
| `PRV-2041` | Query revises its answer; the sink can only append | A sink that accepts retractions, or a query that only appends |
| `PRV-2050` | Unbounded aggregate | Window the `GROUP BY` |
| `PRV-4014` | Cutover refused: not at the same position | Wait for `CAUGHT_UP` |
| `PRV-4018` | Unknown replacement option | Fix the option value |
| `PRV-4019` | Subscription ended by a cutover | Subscribe again |
| `PRV-4023` | No such view | Check `queries()` |
| `PRV-4091` | No such dead letter | The id is stale or was evicted |
| `PRV-4092` | Replay refused: the source will redeliver | Wait, or fix the record at the source |
| `PRV-6105` | Subscriber too far behind (`overflow="FAIL"`) | Subscribe again from a snapshot |
| `PRV-7002` | Not permitted (HTTP 403) | Check `permissions()` |
| `PRV-8001` | Name already registered | Use it, choose another, or `CREATE OR REPLACE` |
| `PRV-8002` | Filter names a column the view lacks | Check `describe_view()` |
| `PRV-8018` | Subscription ended: the view was dropped | Stop, or subscribe to what replaced it |
| `PRV-8020`–`8023` | Tenant quota | See `tenants()` |

Every code has a page in the console's **Help → Codes** (`/help/codes/PRV-nnnn`).

---

# Part III — The REST API from any HTTP client

## 18. REST conventions

- Base URL `http://<host>:18080`. The machine-readable contract is `GET /api/v1/openapi.json` (no
  token needed); a browsable one is `/api/docs`, which redirects to the Swagger UI.
- **Authentication:** `Authorization: Bearer <token>` on every call. Without it, `401`.
- Bodies are JSON. Names in paths are URL-encoded (`window#0` is `window%230`).
- **Every error has one shape:**

```json
{"code": "PRV-2003",
 "message": "PRV-2003  no stream named 'no_such' is declared on this node. ...",
 "helpUrl": "", "timestamp": "2026-09-26T22:27:17.959034653Z", "path": "/api/v1/streams/no_such"}
```

A small helper used by every sample below:

```python
import requests

BASE = "http://qa-vm:18080"
http = requests.Session()
http.headers["Authorization"] = f"Bearer {os.environ['PRAVAHA_TOKEN']}"

def call(method, path, **kw):
    r = http.request(method, BASE + path, timeout=30, **kw)
    if r.status_code >= 400:
        err = r.json()
        raise RuntimeError(f"{r.status_code} {err['code']}: {err['message']}")
    return r.json() if r.content else None
```

## 19. Every endpoint

All 41 operations the node publishes, with the SDK method that makes the same call. Each was called
as shown.

**Node, catalogue and access**

| Method and path | Body / query | SDK |
|---|---|---|
| `GET /api/v1/status` | | `status()` |
| `GET /api/v1/streams` | | `streams()` |
| `GET /api/v1/streams/{name}` | | `stream()` |
| `POST /api/v1/streams` → 201 | `{"name", "schema", "eventTime"?, "outOfOrderness"?, "allowedLateness"?}` | `declare_stream()` |
| `GET /api/v1/plugins` | | `plugins()` |
| `GET /api/v1/sinks` | | `sinks()` |
| `GET /api/v1/me/permissions` | | `permissions()` |
| `GET /api/v1/tenants` | | `tenants()` |
| `GET /api/v1/audit` | `?since&until&principal&view&action&decision&limit&cursor` | `audit()` |
| `GET /actuator/prometheus` | | `metrics_text()` |
| `GET /actuator/health/liveness`, `/readiness` | no token needed | |

```python
call("POST", "/api/v1/streams", json={"name": "fx", "schema": "currency:STRING,rate:DOUBLE"})
# {'name': 'fx', 'version': 1, 'fieldCount': 2, 'fields': [...], 'eventTime': None, ...}
call("GET", "/api/v1/audit", params={"limit": 2, "decision": "allow"})["events"]
```

**Planning and queries**

| Method and path | Body / query | SDK |
|---|---|---|
| `POST /api/v1/queries/validate` | `{"sql"}` | `validate()` |
| `POST /api/v1/queries/explain` | `{"sql"}`, `?level=physical\|logical\|codegen&format=text\|graph` | `explain()` |
| `GET /api/v1/queries` | | `describe_queries()` |
| `GET /api/v1/queries/{name}` | | `describe_query()` |
| `GET /api/v1/queries/{name}/plan` | | `query_plan()` |
| `GET /api/v1/views/{name}` | | `describe_view()` |

```python
call("POST", "/api/v1/queries/explain", params={"level": "physical", "format": "graph"},
     json={"sql": "SELECT txn_id FROM txn WHERE amount > 10"})["plan"]
# 'Project[txn_id]\n  Filter(amount > 10)\n    Scan(txn)\n'
```

**Dead letters**

| Method and path | Body / query | SDK |
|---|---|---|
| `GET /api/v1/queries/{name}/dead-letters` | `?offset&limit` | `dead_letters_http()` |
| `GET /api/v1/queries/{name}/dead-letters/count` | | `dead_letter_count()` |
| `GET /api/v1/queries/{name}/dead-letters/{id}` | | `dead_letter()` (Flight) |
| `POST /api/v1/queries/{name}/dead-letters/replay` | `{"ids": [...]}` | `replay_dead_letters_http()` |

```python
import base64
page = call("GET", "/api/v1/queries/big_payments/dead-letters", params={"limit": 5})
entry = page["entries"][0]
base64.b64decode(entry["raw"])                 # b'30,u9,acme,oops,USD,OK,2026-09-26T09:06:00Z'
call("POST", "/api/v1/queries/big_payments/dead-letters/replay", json={"ids": [entry["id"]]})
# {'query': 'big_payments', 'results': [{'id': ..., 'outcome': 'FAILED_AGAIN', 'newId': ...}], ...}
```

**Replacement and backfill**

| Method and path | Body / query | SDK |
|---|---|---|
| `POST /api/v1/queries/{name}/replacement` → 201 | `{"sql", "keyColumns", "backfill"?, "rateLimit"?, "cutover"?, "rollbackRetention"?}` | `replace()` |
| `GET /api/v1/queries/{name}/replacement` | | `replacement_http()` |
| `GET /api/v1/replacements` | | `replacements()` |
| `POST /api/v1/queries/{name}/replacement/cutover` | | `cut_over()` |
| `POST /api/v1/queries/{name}/replacement/rollback` | | `roll_back()` |
| `POST /api/v1/queries/{name}/replacement/finish` | | `finish_replacement()` |
| `DELETE /api/v1/queries/{name}/replacement` | | `abandon_replacement()` |
| `GET /api/v1/queries/{name}/backfill` | | `replacement()`'s backfill fields |
| `POST /api/v1/queries/{name}/backfill/throttle` | `?rate=N` | `throttle_backfill()` |
| `POST /api/v1/queries/{name}/backfill/pause` | | `pause_backfill()` |
| `POST /api/v1/queries/{name}/backfill/resume` | | `resume_backfill()` |

```python
Q = "/api/v1/queries/big_payments"
call("POST", Q + "/replacement", json={"sql": "SELECT txn_id, user_id, amount FROM txn WHERE amount > 4000",
                                        "keyColumns": [0], "backfill": "history", "cutover": "manual"})
while call("GET", Q + "/replacement")["state"] == "BACKFILLING":
    time.sleep(1)
call("POST", Q + "/replacement/cutover")["state"]        # 'CUT_OVER'
call("POST", Q + "/replacement/finish")["state"]         # 'FINISHED'
call("GET", Q + "/backfill")
# {'historyRows': 6, 'liveRows': 10, 'rowsPerSecond': 0.0, 'partitions': 1, 'partitionsLive': 1,
#  'historyComplete': True, 'rateLimit': 0, 'paused': False, 'lagSeconds': 0.0}
```

**Debugger**

| Method and path | Body | SDK |
|---|---|---|
| `GET /api/v1/queries/{name}/debug/checkpoints` | | `debug_checkpoints()` |
| `POST /api/v1/queries/{name}/debug` → 201 | `{"checkpointId": N or null}` | `debug_fork()` |
| `GET /api/v1/debug/sessions` | | `debug_sessions()` |
| `GET /api/v1/debug/sessions/{id}` | | `debug_session()` |
| `POST /api/v1/debug/sessions/{id}/step` | `{"step": "row"}` | `debug_step()` |
| `GET /api/v1/debug/sessions/{id}/state` | | `debug_state()` |
| `GET /api/v1/debug/sessions/{id}/state/{operator}` | `?key&offset&limit` | `debug_inspect()` |
| `GET /api/v1/debug/sessions/{id}/view` | | `debug_view()` |
| `POST /api/v1/debug/sessions/{id}/fixture` | `{"name"}` | `debug_export()` |
| `DELETE /api/v1/debug/sessions/{id}` → 204 | | `debug_end()` |

```python
s = call("POST", "/api/v1/queries/spend_per_minute/debug", json={"checkpointId": None})
call("POST", f"/api/v1/debug/sessions/{s['id']}/step", json={"step": "row"})["rowsIn"]
call("GET", f"/api/v1/debug/sessions/{s['id']}/state/window%230", params={"limit": 5})
call("DELETE", f"/api/v1/debug/sessions/{s['id']}")
```

## 20. What REST cannot do

These travel only over Flight — use the Python SDK, the Java SDK, the `pravaha` CLI, or any Arrow
Flight SQL client (JDBC, ADBC):

| Operation | SDK |
|---|---|
| Register a continuous query | `register()`, or `CREATE CONTINUOUS QUERY` through `query()` |
| Pause, resume, drop | `pause()`, `resume()`, `drop()` |
| **Read a view's rows** | `query("SELECT … FROM <view>")` |
| Subscribe to changes | `subscribe()` |
| List queries with their feed and sink state | `queries()` (REST's `describe_queries()` has the same facts in JSON) |

`POST /api/v1/queries` answers `405 PRV-1052`.

---

## Appendix: states and outcomes

| Thing | States |
|---|---|
| Query (`RegisteredQuery.state`) | `RUNNING`, `PAUSED`, `FAILED`, `DROPPED` |
| Query's sink (`sink_state`) | `ATTACHED`, `DETACHED`, `NONE` |
| Replacement (`Replacement.state`) | `BACKFILLING` → `CAUGHT_UP` → `CUT_OVER` → `FINISHED` or `ROLLED_BACK`; or `ABANDONED`, `FAILED` |
| Dead letter (`replay`) | `NEW`, `REPLAYED`, `FAILED_AGAIN` |
| Replay outcome (`DeadLetterReplay.outcome`) | `REPLAYED`, `FAILED_AGAIN` |
| Engine (`status()["engineState"]`) | `RUNNING` when serving |

**See also:** [`USER_GUIDE.md`](USER_GUIDE.md) — the same tasks in Java and the shell;
[`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) — the SQL that runs; [`CONCEPTS.md`](CONCEPTS.md) —
the ideas behind the answers; [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — every code.
