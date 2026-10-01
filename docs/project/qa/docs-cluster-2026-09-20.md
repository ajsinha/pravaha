# The documentation cluster, worked one finding at a time — 2026-09-20

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

> The `DOCX` and `CFG` findings still open in [`FINDINGS.md`](FINDINGS.md) that are code fixes
> rather than documentation edits. `DOCX-6` and `PF-11` are owned elsewhere this session and are
> not touched here; `DOCX-20` is answered in
> [`singletons-2026-09-20.md`](singletons-2026-09-20.md) and is not repeated.
>
> One section per finding: **verdict**, **cause**, **fix**, **test**, **seed-proof**, **commit**.
>
> `FINDINGS.md` itself is not edited here: the lead applies statuses so the counts stay
> trustworthy.

---

## DOCX-21 — every error pointed at a host that has never existed

**Verdict: FIXED, by the owner's decision rather than by registering the domain.**

`ErrorCode.helpUrl()` built `https://docs.pravaha.io/errors/PRV-nnnn` from a private constant, and
`BearerTokenFilter` held a second, hand-written copy of the same string for the 401 body. The host
is NXDOMAIN: a click fails to connect rather than 404ing, which reads like a network problem at
exactly the moment somebody is diagnosing one. Four test classes asserted on it, so the build
enforced the link.

**Cause.** A constant where a setting belonged. Nothing about where this product's documentation is
published is knowable from inside the jar, and the code guessed.

**Fix.** `com.ash.messaging.pravaha.api.HelpUrls` holds the base, and there is no default.

- **One setting, three spellings.** `pravaha.docs.base-url` on the server (read by
  `ConfigurationCheck`, before anything else it checks, so a node's first failure cannot be
  reported with a link built out of a second misconfiguration); `pravaha.docs.base-url` in an
  embedded engine's own `Configuration` (read in `DefaultPravahaEngine`'s constructor); and the
  environment variable `PRAVAHA_DOCS_BASE_URL` for the CLI, both SDKs and anything else with no
  configuration file. Each of the first two falls back to the environment variable when the key is
  absent, so there is one value to set and not two.
- **Unset emits no URL.** `ErrorCode.helpUrl()` answers the empty string; `helpUrl` stays in the
  REST and Flight contracts and is empty, which is what the contract now says empty means; and
  every *printed* line that would have carried a link carries `HelpUrls.helpLine(code)` instead —
  "look PRV-2002 up in the console's help under Errors, or in docs/TROUBLESHOOTING.md". The
  console's help resolves every code offline and `TROUBLESHOOTING.md` is the code index, so the
  replacement names two references that exist, which the URL never did.
- **A base that is not a URL is refused where it is set**, with the new `PRV-1029
  CONFIG_DOCS_BASE_URL_INVALID`, naming the key and the value. `docs.example.test/errors/`,
  `/errors/`, `ftp://…`, `file://…` and `http://` are all refused; a missing trailing slash is
  supplied rather than being treated as a mistake. A refused value leaves the previous base alone,
  so configuration is never half-applied.
- **Surfaces.** `ErrorCode`, `PravahaException`, `BearerTokenFilter` (which no longer holds its own
  copy), `DtoMapper`'s backfill `Problem`, `PravahaCli`, `DlqCommand`, `ServerCommand`'s "Each code
  has a help page: …" sentence, the Java SDKs (which inherit it through `ErrorCode`, so the base is
  the *client's* — no link travels over the wire) and the Python SDK (`configure_docs_base`,
  `help_url_for`, `help_line`, and `InvalidDocsBaseUrlError` carrying the same `PRV-1029`).
- **One thing fixed in passing.** `DtoMapper` built its URL by hand *and* lower-cased the code, so
  the backfill-failure `Problem` was the only surface publishing `…/errors/prv-5040` while
  everything else published `PRV-5040`. Routed through `HelpUrls` with the code as rendered.

`api/openapi.lock.json` is unchanged and did not need regenerating: the field is still there with
the same name and type, and `OpenApiContractTest` passes against the recorded lock.

