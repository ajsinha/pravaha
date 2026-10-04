# ADV-GAPS — a second adversarial pass over the 2.0.0 QA round's blind spots

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

The 2.0.0 external round ([`ADV-SURFACE.md`](ADV-SURFACE.md), its log's "Not covered, and why") could
not test five things: **TLS** on the PostgreSQL gateway and Flight, **Npgsql** (no .NET), the
**`stores`** connectors (Aerospike, Cassandra) under failure, **Avro/Protobuf drift** on Kafka, and a
**full disk**. This file is those, and only those.

Written **before** execution, on 2026-10-04, against `develop` at `35bf0e4f` (v2.1.0 + the
2.1.1-SNAPSHOT bump; nothing after the `Release 2.1.0` commit changes the server). The log is
[`../logs/ADV-GAPS.md`](../logs/ADV-GAPS.md).

Case IDs are `QG-<area><nn>`. Every case names the document that promises the expected result; where
no document promises anything the expectation is "what a reasonable operator would expect" and a miss
is a NOTE, not a FAIL. A FAIL needs a minimal reproduction.

**Stance.** Adversarial: each case is written to break a promise.

## Harness

| Name | What |
|---|---|
| **Node** | `pravaha/pravaha-server:2.1.0` in a container on a private network `qg-net`, its `conf/application.yaml` mounted from a scratch directory; `security.authentication: token`, `policy: authenticated`, one static token (`id: qa`, roles operator+admin). Only the ports a case needs are published, on 3xxxx host ports. |
| **PKI** | `openssl`: a private CA, a server certificate for `localhost` and `qg-node` (SAN, *not* `127.0.0.1`), an expired server certificate from the same CA, a second unrelated CA, a client certificate from the CA; keys PKCS#8, and one PKCS#1 copy. |
| **Clients** | psql 18.6; psycopg 3.3.6; pgjdbc 42.7.11 on Java 25 (single-file program); the Java SDK `pravaha-sdk-java-flight-2.1.1-SNAPSHOT-all.jar` (single-file program); the Python SDK from this tree with pyarrow 25.0.1; Npgsql 4.0.17 (the Power BI version) and the current Npgsql 8 in `mcr.microsoft.com/dotnet/sdk:8.0`. |
| **Stores** | `aerospike/aerospike-server:8.1.2.5` and `cassandra:4.1`, own containers on `qg-net` (never the owner's `pravaha-aerospike`); `apache/kafka:3.9.1` (KRaft, one broker) and `confluentinc/cp-schema-registry:7.6.0` on `qg-net`. |

Documents cited: **TLS** = console topic `tls`, **PGW** = console topic `pgwire`, **PBI** = console topic
`power-bi`, **CT** = `docs/guides/CONNECTOR_TLS.md`, **SEC** = `docs/operations/SECURITY.md`, **CON** =
`docs/guides/CONNECTORS.md`, **TS** = `docs/guides/TROUBLESHOOTING.md`, **OPS** =
`docs/operations/OPERATIONS.md`, **SDKR** = `sdk/pravaha-sdk-java/README.md`, **FEED-1** = the
findings-register entry that defines a stopped feed (query stays `RUNNING`, `feed` `STOPPED` with the
failure's code, `/status` `stoppedFeeds`, health `DEGRADED`).

---

## T — TLS on the PostgreSQL gateway and Flight

| ID | Intent | Steps | Expected (promise) |
|---|---|---|---|
| QG-T01 | A node with both pairs set says so | Start the node with `pravaha.pgwire.tls.*` and `pravaha.flight.tls.*` | Log: `PostgreSQL wire protocol listening on … over TLS` and `flight transport=TLS` (PGW, TLS) |
| QG-T02 | `sslmode=require` | `psql "host=localhost … sslmode=require"`, `SELECT` from a view | Connects; psql reports an SSL connection (PGW) |
| QG-T03 | `sslmode=verify-full` with the CA | `sslrootcert=ca.pem`, host `localhost` | Connects, rows returned (PGW "Connect with `sslmode=verify-full`") |
| QG-T04 | `verify-full` with the wrong CA | `sslrootcert=other-ca.pem` | Refused by the client: certificate verify failed; no password sent |
| QG-T05 | `verify-full` with a name not in the certificate | host `127.0.0.1` | Refused by the client: host name mismatch (TLS "verify-full checks the CA and the host name") |
| QG-T06 | A plaintext client against a TLS-configured gateway | `psql sslmode=disable` with the right token | The token never crosses in clear: PGW "authenticates **after** the handshake, so the token is always inside it"; PgWireConnection: "a configured certificate always covers the password". Expected: refused before the password is asked for |
| QG-T07 | psycopg `verify-full` | psycopg connect with `sslrootcert`, parameterised `SELECT` | Rows returned |
| QG-T08 | pgjdbc `verify-full` | `jdbc:postgresql://localhost:P/pravaha?sslmode=verify-full&sslrootcert=ca.pem`, `PreparedStatement` | Rows returned |
| QG-T09 | Java SDK over `grpc+tls` with the CA | `TlsOptions.caCertificate(ca.pem)`, `query`, `queries` | Rows and the query list (SDKR, TLS) |
| QG-T10 | Java SDK, wrong CA | `caCertificate(other-ca.pem)` | A coded `PravahaClientException` (connect failed), promptly — not a hang, not an uncoded gRPC exception |
| QG-T11 | Python SDK over `grpc+tls`, right and wrong CA | `TlsOptions.create(ca_certificate=…)` | Rows; then a coded `PravahaError` for the wrong CA |
| QG-T12 | A plaintext Flight client against a TLS-only Flight port | Java SDK and Python SDK with `grpc://` (no token) | A coded refusal within `requestTimeout`, never a hang |
| QG-T13 | Plaintext pgwire protocol to the Flight port and TLS to a plaintext gateway | `psql sslmode=require` against a node with no pgwire pair | Refused by the client ("server does not support SSL"), because the gateway answers `N` (HLP-5) |
| QG-T14 | An expired server certificate | Node with the expired pair; psql `verify-full`, Java SDK, Python SDK | Every verifying client refuses the handshake. The node starting silently with an expired certificate is a NOTE (TLS promises the *pair* is checked at startup, not its dates) |
| QG-T15 | A PKCS#1 key for the gateway | `pravaha.pgwire.tls.key` = `BEGIN RSA PRIVATE KEY` | The node stops at startup with `PRV-6206`, naming the conversion (PGW) |
| QG-T16 | A client certificate (mutual TLS) | Java SDK `clientCertificate`/`clientKey` from the CA; then one from the other CA | TLS lists `client_certificate` "for mutual TLS". Expected: either the node verifies it, or no document implies it does. Accepting both alike while a document implies verification is a NOTE |
| QG-T17 | A wrong token over TLS | Java SDK with the CA and a wrong token | Coded authentication refusal (`PRV-1022`-family), not a TLS error |

## N — Npgsql (Power BI's driver)

Npgsql 4.0.17 through the repository's probe (`pravaha-pgwire/src/test/dotnet/NpgsqlProbe`), and a
second small probe for what that one does not cover, run in `mcr.microsoft.com/dotnet/sdk:8.0` on
`qg-net` against the node. View `sales`: `region STRING`, `revenue BIGINT` (a `SUM`), and `t_dec`:
`id BIGINT`, `price DECIMAL(10,2)`.

| ID | Intent | Steps | Expected (promise) |
|---|---|---|---|
| QG-N01 | Open, plaintext | Probe, `SSL Mode=Disable` | `OK open` — Npgsql's type loading answered (PGBI-1, PBI) |
| QG-N02 | Catalogue probes | Probe's `GetSchema("Tables")`, `GetSchema("Columns")` and Power BI's `information_schema` queries | All `OK`, the view listed with its columns (PBI) |
| QG-N03 | Parameterised query | Probe's `@min` as `long`; second probe the same as `int` (int4) against the BIGINT column | Both return the rows (PGINTPARAM-1: int4 widened to int8) |
| QG-N04 | `BeginTransaction` / `Commit` / `Rollback` | Probe; second probe adds `Rollback` and a statement after an error inside a transaction | Accepted as no-ops; after an error `25P02` until rollback (PGW "transaction") |
| QG-N05 | DECIMAL | Read `price` (`decimal` in .NET, exact), and a `decimal` parameter against it | Exact values; the parameter accepted or refused by code — never a wrong answer |
| QG-N06 | Npgsql over TLS | Probe with `SSL Mode=Require;Trust Server Certificate=true`; Npgsql 8 with `VerifyFull` and the CA | `OK open` over TLS |
| QG-N07 | Current Npgsql | The second probe on Npgsql 8.x | Opens and queries (PGW lists Npgsql; a regression past 4.0 would be a NOTE since Power BI pins 4.0.17) |

## S — Aerospike and Cassandra under failure

The store containers are private to `qg-net`; the node reaches them by container name. Each source
has a short scan interval (1 s Aerospike, 2 s Cassandra) so a failure is met quickly. Observed through
`GET /api/v1/queries/{name}` (`feed`), `/api/v1/status` and the view's rows.

| ID | Intent | Steps | Expected (promise) |
|---|---|---|---|
| QG-S01 | Aerospike baseline | 50 records in `test.orders`; register `COUNT(*)`, `SUM(amount)` over it (`deletes: detect`) | 50 / the exact sum; feed `RUNNING` |
| QG-S02 | Aerospike stopped mid-stream | `docker stop` the store, wait 3 intervals | Either retried silently with the view intact, or feed `STOPPED` with `PRV-5080`/`5081` (FEED-1); the query stays `RUNNING`, its answer unchanged; nothing uncoded |
| QG-S03 | Aerospike back | `docker start`, write 10 more records | If the feed retried: 60 / new sum. If it stopped: still 50, and the docs say how to resume it (FEED-1). Never a count that double-counts the 50 |
| QG-S04 | Aerospike set truncated | `truncate` the set while running | `deletes: detect` retracts what disappeared (TS §"Aerospike … deletes"): count goes to 0, not stays 50 |
| QG-S05 | Aerospike credentials | `user`/`password` against the community server (no security) | A coded refusal or a stated "security not enabled"; never a silent pass that suggests credentials were checked (NOTE level) |
| QG-S06 | Aerospike unreachable at registration | `hosts: qg-nowhere:3000` | Registration refused `PRV-5080`, naming the host (CON §6) |
| QG-S07 | Cassandra baseline | 40 rows in `qg.orders`; `COUNT(*)`, `SUM(amount)` with `deletes: detect` | 40 / exact sum, stable across passes (SCAN-1) |
| QG-S08 | Cassandra stopped mid-stream, then back | `docker stop`; observe; `docker start`; insert 5 | As S02/S03 with `PRV-5085`/`5086`; never double counting |
| QG-S09 | Cassandra table dropped | `DROP TABLE` while running | Feed `STOPPED` with a coded failure naming the table, or rows retracted; never a silent stale `RUNNING` with nothing said |
| QG-S10 | Cassandra wrong password | PasswordAuthenticator on; source with a wrong password | Registration refused `PRV-5085` naming authentication; the password not echoed |
| QG-S11 | Cassandra keyspace absent | `keyspace: nope` | Registration refused with a code naming the keyspace/table |

## K — Avro and Protobuf drift on Kafka

`format: avro` and `format: protobuf` per CON §"Avro, Protobuf and a schema registry". Records produced
from Python (kafka-python; Avro by fastavro's schemaless writer with the Confluent framing; Protobuf
with a `FileDescriptorSet` built in Python).

| ID | Intent | Steps | Expected (promise) |
|---|---|---|---|
| QG-K01 | Registry baseline | Register schema v1 `{id long, amount long}` → id 1; 5 records; view `SUM(amount)` | 5 rows, exact sum |
| QG-K02 | Incompatible writer schema mid-stream | Register v2 with `amount` as `string` (compatibility `NONE`), 3 records with id 2, then 2 more v1 records | The 3 are dead letters naming schema id 2 and the mismatch; the 2 later v1 records arrive (CON "that record is a dead letter naming the schema id … remembered per id") |
| QG-K03 | Registry unreachable for a new id | Stop the registry; produce a record with an id never seen | `PRV-5109`, the reader stops (feed `STOPPED`, FEED-1) — not a dead letter (CON "a registry being down is not a record's fault") |
| QG-K04 | Static `schema.file`, writer adds a field mid-stream | Reader schema `{id long, amount long}`; writer switches to `{id long, note string, amount long}` without the framing | Each drifted record is a dead letter (it does not decode to the reader schema) — never silently read with wrong values (reasonable expectation; NOTE if no document promises it) |
| QG-K05 | Protobuf, field retyped on the same number | Descriptor `id int64 = 1; amount int64 = 2`; writer sends `amount` as a string (wire type 2) on number 2 | A dead letter, or the column NULL/default *with* the documented proto3 rule — never a wrong non-default number |
| QG-K06 | Framed bytes with no registry configured | `format: avro` + `schema.file`, a Confluent-framed record | The refusal says so and names `schema.registry.url` (CON) |

## D — A full disk

Data directory on a 16 MiB tmpfs (`--mount type=tmpfs,destination=/opt/pravaha/data,tmpfs-size=16m,tmpfs-mode=1777`),
filled from inside the container with a ballast file until a few KiB remain. Checkpoint interval 2 s;
a followed `filesystem` source under `conf/` (not the tmpfs) feeding a keyed view with a DLQ.

| ID | Intent | Steps | Expected (promise) |
|---|---|---|---|
| QG-D01 | Starts on a small volume | Start; register `v1` | Starts; journal, checkpoints, identity under `data/` |
| QG-D02 | Journal append on a full disk | Fill; register `v2` | Refused with a code (registry journal unwritable) and no half-written journal entry; `v1` unaffected (OPS durability) |
| QG-D03 | Checkpoint on a full disk | Fill; append rows so `v1` changes; wait several intervals | Checkpoint failures logged/metered with a code; the node keeps serving reads; no truncated checkpoint is left as the latest (OPS "checkpoint is written … then renamed") |
| QG-D04 | DLQ write on a full disk | Fill; append unparseable lines | Not silently dropped: the record is either dead-lettered later or the feed stops with a code (FEED-1); the count of lost records is zero or stated |
| QG-D05 | Recovery after the disk frees | Remove the ballast; then `docker cp` the data out, start a new container on a bind mount with room | The node recovers: `v1` registered and answering the same rows as before the restart; `v2` either fully present or fully absent; no `PRV` corruption refusal |
| QG-D06 | Spill on a full disk | (only if D01–D05 leave time) `state.spill.directory` on the tmpfs, a view whose state exceeds its memory budget | A coded refusal (`PRV-4001`-family) rather than a crash or a wrong answer |
