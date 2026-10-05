---
title: What this is
slug: what-it-is
section: About
order: 20
icon: diagram-3
summary: A streaming SQL engine that maintains the answer for as long as the question is registered, and refuses at plan time anything it cannot keep bounded.
---

## A maintained answer, not a submitted job

Register SQL under a name and the engine maintains its view for as long as the
name is registered. Clients read it, or subscribe to its changes. There is no
job to submit, no cluster to wait for, and no serving database — because a view
that is already indexed *is* the serving database.

## Four properties that follow

**Incremental, not recomputed.** A change is a delta. Z-sets carry weights, so a
correction is a retraction and an insert rather than a rescan.

**Event time, not wall clock.** A window closes when the data says it is over,
not when the clock does. A late row corrects the answer instead of being lost or
silently dropped.

**Shared by fingerprint.** Two people asking the same question get one
computation and one copy of the state. Matched on the normalised plan and the
security predicates, not on the SQL text, so formatting differences do not
create a second computation and a different entitlement cannot share one.

**Bounded by construction.** Every operator that could grow without limit has a
bound, and a query with no bound is refused when it is planned rather than at
three in the morning when the heap fills.

## What it is not

It is not a database you load and query. It is not a job runner. And the console
you are reading this in is not the product: it is an operator interface built on
the same public API an integrator uses.
