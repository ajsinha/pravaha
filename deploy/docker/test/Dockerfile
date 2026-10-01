# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The test runner tools/docker-test.sh uses: a JDK 25 with Maven, and Python 3 with venv, in one
# image. One image and not two because the Python suites are cross-language -- the SDK's and the
# console's tests start the real Flight server from pravaha-flight's test classpath, and without
# Java in the same container they cannot. Built by docker-test.sh on first use, tagged
# pravaha/test-runner:local; nothing of Pravaha is in it, the checkout is mounted.
#
# Runs as whoever docker-test.sh says (--user $(id -u):$(id -g)), with HOME and every cache on a
# mounted directory, so it needs no user of its own and writes nothing into the image.
# JDK 25, and only 25, as the engine image: from 2.0 the reactor builds Java 25 class files
# (ADR-061), so a test run here proves the code on the JVM the image ships.
FROM maven:3.9-eclipse-temurin-25

RUN apt-get update \
 && apt-get install -y --no-install-recommends python3 python3-venv \
 && rm -rf /var/lib/apt/lists/*

# Not the maven image's entrypoint, which copies settings into /root/.m2 -- unwritable, and beside the
# point, for a container running as the invoking user with its own HOME.
ENTRYPOINT []
CMD ["bash"]

LABEL org.opencontainers.image.title="Pravaha test runner" \
      org.opencontainers.image.description="JDK 25, Maven and Python 3 for tools/docker-test.sh" \
      org.opencontainers.image.licenses="LicenseRef-Proprietary"
