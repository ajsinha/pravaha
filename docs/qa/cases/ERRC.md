# ERRC — every error code: reachable, documented, meaning one thing, message actionable

The surface under test is **the promise `docs/TROUBLESHOOTING.md` makes in its last sentence**:

> Generated from the source, not from memory: every row above is an `ErrorCode` declared in a
> module's main sources. **If a code is missing here it does not exist in the engine.**

Ten codes are missing from it and exist in the engine. That sentence is the reason this file is not
a documentation chore: a support engineer who searches `PRV-5090`, finds nothing, and reads that
sentence concludes the log line is not from Pravaha.

The code under test is `pravaha-api/.../ErrorCode.java` (the record, the `Category` enum and
`category()`), every `*Errors.java` in every module, `pravaha-server/.../api/ApiExceptionHandler.java`
(`statusFor`), `pravaha-server/.../security/BearerTokenFilter.java` (`refuse`),
`pravaha-flight/.../FlightErrors.java` (`asFlightStatus`) and `PrincipalMiddleware.java`, and
`docs/TROUBLESHOOTING.md`.

**Ports:** HTTP 18900, Flight 19900. **Scratch:** `$QA = <scratchpad>/qa-errc`.

---

## Facts this file assumes, each read out of the code

1. **There are 110 `new ErrorCode(...)` declarations in main sources**, across 21 files in 16
   modules. The count excluding `sdk/pravaha-sdk-java` is 104, which is the number the index
   budgets — but the SDK's six are real codes that reach real users, and the brief asks for
   `PRV-1040`–`PRV-1043` explicitly. This file covers all 110. See the Coverage note.
2. **Two of the 110 are aliases, not new numbers.** `PluginErrors.MISSING_SETTING` is
   `PluginContext.MISSING_SETTING` (5001) and `FlightErrors.BAD_HANDLE` is `ControlWire.BAD_REQUEST`
   (6102). Both are aliased deliberately, with the reasoning written down
   (`PluginErrors.java:22-26`, `FlightErrors.java:33-40`). So there are **110 declarations and 110
   distinct numbers**; `ErrorCodeUniquenessTest` is what keeps that true.
3. **`ErrorCode.Category` has seven constants covering 1000–7999**
   (`ErrorCode.java:39-46`). `category()` iterates them and **throws
   `IllegalStateException("no category for PRV-nnnn")` when none matches**
   (`ErrorCode.java:69-76`). **The 8xxx registry family (seven codes) and the 9xxx cluster family
   (seven codes) have no category.** `ErrorCodeTest.java:61` asserts this throw for 8500 — the
   behaviour is tested and the consequence is not.
4. **`ApiExceptionHandler.statusFor` calls `code.category()`** (`ApiExceptionHandler.java:63`).
   So **any `PRV-8nnn` or `PRV-9nnn` that reaches an HTTP controller becomes an
   `IllegalStateException` inside the exception handler**, not an `ApiError`.
5. **The `Category` ranges do not mean what their names say.** `CLUSTER` is `(6000, 6999)` — which
   is the **Flight** family. `FlightErrors.UNSUPPORTED_TYPE` (6100) therefore reports
   `Category.CLUSTER`, and `statusFor` maps it to 500 by the cluster arm of the switch. The real
   cluster codes are 9xxx and have no category at all. `TROUBLESHOOTING.md:16-25` lists eight ranges
   and calls `PRV-6xxx` "The Flight gateway" — agreeing with reality and disagreeing with the enum —
   and **omits `PRV-9xxx` from the ranges table entirely** while listing all seven 9xxx codes in the
   full table below it.
6. **`statusFor` maps the whole `SECURITY` category to 403.** `PRV-7001` is *unauthenticated*, which
   is 401. The only reason this is not visible on every call is that `BearerTokenFilter.refuse`
   writes its own 401 without going through the handler (`BearerTokenFilter.java:113-133`). A
   `PRV-7001` thrown from a **controller** — e.g. by `TokenVerifier` behind a controller rather than
   the filter — arrives as 403 with no `WWW-Authenticate` header.
7. **`BearerTokenFilter.refuse` hand-writes JSON with six fields**: `code`, `message`, `helpUrl`,
   `timestamp`, `path` **and `status`**. `ApiDtos.ApiError` has **five** — no `status`. The comment
   above it says at length that a client which parses two error shapes will handle one badly. There
   are two shapes, and the extra field is in the one every unauthenticated client meets first.
8. **`TROUBLESHOOTING.md` documents 100 of the 110 codes.** The ten missing are
   `PRV-1030`, `PRV-1031`, `PRV-1040`, `PRV-1041`, `PRV-1042`, `PRV-1043` (the SDK/CLI family),
   `PRV-5090`, `PRV-5091`, `PRV-5092` (the whole server ingest family) and `PRV-6104` (TLS).
   Every code in the document does exist in the engine — the error is one-directional.
9. **Nine codes have no throw site anywhere in main sources.**
   `PRV-1043 CLIENT_CLOSED`, `PRV-4002 STATE_UNREADABLE`, `PRV-4013 BACKFILL_MALFORMED_OFFSET`,
   `PRV-5012 PLUGIN_LOAD_FAILED`, `PRV-5020 PLUGIN_CIRCUIT_OPEN`, `PRV-5053 DELTA_FILE_VACUUMED`,
   `PRV-5064 FEEDFILE_FILE_GONE`, `PRV-8007 REGISTRY_REPLAY_UNAUTHORIZED`,
   `PRV-9004 CLUSTER_NOT_LEADER`. Eight of the nine are documented in `TROUBLESHOOTING.md` with a
   cause; the ninth (`PRV-1043`) is not documented either.
10. **`PRV-7002 SECURITY_FORBIDDEN` is thrown from fifteen places in five modules**, and they are
    not one thing:
    - **authorization denied** — `ViewQuery.java:184,279,348,362,432`,
      `QueryRegistry.java:289,306`, `PravahaFlightSqlProducer.java:449,487,505`;
    - **the node's security configuration is unsafe or contradictory** —
      `PravahaNode.java:166` (accidentally open), `:183` (policy/authentication contradiction);
    - **a configuration value is not a name this node knows** — `PravahaNode.java:290` (policy),
      `:310` (audit);
    - **the two policy holders disagree** — `PravahaFlightServer.java:202` (`requireOnePolicy`).
    `TROUBLESHOOTING.md:76-82` documents exactly one of these four: *"Authenticated, not authorized
    — ask for access; a new credential will not help."* Advice which, for a node that refused to
    start because `pravaha.security.audit` was set to `log`, is wrong in every word.
11. **`ErrorCode` refuses a number outside 1000–9999 and a blank name**
    (`ErrorCode.java:29-36`). `helpUrl()` is `https://docs.pravaha.io/errors/PRV-nnnn`
    (`ErrorCode.java:27,77-79`) for **all 110**, including the ten that are not documented and the
    nine that cannot be thrown.
12. `FlightErrors` maps Pravaha failures onto Flight statuses; `TROUBLESHOOTING.md:87-89` states
    that `PRV-4026`/`4027`/`4028` all arrive at a client as `RESOURCE_EXHAUSTED` so a driver retries
    them rather than giving up. That mapping is a claim this file checks per code, not once.

---

## Standing setup and method

`$QA/conf/errc.yaml` is CFG's `base.yaml` on ports 18900 / 19900, with
`pravaha.registry.journal: $QA/journal`, `pravaha.checkpoint.directory: $QA/ckpt`,
`pravaha.security: { authentication: token, policy: authenticated, audit: memory,
tokens: { "errc-token-ann": { id: ann, tenant: acme, roles: [reader] },
"errc-token-bob": { id: bob, tenant: other, roles: [reader] } } }`, and TLS material at
`$QA/tls/`. `txnA.csv` and the query `QW` are CFG's, unchanged.

**Every case in the inventory (ERRC-001 – ERRC-110) has the same Intent, the same Steps and the
same Expected assertions. They are stated once here, and each case supplies only what is specific
to it: the surface it is reached through, the falsifier, and the exact values.**

**Intent (all inventory cases).** A code is only worth having if it can be produced, identified,
acted on and looked up. This case establishes those four for one code.

**Steps (all inventory cases).**
- **S1 — reach it.** Perform the case's named action on the named **product surface**: the CLI, the
  REST API, the Flight transport, an SDK, the console, or a configuration file read at startup.
  A JUnit test is **not** a product surface and does not satisfy S1; if the only way to produce a
  code is to call the throwing method directly, the case's result is *unreachable*, which is a
  finding and is recorded as such rather than skipped.
- **S2 — capture it** exactly as the surface renders it: for HTTP, the status line and the full
  response body; for Flight, the gRPC status and the `FlightStatusCode`, and the description string;
  for the CLI, stderr and the exit status; for a startup refusal, the final log line and the process
  exit status.

**Expected (all inventory cases) — four assertions.**
- **E1 — number.** The rendered code is exactly `PRV-nnnn` for the declared number, on every surface
  it reaches. Not a substring of a stack trace; a field a client can read.
- **E2 — name.** `ErrorCode.name()` matches the declaration byte for byte, and the name is the one
  `TROUBLESHOOTING.md`'s table gives for that number.
- **E3 — message actionable.** The message (a) names the specific thing that failed — the key, the
  file, the column, the plugin, the query, the principal — and (b) says what to do or what would
  have been accepted. A message that is a bare code, a Java exception `toString()`, or a restatement
  of the code's own name fails E3. Record the message verbatim.
- **E4 — documented, and documented correctly.** `docs/TROUBLESHOOTING.md` has a row for the number;
  the name in that row matches E2; and the cause the document gives matches **what this throw site
  actually throws for**. A row that exists but describes a different cause fails E4 as surely as a
  missing row does.

**Vacuity (all inventory cases).** S1 must produce the code from a state the case constructed. A
case that captures a code the node was already emitting for an unrelated reason has not reached it.
Where a case involves state or timing, it says so and says what makes it non-vacuous.

**The inventory is in numeric order**, so the file can be read against `TROUBLESHOOTING.md`'s own
table line by line.

---

## PRV-1xxx — configuration, and the client library that shares the range

`ConfigErrors` (1001–1026) is the engine's own configuration parser — reachable from a server node
only through the engine `Configuration` the node builds, which is two keys wide (CFG fact 12), and
through the checkpointer's configuration. The realistic surface for most of these is the **embedded
engine** and the **CLI's local `run` command**, both of which are products.

## ERRC-001 — PRV-1001 CONFIG_FILE_UNREADABLE
**Reached through:** the embedded engine / `pravaha run`, pointing `ConfigurationBuilder.addFile` at
a path that does not exist (`ConfigurationBuilder.java:77-80`) or one that cannot be opened
(`PropertiesFormat.java:52-55`).
**Falsifier:** a missing configuration file produces a bare `IOException`, or a code other than
`PRV-1001`.
**Setup:** `$QA/conf/absent.properties` (not created), and `$QA/conf/mode000.properties` with mode
`000`.
**Expected:** both paths give `PRV-1001`. E3: the message must contain the **absolute** path —
`ConfigurationBuilder.java:79` says "configuration file does not exist: " + absolute path, and
`PropertiesFormat.java:54` says "cannot read configuration file " + file. **Two different messages
for one code**: record both and check the second also renders an absolute path. E4: documented.

## ERRC-002 — PRV-1002 CONFIG_FILE_MALFORMED
**Reached through:** a properties/YAML file with a line that is neither `key=value` nor `key: value`
(`PropertiesFormat.java:92`), an empty key (`:97`), an unterminated construct (`:82`), a resolver
failure (`ConfigResolver.java:99`), or a builder-level parse failure (`ConfigurationBuilder.java:91`).
**Falsifier:** any of the five produces a different code, or a message without a line number.
**Setup:** five files, one per throw site.
**Expected:** `PRV-1002` five times. E3: `PropertiesFormat` renders `file + ":" + line`, so the
message must name **the line**. Confirm all five do; a `PRV-1002` without a line number is the
failure this code exists to avoid. E4: documented.

## ERRC-003 — PRV-1010 CONFIG_UNRESOLVED_REFERENCE
**Reached through:** a configuration value containing `${missing.key}` with no default
(`ConfigResolver.java:124`).
**Falsifier:** the reference is left as the literal text `${missing.key}` and the node starts.
**Setup:** `a=${nosuch.key}`.
**Expected:** `PRV-1010`, E3 naming **both** the key being resolved and the key that is missing —
one without the other leaves the operator grepping. E4: documented.

