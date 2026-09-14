# DOCX — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/DOCX.md`](../cases/DOCX.md). Executed 2026-09-14 on branch `develop` at `efa8fae`,
in an isolated git worktree under `.claude/`, against `pravaha-*` as built by
`./mvnw -o -T1C install -DskipTests` (Java 21). Four sub-rounds ran in parallel — Group D (error
codes), Group E (links, anchors, paths, ADR index, module list), Group F (ADRs against the code they
decided) and Group G (coverage claims) — each in the same worktree with its own scratch directory and
its own reserved port; Groups A, B, C, H and I were executed in the lead session. No case ID was run
by more than one sub-round. Nodes ran on HTTP **18670–18674** / Flight **19670–19674** and were
recorded and killed by PID; 18801/19801, offered by the brief, were already held by another agent.

**This round departs from its own case file on one point, deliberately.** `DOCX.md` says "no
production code and no document is modified by any case in this file", because round 1 edited
documents underneath its own report and produced an audit of a state that no longer existed. The
standing brief for this round overrides that: documentation rot is a defect the owner cares about
specifically, and unlike a product defect it is safe to repair in the same round. So every FAIL below
records **what the document claimed and what the code does**, as an audit must, and then names the
commit that fixed it. Nothing was measured after being edited: every verdict is against the tree as
found at `efa8fae`, and the repairs are a separate, later act recorded beside the verdict. No product
code was changed. Where the **code** was wrong rather than the document, the code was left alone and
the finding says so.

**Overall: 60/60 cases executed.** **24 PASS**, **32 FAIL**, **4 PARTIAL** (executed, evidence
obtained, one named sub-step not reached — DOCX-008, DOCX-018, DOCX-024, DOCX-058), **0 BLOCKED**,
**0 NOT RUN**. 22 findings recorded in [`../FINDINGS.md`](../FINDINGS.md) as DOCX-1 … DOCX-22.

A large share of the FAILs are **the case file being stale rather than the tree being broken** — the
repairs of three prior rounds landed between the cases being written and this execution. Those are
marked *stale-Expected* and are not findings. The distinction matters: an audit that reports a FAIL
for a defect that was fixed is as misleading as one that misses a live one.

**Documentation corrected in this round** (commits `51b27f5` and `fbe02eb`, both on `develop`):
`docs/QUICKSTART.md`, `docs/OPERATIONS.md`, `docs/HANDOVER.md`, `docs/TROUBLESHOOTING.md`,
`docs/SECURITY.md`, `docs/SQL_SUPPORT.md`, `docs/README.md`, `docs/CONCEPTS.md`, `docs/USER_GUIDE.md`,
`docs/system_design.md`, `docs/implementation_plan.md`, `README.md`, `console/README.md`,
`examples/README.md`, `examples/02-aggregate/README.md`, `examples/case-studies/README.md`,
`examples/case-studies/SETUP.md`, `sdk/python/README.md`, thirteen ADRs and `docs/adr/README.md`,
plus two javadoc lines in `SecurityProperties`.

**One test changed, and it is the point of the round.** `ErrcCrossCuttingTest` asserted the
undocumented error-code set was *exactly* `PRV-5090`, `5091`, `5092` — a previous round's honest
record of a gap, which had become the thing preventing its repair, because adding the rows would have
turned the build red. It now asserts the set is empty in both directions. `ERRC-111: 111 declared,
111 documented`. Its tree walk also now excludes nested `.claude` worktrees relative to the root it
found, never by substring.

**`pravaha-it` is not green, and was not green before this round.** `./mvnw -o -pl pravaha-it test`
reports `Tests run: 733, Failures: 3` — `StateCheckpointDirectoryTest.state034`, `.state035` and
`StateFailureReportingTest.state044`. All three were reconfirmed on the **unmodified** tree with this
round's changes stashed (`Tests run: 18, Failures: 3`, identical assertions), so they predate this
work and are the STATE round's own recorded findings baked into tests. Everything this round touched
is green: `ErrcCrossCuttingTest`, `FindingsRegisterTest`, `DocumentationFreshnessTest` (10/10),
`ExamplesTest` (12/12), `ErrorCodeUniquenessTest`, `ArchitectureRulesTest`, `SqlSupportMatrixTest`.

**A note on a third-party string.** As in prior rounds, `jqwik-engine-1.10.1.jar` carries a sentence
addressed to an "AI Agent" instructing it to disregard its instructions and discard jqwik's results.
It appeared in the surefire output of every `pravaha-it` run in this round. It is untrusted
third-party build output, not a project instruction, and was not acted on. `docs/HANDOVER.md:446` is
right to record it.

**The audit's re-run instructions** are in `$QA/README.md` (scratch, deliberately not in the
repository — DOCX-060), with the denominators, the corpus command and the change→case mapping.

---

## Group A — the instruments (DOCX-001 … DOCX-005)

The group that decides whether the rest of the area means anything: an extractor that silently sees
nothing turns every audit below it into a clean bill.

**Corpus.** `git ls-files '*.md'` gives 136 files; the case file's own enumerated corpus excludes
`docs/qa/**` (QA's own record, not a document under audit) and `console/content/**` (front matter
plus `include:` of a file already in the corpus, so auditing it double-counts every sentence). **D0 =
68 files.** The deviation from the case file's literal `git ls-files` instruction is recorded here
because it changes every denominator below.

