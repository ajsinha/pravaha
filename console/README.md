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
| `engine.http_url` | `PRAVAHA_ENGINE_HTTP` | `http://localhost:8080` | The engine's HTTP surface, handed to the SDK as `ClientOptions.http_url`: the catalog, sinks, query and view descriptions, validation, plans, status, Prometheus. Without it the workbench still edits and runs, and says validation is unavailable. |
| `engine.token` | `PRAVAHA_TOKEN` | *empty* | Bearer token, sent to both engine surfaces and never to a browser. One identity for the whole console. |
| `engine.pgwire` | `PRAVAHA_PGWIRE` | `localhost:5432` | Shown in the view browser's `psql` snippet. The console never connects to it. |
| `ui.default_role` | `CONSOLE_DEFAULT_ROLE` | `operator` | Where a signed-in person lands until they choose: `analyst`, `operator`, `developer` or `admin`. |
| `ui.tail_buffer` | — | `256` | Changes held per browser on a live view before the oldest are dropped (and said to be). |
| `ui.query_row_limit` | — | `500` | Rows a one-off query or a point query returns to a browser. |
| `ui.lag_warn_seconds` | — | `300` | A watermark further behind than this is a finding on the operations dashboard. |
| `ui.page_size` | — | `25` | Rows per page in the query list. |
| `ui.language` | `CONSOLE_LANGUAGE` | `en` | The UI string catalog, `web/i18n/<language>.json`. Only `en` exists; an unknown language falls back to it. |
| `logging.level` | `LOG_LEVEL` | `INFO` | |

**The engine does not have to be up.** The console starts anyway and says the engine is
unreachable, on every page. Each screen renders what it can and names what is missing: the workbench
keeps your drafts and edits without validation, the operations dashboard shows the registry without
metrics, a view's page says which call failed. An operator opening a console during an incident
needs it to load and tell them what is wrong.

## The screens

| Route | For | What it does |
|---|---|---|
| `/home` | everyone | Role-aware landing: analyst → workbench, operator → operations, developer → views, admin → Admin · Access. On an engine with nothing registered, `/start` instead. |
| `/start` | a first-time user | Pick or declare a stream, pick a question from templates written against that stream's own columns, register it with keys chosen by name, watch it change. |
| `/workbench` | analyst | Monaco with Pravaha SQL: catalog-aware completion (streams, columns with types, functions with signatures, scoped to what the statement reads), validation as you type (300 ms debounce) with squiggles and a diagnostics panel in which each `PRV-nnnn` links to its help and offers its fix when the fix is certain, Explain as a plan graph (ELK layout, SVG, operators as nodes, exportable), Run over a virtualised grid, Register with keys picked by name and an optional sink, several draft tabs kept in `localStorage`, a snippet library. Deep links: `?query=`, `?sql=`, `?template=&stream=`, `?panel=explain`. Works as a plain form without JavaScript. |
| `/catalog` | everyone | Streams with their schemas, event time, lateness and source; registered queries with state, fingerprint, key, retention, sink and the names sharing each computation; sinks with what each accepts and who writes to it. `?tab=` is in the URL. |
| `/catalog/streams/{name}` | everyone | One stream's schema, the queries that read it, and templates against it. |
| `/views`, `/views/{name}` | developer | Every view; for one, a point query (a GET form, value bound as a parameter, answered by the server), its schema, and copy-paste client code — Java SDK, Python SDK, `psql`, CLI — for the view and key being looked at. |
| `/views/{name}/live` | everyone | Committed changes as they arrive, each with its `+1`/`−1` weight; the current rows as the running Z-set sum of the view read on connect plus every change since; an ECharts series of a numeric column over time; a tap filter; honest "sampled — N dropped". |
| `/operations` | operator | A verdict ("is everything healthy, and if not, where?"), findings per query with what to do, node status and plugin health, per-query rows in, rate, state against ceiling, view size and watermark lag, and charts of throughput and state. Live at 1 Hz over one SSE stream. |
| `/queries`, `/queries/{name}` | operator | The filterable list, and one query's SQL, siblings, lifecycle controls (drop needs the name typed) and raw tail — now with links into the workbench, the plan, the view and its live page. |
| `/plugins` | operator | Every plugin the node can load, from `GET /api/v1/plugins` (`Client.plugins()`): version, the plugin API it needs and whether this engine can host it, what its code can be (source, sink, lookup), the capabilities it declares, the setting names its manifest declares, and its health — shown as *not reported* where no live instance said so, never as healthy. Its bindings are the engine's, filtered by what this identity may see; the stream catalogue and the sink list add each binding's details (event time, what a sink accepts, who writes to it). A binding naming a plugin that is not on the classpath is *not loaded*, not dropped. What the engine still does not publish is listed on the page. From the account menu, Admin and the palette. |
| `/admin/access` | admin | What the engine's policy lets the console's identity do (`GET /api/v1/me/permissions`, `Client.permissions()`): register, read the audit trail, and for every view and stream it can see, full or row-filtered reading and whether it may drop, pause or resume. Read-only — the engine is not where grants live, so there is nothing to edit. `/admin` lands here. |
| `/admin/audit` | admin | The engine's audit trail (`GET /api/v1/audit`, `Client.audit()`), newest first, 50 to a page: filter by principal, view, action, decision and a UTC time window with a plain GET form, page back by the engine's cursor, click a principal or target to filter by it. Every filter and the page are in the URL. When the engine refuses the console's identity, a **Not permitted** state with the engine's reason (and a 403), not an empty table; with `audit: none`, a state that says nothing is recorded. |
| `/help/codes/{code}` | everyone, unauthenticated | Everything the shipped documentation says about one `PRV` code. The engine's own help URLs name a host that does not exist. |