## ERRC-004 — PRV-1011 CONFIG_CIRCULAR_REFERENCE
**Reached through:** `a=${b}` and `b=${a}` (`ConfigResolver.java:62` and `:112` — two throw sites,
depth limit and cycle detection).
**Falsifier:** a stack overflow, a hang, or the literal text surviving.
**Setup:** a two-key cycle, and a 100-deep chain that terminates.
**Expected:** `PRV-1011` for the cycle; the 100-deep chain **resolves**. E3: the message must name
the cycle's members in order. E4: documented.
**Vacuity:** the terminating 100-deep chain is the control — a depth limit mistaken for a cycle
detector would refuse both.

## ERRC-005 — PRV-1020 CONFIG_MISSING_REQUIRED
**Reached through:** `Configuration.requireString`/`requireInt` on an absent key
(`Configuration.java:204-207`). On the server path the live instance is
`SocketProvider.java:54-58` — which throws `PRV-9005` rather than `PRV-1020`, so that is **not** a
reach for this code.
**Falsifier:** no product surface reaches it.
**Setup:** the embedded engine with a `Configuration` missing a key a plugin requires.
**Expected:** `PRV-1020`, E3 naming the key — *"required configuration key 'x' is not set"* is the
text; judge whether naming the key alone satisfies E3(b), since it does **not** say what an
acceptable value would be. Record the judgement; this is the borderline case that calibrates E3 for
the rest of the file. E4: documented.

## ERRC-006 — PRV-1021 CONFIG_NOT_A_NUMBER
**Reached through:** `getLong`/`getInt`/`getDouble` on non-numeric text
(`ConfigParsers.java:50,58`). On the server, `pravaha.checkpoint.keep` is the live path — but
`PersistenceProperties` binds it as an `int` through Spring first, so a non-numeric value fails at
context refresh and **never reaches this parser**. Reach it through the embedded engine or a plugin
option.
**Falsifier:** a `NumberFormatException` escapes instead.
**Setup:** `n=twelve`, and `n=1_000_000` (which must **succeed** — underscores are stripped).
**Expected:** `PRV-1021` for the first; `1000000` for the second. E3: *"a whole number, e.g. 16 or
1_000_000"* — the message states the accepted form, which is what E3(b) asks for. E4: documented.

## ERRC-007 — PRV-1022 CONFIG_NOT_A_BOOLEAN
**Reached through:** `getBoolean` on anything outside `true/false, yes/no, on/off, 1/0`
(`ConfigParsers.java:41`).
**Falsifier:** an unrecognised value silently means `false`.
**Setup:** `b=maybe`; and the eight accepted spellings, which must all succeed.
**Expected:** `PRV-1022` once, eight successes. **Contrast with `pravaha.security.authentication`,
where `maybe` silently means `none` and no code is raised (CFG-012)** — the engine's own parser
refuses what the server's security key accepts. E4: documented.

## ERRC-008 — PRV-1023 CONFIG_NOT_A_DURATION
**Reached through:** three sites — no unit or no number (`ConfigParsers.java:84`), an unparseable
number (`:93`), an unknown unit (`:105`).
**Falsifier:** a bare number is accepted as milliseconds.
**Setup:** `d=30` (bare), `d=abcs`, `d=30x`, and the accepted set `200us 30s 5min 1h 2d 100ms 5ns`.
**Expected:** `PRV-1023` three times, seven successes. E3: *"a number with a unit: ns, us, ms, s,
min, h or d"*. **Cross-reference CFG-022:** Spring's binder accepts a bare `30` as 30 ms for
`pravaha.checkpoint.interval`, and this parser refuses it. Two dialects, one file, opposite answers.
E4: documented.

## ERRC-009 — PRV-1024 CONFIG_NOT_A_DATA_SIZE
**Reached through:** `getDataSize` (`ConfigParsers.java:125,131,140`).
**Falsifier:** `4MB` is read as decimal, or an unknown unit is accepted.
**Setup:** `s=4MB` (must be `4 × 1024 × 1024 = 4194304`), `s=4` (a bare number **is** accepted —
unit optional), `s=4XB`, `s=MB`.
**Expected:** `4194304` and `4`; `PRV-1024` twice. E3 names the accepted units `B, KB, MB, GB, TB`.
**Note:** no configuration key in this product reads a data size — `arena.slab.size` would have been
the one (CFG-048). Record that the parser exists for a key that does not. E4: documented.

## ERRC-010 — PRV-1025 CONFIG_NOT_AN_ENUM
**Reached through:** `getEnum` (`ConfigParsers.java:154`).
**Falsifier:** an unknown enum name falls back to the default.
**Setup:** an embedded configuration selecting a `WaitStrategy.Kind` of `FAST`.
**Expected:** `PRV-1025`, E3 listing the allowed values. **`pravaha.cluster.mode` is the obvious
product surface and does not use this** — `CoordinatorFactory.modeOf` catches
`IllegalArgumentException` and throws `PRV-9001` instead (`CoordinatorFactory.java:103-117`), so
the enum parser with a purpose-built code is bypassed by the one place a user would meet it. Record
that. E4: documented.

## ERRC-011 — PRV-1026 CONFIG_OUT_OF_RANGE
**Reached through:** `getInt` on a value that does not fit 32 bits (`Configuration.java:190,199`).
**Falsifier:** the value wraps silently.
**Setup:** `n=2147483648` read through `getInt`; and `n=2147483647` which must succeed.
**Expected:** `PRV-1026` and `2147483647`. E3: *"does not fit in a 32-bit int"* — names the value and
the constraint. **Cross-reference CFG-035:** `ZooKeeperProvider` casts a `long` to `int` with a bare
`(int)` and would wrap rather than raise this. E4: documented.

## ERRC-012 — PRV-1030 CLIENT_MALFORMED_ENDPOINT
**Reached through:** the Java SDK and the CLI's `--url`: `Endpoint.parse` on a URL it cannot read
(`Endpoint.java:46,136`). `pravaha queries --url nonsense` is the one-line reach.
**Falsifier:** a malformed URL produces a connection attempt, or a `URISyntaxException`.
**Setup:** `--url nonsense`, `--url http://h:9090` (wrong scheme), `--url grpc://` (no host),
`--url grpc://h:99999` (port out of range), `--url grpc+tls://h` (no port).
**Expected:** `PRV-1030` for each that is genuinely malformed. E3 must name the offending URL **and**
the accepted form (`grpc://host:port` / `grpc+tls://host:port`) — a client library's first error is
the one users meet before they have learned anything.
**E4: NOT DOCUMENTED.** `PRV-1030` is absent from `TROUBLESHOOTING.md`. Its `helpUrl` points at
`https://docs.pravaha.io/errors/PRV-1030`. **Finding.**

## ERRC-013 — PRV-1031 CLIENT_INVALID_OPTIONS
**Reached through:** `ClientOptions` validation — four sites (`ClientOptions.java:173,186,197,209`).
**Falsifier:** an invalid option is accepted and misbehaves later.
**Setup:** `subscriberBufferRows = 0`; a blank `applicationName`; each positive-required option set
to `0` and to `-1`.
**Expected:** `PRV-1031` each time, E3 naming the option and the value — *"subscriberBufferRows must
be at least 1, got 0"* is the model. Note `PravahaClientException(..., false)` — the third argument
is retryability, and `false` is right here; check it is `false` for all four.
**E4: NOT DOCUMENTED.** **Finding.**

