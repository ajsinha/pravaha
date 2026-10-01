# Gate P1 and milestone M2 — end of Wave 2 (E1 Minimal vertical slice)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 2 of 10 — E1 Minimal vertical slice |
| Milestone | **M2 "The DBSP bet is real"** |
| Date | 2026-09-09 |
| Verdict | **PASS — GO on the incremental approach** |

## The M2 decision

This is the go/no-go on §9 of the design: whether Pravaha's execution algebra is Z-sets with
mechanically-derived incremental operators, or conventional hand-written retract streams. It was
scheduled here, before the aggregate and join lifts, precisely so that bad news would arrive in
week 6 rather than week 40 (implementation plan §13).

### Criteria, and the evidence

| Criterion | Required | Measured | |
|---|---|---|---|
| Linear lift rules implemented | filter, project, flatMap, union, distinct | all present, all property-tested | **PASS** |
| Oracle throughput | ≥ 10 000 generated cases per run, ≤ 90 s | **11 200 cases in 3.7 s** | **PASS** |
| Oracle catches a seeded lift bug | yes | 3 seeded bugs, all caught | **PASS** |
| Aggregates and joins tractable within E3/E4 | team judgement | see below | **PASS** |

### The seeded bugs

A property that cannot fail is decoration. Three were planted deliberately and all were caught:

1. **A lift that ignores its query entirely** — caught immediately.
2. **A `DISTINCT` lift emitting on weight change rather than presence change.** Subtle: weight 3 → 4
   is still present and must emit nothing. Caught.
3. **A linear lift that drops retractions** (`.distinct()` applied to the delta). Plausible-looking
   and catastrophically wrong, since every update would be half-applied. Caught by four separate
   properties — `project`, `filter→project`, `view chain`, and the drift check — which is the
   composition property doing its job.

### Tractability judgement

**GO.** The reasoning, stated so it can be held against us later:

- The linear rules turned out to be *simpler* than expected — literally "apply the query to the
  delta, keep no state" — and they cover filter, project, flat-map and union, which is a large
  fraction of real queries on their own.
- `DISTINCT`, the first non-linear operator, needed only the state it already had to consult. Its
  lift is four lines. That is the shape every non-linear operator takes.
- The oracle generalises without modification: adding an aggregate lift means adding a `@Property`,
  not extending the framework. The cost of checking the hard operators is already paid.
- `Lift.byRecomputation` is a working escape hatch. Any construct whose incremental form proves
  impractical can use it and lose that operator's efficiency without losing the query's correctness.

**The residual risk is unchanged and remains R13.** Aggregates need retraction-aware `MIN`/`MAX`
(an ordered multiset per group), and bilinear joins need integrated indexes on both sides. Neither
is written yet. What this gate establishes is that the *method* works and the *oracle* will catch it
if an implementation diverges — not that the remaining implementations are easy.

## Gate P1

| Criterion | Evidence | |
|---|---|---|
| Query runs end to end | `EndToEndQueryTest`: SQL → Calcite → plan → arena execution → file, no mocks | **PASS** |
| Property oracle green | 11 200 cases, every run | **PASS** |
| `pravaha dev` loop under a second | `validate` 0.69–0.82 s cold; `run` 0.92 s end to end | **PASS** |

## Numbers

| | |
|---|---|
| Java tests | 616 |
| Python tests | 28 |
| Modules | 18 |
| Java source files | ~180 |

## Stories delivered

`P1-01` Z-sets · `P1-02` frontiers, D/I · `P1-03` linear lift rules · `P1-04` **the property oracle**
· `P1-05` Calcite integration · `P1-06` interpreted operators · `P1-07` physical plan builder
· `P1-08` execution · `P1-09` plugin SPI and classloader isolation · `P1-10` filesystem plugin
· `P1-12` engine seam and embedded mode · `P1-13` CLI · `P1-14` plugin TCK.

**Not delivered:** `P1-11` Kafka plugin. Deferred to Wave 3 — the filesystem plugin already
exercises every part of the SPI the vertical slice needs, and Kafka's value is breadth rather than
proof.

## Retrospective — what this wave got wrong

**Two real defects in the design's own assumptions, both found by property tests rather than by
reading documentation.** Calcite's default type system caps `DECIMAL` at precision 19 and
`TIMESTAMP` at milliseconds. Left alone, a financial aggregate would have been silently truncated
and sub-millisecond window edges would have landed in the wrong place. Neither would have been found
by an example-based test using ordinary values.

**A structural mistake, caught mid-wave.** The plan IR was first written in `pravaha-sql`, which
would have forced `pravaha-runtime` to depend on Calcite and its thirty transitive dependencies. It
moved to `pravaha-runtime`, with only the two Calcite-importing classes staying behind. Cheap now;
expensive after Wave 3's code generator is written against it.

**A process failure.** `develop` was pushed red once, when the coverage gate correctly caught new
API types without tests. Module-scoped test runs during development are not a substitute for the
full reactor build before pushing.

**The seeded-bug practice keeps earning its place.** Every non-trivial test in this wave had a bug
planted in it before being trusted. That found the row-lifetime violation in a test helper and
confirmed the TCK, the oracle, and the determinism harness are all non-vacuous. It costs minutes
and has now prevented four false-confidence tests across two waves.

## Estimation note

Wave 2 was planned as 14 stories across 3 sprints and delivered 13 plus the CLI's full command set.
Combined with Wave 1's overrun into unplanned work, the pattern is that the plan under-counts
adjacent work rather than that estimates are generous. **Wave 3's estimate should not be revised
down on the strength of two waves landing.** Wave 3 contains the code generator, which is the single
highest-risk item in the whole plan (R2).
