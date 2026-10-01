# DOCX — Documentation, round 2: the audit method

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Area: **every documented instruction executed literally; every config key audited in both
directions; every error code, link, path, ADR and test-coverage claim checked against the thing it
claims.**

IDs `DOCX-001`–`DOCX-060`. Budget 60. Supersedes round 1's `DOC.md` (50 cases, 28 FAIL) and its
Re-QA (11 new defects, 0 verified fixed) for coverage purposes.

**This area is not a re-listing.** Round 1 found the rot; round 1's Re-QA found that none of it had
been fixed and that eleven more had arrived. Re-reading the same documents and reporting the same
sentences is worth nothing. What is worth something is an **audit method**: a set of cases that a
later session can re-run after any change and that will catch the *next* divergence mechanically,
without anyone having to remember that a divergence is possible.

Every case therefore carries a seventh field:

> **Re-run:** the change that makes this case's verdict stale. A case whose re-run trigger is "never"
> is a fact, not an audit, and does not belong here.

Wherever a case can be reduced to a script, it is specified as one, with its output shape given, so
that two runs a month apart can be diffed rather than re-read.

**Ports reserved: HTTP 18660–18699, Flight 19660–19699.** Servers are recorded by PID and killed by
PID. **No production code and no document is modified by any case in this file** — this is an audit,
and editing the documents underneath it would produce a report about a state that no longer exists
(the round-1 process failure, recorded in `FINDINGS.md`).

Scratch: `$QA = <scratchpad>/qa-docx`. Every extractor writes to `$QA/extract/` and every audit
writes a two-column TSV to `$QA/report/` so the whole area's output is diffable.

## The corpus, fixed once

```
README.md
docs/*.md                       (13 files)
docs/adr/*.md                   (34 ADRs + README)
docs/gates/wave-*/              (5 gate packs)
examples/README.md
examples/*/README.md            (3)
examples/case-studies/**/*.md
console/README.md
sdk/*/README.md
api/**/*.md, config/**/*.md
```

`$QA/corpus.txt` is the file list, produced by `git ls-files '*.md'`. Using `git ls-files` rather
than `find` is deliberate: an untracked scratch document must not enter the audit, and a document
deleted from the index must leave it.

## The known baseline

These are established. A case below that touches one measures its extent or re-checks it
**mechanically**; none exists to rediscover it.

| Known | From |
|---|---|
| QUICKSTART steps 2 and 3 — the first two commands a reader types — do not run | DOC-005/006, still failing |
| README's evaluation banner carries **three** false statements; `HANDOVER.md:394` repeats one | DOC-057 |
| `HANDOVER.md` claims `ExamplesTest` executes every QUICKSTART command; it never opens the file | DOC-038 |
| `SQL_SUPPORT.md` claims every construct is checked by `SqlSupportMatrixTest`; it compares no values and runs no rows | Q-8 |
| 38 `pravaha.*` settings (36 YAML + 2 system properties); gaps in **both** directions | DOC Part 5 |
| 110 error codes, 100 documented, **10 missing**, **9 with no throw site** | E-1 and the ERRC corrections |
| ADR-032 is stale on `LIKE`; ADR-029 describes a capability mechanism that was never built | this file, DOCX-040/041 |
| `docs.pravaha.io` is NXDOMAIN while a test asserts on the URL | DOC round 1 |

---

## Group A — the instruments

Five cases, `DOCX-001`–`DOCX-005`. These build the extractors the rest of the area runs on. They are
cases in their own right because **an extractor that silently sees nothing turns every audit below
it into a pass**, which is the single most likely way this area produces a false clean bill.

## DOCX-001 — the command extractor, and proof it sees the corpus
**Intent:** every audit of "documented instructions" depends on finding them. Build one extractor,
make its output the contract, and prove it is not blind.
**Falsifier:** the extractor returning fewer commands than a hand count of QUICKSTART alone, or
missing any of the five shapes below.
**Setup:** `$QA/extract/commands.py` reading `$QA/corpus.txt`.
**Steps:** extract every shell command from: (a) fenced ```` ```bash ```` / ```` ```sh ```` / ```` ```console ```` blocks; (b) fenced blocks with no language that begin with `$ `; (c) indented four-space blocks beginning with `$ `; (d) inline backticks matching `^(pravaha|\./mvnw|mvn|docker|make|curl|python3?|pip|export|openssl|java|git) `; (e) `ENTRYPOINT`/`CMD` lines in `Dockerfile`. Emit one TSV row per command: `file<TAB>line<TAB>command`. Then hand-count the commands in `docs/QUICKSTART.md` and compare.
**Expected:** the hand count of QUICKSTART's commands equals the extractor's count for that file, exactly. Across the corpus the total is recorded as the area's denominator and printed at the top of every later report. A command spanning lines with `\` continuations is **one** row, not three.
**Vacuity:** the case cannot pass with a broken extractor, because the hand count is done independently and the two must agree on a file with no ambiguity in it. Deliberately insert one extra `pravaha version` line into a copy of QUICKSTART in `$QA` and confirm the count rises by exactly one.
**Re-run:** any document added, removed or renamed; any new fence style introduced.

## DOCX-002 — the configuration-key extractor, both directions
**Intent:** a key audit is a set difference, and both sets have to be produced mechanically or the
difference is an opinion.
**Falsifier:** either set missing a key that is provably in it — `pravaha.security.tokens` (a map
prefix) on the code side, `pravaha.streams.<n>.event-time` (a nested, name-parameterised key) on the
document side.
**Setup:** `$QA/extract/keys.py`.
**Steps:** **code side** — collect from main sources: `@Value("${...}")`, `@ConfigurationProperties(prefix=...)` crossed with the setters of the annotated class, `Configuration.get*("...")`, `System.getProperty("pravaha...")`, and every string literal matching `pravaha\.[a-z0-9.\-<>]+`. **document side** — every `pravaha.*` token in the corpus, including inside YAML blocks, javadoc and prose, normalised so that `pravaha.streams.txn.schema` and `pravaha.streams.<n>.schema` collapse to one key. Emit `$QA/extract/keys-code.txt` and `keys-docs.txt`, sorted.
**Expected:** the code side contains **38** entries (36 YAML-bindable plus the two system properties), which is the figure `TEST_PLAN.md` corrects `37` to. `pravaha.security.tokens` appears as a prefix with a note that its *keys are credentials*. The document side contains `pravaha.watermark.out-of-orderness`, which the code side does not.
**Vacuity:** seed a key that exists in neither (`pravaha.nonsense.key`) into a `$QA` copy of a document and confirm it appears in the document set and in the difference; delete a known key from the code-side glob and confirm the count drops. Both are required — a one-sided extractor produces a confident, wrong answer in the safe direction.
**Re-run:** any change to `application.yaml`, any new `@ConfigurationProperties` class, any document mentioning a key.

## DOCX-003 — the error-code extractor
**Intent:** `TROUBLESHOOTING.md` says of itself that it is "generated from the source, not from
memory" and that "if a code is missing here it does not exist in the engine". Two falsifiable claims,
and they need two sets.
**Falsifier:** the code side omitting the Java SDK's six codes — the omission that made the first
count 104 instead of 110.
**Setup:** `$QA/extract/codes.py`.
**Steps:** **declared** — every `new ErrorCode(<n>, "<NAME>")` in every `src/main` tree in the
repository, **including `sdk/`**, with its file and line. **thrown** — every construction site of a
`PravahaException` (or equivalent) naming each code, so a code with zero throw sites is visible.
**documented** — every `PRV-\d{4}` in `docs/TROUBLESHOOTING.md`, split into the ranges table and the
detail table. Emit a four-column TSV: `code, declared-at, throw-sites, documented(y/n)`.
**Expected:** 110 declared. 100 documented. **10 undocumented**: `PRV-1030`, `1031`, `1040`, `1041`,
`1042`, `1043`, `5090`, `5091`, `5092`, `6104`. **9 with zero throw sites**: `1043`, `4002`, `4013`,
`5012`, `5020`, `5053`, `5064`, `8007`, `9004`. Zero documented-but-undeclared, so the error is
one-directional. Note `PRV-1043` appears on both lists.
**Vacuity:** the counts are the vacuity guard — three independent numbers that must all come out, and
a regex that silently matched nothing would produce zeroes rather than 110/100/10.
**Re-run:** any `new ErrorCode` added or removed; any edit to `TROUBLESHOOTING.md`.

## DOCX-004 — the link, anchor and path extractor
**Intent:** three classes of reference, one extractor, because they rot the same way and a reader
cannot tell them apart.
**Falsifier:** the extractor not distinguishing the three classes, or resolving a relative link from
the wrong base directory — which would make every `../` link in `docs/adr/` report falsely.
**Setup:** `$QA/extract/links.py`.
**Steps:** for every corpus file extract (a) markdown links `[text](target)` where the target is not
`http(s):`; (b) the `#anchor` part of any target, including bare `#anchor` links; (c) backticked
strings that look like repository paths — containing `/` and matching a known extension or an
existing directory prefix (`docs/`, `pravaha-`, `plugins/`, `sdk/`, `examples/`, `console/`, `bin/`).
Resolve (a) and (c) against the repository; resolve (b) against the GitHub anchor slug of every
heading in the target file. Emit `file, line, class, target, resolves(y/n)`.
**Expected:** the extractor reports a non-zero count in each of the three classes, and its base-path
handling is proven by a link in `docs/adr/` resolving to a file in `docs/`.
**Vacuity:** seed one broken link, one broken anchor and one broken backticked path into `$QA` copies
and confirm exactly three new failures appear, one per class.
**Re-run:** any file moved or renamed; any heading text changed.

