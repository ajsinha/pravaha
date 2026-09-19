---
title: The console, screen by screen
slug: console-tour
category: start
order: 30
icon: window-sidebar
summary: "Every screen in this console — what it is for, who lands there, what it shows, the deep links it understands — and what the engine does not publish yet."
audience: Everyone
keywords: [console, ui, workbench, catalog, views, live, operations, queries, plugins, admin, audit, palette, deep link, roles, ports]
guide: quickstart#7-open-the-console
related: [start-here, choosing-a-client, sql-reference, point-reads, metrics-alerts, authorization]
---

The console is the browser product for a running engine: where an analyst writes, checks and
registers continuous SQL, where a developer finds a view and copies the code that reads it, where an
operator answers *"is everything healthy, and if not, where?"*, and where anyone watches a view
change as the engine commits. This page walks every screen.

It is a **separate process** (ADR-024) that reaches the engine **only through the published Python
SDK** — Arrow Flight on the engine's port 9090 for queries, registration, lifecycle and
subscriptions, and the engine's HTTP API on 8080 for the catalogue, validation, plans, status and
metrics. It holds no data of its own and cannot reach past the public API, so anything it can do,
an integrator's program can do too.

## How you get in

| Port | Process | What it is |
|---|---|---|
| **8090** | the console | this browser product |
| **9090** | the engine | Arrow Flight SQL — every SDK, the CLI |
| **8080** | the engine | its HTTP API and `/actuator/prometheus` |
| **5432** | the engine | the PostgreSQL gateway, when `pravaha.pgwire.enabled` is set |

The landing page, the help (including every `/help/codes/...` page), About and the health probes are
**public**: an operator opening the console during an incident needs it to load and say what is
wrong before they have found a password. **Everything that names a registered query, reads a view,
shows the catalogue or reaches the engine needs a session.** Sign in with the console password
(`console.password`, environment `CONSOLE_PASSWORD`); if none is set, nobody can sign in — the safe
failure for a tool that can drop queries.

At sign-in you choose a **role**, and the role decides where you land, not what you may do:

| Role | Lands on | For |
|---|---|---|
| analyst | Workbench | writing and registering SQL |
| operator | Operations | health, lag, state, checkpoints |
| developer | Views | reading answers, copying client code |
| admin | Admin · Access | what the console's engine identity may do, the audit trail |

The engine decides what the console may see: the console reaches it as **one identity**
(`engine.token`), and every screen shows exactly what the engine's policy lets that identity see.

## Home and onboarding

| Route | What it does |
|---|---|
| `/` | What Pravaha is — answers even with the engine down |
| `/home` | Your role's landing. On an engine with nothing registered, `/start` instead |
| `/start` | First run: pick or declare a stream (its event time and lateness included), pick a question from templates written against that stream's own columns, register it with keys chosen by name, and watch the view change |

## Workbench — write, check, explain, run, register

`/workbench` is the analyst's screen. A Monaco editor with Pravaha's own SQL language:

- **Completion from the catalogue** — streams, their columns with types, functions with signatures,
  scoped to what the statement reads.
- **Validation as you type** (300 ms after you stop), with squiggles at the engine's own reported
  position and a diagnostics panel. Each `PRV-nnnn` links to its help page, and offers its fix when
  the fix is certain.
- **Explain** draws the engine's physical plan as a graph (operators as nodes; exportable).
- **Run** executes a one-off query over a view into a virtualised grid (up to `ui.query_row_limit`
  rows, 500 by default).
- **Register** names the query, picks the key columns by name, and optionally a sink and a
  retention. It is the same registration as `CREATE CONTINUOUS QUERY`.
- **Drafts** are kept per browser tab in local storage; a snippet library holds common shapes.
- **Compare** puts the draft beside a registered query (v1, picked by name) or another draft: the
  SQL in a diff editor, both plans with each operator marked added, removed or changed, and what the
  engine will do with the new version — [Comparing two versions](/help/topics/compare-versions).

Deep links, so a colleague can be sent straight to the thing:

| Link | Opens |
|---|---|
| `/workbench?query=minute_spend` | a registered query's SQL |
| `/workbench?sql=SELECT+...` | a piece of SQL |
| `/workbench?template=<id>&stream=txn` | a library template written against that stream |
| `/workbench?panel=explain` | with the plan panel open |
| `/workbench?query=v1&panel=diff&against=v1` | a query compared with its registered self, ready to edit |

It also works as a plain form with JavaScript off: run and register still submit.

## Catalog — what exists

`/catalog` has three tabs, and the tab is in the URL (`?tab=streams`, `?tab=queries`, `?tab=sinks`):

| Tab | Shows |
|---|---|
| Streams | each stream's schema, event-time column, out-of-orderness, and the source plugin feeding it (the plugin's name only, never its options) |
| Queries | every registered query: state, fingerprint, key, retention, sink, and **the other names sharing its computation** |
| Sinks | every sink binding: plugin, row shape, key, emit modes, whether it accepts retractions, delivery guarantee, and who writes to it |

`/catalog/streams/{name}` is one stream: its schema, the queries that read it (from each query's
own description, not a text search), and templates written against it.

## Views — read an answer, copy the code

