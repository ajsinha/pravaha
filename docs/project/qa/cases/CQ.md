# The continuous query engine — test cases

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Area code `CQ`. Written before execution. The subject is the engine itself rather than the SQL
surface: the incremental core (`pravaha-algebra`), the generated fast path (`pravaha-codegen`), the
lifecycle and sharing rules (`pravaha-registry`), subscriptions, and `pravaha-embedded`.

The first QA round tested SQL semantics through the interpreted path only and said so. Nothing here
assumes any of that carried over.

## Fixtures

**Server.** HTTP 18700, Flight 19700, `allow-anonymous: true`, streams `txn`
(`id:INT64,k:STRING,amount:INT64`) and `dim` (`id:INT64,label:STRING`), both bound to the
`filesystem` source plugin. PID recorded at start, killed by number at the end.

**Input files.** `txn.csv` — ids 1…2 000 005, `k = "k" + (id mod 1000)`, `amount = id`.
`wide.csv` — the same, with `k` 44 characters wide. `txn2.csv` — a prefix of the first.

**In-process harnesses.** Four scratch programs compiled against the shipped
`pravaha-server-…-app.jar` libraries, so they exercise production classes and not test doubles:

| Harness | What it drives |
|---|---|
| `Harness.java` | `QueryRegistry` / `RegisteredQuery.accept` with weights I choose |
| `Feed2.java`   | the server's own feed loop, with `checkHealth()` left in instead of swallowed |
| `Cg.java`      | `FilterProjectGenerator`, `StageCompiler`, `StageUpgradeService`, metaspace |
| `Sub.java`     | `Subscription`, pause/resume/drop, `ViewChange` weights |
| `Alg.java`     | `ZSet`, `Lift`, `IncrementalJoin`, `Integrate`, `Frontier` |

**The arithmetic rule.** Every expected value below is computed by hand in the case, not read off
the engine and blessed.

---

## Part 1 — Incremental correctness

### CQ-001 — a weight that nets to zero removes the key, it does not leave a zero row
**Intent:** the invariant the whole Z-set model rests on. A zero-weight row that is stored rather
than removed makes `isEmpty()` a lie and grows state with every update.
**Setup:** `ZSet.builder()`.
**Steps:** add `R("a",1)` with weight +3, then the same row with weight −3; build.
**Expected:** 3 + (−3) = 0, so `size() == 0`, `isEmpty() == true`, `toString() == "{}"`. No entry
with weight 0.

### CQ-002 — an update is order-independent in the algebra
**Intent:** "an update is not a special kind of record; it is −1 of the old row and +1 of the new"
is the design's central claim. Addition commutes, so the two orders must give the same answer.
**Setup:** state `{R("a",1): +1}`.
**Steps:** apply delta `{−1·R("a",1), +1·R("a",2)}` and separately `{+1·R("a",2), −1·R("a",1)}`.
**Expected:** both give `{R("a",2): +1}`; `equals` is true.

### CQ-003 — multiplicity: +2 then −1 leaves the row present with weight +1
**Intent:** a retraction withdraws *one* contribution, not the key.
**Setup:** `ZSet.of(row, 2)`.
**Steps:** `.plus(ZSet.of(row, -1))`.
**Expected:** 2 + (−1) = 1, so the row is present with weight exactly +1.

### CQ-004 — integrate(+1, −1) is empty and holds no state
**Intent:** `Integrate` is the operator a view is built out of; a retracted row must leave its state.
**Steps:** `Integrate.apply({+1·r})` then `.apply({−1·r})`.
**Expected:** `current()` is `{}` and `stateSize() == 0`.

### CQ-005 — weight arithmetic at the edge of `long`
**Intent:** the weight is a signed 64-bit integer and every operator does arithmetic on it. Silent
wraparound turns a retraction into an insertion.
**Steps:** `ZSet.of(r, Long.MAX_VALUE/2).scale(4)`; `ZSet.of(r, Long.MAX_VALUE).plus(ZSet.of(r, 1))`.
**Expected:** either a refusal or a saturating result. `(2^63−1)/2 × 4` is `2^64 − 4`, which is not
representable; wrapping it to −4 is a retraction of four copies of a row that was inserted
4 611 686 018 427 387 903 times.

### CQ-006 — `Lift.combine` merges two Z-sets under the merge function it is given
**Intent:** a public helper whose signature promises a `BinaryOperator<Long>` over the two weights.
**Steps:** `combine({r:+5}, {r:+3}, Math::max)` and `combine({r:+5}, {r:+3}, (a,b)->a-b)`.
**Expected:** max(5,3) = 5 and 5 − 3 = 2.

