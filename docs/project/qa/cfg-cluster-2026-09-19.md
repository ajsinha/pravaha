# The CFG cluster — the sixteen open configuration findings, closed

Batch B14. Every `CFG-` finding that `docs/project/qa/FINDINGS.md` still had open on 2026-09-19, worked in
id order, each reproduced against today's code before anything was changed.

`docs/project/qa/FINDINGS.md` is the lead's file and is not edited here. This is the input to it: one
section per finding, with a verdict, the cause, the fix, the test, the seed-proof result and the
commit. One finding raised out of CFG-16, which the lead has since filed as `CKPT-5`, is at
the end.

> **Rebase state: done**, once, onto `develop` at `1c7ebed` — the time-travel debugger (ADR-048,
> `PRV-8011..8016`) on top of the CQ grammar (ADR-049, `PRV-8017`, `PRV-2073`) and the licence
> correction. Both sides kept everywhere they met. Three things it needed, in `298cd76`:
>
> - **`PRV-4091` → `PRV-4093`.** The dead-letter batch took 4091 and 4092 while this branch was
>   held. `STATE_CHECKPOINT_DIRECTORY_UNUSABLE` moved once, in code, tests, TROUBLESHOOTING,
>   OPERATIONS, `application.yaml` and two console topics. `PRV-1027` and `PRV-1052` are clear.
> - **`api/openapi.lock.json` regenerated, not merged.** The merge was clean and the result was
>   wrong: it carried the other batches' thirteen new paths with their own response lists and the
>   CFG-20 `default` only on the operations that existed when this branch was cut. Regenerated from
>   develop's: 35 paths, 40 operations, 40 `default` entries, and the diff against develop's lock
>   is those 40 lines and nothing else.
> - **One silent semantic conflict**, in `PluginSourceFeeds`: the dead-letter batch added a
>   `ConcurrentHashMap` for its live counters while CFG-3(b) removed the import. Both sides merged
>   without a marker and the file did not compile.

**The shape of the cluster, once all sixteen were read together.** Fourteen of them are one defect
wearing different clothes: *a value that is present in the configuration file, correct-looking, and
reaching nothing* — or reaching something at a moment nobody is watching. The two that are not are
CFG-17 and CFG-18, which are stale case-file assertions rather than code defects, and are recorded
as verdicts.

The unifying fix is a rule rather than sixteen patches: **one bad value is one startup failure.**
`PravahaNode` already made that argument, in a comment, for exactly one key
(`pravaha.watermark.idle-after`), and applied it to nothing else. It now holds for the checkpoint
block, the journal path, the Flight endpoint, the security names, the token table and every map key
in the file — and the refusals arrive while the properties beans are being built, ahead of the web
server, so an operator reads the sentence rather than Tomcat's.

| Finding | Verdict | What closed it | Commit |
|---|---|---|---|
| `CFG-1` | Reproduced, **fixed** | The cluster line names the member | `fbe5a6b` |
| `CFG-2` | Reproduced (4 of 4), **fixed** | Port and host refused by name; the bound port served; IPv6 bracketed | `fbe5a6b`, `0cc54c8` |
| `CFG-3` | Reproduced (a and b), **fixed** | A key that reached nothing is refused; `sources bound:` follows the file | `1b64c4a`, `8e5f327` |
| `CFG-4` | Reproduced, **fixed as far as it can be**; `I-7` stays open | The message says what "available" means; the missing mechanism is written down | `8e5f327` |
| `CFG-7` | Reproduced (3 of 3), **fixed** | Journal and checkpoint paths validated at startup, with codes | `3fa6de8` |
| `CFG-8` | Reproduced, **fixed** | Two schemas for one stream refused; declared and bound reconciled in the log | `1b64c4a` |
| `CFG-10` | (a) reproduced, **partly closable**; (b) already fixed | `id` required removes the reason to write `{}`; the empty mapping itself is undetectable | `97c0281` |
| `CFG-11` | Reproduced, **fixed** | `pravaha.security.tokens.<token>.id` is required | `97c0281` |
| `CFG-15` | Reproduced, **fixed** | A bare number on a duration is refused; the interval in force is logged | `1b64c4a`, `0cc54c8` |
| `CFG-16` | Reproduced, **fixed** | `keep` bounded at startup | `3fa6de8` |
| `CFG-17` | **Stale case file. No code defect** | Case corrected; the three facts pinned by a test | `3fa6de8`, `264a1e6` |
| `CFG-18` | **Stale cell, plus one real defect** | Case corrected; a mechanism is matched the way a mode is | `fbe5a6b`, `264a1e6` |
| `CFG-19` | Reproduced, **fixed** | `application` and `node` tags on every metric; `/actuator/info` identifies the node | `d9f1f09` |
| `CFG-20` | Reproduced, **fixed** | One error shape out of every path; `ApiError` published with its fields | `d9f1f09` |
| `CFG-21` | Reproduced, **fixed** | The security names refuse while the properties bean is built | `97c0281` |
| `CFG-22` | Reproduced, **fixed** | An implementation that does not exist is refused; the choice is logged | `e915436`, `0cc54c8` |

**The gate, after the rebase onto `1c7ebed`.** `./mvnw -o verify` at the root: **BUILD SUCCESS**,
37 modules, **3,819 tests run, 0 failures, 0 errors, 184 skipped** (3,635 executed) — against
develop's 3,769 at the same command. Console `test_help.py`: **33 passed**.

Per module, this batch's own contribution: `pravaha-common` 240 → 246, `pravaha-cluster` 59 → 63,
`pravaha-bindings` 63 → 64, `pravaha-server` 145 → 193 (184 before the rebase; the debugger's own
server tests are in the rest), `pravaha-it` 827 — three tests inverted, none added.

