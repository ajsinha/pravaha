---
title: Configuration, API and client codes (PRV-1xxx)
slug: errors-config
category: errors
order: 20
icon: sliders
summary: "PRV-1001 to PRV-1056: a configuration value that cannot be read, a key that reached nothing, a request the REST API cannot accept or will not read, and every refusal the Java and Python SDKs raise before or while talking to a node."
badge: PRV-1XXX
audience: Operators, developers
keywords: [configuration, duration, data size, enum, reference, placeholder, endpoint, client options, tls options, connect failed, missing field, invalid parameter, sdk, "413", "429", body too large, sign-in, max-request-body]
guide: troubleshooting#every-code
related: [errors-overview, configuration, clients, http-api]
listed_on: errors-overview
---

The 1xxx range is everything that goes wrong **before any SQL is planned**: a configuration value the
engine cannot read, a REST request missing a field it needs, a client SDK given an endpoint or an
option that makes no sense, or a client that cannot reach the node at all. The common thread is that
nothing about a query is wrong — the fix is in a file, a URL or a call.

They are grouped here by who raises them:

| Codes | Raised by | When |
|---|---|---|
| PRV-1001 – PRV-1028 | The engine's configuration library (`pravaha-common`), which the embedded engine and plugin options are read through | When a configuration is built — at start, not at first use |
| PRV-1030 – PRV-1044 | The Java and Python SDKs | Constructing a client, or talking to the node |
| PRV-1050 – PRV-1056 | The REST API itself | A request whose body or parameters cannot be read, that carries text no encoder can carry, that reached no endpoint at all, whose body is larger than the node reads, a sign-in past the node's concurrency, or a request the HTTP server itself could not read |

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

Two or more values refer to each other (`a -> b -> a`). The message prints the chain it followed.

**Do:** break the loop — one of the keys has to hold a literal value.

### PRV-1012 — CONFIG_REFERENCE_TOO_DEEP

References nest deeper than the resolver will walk. The limit is 256 and it is a backstop against
the stack, not a statement about configuration: nothing a person writes gets near it, and a chain
that does is almost always generated with a loop in the generator.

**This used to be PRV-1011**, and it fired at 32 — so a genuine 34-deep chain with no loop in it
was refused as a "circular reference" that did not exist, and whoever read that went looking for
one (finding E-8). The two are separate now because the fixes are: a cycle is broken, a chain is
flattened.

**Do:** flatten the chain, or resolve it where it is generated.

### PRV-1015 — CONFIG_CONTRADICTION

Two keys that each read perfectly well and cannot both be obeyed. The first of them is one stream
with two schemas: `pravaha.streams.<name>.schema` is what a query is planned against, and
`pravaha.sources.<name>.options.schema` is what the plugin decodes rows with, so a divergence plans
one shape and reads another. No single-key check can see it, because neither key is wrong.

**Do:** write the schema once, under `pravaha.streams`, and leave the binding's option out.

### PRV-1013 — CONFIG_STREAM_EVENT_TIME_INVALID

A stream's event time, or the lateness that depends on it, cannot be used as written: a column that
is not in the schema, a column that is not a time, or an `out-of-orderness` on a stream that
declares no `event-time` at all — lateness has to be late *about* something.

**Do:** name a timestamp column the stream's schema has, and declare `event-time` before declaring
anything that qualifies it.

### PRV-1014 — CONFIG_STREAM_VERSION_IN_USE

A schema version this node already holds, redeclared with different contents. A version is how a
reader of stored rows knows what shape they are; letting one mean two things makes every stored row
ambiguous.

**Do:** bump the version rather than editing the one in use.

### PRV-1029 — CONFIG_DOCS_BASE_URL_INVALID

`pravaha.docs.base-url` is not an absolute `http://` or `https://` URL. It is where a failure's
help link points — the console's own help under `/help/topics/errors-*`, for most deployments — so
a value that is not a URL would put a broken link on every refusal the node ever raises.

Unset is a legitimate setting and is the default: the node then prints no link at all and says where
to look instead. A refused value leaves the previous one in place rather than half-applying.

**Do:** write the scheme and host in full, or leave the key out.