### CQ-007 — a retraction withdraws the joined output and frees the index entry
**Intent:** the join is where retract-stream engines historically go wrong, and an index entry that
is not unlinked is unbounded state produced by bookkeeping.
**Steps:** `IncrementalJoin.step({+1·L("a",1)}, {+1·R("a",9)})`, then
`step({−1·L("a",1)}, {})`.
**Expected:** the first emits `{a:1/9: +1}`; the second emits `{a:1/9: −1}`; afterwards
`leftRows() == 0` (the left index entry is gone) and `rightRows() == 1` (the right row was never
retracted).

### CQ-008 — a frontier cannot regress
**Intent:** the only safety property `Frontier` has.
**Steps:** `Frontier.at(100).advanceTo(50)`.
**Expected:** refused with a message naming both positions.

### CQ-009 — is `pravaha-algebra` on the execution path at all?
**Intent:** ADR-013 rests on the DBSP oracle. A correctness proof about code the product does not
run proves nothing about the product.
**Steps:** grep every `src/main` tree outside `pravaha-algebra` for `ZSet`, `Lift`, `Integrate`,
`Differentiate`, `IncrementalJoin`, `Frontier`.
**Expected:** if the algebra is the incremental core, the runtime references it.

### CQ-010 — does `IncrementalOracleTest` cover what it claims to cover?
**Intent:** it says the property is "machine-checkable over the entire operator set".
**Steps:** enumerate the operators it lifts and asserts over.
**Expected:** aggregates and joins — the two operators whose incremental forms are hard — appear.

### CQ-011 — in the runtime: a row with weight −1 removes its contribution from a keyed view
**Intent:** the same property as CQ-001, on the code that actually ships.
**Setup:** `QueryRegistry`, `SELECT id, k, amount FROM txn`, keyed on column 0.
**Steps:** accept `(1,a,10,+1)` and `(2,a,20,+1)`, commit; accept `(2,a,20,−1)`, commit.
**Expected:** two rows then one, `[1, a, 10]` surviving.

### CQ-012 — in the runtime: an update in both orders
**Intent:** CQ-002 against the shipping view. If the two orders disagree the Z-set claim is false
where it matters.
**Steps:** with `(1,a,10)` present, apply `{−1·(1,a,10), +1·(1,a,99)}`; then repeat from scratch
applying the same two rows in the opposite order.
**Expected:** both end with exactly one row, `[1, a, 99]`.

### CQ-013 — in the runtime: multiplicity +2 then −1
**Intent:** CQ-003 against the shipping view.
**Steps:** accept `(1,a,10)` with weight +2, commit; accept the same row with weight −1, commit.
**Expected:** 2 + (−1) = +1, so the row is still there and the view holds one key.

### CQ-014 — `SUM` and `COUNT` under retraction and under multiplicity
**Intent:** the documented payoff of weighted accumulation: "a retraction is handled by the same
arithmetic as an insert".
**Steps:** `SELECT SUM(amount), COUNT(*) FROM txn`. Accept +10, +20, +30; then −20; then +5 with
weight 3; then retract all of it.
**Expected, computed by hand:** (60, 3) → (40, 2) → 40 + 3×5 = 55 and 2 + 3 = 5 → (0, 0).

### CQ-015 — `MIN`/`MAX` under retraction
**Intent:** `GlobalAggregate` throws a named refusal for this. A refusal is acceptable; a refusal
that kills the query silently is not.
**Steps:** `SELECT MIN(amount), MAX(amount) FROM txn`; accept +10, +20; then retract the 20.
**Expected:** a refusal that reaches the caller *and* leaves the query in a state that says so —
`state()` is `FAILED` and `failure()` is populated.

### CQ-016 — incremental result versus batch recomputation over a random weighted stream
**Intent:** the operational property. Drift never announces itself.
**Steps:** 200 pseudo-random changes (seed 20260912) over 10 keys with weights ±1 through
`SELECT id, k, amount FROM txn WHERE amount > 50`, keyed on id; maintain the same relation in a
`TreeMap` by the same rules; compare at the end.
**Expected:** identical. Non-vacuous because the model is computed independently and the run
contains both insertions and retractions.

