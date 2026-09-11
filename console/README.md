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
visual-regression baseline, and no WCAG 2.2 AA audit. Light and dark, density, keyboard paths, deep
links and the eight states of §23.12 are *implemented*; they are not yet *audited*.

That trade is recorded rather than accidental — see the implementation plan, which says plainly
that without a dedicated frontend engineer the console degrades to exactly this, and that it is a
legitimate trade to make on purpose.

## Tests

```bash
JAVA_HOME=/path/to/jdk21 make test
```

They start a real Pravaha server from the Maven build and drive the console against it. A console
tested against a fake engine would prove the fake works. If `JAVA_HOME` is unset the suite skips
and says so — it used to skip silently, which meant nineteen tests could vanish while the run still
reported success.
