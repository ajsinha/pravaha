#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The CI helpers' own test: check-workflows.py and assert-suite-ran.sh.
#
#   deploy/ci/test.sh
#
# It runs each of them against the real repository AND against deliberately broken copies in a
# temporary directory. A checker is only worth having if somebody has watched it say no.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

work="$(mktemp -d "${TMPDIR:-/tmp}/pravaha-ci-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok:   $*"; pass=$((pass + 1)); }

check="$here/check-workflows.py"
assert="$here/assert-suite-ran.sh"

# ---------------------------------------------------------------- check-workflows: the real ones

python3 "$check" "$root/.github/workflows" >/dev/null \
  || fail "the repository's own workflows do not pass check-workflows.py.
Run: python3 deploy/ci/check-workflows.py"
ok "every workflow in .github/workflows parses and is wired"

# Every workflow file must be seen. A checker that silently looks at three of five is worse than
# none, and the count is the only thing that catches a glob that stopped matching.
count="$(find "$root/.github/workflows" -name '*.yml' -o -name '*.yaml' | wc -l)"
reported="$(python3 "$check" "$root/.github/workflows" | grep -c '^ok  ')"
[[ "$count" == "$reported" ]] || fail "$count workflow files on disk, $reported checked"
ok "all $count workflow files were checked, not a subset"

# ---------------------------------------------------------------- check-workflows: the seeds

seed() {
  local name="$1"; local body="$2"; local expect="$3"
  local dir="$work/seed-$name"
  mkdir -p "$dir"
  printf '%s' "$body" > "$dir/broken.yml"
  local out
  if out="$(python3 "$check" "$dir" 2>&1)"; then
    fail "SEED '$name' was accepted:
$body"
  fi
  grep -q -- "$expect" <<<"$out" || fail "SEED '$name' was rejected, but not for '$expect':
$out"
  ok "SEED: $name"
}

seed "malformed YAML" 'name: x
jobs:
  a:
   - this: [is
' "does not parse as YAML"

seed "no trigger" 'name: x
jobs:
  a:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - run: true
' "has no \`on:\` trigger"

seed "no timeout" 'name: x
on: push
jobs:
  a:
    runs-on: ubuntu-latest
    steps:
      - run: true
' "has no timeout-minutes"

seed "a step that does nothing" 'name: x
on: push
jobs:
  a:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - name: nothing
' "has neither"

seed "a floating action ref" 'name: x
on: push
jobs:
  a:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout@main
' "a floating ref"

seed "an unversioned action" 'name: x
on: push
jobs:
  a:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/checkout
' "with no version"

seed "a JDK other than 25" 'name: x
on: push
jobs:
  a:
    runs-on: ubuntu-latest
    timeout-minutes: 5
    steps:
      - uses: actions/setup-java@v4
        with:
          java-version: '"'"'21'"'"'
          distribution: temurin
' "sets up Java 21"

seed "no jobs" 'name: x
on: push
' "has no jobs"

# An empty directory must fail, not pass vacuously.
mkdir -p "$work/empty"
if python3 "$check" "$work/empty" >/dev/null 2>&1; then
  fail "SEED: an empty workflow directory passed"
fi
ok "SEED: an empty workflow directory is a failure, not a pass"

# ---------------------------------------------------------------- assert-suite-ran

# Nothing built: it must refuse rather than shrug.
mkdir -p "$work/nothing"
if PRAVAHA_ROOT="$work/nothing" "$assert" failsafe 1 >/dev/null 2>&1; then
  fail "SEED: assert-suite-ran passed with no reports at all"
fi
ok "SEED: no failsafe reports at all is a failure"

# A suite that ran but skipped everything -- the exact shape of a Testcontainers run with no
# Docker daemon -- must also be a failure.
mkdir -p "$work/skipped/pravaha-it/target/failsafe-reports"
cat > "$work/skipped/pravaha-it/target/failsafe-reports/A.txt" <<'TXT'
-------------------------------------------------------------------------------
Test set: com.example.AIT
-------------------------------------------------------------------------------
Tests run: 7, Failures: 0, Errors: 0, Skipped: 7, Time elapsed: 0.01 s
TXT
if PRAVAHA_ROOT="$work/skipped" "$assert" failsafe 1 >/dev/null 2>&1; then
  fail "SEED: assert-suite-ran passed a run where every test skipped"
fi
ok "SEED: seven tests, seven skipped, is a failure"

# And a real run passes.
mkdir -p "$work/ran/pravaha-it/target/failsafe-reports"
cat > "$work/ran/pravaha-it/target/failsafe-reports/A.txt" <<'TXT'
Tests run: 12, Failures: 0, Errors: 0, Skipped: 2, Time elapsed: 30 s
TXT
cat > "$work/ran/pravaha-it/target/failsafe-reports/B.txt" <<'TXT'
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 9 s
TXT
out="$(PRAVAHA_ROOT="$work/ran" "$assert" failsafe 15)" \
  || fail "assert-suite-ran rejected a run of 15 executed tests against a floor of 15:
$out"
grep -q '15 executed' <<<"$out" || fail "the count is wrong: $out"
ok "17 tests, 2 skipped, 15 executed, floor 15: passes and says so"

# One above the floor must fail, so the floor is a floor and not a decoration.
if PRAVAHA_ROOT="$work/ran" "$assert" failsafe 16 >/dev/null 2>&1; then
  fail "SEED: the floor is not enforced"
fi
ok "SEED: 15 executed against a floor of 16 is a failure"

"$assert" nonsense 1 >/dev/null 2>&1 && fail "SEED: an unknown suite kind was accepted"
ok "SEED: an unknown suite kind is refused"

echo
echo "test.sh: $pass checks PASSED"
