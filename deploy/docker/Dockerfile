# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The engine node as a container image.
#
# This file does NOT build the project. Its context is a small staging directory that
# deploy/docker/build.sh assembles from artefacts the reactor has already produced, which is why
# there is no Maven stage here:
#
#   * the repository builds offline (`./mvnw -o ... package`) and a Maven stage inside the daemon
#     would resolve against Maven Central again, so an image build could succeed or fail for
#     reasons that have nothing to do with the commit being built;
#   * CI builds the reactor once and every job after it consumes those artefacts. An image that
#     rebuilds the world is a second, differently-configured build of the same commit;
#   * the staged context is ~80 MB, so the daemon is not sent .git, target/ or the worktrees --
#     no .dockerignore at the repository root required.
#
# The root `Dockerfile` remains the from-source convenience build (clone, `docker build .`, wait).
# This one is what a release publishes. docs/operations/DEPLOYMENT.md says which to reach for.

# glibc, not Alpine (ADR-053, superseding ADR-047's choice of base). Alpine saved 172 MB and cost
# musl, and ADR-047 said what would end it: "if a connector ever brings a glibc-only native library,
# the base changes". One had: snappy-java arrives with Parquet (feedfile, delta), is built for glibc
# only, and failed to load on the Alpine image, so the commonest Parquet file could not be read in
# the container (PORT-1). smoke.sh now loads both Parquet codecs on this image.
#
# The JRE: 21, the floor of the promise (ADR-062). The classes are Java 21 class files and run on
# every JRE from 21 up; the image ships the lowest, so what is released is what the floor runs.
# bin/pravaha-server gives the JVM --enable-native-access=ALL-UNNAMED, and on a JRE of 23 or later
# --sun-misc-unsafe-memory-access=allow as well, so the node starts without a warning on either.
FROM eclipse-temurin:21-jre

# A deployment wants to know what it is running without starting it.
ARG PRAVAHA_VERSION=0.0.0-unset
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="Pravaha engine node" \
      org.opencontainers.image.description="Continuous-query engine node: HTTP on 18080, Flight SQL on 19090." \
      org.opencontainers.image.version="${PRAVAHA_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.authors="Ashutosh Sinha <ajsinha@gmail.com>" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary" \
      org.opencontainers.image.source="https://github.com/ajsinha/pravaha" \
      com.ash.messaging.pravaha.java="21"

WORKDIR /opt/pravaha

# The PRAVAHA_HOME layout (docs/operations/RUNNING_IN_DOCKER.md, "The layout"), the same one an unpacked
# distribution has, rooted at /opt/pravaha:
#
#   bin/ lib/          the launcher and the jar                       (the image's, read-only)
#   conf/              application.yaml -- mounted, read after the layout, so it wins
#   secrets/           key material, 0700
#   plugins/           extra jars on the classpath (loader.path)
#   data/              every byte of engine state                     (VOLUME)
#   logs/              pravaha-server.log, audit.jsonl, heap dumps    (VOLUME)
#   tmp/               java.io.tmpdir and user.home: native codecs unpack here
#
# Nothing is written anywhere else: bin/pravaha-server sees PRAVAHA_HOME and points the JVM's temp
# directory, user.home, heap dumps and error files under it, and names the jar's pravaha-home.yaml,
# which places the journal, checkpoints, identity store and audit trail. Proved with --read-only and
# only /opt/pravaha writable (docs/operations/RUNNING_IN_DOCKER.md, "Nothing outside /opt/pravaha").
#
# ANY UID. The default user is 10001, and the image does not depend on it: run it as
# `--user $(id -u):$(id -g)` over bind-mounted directories and everything it creates belongs to you,
# which is how deploy/docker/compose runs it. The directories below are 10001's and group 0's,
# group-writable, so an orchestrator that assigns an arbitrary uid in group 0 (OpenShift) works on
# the image's own directories too. Nothing here needs root at runtime and nothing chowns.
RUN groupadd --system --gid 10001 pravaha \
 && useradd --system --uid 10001 --gid pravaha --no-create-home --home-dir /opt/pravaha/tmp \
            --shell /usr/sbin/nologin pravaha \
 && mkdir -p /opt/pravaha/bin /opt/pravaha/lib /opt/pravaha/conf /opt/pravaha/plugins \
             /opt/pravaha/data /opt/pravaha/logs /opt/pravaha/secrets /opt/pravaha/tmp \
 && chown 10001:0 /opt/pravaha/conf /opt/pravaha/plugins /opt/pravaha/data /opt/pravaha/logs \
                  /opt/pravaha/secrets /opt/pravaha/tmp \
 && chmod 0770 /opt/pravaha/conf /opt/pravaha/plugins /opt/pravaha/data /opt/pravaha/logs /opt/pravaha/tmp \
 && chmod 0700 /opt/pravaha/secrets

