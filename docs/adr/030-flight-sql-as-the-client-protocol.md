# ADR-030: Arrow Flight SQL is the client protocol, for both interaction models

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-10 |
| Deciders | Ashutosh Sinha |
| Amends | ADR-007, which chose gRPC + Arrow for streaming and Avatica for the control plane |

## Decision

**Arrow Flight SQL is the client protocol.** One server implementation serves both interaction
models: subscribe to a continuous query, and ask a question and iterate the answer.

**Request/response is supported over maintained state, not as general analytics.** Three tiers are
in scope and a fourth is explicitly out:

| Tier | | In scope |
|---|---|---|
| 1 | Point and multi-get against a maintained view | Yes |
| 2 | SQL over maintained views -- filter, project, order, limit, aggregate | Yes |
| 3 | One-shot SQL over a source, terminating, results to the caller | Yes, with stated limits |
| 4 | Ad-hoc federated analytics across stores | **No** |

## Why request/response at all

The engine already computes and indexes the answer. A point read against a maintained view is a
hash probe against lane-local state (§17.1), so the marginal cost of serving it is a protocol, not
an execution engine. Refusing to expose it would mean every adopter putting a serving store in
front of results Pravaha already holds -- which is precisely the second system the product exists to
remove.

The claim is not "we do request/response too"; every database does. It is that **the answer is
already computed**, so the query is a lookup rather than a computation, and the same SQL that
defined the view answers questions about it.

Tier 3 falls out nearly free once tier 2 exists, because a one-shot query is a continuous query with
a bounded source and the client as its sink -- the same operator tree, the same pushdown, the same
codegen. It buys a workflow the streaming incumbents do not have: run the query once, look at the
shape of the answer, then register it as continuous.

## Why tier 4 is refused

Ad-hoc analytics across stores is a different product. It needs spill, a cost-based optimiser for
one-shot execution, and shuffle -- none of which the lane model has, and all of which would compete
for the same threads as the continuous queries. **"Being a database" is an explicit non-goal** (§2),
and §11.1a already positions Trino as the natural *consumer* of the views Pravaha maintains rather
than a competitor to them. A mediocre analytics engine bolted to a good streaming one is worse than
either, because it invites the comparison on ground the product does not hold.

Pravaha will therefore be honest about tier 3's limits: bounded by memory, no spill, and admission
controlled so that an exploratory query cannot damage a production continuous one.

## Why Flight SQL rather than JDBC, Avatica, or a bespoke gRPC service

ADR-007 chose two protocols because no single one did both jobs: Avatica has no push, so streaming
needed gRPC and Arrow beside it. Flight SQL removes that constraint.

**One server implementation, every client.** Flight SQL ships a JDBC driver, an ADBC driver, and
first-party Python, Go, C++ and Rust clients, all maintained upstream. The alternative -- an Avatica
endpoint for BI tools plus a hand-written Python client plus a Go one -- is three implementations of
the same protocol, each with its own bugs and its own release cadence. DBeaver and Tableau connect
through the Flight SQL JDBC driver without Pravaha shipping a driver at all.

**Columnar to the wire.** The engine's rows are already binary and columnar-friendly; Arrow is the
format the receiving end wants, and the conversion is a copy rather than a serialisation round trip
through JSON or Avatica's row protocol.

**It carries streaming too.** Flight is a gRPC service with server streaming, which is what ADR-007
needed gRPC for in the first place. Keeping subscriptions on Flight means one auth story, one TLS
story, one set of connection pools and one thing to operate.

## Alternatives considered

**Keep ADR-007 as written: Avatica plus bespoke gRPC.** Rejected for the reason above -- it is two
protocols where one now suffices, and Avatica's row-oriented protocol is a poor fit for an engine
whose data is already columnar. Avatica remains the better answer for a system whose clients are
exclusively JDBC and whose data is row-oriented; neither is true here.

**Plain JDBC, implemented directly.** Rejected: writing a JDBC driver is a large, thankless, and
easily-botched exercise, and it gives nothing to Python or Go. Flight SQL's JDBC driver is that work
already done and maintained by somebody else.

**REST/JSON only.** Kept as a supplementary surface for application developers (§17.2), not as the
primary one: JSON costs an encode and a decode per row, which is the wrong shape for a client
iterating a million rows and the wrong shape for a subscription.

## Consequences

- ADR-007's *reasoning* stands and its *conclusion* narrows: Arrow remains the wire format, gRPC
  remains the transport, and Flight SQL is the schema on top of both. Avatica is dropped.
- The Wave 7 (E6) gate item "DBeaver connects via Avatica" becomes "DBeaver connects via the Flight
  SQL JDBC driver", which is a weaker commitment for Pravaha and an identical one for the user.
- Read admission control (§17.2, Wave 6) stops being optional. A tier-3 query shares a process with
  production continuous queries, and nothing else stands between them.
- Every read declares its consistency and gets its staleness back (§17.3). A Flight SQL client that
  says nothing gets `CONSISTENT`, which is the mode a person acting on the answer should have.
