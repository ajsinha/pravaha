# Every open finding, checked against the code of 2026-09-19

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../../LICENSE`](../../../LICENSE).

> **What this is.** All 102 findings that [`FINDINGS.md`](FINDINGS.md) records as `OPEN` were read
> and checked against the tree, not against another document. This file carries one verdict each,
> so the lead can apply statuses from it. **It does not edit `FINDINGS.md`**, which is the lead's
> alone and whose counts have to stay trustworthy.
>
> **Why it was worth doing.** Many entries were written weeks ago and the engine has changed
> enormously since — in the last three days alone it gained lane sharing by stream, the spill tier
> past RAM, pushdown, PostgreSQL CDC, a Kafka source, snapshot subscriptions, feed status, the
> `HLP-*` usability series and about twenty defect fixes. **Twenty of the 102 are no longer true**,
> and two more describe behaviour that has moved far enough that the entry as written misleads. A
> register that says 102 when 80 is the number is not one anybody can plan from.

## Counts

| Verdict | Count | |
|---|---|---|
| **STILL OPEN** | 80 | Reproduces, or the mechanism it names is unchanged in the tree |
| **ALREADY FIXED** | 20 | No longer true. The code that fixed it is named, with its test |
| **NOW WRONG** | 2 | Changed enough that the entry as written would send a reader the wrong way |
| **UNCLEAR** | 0 | Every headline was decidable from the code; five *measurements* were not re-run, listed at the end |
| | **102** | |

Six of the 20 were fixed **in this pass** — each small, each with a test, each seed-proven. They
are counted as ALREADY FIXED because they are no longer true of the tree the lead will merge. A
seventh fix, `CFG-10`(b), closes half of a finding whose other half is open, so `CFG-10` stays in
the open column.

**Twelve status lines cite evidence that has moved on**, including for findings that are still
open. They are flagged *stale evidence* below. A status line naming a mechanism that no longer
exists is how a register stops being checked.

---

## Fixed in this pass

Each was verified still open first, then fixed with a test, then seed-proven — fix reverted, test
watched to fail, fix restored.

| Finding | Commit | What changed |
|---|---|---|
| `P-4` (+ `P-7`'s last bullet) | *Every CLI command answers --help, offline* | Per-command `--help`, resolved before any command object is constructed, so nothing dials out. `PravahaCliTest` drives every command in the table with no server running and no `--url`, so anything reaching the network fails the test |
| `API-F11` | *The docs UI is open where the server sends the reader* | `/api/swagger-ui` added to `BearerTokenFilter.OPEN_PREFIXES`; the redirect from the deliberately-open `/api/docs` no longer lands on a 401. `/api/v1/streams` and `/api/v1/queries` are the control |
| `PF-3` (+ `DOCX-20`'s middle bullet) | *The last two messages naming a setting that does not exist* | `state.slab.size` and `lane.exchange.cell.size` were phantom keys that slipped past PF-3's own guard by not starting `pravaha.`. One now names the real key; one stops naming any. The guard is widened for engine modules |
| `CFG-10`(b) | *A node that can verify no credential says so at startup* | `authentication: token` with an empty token table now warns at startup instead of at the first 401, which is the support ticket the code's own comment says it wants to avoid |
| `SX-19` | *validate and explain name the stream you gave them* | The CLI restores the stream name the planner withholds, on the two commands where the caller typed it seconds earlier and no server is involved |
| `API-F3` | *An open failure says "permission denied" when that is what it was* | The sink's open failure now carries the OS reason as the reader's does, and `AccessDeniedException` — whose message is the path and whose reason is null — says "permission denied" and where to look, on both sides |
| `E-9` | *The netty conflict two ERRC comments describe is gone* | Comments only: the conflict itself was already gone. Verified by `dependency:tree` and by an in-process `FlightClient` that passes |

---

## ALREADY FIXED — 20

The part the lead needs most. Each names the code that fixes it and the test that would fail if it
regressed; where there is no such test, it says so.

### `P-4` — no command has help, and one of them makes a network call to say so
Fixed in this pass. `PravahaCli.run` resolves `--help`/`-h`/`help` against a per-command usage
table before the `switch`, so `queries --help` no longer opens a Flight connection and the six
commands that answered "missing required option" now answer with their flags.
**Test:** `PravahaCliTest.everyCommandPrintsItsOwnFlagsWithoutTouchingTheNetwork` and
`oneCommandsHelpIsNotEveryCommandsHelp`. Seed-proven.

### `P-7` — the smallest one, and it ships
`ServerCommand.pastTense` (HLP-10) replaced `action + "ped"`, so `pause` prints `paused`.
**Test:** `ServerCommandTest.eachLifecycleCommandSaysWhatItDidInEnglish`.
Its other two bullets are closed too: QUICKSTART §2's `pravaha run` now passes `--out-schema` with
a matching schema, and `QueryRunner`'s javadoc no longer offers a `--lanes` flag `RunCommand` has
never parsed (fixed in this pass).

### `X-5` — `DelegatingRowWriter.abort()` masks the decode diagnostic
`abort()` no longer throws `UnsupportedOperationException` unconditionally: it delegates and runs
an `onAbort` hook (`DelegatingRowWriter:200-211`). `FilesystemPartitionReader:299-318` now offers
the row to the dead-letter queue **before** abandoning it, with the reasoning spelled out — aborting
first "meant its 'report this' message replaced the decode failure that caused it". With no DLQ
attached the poll still fails and the batch is still lost, which is `I-2`'s shape and deliberate
("a record is not dropped just because nobody arranged somewhere to put it").
**Test:** the DLQ path in `pravaha-runtime`'s ingest tests; no test pins the diagnostic ordering.

### `E-9` — `pravaha-server` and the Flight client SDK cannot share a classpath
`dependency:tree` on `pravaha-it` now reports `netty-buffer`, `netty-common`, `netty-handler`,
`netty-transport` and `netty-resolver` all at `4.2.9.Final`; the `4.1.135` line the finding names
is not on the classpath. Proved by construction rather than by version arithmetic:
`SinkDeliveryEndToEndTest` and `ContinuousStatementEndToEndTest` build a `PravahaFlightClient`
in-process on that classpath. Run here: 2 tests, BUILD SUCCESS. The product risk the finding flags
— an embedded gateway holding both — goes with it.
**Test:** `SinkDeliveryEndToEndTest` is the regression test, by being what the finding said cannot
work. Its stale comments are corrected in this pass.

### `API-F3` — `run`'s open-failure messages never include the underlying OS cause
Fixed in this pass, both named gaps. `FilesystemSinkPlugin.open` now appends
`FilesystemPartitionReader.why(e)`, and `why` gives `AccessDeniedException` — whose message is the
path and whose reason is null — the sentence "permission denied. A node reads and writes as the
user it runs as, so check the file's owner and mode and its directory's". API-048's `--in` naming
a directory already names the path and cause through `why` on the read side.
**Test:** `FilesystemPluginTest.aSinkThatCannotOpenSaysWhatTheOperatingSystemSaid`, driving a real
read-only directory. Seed-proven.

### `API-F11` — the Swagger UI page is behind authentication
Fixed in this pass. `OPEN_PREFIXES` now carries `/api/swagger-ui` beside `/swagger-ui`; the
prefixes are matched against `getRequestURI()` and springdoc serves the UI's resources under the
configured path's parent.
**Test:** `ServerSecurityTest.theDocsUiIsOpenAtTheAddressTheServerRedirectsTo`. Seed-proven.

### `SX-19` — SX-5's catalogue suppression costs the CLI a diagnosis it can safely give
Fixed in this pass, by the route the finding itself proposes. `validate` and `explain` catch the
planner's withheld-catalogue refusal and replace its server sentence with their own naming the
stream the caller passed in.
**Test:** `PravahaCliTest.anOfflineRefusalNamesTheStreamTheCallerGaveIt`. Seed-proven.

### `PF-1` — `lane-scaling.json` records a method that no longer exists
`benchmarks/results/` no longer exists; `benchmarks/baselines/` holds `memory-access.json` and
`profile-a.json`. The unreproducible baseline was deleted, which is one of the two remedies the
finding names. **No test**, and none is cheap: the honest guard would be one that re-runs JMH,
which is `PF-2`'s subject and still open.

### `PF-3` — eleven error messages tell an operator to change a setting that does not exist
The eleven named real keys as of ADR-036. Two more survived by not starting `pravaha.` and are
fixed in this pass. `pravaha.lane.count` is now named in no message at all, and
`pravaha.lane.multiplex.lanes` is the key that sets a lane count.
**Test:** `DocumentationFreshnessTest.everySettingAnErrorMessageTellsYouToChangeExists` plus the
new `anEngineMessageDoesNotInventASettingByLeavingOffThePrefix`, which caught `state.slab.size` by
name while it was still in the tree.

### `DOCX-7` — the only working lateness control is documented nowhere by name
`docs/operations/OPERATIONS.md`'s *Running against a source that does not end* names
`pravaha.streams.<name>.event-time`, `.out-of-orderness` and `.allowed-lateness` with a worked YAML
block and a paragraph on what each costs in state; `docs/guides/CONCEPTS.md:80` names the per-stream key.
The `pravaha.lookups.<n>.*` half is closed too — `docs/guides/CONTINUOUS_QUERIES.md` §7 documents it.
**No test**; `DocumentationFreshnessTest` cannot fail on a sentence that is merely absent.

### `DOCX-8` — a TLS key with no certificate starts a plaintext node and says nothing
`PravahaNode:945` now calls `encryptedWith` when **either** half is set (CFG-6(a)), and
`PravahaFlightServer.encryptedWith:146` refuses a half-configured pair with a coded
`TLS_UNREADABLE` and the sentence "starting in plaintext because half a setting was missing is how
a deployment that asked for encryption ends up without it" (CFG-6(b)).
**Test:** the CFG-6 cases in `pravaha-server`'s security tests.

### `DOCX-10` — `pravaha pause` and `pravaha resume` print "pauseped" and "resumeped"
Same fix and same test as `P-7`.

### `DOCR-16` — ADR-035 promises Wave 8 a gate pack and there is none
`docs/project/gates/wave-8/README.md` exists — Gate P7, written 2026-09-15 retrospectively, verdict "Wave
complete. Gate P7 passed", with the `SIGKILL`ed-process demonstration and the defect (`W8-15`) that
demonstrating it found. `docs/project/gates/wave-9` exists beside it.
**Test:** `DocumentationFreshnessTest.theReadmeStatedWaveMatchesTheNewestRecordedGate` now compares
against a newest gate of 9 rather than 7, so the one-directional assertion the finding complains
about has stopped being vacuous.

### `TIME-7` — there is no way to set allowed lateness, or retention, on a server
**Stale twice over.** *Allowed lateness*: `pravaha.streams.<name>.allowed-lateness`
(`StreamDeclarationProperties.Declaration.allowedLateness`) and `allowedLateness` on
`POST`/`GET /api/v1/streams` (`StreamController:134`, `ApiDtos:56`, `DtoMapper:57`), refused without
an event time and refused negative (`StreamCatalog.withEventTime`, HLP-7). *Retention*: a
registration's fifth control-wire field (`PravahaFlightSqlProducer.retentionOf:626`),
`pravaha register --retain`, both SDKs, journalled by `RegistryJournal.encodeRetention`, and
reported on `GET /api/v1/queries` and `/api/v1/views/{name}`. `QueryRegistry.retaining` is no longer
the only setter and no longer callerless.
**Tests:** `StreamAllowedLatenessTest` shows a configured 30 s reaching the running windowed
aggregate, seed-proven; `JavaSdkRegistryTest` asserts `retention() == "PT1H"` and the refusal of a
value that is not one.

### `TIME-10` — `CONCEPTS.md` promises a correction a `TUMBLE` query cannot produce
Both sentences are corrected. §3 now reads "Allowed lateness defaults to zero, so unless a stream
declares it a row arriving after its window closed is counted as late and dropped", and names the
two server settings that make the correction path reachable; the dead-key sentence is now "(The
engine-wide `pravaha.watermark.out-of-orderness` key is in the shipped `application.yaml` and is
read by nothing — DOCX-6.)"
**No test.** `DocumentationFreshnessTest` cannot fail on a sentence that is merely untrue, which is
this finding's own observation.

### `STRM-3` — the CLI renders a retraction and an insertion as byte-identical lines
`ServerCommand:356` is now `weightText(row.weight()) + "\t" + render(row)` (HLP-11), and the
`subscribe` usage text says what the sign means.
**Test:** `ServerCommandTest.aWeightIsAlwaysSigned`.

### `STRM-6` — read-then-subscribe has a hole, and the frontier is discarded
`SUB-1` added snapshot subscriptions end to end:
`PravahaFlightSqlProducer.subscribeFromSnapshot:930`, `ControlWire.SUBSCRIBE_FROM_SNAPSHOT`,
`RegisteredQuery.subscribeFromSnapshot`, `PravahaEngine.subscribeFromSnapshot`, and
`pravaha subscribe --snapshot`. The snapshot is queued first into an empty queue and every commit
after it follows with nothing between, which closes the hole rather than measuring it. The frontier
is no longer computed and discarded either: the SDK's `ChangeBatch(rows, snapshot, frontier)`
carries it, so a client can prove it missed nothing.
**Tests:** `SnapshotSubscriberBehindTest`, `FlightRegistryTest`.

### `STRM-7` — a subscription filter on a non-string column silently matches nothing
`SubscriptionFilter.asColumnValue:111` (HLP-9) reads the wire's text as the column's declared type
— every numeric width, `BOOLEAN`, `DATE`/`TIME`/`TIMESTAMP`, `DECIMAL` at its scale — and refuses a
value that is not one, with the symmetry the finding asked for: "A filter that quietly matched
nothing would look like a quiet view".
**Test:** the HLP-9 cases in `SubscriptionTest`.

### `STRM-13` — two configuration surfaces say row filters are honoured on subscribe
Both surfaces are corrected: `application.yaml:171` and `SecurityProperties`'s class javadoc now
say subscribe is the one path that **refuses** a principal carrying a row filter. The gap the
finding was really about — that nothing a user reads said it — is closed: `docs/operations/SECURITY.md` has a
section, *A conditional entitlement cannot subscribe*, stating it and giving the remedy.
**No test** pins prose.

### `STRM-19` — `DECIMAL(p,s)` cannot be written in the schema grammar
`FilesystemSourcePlugin.splitColumns:152` (TY-7) splits on commas at paren depth zero, so
`amt:DECIMAL(18,2)` survives through every surface that grammar reaches —
`pravaha.streams.*.schema`, `POST /api/v1/streams`, `--schema`, `--out-schema`. The refusal message
that listed `DECIMAL(p,s)` as supported is now telling the truth about this door as well as about
`typeFor`.
**Test:** the TY-7 cases in `FilesystemPluginTest`.

---

## NOW WRONG — 2

### `T-5` — per-plugin event time, measured
Three of the table's five rows have changed, and the table is what the entry is.
`FeedFilePartitionReader:131` reads `decoder.lastEventTimeNanos()` with HLP-6's reasoning —
"stamping zero regardless kept the watermark in 1970, and no event-time window ever closed";
`DeltaPartitionReader:338` passes a computed `eventTime`; `aerospike` was already recorded fixed.
**Only the `jdbc` row survives**: `JdbcPartitionReader.emit:213` still does
`.eventTimestampNanos(watermark)` where `watermark = results.getLong(watermarkIndex)` — raw, so an
epoch-millis column is still out by 10⁶, silently. Worth keeping as a one-row `jdbc` finding; the
table as printed is wrong in three places.

### `TIME-8` — nothing reports a partition's idle state … and there is no query listing in the REST API at all
The heading's second clause is false. `GET /api/v1/queries` and `GET /api/v1/queries/{name}` exist
(`QueryController:260,279`) and return a `QueryDetail` carrying name, state, sql, fingerprint, the
names sharing the computation, key columns, retention, sink, rows in, registered-at, failure, what
it reads and its feed; `GET /api/v1/queries/{name}/plan` sits beside them, and `/api/v1/views`,
`/api/v1/sinks`, `/api/v1/plugins`, `/api/v1/audit` and `/api/v1/permissions` exist too. The
six-path OpenAPI list the finding quotes is far out of date, and a reader sent looking for a 404
finds a 200.
**The first clause is still true and should be re-filed on its own:** `WatermarkTracker.isIdle`,
`idleExclusions()` and `regressions()` have no caller outside the class.

---

## STILL OPEN — 80

One line of evidence each: the file and method that still does it.

### Round 1 and the source-reading rounds — 17

| Finding | Evidence |
|---|---|
| `T-6` | `pravaha.watermark.out-of-orderness` still has no reader: the literal appears in `src/main` only in `StreamSchema.java:76`'s javadoc. **Stale evidence:** the second half is fixed — `StreamCatalog.withEventTime` now *refuses* an out-of-orderness with no event-time column instead of discarding it, and `SharedClock.every` catches `Throwable`, so an `Error` no longer cancels the tick silently |
| `C-5` | `FilterProjectGenerator.emitProjection:200-224` still emits only `out.putX(...)` per column and never copies a null bit. **Stale evidence:** `GeneratedStageTest.theDifferentialTestCatchesAGeneratedBug` does now compile a real generated stage with its comparison inverted, so "never invokes the generator" is no longer true; the projection still has no nullable column |
| `C-7` | `OrphanedClassTest`'s `KNOWN` still lists `Lift`, `Frontier`, `IncrementalJoin`, `Differentiate`, `StageUpgradeService`; no `import com.ash.messaging.pravaha.algebra` exists outside `pravaha-algebra`; `AdaptiveStage`/`StageUpgradeService` are referenced only from `pravaha-codegen`'s tests. **Stale evidence:** `PravahaEngine` is no longer nine methods — it registers, reads, subscribes and pushes |
| `W-5` | NOTE. Both corrections hold: `WindowSpec`'s javadoc states the `gcd` slice rule, and the four silent-stop mechanisms still surface with nothing but a thread dump |
| `W-6` | NOTE. `CUMULATE` appears in no `src/main` file, so it still falls to a `default` refusal with no PRV code; `WindowSpec:49` still throws a bare `IllegalArgumentException` for `slide > size` |
| `Y-5` | `Expression.java:391` still recommends `CAST(… AS VARCHAR)`, and `ExpressionCompiler.cast` still refuses a cast to or from text (its own javadoc at `:348` says so) |
| `Y-6` | `Expression.Substring:472` counts with `codePointCount`; `Predicate:265`'s LIKE translator still walks Java `char`s |
| `Y-8` | NOTE. The generated path is still the largest untested area; `API-F2` corroborates rather than resolves |
| `S-5` | NOTE. **Stale evidence:** the per-query `ScheduledExecutorService` is gone — `QueryExecution:557` uses `SharedClock.every`, one process-wide timer firing on virtual threads (W9-3). The substance survives: `PumpingFeed:142` still parks 1 ms after an empty poll, so a quiet query still costs about a thousand wake-ups a second |
| `L-3` | `ViewQuery` still re-plans on a cache miss, so a read landing in the gap between a drop and a re-register still gets the planner's `PRV-2002` and not `PRV-4023` |
| `X-3` | NOTE. `QueryRunner:156` still configures the sink from the unchecked `--out-schema` string while the collector is built from `plan.outputSchema()`, and nothing cross-checks them |
| `X-6` | Unchanged: nothing handles a `SqlDynamicParam` as a sort key before Calcite's own refusal; `ParameterMetadata`/`ParameterPlacement` only see `RexDynamicParam` after validation |
| `X-7` | `PhysicalPlanBuilder:390 buildLookupJoin(Correlate)` still holds the good message, and `PredicateCompiler`/`ExpressionCompiler` still refuse both ordinary correlated shapes earlier and more generically |
| `X-9` | Unchanged: `ParameterMetadata.collect` is a post-plan walk, so a bare `?` Calcite cannot type locally is refused by Calcite first |
| `E-8` | `ConfigResolver.MAX_DEPTH = 32` (`:40`), unchanged, still firing on raw nesting depth at `:60` independently of the `visiting` cycle detector |
| `E-15` | `PravahaFlightSqlProducer` overrides no Flight SQL metadata method — `getPrimaryKeys`, `getSqlInfo`, `getCrossReference` and `beginTransaction` appear nowhere in it, so they still fall through to Arrow's `UNIMPLEMENTED` |
| `J-1` | `JoinSide:201,232` still return early on `!JoinKeys.isMatchable(row, keyOrdinals)`, so a null-keyed left row is never stored and never emitted null-padded |

### API surfaces — 5

| Finding | Evidence |
|---|---|
| `API-F2` | Case-file defect. `FilterProjectGenerator.emitProjection` still refuses a `STRING` projection (`PRV-3101`), so `API.md`'s shared `FILTERSQL` still cannot demonstrate the codegen happy path |
| `API-F6` | Case-file correction. `ViewQuery.relFor` still answers `PRV-4023` only on an empty catalog and defers to the planner otherwise, so `API-152` is still the one the evidence sides with |
| `API-F7` | **Second half only.** `ServerCommand.subscribe:264` still prints "subscribed to …" to stdout inside the try-with-resources, and `connect()` provably returns without reaching the server (`ErrcClientTest.connectBuilderItselfNeverThrowsSynchronouslyForAnUnreachableHost` pins that it must). **First half fixed:** a dead server is now `PRV-1040`, retryable, naming the endpoint (`ServerFailures`, `PravahaFlightClient:184`), asserted by `ErrcClientTest.connectingToADeadLoopbackPortProducesClientConnectFailed` down to `127.0.0.1:19900` and the word "running" |
| `API-F8` | `QueryController:196` is `@RequestParam(defaultValue = "physical") String level`, and Spring substitutes the default for an empty value as well as an absent one, so `?level=` still answers 200 `physical` while `?level=PHYSICAL` is refused |
| `API-F10` | Case-file correction. Nothing in the deserializer rejects a lone unpaired surrogate |

### Types and expressions — 11

| Finding | Evidence |
|---|---|
| `TY-4` | `ExpressionCompiler.literal` still casts to `BigDecimal` unconditionally; `Expression.Case`'s compact constructor still throws a raw `IllegalArgumentException`, unwrapped |
| `TY-5` | `PredicateCompiler.nullCheck:275` still requires `instanceof RexInputRef` and calls `unsupported(call)` otherwise |
| `TY-8` | `ApiExceptionHandler.statusFor:80` still maps `PLUGIN` to `INTERNAL_SERVER_ERROR`, and `DelimitedCodec.DECODE_FAILED` (5040) is PLUGIN |
| `TY-9` | `PravahaNode.registerDeclaredStreams:475` still calls `parseSchema(name, declaration.getSchema())` with no added context, and the refusal names neither the stream nor the column |
| `TY-10` | `TypeMapping.baseFromCalcite`'s default still throws `PRV-2021` with only the SQL type name, while its caller holds `field.getName()` |
| `TY-14` | Unchanged: `BYTES` maps to a real Calcite `VARBINARY`, the implicit CAST is inserted, and the generic expression refusal fires before the column-naming path |
| `TY-16` | Unchanged: the implicit `CAST(s AS DECIMAL(38,19))` ahead of `SUM`/`AVG` still trips the DECIMAL guard rather than a type-mismatch refusal |
| `TY-20` | `PhysicalPlanBuilder.build`'s switch still has no `Sort` case, and Calcite still drops an unlimited sort inside a derived table before anything reaches it |
| `TY-22` | `Expression.Substring.evaluateString:477` is still `until = from + Math.max(0L, length…)` in `long`, so `1 + Long.MAX_VALUE` wraps to `Long.MIN_VALUE` and the range is empty |
| `TY-23` | Unchanged: constant folding still happens before `ExpressionCompiler.cast`'s check, and no custom `SqlOperatorTable` registers `CONCAT` |
| `TY-24` | Unchanged: all five shapes are intercepted by Calcite's own validator before Pravaha's coded message |

### Security — 5

| Finding | Evidence |
|---|---|
| `SX-9` | `AuditSink.InMemory:63` is still `events.remove(0)` on a `CopyOnWriteArrayList`, an O(n) copy on every append past the limit |
| `SX-13` | `ViewQuery.withRowFilter` is still re-planned from `view.schema()` rather than the alias being read (`:210`, `:458`, `:594`) |
| `SX-14` | `SecurityProperties:263,294` still iterate `tokens.entrySet()` with no key trimming and no duplicate-normalisation check |
| `SX-16` | (a) still true: the producer's `Location` is built from the pre-bind `port` (`PravahaFlightServer:268`), so an ephemeral-port node still advertises `0`. (b) **stale evidence, half fixed**: that same line is now `Location.forGrpcTls` for a TLS node, so `getFlightInfo`'s endpoint is correct; `this.location` and therefore `uri()` (`:284`) are still `forGrpcInsecure` unconditionally |
| `SX-17` | **Stale evidence, one of three fixed**: a certificate with no key no longer throws a raw NPE — `encryptedWith:146` refuses the half-pair with a coded `TLS_UNREADABLE` (CFG-6(b)). Still true: swapped cert/key throw from `builder.useTls` past `start()`'s IOException-only catch, and a mismatched-but-individually-valid pair still starts and fails only at the first handshake |

### Performance and documentation — 6

| Finding | Evidence |
|---|---|
| `PF-2` | `grep -rn "benchmarks.skip" --include=pom.xml` still returns three lines, none of them a plugin `skip` parameter; no workflow in `.github/workflows/` (`fast.yml`, `matrix.yml`, `verify.yml`) invokes JMH or compares a baseline, while `pravaha-benchmarks/pom.xml:11` and `benchmarks/README.md:5` both still claim the gate |
| `PF-11` | `QueryRegistry.require:1304` still refuses with "no query named 'x' is registered" and nothing anywhere prompts the caller toward `pravaha queries` |
| `DOCX-6` | The code half is untouched: the literal `pravaha.watermark.out-of-orderness` appears in `src/main` only in `StreamSchema.java:76`'s javadoc. **Stale evidence:** every document now says so — `application.yaml:436` opens with "NOT READ BY ANYTHING (DOCX-6)", and `CONCEPTS.md` and `OPERATIONS.md` both say it in the same words. The decision the finding asks for — give the key a reader or delete it — is still unmade, and that is all that is left |
| `DOCX-19` | Eighteen `PRV-7002` throw sites remain across four modules, and `PRV-2002` is still worn by `PravahaNode`'s startup configuration refusals |
| `DOCX-20` | Two of three bullets. The `||` refusal at `Expression.java:391` still recommends a CAST the engine refuses, and the unbounded-`LEFT`-join refusal still says "Swap the inputs and use LEFT". **The middle bullet is fixed in this pass** — the eight messages naming keys that do not exist are down to zero, with a guard that now covers their shape |
| `DOCX-21` | `ErrorCode.java:27` still builds `https://docs.pravaha.io/errors/…`, three tests still assert it, and `ServerCommand.fail:376` still prints the message without the help URL |

