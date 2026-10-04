# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The engine node, built FROM SOURCE inside Docker: clone, `docker build -t pravaha-server:src .`,
# wait. Needs nothing on the machine but Docker -- no JDK, no Maven, no Python -- and network
# access to Maven Central for the first build.
#
# deploy/docker/Dockerfile is the release image: the same runtime stage, over a jar the reactor has
# already built (ADR-047). The two must produce the same layout; docs/operations/RUNNING_IN_DOCKER.md says which
# to reach for. This one additionally carries bin/pravaha-engine, the offline Java CLI.

# JDK 21, as deploy/docker/Dockerfile: the classes are Java 21 class files (ADR-062), built on the
# floor and run on it. Any JDK from 21 up builds the same classes; 21 keeps the two stages one family.

# ---- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

COPY . .
# Only the node and the offline CLI, and what they depend on. The benchmarks, the integration
# module and the tests are not what an image is made of; the reactor's gate is where they run.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -q -pl pravaha-server,pravaha-cli -am -DskipTests -Djacoco.skip=true \
      -Dspotless.check.skip=true package

# ---- run --------------------------------------------------------------------
# Kept in step with deploy/docker/Dockerfile's runtime stage: same base, same uid, same layout.
FROM eclipse-temurin:21-jre

LABEL org.opencontainers.image.title="Pravaha engine node (source build)" \
      org.opencontainers.image.description="Continuous-query engine node: HTTP on 18080, Flight SQL on 19090." \
      org.opencontainers.image.authors="Ashutosh Sinha <ajsinha@gmail.com>" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary" \
      org.opencontainers.image.source="https://github.com/ajsinha/pravaha" \
      com.ash.messaging.pravaha.java="21"

WORKDIR /opt/pravaha

# The PRAVAHA_HOME layout: bin/ lib/ (the image's), conf/ secrets/ plugins/ (the deployment's),
# data/ logs/ tmp/ (the node's). Any --user works over bind mounts; see deploy/docker/Dockerfile.
RUN groupadd --system --gid 10001 pravaha \
 && useradd --system --uid 10001 --gid pravaha --no-create-home --home-dir /opt/pravaha/tmp \
            --shell /usr/sbin/nologin pravaha \
 && mkdir -p /opt/pravaha/bin /opt/pravaha/lib /opt/pravaha/conf /opt/pravaha/plugins \
             /opt/pravaha/data /opt/pravaha/logs /opt/pravaha/secrets /opt/pravaha/tmp \
 && chown 10001:0 /opt/pravaha/conf /opt/pravaha/plugins /opt/pravaha/data /opt/pravaha/logs \
                  /opt/pravaha/secrets /opt/pravaha/tmp \
 && chmod 0770 /opt/pravaha/conf /opt/pravaha/plugins /opt/pravaha/data /opt/pravaha/logs /opt/pravaha/tmp \
 && chmod 0700 /opt/pravaha/secrets

COPY --from=build --chmod=0755 /src/bin/pravaha-server /src/bin/pravaha-engine /src/bin/pravaha-health bin/
COPY --from=build --chmod=0644 /src/pravaha-server/target/pravaha-server-*-app.jar lib/pravaha-server.jar
COPY --from=build --chmod=0644 /src/pravaha-cli/target/pravaha-cli-*-cli.jar       lib/pravaha-engine.jar

ENV PRAVAHA_HOME=/opt/pravaha \
    PRAVAHA_JAVA_OPTS="-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError"

USER 10001:10001

# 18080 HTTP and the operator pages; 19090 Flight SQL, which is what the SDKs and the Python CLI speak.
EXPOSE 18080 19090

VOLUME ["/opt/pravaha/data", "/opt/pravaha/logs"]

# Liveness only. Readiness is deliberately separate: conflating them makes an orchestrator restart
# a node that is merely still restoring state.
# bin/pravaha-health needs only bash: the JRE base has no wget or curl.
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 \
  CMD ["bin/pravaha-health", "/actuator/health/liveness"]

# No --spring.profiles.active=dev here. The server refuses to start open unless a deployment says
# so, and an image that said so on everyone's behalf would put the default back where it was.
ENTRYPOINT ["/__cacert_entrypoint.sh", "bin/pravaha-server"]
