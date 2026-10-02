# ADV-ENGINE — execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

Cases: [`../cases/ADV-ENGINE.md`](../cases/ADV-ENGINE.md). Executed 2026-10-01 (into 2026-10-02 UTC) against
**`e3ad67dc`** (v2.0.0 + the 2.0.1-SNAPSHOT bump) — no product code was changed — on JDK 25
(`/usr/lib/jvm/java-25-openjdk-amd64`), built with `tools/worktree-build.sh` into the worktree's own
`.m2-local`. Branch `worktree-agent-adcc321f855f17fe2`. Every test named below is in
`pravaha-it/src/test/java/com/ash/messaging/pravaha/it/qa/adversarial/`.

**How to run.** The suites are opt-in and skipped by the default build:

```
tools/worktree-build.sh -o -pl pravaha-it test -Dtest='Adv*Test' -Dsurefire.failIfNoSpecifiedTests=false \
    -DforkCount=1 -Dpravaha.qa.adversarial=true            # ~5 min; 122 tests, 28 of them the skipped @Disabled repros
# a defect's reproduction, switched on:
... -Dtest='AdvExpressionTest' -Dpravaha.qa.adversarial=true '-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition'
# wider sweeps: -Dpravaha.qa.seed=N, -Dpravaha.qa.seeds=N, -Dpravaha.qa.predicates=N, -Dpravaha.qa.expressions=N,
#               -Dpravaha.qa.kills=N, -Dpravaha.qa.flips=N, -Dpravaha.qa.strictNulls=true (window sweep without the QE-163 allowance)
```

Each defect has a `@Disabled("QE-xxx: …")` test asserting what the documentation promises (all 28 were
run with the disabled condition deactivated and **fail**, except QE-159's one-day variant, which needs
~8 GB of heap and was run once by hand; its one-hour twin `qe159_observed` runs in the suite) and, beside
it, an `…_observed` test pinning what the engine does today, so the suite stays green and the lead can
switch the reproduction on with the fix.

**A note on method.** An exploratory probe pass (a scratch class, not committed) ran before the X and W
cases took their final form; the expressions it turned up are what QE-001..QE-010 and QE-163 were then
written to pin. Every verdict below comes from the committed tests, not from the scratch pass.

---

## Summary

| Area | Cases | PASS | FAIL | BLOCKED | NOT RUN | N/A |
|---|---:|---:|---:|---:|---:|---:|
| X — expressions and types (QE-001–037, 166) | 38 | 22 | 15 | | | 1 |
| P — differential predicates and expressions (QE-038–040) | 3 | 3 | | | | |
| W — aggregates, windows, late data (QE-041–065, 163, 165) | 27 | 19 | 8 | | | |
| Q — queries on queries, embedded push (QE-066–075, 162) | 11 | 9 | 2 | | | |
| D — durability (QE-076–095, 164) | 21 | 13 | 6 | 1 | 1 | |
| S — security semantics (QE-096–138, 168) | 44 | 37 | 3 | | 4 | |
| R — resource abuse (QE-139–161, 167) | 24 | 21 | 2 | | 1 | |
| **Total** | **168** | **124** | **36** | **1** | **6** | **1** |

Differential volume behind the PASS verdicts: 9,800 random predicates over 120 rows each, half interpreted
and half generated (QE-038/039); 2,100 random BIGINT expressions over 60 rows (QE-040); 420 window
sweeps, ≈11,000 compared watermark advances (QE-041–043); 40 seeds × 150 chained operations (QE-066); 20 top-N and
15 join seeds (QE-057, QE-059/060); 19 SIGKILLs across 3 seeds (QE-077).

**What held, and is worth saying so.** Window arithmetic, late data and corrections matched the oracle
exactly on every seed once QE-163 was set aside; queries on queries, top-N and interval joins matched on
every step; SIGKILL at random points never lost or doubled an effect; masks and row filters held on every
read path tried (point reads, equality and ordered indexes, aggregates, subscriptions, queries built on a
narrowed view); tenants could not tell another tenant's names from names nobody holds on any surface tried;
ownership and grants refused every escalation tried.

### Defects, by severity

