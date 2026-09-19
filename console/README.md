# Pravaha console

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`LICENSE`](LICENSE).

The browser product for a running Pravaha engine: where an analyst writes, checks and registers
continuous SQL, where a developer finds a view and copies the code that reads it, where an operator
answers "is everything healthy, and if not, where?", and where anyone watches a view change as the
engine commits. Plus the documentation the engine ships with, rendered in place.

## Running it

```bash
make install          # .venv, the Pravaha Python SDK, and this
make run              # http://127.0.0.1:8090, engine at grpc://localhost:9090 and http://localhost:8080
```

Any configuration key can be overridden on the command line, so a second instance pointed somewhere
else needs no file of its own:

```bash
python run_pravaha_web.py --server.port=8099 --engine.url=grpc://staging:9090 \
    --engine.http_url=http://staging:8080
```

**Three ports, and confusing them is the commonest way a first run fails.** The console is on
**8090**, the engine's Flight endpoint on **9090**, and the engine's own HTTP surface — its REST
API and `/actuator/prometheus` — on **8080**. The console uses both engine ports: Flight for
queries, registration, lifecycle and subscriptions; HTTP for the catalog, validation, plans, node
status and metrics.

### Configuration

Every setting lives in `config/application.yaml` as `${VAR:default}`, so each can be set by
environment variable, by `--key=value` on the command line, or in a git-ignored
`config/application.local.yaml`.

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `console.password` | `CONSOLE_PASSWORD` | *empty* | **The sign-in gate. Set it or nobody can sign in** — the safe failure, because the console can drop queries and a default password is a public one. Only the landing page, the documentation (including `/help/codes/*`) and the health probes are open, so an operator can open the console during an incident and see what is wrong before they find a password. Every screen and endpoint that names a registered query, reads a view, shows the catalog or reaches the engine needs a session. |
| `console.session_secret` | `CONSOLE_SESSION_SECRET` | *empty* | Signs the session cookie. Set it where sessions should survive a restart. |
| `server.host` | `CONSOLE_HOST` | `127.0.0.1` | Loopback by default; set `0.0.0.0` only behind something that authenticates. |
| `server.port` | `CONSOLE_PORT` | `8090` | |
| `engine.url` | `PRAVAHA_ENGINE` | `grpc://localhost:9090` | The engine's Flight endpoint. `grpc://` is plaintext and spelled out. |
| `engine.http_url` | `PRAVAHA_ENGINE_HTTP` | `http://localhost:8080` | The engine's HTTP surface: the catalog, `/validate`, `/explain`, `/status`, Prometheus. Without it the workbench still edits and runs, and says validation is unavailable. |
| `engine.token` | `PRAVAHA_TOKEN` | *empty* | Bearer token, sent to both engine surfaces and never to a browser. One identity for the whole console. |
| `engine.pgwire` | `PRAVAHA_PGWIRE` | `localhost:5432` | Shown in the view browser's `psql` snippet. The console never connects to it. |
| `ui.default_role` | `CONSOLE_DEFAULT_ROLE` | `operator` | Where a signed-in person lands until they choose: `analyst`, `operator` or `developer`. |
| `ui.tail_buffer` | — | `256` | Changes held per browser on a live view before the oldest are dropped (and said to be). |
| `ui.query_row_limit` | — | `500` | Rows a one-off query or a point query returns to a browser. |
| `ui.lag_warn_seconds` | — | `300` | A watermark further behind than this is a finding on the operations dashboard. |
| `ui.page_size` | — | `25` | Rows per page in the query list. |
| `logging.level` | `LOG_LEVEL` | `INFO` | |

**The engine does not have to be up.** The console starts anyway and says the engine is
unreachable, on every page. Each screen renders what it can and names what is missing: the workbench
keeps your drafts and edits without validation, the operations dashboard shows the registry without
metrics, a view's page says which call failed. An operator opening a console during an incident
needs it to load and tell them what is wrong.

## The screens

