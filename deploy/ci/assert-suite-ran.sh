#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Did the suite that just "passed" actually run anything?
#
#   deploy/ci/assert-suite-ran.sh failsafe <minimum>   # integration tests (Testcontainers)
#   deploy/ci/assert-suite-ran.sh surefire <minimum>   # unit tests
#
# THE FAILURE THIS EXISTS FOR. `./mvnw verify -Pit` exits 0 when it runs zero integration tests
# -- because the profile did not activate, because Docker was not reachable and the tests
# assumed themselves away, because a module was excluded. The job goes green. Nobody looks at a
# green job. This repository has had exactly that: the integration leg and the Spring Boot 3.2
# through 3.4 legs are listed in docs/REMAINING.md as never having run.
#
# So the job asserts a floor. A number, not "more than zero", because one test running out of
# four hundred is the same lie in a smaller font.
#
# Run it from the repository root, after the build.
set -euo pipefail

kind="${1:-}"
minimum="${2:-1}"

case "$kind" in
  failsafe|surefire) ;;
  *) echo "assert-suite-ran.sh: first argument must be 'failsafe' or 'surefire'" >&2; exit 2 ;;
esac

if ! [[ "$minimum" =~ ^[0-9]+$ ]]; then
  echo "assert-suite-ran.sh: '$minimum' is not a number" >&2
  exit 2
fi

root="${PRAVAHA_ROOT:-$PWD}"

mapfile -t reports < <(find "$root" -path "*/target/${kind}-reports/*.txt" -not -path "*/.git/*" 2>/dev/null | sort)

if [[ "${#reports[@]}" -eq 0 ]]; then
  cat >&2 <<EOF
assert-suite-ran.sh: no ${kind}-reports anywhere under $root.

  The build reported success and ran no ${kind} tests at all. For 'failsafe' the usual causes,
  in the order they happen:

    * -Pit was not passed, so failsafe was never bound to verify
    * the Docker daemon was not reachable, so Testcontainers refused and the tests were skipped
    * the module holding them was not in the reactor

  A build that tests nothing must not be green.
EOF
  exit 1
fi

# "Tests run: 12, Failures: 0, Errors: 0, Skipped: 3"
total=0
skipped=0
for report in "${reports[@]}"; do
  line="$(grep -m1 -E '^Tests run:' "$report" || true)"
  [[ -n "$line" ]] || continue
  ran="$(sed -E 's/^Tests run: ([0-9]+).*/\1/' <<<"$line")"
  skip="$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<<"$line")"
  [[ "$ran"  =~ ^[0-9]+$ ]] && total=$((total + ran))
  [[ "$skip" =~ ^[0-9]+$ ]] && skipped=$((skipped + skip))
done

executed=$((total - skipped))

echo "assert-suite-ran.sh: ${#reports[@]} ${kind} report(s), $total test(s), $skipped skipped, $executed executed"

if [[ "$executed" -lt "$minimum" ]]; then
  cat >&2 <<EOF

assert-suite-ran.sh: $executed ${kind} test(s) actually executed, and the floor is $minimum.

  Either the suite shrank -- in which case lower the floor in the workflow, deliberately, in a
  commit that says why -- or it did not really run. Do not raise the floor to make this pass.
EOF
  exit 1
fi