### CQ-017 — can a retraction be ingested from any source a deployment can configure?
**Intent:** everything above is unreachable if no source can produce a negative weight.
**Steps:** read every `PartitionReader` in `plugins/` for the weight it writes; check the
`--schema` grammar and the CLI for a weight or operation column.
**Expected:** at least one configurable source can deliver weight −1.

### CQ-018 — a continuous unwindowed aggregate keeps its view current
**Intent:** `SELECT SUM(x) FROM stream` is the first query anyone writes.
**Steps:** register it; feed rows; read the view while it runs.
**Expected:** the view holds the running total.

### CQ-019 — a window under retraction
**Intent:** listed in the brief.
**Steps:** register a `TUMBLE` aggregate, feed a row and its retraction.
**Expected:** the window's total drops. (Depends on SQL-039.)

### CQ-020 — a join under retraction, through the registry
**Intent:** the runtime's `SymmetricHashJoin` retraction path.
**Steps:** register a two-stream join and feed the right side through `RegisteredQuery.accept`.
**Expected:** rows reach both sides.

## Part 2 — Code generation

### CQ-021 — generated and interpreted must agree on a NULL in a projected column
**Intent:** `copyField` is documented "Copies one field, preserving nulls". The generated
projection must do the same or the two paths give different answers for the same plan.
**Setup:** input `id:INT64, amount:INT64, note:INT64?`; output `id, note`.
**Steps:** one row with `note` NULL and one with `note = 7` through the generated stage.
**Expected:** the output row for the first is `isNull(note) == true`.

### CQ-022 — does `theDifferentialTestCatchesAGeneratedBug` catch a generated bug?
**Intent:** it is the non-vacuity guard for the whole of R2. A differential test that cannot fail
certifies whatever it compares.
**Steps:** read the method.
**Expected:** it perturbs the *generator* (or the generated source) and asserts the comparison
notices.

### CQ-023 — what does the differential property actually compare?
**Intent:** the class javadoc says "Generated code and the interpreted path are two implementations
of one specification … and this asserts they agree."
**Steps:** read `runInterpreted` and `runGenerated`.
**Expected:** the interpreted side runs the interpreted execution path, and the comparison covers
every projected column.

### CQ-024 — a query that upgrades mid-stream gives the same answers before and after
**Intent:** the differential property that matters operationally.
**Steps:** `AdaptiveStage.stateless` over 64 rows with `amount > 50`; record the ids the
interpreted processor kept; `StageUpgradeService.submit`; wait; run the identical batch again and
record the ids the generated stage emitted.
**Expected, computed by hand:** `amount = 2i` for `i` in 0…63, so `2i > 50` ⇔ `i ≥ 26` ⇔ 38 rows,
ids 26…63. Both lists must equal that.

### CQ-025 — does the upgrade happen on a running server?
**Intent:** several components in this tree are built, tested, documented and never wired.
**Steps:** find every construction of `AdaptiveStage` and `StageUpgradeService` in `src/main`; see
what `QueryExecution.start` builds.
**Expected:** a registered query runs on a stage that can be upgraded.

### CQ-026 — metaspace over ten thousand register/drop cycles (risk R2/R12)
**Intent:** every generated stage is a class; classes live in metaspace; metaspace is not swept by
the ordinary collector.
**Steps:** 200 warm-up compilations, `System.gc()`, baseline; 10 000 generate-compile-drop cycles;
`System.gc()`; measure. Run under `-XX:MaxMetaspaceSize=512m`.
**Expected:** growth far below what 10 000 retained classes would cost. The project's own measured
figures are 266 kB when dropped and 29.5 MB when retained, so anything under ~4 MB is not a leak.

### CQ-027 — `StageCompiler` renames the generated class by `String.replace`
**Intent:** a global textual substitution over generated source is a hazard: it rewrites every
occurrence of the name, including inside other identifiers and inside string literals.
**Steps:** compile a source whose body contains the identifier `Foobar` and the literal `"Foo"`
while the class is named `Foo`.
**Expected:** only the class declaration and its own references are rewritten.

### CQ-028 — a chain of two projections
**Intent:** `generate()` assigns `projection = project.sourceOrdinals()` once per `ProjectOperator`
seen, overwriting rather than composing. Ordinals from an outer projection index its *input*
schema, not the scan's.
**Steps:** build `scan(a,b,c) → project[c,a] → project[a]` and read the emitted projection.
**Expected:** either source that reads base column `a`, or a refusal. Not silently wrong code.

### CQ-029 — an oversized or uncompilable stage is refused, not run
**Intent:** the fallback is what makes codegen survivable.
**Steps:** compile 4 010 lines of filler; compile invalid Java.
**Expected:** `PRV-3102` and `PRV-3100`, the second carrying numbered source.

