# Pravaha console

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`LICENSE`](LICENSE).

An operator console for a running Pravaha engine: what is registered, what state it is in, what it
is producing right now, and the controls to change it. Plus the documentation the engine ships
with, rendered in place.

## Running it

```bash
make install          # .venv, the Pravaha Python SDK, and this
make run              # http://127.0.0.1:8090, engine at grpc://localhost:9090
```

Any configuration key can be overridden on the command line, so a second instance pointed somewhere
else needs no file of its own:

```bash
python run_pravaha_web.py --server.port=8099 --engine.url=grpc://staging:9090
```

**Three ports, and confusing them is the commonest way a first run fails.** The console is on
**8090**, the engine's Flight endpoint on **9090**, and the engine's own HTTP/actuator surface on
**8080**.

### Configuration

Every setting lives in `config/application.yaml` as `${VAR:default}`, so each can be set by
environment variable, by `--key=value` on the command line, or in a git-ignored
`config/application.local.yaml`.

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `console.password` | `CONSOLE_PASSWORD` | *empty* | **The sign-in gate. Set it or nobody can sign in** — which is the safe failure, because the console can drop queries and a default password is a public one. Only the landing page, the documentation and the health probes are ungated, deliberately, so an operator can open the console during an incident and see what is wrong before they find a password. Everything that names a registered query — the list, a query's own SQL, its live tail — requires a session, the same as registering, pausing, dropping and running one: which queries exist and what they say is the engine's own data, read with the console's one shared engine identity, not console chrome safe to hand to whoever can reach the port. |
| `console.session_secret` | `CONSOLE_SESSION_SECRET` | *empty* | Signs the session cookie. Set it in any deployment where sessions should survive a restart. |
| `server.host` | `CONSOLE_HOST` | `127.0.0.1` | Loopback by default; set `0.0.0.0` only behind something that authenticates. |
| `server.port` | `CONSOLE_PORT` | `8090` | |
| `engine.url` | `PRAVAHA_ENGINE` | `grpc://localhost:9090` | The engine's Flight endpoint. `grpc://` is plaintext and spelled out. |
| `engine.token` | `PRAVAHA_TOKEN` | *empty* | Bearer token, if the engine authenticates. One identity for the whole console — see *What this does not do* below. |
| `ui.tail_buffer` | — | `256` | Rows held in a live tail. |
| `ui.query_row_limit` | — | `500` | Rows a workbench query will return. |
| `ui.page_size` | — | `25` | Rows per page in the query list. |
| `logging.level` | `LOG_LEVEL` | `INFO` | |

```bash
export CONSOLE_PASSWORD='something only you know'
make run
```

**The engine does not have to be up.** The console starts anyway and says the engine is
unreachable, on every page rather than only the one that failed. An operator opening a console
during an incident needs it to load and tell them what is wrong, which is exactly the moment a
console that refuses to start is least useful.

## How it is laid out

```
run_pravaha_web.py     entry point: config, services, routes, serve
config/application.yaml  every setting, with ${VAR:default} and a git-ignored .local overlay
core/
  config/              the properties configurator (YAML, env, CLI, documented precedence)
  engine.py            the ONLY thing that touches the SDK (ADR-024)
  services.py          typed calls, the subscription broadcaster, no HTTP and no HTML
  content/             markdown topics, rendered at request time
routes/
  base.py              Routes, the brand context, the refusal mapping, the page renderer
  public_routes.py     landing, about, help, tutorials, health probes
  api_routes.py        /api/v1 — everything a screen can do
  ui_routes.py         overview, queries, detail, workbench
web/
  templates/           Jinja2; base.html holds the tokens and the chrome
  static/js/           one file per screen, plus theme, api, states, tail
  static/vendor/       Bootstrap, Bootstrap Icons, the fonts — vendored, no CDN
content/
  help/ tutorials/ about/   front matter plus, usually, an `include:` of a repository document
```

Reading order, because the layering is the point: `engine.py` → `services.py` → `routes/` →
`templates/`. Each knows only the one below it. A template does not know the SDK exists; a service
does not know a browser does.

## Decisions worth knowing before changing it

**Every asset is vendored.** No CDN, so the console renders in an air-gapped deployment — which is
where a streaming engine usually lives. Adding a `<script src>` that points at the internet breaks
that, and it breaks it only for the customers who cannot tell you.

**Every page is rendered by the server first.** The JavaScript makes it live; it does not make it
work. A page that is blank until a module loads is blank exactly when somebody is looking at it
because something is not loading. Every control is a real form for the same reason.

**The documentation is included, not copied.** A help topic is front matter plus
`include: docs/CONCEPTS.md`. One source of truth: copying a document here to give it a card would
create a second copy that drifts from the first, and both would render while only one was right.
Cross-references are repointed at console routes when rendered, because `CONCEPTS.md` is the right
link in a checkout and a dead one here.

