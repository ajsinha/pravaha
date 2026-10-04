# ADV-GAPS — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Cases: [`../cases/ADV-GAPS.md`](../cases/ADV-GAPS.md). Executed 2026-10-04 against
`pravaha/pravaha-server:2.1.0` (the server has not changed since the `Release 2.1.0` commit) and the
Java/Python SDKs of `develop` at `35bf0e4f`, by one agent, one heavy thing at a time.

What was run:

- **Nodes**: `pravaha/pravaha-server:2.1.0` on a private network `qg-net`, `conf/` mounted read-only,
  token authentication with one static token (masked as `<token>` in the evidence). Host ports 36432
  (pgwire), 39090 (Flight), 38080 (HTTP), loopback only.
- **PKI**: `openssl` — a CA, a server certificate for `localhost`/`qg-node` (SAN; not `127.0.0.1`), an
  expired one (2025-01-01..2025-02-01, same CA), an unrelated CA, a client certificate from each CA, and
  a PKCS#1 copy of the server key.
- **Clients**: psql 18.6, psycopg 3.3.6, pgjdbc 42.7.11 on Java 25, the Java SDK
  (`pravaha-sdk-java-flight-2.1.1-SNAPSHOT-all.jar`), the Python SDK from this tree on pyarrow 25.0.1,
  Npgsql 4.0.17 (the repository's `NpgsqlProbe`, unchanged, plus a second probe) and Npgsql 8.0.5 in
  `mcr.microsoft.com/dotnet/sdk:8.0`.
- **Stores**: `aerospike/aerospike-server:8.1.2.5` (needs `--ulimit nofile=65536`), `cassandra:4.1`
  with `PasswordAuthenticator`, `apache/kafka:3.9.1` (KRaft) and `confluentinc/cp-schema-registry:7.6.0`
  — all private to `qg-net`; the owner's containers and ports were not touched.
- **Full disk**: the data directory on `--mount type=tmpfs,destination=/opt/pravaha/data,tmpfs-size=16m,tmpfs-mode=1777`,
  filled from inside the container with `dd` ballast. Two things learned on the way, both about the
  method rather than the product: a tmpfs fills in 4 KiB pages, so an append that fits in a file's last
  page succeeds on a "full" disk (the first D02 attempt registered happily until the journal crossed a
  page); and `docker cp` does not see a tmpfs mount, so the data was taken out with `docker exec … tar`.

Evidence: [`adv-gaps-evidence/`](adv-gaps-evidence/) (stack-trace lines and the token stripped). The
scratch scripts that produced it were under `~/.cache/pravaha-qg/s/`; the one a fix needs is quoted
below.

---

## Summary

| Area | Cases | PASS | FAIL | NOTE | NOT RUN |
|---|---|---|---|---|---|
| T — TLS | 17 | 15 | 1 | 1 | 0 |
| N — Npgsql | 7 | 5 | 1 | 1 | 0 |
| S — Aerospike, Cassandra | 11 | 10 | 1 | 0 | 0 |
| K — Avro/Protobuf drift | 6 | 4 | 0 | 2 | 0 |
| D — Full disk | 6 | 2 | 3 | 0 | 1 |
| **Total** | **47** | **36** | **6** | **4** | **1** |

Findings (suggested register text at the end):

| ID | Severity | Cases | One line |
|---|---|---|---|
| DLQFULL-1 | HIGH | QG-D04 | A dead letter that cannot be written (full disk) is dropped with no log, no metric and no stop — the record is gone |
| DISKJOURNAL-1 | HIGH, **FIXED** | QG-D02, QG-D05 | A journal append refused by a full disk leaves torn bytes; the next registration lands behind them and the node then refuses to start (PRV-8005) |
| PGTLSONLY-1 | MEDIUM | QG-T06 | A TLS-configured gateway still accepts a plaintext startup and asks for the token in cleartext, contrary to "the token is always inside it" |
| DECPARAM-1 | MEDIUM | QG-N05 | Any `?`/`$1` parameter compared with a DECIMAL column is refused PRV-2021 ("RexDynamicParam") on every transport |
| CASSDC-1 | MEDIUM | QG-S07 | `local.datacenter` is documented as optional and auto-detected; without it every Cassandra registration fails with the driver's raw `IllegalStateException` |
| CKPTWHY-1 | LOW | QG-D03 | A failing checkpoint is counted but its reason is neither logged nor exposed anywhere, though the docs say "the reason is its last checkpoint failure" |
| TLSDIAG-1 | LOW | QG-T10, QG-T14 | The Java SDK reports an untrusted or expired server certificate as `PRV-1040 cannot reach … io exception Channel Pipeline: […]`, retryable |
| SDKCLOSE-1 | LOW | QG-T09 | `PravahaFlightClient.close()` throws an uncoded `IllegalStateException: Memory was leaked` when a `QueryResult` was read but not closed |
| MTLSDOC-1 | LOW | QG-T16 | The node never asks for a client certificate, yet the SDKs' TLS options and the `tls` topic offer "mutual TLS" with no word that the node ignores it |
| NPGSQLNEW-1 | LOW | QG-N07 | Npgsql 8 fails to open with PRV-6201 (the docs say PRV-6205); `Server Compatibility Mode=NoTypeLoading` makes it work, undocumented |
| PBDRIFT-1 | LOW | QG-K05 | A protobuf field sent with the wrong wire type is read as its default (0), not dead-lettered |
| CERTEXP-1 | LOW | QG-T14 | A node starts with an expired certificate and says nothing |

Not covered, and why: **QG-D06** (spill on a full disk) — not run for budget; the spill tier's
free-space pre-check (PRV-4006) is unit-tested and needs a view big enough to spill, which a 16 MiB
volume and this session's time did not fit. **Mutual TLS** as a server feature — not built, so QG-T16
tests only what is offered. **Aerospike authentication** — Community Edition has no security, so a
wrong password cannot be told from "security not enabled" (QG-S05 tests what CE answers).
**The alerts and identity journals on a full disk** — same append pattern as the registry journal,
not exercised; DISKJOURNAL-1's shape should be checked there too. **DISKJOURNAL-1's fix inside the
container** — verified by a seed-proven unit test, not by rebuilding the image.

---

## T — TLS

### QG-T01 — PASS
`flight transport=TLS`; `Flight SQL listening on 0.0.0.0:19090`; `PostgreSQL wire protocol listening on
0.0.0.0:5432 over TLS` (`tls-t14.txt` and `tls-t13.txt` show the same lines for the other configs).

### QG-T02 — PASS
`sslmode=require` → three rows; `\conninfo`: `SSL Connection true`, `TLSv1.3` (`tls-pg.txt`).

### QG-T03 — PASS
`verify-full` with `ca.pem`, host `localhost` → `3`.

### QG-T04 — PASS
`other-ca.pem` → `SSL error: certificate verify failed`, exit 2, before any password.

### QG-T05 — PASS
Host `127.0.0.1` → `server certificate for "localhost" (and 2 other names) does not match host name "127.0.0.1"`.

### QG-T06 — FAIL (PGTLSONLY-1)
`psql sslmode=disable` against the TLS-configured gateway → rows, `\conninfo` `SSL Connection false`.
Minimal reproduction, a startup packet with no `SSLRequest` (`tls-pg.txt`):

```text
server -> b'R' auth code 3 (3 = AuthenticationCleartextPassword)
server -> b'R' auth code 0 (0 = AuthenticationOk)
```

```python
s = socket.create_connection(("localhost", 36432))
p = b"user\0qa\0database\0pravaha\0\0"
s.sendall(struct.pack("!ii", 8 + len(p), 196608) + p)          # StartupMessage, no SSLRequest
# server: 'R' 3 -- AuthenticationCleartextPassword, over plaintext
s.sendall(b"p" + struct.pack("!i", 4 + len(tok) + 1) + tok + b"\0")
# server: 'R' 0 -- AuthenticationOk
```

The `pgwire` topic: "With the pair set, the gateway negotiates TLS on the same port and authenticates
**after** the handshake, so the token is always inside it"; `PgWireConnection.authenticate`'s javadoc:
"a configured certificate always covers the password". Neither holds for a client that never sends
`SSLRequest` — `sslmode=disable`, or Npgsql 4.0.17, whose default `SSL Mode` is `Disable`.
`PgWireConnection.handshake` returns the plaintext `Prelude` whenever the first packet is a startup.

### QG-T07 — PASS
psycopg `verify-full` → `[('AMER', 800000), ('EMEA', 900000)]`, `ssl_in_use` True; other CA →
`certificate verify failed` (`tls-py.txt`).

### QG-T08 — PASS
pgjdbc `sslmode=verify-full&sslrootcert=ca.pem`, `setLong` → `EMEA=900000 AMER=800000`; other CA →
`PKIX path building failed` (`tls-java.txt`).

### QG-T09 — PASS (incidental SDKCLOSE-1)
`grpc+tls` with `caCertificate(ca.pem)` → `3 rows; queries=2`. The first version of the probe read
`client.query(…).toList()` without closing the `QueryResult`; the client's `close()` at the end of the
try-with-resources then threw `java.lang.IllegalStateException: Memory was leaked by query. Memory
leaked: (128) … Allocator(flight-client) …` (`tls-java-unclosed-result.txt`), replacing the result of
the block. `PravahaFlightClient.close()` closes its subscriptions and swallows the transport's
`close()` failure ("Deliberately not rethrown") but not the allocator's.

### QG-T10 — PASS (TLSDIAG-1)
Wrong CA → `PravahaClientException PRV-1040 (CLIENT_CONNECT_FAILED) retryable=true PRV-1040 cannot
reach localhost:39090: io exception Channel Pipeline: [SslHandler#0, ProtocolNegotiators$ClientTlsHandler#0,
WriteBufferingAndExceptionHandler#0, DefaultChannelPipeline$TailContext#0]. Check that a Pravaha node is
running there and that the scheme matches`, in 0.0 s. Coded and prompt, as the case asks — but nothing
says "certificate", it is `retryable`, and the advice points at the wrong cause. The Python SDK on the
same node says `certificate verify failed: unable to get local issuer certificate`.

### QG-T11 — PASS
Python SDK: right CA → three rows; other CA and system trust → `ConnectError code=1040 … certificate
verify failed: unable to get local issuer certificate`, 0.0 s.

### QG-T12 — PASS
`grpc://` to the TLS port: Java `PRV-1040 … Network closed for unknown reason … the scheme matches
(grpc:// for plaintext, grpc+tls:// for TLS)`; Python `PRV-1040 … Socket closed`; both 0.0 s.

### QG-T13 — PASS
No pgwire pair (Flight still TLS): the node logs `-- NO TLS, the credential crosses the wire in the
clear`; `psql sslmode=require` → `server does not support SSL, but SSL was required` (`tls-t13.txt`).

### QG-T14 — PASS (CERTEXP-1)
Expired pair: the node starts and logs `over TLS`, nothing about the certificate's dates. psql
`verify-full` → `certificate verify failed`; pgjdbc → `(certificate_expired) … validity check failed`;
Python → `certificate verify failed: certificate has expired`; Java SDK → the same `PRV-1040 … io
exception Channel Pipeline` as T10 (TLSDIAG-1). `sslmode=require` connects (it verifies nothing, as
the `tls` topic warns).

### QG-T15 — PASS
`PRV-6206 /opt/pravaha/pki/server-key-pkcs1.pem is '-----BEGIN RSA PRIVATE KEY-----', not PKCS#8 …
convert with openssl pkcs8 -topk8 -nocrypt …`, exit 1 (`tls-t15.txt`).

### QG-T16 — NOTE (MTLSDOC-1)
A client certificate from the CA and one from an unrelated CA are both accepted, by both SDKs, and
the query answers: the node never sends a `CertificateRequest` and has no setting to. That is
consistent with SECURITY.md ("mTLS between nodes … not implemented") but the `tls` topic's SDK table
offers `client_certificate`, `client_key` "for mutual TLS" with no word that a Pravaha node ignores
it — an operator reading it can believe the node authenticates clients by certificate.

### QG-T17 — PASS
Wrong token over TLS: Java `PRV-7001 (SERVER_REPORTED) retryable=false the credential presented was
not accepted`; Python `PRV-1041 PRV-7001 …`.

---

## N — Npgsql

### QG-N01 — PASS
Repository probe, Npgsql 4.0.17, `SSL Mode=Disable`: `OK open: server=9.4.26` (`npgsql.txt`).

### QG-N02 — PASS
`getschema-tables` `public.sales:BASE TABLE,public.t_dec:BASE TABLE`; `getschema-columns`
`region:text:NO:1,revenue:int8:NO:2`; every `pbi-*` query OK; the probe's expected refusals
(top-N `PRV-2020`, `INSERT` `PRV-2020`) refused; `pooled-reopen` OK.

### QG-N03 — PASS
`int4`, `int8` and `int2` parameters against the BIGINT column all answer (`int4-param-vs-bigint: 2
rows`), on 4.0.17 and 8.0.5 — PGINTPARAM-1 holds.

### QG-N04 — PASS
`BeginTransaction`/`Commit`/`Rollback` accepted; an error inside a transaction → `42000`, the next
statement `25P02`, after `Rollback` the connection answers again.

### QG-N05 — FAIL (DECPARAM-1)
DECIMAL reads are exact: `price=19.99(Decimal/numeric(22, 2))`, `0.01`, `12345678.90`;
`SUM(price)` → `12345698.90 (numeric(38, 2))`. A `decimal` parameter, `WHERE price > @p` or `= @p`, →
`42000 PRV-2021 expression '?0' is a RexDynamicParam, which Pravaha cannot evaluate yet. Supported:
column references, numeric literals, and + - * / % over integers and floating point.` Not Npgsql's
doing (`decparam.txt`): psycopg text and binary `Decimal`, psycopg `int`, and the Python SDK over
Flight with `Decimal` or `int` — every parameter compared with a DECIMAL column is refused the same
way; the literal `price > 19.98` answers `[(1,), (3,)]`. CONTINUOUS_QUERIES §9: "`?` placeholders are
supported in `WHERE` and `HAVING`", no type excluded. Minimal reproduction: a view with a DECIMAL
column `price`, then `SELECT id FROM v WHERE price > ?` with any value.

### QG-N06 — PASS
4.0.17 `SSL Mode=Require;Trust Server Certificate=true` → `OK open … sslmode=require`; 8.0.5
`SSL Mode=VerifyFull;Root Certificate=/pki/ca.pem` (with `NoTypeLoading`, see N07) → every check but
the DECIMAL parameter OK (`npgsql8.txt`).

### QG-N07 — NOTE (NPGSQLNEW-1)
Npgsql 8.0.5 with defaults: `FAIL open: 0A000 PRV-6201 this Query message carries 4 statements; this
gateway runs one at a time` (`npgsql.txt`). The `power-bi` topic says newer versions' type loading "the
gateway does not recognise (PRV-6205)" — the code is PRV-6201. With `Server Compatibility
Mode=NoTypeLoading` Npgsql 8 opens and works (N03–N06), which nothing documents. Power BI pins 4.0.17,
so this is a NOTE for every other .NET application.

---

## S — Aerospike and Cassandra under failure

### QG-S01 — PASS
50 records → `{'n': 50, 's': 12750}`, feed RUNNING (`aerospike.txt`).

### QG-S02 — PASS
`docker stop` → within ~10 s: query `RUNNING`, feed `STOPPED`, `PRV-5081 scan of test.orders
partitions [0, 4096) failed, and nothing from it was emitted: … Client timeout`; ERROR line "will not
retry; the view keeps answering at the frontier it reached"; the view still `50 / 12750`.

### QG-S03 — PASS
Store back and 10 more records: the feed stays `STOPPED` (TROUBLESHOOTING: "not retried … drop and
register the query again"). Drop and register → `{'n': 60, 's': 18300}` — no double counting.

### QG-S04 — PASS
`truncate test.orders` → `{'n': 0, 's': None}` within one interval (`deletes: detect`).

### QG-S05 — PASS (NOTE)
`user`/`password` against Community Edition → registration refused `PRV-5091 … PRV-5080 plugin
's_aero_auth' cannot reach Aerospike at qg-aerospike:3000: Error -8: … Error 51: Login failed. Check
the host list, that the cluster is up …`. Coded and not silent; the words "cannot reach" and the
advice about host lists are wrong for a login failure.

### QG-S06 — PASS
`hosts: qg-nowhere:3000` → `PRV-5080 … Invalid host: qg-nowhere 3000`, at registration.

### QG-S07 — FAIL (CASSDC-1), then PASS with `local.datacenter`
Without `local.datacenter` (documented optional: "a single-datacenter cluster is detected from the
contact points"): `PRV-5091 the 'cassandra' plugin could not be opened for stream 's_cass':
java.lang.IllegalStateException: Since you provided explicit contact points, the local DC must be
explicitly set …` — for every Cassandra source (`cassandra-no-local-dc.txt`). The plugin passes
`addContactPoints` and only calls `withLocalDatacenter` when the option is set; the 4.x driver infers
a local DC only for its implicit default contact point. With `local.datacenter: datacenter1`: 40 rows,
`{'n': 40, 's': 8200}`, the same 6 s later (three passes, no repeats).

### QG-S08 — PASS
Stop → feed `STOPPED`, `PRV-5086 scan of token range […] failed: No node was available`, ERROR line;
view kept `40 / 8200`. Back plus 5 rows → still stopped (documented); drop and register →
`{'n': 45, 's': 10350}`.

### QG-S09 — PASS
`DROP TABLE` → feed `STOPPED`, `PRV-5086 … table orders does not exist`; view kept at its frontier.

### QG-S10 — PASS (NOTE)
Wrong password → `PRV-5085 source 's_cass_badpw' cannot reach Cassandra … AuthenticationException …`;
the password appears in no log line (`grep -c` 0). "cannot reach" again for an authentication failure.

### QG-S11 — PASS (NOTE)
`keyspace: nope` → `PRV-5091 … com.datastax.oss.driver.api.core.InvalidKeyspaceException: Invalid
keyspace nope`: coded and named, through the generic code rather than the plugin's own.

---

## K — Avro and Protobuf drift

### QG-K01 — PASS
Schema id 1, five records → ids 1–5, amounts 100–500 (`kafka-k01-k02.txt`).

### QG-K02 — PASS (NOTE)
Schema id 2 (`amount` string) → three dead letters `t_reg/0@5..7`: `schema id 2 cannot be read into
this stream's columns: Avro field 'amount' is string and column 'amount' is
PrimitiveType[typeName=INT64, nullable=false] …`; the two later v1 records and a compatible v5 (extra
optional field) record arrive. NOTE: the reason prints a Java record's `toString`, and the entries'
`code` is empty.

### QG-K03 — PASS
Registry stopped, unseen id 9999 → feed `STOPPED`, `PRV-5109 … could not be read after 3 attempts:
java.net.ConnectException … the reader stops rather than setting good records aside` — no dead letter.
Registry back: stays stopped (documented). NOTE: the message reads `PRV-5109  PRV-5109  PRV-5109`.

### QG-K04 — NOTE
`schema.file` v1, writer adds `note` mid-record → three dead letters `the record leaves 6/7/8 byte(s)
of the value unread` (good). Writer swaps the order of two `long`s → read silently as `(700, 7)` and
`(800, 8)`. Inherent to a schema-less Avro value (no names on the wire); it is what a registry is for,
and the topic says fields are read "in written order with no names". Recorded, not filed.

### QG-K05 — NOTE (PBDRIFT-1)
Descriptor `id int64 = 1; amount int64 = 2`; one record sends field 2 as a length-delimited string
`"300"` → the row `(3, 0)`, no dead letter (`kafka-k04-k06.txt`). protobuf-java parks a field of the
wrong wire type among the unknown fields, and the proto3 rule then fills the column with 0 — the
documented default rule is about an *absent* field, and this one was present. A `SUM` is short by 300
under a success status.

### QG-K06 — PASS
Framed record with `schema.file` → dead letter `… The value begins with 0x00 and the four-byte schema
id 1, which is the schema registry's wire format …` naming `schema.registry.url`.

---

## D — A full disk

### QG-D01 — PASS
Starts on the 16 MiB tmpfs; `registry.journal`, `checkpoints/`, `dlq/`, `spill/` under `data/`.
Warnings every 10 s `could not set rwx------ on /opt/pravaha/data … Operation not permitted` are the
tmpfs root being root's — harmless (`disk-d01.txt`).

### QG-D02 — PASS (but see D05)
With the journal at 4032 bytes and the volume full, registering `q43` → `PRV-8006 cannot append to the
registry journal … refused now rather than acknowledged and forgotten`; `q43` not registered; the 41
others and `v1` unaffected. The journal is now 4096 bytes: 64 bytes of `q43`'s record stayed
(`disk-d05.txt`, `disk-d02b.txt`).

### QG-D03 — FAIL (CKPTWHY-1)
Volume full, rows arriving, checkpoints due every 2 s: reads keep answering (`v1` 100 → 200 → 210
rows); the newest two checkpoints stay intact (`checkpoint-6.bin`, `-7.bin`, 5240 bytes each) — no
truncated checkpoint left as latest. But `pravaha_query_checkpoint_failures_total` rose to 13 for
each query while the log has **no** line about a failed checkpoint (only the ownership-marker WARN,
`PRV-4004 … No space left on device`, every 10 s), `GET /api/v1/queries/v1` has no checkpoint field,
`/status` and health say nothing. `QueryCheckpoints.start` wires the per-failure narrative to
`message -> {}`, and `RegisteredQuery.lastCheckpointFailure()` has no reader in the product, so the
reason (`No space left on device`) is nowhere. `errors-state` says of PRV-4095 "the reason is its last
checkpoint failure" as though one could see it.

### QG-D04 — FAIL (DLQFULL-1)
Volume full; five unparseable lines appended to the file both `v1` and `v2` read; then ten good ones.
The good rows arrive in both views. `v2`'s dead letters: 5 (its write found pages that checkpoint
pruning had just freed). `v1`'s: **0** — `v1.dlq` is 0 bytes, `pravaha_query_dead_letters{query="v1"}`
0, `dead-letters/count` `total: 0`, no log line, feed `RUNNING` (`disk-d02-d04.txt`). On the same node
after the ballast was removed, three more undecodable lines reached every query's queue — `v1 3, v2 8,
q3 3, q41 3` (quoted from the session; that run's transcript file was overwritten by the later D05
run) — so the loss is the write failing, not the shared feed. `FileDeadLetterQueue.accept` catches the `IOException`
and does `failures++`; in the node nothing reads `failures()` (only the CLI's `QueryRunner` does). The
`dead-letters` topic: "**never drop a record silently**". Minimal reproduction: a node with
`pravaha.dlq.directory` on a full filesystem and a file source fed one undecodable line — the line is
in no queue, no count and no log, and the source reads on.

### QG-D05 — FAIL → FIXED (DISKJOURNAL-1)
After D02, the ballast removed: `q44` registers and is acknowledged (journal 4195 bytes). The data is
copied out (`tar` through `exec`) onto a bind mount and a new container started on it:

```text
PRV-8005  record 42 of the registry journal at /opt/pravaha/data/registry.journal (byte offset 4032)
cannot be decoded. Earlier records are fine; this one is not, and replaying past it would silently
drop whatever it said
```

— the node will not start. `od -c` shows `q43`'s first 64 bytes, then `q44`'s record. `RegistryJournal`
cuts a torn tail once per process (`cutTornTail`, guarded by `tailChecked`), before the first append;
a later append that fails part way leaves its bytes, and the next append lands behind them. Without
the full disk the same restart recovered `42 of 42 queries` (first D05 run). **Fixed** in this branch
(one line: a failed append clears `tailChecked`), with
`RegistryJournalTest#anAppendAfterAFailedOneInTheSameProcessLandsOnARecordBoundary`, seed-proven (fails
with the same PRV-8005 without the fix).