## DOCX-005 — the claim extractor: every sentence asserting that something is enforced
**Intent:** the most damaging defects this area has found are not wrong instructions but **false
claims of coverage**: `HANDOVER.md`'s `ExamplesTest` sentence and `SQL_SUPPORT.md`'s opening line.
Both stopped people checking. Find the whole class rather than the two known members.
**Falsifier:** the extractor missing either known member.
**Setup:** `$QA/extract/claims.py`.
**Steps:** extract every sentence in the corpus matching any of: `checked by a test`, `executed by`,
`enforced by`, `cannot .* rot`, `fails the build`, `asserted`, `guaranteed`, `generated from the
source`, `verified`, `is a test`, `\w+Test\b`, `so it cannot`, `the build fails`. Emit
`file, line, sentence, named-artefact`.
**Expected:** the two known members appear, plus every other sentence of the form. Each is assigned
an ID and becomes an input to Group G. Record the total — it is the number of promises this
documentation set makes about its own accuracy.
**Vacuity:** the two known sentences are the positive controls; a miss on either voids the extractor.
**Re-run:** any document edit — this is the extractor with the shortest half-life, because a
reassuring sentence is the cheapest thing to add.

---

## Group B — every command, executed literally

Thirteen cases, `DOCX-006`–`DOCX-018`. The standard is round 1's and is not negotiable: *executed
exactly as written, by someone who knows nothing*. A step needing knowledge the document does not
give is a defect in the document.

Each case here runs the commands DOCX-001 extracted for its file, in document order, in a clean
directory, and produces `$QA/report/<file>.tsv` with one row per command:
`line, command, exit-code, first-line-of-output, verdict`. The verdict vocabulary is fixed:
`RAN-AS-SHOWN`, `RAN-DIFFERENT-OUTPUT`, `FAILED`, `NEEDS-INVENTION` (the reader must supply something
the document did not give), `NOT-EXECUTABLE` (needs infrastructure the document declares).

## DOCX-006 — `docs/QUICKSTART.md` step 1: build, `PATH`, and the container alternative
**Intent:** the entry point. Everything below it is decoration if this does not work.
**Falsifier:** any of the four commands failing, or producing artefacts at paths other than the two
the document names.
**Setup:** the tree as built; `$QA/step1/` as the working directory; artefacts not rebuilt (the
shared brief forbids a rebuild mid-wave).
**Steps:** run the four step-1 commands verbatim: `./mvnw -q -DskipTests install`,
`export PATH="$PWD/bin:$PATH"`, `pravaha --help`, and the two-line `docker build` / `docker run`
alternative. Confirm `bin/pravaha` and `bin/pravaha-server` glob onto the jars the document names.
**Expected:** `pravaha --help` exits 0 and prints usage. The two artefact paths exist and the
launcher globs match them. `docker build` succeeds or is `NOT-EXECUTABLE` with the Dockerfile
reviewed statically against the printed command — `ENTRYPOINT`, `EXPOSE`, and whether
`--spring.profiles.active=dev` reaches the JVM.
**Vacuity:** `pravaha --help` exiting 0 is not enough: assert it prints at least the nine subcommand
names, because P-4 records that `--help` is parsed as a bare flag and six commands answer it with a
missing-option error.
**Re-run:** any change to `bin/`, `pom.xml` `<version>`, or the Dockerfile.

## DOCX-007 — QUICKSTART steps 2 and 3, character for character
**Intent:** the two commands round 1 and its Re-QA both found broken, re-run **mechanically** so the
verdict is a diffable row rather than a paragraph.
**Falsifier:** either command succeeding — which would be the fix, and would need the case to record
the new output against the document's printed output.
**Setup:** `$QA/step2/` containing only a copy of `examples/01-filter-and-project/transactions.csv`.
**Steps:** run step 2's three lines and step 3's command verbatim; `cat out.csv`.
**Expected:** as of the last pass: step 2 fails with `--out-schema is required` and its `--schema`
declares 2 columns for a 4-column CSV; step 3 fails identically, so the `PRV-2050` lesson the step
exists to teach is never seen. The expected *document* behaviour is `out.csv` containing exactly
`alice,500` / `dave,150` / `frank,1200`. Report both, as `FAILED` with the document's promise beside
the actual.
**Vacuity:** `examples/01-filter-and-project/README.md` prints a **working** form of the same query
(DOCX-014). Running both in the same session is what proves the failure is the quickstart's text and
not the engine's.
**Re-run:** any edit to QUICKSTART §2–3, or to `RunCommand`'s required options.

## DOCX-008 — QUICKSTART step 4: the server, the YAML block, and the registration
**Intent:** the step that carries the document's central claim — "with that file, the whole loop works
from the command line" — and the step with the most invention required of the reader.
**Falsifier:** the registration succeeding with the schema the document prints.
**Setup:** HTTP 18660, Flight 19660; `$QA/step4/` with nothing in it but what step 4 tells the reader
to create.
**Steps:** follow step 4 top to bottom, creating **only** what is explicitly instructed. Record every
point at which the reader must invent something: the file's name, its directory, the CSV's content,
the event-time column, the timestamp wire format. Then run the printed `pravaha register` and
`pravaha queries`.
**Expected:** the document prints a YAML block and then says "with that file", naming neither the
file nor the directory (`NEEDS-INVENTION`). The declared `txn` schema does not carry the column
`velocity.sql` groups on (`FAILED`). `pravaha.streams.<n>.event-time` is required for any window ever
to close and is documented nowhere (`NEEDS-INVENTION`, and silent — `ROWS IN` stays 0 with no log
line). A `TIMESTAMP` column in a delimited source must be epoch **nanoseconds** and nothing says so
(`NEEDS-INVENTION`, also silent). Count the inventions; the count is the case's headline.
**Vacuity:** a control configuration built by someone who already knows all four answers must
register, ingest and close a window in the same session — otherwise "the reader cannot get there" is
indistinguishable from "the engine cannot get there".
**Re-run:** any edit to QUICKSTART §4, `application.yaml`'s stream block, or `DelimitedCodec`.

## DOCX-009 — QUICKSTART steps 5, 6 and 8: query, subscribe, drop
**Intent:** the three commands a reader runs once the loop is up, and the flag syntax they copy.
**Falsifier:** any printed flag the CLI does not accept.
**Setup:** the node from DOCX-008 in its **control** configuration, so these three are not blocked by
step 4's defects; ports 18660/19660.
**Steps:** run step 5's `pravaha query --sql "… WHERE user_id = ?" --params u1`; step 6's two
`pravaha subscribe` forms with `--limit` added so the case terminates (record that the document shows
no way to terminate one); step 8's `pravaha drop --name user_volume`; then `pravaha queries`.
**Expected:** all accepted; `drop` exits 0 and the name disappears from the listing. Record two
things the document does not say: `subscribe` prints `SRVDBG` debug lines from the **server** on every
batch, and there is no `--limit` in the printed form, so a reader following step 6 has to Ctrl-C.
**Vacuity:** a read of the view before and after the drop; a `drop` that exits 0 while the view keeps
answering was a round-1 finding and a bare exit code would not catch it.
**Re-run:** any change to `ServerCommand`'s argument parsing or to QUICKSTART §5–6/§8.

## DOCX-010 — QUICKSTART step 7: the console, as printed
**Intent:** a second language, a second process and a second port — the place a reader is most likely
to be stranded, and the one with its own README two directories away.
**Falsifier:** the printed command sequence completing without the reader supplying anything the
document did not give.
**Setup:** `$QA/step7/`; ports from this area's range.
**Steps:** `export CONSOLE_PASSWORD=…`; `cd console`; `make install`; `make run`. Then request each
route the document's table lists, and check each keyboard shortcut it names against the console
source.
**Expected:** record where the printed command fails. Check whether `make install` needs a network
and whether the document says so. Every listed route must exist; every listed shortcut must be bound.
**Vacuity:** at least one route must answer, or "the other routes 404" means the console is not
running rather than that the table is wrong.
**Re-run:** any change to `console/Makefile`, `console/routes/`, or QUICKSTART §7.

## DOCX-011 — QUICKSTART's code snippets: Java and Python
**Intent:** copy-paste code in a quickstart is code somebody will paste. Every type, method and
signature must exist.
**Falsifier:** any named symbol absent from the SDK, or present with an incompatible signature.
**Setup:** the SDK sources; a scratch compile unit for the Java snippet.
**Steps:** **compile** the Java snippet against the built SDK jars rather than resolving its symbols
by eye — resolution by grep is what lets an arity change through. For Python, run
`cd sdk/python && make install` and then `python3 -c` importing every name the snippet uses.
**Expected:** the Java snippet compiles. Every Python name resolves. Record any import the snippet
needs and does not show.
**Vacuity:** compiling is the vacuity guard; a grep-based version of this case passed in round 1 and
would pass again on a snippet that no longer compiles.
**Re-run:** any SDK signature change, any edit to the snippets.

