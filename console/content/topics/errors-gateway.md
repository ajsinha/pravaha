---
title: Gateway codes (PRV-6xxx)
slug: errors-gateway
category: errors
order: 70
icon: hdd-network
summary: "PRV-6100 to PRV-6211: what the Flight SQL and PostgreSQL gateways refuse — types they will not put on the wire, requests they do not implement, TLS they cannot load, and writes to a read-only gateway."
badge: PRV-6XXX
audience: Developers, operators
keywords: [flight, arrow, grpc, pgwire, postgresql, psql, jdbc, sqlstate, set, pg_catalog, prepared statement, portal, binary format, $1, read-only, tls, certificate]
guide: continuous-queries#16-types
related: [pgwire, client-snippets, tls, errors-overview, sql-types]
---

A **gateway** is how a client reaches the engine. There are two, and each has a block in this range:

- **61nn — Arrow Flight SQL**, on port 19090. Every SDK, the CLI, this console, and any JDBC/ADBC
  Flight SQL driver speak it. It is the full surface: queries, registration, subscriptions.
- **62nn — the PostgreSQL wire protocol**, on port 5432, **off by default**
  (`pravaha.pgwire.enabled`). It lets `psql`, DBeaver, Grafana and any ORM **read** a maintained view
  with no Flight driver. It is a transport and nothing more: the caller reaches the same authorization
  and the same audit trail as over Flight, so it is not a second, weaker route to the data.

(The source calls 6000–6999 the "Flight" category because Flight was once the only gateway; read it
as "gateway".)

| Code | Name | Gateway | SQLSTATE |
|---|---|---|---|
| PRV-6100 | FLIGHT_UNSUPPORTED_TYPE | Flight | — |
| PRV-6101 | FLIGHT_UNSUPPORTED_REQUEST | Flight | — |
| PRV-6102 | FLIGHT_BAD_HANDLE | Flight | — |
| PRV-6103 | FLIGHT_PARAMETERS_TOO_LARGE | Flight | — |
| PRV-6104 | FLIGHT_TLS_UNREADABLE | Flight | (startup) |
| PRV-6105 | FLIGHT_SUBSCRIBER_BEHIND | Flight | — |
| PRV-6200 | PGWIRE_UNSUPPORTED_TYPE | PostgreSQL | `0A000` |
| PRV-6201 | PGWIRE_UNSUPPORTED_REQUEST | PostgreSQL | `0A000` |
| PRV-6202 | PGWIRE_PROTOCOL_VIOLATION | PostgreSQL | `08P01` |
| PRV-6203 | PGWIRE_UNSUPPORTED_PROTOCOL | PostgreSQL | `0A000` |
| PRV-6204 | PGWIRE_UNSUPPORTED_SET | PostgreSQL | `0A000` |
| PRV-6205 | PGWIRE_UNSUPPORTED_CATALOG_QUERY | PostgreSQL | `0A000` |
| PRV-6206 | PGWIRE_TLS_UNREADABLE | PostgreSQL | `08000` (startup) |
| PRV-6207 | PGWIRE_UNKNOWN_STATEMENT | PostgreSQL | `26000` |
| PRV-6208 | PGWIRE_UNKNOWN_PORTAL | PostgreSQL | `34000` |
| PRV-6209 | PGWIRE_UNSUPPORTED_WIRE_FORMAT | PostgreSQL | `0A000` |
| PRV-6210 | PGWIRE_UNSUPPORTED_PARAMETER_SYNTAX | PostgreSQL | `0A000` |
| PRV-6211 | PGWIRE_READ_ONLY | PostgreSQL | `25006` |

## Arrow Flight SQL

### PRV-6100 — Flight unsupported type

A column of a type this gateway will not put on the wire. **`DECIMAL`** in particular is refused
rather than sent as a floating-point number, because the rounding decision belongs to whoever owns the
ledger and not to a serialiser. Every other supported type — including `BYTES` and `TIME`, since
TY-17 and TY-18 were fixed — is carried. Keep money in integer minor units (`amount_cents INT64`), or
cast in the source database.

### PRV-6101 — Flight unsupported request

A Flight SQL request or action this server does not implement — for example an action name it does
not know, or registering, dropping or subscribing on a Flight server that serves views but hosts no
registry. The message names the request.

