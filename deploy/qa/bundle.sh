#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Assemble the QA bundle: everything a QA host needs, as files, with no registry.
#
#   deploy/qa/bundle.sh                      # the version in the root pom; images must exist
#   deploy/qa/bundle.sh --version 0.1.1
#
# Run after deploy/release/release.sh, which builds and smoke-tests both images. Writes
# target/qa-bundle/pravaha-qa-<version>/ and a .tar.gz of it beside, both under target/ and
# never committed. Copy the .tar.gz to the host, unpack it, and run `sudo ./install.sh`.
#
#   install.sh  docker-compose.yml  server.application.yaml  console.application.yaml  README.md
#   VERSION     images/pravaha-server-<v>.tar  images/pravaha-console-<v>.tar
#   dist/       the server fat jar, the CLI jar, the SDK and console wheels, the Helm chart
#   docs/       RELEASE_NOTES.md, DEPLOYMENT.md, QUICKSTART.md, USER_GUIDE.md, PYTHON_API_GUIDE.md
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
docker_bin="${DOCKER:-docker}"
version="$("$root/deploy/release/version.sh" 2>/dev/null || true)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) version="$2"; shift 2 ;;
    -h|--help) sed -n '5,19p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "bundle.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done
[[ -n "$version" ]] || { echo "bundle.sh: no version; pass --version" >&2; exit 2; }

out="$root/target/qa-bundle/pravaha-qa-$version"
rm -rf "$out"
mkdir -p "$out/images" "$out/dist" "$out/docs"

for f in install.sh docker-compose.yml server.application.yaml console.application.yaml README.md; do
  cp "$root/deploy/qa/$f" "$out/$f"
done
echo "$version" > "$out/VERSION"

for image in pravaha-server pravaha-console; do
  "$docker_bin" image inspect "pravaha/$image:$version" >/dev/null 2>&1 \
    || { echo "bundle.sh: no image pravaha/$image:$version -- run deploy/release/release.sh first" >&2; exit 1; }
  echo "bundle.sh: saving pravaha/$image:$version"
  "$docker_bin" save "pravaha/$image:$version" -o "$out/images/$image-$version.tar"
done

# What exists is copied; what does not is said, rather than a bundle quietly missing a piece.
shopt -s nullglob
take() {
  local present=() f
  for f in "$@"; do [[ -e "$f" ]] && present+=("$f"); done
  if (( ${#present[@]} )); then cp "${present[@]}" "$out/dist/"; else echo "bundle.sh: not built: $label" >&2; fi
}
label="server jar";  take "$root"/pravaha-server/target/pravaha-server-"$version"-app.jar
label="cli jar";     take "$root"/pravaha-cli/target/pravaha-cli-"$version"-cli.jar
# The wheels are built from the release's TAG, not the working tree, which release.sh has already
# moved on to the next snapshot -- and in a python container, so the build needs nothing installed
# on this machine beyond Docker.
wheels="$root/target/wheels"
if ! compgen -G "$wheels/pravaha-$version-*.whl" >/dev/null || ! compgen -G "$wheels/pravaha_console-$version-*.whl" >/dev/null; then
  git -C "$root" rev-parse -q --verify "refs/tags/v$version" >/dev/null \
    || { echo "bundle.sh: no tag v$version to build the wheels from" >&2; exit 1; }
  echo "bundle.sh: building the $version wheels from v$version"
  rm -rf "$root/target/release-src" && mkdir -p "$root/target/release-src" "$wheels"
  git -C "$root" archive "v$version" sdk/python console | tar -x -C "$root/target/release-src"
  "$docker_bin" run --rm --user "$(id -u):$(id -g)" -e HOME=/tmp -v "$root/target:/w" python:3.13-slim sh -c \
    'pip install -q --user hatchling && for p in sdk/python console; do python -m pip wheel -q --no-deps --no-build-isolation -w /w/wheels /w/release-src/$p || exit 1; done'
fi
label="sdk wheel";   take "$wheels"/pravaha-"$version"-*.whl
label="console wheel"; take "$wheels"/pravaha_console-"$version"-*.whl
# helm package's archive if release.sh had helm; otherwise the chart directory from the tag, which
# `helm install pravaha pravaha-<v>.tgz` takes just the same.
if [[ ! -e "$root/target/pravaha-$version.tgz" ]] && git -C "$root" rev-parse -q --verify "refs/tags/v$version" >/dev/null; then
  git -C "$root" archive --format=tar.gz -o "$root/target/pravaha-$version.tgz" "v$version:deploy/helm" pravaha
fi
label="helm chart";  take "$root"/target/pravaha-"$version".tgz
for doc in RELEASE_NOTES.md DEPLOYMENT.md QUICKSTART.md USER_GUIDE.md PYTHON_API_GUIDE.md; do cp "$root/docs/$doc" "$out/docs/"; done

(cd "$out" && sha256sum VERSION install.sh docker-compose.yml *.yaml images/* dist/* > SHA256SUMS)
tar -C "$(dirname "$out")" -czf "$out.tar.gz" "$(basename "$out")"
echo
echo "bundle.sh: $out.tar.gz ($(du -h "$out.tar.gz" | cut -f1))"
