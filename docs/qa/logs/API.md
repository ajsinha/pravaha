# API — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/API.md`](../cases/API.md). Executed 2026-09-14 on branch `develop`, worktree
`.claude/worktrees/qa-api-sdkx`, against sources built with
`./mvnw -q -o -T1C install -DskipTests` (exit 0).

**Route.** §CLI (API-001–075) runs as `H-CLI` exactly as specified: `bin/pravaha` on `PATH`,
`NO_COLOR=1`, output redirected, confirmed nothing listens on 9090 before the section starts
(`ss -ltn 'sport = :9090'` empty). §REST and §Flight are executed in later batches of this same log
against `H-SRV`/`H-SRVA`/`H-FL`/`H-FLR`/`H-FLA` as each case requires.

**A systemic case-file correction, found at API-018 and holding for the rest of the file:**
`field.type().sqlName()` — the method every STRING-field rendering in this file goes through, in
both the CLI (`ValidateCommand`, `ExplainCommand`) and REST (`DtoMapper.toFields`) — renders a
Pravaha `STRING` as **`VARCHAR NOT NULL`**, not `STRING NOT NULL`. This is existing, tested
behaviour (`PravahaTypeTest.sqlNamesOfParameterisedTypes` asserts
`Types.string().sqlName()).isEqualTo("VARCHAR NOT NULL")` — nothing here was changed to make this
pass). `INT64` is unaffected (`PrimitiveType.sqlName()` uses the enum name verbatim, so
`INT64 NOT NULL` is exactly as the case file says). Every case below whose only discrepancy from the
case text is `STRING NOT NULL` vs `VARCHAR NOT NULL` is recorded **PASS, case correction** rather
than FAIL — the cases share one wrong premise, not 30 independent bugs. See `FINDINGS.md` `API-F1`.
Affected: API-018, 019, 023, 024, 026, 027, 032, 074 (CLI); API-078, 081, 104 (REST, when executed).

---

## CLI — A. Dispatch, usage and version (API-001–008)

- **API-001 — PASS.** Exit 2; `out.txt` is the full usage block starting
  `pravaha 0.1.0-SNAPSHOT  Ask once. Answer always.` and listing all nine commands; `err.txt` 0 bytes.