### PRV-6102 — Flight bad handle

A prepared-statement handle or control request this server cannot read — from an older server
version, or corrupted in transit. It arrives as `NOT_FOUND`, the conventional prompt for a client to
**prepare the statement again**, which is the fix. (Declared once and shared by both ends of the
wire, as `ControlWire.BAD_REQUEST`.)

### PRV-6103 — Flight parameters too large

The bound parameters of a prepared statement exceed **1 MiB**. A handle travels on every call that
uses it, so a large binding is paid for repeatedly. Send bulk values as data — a stream or a lookup
table — rather than as parameters, or narrow the statement.

### PRV-6104 — Flight TLS unreadable

`pravaha.flight.tls.certificate` or `pravaha.flight.tls.key` is set and cannot be read, only one of
the pair is set, or **the two are not a pair**. **The node refuses to start** rather than warning
and serving plaintext: falling back to plaintext because a certificate was missing is how a
deployment believes it is encrypted for months (CFG-6). Both are PEM files, and the key is
unencrypted PKCS#8 (`-----BEGIN PRIVATE KEY-----`); see [TLS everywhere](/help/topics/tls).

**The pair is checked at startup** (SX-17). A certificate and a key that are each individually
valid and do not belong together used to start a healthy-looking node — the startup summary said
`transport=TLS` — and every client then failed its handshake with `tlsv1 alert internal error`, on
the client, with nothing in the server's log. The check signs a nonce with the key and verifies it
with the certificate's public key, which is the same question TLS itself asks a moment later. A pair
given the wrong way round — the certificate in `key` and the key in `certificate` — used to escape
as a raw Java exception with no code at all, and now arrives here.

```yaml
pravaha:
  flight:
    tls:
      certificate: /opt/pravaha/conf/tls/server.crt
      key: /opt/pravaha/conf/tls/server.key
```

### PRV-6105 — Flight subscriber behind

A **snapshot subscription** — `subscribeFromSnapshot` in Java, `subscribe(..., snapshot=True)` in
Python, `pravaha subscribe --snapshot` — fell more than 64 commits behind the view, and the server
ended its stream. A plain subscription drops batches in that position and carries on; a snapshot
subscription promises the view and then *every* commit after it, so writing past one it would never
receive would leave the client's copy silently wrong. Nothing is lost for good: **subscribe again**,
and the new stream starts from a fresh snapshot. If it keeps happening, the consumer is doing too much
in its callback — hand each batch to a queue of your own and return. Sent as `RESOURCE_EXHAUSTED`,
which retrying clients already treat as retryable. See
[Subscriptions](/help/topics/subscriptions) for the two kinds of subscription.

## The PostgreSQL gateway

Enable it with `pravaha.pgwire.enabled` and give it TLS with `pravaha.pgwire.tls.certificate` and
`pravaha.pgwire.tls.key`; `psql`'s `sslmode=require` then negotiates on the same port. The gateway is
**read-only** and **text-format only**, and it answers a named set of catalog queries — enough for
`psql` and JDBC drivers — rather than guessing. See [The PostgreSQL gateway](/help/topics/pgwire).

### PRV-6200 — pgwire unsupported type

A column type with no PostgreSQL text encoding in this gateway. **`BYTES` and `TIME`** are refused by
name here even though Flight now carries both — a gap in this gateway's type mapping, not in the
engine — and so are `ARRAY`, `MAP` and `ROW`, whose `toString` would arrive looking like data. The
refusal comes **before** the `RowDescription`, so a client gets a clean error instead of a truncated
result it might treat as complete. Read such a view over Flight, or project the column away.

### PRV-6201 — pgwire unsupported request

A protocol message the gateway does not implement — `COPY`, for example. Refused by name.

### PRV-6202 — pgwire protocol violation

Bytes arrived that are not a well-formed message — including a length field that is absurd. A frontend
message says how long it is before anything else, so a hostile or confused client could ask the server
to allocate whatever it likes; lengths are bounded and a violation refused. Usually a client that is
not actually speaking PostgreSQL (an HTTP probe on 5432, say).

### PRV-6203 — pgwire unsupported protocol

A startup packet asking for a protocol version this server does not speak. Current `psql` and JDBC
drivers speak protocol 3.0, which is what the gateway implements.

