#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The release scripts' test.
#
#   deploy/release/test.sh
#
# It never edits the real tree. Every case runs against a throwaway copy of the files that carry
# a version -- the poms' skeletons, the two pyproject.toml files and Chart.yaml -- built in a
# temporary directory and removed afterwards. A version-setting script tested in place is a
# script whose first bug is a dirty working tree.
#
# Four of the seven cases are SEEDS: they break something on purpose and fail if the check passes
# anyway.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

work="$(mktemp -d "${TMPDIR:-/tmp}/pravaha-release-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok:   $*"; pass=$((pass + 1)); }

# ---------------------------------------------------------------- a throwaway repository

# Copied, not generated: the test then exercises the real shapes -- a root pom with no <parent>,
# children whose first <version> is their parent's, a chart, two wheels -- rather than shapes the
# test author imagined.
build_fixture() {
  local dst="$1"
  rm -rf "$dst"
  mkdir -p "$dst"
  (cd "$root" && find . -name pom.xml -not -path "./.git/*" -not -path "*/target/*" -not -path "./.claude/*") \
    | while read -r pom; do
        mkdir -p "$dst/$(dirname "$pom")"
        cp "$root/$pom" "$dst/$pom"
      done
  mkdir -p "$dst/sdk/python" "$dst/console" "$dst/deploy/helm/pravaha" "$dst/deploy/release"
  cp "$root/sdk/python/pyproject.toml"      "$dst/sdk/python/pyproject.toml"
  cp "$root/console/pyproject.toml"         "$dst/console/pyproject.toml"
  cp "$root/deploy/helm/pravaha/Chart.yaml" "$dst/deploy/helm/pravaha/Chart.yaml"
  cp "$here/set-version.sh" "$here/version.sh" "$dst/deploy/release/"
  chmod +x "$dst/deploy/release/"*.sh
}

fixture="$work/repo"
build_fixture "$fixture"
setv() { "$fixture/deploy/release/set-version.sh" "$@"; }

# ---------------------------------------------------------------- 1. the tree agrees today

setv --check >/dev/null || fail "the repository's own files do not agree about the version.
This is not a test failure in the script -- run deploy/release/set-version.sh --check and read it."
ok "the repository agrees about its version today"

# ---------------------------------------------------------------- 2. a release version

out="$(setv 0.2.0)"
grep -q '37 poms' <<<"$out" || grep -q 'poms, 2 wheels and the chart all say 0.2.0' <<<"$out" \
  || fail "set-version 0.2.0 did not report a consistent tree:
$out"
[[ "$("$fixture/deploy/release/version.sh" "$fixture")" == "0.2.0" ]] || fail "the root pom is not 0.2.0"
grep -q '^version = "0.2.0"$' "$fixture/sdk/python/pyproject.toml" || fail "the Python SDK wheel is not 0.2.0"
grep -q '^version = "0.2.0"$' "$fixture/console/pyproject.toml"    || fail "the console wheel is not 0.2.0"
grep -q '^appVersion: "0.2.0"$' "$fixture/deploy/helm/pravaha/Chart.yaml" || fail "the chart's appVersion is not 0.2.0"
grep -q '^version: 0.1.0$' "$fixture/deploy/helm/pravaha/Chart.yaml" \
  || fail "the CHART's own version moved. It is not the engine's and must not follow it without --chart-version."
ok "0.2.0 reached every pom, both wheels and the chart's appVersion -- and not the chart's own version"

# Every pom, not just the root. The failure this guards is a reactor that will not resolve.
stale="$(grep -rl '0\.1\.0-SNAPSHOT' "$fixture" --include=pom.xml || true)"
[[ -z "$stale" ]] || fail "poms left at the old version:
$stale"
ok "no pom left behind"

# ---------------------------------------------------------------- 3. back to a snapshot

setv 0.2.1-SNAPSHOT >/dev/null
[[ "$("$fixture/deploy/release/version.sh" "$fixture")" == "0.2.1-SNAPSHOT" ]] || fail "the root pom is not 0.2.1-SNAPSHOT"
grep -q '^version = "0.2.1"$' "$fixture/sdk/python/pyproject.toml" \
  || fail "the Python wheel did not get the -SNAPSHOT stripped; PEP 440 has no snapshot"
ok "a snapshot sets the poms to 0.2.1-SNAPSHOT and the wheels to 0.2.1"

# ---------------------------------------------------------------- 4. the chart's own version

setv 0.3.0 --chart-version 2.0.0 >/dev/null
grep -q '^version: 2.0.0$' "$fixture/deploy/helm/pravaha/Chart.yaml" || fail "--chart-version was ignored"
grep -q '^appVersion: "0.3.0"$' "$fixture/deploy/helm/pravaha/Chart.yaml" || fail "appVersion was not set alongside it"
ok "--chart-version moves the chart's own version, separately"

# ---------------------------------------------------------------- 5. SEED: one pom left behind

build_fixture "$fixture"
perl -0pi -e 's|<version>0.1.0-SNAPSHOT</version>|<version>0.1.0-OTHER</version>|' \
  -- "$fixture/pravaha-server/pom.xml"
if setv --check >/dev/null 2>&1; then
  fail "--check passed with pravaha-server at a different version. A release from that tree would
ship artefacts naming two versions and the reactor would not resolve."
fi
message="$(setv --check 2>&1 || true)"
grep -q 'pravaha-server/pom.xml is 0.1.0-OTHER' <<<"$message" \
  || fail "--check failed, but did not name the file and the two versions:
$message"
ok "SEED: one pom at a different version is caught, and named"

# ---------------------------------------------------------------- 6. SEED: a stale wheel

build_fixture "$fixture"
perl -0pi -e 's|^version = "0.1.0"|version = "0.0.9"|m' -- "$fixture/sdk/python/pyproject.toml"
if setv --check >/dev/null 2>&1; then
  fail "--check passed with the Python SDK wheel at 0.0.9"
fi
ok "SEED: a wheel left at the old version is caught"

# ---------------------------------------------------------------- 7. SEED: a version that is not one

build_fixture "$fixture"
for bad in "v1.2.3" "1.2" "latest" "1.2.3.4" ""; do
  if setv "$bad" >/dev/null 2>&1; then
    fail "SEED: '$bad' was accepted as a version"
  fi
done
# And the tree was not touched on the way to refusing.
setv --check >/dev/null || fail "a refused version still edited the tree"
ok "SEED: v1.2.3, 1.2, latest and 1.2.3.4 are refused, and nothing is edited"

echo
echo "test.sh: $pass checks PASSED"
