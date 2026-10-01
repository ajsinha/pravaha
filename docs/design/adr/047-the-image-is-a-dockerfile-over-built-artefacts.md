# ADR-047: the image is a Dockerfile over built artefacts — not Jib, and not distroless

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted — **built**; its choice of Alpine as the base is **superseded by [ADR-053](053-native-code-only-where-java-cannot.md)** (a glibc-only codec, PORT-1), and **supersedes the packaging rows of design §6.3, §22.6 and §27.3**, which say Jib and distroless. `deploy/docker/` builds the image; `deploy/docker/smoke.sh` runs the whole journey against a real container on it. There is no `pravaha-dist` module (design §6.3 lists one; it does not exist) |
| Date | 2026-09-19 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-035 (a node claims its state), ADR-045 (cluster mode on hold), design §6.3, §22.6, §27.3, `../DEPLOYMENT.md` |

## Decision

The engine node's container image is built by a **Dockerfile** (`deploy/docker/Dockerfile`) from
artefacts the reactor has **already produced**, on **`eclipse-temurin:21-jre-alpine`**, running as
uid **10001**, with `bin/pravaha-server` as its entrypoint.

Three things that were planned are not done:

- **Not Jib.** No `jib-maven-plugin`, no image built by the reactor.
- **Not distroless.** Alpine, plus about 2 MB of `bash`.
- **Not a build from source inside the image.** No Maven stage. The repository's root `Dockerfile`
  keeps that job — clone, `docker build .`, wait — and is the convenience build, not the release.

## Why not Jib

- **Jib cannot run in the build this project is gated on.** The gate is `./mvnw -o` — offline. Jib
  resolves its base image's manifest from a registry as part of the Maven lifecycle, so an offline
  reactor cannot produce an image, and an image build would succeed or fail for reasons that have
  nothing to do with the commit being built. "No Docker daemon needed" (design §6.3) is a real
  advantage and it is not the constraint that binds here; the network is.
- **Jib emits no `HEALTHCHECK`.** It is not in the OCI config Jib writes. The point of this work is
  that a deployment can run the node *without reading the source*, and `docker run` telling an
  operator the node is unhealthy is part of that. Adding it back would mean a Dockerfile anyway.
- **Jib's layering wants the fat jar taken apart.** Its value is dependencies in a layer that does
  not change; the one artefact here is a 77 MB Spring Boot repackaged jar, so getting that value
  means `-Djarmode=tools extract --layers` and launching `JarLauncher` directly — which replaces
  `bin/pravaha-server`. See the next section for why that launcher is not negotiable.
- **It would have to be declared in `pravaha-server`'s pom**, which puts packaging inside a module
  three other workstreams are editing. A file under `deploy/` is owned by the thing it deploys.

The cost is honest: layer caching is worse than Jib's (the jar is one 77 MB layer, and any change to
the engine replaces all of it), and image builds need a daemon. Both are paid once per release.

## Why not distroless

Distroless has **no shell**, and `bin/pravaha-server` is a bash script. That script is where the
Arrow flags live:

```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
```

Without them a Flight server fails *inside* `putNext` and the client sees `RST_STREAM` with nothing
explaining why (`../OPERATIONS.md`, "JVM flags"). On distroless the entrypoint would have to be a
`java` command line written out again in the Dockerfile — **a second copy of those flags**, in a
file nobody edits when the launcher changes. They have already been lost once that way; the launcher
exists so that every way of starting this server goes through one line. `bash` costs about 2 MB and
buys one launcher, one place the flags live, and a `HEALTHCHECK` that can use busybox `wget`.

Alpine over the Ubuntu-based `eclipse-temurin:21-jre` is measured: **287 MB against 459 MB** for the
same JRE. The finished image is **436,555,582 bytes** -- about 437 MB -- 77 MB of which is the
application jar. The CLI jar is deliberately left out — 49 MB, a second copy of the whole engine, to
run a client that belongs on the operator's machine.

