---
title: Registry codes (PRV-8xxx)
slug: errors-registry
category: errors
order: 90
icon: journal-x
summary: "PRV-8001 to PRV-8104: a query's name and lifecycle, its journal and replay, sinks that fail or do not fit, unknown options, the debugger's six refusals, tenant quotas, queries over queries, alerts, and the embedded engine's four."
badge: PRV-8XXX
audience: Analysts, operators, developers
keywords: [registry, dependants, cycle, chain, queries on queries, name in use, reserved word, no such query, drop, pause, resume, failed, journal, replay, sink detached, sink shape, keyed by, embedded, push, backpressure, row rejected, debug, debugger, debug session, fork, step, fixture, checkpoint, with, option, unknown option, tenant, tenancy, quota, max-queries, max-state-keys, 409]
guide: continuous-queries#8-the-life-of-a-query
related: [query-lifecycle, create-continuous-query, sinks-overview, time-travel-debugger, embedded-engine, errors-overview]
listed_on: errors-overview
---

The registry is where a continuous query lives once it is registered: its name, its state
(`RUNNING`, `PAUSED`, `FAILED`, `DROPPED`), its journal entry, and the sinks it writes to. The 8xxx
range is about that life. The **810n** block belongs to the embedded engine, which hosts a registry
inside an application's own process and adds the ways an application can push rows into it.

| Code | Name | In one line |
|---|---|---|
| PRV-8001 | REGISTRY_NAME_IN_USE | The name is already registered |
| PRV-8002 | REGISTRY_NO_SUCH_QUERY | No query by that name (that you may see) |
| PRV-8003 | REGISTRY_ILLEGAL_TRANSITION | Not possible from the query's current state |
| PRV-8004 | REGISTRY_QUERY_FAILED | The query failed; its view refuses reads |
| PRV-8005 | REGISTRY_JOURNAL_UNREADABLE | A journal record cannot be read |
| PRV-8006 | REGISTRY_JOURNAL_UNWRITABLE | The journal cannot be written; the registration is refused |
| PRV-8007 | REGISTRY_REPLAY_UNAUTHORIZED | A journalled registration's owner may no longer have it |
| PRV-8008 | REGISTRY_NAME_UNUSABLE | The name cannot be a view name at all |
| PRV-8009 | REGISTRY_SINK_WRITE_FAILED | A sink refused a batch and was detached |
| PRV-8010 | REGISTRY_SINK_SHAPE_MISMATCH | The query's output or key does not fit the sink |
| PRV-8011 | DEBUG_NO_CHECKPOINT | There is no checkpoint for a debug session to fork from |
| PRV-8012 | DEBUG_SOURCE_NOT_REPLAYABLE | A source cannot be rewound to the checkpoint's offsets |
| PRV-8013 | DEBUG_NO_SUCH_SESSION | No debug session by that id: ended, or expired |
| PRV-8014 | DEBUG_TOO_MANY_SESSIONS | This node already holds as many sessions as it allows |
| PRV-8015 | DEBUG_BAD_STEP | A step, a predicate or a page this session cannot make sense of |
| PRV-8016 | DEBUG_QUERY_GONE | The query this session forked from has been dropped or replaced |
| PRV-8017 | REGISTRY_OPTION_UNKNOWN | A `WITH (...)` option this engine does not build, or one said twice |
| PRV-8018 | REGISTRY_QUERY_DROPPED | The name a subscription was opened under has been dropped |
| PRV-8019 | REGISTRY_NODE_STOPPING | The node is shutting down; the query is coming back, this stream is not |
| PRV-8020 | REGISTRY_TENANT_QUERY_QUOTA | The tenant already holds as many query names as its quota allows |
| PRV-8021 | REGISTRY_TENANT_STATE_QUOTA | The tenant's views already hold as many keys as its state quota allows |
| PRV-8022 | REGISTRY_TENANT_MISMATCH | A replacement from a principal of another tenant than the name's |
| PRV-8023 | REGISTRY_TENANCY_MISCONFIGURED | `pravaha.tenancy` sets a negative limit or a blank tenant name |
| PRV-8024 | REGISTRY_QUERY_HAS_DEPENDANTS | A query other queries read cannot be dropped until they are |
| PRV-8025 | REGISTRY_QUERY_CYCLE | A new version would read, through other queries, its own answer |
| PRV-8026 | REGISTRY_CHAIN_UNSUPPORTED | Something about a query over a query that cannot be made exact |
| PRV-8027 | REGISTRY_CHAIN_TOO_DEEP | A chain of queries over queries deeper than eight |
| PRV-8040 | ALERT_NO_SUCH_ALERT | No alert by that name that you may see |
| PRV-8041 | ALERT_EXISTS | An alert, a query or a catalogue object already has that name |
| PRV-8042 | ALERT_DEFINITION_INVALID | An alert's view, condition, option or duration cannot be kept |
| PRV-8043 | ALERT_NO_SUCH_CHANNEL | `NOTIFY` names a channel no `pravaha.notifiers.<name>` binds |
| PRV-8044 | ALERT_JOURNAL_FAILED | The alert journal cannot be read or written |
| PRV-8045 | ALERT_DELIVERY_FAILED | A channel did not accept a notification; it is sent again |
| PRV-8046 | ALERT_NOTIFIER_MISCONFIGURED | A notifier binding the node cannot start with |
| PRV-8047 | ALERT_NOT_SERVED | An alert statement where no alert service runs |
| PRV-8101 | EMBEDDED_UNKNOWN_STREAM | A row pushed to an undeclared stream |
| PRV-8102 | EMBEDDED_ROW_REJECTED | A pushed row does not fit its stream |
| PRV-8103 | EMBEDDED_BACKPRESSURE | A push waited too long for room |
| PRV-8104 | EMBEDDED_MISCONFIGURED | The embedded configuration says something impossible |