**Test.** `HelpUrlsTest` (13, new) — unset, configured, the missing slash, blank clearing it, six
shapes refused by name, a refused value leaving the previous one alone, and the code's range.
`ErrorCodeTest#theHelpUrlIsEmptyUntilADeploymentPublishesOne`.
`ConfigurationCheckTest#aDocsBaseUrlThatIsNotAUrlIsRefusedAtStartupByName_DOCX21`,
`#aConfiguredDocsBaseUrlReachesEveryErrorCode_DOCX21`,
`#withNoDocsBaseUrlTheNodeStartsAndEmitsNoUrl_DOCX21`.
`ServerSecurityTest#theUnauthenticatedBodyCarriesTheConfiguredHelpUrlAndNothingWhenThereIsNone`.
`ApiIntegrationTest#anUnknownStreamIsA400WithTheErrorCodeAndAnEmptyHelpUrlWhenNoneIsConfigured`
and `#aConfiguredHelpBaseReachesTheErrorBody`.
`PravahaCliTest#withNoHelpBaseARefusalPrintsNoUrlAndSaysWhereToLookTheCodeUp` and
`#withAHelpBaseConfiguredARefusalPrintsThatDeploymentsUrl`.
`CliDeadLetterTest#aConfiguredHelpBasePutsThisDeploymentsUrlBesideTheCode`,
`CliFeedStatusTest#aConfiguredHelpBaseTurnsTheLookupSentenceBackIntoALink`, and both of those
classes' existing cases rewritten to the offline sentence. `ExamplesTest`, `ClientOptionsTest`,
`SdkErrorCodeFidelityTest` and `PravahaExceptionTest` likewise.

**`ERRC-116` rethought rather than deleted.** That case asserted the URL for three codes and then
resolved `docs.pravaha.io` with `InetAddress.getByName`, printing the answer instead of asserting
it — because a DNS lookup in a unit test can only report the machine running it, and a sandbox with
no resolver looks exactly like a domain that does not exist. The owner's decision removed the
question: there is no built-in host, so the property worth pinning is the one that made the dead
link possible. `noCodeCarriesAHelpUrlTheDeploymentDidNotConfigure` asserts that with no base every
code's help URL is empty, that the line printed instead names two offline references, and that with
a base every code across the sampled four-digit space is that base plus that code and nothing else.
A link this product prints is now one its operator chose, and that is a fact about the code rather
than about the network under the test.

**Seed-proof.** Three, each run against `pravaha-api`'s 255 tests or the CLI's error-line cases:

| Seed | Result |
|---|---|
| `HelpUrls.forCode` falls back to `https://docs.pravaha.io/errors/` when nothing is configured | **3 failures** — `HelpUrlsTest.unsetMeansNoUrlAnywhere`, `ErrorCodeTest.theHelpUrlIsEmptyUntilADeploymentPublishesOne`, `PravahaExceptionTest.prefixesTheMessageWithTheStableCode`. Restored: 255/255 pass |
| `HelpUrls.normalise` returns the value without checking scheme or host | **7 failures** — six parameterised `aBaseThatIsNotAnAbsoluteHttpUrlIsRefusedByName` cases and `aRefusedBaseLeavesThePreviousOneAlone`. Restored: 255/255 pass |
| `PravahaCli` prints `forCode` rather than `helpLine`, and `ServerCommand` prints the sentence unconditionally | **2 failures** of 46 — `PravahaCliTest.withNoHelpBaseARefusalPrintsNoUrlAndSaysWhereToLookTheCodeUp` and `CliFeedStatusTest.aStoppedSourceIsMarkedAndExplainedAndAHealthyOneIsNot`. Restored: all pass |

**Documents.** `docs/TROUBLESHOOTING.md` (the callout at the head rewritten; `PRV-1029` given a row
in the startup-refusal table and in the code index; the `ApiError` paragraph now says `helpUrl` is
always present and may be empty), `docs/OPERATIONS.md` (a new "Where a failure's help link points"
section under *Starting a node*), `docs/system_design.md` §24.4, and
`pravaha-server/src/main/resources/application.yaml`, where the key is written out commented, with
why it has no default.

**Console, for the lead.** These carry the dead host and are not mine to edit:
`console/tests/fake_engine.py:283,338`; `console/content/topics/errors-overview.md:10,71,152,163`;
`console/content/topics/cli-reference.md:93,292`; `console/content/topics/http-api.md:74,245`;
`console/content/topics/authentication.md:181`; `console/content/topics/errors-config.md:270`.
`errors-overview.md:71` in particular now describes behaviour the engine no longer has.

