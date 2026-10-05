---
title: Continuous queries and their lifecycle
slug: query-lifecycle
category: concepts
order: 20
icon: arrow-repeat
summary: "A continuous query is a computation you register once and the engine keeps running. What registration does, the four states — RUNNING, PAUSED, FAILED, DROPPED — what each means for a read, and the statements that move a query between them."
audience: Everyone
keywords: [register, lifecycle, state, running, paused, failed, dropped, pause, resume, drop, show continuous queries, fingerprint, journal, restart, PRV-8003, PRV-8004]
guide: continuous-queries#8-the-life-of-a-query
related: [create-continuous-query, views-and-keys, sharing, checkpoints-recovery, streams]
---

A **continuous query** is SQL registered under a name. It is not a request: you do not ask it for an
answer; you register it once and it keeps one — its **view** — current until somebody drops it.
Reading the view is a separate, cheap operation that never re-runs the query.

The consequence worth internalising: **registering is expensive and reading is cheap.** A
registration commits the node to memory and to a share of a lane for as long as it exists — about
1 MiB of off-heap while idle and 1.3 MiB once rows are moving, ~65 KiB of heap, roughly 16 ms to
register (mostly planning), and no platform thread of its own (measured on one node; see
[sizing](/help/topics/lanes#sizing-lanes)). That is why registering is authorised separately from reading.

## What registration does

1. The SQL is parsed and validated against the declared streams.
2. If the registration names a sink, the plan's changelog is checked against what that sink accepts
   — a query that revises its answer is refused against an append-only sink with PRV-2041, and a
   column or key mismatch with PRV-8010, before anything opens.
3. A physical plan is built and **fingerprinted** — plan, row filters, key columns, retention.
4. If an identical fingerprint is already running, **that computation is shared**: the new name
   points at it and no new state is built.
5. Otherwise a lane is created, the plan compiled onto it, and a feed opened for each source stream.
6. The view is created and registered in the catalogue.

Step 4 is why a thousand dashboards asking the same question cost one computation. See
[sharing](/help/topics/sharing).

## Registering

In SQL, through any SQL surface — `pravaha query --sql`, an SDK's `query()`, the console's
workbench, a Flight SQL JDBC or ADBC driver, the embedded engine:

```sql
CREATE CONTINUOUS QUERY region_five_min
    KEYED BY (region, window_end)
    RETAIN FOR P7D
AS
SELECT region, window_start, window_end,
       COUNT(*)    AS orders,
       SUM(amount) AS revenue
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end;
```

It answers with one row:

```text
name             state    fingerprint    sink
region_five_min  RUNNING  5e0b3d27c41f
```

Or with the same meaning as a registration call, the key as output-column ordinals:

```bash
pravaha register --name region_five_min --keys 0,2 --retain P7D --sql "SELECT region, window_start,
  window_end, COUNT(*) AS orders, SUM(amount) AS revenue
  FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
  GROUP BY region, window_start, window_end"
```

```text
registered region_five_min  state=RUNNING  fingerprint=5e0b3d27c41f  retain=P7D
a query with the same fingerprint is the same computation, shared
```

```python
client.register("region_five_min", sql, [0, 2], retention="P7D")
```

(Fingerprints are hashes; yours will differ.) The full grammar of `CREATE CONTINUOUS QUERY` is on
[its own page](/help/topics/create-continuous-query).

## The four states

```text
          register
             │
             ▼
         RUNNING ──pause──► PAUSED ──resume──► RUNNING
             │                 │
             │ a lane throws   │
             ▼                 ▼
          FAILED           (drop) ──► DROPPED
```

| State | The computation | Reading the view | Subscribers |
|---|---|---|---|
| `RUNNING` | ingesting and maintaining the view | answers from the latest commit | receive every commit |
| `PAUSED` | feed stopped; state kept; resumable | **keeps answering** at the frontier it reached | receive nothing new |
| `FAILED` | a lane threw; terminal | **refused** with PRV-8004 — the rows were correct as of the failure, and the engine will not hand them over as though they were current | closed |
| `DROPPED` | gone; terminal | the name no longer exists (PRV-4023, "not a registered view") | closed |

`FAILED` and `DROPPED` are terminal: pausing or resuming either is refused with PRV-8003, because
the state you asked for is not reachable.

### Pausing

```sql
PAUSE CONTINUOUS QUERY region_five_min;
RESUME CONTINUOUS QUERY region_five_min;
```

**A pause is not a stop.** The view keeps answering at the frontier it reached — far better for a
dashboard than answers that disappear. **Rows arriving while paused are dropped, not buffered**:
buffering would turn a pause into a memory commitment of unknown size, when the point of a pause is
to stop doing work. Resume, and the query continues from what arrives next.

**Pausing twice is not an error.** `PAUSE` on a paused query and `RESUME` on a running one succeed
and change nothing. They say what state you want, not what transition you expect — so a maintenance
script does not need to know whether somebody already paused.

### Failing

A lane that throws is dropped **alone**: its query goes to `FAILED`, and every other query on the
node — including others sharing the runner thread — carries on. A failed query's view refuses reads
rather than serving a snapshot frozen at the moment of failure, because a stale answer presented as
current is worse than no answer. Find the cause in the query's page in the console or the node log,
then drop and re-register it.

### Dropping

```sql
DROP CONTINUOUS QUERY region_five_min;
```

**A drop removes a name.** The computation goes when its *last* name goes. If somebody else
registered the same question, dropping yours leaves theirs running — and neither of you knows the
other exists, which is exactly why the engine never drops eagerly.

## Seeing what is running

```sql
SHOW CONTINUOUS QUERIES;
```

answers one row per name you may learn exists, with `name`, `state`, `sql`, `fingerprint`,
`rows_in` (`-1` when your access to the view is row-filtered and its total is withheld),
`key_columns` (by name), `sink` and `retention`:

```text
name             state    sql                         fingerprint   rows_in  key_columns         sink  retention
region_five_min  PAUSED   SELECT region, window_st…   5e0b3d27c41f  48210    region, window_end        P7D
```

The CLI's listing is the short form:

```bash
pravaha queries
```

```text
NAME             STATE   FINGERPRINT   ROWS IN  SINK
region_five_min  PAUSED  5e0b3d27c41f  48210    -
```

A `-` under `ROWS IN` means the server withheld the count, for the same reason. The HTTP API
describes a query in full — keys by name, retention, sink and whether it is still attached, the
other names sharing it that you may see, the streams it reads — at
`GET /api/v1/queries/{name}`, and its running plan at `GET /api/v1/queries/{name}/plan`.

## Across a restart

With `pravaha.registry.journal` set, every registration — its SQL, key, retention and sink — is
journalled, and comes back when the node restarts. With `pravaha.checkpoint.directory` set, its
state and source offsets come back too, so a restart **resumes** rather than replaying from scratch
or starting empty. Without either, a restart is a clean slate. See
[checkpoints and recovery](/help/topics/checkpoints-recovery).

## Who may do what

A `CREATE` is authorised as `pravaha register` is — may this principal register, and may it read
every stream the query reads. `DROP`, `PAUSE` and `RESUME` are authorised by the policy's
administer check and audited under the same verbs. The spelling changes nothing about who may do
what. See [authorization](/help/topics/authorization).

## Pitfalls

!!! warning "RUNNING, and the view is empty"
    Running means ingesting, not publishing. A windowed query publishes a window when the watermark
    passes its end; with no `event-time` on the stream that never happens. See
    [event time and watermarks](/help/topics/event-time-watermarks).

!!! warning "Resuming does not replay what arrived while paused"
    Those rows were dropped. If the gap matters, drop and re-register against a source that can
    replay (a file, a Delta table from a version), or do not pause.

!!! tip "Replace a query rather than dropping it"
    `CREATE OR REPLACE CONTINUOUS QUERY` starts a blue/green replacement: the new version is
    registered beside the running one, backfilled from the source, and takes the name only when the
    two have consumed the same input — so nobody reading the name loses an answer, which is what
    dropping and re-creating costs. The version it replaced keeps running for the rollback window.
    A query whose computation is shared with another name is refused (PRV-8003): change or drop the
    others first.

## Where next

- [CREATE CONTINUOUS QUERY and the management statements](/help/topics/create-continuous-query)
- [Views and keys](/help/topics/views-and-keys) — what a registration maintains
- [Sharing by fingerprint](/help/topics/sharing)
- The long form: [Streams, queries and SQL §3 and §8](/help/continuous-queries#3-registering-a-continuous-query)
- How it is built: [Architecture: the life of a registered query](/help/architecture#the-life-of-a-registered-query)
- How it is built: [Architecture: registry](/help/architecture-registry#registration-and-sharing)
