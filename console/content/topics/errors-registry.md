---
title: Registry codes (PRV-8xxx)
slug: errors-registry
category: errors
order: 90
icon: journal-x
summary: "PRV-8001 to PRV-8104: a query's name and lifecycle, a failed query whose view refuses reads, the journal, replay after revocation, sinks that fail or do not fit, and the embedded engine's four."
badge: PRV-8XXX
audience: Analysts, operators, developers
keywords: [registry, name in use, reserved word, no such query, drop, pause, resume, failed, journal, replay, sink detached, sink shape, keyed by, embedded, push, backpressure, row rejected]
guide: continuous-queries#8-the-life-of-a-query
related: [query-lifecycle, create-continuous-query, sinks-overview, embedded-engine, errors-overview]
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
| PRV-8011 | REGISTRY_OPTION_UNKNOWN | A `WITH (...)` option this engine does not build, or one said twice |
| PRV-8101 | EMBEDDED_UNKNOWN_STREAM | A row pushed to an undeclared stream |
| PRV-8102 | EMBEDDED_ROW_REJECTED | A pushed row does not fit its stream |
| PRV-8103 | EMBEDDED_BACKPRESSURE | A push waited too long for room |
| PRV-8104 | EMBEDDED_MISCONFIGURED | The embedded configuration says something impossible |

Over REST the registry codes are `400` — the request was the caller's to fix — except PRV-8002, which
is `404`.

## Names

### PRV-8001 — name in use

The name is already registered. Drop it first, register under another name, or write
`CREATE OR REPLACE`, which starts a blue/green replacement: **silently replacing a running query
would take its answers away from whoever is reading them**, so the new version backfills beside the
running one and takes the name only at a cutover.

Registering the *same computation* under a second name is not this — it is sharing: the second name
points at the running computation and costs nothing (see [Sharing](/help/topics/sharing)).

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
total that is wrong for ever. This console's query page shows the detach with its code.

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

### PRV-8011 — unknown option

A `WITH (...)` option this engine does not build, or a value that is not what the option names.
Which options exist depends on the statement: a plain `CREATE CONTINUOUS QUERY` takes `retention`,
`sink` and `keys` — the arguments `pravaha register` already took — and `CREATE OR REPLACE` takes
`backfill`, `backfill.rate.limit`, `cutover` and `rollback.retention`. Each refuses the other's by
name, with the statement that takes it — a replacement's refusal carries PRV-4018 rather than this
code, since it is the backfill that reads the list.

```text
PRV-8011  'consistency.default' is not an option a registration takes, and it is refused rather
than ignored -- an ignored option is a setting somebody believes is in force. ...
```

The design's `consistency.default`, `parallelism` and `allowed.lateness` are not built:
consistency is chosen by the reader and per read, and a query's parallelism and lateness are the
engine's to decide.

The same code covers the same setting said twice — `RETAIN FOR` and `retention`, or `WRITING TO`
and a different `sink` — because which of two answers wins is not something to leave to the order
they were written in.

## Where next

- [The life of a query](/help/topics/query-lifecycle) and [CREATE CONTINUOUS QUERY](/help/topics/create-continuous-query)
- [How a query writes to a sink](/help/topics/sinks-overview) and [Delivery guarantees](/help/topics/delivery-guarantees)
- [The embedded engine](/help/topics/embedded-engine)