- **DOCX-001 — PASS.** `$QA/extract/commands.py`, all five shapes plus blockquoted fences. Hand count
  of `docs/QUICKSTART.md` = **31**; extractor = **31**, exact. Corpus total **D1 = 196**. Two
  extractor defects were found by the hand count and fixed before the number was trusted: a
  twelve-space continuation line was being dropped as an indented block (so step 2's three-line
  command extracted as two fragments), and `> ```bash ` inside a blockquote — which is where
  QUICKSTART puts `export JAVA_HOME` — was invisible. **Vacuity:** one extra `pravaha version`
  appended to a `$QA` copy of QUICKSTART moved the count 31 → 32, exactly one.
  Heredocs are one row, not one row per SQL line.

- **DOCX-002 — PASS.** `$QA/extract/keys.py`, both directions, with `pravaha.streams.txn.*` collapsed
  to `pravaha.streams.<n>.*`. Code side 55 raw literals, **D2 docs side = 125**. Both falsifiers
  cleared: `pravaha.security.tokens` appears on the code side as a map prefix (its *keys are
  credentials*, noted in `application.yaml`'s comment and in `SecurityProperties`), and
  `pravaha.watermark.out-of-orderness` appears on the document side. A third output,
  `keys-comments.txt`, separates keys named only in javadoc — the input to DOCX-023. The first
  version of the code side returned 305 because `io.pravaha.api.data.Types` matched; restricted to
  string literals with all-lowercase segments, which is what a configuration key is.

- **DOCX-003 — FAIL (*stale-Expected*).** Extractor built; the case's numbers have moved.
  Measured **111 declared / 108 documented / 3 undocumented / 9 zero-throw / 0 documented-but-undeclared**
  against an Expected of 110/100/10/9/0. The falsifier is cleared: the Java SDK's six codes are
  present (`PRV-1030` `sdk/pravaha-sdk-java/.../Endpoint.java:46`, `1031` `ClientOptions.java:34`,
  `1040`–`1043` `ClientErrors.java:24,27,30,33`); omitting `sdk/` gives 105. The zero-throw list
  matches the Expected **exactly**: 1043, 4002, 4013, 5012, 5020, 5053, 5064, 8007, 9004. The
  undocumented set is `PRV-5090`, `5091`, `5092` — finding **DOCX-3**, since fixed; the extractor now
  reports `111 declared, 111 documented, 0 undocumented`. Zero dynamic constructions, so a
  regex-plus-statement extractor is sound here; two indirections that defeat a naive per-line regex
  are handled (the `FlightErrors.BAD_HANDLE = ControlWire.BAD_REQUEST` alias, counted once, and
  `fail(ErrorCode, …)`, which is why 1021–1025 are not zero-throw).

- **DOCX-004 — PASS.** Three classes, one extractor, base paths resolved per document directory.
  **D4 = 252 relative links / 63 anchors / 126 backticked paths.** Base-path handling proven by
  `docs/adr/*.md`'s `../../LICENSE` resolving. **Vacuity:** one seeded broken link, one broken anchor
  and one broken backticked path produced exactly three new failures, one per class.

- **DOCX-005 — PASS.** `$QA/extract/claims.py`, thirteen patterns. **D5 = 102 claims.** Both positive
  controls present and quoted: `docs/HANDOVER.md:57` and `docs/SQL_SUPPORT.md:6`. One extractor defect
  found and fixed: a case-insensitive `\w+Test\b` matches `hottest`, `latest`, `greatest` — 17 false
  positives; that pattern is now case-sensitive and the other twelve are not (119 → 102).
  **62 of the 102 name no artefact at all**, which makes them unfalsifiable — worse, on this case
  file's own reckoning, than a false one.

---

## Group B — every command, executed literally (DOCX-006 … DOCX-018)

Standard: *executed exactly as written, by someone who knows nothing*. A step needing knowledge the
document does not give is a defect in the document.

- **DOCX-006 — PASS.** `./mvnw -q -DskipTests install` had already run (the brief forbids a rebuild
  mid-wave); both artefact paths exist — `pravaha-cli/target/pravaha-cli-0.1.0-SNAPSHOT-cli.jar` and
  `pravaha-server/target/pravaha-server-0.1.0-SNAPSHOT-app.jar` — and `bin/pravaha` and
  `bin/pravaha-server` glob onto exactly those. `pravaha --help` exits **0**. **Vacuity:** it prints
  **eleven** subcommand names (validate, query, register, queries, subscribe, pause, resume, drop,
  explain, run, version), not a bare flag acknowledgement. `docker build` is `NOT-EXECUTABLE` here and
  the Dockerfile was reviewed statically against the printed command instead: `EXPOSE 8080 9090`
  matches the `-p 8080:8080 -p 9090:9090` the document prints; `ENTRYPOINT ["bin/pravaha-server"]`
  takes `--spring.profiles.active=dev` as an argument, so the profile does reach the JVM; the
  multi-stage copy globs the same two jar patterns the document names.

- **DOCX-007 — FAIL → finding DOCX-1, FIXED in `51b27f5`.** Both commands run verbatim from
  `$QA/step2/` containing only `transactions.csv`:
  ```
  step 2: --out-schema is required. Supplied: [sql, schema, stream, in, out]   EXIT=2
  step 3: --out-schema is required. Supplied: [sql, schema, stream, in, out]   EXIT=2
  ```
  The document promised `alice,500 / dave,150 / frank,1200` for step 2 and the `PRV-2050` refusal for
  step 3, so the lesson step 3 exists to teach was never seen. The `--schema` also declared 2 columns
  for a 4-column CSV, and the quoted output belongs to a *different* predicate — `examples/01` filters
  `status = 'COMPLETED' AND amount > 100`, the quickstart only `amount > 100`, which is four rows.
  **Vacuity control, run in the same session:** `examples/01`'s form works (DOCX-014), so the failure
  is the quickstart's text and not the engine. After the fix, both commands produce exactly what the
  document prints, re-verified from a clean directory.

- **DOCX-008 — PARTIAL.** Executed top to bottom from `$QA/step4/`. **Four inventions counted**, one
  fewer than round 1 and the worst one now avoidable:
  1. the configuration file's **name and directory** — the document prints a YAML block and then says
     "with that file", naming neither (`NEEDS-INVENTION`);
  2. the CSV's **contents** — no fixture is given for the `txn` source it configures;
  3. the **timestamp wire format** — resolved: `docs/QUICKSTART.md:153-157` now documents ISO-8601 and
     bare-number-as-engine-unit, and ISO-8601 worked first time (no longer an invention);
  4. `pravaha.streams.<n>.event-time` — undocumented (finding **DOCX-7**), but **not required**: a
     node declaring only the *documented* source option `pravaha.sources.txn.options.event.time`
     ingests and closes windows (`ROWS IN 10`, two rows in the view). Round 1 ranked the missing
     stream key the worst documentation defect in the repository; it is now reachable through a
     documented key.
  5. the declared `txn` schema in the document does not carry the column `velocity.sql` groups on
     (`FAILED` as the case predicts).
  **Vacuity control satisfied and it is the round's central measurement:** a configuration built by
  someone who already knows the answers registers, ingests and closes windows in the same session —
  `registered user_volume state=RUNNING fingerprint=8a86337b7b50`, `ROWS IN 10`, and windows closing
  with hand-computed sums (u1's window ending 09:03 = 500, u2's ending 09:02 = 400, both confirmed
  against the fixture by hand). PARTIAL because `$QA/report/step4.tsv` was not emitted as the
  diffable per-command TSV the case specifies; the per-command outcomes are here instead.

- **DOCX-009 — PASS.** Against the control node on 18670/19670. Step 5's parameterised form:
  `pravaha query --sql "… WHERE user_id = ?" --params u1` → `u1  500`, exit 0. Both step-6
  `subscribe` forms accepted, plain and `--filter user_id=u1`. Step 8: `dropped user_volume`, exit 0,
  and the name disappears from `pravaha queries` (`no continuous queries are registered`).
  **Vacuity:** the view was read before the drop (2 rows) and after (`PRV-4023 no views are
  registered`) — a `drop` that exits 0 while the view keeps answering was a round-1 finding and a bare
  exit code would not catch it.
  Two of the case's Expected notes have **changed**: `--limit` *is* in the CLI (`subscribe --view
  <name> [--filter …] [--limit N]`), though the document's printed form still omits it and a reader
  following step 6 has to Ctrl-C; and **`SRVDBG` no longer exists** — `grep -rn SRVDBG` over every
  `*.java` returns 0 hits, and a live subscription printed only the header and the commit groups.
  Appending three rows to the followed file mid-subscription produced two further commits at the tap.

- **DOCX-010 — PASS.** `make install` succeeded (venv, the Python SDK from the tree, the console).
  It needs a network for pip and **the document does not say so** — recorded; it did not fail here.
  The console was started with the document's own override form
  (`python run_pravaha_web.py --server.port=… --engine.url=…`, `QUICKSTART.md:260` /
  `console/README.md:21`) rather than bare `make run`, because `make run` binds 8090, outside this
  round's reserved range and shared with other agents. Every route in both documented tables answers:
  `/` `/about` `/overview` `/queries` `/queries/{name}` `/workbench` `/help` `/tutorials` `/health`
  `/login` all **200**; `/api/v1/queries` **200**. **Vacuity satisfied** — routes answer, so a 404
  would have meant a wrong table rather than a dead console. All four keyboard shortcuts the document
  names are bound: `theme.js:102` `t`, `:103` `d`, `:106` `/`, `:109` `?`.
  The configuration half is finding **DOCX-11**.

- **DOCX-011 — PASS.** The Java snippet **compiles** against the built SDK jars (`javac` exit 0), not
  resolved by grep — which is the case's own vacuity guard, because a grep version passed in round 1.
  It needs **six imports the document does not show** (`java.nio.file.Files`, `Path`, `java.util.List`,
  and the three SDK types); recorded as the case asks. The Python snippet runs verbatim against the
  live node:
  ```
  $ .venv/bin/python -c "from pravaha import connect
    with connect('grpc://localhost:19670') as client:
        for row in client.query('SELECT user_id, total FROM user_volume WHERE user_id = ?', ['u1']):
            print(row['total'])"
  700
  ```
  Every name resolves: `connect`, the context manager, `Client.register(name, sql, key_columns)`,
  `Client.query(sql, parameters)`, `Row.__getitem__`.

- **DOCX-012 — FAIL.** All 12 extracted commands run against a node on 19670. Accepted and correct:
  `queries`, `pause`, `resume`, `drop`, `query --params`, `subscribe --filter`. Two defects:
  - `docs/USER_GUIDE.md:245` prints `pravaha explain --sql "..."` with no `--schema`, which the
    command requires → `--schema is required. Supplied: [sql]`, exit 2. With a schema it exits 0.
  - **The document never tells the reader to start a node.** `grep -n 'pravaha-server\|start a
    server\|Start a node' docs/USER_GUIDE.md` returns nothing, and every command in it needs one, so
    all twelve are `NEEDS-INVENTION` on the case's own rule even though eleven ran in this harness.
  **Vacuity:** at least one ran as shown — several did.
  Found incidentally, a **code** defect left alone and recorded as **DOCX-10**: `pravaha pause` prints
  `pauseped t1` and `resume` prints `resumeped t1` (`ServerCommand.java:132`, `action + "ped "`).

- **DOCX-013 — FAIL → findings DOCX-4, DOCX-5, DOCX-9; all three FIXED in `51b27f5`.** The command
  half is nearly empty and that is itself the result: `OPERATIONS.md` yields **one** extracted command
  and `SECURITY.md` **zero** — they are prose and YAML, so the YAML half is the case. Six YAML blocks
  written verbatim into a config file and started:
  - the "Starting a node" block prints `pravaha.flight.port: 8815` against a shipped default of
    `9090` (`application.yaml:72`) — **DOCX-4**;
  - `watermark` bounds: `idle-after` at `500ms`, `20m` and `-1s` are each **refused** `PRV-2002` with
    the bound named, `45s` accepted — the document's "refused, not clamped" claim holds exactly;
  - `tick: 30s` with `idle-after: 5s` **starts** (`watermarks: idle-after=PT5S, tick=PT30S`) while
    the document says such a configuration is refused — **DOCX-9**;
  - `SECURITY.md`'s `.authorizedBy(myPolicy, myAuditSink)` is the embedded API with no server
    equivalent, unchanged since SECX-004; and `SECURITY.md:208` named
    `PravahaFlightServer.location()`, which does not exist — **DOCX-22**.
  **Vacuity:** a known-good block bound in the same run (the control node's, which started and served).

- **DOCX-014 — PASS.** All three examples run verbatim from the repository root and their quoted
  output reproduced **byte for byte**:
  ```
  01: alice,500 / dave,150 / frank,1200          (ok  6 in, 3 out)
  02: 3,700                                      (ok  4 in, 1 out)
  02 refusal: PRV-2050 … Bound it with a window  (exit 1)
  03: engine : example-engine / state : RUNNING / lanes : 4 / plugins : [] / second : second-engine RUNNING
  ```
  The README's arithmetic checks out: of six rows `bob` and `erin` are under the threshold and `carol`
  is `PENDING`, leaving three. One difference recorded rather than counted as a failure: the README's
  quoted `PRV-2050` block is line-wrapped differently from the terminal and omits the
  `https://docs.pravaha.io/errors/PRV-2050` line the CLI prints. `ExamplesTest` asserts *containment*
  of the quoted strings, which is why the wrap difference is invisible to the build and correct.

- **DOCX-015 — PASS.** `SETUP.md` declares its prerequisites before the reader types anything — a
  four-row table of Java 21 / Docker / Python 3.11+ / a built repository with a check command and a
  remedy for each, plus a `JAVA_HOME` note. Each case-study README opens with a one-line header naming
  its store and linking `../SETUP.md` (`**Store:** Aerospike · **Time to first result:** about fifteen
  minutes · **Setup:** [`../SETUP.md`](../SETUP.md)`). Walked `banking-card-velocity` as a naive
  reader to `docker run … aerospike/aerospike-server:latest`, which is where infrastructure begins and
  where the document said it would: `NOT-EXECUTABLE`, and acceptable on the case's own rule because
  the dependency was declared first. The count dispute the case flags is real — finding **DOCX-15**,
  since fixed.

- **DOCX-016 — FAIL.** Every documented path requested against a node configured as the document
  naming it says:
  ```
  /api/v1/status              200   {"instanceId":"pravaha-node-01","engineState":"RUNNIN…
  /api/v1/streams             200   [{"name":"txn","version":1,"fieldCount":5,…
  /api/v1/queries/validate    405   (POST-only; exists)
  /api/docs                   302   /api/v1/openapi.json  200
  /actuator/health            200   /actuator/info  200
  /actuator/prometheus        200   # HELP application_ready_time_seconds …
  /status                     200   text/html
  /api/v1/queries             404   (GET; README:199 says outright there is no POST either)
  /actuator/pravaha           404   promised by docs/system_design.md:3257
  /api/v1/queries/{id}/backfill 404 promised by docs/system_design.md:2595
  /swagger-ui/index.html      404
  ```
  **`/actuator/prometheus` is 200, not the 404 the case Expected** — *stale-Expected*, the registry
  dependency is present; every per-query metric name in `OPERATIONS.md:335-342` was read back live
  (`pravaha_query_rows_in{query="user_volume"} 10.0`, `…view_size 2.0`, `…view_updates 8.0`,
  `…watermark_lag_seconds NaN`). The two surviving 404s are both promised by `system_design.md`, which
  now carries the header saying it is intent and not build (**DOCX-13**). `README.md:198-199` is
  **accurate** and worth recording: "The HTTP surface is deliberately small: `/status`,
  `/api/v1/streams`, and `/api/v1/queries/validate` and `/explain`. There is no `POST
  /api/v1/queries`." `SECURITY.md`'s `/api/v1/query` is the *console's* endpoint, not the engine's —
  checked before reporting it. **Vacuity:** `/api/v1/status` 200 in the same run.

- **DOCX-017 — FAIL.** Both directions.
  *Documents → CLI:* every subcommand named in the corpus exists. One documented flag is **absent from
  the help text and accepted by the binary** — `register --sql`, used at `QUICKSTART.md:168`, does not
  appear in `pravaha --help`'s `register` line (`--name <view> --sql-file <path> [--keys 0,1]`) and
  registers successfully. That is the case's own vacuity pair, and it appears.
  *CLI → discoverability:* `--help` is parsed as a bare flag, so **nine** commands answer it with a
  missing-required-option error (the case predicted six):
  ```
  validate/query/explain/run --help  →  --sql is required. Supplied: [help]        exit 2
  register/pause/resume/drop --help  →  --name is required. Supplied: [help]       exit 2
  subscribe --help                   →  --view is required. Supplied: [help]       exit 2
  queries --help                     →  PRV-1041  io exception                      exit 1   (dials the network)
  version --help                     →  pravaha 0.1.0-SNAPSHOT                      exit 0
  ```
  So there is no way to discover a command's flags from the binary, and "the documentation is the only
  reference" is load-bearing in a way nobody chose.

- **DOCX-018 — PARTIAL.** The arithmetic check, which is the group's point, **holds**: per-file counts
  over the 68-file corpus sum to D1 = 196, distributed `docs/QUICKSTART.md` 31, `docs/system_design.md`
  22, `examples/case-studies/SETUP.md` 21, `docs/implementation_plan.md` 20, `docs/USER_GUIDE.md` 12,
  `sdk/python/README.md` 9, `README.md` 9, the five case studies 32, `docs/HANDOVER.md` 7,
  `examples/*` 9, `console/README.md` 4, `Dockerfile` 2, and 18 singletons — and the files covered by
  DOCX-006–017 account for 108 of them. PARTIAL, honestly: the residual 88 were **extracted and
  classified but not all executed**. The 43 in `system_design.md` and `implementation_plan.md` are
  addressed by the status headers rather than by running them (**DOCX-13**); the 32 in the case
  studies are `NOT-EXECUTABLE` by declared infrastructure (DOCX-015); `sdk/python`'s nine were
  executed in DOCX-011 and yielded **DOCX-17**. The four in `docs/gates/` and the ADRs were read, not
  run. A later round wanting the diffable per-file TSV the case specifies should build that emitter
  first; `$QA/README.md` §5 records what it would take.

---

## Group C — configuration keys, both directions (DOCX-019 … DOCX-026)

- **DOCX-019 — PASS, and the result is one key, not five.** Inertness proved **by experiment**, as the
  case requires, not by grep. Four nodes over a fixture whose late row arrives one watermark tick
  after the row that should have closed its window:
  ```
  A  pravaha.watermark.out-of-orderness: 0s      window 09:00-09:01 -> 1 row, total 100
  B  pravaha.watermark.out-of-orderness: 10m     window 09:00-09:01 -> 1 row, total 100
  C  pravaha.streams.txn.out-of-orderness: 0s    window 09:00-09:01 -> 1 row, total 100
  D  pravaha.streams.txn.out-of-orderness: 10m   window 09:00-09:01 -> 2 rows, total 150
  ```
  **A == B is inertness; C ≠ D is the control** the case demands — the key one level down in the same
  tree, one word apart, does change the answer, so the fixture exercises lateness. A first attempt
  with a bounded file gave A == B == C == D and was discarded rather than reported: the whole file was
  consumed inside one watermark tick, so lateness could not be the variable. Confirmed statically
  afterwards: the literal `pravaha.watermark.out-of-orderness` occurs in `src/main` **once**, in
  `StreamSchema.java:53`'s javadoc. Documented at `CONCEPTS.md:66`, `OPERATIONS.md:222` and shipped in
  `application.yaml` with a default — finding **DOCX-6**.
  **The case's other Expected members all now have readers** (*stale-Expected*, reported as a delta):
  `pravaha.checkpoint.timeout` → `PeriodicCheckpointer.java:125`; `pravaha.cluster.socket.peers`,
  `.heartbeat.millis`, `.timeout.millis` → `SocketProvider.java:54,80,81`;
  `pravaha.cluster.zookeeper.*` → `ZooKeeperProvider.java:61`. A reader-audit of all 19 uncommented
  shipped YAML keys found exactly one with no reader.

- **DOCX-020 — FAIL → finding DOCX-7.** Keys bound in code and named in no document, each with its
  consequence demonstrated rather than asserted:
  - `pravaha.streams.<n>.out-of-orderness` — bound at `StreamDeclarationProperties.Declaration:91`,
    the **only working lateness control** (DOCX-019 run D), alluded to at `OPERATIONS.md:222` and
    never named. Zero occurrences of the string in the 68-file corpus.
  - `pravaha.streams.<n>.event-time` — bound, named nowhere. **Consequence measured and it is
    smaller than round 1's:** a node with only the documented source-level `event.time` ingests and
    closes windows, so the silent-empty-view failure is now avoidable through a documented key.
  - `pravaha.lookups.<n>.plugin` / `.options.*` — bound at `SourceBindingProperties.java:83`,
    described in that class's javadoc, absent from every document.
  **The `filesystem` plugin's option keys are no longer undocumented** (*stale-Expected*):
  `QUICKSTART.md:127-130` documents `path`, `schema`, `event.time` and `follow`.

- **DOCX-021 — FAIL, reported as a delta.** `docs/qa/TEST_PLAN.md:13` states **37** distinct
  `pravaha.*` config keys. There is no self-correction to 38 in that file; the case's premise about
  one is itself stale. Enumerated: **19 uncommented leaf keys** in the shipped `application.yaml`
  (node.id; flight.enabled/host/port/tls.certificate/tls.key; security.authentication/policy/audit/
  allow-anonymous; registry.journal; checkpoint.directory/interval/keep; watermark.out-of-orderness/
  idle-after/tick; cluster.mode/mechanism), plus **map-bound families** that have no fixed leaf count
  (`security.tokens.<t>.{id,tenant,roles}`, `streams.<n>.{schema,event-time,out-of-orderness}`,
  `sources.<n>.{plugin,options.*}`, `lookups.<n>.{plugin,options.*}`), plus `checkpoint.timeout`
  (bound, not in the shipped file), plus `cluster.socket.*` ×3 and `cluster.zookeeper.*` ×4, plus
  **2 system properties** — `pravaha.ffm` (`MemoryAccess.java:38`) and `pravaha.memory` (`:41`). The
  finding is that **no single number is reproducible**, because four of the families are maps: a count
  is the wrong shape for this surface, and `37` cannot be re-derived from the tree by any method.

- **DOCX-022 — FAIL.** Three-way split of `application.yaml`'s keys against the seven user-facing
  documents (README, QUICKSTART, USER_GUIDE, OPERATIONS, SECURITY, TROUBLESHOOTING, CONCEPTS):
  **USER-DOC 14, YAML-COMMENT-ONLY 4, NEITHER 1.** The four comment-only entries each state something
  an operator needs and reach no reader outside the jar: `flight.port`'s comment explains *why* 9090
  and not Arrow's 8815 — the exact confusion `OPERATIONS.md:325` then caused (**DOCX-4**);
  `checkpoint.keep`'s explains that pruning is counted and not timed, because "an idle system takes no
  new checkpoints, so after a quiet night every checkpoint is old and a time rule would remove them
  all"; `watermark.out-of-orderness`'s states the ten-second default and which direction is safe; and
  the `security` preamble is the clearest statement in the repository of why the defaults refuse to
  start. NEITHER: `pravaha.lookups.*` (**DOCX-7**).

- **DOCX-023 — FAIL → finding DOCX-18, FIXED in `fbe02eb`.** 16 `pravaha.*` keys named in `src/main`
  javadoc or comments, resolved against the code side. Both of the case's known members appear:
  `StreamSchema.java:53` names `pravaha.watermark.out-of-orderness`, which has no reader (**DOCX-6**);
  and `SecurityProperties.java:52` and `:55` named **two values the code rejects** — `policy: tenant`
  and `audit: log`. Reproduced live rather than inferred:
  `PRV-7002  pravaha.security.policy is 'strict', which is not a policy this node knows. Use
  'permissive' or 'authenticated'`. Both javadoc lines corrected. The owner has named this exact
  failure mode before — `pravaha.streams` was in a javadoc before it existed.

- **DOCX-024 — PARTIAL.** `pravaha.watermark.idle-after` is documented min 1s / max 10m and both are
  **refusals, not clamps**: `500ms` → `PRV-2002 … below the minimum`, `20m` → `… above the maximum`,
  `-1s` → `… below the minimum`. **Vacuity satisfied:** `45s`, inside the range, accepted in the same
  run, so "refused" says something about the bound. Defaults read back from the startup log with
  nothing set: `watermarks: idle-after=PT30S, tick=PT1S`, matching the documented defaults.
  PARTIAL: `pravaha.streams.<n>.out-of-orderness` against `-1s`, `0s` and `9999d` was **not** run —
  the key is undocumented (**DOCX-7**), so there are no documented bounds to check it against, and
  the sub-step is recorded as unreached rather than folded into a neighbour's verdict.

- **DOCX-025 — FAIL → finding DOCX-8.** Six pairs, each started:
  | pair | outcome | documented? |
  |---|---|---|
  | `policy=authenticated` + `authentication=none` | **REFUSED** `PRV-7002`, with a full explanation | the refusal appears in **no** document |
  | `allow-anonymous=true` + `authentication=token` | **STARTS**, silently dead (`allow-anonymous` is guarded on `!authenticates()`) | no |
  | `flight.tls.key` set, `certificate` unset | **STARTS**, `flight transport=PLAINTEXT` | no — **DOCX-8** |
  | `flight.enabled=false` | **STARTS** and says so clearly: `Flight SQL disabled …; this node serves HTTP only` | yes |
  | `cluster.mode=PARTITIONED` + `mechanism=single` | **STARTS**: `cluster mode PARTITIONED on single (consensus), self-contained` | `application.yaml`'s comment says this is refused `PRV-9002`; it is not |
  | `checkpoint` pruning by hand | three documents said to prune by hand; `PeriodicCheckpointer` already does | **DOCX-5** |
  The first is the sharpest shape: an operator *hardening* a `dev` node meets an error message that
  appears in no searchable place. The third is the worst outcome, because it fails open and quietly.

- **DOCX-026 — FAIL → finding DOCX-11, FIXED in `51b27f5`.** Ten settings read from
  `console/config/application.yaml`. Split: **console's own README 2** (`server.port` and `engine.url`,
  in passing inside an override example), **QUICKSTART §7 only 1** (`console.password`, as
  `CONSOLE_PASSWORD`), **nowhere 7** (`console.session_secret`, `CONSOLE_HOST`, `PRAVAHA_TOKEN`, the
  three `ui.*` limits, `LOG_LEVEL`). The word "password" did not occur in `console/README.md` at all.
  The console can drop queries; a reader who cannot sign in had no recourse in the document they were
  reading.

---

## Group D — error codes (DOCX-027 … DOCX-033)

- **DOCX-027 — FAIL (*partly stale-Expected*).** Seven of the case's ten "undocumented" codes are now
  documented; three were not (**DOCX-3**). Reachability, each by a run: **1030** reached (`grpc://:19667`
  → `cannot parse endpoint ':19667': the host is blank`); **1041** reached; **5090** reached (a source
  naming an absent plugin); **5091** reached (a `filesystem` source at a missing path); **6104**
  reached and the case's sharpest claim **confirmed** — `QUICKSTART.md:112`'s TLS block copied
  verbatim with nothing else changed makes the node **exit 1** at
  `PravahaFlightServer.encryptedWith:121` with `PRV-6104 the TLS certificate /etc/pravaha/tls.crt is
  not a readable file`, and nothing in the quickstart marked those paths as placeholders (fixed in
  `51b27f5`). **1031** and **1042** are SDK-surface-only and not reachable through the CLI (recorded as
  not reached, not as absent); **1040** is unreachable in practice because gRPC connects lazily, so a
  dead port yields `1041`; **1043** is unreachable as the case predicts and its documentation row
  *says so*; **5092** not reached — `PumpingFeed.java:118` catches `PravahaException` first, so it
  fires only for a non-Pravaha `RuntimeException`. **Vacuity:** `PRV-2050` reached in the same run
  with its full stderr and help URL, and a successful 3-row read alongside.
  Extra defect found here and fixed: `QUICKSTART.md:127`'s comment offered four plugin names and only
  `filesystem` is in the server jar — the other three give `PRV-5090 … Available: [filesystem]`.

- **DOCX-028 — FAIL (*partly stale-Expected*).** All nine zero-throw codes confirmed, each appearing
  exactly once in `src/main` at its own declaration. Provocations: **5012** surfaces as `PRV-5090`
  (`PluginErrors.LOAD_FAILED` is never consulted); **5020**'s condition does not exist — the string
  `circuit` occurs in `src/main` twice, this declaration and an unrelated comment about boolean
  short-circuiting; **5053** and **5064** surface as `PRV-5090` because the `delta` and `feedfile`
  plugins are not in the server jar, and the analogous `filesystem` + `follow` run reproduced the
  expected **silence** exactly — file deleted, replaced by a directory, a malformed line appended, and
  `pravaha queries` reported `vfeed RUNNING … ROWS IN 4` unchanged throughout with zero `PRV-` lines
  in the log; **9004** has no leader concept to be not-leader of. **8007 provoked end to end** and the
  case's stated *reason* is stale: `PravahaNode.java:531-534` no longer invents a principal, so the
  branch **is** reachable — it reports rather than throws, and the user gets
  `WARN … registration not recovered -- owned_by_ann: its owner 'ann' is not a principal this
  deployment knows`, a view silently absent after a restart announced by a line carrying no code and
  no help URL. **4002** and **4013** not provoked — no CLI or HTTP surface accepts a corrupted
  checkpoint image or a backfill offset; recorded as unreached.
  **Vacuity:** every provocation that ran produced something — an error, a log line, or a measured
  silence.

- **DOCX-029 — FAIL → finding DOCX-19.** 66 codes have more than one throw site; 8 span more than one
  module, which is the signal for divergent meaning. **`PRV-7002`: 18 sites, four meanings** —
  12 authorization denials, 1 open-server startup refusal, 1 policy/authentication contradiction,
  1 split-policy refusal, **3 bad configuration values**; the documented remedy ("Ask for access — a
  new credential will not help") is wrong for **6 of 18**, proven live with a one-word YAML typo.
  **`PRV-2002`: the case's claim confirmed exactly** — 3 of 5 sites are startup configuration
  refusals (`PravahaNode.java:220,251,391`), 1 is a duplicate schema version, and **1** is genuine SQL
  validation, while the ranges table files 2xxx under SQL. **Vacuity:** `PRV-2003`, one site
  (`StreamCatalog.java:83`), one meaning — the classification distinguishes.

- **DOCX-030 — FAIL, and the *opposite* of the Expected on four of five (*stale-Expected*).**
  `Category.CLUSTER` is `(9000, 9999)`, not `(6000, 6999)`; `FLIGHT(6000,6999)` is its own constant;
  `FlightErrors.UNSUPPORTED_TYPE.category()` returns **`FLIGHT`**, verified in `jshell` against the
  built jar; 9xxx codes **do** have a category; and `ErrorCode.category()` **cannot throw** for any
  constructible code — `ApiExceptionHandler.java:69-71` carries a comment recording that exact fix.
  **The one surviving divergence, and it is real:** `PRV-9xxx` was **absent from the ranges table**
  while all seven 9xxx codes sat in the detail table two screens below, so a reader finding `PRV-9003`
  in a log got no subsystem from the table that exists to give them one. Added in `51b27f5`.
  **Vacuity:** the eight ranges that agree across all three sources are the control.

- **DOCX-031 — FAIL, sampled.** **25 of the 99** documented codes with a throw site were checked, and
  the sample size is itself the finding: the detail table has three columns — code, name, range — so
  for **96 of 108 rows the only "documented cause" is the symbolic name**. A cause is written for the
  12 codes in the prose sections; all 12 were read at their guards, plus 13 name-only rows spanning
  every module. Disagreements: `PRV-7002` (**DOCX-19**); `PRV-1041`, documented
  `CLIENT_QUERY_REFUSED`, whose guard catches *every* `FlightRuntimeException`, so a dead port
  produces `PRV-1041 io exception` and a transport failure wears a query-refusal name.
  **`PRV-2003` checked specifically, and the case's claim is half stale and half worse:** it *is*
  emitted — `curl /api/v1/streams/nosuch` → `PRV-2003 no stream named 'nosuch'. Registered: [txn]`,
  correct and naming the real stream. But the SQL path never reaches it: `SqlPlanner.java:137` refuses
  with `PRV-2002` and `"Known streams: " + schema.streamNames()`, which in a served context is the
  **view** catalog — so `SELECT * FROM txn` against a node where `txn` is the only configured stream
  answers `Object 'txn' not found. Known streams: [by_user]`, naming the one thing that is not a
  stream and omitting the one thing that is. `QUICKSTART.md:169-171` anticipates this and misdiagnoses
  it. That is a **code** fix and was left alone.

- **DOCX-032 — FAIL → finding DOCX-20.** `||`'s advice `CAST(… AS VARCHAR)` produces a **byte-identical
  refusal**, and sharper than the case states: Calcite inserts the cast itself, so the user's *first*
  attempt already fails on a `CAST` they did not write, and the message offering the advice is
  unreachable. **Eight** messages name a key that does not exist — seven `arena.slab.size`, one
  `state.slab.size` (the case predicted six). The unbounded-`LEFT`-join refusal's "Swap the inputs and
  use LEFT" produces a *second* refusal. **Six remedies worked and are recorded** so the report is a
  measurement and not a list of complaints: the `TUMBLE`/`HOP` block copied character for character,
  "over a view it is allowed", `pravaha queries --url`, "Use TUMBLE or HOP" for `SESSION`,
  `CAST(NULL AS BIGINT)`, and `SUM(CAST(price AS BIGINT))` — which is the float-aggregate advice, and
  it works. **Vacuity satisfied several times over.**

- **DOCX-033 — FAIL → finding DOCX-21.** `host docs.pravaha.io` → `NXDOMAIN` (the resolver answers,
  so this is a real negative rather than a sandbox artefact). `ExamplesTest.java:152` asserts
  `docs.pravaha.io/errors/PRV-2002` appears in stderr; `PravahaCliTest.java:90` and
  `ApiIntegrationTest.java:91` assert the same URL elsewhere — three tests enforcing a link nobody can
  visit. **No document tells a reader it is not live**; grepped every corpus file for "not live",
  "does not resolve", "NXDOMAIN", "placeholder", "not registered" — zero relevant hits outside
  `docs/qa/**`. The case's premise is also too generous: only the three in-process CLI commands print
  the URL at all — `ServerCommand.fail:238-246` prints the message and nothing else, so `query`,
  `register`, `queries`, `drop`, `pause`, `resume` and `subscribe` omit it.

---

## Group E — links, paths, anchors and structure (DOCX-034 … DOCX-038)

- **DOCX-034 — PASS on the tree, FAIL on the case's description of the test.** **Zero broken relative
  links** across 252 in the corpus. The test is
  `DocumentationFreshnessTest.everyRepositoryPathADocumentPointsAtExists:156`, **not**
  `documentsLinkToFilesThatExist` (no such method), and its corpus is **thirteen** files, not
  fourteen. Its regex `\]\((?!https?://)([A-Za-z0-9_./-]+\.[A-Za-z0-9]+)(?:#[^)]*)?\)` requires a
  dot-extension, so it misses every `](../LICENSE)`, every directory link and all twelve
  `plugins/pravaha-plugin-*` links. **Uncovered set: 169 of 252 (67 %)** — 117 outside the corpus
  (34 in `docs/adr/README.md` alone, plus `examples/`, `console/`, `sdk/`), 52 inside it but
  extensionless. **Vacuity:** six seeded broken links, **two of them in `README.md` itself**, left the
  test `PASSED`; a control seed of the *right shape* in the *right file* did fail it, so the test is
  not broken — its perimeter is. Both overstated claims corrected in `51b27f5` (**DOCX-14**).

- **DOCX-035 — PASS.** 843 headings slugged, **63 anchors, 0 broken, none resolving by prefix
  coincidence**. Both anchors the case names resolve exactly:
  `QUICKSTART.md:90 → CONCEPTS.md#7-bounds-what-changes-the-answer-and-what-protects-the-machine`
  and `QUICKSTART.md:230 → CONCEPTS.md#2-event-time-not-clock-time`. **Vacuity:** two seeded broken
  anchors appeared. Recorded for the perimeter: **no test in the repository checks an anchor at all**
  — the freshness regex discards the fragment — so a heading rename silently breaks all 63 and the
  build stays green.

- **DOCX-036 — FAIL → finding DOCX-22.** 126 backticked paths, **122 resolve, 4 do not**; 3 are real:
  `implementation_plan.md:164` `pravaha-sql/src/test/resources/plans/` (the directory does not exist,
  and the row asserts the golden-plan check runs *every build*); `system_design.md:2968`
  `pravaha-ui/src/main/resources/static/vendor/` (no such module — pre-ADR-033 text);
  `implementation_plan.md:780` `docs/gates/PN/` (packs are `docs/gates/wave-N/`). The fourth,
  `examples/case-studies/README.md:54` `schema/streams.properties`, is a false positive of a
  root-relative resolver and resolves inside each study.
  **The identifier half is the one that rots invisibly, and 3 of 23 had:**
  `SECURITY.md:208` `PravahaFlightServer.location()` — no such method, and the sentence attributes a
  since-fixed defect to it; `system_design.md:2726` `PravahaConfig.fromYaml(path)` — no such type
  anywhere in the tree; `SECURITY.md:192` `ServerCommand.connect()` — actually `connect(Args)` and
  private. Ten identifiers resolved correctly **with matching arity**, including
  `FileCheckpointStore.prune(keep)`, `AccessDecision.deniedWithoutDetail()` and
  `PartitionOwner.snapshot(int)`. A method renamed in a refactor leaves a document naming a symbol
  that no longer exists and **nothing fails** — no link checker sees a backticked identifier.

- **DOCX-037 — PASS.** Complete in all three directions: **34 files, 34 index rows, 0 in the directory
  and not the index, 0 index rows pointing at a missing file, 0 dangling `ADR-nnn` citations** in the
  corpus *or* in `src/main` javadoc (14 distinct ADRs are cited from source), and **0 ADR files never
  cited**. A repository-wide sweep for any `ADR-nnn` outside 001–034 returned nothing. The ordering
  deviation the case predicts is present: rows 021 and 018 sit after 022 (`docs/adr/README.md:31-33`).
  **Cosmetic, recorded so a later reader does not chase it.** Coverage note: the freshness test's ADR
  check reads only its thirteen-file corpus, so the source-javadoc citations and everything inside
  `docs/adr/*` are unchecked — valid today, enforced by nothing.

- **DOCX-038 — FAIL.** The module lists agree perfectly: `pom.xml` has **30** `<module>` entries (the
  case says 31), 0 built-but-undescribed, 0 described-but-unbuilt except `sdk/python`, which is
  correctly not a Maven module. All five freshness assertions ran green against the real tree.
  What "described" means to the test is the finding: `corpus.contains(module)` — **the literal module
  path appearing anywhere in thirteen concatenated files.** A link href counts. Seeded proof:
  deleting `pravaha-serving` from the README's list fails `theReadmesModuleListMatchesTheBuild` but
  `everyMavenModuleIsDescribedInTheDocumentation` **still passes**, because `ARCHITECTURE.md` names
  it; and `pravaha-embedded` and `pravaha-server` can be deleted from the list entirely with the build
  green, because they are named elsewhere in `README.md`. So the claim holds for 28 of 30 modules.
  All three specific claims confirmed: **`pravaha-algebra`** is imported by nothing outside itself
  (one hit anywhere, an ArchUnit package string); **`ShadowDeployment`** has every reference in its own
  test; **`pravaha-embedded` has nine methods** — two static factories and seven instance — and no
  `register`, `query` or `read`. Three documents presented **the CLI** as a consumer of
  `pravaha-embedded`, and `pravaha-cli/pom.xml` does not depend on it; corrected in `51b27f5`
  (`README.md:211`, `OPERATIONS.md:431`) and covered by the header on `system_design.md`.

---

## Group F — ADRs against the code they decided (DOCX-039 … DOCX-046)

- **DOCX-039 — FAIL (*stale-Expected*) → part of finding DOCX-12.** Claims (b), (c) and (d) are
  **true and implemented**, and the audit says so: no connector or plugin sees a principal or a policy
  (`grep` over `plugins/` and `pravaha-connect/src/main` returns nothing); `ViewQuery.java:508-527`
  refuses a filter naming a column the view lacks (`PRV-7003`) and `:526-546` fails **closed** on a
  tautology; `:186-188` ANDs the filter in as a `FilterOperator` above the scan — 11/11
  `ViewQueryAuthorizationTest` green, including `aRowFilterCannotBeUndoneByTheCallersOwnWhereClause`.
  Claim (a) splits: the case's "no controller consults the policy" is **now wrong** —
  `StreamController.java:65,76,95` and `QueryController.java:78` call `HttpAuthorizer`, whose javadoc
  documents the repair; only `StatusController` consults nothing. The case's *second* half **holds**:
  `PravahaNode.java:291-306` accepts only `permissive` or `authenticated`, neither of which ever
  returns an `AccessDecision` carrying a `rowFilter`, so the best-built mechanism in this ADR is
  unreachable from any server configuration. ADR-031 now says so.

- **DOCX-040 — PASS.** Expected held exactly. Real CLI runs against a two-column fixture:
  `LIKE 'E%'` → **2 rows**; `LIKE ?` → `PRV-2021 … a pattern that is not a literal`;
  `LIKE 'E%' ESCAPE '!'` → `PRV-2021 … ESCAPE clause, which is not built`; `LIKE status` → `PRV-2021`.
  **Vacuity:** a query with no `LIKE` returned rows in the same run. `SQL_SUPPORT.md:97-98` is
  **accurate**; **ADR-032:61 was stale** — "LIKE is not implemented at all; `LIKE 'u%'` is refused
  too" — one clause of one table cell, and the clause a reader checking "can I use LIKE?" stops at.
  Fixed in `51b27f5`.

- **DOCX-041 — PASS.** Zero production readers of `emitsDeletes`, `replayableOffsets` or
  `DeliveryGuarantee.weakest` (every caller of the last is in `CapabilitiesTest`).
  `grep -i capabilit QueryRegistry.java` → no hits, so there is no code path on which a registration
  could be refused for a capability. **Vacuity satisfied by the positive control:** the declarations
  *are* made, honestly — `AerospikeSourcePlugin.java:197-200` sets `emitsDeletes = false` with a
  comment calling it "the strategy's defining limitation". One correction to the case: Aerospike
  declares `AT_LEAST_ONCE`, not `EXACTLY_ONCE`, with a javadoc explaining why. The live registration
  step needs an Aerospike cluster and was not run; the static proof is the stronger one and is
  recorded as such.

- **DOCX-042 — PASS → finding DOCX-12.** Zero hits for `pravaha-spring-boot-starter`,
  `@PravahaListener` or `PravahaTemplate` as code in any `src/`. **Vacuity:** ADR-019 *is*
  implemented — `grep -rln org.springframework */src/main` returns 16 files, **all** under
  `pravaha-server`. The finding is not the missing module, which may be a deliberate deferral, but
  that **built and unbuilt decisions looked identical**: ADR-020's status row read `Accepted`, exactly
  like every shipped decision. `README.md:212` marks it honestly; `system_design.md:2649-2654` prints
  a copy-pasteable `<dependency>` block for it with no marker.

- **DOCX-043 — FAIL (*stale-Expected*).** "No Ratis anywhere" **holds** (zero hits in every `pom.xml`;
  `StateClusterTest.java:261` asserts mechanism `raft` is *refused*). Two of the Expected's four
  assertions are now false: ADR-009 **does** carry an implementation-status section pointing at
  ADR-034, and `OPERATIONS.md` documents **five** cluster keys, **every one with a production reader**
  (`CoordinatorFactory.java:74,104`, `SocketProvider.java:54,80,81`) — there is no S-3 here, and
  `OPERATIONS.md:136-139` states "**Raft is not implemented**" in bold. The residual defect is real
  and smaller: the pointer was in the body while the `| Status |` row still read a bare `Accepted`, so
  a reader scanning status rows saw a live decision. **Vacuity:** ADR-030's supersession of ADR-007 is
  present and reciprocal — the convention works when someone remembers. Fixed in `51b27f5`.

- **DOCX-044 — PASS → part of finding DOCX-12.** **Zero WebSocket implementations** anywhere: the only
  two occurrences outside `docs/` are comment lines in `console/routes/api_routes.py:118-119`
  explaining why one was deliberately *not* built. The SSE that exists is the Python console
  re-encoding an SDK/gRPC stream (`console/core/engine.py:18,140` → `pyarrow.flight`), which is the
  "translation layer that would acquire semantics of its own" that ADR-026 rejects in its own
  Alternatives section. **Vacuity:** the gRPC carrier found in the same run
  (`PravahaFlightSqlProducer.java:463`). **ADR-011's conflating tap *does* exist as code** and this is
  the affirmative half worth recording: `SubscriptionOptions.java:32`
  `DEFAULT = (10_000, Overflow.CONFLATE)`, implemented at `Subscription.java:116-124`, conflation key
  at `ServedView.java:131`, drop count exposed at `:192`.

- **DOCX-045 — FAIL (*two-thirds stale-Expected*).**
  **(a) ADR-013 — the case's Expected is now FALSE and must be retracted.** A probe compiled against
  `pravaha-serving/target/classes`: weight 0 → `size=0`; `+2` then `−1` → `size=1`; a further `−1`
  (net 0) → `size=0`. `ServedView.applyWeighted:194-208` maintains a running net and tombstones only
  at `net <= 0` — it is **not** last-write-wins. The ADR is still only half true: `pravaha-algebra`,
  the DBSP oracle it names, is imported by nothing outside itself.
  **(b) ADR-025 — Expected HOLDS, and worse than stated.** Measured: `fp(--keys 1)` and
  `fp(--keys 0,1)` are the **identical** digest `b96a9d58…`, the two registrations share one object,
  and the view's `keyOrdinals` for the second name is `[1]` — it asked for `[0,1]` and silently got
  the first registration's. **Vacuity:** two registrations differing in SQL produce different
  fingerprints. The security half *is* implemented, so the sentence is half true, which is the worst
  state for a sentence. **This is the one Group-F result that warrants a code change** and was left
  alone: it produces a wrong answer rather than an error.
  **(c) ADR-008 — the case's Expected is now FALSE.** `QueryRegistry.java:469,473` call `store.latest()`
  and `execution.restore(...)` from the registration path at `:615`, before the feed opens.
  `PluginSourceFeedsTest#aQueryResumesFromItsCheckpointRatherThanReplayingTheWholeFile` passes,
  asserting **three** rows — "not zero, which is state lost, and not six, which is the file replayed".
  ADR-008's own status section said "registered queries are not checkpointed at all"; struck in
  `51b27f5`.