## DOCX-012 — `docs/USER_GUIDE.md`, every command
**Intent:** the document a user reads after the quickstart, and the one that prints
`pravaha explain --sql "…"` without the `--schema` the command requires.
**Falsifier:** any command that runs as printed but is recorded here as failing, or the reverse.
**Setup:** ports 18661/19661 for anything needing a node.
**Steps:** run every command DOCX-001 extracted from `USER_GUIDE.md`, in order, in a clean directory.
**Expected:** the TSV, with §7's `explain` expected to fail on a missing `--schema`. Any command that
needs a running node must be preceded in the document by an instruction to start one; if it is not,
the verdict is `NEEDS-INVENTION` even if it runs in this harness.
**Vacuity:** at least one command must run as shown, or the environment is wrong.
**Re-run:** any edit to `USER_GUIDE.md` or to the CLI's arguments.

## DOCX-013 — `docs/OPERATIONS.md` and `docs/SECURITY.md`, every command and every YAML block
**Intent:** the two documents an operator acts on. A wrong command here is a production action.
**Falsifier:** a YAML block that a server refuses to bind, or a command that names a path or endpoint
that does not exist.
**Setup:** ports 18662/19662; `$QA/ops/`.
**Steps:** run every extracted command. Separately, write **every** YAML block in the two documents
verbatim into a config file (supplying only values marked as placeholders) and start a node with each.
Record bind failures, unknown-property warnings, and any key that binds but has no reader (cross-check
DOCX-019).
**Expected:** `OPERATIONS.md`'s "Starting a node" block prints a `pravaha.flight.port` that contradicts
the shipped default and every other document — a reader who copies it gets a node their own CLI
cannot reach with the documented default URL. `SECURITY.md`'s "Setting it up" shows
`.authorizedBy(myPolicy, myAuditSink)`, which is the **embedded** API and has no server equivalent
(SECX-004). Both are recorded per-command rather than as prose.
**Vacuity:** a block that is known-good must bind in the same run.
**Re-run:** any edit to either document, or to `SecurityProperties`/`PravahaNode`'s bindings.

## DOCX-014 — `examples/01`, `examples/02`, `examples/03`, run verbatim
**Intent:** three examples whose READMEs quote exact output. They are also the control for DOCX-007.
**Falsifier:** any quoted output line not reproduced exactly.
**Setup:** repository root as the working directory, as the READMEs assume.
**Steps:** run all commands in the three READMEs verbatim (skipping the already-done `install`), and
compare stdout and the output files byte for byte against the quoted blocks.
**Expected:** `01` produces exactly `alice,500` / `dave,150` / `frank,1200` — and the arithmetic the
README relies on is checkable: of six rows, `bob` and `erin` are under the `amount > 100` threshold
and `carol` is `PENDING`, leaving three. `02` produces exactly `3,700` and its refusal quotes
`PRV-2050` with `Bound it with a window`. `03` produces its quoted five lines.
**Vacuity:** the byte comparison is the guard. A "contains" assertion would pass on a file with extra
rows, which is exactly how round 1's row-count assertion passed while 200,000 rows collapsed into 500.
**Re-run:** any change to the example data, the CLI's output formatting, or the planner.

## DOCX-015 — `examples/case-studies/`, walked as far as the documents allow
**Intent:** four or five worked systems (the count itself is disputed — DOCX-054(d)). A reader must be
able to get from `SETUP.md` to a running case study or be told plainly to stop.
**Falsifier:** a case study whose README implies it runs and whose prerequisites are neither
installed nor stated.
**Setup:** `$QA/cs/`.
**Steps:** read `SETUP.md` and one case-study README end to end as a naive reader; run every command
until one needs infrastructure; record the point and whether the document warned.
**Expected:** either the reader arrives, or the prerequisites are stated plainly enough that they know
to stop before typing anything. `NOT-EXECUTABLE` is an acceptable verdict **only** if the document
declared the dependency first.
**Vacuity:** none required — no state, no timing.
**Re-run:** any case study added, or `SETUP.md` edited.

## DOCX-016 — every documented HTTP endpoint answers
**Intent:** endpoints are named in prose across five documents. A 404 is a documented-but-nonexistent
instruction.
**Falsifier:** any documented path returning 404 on a node configured as its own document says.
**Setup:** a node on 18663/19663 configured exactly as the document naming each endpoint says to
configure it.
**Steps:** extract every path of the form `/api/...`, `/actuator/...`, `/status`, `/api/docs`,
`/swagger-ui/...` from the corpus with its source file and line; request each; record status and the
first 80 bytes.
**Expected:** the TSV. `/actuator/prometheus` is expected 404 — the registry dependency is absent
while `application.yaml` exposes the endpoint — and `OPERATIONS.md` documents it along with per-query
metric names. Every 404 is reported with the document and line that promised it.
**Vacuity:** `/api/v1/status` returning 200 in the same run.
**Re-run:** any controller or actuator exposure change; any document naming a new path.

## DOCX-017 — every documented CLI subcommand and flag exists
**Intent:** the pair audit: no document names a subcommand the CLI lacks, and `--help` documents every
flag the documents tell a reader to use.
**Falsifier:** either direction non-empty and unreported.
**Setup:** the dispatch table in `PravahaCli` and the `Args` usage of each command.
**Steps:** from DOCX-001's extraction, build the set of `(subcommand, flag)` pairs the documents use.
Compare against the dispatch table and each command's accepted options. Separately, run
`pravaha <cmd> --help` for all nine commands.
**Expected:** every documented subcommand exists. Every documented flag is accepted. And the reverse
direction, which is the finding: `--help` is parsed as a bare flag, so six commands answer it with a
missing-required-option error, `queries --help` **dials the network**, and `version --help` prints the
version — there is no way to discover a command's flags from the binary, so "the documentation is the
only reference" is load-bearing in a way nobody chose.
**Vacuity:** the known pair `register --sql` (used in QUICKSTART, absent from the help text) must
appear in the report, or the pairing is not working.
**Re-run:** any CLI argument change; any document adding an invocation.

## DOCX-018 — every command in every remaining document
**Intent:** the sweep. `README.md`, `docs/README.md`, `CONCEPTS.md`, `ARCHITECTURE.md`,
`TROUBLESHOOTING.md`, `HANDOVER.md`, the ADRs, the gate packs, `console/README.md`, the SDK READMEs,
`system_design.md`, `implementation_plan.md`.
**Falsifier:** the sweep's command count being lower than DOCX-001's total minus the counts of the
files covered by DOCX-006–017.
**Setup:** `$QA/sweep/`, ports 18664/19664.
**Steps:** run each extracted command; for those needing a node, start one per the document's own
instructions.
**Expected:** one TSV, and an arithmetic check: the row counts of DOCX-006…018 sum to DOCX-001's
total. That equation is the whole point of the group — it is what makes "every command" a claim
rather than a hope.
**Vacuity:** the sum check is itself the vacuity guard for the entire group.
**Re-run:** every document edit.

---

## Group C — configuration keys, both directions

Eight cases, `DOCX-019`–`DOCX-026`. Round 1's Re-QA found both directions **worse** than at the first
pass: two keys moved off the inert list and three moved onto the undocumented list, and the two that
arrived decide whether the engine's headline feature runs at all.

