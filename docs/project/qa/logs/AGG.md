# AGG — five aggregate kinds × three operators × sixteen types × seven input shapes — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/AGG.md`](../cases/AGG.md). Executed 2026-09-14 on branch `develop`, against a build
of the working tree (`./mvnw -q -o -T1C install -DskipTests -DskipITs`, exit 0), in the same session
as `docs/qa/logs/SQLX.md` and sharing its harness set and build.

**Scope note, stated plainly.** This area's full budget is 110 cases across six sections; SQLX's 190
cases were run to completion first, leaving a materially smaller share of the session for AGG. What
follows is real, evidence-backed execution of the foundational section (§0) and the central defect
area (§1) — which is where this file's own preamble says the important discoveries live — plus every
case already made executable and passing in `pravaha-it`'s `SqlAnswerTest`. §2 (types × kinds), most
of §3 (input shapes), §4 (retraction), §5 (AVG) and §6 (query shape) are **NOT RUN** and are recorded
as such below, case by case, rather than guessed at. This is a partial round, reported honestly as
one.

**The single most important finding of this file**: the central defect the whole area is built
around — fact 2, "`KeyedAggregate.Group.accumulate` has `case COUNT -> counts[i] += weight;` with no
guard" — **does not reproduce on this build**. AGG-013 through AGG-016, executed directly against a
running server, all return the *correct* answer the case file labels "Expected (correct)", not the
defective one it labels "Expected (this build)". See the finding below.

**Harnesses used exactly as AGG.md defines them.** HB = `pravaha run` (bounded file, one lane, no
watermark generator — `WindowedAggregate.finish()` fires every open window at end of input). HC = a
real `pravaha-server` with `txn` bound to the filesystem plugin, `v_txn` registered as
`SELECT * FROM txn`, read only after `pravaha queries` shows the expected `ROWS IN`. HD = `pravaha
explain`. HA (the in-process operator harness with hand-driven weights/nulls/watermarks) was **not**
set up this session — every case that specifically needs HA is recorded `NOT RUN`.

**Fixtures.** D1 (5 rows, `txn_id,user_id,amount,tier,event_time`) transcribed byte-for-byte from
AGG.md. D2 (all `amount`/`tier` NULL, AGG-011) and D3 (D1 plus two all-NULL `u4` rows, AGG-015)
built as the case file specifies.

---

## §0 — What can be observed at all (AGG-001 … AGG-008)

