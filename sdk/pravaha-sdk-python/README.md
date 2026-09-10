# Pravaha Python SDK

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`LICENSE`](LICENSE).

Python client for [Project Pravaha](../../README.md).

```python
import os
from pravaha import ClientOptions, Consistency, connect

options = ClientOptions.create(
    "grpc+tls://pravaha:9090",
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

Subscriptions are not implemented yet; `query` is request/response.

The surface deliberately mirrors the Java SDK: same concepts, same names, same defaults, so a
team running both does not have to hold two mental models.

## Install

```bash
pip install pravaha            # contracts only
pip install 'pravaha[grpc]'    # with the transport, from Wave 7
```

## Develop

```bash
cd sdk/pravaha-sdk-python
python -m venv .venv && . .venv/bin/activate
pip install -e '.[dev]'
pytest
```

Or from the repository root, as part of the Maven build:

```bash
./mvnw -Ppython verify
```

## Design notes

**No runtime dependencies by default.** A client is installed into someone else's environment,
and every pin it adds is one their resolver has to reconcile. gRPC, protobuf and pyarrow arrive
with the `grpc` extra, not on the default path.

**Options are validated at construction.** A malformed connection string fails next to the code
that supplied it rather than inside a request fifteen minutes later.

**Defaults are the safe choices.** TLS on, `CONSISTENT` reads, a bounded subscriber buffer. A
token over a plaintext connection is refused outright rather than warned about.
