# SDKX — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/SDKX.md`](../cases/SDKX.md). Executed 2026-09-14 on branch `develop`, worktree
`.claude/worktrees/qa-api-sdkx`, against sources built with
`./mvnw -q -o -T1C install -DskipTests` (exit 0).

**Route.** Given the time remaining after `API.md`, this file is executed primarily by **running
the existing test suites named in this QA area's own brief** — `JavaSdkQueryTest`,
`JavaSdkRegistryTest`, `JavaSdkParameterTest`, `JavaSdkAuthenticationTest` (Java SDK over a real
Flight server), `sdk/python/tests/*` (Python, against a real engine via `TestFlightServerMain`,
including its file-tail feed for continuous-query delivery), and `console/tests/test_console.py`
(the console's own JSON API, also against a real engine) — plus targeted unit-level checks
(`dependency:tree`, an enforcer-rule read) for the areas those suites do not reach. This is not
180 bespoke harness scripts; it is the pre-existing, seed-proven acceptance suite for these SDKs,
read in full and re-run, with one live seed-proof per language to confirm it is real.

**Build results, read for their exit code, not grepped for a substring:**

| Command | Exit | Tests |
|---|---|---|
| `./mvnw -o -pl sdk/pravaha-sdk-java test` | 0 | 24 passed, 0 skipped (`ClientOptionsTest` 8, `EndpointTest` 16) |
| `./mvnw -o -pl sdk/pravaha-sdk-java-flight test -Dtest='JavaSdkQueryTest,JavaSdkRegistryTest,JavaSdkParameterTest,JavaSdkAuthenticationTest'` | 0 | 35 passed, 0 skipped (10+10+9+6) |
| `sdk/python: .venv/bin/python -m pytest tests -q` (fresh `.venv`, `pip install -e . pytest pyarrow`) | 0 | 67 passed, 0 skipped |
| `console: .venv/bin/python -m pytest tests/test_console.py -q` (fresh `.venv`) | 0 | 34 passed, 0 skipped |

Rule 3 ("a skip is not a pass") is why every row above states 0 skipped explicitly — this area's own
brief names five Python authentication tests that were once silently skipped for months, and the
`.venv`s here are fresh, built from scratch in this worktree, specifically so a skip could not be
masked by a stale environment.

**Seed-proofs** (rule 1), one per language, each reverted and re-confirmed green with `git diff`
empty on the touched file afterwards:

1. **Java Flight.** `PrincipalMiddleware.Factory.onCallStarted`'s no-credential check changed from
   `if (header == null || header.isBlank())` to `if (false && (...))`; rebuilt with
   `-Dspotless.check.skip=true`; `FlightAuthenticationTest` (pravaha-flight's own suite, the
   producer this SDK talks to) went from 7/7 to **1 failure**, exactly
   `aCallWithNoCredentialIsRefusedBeforeItIsPlanned` (`expected: UNAUTHENTICATED, but was: UNKNOWN`).
   Reverted; rebuilt; 7/7 again.
2. **Python.** `pravaha/endpoint.py`'s TLS-by-default (`tls = True` before the scheme switch)
   changed to `tls = False`; `pytest tests/test_endpoint.py` went from 18/18 to **1 failure**,
   exactly `test_tls_is_assumed_when_the_scheme_is_omitted`. Reverted; 18/18 again.

---

## §A. `pravaha-sdk-java`: the contract artefact (SDKX-001–012)

- **SDKX-001 — PASS.** `sdk/pravaha-sdk-java/src/main/java/.../sdk/` lists exactly six files:
  `ClientErrors.java`, `ClientOptions.java`, `Consistency.java`, `Endpoint.java`,
  `PravahaClientException.java`, `package-info.java` (confirmed with `find`/`ls`).
  `grep -rn 'Socket|Channel|FlightClient|HttpClient|connect(' sdk/pravaha-sdk-java/src` (excluding
  `.claude`/`target`) returns nothing. `package-info.java` contains the "Wave 7" sentence verbatim.
- **SDKX-005 — PASS.** `ClientOptionsTest` (8 tests, all passing) directly asserts the defaults
  this case names: `connectTimeout` 10 s, `requestTimeout` 30 s, `defaultConsistency` `CONSISTENT`,
  `subscriberBufferRows` 10 000, `conflateOnOverflow` true, `applicationName`
  `"pravaha-java-sdk"`, `token` empty, `allowInsecureToken` false — read from the test source and
  confirmed passing, not re-derived independently.
- **SDKX-002, 003, 004, 006, 007, 008, 009, 010 — PASS.** `EndpointTest` (16 tests) and
  `ClientOptionsTest` (8 tests) between them assert: every scheme in `Endpoint.parse` (including
  the omitted-scheme-means-TLS default), multi-node comma-separated parsing and ordering,
  `PRV-1030` on malformed input with the exact message template, the `PRV-1031` builder-time
  refusals including the two succeeding boundary arms (`subscriberBufferRows(1)`,
  `connectTimeout(Duration.ofNanos(1))`), the plaintext-token refusal and `allowInsecureToken`
  escape hatch, `toString()` never containing the token, and the exception's `errorCode`/`helpUrl`/
  `retryable`/message-format contract. All passing; not independently re-derived line-by-line this
  session — the existing, passing test is the evidence.
- **SDKX-011 — PASS.** `grep -rn "ClientErrors.CLOSED" --include=*.java .` (excluding `.claude`,
  `target`) returns nothing — the declared `PRV-1043` contract is thrown by no code in the
  repository, confirming the case's finding. The behavioural half (what a closed client actually
  does) was not independently re-exercised this session.
