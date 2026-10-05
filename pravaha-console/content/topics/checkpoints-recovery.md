---
title: Checkpoints and recovery
slug: checkpoints-recovery
category: operating
order: 50
icon: save2
summary: "What survives a restart: the registry journal remembers the questions, checkpoints remember the answers. The settings, what a restart replays, who owns a state directory, and a crash walked through."
audience: Operators
keywords: [checkpoint, restart, journal, registry.journal, recovery, replay, offsets, keep, interval, timeout, ownership, .pravaha-owner, PRV-4003, PRV-4004, PRV-4002, PRV-8005, PRV-8006, backup]
guide: operations#restarts-what-survives
related: [standby, delivery-guarantees, upgrades, observability, state-spill, source-kafka]
---

A restarted node has to answer two different questions, and Pravaha keeps two different files for
them. The **registry journal** remembers *which queries exist* — name, SQL, key, retention, sink,
owner, bound values. **Checkpoints** remember *what those queries had accumulated* — operator
state, the view, and the source offsets that state describes. A node with a journal and no
checkpoints comes back knowing every question and none of the answers. A node with neither comes
back empty.

Both are **off by default**, and a node that is missing either says so at startup rather than
leaving it to be discovered during a recovery.

## The settings

```yaml
pravaha:
  node:
    id: pravaha-node-01
  registry:
    journal: /opt/pravaha/data/registry.journal
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
    interval: 1m
    keep: 3
    timeout: 30s
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.registry.journal` | empty | The journal file. Empty: registrations live only in memory |
| `pravaha.checkpoint.directory` | empty | The checkpoint root; each query checkpoints into its own sub-directory. Empty: no checkpoints |
| `pravaha.checkpoint.interval` | `1m` | How often each query takes one. It is also how far a restart replays, and how far a transactional sink trails its view |
| `pravaha.checkpoint.keep` | `3` | How many to keep **per query**, pruned automatically |
| `pravaha.checkpoint.timeout` | `30s` | How long one checkpoint may take — including preparing a transactional sink on the query's lane — before it is abandoned and counted as a failure |
| `pravaha.node.id` | `pravaha-node-01` | Who claims these directories (below) |
| `pravaha.state.allow-shared` | `false` | Skip the ownership check — almost never |

Without them the startup log says:

```text
WARN pravaha.checkpoint.directory is not set, so registered queries keep no checkpoints: a restart recovers their definitions from the journal and none of their accumulated state
WARN pravaha.registry.journal is not set, so registered queries live only in memory and ...
```

## What a restart does, in each configuration

| Journal | Checkpoints | After a restart |
|---|---|---|
| no | no | Nothing is registered. Every client must register again |
| yes | no | **A warm-up.** Every query is re-registered, every view starts empty and fills as data arrives; a windowed query's first window or two are partial |
| yes | yes | **A resumption.** Operator state, the view and the source offsets come back together, and each source rewinds to the point the state describes |

**The view is in the checkpoint**, beside the operator state and the offsets. It has to be: a filter
or a projection has no accumulators, so the view *is* its entire answer, and a restore that took the
offsets without it would resume past every row it had read and serve an empty view.

### Why per query, and why counted

Each query checkpoints into its own directory because one shared store would make pruning global --
the newest three across the node — so a busy query would evict a quiet one's only fallback.
Retention is **counted, not timed**: an age rule would delete the last fallback precisely when
nothing is happening, because an idle query takes no new checkpoints. More than one is kept because
the newest is the likeliest to be unreadable — it is the one being written when a process died.

### What a checkpoint is

A checkpoint is one **aligned cut** across every input a query reads (ADR-008): the sources are frozen
between rows, every lane is handed a barrier at a specific position in its stream, and the offsets
and the state recorded name exactly the same rows. That is what makes "resume from here" mean
something — no row is counted twice and none is skipped by the state.

## The journal

The journal records registrations, not state — small, rarely changing, and **impossible to
recompute**, because it came from a client that may never connect again.

- **Owners are re-checked on replay.** A registration is not a standing permission: if the principal
  who registered a query has lost access, replay refuses it and names it. Recovery reports two lists
  — recovered and refused — and **the refused list is the one to read**: each entry is a view some
  client expects to find and will not.
- **A refused registration stays visible until it is dropped.** It is logged at `ERROR`, listed
  `FAILED` with its code (the queries page, `pravaha queries`, `SHOW CONTINUOUS QUERIES`), counted by
  `pravaha_registry_recovery_refused`, and the node's health is `DEGRADED`. The journal keeps it, so
  the next start tries again; `DROP CONTINUOUS QUERY <name>` removes it for good and deletes its
  checkpoints, so registering the name again starts afresh. Until RECOVERYHEALTH-1 it vanished with
  one `WARN` line and health `UP`.