## DOCX-019 — documented and inert: keys a reader can set that nothing reads
**Intent:** a key in a document is an instruction. A key that binds nothing fails silently, which is
the worst kind — the operator believes the setting took.
**Falsifier:** a key on this list turning out to have a reader, or a key with no reader not appearing.
**Setup:** DOCX-002's two sets.
**Steps:** for every key in `keys-docs.txt` and not in `keys-code.txt`, prove inertness **by
experiment** and not by grep: start a node with the key set to a value whose effect would be
observable, and to a second, contradictory value, and show the behaviour is identical.
**Expected:** the list, each entry with its documenting file and line and its experiment. Expected
members: `pravaha.watermark.out-of-orderness` (`CONCEPTS.md:66`, `OPERATIONS.md:222`,
`application.yaml:167`, `StreamSchema`'s javadoc) — set it to `0s` and to `10m` over the same
out-of-order fixture and get the same number of windows; `pravaha.cluster.socket.peers`,
`.heartbeat.millis`, `.timeout.millis` (`OPERATIONS.md:104-106`); `pravaha.cluster.zookeeper.*`
(`OPERATIONS.md:113`); `pravaha.checkpoint.timeout`.
**Vacuity:** the **working** neighbour is the control: `pravaha.streams.<n>.out-of-orderness`, one
level down and one number apart in the tree, must produce **11 windows versus 6** on the same
fixture. Without that pair, "the same result twice" is indistinguishable from a fixture that does not
exercise lateness at all.
**Re-run:** any new `@Value` or `Configuration.get*`; any document naming a key.

## DOCX-020 — bound and documented nowhere: keys without which nothing works
**Intent:** the other direction, and the more damaging one — round 1 ranked
`pravaha.streams.<n>.event-time` as the single worst documentation defect in the repository, because
the failure is silent, permanent and unsearchable.
**Falsifier:** any key on this list being found in a user-facing document.
**Setup:** DOCX-002's two sets; the corpus minus `application.yaml`'s comments (a comment inside a jar
is not documentation, which is DOCX-022's separate question).
**Steps:** for each key in `keys-code.txt` and not in `keys-docs.txt`, record the consequence of not
knowing it, demonstrated: run the documented configuration without the key and capture what the user
sees.
**Expected:** the list with a demonstrated consequence each. `pravaha.streams.<n>.event-time` —
`RUNNING`, rows in, **nothing out, no error, no log line**. `pravaha.streams.<n>.out-of-orderness` —
the only working lateness control. `pravaha.security.audit`. `pravaha.checkpoint.timeout`. The
`filesystem` plugin's option keys (`path`, `schema`, `delimiter`, `event.time`) — documented nowhere
at all, and `path` and `schema` are required for any source binding to work.
**Vacuity:** the demonstration is the point. "Undocumented" is a grep result; "undocumented and the
failure is silence" is a finding, and only the run distinguishes them.
**Re-run:** as DOCX-019.

## DOCX-021 — the count is 38, and it is checkable
**Intent:** `TEST_PLAN.md` says 37 and corrects itself to 38 (36 YAML keys plus two system
properties). A count nobody can reproduce is a number in a document, which is the thing this area
exists to distrust.
**Falsifier:** the extractor's count differing from 38 without an enumerated explanation.
**Setup:** DOCX-002.
**Steps:** print `keys-code.txt` with its 38 lines, annotated `YAML` or `SYSPROP`, and reconcile
against `TEST_PLAN.md`'s figure and `DOC.md` Part 5's list.
**Expected:** 36 + 2 = 38, enumerated. Any difference is itself the finding and is reported as a
delta rather than a new number.
**Vacuity:** none required.
**Re-run:** any configuration surface change.

## DOCX-022 — `application.yaml`'s comments are the best documentation and reach no reader
**Intent:** the shipped `application.yaml` has unusually informative comments. They live inside a jar.
`OPERATIONS.md` is what an operator reads.
**Falsifier:** every key commented in `application.yaml` also appearing in a user-facing document with
comparable substance.
**Setup:** `application.yaml` and the user-facing subset of the corpus (README, QUICKSTART,
USER_GUIDE, OPERATIONS, SECURITY, TROUBLESHOOTING, CONCEPTS).
**Steps:** for each key in `application.yaml`, classify its documentation as `USER-DOC`,
`YAML-COMMENT-ONLY` or `NEITHER`, and for `YAML-COMMENT-ONLY` record whether the comment states
something an operator needs (a bound, a default, a consequence).
**Expected:** the three-way split with counts. Every `YAML-COMMENT-ONLY` entry whose comment states a
bound or a consequence is a documentation gap of known size.
**Vacuity:** none required.
**Re-run:** any `application.yaml` edit.

## DOCX-023 — no javadoc or code comment names a key that does not exist
**Intent:** the owner's stated failure mode: `pravaha.streams` was named in a javadoc before it
existed. A javadoc is documentation to the next engineer.
**Falsifier:** a key named in a comment that resolves to no reader.
**Setup:** every `src/main` tree.
**Steps:** grep for `pravaha\.` inside `//`, `/* */` and `/** */` in main sources; resolve each
against `keys-code.txt`.
**Expected:** the list. Known members to expect: `StreamSchema`'s javadoc naming
`pravaha.watermark.out-of-orderness`, and `SecurityProperties`' own javadoc naming **two values the
code rejects** — `policy: tenant` and `audit: log` (SECX-003 proves both are refused).
**Vacuity:** none required.
**Re-run:** any javadoc edit.

## DOCX-024 — every key's documented **bounds and defaults** are the code's
**Intent:** a key existing is the weak claim. The strong one is that its documented default, its
range and its units are right — `OPERATIONS.md` claims `idle-after`'s bounds are "both enforced".
**Falsifier:** any documented default, minimum, maximum or unit that the code does not implement.
**Setup:** a node on 18665/19665.
**Steps:** for each key with a documented default, start a node setting nothing and read the effective
value back (from the startup log or by observed behaviour). For each with documented bounds, set one
below the minimum, one above the maximum, and one absurd (`-1`, `0`, `100d`, `1ns`, `abc`) and record
whether the node refuses, clamps or accepts.
**Expected:** a table per key. `pravaha.watermark.idle-after` is documented as min 1s / max 10m —
verify both are refusals and not clamps. `pravaha.streams.<n>.out-of-orderness` has **no documented
bounds and was never tested against absurd values**; record what it does with `-1s`, `0s` and `9999d`.
A sub-millisecond interval truncating to zero and then being refused as "not positive" is the shape to
watch for.
**Vacuity:** a value inside the documented range must be accepted in the same run for every key, or
"refused" says nothing about the bound.
**Re-run:** any bound or default change.

## DOCX-025 — contradictory keys, and whether the documents warn
**Intent:** several key pairs cannot both be honoured. A document that shows one without mentioning
the other is setting a trap.
**Falsifier:** a contradictory pair that the node refuses at startup and that no document mentions —
or worse, one the node accepts silently.
**Setup:** a node on 18666/19666.
**Steps:** start with each pair and record the outcome and whether any document mentions it:
`security.policy=authenticated` + `security.authentication=none`;
`security.allow-anonymous=true` + `authentication=token`;
`flight.tls.key` set + `certificate` unset; `flight.enabled=false` + a documented Flight URL;
`cluster.mode=PARTITIONED` + `cluster.mechanism=single`; `checkpoint.enabled=false` + a document
telling operators to prune checkpoints by hand.
**Expected:** the first refuses at startup with `PRV-7002` and is documented **nowhere** — the refusal
is new and no document mentions it, so an operator hardening a `dev` node meets an error message that
appears in no searchable place. The second is silently dead (`allow-anonymous` is guarded on
`!authenticates()`). `PARTITIONED` + `single` **starts** and partitions nothing. Three documents tell
operators to prune checkpoints by hand while `PeriodicCheckpointer` already does it.
**Vacuity:** none required for the startup cases; for `PARTITIONED` the vacuity guard is that a
partition-observable behaviour must be measured, not just a successful start.
**Re-run:** any startup-validation change.

## DOCX-026 — the console's own configuration is documented in the console's own README
**Intent:** a reader who cannot sign in to the console has no recourse if the only instruction lives
in a document two directories away.
**Falsifier:** any setting a first run needs — above all the password gate — that is absent from
`console/README.md`.
**Setup:** `console/config/application.yaml` and the console's config loader.
**Steps:** enumerate every setting the console reads; classify each as documented in
`console/README.md`, only in `QUICKSTART.md` §7, or nowhere.
**Expected:** the three-way split. The password gate must be in the console's own README.
**Vacuity:** none required.
**Re-run:** any console configuration change.

---

## Group D — error codes

Seven cases, `DOCX-027`–`DOCX-033`, all driven by DOCX-003's TSV.

## DOCX-027 — the ten undocumented codes, each reached
**Intent:** `TROUBLESHOOTING.md` states that a code missing from its table does not exist in the
engine. Ten do. Reaching each one converts a table diff into ten reproducible user experiences.
**Falsifier:** any of the ten being unreachable — which would make it a declaration rather than a
documentation gap, and would change the fix.
**Setup:** a node on 18667/19667 plus the CLI and the SDKs.
**Steps:** for each of `PRV-1030`, `1031`, `1040`, `1041`, `1042`, `1043`, `5090`, `5091`, `5092`,
`6104`, find a user action that emits it and run it. Record the action, the message and where a reader
would look.
**Expected:** at least nine reach a user. `PRV-6104` is the sharpest: it is now the **first failure a
reader meets** after copying `QUICKSTART.md`'s TLS lines verbatim, and it is absent from a document
that claims completeness. `PRV-1043` is expected unreachable (no throw site) and is recorded on both
lists.
**Vacuity:** a documented code reached in the same run, so "reached" is a working method.
**Re-run:** any `ErrorCode` added, or `TROUBLESHOOTING.md` edited.

## DOCX-028 — the nine codes with no throw site
**Intent:** eight of the nine are **documented**, so the document describes failures the engine cannot
report. Two are routine operational events.
**Falsifier:** a throw site existing for any of them.
**Setup:** DOCX-003's TSV plus a targeted search for dynamic construction (a code built from a
variable would defeat the extractor).
**Steps:** for `1043`, `4002`, `4013`, `5012`, `5020`, `5053`, `5064`, `8007`, `9004`: confirm zero
throw sites, then provoke the condition each *describes* and record what the user actually gets.
**Expected:** `PRV-5053` (a vacuumed Delta file) surfaces as a generic read failure. `PRV-5064` (a
rotated feed file) surfaces as **silence**. `PRV-8007` is declared for recovery's unknown-owner branch
and that branch is unreachable, because `principalNamed` invents a principal for any non-blank id
(SECX-088). Each row: code, documented(y/n), condition provoked, what the user got instead.
**Vacuity:** the provocations must produce *something* — a generic error or measured silence — or the
condition was not reached and the row is void.
**Re-run:** any throw site added or removed.

## DOCX-029 — one code, one meaning
**Intent:** a code table is cheap to keep true; a *meaning* is where the rot is. `PRV-7002` now has
fifteen throw sites and four unrelated meanings, and the document's advice is wrong for seven of them.
**Falsifier:** any code whose throw sites fall into more than one category while the document gives
one cause and one remedy.
**Setup:** DOCX-003's throw-site column.
**Steps:** for every code with more than one throw site, classify each site (authorization denial,
startup configuration refusal, bad configuration value, SQL planning refusal, runtime failure, …) and
compare the set against the document's stated cause and advice.
**Expected:** `PRV-7002` — four meanings: an authorization denial, the startup refusal of an open
server, the policy/authentication contradiction, and bad configuration *values*; the documented advice
("ask for access; a new credential will not help") is wrong for seven of fifteen sites. `PRV-2002` —
three of five sites are startup configuration refusals wearing an SQL code. Report the whole list,
sorted by site count.
**Vacuity:** a single-site code checked in the same run, to show the classification distinguishes.
**Re-run:** any new throw site for an existing code — which is the change most likely to happen and
least likely to be noticed.

## DOCX-030 — the ranges table covers every range in use
**Intent:** three numbering schemes in three places: the `ErrorCode.Category` enum, the ranges table
in `TROUBLESHOOTING.md`, and that document's own detail table.
**Falsifier:** the three agreeing.
**Setup:** the enum and both tables.
**Steps:** extract all three and diff pairwise.
**Expected:** `Category.CLUSTER` is declared `(6000, 6999)` — the **Flight** range — so
`FlightErrors.UNSUPPORTED_TYPE.category()` returns `CLUSTER`. Real cluster codes are 9xxx and have no
category at all, and `PRV-9xxx` is absent from the ranges table while all seven 9xxx codes appear in
the detail table below it. Also: `ErrorCode.category()` **throws** for 8xxx and 9xxx from inside
`ApiExceptionHandler` — a documentation defect that is also a live 500.
**Vacuity:** the ranges that *do* agree are the control.
**Re-run:** any category or range change.

## DOCX-031 — every documented cause is the condition that raises it
**Intent:** the strong form of the code audit. Round 1 checked twelve codes this way; this checks
every documented code with a throw site.
**Falsifier:** a documented cause that is not the guard at the throw site.
**Setup:** DOCX-003's TSV.
**Steps:** for each of the 100 documented codes with at least one throw site, read the guard and
compare it with the documented cause. Emit `code, documented-cause, actual-guard, agrees(y/n)`.
**Expected:** the TSV. Known disagreements to expect: `PRV-2003` documented and never emitted; its
"Known streams" list shows views and omits every configured base stream.
**Vacuity:** none required — static comparison.
**Re-run:** any guard change.

## DOCX-032 — every documented remedy actually works
**Intent:** the strongest form, and the one with the worst failures. A refusal whose advice the engine
also refuses is worse than a refusal with no advice: it costs the reader a second attempt and their
trust.
**Falsifier:** any documented or in-message remedy that fails when followed.
**Setup:** a node on 18668/19668 and the CLI.
**Steps:** for every `TROUBLESHOOTING.md` entry with an actionable remedy, and for every **error
message** containing a suggestion (grep main sources for messages containing `Use `, `Set `, `Try `,
`Register `, `Narrow `, `CAST(`, `Bound it`), provoke the error and follow the advice verbatim.
**Expected:** the table. Known failures: `||`'s refusal recommends `CAST(… AS VARCHAR)`, which the
engine also refuses; the float-aggregate refusal's advice `SUM(CAST(price AS BIGINT))` **does** work
(record the working ones too, so the report is not a list of complaints); six error messages name
`arena.slab.size` as the remedy and **no such configuration key exists**.
**Vacuity:** at least one remedy must work, or the harness is not following advice correctly.
**Re-run:** any error-message text change.

## DOCX-033 — `docs.pravaha.io` and the help URL every error prints
**Intent:** every `ApiError` and every CLI failure prints
`https://docs.pravaha.io/errors/PRV-nnnn`. The domain is NXDOMAIN, and a test asserts on the URL.
**Falsifier:** the domain resolving, or no test asserting on it.
**Setup:** DNS lookup; `ExamplesTest`.
**Steps:** resolve `docs.pravaha.io`; grep the corpus and the test sources for the host; check whether
any document tells a reader the URL is not live.
**Expected:** NXDOMAIN. `ExamplesTest` asserts `docs.pravaha.io/errors/PRV-2002` appears in stderr —
so the build enforces a URL that cannot be visited. Every error message on the system points a user at
nothing, and the one mechanical check that exists checks the wrong half.
**Vacuity:** none required.
**Re-run:** any change to `ErrorCode`'s help URL construction, or the domain being registered.

---

## Group E — links, paths, anchors and structure

Five cases, `DOCX-034`–`DOCX-038`, driven by DOCX-004.

## DOCX-034 — every relative markdown link resolves
**Intent:** the cheapest rot to introduce and the cheapest to catch. `DocumentationFreshnessTest`
already checks some of this; the case establishes exactly which and closes the rest.
**Falsifier:** a broken link that `DocumentationFreshnessTest` does not fail on.
**Setup:** DOCX-004's class-(a) output.
**Steps:** resolve every non-HTTP link target; separately, read
`DocumentationFreshnessTest.documentsLinkToFilesThatExist` and determine its corpus and its link
pattern; compute the set of links the test does **not** cover.
**Expected:** zero broken links, and the uncovered set named. The test's corpus is a fixed list of
fourteen files; `docs/adr/*`, `examples/*`, `console/` and `sdk/` are outside it, so a broken link
there fails nothing.
**Vacuity:** the seeded broken link from DOCX-004 must appear.
**Re-run:** any file move; any link added.

## DOCX-035 — every in-document anchor resolves to a heading that exists
**Intent:** a wrong anchor lands the reader silently at the top of a long page — the failure with no
error at all. `QUICKSTART.md` links into `CONCEPTS.md#7-bounds-…`.
**Falsifier:** an anchor that does not match any heading slug in its target.
**Setup:** DOCX-004's class-(b) output.
**Steps:** compute the GitHub slug of every heading in every corpus file; resolve every anchor,
including cross-file and same-file ones.
**Expected:** all resolve. Report any that resolve only by coincidence of prefix.
**Vacuity:** the seeded broken anchor.
**Re-run:** any heading text change — which is the trigger people forget, because editing a heading
does not look like editing a link.

## DOCX-036 — every backticked repository path resolves
**Intent:** `` `plugins/pravaha-cluster-zookeeper` `` and `` `FileCheckpointStore.prune(keep)` `` are
instructions too, and no link checker sees them.
**Falsifier:** a dangling path or a named method that does not exist with that signature.
**Setup:** DOCX-004's class-(c) output, split into file paths and code identifiers.
**Steps:** resolve file paths against the tree. For identifiers of the form `Class.method(args)`,
resolve against the source and check the arity.
**Expected:** both lists empty, or every member reported with its file and line. The identifier half
is the one that rots invisibly: a method renamed in a refactor leaves the document naming a symbol
that no longer exists, and nothing fails.
**Vacuity:** the seeded broken path.
**Re-run:** any rename or move — including refactors that never touch a document.

## DOCX-037 — the ADR index and the ADR directory agree, and every citation resolves
**Intent:** 34 ADRs, one index, and citations scattered through the corpus and the source.
**Falsifier:** an ADR in the directory and not the index, an index row pointing at a missing file, or
an `ADR-nnn` citation anywhere naming a number that does not exist.
**Setup:** `docs/adr/`, `docs/adr/README.md`, and every `ADR-\d{3}` occurrence in the corpus **and in
`src/main` javadoc** — the source cites ADRs constantly and no test checks those.
**Steps:** three-way diff.
**Expected:** complete in all directions. Note the index is not in numeric order (018 and 021 sit
after 022), which is cosmetic but is what an automated check will flag — record it as cosmetic so a
later reader does not chase it.
**Vacuity:** none required.
**Re-run:** any ADR added or superseded.

## DOCX-038 — the document set's own map: `docs/README.md` and the module list
**Intent:** `docs/README.md` claims a freshness test enforces that "every module is described".
`README.md` lists "modules currently built".
**Falsifier:** a module in `pom.xml` that no document describes, or a described module that does not
exist.
**Setup:** `pom.xml`'s 31 `<module>` entries, `README.md`'s list, and
`DocumentationFreshnessTest.everyModuleIsDescribed`.
**Steps:** diff all three; read the test to establish what "described" means to it.
**Expected:** every built module described. Watch for modules that exist and are unreachable —
`pravaha-algebra` is referenced by no file outside itself, `pravaha-backfill`'s `ShadowDeployment` is
called from nothing in any `src/main`, `pravaha-embedded` has nine methods and cannot register or read
a query. A document describing these as capabilities is a documentation defect even though the module
list is literally correct.
**Vacuity:** the test must fail when a module is removed from the README in a `$QA` copy; if it does
not, the claim in `docs/README.md` is the finding.
**Re-run:** any module added or removed.

---

## Group F — ADRs against the code they decided

Eight cases, `DOCX-039`–`DOCX-046`. An ADR is not prose: it records a decision with behaviour, and the
behaviour either is in the tree or is not. This group is new — round 1 checked that ADRs were
*indexed*, never that they were *true*.

The method for every case in this group: take the ADR's **load-bearing sentence** — the one that
states what the system does as a result of the decision — and find the code that implements it, or
establish that none does.

## DOCX-039 — ADR-031: authorization at the Pravaha layer
**Intent:** the ADR the entire `SECX` area is about. Its claims are testable one by one.
**Falsifier:** any of the four claims holding on a configured server node.
**Setup:** the ADR text; `N-auth`-equivalent node on 18669/19669; `SECX`'s results.
**Steps:** extract each claim and mark it: (a) "enforced on every read"; (b) "never delegated to the
store"; (c) "a row filter is sound only if the view carries its columns"; (d) the row filter is ANDed
into the plan, not concatenated.
**Expected:** (b), (c) and (d) are **true and implemented** — say so, because an audit that reports
only failures is not an audit. (a) is **false on the HTTP surface** (no controller consults the
policy) and **unreachable by configuration** (no server node can be given a policy with rules). The
ADR should carry a status note; it carries none.
**Vacuity:** none required — the claims are checked against code and against `SECX`'s measured
results.
**Re-run:** any change to `ViewQuery`, `PravahaNode.securityPolicy()`, or the controllers.

## DOCX-040 — ADR-032: parameters are values, and its `LIKE` row is stale
**Intent:** a known, specific rot: ADR-032's table says "LIKE is not implemented at all; `LIKE 'u%'`
is refused too". `PredicateCompiler` implements `column LIKE 'literal'` and `SQL_SUPPORT.md` marks it
✅.
**Falsifier:** `LIKE 'E%'` being refused, which would make the ADR right and `SQL_SUPPORT.md` wrong.
**Setup:** the CLI and a two-column fixture.
**Steps:** run `SELECT region FROM sales WHERE region LIKE 'E%'`; then `… LIKE ?`; then
`… LIKE 'E%' ESCAPE '!'`; then `… LIKE status` (a column pattern). Compare each against ADR-032's
table row, `SQL_SUPPORT.md:97-98`, and the `PredicateCompiler` guards.
**Expected:** the literal form **works**. The parameterised form is the ADR's actual subject and is
refused. `ESCAPE` is refused `PRV-2021`. A column pattern is refused. So the ADR's row is right about
parameters and wrong about `LIKE` itself — one clause of one table cell, and it is the clause a reader
checking "can I use LIKE?" would stop at.
**Vacuity:** a query with no `LIKE` returning rows, so a refusal is about the operator.
**Re-run:** any `PredicateCompiler` change; any `SQL_SUPPORT.md` edit.

## DOCX-041 — ADR-029: the capability mechanism that was never built
**Intent:** ADR-029's load-bearing sentence: "the engine is told all of this through the capability
declaration, so a query that needs more is refused at registration rather than discovering it in
production." That mechanism does not exist.
**Falsifier:** any production code reading a connector's capability declaration to refuse a
registration.
**Setup:** the plugin SPI and `QueryRegistry`.
**Steps:** grep for readers of `emitsDeletes`, `replayableOffsets` and `DeliveryGuarantee.weakest`;
then register a query that needs deletes against the Aerospike plugin, which declares `EXACTLY_ONCE`,
and see whether it is refused or runs.
**Expected:** zero production readers; `DeliveryGuarantee.weakest` has no production caller; the
registration **succeeds** and the shortfall is discovered in production, which is the exact outcome
the ADR says the decision prevents. ADR-028 states the same rule ("a connector that declares
capabilities it has not got") and inherits the same gap.
**Vacuity:** the grep's negative result is confirmed by the positive control that the fields *exist*
and are set by the plugins — so the declaration is made and nobody reads it.
**Re-run:** any change to the connector SPI or to registration.

## DOCX-042 — ADR-020: a Spring Boot starter that is not in the build
**Intent:** an ADR that decides to ship an artefact, and the artefact is not a module.
**Falsifier:** `pravaha-spring-boot-starter`, `@PravahaListener` or `PravahaTemplate` existing
anywhere.
**Setup:** `pom.xml`'s module list and a repository-wide grep.
**Steps:** grep for all three names in every `src/`; check `pom.xml`; check whether any document tells
a reader to depend on the starter.
**Expected:** zero hits for `@PravahaListener` and `PravahaTemplate`; no such module. ADR-020 has no
status marker saying it is unbuilt, and ADR-019 (which it builds on) **is** implemented. An ADR set
where built and unbuilt decisions look identical is unusable as a description of the system, and that
is the finding — not the missing starter, which may be a deliberate deferral.
**Vacuity:** ADR-019's implementation found in the same run, so "grep finds nothing" is not the grep.
**Re-run:** any module added; any ADR status convention introduced.

## DOCX-043 — ADR-009 and ADR-034: Raft, and distribution deferred
**Intent:** two ADRs about the same territory, one deciding to use embedded Raft and one deciding to
defer distribution entirely. A reader needs to know which is current.
**Falsifier:** Ratis being a dependency, or ADR-009 carrying a supersession pointer.
**Setup:** every `pom.xml`; the two ADR texts; `OPERATIONS.md`'s cluster section.
**Steps:** grep for `ratis`/`raft` across the build and the sources; read both ADRs for a supersession
pointer; check what `OPERATIONS.md` tells an operator to configure.
**Expected:** no Ratis anywhere. ADR-034 defers distribution. ADR-009 has no pointer to it, and the
ADR README's own rule is that "a superseded ADR keeps its number and gains a pointer" — so the
convention exists and was not applied. `OPERATIONS.md` documents seven cluster keys with no readers,
so a reader following ADR-009 → `OPERATIONS.md` configures a mode that does nothing (S-3).
**Vacuity:** ADR-030's supersession of ADR-007 is present and correctly pointed — the control that the
convention is used when someone remembers.
**Re-run:** any clustering work; any ADR superseded.

## DOCX-044 — ADR-026 and ADR-011: three carriers, and the UI tap
**Intent:** "One subscription model behind gRPC, WebSocket and SSE; encode once, write N times."
Two of the three carriers do not exist.
**Falsifier:** any WebSocket or SSE subscription carrier in the tree.
**Setup:** repository-wide grep; the console's own transport.
**Steps:** grep for `websocket` and SSE handling in every `src/main`; determine how the console
actually receives changes; compare with ADR-011's "conflating tap".
**Expected:** no WebSocket implementation; the console reaches the engine through the SDK (ADR-024),
which is gRPC. So the ADR describes a three-carrier abstraction with one carrier. Check whether the
"conflating tap" of ADR-011 exists as code, since it is the mechanism that is supposed to keep the UI
out of the data path.
**Vacuity:** the gRPC carrier found in the same run.
**Re-run:** any new subscription transport.

## DOCX-045 — ADR-013, ADR-025 and ADR-008: three decisions the engine's own behaviour contradicts
**Intent:** the three ADRs whose load-bearing sentences are contradicted by measured behaviour rather
than by a missing module. These are the expensive kind, because the ADR reads as true.
**Falsifier:** any of the three matching the measured behaviour.
**Setup:** `FINDINGS.md`'s measured results plus a direct re-check of each.
**Steps:** (a) ADR-013 — "Z-sets + DBSP-derived incremental operators": apply a weight of `0` and a
partial retraction (`+2` then `−1`) to a served view and record the result; (b) ADR-025 — "sharing is
by canonical fingerprint **including security predicates**": register the same SQL with `--keys 1` and
with `--keys 0,1` and compare fingerprints; (c) ADR-008 — "aligned checkpoints; exactly-once state":
write a checkpoint for a stateful query, restart, and read the view.
**Expected:** (a) the net-zero row **stands** and the partial retraction **removes** the key —
`ServedView.applyValues` is last-write-wins, so the surface that serves the answers does not implement
the algebra the ADR names; `pravaha-algebra` gets both right, so the ADR is true of a module nothing
calls. (b) the two fingerprints are **equal**: key columns are not in the fingerprint, so the ADR's
"canonical" is not canonical over the thing that changes the output. The ADR's mention of security
predicates *is* implemented (SECX-071), so the sentence is half true, which is the worst state for a
sentence to be in. (c) the view returns **0 rows**: `latest()` and `restore()` are called from nowhere
outside tests.
**Vacuity:** each has a control — (a) the same operations against `pravaha-algebra` directly; (b) two
registrations differing in SQL, which must produce different fingerprints; (c) the checkpoint file's
byte count, to show state was written before the restart lost it.
**Re-run:** any change to `ServedView`, `QueryFingerprint` or the checkpoint path.

## DOCX-046 — the ADR sweep: a status column for all 34
**Intent:** the group's output. Every ADR gets one of four statuses so that the set becomes usable as
a description of the system.
**Falsifier:** an ADR whose status cannot be determined from the tree — which means the decision was
recorded without a testable consequence, and that is a finding about the ADR.
**Setup:** all 34 ADRs; the results of DOCX-039–045.
**Steps:** for each ADR, extract the load-bearing sentence and assign:
`IMPLEMENTED` / `PARTIAL` / `DECIDED-NOT-BUILT` / `CONTRADICTED-BY-CODE`, with one line of evidence.
**Expected:** the table, 34 rows. Expected members of the last two categories from this group's work:
ADR-005 (codegen unreachable from a server — `AdaptiveStage` and `StageUpgradeService` are called from
nothing in any `src/main`), ADR-009, ADR-016 (`ShadowDeployment` exists in `pravaha-backfill` and is
reachable from nothing), ADR-020, ADR-026, ADR-029, and ADR-013/025/008 from DOCX-045. The **count**
of non-`IMPLEMENTED` rows is the number to report: it is how far the recorded architecture has drifted
from the built one.
**Vacuity:** at least one `IMPLEMENTED` row with real evidence — ADR-031's row-filter soundness rule,
ADR-030's Flight SQL protocol, ADR-003's binary rows — or the classifier is just marking everything
broken.
**Re-run:** any ADR added; any of the named mechanisms built or removed.

---

## Group G — every claim that something is enforced by a test

Six cases, `DOCX-047`–`DOCX-052`, driven by DOCX-005. This is the class of defect that stops people
checking, which makes it worse than the thing it conceals.

## DOCX-047 — `HANDOVER.md`: "every command in QUICKSTART is executed by `ExamplesTest`"
**Intent:** the owner's stated safety net. If it is not there, nobody is looking.
**Falsifier:** `ExamplesTest` reading `docs/QUICKSTART.md`.
**Setup:** `pravaha-it/src/test/java/.../ExamplesTest.java`; `HANDOVER.md:57-58`.
**Steps:** grep `ExamplesTest` for `QUICKSTART`; list every file it opens; list every command it runs
and match each against QUICKSTART's extracted command list from DOCX-001.
**Expected:** `ExamplesTest` **never opens `QUICKSTART.md`**. It opens the three
`examples/*/README.md` files. Four of its methods are *named* for the quickstart
(`theQuickstartsValidateCommandWorksAndReportsItsFields`,
`theQuickstartsExplainCommandShowsBothPlans`) and hard-code their command lines, so they assert
against a copy of the document rather than against the document — which is precisely why DOCX-007's
two commands could rot while the build stayed green. Report the coverage as a fraction: QUICKSTART
commands executed by the test **from the file**, `0 / N`.
**Vacuity:** the three example READMEs *are* read, which proves the test is capable of reading a
document and that the absence is specific.
**Re-run:** any edit to `ExamplesTest` or to `HANDOVER.md`'s claim.

## DOCX-048 — `SQL_SUPPORT.md`: "every construct below is checked by a test"
**Intent:** 77 constructs, one named test, and the claim is the document's opening line.
**Falsifier:** `SqlSupportMatrixTest` executing a row and comparing a value.
**Setup:** `SqlSupportMatrixTest`; `SQL_SUPPORT.md:6-7`.
**Steps:** count the `Case` entries in the test and the rows in the document's tables; diff the two
sets by construct; then read what the test asserts per case.
**Expected:** the test **plans** each statement and asserts `"OK"` or a PRV code. It **runs no row and
compares no value**, so none of the wrong-answer defects — windowed `AVG` returning the SUM,
`COUNT(col)` counting nulls, `ROUND`'s half-ulp error, `COUNT(DISTINCT <string>)` counting byte
lengths — would fail this build. Report: constructs in the document but not the test; in the test but
not the document; and the fraction of the document's ✅ rows whose *answer* is checked anywhere
(expected: 0).
**Vacuity:** the test does fail when a plan-time refusal changes — introduce a `$QA`-local change to a
refusal code and confirm the test goes red. That establishes it is a real test of the thing it
actually checks, which is what makes the gap precise rather than dismissive.
**Re-run:** any construct added to either side.

## DOCX-049 — `examples/README.md`: "the commands in every README here are executed by `ExamplesTest`"
**Intent:** the same claim, one directory down, and this one is closer to true.
**Falsifier:** a command in an example README that the test does not execute.
**Setup:** the three example READMEs and `ExamplesTest`.
**Steps:** diff the extracted command list per README against the test's executed commands.
**Expected:** the test executes the `run` commands and asserts the quoted outputs, and it asserts the
README *contains* the quoted string — a genuine two-way check, and the strongest documentation
enforcement in the repository. Report what it does **not** cover: the `explain` commands, and
`examples/03`'s classpath incantation.
**Vacuity:** the passing half is what makes the gap a gap.
**Re-run:** any example README edit.

## DOCX-050 — `DocumentationFreshnessTest`: what it actually enforces
**Intent:** the repository's general anti-rot mechanism. Establish its exact perimeter, because
everything outside it rots silently and nobody knows where the edge is.
**Falsifier:** the test's corpus being the whole document set.
**Setup:** `DocumentationFreshnessTest`.
**Steps:** enumerate its assertions, its fourteen-file corpus, and its self-check
(`mavenModules().hasSizeGreaterThan(10)`, `corpus().length() > 50_000`). For each assertion, seed a
violation in a `$QA` copy and confirm it fails.
**Expected:** it enforces: every module described; no phantom module; every gate recorded; the README's
wave matches the newest gate; links resolve within its corpus; `QUICKSTART.md` exists and contains
`./mvnw` and `pravaha`; `examples/` is not empty. **Outside its perimeter:** the ADRs, the examples'
READMEs, the console, the SDKs, every command, every config key, every error code, every anchor, and
the *content* of QUICKSTART beyond two substrings. Report the perimeter as a diagram or a list; it is
the most useful single artefact this group produces, because the next person adding a document needs
to know whether anything will check it.
**Vacuity:** its own self-check (`> 10` modules, `> 50_000` characters) guards against a path bug
making every assertion vacuous — note that as a good pattern and confirm it fires by pointing the test
at an empty directory.
**Re-run:** any change to the test or to its corpus list.

## DOCX-051 — the remaining coverage claims, each resolved
**Intent:** DOCX-005 extracts the whole class. Here every remaining member is resolved to
`TRUE`, `PARTIAL` or `FALSE`.
**Falsifier:** a claim that cannot be resolved — meaning it names no artefact, which makes it
unfalsifiable and therefore worse than a false one.
**Setup:** DOCX-005's output minus the members covered by DOCX-047–050.
**Steps:** for each, locate the named artefact and read what it asserts.
**Expected:** the table. Expected members: `TROUBLESHOOTING.md`'s "generated from the source, not from
memory" (`FALSE` — ten codes missing); its "if a code is missing here it does not exist in the engine"
(`FALSE`); `ErrorCodeUniquenessTest` existing and failing on a duplicate (`TRUE` — record it, it is
one of the few that holds); `docs/README.md`'s "every module is described" (`TRUE`, within the
perimeter of DOCX-050).
**Vacuity:** the `TRUE` rows are what keep the report honest.
**Re-run:** any document edit.

## DOCX-052 — the enforcement gap, stated as a number
**Intent:** the group's summary, expressed so it can be tracked across rounds.
**Falsifier:** the numerator being anything other than what DOCX-047–051 measured.
**Setup:** the outputs of this group and of Groups B, C and D.
**Steps:** compute four fractions: documented **commands** executed by a build-time test; documented
**config keys** asserted by any test; documented **error codes** asserted by any test; documented
**SQL constructs** whose *answer* is asserted.
**Expected:** four fractions, each with its numerator and denominator. All four are expected to be
very low, and the point is not the embarrassment but the tracking: this is the one number in the area
that a later round can compare against without re-reading anything. Propose, in one line each, the
cheapest test that would raise each fraction — for commands, a test that *extracts* from
`QUICKSTART.md` rather than hard-coding a copy of it, which is the single change that would have
prevented DOCX-007.
**Vacuity:** none required.
**Re-run:** every round.

---

## Group H — the README banner and cross-document consistency

Four cases, `DOCX-053`–`DOCX-056`.

## DOCX-053 — the README's evaluation banner, sentence by sentence
**Intent:** the first screen an evaluator reads, inside a box labelled "Read this before evaluating",
describing the product's central capability as absent. Round 1 called it the highest-leverage single
edit available in the repository; it is still there.
**Falsifier:** any of the three claims being true of the current build.
**Setup:** `README.md:12-16`; `docs/HANDOVER.md:394`; a node on 18670/19670 with a configured source.
**Steps:** test each claim by experiment. (a) "nothing generates watermarks" — start a node and grep
the log for the watermark line and `registry.generatingWatermarks`; (b) "over an unbounded stream, no
window would close" — run a windowed query over a source appended to continuously and count closed
windows with hand-computed sums; (c) "the `pravaha-server` process has no ingestion path at all: a
query registered against it never receives a row" — register a query against a configured source and
read `ROWS IN`.
**Expected:** (a) false — the node logs `watermarks: idle-after=…, tick=…`. (b) false — windows close;
assert at least two with their sums computed by hand. (c) false — `ROWS IN` is non-zero and the view
answers. Three false statements in five lines, and the same claim repeated in `HANDOVER.md`, whose
purpose is to tell the next session what is true.
**Vacuity:** a query over a source with **no** matching rows must show `ROWS IN = 0` in the same
session, so a non-zero count is ingestion and not a default.
**Re-run:** any README edit; any watermark or ingestion change. This case should be re-run **first**
in any later round, because it is the cheapest fix and the most expensive rot.

## DOCX-054 — one question, one answer, across every document that answers it
**Intent:** a reader has no way to tell which half of a contradiction is true. Each contradiction is
a defect in *both* documents until one is corrected.
**Falsifier:** a question below on which every document already agrees.
**Setup:** the corpus; a node on 18671/19671 to settle each empirically.
**Steps:** for each question, list every document that answers it, with file and line, then settle it
by experiment and mark each document `CURRENT` or `STALE`:
(a) does the server ingest? (b) is there a metrics endpoint, and what are the metric names?
(c) does a console exist? (d) how many case studies are there? (e) what is the default Flight port?
(f) does windowing work? (g) is `COUNT(DISTINCT)` supported? (h) do checkpoints restore?
**Expected:** one answer per question and a `CURRENT`/`STALE` mark per document. Known: metrics are
documented **four** contradictory ways, one document admitting in prose that two of its own sections
disagree; `OPERATIONS.md` prints a Flight port that contradicts every other surface; `README.md`'s
status line says "no UI" beside a full console section.
**Vacuity:** each question must be settled by a run, not by majority vote among documents — that is
the difference between this case and a consistency checker.
**Re-run:** any document edit touching one of the eight questions.

## DOCX-055 — forward-looking statements that have been overtaken
**Intent:** "arrives in Wave 4" in a document about a feature that shipped is a specific and
correctable kind of rot, and it is invisible to every mechanical check because nothing is broken.
**Falsifier:** a wave or roadmap claim matching the current state.
**Setup:** the corpus and `docs/gates/`.
**Steps:** extract every sentence matching `Wave \d`, `arrives in`, `not yet`, `will be`, `is planned`,
`coming in`, `deferred`, `unsolved`, `not built`, with file and line; classify each as `STILL-TRUE`,
`SHIPPED-SINCE` or `ABANDONED`.
**Expected:** the table. Known members: `examples/02-aggregate/README.md` closing with "Windowing gives
the bound, and arrives in Wave 4" while the quickstart uses `TUMBLE`; `QUICKSTART.md` putting the
metrics endpoint in Wave 9; `README.md`'s "What is not built" table, which round 1 found inaccurate in
both directions.
**Vacuity:** at least one `STILL-TRUE` member, or the classifier is marking everything.
**Re-run:** any wave completed; any gate pack added.

## DOCX-056 — `system_design.md` and `implementation_plan.md`: aspirational documents linked as specifications
**Intent:** 3,959 and 996 lines, linked from the README as "the full specification", and unchanged
since before most of this engine existed. A reader cannot tell which parts describe the build.
**Falsifier:** either document carrying a marker distinguishing what is built from what is designed.
**Setup:** both documents; the README's link text; `DocumentationFreshnessTest`'s corpus.
**Steps:** check for a status marker or a dated header; sample twenty specific behavioural claims
across the two documents and classify each `BUILT` / `PARTIAL` / `NOT-BUILT`; check whether either is
in the freshness test's corpus (both are) and what that test asserts about them (that they exist and
link correctly, nothing about content).
**Expected:** no marker. The sample's classification, with the proportion `NOT-BUILT`. Known:
`system_design.md` names `mode: HA`, a value the code rejects. The recommendation to record is a
one-line header on each, because 4,955 lines cannot be audited per round and the honest fix is to
label them rather than to correct them.
**Vacuity:** at least one `BUILT` claim in the sample.
**Re-run:** any edit to either document; any round where the sample is re-drawn (record the twenty
claims so a later round samples the *same* twenty and the proportion is comparable).