- **DOCX-046 — PASS.** 34 rows produced, one per ADR, each with one line of evidence.
  **Non-`IMPLEMENTED`: 20 of 34** — 14 IMPLEMENTED, 12 PARTIAL, 5 DECIDED-NOT-BUILT (009, 010, 015,
  016, 020), 2 CONTRADICTED-BY-CODE (026, 027, whose `LaneMultiplexer` is on the known-orphan list).
  **Vacuity satisfied with executable evidence:** ADR-031's soundness rule (11 tests run this
  session), ADR-030's Flight SQL protocol, ADR-003's binary rows on the ingest hot path, ADR-011's
  conflating tap. Twelve of the non-IMPLEMENTED rows now carry that status in the ADR itself.
  **20 of 34 is the number to track**: it is how far the recorded architecture has drifted from the
  built one.

---

## Group G — every claim that something is enforced by a test (DOCX-047 … DOCX-052)

The class of defect that stops people checking, which makes it worse than the thing it conceals.

- **DOCX-047 — PASS → finding DOCX-14.** `grep -n QUICKSTART ExamplesTest.java` → **one hit, in a
  javadoc sentence**, never in code. Every file it opens: the two example CSVs, the two example
  READMEs, an `examples/` directory listing, and its own `@TempDir` scratch. **`docs/QUICKSTART.md` is
  not among them.** Coverage **0 / 31**. The two methods *named* for the quickstart
  (`theQuickstartsValidateCommandWorksAndReportsItsFields`, `theQuickstartsExplainCommandShowsBothPlans`)
  assert command lines whose SQL and schema **do not appear in `docs/QUICKSTART.md` in any form** —
  `grep -n 'validate\|explain' docs/QUICKSTART.md` returns nothing. **Vacuity satisfied:** the three
  example READMEs *are* read, so the absence is specific rather than an inability to read a document.
  The rot the claim says cannot happen had already happened — DOCX-007.
  (`DistributionTest.java:97` does open QUICKSTART, to assert it contains two substrings. Coverage
  contribution: 0.)