| ID | Sev | Cases | One line |
|---|---|---|---|
| FINEHOP-1 | HIGH | QE-159 | `HOP(1 ms, 1 day)` is accepted; one row and a minute of watermark wedge the lane, take GBs of heap and stall every push to the stream |
| ALLNULLAGG-1 | HIGH | QE-163 | `SUM`/`AVG`/`MIN`/`MAX` of a group whose values are all NULL are published as 0 — windowed, on a view read, and over a view |
| NARROWINT-1 | HIGH | QE-001–006, 013, 040 | `INT`/`SMALLINT`/`TINYINT` arithmetic, unary minus and `ABS` are published wrapped (`2e9 * 2 = -294967296`) |
| JOURNALMID-1 | HIGH | QE-082 | A damaged length prefix mid-journal reads as a torn tail: later registrations vanish at start, and so does every one made afterwards |
| SHAREDLOSS-1 | HIGH | QE-091 | Drop the first name of a shared computation, restart: the surviving name comes back empty |
| CELLBYTES-1 | HIGH | QE-167 | The embedded engine ignores `pravaha.lane.inbox.cell-bytes`; any row over 512 bytes stops every query on the stream for good |
| NARROWCAST-1 | MEDIUM | QE-007–009 | Narrowing casts truncate (`CAST(Long.MAX AS INT) = -1`) and `CAST(NaN AS BIGINT) = 0`, `CAST(+Inf AS BIGINT) = Long.MAX` |
| DIVMIN-1 | MEDIUM | QE-010 | `Long.MIN_VALUE / -1` is published as `Long.MIN_VALUE` |
| CKPTSUM-1 | MEDIUM | QE-080 | Checkpoints carry no checksum: 8–10 of 16 single-bit flips are restored and published as answers |
| PUSHPARTIAL-1 | MEDIUM | QE-162 | A push one query cannot apply throws after the others applied but before they committed; the caller's retry double counts |
| NANGROUP-1 | MEDIUM | QE-045, 046, 048 | A windowed `GROUP BY` on a DOUBLE loses a row (NaN payloads split, then collapsed by the view's key); ±0.0 are separate groups |
| HOPALIGN-1 | MEDIUM | QE-065 | `HOP` whose size is not a multiple of its slide aligns window *ends* to the slide: windows start at −5 s and 5 s |
| RETYPERESTORE-1 | MEDIUM | QE-088 | A checkpoint of `v BIGINT` is restored into a view whose `v` is now `VARCHAR`; the column then holds a `Long` beside `String`s |
| SAMEPIDCLAIM-1 | MEDIUM | QE-083 | A second engine in one JVM with the default node id claims a running engine's directory; closing it deletes the first engine's marker |
| GETTABLES-1 | MEDIUM | QE-168 | Under the catalogue, Flight SQL `GetTables` lists nothing to a non-admin — not their own views, not views granted to them |
| LISTCOUNT-1 | MEDIUM | QE-111 | Under the catalogue, a row-filtered reader is told the view's whole cardinality by `pravaha.list` (SX-18 withholds it only for a `SecurityPolicy` filter) |
| DLQPROJ-1 | MEDIUM | QE-012 | With a dead-letter directory, a row that divides by zero still stops the query; CQ §11 and the `PRV-3010` text say it goes to the DLQ |
| NANNOT-1 | LOW | QE-014, 015, 038 | `NOT`/`IS FALSE`/`IS NOT FALSE` over a DOUBLE comparison are not its IEEE complement: a NaN row is in neither `P` nor `NOT P` |
| FARTIME-1 | LOW | QE-164 | A CSV `TIMESTAMP` after 2262-04-11 wraps silently into 1677 (`epochSecond * 1e9` unchecked) |
| MINRETRACT-1 | LOW | QE-044 | Windowed `MIN`/`MAX` stop the query (`PRV-3020`) on the first retraction; CQ §13 lists them ✅ and nothing refuses them at registration |
| MASKALERT-1 | LOW | QE-105 | `CREATE ALERT … WHERE <masked column>` is accepted ACTIVE and goes BROKEN; SECURITY.md promises `PRV-7006` at plan time (fails closed — no leak) |
| EMITROOM-1 | LOW | QE-165 | A window with ≈836 k groups stops its query (`PRV-3001` "no room to emit") below the documented 1 M view ceiling |
| QOQAPI-1 | LOW | QE-068 | `PravahaEngine.register(...)` cannot build on a view (`PRV-2002 Object 'up' not found`); `CREATE CONTINUOUS QUERY` can |
| UNCODEDAPI-1 | LOW | QE-062, 063, 085, 139, 152, 166 | Uncoded or empty failures: `IllegalArgumentException` from a keyless `register`, `ArithmeticException` from `push` of an `Instant` past 2262, `UncheckedIOException` from `register`, `PRV-2001  null` for deep nesting, and a lane failure that loses "long overflow" once the JIT omits the message |

### Suggested register text

```
### FINEHOP-1 (HIGH) — a HOP with millions of windows per row is accepted, and one row wedges its lane and the stream
> **Status:** OPEN — `HOP(TABLE w, DESCRIPTOR(ts), INTERVAL '0.001' SECOND, INTERVAL '1' DAY)` registers (86.4 M windows per row, 86.4 M slices per window). One pushed row and a watermark advance of 60 s: the advance returns after ~10 s, the lane then never finishes (0 of the 60,000 closed windows published after 45 s), heap reaches 8.3 GB (1.3 GB with a one-hour size), and the next push to the stream fails `PRV-8103` after the push timeout while an unrelated query on the same stream never sees it. Any principal who may register can do this with one row. Reproduction: `AdvResourceTest#qe159_aHopWithMillionsOfWindowsPerRowIsRefusedOrBounded` (disabled; ~8 GB heap) / `#qe159_observed`.
> **Disposition:** GA-REQUIRED — refuse at registration a window whose slices per window (size ÷ gcd(size, slide)) or windows per row (size ÷ slide) pass a ceiling, as TIME-1 bounds windows per watermark advance.

### ALLNULLAGG-1 (HIGH) — SUM, AVG, MIN and MAX of an all-NULL group are 0, not NULL
> **Status:** OPEN — two rows with `v = NULL` in one window: `SUM(v)`, `AVG(v)`, `MIN(v)`, `MAX(v)` are published `0|0|0|0` (`COUNT(v)` is correctly 0). The same on a bounded read of a view (`KeyedAggregate`: `x|0|0|0|0`) and in a continuous query over a view (`x|0|0|0`). SQL says NULL; a total of 0 is indistinguishable from a real zero. Found by the window differential (QE-041–043 fail on every seed that has an all-NULL group unless the oracle copies this). Reproduction: `AdvAggregateTest#qe163_aggregatesOfAnAllNullWindowGroupAreNull`, `#qe163_allNullGroupOnAViewReadAndOverAViewIsNull`.
> **Disposition:** GA-REQUIRED — the accumulators already track presence (`present[]`, COUNT(col)); emit NULL when a group's non-null count is 0.

### NARROWINT-1 (HIGH) — narrow-integer arithmetic is published wrapped
> **Status:** OPEN — `RowStages.writeComputed` writes an `INT32`/`INT16`/`INT8` result with `(int)`/`(short)`/`(byte)` of a 64-bit evaluation, so `i * 2` (i = 2,000,000,000) is published `-294967296`, `i + i` likewise, `i * i` = `-1651507200`, `-i` and `ABS(i)` at `Integer.MIN_VALUE` = `-2147483648`, `sm + sm` (30000) = `-5536`, `ty + ty` (100) = `-56`; constant expressions too (`2147483647 + 1`, `65536 * 65536 = 0`). The query stays RUNNING. A filter on the same expression evaluates in 64 bits and disagrees with the projection (`WHERE i * 2 > 0` keeps the row whose projected `i * 2` is negative). Reproduction: `AdvExpressionTest#qe001…qe006` (disabled) and `#qe001_observed`, `#qe013_…`.
> **Disposition:** GA-REQUIRED — check the narrow result's range (`Math.toIntExact` and the short/byte equivalents) and route the row as a 64-bit overflow is routed.

### JOURNALMID-1 (HIGH) — damage in the middle of the registry journal silently drops every later registration, at every start
> **Status:** OPEN — `RegistryJournal.replayAll` treats any length prefix that runs past the end of the file as "a half-written final record" and stops. Registrations `alpha`, `beta`, `gamma`; the second record's length prefix overwritten: the next start recovers `[alpha]` with no error. `delta` registered on that start is appended after the damage and is lost at the next start too (`[alpha]` again). state086 covers an undecodable record whose length is plausible; a damaged length is the case it does not. Reproduction: `AdvDurabilityTest#qe082_aDamagedRecordInTheMiddleOfTheJournalRefusesTheStart` (disabled) / `#qe082_observed`.
> **Disposition:** GA-REQUIRED — treat a bad length as torn only when it is the final record (nothing decodable follows); otherwise refuse with `PRV-8005` naming the record, as for an undecodable one. A per-record checksum would make the distinction exact.

### SHAREDLOSS-1 (HIGH) — the surviving name of a shared computation loses its state at a restart
> **Status:** OPEN — `alias_a` and `alias_b` register the same SQL (one computation), two rows pushed, `alias_a` dropped, checkpoints taken, restart: `alias_b` comes back RUNNING and empty. Dropping `alias_b` instead, or dropping nothing, keeps both rows. The checkpoint (or its directory) appears to belong to the first registrant and to go with its drop. Reproduction: `AdvDurabilityTest#qe091_theSurvivorOfASharedComputationKeepsItsStateAcrossARestart` (disabled) / `#qe091_observed`.
> **Disposition:** GA-REQUIRED — a computation's state must outlive any one of its names; re-home the recorded checkpoint directory to the next name on drop.

### CELLBYTES-1 (HIGH) — the embedded engine cannot carry a row wider than 512 bytes
> **Status:** OPEN — `DefaultPravahaEngine` reads no `pravaha.lane.*` setting, so the inbox cell is 512 bytes whatever is configured. Pushing a 600-character string stops the query (`PRV-8004 … row of 656 bytes exceeds the cell size of 512; raise pravaha.lane.inbox.cell-bytes for this node`) with `pravaha.lane.inbox.cell-bytes: 65536` set; the remedy the message names cannot be applied. On a server the setting exists (TROUBLESHOOTING `PRV-3001`) but an oversize row still stops the query rather than being dead-lettered. Reproduction: `AdvResourceTest#qe167_aRowWithinTheConfiguredCellIsAccepted` (disabled) / `#qe167_observed`.
> **Disposition:** GA-REQUIRED — honour the lane settings in the embedded engine; consider refusing an oversize row per row (DLQ) instead of stopping the query.

### NARROWCAST-1 (MEDIUM) — narrowing casts truncate, and NaN/Infinity cast to BIGINT become numbers
> **Status:** OPEN — `CAST(a AS INT)` publishes `Long.MIN_VALUE → 0`, `Long.MAX_VALUE → -1`; `CAST(i AS SMALLINT)` publishes `2e9 → -27648`; `CAST(d AS BIGINT)` publishes NaN → 0 and +Inf/1e300 → `Long.MAX_VALUE` (so `WHERE CAST(d AS BIGINT) = 0` keeps NaN rows); literals too (`CAST(9223372036854775808 AS BIGINT)` → `Long.MIN_VALUE`). CQ §11 refuses a narrowing `DECIMAL` cast because "rows that do not fit have no answer"; the integer and double casts answer anyway. Reproduction: `AdvExpressionTest#qe007…qe009` (disabled).
> **Disposition:** POST-GA — range-check narrowing casts and refuse NaN/Infinity, routing the row as an overflow.

### DIVMIN-1 (MEDIUM) — Long.MIN_VALUE / -1 is published as Long.MIN_VALUE
> **Status:** OPEN — `Expression.Arithmetic.evaluateLong`'s `DIVIDE` is `l / r`; `+ - *` use `*Exact`. Reproduction: `AdvExpressionTest#qe010_longMinDividedByMinusOneIsNeverPublished` (disabled) / `#qe010_observed`.
> **Disposition:** POST-GA — one guard (`l == Long.MIN_VALUE && r == -1`).

### CKPTSUM-1 (MEDIUM) — a checkpoint has no checksum, so a flipped bit is restored as an answer
> **Status:** OPEN — `FileCheckpointStore` checks magic, version, counts and trailer, not content. Flipping one bit at 16 evenly spaced offsets of the newest `win` checkpoint (2,021 bytes) and restarting over a replayable file source: 8–10 of 16 restarts publish a corrupted window (e.g. `window_end` `1700000020000000001`, `window_start` `1627942485962072064`) with RUNNING and no complaint, for ever. Truncation is detected (QE-079 PASS). Reproduction: `AdvDurabilityTest#qe080_aFlippedBitInACheckpointIsNeverRestoredAsAnAnswer` (disabled) / `#qe080_observed`.
> **Disposition:** POST-GA — CRC32C over the body, verified on load; a mismatch falls back to the previous checkpoint as a truncated one does.

### PUSHPARTIAL-1 (MEDIUM) — a push one query cannot apply leaves the others applied but unpublished
> **Status:** OPEN — `DefaultPravahaEngine.deliver` offers every row to every target, then commits target by target; the first target whose lane died throws `PRV-3010` and the remaining targets are never committed. Their views hide the row until some later push commits it, and a caller who retries the push it was told failed gets it twice (`COUNT(*)` of one row = 2). The javadoc promises the push "returns once every running query … has applied and committed them". Reproduction: `AdvChainTest#qe162_aPushOneQueryRefusesIsAllOrNothingForTheOthers` (disabled) / `#qe162_observed`.
> **Disposition:** POST-GA — commit every healthy target before reporting the failed one, and say in the exception which targets applied the rows.

### NANGROUP-1 (MEDIUM) — a windowed GROUP BY on a DOUBLE can lose a row
> **Status:** OPEN — the windowed aggregate keys groups by a hash of the value's bits, the view keys rows by `Double.equals`. Five rows in one window, `d` = −0.0, 0.0, NaN, NaN (payload 0x7ff8000000000001), 1.0: four view rows whose counts sum to **4** — the two NaN groups collapse into one view row. −0.0 and 0.0 are separate groups everywhere (windowed, view-read `GROUP BY`, `COUNT(DISTINCT)`) although `d = 0` keeps both (TY-3). Reproduction: `AdvAggregateTest#qe045_046_windowedGroupsOnADoubleCountEveryRowOnce` (disabled) / `#qe045_046_observed`, `#qe047_048_049_…`.
> **Disposition:** POST-GA — canonicalise NaN (and decide ±0.0) before hashing a group key, the same way on every path.

### HOPALIGN-1 (MEDIUM) — HOP windows are aligned by their end when the size is not a multiple of the slide
> **Status:** OPEN — `HOP(..., INTERVAL '10' SECOND, INTERVAL '25' SECOND)`, one row at t = 12 s: windows `[-5, 20)` and `[5, 30)`. SQL's HOP (Calcite's `HopEnumerator`, Flink) starts windows at multiples of the slide: `[-10, 15)`, `[0, 25)`, `[10, 35)`. `SlicedWindows.windowEndsContaining` aligns ends; `lastWindowEndFor` assumes starts are aligned, so the two disagree about a row's last window. Reproduction: `AdvAggregateTest#qe065_hopWindowsStartOnMultiplesOfTheSlide` (disabled) / `#qe065_observed`.
> **Disposition:** POST-GA — align starts, or refuse a size that is not a multiple of the slide and say so in CQ §5.

