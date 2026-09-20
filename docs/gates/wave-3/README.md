# Gate P2 — end of Wave 3 (E2 Performance core)

Copyright © 2026 Ashutosh Sinha. Proprietary and confidential.

| | |
|---|---|
| Wave | 3 of 10 — E2 Performance core |
| Milestone | **M3 "It's fast"** |
| Date | 2026-09-10 |
| Verdict | **BLOCKED on hardware, not on code** — every criterion that can be evaluated here passes; two cannot be evaluated on this machine at all |

## The verdict, stated plainly

All thirteen stories are implemented and green. Two of the four gate criteria are **not
measurable on the development machine**, and no amount of care in the code changes that. Declaring
a pass on numbers this hardware cannot produce would make the gate a formality, which is the one
thing a gate must never be.

| Criterion | Required | Status |
|---|---|---|
| Differential tests green | generated ≡ interpreted over the corpus | **PASS** |
| No metaspace leak | 10 000 register/drop cycles at baseline | **PASS** — 263 kB growth against a 4 MB ceiling |
| Profile A throughput | ≥ 1.2 M rec/s **per lane** | **NOT MEASURABLE HERE** — superseded 2026-09-20, see below |
| Scaling efficiency | ≥ 90 % from 1 to 8 lanes | **NOT MEASURABLE HERE** — measured 2026-09-20 and **NOT REACHED**, see below |

> **Amended 2026-09-20.** Both rows were measured on this machine, because there is no reference
> hardware and the owner's instruction on 2026-09-19 was to measure here and name the machine. The
> throughput criterion is **reached** — the end-to-end Profile A pipeline on one lane sustains tens
> of millions of rows a second, and its worst pass under heavy load is still at the 1.2 M target.
> The scaling criterion is **not reached**: 28–40 % of linear at eight lanes against a target of
> 90 %. Neither figure is reference-hardware evidence and the confounds below are all still present.
> Numbers, conditions and loads: [`../measured-2026-09-20/README.md`](../measured-2026-09-20/README.md).

## Why two criteria cannot be measured

The development machine is an **AMD Ryzen AI 9 HX 370 laptop SoC**: 12 physical cores (24 logical,
SMT2), two different core designs — Zen 5 and Zen 5c — and aggressive frequency scaling, shared
with an IDE and browsers. The design's reference hardware is 16 physical homogeneous cores at
≥ 3.0 GHz.

Three independent confounds, any one of which is disqualifying for a scaling number:

1. **All-core clock is far below single-core boost clock.** A one-lane measurement and an
   eight-lane measurement are taken at different clock speeds, so the ratio between them measures
   the power envelope as much as the software.
2. **The cores are not equal to each other.** Two lanes on two cores are not two equivalent lanes.
3. **The harness needs two threads per lane** — a producer and the lane — so eight lanes is sixteen
   busy threads on twelve physical cores, before anything else on the machine is counted.

Earlier notes in this repository described this box as "a shared 24-core machine". That
overstatement is corrected in `benchmarks/README.md`, because it is the difference between a number
that is noisy and a number that is meaningless.

## What was measured, and what it does say

| Measurement | Figure | What it establishes |
|---|---|---|
| Lane machinery, single lane | **21 M rows/s** | The loop, inbox, arena and handoff cost roughly 1/17th of the per-lane gate. Whatever makes Profile A hard, it is not the lane. |
| Generated vs interpreted (Profile A operator) | **~10×** | The ratio that justifies code generation at all (R2). Unchanged from Wave 2. |
| Padding vs no padding | **4.1×** | False sharing on two cross-thread cursors, which is what the padding in every ring prevents. |
| Metaspace, 10 000 cycles | **263 kB** vs **29.5 MB** when classes are retained | Per-stage classloaders are working; the seeded leak is detected. |
| Scaling, 1 → 8 lanes | 2.7× (34 %) | **Not evidence.** See above. Recorded so nobody re-derives it and believes it. |

## What has to happen to close this gate

One thing: **run `LaneScalingBenchmark` and `ProfileABenchmark` on the reference hardware** — 16
physical homogeneous cores, ≥ 3.0 GHz, quiet, no SMT contention. Everything else is done. Scheduling
that machine is a gate prerequisite rather than a detail of the gate, and it should be booked now,
because it also blocks Gate P3's Profile B figure and every performance claim after it.

## Retrospective — the honest part

**Five defects were found by seeding bugs, and three of those were in the tests rather than the
code.** That ratio is the finding of this wave.

- The scaling benchmark's first version released each round through a phaser, so it measured the
  slowest thread per round. It reported 33 % efficiency at four lanes, which looked exactly like
  lane contention. A plausible hypothesis — false-sharing reject counters — was tested and refuted
  before the harness itself was suspected.
- The stage-splitting test computed how many methods to expect from the constant it was testing, so
  raising that constant raised the expectation with it. Seeding "stop splitting" passed.
- The metaspace test guessed a 64 MB ceiling from an estimate. Seeding the leak — 29.5 MB of
  retained classes — passed. The ceiling is now calibrated from both measured outcomes.
- Twice, a seeded bug **hung** the build rather than failing it, because a test spun on
  `onSpinWait` and `@Timeout` interrupts a thread that is not observing interrupts. A hanging test
  in CI reads as an infrastructure problem and gets retried rather than read.

**Two claims were retracted after being written.** `AdaptiveStage` originally said that reading the
active processor once per batch is what stops the swap losing a row; it is not — atomicity of the
assignment is — and the double read is a once-per-upgrade nanosecond misattribution that no test
here catches. It is now documented as discipline rather than as a guarded invariant. And P2-12's
per-query quota cannot exist as specified: withholding rows produces a wrong answer, buffering them
reintroduces the per-query buffer the density budget excludes, so what is enforceable is ordering
plus attribution, and design §13.7 now says that.

**The plan under-counted again, for the third consecutive wave.** NFR-2d — ten thousand concurrent
queries per node — arrived mid-wave and added P2-12 and P2-13, six and three days respectively.
Both were recorded as additions rather than absorbed. The pattern is now three for three, and any
Wave 4 estimate should be read with that in mind.

**What went well:** the interpreted path earned its keep twice, first as the correctness fallback
and now as the admission strategy, which makes deleting it twice as unlikely. And the lane's
ownership model survived contact with multiplexing without changing — a lane still runs one
processor and knows nothing about queries, which is the clearest sign the boundary was drawn in the
right place.

---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**</sub>
