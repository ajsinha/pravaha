# Pravaha Python SDK

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`LICENSE`](LICENSE).

Python client for [Project Pravaha](../../README.md).

```python
import os
from pravaha import ClientOptions, Consistency, connect

options = ClientOptions.create(
    "grpc+tls://pravaha:19090",
    token=os.environ["PRAVAHA_TOKEN"],
    default_consistency=Consistency.CONSISTENT,
)

with connect(options=options) as client:
    for row in client.query("SELECT user_id, total FROM user_volume WHERE total > ?", [100]):
        print(row["user_id"], row["total"])
```

Pass values for `?` placeholders rather than building the SQL string. A bound value can never be
read as SQL — by the time it reaches the server the statement is already planned, and there is no
parser left for it to reach — and the server plans a statement once and reuses the plan, so two
callers asking the same question about different users share the work (ADR-032).

Any value may be `None`. What that *means* is SQL's business: `WHERE x = ?` bound to `None` matches
no rows, because a comparison with NULL is UNKNOWN. `IS NULL` is what finds the empty ones.

`to_table()` hands the whole result to pandas or Polars in one step, without a row loop:

```python
frame = client.query("SELECT * FROM user_volume").to_table()
```

## Status

**Working against a real server.** Connect, query, iterate, and bind parameters, over Arrow Flight
SQL (ADR-030). Authentication is a bearer token on `ClientOptions`; the SDK refuses to send one over
a plaintext connection unless asked to with `allow_insecure_token=True`, which exists for loopback
tests and sidecar-terminated TLS.

The tests here run against the **actual Java server**, started by the test fixture, rather than a
Python imitation of it — so what passes here is what an application sees.

`query` is request/response; `subscribe` opens a live feed of changes to a view. Each batch is a
`ChangeBatch` — a `list` of rows, one commit — with `.snapshot` and `.frontier`.

A plain `subscribe` starts at the next commit and is **gapful** for a client keeping a copy of the
view: reading the view beside it, before or after, can miss the commit in flight at that moment
(SUB-1). `subscribe(view, filters, snapshot=True)` sends the view first — one batch with
`batch.snapshot` set, every row at a commit with its multiplicity as its weight, even when empty —
and then every commit after it, with nothing between:

```python
for batch in client.subscribe("trade_feed", snapshot=True):
    if batch.snapshot:
        copy = {row["trade_id"]: row.to_dict() for row in batch}
    else:
        for row in batch:
            ...  # apply by row.weight
```

A snapshot subscriber that falls more than 64 commits behind is ended with `PRV-6105` rather than
skipped past a commit; subscribe again. A server older than the SDK refuses `snapshot=True` with
`PRV-6102`.

`subscribe(..., reconnect=True)` survives a server restart: the stream is opened again with backoff
(250 ms to 10 s, for up to `reconnect_timeout` seconds, default 300, `None` for ever), and the first
batch after has `batch.reconnected` set. With `snapshot=True` that batch is a fresh snapshot --
replace your copy with it and nothing is lost. A refusal that will not change is raised at once.

The surface deliberately mirrors the Java SDK: same concepts, same names, same defaults, so a
team running both does not have to hold two mental models.

`register(name, sql, keys, sink=None, retention=None)` takes an ISO-8601 event-time retention
(`"PT24H"`, `"P7D"`, `"forever"`), and `queries()` reports each query's `key_columns`, `sink` and
`retention` as well as its state and fingerprint.

### The engine's HTTP API

Some questions have no Flight form: the stream catalogue, validation with positions, plans as
graphs, what a registered query or view is, which sinks exist, and the node's status. The engine
answers them on its HTTP port, and the SDK calls that published, OpenAPI-locked surface rather than
growing a second implementation of each on Flight — two places deciding what a principal may learn
about the catalogue would drift apart. Give the client the HTTP URL as well:

```python
options = ClientOptions.create("grpc+tls://pravaha:19090", token=token,
                               http_url="https://pravaha:18080")
with connect(options=options) as client:
    client.streams()                      # with eventTime, outOfOrderness, source
    client.validate("SELECT amont FROM txn")["diagnostics"][0]["range"]
    client.explain(sql, graph=True)["graph"]
    client.describe_query("hourly_spend") # keys, retention, sink and its failure, rows in
    client.query_plan("hourly_spend"); client.describe_view("hourly_spend")
    client.replacement_http("hourly_spend")["history"]  # who has served this name
    client.sinks(); client.status(); client.metrics_text()
    client.plugins()                      # manifests, declared capabilities, visible bindings
    client.permissions()                  # what the policy lets this principal do
    client.audit(principal="ann", decision="deny", limit=50)  # 403 unless the policy allows it
```