### Configuration — 16

| Finding | Evidence |
|---|---|
| `CFG-1` | `CoordinatorFactory.describe` still names the mode and the mechanism and never the member, so `/api/v1/status` remains the only surface carrying the node id |
| `CFG-2` | Unchanged on all four counts: nothing validates `pravaha.flight.port`'s range before gRPC's own argument check; `ApiDtos.Status` still has no port field, so an ephemeral port reaches no served surface; and `Member(nodeId, flightHost, flightPort)` still advertises the host string as given and the port as requested |
| `CFG-3` | (a) unchanged — Spring's relaxed map-key canonicalisation still drops `txn ` and `txnü` with no message at any level. (b) unchanged — `PluginSourceFeeds:63 bindings` is still a `ConcurrentHashMap`, so `sources bound:` is in hash order while `streams declared in configuration:` above it is in file order |
| `CFG-4` | `pravaha-server/pom.xml` still carries only `pravaha-plugin-filesystem`, so `PRV-5090`'s "Available:" list still has one entry, and there is still no documented drop-a-jar-in mechanism (`I-7`) |
| `CFG-7` | Unchanged: `RegistryJournal:268` still creates a missing parent silently, so `PRV-8006` never fires for the commonest typo; nothing validates the checkpoint directory or the journal path at startup |
| `CFG-8` | Nothing in `PravahaNode.start` compares the `pravaha.sources` map with the `pravaha.streams` map, and there is no "declared and unbound" line. **The documentation half is fixed** — see `DOCX-7` |
| `CFG-10` | Half (a) only: `pravaha.security.tokens.x: {}` — a credential with no `id`, which `SecurityProperties:153` documents as meaning "use the map key" — is still discarded by Spring's binder before `verifier()` runs, so it is in the file, absent from the chain and mentioned nowhere. **Half (b) is fixed in this pass**: an empty token table under `authentication: token` now warns at startup (`SecurityProperties.unusableTokenTable`, logged by `PravahaNode`), pinned by `ServerSecurityTest.aNodeThatCanVerifyNoCredentialSaysSoAtStartup` and seed-proven |
| `CFG-11` | `SecurityProperties:242,272` still resolve the principal id as `spec.getId() == null ? entry.getKey() : spec.getId()`, and the map key is the bearer token; `RegistryJournal` still persists the owner id to disk |
| `CFG-15` | `PersistenceProperties.Checkpoint.interval:147` is a bare `Duration` with no `@DurationUnit` and no bound, so `2` is still two milliseconds |
| `CFG-16` | `PersistenceProperties.Checkpoint.keep:148` is still a plain `int` with no validation, and the bound still lives in `PeriodicCheckpointer`'s constructor, which runs per registration |
| `CFG-17` | Case-file staleness, and the three code facts hold exactly as the finding states them: the field exists with a 30 s default (`:149`), `checkpointConfiguration()` emits the key (`:99`), and `PeriodicCheckpointer` reads it |
| `CFG-18` | Case-file correction plus two real findings; `CoordinatorFactory` is unchanged, `mode: HA` is still refused and the mode/mechanism case asymmetry is still there |
| `CFG-19` | No `MeterRegistryCustomizer`, no `commonTags`, no `management.info.*` and no `build-info` goal anywhere in `pravaha-server` or the starter |
| `CFG-20` | No `ErrorController` and no `@ExceptionHandler(Exception.class)` in `pravaha-server`, so 405, 415 and a 404 on an unmapped path still fall through to Spring's own shape |
| `CFG-21` | `SecurityProperties.trimmedAuthentication:211` is still reached first from the filter-registration bean, so its excellent sentence still arrives four `Caused by:` levels under a Tomcat startup failure; no `@PostConstruct`, no `Validator` |
| `CFG-22` | `MemoryAccess.best():67-80` still falls through silently for an unrecognised `-Dpravaha.memory` and still logs nothing about which implementation was chosen. **Stale evidence:** "appear in no document an operator reads" is no longer true — `console/content/topics/settings-index.md:183-184` documents both |