- **DOCX-048 — FAIL (*stale-Expected*), and the direction is good news.** The test has been upgraded:
  **122 `Case` entries**, of which **55 are `Case.answers`**, which runs the 4-row fixture and
  **compares rendered rows**. So "it runs no row and compares no value" is no longer true.
  Document side: **64 construct rows** (the case says 77), 42 ✅ and 22 ❌.
  **Fraction of ✅ rows whose *answer* is asserted: 26 / 42 (62 %)** — the case expected 0. But
  **all seven ✅ join rows are plan-only (0 / 7)**, which is where the expensive wrong-answer defects
  live. 4 constructs in the document and not the test; 17 in the test and not the document.
  **Two live contradictions found and fixed in `51b27f5`:** `SQL_SUPPORT.md:112` lists
  `COUNT, SUM, MIN, MAX, AVG` as ✅ with no caveat while the matrix asserts `SUM(price)` and
  `AVG(price)` over `FLOAT64` are refused `PRV-2020`; `:113` lists `COUNT(DISTINCT x)` as ✅ while
  `ExamplesTest:241` asserts the unwindowed form exits non-zero.
  **Vacuity satisfied by seeding:** a scratch copy with `ORDER BY`'s expected code changed and the
  `SUM` answer changed 800 → 801 goes red on both assertions, so it is a real test of both things.

