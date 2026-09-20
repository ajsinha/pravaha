#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Print the reactor's version.
#
# One reader, used by the image build, the release script and CI, because three readers of the
# same number disagree the first time the pom is reformatted.
#
# The root pom has no <parent>, so its FIRST <version> element is the project's own. That is the
# whole trick, and it is why this is a script with a comment rather than a grep inlined three
# times. `mvn help:evaluate` would be more correct and needs a plugin this repository does not
# resolve offline.
set -euo pipefail

root="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
pom="$root/pom.xml"

if [[ ! -f "$pom" ]]; then
  echo "version.sh: no pom.xml at $pom" >&2
  exit 1
fi

if grep -q '<parent>' "$pom"; then
  echo "version.sh: $pom has a <parent>; the first <version> is no longer the project's own." >&2
  exit 1
fi

version="$(awk '/<version>/ { sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit }' "$pom")"

if [[ -z "$version" ]]; then
  echo "version.sh: no <version> found in $pom" >&2
  exit 1
fi

printf '%s\n' "$version"
