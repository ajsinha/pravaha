#!/usr/bin/env bash
# Fast feedback while iterating on a fix. NOT a release gate -- tools/verify-clean.sh is.
#
# The difference that matters: verify-clean.sh deletes the installed Pravaha
# artefacts first, so no stale jar can be resolved. This does not. It is for the
# loop between "write the fix" and "see if it works", where the alternative is a
# six-minute wait per edit and the real risk is losing the thread, not a stale jar.
#
# Usage:
#   tools/check.sh <module> [-Dtest=SomeTest]     one module and what it depends on
#   tools/check.sh --all [-Dtest=SomeTest]        every module
#
# Always finish a batch with tools/verify-clean.sh before committing.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

FORKS="${PRAVAHA_FORKS:-0.5C}"
TARGET="${1:?usage: tools/check.sh <module>|--all [maven args]}"
shift || true

# -o offline: the dependency tree does not change between edits, and resolution
#    against a remote was measurable.
# -T1C module parallelism; -pl/-am builds only what the target needs.
COMMON=(-o -T1C -DforkCount="$FORKS" -DreuseForks=true -Dspotless.check.skip=true)

if [[ "$TARGET" == "--all" ]]; then
    ./mvnw -q "${COMMON[@]}" install "$@"
else
    ./mvnw -q "${COMMON[@]}" -pl "$TARGET" -am install "$@"
fi

echo
echo "PASSED -- but this is not the gate. Run tools/verify-clean.sh before committing:"
echo "  it deletes the installed artefacts first, and spotless:check is skipped here."
