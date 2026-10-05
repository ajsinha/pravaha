# Contributing conventions

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

The rules every change follows, whichever component it touches. **Written once, here**; the developer
guides link to this page rather than repeating it. Most of these are enforced by a test — the table in
[foundations](../../design/architecture/foundations.md#what-the-build-enforces) names each — so a change
that breaks one fails the build rather than a review.

---

## 1. Where a change goes

| You are changing | Module | Guide |
|---|---|---|
| A connector (source, sink, lookup, notifier) | `plugins/<name>`, `pravaha-api` for the SPI | [Connector development](CONNECTOR_DEVELOPMENT.md) |
| SQL: a function, an operator, a refusal | `pravaha-sql`, `pravaha-runtime`, `pravaha-codegen` | [Engine development](ENGINE_DEVELOPMENT.md) |
| A client call, a Flight action, a REST endpoint, a CLI verb | `pravaha-api` (`ControlWire`), `pravaha-flight`, `pravaha-server`, `sdk/*` | [Client development](CLIENT_DEVELOPMENT.md) |
| A console screen, a help topic | `pravaha-console/` | [Console development](CONSOLE_DEVELOPMENT.md) |
| Who may do what | `pravaha-security`, `pravaha-catalog`, `pravaha-identity` | [Security extensions](SECURITY_EXTENSIONS.md) |
| Embedding the engine in an application | — | [`USER_GUIDE.md` §9–§10](../../guides/USER_GUIDE.md#9-embed-the-engine-in-your-application) |
| An assistant provider | `sdk/python/pravaha/assist` | [`ASSIST.md`, *Writing a provider plugin*](../../guides/ASSIST.md#writing-a-provider-plugin) |

How the components fit is [`ARCHITECTURE.md`](../../design/ARCHITECTURE.md).

## 2. Boundaries that are not negotiable

- **`pravaha-api` depends on nothing but the JDK.** A plugin author compiles against it alone.
- **No Spring below `pravaha-server`.** The engine core, `pravaha-embedded` and `pravaha-bindings` are
  enforcer-banned from Spring, and `ArchitectureRulesTest` checks the classes
  ([ADR-019](../../design/adr/019-spring-free-engine-core.md)). If you need a setting in the embedded
  engine, read it from `Configuration`, as `DefaultPravahaEngine` does.
- **No Calcite below `pravaha-sql`.** Add plan types to `com.ash.messaging.pravaha.runtime.plan`; never
  hand a `RexNode` or `RelNode` to the runtime.
- **Only `com.ash.messaging.pravaha.common.memory` names a low-level memory API.** Everything else goes
  through `MemoryAccess` / `MemoryRegion`.
- **No `java.io.Serializable` as a transport.** Use the row format, `ControlWire` or JSON.
- **The console reaches the engine only through the Python SDK** (`pravaha-console/core/engine.py`). If the
  console needs something the SDK cannot do, add it to the engine's public API and the SDK first.

## 3. Error codes

- Every failure a person can see is a `PravahaException` with an `ErrorCode(number, NAME)`, rendered
  `PRV-nnnn`. Declare it in the module's `*Errors` class (`RegistryErrors`, `SqlErrors`, a plugin's
  `KafkaErrors` …), in the **range of its subsystem** (`ErrorCode.Category`: 1xxx configuration, 2xxx
  planning, 3xxx runtime, 4xxx state and serving, 5xxx plugins, 6xxx gateways, 7xxx security, 8xxx
  registry, 9xxx cluster).
- **A number is unique across the repository and is never reused or renumbered** once released
  (`ErrorCodeUniquenessTest`). Pick the next free number in the range: `git grep -n "new ErrorCode(80"`.
- **Document it** in the same change: a row in [`TROUBLESHOOTING.md`](../../guides/TROUBLESHOOTING.md)'s
  code table (`ErrcCrossCuttingTest`, in `pravaha-cli`, fails if the table and the declarations disagree
  either way), and an explanation on the console's errors topic for its range
  (`pravaha-console/content/topics/errors-*.md`; `test_help.py` fails otherwise).
- The message says what happened, why it matters, and what to do — and may name a setting only if
  `application.yaml` declares it (`DocumentationFreshnessTest.everySettingAnErrorMessageTellsYouToChangeExists`).

## 4. Files

- **The licence notice** heads every source file — Java, Python, shell, YAML — within its first lines
  (`LicenceHeaderTest`, `LicenseHeaderTest`). Copy it from a neighbour; for Java it is the block that
  begins `Project Pravaha -- Ask once. Answer always.` and names Ashutosh Sinha and *proprietary and
  confidential*. A new Markdown document carries the two-line notice under its title, as this one does.
- **No source file over 1,500 lines** — Java (`SourceFileSizeTest`), the SDK's and the console's Python
  (`test_file_sizes.py`). Split by responsibility, as `RegistrationPlanning` and `RegistryRecovery` were
  split out of `QueryRegistry`; there is no allow-list.
- **Formatting** is Spotless (palantir-java-format): `./mvnw -o spotless:apply -pl <module>`
  ([`RUNNING_IN_INTELLIJ_AND_PYCHARM.md`](../setup/RUNNING_IN_INTELLIJ_AND_PYCHARM.md#formatting)).
- **No production class reachable only from tests** (`OrphanedClassTest`).

## 5. Documentation that moves with the code

| You changed | Update | Held by |
|---|---|---|
| An error code | `TROUBLESHOOTING.md`, the console's errors topic | `ErrcCrossCuttingTest`, `test_help.py` |
| A `pravaha.*` setting | `application.yaml` (commented if optional), [`OPERATIONS.md`](../../operations/OPERATIONS.md); a `pravaha.lane.*` key must be named there | `DocumentationFreshnessTest`, `test_help_accuracy.py` (defaults must match) |
| A per-query metric | `OPERATIONS.md`'s metric table | `DocumentationFreshnessTest.everyPerQueryGaugeIsDocumented` |
| A REST endpoint | `api/openapi.lock.json` (regenerate, below), [`PYTHON_API_GUIDE.md`](../../guides/PYTHON_API_GUIDE.md) | `OpenApiContractTest`, `OpenApiLockTest` |
| SQL the engine accepts or refuses | [`CONTINUOUS_QUERIES.md`](../../guides/CONTINUOUS_QUERIES.md), then regenerate the assistant's dialect card | the guide's planned-SQL tests, `ContinuousQueriesClaimsTest`; `sdk/python` `tests/test_assist_dialect.py` |
| A CLI command or flag | [`CLI.md`](../../guides/CLI.md) | `test_help_accuracy.py` checks every command a page shows |
| A component's shape | its page under [`../../design/architecture/`](../../design/architecture/README.md) | `MarkdownLinksTest` for links and anchors |
| A decision | a new ADR and its row in [`adr/README.md`](../../design/adr/README.md) | `DocumentationFreshnessTest` |
| A defect found or fixed | [`FINDINGS.md`](../../project/qa/FINDINGS.md) | `FindingsRegisterTest` |

Regenerating the two generated artefacts:

```bash
./mvnw -pl pravaha-server test -Dtest=OpenApiContractTest -Dpravaha.openapi.update=true
sdk/python/.venv/bin/python sdk/python/tools/build_dialect_card.py
```

**One home per topic.** Before writing a paragraph, find the page that owns the topic (the table at the
top of [`ARCHITECTURE.md`](../../design/ARCHITECTURE.md)) and put it there; elsewhere, link.

## 6. Building and testing

The tiers, the Docker set-up and what skips are [`TESTING.md`](../TESTING.md). The short form:

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64      # JDK 21 or later (ADR-062); CI runs 21 and 25

# In a linked git worktree, always this wrapper: it gives the worktree its own Maven repository,
# so parallel checkouts never compile against each other's SNAPSHOT jars (MAVENRACE-1).
tools/worktree-build.sh -o install -DskipTests              # once
tools/worktree-build.sh -o -pl pravaha-registry test -Dtest=QueryRegistryTest   # while iterating
tools/worktree-build.sh -o -pl pravaha-it test -Dtest='MarkdownLinksTest,DocumentationFreshnessTest'
tools/worktree-build.sh -Pep -DskipTests clean test-compile # Error Prone and NullAway
```

In the main checkout the same commands are `./mvnw ...`. Run the tests for what you changed while you
work, one verification of your change before you hand it on, and leave the whole-reactor gate to whoever
merges the batch. The console's and the SDK's tests are `pytest` from their own virtual environments
(`pravaha-console/.venv`, `sdk/python/.venv`); browser tests only when pages change.

**Seed a bug before trusting a test**: break the code, see the test fail, restore it
([`HANDOVER.md` §2](../HANDOVER.md#2-working-practices--please-keep-these) has the cases that made this
a rule).

## 7. Commits

- Stage files explicitly (`git add <paths>`), never `git add -A` or `.`.
- One commit per area; the message explains **why** — what was wrong and what was learned — and ends with
  its own text: no trailers.
- Work lands on `develop`; `main` is merged from it when the owner says so.