### QG-D06 — NOT RUN
See "Not covered".

---

## Suggested register text

### DLQFULL-1 (HIGH) — a dead letter that cannot be written is dropped with nothing said

> **Status:** OPEN — `FileDeadLetterQueue.accept` catches the `IOException` of a write and only counts it (`failures++`); in the node nothing reads that count, nothing is logged, the dead-letter metrics do not move, and the source reads on. On a full disk (ADV-GAPS QG-D04, a 16 MiB tmpfs) five undecodable lines reached one query's queue and not the other's: `v1.dlq` 0 bytes, `pravaha_query_dead_letters{query="v1"}` 0, feed RUNNING. Repro: `pravaha.dlq.directory` on a full filesystem, one undecodable line into a file source. Fix direction: a write that fails stops the feed with a code (as with no queue configured, which "fails loudly"), or at least logs ERROR and counts it in a metric and on the query.
> **Disposition:** GA-REQUIRED — records lost without a refusal, at exactly the moment (a full disk) the queue exists for.

### DISKJOURNAL-1 (HIGH) — after a full disk refuses one registration, the next one makes the node refuse to start

> **Status:** FIXED — `77d6733f`: a journal append that a full disk fails part way leaves the bytes that fitted; `RegistryJournal` checked for a torn tail only before the first append of the process, so once the disk freed the next registration was acknowledged behind the torn bytes, and the next start refused with `PRV-8005` (damage in the middle) — the node would not come up until someone cut the journal by hand. A failed append now re-arms the tail check, so the next append cuts the torn bytes first. Found in ADV-GAPS QG-D05 on a 16 MiB tmpfs; `RegistryJournalTest#anAppendAfterAFailedOneInTheSameProcessLandsOnARecordBoundary`, seed-proven. The alerts and identity journals append the same way and were not checked.
> **Disposition:** GA-REQUIRED — a transient full disk turned into a node that does not start.