| Route | For | What it does |
|---|---|---|
| `/home` | everyone | Role-aware landing: analyst → workbench, operator → operations, developer → views. On an engine with nothing registered, `/start` instead. |
| `/start` | a first-time user | Pick or declare a stream, pick a question from templates written against that stream's own columns, register it with keys chosen by name, watch it change. |
| `/workbench` | analyst | Monaco with Pravaha SQL: catalog-aware completion (streams, columns with types, functions with signatures, scoped to what the statement reads), validation as you type (300 ms debounce) with squiggles and a diagnostics panel in which each `PRV-nnnn` links to its help and offers its fix when the fix is certain, Explain as a plan graph (ELK layout, SVG, operators as nodes, exportable), Run over a virtualised grid, Register with keys picked by name and an optional sink, several draft tabs kept in `localStorage`, a snippet library. Deep links: `?query=`, `?sql=`, `?template=&stream=`, `?panel=explain`. Works as a plain form without JavaScript. |
| `/catalog` | everyone | Streams and their schemas; registered queries with state, fingerprint and the names sharing each computation; sinks (see *needs engine support*). `?tab=` is in the URL. |
| `/catalog/streams/{name}` | everyone | One stream's schema, the queries that read it, and templates against it. |
| `/views`, `/views/{name}` | developer | Every view; for one, a point query (a GET form, value bound as a parameter, answered by the server), its schema, and copy-paste client code — Java SDK, Python SDK, `psql`, CLI — for the view and key being looked at. |
| `/views/{name}/live` | everyone | Committed changes as they arrive, each with its `+1`/`−1` weight; the current rows as the running Z-set sum of the view read on connect plus every change since; an ECharts series of a numeric column over time; a tap filter; honest "sampled — N dropped". |
| `/operations` | operator | A verdict ("is everything healthy, and if not, where?"), findings per query with what to do, node status and plugin health, per-query rows in, rate, state against ceiling, view size and watermark lag, and charts of throughput and state. Live at 1 Hz over one SSE stream. |
| `/queries`, `/queries/{name}` | operator | The filterable list, and one query's SQL, siblings, lifecycle controls (drop needs the name typed) and raw tail — now with links into the workbench, the plan, the view and its live page. |
| `/help/codes/{code}` | everyone, unauthenticated | Everything the shipped documentation says about one `PRV` code. The engine's own help URLs name a host that does not exist. |

**Ctrl-K / ⌘K** opens a command palette on every page: jump to any query, view, stream or page,
pause or resume a query (only the actions its state allows are offered), open a query in the
workbench, switch role. Drop is never run from the palette; it goes to the query's page, where the
typed-name confirmation is.

## How it is built

**No-build islands.** FastAPI and Jinja render every page — the shell, the navigation, the theme,
the first answer the engine gave. The interactive parts are *islands*: plain ES modules under
`web/static/app/`, written with Preact and `htm` (components as tagged templates, so there is no JSX
and nothing to compile), resolved by an import map in `base.html`. There is no bundler, no
`package.json` and no Node toolchain: the islands are the files the browser runs.

Why, in one line each: the console must run **air-gapped**, so everything it loads is a file in this
directory; there is **no Node toolchain** on the machines that build Pravaha, and adding one to build
a console would be a second supply chain to audit; and the console ships as **one artefact** — the
Python wheel carries `web/**` as it is. Design §23.3 records the decision.

```
run_pravaha_web.py       entry point: config, services, routes, serve
config/application.yaml  every setting, with ${VAR:default} and a git-ignored .local overlay
core/
  engine.py              the ONLY thing that touches the engine: the SDK over Flight, and the
                         engine's published REST endpoints and Prometheus text over HTTP
  services.py            typed calls, the subscription broadcaster, catalog / authoring / view /
                         ops services; no HTTP and no HTML
  authoring.py           plan text → graph, diagnostics → positions and fixes, templates, the
                         SQL language data the editor completes from
  metrics.py             Prometheus text parser, per-query summary, health findings and verdict,
                         a scrape cache so N viewers cost one scrape a second
  snippets.py            client code per view and key, with each language's quoting
  content/               markdown topics, and codes.py for /help/codes/*
routes/
  base.py                Routes, the brand context, roles, the refusal mapping, the page renderer
  public_routes.py       landing, about, help, tutorials, error codes, health probes
  auth_routes.py         sign-in (with a role choice), sign-out
  api_routes.py          /api/v1 — queries, lifecycle, one-off query, stats, the live tail
  product_routes.py      the persona screens and their JSON: catalog, sql, views, ops, palette
  ui_routes.py           overview, queries, detail, workbench
web/
  templates/             Jinja2; base.html holds the tokens, the chrome and the import map
  static/js/             the classic per-screen scripts: theme, api, states, tail, lists
  static/app/            the islands: lib, palette, workbench, plan-graph, grid, charts, live,
                         ops, views, start, and product.css
  static/vendor/         Bootstrap, Bootstrap Icons, fonts, Monaco, ECharts, elkjs, Preact, htm
content/
  help/ tutorials/ about/   front matter plus, usually, an `include:` of a repository document
```