**One engine subscription serves every browser.** Ten analysts on one dashboard are ten browser
connections and one subscriber on the engine, ref-counted so the upstream is released when the last
watcher leaves. The engine's claim is that one question costs one computation; a console that
multiplied it by open tabs would be quietly contradicting the thing it exists to demonstrate.

**A theme is a redefinition of one block of tokens.** Light, dark and terminal, plus "system" as a
position rather than the absence of one. Hard-coded colour is what stops a theme from existing, so
there is none.

## What is deliberately not here

This is a **functional admin console**. Server-rendered HTML, no build step, no JavaScript
framework. It does the operator's job and does not pretend to be the product surface design §23.20
describes: no Monaco editor, no plan DAG, no time-travel debugger, no Storybook, no
visual-regression baseline, and no WCAG 2.2 AA audit. Light and dark, density, keyboard paths and
deep links are *implemented*; they are not yet *audited*.

That trade is recorded rather than accidental — see the implementation plan, which says plainly
that without a dedicated frontend engineer the console degrades to exactly this, and that it is a
legitimate trade to make on purpose.

## What ADR-039 item 7 changed (2026-09)

Closing this gap without letting it drift into a rewrite meant identifying what §23.20 asks for
that a functional admin console can genuinely close, and closing exactly that -- not rebuilding the
console as the React product surface §23 describes, which nobody asked for and no dedicated
frontend engineer is here to do.

**Reading what is registered now requires a session.** `/overview`, `/queries`, `/queries/{name}`,
and their JSON equivalents (`GET /api/v1/queries`, `GET /api/v1/queries/{name}`, `GET
/api/v1/stats`), plus the live tail itself (`GET /api/v1/views/{view}/stream`) were reachable by
anyone who could reach the port, with no session at all -- only the mutating routes were gated.
That is the read half of the exact defect `routes/auth_routes.py`'s own docstring says the login
system exists to close on the write half: an anonymous caller could see every registered query's
name, read its full SQL, and open a live stream of a view's actual row-level output, all
unauthenticated and unaudited. `routes/auth_routes.py`'s own account of *why* the gate exists never
distinguished "acting" from "reading" -- both are the console handing out the console's one shared
engine identity, and the write half being gated while the read half was not made this a second,
weaker route to the same data the engine authorizes carefully everywhere else (pgwire, Flight, the
SDKs). Fixed by gating those reads the same way the mutating routes already were; see
`test_reading_what_is_registered_is_not_open_to_an_anonymous_visitor` in `tests/test_console.py`.

**Drop needs the query's name typed, not a dismissable OK/Cancel** (§23.16's own wording: "typed
confirmation of the object's name"). `query_detail.html`'s modal disables its confirm button until
the input matches exactly; the plain, no-JavaScript form this replaces still works exactly as
before for a caller with scripting off, since a modal is JavaScript by definition and the console's
own rule is that every control still works without it.

**The correlation id shown on an error now reaches the console's own log line.** `api.js` has
generated one and displayed it since before this round; nothing on the server side ever logged it,
so pasting the id from the screen into a log search found nothing. `Routes.json_guard` now reads
the browser's `X-Correlation-Id` header and includes it in the same `logger.warning` call that
records the refusal.

**"No secret is ever serialised to the browser" is now a test**, `test_no_secret_is_ever_serialised_to_the_browser`
-- the literal §23.20 item, not merely something believed true because no code path obviously does it.

**What is still open, and why it is not closed here.** Every §23.20 item that depends on the React
island / Monaco / ECharts / Storybook / Lighthouse-CI stack §23.3 and §23.19 describe -- the eight
states audited by Storybook coverage, visual regression, an axe-verified WCAG 2.2 AA pass, the
performance budgets, the eight critical journeys on every PR -- remains open, because closing any
of it for real needs that stack and that stack does not exist in this console; building it would be
the rewrite this round was explicitly told not to become. Two items are open for a different
reason and are not this console's to close at all: "every error message names ... a correlation
id" needs the *engine* to mint and carry a correlation id across pgwire, Flight and the SDKs so the
same id means the same request everywhere, which is `pravaha-api` work outside `console/`'s scope;
and the five-minute, real-people onboarding measurement is exactly that -- a measurement, not code.

## Tests

```bash
JAVA_HOME=/path/to/jdk21 make test
```

They start a real Pravaha server from the Maven build and drive the console against it. A console
tested against a fake engine would prove the fake works. If `JAVA_HOME` is unset the suite skips
and says so — it used to skip silently, which meant nineteen tests could vanish while the run still
reported success.