### Event time — 5

| Finding | Evidence |
|---|---|
| `TIME-3` | `StreamDeclarationProperties.Declaration.outOfOrderness:59` still carries no `@DurationUnit` and no bounds, so `60` is still sixty milliseconds and looks correct |
| `TIME-5` | `PravahaNode.start:802` still validates only `idle-after`, by constructing a throwaway `WatermarkTracker`; the `tick <= idle-after` check is still in `QueryExecution.generatingWatermarks:539`, once per registration |
| `TIME-6` | `PhysicalPlanBuilder.requireDeclaredEventTime:786` still returns early when the stream declares no event time — its own javadoc says it is left alone on purpose — so a windowed plan over such a stream still plans, runs, ingests and emits nothing |
| `TIME-9` | `StreamSchema.Builder.build:379,396,416` still throw bare `IllegalArgumentException`s with no PRV code, no configuration key, and no stream name on the TIMESTAMP one |
| `TIME-11` | The clamp moved and survived: `SharedClock.every:83` is `Math.max(1L, period.toMillis())`, nothing looks at the sign, and `PravahaNode:811` still logs the configured duration rather than the effective one |

### Streams and subscriptions — 12

| Finding | Evidence |
|---|---|
| `STRM-1` | `ViewChange.isRetraction():45` is still `weight < 0`; `ServedView.applyWeighted:247` still returns on `weight == 0` without applying anything |
| `STRM-4` | `PravahaFlightSqlProducer:786` still creates a `VectorSchemaRoot` per subscription and writes each batch on that call's own thread, so N subscribers encode the same batch N times. Measurement not re-run |
| `STRM-8` | `ViewSink.commit` still iterates `listeners` serially on the caller's thread; `Subscription.onCommit` still drains its buffer before returning, so the bound is still on a commit rather than on a slow subscriber. Measurement not re-run |
| `STRM-10` | **Stale evidence, half fixed:** a *snapshot* subscriber that falls behind is now told — the stream ends with `FlightErrors.SUBSCRIBER_BEHIND` and a sentence saying to resubscribe (`:806-822`). A plain subscription still discards whole batches through `handover.offer` and still reports `droppedBatches` only to an `AuditSink`, once, at the end (`:891`) |
| `STRM-12` | `listener.completed()` at `:903` is still what an administrative drop, a restart and a client close all look like on the wire; `RegisteredQuery.close():683` still touches no subscription and leaves them in `sink.listeners`, so `subscriberCount()` still never returns to zero |
| `STRM-14` | Unchanged: `drop` removes the name while `removeName` answers false for a shared computation, and the subscriber's loop tests only `query.state().isTerminal()` |
| `STRM-15` | `SUBSCRIPTION_HANDOVER_BATCHES = 64` (`:119`) is still the only thing between a stalled client and the heap, and still denominated in batches. Measurement not re-run |
| `STRM-16` | `streamSubscription` still passes `SubscriptionOptions.DEFAULT`, and `ControlWire.subscribeTicket` still carries no options, so every remote subscriber is still `(10 000, CONFLATE)` |
| `STRM-17` | `RegisteredQuery:342,364,399` still build the refusal from `anyName()`, which falls back to `fingerprint.shortForm()` once the name set is empty |
| `STRM-18` | Case-file defect; the facts it corrects are unchanged |
| `SINK-3` | No `mayWriteTo` exists anywhere in the tree; `QueryRegistry.register` still asks only `mayRegisterQuery` and `mayRead`, and the register audit event still records the SQL and not the sink |
| `CKPT-3` | `GlobalAggregate.emit():325` still writes `weight(1L)` with no retraction of the answer it already published, and it is still what the finishers run at close |

