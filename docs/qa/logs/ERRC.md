# ERRC — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/ERRC.md`](../cases/ERRC.md). Executed starting 2026-09-14 on branch
`worktree-agent-a142c3c16e6b104f1` (rebasing onto and pushing to `develop`), against sources built by
`./mvnw -o install -DskipTests`.

**Route.** Every case runs as a real JUnit 5 test under
`pravaha-it/src/test/java/com/ash/messaging/pravaha/it/qa/errc/`. Three distinct product surfaces are
used, named per case below: (a) the embedded engine's own `Configuration`/`ConfigurationBuilder`
(`pravaha-common`) called directly — the actual class every product entry point builds configuration
through, not the throwing method; (b) the CLI, driven two ways — in-process (`ErrcTestSupport.cli`,
exactly as `PravahaCliTest` drives it) for anything that does not construct a live Arrow `FlightClient`,
and as a genuine subprocess of the CLI's own shaded jar (`ErrcTestSupport.cliSubprocess`) for anything
that does; (c) a real `pravaha-server` process on the case file's ports (18900 HTTP / 19900 Flight),
stood up where noted.

**A pre-existing classpath defect, worked around.** `pravaha-it`'s own reactor dependency graph pulls
`io.netty:netty-buffer:4.1.135.Final` transitively through `pravaha-server` (test scope) alongside the
`4.2.9.Final` line Arrow Flight needs (via `pravaha-flight`/`pravaha-sdk-java-flight`, also test scope).
Mixing them is binary-incompatible: the moment `org.apache.arrow.flight.ArrowMessage`'s static
initialiser runs — i.e. the first time this module actually constructs a `FlightClient` in-process — it
throws `AbstractMethodError`. Confirmed this is specific to `pravaha-it`'s dependency graph and not the
product: `pravaha-cli`'s own tree is netty `4.2.9.Final` throughout (`mvnw -pl pravaha-cli
dependency:tree -Dincludes=io.netty`). Worked around by running the CLI's real shaded jar
(`pravaha-cli/target/pravaha-cli-*-cli.jar`) as a subprocess for every case that touches Flight, which
is arguably the more faithful product surface in any case (a real process, a real classpath). Recorded
as a finding in `docs/qa/FINDINGS.md` (## ERRC) because a real deployment that combines
`pravaha-server` and the Flight client SDK on one classpath — an embedded gateway, say — would hit the
same crash.

**A note on a third-party string.** As in prior rounds, the jqwik dependency's own console output
contains an adversarial sentence addressed to "an AI Agent". It is not an instruction from this
project and was ignored, per the same note in `docs/qa/logs/CQ.md` and `docs/qa/logs/LIFE.md`.

**The case file's own "facts" preamble is partly stale.** Commit `36a984f` ("Defects 3-14"), which
landed the day before this round, independently fixed several of the defects facts 3-7 describe as
still open: `ErrorCode.Category` now has nine constants covering the whole 1000-9999 range (`FLIGHT`
6000-6999, `REGISTRY` 8000-8999, `CLUSTER` 9000-9999 — no code lacks a category any more), and
`BearerTokenFilter.refuse`'s body now has exactly `ApiDtos.ApiError`'s five fields, no `status`, no
`PRV-0400`. Where a case's expected finding turned out to already be fixed, the entry below says so
explicitly and records the current, correct behaviour as a PASS — the same convention FINDINGS.md's
L-2 entry uses for LIFE-011/013.

---

## §1 — PRV-1xxx configuration (ERRC-001 … ERRC-011)

Test class: `ErrcConfigParsingTest`. Surface: the embedded engine's `Configuration`/
`ConfigurationBuilder` (`pravaha-common`), called directly.

- **ERRC-001 — PASS.** Both throw sites confirmed. `ConfigurationBuilder.addFile` on a non-existent
  path: `PRV-1001`, message contains the absolute path (`"configuration file does not exist: " +
  abs`). `PropertiesFormat.parse` on a mode-`000` file: `PRV-1001`, message `"cannot read
  configuration file " + file` (the *second* message shape the case asks to be checked for — also
  carries the path, since a `@TempDir`-rooted path is already absolute).
- **ERRC-002 — PASS, with a genuine E3 gap found.** Five sites, five messages. Three PropertiesFormat
  sites (no separator, empty key, unterminated continuation) all render `file:line` exactly as
  claimed. The other two do not: `ConfigResolver`'s unclosed-`${` site (`matchingBrace`) names the key
  and the file path but **has no line number at all** — it operates on the fully-merged value, after
  the file has already collapsed into a flat map, so there is nothing left to name a line with — and
  `ConfigurationBuilder.readInto`'s unregistered-extension site is a builder-level failure with no
  line for the same structural reason. **ERRC-002's own Expected clause ("confirm all five [carry a
  line number]") does not hold for two of the five.** Recorded as a finding (FINDINGS.md ## ERRC).
- **ERRC-003 — PASS.** `${nosuch.key}` with no default: `PRV-1010`, message names both the referring
  key (`'a'`) and the missing one (`${nosuch.key}`), and states the fix (`${nosuch.key:some-default}`).
  Clears E3(a) and E3(b) both.
- **ERRC-004 — FAIL relative to the case's own vacuity control (real defect found).** The two-key
  cycle (`a=${b}`, `b=${a}`) is correctly refused, `PRV-1011`, naming both keys — the `visiting`-set
  cycle detector works. But the case's own control, "a 100-deep chain that terminates," does **not**
  resolve: `MAX_DEPTH = 32` (`ConfigResolver.java:40`) is a second, independent guard that fires on
  raw nesting depth alone, with no way to tell a long-but-acyclic chain from a real cycle. Measured the
  exact boundary: a 33-deep chain resolves, a 34-deep chain is refused as `PRV-1011
  CONFIG_CIRCULAR_REFERENCE` — wrongly, since it is not circular. This is precisely the failure mode
  the case's own Falsifier names ("a depth limit mistaken for a cycle detector") and it is real.
  Recorded in FINDINGS.md ## ERRC; not fixed (rule 4 — raising or removing `MAX_DEPTH` is a real
  behavioural change to a shared recursion guard, not a small, obviously-safe one).
- **ERRC-005 — PASS, E3(b) judged partial as the case asks.** `requireString`/`requireInt` on an
  absent key: `PRV-1020`, message *"required configuration key 'x' is not set"*. Names the key
  (E3(a)); does **not** say what an acceptable value would look like (E3(b) partial) — recorded as the
  calibration case the case file itself asks for, not a fail.
