# What 2.x promises

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

Pravaha follows semantic versioning from 1.0.0. A **major** release (3.0) may break what this page
calls stable; a **minor** release (2.1) adds to it without breaking it; a **patch** release (2.0.1)
fixes defects and changes nothing a client can rely on, except where the old behaviour was the
defect. 2.0 is a **one-node** release, as 1.0 was: cluster mode (wave 11) is not in it, and when it
comes it arrives as a 2.x addition that a single node does not have to adopt.

## 2.0: Java 25 is the minimum

2.0's one breaking change is the Java baseline ([ADR-061](../design/adr/061-jdk-25-is-the-baseline-from-2-0.md)).
**Java 25 is required** to build, run, embed or call Pravaha from Java: every module, `pravaha-api`
and the Java SDKs included, is Java 25 class files. 1.x required 21 (`pravaha-api` and
`pravaha-sdk-java` targeted 17) and supported 25.

| Who | What changes |
|---|---|
| Running a node from the jar or the distribution | `JAVA_HOME` must be a JDK or JRE 25; the launchers refuse an older JVM by name |
| Running the image | Nothing: it was already on `eclipse-temurin:25-jre`. The 1.x `-jre21` tag is not built for 2.x |
| Embedding (`pravaha-embedded`), a plugin against `pravaha-api` | The application's JVM must be 25 |
| The Spring Boot starter | Java 25, and **Spring Boot 3.4 or later**: Boot 3.2 and 3.3 cannot read Java 25 class files (1.x: Boot 3.2 to 3.5) |
| A Java SDK client | Java 25. The wire is unchanged, so a 1.x Java SDK keeps speaking it to a 2.0 node while an application moves; the tested pairing is still the same major.minor (below) |
| The Python SDK, the CLI, the console | Nothing |

Everything else this page calls stable is unchanged from 1.x: the SQL, the wire protocols, the
HTTP API, the error codes, the configuration keys, the metrics and the container layout. A 2.0
node reads the state any 1.x node wrote.

## Stable in 2.x

| Surface | What stays compatible | Where it is defined |
|---|---|---|
| Java | JDK 25 is the minimum through 2.x, for building, running, embedding and the Java SDKs; every module's classes target Java 25. The container images run on `eclipse-temurin:25-jre`. (1.x: JDK 21 minimum, 25 supported.) | [GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md](../development/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md) |
| The SQL dialect | Every statement, function and clause [../guides/CONTINUOUS_QUERIES.md](../guides/CONTINUOUS_QUERIES.md) documents keeps its meaning; a statement accepted by 1.0 is accepted by every 1.x and 2.x and answers the same. New syntax and functions may be added. | [../guides/CONTINUOUS_QUERIES.md](../guides/CONTINUOUS_QUERIES.md) |
| The Java SDK | Public types and methods of `pravaha-api`, `pravaha-sdk-java` and `pravaha-sdk-java-flight` are not removed or changed incompatibly; methods may be added. | the javadoc jars; `sdk/pravaha-sdk-java/README.md` |
| The Python SDK | The public names in `pravaha` (everything not starting with `_`), excluding `pravaha.assist` — see below. | [../guides/PYTHON_API_GUIDE.md](../guides/PYTHON_API_GUIDE.md) |
| The HTTP API | Every path and field under `/api/v1`; fields may be added, never removed or retyped. | the checked-in OpenAPI lock (OPENAPILOCK-1) |
| Arrow Flight SQL | The ticket verbs and control actions, and the columns of their results; a new column is appended, never inserted. | `ControlWire` |
| The PostgreSQL gateway | What a psql, pgjdbc, Npgsql or psycopg client may send today keeps working. | [the pgwire help topic](../../console/content/topics/pgwire.md) |
| Error codes | A `PRV-nnnn` code keeps its meaning and its SQLSTATE; new codes may be added; a code is never reused. | `ErrorCodeUniquenessTest`; [../guides/TROUBLESHOOTING.md](../guides/TROUBLESHOOTING.md) |
| The command lines | `pravaha` and `pravaha-engine` subcommands, flags and exit codes; JSON output fields may be added. | [../guides/CLI.md](../guides/CLI.md) |
| Configuration | Every documented `pravaha.*` key; a key is deprecated for at least one minor release, with a warning at start, before it is removed in the next major. | [OPERATIONS.md](OPERATIONS.md) |
| Metrics | Names and labels of the `pravaha.*` meters; new ones may be added. | `ObservabilityContractTest` |
| State on disk | A 2.x node reads the journal, checkpoints, catalogue and identity stores written by any earlier 1.x or 2.x node. Going *back* is not promised: an older build may refuse state written by a newer one. | [OPERATIONS.md](OPERATIONS.md) |
| The container layout | Everything under `PRAVAHA_HOME` (`/opt/pravaha` in the images): `conf/`, `data/`, `logs/`, `plugins/`, `secrets/`, `tmp/`. | [RUNNING_IN_DOCKER.md](RUNNING_IN_DOCKER.md) |

## Experimental in 1.0 and 2.0

These work and are tested, and may still change in a minor release, said in the release notes:

- **The assistant** — `pravaha.assist`, `pravaha ask`, `explain-sql`, `why`, `assist eval`, the
  console's *Describe it* and *Explain*, and **Admin · AI models** ([../guides/ASSIST.md](../guides/ASSIST.md),
  ADR-058). Model behaviour moves under it, and its configuration file's shape may change.
- **The catalogue's unbuilt phases** — what ADR-059 plans beyond phases 1 and 2 is not in 1.0 or 2.0.
  What *is* built — privileges, grants, namespaces, row filters and masks as policies — is stable.
- **Anything a page marks as a preview.**

## Clients and servers

Use an SDK or console of the **same major.minor** as the node. There is no version handshake: a
newer client asking an older node for something it does not have is refused by name (an unknown
ticket verb or action), never silently downgraded. Within a major, a newer node serves an older
client of that major. Other pairings are not tested.

## Upgrading from 0.2.x

0.2.x releases were QA builds and made no promise. Upgrading one to 1.0 is **one-way** once the node
has restarted with a view outside the default tenant: ADR-060 journals per-tenant names in a record
older builds refuse. Take a backup of `data/` first ([OPERATIONS.md](OPERATIONS.md)). The release
notes list every behaviour change since 0.2.0.
