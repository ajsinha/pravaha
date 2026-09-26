# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The console, as its own image beside the engine's (ADR-024: the console is a separate process that
# reaches the engine only through its public API). Built from this checkout, with the build context
# at the repository root so the SDK the console depends on is the one in the same commit:
#
#   deploy/docker/console/build.sh                 # -> pravaha/pravaha-console:<console version>
#
# Configured by one YAML file, /opt/pravaha/console/conf/application.yaml, separate from the
# engine's: the engine's address, its token, the console password and the session secret are
# written there by whoever deploys it (deploy/qa/prepare.sh does it for a QA host), never baked in.
# An empty password means nobody can sign in, which is the safe failure.
FROM python:3.13-slim

LABEL org.opencontainers.image.title="Pravaha console" \
      org.opencontainers.image.description="The Pravaha console, which reaches an engine over Flight SQL and its REST API." \
      org.opencontainers.image.licenses="LicenseRef-Proprietary" \
      org.opencontainers.image.vendor="Ashutosh Sinha"

# Not root, and not a shell account either; the same uid the engine image uses.
RUN groupadd --system --gid 10001 pravaha \
 && useradd --system --uid 10001 --gid pravaha --home-dir /opt/pravaha/console --shell /usr/sbin/nologin pravaha

# The SDK first and on its own layer: it changes less often than the console.
COPY --chown=10001:10001 sdk/python /tmp/sdk
RUN pip install --no-cache-dir "/tmp/sdk[flight]" && rm -rf /tmp/sdk

# Under /opt/pravaha like everything else of this product's: the code at /opt/pravaha/console, and
# two configuration files read in order, the later winning key by key:
#
#   /opt/pravaha/console/config/application.yaml  the product's defaults, from this image
#   /opt/pravaha/console/conf/application.yaml    the deployment's: mounted, edited, any key
#
# So a deployment's file names only what it changes, and the version and the rest of the product's
# own keys still come from the image a release ships -- a copied-out file cannot pin a stale one.
# The image puts an empty deployment file in place, so it starts without a mount (and, with no
# password anywhere, lets nobody sign in).
WORKDIR /opt/pravaha/console
COPY --chown=10001:10001 console /opt/pravaha/console
RUN pip install --no-cache-dir . \
 && mkdir -p conf \
 && printf '# This deployment: any key of config/application.yaml, and it wins.\n{}\n' > conf/application.yaml \
 && chown -R 10001:10001 /opt/pravaha

# Only for the defaults above, which read ${CONSOLE_HOST:127.0.0.1}: inside a container the console
# must listen beyond loopback. A mounted configuration that names server.host is what decides.
ENV CONSOLE_HOST=0.0.0.0 \
    PYTHONUNBUFFERED=1

USER 10001:10001
EXPOSE 8090
CMD ["python", "run_pravaha_web.py", "--config", "/opt/pravaha/console/config/application.yaml,/opt/pravaha/console/conf/application.yaml"]