Over REST the registry codes are `400` — the request was the caller's to fix — except PRV-8002 and
PRV-8040, which are `404`, PRV-8041 and PRV-8047, which are `409`, and PRV-8044 and PRV-8046, which
are the node's (`500`).

## Names

### PRV-8001 — name in use

The name is already registered. Drop it first, register under another name, or write
`CREATE OR REPLACE`, which starts a blue/green replacement: **silently replacing a running query
would take its answers away from whoever is reading them**, so the new version backfills beside the
running one and takes the name only at a cutover.

Registering the *same computation* under a second name is not this — it is sharing: the second name
points at the running computation and costs nothing (see [Sharing](/help/topics/sharing)).

View names are unique on the node, across tenants, so the name may be taken in a tenant other than
yours; the refusal is the same either way and does not say whose it is. Dropping or replacing it
needs its owner, a grant or an admin. Per-tenant names are decided (ADR-060) and not yet built.

### PRV-8008 — name unusable

The name cannot be used for a view, whatever else is true: a name is written in a `FROM` clause, so it
must be a plain identifier (a letter or underscore, then letters, digits or underscores), and it must
not be a SQL reserved word. Distinct from PRV-8001 because "already taken" and "cannot be a name at
all" call for different actions.

```text
CREATE CONTINUOUS QUERY select KEYED BY (txn_id) AS SELECT txn_id, amount FROM txn
PRV-8008  'select' cannot appear in a FROM clause, so no query could read the view: ... The usual
cause is that the name is a reserved word in SQL.
```

Pick a name like `big_txn`, `spend_by_user`, `hourly_spend`.

### PRV-8002 — no such query

No registered query answers to that name — **that you may see**. A name hidden from you by the policy
answers exactly as one that does not exist, so a refusal cannot be used to learn what is registered.
`GET /api/v1/queries`, `pravaha queries` and `SHOW CONTINUOUS QUERIES` list the ones you may see.

## Lifecycle

```text
          register
             |
             v
         RUNNING --pause--> PAUSED --resume--> RUNNING
             |                 |
             | lane throws     |
             v                 v
          FAILED           (drop) --> DROPPED
```

```sql
PAUSE CONTINUOUS QUERY hourly_spend;
RESUME CONTINUOUS QUERY hourly_spend;
DROP CONTINUOUS QUERY hourly_spend;
```

### PRV-8003 — illegal transition