## ERRC-014 — PRV-1040 CLIENT_CONNECT_FAILED
**Reached through:** `PravahaFlightClient.java:153` — any SDK or CLI call against a port nothing is
listening on, or against a TLS server without TLS.
**Falsifier:** a connection failure surfaces as a raw gRPC `UNAVAILABLE` with no `PRV-` code.
**Setup:** node down; `pravaha queries --url grpc://127.0.0.1:19900`. Then node up with TLS
(CFG-069's configuration) and a plain `grpc://` client. Then `flight.enabled: false` (CFG-103).
**Expected:** `PRV-1040` in all three. E3 must name the endpoint. The third case is the important
one: the node is up, HTTP answers, and the client cannot connect — the message must not suggest the
server is down.
**E4: NOT DOCUMENTED.** This is the first error a new user of either SDK will ever see.
**Finding, and the highest-severity one in the missing set.**

## ERRC-015 — PRV-1041 CLIENT_QUERY_REFUSED
**Reached through:** seven sites — `PravahaFlightClient.java:196,236,265,346` and
`Parameters.java:53,95,119`.
**Falsifier:** a server-side refusal reaches the caller with the client's code instead of the
server's, losing the real diagnosis.
**Setup:** for each of the seven: a query the server refuses (`PRV-2050`), a register with a
duplicate name (`PRV-8001`), a drop of an unknown query (`PRV-8002`), a subscribe to a dropped
query, and three parameter-binding failures.
**Expected:** **the key question is whether `PRV-1041` replaces or wraps the server's code.** If the
CLI prints `PRV-1041` for a query the server refused with `PRV-2050`, the operator cannot look up
what actually happened — and `TROUBLESHOOTING.md:34-48` devotes its longest section to `PRV-2050`.
Record, for each of the seven, whether the server's code and message survive in the client's
message. **A client code that hides a server code fails E3.**
**E4: NOT DOCUMENTED.**

## ERRC-016 — PRV-1042 CLIENT_READ_FAILED
**Reached through:** `Row.java:91,106,141` (reading a column by a name or index that is not there,
or as the wrong type) and `QueryResult.java:62`.
**Falsifier:** a wrong column name gives `null` rather than a failure.
**Setup:** an SDK program reading `row.getString("nosuch")`, `row.getLong(99)`, and
`row.getLong("usr")` on a `STRING` column.
**Expected:** `PRV-1042` three times. E3 must name the column and, for the wrong-type case, both the
requested type and the actual one.
**E4: NOT DOCUMENTED.**

## ERRC-017 — PRV-1043 CLIENT_CLOSED — **declared and never thrown**
**Reached through:** nothing. `grep -rn "CLOSED"` across main sources finds only the declaration
(`ClientErrors.java:33`).
**Falsifier:** any product surface produces `PRV-1043`.
**Setup:** the obvious candidates — call `query()` on a closed `PravahaFlightClient`; iterate a
`QueryResult` after closing its client; `close()` twice; use a subscription after `close()`.
**Expected:** **none of them produces `PRV-1043`.** Record what each *does* produce — an
`IllegalStateException`, a gRPC `CANCELLED`, an NPE, or success. Whatever it is, it is not the code
written for it, and a use-after-close on a client library is a mistake users make constantly.
**E4: not documented either**, so this code exists only in a constant pool. **Finding: unreachable
and undocumented.**
**Vacuity:** the case is falsified by finding any reach; it is written so the executor tries four
before concluding.

---

## PRV-2xxx — SQL: parsing, planning, and what the engine will not run

Every code in this family is reachable from `pravaha validate --sql`, which needs no server, and
from `POST /api/v1/queries/validate`, which needs no registration. Both are product surfaces; prefer
the CLI, and confirm the REST path gives the same code for the same SQL.

## ERRC-018 — PRV-2001 SQL_PARSE_FAILED
**Reached through:** `SqlPlanner.java:101`.
**Falsifier:** malformed SQL produces `PRV-2002` or a Calcite exception.
**Setup:** `SELECT FROM`, `SELEC * FROM txn`, an unclosed string, an unclosed parenthesis, `;;`.
**Expected:** `PRV-2001` for each. E3 must carry the **position** — line and column — because that is
the only actionable content a parse error has. E4: documented.

## ERRC-019 — PRV-2002 SQL_VALIDATION_FAILED
**Reached through:** five sites, and **two of them are not SQL at all**:
`SqlPlanner.java:109` and `StreamCatalog.java:46` are; `PravahaNode.java:211` (a stream declared
with no schema), `:242` (an event-time column that does not exist) and `:382` (`idle-after` out of
bounds) are **startup configuration refusals wearing an SQL code**.
**Falsifier:** all five are described by one cause in `TROUBLESHOOTING.md`.
**Setup:** `SELECT nosuch FROM txn` (SQL); then CFG-007's no-schema file, CFG-008's misspelled
event-time file, and CFG-026's `idle-after: 1h` file.
**Expected:** `PRV-2002` in all four, E3 satisfied in all four. **E4 fails**: the document's table
row is `SQL_VALIDATION_FAILED | sql`, and three of the five throw sites are configuration errors
raised before any SQL exists. An operator whose node will not start looks up a code labelled "sql".
**Finding — and note it is the same class of defect as PRV-7002 (ERRC-095), one code meaning
several things.**

## ERRC-020 — PRV-2003 SQL_UNKNOWN_STREAM
**Reached through:** `StreamCatalog.java:62`.
**Falsifier:** an unknown stream produces `PRV-2002`.
**Setup:** `SELECT * FROM nosuch` against a node with `txn` declared, and against a node with
nothing declared (CFG-088).
**Expected:** `PRV-2003`, E3 listing the known streams — *"Object 'txn' not found. Known streams:
[]"*. The empty-list rendering is the one `StreamDeclarationProperties.java:33-38` calls baffling;
confirm it still is, and that the second run lists `[txn]`. E4: documented.

## ERRC-021 — PRV-2010 SQL_PLANNING_FAILED
**Reached through:** `SqlPlanner.java:116,121`.
**Falsifier:** a planner failure surfaces as a Calcite exception.
**Setup:** SQL that parses and validates and cannot be planned — `SQLX` owns finding these; take two
from its refusal list.
**Expected:** `PRV-2010`, E3 distinguishing it from `PRV-2020`: *planning failed* versus *this
operator is not supported*. If the message does not make that distinction, E3 fails, because the two
have completely different remedies. E4: documented.

## ERRC-022 — PRV-2020 SQL_UNSUPPORTED_OPERATOR
**Reached through:** **twenty-four sites in `PhysicalPlanBuilder`** (lines 114, 141, 164, 176, 233,
403, 417, 423, 431, 460, 480, 499, 595, 611, 637, 675, 682, 700, 719, 725, 735, 760, 983, 1004).
**Falsifier:** a refusal names an operator the user did not write, without explanation.
**Setup:** `ORDER BY`, `LIMIT`, `UNION`, an outer join between two streams, and five more chosen to
hit distinct sites.
**Expected:** `PRV-2020` each time. E3: the message must name the operator **and** point at
`SQL_SUPPORT.md`, which `TROUBLESHOOTING.md:50-58` says is checked by a test and therefore true
rather than aspirational. Confirm the pointer exists in the message, not only in the document.
E4: documented.
**Coverage note for this case:** twenty-four sites and one code. `SQLX` (190 cases) owns which
constructs are refused; this case owns that the refusal is legible and looked-up-able. Do not
duplicate `SQLX` here.

## ERRC-023 — PRV-2021 SQL_UNSUPPORTED_EXPRESSION
**Reached through:** nineteen sites across `ExpressionCompiler`, `PredicateCompiler` and
`Constant.java:72`.
**Falsifier:** the message names a function the user did not type and says nothing about why.
**Setup:** `SELECT SQRT(amount) FROM txn` — `TROUBLESHOOTING.md:53-56` states Calcite rewrites this
to `POWER(x, 0.5)` before the engine reads it, so **the refusal names `POWER` for SQL containing
`SQRT`**. Also `DECIMAL` arithmetic and three functions the engine does not have.
**Expected:** `PRV-2021`. **E3 is the case:** the message must either name the function the user
typed or explain the rewrite. If it names only `POWER`, E3 fails as a message — and the document
covers for it, which is a documentation patch over a message defect. Record it that way. E4:
documented.

## ERRC-024 — PRV-2041 SQL_EMIT_MODE_MISMATCH
**Reached through:** `ChangelogAnalysis.java:125`.
**Falsifier:** no product surface reaches it.
**Setup:** a query whose changelog mode conflicts with its sink — `INCR` and `SQLX` know the shapes;
take one that `ChangelogAnalysis` refuses.
**Expected:** `PRV-2041`, E3 naming both modes and which side wants which. This is the code
`ErrorCodeTest` uses as its example throughout, so it is the best-known number in the codebase; if
it turns out to be unreachable from any surface, that is worth saying loudly. E4: documented.

## ERRC-025 — PRV-2050 SQL_UNBOUNDED_STATE
**Reached through:** `PhysicalPlanBuilder.java:782,811`.
**Falsifier:** `GROUP BY user_id` with no window is accepted against a stream.
**Setup:** `SELECT usr, SUM(amount) FROM txn GROUP BY usr` (refused); the same SQL against a
**view** (allowed — `TROUBLESHOOTING.md:44-47`); and the windowed form `QW` (allowed).
**Expected:** `PRV-2050` for the first only. E3: the message must explain the *time* dimension —
that it does not fail on the day you deploy it — and offer the windowed rewrite.
`TROUBLESHOOTING.md:34-48` gives the full rewrite; check how much of it is in the message.
**Vacuity:** the view and windowed runs are the controls; a build that refused all `GROUP BY` would
pass the first assertion and fail these. E4: documented, at length, and correctly.

## ERRC-026 — PRV-2060 SQL_PARAMETER_NOT_BOUND
**Reached through:** `BoundParameters.java:81`.
**Setup:** register `SELECT * FROM txn WHERE usr = ?` with no bound value; via CLI and via Flight
prepared statement.
**Falsifier:** an unbound parameter becomes `NULL` and the query matches nothing —
`TROUBLESHOOTING.md:120-122` warns that `= NULL` matches no rows, which is exactly how this failure
would hide.
**Expected:** `PRV-2060`, E3 naming the ordinal. E4: documented.
**Vacuity:** confirm the same query **with** the parameter bound returns rows, so "no rows" is not
being read as a refusal.

## ERRC-027 — PRV-2061 SQL_PARAMETER_ARITY
**Reached through:** `BoundParameters.java:94`. **Setup:** two `?` and one bound value; one `?` and
two. **Expected:** `PRV-2061`, E3 giving both counts — *"expected 2, got 1"*. E4: documented.

## ERRC-028 — PRV-2062 SQL_PARAMETER_TYPE
**Reached through:** three sites — `BoundParameters.java:132`, `ParameterMetadata.java:122`,
`Constant.java:136`. **Setup:** bind a string to an `INT64` parameter; bind via Flight with an Arrow
type the parameter is not; bind a literal that will not narrow. **Expected:** `PRV-2062`, E3 naming
the ordinal, the expected type and the supplied one. E4: documented.

## ERRC-029 — PRV-2063 SQL_PARAMETER_NOT_A_VALUE
**Reached through:** `ParameterMetadata.java:81,108`. **Setup:** a `?` in a position that is not a
value — `SELECT ? FROM txn`, `GROUP BY ?`, `FROM ?`. **Expected:** `PRV-2063`, E3 explaining that a
parameter may stand for a value and not for an identifier or a table. E4: documented.

---

## PRV-3xxx — runtime and code generation

## ERRC-030 — PRV-3001 RUNTIME_ARENA_EXHAUSTED
**Reached through:** nine sites — `WindowedAggregate.java:278`, `WindowAssign.java:68`,
`KeyedAggregate.java:129`, `GlobalAggregate.java:151`, `InterpretedPipeline.java:643,705`,
`SymmetricHashJoin.java:169,204`, `LookupJoin.java:308`.
**Falsifier:** the arena ceiling is reached and the lane dies with no code.
**Setup:** CFG-048's wide-row query (rows wider than `33554432 / 512 = 65536` bytes).
**Expected:** `PRV-3001`. **E3 fails by construction for six of the nine**: they tell the operator to
raise `arena.slab.size`, which is not a configuration key (CFG-048). A message naming an
inapplicable remedy is worse than one naming none, because the operator spends the afternoon looking.
Record each of the nine messages verbatim and mark which name `arena.slab.size`. E4: documented,
with `TROUBLESHOOTING.md:141` saying *"usually a batch far larger than expected"* — which is a cause
and not a remedy, and does not mention that the remedy the message names does not exist.
**Vacuity:** the query must actually exceed the ceiling; compute `512 × width` and show it exceeds
`8 × 4194304` before running.

## ERRC-031 — PRV-3002 RUNTIME_BACKPRESSURED
**Reached through:** `IngestPump.java:103,165`, `PartitionedIngestPump.java:105,169`.
**Falsifier:** a full inbox drops rows silently, or blocks for ever.
**Setup:** a source faster than the lane — feed a large file into a query with an expensive
projection; the inbox is 2048 cells (`LaneConfig.defaults()`).
**Expected:** `PRV-3002`. E3 must say what to do: slow the source, or raise the inbox — **and the
inbox is not configurable either (CFG-056)**, so check whether this message repeats CFG-048's
problem. E4: documented.
**Vacuity:** confirm rows are being delivered before the backpressure, so the case is not passing on
a source that never started.

## ERRC-032 — PRV-3010 RUNTIME_LANE_FAILED
**Reached through:** twelve sites — `Lane.java:586,631,649`, `InterpretedPipeline.java:397,414,422,
431,444,453`, `DeduplicatingSink.java:97,125`, `PravahaFlightServer.java:238`.
**Falsifier:** a lane dies and the query still reports RUNNING.
**Setup:** provoke one from `Lane` (a stage throwing) and one from `InterpretedPipeline`.
**Expected:** `PRV-3010`; the query's `failure()` is populated; `pravaha queries` shows a terminal
state; `pravaha_query_running` goes to 0; and `/actuator/health` reports it in `failedQueries` and
`firstFailure` **while staying UP** — one broken query is not a broken node
(`EngineHealthIndicator`'s javadoc). Confirm all five. E4: documented.
**Vacuity:** a healthy query registered alongside must stay running and keep delivering, or the
case has proved the node died rather than the lane.

## ERRC-033 — PRV-3020 RUNTIME_UNSUPPORTED_AGGREGATE
**Reached through:** ten sites across `KeyedAggregate`, `GlobalAggregate`, `SlicedAggregateState`
and `QueryExecution.java:478`.
**Falsifier:** an unsupported aggregate is refused at planning with `PRV-2020` instead, so this code
is unreachable.
**Setup:** an aggregate the planner accepts and the runtime does not — this is the pair to look for.
If the planner catches every case, **record `PRV-3020` as unreachable from a product surface**,
which is a good outcome and still a finding.
**Expected:** either a reach with E1–E4, or a documented finding of unreachability with the
reasoning. E4: documented.

## ERRC-034 — PRV-3021 RUNTIME_UNSUPPORTED_JOIN
**Reached through:** nine sites — `JoinKeys.java:54,61,98,163`, `QueryExecution.java:452,580`,
`InterpretedPipeline.java:584`, `LookupJoin.java:344,373`.
**Falsifier / Setup / Expected:** as ERRC-033, with `JOIN`'s refusal list as the source of
candidates. `JoinKeys` validates key columns, which is the likeliest live reach: a join on
incompatible key types. E4: documented.

## ERRC-035 — PRV-3100 CODEGEN_COMPILATION_FAILED
**Reached through:** `StageCompiler.java:95,101`.
**Falsifier:** a compilation failure surfaces as a `javax.tools` diagnostic dump.
**Setup:** hard to reach deliberately — generated code that does not compile is a bug, not an input.
Try: a projection with an expression whose generated Java is invalid, and a run on a JVM with no
compiler available (a JRE rather than a JDK).
**Expected:** the second is the realistic reach and is a **deployment** condition, so it must be
legible: `PRV-3100`, E3 naming that no Java compiler is available and that a JDK is required. If it
instead throws an NPE on a null `JavaCompiler`, that is the finding. E4: documented.

## ERRC-036 — PRV-3101 CODEGEN_UNSUPPORTED_OPERATOR
**Reached through:** `PredicateSource.java:84,96`, `FilterProjectGenerator.java:220,239`.
**Falsifier:** codegen refuses something the interpreter would run, and the query fails rather than
falling back.
**Setup:** an expression the generator does not handle.
**Expected:** **the important question is whether `PRV-3101` reaches a user at all, or whether the
engine falls back to `InterpretedPipeline`.** If it falls back, the code is unreachable and that is
correct — record it. If it reaches the user, E3 must distinguish it from `PRV-2021`, which says the
same thing at a different layer. E4: documented.

## ERRC-037 — PRV-3102 CODEGEN_STAGE_TOO_LARGE
**Reached through:** `StageCompiler.java:71` and `StageCompilation.java:70`, bounded by
`MAX_SOURCE_LINES = 4_000`.
**Falsifier:** a very large query produces a `MethodTooLarge` or a silent truncation.
**Setup:** a `SELECT` with enough projected expressions to generate more than 4000 source lines.
`SQLX` owns "very large queries"; borrow its generator.
**Expected:** `PRV-3102`, E3 naming the limit `4000` and the actual line count so the operator can
see how far over they are. E4: documented.
**Vacuity:** run a query just under the limit first and confirm it compiles and answers, so the
refusal is attributable to size.

---

## PRV-4xxx — state, backfill and serving

## ERRC-038 — PRV-4001 STATE_TOO_LARGE
**Reached through:** `RowStore.java:117,142` and `SymmetricHashJoin.java:232`.
**Falsifier:** state grows past its ceiling and the process dies of `OutOfMemoryError` instead.
**Setup:** a join whose per-key state exceeds a `RowStore` block; `MIN_BLOCK_BYTES = 64` and
`HEADER_BYTES = 16`, so a row wider than `64 − 16 = 48` bytes will not fit a minimum block.
**Expected:** `PRV-4001`. E3: `RowStore.java:117-119` says *"raise state.slab.size for this query"*
— **and `state.slab.size` is not a key (CFG-049)**, so E3(b) fails exactly as ERRC-030 does.
E4: documented at `TROUBLESHOOTING.md:139` as *"An operator's state passed its ceiling"* — a
restatement of the name, with no remedy. **Both the message and the document fail to give an
applicable action.**

## ERRC-039 — PRV-4002 STATE_UNREADABLE — **declared and never thrown**
**Reached through:** nothing. No throw site in main sources.
**Falsifier:** any product surface produces `PRV-4002`.
**Setup:** the obvious candidates — corrupt a checkpoint file under `$QA/ckpt` (truncate it,
zero it, flip a byte in the middle) and restart with `checkpoint.directory` set so restore runs;
make a checkpoint file unreadable (`chmod 000`) and restart.
**Expected:** **none produces `PRV-4002`.** Record what each does produce, and — because this is a
recovery path — whether the node starts at all, starts with an empty view, or starts with *wrong*
state. A silently-empty restore is the outcome to watch for: CFG-096 asserts the three canonical
rows come back, and a corrupt checkpoint that yields zero rows with no error is indistinguishable
from CFG-094.
**E4:** documented at `TROUBLESHOOTING.md` as `STATE_UNREADABLE | state/serving`, so the document
promises a code the engine never emits. **Finding: unreachable but documented — the inverse of
`PRV-5090`.**
**Vacuity:** confirm a clean restore works first (CFG-096), so "no error" is attributable to the
corruption not being detected rather than to restore not running.

## ERRC-040 — PRV-4010 BACKFILL_BUFFER_FULL
**Reached through:** `SplicedReader.java:375`.
**Falsifier:** no product surface reaches the backfill module.
**Setup:** `pravaha-backfill` is reached through — establish this first. If no CLI verb, REST path,
Flight action or SDK method starts a backfill, then all six `PRV-401n` codes are unreachable from
any product surface and that is one finding, not six.
**Expected:** if reachable: `PRV-4010`, E3 naming the buffer size and what to do. If not: record the
module as product-unreachable and note that six codes and a `TROUBLESHOOTING.md` section describe a
feature with no entry point. E4: documented.

## ERRC-041 — PRV-4011 BACKFILL_MISSING_VERSION
**Reached through:** `SplicedReader.java:294`. Same reachability question as ERRC-040.
**Expected:** E3 must name which version was expected and what was found. E4: documented.

## ERRC-042 — PRV-4012 BACKFILL_UNSUPPORTED_KEY
**Reached through:** `SplicedReader.java:321`. Same reachability question.
**Expected:** E3 names the key type and the supported ones. E4: documented.

## ERRC-043 — PRV-4013 BACKFILL_MALFORMED_OFFSET — **declared and never thrown**
**Reached through:** nothing. The four plugin families each have their own `MALFORMED_OFFSET`
(5052, 5063, 5073, 5084) and all four are thrown; the backfill one is not.
**Falsifier:** any surface produces `PRV-4013`.
**Setup:** hand a backfill a malformed offset token, if a surface for doing so exists.
**Expected:** unreachable. **E4: documented**, so the document again promises a code the engine
cannot emit. Group this finding with ERRC-039 and the seven others.

## ERRC-044 — PRV-4014 BACKFILL_NOT_CAUGHT_UP
**Reached through:** `ShadowDeployment.java:129`. Same reachability question as ERRC-040.
**Expected:** E3 must say how far behind and what would count as caught up — a "not yet" error
without a distance is not actionable. E4: documented.

## ERRC-045 — PRV-4015 BACKFILL_SEAM_WENT_BACKWARDS
**Reached through:** `ShadowDeployment.java:165`. Same reachability question.
**Expected:** E3 names both seam positions. This is a correctness alarm, not an operational one, so
the message must make clear that the result would have been wrong. E4: documented.

## ERRC-046 — PRV-4020 SERVING_NO_HISTORY
**Reached through:** `ServedView.java:271`.
**Falsifier:** a read at a timestamp the view no longer holds returns the current state instead.
**Setup:** `QW` with a short retention; read `FOR SYSTEM_TIME AS OF` a moment before the retention
window, via the CLI's `query` and via Flight.
**Expected:** `PRV-4020`, E3 naming the requested timestamp and the oldest available one — without
the second half the caller cannot pick a valid timestamp. E4: documented.
**Vacuity:** a read inside the retention window must succeed and return the canonical rows.

## ERRC-047 — PRV-4021 SERVING_READ_TIMED_OUT
**Reached through:** `ServedView.java:308`.
**Falsifier:** a read that cannot be served waits for ever.
**Setup:** a read-consistency mode that waits for a frontier that will not arrive — `LIFE` owns the
four modes; take the one that waits, against a query whose watermark is pinned (CFG-025's
zero-window configuration pins it).
**Expected:** `PRV-4021`, E3 naming the deadline and the frontier waited for. Distinguish from
`PRV-4027` and `PRV-4029`, which are also timeouts in the read path with different causes — if the
three messages do not distinguish themselves, E3 fails for all three. E4: documented.
**Vacuity:** the elapsed time must be within a second of the configured deadline; a timeout that
fires immediately is a different bug wearing this code.

## ERRC-048 — PRV-4022 SERVING_VIEW_TOO_LARGE
**Reached through:** `ServedView.java:235`.
**Falsifier:** a view grows past its ceiling silently.
**Setup:** an unwindowed view over a high-cardinality key, fed until the ceiling is hit.
**Expected:** `PRV-4022`. E3: `TROUBLESHOOTING.md:137` states *"Retention is applied before this
check, so hitting it means either the view keeps everything and should not, or the window genuinely
holds more rows than the ceiling. **The message says which.**"* That last sentence is the
assertion: **the message must say which of the two it is.** If it does not, the document is
describing a message that does not exist. E4: documented, and this is the one row in the document
that makes a checkable claim about a message.

## ERRC-049 — PRV-4023 SERVING_NO_SUCH_VIEW
**Reached through:** `ViewQuery.java:175,297,425` — three entry points into the read path.
**Falsifier:** an unknown view name returns an empty result rather than a failure.
**Setup:** `pravaha query --sql "SELECT * FROM nosuch"`; the same after dropping a query that
existed; the same against a paused query.
**Expected:** `PRV-4023` for the first two. E3: the message names the views the server **does**
serve (`TROUBLESHOOTING.md:60-66`), which is the actionable part — confirm the list is present and
correct. The third (paused) must **not** be `PRV-4023`: a paused query keeps answering at the
frontier it reached. E4: documented, with the three usual causes.
**Vacuity:** the paused case is the control that distinguishes "no such view" from "view not
advancing".

## ERRC-050 — PRV-4024 SERVING_RESULT_TOO_LARGE
**Reached through:** `ViewQuery.java:210`, bounded by `MAX_RESULT_ROWS = 1_000_000`.
**Falsifier:** a result larger than a million rows is truncated silently.
**Setup:** a view with more than `1_000_000` keys, read without a filter; and one with exactly
`1_000_000`, which must **succeed**.
**Expected:** `PRV-4024` for the first, success for the second. E3 names the limit and the actual
size, and suggests a filter or a windowed read. **Truncation instead of a failure is the finding to
watch for** — round 1 had a row-count assertion pass while 200 000 rows collapsed into 500.
E4: documented.
**Vacuity:** the boundary pair `1_000_000` / `1_000_001` is the whole case; a build with no limit
passes the first and fails the second.

## ERRC-051 — PRV-4025 SERVING_UNSUPPORTED_QUERY
**Reached through:** `ViewQuery.java:580`.
**Falsifier:** SQL that a view read cannot serve is refused with `PRV-2020` instead, making the
serving code unreachable.
**Setup:** SQL valid against a stream and not against a view. `TROUBLESHOOTING.md:44-47` says
`GROUP BY` with no window is **allowed** over a view, so that is not the case; look for the reverse.
**Expected:** `PRV-4025`, E3 distinguishing "not supported against a view" from "not supported at
all". If no such SQL exists, record the code as unreachable. E4: documented.

## ERRC-052 — PRV-4026 SERVING_READ_REJECTED
**Reached through:** `ReadAdmission.java:138`.
**Falsifier:** an over-capacity node queues indefinitely rather than rejecting.
**Setup:** saturate the read path — more concurrent reads than permits and more waiting than the
queue holds.
**Expected:** `PRV-4026`. **E3 and the Flight status together:** `TROUBLESHOOTING.md:85-89` states
it arrives as `RESOURCE_EXHAUSTED` so a driver retries with backoff rather than giving up. Assert
the gRPC status is `RESOURCE_EXHAUSTED` and **not** `INVALID_ARGUMENT`. E3 must say "retry with
backoff", per the document's own table. E4: documented, in a dedicated three-row table.
**Vacuity:** an unsaturated node must serve the same read, or the case has proved nothing about
admission.

## ERRC-053 — PRV-4027 SERVING_READ_QUEUE_TIMED_OUT
**Reached through:** `ReadAdmission.java:149,156`.
**Falsifier:** indistinguishable from `PRV-4026` at the client.
**Setup:** saturate with a queue long enough to accept the read and slow enough that it gives up.
**Expected:** `PRV-4027`, `RESOURCE_EXHAUSTED`, and E3 saying *the node is saturated for longer than
your patience* rather than *retry* — the two codes exist because the fix differs, and the messages
must differ accordingly. E4: documented.

## ERRC-054 — PRV-4028 SERVING_TENANT_QUOTA_EXCEEDED
**Reached through:** `ReadAdmission.java:123`.
**Falsifier:** one tenant can hold every permit.
**Setup:** two tenants (`acme` via `errc-token-ann`, `other` via `errc-token-bob`); saturate with
`acme` and then read as `other`.
**Expected:** `acme`'s excess reads get `PRV-4028`; **`other`'s read succeeds**, because capacity is
still free and the quota protects the other tenants
(`OPERATIONS.md:85-88`). E3 must say that capacity may still be free. `RESOURCE_EXHAUSTED`.
E4: documented.
**Vacuity:** `other`'s success is the case. Without it, `PRV-4028` is indistinguishable from
`PRV-4026`.

## ERRC-055 — PRV-4029 SERVING_READ_DEADLINE_EXCEEDED
**Reached through:** `ViewQuery.java:229`, checked every `DEADLINE_CHECK_ROWS = 4_096` rows.
**Falsifier:** a read that exceeds its deadline runs to completion.
**Setup:** a read over a large view with a short deadline.
**Expected:** `PRV-4029`. E3 names the deadline and how far it got. **Boundary worth checking:** the
deadline is only observed every 4096 rows, so a view of fewer than 4096 rows **cannot** produce this
code however long it takes. Confirm that, and record it — it is a real limit on a real control.
E4: documented.
**Vacuity:** the same read with a generous deadline must complete; otherwise the case has found a
slow read, not a deadline.

---

## PRV-5xxx — plugins, and the server's own ingest family

## ERRC-056 — PRV-5001 PLUGIN_MISSING_SETTING
**Reached through:** `PluginContext.MISSING_SETTING`, aliased as `PluginErrors.MISSING_SETTING`
(fact 2). Live reach: `pravaha.sources.txn.options` missing `path` for the filesystem plugin
(CFG-011), which surfaces wrapped in `PRV-5091`.
**Falsifier:** a missing required option produces a generic failure with no setting name.
**Setup:** CFG-011's empty-options file; register `QW`.
**Expected:** `PRV-5091` at the top with `PRV-5001` as the cause. **E1/E2 must be checked on the
cause, not the wrapper** — and the case's real question is whether the cause's code survives to the
client at all. If the CLI prints only `PRV-5091`, the operator cannot look up the setting-level
code. E3: names the setting and the plugin. E4: documented — but see ERRC-087, where the wrapper is
**not**.

## ERRC-057 — PRV-5010 PLUGIN_NOT_FOUND
**Reached through:** `PluginRegistry.java:96`.
**Falsifier:** `PluginSourceFeeds.discover` throws `PRV-5090` for the same condition, making this
one unreachable from the server.
**Setup:** request a plugin by a name nothing provides, through whatever surface uses
`PluginRegistry` rather than `PluginSourceFeeds` — establish which surfaces those are.
**Expected:** either a reach with E1–E4, or the finding that the server path uses `PRV-5090` and
`PRV-5010` is reachable only from the embedded engine. **Two codes for "no such plugin" in one
product is itself worth recording** (`IngestErrors.java:20-26` explains the 509n numbering but not
the duplication of meaning with 5010). E4: documented.

## ERRC-058 — PRV-5011 PLUGIN_INCOMPATIBLE_API
**Reached through:** `PluginRegistry.java:66`.
**Falsifier:** a plugin built against a different API version loads and fails later.
**Setup:** a plugin jar declaring an incompatible API version, placed on the classpath.
**Expected:** `PRV-5011` at load, E3 naming the plugin, the version it wants and the version this
engine provides. E4: documented.

## ERRC-059 — PRV-5012 PLUGIN_LOAD_FAILED — **declared and never thrown**
**Reached through:** nothing.
**Falsifier:** any surface produces `PRV-5012`.
**Setup:** a plugin jar whose `ServiceLoader` entry names a class that is absent; one whose class
throws from its constructor; one that is a corrupt zip.
**Expected:** none produces `PRV-5012`. `ServiceLoader` raises `ServiceConfigurationError`, and
`PluginSourceFeeds.discover` iterates it without a try. **Record whether a broken plugin jar on the
classpath prevents the node from starting at all**, which is the operationally important part and is
worse than a missing code. E4: documented. **Finding: unreachable but documented.**

## ERRC-060 — PRV-5013 PLUGIN_DUPLICATE_NAME
**Reached through:** `PluginRegistry.java:74`.
**Falsifier:** two plugins answering to one name and the engine silently picks one.
**Setup:** two jars both reporting `filesystem`.
**Expected:** `PRV-5013` from `PluginRegistry`. **Then the contrast:** `PluginSourceFeeds.discover`
returns the **first** match and closes the rest (`PluginSourceFeeds.java:163-177`) with no
duplicate check at all — so on the server's ingest path a duplicate name is resolved silently by
`ServiceLoader` order. Record both behaviours. E4: documented.

## ERRC-061 — PRV-5020 PLUGIN_CIRCUIT_OPEN — **declared and never thrown**
**Reached through:** nothing. There is no circuit breaker in main sources.
**Falsifier:** any surface produces `PRV-5020`.
**Setup:** make a lookup plugin fail repeatedly (the JDBC lookup against a database that refuses
every connection) and look for a breaker opening.
**Expected:** no breaker, no code; every call fails individually with `PRV-5070`. **E4: documented**,
so `TROUBLESHOOTING.md` describes a resilience mechanism this engine does not have. That is worse
than an unreachable code: it is a capability claim. **Finding.**

## ERRC-062 — PRV-5030 PLUGIN_CAPABILITY_MISMATCH
**Reached through:** `PluginRegistry.java:104`.
**Falsifier:** a plugin used for a role it does not support fails late and obscurely.
**Setup:** bind a **sink-only** plugin as a source, or a source-only plugin as a lookup.
**Expected:** `PRV-5030`, E3 naming the plugin, the capability asked for and the ones it has.
E4: documented.

## ERRC-063 — PRV-5040 FILESYSTEM_DECODE_FAILED
**Reached through:** twelve sites across `FilesystemSourcePlugin`, `FilesystemSinkPlugin` and
`FilesystemPartitionReader`.
**Falsifier:** a malformed CSV row is skipped silently.
**Setup:** `$QA/data/bad.csv` — a row with too few fields, a row with too many, a non-numeric value
in an `INT64` column, an unparseable timestamp, and a valid row after each bad one.
**Expected:** `PRV-5040` per bad row. E3 must name the **file and line**, because a decode failure
without a location is unusable on a large file. **Then the behavioural question:** does the query
fail, or does it skip the row and continue? Count the rows that arrive — with 4 bad rows among 12,
either 8 arrive and 4 are reported, or 0 arrive. **Both are defensible and only one is documented;
record which.** E4: documented.
**Vacuity:** the valid rows interleaved between the bad ones are what distinguish "skipped" from
"stopped".

## ERRC-064 — PRV-5050 DELTA_TABLE_UNREADABLE
**Reached through:** `DeltaSourcePlugin.java:95,117`.
**Setup:** `plugin: delta` with `path` pointing at a directory with no `_delta_log`; and at one
whose log is truncated.
**Expected:** `PRV-5050` wrapped in `PRV-5091`, E3 naming the path and what was missing.
E4: documented.

## ERRC-065 — PRV-5051 DELTA_UNSUPPORTED_TYPE
**Reached through:** `DeltaTypes.java:103,147`.
**Setup:** a Delta table with a column type the engine's 16 types do not cover (a `MAP`, a `STRUCT`,
a `DECIMAL(38,10)`).
**Expected:** `PRV-5051`, E3 naming the column, its Delta type and the supported set. E4: documented.

## ERRC-066 — PRV-5052 DELTA_MALFORMED_OFFSET
**Reached through:** `DeltaOffset.java:89`.
**Setup:** resume from a hand-edited offset token.
**Expected:** `PRV-5052`, E3 quoting the token. E4: documented.

## ERRC-067 — PRV-5053 DELTA_FILE_VACUUMED — **declared and never thrown**
**Reached through:** nothing.
**Falsifier:** any surface produces `PRV-5053`.
**Setup:** the genuine condition — a Delta table read from an offset whose data files have since
been vacuumed away. Create it: write a table, read to an offset, `VACUUM` with a zero retention,
resume.
**Expected:** **the read fails with `PRV-5054 DELTA_READ_FAILED`** (a file-not-found wrapped by
`DeltaScanFiles.java:80` or `DeltaPartitionReader.java:296`) rather than the purpose-built code.
This is the most *useful* of the nine unreachable codes — vacuuming is the single most common way a
Delta consumer breaks, and a generic read failure sends the operator to the wrong place.
**E4: documented.** **Finding, and the one to fix first in this group.**
**Vacuity:** confirm the same resume works **before** the vacuum, so the failure is attributable.

## ERRC-068 — PRV-5054 DELTA_READ_FAILED
**Reached through:** `DeltaScanFiles.java:80,131`, `DeltaPartitionReader.java:296`.
**Setup:** a parquet file removed from under a live read; an I/O error during a scan.
**Expected:** `PRV-5054`, E3 naming the file. Record how it differs from ERRC-067's output — if it
does not differ, the two conditions are indistinguishable at the client. E4: documented.

## ERRC-069 — PRV-5055 DELTA_UNSUPPORTED_FEATURE
**Reached through:** `DeltaScanFiles.java:100`.
**Setup:** a table with a reader feature the plugin does not implement — deletion vectors, column
mapping, or `v2Checkpoint`.
**Expected:** `PRV-5055`, E3 naming the feature by its protocol name so it can be matched against
the table's `protocol` entry. E4: documented.

## ERRC-070 — PRV-5060 FEEDFILE_DIRECTORY_UNREADABLE
**Reached through:** `FeedDirectory.java:105`, `FeedFileSourcePlugin.java:161`.
**Setup:** `plugin: feedfile` pointed at a path that does not exist, at a file rather than a
directory, and at a directory with mode `000`.
**Expected:** `PRV-5060` for all three, E3 distinguishing them — "does not exist", "is not a
directory" and "cannot be read" are three different fixes. E4: documented.

## ERRC-071 — PRV-5061 FEEDFILE_BAD_SCHEMA
**Reached through:** `FeedSchemas.java:45,74`.
**Setup:** a feed file whose header does not match the declared schema; a schema string the feedfile
plugin cannot parse.
**Expected:** `PRV-5061`, E3 showing both the declared and the observed shape. E4: documented.

## ERRC-072 — PRV-5062 FEEDFILE_DECODE_FAILED
**Reached through:** `CsvDecoder.java:89,155,177` and `ParquetDecoder.java:85,112`.
**Setup:** a malformed CSV row and a malformed parquet page, one each.
**Expected:** `PRV-5062`, E3 naming file and record index. Compare with `PRV-5040` (ERRC-063) —
two plugins, two decode codes, and the messages should be equally located. E4: documented.

## ERRC-073 — PRV-5063 FEEDFILE_MALFORMED_OFFSET
**Reached through:** `FeedFileOffset.java:60,68`.
**Setup:** a hand-edited offset token, and one with a non-numeric record index.
**Expected:** `PRV-5063` twice, E3 quoting the token. E4: documented.

## ERRC-074 — PRV-5064 FEEDFILE_FILE_GONE — **declared and never thrown**
**Reached through:** nothing.
**Falsifier:** any surface produces `PRV-5064`.
**Setup:** the genuine condition — delete a feed file that a reader has open and is part-way
through; and rotate one out from under a reader.
**Expected:** record what happens instead. On Linux an open file survives its directory entry, so
the reader may continue to EOF and then simply stop — **which is silence, not an error**, and is the
worst outcome of the three. Confirm whether the query notices. E4: documented. **Finding.**
**Vacuity:** confirm the reader was actually mid-file (rows delivered, more remaining) before the
deletion.

## ERRC-075 — PRV-5065 FEEDFILE_BAD_CONFIGURATION
**Reached through:** `FeedFileSourcePlugin.java:104,114,136,148`.
**Setup:** four option mistakes, one per site — a missing required option, a conflicting pair, an
unparseable value, an unsupported format.
**Expected:** `PRV-5065` four times with four distinguishable messages, each naming the option.
E4: documented.

## ERRC-076 — PRV-5070 JDBC_CONNECT_FAILED
**Reached through:** `JdbcSourcePlugin.java:185,242`, `JdbcLookupPlugin.java:142`.
**Setup:** a JDBC URL to a port nothing listens on; valid host, wrong credentials; a driver class
not on the classpath.
**Expected:** `PRV-5070` for the first two. The third may be `PRV-5074` — record which. E3 must
**not** include the password: check the message and the log for the connection string. **A
credential in an error message is a disclosure finding and outranks everything else in this case.**
E4: documented.

## ERRC-077 — PRV-5071 JDBC_QUERY_FAILED
**Reached through:** six sites across `JdbcPartitionReader`, `JdbcLookupPlugin`, `JdbcSourcePlugin`.
**Setup:** a query against a table that does not exist; a syntax error in the configured SQL; a
connection dropped mid-read.
**Expected:** `PRV-5071`, E3 carrying the database's own `SQLState`/message, which is the actionable
part — and **not** the bound parameter values, which are customer data. Check both. E4: documented.

## ERRC-078 — PRV-5072 JDBC_UNSUPPORTED_TYPE
**Reached through:** `JdbcTypes.java:76`.
**Setup:** a table with a `CLOB`, a `BLOB`, an array column, and a `DECIMAL(38,10)`.
**Expected:** `PRV-5072`, E3 naming the column, its JDBC type name and the supported set.
E4: documented.

## ERRC-079 — PRV-5073 JDBC_MALFORMED_OFFSET
**Reached through:** `JdbcOffset.java:74`. **Setup / Expected:** as ERRC-066. E4: documented.

## ERRC-080 — PRV-5074 JDBC_BAD_CONFIGURATION
**Reached through:** seven sites — `JdbcLookupPlugin.java:99,104,109,120,153`,
`JdbcSourcePlugin.java:117,228`.
**Setup:** seven option mistakes, one per site.
**Expected:** `PRV-5074` seven times, seven distinguishable messages. **The password check again:**
`JdbcLookupPlugin.java:99-120` is option validation and a message that echoes the whole options map
would include the password. Check. E4: documented.

## ERRC-081 — PRV-5080 AEROSPIKE_CONNECT_FAILED
**Reached through:** `AerospikeSourcePlugin.java:220`, `AerospikeClients.java:36`.
**Setup:** seed host nothing listens on; and the containerised case `TROUBLESHOOTING.md:146-150`
describes — a node reporting its bridge address, where **the symptom is a hang rather than an
error**.
**Expected:** `PRV-5080` for the first. For the second, **the documented symptom is a hang**, so the
assertion is that it still hangs (and how long) — a code would be an improvement and its absence is
the finding the document already records. E3 names the seed host. E4: documented, with the
`--network host` remedy in the connection-problems section rather than in the code table; note that
a reader who searches `PRV-5080` finds the table row, not the remedy. `AERO` owns the rest.

## ERRC-082 — PRV-5081 AEROSPIKE_OPERATION_FAILED
**Reached through:** `AerospikeLookupPlugin.java:161`, `AerospikeSinkPlugin.java:177,189,226`,
`LutScanReader.java:197`.
**Setup:** a write to a namespace that does not exist; a read of a set that does not exist; a scan
interrupted.
**Expected:** `PRV-5081`, E3 carrying the Aerospike result code, which is what the vendor's
documentation is indexed by. E4: documented.

## ERRC-083 — PRV-5082 AEROSPIKE_UNSUPPORTED_TYPE
**Reached through:** `AerospikeSchemas.java:97,146,158,173`.
**Setup:** a bin holding a map, a list, a GeoJSON value, and an HLL.
**Expected:** `PRV-5082` four times, E3 naming the bin and the type. E4: documented.

## ERRC-084 — PRV-5083 AEROSPIKE_BAD_CONFIGURATION
**Reached through:** **fifteen sites** — `AerospikeSchemas.java:61,67,104`,
`AerospikeSourcePlugin.java:120,126,133`, `AerospikeHosts.java:34,41`,
`AerospikeLookupPlugin.java:92,99,105`, `AerospikeSinkPlugin.java:103,112`,
`AerospikeStrategy.java:73,84`.
**Setup:** fifteen option mistakes, one per site. `AerospikeStrategy.java:73,84` covers an unknown
strategy name — one of the four `AERO` enumerates.
**Expected:** `PRV-5083` fifteen times with fifteen distinguishable messages. **This is the widest
single code in the product**; the case's value is finding the two or three sites whose message is a
restatement of the code name. E4: documented.

## ERRC-085 — PRV-5084 AEROSPIKE_MALFORMED_OFFSET
**Reached through:** `LutScanReader.java:125,133`. **Setup / Expected:** as ERRC-066, including the
non-numeric variant. E4: documented.

## ERRC-086 — PRV-5090 INGEST_NO_SUCH_PLUGIN
**Reached through:** `PluginSourceFeeds.java:172`. The one-line reach is CFG-010's `plugin: kafka`.
**Falsifier:** the code is documented, or it fires at startup rather than at first registration.
**Setup:** `base.yaml` with `pravaha.sources.txn.plugin: kafka`; start; `pravaha register`.
**Expected:** node **starts**; the registration fails with `PRV-5090`; E3 lists the available
plugins — *"Available: [filesystem, feedfile, jdbc, delta]"* — which is exactly the actionable
content. Also check the `plugin: ` unset variant (CFG-010), whose message contains `'null'`.
**E4: NOT DOCUMENTED.** `IngestErrors.java:20-26` explains the 509n numbering choice in detail; the
family never reached `TROUBLESHOOTING.md`. A support engineer searching `PRV-5090` finds nothing and
reads *"If a code is missing here it does not exist in the engine."*
**Finding — and this is the family the brief names first.**

## ERRC-087 — PRV-5091 INGEST_BINDING_FAILED
**Reached through:** `PluginSourceFeeds.java:141` (a `RuntimeException` from `configure`/`open`) and
`:148` (a checked `Exception` — "refused its configuration"). Two sites, two messages, one code.
**Setup:** CFG-011's empty options (missing `path`) for the first; a path that exists and cannot be
read for the second.
**Expected:** `PRV-5091` twice. E3: both name the plugin and the stream; check the cause's own code
survives (ERRC-056). Confirm `closeQuietly` ran — no file handle is left open after the failure
(`PluginSourceFeeds.java:118-125`); check with `lsof` on the node's pid.
**E4: NOT DOCUMENTED.** **Finding.**
**Vacuity:** the `lsof` check is the non-vacuous half — a `PRV-5091` with a leaked handle passes
every other assertion.

## ERRC-088 — PRV-5092 INGEST_FEED_FAILED
**Reached through:** `PumpingFeed.java:128,135` — *a feed stopped part-way through: the source
failed after the query was already running.*
**Falsifier:** a source that dies mid-read leaves the query RUNNING with no rows and no error.
**Setup:** a FIFO source; deliver 6 of the 12 rows; then make the source fail (close the writer end
after writing a partial line, or delete and recreate the FIFO).
**Expected:** `PRV-5092`. **Where does it surface?** The feed runs on the `pravaha-feed-<query>`
thread, so it cannot be returned to a caller — check `RegisteredQuery.failure()`,
`pravaha queries`' state column, `pravaha_query_running`, and `/actuator/health`'s `firstFailure`.
**If it reaches none of the four, the code is thrown into a log and the query looks healthy**, which
is the failure this file exists to find. E3 names the query and the underlying failure.
**E4: NOT DOCUMENTED.** **Finding.**
**Vacuity:** confirm 6 rows arrived before the failure, so the case is not passing on a feed that
never started.

---

## PRV-6xxx — the Flight gateway (which `ErrorCode.Category` calls CLUSTER)

## ERRC-089 — PRV-6100 FLIGHT_UNSUPPORTED_TYPE
**Reached through:** `ArrowSchemas.java:113` — a Pravaha type with no Arrow mapping, or the reverse
on `DoPut`.
**Falsifier:** an unmappable type produces a `ClassCastException` or a silently wrong Arrow type.
**Setup:** `TYPE` owns the 16 types; take the one(s) `ArrowSchemas` does not map, in a projected
column and in a `DoPut` payload.
**Expected:** `PRV-6100`, E3 naming the type and the direction. **E-category:** confirm
`FlightErrors.UNSUPPORTED_TYPE.category()` returns **`Category.CLUSTER`** (fact 5) and that
`ApiExceptionHandler.statusFor` would therefore return 500 — a Flight type error classified as a
cluster problem. It does not reach HTTP today; record the latent mapping. E4: documented, as
`gateway`.

## ERRC-090 — PRV-6101 FLIGHT_UNSUPPORTED_REQUEST
**Reached through:** `PravahaFlightSqlProducer.java:423,618`.
**Setup:** Flight SQL verbs this producer does not implement — `getSqlInfo` variants,
`getCrossReference`, `getExportedKeys`, `getImportedKeys`, `getPrimaryKeys`, `beginTransaction`.
**Expected:** `PRV-6101` for each, E3 naming the request and, ideally, what the server does support.
`API` owns the five implemented verbs; this case owns that the unimplemented ones refuse legibly
rather than returning empty. **An empty result for an unimplemented metadata call is the finding to
watch for** — a driver reads it as "no primary keys" rather than "not supported". E4: documented.

## ERRC-091 — PRV-6102 FLIGHT_BAD_HANDLE
**Reached through:** `ControlWire.java:110,115,120,126,136` and, through the alias,
`PravahaFlightSqlProducer.java:372,476` and `ArrowParameters.java:64,80,85,92,107`.
**Setup:** a hand-built `Action` that is not a Pravaha request; a truncated control payload; a
`register` action missing the key columns; a `getStream` with a ticket that is not a subscription
ticket; bound parameters carrying no rows.
**Expected:** `PRV-6102` for each. E2: the number's name is **`FLIGHT_BAD_HANDLE`** while the
constant that declares it is `ControlWire.BAD_REQUEST` — the alias at `FlightErrors.java:40` is what
reconciles them. Confirm the rendered name is `FLIGHT_BAD_HANDLE` on every surface, since that is
what `TROUBLESHOOTING.md` lists. E3: *"this Pravaha request is malformed"* appears at three of the
five `ControlWire` sites — **a message that names nothing specific fails E3(a)**; record it.
E4: documented.

## ERRC-092 — PRV-6103 FLIGHT_PARAMETERS_TOO_LARGE
**Reached through:** `StatementHandle.java:55`, bounded by `MAX_PARAMETER_BYTES = 1 << 20 = 1048576`.
**Falsifier:** a larger payload is truncated or accepted.
**Setup:** a prepared statement with bound parameters of exactly `1048576` bytes (**must succeed**)
and `1048577` bytes (**must fail**).
**Expected:** `PRV-6103` for the second only; E3 naming the limit and the actual size. E4:
documented.
**Vacuity:** the boundary pair is the case; a build with no limit passes the first and fails the
second.

## ERRC-093 — PRV-6104 FLIGHT_TLS_UNREADABLE
**Reached through:** `PravahaFlightServer.java:120,125` — CFG-072, CFG-073, CFG-074, CFG-075.
**Falsifier:** an unreadable certificate or key starts a plaintext node.
**Setup:** CFG's four TLS files.
**Expected:** `PRV-6104` in all four, at **startup**, naming the absolute path. Then the two cases it
does **not** cover: CFG-070 (key without certificate → plaintext, no code) and CFG-071 (certificate
without key → `NullPointerException`, no code). **`FlightErrors.java:46-49` states the code exists
because *"falling back to plaintext because a certificate was missing is how a deployment believes
it is encrypted for months"* — and CFG-070 is exactly that fallback, happening below this code.**
**E4: NOT DOCUMENTED.** `PRV-6104` is absent from `TROUBLESHOOTING.md`, which lists 6100–6103 and
stops. **Finding — a TLS failure at startup is precisely when an operator reaches for the
troubleshooting page.**

---

## PRV-7xxx — security

## ERRC-094 — PRV-7001 SECURITY_UNAUTHENTICATED
**Reached through:** six sites — `PrincipalMiddleware.java:96,103,115`,
`StaticTokenVerifier.java:83`, `TokenVerifier.java:45,56` — plus the HTTP filter's hand-written
response (`BearerTokenFilter.java:113-133`).
**Falsifier:** an unauthenticated caller is served, or the refusal reveals *why* the credential
failed.
**Setup:** `errc.yaml` (token auth). Four calls: HTTP with no header; HTTP with a bad token; Flight
with no credential; Flight with a bad credential.
**Expected:**
- **E1/E2:** `PRV-7001`, `SECURITY_UNAUTHENTICATED`, on all four.
- **HTTP status is 401** from the filter. Note there is **no `WWW-Authenticate` header** — check,
  because a 401 without one is not a valid HTTP challenge.
- **The shape:** the filter's JSON has **six** fields (`code`, `message`, `helpUrl`, `timestamp`,
  `path`, `status`); every other error on the surface has **five** (`ApiDtos.ApiError`).
  **Two error shapes on one API, which the comment above the method says three times it will not
  do (fact 7). Finding.**