**Commit.** `A failure's help page is the deployment's to name, and unset means no link`.

---

## DOCX-19 — one code for a node that will not boot and for a query that will not plan

**Verdict: FIXED for `PRV-2002`. The `PRV-7002` half is stale — E-3 already split it.**

`PRV-7002` was re-counted against the tree on 2026-09-20: every remaining `SecurityErrors.FORBIDDEN`
site is an authorization denial. The startup refusal of an open server, the
policy/authentication contradiction, the split-policy refusal and the bad configuration values all
carry `PRV-7004 SECURITY_MISCONFIGURED` now, and `TROUBLESHOOTING.md`'s row for `7002` says so.
Nothing to do.

`PRV-2002 SQL_VALIDATION_FAILED` had grown, not shrunk: the entry counted five sites and there are
ten. Seven of the ten are a configuration file the node refuses to start with.

**Cause.** `SqlErrors.VALIDATION_FAILED` is the nearest code to hand when a value is rejected, and
`pravaha-server` can see it. It says "SQL" only in the ranges table, which is in a different file
from the throw.

**Fix.** The seven configuration refusals move into the configuration range, and `PRV-2002` keeps
the one thing it is for.

| Site | Was | Is | Why |
|---|---|---|---|
| `PravahaNode.registerDeclaredStreams` — a stream under `pravaha.streams` with no `schema` | `PRV-2002` | `PRV-1020` | a required key is missing, which is what 1020 means |
| `PravahaNode.start` — `pravaha.standby.enabled=true` with no `pravaha.checkpoint.directory` | `PRV-2002` | `PRV-1020` | one key requires another |
| `PravahaNode.start` — `pravaha.watermark.idle-after` outside the tracker's bounds | `PRV-2002` | `PRV-1026` | a value outside a bound, which is what 1026 means |
| `PravahaNode.start` — `pravaha.watermark.tick` outside its bounds | `PRV-2002` | `PRV-1026` | same |
| `ConfigurationCheck.reconcileStreamsAndSources` — one stream, two schemas | `PRV-2002` | **`PRV-1012`** new | two keys that must agree and do not; each is well-formed alone, so no single-key check sees it |
| `StreamCatalog.withEventTime` and its `refused(...)` helper — the event-time, out-of-orderness and allowed-lateness refusals | `PRV-2002` | **`PRV-1013`** new | every one of them names a configuration key or the REST field that spells the same setting |
| `StreamCatalog.register` — a schema version already held | `PRV-2002` | **`PRV-1014`** new | a declaration conflicting with the catalog, not a statement being validated |

Three numbers taken: `PRV-1012 CONFIG_CONTRADICTION`, `PRV-1013 CONFIG_STREAM_EVENT_TIME_INVALID`,
`PRV-1014 CONFIG_STREAM_VERSION_IN_USE`. Each was grepped over every `.java`, `.md`, `.json`,
`.py` and `.yaml` in the tree first and appeared nowhere; 1015-1019 are left free, and 1029 is
DOCX-21's. They sit at 1012-1014 rather than beside the 1020-1028 value block because 1029 is the
last free number before the client range at 1030.

Three remaining `PRV-2002` sites are genuine and unchanged: `SqlPlanner`'s validation failure,
`PhysicalPlanBuilder`'s "declares no event-time column", and the same code reached through the
`StreamCatalog` path a planner consults.

**What a client that pinned the old number sees.** The HTTP status does not move: `statusFor` maps
CONFIGURATION and PLANNING to the same `400`, so a caller switching on the status is unaffected,
and `theRenumberingChangesNoHttpStatus_DOCX19` pins that. Flight and pgwire do not move either —
neither `FlightErrors.statusFor` nor `PgWireErrors.sqlStateFor` names `PRV-2002`, so both the old
and the new codes fall to the same defaults (`INVALID_ARGUMENT`, SQLSTATE `42000`); and in practice
none of the seven can reach a client at all, because a node that raises one does not finish
starting. What breaks is a log filter or runbook matching the four digits: six of the seven now
report a `1xxx`, which is the point — the old number told the reader the wrong subsystem.