### PGTLSONLY-1 (MEDIUM) — a TLS-configured PostgreSQL gateway still accepts plaintext and asks for the token in clear

> **Status:** OPEN — with `pravaha.pgwire.tls.*` set, a client that sends a startup packet without `SSLRequest` (`sslmode=disable`; Npgsql 4.0.17's default) is answered `AuthenticationCleartextPassword` over plaintext and signed in. The `pgwire` topic says "authenticates **after** the handshake, so the token is always inside it" and `PgWireConnection.authenticate` "a configured certificate always covers the password". Repro: ADV-GAPS QG-T06 (raw startup → `R 3`, token → `R 0`). Fix direction: when a certificate is configured, refuse a startup that did not come through `SSLRequest` (`28000`, naming `sslmode=verify-full`) — PostgreSQL's `hostssl` — with an explicit opt-out if plaintext must stay possible.
> **Disposition:** GA-REQUIRED — a documented credential protection that a client can switch off without the server's say.

### DECPARAM-1 (MEDIUM) — a parameter compared with a DECIMAL column is refused on every transport

> **Status:** OPEN — `SELECT id FROM v WHERE price > ?` (price DECIMAL) is `PRV-2021 expression '?0' is a RexDynamicParam, which Pravaha cannot evaluate yet` for a `Decimal` or an integer value, over Flight (Python SDK), pgwire text and binary (psycopg) and Npgsql; the literal answers. CONTINUOUS_QUERIES §9 says parameters are supported in `WHERE`, with no type excluded, and the message names a Calcite class. Repro: ADV-GAPS QG-N05, `decparam.txt`.
> **Disposition:** POST-GA — refused with a code, never a wrong answer; but parameterised filters on money columns are the common case for BI tools and prepared statements.

### CASSDC-1 (MEDIUM) — `local.datacenter` is documented as auto-detected and is required

> **Status:** OPEN — `source-cassandra` and the plugin javadoc say a single-datacenter cluster's `local.datacenter` "is detected from the contact points"; the plugin builds the session with explicit contact points and no local DC, and the 4.x driver refuses that: every Cassandra registration without the option fails `PRV-5091 … java.lang.IllegalStateException: Since you provided explicit contact points, the local DC must be explicitly set`. The topic's own examples set it, which is why nothing caught it. Repro: ADV-GAPS QG-S07. Fix direction: configure the driver's `DcInferringLoadBalancingPolicy` when the option is blank (or make it required and say so), and turn the raw exception into `PRV-5088`.
> **Disposition:** POST-GA — fails at registration with a message that names the fix.

### CKPTWHY-1 (LOW) — why a checkpoint failed is recorded and shown nowhere

> **Status:** OPEN — on a full disk `pravaha_query_checkpoint_failures_total` rises, but no log line says a checkpoint failed or why: `QueryCheckpoints.start` hands the checkpointer's narrative (one line per failure) to `message -> {}`, and `RegisteredQuery.lastCheckpointFailure()` has no reader. `errors-state` (PRV-4095) says "the reason is its last checkpoint failure" as if it were visible. Repro: ADV-GAPS QG-D03.
> **Disposition:** POST-GA — the failure is counted and alerted on; only its cause is missing.

### TLSDIAG-1 (LOW) — the Java SDK reports a certificate it does not trust as a node it cannot reach

> **Status:** OPEN — an untrusted or expired server certificate is `PRV-1040 CLIENT_CONNECT_FAILED`, `retryable=true`, "cannot reach host:port: io exception Channel Pipeline: [SslHandler#0, …]. Check that a Pravaha node is running there and that the scheme matches" — nothing about the certificate. The Python SDK, psql and pgjdbc all say "certificate verify failed"/"certificate has expired". Repro: ADV-GAPS QG-T10, QG-T14.
> **Disposition:** POST-GA — diagnosability.

### SDKCLOSE-1 (LOW) — closing the Java client with an unclosed `QueryResult` throws an uncoded `IllegalStateException`

> **Status:** OPEN — `client.query(sql).toList()` without closing the result, then `client.close()` → `IllegalStateException: Memory was leaked by query … Allocator(flight-client)` from the allocator, replacing whatever the try block returned or threw. `close()` already closes subscriptions and swallows the transport's failure; open results are neither tracked nor closed. Repro: ADV-GAPS QG-T09 (`tls-java-unclosed-result.txt`).
> **Disposition:** POST-GA — the documented pattern closes the result; the failure is the client's, loud, and late.

### MTLSDOC-1 (LOW) — the SDKs offer "mutual TLS" to a node that never asks for a certificate

> **Status:** OPEN — the node's Flight and pgwire listeners never request a client certificate (no setting exists), so a client certificate from any CA, or none, is accepted alike; the `tls` topic's SDK table lists `client_certificate`/`client_key` "for mutual TLS" without saying it only matters to a terminator in front of the node. Repro: ADV-GAPS QG-T16.
> **Disposition:** POST-GA — documentation; server-side mTLS is a feature not built.

### NPGSQLNEW-1 (LOW) — Npgsql after 4.x fails to open with PRV-6201, and the workaround is undocumented

> **Status:** OPEN — Npgsql 8.0.5 sends its type loading as one four-statement Query and gets `PRV-6201`; the `power-bi` topic says such versions get `PRV-6205`. With `Server Compatibility Mode=NoTypeLoading` Npgsql 8 opens and every read works. Repro: ADV-GAPS QG-N07.
> **Disposition:** POST-GA — Power BI pins 4.0.17; document the code and the connection-string setting for other .NET clients.

### PBDRIFT-1 (LOW) — a protobuf field of the wrong wire type reads as zero

> **Status:** OPEN — a record whose field 2 (declared `int64`) arrives length-delimited is read with that column at its default (0), not dead-lettered: `DynamicMessage` keeps the field among the unknown fields and the proto3 default rule fills the column. Repro: ADV-GAPS QG-K05. Fix direction: a declared field number present in the unknown fields is a dead letter naming the field and both wire types.
> **Disposition:** POST-GA — a producer-side schema break, but answered with a wrong number under a success status.

### CERTEXP-1 (LOW) — a node starts on an expired certificate without a word

> **Status:** OPEN — with an expired pair the node logs `over TLS`/`flight transport=TLS` and every verifying client then fails its handshake. The pair is checked at startup (SX-17); its validity dates are not. Repro: ADV-GAPS QG-T14. Fix direction: WARN at startup (and a metric) when the leaf is expired or expires within N days.
> **Disposition:** POST-GA.
