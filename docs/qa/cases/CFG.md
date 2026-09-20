# CFG — configuration keys, their defaults, their refusals, and the combinations nobody checks

The surface under test is **the file a node is described by**, and the code that reads it:
`pravaha-server/src/main/resources/application.yaml` and `application-dev.yaml`;
`pravaha-server/.../PravahaNode.java` (the fourteen-argument constructor, `refuseAccidentalOpenServer`,
`registerDeclaredStreams`, `withEventTime`, `withDeclaredEventTime`, `securityPolicy`, `auditSink`,
`start`); the four `@ConfigurationProperties` classes —
`security/SecurityProperties.java`, `catalog/StreamDeclarationProperties.java`,
`ingest/SourceBindingProperties.java`, `state/PersistenceProperties.java`;
`PravahaServerApplication.java` (what is forwarded into the engine's own `Configuration`);
`pravaha-cluster/.../CoordinatorFactory.java` and `SocketProvider.java`;
`pravaha-runtime/.../exec/PeriodicCheckpointer.java` and `exec/QueryExecution.java`;
`pravaha-common/.../config/ConfigParsers.java` and `Configuration.java`;
`pravaha-flight/.../PravahaFlightServer.java` (`encryptedWith`, `requireOnePolicy`);
`pravaha-server/.../EngineHealthIndicator.java`.

Configuration is where this engine's failures are *cheapest to find and most expensive to miss*. A
wrong query is refused at registration by somebody who is watching. A wrong configuration key starts
a node that looks healthy and does less than the operator believes, for as long as nobody checks.
Round 1 found four such keys by accident. This file asks about every one of them on purpose.

**Ports:** HTTP 18800, Flight 19800. **Scratch:** `$QA = <scratchpad>/qa-cfg`.

---

## Facts this file assumes, each read out of the code

Stated once so the cases can be terse. An executor who finds one of these false has found something
more interesting than the case that referenced it.

1. **`PravahaNode` takes fifteen constructor arguments and eleven of them are `@Value`**
   (`PravahaNode.java:113-128`). Defaults are written in the annotation, *not* in
   `application.yaml`, so deleting a line from the YAML does **not** unset the key — it falls back
   to the annotation default. `application.yaml` and the annotation agree today for every key; a
   case below checks each pair.
2. The four `@ConfigurationProperties` classes are bound with `prefix = "pravaha"` for streams,
   sources and persistence, and `prefix = "pravaha.security"` for security
   (`StreamDeclarationProperties.java:44-45`, `SourceBindingProperties.java:43-44`,
   `PersistenceProperties.java:41-42`, `SecurityProperties.java:46`). The odd-looking choice is
   deliberate and documented at `SourceBindingProperties.java:52-58`: `prefix = "pravaha.sources"`
   would have bound `pravaha.sources.bindings.txn`, so the key everybody documents would have bound
   nothing.
3. **`refuseAccidentalOpenServer` runs *after* `CoordinatorFactory.create`**
   (`PravahaNode.java:328` then `:335`). A node with both a bad cluster mode and an accidentally
   open security posture reports the cluster problem and never mentions the security one.
4. `securityPolicy()` accepts `permissive`, `authenticated` and `authenticated-only`, case- and
   whitespace-insensitive, and throws `PRV-7002` on anything else (`PravahaNode.java:281-295`).
   `auditSink()` accepts `none` and `memory` and throws `PRV-7002` otherwise
   (`PravahaNode.java:302-313`). `SecurityProperties`' own javadoc at line 39 says the policy values
   are `permissive` or **`tenant`** — a value the code does not know.
5. `security.authenticates()` is `"token".equalsIgnoreCase(authentication)`
   (`SecurityProperties.java:110-112`). **Every other string, including `"tokens"`, `"TOKEN "` with
   a trailing space, and `"basic"`, silently means `none`.** There is no refusal of an unknown
   authentication value anywhere.
6. TLS is applied only through `if (tlsCertificate != null) server.encryptedWith(tlsCertificate,
   tlsKey)` (`PravahaNode.java:433-435`). So **key-without-certificate is silently plaintext**, and
   **certificate-without-key reaches `encryptedWith(cert, null)` and dereferences null at
   `PravahaFlightServer.java:123` (`privateKey.isFile()`) — a raw `NullPointerException`, not
   `PRV-6104`.** The pair is never validated as a pair.
7. `pravaha.watermark.out-of-orderness` **has no reader anywhere in the repository.** It is
   documented in `application.yaml`, `OPERATIONS.md:222`, `CONCEPTS.md:66` and
   `StreamSchema.java:51`. The key that works is `pravaha.streams.<n>.out-of-orderness`, read by
   `StreamDeclarationProperties.Declaration.setOutOfOrderness` and applied by
   `PravahaNode.withEventTime` — **and only when `event-time` is also declared**, because
   `withEventTime` returns the unmodified schema when it is not
   (`PravahaNode.java:232-236`). Round 1 proved the inertness by experiment
   (`docs/qa/logs/DOC.md:1362-1385`).
8. `pravaha.watermark.idle-after` is validated **once, at startup**, by constructing a throwaway
   `WatermarkTracker` (`PravahaNode.java:376-386`); bounds are `MINIMUM_IDLE_TIMEOUT = 1s` and
   `MAXIMUM_IDLE_TIMEOUT = 10m`, **refused rather than clamped**. `pravaha.watermark.tick` is
   **not** validated at startup; `tick <= idle-after` is checked per registration in
   `QueryExecution.generatingWatermarks` (`QueryExecution.java:339`).
9. ~~`PersistenceProperties.checkpointConfiguration()` builds a `Configuration` containing exactly
   two keys — interval and keep — so `pravaha.checkpoint.timeout` is inert on the server path.~~
   **WITHDRAWN 2026-09-19 (CFG-17).** False against this build, in both halves:
   `PersistenceProperties.Checkpoint` *has* a `timeout` field with a 30 s default, and
   `checkpointConfiguration()` emits **three** keys, `pravaha.checkpoint.timeout` among them, in
   nanoseconds — which is exactly what `PeriodicCheckpointer.from` reads. Pinned by
   `PersistencePropertiesTest.theCheckpointTimeoutIsBoundForwardedAndReadable_CFG17`. What CFG-024
   now asks is whether the timeout is *enforced*.
10. `PeriodicCheckpointer` refuses `keep < 1` with an `IllegalArgumentException`
    (`PeriodicCheckpointer.java:93-95`) — **at construction, which is at first registration, not at
    startup**. `PersistenceProperties.Checkpoint.keep` is a plain `int` with no validation.
11. `PravahaNode` builds the cluster `Configuration` from exactly two keys —
    `pravaha.cluster.mode` and `pravaha.cluster.mechanism` (`PravahaNode.java:144-147`).
    `SocketProvider` reads `pravaha.cluster.socket.peers`, `.heartbeat.millis` and `.timeout.millis`
    (`SocketProvider.java:54,80,81`) and `ZooKeeperProvider` reads four `pravaha.cluster.zookeeper.*`
    keys (`ZooKeeperProvider.java:61-68`). **None of them is forwarded**, so following
    `OPERATIONS.md:99-107` verbatim fails at startup with `PRV-9005`.
12. `PravahaServerApplication.pravahaEngine` forwards exactly **one** key into the engine's own
    `Configuration`: `pravaha.node.id` (`PravahaServerApplication.java:50-54`). Every engine-level
    key the design document describes — `pravaha.runtime.*`, `pravaha.state.*` — therefore has no
    path from a YAML file into the engine even if a reader existed.
13. **`arena.slab.size` is named as the remedy by six runtime error messages and is not a
    configuration key anywhere in the repository.** `WindowAssign.java:68`,
    `SymmetricHashJoin.java:170` and `:205`, `InterpretedPipeline.java:644` and `:706`,
    `LookupJoin.java:308`. `grep -rn` finds it in those six strings, in
    `RowArenaTest.java:170`, and in `docs/system_design.md:3543`. `state.slab.size` is the same
    story with one message (`RowStore.java:119`) and one test.
14. `QueryRegistry.executingWith(LaneConfig, MemoryAccess)` exists
    (`QueryRegistry.java:136`) and **nothing in `pravaha-server` calls it**. `LaneConfig.defaults()`
    is therefore the only lane sizing a server node can ever have: `batchSize=512`,
    `inboxCells=2048`, `inboxCellBytes=512`, `waitStrategy=SPIN_THEN_YIELD`,
    `arenaSlabBytes=4 MiB`, `arenaMaxSlabs=8`, `exchangeCells=1024`, `shutdownTimeout=5s`
    (`LaneConfig.java:83-96`). There is no `pravaha.lane.*` key.
15. `EngineHealthIndicator` returns **DOWN** when `node.flightPort()` is empty — which is exactly
    the `pravaha.flight.enabled=false` case (`EngineHealthIndicator.java:69-77`). Its own javadoc
    records that this was once UP. `management.endpoint.health.probes.enabled=true` adds
    `/actuator/health/liveness` and `/actuator/health/readiness`; whether a custom indicator
    contributes to the **readiness group** is the thing to check, not to assume.
16. `Configuration`'s own parsers refuse rather than guess: a bare number is not a duration
    (`ConfigParsers.java:67-71`), booleans accept `true/false, yes/no, on/off, 1/0`
    (`ConfigParsers.java:35-41`), data sizes are binary (`ConfigParsers.java:95-98`). These raise
    `PRV-102n`. **Spring's binder, which is what reads `application.yaml`, is a different parser**
    with different rules — `Duration` accepts ISO-8601 `PT30S` and bare `30` (as milliseconds by
    default). Two duration dialects meet in this file; `PersistenceProperties.java:69-74` records
    the bug that came of it.
17. `application-dev.yaml` contains exactly one key: `pravaha.security.allow-anonymous: true`.
18. `OPERATIONS.md:322-326` documents `pravaha.flight.port: 8815`. `application.yaml` and
    `PravahaNode.java:124` both say **9090**, and `application.yaml`'s own comment explains at
    length why 9090 and not Arrow's registered 8815.

---

## Standing setup

`$QA/conf/base.yaml` — the file every case starts from, copied and edited per case:

```yaml
server:
  port: 18800
pravaha:
  node: { id: cfg-node }
  flight: { enabled: true, host: 127.0.0.1, port: 19800 }
  security: { authentication: none, policy: permissive, audit: none, allow-anonymous: true }
  streams:
    txn: { schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP", event-time: event_time, out-of-orderness: 2s }
  sources:
    txn: { plugin: filesystem, options: { path: $QA/data/txnA.csv, schema: "id:INT64,usr:STRING,amount:INT64,event_time:TIMESTAMP" } }
  watermark: { idle-after: 30s, tick: 1s }
  checkpoint: { directory: "", interval: 1m, keep: 3 }
  registry: { journal: "" }
  cluster: { mode: SINGLE, mechanism: single }
```

Started with
`pravaha-server --spring.config.additional-location=file:$QA/conf/<case>.yaml`, unless a case says
otherwise.

**Setup, when a case does not state one.** Every case below starts from `base.yaml` above, copied to
`$QA/conf/<case-id>.yaml` and edited **only in the key or keys the case names** — that is the case's
setup, and stating it again in forty-nine cases would be forty-nine chances for it to drift. A case
carries its own `**Setup:**` line exactly when it needs something else: extra files, a second node, a
pre-existing journal or checkpoint directory, a TLS pair, or a different starting security posture.
Two section preambles carry shared setup for the cases beneath them — the security matrix
(CFG-057 – CFG-080) and the TLS 3 x 3 (CFG-068 – CFG-076) — and those cases inherit it rather than
repeating it.

Every case that expects a **refusal** expects it to be visible in the process's exit
status and its final log line, not only in a stack trace.

**T0 = 1767225600000000000 ns = 2026-01-01T00:00:00Z.** Timestamps below are written `T0+n` seconds.

**`$QA/data/txnA.csv` — 12 rows.** Row `k` for `k = 0…11` is `k,u{k mod 3},{k+1},T0+k`.
So `id = k`, `usr` cycles `u0,u1,u2`, `amount = k+1`, `event_time = T0+k`.

**The canonical arithmetic, computed once.** `QW` is

```sql
SELECT window_start, window_end, usr, SUM(amount)
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '5' SECOND))
GROUP BY window_start, window_end, usr
```

| Window | Rows (k) | u0 | u1 | u2 | Window total |
|---|---|---|---|---|---|
| `[T0+0, T0+5)` | 0,1,2,3,4 | `1 + 4 = 5` | `2 + 5 = 7` | `3` | `5 + 7 + 3 = 15` |
| `[T0+5, T0+10)` | 5,6,7,8,9 | `7 + 10 = 17` | `8` | `6 + 9 = 15` | `17 + 8 + 15 = 40` |
| `[T0+10, T0+15)` | 10,11 | — | `11` | `12` | `11 + 12 = 23` |

Cross-check: `15 + 40 + 23 = 78`, and `1 + 2 + … + 12 = (12 × 13) / 2 = 78`. ✔

**Only the first window ever closes from this file.** The highest event time is `T0+11`; with
`out-of-orderness: 2s` the watermark reaches `T0+11 − 2s = T0+9`, which is `≥ T0+5` and `< T0+10`.
And it does not later advance by idle exclusion, because `QueryExecution.advanceWatermarkQuietly`
calls `observe` on every partition on every tick, and `WatermarkTracker.observe` sets
`idle = false` — so a partition whose file is exhausted never goes idle (TIME.md facts 8 and 9).

**The canonical assertion is therefore three rows**: `(T0+0, T0+5, u0, 5)`, `(T0+0, T0+5, u1, 7)`,
`(T0+0, T0+5, u2, 3)`. Any case below that says "QW delivers its canonical result" means exactly
those three rows and no fourth.

---

## The inventory — one case per key, five variants each

Each case in this section runs the same five-variant matrix against one key: **unset** (the default
that results), **a valid non-default value that must be observable**, **an invalid value**, **a
boundary value**, and **the wrong type**. A variant whose value is accepted and changes nothing is a
finding, not a pass; every case names what the observation is.

Thirty-eight of these keys are `pravaha.*`. The Spring keys in `application.yaml` are included
because they are in the same file, an operator edits them with the same hand, and nothing marks
which half of the file the engine reads.

### Node identity and the wire protocol

## CFG-001 — `pravaha.node.id`
**Intent:** The only key forwarded into the engine's own `Configuration`
(`PravahaServerApplication.java:50-54`), the id a cluster `Member` is advertised under
(`PravahaNode.java:332`), and the id `DefaultPravahaEngine` reports as `instanceId`
(`DefaultPravahaEngine.java:42`). If it is unreadable at any of those three, the key is decorative.
**Falsifier:** the configured id does not appear in `GET /api/v1/status`, in the coordinator's
membership line, or in the engine's own instance id — or two of the three disagree.
**Setup:** `base.yaml`, `pravaha.node.id` varied.
**Steps:** for each variant, start the node, `GET http://127.0.0.1:18800/api/v1/status`, and read
the startup log line produced by `CoordinatorFactory.describe`.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | key absent | `pravaha-node-01` — the `@Value` default at `PravahaNode.java:128` **and** the `application.yaml` value. Both must be the same string |
| valid | `cfg-node-alpha` | that exact string in status, in the membership line, and as the engine instance id |
| invalid | `""` (empty) | node starts with an empty id. `Member("")` is advertised. **There is no validation, so record what an empty member id does to `SocketCoordinator`'s "lowest id among peers I can reach" election — that is the finding, not the start** |
| boundary | 1 character `x`; and 4096 characters | both accepted; the 4096-character id must not be truncated anywhere it is displayed |
| wrong type | `id: 12345` (YAML integer) | Spring coerces to the string `12345`. Accepted. Note whether any surface shows it quoted or unquoted |

**Vacuity:** not stateful. The case cannot pass trivially because it compares three independent
readbacks of one value; a node that ignored the key would show `pravaha-node-01` in all three.

## CFG-002 — `pravaha.flight.enabled`
**Intent:** The switch that decides whether this node is reachable by any client at all — both SDKs
and the CLI speak Flight and nothing else (ADR-030). Its off position must be visible in health,
not only in a log line.
**Falsifier:** with `enabled: false` the node reports UP on `/actuator/health`, or on
`/actuator/health/readiness`.
**Setup:** `base.yaml`.
**Steps:** start with each variant; `GET /actuator/health`, `/actuator/health/liveness`,
`/actuator/health/readiness`; then `pravaha queries --url grpc://127.0.0.1:19800`.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `true` (`PravahaNode.java:122`). Flight listens on 19800; `pravaha queries` returns an empty list and exit 0 |
| valid | `false` | log line `Flight SQL disabled (pravaha.flight.enabled=false); this node serves HTTP only` (`PravahaNode.java:439`); `/actuator/health` is **DOWN** with `flight: not listening; no client can reach this node`; `pravaha queries` fails to connect |
| invalid | `maybe` | Spring's relaxed binder refuses a non-boolean and the context fails to refresh. **If it instead binds to `false`, that is a silent disabling of the wire protocol and is the finding** |
| boundary | `TRUE`, `yes`, `on`, `1` | Spring's binder accepts `true/false` and `on/off`/`yes`/`no` variably by version. Record exactly which of the four are accepted, because `ConfigParsers.parseBoolean` (`ConfigParsers.java:35-41`) accepts all four and an operator will assume one parser |
| wrong type | `enabled: []` | context refresh fails with a binding error naming the key |

**Vacuity:** the `false` variant cannot pass with the health indicator removed — Spring's default
contributors (ping, diskSpace) are both UP, which is precisely the state
`EngineHealthIndicator`'s javadoc describes as having shipped.

## CFG-003 — `pravaha.flight.host`
**Intent:** The bind address, and — separately — the address advertised to the cluster
(`PravahaNode.java:332` passes `flightHost` into `Member`). One key doing two jobs is worth pinning.
**Falsifier:** the node binds an address other than the one configured, or advertises a different
one than it binds.
**Setup:** `base.yaml`.
**Steps:** start; `ss -ltnp | grep 19800`; read the membership line; connect from 127.0.0.1.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `0.0.0.0` (`PravahaNode.java:123`), matching `application.yaml`. Socket bound on all interfaces |
| valid | `127.0.0.1` | bound on loopback only; a connection to the host's LAN address is refused. The membership line advertises `127.0.0.1:19800` — **which is an address no other node can reach, and nothing warns** |
| invalid | `not-a-host` | startup fails when gRPC cannot resolve. Expect the failure to name the key; record it if it does not |
| boundary | `::1`; `0.0.0.0` | IPv6 loopback binds; record whether the advertised member string is bracketed (`[::1]:19800`) or not — an unbracketed IPv6 address with a port is unparseable |
| wrong type | `host: 127` | coerced to the string `127`, which resolves to `127.0.0.1` on Linux. Accepted, and surprising |

## CFG-004 — `pravaha.flight.port`
**Intent:** The port every client, both SDKs, the CLI, the console and every example in the
repository default to — and the one place `application.yaml` argues at length against the more
"correct" choice. `OPERATIONS.md:326` documents a *different* number.
**Falsifier:** the node listens on a port other than the configured one, or `flightPort()` reports
a number the node is not listening on.
**Setup:** `base.yaml`.
**Steps:** start; `GET /api/v1/status`; `ss -ltn`; connect.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `9090` (`PravahaNode.java:124`), agreeing with `application.yaml`. **`OPERATIONS.md:322-326` says 8815 — raise that as a documentation defect against OPERATIONS.md, not against the code** |
| valid | `19800` | listening on 19800; `/api/v1/status` and health both report `flightPort: 19800` |
| invalid | `70000` | out of range. `@Value("${...}") int` binds it as an `int` fine; the failure comes from the socket bind. The message must name the port |
| boundary | `0` | ephemeral. `PravahaNode.flightPort()` exists precisely for this (`PravahaNode.java:470-473`). Health and `/api/v1/status` must report the **actual** port, never `0` |
| boundary | `1` | privileged; expect a bind failure as a non-root user, and for the message to say permission rather than "in use" |
| wrong type | `port: "nine thousand"` | context refresh fails with a binding error naming `pravaha.flight.port` |

**Vacuity:** the `0` variant cannot pass by accident — a node that reported the configured value
rather than the bound one would print `0`, and a client told to connect to port 0 fails.

## CFG-005 — `pravaha.flight.tls.certificate`
**Intent:** Half of the only pair that decides whether rows, credentials and query text cross the
wire in clear. Read as a `String` and turned into a `File` with no existence check at that point
(`PravahaNode.java:118,133`); the check is `PravahaFlightServer.encryptedWith`.
**Falsifier:** a configured certificate that cannot be read starts a node that serves plaintext.
**Setup:** `base.yaml` plus a real PEM pair generated once into `$QA/tls/` (`tls.crt`, `tls.key`).
**Steps:** start with each variant; read the `security: ... flight transport=` line
(`PravahaNode.java:342-350`); attempt `grpc://` and `grpc+tls://` connections.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent or `""` | `null` (`PravahaNode.java:133` treats blank as unset); log says `flight transport=PLAINTEXT`; `grpc://` connects |
| valid | `$QA/tls/tls.crt` **with** `.key` set | log says `flight transport=TLS`; `grpc+tls://` connects; plain `grpc://` does not |
| invalid | `/nonexistent/tls.crt` with `.key` set | `PRV-6104 FLIGHT_TLS_UNREADABLE` at startup, naming the absolute path (`PravahaFlightServer.java:118-122`). Startup must **fail**, not fall back |
| invalid | a path to a **directory** | `isFile()` is false → same `PRV-6104`. Confirm the message says "is not a readable file" rather than "does not exist" |
| invalid | an existing file that is not a PEM (e.g. `/etc/hostname`) | `isFile()` passes. The failure moves to gRPC's certificate parse. **Record which code, if any, it carries — a raw Netty exception here is a finding** |
| boundary | a file with mode `000` | `File.isFile()` is true for an unreadable file. Expect the failure to arrive later, from the TLS layer, with no `PRV-` code |
| wrong type | `certificate: 42` | coerced to `"42"`, becomes a relative path, `PRV-6104` |

**Vacuity:** the "invalid path" variants cannot pass with the check removed — the node would start
and `grpc://` would succeed, which is the exact failure `FlightErrors.java:46-49` was written
against.

## CFG-006 — `pravaha.flight.tls.key`
**Intent:** The other half. It is read (`PravahaNode.java:119,134`) and then used **only** inside
`if (tlsCertificate != null)`.
**Falsifier:** the key alone, with no certificate, starts a node and nothing says the TLS the
operator configured is not in force.
**Setup / Steps:** as CFG-005, varying `.key` and holding `.certificate` at a valid path except
where noted.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset (cert also unset) | both absent | PLAINTEXT, no message. Correct |
| valid | `$QA/tls/tls.key` with a valid cert | TLS |
| invalid | `/nonexistent/tls.key` with a valid cert | `PRV-6104` naming the key path (`PravahaFlightServer.java:123-127`) |
| **key set, certificate unset** | `.key` valid, `.certificate` absent | **`tlsCertificate == null`, so `encryptedWith` is never called and the node starts in PLAINTEXT.** The log line says `flight transport=PLAINTEXT`, which is true and is the only clue. **This is the defect; see CFG-070** |
| boundary | `key: ""` with a valid cert | blank is treated as unset → `tlsKey == null` → `encryptedWith(cert, null)` → **`NullPointerException` at `PravahaFlightServer.java:123`** |
| wrong type | `key: true` | coerced to `"true"`, relative path, `PRV-6104` |

**Vacuity:** not stateful, but the key-without-certificate variant must be checked by *attempting a
plaintext connection and succeeding*, not by reading the log line — a log line is what the current
behaviour already produces.

### Streams and sources

## CFG-007 — `pravaha.streams.<n>.schema`
**Intent:** The key that makes a node describable by a file at all. Parsed by
`FilesystemSourcePlugin.parseSchema` (`PravahaNode.java:215`) — the plugin's own grammar, shared
with the CLI, the REST API and a filesystem binding's `schema` option.
**Falsifier:** a stream declared with a schema is not plannable against after startup, or a stream
declared without one starts the node.
**Setup:** `base.yaml`, `pravaha.streams.txn.schema` varied.
**Steps:** start; `GET /api/v1/streams`; register `QW`.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | `txn: {}` (key present, schema absent) | **startup refused** with `PRV-2002`: *"stream 'txn' is declared under pravaha.streams with no schema. A stream is a name and a shape; the name alone cannot be planned against."* (`PravahaNode.java:209-214`) |
| valid | the base four-column spec | `GET /api/v1/streams` lists `txn` with four fields in declaration order; `QW` registers and delivers its canonical result |
| invalid | `"id:NOSUCHTYPE"` | refused at startup by `parseSchema`. Expect the message to name the unknown type and list the known ones |
| invalid | `"id"` (no type) | refused at startup |
| boundary | `""` (blank) | blank counts as absent for `isBlank()` → same `PRV-2002` as unset |
| boundary | a 200-column schema | accepted; all 200 columns visible on `GET /api/v1/streams/txn` |
| wrong type | `schema: {id: INT64}` (a map) | binding failure at context refresh naming the key |

**Vacuity:** the "valid" row is checked by *registering and reading a result*, not by the listing —
a catalog entry with no usable schema would still list.

## CFG-008 — `pravaha.streams.<n>.event-time`
**Intent:** The declaration without which no watermark advances and no window ever closes — a
windowed query registers, reports RUNNING, ingests every row and emits nothing, for ever
(`StreamDeclarationProperties.java:70-77`). It is also what `withDeclaredEventTime` pushes down to
the plugin as the `event.time` option (`PravahaNode.java:267-279`), so it is declared once rather
than twice.
**Falsifier:** `QW` against a stream with `event-time` declared emits nothing; or a misspelled
column starts the node.
**Setup:** `base.yaml`.
**Steps:** start; register `QW`; feed `txnA.csv`; read the view after 10 s.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `withEventTime` returns the parsed schema untouched (`PravahaNode.java:234-236`), the plugin is never given `event.time`, every row is stamped 0, and `QW` **emits nothing**. The node logs nothing about it. Record the elapsed time to be sure it is "nothing, ever" and not "nothing yet" |
| valid | `event_time` | `QW` delivers exactly the three canonical rows `(T0+0,T0+5,u0,5)`, `(…,u1,7)`, `(…,u2,3)` |
| invalid | `evnt_time` (misspelled) | **startup refused** with `PRV-2002` naming the column and listing the stream's actual columns (`PravahaNode.java:240-248`) |
| invalid | a column of type `STRING` (`usr`) | `hasField` passes, so **startup succeeds**. What happens at ingest is the finding: record whether `usr` is used as an event time, silently ignored, or fails per row |
| boundary | `"  event_time  "` | `strip()` is applied at `PravahaNode.java:239`, so it must behave identically to the unpadded value |
| boundary | `EVENT_TIME` (wrong case) | `hasField` — check whether it is case-sensitive. Whichever it is, it must be the same answer here and in SQL |
| wrong type | `event-time: 3` | coerced to `"3"`, no such column, `PRV-2002` |

**Vacuity:** the valid row cannot pass with the feature removed: the "unset" row of this same table
is the feature-removed control, and it emits zero rows against the same data and the same SQL.

## CFG-009 — `pravaha.streams.<n>.out-of-orderness`
**Intent:** The **working** lateness key — as opposed to `pravaha.watermark.out-of-orderness`, which
is not (fact 7). It is applied only inside `withEventTime`, after the early return, so it depends
on `event-time` being declared too.
**Falsifier:** changing this key does not change which windows close.
**Setup:** `base.yaml` with `event-time: event_time` held constant.
**Steps:** start; register `QW`; feed; read the view; count closed windows.
**Expected:**

| Variant | Value | Windows that close | Arithmetic |
|---|---|---|---|
| unset | absent | `[T0+0,T0+5)` and `[T0+5,T0+10)`? **No.** `StreamSchema.DEFAULT_OUT_OF_ORDERNESS = 10s`, so watermark `= (T0+11) − 10s = T0+1`, which is `< T0+5`. **Zero windows close** |
| valid | `2s` | watermark `= T0+11 − 2s = T0+9`; `T0+9 ≥ T0+5` and `< T0+10` → **one window**, the canonical three rows |
| valid | `0s` | watermark `= T0+11 − 0 = T0+11`; `≥ T0+10`, `< T0+15` → **two windows**: the canonical three rows plus `(T0+5,T0+10,u0,17)`, `(…,u1,8)`, `(…,u2,15)`. Six rows, totalling `15 + 40 = 55` |
| invalid | `-1s` | a negative lateness means a watermark ahead of the data. Record whether `StreamSchema.Builder.outOfOrderness` refuses it. **If it is accepted, that is a finding**: with `−1s` the watermark is `T0+12 ≥ T0+10`, so two windows close and the third does not, and nothing said the value was nonsense |
| boundary | `10s` (equal to the default) | must behave identically to unset. If it does not, one of the two paths is not the one it claims |
| boundary | `30s` | watermark `= T0+11 − 30s = T0−19` → zero windows close |
| wrong type | `out-of-orderness: fast` | Spring's `Duration` binder fails at context refresh naming the key |
| **interaction** | `2s` with `event-time` **removed** | `withEventTime` returns early at `PravahaNode.java:234-236`, so **`outOfOrderness` is never applied.** The key is silently inert in this configuration. See CFG-084 |

**Vacuity:** the three valid rows give three different window counts (0, 1, 2) from one data file
and one query. A build that ignored the key would give the same count three times.

## CFG-010 — `pravaha.sources.<n>.plugin`
**Intent:** The name a plugin reports for itself, matched case-insensitively against
`ServiceLoader`-discovered plugins (`PluginSourceFeeds.java:163-177`).
**Falsifier:** a valid plugin name binds nothing, or an invalid one starts a node that silently
reads no rows.
**Setup:** `base.yaml`.
**Steps:** start; read the `sources bound:` log line (`PravahaNode.java:397`); register `QW`.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | `txn: { options: {...} }` | `spec.plugin` is `null`. `SourceBindingProperties.toBindings` builds `SourceBinding("txn", null, opts)` with **no validation** (`SourceBindingProperties.java:62-68`), so the node **starts**. The failure arrives at the first registration, as `PRV-5090` with `'null'` in the message. **A binding with no plugin should be refused at startup; record this as a finding** |
| valid | `filesystem` | `sources bound: [...]` names `txn`; `QW` delivers the canonical result |
| valid | `FileSystem` | equalsIgnoreCase at `PluginSourceFeeds.java:166` → same as `filesystem` |
| invalid | `kafka` | `PRV-5090 INGEST_NO_SUCH_PLUGIN` **at first registration, not at startup**, listing the available plugins. Record the list: `filesystem, feedfile, jdbc, delta` must all appear |
| boundary | `""` | as unset: starts, fails at registration |
| wrong type | `plugin: [filesystem]` | binding failure at context refresh |

**Vacuity:** not stateful. The "valid" row is proved by rows arriving, not by the log line.

## CFG-011 — `pravaha.sources.<n>.options.<k>`
**Intent:** Passed to the plugin untouched (`application.yaml`'s comment; `SourceBinding.options`).
An unknown option must not be swallowed, and the `schema` option is the second place a schema is
written — which is the drift `withDeclaredEventTime` exists to prevent.
**Falsifier:** an option the plugin does not know is accepted silently, or the `schema` option and
`pravaha.streams.txn.schema` disagree without anyone saying so.
**Setup:** `base.yaml`.
**Steps:** start; register `QW`; read rows.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | `options: {}` | `filesystem` needs `path`; expect `PRV-5091 INGEST_BINDING_FAILED` at first registration naming the missing setting (`PluginSourceFeeds.java:139-145`), with `PRV-5001 PLUGIN_MISSING_SETTING` as the cause |
| valid | `path` + `schema` as in `base.yaml` | canonical result |
| valid | `event.time: event_time` set explicitly | `withDeclaredEventTime` sees it already present and leaves it alone (`PravahaNode.java:273`). Identical result to not setting it |
| invalid | `path: /no/such/file.csv` | `PRV-5091` at registration, naming the path |
| **disagreement** | binding `schema: "id:INT64"` while `pravaha.streams.txn.schema` has four columns | **two schemas for one stream.** Record what happens: the catalog plans against four columns and the plugin decodes one. Whatever the outcome, nothing at startup compares the two, and that is the finding |
| boundary | an option key with a `.` in it (`event.time`) | must survive Spring's relaxed binding as the literal string `event.time`, not `eventTime` or `event-time` |
| wrong type | `options: "path=/x"` | binding failure at context refresh |

### Security

## CFG-012 — `pravaha.security.authentication`
**Intent:** One of the three ways to close this server, and the one with **no validation at all**:
`authenticates()` is a single `equalsIgnoreCase("token")` (fact 5).
**Falsifier:** a misspelled value produces an authenticating server, or an unauthenticating one with
no complaint.
**Setup:** `base.yaml` with `allow-anonymous: true` so the node can start under `none`.
**Steps:** start; read the `security: authentication=…` log line (`PravahaNode.java:342-350`);
call Flight with and without a bearer token; call `GET /api/v1/streams` with and without one.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `none` (`SecurityProperties.java:50`) |
| valid | `token` with `tokens` configured | log says `authentication=token`; `BearerTokenFilter` is registered (`PravahaServerApplication.java:65-81`); an unauthenticated HTTP call gets `PRV-7001`; an unauthenticated Flight call gets `PRV-7001` from `PrincipalMiddleware.java:96` |
| valid | `TOKEN` | `equalsIgnoreCase` → identical to `token` |
| **invalid** | `tokens` (plural — the sub-key's name) | **silently means `none`.** The node starts, the log says `authentication=none`, the filter is not registered, and `refuseAccidentalOpenServer` then either refuses (if `allow-anonymous` is false) or opens the server. A one-character typo in the most security-relevant key in the file is accepted without a word. **Finding** |
| invalid | `basic`, `mtls`, `oauth` | same: silently `none` |
| boundary | `"token "` (trailing space) | `equalsIgnoreCase` does **not** strip. Silently `none`. Compare with `getPolicy()`, which *does* `trim()` (`PravahaNode.java:283`) — two adjacent keys in one block with different whitespace rules |
| wrong type | `authentication: true` | coerced to `"true"` → silently `none` |

**Vacuity:** each row is proved by making an unauthenticated call and seeing whether it is served,
never by reading the log line.

## CFG-013 — `pravaha.security.policy`
**Intent:** The second of the three ways to close the server, and the one whose own javadoc
disagrees with its own code (fact 4).
**Falsifier:** a value the code accepts is not one the documentation names, or vice versa.
**Setup:** `base.yaml`.
**Steps:** start; if it starts, register a query as one principal and read it as another.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `permissive` (`SecurityProperties.java:52`) |
| valid | `permissive` | `SecurityPolicy.PERMISSIVE`; every caller sees every view |
| valid | `authenticated` | `AuthenticatedOnlyPolicy`; with `authentication: token` an anonymous Flight caller gets nothing and a verified one does |
| valid | `authenticated-only` | accepted as a synonym (`PravahaNode.java:287`). Must behave identically to `authenticated` |
| valid | `AUTHENTICATED`, `  authenticated  ` | `trim()` then `toLowerCase(ROOT)` (`PravahaNode.java:283-285`) → identical |
| **invalid** | `tenant` — **the value `SecurityProperties.java:52` documents** | `PRV-7002` at startup: *"pravaha.security.policy is 'tenant', which is not a policy this node knows."* **The javadoc on the field is wrong; raise it** |
| invalid | `strict` | `PRV-7002`, message listing `permissive` and `authenticated` |
| boundary | `""` | `trim()` gives `""`, which matches no case → `PRV-7002`. Note the message renders an empty quoted string |
| boundary | `null` in YAML (`policy:` with nothing after it) | `getPolicy()` returns null → the `== null` branch defaults to `permissive` (`PravahaNode.java:282-284`) — **a different answer from `""`**, from two spellings of "nothing" |
| wrong type | `policy: [permissive]` | binding failure at context refresh |

## CFG-014 — `pravaha.security.audit`
**Intent:** Whether authorization decisions are recorded. `auditSink()` validates, unlike
`authentication`.
**Falsifier:** `memory` records nothing, or an unknown value starts the node.
**Setup:** `base.yaml` with `authentication: token`, `policy: authenticated`, one token.
**Steps:** start; make one authorized read and one refused read; inspect the sink.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `none` (`SecurityProperties.java:56`) → `AuditSink.NONE` |
| valid | `none` | no records |
| valid | `memory` | `AuditSink.InMemory`; **both** the allowed and the refused decision recorded, each naming the principal and the view. A sink that records only refusals is a finding |
| valid | `MEMORY`, `  memory  ` | `trim()` + `toLowerCase` → identical |
| **invalid** | `log` — **the value `SecurityProperties.java:55` documents** | `PRV-7002`: *"pravaha.security.audit is 'log'; use 'none' or 'memory'."* **The javadoc names a third value the code does not have; raise it** |
| boundary | `""` | matches nothing → `PRV-7002` |
| boundary | `audit:` (null) | `== null` → `none` |
| wrong type | `audit: 1` | `"1"` → `PRV-7002` |

**Note for the executor:** `auditSink()` is called **twice** in `start()` — once at line 340 and
again at line 427 — and the `memory` case caches into the `audit` field
(`PravahaNode.java:307,315`). Check that the registry and the Flight server share **one** sink, not
two. Two sinks means half the decisions are recorded in a sink nobody reads.

## CFG-015 — `pravaha.security.allow-anonymous`
**Intent:** The third way to close the server, by acknowledging that it is open. Deliberately
awkward to set by accident.
**Falsifier:** a node with `authentication: none` and `policy: permissive` starts without it.
**Setup:** `base.yaml` with `authentication: none`, `policy: permissive`.
**Steps:** start; observe.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `false` (`SecurityProperties.java:65`, a plain `boolean` field) → **startup refused** with `PRV-7002` and the full three-option message at `PravahaNode.java:165-173` |
| valid | `true` | node starts; every view served to every caller |
| valid | `false` explicitly | identical to unset: refused |
| boundary | `yes` / `on` / `1` | Spring's boolean binder. Record which are accepted — `ConfigParsers` accepts all three and a reader of `application.yaml` has no way to know two parsers are involved |
| wrong type | `allow-anonymous: "sure"` | binding failure at context refresh. **Confirm it is a failure and not a silent `false`** — a silent `false` is safe here, but a silent `true` would not be, and the same coercion governs both |

**Vacuity:** the unset row is the control for the entire security section: it proves the refusal is
live, so a later case that starts successfully proves something was configured rather than that the
guard is absent.

## CFG-016 — `pravaha.security.tokens.<token>` — the map key is the credential
**Intent:** The map key **is the bearer token**. That is unusual enough to be worth its own case:
the secret is a YAML key, so it appears in any diff, any `kubectl describe`, and any log of the
bound properties.
**Falsifier:** a token that is configured is not accepted, or the token value appears in a log or an
API response.
**Setup:** `base.yaml` with `authentication: token` and one token `tok-aaaaaaaaaaaaaaaaaaaaaaaa`.
**Steps:** start; grep the whole startup log for the token string; `GET /actuator/env` if exposed;
authenticate with it.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | `tokens: {}` or absent | `verifier()` returns `TokenVerifier.rejectAll()` (`SecurityProperties.java:121-130`) — **every call is refused**, deliberately, so the state is visible at the first call rather than in a support ticket. Confirm the startup log makes this discoverable |
| valid | one token | that token authenticates; a different string gets `PRV-7001` |
| valid | three tokens | `StaticTokenVerifier.of(...).and(...)` chain (`SecurityProperties.java:131-143`); **all three** authenticate. A chain that only honours the first or the last is the failure this row exists for |
| boundary | a 1-character token `x` | accepted. No minimum length is enforced anywhere; note it |
| boundary | two identical token strings | impossible in YAML (duplicate map key). Record which of the two Spring keeps |
| **disclosure** | any | the token string must **not** appear in the startup log, in `/api/v1/status`, or in `/actuator/env`. `management.endpoints.web.exposure.include` does not list `env` (`application.yaml`), so `/actuator/env` should 404 — confirm it |
| wrong type | `tokens: [a, b]` | binding failure at context refresh |

## CFG-017 — `pravaha.security.tokens.<t>.id`
**Intent:** The principal id the credential stands for — the string that ends up in audit records,
in journal entries as the query's owner, and in `PRV-7002` messages
(`QueryRegistry.java:289`).
**Falsifier:** the id does not reach the audit sink, the journal, or the refusal message.
**Setup:** `base.yaml`, `authentication: token`, `audit: memory`, one token.
**Steps:** authenticate; register a query; read the journal; force a refusal.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | **the map key is used as the id** (`SecurityProperties.java:134-136`) — that is, *the bearer token becomes the principal id*, and therefore lands in the audit sink and in the journal on disk. **That is credential material in two durable places. Finding** |
| valid | `ann` | `ann` in the audit record, in the journal entry's owner field, and in any `PRV-7002` message |
| boundary | `""` | `spec.getId()` is `""`, not null, so the `== null` fallback does not fire → an empty principal id. `principalNamed("")` on recovery returns `Optional.empty()` (`PravahaNode.java:491-495`), so **a query registered by this principal is refused on replay**. Pin that chain |
| boundary | a 512-character id | accepted; must not be truncated in the journal |
| wrong type | `id: 7` | `"7"` |

## CFG-018 — `pravaha.security.tokens.<t>.tenant`
**Intent:** The tenant a principal belongs to — the dimension `SERVING_TENANT_QUOTA_EXCEEDED`
(`PRV-4028`) and any tenant-scoped policy would be judged on.
**Falsifier:** the tenant is not carried onto the `Principal`, or its default is not what the field
says.
**Setup / Steps:** as CFG-017.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `"public"` (`SecurityProperties.java:150`), **not null** |
| valid | `acme` | `Principal.tenant() == "acme"`, visible in the audit record |
| boundary | `""` | empty tenant, accepted, no complaint. Record what a policy does with it |
| boundary | two tokens with different tenants | both work; the two principals differ only in tenant |
| wrong type | `tenant: 1` | `"1"` |
| **coverage gap** | any | with `policy: permissive` or `authenticated`, **nothing in this engine reads `tenant`** — `AuthenticatedOnlyPolicy` checks only that a principal exists. The key is carried and unused; say so |

## CFG-019 — `pravaha.security.tokens.<t>.roles`
**Intent:** The role set on the `Principal`. Bound through `setRoles(List<String>)` into a
`LinkedHashSet` (`SecurityProperties.java:173-175`).
**Falsifier:** roles are dropped, reordered in a way that matters, or deduplicated silently when
duplication was meaningful.
**Setup / Steps:** as CFG-017.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | empty set, **not null** (`SecurityProperties.java:151`) |
| valid | `[reader]` | `Principal.roles() == {reader}` |
| valid | `[reader, writer, admin]` | all three, in that iteration order |
| boundary | `[reader, reader]` | deduplicated to one by the `Set`. Silent; note it |
| boundary | `[]` | empty set |
| wrong type | `roles: reader` (a scalar) | Spring's relaxed binder may accept a single value as a one-element list. Record which |
| **coverage gap** | any | as CFG-018: no shipped policy reads `roles`. The mechanism exists for a `SecurityPolicy` implementation that does not ship |

### Persistence

## CFG-020 — `pravaha.registry.journal`
**Intent:** Whether registered continuous queries survive a restart. Unset is the default and the
node warns (`PravahaNode.java:412-415`).
**Falsifier:** a configured journal does not replay, or an unset one loses queries without saying
so.
**Setup:** `base.yaml`.
**Steps:** start; register `QW`; stop; start again; `pravaha queries`.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | `""` | `pathOf` gives `Optional.empty()` (`PersistenceProperties.java:82-84`); WARN line *"pravaha.registry.journal is not set, so registered queries live only in memory and a restart will lose them without saying so"*; after restart `pravaha queries` returns **0** queries |
| valid | `$QA/journal` | file created; after restart the log says `registry recovered 1 of 1 queries from $QA/journal` and `pravaha queries` returns **1** |
| invalid | a path in a directory that does not exist | expect `PRV-8006 REGISTRY_JOURNAL_UNWRITABLE` (`RegistryJournal.java:229,276`). Record **when**: at startup, or at the first registration. Startup is the right answer |
| invalid | an **unwritable** directory (`chmod 500`) | `PRV-8006`. `OPERATIONS.md` states a registration whose journal append fails is **refused**, because acknowledging one that will not survive a restart tells the client something untrue. Prove that: the `register` call must fail, and `pravaha queries` must then show **0**, not 1 |
| invalid | a path that is an existing **directory** | record the code; a bare `IOException` here is a finding |
| boundary | a corrupt journal — last record truncated mid-append | replay keeps everything before it and ignores the tail; `recovered n of n`, no refusals |
| boundary | a journal written by a newer version | `PRV-8005 REGISTRY_JOURNAL_UNREADABLE`, refused and not skipped |
| wrong type | `journal: 7` | `"7"`, a relative path in the working directory. Accepted. Note it |

**Vacuity:** the unset row loses the query and the valid row recovers it, from one registration and
one restart. A build with journalling removed would show `0` in both.

## CFG-021 — `pravaha.checkpoint.directory`
**Intent:** Whether a restart recovers answers as well as questions. Unset is the default and the
node warns (`PravahaNode.java:358-365`).
**Falsifier:** with a directory set, a restart comes back with an empty view; or with it unset,
nothing says so.
**Setup:** `base.yaml` with `pravaha.registry.journal: $QA/journal` so the query itself survives,
and `pravaha.checkpoint.interval: 2s` so a checkpoint exists within the test.
**Steps:** start; register `QW`; feed; wait for the canonical result; wait `2s + 2s`; stop; restart;
read the view **before** feeding anything.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | `""` | WARN *"pravaha.checkpoint.directory is not set, so registered queries keep no checkpoints…"*; after restart the view holds **0 rows**; the query exists |
| valid | `$QA/ckpt` | INFO `checkpointing registered queries under $QA/ckpt`; a per-query subdirectory appears beneath it (**one shared store would make pruning global** — `application.yaml`); after restart the view holds the **three canonical rows** with `u0=5, u1=7, u2=3` before any row is fed |
| invalid | an unwritable directory | expect a failure naming the directory. Record whether it is at startup or at the first checkpoint — **the first checkpoint is 1 minute after registration by default, so a startup failure is the only one an operator will see** |
| invalid | a path that is an existing **file** | record the code |
| boundary | a directory that does not exist but whose parent does | must be created. Confirm |
| boundary | the **same** directory as `pravaha.registry.journal`'s parent | both must coexist |
| wrong type | `directory: true` | `"true"`, a relative path. Accepted |

**Vacuity:** the case reads the view **before feeding**, so a pass cannot come from the source
replaying the file. The unset row is the feature-removed control and returns 0 rows.

## CFG-022 — `pravaha.checkpoint.interval`
**Intent:** How often state is written down, and the key whose two duration dialects already caused
one shipped bug (`PersistenceProperties.java:69-74`).
**Falsifier:** the configured interval is not the interval observed, or an ISO-8601 value reaches
the engine's parser.
**Setup:** `base.yaml` with `checkpoint.directory: $QA/ckpt`.
**Steps:** start; register `QW`; count files under the per-query checkpoint directory over 20 s;
read the `checkpointing every Nms, keeping the newest K` log line
(`PeriodicCheckpointer.java:136`).
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `Duration.ofMinutes(1)` (`PersistenceProperties.java:104`), and `PeriodicCheckpointer.DEFAULT_INTERVAL` is also `1m` — the two defaults must agree, and they do |
| valid | `2s` | log says `checkpointing every 2000ms`; over 20 s expect `20 / 2 = 10` checkpoints taken, of which `keep` are retained. **The first is one interval away, not immediate** (`PeriodicCheckpointer.java:130-135`), so expect 9 or 10, never 11 |
| valid | `PT2S` (ISO-8601) | Spring's binder accepts it; `PersistenceProperties` converts via `toNanos()`, so the engine sees `2000000000ns` and never sees `PT2S`. **This is the fix for the shipped bug — confirm it holds**, because writing `Duration.toString()` here would reproduce it |
| invalid | `2` (bare number) | Spring's `Duration` binder treats a bare number as **milliseconds** by default. A `2 ms` checkpoint interval is a node that does nothing but checkpoint. `ConfigParsers.parseDuration` would have **refused** the same text (`ConfigParsers.java:67-71`). Two dialects, opposite answers, one file. **Finding** |
| invalid | `0s` | `scheduleWithFixedDelay(0, 0)` — a tight loop. Nothing refuses it. **Finding** |
| boundary | `-1s` | record whether Spring binds it and what the scheduler does |
| boundary | `24h` | accepted; no checkpoint taken during any realistic test |
| wrong type | `interval: soon` | binding failure at context refresh naming the key |

**Vacuity:** counting files over a fixed 20 s window distinguishes `2s` from `1m` by count
(`≈10` vs `0`), not by the log line.

## CFG-023 — `pravaha.checkpoint.keep`
**Intent:** How many checkpoints survive pruning. Counted, not timed, and deliberately
(`application.yaml`). The validation lives in `PeriodicCheckpointer`, which is constructed **per
registration**, not at startup (fact 10).
**Falsifier:** `keep: 0` starts a node; or `keep: 3` leaves a number other than 3 on disk.
**Setup:** `base.yaml`, `checkpoint.directory: $QA/ckpt`, `checkpoint.interval: 2s`.
**Steps:** start; register `QW`; wait 20 s; `ls` the per-query directory and count.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `3` (`PersistenceProperties.java:105`), agreeing with `PeriodicCheckpointer.DEFAULT_KEEP = 3` |
| valid | `1` | exactly **1** file after 20 s. `20 / 2 = 10` taken, `10 − 1 = 9` pruned |
| valid | `5` | exactly **5** files |
| **invalid** | `0` | `PeriodicCheckpointer` throws `IllegalArgumentException` *"at least one checkpoint must be kept, asked to keep 0"* (`PeriodicCheckpointer.java:93-95`) — **at the first registration, not at startup.** The node starts, reports healthy, logs `checkpointing registered queries under …`, and then fails every registration. The brief's rule applies: this must be refused at startup. **Finding** |
| invalid | `-1` | same path, same finding |
| boundary | `2147483647` | binds (`int`); nothing is ever pruned; disk grows without bound. `TROUBLESHOOTING.md` already names checkpoint files as *the* known disk-growth path — this is the configuration that guarantees it |
| boundary | `2147483648` | exceeds `int`; binding failure at context refresh |
| wrong type | `keep: three` | binding failure at context refresh |

**Vacuity:** the `1` and `5` rows produce different file counts from identical timing. A build that
never pruned would show ~10 in both.

## CFG-024 — `pravaha.checkpoint.timeout` — is it *enforced*?
**CORRECTED 2026-09-19 (CFG-17).** This case used to assert that the key had a reader and no
writer, and that `PersistenceProperties.Checkpoint` had no `timeout` field so the key was not even
bound. **Both halves are false**, and were checked against the code rather than re-run: the field
exists with a 30 s default (`PersistenceProperties.java`), `checkpointConfiguration()` emits
`pravaha.checkpoint.timeout` in nanoseconds beside `interval` and `keep`, and
`PeriodicCheckpointer.from` reads it. Assumed fact 9 is withdrawn.
`PersistencePropertiesTest.theCheckpointTimeoutIsBoundForwardedAndReadable_CFG17` now pins all
three, so the case cannot go stale in the other direction either.

**Intent (rewritten):** whether the configured timeout is *enforced* — a bound that is bound,
forwarded and read is not yet a bound that fires.
**Falsifier:** a checkpoint that takes longer than the configured timeout completes anyway.
**Setup:** `base.yaml`, `checkpoint.directory: $QA/ckpt`, `checkpoint.interval: 2s`,
`checkpoint.timeout: 1ms`.
**Steps:** register a query whose state is **large enough that one checkpoint genuinely exceeds the
bound** — a twelve-row view checkpoints well inside a millisecond, which is why the previous run
produced three checkpoint files, zero timeout-related log lines, and no evidence either way. Feed
until the state is of the order of hundreds of megabytes, then watch
`pravaha_query_checkpoint_failures_total` and the log.
**Expected:** the checkpoint is abandoned and counted, rather than running to completion past its
own bound.

**Vacuity:** a run whose state checkpoints inside the timeout proves nothing. Record the state size
and the measured checkpoint duration alongside the result, or the case has not been executed.

### Watermarks

## CFG-025 — `pravaha.watermark.out-of-orderness` — the inert key
**Intent:** The single most-documented configuration key in this repository with **no reader
anywhere**. `application.yaml` describes it in eight lines; `OPERATIONS.md:222` puts it in a YAML
block; `CONCEPTS.md:66` tells the reader to move it; `StreamSchema.java:51` names it in javadoc.
`grep -rn "pravaha.watermark.out-of-orderness"` finds those four, and no `@Value`, no
`Configuration.getDuration`, no `@ConfigurationProperties` field.
**Falsifier:** setting this key changes which windows close.
**Setup:** `base.yaml` with `pravaha.streams.txn.out-of-orderness` **removed**, so the stream falls
back to `StreamSchema.DEFAULT_OUT_OF_ORDERNESS = 10s`.
**Steps:** three runs, identical except for this key: absent, `0s`, `30s`. In each: start, register
`QW`, feed `txnA.csv`, wait 15 s, read the view.
**Expected:** **all three runs return exactly the same thing — zero rows.**
- With `10s` lateness the watermark reaches `T0+11 − 10s = T0+1`, and `T0+1 < T0+5`, so **no window
  closes** in any of the three runs.
- `0s` would give watermark `T0+11` and close **two** windows (six rows, `15 + 40 = 55`) if the key
  were read. It does not.
- `30s` would give `T0−19` and close none — indistinguishable from the default, which is why the
  `0s` run is the one that carries the case.
**Vacuity:** the `0s` run is the whole case. A key that were live would change 0 rows into 6. If all
three runs give 0 rows, the key is inert; if the `0s` run gives 6, the key is live and fact 7 is
wrong. Either outcome is a result.
**Cross-reference:** CFG-009 shows `pravaha.streams.txn.out-of-orderness: 0s` producing exactly
those 6 rows on the same data. **The two keys differ only in tree position, and one of them works.**
Run CFG-009's `0s` row and CFG-025's `0s` run back to back, in that order, in one sitting; the pair
is the evidence.

## CFG-026 — `pravaha.watermark.idle-after`
**Intent:** Partition quiet time, refused rather than clamped, and validated **at startup** by
constructing a throwaway `WatermarkTracker` (fact 8) — one bad value being one startup failure
rather than every registration failing separately.
**Falsifier:** a value outside `[1s, 10m]` starts the node.
**Setup:** `base.yaml`.
**Steps:** start; read the `watermarks: idle-after=…, tick=…` line (`PravahaNode.java:388`).
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `30s` (`PravahaNode.java:120`), agreeing with `application.yaml` and with `QueryExecution.DEFAULT_IDLE_AFTER` |
| valid | `5s` | starts; log says `idle-after=PT5S` |
| invalid | `500ms` | **startup refused** with `PRV-2002` quoting the tracker's own message and adding *"Left as configured, every registration on this node would fail and the node would look healthy."* (`PravahaNode.java:380-386`) |
| invalid | `1h` | **startup refused**, same path. This is the value `PravahaNode.java:373-375` records as having started a node that recovered 0 of 3 queries and reported healthy |
| boundary | `1s` | exactly `MINIMUM_IDLE_TIMEOUT` → **accepted** (the bound is inclusive) |
| boundary | `999ms` | refused |
| boundary | `10m` | exactly `MAXIMUM_IDLE_TIMEOUT` → **accepted** |
| boundary | `10m1s` | refused |
| boundary | `0s` | refused |
| wrong type | `idle-after: soon` | binding failure at context refresh; the `@Value` is a `Duration` so this fails before `PravahaNode` is constructed. **The message will name the constructor argument, not the key — record how legible it is** |

**Vacuity:** the `1s`/`999ms` and `10m`/`10m1s` pairs straddle the bound in each direction, so a
build that clamped instead of refusing would start on all four.

## CFG-027 — `pravaha.watermark.tick`
**Intent:** How often event time advances — and the key that makes idleness detectable at all. Its
constraint (`tick <= idle-after`) is checked **per registration**, not at startup (fact 8), which is
the asymmetry this case exists to pin.
**Falsifier:** a tick coarser than the idle timeout starts a node that then fails every
registration.
**Setup:** `base.yaml`.
**Steps:** start; register `QW`; observe.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `1s` (`PravahaNode.java:121`), agreeing with `application.yaml` and `QueryExecution.DEFAULT_TICK` |
| valid | `200ms` | starts; `QW` registers; canonical result |
| **invalid** | `60s` with `idle-after: 30s` | **node starts**, logs `watermarks: idle-after=PT30S, tick=PT1M` as though it were in force, and then **every registration fails** from `QueryExecution.generatingWatermarks` (`QueryExecution.java:339`). Compare with CFG-026, where the neighbouring key in the same block is refused at startup for the same class of error. **Finding: one of these two keys is validated in the right place and the other is not** |
| boundary | `tick == idle-after` (`30s` and `30s`) | `<=` → accepted. Confirm registrations succeed |
| boundary | `0s` | record: a zero tick is a tight loop on the `pravaha-watermark` daemon thread. Nothing refuses it at startup |
| boundary | `1ns` | as above |
| wrong type | `tick: fast` | binding failure at context refresh |

**Vacuity:** the invalid row is proved by a **registration failing on a node that started and
reports healthy** — not by a log line, which reads correct.

### Cluster

## CFG-028 — `pravaha.cluster.mode`
**Intent:** What is being asked of the cluster — a correctness question — checked against the
mechanism's guarantees before anything else in `start()` (fact 3).
**Falsifier:** `PARTITIONED` on a mechanism without consensus starts.
**Setup:** `base.yaml`.
**Steps:** start; read `cluster mode X on <guarantee>` (`CoordinatorFactory.describe`).
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `SINGLE` (`PravahaNode.java:126`), agreeing with `application.yaml` |
| valid | `SINGLE` with `mechanism: single` | starts |
| valid | `REPLICATED` with `mechanism: socket` + peers | starts (peers cannot be supplied — see CFG-030; expect `PRV-9005`) |
| valid | `replicated` (lower case) | `toUpperCase(ROOT)` at `CoordinatorFactory.java:106` → accepted |
| valid | `  SINGLE  ` | `strip()` → accepted |
| invalid | `HA` — the value `system_design.md` §27.1 documents | `PRV-9001`: *"'HA' is not a cluster mode; one of [SINGLE, REPLICATED, PARTITIONED]"*. The design document's `EMBEDDED \| SINGLE \| HA` is three names, none of which but `SINGLE` exists. **The documentation half is closed** (CFG-18): that document's own header now names `mode: HA` in its list of things it describes and the tree does not have — it is the record of intent, not of the build |
| valid | `PARTITIONED` with `mechanism: single` | **CORRECTED 2026-09-19 (CFG-18): the coordinator starts.** This cell predicted `PRV-9002`, which does not fire and should not: `single` genuinely excludes split-brain because there is no second node, which is what `OPERATIONS.md`'s own mechanism table says. The guarantee check is working — the row below is where it fires. A **node** then refuses to serve the mode for a different reason (`PRV-9002` from `PravahaNode.refusePartitionedServing`, S-3): nothing in a node consumes partition ownership yet. Two refusals in two places, and only the second occurs here |
| invalid | `PARTITIONED` with `mechanism: socket` | `PRV-9002` — `socket` cannot exclude split-brain |
| boundary | `""` | `""` is not a `ClusterMode` → `PRV-9001`. Note that the message quotes the uppercased empty string |
| wrong type | `mode: 1` | `"1"` → `PRV-9001` |

**Vacuity:** the `PARTITIONED`/`socket` row is the one that matters, and it is falsified by the
factory building a coordinator at all. (This said `PARTITIONED`/`single`, falsified by "the node
starting" — which is what that combination correctly does at the factory, so the case could only
ever fail. CFG-18.)

## CFG-029 — `pravaha.cluster.mechanism`
**Intent:** Which coordinator provides it — an operational question — resolved through
`ServiceLoader` with the two built-ins registered first so a broken third-party provider cannot
displace them (`CoordinatorFactory.java:38-46`).
**Falsifier:** an unknown mechanism starts the node, or a known one is not found.
**Setup:** `base.yaml`.
**Steps:** start; read the membership line.
**Expected:**

| Variant | Value | Expected |
|---|---|---|
| unset | absent | `single` (`PravahaNode.java:127`) |
| valid | `single` | starts; guarantees exclude split-brain trivially |
| valid | `socket` | requires `pravaha.cluster.socket.peers`, which cannot be delivered (CFG-030) → `PRV-9005` |
| invalid | `zookeeper` **without** `plugins/pravaha-cluster-zookeeper` on the classpath | `PRV-9001`: *"no cluster coordinator called 'zookeeper' is on the classpath. Available: [single, socket]"* |
| valid | `SOCKET` (upper case) | **CORRECTED 2026-09-19 (CFG-18): accepted.** The lookup was a plain map lookup with no case folding while `mode` **was** upper-cased, so two adjacent keys had two case rules and `SOCKET` answered "no cluster coordinator called 'SOCKET' is on the classpath" beside an `Available: [single, socket]` list that appears to contradict it. `CoordinatorFactory.providerNamed` now matches exactly first and then ignoring case, so `SOCKET` reaches the socket provider and fails on its missing peer list (`PRV-9005`, CFG-030) — the same answer lower-case `socket` gives |
| boundary | `"  socket  "` | `.strip()` at `CoordinatorFactory.java:59` → accepted |
| boundary | `""` | `PRV-9001` listing the available mechanisms |
| wrong type | `mechanism: 1` | `"1"` → `PRV-9001` |

## CFG-030 — `pravaha.cluster.socket.peers`
**Intent:** The peer list the socket coordinator needs — documented in `OPERATIONS.md:99-107` and
**not forwarded into the coordinator's `Configuration`** (fact 11).
**Falsifier:** following `OPERATIONS.md` verbatim produces a working socket cluster.
**Setup:** exactly the YAML block at `OPERATIONS.md:99-107`, adapted to loopback:
```yaml
pravaha:
  cluster:
    mode: REPLICATED
    mechanism: socket
    socket:
      peers: "a=127.0.0.1:19070,b=127.0.0.1:19071"
      heartbeat.millis: 1000
      timeout.millis: 5000
```
**Steps:** start.
**Expected:**
- `PravahaNode` builds the coordinator `Configuration` from `mode` and `mechanism` only
  (`PravahaNode.java:144-147`).
- `SocketProvider.create` calls `configuration.getString("pravaha.cluster.socket.peers")` on a
  two-key `Configuration`, finds nothing, and throws **`PRV-9005 CLUSTER_BAD_MEMBERSHIP`**:
  *"the socket coordinator needs pravaha.cluster.socket.peers, as …"* (`SocketProvider.java:54-58`).
- **Startup fails, and the message blames the operator for omitting a key they supplied.**
- Prove the key was supplied by confirming Spring bound it: the node's own environment has it (check
  via a debug-bound-properties run or by adding a temporary echo), while the coordinator's
  `Configuration` does not.
**Vacuity:** not stateful. The case is falsified by the node starting — which would mean the
forwarding exists and fact 11 is wrong.

## CFG-031 — `pravaha.cluster.socket.heartbeat.millis`
**Intent:** Read by `SocketProvider.java:80` with a default of `1000`. Unreachable for the same
reason as CFG-030, and worth its own case because it has a **default**, so its inertness cannot be
seen as a failure — only as a value that never changes.
**Falsifier:** setting it to `50` or `60000` changes observed heartbeat behaviour.
**Setup:** as CFG-030, but with the peers supplied by a route that works, if any exists (record
whether one does: a `-Dpravaha.cluster.socket.peers=` system property does **not** reach the
coordinator either, because the `Configuration` is built from two literals).
**Expected:**
- unset → `1000 ms`; valid `50` → no observable change; invalid `abc` → would be `PRV-1021` if it
  were read, and is not read; boundary `0` → likewise.
- **Every variant is indistinguishable, because the key never reaches the reader.** That is the
  case's finding, and it is the same finding as CFG-030 with the additional detail that a default
  hides it.

## CFG-032 — `pravaha.cluster.socket.timeout.millis`
**Intent / Falsifier / Expected:** identical in shape to CFG-031, default `5000`
(`SocketProvider.java:81`). Run it separately so the finding is recorded per key rather than as
"and the other one too" — a defect list that says "three cluster keys" is harder to close than one
that names them.
**Falsifier:** setting it to `50` or `60000` changes the socket coordinator's observed peer timeout.
**Expected:** every variant indistinguishable, because `PravahaNode.java:144-147` never forwards the key. Default `5000 ms` is always in force. Same finding as CFG-030, recorded against this key by name.

## CFG-033 — `pravaha.cluster.zookeeper.connect`
**Intent:** Required by `ZooKeeperProvider.java:61-65`, with no default, and unreachable for the
same reason as CFG-030.
**Falsifier:** with `plugins/pravaha-cluster-zookeeper` on the classpath and `connect` configured,
the node reaches a ZooKeeper ensemble.
**Setup:** the zookeeper plugin jar on the classpath; `mechanism: zookeeper`;
`pravaha.cluster.zookeeper.connect: 127.0.0.1:2181`.
**Steps:** start.
**Expected:** `PRV-9005` from `ZooKeeperProvider.java:63` — *"the ZooKeeper coordinator needs
pravaha.cluster.zookeeper.connect"* — despite the key being present in the file. `OPERATIONS.md:113`
tells an operator to use `zookeeper` for production `PARTITIONED`; **production `PARTITIONED` is
therefore unreachable from configuration.** That is the strongest form of this defect and should be
raised at that severity.

## CFG-034 — `pravaha.cluster.zookeeper.root`
**Intent:** Default `/pravaha` (`ZooKeeperProvider.java:66`). Same inertness as CFG-031.
**Falsifier:** setting it to `/other` changes the ZooKeeper path this node uses.
**Expected:** every variant indistinguishable; record as one finding with CFG-033.

## CFG-035 — `pravaha.cluster.zookeeper.session.timeout.millis`
**Intent:** Default `15_000`, cast to `int` (`ZooKeeperProvider.java:67`). Same inertness.
**Boundary worth recording even though unreachable:** `getLong` returns a `long` and the cast to
`int` is unchecked, so a value above `2147483647` would wrap silently **if the key ever became
readable**. Note it against the reader, not the key.
**Falsifier:** setting it to `60000` changes the session timeout the coordinator asks ZooKeeper for.
**Expected:** every variant indistinguishable; default `15_000 ms` always in force.

## CFG-036 — `pravaha.cluster.zookeeper.connect.timeout.millis`
**Intent:** Default `10_000`, same unchecked `long`→`int` cast (`ZooKeeperProvider.java:68`). Same
inertness, same latent narrowing.

### The Spring half of the same file
**Falsifier:** setting it to `1000` changes how long the coordinator waits to connect.
**Expected:** every variant indistinguishable; default `10_000 ms` always in force.

## CFG-037 — `server.port`
**Intent:** The HTTP surface every operator, the console and every `/api/v1` example uses.
**Falsifier:** the node serves HTTP on a port other than the configured one.
**Expected:** unset → `8080` from `application.yaml` (**there is no annotation default behind this
one — deleting the line really does unset it, unlike every `pravaha.*` key in fact 1**); valid
`18800` → served; invalid `70000` → bind failure; boundary `0` → ephemeral, and
`/api/v1/status` must report the actual port; wrong type `"http"` → context refresh fails.

## CFG-038 — `server.shutdown`
**Intent:** `graceful` is what lets lanes drain before the HTTP surface stops accepting; the
ordering `PravahaServerApplication` builds with `SmartLifecycle` depends on it.
**Falsifier:** with `graceful`, an in-flight request is cut off at shutdown.
**Expected:** unset → `immediate` (Spring's default) — **note that `application.yaml` sets
`graceful` explicitly, so an operator who trims the file silently loses it**; valid `graceful` → an
in-flight `GET /api/v1/queries` completes during `SIGTERM`; invalid `polite` → context refresh
fails; `PravahaNode.shutdownTimeout()` returns 30 s (`PravahaNode.java:510-512`) — check whether
anything actually reads it, because `spring.lifecycle.timeout-per-shutdown-phase` is the key Spring
honours and **it is not in this file**.

## CFG-039 — `spring.application.name`
**Intent:** Appears in metric tags and logs. Low risk, enumerated for completeness.
**Falsifier:** the configured name appears in no metric tag and in no `/actuator/info` field.
**Expected:** unset → absent; valid `pravaha` → present in `/actuator/info` and metric common tags;
boundary `""` → accepted; wrong type `[]` → context refresh fails.

## CFG-040 — `spring.threads.virtual.enabled`
**Intent:** `application.yaml` states that lane threads are created by the engine's own pinning
factory and are unaffected. That claim is the case.
**Falsifier:** a lane thread appears as a virtual thread, or a request-handling thread does not.
**Steps:** start with `true` and with `false`; take a thread dump; classify threads named
`pravaha-lane-*`, `pravaha-watermark`, `pravaha-checkpointer`, `pravaha-feed-*` and the servlet
pool.
**Expected:** with `true`, servlet request threads are virtual and **every** `pravaha-*` thread is a
platform thread. With `false`, both are platform. **A `pravaha-lane-*` virtual thread would falsify
`application.yaml`'s comment and is the reason for the case** — a pinned spin-wait strategy on a
virtual thread starves its carrier.

## CFG-041 — `spring.mvc.problemdetails.enabled`
**Intent:** Off deliberately: every non-2xx response is an `ApiError` and nothing else, because a
client that has to parse two error shapes will handle one of them badly.
**Falsifier:** any non-2xx response from `/api/v1/**` is an RFC 7807 `ProblemDetail` rather than an
`ApiError`.
**Steps:** with `false` and with `true`, provoke: a 400 (`POST /api/v1/queries/validate` with
malformed SQL), a 403 (a security refusal), a 404 (`GET /api/v1/streams/nosuch`), a 405 (`DELETE
/api/v1/streams`), a 415 (`POST` with `text/plain`), and a 500.
**Expected:** with `false`, **all six** carry `code`, `message`, `helpUrl`, `timestamp`, `path`.
With `true`, the ones `ApiExceptionHandler` does not handle (405, 415, and 404 on an unmapped path)
become `ProblemDetail` — which is exactly the two-shape world the comment rejects. **Record which of
the six are already `ProblemDetail` even with `false`**, because `ApiExceptionHandler` handles only
`PravahaException` and `IllegalArgumentException`, and 405/415 are neither.

## CFG-042 — `management.endpoints.web.exposure.include`
**Intent:** Which actuator endpoints exist. It governs whether `/actuator/env` — which would show
bearer tokens — is reachable (CFG-016).
**Falsifier:** an endpoint not in the list answers, or one in the list does not.
**Expected:** unset → Spring's default of `health` only; configured
`health,info,metrics,prometheus` → those four answer and `env`, `configprops`, `beans`,
`threaddump`, `heapdump` all 404; `*` → **`/actuator/env` and `/actuator/configprops` expose
`pravaha.security.tokens.*`; confirm whether Spring's default sanitisation masks a key whose *name*
is the secret** — sanitisation matches on key names like `password` and `secret`, and a token whose
map key is the credential matches none of them. **That is the finding**; wrong type `[health]` → a
list is accepted and equivalent.

## CFG-043 — `management.endpoint.health.probes.enabled`
**Intent:** Liveness and readiness kept distinct so Kubernetes does not restart a node that is
merely still restoring state.
**Falsifier:** `/actuator/health/liveness` and `/actuator/health/readiness` return the same thing as
`/actuator/health` in every state.
**Steps:** with `true` and `false`; in three states — normal, `flight.enabled: false` (CFG-002), and
during journal replay of a large journal.
**Expected:** with `true`, both sub-paths exist. **The load-bearing check: with
`flight.enabled: false`, `/actuator/health` is DOWN (fact 15) — is
`/actuator/health/readiness` also DOWN?** By default the readiness group contains only
`readinessState`, and a custom `HealthIndicator` contributes to it **only if added to
`management.endpoint.health.group.readiness.include`**, which this file does not do. If readiness is
UP while health is DOWN, **an orchestrator keeps an unreachable node in rotation — which is exactly
the failure `EngineHealthIndicator`'s javadoc says it was written to prevent, surviving in the
probe that orchestrators actually read.** That is the finding.

## CFG-044 — `management.endpoint.health.show-details`
**Intent:** `when-authorized` — so the detail map (`queries`, `failedQueries`, `firstFailure`,
`flightPort`) is shown to authorized callers only.
**Falsifier:** details leak to an unauthenticated caller, or never appear to an authorized one.
**Expected:** with `when-authorized` and `authentication: none`, there is no notion of an authorized
caller — record whether details appear or are always hidden. With `authentication: token`, an
authenticated caller must see `firstFailure`, **which is a query's failure message and may contain
SQL and bound parameter values**; an unauthenticated one must not. `always` → shown to everybody,
including `firstFailure`; `never` → shown to nobody, which also hides `flightPort` and makes CFG-002
harder to diagnose.

## CFG-045 — `springdoc.api-docs.path`
**Intent:** Where the OpenAPI document is served. `OpenApiContractTest` pins the document itself; this
pins the path.
**Falsifier:** the OpenAPI document is served somewhere other than the configured path, or the document served does not describe the paths this server actually maps.
**Expected:** unset → `/v3/api-docs`; configured → `/api/v1/openapi.json` serves a document whose
`paths` include `/api/v1/streams`, `/api/v1/queries/validate`, `/api/v1/queries/explain`,
`/api/v1/status`; boundary `/` → record; wrong type `[]` → context refresh fails. Also confirm the
OpenAPI document's `ApiError` schema matches `ApiDtos.ApiError`'s five fields, since CFG-041 depends
on it.

## CFG-046 — `springdoc.swagger-ui.path`
**Intent:** Where the Swagger UI is served, and whether it and the OpenAPI document stay in step when only one of the two paths is changed.
**Falsifier:** the UI is served somewhere other than the configured path, or loads a document from a path the server does not serve.
**Expected:** unset → `/swagger-ui.html`; configured `/api/docs` → serves the UI and the UI loads
the document from CFG-045's path; a mismatch between the two (change one, not the other) must
produce a UI that fails to load, not a blank page — record which.
**And with authentication on (API-F11).** `BearerTokenFilter` is handed both springdoc paths and
opens them, plus the page's resource prefix, which springdoc derives from `swagger-ui.path`'s
parent. So changing this setting must move the open path with it: set it to something else and the
page must still answer without a credential at its new address, and must **not** at the old one.
It used to be a constant transcribed from the shipped `application.yaml`, and they had already
drifted.

## CFG-047 — `-Dpravaha.ffm` and `-Dpravaha.memory`
**Intent:** The only two `pravaha.*` settings that are **system properties rather than configuration
keys** (`MemoryAccess.java:38,41`). They select the off-heap implementation, and they are not in
`application.yaml`, not in any `@Value`, and not in any documentation an operator reads.
**Falsifier:** `-Dpravaha.memory=agrona` and the default select the same implementation; or an
unknown value silently falls back.
**Steps:** start four times: no properties; `-Dpravaha.ffm=true`; `-Dpravaha.memory=agrona`;
`-Dpravaha.memory=nonsense`. In each, register `QW` and confirm the canonical result, and record
which `MemoryAccess` implementation is in use.
**Expected:** default is the Agrona path (FFM is opt-in until the parity benchmark, per
`MemoryAccess.java:29`); `ffm=true` selects FFM **on a JVM that supports it** and must fail loudly
on one that does not; `memory=agrona` selects Agrona explicitly; `memory=nonsense` must **refuse**,
not fall back — a silent fallback means a deployment believing it is running FFM for months.
**All four must produce the identical canonical result** (`u0=5, u1=7, u2=3`): an implementation
switch that changes an answer is the most serious kind of finding this file can produce.

---

## Keys the product names and does not have

Nine cases. Each one is a string an operator will search for, follow, and find nothing behind. A key
named by an error message is worse than an undocumented one: the engine has told somebody what to do
and the instruction cannot be carried out.

## CFG-048 — `arena.slab.size` is the remedy six error messages name and is not a key
**Intent:** `PRV-3001 RUNTIME_ARENA_EXHAUSTED` is thrown from six places, and **every one of them
tells the operator to raise `arena.slab.size`**:

| Where | Message |
|---|---|
| `WindowAssign.java:68` | "the window assigner's arena is full; raise arena.slab.size" |
| `SymmetricHashJoin.java:170` | "the join's output arena is full; raise arena.slab.size, or reduce the fan-out of this join" |
| `SymmetricHashJoin.java:205` | "the join's output arena is full while emitting an unmatched row; raise arena.slab.size" |
| `InterpretedPipeline.java:644` | "the compute stage's arena is full; raise arena.slab.size or reduce the batch size" |
| `InterpretedPipeline.java:706` | "the projection's arena is full; raise arena.slab.size or reduce the batch size" |
| `LookupJoin.java:308` | "the lookup join's arena is full; raise arena.slab.size" |

plus `RowArena.java:86-87` — "row of N bytes exceeds the slab size of M; raise arena.slab.size for
this query".
**Falsifier:** `arena.slab.size` set anywhere — YAML, environment variable `ARENA_SLAB_SIZE`, system
property `-Darena.slab.size`, or `-Dpravaha.arena.slab.size` — changes the arena slab size.
**Setup:** a query that exhausts the arena. `LaneConfig.defaults()` gives `arenaSlabBytes =
RowArena.DEFAULT_SLAB_BYTES = 4 × 1024 × 1024 = 4194304` bytes and `arenaMaxSlabs = 8`, so the
ceiling is `8 × 4194304 = 33554432` bytes = 32 MiB per lane. Build a projection whose per-batch
output exceeds that: 512 rows per batch (`LaneConfig.DEFAULT_BATCH_SIZE`) × a row wide enough that
`512 × width > 33554432`, i.e. `width > 65536` bytes. A `SELECT` producing a single `STRING` column
of 70 000 characters per row does it.
**Steps:**
1. Confirm the failure: run the query, capture `PRV-3001` and the exact message.
2. `grep -rn "arena.slab.size" --include=*.java --include=*.yaml --include=*.yml --include=*.md .`
   and record every hit. Expect: the six messages above, `RowArena.java:87`, `RowArenaTest.java:170`,
   `docs/system_design.md:3543`, and `docs/qa/logs/*`. **No `@Value`, no
   `@ConfigurationProperties` field, no `Configuration.get*` call, no `application.yaml` entry.**
3. Try each of the four spellings above and re-run. Confirm the failure is byte-for-byte identical.
4. Confirm `pravaha-server` never calls `QueryRegistry.executingWith` (fact 14), so there is no
   code path by which any value could reach `LaneConfig.arenaSlabBytes` on a server node.
**Expected:** the only remedy the engine offers cannot be applied, by any spelling, from any source.
`docs/system_design.md:3543` places `arena.slab.size: 4MB` inside a `pravaha.runtime:` block, which
is where an operator will try first and where it does the least.
**Vacuity:** step 1 establishes the failure is real and reachable before step 3 claims the remedy is
not; without step 1 the case would pass on a query that never exhausted anything.

## CFG-049 — `state.slab.size` is the remedy `PRV-4001` names and is not a key
**Intent:** `RowStore.java:117-119` throws `PRV-4001 STATE_TOO_LARGE` with *"…-byte slab; raise
state.slab.size for this query"*. Same defect, different subsystem, one message instead of six.
**Falsifier:** any spelling of `state.slab.size` changes `RowStore`'s block size.
**Setup:** a join whose per-key state exceeds one `RowStore` slab. `RowStore.MIN_BLOCK_BYTES = 64`
and `HEADER_BYTES = 16`, so the largest row a 64-byte block holds is `64 − 16 = 48` bytes.
**Steps:** as CFG-048, substituting `state.slab.size`. Expect hits only at `RowStore.java:119` and
`RowStoreTest.java:189`.
**Expected:** no key, no reader, no documentation. Record separately from CFG-048 — they are two
independent constants in two modules and closing one does not close the other.

## CFG-050 — `pravaha.runtime.lanes` and `pravaha.runtime.lane.affinity`
**Intent:** `system_design.md:3535-3537` documents `pravaha.runtime.lanes: ${PRAVAHA_LANES:14}` and
`lane.affinity: true` as node configuration. The number of lanes is the single largest capacity
decision a deployment makes.
**Falsifier:** setting `pravaha.runtime.lanes: 2` or `PRAVAHA_LANES=2` changes the lane count.
**Steps:** start with the key and the environment variable set to 2; take a thread dump; count
threads matching `pravaha-lane-*`. Repeat with 14.
**Expected:** identical thread counts. `grep -rn "pravaha.runtime.lanes"` finds only the design
document; `PravahaServerApplication` forwards only `pravaha.node.id` (fact 12). Record the lane count
the node actually runs and where it comes from, because an operator cannot find that out from any
document.

## CFG-051 — `pravaha.runtime.wait.strategy`
**Intent:** `system_design.md:3538` documents `SPIN_THEN_YIELD | BUSY_SPIN | BACKOFF_PARK |
BLOCKING`. `WaitStrategy.Kind` exists and `LaneConfig.withWaitStrategy` exists; nothing on the
server path calls either.
**Falsifier:** setting the key changes CPU usage on an idle node.
**Steps:** start idle with `BLOCKING` and with `BUSY_SPIN`; measure per-thread CPU over 60 s with
`top -H -b -n 12`.
**Expected:** identical. `SPIN_THEN_YIELD` is in force in both, from `LaneConfig.defaults()`. **This
is the one with a measurable cost**: an idle node spinning is the difference between a lane costing
0 % and 100 % of a core, and the documented control for it is unreachable. Cross-reference `PERF`'s
idle-cost cases.

## CFG-052 — `pravaha.runtime.batch.max.records` and `batch.max.linger`
**Intent:** `system_design.md:3539-3540`: `512` and `200us`. `LaneConfig.batchSize` defaults to 512,
so the documented default is correct and the control is not there. `batch.max.linger` has **no
corresponding `LaneConfig` field at all** — there is no linger in this engine.
**Falsifier:** setting either key changes batching.
**Expected:** both inert; `batch.max.linger` additionally names a mechanism that does not exist.
Record the second separately: an operator tuning latency will look for it first.

## CFG-053 — `pravaha.runtime.ring.capacity` and the backpressure watermarks
**Intent:** `system_design.md:3541-3543`: `ring.capacity: 65536`, `backpressure.high.watermark: 0.80`,
`backpressure.low.watermark: 0.50`. `LaneConfig` has `inboxCells` (default **2048**, not 65536) and
`exchangeCells` (default 1024). There are no watermark fractions anywhere.
**Falsifier:** any of the three keys changes anything.
**Expected:** all three inert, and the documented default `65536` disagrees with the real default
`2048` by a factor of 32 — so even read as documentation rather than as configuration, it is wrong.

## CFG-054 — `pravaha.state.*` — the whole tiering block
**Intent:** `system_design.md:3545-3552` documents `default.tier: HYBRID` with
`HEAP | OFFHEAP | ROCKSDB | HYBRID`, `offheap.max.per.lane: 1GB`, and a `rocksdb` block with `dir`,
`block.cache`, `write.buffer.manager` and `compaction.style`.
**Falsifier:** any of these keys is read anywhere.
**Steps:** `grep -rn "default.tier\|offheap.max\|block.cache\|write.buffer.manager\|compaction.style"`
across `--include=*.java`.
**Expected:** no hits outside the design document. There is no RocksDB dependency in the build.
**This is a block an operator will size a machine around.** Raise it as a documentation defect at
that severity, distinct from the individual key findings above.

## CFG-055 — `pravaha.checkpoint.*` — the design document's block versus the real one
**Intent:** `system_design.md:3554-3568` documents nine checkpoint keys: `enabled`, `interval`,
`timeout`, `min.pause.between`, `max.concurrent`, `alignment`, `retain`, and a `store` block with
`type: aerospike | s3 | filesystem`, `namespace`, `set`. The real block
(`PersistenceProperties.Checkpoint`) has **three**: `directory`, `interval`, `keep`.
**Falsifier:** any of the six extra keys is read.
**Expected:**
- `interval` is the only name that matches. `retain` is spelled `keep`. `enabled` does not exist —
  checkpointing is on iff `directory` is set. `timeout` is bound by nothing (CFG-024). `alignment`,
  `min.pause.between`, `max.concurrent` and the whole `store` block do not exist; the store is
  always a file store under `directory`.
- An operator copying the design document's block gets a node that starts, ignores eight of nine
  keys, and **does not checkpoint at all**, because `directory` is not among them.
- That last sentence is the case. Run it: start with the design document's block verbatim and
  confirm the WARN at `PravahaNode.java:363` fires.

## CFG-056 — there is no `pravaha.lane.*`, and `executingWith` is never called
**Intent:** The whole lane tier — wait strategy, inbox size and cell width, batch size, arena slab
size and slab ceiling, exchange ring size, thread name prefix, daemon flag, shutdown timeout — is
one record, `LaneConfig`, with a `with*` method for every field. `QueryRegistry.executingWith(LaneConfig,
MemoryAccess)` takes it. **Nothing in `pravaha-server` calls that method**, and no key anywhere maps
to any field.
**Falsifier:** `grep -rn "executingWith" --include=*.java .` returns a call site outside
`QueryRegistry.java:136` and its tests.
**Steps:**
1. Run the grep. Record every hit.
2. Enumerate the ten `LaneConfig` fields and, for each, record the value a server node runs with,
   taken from `LaneConfig.defaults()` (`LaneConfig.java:83-96`), and the configuration key that
   would set it. The second column is empty ten times.
3. Confirm `MemoryAccess` selection is likewise only a system property (CFG-047), so the second
   argument is unreachable too.
**Expected:** a table of ten settings, each documented in `LaneConfig`'s javadoc as *"a
CPU-for-latency or memory-for-throughput trade that a deployment is entitled to make differently,
which is why none of them is baked into Lane"* — and none of which a deployment can make.
**This is the single largest gap in this file**: not one wrong key, but a whole tier unreachable.
It is also the root cause of CFG-048 and CFG-051, so file it as the parent and those as instances.

---

## Security: policy × authentication × allow-anonymous × TLS

Twenty-four cases. The four keys are checked in three different places — `refuseAccidentalOpenServer`
at startup, `securityPolicy()` when the registry is built, and `encryptedWith` when Flight starts —
and **no two of them see the same view of the configuration**. The combinations below are the
product of those keys, written out, because round 1's security work tested each key alone.

### The 2 × 2 × 2 of policy, authentication and allow-anonymous

Eight cases. `policy ∈ {permissive, authenticated}` × `authentication ∈ {none, token}` ×
`allow-anonymous ∈ {false, true}`. In every one: `audit: none`, no TLS, `tokens` configured
whenever `authentication: token`. The observation is always the same four things — **does it start**,
**what does the `security:` log line say**, **what does an anonymous Flight call get**, and **what
does an anonymous HTTP `GET /api/v1/streams` get** — because the fourth is where the two halves
diverge.

## CFG-057 — permissive / none / allow-anonymous=false
**Intent:** The shipped default, and the state the guard exists to refuse.
**Falsifier:** the node starts.
**Expected:** **startup refused**, `PRV-7002`, message at `PravahaNode.java:165-173` naming all
three remedies. Exit non-zero. No port is bound — confirm 18800 and 19800 are both closed
afterwards.
**Vacuity:** CFG-015's `true` row starts on the same file with one line changed, so a pass here is
not "the node failed for some other reason".

## CFG-058 — permissive / none / allow-anonymous=true
**Intent:** Open, on purpose, written down. The `dev` profile's meaning.
**Falsifier:** an anonymous caller is refused anything.
**Expected:** starts. Log: `authentication=none, policy=permissive, audit=none, flight
transport=PLAINTEXT`. Anonymous Flight `getTables`/`getStream` succeed. Anonymous
`GET /api/v1/streams` returns 200 with `txn`. `POST /api/v1/streams` from an anonymous caller
**succeeds** — record it; an open server that accepts stream *registrations* is a different exposure
from one that serves reads.

## CFG-059 — permissive / token / allow-anonymous=false
**Intent:** The ordinary authenticated deployment. `authenticates()` is true, so the first guard
does not fire.
**Falsifier:** an unauthenticated call is served, or an authenticated one is refused.
**Expected:** starts, with the WARN at `PravahaNode.java:193-195` about credentials in the clear
(no TLS). Anonymous Flight → `PRV-7001` from `PrincipalMiddleware.java:96`. Anonymous HTTP →
`PRV-7001` from `BearerTokenFilter` (registered because `verifier() != null`). With the token: both
succeed, and **`policy: permissive` means the verified principal then sees every view** — confirm a
second principal sees the first's views.

## CFG-060 — permissive / token / allow-anonymous=true
**Intent:** `allow-anonymous` is only consulted when `open` is true (`PravahaNode.java:163-164`),
and `open` requires `!authenticates()`. So with `token` it is **dead**.
**Falsifier:** setting `allow-anonymous: true` alongside `authentication: token` admits anonymous
callers.
**Expected:** **identical to CFG-059 in every observation.** The key reads like "and also let
anonymous callers in", and does nothing. Nothing warns that a key was set and ignored. Record it —
an operator who sets this believes they have opened a door and has not, which is the safe direction
but is still a lie in the configuration file.

## CFG-061 — authenticated / none / allow-anonymous=false
**Intent:** The contradiction the second guard exists for: a policy that serves only verified callers
on a node with nothing that can verify one.
**Falsifier:** the node starts.
**Expected:** **startup refused**, `PRV-7002`, message at `PravahaNode.java:182-187`: *"pravaha.security.policy=authenticated
with pravaha.security.authentication=none is a node nobody can use…"*. The comment above it
(`PravahaNode.java:176-181`) states the reason: Flight would refuse everybody while the HTTP surface,
which has no filter because authentication is off and does not consult the policy, would keep serving
stream schemas and accepting registrations. **Confirm that reasoning independently** by commenting
out the guard in a scratch build and observing both surfaces — if the comment is right, HTTP serves
and Flight does not. If the executor will not modify code, CFG-062 reaches the same state legally.

## CFG-062 — authenticated / none / allow-anonymous=true
**Intent:** Whether `allow-anonymous: true` is enough to get past the *second* guard. It is not:
the second `if` at `PravahaNode.java:175` does not consult `allow-anonymous` at all.
**Falsifier:** the node starts.
**Expected:** **startup refused with the same `PRV-7002` as CFG-061.** Two different refusals in one
method, and only the first mentions `allow-anonymous` as a remedy — so an operator who follows the
first message's advice lands here and gets a second refusal that does not acknowledge what they just
did. Record the sequence; it is a usability defect in a security control, which is where they matter
most.

## CFG-063 — authenticated / token / allow-anonymous=false
**Intent:** The recommended closed deployment — `QUICKSTART.md:104-113`'s block.
**Falsifier:** an anonymous caller sees anything, on either transport.
**Expected:** starts. `refuseAccidentalOpenServer`: `open` is false (authenticates), and the second
guard needs `!authenticates()` — neither fires. WARN about plaintext. Anonymous Flight →
`PRV-7001`. Anonymous HTTP → `PRV-7001`. With a token, both work, and the policy is
`AuthenticatedOnlyPolicy`. **Then the ordering check:** `PravahaFlightServer.authorizedBy` is called
with `securityPolicyOf(registry)` **before** `hosting(registry)` (`PravahaNode.java:426-428`), and
`PravahaNode.java:418-425` records that the reverse order made `policy: authenticated` unable to
start a node with Flight enabled at all. **Prove the fix holds**: this exact configuration is the
regression.

## CFG-064 — authenticated / token / allow-anonymous=true
**Intent:** The dead key again (CFG-060), now on the closed configuration.
**Falsifier:** this configuration behaves differently from CFG-063 in any of the four observations.
**Expected:** identical to CFG-063. Worth its own run because this is the configuration a team
reaches by starting from `dev` and hardening: they set `authentication` and `policy` and **leave
`allow-anonymous: true` behind**, and nothing tells them it is now meaningless. If a future change
ever makes `allow-anonymous` consulted independently of `authenticates()`, this file is the one that
silently opens.

### Invalid values in the same four keys

## CFG-065 — an unknown policy on an otherwise closed node
**Intent:** `securityPolicy()` is called **twice** — once from `refuseAccidentalOpenServer` at
`PravahaNode.java:163` and once at line 339. The first call is where an unknown value is discovered.
**Falsifier:** the node starts with a policy name it does not know, or exits leaving a coordinator thread behind.
**Setup:** `authentication: token`, `policy: strict`, tokens configured.
**Expected:** `PRV-7002` at startup from `PravahaNode.java:288-293`, quoting `'strict'` and naming
`permissive` and `authenticated`. **Before** any port is bound and **after** the coordinator has
started (fact 3) — confirm the coordinator is closed cleanly on the way out, because
`refuseAccidentalOpenServer` throws from inside `start()` after `coordinator.start()` and there is
no try/finally around it. **A leaked coordinator thread after a failed startup is a finding.**

## CFG-066 — an unknown policy on a node that would also be accidentally open
**Falsifier:** the node reports the open-server problem rather than the unknown-policy one, or reports both.
**Setup:** `authentication: none`, `policy: strict`, `allow-anonymous: false`.
**Intent:** Which of the two problems is reported. `refuseAccidentalOpenServer` evaluates
`securityPolicy()` inside the expression that computes `open` (line 163), so the **policy** error
fires first.
**Expected:** `PRV-7002` about the unknown policy, **not** about the open server. Both are true; the
operator fixes the policy, restarts, and meets the second. Record the two-restart sequence.

## CFG-067 — `authentication: token` with `tokens` empty
**Intent:** `verifier()` returns `TokenVerifier.rejectAll()` rather than null
(`SecurityProperties.java:121-130`), so `authenticates()` is true, the filter is registered, and
**every** call is refused.
**Falsifier:** the node starts and serves anybody; or the node refuses to start.
**Expected:** starts. `authenticates()` true → neither startup guard fires. Anonymous and
credentialled calls alike get `PRV-7001`. The class's own comment says the reason should be visible
at startup rather than in a support ticket about 401s — **check whether it actually is**: grep the
startup log for any line mentioning that no tokens are configured. If there is none, the stated
intent is not met, and that is the finding.

### TLS: the pair that is never checked as a pair

Nine cases over `certificate ∈ {unset, valid, missing-file}` × `key ∈ {unset, valid, missing-file}`.
`$QA/tls/tls.crt` and `$QA/tls/tls.key` are a matched self-signed pair; `$QA/tls/absent.*` do not
exist.

## CFG-068 — certificate unset, key unset
**Intent:** The TLS baseline: neither half configured, which is the shipped default and the state every other row in this 3x3 is measured against.
**Falsifier:** a node with no TLS material configured serves TLS, or refuses to start.
**Expected:** plaintext, `flight transport=PLAINTEXT`, `grpc://` connects, `grpc+tls://` does not.
The baseline. With `authentication: token` the WARN at `PravahaNode.java:193-195` fires; with
`authentication: none` it does not.

## CFG-069 — certificate valid, key valid
**Intent:** The only TLS configuration that works, and the control for CFG-070 through CFG-076.
**Falsifier:** a matched certificate and key do not produce an encrypted transport.
**Expected:** `encryptedWith` accepts both; `flight transport=TLS`; `grpc+tls://` connects with the
CA; `grpc://` fails. `PravahaFlightServer.isEncrypted()` is true.

## CFG-070 — **certificate unset, key valid** — the plaintext node that looks configured
**Intent:** The defect. `tlsCertificate == null`, so `if (tlsCertificate != null)` is false and
`encryptedWith` is never called. The key is read into a `File` (`PravahaNode.java:134`), held in a
field, and never used.
**Falsifier:** the node refuses to start, or serves TLS, or says anything about the unused key.
**Setup:** `base.yaml` plus `pravaha.flight.tls.key: $QA/tls/tls.key`, no `certificate`.
**Steps:** start; read the whole startup log; `grpc://127.0.0.1:19800` with an SDK client; capture
the first 64 bytes on the wire with `tcpdump -i lo -X port 19800` and confirm the gRPC preface is
readable.
**Expected:** **node starts in plaintext.** Log says `flight transport=PLAINTEXT`. `grpc://`
connects and rows are readable on the wire. **Nothing anywhere mentions that a TLS private key was
configured and ignored.** With `authentication: token` this is the worst case: the WARN fires saying
"set pravaha.flight.tls.certificate and .key" — advice the operator has already half-taken, with no
acknowledgement of the half they took.
**Vacuity:** CFG-069 is the control — the same key file, one extra line, produces TLS. So the
plaintext here is caused by the missing certificate and not by the key being unusable.

## CFG-071 — **certificate valid, key unset** — the raw NPE
**Intent:** `tlsCertificate != null`, so `encryptedWith(cert, null)` is called and
`privateKey.isFile()` at `PravahaFlightServer.java:123` dereferences null.
**Falsifier:** startup fails with `PRV-6104` and a message naming the missing key.
**Setup:** `base.yaml` plus `pravaha.flight.tls.certificate: $QA/tls/tls.crt`, no `key`.
**Expected:** **`NullPointerException`**, not `PRV-6104`. It propagates out of `PravahaNode.start()`
through `SmartLifecycle` and fails the context. Record the exact top frame and whether the message
names `pravaha.flight.tls.key` — a modern JVM's helpful NPE message will say *"Cannot invoke
`java.io.File.isFile()` because `privateKey` is null"*, which is legible to a Java developer and to
nobody else. Record whether the coordinator is left running (as CFG-065).
**The pair-check that is missing:** CFG-070 and CFG-071 are the same operator mistake in two
directions, and they produce a silent plaintext server and an unexplained crash. A single check
"both or neither" in `PravahaNode`'s constructor would produce one `PRV-6104` for both. **File them
as one finding with two symptoms.**

## CFG-072 — certificate missing-file, key valid
**Intent:** The certificate half unreadable, with the key half valid -- the branch `PravahaFlightServer.java:118-122` exists for.
**Falsifier:** the node starts, in plaintext or otherwise.
**Expected:** `PRV-6104` from `PravahaFlightServer.java:118-122`, naming the certificate's
**absolute** path. Startup fails. This is the behaviour CFG-070 should have.

## CFG-073 — certificate valid, key missing-file
**Intent:** The key half unreadable, with the certificate half valid -- the branch at `PravahaFlightServer.java:123-127`.
**Falsifier:** the node starts, in plaintext or otherwise.
**Expected:** `PRV-6104` from `PravahaFlightServer.java:123-127`, naming the key's absolute path.
This is the behaviour CFG-071 should have. **The two `PRV-6104` branches are three lines apart and
both work; the two failures above bypass them entirely.**

## CFG-074 — certificate missing-file, key missing-file
**Intent:** Both halves unreadable: which one the operator is told about.
**Falsifier:** the message names the key, or names both, or the node starts.
**Expected:** `PRV-6104` naming the **certificate** (checked first). The key's absence is not
mentioned. Record it: an operator fixes the path in the message, restarts, and meets the second.

## CFG-075 — certificate missing-file, key unset
**Intent:** Establishing that the NPE of CFG-071 needs a certificate that exists -- the ordering of the two checks inside `encryptedWith` decides which failure an operator gets.
**Falsifier:** a `NullPointerException` rather than `PRV-6104`.
**Expected:** `encryptedWith(missingCert, null)` — the certificate check at line 118 runs **before**
the null dereference at line 123, so this is `PRV-6104`, not an NPE. **The NPE in CFG-071 requires a
certificate that exists.** That is worth stating explicitly, because it means the crash only happens
to operators who got the certificate right.

## CFG-076 — certificate unset, key missing-file
**Intent:** Both halves wrong in the silent direction: a key that does not exist, and no certificate to make anything look at it.
**Falsifier:** the node refuses to start, or says anything at all about TLS.
**Expected:** plaintext, as CFG-070. The key is never opened, so its non-existence is never noticed.
Two wrong things and zero messages.

### Where the two halves of the security model diverge

## CFG-077 — the `security:` log line versus what is enforced
**Intent:** `PravahaNode.java:342-350` logs four things. Three of them are the configured **strings**
(`security.getPolicy()`, `security.getAudit()`) or a derived label, not the objects in force.
**Falsifier:** the log line and the enforced behaviour ever disagree.
**Steps:** for each of CFG-057 through CFG-076 that starts, record the log line and the four
behavioural observations, and build the table.
**Expected:** the line is accurate in every starting configuration. The known trap it was written
against is documented in the comment at `PravahaNode.java:345-347`: logging the object would print
`SecurityPolicy$1@7657d90b`. **The remaining risk is `authentication`**: the line prints
`security.authenticates() ? "token" : "none"`, so `authentication: tokens` (CFG-012) prints `none` —
correct, and the only place the typo is visible. Confirm that.

## CFG-078 — the `dev` profile is exactly one key
**Intent:** `application-dev.yaml` sets `pravaha.security.allow-anonymous: true` and nothing else.
**Falsifier:** `--spring.profiles.active=dev` changes anything else.
**Steps:** start with and without the profile on an otherwise identical file; diff
`/api/v1/status`, the full startup log, the bound port set, and `/actuator/health`.
**Expected:** the only difference is that the `dev` run starts and the other is refused by CFG-057.
`QUICKSTART.md:93-101` and `HANDOVER.md:96` both describe the profile as exactly that
acknowledgement; confirm the file has not grown.

## CFG-079 — HTTP is not governed by the policy
**Intent:** `BearerTokenFilter` is registered iff `verifier() != null`, i.e. iff
`authentication: token` (`PravahaServerApplication.java:65-81`). **`policy` is not consulted on the
HTTP path at all.** `ApiExceptionHandler` maps `SECURITY` to 403, but nothing on the HTTP side calls
a `SecurityPolicy`.
**Falsifier:** an HTTP call is refused by the policy.
**Setup:** `authentication: token`, `policy: authenticated`, two tokens `ann` and `bob`.
**Steps:** as `ann`, `POST /api/v1/streams` to declare a stream. As `bob`, `GET /api/v1/streams`.
Then the same two operations over Flight.
**Expected:** over HTTP, `bob` sees `ann`'s stream — authentication is enforced, authorization is
not. Over Flight, `PravahaFlightSqlProducer` consults the policy
(`PravahaFlightSqlProducer.java:449,487,505`). **Two transports, two authorization models, one
`policy` key.** Record which `/api/v1` operations are policy-checked; expect none.

## CFG-080 — the registry's policy and the Flight server's policy must be the same object
**Intent:** `requireOnePolicy` (`PravahaFlightServer.java:184-192`) exists because a deployment that
configured one and not the other got a server where registering was judged by one set of rules and
reading by another — silently, in the direction of whichever was more permissive.
**Falsifier:** a configuration exists in which the two differ.
**Steps:** for `policy: permissive` and `policy: authenticated`, confirm
`securityPolicyOf(registry)` (`PravahaNode.java:298-300`, which returns `registry.policy()`) is the
object passed to `authorizedBy`, and that `hosting(registry)` is called **after**. Then attempt to
construct a divergence: is there any key, profile or ordering that makes them differ? Expect not,
and record the reasoning, because this is a guard whose value is that it can never fire.
**Expected:** one `SecurityPolicy` object, fetched from the registry at `PravahaNode.java:298-300` and handed to `authorizedBy` at `:427` before `hosting(registry)` at `:428`. `requireOnePolicy` compares it with itself and passes. No configuration reaches a divergence. Record the mechanism, not the absence of an error.
**Vacuity:** a guard that cannot fire is indistinguishable from a guard that is not there, so the
case must show the *mechanism* — that one object is fetched from the registry and handed to the
server — not merely that no error occurred.

---

## The two out-of-orderness keys

Six cases. Two documented keys differing only in tree position —
`pravaha.watermark.out-of-orderness` and `pravaha.streams.<n>.out-of-orderness` — of which one is
inert. CFG-009 and CFG-025 establish each alone; these six distinguish them, and pin the conditions
under which the working one also stops working.

## CFG-081 — the distinguishing pair, run back to back
**Intent:** One sitting, one data file, one query, two keys, two answers. This is the case that
settles it.
**Falsifier:** both keys produce the same row count, or neither does.
**Setup:** two config files identical to `base.yaml` except:
- `A.yaml`: `pravaha.streams.txn.out-of-orderness: 0s`, `pravaha.watermark` block absent of
  `out-of-orderness`.
- `B.yaml`: `pravaha.watermark.out-of-orderness: 0s`, `pravaha.streams.txn.out-of-orderness`
  **removed**.
Both keep `pravaha.streams.txn.event-time: event_time`.
**Steps:** for each file: start, register `QW`, feed `txnA.csv`, wait 15 s, `pravaha query --sql
"SELECT * FROM QW"`, count rows and record every value.
**Expected:**
- **A: 6 rows.** `[T0+0,T0+5)` → `u0 = 1 + 4 = 5`, `u1 = 2 + 5 = 7`, `u2 = 3`; `[T0+5,T0+10)` →
  `u0 = 7 + 10 = 17`, `u1 = 8`, `u2 = 6 + 9 = 15`. Totals `15` and `40`, sum `55`.
- **B: 0 rows.** The key is not read, so the stream keeps `StreamSchema.DEFAULT_OUT_OF_ORDERNESS =
  10s`; watermark `= T0+11 − 10s = T0+1 < T0+5`; no window closes.
- `6 ≠ 0` is the whole result. Both keys are documented in `application.yaml`; one of them does
  nothing.
**Vacuity:** the two runs differ in one line of YAML and nothing else — same data, same SQL, same
build, same ports, consecutive. A pass cannot come from timing, because A's 6 rows require the
watermark to have passed `T0+10`, which the default lateness cannot produce at any time.

## CFG-082 — both keys set, and disagreeing
**Intent:** What an operator who read both documents does.
**Falsifier:** the engine-wide key overrides the per-stream one, or the two are combined.
**Setup:** `pravaha.watermark.out-of-orderness: 0s` **and**
`pravaha.streams.txn.out-of-orderness: 30s`.
**Expected:** **0 rows.** The per-stream key wins because it is the only one read: `30s` gives
watermark `T0+11 − 30s = T0−19`, closing nothing. The engine-wide key that reads like a default and
is set to the more permissive value has no say. Record the log — nothing mentions either key.

## CFG-083 — both keys set, agreeing
**Intent:** The configuration an operator who read both documents will actually arrive at, and the reason the inert key survives undetected.
**Falsifier:** the result differs from CFG-081 run A.
**Setup:** both at `0s`.
**Expected:** **6 rows**, identical to CFG-081 A. The result is right for the wrong reason, which is
why this configuration is dangerous: it is the one an operator will arrive at, and it works, so the
inert key is never discovered until somebody removes the line that was doing the work.

## CFG-084 — the per-stream key with `event-time` removed
**Intent:** The working key's own failure mode: `withEventTime` returns at
`PravahaNode.java:234-236` when `event-time` is blank, so `outOfOrderness` at line 251 is never
reached.
**Falsifier:** `out-of-orderness` takes effect without `event-time` being declared.
**Setup:** `pravaha.streams.txn.out-of-orderness: 0s`, `event-time` **removed**.
**Expected:** **0 rows**, and for a second reason on top of the first: with no event-time column the
plugin stamps every row 0 (`PravahaNode.java:261-265`) and no watermark can advance into the
present regardless of lateness. Confirm both: the schema in the catalog has no event-time ordinal,
**and** the lateness is `10s` rather than `0s`. The second is the silent one — a key present in the
file, syntactically valid, applied to nothing.
**Vacuity:** CFG-081 A is the control: same key, same value, `event-time` present, 6 rows.

## CFG-085 — per-stream lateness on a multi-stream query
**Intent:** `OPERATIONS.md:228-232` states that a query reading three streams should get three
tolerances rather than the worst of them, and that lateness reaches the tracker per stream
(`QueryExecution.trackEventTimeOf` reads `inputSchema(stream).outOfOrderness()`).
**Falsifier:** two streams with different `out-of-orderness` behave as though they shared one value.
**Setup:** declare `txn` (`0s`) and a second stream `txn2` (`10s`) with the same schema, both bound
to `txnA.csv`, both with `event-time`. Register a query joining or unioning them — use the simplest
construct `SQLX` confirms is supported.
**Expected:** the `txn` side's windows close at watermark `T0+11`; the `txn2` side's at `T0+1`. Hand
the expected per-side row counts from the CFG-081 arithmetic: `txn` contributes the six rows,
`txn2` contributes none. If the query's watermark is the **minimum across partitions**, the joined
result is empty and the per-stream tolerances are visible only in the per-side state. Record which,
because "three tolerances rather than the worst of them" and "the minimum across partitions" are in
tension and the documentation asserts both.

## CFG-086 — the documentation audit for the pair
**Intent:** Close the loop in the other direction: every place the inert key is documented must be
corrected, and the working key must be documented somewhere.
**Falsifier:** the working key is documented in an operator-facing document, or the inert key is documented in fewer than four places.
**Steps:** `grep -rn "out-of-orderness" docs/ pravaha-*/src/main/resources/`.
**Expected:** the inert key appears at `application.yaml` (the `pravaha.watermark` block, eight
lines of prose), `OPERATIONS.md:222`, `CONCEPTS.md:66`, and `StreamSchema.java:51` — four places.
The working key, `pravaha.streams.<n>.out-of-orderness`, appears in
`StreamDeclarationProperties.java:86` javadoc and **in no operator-facing document at all**.
`OPERATIONS.md:227-232` tells the operator to set lateness *"per stream, at creation, with
`StreamSchema.outOfOrderness`"* — a Java API — without mentioning that a configuration key for it
exists. **Four documents for the key that does nothing, none for the key that works.** That is the
finding; the executor should propose the exact edits.

---

## `pravaha.streams` and `pravaha.sources` as a pair

Seven cases. Two blocks, one name space, no check that they correspond.

## CFG-087 — a stream declared with no source
**Intent:** The documented and intended state — *"that is what a query written ahead of its source
needs"* (`SourceBindingProperties.java:38-41`).
**Falsifier:** the node refuses, or the stream is unplannable.
**Setup:** `base.yaml` with the whole `pravaha.sources` block removed.
**Steps:** start; read the log; `GET /api/v1/streams`; register `QW`; wait 15 s; read the view.
**Expected:** starts. INFO *"no sources are bound, so registered queries receive rows only from
clients that push them; bind one under pravaha.sources.<stream>"* (`PravahaNode.java:393-395`).
`txn` is listed and `QW` registers and reports RUNNING. The view is **empty** — 0 rows, for ever.
`pravaha_query_rows_in{query="QW"}` is **0**. Correct, documented, and worth a case because a
RUNNING query with an empty view is the single most common false alarm.
**Vacuity:** CFG-088's control is the same file with the source restored, giving 3 rows.

## CFG-088 — a source bound to a stream that is not declared
**Intent:** The reverse, and the one `StreamDeclarationProperties.java:34-38` says produced
*"Object 'txn' not found. Known streams: []"* — accurate and baffling next to a configuration file
that clearly mentions `txn`.
**Falsifier:** the node refuses at startup, or the binding silently works.
**Setup:** `base.yaml` with `pravaha.streams` removed and `pravaha.sources.txn` kept.
**Expected:** **starts.** `sources bound: [...]` names `txn` (`PravahaNode.java:397`) — so the log
says the stream is bound. `GET /api/v1/streams` returns **empty**. `register QW` fails with
`PRV-2003 SQL_UNKNOWN_STREAM`: *"Object 'txn' not found. Known streams: []"*. **The node logged that
it bound a stream it does not know and then said it knows no streams.** Nothing at startup compares
the two maps; `sources.toBindings()` does no validation (`SourceBindingProperties.java:62-68`).
**Finding: the two blocks should be reconciled at startup and are not.**

## CFG-089 — a source for one stream, a declaration for another
**Intent:** The typo case: a source block and a streams block that are each internally valid and name different streams.
**Falsifier:** the node refuses to start, or any log line pairs the two names.
**Setup:** `pravaha.streams.txn` declared; `pravaha.sources.txns` (plural) bound.
**Expected:** starts; `sources bound:` names `txns`; `GET /api/v1/streams` lists `txn`; `QW` against
`txn` registers, runs, and receives **nothing**, for ever. **Neither block is wrong on its own and
the node is useless.** This is CFG-087 and CFG-088 at once, and it is what a typo produces.
Confirm `pravaha_query_rows_in` is 0 and that no log line pairs the two names.

## CFG-090 — many streams, some bound
**Intent:** The realistic case: declare four streams, bind two.
**Falsifier:** the two startup log lines, read together, tell the operator which streams are declared and unbound.
**Setup:** declare `s1..s4` with the base schema; bind `s1` and `s2` to `txnA.csv`.
**Expected:** `streams declared in configuration: [s1, s2, s3, s4]` (`PravahaNode.java:219-221`) and
`sources bound: [...]` naming two. Four queries, one per stream: `s1` and `s2` deliver the canonical
three rows each; `s3` and `s4` deliver 0. **The two log lines together contain the answer and
neither states it.** Propose the line that should exist: *"declared and unbound: s3, s4"*.

## CFG-091 — declaration order and binding order
**Intent:** `LinkedHashMap` in both properties classes, so both preserve file order. Whether
anything depends on it.
**Falsifier:** two runs of the same file, or two files differing only in key order, produce a different order in any of the three places.
**Steps:** two files with `s1,s2,s3` and `s3,s2,s1`; compare `GET /api/v1/streams` order, the
`streams declared in configuration:` line, and the `sources bound:` line.
**Expected:** all three follow file order in both runs. Nothing behavioural depends on it. Record
it, because a `HashMap` here would make the startup log non-deterministic and an operator's diff of
two nodes' logs useless.

## CFG-092 — the same stream name in both blocks with different schemas
**Intent:** CFG-011's disagreement row, run as a pairing case.
**Falsifier:** the node compares the two schemas at startup and says something.
**Setup:** `pravaha.streams.txn.schema` with four columns; `pravaha.sources.txn.options.schema` with
two.
**Expected:** starts, no comparison, no warning. `QW` plans against four columns. The plugin decodes
two. Record precisely what a row looks like on arrival — the likely outcomes are a decode failure
per row (`PRV-5040`), silent nulls in the missing columns, or a column shift. **Any of the three is
a finding**; the last is the dangerous one, because it produces wrong answers rather than errors.
**Vacuity:** run the matching-schema control first and confirm the canonical three rows, so the
divergence is attributable.

## CFG-093 — stream names that are not valid SQL identifiers
**Intent:** The map key becomes a catalog name and then a table name in SQL.
**Falsifier:** a name that startup accepts cannot be found in the catalog listing, and nothing said so.
**Setup:** declare streams named `my-stream`, `1txn`, `select`, `txn ` (trailing space), `TXN` and
`txn` together, and a name with a non-ASCII character.
**Expected:** for each: whether startup accepts it, whether it appears in `GET /api/v1/streams`,
and whether a query can name it (quoted and unquoted). `registerDeclaredStreams` does **no** name
validation (`PravahaNode.java:207-223`). Expect several to start and be unplannable, which is
CFG-088's symptom arriving from a different cause. `TXN`/`txn` together is the one to watch: two map
keys, and whether the catalog folds case.

---

## Persistence, end to end

Nine cases. CFG-020 to CFG-024 covered the keys alone; these cover them together, which is where
"knows every question and none of the answers" lives.

## CFG-094 — journal set, checkpoint unset
**Intent:** The state `PersistenceProperties`' javadoc names: *"comes back knowing every question
and none of the answers"*.
**Falsifier:** state survives, or the query does not.
**Setup:** `journal: $QA/journal`, `checkpoint.directory: ""`.
**Steps:** start; register `QW`; feed; confirm the canonical three rows; stop; restart; **before
feeding**, `pravaha queries` and `pravaha query --sql "SELECT * FROM QW"`.
**Expected:** `pravaha queries` → **1** query, RUNNING. The view → **0 rows**. The WARN at
`PravahaNode.java:363-365` fired at startup. Then feed `txnA.csv` again and confirm the canonical
three rows return — the warm-up, not an outage, that `OPERATIONS.md:367-369` describes.
**Vacuity:** the read happens **before** any row is fed, so a pass cannot come from re-ingestion.

## CFG-095 — checkpoint set, journal unset
**Intent:** The mirror image, and the more surprising one: state is written and there is nothing to
restore it into.
**Falsifier:** the orphaned checkpoint files are cleaned up, or the query comes back.
**Setup:** `journal: ""`, `checkpoint.directory: $QA/ckpt`, `interval: 2s`.
**Steps:** as CFG-094, plus `ls -R $QA/ckpt` before and after the restart.
**Expected:** checkpoint files exist before the restart and **still exist after it**, orphaned —
the query they belong to is gone, so nothing will ever read them and nothing will ever prune them.
`pravaha queries` → 0. **Disk grows by one abandoned checkpoint set per restart**, and
`TROUBLESHOOTING.md` already names checkpoint files as the known disk-growth path. Confirm whether
anything cleans them; expect not.

## CFG-096 — both set
**Intent:** The intended production configuration.
**Falsifier:** the view is empty after the restart, or the per-query subdirectory name changes across it.
**Expected:** after restart and **before feeding**: `pravaha queries` → 1; the view → the three
canonical rows `u0=5, u1=7, u2=3`. Confirm the per-query subdirectory naming under `$QA/ckpt` is
stable across the restart, because a name derived from anything volatile would silently orphan the
state exactly as CFG-095 does.

## CFG-097 — neither set
**Intent:** The development default: neither durability mechanism on, and both warnings present so the operator knows which of CFG-094 and CFG-095 they are not in.
**Falsifier:** fewer than two warnings at startup.
**Expected:** two WARNs at startup (`PravahaNode.java:363` and `:413`); after restart, 0 queries and
0 rows. The development default. Both warnings must be present — one without the other is the
configuration in CFG-094 or CFG-095 and the operator needs to know which.

## CFG-098 — checkpoint directory shared between two nodes
**Intent:** `application.yaml` argues that each query checkpoints into its own directory beneath the
root so that pruning is per query. Two **nodes** sharing a root is the case it does not address.
**Falsifier:** two nodes sharing a checkpoint root keep their state separate.
**Setup:** two nodes, ports 18800/19800 and 18801/19801, different `pravaha.node.id`, the **same**
`checkpoint.directory` and **different** journals; register a query with the same name on both.
**Expected:** record whether the per-query subdirectory is namespaced by node id. If it is not, two
nodes write the same directory and `prune(keep)` on one deletes the other's fallbacks. With
`keep: 3`, `interval: 2s` and two nodes, each node takes ~10 checkpoints in 20 s and each prunes to
3 — so the directory holds 3 files belonging to an unpredictable mix of two nodes. **A restore then
reads another node's state.** That is the finding if the namespacing is absent.

## CFG-099 — journal shared between two nodes
**Intent:** Two nodes appending to one journal file, which nothing in the configuration forbids.
**Falsifier:** the journal is locked, or interleaved appends replay cleanly on both nodes.
**Setup:** as CFG-098 but sharing the **journal** and not the checkpoint directory.
**Expected:** both nodes append to one file. Record whether `RegistryJournal` takes a lock. If not,
interleaved appends produce records that neither node can replay, which surfaces as `PRV-8005` on
the next restart of both. Confirm by restarting one.

## CFG-100 — journal permissions change while running
**Intent:** The journal becoming unwritable under a running node -- a full disk or a permissions change -- and whether a registration is refused or acknowledged.
**Falsifier:** the registration succeeds, or `pravaha queries` shows it afterwards.
**Setup:** journal set and working; after one successful registration, `chmod 400` the journal file.
**Steps:** register a second query.
**Expected:** `PRV-8006 REGISTRY_JOURNAL_UNWRITABLE` and **the registration refused** —
`OPERATIONS.md:379` states acknowledging one that will not survive a restart tells the client
something untrue. Confirm `pravaha queries` then shows **1**, not 2. Then `chmod 600` and register
again: it must succeed, with no residue from the failed attempt.
**Vacuity:** the count is read after the failure, so a pass requires the refusal to have been total
— a query that registered in memory and failed only to journal would show 2.

## CFG-101 — checkpoint directory permissions change while running
**Intent:** The checkpoint directory becoming unwritable under a running node, and whether anything an operator watches moves.
**Falsifier:** a failing checkpointer is visible in a metric, a query state, or health.
**Setup:** `checkpoint.directory` set and working, `interval: 2s`; after the first checkpoint,
`chmod 500` the per-query directory.
**Expected:** `PeriodicCheckpointer.checkpointQuietly` is named "quietly" — record exactly what it
does: a WARN, a metric, a query failure, or silence. **Silence is the finding**, because the
operator's next restart is the one that discovers it, and `pravaha_query_running` will still read 1.
Confirm which of the seven gauges (`PravahaMetrics.java:120-135`) moves. Expect none.

## CFG-102 — `keep` reduced across a restart
**Intent:** Whether `keep` is applied to what a previous run wrote, and whether checkpoint ids stay monotonic across a restart.
**Falsifier:** the old run's files survive a smaller `keep`, or an id is reused.
**Setup:** run with `keep: 5` and `interval: 2s` for 20 s (expect 5 files); stop; restart with
`keep: 1`.
**Expected:** after the first checkpoint on the new run, `prune(1)` leaves **1** file — the four
older ones are deleted, including the ones written by the previous run. Confirm the id numbering is
monotonic across the restart: `PeriodicCheckpointer`'s constructor resumes above the highest stored
id (`PeriodicCheckpointer.java:107-108`), so *"checkpoint 4" means one thing for the life of the
directory*. Check the ids on disk before and after and confirm no id is reused.
**Vacuity:** the file count `5 → 1` and the id sequence are two independent observations of the same
restart; a build that recreated the store from scratch would restart ids at 1 and pass the count
check while failing the id check.

---

## Flight off, and what a monitoring system then believes

## CFG-103 — `flight.enabled: false` — what is still reachable
**Intent:** Establish the blast radius before asking about health.
**Falsifier:** any client surface still works, or any `/api/v1` path stops working.
**Setup:** `base.yaml` with `pravaha.flight.enabled: false`.
**Steps:** start; then, in order: `GET /api/v1/status`, `GET /api/v1/streams`,
`POST /api/v1/streams`, `POST /api/v1/queries/validate`, `POST /api/v1/queries/explain`,
`GET /actuator/prometheus`; then `pravaha queries`, `pravaha register`, `pravaha query`,
`pravaha subscribe`, the Java SDK, the Java Flight SDK, the Python SDK, and the console.
**Expected:** every `/api/v1` and `/actuator` path answers normally. **Every client — both SDKs, all
nine CLI commands that talk to a server, and the console — fails to connect**, because they all
speak Flight and nothing else (ADR-030). `/api/v1/status` reports `flight: disabled`
(`PravahaNode.java:504`). There is **no** REST path to register or query a continuous query:
`/api/v1/queries/validate` and `/explain` do not register, and there is no `POST /api/v1/queries`.
So the node can be described, inspected and monitored, and cannot be used.
**Vacuity:** the list is exhaustive by construction — it enumerates every mapped path
(`StreamController`, `QueryController`, `StatusController`) and every CLI verb.

## CFG-104 — `flight.enabled: false` and `/actuator/health`
**Intent:** Fact 15. The indicator exists and returns DOWN; its javadoc records the state in which
it did not.
**Falsifier:** `/actuator/health` returns UP.
**Steps:** start; `GET /actuator/health`; record status and the whole `components` map.
**Expected:** **`"status": "DOWN"`**, with `flight: not listening; no client can reach this node`
and the `queries`/`failedQueries` details alongside. `diskSpace` and `ping` are UP; the aggregate is
DOWN. **If it returns UP, the defect described in `EngineHealthIndicator`'s javadoc is present and
is the most important finding in this file.**
**Vacuity:** run the same request against the same node with `flight.enabled: true` first and
confirm UP, so a DOWN here is caused by the key and not by an unrelated contributor.

## CFG-105 — `flight.enabled: false` and the probes an orchestrator reads
**Intent:** CFG-043's load-bearing question, run against the state that matters.
**Falsifier:** `/actuator/health/readiness` returns UP while `/actuator/health` returns DOWN.
**Steps:** with `flight.enabled: false` and `management.endpoint.health.probes.enabled: true`,
`GET /actuator/health/liveness` and `GET /actuator/health/readiness`.
**Expected:** liveness UP is correct — the JVM is alive and restarting it would not help. **Readiness
must be DOWN**, because a node no client can reach must leave the load-balancer's rotation. By
default the readiness group contains only `readinessState` and `application.yaml` adds no
`management.endpoint.health.group.readiness.include`. If readiness is UP: **an orchestrator keeps an
unusable node in rotation, which is exactly the failure the indicator was written to prevent,
surviving in the endpoint orchestrators actually poll.** The fix is one line in `application.yaml`;
propose it in the finding.

---

## Spring profiles

## CFG-106 — no profile
**Intent:** The no-profile baseline, and the log line that tells an operator which files were read.
**Falsifier:** a profile-specific file is applied when no profile is active.
**Expected:** `application.yaml` only. `allow-anonymous: false` → CFG-057's refusal. Startup log
shows `The following 0 profiles are active` or the equivalent for the Boot version; record the exact
line, because it is how an operator confirms which files were read.

## CFG-107 — `--spring.profiles.active=dev`
**Intent:** The `dev` profile applied, as every quickstart instructs.
**Falsifier:** the node is still refused, or differs from CFG-078's diff in any way.
**Expected:** `application-dev.yaml` overlays exactly one key; the node starts open (CFG-078).
`The following 1 profile is active: "dev"`.

## CFG-108 — an unknown profile
**Intent:** What happens when a profile name has no file behind it -- the way a production deployment silently runs on development settings.
**Falsifier:** Spring refuses an unknown profile, or logs that no file was found for it.
**Setup:** `--spring.profiles.active=prod`.
**Expected:** **Spring does not refuse an unknown profile.** No `application-prod.yaml` exists, so
nothing is overlaid and the node behaves exactly as CFG-106 — that is, it is **refused**, for the
security reason, and the operator's conclusion will be that `prod` is broken. Record the log: there
must be a line naming the active profile, and there will be **no** line saying no file was found for
it. Propose that line; a silently ignored profile name is how a production deployment runs on
development settings.

## CFG-109 — several profiles at once
**Intent:** Profile precedence, which is the whole semantics of running more than one and is discoverable from no document in this repository.
**Falsifier:** `dev,prod` and `prod,dev` behave identically when both files set the same key to different values.
**Setup:** `--spring.profiles.active=dev,prod`; then `prod,dev`; then `dev,dev`.
**Expected:** later profiles win for overlapping keys. Only `dev` has a file, so all three start
open and identically. Record the active-profile log line for each. Then the real test: create
`$QA/conf/application-prod.yaml` containing `pravaha.security.allow-anonymous: false` alongside
`application-dev.yaml`, and confirm `dev,prod` is **refused** while `prod,dev` **starts** — the
ordering is the whole semantics and an operator has no other way to discover it.

## CFG-110 — profile interaction with `--spring.config.additional-location`
**Intent:** Every case in this file uses `--spring.config.additional-location`. Its precedence
against a profile-specific file is the assumption the whole file rests on, so it is checked once,
last, explicitly.
**Falsifier:** `application-dev.yaml` wins over a file given with `--spring.config.additional-location`.
**Setup:** `$QA/conf/cfg.yaml` with `pravaha.security.allow-anonymous: false`; run with
`--spring.profiles.active=dev` (whose packaged file sets it to `true`).
**Steps:** run both ways round: additional-location with the `dev` profile, and the `dev` profile
alone.
**Expected:** an `additional-location` file is added **after** the packaged locations, so it wins
over `application-dev.yaml` and the node is **refused**. If it does not win, **every earlier case in
this file that set a key which `application-dev.yaml` also sets was testing the wrong value**, and
the executor must say so before reporting any other result. Confirm also that
`--spring.config.additional-location` adds to, rather than replaces, the packaged
`application.yaml` — `spring.config.location` would replace it, and the difference is whether
`pravaha.flight.port: 9090` and the rest of the packaged defaults are still present.

---

## Coverage note

**110 cases, the budgeted number**, but the budget's shape is worth recording.

The index budgets `CFG` at "37 config keys × valid / invalid / default / interaction". The count is
close: **38 `pravaha.*` settings** exist — 36 YAML keys plus the two system properties
`pravaha.ffm` and `pravaha.memory` — and nine further Spring keys live in the same file and are
edited by the same hand. Read literally, "37 keys × 5 variants" is 185 cases before a single
interaction, and the interactions are where every defect in this file was found.

So the inventory is compressed: **CFG-001 to CFG-047 is one case per key with its five variants
enumerated as a table inside the case.** A startup-configuration key's five variants are five node
starts in one sitting against one config file, and splitting each into five numbered cases would
have produced 235 cases that say the same thing at a fifth the density. Every variant the brief asks
for — default when unset, a valid non-default that is *observably* different, an invalid value, a
boundary, and the wrong type — appears in every one of those 47 tables, and each table names the
observation rather than asserting "it works".

That compression bought 63 cases for the interactions, which is where they belong:

| Section | Cases | What it buys |
|---|---|---|
| Keys the product names and does not have | CFG-048–056 | `arena.slab.size` (six error messages), `state.slab.size`, and the whole `pravaha.runtime.*` / `pravaha.state.*` / lane tier |
| Security, four keys in three places | CFG-057–080 | the full 2×2×2, the two refusals, and the 3×3 TLS pair |
| The two out-of-orderness keys | CFG-081–086 | the pair run back to back, 6 rows against 0 |
| `streams` × `sources` | CFG-087–093 | both directions, and the typo that produces neither error |
| Persistence end to end | CFG-094–102 | the four combinations, two nodes, and permissions changing under a running node |
| Flight off | CFG-103–105 | blast radius, health, and the probe an orchestrator actually reads |
| Profiles | CFG-106–110 | including the precedence assumption every other case rests on |

**Three things were deliberately not written here.**

1. **Deep behaviour of `pravaha.cluster.*` beyond reachability.** CFG-030 to CFG-036 establish that
   seven cluster keys cannot reach their readers. Until that is fixed, cases about heartbeat
   intervals and session timeouts would be untestable, and writing them now would produce seven
   cases that all fail for one reason. `STATE` owns cluster modes; this file hands it the blocker.
2. **The `AERO`, `JDBC` and `DELTA` plugins' own option keys.** They are `pravaha.sources.<n>.options.*`
   values passed to a plugin untouched, and `AERO` budgets 45 cases for one of them. CFG-011 pins
   the contract — untouched, undeclared, unvalidated by the server — and leaves the contents to
   the plugin areas.
3. **`pravaha.security.tokens.*` as an authentication mechanism.** `SECX` owns policy × auth × TLS ×
   row filters × every verb on both transports, with 95 cases. This file owns whether the *keys*
   bind, default and refuse correctly, and stops at the transport boundary. CFG-079 is the one
   deliberate exception, because the divergence it finds is visible only when you hold the
   configuration still and change the transport.

**Two cases here are expected to fail as written, and should be run anyway.** CFG-071 asserts a raw
`NullPointerException`, and CFG-023's `keep: 0` row asserts a failure at first registration rather
than at startup. Both are written as the behaviour the code has, not the behaviour it should have,
so that the executor's log records the defect rather than an ambiguous "did not match expected".
Each names the correct behaviour in its Expected block.