- **E3 deliberately says little:** `TROUBLESHOOTING.md:78-82` states the message never says *why* —
  "expired" versus "unknown" versus "wrong signature" is three bits of an oracle. Confirm all four
  refusals carry the **same** message. **A message that distinguishes them is a security finding,
  not an E3 improvement.**
- **The latent 401/403 defect:** `statusFor` maps the whole `SECURITY` category to
  `HttpStatus.FORBIDDEN` (`ApiExceptionHandler.java:64`). Any `PRV-7001` thrown from a controller
  rather than the filter arrives as **403**. Construct one if a path exists; if none does, record
  the mapping as latent.
- **E4:** documented, in a dedicated table opposite `PRV-7002`.
**Vacuity:** an authenticated call with `errc-token-ann` must succeed against all four surfaces, or
the case has proved the node refuses everybody.

## ERRC-095 — PRV-7002 SECURITY_FORBIDDEN — **one code, four meanings**
**Intent:** Fifteen throw sites in five modules, and `TROUBLESHOOTING.md` documents one of the four
meanings (fact 10). This is the most-overloaded code in the product and the one whose documented
remedy is actively wrong for two-thirds of its sites.
**Falsifier:** the four meanings are distinguishable at the client without reading the message.
**Setup / Steps:** reach all four, in order:

