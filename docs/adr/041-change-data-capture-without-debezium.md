# ADR-041: change data capture without Debezium, at least first

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — decision recorded, no code started |
| Date | 2026-09-16 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-013 (Z-sets), ADR-030 (scope tiers), ADR-039 (GA order), ADR-040 (remote connector), `../CONNECTORS.md` §5 |

## Decision

The first change-data-capture source is a **native Postgres logical-replication reader built on the
PostgreSQL JDBC driver**, not an embedded Debezium engine.

Debezium stays in `CONNECTORS.md` as the explanation of *what CDC is and why this engine wants it* —
that section is correct and stays. What changes is which code arrives first.

## Why this came up

`CONNECTORS.md` §5 shows a `plugin: debezium` binding marked "does not exist yet". The label is
accurate and was written deliberately, but a configuration example for a plugin nobody has built
invites somebody to build exactly that thing. This records why they should not start there.

## What each choice actually costs

**Debezium Embedded** is the obvious route and it is not a small one. `debezium-embedded` pulls
`debezium-core`, a connector module per database, and the **Kafka Connect API and client** — a
Connect runtime hosted inside the Pravaha process, with its own lifecycle, its own threading, its own
offset storage and its own configuration surface, all of which then have to be reconciled with this
engine's. Tens of megabytes, and a second framework's opinions about how sources work living next to
this one's.

That matters more here than it would elsewhere, because the deployment goal is **one bundle that runs
on every platform**. Every dependency is a licensing question, a size question and a portability
question.

**The driver already does it.** `postgresql` 42.7.8 — already the driver this repository tests the
JDBC plugin against — ships `PGReplicationConnection`, `PGReplicationStream`, `LogSequenceNumber` and
the v3 replication protocol. Streaming a replication slot needs *no new dependency at all*: the same
driver a deployment already supplies for the JDBC source carries the replication API.

So the comparison is a Kafka Connect runtime against a driver that is already present. That is not a
close call for the first connector.

## What is genuinely harder without Debezium

Stated plainly, because this is the cost of the decision rather than a footnote.

**The `pgoutput` stream is a binary protocol and has to be decoded by hand.** It is documented and
stable, and it is the one Postgres ships built in — `wal2json` is friendlier to read and requires a
server-side extension, which is a portability cost paid by every operator rather than once by this
repository. So `pgoutput` it is, and the message decoding is real work: Relation, Insert, Update,
Delete, Begin, Commit, plus tuple data with its null and toast markers.

Debezium would have supplied that decoder, along with schema-change handling, many databases from one
connector, and years of production hardening. This decision buys a small dependency footprint and
pays for it in decoder work and in supporting one database instead of seven.

**The rule for revisiting: a second database.** MySQL binlog, Oracle LogMiner and MongoDB oplog are
three more hand-written decoders, and at that point Debezium's one-connector-reaches-seven argument
wins on its own terms. Postgres first, natively; a second database is the signal to reconsider, not
to write a second decoder.

## The operational trap that has to be in the documentation from day one

**`REPLICA IDENTITY` decides whether corrections are even possible.** Postgres defaults a table to
`REPLICA IDENTITY DEFAULT`, which puts only the *primary key* in the before-image of an update or
delete. This engine needs the whole old row.

`CONNECTORS.md` §5 works the example: a customer moves from silver to gold, the before-image retracts
`(42, "silver")` at `-1` and the after-image inserts `(42, "gold")` at `+1`, and a `GROUP BY tier`
walks 900/100 to 899/101. With `REPLICA IDENTITY DEFAULT` the before-image is `(42)` and nothing else
— **there is no `silver` to retract**, so silver stays at 900 for ever, with no error and no gap in
any metric. That is precisely the failure that section already warns about, reached through a
database setting rather than through a connector bug.

So the connector must **refuse a table whose replica identity is not `FULL`**, at configuration time,
naming the `ALTER TABLE ... REPLICA IDENTITY FULL` that fixes it. A source that silently emits
key-only before-images would be a correctness defect wearing a working connector's clothes. It must
also declare `emitsBeforeImage` honestly — and those two `SourceCapabilities` fields, written when
the SPI was designed and used by no connector since, are what this is finally for.

**A replication slot is server-side state.** It retains WAL until it is consumed, so a slot created
and then abandoned fills the database's disk — a Pravaha outage becoming a Postgres outage. The
connector owns saying so, and the slot's lifecycle is most of its operational story.

## What this does not change

`SplicedReader` in `pravaha-backfill` already solves the snapshot-to-stream seam, and a CDC source
plugs into it rather than inventing one. Nothing here revisits ADR-040: the remote connector is a
different answer to a different question — pushing rows from inside an application, rather than
reading a database's own log.