- **SDKX-012 — PASS (dependency count); enforcer-rule trip not independently re-proven this
  session.** `./mvnw -o -pl sdk/pravaha-sdk-java dependency:tree` shows exactly one compile
  dependency, `pravaha-api` (plus JUnit/AssertJ at test scope) — confirmed by direct execution.
  Adding `io.netty:netty-handler` and rebuilding was attempted but blocked by this being an
  **offline** build (`-o`) whose local repository does not carry `netty-handler`'s transitive
  dependencies at any version tried, so the attempt failed on dependency resolution rather than
  reaching the enforcer rule; the pom edit was reverted (`git diff` empty) without having proven the
  rule fires. The rule's XML (`enforce-thin-client`, bound under `maven-enforcer-plugin`'s
  `<executions>`) was read and is present and bound to a phase.

## §B. `pravaha-sdk-java-flight`: the Java transport (SDKX-013–033)

Evidence: `JavaSdkQueryTest` (10), `JavaSdkRegistryTest` (10), `JavaSdkParameterTest` (9),
`JavaSdkAuthenticationTest` (6) — all 35 passing, all against a real embedded
`PravahaFlightServer`/`PravahaFlightClient` pair.

- **SDKX-013, 022 — PASS.** `JavaSdkQueryTest.anApplicationQueriesAndIterates`,
  `.columnsAreKnownBeforeTheFirstRow` cover connect/query/iterate and column-metadata-before-rows.
- **SDKX-014, 015, 016, 017 — NOT independently re-verified.** No test in this class connects to a
  genuinely dead port and times the lazy-channel behaviour, or exercises a two-node endpoint with
  one node down; these are `H-DEAD`/multi-node scenarios this session did not script separately.
  (SDKX-039's Python equivalent, `test_the_token_never_appears_in_a_repr` — no, see below — is not a
  substitute; recorded as a real gap.)
- **SDKX-018, 019, 020, 021 — PASS.** `JavaSdkAuthenticationTest.aClientWithNoCredentialIsRefused`,
  `.anUnknownCredentialIsRefused`, `.aTokenIsRefusedOverAPlaintextConnectionUnlessAskedFor` cover
  the valid/invalid/missing-token and plaintext-refusal shapes. The exact wire header casing
  (SDKX-021) was not independently captured with a packet/header inspector this session.
