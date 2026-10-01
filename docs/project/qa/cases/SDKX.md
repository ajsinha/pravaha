# SDKX — the three SDKs and the console

*Area `SDKX`, IDs `SDKX-001`–`SDKX-080`, budget 80.*

Four client surfaces, and they are not four equivalent things:

| Surface | What it actually is | Where |
|---|---|---|
| `pravaha-sdk-java` | **Types only.** No client, no transport. Six files: `Endpoint`, `ClientOptions`, `Consistency`, `ClientErrors`, `PravahaClientException`, `package-info` | `sdk/pravaha-sdk-java/src/main/java/com/ash/messaging/pravaha/sdk/` |
| `pravaha-sdk-java-flight` | The Java transport, Arrow Flight SQL over gRPC | `sdk/pravaha-sdk-java-flight/src/main/java/com/ash/messaging/pravaha/sdk/flight/PravahaFlightClient.java` |
| `sdk/python` | An independent Python client over `pyarrow.flight`, with its own re-implementation of the control wire | `sdk/python/pravaha/client.py` |
| `console/` | FastAPI + Jinja2, server-rendered, consuming the **published Python SDK** (ADR-024) | `console/run_pravaha_web.py`, `console/routes/`, `console/core/` |

`package-info.java:24`–`:26` says the first of these outright: *"Wave 1 delivers the connection and
result contracts. The gRPC transport that implements them lands with the gateways in Wave 7."* So the
"Java SDK" and the "Java Flight SDK" are one product in two artefacts, and the first one cannot
connect to anything. Section A is written accordingly.

## Four facts the whole file is built on

Each is established by a case; they are collected here so the rest reads sensibly.

1. **A connect never fails.** gRPC channels are lazy in both Java and Python, so
   `PravahaFlightClient.connect(...)` and `pravaha.connect(...)` against a dead host **succeed**, and
   the failure arrives at the first call — as `QUERY_REFUSED` (PRV-1041, `retryable = false`), not as
   `CONNECT_FAILED` (PRV-1040, `retryable = true`). SDKX-014, 015, 039.
2. **A server's PRV code does not survive into the client's error code.** Every server refusal is
   re-stamped PRV-1041 by the Java client and code `1041` by the Python one; the server's own code
   survives only inside the message text. The console recovers it by *string-searching* the message
   (`console/core/services.py:106`). SDKX-029, 048, 078.
3. **The configured timeouts are never read.** `ClientOptions.connectTimeout()` / `requestTimeout()`
   and their Python equivalents are validated at construction and consulted by nothing;
   `grep -rn "withDeadline\|CallOptions.timeout\|FlightCallOptions(.*timeout" sdk/` is empty.
   SDKX-030, 049.
4. **No shipped client can complete a TLS handshake, and none can be given a CA.** The netty
   artefact the gRPC SSL pipeline needs is declared in `pravaha-server/pom.xml` alone. Section D.

## Harnesses

**`H-UNIT`** — no server. JUnit for Java, pytest for Python. Used for every parsing and options case.

**`H-OPEN`** — a plaintext node with no authentication:

```yaml
pravaha:
  streams:
    txn: { fields: "user_id STRING, amount INT64, product_type STRING", event-time: "" }
  security: { authentication: none, policy: permissive, allow-anonymous: true }
  flight: { enabled: true }
```