### RETYPERESTORE-1 (MEDIUM) — a checkpoint of another output schema is restored into the view
> **Status:** OPEN — `q = SELECT id, v FROM s` with `v INT64`, a row, a checkpoint; restart with `v` declared `STRING`: `q` recovers RUNNING, its schema says `v VARCHAR`, and its view holds the old `Long` 10 beside new `String`s. A column added elsewhere restores correctly. Reproduction: `AdvDurabilityTest#qe088_aCheckpointOfAnotherOutputSchemaIsNotRestored` (disabled) / `#qe088_observed`.
> **Disposition:** POST-GA — record the output schema with the checkpoint and refuse (or re-derive) a restore whose schema differs.

### SAMEPIDCLAIM-1 (MEDIUM) — two engines in one process share a state directory, and the second's close unclaims the first's
> **Status:** OPEN — `StateOwnership.refuseIfHeldByAnother` returns early for "our own claim, being re-made" when the pid matches, so a second embedded engine in the same JVM with the default node id (`pravaha-embedded`) on the same directories starts, journals and checkpoints beside the first; when it closes it deletes the ownership markers while the first runs, after which an engine with another node id is accepted. A different node id is refused (`PRV-4003`) while the markers stand. Reproduction: `AdvDurabilityTest#qe083_aSecondEngineOnARunningEnginesDirectoryIsRefused` (disabled) / `#qe083_observed`.
> **Disposition:** POST-GA — track live claims per process (an in-JVM registry), not only per pid.

### GETTABLES-1 (MEDIUM) — GetTables lists nothing to a non-admin under the catalogue
> **Status:** OPEN — with `pravaha.catalog.enabled` and `authority: catalog`, `FlightSqlClient.getTables` returns no rows to `ana` (granted `SELECT` on `payments`, whose read returns her two rows) and none to `eve` (who registered her own `payments` in `globex`); `ops` and `gops` (role `admin`) are listed every tenant's views. Nothing leaks, but a BI tool's catalogue is empty for every ordinary user. Reproduction: `AdvSecurityTest#qe168_getTablesListsTheViewsACallerMayRead` (disabled) / `#qe168_observed`.
> **Disposition:** GA-REQUIRED — list what `SELECT` (or ownership) reaches, resolved in the caller's tenant, as `SHOW CONTINUOUS QUERIES` does.

### LISTCOUNT-1 (MEDIUM) — LIST tells a catalogue-filtered reader the view's whole cardinality
> **Status:** OPEN — `ana`, narrowed by `CREATE ROW FILTER region_scope AS region = session_attribute('region')`, reads 2 of 3 rows of `payments` and is told `ROWS IN = 3` by `pravaha.list`. SX-18 withholds the count (`-1`) for a principal whose `SecurityPolicy` decision carries a row filter; the catalogue's row filters are not consulted. Reproduction: `AdvSecurityTest#qe111_listWithholdsAFilteredReadersCount` (disabled) / `#qe111_observed`.
> **Disposition:** GA-REQUIRED — the catalogue's narrowing must withhold the count as SX-18 does.

