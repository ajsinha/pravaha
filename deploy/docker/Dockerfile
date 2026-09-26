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
# This one is what a release publishes. docs/DEPLOYMENT.md says which to reach for.

# Alpine, not the Ubuntu-based tag: 287 MB against 459 MB for the same JRE, and busybox brings a
# `wget` the HEALTHCHECK below can use without installing a package. Measured, not assumed --
# ADR-047 records the musl question and how it was answered (the smoke journey runs on this image).
FROM eclipse-temurin:21-jre-alpine

# A deployment wants to know what it is running without starting it.
ARG PRAVAHA_VERSION=0.0.0-unset
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.title="Pravaha engine node" \
      org.opencontainers.image.description="Continuous-query engine node: HTTP on 8080, Flight SQL on 9090." \
      org.opencontainers.image.version="${PRAVAHA_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.authors="Ashutosh Sinha <ajsinha@gmail.com>" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary" \
      org.opencontainers.image.source="https://github.com/ajsinha/pravaha"

WORKDIR /opt/pravaha

# One layer, three things.
#
# bash, because bin/pravaha-server is a bash script and Alpine ships only busybox ash. ~2 MB, and
# the alternative -- rewriting the shipped launcher to POSIX sh so one base image is happier --
# would change the thing every other way of running this server goes through. Paid here, once.
#
# Not root, and not a shell account either. The engine needs no privileged port -- 8080 and 9090
# are both above 1024 -- and a streaming engine that reads whatever a binding points it at is
# precisely the thing that should not be able to read the rest of the filesystem. 10001 rather
# than a name, because a Kubernetes runAsUser and a `docker run --user` both take a number, and a
# chart that has to guess the image's uid gets it wrong once.
#
# Every path the node touches is under /opt/pravaha, and nothing else on the filesystem is the
# node's: bin/ and lib/ (the launcher and the jar), defaults/ (this image's configuration),
# conf/ (the deployment's, mounted), secrets/ (mounted key material), data/ (the volume: journal,
# checkpoints, dead letters, spill) and logs/ (the audit file). One root to mount, back up and
# reason about, and the same shape as the release tarball unpacked at /opt/pravaha.
#
# The data directories, 0750, owned by that uid. The engine narrows them again before its first
# write; creating them wrong first would make the first run's failure a permissions puzzle.
RUN apk add --no-cache bash \
 && addgroup -S -g 10001 pravaha \
 && adduser  -S -u 10001 -G pravaha -H -s /sbin/nologin pravaha \
 && mkdir -p /opt/pravaha/data/checkpoints /opt/pravaha/data/dlq /opt/pravaha/data/spill \
              /opt/pravaha/conf /opt/pravaha/defaults /opt/pravaha/logs /opt/pravaha/secrets \
 && chown -R 10001:10001 /opt/pravaha \
 && chmod 0750 /opt/pravaha/data /opt/pravaha/logs /opt/pravaha/secrets

# The launcher, which is where the Arrow --add-opens live (see below), then the jar, then the
# image's own configuration defaults. Ordered cheapest-changing first so a rebuild of the same
# release reuses the layers above the jar.
COPY --chown=10001:10001 bin/pravaha-server bin/pravaha-server
COPY --chown=10001:10001 conf/application.yaml defaults/application.yaml
COPY --chown=10001:10001 lib/pravaha-server.jar lib/pravaha-server.jar
RUN chmod 0755 bin/pravaha-server

# Configuration, in three layers, lowest precedence first. Spring Boot gives a location named later
# in spring.config.additional-location precedence over one named earlier, and an OS environment
# variable precedence over both -- so:
#
#   1. /opt/pravaha/defaults/application.yaml -- this image's data-directory defaults
#   2. /opt/pravaha/conf/application.yaml     -- a mounted file or a Kubernetes ConfigMap
#   3. PRAVAHA_* / SPRING_* in the environment -- a single override at `docker run -e`
#
# Both file locations are `optional:`, so an image run with neither still starts (on the jar's own
# application.yaml) instead of failing on a missing file.
ENV SPRING_CONFIG_ADDITIONAL_LOCATION="optional:file:/opt/pravaha/defaults/,optional:file:/opt/pravaha/conf/"

# JDK 21 reads the cgroup limit, so the only question is what share of it the HEAP may take. 50%,
# not the 75% a mostly-heap service would use: this engine's state is off-heap (inboxes, arenas,
# row stores), Arrow and Netty allocate direct buffers for every Flight call, and the spill tier
# is only fast while the page cache holds its index (docs/OPERATIONS.md, "Disk"). A heap sized at
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
# (docs/OPERATIONS.md, "JVM flags").

USER 10001:10001

# 8080 HTTP: the operator pages, the REST read API and the actuator probes.
# 9090 Flight SQL: what the SDKs, the CLI and the console speak, and how a query is REGISTERED --
# there is no REST endpoint that registers one. Confusing the two is the commonest first-run
# failure.
EXPOSE 8080 9090

# The registry journal, the checkpoints and the dead-letter queue all live under here. Without a
# volume a restart loses every registered query -- which the node warns about at startup rather
# than leaving to be discovered at the next restart.
VOLUME ["/opt/pravaha/data"]

# Liveness only, deliberately. Docker has one health state, and pointing it at readiness would make
# a node that is merely still restoring state look broken; Kubernetes gets both probes separately
# (deploy/helm/pravaha). --spider because busybox wget writes the body to stdout otherwise.
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD wget -q --spider http://127.0.0.1:8080/actuator/health/liveness || exit 1

# No --spring.profiles.active=dev. The server refuses to start open unless a deployment says so,
# and an image that said so on everyone's behalf would put the default back where it was.
#
# The base image's own entrypoint is kept in front of the launcher rather than replaced. It is a
# no-op unless USE_SYSTEM_CA_CERTS is set, and when it is set it imports /certificates into the
# JVM truststore -- which is how a deployment whose connectors or Flight clients present a private
# CA gets that CA trusted (docs/CONNECTOR_TLS.md). Dropping it, as replacing the entrypoint does,
# would leave that with no route but rebuilding the image. It execs what follows, so
# bin/pravaha-server still receives every argument.
ENTRYPOINT ["/__cacert_entrypoint.sh", "bin/pravaha-server"]