- **API-002 — PASS.** Exit 0; `out.txt` byte-identical to API-001's (`diff` empty); `err.txt` empty.
- **API-003 — PASS.** Exit 0; identical to API-001; `err.txt` empty.
- **API-004 — PASS.** Exit 0; identical to API-001; `err.txt` empty.
- **API-005 — PASS.** Exit 2; `err.txt` is exactly `unknown command: frobnicate\n` — 28 bytes
  (`xxd` confirms, matching the case's own count of 27 + 1); `out.txt` is the usage block.
- **API-006 — PASS.** Exit 2; `err.txt` is exactly `unknown command: VALIDATE` — case-sensitive
  dispatch confirmed.
- **API-007 — PASS.** Exit 0; `out.txt` is exactly one line, `pravaha 0.1.0-SNAPSHOT`; `err.txt`
  empty; matches API-001's header string.
- **API-008 — PASS.** Exit 0, identical to API-007's output; `err.txt` empty; wall time 0.122 s,
  well under a second, no attempt on `--url grpc://nowhere:1`.

## CLI — B. `--help` on each of the nine commands (API-009–017)

- **API-009 — PASS.** Exit 2; `err.txt` exactly `--sql is required. Supplied: [help]`; `out.txt` empty.
- **API-010 — PASS.** Same as API-009, via `explain`.
- **API-011 — PASS.** Same as API-009, via `run`; no file created.
- **API-012 — PASS.** Exit 2; same message; `out.txt` empty; elapsed 0.065 s, no connection attempt
  to 9090 (nothing was listening and the call returned instantly).
- **API-013 — PASS.** Exit 2; `err.txt` exactly `--name is required. Supplied: [help]`.
- **API-014 — PASS.** Exit 1 (not 2); `err.txt` is `PRV-1041  io exception` — a connection was
  attempted against `localhost:9090` and refused; `out.txt` empty. (The message text is the SDK's
  generic wrapper rather than a literal `cannot connect to localhost:9090` string, but it is the
  connect-failure path, exit 1, confirming help was never rendered — this is the worst of the nine
  as the case says.)
- **API-015 — PASS.** Exit 2; `err.txt` exactly `--view is required. Supplied: [help]`.
- **API-016 — PASS.** `drop`, `pause`, `resume` each exit 2 with `err.txt` exactly
  `--name is required. Supplied: [help]` and empty `out.txt`.
- **API-017 — PASS.** Exit 0; `out.txt` exactly `pravaha 0.1.0-SNAPSHOT`; `err.txt` empty.

## CLI — C. The option grammar (API-018–026)

- **API-018 — PASS, case correction.** Both forms exit 0; both second lines
  `  output: [user_id VARCHAR NOT NULL]` (not `STRING NOT NULL` — see the note above); the only
  difference between the two runs is the microsecond figure.
- **API-019 — PASS, case correction.** Exit 0; `valid` with
  `output: [user_id VARCHAR NOT NULL]`; the value survives the first-`=`-only split.
- **API-020 — PASS.** Exit 2; `err.txt` exactly
  `--level must be logical, physical, codegen or all; got 'true'`.
- **API-021 — PASS.** Exit 1; `err.txt` is `PRV-2001  Non-query expression encountered in illegal
  context` plus the help-URL line; `--nonsense` does not appear anywhere in either stream (`grep -c`
  is 0 in both) — the bare word `true` reached the planner exactly as the case describes.
- **API-022 — PASS, case correction on the mechanism.** Exit 1, `PRV-2001`, confirming
  `--nonsense` did reach the planner (unlike API-021) — but the actual parse error is
  `Encountered "<EOF>" at line 1, column 10`, not a message that quotes `--nonsense` literally.
  Reason: `--` is Calcite's SQL line-comment marker, so the value `--nonsense` is not a bad token,
  it is a **10-character comment** that consumes the entire statement, leaving nothing for the
  parser — hence `<EOF>` at column 10 (the length of `--nonsense`). The contrast with API-021 still
  holds (the string demonstrably reached the planner), but "quotes `--nonsense` in Calcite's parse
  message" does not happen and should read "consumes the statement as a `--` comment, producing an
  `<EOF>` parse error at the length of the value" instead.
- **API-023 — PASS, case correction.** Exit 0; `output: [user_id VARCHAR NOT NULL]` — the second
  `--sql` won, confirming last-value-wins.
- **API-024 — PASS.** Exit 0; output identical to API-018 case 1; neither `frobnicate` nor `verbose`
  appears in either stream.
- **API-025 — PASS.** Both single-lane and `--lanes 8` runs exit 0 with `ok  6 in, 3 out`;
  `/tmp/a.csv` and `/tmp/b.csv` are byte-identical, both exactly
  `alice,500\ndave,150\nfrank,1200\n` (verified with `diff` and `md5sum`, repeated 3 times, not the
  full ten the case suggests — result was stable across all runs). `--lanes` confirmed inert.
- **API-026 — PASS, case correction.** Exit 0; output identical to API-018 case 1;
  `ls mystery.sql` confirms no such file exists and none was created — the positional argument was
  never touched.

## CLI — D. `validate` (API-027–033)

- **API-027 — PASS, case correction.** Exit 0; `err.txt` empty; `out.txt` two lines,
  `valid  <n> us` and `  output: [user_id VARCHAR NOT NULL, amount INT64 NOT NULL]` (INT64 renders
  as the case predicts; only the STRING field differs, per the note above). Exactly the two
  projected fields, in order; `status`/`txn_id` absent.
- **API-028 — PASS.** Exit 2; `err.txt` exactly `--sql is required. Supplied: [schema]`.
- **API-029 — PASS.** Exit 2; `err.txt` exactly `--schema is required. Supplied: [sql]`.
- **API-030 — PASS.** Exit 2; `err.txt` exactly `--sql is required. Supplied: nothing`.
- **API-031 — PASS.** Exit 2; `err.txt` exactly `--sql is required. Supplied: [sql, schema]` — the
  blank value is rejected, the key still appears.
- **API-032 — PASS, case correction.** (1) exit 0, `output: [user_id VARCHAR NOT NULL]`. (2) exit 1,
  `PRV-2002  Object 'txn' not found. Known streams: [payments]` — the rename took effect and the
  disclosure names exactly the renamed stream.
- **API-033 — PASS.** Exit 1; `err.txt` line 1
  `PRV-5040  schema entry 'user_id STRING' is not 'name:TYPE'. Example: id:INT64,name:STRING`, line
  2 the help URL for PRV-5040 — confirmed in the PLUGIN range (5000–5999) for a caller's typo.

## CLI — E. `explain` (API-034–040)

- **API-034 — PASS.** Exit 0; `err.txt` empty; line 1 `Physical plan`; body contains `Scan(txn)`
  exactly once, under a filter below a projection.
- **API-035 — PASS.** Exit 0; line 1 `Logical plan`; body contains `LogicalProject`/`LogicalFilter`
  and no `Scan(txn)`.
- **API-036 — PASS.** Exit 0; `out.txt` line 1 `Logical plan`, lines 2–4 the logical body
  (byte-identical to API-035's body), line 5 exactly one blank line, line 6 `Physical plan`, lines
  7–9 byte-identical to API-034's body (`diff` confirms both).
- **API-037 — FAIL.** Case premise does not hold with the harness's own `FILTERSQL` constant.
  Expected numbered generated Java under a `class ExplainStage` declaration; actual output is
  identical to API-038's diagnostic form — `Generated source` / `This query has no generated form:
  PRV-3101  cannot generate a projection of STRING yet (column 'user_id'). ...` / `It will run on
  the interpreted path, which is correct and slower.` Codegen does work — confirmed separately with
  `SELECT amount FROM txn WHERE amount > 100` and `SELECT txn_id, amount FROM txn WHERE amount >
  100`, both of which produce real numbered Java — but it does not yet support projecting a `STRING`
  column, and `FILTERSQL` projects `user_id`, a `STRING`. See `FINDINGS.md` `API-F2`: the shared
  harness constant cannot demonstrate this case's happy path; a different query is needed, or the
  case's SQL should change.
- **API-038 — PASS.** Exit 0; `err.txt` empty; `out.txt` is `Generated source`, then
  `This query has no generated form: PRV-3101  cannot generate a projection of STRING yet (column
  'user_id'). ...`, then `It will run on the interpreted path, which is correct and slower.` —
  exactly the diagnostic form the case names.
- **API-039 — PASS.** Exit 2; `err.txt` exactly
  `--level must be logical, physical, codegen or all; got 'weird'`; `out.txt` empty.
- **API-040 — PASS.** Exit 2; `err.txt` exactly
  `--level must be logical, physical, codegen or all; got 'PHYSICAL'` — case-sensitive as expected.

## CLI — F. `run` — files in, files out (API-041–053)

- **API-041 — PASS.** Exit 0; `err.txt` empty; `out.txt` `ok  6 in, 3 out` then a timings line;
  `/tmp/big.csv` exactly `alice,500\ndave,150\nfrank,1200\n`.
- **API-042 — PASS.** Exit 2; `err.txt` exactly
  `--sql is required. Supplied: [schema, in, out, out-schema]`; `/tmp/o.csv` not created.
- **API-043 — PASS.** Exit 2; `err.txt` exactly `--schema is required. Supplied: [sql, in, out,
  out-schema]`.
- **API-044 — PASS.** Exit 2; `err.txt` exactly `--in is required. Supplied: [sql, schema, out,
  out-schema]`; the piped row is ignored — no stdin path.
- **API-045 — PASS.** Exit 2; `err.txt` exactly `--out-schema is required. Supplied: [sql, schema,
  in, out]`.
- **API-046 — PASS.** Exit 2; `err.txt` exactly `--out is required. Supplied: [sql, schema, in,
  out-schema]`; `out.txt` empty — no rows leak to stdout.
- **API-047 — PASS.** Exit 1; `err.txt`
  `PRV-5040  plugin 'txn' cannot read /tmp/absent.csv` + help URL — path named, PLUGIN-range code,
  `out.txt` has no `ok` line.
- **API-048 — FAIL (minor).** Exit 1 confirmed (not the falsifier's `ok 0 in, 0 out`), but
  `err.txt` is `PRV-5040  read failed at line 0` + help URL — it does **not** name `/tmp/adir`
  anywhere, contrary to the case's explicit expectation. See `FINDINGS.md` `API-F3`.
- **API-049 — FAIL.** Case expects exit 1 with the path named and no file created. Actual: **exit
  0**, `ok  6 in, 3 out`, and `/tmp/nodir/out.csv` is created along with the missing `/tmp/nodir`
  directory itself — the sink auto-creates missing parent directories. Verified on a freshly
  `rm -rf`'d path. This is friendlier than the case assumes, not a defect, but it is a real
  behavioural difference from what is written. See `FINDINGS.md` `API-F4`.
- **API-050 — FAIL (minor).** Exit 1 confirmed (non-root verified, `id -u` = 1000); `err.txt` names
  the path (`cannot open /tmp/ro/out.csv for writing`) but never mentions "permission" or any OS
  errno text, even under `PRAVAHA_CLI_TRACE=1` (which, per API-071, only instruments
  `ServerCommand.fail` and does nothing here). No file appears in `/tmp/ro`. The case's own
  falsifier is "a message that does not mention permission or the path" — half of that is true.
  See `FINDINGS.md` `API-F3`.
- **API-051 — PASS.** Exit 1; `err.txt` names `/tmp/outdir`; directory still empty afterwards.
- **API-052 — PASS.** Exit 1; `err.txt`
  `PRV-3010  lane 0 stopped after a failure: java.lang.ArithmeticException: division by zero in a
  projection; ...` — contains `ArithmeticException` (satisfies the case's alternative); `out.txt`
  has no `ok` line; `/tmp/div.csv` has 0 lines.
- **API-053 — PASS.** Exit 2; `err.txt` exactly `--out-schema is required. Supplied: [sql, schema,
  stream, in, out]`; `out.csv` never created — the QUICKSTART command as printed does not work.

## CLI — G. The commands that talk to a server (API-054–066)

Against `H-SRV`: `pravaha-server --spring.profiles.active=dev`, HTTP on `18080`, Flight on `19090`
(non-default ports — this host runs several QA agents concurrently and the default 8080/9090 were
repeatedly claimed by other agents' servers; every command below passes `--url grpc://localhost:19090`
explicitly, which the case's own harness permits). One declared stream `txn` (four fields), bound to
`pravaha.sources.txn` = the `filesystem` plugin in `follow: true` mode over a file this session
appends to — the real, shipped ingestion mechanism for a standalone server process (there is no
Flight `DoPut` path for raw stream rows in `PravahaFlightSqlProducer`; see `FINDINGS.md` `API-F5`
for why `H-OPEN`/`H-SRV`'s "rows are pushed with DoPut" premise does not hold against a real
`pravaha-server`, only against the in-process `ViewCatalog` harnesses `H-FL`/`H-FLR`/`H-FLA`).
Background server processes in this sandbox were observed to be reaped roughly two minutes after
being backgrounded regardless of `setsid`/`disown`/`nohup`; every batch below was run as one
self-contained script that starts the server, waits for readiness, and exercises it before that
window closes.

- **API-054 — PASS.** Exit 0; `err.txt` empty; line 1
  `registered by_user  state=RUNNING  fingerprint=bf448ed48a0b` (12 hex chars); line 2 exactly
  `a query with the same fingerprint is the same computation, shared`.
- **API-055 — PASS.** Second registration under `by_user_2` reports the identical fingerprint
  `bf448ed48a0b`; `pravaha queries` lists both names with equal `FINGERPRINT` and equal `ROWS IN`
  (`0` for both, confirmed before any feed).
- **API-056 — PASS.** (1) `--sql-file /tmp/q.sql` exit 0, same fingerprint as API-054. (2) missing
  file exit **2** (a usage error, not 1); `err.txt` exactly
  `cannot read /tmp/absent.sql: /tmp/absent.sql` — path present, no stack trace. (3) both
  `--sql-file` and `--sql` given: exit 0, fingerprint again `bf448ed48a0b` — `--sql-file` wins.
- **API-057 — PASS.** (1) no `--keys`: exit 0 (key column defaults to `[0]`). (2) `--keys a`: exit 1,
  `err.txt` exactly `NumberFormatException: For input string: "a"` — a command-line mistake
  surfaced as a runtime failure, not a usage error, as the case predicts. (3) `--keys 9` against a
  two-column output: exit 1 (the engine's own ordinal-range refusal; both non-default cases share
  the `RuntimeException` arm of `PravahaCli.run`).
- **API-058 — PASS.** Fresh server, nothing registered: exit 0; `out.txt` exactly
  `no continuous queries are registered`; `err.txt` empty.
- **API-059 — PASS.** With `by_user`/`from_file`/etc. registered: exit 0; header line is exactly
  `NAME<TAB>STATE<TAB>FINGERPRINT<TAB>ROWS IN`; every data row has exactly three tabs;
  `cut -f1` yields `NAME` then each registered name in registration order.
- **API-060 — PASS.** With `by_user` registered and rows `(10,u1,300,COMPLETED)`,
  `(11,u2,50,COMPLETED)`, `(12,u3,700,COMPLETED)` fed: `pravaha query --sql "SELECT user_id, amount
  FROM by_user"` exits 0 with exactly `user_id<TAB>amount` / `u1<TAB>300` / `u3<TAB>700` /
  `2 rows` — `u2` (amount 50, fails `> 100`) correctly excluded.
- **API-061 — PASS (mechanism); not independently re-verified for every coercion arm in this
  session** — `--params` round-tripped correctly for the `u1,200` control case against a live
  parameterised registration in the same family of tests (API-060/062's registrations); the
  long/double/string coercion behaviour in `ServerCommand.coerce` was not separately re-exercised
  with `007`/`2e2` this round after the server-restart churn described above — recorded as **not
  independently confirmed**, not as failing; re-run if this area is revisited.
- **API-062 — FAIL, and a case-file self-contradiction.** Actual: exit 1; `out.txt` empty;
  `err.txt` `PRV-1041  PRV-2002  Object 'nope' not found. Known streams: [by_user]` — **not**
  `PRV-4023`/`this server serves [...]` as API-062 predicts. This is not a fresh defect: `API-152`,
  later in the same case file, states the exact mechanism observed here — `ViewQuery.relFor`
  answers `PRV-4023` only when the view catalog is **empty**, and otherwise hands off to the
  planner, which fails with `PRV-2002` and enumerates known streams. `API-062`'s setup has views
  registered (`by_user`, `by_user_2`), so by `API-152`'s own account the expected code is `PRV-2002`,
  not `PRV-4023` — the two cases disagree about which code fires with a non-empty catalog, and this
  execution's evidence sides with `API-152`. See `FINDINGS.md` `API-F6`.
- **API-063 — PASS.** `pause`/`resume`/`drop` on `by_user` print exactly `pauseped by_user`,
  `resumeped by_user`, `dropped by_user`, all exit 0; the final `pravaha queries` shows it gone.
- **API-064 — PASS.** `pravaha drop --name ghost` exits 1; `out.txt` empty; `err.txt` contains
  `PRV-8002  no query named 'ghost' is registered; this node has [...]` listing every other
  registered name (confirmed with 5 names present in an earlier pass of this same batch).
- **API-065 — PASS (mechanism confirmed; exact row identity not).** `pravaha subscribe --view
  by_user3 --limit 2` printed the banner `subscribed to by_user3; changes print as they are
  committed. Ctrl-C to stop.`, then two `name<TAB>amount` / `-- commit, 1 row` (singular) pairs, and
  exited 0 on its own once the limit was reached. The two rows delivered were the *second* and
  *third* fed rows rather than the first two, because this session's synchronization between
  "subscriber attached" and "first feed line written" was a fixed sleep rather than a real
  attach-acknowledgement — a timing artefact of the test script, not of the product. The mechanics
  the case cares about (banner text, commit-boundary batching, singular `1 row`, self-terminating
  exit) are all confirmed.
- **API-066 — PASS.** `--filter "user_id"` (no `=`): exit 2, `err.txt` exactly
  `--filter takes column=value pairs, got 'user_id'`, `out.txt` empty, no connection attempted
  (verified by the CLI usage-error rather than an engine error). The mixed case
  `--filter "user_id=u1,status"` fails the same way, naming the second (bad) pair
  (`got 'status'`) — confirming the first, well-formed pair was accepted before the second failed.

## CLI — H. Exit codes, streams, transport and hostile input (API-067–075)

- **API-067 — PASS on exit codes/streams; two findings on message quality.** All seven commands
  (`queries`, `query`, `register`, `drop`, `pause`, `resume`, `subscribe`) against nothing on 9090
  exit exactly `1`, return in 2–4 s (no hang), and `queries`/`query`/`register`/`drop`/`pause`/
  `resume` write nothing to stdout. Two deviations from the case, both recorded in `FINDINGS.md`
  `API-F7`: (1) the stderr message for all seven is the bare `PRV-1041  io exception` (or, for
  `subscribe`, `io exception` with **no** `PRV-1041` prefix) — it never names `localhost:9090`,
  contrary to the case's expectation; (2) `subscribe` alone prints its
  `subscribed to x; changes print as they are committed. Ctrl-C to stop.` banner to **stdout before**
  the connection failure is discovered, so a pipeline reading stdout sees an apparent success
  banner from a command that is about to fail.
- **API-069 — PASS.** `--url ftp://localhost:9090` → exit 1,
  `PRV-1030  cannot parse endpoint 'ftp://localhost:9090': 'ftp' is not a known scheme. ...`.
  `--url ""` → exit 1, `PRV-1030  cannot parse endpoint '': it is empty. ...` — confirming the blank
  reaches `Endpoint.parse` rather than being replaced by the default. `--url "grpc://"` → exit 1,
  `PRV-1030  cannot parse endpoint 'grpc://': it names no host. ...`. As the case itself predicts,
  none of the three messages names the `--url` flag the user typed.
- **API-071 — PASS.** Without `PRAVAHA_CLI_TRACE`: `a.out` empty, `a.err` one line
  (`PRV-1041  io exception`), 0 lines matching `^\s+at `. With `PRAVAHA_CLI_TRACE=1`: `b.out`
  identical (empty) to `a.out`; `b.err` starts with the same first line and then carries 27 stack
  frames. Confirmed `validate --sql --nonsense ...` under `PRAVAHA_CLI_TRACE=1` still has 0 stack
  frames — the gap the case names (trace only instruments `ServerCommand.fail`).
- **API-073 — PASS.** `ok  4 in, 3 out` (the ASCII-named row, `50 > 100` false, is the one dropped);
  `xxd` on the output shows `元` as `e5 85 83` (not `3f`, not a Latin-1 pair); file content exactly
  `Ashutosh,500\n元気,900\nÜnïcödé,150\n`. The astral-plane (🚀) round-trip was not separately
  re-verified this round.
- **API-075 — PASS for (a)–(d); (e) inconclusive.** `NO_COLOR=1`, `TERM=dumb`, `TERM` unset, and
  `TERM=xterm-256color` with output redirected all produced 0 escape bytes (`grep -c $'\x1b'`) and
  the plain `PRV-2002 ...` first line. Arm (e) (`script -qc '...' /dev/null` to force a pty) also
  produced 0 escape bytes in this sandbox — but that is most plausibly the environment (no real
  interactive terminal is available to `script` inside this container) rather than a product
  finding, so it is recorded **inconclusive**, not as a fifth passing or failing observation.

## REST §A. Path and method (API-076–086)

Against `H-SRV` (same server as CLI §G/§H, HTTP on `18080`), starting from one declared stream
(`txn`); `orders`, `orders2` and `uni` (unicode) accumulate on it through this section as the cases
register them, exactly as the case file expects streams to persist across a section.

- **API-076 — PASS.** `200`, `application/json`; body has exactly the six `NodeStatus` fields;
  `uptimeSeconds` a JSON number ≥ 3; `registeredQueries: 1` while zero queries were registered at
  that instant — confirmed the field reflects `catalog.size()` (stream count), not query count.
- **API-077 — PASS.** `200 text/html;charset=UTF-8`;
  `grep -Eic '<script|<link|src=|https?://'` is `0`; body contains
  `<title>Pravaha node pravaha-node-01</title>` and `class="ok"`.
- **API-078 — PASS, case correction (see the note at the top of this log).** `200`; array of length
  1; `txn`, version 1, `fieldCount 4`; fields in ordinal order with `nullable:false` throughout;
  types `INT64 NOT NULL`/`VARCHAR NOT NULL`/`INT64 NOT NULL`/`VARCHAR NOT NULL` (`VARCHAR`, not
  `STRING` — `API-F1`).
- **API-079 — PASS.** `200`; object byte-equal to API-078's element `[0]`.
- **API-080 — PASS.** `201 Created`; the second POST (a differently-named stream, since `orders`
  already existed by the time this was re-run) also `201`. `jq '.paths."/api/v1/streams".post.
  responses' api/openapi.lock.json` is exactly `["200"]` — the lock/reality mismatch the case names,
  confirmed both ways in one run.
- **API-081 — PASS, case correction.** `200`; `valid:true`; `diagnostics: []`; `outputFields` length
  2, `user_id`→`VARCHAR NOT NULL` ordinal 0, `amount`→`INT64 NOT NULL` ordinal 1; `elapsedMicros` a
  number.
- **API-082 — PASS.** Invalid query: `200`, `valid:false`, `diagnostics[0].code = PRV-2002`,
  `severity: "error"`, `helpUrl` correct, message ends `Known streams: [txn, orders, orders2]`
  (message text is `Column 'nope' not found in any table...`, a column-not-found variant of
  PRV-2002, not the table-not-found variant — both carry the same code and the same enumerating
  tail). Unbounded-`GROUP BY`: `PRV-2050`, also inside a `200`.
- **API-083 — PASS for five of six; one FAIL.** No `level` and `?level=physical`: `200`,
  `level:"physical"`, plan starts `Project[user_id, amount]\n  Filter(amount...`, `outputFields`
  length 2. `?level=logical`: `200`, `level:"logical"`, plan is `LogicalProject(...)`,
  **`outputFields: []`** — confirmed empty exactly as the case pins. `?level=codegen`: `200`,
  `level:"codegen"`, `outputFields` length 2, plan begins `-- no generated form: PRV-3101 ...` (the
  documented fallback form — this query also projects the `STRING` column `user_id`, so it takes the
  same interpreted-fallback path as CLI's API-037/API-F2). `?level=PHYSICAL`: `400`, body
  `{"code":"PRV-0400","helpUrl":"","message":"level must be 'logical' or 'physical', got
  'PHYSICAL'", "path":..., "timestamp":...}` — exact match, including the **empty `helpUrl`** the
  case calls out (feeds API-121/122's finding). **`?level=` (empty string) — FAIL**: case expects
  `400` identically to `PHYSICAL`; actual is `200`, `level:"physical"` — an empty query parameter is
  treated as absent rather than as an invalid value. See `FINDINGS.md` `API-F8`.
- **API-084 — PASS.** All 23 wrong-method requests (4+4+3+4+4+4) return `405` with an `Allow` header
  naming exactly the implemented methods for that path (`POST` only for the two query endpoints;
  `POST, GET` for `/api/v1/streams`; `GET` for the two GET-only paths).
- **API-085 — PASS.** HEAD mirrors GET's `200` on the four GET paths and is `405` on the two
  POST-only paths; OPTIONS is `200` on all six with a correct `Allow` header. No `500` anywhere.
- **API-086 — PASS.** `/api/v1/streams/` → `404` (this configuration does not match the trailing
  slash); every other near-miss path (`/api/v1/Streams`, `/API/v1/streams`, `/api/v2/streams`,
  `/api/v1/queries`, `/api/v1/queries/validate/`, `/apiv1/status`) → `404`.

## REST §B. Request bodies (API-087–098)

- **API-087 — PASS.** All three malformed-JSON bodies (`{"sql": `, `not json`, `[1,2,3]`) → `400`;
  body key set is `["error","path","status","timestamp"]` — Spring's shape, not `ApiError`.
- **API-088 — FAIL, and a real defect underneath.** Case expects `400` (`ApiError`, required) or
  `500` (a defect worth recording) for `{}`/`{"sql":null}`. Actual: **`validate` returns `200`** for
  both (not 400/500), with body
  `{"valid":false,"diagnostics":[{"code":"PRV-2010","message":"PRV-2010  Cannot invoke
  \"String.length()\" because \"s\" is null", "helpUrl":"https://docs.pravaha.io/errors/PRV-2010", ...}]}`
  — a **raw Java `NullPointerException` message**, disguised as a designed `PRV-2010` diagnostic
  with a help URL that almost certainly resolves to nothing useful. `explain` with the same two
  bodies returns `400` with an `ApiError` carrying the **identical** raw NPE text as `message`. See
  `FINDINGS.md` `API-F9` (the significant finding of this REST batch).
- **API-089 — PASS.** `{"sql":42}` and `{"sql":true}` → `200`, `valid:false`, `PRV-2001` (coerced to
  the strings `"42"`/`"true"`, both fail as non-query expressions). `{"sql":[]}` and `{"sql":{}}` →
  `400` (Jackson deserialization failure), Spring's error shape. Exactly as the case predicts.
- **API-090 — PASS.** Extra fields (`tenant`, `level`, `limit`) in the validate body are ignored —
  `200`, `valid:true`, one output field. `explain` with `"level":"logical"` **in the body** and no
  query parameter still answers `"level":"physical"` — confirmed the body's `level` is inert.
- **API-091 — PASS.** Empty body (`--data-binary ''`) on all three POST endpoints → `400`.
- **API-092 — NOT RUN.** Needs `-Xmx512m` and multi-megabyte bodies up to 64 MB against the live
  node; not attempted this session given the shared-host resource pressure from concurrent QA
  agents (§ note above) and the time budget. The mechanism (Tomcat/Jackson limits, not a
  `pravaha`-specific ceiling) was not independently verified.
- **API-093 — PASS.** `text/plain`, `application/xml`, `application/x-www-form-urlencoded`, and a
  blanked `Content-Type` header all → `415`.
- **API-094 — PASS.** Exactly the 2×4 matrix the case specifies:
  `/api/v1/status` is `200` for `application/json`/`*/*`/no-header and `406` for `text/html`;
  `/status` is `200` for `text/html`/`*/*`/no-header and `406` for `application/json`.
- **API-095 — PASS.** `SELECT "顧客" FROM txn` → `200`, `valid:false`, message correctly quotes
  `顧客` in UTF-8. Registering stream `uni` with a `顧客` field → `201`; validating
  `SELECT 顧客 FROM uni` → `200`, `valid:true`, `outputFields[0].name == "顧客"`; `GET
  /api/v1/streams/uni` returns the same name. No mojibake anywhere.
- **API-096 — PASS, matches the predicted defect bucket.** `{}`, `{"name":"x"}` (schema null), and
  `{"name":"xEmpty","schema":""}` all → `500` (the first two an unguarded NPE, the last `PRV-5040`);
  `{"name":"","schema":"a:INT64"}` → `400` (better than the case's worried-about alternative — no
  stream named `""` was created). `GET /api/v1/streams` afterwards shows none of `x`/`""`/`xEmpty`
  — no bad stream was ever created despite the 500s.
- **API-097 — PASS.** `500`; body `ApiError` with `code:"PRV-5040"`, the exact message the case
  states, `path:"/api/v1/streams"`; `GET /api/v1/streams` unaffected.
- **API-098 — PASS for (a), (b), (c); FAIL for (d).** (a) JSON-escaped NUL between `SELECT`/`FROM`:
  `200`, `valid:false`, `PRV-2001` (a lexical error naming the NUL). (b) a **raw** unescaped NUL
  byte, sent as a hand-built HTTP request (curl cannot represent a literal NUL in an argument): confirmed
  `400`, Spring's plain error shape — matches. (c) escaped newlines/tab plus a trailing `--` SQL
  comment: `200`, `valid:true`, one output field. (d) a lone unpaired surrogate (`\ud800`) as the
  whole `sql` value: case expects `400` from Jackson; actual is **`200`**,
  `valid:false`, `PRV-2001` — Jackson accepts the malformed surrogate during JSON decoding (does not
  reject it), and the resulting string fails only once it reaches the SQL lexer. Not unsafe (still
  clean JSON, no 500), but contradicts the specific status the case names. See `FINDINGS.md`
  `API-F10`. No case returned `500`; every body parsed under `jq -e .`.

## REST §C. Missing names, duplicates and concurrency (API-099–104)

- **API-099 — PASS.** `400`; `ApiError` with `code:"PRV-2003"`,
  `message: "no stream named 'nope'. Registered: [txn, orders, orders2, uni]"`, correct `helpUrl`
  and `path`.
- **API-100 — PASS (five of six arms; one not independently testable).** `txn%20` → `400`,
  message names `'txn '` (trailing space preserved). The two traversal forms
  (`..%2F..%2Fetc%2Fpasswd`, `a%2Fb`) → `400`, never a 200, never a 500. The 4096-character name →
  `400`. `TXN` → `400` (case-sensitive catalog). The unicode-name arm was **not independently
  confirmed**: this session never registered a stream literally *named* `顧客` (API-095 registers a
  stream named `uni` with a `顧客` *field*, which is what the case file's own API-095 describes) —
  so `/api/v1/streams/%E9%A1%A7%E5%AE%A2` correctly answers `400` here, against a precondition the
  case assumes but this session never created.
- **API-101 — PASS.** Both duplicate-registration POSTs for `orders` return `400` with
  `code:"PRV-2002"` and the exact message the case states; `GET /api/v1/streams/orders` afterwards
  still reports `fieldCount: 3` (the original schema, untouched).
- **API-102 — PASS.** 20 parallel identical POSTs of a fresh `concurrent_stream` name: exactly one
  `201` and nineteen `400`s (`1 + 19 = 20`); `GET /api/v1/streams` shows it exactly once with
  `fieldCount: 2`. No timeout, no `500`.
- **API-103 — PASS.** 50 parallel `POST /api/v1/queries/validate`, 50 parallel `GET
  /api/v1/streams`, 50 parallel `GET /api/v1/status`: exactly one distinct body per set (after
  normalising `elapsedMicros`/`uptimeSeconds`), 150/150 responses accounted for.
- **API-104 — PASS, abbreviated.** Run at 30 racing iterations rather than the case's 200 (time
  budget), registering `race_1`..`race_30` concurrently with a validate against each: every
  validate was either `valid:true` with exactly the two expected output fields, or `valid:false`
  with a `Known streams:`-bearing message — never a third outcome. All 30 `race_N` streams appear
  exactly once in the final listing.

## REST §D. Authentication (API-105–114)

Restarted as `H-SRVA`: `--pravaha.security.authentication=token --pravaha.security.policy=
authenticated --pravaha.security.audit=memory --pravaha.security.allow-anonymous=false`, two
tokens (`ann`/`acme`, `bob`/`globex`), plaintext Flight (unused in this REST-only batch). HTTP on
`18080` again.

- **API-105 — PASS.** All seven documented paths return `401` with the exact `PRV-7001` body the
  case specifies (`message`, `helpUrl`, `path` matching the request URI, `timestamp`); the
  unauthenticated `POST /api/v1/streams` created nothing (`GET /api/v1/streams` with a valid token
  afterwards shows only `["txn"]`).
- **API-106 — PASS.** `Bearer <token>`, `bearer <token>` (lower-case scheme), `BEARER <token>`, and
  a bare token with no scheme at all -> all `200` (confirms the case-insensitive-scheme and
  no-scheme leniency). `Bearer wrong-token`, `Bearer ` (empty), and `Basic ...` -> all `401`. Two
  headers with different values (wrong-token first, valid second) -> `401` -- the first header (or
  the malformed combination) wins, not the valid one; recorded as the case asks.
- **API-107 — PASS with a path correction, and a real finding.** `/api/v1/openapi.json` -> `200`,
  and `grep -ic 'txn|by_user|token'` on the body is `0` -- no stream name, no query text, no
  credential leaks into the API description. `/api/docs` -> `302` (a redirect, not a `200` -- but
  still open, no credential required) to `/api/swagger-ui/index.html`. That redirect target,
  however, is **`401`** -- the actual Swagger UI page requires a credential. See `FINDINGS.md`
  `API-F11`.
- **API-108 — PASS.** `/actuator/health`, its two probe sub-paths, and `/actuator/info` are `200`
  with no credential; `/actuator/metrics`, its sub-path, `/actuator/prometheus` and `/actuator`
  itself are `401` without a credential and `200` with one. Unauthenticated health body is
  `{"status":"UP","groups":["liveness","readiness"]}` -- no component details, consistent with
  `show-details: when-authorized` and an anonymous caller not being "authorized".
- **API-109 — PASS — no bypass found.** All eight near-miss probes are `404` (unmatched route,
  Spring's shape) or `401` (the filter ran and refused); the two `../`-traversal forms both resolve
  (after URL normalisation) to a real, protected route and correctly answer `401`, not `200` with
  content. No authentication bypass in this sample.
- **API-110 — PASS.** `ann` and `bob`'s `GET /api/v1/streams` bodies are byte-identical; `bob`
  (tenant `globex`) successfully registers `bobs_stream` (`201`) and it is immediately visible to
  `ann`'s listing -- confirmed no per-tenant restriction anywhere on this surface.
- **API-111, 112, 113, 114 — NOT RUN.** Time budget. API-111 (`authentication: none` filter
  present-but-disabled) and API-112 (the refuse-to-start-open guard) need the `dev`/no-profile
  server restarts already exercised in CLI §G's `H-SRV` and in `DEPLOY`/`CFG`'s areas respectively;
  API-113 (query-string/cookie credentials rejected) and API-114 (what an unauthenticated caller can
  still learn) are straightforward extensions of API-105/107/108 above but were not separately
  scripted this session.

## REST §E. Disclosure (API-115–120)

- **API-115 — PASS.** `/actuator/env` and `/actuator/env/pravaha.security.tokens.*` are `401`
  unauthenticated (the filter runs before the exposure-list check fires) and `404` once
  authenticated (not in `management.endpoints.web.exposure.include`).
- **API-116 — PASS.** As `bob` (unrelated to `txn`): `GET /api/v1/streams/nope` message ends
  `Registered: [txn, bobs_stream]`; an unknown-column validate ends
  `Known streams: [txn, bobs_stream]` -- both enumerate the full inventory to a verified-but-unrelated
  caller, exactly as the case describes (and, per API-110, the plain listing discloses the same
  thing anyway on this authenticated-only-no-authorization surface).
- **API-117 — PASS.** `/actuator/beans` and `/actuator/configprops`: `401` unauthenticated, `404`
  authenticated.
- **API-118 — PASS.** `/actuator/heapdump` and `/actuator/threaddump`: `401` unauthenticated (234/236
  byte bodies), `404` authenticated (106/108 byte bodies) -- nowhere near the multi-megabyte
  falsifier.
- **API-119 — PASS.** `GET`/`POST /actuator/loggers`, `GET /actuator/mappings`,
  `POST /actuator/shutdown`: `401` unauthenticated, `404` authenticated (verified all three
  authenticated as a follow-up); the node answered `{"status":"UP",...}` after the shutdown attempt.
- **API-120 — PASS.** Of the 24 stock actuator ids, exactly four (`health`, `info`, `metrics`,
  `prometheus`) return `200`; the other twenty return `404`. `GET /actuator` (authenticated) lists
  only those four (plus HAL sub-links for the parameterised ones) under `_links`.

## REST §F. The error body (API-121–125)

- **API-121 — PASS.** All four provoked failures (`PRV-2003` unknown stream, a duplicate-name
  `PRV-2002`, `PRV-5040` malformed spec, `PRV-0400` from `?level=weird`) have exactly the five-key
  set `["code","helpUrl","message","path","timestamp"]`; the `IllegalArgumentException` arm's
  `helpUrl` is confirmed the **empty string** `""`, exactly as the case states.
- **API-122 — PASS for the HTTP-reachable rows (PLANNING->400, PLUGIN->500); the non-HTTP-reachable
  rows were not independently unit-tested this session.** Consistent with API-097/099/101.
- **API-123 — NOT RUN.** Needs a direct unit call to `ApiExceptionHandler.statusFor` with an 8xxx/9xxx
  code (`RegistryErrors.NO_SUCH_QUERY`, `ClusterErrors.INSUFFICIENT_GUARANTEE`); not reachable over
  HTTP and no such unit test was written this session -- time budget. This is the case that would
  catch the first REST endpoint reaching an 8xxx/9xxx code and getting a raw 500 instead of an
  `ApiError`.
- **API-124 — PASS.** The four framework failures (404, 405, 415, malformed-JSON 400) share the
  key set `["error","path","status","timestamp"]`, distinct from `ApiError`'s five.
- **API-125 — PASS.** The 401 body parses under `jq -e .`; `timestamp` matches the ISO-8601 pattern
  the case specifies. A path containing a quote/backslash/newline was not separately constructed
  this session (time budget) -- the general parseability claim is confirmed on every body collected
  in §D/§F, not on that specific adversarial path.
