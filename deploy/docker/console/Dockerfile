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
# The same PRAVAHA_HOME layout as the engine's image, rooted at /opt/pravaha
# (docs/operations/RUNNING_IN_DOCKER.md, "The layout"):
#
#   console/ docs/ examples/     the console's code and the documentation it serves (the image's)
#   conf/console.yaml            the deployment's configuration: any key, and it wins (mounted)
#   data/console/                the assistant's configuration, usage ledger and log (PRAVAHA_CONFIG_DIR)
#   logs/pravaha-console.log     the console's log, rotated; standard error as well
#   tmp/                         TMPDIR and HOME
#
# Nothing else is written. No dev dependencies (pytest, ruff, mypy) are installed: the image is
# what runs. It also carries the Python SDK and so the `pravaha` command line, which is how
# deploy/docker/compose registers its sample query with no Python on the host.
#
# Any --user works, as with the engine's image: bind-mount conf/, data/, logs/ and tmp/ owned by
# you and run it as `--user $(id -u):$(id -g)`. The session secret and the engine's address are
# configuration, never baked in; an empty session secret is generated at start (and every restart
# signs everyone out), so a deployment sets one.
FROM python:3.13-slim

LABEL org.opencontainers.image.title="Pravaha console" \
      org.opencontainers.image.description="The Pravaha console, which reaches an engine over Flight SQL and its REST API." \
      org.opencontainers.image.licenses="LicenseRef-Proprietary" \
      org.opencontainers.image.vendor="Ashutosh Sinha"

# Not root, and not a shell account either; the same uid the engine image uses. The directories
# the console writes are 10001's and group 0's, group-writable, for an orchestrator that assigns an
# arbitrary uid in group 0; with bind mounts they are whoever owns the mount. Traversable by anyone
# (0775), because the compose stack mounts single entries beneath them -- conf/console.yaml,
# data/console -- and a user who is neither 10001 nor in group 0 has to be able to reach them.
RUN groupadd --system --gid 10001 pravaha \
 && useradd --system --uid 10001 --gid pravaha --home-dir /opt/pravaha/tmp --no-create-home \
            --shell /usr/sbin/nologin pravaha \
 && mkdir -p /opt/pravaha/conf /opt/pravaha/data/console /opt/pravaha/logs /opt/pravaha/tmp \
 && chown 10001:0 /opt/pravaha/conf /opt/pravaha/data /opt/pravaha/data/console /opt/pravaha/logs /opt/pravaha/tmp \
 && chmod 0775 /opt/pravaha/conf /opt/pravaha/data /opt/pravaha/logs /opt/pravaha/tmp \
 && chmod 0770 /opt/pravaha/data/console

# The SDK first and on its own layer: it changes less often than the console.
COPY sdk/python /tmp/sdk
RUN pip install --no-cache-dir "/tmp/sdk[flight]" && rm -rf /tmp/sdk

# The code at /opt/pravaha/console, and three configuration files read in order, the later winning
# key by key (a missing one is skipped):
#
#   /opt/pravaha/console/config/application.yaml  the product's defaults, from this image
#   /opt/pravaha/console/conf/application.yaml    where a 0.2.0 QA host mounts its file (deploy/qa)
#   /opt/pravaha/conf/console.yaml                the PRAVAHA_HOME layout's place for it
#
# So a deployment's file names only what it changes, and the version and the rest of the product's
# own keys still come from the image a release ships -- a copied-out file cannot pin a stale one.
WORKDIR /opt/pravaha/console
COPY console /opt/pravaha/console

# The documentation the console serves but does not own. A guide, a tutorial, the code browser, the
# decision records and the About page's "what is built" all read files that live at the repository
# root -- docs/, examples/case-studies/, README.md -- through `include:` paths resolved from the
# console's grandparent directory, which in this image is /opt/pravaha. The 0.1.1 image shipped
# without them: every tutorial said "not present in this installation" and the code browser listed
# three codes. So they are copied to the same relative places, and a test in this image's build
# (below) fails if one of them goes missing again.
COPY docs /opt/pravaha/docs
COPY examples/case-studies /opt/pravaha/examples/case-studies
COPY README.md LICENSE /opt/pravaha/
COPY sdk/python/README.md /opt/pravaha/sdk/python/README.md
RUN pip install --no-cache-dir . \
 && chmod -R a+rX /opt/pravaha/console /opt/pravaha/docs /opt/pravaha/examples /opt/pravaha/sdk \
 && python - <<'CHECK'
# Every include a help topic, guide or tutorial names must resolve in this image, or the page
# renders "not present in this installation" -- refused at build time instead.
import pathlib, re, sys
root = pathlib.Path("/opt/pravaha")
missing = []
for md in (root / "console" / "content").rglob("*.md"):
    m = re.search(r"^include:\s*(\S+)", md.read_text(encoding="utf-8"), re.M)
    if m and not (root / m.group(1)).is_file():
        missing.append(f"{md.relative_to(root)} -> {m.group(1)}")
if missing:
    sys.exit("console includes that this image does not carry:\n  " + "\n  ".join(missing))
print("every console include resolves")
CHECK

# CONSOLE_HOST only for the defaults above, which read ${CONSOLE_HOST:127.0.0.1}: inside a container
# the console must listen beyond loopback. A mounted configuration that names server.host decides.
# The rest put every file the console, Python or the SDK would write under /opt/pravaha.
ENV PRAVAHA_HOME=/opt/pravaha \
    CONSOLE_HOST=0.0.0.0 \
    CONSOLE_LOG_FILE=/opt/pravaha/logs/pravaha-console.log \
    PRAVAHA_CONFIG_DIR=/opt/pravaha/data/console \
    HOME=/opt/pravaha/tmp \
    TMPDIR=/opt/pravaha/tmp \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

USER 10001:10001
EXPOSE 17070

# What the console writes: the assistant's files and its log. Declared at exactly the paths the compose
# stack bind-mounts, so a mounted run creates no anonymous volume beside them.
VOLUME ["/opt/pravaha/data/console", "/opt/pravaha/logs"]

# Liveness only: /health/live never consults the engine, so a console pointed at an engine that is
# still starting is not reported unhealthy (/health/ready is the one that asks).
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
  CMD ["python", "-c", "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:17070/health/live', timeout=2).status == 200 else 1)"]

# Last, so a new revision re-labels the image without rebuilding a layer above.
ARG PRAVAHA_VERSION=0.0.0-unset
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown
LABEL org.opencontainers.image.version="${PRAVAHA_VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.authors="Ashutosh Sinha <ajsinha@gmail.com>" \
      org.opencontainers.image.source="https://github.com/ajsinha/pravaha"

CMD ["python", "run_pravaha_web.py", "--config", "/opt/pravaha/console/config/application.yaml,/opt/pravaha/console/conf/application.yaml,/opt/pravaha/conf/console.yaml"]