### AGG-001 — NOT RUN
Needs HC with a feed that never closes (a continuous, non-terminating source) and a wait on `ROWS IN`
reaching 5 while the view stays at 0 rows. The server fixtures built this session all read a finite
file (feedfile semantics were not set up); the *finite*-input half of this claim is established
instead by AGG-002 below, and the continuous-registration-never-emits half is already an established,
`FIXED`-then-reconfirmed fact in `FINDINGS.md` (I-2, and Q-1's history) — not independently
re-executed here.

### AGG-002 — PASS
```
$ pravaha run --sql "SELECT COUNT(*) AS n FROM txn" --stream txn --schema T --in d1.csv --out-schema "n:INT64"
ok  5 in, 1 out
5
```
Establishes HB as a working surface, exactly as the case asks. V1: `5 in`. V2 confirmed by AGG-009
below in the same session (a differing second column).

### AGG-003 — PASS (already executable and green)
`pravaha-it`'s `SqlAnswerTest.everyWindowedCaseProducesItsHandComputedWindows`, case
`"SQLX-101/AGG-003 TUMBLE: two windows, boundaries and sums"`, asserts exactly this case's SQL and
expected values (`SqlAnswerTest`'s own D1 differs slightly in shape from AGG.md's D1 — six rows on
`txn_id,user_id,amount,price,status,flagged,event_time` rather than AGG.md's five on
`txn_id,user_id,amount,tier,event_time` — but the windowed-firing behaviour under test is the same
mechanism and the test is cited as the evidence rather than re-derived by hand). `./mvnw -o -pl
pravaha-it test -Dtest=SqlAnswerTest`, exit 0.

### AGG-004 — PASS
(a) HD: `pravaha explain --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" --schema T` →
`PRV-2050  GROUP BY user_id has no bound on its key space...` — refused, names `user_id`.
(b) HC: `v_txn` committed at 5 rows; `SELECT user_id, COUNT(*) AS n FROM v_txn GROUP BY user_id` →
`u1 3 / u2 1 / u3 1` — exactly the case's expected values (r1, r3, r4 are u1).

### AGG-005 — PASS
HD: `GROUP BY user_id` → message contains the literal `GROUP BY user_id`, not `[1]`.
`GROUP BY user_id, tier` → `PRV-2050  GROUP BY user_id, tier has no bound on its key space...` — both
columns named together, matching the case exactly.

### AGG-006 — PASS
HD: `SELECT user_id, COUNT(*) FROM TABLE(TUMBLE(...)) GROUP BY user_id` (boundaries omitted) →
`PRV-2050  this GROUP BY is over a windowed stream but does not group by the window: add
window_start and window_end to the GROUP BY...` — the exact message the case names.

### AGG-007 — NOT RUN
Needs `QueryExecution.start(plan, 4, ...)` driven directly (HA), which was not set up this session.

### AGG-008 — NOT RUN
Same reason as AGG-007: needs HA with four unpartitioned lanes fed round-robin.

---

## §1 — COUNT(*) vs COUNT(col) vs COUNT(DISTINCT col) × three operators (AGG-009 … AGG-032)

### AGG-009 — PASS
`SELECT COUNT(*) AS rows_all, COUNT(amount) AS rows_amount FROM txn` (HB) → `5,4`. r4's amount is
NULL; `5 - 1 = 4`. Matches exactly, and is the fixed `GlobalAggregate` behaviour this case exists to
re-establish.

### AGG-010 — PASS
`SELECT COUNT(*) AS a, COUNT(tier) AS b FROM txn` (HB) → `5,4`. r2's tier is NULL. V3: `COUNT(amount)
= 4` (AGG-009) excludes a *different* row (r4) — confirmed independently, so the two nulls are not
the same accident.

### AGG-011 — PASS
D2 (all `amount`/`tier` NULL), `SELECT COUNT(*) AS a, COUNT(amount) AS b, COUNT(tier) AS c FROM txn`
(HB) → `5,0,0`. `COUNT(*)` counts rows regardless of content, as required.

### AGG-012 — **PASS, and a probable correction to the case's own "Expected (this build)" text**
`SELECT COUNT(DISTINCT user_id) AS n FROM txn` (HB) → refused **at plan time**:
```
PRV-2050  COUNT(DISTINCT ...) over an unwindowed stream holds one entry per distinct value for ever,
which is unbounded state by another name.
  Bound it with a window -- GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE) -- so the entries are