| # | Meaning | Reach | Site |
|---|---|---|---|
| 1 | **authorization denied** | as `bob`, read a view `ann` owns, under `policy: authenticated` with ownership | `ViewQuery.java:184` |
| 2 | | as `bob`, register a query against a source `bob` may not read | `QueryRegistry.java:289` |
| 3 | | as `bob`, `getStream` on a subscription ticket belonging to `ann` | `PravahaFlightSqlProducer.java:487` |
| 4 | **the node is accidentally open** | CFG-057's file: `authentication: none`, `policy: permissive`, `allow-anonymous: false` | `PravahaNode.java:166` |
| 5 | **the node is contradictory** | CFG-061's file: `policy: authenticated`, `authentication: none` | `PravahaNode.java:183` |
| 6 | **a configuration value is not a name** | CFG-013's `policy: tenant` | `PravahaNode.java:290` |
| 7 | | CFG-014's `audit: log` | `PravahaNode.java:310` |
| 8 | **the two policy holders disagree** | CFG-080 — establish whether any configuration reaches it | `PravahaFlightServer.java:202` |

**Expected:**
- E1/E2: `PRV-7002`, `SECURITY_FORBIDDEN`, in all eight.
- **E3 passes site by site and fails as a code.** Each message is specific and actionable on its own;
  the *code* is not, because it cannot tell an operator which of four kinds of problem they have.
  Reaches 4–7 are **startup refusals printed to a log with a security-authorization code**, and
  reaches 1–3 are **runtime 403s**. One number.