Reading order, because the layering is the point: `engine.py` → `services.py` → `routes/` →
`templates/` → `static/app/`. Each knows only the one below it. A template does not know the SDK
exists; a service does not know a browser does; an island knows only the console's own `/api/v1`.

### Vendored libraries

| Library | Version | Where | Used for |
|---|---|---|---|
| Monaco Editor | 0.56.0 | `vendor/monaco/vs/` — the editor core, AMD loader and editor worker from the prebuilt `min/` tree; language workers left out | the workbench editor, with Pravaha's own SQL language |
| Apache ECharts | 6.1.0 | `vendor/echarts/echarts.min.js` | canvas charts on the live and operations screens |
| elkjs | 0.12.0 | `vendor/elkjs/elk.bundled.js` | deterministic layered layout of the plan graph |
| Preact (+ hooks) | 10.29.8 | `vendor/preact/*.module.js` | the islands' components |
| htm | 3.1.1 | `vendor/htm/htm.module.js` | JSX-like templates with no compiler |
| Bootstrap, Bootstrap Icons | 5.3.8, 1.x | `vendor/bootstrap*/` | layout, dropdowns, modals, icons |

Each directory carries its upstream licence; `THIRD-PARTY-NOTICES.md` at the repository root lists
them. To update one, fetch its tarball from `registry.npmjs.org` with `curl`, copy the same files,
and bump the version here and in the notices. ELK and ECharts are UMD bundles and Monaco installs an
AMD `define`, so `plan-graph.js` hides `define` while it loads a UMD script — otherwise the bundle
registers as an AMD module and its global never appears.

## Decisions worth knowing before changing it

**Every asset is vendored.** No CDN, so the console renders in an air-gapped deployment. A test
fails on any template, island or stylesheet that makes the browser fetch from another host, and
another on any page whose `<script>`, `<link>` or `<img>` points off this server.

**Every page is rendered by the server first.** The JavaScript makes it live; it does not make it
work. The workbench is a working form, a point query is a GET form, the catalog's tabs are links,
the role switch is a form. A page that is blank until a module loads is blank exactly when somebody
is looking at it because something is not loading.

**The console uses only the engine's public API** (design §23.2a). Flight through the SDK; the
REST endpoints and the Prometheus text any client could call. When a screen needs something the
engine does not publish, the screen says so — a dashed *needs engine support* box naming the exact
call — rather than approximating it. The list is below.

**Judgements live on the server.** Where a diagnostic belongs, which fix is safe, whether a query
is healthy: written once in `core/`, so the workbench, onboarding and operations cannot disagree.

**One engine subscription serves every browser, and one scrape serves every dashboard.** Ten
analysts on one live view are one subscriber on the engine; ten operators on the dashboard cost one
Prometheus scrape a second. The engine's claim is that one question costs one computation, and a
console that multiplied load by open tabs would contradict it.

**A theme is a redefinition of one block of tokens**, and that now includes the data palette
(`--series-1..8`, a colour-blind-checked categorical order stepped separately for dark). Charts,
the editor's syntax colours and the plan graph read the tokens at draw time and redraw when the
theme changes.

**Roles pick a landing, not a permission.** Every signed-in person can reach every screen; the
server checks the session on every call. The shared-secret sign-in names everyone `operator`, so the
role comes from the person's own choice or `ui.default_role`; a principal from an identity provider
named `analyst` or `developer` would land there by name.

