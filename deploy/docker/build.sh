#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Build the engine node image from artefacts the reactor has already produced.
#
#   ./mvnw -o -pl pravaha-server -am package -DskipTests
#   deploy/docker/build.sh                       # Java 21 JRE -> pravaha/pravaha-server:<project version>
#   deploy/docker/build.sh --tag pravaha:local   # an explicit tag instead
#
# The image runs on a Java 21 JRE, the floor every newer JRE also runs (ADR-062); there is one image
# and no --java option.
#
# It does NOT run Maven. A missing jar is an error naming the command that produces it, because a
# script that quietly rebuilds turns "the image is stale" into "the image is a different build".
#
# On a machine where the daemon is reached through a group, put the whole line inside it:
#   sg docker -c "deploy/docker/build.sh"
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

image=""
push=0
docker_bin="${DOCKER:-docker}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag|-t) image="$2"; shift 2 ;;
    --push)   push=1; shift ;;
    --java)
      echo "build.sh: there is no --java option: the image is built on a Java 21 JRE, which every newer JRE also runs (ADR-062)" >&2
      exit 2 ;;
    -h|--help) sed -n '5,19p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "build.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# The version is the reactor's, read from the root pom rather than passed in: an image tagged with
# a version the artefact inside it does not carry is worse than an untagged one.
version="$("$root/deploy/release/version.sh" "$root")"
image="${image:-pravaha/pravaha-server:$version}"

jar="$(ls -1 "$root"/pravaha-server/target/pravaha-server-*-app.jar 2>/dev/null | head -1 || true)"
if [[ -z "$jar" ]]; then
  cat >&2 <<EOF
build.sh: no pravaha-server application jar in $root/pravaha-server/target.

  The image packages an artefact; it does not build one. Produce it with:

      ./mvnw -o -pl pravaha-server -am package -DskipTests

  (Offline. Drop -o for the first build on a machine with an empty ~/.m2.)
EOF
  exit 1
fi

# A staging context, so the daemon is sent ~80 MB rather than the repository. Removed on every
# exit, including a failure -- a half-built context left in /tmp is the next build's mystery.
staging="$(mktemp -d "${TMPDIR:-/tmp}/pravaha-image.XXXXXX")"
trap 'rm -rf "$staging"' EXIT

mkdir -p "$staging/bin" "$staging/lib"
cp "$root/bin/pravaha-server" "$staging/bin/pravaha-server"
cp "$root/bin/pravaha-health" "$staging/bin/pravaha-health"
cp "$jar"                     "$staging/lib/pravaha-server.jar"
cp "$here/Dockerfile"         "$staging/Dockerfile"

vcs_ref="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
if ! git -C "$root" diff --quiet HEAD 2>/dev/null; then
  vcs_ref="$vcs_ref-dirty"
fi
build_date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo "build.sh: $image"
echo "  jar      $(basename "$jar") ($(du -h "$jar" | cut -f1))"
echo "  revision $vcs_ref"
echo "  java     21 (eclipse-temurin:21-jre)"

"$docker_bin" build \
  --tag "$image" \
  --build-arg "PRAVAHA_VERSION=$version" \
  --build-arg "VCS_REF=$vcs_ref" \
  --build-arg "BUILD_DATE=$build_date" \
  "$staging"

echo
"$docker_bin" image inspect "$image" \
  --format '  built {{.Id}}
  size  {{.Size}} bytes
  user  {{.Config.User}}
  entry {{.Config.Entrypoint}}'

if [[ "$push" == 1 ]]; then
  echo "build.sh: pushing $image"
  "$docker_bin" push "$image"
fi