**The cost of Alpine is musl**, and it is the one real risk in this decision. Arrow and Netty reach
off-heap through the JVM's own `java.nio` and `Unsafe` paths here, not through JNI, so there is
nothing platform-specific to go wrong — but that is a claim, and a claim about a native memory path
is worth testing rather than reasoning about. So `deploy/docker/smoke.sh` runs the full loop on this
image: register over Flight, read the view, append to a live source and watch it move, restart onto
the same volume. **If a connector ever brings a glibc-only native library, the base changes and this
ADR is superseded.** The Cassandra driver's `jnr-posix` was already excluded from the bundle for the
same family of reasons (ADR-044).

## Why the image does not build the project

- The reactor builds **offline**; a Maven stage inside the daemon resolves against Maven Central
  again, so the image's build and the repository's build are two different builds of one commit.
- CI builds the reactor once and every job after it consumes those artefacts. An image that rebuilt
  the world would be a second, differently-configured build in the same workflow.
- The staged context is about 80 MB — the launcher, the jar and the image's own `application.yaml` —
  so the daemon is never sent `.git`, `target/` or the worktrees, and **no `.dockerignore` is needed
  at the repository root**.

## What follows from it

- **Configuration arrives in three layers**, and the image names them in precedence order:
  `/opt/pravaha/conf/application.yaml` (the image's data-directory defaults), then `/etc/pravaha/`
  (a mounted file or a Kubernetes ConfigMap), then the environment. Both file locations are
  `optional:`, so an image run with neither still starts.
- **The image sets location, never meaning.** The registry journal and the checkpoint directory go
  onto the volume, because that is where a container's state belongs. `pravaha.dlq.directory` and
  `pravaha.state.spill.directory` are left unset although the directories exist, because each
  changes what the engine *does* — the first turns a loud failure into a written record and a
  continued read, the second turns `PRV-4001` into a slowdown (ADR-044). Those are the deployment's
  decisions.
- **No credential is in the image**, and the shipped security defaults still refuse to start.
- **The base image's own entrypoint is kept in front of the launcher** rather than replaced. It is a
  no-op unless `USE_SYSTEM_CA_CERTS` is set, and when it is set it is how a private CA reaches the
  JVM truststore (`../CONNECTOR_TLS.md`). Replacing it would have removed that with nothing in its
  place.

## Alternatives rejected

| | Why not |
|---|---|
| `jib-maven-plugin`, as design §6.3 and §27.3 say | Needs the network inside the offline gate; writes no `HEALTHCHECK`; its layering wants the fat jar taken apart, which costs the one launcher |
| `spring-boot:build-image` (Paketo buildpacks) | Same network dependency, a much larger image, and a JVM command line assembled by a buildpack rather than by the launcher the rest of the project uses |
| Distroless | No shell, so the Arrow flags get written out a second time somewhere nobody maintains |
| `eclipse-temurin:21-jre` (Ubuntu) | 172 MB more for `bash` that Alpine supplies for 2 MB |
| A `pravaha-dist` module, as design §6.3 lists | It does not exist, and an image is not a Maven artefact. `deploy/` is where a deployment looks |
| Extracting the Boot jar into layers | Better caching, at the cost of replacing `bin/pravaha-server` with a `JarLauncher` invocation. Worth revisiting when the image is published often enough for the caching to be felt |

## Amendment, 2026-09-26: one root, and a second image

By the owner's decision every path moved under `/opt/pravaha`: the volume from `/var/lib/pravaha`
to `/opt/pravaha/data`, the deployment's configuration from `/etc/pravaha/` to `/opt/pravaha/conf/`,
the audit log to `/opt/pravaha/logs/`, and the chart's secret mounts to `/opt/pravaha/secrets/`. The
image's own defaults moved from `/opt/pravaha/conf/` to `/opt/pravaha/defaults/`, so the directory
an operator mounts hides nothing the image needs. Where the body above names the old paths it
records what was decided then.

The console became its own image, `pravaha/pravaha-console`, built by
`deploy/docker/console/build.sh` from the checkout. It follows the same rule as this one: it builds
nothing the reactor or the SDK's own packaging has not already defined, it runs as uid 10001, and
it is configured by one mounted YAML file, `/opt/pravaha/console/conf/application.yaml`.

## Amendment, 2026-09-29: PRAVAHA_HOME, the same layout everywhere, and any uid

The owner asked that Pravaha run the same in a container and out of one, with every path under
`/opt/pravaha` in Docker and every file the product creates owned by the user who runs it.

- **The layout moved into the jar.** `/opt/pravaha/defaults/application.yaml` is gone; the server jar
  carries `pravaha-home.yaml`, which places the log, the registry journal, the checkpoints, the
  identity store and the audit file under `${PRAVAHA_HOME}`, and `bin/pravaha-server` names it, then
  `$PRAVAHA_HOME/conf/`, when it runs from a home (`PRAVAHA_HOME` set, or `bin/` beside
  `lib/pravaha-server.jar`). It also points `java.io.tmpdir`, `user.home`, heap dumps and `hs_err`
  files under the home and keeps HotSpot's perf counters off `/tmp`. So the image, an unpacked
  distribution (`deploy/release/dist.sh`) and a developer's chosen home get one layout, and
  `--read-only` with only `/opt/pravaha` writable is enough — `smoke.sh` now proves that with no
  `/tmp` at all.
- **Any uid.** Directories are `10001:0` and group-writable, code is root's and read-only, nothing at
  runtime needs root or `chown`s; run as `--user $(id -u):$(id -g)` over bind mounts and every file is
  the caller's. The compose stack (`deploy/docker/compose`) runs that way, read-only.
- **`plugins/`.** The jar's layout is `ZIP`, so its launcher is `PropertiesLauncher` and
  `-Dloader.path=$PRAVAHA_HOME/plugins` works: the "a JDBC driver cannot be added at deployment time"
  consequence recorded in DEPLOYMENT.md no longer holds.
- **The root `Dockerfile`** builds only `pravaha-server` and `pravaha-cli`, with a BuildKit cache for
  `~/.m2`, into the same runtime layout. It remains the no-JDK route; this Dockerfile remains what a
  release publishes.
- The console image keeps its code at `/opt/pravaha/console` and reads `/opt/pravaha/conf/console.yaml`
  after its defaults (and the QA layout's `console/conf/application.yaml`), logs to
  `/opt/pravaha/logs`, and keeps `HOME`, `TMPDIR` and the assistant's files under `/opt/pravaha`.

docs/operations/RUNNING_IN_DOCKER.md is the reference for all of it.

## Amendment, 2026-10-01: the JRE is 25, and the probe is bash

By the owner's decision the images default to **Java 25**: `deploy/docker/Dockerfile` and the root
`Dockerfile` take `ARG JAVA_VERSION=25` (`eclipse-temurin:25-jre`; the root one's build stage is
`maven:3.9-eclipse-temurin-25`), and the test runner is `maven:3.9-eclipse-temurin-25`. The jar does
not change — its classes target Java 21 — and `deploy/docker/build.sh --java 21` still builds the
image on 21, tagged `<version>-jre21`; the plain `<version>` tag, which the release publishes, is 25.
The launcher is still the one place the JVM flags live: on a 24+ JVM it adds
`--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED`, so a 25 node starts with no
JVM warning, and nothing went into `PRAVAHA_JAVA_OPTS`.

The 25 JRE image carries no `wget` (nor `curl`), and the `HEALTHCHECK` above relied on one: a serving
node was reported unhealthy indefinitely, and compose held back every service that waited on it. The
probe is now `bin/pravaha-health`, HTTP/1.0 over bash's `/dev/tcp` — `bash`, which this ADR already
keeps for the launcher, is all it needs — and the compose healthcheck and `helm test`'s probe pod use
it too. `smoke.sh` runs the image's own `HEALTHCHECK` inside a serving container, because every other
step probes from the host and so could not have seen this.

## Amendment, 2026-10-01 (later): Java 25 only, from 2.0

[ADR-061](061-jdk-25-is-the-baseline-from-2-0.md) makes Java 25 the only JDK from Pravaha 2.0, and the
jar's classes are Java 25 class files, so the image's JRE is no longer a choice. Both Dockerfiles name
`eclipse-temurin:25-jre` (and `maven:3.9-eclipse-temurin-25` for the root one's build stage) with no
`JAVA_VERSION` argument; the test runner is 25 only; `deploy/docker/build.sh --java` is refused by
name, the `-jre21` tag is not built, and `release.sh` builds and smoke-tests one engine image. The
launcher adds `--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED` always, and
refuses a JVM older than 25.