### DLQPROJ-1 (MEDIUM) — a row that fails evaluation stops the query even with a dead-letter queue
> **Status:** OPEN — embedded engine with `pravaha.dlq.directory`, `filesystem` source, `SELECT id, a / b FROM z` over `1,10,2 / 2,10,0 / 3,10,5`: the query stops (`PRV-8003 … division by zero in a projection; the record is routed to the DLQ …`) and the DLQ holds 0 entries. CQ §11 says such a row "goes to the dead-letter queue, as a 64-bit overflow does". W8-11 recorded the lane-level poison row as deliberately not done; the guide was not changed with it. Reproduction: `AdvDurabilityTest#qe012_aRowThatDividesByZeroGoesToTheDeadLetterQueue` (disabled) / `#qe012_observed`.
> **Disposition:** GA-REQUIRED — either dead-letter the row from the lane or correct CQ §11 and the `PRV-3010`/`ArithmeticException` texts.

### NANNOT-1 (LOW) — the negation of a DOUBLE comparison is not its IEEE complement
> **Status:** OPEN — `PredicateCompiler.negate` turns `NOT (d > 5)` into `d <= 5`, false for NaN, so a NaN row is in neither `d > 5` nor `NOT (d > 5)`; `IS FALSE` and `IS NOT FALSE` over such a comparison go wrong the same way (rows dropped and added). TY-3 chose IEEE 754, under which `NaN > 5` is FALSE and its negation TRUE. 26–33 of 1,500 random predicates per seed differ only on NaN rows for this reason, interpreted and generated alike. Reproduction: `AdvExpressionTest#qe014_notOfAComparisonKeepsANanRowAsIeeeSays` (disabled).
> **Disposition:** POST-GA — negate a floating-point comparison as `NOT(cmp) AND col IS NOT NULL`, not the opposite operator.

### FARTIME-1 (LOW) — a CSV timestamp after 2262 wraps into 1677
> **Status:** OPEN — `DelimitedCodec.temporal` computes `getEpochSecond() * 1_000_000_000L + getNano()` unchecked; `3000-01-01T00:00:00Z` is stored as `-4389808147419103232` ns. Reproduction: `AdvDurabilityTest#qe164_aTimestampPastTheNanosecondRangeIsRefusedNotWrapped` (disabled) / `#qe164_observed`.
> **Disposition:** POST-GA — `Math.multiplyExact`/`addExact` and a decode error with the line.

### MINRETRACT-1 (LOW) — windowed MIN/MAX stop the query on the first retraction
> **Status:** OPEN — documented only in LIMITS' pushdown paragraph and LIFE-126's path; CQ §13 marks `MIN`/`MAX` ✅ in windows and nothing refuses them over a stream that can carry deletes (an `op.column` file, `postgres-cdc`). Reproduction: `AdvAggregateTest#qe044_windowedMinGivenARetractionStopsTheQuery` (passes as observed).
> **Disposition:** NOTE — say it in CQ §13, or refuse at registration when a source can retract (as `PRV-2042` does for repeating sources).

### MASKALERT-1 (LOW) — an alert comparing a masked column is accepted, then broken
> **Status:** OPEN — `ana` (card masked): `CREATE ALERT raw_guess ON payments WHERE card = '4111-1111' NOTIFY ops_log` answers `ACTIVE`; the alert then shows `following=BROKEN` and never fires (fails closed). SECURITY.md lists "an alert's WHERE" among the plan-time `PRV-7006` refusals. Reproduction: `AdvSecurityTest#qe105_anAlertComparingAMaskedColumnIsRefusedWhenCreated` (disabled) / `#qe105_observed`.
> **Disposition:** POST-GA — run `AlertAccess.narrowingFor` in `create` so the refusal reaches the person creating it.

### EMITROOM-1 (LOW) — a window of ~836 k groups stops its query below the view ceiling
> **Status:** OPEN — 800,000 groups in one tumbling window are answered; 1,000,050 stop the query at the 836,416th emitted group with `PRV-3001 no room to emit a window result`, not the view's `PRV-4022` ceiling (1,000,000) that names the limit. Reproduction: `AdvResourceTest#qe144_145_146_aMillionGroupsKeysAndDistinctValues` (observed).
> **Disposition:** NOTE — size the emission buffer to the view ceiling, or refuse with the ceiling's own code and message.

### QOQAPI-1 (LOW) — the embedded register call cannot build on a view
> **Status:** OPEN — `engine.register("down", "SELECT g, SUM(v) AS s FROM up GROUP BY g", "g")` → `PRV-2002 Object 'up' not found`; the same SQL as `CREATE CONTINUOUS QUERY` works. Reproduction: `AdvChainTest#qe068_theEmbeddedRegisterCallCannotBuildOnAView`.
> **Disposition:** POST-GA — route `register` through the path the statement uses.

