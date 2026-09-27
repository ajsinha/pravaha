# The API-surface cluster — seven findings about what a caller is told

Every finding in this batch is about the same thing seen from seven places: **what the product says
back to somebody who got it slightly wrong.** Not one of them is a wrong answer. Each is a right
answer given under the wrong name, at the wrong moment, or to a question the caller did not ask.

`docs/qa/FINDINGS.md` is the lead's file and is not edited here. This is the input to it: one
section per finding, with a verdict, the cause, the fix, the test, the seed-proof result and the
error codes taken.

| Finding | Verdict | What closed it |
|---|---|---|
| `API-F11` (MEDIUM) | **FIXED** | The filter is handed `springdoc.*.path` and derives all three open documentation paths from them, instead of transcribing them |
| `API-F7` (MEDIUM) | **FIXED** | `Subscription` maps Flight failures like every other call, and the CLI's banner waits for the server's schema |
| `E-8` (MEDIUM) | **FIXED** | `PRV-1012 CONFIG_REFERENCE_TOO_DEEP`, and the bound raised from 32 to 200 |
| `SX-19` (LOW) | **FIXED already**, verified | `5a45149`, on this branch as `PravahaCliTest.anOfflineRefusalNamesTheStreamTheCallerGaveIt` |
| `API-F8` (LOW) | **FIXED** | `?level=` is a value the caller sent, and is refused; absent is still the default |
| `API-F10` (LOW) | **FIXED**, and it was not low | A lone surrogate reached the stream catalogue as an unaddressable name; refused at the deserializer |
| `API-F2` (LOW) | **FIXED** (case file) | A second SQL constant, `FUSEDSQL`, and the precise reason `FILTERSQL` cannot demonstrate codegen |

**Codes taken:** `PRV-1012 CONFIG_REFERENCE_TOO_DEEP` and `PRV-1053 API_MALFORMED_TEXT`. Both
grepped across the whole tree before allocation — `PRV-1012` was the only gap between `1011` and
`1020`, and `1053` is the next after `API_UNHANDLED_REQUEST`, keeping the API codes contiguous.

---

## API-F11 — the Swagger UI page is behind authentication although `/api/docs` and the document are not

**Verdict: FIXED.** The one deployment the finding names had already been fixed (`b4c1e665`:
`/api/swagger-ui` added to the constant). That is not what the finding asks for, and the half it
leaves is the half that will come back.

**Which of the three is right.** Open. The design's reason is recorded in API-107 and holds: these
three describe the *shape* of the API, disclose no stream name, query text, row or token, and a
client that cannot fetch the schema cannot generate a client. A person who cannot open the page
cannot read the API they are entitled to call. Nothing about them is a disclosure that
authentication would prevent.

**Why they disagreed.** `BearerTokenFilter.OPEN_PREFIXES` was a constant listing `/api/docs`,
`/api/v1/openapi.json` and `/swagger-ui`, and `application.yaml` configured
`springdoc.swagger-ui.path: /api/docs` and `springdoc.api-docs.path: /api/v1/openapi.json`. Two
files, one fact, agreeing by transcription — and they had already stopped agreeing, because
springdoc serves the page's own HTML and JavaScript under the configured path's *parent*
(`/api/swagger-ui`), which nobody transcribed. So `/api/docs` answered 302 without a credential,
exactly as intended, and the address it handed the browser answered 401.

