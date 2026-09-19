# Troubleshooting

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Every failure in Pravaha carries a `PRV-nnnn` code. The code is the stable part — the message may
improve, the code does not change — so it is what belongs in a runbook, a log filter or a support
ticket.

**Read the message first.** Pravaha's errors are written to say what to do, not just what happened.
This page is for when the message was not enough, or when you are searching for a code you found in
a log.

> **The URL in the message does not resolve.** Every refusal ends with
> `https://docs.pravaha.io/errors/PRV-nnnn`, and `docs.pravaha.io` is not registered — the host does
> not exist, so the link fails to connect rather than 404ing, which reads like a network problem at
> exactly the wrong moment. **This file is the reference those links were meant to reach.** Recorded
> as DOCX-21; whether to register the domain or drop the line from the message is the owner's call,
> not a documentation edit.

## The ranges

| | |
|---|---|
| `PRV-1xxx` | Configuration |
| `PRV-2xxx` | SQL — parsing, planning, what the engine will and will not run |
| `PRV-3xxx` | Runtime and code generation |
| `PRV-4xxx` | State, backfill and serving |
| `PRV-5xxx` | Plugins |
| `PRV-6xxx` | The Flight gateway |
| `PRV-7xxx` | Security |
| `PRV-8xxx` | The query registry |
| `PRV-9xxx` | Clustering and partition ownership |

Codes are unique across the whole system and enforced by a test — `ErrorCodeUniquenessTest` fails the
build if two failures ever answer to one number, which happened once and is how this section exists.

---

## The five you will actually meet

### `PRV-2050` — "this GROUP BY has no bound on its key space"

**The most common refusal, and it is the engine working.** `GROUP BY user_id` with no window keeps
one accumulator per user *forever*. It does not fail on the day you deploy it; it fails months later,
and the query looked innocent in the review that approved it.

Add a window:

```sql
SELECT window_start, window_end, user_id, COUNT(*)
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end, user_id
```

**Over a view it is allowed**, because a read of a view scans a finite set of rows and stops. The
same SQL is refused against a stream and answered against a view; the difference is the input, not
the query.

### `PRV-2020` / `PRV-2021` — "not supported yet"

A relational operator (`2020`) or an expression (`2021`) the engine will not run: `ORDER BY`, `LIMIT`,
`UNION`, an outer join between streams, `DECIMAL` arithmetic, a function the engine does not have.

The name in a `2021` message is the function the *planner* saw, which is not always the one you
typed — Calcite rewrites `SQRT(x)` to `POWER(x, 0.5)` before the engine reads the query, so a
refusal can name a function your SQL does not contain.

The complete list, with what to do instead, is [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) — and it is checked
by a test, so it is true rather than aspirational.

### `PRV-4023` — "is not a registered view"

The message names the views the server does serve. Usually one of: the registration was dropped, the
name is misspelled, or you are pointed at a different server than you think.

```bash
pravaha queries --url grpc://localhost:9090
```

### `PRV-7001` vs `PRV-7002` — told apart deliberately

| | | Your move |
|---|---|---|
| `PRV-7001` | Not authenticated | Present a credential, or a fresh one |
| `PRV-7002` | Authenticated, not authorized | Ask for access — a new credential will not help |
| `PRV-7004` security misconfigured | A **security setting** this node refuses to start with — not a caller being denied. The message names the key and the value. Split from `PRV-7002` by E-3, which had accumulated four unrelated meanings across twenty sites: an authorization denial, the open-server refusal, the policy/authentication contradiction, and a bad configuration value. The advice for `7002` ("ask for access; a new credential will not help") is right for a denial and useless for a typo — an operator who wrote `policy: permisive` was being told to go and ask somebody for permission. One is about a caller and is answered by a grant; this one is about a file and is answered by an edit Also raised when `pravaha.security.audit: file` names a path this node cannot write (`pravaha.security.audit-file`) — refused at startup rather than at the first decision nobody sees, because a node that starts believing it is auditing and writes nowhere has no record at all (CFG-23) |
| `PRV-1050` missing field | A JSON request body left out a field the endpoint requires — today a null `sql` on `/validate` or `/explain`. Returned as **400**, not as a 200 with `valid:false`: a malformed request is not a query that failed to validate, and the distinction matters to anything reading the response programmatically. It used to surface as a raw `NullPointerException` message dressed in a `PRV-` code (API-F9). Deliberately 1xxx rather than 2xxx — a `PRV-2xxx` would send the reader to the SQL documentation for a request that carried no SQL. An *empty* `sql` is not this: the console sends one between keystrokes and the lexer already refuses it precisely |