`SharedLaneIngestPropertyTest` **passed** in this run, in 18.17 s. That is not evidence about
`LANE-6`: the finding is that it fails intermittently, and a green run is what an intermittent
failure looks like most of the time. Recorded, not closed.

**Three gate tests in `pravaha-it` asserted two of these defects as facts**, and are inverted with
the reason in place. `StateClusterTest` state104 arm 4 and state105 both pinned "mechanism names
are case-sensitive, unlike modes" — which is CFG-18 written as a property.
`StateCheckpointScheduleTest` state011 asserted that `application.yaml`'s `checkpoint:` block does
**not** contain `timeout`, under the description "timeout is read by the code and not documented"
— and that absence is precisely how the case file came to record the key as having no writer
(CFG-17). `OrphanedClassTest` flagged `ConfigurationCheck`, correctly: it is a `@Component` whose
whole job happens in its `@PostConstruct` and nothing references it, so it is in
`REACHABLE_OTHERWISE` with the mechanism named.

Three error codes are new — `PRV-1027 CONFIG_KEY_UNREACHABLE`, `PRV-1052 API_UNHANDLED_REQUEST`,
`PRV-4093 STATE_CHECKPOINT_DIRECTORY_UNUSABLE` — all three in `docs/guides/TROUBLESHOOTING.md`'s table and
in the console's error topics. **Both of them moved once, for the same reason and caught the same
way.** `PRV-1027` is not 1030 because the Java SDK already holds 1030 for
`CLIENT_MALFORMED_ENDPOINT`; `PRV-4093` is not 4091 because the dead-letter batch took 4091 and
4092 while this branch was held un-rebased. `ErrorCodeUniquenessTest` caught the first before the
rebase and would have caught the second, which is what it is for — a code is what goes in a
runbook, so it moves before it ships and never after.

---

## `CFG-1` — `pravaha.node.id` reaches exactly one surface

**Verdict: reproduced, fixed.** `CoordinatorFactory.describe` logs `cluster mode SINGLE on single
(consensus), self-contained` and names no member, so `GET /api/v1/status`'s `instanceId` was the
only place the configured id could be read back from a running node. (The finding's other half —
an empty id electing rather than refusing — was already stale: `Member` refuses a blank id.)

**Cause.** `describe(Configuration, ClusterCoordinator)` has no member to name. The node has one: it
constructs `new Member(nodeId, flightHost, flightPort)` one line earlier and throws it away.

**Fix.** A three-argument `describe` that takes the `Member` the node joined as and appends it with
its address and the key it came from. The node passes the member it just started with. The
two-argument line is unchanged for callers that have none. The id also became a `node` tag on every
metric and a field of `/actuator/info` under CFG-19, so it now reaches four surfaces rather than
one.

**Test.** `CoordinatorFactoryTest.theStartupLineNamesTheMemberThisNodeJoinedAs_CFG1` — asserts the
mode, the id and the address in the three-argument line, and that the two-argument line still does
not carry the id.

**Seed proof.** `describe(config, coordinator, self)` reverted to `describe(config, coordinator)`:
`CoordinatorFactoryTest` 1/15 fails, on `theStartupLineNamesTheMemberThisNodeJoinedAs_CFG1` and
nothing else. Restored.

**Commit** `fbe5a6b`.

---

## `CFG-2` — the Flight endpoint: no code, no reportable port, an unparseable address

**Verdict: reproduced on all four counts, fixed.**

**Cause and fix, one per count.**

- **(a)** `pravaha.flight.port: 70000` reached gRPC's own argument check, so the one bind failure
  that is purely a configuration mistake was the only one on that key carrying neither a `PRV-`
  code nor the key's name. `PravahaNode.refuseUnusableFlightEndpoint` checks the range before the
  coordinator is built — before it, because the member this node advertises carries the Flight host
  and port, and a cluster that forms around an unreachable address is worse than a node that
  refused to start. `PRV-3010`, naming the key, and saying that `0` is legal and reportable.
- **(b)** `port: 0` binds correctly and **no served surface reported the bound port**: `NodeStatus`
  had no field for it, and `/actuator/health`'s components map is suppressed by the shipped
  `show-details: when-authorized` on a node with `authentication: none`. `NodeStatus` gains
  `flight`, carrying the address that was bound; the HTML page at `/status` shows it too, since
  that page exists for the case where nothing else works. `PravahaNode.flightAddress()` is the one
  place it is composed, and `describe()` uses it as well.
- **(c)** `host: ::1` was logged and advertised as `::1:19090`, which nothing can parse back into a
  host and a port. `Endpoint.address` in `pravaha-common` brackets an IPv6 literal, leaves
  everything else alone, and is safe to apply twice; `Member.address()`, the startup log line and
  the status field all go through it, so the three cannot disagree.
- **(d)** `host: 127` is legal input to `InetAddress` and means `0.0.0.127`. Refused, naming what
  it would have been read as (`Endpoint.expandedIpv4`) and what to write instead — and on a machine
  that happened to hold `0.0.0.127` the old behaviour would have bound it silently.

**Tests.** `EndpointTest` (4, new) — bracketing, idempotence, the abbreviated-IPv4 predicate, and a
V-control that every host an operator legitimately writes is *not* flagged.
`CoordinatorFactoryTest.anIpv6MemberAdvertisesAnAddressAClientCanParse_CFG2`.
`PravahaNodeTest` +4: the port refusal, the host refusal, the ephemeral port readable through
`flightAddress()` and `describe()`, and a node with Flight off reporting no address rather than a
misleading one. `ApiIntegrationTest.theBoundFlightAddressIsServed_CFG2` — that context runs with
`pravaha.flight.port=0`, so it is the real case.