`/views` lists every view you may read. `/views/{name}` is one view: its schema, key, retention and
fingerprint, a **point query** (a plain GET form — `?key=user_id&value=u1` — bound as a parameter
and answered by the server), and **copy-paste client code** for exactly that view and key: Java SDK,
Python SDK, `psql`, and the CLI. The `psql` snippet uses `engine.pgwire` (default
`localhost:5432`) — the console itself never connects there.

## Live — watch a view change

`/views/{name}/live` subscribes to the view and shows each committed change as it arrives, **with
its weight** — `+1` for a row appearing, `−1` for one withdrawn. The current rows are the running
Z-set sum of the view read on connect plus every change since. A numeric column can be charted over
time; a tap filter narrows the stream at the engine. If the browser falls behind, the page says
"sampled — N dropped" rather than pretending (`ui.tail_buffer`, 256 changes per browser).

Ten people on one live view cost the engine **one** subscription: the console fans a single
subscription out to every browser.

## Operations — is everything healthy?

`/operations` opens with a **verdict** and a list of **findings** per query with what to do about
each, then node status and plugin health, then per-query rows in, rate, state held against its
ceiling, view size, watermark lag, subscribers, checkpoint health and mean commit latency, with
charts. It updates once a second over one event stream, from one Prometheus scrape per second
however many operators are watching.

A watermark further behind than `ui.lag_warn_seconds` (300 by default) is a finding; so are
checkpoint failures and a stale checkpoint. A query whose source has stopped is a **critical**
finding, with the code linked to its help page, and the verdict names it. Commit latency is shown as a **mean**, labelled as one:
the engine publishes a count and a total, so a percentile would be invented.

`/overview` is the compact version: is it up, what is registered, what is shared, the busiest
queries.

## Queries — the list and one query

`/queries` is filterable, sortable and paged, and **every filter is in the URL**
(`?search=spend&state=RUNNING&sort=-rows_in`). `/queries/{name}` is one query: its SQL, key,
retention, sink (and its PRV-8009 failure if the sink was detached), its **source** — receiving rows,
or stopped with the code, the stream#partition and the time (a stopped source leaves the query
`RUNNING`; the list marks it "source stopped") — the other names sharing it,
the streams it reads, lifecycle controls, and a raw live tail — with links into the workbench, its
plan, its view and its live page.

**Drop needs the query's name typed**, because it removes the name for everybody reading through it.
Pause and resume do not; they are reversible.

## Plugins

`/plugins` is every plugin the node can load, from the engine's `GET /api/v1/plugins`: its version,
the plugin API it needs and whether this engine can host it, whether its code can be a source, a
sink or a lookup, the capabilities it declares, the setting names in its manifest, and its health —
shown as *not reported* where no live instance said so, never as healthy. Its bindings are listed
as the engine reports them to this identity, never with their options (which may hold passwords).

## Admin

| Route | What it does |
|---|---|
| `/admin/access` | What the engine's policy lets the console's identity do: register, read the audit trail, and for every view and stream, full or row-filtered reading and whether it may drop, pause or resume. Read-only — grants live in the deployment's own identity system |
| `/admin/audit` | The engine's audit trail, newest first, 50 to a page, filtered by `principal`, `view`, `action`, `decision` and a UTC window (`since`, `until`) — all in the URL. When the engine refuses the console's identity, a designed **Not permitted** state with the engine's reason |

Reading the trail is itself audited.

## Help, everywhere

- Every screen has a **?** beside its title, opening the help topic for that screen, and a row of
  help cards at the bottom for the questions it usually raises.
- Every `PRV-nnnn` anywhere in the console is a link to that code's page.
- **Ctrl-K / ⌘K** opens a command palette on every page: jump to any query, view, stream or page;
  pause or resume a query (only the actions its state allows); open a query in the workbench;
  switch role. Drop is never run from the palette — it goes to the query's page, where the
  typed-name confirmation is.

## When the engine is down

The console starts anyway and says so on every page. Each screen renders what it can and names what
is missing: the workbench keeps your drafts and edits without validation, operations shows the
registry without metrics, a view's page says which call failed.

!!! note "What the engine does not publish yet"
    Per-operator rows and state on the plan (the runtime counts per query), lane backpressure,
    commit-latency percentiles, the live health of a classpath plugin, per-plugin throughput, and
    grants to edit. Each screen says so where the number would be, rather than drawing a zero.

## Settings that change what you see

| Setting | Default | Effect |
|---|---|---|
| `engine.url` | `grpc://localhost:9090` | the engine's Flight endpoint |
| `engine.http_url` | `http://localhost:8080` | the engine's HTTP surface; without it validation and plans are unavailable |
| `engine.token` | empty | the console's single engine identity, never sent to a browser |
| `engine.pgwire` | `localhost:5432` | shown in the `psql` snippets only |
| `ui.default_role` | `operator` | where a signed-in person lands until they choose |
| `ui.tail_buffer` | 256 | changes held per browser on a live view |
| `ui.query_row_limit` | 500 | rows a one-off or point query returns to a browser |
| `ui.lag_warn_seconds` | 300 | watermark lag that becomes a finding |

These are the console's own settings (in the console's `config/application.yaml`), not the
engine's.

## Where next

- [Your first maintained view](/help/topics/first-view) — the engine side of what these screens show
- [Point reads](/help/topics/point-reads) and [subscriptions](/help/topics/subscriptions)
- [Metrics and alerts](/help/topics/metrics-alerts) — the numbers behind Operations
- [Authorization](/help/topics/authorization) — why the console shows what it shows