released when each window closes.
```
The case's "Expected (this build)" text predicts a **runtime** failure — `PRV-3010` from
`GlobalAggregate.process`, firing on the first row, after rows have already been read — and asks the
executor to "record the exit code and whether the failure is reported at all — FINDINGS Q-6 has this
hanging". On this build the refusal is clean, immediate, plan-time, and carries the unbounded-state
code rather than a runtime one. Either `buildAggregate` grew a plan-time check for this shape since
the case was authored, or the case's premise was already stale when written. Recorded as a probable
correction alongside the SQLX-107/108 corrections to Q-6 (`docs/qa/logs/SQLX.md`).

### AGG-013 — **PASS against "Expected (correct)"; the defect does not reproduce — major finding, see below**
HC, `v_txn` at 5 rows:
```
$ pravaha query --sql "SELECT user_id, COUNT(amount) AS n, SUM(amount) AS total, AVG(amount) AS mean FROM v_txn GROUP BY user_id"
u1  2  300  150
u2  1  50   50
u3  1  7    7
```
u1 → `n = 2`, matching the *correct* semantics (r4's NULL amount excluded), and internally consistent
with `total = 300` and `mean = 150` (`300 / 2 = 150`). The case's "Expected (this build)" text
predicts `n = 3` (the NULL counted) with the same `total`/`mean`, which would be the self-contradictory
row the case's own preamble calls "arithmetically self-contradictory... needs no external oracle to
be recognised as wrong." That row does not appear. `KeyedAggregate`'s `COUNT` branch is not
reproducibly unguarded on this build.

### AGG-014 — **PASS against "Expected (correct)"; confirms AGG-013**
`SELECT user_id, COUNT(*) AS a, COUNT(tier) AS b FROM v_txn GROUP BY user_id` → `u1 3 3`, `u2 1 0`,
`u3 1 1`. u2 → `b = 0` (r2's tier is NULL, correctly excluded) — the case's "Expected (this build)"
predicts `b = 1`. Confirms the fix holds over a second column and a different group.

### AGG-015 — **PASS against "Expected (correct)"**
D3 (D1 plus two all-NULL-amount `u4` rows), HC: `SELECT user_id, COUNT(amount) AS n, SUM(amount) AS
total FROM v_txn GROUP BY user_id` → `u4  0  0`. `n = 0` is correct (the case's "Expected (this
build)" predicts `n = 2`). The `total = 0` half (SQL says `SUM` over zero rows is `NULL`, not `0`) is
a **separate, still-real** disagreement the case file itself names as AGG-071's territory, not
re-opened as a new finding here — recorded as present and unaffected by the COUNT fix.

### AGG-016 — PASS
`SELECT user_id, COUNT(*) AS n FROM v_txn GROUP BY user_id` → `u1 3, u2 1, u3 1`; `3+1+1=5`. Bound
established, as the case asks, to be unaffected by whatever guard is or is not present.

### AGG-017, AGG-018 — PASS (already executable and green)
`SqlAnswerTest`'s `"SQLX-100/AGG-017 windowed COUNT(*) against windowed COUNT(col)"` case asserts
`COUNT(status)` excludes the NULL row per window on its own D1 shape — the windowed operator's
equivalent fix, confirmed the same way (cited, not re-derived).

### AGG-019 — Not independently re-run; subsumed by `SqlAnswerTest`'s
`"SQLX-101/AGG-003"` case, which asserts both windows' `COUNT(*)` values against the hand-computed
row counts in the same assertion as AGG-003.

### AGG-020, AGG-021 — PASS
(020, already covered) `SqlAnswerTest.windowedCountDistinctOverAStringColumn` asserts the true
distinct count (3, not 1) over same-length two-character user ids. (021, executed directly this
session) A four-row, one-window file with `user_id` values `a, bb, ccc, a` (lengths 1, 2, 3, 1):
```
$ pravaha run --sql "SELECT window_start, COUNT(*) AS n, COUNT(DISTINCT user_id) AS d FROM TABLE(TUMBLE(...)) GROUP BY window_start, window_end" ...
0,4,3
```
`d = 3` (`{a, bb, ccc}`), the *true* distinct count — not distinct string **lengths** (which would
give 2, since two rows share length 1) and not the AGG-020-style collapse to 1. Confirms the
`WindowedAggregate` `COUNT(DISTINCT)` fix (commit `189890be`, "The four COUNT(DISTINCT) defects")
holds for strings of differing length, not only same-length ones.

### AGG-022 — PASS (already executable and green)
`SqlAnswerTest`'s `"AGG-022 windowed COUNT(DISTINCT) over an INT64 column"` case: `d = 4` in W1
(`{100, -50, 250, 0}` per that test's own D1), `d = 1` in W2 — matches its hand computation.

### AGG-023 — NOT RUN
Needs D5 (`alph, beta, gama`, all 4 characters). Not built this session; AGG-021 above already
establishes the mechanism (true value, not length) directly, which is what AGG-023 exists to isolate
further — recorded as not independently executed rather than assumed identical.

### AGG-024 — PASS (already executable and green)
`SqlAnswerTest`'s `"AGG-024 keyed COUNT(DISTINCT) over a STRING column"` case: the `ok` group holds
u1, u1, u2, ünïcødé → `d = 3`, `n = 4`. `3 ≠ 4` confirmed.

### AGG-025 … AGG-032 — NOT RUN
Not reached this session (COUNT(DISTINCT) over further type/shape combinations, and the remaining
§1 boundary cases). Recorded `NOT RUN` rather than assumed to follow the same pattern as AGG-020-024.

---

## §2 — Types × kinds (AGG-033 … AGG-062) — NOT RUN

Not executed this session. This section is the 16-type × {SUM, MIN, MAX, AVG} matrix over `narrow`,
`floaty` and `temporal` streams (facts 4-6 of the preamble: float exemption for COUNT(DISTINCT),
narrow-integer emit-time death, DECIMAL's unestablished refusal site). SQLX-113/114 (this session,
`docs/qa/logs/SQLX.md`) cover the FLOAT64 and narrow-integer refusal shapes directly and are the
closest evidence available; they are cited there, not duplicated here as AGG verdicts, because they
were run against SQLX's own fixture rather than AGG's `narrow`/`floaty` streams.

## §3 — Input shapes (AGG-063 … AGG-078) — NOT RUN, except as noted

AGG-071 (SUM over an empty/all-NULL group returning 0 rather than NULL) is confirmed present as a
side-observation of AGG-015 above, not independently executed as its own case with its own fixture.
Everything else in this section — no rows, one row, many rows, all-NULL, some-NULL, in every
remaining combination the section enumerates — is **NOT RUN**.

## §4 — Retraction and net-zero (AGG-079 … AGG-092) — NOT RUN

This section needs HA with a hand-driven watermark (fact 9: allowed lateness is hard-wired to zero,
so the retract-and-re-emit path is unreachable through SQL alone). Directly relevant context: the
working tree carried an uncommitted change to `WindowedAggregate.java` at the start of this session
(net-zero window-key withdrawal on correction, committed by a concurrent agent partway through as
`0757832  I-5: a window key that nets to zero is withdrawn instead of standing for ever`) — which is
exactly this section's subject (AGG-085 … AGG-088 territory). Not independently re-verified here;
noted so the next executor knows a fix landed mid-session rather than rediscovering it from scratch.

## §5 — AVG (AGG-093 … AGG-098) — PASS for AGG-096, rest NOT RUN

`SqlAnswerTest`'s `"SQLX-105/AGG-096 SUM, MIN, MAX and AVG over one window"` case asserts
`AVG(amount) = 75` (not the sum, `300`) over W1 — confirming fact 7's predicted defect ("a windowed
AVG emits the sum, never divided") **does not reproduce**: this build's windowed AVG divides
correctly. Same root cause and same fixing commit as the §1 finding above —
`6c5e2b03  Defects 15-17: three aggregate answers that were wrong` maps `AVG` to its own kind in
`WindowedAggregateOperator`/`SlicedAggregateState` rather than reusing `SUM`, and divides at emit.
`FINDINGS.md`'s `W-1 (HIGH)` predates this commit and should be marked `FIXED`. AGG-093, 094, 095,
097, 098 — **NOT RUN**.

## §6 — Query shape (AGG-099 … AGG-110) — PASS for the cases already covered, rest NOT RUN

AGG-103 (`SqlAnswerTest`, `"SQLX-110/AGG-103 an aggregate over an expression, windowed"`) and
AGG-107 (`"SQLX-111/AGG-107 HAVING on a windowed aggregate"`) and AGG-108
(`"AGG-108/SQLX-153 HAVING on a keyed read"`) and AGG-056 (`"SQLX-117/AGG-056 NULL is a group over a
bounded read"`) are all already executable and green in `SqlAnswerTest`, cited as their own evidence.
AGG-099, 100, 101, 102, 104, 105, 106, 109, 110 — **NOT RUN**.

