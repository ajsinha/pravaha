# DOC — Documentation QA cases

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Area: **documentation — completeness, accuracy and user-friendliness, A to Z.**

The standard applied throughout: *every instruction in the documentation must actually work,
executed exactly as written, by someone who knows nothing.* A step that needs knowledge the document
does not give is a defect, not a reader failure. A document that contradicts another document is a
defect in both until one is corrected, because a reader has no way to tell which half is true.

Ports reserved for this area: HTTP 18500–18519, Flight 19500–19519.
Scratch: `/tmp/.../scratchpad/qa-doc`.

---

## Group A — `docs/QUICKSTART.md`, executed literally

## DOC-001 — Prerequisites section is sufficient for a naive reader
**Intent:** The "Before you start" table is the contract with someone who has nothing installed. If
it omits a prerequisite that a later step needs, the reader discovers it as a crash.
**Setup:** Read `docs/QUICKSTART.md` §"Before you start" and enumerate every tool any later step
invokes (`java`, `./mvnw`, `python3`, `docker`, `make`, `git`).
**Steps:** Cross-check the table against the full set of invoked tools.
**Expected:** Every tool invoked anywhere in the document appears in the prerequisite table, or is
introduced with its own install note at the point of use.

## DOC-002 — `./mvnw -q -DskipTests install` produces exactly the two artefacts named
**Intent:** Step 1 promises two runnable things at specific paths. A new user checks for those paths.
**Setup:** Artefacts are already built in this tree (do not re-run `install`, per the shared brief).
**Steps:** Verify `pravaha-cli/target/pravaha-cli-<version>-cli.jar` and
`pravaha-server/target/pravaha-server-<version>-app.jar` exist and are the files `bin/pravaha` and
`bin/pravaha-server` resolve to.
**Expected:** Both paths exist; the launcher glob patterns match them.

## DOC-003 — `export PATH="$PWD/bin:$PATH"; pravaha --help` works from the repository root
**Intent:** Every subsequent example types `pravaha`. If this does not work, nothing below it does.
**Steps:** From the repository root, run exactly the two lines in step 1.
**Expected:** `pravaha --help` prints usage and exits 0.

