#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Build the console image from this checkout.
#
#   deploy/docker/console/build.sh                    # -> pravaha/pravaha-console:<console version>
#   deploy/docker/console/build.sh --tag pravaha-console:local
#
# The tag defaults to the version in console/pyproject.toml, which deploy/release/set-version.sh
# keeps in step with the engine's. On a machine reaching the daemon through a group:
#   sg docker -c "deploy/docker/console/build.sh"
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
docker_bin="${DOCKER:-docker}"
tag=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag) tag="$2"; shift 2 ;;
    -h|--help) sed -n '5,13p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "build.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [[ -z "$tag" ]]; then
  version="$(awk -F'"' '/^version = "/ { print $2; exit }' "$root/console/pyproject.toml")"
  [[ -n "$version" ]] || { echo "build.sh: no version in console/pyproject.toml" >&2; exit 1; }
  tag="pravaha/pravaha-console:$version"
fi

"$docker_bin" build -f "$root/deploy/docker/console/Dockerfile" -t "$tag" "$root"
echo "  built $tag"
