#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Build the engine node image from artefacts the reactor has already produced.
#
#   ./mvnw -o -pl pravaha-server -am package -DskipTests
#   deploy/docker/build.sh                       # -> pravaha/pravaha-server:<project version>
#   deploy/docker/build.sh --tag pravaha:local   # an explicit tag instead
#   deploy/docker/build.sh --java 25             # on a Java 25 JRE -> ...:<project version>-jre25
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
# The JRE the image runs on: 21 (the default, untagged) or 25. The jar is the same either way.
java_version=21
docker_bin="${DOCKER:-docker}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag|-t) image="$2"; shift 2 ;;
    --push)   push=1; shift ;;
    --java)   java_version="$2"; shift 2 ;;
    -h|--help) sed -n '5,18p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "build.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# The version is the reactor's, read from the root pom rather than passed in: an image tagged with
# a version the artefact inside it does not carry is worse than an untagged one.
version="$("$root/deploy/release/version.sh" "$root")"
case "$java_version" in
  21) default_tag="$version" ;;
  25) default_tag="$version-jre25" ;;
  *) echo "build.sh: --java $java_version: the supported JREs are 21 and 25" >&2; exit 2 ;;
esac
image="${image:-pravaha/pravaha-server:$default_tag}"

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
echo "  java     $java_version (eclipse-temurin:$java_version-jre)"

"$docker_bin" build \
  --tag "$image" \
  --build-arg "JAVA_VERSION=$java_version" \
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
