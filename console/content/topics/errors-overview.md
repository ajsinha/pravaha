---
title: Errors — reading a PRV code, range by range
slug: errors-overview
category: errors
order: 10
icon: exclamation-octagon
summary: "Every failure Pravaha raises carries a stable PRV-nnnn code: what the nine ranges mean, how a message is built, which link in it does not work, and how to take a code from a log to a fix."
badge: START HERE
audience: Everyone
keywords: [error, code, prv, range, helpUrl, pravaha.docs.base-url, sqlstate, grpc status, http status, exit code, support]
guide: troubleshooting#the-ranges
related: [errors-sql, errors-state, errors-security, errors-registry, sql-refusals]
---

Every failure the engine raises is a `PravahaException` with an **error code**: a number in the
form `PRV-nnnn` and a constant name such as `SQL_UNBOUNDED_STATE`. The code is the stable part. A
message may be rewritten to say more; the code behind it is never renumbered and never reused, so it
is what belongs in a runbook, a log filter, an alert rule and a support ticket. A test in the build
(`ErrorCodeUniquenessTest`) fails if two different failures ever answer to one number — which happened
once, and is why several codes explain in their own source why they sit where they do.

This page is how to read one. Each range has a page of its own listing **every** code in it: what it
means, why the engine refuses rather than guessing, and what to do.

## The nine ranges

The first digit says which part of the system noticed the problem, and therefore where the fix is.

| Range | Area | Explained on | Typically fixed by |
|---|---|---|---|
| PRV-1xxx | Configuration, the REST API's own request checks, and the client SDKs | [Configuration, API and client codes](/help/topics/errors-config) | Editing a file or a call |
| PRV-2xxx | SQL — parsing, validation, planning, what the engine will and will not run | [SQL codes](/help/topics/errors-sql) | Rewriting the query |
| PRV-3xxx | Runtime and code generation | [Runtime codes](/help/topics/errors-runtime) | Sizing, or the data |
| PRV-4xxx | State, backfill and serving (reading views) | [State and serving codes](/help/topics/errors-state) | Sizing, retention, or retrying |
| PRV-5xxx | Plugins — sources, lookups and sinks | [Plugin codes](/help/topics/errors-plugins) | The binding's options, or the external system |
| PRV-6xxx | The gateways: Arrow Flight SQL (61nn) and the PostgreSQL gateway (62nn) | [Gateway codes](/help/topics/errors-gateway) | The client, or the protocol it speaks |
| PRV-7xxx | Security | [Security codes](/help/topics/errors-security) | A credential, a grant, or the security settings |
| PRV-8xxx | The query registry and the embedded engine | [Registry codes](/help/topics/errors-registry) | The query's lifecycle, its name, or its sink |
| PRV-9xxx | Clustering and partition ownership | [Cluster codes](/help/topics/errors-cluster) | The cluster settings |