### PRV-1053 — API_MALFORMED_TEXT

A string in a request body that is not well-formed text — today an unpaired UTF-16 surrogate, half
of a character. No UTF-8 encoder can carry one: every one substitutes `U+FFFD`, so the name or the
SQL the node would store, log and quote back is not the one that was sent. Refused in the
deserializer, before the body becomes an argument, because a stream registered under such a name is
a key no later request can address — not by URL, not in SQL, and over Flight only as `?`.

A surrogate **pair** is an ordinary character and is untouched.

**Do:** send the text as UTF-8. If it came from a file, check what read it.

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

On a server it is also what `pravaha.checkpoint.keep: 0` answers with. Keeping no checkpoints means
every restart starts from nothing, which is what leaving `pravaha.checkpoint.directory` unset
already means — and the bound used to live in the checkpointer's constructor, which runs once per
registration, so the node started healthy, advertised itself as checkpointing, and refused every
query separately.

### PRV-1027 — CONFIG_KEY_UNREACHABLE

**A key that is in the file and reached nothing.** Spring canonicalises a map key before binding it
and silently discards one it cannot, so a stream declared as `txn ` (trailing space) or `txnü` was
present, syntactically valid, absent from the catalog, and reported at no log level at all — and
the first query against it said `Object 'txnü' not found. Known streams: [...]`, which is accurate
and impossible to act on beside a file that clearly declares it.

Quote the key in brackets to bind it verbatim:

```yaml
pravaha:
  streams:
    "[txnü]":
      schema: "id:INT64,amount:INT64"
```

or rename it to letters, digits and hyphens. The same rule covers `pravaha.sources`,
`pravaha.lookups` and `pravaha.sinks`.

### PRV-1028 — CONFIG_SCHEMA_MALFORMED

A `name:TYPE,name:TYPE` schema string that will not parse: an entry with no colon, or a type nothing
knows. One grammar serves every surface that declares a stream's shape — `--schema`, `--out-schema`,
`pravaha.streams.*.schema`, `POST /api/v1/streams`, and a source or sink plugin's own `schema`
option — so this is the code for all of them.

```text
PRV-1028  stream 'd', column 'amt': unknown type 'DECIMAL'. Supported: BOOLEAN, INT8, ...
```

The message names the **stream** and the **column**, which it did not before: an operator whose node
refused to start had one sentence about a type and every declared stream to check it against (TY-9).

**It used to be `PRV-5040`**, the filesystem plugin's decode code, because that is where the parser
lives. The REST API derives its HTTP status from a code's category, so a caller who misspelled a type
in the body of their own request was answered `500 Internal Server Error` — the server is broken —
for something only they could fix. It answers `400` now (TY-8). `PRV-5040` still means what it always
meant: a line of data in a file that could not be decoded.

**Do:** read the column the message names. `DECIMAL(10,2)` parses, so a parenthesised type is not the
problem it once was (TY-7); suffix a type with `?` for nullable; `ARRAY`, `MAP` and `ROW` are not
declarable through this grammar at all.

## The client SDKs

The Java SDK (`pravaha-sdk-java`) and the Python SDK (`pravaha`) raise the same codes with the same
meaning — a team running both reads one page per code, not two.

### PRV-1030 — CLIENT_MALFORMED_ENDPOINT

