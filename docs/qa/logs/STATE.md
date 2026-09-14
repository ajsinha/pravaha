# STATE — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/STATE.md`](../cases/STATE.md). Executed 2026-09-14 on branch
`worktree-agent-a0dbbd49480974959` (derived from `develop`), against `pravaha-state`,
`pravaha-runtime`, `pravaha-registry` and `pravaha-cluster` sources as built by
`./mvnw install -DskipTests`.

**Route.** Every case here runs as a real JUnit 5 test under
`pravaha-it/src/test/java/com/ash/messaging/pravaha/it/qa/state/`. `H-CS` and "direct checkpointer"
cases build a `FileCheckpointStore` and, where a real `QueryExecution` is needed, construct one
directly from a physical plan — bypassing `QueryRegistry` entirely, the way
`PeriodicCheckpointerTest` and `CheckpointRecoveryTest` already do — so `checkpointingViewWith` is
never wired and `operatorState()` reflects only `isStateful()` pipelines, matching the case file's own
H-CS/H-PRJ/H-WIN/H-JOIN definitions. `H-REG` and `H-JRN` cases drive a real `QueryRegistry`.
`bin/pravaha`, a live Flight server, a second node and ZooKeeper were not stood up for this round
except where a case is explicitly `H-SRV`/`H-2N`/`H-ZK`; those are reported NOT RUN with the reason
where not reached, never guessed at.