### UNCODEDAPI-1 (LOW) — failures without a code, or without a message
> **Status:** OPEN — `PravahaEngine.register(name, sql)` with no key → `IllegalArgumentException`; `push` of an `Instant` past 2262 → `ArithmeticException: long overflow`; `register` when the checkpoint directory cannot be created → `UncheckedIOException`; 3,000 nested parentheses and a 1.2 MiB `OR` chain → `PRV-2001  null` (the second after 30.5 s of planning); and a lane failure from `Math.subtractExact` reads `java.lang.ArithmeticException` with no "long overflow" once the JIT has compiled it (OmitStackTraceInFastThrow). Reproductions: `AdvAggregateTest#qe062_…`, `#qe063_064_…`, `AdvDurabilityTest#qe084_085_…`, `AdvResourceTest#qe139_…`, `AdvChainTest#qe162_observed`.
> **Disposition:** POST-GA.
```

### Design concerns recorded as NOTE (behaving as documented)

- **QE-113** — six tautologies pass the vacuity analysis and are bound: `amount * 0 = 0`, `ABS(amount) >= 0`,
  `region || '' = region`, `amount + 0 = amount`, `SUBSTRING(region FROM 1) = region`, `amount * amount >= 0`.
  SECURITY.md says the analysis is "sound, not complete" and names expression-vs-expression comparisons as
  missed; the six all reach that clause. Worth a rule for "an arithmetic identity on a NOT NULL column".
- **QE-130** — an exempt owner (`admin`) can register `SELECT id, card FROM payments` and grant it; analysts
  then read raw cards. SECURITY.md: building on a view needs `BUILD_ON`, and the owner of the new view decides.
- **QE-129** — `SHOW GRANTS ON VIEW payments` is answered for a principal holding only `SELECT`, including who
  granted what to whom.
- **QE-122** — the `admin` role is global: `gops`, an admin of `globex`, is listed (and may address) `acme`'s
  views, as `ops` of `acme` is `globex`'s. SECURITY.md: "Only an admin reaches another tenant's view". A
  deployment that gives each tenant its own administrator has made each of them every tenant's.
- **QE-118** — reading another tenant's qualified name answers `PRV-4023` in identical words whether it exists
  or not (no leak); SECURITY.md says `PRV-7002`.
- **QE-024** — `SUBSTRING(t FROM 2 FOR -1)` answers `''`; SQL raises "negative substring length".
- **QE-040** — the planner simplifies away sub-expressions whose value is not needed (`CASE` with equal branches,
  `-(-x)`), and the error SQL would raise with them (`ABS(Long.MIN)`, `-(-MIN)`, a division by zero in a dead
  condition). Common in engines; recorded so nobody reads it as a guard.
- **QE-155** — an embedded subscription with `FAIL` overflow closes silently: the `Consumer` is not told.
- **QE-161** — one-millisecond tumbling windows stop their query (`PRV-3022`, TIME-1) after an event-time gap
  of ~2.8 hours, which an idle stream reaches without any bad timestamp.
- **QE-167 (server half)** — a row wider than `cell-bytes` stops the query rather than being dead-lettered.
- **QE-086** — with the journal path a symlink to `/dev/full`, `RegistryJournal.replayAll`'s `readAllBytes`
  runs out of heap (`Requested array size exceeds VM limit`): the journal is read whole into one array.
- Embedded `push` is not replayable: rows pushed after the last checkpoint are not there after a restart.
  By design (a host that pushes owns its replay); QE-067 and QE-076 wait a checkpoint before restarting.

### What could not be tested

- **QE-086 disk full** — BLOCKED: making a full filesystem needs root (a loop or tmpfs mount); the `/dev/full`
  surrogate tests a device, not ENOSPC on a real file.
- **QE-093 spill + restart** — NOT RUN: the embedded engine has no spill setting
  (`InterpretedPipeline.configureSpill` is wired by `PravahaNode` only).
- **QE-106 dead letters of a narrowed reader's query**, **QE-132 audit of a denied read**, **QE-138 metrics
  labels across tenants**, **QE-156 lane rebalance** — NOT RUN: each is read or driven only over HTTP/CLI,
  the companion round's surfaces.
- **QE-131 the assistant registering without confirmation** — NOT RUN: drafting lives in the Python SDK and
  the console (ADR-058); the engine only fingerprints drafts.
- Two nodes on one data directory across *processes* is covered by `NodeCrashRestartTest`; QE-083 covers
  the in-process case it does not.
- Cluster mode is out of scope (single-node GA, LIMITS).

---

## X — Expressions and types

Harness: `AdvExpressionTest` — a fresh embedded engine per query, stream `s` (schema in the case file), the
seven edge rows of `AdvExpressionTest.ROWS`, interpreted path. "State" is `RegisteredQuery.state()` after the
pushes; rows are `id|value` from `view().scan()`.

| ID | Verdict | Actual | Evidence |
|---|---|---|---|
| QE-001 | **FAIL** | `i * 2` with i = 2,000,000,000 published `1|-294967296`, state RUNNING | `#qe001_observed`; repro `#qe001_…` → NARROWINT-1 |
| QE-002 | **FAIL** | `i + i` → `-294967296`; `i * i` → `-1651507200` | `#qe002_…` (disabled, fails) |
| QE-003 | **FAIL** | `-i` at `Integer.MIN_VALUE` → `-2147483648` | `#qe003_…` |
| QE-004 | **FAIL** | `ABS(i)` at `Integer.MIN_VALUE` → `-2147483648` (Q-12 fixed only the BIGINT case) | `#qe004_…` |
| QE-005 | **FAIL** | `sm + sm` (30000) → `-5536`; `sm * sm` → `-5888` | `#qe005_…` |
| QE-006 | **FAIL** | `ty + ty` (100) → `-56` | `#qe006_…` |
| QE-007 | **FAIL** | `CAST(a AS INT)`: `Long.MIN → 0`, `Long.MAX → -1` | `#qe007_…` → NARROWCAST-1 |
| QE-008 | **FAIL** | `CAST(i AS SMALLINT)`: 2e9 → `-27648` | `#qe008_…` |
| QE-009 | **FAIL** | `CAST(d AS BIGINT)`: NaN → 0, +Inf and 1e300 → `9223372036854775807`; `WHERE CAST(d AS BIGINT) = 0` keeps the NaN row | `#qe009_…` |
| QE-010 | **FAIL** | `a / b` = `Long.MIN_VALUE / -1` → `-9223372036854775808`, RUNNING | `#qe010_observed` → DIVMIN-1 |
| QE-011 | PASS | `a - 1` at `Long.MIN_VALUE`: `FAILED PRV-8003 … ArithmeticException`, nothing published | `#qe011_…` |
| QE-012 | **FAIL** | DLQ configured, `filesystem` source: `FAILED PRV-8003 … division by zero … routed to the DLQ`, DLQ entries 0 | `AdvDurabilityTest#qe012_observed` → DLQPROJ-1 |
| QE-013 | **FAIL** | `WHERE i * 2 < 0` → `[2, 5]` (64-bit, correct), while `SELECT i * 2 … WHERE i * 2 > 0` publishes `1|-294967296` | `#qe013_…` (part of NARROWINT-1) |
| QE-014 | **FAIL** | `NOT (d > 5)` → `[2, 3, 6, 7]`; `d > 5` → `[4, 5]`; NaN row 1 in neither | `#qe014_observed` → NANNOT-1 |
| QE-015 | **FAIL** | `NOT (d < 5)` → `[4, 5]`, NaN row missing | `#qe014_…` (same test) |
| QE-016 | PASS | `d <> d` → `[1]` (NaN only) | `#qe016_017_…` |
| QE-017 | PASS | `d = 0` → `[2, 3]` (−0.0 and 0.0) | same |
| QE-018 | PASS | `i < 3000000000`, `i > -3000000000`: all six non-null rows | `#qe018_…` |
| QE-019 | PASS | `a NOT IN (7, NULL)` → `[]`; `a IN (7, NULL)` → `[3]` | `#qe019_…` |
| QE-020 | PASS | reversed `BETWEEN` → `[]`; `NOT (a BETWEEN -7 AND 7)` → `[1, 2]` | `#qe020_…` |
| QE-021 | PASS | `t LIKE 'a.%'` → `[1]` (`abc` not matched) | `#qe021_022_…` |
| QE-022 | PASS | `t LIKE '_'` → `[2, 4]` (`ß`, 😀) | same |
| QE-023 | PASS | `SPLIT_INDEX(t, '.', 1)` → `1|b`; `SPLIT_INDEX(t, '|', 0)` → `1|a.b` | `#qe023_…` |
| QE-024 | PASS (NOTE) | `FROM 0 FOR 2` → `a`, 😀 whole; `FROM -1` → whole; `FOR -1` → `''` (SQL errors) | `#qe024_…` |
| QE-025 | PASS | `UPPER('ß')` → `SS` | `#qe025_…` |
| QE-026 | PASS | `TRIM` leaves the tab | same |
| QE-027 | PASS | unmatched group → null | same |
| QE-028 | N/A | moved to QE-149 | — |
| QE-029 | PASS | `t || 'x'` with NULL → null | same |
| QE-030 | PASS | `CASE WHEN i > 0 THEN 1 END` → null where no branch | same |
| QE-031 | PASS (NOTE) | SQL types `amt*amt*amt` `DECIMAL(38, 12)` (scale kept, nothing to refuse); the 42-digit row stops the query (`does not fit DECIMAL(38, 12)`), nothing rounded published — the DLQ claim is DLQPROJ-1 | `#qe031_…` |
| QE-032 | PASS | `amt > 99999999999999.99985` → `[1]`; `> …99995` → `[]` | `#qe032_…` |
| QE-033 | PASS | `CAST(a AS DECIMAL(19,0))` exact at MIN/MAX | `#qe033_034_…` |
| QE-034 | PASS | `a * 1.5` → `-13835058055282163712.0`, `13835058055282163710.5` | same |
| QE-035 | PASS | `d / 0` → NaN, ±Infinity | `#qe035_…` |
| QE-036 | PASS | windowed `AVG(v)` of −7, 0, MAX, MAX, MIN, MIN → `-1`, `SUM` −9 (128-bit netting) | `AdvAggregateTest#qe036_…` |
| QE-037 | PASS | 1,500 generator-eligible predicates per seed ran generated (`executionPaths` `generated:`) and agreed with O and the interpreter except QE-014's NaN class | `AdvPredicateDifferentialTest#qe037_039_…`, seeds 20261002, 2, 3, 4 |
| QE-166 | **FAIL** | after JIT warm-up the lane failure reads `PRV-3010 … java.lang.ArithmeticException` with no "long overflow" | full-suite run, `AdvChainTest#qe162_observed` → UNCODEDAPI-1 |