# The launcher, which is where the Arrow --add-opens and the PRAVAHA_HOME handling live, then the
# jar. Cheapest-changing first, so a rebuild of the same release reuses the layers above the jar.
# Owned by root and world-readable: code the node runs, not state it writes.
# --chmod on the COPY, not a RUN chmod after it: a RUN that touches the jar copies all 180 MB of it
# into a layer of its own.
COPY --chmod=0755 bin/pravaha-server bin/pravaha-health bin/
COPY --chmod=0644 lib/pravaha-server.jar lib/pravaha-server.jar

# Configuration, in layers, lowest precedence first (bin/pravaha-server passes the locations):
#
#   1. the jar's application.yaml                 the engine's defaults
#   2. the jar's pravaha-home.yaml                where things go under PRAVAHA_HOME
#   3. /opt/pravaha/conf/application.yaml         the deployment's: a mounted file or a ConfigMap
#   4. PRAVAHA_* / SPRING_* in the environment    a single override at `docker run -e`
#
# conf/ is `optional:`, so an image run with no mount still starts (and, with no security
# configured, refuses to, by design). A SPRING_CONFIG_ADDITIONAL_LOCATION set in the environment
# replaces 2 and 3 -- the chart sets it, naming both, plus its secret mount.
ENV PRAVAHA_HOME=/opt/pravaha

# The JVM reads the cgroup limit, so the only question is what share of it the HEAP may take. 50%,
# not the 75% a mostly-heap service would use: this engine's state is off-heap (inboxes, arenas,
# row stores), Arrow and Netty allocate direct buffers for every Flight call, and the spill tier
# is only fast while the page cache holds its index (docs/operations/OPERATIONS.md, "Disk"). A heap sized at
# three quarters of the limit leaves that nothing, and the kernel -- not the JVM -- decides what
# gives way.
#
# ExitOnOutOfMemoryError because a container that has died should be restarted by its
# orchestrator, not left answering liveness while every query in it is wedged.
#
# Overridden wholesale by setting PRAVAHA_JAVA_OPTS, which is the documented knob. The Arrow
# --add-opens are NOT in it, on purpose -- see below.
ENV PRAVAHA_JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"

# The Arrow flags are NOT here. bin/pravaha-server sets
#   --add-opens=java.base/java.nio=ALL-UNNAMED
#   --add-opens=java.base/java.lang=ALL-UNNAMED
# on the exec line, before PRAVAHA_JAVA_OPTS, so they cannot be lost by someone setting that
# variable -- which is exactly how they would be lost if they lived in it. Without them a Flight
# server fails inside putNext and the client sees RST_STREAM with nothing explaining why
# (docs/operations/OPERATIONS.md, "JVM flags").

USER 10001:10001

# 18080 HTTP: the operator pages, the REST read API and the actuator probes.
# 19090 Flight SQL: what the SDKs, the Python CLI and the console speak, and how a query is REGISTERED --
# there is no REST endpoint that registers one. Confusing the two is the commonest first-run
# failure.
EXPOSE 18080 19090

# The registry journal, the checkpoints, the identity store and the dead-letter queue all live
# under data/; the node's log and the audit trail under logs/. Without a volume a restart loses
# every registered query -- which the node warns about at startup rather than leaving to be
# discovered at the next restart. tmp/ is not a volume: give it a tmpfs (exec, for the native
# codecs) under --read-only, or leave it in the container's writable layer.
VOLUME ["/opt/pravaha/data", "/opt/pravaha/logs"]

# Liveness only, deliberately. Docker has one health state, and pointing it at readiness would make
# a node that is merely still restoring state look broken; Kubernetes gets both probes separately
# (deploy/helm/pravaha). bin/pravaha-health, not wget: it needs bash and nothing else, and the
# eclipse-temurin JRE carries no wget (or curl) -- a wget probe there reports a serving node
# unhealthy forever.
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD ["bin/pravaha-health", "/actuator/health/liveness"]

# No --spring.profiles.active=dev. The server refuses to start open unless a deployment says so,
# and an image that said so on everyone's behalf would put the default back where it was.
#
# The base image's own entrypoint is kept in front of the launcher rather than replaced. It is a
# no-op unless USE_SYSTEM_CA_CERTS is set, and when it is set it imports /certificates into the
# JVM truststore -- which is how a deployment whose connectors or Flight clients present a private
# CA gets that CA trusted (docs/guides/CONNECTOR_TLS.md). Dropping it, as replacing the entrypoint does,
# would leave that with no route but rebuilding the image. It execs what follows, so
# bin/pravaha-server still receives every argument.
ENTRYPOINT ["/__cacert_entrypoint.sh", "bin/pravaha-server"]