- **E4 fails.** `TROUBLESHOOTING.md:76-82` says: *"Authenticated, not authorized — ask for access;
  a new credential will not help."* For reach 7 — a node that will not start because
  `pravaha.security.audit` is `log` — every word of that is wrong. Quote the document's advice
  against reach 7's message in the finding; the contrast is the whole argument.
- **Categories differ too:** reaches 1–3 reach HTTP or Flight and map to 403; reaches 4–8 never
  reach a status because the process is exiting.
**Finding:** `PRV-7002` should be split — at minimum, the four configuration refusals
(`PravahaNode.java:166,183,290,310`) belong in the `PRV-1xxx` configuration range, which is what
`ErrorCode`'s own doc comment says the number is *for*: *"the number alone tells an operator which
subsystem failed."* Here it tells them the wrong subsystem four times out of eight.
**Vacuity:** reaches 1–3 must be shown to succeed for `ann` and fail for `bob` on the same call, or
they are not authorization failures.

## ERRC-096 — PRV-7003 SECURITY_FILTER_NOT_ENFORCEABLE
**Reached through:** `ViewQuery.java:518,569`.
**Falsifier:** a row filter that cannot be enforced is silently dropped and the caller sees rows the
filter would have hidden.
**Setup:** a principal with a row filter, against a view whose shape makes the filter unenforceable —
a filter on a column the view does not project, or on an aggregated column.
**Expected:** `PRV-7003` and **no rows**. E3 must name the filter and the view and say that the
read was refused rather than narrowed. **The failure mode this guards is the worst kind: a filter
that quietly does nothing returns more data, not less.** E4: documented.
**Vacuity:** the same principal against an enforceable view must return the filtered rows, so the
refusal is attributable to enforceability rather than to the filter matching nothing —
`TROUBLESHOOTING.md:120-122` warns that a filter can silently match nothing, which is the
indistinguishable case.

