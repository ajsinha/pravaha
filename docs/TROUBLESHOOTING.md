# Troubleshooting

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Every failure in Pravaha carries a `PRV-nnnn` code. The code is the stable part — the message may
improve, the code does not change — so it is what belongs in a runbook, a log filter or a support
ticket.

**Read the message first.** Pravaha's errors are written to say what to do, not just what happened.
This page is for when the message was not enough, or when you are searching for a code you found in
a log.

> **Where the help link comes from.** A refusal used to end with
> `https://docs.pravaha.io/errors/PRV-nnnn`, and that host has never been registered: the link
> failed to connect rather than 404ing, which reads like a network problem at exactly the wrong
> moment. It is gone. The base of that link is now configuration — `pravaha.docs.base-url` on the
> server, `pravaha.docs.base-url` in an embedded engine's configuration, and the environment
> variable `PRAVAHA_DOCS_BASE_URL` for the CLI and the SDKs — and there is **no default**. A
> deployment that sets it to the console's help gets a link to a page that resolves offline; a
> deployment that sets nothing gets no URL at all, and every message that would have carried one
> says to look the code up in the console's help under Errors, or here. **This file is the code
> index.** (DOCX-21.)

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

`INSERT`, `UPDATE`, `DELETE` and `MERGE` are `2020` too: there is nothing here whose rows a statement
may edit in place. `INSERT INTO <sink> SELECT ...` in particular is refused rather than read as a
registration, because it carries neither the name the query is managed and read by — which is not
the sink's — nor the key its view needs. Say both:

```sql
CREATE CONTINUOUS QUERY user_volume KEYED BY (user_id) WRITING TO warehouse AS SELECT ...
```

or `WITH (sink = 'warehouse')`, or `pravaha register --sink warehouse`.

The name in a `2021` message is the function the *planner* saw, which is not always the one you
typed — Calcite rewrites `SQRT(x)` to `POWER(x, 0.5)` before the engine reads the query, so a
refusal can name a function your SQL does not contain.

The complete list, with what to do instead, is [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) — and it is checked
by a test, so it is true rather than aspirational.

### `PRV-4023` — "is not a registered view"

Usually one of: the registration was dropped, the name is misspelled, you are pointed at a
different server than you think, or the view is **another tenant's**. A name is resolved in your own
tenant (ADR-060), so a view registered under the same bare name by a principal of another tenant is
not one you can name -- an admin reaches it as `tenant.default.name`, quoted in a `FROM` clause
(`SELECT * FROM "acme.default.orders"`). The message does **not** list the views the server does serve —
that would hand the node's inventory to whoever mistypes a name (SX-5). Ask for the list the way it
can be authorized:

```bash
pravaha queries --url grpc://localhost:19090
```

**This is the code whether or not other views exist.** It used to be `PRV-4023` only over an
*empty* catalogue and the planner's `PRV-2002 Object '...' not found` otherwise, so the code you got
for one missing view depended on whether some unrelated view happened to be registered. A read
racing a `drop` and its `re-register` hit exactly that — the view is momentarily in neither
catalogue — and came back with an SQL validation failure where a retry loop needed this (L-3). An
unknown **column** of a view that does exist is still `PRV-2002`, which is the right code for it.

### `PRV-7001` vs `PRV-7002` — told apart deliberately

