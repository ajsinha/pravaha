#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Set -- or check -- the version across everything in this repository that carries one.
#
#   deploy/release/set-version.sh --check           # do the 40 files agree? (CI, and before a release)
#   deploy/release/set-version.sh 0.2.0             # a release
#   deploy/release/set-version.sh 0.2.1-SNAPSHOT    # back to a snapshot afterwards
#   deploy/release/set-version.sh --check 0.2.0     # would they, if set to this?
#
# WHY A SCRIPT AND NOT `mvn versions:set`. Every one of the reactor's poms carries the version as
# a literal -- the root as <project><version>, each child as <parent><version> -- so there is no
# ${revision} property to change, and 37 files have to move together or the reactor will not
# resolve. versions-maven-plugin does exactly this and is NOT in the local repository, so it
# cannot run in the offline build this project is gated on. Rather than make a release depend on
# network access, this does the edit and checks its own work.
#
# WHAT IT TOUCHES
#   */pom.xml                          the reactor, root and every module
#   sdk/python/pyproject.toml          the Python SDK wheel
#   console/pyproject.toml             the console wheel
#   deploy/helm/pravaha/Chart.yaml     appVersion (the chart's OWN version is separate; see below)
#   README.md, docs/USER_GUIDE.md and five console help pages: the versions they show
#
# PYTHON AND -SNAPSHOT. PEP 440 has no snapshot, so a Python version is the Maven one with
# -SNAPSHOT removed: 0.2.0-SNAPSHOT and 0.2.0 both give 0.2.0. That is why a snapshot wheel is
# never published -- it would claim to be the release. It is also why the repository is
# consistent today at 0.1.0-SNAPSHOT / 0.1.0 rather than wrong.
#
# THE CHART'S OWN VERSION is not the engine's. appVersion follows the engine; `version` is the
# chart's, bumped when the chart changes, and this script leaves it alone unless --chart-version
# says otherwise.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

check_only=0
chart_version=""
target=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --check) check_only=1; shift ;;
    --chart-version) chart_version="$2"; shift 2 ;;
    -h|--help) sed -n '5,32p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) echo "set-version.sh: unknown option '$1'" >&2; exit 2 ;;
    # An EMPTY argument is refused rather than falling through to "no argument given, keep the
    # current version": `set-version.sh "$VERSION"` with VERSION unset would otherwise report
    # success having done nothing, which is the shape of release bug that is found in production.
    *) [[ -n "$1" ]] || { echo "set-version.sh: the version argument is empty." >&2; exit 2; }
       target="$1"; shift ;;
  esac
done

current="$("$here/version.sh" "$root")"
target="${target:-$current}"