The operation is not legal from the state the query is in — resuming one that is running, pausing
one that has failed, subscribing to one that is `FAILED` or `DROPPED` ("cannot subscribe to
'hourly_spend': it is FAILED"). `FAILED` and `DROPPED` are terminal: a failed query is dropped and
registered again, not resumed.

### PRV-8018 / PRV-8019 — how a subscription ends

Three different events used to be one signal on the wire: an administrative drop, a node shutting
down, and the client's own `close()` all arrived as a clean completion, and only the last of the
three is an ending a client should accept (STRM-12). They are now told apart, and the Flight status
says which action to take before you read anything.

| Code | Status | What happened | What to do |
|---|---|---|---|
| PRV-8018 | `NOT_FOUND` | the name you subscribed to was dropped | stop. The name does not exist any more; what you received is complete up to the drop |
| PRV-8019 | `UNAVAILABLE` | the node is shutting down | reconnect. The query is journalled and comes back `RUNNING`; read the view to catch up on what happened in between |

PRV-8018 also ends a subscription on a name that was **sharing** a computation (STRM-14). Two
registrations over the same question are one computation with two names; dropping one leaves the
other running, and a subscriber on the dropped name used to go on receiving rows under a name a
read of the view refused as nonexistent. A subscriber on the surviving name is unaffected.

In process, the same ending closes the `Subscription` and removes it from the sink, so
`subscriberCount()` returns to zero after a drop — it never used to, which made the number an
operator reads as "nobody is watching this" permanently wrong.

### PRV-8004 — query failed

The query stopped while running — its lane died; its recorded cause says how (often a PRV-3xxx or
PRV-4001) — and **its view refuses reads** rather than serving a snapshot frozen at the failure (E-13).
The rows it holds were correct as of the failure; what the engine will not do is hand them over as
though they were current.

The same number is declared by the serving layer, so a read sees this code whichever door it came
through. **Do:** read the cause on the query's page (or `GET /api/v1/queries/{name}`), fix it, drop
the query and register it again.

## The journal

With `pravaha.registry.journal` set, every registration is written down and replayed at startup, so a
restart keeps the questions. (Checkpoints keep the answers — see
[Checkpoints and recovery](/help/topics/checkpoints-recovery).)

### PRV-8005 — journal unreadable

A record in the journal cannot be decoded, or is of a kind this version does not understand. The node
**refuses rather than skipping it**: a skipped registration is a view a client expects to find and
will not, failing at subscribe time with "no such view" — a long way from the unreadable byte that
caused it. Earlier records are fine; the message names the record and the file. Restore the journal
from backup, or remove the damaged record deliberately.

### PRV-8006 — journal unwritable

The journal cannot be appended to (or compacted). The registration is **refused**: acknowledging one
that will not survive a restart tells the client something that is not true, and nothing will correct
it later. Check the disk and the file's permissions. (The journal holds query text and bound parameter
values, so permission it like data.)

### PRV-8007 — replay unauthorized

At startup, a journalled registration is replayed **as its principal** — and that principal may no
longer be allowed to register it, or to read a stream it reads. A registration is not a standing
permission: replaying it blindly would let someone keep an entitlement after it was revoked, simply by
having registered before it was. The registration is not restored; the principal registers it again
if they still may.

The owner is looked up in the identity store first and the static token table second (ADR-052,
RECOVERYOWNER-1), so a query registered by a signed-in user comes back as that user. The code is also
given for an owner neither of them knows. A **disabled** user is still known: their queries keep
running after a restart, as they did before it, and the node logs a warning naming the owner — drop
the queries to stop them.

## Sinks

A query registered `WRITING TO` a sink (or with `--sink`) writes every commit of its view there too.
See [How a query writes to a sink](/help/topics/sinks-overview).

### PRV-8010 — sink shape mismatch

Refused **at registration, before the sink is opened**: the sink's declared `schema` does not match the
query's output column for column — order, name and type — or a keyed sink's key is not the query's
`KEYED BY`. A sink reads each row through its own schema, so a mismatch would write every value from
another column's place — plausible nonsense rather than a failure — and a key mismatch would send
retractions to the wrong records.

```yaml
pravaha:
  sinks:
    spend_table:
      plugin: jdbc-sink
      options:
        url: "jdbc:postgresql://pg-1:5432/analytics"
        user: pravaha
        password: "${PG_PASSWORD}"
        table: merchant_spend
        schema: "merchant:STRING,window_start:TIMESTAMP,window_end:TIMESTAMP,spend:INT64"
        key.columns: "merchant,window_end"
```

A query that fits it exactly — the same four columns, in order, keyed the same way:

```sql
CREATE CONTINUOUS QUERY merchant_spend
    KEYED BY (merchant, window_end)
    WRITING TO spend_table
AS
SELECT merchant, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, window_start, window_end;
```

Selecting `spend` before `window_end`, or keying by `merchant` alone, is PRV-8010.

### PRV-8009 — sink write failed

A sink refused a batch — a database error, a full disk, a lost connection — and has been **detached**
from the query. Recorded on the registration and logged, rather than thrown into the query:

```text
PRV-8009  sink 'spend_table' for query 'merchant_spend' failed and has been detached: PRV-5076 ...
Writing later batches over the one that failed would leave the sink missing changes with nothing to
say so; the query and its view carry on, and re-registering the query against the sink starts it
again from the view's ...
```

The query, its view and any other sink on it **carry on**. Writing later batches over the lost one
would leave the sink missing a change with nothing to say so — a retraction that never arrived is a
total that is wrong for ever. This console's query page shows the detach with its code, and so do
`pravaha queries` (`SINK` reads `<name> (detached)`, with the code and the reason under the table),
`GET /api/v1/queries/{name}` and both SDKs' listings.

**Do:** fix what the sink refused, then drop and re-register the query; the sink is sent the view's
whole contents first. Whatever it already held, it will hold twice — unless it upserts by key
(`jdbc-sink` in upsert mode, `aerospike-sink`, `kafka-sink` in upsert mode on a compacted topic), in
which case the repeat overwrites itself.

## The embedded engine

An application can run the engine in its own JVM (`PravahaEngine`), declare streams, and **push** rows
in directly. These four are what that API refuses. See [The embedded engine](/help/topics/embedded-engine).

### PRV-8101 — embedded unknown stream

A row was pushed to a stream this engine was never told about. Declare it first (in configuration under
`pravaha.streams.<name>`, or through the API).

### PRV-8102 — embedded row rejected

A pushed row does not fit its stream: the wrong number of values, a value of the wrong kind for its
column, or a null in a `NOT NULL` column. The message names the column.

### PRV-8103 — embedded backpressure

A computation's inbox stayed full for longer than a push was prepared to wait
(`pravaha.embedded.push-timeout`, 30 seconds by default). The application is producing faster than
the query consumes: slow the producer, or size the lane larger.

### PRV-8104 — embedded misconfigured

The engine's configuration says something it cannot do — a stream declared twice, an
`out-of-orderness` that is not a duration — found **at start** rather than at first use.

## The time-travel debugger

A debug session forks a query from one of its retained checkpoints and steps it with every sink
disabled. See [The time-travel debugger](/help/topics/time-travel-debugger). All six of these mean
the session did not start or did not advance; **none of them means the live query is in a strange
state**, because a fork writes into a view of its own and has no sink at all.

### PRV-8011 — no checkpoint to fork from

Three different absences, and the message says which: this node is not checkpointing
(`pravaha.checkpoint.directory` is unset), this query has not taken its first checkpoint yet (wait
one `pravaha.checkpoint.interval`), or the id you asked for has been pruned — this node keeps the
newest `pravaha.checkpoint.keep`, and the ones it still has are listed in the message.

```bash
pravaha debug checkpoints --name user_volume
```

### PRV-8012 — source not replayable

A fork reads the sources from the offsets the checkpoint recorded, so every stream the query reads
has to be rewindable to them. Refused when nothing is bound to the stream (its rows are pushed in
by an embedder, so there is no position), when the plugin cannot be read again from a position it
handed out, or when its positions do not order the records within a partition — a table scan
reports where its pass began. It is the same question a backfill asks (`PRV-4018`), answered by the
same code and named the same way.

### PRV-8013 — no such session

The id is not one this node holds. A session is released when it is ended and when nobody has
touched it for `pravaha.debug.session.ttl` (15 minutes by default) — it holds a whole second copy
of a query's state, so it is not kept indefinitely. `pravaha debug sessions` lists the open ones;
fork again to carry on.

### PRV-8014 — too many sessions

`pravaha.debug.sessions.max` (4 by default). The ceiling is memory rather than policy: each session
is a second copy of a query's lanes, arena and operator state. The message lists the sessions this
node is holding, so one can be ended. The gauge `pravaha_debug_sessions_open` is what to watch.