| | | Your move |
|---|---|---|
| `PRV-7001` | Not authenticated | Present a credential, or a fresh one |
| `PRV-7002` | Authenticated, not authorized | Ask for access — a new credential will not help. On `drop`, `pause`, `resume`, `replace` or a debug session, "may not administer" means the view is somebody else's: ask its owner (`pravaha describe` names them), an admin, or for `MODIFY` on it. "May not address 'acme.default.orders': a qualified name reaches outside the caller's tenant" means you named another tenant's view by its catalogue name, which only an admin may do (ADR-060) — and it is said alike whether that view exists or not |
| `PRV-7004` security misconfigured | A **security setting** this node refuses to start with — not a caller being denied. The message names the key and the value. Split from `PRV-7002` by E-3, which had accumulated four unrelated meanings across twenty sites: an authorization denial, the open-server refusal, the policy/authentication contradiction, and a bad configuration value. The advice for `7002` ("ask for access; a new credential will not help") is right for a denial and useless for a typo — an operator who wrote `policy: permisive` was being told to go and ask somebody for permission. One is about a caller and is answered by a grant; this one is about a file and is answered by an edit Also raised when `pravaha.security.audit: file` names a path this node cannot write (`pravaha.security.audit-file`) — refused at startup rather than at the first decision nobody sees, because a node that starts believing it is auditing and writes nowhere has no record at all (CFG-23). And when `pravaha.security.audit-recent`, the number of recent decisions kept readable over `GET /api/v1/audit`, is below 1 |
| `PRV-7006` masked column used | A column masked for you (ADR-059 §4) is used where its value would be compared rather than shown: a `WHERE` operand, a `GROUP BY` key, a join key, an `ORDER BY` or ranking key, an aggregate's argument, a registered view's key column, a subscription's tap filter, or an alert's `WHERE`. Comparing it would tell you which rows share its value, or their order — what the mask hides. Select it to see its masked value; compare on another column. `SHOW EFFECTIVE ACCESS FOR USER <you> ON <view>` lists the masks that apply to you |
| `PRV-7007` subscription ended: policy changed | The row filters or masks that apply to you on a view you were subscribed to changed, so the stream ended rather than change what it means half-way. Subscribe again: the new stream starts from what the new policy shows you. Sent over Flight as `UNAVAILABLE`, which clients already retry |
| `PRV-7005` sink write not filterable | The policy answered `mayWriteTo` for a sink with **allow plus a row filter**, and a sink takes a query's whole changelog or none of it — there is no row of it the engine could withhold and still leave the destination equal to the view. Refused at registration, before the sink is opened, rather than written unfiltered. Not `PRV-7002`: nothing was denied, so it is the policy that has to change and not the caller's entitlement. Answer `mayWriteTo` with `allow()` or `deny()` |
| `PRV-7010` credentials refused | A sign-in whose user name and password do not match. The answer is the same for an unknown user and a wrong password, and takes as long, so it does not say which (ADR-052). Each failure counts towards the lockout |
| `PRV-7011` account locked | Five failed sign-ins within 15 minutes lock the account for 30 minutes (`pravaha.identity.lockout.*`). Wait until the time in the message, or ask an administrator to reset the password, which clears it |
| `PRV-7012` password refused by policy | A new password breaks a rule of `pravaha.identity.password.*`, named in the message: at least 12 characters, from 3 of lower case, upper case, digits and symbols, and none of the last 5. The same check covers creation, change, an administrator's reset and a reset token |
| `PRV-7013` key not valid | An API key that is unknown, expired, revoked, past its rotation overlap or presented with the wrong secret. The caller of a transport sees `PRV-7001`; this code is in the audit trail and on the identity API. Issue a new key |
| `PRV-7014` key for another environment | A key minted by another deployment: `prv_qa_...` is refused by a node whose `pravaha.identity.environment` is `prod`. Issue a key on this deployment |
| `PRV-7015` would widen | A key asked for a role its holder does not have, or an administrator tried to remove their own admin role. A key can narrow its holder, never widen them |
| `PRV-7016` session expired | A console or API session past 30 minutes idle or 12 hours in all (`pravaha.identity.session.*`), ended by logout, a password change or reset, or its user being disabled. Sign in again |
| `PRV-7017` reset token invalid | A password-reset token that is unknown, already used or older than `pravaha.identity.reset-token-life`. Ask an administrator for another |
| `PRV-7018` must change password | Only with `pravaha.identity.password.force-change: true`: this account was created or reset by an administrator, and its session can do nothing but change the password |
| `PRV-7019` default admin password | The bootstrap user `admin` still has its published default password (ADR-052), and this node is not the dev profile, so it refuses to start. Change the password, or set `pravaha.identity.allow-default-admin-password` to run like this on purpose |
| `PRV-7020` identity request invalid | An identity request that cannot be carried out as written: a user name outside `[a-z][a-z0-9._-]{1,63}`, a user that exists, a status other than `active` or `disabled`, a key life outside 1 to `pravaha.identity.key.max-days` |
| `PRV-7021` identity not found | No user, or no session, by that name |
| `PRV-7030` catalog disabled | A catalogue statement (`GRANT`, `SHOW GRANTS`, `CREATE NAMESPACE`, …) or `/api/v1/catalog` call on a node whose catalogue is off (`pravaha.catalog.enabled: false`). Who may do what is decided by `pravaha.security.policy` there, and there are no grants to show or change. Turn the catalogue on (ADR-059) |
| `PRV-7031` catalog: no such object | No object by that name that you may see. The same answer for a name that does not exist and one in a namespace you may not `USE`, so it confirms nothing. `SHOW NAMESPACES` and `pravaha catalog ls` list what you may |
| `PRV-7032` privilege not applicable | A privilege that means nothing on that kind of object — `WRITE` on a view, `CREATE` on a sink, `SUBSCRIBE` on a stream. The message lists what applies there |
| `PRV-7033` catalog: manage required | Changing an object's grants, description or tags needs `MANAGE` (or ownership) on it; giving it away needs ownership; creating a namespace needs `CREATE` on the tenant; asking what someone else may do needs `MANAGE`. Ask the object's owner or an administrator |
| `PRV-7034` two authorities | The catalogue imported `pravaha.security.policy` once and the setting now says something else, or the catalogue holds grants it never imported a policy for. The node refuses to start rather than obey one of two authorities silently. Set `pravaha.catalog.authority: catalog` to let the catalogue alone decide, or restore the setting it imported |
| `PRV-7035` catalog journal failed | The catalogue journal (`pravaha.catalog.journal`, by default `catalog.journal` beside the registry journal) cannot be read or appended to, or holds a record a newer version wrote. A change is refused rather than acknowledged and lost at the next restart. Check the file's permissions and the disk |
| `PRV-7036` catalog: object exists | `CREATE NAMESPACE` of a namespace that exists. Add `IF NOT EXISTS` if that is fine |
| `PRV-7037` catalog request invalid | A catalogue request that cannot be carried out as written: a name part with a dot, quote or space, a tag key over 128 characters, a grantee that is not `ROLE <name>` or `USER <name>`, a grant of `OWN` (ownership is transferred with `OWNER TO`), or a user this node does not know |
| `PRV-7038` policy invalid | A row filter or mask the catalogue will not hold or bind: a subquery, a non-deterministic function (`RAND`, `CURRENT_TIMESTAMP`, …), a function not on the policy list, a mask naming another column than its own or producing another type, a filter naming a column the object does not carry, or one true for every row. The message names which. Rewrite the expression; bind a mask only where its column exists |
| `PRV-7039` policy claim missing | A policy that applies to you reads a claim with `session_attribute('<claim>')` that your credential does not carry. Refused rather than guessed — an absent region is neither every region nor none. Sign in with a credential that carries it (`pravaha.security.tokens.<t>.claims`, or your identity provider's token), or have the policy exempt you with `EXCEPT ROLE` |
| `PRV-7040` policy conflict | Two policies that cannot both hold: two masks on one column for the same reader (bound directly, or one by tag), or `DROP ROW FILTER` / `DROP MASK` of a policy that is still bound — dropping it would widen what those objects show. Unbind one with `ALTER ... UNSET POLICY` first |
| `PRV-1050` missing field | A JSON request body left out a field the endpoint requires — today a null `sql` on `/validate` or `/explain`. Returned as **400**, not as a 200 with `valid:false`: a malformed request is not a query that failed to validate, and the distinction matters to anything reading the response programmatically. It used to surface as a raw `NullPointerException` message dressed in a `PRV-` code (API-F9). Deliberately 1xxx rather than 2xxx — a `PRV-2xxx` would send the reader to the SQL documentation for a request that carried no SQL. An *empty* `sql` is not this: the console sends one between keystrokes and the lexer already refuses it precisely |
| `PRV-1053` malformed text | A string in a request body that is not well-formed text — today an unpaired UTF-16 surrogate, half of a character, which no UTF-8 encoder can carry: every one substitutes U+FFFD, so the name or the SQL the server would store, log and quote back is not the one that was sent. Refused as **400** in the deserializer, before the body becomes an argument, because a stream registered under such a name is a key no later request can address — not by URL, not in SQL, and over Flight only as `?`. Over Flight the same text is refused with the same code: the Java SDK refuses it before sending, because protobuf and `String.getBytes` would deliver `?` in its place, and the server refuses a control request whose bytes are not UTF-8 rather than decoding them into replacement characters |
| `PRV-1051` invalid parameter | A query parameter the endpoint could not read — today on `GET /api/v1/audit`: a `since` or `until` that is not an ISO-8601 instant (`2026-09-19T08:00:00Z`), a `decision` that is neither `allow` nor `deny`, a `cursor` that is not a previous page's `nextCursor`; on any endpoint, an `?offset=` or `?limit=` that is present and empty or not a number, an empty `?level=` or `?format=` on `/explain`. Returned as **400** naming the parameter rather than the filter being dropped: an audit search that ignored a malformed `since` would answer a different question and look right |

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

**What lateness is actually in force?** The node states it, one line per stream, at startup
(TIME-6):

```
stream txn: event-time=event_time, out-of-orderness=PT10M, allowed-lateness=PT0S
stream ref: event-time=none -- no window over this stream can ever close
```

Read those before anything else when a windowed query is `RUNNING` with a climbing `ROWS IN` and an
empty view. An out-of-orderness larger than the span of the data on hand holds every window open
for ever, and it is a legitimate setting, so nothing refuses it — it is only visible here.
**A unitless number is seconds**: `out-of-orderness: 60` is a minute, not sixty milliseconds
(TIME-3).

**A stream with no event-time column is no longer one of the ways to get here.** It says so in the
startup line above, and a windowed query over it is refused when it is registered rather than left
running (TIME-6):

```text
PRV-2002  TUMBLE is given DESCRIPTOR(event_time), but 'ref' declares no event-time column -- so no
          watermark advances over it and no window this query opens can ever close. It would
          register, report RUNNING, ingest every row and emit nothing, for ever.
            Declare the column: pravaha.streams.ref.event-time: event_time, or 'eventTime' on
            POST /api/v1/streams. The column must be a TIMESTAMP.
```

A query that registered before an upgrade and is refused after it is this: it could never have
answered, and the declaration is the fix. Over a **bounded** read the same SQL is still accepted,
because those windows are fired by the end of the scan rather than by a watermark.

**Is the subscription attached?** A plain subscription starts from *now*, not from the beginning of
time: a change committed before the subscriber attached was published to nobody. Worse, reading the
view beside it does not fill the gap — subscribe-then-read and read-then-subscribe can both lose the
commit in flight at that moment, silently (SUB-1). To keep a copy of a view, or to wait for one to
reach an answer, subscribe *from a snapshot* instead: the first batch is the view at a commit and
every later batch is a commit after it, with none missed. The console shows subscriber counts;
`RegisteredQuery.subscriberCount()` is the same number in code.

**Did your subscription end, and did it say why?** It does now (STRM-12). An administrative drop,
a node shutting down and your own `close()` used to be one signal -- a clean completion, which
reads as "this stream is finished" -- and only the last of the three is. A drop arrives as
`PRV-8018` with Flight status `NOT_FOUND`: the name is gone, what you received is complete, and
there is nothing to reconnect to. A shutdown arrives as `PRV-8019` with `UNAVAILABLE`, which every
gRPC client already retries: the query is journalled, comes back `RUNNING`, and a read of the view
catches you up on what it did while you were away. `PRV-8018` also ends a subscription on a name
that was **sharing** a computation with another registration -- dropping that name leaves the
computation running for the other name, and a subscriber on the dropped one was being streamed
rows under a name a read refused as nonexistent (STRM-14).

**Is the query paused?** A paused query keeps answering at the frontier it reached and stops
advancing. Rows arriving while paused are dropped rather than buffered — a pause is meant to stop it
doing work.

```bash
pravaha queries        # state column
```

**Has a source stopped?** A source that fails mid-read — a deleted file, a revoked credential, a
line it cannot decode — stops its feed and is not retried; the query stays `RUNNING` and its view
answers at the frontier it reached. `pravaha queries` shows it as `RUNNING (source stopped)` with a
line giving the code, the stream#partition and the time; `GET /api/v1/queries/{name}` has the same
under `feed`; the console marks it on the query page and makes it a critical finding on Operations;
`pravaha_query_feed_stopped{query=}` is 1; and the node logged an `ERROR` line when it happened. The
code is the source's own (`PRV-5040` for a file it could not decode, `PRV-5107` for a Kafka read) or
`PRV-5092` when the feed stopped for a reason that was not the source's. Fix the cause, then drop and
register the query again, or restart the node. For records a source cannot decode, a dead-letter
queue (`pravaha.dlq.directory`) keeps the source going instead.

**Has the sink stopped?** A query registered with `--sink` keeps its view current even after the sink
refuses a batch: the sink is detached with `PRV-8009` and an `ERROR` line names it, and nothing more
is written to it, because writing past a lost batch would leave the sink missing a change with
nothing to say so. The view still answering is not evidence the sink is receiving. `pravaha queries`
shows it: the `SINK` cell reads `<name> (detached)` and a line under the table gives the code and
what happened. `GET /api/v1/queries/{name}` carries the same as `sink.attached` and `sink.failure`,
and both SDKs' listings as `sinkState`/`sink_state` and `sinkFailure`/`sink_failure`. Fix what the sink
refused, then drop and re-register the query; it is sent the view's contents first. A transactional
sink is detached the same way when a prepare or a commit fails.

**Is the sink transactional?** Then nothing it is written is visible until the next checkpoint is
stored — up to `pravaha.checkpoint.interval` behind the view — and without
`pravaha.checkpoint.directory` each commit is its own transaction. The registration's `INFO` line
(`query '…' writes to sink '…', exactly-once: …`) says which the node is doing. For `kafka-sink`,
also check the reader: a consumer with the default `isolation.level=read_uncommitted` can see a
commit that a crash aborted and the restart redid, twice; exactly once is promised to
`read_committed`.

**Did a filter silently match nothing?** `WHERE tier = ?` bound to `NULL` matches **no rows**, because
`x = NULL` is UNKNOWN under SQL's three-valued logic. `IS NULL` is what finds the empty ones.

## "It is running, but it cannot keep up"

Different from the above: rows *are* arriving and the query *is* answering, just not as fast as the
source produces. Nothing fails, nothing is dropped — the source is slowed instead — so the only
evidence is in the numbers.

**Is it actually backpressured?** `pravaha_query_backpressure_blocked_fraction{query=}` is the
share of time a writer into this query's lanes had nowhere to put a row. Near zero and the query is
keeping up and the lag is in the data (look at `pravaha_query_watermark_lag_seconds` instead).
Near 1 and the lane is the limit. `pravaha_query_inbox_depth` against `pravaha_query_inbox_cells`
says whether the queue is standing full or merely spiking.

**Is it this query or a neighbour?** On a shared lane (`pravaha.lane.multiplex.*`) the queries
queue behind one inbox. `pravaha_query_backpressure_wait_seconds_total{query=}` counts only this query's
own writers; `blocked_fraction` counts every writer into the lane. High `blocked_fraction` with a
low wait time is a query being held up by whatever else is on that lane — check
`pravaha_lane_blocked_fraction{lane=}` and `pravaha_lane_shared_queries{lane=}`, and either move
the query off (`pravaha.lane.multiplex.max-queries-per-lane`) or add lanes.

**Which operator is the cost?** Set `pravaha.metrics.operators: true`, restart the node (the
counters are compiled into a query's stages, so a running query does not gain them), and read
`GET /api/v1/queries/{name}/plan`. Each node carries its own rows in, rows out, state bytes and a
sampled self time, and `bottleneck` names the node most of the query's own time went into. It
costs about 12 % of a narrow query's throughput, which is why it is off by default; `docs/OPERATIONS.md` has the
measurement and what the sampling error is.

**Common answers once the operator is named.** A join holding megabytes of state and most of the
time is usually a match window wider than it needs to be (`pravaha_query_state_held`). A windowed
aggregate is usually too many groups. A scan carrying most of the time with a filter above it
passing 1 % is a filter that should have been pushed into the source — `EXPLAIN` says what the
source accepted.

## "The numbers are wrong"

**Is it `AVG` over an integer?** SQL's `AVG` on an integer column is integer division: a mean depth of
47.9 reads as `47`. Take `SUM` and `COUNT` and divide where you have floating point.

**Are you ignoring weights?** A subscriber maintaining its own aggregate must apply the `-1`/`+1`
weights, or it drifts from the view the first time late data corrects a window.

**`PRV-2042` — "is read from a … source that repeats rows".** Refused at registration, before a
feed or a sink opens (SCAN-1). Some sources deliver a row they have already delivered, with nothing
retracting the earlier copy: `cassandra` with `deletes: ignore` (the default) emits every row again
on every pass; `aerospike` with `deletes: ignore` reads an updated record again as the new row, and
re-reads a record written while a scan ran; `jdbc` reads a row again when an update moves its
watermark column, and — without `key.column` — may re-read rows tied on the watermark. Every copy
arrives at weight `+1`, so a `COUNT` over a Cassandra table grew by the table's size every interval,
and an Aerospike update was counted twice, under a success status. The source says so in its
capabilities (`repeatsRows`), and the registry refuses what depends on how many times a row arrived:

- any aggregate, windowed or not (`MIN`/`MAX` and `DISTINCT` included — an update is never
  retracted from them either);
- a join, which pairs every copy again;
- a sink that cannot upsert by key (`filesystem`, `jdbc-sink` with `mode: append`, `kafka-sink`
  with `format: changelog`), which would write every copy as another row.

What is still admitted: a projection or filter (computed columns and a lookup join included) served
as a keyed view, or written to a sink that upserts by key. A copy overwrites its own key with the
values it already has, and a source that repeats never retracts, so a keyed read or a scan returns
each row once, as the store holds it. A **subscriber** to such a view sees each copy as another `+1`
of a row it already has: overwrite by key, and do not sum the weights.

The fix is on the binding the message names. For `cassandra` or `aerospike`, set `deletes: detect`
(with `deletes.state.dir`): each pass is compared with what was emitted, so an unchanged row is not
sent again, an update is a retraction and an insertion, and a delete is a retraction — an exact
changelog, [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §2.1. For `jdbc`, poll a watermark
column that only an insert sets (a sequence, a `created_at`), with `key.column`, and say so with
`watermark.moves.on.update: false` — or read a PostgreSQL table through `postgres-cdc`. A query
journalled before this check existed comes back from a restart as a recovery refusal with this code,
not as a view.

**Did rows age out?** A view keeps everything unless its registration set a retention (`RETAIN FOR`,
`--retain`); one that did evicts by event time. `evicted()` counts what has
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
format. Any other part refused later in the restore — a lane's operator state, what the checkpoint
recorded for a sink — is undone the same way: every part already back is put back to empty first
(RESTOREPART-1). Look for the node's `WARN` "checkpoint N of query '…' could not be restored" and a
rise in `pravaha_query_checkpoint_failures_total`; both name what was refused.

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
| `PRV-4091` no such dead letter | The id named is not in that query's queue. Three ordinary causes and they want different actions: the id is mistyped; the page it came from is stale, because the entry has since been replayed; or retention evicted it. The queue's `evicted` count — on `GET /api/v1/queries/{name}/dead-letters`, on `pravaha dlq list` and on the console's screen — says whether the third is plausible |
| `PRV-4092` replay refused | The engine will not replay that dead letter, because doing so could not be correct, and the message says which of four reasons applies. The stream's schema has changed since the record was rejected, so the same bytes would decode into a different row. The source promises `EXACTLY_ONCE` and has not read past the record's offset, so it is going to deliver the record again itself and feeding it in now would count it twice — wait for the source to reach it. The source cannot decode a record outside its own read, so the bytes cannot be put back through it. Or the query is no longer registered, or reads a different stream now, so there is no decoder that would decode it as it was decoded then. In every case the remedy is to correct the record at the source; a replay is for a record the source will never send again |
| `PRV-3022` window span implausible | One watermark advance would fire millions of windows. Almost always a single row carrying an event time far outside the rest of the stream — an unset field read as the epoch, a value in the wrong unit, or a parse that silently produced zero. The message names both instants and the slide; check the event-time column of the **earliest** row the query saw, not the latest. The limit is 10,000,000 windows per advance, which a legitimate catch-up stays well inside: a day of one-second windows is 86,400 and a year of hourly ones 8,760. If the span really is intended, the window is too fine for it (TIME-1) |
| `PRV-3024` retracted a row it does not hold | A top-N (`ROW_NUMBER() OVER (...)` filtered to `rn <= N`) holds every row of every partition, and was handed a retraction of a row it holds no copy of. The input then withdraws more than it ever inserted, which has no answer as a set of rows, so the query stops rather than numbering the rest around a row held a negative number of times. The cause is upstream: a source emitting deletes for rows it never emitted, or a replay that started after the insert. Replay the stream from an offset before the row was first written |
| `PRV-3025` a total left the 64-bit range | A `SUM`, `COUNT` or `AVG` total passed `±9223372036854775807` — of a `BIGINT`, or of a `DECIMAL`'s unscaled value. The message names the aggregate. It used to wrap round and be served as a large negative number (SUMWRAP-1); it is now refused, the query `FAILED` or the read refused. Every total is checked, a retraction and a window's combined slices included; a batch is netted in 128 bits first, so a total that passes the range inside one batch and comes back is answered (TRANSOVF-1). Aggregate a smaller quantity (scale the column down), split the total by a key, or filter out the rows carrying it |
| `PRV-3001` arena exhausted | Off-heap arena full — usually a batch far larger than expected, or a slab sized for narrower rows than the query produces. The message names the setting to change: `pravaha.lane.arena.slab-bytes`, or `pravaha.lane.batch-size` to make each batch smaller. The rule is `batch-size × widest output row` must fit one slab. A *row* that does not fit an inbox cell is the same code from the ingest side and names `pravaha.lane.inbox.cell-bytes` instead. These are real settings as of ADR-036; until then eleven messages named `arena.slab.size` and `lane.inbox.cell.size`, neither of which existed (PF-3) |
| Too many open files | One bound source costs about one descriptor. The node logs its descriptor ceiling at startup, and a source that fails to open near that ceiling gets a sentence naming `ulimit -n` and `LimitNOFILE`. Two codes still name the wrong thing when descriptors are the real cause: `PRV-5040 FILESYSTEM_DECODE_FAILED` (a decode code for a resource exhaustion) and `PRV-5080 AEROSPIKE_CONNECT_FAILED`, whose every suggested remedy is wrong in that case — the Aerospike client's exception carries no cause, so it cannot be told apart by catching it (SRC-4) |
| Disk growing | **Not checkpoints, unless you configured it that way.** `PeriodicCheckpointer` prunes after every checkpoint, keeping the newest `pravaha.checkpoint.keep` (default 3) per query; this row used to say nothing called `prune`, and something does. Check `pravaha.checkpoint.keep`, and then the registry journal, which grows until it is compacted. See [`OPERATIONS.md`](OPERATIONS.md) |

## `PRV-1028` — a schema string that will not parse

**The code moved, and the message grew two names (TY-8, TY-9).** A `name:TYPE,name:TYPE` schema
string that will not parse used to answer `PRV-5040`, the filesystem plugin's decode code, because
the parser for that grammar lives in that plugin. Two things were wrong with that. The HTTP API
derives its status from the code's *category*, so `POST /api/v1/streams` with a misspelled type
answered `500 Internal Server Error` for a mistake in the caller's own request body; `PRV-1028` is
a configuration code, so the same request now answers `400`. And the message named neither the
stream nor the column, so an operator whose node refused to start over `pravaha.streams.*.schema`
had one sentence and every declared stream to check it against. It now reads:

```text
PRV-1028  stream 'd', column 'amt': unknown type 'DECIMAL'. Supported: BOOLEAN, INT8, ...
```

**`DECIMAL(10,2)` is declarable** (TY-7, fixed). The grammar is split at commas at paren depth
zero, so a parenthesised type survives every surface: `--schema`, `--out-schema`,
`pravaha.streams.*.schema` and `POST /api/v1/streams`. Arithmetic over a decimal is still not built
— that is `PRV-2021` at plan time, and deliberate — but the column can be declared, scanned,
filtered and projected.

## `PRV-5040` — reading a file

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

**A `kafka-sink` detached with `PRV-5102` saying another producer fenced it.** Two sinks opened with
the same `transactional.id` — two nodes running one binding, or two registrations naming it — and
the broker lets only the newest write. Give each registration its own binding (the default id is the
binding's name). The same code follows a transaction that outlived `kafka.transaction.timeout.ms`.
`PRV-5101` at registration is the brokers unreachable, the target topic missing (the sink never
creates it), or the credentials or ACLs refused; `PRV-5103` is the staging topic compacted, not
creatable, or — at a restart after a long outage — already past the staged changes a checkpoint
recorded, which are then lost to the topic: raise `staging.retention.ms` and re-register.

**A `delta-sink` was refused, or detached.** The code says which:

- `PRV-5056` — the binding cannot be honoured as written: a `mode` that is neither `upsert` nor
  `changelog`, a missing `key.columns` in upsert mode or a pointless one in changelog mode, a key
  column that is floating point, nullable or not in the schema, a `transaction.id` that cannot also
  be a directory name, a changelog schema that already declares `_op` or `_weight`, or a
  `partition.columns` entry that is not a declared column, is `BYTES`, is named twice, or leaves no
  column unpartitioned.
- `PRV-5051` — a column of a type Delta has no equivalent for. `TIME` is the one: Delta has `DATE`
  and `TIMESTAMP` and nothing for a time of day, and writing nanoseconds into a `BIGINT` that no
  Delta reader reads as a time is not a substitute. Project the column away or convert it.
- `PRV-5057` — the table at `path` is not the one the binding describes: a column missing, renamed,
  retyped or in another position (named in the message), a `NOT NULL` table column under a nullable
  declaration, a table **partitioned otherwise** than `partition.columns` says (an unpartitioned
  binding over a partitioned table included), or `create: false` with no table there. The sink never
  alters a table's schema; change the binding, evolve the table with the engine that owns it, or
  point the sink at a new path.
- `PRV-5055` — the sink's upsert mode found a data file carrying a **deletion vector**. It rewrites
  whole files to remove rows and does not carry a vector through the rewrite, so the rows the vector
  deletes would come back. Point the sink at a table without deletion vectors, or purge them with
  the engine that owns the table (`REORG TABLE ... APPLY (PURGE)`) and disable
  `delta.enableDeletionVectors`. The `delta` source reads deletion vectors and does not raise this.
- `PRV-5058` — staging, reading back or committing failed. Two cases are the sink refusing rather
  than the filesystem failing: a **null key column**, and a `TIMESTAMP` that is not a whole number
  of microseconds — Delta stores microseconds, the engine nanoseconds, and a rounded timestamp is a
  value nobody can tell from a true one.
- `PRV-5059` — **another writer committed to the table while this commit was being built.** The
  sink does not retry: its commit removes the data files it read, and replaying those removals over
  the other writer's version would undo their change. It is detached with `PRV-8009` and the
  checkpoint's changes are left staged under `staging.dir`. A Delta table maintained by a
  continuous query must have no other writer; stop the other one, then drop and re-register the
  query.

**An `iceberg-sink` was refused, or detached.** The code says which:

- `PRV-5140` — the binding cannot be honoured as written: no `path` (or a URI such as `s3://`, since
  the sink writes local-filesystem tables only), no `schema` or an unknown type in it, a `mode` that
  is neither `upsert` nor `changelog`, a missing or pointless `key.columns`, a key column that is
  floating point, nullable or not in the schema, a changelog schema declaring `_op` or `_weight`, or
  a `transaction.id` that cannot also be a directory name.
- `PRV-5141` — the Iceberg table at `path` is not the one the binding describes: a column missing,
  renamed, retyped or in another position, a required table column under a nullable declaration, a
  format-version 1 table in upsert mode (equality deletes need version 2), or `create: false` with
  no table there. Checked at configuration when the table exists. The sink never alters a table's
  schema.
- `PRV-5142` — writing or committing failed; the message carries the reason. Refusals rather than
  failures: a `TIMESTAMP` or `TIME` that is not a whole number of microseconds, a decimal that does
  not fit its declared scale, a null key, or a handle from another `transaction.id`.
- `PRV-5143` — upsert mode's changes since the checkpoint began reached `upsert.max.keys` distinct
  keys (default 1,000,000), which it holds in memory until the checkpoint commits them as one
  snapshot. Checkpoint more often, raise `upsert.max.keys`, or use `mode: changelog`.

**A `delta-sink` table is full of small files.** Expected, and not something the sink fixes. Each
checkpoint is one Delta commit writing at least one Parquet file, plus one for every file an upsert
had to rewrite. Run Delta's `OPTIMIZE`, and `VACUUM` for the files the rewrites superseded, from an
engine that has them (Spark, `delta-rs`). Delta Kernel exposes no compaction API, so the plugin has
none. Lengthening `pravaha.checkpoint.interval` makes fewer, larger commits; `mode: changelog`
writes one file per commit and rewrites none.

**A `kafka` source stopped, or its registration was refused.** The code says which:

- `PRV-5101` — at registration: the brokers did not answer within `start.timeout`, the topic does not
  exist (the source never creates it), or the credentials or ACLs refused `Describe`/`Read`.
- `PRV-5105` — a record does not fit the declared schema (not JSON, not the Avro or protobuf the
  schema describes, a string in an `INT64` column, a
  missing `NOT NULL` column, a tombstone in `format: json`), and there is no dead-letter queue. The
  message names it as `topic/partition@offset`. Set `pravaha.dlq.directory` to set such records aside
  and read on, fix the producer, or for an upsert topic's tombstones set `tombstone: skip`. The
  position stays before the record, so a restart meets it again rather than skipping it.
- `PRV-5106` — the offset the checkpoint resumes from is gone: retention deleted records the checkpoint
  had not read (the node was down longer than the topic's `retention.ms`), or the offset is past the
  partition's end because the topic was deleted and recreated. Those records are lost to every
  reader, and resuming anywhere else would hide it. Raise retention, then drop the checkpoint and
  re-register the query; it starts from `start.from` without them.
- `PRV-5104` — a checkpoint holds a position this source did not write, or one for another topic or
  partition: the binding's `topic` was changed under an existing checkpoint. Register afresh.
- `PRV-5107` — fetching failed in a way retrying will not fix, such as an ACL revoked mid-stream, or
  a batch compressed with `lz4` (ADR-053: its library is native code the build refuses), or with
  `snappy` or `zstd` on a platform whose native library does not load. The message names the codec.
- `PRV-5108` — at registration, with `format: avro` or `format: protobuf`: the writer schema cannot
  become rows of this stream. The message names the column with no field, or the field and the
  column whose types cannot meet — `schema.file` that is not an Avro schema, `schema.descriptor`
  that is not a `FileDescriptorSet` or has no message of that name, an Avro `long` where the column
  is a `TIMESTAMP` (declare it `timestamp-millis`), a `repeated` field where the column is one
  value. Fix the binding's `schema`, or the schema the producer writes. A schema that arrives with
  the record, from the registry, cannot be checked this early: those records become dead letters
  (`PRV-5105` with no dead-letter queue), and the message names the schema id. On `kafka-sink` it
  is the other direction: a column the schema cannot hold exactly (a nullable column into a field
  with no null branch or no presence, an `INT64` into an Avro `int`, a Protobuf unsigned integer, a
  `FLOAT64` into a `float`, a field no column fills that cannot be left null, a `TIMESTAMP` or `TIME`
  declared finer than its Avro `-millis` or `-micros` field — declare it `TIMESTAMP(3)` or
  `TIME(6)`, and each value is floored to that precision), and, with `schema.registry.url`, a
  `schema.id` or `key.schema.id` naming a different schema in the registry than the binding's.
  Change the schema file, the id or the column's type.
- `PRV-5109` — the schema registry could not be read: unreachable or timed out after three attempts
  (`schema.registry.timeout`), the credentials refused (401/403 — set `schema.registry.user` and
  `schema.registry.password`, or `schema.registry.token`), no schema with that id (404 — the records
  were written against another registry), or an answer that is not the documented shape of
  `GET /schemas/ids/{id}`, which is usually a proxy's error page. The reader stops rather than
  dead-lettering records that are probably fine; it resumes from its checkpoint once the registry is
  back.

A source that seems stuck with nothing refused is usually `read_committed` waiting behind a producer's
open transaction — the position cannot pass it until it commits or `transaction.timeout.ms` aborts it.

**A `postgres-cdc` source is refused at registration with `PRV-5112`.** The database cannot support
change capture as configured, and the message names the statement that fixes it: `wal_level` is not
`logical` (`ALTER SYSTEM SET wal_level = logical;` and a **restart** — a reload changes nothing), the
table is not `REPLICA IDENTITY FULL` (`ALTER TABLE ... REPLICA IDENTITY FULL;`, without which a
delete could retract only the key and the view would stay wrong for ever), the publication does not
publish updates and deletes or does not include the table, the server is older than PostgreSQL 14,
or the slot is missing, invalidated (`wal_status = 'lost'`) or belongs to another plugin or database.
`PRV-5113` is the table's columns disagreeing with a declared `schema`, or a column type with no
mapping (leave it out of a declared schema). `PRV-5111` is the database unreachable, the credentials
refused, or the PostgreSQL driver not on the classpath — the plugin uses the driver the deployment
supplies, as `jdbc` does. A role that may not create the publication — it does not own the table,
or has no `CREATE` on the database — is `PRV-5112`, naming `ALTER TABLE <table> OWNER TO <role>;` or
`GRANT CREATE ON DATABASE <db> TO <role>;`, or a publication made by a role that may with
`create.publication: "false"`.

**A `postgres-cdc` source with `snapshot.mode: initial` is refused with `PRV-5118`.** The initial
snapshot could not start or could not be read. At start it is almost always a transaction left open
since before the registration: PostgreSQL creates the temporary slot that pins the snapshot only once
every transaction already running has ended, and `start.timeout` (30s) bounds the wait. Find it with
`SELECT pid, xact_start, state FROM pg_stat_activity WHERE backend_xid IS NOT NULL ORDER BY
xact_start;`, end it, and register again. The other causes are `max_replication_slots` with no room
for the temporary slot, and the snapshot's connection failing mid-read — which a restart from the
last checkpoint recovers from exactly, resuming after the last key delivered. `PRV-5112` with
"needs a primary key" is `snapshot.mode: initial` on a table without one: add one, or use `never`.

**A `postgres-cdc` query stopped with `PRV-5116`.** The change stream carried something that cannot
become rows: a `TRUNCATE` of the captured table (it carries no rows, so there is nothing to retract),
or a before-image with only the key (the table's replica identity was changed while it was being
captured). Everything before it was delivered. `PRV-5115` at a restart means the slot has already
been confirmed past the checkpoint being restored — the newest checkpoint was unreadable and recovery
fell back to an older one, or the slot was recreated — and PostgreSQL has released the changes in
between. `PRV-5117` is the replication stream failing in a way no reconnect can fix: the slot
dropped, invalidated, or the role's privileges revoked. For all three the recovery is the same:
stop the registration, delete its checkpoint directory, drop the slot, register again
([`OPERATIONS.md`](OPERATIONS.md), *Change data capture: the replication slot*).

**A second query over a `postgres-cdc` or `mysql-cdc` stream is refused with `PRV-8028`.** The
binding names one replication slot (or replica `server.id`), and another query — named in the
message — is reading it; a slot streams to one connection at a time. Bind the table a second time
under another stream name with a `slot` (or `server.id`) of its own and register the second query
over that stream, or drop the query that holds the binding. Before CDCREPL-2 this registration was
accepted and failed `PRV-5117` ("is active for PID") about fifteen seconds later.

**The database's disk is filling and `pg_replication_slots` shows a `pravaha_` slot retaining it.**
The slot is confirmed only at Pravaha checkpoints: check the node is running, that
`pravaha.checkpoint.directory` is set, and that the source's `heartbeat.interval` is not `0` on a
quiet table. A slot nothing will read again has to be dropped by hand —
`SELECT pg_drop_replication_slot('<slot>');` — because nothing else ever will.

**A `mysql-cdc` source is refused at registration with `PRV-5152`.** The server cannot support
change capture, and the message names the statement that fixes it: binary logging off (`log_bin`),
`binlog_format` not `ROW` (`SET PERSIST binlog_format = 'ROW';` — sessions already connected keep
the old format until they reconnect), `binlog_row_image` not `FULL` (`SET PERSIST binlog_row_image =
'FULL';`, without which a delete could retract only part of the row), `binlog_transaction_compression`
on, or the user without `REPLICATION SLAVE` and `REPLICATION CLIENT`, directly or through a role active
at login (a granted role that is not active is named, with `SET DEFAULT ROLE ALL TO ...`). `PRV-5153` is the table missing, the user without `SELECT` on it, or a column
type with no mapping (`JSON`, `ENUM`, `SET`, `BIT`, `TIME`, `YEAR`, spatial types, `DECIMAL` wider than
38 digits). `PRV-5150` includes `snapshot.mode: initial`, which is not built for MySQL yet.
`PRV-5155` at a restart is the checkpoint's binlog file purged by `binlog_expire_logs_seconds` (for a
GTID checkpoint, transactions after it purged); the changes in between are gone. An idle table's
position follows the log through heartbeats and rotations, so a purge of files it had nothing in does
not cause it. `PRV-5156` is a `TRUNCATE`, `ALTER`, `DROP` or `RENAME` of the captured table, a column
type change the binlog's table description shows (count kept: `SMALLINT` to `INT UNSIGNED`, a decimal's
scale, `NOT NULL` dropped), or an event that cannot be decoded; everything before it was delivered.
`PRV-5157` is the binlog connection failing ten times in a row. For `PRV-5155`, `5156` and `5157`: drop
the registration and its checkpoint directory, and register again. `PRV-5158` is a GTID checkpoint
restarted against a server that has not executed all of it — a replica behind the old primary after a
failover: wait until it has caught up, then start again.

**An `aerospike` or `cassandra` source with `deletes: detect` stopped with `PRV-5120` or
`PRV-5122`.** A partition (Aerospike) or token range (Cassandra) would hold more rows than
`deletes.max.keys` (default 1,000,000 each). Every row the source has emitted is remembered so that
its disappearance can be retracted, and forgetting some would leave their deletes undetectable for
ever — so the pass is refused, by code, rather than degraded. Aerospike refuses before the pass emits
anything; Cassandra bounds the rows held at every moment, so a pass that inserts before it reaches
the rows it retracts can meet the ceiling on the way. Raise `deletes.max.keys` with the heap to match
(about 150 bytes a row plus the row's encoded size, [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md)
§2.1), split the source into more `partitions`, or set `deletes: ignore`. The same codes at a
restart mean the checkpoint's rows no longer fit a lowered ceiling.

**`PRV-5121` or `PRV-5123`: the remembered rows could not be written or read back.** At a restart it
is almost always the directory the checkpoint names under `deletes.state.dir` being gone — the
directory was on a disk that did not survive, or was cleaned — or its files failing their checksums,
or the stream's schema having changed since the checkpoint. Without those rows the rows the restored
view holds are unknown, and the next pass would retract nothing it should and double everything
else, so the restore is refused rather than guessed at. Restore the directory, or drop the
registration and its checkpoint directory and register again. While running, it is the state
directory not writable or full. An offset written in the other `deletes` mode is refused the same
way, with `PRV-5083` / `PRV-5088`: switching `deletes` on an existing checkpoint needs a fresh start.

**A client closed and the server still holds a subscription.** Fixed, but if you see it: the server
learns nobody is listening from a *cancellation*, not from a dropped transport. The SDK cancels what
it opened when you close it; a hand-rolled client must do the same.

**A snapshot subscription ended with `PRV-6105 FLIGHT_SUBSCRIBER_BEHIND`.** The client stopped
reading, or read more slowly than the query commits, and more than 64 commits waited for it on the
server. A plain subscription drops batches in that position and carries on; a snapshot subscription
(`subscribeFromSnapshot`, `subscribe(..., snapshot=True)`, `pravaha subscribe --snapshot`) promises
every commit after its snapshot, so the server ends the stream instead of writing past a commit the
client would never see. Nothing is lost for good: subscribe again and the new stream starts from a
fresh snapshot. If it keeps happening, the consumer is doing too much in its callback — hand the
batch to a queue of your own and return. The status is `RESOURCE_EXHAUSTED`, which retrying
clients already treat as retryable.

**A snapshot subscription is refused with `PRV-6102` "this is not a subscription ticket".** The
server predates snapshot subscriptions (SUB-1). Upgrade it, or use a plain subscription, which is
gapful: see "Is the subscription attached?" above.

---

## "The node refused to start" — a configuration value that cannot mean what it says

Each of these used to start a node. That is the point of the section: every row below was a
deployment that came up, passed every liveness and readiness probe, and was wrong — either
silently, or once per client for the rest of its life. One bad value should be one startup failure,
which is what these now are.

| | |
|---|---|
| `PRV-1015` two keys that must agree | One stream with two schemas: `pravaha.streams.<n>.schema` is what a query is planned against and `pravaha.sources.<n>.options.schema` is what the plugin decodes rows with, so a divergence plans one shape and reads another. Each value is well-formed on its own, which is why no single-key check saw it. Write the schema once, under `pravaha.streams`, and leave the binding's option out (CFG-8) |
| `PRV-1013` a stream's event-time settings | `pravaha.streams.<n>.event-time` naming a column the schema does not carry or one that is not a `TIMESTAMP`; a negative `.out-of-orderness` or `.allowed-lateness`; either of those declared with no event time to be about. The message names the stream, the key and the field spelling `POST /api/v1/streams` uses (TIME-9) |
| `PRV-1014` a schema version already registered | A stream declared twice at the same version. Versions are immutable, because a query already planned against that shape would silently start reading a different one. Register a new version instead of replacing one |
| `PRV-1029` the help-page base is not a URL | `pravaha.docs.base-url` is set to something that is not an absolute `http` or `https` URL. The engine appends a code to it to build the help link every failure carries, so a value like `docs.example.test/errors/` or `/errors/` would produce a link nobody can follow. Write a base that resolves — `http://localhost:17070/help/codes/` points at the console's own help — or leave the key unset, which is supported and means the engine emits no URL at all (DOCX-21) |
| `PRV-1027` a key reached nothing | A key under `pravaha.streams`, `pravaha.sources`, `pravaha.lookups` or `pravaha.sinks` is in the file and is not in the map the server bound. Spring canonicalises a map key before binding it and **discards one it cannot** — a trailing space, a non-ASCII letter — with no message at any level, so the stream was in the file, absent from the catalog, and the first query against it said "Object not found. Known streams: [...]". Quote the key in brackets to bind it verbatim: `"[txnü]": {schema: "..."}`. Or rename it to letters, digits and hyphens (CFG-3) |
| `PRV-1012` a reference chain too deep to walk | `${a}` referring to `${b}` referring to `${c}`, nested past 256 levels. A backstop against the stack, not a statement about configuration. **It used to be `PRV-1011` at 32 levels**, so a genuine 34-deep chain with no loop in it was refused as a circular reference that did not exist and the operator went looking for one (E-8). A cycle is still `PRV-1011` and still names the keys in it |
| `PRV-1023` a duration with no unit | Spring reads a bare number on a duration key as **milliseconds**. `pravaha.checkpoint.interval: 2`, written meaning two seconds, produced 6,409 checkpoints in twenty seconds with nothing in the log naming the interval in force. Write the unit — `2s`, `500ms`, `1m` — or ISO-8601, `PT2S`. The engine's own duration parser has always refused a bare number for this reason; this is the same rule on the Spring side (CFG-15) |
| `PRV-1026` a bound this value is outside | `pravaha.checkpoint.keep` below 1, or a non-positive `interval` or `timeout`. `keep: 0` used to start a healthy node that then refused **every** registration with "at least one checkpoint must be kept" — once per client, because the bound lived in the checkpointer's constructor and that runs per registration (CFG-16) |
| `PRV-4093` the checkpoint directory is unusable | `pravaha.checkpoint.directory` names something that exists and is not a directory, a directory this process cannot write to, or a path whose parent does not exist. Pointing it at a CSV file used to log `checkpointing registered queries under .../txnA.csv` and then fail every registration (CFG-7) |
| `PRV-8006` the registry journal is unwritable | `pravaha.registry.journal` is a directory, sits in a directory that does not exist, or sits in one this process cannot write to. **The middle one is the commonest typo and used to be invisible**: the directory was created on the first append, so the node journalled perfectly to somewhere nobody meant while the real journal stayed empty. Create the directory, or correct the path (CFG-7) |
| `PRV-3010` the Flight endpoint cannot be bound | `pravaha.flight.port` outside 0–65535 — which used to fail inside gRPC's own argument check, naming neither the key nor a code — or `pravaha.flight.host` written as an abbreviated IPv4 address. `127` is legal input to `InetAddress` and means `0.0.0.127`; write the address in full (CFG-2) |
| `PRV-7004` a security value that is not one | `pravaha.security.policy`, `.administer` (`ownership` or `legacy-read`), `.audit`, or a credential under `pravaha.security.tokens` with no `id`. **The id is required** because the map key is the bearer credential, and the id is written to the audit trail and durably into the registry journal as a query's owner (CFG-11). `pravaha.security.authentication` is refused the same way, and all four now fail while the properties object is built rather than four `Caused by:` levels under a Tomcat startup failure (CFG-21) |
| A credential that authenticates nobody | **One spelling cannot be refused, so the node states a count instead.** `pravaha.security.tokens.x: {}` — an empty mapping — contributes no property at all, so Spring's binder never sees the key and nothing in the process knows it was written; the credential is in the file and the server accepts it from nobody. The node therefore logs, at startup, `N credentials configured under pravaha.security.tokens`: if your file declares more than `N`, an entry is written as an empty mapping, and `x: {id: x}` is the spelling that binds. The two neighbouring mistakes *are* refused — `x:` with a null value fails the bind naming the key, and `x: {id: ""}` is the `PRV-7004` above (CFG-10) || `-Dpravaha.memory` names nothing | An off-heap implementation that does not exist is refused rather than ignored. The values are `agrona`, `foreign` and `bytebuffer`, or leave it unset. An implementation that exists and is **unavailable** on this JDK still falls through silently — `-Dpravaha.ffm=true` on Java 21 is a launcher that will start working on an upgrade, not a mistake. The node logs which one it chose (CFG-22) |

Two things in this class are **warnings, not refusals**, because both are legitimate:

- **bound and undeclared** — `pravaha.sources` names a stream `pravaha.streams` does not. A stream
  can also be declared over `POST /api/v1/streams` after the node is up.
- **declared and unbound** — a stream nothing feeds, which is correct for one a client pushes rows
  into. Before these lines existed, `txn` declared and `txns` bound was paired in no log line
  anywhere and presented as a query that ran for ever receiving nothing (CFG-8).

What *is* refused there is one stream with two schemas: `pravaha.streams.<n>.schema` and the
binding's own `schema` option disagreeing. The first is what a query is planned against and the
second is what the plugin decodes rows with, so a divergence is a node that plans one shape and
reads another — and it used to surface at the first registration as a complaint about a column
name, blaming whichever of the two the message happened to be built from.

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
`pravaha-engine validate` plans a 1,000-column projection without complaint, because nothing writes a row
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
([`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §10.1) have five refusals of their own. Each names
the statement's expected shape.

| Code | What happened | What to do |
|---|---|---|
| `PRV-2070` | The text starts as one of the statements — `CREATE CONTINUOUS`, `DROP CONTINUOUS`, `SHOW CONTINUOUS`, `PAUSE`, `RESUME` — and does not have its shape: no `KEYED BY`, a clause given twice, a retention that is not a duration, words after the name. Also: parameters bound to one of these statements, which take none | The message says what was expected, what was found, and the line and column. Compare it with the shape at the end of the message |
| `PRV-2071` | `KEYED BY` names a column the `SELECT` does not produce, or names one twice | Name the column as the `SELECT` list does — by its alias where it has one (`SUM(amount) AS total` is `total`). The message does not list the columns, because it is raised before the registry has decided whether you may read what the query reads |
| `PRV-2072` | A clause from the design's grammar that is not built: `EMIT CHANGES WITH (...)`, or a `SERVE AS VIEW` naming a view other than the query | Drop the `EMIT CHANGES WITH` list — every continuous query emits its changes — and say a retention with `RETAIN FOR` or `WITH (retention = ...)`; give the query the name clients read. Refused rather than ignored: an ignored `'allowed.lateness' = '30s'` drops rows somebody asked to be waited for |
| `PRV-2073` | `RANGE (column)` asks for an ordered index over a column this engine has no total order for: text (needs a collation), `FLOAT` (IEEE 754, and `NaN` is ordered against nothing), `DECIMAL` (`compareTo` disagrees with `equals`, so `1.0` and `1.00` would be one entry and two rows), `BYTES`, `BOOLEAN` | Drop the `RANGE` — the key still works as a key, and point reads and full-key lookups are unaffected — or range-scan a whole-number or temporal column. Refused at registration, against the columns the view will actually have |
| `PRV-2074` | `INDEX (column)` or `WITH (index = ...)` asks for an equality index this engine will not keep: over `FLOAT` (`0.0` and `-0.0` are equal and stored apart; `NaN` equals nothing), `DECIMAL` (`1.0` and `1.00`), `BYTES`, or the view's whole key (already a hash probe). Also a fifth index on one view, which only names sharing a computation can reach | Index a whole-number, temporal, text or `BOOLEAN` column outside the key, or drop the `INDEX` — the read by that column still works, as a scan. Refused at registration, against the columns the view will actually have |
| `PRV-8017` | A `WITH (...)` option this engine does not build, or a value that is not what the option names. A plain `CREATE` takes `retention`, `sink`, `keys`, `index` and `lane`; `CREATE OR REPLACE` takes `backfill`, `backfill.rate.limit`, `cutover`, `rollback.retention` and `lane`. Also raised for `lane = 'dedicated'` on a query that is the same computation as one already running on a shared lane: register it `lane = 'shared'` to share that computation, or move the running one with `CREATE OR REPLACE ... WITH (lane = 'dedicated')`. Also raised for the same setting said twice — `RETAIN FOR` and `retention`, two different sinks, or `INDEX` and `index` | Use the option the message lists, on the statement that takes it. The design's `consistency.default`, `parallelism` and `allowed.lateness` are not built: consistency is chosen by the reader and per read, and a query's parallelism and lateness are the engine's to decide. Refused rather than ignored, because an option nobody reads is a setting you believe is in force |
| `PRV-6211` | One of the statements was sent to the PostgreSQL gateway (SQLSTATE `25006`), which is read-only | Send it over Flight SQL: an SDK's `query()`, `pravaha query --sql`, or the console's workbench |

A reserved word as the query's name is refused by the registry's own name rule, `PRV-8008`, whichever
way it was registered.

## Replacing a query: `CREATE OR REPLACE`, cutover and rollback

A blue/green replacement ([`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §8.1,
[`OPERATIONS.md`](OPERATIONS.md)) refuses seven things by name, and each refusal is the engine
declining to make an answer quietly wrong.

| Code | What happened | What to do |
|---|---|---|
| `PRV-4013` | The backfill read all the history the source has and never reached the position the running version is at. Its positions do not name the record they were taken after, so there is no offset the history and the live stream can meet at | Nothing to retry: the replacement is stopped rather than reading past the seam and delivering the overlap twice. Replace over a source whose offsets are records — a file, a Kafka topic, a Delta table — or drop and re-register |
| `PRV-4014` | A cutover before the new version had caught up, **or** the two versions could not be brought to the same position inside thirty seconds | Wait for `partitions_live` to reach `partitions` (`pravaha replacements`). If it is caught up and the cutover still refuses, the source is busy enough that the two never stop at the same record: try again, or quieten it. Nothing has changed either way |
| `PRV-4016` | A cutover, rollback, throttle or status for a name nothing is replacing | Start one: `pravaha replace`, `CREATE OR REPLACE CONTINUOUS QUERY`, or `POST /api/v1/queries/{name}/replacement`. After a `finish` or a `rollback` there is no replacement left to act on |
| `PRV-4017` | A second replacement of one name; a new version whose plan normalises to the same computation as the old one (a cutover to itself); or a drop while a candidate is still running | Cut over, roll back or abandon the first. For "the same computation", the SQL you are replacing with is the query you already have — point readers at the existing name instead |
| `PRV-4018` | A stream nothing is bound to, or one whose source cannot be replayed or whose positions do not order its records (a table scan reports where its pass began); an option this engine does not build (`backfill.parallelism`, `backfill.window`, `backfill.adaptive`); a rate above the ceiling the replacement was started with; or a `WRITING TO` / `RETAIN` that would change the sink or the retention | The message names which. A rate limit is a ceiling by design; start another replacement to raise it. Moving a sink is a drop and a fresh registration, so that what the old sink holds is a decision rather than a side effect |
| `PRV-4019` | The subscription you were holding ended: the view it followed was replaced at a cutover | Subscribe again. A snapshot subscription then starts from a fresh snapshot of the new version — which is what it needs, and what a diff between two different queries could not give it |
| `PRV-8003` | The query's computation is shared with another name (two registrations of the same question), or it is paused | Change or drop the other names first. A replacement moves one name, and a shared computation cannot tell which name a subscriber arrived through, so some subscribers would go on following the old version under a name that now answers the new one |

**After a restart there is no rollback.** A replacement still backfilling comes back and carries on;
one that had cut over comes back as the new version, and the version it replaced is gone — it was a
running computation, not a durable one. Confirm or roll back before a planned restart.

## The time-travel debugger refuses (`PRV-8011` … `PRV-8016`)

A debug session (ADR-048, [`USER_GUIDE.md`](USER_GUIDE.md#11-the-time-travel-debugger)) forks a query
from one of its checkpoints and steps it with every sink disabled. Six refusals, each naming the
thing rather than the operation.

| Code | What happened | What to do |
|---|---|---|
| `PRV-8011` | There is no checkpoint to fork from. The message says which of the three it is: this node is not checkpointing at all, this query has not taken its first one yet, or the id you asked for has been pruned | Set `pravaha.checkpoint.directory` and wait one `pravaha.checkpoint.interval`; or ask `pravaha debug checkpoints --name <query>` for the ids this node still has. A fork can only start where a checkpoint is |
| `PRV-8012` | A source cannot be rewound to the checkpoint's offsets: nothing is bound to the stream (its rows are pushed in by an embedder), the plugin cannot be read again from a position it handed out, or its positions do not order the records within a partition | The same constraint a backfill has (`PRV-4018`), for the same reason and named the same way. Debug a query over a source whose offsets are records — a file, a Kafka topic, a Delta table, `postgres-cdc` |
| `PRV-8013` | No session answers to that id: it was ended, or it expired after `pravaha.debug.session.ttl` untouched | Fork again. A session holds a second copy of a query's state, so it is not kept indefinitely; `pravaha debug sessions` lists the ones this node has |
| `PRV-8014` | This node already holds `pravaha.debug.sessions.max` sessions | End one — the message lists their ids — or raise the setting. The ceiling is memory rather than policy: each session is a whole extra copy of a query |
| `PRV-8015` | A step, a predicate or a page this session cannot make sense of: an unreadable step verb, a predicate over a column the view does not have, a comparison that is not one of `= != < <= > >=`, a watermark that goes backwards, a page above the ceiling, a session past `pravaha.debug.session.max-rows`, or a fixture name that cannot be a Java class | The message says which and lists the legal values. A predicate is one column against one value on purpose (ADR-048); anything more is a query, so step to the row and read the view |
| `PRV-8016` | The query this session forked from has been dropped, or replaced by a different computation (ADR-046), since the fork | Export what the session has if you still want it, then end it. Stepping on would report the old version's behaviour under a name that now answers a new one |

Each refusal answers the same status on both transports (DBG-2): `PRV-8013` and `PRV-8016` are
Flight `NOT_FOUND` and HTTP `404`, `PRV-8014` is Flight `RESOURCE_EXHAUSTED` and HTTP `429` — a node
at its ceiling, worth retrying later — and the other three are Flight `INVALID_ARGUMENT` and HTTP
`400`, because the caller can correct them.

**A fork cannot make a query worse.** Its view is in no catalogue, no sink is attached to it, and
its lanes are its own — so a refusal here means the session did not start, never that the live
query is in a strange state. If a session is open and the query looks wrong, the session is not the
cause; end it and read `pravaha queries`.

## A tenant's quota refuses a registration (`PRV-8020` … `PRV-8023`)

Quotas are set in `pravaha.tenancy` and checked only at registration (ADR-050,
[`OPERATIONS.md`](OPERATIONS.md#tenant-quotas)). A refusal never means that a running query
changed.

| Code | What happened | What to do |
|---|---|---|
| `PRV-8020` | The tenant already holds `max-queries` names. A second name on a computation the tenant already runs still counts, because a name is what the tenant holds | Drop one of the tenant's queries, or raise `max-queries` for the tenant. `GET /api/v1/tenants` lists what each tenant holds |
| `PRV-8021` | The tenant's views already hold `max-state-keys` keys, and this registration would start another computation. A name attached to a computation the tenant already runs adds no state and is not refused by this | Drop or narrow one of the tenant's queries (for example with a shorter retention), or raise `max-state-keys`. The quota counts view keys, not bytes |
| `PRV-8022` | A replacement from a principal in a different tenant from the one that registered the name | Replace the query from within the tenant that owns it. A new version is charged to that tenant and shared only within it |
| `PRV-8023` | A negative limit, or a `pravaha.tenancy.tenants` entry with no tenant name. The node does not start | Leave a limit unset for no limit, or set `0` to allow none |

On restart, a journal replayed under a lowered quota reports each entry beyond the limit in the
recovery log with `PRV-8020` or `PRV-8021`. The journal entry stays live and comes back once the
limit is raised.

## A query over another query is refused (`PRV-2075`, `PRV-8024` … `PRV-8027`)

A continuous query whose `FROM` names a registered query follows that query's answer
([ADR-056](adr/056-queries-on-queries.md), [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §3.1). What
cannot be kept exact over an input that retracts is refused rather than approximated.

| Code | What happened | What to do |
|---|---|---|
| `PRV-2075` | The query over a view uses a window, a join (with a stream, a lookup table or another view), top-N, `MIN`, `MAX` or `COUNT(DISTINCT)`, or groups by a column the grouped aggregate cannot hold | Filter, project, and aggregate with `COUNT`, `SUM` and `AVG` over the view; put the window, join or extreme in the upstream query, or read the view with a plain `SELECT` |
| `PRV-8024` | `DROP` of a query that other queries read. The message names them | Drop the queries that read it first. There is no cascade: it would drop queries somebody else registered |
| `PRV-8025` | A `CREATE OR REPLACE` whose new version would read, through other queries, its own answer. The message names the loop | Read the stream, not a query that depends on the one being replaced |
| `PRV-8026` | Replacing a query that reads a view or that others read; registering over a name that is being replaced; `RETAIN FOR` on a query over a view; a column type a row cannot carry; or a restored checkpoint that holds the downstream's view but not what it had consumed | Drop and register instead of replacing; wait for the replacement to finish; retain in the upstream (`RETAIN FOREVER` here); project the column away upstream. A restore refused for its missing record is exactly the case where feeding the whole answer again would double-count: drop and register the downstream |
| `PRV-8027` | The chain would be more than eight queries deep over its streams | Fold some of the steps into one query |

A downstream whose upstream fails stops following it with `PRV-8004`, naming the upstream, and keeps
answering at the frontier it reached: its feed is reported stopped, as any source's is.

## An alert is refused, silent, or not delivered (`PRV-8040` … `PRV-8047`)

An alert follows a view's answer and notifies when a key's row enters it and when it leaves
([ADR-057](adr/057-alerts.md), [`CONTINUOUS_QUERIES.md`](CONTINUOUS_QUERIES.md) §3.2).

| Code | What happened | What to do |
|---|---|---|
| `PRV-8040` | No alert by that name that you may see — the same answer for one that does not exist and one you hold no `SELECT` on | `SHOW ALERTS` or `pravaha alerts ls` lists what you may see |
| `PRV-8041` | `CREATE ALERT` of a name an alert of your tenant already has, or a continuous query of your tenant has, or (under the catalogue) another object has in your `default` namespace. Another tenant's names are free to you (ADR-060) | Choose another name, or add `IF NOT EXISTS` |
| `PRV-8042` | The view is not registered or not yours to see; a `WHERE` or `include` column is not the view's; a literal of the wrong type; an unknown option; a severity that is not `info`, `warning` or `critical`; a duration that is not one | Correct the statement; the message names the part. A condition richer than `column op literal AND ...` belongs in a query over the view |
| `PRV-8043` | `NOTIFY` names a channel the node does not bind | Bind it under `pravaha.notifiers.<name>` (OPERATIONS, "Alerts and notifier channels"); `pravaha alerts channels` lists them |
| `PRV-8044` | `alerts.journal` cannot be read or appended to. A decision is not acted on unless it is journalled | Check the file (beside the registry journal, or `pravaha.alerts.journal`), its permissions and the disk |
| `PRV-8045` | A channel did not accept a notification after its retries. Recorded on the alert — its delivery error, and a `FAILED` line in its history — never thrown to a caller | Fix the receiver. The notification is sent again every `pravaha.alerts.redeliver-after`, under the same idempotency key, until it is accepted |
| `PRV-8046` | The node refused to start: a `pravaha.notifiers.<name>` binding names a plugin not on the classpath, has no URL, writes a secret into the configuration (`secret`, `token`, `password`), or names an environment variable that is not set or a file that cannot be read | Correct the binding; a secret is given by `secret-env` or `secret-file`, never inline |
| `PRV-8047` | An alert statement or `/api/v1/alerts` call where no alert service runs: an embedded engine, or `pravaha.alerts.enabled: false` | Run it against a node with its alert service on |

**An alert that says nothing.** Look at `pravaha alerts show <name>`: `following` other than
`FOLLOWING` means its view is not registered (it waits for it); `PAUSED` or `SNOOZED` hold every
notification back until resumed; a key in `PENDING` is waiting out `fire_after`; one in `CLEARING`
is waiting out `clear_after`; `owed` shows what the channels have not been told yet — held back by
`dedupe`, or not delivered (`delivery`). **An alert that said something twice** is at-least-once
delivery doing its job after a retry or a restart: both carry the same `Idempotency-Key`.

## `PRV-1052` — an HTTP request that reached no endpoint

Every non-2xx response on `/api/v1/**` is an `ApiError` — `code`, `message`, `helpUrl`,
`timestamp`, `path` — and nothing else. A client that has to parse two error shapes will handle one
of them badly, and it will be the one that occurs rarely.

`helpUrl` is **always present and may be empty**: it is `pravaha.docs.base-url` plus the code, and
an empty string means this deployment publishes no help pages. A client should treat the empty
string as "no link" rather than expecting the field to disappear; the field is part of the
contract, the value is the operator's (DOCX-21).

`PRV-1052` is what a request that reached no handler answers with: a method the path does not
support (405), a body in a media type the endpoint does not read (415), an unmapped path (404). The
status is what distinguishes them and the client already has it; the code says the body is an
`ApiError`. Before it existed those three fell through to Spring Boot's own error controller and
came back as a *third* shape — `{"timestamp":…,"status":405,"error":"Method Not Allowed","path":…}`
— with no code, no message and no help URL (CFG-20). The published OpenAPI document now carries the
`ApiError` schema with its five fields and a `default` response on every operation, so a generated
client models the error rather than an empty object.

## Every code

| Code | Name | Range |
|---|---|---|
| `PRV-1001` | CONFIG_FILE_UNREADABLE | config |
| `PRV-1050` | API_MISSING_FIELD | api |
| `PRV-1051` | API_INVALID_PARAMETER | api |
| `PRV-1053` | API_MALFORMED_TEXT | api |
| `PRV-1052` | API_UNHANDLED_REQUEST | api |
| `PRV-1002` | CONFIG_FILE_MALFORMED | config |
| `PRV-1010` | CONFIG_UNRESOLVED_REFERENCE | config |
| `PRV-1011` | CONFIG_CIRCULAR_REFERENCE | config |
| `PRV-1012` | CONFIG_REFERENCE_TOO_DEEP | config |
| `PRV-1015` | CONFIG_CONTRADICTION | config |
| `PRV-1013` | CONFIG_STREAM_EVENT_TIME_INVALID | config |
| `PRV-1014` | CONFIG_STREAM_VERSION_IN_USE | config |
| `PRV-1020` | CONFIG_MISSING_REQUIRED | config |
| `PRV-1021` | CONFIG_NOT_A_NUMBER | config |
| `PRV-1022` | CONFIG_NOT_A_BOOLEAN | config |
| `PRV-1023` | CONFIG_NOT_A_DURATION | config |
| `PRV-1024` | CONFIG_NOT_A_DATA_SIZE | config |
| `PRV-1025` | CONFIG_NOT_AN_ENUM | config |
| `PRV-1026` | CONFIG_OUT_OF_RANGE | config |
| `PRV-1027` | CONFIG_KEY_UNREACHABLE | config |
| `PRV-1028` | CONFIG_SCHEMA_MALFORMED | config |
| `PRV-1029` | CONFIG_DOCS_BASE_URL_INVALID | config |
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
| `PRV-2042` | SQL_SOURCE_REPEATS_ROWS | sql |
| `PRV-2050` | SQL_UNBOUNDED_STATE | sql |
| `PRV-2060` | SQL_PARAMETER_NOT_BOUND | sql |
| `PRV-2061` | SQL_PARAMETER_ARITY | sql |
| `PRV-2062` | SQL_PARAMETER_TYPE | sql |
| `PRV-2063` | SQL_PARAMETER_NOT_A_VALUE | sql |
| `PRV-2070` | SQL_STATEMENT_MALFORMED | sql |
| `PRV-2071` | SQL_KEY_COLUMN_UNKNOWN | sql |
| `PRV-2072` | SQL_CLAUSE_NOT_BUILT | sql |
| `PRV-2073` | SQL_RANGE_NOT_ORDERED | sql |
| `PRV-2074` | SQL_INDEX_UNUSABLE | sql |
| `PRV-2075` | SQL_VIEW_INPUT_UNSUPPORTED | sql |
| `PRV-3001` | RUNTIME_ARENA_EXHAUSTED | runtime |
| `PRV-3002` | RUNTIME_BACKPRESSURED | runtime |
| `PRV-3010` | RUNTIME_LANE_FAILED | runtime |
| `PRV-3020` | RUNTIME_UNSUPPORTED_AGGREGATE | runtime |
| `PRV-3022` | RUNTIME_WINDOW_SPAN_IMPLAUSIBLE | runtime |
| `PRV-3021` | RUNTIME_UNSUPPORTED_JOIN | runtime |
| `PRV-3024` | RUNTIME_RETRACTED_UNHELD_ROW | runtime |
| `PRV-3025` | RUNTIME_AGGREGATE_OVERFLOW | runtime |
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
| `PRV-4093` | STATE_CHECKPOINT_DIRECTORY_UNUSABLE | state |
| `PRV-4010` | BACKFILL_BUFFER_FULL | state/serving |
| `PRV-4011` | BACKFILL_MISSING_VERSION | state/serving |
| `PRV-4012` | BACKFILL_UNSUPPORTED_KEY | state/serving |
| `PRV-4013` | BACKFILL_SPLICE_MISSED | state/serving |
| `PRV-4014` | BACKFILL_NOT_CAUGHT_UP | state/serving |
| `PRV-4015` | BACKFILL_SEAM_WENT_BACKWARDS | state/serving |
| `PRV-4016` | BACKFILL_NO_REPLACEMENT | state/serving |
| `PRV-4017` | BACKFILL_REPLACEMENT_IN_PROGRESS | state/serving |
| `PRV-4018` | BACKFILL_SOURCE_UNSUPPORTED | state/serving |
| `PRV-4019` | BACKFILL_VIEW_REPLACED | state/serving |
| `PRV-4020` | SERVING_NO_HISTORY | state/serving |
| `PRV-4021` | SERVING_READ_TIMED_OUT | state/serving |
| `PRV-4022` | SERVING_VIEW_TOO_LARGE | state/serving |
| `PRV-4023` | SERVING_NO_SUCH_VIEW | state/serving |
| `PRV-4090` | STATE_DLQ_UNUSABLE | state |
| `PRV-4091` | STATE_DLQ_NO_SUCH_LETTER | state |
| `PRV-4092` | STATE_DLQ_REPLAY_REFUSED | state |
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
| `PRV-5056` | DELTA_SINK_BAD_CONFIGURATION | plugins |
| `PRV-5057` | DELTA_SINK_TABLE_MISMATCH | plugins |
| `PRV-5058` | DELTA_SINK_WRITE_FAILED | plugins |
| `PRV-5059` | DELTA_SINK_COMMIT_CONFLICT | plugins |
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
| `PRV-5100` | KAFKA_BAD_CONFIGURATION | plugins |
| `PRV-5101` | KAFKA_CONNECT_FAILED | plugins |
| `PRV-5102` | KAFKA_WRITE_FAILED | plugins |
| `PRV-5103` | KAFKA_STAGING_UNUSABLE | plugins |
| `PRV-5104` | KAFKA_MALFORMED_OFFSET | plugins |
| `PRV-5105` | KAFKA_UNDECODABLE_RECORD | plugins |
| `PRV-5106` | KAFKA_RESUME_POINT_GONE | plugins |
| `PRV-5107` | KAFKA_READ_FAILED | plugins |
| `PRV-5108` | KAFKA_SCHEMA_UNMAPPABLE | plugins |
| `PRV-5109` | KAFKA_REGISTRY_UNAVAILABLE | plugins |
| `PRV-5110` | PGCDC_BAD_CONFIGURATION | plugins |
| `PRV-5111` | PGCDC_CONNECT_FAILED | plugins |
| `PRV-5112` | PGCDC_NOT_CAPTURABLE | plugins |
| `PRV-5113` | PGCDC_SCHEMA_MISMATCH | plugins |
| `PRV-5114` | PGCDC_MALFORMED_OFFSET | plugins |
| `PRV-5115` | PGCDC_RESUME_POINT_RELEASED | plugins |
| `PRV-5116` | PGCDC_UNREPRESENTABLE_CHANGE | plugins |
| `PRV-5117` | PGCDC_STREAM_FAILED | plugins |
| `PRV-5118` | PGCDC_SNAPSHOT_FAILED | plugins |
| `PRV-5120` | AEROSPIKE_DELETE_STATE_FULL | plugins |
| `PRV-5121` | AEROSPIKE_DELETE_STATE_FAILED | plugins |
| `PRV-5122` | CASSANDRA_DELETE_STATE_FULL | plugins |
| `PRV-5123` | CASSANDRA_DELETE_STATE_FAILED | plugins |
| `PRV-5140` | ICEBERG_SINK_BAD_CONFIGURATION | plugins |
| `PRV-5141` | ICEBERG_SINK_TABLE_MISMATCH | plugins |
| `PRV-5142` | ICEBERG_SINK_WRITE_FAILED | plugins |
| `PRV-5143` | ICEBERG_SINK_BUFFER_FULL | plugins |
| `PRV-5150` | MYCDC_BAD_CONFIGURATION | plugins |
| `PRV-5151` | MYCDC_CONNECT_FAILED | plugins |
| `PRV-5152` | MYCDC_NOT_CAPTURABLE | plugins |
| `PRV-5153` | MYCDC_SCHEMA_MISMATCH | plugins |
| `PRV-5154` | MYCDC_MALFORMED_OFFSET | plugins |
| `PRV-5155` | MYCDC_RESUME_POINT_PURGED | plugins |
| `PRV-5156` | MYCDC_UNREPRESENTABLE_CHANGE | plugins |
| `PRV-5157` | MYCDC_STREAM_FAILED | plugins |
| `PRV-5158` | MYCDC_RESUME_POINT_AHEAD | plugins |
| `PRV-6100` | FLIGHT_UNSUPPORTED_TYPE | gateway |
| `PRV-6101` | FLIGHT_UNSUPPORTED_REQUEST | gateway |
| `PRV-6102` | FLIGHT_BAD_HANDLE | gateway |
| `PRV-6103` | FLIGHT_PARAMETERS_TOO_LARGE | gateway |
| `PRV-6104` | FLIGHT_TLS_UNREADABLE | gateway |
| `PRV-6105` | FLIGHT_SUBSCRIBER_BEHIND | gateway |
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
| `PRV-7005` | SECURITY_SINK_WRITE_NOT_FILTERABLE | security |
| `PRV-7006` | SECURITY_MASKED_COLUMN_USE | security |
| `PRV-7007` | SECURITY_NARROWING_CHANGED | security |
| `PRV-7010` | IDENTITY_CREDENTIALS_REFUSED | security |
| `PRV-7011` | IDENTITY_LOCKED | security |
| `PRV-7012` | IDENTITY_PASSWORD_POLICY | security |
| `PRV-7013` | IDENTITY_KEY_NOT_VALID | security |
| `PRV-7014` | IDENTITY_KEY_WRONG_ENVIRONMENT | security |
| `PRV-7015` | IDENTITY_WOULD_WIDEN | security |
| `PRV-7016` | IDENTITY_SESSION_EXPIRED | security |
| `PRV-7017` | IDENTITY_RESET_TOKEN_INVALID | security |
| `PRV-7018` | IDENTITY_MUST_CHANGE_PASSWORD | security |
| `PRV-7019` | IDENTITY_DEFAULT_ADMIN_PASSWORD | security |
| `PRV-7020` | IDENTITY_INVALID_REQUEST | security |
| `PRV-7021` | IDENTITY_NOT_FOUND | security |
| `PRV-7030` | CATALOG_DISABLED | security |
| `PRV-7031` | CATALOG_NO_SUCH_OBJECT | security |
| `PRV-7032` | CATALOG_PRIVILEGE_NOT_APPLICABLE | security |
| `PRV-7033` | CATALOG_MANAGE_REQUIRED | security |
| `PRV-7034` | CATALOG_TWO_AUTHORITIES | security |
| `PRV-7035` | CATALOG_JOURNAL_FAILED | security |
| `PRV-7036` | CATALOG_OBJECT_EXISTS | security |
| `PRV-7037` | CATALOG_INVALID_REQUEST | security |
| `PRV-7038` | CATALOG_POLICY_INVALID | security |
| `PRV-7039` | CATALOG_POLICY_CLAIM_MISSING | security |
| `PRV-7040` | CATALOG_POLICY_CONFLICT | security |
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
| `PRV-8011` | DEBUG_NO_CHECKPOINT | registry (debugger) |
| `PRV-8012` | DEBUG_SOURCE_NOT_REPLAYABLE | registry (debugger) |
| `PRV-8013` | DEBUG_NO_SUCH_SESSION | registry (debugger) |
| `PRV-8014` | DEBUG_TOO_MANY_SESSIONS | registry (debugger) |
| `PRV-8015` | DEBUG_BAD_STEP | registry (debugger) |
| `PRV-8016` | DEBUG_QUERY_GONE | registry (debugger) |
| `PRV-8017` | REGISTRY_OPTION_UNKNOWN | registry |
| `PRV-8018` | REGISTRY_QUERY_DROPPED | registry |
| `PRV-8019` | REGISTRY_NODE_STOPPING | registry |
| `PRV-8020` | REGISTRY_TENANT_QUERY_QUOTA | registry (tenancy) |
| `PRV-8021` | REGISTRY_TENANT_STATE_QUOTA | registry (tenancy) |
| `PRV-8022` | REGISTRY_TENANT_MISMATCH | registry (tenancy) |
| `PRV-8023` | REGISTRY_TENANCY_MISCONFIGURED | registry (tenancy) |
| `PRV-8024` | REGISTRY_QUERY_HAS_DEPENDANTS | registry (queries on queries) |
| `PRV-8025` | REGISTRY_QUERY_CYCLE | registry (queries on queries) |
| `PRV-8026` | REGISTRY_CHAIN_UNSUPPORTED | registry (queries on queries) |
| `PRV-8027` | REGISTRY_CHAIN_TOO_DEEP | registry (queries on queries) |
| `PRV-8028` | REGISTRY_SOURCE_HELD | registry (bindings) |
| `PRV-8040` | ALERT_NO_SUCH_ALERT | registry (alerts) |
| `PRV-8041` | ALERT_EXISTS | registry (alerts) |
| `PRV-8042` | ALERT_DEFINITION_INVALID | registry (alerts) |
| `PRV-8043` | ALERT_NO_SUCH_CHANNEL | registry (alerts) |
| `PRV-8044` | ALERT_JOURNAL_FAILED | registry (alerts) |
| `PRV-8045` | ALERT_DELIVERY_FAILED | registry (alerts) |
| `PRV-8046` | ALERT_NOTIFIER_MISCONFIGURED | registry (alerts) |
| `PRV-8047` | ALERT_NOT_SERVED | registry (alerts) |
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