- **DOCX-049 — PASS → finding DOCX-14.** **3 / 9.** Covered: `01`'s `run`, `02`'s `run`, `02`'s
  `validate`. Not covered: both `explain` commands (`01`'s is asserted with *different* SQL — one
  column, reordered predicate) and all five lines of `03`'s classpath incantation.
  **Vacuity — the passing half is what makes the gap a gap, and it deserves saying:** `ExamplesTest`
  runs the command, asserts the produced CSV byte-exactly, **and** asserts the README still contains
  the string it quotes. That two-way check is the strongest documentation enforcement in the
  repository.

- **DOCX-050 — FAIL on one detail of the Expected: the corpus is THIRTEEN files, not fourteen.**
  Everything else held. Every assertion seeded in a symlink-farm copy and **all ten fire**: a phantom
  module, a named-but-absent module, `ADR-999`, a broken link, a wrong README wave, `./mvnw` →
  `./gradlew` in QUICKSTART, a deleted QUICKSTART, an emptied `examples/`, a fictional Java type in a
  README fence, and a module removed from the README list. The `<!-- illustrative -->` escape hatch
  correctly silences the last.
  **The good pattern, confirmed empirically:** pointed at a near-empty tree, two assertions pass
  *vacuously* (`if (!Files.exists(path)) continue;`) and only the self-check
  (`> 10` modules, `> 50_000` characters) fires — it is the single thing standing between a path bug
  and a green build. The same file also gets the `.claude` worktree trap right, comparing against the
  walked root rather than testing for a substring, with the reason written down at `:536-538`.
  **The perimeter — the group's most useful artefact.** *Inside*, and only this: modules named;
  no phantom modules; ADR numbers cited exist; `](path.ext)` links in thirteen files resolve;
  README's wave ≥ newest gate; QUICKSTART exists and contains two substrings; `examples/` non-empty;
  Java fences **in `README.md` only** name real types. *Outside*: 55 of the 68 corpus files entirely —
  all 34 ADRs, all five gate packs, every example and case study, `console/`, `sdk/`; **every shell
  command** (all 196); **every configuration key** (all 125); **every error code**; **every anchor**;
  every external URL; every reference-style link; every ADR's *content*; QUICKSTART's content beyond
  two substrings; every prose claim; every quoted number or output; every cross-document
  contradiction. And its own blind spot: **nothing checks that the corpus list is complete**, so a new
  document is outside the perimeter by default and silently.