**Seed proof.** Three defects back at once — `Endpoint.address` returning `host + ":" + port`,
`refuseUnusableFlightEndpoint` returning immediately, `flightAddress()` returning empty. Run
fail-at-end so every module reports: `EndpointTest` 1/4 fails
(`anIpv6LiteralIsBracketedSoTheAddressCanBeParsedBack_CFG2`), `CoordinatorFactoryTest` 1/15
(`anIpv6MemberAdvertisesAnAddressAClientCanParse_CFG2`), `PravahaNodeTest` 3/17 (the port refusal,
the host refusal, the ephemeral port), and `ApiIntegrationTest` the served address. The V-controls
— every host an operator legitimately writes, and a node with Flight off — stay green. Restored.

**Commits** `fbe5a6b` (Endpoint, Member), `0cc54c8` (the node and the status surface).

---

## `CFG-3` — a declared stream the binder dropped, and a log line in hash order

**Verdict: reproduced on both counts, fixed.**

**(a) Cause.** Spring canonicalises a map key before binding it and **silently discards one it
cannot**. Seven streams declared, five in the catalog: `txn ` and `txnü` were present in the file,
syntactically valid, absent from the catalog, and reported at no log level at all — so the first
query against one failed with `Object 'txnü' not found. Known streams: [...]`, which is accurate
and impossible to act on beside a file that clearly declares it.

**(a) Fix.** `ConfigurationCheck` reads the raw property names out of the `Environment` — they are
still there; only the *bound map* lost them — and refuses any first segment under
`pravaha.streams.`, `pravaha.sources.`, `pravaha.lookups.` or `pravaha.sinks.` that is not a key of
the corresponding bound map. `PRV-1027 CONFIG_KEY_UNREACHABLE`, naming Spring's own bracket form as
the remedy. Compared against the bound maps rather than against a guess at Spring's canonicalisation
rules, so the check reports what Spring did rather than a second copy that can drift. Environment
variables are deliberately not examined: `PRAVAHA_STREAMS_TXN_SCHEMA` is a different name, not one
an operator can get wrong this way, and guessing at it would invent false positives.

**(b) Cause and fix.** `PluginSourceFeeds.bindings` was a `ConcurrentHashMap` and `bindings()`
returned `Map.copyOf`, which has no order either — so `sources bound:` came out in hash order while
`streams declared in configuration:` immediately above it came out in file order, and a diff of two
nodes' startup logs was unusable. A synchronised `LinkedHashMap`, and a copy that keeps the order.
Binding happens once at startup and every read afterwards is a copy, so the concurrent map was
buying nothing the lock does not.

**Tests.** `ConfigurationCheckTest` — the refusal naming both lost keys; a V-control that
`my-stream`, `1txn`, `select`, `TXN`, `txn` and `camelCase` are all accepted (a refusal that fired
on those would be worse than the silence it replaces); and that the bracket form the message offers
actually binds. `PluginSourceFeedsTest.theBindingsAreReportedInTheOrderTheyWereDeclared_CFG3`,
including that re-binding a stream keeps its place.

**Seed proof.** (a) `refuseKeysThatReachedNothing()` removed from `check()`:
`ConfigurationCheckTest` 2/12 fail — the streams case and the sources case — and the three
V-controls stay green. (b) `bindings()` back to `Map.copyOf`: `PluginSourceFeedsTest` 1/18 fails,
on the ordering case. Both restored.

**Commits** `1b64c4a` (a), `8e5f327` (b).

---

## `CFG-4` — `PRV-5090`'s "Available:" list has one entry

**Verdict: reproduced. Fixed as far as a configuration change can fix it; the mechanism (`I-7`)
stays open and is now written down as a gap.**

**Cause.** The message is well-formed and the list is accurate: `pravaha-server` depends on
`pravaha-plugin-filesystem` and on no other plugin, so on a shipped node `Available: [filesystem]`
is the truth. The defect is that the list is offered *as the remedy* beside documentation naming
seven plugin names, and an operator reading both has no way to tell whether they mistyped a name or
whether the jar is simply absent.

**Fix.** The message now says which of the two it is: a plugin answers to the name it reports for
itself, is found by `ServiceLoader`, and therefore resolves only when its jar is on **this
process's** classpath — the server jar carries `filesystem` alone and the rest are separate modules.
`docs/guides/CONNECTORS.md` section 1 says the same where somebody choosing a connector reads it, and
section 8 records the absence of a drop-a-jar-in mechanism as a gap rather than leaving it implied
by a one-item list.

**What is left, precisely.** There is no plugin directory, no documented launcher that reads one
(the executable jar uses `JarLauncher`, not `PropertiesLauncher`), and no `-Dloader.path`
convention. Adding a connector to a deployment today means building a jar that depends on both.
Giving a shipped node a supported way to load one is a **packaging change** — the executable-jar
layout, the Dockerfile and the release artefacts — which is `I-7` and belongs with whoever owns
`deploy/`, not in a configuration batch.

**Test.** `PluginSourceFeedsTest.aBindingNamingAPluginThatIsNotThereIsRefusedWithWhatIsAvailable`
extended: the message must still name what *is* there, and must now also say `ServiceLoader`,
`classpath` and where to read which module ships which name.

**Seed proof.** The appended sentence removed: `PluginSourceFeedsTest` 1/18 fails, on that case
and nothing else. Restored.

**Commit** `8e5f327`.

---

## `CFG-7` — a bad persistence path starts a healthy node

**Verdict: reproduced on all three cells, fixed.**

**Cause.** None of the three arrives where an operator will see it.

1. `pravaha.checkpoint.directory` naming a regular file **started a node that announced
   checkpointing** and then failed every registration with `PRV-1041 cannot create the checkpoint
   directory …/txnA.csv/QW` — up, green, and unable to accept work.