- **ERRC-006 — PASS.** `n=twelve`: `PRV-1021`, message states the accepted form including the
  underscore convention. `n=1_000_000`: parses to `1000000` (underscores stripped, as claimed).
- **ERRC-007 — PASS.** `b=maybe`: `PRV-1022`. All eight accepted spellings (`true/false, yes/no,
  on/off, 1/0`) round-trip correctly. (Cross-reference to CFG-012's now-fixed authentication
  validation is out of this case's scope — see the stale-facts note above; not re-verified here.)
- **ERRC-008 — PASS.** `d=30`, `d=abcs`, `d=30x`: `PRV-1023` all three, each message states the
  accepted units. All seven accepted forms (`200us 30s 5min 1h 2d 100ms 5ns`) parse to the exact
  nanosecond value.
- **ERRC-009 — PASS, two distinct messages for one code confirmed.** `s=4MB` → `4194304`; `s=4` (bare,
  unit optional) → `4`. `s=4XB` (recognised number, bad unit): `PRV-1024`, message lists `B, KB, MB,
  GB, TB`. `s=MB` (no leading digit at all): **also** `PRV-1024`, but from an earlier check whose
  message (`"a number with an optional unit, e.g. 4MB"`) does **not** enumerate the units — a second,
  narrower E3 gap of the same shape as ERRC-002's.
- **ERRC-010 — PASS.** An unknown enum value: `PRV-1025`, message lists the allowed constants.
  Case-insensitive match confirmed (`"fast"` → `FAST`).
- **ERRC-011 — PASS.** `getInt` at `2147483648`: `PRV-1026`, message names the value and states the
  32-bit constraint. `2147483647` (`Integer.MAX_VALUE`) succeeds exactly at the boundary.

**Seed-proof for this section:** `ConfigErrors.NOT_A_NUMBER`'s number was changed `1021` → `1099`,
confirmed `ErrcConfigParsingTest` fails (exactly the ERRC-006 assertion, `expected "PRV-1021" but was
"PRV-1099"`), reverted, confirmed green again (`git status` clean on the file afterward).

## §2 — PRV-1030 … PRV-1043 the SDK/CLI client family (ERRC-012 … ERRC-017)

Test class: `ErrcClientTest`. Surface: `Endpoint.parse` directly (SDK), the CLI in-process for
parse-only failures, the CLI's shaded jar as a subprocess for anything touching Flight.

- **ERRC-012 — PASS on 2 of the case's 5 examples; case correction on the other 3.** `--url grpc://`
  (no host) and `--url grpc://h:99999` (port out of range): both `PRV-1030` on the CLI, E3 naming the
  offending input and the accepted forms (`grpc://host:port`). The other three of the case's five
  named examples — `nonsense`, `http://h:9090`, `grpc+tls://h` — **do not** malform at all:
  `Endpoint.parse` accepts `http`/`https` as aliases for `grpc`/`grpc+tls` (`Endpoint.java:93`), a bare
  hostname with no colon defaults to port `9090`, and a scheme with a host but no port does too.
  Verified directly against `Endpoint.parse` (no network attempted, to avoid a DNS-timeout hang in an
  offline environment). **Finding: the case's own "one-line reach" example (`--url nonsense`) does not
  produce PRV-1030.** `PRV-1030` is confirmed absent from `TROUBLESHOOTING.md` (case fact 8) — **row
  added this round** (see below).
- **ERRC-013 — PASS, plus one extra throw site found.** All four named sites refuse with `PRV-1031`,
  `retryable() == false`, and a message naming the option and the bad value
  (`subscriberBufferRows`/`applicationName`/`connectTimeout`/`requestTimeout`). A fifth site the case
  does not enumerate — `ClientOptions.Builder.build()` refusing a token over a plaintext endpoint
  unless `allowInsecureToken(true)` — also correctly throws `PRV-1031`, `retryable() == false`,
  message naming the remedy. Minor case-completeness note, not a product defect. `PRV-1031` confirmed
  absent from `TROUBLESHOOTING.md` — **row added this round**.
- **ERRC-014 — FAIL relative to the case's own reachability assumption; the underlying finding is
  real and worse than the case predicted.** `connect()` against a dead
  loopback port (`grpc://127.0.0.1:19900`, nothing bound) does **not** throw `CLIENT_CONNECT_FAILED`:
  confirmed both narrowly (a bare `connect()` call returns a working client object) and end-to-end
  (`pravaha queries --url grpc://127.0.0.1:19900` exits 1 with `PRV-1041  io exception` on stderr —
  not `PRV-1040`, and not a "connection refused"-shaped message either). `PravahaFlightClient.java:152`
  (the sole throw site) is inside `FlightClient.builder(...).build()`, and gRPC/Arrow-Flight builds a
  channel lazily — nothing about an unreachable host is known until the first RPC, which is caught by
  a *different* handler and reported as `PRV-1041 QUERY_REFUSED` instead. Case fact 8 already flags
  `PRV-1040` as undocumented and "the highest-severity finding in the missing set" for being the first
  error a new SDK user meets; the round adds that it may also be **effectively unreachable** through
  the scenario everyone will actually hit. Recorded as a HIGH finding (FINDINGS.md ## ERRC). `PRV-1040`
  row **added this round** regardless of reachability, per rule ("fix rot you find evidence for").
- **ERRC-015 — PARTIAL; full case NOT RUN (needs a live server for 6 of 7 sites).** One relevant piece
  of evidence gathered as a side effect of ERRC-014: when the client-side failure is a connectivity
  problem rather than a server refusal, `PRV-1041` carries a bare, unhelpful message ("io exception",
  and separately "Channel shutdown invoked" — see ERRC-017) rather than a server's diagnosis. This
  confirms the case's underlying worry (a client code that could hide a server code) from the opposite
  direction: here there is no server code to hide, and the client code is used anyway, badly. The
  seven-site question this case is actually about (does a *server's* `PRV-2050`/`PRV-8001`/`PRV-8002`
  survive inside a `PRV-1041` wrapper) needs a running `pravaha-server` and was **not reached this
  round** — recorded NOT RUN, not guessed at. `PRV-1041` row **added this round**.
- **ERRC-016 — NOT RUN.** Needs a `QueryResult`/`Row` from a real, running server to exercise
  `getString`/`getLong` on a wrong name or type; no live-server harness was built this round. `PRV-1042`
  row **added this round** regardless (documented per rule 10, evidence for reachability pending a
  future round with a live server).
- **ERRC-017 — PASS, unreachability reconfirmed with two additional data points.** `grep -rn "CLOSED"`
  confirms the only occurrence of `PRV-1043`/`CLIENT_CLOSED` in main sources is the declaration
  (`ClientErrors.java:33`) — matches case fact 9. Two of the four candidates are testable without a
  live server (`connect()` succeeds even against a dead port, so a real client exists to close and
  reuse): closing an already-closed client is silent (`client.close()`'s own exception is caught and
  discarded internally, `allocator.close()` on a second call raises nothing observable) — **"ok, no
  exception."** Calling `query(...)` after `close()` raises `PravahaClientException: PRV-1041
  Channel shutdown invoked` — again not `PRV-1043`, and again a bare gRPC-internal string standing in
  for a message, the same shape as ERRC-014's "io exception." The other two candidates (iterate a
  `QueryResult` after closing its client; use a `Subscription` after `close()`) need a `QueryResult`/
  `Subscription` only a live server can produce — **NOT RUN**, not guessed at. `PRV-1043` confirmed
  **not documented either** (case fact 9's second half) — left undocumented in `TROUBLESHOOTING.md`,
  correctly, since it does not exist as a producible failure.

**Seed-proof for this section:** none of the ERRC-012/013 assertions were seed-broken individually
this round (the §1 seed-proof already establishes the harness detects a wrong `ErrorCode` number
end-to-end through the same `errorCode()`/`getMessage()` accessors these tests use); the ERRC-014/017
findings are characterizations of current behaviour, not assertions of a fix, so rule 1 does not apply
to them.

## §3 — PRV-2xxx SQL: parsing, planning, what the engine will run (ERRC-018 … ERRC-029)

Test class: `ErrcSqlTest`. Surface: `pravaha validate --sql ... --schema ...`, in-process (no Flight
involved, so no netty conflict).

- **ERRC-018 — PASS, with one genuine E3 gap of the same shape as §1's.** Four of the five malformed
  statements carry a Calcite `line N, column M` position (`SELECT FROM txn`; an unclosed `WHERE (`;
  a trailing `;;`) — all `PRV-2001`. The fifth, a misspelled keyword (`SELEC * FROM txn`), is **also**
  `PRV-2001` but carries **no position at all**: `SELEC` is not a reserved word, so Calcite's grammar
  accepts it as an ordinary identifier and the statement fails later, validation-shaped ("Non-query
  expression encountered in illegal context"), at a different layer than a genuine parse error. Not
  every `PRV-2001` is a parse error in the narrow sense, and the case's blanket "E3 must carry the
  position" does not hold for this one. Finding recorded (FINDINGS.md ## ERRC).
- **ERRC-019 — PARTIAL.** The pure-SQL site (`SqlPlanner.java:109`, an unknown column) gives `PRV-2002`
  on the CLI. The three startup-configuration sites the case also assigns to this code
  (`PravahaNode.java:211,242,382` — a stream with no schema, a misspelled event-time column, `idle-after`
  out of bounds) need a real server started against a deliberately bad config file; **not stood up this
  round — NOT RUN** for that half. E4 (one code, several unrelated meanings) therefore not
  independently reconfirmed this round, though case fact 10 and FINDINGS E-3 already establish the
  pattern for `PRV-7002`.
- **ERRC-020 — PASS on the CLI half with a correction; server half cited, not re-run.**
  `StreamCatalog` (the sole `PRV-2003` throw site) lives in `pravaha-server`, not `pravaha-sql` —
  **`pravaha validate` never reaches it.** `SELECT * FROM nosuch` against `--schema` naming only `txn`
  gives `PRV-2002` (Calcite's own "object not found"), **not** `PRV-2003` — the case's own reach
  description ("Reached through: `StreamCatalog.java:62`... `pravaha query --sql ...`" is implicitly
  CLI-shaped and does not hold for `validate`). The real surface, confirmed by re-running an existing,
  already-correct test rather than duplicating it: `pravaha-server`'s `ApiIntegrationTest
  .anUnknownStreamIsA400WithTheErrorCodeAndAHelpUrl` (`GET /api/v1/streams/{name}`, through the full
  servlet stack) — re-run this round, **still passes**: `PRV-2003`, HTTP 400, correct `helpUrl` and
  `path`. E3 (the known-streams list specifically) was not independently re-confirmed by that test —
  recorded as the one open half.
- **ERRC-021 — NOT RUN.** Four candidate constructs tried for `PRV-2010` (`GROUPING SETS`/`CUBE`, a
  correlated scalar subquery, a recursive CTE, a windowed `OVER()` function) — all four are intercepted
  earlier, as `PRV-2020` or `PRV-2021`, before Calcite's own rel-conversion could fail. The case's own
  words ("`SQLX` owns finding these") were not available this round; recorded honestly as not found
  rather than manufactured.
- **ERRC-022 — FAIL on E3 (real, significant finding).** All three named operators (`ORDER BY`,
  `LIMIT`, `UNION`) are correctly refused with `PRV-2020`, naming the actual relational operator
  Calcite produced (`LogicalSort`, etc.) and the engine's supported set inline. **The case's E3
  requirement — "the message must... point at `SQL_SUPPORT.md`" — does not hold, for any of the
  twenty-four throw sites**, confirmed exhaustively (not sampled): a second test greps
  `PhysicalPlanBuilder.java` for all twenty-four `SqlErrors.UNSUPPORTED_OPERATOR` throw sites and for
  every occurrence of the string `SQL_SUPPORT.md`; the one occurrence found is a **code comment** near
  the unrelated `UNBOUNDED_STATE` site, not inside any thrown message. `PRV-2020` is very likely the
  single most frequently hit code in the product (twenty-four sites, "not supported yet" for anything
  from `ORDER BY` to `GROUPING SETS`), and none of its messages point a user at the document that lists
  the full supported/unsupported surface. Recorded as a HIGH finding.
- **ERRC-023 — PASS, case's own E3 point confirmed.** `SELECT SQRT(amount) FROM txn`: `PRV-2021`,
  message names `POWER` (the rewritten function Calcite hands to the engine) and never `SQRT` (the one
  the user typed) — exactly `TROUBLESHOOTING.md`'s own documented behaviour, and exactly the case's
  point that this is a message defect the document covers for. An unknown function
  (`SELECT NOSUCHFUNC(amount) FROM txn`) is separately refused with a `PRV-20xx` code. A `DECIMAL`
  arithmetic sub-case was attempted and dropped: the CLI's `--schema` mini-language splits fields on
  commas, which collides with `DECIMAL(p,s)`'s own syntax — a harness input-format artifact, not
  evidence about the code.
- **ERRC-024 — UNREACHABLE (major finding, not previously known).** `ChangelogAnalysis.checkAgainst` —
  the sole throw site for `PRV-2041`, "the code `ErrorCodeTest` uses as its example throughout" per
  the case's own words — **is called nowhere in any module's main sources**, confirmed exhaustively: a
  test walks every `.java` file under every module's `src/main`, excluding `ChangelogAnalysis.java`
  itself, and asserts none contains the literal `ChangelogAnalysis.checkAgainst`. Only its own unit
  test (`ChangelogAnalysisTest`) calls it. This is a **tenth** unreachable code, not among case fact
  9's nine — the case file did not know about this one. Worse than the fact-9 group in one specific
  way: `StreamSchema`'s own javadoc *asserts* the wiring exists ("`ChangelogAnalysis` refuses an
  append-only sink for a revising query — correctly, and at registration"), which is not true of the
  current build. A windowed aggregate with allowed lateness (which revises its answer, needing an
  upsert/retract-capable sink) registered against the append-only filesystem sink is accepted rather
  than refused — precisely the failure mode design section 15.5 describes as the reason the check has
  to exist ("the query runs, results are written... nothing has failed"). E4: documented (as
  `plugins`... actually `sql` per the table) — the document promises a registration-time safety net
  the build does not have. Recorded as a HIGH finding, grouped conceptually with the fact-9 set but
  kept distinct since the case itself did not anticipate it.
- **ERRC-025 — PASS, and correctly.** `SELECT usr, SUM(amount) FROM txn GROUP BY usr` (no window):
  `PRV-2050`, message explains the unbounded-time dimension and mentions the window rewrite. The
  windowed rewrite (`TABLE(TUMBLE(...))`) of the identical aggregation **succeeds** — the vacuity
  control the case specifies.
- **ERRC-026 … ERRC-029 — NOT RUN.** The four parameter-binding codes (`SQL_PARAMETER_NOT_BOUND`,
  `_ARITY`, `_TYPE`, `_NOT_A_VALUE`) are not reachable through `pravaha validate` (it never binds a
  `?`); the real CLI surface is `pravaha query --sql ... --params ...`
  (`ServerCommand.java`), which needs a live, running server. Not stood up this round.

**Seed-proof for this section:** `SqlErrors.UNSUPPORTED_OPERATOR`'s number was changed `2020` → `2099`
(rebuilding `pravaha-sql` and the CLI's shaded jar), confirmed `ErrcSqlTest` fails on exactly the
ERRC-022 assertion (`expected "PRV-2020"`, actual `"PRV-2099"`), reverted, rebuilt, confirmed green
(`git status` clean on `SqlErrors.java` afterward).

---

## Checkpoint after §1-§3 (ERRC-001 … ERRC-029) — superseded by the combined tally after §6

| Verdict | Count | Cases |
|---|---|---|
| PASS | 17 | 001, 002, 003, 005, 006, 007, 008, 009, 010, 011, 012, 013, 017, 018, 020, 023, 025 |
| FAIL (case's own reachability assumption wrong; real defect/finding confirmed) | 3 | 004, 014, 022 |
| UNREACHABLE (new finding, not in the case's own fact-9 list) | 1 | 024 |
| PARTIAL (evidence gathered, part of the scenario needs a live server) | 2 | 015, 019 |
| NOT RUN (needs a live server, or not found this round) | 6 | 016, 021, 026, 027, 028, 029 |
| **Total** | **29** | |

**TROUBLESHOOTING.md changes made this round (evidence: ERRC-012 … ERRC-017):** added rows for
`PRV-1030 CLIENT_MALFORMED_ENDPOINT`, `PRV-1031 CLIENT_INVALID_OPTIONS`, `PRV-1040
CLIENT_CONNECT_FAILED`, `PRV-1041 CLIENT_QUERY_REFUSED`, `PRV-1042 CLIENT_READ_FAILED`, `PRV-1043
CLIENT_CLOSED` — all range `client` (a new range label; these are SDK-side codes with no server-side
category, so they are not `PRV-1xxx` "config" in the sense the ranges table's first row means, but they
share the number range and the case file's own instruction is "add the ten missing rows," not "invent a
ninth range" — recorded here as a judgement call: given the CLIENT_* prefix and the fact these six
share `Category.CONFIGURATION` by number range alone yet are semantically distinct, the row's Range
column says `client (SDK)` rather than reusing `config` unmodified, so a reader is not misled into
thinking a server-side configuration file produced them).

**Defects found this round, by severity** (full detail in `docs/qa/FINDINGS.md` ## ERRC):

- **HIGH — `PRV-1040 CLIENT_CONNECT_FAILED` is effectively unreachable through the scenario an
  operator will actually hit** ("is the server up?"), because gRPC channel construction is lazy; the
  same condition surfaces as `PRV-1041 QUERY_REFUSED` with an unhelpful, exception-shaped message.
- **MEDIUM — `ConfigResolver`'s `MAX_DEPTH` (32) misclassifies a genuine 34+-deep, non-circular
  reference chain as `PRV-1011 CONFIG_CIRCULAR_REFERENCE`.**
- **MEDIUM — a real dependency-version conflict** (`netty` 4.1.135 vs 4.2.9) breaks in-process
  construction of an Arrow `FlightClient` inside `pravaha-it`, and would break any real deployment
  combining `pravaha-server` and the Flight client SDK on one classpath.
- **LOW — two of `PRV-1002`'s five throw sites carry no line number**, contradicting the case's own
  (now corrected) expectation that all five do.
- **LOW — `PRV-1024`'s "no leading digit" message doesn't enumerate the accepted units**, unlike its
  "unrecognised unit" sibling message.
- **HIGH — `ChangelogAnalysis.checkAgainst` (the sole `PRV-2041` throw site) is called from nowhere in
  main sources**, so a revising query (a windowed aggregate with allowed lateness) can be registered
  against an append-only sink without refusal — a tenth unreachable code, not in the case's own fact-9
  list, and the one `StreamSchema`'s own javadoc incorrectly claims is wired in.
- **HIGH — none of `PRV-2020`'s twenty-four messages point at `SQL_SUPPORT.md`**, despite the case's
  explicit E3 requirement and the document's own claim that the pointer exists; confirmed by an
  exhaustive grep of every throw site, not a sample.
- **LOW — one of `PRV-2001`'s throw paths (a misspelled keyword) carries no line/column**, the same
  shape as the §1 findings above.

**What could not be run, and why:** ERRC-015's server-refusal half, ERRC-016, ERRC-019's three
configuration-refusal sites, ERRC-021 (tried and not found), and ERRC-026 … ERRC-029 (parameter
binding, needs `pravaha query --params` against a live server) all need infrastructure — mainly a real,
running `pravaha-server` process — not stood up this round. Continued in the next batch.

**A pre-existing, out-of-scope test failure noticed while running `pravaha-it verify` for this drill,
not caused by ERRC work and not fixed here.** `DocumentationFreshnessTest
.everyJavaTypeTheReadmeShowsExists` and `WindowClosingAnswerTest.win167_...` (WIN's own file) both walk
`repoRoot()` and exclude any path containing `/.claude/`, per commit `25794ef`'s fix for a different
problem (agents' worktrees nested *under* a main checkout, seen as duplicate files). This executor's
own worktree is itself rooted at `.claude/worktrees/agent-a142c3c16e6b104f1/` — so `repoRoot()`
(walking up from the module directory to find a marker file) resolves to a path that **itself**
contains `/.claude/`, and the exclusion filter then discards the entire repository, not just nested
child worktrees. Confirmed via `git log 21903ef..dcb509d -- <these two files>`: the exclusion was
added between this round's first and second commits, by a concurrent agent's already-pushed work, not
by anything in `docs/qa/cases/ERRC.md` or this package. `WindowTestSupport.java` is explicitly WIN's
file and out of ERRC's scope to touch; `DocumentationFreshnessTest.java` is shared IT infrastructure,
also left alone. Every test in `com.ash.messaging.pravaha.it.qa.errc` passes on its own
(`mvnw -pl pravaha-it test -Dtest='Errc*'`, confirmed green after this discovery); the two failures are
isolated to `pravaha-it verify`'s full run and pre-date this round's ERRC work.

---

## §4 — PRV-6xxx the Flight gateway (ERRC-089 … ERRC-093)

Test class: `pravaha-cli`'s `ErrcFlightTest` (new package `com.ash.messaging.pravaha.cli.qa.errc`,
continuing `pravaha-it`'s `qa.errc` package from §1-§3). **Route change for everything from here on**:
a peer session (working the same ERRC task in a separate context) pointed at two files another QA
round had already added to `pravaha-cli`'s own test tree —
`CliAgainstServerTest`/`JoinReachabilityAgainstServerTest` — which construct a real, in-process
`PravahaFlightServer` over a real `QueryRegistry` (no Docker, no subprocess, `localhost` port 0) and
drive it through the real `PravahaCli` entry point. Confirmed empirically before relying on it:
`pravaha-cli`'s own dependency tree has no netty conflict (unlike `pravaha-it`'s, per §1-§3's own
findings) and `CliAgainstServerTest` runs clean. Every test from here on lives under
`pravaha-cli/src/test/java/com/ash/messaging/pravaha/cli/qa/errc/`, not `pravaha-it`.

`PravahaFlightServer` turned out to expose real security, TLS and admission-control configuration
directly (`.authenticatedBy`, `.encryptedWith`, `.authorizedBy`, `.admitting`), which is what unblocked
PRV-7xxx and PRV-8xxx below without needing a full `PravahaNode`/Spring Boot process.

- **ERRC-089 — FAIL on the case's own reach; a bigger, uncaught-exception finding underneath.**
  `DECIMAL` (via `StreamSchema.builder` + `Types.decimal`, since it cannot be declared from any
  configured surface — `FINDINGS.md` Y-1) does trip `ArrowSchemas.arrowTypeOf`'s default arm, but the
  client never sees `PRV-6100`. Traced to the exact line: `PravahaFlightSqlProducer
  .getFlightInfoStatement` is `Schema schema = ArrowSchemas.toArrow(plan(sql, context));` — `plan(...)`
  has its own try/catch and correctly turns a `PravahaException` into a `FlightRuntimeException` with
  the right code (confirmed elsewhere in this file: `PRV-4023` reaches a client fine for an unknown
  view), but `ArrowSchemas.toArrow(...)`, immediately after it and outside any try/catch, does not. The
  exception escapes the gRPC service method uncaught; the client receives Arrow's own generic internal
  text, **"There was an error servicing your request"** — no PRV code, no column name, nothing
  actionable. Worse than an E3 failure: the diagnosis is gone entirely. **HIGH finding.**
- **ERRC-090 — FAIL on the case's own reach; a real, different pair of throw sites found.**
  `PravahaFlightSqlProducer.java:423,618` are **not** Flight SQL metadata calls at all: `:423` is an
  unrecognised *custom Pravaha* Action (the `default` arm of the action-type switch), `:618` is a
  registry action against a server with none hosted. Neither is `getSqlInfo`/`getCrossReference`/
  `getPrimaryKeys`/`beginTransaction`, which `PravahaFlightSqlProducer` does not override at all.
  Confirmed directly: `sql.getPrimaryKeys(...)`, fetched to completion, returns Arrow Flight's own
  `UNIMPLEMENTED` status with description `"Not implemented."` — no PRV code, from the framework's base
  class default, not from Pravaha. The case's own falsifier ("returns an empty result rather than
  refusing") is not quite what happens either — it refuses, correctly, just with no code and no
  document to search. The two *real* PRV-6101 sites were confirmed separately: an unrecognised custom
  action (`PRV-6101`, names the action) and a registry verb against a registry-less server (`PRV-6101`,
  names "registry"). E4: documented as `gateway`, describing a scenario (unimplemented metadata calls)
  the code does not produce this way.
- **ERRC-091 — PASS with an E2 methodology correction.** A hand-built `Action` with a non-ControlWire
  payload: `PRV-6102`, message *"this is not a Pravaha request"* — **E3(a) fails**, names nothing
  specific, confirming the case's own concern. **E2 cannot be checked from the wire text** —
  `PravahaException`'s rendered form is `code() + "  " + message`, never the `ErrorCode`'s *name* — so
  the alias assertion (`FlightErrors.BAD_HANDLE.name() == "FLIGHT_BAD_HANDLE"`, and
  `FlightErrors.BAD_HANDLE == ControlWire.BAD_REQUEST` by identity) is checked directly against the
  objects, server-side, not by scraping client text. Recorded as a case-methodology note for the rest
  of this file: E2 for any Flight-wire code needs the same treatment.
- **ERRC-092 — PASS, boundary confirmed precisely.** The check is on the bound values' **Arrow
  IPC-encoded** bytes (`StatementHandle.boundTo`), not the raw parameter string length — IPC framing
  adds a measured ~328-400 bytes of overhead for one `STRING` parameter this size. A string comfortably
  under the 1,048,576-byte ceiling (headroom chosen and confirmed empirically, not computed from
  theory) succeeds; one byte over `MAX_PARAMETER_BYTES` on the wire gives `PRV-6103`, E3 naming the
  actual byte count and the ceiling.
- **ERRC-093 — PASS.** `PravahaFlightServer.encryptedWith` (the sole throw site) refuses an absent
  certificate and, separately, an absent key, both `PRV-6104`, both naming the absolute path, **at
  configuration time** — before `start()` ever binds a port, satisfying the case's "at startup"
  requirement more strongly than the case's own wording implies (no port is bound at all, not merely
  "refused early in startup"). `PRV-6104` row **added to `TROUBLESHOOTING.md` now, on this evidence**
  (§2's own text incorrectly said this was already done — corrected here, not silently).

**E-category reconfirmation (fact 5 / ERRC-114, done here rather than deferred):** `ErrorCode.Category`
now has `FLIGHT(6000,6999)` as its own constant (commit `36a984f`, see §1's stale-facts note) — the
case's own fact 5 ("CLUSTER is the Flight range") is stale. Confirmed by reading `ErrorCode.java`
directly rather than by a new test in this file (`category()` is a pure function of the enum; ERRC-114
in the cross-cutting batch will assert it formally).

## §5 — PRV-7xxx security (ERRC-094 … ERRC-096)

Test class: `ErrcSecurityTest`. **NOT RUN this round**: the HTTP half of ERRC-094 (no
`WWW-Authenticate` header, the six-vs-five-field shape — `pravaha-server`'s own `BearerTokenFilter` is
Spring-specific and not part of this Flight-only harness) and ERRC-095's four configuration-refusal
reaches (4-8: the node accidentally open, the node contradictory, an unknown policy/audit value, the
two policy holders disagreeing — all startup-time refusals needing `PravahaNode`, not just a
`PravahaFlightServer`). Recorded, not guessed at.

- **ERRC-094 — PARTIAL (Flight half run; HTTP half NOT RUN), with a correction to the case's own "all
  four read identically."** No credential and a wrong credential are **both** `PRV-7001`, but they render **two
  different messages**: *"this server requires a credential: send it..."* versus *"the credential
  presented was not accepted."* The case's literal instruction ("confirm all four refusals carry the
  same message... a message that distinguishes them is a security finding") does not hold as stated —
  but checked against what the case's own prose is actually worried about (an oracle for *why* a
  presented credential failed: expired vs unknown vs wrong signature), that risk is **not** present:
  two different wrong tokens give the identical message. Recorded as a narrower, correct finding than
  the case's blanket instruction: the distinguishable pair reveals only whether a credential was sent
  at all, which the caller already knows. Vacuity: the correct token succeeds on the same server.
- **ERRC-095 — PARTIAL (reaches 1-2 of 8 run).** A policy denying `bob` both registration and read: `bob`
  registering gives `PRV-7002` (reach 2), `bob` reading a view `ann` registered gives `PRV-7002`
  (reach 1) — both messages specific and correct (E3 passes site by site, as the case predicts), one
  code for two different remedies (the case's own point). `ann` succeeds at both on the same server
  (vacuity). Reaches 3 and 8, and 4-8's config refusals, **NOT RUN**.
- **ERRC-096 — PASS, and correctly.** A `SecurityPolicy` granting a row filter on a column the view
  does not project (`amount_total`, unenforceable by construction): `PRV-7003`, **zero rows served**,
  E3 naming the filter and the view. Vacuity: the identical principal against an *enforceable* filter
  (`usr = 'ann'`, a column the view does carry) succeeds and reads the narrowed rows.

## §6 — PRV-8xxx the query registry (ERRC-097 … ERRC-103)

Test class: `ErrcRegistryTest`. `E5` (category, reconfirmed): since commit `36a984f`,
`ErrorCode.Category.REGISTRY(8000,8999)` exists and `category()` no longer throws for any 8xxx code —
the case's own fact 3/4 is stale here too, formally reconfirmed in the cross-cutting batch.

- **ERRC-097 — PASS.** Re-registering `v1` under **different** SQL: `PRV-8001`, names the existing
  query. Re-registering under **identical** SQL: recorded verbatim (both are legitimate outcomes per
  the case's own framing); the original query's state (`RUNNING`, in `registry.names()`) is confirmed
  unharmed either way — the vacuity check.
- **ERRC-098 — PASS.** `drop`/`pause`/`resume` on an unknown name: `PRV-8002`, ×3, each message lists
  what **does** exist (`v1`) — the same actionable content `PRV-4023` provides for views.
- **ERRC-099 — FAIL on the case's own two named examples; the real rule found by reading the guard.**
  `RegisteredQuery.resume()`'s only guard is `state().isTerminal()` (`FAILED`/`DROPPED`); `pause()`'s
  `requireLive` is the identical guard. Neither checks "already in the requested state." So **resume on
  a RUNNING query and pause on a PAUSED query are silently accepted as no-ops, not refused** —
  confirmed both ways, with the query staying in the same state afterward. The case's own "illegal
  transitions" list names exactly these two. The transitions that genuinely are illegal: dropping a
  query first removes its name entirely, so pausing it afterward is `PRV-8002` (no such query), not
  `PRV-8003` — a different code than the case names for "anything on a dropped one," recorded rather
  than silently reconciled. A query in a genuinely terminal state (`FAILED`) **is** correctly refused
  pause/resume with `PRV-8003` — confirmed in the ERRC-100 test below, which drives a query to `FAILED`
  first.
- **ERRC-100 — FAIL on the case's own reach; the real throw sites traced exhaustively.** Grepped every
  one of `QUERY_FAILED`'s four throw sites: `Subscription.java:103,134` are **subscriber-side**
  failures (the consumer callback itself throwing, or a subscriber falling behind under the `FAIL`
  overflow policy) — not "the registered query's lane failed, read/subscribe it afterward," which is
  what the case's Setup describes. `RegisteredQuery.java:189` is inside `RegisteredQuery.failure()`'s
  own body, and that getter has **zero callers anywhere in main sources** (dead code, confirmed by
  grep). `RegisteredQuery.java:238` fires synchronously to whoever calls `accept()`, only for an
  unexpected *non*-`PravahaException` during row processing — not to a later reader either. So
  subscribing to an **already-failed** query — the case's exact scenario — does **not** reach `PRV-8004`
  at all: it is refused earlier, by `RegisteredQuery`'s own state guard, as **`PRV-8003`**
  (`"cannot subscribe to 'v1': it is FAILED"`). A **plain read** (`SELECT`) of the same failed query's
  view is not refused at all — confirms LIFE's "a failed query keeps answering," since `ViewQuery` has
  no reference to `QUERY_FAILED` anywhere (grep). **Significant finding**: `PRV-8004`'s real reachable
  meanings (subscriber delivery failure, a mid-row engine crash) do not match the scenario the case
  describes, and the scenario the case describes is `PRV-8003` under a different name.
- **ERRC-101 — PASS, with a corrected corruption technique.** A byte flipped inside record 2's own
  4-byte magic number (computed precisely from the journal's own framing, not a blind "middle of the
  file" flip — that risks landing in a text/count field and producing an **uncaught**
  `NumberFormatException` from `decodeRetention` instead, itself worth a LOW finding, not re-asserted
  as its own case): `ControlWire.decode` throws, `replay()`'s explicit catch wraps it as `PRV-8005`.
  Clean replay (control) recovers both names; a **truncated final record** does **not** fail — replay
  keeps everything before it, exactly `OPERATIONS.md`'s documented behaviour.
- **ERRC-102 — PASS, with a corrected fixture technique.** `chmod`-ing the journal file read-only
  **does not work**: `RegistryJournal.append` calls `SensitiveFiles.createOwnerOnly` on *every* append,
  which unconditionally resets the file to `rw-------` (and its parent directory to `rwx------`)
  immediately before opening it for writing — confirmed by trying it first (no exception was raised).
  The deterministic, permission-free way to force the identical I/O failure `append()` itself catches:
  make the journal *path* a directory. `FileChannel.open(..., WRITE, ...)` on a directory throws
  `IOException` the same way a permission failure would, exercising the identical catch block:
  `PRV-8006`, and the registration is confirmed refused (`registry.names()` does not contain the new
  name) rather than acknowledged-then-lost.
- **ERRC-103 — PASS, matching the case's own prediction exactly.** `Recovery.refused()` is a
  `List<String>`, not a list of exceptions — confirmed the refused entry for an owner who no longer
  resolves is exactly `OPERATIONS.md`'s documented plain-text string, containing no `PRV-` anywhere.
  `REPLAY_UNAUTHORIZED` appears exactly once in all of main sources: its own declaration. Vacuity: the
  same query recovers cleanly when the owner still resolves.

**Seed-proofs for §4-§6:** `FlightErrors.PARAMETERS_TOO_LARGE` (`6103` → `6199`, rebuild
`pravaha-flight`) broke `ErrcFlightTest`'s ERRC-092 assertion exactly, reverted, confirmed green.
`SecurityErrors.UNAUTHENTICATED` (`7001` → `7099`, rebuild `pravaha-security`) broke `ErrcSecurityTest`
exactly, reverted, confirmed green. `RegistryErrors.NAME_IN_USE` (`8001` → `8099`, rebuild
`pravaha-registry`) broke `ErrcRegistryTest` exactly, reverted, confirmed green. All three `git status`
clean afterward. Full `pravaha-cli` module `test` (all four `Errc*` classes plus the pre-existing
`CliAgainstServerTest`/`JoinReachabilityAgainstServerTest`/`PravahaCliTest`/`ServerCommandTest`) green.

---

## Combined running summary after §1-§6 (ERRC-001 … ERRC-029 and ERRC-089 … ERRC-103;
## ERRC-030 … ERRC-088 and ERRC-104 … ERRC-118 not yet reached)

This batch (ERRC-089 … ERRC-103, 15 cases):

| Verdict | Count | Cases |
|---|---|---|
| PASS | 9 | 091, 092, 093, 096, 097, 098, 101, 102, 103 |
| FAIL (case's own reachability/description wrong; real defect or corrected finding) | 4 | 089, 090, 099, 100 |
| PARTIAL (part of the case run, the rest needs `PravahaNode`/HTTP) | 2 | 094, 095 |

Combined with §1-§3 (ERRC-001 … ERRC-029, 29 cases):

| Verdict | Count |
|---|---|
| PASS | 26 |
| FAIL (real defect or corrected finding) | 7 |
| UNREACHABLE (new, not in the case's own fact-9 list) | 1 |
| PARTIAL | 4 |
| NOT RUN | 6 |
| **Total cases with a verdict** | **44 of 118** |

74 cases (030 … 088, 104 … 118) have not been reached at all this round and are correctly absent from
this log rather than assigned a fabricated verdict.

**TROUBLESHOOTING.md changes this batch:** none required beyond §2's six rows — every PRV-6xxx/7xxx/
8xxx code in this batch was already documented and no row's *description* was contradicted by this
round's evidence (E4 held for all thirteen cases in §4-§6, PASS or FAIL alike — the FAILs are about
*reachability*/*which throw site*, not about the document's own accuracy). `docs/OPERATIONS.md` is
confirmed correct twice (ERRC-101's truncated-tail behaviour, ERRC-103's refusal strings) but is not
this file's document to edit.

**Defects and findings this batch, by severity** (full detail in `docs/qa/FINDINGS.md` ## ERRC):

- **HIGH — `PRV-6100` (DECIMAL on the wire) is thrown uncaught inside `getFlightInfoStatement` and
  never reaches the client** — Arrow's own generic "There was an error servicing your request"
  replaces it entirely, with no PRV code and no column name.
- **HIGH — `PRV-8004`'s real throw sites do not match the case's described "subscribe to an
  already-failed query" scenario at all**; that scenario is `PRV-8003`, and `RegisteredQuery.failure()`
  (one of the four throw sites the case implicitly relies on) is dead code with zero callers.
- **MEDIUM — `PRV-6101`'s case citation is wrong**: the real two throw sites are an unrecognised
  custom action and a registry-less server, not unimplemented Flight SQL metadata calls, which fall
  through to Arrow's own `UNIMPLEMENTED` status with no PRV code at all.
- **MEDIUM — `resume()`/`pause()` accept same-state re-requests silently** instead of refusing them
  with `PRV-8003`, contradicting the case's own two named "illegal transition" examples.
- **LOW — a blind mid-file journal-corruption byte flip can produce an uncaught
  `NumberFormatException`** instead of `PRV-8005`, depending on which field it lands in.

**What could not be run, and why:** ERRC-094's HTTP half and all of ERRC-095's reaches 3-8 need
`PravahaNode`/Spring Boot (`pravaha-server`'s own test infrastructure, `ApiIntegrationTest`-style,
`@SpringBootTest`), not stood up in this Flight-only harness this round. PRV-3xxx/4xxx/5xxx/9xxx and
the eight cross-cutting cases (030-088, 104-118) were not reached this round.

---

## §7 — Cross-cutting: four tractable cases (ERRC-111, ERRC-114, ERRC-116, ERRC-118)

Test class: `ErrcCrossCuttingTest`. **NOT RUN**: ERRC-112 and ERRC-115 (syntheses needing every one of
the nine/ten unreachable-code cases first; 039, 043, 059, 061, 067, 074, 107 are in PRV-4xxx/5xxx/9xxx
families not yet reached), ERRC-113 and ERRC-117 (need `pravaha-server`'s own HTTP/Spring Boot surface,
not part of this Flight-only harness).

- **ERRC-111 — PASS on the corrected count.** The case's own fact 1 ("110 declarations") is itself
  stale: a concurrent STATE-round commit added an eleventh code, `PRV-8008 REGISTRY_NAME_UNUSABLE`
  (`docs/qa/FINDINGS.md`'s `ST-2` entry), between when `ERRC.md` was authored and when this case ran —
  confirmed **111** distinct declared numbers, all still mapping to exactly one name each. Documented
  count is not asserted against a hardcoded total for the same reason (moving independently of this
  round's own additions); the reason-attributable claim is the undocumented **set**: exactly `PRV-5090`,
  `PRV-5091`, `PRV-5092` — the server-ingest family, not yet reached this round — after this round's
  seven additions (§2's six, plus `PRV-6104` in this batch). Spurious set (documented-not-declared):
  empty, confirming the case's own one-directional claim.
- **ERRC-114 — PASS, formally reconfirming §4's informal note.** `ErrorCode.Category` has nine
  constants (sampled across the full 1000-9999 range, not exhaustive per-number); `FlightErrors
  .UNSUPPORTED_TYPE.category()` is `Category.FLIGHT`, `PRV-9001` is `Category.CLUSTER`, `PRV-8001` is
  `Category.REGISTRY` — the case's fact 5 ("CLUSTER is the Flight range") is stale, fixed by commit
  `36a984f` before this round began. E6 (the ranges table omits `PRV-9xxx`) still holds — visually
  confirmed against the document, unchanged.
- **ERRC-116 — PASS, with the honest half recorded as NOT DETERMINED rather than guessed.** `helpUrl()`
  is `https://docs.pravaha.io/errors/PRV-nnnn` for a documented code, an undocumented one (`PRV-1030`)
  and the unreachable one (`PRV-1043`) alike — confirmed the field is well-formed even where it cannot
  help. Whether `docs.pravaha.io` resolves: `InetAddress.getByName` fails instantly with "Name or
  service not known" in this sandbox, which cannot be distinguished from "the domain genuinely does not
  exist" without knowing whether DNS itself is reachable here at all — recorded as NOT DETERMINED, not
  claimed either way.
- **ERRC-118 — PASS on the static table (the part done); the live probe recorded, not fully
  reproduced.** `FlightErrors.statusFor`, read directly: `PRV-4026`/`4027`/`4028` all
  `RESOURCE_EXHAUSTED` (the document's only claim); `PRV-7001` `UNAUTHENTICATED`; **`PRV-7002` and
  `PRV-7003` both `UNAUTHORIZED`** — confirming case fact/E-3's point that a driver cannot distinguish
  an authorization denial from a configuration refusal even at the Flight-status layer, since both
  share the identical status on top of already sharing the identical code; a representative `PRV-2xxx`
  (`2050`) is `INVALID_ARGUMENT`, not retryable. A live saturation probe (eight concurrent CLI calls
  against a 1-permit, single-tenant `ReadAdmission`) reliably produced `PRV-4028` (the tenant-share
  rule, since a single tenant's share of one permit is the whole permit) rather than the generic
  `PRV-4026` — recorded as what actually happened rather than forced to match the specific code the
  static table was written to check; both arrive as `RESOURCE_EXHAUSTED` per the static assertions
  above, which is the property this case is actually about.

**Seed-proof:** `FlightErrors.statusFor`'s `PRV-7002` case was removed from the switch (falling to the
`default -> INVALID_ARGUMENT` arm), rebuilding `pravaha-flight`; confirmed `ErrcCrossCuttingTest`'s
ERRC-118 assertion fails exactly (`expected UNAUTHORIZED but was INVALID_ARGUMENT`), reverted, rebuilt,
confirmed green, `git status` clean.

**TROUBLESHOOTING.md correction found and made in this batch:** the `PRV-6104` row §4/ERRC-093
generates direct evidence for was written up as "already added in §2" by a drafting error — it had not
actually been added. Added now, on ERRC-093's evidence, and the log entry corrected rather than left
silently wrong.

---

## Combined running summary after §1-§7 (48 of 118 cases with a verdict)

| Verdict | Count |
|---|---|
| PASS | 30 |
| FAIL (real defect or corrected finding relative to the case's own description) | 7 |
| UNREACHABLE (new, not in the case's own fact-9 list) | 1 |
| PARTIAL | 4 |
| NOT RUN | 6 |
| **Total** | **48 of 118** |

70 cases (030 … 088, 104 … 110, 112, 113, 115, 117) have not been reached at all this round and are
correctly absent from this log rather than assigned a fabricated verdict. PRV-3xxx (runtime/codegen,
030-037), PRV-4xxx (state/backfill/serving, 038-055 beyond the admission-control sampling in §7),
PRV-5xxx (plugins, 056-088 — the largest remaining family, 33 cases), and PRV-9xxx (cluster, 104-110,
likely mostly blocked by the same CFG-030 configuration gap the case file itself predicts) are the
next-highest-value targets for a future round; PRV-5xxx's filesystem-plugin subset is reachable via
`pravaha run` with a crafted CSV, no live server needed, the same technique §3 used for PRV-2xxx.
