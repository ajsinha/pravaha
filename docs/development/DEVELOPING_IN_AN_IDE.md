# Running Pravaha from an IDE

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

How to run the **engine** from IntelliJ IDEA and the **console** from PyCharm, with breakpoints in
both, and how to run their tests there. It is the developer's loop, not a deployment: a deployment
is a container or the release jar ([`DEPLOYMENT.md`](DEPLOYMENT.md)).

The launch settings below were checked by starting both processes exactly as the run configurations
describe: the engine from compiled classes with these VM options and the `dev` profile, and the
console from its virtualenv against it. The engine served with all 14 plugins loaded, and the console
signed in and showed the engine as `RUNNING`.

## What you need

| | |
|---|---|
| JDK | **21** (Temurin or OpenJDK). The build targets 21; `pravaha-api` targets 17 for embedders |
| IntelliJ IDEA | Community or Ultimate, 2024.1 or later |
| Python | 3.11 or later for the console (the SDK itself runs on 3.9+) |
| PyCharm | Community or Professional; IntelliJ Ultimate with the Python plugin works the same way |
| The repository | cloned, with one successful build: `./mvnw -o install -DskipTests` (drop `-o` on a machine whose `~/.m2` is empty) |

## The engine in IntelliJ IDEA

### Open the project

1. **File → Open…** and choose the repository's root `pom.xml`, then **Open as Project**. IntelliJ
   imports all the reactor's modules.
2. **File → Project Structure → Project**: set the **SDK** to your JDK 21 and the **language level**
   to 21.
3. Let the Maven import finish: the progress bar at the bottom, or the **Maven** tool window's
   refresh button.

### The run configuration

**Run → Edit Configurations… → + → Application**:

| Field | Value |
|---|---|
| Name | `Pravaha server` |
| Module (`-cp`) | `pravaha-server` |
| Main class | `com.ash.messaging.pravaha.server.PravahaServerApplication` |
| VM options | `--add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED` |
| Program arguments | `--spring.profiles.active=dev,users` |
| Working directory | the repository root (`$PROJECT_DIR$`) |

On IntelliJ Ultimate, a **Spring Boot** configuration is equivalent. Put the same VM options under
**Modify options → Add VM options**, and `dev,users` under **Active profiles**.

What each setting is for:

- **The two `--add-opens` flags are required.** Apache Arrow, which Flight SQL runs on, reads
  `java.nio` internals. Without them the server starts, and then every Flight call fails inside
  `putNext`: the client sees `RST_STREAM` with no explanation. `bin/pravaha-server` and the
  container image set them for the same reason.
- **`--spring.profiles.active=dev,users`**: `dev` is the local acknowledgement that the server may run
  open and that `admin` may keep its published password (`pravaha-dev-admin`); `users` turns on the
  engine's own users, which the console signs people in against (ADR-052). Drop `users` to run the
  engine alone with no credentials at all.
  Without a security choice the engine refuses to start (`PRV-7004`). The `dev` profile sets
  `pravaha.security.allow-anonymous: true` and nothing else. Never use it anywhere reachable.
- **The module is `pravaha-server`**, because it depends on every connector. Kafka, Delta, JDBC,
  PostgreSQL CDC, Aerospike and Cassandra are therefore all on the run classpath, and bind exactly as
  they do from the fat jar.

Press **Run** or **Debug**. It is ready when the log says `Started PravahaServerApplication`:

| Port | What |
|---|---|
| `18080` | HTTP: the operator pages, `/api/v1`, `/actuator/health` |
| `19090` | Flight SQL: the SDKs, the CLI, the console |

Check it: `curl localhost:18080/api/v1/status` answers with `"engineState":"RUNNING"`. Its `version`
is `unknown` from an IDE, because the version is read from the jar's manifest and a run from classes
has no jar. That is expected.

### Your own configuration

Put a local file anywhere outside the repository and add it to the program arguments:

```text
--spring.profiles.active=dev --spring.config.additional-location=file:/home/you/pravaha-local.yaml
```

Streams, sources, sinks, checkpoints: every key in [`../pravaha-server/src/main/resources/application.yaml`](../pravaha-server/src/main/resources/application.yaml)
can go there, and the file wins over the jar's defaults. Two things are worth setting for a
development loop:

```yaml
pravaha:
  registry:
    journal: /home/you/.pravaha-dev/registry.journal     # registrations survive a restart of the run
  checkpoint:
    directory: /home/you/.pravaha-dev/checkpoints
```

**Port already in use?** Move it in the program arguments:
`--server.port=18081 --pravaha.flight.port=19091`. Then point the console at the new ports (below).

### Debugging

**Debug** instead of **Run**, and breakpoints work anywhere in the reactor: `pravaha-runtime` for
operators and lanes, `pravaha-registry` for registration and replacement, `pravaha-flight` for the
wire, and the `plugins/` modules for a connector. A breakpoint on a lane thread pauses that lane's
queries only. Other lanes, and the HTTP and Flight threads, keep running, so the console stays
responsive while you step.

### Running tests

- Right-click a test class or method → **Run**. Tests are JUnit 5.
- Modules whose tests use Flight (`pravaha-flight`, `pravaha-it`, `pravaha-cli`,
  `sdk/pravaha-sdk-java-flight`) need the same `--add-opens` flags. IntelliJ takes them from the
  module's surefire `argLine` when **Settings → Build, Execution, Deployment → Build Tools → Maven →
  Running Tests → argLine** is ticked, which is the default. Leave it ticked.