### PRV-8015 — a step this session cannot make

An unreadable step verb; a predicate over a column the view does not have, or a comparison that is
not one of `= != < <= > >=`; a watermark that would go backwards, which would let a fired window
fire again; a page above the ceiling; a session past `pravaha.debug.session.max-rows`; or a fixture
name that cannot be a Java class.

A predicate is deliberately **one column of the view against one value**. A second expression
language that is nearly SQL's would disagree with SQL somewhere, in the one tool you opened because
you already have a wrong answer. Step to the row and read the view instead.

```text
PRV-8015  'balance' is not a column of this query's view, which has [user_id, n, total].
          A debug predicate reads the view's own columns, so it can only name one of those.
```

### PRV-8016 — the query is gone

The query was dropped, or replaced by a different computation (a blue/green cutover), after the
session was forked. Stepping on would report the old version's behaviour under a name that now
answers a new one. Export what the session has if you still want it, then end it.

## Registration options

### PRV-8017 — unknown option

A `WITH (...)` option this engine does not build, or a value that is not what the option names.
Which options exist depends on the statement: a plain `CREATE CONTINUOUS QUERY` takes `retention`,
`sink` and `keys` — the arguments `pravaha register` already took — `index`, the value form of
`INDEX (column)`, and `lane` (`'dedicated'` or `'shared'`); `CREATE OR REPLACE` takes
`backfill`, `backfill.rate.limit`, `cutover`, `rollback.retention` and `lane`. Each refuses the other's by
name, with the statement that takes it — a replacement's refusal carries PRV-4018 rather than this
code, since it is the backfill that reads the list.

```text
PRV-8017  'consistency.default' is not an option a registration takes, and it is refused rather
than ignored -- an ignored option is a setting somebody believes is in force. ...
```

The design's `consistency.default`, `parallelism` and `allowed.lateness` are not built:
consistency is chosen by the reader and per read, and a query's parallelism and lateness are the
engine's to decide.