on `grpc://localhost:19090`. A query `q` is registered as
`SELECT user_id, amount, product_type FROM txn` with key columns `[0]`. Rows reach `txn` through a
bound source -- `pravaha.sources.txn` with the `filesystem` plugin and `follow: true`, appended to --
unless a case says otherwise: a real node has no Flight `DoPut` (or REST) path that pushes rows into
a declared stream (API-F5; `docs/project/qa/cases/API.md`'s `H-SRV` has the binding).

**`H-AUTH`** — `H-OPEN` with

```yaml
  security:
    authentication: token
    policy: authenticated
    allow-anonymous: false
    tokens:
      "correct-horse-battery-staple-0001": { id: ann, tenant: acme, roles: [reader] }
```

The valid token is `correct-horse-battery-staple-0001`; the invalid one used throughout is
`wrong-token-0002`.

**`H-TLS`** — `H-AUTH` plus `pravaha.flight.tls.certificate` and `.key` pointing at a self-signed
pair generated for `CN=localhost` with
`openssl req -x509 -newkey rsa:2048 -keyout key.pem -out cert.pem -days 1 -nodes -subj /CN=localhost`.

**`H-DEAD`** — nothing listening on `localhost:9099`. Confirmed with `ss -ltn | grep 9099` returning
nothing before each case.

**`H-CON`** — the console at `http://127.0.0.1:17070`, `CONSOLE_PASSWORD=letmein`, pointed at
`H-OPEN` via `PRAVAHA_ENGINE=grpc://localhost:19090`. Driven with an HTTP client that keeps cookies.

**`H-CON-DOWN`** — `H-CON` with the engine stopped and nothing listening on 19090.

---

### A. `pravaha-sdk-java`: the contract artefact (SDKX-001–012)

Twelve cases, none of which touch a network, because the module cannot.

## SDKX-001 — the artefact ships no client, and says so
**Intent:** an executor, a consumer and a release note all need to know that adding
`pravaha-sdk-java` to a project gets you types and nothing that connects.
**Falsifier:** any type in the module opens a socket, or has a method named `connect`, `query`,
`register` or `subscribe`.
**Setup:** the module at `develop`.
**Steps:** 1. List `sdk/pravaha-sdk-java/src/main/java/**/*.java`. 2. Grep the module for `Socket`,
`Channel`, `FlightClient`, `HttpClient`, `connect(`. 3. Read `package-info.java`.
**Expected:** step 1 lists exactly six files: `ClientErrors.java`, `ClientOptions.java`,
`Consistency.java`, `Endpoint.java`, `PravahaClientException.java`, `package-info.java`. Step 2
returns nothing. Step 3 contains `the gRPC transport that implements them lands with the gateways in
Wave 7`. The public surface is: `Endpoint` (with the nested record `HostPort`), `ClientOptions`
(with `Builder`), the enum `Consistency` with four constants, `ClientErrors` with four `ErrorCode`
constants, and `PravahaClientException`.
**Vacuity:** n/a — an enumeration.

## SDKX-002 — `Endpoint.parse` resolves every scheme, and an omitted scheme means TLS
**Intent:** `Endpoint.java:87`–`:96`. The default is the dangerous-looking-but-safe direction: no
scheme means TLS. The CLI defaults the other way (SDKX-059), and the two together are the trap.
**Falsifier:** any row below resolves differently.
**Setup:** `H-UNIT`.
**Steps:** 1. `Endpoint.parse(s)` for each `s`.
**Expected:**

| input | `nodes()` | `tls()` |
|---|---|---|
| `grpc://h:19090` | `[(h,19090)]` | false |
| `http://h:19090` | `[(h,19090)]` | false |
| `grpc+tls://h:19090` | `[(h,19090)]` | **true** |
| `grpcs://h:19090` | `[(h,19090)]` | **true** |
| `https://h:19090` | `[(h,19090)]` | **true** |
| `h:19090` (no scheme) | `[(h,19090)]` | **true** |
| `h` (no scheme, no port) | `[(h,19090)]` | **true** |
| `ftp://h:19090` | — | PRV-1030 |
| `GRPC://h:19090` | — | record actual |

The last row is the one to measure rather than assume: the `switch` is on the lower-cased scheme or
not, and the case must record which. `DEFAULT_PORT = 19090` (`Endpoint.java:44`) supplies the port in
rows 6 and 7.
**Vacuity:** n/a.

## SDKX-003 — a comma-separated endpoint parses into several nodes, in order
**Intent:** `Endpoint`'s javadoc promises the client picks one and fails over. SDKX-017 shows the
Flight client does not; this case pins that the *parsing* half is real, so the gap is attributable.
**Falsifier:** the order changes, or only one node comes back.
**Setup:** `H-UNIT`.
**Steps:** 1. `Endpoint.parse("grpc://a:19090,b:9091,c:9092")`. 2.
`Endpoint.parse("grpc+tls://a:19090, b:9091 ,c")`. 3. `Endpoint.parse("grpc://a:19090,,b:9091")`.
**Expected:** step 1 — `nodes()` is `[(a,19090), (b,9091), (c,9092)]` in that order, `tls()` false.
Step 2 — three nodes, whitespace stripped, `c` defaulted to port 19090, `tls()` true: the scheme is
stated once and applies to all. Step 3 — record whether the empty element is skipped or refused with
PRV-1030.
**Vacuity:** using three distinct ports makes the order checkable; three copies of one host would not.

## SDKX-004 — a malformed endpoint is PRV-1030 with the expected forms in the message
**Intent:** `MALFORMED_ENDPOINT = new ErrorCode(1030, "CLIENT_MALFORMED_ENDPOINT")`
(`Endpoint.java:46`) and the message at `:136`–`:139`.
**Falsifier:** a `NumberFormatException`, `StringIndexOutOfBoundsException`, or any exception that is
not a `PravahaClientException`.
**Setup:** `H-UNIT`.
**Steps:** parse each of: `""`, `"   "`, `null`, `"grpc://"`, `"grpc://h:"`, `"grpc://h:abc"`,
`"grpc://h:0"`, `"grpc://h:65536"`, `"grpc://h:-1"`, `"ftp://h:19090"`, `"://h:19090"`.
**Expected:** every one throws `PravahaClientException` whose `errorCode().code()` is `PRV-1030` and
whose message matches `cannot parse endpoint '<input>': <why>. Expected grpc://host:port,
grpc+tls://host:port, or a comma-separated list.` Record the `<why>` for each. The port arms
(`:abc`, `:0`, `:65536`, `:-1`) are the ones most likely to leak a raw
`NumberFormatException` — if any does, that is the finding.
**Vacuity:** eleven arms across four failure kinds (empty, no host, bad port, bad scheme) means a
single over-broad `catch` that turns everything into PRV-1030 still has to produce eleven distinct
`<why>` texts to be useful, which the case records.

## SDKX-005 — every `ClientOptions` default is what the class says
**Intent:** `ClientOptions.java:126`–`:131`. These are the numbers a consumer reasons about, and two
of them are inert (SDKX-030).
**Falsifier:** any default differs.
**Setup:** `H-UNIT`.
**Steps:** 1. `ClientOptions.builder("grpc://h:19090").build()`. 2. Read all eight accessors.
**Expected:** `endpoint()` is the parsed endpoint; `token()` is `Optional.empty()`;
`allowInsecureToken()` is `false`; `connectTimeout()` is `Duration.ofSeconds(10)`;
`requestTimeout()` is `Duration.ofSeconds(30)`; `defaultConsistency()` is `Consistency.CONSISTENT`;
`subscriberBufferRows()` is `10_000`; `conflateOnOverflow()` is `true`; `applicationName()` is
`"pravaha-java-sdk"`. Note `subscriberBufferRows`/`conflateOnOverflow` mirror
`SubscriptionOptions.DEFAULT = (10_000, CONFLATE)` on the server side — assert the two agree, because
nothing enforces it.
**Vacuity:** n/a.

## SDKX-006 — invalid options are refused at the builder with PRV-1031
**Intent:** `INVALID_OPTIONS = new ErrorCode(1031, "CLIENT_INVALID_OPTIONS")`
(`ClientOptions.java:34`). Refusing at `build()` rather than at first use is the point.
**Falsifier:** any of these builds.
**Setup:** `H-UNIT`, base `builder("grpc://h:19090")`.
**Steps:** set each in turn and `build()`: `connectTimeout(Duration.ZERO)`;
`connectTimeout(Duration.ofSeconds(-1))`; `connectTimeout(null)`; the same three for
`requestTimeout`; `subscriberBufferRows(0)`; `subscriberBufferRows(-1)`; `applicationName("")`;
`applicationName("   ")`; `applicationName(null)`; `defaultConsistency(null)`.
**Expected:** every arm throws `PravahaClientException` with `errorCode().code() == "PRV-1031"` —
except record what `defaultConsistency(null)` and the `null` durations actually do, since a `null`
may reach the validation as an NPE instead. `subscriberBufferRows(1)` and
`connectTimeout(Duration.ofNanos(1))` must **succeed**, as the boundary controls.
**Vacuity:** the two succeeding boundary arms are what prove the validation is `< 1` and `<= 0`
rather than a blanket refusal.

## SDKX-007 — a token over a plaintext endpoint is refused, and `allowInsecureToken` permits it
**Intent:** `ClientOptions.java:192`–`:204`. The check exists, and SDKX-059 and SDKX-080 are two
first-party consumers that switch it off unconditionally.
**Falsifier:** `builder("grpc://h:19090").token("t").build()` succeeds.
**Setup:** `H-UNIT`.
**Steps:** 1. `builder("grpc://h:19090").token("t").build()`. 2. The same with
`.allowInsecureToken(true)`. 3. `builder("grpc+tls://h:19090").token("t").build()`. 4.
`builder("h:19090").token("t").build()` (scheme omitted, so TLS).
**Expected:** step 1 throws PRV-1031 with
`refusing to send a token over a plaintext connection to <endpoint>; use grpc+tls://, remove the
token, or call allowInsecureToken(true) if the connection is loopback or TLS ends at a local
sidecar`. Steps 2, 3 and 4 all succeed. Note that step 2's escape hatch talks about loopback and does
not *check* for loopback — `builder("grpc://public.example.com:19090").token("t")
.allowInsecureToken(true).build()` also succeeds; assert that, because it is what SDKX-059 exploits.
**Vacuity:** step 3 and 4 are the controls: without them, a builder that refused every token would
pass step 1.

## SDKX-008 — `toString` does not print the token
**Intent:** `ClientOptions.java:115` deliberately omits it; a client's options land in logs and
exception messages.
**Falsifier:** the token appears in any string form.
**Setup:** `H-UNIT`, options with `token("correct-horse-battery-staple-0001")`,
`allowInsecureToken(true)`, `grpc://h:19090`.
**Steps:** 1. `options.toString()`. 2. Trigger a PRV-1030 and a PRV-1031 carrying these options and
read the messages.
**Expected:** none of the three strings contains `correct-horse` or any substring of the token of
length 8 or more. Step 1 does contain the endpoint, the consistency and the application name. Python
equivalent in SDKX-036.
**Vacuity:** searching for an 8-character substring rather than the whole token catches a truncating
or masking implementation that still leaks a usable prefix.

## SDKX-009 — a PRV code, a help URL and a retryable flag reach the caller
**Intent:** `PravahaClientException` extends `PravahaException`, which carries `errorCode()` and
`helpUrl()`, and adds `retryable()`. The message format is `code.code() + "  " + message` — **two
spaces** (`PravahaException.java:125`).
**Falsifier:** `errorCode()` is null, or the message has one space, or `helpUrl()` is wrong.
**Setup:** `H-UNIT`. Construct
`new PravahaClientException(ClientErrors.CONNECT_FAILED, "cannot reach h", true)`.
**Steps:** 1. Read `errorCode().number()`, `errorCode().name()`, `errorCode().code()`,
`helpUrl()`, `retryable()`, `getMessage()`.
**Expected:** `1040`; `"CLIENT_CONNECT_FAILED"`; `"PRV-1040"`;
`"https://docs.pravaha.io/errors/PRV-1040"`; `true`; and `getMessage()` exactly
`"PRV-1040  cannot reach h"` — two spaces between the code and the text. It is a
`RuntimeException`, so nothing forces a caller to handle it; assert that too.
**Vacuity:** n/a.

## SDKX-010 — the four client error codes are distinct and in the 10xx band
**Intent:** ERRC owns the global uniqueness sweep; this is the SDK's own four, checked here so a
later renumber is caught at the source.
**Falsifier:** two share a number, or one collides with a server code.
**Setup:** `H-UNIT`.
**Steps:** 1. Read all four. 2. Grep the whole repository for `new ErrorCode(1030`, `(1031`, `(1040`
.. `(1043`.
**Expected:** `CONNECT_FAILED` 1040 `CLIENT_CONNECT_FAILED`; `QUERY_REFUSED` 1041
`CLIENT_QUERY_REFUSED`; `READ_FAILED` 1042 `CLIENT_READ_FAILED`; `CLOSED` 1043 `CLIENT_CLOSED`; plus
`MALFORMED_ENDPOINT` 1030 and `INVALID_OPTIONS` 1031 declared on their own classes. Six distinct
numbers, each declared exactly once in the repository.
**Vacuity:** n/a.

## SDKX-011 — `ClientErrors.CLOSED` (PRV-1043) is declared and thrown by nothing
**Intent:** the contract says there is a "you closed this client" failure; there is not. A caller
using a closed `PravahaFlightClient` gets whatever Arrow throws.
**Falsifier:** any file outside `ClientErrors.java` references `CLOSED`.
**Setup:** the repository, plus `H-OPEN`.
**Steps:** 1. `grep -rn "ClientErrors.CLOSED" --include=*.java .`. 2. Against `H-OPEN`:
`client.close()` then `client.query("SELECT 1")`. 3. `client.close()` twice.
**Expected:** step 1 returns nothing. Step 2 throws — record the exact type and message; it will be
an Arrow or gRPC exception with no PRV code, not PRV-1043. Step 3: record whether the second `close`
throws. Both are findings: the declared contract is not implemented, and the actual behaviour is
undocumented.
**Vacuity:** step 1's emptiness is the proof; step 2 is what it costs.

## SDKX-012 — the thin client stays thin
**Intent:** `sdk/pravaha-sdk-java/pom.xml:36`–`:62` is an enforcer rule banning `io.netty:*`,
Calcite, RocksDB, Agrona, Aerospike, Spring, `pravaha-runtime` and `pravaha-common`. It is the only
thing keeping the contract artefact embeddable.
**Falsifier:** the build passes with a banned dependency added.
**Setup:** the module.
**Steps:** 1. `./mvnw -o -pl sdk/pravaha-sdk-java dependency:tree`. 2. Add
`<dependency>io.netty:netty-handler</dependency>` and build. 3. Revert.
**Expected:** step 1 shows exactly one compile dependency, `pravaha-api`, plus test scope. Step 2
fails the `enforce-thin-client` rule with a message naming `io.netty`. Step 3 restores a green build.
**Vacuity:** step 2 is the non-vacuity: a rule that is configured but not bound to a phase passes
step 1 and does nothing.

---

### B. `pravaha-sdk-java-flight`: the Java transport (SDKX-013–033)

`PravahaFlightClient` holds two views of one connection: a `FlightClient` for the five Pravaha
actions and a `FlightSqlClient` for SQL. Every call passes `CallOption[]` carrying only an
`authorization` header. Running it needs
`--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED`.

## SDKX-013 — connect and round-trip against a live plaintext server
**Intent:** the base case everything else is measured against.
**Falsifier:** `connect` throws, or `query` returns no rows for data that is there.
**Setup:** `H-OPEN`, with three rows pushed into `txn`: `("u1", 100, "card")`, `("u2", 250, "wire")`,
`("u1", 102, "card")`, and the view `q` committed.
**Steps:** 1. `PravahaFlightClient c = PravahaFlightClient.connect("grpc://localhost:19090")`.
2. `try (QueryResult r = c.query("SELECT user_id, amount FROM q ORDER BY amount"))`, collect
`r.toList()`. 3. `c.close()`.
**Expected:** `r.columns()` is `["user_id", "amount"]`. `toList()` has 3 elements:
`["u1", 100L]`, `["u1", 102L]`, `["u2", 250L]` in that order. `amount` is `Long`, not `Integer` —
the stream declares `INT64`. `close()` returns without throwing, twice is not attempted here (SDKX-011).
**Vacuity:** ordering by `amount` and asserting the values, not the count, is what stops a result of
three unrelated rows passing. `100 + 102 = 202` is deliberately *not* what this query computes —
it is a projection, so both `u1` rows must appear separately.

## SDKX-014 — connecting to a dead server succeeds
**Intent:** gRPC channels are lazy, and `FlightClient.builder(...).build()` does no I/O. So
`connect` is not a liveness check, and any code treating it as one is wrong.
**Falsifier:** `connect` throws against a host with nothing listening.
**Setup:** `H-DEAD`. Confirm `ss -ltn | grep 9099` is empty.
**Steps:** 1. Time `PravahaFlightClient.connect("grpc://localhost:9099")`. 2. Repeat with a host
that does not resolve (`grpc://no-such-host.invalid:19090`). 3. Repeat with a routable but
unreachable address (`grpc://10.255.255.1:19090`).
**Expected:** all three **return a client**, in under 100 ms each, with no exception. Record the
elapsed time for each — in particular arm 2, where a DNS lookup might be expected to happen eagerly
and does not. This is a finding, not a pass: the SDK's own `CONNECT_FAILED` code implies otherwise.
**Vacuity:** the sub-100 ms timing distinguishes "lazy" from "connected quickly"; without it, arm 3
against a black-holed address is the discriminator.

## SDKX-015 — the first call against a dead server is PRV-1041, non-retryable
**Intent:** the consequence of SDKX-014. A caller doing
`catch (PravahaClientException e) { if (e.retryable()) retry(); }` will not retry a server that is
merely restarting, which is the single most retryable condition there is.
**Falsifier:** the exception carries PRV-1040, or `retryable()` is true.
**Setup:** `H-DEAD`, client from SDKX-014.
**Steps:** 1. `c.query("SELECT 1")`. 2. `c.queries()`. 3. `c.register("x", "SELECT user_id FROM
txn", List.of(0))`. 4. `c.drop("x")`. 5. `c.subscribe("q", b -> {})`.
**Expected:** every one throws `PravahaClientException` with `errorCode().code() == "PRV-1041"`
(`CLIENT_QUERY_REFUSED`) and `retryable() == false`. The message is the gRPC status description,
containing `UNAVAILABLE` and `Connection refused` (or `UNKNOWN`/`UnknownHostException` for the
unresolvable arm). Record the elapsed time: with no deadline configured (SDKX-030), a black-holed
address blocks until the OS TCP timeout, which on Linux is about `tcp_syn_retries`-derived — measure
it and report.
**Vacuity:** five different entry points rather than one is what shows this is the module's single
error shape and not one method's bug.

## SDKX-016 — PRV-1040 `CONNECT_FAILED` is only reachable when the builder itself throws
**Intent:** the code exists and is `retryable = true`, which is the correct classification. This case
finds out what, if anything, produces it.
**Falsifier:** a realistic connection failure produces PRV-1040.
**Setup:** `H-UNIT` and `H-DEAD`.
**Steps:** 1. Enumerate every `throw` of `ClientErrors.CONNECT_FAILED` in the module. 2. Construct
inputs that make `FlightClient.builder(...).build()` throw a `RuntimeException`: an endpoint whose
host is a very long string; a port of `0`; a `BufferAllocator` already closed, passed to the
three-argument `connect`. 3. Observe which, if any, yields PRV-1040.
**Expected:** step 1 finds exactly one site, `PravahaFlightClient.java:152`–`:158`, message
`cannot connect to <host>:<port>: <cause>`, `retryable = true`. Step 3: record which arm reaches it.
If none does, the code is unreachable in practice and that is the finding — the retryable
classification the SDK offers callers is attached to the one failure that never happens, while the
one that always happens is classified non-retryable (SDKX-015).
**Vacuity:** step 1's enumeration bounds the search, so "none reached it" is a statement about one
`catch` block rather than about the whole module.

## SDKX-017 — only the first node of a multi-node endpoint is ever used
**Intent:** `PravahaFlightClient.java:138` is `options.endpoint().nodes().get(0)`. `Endpoint`'s
javadoc promises the client picks and fails over; it does not.
**Falsifier:** a request succeeds when the first node is dead and the second is alive.
**Setup:** `H-OPEN` on 19090. Nothing on 9099.
**Steps:** 1. `connect("grpc://localhost:9099,localhost:19090")`, then `queries()`. 2.
`connect("grpc://localhost:19090,localhost:9099")`, then `queries()`. 3. Stop `H-OPEN` mid-session in
arm 2 and call `queries()` again.
**Expected:** arm 1 throws PRV-1041 — the live second node is never tried. Arm 2 succeeds. Arm 3
throws PRV-1041 and does **not** fail over to the (dead) second node either, which is consistent.
Also assert `Endpoint.parse` returned two nodes in both arms, so the loss is in the client and not
the parser (SDKX-003 is the control).
**Vacuity:** running the same two-node list in both orders is what isolates "uses the first" from
"cannot reach 9099 for an unrelated reason".

## SDKX-018 — a valid token authenticates
**Intent:** the happy path of the header at `PravahaFlightClient.java:178`–`:182`.
**Falsifier:** PRV-7001 with the correct token.
**Setup:** `H-AUTH`.
**Steps:** 1. `connect(ClientOptions.builder("grpc://localhost:19090")
.token("correct-horse-battery-staple-0001").allowInsecureToken(true).build())`. 2. `queries()`.
3. `query("SELECT user_id FROM q")`.
**Expected:** both calls succeed. The server's audit sink records the principal as `ann`, tenant
`acme`, roles `[reader]`. `allowInsecureToken(true)` is required because `H-AUTH` is plaintext
(SDKX-007); without it `build()` throws PRV-1031 — assert that as a sub-arm.
**Vacuity:** the audit assertion distinguishes "the call worked" from "the call worked as the right
principal", which matters because `policy: authenticated` would also admit a differently-identified
caller.

## SDKX-019 — an invalid token is refused, and the refusal reaches the caller
**Intent:** error surfacing under the one condition every operator hits first.
**Falsifier:** the call succeeds, or the failure is indistinguishable from a network failure.
**Setup:** `H-AUTH`.
**Steps:** 1. Connect with `token("wrong-token-0002")`. 2. `queries()`. 3. `query("SELECT 1")`.
4. `register(...)`. 5. `subscribe("q", b -> {})`.
**Expected:** all four throw `PravahaClientException` with `errorCode().code() == "PRV-1041"` —
**not** the server's PRV-7001 — and `retryable() == false`. The *message* contains the server's text
including `PRV-7001`. So a caller wanting to distinguish "bad credentials" from "server down"
(SDKX-015, also PRV-1041, also non-retryable) must parse the message. That is the finding, and it is
the same one SDKX-029 states in general.
**Vacuity:** comparing against SDKX-015's exception — same code, same flag, different message — is
what makes "indistinguishable except by text" a measured claim.

## SDKX-020 — a missing token against an authenticating server
**Intent:** the third auth arm, and the one where the client sends **no header at all**
(`credentialsOf` returns `new CallOption[0]`, `:170`–`:176`) rather than an empty one.
**Falsifier:** the client refuses locally instead of letting the server answer, or the call succeeds.
**Setup:** `H-AUTH`.
**Steps:** 1. `connect("grpc://localhost:19090")` with no token. 2. `queries()`. 3. Capture the gRPC
metadata on the wire (server-side interceptor or `tcpdump` on loopback).
**Expected:** step 1 succeeds — the client does not pre-check (the comment at `:166`–`:169` says so:
"a server that requires auth answers PRV-7001"). Step 2 throws PRV-1041 whose message contains the
server's PRV-7001 text. Step 3: **no** `authorization` header is present, as opposed to
`authorization: Bearer ` with an empty value.
**Vacuity:** step 3 is what distinguishes "no header" from "empty header" — two different things for
a server or proxy in between, and only one of them is what the code does.

## SDKX-021 — the credential is `authorization: Bearer <token>`, lower-case, on every call
**Intent:** the exact wire shape, because an intermediary (a proxy, a service mesh) sees this and not
the Java.
**Falsifier:** the header name is `Authorization`, or the scheme is not `Bearer `, or it is sent only
on the first call.
**Setup:** `H-AUTH` with a Flight server-side middleware recording incoming headers.
**Steps:** 1. Connect with the valid token. 2. `queries()`, `query(...)`, `register(...)`,
`drop(...)`, `pause(...)`, `resume(...)`, and a `subscribe` that receives one batch. 3. Read the
recorded headers per call.
**Expected:** every one of the seven calls carries exactly one header whose key is `authorization`
(lower case, as inserted at `:180`) and whose value is
`Bearer correct-horse-battery-staple-0001` — one space after `Bearer`. No call is missing it. The
server's own reader is `PrincipalMiddleware`, which does `header.substring(BEARER.length())`
(`pravaha-flight/.../PrincipalMiddleware.java:107`) — assert the two agree on the prefix.
**Vacuity:** seven call kinds rather than one is the point: the `CallOption[]` is built once in the
constructor and passed everywhere, so a call site that forgot it is the failure being looked for.

## SDKX-022 — `query` returns typed rows, and `Row` is a flyweight
**Intent:** `Row`'s accessors and the lifetime rule. A `Row` is valid only while the iteration is on
it; keeping one past the batch is a use-after-free in disguise.
**Falsifier:** a retained `Row` still reads correct values after the result is closed.
**Setup:** `H-OPEN` with SDKX-013's three rows.
**Steps:** 1. `query("SELECT user_id, amount, product_type FROM q ORDER BY amount")`. 2. In the loop,
read `getString(0)`, `getString("user_id")`, `getLong(1)`, `getLong("amount")`, `get(2)`,
`isNull(2)`, `columns()`, `toArray()`. 3. Retain a reference to the first `Row`. 4. Close the result.
5. Read the retained `Row`.
**Expected:** step 2 — for the first row, `getString(0)` and `getString("user_id")` both `"u1"`;
`getLong(1)` and `getLong("amount")` both `100L`; `get(2)` is `"card"`; `isNull(2)` is `false`;
`columns()` is `["user_id","amount","product_type"]`; `toArray()` is `["u1", 100L, "card"]`.
Step 5 — record what happens: garbage, an exception, or (worst) a plausible wrong value. Whatever it
is, it is undocumented, and the case records it.
**Vacuity:** reading each column both by ordinal and by name in the same pass is what catches an
off-by-one in the name lookup, which a by-ordinal-only test cannot.

## SDKX-023 — a `QueryResult` can be iterated once
**Intent:** `QueryResult.java:59` throws PRV-1042 on a second `iterator()`, with
`already been iterated` in the text. A stream is not a list.
**Falsifier:** the second iteration yields rows, or yields nothing silently.
**Setup:** `H-OPEN`.
**Steps:** 1. `QueryResult r = c.query("SELECT user_id FROM q")`. 2. `r.iterator()` and drain.
3. `r.iterator()` again. 4. Separately: `r2.toList()` then `r2.iterator()`. 5. Separately:
`r3.iterator()` then `r3.toList()`.
**Expected:** step 3 throws `PravahaClientException` PRV-1042 (`CLIENT_READ_FAILED`) containing
`already been iterated`. Steps 4 and 5 record whether `toList()` counts as an iteration in both
directions — it should, since it drains the stream, and if only one direction is guarded that is the
finding.
**Vacuity:** steps 4 and 5 are the two orderings; testing one leaves the other unasserted.

## SDKX-024 — a parameterised query round-trips through the prepared-statement path
**Intent:** `query(sql, Object...)` takes a different route (`:222`) — create prepared, bind via
`DoPut`, execute, close. Four round trips, and `Parameters.java` raises PRV-1041 for unsupported
types.
**Falsifier:** the filter is ignored, or a supported type is refused.
**Setup:** `H-OPEN` with SDKX-013's three rows.
**Steps:** 1. `query("SELECT user_id, amount FROM q WHERE amount > ?", 150L)`. 2. The same with
`Integer.valueOf(150)`, `"card"` against `product_type = ?`, `Double.valueOf(150.0)`,
`Boolean.TRUE`, `null`, and a `byte[]`. 3. `query("SELECT ... WHERE amount > ?")` with **no**
parameter. 4. With two parameters for one placeholder.
**Expected:** step 1 returns exactly one row, `["u2", 250L]` — `250 > 150` and
`100 > 150`, `102 > 150` are both false. The `Integer` arm must return the same one row. The
`product_type = 'card'` arm returns two rows, `["u1",100L]` and `["u1",102L]`. Record which of the
remaining types are accepted and which raise PRV-1041 from `Parameters.java:52`/`:94`/`:118`. Steps 3
and 4 are refusals — record the code and message; a silent success in either is the finding.
**Vacuity:** `150` is chosen so exactly one of three rows passes; a threshold of `0` or `1000` would
pass whether or not the parameter was bound.

## SDKX-025 — `register` creates a query and returns its description
**Intent:** the `pravaha.register` action, and that `rowsIn` is hard-coded `0` at registration
(`:270`).
**Falsifier:** the query does not appear in `queries()`, or the returned record is wrong.
**Setup:** `H-OPEN`.
**Steps:** 1. `RegisteredQueryInfo i = c.register("byuser", "SELECT user_id, SUM(amount) AS total
FROM txn GROUP BY user_id", List.of(0))`. 2. Push `("u1",100,"card")` and `("u1",102,"card")`.
3. `c.queries()`. 4. `c.query("SELECT * FROM byuser")`.
**Expected:** step 1 — `i.name()` is `"byuser"`; `i.state()` is `"RUNNING"`; `i.sql()` is the text as
sent; `i.fingerprint()` is non-blank; `i.rowsIn()` is `0`; `i.isRunning()` is `true`. Step 3 — the
entry for `byuser` now has `rowsIn() == 2`, so the `0` at step 1 is a placeholder and not a reading.
Step 4 — one row, `["u1", 100 + 102 = 202]`.
**Vacuity:** the `202` is hand-computed from two rows, so a view that was registered but not fed
returns zero rows and fails; and `rowsIn` moving from 0 to 2 shows the field is real elsewhere.

## SDKX-026 — `queries()` lists what is registered, and tolerates a short record
**Intent:** the `pravaha.list` action and the swallowed `NumberFormatException` at `:279`–`:283`,
which exists so an older server's four-field reply still parses.
**Falsifier:** a registered query is missing, or a short reply throws.
**Setup:** `H-OPEN` with three registrations: `q`, `byuser`, and `shared` sharing `q`'s fingerprint.
**Steps:** 1. `c.queries()`. 2. Compare against `pravaha queries --url grpc://localhost:19090`.
3. Feed a hand-built four-field LIST reply through the same parser.
**Expected:** step 1 returns three `RegisteredQueryInfo`s, one per **name** (not per computation), so
`q` and `shared` both appear with the **same** `fingerprint()`. Step 2's CLI output has the same
three names and the same states. Step 3 yields a record with `rowsIn() == 0` and no exception.
**Vacuity:** the shared pair is what distinguishes "lists names" from "lists computations", which are
different counts and is the thing an operator misreads.

## SDKX-027 — `pause`, `resume` and `drop` do what they say, and refuse what they should
**Intent:** three of the five control-wire actions, and their refusals.
**Falsifier:** a dropped query still answers, or a double drop succeeds silently.
**Setup:** `H-OPEN` with `byuser` registered and fed as in SDKX-025.
**Steps:** 1. `pause("byuser")`; `queries()`. 2. Push two more rows; `query("SELECT * FROM byuser")`.
3. `resume("byuser")`; push two more; query again. 4. `drop("byuser")`; `queries()`;
`query("SELECT * FROM byuser")`. 5. `drop("byuser")` again. 6. `pause("nosuch")`.
**Expected:** step 1 — state is `PAUSED`. Step 2 — the total is still `202`; a paused query drops
rows rather than buffering them. Step 3 — state `RUNNING`, and the total is
`202 + 100 + 102 = 404` if the same two values are pushed again, and the two pushed while paused are
**gone**, so the total is not `202 + 4 * 101`. Compute from the actual values pushed and show the
arithmetic. Step 4 — `byuser` is absent from `queries()`, and the query throws PRV-1041 whose
message contains the server's PRV-8002 and `no query named 'byuser' is registered`. Steps 5 and 6 —
the same PRV-8002-in-the-message refusal.
**Vacuity:** the pause arm is exactly the round-1 failure the brief warns about — a pause test that
passed because the source ran dry. Here rows are pushed *during* the pause and their absence from the
total after the resume is the assertion, so an idle source cannot produce a pass.

## SDKX-028 — `subscribe` streams changes, and the ticket is built by the client
**Intent:** the subscription path does not use `getFlightInfo`; the client fabricates
`ControlWire.subscribeTicket(view, pairs)` and calls `getStream` directly (`:327`). `Subscription.run()`
**blocks the calling thread**.
**Falsifier:** no batch arrives, or `run()` returns before `close()`.
**Setup:** `H-OPEN` with `q` registered.
**Steps:** 1. `Subscription s = c.subscribe("q", batch -> collected.addAll(batch.rows()))` on a
background thread calling `s.run()`. 2. Push `("u1",100,"card")`, commit. 3. Push
`("u2",250,"wire")`, commit. 4. Read `s.batches()` and `s.rows()`. 5. `s.close()`; join the thread.
6. Decode the ticket bytes the client sent.
**Expected:** two callbacks; `collected` holds two rows with values `["u1",100L,"card"]` and
`["u2",250L,"wire"]` in arrival order. `s.batches() == 2`, `s.rows() == 2`. `s.isClosed()` is false
before step 5 and true after; the `run()` thread returns within 5 s of `close()`. Step 6 — the ticket
decodes to `["subscribe", "q"]`, with `MAGIC = 0x50525648` and `VERSION = 1` in the framing. Note
there is **no** `pravaha.subscribe` action constant — subscribe rides `DoGet`.
**Vacuity:** two separate commits rather than one is what proves batches track commits; a single push
would pass for a client that delivered everything once at the end.

## SDKX-029 — a server PRV code does not reach the caller as a code
**Intent:** the central error-surfacing question the brief asks, answered directly. Every server
refusal is re-stamped PRV-1041.
**Falsifier:** `e.errorCode().code()` equals the server's code for any of these.
**Setup:** `H-AUTH` and `H-OPEN` as appropriate.
**Steps:** provoke each of these and record `errorCode().code()`, `retryable()`, and whether the
server's code appears in `getMessage()`:
(a) bad token → server PRV-7001; (b) `query("SELEKT 1")` → a SQL error, PRV-2xxx;
(c) `query("SELECT * FROM nosuchview")` → PRV-8002; (d) `register` with a duplicate name → PRV-8001;
(e) `register` with zero key columns → an `IllegalArgumentException` server-side;
(f) `drop("nosuch")` → PRV-8002; (g) a forbidden read under a restrictive policy → PRV-7002;
(h) a dead server → no server code at all.
**Expected:** every one of (a)–(h) gives `errorCode().code() == "PRV-1041"` and
`retryable() == false`. For (a)–(g) the server's code **is** present in `getMessage()`; for (h) it is
not. So the only way for a Java caller to branch on the server's code is
`e.getMessage().contains("PRV-7001")`, which is what the console does in Python
(`console/core/services.py:106`–`:113`, `_code_in`). Record this as the area's headline finding and
cross-reference SDKX-048 and SDKX-078.
**Vacuity:** eight provocations spanning five server error families is what makes "always 1041" a
measurement rather than one unlucky path.

## SDKX-030 — the configured timeouts are never applied
**Intent:** `ClientOptions` validates `connectTimeout` and `requestTimeout` and nothing reads them.
A caller who sets `requestTimeout(Duration.ofSeconds(2))` and then hangs for minutes has been told
something untrue.
**Falsifier:** a call against a black-holed address returns within the configured request timeout.
**Setup:** a TCP endpoint that accepts and never responds — `nc -l 9098` — plus `H-DEAD`'s
black-holed `10.255.255.1`.
**Steps:** 1. `grep -rn "connectTimeout\|requestTimeout\|withDeadline\|CallOptions.timeout"
sdk/pravaha-sdk-java-flight/src/main/java/`. 2. Connect to `grpc://10.255.255.1:19090` with
`connectTimeout(Duration.ofSeconds(1))` and time `queries()`. 3. Connect to `grpc://localhost:9098`
with `requestTimeout(Duration.ofSeconds(2))` and time `query("SELECT 1")`.
**Expected:** step 1 returns **zero** hits. Step 2 blocks far longer than 1 s — record the actual
figure; on a default Linux it is tens of seconds, governed by TCP SYN retries and not by the SDK.
Step 3 blocks indefinitely; abandon it after 60 s and report that no deadline fired. The `CallOption[]`
built at `:178` carries only the auth header, never `CallOptions.timeout`.
**Vacuity:** the grep bounds the claim and the two timings measure the cost; either alone is weaker —
a grep could miss a deadline set elsewhere, and a timing alone could be blamed on the network.

## SDKX-031 — a server killed mid-query surfaces as PRV-1041 and leaves a usable client
**Intent:** connection loss is not the same as a dead server at connect time; and a client that is
poisoned by one failure is a different defect from one that reports it.
**Falsifier:** the JVM hangs, or the client is unusable after the server returns.
**Setup:** `H-OPEN` with 200 000 rows in `q` so a full scan takes seconds.
**Steps:** 1. Start `query("SELECT * FROM q")` and begin iterating. 2. After the first batch,
`kill -9` the server. 3. Observe the iteration. 4. Restart the server, re-register `q`, re-push.
5. On the **same** client object, call `queries()` and `query(...)`.
**Expected:** step 3 throws `PravahaClientException` PRV-1041 mid-iteration, message containing
`UNAVAILABLE`; partial rows already delivered are not rolled back and the case records how many
arrived. Step 5 — record whether the same client recovers. gRPC channels reconnect, so it should; if
it does not, that is a finding, because there is no documented way to reset one short of `close()`
and a new `connect`.
**Vacuity:** 200 000 rows is what guarantees the kill lands mid-stream rather than between calls; with
3 rows the case degenerates into SDKX-015.

## SDKX-032 — a server killed mid-subscription ends the stream rather than hanging
**Intent:** the same question on the delivery path, where `run()` owns a thread.
**Falsifier:** `run()` never returns, or returns normally as though the stream had ended cleanly.
**Setup:** `H-OPEN`, a subscriber on `q` as in SDKX-028, a feeder pushing one row every 50 ms.
**Steps:** 1. Subscribe; let 20 batches arrive. 2. `kill -9` the server. 3. Wait up to 30 s for
`run()` to return or throw, recording which and how long it took. 4. Read `s.batches()`,
`s.rows()`, `s.isClosed()`. 5. Call `s.close()` after the fact.
**Expected:** `run()` terminates within 30 s. Record whether it returns normally or throws, and with
what — a clean return would be wrong, because it is indistinguishable from the server closing the
subscription deliberately, and a subscriber has no other signal. `batches()` and `rows()` hold the
counts delivered before the kill. `close()` after termination does not throw.
**Vacuity:** the 20 batches before the kill establish the stream was live; the distinction between
"returned" and "threw" is the actual finding and is recorded either way.

## SDKX-033 — closing a `Subscription` stops delivery and releases the thread
**Intent:** `Subscription.close()` cancels the Flight stream with
`"client closed the subscription"` and closes it if `run()` is not active. The two orderings are
different code paths.
**Falsifier:** `run()` blocks for ever after `close()`, or rows keep arriving.
**Setup:** `H-OPEN`, a feeder pushing one row every 20 ms.
**Steps:** Arm A: subscribe, `run()` on a background thread, `close()` from the test thread after 10
batches, join with a 5 s bound, then push 50 more rows and confirm none is delivered. Arm B:
subscribe and `close()` **without** ever calling `run()`; assert no thread is left and `isClosed()`
is true. Arm C: `close()` twice.
**Expected:** A — the thread ends within 5 s; `batches()` stops changing; the 50 later rows produce
no callback; the server's `ViewSink` listener count drops by one (check via `H-OPEN`'s metrics or a
second subscriber's behaviour). B — returns immediately, no thread created. C — the second `close()`
does not throw.
**Vacuity:** pushing 50 rows *after* the close and asserting zero callbacks is what makes "stopped"
checkable; asserting only that the thread ended would pass for a subscriber still registered on the
server, which is the leak that matters.

---

### C. `sdk/python`: an independent client over `pyarrow.flight` (SDKX-034–050)

The Python SDK is not a binding onto the Java one. It re-implements `Endpoint`, `ClientOptions`, the
error hierarchy, the `ControlWire` framing **and** enough of Flight SQL's protobuf to drive prepared
statements, all in `client.py`. That means every contract has two implementations that can drift, and
SDKX-050 is the case that measures the drift.

## SDKX-034 — `import pravaha` works without pyarrow, and `connect` imports it lazily
**Intent:** `pyproject.toml:25` declares `dependencies = []` and puts pyarrow behind the `flight`
extra; `__init__.py:20` makes `connect()` a shim that imports `pravaha.client` on call. So a
consumer can depend on the types without the 100 MB Arrow wheel.
**Falsifier:** `import pravaha` raises `ModuleNotFoundError` in a venv without pyarrow.
**Setup:** two virtualenvs on Python 3.9 and on 3.11: (a) `pip install ./sdk/python`; (b)
`pip install "./sdk/python[flight]"`.
**Steps:** In (a): 1. `import pravaha`. 2. `pravaha.Endpoint.parse("grpc://h:19090")`. 3.
`pravaha.ClientOptions.create("grpc://h:19090")`. 4. `pravaha.connect("grpc://h:19090")`. In (b):
5. all four again.
**Expected:** (a) steps 1–3 succeed; step 4 raises `ModuleNotFoundError` naming `pyarrow`. (b) all
four succeed. `pravaha.__all__` is exactly
`["ClientOptions", "connect", "Consistency", "Endpoint", "HostPort", "InvalidOptionsError",
"MalformedEndpointError", "PravahaError"]` and `pravaha.__version__` is `"0.1.0"`. Note that
`QueryError`, `ReadError` and `ConnectError` are defined in `client.py` and are therefore **not**
importable without pyarrow — a consumer writing `except pravaha.QueryError` in a types-only install
cannot. Record it.
**Vacuity:** running on 3.9 as well as 3.11 is required because `requires-python = ">=3.9"` while
the console needs `>=3.11`; a 3.9-only failure would otherwise go unseen.

## SDKX-035 — Python `Endpoint.parse` matches the Java one, row for row
**Intent:** two implementations of one contract. `endpoint.py:17`–`:18` declares the same two scheme
sets and the same `DEFAULT_PORT = 19090`, and defaults to TLS when the scheme is omitted (`:64`).
**Falsifier:** any row of SDKX-002's or SDKX-003's table resolves differently in Python.
**Setup:** `H-UNIT` (pytest).
**Steps:** 1. Run SDKX-002's nine inputs and SDKX-003's three through `Endpoint.parse`. 2. Run
SDKX-004's eleven malformed inputs. 3. Diff against the Java results recorded there.
**Expected:** identical `nodes()`/`tls()` for every well-formed input, including the omitted-scheme
TLS default and the `DEFAULT_PORT` fill-in. For malformed inputs, `MalformedEndpointError` with
`.code == 1030` (an **int**, not the string `"PRV-1030"` — see SDKX-048) and `str(e)` beginning
`PRV-1030  ` with two spaces. Any row where Java and Python differ is the finding. Also note
`endpoint.py:113` holds a dead `_unused` placeholder function — record it.
**Vacuity:** the diff against recorded Java results, rather than against an expectation written twice,
is what makes this a drift test rather than two independent tests that could both be wrong.

## SDKX-036 — Python `ClientOptions` defaults and refusals match the Java ones
**Intent:** `options.py:28`–`:40` and `__post_init__` at `:42`. The frozen dataclass validates the
same four things.
**Falsifier:** a default differs, or a refusal differs.
**Setup:** `H-UNIT`.
**Steps:** 1. `ClientOptions.create("grpc://h:19090")` and read all nine fields. 2. Repeat SDKX-006's
refusal arms. 3. `str(options)` with a token set. 4. `repr(options)`.
**Expected:** `token` `None`; `connect_timeout_seconds` `10.0`; `request_timeout_seconds` `30.0`;
`default_consistency` `Consistency.CONSISTENT`; `subscriber_buffer_rows` `10_000`;
`conflate_on_overflow` `True`; `application_name` `"pravaha-python-sdk"` — note this differs from
Java's `"pravaha-java-sdk"`, correctly; `allow_insecure_token` `False`. Refusals raise
`InvalidOptionsError` with `.code == 1031`. Steps 3 and 4: neither contains the token —
`token` is declared `field(default=None, repr=False)` (`:29`), so `repr` excludes it, and `__str__`
(`:72`) omits it explicitly. Assert both, because the `repr=False` covers `repr` only.
**Vacuity:** asserting `repr` as well as `str` is what catches a leak through the path a debugger or
a logging framework actually takes.

## SDKX-037 — Python refuses a token over plaintext, with the same wording
**Intent:** `options.py:57`–`:64`. The console defeats this (SDKX-080), so the check has to be shown
to exist first.
**Falsifier:** the options construct.
**Setup:** `H-UNIT`.
**Steps:** 1. `ClientOptions.create("grpc://h:19090", token="t")`. 2. The same with
`allow_insecure_token=True`. 3. `ClientOptions.create("grpc+tls://h:19090", token="t")`.
4. `ClientOptions.create("h:19090", token="t")`.
**Expected:** step 1 raises `InvalidOptionsError` code `1031`, message containing
`refusing to send a token over a plaintext connection to` and
`pass allow_insecure_token=True`. Steps 2, 3, 4 succeed. Diff the wording against Java's
(`call allowInsecureToken(true)`): the two differ by language idiom only, which is correct — record
both.
**Vacuity:** as SDKX-007.

## SDKX-038 — connect and round-trip against a live plaintext server
**Intent:** the Python equivalent of SDKX-013, over the same server and the same data, so the two
SDKs are directly comparable.
**Falsifier:** different rows, different types, or different ordering from SDKX-013.
**Setup:** `H-OPEN` with SDKX-013's three rows.
**Steps:** 1. `with pravaha.connect("grpc://localhost:19090") as c:`. 2. `r = c.query("SELECT
user_id, amount FROM q ORDER BY amount")`. 3. `r.columns`, `list(r)`, `r.to_list()`, `r.to_table()`.
4. For the first row: `row["user_id"]`, `row[0]`, `row.get("amount")`, `row.is_null("amount")`,
`row.to_dict()`, `len(row)`.
**Expected:** `r.columns` is `["user_id", "amount"]`. Three rows in the order `("u1",100)`,
`("u1",102)`, `("u2",250)`. `amount` is a Python `int`. `row["user_id"] == row[0] == "u1"` — the
`__getitem__` at `client.py:95` accepts a name **or** an ordinal, which Java's does not; assert both.
`row.is_null("amount")` is `False`. `row.to_dict()` is `{"user_id": "u1", "amount": 100}`.
`len(row) == 2`. `r.to_table()` is a `pyarrow.Table` with 3 rows. Also: `c.uri` is
`"grpc://localhost:19090"` (`client.py:222`).
**Vacuity:** comparing the values against SDKX-013's recorded Java results is the cross-check; the
two SDKs reading the same view differently would be invisible to either test alone.

## SDKX-039 — a Python connect to a dead server also succeeds, and the first call is `QueryError`
**Intent:** the Python mirror of SDKX-014/015. `pyarrow.flight.FlightClient(uri)` is lazy too, and
`_act` converts **everything** to `QueryError` (`client.py:424`–`:438`) — so `ConnectError`
(code 1040, `retryable=True`) is even less reachable here than in Java.
**Falsifier:** `connect` raises, or a call against a dead server raises `ConnectError`.
**Setup:** `H-DEAD`.
**Steps:** 1. `c = pravaha.connect("grpc://localhost:9099")`; time it. 2. `c.query("SELECT 1")`.
3. `c.queries()`. 4. `c.register("x", "SELECT user_id FROM txn", [0])`. 5. `c.drop("x")`.
6. `list(c.subscribe("q"))`.
**Expected:** step 1 returns in under 100 ms with no exception. Steps 2–6 all raise `QueryError` with
`.code == 1041` and `.retryable == False`; none raises `ConnectError`. `str(e)` begins `PRV-1041  `
and contains the pyarrow status text. Record: `ConnectError` is raised only from `__init__`'s own
`except` (`client.py:~208`), i.e. when `FlightClient(uri)` itself throws, which lazy construction
makes near-unreachable — the same shape as SDKX-016.
**Vacuity:** six entry points, as in SDKX-015.

## SDKX-040 — Python authentication: valid, invalid, missing
**Intent:** `client.py:210`–`:215` builds one `FlightCallOptions` with
`headers=[(b"authorization", f"Bearer {token}".encode())]` and reuses it for every call.
**Falsifier:** a wrong token succeeds, or the header differs from Java's.
**Setup:** `H-AUTH` with the header-recording middleware of SDKX-021.
**Steps:** 1. Valid token with `allow_insecure_token=True`; `c.queries()` and `c.query(...)`.
2. `wrong-token-0002`; the same calls. 3. No token; the same calls. 4. Read the recorded headers for
all three.
**Expected:** 1 succeeds, audited as `ann`/`acme`/`[reader]`. 2 and 3 raise `QueryError` code `1041`
whose message contains the server's `PRV-7001`. 4 — arms 1 and 2 send exactly
`authorization: Bearer <token>`, byte-identical in key and prefix to SDKX-021's Java capture; arm 3
sends **no** authorization header (`FlightCallOptions()` with no `headers`). Without
`allow_insecure_token=True`, arm 1 raises `InvalidOptionsError` 1031 before any call — assert that as
a sub-arm.
**Vacuity:** the byte-identical comparison with the Java capture is what makes this a drift test.

## SDKX-041 — Python `register`, `queries`, `pause`, `resume`, `drop`
**Intent:** the four control-wire actions through the Python re-implementation of the framing
(`_wire_encode` at `client.py:495`).
**Falsifier:** any action fails against a server the Java client drives successfully.
**Setup:** `H-OPEN`.
**Steps:** 1. `c.register("pybyuser", "SELECT user_id, SUM(amount) AS total FROM txn GROUP BY
user_id", [0])`. 2. Push `("u1",100,"card")` and `("u1",102,"card")`. 3. `c.queries()`.
4. `c.query("SELECT * FROM pybyuser")`. 5. `c.pause("pybyuser")`; push two more; query. 6.
`c.resume(...)`; push two more; query. 7. `c.drop("pybyuser")`; `c.queries()`; query.
8. `c.drop("pybyuser")` again and `c.pause("nosuch")`.
**Expected:** step 1 returns a `RegisteredQuery` dataclass with `name="pybyuser"`, `state="RUNNING"`,
`is_running` `True`, `rows_in=0`. Step 3 lists it. Step 4 gives one row, `100 + 102 = 202`. Step 5's
total is still `202`. Step 6's total is `202` plus exactly what was pushed after the resume — show the
arithmetic for the values used. Step 7: absent from `queries()`, and the query raises `QueryError`
1041 whose message carries `PRV-8002`. Step 8: the same refusal twice.
**Vacuity:** as SDKX-027, the rows pushed *during* the pause are what make the pause assertion
non-vacuous.

## SDKX-042 — a parameterised Python query drives the prepared-statement protocol by hand
**Intent:** `_query_with_parameters` (`client.py:254`) hand-encodes protobuf: `_statement_command`,
`_create_prepared_request`, `_prepared_command`, `_parse_prepared_result`, `_parse_doput_result`,
`_read_varint`, `_varint`. Four round trips, and the handle is rewritten mid-flight. This is the most
fragile code in either SDK.
**Falsifier:** the filter is ignored, or the result differs from the Java path's (SDKX-024).
**Setup:** `H-OPEN` with SDKX-013's three rows.
**Steps:** 1. `c.query("SELECT user_id, amount FROM q WHERE amount > ?", [150])`. 2. The same with
`["card"]` against `product_type = ?`, with `[150.0]`, `[True]`, `[None]`, and `[b"\x01"]`. 3. A
parameter count that does not match the placeholders, both too few and too many. 4. A 1 MiB string
parameter (`StatementHandle.MAX_PARAMETER_BYTES = 1 << 20`). 5. Compare every result against the
Java results recorded in SDKX-024.
**Expected:** step 1 — exactly one row, `["u2", 250]`, because `250 > 150` and `100 > 150`,
`102 > 150` are false. The `"card"` arm — two rows. Record acceptance or refusal for the remaining
types. Step 3 — refusals; record the code and message and compare with Java's. Step 4 — at 1 MiB
exactly, record whether the server's `MAX_PARAMETER_BYTES` boundary refuses or accepts, and at
`1 MiB + 1` expect a refusal. Step 5 — any divergence from SDKX-024 is the finding.
**Vacuity:** `150` gives one of three rows, so an unbound parameter returns three and is caught.

## SDKX-043 — Python `subscribe` is a generator: nothing happens until it is iterated
**Intent:** `subscribe` (`client.py:370`) returns `Iterator[list[Row]]`. Calling it does no work, so
a consumer who calls it and stores the result has subscribed to nothing.
**Falsifier:** rows arrive without iteration, or the first `next()` misses rows pushed between the
call and the iteration.
**Setup:** `H-OPEN` with `q` registered.
**Steps:** 1. `it = c.subscribe("q")` — do not iterate. 2. Push `("u1",100,"card")` and commit.
3. Wait 1 s; assert nothing has been received. 4. `next(it)` and record what arrives. 5. Push
`("u2",250,"wire")`; `next(it)` again. 6. Close the generator (`it.close()`), push a third row, and
assert nothing more arrives.
**Expected:** step 3 — no server-side subscriber exists yet; check via the server's subscriber count.
Step 4 — record whether the row pushed at step 2 is delivered. It is the important observation: a
generator that subscribes at first `next()` **misses everything pushed between the call and the
iteration**, and a consumer reading `subscribe()`'s signature has no way to know. Step 5 — the second
row arrives. Step 6 — the generator's `GeneratorExit` path is taken (`client.py` re-raises
`KeyboardInterrupt`/`GeneratorExit` untouched) and the server-side subscriber goes away.
**Vacuity:** the 1 s gap at step 3 with a committed row is what makes the gap observable; pushing only
after the first `next()` would hide it.