2. A journal path inside a directory that does not exist was created silently by
   `RegistryJournal.append` through `Files.createDirectories`. Defensible, and it means `PRV-8006`
   never fired for the commonest typo there is: the node journalled perfectly to somewhere nobody
   meant while the real journal stayed empty.
3. A journal path that is itself a directory failed at startup — correctly — with a bare
   `UncheckedIOException`, no code and no help URL. The one shape caught early had the worst
   message.

**Fix.** `PersistenceProperties.validate()`, from `@PostConstruct` and from `PravahaNode.startNow()`
(a node built through the builder never reaches a `@PostConstruct`, and that covers every test and
the embedded case). `PRV-4093 STATE_CHECKPOINT_DIRECTORY_UNUSABLE` for the checkpoint root — not a
directory, unwritable, or no parent — and `PRV-8006 REGISTRY_JOURNAL_UNWRITABLE` for the journal
being a directory, having no directory, or having an unwritable one. Each message says what used to
happen instead, because an operator meeting the refusal on an upgrade needs to know why a path that
worked yesterday does not today.

**Test.** `PersistencePropertiesTest` (10, new): a checkpoint directory that is a file, one under a
missing parent, a journal that is a directory, a journal under a missing directory, and a V-control
that the two ordinary first-start shapes — a journal file that does not exist yet in a directory
that does, and a checkpoint directory that does not exist yet under a parent that does — are left
alone. Plus an assertion that `validate` carries `@PostConstruct`, because the placement is the fix.

**Seed proof.** Both path checks removed from `validate()`: `PersistencePropertiesTest` 4/10 fail
— the checkpoint file, the missing checkpoint parent, the journal-as-directory and the missing
journal directory — and `thePathsThatAreFineAreLeftAlone_CFG7` stays green. Restored.

**Commit** `3fa6de8`.

---

## `CFG-8` — `pravaha.streams` and `pravaha.sources` are never reconciled

**Verdict: reproduced, fixed. The documentation half was already closed (`DOCX-7`).**

**Cause.** Nothing in `PravahaNode.start` compared the binding map with the declaration map. Three
configurations, each internally valid and each useless: a source bound to an undeclared stream (the
node *says* it bound a stream it then says it does not know); a declaration whose source names the
plural, paired in no log line anywhere; and two schemas for one stream, which nothing compared.

**Fix.** `ConfigurationCheck.reconcileStreamsAndSources`. The first two are **warnings** with both
lists named — `bound and undeclared:` and `declared and unbound:` — because a stream can also be
declared over `POST /api/v1/streams` after the node is up, and a stream nothing feeds is correct for
one a client pushes rows into, so refusing either would break deployments that are right. The third
is **refused**: the declaration is what a query is planned against and the binding's `schema` option
is what the plugin decodes rows with, so a divergence is a node that plans one shape and reads
another, and it used to surface at the first registration as `PRV-5040` complaining about a column
name — blaming whichever of the two the message happened to be built from. Whitespace and type case
are not a divergence.

The shipped `application.yaml`'s commented example had exactly that divergence between its
`streams:` and `sources:` blocks. Corrected.

**Test.** `ConfigurationCheckTest` — the refusal naming the stream and both keys; a V-control that
`"id:INT64, user_id:STRING"` and `"id:int64,user_id:string"` are the same schema; a source bound to
an undeclared stream starting; and a consistent file passing every check.

**Seed proof.** `refuseDivergentSchemas()` removed from `check()`: `ConfigurationCheckTest` 1/12
fails, on the divergence case only — the two-spellings V-control and the bound-but-undeclared case
stay green. Restored.

**Commit** `1b64c4a`.

---

## `CFG-10` — an empty token spec is dropped, and an empty token table is silent

**Verdict: (b) was already fixed on 2026-09-19. (a) reproduced, and is *not fully closable in
code*; what closes it is making the spelling that fails impossible to want.**

**(a) Cause, measured rather than assumed.** I ran Spring's real `YamlPropertySourceLoader` over the
finding's own fixture and printed every property name it produced. `pravaha.security.tokens.x: {}`
produces **no property at all** — an empty YAML mapping flattens to nothing, so the binder never
sees the key and neither does the `Environment`. (`y:`, a null value, *does* produce a bare property
— and makes the binder fail outright.) The `Environment` scan that closes CFG-3 therefore cannot see
this case: there is nothing in the process to look at.

**Fix, and its limit.** Requiring `id` (CFG-11) removes the reason anybody writes `{}`: the entry
that used to mean "use the map key as the id" is now the one entry that *cannot* be expressed, and
the spelling that works is the spelling that is safe. The residue — that `x: {}` is invisible rather
than refused — is documented where an operator meets it: the token table's own comment in
`application.yaml`, `SecurityProperties.getTokens()`'s javadoc, and the console's authentication
topic. A warning nothing can emit is not a fix, and claiming one would be worse than the finding.

**(b)** already closed by `SecurityProperties.unusableTokenTable`, logged by `PravahaNode`, pinned
by `ServerSecurityTest.aNodeThatCanVerifyNoCredentialSaysSoAtStartup`. Unchanged here.

**Test.** Shared with CFG-11 (below).

**Commit** `97c0281`.

---

## `CFG-11` — a token without `id` writes the credential into the registry journal

**Verdict: reproduced, fixed.**

**Cause.** `SecurityProperties` resolved a principal id as `spec.getId() == null ? entry.getKey() :
spec.getId()`, and **the map key is the bearer credential**. A deployment that wrote
`pravaha.security.tokens.s3cr3t-value: {}` and registered a query put the secret in two durable
places it did not choose: the audit trail, and the registry journal at `pravaha.registry.journal`,
as that registration's owner, where it survives restarts and backups. The credential is correctly
kept out of the startup log and out of `/actuator/env`, which made the journal the only leak and an
easy one to miss.

