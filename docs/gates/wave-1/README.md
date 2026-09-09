# Gate P0 — end of Wave 1 (E0 Foundations)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 1 of 10 — E0 Foundations |
| Milestone | M0 "It builds", M1 "It's deterministic" |
| Date | 2026-09-09 |
| Verdict | **PASS** — merged to `main` |

## Acceptance criteria

| Criterion | Evidence | Result |
|---|---|---|
| Clean clone builds green on the JDK 21 baseline | `./mvnw clean verify` from a clean tree | **PASS** |
| No system Maven required | Wrapper vendored; `./mvnw` bootstraps | **PASS** |
| Deterministic harness demonstrated | `DeterminismTest` — 1000 seeded interleavings, byte-identical output | **PASS** |
| JMH baselines recorded | `benchmarks/baselines/memory-access.json` | **PASS** |
| Architecture rules enforced | `ArchitectureRulesTest`, `SourceFileSizeTest`, `LicenseHeaderTest` | **PASS** |

## Numbers

| | |
|---|---|
| Java tests | 420 |
| Python tests | 28 |
| Java source files | 96 |
| Largest source file | 372 lines (limit 1500) |
| Modules | 14 |

## Stories delivered

`P0-01` reactor and wrapper · `P0-02` build plumbing · `P0-03` CI · `P0-04` `pravaha-api`
· `P0-05` `MemoryAccess` seam · `P0-06` binary row layout · `P0-07` arena · `P0-08` rings and
wait strategies · `P0-09` deterministic testkit · `P0-10` JMH baselines · `P0-11` ArchUnit rules
· `P0-12` ADRs.

Beyond the planned scope: the configuration system, the Java and Python client SDKs, the brand
identity, and the licence change to proprietary.

## Measured, not asserted

`getLong` at **1.309 ns/op** is ~4 cycles at 3 GHz — exactly the figure design §29.1 budgeted for
"binary field read, fixed-width, L1-resident". Twelve fields in 2.875 ns is ~0.24 ns per field.
The performance model the whole design rests on is now confirmed by measurement.

## Retrospective — what this wave got wrong

Kept deliberately, because the next wave's estimate should be informed by it rather than by
optimism.

**Three tests passed while proving nothing.** The ArchUnit suite imported zero classes and every
rule passed vacuously. The row round-trip property wrote every row at offset 0, where an absolute
payload offset is indistinguishable from a relative one — it would have shipped a bug that corrupts
every row the moment it is copied, which is what checkpointing does. The MPSC contention test
asserted a timing-dependent property and was flaky on an idle machine.

All three are now fixed *and* guarded: each has a companion assertion that the test has something to
check. **The practice adopted for the rest of the project: seed a bug and confirm the test fails
before trusting it.** This cost perhaps two hours in Wave 1 and would have cost weeks later.

**A claim in the design was wrong and only building it revealed that.** Design §4.5 stated Agrona was
flag-free on Java 21. It is not — it reaches `jdk.internal.misc.Unsafe` and needs `--add-exports`.
That disqualified it as the default for a *product* reason: an embedded engine inherits its host's
launch arguments. Corrected in place rather than quietly dropped, and the `MemoryAccess` seam meant
the fix cost one file.

**Estimation.** Wave 1 was planned as 12 stories across 2 sprints. It delivered those plus four
unplanned bodies of work, which suggests the plan under-counts adjacent work rather than that the
estimates were generous. Wave 2's estimate should not be revised down on the strength of this.