---

## Group I — the onboarding walk

Four cases, `DOCX-057`–`DOCX-060`. The brief's central question, and the only part of this area that
cannot be scripted: it needs a reader who invents nothing.

## DOCX-057 — clone to a running continuous query, typing only what is written
**Intent:** the walk. Following documents in the order they point at each other, typing only what is
printed, inventing nothing.
**Falsifier:** the walk completing with zero inventions — which would close the area's most important
finding.
**Setup:** a clean clone into `$QA/walk/`, ports 18672/19672. The walker records every keystroke and
every moment of doubt.
**Steps:** README → `docs/QUICKSTART.md` → the first question answered. At each step record:
`step, document, line, command, outcome, invention-required(y/n), what-had-to-be-invented`.
**Expected:** the log. As of the last pass the walker must invent, in order: the config file's name
and location (§4), the CSV's contents and its timestamp wire format, the `event-time` key, and a
schema that matches the query. Report the **first** invention's position, because an early one stops
everybody — the rest are only reachable by a reader who already got past it.
**Vacuity:** a second walker who *does* know all four answers must arrive at a running query with
correct hand-computed sums in the same session. Without that control, "the reader cannot get there" is
indistinguishable from "the engine does not work", and the two need different fixes.
**Re-run:** any edit to README or QUICKSTART; any change to the required configuration surface.