## SDKX-044 — a slow Python subscriber is conflated, not blocked
**Intent:** `SubscriptionOptions.DEFAULT` is `(10_000, CONFLATE)` server-side, and
`ClientOptions.subscriber_buffer_rows`/`conflate_on_overflow` mirror it client-side — but the
client-side pair, like the timeouts, may never reach the server. Find out.
**Falsifier:** the engine's ingest rate collapses while the subscriber is slow, or the client-side
options change the server's behaviour.
**Setup:** `H-OPEN` with a keyed view so conflation has something to collapse onto. A Python
subscriber that sleeps 100 ms per batch. A feeder pushing 50 000 rows across 500 keys.
**Steps:** 1. Record the engine's ingest rate with no subscriber. 2. Attach the slow subscriber;
record the rate again. 3. Count rows delivered to the subscriber and compare with 50 000. 4. Repeat
with `subscriber_buffer_rows=10` and with `conflate_on_overflow=False`, and see whether either
changes anything observable. 5. Read the server's `conflated`/`dropped` counters.
**Expected:** step 2 — the ingest rate does not collapse; the class javadoc is explicit that
"Blocking is not on the list". Step 3 — fewer than 50 000 rows arrive, and the difference equals the
server's `conflated + dropped`. Step 4 — record whether the two client options have **any** effect;
if `grep -rn "subscriber_buffer_rows\|conflate_on_overflow" sdk/python/pravaha/client.py` is empty
then they are inert, which is the finding, and it parallels SDKX-030.
**Vacuity:** step 1 is the control rate; 500 keys rather than 1 is what gives conflation something to
do without collapsing 50 000 rows into a single visible change (the round-1 failure the brief cites).

