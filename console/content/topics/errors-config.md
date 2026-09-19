---
title: Configuration, API and client codes (PRV-1xxx)
slug: errors-config
category: errors
order: 20
icon: sliders
summary: "PRV-1001 to PRV-1051: a configuration value that cannot be read, a request the REST API cannot accept, and every refusal the Java and Python SDKs raise before or while talking to a node."
badge: PRV-1XXX
audience: Operators, developers
keywords: [configuration, duration, data size, enum, reference, placeholder, endpoint, client options, tls options, connect failed, missing field, invalid parameter, sdk]
guide: troubleshooting#every-code
related: [errors-overview, configuration, client-snippets, sdk-reference, http-api]
---

The 1xxx range is everything that goes wrong **before any SQL is planned**: a configuration value the
engine cannot read, a REST request missing a field it needs, a client SDK given an endpoint or an
option that makes no sense, or a client that cannot reach the node at all. The common thread is that
nothing about a query is wrong — the fix is in a file, a URL or a call.

They are grouped here by who raises them:

| Codes | Raised by | When |
|---|---|---|
| PRV-1001 – PRV-1026 | The engine's configuration library (`pravaha-common`), which the embedded engine and plugin options are read through | When a configuration is built — at start, not at first use |
| PRV-1030 – PRV-1044 | The Java and Python SDKs | Constructing a client, or talking to the node |
| PRV-1050, PRV-1051 | The REST API itself | A request whose body or parameters cannot be read |

!!! note "A server's application.yaml is bound by Spring Boot"
    The server reads `application.yaml` through Spring Boot's binder, which reports a value it cannot
    convert in its own words at startup. The codes below are the engine's own configuration library:
    what an **embedded** engine, a plugin's options and the CLI's configuration are read through. The
    meaning is the same either way — a value that is not what the key needs — and so is the rule:
    **values are resolved and validated when the configuration is built, not when a key is first
    read.** A node that started and then failed on its first cache miss because a size said
    "sixteen" is precisely the failure this avoids.

## Reading a configuration file

### PRV-1001 — CONFIG_FILE_UNREADABLE

A configuration file that was named as **required** does not exist or cannot be read (permissions, a
directory where a file was expected). An *optional* file — the git-ignored `.local` overlay, for
instance — may be absent without this code; a required one may not, because an engine that starts
without the file it was told to read is running on defaults nobody chose.

**Do:** check the path in the message, and that the process user can read it.

### PRV-1002 — CONFIG_FILE_MALFORMED

The file was read and is not valid in its format: a properties line that does not parse, YAML that
does not load, or an unclosed `${` placeholder. The message names the file and the line (or the key
and its origin) so the fix is one edit.

```text
PRV-1002  unclosed '${' in 'pravaha.checkpoint.directory' (<source>: <position>): ${STATE_DIR
```

(`<source>` and `<position>` are the file, or the environment or command line, and where in it the
value was set.)

## References between values

A value may refer to another with `${key}` or `${key:default}` — so a directory can be written once
and reused. Two codes guard that mechanism.

### PRV-1010 — CONFIG_UNRESOLVED_REFERENCE

A value refers to `${some.key}` and that key is set nowhere — not in a file, not in the environment,
not on the command line — and the reference gives no default. Refused rather than substituted with an
empty string: an empty checkpoint directory is a node that silently checkpoints nowhere.

```text
PRV-1010  'pravaha.registry.journal' (<source>: <position>) refers to '${STATE_DIR}', which is not
set anywhere and has no default. Set it, or write ${STATE_DIR:some-default}.
```

**Do:** set the referenced key, or give the reference a default with `${key:default}`.

### PRV-1011 — CONFIG_CIRCULAR_REFERENCE

Two or more values refer to each other (`a -> b -> a`), or references nest deeper than the resolver
allows. The message prints the chain it followed.

**Do:** break the loop — one of the keys has to hold a literal value.

## Values that are not what the key needs

Each of these names the key, the value it found (secrets are masked — a password is never echoed into
a log), where the value came from, and what would have been accepted.

### PRV-1020 — CONFIG_MISSING_REQUIRED

A key the component cannot work without is not set. `required configuration key '…' is not set`.

### PRV-1021 — CONFIG_NOT_A_NUMBER

A number was expected. Whole numbers may use underscores for readability (`1_000_000`); decimals use a
point or an exponent (`0.8`, `1e6`).

### PRV-1022 — CONFIG_NOT_A_BOOLEAN

Accepted spellings are `true`/`false`, `yes`/`no`, `on`/`off` and `1`/`0`, in any case. Anything else
— `enabled`, `y` — is refused rather than guessed.

### PRV-1023 — CONFIG_NOT_A_DURATION

A duration is a number **with a unit**: `ns`, `us`, `ms`, `s`, `min` (or `m`), `h`, `d` — `200us`,
`30s`, `5min`. A bare `30` is refused: thirty what? A unit-less duration is how a timeout meant as
seconds becomes thirty milliseconds.

### PRV-1024 — CONFIG_NOT_A_DATA_SIZE

A size is a number with an optional binary unit: `B`, `KB`, `MB`, `GB`, `TB` (each a power of 1024;
`KiB`-style spellings are accepted too). `4MB` is 4,194,304 bytes.

### PRV-1025 — CONFIG_NOT_AN_ENUM