`7001`'s message says only that the credential was not accepted, never *why*: "expired" versus
"unknown" versus "wrong signature" is three bits of an oracle for whoever is working through guesses.

### `PRV-4026` / `PRV-4027` / `PRV-4028` — the node is busy

Admission control, and the three are separate because the fix differs:

| | | |
|---|---|---|
| `4026` | Full now, queue full, never waited | Retry with backoff |
| `4027` | Waited its turn and gave up | The node is saturated for longer than your patience |
| `4028` | Your tenant is over its share | Capacity may still be free — this protects the other tenants |

All three arrive at a client as `RESOURCE_EXHAUSTED`, so a driver retries them and does not treat
them as a malformed query.

---

## "Nothing is happening"

By far the most common report, and usually not a fault.

**Is it waiting on a watermark?** A window closes when data says the window is over, not when the
clock does. Ten rows in, nothing out, is correct if nothing has yet told the engine that the window
is complete. Send an event past the window's end.

**Is the subscription attached?** A subscription starts from *now*, not from the beginning of time. A
change committed before the subscriber attached was published to nobody. The console shows subscriber
counts; `RegisteredQuery.subscriberCount()` is the same number in code.

**Is the query paused?** A paused query keeps answering at the frontier it reached and stops
advancing. Rows arriving while paused are dropped rather than buffered — a pause is meant to stop it
doing work.

```bash
pravaha queries        # state column
```

**Has the sink stopped?** A query registered with `--sink` keeps its view current even after the sink
refuses a batch: the sink is detached with `PRV-8009` and an `ERROR` line names it, and nothing more
is written to it, because writing past a lost batch would leave the sink missing a change with
nothing to say so. The view still answering is not evidence the sink is receiving. Fix what the sink
refused, then drop and re-register the query; it is sent the view's contents first. A transactional
sink is detached the same way when a prepare or a commit fails.

**Is the sink transactional?** Then nothing it is written is visible until the next checkpoint is
stored — up to `pravaha.checkpoint.interval` behind the view — and without
`pravaha.checkpoint.directory` each commit is its own transaction. The registration's `INFO` line
(`query '…' writes to sink '…', exactly-once: …`) says which the node is doing.

**Did a filter silently match nothing?** `WHERE tier = ?` bound to `NULL` matches **no rows**, because
`x = NULL` is UNKNOWN under SQL's three-valued logic. `IS NULL` is what finds the empty ones.

## "The numbers are wrong"

**Is it `AVG` over an integer?** SQL's `AVG` on an integer column is integer division: a mean depth of
47.9 reads as `47`. Take `SUM` and `COUNT` and divide where you have floating point.

**Are you ignoring weights?** A subscriber maintaining its own aggregate must apply the `-1`/`+1`
weights, or it drifts from the view the first time late data corrects a window.

**Did rows age out?** A view has a retention window — a day by default. `evicted()` counts what has
been forgotten, and a window shorter than the questions being asked of it is exactly what that
counter exists to reveal.

**Are you summing across currencies?** Not an engine problem, but the one that gets shipped. The
schema is where you stop yourself.

**Did the query restart from an old checkpoint and start again from its sources?** A checkpoint
carries the view's contents, and the view snapshot format is versioned. A snapshot written before
the format carried a version (version 1) is refused with `PRV-4002 STATE_UNREADABLE`, because it
stored `INT32`, `INT16` and `INT8` values as `INT64`, `FLOAT32` as `FLOAT64` and `DECIMAL` truncated
to a whole number — restored, an `INT32`-keyed view held a second row beside every key updated after
the restart and a decimal lost its fraction (VIEW-2). The refusal is checked before any operator state
is restored, so the query resumes from its sources with empty operators, exactly as for any checkpoint
it cannot read: the answers are rebuilt, not doubled. The next checkpoint is written in the current
format.

## "It ran out of memory" / "the disk filled"