## SDKX-045 — Python error surfacing: `.code` is an int, and the server's code is only in the text
**Intent:** `PravahaError.__init__(self, code: int, ...)` sets `self.code` to `1041`, not
`"PRV-1041"`, and formats the message as `f"PRV-{code}  {message}"` (`errors.py:22`). A consumer
matching on `e.code == "PRV-7001"` never matches.
**Falsifier:** `.code` is a string, or a server code reaches it.
**Setup:** SDKX-029's eight provocations, in Python.
**Steps:** 1. Provoke each; record `type(e).__name__`, `e.code`, `e.retryable`, `e.help_url`,
`str(e)`.
**Expected:** every one is `QueryError` with `e.code == 1041` (int), `e.retryable is False`,
`e.help_url == "https://docs.pravaha.io/errors/PRV-1041"`, and `str(e)` beginning `PRV-1041  `. The
server's code appears only inside the text. `ReadError` (1042) arises from the row accessors, and
`ConnectError` (1040) from none of these. Compare with SDKX-029's Java table: same conclusion, two
different `.code` representations — Java's `errorCode().code()` is the string `"PRV-1041"` and
Python's `.code` is the int `1041`. A polyglot consumer writing one error-handling rule cannot.
**Vacuity:** the eight-way sweep, and the explicit type check on `e.code`.