### State and sources — 3

| Finding | Evidence |
|---|---|
| `W8-14` | `SlicedAggregateState:147` still keys accumulators by `SliceKey(keyHigh, keyLow, sliceStart)` — 128 bits of digest with no comparison of the values behind it. **Stale evidence:** the 64-bit `emitted` fold is gone (W8-8), and `WindowedAggregate:62`'s own javadoc now says the `SliceKey` is what remains |
| `SRC-6` | `PumpingFeed:61,142,155` still parks `IDLE_NAP_NANOS = 1_000_000L` after a poll that moved nothing, and `SharedPartitionFeed:141` does the same. The 1 ms is still a compile-time constant no setting reaches. Measurement not re-run |
| `SRC-7` | `LutScanReader.scan():299` still collects the whole result into an `ArrayList<Record>` through `found::add` and drains it into an unbounded `ArrayDeque`; `maxRecords` still bounds only emission (`:226`). **Stale evidence:** `SourceCapabilities.typicalLatency` is no longer read by nothing — `PluginController:238` reports it on `GET /api/v1/plugins`. It still drives no scan interval, which is the half the finding cares about |

The eighty rows above are 17 + 5 + 11 + 5 + 6 + 16 + 5 + 12 + 3.

---

## Measurements not re-run

Every headline above was decided from the code. Five findings carry a *number* that would need a
node, a load generator or a real key pair to reproduce, and those numbers were not re-measured. In
each case the mechanism the number came from is unchanged, so the verdict stands on the mechanism.

