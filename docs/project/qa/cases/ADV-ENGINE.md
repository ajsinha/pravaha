# ADV-ENGINE — adversarial engine, data-correctness and security-semantics cases (2.0.0)

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../../LICENSE`](../../../../LICENSE).

IDs `QE-001`–`QE-161`, and `QE-162`–`QE-168` added during execution (last section). Written before execution (an exploratory probe pass, recorded in the log as
such, preceded the final form of the X and W areas). Execution record:
[`../logs/ADV-ENGINE.md`](../logs/ADV-ENGINE.md).

**Target:** `develop` at `e3ad67dc` (v2.0.0 + the 2.0.1-SNAPSHOT bump), JDK 25.

**Scope.** The engine, the answers it maintains, and its security semantics. The external surfaces
(pgwire, HTTP, CLI, SDKs, console, Docker, connectors, upgrade) belong to the companion external QA
round; a case here touches one only where the security property under test lives behind it.

**Stance.** Adversarial: each case is written to break a promise, and names the promise. A case
passes when the engine does what its documentation says, even where the tester would have designed
it otherwise — those are recorded as NOTE. A FAIL needs a reproduction.

## Harnesses

| Name | What | Where |
|---|---|---|
| **E** (embedded) | `PravahaEngine` (`pravaha-embedded`), permissive caller, synchronous `push`/`retract`, explicit `advanceEventTime`; interpreted path unless a case installs the generator (`FilterProjectStageGenerator.install()`) | `pravaha-it/.../qa/adversarial/` |
| **E-durable** | E with `pravaha.registry.journal`, `pravaha.checkpoint.directory` (200 ms interval) and `pravaha.dlq.directory` under a temp dir | `AdvSupport.durable` |
| **E-child** | E in a child JVM fed by a followed `filesystem` source with `op.column`, killed with SIGKILL | `AdvCrashChildMain` |
| **N** (node) | `PravahaNode` with Flight on an ephemeral port, token authentication, `pravaha.catalog.enabled` with `authority: catalog`, four principals in two tenants: `ops` (acme, admin), `ana` (acme, analyst, claim `region=EU`), `bob` (acme, analyst, claim `region=US`), `eve` (globex, analyst) | `AdvSecurityTest` |
| **O** (oracle) | Straightforward Java written for this round: 3-valued predicate evaluation, BigInteger/BigDecimal arithmetic, per-(row, window) lateness, a keyed view as "last row to gain weight" (`KeyRows`' documented rule), group aggregates recomputed from the surviving multiset | in each test class |

Streams used by area X (`s`): `id INT64, i INT32?, a INT64?, b INT64?, sm INT16?, d FLOAT64?,
t STRING?, amt DECIMAL(18,4)?, ts TIMESTAMP` (event time). Every projection is keyed by `id`.

Documents cited: **CQ** = `docs/guides/CONTINUOUS_QUERIES.md`, **SEC** = `docs/operations/SECURITY.md`,
**LIM** = `docs/guides/LIMITS.md`, ADRs by number.

---

## X — Expressions and types (E)

| ID | Intent | Inputs / steps | Expected (promise) |
|---|---|---|---|
| QE-001 | INT × INT past the 32-bit range | `SELECT id, i * 2 AS x` with `i = 2,000,000,000` | No wrapped value is published: the row is refused (lane/DLQ with a code) or the result is widened. CQ §11 "A row whose answer has more integer digits than its type holds goes to the dead-letter queue, as a 64-bit overflow does"; SQL overflow is an exception |
| QE-002 | INT + INT overflow | `i + i`, `i * i` with `i = 2e9` | as QE-001 |
| QE-003 | Unary minus at INT MIN | `-i`, `i = Integer.MIN_VALUE` | as QE-001 (2147483648 does not fit INT) |
| QE-004 | ABS at INT MIN | `ABS(i)`, `i = Integer.MIN_VALUE` | Refused like `ABS(Long.MIN_VALUE)` (Q-12, `Expression` "an absolute value that is negative ... is the wrong one") |
| QE-005 | SMALLINT arithmetic | `sm + sm`, `sm * sm`, `sm = 30000` | as QE-001 |
| QE-006 | TINYINT arithmetic | `ty + ty`, `ty = 100` | as QE-001 |
| QE-007 | Narrowing cast BIGINT→INT | `CAST(a AS INT)`, `a = Long.MIN/MAX` | Refused or DLQ; never `0`/`-1`. CQ §11 `CAST` row: a narrowing cast "is refused PRV-2021, because rows that do not fit have no answer" (stated for DECIMAL; the same reasoning) |
| QE-008 | Narrowing cast INT→SMALLINT | `CAST(i AS SMALLINT)`, `i = 2e9` | as QE-007 |
| QE-009 | DOUBLE→BIGINT of NaN/±Inf/1e300 | `CAST(d AS BIGINT)` | No silent `0` / `Long.MAX_VALUE`; CQ §13 sends people to `SUM(CAST(price AS BIGINT))` "if the rounding is acceptable" — NaN→0 is not rounding |
| QE-010 | `Long.MIN_VALUE / -1` | `a / b` | Overflow refused (the other 64-bit operators use `*Exact`) |
| QE-011 | BIGINT subtraction at MIN | `a - 1`, `a = Long.MIN_VALUE` | Refused with a code, never wrapped |
| QE-012 | Division by zero with a DLQ | filesystem source (E-durable, `pravaha.dlq.directory`), `a / b` with one `b = 0` line among good lines | The bad record goes to the dead-letter queue and the query keeps running (CQ §11, and the `PRV-3010` message: "the record is routed to the DLQ") |
| QE-013 | Filter and projection agree | `WHERE i * 2 < 0` beside `SELECT i * 2` | A row is kept by the filter iff its projected value satisfies it |
| QE-014 | `NOT (d > 5)` over NaN | `d = NaN` | IEEE 754 as documented (TY-3, SEC "Floating point is taken as IEEE 754"): `NaN > 5` is FALSE, so `NOT` is TRUE and the row is kept |
| QE-015 | `NOT (d < 5)` over NaN | as QE-014 | as QE-014 |
| QE-016 | `d <> d` | NaN, ±0.0 | Only NaN kept (TY-3) |
| QE-017 | `d = 0` | `-0.0`, `0.0` | both kept (TY-3) |
| QE-018 | INT column against an out-of-range literal | `i < 3000000000`, `i > -3000000000` | every non-null row kept (no literal truncation) |
| QE-019 | `IN` / `NOT IN` with NULL | `a NOT IN (7, NULL)`, `a IN (7, NULL)` | empty; the `7` row |
| QE-020 | Reversed `BETWEEN`, `NOT BETWEEN` with NULLs | `a BETWEEN 7 AND -7`, `NOT (a BETWEEN -7 AND 7)` | empty; out-of-range non-null rows only |
| QE-021 | `LIKE` with regex metacharacters | `t LIKE 'a.%'` over `a.b`, `abc` | only `a.b` |
| QE-022 | `LIKE '_'` matches one code point | emoji (surrogate pair), `ß` | both match |
| QE-023 | `SPLIT_INDEX` with regex metacharacter delimiters | `'.'`, `'|'` | literal delimiters (CQ §11: "split on the whole delimiter") |
| QE-024 | `SUBSTRING` edges | `FROM 0 FOR 2`, `FROM -1`, `FOR -1` | SQL positions; a negative length is an SQL error |
| QE-025 | `UPPER`/`LOWER` root locale | `ß` | `SS`; no machine dependence |
| QE-026 | `TRIM` | leading spaces, trailing tab | spaces only (documented) |
| QE-027 | `REGEXP_EXTRACT` group that took no part | `(a)(x)?`, group 2 | null (documented) |
| QE-028 | `REGEXP_EXTRACT` that backtracks catastrophically | moved to R as QE-149 | — |
| QE-029 | `||` with NULL | `t || 'x'`, `t = NULL` | null (documented) |
| QE-030 | `CASE` without `ELSE` | `CASE WHEN i > 0 THEN 1 END` | null where no branch |
| QE-031 | DECIMAL product past 38 digits | `amt * amt * amt` on `DECIMAL(18,4)` | refused at registration `PRV-2021` (scale cannot be kept) — CQ §11 |
| QE-032 | DECIMAL literal with more scale than the column | `amt > 99999999999999.99985` | compared exactly (CQ §12) |
| QE-033 | `CAST(BIGINT AS DECIMAL(19,0))` at MIN/MAX | | exact |
| QE-034 | BIGINT × 1.5 at MIN/MAX | | exact `DECIMAL(21,1)` |
| QE-035 | DOUBLE ÷ 0 | `d / 0` | ±Infinity / NaN, IEEE (documented) |
| QE-036 | Integer `AVG` of negatives | windowed `AVG(v)` over −7, 0, MAX, MAX, MIN, MIN | −1 (SQL integer average truncates toward zero; 128-bit netted sum −9) |
| QE-037 | Generated vs interpreted comparisons | QE-038's predicates restricted to the generator's shapes, generator installed | identical answers to the interpreted path and to O |

## P — Differential predicates and expressions (E + O)

| ID | Intent | Input space / iterations | Expected |
|---|---|---|---|
| QE-038 | Random predicates under three-valued logic | Columns `n INT64?`, `m INT32?`, `d FLOAT64?` (NaN, ±0.0, ±Inf, MIN/MAX included), `t STRING?` (empty, unicode), `f BOOLEAN?`. Predicates: `AND`/`OR`/`NOT` to depth 4 over comparisons with literals and columns, `IS [NOT] NULL`, `IS [NOT] TRUE/FALSE`, `IN`, `BETWEEN`, `LIKE`. 400 predicates × 120 rows, seed 20261001 | Each registered filter's view = O's rows where the predicate is TRUE (SEC/CQ §12 "Three-valued logic is honoured throughout"; TY-3 IEEE) |
| QE-039 | QE-038 with the code generator installed | same, generator-eligible shapes | same as O and as the interpreted path |
| QE-040 | Random BIGINT expressions | `+ - * / %`, unary minus, `ABS`, `CASE` over values in ±2^40 plus edge rows, 300 expressions × 60 rows | value = BigInteger O; any row O says overflows is not published with a wrapped value |

## W — Aggregation, windows, late data (E + O)

| ID | Intent | Inputs / steps | Expected |
|---|---|---|---|
| QE-041 | Random tumbling windows vs O | 10 s tumble, `GROUP BY window, k` with `SUM`, `COUNT(*)`, `COUNT(v)`, `AVG`, `COUNT(DISTINCT v)`; batches of out-of-order inserts and retractions of earlier inserts, explicit watermark advances, `allowedLateness` 0 / 10 s; compare after every advance. 25 seeds × 300 rows | view = O, where O accepts a (row, window) pair iff `window_end + lateness > watermark` at arrival and retractions follow the same rule (CQ §5, §6, `WindowedAggregate`) |
| QE-042 | QE-041 with HOP 10 s / 30 s | 15 seeds × 200 rows | as QE-041 |
| QE-043 | Windowed `MIN`/`MAX`, inserts only, vs O | 15 seeds × 200 rows | = O |
| QE-044 | Windowed `MIN` given a retraction | push, retract one row | Either correct or refused at registration; CQ §13 lists `MIN`/`MAX` as supported in windows |
| QE-045 | Windowed `GROUP BY` a DOUBLE: −0.0 and 0.0 | one window, d = −0.0, 0.0 | one group of 2 (they compare equal, TY-3) — or at minimum the counts sum to the rows |
| QE-046 | Windowed `GROUP BY` a DOUBLE: two NaN payloads | d = NaN (0x7ff8…0), NaN (0x7ff8…1) | the two rows are counted; counts sum to the rows pushed |
| QE-047 | Windowed `COUNT(DISTINCT d)` | ±0.0, NaN payloads, 1.0 | consistent with QE-045/046 and with the view read |
| QE-048 | View read `GROUP BY d` | same values | NaN one group; ±0.0 one group |
| QE-049 | View read `COUNT(DISTINCT d)` | same values | consistent with QE-048 |
| QE-050 | Windowed `SUM` overflow | MAX then +100 | `PRV-3025`, query stops; never wrapped (CQ §13) |
| QE-051 | 128-bit netting inside one batch | +MAX, +MAX, MIN, MIN, −7 in one push | answered −9 (TRANSOVF-1) |
| QE-052 | Overflow across batches | MAX; +1 in the next push | refused at the second batch (documented: only a batch that *ends* outside is refused) |
| QE-053 | Lateness boundary, zero lateness | watermark = window end, then a row at end − 1 s | dropped as late |
| QE-054 | Correction within lateness | fire window, late row within 10 s | answer corrected; a subscriber sees −1 and +1 in one commit (CQ §6, VIEW-1) |
| QE-055 | Beyond lateness | row older than end + lateness | never applied; late count increments |
| QE-056 | A retraction arriving after its window is final | retract a row whose window passed lateness | ignored, answer unchanged (same rule as inserts) |
| QE-057 | Top-N vs O | `ROW_NUMBER() OVER (PARTITION BY k ORDER BY v DESC) <= 3`, random inserts and retractions of held rows, 20 seeds × 150 ops | = O, ties numbered by remaining columns (CQ §15) |
| QE-058 | Top-N retraction of a row never inserted | | `PRV-3024` |
| QE-059 | Interval join vs O | `a JOIN b ON a.k = b.k AND a.t BETWEEN b.t - 5 s AND b.t`, inserts and retractions, 15 seeds × 120 rows | = O nested loop |
| QE-060 | Self join | stream joined to itself on `k` with a time bound | = O |
| QE-061 | View read `AVG`/`SUM` overflowing | MAX, 1, −1 | `PRV-3025` on the read (documented) |
| QE-062 | Global `COUNT(*)` netted to zero | insert 3, retract 3 | answer 0 (or no row) — not negative, not stale |
| QE-063 | Extreme event times | timestamps at 1677-09-21 and 2262-04-11 (±2^63 ns) | window assignment does not overflow into a wrong window |
| QE-064 | NULL event time in a windowed stream | row with `ts = NULL` | refused with a code or routed late; never placed in window 0 silently |
| QE-065 | HOP whose size is not a multiple of the slide | size 25 s, slide 10 s | each row in ⌈25/10⌉ or ⌊25/10⌋ windows exactly as O computes |

## Q — Queries on queries, alerts (E, N)

| ID | Intent | Inputs / steps | Expected |
|---|---|---|---|
| QE-066 | Random chain vs O | `up` (`WHERE v > 0`, keyed by id) → `mid` (`GROUP BY g`: SUM, COUNT, AVG) → `top` (`WHERE s > 50`); random proper updates, blind upserts, retractions; settle and compare after every step. 10 seeds × 150 ops | each view = O (CQ §3.1: the downstream sees the upstream's answer as a reader sees it) |
| QE-067 | Chain across a restart | E-durable, stop and start | views equal before and after |
| QE-068 | `PravahaEngine.register(...)` over a view | `register("down", "SELECT ... FROM up ...")` | as `CREATE CONTINUOUS QUERY` (CQ §3: "wherever SQL arrives ... the embedded engine") |
| QE-069 | Chain deeper than eight | 9 levels | `PRV-8027` |
| QE-070 | Cycle through `CREATE OR REPLACE` | | `PRV-8025` |
| QE-071 | `DROP` an upstream | | `PRV-8024` naming the dependant |
| QE-072 | `MIN` over a view | | `PRV-2075` |
| QE-073 | `RETAIN FOR` over a view | | `PRV-8026` |
| QE-074 | Upstream fails | upstream `SUM` overflows | downstream stays `RUNNING` at its frontier; feed stops with `PRV-8004` (CQ §3.1 table) |
| QE-075 | Pause and resume in a chain | pause downstream, 100 changes, resume | caught up, = O |

## D — Durability (E-durable, E-child)

| ID | Intent | Inputs / steps | Expected |
|---|---|---|---|
| QE-076 | Windowed state across a clean restart | E-durable, open windows, stop after a checkpoint, start, advance | same answer as without the restart |
| QE-077 | SIGKILL mid-stream, exactly once | E-child, followed CSV with inserts and `op=D` deletes appended in 20 chunks; SIGKILL at 5 random moments (between checkpoints, right after one); restart, drain | final view = O over the whole file: no lost or doubled effect (CQ §4 "A restart replays from the last checkpoint"; ADR-008) |
| QE-078 | Kill right after a checkpoint vs right before | variants of QE-077 | as QE-077 |
| QE-079 | Newest checkpoint truncated | truncate the newest file before restart | previous checkpoint used; answer = O |
| QE-080 | Newest checkpoint bit-flipped (body) | flip a byte in the middle | refused or skipped; never a silently different answer |
| QE-081 | Journal's last record half-written | truncate mid-record | earlier registrations recovered (state085, end to end) |
| QE-082 | Journal garbage mid-file | | start refused naming the record |
| QE-083 | Two engines on one data directory | second engine while the first runs | refused `PRV-4003` |
| QE-084 | Checkpoint directory unwritable | chmod 0500 after start | failures counted and reported (`checkpointFailures`, `lastCheckpointFailure`), query keeps answering |
| QE-085 | Journal unwritable | chmod after start, register | registration refused, not acknowledged (state073) |
| QE-086 | Disk full | journal on a full filesystem (`/dev/full`-style) | registration refused with a code |
| QE-087 | Checkpoint directory is a file | | start refused with a code |
| QE-088 | Restart with a changed stream schema | add a column, restart | refused or recovered correctly, named |
| QE-089 | Dropped query stays dropped | drop, restart | not resurrected |
| QE-090 | Restart with a replacement in flight | `CREATE OR REPLACE` with backfill, kill before cutover | the running version keeps answering; replacement refused or resumed |
| QE-091 | Shared computation, one name dropped | two names one fingerprint; drop one; restart | survivor keeps state |
| QE-092 | Restart with lanes shared | `pravaha.lane.multiplex.enabled=true`, 10 queries | every answer equal after restart |
| QE-093 | Restart with spill on | spill enabled, windowed state | equal after restart |
| QE-094 | Corrupt dead-letter file at restart | garbage appended | start not prevented; DLQ readable or refused with code |
| QE-095 | Only checkpoint corrupt, keep = 1 | | refused or full replay; never silently empty state presented as current |

## S — Security semantics (N unless stated)

| ID | Intent | Inputs / steps | Expected |
|---|---|---|---|
| QE-096 | Masked column compared in `WHERE` | `ana`: `WHERE card = '4111-1111'` | `PRV-7006` (SEC, ADR-059 §4) |
| QE-097 | Masked column inside a function in `WHERE` | `WHERE SUBSTRING(card FROM 1 FOR 1) = '4'`, `WHERE card LIKE '4%'`, `WHERE UPPER(card) = ...` | `PRV-7006` |
| QE-098 | Masked column in a projected `CASE` | `SELECT CASE WHEN card LIKE '4111%' THEN 1 ELSE 0 END` | evaluates the masked value: no row reveals the raw prefix |
| QE-099 | Masked column grouped / counted distinct | `GROUP BY card`, `COUNT(DISTINCT card)` | `PRV-7006` |
| QE-100 | Masked column as a ranking key | `ROW_NUMBER() OVER (ORDER BY card)` | `PRV-7006` |
| QE-101 | Masked numeric column through arithmetic errors | mask `amount` to 0; `SELECT 1 / (amount - 20)` | no error that depends on the raw value |
| QE-102 | Masked key column, point read | mask on `id`; `WHERE id = 'p1'` | `PRV-7006` (a view's key column) |
| QE-103 | Mask on a subscription | snapshot + commits | masked |
| QE-104 | Query over a masked view by a masked reader | `ana` registers `SELECT id, card FROM payments` | downstream holds masked values |
| QE-105 | Alert `WHERE` on a masked column | `ana`: `CREATE ALERT ... WHERE card = 'x'` | `PRV-7006` |
| QE-106 | Dead letters of a narrowed reader's query | `ana` (masked, filtered) owns a query over a stream whose source dead-letters a line containing a card | the dead letter does not hand `ana` the raw card or a filtered row |
| QE-107 | Row filter, point read of a hidden key | `ana`: `WHERE id = 'p2'` (US) | empty |
| QE-108 | Row filter through an equality index | view `INDEX (region)`; `ana`: `WHERE region = 'US'` | empty |
| QE-109 | Row filter through the ordered index | view keyed `(region, n)`, `RANGE (n)`; `ana` range over US | empty |
| QE-110 | Row filter under aggregates on read | `SELECT COUNT(*), SUM(amount)` | visible rows only |
| QE-111 | Row filter and `LIST` cardinality | Flight `pravaha.list` as `ana` | rows-in `-1` (SX-18) |
| QE-112 | Query over a view by a filtered registrant | `ana` registers over `payments`; `bob` granted `SELECT` on it | the downstream holds EU rows only, whoever reads it |
| QE-113 | Tautologies past the vacuity analysis | `ALTER VIEW ... SET POLICY` with: `region LIKE '%'`, `amount * 0 = 0`, `ABS(amount) >= 0`, `CASE WHEN region = 'EU' THEN TRUE ELSE TRUE END`, `region IN ('EU') OR region NOT IN ('EU')`, `amount BETWEEN MIN AND MAX`, `NOT (region <> 'EU' AND region = 'EU')`, `UPPER(region) = UPPER(region)`, `region || '' = region` | refused `PRV-7038`/`PRV-7003`, or accepted where SEC says the analysis is incomplete (NOTE) |
| QE-114 | Claim value carrying SQL | `region` claim `EU' OR '1'='1` | bound as a literal: no rows (SEC "quotes doubled") |
| QE-115 | Same name in two tenants | `eve` registers `payments` | succeeds; each reads its own |
| QE-116 | Alert name colliding with another tenant's object | `eve`: `CREATE ALERT payments ...` / name of acme's alert | as for a name nobody holds (ADR-060 §1) |
| QE-117 | Policy name colliding across tenants | `eve`: `CREATE ROW FILTER region_scope ...` | as for a name nobody holds |
| QE-118 | Qualified name of another tenant | `eve`: `SELECT * FROM "acme.default.payments"` and of a name acme does not hold | `PRV-7002` in the same words |
| QE-119 | `SHOW CONTINUOUS QUERIES` across tenants | `eve` | own tenant only |
| QE-120 | Administer another tenant's view by local name | `eve`: `DROP/PAUSE CONTINUOUS QUERY payments` when eve holds none | as unknown (`PRV-8002`), identical to a name nobody holds |
| QE-121 | Build on another tenant's view | `eve`: `CREATE CONTINUOUS QUERY x ... FROM payments` | unknown name |
| QE-122 | Flight `GetTables` across tenants | `eve` | own tenant's views only |
| QE-123 | Registration errors that enumerate | `eve`: `FROM paymnts` (typo) | no other tenant's names in the message |
| QE-124 | Identical SQL, two tenants | `ops` and `eve` register the same SQL | two computations (fingerprints differ) |
| QE-125 | Non-owner, same tenant, no grant | `ana`: `DROP CONTINUOUS QUERY payments` | `PRV-7002` |
| QE-126 | Drop and re-create a governed name | `ops` binds `region_scope` to `payments`, grants `SELECT` to analysts, drops, re-registers `payments` | analysts never read the new view unfiltered: either the grant went with the old object or the policy came back with the name |
| QE-127 | Grant without `MANAGE` | `ana` (SELECT only): `GRANT SELECT ON VIEW payments TO USER bob` | refused |
| QE-128 | Revocation ends a subscription | `REVOKE SUBSCRIBE` while `ana` subscribes | stream ends with `PRV-7002` within a few seconds (SEC) |
| QE-129 | `MODIFY` cannot change policy | `bob` granted `MODIFY`: `ALTER VIEW payments UNSET POLICY region_scope` | refused (needs `MANAGE`) |
| QE-130 | Exempt registrant launders a masked column | `ops` (admin) registers `copy` over `payments`, grants `SELECT` on `copy` to analysts | per SEC this is the owner's decision (NOTE: the raw value reaches analysts) |
| QE-131 | Assistant registers without confirmation | | not reachable from the engine (Python/console surface) — NOT RUN here |
| QE-132 | Denied read is audited | `eve` reads `acme.default.payments` | audit `DENY` |
| QE-133 | Policy change during a subscription | as CatalogPoliciesEndToEndTest | `PRV-7007` |
| QE-134 | Prepared statement handle reused by another principal | `ana` prepares, `eve` binds and fetches | refused (SX-10) |
| QE-135 | Debug fork by a non-owner | `ana` forks `payments` | `PRV-7002` |
| QE-136 | Quoted identifiers smuggling a tenant | `eve` registers `"acme.default.x"` | refused (name grammar) |
| QE-137 | Count side channel through a row filter | `ana`: `SELECT COUNT(*) FROM payments WHERE amount > 15` | counts visible rows only |
| QE-138 | Metrics labelled with other tenants' names | | HTTP surface — NOT RUN here |

## R — Resource abuse (E)

| ID | Intent | Inputs | Expected |
|---|---|---|---|
| QE-139 | Deep nesting | 3,000 nested parentheses; 300 nested `CASE` | coded refusal; no `StackOverflowError`, no hang |
| QE-140 | Huge `IN` list | 20,000 literals | `PRV-2011` or runs (CQ §12) |
| QE-141 | Deeply nested derived tables | 200 levels | coded refusal or runs |
| QE-142 | Cartesian join | `FROM a, b` without condition | `PRV-2020` |
| QE-143 | Many registrations | 1,000 distinct filters, then pushes | all `RUNNING`, lanes shared, answers right |
| QE-144 | A million groups in one window | 1,000,000 distinct keys | answered or refused with a code (`PRV-4001`); no OOM in a capped heap |
| QE-145 | High-cardinality `COUNT(DISTINCT)` | 1,000,000 distinct values | as QE-144 |
| QE-146 | View key ceiling | push past the view's key ceiling | refused naming the count (`ServedView`) |
| QE-147 | Top-N held rows past 1,000,000 | | `PRV-4001` (CQ §15) |
| QE-148 | 65 output columns | `SELECT` of 65 columns | `PRV-3030` (CQ §11) — at registration or first row as documented |
| QE-149 | Catastrophic regex | `REGEXP_EXTRACT(t, '(a+)+$')` over 40 × `a` + `b` | bounded time or refused; one query must not wedge the engine |
| QE-150 | 10 MB string value | push | stored or refused with a code |
| QE-151 | 10,000-character view name | | `NAME_UNUSABLE` |
| QE-152 | 1 MB SQL text | | refused with a code or planned; no hang |
| QE-153 | Tenant `max-queries` | N + 1 registrations | `PRV-8020` (SEC) — node config |
| QE-154 | Tenant `max-state-keys` | | `PRV-8021` |
| QE-155 | Slow subscriber with `FAIL` overflow | subscriber that never drains | ends with a code; no unbounded buffer |
| QE-156 | Lane rebalance under load | `pravaha.lane` admin rebalance | NOT RUN unless reachable from the engine API |
| QE-157 | Unwindowed `GROUP BY` over a stream | | `PRV-2050` |
| QE-158 | `LEFT JOIN` without a time bound | | `PRV-2020` |
| QE-159 | HOP with millions of windows per row | `HOP(..., INTERVAL '0.001' SECOND, INTERVAL '1' DAY)` | refused at registration, or bounded; one row must not allocate 86 M windows |
| QE-160 | Zero / negative window size | `INTERVAL '0' SECOND` | refused with a code |
| QE-161 | `TUMBLE` 1 ms over a year of event time | rows a year apart, watermark jump | bounded firing work (no 31 G empty windows) |

## Added during execution

Cases that execution of the above suggested, written down before they were run as cases of their own.

| ID | Area | Intent | Inputs / steps | Expected (promise) |
|---|---|---|---|---|
| QE-162 | Q / embedded | A push one query cannot apply | three queries on a stream; the first registered fails on the row (`x - 1`, `x = Long.MIN_VALUE`); the caller retries the push it was told failed | all or nothing for the healthy queries: either the row is visible to them after the failure (and a retry is wrong), or it reached none (and a retry is right). `PravahaEngine.push` javadoc: "returns once every running query reading the stream has applied and committed them" |
| QE-163 | W | `SUM`/`AVG`/`MIN`/`MAX` of a group whose values are all NULL | windowed, on a view read, and in a query over a view | NULL (SQL), never 0 — the engine's own `Expression.Arithmetic` comment: "zeroes where nulls belong … a SUM over those zeroes is a number that looks entirely reasonable" |
| QE-164 | D / ingest | A CSV `TIMESTAMP` after 2262-04-11 | `3000-01-01T00:00:00Z` through the `filesystem` source | refused (dead letter / decode error with a code), never wrapped into 1677 |
| QE-165 | R | One window with more groups than its emission buffer holds | 1,000,050 distinct keys in one tumbling window | answered up to the documented 1,000,000-key view ceiling, refused past it by name |
| QE-166 | X | A runtime arithmetic failure keeps its reason | the same overflow row after JIT warm-up | the query's failure names the overflow |
| QE-168 | S | Flight SQL `GetTables` under the catalogue | `ana` (granted `SELECT` on `payments`), `eve` (owns her `payments`), the admins | each is listed the views they may read and no other tenant's (SEC "Metadata is data"; ADR-060 names `GetTables` among the lookups resolved in the caller's tenant) |
| QE-167 | R / embedded | A row wider than the default inbox cell | `pravaha.lane.inbox.cell-bytes: 65536` on an embedded engine; one 600- and one 4,000-character string | accepted once the cell is raised, as the refusal (`raise pravaha.lane.inbox.cell-bytes`) and TROUBLESHOOTING (`PRV-3001`) say |
