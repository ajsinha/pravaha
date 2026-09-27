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

# The documentation the console serves but does not own. A guide, a tutorial, the code browser, the
# decision records and the About page's "what is built" all read files that live at the repository
# root -- docs/, examples/case-studies/, README.md -- through `include:` paths resolved from the
# console's grandparent directory, which in this image is /opt/pravaha. The 0.1.1 image shipped
# without them: every tutorial said "not present in this installation" and the code browser listed
# three codes. So they are copied to the same relative places, and a test in this image's build
# (below) fails if one of them goes missing again.
COPY --chown=10001:10001 docs /opt/pravaha/docs
COPY --chown=10001:10001 examples/case-studies /opt/pravaha/examples/case-studies
COPY --chown=10001:10001 README.md LICENSE /opt/pravaha/
COPY --chown=10001:10001 sdk/python/README.md /opt/pravaha/sdk/python/README.md
RUN pip install --no-cache-dir . \
 && mkdir -p conf \
 && printf '# This deployment: any key of config/application.yaml, and it wins.\n{}\n' > conf/application.yaml \
 && chown -R 10001:10001 /opt/pravaha \
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

# Only for the defaults above, which read ${CONSOLE_HOST:127.0.0.1}: inside a container the console
# must listen beyond loopback. A mounted configuration that names server.host is what decides.
ENV CONSOLE_HOST=0.0.0.0 \
    PYTHONUNBUFFERED=1

USER 10001:10001
EXPOSE 17070
CMD ["python", "run_pravaha_web.py", "--config", "/opt/pravaha/console/config/application.yaml,/opt/pravaha/console/conf/application.yaml"]