- If a test run fails to start with `Could not find or load main class @{jacoco.surefire.argLine}`,
  your checkout predates the root POM's empty default for that property. Pull, and reimport Maven.
- Tests that need Docker (the Kafka, PostgreSQL CDC and Aerospike integration tests) skip, **by
  name**, when no daemon is reachable. That is not a failure.
- The project's gate is `tools/verify-clean.sh`, from a terminal. It builds the whole reactor
  offline from a clean `~/.m2`, runs the Python SDK's suite against the server it just built (it
  needs `sdk/python/.venv`; `PRAVAHA_GATE_SDK=0` leaves the suite out), and is what a commit is held to.
- Building in several git worktrees at once? Use `tools/worktree-build.sh` in place of `./mvnw`
  (same arguments). In a linked worktree it gives the build a Maven repository of its own,
  `<worktree>/.m2-local` (gitignored, seeded from `~/.m2` by hard links without Pravaha's own
  artefacts), so one checkout never compiles against a SNAPSHOT jar another installed
  (MAVENRACE-1). Build the modules you changed with `-am`, or `install` once, so this worktree's
  Pravaha artefacts are the ones there. In the main checkout it is plain `./mvnw`.

### Formatting

Java is formatted by Spotless (Palantir Java Format), and the build checks it. Run
`./mvnw -o spotless:apply -pl <module>` before committing, or install the **palantir-java-format**
IntelliJ plugin and enable it for this project, so the IDE formats as the build does.

## The console in PyCharm

### Open the project and create the interpreter

1. **File → Open…** and choose the `console/` directory. Opening the repository root also works:
   mark `console/` as a sources root.
2. Create the virtualenv the way the project does. From a terminal in `console/`:

   ```bash
   make install        # creates console/.venv, installs ../sdk/python[flight] editable, then the console with dev extras
   ```

   The console depends on the Python SDK in this repository, so the SDK is installed **editable**:
   a change under `sdk/python/` is live in the console at the next run, with no reinstall.
3. **Settings → Project → Python Interpreter → Add Interpreter → Existing** and choose
   `console/.venv/bin/python`.

### The run configuration

**Run → Edit Configurations… → + → Python**:

| Field | Value |
|---|---|
| Name | `Pravaha console` |
| Script path | `console/run_pravaha_web.py` |
| Working directory | `console/` |
| Python interpreter | `console/.venv/bin/python` |
| Environment variables | none |

That is enough against an engine on the default ports, started with the `users` profile. Sign in as
`admin` / `pravaha-dev-admin`: the console keeps no password of its own and acts as whoever signed in. The console reads everything else from
`console/config/application.yaml`, whose defaults are `grpc://localhost:19090` and
`http://localhost:18080`. The other variables it reads:

| Variable | Default | Set it when |
|---|---|---|
| `PRAVAHA_ENGINE` | `grpc://localhost:19090` | the engine's Flight port moved: `grpc://localhost:19091` |
| `PRAVAHA_ENGINE_HTTP` | `http://localhost:18080` | its HTTP port moved: `http://localhost:18081` |
| `CONSOLE_PORT` | `17070` | 17070 is taken |
| `CONSOLE_SESSION_SECRET` | generated at start | you want sessions to survive a restart |

Any key can also be given as a parameter, for example `--server.port=8099`. Machine-local values
that should not be committed go in `console/config/application.local.yaml`, which git ignores and
the console reads straight after `application.yaml`.

Press **Run**. The log says `console on http://127.0.0.1:17070 — engine expected at
grpc://localhost:19090`. Open it and sign in with the password you set. The overview shows the
engine as `RUNNING` when the console reached it.

### Debugging

**Debug** the same configuration, and breakpoints work in `routes/` (request handling), `core/`
(the engine client, help, authoring), and, because it is installed editable, in `sdk/python/pravaha/`
too. Leave `server.reload` at its default, `false`, while debugging. A reloading server runs your
code in a child process, which the debugger does not follow.

### Running tests

**Run → Edit Configurations… → + → pytest**, target `console/tests`, working directory `console/`.
Or right-click `tests/` → **Run pytest in tests**. What the suites need:

- `test_help.py`, `test_console.py`, `test_product.py` and `test_contrast.py` need nothing else.
  They run against a fake engine.
- The `test_browser_*.py` suites drive Chrome: journeys, accessibility, visual baselines,
  performance. They need Chrome or Chromium on the path, or `PRAVAHA_CHROME=<path>`.
  `make test-fast` runs everything except these.
- `make baselines` retakes the visual screenshots. Run it only after reviewing the differences.

For the SDK, open `sdk/python/` the same way. `pip install -e ".[flight]"` into its own virtualenv,
then run pytest on `sdk/python/tests`.

## Both together

The usual loop:

1. **IntelliJ**: debug `Pravaha server` with the `dev` profile and your local YAML.
2. **PyCharm**: debug `Pravaha console`, pointed at it.
3. Register a query from the console's **Workbench**, and step through the engine as the
   registration arrives over Flight, or through the console as the view renders.

If something else on your machine already holds 18080 or 19090, move the engine
(`--server.port=18081 --pravaha.flight.port=19091`) and give the console `PRAVAHA_ENGINE` and
`PRAVAHA_ENGINE_HTTP` to match.
