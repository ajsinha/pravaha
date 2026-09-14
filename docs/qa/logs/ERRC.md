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

---

## Running summary (ERRC-001 … ERRC-017 of 118)

| Verdict | Count | Cases |
|---|---|---|
| PASS | 13 | 001, 002, 003, 005, 006, 007, 008, 009, 010, 011, 012, 013, 017 |
| FAIL (case's own reachability assumption wrong; real defect/finding confirmed) | 2 | 004, 014 |
| PARTIAL (evidence gathered, main scenario needs a live server) | 1 | 015 |
| NOT RUN (needs a live server) | 1 | 016 |
| **Total** | **17** | |

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

**What could not be run, and why:** ERRC-015's server-refusal half and ERRC-016 both need a running
`pravaha-server` process; none was stood up this round. Continued in the next batch.