| | |
|---|---|
| `PRV-4022` view too large | Retention is applied *before* this check, so hitting it means either the view keeps everything and should not, or the window genuinely holds more rows than the ceiling. The message says which |
| `PRV-4001` state too large | An operator's state passed its ceiling, and the lane it was running on dies with it. **Do not meet this for the first time here**: `pravaha_query_state_fraction` reports how full every query is, and `_held` / `_ceiling` report the two numbers behind it. Alert at 0.9. Without a spill tier a query that hits the ceiling stops. Configure `pravaha.state.spill.*` (off by default) and join and windowed-aggregate state spills to disk instead, slower but running (ADR-037 B2), `COUNT(DISTINCT)` included since ADR-044 |
| `PRV-4005` spill quota reached | The node's spilled state, across every query, reached `pravaha.state.spill.max-bytes`, and the query whose state needed one more overflow slab stopped before writing anything (ADR-044). `pravaha_state_spill_bytes_mapped` shows the total and `pravaha_query_spill_bytes` which query holds it. Raise the quota if the disk can hold it; otherwise the query holding the most is the one to bound with a window or a tighter key range. Check `pravaha_query_spill_fragmentation` first: a query sitting far above its live state is one whose compaction threshold is set higher than its churn reaches |
| `PRV-4006` spill disk full | The spill directory's filesystem had less free space than the next overflow slab needs, so the slab was refused before it was created (ADR-044) — rather than the disk filling inside a write to a mapped file, which surfaces as a fault from whatever operator touched the page. Free space on that filesystem, move `pravaha.state.spill.directory` to a larger one, or set `pravaha.state.spill.max-bytes` below what it holds so the quota (`PRV-4005`) refuses first |
| `PRV-4003` state not ours | Another node owns this checkpoint directory or registry journal, or a second instance of this node is running. The message names the holder's node id, host, port and how long ago it was last seen. Give this node its own directory, stop the other instance, or set `pravaha.state.allow-shared=true` if sharing really is intended. A node reclaiming *its own* state after a crash does **not** hit this: an expired claim under the same node id is taken over automatically |
| `PRV-4004` ownership marker unreadable | The `.pravaha-owner` file in a state directory exists and cannot be read, written, or names no node. Refused rather than assumed free, because a truncated marker and an absent one mean different things. Delete it only if the directory is genuinely unowned |
| `PRV-4090` dead-letter queue unusable | `pravaha.dlq.directory` is set and this node cannot create or write there, so it refuses to start. Deliberately fatal: an operator who configured a dead-letter queue asked for undecodable records to be kept, and starting without one would hand them the behaviour they configured it to avoid — a single bad field ending the poll and taking the rest of the file with it (TIME-4). Fix the path and its permissions, or unset the key to go back to failing loudly on a bad record |
| `PRV-3022` window span implausible | One watermark advance would fire millions of windows. Almost always a single row carrying an event time far outside the rest of the stream — an unset field read as the epoch, a value in the wrong unit, or a parse that silently produced zero. The message names both instants and the slide; check the event-time column of the **earliest** row the query saw, not the latest. The limit is 10,000,000 windows per advance, which a legitimate catch-up stays well inside: a day of one-second windows is 86,400 and a year of hourly ones 8,760. If the span really is intended, the window is too fine for it (TIME-1) |
| `PRV-3001` arena exhausted | Off-heap arena full — usually a batch far larger than expected, or a slab sized for narrower rows than the query produces. The message names the setting to change: `pravaha.lane.arena.slab-bytes`, or `pravaha.lane.batch-size` to make each batch smaller. The rule is `batch-size × widest output row` must fit one slab. A *row* that does not fit an inbox cell is the same code from the ingest side and names `pravaha.lane.inbox.cell-bytes` instead. These are real settings as of ADR-036; until then eleven messages named `arena.slab.size` and `lane.inbox.cell.size`, neither of which existed (PF-3) |
| Too many open files | One bound source costs about one descriptor. The node logs its descriptor ceiling at startup, and a source that fails to open near that ceiling gets a sentence naming `ulimit -n` and `LimitNOFILE`. Two codes still name the wrong thing when descriptors are the real cause: `PRV-5040 FILESYSTEM_DECODE_FAILED` (a decode code for a resource exhaustion) and `PRV-5080 AEROSPIKE_CONNECT_FAILED`, whose every suggested remedy is wrong in that case — the Aerospike client's exception carries no cause, so it cannot be told apart by catching it (SRC-4) |
| Disk growing | **Not checkpoints, unless you configured it that way.** `PeriodicCheckpointer` prunes after every checkpoint, keeping the newest `pravaha.checkpoint.keep` (default 3) per query; this row used to say nothing called `prune`, and something does. Check `pravaha.checkpoint.keep`, and then the registry journal, which grows until it is compacted. See [`OPERATIONS.md`](OPERATIONS.md) |