**Fix.** `id` is required. There is no id this class can invent that is not either the credential or
a lie, so deriving one was never going to be right. `principalIdOf` refuses with `PRV-7004`, names
the key, says why, and prints the credential's **length** rather than the credential. It fires from
`validate()`, from `verifier()` and from `principalFor()` — the two paths that used to apply the
fallback are the two that wrote it to disk, so each refuses on its own rather than depending on
`validate()` having run.

**Test.** `ServerSecurityTest.aCredentialWithNoIdIsRefusedRatherThanBecomingItsOwnPrincipalId_CFG11`
— the refusal, from all three entry points, and `hasMessageNotContaining` the credential. Plus
`aCredentialWithAnIdIsUnaffected_CFG11` as the V-control: the required spelling works end to end,
including the recovery lookup, and the credential does **not** resolve as an identity.

**Seed proof.** The fallback (`spec.getId() == null ? entry.getKey() : …`) restored and the guard
neutered: `ServerSecurityTest` 1/20 fails, on the CFG-11 case;
`aCredentialWithAnIdIsUnaffected_CFG11` stays green, which is the point — the seed is invisible to
a correctly-written token table. Restored.

**Commit** `97c0281`.

---

## `CFG-15` — `interval: 2` is two milliseconds, and the interval in force is logged nowhere

**Verdict: reproduced, fixed on both halves.**

**Cause.** Spring's binder reads a bare number on a `Duration` field as **milliseconds**;
`ConfigParsers.parseDuration`, which the engine's own `Configuration` uses, *refuses* a bare number
precisely so this cannot happen. Two duration dialects meet in one YAML file. An operator who wrote
`interval: 2` meaning two seconds got 6,409 checkpoints in twenty seconds against 10 for
`interval: 2s` on identical timing — with no warning. Compounding it,
`PeriodicCheckpointer`'s own `checkpointing every {}ms` line did not appear in any of six measured
runs, so a 2 ms node looked exactly like a 2 s one until somebody counted files.

**Fix.** `ConfigurationCheck.refuseBareNumberDurations` reads the value out of the `Environment` as
the operator wrote it — before the binder's interpretation can hide it — and refuses a digits-only
value on `pravaha.checkpoint.interval`, `.timeout`, `pravaha.watermark.idle-after` and `.tick` with
`PRV-1023`, naming `2s` as what to write. Changing the *unit* was considered and rejected:
`@DurationUnit(SECONDS)` would silently turn an existing `interval: 2000`, meaning two seconds in
milliseconds, into two thousand seconds. Refusing is the only change that cannot make a correct
deployment wrong.

`PravahaNode` logs the interval, the count and the timeout beside the directory, as `Duration`s
rather than as numbers, because the number is the thing that was ambiguous. A non-positive interval
or timeout is refused at startup too, rather than at the first registration.

**Test.** `ConfigurationCheckTest.aBareNumberOnADurationIsRefusedRatherThanReadAsMilliseconds_CFG15`
and a V-control that `2s`, `PT2S`, `2000ms` and `1m` all stay legal.
`PersistencePropertiesTest.aNonPositiveCheckpointIntervalOrTimeoutIsRefused_CFG15`.

**Seed proof.** `refuseBareNumberDurations()` and both `requirePositive` calls removed:
`ConfigurationCheckTest` 1/12 and `PersistencePropertiesTest` 1/10 each fail on their CFG-15 case,
and `everyUnitedSpellingOfTheSameDurationIsAccepted_CFG15` stays green. Restored.

**Commits** `1b64c4a`, `0cc54c8` (the log line).

---

## `CFG-16` — `keep: 0` starts a healthy node that refuses every registration

**Verdict: reproduced, fixed.**

**Cause.** `PersistenceProperties.Checkpoint.keep` is a plain `int` with no validation, and the
bound lives in `PeriodicCheckpointer`'s constructor — which runs **per registration**. So one bad
integer produced a node that passed every liveness and readiness probe, advertised itself as
checkpointing, and could not accept a single query, failing once per client for as long as it ran.

**Fix.** `PersistenceProperties.validate()` refuses `keep < 1` with `PRV-1026`, saying what keeping
none would mean. Same placement argument as CFG-7, and the same one `PravahaNode` already made in a
comment for `pravaha.watermark.idle-after` and applied to nothing else.

**Test.** `PersistencePropertiesTest.keepingNoCheckpointsIsRefusedAtStartupRatherThanAtEveryRegistration_CFG16`,
with `theCheckpointCountsThatWorkKeepWorking_CFG16` as the V-control — `keep: 1` and `keep: 5` were
both measured working and must stay legal.

**The finding's incidental observation is now a finding of its own** — see *A gap in the checkpoint
id sequence is a failed checkpoint* below. Short version: pruning **is** strictly newest-K-by-id;
`5,7,8,9,10` is the newest five ids that exist, over a sequence where id 6 was attempted and never
stored. Not a configuration defect, and not fixed here.

**Seed proof.** The `keep < 1` guard neutered: `PersistencePropertiesTest` 1/10 fails, on the
CFG-16 case; the V-control over `keep` 1, 3, 5 and `Integer.MAX_VALUE` stays green. Restored.

**Commit** `3fa6de8`.

---

## `CFG-17` — the case file's assumed fact 9 is stale

**Verdict: no code defect. The case file was wrong and is corrected.**

All three facts hold exactly as the finding states them, checked against the code:
`PersistenceProperties.Checkpoint` **has** a `timeout` field with a 30 s default;
`checkpointConfiguration()` emits `pravaha.checkpoint.timeout` in nanoseconds beside `interval` and
`keep`; and `PeriodicCheckpointer.from` reads it.