The range is also what a surface derives its status from, so a new code is classified correctly
without anybody maintaining a second table (see [the statuses](#the-status-that-travels-with-a-code)
below). The [code browser](/help/codes) lists all of them in one table.

## How a message is built

A message is the code, **two spaces**, and a sentence that says what happened and, wherever there is
one, what to do:

```text
PRV-2050  this GROUP BY is over a windowed stream, and the window is found by its columns' names,
window_start and window_end, among the grouped columns: here they are user_id. Group by both, and
keep their names -- a SELECT that renames one (window_end AS closes) hides it; rename it in an outer
query or in the client instead. Without the window the aggregate spans every window at once, which
is the unbounded case wearing a window's clothes.
```

Three rules hold for every message, and knowing them saves time:

1. **The code is first**, so a log filter on `PRV-` finds every Pravaha failure and nothing else.
2. **The sentence names the thing** — the stream, the column, the setting, the file and line — and,
   where the fix is certain, the fix. `PRV-3001` names the exact setting to raise; `PRV-2070` says
   which statement shape it expected and the line and column where reading stopped.
3. **It never leaks what the caller may not know.** A name that does not exist is refused without
   listing the names that do (`PRV-2002`, `PRV-2003`), and `PRV-7001` never says *why* a credential
   was not accepted — "expired" versus "unknown" versus "bad signature" is three bits of an oracle
   for whoever is guessing.

!!! note "Where the link at the end of a message points, and who decides"
    The engine's `helpUrl()` — shown by the CLI under the message, and returned as `helpUrl` in the
    REST API's error body — is **whatever the deployment set `pravaha.docs.base-url` to**, with the
    code on the end. It has no default: unset, the engine prints no link at all and says where to
    look instead, because a link that does not resolve reads like a network problem at exactly the
    wrong moment (that is what it used to do — it pointed at a host nobody had registered, finding
    DOCX-21).

    Point it at this console. Every code has a page here, and every code the console shows you is
    already a link to it.

## Where codes appear, and what each surface adds

| Surface | What you see | Status |
|---|---|---|
| **CLI** (`pravaha ...`) | The message in red on stderr, then the help URL dimmed beneath it | Exit code `1` for a refusal, `2` for a usage mistake |
| **REST API** (`/api/v1/...`) | A JSON body `{"code", "message", "helpUrl", "timestamp", "path"}` | Configuration, SQL and registry codes → `400`; security → `403`; an unknown query or view (`PRV-8002`, `PRV-4023`) → `404`; runtime, state, plugin, gateway and cluster → `500` |
| **Validation** (`POST /api/v1/queries/validate`) | `200` with `valid: false` and a diagnostic `{code, message, helpUrl, severity, range}` — asking "is this valid?" and hearing "no, because" is a successful answer | `200` |
| **Arrow Flight SQL** (every SDK, JDBC, ADBC) | The message as the status description, and the code's *name* in a trailer so an SDK can rebuild the same code rather than stamping its own | A gRPC status chosen by the code — below |
| **PostgreSQL gateway** (`psql`, Grafana, any ORM) | An `ErrorResponse` with the message and a SQLSTATE chosen by the code | SQLSTATE — below |
| **Java and Python SDKs** | A `PravahaException` / `PravahaError` with the same code; the Python error carries `.code` and `.retryable` | — |
| **This console** | Workbench diagnostics, a query's failure line, a sink's detach reason, the audit trail's refusal — each code a link to its page | — |

### The status that travels with a code

A client decides what to do from the status *before* anyone reads the message: gRPC clients and
JDBC drivers retry `RESOURCE_EXHAUSTED`, re-authenticate on `UNAUTHENTICATED`, give up on
`INVALID_ARGUMENT`. So the gateways map codes deliberately, and the two gateways use the same
taxonomy code for code:

| Code(s) | Flight status | PostgreSQL SQLSTATE | Meaning for the client |
|---|---|---|---|
| PRV-7001 | `UNAUTHENTICATED` | `28000` | Present a (fresh) credential |
| PRV-7002, PRV-7003, PRV-7005 | `UNAUTHORIZED` | `42501` | A new credential will not help; a grant, or a corrected policy, might |
| PRV-4026, PRV-4027, PRV-4028 | `RESOURCE_EXHAUSTED` | `53000` | Back off and retry |
| PRV-4021, PRV-4029 | `TIMED_OUT` | `57014` | The read ran out of time |
| PRV-4023 | `NOT_FOUND` | `42P01` | No such view |
| PRV-6102 | `NOT_FOUND` | — | Prepare the statement again |
| PRV-4024 | — | `54000` | Narrow the read |
| PRV-6211 | — | `25006` | Send it over Flight SQL instead |
| everything else | `INVALID_ARGUMENT` | `42000` (or `0A000` for what the gateway declines) | Fix the request |

## From a log line to a fix — worked

Suppose a registration is refused after a deploy, and the message someone pastes into a ticket
begins:

```text
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of
distinct keys and never shrinks. One row per key is fine at a thousand keys and fatal at a hundred
million, and the failure arrives weeks after deployment.
```

1. **Open the code.** Type `PRV-2050` into the help search, or go to `/help/codes/PRV-2050`. The
   first digit, `2`, already says: this is the SQL, not the node.
2. **Read the range page** — [SQL codes](/help/topics/errors-sql#prv-2050-unbounded-state) — which
   says an unwindowed `GROUP BY` over a stream keeps one accumulator per key for ever, and that the
   fix is almost always a window.
3. **Reproduce it without registering anything.** The workbench, `pravaha-engine validate` or
   `POST /api/v1/queries/validate` plans the SQL and returns the same code:

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, SUM(amount) AS spend
FROM txn
GROUP BY user_id
```

4. **Fix and validate again:**

```sql
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end
```

Validation now answers `valid: true`, no diagnostics, and the four output columns.

The same refusal as it reaches each surface:

```bash
# The CLI: message, then the (unresolvable) help URL; exit code 1.
pravaha query --sql "SELECT user_id, SUM(amount) FROM txn GROUP BY user_id"
```

```text
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of ...
  look PRV-2050 up in the console's help under Errors, or in docs/guides/TROUBLESHOOTING.md
```

```bash
curl -s -X POST http://localhost:18080/api/v1/queries/validate \
     -H 'Content-Type: application/json' \
     -d '{"sql": "SELECT user_id, SUM(amount) FROM txn GROUP BY user_id"}'
```

```text
{"valid":false,"diagnostics":[{"code":"PRV-2050","message":"PRV-2050  GROUP BY user_id has no
bound on its key space, ...","helpUrl":"","severity":"error",
"range":null}],"outputFields":[],"elapsedMicros":1840}
```

Abbreviated where marked, and `elapsedMicros` varies. It is a `200`: the question "is this valid?"
was answered.

## A refusal is a feature

Pravaha refuses at plan time, with a code and an explanation, rather than accepting a query and
approximating it. A refusal costs a developer five minutes; a query that runs and returns a
plausible wrong number costs whatever was decided on the strength of it. So most codes in the 2xxx
range are the engine working, not the engine failing — and the few that are genuine defects say so.

Every refusal carries a code. The last one that did not — a self-join, one stream on both sides of a
join — now runs; see [SQL codes](/help/topics/errors-sql#self-joins-run).

## Reporting a code

When a code needs somebody else — a platform team, a support ticket — send:

- **the whole message**, not just the code: the sentence names the stream, column, setting or file;
- **where it surfaced** (CLI, REST, Flight client, `psql`, console, log) — the status differs;
- **the SQL**, if it is a 2xxx, and the stream's declaration (`GET /api/v1/streams/{name}`);
- **the node's version** from `GET /api/v1/status` or this console's About page;
- for a 3xxx, 4xxx or 5xxx, the few log lines before it — the cause is often one line earlier.

## Where next

- [Every code in one table](/help/codes)
- [What the engine refuses, and why](/help/topics/sql-refusals)
- [Troubleshooting by symptom](/help/troubleshooting) — "nothing is happening", "the numbers are wrong"