- **The journal records the sink.** A restart re-attaches it; a journalled registration whose sink is
  no longer bound is refused by name rather than recovered writing to nothing.
- A crash mid-append leaves a truncated final record; replay keeps everything before it, and the
  next append cuts the torn bytes off first. Damage in the *middle* — a complete record after a bad
  one — refuses the start (PRV-8005) rather than dropping every registration after it.
- **A shared computation's state outlives any one of its names.** Two registrations of the same
  question share one computation, which checkpoints into the directory of the name that started it.
  Dropping that name records, with the drop, that the surviving names checkpoint there, so a restart
  brings them back with the state rather than empty (SHAREDLOSS-1).
- Compaction rewrites the journal with only what is live, through an atomic move.

| You see | It means |
|---|---|
| PRV-8005 `... this version does not understand` | A journal record from a newer version. Refused, not skipped — skipping would silently drop a registration |
| PRV-8005 `... has a damaged length ..., and a complete record follows` | Damage in the middle of the journal. The start is refused, naming both byte offsets, rather than dropping every later registration; restore from backup or move the journal aside and re-register |
| `WARN ... ends in a half-written record` | A crash cut the last append short; what precedes it is replayed and the torn bytes are cut off before the next append |
| PRV-8006 on register | The journal could not be written, so the registration is **refused**: acknowledging one that will not survive a restart would tell the client something untrue |
| `refused: ... contract ended` | The owner lost the permission they registered under. Working as intended |
| `refused: ... not a principal this deployment knows` | The owner no longer resolves |

!!! danger "The journal and the checkpoints are data"
    The journal holds query text **and bound parameter values** — account numbers, customer ids.
    Checkpoints hold serialised operator state, which is the aggregated data itself. Both are created
    owner-only (`rw-------`) before the first byte is written. Nothing is encrypted at rest: if that
    is required, put the directories on an encrypted volume.

## Who owns a state directory

A node **claims** the checkpoint root and the journal's directory at startup by writing a
`.pravaha-owner` marker naming its node id, host, Flight port and pid, refreshed on a 30-second
lease. Two nodes pointed at one root used to prune each other's checkpoints and replay each other's
registrations, silently; now the second refuses to start:

```text
PRV-4003  the state in /opt/pravaha/data/checkpoints belongs to node 'pravaha-node-01'
          (pravaha-node-01 at 10.0.0.4:19090 (pid 8123)), and this node is 'pravaha-node-02'.
          ... The other node refreshed its claim 3s ago, so it is running now.
```

| Code | Means | Do |
|---|---|---|
| PRV-4003 | Held by another node, or by a second live instance of this one | Give this node its own directory, or stop the other instance |
| PRV-4004 | A marker exists and cannot be read or written, or names no node | Refused rather than assumed free. Delete the marker only if the directory is genuinely unowned |

The directory is namespaced by **node id, not address**, so a node restarting on a new pod IP finds
its own checkpoints. **A crash restart is not a conflict**: an expired claim under the *same* node id
is taken over automatically, with a log line saying so. `pravaha.state.allow-shared: true` skips
every check, for an operator who has read the refusal and meant it.

## A crash, walked through

A node with the configuration above runs `hourly_spend`:

```sql
CREATE CONTINUOUS QUERY hourly_spend_ckpt KEYED BY (user_id, window_end)
RETAIN FOR P7D
AS SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

1. **10:00:00** — checkpoint 41 stored: the open windows' sums, the view, and the source offset,
   say line 1,204,331 of the file. `pravaha_query_checkpoint_last_success_timestamp_seconds` moves.
2. **10:00:40** — the process is killed. Rows up to line 1,209,870 had been applied; the view had
   answered reads from them.
3. **10:01:10** — the node restarts. It claims its directories (its own expired claim, taken over),
   replays the journal — `hourly_spend_ckpt` recovered — and restores checkpoint 41: the sums, the
   view and offset 1,204,331.
4. The source rewinds to line 1,204,331 and re-reads the 5,539 rows after it. Because the state is
   exactly what those offsets describe, each is counted once. For forty seconds after the restart the
   view is catching up; it is never double-counted.
5. The first commit after the catch-up makes the view equal to what it would have been with no
   crash.

What the replay does to a **sink** depends on the sink — exactly once for `jdbc-sink` and `kafka-sink`
(to a `read_committed` consumer) on a node that checkpoints, effectively once for `aerospike-sink`, at
least once for `filesystem`, which keeps what it had written and appends the replayed rows below it
(duplicates in the file). See [Delivery guarantees](/help/topics/delivery-guarantees).

What it does to a **source** that cannot rewind: a source that cannot return to a checkpoint's
offsets makes the whole pipeline at-least-once, and a source that no longer holds those rows cannot
supply them at all. A source may also be *told* when a checkpoint holding its offset is durable:
[postgres-cdc](/help/topics/source-postgres-cdc) confirms its replication slot only then, so the
database never discards a change a restore could ask for — which is why that source needs
checkpoints to run at all. The [Kafka source](/help/topics/source-kafka)'s offsets live only in the
checkpoint (never in a consumer group), and a restore that finds retention has already deleted the
records after them is refused with [PRV-5106](/help/codes/PRV-5106) rather than resumed further on.

After the restart, a point read shows the recovered answer immediately:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend FROM hourly_spend_ckpt WHERE user_id = 'u-1042'
```