## P — Differential predicates and expressions

| ID | Verdict | Actual | Evidence |
|---|---|---|---|
| QE-038 | PASS (with QE-014) | 400 predicates (seed 20261001) and 1,500 each (seeds 1, 2, 3), 120 rows: every view equal to the three-valued oracle except 8 / 26 / 33 / 26 predicates whose only difference is NaN rows under `NOT`/`IS [NOT] FALSE` of a DOUBLE comparison (e.g. `p164 NOT ((… d <= 1.0E0 …)) IS FALSE)` keeps 5 NaN rows O drops) — QE-014's defect; 0 other mismatches; 0 refusals | `AdvPredicateDifferentialTest#qe038_…`, `-Dpravaha.qa.seed=1..3 -Dpravaha.qa.predicates=1500` |
| QE-039 | PASS (with QE-014) | generated: 400 (seed 20261002) + 1,500 × 3 (seeds 2, 3, 4), all generated, 0 non-NaN mismatches | `#qe037_039_…` |
| QE-040 | PASS (with notes) | 300 + 3 × 600 random BIGINT expressions × 60 rows against `BigInteger`: no row published with a value differing from O. Mismatches only where O raises an error and the engine publishes because the planner removed the sub-expression (`CASE` with equal branches, `-(-x)`), or where a literal is narrowed (`-(-9223372036854775808)` → MIN, part of NARROWINT/NARROWCAST); 1,133 queries stopped on rows O also refuses | `AdvArithmeticDifferentialTest`, seeds 40–43 |

## W — Aggregates, windows, late data

| ID | Verdict | Actual | Evidence |
|---|---|---|---|
| QE-041 | PASS (with QE-163) | tumble 10 s, L = 0 and L = 10 s: 100 seeds × 300 rows, ≈5,400 advances compared, 0 mismatches once O answers an all-NULL group as the engine does; with `-Dpravaha.qa.strictNulls=true` 16/25 and 18/25 seeds fail, every one on an all-NULL group | `AdvWindowDifferentialTest`, seeds 100, 200, 300, 400 |
| QE-042 | PASS (with QE-163) | hop 30 s / 10 s, L = 0 and 10 s: 60 seeds × 200 rows, 0 mismatches | same |
| QE-043 | PASS (with QE-163) | `MIN`/`MAX`, inserts only, tumble and hop: 100 seeds, 0 mismatches | same |
| QE-044 | **FAIL** | a retraction stops the windowed `MIN` query: `PRV-3020 MIN cannot handle a retraction` | `AdvAggregateTest#qe044_…` → MINRETRACT-1 |
| QE-045 | **FAIL** | −0.0 and 0.0 are two groups (`…|-0.0|1`, `…|0.0|1`) | `#qe045_046_observed` → NANGROUP-1 |
| QE-046 | **FAIL** | two NaN payloads: one view row `NaN|1`; five rows in, counts sum to 4 | same |
| QE-047 | PASS | windowed `COUNT(DISTINCT d)` = 4 (NaN payloads one value, ±0.0 two) — consistent with QE-048/049 | `#qe047_048_049_…` |
| QE-048 | **FAIL** | view read `GROUP BY d`: `-0.0|1, 0.0|1, 1.0|1, NaN|2` — ±0.0 split though `d = 0` keeps both | same → NANGROUP-1 |
| QE-049 | PASS | view read `COUNT(DISTINCT d)` = 4, consistent with QE-048 | same |
| QE-050 | PASS | +100 onto MAX: `PRV-3025`, query stops | `#qe050_051_052_…` |
| QE-051 | PASS | +MAX, +MAX, MIN, MIN, −7 in one push answered | same |
| QE-052 | PASS | MAX in one batch fine; the next batch ending outside → `PRV-3025` | same |
| QE-053 | PASS | t = 9 s after watermark 10 s with L = 0: dropped, count stays 1 | `#qe053_…` |
| QE-054 | PASS | late row within L: answer 2 → 3; the subscriber's commit holds `[-1, 1]` | `#qe054_055_056_…` |
| QE-055 | PASS | beyond lateness: never applied | same |
| QE-056 | PASS | retraction after the window is final: ignored, answer stays 3 | same |
| QE-057 | PASS | top-3 per partition, 20 seeds × 150 inserts/retractions, `(k, rn, v)` equal to O after every op | `#qe057_…`, seeds 500–519 |
| QE-058 | PASS | retracting a row never held: `PRV-3024`, query stops | `#qe058_…` |
| QE-059 | PASS | interval join, 15 seeds × 120 ops with retractions: equal to the nested loop | `#qe059_060_…`, seeds 700–714 |
| QE-060 | PASS | self join, same seeds: equal | same |
| QE-061 | PASS | `SUM` = MAX answered; one more row → `AVG` read `PRV-3025` | `#qe061_…` |
| QE-062 | **FAIL** | `engine.register("n", "SELECT COUNT(*) AS c FROM src")` → uncoded `IllegalArgumentException … needs at least one key column`; a global count has no key to give | `#qe062_…` → UNCODEDAPI-1 |
| QE-063 | PASS (LOW) | `Instant` at ±2^63 ns accepted, windows assigned, query RUNNING; an `Instant` beyond the range → uncoded `ArithmeticException` from `push` (UNCODEDAPI-1) | `#qe063_064_…` |
| QE-064 | PASS | NULL event time → `PRV-8102 … null in NOT NULL column 'ts'` | same |
| QE-065 | **FAIL** | windows `[-5 s, 20 s)` and `[5 s, 30 s)`; SQL: `[-10, 15)`, `[0, 25)`, `[10, 35)` | `#qe065_observed` → HOPALIGN-1 |
| QE-163 | **FAIL** | all-NULL group: windowed `…|a|0|0|0|0|0`, view read `x|0|0|0|0`, over a view `x|0|0|0` | `#qe163_observed`, `#qe163_observed_allNull…` → ALLNULLAGG-1 |
| QE-165 | **FAIL** | 800,000 groups answered; 1,000,050 → `FAILED PRV-3001 no room to emit a window result for key [k836416]` | scratch probe (800 k), `AdvResourceTest#qe144_…` → EMITROOM-1 |

## Q — Queries on queries, embedded push