## DOCX-058 — the same walk, from the README's other entry points
**Intent:** the README offers more than one door — the container, the embedded snippet, the console,
`docs/USER_GUIDE.md`. A reader picks one.
**Falsifier:** all four doors leading to a working query.
**Setup:** `$QA/walk2/`, four independent attempts.
**Steps:** walk each door to a running query or to a stop; record the stop.
**Expected:** four verdicts. The embedded door is expected to stop early: `pravaha-embedded` has nine
methods and cannot register or read a query, so a reader following ADR-019's "plain-Java
`PravahaEngine` seam" arrives at an object that starts and stops and does nothing else.
**Vacuity:** at least one door must work, or this is a build problem.
**Re-run:** any README restructuring.

## DOCX-059 — what an operator needs, and whether they can find it
**Intent:** completeness rather than accuracy. Eight operational questions, each with a consequence
for not having an answer.
**Falsifier:** all eight having a findable, actionable answer.
**Setup:** the corpus; search as an operator would (repository grep plus the documents' own tables of
contents).
**Steps:** for each of: security configuration; binding a source; checkpoint and journal operations;
upgrade; backup; monitoring; capacity planning; what to do when a query fails — record whether an
answer exists, where, and whether it is actionable (names a command or a key).
**Expected:** per-question verdicts with the operational consequence of each gap. Known: three
documents tell operators to prune checkpoints by hand while `PeriodicCheckpointer` already does it;
monitoring has four contradictory answers and a 404; "what to do when a query fails" is
`TROUBLESHOOTING.md`, whose code table is missing ten codes including the one a TLS reader meets
first; and there is no answer at all for "a query reports `RUNNING` and its lane is dead", which is
the failure mode ten surfaces misreport (PERF-041).
**Vacuity:** at least one question fully answered, or the search method is wrong.
**Re-run:** any operational document edit.