## What the console needs from the engine next

Each of these is rendered today as a *needs engine support* state on the screen that wants it.

| Screen | Needs | Shape |
|---|---|---|
| Catalog · sinks; Register panel | List the sink bindings | `GET /api/v1/sinks` → `[{name, plugin, fields:[{name,type}], keyColumns, acceptsRetractions, writers:[query]}]` |
| Catalog · queries; view browser | A query's sink, keys and retention in the listing | the `pravaha.list` action (and a REST twin) carrying `keyColumns`, `sink`, `retention` per query |
| View browser | Describe a view without reading it | `GET /api/v1/views/{name}` → `{schema:[FieldInfo], keyColumns:[int], retention, sink, fingerprint}` |
| Register panel | Retention at registration | a fifth, optional field on `pravaha.register`: an ISO-8601 event-time retention such as `PT24H` |
| Catalog · streams; onboarding | Event time and lateness | `StreamSummary` gaining `eventTime`, `outOfOrderness` and `source` (plugin or none); `POST /api/v1/streams` accepting `eventTime` and `outOfOrderness` |
| Workbench diagnostics | Positions | `Diagnostic` gaining `{startLine, startColumn, endLine, endColumn}` (today they are parsed from Calcite's message, or guessed from a quoted identifier) |
| Workbench plan; query plan | A structured plan with per-operator telemetry | `GET /api/v1/queries/{name}/plan` → `{nodes:[{id, operator, detail, stage}], edges:[{from,to}], metrics:{nodeId:{rowsIn, rowsOut, stateBytes, watermark}}}`, plus `/explain?format=graph` for unregistered SQL |
| Operations | Subscribers per view | gauge `pravaha_query_subscribers{query}` |
| Operations | Checkpoint health | `pravaha_checkpoint_last_success_timestamp_seconds`, `pravaha_checkpoint_duration_seconds`, `pravaha_checkpoint_failures_total` |
| Operations | Backpressure and latency | `pravaha_lane_backpressure_ratio{lane,query}`; histogram `pravaha_query_commit_latency_seconds{query}` |
| Everything HTTP | The REST calls in the SDK | `Client.streams()`, `validate()`, `explain()`, `status()` in the Python SDK, so `core/engine.py` stops being the one place the console speaks HTTP to the engine |
| Time-travel debugger (§23.9) | Everything | not started: needs a checkpoint-fork and step protocol |

## What is still open

The §23.20 release gate is not met and this does not claim it is: there is no Storybook, no visual
regression baseline, no axe run in CI, no Lighthouse budget, and the eight critical journeys are not
automated in a browser. Light and dark, density, keyboard paths, focus handling in the palette,
deep links and the eight states are *implemented* across the new screens; they are not yet
*audited*. The time-travel debugger (§23.9), backfill and cutover control (§23.10), cluster,
plugins and administration screens are not built. "Every error message names a correlation id"
still needs the engine to mint one that travels across pgwire, Flight and the SDKs.

## What ADR-039 item 7 changed (2026-09)

Reading what is registered requires a session — `/overview`, `/queries`, a query's page and their
JSON, and the live tail were reachable anonymously while only the mutating routes were gated,
which was a second, weaker route to data the engine authorizes carefully everywhere else. Drop needs
the query's name typed (§23.16). The correlation id shown on an error reaches the console's own log
line. "No secret is ever serialised to the browser" is a test. See `tests/test_console.py`.

## Tests

```bash
JAVA_HOME=/path/to/jdk21 make test
```

Two files. `tests/test_console.py` starts a real Pravaha server from the Maven build and drives the
console against it — a console tested only against a fake engine would prove the fake works. If the
engine's test classes are not built (`./mvnw -o -pl pravaha-flight -am test-compile`) or `JAVA_HOME`
is unset, those tests skip and say so. `tests/test_product.py` needs no Java: it builds the real
application with the one engine adapter replaced, and covers every persona screen and JSON endpoint,
the sign-in gate on each, every page with the engine down, the Prometheus parser and health verdict,
the snippets' quoting, the plan graph, diagnostics and fixes, the error-code pages, the air-gap rule
and the vendored assets.