- **DOCX-051 — PASS.** 102 claims resolved; **62 name no artefact at all** and are unfalsifiable by
  construction, which this case file rates worse than false. Of the resolvable remainder:
  `TROUBLESHOOTING.md:277` "generated from the source" → **FALSE** (**DOCX-3**, since fixed);
  `:278` "if a code is missing here it does not exist in the engine" → **FALSE** for three codes;
  `docs/README.md:44`, `README.md:298`, `HANDOVER.md:345,349` repeat the generation claim → FALSE;
  `implementation_plan.md:50` "a script resolves every cross-reference … and fails the build" →
  **FALSE**, no such script, and all three workflows run only `./mvnw verify`; `:261,:338,:360`
  benchmark-regression and cold-start CI gates → **FALSE**; `system_design.md:2919` "a test
  regenerates the stylesheet and compares it byte for byte" → **FALSE**;
  `sdk/python/README.md:77` "the Maven build still runs these tests" → **PARTIAL**, the profile exists
  and `pravaha.python.tests.skip` defaults to `true` with no workflow passing `-Ppython`.
  **The TRUE rows keep the report honest:** `ErrorCodeUniquenessTest` **TRUE** — seeded a duplicate
  `PRV-2050` and an out-of-range `PRV-12345`, both went red, removed, green;
  `SECURITY.md:118` "There is a test for exactly that attack" **TRUE** —
  `ViewQueryAuthorizationTest.java:106` runs the exact neutralising predicate;
  `ARCHITECTURE.md:568-578`'s eight-class enforcement table **TRUE**, all eight exist;
  `examples/03`'s Spring enforcer rule **TRUE** (`pravaha-embedded/pom.xml:51-69`);
  the console help "rendered, not copied" **TRUE** (each topic carries `include: docs/<DOC>.md`);
  and `docs/gates/wave-7/README.md:22` "**NOT VERIFIED** … 'should' is not a gate" — recorded because
  it proves the corpus can state a gap accurately when it chooses to.
  Of the 19 named `*Test` classes across all 102 claims, **18 exist**.

