---
title: Upgrades
slug: upgrades
category: operating
order: 80
icon: arrow-up-circle
summary: "Upgrading a node is a stop and a start. What survives it, which checkpoint formats a new engine refuses and what that costs, how to change a query, and what blue/green (ADR-016) does and does not do today."
audience: Operators
keywords: [upgrade, version, rolling, blue green, ShadowDeployment, ADR-016, checkpoint version, PRV-4002, PRV-8005, journal compatibility, changing a query]
guide: operations#upgrades
related: [checkpoints-recovery, standby, create-continuous-query, query-lifecycle]
---

A Pravaha deployment is one active node, so an upgrade is **a stop and a start** of that node on
the new version. There is no cluster to roll through. What makes it safe is that everything a node
needs to come back — its registrations and its state — is in two places on disk that the new
version reads: the [registry journal and the checkpoints](/help/topics/checkpoints-recovery).

What makes it occasionally expensive is that both carry **format versions**, and a version the new
engine does not read is refused rather than guessed at. This page is the procedure, the cost, and an
honest account of what the "zero downtime" machinery does today.

## The procedure

1. **Read the release notes for format changes.** Look for a checkpoint snapshot version change and
   for journal record changes. Most releases have neither. **Upgrading 1.x to 2.0** changes neither,
   and needs **Java 21 or later** (2.0.0 asked for 25; 2.x now asks for 21): point `JAVA_HOME` at a JDK or JRE 21 or later first, or the new launcher refuses to
   start (the image is on 21). **Upgrading 2.0.0 to 2.0.1** changes answers where 2.0.0's
   were defects — NULL for an all-NULL `SUM`, overflow instead of wrap, merged `NaN`/`-0.0` groups,
   `HOP` windows on multiples of the slide, `MIN`/`MAX` over a CDC source refused — and the `users`
   profile's policy; read the release notes' list (and COMPATIBILITY.md, "2.0.1") first.
2. **Check the last checkpoint is recent** — `time() - pravaha_query_checkpoint_last_success_timestamp_seconds`
   well under an interval for every query. That is how much the restart replays.
3. **Back up** the checkpoint root and the journal together (a filesystem snapshot, or copy with the
   node stopped). This is the rollback.
4. **Stop the node.** Shutdown stops accepting connections, lets go of the queries, then leaves the
   cluster. The tail since the last checkpoint is left uncommitted, as a crash would leave it.
5. **Start the new version** on the same configuration and `pravaha.node.id`. It claims its
   directories (its own expired claim), replays the journal, and restores each query from its
   newest checkpoint.
6. **Read the recovery report** in the log: the recovered list and the refused list. Every refused
   entry is a view a client expects and will not find.
7. **Watch watermark lag fall** back to normal as the replay catches up.

With a [standby](/help/topics/standby), steps 4-5 can be: upgrade the standby first, then stop the
primary and let the upgraded standby take over; the old primary is upgraded and becomes the new
standby. The outage is the lease expiry plus the restore, rather than the host restart.

## What survives, and what a new version may refuse

### The journal

