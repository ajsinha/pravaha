# Pravaha Python SDK

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`LICENSE`](LICENSE).

Python client for [Project Pravaha](../../README.md).

```python
from pravaha import ClientOptions, Consistency

options = ClientOptions.create(
    "grpc+tls://pravaha:9090",
    token=os.environ["PRAVAHA_TOKEN"],
    default_consistency=Consistency.CONSISTENT,
)
```

## Status

Wave 1 delivers the connection and result **contracts** — endpoint parsing, client options,
consistency modes and the error hierarchy. These are complete and tested.

The gRPC transport that implements them lands with the gateways in **Wave 7** (implementation
plan §11, epic E6). Until then there is nothing to connect to.

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
