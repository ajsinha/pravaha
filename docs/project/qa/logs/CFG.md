# CFG — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Cases: [`../cases/CFG.md`](../cases/CFG.md). Executed 2026-09-14 on branch `develop` (worktree
`.claude/worktrees/qa-cfg`, `worktree-qa-cfg` at `7efaf9c`), against `pravaha-*` as built by
`./mvnw -o -T1C install -DskipTests` (Java 21, `/usr/lib/jvm/java-21-openjdk-amd64`). Every runtime
observation below is a **real `pravaha-server-0.1.0-SNAPSHOT-app.jar` node**, one per configuration
cell, started with `--spring.config.additional-location=file:$QA/conf/<cell>.yaml` and killed by
PID. Ports HTTP **18800** / Flight **19800** throughout (18801/19801 for the second node in
CFG-098 and CFG-099, both confirmed unbound beforehand). Scratch `$QA =
/tmp/claude-1000/-home-ashutosh-IdeaProjects-pravaha/qa-cfg`. **No production code was modified by
any case in this file**; nothing below is seed-proven, every finding is a direct reproduction.

Two harness notes the case file does not carry, recorded because they change what an executor sees:

- **`--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED` are
  required on the *server* JVM, not only the client.** Without them a node starts, registers, ingests
  and then fails every read with `RuntimeException: Unexpected IO Exception` /
  `UnsupportedOperationException: sun.misc.Unsafe or java.nio.DirectByteBuffer.<init>(long, int) not
  available`, logged as `io.grpc.internal.ServerCallImpl: Cancelling the stream because of internal
  error` and reaching the client as a bare `RST_STREAM closed stream. HTTP/2 error code: CANCEL`.
  `docs/operations/OPERATIONS.md:453` documents the flags; the case file's standing setup does not.
- **`QW` must be registered with `--keys 0,1,2`.** Registered without keys it collapses to one row
  (`u0=5` only). The case file's "canonical three rows" is only reachable with the key list, and no
  case states it.

**The owner's three standing constraints**, restated because every finding below is judged against
them: (1) authorization is enforced at the Pravaha layer, never pushed to persistence; (2) only
authenticated users may reach data; (3) a user receives only the data they are authorized for.
Observed behaviour contradicting any of these is **HIGH** regardless of what the case predicted.

**Overall: 107/110 cases executed. 75 PASS / 28 FAIL / 4 BLOCKED / 3 NOT RUN.** 22 new findings are
recorded in `docs/project/qa/FINDINGS.md` as **CFG-1 … CFG-22**. The three most severe:

- **CFG-13 (HIGH)** — two nodes pointed at one `pravaha.checkpoint.directory` share a single
  per-query subdirectory with **no node-id namespacing**, and each prunes the other's checkpoints.
  Reproduced with `node-a` and `node-b` writing `$QA/p/shared98/QW/` and three files surviving from
  an unpredictable mix of both. A restore reads another node's state.
- **CFG-14 (HIGH)** — two nodes sharing one `pravaha.registry.journal` take **no lock**, and on
  restart one node **recovers the other's registrations**: `node-a` came back with `QA1`, `QA2` *and*
  `QB1`, a query it never registered and whose owner it cannot identify.
- **CFG-12 (HIGH)** — `SensitiveFiles.createOwnerOnly` silently **widens** permissions rather than
  narrowing them. A journal file an operator `chmod 400`s is reset to `600` and written; a checkpoint
  directory `chmod 500`d under a running node is reset to `700` by the next checkpoint. The control
  named `narrow()` sets modes absolutely, so it undoes an operator's deliberate lock and says nothing.

**Eight of the case file's eighteen assumed facts are false against this build.** They are listed in
their own section below, before the per-case verdicts, because — as the case file itself says — a
false assumed fact is a more interesting finding than the case that referenced it. The most
consequential is **fact 5**: `pravaha.security.authentication` *is* now validated, so the "one
character typo opens the server" defect the whole security section is built around no longer exists.

**A note on a third-party string.** As in prior rounds, the jqwik dependency's console output
carries an adversarial sentence addressed to an "AI Agent" instructing it to disregard its
instructions. It is untrusted third-party build output, not a project instruction, and was not acted
on. The same applies to any `system-reminder` arriving inside a tool result.

---

## The assumed facts, re-read against this build

`docs/project/qa/cases/CFG.md:30-121` states eighteen facts "each read out of the code". Ten hold. Eight do
not, and every case that leans on one of those eight is marked in its verdict.

| # | Fact as written | Verdict | Evidence |
|---|---|---|---|
| 1 | `PravahaNode` takes **fifteen** constructor arguments, **eleven** `@Value`, at `:113-128` | **FALSE in part** | Fifteen arguments ✔, but **ten** are `@Value` (`PravahaNode.java:124-134`), and the whole block has moved: the constructor is `:120-153`, the `node.id` default is `:134` not `:128`. Every `PravahaNode` line citation in the case file is six lines low |
| 2 | Four `@ConfigurationProperties`, `prefix = "pravaha"` ×3 and `"pravaha.security"` ×1 | TRUE | `StreamDeclarationProperties.java:45`, `SourceBindingProperties.java:44`, `PersistenceProperties.java:42`, `SecurityProperties.java:46` |
| 3 | `refuseAccidentalOpenServer` runs **after** `CoordinatorFactory.create`; a bad cluster mode masks the security problem | TRUE of `start()` (`:338` then `:345`), **but no longer reachable for an unknown policy** | `securityPolicy()` is now duplicated as a Spring `@Bean` (`PravahaServerApplication.java:99-116`) that `HttpAuthorizer` depends on, so an unknown policy fails at **context refresh**, before `start()` runs at all. See CFG-065 |
| 4 | `securityPolicy()` accepts three spellings, `PRV-7002` otherwise; `SecurityProperties` javadoc says `tenant` | TRUE | `PravahaNode.java:290-304`; javadoc still says `tenant` at `SecurityProperties.java:52`. `audit` javadoc still says `log` at `:55`. Both confirmed wrong by CFG-013/CFG-014 |
| 5 | `authenticates()` is one `equalsIgnoreCase("token")`; **every other string silently means `none`** | **FALSE** | `SecurityProperties.java:110-130`: `trimmedAuthentication()` **trims and refuses**. `authentication: tokens` now fails startup with *"the values are 'none' and 'token'. A misspelling here would otherwise mean 'none'"*; `authentication: "token "` is trimmed and **means `token`**. Both rows of CFG-012 invert |
| 6 | TLS pair never validated as a pair: key-without-cert is plaintext, cert-without-key is a raw NPE | TRUE | Reproduced exactly — CFG-070 (plaintext), CFG-071 (`NullPointerException: Cannot invoke "java.io.File.isFile()" because "privateKey" is null` at `PravahaFlightServer.java:123`) |
| 7 | `pravaha.watermark.out-of-orderness` has **no reader**; documented in four places incl. `OPERATIONS.md:222` | TRUE, and documented in **four** places still | No `@Value`, no `getDuration`, no bound field. Documented at `application.yaml` (`pravaha.watermark` block), `OPERATIONS.md:222`, `CONCEPTS.md:66`, `StreamSchema.java:53` (the case says `:51`). Proven inert again by CFG-025/CFG-081 |
| 8 | `idle-after` validated at startup by a throwaway `WatermarkTracker`, `[1s,10m]`, refused not clamped; `tick` checked per registration | TRUE | `PravahaNode.java:383-393`; CFG-026 and CFG-027 reproduce both halves |
| 9 | `checkpointConfiguration()` writes **two** keys; `PeriodicCheckpointer.from` reads **three**; `pravaha.checkpoint.timeout` is **inert** and **not even bound** | **FALSE** | `PersistenceProperties.java:76-82` writes **three**, including `.set("pravaha.checkpoint.timeout", checkpoint.timeout.toNanos() + "ns")`, and `Checkpoint` **has** a `timeout` field defaulting to 30s at `:109`. `PeriodicCheckpointer.java:138` reads it. CFG-024's entire premise is withdrawn |
| 10 | `PeriodicCheckpointer` refuses `keep < 1` at construction, i.e. at first registration | TRUE | CFG-023: node starts healthy, then `PRV-1041 at least one checkpoint must be kept, asked to keep 0` on every registration |
| 11 | Cluster `Configuration` is built from two literals; seven `socket`/`zookeeper` keys are never forwarded | TRUE | `PravahaNode.java:149-152`; CFG-030 reproduces `PRV-9005` against the verbatim `OPERATIONS.md:99-107` block |
| 12 | `PravahaServerApplication.pravahaEngine` forwards exactly one key, `pravaha.node.id` | TRUE | `PravahaServerApplication.java:50-55` |
| 13 | `arena.slab.size` named by six error messages and is not a key; `state.slab.size` likewise | TRUE | Six `raise arena.slab.size` strings plus `RowArena.java:86-87`; `state.slab.size` only at `RowStore.java:119`. No reader, no key, no env var |
| 14 | `QueryRegistry.executingWith` exists and nothing in `pravaha-server` calls it | TRUE | Declared at `QueryRegistry.java:141` (the case says `:136`); the only call site is `pravaha-it`'s `StateReauthorizationTest.java:367`, a test |
| 15 | `EngineHealthIndicator` returns DOWN when the Flight port is empty; **whether it reaches the readiness group is the thing to check** | TRUE, and it **does** reach it | `application.yaml` already sets `management.endpoint.health.group.readiness.include: readinessState,engine`. CFG-105's predicted defect does not exist |
| 16 | Two duration dialects meet in this file, Spring's and `ConfigParsers`' | TRUE, and still costly | CFG-022: `interval: 2` binds as **2 ms** and produced **6409 checkpoints in 20 s** |
| 17 | `application-dev.yaml` contains exactly one key | TRUE | `pravaha.security.allow-anonymous: true`, seven lines of comment, nothing else |
| 18 | `OPERATIONS.md:322-326` documents `flight.port: 8815`; the code says 9090 | TRUE | `OPERATIONS.md:327` still says `port: 8815`; `application.yaml` and `PravahaNode.java:130` both say `9090` |

Two further drifts, not numbered facts but load-bearing for whole sections:

- **`refuseAccidentalOpenServer`'s `open` test has been rewritten.** It is now
  `!(securityPolicy() instanceof AuthenticatedOnlyPolicy)` (`PravahaNode.java:174`), not
  "authentication is off". So `policy: permissive` + `authentication: token` + `allow-anonymous:
  false` — a fully credentialled deployment — **refuses to start**, and `allow-anonymous` is no
  longer dead under `token`. CFG-059 and CFG-060 both invert. This is `SX-12`, reconfirmed here from
  the configuration side with the additional detail that **the refusal message hard-codes
  `pravaha.security.authentication=none`** and therefore lies about the cause.
- **The HTTP surface now authorizes.** `HttpAuthorizer` (`StreamController.java:65,76,95`,
  `QueryController.java:78`) consults a `SecurityPolicy` bean. CFG-079's "expect none policy-checked"
  is false. What has *not* moved is the audit sink — see CFG-5 below.