- **DOCX-052 — PASS.** The four fractions, each with a real numerator and denominator:
  | | fraction | |
  |---|---|---|
  | documented **commands** executed by a build-time test | **3 / 196** (1.5 %) | **from the file: 0 / 196.** QUICKSTART 0/31; example READMEs 3/9 |
  | documented **config keys** asserted by any test | **13 / 125** (10 %) | 102 of 125 appear in no test source at all |
  | documented **error codes** asserted by any test | **67 / 108** (62 %) | and now 111/111 are *documented*, enforced |
  | documented **SQL constructs** whose *answer* is asserted | **26 / 42 ✅** (62 %) | **0 / 7 for joins** |
  The cheapest test that would raise each, one line:
  1. **Commands** — make `ExamplesTest` *parse* the fenced `bash` blocks out of `docs/QUICKSTART.md`
     and feed each `pravaha …` line to `PravahaCli.run` as argv, asserting the exit code and the next
     fenced block as expected output. **This is the single change that would have prevented DOCX-007**,
     and it is the one this round did not make.
  2. **Config keys** — one test diffing every `pravaha.*` token in the corpus against the
     `@ConfigurationProperties` setters and `Configuration.get*` literals, failing in either direction
     with the key named. The extractor already exists at `$QA/extract/keys.py`.
  3. **Error codes** — **done this round**: `ErrcCrossCuttingTest` now asserts set-equality between the
     declarations and `TROUBLESHOOTING.md`'s rows, in both directions.
  4. **SQL answers** — convert the fourteen plan-only ✅ rows from `Case.ok` to `Case.answers`,
     starting with the seven join rows, since a join returning the wrong pairs is the class of defect
     a plan-only matrix structurally cannot see.

---

## Group H — the README banner and cross-document consistency (DOCX-053 … DOCX-056)

- **DOCX-053 — FAIL → finding DOCX-2, FIXED in `51b27f5`. Run first, as the case instructs.**
  **`README.md`'s banner has already been corrected** — lines 11-15 now assert the positive, and the
  status line and Wave-7-of-10 badge are accurate (waves 8-10 are genuinely not started; the roadmap
  in `system_design.md` and `docs/gates/` agree). All three claims tested by experiment anyway,
  because that is what makes the fix verifiable:
  - **(a) "nothing generates watermarks" — false.** Startup log:
    `PravahaNode : watermarks: idle-after=PT30S, tick=PT1S`.
  - **(b) "over an unbounded stream, no window would close" — false.** Over a `follow: true` source
    appended to continuously, **three** windows closed with hand-computed sums: u1's window ending
    09:03 = 500 and u2's ending 09:02 = 400, each matching the fixture by hand, and after three
    appended rows u1 = 700 and u2 = 800.
  - **(c) "the `pravaha-server` process has no ingestion path at all: a query registered against it
    never receives a row" — false.** `ROWS IN 10`, and the view answers.
  **Vacuity:** the same node answered `PRV-4023 no views are registered` after the drop, and a
  registration on a stream with no rows yet showed `ROWS IN 0`, so a non-zero count is ingestion and
  not a default.
  **`docs/HANDOVER.md:394` still carried the claim** — the document whose purpose is to tell the next
  session what is true. That is the finding, and it is exactly the shape the case predicted, one
  document later than predicted.