| ID | Verdict | Actual | Evidence |
|---|---|---|---|
| QE-066 | PASS | `up → mid → top`, proper updates, blind upserts, retractions; 40 seeds × 150 ops, 600 comparisons: every view equal to O (the keyed view's "last row to gain weight" rule included) | `AdvChainTest#qe066_…`, seeds 900–909, 2000–2029 |
| QE-067 | PASS | 120 ops, checkpoint, restart: all three views equal; 40 more ops followed | `#qe067_…` |
| QE-068 | **FAIL** | `register()` → `PRV-2002 Object 'up' not found`; `CREATE` → OK | `#qe068_…` → QOQAPI-1 |
| QE-069 | PASS | refused at the 10th query: `PRV-8027 'l10' would be 9 queries deep … at most 8` | `#qe069_to_073_…` |
| QE-070 | PASS | `PRV-8025 … up reads top reads mid reads up` | same |
| QE-071 | PASS | `PRV-8024 'up' cannot be dropped: [mid] reads its answer` | same |
| QE-072 | PASS | `PRV-2075 … uses MIN` | same |
| QE-073 | PASS | `PRV-8026` | same |
| QE-074 | PASS | `mid` `FAILED PRV-3025`; `top` RUNNING, still answers `g0|70` | `#qe074_…` |
| QE-075 | PASS | `mid` paused across 100 ops, resumed: equal to O | `#qe075_…` |
| QE-162 | **FAIL** | first push `PRV-3010`; `good` shows nothing; after the caller's retry the window counts the one row twice (`2`) | `#qe162_observed` → PUSHPARTIAL-1 |

## D — Durability

| ID | Verdict | Actual | Evidence |
|---|---|---|---|
| QE-076 | PASS | open window across a clean restart: `w0|a|3|6` (two rows before, one after) | `AdvDurabilityTest#qe076_089_091_092_…` |
| QE-077 | PASS | followed CSV (20 chunks × 30 lines, 25 % deletes), child JVM SIGKILLed 5 / 7 / 7 times (seeds 77, 78, 79) at 0–900 ms after appends: `proj`, `win` and `agg` (a query over `proj`) equal to O every time | `#qe077_078_…`, `-Dpravaha.qa.seed=78/79 -Dpravaha.qa.kills=7` |
| QE-078 | PASS | kills at +0 ms (right after an append, before a checkpoint) and late in an interval are among them | same log lines |
| QE-079 | PASS | newest checkpoint of each query truncated: previous used, answer EQUAL; with `keep = 1` (QE-095) also EQUAL (full replay) | `#qe079_080_095_…` |
| QE-080 | **FAIL** | 16 single-bit flips in `win`'s newest checkpoint: 8 and 10 of 16 published DIFFERENT (two runs); the other run of the coarse variant once refused `PRV-8026` for `agg` | `#qe080_observed` → CKPTSUM-1 |
| QE-081 | PASS | last record cut by 7 bytes: `queries [first]` recovered | `#qe081_…` |
| QE-082 | **FAIL** | middle record's length damaged: `[alpha]` of three, no error; `delta` registered after is lost at the next start | `#qe082_observed` → JOURNALMID-1 |
| QE-083 | **FAIL** | same id, same JVM: second engine `OK`; markers gone after it closes; an `intruder` id then `OK` (refused `PRV-4003` before) | `#qe083_observed` → SAMEPIDCLAIM-1 |
| QE-084 | PASS | checkpoint directories made read-only: `failures=5 last=checkpoint failed (5 so far): cannot store checkpoint 3 …`, reads still `a|1` | `#qe084_085_…` |
| QE-085 | PASS (LOW) | `register` refused, nothing journalled (`q2` absent) — as `UncheckedIOException: cannot create the checkpoint directory`, uncoded (UNCODEDAPI-1) | same |
| QE-086 | BLOCKED | see "What could not be tested"; the `/dev/full` surrogate: `OutOfMemoryError: Requested array size exceeds VM limit` from the journal replay | `#qe086_087_…` |
| QE-087 | PASS | `PRV-4004 cannot create the state directory … FileAlreadyExistsException` | same |
| QE-088 | **FAIL** | column added: restored correctly. Column retyped `INT64 → STRING`: `schema … v VARCHAR`, values `1=Long, 2=String` | `#qe088_observed` → RETYPERESTORE-1 |
| QE-089 | PASS | `dropped` not recovered | `#qe076_089_091_092_…` |
| QE-090 | PASS | `CREATE OR REPLACE … WITH (backfill = 'history', cutover = 'manual')` then stop mid-backfill and restart: all views EQUAL to O, `win` RUNNING on the running version | `#qe090_…` |
| QE-091 | **FAIL** | survivors after a restart: nothing dropped `[a|1, a|2]`; second name dropped `[a|1, a|2]`; first name dropped `[]` | `#qe091_observed` → SHAREDLOSS-1 |
| QE-092 | PASS | 74 registrations (past the 64-query multiplexing threshold) survive a restart with their rows (`many_69` = `a|1, a|2, a|3`) | `#qe076_089_091_092_…` |
| QE-093 | NOT RUN | embedded engine has no spill setting | — |
| QE-094 | PASS | garbage appended to the DLQ file: engine starts RUNNING, counts and page still readable | `#qe094_…` |
| QE-095 | PASS | `keep = 1`, only checkpoint truncated: EQUAL (full replay from the file) | `#qe079_080_095_…` |
| QE-164 | **FAIL** | `3000-01-01T00:00:00Z` stored as `-4389808147419103232` ns | `#qe164_observed` → FARTIME-1 |

## S — Security semantics

Harness N (`AdvSecurityTest`): a `PravahaNode` per test, Flight on an ephemeral port, token authentication,
catalogue on with `authority: catalog`, a `log` notifier, rows pushed into the registered computations
directly (as `CatalogPoliciesEndToEndTest` does). Baseline: `payments` granted `SELECT, SUBSCRIBE` to role
`analyst`, `region_scope` (row filter on the `region` claim) and `card_last4` (mask) bound to it, three
rows p1 EU / p2 US / p3 EU.

| ID | Verdict | Actual | Evidence |
|---|---|---|---|
| QE-096 | PASS | `WHERE card = …` → `PRV-7006 payments.card is masked for you … a filter operand` | `#qe096_to_100_102_…` |
| QE-097 | PASS | `SUBSTRING`, `LIKE`, `UPPER`, `card || ''`, `IS NULL`, `IN`, `REGEXP_EXTRACT`, and a derived-table column `bin` compared outside → all `PRV-7006` | same |
| QE-098 | PASS | projected `CASE WHEN card LIKE '4111%'` / `SUBSTRING(card …) = '4111'` → `[p1|0, p3|0]`; `SUBSTRING(card FROM 1 FOR 4)` → `XXXX`: computed over the masked value | same |
| QE-099 | PASS | `GROUP BY card`, `COUNT(DISTINCT card)`, `MIN(card)` → `PRV-7006` | same |
| QE-100 | PASS | `ROW_NUMBER() OVER (… ORDER BY card)` → `PRV-7006` | same |
| QE-101 | PASS | `amount` masked to 0: `[p1|0, p3|0]`; `100 / (amount - 10)` → `[p1|-10, p3|-10]` (no error from the raw 10); `SUM(amount)` → `PRV-7006` | `#qe101_…` |
| QE-102 | PASS | `id` masked: `WHERE id = 'p1'` → `PRV-7006`; shown as `p?` | `#qe102_…` |
| QE-103 | PASS | snapshot `[[p1, EU, XXXX-1111, 10, 1], [p3, EU, XXXX-3333, 30, 1]]` | `#qe103_133_…` |
| QE-104 | PASS | `ana_copy` built by `ana` holds `p1|EU|XXXX-1111, p3|EU|XXXX-3333` | `#qe104_105_112_…` |
| QE-105 | **FAIL** | both alerts (`= '4111-1111'`, `= 'XXXX-1111'`) created ACTIVE, then `following=BROKEN`, 0 keys, 0 sent | `#qe105_observed` → MASKALERT-1 |
| QE-106 | NOT RUN | HTTP surface | — |
| QE-107 | PASS | `WHERE id = 'p2'` → `[]`; `id IN ('p1','p2')` → `[p1]` | `#qe107_to_110_137_…` |
| QE-108 | PASS | `INDEX (region)`: `region = 'US'` → `[]`; `IN ('US','EU')` → `[p1, p3]` | same |
| QE-109 | PASS | `RANGE (amount)`: US range and exact key → `[]` | same |
| QE-110 | PASS | `COUNT(*), SUM(amount)` → `2|40`; `GROUP BY region` → `EU|2` | same |
| QE-111 | **FAIL** | `pravaha.list` as `ana`: `payments=3`; her own `COUNT(*)` → 2 | `#qe111_observed` → LISTCOUNT-1 |
| QE-112 | PASS | `ana` grants `SELECT` on her view to `bob` (US): `bob` reads `p1|EU|XXXX-1111, p3|EU|XXXX-3333` — the registrant's narrowing, as SECURITY.md says | `#qe104_105_112_…` |
| QE-113 | PASS (NOTE) | 11 of 17 tautologies refused (`PRV-7038`/`PRV-7003`, incl. `LIKE '%'`, `IN … OR NOT IN …`, full-range `BETWEEN`, `UPPER(r) = UPPER(r)`, `TRIM`, `IS NOT NULL OR IS NULL`, `x = x OR …`); 6 accepted (see NOTE) and `ana` then reads all three rows | `#qe113_…` |
| QE-114 | PASS | claim `EU' OR '1'='1` → `[]` | `#qe114_…` |
| QE-115 | PASS | `eve` (globex) registers `payments`: OK, reads her own | `#qe115_to_124_136_…` |
| QE-116 | PASS | alert names equal to acme's query or alert: accepted as fresh names; an alert on acme's view: `PRV-8042` in the same words as on a name nobody holds | `#qe116_120_121_…` (distinguishable pairs: none) |
| QE-117 | PASS | `gops` creates `region_scope` in globex: as a fresh name | same |
| QE-118 | PASS (NOTE) | `"acme.default.payments"` and `"acme.default.nothing_here"` → `PRV-4023` in identical words | `#qe115_to_124_136_…` |
| QE-119 | PASS | `SHOW CONTINUOUS QUERIES` as `eve` lists her `payments` only | same |
| QE-120 | PASS | `DROP`/`PAUSE` of acme's name and of a name nobody holds: identical `PRV-7002` (resolved as `globex.default.<name>`) | same |
| QE-121 | PASS | `FROM acme_only` and `FROM never_was`: identical `PRV-2002` | same |
| QE-122 | PASS | `GetTables` as `eve` lists no `acme` view (it lists nothing at all — QE-168); admins of either tenant see both tenants' (NOTE) | same |
| QE-123 | PASS | `FROM paymnts` → `PRV-4023` naming only `paymnts` | same |
| QE-124 | PASS | `[acme.default.payments, globex.default.payments]`, 2 computations | same |
| QE-125 | PASS | `ana` `DROP`/`PAUSE` → `PRV-7002` | `#qe125_127_129_…` |
| QE-126 | PASS | drop and re-create: grants and policy bindings went with the old view; `ana` refused `PRV-7002` on the new one (never read it unfiltered) | `#qe126_…` |
| QE-127 | PASS | `ana` `GRANT … TO USER eve`, `GRANT MANAGE … TO USER ana` → `PRV-7033` | `#qe125_127_129_…` |
| QE-128 | PASS | `REVOKE SUBSCRIBE`: open subscription ended after 2,004 ms with `PRV-7002` | `#qe128_…` |
| QE-129 | PASS (NOTE) | `bob` with `MODIFY`: unbind policy, `OWNER TO`, `GRANT` → `PRV-7033`; `PAUSE` allowed (documented); `SHOW EFFECTIVE ACCESS FOR USER ana` refused; `SHOW GRANTS` answered (NOTE) | same |
| QE-130 | PASS (NOTE) | `ops`'s unmasked copy, granted: `ana` reads `4111-1111` | `#qe130_…` |
| QE-131 | NOT RUN | assistant surface | — |
| QE-132 | NOT RUN | audit read over HTTP | — |
| QE-133 | PASS | `ALTER VIEW payments UNSET POLICY card_last4` ends the open subscription with `PRV-7007` | `#qe103_133_…` |
| QE-134 | PASS | `eve` executing `ana`'s prepared handle → `PRV-4023 no views are registered` (eve's tenant has none); no rows | `#qe134_…` |
| QE-135 | PASS | `ana` forks `payments` → `PRV-7002 … may not debug` | `#qe135_…` |
| QE-136 | PASS | `eve`: `CREATE … "acme.default.x"` → `PRV-7002 … a qualified name reaches outside the caller's tenant` | `#qe115_to_124_136_…` |
| QE-137 | PASS | `COUNT(*) … WHERE amount > 15` → `[1]` (only p3) | `#qe107_to_110_137_…` |
| QE-138 | NOT RUN | HTTP surface | — |
| QE-168 | **FAIL** | `GetTables`: `ana` → `[]` while her read returns `[p1, p3]`; `eve` → `[]`; `ops` → `[null.null.payments]` (and `globex.default.payments` when it exists) | `#qe168_observed` → GETTABLES-1 |