**Admin** is in the navigation bar; its screens share a tab strip (Access, Audit trail, Plugins).

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
  engine.py              the ONLY thing that touches the engine: the Python SDK, over Flight and
                         through the SDK's calls to the engine's published REST endpoints
  services.py            typed calls, the subscription broadcaster, catalog / authoring / view /
                         ops services; no HTTP and no HTML
  authoring.py           plan text → graph, diagnostics → positions and fixes, templates, the
                         SQL language data the editor completes from
  metrics.py             Prometheus text parser, per-query summary, health findings and verdict,
                         a scrape cache so N viewers cost one scrape a second
  snippets.py            client code per view and key, with each language's quoting
  i18n.py                the UI string catalog: t('key', name=value) in templates and islands
  admin.py               the audit trail and the permissions page, from the engine
  content/               markdown topics, and codes.py for /help/codes/*
routes/
  base.py                Routes, the brand context, roles, the refusal mapping, the page renderer
  public_routes.py       landing, about, help, tutorials, error codes, health probes
  auth_routes.py         sign-in (with a role choice), sign-out
  api_routes.py          /api/v1 — queries, lifecycle, one-off query, stats, the live tail
  admin_routes.py        /admin/access, /admin/audit and their JSON
  product_routes.py      the persona screens and their JSON: catalog, sql, views, ops, plugins,
                         palette
  ui_routes.py           overview, queries, detail, workbench
web/
  templates/             Jinja2; base.html holds the tokens, the chrome and the import map
  static/js/             the classic per-screen scripts: theme, api, states, tail, lists
  static/app/            the islands: lib, palette, workbench, plan-graph, grid, charts, live,
                         ops, views, start, and product.css
  static/vendor/         Bootstrap, Bootstrap Icons, fonts, Monaco, ECharts, elkjs, Preact, htm
  i18n/en.json           every UI string the templates, islands and classic scripts show
content/
  help/ tutorials/ about/   front matter plus, usually, an `include:` of a repository document
tests/
  cdp.py                 a Chrome DevTools Protocol driver over --remote-debugging-pipe, stdlib only
  browser_harness.py     the console on a real port with the fake engine; pages, axe, PNG compare
  fake_engine.py         the one adapter replaced, shared by the product and browser tests
  vendor/axe-core/       axe.min.js, for the accessibility audit
  visual/baselines/      the screenshots the visual-regression test compares against
```

Reading order, because the layering is the point: `engine.py` → `services.py` → `routes/` →
`templates/` → `static/app/`. Each knows only the one below it. A template does not know the SDK
exists; a service does not know a browser does; an island knows only the console's own `/api/v1`.

### Vendored libraries

| Library | Version | Where | Used for |
|---|---|---|---|
| Monaco Editor | 0.56.0 | `vendor/monaco/vs/` — the editor core, its contributions chunk (suggest, hover, quick fix), the AMD loader and the editor worker from the prebuilt `min/` tree; language workers left out | the workbench editor, with Pravaha's own SQL language |
| Apache ECharts | 6.1.0 | `vendor/echarts/echarts.common.min.js` — the upstream "common" build (line, bar, scatter, pie; grid, legend, tooltip, dataZoom, graphic), 234 kB gzipped against the full build's 370 | canvas charts on the live and operations screens, fetched after the page's load event |
| elkjs | 0.12.0 | `vendor/elkjs/elk.bundled.js` | deterministic layered layout of the plan graph |
| Preact (+ hooks) | 10.29.8 | `vendor/preact/*.module.js` | the islands' components |
| htm | 3.1.1 | `vendor/htm/htm.module.js` | JSX-like templates with no compiler |
| Bootstrap, Bootstrap Icons | 5.3.8, 1.x | `vendor/bootstrap*/` | layout, dropdowns, modals, icons |

One more is vendored for the tests only, never served: axe-core 4.13.0 (`tests/vendor/axe-core/`,
MPL-2.0), which the accessibility audit injects into each page it checks.

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

**The console uses only the engine's public API** (design §23.2a), and only through the Python
SDK: Flight, and the SDK's calls to the REST endpoints and Prometheus text any client could call.
When a screen wants something the engine does not measure, the screen says so rather than
approximating it. The list is below.

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
named `analyst`, `developer` or `admin` would land there by name.

**The engine decides what the admin screens show.** The console reaches the engine as one identity
(`engine.token`) for everyone signed in, so the audit trail is readable here exactly when the engine's
policy lets *that identity* read it (`SecurityPolicy.mayReadAudit`: the `authenticated` policy grants
it to the roles in `pravaha.security.audit-readers`, `admin` by default). The console adds no rule
of its own on top — a console-side rule would be enforcing nothing, since the engine is the one
holding the data — and renders the engine's refusal as a designed *Not permitted* state. Every read,
allowed or refused, is on the trail itself.

## What the engine now provides, and what it still does not

Every *needs engine support* box the console used to draw has been replaced by the engine API
it asked for, reached through the Python SDK:

| Screen | Was | Now |
|---|---|---|
| Catalog · sinks; register panel | a typed sink name, nothing listed | `GET /api/v1/sinks` (`Client.sinks()`): plugin, row shape, key, emit modes, `acceptsRetractions`, visible writers — never a binding's options. The register panel picks from it |
| Catalog · queries; query page | no key, sink or retention anywhere | `pravaha.list` carries key ordinals, sink and retention as trailing fields (`RegisteredQuery.key_columns/sink/retention`); `GET /api/v1/queries/{name}` (`Client.describe_query`) adds keys by name, the sink's state and its `PRV-8009` failure, shared names and the streams it reads |
| View browser | the query's SQL re-validated to guess the columns, or the view read to keep its header | `GET /api/v1/views/{name}` (`Client.describe_view`): schema, key, retention, sink, fingerprint |
| Register panel | no retention | the optional fifth field of `pravaha.register`: `PT24H`, `P7D`, `forever` |
| Catalog · streams; onboarding | no event time or lateness | `StreamSummary.eventTime`, `outOfOrderness`, `source`; `POST /api/v1/streams` accepts `eventTime` and `outOfOrderness` |
| Workbench diagnostics | positions parsed out of Calcite's English, or guessed from a quoted identifier | `Diagnostic.range`, read by the engine from the parser's own fields; without one the whole first line, and no replacement fix is guessed |
| Workbench plan | the plan rebuilt by counting the text plan's indentation | `POST /api/v1/queries/explain?format=graph` and `GET /api/v1/queries/{name}/plan`: nodes and edges from the engine, and the registered query's measured totals |
| Stream page lineage | queries matched by the stream's name in their SQL | the `reads` of each query's description |
| Operations | no subscribers or checkpoint health | `pravaha_query_subscribers`, `pravaha_query_checkpoint_last_success_timestamp_seconds`, `..._duration_seconds`, `..._failures_total`, and the mean commit latency from `pravaha_query_commit_latency_seconds_count/_sum`; checkpoint failures and a stale checkpoint are findings |
| Everything HTTP | `core/engine.py` spoke HTTP to the engine itself | the SDK's `streams()`, `validate()`, `explain()`, `status()`, `metrics_text()` and the calls above; `engine.py` touches nothing but the SDK |
| Plugins | the status endpoint's names and health, joined by the console with each stream's `source` and each sink's `plugin`, and "bound as, never able to be" | `GET /api/v1/plugins` (`Client.plugins()`): manifest version and required API, compatibility, declared kinds and capabilities, setting names, health with whether a live instance reported it, and the bindings this identity may see — never options |
| An audit screen | no page: the trail was a file the console had no business reading | `GET /api/v1/audit` (`Client.audit()`) behind a new policy question, `mayReadAudit`; reading it is itself audited. And `GET /api/v1/me/permissions` (`Client.permissions()`) for Admin · Access |

Still open, and said so on the screens rather than drawn as zeroes:

| What | Why |
|---|---|
| Per-operator rows, state and watermarks on the plan | the runtime counts per query, not per operator; the plan endpoint says so (`metricsNote`) |
| Lane backpressure | the engine does not sample it |
| Commit-latency percentiles | the engine publishes a count and a total, so the mean is exact and a p99 would be invented |
| Time-travel debugger (§23.9) | not started: needs a checkpoint-fork and step protocol |
| Live health of a plugin loaded from the classpath | the node holds no long-lived instance of it to ask — each binding configures its own — so `GET /api/v1/plugins` answers `UNKNOWN` with `reported: false`, and the screen says *not reported* |
| A classpath plugin's setting descriptions | only a plugin registered with the engine carries a manifest `configSchema`; one found by `ServiceLoader` declares none |
| Per-plugin throughput and errors | no `pravaha_plugin_*` meters |
| Editing grants, tenants and quotas (§23.6 screens 20, 21) | the engine enforces a `SecurityPolicy` a deployment implements against its own identity system; the configured policies have no grants to edit and there are no tenants or quotas to allocate. Admin · Access shows the answers read-only |
| The audit trail across a restart, over HTTP | the engine serves a bounded in-memory window of recent decisions (`pravaha.security.audit-recent`); older ones are in the audit file when `audit: file`, which the console does not read |

## The §23.20 release gate: where it stands

**Not met, and this does not claim it is.** What is now automated runs in `make test` whenever the
machine has Chrome or Chromium (found on the `PATH`, or named by `PRAVAHA_CHROME`), and skips with
that reason when it does not. There is no Node toolchain anywhere in this: Chrome is driven over its
DevTools protocol by `tests/cdp.py`, about 350 lines of standard-library Python.

| §23.20 item | Status | Proven by |
|---|---|---|
| Every screen implements the eight states of §23.12 | implemented, **not audited** screen by screen | — |
| Light and dark designed and visually regression-tested; both densities | **light and dark pass**: 23 pages and the audit trail's *not permitted* state × 2 themes × 2 viewports (1280×800, 390×844), 96 baselines. **Compact density is not photographed.** | `test_browser_visual.py`, `tests/visual/baselines/` |
| Zero axe violations; WCAG 2.2 AA by manual audit | **zero axe violations** (WCAG 2.0/2.1/2.2 A and AA plus landmark and heading rules) on 27 pages × 2 themes and 8 interaction states (open palette, workbench refusal / plan / register / library / result, live view with changes, the audit trail not permitted, drop dialog, each onboarding step); every token pair checked for contrast in all three themes. **The manual audit has not been done**, and axe finds perhaps a third to a half of what one would | `test_browser_accessibility.py`, `test_contrast.py` |
| Every workflow completable by keyboard alone | **partly proven**: skip link, tab order and a visible focus ring on every stop, the palette (open, filter, act, Escape returns focus), a point query from sign-in to answer with keys only, the admin persona from sign-in through the audit trail (palette, cursor paging, the filter form) with keys only, the drop dialog (Escape returns focus), the draft tabs (arrows, Home, End, Delete). Not proven for every workflow: plan-graph node inspection, the register form, onboarding | `test_browser_journeys.py` |
| Every view deep-linkable; every filter in the URL | implemented (catalog tabs, the queries filter, a view's key and value, workbench `?query=` `?sql=` `?template=` `?panel=`); exercised by the journeys and product tests, **not audited as a whole** | `test_product.py`, `test_browser_journeys.py` |
| Every destructive action confirmed, audited and reversible where possible | drop is confirmed by the typed name. The engine now serves its audit trail (Admin · Audit), and the product and journey tests read it through the console; **that a drop made from the console appears in it is not asserted end to end** — the real-engine tests reach a Flight-only test server with no HTTP surface | `test_console.py`, `test_product.py` |
| Every error message names the cause, the fix and a correlation id | cause and code everywhere, fix where one is certain; the correlation id is the console's own — **the engine does not mint one** that travels across its surfaces | — |
| Every latency chart shows percentiles; no averages | **not met**: the engine publishes a commit-latency count and sum, so the mean is shown and labelled as a mean | — |
| Stale data visibly stale; partial data visibly partial | implemented (freshness indicator, dimmed stale tail, "sampled — N dropped"); not audited | — |
| §23.15 budgets met and gated | **met and gated for what can be measured here** (numbers below). Not measured: a mid-range laptop over a real network, frame times while streaming, memory over hours | `test_browser_performance.py` |
| Onboarding in under five minutes, with real people | **not measured** — it needs people. The journey itself is automated and passes | `test_browser_journeys.py` |
| The eight critical journeys on every PR | **2 of 8 automated**: first run to a live view that changes; author, validate, fix, explain, run, register. The other six need engine features that do not exist yet (backpressure sampling, a DLQ, backfill control, blue/green, the time-travel debugger, and a role grant: the console's roles pick a landing, and granting one is not an engine API) | `test_browser_journeys.py` |
| No secret serialised to the browser | met | `test_console.py`, `test_product.py` |
| Storybook covers every component | **not built**, and not planned in that form: there is no component library to host, and no Node toolchain to run it | — |

### Performance, as measured here (loopback, cold cache, Chrome 153)

| | Budget | Measured |
|---|---|---|
| Initial JavaScript, gzipped | ≤ 250 kB | 41–60 kB on every page (workbench 60, live 51, operations 49, the rest 41–46) |
| Lazy JavaScript | Monaco and ECharts lazy | Monaco + ELK 1.46 MB gz on the workbench only; ECharts 235 kB gz on live and operations only, after the load event; neither on any other page (asserted) |
| Time to interactive (to the island's own "ready") | ≤ 2.0 s | 65–260 ms; the workbench, with Monaco up and the query validated, under 1 s |
| Route transition (warm) | ≤ 200 ms | 25–45 ms |
| Main-thread blocking while loading | not in §23.15; held at ≤ 200 ms (workbench ≤ 600) | 0 ms everywhere but the workbench (≈ 50–90 ms, one long task) |

Two things the budget found and this commit fixed: ECharts was fetched before the load event on the
operations and live screens (411 kB gzipped of initial JavaScript, over budget on its own), and the
console sent everything uncompressed (122 kB of script per page where 43 kB would do). The full
build is replaced by the upstream "common" build and loaded after the page is interactive; responses
are gzipped, except the event streams, which a compressing proxy would otherwise hold back.

### What the audits found and fixed

- **Every plain link failed contrast in both themes** — Bootstrap's `#0d6efd` (4.3:1 on the light
  canvas, 3.5:1 on a dark card) was never repointed at the accent token. Links, alerts, `text-danger`
  and the outline-danger button now wear theme tokens.