Records a newer engine wrote are refused by an older one with PRV-8005 — refused, not skipped,
because skipping would silently drop a registration. 2.0.1 adds one record (`M`, a shared
computation's survivors re-homed, SHAREDLOSS-1), and refuses with PRV-8005 a journal damaged in the
middle rather than reading past it (JOURNALMID-1). So **downgrading past a journal format change
loses nothing silently, but will not start those queries**; restore the backup taken in step 3.

### Checkpoints

Formats inside a checkpoint carry a version, and a different one is refused with PRV-4002. The
refusal is per query, and its consequence is bounded: **that query resumes from the start of its
sources** — reprocessing, visible in the numbers while it catches up, **never a double count** in its
answers. A sink that is not transactional is written the replayed rows again. The refusal is logged
at `WARN` and counted as a checkpoint failure, so a query that came back empty says so; a
checkpoint refused half-way through is put back to empty first (RESTOREPART-1).

The format changes so far, as examples of what to look for:

| Change | Refused after it | Everything else |
|---|---|---|
| The served view's snapshot went to version 2, keeping every value's exact type (VIEW-2) | a checkpoint whose view is version 1 | — |
| The operator snapshot went to version 4 when unwindowed aggregates began carrying their accumulators (CKPT-2) | a version 3 checkpoint of an unwindowed aggregate | version 3 windowed and join checkpoints still restore |
| The pipeline snapshot went to version 5 when `COUNT(DISTINCT)` state moved off-heap (ADR-044) | a version 3 or 4 snapshot holding windowed state | a version 3 or 4 snapshot of a plan with no windowed aggregate restores |
| 2.0.1: every checkpoint ends with a CRC32C and records its output schema (CKPTSUM-1, RETYPERESTORE-1) | none by format: a 2.0.0 checkpoint has neither and restores, logged as unverified. Not restored: one that fails its checksum (PRV-4094, the one before it is used), one of another output schema (PRV-4095), and one holding a `-0.0` or non-standard `NaN` as a key (NANGROUP-1) | 2.0.0 still reads a 2.0.1 checkpoint, the checksum being a tail after an unchanged body |

**Plan an upgrade across such a change for a time when replaying the sources is affordable** — a
source that no longer holds the history cannot replay it — or accept that those views warm up.
Every checkpoint written after the upgrade is readable by the new version.

## Changing a query

A registered query is never edited in place. To change its SQL, key, retention or sink:

```sql
DROP CONTINUOUS QUERY hourly_spend_v1;
```

```sql
CREATE CONTINUOUS QUERY hourly_spend_v1 KEYED BY (user_id, window_end)
RETAIN FOR P7D
AS SELECT user_id, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

The new registration is a new computation: its view starts empty and fills from its sources.
`CREATE OR REPLACE` does this for you without the gap — the new version backfills beside the
running one and takes the name only once the two have consumed the same input.

To change without a gap for readers, register the new version under a **new name** beside the old
one, let it fill, move clients over, then drop the old:

```sql
CREATE CONTINUOUS QUERY hourly_spend_v2 KEYED BY (user_id, window_end)
RETAIN FOR P7D
AS SELECT user_id, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

```sql
SHOW CONTINUOUS QUERIES;
```

```text
 name            | state   | fingerprint | rows_in | key_columns        | sink | retention
-----------------+---------+-------------+---------+--------------------+------+----------
 hourly_spend_v1 | RUNNING | 9f2c...     | 1284311 | user_id,window_end |      | P7D
 hourly_spend_v2 | RUNNING | 41ab...     |    2210 | user_id,window_end |      | P7D
```

(Abbreviated; `SHOW CONTINUOUS QUERIES` also returns the SQL.) When `hourly_spend_v2`'s windows cover
what readers need, point them at it and `DROP CONTINUOUS QUERY hourly_spend_v1`.

## Blue/green: what is and is not built

ADR-016 decides that every query change should be a **blue/green shadow deployment**: the new version
runs beside the old, cuts over at a **frontier, not a moment** — so every input record is reflected
in exactly one version's output — and rollback is the same swap reversed.

**It is not built into the product.** `ShadowDeployment` exists in `pravaha-backfill`, with tests, and
**nothing calls it**: there is no registration option, statement, API or console action that performs
a cut-over. The side-by-side procedure above is the manual form, and it is not exactly-once across the
switch — a reader moving from v1 to v2 at an arbitrary moment may see a window counted by v1 and then
by v2.

!!! note "The long-form Operations guide says otherwise"
    `OPERATIONS.md` currently says blue/green "is supported for a query". The decision record's status
    — "Accepted; not built — `ShadowDeployment` exists ... with tests and no caller" — is the
    accurate one.

## Pitfalls

!!! warning "Pitfall: upgrading a node with no checkpoint directory"
    It comes back knowing every question and none of the answers: every view is empty after the
    upgrade and fills from whatever its sources still hold.

!!! warning "Pitfall: the JVM flags of a custom launcher"
    A new Java version may need another flag. A launcher of your own needs
    `--enable-native-access=ALL-UNNAMED` (valid from Java 21) and, on a JVM 23 or later,
    `--sun-misc-unsafe-memory-access=allow`, as `bin/pravaha-server` passes them, or the JVM warns;
    older JVMs refuse the second as an unknown option, which is why it is passed only on 23 and later. `bin/pravaha-server` passes `PRAVAHA_JAVA_OPTS` through.

## Where next

- [Checkpoints and recovery](/help/topics/checkpoints-recovery)
- [Standby](/help/topics/standby)
- [CREATE CONTINUOUS QUERY and the management statements](/help/topics/create-continuous-query)
- [ADR-016: blue/green query updates](/help/decisions/016-blue-green-query-updates)
- How it is built: [Architecture: packaging](/help/architecture-observability-packaging#packaging-and-deployment)