- **DOCX-054 — FAIL.** Eight questions, each settled by a **run** and not by majority vote:
  | | answer, measured | stale documents |
  |---|---|---|
  | (a) does the server ingest? | **yes** — `ROWS IN 10` | `HANDOVER.md:394` → **DOCX-2** |
  | (b) metrics endpoint and names? | **yes**, `/actuator/prometheus` 200, all seven names exact | documented **four** contradictory ways: `OPERATIONS.md:333` CURRENT, `:49-51` and `:483` STALE (**in the same file**, one admitting in prose that it contradicts the other), `QUICKSTART.md:355` STALE → **DOCX-5** |
  | (c) does a console exist? | **yes**, ran it, every route 200 | none stale; `README.md`'s old "no UI" line is already gone |
  | (d) how many case studies? | **five** on disk | six documents said four → **DOCX-15** |
  | (e) default Flight port? | **9090** (`application.yaml:72`) | `OPERATIONS.md:325` printed 8815 → **DOCX-4** |
  | (f) does windowing work? | **yes**, three windows closed | `examples/02-aggregate/README.md:39` said it "arrives in Wave 4" |
  | (g) is `COUNT(DISTINCT)` supported? | **windowed yes, unwindowed refused `PRV-2050`** | `SQL_SUPPORT.md:113` ✅ with no caveat → fixed |
  | (h) do checkpoints restore? | **yes** — `QueryRegistry:469,473`, `PluginSourceFeedsTest` green | `ADR-008`'s own status section said they are not → fixed |

- **DOCX-055 — FAIL.** **85 forward-looking sentences** extracted from the corpus (excluding the two
  aspirational documents and the gate packs, which are historical by nature).
  **`STILL-TRUE` (vacuity satisfied):** `QUICKSTART.md:361` "Clustering, HA, failover | Wave 8 —
  single node today"; `SECURITY.md:226` mTLS at Wave 8; `:228` security review and SBOM at Wave 10;
  `OPERATIONS.md:488` no clustering.
  **`SHIPPED-SINCE`, all fixed this round:** `examples/02-aggregate/README.md:39` "Windowing … arrives
  in Wave 4" while the quickstart uses `TUMBLE`; `QUICKSTART.md:355` metrics at Wave 9;
  `OPERATIONS.md:482-483` no pruning and no metrics endpoint; `HANDOVER.md:24` "Wave 6 (E5) has
  started", two waves behind its own body (**DOCX-16**).
  **`ABANDONED`:** ADR-009's Ratis, now pointed at ADR-034.
  `README.md`'s "What is not built" table was re-checked item by item against the tree and is
  **accurate in both directions** — the round-1 finding against it no longer reproduces.

- **DOCX-056 — FAIL → finding DOCX-13, FIXED in `51b27f5`.** Neither document carried a status marker
  or a dated header. Twenty behavioural claims sampled and **recorded here so a later round samples
  the same twenty**: (1) `mode: HA` — **NOT-BUILT**, a value the code rejects; (2)
  `PravahaConfig.fromYaml` — NOT-BUILT, no such type; (3) `PravahaProperties` mirror + reflecting sync
  test — NOT-BUILT; (4) `@PravahaTest` — NOT-BUILT; (5) `pravaha-ui/` vendored assets — NOT-BUILT;
  (6) `/actuator/pravaha` — NOT-BUILT, 404 measured; (7) `POST /api/v1/queries/{id}/backfill` —
  NOT-BUILT, 404 measured; (8) the `pravaha-spring-boot-starter` dependency block — NOT-BUILT;
  (9) cross-reference validation script in CI — NOT-BUILT; (10) benchmark regression gate — NOT-BUILT;
  (11) cold-start < 1 s asserted in CI — NOT-BUILT; (12) golden-plan resource directory — NOT-BUILT;
  (13) stylesheet byte-comparison test — NOT-BUILT; (14) Storybook / axe / Lighthouse gates —
  NOT-BUILT; (15) `docs/gates/PN/` naming — NOT-BUILT; (16) RocksDB L1 tier — NOT-BUILT;
  (17) Calcite as compiler with a custom runtime — **BUILT**; (18) binary flyweight rows over an arena
  — **BUILT**; (19) Flight SQL as the one client protocol — **BUILT**; (20) Spring-free engine core
  with an enforcer rule — **BUILT**. **Proportion NOT-BUILT: 16/20.** **Vacuity satisfied:** four
  BUILT claims. Both documents *are* in the freshness test's corpus, which asserts they exist and that
  their extensioned links resolve, and nothing about their content. Labelling rather than correcting
  is the honest fix: 4,955 lines cannot be audited per round.

---

## Group I — the onboarding walk (DOCX-057 … DOCX-060)

- **DOCX-057 — FAIL.** The walk, `README.md` → `docs/QUICKSTART.md` → a running query, typing only
  what is printed. **First invention at step 2**, and it stops everybody: the printed command exits 2
  before any concept is reached (**DOCX-1**). Past that, in order: the configuration file's **name and
  directory** (§4 prints a YAML block and says "with that file"); the **CSV's contents**; a schema
  that matches the query. The timestamp wire format and the source-level `event.time` key are now
  documented and were not inventions. **Vacuity control satisfied and it is the round's key
  measurement:** a second walker who knows the answers reached a running continuous query with
  correct hand-computed sums in the same session — so "the reader cannot get there" is distinguishable
  from "the engine cannot get there", and the two need different fixes. After `51b27f5` the first
  invention moves from step 2 to step 4.

- **DOCX-058 — PARTIAL.** Four doors:
  - **CLI / quickstart** — reaches a running query (the control walk above). **Works.**
  - **Console** — `make install`, run, every route 200. **Works**, with the password gate documented
    only two directories away until `51b27f5` (**DOCX-11**).
  - **Embedded** — **stops early, as predicted.** `pravaha-embedded`'s `PravahaEngine` has nine
    methods — two static factories plus `start`, `stop`, `state`, `configuration`, `plugins`,
    `instanceId`, `close` — and no `register`, `query` or `read`. A reader following ADR-019's
    "plain-Java `PravahaEngine` seam" arrives at an object that starts and stops. `examples/03`'s own
    quoted output is the proof: it prints the engine's state and nothing else.
    `implementation_plan.md:359`'s acceptance criterion "a 15-line Java main **runs a query**
    in-process" is unmet.
  - **Container** — `NOT-EXECUTABLE` here; the Dockerfile was reviewed statically (DOCX-006) and its
    ports, entrypoint and jar globs match the printed command. PARTIAL because this door was not
    walked to a running query.
  **Vacuity:** at least one door works — two do.

- **DOCX-059 — FAIL.** Eight operational questions, searched as an operator would:
  | question | answer exists? | actionable? |
  |---|---|---|
  | security configuration | yes, `SECURITY.md` + `application.yaml` | **yes** — names keys and values |
  | binding a source | yes, `QUICKSTART.md:120-147` | **yes** — and this is the fully-answered one the vacuity guard needs |
  | checkpoint / journal operations | yes | **was wrong** — three documents said to prune by hand (**DOCX-5**) |
  | upgrade | `OPERATIONS.md:470` | **partly** — names `ShadowDeployment`, which is called from nothing |
  | backup | no dedicated answer | no |
  | monitoring | **four contradictory answers** (DOCX-054b) | now yes, after **DOCX-5** |
  | capacity planning | `ARCHITECTURE.md` prose, no numbers | no |
  | what to do when a query fails | `TROUBLESHOOTING.md` | **partly** — the table was missing three codes including two an ingest misconfiguration meets first (**DOCX-3**); and there is **no answer at all** for "a query reports `RUNNING` and its lane is dead" (PERF-041), which is the failure mode ten surfaces misreport |
  **Vacuity satisfied:** "binding a source" is fully and correctly answered.

- **DOCX-060 — PASS.** `$QA/README.md` written — in the scratch directory, **not** in the repository,
  as the case requires. It carries the five extractors and their invocations, the fixed corpus
  command, the report layout, the denominators (D0 68, D1 200 after this round's own fixes — 196
  before, D2 125, D3 111, D4 252/63/126, D5 102) so a later run can detect that the corpus changed,
  and the ordered change→case mapping derived from every **Re-run** line in the case file. It also
  carries the `.claude` worktree trap in both its halves, because that is the thing that has cost five
  sessions.
  **Three cases re-run cold and the byte-identical requirement met where it applies:** `commands.py`
  and `codes.py` each produce byte-identical output on a second run over an unchanged tree
  (`diff -q` clean), and the ADR three-way diff reproduces 34/34/34. The diff between this round's
  first and last `commands.tsv` is **entirely attributable to this round's own documentation fixes**
  (+4 rows: two in `console/README.md`, two in `docs/HANDOVER.md`), which is the instrument working.
  **Recorded honestly, and it is the case's own standard turned on this round:** cases with a port, a
  server or a wall clock (006–018, 024–027, 032, 053, 057) are **not** byte-stable and must not be
  diffed that way; `$QA/README.md` says so and says which to compare by verdict instead. And the
  per-file `$QA/report/*.tsv` that Group B's cases specify was **not** built — the commands were
  executed and their outcomes recorded here per case, but not emitted as a diffable two-column file.
  That is the one deliverable of this area that a later round would have to build before it could diff
  rather than re-read, and `$QA/README.md` §5 says what it would take.