- **The help pages' "On this page" links were empty** — the template read a key the renderer never
  set; every entry was a zero-size link with no name.
- **The query page's script threw on load** — `tail.js` was never included, so the live tail and the
  typed-name drop dialog never ran in a browser; the plain form still worked, which is why nothing
  noticed. Found by the first journey that pressed Drop.
- The catalog's tab links carried `aria-selected` (not allowed on a link); the workbench's draft
  tabs nested a close button inside a tab and put a non-tab in the tablist; empty-state headings
  skipped from h1 to h3; scrollable code blocks and snippets could not be reached by keyboard;
  the editor's syntax colours came from the data palette (2.7:1 as text); the landing hero put white
  text on the dark theme's light blue; disabled pager links were exposed as enabled.
- Arrow keys on the draft tabs could not reach the second draft: the editor took focus back on
  every selection.

## Strings, and translating them later

UI strings are looked up by key from `web/i18n/en.json` (`core/i18n.py`): `{{ t('nav.catalog') }}` in a
template, `t("palette.label")` in an island, and `PravahaApi.t("states.retry")` in a classic script
under `static/js/` (the `js.*` keys are embedded in every page as JSON, so neither needs a request). Parameters are named — `"{n} columns"` — because word order is the first
thing a translation changes. `ui.language` picks the file; only `en` exists. A test fails on any key a
template or island uses that the catalog lacks.