**What was done.** Assumed fact 9 in `docs/project/qa/cases/CFG.md` is struck through and withdrawn, and
CFG-024 is rewritten to ask what it should now ask — whether the timeout is **enforced** — with the
previous run's own evidence recorded against it: three checkpoint files and zero timeout log lines
from a twelve-row view, which checkpoints well inside a millisecond and is therefore evidence of
nothing. The rewritten case requires the state size and the measured checkpoint duration to be
recorded, or it has not been executed. `pravaha.checkpoint.timeout` is also now documented in
`application.yaml`, OPERATIONS and the console's settings index — it appeared in none of them, which
is how the case came to record it as a key with no writer.

**Test.** `PersistencePropertiesTest.theCheckpointTimeoutIsBoundForwardedAndReadable_CFG17` pins all
three facts, so the case cannot go stale in the other direction either.

**Seed proof.** The `.set("pravaha.checkpoint.timeout", …)` line removed from
`checkpointConfiguration()` — which is exactly the state assumed fact 9 describes:
`PersistencePropertiesTest` 1/10 fails, on the CFG-17 case. Restored.

**Commits** `3fa6de8` (test), `264a1e6` (case file, documentation).

---

## `CFG-18` — a case-file cell that cannot occur, and one real defect beside it

**Verdict: the load-bearing cell is wrong and is corrected; one of the two findings it carries is
real and is fixed; the other is already closed.**

**The cell.** `PARTITIONED` + `mechanism: single` starts, and should: `single` genuinely excludes
split-brain because there is no second node, which is what `OPERATIONS.md`'s own mechanism table
says. The guarantee check is working — it fires correctly on `PARTITIONED` + `socket`. The cell is
corrected to expect a successful start, and its vacuity note with it: the note said the case was
falsified by "the node starting", which is what that combination correctly does, so the case could
only ever fail. A **node** does still refuse to serve the mode, for the different reason S-3 gives.

**Real defect, fixed.** `mode` was upper-cased before resolution and `mechanism` was a plain map
lookup, so two adjacent keys in one YAML block had two case rules: `mode: replicated` was accepted
and `mechanism: SOCKET` answered "no cluster coordinator called 'SOCKET' is on the classpath" beside
an `Available: [single, socket]` list that appears to contradict it. `providerNamed` matches exactly
first — so a provider registering two names differing only in case keeps whichever was asked for —
and then ignoring case.

**Already closed.** `mode: HA` is refused while `system_design.md` §27.1 documents it. That document
now carries a header naming `mode: HA` among the things it describes and the tree does not have, so
the documentation defect is addressed; the case-file cell records that.

**Test.** `CoordinatorFactoryTest.theMechanismIsMatchedWithoutRegardToCase_CFG18` (`SINGLE` reaches
the single provider; `SOCKET` reaches the socket provider and then fails on its missing peer list,
which is the same answer lower-case `socket` gives) and
`thePartitionedModeOnSingleStarts_CFG18`.

**Seed proof.** `equalsIgnoreCase` back to `equals`: `CoordinatorFactoryTest` 1/15 fails, on
`theMechanismIsMatchedWithoutRegardToCase_CFG18` and nothing else —
`thePartitionedModeOnSingleStarts_CFG18` stays green, because that half was never a code defect.
Restored.

**Commits** `fbe5a6b` (code, tests), `264a1e6` (case file).

---

## `CFG-19` — `spring.application.name` reaches no metric tag and no `/actuator/info` field

**Verdict: reproduced, fixed.**

**Cause.** Nothing registered a `MeterRegistryCustomizer` or common tags, and nothing contributed to
the info endpoint — while `spring.application.name: pravaha` sat in the shipped `application.yaml`
and `info` sat on the exposure list.

**Fix.** A `MeterFilter` adding `application` (from `spring.application.name`) and `node` (from
`pravaha.node.id`) to every meter, present and future — a filter rather than a per-meter tag,
because the point is that a series registered tomorrow carries it too. `node` as well as
`application`, because the question an operator asks of a fleet is *which node*, and
`pravaha.node.id` is the answer this project has already chosen for that: it names a state claim
and is what a member advertises (CFG-1). An `InfoContributor` reports the name, the version and the
id — in code rather than through `management.info.env.*` and a block of `info.*` keys, which would
be a second copy of facts this process already holds and can drift from them.

**Test.** `ApiIntegrationTest.everyMetricSaysWhichApplicationAndWhichNodeItCameFrom_CFG19` — asserted
on the registry rather than on a scrape, because the property being fixed is that the tag is on
*any* meter, including ones registered after the test runs. `theEndpointAnOperatorChecksFirstIdentifiesTheNode_CFG19`
for `/actuator/info`.

**Seed proof.** The common tags emptied and the info contributor made a no-op:
`ApiIntegrationTest` 2/19 fail, on both CFG-19 cases. Restored.

**Commit** `d9f1f09`.

---

## `CFG-20` — three of six non-2xx shapes are not `ApiError`, and `ApiError` has no properties

**Verdict: reproduced, fixed. One part of the finding is stale.**

**Cause.** `application.yaml` turns RFC 7807 problem details off with the stated intent that every
non-2xx response is an `ApiError` and nothing else. Turning them off worked and **was never the
mechanism that mattered**: `ApiExceptionHandler` handles `PravahaException` and
`IllegalArgumentException`, and a 405, a 415 and a 404 on an unmapped path are neither — so they
fell through to Spring Boot's `BasicErrorController` and came back as a *third* shape,
`{"timestamp":…,"status":405,"error":"Method Not Allowed","path":…}`, with no `code`, no `message`
and no `helpUrl`.

