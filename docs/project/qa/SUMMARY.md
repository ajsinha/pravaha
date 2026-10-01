# QA summary — read this one first

> **This page stopped being current on 2026-09-12 and is kept as the record of that round, not as a
> description of the product.** Everything below it — the counts, the blockers, and especially *The
> state of the product, in one paragraph* — describes the tree as it stood when the first four rounds
> closed. Twelve rounds have run since (`logs/`: AGG, API, CFG, DOCX, ERRC, INCR, PERF, SDKX, SECX,
> SQLX, STRM, TIME, TYPE, WIN), Waves 7, 8 and 9 shipped, and several of the blockers named here are
> fixed: state **is** restored after a restart (ST-5, `StateRestoreTest#state063`), a windowed
> aggregate **does** emit continuously, and `ServedView` **does** sum weights (ADR-013). For what is
> open now, read [`FINDINGS.md`](FINDINGS.md), which carries a
> status line per finding and is enforced by `FindingsRegisterTest` — including the file's own header
> totals, since those had drifted too (DOCS-10). For what is built, read
> [`../../development/HANDOVER.md`](../../development/HANDOVER.md). Recorded as DOCR-21 and re-swept as DOCS-10.

**2,385 test cases written across 24 areas. 428 executed. ~200 distinct defects recorded.**
The remaining ~1,950 cases are authored, reviewed and not yet run.

| | |
|---|---|
| Cases authored | **2,385** (36,777 lines) |
| Cases executed | **428** — round 1 (254), Aerospike (60), continuous query (57), re-QA (57) |
| Defects recorded | **~200**, every one with evidence in `logs/` or a source citation |
| Blockers | **14** |
| Defects introduced by this session's own remediation | **13** |

## The state of the product, in one paragraph

A Pravaha server can ingest a finite file of insertions and answer a windowed query about it. It
**cannot** receive a retraction from any shipped source, run a continuous aggregate that ever emits,
declare six of its sixteen data types, reach a lookup join, restore any state after a restart, or
serve results through a view that implements the engine's own algebra. It dies silently at 64 MiB of
output and reports `RUNNING` afterwards. Its own algebra module — which is correct — is referenced by
nothing outside itself.

## The blockers

| | Defect |
|---|---|
| 1 | Every interpreted query dies after 64 MiB of output. The lane records the failure; the server never calls `checkHealth()`. Query reports `RUNNING`, view serves stale, orphaned thread burns a core. Ten surfaces report healthy. |
| 2 | No shipped source can deliver a retraction. Four of five plugins hard-code weight `+1`; the schema grammar has no weight column. **The Z-set model has no route in** — which is why every retraction defect below survived. |
| 3 | `ServedView` is not a Z-set. Weights are never summed: weight 0 inserts, a single `−1` deletes a key of accumulated weight 2. |
| 4 | A continuously registered global or keyed aggregate never emits. Both emit only at end of input; a stream has no end. |
| 5 | Six of sixteen types cannot be declared. `typeFor` — behind all four schema surfaces — has no case for DECIMAL, DATE, TIME, ARRAY, MAP, ROW. |
| 6 | Checkpoints are never read. `restore()` has no shipped caller, and `isStateful()` omits keyed aggregates, so the canonical continuous query checkpoints nothing anyway. |
| 7 | Under any real policy, no query survives a restart: recovery rebuilds every owner as a role-less principal in tenant `"unknown"`. |
| 8 | Partition quiet time cannot fire. The tick re-observes each partition's retained high-water every time, refreshing its activity clock — so only a partition that never spoke can go idle. |
| 9 | The Aerospike plugin has no `META-INF/services` file, so the flagship connector cannot be selected by configuration anywhere. |
| 10 | The weight never reaches a remote subscriber: a retraction is byte-identical to an insertion on the wire. |
| 11 | Aerospike pushdown drops rows silently in three ways, and is never wired on the server path at all. |
| 12 | Lookup joins are unreachable from every shipped surface; `withLookups` has one caller in all of main. |
| 13 | `pravaha run` silently truncates — five runs of one command over one file returned 17,664 / 9,472 / 7,424 / 6,400 / 4,608 rows, all reporting `ok`. |
| 14 | Row filters fail open: a filter Calcite folds to a constant leaves no `FilterOperator`, and the plan is returned unchanged while the decision is recorded as "allowed with a row filter". |

## Where the safety nets were

Three mechanisms that were supposed to catch this did not:

- **`SqlSupportMatrixTest` compares no values.** Every ✅ in `SQL_SUPPORT.md` is a statement about
  planning, not about an answer. None of the wrong-answer defects would fail the build.
- **`theDifferentialTestCatchesAGeneratedBug` never invokes the generator** — both sides of the
  comparison are the interpreter, and the property compares only column 0.
- **`ExamplesTest` never opens `QUICKSTART.md`**, though `HANDOVER.md` says it does. Its
  quickstart-named methods hold hard-coded copies of the commands, which is why they rotted green.

And one case is recorded **NOT DISCRIMINATING** on purpose: it is the query `SQL_SUPPORT.md`
recommends as the bounded alternative to `COUNT(DISTINCT)`, and it agrees with the defective
implementation. The documentation steers users onto the one input where the bug is invisible.

## Defects this session introduced

Thirteen, while remediating. They are listed here rather than buried because they are the strongest
argument in this record about how the rest should be fixed:

the close ordering that discards every query's final window · the idle exclusion that can never fire ·
watermark partition names that collide · a rounding fix wrong in three ways, computed to the bit ·
`LIKE` and `SUBSTRING` disagreeing about the length of an emoji · two refusal messages advising
syntax the engine rejects · `PRV-8001` meaning two things and `PRV-7002` meaning four · an
authentication setting that fails open on a typo · `allow-anonymous` dead on the configuration a team
reaches by hardening `dev` · a checkpoint key bound to nothing · a health indicator absent from the
readiness group · a 401 emitting a code the `ErrorCode` constructor would reject · a lock placed
without measuring what it would serialise, taking ingestion from 520,000 rows/s to 2,000 under eight
readers.

Each was a correct diagnosis of a real defect, fixed under time pressure, that missed a sibling call
site or broke a neighbour.

## Recommendation

**Do not remediate this list item by item.** Roughly a third is architectural — a serving layer that
does not implement the algebra, aggregates that never fire, no ingress for retractions, six
undeclarable types — and the rest cannot be validated while the safety nets above are blind.

The order that makes the others checkable:

1. **Make the tests capable of failing.** Give the support matrix value comparison, make the
   differential test invoke the generator, make `ExamplesTest` read `QUICKSTART.md`.
2. **Open a path for retractions** — a weight column and plugin support. Until then every Z-set
   defect is unreachable and unverifiable.
3. **Make `ServedView` a Z-set**, and make continuous aggregates emit.
4. **Wire what exists**: `restore()`, `checkHealth()`, lookup joins, the Aerospike service file,
   custom policies, the lane configuration.
5. Then the long tail, against a suite that can now catch a regression.

Steps 1–3 are where the engine stops being a batch processor with a streaming API.