## DOCX-060 — the audit's own re-run instructions
**Intent:** this area's deliverable is a method, and a method that only its author can run has not
been delivered. This case is the handover.
**Falsifier:** a later session being unable to re-run the area from this file and `$QA/extract/` alone.
**Setup:** everything this area produced.
**Steps:** write `$QA/README.md` (in the scratch directory, **not** in the repository) containing: the
five extractors and how to invoke them; the fixed corpus command; the report file layout; the
denominators from DOCX-001, DOCX-003 and DOCX-021 so a later run can detect that the corpus changed;
and the ordered list of which cases must be re-run for which kind of change, derived from every
**Re-run** line in this file. Then have a second person — or a fresh session — execute three cases
from it cold and confirm the outputs match.
**Expected:** three cold re-runs producing byte-identical reports for unchanged inputs. Any case that
cannot be re-run cold is rewritten here until it can.
**Vacuity:** the byte-identical requirement is the guard: a report that differs run to run on unchanged
inputs is not an audit instrument, and this case is the only place that would be noticed.
**Re-run:** every round, first.

---

## Coverage note

**60 cases, the budget, and it is the right size for this shape.** Round 1's 50 cases were one per
document-and-question; this file's 60 are one per *audit*, and each audit covers a class. The
difference is deliberate and is the whole brief: DOCX-018's arithmetic check covers every command in
the corpus in one case, where round 1 would have needed one case per document and would still have
missed whatever it did not think to look at.