The value is not one of the names the key accepts. The message lists them — for example a wait
strategy must be one of `BUSY_SPIN`, `SPIN_THEN_YIELD`, `BACKOFF_PARK`, `BLOCKING`.

### PRV-1026 — CONFIG_OUT_OF_RANGE

A number outside the range the key allows, or one that does not fit the integer type the component
reads it into. The message has the shape `'<key>' is <value>; must be between <min> and <max>`
followed by where the value came from, or `does not fit in a 32-bit int`.

## The client SDKs

The Java SDK (`pravaha-sdk-java`) and the Python SDK (`pravaha`) raise the same codes with the same
meaning — a team running both reads one page per code, not two.

### PRV-1030 — CLIENT_MALFORMED_ENDPOINT

The endpoint string is not one the client can use. The accepted forms are `grpc://host:9090`
(plaintext), `grpc+tls://host:9090` (TLS, also spelled `grpcs://`), a comma-separated list of nodes
(`grpc+tls://h1:9090,h2:9090`, the client picks and fails over), and a bare `host:9090`, which
assumes TLS. An unknown scheme (`tcp://`), an empty host or a port that is not a number is refused
**when the client is constructed**, next to the code that supplied it, rather than fifteen minutes
later inside a request. In Python this is `pravaha.errors.MalformedEndpointError`.

### PRV-1031 — CLIENT_INVALID_OPTIONS

A client option that cannot be honoured — a negative timeout, an HTTP URL that is not a URL.
Python: `InvalidOptionsError`.

### PRV-1032 — CLIENT_INVALID_TLS_OPTIONS

The TLS options contradict each other before any file is opened: a client certificate without its
key (or the reverse), a CA file *and* a trust store, a password for a store that is not named. The
message names the pair that disagrees. Python: `InvalidTlsOptionsError`.

### PRV-1044 — CLIENT_TLS_UNREADABLE

The TLS settings were coherent, and the files disagreed with them: a certificate path that does not
exist, a keystore password that is wrong, an alias the client did not expect. Distinct from
PRV-1032 on purpose — one is fixed by editing the options, the other by fixing the file.

### PRV-1040 — CLIENT_CONNECT_FAILED

The node could not be reached. **Retryable** — a node may come back — and the Python error says so
with `retryable = True`. The commonest causes, in order: the wrong port (the console is on 8090, the
engine's Flight endpoint on 9090, its HTTP API on 8080), plaintext against a TLS listener or the
reverse, and a firewall.

```python
from pravaha import connect
from pravaha.errors import PravahaError

try:
    with connect("grpc://localhost:9090") as client:
        rows = client.query("SELECT user_id, spend FROM hourly_spend")
except PravahaError as e:
    print(e.code, e.retryable)
```

```text
1040 True
```

(With no node listening on 9090.)

### PRV-1041 — CLIENT_QUERY_REFUSED

The node refused a query sent over Flight SQL. **Not retryable**: the same SQL will be refused again.
The client's error carries 1041, and its **message is the server's own**, which begins with the
engine's code — `PRV-4023 ... this server serves ['user_volume']` — so the diagnosis is on *that*
code's page. (Python: `pravaha.client.QueryError`. The Python REST client's `ApiError` goes one step
further and carries the engine's number as its `code` when the engine gave one, keeping 1041 only
for a refusal with none.)

### PRV-1042 — CLIENT_READ_FAILED

A result stream could not be read to the end — the connection dropped mid-result, or the batches did
not decode. Rows already delivered are not a complete answer; read again.

### PRV-1043 — CLIENT_CLOSED

A call on a client (or a subscription) that has already been closed. Create a new client; a closed
one does not reopen.

## The REST API's own refusals

These are about the **request**, not the engine: nothing was planned, and nothing could be. That is
why they sit in 1xxx — a 2xxx code would send the reader to the SQL documentation for a request that
carried no SQL — and why they are answered `400`.

### PRV-1050 — API_MISSING_FIELD

A JSON body left out a field the endpoint requires — today, `sql` on `/api/v1/queries/validate` or
`/explain`:

```bash
curl -s -X POST http://localhost:8080/api/v1/queries/validate \
     -H 'Content-Type: application/json' -d '{}'
```

```text
HTTP/1.1 400
{"code":"PRV-1050","message":"PRV-1050  this request has no 'sql': send a JSON body of the form
{\"sql\": \"SELECT ...\"}","helpUrl":"https://docs.pravaha.io/errors/PRV-1050",...}
```

An *empty* `sql` is not this: the console sends one between keystrokes, and the SQL lexer refuses it
precisely. It used to surface as a raw `NullPointerException` text dressed in a planning code (API-F9).

### PRV-1051 — API_INVALID_PARAMETER

A query-string parameter the endpoint could not read — today on `GET /api/v1/audit`: a `since` or
`until` that is not an ISO-8601 instant (`2026-09-19T08:00:00Z`), a `decision` that is neither
`allow` nor `deny`, a `cursor` that is not a previous page's `nextCursor`. Refused with a `400`
naming the parameter rather than the filter being dropped: an audit search that ignored a malformed
`since` would answer a different question and look right.

## Where next

- [Configuration](/help/topics/configuration) — how a node is configured, and every setting in the
  [settings index](/help/topics/settings-index)
- [Client code](/help/topics/client-snippets) and the [SDK reference](/help/topics/sdk-reference)
- [TLS everywhere](/help/topics/tls)