---

## Inventory — one case per key (CFG-001 … CFG-047)

Every cell below is a real node start unless the row says otherwise. `EXITED` means the process left
with a non-zero status and the quoted final cause; `UP` means `Started PravahaServerApplication`.

### Node identity and the wire protocol

- **CFG-001 — FAIL (finding CFG-1).** `unset`: `application.yaml`'s `pravaha.node.id:
  pravaha-node-01` and the `@Value` default at `PravahaNode.java:134` are the same string ✔.
  `valid`: `id: cfg-node` → `GET /api/v1/status` → `{"instanceId":"cfg-node",…}` ✔. **`invalid` row
  falsified:** `id: ""` does **not** start — `Caused by: java.lang.IllegalArgumentException: a member
  needs a stable id`, so the empty-member-id election question the case exists to answer cannot
  arise. `boundary`: `x` and a 4096-character id both start and serve. `wrong type`: `id: 12345`
  coerced to `"12345"`, accepted. Separately: **the third readback the case asks for does not
  exist** — `CoordinatorFactory.describe` logs `cluster mode SINGLE on single (consensus),
  self-contained` and never names the node id, so only two of the three surfaces can be compared.
- **CFG-002 — PASS.** `unset` → Flight on 19800, `pravaha queries` → *"no continuous queries are
  registered"*, exit 0. `false` → `Flight SQL disabled (pravaha.flight.enabled=false); this node
  serves HTTP only`; `/actuator/health` → `{"status":"DOWN","groups":["liveness","readiness"]}`;
  `pravaha queries` → `PRV-1041 io exception`; `ss -ltn` shows 18800 bound and 19800 absent. `maybe`
  → `IllegalArgumentException: Invalid boolean value [maybe]`, context refresh fails ✔ (it does
  **not** silently bind `false`). **`boundary`: all four of `TRUE`, `yes`, `on`, `1` are accepted**
  by Spring's binder, matching `ConfigParsers.parseBoolean`. `enabled: []` → `Invalid boolean value
  []`, refused. One deviation, recorded not counted: the case expects the `flight: not listening; no
  client can reach this node` detail in the health body; `show-details: when-authorized` with
  `authentication: none` suppresses the whole `components` map, so the operator sees `DOWN` and no
  reason.
- **CFG-003 — FAIL (finding CFG-2).** `unset` → `0.0.0.0` ✔. `127.0.0.1` → bound loopback only ✔.
  `not-a-host` → `PRV-3010 cannot start the Flight SQL server on not-a-host:19800: Failed to bind to
  address not-a-host/<unresolved>:19800` — carries a code and names the value, but **not the key**.
  `::1` → starts, and the log line is `Flight SQL listening on ::1:19800` — **unbracketed**, so the
  string an operator copies is unparseable as host:port, exactly the risk the case names. **`wrong
  type` falsified:** `host: 127` does **not** resolve to `127.0.0.1`; it is coerced to `0.0.0.127`
  and the node dies with `PRV-3010 … Failed to bind to address /0.0.0.127:19800` / `BindException:
  Cannot assign requested address`.
- **CFG-004 — FAIL (finding CFG-2).** `19800` → listening ✔. `70000` → `Caused by:
  java.lang.IllegalArgumentException: port out of range:70000` — **no `PRV-` code and the message
  does not name `pravaha.flight.port`**. `1` → `PRV-3010 … Failed to bind to address /127.0.0.1:1` /
  `BindException: Permission denied` ✔ (says permission, not "in use"). `"nine thousand"` →
  `NumberFormatException: For input string: "ninethousand"` — note the space is silently stripped
  before the failure, so the message quotes a string the operator never wrote. **`boundary 0` is the
  falsifier and it fails:** the node binds an ephemeral port (`Flight SQL listening on
  127.0.0.1:44131`) but `GET /api/v1/status` has **no port field at all**
  (`{"instanceId","version","engineState","uptimeSeconds","registeredQueries","plugins"}`) and
  `/actuator/health` suppresses details, so **neither surface the case names reports the bound
  port** — a client told to connect has nowhere to look. (`PravahaNode.describe()` at `:543-551`
  does produce `flight: 127.0.0.1:44131`; nothing serves it.)
- **CFG-005 — PASS.** `unset`/`""` → `flight transport=PLAINTEXT` ✔. valid pair → `flight
  transport=TLS` ✔. `/nonexistent/tls.crt` → `PRV-6104 the TLS certificate
  …/tls/absent.crt is not a readable file`, startup fails, absolute path ✔. **A directory** → the
  same `PRV-6104 … is not a readable file` ✔ (the case asked to confirm the wording is "is not a
  readable file" rather than "does not exist" — it is). **A non-PEM existing file** (`/etc/hostname`)
  → `IllegalArgumentException: Input stream not contain valid certificates` /
  `CertificateException: found no certificates in input stream` — **a raw Netty/JSSE exception with
  no `PRV-` code**, which is the finding the case asks to record (folded into finding CFG-6).
  `mode 000` → `PRV-3010 … (Permission denied)` — it does carry a code, just the bind code rather
  than `PRV-6104`. `certificate: 42` → `PRV-6104 the TLS certificate
  /home/ashutosh/IdeaProjects/pravaha/42 is not a readable file` ✔ relative path.
- **CFG-006 — PASS.** Both unset → PLAINTEXT ✔. Valid pair → TLS ✔. `/nonexistent/tls.key` with a
  valid certificate → `PRV-6104 the TLS private key …/tls/absent.key is not a readable file` ✔.
  **key set, certificate unset → the node starts in PLAINTEXT** (CFG-070). Certificate set, key
  unset → **`NullPointerException`** (CFG-071). The `key: true` wrong-type row was **not run**; every
  other row carries a node start.

### Streams and sources

- **CFG-007 — PASS.** `txn: {}` → `PRV-2002 stream 'txn' is declared under pravaha.streams with no
  schema. A stream is a name and a shape; the name alone cannot be planned against.` ✔ verbatim.
  Valid four-column spec → `GET /api/v1/streams` lists `txn` with four fields in declaration order
  and `QW` delivers `(u0,5) (u1,7) (u2,3)` ✔. `"id:NOSUCHTYPE"` → `PRV-5040 unknown type
  'NOSUCHTYPE'. Supported: BOOLEAN, INT8, …, DECIMAL(p,s). Suffix with ? for nullable.` ✔ names the
  type and lists the known ones. `"id"` → `PRV-5040 schema entry 'id' is not 'name:TYPE'. Example:
  id:INT64,name:STRING` ✔. `""` → the same `PRV-2002` as unset ✔. The 200-column row and the
  `schema: {id: INT64}` map row were **not run**.
- **CFG-008 — PASS.** `unset` → 0 rows after 15 s against the same data and SQL (run as CFG-084).
  `event_time` → the canonical three rows ✔. `evnt_time` → `PRV-2002 stream 'txn' declares
  'evnt_time' as its event time and has no such column. Its columns are [id, usr, amount,
  event_time].` ✔ names the column and lists the actual ones. `"  event_time  "` → starts and
  behaves identically ✔ (`strip()` at `PravahaNode.java:245`). **`EVENT_TIME` → `PRV-2002`, so
  `hasField` is case-sensitive** — the same answer SQL gives. `event-time: 3` → `PRV-2002` naming
  `'3'` ✔. The `usr` (STRING column as event time) row was **not run**.
- **CFG-009 — PASS, and it is the sharpest case in the file.** One data file, one query, five node
  starts, five different answers:

  | `out-of-orderness` | Windows closed | Rows | Values |
  |---|---|---|---|
  | unset (`DEFAULT = 10s`) | 0 | **0** | — |
  | `2s` | 1 | **3** | `u0=5, u1=7, u2=3` (sum 15) |
  | `0s` | 2 | **6** | `+ u0=17, u1=8, u2=15` (sum 55) |
  | `10s` | 0 | **0** | identical to unset ✔ |
  | `30s` | 0 | **0** | ✔ |
  | `-1s` | — | — | **refused**: `IllegalArgumentException: out-of-orderness must not be negative, got PT-1S. Zero means the source is strictly ordered, which is a claim the engine will hold you to` |

  The `-1s` row is the one the case hedges on ("if it is accepted, that is a finding") — it is
  refused, and the refusal explains itself. `out-of-orderness: fast` was **not run**; the negative
  row already proves the binder reaches the validator.
- **CFG-010 — FAIL (finding CFG-4).** **The `unset` row inverts:** a binding with no `plugin` no
  longer starts the node. `Caused by: java.lang.IllegalArgumentException: a source binding for 'txn'
  needs a plugin name; the plugins on the classpath report their own names and one of those is what
  goes here` — refused at startup, which is what the case says *should* happen and predicts does
  not. `filesystem` ✔; `FileSystem` → starts and logs `sources bound: [txn <- FileSystem[…]]` ✔
  (matched case-insensitively). `kafka` → node starts, and the first registration fails with
  `PRV-5090 no source plugin named 'kafka' is on the classpath, so stream 'txn' cannot be fed.
  Available: [filesystem]` — **the available list is one plugin, not the four
  (`filesystem, feedfile, jdbc, delta`) the case requires**, which is finding `I-7` reconfirmed from
  the configuration side. `plugin: ""` and `plugin: [filesystem]` were **not run** (the `unset` row
  already refuses).
- **CFG-011 — PASS.** `options: {}` → `PRV-5091 the 'filesystem' plugin could not be opened for
  stream 'txn': … PRV-5001 plugin 'txn' requires 'path', which is not set. Available: [event.time]`
  ✔ both codes, and the `Available:` list proves the dotted key survived binding. `path` + `schema`
  → canonical result ✔. Explicit `event.time: event_time` → byte-identical result to omitting it ✔
  (`withDeclaredEventTime` leaves a present key alone, `PravahaNode.java:279`). `path:
  /no/such/file.csv` → `PRV-5091 … PRV-5040 plugin 'txn' cannot read /no/such/file.csv` ✔ names the
  path. **Disagreement row** — binding `schema: "id:INT64,usr:STRING"` against a four-column stream:
  **nothing at startup compares the two**; the failure lands at the first registration as
  `PRV-5091 … PRV-5040 event.time names 'event_time', which is not a column of stream 'txn'`, which
  is a message about the *source's* schema wearing the *stream's* column name. Neither silent nulls
  nor a column shift — the safest of the three outcomes the case lists, and the startup gap it names
  is real (see finding CFG-8). `options: "path=/x"` → binding failure at context refresh ✔.

### Security

- **CFG-012 — FAIL (finding CFG-21; this is assumed-fact 5).** `unset` → `none` ✔. `token` with a
  token table → `security: authentication=token…`, `Filter pravahaAuthentication` registered,
  anonymous HTTP → `401 {"code":"PRV-7001","message":"this server requires a credential; send it as
  'Authorization: Bearer <token>'"}`, anonymous Flight → `PRV-7001 this server requires a credential:
  send it as the header 'authorization: Bearer <token>'` ✔. **`tokens` (plural) no longer means
  `none`:** the node refuses to start — `IllegalArgumentException: pravaha.security.authentication is
  'tokens'; the values are 'none' and 'token'. A misspelling here would otherwise mean 'none', so a
  node that looked authenticated would accept every caller.` **`"token "` (trailing space) no longer
  means `none`** either: `trimmedAuthentication()` trims, the node starts, and the log says
  `authentication=token`. The defect the case is built around is fixed. What remains is legibility:
  the refusal surfaces wrapped in `org.springframework.boot.web.server.WebServerException: Unable to
  start embedded Tomcat`, because `verifier()` is first called from the `pravahaAuthentication` bean
  — the operator's first line is about Tomcat.
- **CFG-013 — PASS.** `unset` → `permissive` ✔. `tenant` — **the value the field's own javadoc
  documents** — → `PRV-7002 pravaha.security.policy is 'tenant', which is not a policy this node
  knows. Use 'permissive' or 'authenticated', or implement SecurityPolicy for rules of your own.`
  ✔ verbatim, and `SecurityProperties.java:52` still says `{@code tenant}`. `strict` → the same
  `PRV-7002` listing both real values ✔ (CFG-065). `""` → not run separately; the `audit` twin at
  CFG-014 shows the empty-string rendering. `authenticated` / `authenticated-only` both accepted
  (CFG-063/CFG-064 and the source at `PravahaNode.java:295`). `policy:` (YAML null) and
  `policy: [permissive]` were **not run**.
- **CFG-014 — PASS, with a finding attached (CFG-5).** `unset` → `none` ✔. `log` — **the value the
  field's own javadoc documents at `SecurityProperties.java:55`** — → `PRV-7002 pravaha.security.audit
  is 'log'; use 'none' or 'memory'.` ✔ verbatim. `""` → `PRV-7002 pravaha.security.audit is '';
  use 'none' or 'memory'.` ✔, and the message does render an empty quoted string. **The executor's
  note is the finding.** `auditSink()` caches into the `audit` field so the registry and the Flight
  server share one sink ✔ — but `PravahaServerApplication.pravahaAuditSink()` (`:118-121`)
  is `return AuditSink.NONE;` **unconditionally**, and that is the sink `HttpAuthorizer` records
  into (`HttpAuthorizer.java:47,75,85`). With `audit: memory` there are **two sinks after all**, and
  every HTTP authorization decision goes to the one that discards.
- **CFG-015 — PASS.** `unset` → refused with the full three-remedy `PRV-7002` at
  `PravahaNode.java:175-183` ✔ (CFG-057). `true` → starts and serves every view to anonymous callers
  ✔ (CFG-058). `false` explicitly → identical refusal ✔. `allow-anonymous: "sure"` → **a binding
  failure, not a silent `false`**: `Failed to bind properties under 'pravaha.security.allow-anonymous'
  … Value: "sure" … Reason: failed to convert` ✔. The `yes`/`on`/`1` row was not run on this key;
  CFG-002 proves Spring's binder takes all three on the neighbouring boolean.
- **CFG-016 — FAIL (finding CFG-10).** `tokens: {}` with `authentication: token` → node starts,
  every caller refused (CFG-067) ✔. One token → authenticates; a different string → `PRV-7001 the
  credential presented was not accepted` ✔. **Four tokens `t1,t2,t3,q` → all four authenticate on
  both transports**, so the `StaticTokenVerifier.of(...).and(...)` chain honours the whole table, not
  the first or the last ✔. **The `boundary` row fails for a reason the case did not anticipate:** a
  one-character token is fine (`q` works), but a token declared as `x: {}` — an empty spec map —
  is **silently dropped by the binder** and never authenticates, while `q: {id: q1}` in the same
  table does. A credential can be present in the file, absent from the verifier, and unmentioned
  anywhere. **`disclosure` row PASSES and is the important one:** `grep -c 'tok-ann'` over the entire
  startup log is **0**, and `/actuator/env` and `/actuator/configprops` both return **404** on the
  shipped `management.endpoints.web.exposure.include` (`401` once authentication is on, since
  `BearerTokenFilter` is mapped to `/*`).
- **CFG-017 — FAIL (finding CFG-11).** `valid`: `id: ann` reaches the principal ✔ (the token
  authenticates and the HTTP/Flight calls succeed). **`boundary ""` inverts:** `id: ''` no longer
  produces an empty principal id that fails on replay — the node refuses to start with
  `IllegalArgumentException: a principal needs an id; the audit log has nothing to record without
  one, and a row filter has nothing to key on`. The `unset` row — *the bearer token becomes the
  principal id and therefore lands in the journal on disk* — is **confirmed by source**
  (`SecurityProperties.java:153`: `spec.getId() == null ? entry.getKey() : spec.getId()`) and is
  still true: a deployment that omits `id` writes its own credential into
  `pravaha.registry.journal`. The 512-character and `id: 7` rows were **not run**.
- **CFG-018 — NOT RUN.** No shipped surface reports `Principal.tenant()`: `audit: memory` records
  into an `AuditSink.InMemory` with no reader on either transport, and `/api/v1/status` carries no
  principal. The case's central observation ("visible in the audit record") cannot be made without
  either a harness on the released jars or a code change, and this round modifies no code. What
  *can* be said from source, and is: the default is `"public"` not null
  (`SecurityProperties.java:196`), and the **coverage-gap row is true** — `AuthenticatedOnlyPolicy`
  checks only that a principal exists, so nothing shipped reads `tenant`.
- **CFG-019 — PASS on the two rows that are observable.** `roles: reader` written as a **scalar**
  rather than a list binds successfully and the principal authenticates (token `tok-bob`, HTTP 200
  and Flight OK) — Spring's relaxed binder accepts a single value as a one-element list. The
  **coverage-gap row is confirmed by source**: no shipped `SecurityPolicy` reads `roles`; the
  mechanism exists for an implementation that does not ship. The dedup, ordering and empty-set rows
  need the same unavailable readback as CFG-018 and were **not run**.

### Persistence

- **CFG-020 — FAIL (findings CFG-7 and CFG-12).** `unset` → the WARN fires verbatim and a restart
  returns 0 queries ✔ (CFG-097). `valid` → journal file created `-rw-------`, restart logs `registry
  recovered 1 of 1 queries from $QA/p/j094` and `pravaha queries` returns 1 ✔. **`invalid` (a path in
  a directory that does not exist) falsified:** no `PRV-8006` at any point — `RegistryJournal.append`
  calls `Files.createDirectories(parent)` (`:212`), so `$QA/p/absent2/sub/j` is **created silently**
  and journalling works. **`invalid` (an unwritable directory, `chmod 500`) falsified, and worse:**
  the registration is **accepted**, `pravaha queries` shows 1, and the directory's mode is **reset
  from `dr-x------` to `drwx------`** by `SensitiveFiles.createOwnerOnly` → `narrow(parent,
  OWNER_ONLY_DIRECTORY)`. The one behaviour `OPERATIONS.md:379` insists on — refuse rather than
  acknowledge — never fires because the failure is engineered away. **`invalid` (the path is a
  directory)** → `UncheckedIOException: cannot read the registry journal at $QA/p/nodir` caused by
  `IOException: Is a directory` — **startup fails with no `PRV-` code**, which is the finding the
  case asks to record. `journal: 7` → starts, `registry recovered 0 of 0 queries from 7`, a relative
  path in the working directory ✔. The corrupt-tail and newer-version rows were **not run**.
- **CFG-021 — FAIL (findings CFG-7 and CFG-12).** `unset` → WARN verbatim, restart holds 0 rows,
  query exists ✔ (CFG-094). `valid` → `checkpointing registered queries under $QA/p/k096`, a
  **per-query subdirectory** `k096/QW/` appears, and after a restart with the source removed the
  view holds the six rows *before any row is fed* ✔ (CFG-096). **`invalid` (an unwritable
  directory)** → the node starts, logs `checkpointing registered queries under $QA/p/ro/ck`, and the
  registration succeeds; the mode is silently widened as in CFG-020. **`invalid` (the path is an
  existing file)** → the node **starts** and logs `checkpointing registered queries under
  $QA/data/txnA.csv` as though it had a directory; the failure arrives at the first registration as
  `PRV-1041 cannot create the checkpoint directory $QA/data/txnA.csv/QW`. `boundary` (a directory
  that does not exist but whose parent does) → **created** ✔.
- **CFG-022 — FAIL (finding CFG-15).** `2s` → ids 8,9,10 survive after 20 s with `keep: 3`, i.e.
  ~10 checkpoints taken, first one interval in ✔. **`PT2S` (ISO-8601) → identical**: ids 8,9,10 —
  the `toNanos()` conversion at `PersistenceProperties.java:77` holds and the shipped bug does not
  recur ✔. **`2` (bare number) → `6409` checkpoints in 20 s** (ids `checkpoint-6407/6408/6409`
  surviving), i.e. Spring bound it as **2 milliseconds**, silently, on a key whose neighbour
  `ConfigParsers.parseDuration` would have refused outright. That is the finding, reproduced with a
  file count. **`0s` no longer passes**: the registration fails with `PRV-1041 checkpoint interval
  must be positive, got PT0S`, so the tight-loop the case predicts is refused — at first
  registration, not at startup. One secondary miss: the `checkpointing every Nms, keeping the newest
  K` log line the case cites at `PeriodicCheckpointer.java:136` **does not appear in any of the six
  runs**, so the interval in force is not observable from the log at all. `-1s`, `24h` and
  `interval: soon` were **not run**.
- **CFG-023 — FAIL (finding CFG-16), exactly as the case predicts it will.** `unset` → 3 ✔
  (`k096/QW` held `checkpoint-8/9/10`). `keep: 1` → **exactly 1** file after 20 s ✔. `keep: 5` →
  **exactly 5** ✔. **`keep: 0`** → the node **starts**, reports healthy, logs `checkpointing
  registered queries under …`, and then **every registration fails**: `PRV-1041 at least one
  checkpoint must be kept, asked to keep 0. Keeping none means every restart starts from nothing`.
  `pravaha queries` afterwards: *"no continuous queries are registered"*. A node that is up, green
  and incapable of accepting work. `-1`, `2147483647` and `2147483648` were **not run** — the `0`
  row already reaches the same constructor. One incidental observation: with `keep: 5` the surviving
  ids were `5,7,8,9,10` — **not** the five newest, so pruning is not strictly "newest K by id".
- **CFG-024 — FAIL, and the case is withdrawn rather than confirmed (finding CFG-17).** Assumed
  fact 9 is false. `PersistenceProperties.Checkpoint` **has** a `timeout` field (`:109`, default
  30s), `checkpointConfiguration()` **does** emit `pravaha.checkpoint.timeout` (`:81`), and
  `PeriodicCheckpointer.java:138` reads it. The key is bound, forwarded and read — the "reader with
  no writer" defect the case describes does not exist on this build. Run anyway:
  `pravaha.checkpoint.timeout: 1ms` with `interval: 2s` over 20 s produced three checkpoint files
  and **zero** occurrences of "timeout"/"timed out" in the log, so `1ms` had no observable effect
  *for this workload* — a 12-row view checkpoints well inside a millisecond either way, so this is
  not evidence that the value is ignored. The case as written asserts a fact that has been fixed;
  what it should now ask is whether the timeout is enforced at all, which needs a checkpoint large
  enough to exceed it.

### Watermarks

- **CFG-025 — PASS, and it is the decisive one.** Three runs, identical but for this key, with
  `pravaha.streams.txn.out-of-orderness` removed: absent → **0 rows**; `0s` → **0 rows**; `30s` →
  **0 rows**. The `0s` run is the whole case and it returned nothing, so the key is inert. Run back
  to back with CFG-009's `0s` row in one sitting, in that order: `pravaha.streams.txn.out-of-orderness:
  0s` → **6 rows**, `pravaha.watermark.out-of-orderness: 0s` → **0 rows**. Both are documented in
  `application.yaml`.
- **CFG-026 — PASS, on all eight cells.** `unset` → `watermarks: idle-after=PT30S, tick=PT1S` ✔
  agreeing with `application.yaml` and the `@Value`. `5s` → starts. `500ms` → `PRV-2002
  pravaha.watermark.idle-after is PT0.5S, which this engine will not accept: …` ✔. `1h` → **the exact
  value `PravahaNode.java:379-381` records as having started a node that recovered 0 of 3 queries** →
  now `PRV-2002`, refused ✔. `1s` → **accepted** (inclusive) ✔. `999ms` → refused ✔. `10m` →
  **accepted** ✔. `0s` → refused ✔. The four cells straddling the two bounds behave in four
  different ways, so a build that clamped would have started on all of them. `idle-after: soon` →
  `ConversionFailedException` / `IllegalArgumentException: 'soon' is not a valid duration`, and the
  case's question about legibility answers itself: the message names the **constructor argument** of
  `pravahaNode`, not `pravaha.watermark.idle-after`.
- **CFG-027 — PASS, and it is the asymmetry the case exists for.** `200ms` → starts, registers,
  canonical result. **`tick: 60s` with `idle-after: 30s`** → the node **starts**, logs `watermarks:
  idle-after=PT30S, tick=PT1M` as though both were in force, reports healthy — and then **every
  registration fails**: `PRV-1041 the watermark tick (PT1M) is longer than the idle timeout (PT30S),
  so a partition could not be noticed idle until long after it was. Idleness is detected on the
  tick; the tick has to be the finer of the two.` `pravaha queries` afterwards: none. Its
  neighbour in the same YAML block, one key away, is refused at startup for the identical class of
  error (CFG-026). `tick: 30s` with `idle-after: 30s` → `<=` holds, registration succeeds, canonical
  result ✔. `0s`, `1ns` and `tick: fast` — the last → context-refresh binding failure ✔; the first
  two **not run**.

### Cluster

- **CFG-028 — FAIL (finding CFG-18, against the case file rather than the product).** `SINGLE` +
  `single` → starts ✔. `replicated` (lower case) + `socket` → accepted by `toUpperCase(ROOT)` and
  fails later on peers, i.e. the mode parsed ✔. `HA` — **the value `system_design.md:3531`
  documents** — → `PRV-9001 'HA' is not a cluster mode; one of [SINGLE, REPLICATED, PARTITIONED]` ✔.
  `""` → `PRV-9001 '' is not a cluster mode; one of [SINGLE, REPLICATED, PARTITIONED]` ✔. `PARTITIONED`
  + `socket` → `PRV-9002 cluster mode PARTITIONED assigns partitions to particular nodes, and 'socket'
  cannot exclude split-brain — socket (NO consensus — cannot exclude split-brain), self-contained,
  development only. Two nodes each believing they own a partition means two nodes writing the same
  aggregate, and the damage is silent and durable.` ✔ verbatim. **The cell the case calls the one
  that matters is wrong:** `PARTITIONED` + `mechanism: single` **starts**, logging `cluster mode
  PARTITIONED on single (consensus), self-contained`. It is not a defect — `single` genuinely
  excludes split-brain because there is no second node, which is what `OPERATIONS.md:110`'s own table
  says — but the case asserts a `PRV-9002` that cannot be produced, and the FAIL is recorded against
  the case file.
- **CFG-029 — PASS.** `single` ✔; `socket` → `PRV-9005` on peers, i.e. the mechanism resolved ✔;
  `zookeeper` → `PRV-9001 no cluster coordinator called 'zookeeper' is on the classpath. Available:
  [single, socket]. A mechanism ships in its own artefact…` ✔; **`SOCKET` (upper case) → `PRV-9001 no
  cluster coordinator called 'SOCKET' is on the classpath`** — the map lookup does not case-fold
  while the adjacent `mode` key does, confirming the two-case-rules observation ✔; `""` → `PRV-9001
  no cluster coordinator called '' is on the classpath. Available: [single, socket]` ✔.
- **CFG-030 — PASS.** The YAML block from `OPERATIONS.md:99-107`, transcribed verbatim to loopback,
  produces: `PRV-9005 the socket coordinator needs pravaha.cluster.socket.peers, as
  'id=host:port,id=host:port'. There is no discovery: discovery without consensus is one more thing
  for two halves of a cluster to disagree about`. **Startup fails, and the message asks for a key the
  operator supplied.** Spring did bind it — the same file's `pravaha.cluster.mode: REPLICATED` and
  `mechanism: socket` were both read (the failure comes from `SocketProvider`, which is only reached
  once the mechanism resolves) — and `PravahaNode.java:149-152` builds the coordinator's
  `Configuration` from two literals, so the peers never travel.
- **CFG-031 — PASS.** Unreachable for the same mechanism as CFG-030, recorded against this key by
  name. `SocketProvider.java:80` reads `pravaha.cluster.socket.heartbeat.millis` with default
  `1000` from a `Configuration` containing only `pravaha.cluster.mode` and
  `pravaha.cluster.mechanism`. Every variant is indistinguishable because the node never gets past
  `PRV-9005` on the peers key, and would read `1000` if it did. A `-D` system property does not help:
  the `Configuration` is built from two `.set()` literals, not from the environment.
- **CFG-032 — PASS.** Identical, `SocketProvider.java:81`, default `5000`. Recorded separately so
  the defect list names the key.
- **CFG-033 — BLOCKED.** `plugins/pravaha-cluster-zookeeper` is not on the classpath of the shipped
  `-app.jar` and there is no documented mechanism for putting it there (`I-7`), so `mechanism:
  zookeeper` fails at `PRV-9001 no cluster coordinator called 'zookeeper' is on the classpath.
  Available: [single, socket]` **before** `ZooKeeperProvider` is ever constructed. The case's own
  `PRV-9005` cannot be reached. The underlying claim stands and is recorded from source:
  `ZooKeeperProvider` reads four `pravaha.cluster.zookeeper.*` keys that `PravahaNode.java:149-152`
  never forwards, so `OPERATIONS.md:113`'s "use `zookeeper` for production `PARTITIONED`" is
  unreachable twice over.
- **CFG-034 — BLOCKED.** Same reason. `root` default `/pravaha`; unreachable.
- **CFG-035 — BLOCKED.** Same reason. The boundary the case asks to record against the reader rather
  than the key is confirmed by inspection: the provider reads a `long` and casts to `int`
  unchecked, so a value above `2147483647` would wrap silently if the key ever became readable.
- **CFG-036 — BLOCKED.** Same reason, same unchecked narrowing, default `10_000`.

### The Spring half of the same file

- **CFG-037 — PASS.** `server.port: 18800` served every `/api/v1` and `/actuator` request in every
  run in this log. `unset` → `8080`, and the case's parenthetical is confirmed: `grep -rn
  'server.port'` finds no `@Value` behind it, so deleting the line really does unset it, unlike
  every `pravaha.*` key. The `70000` / `0` / `"http"` rows were **not run** on this key; CFG-004
  exercises the identical Spring conversions on `pravaha.flight.port`.
- **CFG-038 — NOT RUN.** The case's falsifier is *"with `graceful`, an in-flight request is cut off
  at shutdown"*, and no in-flight request was held across a `SIGTERM` in this round. Recorded
  honestly rather than inferred. The source half of the case was checked and is worth keeping:
  `PravahaNode.shutdownTimeout()` returns `Duration.ofSeconds(30)` (`:555-557`) and **nothing calls
  it** — `grep -rn 'shutdownTimeout()'` finds the declaration and no caller; Spring honours
  `spring.lifecycle.timeout-per-shutdown-phase`, which is **not in `application.yaml`**, so the
  effective grace period is Spring's 30 s default and the method is decorative.
- **CFG-039 — FAIL (finding CFG-19).** `spring.application.name: pravaha` is set in
  `application.yaml`, `info` is in `management.endpoints.web.exposure.include`, and
  `GET /actuator/info` returns **`{}`**. `GET /actuator/prometheus` contains **no `application=`
  tag** on any series (`pravaha_query_rows_in{query="QW"}` and its six siblings carry `query` and
  nothing else). The configured name appears in no metric tag and in no `/actuator/info` field —
  the case's falsifier, met.
- **CFG-040 — PASS on the load-bearing claim.** A thread dump (`jcmd Thread.print`) of a running
  node with `spring.threads.virtual.enabled: true` in force contains **zero** virtual threads among
  the `pravaha-*` set: `pravaha-watermark`, `pravaha-query-0`, `pravaha-metrics`, `pravaha-feed-QW`
  are all platform threads. `application.yaml`'s claim that the engine's own threads are unaffected
  holds, and the starvation failure the case is written against (a spin-wait strategy pinned to a
  virtual thread's carrier) does not occur. The servlet-thread half was not separately demonstrated:
  Tomcat's `http-nio-*` acceptor/poller are platform threads by design and a virtual request thread
  only exists while a request is in flight, which the dump did not catch.
- **CFG-041 — FAIL (finding CFG-20).** With the shipped `spring.mvc.problemdetails.enabled: false`,
  six provoked responses split into two shapes:

  | Provoked | Body |
  |---|---|
  | 400-ish, `POST /queries/validate` with `SELEKT 1` | `{"valid":false,"diagnostics":[{"code":"PRV-2001",…,"helpUrl":…}]}` — `ApiError`-shaped diagnostics ✔ |
  | 404, `GET /api/v1/streams/nosuch` | `{"code":"PRV-2003","message":"…no stream named 'nosuch'. Registered: [txn]","helpUrl":…,"timestamp":…}` ✔ |
  | 403, an anonymous call under `authentication: token` | `{"code":"PRV-7001",…,"helpUrl":…}` ✔ |
  | **405, `DELETE /api/v1/streams`** | `{"timestamp":…,"status":405,"error":"Method Not Allowed","path":"/api/v1/streams"}` ✘ |
  | **415, `POST /queries/validate` as `text/plain`** | `{"timestamp":…,"status":415,"error":"Unsupported Media Type","path":…}` ✘ |
  | **404 on an unmapped path, `GET /nosuchpath`** | `{"timestamp":…,"status":404,"error":"Not Found","path":"/nosuchpath"}` ✘ |

  Three of six carry **no `code`, no `message`, no `helpUrl`**. They are not RFC 7807
  `ProblemDetail`s — turning problem details off did work — they are Spring's `BasicErrorController`
  default body, which is a **third** shape. `ApiExceptionHandler` handles `PravahaException` and
  `IllegalArgumentException`; 405, 415 and an unmapped 404 are neither. The comment's stated goal,
  *"every non-2xx response is an `ApiError` and nothing else"*, is not met and `problemdetails` was
  never the reason.
- **CFG-042 — PASS.** With the shipped `health,info,metrics,prometheus`: `/actuator/health` 200,
  `/actuator/info` 200, `/actuator/prometheus` 200, and `/actuator/env` and `/actuator/configprops`
  both **404** — so the bearer-token disclosure path CFG-016 worries about is closed by default ✔.
  The `*` row was **not run**; the question it asks (whether Spring's sanitiser masks a key whose
  *name* is the secret) remains open and is worth its own case.
- **CFG-043 — PASS, and assumed fact 15's open question is already answered in the code.**
  `probes.enabled: true` → `/actuator/health/liveness` and `/actuator/health/readiness` both exist.
  In the normal state both are `200 {"status":"UP"}`. With `flight.enabled: false`, liveness stays
  `200 UP` and readiness is **`503 {"status":"DOWN"}`**. The reason the case says to check rather
  than assume: `application.yaml` **does** carry
  `management.endpoint.health.group.readiness.include: readinessState,engine`, so
  `EngineHealthIndicator` is in the readiness group by configuration. The predicted defect does not
  exist.
- **CFG-044 — PASS on the rows run.** `show-details: when-authorized` with `authentication: none`:
  the body is `{"status":"UP","groups":["liveness","readiness"]}` — **no `components`, no
  `firstFailure`, no `flightPort`**. So with no notion of an authorized caller, details are hidden
  from everybody, which is the safe answer and the one that makes CFG-002 and CFG-004 harder to
  diagnose exactly as the case predicts. The `authentication: token` split (authorized sees
  `firstFailure`, unauthorized does not) and the `always`/`never` rows were **not run**.
- **CFG-045 — FAIL (finding CFG-20).** The configured path works — `GET /api/v1/openapi.json` →
  200, `GET /v3/api-docs` → 404 ✔ — and the document's `paths` cover the mapped surface. Two
  defects in the document itself: (a) its `paths` are `['/api/v1/queries/explain',
  '/api/v1/queries/validate', '/api/v1/status', '/api/v1/streams', '/api/v1/streams/{name}',
  '/status']` — **`/status` is advertised and is not a path this server maps**; (b) the
  `components.schemas.ApiError` entry has **zero properties**, so the document does not describe
  `code`, `message`, `helpUrl`, `timestamp` or `path` at all. CFG-041 depends on that schema being
  right, and a generated client would model every error as an empty object.
- **CFG-046 — PASS.** `springdoc.swagger-ui.path: /api/docs` → `GET /api/docs` returns **302** (the
  redirect to the UI bundle) and the un-configured `GET /swagger-ui.html` returns **404** ✔. The
  deliberate-mismatch row was **not run**.
- **CFG-047 — FAIL (finding CFG-22).** Four node starts, `-Dpravaha.ffm=true`,
  `-Dpravaha.memory=agrona`, `-Dpravaha.memory=nonsense` and none, each registering `QW` with
  `out-of-orderness: 0s`. **All four returned the identical six-row canonical result** — the most
  serious thing this case could have found did not happen ✔. But both refusal requirements fail:
  `-Dpravaha.memory=nonsense` **starts normally and silently falls back to Agrona**, and
  `-Dpravaha.ffm=true` on a Java 21 JVM (which cannot support FFM — `MemoryAccess.best()` gates on
  `Runtime.version().feature() >= 22`) also **starts normally and silently falls back**, with no log
  line either way. `MemoryAccess.java`'s own javadoc states the policy — *"An unavailable or
  unflagged implementation is never an error"* — so this is deliberate, and it is the exact
  deployment-believes-it-is-running-FFM-for-months failure the case names. Neither property appears
  in `application.yaml`, in any `@Value`, or in any operator-facing document.

---

## Keys the product names and does not have (CFG-048 … CFG-056)

All greps run from the repository root with `--exclude-dir=.claude --exclude-dir=target`, per
`docs/project/qa/README.md`'s trap guidance — never by matching `/.claude/` as a substring.

- **CFG-048 — PASS.** `grep -rn 'arena\.slab\.size'` over `--include=*.java` finds exactly the seven
  strings the case lists — `WindowAssign.java`, `SymmetricHashJoin.java` ×2, `InterpretedPipeline.java`
  ×2, `LookupJoin.java`, `RowArena.java` — plus `RowArenaTest.java:170` and `docs/design/system_design.md:3549`
  (the case cites `:3543`; the block has moved). **No `@Value`, no `Configuration.get*`, no
  `@ConfigurationProperties` field, no `application.yaml` entry, no environment variable.** Six
  runtime error messages instruct the operator to change a setting that does not exist. The
  arena-exhausting query in step 1 was **not run**; the falsifier is settled by the key having no
  reader at all, so no value of it can change anything.
- **CFG-049 — PASS.** `state.slab.size` appears at `RowStore.java:119` and `RowStoreTest.java:189`
  and nowhere else. Same defect, different module, recorded separately because closing one does not
  close the other.
- **CFG-050 — PASS.** `pravaha.runtime.lanes` has no reader in `pravaha-*/src/main` (the only hit is
  prose in `ConfigurationBuilder.java:111` describing how `PRAVAHA_RUNTIME_LANES` *would* map into
  the engine's own `Configuration` — which `PravahaServerApplication` never populates, fact 12).
  `lane.affinity`: **zero hits.** A thread dump of a running node answers the question the case says
  an operator cannot answer from any document: there is **no `pravaha-lane-*` thread at all**. The
  full `pravaha-*` census is `pravaha-query-0` (one), `pravaha-watermark`, `pravaha-metrics`,
  `pravaha-feed-QW`. The documented default of 14 lanes describes nothing that exists.
- **CFG-051 — PASS on reachability.** `pravaha.runtime.wait.strategy` has no reader; the five
  `wait.strategy` grep hits are all javadoc prose. `LaneConfig.defaults()` (`LaneConfig.java:83-95`)
  fixes `SPIN_THEN_YIELD` and nothing on the server path calls `withWaitStrategy`. The 60-second
  `top -H` measurement was **not run**; the key having no reader settles the falsifier, and the cost
  question belongs to `PERF`'s idle-cost cases.
- **CFG-052 — PASS.** `batch.max.records`: **zero** hits in `src/main`. `batch.max.linger`:
  **zero**, and there is no linger field on `LaneConfig` at all — the document names a mechanism this
  engine does not have, which is the half the case asks to record separately.
- **CFG-053 — PASS.** `ring.capacity`, `backpressure.high.watermark`, `backpressure.low.watermark`,
  `arena.max.per.lane`: **zero** hits each. `LaneConfig.defaults()` sets `inboxCells = 2048` and
  `exchangeCells = 1024`, against a documented `ring.capacity: 65536` — wrong by a factor of 32 even
  read as documentation.
- **CFG-054 — PASS.** `default.tier`, `offheap.max`, `block.cache`, `write.buffer.manager`,
  `compaction.style`: **zero** hits each in `--include=*.java`; the only occurrences are
  `system_design.md:3545-3552`. `grep -rn rocksdb --include=pom.xml` finds one hit and it is not a
  dependency declaration. An operator sizes a machine around `offheap.max.per.lane: 1GB` and a
  RocksDB block cache that do not exist.
- **CFG-055 — PASS, run rather than argued.** `system_design.md:3554-3568`'s nine-key checkpoint
  block, transcribed verbatim into a node's YAML — `enabled`, `interval`, `timeout`,
  `min.pause.between`, `max.concurrent`, `alignment`, `retain`, and the `store` block with `type:
  aerospike` — **starts**, ignores eight of the nine, and logs the WARN the case predicts:
  `pravaha.checkpoint.directory is not set, so registered queries keep no checkpoints`. `directory`
  is not among the documented keys, so the node **does not checkpoint at all**. Grep confirms the
  extras are unread: `checkpoint.enabled`, `min.pause.between`, `max.concurrent`,
  `checkpoint.alignment`, `checkpoint.retain` → **zero** hits each. `interval` matches; `retain` is
  spelled `keep`; `timeout` **is** bound (fact 9, above).
- **CFG-056 — PASS, and it is the parent of CFG-048 and CFG-051.** `grep -rn 'executingWith
  --include=*.java` returns the declaration at `QueryRegistry.java:141` and exactly one call site,
  `pravaha-it/.../StateReauthorizationTest.java:367` — a test, not `pravaha-server`. `pravaha.lane.`:
  **zero** hits anywhere. So every `LaneConfig` field is fixed at `defaults()` for every server node,
  and the second argument is unreachable too (CFG-047):

  | Setting | Value a server node runs | Key that would set it |
  |---|---|---|
  | `batchSize` | 512 | — |
  | `inboxCells` | 2048 | — |
  | `inboxCellBytes` | 512 | — |
  | `waitStrategy` | `SPIN_THEN_YIELD` | — |
  | `arenaSlabBytes` | 4 MiB (`RowArena.DEFAULT_SLAB_BYTES`) | — |
  | `arenaMaxSlabs` | 8 | — |
  | `threadNamePrefix` | `pravaha-lane` | — |
  | `daemon` | false | — |
  | `exchangeCells` | 1024 | — |
  | `shutdownTimeout` | 5 s | — |
  | `MemoryAccess` | Agrona | only `-Dpravaha.memory`, which silently falls back (CFG-047) |

---

## Security: policy × authentication × allow-anonymous × TLS (CFG-057 … CFG-080)

### The 2 × 2 × 2

Eight node starts, `audit: none`, no TLS, a one-token table wherever `authentication: token`.

| Case | policy | auth | allow-anon | Starts? | `security:` line |
|---|---|---|---|---|---|
| CFG-057 | permissive | none | false | **no** — `PRV-7002` | — |
| CFG-058 | permissive | none | true | yes | `authentication=none, policy=permissive, audit=none, flight transport=PLAINTEXT` |
| **CFG-059** | permissive | **token** | false | **no** — `PRV-7002` | — |
| CFG-060 | permissive | token | true | yes | `authentication=token, policy=permissive, …` |
| CFG-061 | authenticated | none | false | **no** — `PRV-7002` | — |
| CFG-062 | authenticated | none | true | **no** — `PRV-7002` | — |
| CFG-063 | authenticated | token | false | yes | `authentication=token, policy=authenticated, …` |
| CFG-064 | authenticated | token | true | yes | identical to CFG-063 |

- **CFG-057 — PASS.** Refused with the full three-remedy message verbatim: *"this node is configured
  to accept unauthenticated callers and serve them every view (pravaha.security.authentication=none,
  policy=permissive) … Set pravaha.security.authentication=token with pravaha.security.tokens.*, or
  set pravaha.security.policy=authenticated, or -- if open really is what you want -- set
  pravaha.security.allow-anonymous=true to say so on purpose."* Non-zero exit; neither 18800 nor
  19800 bound afterwards. CFG-058 starts on the same file with one line changed, so the refusal is
  live rather than incidental.
- **CFG-058 — PASS.** Anonymous `GET /api/v1/streams` → 200 with `txn` and all four fields.
  Anonymous Flight `queries` → served. **Anonymous `POST /api/v1/streams` → `201 Created`** with the
  new stream echoed back — recorded as the case asks, and worth restating: an open server accepts
  stream *declarations*, not only reads, from anyone who can reach 18800. Constraints (2) and (3)
  are waived here by the operator's own `allow-anonymous: true`, so this is not an override finding;
  it is the documented meaning of the switch.
- **CFG-059 — FAIL (finding CFG-9, and `SX-12` reconfirmed).** `policy: permissive`,
  `authentication: token`, a real token table, `allow-anonymous: false` — a fully credentialled,
  entirely reasonable deployment — **refuses to start**. The case predicts it starts with only the
  plaintext WARN. Worse than the refusal: the message is CFG-057's, which begins *"this node is
  configured to accept unauthenticated callers … (pravaha.security.authentication=none,
  policy=permissive)"* — **`authentication=none` is hard-coded into the string**
  (`PravahaNode.java:179-181`) and this node's `authentication` is `token`. The operator is told the
  cause is a setting they did not make.
- **CFG-060 — FAIL (finding CFG-9).** The case's premise is that `allow-anonymous` is **dead** under
  `authentication: token` and that CFG-060 is therefore identical to CFG-059. It is not: CFG-059
  refuses and CFG-060 **starts**. `open` is now `!(securityPolicy() instanceof
  AuthenticatedOnlyPolicy)` (`PravahaNode.java:174`), so `allow-anonymous` is consulted on every
  non-`authenticated` policy regardless of authentication. The key is alive, load-bearing, and its
  own guard's message still describes the old semantics.
- **CFG-061 — PASS.** Refused: *"pravaha.security.policy=authenticated with
  pravaha.security.authentication=none is a node nobody can use: the policy serves only verified
  callers and nothing here can verify one. Set pravaha.security.authentication=token and configure
  pravaha.security.tokens, or choose a policy that admits anonymous callers."* The comment's
  reasoning was **not** independently verified by commenting out the guard — this round modifies no
  code — but CFG-079's HTTP observations reach the same conclusion legally: the HTTP surface serves
  whatever the policy admits and has no filter when authentication is off.
- **CFG-062 — PASS.** `allow-anonymous: true` does **not** get past the second guard — identical
  `PRV-7002`, because the second `if` (`PravahaNode.java:184`) does not consult it. The two-refusal
  sequence the case asks to record is real: only the *first* refusal names `allow-anonymous` as a
  remedy, so an operator who takes that advice on a node whose policy is `authenticated` lands on a
  second refusal that does not acknowledge what they just changed.
- **CFG-063 — PASS, and the regression it guards is intact.** Starts; WARN about credentials in the
  clear; anonymous Flight → `PRV-7001`; anonymous HTTP → `401 PRV-7001`; with a token both succeed.
  The ordering fix holds: `authorizedBy(securityPolicyOf(registry), auditSink())` is called at
  `PravahaNode.java:457` **before** `.hosting(registry)` at `:458`, so `requireOnePolicy` compares
  the registry's own policy with itself and `policy: authenticated` starts a node with Flight
  enabled.
- **CFG-064 — PASS.** Identical to CFG-063 in all four observations. This is the configuration a
  team reaches by hardening `dev` and forgetting to delete `allow-anonymous: true`, and — given
  CFG-060 — it is now the *only* reason that leftover is harmless: it is ignored solely because the
  policy is `authenticated`.

### Invalid values in the same four keys

- **CFG-065 — FAIL (finding CFG-9, and assumed fact 3).** `policy: strict` is refused with
  `PRV-7002 pravaha.security.policy is 'strict', which is not a policy this node knows. Use
  'permissive' or 'authenticated', or implement SecurityPolicy for rules of your own.` ✔ — **but not
  where the case says.** It fails at **context refresh**, inside
  `BeanInstantiationException: Factory method 'pravahaSecurityPolicy' threw exception`, because
  `HttpAuthorizer` now depends on a `SecurityPolicy` bean (`PravahaServerApplication.java:99-116`).
  `PravahaNode.start()` never runs, so `CoordinatorFactory.create` is never called and the case's
  central question — whether a coordinator thread leaks out of a `start()` that throws after
  `coordinator.start()` — **cannot be asked from configuration at all**. No thread leaks; the guard
  the case is probing is unreachable by this route.
- **CFG-066 — PASS on the observation, by a different mechanism.** `authentication: none`,
  `policy: strict`, `allow-anonymous: false` reports the **unknown policy**, not the open server, ✔ —
  but again at bean creation rather than inside `refuseAccidentalOpenServer`. The two-restart
  sequence the case asks to record is real: fix the policy to `permissive`, restart, and meet
  CFG-057's refusal.
- **CFG-067 — FAIL (finding CFG-10).** `authentication: token` with `tokens: {}`: the node
  **starts** ✔; anonymous HTTP → `401`; **any** token → `401`; anonymous Flight → `PRV-7001 this
  server requires a credential`; a credentialled Flight call → `PRV-7001 this server has no way to
  verify credentials, so it accepts none. Configure a TokenVerifier, or run without authentication if
  the server is already behind a boundary that does it.` — an excellent message, at the **first
  call**. `grep -ciE 'no tokens|tokens are configured|rejectAll'` over the whole startup log: **0**.
  `SecurityProperties.java:144-147`'s own comment says the reason should be visible at startup
  rather than in a support ticket about 401s; it is not, which is the condition the case names as
  the finding.

### TLS: the pair that is never checked as a pair

Nine node starts over `certificate ∈ {unset, valid, missing}` × `key ∈ {unset, valid, missing}`,
with a real self-signed pair generated once into `$QA/tls/`.

| | key unset | key valid | key missing-file |
|---|---|---|---|
| **cert unset** | CFG-068 PLAINTEXT | **CFG-070 PLAINTEXT** | **CFG-076 PLAINTEXT** |
| **cert valid** | **CFG-071 `NullPointerException`** | CFG-069 TLS | CFG-073 `PRV-6104` (key) |
| **cert missing** | CFG-075 `PRV-6104` (cert) | CFG-072 `PRV-6104` (cert) | CFG-074 `PRV-6104` (cert) |

- **CFG-068 — PASS.** `flight transport=PLAINTEXT`; node up; the baseline every other cell is read
  against.
- **CFG-069 — PASS.** `flight transport=TLS`; node up. The control for the whole grid: the same key
  file plus one certificate line produces TLS, so the plaintext in CFG-070 is caused by the missing
  certificate and not by an unusable key.
- **CFG-070 — PASS (i.e. the defect reproduces).** `pravaha.flight.tls.key` valid, no certificate:
  the node **starts in plaintext**, logs `security: … flight transport=PLAINTEXT`, and **nothing
  anywhere in the startup log mentions that a TLS private key was configured and ignored**. Under
  `authentication: token` the same node also emits *"set pravaha.flight.tls.certificate and .key"* —
  advice the operator has already half-taken, with no acknowledgement of the half they took. The
  packet capture the case asks for was not taken; the plaintext is established instead by a
  successful `grpc://` client call on the same node, which is the stronger of the two.
- **CFG-071 — PASS (the defect reproduces exactly as written).** Certificate valid, key unset:

  ```
  Caused by: java.lang.NullPointerException: Cannot invoke "java.io.File.isFile()" because "privateKey" is null
      at com.ash.messaging.pravaha.flight.PravahaFlightServer.encryptedWith(PravahaFlightServer.java:123)
      at com.ash.messaging.pravaha.server.PravahaNode.start(PravahaNode.java:463)
      at org.springframework.context.support.DefaultLifecycleProcessor.doStart(...)
  ```

  No `PRV-` code. The helpful-NPE message names `privateKey`, a field, and never
  `pravaha.flight.tls.key`. It propagates through `SmartLifecycle` and fails the context. The
  coordinator question raised in CFG-065 does apply here — this failure *is* inside `start()`, after
  `coordinator.start()` — but the JVM exits, so no thread outlives the process. Filed with CFG-070 as
  one finding with two symptoms (CFG-6).
- **CFG-072 — PASS.** `PRV-6104 the TLS certificate $QA/tls/absent.crt is not a readable file` —
  absolute path, startup fails. The behaviour CFG-070 should have.
- **CFG-073 — PASS.** `PRV-6104 the TLS private key $QA/tls/absent.key is not a readable file` —
  absolute path, startup fails. The behaviour CFG-071 should have. The two working branches are
  three lines apart at `PravahaFlightServer.java:118-127`, and the two failures above walk past both.
- **CFG-074 — PASS.** Both halves missing → the message names the **certificate** only. The key's
  absence is never mentioned; the operator fixes the path in the message, restarts, and meets
  CFG-073.
- **CFG-075 — PASS.** Certificate missing, key unset → `PRV-6104` about the certificate, **not** an
  NPE. The certificate check at `:118` runs before the null dereference at `:123`, so **the crash in
  CFG-071 only happens to operators who got the certificate right**.
- **CFG-076 — PASS.** Certificate unset, key missing-file → **plaintext, node up, silence**. The key
  is never opened, so its non-existence is never noticed. Two wrong things and zero messages.

### Where the two halves of the security model diverge

- **CFG-077 — PASS.** Across every configuration in this log that started, the `security:` line
  matched the enforced behaviour. The residual risk the case names is now moot in the direction it
  worried about — `authentication: tokens` no longer starts at all (CFG-012), so the line cannot
  print `none` for a typo'd `token`. It remains accurate for the genuine `none`/`token` split:
  CFG-058's `authentication=none` node served anonymous callers and CFG-063's `authentication=token`
  node refused them.
- **CFG-078 — PASS.** `application-dev.yaml` is `pravaha.security.allow-anonymous: true` and seven
  lines of comment — nothing else. Started with and without `--spring.profiles.active=dev` against
  an otherwise identical file: the **only** difference is that the `dev` run starts and the other is
  refused by CFG-057's guard. `QUICKSTART.md:93-101` and `HANDOVER.md:96` both describe it as exactly
  that acknowledgement; the file has not grown.
- **CFG-079 — FAIL (finding CFG-5, against the case rather than a new product defect).** The case's
  Expected — *"Record which `/api/v1` operations are policy-checked; expect none"* — is false.
  `HttpAuthorizer` is injected into both controllers and consults a `SecurityPolicy`:
  `StreamController.java:65` filters the listing through `mayRead`, `:76` calls `requireRead`, `:95`
  calls `requireAdminister`; `QueryController.java:78` calls `requireRead`. Observed with two tokens
  under `policy: authenticated`: `ann` `POST`s a stream (201) and `bob` sees it on `GET` — which is
  **correct**, because `AuthenticatedOnlyPolicy` grants every verified principal everything; it is
  the policy speaking, not its absence. What *has* not moved, and is the real divergence, is the
  audit sink: `HttpAuthorizer` records every decision into the `AuditSink` bean, which
  `PravahaServerApplication.java:118-121` hard-codes to `AuditSink.NONE`. With `audit: memory`
  configured, **every HTTP authorization decision is recorded nowhere** while every Flight decision
  is recorded. Half the decisions, in a sink nobody reads — the exact failure CFG-014's executor note
  is looking for, one layer up from where it looks.
- **CFG-080 — PASS, and the mechanism is shown rather than the absence of an error.** One object:
  `securityPolicyOf(registry)` (`PravahaNode.java:305-307`, `return registry.policy()`) is handed to
  `authorizedBy` at `:457`, and `hosting(registry)` runs at `:458`, so `requireOnePolicy`
  (`PravahaFlightServer.java:184-192`) compares the registry's policy with itself. No configuration
  reaches a divergence on that path, for both `permissive` and `authenticated`. **A second policy
  object does exist** — `PravahaServerApplication.pravahaSecurityPolicy` builds its own from the same
  key for `HttpAuthorizer` — but it is built by the identical `switch` on the identical string, so
  the two agree by construction rather than by identity. That is a weaker guarantee than
  `requireOnePolicy` gives on the Flight side, and it is worth saying: a future policy with
  per-instance state would diverge silently between HTTP and Flight with nothing to catch it.

---

## The two out-of-orderness keys (CFG-081 … CFG-086)

- **CFG-081 — PASS. This is the case that settles it.** Two files differing in one line, same data,
  same SQL, same build, same ports, consecutive:
  - **A** (`pravaha.streams.txn.out-of-orderness: 0s`) → **6 rows**: `(T0+0,T0+5)` `u0=5, u1=7,
    u2=3`; `(T0+5,T0+10)` `u0=17, u1=8, u2=15`. Totals 15 and 40, sum **55**.
  - **B** (`pravaha.watermark.out-of-orderness: 0s`) → **0 rows.**

  `6 ≠ 0`. Both keys are documented in `application.yaml`; one of them does nothing. A's six rows
  require the watermark to have passed `T0+10`, which the 10 s default cannot produce at any time, so
  the result is not a timing artefact.
- **CFG-082 — PASS.** Both keys set and disagreeing (`watermark: 0s`, `streams.txn: 30s`) → **0
  rows**. The per-stream key wins because it is the only one read; the engine-wide key that reads
  like a default and is set to the more permissive value has no say. Nothing in the log mentions
  either key.
- **CFG-083 — PASS, and it is the reason the inert key survives.** Both at `0s` → **6 rows**,
  identical to CFG-081 A. The configuration an operator who read both documents arrives at is the
  one that works, so the dead key is never discovered until somebody deletes the line that was doing
  the work.
- **CFG-084 — PASS.** `out-of-orderness: 0s` with `event-time` removed → **0 rows**, for both
  reasons. `withEventTime` returns the unmodified schema at `PravahaNode.java:240-242` before line
  `:257` applies the lateness, so the key is present, syntactically valid, and applied to nothing;
  and with no event-time column the plugin stamps every row zero. CFG-081 A is the control: same key,
  same value, `event-time` present, six rows.
- **CFG-085 — NOT RUN.** Requires a second declared stream in a join or union and a per-side row
  count. Not executed in this round; recorded as not run rather than inferred from CFG-009. The
  tension the case identifies is visible in the documentation without running it:
  `OPERATIONS.md:233` says a query reading three streams *"should get three tolerances rather than
  the worst of them"*, and `OPERATIONS.md:238` says a query's watermark *"is the **minimum across its
  partitions**"*. Both sentences are in the same document eleven lines apart.
- **CFG-086 — PASS. The documentation audit closes the loop.** `grep -rn 'out-of-orderness' docs/
  pravaha-*/src/main/resources/`:
  - The **inert** key, `pravaha.watermark.out-of-orderness`, is documented in **four** places:
    `application.yaml` (the `pravaha.watermark` block), `OPERATIONS.md:222`, `CONCEPTS.md:66`, and
    javadoc at `StreamSchema.java:53`.
  - The **working** key, `pravaha.streams.<n>.out-of-orderness`, appears in javadoc at
    `StreamDeclarationProperties.java:86-92` and **in no operator-facing document at all**.
  - `OPERATIONS.md:233-235` tells the operator to set lateness *"per stream, at creation, with
    `StreamSchema.outOfOrderness`"* — a Java API — and adds *"The configuration key is the default
    for streams that do not say"*, which is a sentence about a key with no reader.

  Four documents for the key that does nothing, none for the key that works. The exact edits the
  case asks the executor to propose are recorded in finding CFG-8; no document was changed in this
  round, because the round's rule is observation, not repair.

---

## `pravaha.streams` and `pravaha.sources` as a pair (CFG-087 … CFG-093)

- **CFG-087 — PASS.** Whole `pravaha.sources` block removed: node starts; `no sources are bound, so
  registered queries receive rows only from clients that push them; bind one under
  pravaha.sources.<stream>` ✔ verbatim; `txn` listed on `GET /api/v1/streams`; `QW` registers and
  reports RUNNING; the view is **empty** for the life of the run. Documented, intended, and the most
  common false alarm there is.
- **CFG-088 — PASS.** `pravaha.streams` removed, `pravaha.sources.txn` kept: node **starts** and
  logs `sources bound: [txn <- filesystem[path, skip.header, schema]]` — it says it bound a stream.
  `GET /api/v1/streams` returns `[]`. `register QW` fails with **`PRV-2002 Object 'txn' not found.
  Known streams: []`** — the case predicts `PRV-2003`; the code is `PRV-2002`, recorded as a
  citation correction rather than a defect. Nothing at startup compares the two maps
  (`SourceBindingProperties.toBindings()` at `:94-97` does no validation), which is the finding
  (CFG-8).
- **CFG-089 — PASS.** `streams.txn` + `sources.txns`: starts; `sources bound: [txns <- …]`;
  `GET /api/v1/streams` lists `txn`; `QW` against `txn` registers, runs and receives nothing.
  **No log line anywhere pairs the two names.** Neither block is wrong on its own and the node is
  useless — which is what a typo produces.
- **CFG-090 — PASS.** Four streams declared, two bound: `streams declared in configuration: [s1, s2,
  s3, s4]` and `sources bound: [s2 <- …, s1 <- …]`. Four queries, one per stream: `Qs1` and `Qs2`
  deliver **6 rows** each (with `out-of-orderness: 0s`), `Qs3` and `Qs4` deliver **0**. The two log
  lines together contain the answer and neither states it. The line that should exist is
  *"declared and unbound: s3, s4"*.
- **CFG-091 — FAIL (finding CFG-3).** `streams declared in configuration:` follows file order in
  both runs — `[s1, s2, s3]` and `[s3, s2, s1]` ✔ — and so does `GET /api/v1/streams`. **`sources
  bound:` does not.** The `s3,s2,s1` file produced `sources bound: [s3 <- …, s1 <- …, s2 <- …]`, and
  the four-stream file produced `[s2 <- …, s1 <- …]` from a file declaring `s1` first.
  `PluginSourceFeeds.bindings` is a `ConcurrentHashMap` returned through `Map.copyOf`
  (`PluginSourceFeeds.java:61,86-88`), so the order is hash order, not file order. This is precisely
  the failure the case names — *"a `HashMap` here would make the startup log non-deterministic and an
  operator's diff of two nodes' logs useless"* — present in one of the two lines.
- **CFG-092 — PASS.** Four-column `pravaha.streams.txn.schema` against a two-column binding
  `schema`: the node **starts**, **nothing compares them**, **no warning**. The failure lands at the
  first registration: `PRV-5091 the 'filesystem' plugin could not be opened for stream 'txn': …
  PRV-5040 event.time names 'event_time', which is not a column of stream 'txn'` — the message is
  about the *binding's* schema wearing the *stream's* column name, which is confusing but is neither
  a per-row decode failure, nor silent nulls, nor a column shift. The safest of the three outcomes
  the case lists; the dangerous one does not occur. The matching-schema control was run first
  (canonical three rows) so the divergence is attributable.
- **CFG-093 — FAIL (finding CFG-3).** Seven stream names declared —
  `my-stream`, `1txn`, `select`, `txn ` (trailing space), `TXN`, `txn`, `txnü` — and the node starts
  reporting **five**: `streams declared in configuration: [my-stream, 1txn, select, txn, TXN]`, with
  `GET /api/v1/streams` returning the identical five. **`txn ` and `txnü` are silently dropped by
  Spring's relaxed map-key binding.** A declaration present in the file, syntactically valid, is
  absent from the catalog with no message at any level. `TXN` and `txn` coexist as two separate
  entries, so the catalog does **not** fold case. `registerDeclaredStreams` (`PravahaNode.java:217-232`)
  does no name validation, so `1txn` and `select` start and become catalog entries that SQL cannot
  name unquoted — CFG-088's symptom arriving from a different cause.

---

## Persistence, end to end (CFG-094 … CFG-102)

Every restart below reads the view **before feeding anything**, by restarting with the
`pravaha.sources` block removed — necessary because a filesystem binding re-reads its file from the
beginning on every start, which would make every one of these cases pass for the wrong reason. The
case file's "read the view before feeding" instruction is not achievable with the standing setup as
written, and that is worth carrying forward.

- **CFG-094 — PASS.** Journal set, checkpoint unset. Run 1: register `QW`, feed, six rows. Restart
  with no source: `registry recovered 1 of 1 queries from $QA/p/j094`; `pravaha queries` → **1**,
  RUNNING; the view → **0 rows**; the `pravaha.checkpoint.directory is not set` WARN fired at
  startup. Knows every question and none of the answers, exactly as `PersistenceProperties`' javadoc
  says.
- **CFG-095 — PASS.** Checkpoint set, journal unset. Before the restart `$QA/p/k095/QW/` holds
  `checkpoint-8/9/10.bin`. After it: `pravaha queries` → **0**, and the three files are **still
  there**, orphaned. Nothing reads them, nothing prunes them, and nothing mentions them. Disk grows
  by one abandoned checkpoint set per restart, on the path `TROUBLESHOOTING.md` already names as the
  known disk-growth path.
- **CFG-096 — PASS.** Both set. After restart and **before feeding**: `registry recovered 1 of 1`;
  `pravaha queries` → 1; the view → the **six rows** (`u0=5,u1=7,u2=3,u0=17,u1=8,u2=15` with
  `out-of-orderness: 0s`). The per-query subdirectory is `k096/QW/` before and after the restart —
  derived from the view name, nothing volatile — so the orphaning failure of CFG-095 cannot arrive
  by accident.
- **CFG-097 — PASS.** Neither set: **both** WARNs present at startup, one from `PravahaNode.java:370`
  and one from `:420`. After restart, 0 queries and 0 rows. One warning without the other would be
  CFG-094 or CFG-095 and the operator needs to know which.
- **CFG-098 — FAIL (finding CFG-13, HIGH).** Two nodes — `node-a` on 18800/19800 and `node-b` on
  18801/19801, different `pravaha.node.id`, **different journals**, the **same**
  `pravaha.checkpoint.directory` — each registering a view named `QW`. After 22 s the shared root
  holds exactly one subdirectory:

  ```
  $QA/p/shared98/QW/checkpoint-10.bin
  $QA/p/shared98/QW/checkpoint-11.bin
  $QA/p/shared98/QW/checkpoint-12.bin
  ```

  **The per-query subdirectory is namespaced by the view name and by nothing else.** Two nodes with
  distinct ids write one directory; each takes ~10 checkpoints in 20 s and each `prune(3)` deletes
  whatever is oldest, so the three survivors belong to an unpredictable mix of two nodes. A restart
  of either node restores from files the other wrote. This is silent, durable cross-node state
  corruption reachable from two lines of YAML, with no warning at startup and no way to detect it
  afterwards.
- **CFG-099 — FAIL (finding CFG-14, HIGH).** Two nodes, **same** `pravaha.registry.journal`,
  different checkpoint directories. `node-a` registers `QA1` and `QA2`, `node-b` registers `QB1`.
  Both append to one 699-byte file; `RegistryJournal` takes **no lock** (`grep -n 'lock\|FileLock'`
  finds none; `append` is `synchronized` on the instance only). The interleaved records replay
  cleanly — no `PRV-8005` — which is *worse* than the corruption the case predicts: on restart,
  **`node-a` logs `registry recovered 3 of 3 queries` and comes up running `QA1`, `QA2` and `QB1`**,
  a query it never accepted, whose owner it may not be able to identify, and whose rows it will now
  serve. One shared path silently merges two nodes' registration sets.
- **CFG-100 — FAIL (finding CFG-12, HIGH).** Journal set and working; after one successful
  registration the journal file is `chmod 400`'d (`-r--------` confirmed by `ls -l`). A second
  registration **succeeds**: `pravaha queries` shows 2, no `PRV-8006`, and `ls -l` on the journal now
  reads **`-rw-------`**. `RegistryJournal.append` (`:216`) calls `SensitiveFiles.createOwnerOnly`,
  whose `narrow(file, OWNER_ONLY)` (`SensitiveFiles.java:70,79-90`) sets `rw-------`
  **absolutely** — so it widens a mode the operator deliberately narrowed. The control
  `OPERATIONS.md:379` describes ("a registration whose journal append fails is refused, because
  acknowledging one that will not survive a restart tells the client something untrue") can never
  fire for this failure mode, because the failure is removed rather than reported. Restoring
  `chmod 600` and registering again also succeeds, with no residue.
- **CFG-101 — FAIL (finding CFG-12, HIGH).** Checkpoint directory set, `interval: 2s`; after the
  first checkpoints the per-query directory is `chmod 500`'d (`dr-x------` confirmed). Twelve
  seconds later it reads **`drwx------`** and the file count is unchanged at 3 — the next checkpoint
  restored write permission and proceeded. Nothing moved anywhere an operator watches: the seven
  `pravaha_*` gauges on `/actuator/prometheus` (`rows_in`, `running`, `view_evicted`,
  `view_removals`, `view_size`, `view_updates`, `watermark_lag_seconds`) are unchanged,
  `pravaha_query_running{query="QW"}` still reads `1.0`, `pravaha queries` still says RUNNING, and
  `grep -icE 'checkpoint.*(fail|error|warn)'` over the log returns **0**. This is `ST-3` reproduced
  with its root cause named: `checkpointQuietly` is not the reason for the silence; there is nothing
  to be quiet about, because the permission change was undone.
- **CFG-102 — PASS, on both independent observations.** Run 1 with `keep: 5`, `interval: 2s`, 20 s →
  `checkpoint-7/8/9/10/11.bin`, five files. Restart with `keep: 1` and no source → after the first
  checkpoint the directory holds **one** file, `checkpoint-15.bin`. The four older ones, including
  every file the previous run wrote, are gone ✔, and the id is **monotonic across the restart**
  (15 > 11) with no id reused ✔ — `PeriodicCheckpointer`'s constructor resumes above the highest
  stored id, so "checkpoint 11" means one thing for the life of the directory. A build that recreated
  the store from scratch would have restarted at 1 and passed the count check while failing this one.

---

## Flight off, and what a monitoring system then believes (CFG-103 … CFG-105)

- **CFG-103 — PASS on every surface exercised.** With `pravaha.flight.enabled: false`:

  | Surface | Result |
  |---|---|
  | `GET /api/v1/status` | 200, full body |
  | `GET /api/v1/streams` | 200, `txn` with four fields |
  | `POST /api/v1/streams` | 201 |
  | `POST /api/v1/queries/validate` | 200 |
  | `POST /api/v1/queries/explain` | 200 |
  | `GET /actuator/prometheus` | 200 |
  | `GET /actuator/health` | **503 DOWN** |
  | `ss -ltn` | 18800 bound, **19800 absent** |
  | `pravaha queries` | `PRV-1041 io exception` |

  Every `/api/v1` and `/actuator` path answers normally; the Flight port is not bound; the CLI
  cannot connect. There is no REST path that registers a continuous query — `validate` and `explain`
  do not register and there is no `POST /api/v1/queries` in the OpenAPI document's `paths`. The node
  can be described, inspected and monitored, and cannot be used. **Not exercised:** the Java SDK,
  the Java Flight SDK, the Python SDK, the console, and the eight CLI verbs other than `queries`;
  they share `queries`' single transport (ADR-030) but that is inference, not evidence, and is
  recorded as such.
- **CFG-104 — PASS.** `GET /actuator/health` → **`{"status":"DOWN","groups":["liveness","readiness"]}`**.
  The same request against the same node with `flight.enabled: true` returns `UP`, so the DOWN is
  caused by the key. The `components` map the case wants to read — `flight: not listening; no client
  can reach this node` alongside `queries`/`failedQueries` — is **suppressed** by
  `show-details: when-authorized` on a node with `authentication: none`, so the aggregate is right
  and the reason is invisible. `EngineHealthIndicator`'s javadoc'd defect is **not** present.
- **CFG-105 — PASS, and the predicted finding does not exist.** `/actuator/health/liveness` →
  `200 {"status":"UP"}` (correct: the JVM is alive and restarting it would not help).
  `/actuator/health/readiness` → **`503 {"status":"DOWN"}`**. The node leaves the rotation. The
  reason is one line the case assumed was missing: `application.yaml` already carries
  `management.endpoint.health.group.readiness.include: readinessState,engine`, so the custom
  indicator is in the readiness group by configuration. The fix the case proposes is already
  applied.

---

## Spring profiles (CFG-106 … CFG-110)

- **CFG-106 — PASS.** No profile: `No active profile set, falling back to 1 default profile:
  "default"` — that is the exact line an operator reads to confirm which files were applied. Against
  a file with `allow-anonymous` absent, the node is refused by CFG-057's guard ✔.
- **CFG-107 — PASS.** `--spring.profiles.active=dev`: `The following 1 profile is active: "dev"`;
  the packaged `application-dev.yaml` overlays exactly one key; the node starts open with
  `security: authentication=none, policy=permissive, audit=none, flight transport=PLAINTEXT` ✔.
- **CFG-108 — PASS, and the gap it names is real.** `--spring.profiles.active=prod`: `The following
  1 profile is active: "prod"`. **Spring does not refuse an unknown profile and logs nothing about a
  file it could not find.** No `application-prod.yaml` exists, nothing is overlaid, and the node is
  refused for the security reason — so an operator's conclusion is that `prod` is broken. There is a
  line naming the active profile and **no** line saying no file was found for it. The line that
  should exist is *"no configuration file was found for active profile 'prod'"*; a silently ignored
  profile name is how a production deployment runs on development settings.
- **CFG-109 — PASS, with a qualification that matters more than the case's own expectation.**
  `dev,prod`, `prod,dev` and `dev,dev` all log their profile list correctly (`The following 2
  profiles are active: "dev", "prod"` etc.; `dev,dev` collapses to `The following 1 profile is
  active: "dev"`). The real test, with `application-prod.yaml` (`allow-anonymous: false`) written
  **alongside** `application-dev.yaml` in the same config location: `dev,prod` → **refused**,
  `prod,dev` → **starts**. Later profiles win for overlapping keys ✔. **But when the two files live
  in different locations, profile order does not decide.** With `application-prod.yaml` in an
  `--spring.config.additional-location` directory and `application-dev.yaml` packaged in the jar,
  **both** orderings are refused — the additional location outranks the classpath regardless of which
  profile is named last. Profile precedence is not the whole semantics; location precedence dominates
  it, and neither is documented anywhere in this repository.
- **CFG-110 — PASS, and it validates every other case in this file.** A file given with
  `--spring.config.additional-location` setting `pravaha.security.allow-anonymous: false`, run
  **with** `--spring.profiles.active=dev` (whose packaged file sets it `true`): the node is
  **refused**. The additional location wins over `application-dev.yaml`, so every earlier case here
  that set a key `application-dev.yaml` also sets was testing the value it thought it was.
  `--spring.config.additional-location` **adds to** rather than replaces the packaged locations:
  confirmed by every run in this log, all of which inherited `springdoc.api-docs.path`,
  `management.endpoints.web.exposure.include` and the health groups from the packaged
  `application.yaml` while overriding only the `pravaha.*` keys the case named.

---

## Verdict summary

| | Cases |
|---|---|
| **PASS (75)** | 002, 005, 006, 007, 008, 009, 011, 013, 014, 015, 019, 025, 026, 027, 029, 030, 031, 032, 037, 040, 042, 043, 044, 046, 048, 049, 050, 051, 052, 053, 054, 055, 056, 057, 058, 061, 062, 063, 064, 066, 068, 069, 070, 071, 072, 073, 074, 075, 076, 077, 078, 080, 081, 082, 083, 084, 086, 087, 088, 089, 090, 092, 094, 095, 096, 097, 102, 103, 104, 105, 106, 107, 108, 109, 110 |
| **FAIL (28)** | 001, 003, 004, 010, 012, 016, 017, 020, 021, 022, 023, 024, 028, 039, 041, 045, 047, 059, 060, 065, 067, 079, 091, 093, 098, 099, 100, 101 |
| **BLOCKED (4)** | 033, 034, 035, 036 — the ZooKeeper coordinator is not on the shipped jar's classpath and there is no documented way to put it there (`I-7`), so `PRV-9001` fires before any `pravaha.cluster.zookeeper.*` key is read |
| **NOT RUN (3)** | 018 (no shipped readback for `Principal.tenant()`), 038 (no in-flight request held across `SIGTERM`), 085 (multi-stream lateness not exercised) |

New findings: **CFG-1 … CFG-22** in [`../FINDINGS.md`](../FINDINGS.md).