`audit()` pages newest first: pass a page's `nextCursor` back as `cursor` for the next one. Reading
the audit trail is a permission of its own (`SecurityPolicy.mayReadAudit`), not a consequence of
being allowed to read views, and every attempt is itself recorded.

These return the JSON the API documents, as dicts. A refusal raises `ApiError` with the HTTP
`status`, the engine's `engine_code` (`"PRV-7002"`) and its number as `code`; an engine that does not
answer is status `0` and retryable. The token rule is the Flight one: never over plain `http://`
unless `allow_insecure_token=True` is asked for.

`EngineApi` is the same HTTP surface **without Flight or pyarrow** — for a script that only
administers. Every HTTP method on `Client` delegates to one (`client.http_api`), and it adds the
calls that have no `Client` form: lanes and their rebalance, health, and the engine's identity
endpoints.

```python
from pravaha import EngineApi

api = EngineApi("https://pravaha:18080")
token = api.login("ann", password)["token"]
api = EngineApi("https://pravaha:18080", token=token)
api.lanes(); api.rebalance_lanes(dry_run=True)   # a plan; dry_run=False moves queries
api.health()["status"]                           # UP, DEGRADED, ... (a 503 is answered, not raised)
api.users(); api.create_key("ci", roles=["reader"], expires_days=30)["key"]  # shown once
api.logout()
```

## The command line

Installing the package installs **`pravaha`**, the command line for a running engine (also
`python -m pravaha.cli`, or `bin/pravaha` from a checkout). It is built on this SDK and nothing
else: the Flight commands through `Client`, the HTTP ones through `EngineApi`.

```bash
pip install 'pravaha[flight]'
pravaha --http https://pravaha:18080 login --user ann --save   # token saved, mode 0600
pravaha --url grpc+tls://pravaha:19090 query --sql "SELECT * FROM user_volume WHERE total > ?" --params 100
pravaha queries --json | jq '.[].name'
pravaha subscribe --view trade_feed --snapshot --reconnect
pravaha describe hourly_spend; pravaha lanes; pravaha audit --decision deny
pravaha drop --name hourly_spend --yes   # without --yes it only says what would happen
```

Every command takes `--url`/`--http`/`--token` (or `PRAVAHA_URL`/`PRAVAHA_HTTP`/`PRAVAHA_TOKEN`),
`--json`, and the TLS options, and exits `0` done, `1` the engine refused (its `PRV` code on stderr),
`2` a usage error, `3` nothing answered. Without the `flight` extra the HTTP commands still work.
Planning or running SQL with no server is the Java tool `pravaha-engine`. The full reference is
[`docs/CLI.md`](../../docs/CLI.md) and the console's *CLI reference* help page.

## Install

```bash
pip install pravaha            # contracts only
pip install 'pravaha[flight]'  # with the transport (pyarrow, which brings Flight and gRPC)
```

## Develop

```bash
cd sdk/python
make install     # creates .venv and installs with dev extras
make test
```

Or without `make`, which does the same three things:

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'
pytest
```

**This is a Python project, not a Maven module** — there is no `pom.xml` here, because a pom in a
Python source tree is a lie about what builds it. The repository's Maven build still *runs* these
tests, from the root:

```bash
./mvnw -Ppython verify
```

It invokes the same `pytest` in this directory, preferring `.venv` when one exists. A cross-language
test that is not in the build is a test nobody notices has stopped working — which had already
happened here once, silently, to sixteen of them.

## Layout

```
sdk/python/
├── pyproject.toml      the build and the dependencies
├── Makefile            install, test, lint, typecheck, build
├── pravaha/            the package
└── tests/
```

Flat, with the package in the project root rather than under `src/`. No `pom.xml`: this is built by
`pip`, and a pom here would be a lie about what builds it.

## Design notes

**No runtime dependencies by default.** A client is installed into someone else's environment,
and every pin it adds is one their resolver has to reconcile. gRPC, protobuf and pyarrow arrive
with the `grpc` extra, not on the default path.

**Options are validated at construction.** A malformed connection string fails next to the code
that supplied it rather than inside a request fifteen minutes later.

**Defaults are the safe choices.** TLS on, `CONSISTENT` reads, a bounded subscriber buffer. A
token over a plaintext connection is refused outright rather than warned about.