## DOC-004 — The container build and run in step 1 work as written
**Intent:** Added this week. Offered as the alternative for a reader with no JDK, which is exactly
the reader least able to diagnose it.
**Steps:** `docker build -t pravaha:local .` then
`docker run --rm -p 18080:18080 -p 19090:19090 pravaha:local --spring.profiles.active=dev`
(remapped to this area's assigned ports for the run).
**Expected:** The image builds; the container starts and serves Flight and HTTP. If docker is not
available in this environment, the case is BLOCKED and the Dockerfile is reviewed statically against
the documented command instead — `ENTRYPOINT`, `EXPOSE`, the argument form, and whether
`--spring.profiles.active=dev` reaches the JVM.

## DOC-005 — Step 2 "Run a query with no server at all", typed exactly, in a clean directory
**Intent:** The first command a new user runs. The highest-leverage line in the whole documentation
set. It must work character for character.
**Setup:** A clean scratch directory containing only a copy of
`examples/01-filter-and-project/transactions.csv`.
**Steps:** Run the three lines of step 2 verbatim, then `cat out.csv`.
**Expected:** Exit 0; `out.csv` contains exactly `alice,500` / `dave,150` / `frank,1200` as the
document shows.

## DOC-006 — Step 3 "a query the engine refuses" produces the quoted `PRV-2050`
**Intent:** The document teaches a refusal as a feature. If the command fails for an unrelated
reason, the lesson lands as "the tool is broken".
**Steps:** Run step 3 verbatim.
**Expected:** Exit non-zero with a `PRV-2050` message whose substance matches the quoted text —
i.e. the failure is the unbounded-`GROUP BY` refusal and not a usage error.

## DOC-007 — Step 4 `pravaha-server --spring.profiles.active=dev` starts and `pravaha queries` answers
**Intent:** The `dev` profile is new this week and is the documented escape hatch from the
refuse-to-start-open behaviour. It must actually start a usable server.
**Setup:** Assigned ports only (HTTP 18500, Flight 19500) via explicit overrides.
**Steps:** Start the server with the `dev` profile; run `pravaha queries --url grpc://localhost:19500`.
**Expected:** Server starts; `queries` returns an empty list rather than an error. Server stopped by
recorded PID afterwards.

## DOC-008 — The `pravaha.security` YAML block in step 4 is accepted by the server
**Intent:** It is presented as the thing to do "for anything beyond a local first run". A reader
will paste it. Every key in it must exist and bind.
**Steps:** Write the block verbatim into a config file (supplying a token value), start the server
with it, and attempt an unauthenticated and an authenticated call.
**Expected:** The server starts; unauthenticated calls are refused (`PRV-7001`); the token is
accepted. No "unknown property" or bind failure. The `flight.tls` sub-block binds (paths may not
exist; the key names must).

## DOC-009 — `pravaha.streams` + `pravaha.sources` deliver rows to a registered query
**Intent:** The central new claim of the rewrite: "With that file, the whole loop works from the
command line." The rest of the document is decoration if this is false. It is also the claim the QA
brief says was a release blocker, so it is the one most likely to be half-true.
**Setup:** A config file containing the `streams` and `sources` blocks exactly as printed, pointed at
a CSV this area owns; assigned ports.
**Steps:** Start the server with that file; `pravaha register`; `pravaha query` the resulting view.
**Expected:** Rows registered by the source reach the view and come back from `query`. Proven
non-vacuous by changing the source data and observing the answer change — an empty result would
otherwise "pass" a badly written assertion.

## DOC-010 — Step 4 tells the reader to create the configuration file it then uses
**Intent:** The document prints a YAML block, then says "With that file" and runs
`--spring.config.additional-location=file:./application.yaml`. A reader who knows nothing has no
file, no filename and no directory.
**Steps:** Follow step 4 top to bottom as written, creating nothing that is not explicitly instructed.
**Expected:** The document names the file to create, where to create it, and what goes in it; and it
does not leave a previously started server occupying the ports the second one needs.

## DOC-011 — The schema in the `streams` block supports the `velocity.sql` the same step registers
**Intent:** Step 4 declares `txn` with four columns and then registers a query grouping on
`t.event_time`. If the declared schema has no such column, the document contradicts itself inside one
section.
**Steps:** Compare the declared schema with the columns `velocity.sql` names; then attempt the
registration against a server configured with that schema.
**Expected:** The registration succeeds. If it cannot, the document must declare the column it needs.

## DOC-012 — `pravaha queries` output matches the columns the document prints
**Intent:** The document shows `NAME STATE FINGERPRINT ROWS IN` and a sample row. A reader uses it to
confirm success.
**Steps:** Register a query and run `pravaha queries`.
**Expected:** The real output has the same columns in the same order.

## DOC-013 — Step 5 `pravaha query --sql "… WHERE user_id = ?" --params u1` works as printed
**Intent:** Parameter binding is a headline claim (ADR-032). The printed invocation is the only
example most readers will copy.
**Steps:** Run step 5 verbatim against the server from step 4.
**Expected:** Exits 0 and prints rows (or an empty result set, not an error).

## DOC-014 — Step 6 `pravaha subscribe --view … [--filter …]` works as printed
**Intent:** Subscription is the product's differentiator; the flag syntax must be right.
**Steps:** Run both printed forms, with a bounded `--limit` so the case terminates.
**Expected:** Both are accepted. The filtered form is accepted and does not error on a column the
view has.

## DOC-015 — Step 7, the console, start to finish
**Intent:** The brief asks specifically whether the console is documented and whether the
documentation works. It is a second language, a second process and a second port — the place a reader
is most likely to be stranded.
**Steps:** `export CONSOLE_PASSWORD=…`; `cd console`; `make install`; `make run`. Then check every
route the document's table lists.
**Expected:** Install succeeds offline or the document says it needs a network; the console serves;
every listed route exists in the code. The keyboard shortcuts listed exist.

## DOC-016 — Step 8 `pravaha drop --name user_volume`
**Steps:** Run it against the registered query.
**Expected:** Exits 0 and the query disappears from `pravaha queries`.

## DOC-017 — The Java snippet under "From a program" names real API
**Intent:** Copy-paste code in the quickstart is code somebody will paste. Every type and method must
exist with that signature.
**Steps:** Resolve `PravahaFlightClient.connect(String)`, `register(String, String, List<Integer>)`,
`query(String, Object...)`, `QueryResult` being `Iterable<Row>`, `Row.getLong(String)` in the SDK
sources.
**Expected:** All present with compatible signatures.

## DOC-018 — The Python snippet and `cd sdk/python && make install` work
**Steps:** Check `sdk/python/Makefile` has an `install` target that creates `.venv`; check
`pravaha.connect`, `client.register`, `client.query` exist.
**Expected:** The documented path exists and the names are real.

## DOC-019 — The "What is not built" table is true
**Intent:** A closing table stating what to not go looking for. If it under-states what exists, a
reader will not use a feature that is there; if it over-states, they will hunt for one that is not.
**Steps:** Check each row against the code: clustering, metrics endpoint, connectors, Spring Boot
starter, column masking, performance gates.
**Expected:** Each row is accurate.

---

## Group B — commands, flags, endpoints

## DOC-020 — Every `pravaha` subcommand named in any document exists
**Steps:** Extract every `pravaha <word>` invocation from all documents and compare with the
dispatch table in `PravahaCli`.
**Expected:** No document names a subcommand the CLI does not have.

## DOC-021 — Every flag named in any document is accepted by the command it is given to
**Intent:** `--sql` is documented for `register` in the quickstart; `pravaha --help` documents only
`--sql-file`. Either the help is incomplete or the quickstart is wrong.
**Steps:** For each documented `<command> --flag` pair, check the argument parsing in the CLI and, for
the ambiguous ones, run the command.
**Expected:** Every documented flag is accepted, and `pravaha --help` documents every flag the
documentation tells a reader to use.

## DOC-022 — `pravaha explain --sql "…"` as printed in USER_GUIDE §7
**Steps:** Run `explain` with only `--sql`, as the user guide shows.
**Expected:** It works, or the user guide shows the required `--schema`.

## DOC-023 — Every HTTP endpoint named in any document responds
**Intent:** `/actuator/prometheus`, `/actuator/health/liveness`, `/api/v1/openapi.json`, `/api/docs`,
`POST /api/v1/streams`, `/status`, and the console's `/api/v1/...` are all named in prose.
**Steps:** Start a server on the assigned port and request each documented path.
**Expected:** Each returns a non-404. Any 404 is a documented-but-nonexistent endpoint.

---

## Group C — configuration keys, both directions

## DOC-024 — No document mentions a `pravaha.*` key that the code does not bind
**Intent:** The owner's stated failure mode: `pravaha.streams` was named in a javadoc before it
existed. A key in a document is an instruction; a key that binds nothing fails silently, which is the
worst kind.
**Steps:** Inventory every `pravaha.*` key across README, all of `docs/`, `docs/adr/`, `examples/` and
`console/`, and compare against the keys actually bound in `pravaha-server` main sources.
**Expected:** Empty set of documented-but-nonexistent keys.

## DOC-025 — Every key in `application.yaml` is documented somewhere a user would look
**Intent:** The reverse direction. A key that only exists in a comment inside the jar is not
documented; an operator reads `OPERATIONS.md`.
**Steps:** Inventory `pravaha-server/src/main/resources/application.yaml` and check each key against
the user-facing documents.
**Expected:** Every key appears in at least one user-facing document, or is deliberately internal and
marked so.

## DOC-026 — `OPERATIONS.md` "Starting a node" YAML is safe to copy
**Intent:** It prints a `pravaha.flight.port`. Every other surface in the repository — the shipped
default, the CLI's default URL, both SDKs, the console, every case study — uses 19090, and the shipped
`application.yaml` carries a comment explicitly rejecting the alternative.
**Steps:** Compare the printed value with the shipped default and with every other document.
**Expected:** They agree. A reader who copies the operations block gets a node their own CLI can
reach with the documented default URL.

## DOC-027 — No javadoc or code comment names a configuration key that does not exist
**Steps:** Grep main sources for `pravaha.` inside comments and javadoc and resolve each against the
binding code.
**Expected:** Every key named in a comment exists.

## DOC-028 — Console configuration is documented
**Intent:** `console/README.md` is the only document dedicated to the console. A reader who cannot
sign in has no recourse.
**Steps:** Compare `console/config/application.yaml` and the console's own config loader against
`console/README.md` and QUICKSTART §7.
**Expected:** Every setting a first run needs — above all the password gate — is documented in the
console's own README, not only in a document two directories away.

---

## Group D — error codes

## DOC-029 — The `TROUBLESHOOTING.md` code table matches the source, both directions
**Intent:** The document claims the table is "generated from the source, not from memory" and that
"if a code is missing here it does not exist in the engine" — a falsifiable claim.
**Steps:** Extract every `ErrorCode` declared in main sources; diff against the table.
**Expected:** Exact match in both directions.

## DOC-030 — The described cause matches the throwing code, for the codes the document features
**Intent:** A code table is cheap to keep true; a *cause* is where the rot is.
**Steps:** For `PRV-2050`, `2020`, `2021`, `4023`, `7001`, `7002`, `4022`, `4026`–`4028`, `4001`,
`3001`, `9002`, locate the throw site and compare the condition with the documented cause.
**Expected:** Each documented cause is the condition that actually raises it, and each featured code
is actually raised somewhere in main sources rather than merely declared.

## DOC-031 — The "ranges" table covers every range the full table uses
**Steps:** Compare the range list with the prefixes present in the code table.
**Expected:** No range appears in the detail table and not in the summary table.

## DOC-032 — `ErrorCodeUniquenessTest` exists and asserts what the document says it asserts
**Steps:** Locate the test and read its assertion.
**Expected:** It exists in main test sources and fails on a duplicate code.

---

## Group E — cross-document consistency (doc rot)

## DOC-033 — "The server has no ingestion path at all" — which document is telling the truth?
**Intent:** `README.md`'s evaluation banner says a query registered against `pravaha-server` never
receives a row. `QUICKSTART.md` §4 says the whole loop works from the command line. `HANDOVER.md`
says the server became a server on 2026-09-12. These cannot all be true, and the README banner is the
first paragraph an evaluator reads.
**Steps:** Settle it empirically with DOC-009, then record which documents are stale.
**Expected:** One consistent answer across README, QUICKSTART, OPERATIONS and HANDOVER.

## DOC-034 — Metrics: does the endpoint exist?
**Intent:** `OPERATIONS.md` documents a Prometheus endpoint and per-query metric names, and in
another section lists "**No metrics endpoint.** Counters exist on objects; nothing scrapes them" as
unsolved — and in a third place admits in prose that the two sections contradict each other.
`QUICKSTART.md` puts the metrics endpoint in Wave 9.
**Steps:** Start a server, scrape `/actuator/prometheus`, and look for the documented metric names.
**Expected:** One answer, and every document agreeing with it.

## DOC-035 — Does the console exist, according to the documentation?
**Intent:** `README.md`'s status line says "no UI"; its roadmap row for Wave 7 says "no console"; the
same file has a full console section, and `console/` exists with its own README.
**Steps:** Compare.
**Expected:** Internally consistent.

## DOC-036 — Four worked systems or five?
**Steps:** Count `examples/case-studies/` and compare with every document that states a number.
**Expected:** One number, and it is the right one.

## DOC-037 — Wave and feature claims in `examples/` match the current state
**Intent:** `examples/02-aggregate/README.md` closes with "Windowing gives the bound, and arrives in
Wave 4" while the rest of the documentation says windowing landed and the quickstart uses `TUMBLE`.
**Steps:** Compare forward-looking statements in `examples/` with the current state.
**Expected:** No example promises as future a feature that shipped.

## DOC-038 — `HANDOVER.md`'s claim that the quickstart is test-enforced
**Intent:** "`docs/QUICKSTART.md` is accurate and every command in it is executed by `ExamplesTest`,
so it cannot silently rot." If that is false, the owner's stated safety net is not there, and DOC-005
predicts it is false.
**Steps:** Read `ExamplesTest` and `DocumentationFreshnessTest` and establish exactly what they
assert about `QUICKSTART.md`.
**Expected:** The claim is true, or it is recorded as a defect of the highest order — a false claim
of test coverage is worse than no coverage, because it stops anyone checking.

## DOC-039 — README's module list against the real module set
**Intent:** `docs/README.md` claims a freshness test enforces that "every module is described".
**Steps:** Diff `README.md`'s "Modules currently built" list against `<module>` entries in `pom.xml`.
**Expected:** Every built module is described somewhere the test checks, and the README's list is not
misleading about what exists.

---

## Group F — examples

## DOC-040 — `examples/01-filter-and-project` runs exactly as its README says
**Steps:** Run the `run` and the `explain` commands verbatim from the repository root.
**Expected:** The quoted output appears.

## DOC-041 — `examples/02-aggregate` runs exactly as its README says
**Steps:** Run both commands verbatim; compare with the quoted `3,700` and the quoted `PRV-2050`.
**Expected:** Both match.

## DOC-042 — `examples/03-embedded-java` runs exactly as its README says
**Intent:** The only compile-and-run example. Its instructions involve a dependency classpath
incantation a naive reader cannot debug.
**Steps:** Run the four commands verbatim (skipping the `install`, which is already done).
**Expected:** The quoted five lines of output.

## DOC-043 — `examples/README.md` claim that the examples are build-enforced
**Intent:** "The commands in every `README.md` here are executed by `pravaha-it`'s `ExamplesTest`, so
an example that stops working fails the build."
**Steps:** Read `ExamplesTest` and establish which example commands it actually executes.
**Expected:** The claim is true.

## DOC-044 — `examples/case-studies/` — is a reader told how to stand the stores up?
**Steps:** Read `SETUP.md` and one case-study README end to end as a naive reader.
**Expected:** A reader can get from the README to a running case study, or the prerequisites are
stated plainly enough that they know to stop.

---

## Group G — links, paths, structure

## DOC-045 — Every markdown link in every document resolves
**Steps:** Resolve every relative link target across README, `docs/`, `docs/adr/`, `examples/`,
`console/README.md`, `sdk/`.
**Expected:** No broken target.

## DOC-046 — Every repository path named in backticks resolves
**Intent:** Prose references like `` `plugins/pravaha-cluster-zookeeper` `` and
`` `FileCheckpointStore.prune(keep)` `` are instructions too, and the link checker does not see them.
**Steps:** Extract backticked strings that look like repository paths and resolve them.
**Expected:** No dangling reference.

## DOC-047 — Every in-document anchor link resolves to a heading that exists
**Intent:** `QUICKSTART.md` links to `CONCEPTS.md#7-bounds-…`; a wrong anchor silently lands the
reader at the top of a long page.
**Steps:** Resolve every `#anchor` against the target file's headings.
**Expected:** All resolve.

## DOC-048 — Every ADR in `docs/adr/` is indexed and every ADR a document cites exists
**Steps:** Diff `docs/adr/README.md` against the directory; resolve every `ADR-nnn` citation.
**Expected:** Complete in both directions.

---

## Group H — onboarding and gaps

## DOC-049 — Clone to a running query answering questions, using only the documentation
**Intent:** The brief's central question. Walked as a competent engineer who has never seen this
repository, following documents in the order they point at each other, typing only what is written
and inventing nothing.
**Steps:** README → QUICKSTART → the first question answered. Record the first point at which the
reader must invent something the documentation did not give them.
**Expected:** They arrive. Every place they must invent something is a defect, ranked by how early it
occurs — an early one stops everybody.

## DOC-050 — What an operator needs and cannot find
**Intent:** Completeness, not accuracy. The brief names: security configuration, source binding,
checkpoint and journal operations, upgrade, backup, monitoring, capacity planning, and what to do
when a query fails.
**Steps:** For each, search the documentation set for an answer an operator could act on.
**Expected:** Each has a findable, actionable answer. Anything absent or answered only in passing is
recorded as a gap with the operational consequence of not having it.