---

## PRV-8xxx — the registry, which has no `Category`

Every case in this family carries one extra assertion, **E5**, because of facts 3 and 4:

> **E5 — the code has no category.** `ErrorCode.category()` throws
> `IllegalStateException("no category for PRV-8nnn")`. Therefore, if this code reaches
> `ApiExceptionHandler`, the handler itself throws, and the client gets whatever a servlet container
> does with an exception from an `@ExceptionHandler` — a 500 with an HTML error page, or an empty
> body — **instead of the `ApiError` the API contract promises for every non-2xx response**.
> For each code: determine whether any `/api/v1` path can throw it, and if so, capture the actual
> response. If no path can, record it as latent and say which future controller would trip it.

## ERRC-097 — PRV-8001 REGISTRY_NAME_IN_USE
**Reached through:** `QueryRegistry.java:748,761,774`.
**Falsifier:** registering a second query under an existing name replaces the first.
**Setup:** register `QW`; register `QW` again with **the same SQL**; then with **different SQL**.
**Expected:** `PRV-8001` for at least the second. **The first is the interesting one:** registrations
sharing a fingerprint share one computation and one copy of state, so identical SQL under the same
name may be idempotent rather than a conflict. Record which, and confirm the original query is
unharmed either way. E3 names the existing query. E4: documented. **E5:** the CLI reaches this over
Flight; determine whether any REST path does.
**Vacuity:** read the original view after the failed re-registration and confirm the three canonical
rows are still there.

## ERRC-098 — PRV-8002 REGISTRY_NO_SUCH_QUERY
**Reached through:** `QueryRegistry.java:669`, `SubscriptionFilter.java:83`.
**Setup:** `pravaha drop --name nosuch`, `pause`, `resume`; and a subscribe to a query dropped
mid-subscription.
**Expected:** `PRV-8002`, E3 listing the queries that do exist — the same actionable content
`PRV-4023` provides. **Note the pair:** `PRV-8002` (no such *query*) and `PRV-4023` (no such *view*)
are the same user mistake at two layers; check that the two messages do not contradict each other
about what exists. E4: documented. **E5** applies.

## ERRC-099 — PRV-8003 REGISTRY_ILLEGAL_TRANSITION
**Reached through:** `RegisteredQuery.java:249,269,333,345`.
**Setup:** the illegal transitions — `resume` a running query, `pause` a paused one, `pause` a
failed one, anything on a dropped one.
**Expected:** `PRV-8003` for each, E3 naming the current state and the attempted transition, and the
transitions that **are** legal from here. `LIFE` owns the state machine; this case owns the code.
E4: documented. **E5** applies.
**Vacuity:** a legal transition from each state must succeed, or the case has found a query stuck
rather than a guard working.

