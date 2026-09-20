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