Also raised for `lane = 'dedicated'` on a query that asks the same question as one already running
on a shared lane: the two would share one computation, and a running query is never moved in place.
Register it `lane = 'shared'`, or move the running one onto a lane of its own with
`CREATE OR REPLACE ... WITH (lane = 'dedicated')` ([Sharing lanes](/help/topics/lanes#sharing-lanes)).

The same code covers the same setting said twice — `RETAIN FOR` and `retention`, `WRITING TO`
and a different `sink`, or `INDEX` and `index` — because which of two answers wins is not something to leave to the order
they were written in.

## Tenancy quotas

A tenant is admitted by two quotas set under `pravaha.tenancy` (ADR-050). Both are checked when a
query registers, after authorization and planning and before a sink is opened; neither stops a query
that is already running. A limit that is not set is no limit, and zero is a limit. Each refusal is
audited as a denied `register:quota` (or `replace:quota`) decision with the tenant as its target,
and counted per tenant. The console's **Admin → Tenants** screen shows each tenant's use against its
limits and the refusals; the engine serves the same as `GET /api/v1/tenants`.

### PRV-8020 — the tenant's query quota

The tenant already holds `max-queries` names. Every name counts, including one that attaches to a
computation the tenant already runs. Drop a name the tenant no longer needs, or raise the tenant's
`max-queries`. A 409: the request is fine, and what the tenant already holds is the reason.

### PRV-8021 — the tenant's state quota

The tenant's views already hold `max-state-keys` keys, and the registration would start a new
computation. A name that attaches to identical SQL the tenant already runs starts nothing and is not
refused by this. Drop a query, shorten a retention so its view holds fewer keys, or raise the
tenant's `max-state-keys`. A 409, like PRV-8020. A tenant whose views grew past the quota after they
were admitted keeps running and is shown over the limit.

### PRV-8022 — a replacement from another tenant

Only a principal of the tenant that registered a name can replace it, because the new version is
charged to that tenant and shared only within it. A 403. The refusal does not name the tenant that
holds it; the audit trail does.

### PRV-8023 — tenancy configuration

The node refuses to start with a negative limit or a blank tenant name under `pravaha.tenancy`.

## Queries over queries

A continuous query can read another query's answer (see
[CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query#queries-on-queries)). Four codes
keep a chain exact.

### PRV-8024 — a query other queries read

`DROP` of a name that other continuous queries read is refused, and the message names them. Drop
them first. There is no cascade: it would take answers away from queries somebody else registered.

### PRV-8025 — a loop

A `CREATE OR REPLACE` whose new version would read, through other queries, its own answer. The
message names the loop — `a reads b reads a`. A plain `CREATE` cannot make one, because it can only
read what already exists.

### PRV-8026 — what a chain cannot make exact

Refused rather than approximated: replacing a query that reads a view, or one that others read (a
cutover would move the name to another computation behind the queries following the first);
registering over a name that is being replaced; `RETAIN FOR` on a query over a view (retention
belongs to the upstream — say `RETAIN FOREVER`); a column type a row cannot carry; and a restore
whose checkpoint holds the downstream's view without what it had consumed of the upstream, which
would feed the whole answer again on top of it.

### PRV-8027 — a chain too deep

At most eight queries over queries above a stream. Each level adds a commit's latency and a copy of
its input's answer; fold some steps into one query.

### PRV-8028 — a single-consumer binding another query holds

The stream is bound to a source with one consumer at a time — a `postgres-cdc` replication slot, a
`mysql-cdc` replica `server.id` — and another query, named in the message, is reading it. The
registration is refused before anything opens, and the running query is untouched; the REST API
answers `409`. A second name for the *same* SQL is one computation and is not refused. For a second,
different question, bind the table again under another stream name with a `slot` (or `server.id`) of
its own, or drop the query holding the binding. It used to be accepted and fail `PRV-5117` about
fifteen seconds later (CDCREPL-2).

## Alerts

An alert watches a view and notifies when a key's row enters it and when it leaves
([Alerts](/help/topics/alerts)). A view an alert follows cannot be dropped (`PRV-8024`, naming
`ALERT <name>`) until the alert is.

### PRV-8040 — no such alert

No alert by that name that you may see: the same answer for one that does not exist and one you hold
no `SELECT` on, so it confirms nothing. `SHOW ALERTS` and `pravaha alerts ls` list what you may.

### PRV-8041 — the name is taken

An alert of that name exists (add `IF NOT EXISTS` if that is fine), or a continuous query is called
that, or — under the catalogue — another object has that name in your `default` namespace.

### PRV-8042 — a definition that cannot be kept

The view is not registered (or not yours to see), a column in `WHERE` or `include` is not the view's,
a literal is the wrong type for its column (a number compared with `'many'`), an option does not
exist, a severity is not `info`, `warning` or `critical`, or a duration is not one (`30s`, `10m`,
`2h`, `1d`, `PT10M`).

### PRV-8043 — no such channel

`NOTIFY` names a channel the node does not bind. Channels are configured, like sinks, under
`pravaha.notifiers.<name>`; `pravaha alerts channels` lists them.

### PRV-8044 — the alert journal

`alerts.journal` (beside the registry journal, or `pravaha.alerts.journal`) cannot be read or
appended to. A decision is not acted on unless it is journalled, because a fire or a clear that does
not survive a restart is exactly what the journal prevents. Check the file and the disk.

### PRV-8045 — a notification not delivered

A channel did not accept a notification after its retries — a receiver down, a 5xx, a timeout. It is
recorded on the alert (its delivery error, and a `FAILED` line in its history) and sent again every
`pravaha.alerts.redeliver-after` until accepted, under the same idempotency key.

### PRV-8046 — a notifier the node cannot start with

A `pravaha.notifiers.<name>` binding names a plugin that is not on the classpath, has no URL, has a
secret written into it (`secret`, `token`, `password` are refused: say `secret-env` or
`secret-file`), names an environment variable that is not set or a file that cannot be read. The
node refuses to start rather than find the pager broken at the first page.

### PRV-8047 — no alert service

An alert statement or `/api/v1/alerts` call where no alert service runs: an embedded engine, or a
node with `pravaha.alerts.enabled: false`.

## Where next

- [Alerts](/help/topics/alerts)
- [The time-travel debugger](/help/topics/time-travel-debugger)
- [The life of a query](/help/topics/query-lifecycle) and [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query)
- [How a query writes to a sink](/help/topics/sinks-overview) and [Delivery guarantees](/help/topics/delivery-guarantees)
- [The embedded engine](/help/topics/embedded-engine)