## `PRV-5040` — reading a file, or declaring the schema for one

**`unknown type 'DECIMAL(10'`, when you wrote `DECIMAL(10,2)`.** The schema-string grammar —
`name:TYPE,name:TYPE`, used by `--schema`, `--out-schema`, `pravaha.streams.*.schema` and
`POST /api/v1/streams` alike — is split on commas before any per-column type is parsed, so a
parenthesised type is cut in half at its own comma. `DECIMAL(p,s)` is therefore **not declarable
through any surface**, despite being named in this refusal's own list of supported types. There is
no escaping or quoting that gets round it. A decimal column has to be declared programmatically
(`Types.decimal(p, s)` through the embedded API) until the parser reads a column at a time. Recorded
as TY-7; the message's claim is what to distrust here, not your spelling.

**`read failed at line 0` on a file you know is there.** If any byte in the file is not valid UTF-8,
this is the whole-file failure it produces. The delimited source reads lines as UTF-8 text before a
single column is decoded, so one invalid sequence anywhere ends the read — including one inside a
`BYTES` column, whose entire point is to carry bytes that are not text. A `BYTES` column can hold
arbitrary binary only while every *other* byte on its line is valid UTF-8; `FF FE 00 41` in the
field is enough to lose the file. Route genuinely binary payloads through a source that frames them
(or base64 them into a `STRING` column) rather than through the delimited reader. Recorded as TY-12.

## Connection problems

**The Aerospike cluster is at 200–300% CPU and nothing is changing in the set.** One continuous
query used to scan the set as fast as the cluster would answer, for ever — there was no scan
interval. There is one now: `scan.interval.ms`, one second by default, under the source's `options`.
Raise it on a shared cluster; the cost is staleness bounded by the interval. `records.per.second` is
a different knob and throttles records *within* a scan, which is why it never helped here.

**Aerospike connects and then hangs.** Run the container with `--network host`. A containerised node
reports its *bridge* address to clients, so the client connects to the seed and is then redirected
somewhere it cannot reach. The symptom is a hang rather than an error, which is why it costs people
an afternoon. Port 3000 must be free.

**`RST_STREAM ... CANCEL` from a Flight client, with nothing explaining why.** Almost always the JVM
missing `--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED`.
Arrow fails *inside the server* and cancels the stream; the client sees only the cancellation. On
Java 24+ add `--sun-misc-unsafe-memory-access=allow` — it is **not** valid on 21 and the JVM refuses
to start.

**A client closed and the server still holds a subscription.** Fixed, but if you see it: the server
learns nobody is listening from a *cancellation*, not from a dropped transport. The SDK cancels what
it opened when you close it; a hand-rolled client must do the same.

---

## Two ceilings that used to have no code of their own

Two ceilings a query can reach that are real, reproducible, and — as of X-11 — carry a `PRV-` code
and a documented shape. They are written down here because the first thing anyone does with an
unfamiliar failure is search this file for it.

**A row cannot have more than 64 columns — `PRV-3030`.** Any row — a wide projection, a wide join, a
wide aggregate — is capped at 64 fields, because `BinaryRowWriter` (in `pravaha-common`) tracks which
fields have been written in a single `long` bitmask, and a `long` has 64 bits. Past that it refuses
at construction:

```
PRV-3030  BinaryRowWriter tracks written fields in a long bitmask and so supports at most 64 fields;
<stream> has 65
```

Used to be a plain `IllegalArgumentException` with no code and no category, so the refusal could not
be classified, mapped to an HTTP or Flight status, or looked up. Note also *when* it fires:
`pravaha validate` plans a 1,000-column projection without complaint, because nothing writes a row
during validation. The ceiling is met later, when rows start moving — which is the worst time to meet
it. Split the query, or project fewer columns. See also [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md)
§11.