The endpoint string is not one the client can use. The accepted forms are `grpc://host:19090`
(plaintext), `grpc+tls://host:19090` (TLS, also spelled `grpcs://`), a comma-separated list of nodes
(`grpc+tls://h1:19090,h2:19090`, the client picks and fails over), and a bare `host:19090`, which
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
with `retryable = True`. The commonest causes, in order: the wrong port (the console is on 17070, the
engine's Flight endpoint on 19090, its HTTP API on 18080), plaintext against a TLS listener or the
reverse, and a firewall.

```python
from pravaha import connect
from pravaha.errors import PravahaError

try:
    with connect("grpc://localhost:19090") as client:
        rows = client.query("SELECT user_id, spend FROM hourly_spend")
except PravahaError as e:
    print(e.code, e.retryable)
```

```text
1040 True
```

(With no node listening on 19090.)

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
curl -s -X POST http://localhost:18080/api/v1/queries/validate \
     -H 'Content-Type: application/json' -d '{}'
```

```text
HTTP/1.1 400
{"code":"PRV-1050","message":"PRV-1050  this request has no 'sql': send a JSON body of the form
{\"sql\": \"SELECT ...\"}","helpUrl":"",...}
```

An *empty* `sql` is not this: the console sends one between keystrokes, and the SQL lexer refuses it
precisely. It used to surface as a raw `NullPointerException` text dressed in a planning code (API-F9).

### PRV-1051 — API_INVALID_PARAMETER

A query-string parameter the endpoint could not read — today on `GET /api/v1/audit`: a `since` or
`until` that is not an ISO-8601 instant (`2026-09-19T08:00:00Z`), a `decision` that is neither
`allow` nor `deny`, a `cursor` that is not a previous page's `nextCursor`. And on any endpoint that
pages, an `?offset=` or `?limit=` present and empty or not a number (an empty `?limit=` is most often a
shell variable that did not expand; leave the parameter out for its default), and an empty `?level=`
or `?format=` on `/explain`. Refused with a `400`
naming the parameter rather than the filter being dropped: an audit search that ignored a malformed
`since` would answer a different question and look right.

### PRV-1052 — API_UNHANDLED_REQUEST

The request reached **no endpoint**: a method the path does not support (405), a body in a media
type the endpoint does not read (415), or an unmapped path (404). The status distinguishes them and
a client already has it; the code says the body is an `ApiError` like every other failure on this
API.

Before it existed, those three fell past the exception handler to Spring Boot's own error
controller and came back as a third error shape —
`{"timestamp":…,"status":405,"error":"Method Not Allowed","path":…}` — with no `code`, no `message`
and no `helpUrl`, on a surface whose stated contract is one shape and nothing else. The published
OpenAPI document now carries the `ApiError` schema with its five fields and a `default` response on
every operation, so a generated client models the error rather than an empty object.

### PRV-1054 — API_BODY_TOO_LARGE

`413`. The request's body is larger than this node reads: `pravaha.http.max-anonymous-body` (16KB) on
a path open without a credential — sign-in, password reset, the API documentation — and
`pravaha.http.max-request-body` (4MB) everywhere else. A body that declares its length is refused on
the declaration, before a byte of it is read; a chunked one as soon as it passes the limit. The
connection is closed after the answer.

Before HTTPBODY-1 a body was read whole, before authentication, up to Jackson's 20-million-character
string limit, and thirty concurrent 19 MB anonymous sign-ins ran a 1 GiB node out of heap.

**Do:** send less — no request the API takes needs megabytes. If one genuinely does, raise
`pravaha.http.max-request-body`.

### PRV-1055 — API_TOO_MANY_SIGN_INS

`429`, with `Retry-After: 1`. More sign-ins (`POST /api/v1/auth/login`, `/api/v1/auth/reset/redeem`)
are in progress at once than `pravaha.http.max-concurrent-sign-ins` (8). Each runs a deliberately slow
password hash, so the number at once is bounded rather than left to whoever is sending them.

**Do:** retry after the second the header names; a script signing in in a loop should sign in once and
keep the session, or use an API key.

### PRV-1056 — API_MALFORMED_REQUEST

`400`. The HTTP server could not read the request and refused it before any endpoint saw it: an
encoded `/` (`%2F`) or `\` (`%5C`), a NUL (`%00`) or another character a path may not carry, a header
line larger than `server.max-http-request-header-size` (8KB by default), or more headers than the
server takes. The answer is an `ApiError` like every other; before TOMCATHTML-1 it was the HTTP
server's own HTML page.

**Do:** send the name unencoded in the path only if it has no `/` (a view or stream name never
needs one), and keep headers small — a bearer token is far below the limit.

## Where next

- [Configuration](/help/topics/configuration) — how a node is configured, and every setting in the
  [settings index](/help/topics/configuration#every-setting)
- [Client code](/help/topics/clients#snippets) and the [SDK reference](/help/topics/clients#sdk-reference)
- [TLS everywhere](/help/topics/tls)