| Finding | The number, and what re-measuring it needs |
|---|---|
| `STRM-4` | 69 % ingest loss per stalled subscriber — a node, 60 000 keys, N stalled Flight clients |
| `STRM-8` | 2001 ms / 6002 ms commit times for 1 and 3 sleeping subscribers |
| `STRM-15` | 1.7 GB RSS for one stalled subscriber — 20 × 50 000-row appends against a running node |
| `SRC-6` | 12.8 ms/s of CPU per idle followed source — `SourceScaleTest`, run on its own; inside a full build the same test reported a negative figure |
| `SX-17` | The third shape: a cert and key each individually valid but not matching, starting the node and failing at the first handshake. Needs two real key pairs and a TLS handshake |

---

## Notes for whoever edits the register

1. **Twelve status lines cite evidence that has moved**, including on findings that are still
   open: `T-5`, `T-6`, `C-5`, `C-7`, `S-5`, `SX-16`, `SX-17`, `DOCX-6`, `DOCX-20`, `SRC-7`,
   `W8-14`, `STRM-10`, `CFG-8`, `CFG-22`, `API-F7`. Each is marked *stale evidence* above. A status
   line naming a mechanism that no longer exists is how a register stops being checked.
2. **`TIME-8` should be split.** One of its two claims is true and the other sends a reader looking
   for a 404 that answers 200.
3. **`T-5` should be reduced to its `jdbc` row.**
4. **`CFG-10` and `DOCX-20` are each half closed**, and both halves are named above, so the lead
   can decide whether to split them or leave them open on the remainder.
5. **`PF-11` and `API-F7`'s second half are the two cheapest things left** on this list that would
   visibly improve the CLI, and both were left alone here because each changes what a command
   prints on a path with no test around it.
6. **The header counts move by 20** — 102 open becomes 82 — and by 22 if the two NOW WRONG entries
   are closed rather than rewritten.
