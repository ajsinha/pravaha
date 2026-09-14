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
