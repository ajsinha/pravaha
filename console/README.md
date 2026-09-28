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
make run              # http://127.0.0.1:17070, engine at grpc://localhost:19090 and http://localhost:18080
```

Any configuration key can be overridden on the command line, so a second instance pointed somewhere
else needs no file of its own:

```bash
python run_pravaha_web.py --server.port=8099 --engine.url=grpc://staging:19090 \
    --engine.http_url=http://staging:18080
```

**Three ports, and confusing them is the commonest way a first run fails.** The console is on
**17070**, the engine's Flight endpoint on **19090**, and the engine's own HTTP surface — its REST
API and `/actuator/prometheus` — on **18080**. The console uses both engine ports: Flight for
queries, registration, lifecycle and subscriptions; HTTP for the catalog, validation, plans, node
status and metrics.

### Configuration

Every setting lives in `config/application.yaml` as `${VAR:default}`, so each can be set by
environment variable, by `--key=value` on the command line, or in a git-ignored
`config/application.local.yaml`.

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `console.session_secret` | `CONSOLE_SESSION_SECRET` | *empty* | Signs the session cookie, which holds the signed-in person's engine session. Set it where sessions should survive a restart. |
| `console.secure_cookies` | `CONSOLE_SECURE_COOKIES` | `false` | Marks the session cookie `Secure` always. Without it the cookie is `Secure` exactly when the request reached the console over https; set it behind a proxy that terminates TLS without saying so. |
| `server.host` | `CONSOLE_HOST` | `127.0.0.1` | Loopback by default; set `0.0.0.0` only behind something that authenticates. |
| `server.port` | `CONSOLE_PORT` | `17070` | |
| `engine.url` | `PRAVAHA_ENGINE` | `grpc://localhost:19090` | The engine's Flight endpoint. `grpc://` is plaintext and spelled out. |
| `engine.http_url` | `PRAVAHA_ENGINE_HTTP` | `http://localhost:18080` | The engine's HTTP surface, handed to the SDK as `ClientOptions.http_url`: the catalog, sinks, query and view descriptions, validation, plans, status, Prometheus. Without it the workbench still edits and runs, and says validation is unavailable. |
| `engine.pgwire` | `PRAVAHA_PGWIRE` | `localhost:5432` | Shown in the view browser's `psql` snippet. The console never connects to it. |
| `ui.default_role` | `CONSOLE_DEFAULT_ROLE` | `operator` | Where a signed-in person lands until they choose: `analyst`, `operator`, `developer` or `admin`. |
| `ui.tail_buffer` | — | `256` | Changes held per browser on a live view before the oldest are dropped (and said to be). |
| `ui.query_row_limit` | — | `500` | Rows a one-off query or a point query returns to a browser. |
| `ui.lag_warn_seconds` | — | `300` | A watermark further behind than this is a finding on the operations dashboard. |
| `ui.page_size` | — | `25` | Rows per page in the query list. |
| `ui.component_gallery` | `CONSOLE_COMPONENT_GALLERY` | `false` | Serves `/_components`, the component gallery (below). A development aid: off, the route is a 404. |
| `ui.language` | `CONSOLE_LANGUAGE` | `en` | The UI string catalog, `web/i18n/<language>.json`. Only `en` exists; an unknown language falls back to it. |
| `assist.config` | `PRAVAHA_ASSIST_CONFIG` | *empty* | The assistant's configuration (ADR-058): empty is the SDK's own `assist.json` in `$PRAVAHA_CONFIG_DIR` or `~/.config/pravaha`, shared with `pravaha assist`. Followed while the console runs. |
| `assist.usage` | `PRAVAHA_ASSIST_USAGE` | *empty* | The usage ledger the daily budget counts: empty is `assist-usage.json` beside the configuration. |
| `assist.log` | `PRAVAHA_ASSIST_LOG` | *empty* | The console's assist log (JSON Lines): every configuration change and every request. Empty is `console-assist-log.jsonl` beside the configuration. |
| `assist.watch_seconds` | — | `1` | How often the console looks for a change another process stored. |
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
| `/workbench` | analyst | Monaco with Pravaha SQL: catalog-aware completion (streams, columns with types, functions with signatures, scoped to what the statement reads), validation as you type (300 ms debounce) with squiggles and a diagnostics panel in which each `PRV-nnnn` links to its help and offers its fix when the fix is certain, Explain as a plan graph (ELK layout, SVG, operators as nodes, exportable) **with the engine's per-operator numbers drawn inside each node — rows in, rows out and its share of the sampled time — the bottleneck marked by a border, a glyph and its accessible name, and a detail card with state bytes, the watermark and how many samples the share came from; where a node is not counting, the panel says which of the three reasons it is and names `pravaha.metrics.operators` rather than drawing a graph with no numbers on it**, Run over a virtualised grid, Register with keys picked by name and an optional sink, several draft tabs kept in `localStorage`, a snippet library, and **Compare** (design §23.7): the draft against a registered query picked by name, or another draft — the SQL in Monaco's diff editor (side by side, or unified), both plans as graphs with each operator marked added, removed or changed and the same in words, v1's measured totals on v1's side only, and what the engine will do with the new version as far as it can be known before registering (below). Deep links: `?query=`, `?sql=`, `?template=&stream=`, `?panel=explain`, `?panel=diff&against=`. Works as a plain form without JavaScript (Compare needs it). |
| `/catalog` | everyone | Streams with their schemas, event time, lateness and source; registered queries with state, fingerprint, key, retention, sink and the names sharing each computation; sinks with what each accepts and who writes to it. `?tab=` is in the URL. |
| `/catalog/streams/{name}` | everyone | One stream's schema, the queries that read it, and templates against it. |
| `/views`, `/views/{name}` | developer | Every view; for one, a point query (a GET form, value bound as a parameter, answered by the server), its schema, and copy-paste client code — Java SDK, Python SDK, `psql`, CLI — for the view and key being looked at. |
| `/views/{name}/live` | everyone | Committed changes as they arrive, each with its `+1`/`−1` weight; the current rows as the running Z-set sum of the view read on connect plus every change since; an ECharts series of a numeric column over time; a tap filter; honest "sampled — N dropped". |
| `/operations` | operator | A verdict ("is everything healthy, and if not, where?" — and on a backpressured query, *where* is the operator: `hot → Aggregate (n0)`), findings per query with what to do, node status and plugin health, per-query rows in, rate, state against ceiling, view size, watermark lag, **the share of time the lane had nowhere to put a row and how full the inbox is**, and charts of throughput and state. Below them, **Shared lanes**: each lane's blocked share, inbox depth and how many queries are on it, which is how a query blocked *by* a neighbour is told from one blocking itself — and, with lane sharing off, a state that says there are no shared lanes rather than a table of idle ones. Whether this node counts per operator at all is said on the page. Live at 1 Hz over one SSE stream. |
| `/queries`, `/queries/{name}` | operator | The filterable list, and one query's SQL, siblings, lifecycle controls (drop needs the name typed) and raw tail — now with links into the workbench, the plan, the view, its live page, its dead letters and its replacement. |
| `/queries/{name}/replacement` | operator | **Backfill and cutover** (design §23.10, ADR-046). One status call renders the whole screen: the state, what the backfill has read, and the rollback window, because a screen that asked three times would show three moments. **No ETA, no percentage, no bar** — a source does not say how much history it holds, so every denominator would be invented, and the panel says that where the bar would be. What is shown is measured: records of history read, records since the seam, the rate, partitions on the live stream out of how many there are, the candidate's event-time lag (*not known yet*, never `0 s`), the ceiling and whether the reader is paused. Start one from here (the SQL is the engine's to judge, so the form says it does not check it and links to the workbench), throttle it — lowering only, and the engine's refusal of a raise is shown rather than the number quietly clamped — pause, resume, abandon. Cut over and roll back are confirmed by the typed name, the same pair of controls drop uses. The rollback window is a time, and **said to have closed** when it has. Everything needs the administer permission: a reader gets the whole screen read-only with the engine's reason beside each control. |
| `/queries/{name}/debug` | operator, developer | **The time-travel debugger** (design §23.9, ADR-048). Fork the query from one of the positions this node still retains and step the fork by hand: one row, ten, on to the next commit, on to a watermark, or on until a column of the view crosses a value. Each step answers four questions at once — the rows that came in with their weights and offsets, what **every operator** did with them (rows in and out, under the plan's own node ids, so a step and the plan graph are counted once), what the view did with their Z-set weights, and where event time now stands. Below: what each operator is holding, paged and narrowable to one key; the fork's whole view; and an export of the session as a JUnit fixture, shown with the path it belongs at, because the console writes nothing to disk. **DEBUG — sinks disabled** is on the screen for as long as the session is, read from the engine's answer and not from the template: the fork has no sink because a sink is opened for a registration and a fork is not one. The session id is in the URL, so a link reaches the same session. Nothing is polled and no socket is opened — a fork moves only when somebody steps it. Everything here needs the administer permission, reading included, because a fork shows the SQL, the input rows and the operator state. |
| `/plugins` | operator | Every plugin the node can load, from `GET /api/v1/plugins` (`Client.plugins()`): version, the plugin API it needs and whether this engine can host it, what its code can be (source, sink, lookup), the capabilities it declares, the setting names its manifest declares, and its health — shown as *not reported* where no live instance said so, never as healthy. Its bindings are the engine's, filtered by what this identity may see; the stream catalogue and the sink list add each binding's details (event time, what a sink accepts, who writes to it). A binding naming a plugin that is not on the classpath is *not loaded*, not dropped. What the engine still does not publish is listed on the page. From the account menu, Admin and the palette. |
| `/admin/access` | admin | What the engine's policy lets the console's identity do (`GET /api/v1/me/permissions`, `Client.permissions()`): register, read the audit trail, and for every view and stream it can see, full or row-filtered reading and whether it may drop, pause or resume. Read-only — the engine is not where grants live, so there is nothing to edit. `/admin` lands here. |
| `/admin/audit` | admin | The engine's audit trail (`GET /api/v1/audit`, `Client.audit()`), newest first, 50 to a page: filter by principal, view, action, decision and a UTC time window with a plain GET form, page back by the engine's cursor, click a principal or target to filter by it. Every filter and the page are in the URL. When the engine refuses the console's identity, a **Not permitted** state with the engine's reason (and a 403), not an empty table; with `audit: none`, a state that says nothing is recorded. |
| `/admin/tenants` | admin | Each tenant's use against its admission quotas (`GET /api/v1/tenants`, ADR-050): names held, computations, view keys held, the limits in force and the registrations refused per quota since the node started, with the defaults a tenant without its own entry takes. A limit the engine sends as `null` reads **no limit**, never zero; zero is shown as a limit. Every tenant when the engine lets the console's identity read the audit trail, otherwise only its own, and the page says which. A registration refused with `PRV-8020` or `PRV-8021` is a 409 on the workbench and on `POST /queries`, with a note naming the quota and what to do, linked here. |
| `/admin/ai-models` | admin | **The assistant's models** (ADR-058 phase 3): providers (configured, and every type this console can build), models (the key by the name of its variable or file, never its value; **Test** for latency or the normalised error; enable, disable, edit, remove), each profile's chain in fallback order, the default profile, budgets, usage per model and per person, and recent changes (who, what, before and after). Every change is a form through the SDK's `AssistAdmin`, refused as a conflict when the configuration changed since the page was drawn, and applied to the console's one model router before it answers — the next request uses it, no restart. For a person the engine gives the `admin` role, asked of the engine on every request. The workbench's **Describe it** panel and **Explain** (a query's page, beside every refusal code) are the assistant's other surfaces; see [ASSIST.md](../docs/ASSIST.md#in-the-console) |
| `/help` | everyone, unauthenticated | The help index: a collapsible tile per category, a card per topic, rendered from `core/help_catalog.py`. Typing in the search box filters the cards in place (every word must match a card's title, summary, headings, codes or keywords); Enter searches every page. Works as a plain page without JavaScript |
| `/help/topics/{slug}` | everyone, unauthenticated | One help topic: options tables, complete worked examples with their output, the pitfalls, and the shared footer — **Full reference** (the long-form guide behind it), the topics it names, the rest of its category, previous and next |
| `/help/search?q=` | everyone, unauthenticated | Full-text search over every topic and guide, best first (title, then headings, then summary and keywords, then body); a `PRV` code goes straight to its page |
| `/help/{guide}`, `/help/guides` | everyone, unauthenticated | The long-form guides — `docs/*.md`, the Python SDK's README — rendered in place, and a browser of all of them and the worked systems |
| `/help/codes`, `/help/codes/{code}` | everyone, unauthenticated | Every `PRV` code the engine can raise, by range, each linking to everything the shipped documentation says about it and to the errors topic for its range. The engine's own help URLs name a host that does not exist |
| `/help/decisions/{nnn-…}` | everyone, unauthenticated | One architecture decision record, rendered in place; `/help/decisions` is the index |
| `/about` | everyone, unauthenticated | What Pravaha is and the problem it solves, how it works (a theme-aware diagram), what is built and what is not (read from the README), the measured numbers with where each was measured, the decisions and principles, the worked systems, this installation's console and engine versions, and the author and licence (read from the README and `LICENSE`) |

| `/_components` | whoever changes the look | The component gallery, only where `ui.component_gallery` is set, and behind the sign-in: status chips, verdicts, stat tiles, buttons and a refused one, alerts, a table that follows the density, and the eight states of §23.12, each drawn by the `states.js` function the screens call. Review it in each theme (`t`) and density (`d`). It stands in for Storybook — see the §23.20 table |

**Admin** is in the navigation bar; its screens share a tab strip (Access, Audit trail, Plugins).

**Density.** `d`, or the toolbar button beside the theme, switches between comfortable and
compact (design §23.4), kept per viewer in `localStorage` like the theme and applied before the
first paint. Like a theme it is one block of tokens in `base.html` — row height, cell padding,
cell text, card padding, gutters, section spacing — not a zoom: text stays at reading size and
no control shrinks below a 24px target.

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
  help_catalog.py        the help: categories, their extra cards, which topics each screen offers,
                         the footer, reading order, search
  about.py               what the About page says, and which document each part is read from
  admin.py               the audit trail and the permissions page, from the engine
  content/               markdown topics, and codes.py for /help/codes/*
routes/
  base.py                Routes, the brand context, roles, the refusal mapping, the page renderer
  public_routes.py       landing, about, help, tutorials, error codes, health probes
  auth_routes.py         sign-in (with a role choice), sign-out
  api_routes.py          /api/v1 — queries, lifecycle, one-off query, stats, the live tail
  admin_routes.py        /admin/access, /admin/audit, /admin/tenants and their JSON
  product_routes.py      the persona screens and their JSON: catalog, sql, views, ops, plugins,
                         palette
  ui_routes.py           overview, queries, detail, workbench
web/
  templates/             Jinja2; base.html holds the tokens, the chrome and the import map, and
                         _states.html the eight states of 23.12 for a server-rendered screen
  static/js/             the classic per-screen scripts: theme, api, states (the eight), tail, lists
  static/app/            the islands: lib, palette, workbench, diff, plan-graph, grid, charts, live,
                         ops, views, start, and product.css
  static/vendor/         Bootstrap, Bootstrap Icons, fonts, Monaco, ECharts, elkjs, Preact, htm
  i18n/en.json           every UI string the templates, islands, classic scripts and routes show
content/
  topics/                the help topics: front matter (category, order, icon, summary, guide,
                         related, keywords) and the page, in markdown
  examples/              the streams and views every SQL example in topics/ is planned against
  help/ tutorials/ about/   front matter plus, usually, an `include:` of a repository document
tests/
  cdp.py                 a Chrome DevTools Protocol driver over --remote-debugging-pipe, stdlib only
  browser_harness.py     the console on a real port with the fake engine; pages, axe, PNG compare
  fake_engine.py         the one adapter replaced, shared by the product and browser tests; its
                         `fail`, `slow` and `fresh` are how a screen is put in each of the eight
                         states of 23.12
  i18n_scan.py           finds user-visible English that bypasses the string catalog
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
theme changes. The accent is Harvard crimson (`#A51C30`, lightened to `#E47F92` on the dark
ground where the crimson itself is 2.2:1 and fails as text), the bar is a crimson-deep → crimson
→ indigo gradient whose three stops are tokens so white can be checked against each, and `--bad`
is an orange-shifted red 34 ΔE away from the accent — a brand red and an error red beside each
other is a page where nothing is wrong and everything looks it. `test_contrast.py` holds both the
ratios and that distance.

**Signing in is the engine's (ADR-052).** A person signs in with the username and password the
engine holds for them; the console posts them to the engine's `POST /api/v1/auth/login`, keeps only
the session token it answers with (in its signed, HttpOnly, SameSite=Lax cookie), and makes every
engine call for that person with that token — so everything done here is authorised and audited as
the person who did it. The console has no password and no engine token of its own
(`console.password` and `engine.token` are no longer read), verifies no password, key or session
itself, and keeps no user table. Only the landing page, About, the help and the health probes are
open without a session. When the engine says a session is over (`PRV-7016`) the person is sent back
to sign in; when it says a password must change first (`PRV-7018`, only with the engine's
`pravaha.identity.password.force-change`), every page but the password page sends them there. Every
form and every state-changing request carries the session's CSRF token.

People, API keys and sessions are administered in the engine and shown here: **Account** (your
password, your API keys — each shown once — and your sessions) and **Admin · Users, API keys,
Sessions** for an administrator. The first sign-in on a new engine is its bootstrap `admin`.

**Personas pick a landing, not a permission.** Every signed-in person can reach every screen, and
the engine decides what each shows them. The persona comes from the person's own choice (the account
menu, or the account page), remembered for them on that browser, or `ui.default_role`.

**The engine decides what the admin screens show.** The console reaches the engine as the person
signed in, so the audit trail is readable here exactly when the engine's policy lets *that person*
read it (`SecurityPolicy.mayReadAudit`: the `authenticated` policy grants it to the roles in
`pravaha.security.audit-readers`, `admin` by default), and the people screens answer only an
administrator. The console adds no rule
of its own on top — a console-side rule would be enforcing nothing, since the engine is the one
holding the data — and renders the engine's refusal as a designed *Not permitted* state. Every read,
allowed or refused, is on the trail itself.

**The engine's policy decides which actions are offered** (§23.16, "RBAC drives affordances").
The same `GET /api/v1/me/permissions` answer that Admin · Access shows is read when a page with
an action renders: a view the policy refuses to administer shows Pause, Resume and Drop disabled
with the policy's reason on the page, and the palette leaves them out; a refused registration
disables Register in the workbench and in onboarding, with the reason. A grant made in the
deployment's identity system shows on the next page load. An engine that does not answer the
permissions call is an unknown, not a refusal: the controls stay, and the engine — which
re-checks every action anyway — gives its own answer.

**Comparing two versions is a judgement, so it is on the server too.** `POST /api/v1/sql/diff`
takes two sides — `{"query": name}` (the registry's SQL, `GET /api/v1/queries/{name}/plan` for the
running plan and its measured totals, the description for keys and retention) or `{"sql", "label"}`
(a draft, explained and validated) — and `core/authoring.py` matches the plans: read from the root as
branches (down through one-input operators to a scan or a join, a join's inputs paired by position),
operators paired along a branch by **kind**, in order, as many as possible and preferring identical
labels; never by node id, which is a pre-order position. A pair is *changed* when its label, its
output columns or whether it keeps state differ; anything unpaired is added or removed, and an
operator never changes into another kind. The consequences are only what the console can know:
fingerprints when both sides are registered; otherwise plans that differ cannot share, and identical
ones *may* (keys, retention and row filters are in the fingerprint too); output columns, v1's keys
in v2's output, stateful operators changed — and, always, that fill time and state size are not
determinable before registration. The island (`static/app/diff.js`) only draws it: Monaco's diff
editor, the plan graph twice with marks, the lists. Help: `content/topics/compare-versions.md`.

## What the engine now provides, and what it still does not

Every *needs engine support* box the console used to draw has been replaced by the engine API
it asked for, reached through the Python SDK:

| Screen | Was | Now |
|---|---|---|
| Catalog · sinks; register panel | a typed sink name, nothing listed | `GET /api/v1/sinks` (`Client.sinks()`): plugin, row shape, key, emit modes, `acceptsRetractions`, visible writers — never a binding's options. The register panel picks from it |
| Catalog · queries; query page | no key, sink or retention anywhere | `pravaha.list` carries key ordinals, sink, retention and the sink's own state as trailing fields (`RegisteredQuery.key_columns/sink/retention/sink_state/sink_failure`); `GET /api/v1/queries/{name}` (`Client.describe_query`) adds keys by name, the sink's state and its `PRV-8009` failure, the feed's state and a stopped source's code (FEED-1), shared names and the streams it reads |
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
| Operations · backpressure; the plan's operators | listed under *not measured*: the engine sampled neither | B6. Per query, `pravaha_query_backpressure_waits_total`, `..._wait_seconds`, `..._blocked_fraction`, `pravaha_query_inbox_depth`/`_cells`; per shared lane, `pravaha_lane_blocked_fraction`/`_inbox_depth`/`_shared_queries`; `pravaha_metrics_operators_enabled` for whether the node counts operators at all. `GET /api/v1/queries/{name}/plan` carries `operatorMetrics` keyed by the graph's own node ids, a measured `bottleneck`, and a `metricsNote` that distinguishes *not registered* from *counters off* from *measured* |
| The time-travel debugger (screen 10) | no page: the console asserted it offered none | ADR-048. `GET /api/v1/queries/{name}/debug/checkpoints`, `POST /api/v1/queries/{name}/debug`, `GET /api/v1/debug/sessions[/{id}]`, `POST .../step`, `GET .../state[/{operator}]`, `GET .../view`, `POST .../fixture`, `DELETE .../{id}` — reached through the SDK's nine published actions (`Client.debug_fork`, `debug_checkpoints`, `debug_step`, `debug_sessions`, `debug_session`, `debug_state`, `debug_inspect`, `debug_view`, `debug_export`, `debug_end`). Request/response: §23.11 reserves a WebSocket for this screen and ADR-048 did not build one, because a step is asked for and answered |
| Which versions have served a name, on the replacement screen | said and not listed: the Flight status carries no `history` | `Client.replacement_http(name)` — the same status over HTTP, where a list of sentences fits |
| Backfill and cutover (screens 14, 15) | no page: the console asserted it offered none | ADR-046. `GET /api/v1/replacements`, `GET\|POST\|DELETE /api/v1/queries/{name}/replacement`, `.../replacement/cutover\|rollback\|finish`, `.../backfill/throttle\|pause\|resume` — reached through the SDK's published actions (`Client.replace`, `replacement`, `cut_over`, `roll_back`, `finish_replacement`, `abandon_replacement`, `throttle_backfill`, `pause_backfill`, `resume_backfill`) |

Still open, and said so on the screens rather than drawn as zeroes:

| What | Why |
|---|---|
| Commit-latency percentiles | the engine publishes a count and a total, so the mean is exact and a p99 would be invented |
| The storage cluster's own p99 beside our ingest rate (§23.10) | the engine measures what it reads, not what it reads *from*: there is no meter for the source system's latency, so the panel it would go in shows the backfill's own rate alone |
| Adaptive throttling, and an indicator for when it engaged (§23.10) | the engine throttles to the ceiling it was given and never lowers it by itself, so there is nothing to indicate |
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
| Every screen implements the eight states of §23.12 | **audited screen by screen** (the table below): every data-bearing component on every product screen, each state either implemented from the shared `states.js` functions or marked not applicable with its reason, each implemented one driven in a real browser and audited by axe. The eight components themselves are also on the component gallery, photographed in both themes and densities | `test_browser_states.py`, `test_browser_accessibility.py`, `test_browser_visual.py` |
| Light and dark designed and visually regression-tested; both densities | **met, for the pages photographed**: 32 pages — among them the help index, a topic, a connector topic, help search, the guides browser, About, the backfill and cutover screen, the component gallery and the workbench's Compare panel (both whole page) — and four states a screen is in rather than pages — the audit trail's *not permitted*, the catalog when the engine did not answer (§23.12's error state, retry and correlation id included), a backfill in flight and a replacement that has cut over with its rollback window open — × 2 themes × 2 viewports (1280×800, 390×844) × 2 densities (comfortable, compact), **288 baselines**. Documents included verbatim are not photographed | `test_browser_visual.py`, `tests/visual/baselines/` |
| Zero axe violations; WCAG 2.2 AA by manual audit | **zero axe violations** (WCAG 2.0/2.1/2.2 A and AA plus landmark and heading rules) on 36 pages × 2 themes and again in compact density (the help index, a topic, a connector topic, search, the code and guide browsers, About, the backfill and cutover screen, the component gallery and the workbench compared with a registered query among them), and 15 interaction states (open palette, workbench refusal / plan / register / library / result, a plan carrying its per-operator numbers and one operator's detail, the Compare panel never compared / engine unreachable / compared / unified with every operator / out of date / not permitted / partly compared, live view with changes, the audit trail not permitted, controls the policy refuses, drop dialog, a backfill in flight, the cutover confirmation, a replacement that has cut over, each onboarding step), and **53 states of design 23.12 driven screen by screen** (the table below) — which found three empty and error states skipping a heading level. Every token pair is checked for contrast in all three themes, and again as the stale state draws it. **The manual audit has not been done**, and axe finds perhaps a third to a half of what one would — the invisible *Try again* below is one it did not | `test_browser_accessibility.py`, `test_browser_states.py`, `test_contrast.py` |
| Every workflow completable by keyboard alone | **partly proven**: skip link, tab order and a visible focus ring on every stop, the palette (open, filter, act, Escape returns focus), a point query from sign-in to answer with keys only, the admin persona from sign-in through the audit trail (palette, cursor paging, the filter form) with keys only, the drop dialog (Escape returns focus), the draft tabs (arrows, Home, End, Delete), the Compare panel's unified toggle, the help from a word to its full reference (filter, search, open a topic, follow a related one by keyboard, open the guide at its section). Not proven for every workflow: plan-graph node inspection, the register form, onboarding, the backfill and cutover screen | `test_browser_journeys.py` |
| Every view deep-linkable; every filter in the URL | implemented (catalog tabs, the queries filter, a view's key and value, workbench `?query=` `?sql=` `?template=` `?panel=`); exercised by the journeys and product tests, **not audited as a whole** | `test_product.py`, `test_browser_journeys.py` |
| Every destructive action confirmed, audited and reversible where possible | drop, cutover and rollback are each confirmed by the typed name, and a cutover is **reversible for the retention window the replacement was started with** — which the screen shows as a time and says has closed when it has. The engine now serves its audit trail (Admin · Audit), and the product and journey tests read it through the console; **that a drop made from the console appears in it is not asserted end to end** — the real-engine tests reach a Flight-only test server with no HTTP surface | `test_console.py`, `test_product.py` |
| Every error message names the cause, the fix and a correlation id | cause and code everywhere, fix where one is certain; the correlation id is the console's own — **the engine does not mint one** that travels across its surfaces | — |
| Every latency chart shows percentiles; no averages | **not met**: the engine publishes a commit-latency count and sum, so the mean is shown and labelled as a mean | — |
| Stale data visibly stale; partial data visibly partial | implemented and **audited on every screen that can be either** (the table above): a freshness indicator, data greyed inside a dashed fence with a banner giving its age and whether it is reconnecting, "sampled — N dropped", a count that did not load shown as `?` and never as `0`, and an answer about SQL that has since changed marked out of date | `test_browser_states.py`, `test_contrast.py` |
| §23.15 budgets met and gated | **met and gated for what can be measured here** (numbers below). Not measured: a mid-range laptop over a real network, frame times while streaming, memory over hours | `test_browser_performance.py` |
| Onboarding in under five minutes, with real people | **not measured** — it needs people. The journey itself is automated and passes | `test_browser_journeys.py` |
| The eight critical journeys on every PR | **8 of 8 automated, all eight end to end.** Four of them once stopped at an assertion that the console offered nothing, because the engine offered nothing: B5 gave them a readable dead-letter queue, B6 backpressure with per-operator numbers, ADR-046 blue/green cutover with a rollback window, and ADR-048 a debug fork that can be stepped and exported — table below | `test_browser_journeys.py` |
| No secret serialised to the browser | met | `test_console.py`, `test_product.py` |
| Storybook covers every component | **not adopted, and replaced**: Storybook is a Node tool, and the console is no-build and air-gapped by decision (§23.3) — there is no Node toolchain and no component library to host. `/_components`, rendered by the console itself (behind `ui.component_gallery` and the sign-in), shows every design-system component and the eight states from the same `states.js` the screens call, and is axe-audited and photographed like a screen. Beside it, `test_browser_states.py` drives the screens themselves into each state a real engine can put them in and audits that — which a Storybook of components could not do at all. What Storybook also gives and this does not: an interactive knob per prop, and a per-component test runner (§23.18's Vitest has no equivalent without a build) | `test_browser_visual.py`, `test_browser_accessibility.py`, `test_browser_states.py` |

### The eight states of §23.12, screen by screen

Every data-bearing component on every product screen, against the eight states. **Implemented**
means the state is drawn by the shared functions — `static/js/states.js` for the classic scripts,
`states` in `static/app/lib.js` for the islands (the same markup, with the action wired to a
callback), and the macros in `templates/_states.html` for a screen the server renders before any
script runs. **N/A** carries its reason: a state a screen cannot be in is not a gap, and saying
which is the point of the audit. Each implemented cell is driven in a real browser and audited by
axe in `tests/test_browser_states.py` — the engine's answers are made to fail, delay, empty out or
be refused, so no state is faked in the page.

| Screen · component | Loading (first) | Loading (refresh) | Empty (never) | Empty (filtered) | Error | Partial | Stale | Unauthorized |
|---|---|---|---|---|---|---|---|---|
| **Workbench** · diagnostics | N/A — nothing is asked until you type; *Nothing to check yet* is the resting state | `checking…` in the status line, the previous diagnostics kept | *Nothing to check yet* | N/A — no filter | shared error state: the engine's message, a retry that revalidates, the correlation id, and what still works | N/A — one call | N/A — every edit revalidates | N/A — the planner does not ask the policy |
| **Workbench** · Explain | skeleton | the plan stays, `explaining…` beside Explain | *No plan yet* | N/A | shared error state with retry | an operator the engine published no numbers for gets no line rather than a row of zeros, and the panel names which of `metricsNote`'s three answers it is — not registered, `pravaha.metrics.operators` off, or measured | *Out of date* when the SQL has changed since the plan, and the plan greys | N/A for a draft; a registered plan the policy refuses is the error state with `PRV-7002` |
| **Workbench** · Run | skeleton | the rows stay, `running…` | *Nothing run yet* | *No rows* — it ran and matched nothing | shared error state; a refusal (4xx) says a retry will not help instead of offering one | *Showing the first N rows* | *Out of date* when the SQL has changed since the run | N/A — the engine refuses the read, which is the error state |
| **Workbench** · Register | *Registering…* on the button | N/A — a registration is made once, and answers with its own view | the keys appear when the query validates | N/A | shared error state with retry | the sink list not loading is said; the form still registers | N/A | Register disabled with the policy's reason and a link to Admin · Access |
| **Workbench** · Compare (§23.7) | skeleton of the whole panel | the comparison stays, `comparing…` | *Nothing compared yet* / *Nothing to compare with* | N/A | shared error state with retry | *Partly compared*, naming the side the engine would not plan | *Out of date* — the SQL has changed since the comparison | *Not permitted*, per side, with the policy's reason |
| **Workbench** · draft library | N/A — templates come embedded in the page, snippets from `localStorage` | N/A | *None yet. Select some SQL and save it here* | N/A | N/A — nothing is fetched | N/A | N/A | N/A |
| **Catalog** · streams, queries, sinks | N/A — rendered by the server with the engine's answer | N/A — a reload is the refresh | one per tab, each with the action that makes the first one | N/A — the tabs are links, not a filter | shared error state per tab: the message, a retry link, the correlation id | a tab whose count did not load shows `?`, never `0` | N/A — nothing streams | N/A — read-only |
| **Catalog** · one stream | N/A | N/A | *no query reads it* | N/A | the stream itself missing is a 404 page | the registry not answering: the readers are named as unknown | N/A | N/A |
| **Views** · the list | N/A | N/A | *No views yet*, with onboarding and the workbench | filtered in the browser: *Nothing matches this filter*, and Clear | shared error state with retry | N/A | N/A | N/A |
| **View** · point query | skeleton block | N/A — each lookup is a new question, not a refresh of the last | *Ask for a row* | *No row has that key*, with the way back to a scan | shared error state, retry re-asks | the engine's description missing: keys, retention and sink named as unknown | N/A | N/A — a row-filtered read is the engine's answer, not a refusal |
| **View** · client code | N/A — composed by the server | N/A | N/A — every view has code | N/A | the snippets' own error state | N/A | N/A | N/A |
| **Live** (`/views/{name}/live`) | skeleton rows the shape of the view | the stream: freshness says `live`, `quiet Ns`, and the rows stay | *Nothing in the view yet* | the tap filter matching nothing, with Clear | the engine ending the subscription: its reason, and a button that reconnects | *sampled — N dropped*, counted and said | the data greys inside its fence, the banner gives its age and whether it is reconnecting | N/A — a refused subscription arrives as the error state |
| **Operations** | N/A — the server renders the first snapshot | freshness moves at 1 Hz; the numbers stay | *Nothing registered* / *Nothing needs attention*; *No lane is shared* when lane sharing is off, which is not a table of idle lanes | N/A — no filter | the engine unreachable is the verdict itself, on the page and in the tiles | the metrics endpoint missing: said, with the registry's numbers still shown; a meter the node does not publish is `—` or *not published yet*, never a gauge at zero | the dashboard greys and the banner gives its age and that it is reconnecting | N/A — read-only |
| **Queries** · the list | N/A | freshness at 5 s; the rows stay | *No queries yet* | *Nothing matches this filter*, with Clear (server-rendered and again in the browser) | shared error state with retry | N/A | the rows grey and stay, with the age and the failure beside them | N/A |
| **Dead letters** (`/queries/{name}/dead-letters`) | N/A — rendered by the server with the engine's answer | N/A — a reload is the refresh | *no dead letters*, and separately *this node keeps no dead letters* with `pravaha.dlq.directory` unset, which is the queue switched off rather than an empty one | N/A — no filter | shared error state with retry and the correlation id | a record this identity may not see says so, with its size and code, and is never an empty cell | N/A — a page is a point in time | Replay disabled with the policy's reason |
| **Debugger** (`/queries/{name}/debug`) | N/A — the server renders the session, and a fork moves only when somebody steps it | N/A — nothing arrives that was not asked for, so there is nothing to poll and no socket to open | *{name} is not being debugged*, with the three absences and the positions a fork can start from; separately *there is no checkpoint to fork from*, which does not guess which of its three reasons it is; separately *this fork holds no operator state*, which a plan of scans and filters truly does | an operator's state paged to a key it does not hold, with the way back to the whole page | shared error state with retry; a step the engine refuses keeps the session and shows the refusal with its `PRV` code linked | N/A — a session's status, its state and its view are one moment, because nothing moves between the calls | a session the node has released: *that session is over*, with the way to a new one — not an error | the fork control disabled with the policy's reason, and the checkpoint list not even asked for |
| **Replacement** (`/queries/{name}/replacement`) | N/A — the server renders the first status, and one call is the whole screen | freshness at 1 Hz over its own stream; the numbers move and nothing else does | *{name} is not being replaced*, with the form that starts one | N/A — no filter | shared error state with retry; an action the engine refused is shown with its `PRV` code linked | a version history the HTTP surface did not answer, said rather than shown as an empty list — the trail is a second call, because a control-wire row cannot carry a list of sentences | the stream dropping dims the numbers and the banner gives their age | every control disabled with the policy's reason, the screen still readable |
| **Query** · one query | N/A | the raw tail streams | the tail says nothing has been committed since the page opened | N/A | a lifecycle action that fails: the error state with a retry | the engine's description missing: keys, retention, sink and lineage named as unknown | the tail greys, with its age and that it is reconnecting | Pause, Resume and Drop disabled with the policy's reason (§23.16) |
| **Plugins** | N/A | N/A | *No plugin* | N/A | the plugin listing failing is the error state | every other call that failed is named, and the page keeps what answered | N/A | N/A — the engine filters the bindings to what this identity may see |
| **Admin · Access** | N/A | N/A | *nothing this identity may see* | N/A | shared error state with retry | N/A — one call | N/A | N/A — the page *is* the policy's answers, refusals included |
| **Admin · Audit trail** | N/A | N/A | *Nothing recorded yet* | *Nothing matches this filter*, with Clear | shared error state with retry | the window the engine keeps, and what it evicted, said on the page | N/A — a page is a point in time, and paging is by cursor | *Not permitted*, with the engine's reason and a 403 |
| **Help · search** | N/A — the content is local | N/A | *Nothing asked yet* | *Nothing matched*, with Browse and Clear | N/A | N/A | N/A | N/A — the help is public |
| **Onboarding** (`/start`) | the step's own button says it is working | N/A | it *is* the empty state of the console | N/A | shared error state with retry, per step | the engine down: the steps that need it say so | N/A | Register disabled with the policy's reason |
| **Component gallery** (`/_components`) | all eight, drawn by the same functions with sample data, in both themes and densities | | | | | | | |

### The eight journeys, and what each waits on

| §23.18 journey | What the test drives | Waits on (engine) |
|---|---|---|
| First-run onboarding → first query | end to end: declare a stream, pick a question, register, watch it change, find it in the catalog and healthy on operations | — |
| Author, validate, explain, deploy | end to end: type, see the refusal, apply the fix, explain the plan, run, register with a key | — |
| Diagnose a backpressured query from the dashboard | end to end: the verdict names the query **and** the operator (`hot → Aggregate (n0)`), the finding gives the blocked share, the inbox and whose fault it is on a shared lane, the lanes table shows the lane's own view, the plan draws every operator's rows and share with the bottleneck marked three ways, the detail card gives state bytes and how many samples the share came from → pause, see it paused, resume. A second test drives the answer a first run actually meets: the counters off, the setting named, and no operator's numbers invented in their place | — |
| Inspect and act on a DLQ record | end to end: help search to the dead-letters topic, `PRV-4090`, then the query's page to its **Dead letters** screen (newest first, each code linked, the record legible), replay a corrected record and see it marked replayed, replay one that is still malformed and see it fail again and return to the queue as a new entry | — |
| Start and throttle a backfill | end to end: the stream, what feeds it, whether its source can replay (the plugin's capabilities), the queries a reload reaches, then the query's **Replacement** screen — start one, watch the numbers move over the 1 Hz stream, turn the ceiling down, be refused a raise with `PRV-4018` and see that the console sent what was typed, pause, see what was read is kept, resume. It also asserts the rule the screen exists to keep: no percentage, no meter and no bar anywhere on the panel, and an unmeasured lag said to be unknown rather than shown as zero | — |
| Blue/green update with rollback | end to end: open v1 in the workbench, edit it, explain it (no measured totals: they belong to v1's SQL), register v2 beside it, diff v2 against v1 (the changed Filter marked in both plans and listed, two fingerprints, v1's totals on v1's side only, the SQL diff made unified from the keyboard), compare the two views with the same point query, drop the trial registration by its typed name — then the cutover itself: start the replacement of v1's own name, find Cut over disabled with the reason on the page until the seam is reached, type the name (and watch a wrong one stay refused), cut over, read the rollback window as a time, roll back by the typed name, and see v1 answering as it did. v1 never stopped | — |
| Debug a wrong result and export the fixture | end to end, in two parts. First: point-query the row, filter the live view to its key (the filter reaches the engine's subscription), watch a correction arrive as −1 and +1, see the current row as the sum, open the plan. Then the **Debugger** screen — fork the query from a retained position, step one row and read it arriving with its weight, step the row the filter rejects and see the view not move while `n1 Filter` reads `in=1 out=0`, be refused a step the engine cannot parse (`PRV-8015`) without losing the session, export the incident as a named JUnit fixture with the path it belongs at, and end the session. The live query is never paused, never dropped and answers throughout | — |
| Grant a role and verify the affordance appears | Access shows the refusal; the query's controls are disabled with the policy's reason and absent from the palette; after the grant, Access says allowed, the controls return and Pause reaches the engine | nothing for what it proves. The grant itself is made where grants live — the deployment's identity system behind `SecurityPolicy` — so the test changes the fake engine's policy; editing grants, tenants and quotas in the console (screens 20, 21) would need an engine API for them |

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
- **The query page, the palette, the workbench and onboarding offered actions the engine's
  policy refused** — Pause, Resume, Drop and Register whatever `GET /api/v1/me/permissions` said,
  so each failed on click with a 403 (§23.16 says absent or disabled with a reason). Found by the
  grant-a-role journey; they now follow the policy.
- **Stale data fell below 4.5:1** — it faded to 55% opacity, every word in it under AA in both
  themes. It now goes grey (luminance, and so contrast, kept) inside a dashed outline. And the
  *unauthorized* state hid its reason in a disabled button's title, which nobody can reach. Both
  found by the component gallery, the first place those states were in front of axe.
- Arrow keys on the draft tabs could not reach the second draft: the editor took focus back on
  every selection.
- **What the screen-by-screen states audit found**, and this commit fixed: the queries list showed
  *No queries yet* when a **filter** matched none of them, which says there is no data when there is
  plenty and the filter is wrong (the same conflation on the audit trail, and the view list had no
  filtered state at all); a catalog tab whose count failed to load drew **`0`**, which is
  under-reporting rather than saying so; a stream page listed **no readers** when it was the query
  list that had failed, and a query page dropped its keys, retention and sink for the same reason,
  both silently; the live view and the query page's raw tail **dimmed without saying how old** what
  was on screen was or whether anything was reconnecting, and the operations dashboard the same;
  Explain and Run **blanked and refilled** on a second press, and went on showing a plan and rows
  for SQL that had since changed; a server-rendered failure had **no correlation id and no retry**
  anywhere (the catalog, the views, the queries list, the plugins, both admin screens); the query
  page's raw tail was a **blank box** before the first change; a lifecycle action that failed said
  *Retrying will not help* whatever had happened; the workbench's *Validation is unavailable* had
  neither; the tail's state was shown to a person as the word `live` or `stale` **from the code**,
  in English whatever the language; and the empty and error states of the queries list and the
  operations dashboard **skipped from `h1` to `h3`** (axe, found by the audit itself).
- **Two states of the dead-letter screen raised a 500 instead of drawing.** `_states.html` never
  had the `empty` macro that screen called, and its error state passed the failure as `body=`,
  which `error` does not take. So a query with nothing in its queue — which is most queries most
  of the time — a node with no `pravaha.dlq.directory`, and an engine that did not answer each
  turned the page into a stack trace. B5 wrote all three states and no test ever opened one;
  three now do.
- **A button inside an alert had invisible text.** `.alert a` was repointed at the accent when
  every plain link was, which made a link styled as a primary button the accent *on* the accent —
  and the error state's **Try again** was a blue rectangle with nothing on it. axe did not report
  it (it reads the contrast of text against its own background and reported nothing for this
  pair); the first photograph of the state did. Only a link that is not a button wears the accent
  now.

## Strings, and translating them later

UI strings are looked up by key from `web/i18n/en.json` (`core/i18n.py`): `{{ t('nav.catalog') }}` in a
template, `t("palette.label")` in an island, and `PravahaApi.t("states.retry")` in a classic script
under `static/js/` (the `js.*` keys are embedded in every page as JSON, so neither needs a request). Parameters are named — `"{n} columns"` — because word order is the first
thing a translation changes. `ui.language` picks the file; only `en` exists. A route that hands a
page words rather than data — the command palette's entries, a not-found page's "Back to …", the
role labels — passes **keys** and calls `Routes.t` (`routes/base.py`), so the English is in the
catalog like everything else. Two tests fail on a key a template, island or route uses that the
catalog lacks.

**How far it goes:** every template, every island, every classic script and every string a route
composes — about 1,350 keys. Each pass was mechanical and held to one rule: the English did not
change. Each template was rendered with the fake engine before and after and compared
(byte-identical, or identical once whitespace is collapsed and `&#39;` read as an apostrophe), and
the visual baselines still matched.

**A test fails on the next hard-coded string** (`tests/i18n_scan.py`,
`test_no_user_visible_english_bypasses_the_catalog`): it reads every island and classic script and
every template and reports any text a person would read that is not looked up by key — the text
between tags in an `html` template or a Jinja page, and the `title`, `aria-label`, `placeholder` and
`alt` attributes; and, in the scripts, any other string literal that reads as prose (two or more
words, at least one of them not a CSS class the stylesheets define) or as a capitalised label. What
it lets through is a short reviewed allow-list in that file — the product's name, file names, PRV
codes, SQL, the key names a browser reports, units and glyphs, and the example identifiers an input
shows as a placeholder — plus anything marked `translate="no"`, HTML's own word for it, which is how
the component gallery's sample data is spelled. `test_the_english_guard_has_teeth` holds the guard
itself to what it must catch and what it must leave alone.

What is still English in code:

- **Strings the server composes deeper than the routes**: the operations verdict and findings
  (`core/metrics.py`) and the plugins screen's "not published" list (`core/services.py`). Moving
  them means passing keys, not sentences, across the service boundary — the routes now do, and
  these two do not yet.
- **Documentation**: help topics, guides, tutorials and the About page's prose are authored English
  — markdown under `content/`, the repository's own documents, and the README sections About quotes
  — and stay English. Everything around them is in the catalog: the help index, search, the guides
  and codes browsers, the topic page's chrome and footer, the contextual help cards, and every
  heading, label and caption on About (`help.*`, `about.*`). A topic's title and summary are content
  too, so the contextual cards on a screen show them in English.

Some keys are fragments — a sentence split around inline markup, or prose that keeps its line
breaks so the rendered page stayed identical — and read oddly out of context; a translator will want
the template beside the file.

## The help system

Three kinds of help, each with one home, and the index built from them:

- **Topics** — `content/topics/<slug>.md`, one file per topic. A topic registers itself: its front
  matter names its `category`, `order`, `icon`, `summary`, optional `badge`, `keywords` (extra words
  the index search matches), `related` topics and its companion `guide`. Nothing lists topics by
  name, so adding one is adding a file. Each is meant to be enough on its own — an options or
  settings table, complete worked examples (a whole `pravaha:` binding, the SQL, the CLI, SDK or
  `psql` call) each followed by the output it produces, and the pitfalls.
- **Guides** — `content/help/*.md`, each an `include:` of a document in `docs/` (or the Python SDK's
  README). The long form, rendered in place. A topic's `guide:` (a guide and, optionally, a section
  anchor) becomes the **Full reference** link in the footer every topic shares; a category supplies
  the default.
- **Codes** — `/help/codes/{code}`, gathered from the documents, and `/help/codes`, every code in
  `TROUBLESHOOTING.md`'s generated table, grouped by range, each range linking to its errors topic.

`core/help_catalog.py` owns the categories and their order, the cards a category carries that are
not topics (a guide, the code browser), the topics each product screen offers (`SCREEN_HELP`) —
rendered as the cards at the foot of the screen and the **?** beside its heading — and the search.
Every `PRV-nnnn` in a rendered page links to its own page, outside code blocks. A relative link to
an ADR opens it at `/help/decisions/…`.

**How the examples are kept true.** Every ` ```sql ` block in a topic is planned by the real
engine: `pravaha-it`'s `HelpExamplesSqlTest` — the mechanism `CaseStudySqlTest` uses for the case
studies, extended — declares the streams and views in `content/examples/*.properties` exactly as a
node would (the schema grammar, event time, out-of-orderness, lookups) and runs each block through
the planner and plan builder `POST /api/v1/queries/validate` uses. A `CREATE CONTINUOUS QUERY` is
recognised as the engine recognises it, its `KEYED BY` resolved against the plan, and the view it
creates becomes readable by the page's read examples. A comment on the line above a block says what
else it is: `<!-- sql: read -->` (prepared against the views, the path a point read or a `psql`
`SELECT` takes), `<!-- sql: refused PRV-2050 -->` (must be refused with exactly that code),
`<!-- sql: read-refused … -->`, `<!-- sql: parameterised -->` (planned, its placeholders counted).
Run it with `./mvnw -o -pl pravaha-it -am test -Dtest=HelpExamplesSqlTest`.

`tests/test_help.py` checks the rest: a topic whose category does not exist (a page no card links
to), a screen, footer or code range naming a topic that does not exist, every topic being a card
that opens, every internal link on every help page and About resolving — anchors included — every
code mentioned being a link, every code the engine can raise being explained on its range's page,
every SQL block being marked, every `pravaha.*` setting a topic names existing in the engine's
`application.yaml` (commented keys count, as `DocumentationFreshnessTest` counts them) or being
read by name in its source, every YAML example parsing and every connector option in one being a
name that plugin's code reads, search ranking and code lookup, and the gate — help and About public,
nothing secret on them, the screens still behind it.

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
  Chrome through it: the journeys, the axe audit, the eight states screen by screen
  (`test_browser_states.py`), the screenshots and the performance budget. They
  need Chrome or Chromium; `PRAVAHA_CHROME=/path/to/chrome` names one, `PRAVAHA_BROWSER_TESTS=0`
  (or `make test-fast`) switches them off. About eight minutes.

A screenshot that no longer matches fails with the actual image and a diff (changed pixels in red)
in `tests/visual/failures/`. If the change is intended, look at the diff, then `make baselines` and
commit the new images: that look is the review design §23.18 asks for. Baselines record the Chrome
major version that took them; on another version the comparison skips and says so, because text
rasterises differently across versions and that is not a regression in the console.
