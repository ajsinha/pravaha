# ADR-032: A parameter is a value, and a value is not a query

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted |
| Date | 2026-09-10 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-025 (sharing by canonical fingerprint), ADR-030 (Flight SQL), ADR-031 (authorization and the soundness rule), §25 |

## Decision

**Prepared statements are supported for request/response, over Flight SQL's native protocol, with
values bound as an Arrow batch.** A statement is planned once and executed many times.

**A `?` belongs in a WHERE clause and nowhere else.** That is the whole rule. A placeholder anywhere
else — the select list, a window size, a group key, an aggregate argument, a table name — is refused
(`PRV-2063`). `HAVING` qualifies, because it is a filter above the aggregate; that is not a special
case in the code, it falls out of the rule.

**For continuous queries, a binding is classified rather than assumed**, by the same rule that
governs security row filters (ADR-031): it is applied at the tap when the view carries every column
it names, and requires a separate registration when it does not. The classification is reported, not
silent.

## The two questions wearing one syntax

`WHERE user_id = ?` means something quite different depending on what is being asked.

In **request/response** it is what everyone expects: plan once, bind per call, get rows back. The
plan does not change between bindings and nothing is retained between them.

In a **continuous query** the same text is ambiguous, because the computation has state and lives
for months. Either the binding is part of what the computation *is* — in which case each distinct
value is a separate computation with its own state — or it is a filter applied where a subscriber
attaches, in which case one computation serves every binding.

The difference is invisible in the SQL and enormous in what it costs. `user_id = ?` against a keyed
view is free at the tap. The same placeholder below an aggregate that removed `user_id` cannot be
applied at the tap at all, and binding a thousand users would mean a thousand computations. An
engine that guessed would be an engine whose memory use depended on a distinction its users could
not see, and the first anyone would know of it is a memory alarm.

## Why WHERE and nothing else

A parameter selects rows. That is the entire job, and every other position a `?` could occupy is a
different query rather than a different binding of one.

A window size is the clearest case and the reason the rule is worth stating rather than assuming. A
five-minute window and an hourly one have no rows in common, so they cannot share a computation or
its state — a parameterised window is not one query with a knob, it is a family of queries. Nobody
needs that: a deployment knows its windows when it writes the query. The same is true of a group key
and of a table name. `SELECT total * ?` is subtler and lands the same way: it computes a different
answer from the same rows.

The rule is written as **one position accepted** rather than a list of positions forbidden. An
earlier version of this code recognised windowing functions by Calcite's internal operator names and
refused those specifically, which was a brittle dependency and left a hole for every position nobody
had thought of. Inverting it means the next place a placeholder could appear is refused by default,
and supporting it becomes a deliberate act with an ADR behind it.

## The rule is the one we already have

A binding can be applied at read or tap time **iff every column it names is present in the view** —
the soundness rule of ADR-031, unchanged. If the column was aggregated away, the rows already mix
the values the binding is meant to separate, and nothing applied afterwards separates them.

This is not a coincidence. A security row filter and a query parameter are the same object: a
predicate supplied outside the query text, applied to a shared computation. Discovering that they
are the same mechanism is why this ADR adds a classification and not a subsystem.

## Alternatives considered

**String interpolation by the caller, which is the status quo.** Rejected on three counts, and only
the first is the famous one. It is an injection surface. It defeats ADR-025's sharing, because two
callers asking the same question about different users produce different SQL text, different plans
and therefore different computations. And it makes a query unrecognisable in a log: one question
asked a thousand ways looks like a thousand questions, so the thing an operator most wants to
aggregate is the thing that has been pre-shredded.

**Substituting values into the SQL server-side.** Safe from injection — the value is typed by then —
but it re-parses and re-plans on every call, which gives up the only mechanical benefit of preparing
at all.

**Binding into the runtime plan IR.** A `Predicate.Parameter` node, resolved before execution.
Rejected because it makes an *unbound plan* representable: a plan that compiles, passes review, and
fails at the first row on a machine already serving traffic. Binding during physical translation
means an unbound plan cannot be constructed.

**Automatically forking state for a continuous query's parameter.** Rejected as a default for the
same reason ADR-031 rejected it for security filters: state multiplied by the number of distinct
bindings, silently.

## How it works

Binding happens when the **physical plan is built**, not when the SQL is parsed. Calcite parses,
validates and optimises once; binding walks the planned tree and produces operators. Two consequences
follow, and both are load-bearing:

- **A bound value never passes through a parser.** By the time it exists there is no parser left to
  reach. That is a stronger guarantee than escaping, and a different one from "we escaped it
  carefully".
- **A bound value and a written literal compile to the same predicate.** Not equivalent — equal.
  There is one code path, so the parameterised form cannot be slower, cannot behave differently, and
  cannot be treated differently by filter pushdown, which is unable to tell them apart. A separate
  parameter predicate would have been the obvious design and would have doubled the shapes every
  operator and every pushdown negotiation has to understand.

Parameter types are **inferred, not declared**. Calcite works them out from context, so `WHERE
user_id = ?` yields STRING because the column says so, and the server sends the parameter schema
when the statement is prepared. Nothing in either SDK guesses a type, which is the part JDBC's
`setObject` gets wrong often enough to be a category of bug.

## Handles carry statements, not sessions

A prepared-statement handle contains the statement and, once bound, its values. The server keeps
nothing between the call that prepares one and the call that fetches rows — the same choice the
plain statement ticket already makes, and kept for the same reasons: no session table to size,
nothing to expire, no client returning after a restart to find its handle gone, and no requirement
that the second call reach the same node as the first.

Flight SQL is built for this: a server may return an *updated* handle when parameters are bound, and
the stock client — which the JDBC driver and the Python, Go and ADBC clients are built on — uses the
updated one. Statelessness costs no compatibility.

Plans are cached, and a cache is not session state. Losing an entry costs the next caller a re-plan
and nothing else, because the handle carries the statement rather than pointing at something the
server is keeping. The cache is bounded (keys come from callers, so an application generating SQL in
a loop would otherwise turn it into a leak at a client-controlled rate) and keyed on the catalogue's
generation, so a plan built when a view had three columns is not reused after it gains a fourth.

**A handle is a plan, never a permission.** Authorization runs on every execution, not once at
preparation, so a statement prepared while a principal had access stops serving rows when that
access is taken away.

## NULL

`WHERE tier = ?` bound to NULL matches nothing, because `x = NULL` is UNKNOWN for every row and a
filter keeps only rows where the predicate is TRUE. This surprises people, and Pravaha follows the
standard anyway: a client library that silently rewrote a comparison into `IS NULL` would be
changing the meaning of the query, which is a worse surprise than an empty result. `IS NULL` says
what it means.

## Error codes

| Code | Means |
|---|---|
| `PRV-2060` | A placeholder has no value bound |
| `PRV-2061` | The number of values does not match the number of placeholders |
| `PRV-2062` | A value's type is not the one inferred for its placeholder |
| `PRV-2063` | A `?` stands where the shape of the plan goes, not a value |
| `PRV-6102` | A handle this server cannot read; prepare the statement again |
| `PRV-6103` | More bound bytes than a handle should carry |

## Consequences

The scope is settled, not partial: WHERE and HAVING take parameters, and nothing else does. A
request to parameterise a select-list expression or a window is a request to reopen this ADR, which
is the right amount of friction for a change that decides how many computations a deployment runs.

The continuous-query half of this decision is recorded and not yet built. Registration does not exist
as a surface, so there is nothing to classify against; when it arrives it uses the rule above and
reports which classification it chose, because the one thing this ADR refuses is for the expensive
case to be the quiet one.