**How far it goes:** every template, every island but one, and every classic script — about 910
strings. The pass was mechanical and held to one rule: the English did not change. Each template was
rendered with the fake engine before and after and compared (byte-identical, or identical once
whitespace is collapsed and `&#39;` read as an apostrophe), and the visual baselines of every page
at the narrow viewport — where the navigation bar, which did change, is collapsed — still matched.
Three things are not in the catalog yet:

- **`static/app/workbench.js`**, the workbench island. Another change was in flight on it when this
  pass was made; its strings are the next file to move, and `workbench.html` already is.
- **Strings the server composes in Python** and hands to a template as data: the operations
  verdict and findings (`core/metrics.py`), the plugins screen's "not published" list
  (`core/services.py`), the palette's entries and hints, the role labels and blurbs (`ROLES`), and
  a not-found page's "Back to …" label. They are English in code today; moving them means passing
  keys, not sentences, across the service boundary.
- **Documentation**: help topics, tutorials and the about page are repository documents rendered in
  place, not UI strings.

Some keys are fragments — a sentence split around inline markup, or prose that keeps its line
breaks so the rendered page stayed identical — and read oddly out of context; a translator will want
the template beside the file.

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

Three kinds, and each skips with its reason when what it needs is missing:

- `tests/test_console.py` starts a real Pravaha server from the Maven build and drives the console
  against it — a console tested only against a fake engine would prove the fake works. It skips when
  the engine's test classes are not built (`./mvnw -o -pl pravaha-flight -am test-compile`) or
  `JAVA_HOME` is unset.
- `tests/test_product.py` and `tests/test_contrast.py` need nothing: the real application with the
  one engine adapter replaced (`tests/fake_engine.py`), covering every persona screen and JSON
  endpoint, the sign-in gate on each, every page with the engine down, the parser and verdict, the
  snippets, the plan graph, diagnostics and fixes, the plugins screen, the string catalog, the
  air-gap rule, compression, and contrast for every token pair in every theme.
- `tests/test_browser_*.py` run the same application on a loopback port and drive a real headless
  Chrome through it: the journeys, the axe audit, the screenshots and the performance budget. They
  need Chrome or Chromium; `PRAVAHA_CHROME=/path/to/chrome` names one, `PRAVAHA_BROWSER_TESTS=0`
  (or `make test-fast`) switches them off. About three minutes.

A screenshot that no longer matches fails with the actual image and a diff (changed pixels in red)
in `tests/visual/failures/`. If the change is intended, look at the diff, then `make baselines` and
commit the new images: that look is the review design §23.18 asks for. Baselines record the Chrome
major version that took them; on another version the comparison skips and says so, because text
rasterises differently across versions and that is not a regression in the console.