### CQ-030 — `explain --level codegen` on the server
**Intent:** the only place codegen is reachable from the product.
**Steps:** `POST /api/v1/queries/explain?level=codegen` for a generable and an ungenerable query.
**Expected:** Java for the first, an explanation naming the interpreted fallback for the second.

## Part 3 — Continuous query lifecycle

### CQ-031 — register, pause, resume, drop
**Intent:** the happy path, and the states reported at each step.
**Steps:** each verb over Flight, reading `pravaha queries` between them.
**Expected:** `RUNNING`, `PAUSED`, `RUNNING`, then absent.

### CQ-032 — a paused query drops pushed rows rather than buffering them
**Intent:** the documented guarantee on `RegisteredQuery.accept`.
**Steps:** accept a row, pause, accept a second, resume, accept a third; read the view at each
step.
**Expected:** `accept` returns `false` while paused; the second row never appears; the third does.

### CQ-033 — what a pause does to the server's own ingest path
**Intent:** the server feeds through `PumpingFeed`, not through `accept`. The guarantee documented
on `accept` may not be the guarantee an operator gets.
**Setup:** a 900 000-row input, so the feed is still draining when the pause lands.
**Steps:** register; pause immediately; hold for three seconds; resume; let it finish; count.
**Expected:** whatever happens, the documentation says which. Either rows are dropped (matching the
`accept` contract) or they are not.

### CQ-034 — can a consumer detect the gap a pause left?
**Intent:** a guarantee of "rows are dropped" is only usable if the loss is visible.
**Steps:** after CQ-032, look for a refused-row counter, a watermark discontinuity, or a
subscription signal.
**Expected:** some countable evidence of how much was lost.

### CQ-035 — resume and drop in illegal states
**Intent:** lifecycle transitions must refuse rather than corrupt.
**Steps:** pause twice; resume after drop; drop twice; subscribe to a dropped query.
**Expected:** idempotent where that is sane, refused with a `PRV` code where it is not.

### CQ-036 — re-registering the same SQL after a drop
**Intent:** does a new registration inherit the dropped one's state?
**Steps:** register `kfirst`, let it fill, drop it, register the same SQL under the same name.
**Expected:** a fresh computation that starts from empty and re-reads its source.

### CQ-037 — a continuous query keeps ingesting
**Intent:** the product is a *continuous* query engine. A query that silently stops is the worst
failure shape there is.
**Steps:** register a plain filter/projection over a two-million-row source; watch `rowsIn` and the
view size to completion.
**Expected:** all two million rows are ingested, or the query reports why not.

### CQ-038 — when a lane dies, does anything say so?
**Intent:** `Lane.run` catches `Throwable`, sets `state = FAILED` and exits. Nothing above it polls.
**Steps:** provoke a lane failure on a registered query; read `pravaha queries`, `/api/v1/status`
and the server log.
**Expected:** the query's reported state is not `RUNNING`, or the log says what happened.

### CQ-039 — rows appended to a bound source after registration
**Intent:** a file source that stops at EOF makes every registration a one-shot batch.
**Steps:** register over a three-row file; append two rows; wait; re-read.
**Expected:** either the new rows arrive, or the node says the source is finite and finished.

## Part 4 — Sharing by fingerprint

### CQ-040 — two identical queries are one computation, and the row counts are not doubled
**Intent:** the entire point of hashing the plan.
**Steps:** register the same SQL under `big` and `big2`; list; read both views.
**Expected:** one fingerprint, one `rowsIn`, both names answering identically.

### CQ-041 — dropping one name leaves the other correct
**Intent:** "released when the last name is dropped, not the first."
**Steps:** register two names on one fingerprint; drop one; read the other; drop the second.
**Expected:** the survivor keeps answering; after the second drop the view is gone.

### CQ-042 — `--keys 0` and `--keys 0,1` on the same SQL
**Intent:** `QueryFingerprint.of` hashes the physical plan and the security row filters. Key columns
are not in it, and the shared path calls `existing.addName(name)` without looking at them. If that
is so, one caller is handed a view keyed differently from the one they asked for.
**Setup:** `SELECT k, id, amount FROM txn`, whose column 0 (`k`) is not unique.
**Steps:** register `kfirst` with `--keys 0,1`; register `ksecond`, same SQL, with `--keys 0`;
register `kctl` with SQL that differs only in a no-op `WHERE 1=1` (so a different fingerprint) and
`--keys 0`. Read all three.
**Expected, computed by hand:** the source holds 5 rows with `k` values a, a, b, c, c. Keyed on
`(k,id)` that is 5 distinct keys; keyed on `k` alone it is 3, last write per key winning. `ksecond`
asked for `--keys 0` and must therefore return 3 rows, the same as `kctl`.