**A long chain of `AND` or `OR` terms fails, and used to echo the whole predicate back —
`PRV-2011`.** Past roughly a couple of thousand terms — the shape a generated query or an `IN`-list
rewrite produces — the planner's conversion step fails, and used to carry Calcite's own text
verbatim: a 3,000-term chain produced about 125 KB of stderr, the entire predicate interpolated into
the message, which for a CLI user meant the reason scrolled away and for a log meant one refusal
filled a page. The message is now a summary instead — the operator and the term count, not the text
— and the failure carries its own code rather than sharing `PRV-2010 SQL_PLANNING_FAILED`, the
planner's catch-all for everything else that can go wrong converting a query:

```
PRV-2011  the query planner failed while converting a predicate, and the predicate itself is omitted
from this message because it is a 3000-term AND chain (124807 characters) -- too large to be useful
here. This shape is usually a client library rewriting a wide IN (...) list into AND/OR, or a
generated query; write it as IN (...) instead, or split it into several queries.
```

Shorter chains are fine; the threshold depends on the shape of the terms, not just their number.
Rewrite the filter (a range comparison instead of a chain of them, or a join against a table of
values instead of a long `IN`).

This is one of three distinct things a large boolean expression can do, worth telling apart because
the same input can produce any of them depending on width and shape. A `StackOverflowError` during
planning is caught separately, before it reaches this code path, and is already refused as
`PRV-2010` with a message naming the cause (unrelated to this round's fix — see `SqlPlanner.plan`'s
own `catch (StackOverflowError e)`). A `StackOverflowError` reaching the *execution* path can still
surface as `PRV-2001` with no message at all — a separate, open defect (X-10), not addressed here.
And this — Calcite's own conversion failure, distinct from either `StackOverflowError` — is
`PRV-2011`.

## `CREATE CONTINUOUS QUERY` and the statements around it