**Fix.** `ApiErrorController implements ErrorController`, which **replaces** Boot's rather than
adding a handler per exception type: a handler list is a list somebody has to keep complete, and the
failure mode of an incomplete one is invisible — the response still looks like an error, just not
this API's. Everything that reaches `/error` leaves as an `ApiError`, including whatever is added
next. `PRV-1052 API_UNHANDLED_REQUEST`, one code rather than one per status, because the status
already distinguishes them and what the code adds is that the body is an `ApiError`. Hidden from the
OpenAPI document, because `/error` is where the container forwards and not a path a client calls.

**The compounding half.** `components.schemas.ApiError` was published with no properties — by now,
with no schema at all — because `ApiError` is the return type of every `@ExceptionHandler` and of
nothing a controller declares, so springdoc never walked it. A generated client modelled every error
as an empty object, on the one schema a client is guaranteed to meet. An `OpenApiCustomizer`
registers the schema and attaches it as a `default` response to **every** operation, which is what
the API's own rule says: any non-2xx, whatever its number, is an `ApiError`. Enumerating statuses
per endpoint would be a second copy of that rule, kept by hand, wrong the first time an endpoint
grows a refusal. `api/openapi.lock.json` regenerated — one `"default"` per operation, nothing else
moved.

**Stale.** The finding also says the document advertises a `/status` path this server does not map.
It does map it: `StatusController.statusPage()`, `@GetMapping("/status")`, and
`ApiIntegrationTest.statusIsAlsoASelfContainedHtmlPageServedByTheEngine` exercises it.

**Test.** `ApiErrorShapeTest` (4, new) over a **real container**, and that is not decoration: MockMvc
does not run the servlet container's error dispatch, so it sees the right status and an *empty
body* — a test of the error shape written on MockMvc passes whether or not an `ErrorController`
exists, which is how a module with this much coverage had an `/error`-shaped hole in it. The
V-control asserts that a refusal which *does* reach a handler keeps its own specific code, so a fix
that funnelled everything through the fall-through would fail. `ApiIntegrationTest` keeps the half
MockMvc can honestly see and says why. `OpenApiContractTest.theOneErrorSchemaEveryClientMeetsHasItsFields_CFG20`.

**Seed proof.** `@RestController` removed from `ApiErrorController` — so Boot's
`BasicErrorController` comes back, which is the defect exactly — and the schema registration
removed from the customizer: `ApiErrorShapeTest` 3/4 fail (405, 415, 404) and `OpenApiContractTest`
1/5. `theShapesThatWereAlreadyCorrectStayCorrect_CFG20` stays green, which is what says the three
handled shapes were never the problem. Restored.

**Commit** `d9f1f09`.

---

## `CFG-21` — a validated value whose refusal arrives under a Tomcat startup failure

**Verdict: reproduced, fixed.**

**Cause.** `authentication`, `policy` and `audit` were all validated, and every refusal arrived from
inside a bean the servlet container was building — `verifier()` from the `pravahaAuthentication`
`FilterRegistrationBean`, `policy` and `audit` from the node's own start. An operator who wrote
`authentication: tokens` read three lines about Tomcat failing to start, and the actual sentence —
an excellent one — was four `Caused by:` levels down. Nothing was wrong with the diagnosis; it was
in the wrong place.

**Fix.** `trimmedPolicy()` and `trimmedAudit()` move the other two refusals into `SecurityProperties`
beside `trimmedAuthentication()`, and a `@PostConstruct validate()` runs all three plus the token
table while the properties object is being initialised — ahead of every bean that depends on it, so
the failure names `SecurityProperties` and the message is the first thing under it.
`PravahaNode.securityPolicy()`, `PravahaNode.auditSink()` and
`PravahaServerApplication.pravahaSecurityPolicy` now switch on the validated name, which also
removed a second copy of the policy message that had been living in the application class.

While there: the `policy` field's javadoc said the refusal was `PRV-7002`. `SECURITY_FORBIDDEN` is
7002; this is 7004.

**Test.** `ServerSecurityTest.everySecurityNameIsRefusedWhileThePropertiesAreBuilt_CFG21` — the three
refusals **and** an assertion that `validate` carries `@PostConstruct`, because the placement is the
fix and a test that only called the method would pass with the annotation gone.
`theSpellingsThatAreMeantToWorkStillValidate_CFG21` is the V-control, including `"  token  "`
(trimmed, and genuinely *means* token) and `authenticated-only`.

**Seed proof.** `@PostConstruct` removed and the policy guard neutered: `ServerSecurityTest` 2/20
fail — `everySecurityNameIsRefusedWhileThePropertiesAreBuilt_CFG21` and the pre-existing
`anUnknownPolicyNameIsRefusedRatherThanFallingBackToPermissive`, which is honest collateral: the
older test was always about this guard. Restored.

**Commit** `97c0281`.

---

## `CFG-22` — `-Dpravaha.memory` accepts any value and silently falls back

**Verdict: reproduced, fixed. One part of the finding is stale.**

**Cause.** `MemoryAccess.best()` falls through to the default for any value it does not recognise, on
the policy its own javadoc states: *"an unavailable or unflagged implementation is never an error:
the default is a correct answer, not a degraded one."*

**Fix, and the distinction it turns on.** That policy is right for *unavailable* and wrong for
*nonexistent*. `-Dpravaha.ffm=true` on a Java 21 JVM is a launcher that will start working on an
upgrade, so it still falls through silently and deliberately. `-Dpravaha.memory=nonsense` is a typo,
and silence defeats the only reason anybody sets the property: it is set to be *certain*, and a node
that is correct and is not the one you asked for is exactly what certainty was meant to rule out.
Refused, naming the three values. `bytebuffer` joins `agrona` and `foreign` as a name that can be
asked for — it was the default and was not selectable, so a refusal listing it would have listed a
value `best()` then refused.