## R — Resource abuse

| ID | Verdict | Actual | Evidence |
|---|---|---|---|
| QE-139 | PASS (LOW) | 3,000 parentheses → `PRV-2001  null` in 25 ms (coded, empty message — UNCODEDAPI-1); 300 nested `CASE` → OK in 346 ms | `AdvResourceTest#qe139_…` |
| QE-140 | PASS | `IN` of 20,000 → `PRV-2021` in 313 ms | same |
| QE-141 | PASS | 200 nested derived tables → OK in 210 ms | same |
| QE-142 | PASS | cross join → `PRV-2020` | same |
| QE-143 | PASS | 1,500 distinct registrations on one engine, one push of 120 rows, every view checked against O | `AdvPredicateDifferentialTest` (seeds 1–3) |
| QE-144 | PASS | 1,000,050 groups: coded stop (`PRV-3001`, see QE-165), heap ≈1 GB, no OOM | `#qe144_145_146_…` |
| QE-145 | PASS | `COUNT(DISTINCT v)` over 1,000,050 values → `1000050` | same |
| QE-146 | PASS | projection view past 1,000,000 keys → `PRV-4022 … past its ceiling of 1000000` | same |
| QE-147 | PASS | top-N at 1,000,001 held rows → `PRV-4001` | `#qe147_…` |
| QE-148 | PASS | a 65-column stream is refused when declared: `PRV-3030` | `#qe148_…` |
| QE-149 | PASS | `REGEXP_EXTRACT(t, '(a+)+$')` over 16–40 `a`s + `b`: ≤ 9 ms each; engine unaffected | `#qe149_…` |
| QE-150 | PASS | 10 MiB string → `PRV-8004 … exceeds the cell size of 512` (coded; see QE-167) | `#qe150_…` |
| QE-151 | PASS | 10,000-character name → `PRV-8008` | `#qe139_…` |
| QE-152 | PASS (NOTE) | 1.2 MiB `OR` chain → `PRV-2001  null` after 30.5 s | same |
| QE-153 | PASS | third query in a tenant capped at 2 → `PRV-8020`; another tenant unaffected | `AdvSecurityTest#qe153_…` |
| QE-154 | PASS | `max-state-keys: 2` with 3 held → `PRV-8021`; running query untouched | `AdvSecurityTest#qe154_…` |
| QE-155 | PASS (NOTE) | `FAIL` overflow, 200 ms consumer: 200 pushes in 35 ms, subscription closed after 1 row delivered | `#qe155_…` |
| QE-156 | NOT RUN | rebalance is an HTTP/CLI admin action | — |
| QE-157 | PASS | `PRV-2050` | `#qe139_…` |
| QE-158 | PASS | `PRV-2020` | same |
| QE-159 | **FAIL** | accepted; `advance` returns after ~10 s; next push `PRV-8103` after 20 s; the plain query never sees it; 0 windows published; heap 8.3 GB (one day) / 1.3 GB (one hour) | `#qe159_observed` → FINEHOP-1 |
| QE-160 | PASS | size 0 → `PRV-2020 window size must be positive`; negative slide likewise | `#qe139_…` |
| QE-161 | PASS (NOTE) | a year of 1 ms windows → `PRV-3022 one watermark advance would fire 31536000001 windows`, query FAILED; one second → answered | `#qe159_161_…` |
| QE-167 | **FAIL** | with `cell-bytes: 65536` configured: 400 chars RUNNING, 600 chars `FAILED PRV-8004 … cell size of 512` | `#qe167_observed` → CELLBYTES-1 |