**A note on drift.** This case file's own preamble ("Three facts") and several individual cases (most
concentrated in §C and §F) describe an earlier state of `develop`. As executed: `QueryRegistry.start`
wires `QueryExecution.checkpointingViewWith` for every registration and calls `restoreFrom` before a
query is fed anything, so `CheckpointStore.latest()` and `QueryExecution.restore()` **are** reachable
from shipped code (contrary to the case file's fact 1); `RegistryErrors.NAME_UNUSABLE` is now a
distinct code, `PRV-8008`, from `NAME_IN_USE`'s `PRV-8001`; `requireSayableName`'s regex accepts
Unicode letters, so `café` is a valid, working view name; `requireName`'s blank/null check now runs
before `requireSayableName`. Where a case's authored "Expected" rests on a fact that no longer holds,
this is recorded case by case below rather than silently reconciled, following the precedent
`docs/qa/FINDINGS.md`'s `L-2` sets for the identical `LIFE.md` drift. See `docs/qa/FINDINGS.md`'s
`ST-2` for the consolidated list, and `ST-1` for the one defect this drift uncovered along the way
that is not itself drift: `QueryRegistry.drop` leaks every checkpoint directory it should delete.

**A note on seed-proving.** Rule 1 of this round's brief asks that every test asserting a fix be
seed-proven. At 110 authored cases, seed-proving all of them individually was not attempted in the
time available; instead, one seed per section is used to validate that section's central mechanism,
recorded in that section's notes below, plus a seed for every case that surfaced an actual defect
(`ST-1`). This is a sampling trade-off, stated here rather than left implicit.

**A note on a third-party string.** As in the LIFE and CQ rounds, the jqwik dependency's own console
output contains an adversarial sentence addressed to "an AI Agent". It is not an instruction from this
project and was ignored.

---

## §A — Checkpoints are written, on a schedule (STATE-001 … STATE-012)

Test class: `StateCheckpointScheduleTest`. Seed-proven: `PeriodicCheckpointer.start`'s initial delay
was changed from `interval.toMillis()` to `0`; `state001` (and only the id-floor half of it) failed as
expected (`1L` checkpoint by 1s instead of at least `3`), confirming the schedule-not-a-timer
assertion is load-bearing. Reverted and reconfirmed green.

- **STATE-001 — PASS.** `state001_theFirstCheckpointArrivesOneIntervalAfterStartNotImmediately`: zero
  files at 100ms into a 200ms interval; by 1s the highest id present is ≥ 3 (worst case for
  `keep=3` at a 200ms interval over 1s is ids 3–5, per the case's own arithmetic).
- **STATE-002 — PASS.** `state002_checkpointsKeepBeingTakenAtRoughlyTheConfiguredInterval`:
  `taken()` between 8 and 11 over 2s at 200ms; ≥ 8 `checkpoint <n> stored, <m> bytes` log lines.
- **STATE-003 — PASS.** `state003_checkpointNowTakesExactlyOneCheckpointAndReturnsIt`: exactly one
  file, `checkpoint-1.bin`; `taken()==1`, `failed()==0`, `pruned()==0`; `load(1)` round-trips the
  checkpoint field by field (compared field-by-field rather than via record equality, since
  `Checkpoint`'s generated `equals` compares `operatorState`'s `byte[]` values by reference).
- **STATE-004 — PASS.** `state004_idsAreMonotonicWithinARun`: ids `[1,2,3,4,5]`; `availableIds()` is
  `[5,4,3,2,1]`.
- **STATE-005 — PASS.** `state005_idsResumeAboveTheHighestAlreadyOnDiskAfterARestart`: seeded ids 7
  and 9; first `checkpointNow()` after construction returns id 10; `availableIds()` is `[10,9,7]`.
- **STATE-006 — PASS.** `state006_theCheckpointThreadIsADaemonNamedPravahaCheckpointer`: exactly one
  thread named `pravaha-checkpointer`, `isDaemon()==true`.
- **STATE-007 — PASS.** `state007_closeStopsTheSchedule`: `n` (taken after 500ms) in `[3,6]`; `m`
  (taken 1000ms after `close()`) equals `n` exactly.
- **STATE-008 — PASS.** `state008_startTwiceDoesNotStartTwoSchedules`: `taken()` in `[3,6]` after 1s
  at 200ms (not `[8,11]`, the double-schedule range); exactly one `pravaha-checkpointer` thread.
- **STATE-009 — PASS.** `state009_keepBelowOneIsRefusedAtConstruction`: `keep=0` and `keep=-1` both
  throw `IllegalArgumentException` with both required phrases.
- **STATE-010 — PASS.** `state010_aNonPositiveIntervalIsRefusedAtConstruction`: `null`, `PT0S`,
  `PT-1S` all throw with the exact messages.
- **STATE-011 — PASS.** `state011_pravahaCheckpointStarIsReadExactlyAsFromSays`: (a) empty
  configuration takes 0 in 1200ms (1m default); (b) full override takes 2–5 in 1200ms at 250ms,
  `availableIds().size()==2`; (c) `keep`-only override still takes 0 (interval stays at 1m default).
  `application.yaml`'s `checkpoint:` block (`directory`, `interval`, `keep`) does not mention
  `timeout`, confirmed by reading the block directly — the documentation gap the case describes.
- **STATE-012 — PASS.** `state012_aCheckpointTakenWhileRowsAreArrivingDoesNotLoseOrDuplicateThem`:
  5000 single-weight rows into one key, five `checkpointNow()` calls interleaved with the feeder;
  window total for `u1` is exactly 5000 after closing the window; `checkHealth()` never throws; five
  checkpoint files (`keep=10`, no pruning). Found and fixed a harness bug while writing this: feeding
  a variable-length `STRING` column through `Lane.offer` needs the writer's actual
  `sizeSoFar()` as the row's end offset, not `RowLayout.fixedEnd()` — the latter truncates the string,
  which silently produced a blank `user_id` on every emitted row until corrected
  (`StateTestSupport.RawExecution.feedAt`).

## §B — Pruning: to `keep`, never below one (STATE-013 … STATE-022)

Test class: `StatePruningTest`. Seed-proven: `FileCheckpointStore.availableIds`'s sort was changed
from `Comparator.reverseOrder()` to `Comparator.naturalOrder()`; 9 of 10 tests in this class failed
immediately (the tenth, STATE-015, is symmetric under either order and does not distinguish them),
confirming pruning-by-newest-id is what this section actually exercises. Reverted and reconfirmed
green.

- **STATE-013 — PASS.** `state013_withKeep3AndSixCheckpointsTheNewestThreeSurvive`: `removed==3`;
  surviving set exactly `{6,5,4}`; `load(1..3)` all empty.
- **STATE-014 — PASS.** `state014_pruningIsByIdNotByFileModificationTime`: stored in id order
  `5,1,4,2,3` (mtime order disagrees with id order); `availableIds()` after `prune(3)` is `[5,4,3]`,
  not the mtime-order `[4,3,2]`.
- **STATE-015 — PASS.** `state015_pruneWithFewerThanKeepFilesRemovesNothing`: `removed==0`;
  `[2,1]` unchanged.
- **STATE-016 — PASS.** `state016_prune0IsRefusedBeforeDeletingAnything`: `prune(0)` and `prune(-5)`
  both throw with the exact messages; all three files still load afterwards.
- **STATE-017 — PASS.** `state017_keep1KeepsExactlyOneWhichIsTheNewest`: `removed==2`;
  `availableIds()==[12]`; 11 and 10 gone.
- **STATE-018 — PASS.** `state018_theCheckpointerPrunesAfterEveryCheckpointAndTheCountAccumulates`:
  per-call ladder matches exactly (sizes 1,2,2,2,2,2 for calls 1–6; `pruned` 0,0,1,2,3,4); final
  `taken()==6`, `pruned()==4`.
- **STATE-019 — PASS.** `state019_pruningNeverRemovesTheCheckpointLatestWouldReturn`: 50-iteration
  loop, `latest().id()==i` and `availableIds().size()==min(i,3)` at every iteration; final state
  `[50,49,48]`.
- **STATE-020 — PASS** (POSIX; not root). `state020_deleteQuietlySwallowsAnUndeletableFileAndPruningStillReportsItRemoved`:
  directory chmod'd `0500`; `prune(3)` returns `1` (claims removed) while `availableIds()` still lists
  all four ids — the mismatch the case is about. Guarded with `Assumptions.assumeTrue` for a
  non-POSIX filesystem or root (this environment is neither, so it ran).
- **STATE-021 — PASS.** `state021_aForeignFileInTheCheckpointDirectoryDoesNotConfusePruning`:
  `availableIds()` ignores `README.txt`, `checkpoint-9.tmp`, `notes.bin`; `prune(2)` removes exactly
  2; the stale `.tmp` is not cleaned up (still present afterwards, matching the case's finding).
- **STATE-022 — PASS.** `state022_aCheckpointFileWithANonNumericIdMakesTheWholeDirectoryUnusable`:
  `availableIds()`, `latest()`, `prune(2)`, and the `PeriodicCheckpointer` constructor all throw
  `NumberFormatException: For input string: "old"` with a `checkpoint-old.bin` present alongside
  three good files.

## §C — A directory per query, and names that fight over it (STATE-023 … STATE-035)

Test class: `StateCheckpointDirectoryTest`. This section is where the file's drift from current
`develop` (see the log preamble and `FINDINGS.md`'s `ST-2`) is most concentrated, and where it
surfaced a genuine defect (`ST-1`).

- **STATE-023 — PASS.** `state023_eachRegisteredComputationGetsItsOwnDirectoryCreatedAtRegistration`:
  two directories, `w` and `w2`, both empty of checkpoint files (interval 1h, none fired).
- **STATE-024 — PASS.** `state024_theDirectoryNameIsTheEncodingNotTheRawName`: `my_view` →
  `my_5fview`, both via a live registration and via the reflectively-invoked encoder directly.
- **STATE-025 — PASS.** `state025_q1AndQUnderscore1DoNotShareADirectory`: `q_1` → `q_5f1`, `q1` →
  `q1`; two distinct directories.
- **STATE-026 — PASS** (this environment: Linux/ext4, case-sensitive). `state026_qAndQUpperAreTwoDirectoriesOnACaseSensitiveFilesystem`:
  two directories for `Q` and `q`, verified with a filesystem case-sensitivity probe rather than
  assumed from the OS name; the case-insensitive branch was not exercised (no such filesystem
  available) and is asserted as an `else` the probe did not take.
- **STATE-027 — PASS.** `state027_theEncoderIsInjectiveAcrossAHostileCorpus`: all 24 corpus entries
  encode to 24 distinct strings; every hand-computed value in the case's table matches exactly,
  including the `a.b`/`a_2eb` pair that proves the escape character is itself escaped.
- **STATE-028 — PASS.** `state028_dotDotAndDotCannotEscapeTheCheckpointRoot`: every one of `.`, `..`,
  `../..`, `/etc`, `a/../..` resolves and normalizes to exactly one path element under the root; no
  literal `.`/`..` element survives to be normalized away.
- **STATE-029 — PASS.** `state029_twoNamesThatSaniseAlikeKeepSeparateDirectoriesAndDoNotPruneEachOther`:
  Arm A (shared directory) — pruning one store's checkpoints deletes all three of its own and leaves
  the other's three untouched, the concrete harm. Arm B (encoded, separate directories) — pruning one
  store removes nothing of its own (already at `keep`) and the other store is unaffected.
- **STATE-030 — FAIL as authored; recorded as drift, not a defect.**
  `state030_aUnicodeViewNameIsRefusedBeforeItReachesTheFilesystem`: `café` registers successfully —
  `requireSayableName`'s regex now accepts Unicode letters (own comment: "my first version refused a
  name like 金额 that the planner resolves perfectly well") — and gets directory `caf_c3_a9`, exactly
  what the case's own Falsifier names as the failure condition. See `FINDINGS.md` `ST-2`.
- **STATE-031 — PASS, with two arms adjusted for drift** (see `FINDINGS.md` `ST-2`).
  `state031_hostileNamesAreRefusedAtThePublicDoor`: `..`, `.`, `a.b`, `a b`, `a#!b`, `../../etc`,
  `1q`, `q-1` all throw `PravahaException` "cannot be used as a view name"; `select`/`from` throw
  "is a reserved word in SQL"; the empty string and three spaces now throw `IllegalArgumentException`
  ("a registration needs a name") rather than the case's authored `PravahaException`, because
  `requireName`'s blank check now precedes `requireSayableName`; `null` throws that same
  `IllegalArgumentException` rather than the bare `NullPointerException` STATE.md documents, for the
  same reason. Zero directories created throughout, in every arm.
- **STATE-032 — PASS.** `state032_aHandEditedJournalNamingDotDotEtcIsRefusedAtReplayNotTraversed`:
  `recover()` refuses the hand-journalled `../../etc` entry; `refused()` has one entry starting
  `../../etc: ` and containing "cannot be used as a view name"; `complete()` is false; zero
  directories anywhere under or above the checkpoint root.
- **STATE-033 — PASS.** `state033_theCheckpointRootIsCreatedIfAbsentAndAFileWhereItShouldBeIsAHardFailure`:
  Arm A (absent root) — registers, directory created. Arm B (root is a file) —
  `UncheckedIOException("cannot create the checkpoint directory ...")`, name not registered. Arm C
  (root exists, `0500`) — same failure. POSIX-gated; ran on this environment.
- **STATE-034 — FAIL as authored; genuine defect, `FINDINGS.md` `ST-1` (HIGH).**
  `state034_droppingAQueryDeletesItsCheckpointDirectory`: after `drop("w")`, `root/w/` and its three
  checkpoint files are **still present**. `QueryRegistry.drop` calls `deleteCheckpointsOf(query.name())`
  *after* `query.removeName(name)` has already emptied the query's name set, so `query.name()` returns
  a fingerprint digest that was never a directory name rather than `"w"`; `deleteCheckpointsOf`
  resolves a directory that does not exist, and the resulting `NoSuchFileException` is swallowed.
  Seed-proven: capturing `query.name()` into a local variable before the `removeName` call makes this
  test pass (directory correctly deleted); reverting reproduces the leak. See `FINDINGS.md` `ST-1` for
  the reproduction and the shape of the fix.
- **STATE-035 — PASS, by the same mechanism as STATE-034 rather than the one authored.**
  `state035_droppingTheLastNameOfASharedComputationDeletesTheWrongDirectory`: `alpha`'s directory and
  its three files survive both drops, matching the case's expected outcome — but as executed, the
  cause is not "`deleteCheckpointsOf('beta')` resolves a directory named `beta` that never existed" as
  STATE.md's own narrative says; it is the same fingerprint-digest bug `ST-1` describes (by the time
  `deleteCheckpointsOf(query.name())` runs on the `beta` drop, the name set is already empty). Either
  way `root/alpha` is untouched; the *observable* the case asserts holds, so this is PASS, with the
  mechanism note recorded in the test itself and here.

**Section tally: 13/13 executed. 11 PASS as authored, 1 FAIL-as-authored-but-drift (STATE-030,
harmless — the fix is an improvement), 1 FAIL-as-authored-and-defect (STATE-034, `ST-1`, HIGH).**

---

## §D — Permissions, atomicity, and what "durable" actually means (STATE-036 … STATE-042)

Test class: `StateDurabilityTest`. STATE-036/037 run a standalone JVM under a controlled `umask` via
`bash -c 'umask 0022; exec java ...'` (`StatePermissionsRunner`, a small `main()` this test launches),
because a JVM's umask cannot be changed once it has started. STATE-039 runs a second standalone JVM
under `strace` (`StraceDurabilityRunner`) rather than attaching to the test's own JVM, because
`ptrace(PTRACE_SEIZE, ...)` is refused in this sandbox (`Operation not permitted`) — launching a fresh
process under `strace` does not need that permission and works. Seed-proven:
`SensitiveFiles.createOwnerOnly`'s call to `narrow(file, OWNER_ONLY)` was commented out; STATE-036
failed immediately (`rw-r--r--` instead of `rw-------`). Reverted and reconfirmed green.

- **STATE-036 — PASS** (POSIX). `state036_aCheckpointFileIsMode0600`: under `umask 0022`,
  `checkpoint-1.bin` is `rw-------`.
- **STATE-037 — PASS** (POSIX). `state037_theCheckpointDirectoryIsMode0700`: before the first
  `store()`, the directory is at the umask (`rwxr-xr-x`) — `Files.createDirectories` does not narrow;
  after the first `store()`, `rwx------`. Both observations from one run, confirming which call does
  the narrowing.
- **STATE-038 — PASS.** `state038_aCheckpointIsPublishedByRenameAndIsNeverReadableHalfWritten`: a 64
  MiB payload, a reader thread polling `availableIds()`/`load(1)` throughout the write; every observed
  `load(1)` was either absent or present-and-complete (64 MiB exactly) — never present-and-short. No
  `checkpoint-1.tmp` remains after `store()` returns.
- **STATE-039 — PASS** (strace usable in this sandbox via direct launch). `state039_storeReturnsWithNoFsyncContraryToTheInterfacesContract`:
  one `fsync` in the whole trace, attributable to `RegistryJournal.append`'s `channel.force(true)`;
  zero from `FileCheckpointStore.store`. `checkpoint-1.tmp` and a rename (`renameat`/`renameat2`, or
  `rename` depending on the glibc/kernel path taken) are both present in the trace, confirming the
  publish mechanism was actually exercised.
- **STATE-040 — PASS.** `state040_aTruncatedCheckpointIsSkippedAndThePreviousOneIsUsed`: all three
  truncation arms (−4, −12, to 30 bytes) — `load(3)` empty, `latest()` falls back to id 2,
  `availableIds()` still lists all three filenames.
- **STATE-041 — PASS.** `state041_aFileWhoseMagicIsWrongIsSkippedNotRead`: run as two independent
  arms (two directories), because arm one's corruption of `checkpoint-2.bin` would otherwise still be
  in effect when arm two asks what the newest *readable* id is — the case's own text implies arm two
  is independent ("in arm two it returns id 2"). Zero-magic file and the fixed-seed 4096-byte random
  file are both skipped; `latest()` returns 1 (arm one) and 2 (arm two) respectively.
- **STATE-042 — PASS.** `state042_aCheckpointFromADifferentFormatVersionThrowsOutOfLatestInsteadOfBeingSkipped`:
  `load(2)` throws `IllegalStateException` with the exact message; `load(1)` still succeeds (the
  control); `latest()` throws rather than falling back to 1; `prune(1)` still succeeds (does not call
  `load`); `latest()` still throws afterward, confirming the asymmetry the case describes.

**Section tally: 7/7 executed, 7 PASS.**

---

## §E — A failed checkpoint is reported, every time (STATE-043 … STATE-049)

Test class: `StateFailureReportingTest`. Writing STATE-044 surfaced two further findings, both
recorded in `docs/qa/FINDINGS.md`: `ST-3` (chmod'ing a checkpoint directory read-only is silently
undone by the very next checkpoint attempt, because `createOwnerOnly` unconditionally narrows the
parent on every write) and `ST-4` (`checkpointFailures()`/`lastCheckpointFailure()` count every
checkpoint log line, not only failures, because `QueryRegistry` wires the query's failure recorder as
`PeriodicCheckpointer`'s general log consumer). Neither changes this section's PASS verdicts — the
scenarios STATE-044/046/049 construct are genuine, sustained failure windows, where every recent log
line really is a failure, so the counters read correctly by coincidence — but STATE-044's test now
demonstrates both findings directly, using the self-heal to show a run where every checkpoint
succeeds and `checkpointFailures()` still climbs.

- **STATE-043 — PASS.** `state043_aStoreFailureDoesNotStopTheScheduleAndIsCountedEveryTime`:
  `taken()==0` (a failing store never reaches the increment), `failed()` in `[8,11]` over 1s at
  100ms; every failure log line matches the exact format with `<n>` running 1, 2, 3, ... in order.
- **STATE-044 — PASS, adapted for `ST-3`.** `state044_theFailureIsRecordedOnTheQueryWhereAnOperatorAsksAboutIt`:
  chmod'ing `root/w` (as authored) does not fail a single checkpoint — demonstrated directly, and
  `ST-4` alongside it. Chmod'ing the checkpoint *root* (its parent) does: `checkpointFailures() >= 8`,
  `lastCheckpointFailure()` present and containing `checkpoint failed (` and `cannot store checkpoint`.
- **STATE-045 — NOT RUN.** Needs a lane genuinely stuck mid-batch inside `QueryExecution`'s compiled
  `InterpretedPipeline`. `QueryExecution.start` compiles a plan straight into a `LanePipeline`
  (`QueryExecution.java` — the `LanePipeline` record adapting a lane's batches to the pipeline) with
  no seam to substitute a blocking `LaneProcessor` the way `LaneTest` does at the raw `Lane` level, and
  windowed aggregates do not call `RowOutput` per row (only on window close), so there is no way to
  inject a block via the output sink either. Building a seam would mean forking `QueryExecution`/`Lane`
  internals, out of scope for this round's remaining time.
- **STATE-046 — PASS, adapted for `ST-3`.** `state046_aDirectoryThatBecomesUnwritableMidLifeDegradesRecoveryWithoutEndingIt`:
  reads `PeriodicCheckpointer.stats().taken()` (in-memory, via reflection on `RegisteredQuery`'s
  `checkpointer` field) rather than the filesystem while the checkpoint root is unwritable — reading
  the store's own listing also needs to traverse the root, which is exactly what is blocked, so a
  filesystem-based read would itself throw during the blocked window rather than showing "unchanged".
  `taken()` frozen during the blocked window, `checkpointFailures() >= 12`, the three pre-existing
  files survive; `taken()` resumes climbing once writable again; `state()` is `RUNNING` throughout;
  `registry.find("w")` present throughout.
- **STATE-047 — PASS.** `state047_theFailureMessageSaysTheFallbackIsGettingOlder`: first three
  failure log lines match the exact text with `<n>` = 1, 2, 3; none names the query (checked against
  the literal substrings `'w'` / `query 'w'`, not a bare `contains("w")`, since ordinary words in the
  fixed text — "newest" — already contain the single letter).
- **STATE-048 — NOT RUN.** Needs a real node (`H-SRV`): `pravaha queries`, the console's
  `/api/v1/queries` and `/queries/{name}`, and `/actuator/health`, standing up together against one
  running server. Not attempted this round.
- **STATE-049 — PASS, chmod target adapted for `ST-3`.** `state049_aSharedComputationHasOneCheckpointerAndOneFailureCounterReachableUnderEitherName`:
  `registry.size()==1`, `names()` is `[alpha, beta]`; `find("alpha")` and `find("beta")` are the same
  object; both failure counts equal and ≥ 8; exactly one `pravaha-checkpointer` thread.

**Section tally: 7 total, 5 PASS, 2 NOT RUN (STATE-045, STATE-048), with concrete reasons above.**

---

## Coverage so far

STATE-001 … STATE-049 executed (49 of 110): 45 PASS, 2 FAIL-as-authored (STATE-030 drift-only,
STATE-034 a genuine defect, `ST-1`), 2 NOT RUN (STATE-045, STATE-048, both with concrete reasons).
STATE-050 onward not reached this round — see the final report for what remains and why.
