# What 2.x promises

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../../LICENSE`](../../LICENSE).

Pravaha follows semantic versioning from 1.0.0. A **major** release (3.0) may break what this page
calls stable; a **minor** release (2.1) adds to it without breaking it; a **patch** release (2.0.1)
fixes defects and changes nothing a client can rely on, except where the old behaviour was the
defect. 2.0 is a **one-node** release, as 1.0 was: cluster mode (wave 11) is not in it, and when it
comes it arrives as a 2.x addition that a single node does not have to adopt.

## 2.0: what breaks

2.0 broke two things: the Java baseline (since relaxed to Java 21 or later, below), and `pravaha.security.administer: legacy-read`, removed as
1.x announced.

### Java 21 or later

**The requirement is Java 21 or later, tested on 21 and 25** ([ADR-062](../design/adr/062-java-21-or-later.md)).
2.0.0 had made Java 25 the minimum ([ADR-061](../design/adr/061-jdk-25-is-the-baseline-from-2-0.md));
ADR-062 supersedes that baseline. Any JDK or JRE from 21 up (22, 23, 24, 25, ...) runs Pravaha. Every
module, `pravaha-api` and the Java SDKs included, is Java 21 class files (`maven.compiler.release` 21,
class-file version 65), and the build itself runs on JDK 21 or later. CI runs the whole reactor on 21
and on 25.

| Who | What it means |
|---|---|
| Running a node from the jar or the distribution | `JAVA_HOME` must be a JDK or JRE 21 or later; the launchers refuse an older JVM by name |
| Running the image | One engine image, on `eclipse-temurin:21-jre`; there is no per-Java tag |
| Embedding (`pravaha-embedded`), a plugin against `pravaha-api` | The application's JVM must be 21 or later |
| The Spring Boot starter | Java 21 or later, and Spring Boot 3.4 or 3.5, the tested lines. Java 21 class files would load on Boot 3.2 and 3.3 again, but those lines are out of open-source support and are not brought back |
| A Java SDK client | Java 21 or later. The wire is unchanged, so a Java SDK of any 1.x or 2.x keeps speaking it to a 2.x node; the tested pairing is still the same major.minor (below) |
| The Python SDK, the CLI, the console | Nothing, except that their tests start a Java server, which needs `JAVA_HOME` on a JDK 21 or later |

On JDK 21 a virtual thread that blocks inside a `synchronized` monitor pins its carrier thread (JDK 24
fixed that, JEP 491), so the monitors that request and feed virtual threads block inside were converted
to `ReentrantLock`; [ADR-062](../design/adr/062-java-21-or-later.md) has the list.

### `legacy-read` is removed

`pravaha.security.administer: legacy-read` was deprecated in 1.0.0 and announced for removal in 2.0
(the rule below: a key is deprecated through a minor release, then removed in the next major). It
let anyone who may read a view without a row filter drop, pause, resume or replace it. In 2.0 a
view is administered by its owner, a principal granted `MODIFY` or `MANAGE` on it, or the `admin`
role — as it already was by default in 1.x. A node or embedded engine that still sets `legacy-read`
**refuses to start** with `PRV-7004 … legacy-read was removed in 2.0; grant MODIFY/MANAGE or use the admin role`. Grant those
operators `MODIFY` or `MANAGE`, or the `admin` role, then remove the setting
([SECURITY.md](SECURITY.md#drop-pause-resume-and-replace-are-authorized-by-ownership-not-by-reading)).
`ownership` stays an accepted value.

Everything else this page calls stable is unchanged from 1.x: the SQL, the wire protocols, the
HTTP API, the error codes, every other configuration key, the metrics and the container layout. A
2.0 node reads the state any 1.x node wrote.

## 2.0.1: fixes that change an answer

2.0.1 is a patch: it fixes the 50 findings of the adversarial QA of 2.0.0 and adds no feature. A
patch "changes nothing a client can rely on, except where the old behaviour was the defect" (above),
and these are the places where it was — each a wrong answer, or a refusal that came too late, that a
client could have come to depend on. The release notes' "Unreleased" section has every one with its
finding; read it before upgrading a node whose queries touch any of these.

| What 2.0.0 did | What 2.0.1 does | Finding |
|---|---|---|
| `SUM`, `AVG`, `MIN`, `MAX` of a group with no non-null value published `0` | NULL, as SQL says; `COUNT(col)` is still 0 | ALLNULLAGG-1 |
| An `INT`, `SMALLINT` or `TINYINT` result outside its range, and a narrowing integer `CAST`, wrapped | An overflow: the query stops naming the value, or the row is dead-lettered | NARROWINT-1 |
| `CAST` of `NaN`, `±Infinity` or an out-of-range `DOUBLE` to an integer was `0` or a clamped extreme; `BIGINT` minimum `/ -1` wrapped; an integer literal outside `BIGINT` compiled as its low 64 bits | An overflow; the literal is refused `PRV-2021` at registration | NARROWCAST-1, DIVMIN-1 |
| A row that failed evaluation stopped the query, dead-letter queue or not | With `pravaha.dlq.directory` set it is dead-lettered (`PRV-3027`) and the query keeps running; every query gets a `<query>.dlq` | DLQPROJ-1, CLIDLQ-1 |
| `-0.0` and `0.0`, and two `NaN` payloads, were separate groups | One group each; fewer, merged groups are published | NANGROUP-1 |
| `NOT (d > 5)` dropped a `NaN` row from both a predicate and its negation | The IEEE complement keeps it in the negation | NANNOT-1 |
| A `HOP` whose size is not a multiple of its slide aligned window ends to the slide | Windows start on multiples of the slide, as SQL's `HOP`; `TUMBLE` and other hops unchanged | HOPALIGN-1 |
| A hop finer than a lane could serve registered and wedged its lane | Refused at registration, `PRV-3026` (`pravaha.lane.max-windows-per-row`) | FINEHOP-1 |
| `MIN`/`MAX` over a retracting source registered and stopped at the first retraction (`PRV-3020`) | Refused at registration, `PRV-2076` | MINRETRACT-1 |
| A file timestamp past 2262 was stored as a time in 1677 | Refused, `PRV-5040` | FARTIME-1 |
| The `users` profile left the `permissive` policy in force | It sets `authenticated`; a home that imported `permissive` under it refuses to start with `PRV-7034` until the policy is set explicitly | PERMISSIVEUSERS-1 |
| A locked account answered `423 PRV-7011` | Every refused sign-in answers `401 PRV-7010`; failures bar the address, not the account | LOCKENUM-1 |
| pgwire refused `COPY`, cursors and `LISTEN` as `42000 PRV-2001`, and `SELECT 1` and `SHOW search_path` as errors | `0A000 PRV-6201` by name; the probes are answered | PGCOPY-1, PGVALIDATE-1 |
| Requests Tomcat refused itself (encoded `/`, oversized headers) were HTML | An `ApiError`, `400 PRV-1056` | TOMCATHTML-1 |
| A running query over a deleted Kafka topic or a dropped CDC slot stayed `RUNNING` and health `UP` | The feed stops (`PRV-5130`, `PRV-5117`) and health is `DEGRADED` | TOPICGONE-1, CDCSLOT-1 |

**State on disk.** A 2.0.1 node reads every 2.0.0 checkpoint and journal, with three deliberate
exceptions that make the query rebuild from its sources rather than restore a wrong answer: a
checkpoint holding a `-0.0` or a non-standard `NaN` as a key or distinct value (NANGROUP-1), one
whose output schema differs from the query's (`PRV-4095`, RETYPERESTORE-1; a 2.0.0 checkpoint records
no schema and restores as before), and a checkpoint whose checksum does not match (`PRV-4094`,
CKPTSUM-1; a 2.0.0 checkpoint has none and is restored, logged as unverified). Going back: the
checksum is a tail after an unchanged body, so 2.0.0 reads a 2.0.1 checkpoint; a journal holding the
new `M` record (SHAREDLOSS-1) is not promised to an older build. Damage in the middle of a journal,
which 2.0.0 read past silently, now refuses the start with `PRV-8005` (JOURNALMID-1).

**New refusals that are configuration.** `pravaha.pgwire.limits.*`, `pravaha.http.*` and
`pravaha.identity.lockout.*` bound what a client can make a node hold; their defaults are generous
for one node, and a deployment past them meets `PRV-6216`, `PRV-1054` or `PRV-1055` by name
([OPERATIONS.md](OPERATIONS.md#connections-and-request-bodies)). The console's cookie no longer
carries the engine token, so a console restart signs everyone out of it and several console
instances need sticky sessions.

## 2.4.0: fixes that change behaviour

| What 2.3.0 did | What 2.4.0 does | Finding |
|---|---|---|
| Over HTTP, a custom `TokenVerifier` returning `Principal.ANONYMOUS` admitted the caller as anonymous; one returning null or throwing was a 500 with no code | Refused `PRV-7001`, 401, as Flight and pgwire already refused it | J21-1 |

## 2.2.0: fixes that change behaviour

| What 2.1.0 did | What 2.2.0 does | Finding |
|---|---|---|
| A dead letter that could not be written (a full disk) was counted and dropped; the source read on | The feed stops at that record, `PRV-4090`, logged at ERROR and counted; a row whose evaluation failed stops its query | DLQFULL-1 |
| A TLS-configured PostgreSQL gateway signed in a client that never asked for TLS, its token in the clear | Refused `FATAL 28000`, `PRV-6221`, unless `pravaha.pgwire.tls.allow-plaintext: true` | PGTLSONLY-1 |
| A protobuf field of the wrong wire type was read as `0` | The record is undecodable and dead-lettered | PBDRIFT-1 |
| A node started on an expired (or not yet valid) TLS certificate | Refused at start, `PRV-6104`/`PRV-6206`; within 30 days of expiry a `WARN` | CERTEXP-1 |
| The Java SDK reported a certificate it did not trust as `PRV-1040`, retryable | `PRV-1046 CLIENT_TLS_HANDSHAKE_FAILED`, not retryable | TLSDIAG-1 |
| An SDK call to a node that never answered waited for ever | Every unary call, and a subscription's opening, fails `PRV-1045` (retryable) past `requestTimeout` / `request_timeout_seconds`, 60 s by default | SDKDEADLINE-1 |
| A `SecurityPolicy`, `TokenVerifier` or `AuditSink` bean in a node's context was ignored | It replaces the configured one; two of a type, or one that would be ignored, is refused at start with `PRV-7004` | POLICYPLUG-1 |

## Stable in 2.x

| Surface | What stays compatible | Where it is defined |
|---|---|---|
| Java | Java 21 or later through 2.x, tested on 21 and 25, for building, running, embedding and the Java SDKs; every module's classes target Java 21 ([ADR-062](../design/adr/062-java-21-or-later.md)). The container images run on `eclipse-temurin:21-jre`. | [GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md](../development/setup/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md) |
| The SQL dialect | Every statement, function and clause [../guides/CONTINUOUS_QUERIES.md](../guides/CONTINUOUS_QUERIES.md) documents keeps its meaning; a statement accepted by 1.0 is accepted by every 1.x and 2.x and answers the same, except where the answer was a defect — 2.0.1's are listed above. New syntax and functions may be added. | [../guides/CONTINUOUS_QUERIES.md](../guides/CONTINUOUS_QUERIES.md) |
| The Java SDK | Public types and methods of `pravaha-api`, `pravaha-sdk-java` and `pravaha-sdk-java-flight` are not removed or changed incompatibly; methods may be added. | the javadoc jars; `sdk/pravaha-sdk-java/README.md` |
| The Python SDK | The public names in `pravaha` (everything not starting with `_`), excluding `pravaha.assist` — see below. | [../guides/PYTHON_API_GUIDE.md](../guides/PYTHON_API_GUIDE.md) |
| The HTTP API | Every path and field under `/api/v1`; fields may be added, never removed or retyped. | the checked-in OpenAPI lock (OPENAPILOCK-1) |
| Arrow Flight SQL | The ticket verbs and control actions, and the columns of their results; a new column is appended, never inserted. | `ControlWire` |
| The PostgreSQL gateway | What a psql, pgjdbc, Npgsql or psycopg client may send today keeps working. | [the pgwire help topic](../../pravaha-console/content/topics/pgwire.md) |
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