Where this area is still thin, named so it is a choice:

1. **`system_design.md` and `implementation_plan.md` are sampled, not audited.** 4,955 lines against a
   60-case budget is not a trade worth making; DOCX-056 samples twenty claims and records them so the
   sample is comparable across rounds. If the owner wants them audited, that is an area of its own and
   roughly 80 cases.
2. **The console's documentation is two cases** (DOCX-010, DOCX-026). The console is a second product
   surface with its own README, its own configuration and its own six route modules; a fair audit is
   ten cases and belongs beside `SDKX`'s console coverage rather than here.
3. **Translation of the corpus into a link/anchor checker that runs in CI is proposed and not built.**
   DOCX-060 hands over the extractors; wiring them into the build is a remediation, not a QA case, and
   this file deliberately does not do remediation.
4. **The gate packs** (`docs/gates/wave-*`) are checked for existence by
   `DocumentationFreshnessTest` and for content by nobody, here included. Five packs recording why
   waves merged — including one that merged without a passing performance gate — are exactly the
   documents a later reader will trust most.

**Ordering note for the execution wave.** Group A first, always: every other group consumes its
output, and an extractor that silently sees nothing turns this whole area into a clean bill. Then
Group B (it needs the most server time), then C and D in parallel with E and F (static), then G, H and
I. **DOCX-053 should be run first of all** regardless of ordering, because it is one command, it takes
two minutes, and it is the finding with the widest blast radius in the repository.