## SDKX-046 — Python timeouts are also never applied
**Intent:** the mirror of SDKX-030. `FlightCallOptions(timeout=...)` is never constructed.
**Falsifier:** a call returns within `request_timeout_seconds` against a black hole.
**Setup:** as SDKX-030.
**Steps:** 1. `grep -n "timeout" sdk/python/pravaha/client.py`. 2. Connect to
`grpc://10.255.255.1:19090` with `connect_timeout_seconds=1.0`; time `c.queries()`. 3. Connect to
the accept-and-never-answer socket with `request_timeout_seconds=2.0`; time `c.query("SELECT 1")`.
**Expected:** step 1 returns nothing. Steps 2 and 3 block far past the configured values; record the
elapsed times and compare with SDKX-030's Java figures.
**Vacuity:** as SDKX-030.

## SDKX-047 — a server killed mid-call and mid-subscription, in Python
**Intent:** the mirror of SDKX-031 and SDKX-032, because the exception mapping differs: pyarrow maps
Flight statuses onto several classes (`ArrowInvalid` for `INVALID_ARGUMENT`, `FlightError`
otherwise) and `_act` catches broadly while `query` catches `FlightError` then bare `Exception`.
**Falsifier:** the process hangs, or an exception escapes that is not a `PravahaError`.
**Setup:** `H-OPEN` with 200 000 rows, plus a subscriber.
**Steps:** 1. Start iterating a large `query`; `kill -9` the server mid-stream. 2. Record the
exception type and message, and how many rows had arrived. 3. Start a subscription; let 20 batches
arrive; kill the server; record whether the generator raises, ends, or hangs, and how long it takes.
4. Restart the server and reuse the same `Client` object.
**Expected:** step 2 — `QueryError` code `1041`; **not** a bare `pyarrow.lib.ArrowException` or
`FlightUnavailableError`, because the bare `except Exception` at `client.py:~256` catches it. Record
the count delivered. Step 3 — the generator must not hang; record raise-versus-end within 30 s and
compare with SDKX-032's Java result. Step 4 — record whether the client recovers.
**Vacuity:** as SDKX-031, the row volume is what forces the kill to land mid-stream.

## SDKX-048 — the two clients disagree about what an error *is*
**Intent:** collect SDKX-029's and SDKX-045's results into one comparison, because this is what a
consumer with both SDKs actually experiences and no single-language case states it.
**Falsifier:** the two representations agree.
**Setup:** the recorded results of SDKX-029 and SDKX-045.
**Steps:** 1. Build the comparison table.
**Expected:**

| | Java | Python |
|---|---|---|
| type for a server refusal | `PravahaClientException` | `QueryError` |
| code accessor | `errorCode().code()` → `"PRV-1041"` | `.code` → `1041` (int) |
| numeric accessor | `errorCode().number()` → `1041` | `.code` |
| symbolic name | `errorCode().name()` → `"CLIENT_QUERY_REFUSED"` | *absent* |
| retryable | `retryable()` | `.retryable` |
| help URL | `helpUrl()` | `.help_url` |
| server's code | message text only | message text only |
| dead server | `PRV-1041`, `retryable=false` | `1041`, `retryable=False` |
| unreachable code | `CLOSED` (1043) | `ConnectError` (1040) in practice |

The symbolic-name row is the one to act on: Python has no equivalent of `ErrorCode.name()`, so a
Python consumer cannot even name the error it caught.
**Vacuity:** n/a — a comparison of recorded results.

## SDKX-049 — the Python SDK re-implements the control wire, and the two can drift
**Intent:** `_wire_encode` (`client.py:495`), `_wire_decode` (`:507`) and `_subscribe_ticket`
(`:527`) duplicate `ControlWire`'s magic, version and framing. There is no shared definition and no
test that compares them.
**Falsifier:** a payload encoded by one cannot be decoded by the other.
**Setup:** `H-UNIT` in both languages, plus a corpus of field lists: `[]`, `[""]`, `["a"]`,
`["a","b"]`, `["subscribe","q"]`, `["subscribe","q","col","val"]`, a list of 1024 fields, a list of
1025 fields, a field containing a NUL byte, a field of 1 MiB, a field of non-ASCII UTF-8 (`"café"`,
`"日本"`), and a field containing a newline.
**Steps:** 1. Encode each in Java, decode in Python. 2. Encode each in Python, decode in Java.
3. Compare the raw bytes of the two encodings. 4. Grep both for the magic constant.
**Expected:** steps 1 and 2 round-trip identically for every corpus entry that both accept. Step 3 —
byte-for-byte identical encodings. The 1025-field entry must be **refused by the decoder** on both
sides: `ControlWire.decode` refuses `count > 1024` with `this Pravaha request is malformed`
(`ControlWire.java:119`–`:121`); assert Python refuses it too, and if it does not, that is the
finding. Step 4 — `0x50525648` appears in `ControlWire.java:53` and again in `client.py`; two
declarations, no shared source, no cross-language test. Record it.
**Vacuity:** the 1024/1025 boundary pair and the non-ASCII entries are the ones a hand-written
re-implementation gets wrong; a corpus of plain ASCII two-field lists would round-trip under any
implementation.

## SDKX-050 — the Python test suite's own gaps
**Intent:** name what is not covered, so a later wave does not assume it is.
**Falsifier:** a test exists for any of these.
**Setup:** `sdk/python/tests/`.
**Steps:** 1. List the test files. 2. Grep them for `tls`, `timeout`, `consistency`, `subscribe`,
`ConnectError`.
**Expected:** four files — `test_authentication.py`, `test_client.py`, `test_endpoint.py`,
`test_options.py`. There is **no** test for `consistency.py`, none that exercises TLS, none that
asserts a timeout is honoured, and none for the `ConnectError` path. Java's four Flight tests
(`JavaSdkAuthenticationTest`, `JavaSdkParameterTest`, `JavaSdkQueryTest`, `JavaSdkRegistryTest`) have
the same gaps, and `JavaSdkQueryTest:80`
(`omittingTheSchemeMeansTlsAndSoFailsAgainstAPlaintextServer`) is the **only** test in the repository
that touches a TLS code path — and it asserts failure, with
`isInstanceOf(PravahaClientException.class)` and no assertion on the code or message.
**Vacuity:** n/a — an enumeration, and the setup for Section D.

---

### D. TLS: configured, logged, and unreachable (SDKX-051–060)

The server can be told to serve TLS. It will log that it is. No client in this repository can
complete the handshake, and none can be given a CA. Ten cases, because the failure has several
independent causes and a fix for one does not fix the others.

## SDKX-051 — a TLS node starts, logs TLS, and binds its port
**Intent:** establish that the server half is configured and running, so that every client failure
below is attributable to the client.
**Falsifier:** the node refuses to start, or logs `PLAINTEXT`.
**Setup:** `H-TLS`.
**Steps:** 1. Start the node. 2. Grep the log for `flight transport=`. 3. `ss -ltn | grep 19090`.
4. `openssl s_client -connect localhost:19090 -alpn h2 -servername localhost` with `-CAfile cert.pem`.
5. `PravahaFlightServer.isEncrypted()` via the status endpoint or a test hook.
**Expected:** step 2 — `security: authentication=token, policy=authenticated, audit=..., flight
transport=TLS` (`PravahaNode.java:350`). Step 3 — listening. Step 4 — the handshake **succeeds**;
`openssl` prints the self-signed certificate with `CN=localhost` and negotiates `h2`. So the server's
TLS is real. Step 5 — `true`. Also assert the startup log does **not** contain the plaintext warning
at `PravahaNode.java:189` (`credentials travel in the clear`), which fires only when authentication
is on and no certificate is set.
**Vacuity:** the `openssl` handshake is the control that makes every client failure below a client
defect; without it, a broken certificate would produce the same symptoms.