The statements that register and manage queries in SQL
([`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §10.1) have four refusals of their own. Each names
the statement's expected shape.

| Code | What happened | What to do |
|---|---|---|
| `PRV-2070` | The text starts as one of the statements — `CREATE CONTINUOUS`, `DROP CONTINUOUS`, `SHOW CONTINUOUS`, `PAUSE`, `RESUME` — and does not have its shape: no `KEYED BY`, a clause given twice, a retention that is not a duration, words after the name. Also: parameters bound to one of these statements, which take none | The message says what was expected, what was found, and the line and column. Compare it with the shape at the end of the message |
| `PRV-2071` | `KEYED BY` names a column the `SELECT` does not produce, or names one twice | Name the column as the `SELECT` list does — by its alias where it has one (`SUM(amount) AS total` is `total`). The message does not list the columns, because it is raised before the registry has decided whether you may read what the query reads |
| `PRV-2072` | A clause from the design's grammar that is not built: `INDEXED BY ... RANGE`, `WITH (...)`, `EMIT CHANGES WITH (...)`, `CREATE OR REPLACE`, a `SERVE AS VIEW` naming another view | Say a retention with `RETAIN FOR`; drop and re-create instead of replacing; give the query the name clients read. Refused rather than ignored: an ignored `'retention' = '24h'` is a view kept for ever |
| `PRV-6211` | One of the statements was sent to the PostgreSQL gateway (SQLSTATE `25006`), which is read-only | Send it over Flight SQL: an SDK's `query()`, `pravaha query --sql`, or the console's workbench |

A reserved word as the query's name is refused by the registry's own name rule, `PRV-8008`, whichever
way it was registered.

## Every code

| Code | Name | Range |
|---|---|---|
| `PRV-1001` | CONFIG_FILE_UNREADABLE | config |
| `PRV-1050` | API_MISSING_FIELD | api |
| `PRV-1002` | CONFIG_FILE_MALFORMED | config |
| `PRV-1010` | CONFIG_UNRESOLVED_REFERENCE | config |
| `PRV-1011` | CONFIG_CIRCULAR_REFERENCE | config |
| `PRV-1020` | CONFIG_MISSING_REQUIRED | config |
| `PRV-1021` | CONFIG_NOT_A_NUMBER | config |
| `PRV-1022` | CONFIG_NOT_A_BOOLEAN | config |
| `PRV-1023` | CONFIG_NOT_A_DURATION | config |
| `PRV-1024` | CONFIG_NOT_A_DATA_SIZE | config |
| `PRV-1025` | CONFIG_NOT_AN_ENUM | config |
| `PRV-1026` | CONFIG_OUT_OF_RANGE | config |
| `PRV-1030` | CLIENT_MALFORMED_ENDPOINT | client (SDK) |
| `PRV-1031` | CLIENT_INVALID_OPTIONS | client (SDK) |
| `PRV-1032` | CLIENT_INVALID_TLS_OPTIONS | client (SDK) |
| `PRV-1040` | CLIENT_CONNECT_FAILED | client (SDK) |
| `PRV-1041` | CLIENT_QUERY_REFUSED | client (SDK) |
| `PRV-1042` | CLIENT_READ_FAILED | client (SDK) |
| `PRV-1043` | CLIENT_CLOSED | client (SDK) |
| `PRV-1044` | CLIENT_TLS_UNREADABLE | client (SDK) |
| `PRV-2001` | SQL_PARSE_FAILED | sql |
| `PRV-2002` | SQL_VALIDATION_FAILED | sql |
| `PRV-2003` | SQL_UNKNOWN_STREAM | sql |
| `PRV-2010` | SQL_PLANNING_FAILED | sql |
| `PRV-2011` | SQL_PREDICATE_TOO_LARGE | sql |
| `PRV-2020` | SQL_UNSUPPORTED_OPERATOR | sql |
| `PRV-2021` | SQL_UNSUPPORTED_EXPRESSION | sql |
| `PRV-2041` | SQL_EMIT_MODE_MISMATCH | sql |
| `PRV-2050` | SQL_UNBOUNDED_STATE | sql |
| `PRV-2060` | SQL_PARAMETER_NOT_BOUND | sql |
| `PRV-2061` | SQL_PARAMETER_ARITY | sql |
| `PRV-2062` | SQL_PARAMETER_TYPE | sql |
| `PRV-2063` | SQL_PARAMETER_NOT_A_VALUE | sql |
| `PRV-2070` | SQL_STATEMENT_MALFORMED | sql |
| `PRV-2071` | SQL_KEY_COLUMN_UNKNOWN | sql |
| `PRV-2072` | SQL_CLAUSE_NOT_BUILT | sql |
| `PRV-3001` | RUNTIME_ARENA_EXHAUSTED | runtime |
| `PRV-3002` | RUNTIME_BACKPRESSURED | runtime |
| `PRV-3010` | RUNTIME_LANE_FAILED | runtime |
| `PRV-3020` | RUNTIME_UNSUPPORTED_AGGREGATE | runtime |
| `PRV-3022` | RUNTIME_WINDOW_SPAN_IMPLAUSIBLE | runtime |
| `PRV-3021` | RUNTIME_UNSUPPORTED_JOIN | runtime |
| `PRV-3100` | CODEGEN_COMPILATION_FAILED | runtime |
| `PRV-3101` | CODEGEN_UNSUPPORTED_OPERATOR | runtime |
| `PRV-3102` | CODEGEN_STAGE_TOO_LARGE | runtime |
| `PRV-3030` | ROW_FIELD_LIMIT_EXCEEDED | runtime |
| `PRV-4001` | STATE_TOO_LARGE | state/serving |
| `PRV-4002` | STATE_UNREADABLE | state/serving |
| `PRV-4003` | STATE_NOT_OURS | state/serving |
| `PRV-4004` | STATE_OWNERSHIP_UNREADABLE | state/serving |
| `PRV-4005` | STATE_SPILL_QUOTA_REACHED | state |
| `PRV-4006` | STATE_SPILL_DISK_FULL | state |
| `PRV-4010` | BACKFILL_BUFFER_FULL | state/serving |
| `PRV-4011` | BACKFILL_MISSING_VERSION | state/serving |
| `PRV-4012` | BACKFILL_UNSUPPORTED_KEY | state/serving |
| `PRV-4014` | BACKFILL_NOT_CAUGHT_UP | state/serving |
| `PRV-4015` | BACKFILL_SEAM_WENT_BACKWARDS | state/serving |
| `PRV-4020` | SERVING_NO_HISTORY | state/serving |
| `PRV-4021` | SERVING_READ_TIMED_OUT | state/serving |
| `PRV-4022` | SERVING_VIEW_TOO_LARGE | state/serving |
| `PRV-4023` | SERVING_NO_SUCH_VIEW | state/serving |
| `PRV-4090` | STATE_DLQ_UNUSABLE | state |
| `PRV-4024` | SERVING_RESULT_TOO_LARGE | state/serving |
| `PRV-4025` | SERVING_UNSUPPORTED_QUERY | state/serving |
| `PRV-4026` | SERVING_READ_REJECTED | state/serving |
| `PRV-4027` | SERVING_READ_QUEUE_TIMED_OUT | state/serving |
| `PRV-4028` | SERVING_TENANT_QUOTA_EXCEEDED | state/serving |
| `PRV-4029` | SERVING_READ_DEADLINE_EXCEEDED | state/serving |
| `PRV-5001` | PLUGIN_MISSING_SETTING | plugins |
| `PRV-5010` | PLUGIN_NOT_FOUND | plugins |
| `PRV-5011` | PLUGIN_INCOMPATIBLE_API | plugins |
| `PRV-5012` | PLUGIN_LOAD_FAILED | plugins |
| `PRV-5013` | PLUGIN_DUPLICATE_NAME | plugins |
| `PRV-5030` | PLUGIN_CAPABILITY_MISMATCH | plugins |
| `PRV-5040` | FILESYSTEM_DECODE_FAILED | plugins |
| `PRV-5050` | DELTA_TABLE_UNREADABLE | plugins |
| `PRV-5051` | DELTA_UNSUPPORTED_TYPE | plugins |
| `PRV-5052` | DELTA_MALFORMED_OFFSET | plugins |
| `PRV-5053` | DELTA_FILE_VACUUMED | plugins |
| `PRV-5054` | DELTA_READ_FAILED | plugins |
| `PRV-5055` | DELTA_UNSUPPORTED_FEATURE | plugins |
| `PRV-5060` | FEEDFILE_DIRECTORY_UNREADABLE | plugins |
| `PRV-5061` | FEEDFILE_BAD_SCHEMA | plugins |
| `PRV-5062` | FEEDFILE_DECODE_FAILED | plugins |
| `PRV-5063` | FEEDFILE_MALFORMED_OFFSET | plugins |
| `PRV-5064` | FEEDFILE_FILE_GONE | plugins |
| `PRV-5065` | FEEDFILE_BAD_CONFIGURATION | plugins |
| `PRV-5070` | JDBC_CONNECT_FAILED | plugins |
| `PRV-5071` | JDBC_QUERY_FAILED | plugins |
| `PRV-5072` | JDBC_UNSUPPORTED_TYPE | plugins |
| `PRV-5073` | JDBC_MALFORMED_OFFSET | plugins |
| `PRV-5074` | JDBC_BAD_CONFIGURATION | plugins |
| `PRV-5075` | JDBC_SINK_TABLE_MISMATCH | plugins |
| `PRV-5076` | JDBC_WRITE_FAILED | plugins |
| `PRV-5080` | AEROSPIKE_CONNECT_FAILED | plugins |
| `PRV-5081` | AEROSPIKE_OPERATION_FAILED | plugins |
| `PRV-5082` | AEROSPIKE_UNSUPPORTED_TYPE | plugins |
| `PRV-5083` | AEROSPIKE_BAD_CONFIGURATION | plugins |
| `PRV-5084` | AEROSPIKE_MALFORMED_OFFSET | plugins |
| `PRV-5085` | CASSANDRA_CONNECT_FAILED | plugins |
| `PRV-5086` | CASSANDRA_OPERATION_FAILED | plugins |
| `PRV-5087` | CASSANDRA_UNSUPPORTED_TYPE | plugins |
| `PRV-5088` | CASSANDRA_BAD_CONFIGURATION | plugins |
| `PRV-5089` | CASSANDRA_MALFORMED_OFFSET | plugins |
| `PRV-5090` | INGEST_NO_SUCH_PLUGIN | plugins |
| `PRV-5091` | INGEST_BINDING_FAILED | plugins |
| `PRV-5092` | INGEST_FEED_FAILED | plugins |
| `PRV-5093` | EGRESS_NO_SUCH_SINK_PLUGIN | plugins |
| `PRV-5094` | EGRESS_SINK_BINDING_FAILED | plugins |
| `PRV-6100` | FLIGHT_UNSUPPORTED_TYPE | gateway |
| `PRV-6101` | FLIGHT_UNSUPPORTED_REQUEST | gateway |
| `PRV-6102` | FLIGHT_BAD_HANDLE | gateway |
| `PRV-6103` | FLIGHT_PARAMETERS_TOO_LARGE | gateway |
| `PRV-6104` | FLIGHT_TLS_UNREADABLE | gateway |
| `PRV-6200` | PGWIRE_UNSUPPORTED_TYPE | gateway |
| `PRV-6201` | PGWIRE_UNSUPPORTED_REQUEST | gateway |
| `PRV-6202` | PGWIRE_PROTOCOL_VIOLATION | gateway |
| `PRV-6203` | PGWIRE_UNSUPPORTED_PROTOCOL | gateway |
| `PRV-6204` | PGWIRE_UNSUPPORTED_SET | gateway |
| `PRV-6205` | PGWIRE_UNSUPPORTED_CATALOG_QUERY | gateway |
| `PRV-6206` | PGWIRE_TLS_UNREADABLE | gateway |
| `PRV-6207` | PGWIRE_UNKNOWN_STATEMENT | gateway |
| `PRV-6208` | PGWIRE_UNKNOWN_PORTAL | gateway |
| `PRV-6209` | PGWIRE_UNSUPPORTED_WIRE_FORMAT | gateway |
| `PRV-6210` | PGWIRE_UNSUPPORTED_PARAMETER_SYNTAX | gateway |
| `PRV-6211` | PGWIRE_READ_ONLY | gateway |
| `PRV-7001` | SECURITY_UNAUTHENTICATED | security |
| `PRV-7002` | SECURITY_FORBIDDEN | security |
| `PRV-7003` | SECURITY_FILTER_NOT_ENFORCEABLE | security |
| `PRV-7004` | SECURITY_MISCONFIGURED | security |
| `PRV-8001` | REGISTRY_NAME_IN_USE | registry |
| `PRV-8002` | REGISTRY_NO_SUCH_QUERY | registry |
| `PRV-8003` | REGISTRY_ILLEGAL_TRANSITION | registry |
| `PRV-8004` | REGISTRY_QUERY_FAILED | registry |
| `PRV-8005` | REGISTRY_JOURNAL_UNREADABLE | registry |
| `PRV-8006` | REGISTRY_JOURNAL_UNWRITABLE | registry |
| `PRV-8007` | REGISTRY_REPLAY_UNAUTHORIZED | registry |
| `PRV-8008` | REGISTRY_NAME_UNUSABLE | registry |
| `PRV-8009` | REGISTRY_SINK_WRITE_FAILED | registry |
| `PRV-8010` | REGISTRY_SINK_SHAPE_MISMATCH | registry |
| `PRV-8101` | EMBEDDED_UNKNOWN_STREAM | registry (embedded engine) |
| `PRV-8102` | EMBEDDED_ROW_REJECTED | registry (embedded engine) |
| `PRV-8103` | EMBEDDED_BACKPRESSURE | registry (embedded engine) |
| `PRV-8104` | EMBEDDED_MISCONFIGURED | registry (embedded engine) |
| `PRV-9001` | CLUSTER_UNKNOWN_MECHANISM | cluster |
| `PRV-9002` | CLUSTER_INSUFFICIENT_GUARANTEE | cluster |
| `PRV-9003` | CLUSTER_COORDINATOR_UNAVAILABLE | cluster |
| `PRV-9004` | CLUSTER_NOT_LEADER | cluster |
| `PRV-9005` | CLUSTER_BAD_MEMBERSHIP | cluster |
| `PRV-9006` | CLUSTER_HANDOFF_FAILED | cluster |
| `PRV-9007` | CLUSTER_REBALANCE_REFUSED | cluster |

Checked against the source, not written from memory: every row above is an `ErrorCode` declared in a
module's `src/main`, and `ErrcCrossCuttingTest` fails the build if this table and those
declarations ever disagree in either direction. The table is maintained by hand and enforced by
that test — it is not generated, and the sentence that said it was is what let `PRV-5090`, `5091`
and `5092` sit undocumented for a round.