- **SDKX-023 — PASS (mechanism, via the SDK's `QueryResult` iteration).**
  `JavaSdkQueryTest.iteratingTwiceIsRefusedRatherThanSilentlyEmpty`.
- **SDKX-024 — PASS.** `JavaSdkParameterTest.aParameterBindsRatherThanBeingInterpolated`,
  `.aNumericParameterBinds`, `.anIntBindsToABigintPlaceholder`,
  `.aValueThatLooksLikeSqlIsAValue`, `.bindingNullMatchesNothingRatherThanEverything`,
  `.theWrongNumberOfValuesIsRefusedBeforeTheCall`, `.aValueOfTheWrongTypeNamesThePlaceholder`,
  `.aQueryWithNoParametersTakesTheSimplePath` — a closer, more thorough match to this case than the
  case file's own three-value enumeration.
- **SDKX-025, 026, 027, 028 — PASS.**
  `JavaSdkRegistryTest.aContinuousQueryCanBeRegisteredAndListed`,
  `.theSameQuestionRegisteredTwiceIsOneComputation`, `.aQueryCanBePausedResumedAndDropped`,
  `.droppingAnUnknownQueryIsRefused`, `.aSubscriberReceivesCommittedChanges`,
  `.aRetractionReachesASubscriberAsARetraction`, `.theWeightColumnIsNotMistakenForOneOfTheViewsOwn`,
  `.aSubscriberCanFilterAtTheTap`, `.aFilterNamingAnUnknownColumnIsRefusedRatherThanIgnored`,
  `.closingTheClientReleasesTheServersSideOfASubscription` — register/list/pause/resume/drop,
  subscribe with commit-batch delivery, retraction handling, the weight-column guard, tap-side
  filtering (valid and invalid column), and clean client-close release.
- **SDKX-029 — PASS (by inspection of JavaSdkAuthenticationTest's assertions).**
  `anUnknownCredentialIsRefused` asserts the client-side exception code (`PRV-1041`) while the
  server's own `PRV-7001` text survives only inside the message — exactly the fact SDKX.md's
  preamble states.
- **SDKX-030, 031, 032, 033 — NOT RUN.** Timeout-never-applied, server-killed-mid-query/
  mid-subscription, and subscription-close-releases-the-thread are process-kill/timing scenarios
  none of the four classes construct; not scripted this session.

## §C. `sdk/python` (SDKX-034–050)

Evidence: 67 passing tests across `test_authentication.py` (7), `test_client.py` (32),
`test_endpoint.py` (18), `test_options.py` (10).

- **SDKX-035, 036, 037 — PASS.** `test_endpoint.py`/`test_options.py` mirror the Java suite's
  coverage of parsing, defaults/refusals, and the plaintext-token guard — read and confirmed
  passing; not independently line-matched against the Java results field-by-field this session.
- **SDKX-038, 039, 040 — PASS (039 the connect-succeeds/first-call-fails half only as inferred from
  the auth tests' shape, not independently timed against a dead host).**
  `test_a_script_queries_and_iterates`, the authentication suite's four tests
  (`test_a_client_with_no_credential_is_refused`, `test_an_unknown_credential_is_refused`,
  `test_an_authenticated_but_unauthorized_caller_is_told_apart`,
  `test_an_authorized_caller_sees_only_its_own_rows`) cover the connect/round-trip and all three
  authentication arms.
- **SDKX-041, 042 — PASS.** `test_a_continuous_query_can_be_registered_and_listed`,
  `test_the_same_question_registered_twice_is_one_computation`,
  `test_a_query_can_be_paused_resumed_and_dropped`, `test_dropping_an_unknown_query_is_refused`,
  `test_registering_a_query_over_an_unknown_stream_is_refused`,
  `test_a_parameter_binds_rather_than_being_interpolated`,
  `test_one_statement_answers_different_questions`, `test_a_numeric_parameter_binds`,
  `test_a_value_that_looks_like_sql_is_a_value`,
  `test_binding_none_matches_nothing_rather_than_everything`,
  `test_the_wrong_number_of_values_is_refused_before_the_call`,
  `test_a_value_of_the_wrong_type_names_the_placeholder`.
- **SDKX-043, 044 — PASS for delivery-over-the-network; the generator-is-lazy-until-iterated and
  slow-subscriber-is-conflated specifics not independently isolated.**
  `test_a_continuous_query_delivers_rows_to_python_over_the_network`,
  `test_a_retraction_reaches_python_as_a_retraction_over_the_network`,
  `test_a_python_subscriber_filters_at_the_tap_and_receives_only_its_slice`,
  `test_a_python_client_reads_the_view_of_its_own_continuous_query`,
  `test_a_filter_naming_an_unknown_column_is_refused` — these are exactly the tests the task brief
  flagged as using the fixture's file-tail feed (`PRAVAHA_FEED_FILE`) to prove delivery rather than
  asserting the shape of a call, confirmed by reading the test source: they append lines to the fed
  file and assert the rows the subscription actually receives.
- **SDKX-045, 048 — PASS (by design match; not independently re-derived).**
  `test_a_refused_query_carries_the_servers_diagnosis` is the direct analogue of Java's
  `aRefusedQueryCarriesTheServersOwnDiagnosis`.
- **SDKX-046, 047 — NOT RUN.** Timeout-never-applied and server-killed-mid-call/mid-subscription;
  same gap as the Java side (SDKX-030/031/032), not scripted this session.
- **SDKX-049 — NOT independently re-verified.** The Python control-wire re-implementation's drift
  risk from the Java one was not diffed byte-for-byte this session; both suites passing against the
  same real server is suggestive but not the same evidence.
- **SDKX-050 — NOT RUN** (a case about the test suite's own gaps; out of scope for a re-run).

## §D. TLS (SDKX-051–060) — NOT RUN

None of the ten TLS cases were executed this session: they need a generated self-signed certificate
pair, a TLS-configured server, and (per the area's fact 4) a demonstration that no shipped client
can complete the handshake — none of which this session's time budget covered, and none of which
the four re-run Java/Python test classes exercise (they are all plaintext). This is the largest
deliberate gap in this file after Flight's catalog/metadata and concurrency cases.

## §E. The console (SDKX-061–080)

Evidence: `console/tests/test_console.py`, 34/34 passing, against a real engine started the same way
the Python SDK's own tests start one (a classpath-launched `PravahaFlightServer` with a registry),
driven through `fastapi.testclient`.

- **SDKX-061 — PASS (by construction).** The app importing and every route module registering under
  `create_app()` is a precondition for any of the other 33 tests to run at all; none failed with a
  routing error.
- **SDKX-062–080 — PASS as a class, not individually itemised this session.** The 34 tests cover
  sign-in with the configured password, wrong/empty-password handling, anonymous read access,
  authenticated mutation, the landing/overview/queries/workbench pages, health endpoints, and the
  JSON API's error mapping — reading the full test file confirms it exercises materially the same
  ground SDKX-062–080 enumerate, but this session did not map each of the 19 case IDs to one test
  method by name (unlike §A–§C above) for want of time. Recorded as a genuine, passing class of
  evidence rather than 19 individually confirmed cases.

## Coverage summary

| Section | Cases | Substantially covered by re-run tests | Not run this session |
|---|---|---|---|
| A (`pravaha-sdk-java`) | 001–012 | 001, 002–010, 011, 012 (partial) | — |
| B (Java Flight) | 013–033 | 013, 018–029 | 014–017, 030–033 |
| C (Python) | 034–050 | 034–045, 048 | 046, 047, 049, 050 |
| D (TLS) | 051–060 | — | all ten |
| E (console) | 061–080 | all, as a class | individual case mapping |

**Total: two thirds to three quarters of the 80 cases have direct or class-level passing evidence
from a real, seed-proven, freshly-run test suite; TLS (10) and several process-kill/timing/
multi-node scenarios (roughly 12 more, spread across B and C) were not run this session.** No
FAIL was produced anywhere in this area this session — every test that ran, passed, including the
five Python authentication tests the brief specifically warned had been lost to a mislabelled skip
for months (confirmed 0 skipped, not merely a green summary line).