## SDKX-052 — the netty artefact that makes the client side of TLS work is in `pravaha-server` only
**Intent:** the root cause, established from the dependency graph rather than from a symptom. The
artefact is **`io.netty:netty-transport-native-unix-common:4.1.135.Final`**, scope `runtime`,
declared in exactly one pom.
**Falsifier:** the artefact appears on the `pravaha-flight`, `pravaha-cli` or
`sdk/pravaha-sdk-java-flight` trees.
**Setup:** the repository.
**Steps:** 1. `grep -rn "io.netty" --include=pom.xml .`. 2.
`./mvnw -o -pl pravaha-server dependency:tree | grep netty`. 3. The same for `pravaha-flight`,
`pravaha-cli`, `sdk/pravaha-sdk-java-flight`. 4. `grep netty pravaha-flight/target/test-classpath.txt`.
5. Read the comment at `pravaha-server/pom.xml:119`–`:131`.
**Expected:** step 1 returns two hits: the declaration at `pravaha-server/pom.xml:143`–`:148` and the
*exclusion* in `sdk/pravaha-sdk-java/pom.xml:46`. Step 2 shows
`netty-transport-native-unix-common:4.1.135.Final (runtime)`, `netty-handler:4.1.135.Final`,
`netty-tcnative-boringssl-static:2.0.77.Final`. Step 3 shows, for all three,
`netty-handler:4.2.9.Final` and `netty-tcnative-boringssl-static:2.0.74.Final` and **no**
`netty-transport-native-unix-common`. Step 4 confirms it on a classpath that was actually built.
Step 5 is the prediction to test in SDKX-053: *"a TLS node starts, logs that it is serving TLS, binds
its port, and answers nobody: every connection dies with NoClassDefFoundError while the SSL handler
is stripped from the pipeline."*
**Refinement to record:** the missing piece is **not** `netty-tcnative` — the TLS crypto provider is
present on every tree. It is `io.netty.channel.unix.*`, which the gRPC SSL pipeline reaches. So the
expected runtime failure is a `NoClassDefFoundError`, not a cipher or provider error, and a case that
asserts on "TLS not supported" text will not match.
**Second finding:** `pravaha-server` resolves netty **4.1.135** and `pravaha-flight` resolves
**4.2.9**. The shipped jar is assembled from the server module, so production runs 4.1.135, while
every test in `pravaha-flight` and the SDK runs 4.2.9. The TLS path is not tested on the netty version
it ships on.
**Vacuity:** step 4's built classpath is the control against a `dependency:tree` that reports a
different graph from the one the build actually used.

## SDKX-053 — the Java Flight SDK cannot complete a TLS handshake
**Intent:** the prediction of SDKX-052, executed.
**Falsifier:** a query over `grpc+tls://` returns rows.
**Setup:** `H-TLS`. The client run with the JDK truststore extended to trust `cert.pem`
(`keytool -importcert` into a copy of `cacerts`, passed with
`-Djavax.net.ssl.trustStore=...`), so that certificate trust is **not** the variable.
**Steps:** 1. `connect(ClientOptions.builder("grpc+tls://localhost:19090")
.token("correct-horse-battery-staple-0001").build())`. 2. `queries()`. 3. Capture the full stack
trace, including causes and suppressed. 4. Repeat with the netty artefact added to the client
classpath by hand and record whether it then works.
**Expected:** step 1 succeeds (lazy, SDKX-014). Step 2 fails. Record precisely what escapes: a
`NoClassDefFoundError` for `io/netty/channel/unix/...` is an `Error`, **not** a
`FlightRuntimeException`, so neither `catch` in `PravahaFlightClient` (`:194`, `:234`, `:345`) sees it
— it propagates raw, with no PRV code, no `retryable()` flag and no diagnosis. Assert that the escaping
throwable is not a `PravahaClientException`. Step 4 is the control: with the artefact present, the
same call must succeed against the same server, which attributes the failure to the dependency and
nothing else.
**Vacuity:** step 4 is the whole case. Without it, a handshake failure could be the certificate, the
ALPN, the token, or the server — and the extended truststore already removes one of those.

## SDKX-054 — the Python SDK against the same TLS server
**Intent:** Python's Flight client has its own netty-free stack (it is C++ under pyarrow), so the
failure mode differs and must be measured rather than assumed.
**Falsifier:** an untested assumption either way.
**Setup:** `H-TLS`.
**Steps:** 1. `pravaha.connect(options=ClientOptions.create("grpc+tls://localhost:19090",
token="correct-horse-battery-staple-0001"))`. 2. `c.queries()`. 3. Repeat with the certificate
installed in the system CA store (`update-ca-certificates` or `SSL_CERT_FILE`). 4. Repeat against a
server whose certificate chains to a public root.
**Expected:** step 2 fails at handshake with a pyarrow TLS error, wrapped by `_act` into
`QueryError` code `1041` — record the underlying text. Step 3: record whether the system CA store is
consulted; if `pyarrow.flight` uses gRPC's bundled roots rather than the OS store, it will not be, and
that is the finding. Step 4 is the only configuration that can work, and confirms the handshake
machinery itself is sound. So: Python may be able to speak TLS to a publicly-signed server, which
Java currently cannot at all — a real asymmetry between the two SDKs, and the case records which side
of it each is on.
**Vacuity:** step 4 separates "TLS is broken" from "this certificate is not trusted", which steps 1–3
alone cannot.

## SDKX-055 — the CLI against the same TLS server
**Intent:** `pravaha` is the first-party client an operator reaches for, and it shares the Java Flight
SDK's classpath.
**Falsifier:** the CLI connects.
**Setup:** `H-TLS`.
**Steps:** 1. `pravaha queries --url grpc+tls://localhost:19090 --token
correct-horse-battery-staple-0001`. 2. Record stdout, stderr and the exit code. 3. `pravaha queries
--url localhost:19090 --token ...` (scheme omitted, so TLS, SDKX-002). 4. `pravaha --help` and grep
for `tls`.
**Expected:** step 1 fails. Record whether the operator sees a PRV code and an actionable message or
a raw stack trace — given SDKX-053, expect the latter, and a non-zero exit. Step 3 fails the same
way. Step 4: the help text (`PravahaCli.java:121`–`:133`) offers `--url` and `--token` and **no**
`--tls`, `--tls-ca` or `--insecure`; `grep -rn "trustedCertificates\|--tls\|tls-ca"
pravaha-cli/src/main/java/` returns nothing. So a TLS deployment is unreachable from its own CLI and
there is no flag to fix it.
**Vacuity:** step 4's grep bounds the claim: "no flag" is checked, not inferred from the help text.

## SDKX-056 — there is no API for a custom CA in any of the three clients
**Intent:** the second, independent reason TLS does not work. Even with SDKX-052 fixed, a private CA
or self-signed certificate cannot be trusted from any SDK, and most internal deployments use one.
**Falsifier:** any client exposes a truststore, CA file, certificate, or verification switch.
**Setup:** the three modules.
**Steps:** 1. Enumerate `ClientOptions.Builder`'s methods. 2. Enumerate `ClientOptions`' Python
fields. 3. `grep -rn "trustedCertificates\|verifyServer\|tls_root_certs\|cert_chain\|private_key\|
override_hostname\|disable_server_verification" sdk/`. 4. Check what `Location.forGrpcTls` and
`FlightClient(uri)` use for trust.
**Expected:** step 1 — eight methods: `allowInsecureToken`, `token`, `connectTimeout`,
`requestTimeout`, `defaultConsistency`, `subscriberBufferRows`, `conflateOnOverflow`,
`applicationName`. None about TLS. Step 2 — nine fields, none about TLS. Step 3 — **zero** hits, even
though `FlightClient.Builder.trustedCertificates(...)` and `.verifyServer(false)` exist upstream and
`pyarrow.flight.FlightClient` accepts `tls_root_certs`. Step 4 — Java uses the JVM default
truststore; Python uses whatever gRPC's bundled roots are. Conclusion: the only TLS deployment any
shipped client can reach is one whose certificate chains to a public root already in the JDK
`cacerts` — which for an internal Flight port is the unusual case, not the usual one.
**Vacuity:** naming the upstream methods that exist and are not called is what makes this a gap
rather than a limitation of the underlying library.

## SDKX-057 — omitting the scheme means TLS, and so fails against a plaintext server
**Intent:** the accidental path, and the one existing test that touches TLS at all
(`JavaSdkQueryTest:80`). That test asserts only `isInstanceOf(PravahaClientException.class)`; this
case pins the code and the message, which is what an operator will actually see.
**Falsifier:** the message does not mention TLS or the scheme.
**Setup:** `H-OPEN` (plaintext) on 19090.
**Steps:** 1. Java: `connect("localhost:19090")`, then `queries()`. 2. Python: the same.
3. CLI: `pravaha queries --url localhost:19090`. 4. For each, record the full message chain.
**Expected:** all three fail. Java gives `PravahaClientException` PRV-1041 whose message is the gRPC
status description — record it, and record whether it says anything about TLS. If it does not, that
is the finding: the most likely single mistake a new user makes (omitting the scheme) produces a
message that does not name its cause, while `Endpoint`'s javadoc is the only place the rule is
written down. Step 3 is the control for the CLI's opposite default: `--url localhost:19090` is TLS
because it goes through `ClientOptions.builder(url)`, whereas the CLI's *default* when `--url` is
omitted is the literal string `grpc://localhost:19090`, which is plaintext and works. Two defaults,
two directions, one flag.
**Vacuity:** the three clients and the CLI's two paths are what show the inconsistency is in the
contract, not in one implementation.

## SDKX-058 — a plaintext client against a TLS server
**Intent:** the mirror of SDKX-057, and the failure an operator hits after turning TLS on and
forgetting to update one client.
**Falsifier:** the call hangs indefinitely, or succeeds.
**Setup:** `H-TLS`.
**Steps:** 1. Java: `connect("grpc://localhost:19090")`, `queries()`. 2. Python: the same.
3. `pravaha queries --url grpc://localhost:19090`. 4. Time each.
**Expected:** all three fail. Record the message and the elapsed time. With no deadline (SDKX-030),
the plaintext client sending HTTP/2 preface bytes into a TLS listener may block until the server or
the OS gives up — measure it. Anything over a few seconds is a finding, because this is the
first thing that happens in a rolling TLS enablement and an operator will read a long hang as a
network problem.
**Vacuity:** the timing is the measurement; "it fails" is already known from SDKX-051's `openssl`
control.

## SDKX-059 — the CLI's `--token` disables the SDK's own plaintext refusal
**Intent:** `ServerCommand.java:193`–`:197`:
`args.get("token").ifPresent(token -> options.token(token).allowInsecureToken(true));`. The SDK
refuses to send a token over plaintext (SDKX-007); its own first-party consumer switches that off
**unconditionally**, for every URL, including a remote one.
**Falsifier:** `pravaha query --token secret --url grpc://remote:19090` is refused, or warns.
**Setup:** `H-AUTH` on a second host (or a loopback alias that is not `127.0.0.1`), plus a packet
capture on the path.
**Steps:** 1. `pravaha queries --url grpc://<remote>:19090 --token
correct-horse-battery-staple-0001`. 2. Capture the traffic. 3. Check stdout and stderr for any
warning. 4. Read the CLI's default URL when `--url` is omitted.
**Expected:** step 1 **succeeds**. Step 2 — the token is visible in cleartext in the gRPC metadata on
the wire; extract it from the capture and show it matches. Step 3 — **no warning**, on either stream.
Step 4 — the default is `grpc://localhost:19090`, plaintext (`ServerCommand.java:194`), which is the
opposite default from the SDK's (SDKX-002 row 6). Cross-reference `docs/project/qa/logs/SEC.md:1557`, where
this was already recorded. The finding is not that loopback is exempted — it is that nothing checks
whether the connection is loopback, so the exemption applies to every host.
**Vacuity:** using a non-loopback address is essential; on `127.0.0.1` the behaviour would be
defensible and the case would prove nothing.

## SDKX-060 — the server's TLS wiring has two defects of its own
**Intent:** two small things in `PravahaFlightServer` that a TLS deployment meets immediately.
**Falsifier:** `location()` reports `grpc+tls` under TLS, and `encryptedWith(cert, null)` is refused
with a message.
**Setup:** `H-TLS`, plus unit-level access to `PravahaFlightServer`.
**Steps:** 1. Start with TLS; read `server.location()`. 2. `new PravahaFlightServer(views)
.encryptedWith(cert, null)`. 3. `encryptedWith(missingFile, key)` and `encryptedWith(cert,
missingFile)`. 4. `encryptedWith(directory, key)`.
**Expected:** step 1 — the recorded location is `Location.forGrpcInsecure(host, port)`
(`PravahaFlightServer.java:222`), i.e. it reports `grpc://` **even under TLS**. Anything advertising
this location to another node or writing it into a log tells a lie. Step 2 — **`NullPointerException`**,
because `privateKey` is dereferenced by `privateKey.isFile()` without a null check (`:119`–`:127`);
already recorded at `docs/project/qa/logs/SEC.md:1487`. Steps 3 and 4 — `PravahaException`
`FlightErrors.TLS_UNREADABLE` with
`the TLS certificate <absolute path> is not a readable file` or
`the TLS private key <absolute path> is not a readable file`. Also assert: `verifyClient` /
client-certificate trust is configured nowhere, so there is **no mTLS** and no server-side
`trustedCertificates` call either.
**Vacuity:** steps 3 and 4 are the controls — they show the validation exists and works, which makes
step 2's NPE a gap in it rather than an absence of validation.

---

### E. The console (SDKX-061–080)

FastAPI, Uvicorn, Jinja2, Bootstrap vendored, server-rendered — not a SPA. It consumes the published
Python SDK (ADR-024), which makes it the fourth client and the only one with a user interface. Four
route modules, registered in the order
`ALL_ROUTES = (AuthRoutes, PublicRoutes, ApiRoutes, UIRoutes)` (`console/routes/__init__.py:16`),
"because `/queries/{name}` would swallow later literals".

## SDKX-061 — every route module registers every path it is supposed to, and nothing else
**Intent:** the enumeration the brief asks for, done once so the rest of the section can name routes
rather than describe them.
**Falsifier:** a path in the source is not served, or a served path is in no module.
**Setup:** `H-CON`.
**Steps:** 1. Read `app.routes` (or `GET /api/docs` and the OpenAPI JSON). 2. Compare against the
four route files. 3. Request each path and record the status.
**Expected:** exactly these, and no others beyond `/static/*` and the OpenAPI docs:

| module | method | path | auth |
|---|---|---|---|
| `auth_routes.py:63` | GET | `/login` | open |
| `auth_routes.py:68` | POST | `/login` | open |
| `auth_routes.py:87` | GET | `/logout` | open |
| `public_routes.py:27` | GET | `/` | open |
| `public_routes.py:38` | GET | `/about` | open |
| `public_routes.py:98` | GET | `/help` | open |
| `public_routes.py:103` | GET | `/help/{slug}` | open |
| `public_routes.py:107` | GET | `/tutorials` | open |
| `public_routes.py:111` | GET | `/tutorials/{slug}` | open |
| `public_routes.py:116` | GET | `/health` | open |
| `public_routes.py:133` | GET | `/health/live` | open |
| `public_routes.py:143` | GET | `/health/ready` | open |
| `api_routes.py:48` | GET | `/api/v1/health` | open |
| `api_routes.py:52` | GET | `/api/v1/queries` | open |
| `api_routes.py:58` | GET | `/api/v1/queries/{name}` | open |
| `api_routes.py:66` | POST | `/api/v1/queries` | **401 anon** |
| `api_routes.py:77` | POST | `/api/v1/queries/{name}/{action}` | **401 anon** |
| `api_routes.py:87` | POST | `/api/v1/query` | **401 anon** |
| `api_routes.py:95` | GET | `/api/v1/stats` | open |
| `api_routes.py:114` | GET | `/api/v1/views/{view}/stream` | open (SSE) |
| `ui_routes.py:66` | GET | `/overview` | open |
| `ui_routes.py:74` | GET | `/queries` | open |
| `ui_routes.py:86` | GET | `/queries/{name}` | open |
| `ui_routes.py:103` | POST | `/queries/{name}/{action}` | redirect |
| `ui_routes.py:124` | GET | `/workbench` | open |
| `ui_routes.py:129` | POST | `/workbench` | redirect |
| `ui_routes.py:146` | POST | `/queries` | redirect |

Plus `/static` mounted at `run_pravaha_web.py:64` and the docs at `/api/docs` (`:53`). Also assert the
ordering claim: `GET /queries/workbench` must **not** be captured by `/queries/{name}` — request it
and record which handler answers, because `UIRoutes` registers `/queries/{name}` at `:86` before
`/workbench` at `:124`, and a literal that looked like a name is exactly the collision the comment
warns about.
**Vacuity:** the ordering probe is the non-trivial half; the table alone is a listing.

## SDKX-062 — signing in with the configured password
**Intent:** `auth_routes.py:68`–`:85`. A single shared password, compared with
`hmac.compare_digest`, then `request.session["user"] = "operator"` and a 303.
**Falsifier:** the correct password is refused, or the session is not established.
**Setup:** `H-CON` with `CONSOLE_PASSWORD=letmein`.
**Steps:** 1. `GET /login`; record the form and any CSRF token. 2. `POST /login` with
`password=letmein`. 3. Follow the redirect. 4. `GET /queries` and confirm the page shows a signed-in
state. 5. `GET /logout`, then repeat step 4.
**Expected:** step 2 returns **303** with `Location: /overview` (the default `next`) and a
`Set-Cookie` for the Starlette session. Step 4's HTML contains the signed-in affordances — the
lifecycle buttons on `/queries` — because `brand()` injects `signed_in` into every template
(`routes/base.py:131`). Step 5 clears the session and step 4 repeated shows the anonymous state.
**Vacuity:** step 5 is the control; without it a page that always renders the signed-in affordances
would pass.

## SDKX-063 — a wrong password is 401, not a redirect
**Intent:** `auth_routes.py:80` renders `login.html` with status 401. A redirect would make a failed
sign-in indistinguishable from a successful one to anything scripted.
**Falsifier:** the response is 200 or 303.
**Setup:** `H-CON`.
**Steps:** 1. `POST /login` with `password=wrong`. 2. Read the status, the body and the cookies.
3. `GET /queries` with whatever cookie came back.
**Expected:** step 1 is **401**; the body is the login page containing
`That is not the console password.`; **no** session cookie is set. Step 3 shows the anonymous state.
Also assert the response does not distinguish "wrong password" from "no password configured" in a way
that reveals which — compare with SDKX-064's body.
**Vacuity:** step 3 rules out a 401 that nevertheless established a session.

## SDKX-064 — with no password configured, nobody can sign in
**Intent:** `secret = config.get("console.password", "") or ""` and
`if not secret or not hmac.compare_digest(...)` (`auth_routes.py:61`, `:74`). The comment at
`console/config/application.yaml:26`–`:31` says why: "a default password is a public password, and
this console can destroy state."
**Falsifier:** an empty password, or any password, signs in.
**Setup:** `H-CON` with `CONSOLE_PASSWORD` unset.
**Steps:** 1. `POST /login` with `password=` (empty). 2. With `password=letmein`. 3. With
`password=anything`. 4. `GET /overview` afterwards.
**Expected:** all three are **401** with `That is not the console password.` and no session cookie.
Step 4 renders, anonymous — the console is usable read-only with no password, which is the intended
posture. Assert that `hmac.compare_digest` is reached in none of the three (the `not secret`
short-circuits), so no timing signal distinguishes them.
**Vacuity:** three different submitted passwords is what shows the refusal is the missing
configuration and not a comparison.

## SDKX-065 — an empty password submit is 401, not 422
**Intent:** `password: str = Form("")` is deliberately defaulted rather than required
(`auth_routes.py:70`–`:73`), so FastAPI does not reject the request body before the handler runs.
**Falsifier:** the response is 422 with a validation error body.
**Setup:** `H-CON` with `CONSOLE_PASSWORD=letmein`.
**Steps:** 1. `POST /login` with an empty form body. 2. `POST /login` with `password=`. 3. `POST
/login` with `Content-Type: application/json` and `{}`.
**Expected:** steps 1 and 2 are **401** with the login page. Step 3 — record the status; a 422 here
is acceptable (wrong content type) and a 500 is not.
**Vacuity:** step 3 is what distinguishes "the default makes every bad submit a 401" from "the
default makes every bad request a 401", which are different guarantees.

## SDKX-066 — the `next` parameter cannot be turned into an open redirect
**Intent:** `local_path()` (`auth_routes.py:32`) rejects anything not starting with `/` and anything
starting with `//`, falling back to `/overview`.
**Falsifier:** any of these redirects off-site.
**Setup:** `H-CON` with `CONSOLE_PASSWORD=letmein`.
**Steps:** `POST /login` with `password=letmein` and `next` set to each of: `/queries`;
`/queries?state=RUNNING`; `https://evil.example`; `//evil.example`; `/\evil.example`;
`javascript:alert(1)`; `http://evil.example`; `/%2f%2fevil.example`; `%2F%2Fevil.example`; the empty
string; a 4 KiB `/aaaa...`; and `/queries#frag`.
**Expected:** the first two and the last redirect to themselves. `https://`, `http://`,
`javascript:`, `//evil.example`, `%2F%2Fevil.example` and the empty string all fall back to
`/overview`. Record what `/\evil.example` and `/%2f%2fevil.example` do — they start with `/` and pass
the guard, so they redirect as given; whether a browser then treats `/\evil.example` as protocol-
relative is the thing to check in an actual browser, and if it does, that is a finding.
**Vacuity:** the two ambiguous forms are the point; the obvious `https://evil.example` is caught by
any implementation.

## SDKX-067 — the session secret is generated per process, so a restart signs everyone out
**Intent:** `run_pravaha_web.py:58`–`:59` — the secret comes from `console.session_secret` if set,
else `secrets.token_urlsafe(32)` generated at startup; `SessionMiddleware` with `same_site="lax"`,
`https_only=False`.
**Falsifier:** a session survives a console restart with no configured secret, or the cookie has
`Secure` set when `https_only=False`.
**Setup:** `H-CON`, no `console.session_secret`.
**Steps:** 1. Sign in; record the cookie and its attributes. 2. `GET /queries` — signed in.
3. Restart the console. 4. `GET /queries` with the same cookie. 5. Configure
`console.session_secret`, restart twice, and repeat. 6. Start two console processes with no secret
and try one process's cookie against the other.
**Expected:** step 1 — the cookie has `HttpOnly`, `SameSite=Lax`, **no** `Secure` (because
`https_only=False`), and `Path=/`. Step 4 — signed **out**; the old cookie no longer verifies. Step 5
— the session survives. Step 6 — the cookie is rejected by the other process. Record the
consequence: two console replicas behind a load balancer, with no configured secret, log users out on
every request that lands on the other one, and `application.yaml` does not say so.
**Vacuity:** step 5 is the control that attributes step 4 to the generated secret and not to session
expiry.

## SDKX-068 — reading is open to anonymous visitors; mutating through the UI redirects to sign-in
**Intent:** `login_required()` (`auth_routes.py:51`) returns a 303 to `/login?next=...`. The console
is a read-open, write-closed surface.
**Falsifier:** an anonymous POST changes state.
**Setup:** `H-CON` with a registered query `byuser`, no session cookie.
**Steps:** 1. `GET /`, `/about`, `/overview`, `/queries`, `/queries/byuser`, `/workbench`, `/help`,
`/tutorials` — record every status. 2. `POST /queries/byuser/pause`. 3. `POST /queries/byuser/drop`.
4. `POST /workbench` with SQL. 5. `POST /queries` with a registration. 6. After each of 2–5, confirm
the engine state is unchanged via `pravaha queries`.
**Expected:** step 1 — all **200**. Steps 2–5 — all **303** to `/login?next=<the original path>`;
none reaches the engine. Step 6 — `byuser` is still `RUNNING` after step 2, still present after step
3, and no new query exists after step 5. Assert the `next` round-trips so that signing in resumes the
action's *page*, not the action.
**Vacuity:** step 6 is the non-vacuity: a 303 returned after the mutation had already happened would
pass steps 2–5 alone.

## SDKX-069 — an anonymous JSON mutation is 401, not a redirect
**Intent:** `api_routes.py` uses `json_guard` (`routes/base.py:122`) rather than `guard`, because a
303 to an HTML login page is useless to a fetch client and is indistinguishable from success to a
naive one.
**Falsifier:** the API returns 303, or 200.
**Setup:** `H-CON`, no cookie.
**Steps:** 1. `POST /api/v1/queries` with a valid body. 2. `POST /api/v1/queries/byuser/pause`.
3. `POST /api/v1/query` with SQL. 4. `GET /api/v1/queries`, `/api/v1/queries/byuser`,
`/api/v1/stats`, `/api/v1/health`. 5. Confirm engine state unchanged.
**Expected:** steps 1–3 are **401** with a JSON problem body and `Content-Type: application/json`;
no `Location` header. Step 4 — all **200**, so reads stay open on the API exactly as on the UI. Step
5 — unchanged.
**Vacuity:** the paired read/write arms are what show the 401 is about the method and not the API
prefix.

## SDKX-070 — the landing page tells you which engine this console is pointed at
**Intent:** `landing.html:100`–`:117` renders a "THIS INSTANCE" card with an up/down chip and the
engine URL, from the `brand()` context.
**Falsifier:** the engine URL is absent, or the chip disagrees with reality.
**Setup:** `H-CON` (engine up) and `H-CON-DOWN`.
**Steps:** 1. `GET /` in each. 2. Compare.
**Expected:** up — a chip reading `engine up` and the text `grpc://localhost:19090`. Down — a chip
reading `engine unreachable` and a `<pre>` containing the error text from `Engine.health()`
(`console/core/engine.py:71`–`:80`). Both return **200**. The footer (`base.html:348`) shows
`engine at grpc://localhost:19090` in both.
**Vacuity:** running both arms and diffing is what makes the chip an observation rather than a
constant.

## SDKX-071 — `/overview` shows the four tiles and the eight busiest queries
**Intent:** `ui_routes.py:66`–`:72` calls `services.queries.find(limit=8, sort="-rows_in")`.
**Falsifier:** the tiles do not add up, or more than eight queries are listed.
**Setup:** `H-CON` with twelve registered queries: ten running, one paused, and two names sharing one
fingerprint. Push distinct row counts so the ordering is unambiguous: query `k` gets `k * 100` rows,
`k = 1..12`.
**Steps:** 1. `GET /overview`. 2. Parse the four tiles and the table.
**Expected:** `Registered` is `12` (names, not computations — assert which, and record it, because
the shared pair makes them differ). `Running` is `11`. `Shared` is `1` — one fingerprint carrying
more than one name. `Live feeds` is whatever `H-CON`'s source bindings give; record it. The table has
exactly 8 rows, ordered by `rows_in` descending: queries 12, 11, 10, 9, 8, 7, 6, 5 with
`12 * 100 = 1200` down to `5 * 100 = 500`.
**Vacuity:** the `k * 100` scheme makes every row's position hand-computable, so a table that is
merely non-empty fails.

## SDKX-072 — `/queries` pages, filters and sorts, and the URL carries the view
**Intent:** `queries.html` plus `_queries_table.html`; `search`, `state`, `sort` and `offset` are URL
parameters "so a view is shareable"; page size is `ui.page_size: 25`.
**Falsifier:** a filter changes nothing, or the second page repeats the first.
**Setup:** `H-CON` with 60 queries: 40 named `alpha00`..`alpha39` (running), 20 named
`beta00`..`beta19` (10 paused). Row counts `k * 10`.
**Steps:** 1. `GET /queries`. 2. `?offset=25`. 3. `?offset=50`. 4. `?search=beta`. 5.
`?state=PAUSED`. 6. `?sort=-rows_in` and `?sort=name`. 7. `?offset=1000`. 8. `?offset=-1` and
`?offset=abc`.
**Expected:** step 1 — 25 rows. Step 2 — rows 26–50, no overlap with step 1. Step 3 — rows 51–60, ten
rows. Step 4 — 20 rows (one page, since `20 < 25`), every name starting `beta`. Step 5 — 10 rows, all
`PAUSED`. Step 6 — the two orderings are reverses of each other on the first page for the metric
concerned. Step 7 — zero rows and a page that still renders 200, not a 500. Step 8 — record the
status; a 422 is acceptable, a 500 is not. Every response's URL-derived state must be reproducible by
pasting the URL into a fresh session.
**Vacuity:** the no-overlap assertion between pages 1 and 2 is what catches an offset that is applied
to the wrong collection, which a count-only assertion passes.

