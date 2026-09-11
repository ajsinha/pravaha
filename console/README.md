# Pravaha console

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`LICENSE`](LICENSE).

An operator console for a running Pravaha server: what is registered, what state it is in, what it
is producing right now, and the controls to change it.

## What this is, and what it is not

**It is a functional admin console, deliberately.** The implementation plan says plainly that without
a dedicated frontend engineer the console degrades to exactly that, that design §23.20's checklist
will not be met, and that this is a legitimate trade to make *on purpose* rather than by accident.
This is that trade, taken on purpose.

So: server-rendered HTML, no build step, no JavaScript framework, no design system. About four
hundred lines. It does the operator's job and does not pretend to be the product surface §23.20
describes.

## Why it is a separate process

ADR-024. The console reaches the engine **only through the published Python SDK** — the same client
an integrator uses.

A boundary enforced by a test can be waived by whoever is under deadline pressure that week. A
boundary enforced by a process cannot: this is Python, the engine is Java, and there is no way to
reach past the API even carelessly.

The second benefit is that the console is the SDK's first serious consumer. An awkward corner of the
client API becomes an awkward corner of the console, where somebody notices, instead of being found
by an integrator.

## Run it

```bash
cd console
make install
make run                     # or: pravaha-console --engine grpc://localhost:9090 --port 8080
```

Then open <http://127.0.0.1:8080>.

> **Loopback by default, and that is a decision.** The console reaches a whole cluster's state and
> has no authentication of its own. Making it reachable from elsewhere should be something somebody
> does deliberately, with something in front of it. Pass `--host 0.0.0.0` if you mean it, and put a
> reverse proxy and an identity provider in front when you do.

Against a server that requires a credential:

```bash
pravaha-console --engine grpc://pravaha:9090 --token "$PRAVAHA_TOKEN"
```

## What it shows

| Page | |
|---|---|
| `/` | Every registered query: name, state, rows in, and **whether its computation is shared** |
| `/queries/{name}` | The SQL, the fingerprint, pause/resume, and a **live tail** of the view |
| `/query` | Ask a question, with bound parameters |
| `/health` | Whether the engine is reachable. Returns 200 even when it is not — see below |
| `/help` | The project's guides, rendered in the console |
| `/help/{page}` | One guide — quickstart, concepts, SQL support, troubleshooting, operations, security |
| `/help/study/{name}` | One worked system, rendered in the console |

**Help is in the console, not somewhere else.** Every page carries contextual cards — three short
answers to the questions that page provokes — and each links into the full guide, rendered here. An
operator reading a console is already where the question arose; sending them to a wiki or a search
engine loses the thread and usually loses the question.

The guides are rendered from the repository's own `docs/` rather than copied. A copy would drift, and
the point of the build checking those files is that they can be trusted. A stale copy in a console
would quietly undo that.

The five case studies are offered alongside the guides, because somebody deciding how to shape a
query wants a worked example far more often than a specification.

Only an allow-list of pages is served. That is the security control rather than path arithmetic: a
console that accepted a name and joined it to a directory would serve `../../etc/passwd` to anybody
who asked, and normalising afterwards is never as reliable as not accepting the name.

Two things it surfaces that nothing else does:

**Shared computations.** Two names on one fingerprint are one computation holding one copy of the
state. "Ten analysts on one dashboard cost one query" is the central claim about how this engine
scales, and the index marks the rows where it is holding, so it can be watched rather than believed.

**A live tail, not a poll.** The detail page holds a subscription open over server-sent events.
Polling would show an operator a number that is always slightly out of date, which would undercut the
product's whole argument on its own front page.

## `/health` returns 200 when the engine is down

Deliberately. This endpoint answers *"is the console up"*, and the payload says what it found:

```json
{"reachable": false, "url": "grpc://localhost:9090", "error": "..."}
```

A console whose own health check fails when the engine fails cannot tell you the engine has failed,
which is the one moment you needed it. Point your liveness probe here and your alerting at
`reachable`.

## Tests

```bash
make test
```

They run against **the real Java server**, started from the classpath the Maven build writes — the
same fixture the Python SDK's tests use. A console tested against a fake engine would prove the fake
works. Build the Java side first:

```bash
cd .. && ./mvnw -q -pl pravaha-flight -am test-compile -DskipTests
```

## Known limits

Stated so you do not find them in a demo:

- **No authentication of its own.** It passes a token through to the engine; it does not have users.
  Put it behind something.
- **One engine per process.** No cluster view, because there is no cluster yet (Wave 8).
- **The help is the shipped documentation, not a tutorial.** It renders the guides; it does not
  teach interactively.
- **No EXPLAIN, no metrics charts, no time-travel.** `pravaha explain` covers the first; the rest
  arrive with Waves 9–10, and the debugger is planned as a CLI (`pravaha replay`) for the same
  staffing reason this console is plain.
- **The live tail starts from now.** A subscription is not a replay: it shows what is committed from
  the moment you open the page.
