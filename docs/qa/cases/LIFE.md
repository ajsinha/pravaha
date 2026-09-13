# LIFE — the lifecycle of a continuous query

**Area:** `LIFE` · **IDs:** LIFE-001 … LIFE-130 · **Budget:** 130 · **Status:** authored, not executed.

A continuous query is a thing with a name, a state, a share of a machine and an end. This area owns
every transition between those: register, pause, resume, drop, re-register, share, read, restart,
fail. `INCR` owns whether the numbers are right; `STRM` owns what the subscription delivers. This
file owns whether the *object* behaves.

Surface under test: `pravaha-registry/.../QueryRegistry`, `RegisteredQuery`, `QueryState`,
`QueryFingerprint`, `RegistryJournal`; `pravaha-serving/.../ViewCatalog`, `ServedView`, `ViewQuery`,
`Consistency`; the three shipped ways in — `bin/pravaha` (Flight actions `pravaha.register`,
`pravaha.drop`, `pravaha.pause`, `pravaha.resume`, `pravaha.list`), the Java SDK
(`PravahaFlightClient`), and `QueryRegistry` in-process.

---

## What the executor needs to know before the first case

Six facts established by reading, each of which decides how a group of cases below must be run.

1. **`pravaha register` takes `--keys`, and the fingerprint does not.**
   `QueryFingerprint.of(plan, rowFilters)` hashes the physical plan and the security predicates.
   The key columns are passed to `start(...)` on the fresh path and are **journalled only** on the
   shared path. Two registrations of identical SQL with different `--keys` therefore share one
   computation and one `ServedView`, keyed as the *first* registrant asked. §10 is about this.
2. **Lifecycle is Flight-only.** `QueryController` (`/api/v1/queries`) offers `validate` and
   `explain` and nothing else — there is no REST register, pause, resume, drop or list. Every
   lifecycle case runs over Flight or in-process.
3. **The read path is always `CONSISTENT`.** `ViewQuery.run` reads `view.scan()`, which returns
   `visible` — the committed map. `ServedView.get(Consistency, timeout, key)` is the only code that
   honours a mode, and no transport calls it. The SDK's `ClientOptions.defaultConsistency` is stored,
   returned by a getter, and **referenced nowhere else in the SDK's main sources**. §11 is about this.
4. **`Consistency.AsOf` is always refused.** `ServedView.get` throws `PRV-4020` (`SERVING_NO_HISTORY`)
   for `AsOf` unconditionally: "a view holds the present, and the past lives in checkpoints."
5. **Rows arriving during a pause are dropped, not buffered**, and `advanceWatermark` is a no-op
   while paused, so the view's committed frontier freezes with it.
6. **A failed query keeps its names.** `fail(...)` sets `state = FAILED` and touches nothing else:
   the name stays in `byName`, the view stays in the `ViewCatalog` and keeps answering, and the
   fingerprint stays in `byFingerprint` — where the `!existing.state().isTerminal()` test in
   `register` means the *next* registration of the same SQL silently replaces the map entry. §13 is
   about this.

### Standing setup

```
stream txn : id INT64, usr STRING, amount INT64, event_time TIMESTAMP
bound to a 20-row CSV via pravaha.sources.txn (feedfile), as in INGEST's fixture.
stream payroll : declared, bound, and readable only by principal `hr`.
Ports: HTTP 18110, Flight 19110.  $QA = .../scratchpad/qa-life.
```

`S1` = `SELECT usr, amount FROM txn` — output columns `(usr, amount)`, ordinals 0 and 1.
`S1'` = `SELECT t.usr, t.amount FROM txn AS t` — different text, same plan, same fingerprint.
`S2` = `SELECT usr, SUM(amount) AS total FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time),
INTERVAL '10' SECOND)) GROUP BY usr, window_start, window_end` — a windowed aggregate, the only shape
that emits without an end of input (see `INCR-005`).

### Vacuity kit

Every stateful case below carries at least one of these, named in its own **Vacuity** line.

- **V-rows — the source was not dry.** `pravaha queries` ROWS IN is a specific expected number, not
  "non-zero". Round 1 shipped a pause test that passed because the feed had run out; every pause and
  resume case here therefore asserts ROWS IN *before* the pause and *after* the resume, and asserts
  that the unpaused control query in the same server run advanced over the same interval.
- **V-control — an unaffected query moved.** A second registration `ctrl` over the same stream, never
  paused and never dropped, whose ROWS IN must increase across the interval under test. If `ctrl` did
  not advance, the server was idle and the case is `INCONCLUSIVE`.
- **V-before — the thing existed.** Assert the view answers, the name lists, the key is present,
  *before* asserting it stops.
- **V-distinct — the two things are distinct.** Any sharing case asserts the two names are different
  strings and both appear in `pravaha queries` before asserting anything about one computation.

---

## §1 — Registration, the happy path (LIFE-001 … LIFE-006)

### LIFE-001 — A valid registration runs and is readable under its own name
**Intent:** The base case every other case in this file is measured against.
**Falsifier:** `register` returns `RUNNING` and `SELECT * FROM v1` answers "Object not found" — the
exact failure `ViewCatalog.registerAs`'s javadoc records.
**Setup:** Server up, `txn` bound to the 20-row CSV.
**Steps:** `pravaha register --name v1 --sql "SELECT usr, amount FROM txn" --keys 0`; wait;
`pravaha queries`; `pravaha query --sql "SELECT * FROM v1"`.
**Expected:** register prints `registered v1 state=RUNNING fingerprint=<12 hex chars>`. `queries`
lists exactly one row, `v1 RUNNING <fp> 20`. The read returns rows. Record the fingerprint — later
cases compare against it.
**Vacuity:** V-rows — ROWS IN is exactly 20, not 0 and not more.

### LIFE-002 — The registration's own name, not the plan's, is the name in a FROM clause
**Intent:** `ServedView.schema()` renames the output schema to the view's name, and
`ViewCatalog.schemas()` re-keys by the registered name. The javadoc records both as bugs that shipped:
"the alias was simply invisible: registration acknowledged RUNNING and the read answered 'Object not
found. Known streams: [the first name]'."
**Falsifier:** The planner reports the view under a derived name such as `txn_aggregated`.
**Setup:** LIFE-001 done.
**Steps:** `pravaha query --sql "SELECT * FROM v1"`; then `SELECT * FROM txn_projected` (a derived
name) to confirm it does not resolve.
**Expected:** the first succeeds; the second fails with `PRV-4023` naming the served set as `[v1]`.
**Vacuity:** V-before — the first read must return rows, or "the second fails" proves nothing.

### LIFE-003 — A registration's key columns are the *output* ordinals
**Intent:** `USER_GUIDE.md`: "The ordinals are into the query's *output* columns." `start(...)`
validates against `plan.outputSchema().fieldCount()`.
**Falsifier:** `--keys 1` is validated against the input stream's four columns rather than the
output's two.
**Setup:** Server up. `S1` has two output columns.
**Steps:** Register `v_k0` with `--keys 0`, `v_k1` with `--keys 1`, `v_k01` with `--keys 0,1` — each
with **different SQL** (add a distinguishing `WHERE amount > 0`, `> 1`, `> 2`) so they do not share
a fingerprint and the key columns actually take effect.
**Expected:** all three register. `v_k0` holds one row per distinct `usr`; `v_k1` one row per
distinct `amount`; `v_k01` one row per distinct pair. With the 20-row CSV's known contents, state the
three expected row counts explicitly and assert each.
**Vacuity:** V-distinct — assert the three fingerprints differ. If they match, §10's sharing takes
over and this case is testing something else entirely.

### LIFE-004 — A windowed aggregate registers and its view fills as windows close
**Intent:** `S2` is the shape the product exists for, and the only one that emits without an end of
input (`INCR-005`).
**Falsifier:** ROWS IN climbs and the view stays empty past the point the watermark should have
closed the first window.
**Setup:** Server up, `txn` CSV with event times spanning 40 seconds. Watermark generation on
(`QueryRegistry.generatingWatermarks()`, default 30s idle / 1s tick).
**Steps:** Register `v_win` with `S2 --keys 0`; poll `SELECT * FROM v_win` until non-empty, recording
elapsed time; assert contents.
**Expected:** the view fills. State the number of `(usr, window)` groups the CSV produces and the
hand-computed total per group. Record the observed latency from registration to first readable row.
**Vacuity:** V-rows — ROWS IN equals the CSV's row count before the first assertion on contents.

### LIFE-005 — `pravaha queries` reports state, fingerprint and rows for every name
**Intent:** The one shipped surface an operator has for the lifecycle. `doAction` LIST iterates
`required.names()`, which is insertion-ordered.
**Falsifier:** A registered name is missing, or the listing order changes between two calls.
**Setup:** Register `a`, `b`, `c` with three distinct queries, in that order.
**Steps:** `pravaha queries` twice, a second apart.
**Expected:** three rows, in registration order `a, b, c`, both times. Columns
`NAME STATE FINGERPRINT ROWS IN`. Three distinct fingerprints.
**Vacuity:** V-distinct — three distinct fingerprints asserted, so the ordering claim is about three
computations and not one aliased three times.

### LIFE-006 — A registration acknowledged is a registration journalled
**Intent:** `journalRegistration` runs after the query starts and before `register` returns. The
javadoc: "a client never gets an acknowledgement for a registration that would vanish at the next
restart."
**Falsifier:** `register` returns `RUNNING` and the journal file has no entry for it.
**Setup:** Server with a registry journal configured under `$QA/journal`.
**Steps:** Register `v_j`; immediately read the journal file.
**Expected:** an entry naming `v_j`, its SQL, its key columns, its owner id and its retention, present
before the CLI's acknowledgement was printed (assert by reading the file after the command returns).
**Vacuity:** V-before — assert the journal file is empty, or lacks `v_j`, immediately before the
register call.

---

## §2 — Names (LIFE-007 … LIFE-020)

`requireName` calls `requireSayableName` **first**, then the null/blank check, then the duplicate
check. `requireSayableName` applies the regex `[A-Za-z_][A-Za-z0-9_]*` and then hands
`SELECT 1 FROM <name>` to Calcite's parser.

### LIFE-007 — A duplicate name is refused, and the running query is untouched
**Intent:** The refusal's own reasoning: "silently replacing a running query would take its answers
away from whoever is reading them."
**Falsifier:** The second registration succeeds, or the first stops answering.
**Setup:** `v1` registered per LIFE-001 and answering.
**Steps:** Register `v1` again with **different** SQL (`SELECT usr FROM txn WHERE amount > 5`).
Then read `v1`.
**Expected:** `PRV-8001` (`REGISTRY_NAME_IN_USE`), message "'v1' is already registered. Drop it
first, or register under another name". `pravaha queries` still shows one `v1`, `RUNNING`, the
original fingerprint. The read returns the *original* query's rows.
**Vacuity:** V-before — read `v1` before the duplicate attempt and compare the row set afterwards.
An unchanged empty result would satisfy a naive assertion.

### LIFE-008 — A duplicate name with identical SQL is refused too, before sharing is considered
**Intent:** `requireName` runs before `PreparedContinuousQuery.of` and before the fingerprint is
computed. Sharing attaches a *new* name to an existing computation; it never reuses an old one.
**Falsifier:** The second registration returns the shared computation instead of refusing.
**Setup:** `v1` registered with `S1`.
**Steps:** Register `v1` again with exactly `S1`.
**Expected:** `PRV-8001`, not a shared registration. `pravaha queries` still shows one row.
**Vacuity:** Register the same `S1` under `v1b` in the same run and assert *that* succeeds and
shares — proving the refusal is about the name, not the SQL.

### LIFE-009 — A reserved word is refused at registration, not at the first read
**Intent:** `requireSayableName` runs `SELECT 1 FROM <name>` through Calcite precisely so the person
who chose the name is still holding it.
**Falsifier:** `primary` registers and fails only when read.
**Setup:** Server up.
**Steps:** `pravaha register --name primary --sql "$S1" --keys 0`.
**Expected:** `PRV-8001`, message "'primary' is a reserved word in SQL, so no query could read the
view. Choose a name that can appear in a FROM clause unquoted." Exit code `EXIT_FAILED`.
**Vacuity:** V-before — `pravaha queries` is empty before and after; a name that failed must not
occupy a slot.

### LIFE-010 — Every SQL reserved word behaves the same way
**Intent:** One reserved word proves the mechanism; the mechanism is a live parser, so the set it
refuses is version-specific and worth enumerating. `requireSayableName`'s comment says a hand-kept
list "is wrong the first time Calcite changes" — check the parser actually covers the obvious ones.
**Falsifier:** Any of these registers successfully.
**Setup:** Server up, empty registry.
**Steps:** Attempt registration under each of: `select`, `from`, `where`, `table`, `values`, `order`,
`group`, `join`, `union`, `primary`, `user`, `window`, `stream`, `default`, `case`, `end`.
**Expected:** a per-name verdict table. Every refusal is `PRV-8001`. Record which names are
**accepted** — `stream` and `window` are plausible acceptances and each accepted reserved-ish name is
a latent LIFE-009 waiting for a Calcite upgrade.
**Vacuity:** Include one control, `velocity`, which must register — proving the loop can succeed.