## SDKX-073 — `/queries/{name}` shows the definition and its siblings, and 404s cleanly
**Intent:** `query_detail.html` shows SQL, fingerprint, state and the other names on the same
computation. Sharing is invisible everywhere else in the product.
**Falsifier:** siblings are missing, or an unknown name 500s.
**Setup:** `H-CON` with `alpha` and `beta` sharing one fingerprint, and `solo` not.
**Steps:** 1. `GET /queries/alpha`. 2. `GET /queries/beta`. 3. `GET /queries/solo`. 4. `GET
/queries/nosuch`. 5. `GET /queries/..%2f..%2fetc%2fpasswd`. 6. `GET /queries/<64 KiB name>`.
**Expected:** steps 1 and 2 — 200; each lists the other as a sibling; identical `fingerprint`;
identical SQL. Step 3 — 200, no siblings. Step 4 — **404** rendering `not_found.html`, not a 500 and
not a stack trace. Steps 5 and 6 — 404, and nothing in the response body echoes the path in a way
that could be injected; assert the rendered page HTML-escapes the name.
**Vacuity:** steps 1 and 2 together are what prove the sibling list is derived from the fingerprint
rather than hard-coded; step 3 is the negative control.

## SDKX-074 — `/workbench` runs ad-hoc SQL, coerces parameters, and caps the result
**Intent:** `ui_routes.py:124`–`:145`, with `_typed()` (`:29`) coercing digit-strings to `int`/
`float` and `ui.query_row_limit: 500`.
**Falsifier:** a parameter is passed as the wrong type, or more than 500 rows are rendered.
**Setup:** `H-CON` signed in, with `q` holding 2 000 rows including SDKX-013's three.
**Steps:** 1. `POST /workbench` with `sql=SELECT user_id, amount FROM q WHERE amount > ?` and
`params=150`. 2. The same with `params=150.0`. 3. With `params=card` against `product_type = ?`.
4. With `params=150, card` (two parameters, comma-separated). 5. With `params=007`. 6. With
`sql=SELECT * FROM q` (2 000 rows). 7. With `sql=SELEKT 1`. 8. With a 1 MiB SQL string. 9. Anonymous.
**Expected:** step 1 — `150` becomes `int` and the filter applies. Step 2 — `float`. Step 3 — stays a
string. Step 4 — two parameters, `int` then `str`. Step 5 — `007` is all digits, so it becomes `int`
`7`; if the query expected the string `"007"` (a zero-padded account number) the answer is silently
wrong. **Record this**: `_typed` cannot be overridden from the form, so a zero-padded identifier
cannot be passed. Step 6 — at most 500 rows rendered, with a visible notice that the result was
capped; assert the notice exists, because a silent cap is a wrong answer. Step 7 — `refused.html`
with the PRV code from the message. Step 8 — record the status; not a 500. Step 9 — 303 to `/login`.
**Vacuity:** step 5 is the case that would not be written by someone testing that parameters "work";
it is the one that finds the defect.

## SDKX-075 — the content pages render, and an unknown slug is a 404 rather than a file read
**Intent:** `core/content/library.py` + `renderer.py` render Markdown from
`console/content/{about,help,tutorials}/` at request time, with the slug **allow-listed** rather than
used as a path.
**Falsifier:** a slug reaches the filesystem.
**Setup:** `H-CON`.
**Steps:** 1. `GET /about`, `/help`, `/tutorials`. 2. `GET /help/{slug}` for every slug in
`console/content/help/`. 3. The same for tutorials. 4. `GET /help/nosuch`. 5. `GET
/help/..%2f..%2f..%2fetc%2fpasswd`, `/help/../../console/config/application.yaml`,
`/help/%2e%2e%2f%2e%2e%2fpyproject.toml`. 6. Compare each rendered page against its source Markdown.
**Expected:** steps 1–3 — 200, with the Markdown rendered to HTML and code blocks highlighted by
Pygments. Step 4 — **404** rendering `not_found.html`. Step 5 — 404 for every traversal attempt, and
**no** file content from outside the content directory in any response body; grep each body for
`root:` and for `console.password`. Step 6 — the index pages list exactly the slugs that exist, so a
content file added without an index entry is invisible and one removed leaves a dead link; assert
both directions.
**Vacuity:** grepping the bodies for known secrets is the assertion; a 404 status alone does not rule
out a partially-rendered file.

## SDKX-076 — the three health endpoints answer three different questions
**Intent:** `/health` says "is the console up" and returns **200 even when the engine is down**;
`/health/live` never consults the engine; `/health/ready` returns **503** when the engine is
unreachable (`public_routes.py:116`–`:152`).
**Falsifier:** `/health` 503s when the engine is down, or `/health/ready` 200s.
**Setup:** `H-CON` and `H-CON-DOWN`.
**Steps:** 1. In both: `GET /health`, `/health/live`, `/health/ready`, `/api/v1/health`. 2. Record
status and body. 3. Time `/health/live` with the engine black-holed rather than merely absent (an
address that accepts and never answers).
**Expected:** engine **up** — all four 200. Engine **down** — `/health` 200,
`/health/live` 200, `/health/ready` **503** with `{"status": "not-ready", ...}`, `/api/v1/health`
200 (it is the console's own health). Step 3 — `/health/live` must return fast (well under a second)
because it does not touch the engine; if it hangs, that is the finding, because a liveness probe that
hangs gets the console killed for the engine's fault.
**Vacuity:** the black-hole arm is what separates "does not consult the engine" from "consults an
engine that fails fast".

## SDKX-077 — every page renders with the engine down
**Intent:** the console's stated design: "a console whose own page 500s when the engine is down is a
console that cannot tell you the engine is down" (`core/engine.py`). `UIRoutes._safe()`
(`ui_routes.py:52`–`:62`) swallows `ServiceError` and renders with a fallback.
**Falsifier:** any page returns 500, or renders as if the engine were fine.
**Setup:** `H-CON-DOWN`.
**Steps:** 1. `GET` every page route from SDKX-061's table. 2. Record the status and grep each body
for the phrase that should be there. 3. Also do it with the engine *black-holed* rather than absent,
and record the response times.
**Expected:** every page returns **200**. `/overview` contains `The engine is not answering`, a
`<pre>` with the error, the sentence
`This console is running and this page is current — it is the engine at &lt;url&gt; that cannot be
reached. Check that it is started and that its Flight port matches.`, and a "Try again" button.
`/queries` contains `The engine is not answering` and `Nothing can be listed until it does.` Every
page's header carries the `engine unreachable` chip. Step 3 — record the per-page latency; with no
SDK timeout (SDKX-046) a black-holed engine may make every page hang, and `health_cache_seconds: 1`
means the hang is retried every second. That would be a worse failure than the absent-engine case and
is the thing to measure.
**Vacuity:** the black-hole arm is the non-vacuity. "The engine is down" is easy when the connection
is refused instantly; the design claim only holds if it also holds when the engine is slow.

## SDKX-078 — the JSON API maps PRV families onto HTTP status codes
**Intent:** `routes/base.py:53`–`:63` — `STATUS_BY_FAMILY`: PRV-1xxx → 503; 2/3/4/5/6/8xxx → 400;
7xxx → 403; 9xxx → 503. The code is recovered by string-searching the message (`_code_in`,
`core/services.py:106`–`:113`) because the SDK dropped it (SDKX-045).
**Falsifier:** any family maps to the wrong status, or an unmapped code 500s.
**Setup:** `H-CON` signed in, against an engine configured to produce each family.
**Steps:** provoke and record the HTTP status and the problem body for: (a) engine down → PRV-1041 →
expect **503**; (b) bad SQL → PRV-2xxx → **400**; (c) arena/runtime → PRV-3xxx → **400**;
(d) state → PRV-4xxx → **400**; (e) plugin → PRV-5xxx → **400**; (f) flight → PRV-6xxx → **400**;
(g) forbidden → PRV-7xxx → **403**; (h) registry → PRV-8xxx → **400**; (i) cluster → PRV-9xxx →
**503**; (j) an error message containing **no** PRV code at all.
**Expected:** the nine mapped families give the statuses above, and the problem body carries the
`code`. Arm (j) is the one to watch: `_code_in` finds nothing, and the case records what
`status_for()` does with that — a default of 500 is defensible and must be observed, not assumed.
Also assert the reverse: a message containing `PRV-` *inside user data* (a query whose text is
`SELECT 'PRV-7001' FROM q`) must not be mis-classified as a 403. That is the cost of recovering a
code by string search, and it is directly caused by SDKX-045.
**Vacuity:** arm (j) and the injection arm are what make this a test of the mapping mechanism rather
than a table lookup.

## SDKX-079 — one engine subscription is shared across browsers, and a slow browser drops rows
**Intent:** `core/services.py` `Broadcaster` (`:290`), `_Feed` (`:385`), `Subscriber` (`:332`). One
engine subscription per view serves every browser; each browser has a bounded queue that drops the
oldest (`offer`, `:341`). The SSE endpoint polls `drain()` every 250 ms (`api_routes.py:158`).
**Falsifier:** N browsers create N engine subscriptions, or a slow browser slows the others.
**Setup:** `H-CON` with `q` and a feeder pushing 200 rows/s.
**Steps:** 1. Open `GET /api/v1/views/q/stream` from five clients. 2. Count the engine-side
subscriber count on `H-OPEN`. 3. Make client 3 stop reading its socket for 10 s. 4. Measure the row
rate at clients 1, 2, 4, 5 during those 10 s. 5. Resume client 3 and count how many rows it missed.
6. Close all five and re-check the engine-side subscriber count. 7. Read the SSE event types.
**Expected:** step 2 — **one** engine subscriber, not five. Step 4 — the other four keep receiving at
roughly the feed rate; the gap is under 10 %. Step 5 — client 3 missed rows, and the console emitted a
`lag` event to it rather than silently skipping. Step 6 — the engine-side subscriber count returns to
zero, and the shared `_Feed` is torn down; if it is not, that is a leak. Step 7 — the stream carries
`open`, `row`, `lag` and `error` events plus `: keep-alive` comments.
**Vacuity:** step 4's measurement of the *other* clients is the point; a slow-client test that only
checks the slow client passes for a console that blocked everyone equally.

## SDKX-080 — the console disables the SDK's plaintext-token refusal for every connection
**Intent:** `console/core/engine.py:60`–`:69`:
`connect(options=ClientOptions.create(self._url, token=self._token, allow_insecure_token=True))`.
Unconditional, exactly as the CLI does (SDKX-059) — and the console builds **a new SDK client per
call**.
**Falsifier:** a token over a plaintext remote engine is refused or warned about; or the client is
reused across calls.
**Setup:** `H-CON` with `PRAVAHA_ENGINE=grpc://<remote>:19090` and `PRAVAHA_TOKEN=
correct-horse-battery-staple-0001`, against `H-AUTH` on a non-loopback address, with a packet capture.
**Steps:** 1. `GET /queries`. 2. Capture the traffic. 3. Grep the console's logs and the page for any
warning. 4. Instrument `Engine._client` and count constructions across 20 page loads. 5. Count TCP
connections opened to 19090 over those 20 loads. 6. Repeat with `PRAVAHA_ENGINE=grpc+tls://...` and
record what happens (cross-reference SDKX-054).
**Expected:** step 2 — the bearer token is in cleartext on the wire. Step 3 — no warning anywhere.
Step 4 — **20 or more** client constructions; the SDK's refusal is bypassed on every one. Step 5 —
record the connection count; a new `FlightClient` per call means a new channel per call unless gRPC
pools them, and 20 page loads should not open 20 channels. If it does, that is a resource finding on
top of the security one. Step 6 — the TLS arm fails per Section D, so the *only* working
configuration for a console with a token is the plaintext one, which is the configuration that leaks
the token.
**Vacuity:** the non-loopback address is essential, as in SDKX-059; and step 4's instrumented count
turns "a new client per call" from a code reading into a measurement.

---

## Coverage note

**Budget: 80. Written: 80.** IDs `SDKX-001`–`SDKX-080`, contiguous.

**Twelve cases for an artefact with no client.** `pravaha-sdk-java` (SDKX-001–012) is types only, and
at first reading that looks like twelve cases spent on nothing. It is where the contract lives:
`Endpoint`'s "no scheme means TLS" rule, `ClientOptions`' plaintext-token refusal, and the four
`ClientErrors` codes are the promises every other section measures the transports against. Two of the
three findings in Section D — the CLI's opposite default (SDKX-059) and the console's unconditional
override (SDKX-080) — are only legible as defects because SDKX-002 and SDKX-007 established what was
promised.

**Ten cases for TLS, which no client can use.** The brief asked for one TLS case and one custom-CA
case per SDK, which would be six. It is ten because the failure has three independent causes and
fixing one leaves the others: the missing `netty-transport-native-unix-common` on the client trees
(SDKX-052, 053), the absence of any CA API in all three clients (SDKX-056), and the two server-side
defects in `PravahaFlightServer` (SDKX-060). A deployment that adds the netty artefact still cannot
use a private CA; one that gets a public certificate still has a server that reports its own location
as `grpc://`. Each needs its own verdict.

**`Consistency` is under-covered and the reason is not in this area.** The enum has four constants
(`LATEST`, `CONSISTENT`, `AS_OF`, `AT_LEAST`) and `ClientOptions.defaultConsistency` defaults to
`CONSISTENT`, but neither transport reads it — `grep -rn "defaultConsistency\|default_consistency"`
finds only the accessors. The four read-consistency modes belong to `LIFE` (index row: "the 4
read-consistency modes"), and their client-side plumbing is asserted here only as a default value
(SDKX-005, SDKX-036). If `LIFE` finds the modes work server-side, someone should write the case that
asks how a client selects one, because on this code it cannot.

**Three cases are deliberately environment-dependent** and must report a skip with a reason rather
than a pass: SDKX-034 needs two virtualenvs on two Python versions; SDKX-059 and SDKX-080 need a
non-loopback address, because on `127.0.0.1` the `allowInsecureToken` behaviour is defensible and the
cases prove nothing. SDKX-053 needs the ability to add a jar to the client classpath by hand, which
is the control that attributes the TLS failure to the dependency.