if ! [[ "$target" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
  echo "set-version.sh: '$target' is not MAJOR.MINOR.PATCH[-qualifier]." >&2
  exit 2
fi

python_version="${target%-SNAPSHOT}"

# Every file that carries a version, and the expression that finds it in that file. Listed here
# rather than discovered, so a NEW file with a version in it is a deliberate edit to this list
# and not something the next release silently leaves behind.
mapfile -t poms < <(cd "$root" && find . -name pom.xml -not -path "./.git/*" -not -path "*/target/*" -not -path "./.claude/*" | sort)

problems=0
changed=0

note()    { echo "      $*"; }
problem() { echo "MISMATCH: $*" >&2; problems=$((problems + 1)); }

# ---------------------------------------------------------------- the reactor

for pom in "${poms[@]}"; do
  file="$root/${pom#./}"
  # The version element that belongs to THIS project: the root pom's own <version>, or a child's
  # <parent><version>. Both are the first <version> in the file, because a <parent> block comes
  # before anything else and the root pom has none -- the same fact version.sh relies on.
  found="$(awk '/<version>/ { sub(/.*<version>/, ""); sub(/<\/version>.*/, ""); print; exit }' "$file")"
  if [[ -z "$found" ]]; then
    problem "$pom has no <version> at all"
    continue
  fi
  if [[ "$check_only" == 1 ]]; then
    [[ "$found" == "$target" ]] || problem "$pom is $found, not $target"
    continue
  fi
  if [[ "$found" == "$target" ]]; then
    continue
  fi
  # Replace only the FIRST occurrence, which is the one identified above. A dependency on another
  # module written as a literal rather than ${project.version} would be a second occurrence, and
  # rewriting it here would hide that mistake instead of leaving it to be found.
  perl -0pi -e "s|<version>\Q$found\E</version>|<version>$target</version>|" -- "$file" 2>/dev/null \
    || { echo "set-version.sh: perl is required to edit $pom" >&2; exit 1; }
  # perl -0p replaces every occurrence, so put back any that were not the first and did not match
  # the old project version by coincidence -- there are none today, and this asserts it.
  after="$(grep -c "<version>$target</version>" "$file")"
  if [[ "$after" != "1" ]]; then
    problem "$pom now has $after occurrences of <version>$target</version>. It carried the project version more than once, which this script is not safe for -- revert it and use \${project.version}."
  fi
  changed=$((changed + 1))
  note "$pom  $found -> $target"
done

# ---------------------------------------------------------------- the wheels

for toml in sdk/python/pyproject.toml console/pyproject.toml; do
  file="$root/$toml"
  [[ -f "$file" ]] || { problem "$toml is missing"; continue; }
  found="$(awk -F'"' '/^version = "/ { print $2; exit }' "$file")"
  if [[ "$check_only" == 1 ]]; then
    [[ "$found" == "$python_version" ]] || problem "$toml is $found, not $python_version"
    continue
  fi
  [[ "$found" == "$python_version" ]] && continue
  perl -0pi -e "s|^version = \"\Q$found\E\"|version = \"$python_version\"|m" -- "$file"
  changed=$((changed + 1))
  note "$toml  $found -> $python_version"
done

# ---------------------------------------------------------------- the console's displayed version
#
# console/config/application.yaml's app.version is what the console shows on its landing page and
# its About page. It was not in this list, so a release moved the wheel and left the page saying the
# previous version: 0.1.0 on a 0.1.1 build.

console_cfg="$root/console/config/application.yaml"
if [[ -f "$console_cfg" ]]; then
  found="$(awk -F'"' '/^  version: "/ { print $2; exit }' "$console_cfg")"
  if [[ "$check_only" == 1 ]]; then
    [[ "$found" == "$python_version" ]] || problem "console/config/application.yaml app.version is $found, not $python_version"
  elif [[ "$found" != "$python_version" ]]; then
    perl -0pi -e "s|^  version: \"\Q$found\E\"|  version: \"$python_version\"|m" -- "$console_cfg"
    changed=$((changed + 1))
    note "console/config/application.yaml app.version  $found -> $python_version"
  fi
else
  problem "console/config/application.yaml is missing"
fi

# ---------------------------------------------------------------- the pages that show a version
#
# A <dependency> block, an artifact version or an example response copied from the docs has to
# resolve against the build it came with (console/tests/test_help_accuracy.py checks it). Only
# these shapes, in these files, are rewritten: history -- the release notes, the findings -- is left
# as it was written.

doc_pages=(README.md docs/USER_GUIDE.md console/content/topics/clients.md
           console/content/topics/spring-boot-starter.md console/content/topics/embedded-engine.md
           console/content/topics/http-api.md console/content/topics/cli-reference.md)
if [[ "$check_only" == 0 && "$current" != "$target" ]]; then
  current_python="${current%-SNAPSHOT}"
  for page in "${doc_pages[@]}"; do
    file="$root/$page"
    [[ -f "$file" ]] || continue
    before="$(cksum < "$file")"
    perl -0pi -e "s|<version>\Q$current\E</version>|<version>$target</version>|g;
                  s|version \x60\Q$current\E\x60 in this repository|version \x60$target\x60 in this repository|g;
                  s|\"version\": \"\Q$current\E\"|\"version\": \"$target\"|g;
                  s|pravaha-server:\Q$current\E|pravaha-server:$target|g;
                  s|^(version +)\Q$current_python\E(?![0-9A-Za-z.-])|\${1}$python_version|mg" -- "$file"
    if [[ "$(cksum < "$file")" != "$before" ]]; then
      changed=$((changed + 1))
      note "$page  $current -> $target"
    fi
  done
fi

# ---------------------------------------------------------------- the chart

chart="$root/deploy/helm/pravaha/Chart.yaml"
if [[ -f "$chart" ]]; then
  found="$(awk -F'"' '/^appVersion:/ { print $2; exit }' "$chart")"
  if [[ "$check_only" == 1 ]]; then
    [[ "$found" == "$target" ]] || problem "Chart.yaml appVersion is $found, not $target"
  elif [[ "$found" != "$target" ]]; then
    perl -0pi -e "s|^appVersion: \"\Q$found\E\"|appVersion: \"$target\"|m" -- "$chart"
    changed=$((changed + 1))
    note "deploy/helm/pravaha/Chart.yaml appVersion  $found -> $target"
  fi
  if [[ -n "$chart_version" && "$check_only" == 0 ]]; then
    old="$(awk '/^version:/ { print $2; exit }' "$chart")"
    perl -0pi -e "s|^version: \Q$old\E$|version: $chart_version|m" -- "$chart"
    changed=$((changed + 1))
    note "deploy/helm/pravaha/Chart.yaml version  $old -> $chart_version"
  fi
else
  problem "deploy/helm/pravaha/Chart.yaml is missing"
fi

# ---------------------------------------------------------------- the verdict

echo
if [[ "$check_only" == 1 ]]; then
  if [[ "$problems" -gt 0 ]]; then
    cat >&2 <<EOF
set-version.sh: $problems file(s) disagree about the version.

  The reactor will not resolve across a mismatch, and a release built from this tree would ship
  artefacts naming two different versions. Fix it with:

      deploy/release/set-version.sh $target
EOF
    exit 1
  fi
  echo "set-version.sh: ${#poms[@]} poms, 2 wheels and the chart all say $target (Python: $python_version). OK."
  exit 0
fi

echo "set-version.sh: $changed file(s) set to $target (Python: $python_version)."
echo "                Re-checking..."
exec "${BASH_SOURCE[0]}" --check "$target"