## ERRC-100 — PRV-8004 REGISTRY_QUERY_FAILED
**Reached through:** `Subscription.java:103,134`, `RegisteredQuery.java:191`.
**Setup:** a query that fails at runtime (ERRC-032's lane failure); then read it, and subscribe to
it.
**Expected:** `PRV-8004`, E3 carrying the **underlying** failure — a "query failed" with no cause is
not actionable. Confirm the original `PRV-3010` and its message survive. E4: documented. **E5**
applies.
**Vacuity:** a healthy query must remain readable and subscribable throughout.

## ERRC-101 — PRV-8005 REGISTRY_JOURNAL_UNREADABLE
**Reached through:** `RegistryJournal.java:163,185`.
**Setup:** CFG-020's corrupt-journal variants — a record from a newer version, a record with an
unknown field, a byte flipped in the middle, and a truncated final record.
**Expected:** `PRV-8005` for the version case and the mid-file corruption; **the truncated tail must
NOT fail** — replay keeps everything before it and ignores the tail (`OPERATIONS.md:397-399`).
E3 must say which record and why, and `TROUBLESHOOTING.md:391` gives *"this version does not
understand"* — **refused, not skipped, because skipping would silently drop a registration.**
Confirm the refusal is of the whole replay or of that record; the document says the registration is
not skipped, so a partial recovery that omits it would be the finding. E4: documented. **E5:** this
fires at **startup**, so it never reaches the HTTP handler — record it as latent.
**Vacuity:** the truncated-tail control distinguishes "refuses corruption" from "refuses anything
unusual".

## ERRC-102 — PRV-8006 REGISTRY_JOURNAL_UNWRITABLE
**Reached through:** `RegistryJournal.java:229,276`.
**Setup:** CFG-100 — journal made read-only under a running node, then a registration.
**Expected:** `PRV-8006` **and the registration refused** —
`TROUBLESHOOTING.md:393` states the registration is refused *"because acknowledging one that will
not survive a restart tells the client something untrue."* Assert `pravaha queries` shows the
count **unchanged**. E3 names the journal path. E4: documented, correctly and with the reasoning.
**E5:** reached over Flight from `pravaha register`; determine whether any REST path registers.
**Vacuity:** as CFG-100 — the count after the failure is the non-vacuous assertion.

## ERRC-103 — PRV-8007 REGISTRY_REPLAY_UNAUTHORIZED — **declared and never thrown**
**Reached through:** nothing. `grep -rn "REPLAY_UNAUTHORIZED"` finds only the declaration
(`RegistryErrors.java:65`).
**Falsifier:** any replay refusal carries `PRV-8007`.
**Setup:** the genuine condition, which is well documented: register a query as `ann`; stop the
node; change the configuration so `ann` no longer resolves (remove `ann`'s token) or so `ann` is no
longer permitted; restart and read the recovery log.
**Expected:** `QueryRegistry.Recovery.refused()` is populated and `PravahaNode.java:410` logs
*"registration not recovered -- {}"* per entry. `OPERATIONS.md:389-391` documents the two refusal
strings — *"contract ended"* and *"not a principal this deployment knows"* — as **plain text, not as
codes**. **So the refusal is real, documented, operationally important, and carries no code**, while
a code exists for exactly it and is never used.
**E4: documented** in `TROUBLESHOOTING.md`'s table, so an operator who sees a refusal cannot search
for it by code, and an operator who searches the code finds a row describing something they will
never see. **Finding.**
**Vacuity:** confirm the same query recovers cleanly when `ann` still resolves, so the refusal is
attributable to the authorization change.

---

## PRV-9xxx — the cluster, which has no `Category` and is not in the ranges table

Every case in this family carries **E5** (as PRV-8xxx) and one further assertion:

> **E6 — the ranges table.** `TROUBLESHOOTING.md:16-25` lists eight ranges, `PRV-1xxx` through
> `PRV-8xxx`. **`PRV-9xxx` is absent**, although all seven 9xxx codes appear in the full table
> below with the range label `cluster`. A reader who meets `PRV-9002` and consults the ranges
> section concludes the code is not Pravaha's. Assert the omission, once, and attach it to all seven.

## ERRC-104 — PRV-9001 CLUSTER_UNKNOWN_MECHANISM
**Reached through:** `CoordinatorFactory.java:67` (no provider by that name), `:80`, and `:115`
(**an unknown cluster *mode***).
**Falsifier:** an unknown mechanism or mode starts the node.
**Setup:** CFG-029's `mechanism: zookeeper` without the plugin; `mechanism: SOCKET` (case); and
CFG-028's `mode: HA`.
**Expected:** `PRV-9001` for all three, at **startup**, before any port is bound. E3 lists the
available mechanisms / the valid modes. **E2 is the finding here:** the name is
`CLUSTER_UNKNOWN_MECHANISM` and `:115` throws it for an unknown **mode**, which is a different
thing — the code's own name is wrong at one of its three sites. E4: documented as
`CLUSTER_UNKNOWN_MECHANISM | cluster`, with no mention of the mode case. **E5, E6** apply.

## ERRC-105 — PRV-9002 CLUSTER_INSUFFICIENT_GUARANTEE
**Reached through:** `CoordinatorFactory.java:90`.
**Falsifier:** `PARTITIONED` starts on a mechanism that cannot exclude split-brain.
**Setup:** CFG-028 — `mode: PARTITIONED` with `mechanism: single`, and with `mechanism: socket`.
**Expected:** `PRV-9002` at startup, for both. E3 is the best message in the product — it names the
mode, the mechanism, the guarantee it lacks, the consequence (*two nodes writing the same
aggregate… silent and durable*) and **two** remedies. Quote it in full as the E3 benchmark the rest
of this file is judged against. E4: documented, and `OPERATIONS.md:119-123` documents the refusal
by code. **E5, E6** apply.

## ERRC-106 — PRV-9003 CLUSTER_COORDINATOR_UNAVAILABLE
**Reached through:** `SocketCoordinator.java:134`, `ZooKeeperCoordinator.java:121`.
**Falsifier:** the coordinator is unreachable and the node starts anyway, or hangs.
**Setup:** `mechanism: socket` with peers pointing at ports nothing listens on — **which cannot be
configured (CFG-030)**, so the reach depends on CFG-030's blocker being fixed or on a direct
embedded construction. Record the blocker.
**Expected:** if reachable, `PRV-9003` naming the coordinator and what could not be reached. If not,
record it as **blocked by CFG-030**, which is a stronger statement than "unreachable": the code is
fine and the configuration path to it is broken. E4: documented. **E5, E6** apply.

## ERRC-107 — PRV-9004 CLUSTER_NOT_LEADER — **declared and never thrown**
**Reached through:** nothing.
**Falsifier:** any surface produces `PRV-9004`.
**Setup:** the genuine condition — a write-ish operation (`register`, `drop`) on a non-leader node
in a `REPLICATED` or `PARTITIONED` cluster. Blocked by CFG-030 in the same way as ERRC-106.
**Expected:** unreachable, and **doubly so**: no throw site, and no configuration path to a
multi-node cluster. E4: documented. **Record this as the clearest single example of the class**: the
document lists seven cluster codes, two of which cannot be produced and three of which cannot be
reached because their configuration keys are not forwarded. **E5, E6** apply.

## ERRC-108 — PRV-9005 CLUSTER_BAD_MEMBERSHIP
**Reached through:** **seven sites** — `SocketProvider.java:56,71`, `SocketCoordinator.java:91,97`,
`PartitionAssignment.java:75,150`, `ZooKeeperProvider.java:63`.
**Falsifier:** a valid peer list produces `PRV-9005`.
**Setup:** CFG-030 verbatim — `OPERATIONS.md:99-107`'s block, adapted to loopback.
**Expected:** `PRV-9005` from `SocketProvider.java:56` — *"the socket coordinator needs
pravaha.cluster.socket.peers"* — **on a configuration that supplies it.** E3 is correct English and
false in fact: the operator did set the key. **This is the worst E3 failure in the product, because
the message is actionable and the action has already been taken.** Then `mechanism: zookeeper` with
`connect` set gives the same shape from `ZooKeeperProvider.java:63`.
E4: documented. **E5, E6** apply.
**Vacuity:** confirm Spring bound the key (the node's own environment has it) before asserting the
message is false; otherwise the case has found a YAML mistake.

## ERRC-109 — PRV-9006 CLUSTER_HANDOFF_FAILED
**Reached through:** `PartitionHandoff.java:126,150,179`. Blocked by CFG-030 in the same way.
**Expected:** if reachable, `PRV-9006` naming the partition, the source and the destination node —
a handoff failure with no partition id is not actionable. If not, record as blocked. E4: documented.
**E5, E6** apply.

## ERRC-110 — PRV-9007 CLUSTER_REBALANCE_REFUSED
**Reached through:** `Rebalancer.java:121,129`. Blocked by CFG-030 in the same way.
**Expected:** if reachable, `PRV-9007` naming why the rebalance was refused and what would make it
acceptable — a refusal is an operational decision and the operator needs the criterion. If not,
record as blocked. E4: documented. **E5, E6** apply.

---

## Cross-cutting: properties of the code set as a whole

Eight cases. Each is a property of all 110 codes together, which no per-code case can establish.

## ERRC-111 — the inventory is complete and unique, both directions
**Intent:** Ground every other case in this file.
**Falsifier:** the counts below differ from what the executor finds.
**Steps:**
1. `grep -rn "new ErrorCode(" --include=*.java . | grep -v /target/ | grep -v src/test` → **110**.
2. Extract `(number, name)` from each; sort by number; assert **110 distinct numbers** and 110
   distinct names. Two declarations share a number only via the two documented aliases (fact 2), and
   those are assignments, not `new ErrorCode(...)` — so no number appears twice in step 1's output.
3. `grep -o "PRV-[0-9]\{4\}" docs/TROUBLESHOOTING.md | sort -u` → **100**.
4. `comm` the two sets both ways.
**Expected:** code-not-in-doc = exactly `{1030, 1031, 1040, 1041, 1042, 1043, 5090, 5091, 5092,
6104}`, **ten**. doc-not-in-code = **empty**. Run `ErrorCodeUniquenessTest` and confirm it passes,
then confirm it checks uniqueness and **not** completeness against the document — that gap is why
ten codes could go undocumented with a green build.
**Vacuity:** the doc-not-in-code direction being empty is what makes the other direction meaningful;
without it the document could simply be a different list.

## ERRC-112 — the document's closing claim is false
**Intent:** `TROUBLESHOOTING.md:270`: *"Generated from the source, not from memory: every row above
is an `ErrorCode` declared in a module's main sources. If a code is missing here it does not exist in
the engine."*
**Falsifier:** the ten codes from ERRC-111 do not exist in the engine.
**Steps:** for each of the ten, produce it from a product surface (ERRC-012, 013, 014, 015, 016, 017,
086, 087, 088, 093) and capture it.
**Expected:** **nine of the ten are produced** (`PRV-1043` is the exception — it exists and cannot be
thrown, ERRC-017). So the sentence is false nine times over, and the tenth failure is of a different
kind. **The remedy is a test, not an edit:** the first half of the sentence claims the table is
generated from the source and it is not. Propose the test that would make the claim true and would
have failed on the day `IngestErrors` was written.
**Vacuity:** each of the nine must be captured from a surface, not asserted from a grep — the claim
is about what the engine emits.

## ERRC-113 — `category()` throws for fourteen real codes
**Intent:** Facts 3 and 4. `ErrorCode.Category` covers 1000–7999; the registry (8001–8007) and
cluster (9001–9007) families are outside it.
**Falsifier:** `category()` returns a value for any 8xxx or 9xxx code.
**Steps:**
1. For all 110 codes, call `category()` and record the result. Expect 96 values and **14
   `IllegalStateException`s**, message `no category for PRV-nnnn`.
2. For each of the 14, determine whether any `/api/v1` path can throw it. `QueryController` and
   `StreamController` are the surfaces; `POST /api/v1/streams` with a name that collides is the most
   likely candidate — trace it.
3. Where one exists, provoke it and capture the **actual HTTP response**.
**Expected:** `ApiExceptionHandler.handle` calls `statusFor(e.errorCode())` at line 31, which calls
`category()` at line 63, which throws. The handler fails **while handling**, so the client receives
whatever the servlet container produces — a 500 with an HTML body or an empty one — **not the
`ApiError` the API contract promises for every non-2xx response, and not the code**. That is a
contract violation on the error path, which is the path clients handle worst.
`ErrorCodeTest.java:61` already asserts the throw for `8500`; the consequence is untested.
**Vacuity:** step 3 must produce an actual response. A case that stops at step 1 has proved a unit
test, not a defect.

## ERRC-114 — the `Category` ranges disagree with the families they are named for
**Intent:** Fact 5. `Category.CLUSTER` is `(6000, 6999)` — the Flight family. `Category.SECURITY` is
`(7000, 7999)` — correct. There is no `FLIGHT` and no `REGISTRY`.
**Falsifier:** `FlightErrors.UNSUPPORTED_TYPE.category()` returns something other than `CLUSTER`.
**Steps:** call `category()` on all five `PRV-61nn` codes; read `statusFor`'s switch; read
`TROUBLESHOOTING.md:16-25`'s ranges table.
**Expected:** all five report `CLUSTER`. `statusFor` maps `CLUSTER` to 500 — correct by luck for a
gateway error, wrong by construction. `TROUBLESHOOTING.md` calls `PRV-6xxx` "The Flight gateway",
agreeing with reality and disagreeing with the enum, and omits `PRV-9xxx` entirely (E6).
**Three descriptions of one numbering scheme: the enum, the document's ranges table, and the
document's full table. No two agree.** The enum is the one that decides an HTTP status, and it is
the wrong one.

## ERRC-115 — nine codes have no throw site
**Intent:** Collect ERRC-017, 039, 043, 059, 061, 067, 074, 103, 107 into one finding with one
cause and one remedy.
**Falsifier:** any of the nine is produced from any surface.
**Steps:** for each, run its case's Setup candidates; then, for each, determine which of three kinds
it is:
- **the condition cannot occur** (retire the code),
- **the condition occurs and another code is used** (`PRV-5053` → `PRV-5054`; ERRC-067),
- **the condition occurs and nothing is raised** (`PRV-5064` → silence; ERRC-074).
**Expected:** a nine-row table with the kind, the observed alternative, and the operational cost.
**Eight of the nine are documented in `TROUBLESHOOTING.md` with a cause**, so the document describes
eight failures the engine cannot report. The second and third kinds are the expensive ones:
`PRV-5053` (a vacuumed Delta file) and `PRV-5064` (a rotated feed file) are both **routine**
operational events on their respective sources.
**Vacuity:** each row needs its condition genuinely constructed, not assumed — ERRC-067 and ERRC-074
carry their own vacuity notes for that reason.

## ERRC-116 — every code's `helpUrl` and what is behind it
**Intent:** `ErrorCode.helpUrl()` is `https://docs.pravaha.io/errors/PRV-nnnn` for all 110
(fact 11), and it is a field in every `ApiError`, in the filter's hand-written 401, and in the CLI's
stderr (`PravahaCliTest.java:90` asserts it).
**Falsifier:** the URL is absent from any surface, or malformed for any code.
**Steps:** for every code reached in ERRC-001–110, assert the `helpUrl` field equals
`https://docs.pravaha.io/errors/` + the rendered code. Then resolve three of them.
**Expected:** the field is present and correct on all three surfaces. **Then the honest part:**
record whether `docs.pravaha.io` resolves at all. If it does not, every error in this product points
the operator at a dead link, and `docs/TROUBLESHOOTING.md` — which does exist — is not mentioned by
any message. **That is a one-line fix with a large effect and should be raised whatever the DNS
answer is**, because the ten undocumented codes (ERRC-111) have a `helpUrl` that cannot help by
construction.

## ERRC-117 — one error shape, checked across every surface
**Intent:** Three separate places in this codebase state that a client which parses two error shapes
will handle one of them badly: `application.yaml`'s `problemdetails` comment,
`ApiExceptionHandler`'s class javadoc, and `BearerTokenFilter.refuse`'s comment. **There are at
least three shapes.**
**Falsifier:** every non-2xx response from `/api/v1/**` and `/actuator/**` has exactly the five
`ApiDtos.ApiError` fields.
**Steps:** provoke and capture: a `PravahaException` through the handler (5 fields); an
`IllegalArgumentException` through the handler (5 fields, `code` = **`PRV-0400`** — a code that is
not an `ErrorCode` and is in no table); the filter's 401 (**6** fields); a 405 and a 415, which
`ApiExceptionHandler` does not handle at all; a 404 on an unmapped path; and the response from
ERRC-113, if one exists.
**Expected:** at least three distinct shapes, plus `PRV-0400` — **a `PRV-` code with no `ErrorCode`,
no name, no help URL and no row in `TROUBLESHOOTING.md`, produced by the same handler that produces
the real ones**. Add `PRV-0400` to the findings as an eleventh undocumented code, of a different
kind: it would fail `ErrorCode`'s own constructor, which requires four digits between 1000 and 9999.
**Vacuity:** the 405/415/404 responses are the ones no per-code case would ever have reached, which
is why this case exists.

## ERRC-118 — the Flight status every code arrives as
**Intent:** `FlightErrors.asFlightStatus` decides what a driver does. `TROUBLESHOOTING.md:87-89`
makes one claim about it — that `PRV-4026`/`4027`/`4028` arrive as `RESOURCE_EXHAUSTED` so a driver
retries rather than giving up — and none about the other 107.
**Falsifier:** a retryable failure arrives as `INVALID_ARGUMENT`, or a permanent one as
`UNAVAILABLE`.
**Steps:** for every code reached over Flight in ERRC-001–110, record the `FlightStatusCode` and the
description string. Build the table.
**Expected:** a 110-row (or as-many-as-reached) table with three columns: code, Flight status,
retryable-or-not. Then check three properties:
- the three admission codes are `RESOURCE_EXHAUSTED` (the document's only claim);
- `PRV-7001` is `UNAUTHENTICATED` and `PRV-7002` is `UNAUTHORIZED`/`PERMISSION_DENIED` — **and note
  that `PRV-7002`'s four meanings (ERRC-095) would all map to the same status**, so a driver cannot
  distinguish a configuration refusal from an authorization denial even in principle;
- every `PRV-2xxx` is `INVALID_ARGUMENT` and **not** retryable — a driver that retried a `PRV-2050`
  would loop for ever.
Any code whose status contradicts its meaning is a finding, because the status is what an
unattended client acts on and the message is what a human reads.

---

## Coverage note

**118 cases against a budget of 104, and the discrepancy is arithmetic rather than judgement.**

The index budgets `ERRC` at `001–104` — "every error code". `grep -rn "new ErrorCode(" --include=*.java
. | grep -v /target/ | grep -v src/test` returns **110**. The number 104 is exactly the count
excluding `sdk/pravaha-sdk-java`, which declares six: `PRV-1030`, `PRV-1031`, `PRV-1040`, `PRV-1041`,
`PRV-1042`, `PRV-1043`. The brief that commissioned this file names `PRV-1040`–`PRV-1043`
explicitly as codes to cover, so the SDK is in scope and the inventory is 110.

**ERRC-001 to ERRC-110 is one case per code, in numeric order**, so the file reads against
`TROUBLESHOOTING.md`'s own table line by line and a reviewer can find any code by its position. Each
carries the four assertions E1–E4 defined once in the method section; the 8xxx family adds E5 and
the 9xxx family adds E5 and E6.

**ERRC-111 to ERRC-118 are eight cross-cutting cases**, and they are not padding — each establishes
a property of the code set that no per-code case can reach:

| Case | The property |
|---|---|
| ERRC-111 | the inventory itself: 110 declared, 100 documented, 10 missing, 0 spurious |
| ERRC-112 | the document's closing claim is false nine times |
| ERRC-113 | `category()` throws for 14 real codes, **from inside the exception handler** |
| ERRC-114 | the enum, the ranges table and the full table describe three different numbering schemes |
| ERRC-115 | nine codes with no throw site, sorted into three kinds with three different remedies |
| ERRC-116 | every `helpUrl` points at a host that may not exist, and never at the document that does |
| ERRC-117 | three error shapes on one API, plus `PRV-0400`, an eleventh undocumented code |
| ERRC-118 | the Flight status table, which is what unattended clients act on |

**What is deliberately not here.**

1. **Which SQL is refused.** `SQLX` (190 cases) owns the constructs. ERRC-022 and ERRC-023 own
   whether the refusal is legible and looked-up-able, and borrow `SQLX`'s inputs rather than
   re-deriving them.
2. **Whether authorization decisions are correct.** `SECX` (95 cases) owns policy × auth × TLS × row
   filters × every verb. ERRC-094, 095 and 096 own whether the resulting failures carry the right
   code, the right status and a message an operator can act on.
3. **Plugin behaviour beyond the error path.** `AERO` (45 cases) owns Aerospike. ERRC-081 to
   ERRC-085 own its five codes.
4. **A per-code case for the two aliases as separate numbers.** `PluginErrors.MISSING_SETTING` and
   `FlightErrors.BAD_HANDLE` are the same numbers as `PluginContext.MISSING_SETTING` and
   `ControlWire.BAD_REQUEST`; they get one case each (ERRC-056, ERRC-091) with the alias asserted,
   not two.

**Twelve cases are expected to record an absence rather than a capture**: the nine unreachable codes
(ERRC-017, 039, 043, 059, 061, 067, 074, 103, 107) and the three blocked by CFG-030 (ERRC-106, 109,
110). Each is written to *construct the genuine condition first* and record what happens instead, so
the executor's log distinguishes "this code cannot be produced" from "we did not try hard enough".
That distinction is the whole value of writing them rather than skipping them.