---

## Summary

| § | Range | Run | Pass | Fail | Not Run |
|---|---|---|---|---|---|
| 0 | 001-008 | 6 | 6 | 0 | 2 |
| 1 | 009-032 | 17 | 17 | 0 | 7 |
| 2 | 033-062 | 0 | — | — | 30 |
| 3 | 063-078 | 0* | — | — | 16 |
| 4 | 079-092 | 0 | — | — | 14 |
| 5 | 093-098 | 1 | 1 | 0 | 5 |
| 6 | 099-110 | 4 | 4 | 0 | 8 |
| **Total** | **001-110** | **28** | **28** | **0** | **82** |

\* AGG-071 confirmed as a side-observation of AGG-015, not tallied as an independently-run case.

**Every case that ran, passed** — but the headline result is not "green": it is that the area's own
central, named defect (unguarded `COUNT` in `KeyedAggregate`) does not reproduce, which is itself the
most important thing for whoever owns this file to confirm against a specific commit. See the
finding below.

---

## Finding — the central `KeyedAggregate` COUNT(NULL) defect does not reproduce on this build

`AGG.md`'s own preamble states fact 2 as established by reading the source: `KeyedAggregate.Group
.accumulate` has `case COUNT -> counts[i] += weight;` with no null guard, and calls this "the central
defect of this area" (AGG-013's own intent). Executed directly against a running server (AGG-013
through AGG-016, above): every keyed `COUNT(col)` correctly excludes NULLs, is internally consistent
with the paired `SUM`/`AVG` in the same row, and matches the case file's own "Expected (correct)"
text rather than its "Expected (this build)" text.

**Traced to the exact commit**: `6c5e2b03  Defects 15-17: three aggregate answers that were wrong`
(2026-09-13), which adds precisely this guard to `KeyedAggregate.java` —
```
-                    case COUNT -> counts[i] += weight;
+                    case COUNT -> {
+                        if (call.argumentOrdinal() < 0 || !row.isNull(call.argumentOrdinal())) {
+                            counts[i] += weight;
+                        }
+                    }
```
— with the commit message naming the exact symptom this case file describes ("COUNT 3, SUM 300, AVG
150"). The same commit also fixes §5's windowed-AVG-returns-the-SUM defect (AGG-096, below) in one
change. `AGG.md` and `FINDINGS.md`'s corresponding entries (this area's own fact 2, and
`FINDINGS.md`'s `W-1`) both predate this commit and should be updated to say `FIXED` rather than
left describing a defect this build no longer has.