### PRV-6204 — pgwire unsupported SET

A `SET` naming a parameter this gateway does not accept. `SET` is valid PostgreSQL, understood here and
refused by name — rather than handed to a SQL planner that would call it a syntax error. The
allow-list is short on purpose, and each entry is one whose value is already true of this gateway, so
accepting it is a no-op rather than a lie:

| Accepted | With |
|---|---|
| `extra_float_digits` | any value |
| `application_name` | any value |
| `client_min_messages` | any value |
| `client_encoding` | `UTF8` or `UNICODE` only |
| `DateStyle` | `ISO` styles only |

Anything else — `SET search_path`, `SET TIME ZONE` — is PRV-6204. Most tools send only these at
connect; if one sends another, configure it not to.

### PRV-6205 — pgwire unsupported catalog query

A `pg_catalog` query the gateway recognises as catalog introspection and cannot answer. It answers a
small, named set of query shapes — the ones `psql` (at the server version it announces) and JDBC
drivers actually send — and refuses the rest rather than guessing at a join it has never modelled. A
wrong catalog answer is worse than none: `psql` would print it as fact. A GUI's schema browser may hit
this; the query editor still works.

### PRV-6206 — pgwire TLS unreadable

A configured TLS certificate or private key the gateway cannot use — one of the pair without the
other, an unreadable file, or a PEM key shape it cannot parse (PKCS#1 versus PKCS#8). Refused at
startup, never silently served in plaintext.

### PRV-6207 — pgwire unknown statement

`Describe` or `Execute` named a prepared statement this connection never parsed, or already closed.
SQLSTATE `26000`, which is what a driver's recovery path is written against — distinct from the portal
code below, because a driver that gets the wrong one debugs the wrong half of its own state machine.

### PRV-6208 — pgwire unknown portal

`Describe` or `Execute` named a portal this connection never bound, already closed, or already ran to
completion. SQLSTATE `34000`.

### PRV-6209 — pgwire unsupported wire format

A `Bind` parameter, or a requested result column, in **binary** format. The gateway is text-format
only, in both directions; a driver that asks for binary is refused by name rather than handed bytes
decoded as though they were text, which is how a number becomes garbage instead of an error. Most
drivers can be told to use text (for the PostgreSQL JDBC driver, set `binaryTransfer=false` in the
connection URL).

### PRV-6210 — pgwire unsupported parameter syntax

PostgreSQL writes parameters as `$1`, `$2`; Pravaha's dialect uses a positional `?` (ADR-032). The
gateway rewrites one into the other, and the rewrite is text substitution, sound only when every `$n`
is used **exactly once, in order, starting at `$1`** — which is always true of a driver-generated
statement. A statement that reuses a `$n` or skips one is refused rather than silently rewritten into a
different statement that happens to parse.

```text
-- accepted: each placeholder once, in order
SELECT user_id, spend FROM hourly_spend WHERE user_id = $1 AND spend > $2
-- PRV-6210: $1 used twice
SELECT user_id, spend FROM hourly_spend WHERE window_start <= $1 AND window_end > $1
```

### PRV-6211 — pgwire read-only

A `CREATE`, `DROP`, `PAUSE` or `RESUME CONTINUOUS QUERY`, or `SHOW CONTINUOUS QUERIES`, sent to the
PostgreSQL gateway. It answers questions over views and hosts no registry, so it can neither stand a
computation up nor list them. Refused by name with SQLSTATE `25006 read_only_sql_transaction` —
PostgreSQL's own word for "this session does not write".

```text
$ psql "host=localhost port=5432 user=ann sslmode=require"
ann=> SHOW CONTINUOUS QUERIES;
ERROR:  PRV-6211  the PostgreSQL gateway is read-only: it does not register, drop, pause, resume or
list continuous queries. Send the statement over Flight SQL instead -- an SDK's query(), `pravaha
query --sql`, or the console's workbench -- where it runs as your principal.
```

**Do:** send it over Flight SQL — an SDK's `query()`, `pravaha query --sql`, or the workbench.

## Where next

- [The PostgreSQL gateway](/help/topics/pgwire) — enabling it, what it answers, `psql` and Grafana
- [Client code](/help/topics/client-snippets) — Flight SQL from every language
- [TLS everywhere](/help/topics/tls)