`PravahaNode` logs the choice once: `off-heap access: bytebuffer (-Dpravaha.memory,
-Dpravaha.ffm=false)`. Before that line, a deployment that set `-Dpravaha.ffm=true`, upgraded its
JDK expecting the switch to take effect, or typed `agrone` had no way to find out what it was
running. All four selections were measured producing byte-identical canonical results, so none of
this changes an answer — it changes whether an operator can know.

**Stale.** "Appear in no document an operator reads" is no longer true —
`console/content/topics/settings-index.md` documents both. They are now also in `OPERATIONS.md`
under **Choosing the off-heap implementation**, with the table, the reason, and the log line.

**Test.** `MemoryAccessTest.anOffHeapImplementationThatDoesNotExistIsRefusedRatherThanIgnored_CFG22`
and `everyNameTheRefusalOffersIsOneBestAccepts_CFG22` — a refusal that lists values is worth nothing
if one of them is refused too. `bestFallsBackWhenAnUnavailableImplementationIsRequested` **asserted
the nonsense case as correct fall-through**, so the test and the code agreed with each other and not
with the operator; it keeps the `ffm` half, which is the half that must stay true.

**Seed proof.** The guard neutered: `MemoryAccessTest` 1/25 fails, on
`anOffHeapImplementationThatDoesNotExistIsRefusedRatherThanIgnored_CFG22`;
`bestFallsBackWhenAnUnavailableImplementationIsRequested` stays green, which is the whole
distinction the fix turns on. Restored.

**Commits** `e915436`, `0cc54c8` (the log line).

---

## `CKPT-5` — a gap in the checkpoint id sequence is a failed checkpoint, and nothing says so

**Raised out of CFG-16. Filed by the lead as `CKPT-5` (LOW, OPEN) in `84348b5`, independently and
with the same reading, while this branch was held un-rebased. Kept here because the analysis below
is what the register's entry is short for. Not fixed.**

**What was seen.** CFG-16 records, as an incidental observation from the 2026-09-14 run: with
`keep: 5`, the survivors were ids `5,7,8,9,10` — *"not the five newest, so pruning is not strictly
newest K by id"*.

**That inference is wrong, and what is actually there is worse.** Read rather than re-run, because
two methods answer it:

- `FileCheckpointStore.availableIds` (`:125-135`) sorts by id, `Comparator.reverseOrder()`, newest
  first. `prune(keep)` (`:186-197`) deletes from index `keep` onward. So pruning **is** strictly
  "newest K by id", over the ids that exist.
- `PeriodicCheckpointer.checkpointNow` (`:200-203`) takes the id **first** —
  `long id = nextId.getAndIncrement();` — and only then calls `execution.checkpoint(id, timeout)`
  and `store.store(checkpoint)`. A checkpoint that throws in either call has already consumed its
  id and stored nothing.

So `5,7,8,9,10` is the newest five ids that exist, over a sequence in which **id 6 was attempted
and never landed**. Pruning is correct. The observation is a symptom of a different thing: a
checkpoint failed, the id it burned is the only durable trace, and the sequence carries that trace
permanently while no surface reads it.

**Why it matters.** `pravaha_query_checkpoint_failures_total` counts failures and
`pravaha_query_checkpoint_last_success_timestamp_seconds` ages, so a *fleet* can be alerted. What
cannot be done is the thing an operator does at 3 a.m.: look at a checkpoint directory and tell
whether it is healthy. A directory holding `5,7,8,9,10` and one holding `6,7,8,9,10` look equally
fine to `ls`, and one of them has a hole. Nothing logs the gap, nothing reports it per query, and
recovery does not mention it — recovery restores from the newest readable checkpoint and is
correct either way, which is precisely why the hole stays invisible.

**What it needs.** Not a change to pruning. Either take the id *after* the checkpoint is stored, so
ids stay contiguous and a gap becomes impossible (the simpler fix, and it changes what an id
means — `nextId` is also seeded from `availableIds().max()+1` at construction, so this wants
checking against restart); or leave the gap and surface it — log it when it happens, and report it
where the checkpoint's health is reported. The first is a `PeriodicCheckpointer` change, the second
an observability one; both are somebody else's batch, and neither is a configuration defect, which
is why this is a new finding rather than part of CFG-16.

**Severity:** LOW, as filed. It misleads a diagnosis rather than losing data.

---

## What this batch did not do

- **`I-7`, a way to add a connector to a shipped node.** CFG-4's real remedy. It is a packaging
  change — executable-jar layout, Dockerfile, release artefacts — and belongs with whoever owns
  `deploy/`. Written down as a gap in `docs/guides/CONNECTORS.md` section 8 rather than implied by a
  one-item list.
- **CFG-10(a)'s empty mapping.** `x: {}` produces no property, so no code in this process can see
  it. Measured, not assumed. Documented in three places; requiring `id` removes the reason to write
  it.
- **CFG-16's pruning observation.** Raised as a finding rather than chased, and now filed as
  `CKPT-5`: pruning is correct, and the gap in the id sequence means something else.
- **CFG-024's rewritten case — deliberately left unexecuted.** Whether the checkpoint timeout is
  *enforced* needs a view whose state is large enough that one checkpoint genuinely exceeds the
  bound; the previous run's twelve-row view checkpointed well inside a millisecond and proved
  nothing in either direction. That is a measurement on a quiet machine, not a unit test — the
  result is a timing claim, and a timing claim taken on a box running four other agents' builds is
  not evidence. **It belongs with the benchmark batch.** The rewritten case in
  `docs/project/qa/cases/CFG.md` states the setup and requires the state size and the measured checkpoint
  duration to be recorded alongside the result, so that whoever runs it cannot record a pass
  without the numbers that make it one.