### CQ-043 — a key column outside the output, on the shared path
**Intent:** `start()` validates key ordinals against the output schema. The shared path does not
reach `start()`.
**Steps:** register the same SQL a third time with `--keys 99` against a three-column output.
**Expected:** refused, with the same message the unshared path gives.

### CQ-044 — what the register response names
**Intent:** a client that registers `big2` and is told `big` cannot script against it.
**Steps:** read the CLI output of a shared registration.
**Expected:** the name the caller registered.

## Part 5 — Subscriptions

### CQ-045 — a subscriber sees every change exactly once
**Intent:** the core delivery property.
**Steps:** subscribe over Flight to a view being filled at speed; capture 2 000+ rows; check for
duplicates and gaps against the known contiguous id sequence.
**Expected:** every id in the observed range present exactly once.

### CQ-046 — a Flight subscriber can tell an insertion from a retraction
**Intent:** `RegisteredQuery.subscribe`'s contract: "Changes … carry weights: −1 withdraws a row,
which is how a late-data correction reaches a consumer rather than as a special message type."
**Steps:** read `PravahaFlightSqlProducer.writeBatch`, `ArrowSchemas.toArrow` and the Java SDK's
`Row`.
**Expected:** the weight is on the wire.

### CQ-047 — an in-process subscriber can tell an insertion from a retraction
**Intent:** the same contract through the API the wire path is built on.
**Steps:** subscribe in process; commit a +1 and then a −1 for the same row.
**Expected:** two changes, weights +1 and −1.

### CQ-048 — a slow subscriber under the default overflow policy
**Intent:** `SubscriptionOptions.DEFAULT` is `CONFLATE` at 10 000 rows, and the Flight path
hard-codes it. Conflation replaces a buffered change with a newer one for the same key — which for
a consumer that applies weights is not conflation but corruption.
**Steps:** buffer of 2, four changes in one commit on three keys, one of them a retraction of a key
whose insertion is still buffered.
**Expected:** either every change, or a loss the subscriber can see and count.

### CQ-049 — a subscriber that throws
**Intent:** one broken consumer must not become a stream of exceptions on the engine's thread.
**Steps:** subscribe with a consumer that throws; commit; commit again.
**Expected:** detached with a recorded failure; the query keeps running and keeps serving.

### CQ-050 — the query is dropped while a subscriber is attached
**Intent:** the subscriber must be able to find out.
**Steps:** subscribe; drop; inspect `isClosed()`, `failure()` and `subscriberCount()`.
**Expected:** the subscription is closed or failed, and the count falls to zero.

### CQ-051 — subscribing to a dropped query
**Steps:** drop, then subscribe.
**Expected:** refused with a `PRV` code naming the state.

### CQ-052 — what the subscription path writes to standard output
**Intent:** a release build should not print debugging to `System.out`, least of all per batch on a
hot path.
**Steps:** subscribe; read the server's stdout.
**Expected:** nothing but framework logging.

## Part 6 — `pravaha-embedded`

### CQ-053 — can the embedded API run a query?
**Intent:** the brief asks whether it is usable and whether it agrees with the server path.
**Steps:** `javap` the interface; read the shipped example.
**Expected:** some way to submit a query and read an answer.

### CQ-054 — is the embedded engine's configuration honoured?
**Intent:** the example sets `pravaha.runtime.lanes = 4` and prints it back.
**Steps:** read `DefaultPravahaEngine` for what it does with the `Configuration`.
**Expected:** the settings reach something.

### CQ-055 — what plugins an embedded engine has
**Steps:** run the shipped example with the filesystem plugin on the classpath.
**Expected:** the plugin is discovered, or the example does not claim to list plugins.

### CQ-056 — does the embedded path agree with the server path?
**Steps:** run the same query both ways and compare.
**Expected:** identical answers.

### CQ-057 — `/api/v1/status` reports the node's registered queries
**Intent:** the field is named `registeredQueries` and an operator will read it as one.
**Steps:** register five names over three fingerprints; `GET /api/v1/status`.
**Expected:** a number that is either the name count or the fingerprint count.