### LIFE-011 — A unicode name is refused by the regex, not by the parser
**Intent:** The regex is ASCII-only. A name a non-English-speaking operator would naturally choose is
refused, and the message says "a letter or underscore", which is not what a user of `é` will read as
an explanation.
**Falsifier:** `café_velocity` registers, or the error message is the reserved-word one.
**Setup:** Server up, terminal in UTF-8.
**Steps:** Register `café_velocity`, `日次集計`, `naïve`.
**Expected:** all three `PRV-8001` with the *first* message ("must be a plain identifier — a letter
or underscore, then letters, digits or underscores"), not the reserved-word message. Record the
message, because "letter" is what the message says and `é` is a letter.
**Vacuity:** Control: `cafe_velocity` (ASCII) registers in the same run.

### LIFE-012 — A name with whitespace is refused, in every position
**Intent:** Whitespace is the most common accidental name — a shell quoting slip.
**Falsifier:** Any of these registers.
**Setup:** Server up.
**Steps:** Register `"my view"`, `" v1"`, `"v1 "`, `"v\t1"`, `"v\n1"`, and the empty string `""`.
**Expected:** all `PRV-8001` from the regex. Note the empty string hits the regex first and so
reports the "plain identifier" message rather than `IllegalArgumentException("a registration needs a
name")` — the blank check in `requireName` is **unreachable**, because `requireSayableName` runs
before it. Record that as a dead branch.
**Vacuity:** Control `v_1` registers.

### LIFE-013 — A null name throws `NullPointerException`, not a Pravaha error
**Intent:** `requireSayableName(name)` calls `name.matches(...)` before `requireName`'s
`name == null` test. The null check is unreachable for the same reason as LIFE-012's blank check.
**Falsifier:** A `PravahaException` with a PRV code, or `IllegalArgumentException("a registration
needs a name")`.
**Setup:** In-process `QueryRegistry` (the CLI cannot express a null; the SDK and an embedder can).
**Steps:** `registry.register(null, S1, List.of(0), principal)`.
**Expected:** `NullPointerException` from `String.matches`, with no message, no PRV code and no help
URL. Over Flight the same input produces `CallStatus.INTERNAL` with description `null`, per
`doAction`'s catch-all.
**Vacuity:** Control — the same call with `"v_ok"` succeeds in the same registry.

### LIFE-014 — A very long name is accepted by the registry and may break the checkpoint directory
**Intent:** Nothing bounds the name's length. `checkpointDirectoryFor` percent-encodes every
non-alphanumeric byte, so the directory name can be up to 3× the name's UTF-8 length, against a
filesystem limit of 255 bytes per component on ext4.
**Falsifier:** The registration is refused for length (it is not expected to be), or a 300-character
name registers and checkpoints silently fail with nothing recorded.
**Setup:** Server with `checkpointingTo($QA/ckpt, …)` configured.
**Steps:** Register with a name of 64 `a`s; then 255 `a`s; then 300 `a`s; then 100 `_`s (which encode
to `_5f` each = 300 bytes).
**Expected:** all four pass the regex and register. For each, list `$QA/ckpt` and check a directory
exists. Where the directory cannot be created, assert the failure is *recorded*:
`RegisteredQuery.lastCheckpointFailure()` is present and `checkpointFailures()` is non-zero — the
registry's own javadoc says a query "which has silently not checkpointed for six hours does not look
like one that has". Report any case where the directory is missing and `checkpointFailures()` is 0.
**Vacuity:** Control — a 5-character name must produce a directory and `checkpointFailures() == 0`.

### LIFE-015 — A name that starts with a digit is refused
**Intent:** The regex's first character class.
**Falsifier:** `1v` registers.
**Steps:** Register `1v`, `2024_totals`, `_2024_totals`.
**Expected:** first two `PRV-8001`; the third **succeeds** (leading underscore is allowed).
**Vacuity:** The third is the control; without it the case cannot distinguish "digit rejected" from
"everything rejected".

### LIFE-016 — A name containing a path separator or dots is refused
**Intent:** The registry's own threat model — `checkpointDirectoryFor`'s comment about a query named
`..` writing above the configured root. The regex is the first line of defence.
**Falsifier:** Any of these registers.
**Steps:** Register `../evil`, `a/b`, `a.b`, `..`, `.`, `a\\b`, `a:b`.
**Expected:** all `PRV-8001` from the regex, before `checkpointDirectoryFor` is ever reached. Then,
separately, call `checkpointDirectoryFor` directly (in-process) with `a.b` and `a_b` and assert the
two encodings differ — `a_62e` vs `a_5fb` — confirming the injective encoding the round-2 note
records as pending re-verification.
**Vacuity:** The direct call is the control for the encoding half; the registry half's control is
`a_b` registering successfully.

### LIFE-017 — A name colliding with a declared stream
**Intent:** `ViewCatalog` and `StreamCatalog` are separate maps, and the planner resolves a `FROM`
name against both. A view named `txn` would shadow the stream its own query reads.
**Falsifier:** Registering a view named `txn` succeeds and `SELECT * FROM txn` becomes ambiguous or
silently resolves to the view.
**Setup:** `txn` declared and bound.
**Steps:** `pravaha register --name txn --sql "SELECT usr, amount FROM txn" --keys 0`; then
`pravaha query --sql "SELECT * FROM txn"`; then register a second query reading `FROM txn`.
**Expected:** record whether the registration is refused (no refusal exists in `requireName` for
this), and if it succeeds, which object the two subsequent reads resolve to. Any answer other than a
clear refusal at registration is a finding.
**Vacuity:** V-before — `SELECT * FROM txn` before the registration must fail with `PRV-4023` (it is
a stream, not a view), establishing the baseline resolution.

### LIFE-018 — A name colliding with another view's *derived* schema name
**Intent:** `ServedView.schema()`'s javadoc: "two views built from the same query shape collide under
one name and the second is invisible." The rename exists to prevent it; check it holds for two
different queries over one stream.
**Falsifier:** Registering the second makes the first unreadable.
**Steps:** Register `va` = `SELECT usr, amount FROM txn WHERE amount > 1 --keys 0` and `vb` =
`SELECT usr, amount FROM txn WHERE amount > 2 --keys 0`. Read both.
**Expected:** both readable, each returning its own filtered row set. State the two expected row
counts from the CSV explicitly.
**Vacuity:** The two counts must differ, or "both readable" is satisfied by one view answering twice.

### LIFE-019 — Case sensitivity of names
**Intent:** The regex admits both cases. Whether `V1` and `v1` are one name or two decides whether a
duplicate-name refusal can be bypassed by capitalisation, and whether a `FROM` clause resolves.
**Falsifier:** `V1` registers and then cannot be read, or `v1` and `V1` resolve to each other.
**Steps:** Register `v1`; register `V1` with different SQL; read both, with the name written each
way in the `FROM` clause (four reads).
**Expected:** `byName` is a plain `LinkedHashMap`, so `v1` and `V1` are two distinct registrations
and both succeed. Record how each of the four reads resolves — Calcite's identifier casing rules
decide, and an uppercase view name that cannot be read unquoted is a LIFE-009 that the parser check
did not catch.
**Vacuity:** V-distinct — assert two rows in `pravaha queries` with different fingerprints.

### LIFE-020 — A name is refused, and nothing is left behind
**Intent:** A refused registration must not have started a lane, opened a feed, created a view or
written a journal entry. `requireName` runs first, so nothing should have been built — assert it.
**Falsifier:** `pravaha queries` grows, a thread appears, or the journal gains an entry.
**Setup:** Baseline recorded: `pravaha queries` row count, JVM thread count for `pravaha-query-*`,
journal file size.
**Steps:** Attempt 50 registrations under refused names (mix of reserved, unicode, whitespace).
Re-record all three baselines.
**Expected:** all three unchanged. 50 failed registrations leak nothing.
**Vacuity:** Do one *successful* registration between the baselines and assert all three move — then
drop it and assert they return. Without that, "unchanged" is what a dead server reports.

---

## §3 — The SQL (LIFE-021 … LIFE-028)

### LIFE-021 — Malformed SQL is refused at registration with a plan error
**Intent:** `PreparedContinuousQuery.of` runs before authorization and before the fingerprint, so a
parse failure is the first thing that happens.
**Falsifier:** The registration is accepted and fails later, or the failure carries no PRV code.
**Steps:** Register with `SELECT usr FROM`, `SELEC usr FROM txn`, `SELECT usr FROM txn WHERE`,
and `''` (empty).
**Expected:** each a `PravahaException` with a `PRV-2xxx` code and a message naming the position.
Exit code `EXIT_FAILED`, not `EXIT_USAGE`. `pravaha queries` unchanged.
**Vacuity:** LIFE-020's three baselines re-checked after all four.

### LIFE-022 — An unknown stream is refused, and the message names what is known
**Intent:** The most common real mistake, and the one where a good message saves an hour.
**Falsifier:** The message does not list the available streams.
**Steps:** Register `SELECT usr FROM txns` (note the `s`).
**Expected:** refusal naming `txns` as unknown and listing the declared streams. Assert `txn` and
`payroll` appear in the list. Cross-check against `SECX`: for a principal who may not read `payroll`,
does this message still disclose it? Record the answer — round 2 found exactly this shape of leak in
`PRV-2002`/`PRV-8002`.
**Vacuity:** Control — `SELECT usr FROM txn` registers in the same run.

### LIFE-023 — An unwindowed keyed `GROUP BY` is refused as a continuous query
**Intent:** `SQL_SUPPORT.md` and `PhysicalPlanBuilder` both call this the one asymmetry that matters.
**Falsifier:** It registers.
**Steps:** Register `SELECT usr, COUNT(*) AS n FROM txn GROUP BY usr --keys 0`.
**Expected:** `PRV-2050` (`UNBOUNDED_STATE`), message explaining that state grows with distinct keys.
Then run the *same SQL* as a read against a maintained view (`SELECT usr, COUNT(*) FROM v1 GROUP BY
usr`) and assert it **succeeds** — same SQL, different answer, which is the documented asymmetry.
**Vacuity:** Both halves run. The refusal alone could be a general `GROUP BY` failure.

### LIFE-024 — A windowed `GROUP BY` without the window boundaries is refused
**Intent:** "the unbounded case wearing a window's clothes."
**Falsifier:** It registers.
**Steps:** Register `SELECT usr, SUM(amount) FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time),
INTERVAL '10' SECOND)) GROUP BY usr`.
**Expected:** `PRV-2050`, message "add window_start and window_end to the GROUP BY".
**Vacuity:** Control — `S2`, which does include them, registers in the same run.

### LIFE-025 — A refused registration releases the lane it was about to take
**Intent:** `start(...)` builds a `QueryExecution` *before* opening the feed and unwinds with
`execution.close()` if the feed throws. A plan-time refusal never reaches `start`, but a
feed-open refusal does.
**Falsifier:** Thread count or arena bytes climb across repeated failures.
**Setup:** A stream bound to a source that throws on `open` — the "plugin name that does not exist"
binding from `INGEST`'s fixture.
**Steps:** Record `pravaha-query-*` thread count. Attempt 20 registrations over that stream.
Re-record.
**Expected:** 20 refusals, thread count returned to baseline, `pravaha queries` empty.
**Vacuity:** One successful registration between the baselines must raise the thread count by one and
dropping it must return it — proving the counter measures what the case claims.

### LIFE-026 — A very large query registers or is refused, and says which
**Intent:** Nothing bounds SQL length. A generated query is a real client behaviour.
**Falsifier:** The server accepts it and then cannot list it, or dies.
**Steps:** Register a query whose `WHERE` is a chain of 1 000 `OR amount = <n>` terms; then 10 000.
**Expected:** a verdict for each: registered, or refused with a code. If registered, assert
`pravaha queries` still lists it (the LIST action returns the full SQL text in the result, so a
10 000-term query is a large Flight `Result`), and assert the read still works. Record the
registration latency.
**Vacuity:** Control — a 1-term query registers in the same run with sub-second latency.

### LIFE-027 — Parameters are classified before they are bound
**Intent:** `QueryRegistry.classify(sql)` exists so a caller can find out, before registering, how
many computations a parameterised query costs. ADR-032's whole position.
**Falsifier:** `classify` throws for a valid query, or reports `REGISTRATION` for a parameter the
view carries.
**Setup:** In-process registry (the CLI has no `classify` surface — record that as an API gap).
**Steps:** `classify("SELECT usr, amount FROM txn WHERE usr = ?")` — the view carries `usr`.
`classify("SELECT usr, SUM(amount) AS total FROM TABLE(TUMBLE(...)) GROUP BY usr, window_start,
window_end HAVING SUM(amount) > ?")` — aggregated away.
**Expected:** the first reports placement `TAP`; the second `REGISTRATION`. Then register the second
with two different bindings and assert `pravaha queries` shows **two** computations with two
fingerprints — bound values are in the plan and therefore in the fingerprint.
**Vacuity:** V-distinct — assert the two fingerprints differ. If they match, the binding is not in
the plan and ADR-032's cost model is wrong.

### LIFE-028 — A registration with bound parameters records its placements on the query
**Intent:** `RegisteredQuery.parameterPlacements()` and `avoidableForks()` are what a console shows
as "you are running N copies of something that could be one".
**Falsifier:** `avoidableForks()` is empty for a query whose parameter is in a `TAP` position.
**Setup:** In-process registry.
**Steps:** Register `SELECT usr, amount FROM txn WHERE usr = ?` bound to `'u1'`. Inspect
`parameterPlacements()` and `avoidableForks()`.
**Expected:** one placement, `TAP`; `avoidableForks()` has one entry. Then register the same SQL
bound to `'u2'` under another name and assert **two** fingerprints exist — i.e. the fork the
placement warned about actually happened. Record whether any shipped surface (CLI, REST, console API)
exposes `avoidableForks()`; none is expected to.
**Vacuity:** V-distinct on the two fingerprints.

---

## §4 — Key columns (LIFE-029 … LIFE-035)

### LIFE-029 — No key columns is refused
**Intent:** "a view with no key is a log, and a point read against it has nothing to look up."
**Falsifier:** An empty key list registers.
**Steps:** In-process: `register("v", S1, List.of(), principal)`. Over Flight:
`pravaha register --name v --sql "$S1" --keys ""` — note `ControlWire`'s decoder skips blank ordinals,
so the list arrives empty.
**Expected:** both `IllegalArgumentException` (not a `PravahaException`, so no PRV code — record it),
message "a registration needs at least one key column". Over Flight this surfaces as
`CallStatus.INTERNAL` with the raw message, per `doAction`'s catch-all.
**Vacuity:** Control — `--keys 0` registers in the same run.

### LIFE-030 — A key column past the end of the output is refused, naming the width
**Intent:** `start(...)`'s bounds check.
**Falsifier:** `--keys 5` registers on a two-column output.
**Steps:** `--keys 5`, `--keys 2` (one past the end of a two-column output), `--keys -1`.
**Expected:** all three `IllegalArgumentException`, message "key column N is not in the query's
output, which has 2 columns". Assert the message contains the actual width, `2`.
**Vacuity:** `--keys 1` (the last valid ordinal) registers in the same run — proving the boundary is
at 2, not at 1.

### LIFE-031 — A non-numeric `--keys` value is a CLI usage error, not a server error
**Intent:** `ServerCommand.register` does `Integer.parseInt(ordinal.strip())` with no handling, so a
`NumberFormatException` escapes into `PravahaCli`'s `RuntimeException` catch.
**Falsifier:** Exit code `EXIT_USAGE` (2), or a message naming `--keys`.
**Steps:** `pravaha register --name v --sql "$S1" --keys usr`.
**Expected:** `NumberFormatException: For input string: "usr"` printed as
`NumberFormatException: ...`, exit code `EXIT_FAILED`. The client never contacts the server. Record
that a plainly-wrong flag value reports a Java exception class name rather than usage.
**Vacuity:** Control — `--keys 0` succeeds and prints `registered`.

### LIFE-032 — Duplicate ordinals in `--keys`
**Intent:** `--keys 0,0` builds `List.of(0, 0)`; `ServedView` maps it to a two-element key array whose
halves are always equal.
**Falsifier:** The registration is refused (no check exists) — or it succeeds and the view
double-counts.
**Steps:** Register with `--keys 0,0`; feed the 20-row CSV; read.
**Expected:** registration succeeds. The view's key is `[usr, usr]`, so the row count equals the
distinct-`usr` count — identical to `--keys 0`. Assert the two produce the same row count, and record
that the redundant ordinal is silently accepted.
**Vacuity:** V-distinct — the `--keys 0` control must use *different* SQL, or it shares the
fingerprint and §10 takes over.

### LIFE-033 — Every output column as a key
**Intent:** The widest legal key. `ServedView.keyOf` builds an `Object[]` per row.
**Falsifier:** Registration fails, or the view holds fewer rows than the input's distinct rows.
**Steps:** Register `S1` with `--keys 0,1`; feed the CSV; read; compare against the CSV's distinct
`(usr, amount)` pairs, counted by hand.
**Expected:** row count equals the distinct pair count. State the number.
**Vacuity:** V-rows — ROWS IN equals 20 first.

### LIFE-034 — Key ordinals out of order
**Intent:** `--keys 1,0` is a different key *tuple* from `--keys 0,1`. `ServedView.Key` compares
`Arrays.equals` over the ordinals in the order given, so the tuples differ but the *partition* is
identical.
**Falsifier:** They produce different row counts.
**Steps:** Register two queries (distinct SQL to avoid sharing) with `--keys 0,1` and `--keys 1,0`;
compare row counts.
**Expected:** identical counts. The ordering affects only the key array's internal layout, which
nothing observable exposes.
**Vacuity:** V-distinct on fingerprints.

### LIFE-035 — The key columns do not reach the fingerprint
**Intent:** The precondition for every case in §10, established here in isolation.
**Falsifier:** Two registrations of identical SQL with different `--keys` report different
fingerprints.
**Setup:** Fresh registry.
**Steps:** Register `ka` with `S1 --keys 0`. Register `kb` with `S1 --keys 1`. Compare the
fingerprints printed by both `register` calls and shown by `pravaha queries`.
**Expected:** **identical** fingerprints, and `pravaha queries` shows two names. `QueryRegistry.size()`
— distinct computations — is **1**, while `names()` has 2.
**Vacuity:** V-distinct — assert `ka != kb` as strings and both appear in the listing. Then register
`kc` with `S1 || ' '` (genuinely different SQL) and assert *its* fingerprint differs, proving the
fingerprint is not constant.

---

## §5 — Authorization (LIFE-036 … LIFE-041)

### LIFE-036 — A principal who may not register is refused before anything is built
**Intent:** `policy.mayRegisterQuery` is checked after planning and before the source-read check.
**Falsifier:** The query starts and is then torn down, or the audit sink has no record.
**Setup:** A policy denying `mayRegisterQuery` for principal `guest`.
**Steps:** Register as `guest`.
**Expected:** `PRV-7001`-family `FORBIDDEN`, message "<id> may not register a query: <reason>". One
audit event recorded with `action=register` and `allowed=false`. `pravaha queries` unchanged.
**Vacuity:** LIFE-020's three baselines unchanged.

### LIFE-037 — A principal who may register but may not read the source is refused
**Intent:** The escalation the comment in `register` records: "a principal who could register but
could not read `payroll` could register `SELECT * FROM payroll` under a name of their choosing and
then read that view."
**Falsifier:** `SELECT * FROM payroll` registers for a principal denied `mayRead("payroll")`.
**Setup:** Policy: `guest` may register; `guest` may not read `payroll`.
**Steps:** Register `SELECT * FROM payroll --keys 0` as `guest`.
**Expected:** `FORBIDDEN`, message "…because it reads 'payroll', which they may not read: … A
registration is a standing read of everything the query names, so it is refused here rather than at
the first row." Two audit events: `register` (allowed) and `register:source` (denied).
**Vacuity:** Control — the same principal registering over `txn`, which they may read, succeeds.

### LIFE-038 — Source streams come from the plan, not from the SQL text
**Intent:** `sourceStreams(plan)` walks `ScanOperator`s. The comment: the text "can name a stream the
planner optimised away and can omit one a view expanded into."
**Falsifier:** A query whose text does not name `payroll` but whose plan scans it is allowed.
**Setup:** A registered view `v_pay` over `payroll`, and a policy denying `guest` on `payroll`.
**Steps:** As `guest`, register a query over `v_pay`. Then construct a query whose text names a
stream that the planner eliminates (e.g. a join branch removed by a contradictory predicate) and
check whether the eliminated stream is still authorized against.
**Expected:** a verdict for each. The second is the interesting one: a stream the plan does not scan
is not checked, so a principal denied on it is not refused — which is correct by the plan rule and
surprising by the text rule. Record it.
**Vacuity:** Control — the same principal on a plan that *does* scan `payroll` is refused.

### LIFE-039 — Row filters are sorted into the fingerprint
**Intent:** "two principals holding the same filters in a different order share, and two holding
different ones do not." `Collections.sort(rowFilters)` immediately before `QueryFingerprint.of`.
**Falsifier:** Two principals with the same filter set in different orders get two computations.
**Setup:** A policy returning row filters. Principal `p1` gets `["region='EU'", "tier='gold'"]`;
`p2` gets the same two in the other order; `p3` gets only `["region='EU'"]`.
**Steps:** Register the same SQL as each, under three names.
**Expected:** `p1` and `p2` share one fingerprint; `p3` has a different one. `QueryRegistry.size()`
is **2** with three names.
**Vacuity:** V-distinct — three names asserted present. Note round 2's finding that a server node
cannot be given a custom `SecurityPolicy` at all; if that still holds, this case is runnable only
in-process and must be marked so.

### LIFE-040 — `pause`, `resume` and `drop` require `mayAdminister`
**Intent:** `requireAdministrable` was added after "an unauthenticated caller dropped every
continuous query on a node configured to serve only verified callers". Re-verify, and check the
default round 2 recorded: `mayAdminister` defaulting to `mayRead` means read access is destroy
access.
**Falsifier:** A principal denied administration succeeds at any of the three verbs.
**Setup:** Server under the closed policy. Principal `reader` may read `v1` and is not its owner.
**Steps:** As `reader`: `pause`, `resume`, `drop` on `v1`. Then inspect whether the policy
distinguishes `mayAdminister` from `mayRead` at all.
**Expected:** each verb either refused with `FORBIDDEN` naming the verb, or — if `mayAdminister`
still delegates to `mayRead` — **allowed**, in which case record that a reader destroyed another
principal's computation and its state. Assert `pravaha queries` afterwards to show what survived.
**Vacuity:** V-before — `v1` listed and answering before each attempt.

### LIFE-041 — Registration over Flight is not checked by `requireAdministrable`
**Intent:** `doAction`'s `REGISTER` branch calls `required.register(...)` directly; only DROP, PAUSE
and RESUME call `requireAdministrable`. Registration's authorization therefore lives entirely in
`QueryRegistry`, which is the right place — confirm there is no second, weaker gate.
**Falsifier:** A principal refused by `mayRegisterQuery` registers over Flight.
**Setup:** Closed policy, `guest` denied registration.
**Steps:** `pravaha register --token <guest> …`.
**Expected:** `FORBIDDEN` from the registry, surfaced through `FlightErrors.statusFor`. Record the
gRPC status code so `SECX` and `API` can cross-check it.
**Vacuity:** Control — an allowed principal registers over the same connection.

---

## §6 — Pause (LIFE-042 … LIFE-053)

### LIFE-042 — A paused query stops accepting rows
**Intent:** `accept` returns `false` when `state != RUNNING`, and `pause()` also calls `feed.pause()`
— "a feed writes into the lane's inbox without passing through accept(), so a pause that stopped only
at accept() would not stop anything a source was pushing."
**Falsifier:** ROWS IN climbs while paused.
**Setup:** A **continuously producing** source — not the 20-row CSV, which runs dry and is exactly
how round 1's pause test passed vacuously. Use a large feed file (500 000 rows) or a directory the
plugin tails. Plus `ctrl`, an unpaused registration over the same stream.
**Steps:** Record ROWS IN for `v1` and `ctrl`. `pravaha pause --name v1`. Wait 10 seconds. Record
both again.
**Expected:** `v1` ROWS IN unchanged to the row; `ctrl` ROWS IN increased. State the observed
increase for `ctrl`.
**Vacuity:** V-control is the entire case. Without `ctrl` advancing, "v1 did not advance" is what an
exhausted source produces. Report `INCONCLUSIVE` if `ctrl` is flat.

### LIFE-043 — A paused query's view keeps answering at the frontier it reached
**Intent:** `QueryState.PAUSED`'s javadoc and `USER_GUIDE.md` both promise it: "far better for a
dashboard than answers that disappear."
**Falsifier:** A read of a paused view fails, returns zero rows, or advances.
**Setup:** LIFE-042's setup; `v1` has committed rows before the pause.
**Steps:** Read `v1` before the pause, recording the exact row set. Pause. Read again immediately,
and again after 10 seconds.
**Expected:** all three reads return the identical row set. `pravaha queries` shows `v1 PAUSED`.
**Vacuity:** V-before — the pre-pause read must be non-empty and its contents recorded, so "identical"
is a real comparison.

### LIFE-044 — A paused query's committed frontier does not advance
**Intent:** `advanceWatermark` returns immediately when `state != RUNNING`, so `sink.commit(...)` is
never called and `ServedView.committedFrontier` freezes.
**Falsifier:** `committedFrontier` moves during a pause.
**Setup:** In-process registry with a driving source, or the server plus a read reporting staleness.
**Steps:** Record `view.committedFrontier()`. Pause. Feed rows (which are dropped) and advance the
watermark 10 times. Record again.
**Expected:** unchanged. `appliedFrontier()` unchanged too, since `accept` refused the rows.
**Vacuity:** V-control — the same 10 advances on an unpaused query move its frontier; record both
numbers.

### LIFE-045 — Rows arriving during a pause are lost, not replayed on resume
**Intent:** The documented trade: "Buffering would turn a pause into a memory commitment of unknown
size." The cost is a gap, and the gap must be measurable.
**Falsifier:** The resumed query's ROWS IN accounts for every row the source produced during the
pause, or the view's totals after resume match a never-paused control.
**Setup:** LIFE-042's continuous source. `v1` and `ctrl` both registered, identical SQL is
**not** usable (they would share — see §10), so `ctrl` uses `WHERE amount > 0`, which the fixture
guarantees matches every row.
**Steps:** Record both ROWS IN. Pause `v1` for 10 seconds. Resume. Wait 10 seconds. Record both.
**Expected:** `ctrl` gained *N* rows over the 20 seconds; `v1` gained materially fewer, and the
shortfall is approximately the rows produced during the 10-second pause. State both numbers and the
shortfall. No error is reported anywhere, and nothing counts the lost rows — record that there is no
counter for it.
**Vacuity:** V-control supplies *N*. Without it the shortfall is unmeasurable.

### LIFE-046 — Pausing an already-paused query is accepted silently
**Intent:** `pause()` calls `requireLive("pause")`, which refuses only terminal states. `PAUSED` is
not terminal, so a second pause is a no-op that returns success.
**Falsifier:** The second pause raises `PRV-8003`.
**Steps:** `pause v1`; `pause v1` again.
**Expected:** both print `paused v1`, exit 0. State stays `PAUSED`. Record whether an operator
scripting `pause` can distinguish "I paused it" from "it was already paused" — they cannot.
**Vacuity:** V-before — `v1` is `RUNNING` before the first pause.

### LIFE-047 — Pausing a dropped query is refused
**Intent:** `requireLive` refuses `DROPPED`. But `drop` removes the name from `byName` first, so
`require(name)` fails before `pause()` is ever reached.
**Falsifier:** `PRV-8003` (`ILLEGAL_TRANSITION`) instead of `PRV-8002` (`NO_SUCH_QUERY`).
**Steps:** `drop v1`; `pause v1`.
**Expected:** `PRV-8002`, "no query named 'v1' is registered; this node has [...]". The
`ILLEGAL_TRANSITION` path for a dropped query is therefore **unreachable via the registry** — record
it. Reach it directly in-process by holding a `RegisteredQuery` reference across the drop and calling
`pause()` on it, and assert `PRV-8003` there.
**Vacuity:** The in-process half is the proof the branch exists at all.

### LIFE-048 — Pausing a failed query is refused with `PRV-8003`
**Intent:** `FAILED` is terminal and the name survives (fact 6), so `require(name)` succeeds and
`requireLive` does the refusing. This is the one terminal state reachable by name.
**Falsifier:** The pause succeeds, or reports `NO_SUCH_QUERY`.
**Setup:** A query driven to `FAILED` — see LIFE-126 for how.
**Steps:** `pravaha pause --name v_failed`.
**Expected:** `PRV-8003`, "cannot pause query 'v_failed': it is FAILED".
**Vacuity:** V-before — `pravaha queries` shows `v_failed FAILED` before the attempt.

### LIFE-049 — Pausing a name that does not exist
**Intent:** The plain error path.
**Steps:** `pravaha pause --name nope`.
**Expected:** `PRV-8002`, message listing every registered name. Cross-check with `SECX`: the message
enumerates names the caller may not be entitled to see. `requireAdministrable` runs **before**
`required.pause(name)` in `doAction`, so a denied principal is refused before the listing is built —
assert that the `FORBIDDEN` message for a nonexistent name does *not* enumerate.
**Vacuity:** Run as both an allowed and a denied principal; the two messages must differ.

### LIFE-050 — A subscriber attached to a paused query stays connected and receives nothing
**Intent:** `PAUSED` is not terminal, so `streamSubscription`'s loop condition
(`!query.state().isTerminal()`) keeps running and the handover queue stays empty.
**Falsifier:** The subscription ends, errors, or delivers changes during the pause.
**Setup:** Continuous source; `pravaha subscribe --view v1` running in a second shell.
**Steps:** Confirm changes are printing. Pause. Watch for 15 seconds. Resume. Watch again.
**Expected:** during the pause, no output at all and no error; the connection stays open. After
resume, output restarts. Record the gap in wall-clock seconds and whether any change spans it.
**Vacuity:** V-control — the subscriber must be printing before the pause, at a recorded rate.

### LIFE-051 — A new subscription to a paused query is accepted
**Intent:** `RegisteredQuery.subscribe` refuses only terminal states.
**Falsifier:** Subscribing to a paused view raises `PRV-8003`.
**Steps:** Pause `v1`; then `pravaha subscribe --view v1`.
**Expected:** the subscription is established and the CLI prints its "subscribed to v1" banner. No
changes arrive. On resume, changes arrive. `subscriberCount()` is 1 throughout.
**Vacuity:** Assert `subscriberCount()` moves 0 → 1 on subscribe, so "no changes" is not "no
subscriber".

### LIFE-052 — A paused query still holds its lane, arena, feed and checkpointer
**Intent:** Pause stops work, not cost. An operator pausing to relieve a machine needs to know that.
**Falsifier:** The lane thread disappears, or checkpointing continues writing new checkpoints.
**Setup:** Server with checkpointing configured. Record thread count, RSS, and the checkpoint
directory listing.
**Steps:** Pause `v1`. Wait past two checkpoint intervals. Re-record all three.
**Expected:** the `pravaha-query-*` thread still exists; RSS essentially unchanged. For checkpoints:
`PeriodicCheckpointer` is not stopped by `pause()` — record whether new checkpoint files appear for a
paused query, and whether checkpointing a frozen state repeatedly prunes the older, more useful ones.
**Vacuity:** Compare against `ctrl`, still running: its checkpoint count must grow over the same
interval.

### LIFE-053 — Pause and resume 50 times leaks nothing
**Intent:** The cheapest way to find a lifecycle leak.
**Falsifier:** Threads, heap, or checkpoint directories grow monotonically across cycles.
**Setup:** Continuous source. Record thread count, heap after GC, checkpoint directory count.
**Steps:** 50 × (`pause v1`; sleep 200ms; `resume v1`; sleep 200ms). Re-record.
**Expected:** all three within noise of the baseline. `v1` still `RUNNING` and answering, ROWS IN
having advanced across the whole run.
**Vacuity:** V-rows — the final ROWS IN must exceed the initial, or the source went dry and 50 cycles
of nothing proves nothing.

---

## §7 — Resume (LIFE-054 … LIFE-061)

### LIFE-054 — Resume restarts acceptance and the feed
**Intent:** `resume()` sets `RUNNING` and calls `feed.resume()`.
**Falsifier:** ROWS IN stays flat after resume.
**Setup:** LIFE-042's setup, `v1` paused.
**Steps:** Record ROWS IN. Resume. Wait 10 seconds. Record.
**Expected:** ROWS IN increased; state `RUNNING`. State the observed increase and compare it to
`ctrl`'s over the same interval — they should be of the same order.
**Vacuity:** V-control.

### LIFE-055 — Resume does not replay the gap
**Intent:** The other half of LIFE-045, asserted on the view rather than the counter.
**Falsifier:** The resumed view's aggregate matches a never-paused control exactly.
**Setup:** `S2`-shaped windowed queries `v_win` and `ctrl_win` (distinct SQL), continuous source with
known event times.
**Steps:** Let both fill one window. Pause `v_win` across the next whole window. Resume. Let both fill
a third window. Compare the three windows' totals.
**Expected:** window 1 identical; window 2 **missing or partial** in `v_win` and complete in
`ctrl_win`; window 3 identical again. State the hand-computed expected total for window 2 and the
observed one. Record whether `v_win`'s window 2 is absent or present-but-wrong — the second is worse
and is what a dropped-rows pause produces.
**Vacuity:** Windows 1 and 3 matching is the control: it proves the two queries agree when neither is
paused.

### LIFE-056 — Resuming a running query is accepted silently
**Intent:** `resume()` refuses only terminal states, so `RUNNING → RUNNING` succeeds.
**Falsifier:** `PRV-8003`.
**Steps:** `resume v1` while it is running; twice.
**Expected:** both succeed, printing `resumed v1`; state stays `RUNNING`; ROWS IN keeps climbing
across both calls (assert it, to show the feed was not disturbed).
**Vacuity:** V-rows across the two calls.

### LIFE-057 — Resuming a failed query is refused, with the documented reason
**Intent:** The most carefully worded refusal in the registry: "A failed query is not restarted in
place: whatever failed is still in the state it failed in, and restarting over it hides the cause."
**Falsifier:** The resume succeeds, or the message does not give that reason.
**Setup:** `v_failed` in `FAILED` (LIFE-126).
**Steps:** `pravaha resume --name v_failed`.
**Expected:** `PRV-8003`, message containing "is FAILED and cannot be resumed" and the reasoning
above. State stays `FAILED`.
**Vacuity:** V-before — `pravaha queries` shows `FAILED`, and shows it still `FAILED` after.

### LIFE-058 — Resuming a dropped query reports `NO_SUCH_QUERY`
**Intent:** Same shape as LIFE-047: the name is gone before the state check.
**Steps:** `drop v1`; `resume v1`.
**Expected:** `PRV-8002`. In-process, holding the `RegisteredQuery` across the drop and calling
`resume()` gives `PRV-8003` with "is DROPPED and cannot be resumed". Both recorded.
**Vacuity:** The in-process half proves the branch exists.

### LIFE-059 — Resume after a pause that spanned a whole window
**Intent:** Windowed state interacts with a pause in a way the counters do not show: the watermark
stopped advancing, so windows that should have closed did not, and on resume they close all at once.
**Falsifier:** Windows that should have fired during the pause are never emitted, or fire with
partial data and are never corrected.
**Setup:** `S2` with 10-second tumbles, a source whose event times advance steadily.
**Steps:** Let window 1 fire. Pause across windows 2 and 3. Resume. Advance past window 4.
**Expected:** on resume, `advanceWatermark` jumps forward and `windowsCompletedBetween(lastFired,
now)` fires windows 2, 3 and 4 in one batch. Windows 2 and 3 contain only whatever rows arrived
before the pause — the rest were dropped by `accept` — so their totals are low. Hand-compute the
expected totals from the rows the fixture delivered before the pause and assert them. No error, no
late-record count (the rows never reached the operator).
**Vacuity:** `ctrl_win`'s windows 2 and 3 must be complete, giving the comparison.

### LIFE-060 — Resume after a pause longer than the watermark idle timeout
**Intent:** `generatingWatermarks(idleAfter, tick)` — an idle partition is excluded from the
watermark after `idle-after` (min 1s, max 10m). A pause makes the query look idle to its own
watermark generator.
**Falsifier:** The watermark regresses, or throws — `Frontier.advanceTo` refuses a regression with
`IllegalArgumentException`.
**Setup:** `idle-after` set to 5 seconds. `S2` registered.
**Steps:** Pause for 30 seconds. Resume. Feed rows whose event times are *earlier* than the watermark
the generator reached while the query was paused, if it advanced at all.
**Expected:** record whether the watermark advanced during the pause (it should not — `advanceWatermark`
returns early when not RUNNING, but the generator thread may still be running). Assert no
`IllegalArgumentException("a frontier cannot regress")` escapes, and that the resumed query either
accepts the rows or counts them as late — never crashes.
**Vacuity:** V-control — `ctrl_win`, never paused, must not produce the same behaviour.

### LIFE-061 — Pause and resume of one name pauses the shared computation
**Intent:** `pause(name)` resolves through `byName` to the shared `RegisteredQuery` and sets *its*
state. Two names on one computation cannot be paused independently — and neither registrant knows the
other exists.
**Falsifier:** Pausing `a` leaves `b` running.
**Setup:** `a` and `b` registered with `S1` and `S1'` (same fingerprint, per §10).
**Steps:** Confirm both listed `RUNNING`. `pause a`. `pravaha queries`. Read `b`. Wait 10 seconds and
read `b` again.
**Expected:** **both** `a` and `b` show `PAUSED` — one state object, two names. `b`'s view stops
advancing. State the ROWS IN for both before and after. This is a correct consequence of sharing and
a severe operational surprise; record it against `USER_GUIDE.md`, which documents drop's refcount but
says nothing about pause.
**Vacuity:** V-control — a third, unshared query must keep advancing over the same interval.

---

## §8 — Drop (LIFE-062 … LIFE-075)

### LIFE-062 — Dropping the sole name releases the computation
**Intent:** `drop` → `removeName` returns true → `byFingerprint.remove`, `query.close()`,
`deleteCheckpointsOf`.
**Falsifier:** The name still lists, or the lane thread survives.
**Setup:** `v1` registered and running; thread count recorded.
**Steps:** `pravaha drop --name v1`; `pravaha queries`; read `v1`; re-record threads.
**Expected:** `dropped v1`. Listing empty. Read fails with `PRV-4023` naming an empty served set.
Thread count back to baseline.
**Vacuity:** V-before — listing non-empty, read succeeding, thread present, immediately before.

### LIFE-063 — A dropped view stops answering, immediately
**Intent:** `ViewCatalog.remove`'s javadoc: "A dropped view that keeps answering serves whatever the
closed computation last committed, for ever, to a caller who has no way to tell."
**Falsifier:** A read issued after the drop returns rows.
**Setup:** `v1` with a known non-empty row set.
**Steps:** Read (record rows). Drop. Read again, within 100ms.
**Expected:** second read `PRV-4023`. No window in which stale rows are served.
**Vacuity:** V-before — the first read's row count is stated.

### LIFE-064 — Dropping one of several shared names leaves the others correct
**Intent:** The refcount. `CONCEPTS.md` §5: "dropping eagerly would be an outage caused by somebody
tidying up their own query."
**Falsifier:** Dropping `a` breaks `b`.
**Setup:** `a` and `b` sharing one fingerprint; both answering with a recorded row set.
**Steps:** Read both. `drop a`. Read `a` (must fail) and `b` (must succeed, same rows). Wait 10
seconds and read `b` again — it must have advanced.
**Expected:** `a` → `PRV-4023`. `b` → the same rows, then more. `pravaha queries` lists only `b`.
`QueryRegistry.size()` still 1. Thread count unchanged — the lane was not released.
**Vacuity:** V-before on both reads; V-rows on `b`'s advance, which proves the computation is alive
rather than merely listed.

### LIFE-065 — Dropping the last of several names releases everything
**Intent:** The other end of the refcount.
**Falsifier:** The lane survives after the last name goes.
**Setup:** As LIFE-064, `a` already dropped.
**Steps:** `drop b`. Listing, read, thread count, checkpoint directory.
**Expected:** listing empty, read fails, thread released. **Checkpoints:** `deleteCheckpointsOf(b)`
is called, but `startCheckpointing` created the directory under **`a`**, the first registrant's name.
So `a`'s checkpoint directory survives with nothing owning it. Assert the directory listing and record
the orphan — this is the "52 directories for 2 live queries" failure the registry's own comment
records, arriving by a different route.
**Vacuity:** V-before — assert `a`'s checkpoint directory exists and is non-empty before dropping `b`.

### LIFE-066 — Dropping a query with a live subscriber ends the stream without an error
**Intent:** `streamSubscription`'s loop exits on `query.state().isTerminal()` and then calls
`listener.completed()` — a normal end. The subscriber cannot distinguish "the query was dropped" from
"the stream finished".
**Falsifier:** The subscriber receives an error status naming the drop, or hangs.
**Setup:** `pravaha subscribe --view v1` running and printing.
**Steps:** `pravaha drop --name v1` from another shell. Observe the subscriber.
**Expected:** the subscriber's stream ends cleanly, exit code 0, with no message saying why. Record
the latency between the drop and the stream ending — the loop polls the handover queue with a 200ms
timeout, so up to ~200ms. Record that nothing tells the subscriber the view is gone.
**Vacuity:** V-before — the subscriber must be printing changes at a recorded rate before the drop.

### LIFE-067 — Dropping mid-ingest does not corrupt the shutdown ordering
**Intent:** `RegisteredQuery.close()` closes the feed first, then the checkpointer, then the
execution — "closing the execution while a pump is mid-write leaves it writing into a lane that has
gone."
**Falsifier:** Any exception on any thread, a hung `drop`, or a `ConcurrentModificationException` of
the kind `ServedView`'s javadoc records ("the feed died … silently, after 181,248 of 200,000 rows,
with the query still reporting RUNNING").
**Setup:** 500 000-row feed, `v1` registered, ingest confirmed in flight (ROWS IN climbing fast).
**Steps:** Drop `v1` while ROWS IN is climbing. Capture server stderr and the thread dump before and
after.
**Expected:** the drop returns within a second; no exception in the log; the lane thread is gone; the
server's other queries are unaffected (`ctrl` keeps advancing). Repeat 20 times at different points in
the ingest.
**Vacuity:** V-rows — ROWS IN must be climbing at the moment of each drop, recorded per iteration. A
drop against an idle query tests nothing.

### LIFE-068 — Dropping twice reports `NO_SUCH_QUERY` the second time
**Intent:** Idempotence, or the honest absence of it.
**Steps:** `drop v1`; `drop v1`.
**Expected:** first succeeds; second `PRV-8002` listing the remaining names. Exit codes 0 then
`EXIT_FAILED`. Record that a cleanup script must tolerate the second.
**Vacuity:** V-before — listed before the first.

### LIFE-069 — Dropping a name that never existed
**Steps:** `pravaha drop --name nope`.
**Expected:** `PRV-8002`, message "no query named 'nope' is registered; this node has [...]" — and
the listing is the *full* set, as a disclosure. Under a closed policy, `requireAdministrable` runs
first, so a denied principal gets `FORBIDDEN` with no listing. Run as both and record.
**Falsifier:** The unauthenticated form enumerates the node's queries.
**Vacuity:** Two principals, two messages, compared.

### LIFE-070 — The drop is journalled before anything is released
**Intent:** `drop` writes `journal.recordDrop(name)` first, deliberately: "a drop the client is told
failed must not have destroyed the computation, and a drop that succeeded must survive a restart.
Recording it here instead meant neither was guaranteed." Round 2 lists this as fixed; re-verify.
**Falsifier:** A drop that raised an error to the client left the computation destroyed and unlogged.
**Setup:** Journal on a filesystem that can be made read-only mid-test.
**Steps:** (a) Normal drop: assert the journal gains a drop record and the query is gone. (b) Make the
journal unwritable; drop again (a different query): assert the client sees `PRV-8006`
(`JOURNAL_UNWRITABLE`), and then assert the query is **still registered and still answering** — the
throw happens before `byName.remove`.
**Expected:** exactly that. In (b), `pravaha queries` still lists it and a read still returns rows.
**Vacuity:** (a) is the control for (b): without it, "still listed" could mean the drop never ran.

### LIFE-071 — A drop survives a restart
**Intent:** The point of journalling the drop.
**Falsifier:** The dropped query comes back on restart — round 2's "A drop the client is told failed
had already destroyed the computation … and came back on restart."
**Setup:** Journal configured. `a` and `b` registered.
**Steps:** `drop a`. Restart the server. `pravaha queries`.
**Expected:** only `b`. `recover(...)` reports `Recovery[1 recovered, 0 refused]`.
**Vacuity:** V-before — both listed before the restart, so "only b" is the result of the drop and not
of a failed recovery.

### LIFE-072 — Dropping a paused query works
**Intent:** `PAUSED` is not terminal; `require(name)` resolves; `close()` sets `DROPPED`.
**Steps:** Pause `v1`; drop `v1`.
**Expected:** drop succeeds, listing empty, read fails, lane released. No `ILLEGAL_TRANSITION`.
**Vacuity:** V-before — `PAUSED` shown in the listing immediately before.

### LIFE-073 — Dropping a failed query works and releases it
**Intent:** `drop` never calls `requireLive`; `close()` checks only `state != DROPPED`.
**Setup:** `v_failed` in `FAILED`.
**Steps:** `pravaha drop --name v_failed`; listing; thread count; read.
**Expected:** drop succeeds; the name is gone; the lane thread is released; the view stops answering.
State transitions `FAILED → DROPPED`.
**Vacuity:** V-before — the failed query's view must have been *answering* before the drop (fact 6),
so "stops answering" is a change.

### LIFE-074 — 50 register/drop cycles leak nothing
**Intent:** The registry's own comment records the failure this catches: "52 directories for 2 live
queries, in a QA run of 50 register/drop cycles."
**Falsifier:** Checkpoint directories, threads, heap or journal size grow with the cycle count.
**Setup:** Checkpointing and journalling both on. Baselines recorded.
**Steps:** 50 × (register `v_n` with distinct SQL; wait for ROWS IN > 0; drop `v_n`). Record all four
metrics every 10 cycles.
**Expected:** checkpoint directory count returns to 0 after each drop and is 0 at the end; threads at
baseline; heap flat after GC. The journal **grows** — it is append-only — so record its size and check
whether compaction ever runs. State the journal's size after 50 cycles.
**Vacuity:** V-rows per cycle: each `v_n` must reach ROWS IN > 0 before being dropped, or the cycle
never built the state the leak would be in.

### LIFE-075 — Dropping does not disturb an unrelated query mid-window
**Intent:** One lane per query, but one arena allocator and one `ViewCatalog` generation counter.
`ViewCatalog.remove` bumps `generation`, which invalidates cached plans.
**Falsifier:** `ctrl_win`'s window totals change, or its next read re-plans and fails.
**Setup:** `ctrl_win` (`S2`-shaped) mid-window with a hand-computed partial total; `v1` also
registered.
**Steps:** Record `ViewCatalog.generation()`. Drop `v1`. Record generation. Let `ctrl_win`'s window
close and read it.
**Expected:** generation incremented by exactly 1. `ctrl_win`'s window total equals the hand-computed
value for all its rows, unaffected. The next read against `ctrl_win` re-plans (cache invalidated) and
succeeds.
**Vacuity:** State the expected window total arithmetically before the drop, so "unaffected" is a
comparison against a number and not against itself.

---

## §9 — Re-registration after a drop (LIFE-076 … LIFE-082)

### LIFE-076 — The same SQL under the same name after a drop starts with empty state
**Intent:** `drop` removes the fingerprint entry and closes the execution, so the next registration
takes the fresh path and builds a new `ServedView`. State is **not** inherited.
**Falsifier:** The re-registered view answers immediately with the dropped query's rows.
**Setup:** `v1` registered, filled with a known row set, then dropped.
**Steps:** Immediately re-register `v1` with the identical SQL and keys. Read within 100ms, before
the feed can refill it.
**Expected:** the read succeeds (the name resolves) and returns **zero rows**, then fills from the
source. `pravaha queries` shows ROWS IN restarting from 0. The fingerprint printed is the **same**
string as before — the fingerprint is a hash of the plan, so it is stable across drops and is
therefore *not* an identity for a computation instance. Record that.
**Vacuity:** V-before — the pre-drop row count is recorded and non-zero, so "zero rows" is a change.

### LIFE-077 — A re-registration does not inherit the dropped query's checkpoints
**Intent:** `deleteCheckpointsOf(name)` runs on the drop, so there is nothing to restore from. The
registry does not restore from a checkpoint on registration at all — `startCheckpointing` only writes.
**Falsifier:** The re-registered query's windows come back partially filled.
**Setup:** Checkpointing on; `v1` filled and checkpointed at least once; dropped.
**Steps:** Assert the checkpoint directory is gone. Re-register `v1`. Read immediately.
**Expected:** directory absent; the new query starts empty. Record that a registration **never**
restores from a checkpoint — recovery is `QueryRegistry.recover(...)` re-registering from the journal,
which also starts from empty state, and the registry's own javadoc says so: "a restart costs a
warm-up rather than an outage: the views are there immediately and fill as data arrives."
**Vacuity:** V-before — the directory exists and is non-empty before the drop.

### LIFE-078 — Re-registering under a *different* name after a drop
**Intent:** The fingerprint entry was removed, so this is a fresh computation, not a share.
**Steps:** `drop v1`; register the same SQL as `v2`.
**Expected:** succeeds; one computation; `QueryRegistry.size() == 1`; same fingerprint string as `v1`
had. `v2` starts empty and fills.
**Vacuity:** V-before — listing empty between the drop and the register.

### LIFE-079 — Re-registering while another name still holds the computation shares it
**Intent:** The contrast with LIFE-076. If `b` still holds the fingerprint, re-registering `a`
attaches to `b`'s **warm** computation and inherits its state instantly.
**Falsifier:** `a` comes back empty.
**Setup:** `a` and `b` sharing; `b` filled with a known row set; `drop a`.
**Steps:** Re-register `a` with the same SQL. Read `a` within 100ms.
**Expected:** `a` answers immediately with `b`'s current rows — no warm-up. This is the sharing
feature working, and it is the opposite of LIFE-076's behaviour for the same command. Record both
side by side: whether a re-registration inherits state depends entirely on whether somebody else
happens to hold the same fingerprint, which the registrant cannot see.
**Vacuity:** LIFE-076 is the control. Both must be run in the same session to make the contrast.

### LIFE-080 — Re-registering the same SQL with *different* keys after a drop uses the new keys
**Intent:** On the fresh path, `start(...)` builds the `ServedView` from the keys given. Only the
shared path ignores them (§10).
**Falsifier:** The re-registered view is keyed as the dropped one was.
**Setup:** `v1` registered with `--keys 0`, filled, dropped.
**Steps:** Re-register `v1` with the same SQL and `--keys 0,1`. Fill. Count rows.
**Expected:** the new view's row count equals the distinct `(usr, amount)` pair count, not the
distinct `usr` count. State both numbers from the CSV.
**Vacuity:** The two numbers must differ in the fixture, or the case cannot tell the keyings apart.

### LIFE-081 — A drop and re-register under load does not lose the name
**Intent:** `drop` and `register` are both `synchronized` on the registry, and `views.remove` /
`views.register` bump the catalogue generation. A reader mid-plan across the boundary must fail
cleanly, never serve the wrong view.
**Falsifier:** A read returns rows from the dropped instance after the re-registration, or a read
throws something other than `PRV-4023`.
**Setup:** A reader loop issuing `SELECT * FROM v1` continuously from a second process.
**Steps:** 20 × (`drop v1`; re-register `v1`), with the reader running throughout. Classify every
reader response.
**Expected:** every response is either a successful read or `PRV-4023`. No other error, no stale rows
from a closed computation. Record the count of each class.
**Vacuity:** Assert the reader issued at least 1 000 requests across the run; a reader that made three
calls proves nothing about a race.

### LIFE-082 — Registering after `close()` of the whole registry
**Intent:** `QueryRegistry.close()` clears both maps and closes every query, but leaves the registry
object usable — there is no closed flag.
**Falsifier:** A registration after `close()` succeeds and produces a query nothing will ever clean up.
**Setup:** In-process registry.
**Steps:** Register two queries; `registry.close()`; assert `names()` empty; register a third.
**Expected:** record the outcome. No guard exists, so the third registration is expected to succeed —
starting a lane thread on a registry the owner believes is shut down. Assert whether `close()` a
second time releases it.
**Vacuity:** Assert `names()` is empty between the close and the third registration.

---

## §10 — Sharing by fingerprint (LIFE-083 … LIFE-100)

### LIFE-083 — Two identical queries are one computation with two names
**Intent:** The headline claim: "ten analysts opening the same dashboard cost one query rather than
ten."
**Falsifier:** Two fingerprints, or two lane threads.
**Setup:** Fresh registry; thread count recorded.
**Steps:** Register `a` with `S1 --keys 0`. Record threads. Register `b` with `S1 --keys 0`. Record
threads and both fingerprints.
**Expected:** identical fingerprints. Thread count rises by exactly 1 for `a` and by **0** for `b`.
`pravaha queries` lists two rows; `QueryRegistry.size() == 1`.
**Vacuity:** V-distinct — `a` and `b` are different strings, both listed. And register `c` with
genuinely different SQL and assert threads rise by 1 again, proving the counter moves.

### LIFE-084 — Different text, same plan, same computation
**Intent:** `CONCEPTS.md` §5's worked example: aliases and whitespace must not fork.
**Falsifier:** `S1` and `S1'` produce different fingerprints.
**Steps:** Register `a` with `S1`, `b` with `S1'` (`SELECT t.usr, t.amount FROM txn AS t`), `c` with
`S1` reformatted across five lines with extra whitespace, `d` with a reordered `AND` in the `WHERE`
(`WHERE amount > 1 AND id > 0` vs `WHERE id > 0 AND amount > 1`, both applied to the same base query).
**Expected:** `a`, `b` and `c` share one fingerprint. For `d`, record whether Calcite normalises the
`AND` operand order — `CONCEPTS.md` claims "reordered `AND` operands all land on the same
computation", so a differing fingerprint is a documentation defect, not an engine one.
**Vacuity:** A fifth registration with a genuinely different predicate must produce a different
fingerprint.

### LIFE-085 — Row counts do not double when a query is shared
**Intent:** `start(...)`'s comment: the second registration "returns early above without reaching
here — which is what stops a shared computation being fed twice and double-counting every row."
**Falsifier:** ROWS IN for the shared computation is 40 for a 20-row source.
**Setup:** 20-row CSV. Fresh server.
**Steps:** Register `a` with `S1`. Wait for ROWS IN 20. Register `b` with `S1`. Wait 10 seconds.
`pravaha queries`; read both.
**Expected:** both rows show ROWS IN **20**, not 40 — it is one counter read twice. Both reads return
the same rows, and the row count matches the CSV's distinct-key count exactly, hand-stated.
**Vacuity:** Round 1's failure mode was a row-count assertion that passed while 200 000 rows collapsed
into 500 keys. State the distinct-key count and the raw row count separately and assert both.

### LIFE-086 — Each shared name is independently readable
**Intent:** The bug `ViewCatalog.registerAs` records: "register returned RUNNING and `SELECT … FROM
second_name` answered 'Object not found'." Round 2 lists the fix as pending re-verification.
**Falsifier:** Reading the second name fails.
**Setup:** `a` and `b` sharing.
**Steps:** `SELECT * FROM a`; `SELECT * FROM b`; `SELECT usr FROM b WHERE amount > 1`.
**Expected:** all three succeed and `a` and `b` return identical row sets. The third proves `b`'s
schema is resolvable by column name, not only by `*`.
**Vacuity:** V-before — `a`'s read is the control; if it also fails the server is broken and the case
says nothing about sharing.

### LIFE-087 — The second name's schema is renamed to it
**Intent:** `ViewCatalog.schemas()` re-keys by registered name — the layer the round-2 fix landed in.
**Falsifier:** `b`'s advertised schema carries `a`'s name.
**Steps:** Fetch the Flight SQL catalogue (`getTables` / the SDK's schema listing) and inspect the
schema name reported for `b`.
**Expected:** `b`. Both `a` and `b` appear, each with its own name and identical field lists.
**Vacuity:** Assert `a`'s entry is also present and correctly named.

### LIFE-088 — Dropping one shared name leaves the other correct — under load
**Intent:** LIFE-064 with ingest running, because the interesting failure is the drop unwinding
`views.remove(name)` while the shared computation is writing.
**Falsifier:** `b` stops advancing, or serves rows from a half-torn-down view.
**Setup:** 500 000-row feed; `a` and `b` sharing; ingest in flight.
**Steps:** Record `b`'s ROWS IN. `drop a` mid-ingest. Wait 10 seconds. Read `b` and record ROWS IN.
**Expected:** `b` advanced; its read succeeds; no exception on the server. `a` gone.
**Vacuity:** V-rows on `b` across the drop.

### LIFE-089 — `--keys 1` and `--keys 0,1` share a fingerprint and the caller gets the wrong keying
**Intent:** The case the brief singles out, and the one this area exists to find. The key columns are
not in the fingerprint (LIFE-035), and the sharing path never looks at them.
**Falsifier:** The second registration produces a view keyed as it asked, or is refused.
**Setup:** Fresh registry. CSV arranged so that two distinct `usr` values share an `amount`: rows
`(u1, 100)` and `(u2, 100)` both present, plus `(u3, 7)`.
**Steps:** `register ka --sql "$S1" --keys 1` (keyed on `amount`). Then
`register kb --sql "$S1" --keys 0,1` (asking for the pair). Feed; read both; count rows.
**Expected:** identical fingerprints; `QueryRegistry.size() == 1`. Both `ka` and `kb` read the **same
`ServedView`, keyed on `amount` alone**. So both return **2** rows — `amount = 100` (holding whichever
of u1/u2 committed last) and `amount = 7` — where `kb`'s caller asked for a keying that would give
**3**. Assert the count is 2 and record which `usr` survives under `amount = 100`.
**Vacuity:** Register `kc` with `--keys 0,1` and *different* SQL (`WHERE amount > 0`, matching every
row) in the same run, and assert it returns **3** rows. That is the answer `kb` asked for and did not
get, and without it "2 rows" is not obviously wrong.

### LIFE-090 — The shared path skips the key-column bounds check entirely
**Intent:** `start(...)` validates `ordinal < schema.fieldCount()`. The sharing path returns before
`start` is called, so an out-of-range key is accepted silently.
**Falsifier:** The out-of-range key is refused on the shared path (it is refused on the fresh path —
LIFE-030).
**Setup:** `a` registered with `S1 --keys 0`. `S1` has 2 output columns.
**Steps:** Register `b` with the same `S1` and `--keys 99`.
**Expected:** **succeeds**, `RUNNING`, shared fingerprint, no error. The same `--keys 99` on a fresh
registration (different SQL) raises `IllegalArgumentException` naming the width. Assert both in the
same run.
**Vacuity:** The fresh-path refusal is the control; without it the acceptance looks like a global
absence of validation rather than a path-dependent one.

### LIFE-091 — The shared path ignores the second registration's retention
**Intent:** `register(name, sql, keys, principal, retention)` passes `retention` only to `start(...)`.
The sharing path journals it and discards it.
**Falsifier:** The shared view's retention reflects the second registrant's request.
**Setup:** In-process. `a` registered with `Retention.ofAge(Duration.ofHours(8))`.
**Steps:** Register `b` with the same SQL and `Retention.ofAge(Duration.ofMinutes(5))`. Inspect
`registry.require("b").view().retention()`.
**Expected:** 8 hours — `a`'s. `b`'s caller asked for 5 minutes and silently received 8 hours. Then
restart from the journal, where `b`'s entry records 5 minutes, and record which registration replays
first and therefore which retention the shared view ends up with. If `b` replays first, the retention
**changes across a restart** with no change to any configuration.
**Vacuity:** Register `c` with 5 minutes and different SQL and assert *its* view reports 5 minutes,
proving the setter works.

### LIFE-092 — Two principals with different row filters do not share
**Intent:** The safety property that makes implicit sharing acceptable at all. `QueryFingerprint.of`
takes the sorted filter list, "so that a future change to plan rendering cannot silently stop
distinguishing two principals".
**Falsifier:** A restricted and an unrestricted principal land on one computation — the exact failure
the `register` comment records.
**Setup:** In-process registry with a policy giving `p1` a `region = 'EU'` filter and `p2` none.
**Steps:** Register the same SQL as each, under two names. Compare fingerprints and
`QueryRegistry.size()`.
**Expected:** different fingerprints; `size() == 2`; two lane threads. Then read each name as its own
principal and assert `p1` sees strictly fewer rows, hand-counted from the fixture.
**Vacuity:** Register a third name as `p2` and assert it *does* share with `p2`'s first — proving
sharing still works when the filters match.

### LIFE-093 — A `FAILED` computation is not shared with
**Intent:** `if (existing != null && !existing.state().isTerminal())`. A new registration of the same
SQL must not attach to a dead computation.
**Falsifier:** The new registration returns the failed query and reports `FAILED`.
**Setup:** `a` registered and driven to `FAILED`.
**Steps:** Register `b` with the same SQL. Inspect both states and both fingerprints.
**Expected:** `b` takes the fresh path, is `RUNNING`, and `byFingerprint.put(fingerprint, b)`
**overwrites** the entry `a` still occupies. So `pravaha queries` lists `a FAILED` and `b RUNNING`
with the **same fingerprint** — two computations, one fingerprint, which the listing gives an operator
no way to tell apart. Assert both rows and their identical fingerprint strings.
**Vacuity:** V-before — `a` shown `FAILED` with that fingerprint before `b` is registered.

### LIFE-094 — A `DROPPED` computation is not shared with
**Intent:** The other terminal state, which cannot normally be reached because `drop` removes the
fingerprint entry — unless the drop was of one name among several.
**Falsifier:** A registration attaches to a dropped computation.
**Steps:** `a` and `b` sharing. `drop a` (computation survives, held by `b`). `drop b` (released,
fingerprint removed). Register `c` with the same SQL.
**Expected:** `c` takes the fresh path, `size() == 1`, starts empty. Assert `byFingerprint` was empty
between the two drops and the registration by observing `c`'s ROWS IN restarting from 0.
**Vacuity:** LIFE-079 is the contrast: the same command inherits state when a holder remains.

### LIFE-095 — Ten registrations of one query cost one lane
**Intent:** The claim at the scale it is sold at.
**Falsifier:** Threads or memory scale with the name count.
**Setup:** Fresh server; thread count, RSS recorded.
**Steps:** Register `n1 … n10`, all with `S1`. Record after each.
**Expected:** one `pravaha-query-*` thread after `n1`, unchanged through `n10`. RSS growth per extra
name is the cost of a map entry and a catalogue entry only — state the observed delta. All ten read
successfully and return identical rows. ROWS IN identical across all ten listing rows.
**Vacuity:** Register `m1 … m10` with ten genuinely different queries and assert threads rise to
eleven — the comparison is the case.

### LIFE-096 — Ten shares dropped one at a time release on the tenth
**Intent:** The refcount at scale, and the checkpoint-directory orphan (LIFE-065) at scale.
**Falsifier:** The lane is released early, or never.
**Steps:** From LIFE-095's state, drop `n1 … n10` in order, checking threads, listing and a read of a
surviving name after each.
**Expected:** the lane survives drops 1–9; every surviving name still reads; it is released on drop
10. Checkpoint directory: `n1`'s directory is the only one ever created and
`deleteCheckpointsOf("n10")` is what runs at the end, so `n1`'s directory is orphaned. Assert the
directory listing.
**Vacuity:** A read of a surviving name after each of the nine drops, returning the recorded row set.

### LIFE-097 — The shared second name is journalled and recovers
**Intent:** The sharing path's `journalRegistration` call, added because "it vanished at the next
restart while the node reported 'recovered 2 of 2'." Round 2 lists this as pending re-verification.
**Falsifier:** After a restart, only one of the two names exists, or `Recovery` reports 2 recovered
while only 1 is listed.
**Setup:** `a` and `b` sharing, journal on.
**Steps:** Restart the server. `pravaha queries`. Read both names.
**Expected:** `Recovery[2 recovered, 0 refused]`; two rows listed with one shared fingerprint;
`size() == 1`; both names readable. Assert the *read*, not only the listing — the two previously
diverged.
**Vacuity:** V-before — both names listed and readable before the restart.

### LIFE-098 — A journal-append failure on the shared path unwinds the name
**Intent:** The shared path's unwind: `existing.removeName(name)`, `byName.remove`, `views.remove`,
rethrow — "Without it a refusal the client could see left the name held and the shared computation
pinned open by a registration that, as far as its caller knew, had failed."
**Falsifier:** After the failed registration, the name is listed or the refcount is inflated.
**Setup:** `a` registered and sharing-eligible; journal made unwritable.
**Steps:** Register `b` with the same SQL. Capture the error. Then make the journal writable, drop
`a`, and check whether the computation is released.
**Expected:** `b` raises `PRV-8006`. `pravaha queries` lists only `a`. Critically: dropping `a` must
release the lane — if `b`'s name were still in the `RegisteredQuery.names` set, `removeName` would
return false and the computation would be pinned open forever. Assert the thread count returns to
baseline.
**Vacuity:** The drop-and-release check is the real assertion; "b is not listed" alone would pass with
the refcount still inflated.

### LIFE-099 — A fresh-path journal failure leaves nothing running
**Intent:** The fresh path's unwind: `byName.remove`, `byFingerprint.remove`, `views.remove`,
`query.close()`, rethrow — "the client is told the registration failed, the computation serves rows
regardless, and nothing will bring it back after a restart."
**Falsifier:** A lane thread survives a failed registration, or the view answers.
**Setup:** Empty registry, journal unwritable, thread count recorded.
**Steps:** Register `v1`. Capture the error. Check listing, thread count, and a read of `v1`.
**Expected:** `PRV-8006`; listing empty; thread count at baseline; read fails with `PRV-4023`. Repeat
20 times and assert the thread count is still at baseline.
**Vacuity:** Make the journal writable and register once successfully, asserting the thread count
rises — proving the counter responds.

### LIFE-100 — Sharing is visible to an operator
**Intent:** `OPERATIONS.md` lists "Shared fingerprints | `pravaha queries`, console | The sharing
claim holding — or not". Check the operator can actually see it.
**Falsifier:** Nothing in any shipped surface distinguishes two names on one computation from two
computations.
**Setup:** `a` and `b` sharing; `c` separate.
**Steps:** `pravaha queries`. Then the Flight LIST action's raw result. Then the console's query list
if reachable.
**Expected:** the fingerprint column makes `a` and `b` visibly equal and `c` different. Record whether
anything reports the *number of computations* (`QueryRegistry.size()`) as distinct from the number of
names — no shipped surface is expected to, so an operator must compare fingerprints by eye across a
list that may be long. Note also LIFE-093: two computations can share a fingerprint, so equality of
fingerprints is necessary and not sufficient.
**Vacuity:** `c`'s differing fingerprint is the control.

---

## §11 — The four read-consistency modes (LIFE-101 … LIFE-116)

Four modes × four conditions, written out. The precondition established in fact 3: **no transport
carries a consistency mode**, so cases 101–116 are run in-process against
`ServedView.get(Consistency, Duration, Object...)` unless they say otherwise, and each records
whether the mode is reachable from a shipped client at all.

### LIFE-101 — `Latest` during ingest sees uncommitted work
**Intent:** `readLatest` consults `pending` first. The point of the mode.
**Falsifier:** `Latest` and `Consistent` return the same thing while changes are staged.
**Setup:** In-process `ServedView`, rows applied but not committed.
**Steps:** Apply `(u1, 300)` at frontier 10s without committing. Read with `Latest` and with
`Consistent`.
**Expected:** `Latest` → `found = true`, values `[u1, 300]`, `frontier = appliedFrontier = 10s`,
`staleness = 0`, `frontierComplete = false`. `Consistent` → `found = false`,
`frontier = committedFrontier = Long.MIN_VALUE`, `staleness = 10s - MIN_VALUE` clamped by
`Math.max(0, …)`. Record the actual staleness number, which for an uninitialised committed frontier
is arithmetically enormous.
**Vacuity:** Both modes read in the same instant against the same view; the difference is the case.

### LIFE-102 — `Latest` while paused returns the frozen state, not a stale-marked one
**Intent:** A paused query stops applying, so `pending` empties at the last commit and `Latest`
degenerates to `Consistent` — with `frontierComplete = true`, which reads as "fully fresh".
**Falsifier:** `Latest` signals the pause in any way.
**Setup:** Query paused after a commit.
**Steps:** Read with `Latest`; read with `Consistent`.
**Expected:** identical results, both `frontierComplete = true`, both `staleness = 0`. Nothing in the
`ViewResult` says the query is paused; the staleness is zero because the view is consistent with
itself, not with the world. Record that a dashboard cannot tell a paused query from a current one
through the read path.
**Vacuity:** Compare with the same reads against a running query with rows in flight (LIFE-101), where
`frontierComplete` is false.

### LIFE-103 — `Latest` after a drop is unreachable
**Intent:** The view is removed from the catalogue, so there is nothing to call `get` on.
**Falsifier:** A `Latest` read succeeds after the drop.
**Steps:** Hold a `ServedView` reference across a drop (in-process) and read it with `Latest`; and
separately, read by name.
**Expected:** by name → `PRV-4023`. By held reference → the read **succeeds**, returning whatever the
closed computation last committed, forever. That is precisely the failure `ViewCatalog.remove`'s
javadoc describes, reachable by anything holding a reference — record whether any shipped code path
does.
**Vacuity:** V-before — the by-name read succeeds before the drop.

### LIFE-104 — `Latest` against a query that has never received a row
**Intent:** The empty case, which must be "not found", not an error and not a hang.
**Setup:** Registered query over a stream with no data.
**Steps:** Read any key with `Latest`.
**Expected:** `found = false`, `frontier = Long.MIN_VALUE` (`appliedFrontier` initial),
`staleness = 0`, `frontierComplete = true` (because `pending` is empty). Record that a view which has
never seen anything reports itself as complete and fresh.
**Vacuity:** Apply and commit one row and re-read, asserting `found = true` — proving the view works.

### LIFE-105 — `Consistent` during ingest returns the last commit and reports the lag
**Intent:** The default, and the only mode under which two views reconcile.
**Falsifier:** `staleness` is 0 while changes are pending.
**Setup:** Committed at 10s, further rows applied at 20s uncommitted.
**Steps:** Read with `Consistent`.
**Expected:** the 10s values; `frontier = 10s`; `staleness = 20s - 10s = 10_000_000_000` nanos;
`frontierComplete = true`.
**Vacuity:** Commit at 20s and re-read: same values or newer, `staleness = 0`. The change in staleness
is the assertion.

### LIFE-106 — `Consistent` while paused reports zero staleness on frozen data
**Intent:** The most misleading combination in the matrix. `appliedFrontier` and `committedFrontier`
are equal because nothing is arriving, so the view claims to be perfectly fresh while being minutes
behind the world.
**Falsifier:** Staleness grows with wall-clock time during the pause.
**Setup:** Query paused after a full commit; a control query still running.
**Steps:** Read `Consistent` immediately after the pause, then after 60 seconds.
**Expected:** both reads identical, `staleness = 0` both times. The control's frontier has advanced by
roughly 60 seconds of event time in the same interval. Record the divergence between the two views'
`committedFrontier` values — this is the number that tells the truth, and no CLI surface exposes it.
**Vacuity:** The control's frontier advancing is what makes the paused view's zero staleness a
finding rather than a tautology.

### LIFE-107 — `Consistent` after a drop
**Steps:** As LIFE-103 with `Consistent`.
**Expected:** by name `PRV-4023`; by held reference, the last committed rows are served indefinitely
with `staleness = 0` and `frontierComplete = true` — the most confident possible presentation of data
nothing is maintaining.
**Falsifier:** Any staleness signal distinguishes it.
**Vacuity:** V-before — the by-name read succeeds beforehand.

### LIFE-108 — `Consistent` against a query that has never received a row
**Steps:** Read any key with `Consistent` on a view that has never committed.
**Expected:** `found = false`; `frontier = Long.MIN_VALUE`; `staleness = Math.max(0, MIN_VALUE -
MIN_VALUE) = 0`; `frontierComplete = true`. Then run the same read through `pravaha query` (the
shipped path, which is always `Consistent`) and assert it returns **0 rows** rather than an error.
**Falsifier:** An error, or a non-zero row count.
**Vacuity:** Commit one row and re-read through both surfaces.

### LIFE-109 — `AtLeast` during ingest blocks until the frontier arrives, then reads
**Intent:** Read-your-writes. `awaitFrontier` spins with `LockSupport.parkNanos(100_000)`.
**Falsifier:** The read returns before the frontier is reached, or returns the pre-commit value.
**Setup:** In-process view committed at 10s; a second thread that commits 20s after a 500ms delay.
**Steps:** Read with `AtLeast(20s)` and a 5-second timeout, timing it.
**Expected:** the call blocks ~500ms then returns the 20s values, `frontier = 20s`, `staleness = 0`.
Record the measured block time.
**Vacuity:** Read with `AtLeast(10s)` — already satisfied — and assert it returns in under a
millisecond. The difference between the two timings is the case.

### LIFE-110 — `AtLeast` while paused times out with an actionable message
**Intent:** The bounded wait exists because "a hung read is indistinguishable from a hung engine".
A pause is exactly the condition that triggers it.
**Falsifier:** The read hangs past its timeout, or the message does not name both frontiers.
**Setup:** Query paused, committed through 10s.
**Steps:** Read with `AtLeast(50s)` and a 2-second timeout.
**Expected:** `PRV-4021` (`SERVING_READ_TIMED_OUT`) after ~2 seconds, message "view 'v' is committed
through 10000000000 and the read asked for 50000000000, which it did not reach within PT2S. The
source may be idle, or behind." Assert the elapsed time is within 10% of the timeout — the spin loop
checks the deadline before parking, so it should not overshoot.
**Vacuity:** The same read with a 2-second timeout against a **running** query that will reach 50s
must succeed, proving the timeout is not universal.

### LIFE-111 — `AtLeast` after a drop
**Steps:** By name → `PRV-4023`. By held reference with `AtLeast(future)` → the frontier will never
advance, so it times out with `PRV-4021`.
**Expected:** exactly that. Note the timeout message says "The source may be idle, or behind", which
is wrong: the query is dead. Record the misleading diagnosis.
**Falsifier:** The read returns successfully, or hangs unbounded.
**Vacuity:** V-before — an `AtLeast` at an already-reached frontier succeeds before the drop.

### LIFE-112 — `AtLeast` against a query that has never received a row
**Intent:** `committedFrontier` is `Long.MIN_VALUE`, so any `AtLeast(t)` with `t > MIN_VALUE` blocks.
**Steps:** `AtLeast(0)` with a 2-second timeout on a view that has never committed.
**Expected:** `PRV-4021` after 2 seconds — `MIN_VALUE < 0`, so the wait is entered. Then
`AtLeast(Long.MIN_VALUE)` returns immediately with `found = false`. Record that the only frontier a
never-committed view satisfies is `Long.MIN_VALUE`, which no caller would think to pass.
**Falsifier:** `AtLeast(0)` returns immediately.
**Vacuity:** Both variants run; the immediate one proves the loop is entered by comparison.

### LIFE-113 — `AsOf` during ingest is refused with `PRV-4020`
**Intent:** The refusal is unconditional and deliberate: "answering with the current value would be
the worst possible response to an audit question."
**Falsifier:** Any `AsOf` read returns a value, at any frontier, under any condition.
**Setup:** View with committed data at 10s and 20s.
**Steps:** `AsOf(15s)`; `AsOf(10s)` (a frontier that *was* committed); `AsOf(20s)` (the current one);
`AsOf(0)`.
**Expected:** all four `PRV-4020` (`SERVING_NO_HISTORY`), message "view 'v' cannot answer as of
frontier N: a view holds the present, and the past lives in checkpoints. Read the checkpoint at that
frontier instead". Note `AsOf(currentFrontier)` is also refused — a caller asking for the present by
timestamp gets nothing.
**Vacuity:** All four, because the "current frontier" case is the one a naive implementation would
special-case.

### LIFE-114 — `AsOf` while paused is refused identically
**Steps:** `AsOf(committedFrontier)` on a paused view.
**Expected:** `PRV-4020`. The pause is irrelevant; the refusal precedes any state inspection.
**Falsifier:** A different code or message.
**Vacuity:** Compare the message string with LIFE-113's, character for character — one error code with
two messages is an `ERRC` finding.

### LIFE-115 — `AsOf` after a drop, and whether the checkpoint it points at exists
**Intent:** The refusal's advice is "Read the checkpoint at that frontier instead." After a drop,
`deleteCheckpointsOf` has removed them, so the advice is unfollowable.
**Falsifier:** The advice is followable — i.e. a checkpoint at that frontier still exists.
**Setup:** Checkpointing on; a query that has checkpointed; then dropped.
**Steps:** Assert the checkpoint directory is gone. Then attempt `AsOf` by name and by held reference.
**Expected:** by name `PRV-4023`; by reference `PRV-4020` advising a checkpoint that no longer exists.
Record that no shipped surface reads a checkpoint as of a frontier at all — enumerate any that does.
**Vacuity:** Assert the directory existed before the drop.

### LIFE-116 — The SDK's `defaultConsistency` never reaches the server
**Intent:** `ClientOptions.defaultConsistency` defaults to `CONSISTENT`, has a builder setter, a
getter and a test asserting all four enum values exist — and is referenced by nothing else in the
SDK's main sources. A client setting `LATEST` believes it is asking for something.
**Falsifier:** Any `Consistency` value appears in a Flight request's headers, ticket or command body.
**Setup:** SDK client configured with `defaultConsistency(Consistency.LATEST)`; a server with
uncommitted rows staged.
**Steps:** (a) `grep -rn "defaultConsistency" sdk/pravaha-sdk-java/src/main/java` and enumerate the
uses. (b) Capture the Flight traffic for a `query` call and inspect it for any consistency field.
(c) Issue the same query with `LATEST` and with `CONSISTENT` against a view with staged uncommitted
rows and compare the results.
**Expected:** (a) `ClientOptions` only — a field, a getter, a builder setter, and a `toString`.
(b) nothing on the wire. (c) **identical** results, both reflecting the committed state, because
`ViewQuery.run` reads `view.scan()`. Record that three of the four documented modes are unreachable
from every shipped client, and that the SDK's API advertises all four.
**Vacuity:** (c) is only meaningful if uncommitted rows exist at the moment of both reads — assert
`pendingChanges() > 0` on the server side, or the two modes would agree trivially.

---

## §12 — Restart at each lifecycle point (LIFE-117 … LIFE-125)

Each case: bring the query to a state, restart the server, and ask what came back. `recover(...)`
re-registers from the journal with **authorization checked again**, and state is never restored —
"a restart costs a warm-up rather than an outage".

### LIFE-117 — Restart with a running query
**Falsifier:** The query is absent after the restart, or comes back with its state.
**Setup:** Journal on. `v1` running with a known committed row set.
**Steps:** Record the row set and ROWS IN. Restart. `pravaha queries`; read `v1` within 100ms; read
again after the feed refills.
**Expected:** `Recovery[1 recovered, 0 refused]`; `v1 RUNNING`; ROWS IN restarts at 0; the immediate
read returns **0 rows**; the later read returns the row set again once the source is re-read from its
configured start position. State both.
**Vacuity:** V-before — the pre-restart row set is recorded and non-empty.

### LIFE-118 — Restart with a paused query
**Intent:** `PAUSED` is not journalled — `RegistryJournal.Entry` carries name, SQL, keys, owner,
retention and parameters. There is no state field.
**Falsifier:** The query comes back `PAUSED`.
**Setup:** `v1` paused.
**Steps:** Restart. `pravaha queries`.
**Expected:** `v1 RUNNING` — the pause is silently undone by the restart, and a query an operator
deliberately stopped starts consuming again. Record it: there is no way to express "stay paused
across a restart".
**Vacuity:** V-before — `PAUSED` shown in the listing immediately before the restart.

### LIFE-119 — Restart with a dropped query
**Falsifier:** The dropped query returns.
**Setup:** `a` and `b` registered; `drop a`.
**Steps:** Restart. Listing.
**Expected:** only `b`. `Recovery[1 recovered, 0 refused]`. The journal's drop record suppresses `a`
during replay.
**Vacuity:** V-before — both listed before the drop, one after.

### LIFE-120 — Restart with a failed query
**Intent:** A failed query is journalled (it registered successfully). Replay re-registers it fresh.
**Falsifier:** It comes back `FAILED`, or does not come back.
**Setup:** `v_failed` in `FAILED`, its failure cause still present in the environment (e.g. the MIN
retraction of `INCR-010` will recur).
**Steps:** Restart. Listing. Then let the source deliver the offending row again.
**Expected:** `v_failed RUNNING` after the restart, then `FAILED` again when the cause recurs — a
crash loop across restarts with no backoff and nothing recording the repetition. Record the number of
restarts needed to fail again, and whether `failure()` from the previous life is available anywhere.
**Vacuity:** V-before — `FAILED` before the restart, `RUNNING` immediately after.

### LIFE-121 — Restart with two names sharing one computation
**Intent:** Both journal entries replay; the second must take the sharing path again.
**Falsifier:** Two computations after the restart, or one name missing.
**Setup:** `a` and `b` sharing.
**Steps:** Restart. Listing; `size()`; read both.
**Expected:** two names, one fingerprint, `size() == 1`, both readable. Record the replay order from
the journal and whether it matches the original registration order — it decides which name's key
columns and retention the shared view takes (LIFE-091).
**Vacuity:** V-before — both readable beforehand, so "both readable" is a restoration.

### LIFE-122 — Restart when the owner is no longer a known principal
**Intent:** `recover` refuses an entry whose owner cannot be resolved: "there is nobody to authorize
it as."
**Falsifier:** The query comes back unauthorized.
**Setup:** `v1` registered by principal `alice`; `alice` removed from the deployment's principal
source.
**Steps:** Restart. Inspect `Recovery`.
**Expected:** `Recovery[0 recovered, 1 refused]`, the refusal reading "its owner 'alice' is not a
principal this deployment knows, so there is nobody to authorize it as". `v1` is not listed and does
not answer. Assert the refusal reaches an operator-visible log, since the registry's javadoc calls it
"a view some client is about to ask for".
**Vacuity:** A second query owned by a still-known principal must recover in the same restart,
proving the replay ran.

### LIFE-123 — Restart when the owner has lost read access to the source
**Intent:** "Replaying blindly would make the journal a way to keep an entitlement after it was
revoked, by having registered before it was."
**Falsifier:** The query recovers.
**Setup:** `v_pay` registered by `hr` over `payroll`; policy changed to deny `hr` on `payroll`.
**Steps:** Restart. Inspect `Recovery` and the listing.
**Expected:** refused, with the `register:source` reason. `v_pay` absent and not answering.
**Vacuity:** A query by the same principal over `txn`, still permitted, recovers in the same restart.

### LIFE-124 — One unrecoverable entry does not stop the rest
**Intent:** "a deployment recovering forty queries should not lose thirty-nine because the fortieth
names a stream that has since been removed."
**Falsifier:** Recovery stops at the first failure.
**Setup:** 40 journalled registrations; remove one stream's declaration from the configuration and
make one owner unknown.
**Steps:** Restart. Inspect `Recovery`.
**Expected:** `Recovery[38 recovered, 2 refused]`, with two distinct reasons. All 38 readable. Assert
the exact counts.
**Vacuity:** Assert the 38 by reading each, not by trusting the count — a `Recovery` record is the
registry's own claim about itself.

### LIFE-125 — Restart mid-ingest loses no registration and duplicates none
**Intent:** The journal is append-only and `registerWithoutJournalling` suspends the journal during
replay. A restart during a burst of registrations is where a double-append would show.
**Falsifier:** A name appears twice in the journal, or a recovered registration re-appends and grows
the journal on every restart.
**Setup:** Journal on. Record its byte size.
**Steps:** Register 20 queries. Record the journal size. Restart three times without any further
registration, recording the size each time. Assert the listing after each.
**Expected:** 20 names after every restart; the journal size **unchanged** across the three restarts
(replay must not append). If it grows, the journal grows unboundedly with restart count — record the
per-restart delta and whether compaction exists.
**Vacuity:** Register one more query between restart 2 and 3 and assert the size grows by exactly one
entry — proving the size measurement is sensitive.

---

## §13 — Failure (LIFE-126 … LIFE-130)

### LIFE-126 — A row that an operator refuses puts the query in `FAILED`
**Intent:** `accept` catches `PravahaException`, calls `fail(e)` and rethrows; `fail` sets
`failure` and `state = FAILED`. Reaching it needs a row the operator refuses — the `MIN` retraction
of `INCR-010` is the cleanest, via a Delta source's REMOVES phase.
**Falsifier:** The query keeps reporting `RUNNING` after the refusal, or the failure is swallowed.
**Setup:** A Delta-sourced stream; registered query `v_min` = windowed `MIN(amount)`. Delta version 2
removes a row.
**Steps:** Let version 1 ingest; assert `RUNNING` and a readable view. Let version 2's REMOVES phase
deliver the `-1`. `pravaha queries`.
**Expected:** `v_min FAILED`. `RegisteredQuery.failure()` present, carrying `PRV-3020` and the
"ordered multiset per group" message.
**Vacuity:** V-before — `RUNNING` with a non-zero ROWS IN and a readable view immediately before.

### LIFE-127 — A failed query's view keeps answering, and nothing says it is dead
**Intent:** `fail` touches only `state` and `failure`. The view stays in the `ViewCatalog` at the
frontier it reached, and a read carries `staleness = 0` and `frontierComplete = true` (LIFE-106's
shape).
**Falsifier:** The read fails, or the result carries any signal of the failure.
**Steps:** Read `v_min` after LIFE-126. Read again 60 seconds later.
**Expected:** both reads succeed, identical rows, no warning. The only place the failure is visible is
`pravaha queries`' STATE column. Record whether the read path, the SDK's `QueryResult`, or the
subscription carries any failure indication — none is expected to.
**Vacuity:** V-control — a healthy query read in the same instant returns advancing data, so
"identical rows" means frozen and not merely stable.

### LIFE-128 — `failure()` is not reachable from any shipped surface
**Intent:** `RegisteredQuery.failure()` holds the `PravahaException` that killed the query. The LIST
action returns name, state, SQL, fingerprint and rowsIn — **not** the failure.
**Falsifier:** Any CLI, REST or Flight response carries the cause.
**Steps:** With `v_min` `FAILED`: `pravaha queries`; the raw Flight LIST result; `/api/v1/queries`
(which has no listing endpoint); the console API if reachable.
**Expected:** `FAILED` and nothing more. An operator learns *that* it failed and must go to the server
log for *why*. Record the same for `lastCheckpointFailure()` and `checkpointFailures()`, which have
the same problem and the same javadoc justification ("a log is the wrong shape to answer it").
**Vacuity:** Assert `failure()` is present in-process, so the absence is a surfacing gap and not an
absent cause.

### LIFE-129 — A subscriber to a failing query has its stream ended
**Intent:** `streamSubscription`'s loop exits on `query.state().isTerminal()`, and `FAILED` is
terminal. Same path as the drop (LIFE-066), same silence.
**Falsifier:** The subscriber receives an error naming the failure, or keeps waiting forever.
**Setup:** `pravaha subscribe --view v_min` running and printing; then trigger the failure.
**Steps:** Observe the subscriber.
**Expected:** the stream ends cleanly within ~200ms of the state change, exit 0, with no explanation.
A subscriber cannot distinguish a dropped query, a failed query and a completed stream.
**Vacuity:** V-control — the subscriber must be printing at a recorded rate before the failure.
Also, note the server prints `SRVDBG starting subscription on …` and `SRVDBG <t> writing <n>` to
stdout on every subscription and every batch (`PravahaFlightSqlProducer.streamSubscription`); capture
the server's stdout and record this debug output as a defect in its own right.

### LIFE-130 — A new subscription to a failed query is refused
**Intent:** `RegisteredQuery.subscribe` raises `ILLEGAL_TRANSITION` when `state.isTerminal()`.
Contrast with LIFE-051, where a *paused* query accepts one.
**Falsifier:** The subscription is established and silently delivers nothing.
**Setup:** `v_min` in `FAILED`.
**Steps:** `pravaha subscribe --view v_min`.
**Expected:** `PRV-8003`, "cannot subscribe to 'v_min': it is FAILED". The read path, meanwhile, still
answers (LIFE-127) — so the same query is subscribable-no, readable-yes, which is worth recording as
an inconsistency in what a terminal state means.
**Vacuity:** Subscribe to a healthy query in the same run and assert it establishes, proving the
client works.

---

## Coverage note

**130 cases, the budget, and the budget is right — but three of the thirteen sections found something
structural rather than incremental, and those are worth naming here.**

1. **The key columns are not in the fingerprint (§10).** `--keys 1` and `--keys 0,1` over the same SQL
   are one computation and one view, keyed as whoever registered first asked. The second registrant is
   told `RUNNING`, gets a fingerprint, and reads a differently-keyed view than they specified — with
   no error, no warning and nothing in `pravaha queries` to reveal it. The same path skips the
   key-ordinal bounds check (LIFE-090) and discards the second registrant's retention (LIFE-091). The
   fix is one line — put the key columns and the retention into `QueryFingerprint.of` — and until it
   lands, sharing is a correctness hazard rather than only an efficiency feature.
2. **Three of the four consistency modes are unreachable (§11).** `ViewQuery.run` reads
   `view.scan()`, which is the committed map, so every shipped read is `CONSISTENT` whatever the
   client asked for. The Java SDK advertises all four through `ClientOptions.defaultConsistency`,
   stores the value, and sends nothing. `AsOf` is refused even in-process. `AtLeast`'s timeout message
   ("The source may be idle, or behind") is the only diagnosis a caller gets for a paused, dropped or
   failed query. The area that looked like sixteen combination cases is really one case — "the mode
   does not travel" — and fifteen recordings of what each mode *would* do.
3. **A terminal state means different things to different surfaces (§13).** A `FAILED` query keeps its
   name, keeps its view, keeps answering reads with `staleness = 0` and `frontierComplete = true`,
   refuses new subscriptions, silently ends existing ones, and is replaced in `byFingerprint` by the
   next registration of the same SQL — leaving two computations under one fingerprint (LIFE-093).
   `failure()` holds the cause and no surface returns it.

**What is deliberately not here.** Subscription delivery semantics — ordering, conflation,
backpressure, slow subscribers, reconnection — belong to `STRM` and are touched here only where the
*lifecycle* changes them (LIFE-050, LIFE-051, LIFE-066, LIFE-129, LIFE-130). Checkpoint content and
journal corruption belong to `STATE`; this file asserts only that the right files exist or do not
after a lifecycle verb. The authorization cases in §5 are the lifecycle-shaped subset of `SECX` and
are written to be cross-referenced rather than duplicated — particularly LIFE-040, where round 2's
`mayAdminister`-defaults-to-`mayRead` finding means the answer may be that read access is destroy
access.
