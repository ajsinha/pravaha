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
   (`StreamDeclarationProperties.java:32-33`, `SourceBindingProperties.java:29-30`,
   `PersistenceProperties.java:28-29`, `SecurityProperties.java:45-46`). The odd-looking choice is
   deliberate and documented at `SourceBindingProperties.java:53-56`: `prefix = "pravaha.sources"`
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
   (`SecurityProperties.java:118-120`). **Every other string, including `"tokens"`, `"TOKEN "` with
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
9. `PersistenceProperties.checkpointConfiguration()` builds a `Configuration` containing exactly two
   keys — `pravaha.checkpoint.interval` (in nanoseconds, deliberately: `PersistenceProperties.java:64-79`)
   and `pravaha.checkpoint.keep`. `PeriodicCheckpointer.from` reads **three**:
   interval, keep and `pravaha.checkpoint.timeout` (`PeriodicCheckpointer.java:122-126`).
   **`pravaha.checkpoint.timeout` is therefore inert on the server path** and always falls back to
   `DEFAULT_TIMEOUT = 30s` (`PeriodicCheckpointer.java:67`).
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
    `Configuration`: `pravaha.node.id` (`PravahaServerApplication.java:50-53`). Every engine-level
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
    (`LaneConfig.java:83-95`). There is no `pravaha.lane.*` key.
15. `EngineHealthIndicator` returns **DOWN** when `node.flightPort()` is empty — which is exactly
    the `pravaha.flight.enabled=false` case (`EngineHealthIndicator.java:69-76`). Its own javadoc
    records that this was once UP. `management.endpoint.health.probes.enabled=true` adds
    `/actuator/health/liveness` and `/actuator/health/readiness`; whether a custom indicator
    contributes to the **readiness group** is the thing to check, not to assume.
16. `Configuration`'s own parsers refuse rather than guess: a bare number is not a duration
    (`ConfigParsers.java:67-71`), booleans accept `true/false, yes/no, on/off, 1/0`
    (`ConfigParsers.java:35-41`), data sizes are binary (`ConfigParsers.java:95-98`). These raise
    `PRV-102n`. **Spring's binder, which is what reads `application.yaml`, is a different parser**
    with different rules — `Duration` accepts ISO-8601 `PT30S` and bare `30` (as milliseconds by
    default). Two duration dialects meet in this file; `PersistenceProperties.java:64-71` records
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
otherwise. Every case that expects a **refusal** expects it to be visible in the process's exit
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
And it does not later advance by idle exclusion, because `QueryExecution.advanceWarermarkQuietly`
calls `observe` on every partition on every tick, and `WatermarkTracker.observe` sets
`idle = false` — so a partition whose file is exhausted never goes idle (TIME.md facts 8 and 9).

**The canonical assertion is therefore three rows**: `(T0+0, T0+5, u0, 5)`, `(T0+0, T0+5, u1, 7)`,
`(T0+0, T0+5, u2, 3)`. Any case below that says "QW delivers its canonical result" means exactly
those three rows and no fourth.

---