```text
 user_id | window_end          | spend
---------+---------------------+-------
 u-1042  | 2026-09-19 09:00:00 |  8120
 u-1042  | 2026-09-19 10:00:00 |  2210
```

(Sample values.)

## Watching checkpoints

| Metric | Alert when |
|---|---|
| `pravaha_query_checkpoint_last_success_timestamp_seconds` | `time() - ...` exceeds a few intervals: that is how much a restart would now replay. `NaN` while a query has not stored one, never zero |
| `pravaha_query_checkpoint_duration_seconds` | approaches `pravaha.checkpoint.timeout` |
| `pravaha_query_checkpoint_failures_total` | rises — especially while the last-success age also rises |

The console's operations screen flags "Checkpoints are failing" on any new failure and "No recent
checkpoint" when the last one is more than fifteen minutes old. Rules are in
[Metrics and alerts](/help/topics/observability).

**Why it failed** is in two places (CKPTWHY-1): a `WARN` line in the node's log for every failure —
`query 'totals': checkpoint failed (3 so far): cannot store checkpoint 12. Recovery will fall back to
the newest stored checkpoint, which is getting older` — and `checkpoint` in
`GET /api/v1/queries/{name}`: `enabled`, `last` (when one was last stored), `failures` and
`lastFailure`, the most recent reason, including a checkpoint that could not be restored at start.

## Backup

Checkpoints are files; recovery restores from the newest complete one. To back a node up, copy the
checkpoint root and the journal together, from a filesystem snapshot or with the node stopped — a
copy of one without the other restores questions without answers, or answers to questions it does
not know. Restore both to the same paths under the same `pravaha.node.id`.

## Pitfalls

!!! warning "Pitfall: a checkpoint written by an older engine may be refused"
    Formats inside a checkpoint carry a version, and a different one is refused with PRV-4002, never
    guessed at. That query then resumes from the start of its sources — reprocessing, never a double
    count. See [Upgrades](/help/topics/upgrades).

!!! note "A damaged checkpoint, or one of another schema, is never restored"
    Each checkpoint carries a checksum; one whose bytes do not match is skipped with PRV-4094 and the
    one before it is restored (CKPTSUM-1). Each records its query's output schema; a restart whose query
    now produces another rebuilds from its sources, PRV-4095 (RETYPERESTORE-1). Checkpoints written
    before 2.0.1 have neither and are restored as before, the first logged as unverified.

!!! note "A checkpoint that cannot be restored is restored not at all"
    A checkpoint comes back in parts — each lane's operators, then the view, then what it recorded
    for sinks — and any part can refuse. The parts already back are put back to empty before the
    query starts from its sources, so a refusal half-way never leaves some operators holding history
    beside a replay that counts it again (RESTOREPART-1). It is said, not only done: the log has a
    `WARN` naming the query, the checkpoint and the cause, and the query counts it as a checkpoint
    failure, so `pravaha_query_checkpoint_failures_total` rises and the operations screen flags it.
    In the one case the parts cannot be put back — a lane that does not answer — the registration is
    refused (PRV-3010) and a recovery lists it among the refused, rather than running from state
    that is neither the checkpoint's nor empty.

!!! warning "Pitfall: a long interval on a transactional sink"
    `jdbc-sink` applies what was staged once the checkpoint that recorded it is durable, so the table
    trails the view by up to one interval. `interval: 10m` is a ten-minute-stale table.

!!! note "A drop commits; a shutdown does not"
    Dropping a registration commits what its transactional sink was written, since nothing will replay
    it. Stopping the node leaves the tail since the last checkpoint uncommitted, as a crash would, and
    the restart writes it again.

## Where next

- [Standby](/help/topics/standby) — the same checkpoints, taken over by a second process
- [Delivery guarantees](/help/topics/delivery-guarantees) — what a replay does to each sink
- [Upgrades](/help/topics/upgrades) — checkpoints across versions
- [ADR-008: aligned checkpoints](/help/decisions/008-aligned-checkpoints)
- How it is built: [Architecture: the checkpoint cut](/help/architecture-registry#the-checkpoint-cut)
- How it is built: [Architecture: a restart, traced](/help/architecture#a-restart)
