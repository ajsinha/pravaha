---
title: The problem it solves
slug: the-problem
section: About
order: 5
icon: bullseye
summary: "Answers that go stale the moment they are computed, and the second database people build to keep them."
---

## The same question, asked a thousand times

Most systems that need a current answer — a customer's spend this hour, a desk's exposure to a
counterparty, a card's velocity over the last minute — ask for it over and over. Each dashboard,
each service, each alert rule runs the same query against the same data, on a timer, and pays for
the whole computation every time for the sake of whatever changed since the last run. Between runs
the answer is stale; during a run the database is busy answering a question it answered a moment
ago.

The usual way out is a pipeline: a stream processor computes the answer as data arrives and writes
it to a *second* database, which the applications read. That works, and it costs a job to deploy,
a cluster to run, a serving store to load and keep in step, and an operator who understands all
three — and the answer in the serving store is only as right as the last time the two agreed.

## Asking once instead

Pravaha turns the question around. You register the SQL **once**, under a name. The engine reads
the stores you already have — Aerospike, Cassandra, any JDBC database, PostgreSQL's change log, Kafka
topics, Delta tables, files —
computes the answer incrementally as rows change, and **keeps it**: a maintained, keyed view that
applications read by key, or subscribe to and receive every committed change. There is no job to
submit and no second database, because a view that is already indexed by its key *is* the serving
store.

Late and corrected data are part of the model rather than an exception to it. A row that arrives
late for a window that already published does not produce a second answer or a wrong one: it
produces a **correction** — the old row withdrawn, the new one inserted — that every reader and
subscriber sees as one commit.

And a question the engine cannot keep answering within bounded memory is **refused when it is
registered**, with a code and the reason, instead of being accepted and failing months later when
its state has grown past the heap.