**The fix.** The filter takes the two configured paths and derives the third the way springdoc
derives it. `PravahaServerApplication.pravahaAuthentication` reads
`springdoc.api-docs.path`/`springdoc.swagger-ui.path` (with springdoc's own defaults) and
`springdoc.*.enabled`; `BearerTokenFilter.openPaths` turns them into the open set. Moving either
property moves the opening with it, and turning either off closes it rather than leaving an
opening onto nothing.

**A second thing the same method had to stop doing.** `shouldNotFilter` was
`anyMatch(path::startsWith)` on the raw request URI, which is API-109's whole case: `/api/docs`
also opened `/api/docsomething`. It now matches whole path segments — the path itself, or the path
followed by `/` — so `/actuator/health/liveness` still works and a neighbour that merely shares a
prefix does not.

**Test:** `ServerSecurityTest.theOpenDocsPathsFollowTheConfiguredOnesRatherThanACopyOfThem`,
`.documentationTurnedOffLeavesNoOpeningBehindIt`,
`.anOpenPathOpensItsOwnSegmentsAndNotANeighbourThatSharesItsPrefix`, beside the existing
`.theDocsUiIsOpenAtTheAddressTheServerRedirectsTo`. `/api/v1/streams` and `/api/v1/queries` are the
control in each.

**Seed-proof:** restoring the constant set and `startsWith` — **3 of 23 fail** in
`ServerSecurityTest`, and the four-assertion control on the shipped configuration stays green,
which is the point: the old code was right about today's `application.yaml` and wrong about the
rule.

## API-F7 — a dead-server refusal never names the address, and `subscribe` differs again

**Verdict: FIXED**, in two places, and the first half was already half-done.

**The six commands.** `queries`, `query`, `register`, `drop`, `pause` and `resume` already answered
`PRV-1040  cannot reach localhost:19090: …` with the scheme advice after it — E-7's fix, in
`ServerFailures.of`, which recovers the server's own code from the wire and treats an `UNAVAILABLE`
carrying none as a connection that reached no server. Verified rather than re-fixed.

**`subscribe` did not, and the reason is structural.** `PravahaFlightClient.open` builds the stream
and hands it to `Subscription`, which was the one class in the SDK that never went through
`failureOf`: `run()` rethrew Arrow's `FlightRuntimeException` exactly as it arrived. That is the
bare `io exception` with no `PRV-` prefix the finding records. `Subscription` now carries the same
mapper every other call uses, with `READ_FAILED` as its fallback because a subscription is a result
that arrives over time, and a failure half way through a stream is reported exactly as one when the
call was made.

**The banner.** A Flight stream is lazy: `client.subscribe(...)` returns without sending anything,
so the confirmation was printed on the strength of a call that had not yet reached the server —
whether or not there was one, whether or not the view existed, whether or not this principal could
read it. `Subscription.awaitOpen()` waits for the schema, which Flight sends before any data and
which `streamSubscription` sends as soon as it has authorized the reader and resolved the view. The
CLI calls it, and the banner is then a statement about something that happened. It costs nothing on
a working subscription — the message is already in flight.

**Tests:** `ServerCommandTest.everyCommandAgainstADeadNodeNamesTheAddressItTried` (all seven
commands, asserting `PRV-1040` and `localhost:1`),
`.subscribeSaysNothingOnStandardOutputWhenTheSubscriptionNeverOpened`,
`JavaSdkSnapshotSubscriptionTest.awaitOpenReportsTheServersRefusalBeforeAnythingClaimsToBeSubscribed`
and `.awaitOpenOnALiveSubscriptionReturnsAndTheSubscriptionThenWorks` as its control.

**Seed-proofs, two, because they are two defects:** removing the mapping from `Subscription` —
**1 of 5 fails** in `JavaSdkSnapshotSubscriptionTest` and **2 of 16** in `ServerCommandTest`.
Moving the banner back above `awaitOpen()` with the mapping intact — **1 of 16** fails, and it is
the stdout one, which is the assertion that distinguishes the two halves.

## E-8 — a 34-deep, non-circular reference chain is refused as circular

**Verdict: FIXED.** Both halves of it were wrong, and the smaller one is the one that cost the
reader time.

`ConfigResolver` has two guards and they are not the same kind of thing. The `visiting` set is an
exact cycle detector: it names the keys in the loop and it is right. `MAX_DEPTH` is a bound on
recursion, so that a pathological file — or a bug in the set above — costs an exception rather than
a `StackOverflowError` part way through building the configuration. The bound was answering the
detector's code, so a chain of 34 keys that terminates was reported as
`PRV-1011 CONFIG_CIRCULAR_REFERENCE`, naming a cycle that does not exist. An operator following
that message looks for a loop for as long as it takes them to stop believing the message.

**New code: `PRV-1012 CONFIG_REFERENCE_TOO_DEEP`** (grepped tree-wide; 1012 was the only unused
number between the two reference codes, which is where a reader looks for it). The message says it
is a limit, gives the number, says no key on the way referred to one already being resolved, and
says which code reports that.

**And the bound is now 200,** chosen against the stack rather than against a guess at what
configuration is reasonable: each level costs two frames, so the whole bound is some hundreds of
frames of a default thread's stack. 32 is reachable by generated configuration — a chain of
environment overlays each defaulting to the one beneath it — and ERRC-004's own vacuity control, a
terminating 100-deep chain, was tripping it. That control now passes, which is the first time it
has.

**Tests:** `ConfigResolverTest.aLongTerminatingChainResolvesRatherThanBeingCalledCircular` and
`.aChainPastTheLimitIsRefusedAsALimitAndNotAsACycle`;
`ErrcConfigParsingTest.aTwoKeyCircularReferenceIsRefusedAndA100DeepChainIsNot` is inverted (its
100-deep control asserted `FAIL PRV-1011` and now asserts it resolves) and
`.aChainPastTheLimitIsRefusedAsALimitWithItsOwnCode` is added beside it.

**Seed-proof:** `MAX_DEPTH` back to 32 and the code back to `CIRCULAR_REFERENCE` — **2 of 18 fail**
in `ConfigResolverTest` (one failure, one error) and **2 of 15** in `ErrcConfigParsingTest`.

## SX-19 — SX-5's catalogue suppression costs the CLI a diagnosis it can safely give

**Verdict: FIXED already; verified, not re-fixed.** `5a45149` is on this branch. `validate` and
`explain` catch `PRV-2002` and append the stream name the caller typed on the same command line,
through `PravahaCli.namingTheStreamYouGaveIt`, and the planner's blanket suppression is untouched —
which is right, because `SqlPlanner` still cannot tell one caller from another.
`PravahaCliTest.anOfflineRefusalNamesTheStreamTheCallerGaveIt` passes, including its
`doesNotContain("This server has")` half, which is what keeps the CLI's hint from turning back into
a catalogue dump.

## API-F8 — `explain`'s `?level=` is treated as absent rather than as invalid

**Verdict: FIXED.**

`@RequestParam(defaultValue = "physical")` looks like "the default when the parameter is absent" and
is not: Spring's `AbstractNamedValueMethodArgumentResolver` applies the default to an empty value
as well. So `?level=` answered `200` with the physical plan while `?level=PHYSICAL` answered `400`
— two answers to the same class of mistake, on the same parameter, in the same request. The
commonest way to send an empty one is a shell variable that did not expand, which is a mistake
worth being told about rather than one worth guessing past.

Both `level` and `format` are now `required = false` with the default applied explicitly, so an
absent parameter still means `physical`/`text` — not asking is not the same as asking for nothing —
and an empty one reaches the `default ->` arm and leaves as `PRV-0400`.

**Two smaller things in the same messages, because they are what the reader sees.** The level
refusal said `level must be 'logical' or 'physical'`, naming two of the three it accepts, so a
caller who mistyped `codegen` was told `codegen` is not a level (API-083 recorded this as a second
finding). And `got ''` is a pair of quotes a reader has to interpret; an empty value is now named
rather than shown.

**No REST contract change.** `defaultValue` and `required = false` produce the same published
parameter, and `OpenApiContractTest` passes against the unchanged `api/openapi.lock.json` — checked
rather than assumed, because the lock has merged cleanly and wrongly before.

**Test:** `ApiIntegrationTest.anEmptyLevelIsRefusedAndAnAbsentOneIsStillTheDefault`, with the absent
case as its control.

**My first version of this had a bug of exactly the kind the change is about, and the control did
not catch it.** Moving the default out of the annotation left one comparison, `if
("text".equals(format))`, reading the raw parameter rather than the resolved one — so a request with
no `format` at all fell through to the graph arm and was planned twice. The control asserted
`level == "physical"`, which the graph arm also returns, so it passed. What catches it is asserting
what the *absent* default means rather than what it is called: `$.graph` must not exist. A test that
checks a defaulted value is not checking that the default was applied.

**Seed-proof, two:** restoring both `defaultValue`s — **1 of 22 fails**. Restoring
`"text".equals(format)` — **1 of 22 fails**, and it is the `$.graph` assertion that reports it.

## API-F10 — a lone unpaired UTF-16 surrogate is accepted by the deserializer

**Verdict: FIXED — and the finding's own severity is the thing that was wrong with it.**

The lead asked what it can then do, so it was followed. In `sql` it does what the finding says: it
reaches the lexer and is refused cleanly, which is why this looked like a disagreement about a
status code. `sql` is not the only string this API takes.

**Measured, against a running node:** `POST /api/v1/streams` with `{"name":"bad\ud800name",
"schema":"id:INT64,v:STRING"}` answered **`201`** and the stream was in `GET /api/v1/streams`
afterwards. The name is a key, and every later request that would address it has to carry the same
half-character back — which a URL path cannot (there is no UTF-8 encoding of a lone surrogate), SQL
will not quote, and Flight carries only as `?`, because protobuf's Java encoder substitutes for an
unpaired surrogate rather than failing. So one anonymous request adds an entry to the catalogue
that nobody, including its author, can name again, and the same object has two identities on two
surfaces. Past the catalogue a stream name is a state-directory and checkpoint-file path element,
where the file system has its own opinion.

**Refused at the deserializer**, in `WellFormedTextModule`, a Jackson `Module` bean on the
auto-configured mapper. That is the earliest point that sees it and the only one that sees all of
it: refusing per field would mean every controller, and every future controller, remembering that a
string from JSON may not be text. Refusing here means a string that reaches a controller is text.

**Deliberately only the unpaired surrogate.** Control characters, emoji, right-to-left marks and
every other awkward-but-real character are untouched — they encode, they round-trip, and which of
them a *name* may contain is a different question with a different answer per field. This one has
no legitimate use, because there is no text it represents. A surrogate **pair** is one character and
is unaffected, which has its own test.

**New code: `PRV-1053 API_MALFORMED_TEXT`** (grepped tree-wide), in the 1xxx API range beside
`MISSING_FIELD` and `INVALID_PARAMETER` and for the same reason: nothing was planned and nothing
could be, so a 2xxx would send the reader to the SQL documentation for a request the SQL never saw.
The category maps to 400, so API-098(d)'s expected status is now met — by a designed refusal rather
than by Jackson.

**Tests:** `ApiIntegrationTest.aStringCarryingALoneSurrogateIsRefusedBeforeItBecomesAName` (the
refusal, *and* that the catalogue is unchanged afterwards) and
`.aProperSurrogatePairIsOrdinaryTextAndStillRegisters` as the control.

**Seed-proof:** replacing the module bean with an empty one — **1 of 22 fails**. The whole
`pravaha-server` suite (209) passes with the module in place, which is the check that mattered for a
change to every string on the surface.

## API-F2 — the codegen happy path cannot be demonstrated with the shared fixture

**Verdict: FIXED, in the case file, and the reason is recorded precisely.**

It is not a product defect. `FilterProjectGenerator.emitProjection` has an arm for every
fixed-width type and none for a variable-width one: copying a `STRING` between two rows means
copying bytes into the output row's variable-width region and rewriting its pointer, which the
generator does not emit. So any projection carrying a `STRING` falls back with `PRV-3101`, and
`API.md`'s shared `FILTERSQL` projects `user_id`. API-037 was therefore running API-038's case.

`docs/qa/cases/API.md` gains a second constant, `FUSEDSQL` — the same filter over a numeric-only
projection — and API-037 uses it. The two differ **only** in the first projected column, on purpose:
the filter, the schema, the input file and the row count are shared, so a difference between them is
a difference in codegen and nothing else. The constants block carries the reason, so the next
executor does not rediscover it.

The happy path is demonstrable and is already demonstrated in the build:
`PravahaCliTest.explainCodegenShowsTheJavaTheEngineWillRun` uses a numeric-only projection and
asserts the numbered source and the class declaration.

---

## Console pages that state the old behaviour

`console/` is another agent's. Named, not edited:

- **`console/content/topics/errors-config.md`** — three changes. The `PRV-1011` section says a
  refusal fires when "references nest deeper than the resolver allows", which is now `PRV-1012` and
  needs its own section (the bound is 200, the message says it is a bound). The range row
  `| PRV-1050 – PRV-1052 | The REST API itself |` becomes `PRV-1050 – PRV-1053`, the page summary
  says "PRV-1001 to PRV-1052", and `PRV-1053 API_MALFORMED_TEXT` needs a section of its own.
- **`console/content/topics/authentication.md:40`** — "`/actuator/health`, `/actuator/info`,
  `/api/v1/openapi.json` and `/api/docs` stay open" is now derived from
  `springdoc.api-docs.path`/`springdoc.swagger-ui.path` rather than fixed, and the page's own
  resource prefix (`/api/swagger-ui` under the shipped configuration) is open too. Setting
  `springdoc.*.enabled=false` closes the corresponding paths.
- **`console/content/topics/http-api.md`** — the `explain?level=` row should say an empty `level`
  or `format` is a `400` rather than the default; the error-status table at `:253` should list
  `PRV-1053` beside `PRV-1050` and `PRV-1051`.

## For the register

Three things this batch turned up that are not in it.

1. **The same empty-parameter leniency on two other endpoints.** `DebugController.inspect` and
   `DeadLetterController.page` take `@RequestParam(defaultValue = "0") int offset` and
   `defaultValue = "50" int limit`. `?limit=` on either silently means 50, by exactly the mechanism
   API-F8 describes. Not changed here because API-F8 names `explain` and each of these is a
   separate contract with its own tests, but it is the same defect and should be closed the same
   way.
2. **A lone surrogate on the paths this batch does not cover.** `WellFormedTextModule` guards
   String-typed fields in JSON request bodies on the HTTP surface. It does not guard JSON *map
   keys* (a separate `KeyDeserializer`), fields typed `Object`, or the Flight control path, where
   `ControlWire` strings reach the same registry from a client that encodes them itself. Flight's
   protobuf substitutes `?` rather than failing, so the Flight side is where a name arrives already
   corrupted rather than refused.
3. **`Subscription.awaitOpen` is a behaviour the SDK now has and does not require.** A caller that
   never calls it gets the old laziness, which is right for an application that wants to subscribe
   without blocking — but every consumer that prints or logs "subscribed" before pulling a batch has
   the defect API-F7 records. Worth a line in the SDK reference; the Python SDK's subscribe path was
   not examined in this batch.