**Test.** `ConfigurationRefusalCodesTest` (5, new): the missing schema, the standby with nothing to
stand by, the schema version already held, the control that SQL validation is still `PRV-2002` in
the PLANNING category, and that all three new codes are in the CONFIGURATION range.
`StreamEventTimeTest#theRenumberingChangesNoHttpStatus_DOCX19` (new).
`WatermarkSettingsTest` (3 cases) and `ConfigurationCheckTest#oneStreamWithTwoDisagreeingSchemasIsRefused_CFG8`
rewritten to the new numbers; the CFG-8 case gained a code assertion it never had.

**Seed-proof.** Every one of the seven sites put back to `SqlErrors.VALIDATION_FAILED` at once:
**8 failures of 31** — `WatermarkSettingsTest` (3: `time5…`, `time9…`, `time11…`),
`StreamEventTimeTest` (1: `latenessWithoutAnEventTimeIsRefusedAndSoIsAColumnTheStreamDoesNotHave`;
`theRenumberingChangesNoHttpStatus_DOCX19` correctly does *not* fail, because the status is what
the renumbering does not change), `ConfigurationCheckTest` (1:
`oneStreamWithTwoDisagreeingSchemasIsRefused_CFG8`) and `ConfigurationRefusalCodesTest` (3: the
missing schema, the standby, the schema version). Restored: 31/31 pass.

**Documents.** `docs/TROUBLESHOOTING.md`: three rows in the startup-refusal table and three in the
code index.

**Commit.** `A node that will not boot says so in the configuration range, not the SQL one`.

---

## CFG-4 — `PRV-5090`'s "Available:" list is an answer only if you know what it is a list of

**Verdict: the source half is already fixed; the lookup half was not, and reproducing it found a
documentation defect that produces the same refusal. Both fixed here.**

The entry is about the *source* side, and `PluginSourceFeeds.discover` now says what "available"
means — that it is this process's classpath, that the server jar carries `filesystem` alone, that
the other seven live in their own modules under `plugins/`, and that `docs/CONNECTORS.md` says
which module ships which name. Re-run on 2026-09-20: that message is in the tree and
`docs/CONNECTORS.md` carries the same account. Nothing to do.

**What was still open.** `PluginLookupSources.discover` raises the same `PRV-5090` and was left as
the bare list: *"no lookup plugin named 'jdbc' is on the classpath, so dimension table 'users'
cannot be opened. Available: none -- no lookup plugin jar is on the classpath."* On a shipped node
that list is **empty**, because the server jar carries no lookup plugin at all — and an empty list
beside a document naming two reads as a broken node rather than as a packaging decision. It is the
same defect the source side was repaired for, one method over.

**And the trap underneath it.** Both shipped lookup plugins report names ending in `-lookup`:
`AerospikeLookupPlugin.name()` is `aerospike-lookup` and `JdbcLookupPlugin.name()` is
`jdbc-lookup`. `docs/CONNECTORS.md:51` listed them as "aerospike, jdbc" — the *source* plugins'
names. An operator copying the table into `pravaha.lookups.<n>.plugin` got `PRV-5090` from a
correct document, which is the exact shape CFG-4 is about: the remedy the error offers, and the
remedy the document offers, both failing. `CONTINUOUS_QUERIES.md` and the console's `lookups.md`
had it right, so the table was the only wrong copy.

**Fix.** The lookup refusal gains the source side's explanation, adapted: what "available" means,
that the server jar carries no lookup plugin, which module ships each of the two names, that both
end in `-lookup` and that `aerospike` and `jdbc` without the suffix are the source plugins and will
not be found here. `docs/CONNECTORS.md`'s table now names them with the suffix and says why.

**Test.** `PluginLookupSourcesTest#theLookupRefusalSaysWhatAvailableMeansAndWhereTheTwoShippedNamesLive_CFG4`,
which asks for `jdbc` — the documented spelling — and asserts the message names the classpath rule,
both modules, both real names, the suffix trap and `docs/CONNECTORS.md`.

**Seed-proof.** The message reverted to the bare `Available: …` list fails that one case (1 test,
1 failure) and leaves `anUnknownLookupPluginIsRefusedWithWhatIsAvailable` passing, which is the
point: the old assertion could not tell a useful message from a useless one. Restored: 4/4 pass.

