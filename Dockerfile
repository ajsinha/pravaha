# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.

# ---- build ------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# Dependencies resolve against their own layer, so editing source does not re-download the world.
COPY pom.xml ./
COPY pravaha-bom/pom.xml pravaha-bom/
COPY .mvn/ .mvn/
COPY mvnw ./

COPY . .
RUN ./mvnw -B -q -DskipTests package

# ---- run --------------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /opt/pravaha

# Not root. The engine needs no privileged port -- 8080 and 9090 are both above 1024 -- and a
# streaming engine that reads whatever a binding points it at is precisely the thing that should
# not be able to read the rest of the filesystem.
RUN useradd --system --create-home --uid 10001 pravaha \
 && mkdir -p /opt/pravaha/data \
 && chown -R pravaha:pravaha /opt/pravaha

COPY --from=build /src/pravaha-server/target/pravaha-server-*-app.jar lib/pravaha-server.jar
COPY --from=build /src/pravaha-cli/target/pravaha-cli-*-cli.jar       lib/pravaha-cli.jar
COPY --from=build /src/bin/ bin/
RUN chmod +x bin/*

USER pravaha

# 8080 HTTP and the operator pages; 9090 Flight SQL, which is what the SDKs and the CLI speak.
# Confusing the two is the commonest way a first run fails.
EXPOSE 8080 9090

# The registry journal belongs on a volume. Without one, a restart loses every registered query --
# which the node warns about at startup rather than leaving to be discovered at the next restart.
VOLUME ["/opt/pravaha/data"]

# Liveness only. Readiness is deliberately separate: conflating them makes an orchestrator restart
# a node that is merely still restoring state.
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health/liveness || exit 1"]

# No --spring.profiles.active=dev here. The server refuses to start open unless a deployment says
# so, and an image that said so on everyone's behalf would put the default back where it was.
ENTRYPOINT ["bin/pravaha-server"]