**Documents.** `docs/CONNECTORS.md` §1's `LookupSourcePlugin` row.

**Commit.** `The lookup refusal says what "available" means, and the lookup names gain their suffix`.

---

## CFG-10 — a credential declared with an empty spec, and an empty token table

**Verdict: (b) is already fixed. (a) is fixed as far as it can be, and the measurement that says
how far is in the tree as a test.**

**(b), the empty token table, is done.** `SecurityProperties.unusableTokenTable()` exists and
`PravahaNode.start` logs it at WARN, so `authentication: token` with `tokens: {}` now says at
startup that the node can verify no credential and refuses every call, instead of arriving at the
first 401. That is what the entry asked for and what the code's own comment had been asking for.

**(a) is the interesting one, and the entry's framing needs correcting.** It reads as a silent
drop the engine ought to refuse. It is narrower and worse than that. Four spellings, measured
through Spring's own `YamlPropertySourceLoader` and `Binder` rather than reasoned about:

| Written | What the Environment carries | What binds | Today |
|---|---|---|---|
| `q: {id: q1}` | `tokens.q.id=q1` | yes | works |
| `z: {id: ""}` | `tokens.z.id=` | yes | refused, `PRV-7004` (CFG-11) |
| `y:` | `tokens.y=` | **no** — `BindException` naming `pravaha.security.tokens.y` | refused, loudly, before any Pravaha code runs |
| `x: {}` | **nothing at all** | no | invisible |

An empty mapping contributes no leaf to the flattened property map, so `x` is absent from the
`Environment` as well as from the bound map. There is no object anywhere in the JVM that knows it
was written. CFG-3(a)'s technique — compare what the file says against what the binder produced —
is the obvious fix and **cannot work here**, because the left-hand side of that comparison is
empty too. I wrote that check first and it never fired; the two tests for it failed with
"Expecting code to raise a throwable", which is how the measurement above came to be made.

So there is no refusal to write, and writing one would be a check that never runs. What is
available is to stop being silent: the node now logs, once at startup, **how many credentials it
will verify**, with the empty-mapping trap named in the same line. An operator who wrote three and
reads one has the discrepancy in front of them where CFG-10(b)'s warning already is, instead of in
a support ticket about 401s — which is (b)'s remedy applied to the case one entry up. The
credentials are not printed and cannot be: the map key is the bearer token (CFG-11).

**Fix.** `ConfigurationCheck.credentialCountLine()`, logged from `check()`, beside the
`pravaha.docs.base-url` line. Deliberately not in `SecurityProperties`: that class cannot see an
entry that never reached it, and putting the explanation where the data is not would be the third
version of this mistake.

**Test.** `ConfigurationCheckTest#onlyAnEmptyMappingIsInvisibleAndTheOtherTwoAlreadyFail_CFG10`
pins the measurement, so a future Spring upgrade that starts emitting a property for `x: {}`
breaks a test rather than quietly making a refusal possible again without anyone writing one.
`#anAuthenticatingNodeSaysHowManyCredentialsItWillVerify_CFG10`,
`#theCountLineDoesNotPrintTheCredentials_CFG10`,
`#aNodeThatDoesNotAuthenticateSaysNothingAboutCredentials_CFG10`.

**Still open, honestly.** A node cannot refuse `x: {}`, and nothing in this repository can make it.
Refusing it would need the raw configuration file re-read outside Spring's resolution — a second
source of truth for configuration, which is the defect this codebase spends the most effort
avoiding. Recorded here so the next reader does not spend the same afternoon proving it.

**Documents.** `docs/TROUBLESHOOTING.md`'s startup-refusal table gains a row for the credential
that authenticates nobody; `application.yaml`'s `pravaha.security.tokens` comment, which already
named the trap, now also names the count line as how you notice it.

**Seed-proof.** `credentialCountLine()` returning null unconditionally fails **3 of
`ConfigurationCheckTest`'s 19** — `onlyAnEmptyMappingIsInvisibleAndTheOtherTwoAlreadyFail_CFG10`,
`anAuthenticatingNodeSaysHowManyCredentialsItWillVerify_CFG10` and
`theCountLineDoesNotPrintTheCredentials_CFG10`. Restored: 19/19 pass.

**Commit.** `A node that authenticates says how many credentials it will verify`.
