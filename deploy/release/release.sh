#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Cut a release, as far as this repository can actually go.
#
#   deploy/release/release.sh --version 0.2.0 --next 0.2.1-SNAPSHOT
#   deploy/release/release.sh --version 0.2.0 --next 0.2.1-SNAPSHOT --dry-run
#
# WHAT THIS DOES, in order, stopping at the first failure:
#
#   1. refuses unless the working tree is clean and on a branch that may be released from
#   2. sets the version across 37 poms, 2 wheels and the chart (deploy/release/set-version.sh)
#   3. `./mvnw -o clean verify` -- the whole reactor, offline, tests and all
#   4. builds the container images (engine and console) and tags them with the release version:
#      the engine on Java 25 as <version>, and on Java 21 as <version>-jre21 (the same jar)
#   5. runs the image's smoke journey against both engine images it just built
#   6. packages the Helm chart, if helm is available
#   7. commits the version bump and writes an ANNOTATED TAG
#   8. sets the version to --next and commits that
#
# WHAT IT DELIBERATELY DOES NOT DO, because the repository cannot:
#
#   * `mvn deploy`. There is no <distributionManagement> in the root pom and no repository to
#     deploy to. Adding one here would be inventing a release process rather than running one.
#   * push the tag, or the commits. `git push` is the owner's, deliberately: this script makes a
#     release reproducible, it does not make it public.
#   * push the image. There is no registry configured. `deploy/docker/build.sh --push --tag
#     <registry>/<repo>:<version>` is the one command, once there is one.
#   * publish the wheels. `python -m build` in sdk/python and console produces them; there is no
#     index configured and no credential in this repository.
#   * sign anything. No GPG key, no keyless signing, no provenance attestation.
#
# Every one of those is a decision somebody has to make once, and docs/operations/DEPLOYMENT.md's "Release"
# section records what each would need. A script that quietly skipped them would let a release
# report success having shipped nothing.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"

version=""
next=""
dry=0
allow_branch="${RELEASE_BRANCHES:-main develop}"
image_repo="${RELEASE_IMAGE_REPO:-pravaha/pravaha-server}"
docker_bin="${DOCKER:-docker}"
helm="${HELM:-helm}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version) version="$2"; shift 2 ;;
    --next)    next="$2"; shift 2 ;;
    --dry-run) dry=1; shift ;;
    -h|--help) sed -n '5,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "release.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

step()  { echo; echo "=== $* ==="; }
run()   { if [[ "$dry" == 1 ]]; then echo "  would run: $*"; else "$@"; fi; }
die()   { echo "release.sh: $*" >&2; exit 1; }

[[ -n "$version" ]] || die "--version is required."
[[ -n "$next" ]]    || die "--next is required. A release that leaves the tree on the released
version means the next commit to develop claims to be the release."
[[ "$next" == *-SNAPSHOT ]] || die "--next '$next' is not a -SNAPSHOT. The tree after a release
has to be a snapshot, or every build between now and the next release claims to be a release."
[[ "$version" != *-SNAPSHOT ]] || die "--version '$version' is a snapshot. Release a release."

# ---------------------------------------------------------------- 1. the tree

step "preconditions"

# --porcelain, so UNTRACKED files count too. A release built beside an untracked source file is
# a release whose tag does not describe it, and `git diff` alone would not have noticed.
dirty="$(git -C "$root" status --porcelain)"
[[ -z "$dirty" ]] || die "the working tree is not clean. A release built from uncommitted or
untracked changes cannot be reproduced from its own tag, which is the only thing a tag is for.

$dirty"

branch="$(git -C "$root" rev-parse --abbrev-ref HEAD)"
found=0
for b in $allow_branch; do [[ "$branch" == "$b" ]] && found=1; done
[[ "$found" == 1 ]] || die "on branch '$branch'. Releases are cut from: $allow_branch
Override with RELEASE_BRANCHES='$branch'."

tag="v$version"
if git -C "$root" rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  die "tag $tag already exists. A released version is never rebuilt under the same name."
fi
echo "  branch $branch, tag $tag is free, tree is clean"

command -v "$docker_bin" >/dev/null || die "no docker. The release builds and smoke-tests the image."

# ---------------------------------------------------------------- 2. the version

step "set the version to $version"
run "$here/set-version.sh" "$version"

# ---------------------------------------------------------------- 3. the build

step "build and test the whole reactor, offline"
# -o because the offline build is the gate this project is held to; a release that needed the
# network would be a release nobody could reproduce in a year.
run "$root/mvnw" -o -B clean verify -f "$root/pom.xml"

# ---------------------------------------------------------------- 4 and 5. the image

# Java 25 is the image's JRE (2026-10-01, the owner's decision); 21 is built and smoke-tested beside it
# under -jre21, because it is supported and a tag nobody ran is not a release of it.
step "build the image (Java 25)"
run "$root/deploy/docker/build.sh" --java 25 --tag "$image_repo:$version"

step "smoke-test the image (Java 25)"
run "$root/deploy/docker/smoke.sh" --image "$image_repo:$version"

step "build the image (Java 21)"
run "$root/deploy/docker/build.sh" --java 21 --tag "$image_repo:$version-jre21"

step "smoke-test the image (Java 21)"
run "$root/deploy/docker/smoke.sh" --image "$image_repo:$version-jre21"

# The console is its own image (2026-09-26, the owner's decision): same version, same release.
step "build the console image"
run "$root/deploy/docker/console/build.sh" --tag "${image_repo%-server}-console:$version"

# ---------------------------------------------------------------- 6. the chart

step "package the chart"
if command -v "$helm" >/dev/null 2>&1; then
  run "$root/deploy/helm/test.sh"
  run "$helm" package "$root/deploy/helm/pravaha" --destination "$root/target"
else
  echo "  NO HELM. The chart is NOT packaged and NOT linted by this release."
  echo "  This is recorded rather than skipped: install helm and re-run, or the release ships"
  echo "  an unvalidated chart. docs/operations/DEPLOYMENT.md says how CI installs it."
fi

# ---------------------------------------------------------------- 7. commit and tag

step "commit and tag"
run git -C "$root" add -A -- '*pom.xml' sdk/python/pyproject.toml console/pyproject.toml console/config/application.yaml \
    deploy/helm/pravaha/Chart.yaml README.md docs/guides/USER_GUIDE.md console/content/topics docs/operations/DEPLOYMENT.md
run git -C "$root" commit -m "Release $version"
run git -C "$root" tag -a "$tag" -m "Pravaha $version"

# ---------------------------------------------------------------- 8. back to a snapshot

step "back to $next"
run "$here/set-version.sh" "$next"
run git -C "$root" add -A -- '*pom.xml' sdk/python/pyproject.toml console/pyproject.toml console/config/application.yaml \
    deploy/helm/pravaha/Chart.yaml README.md docs/guides/USER_GUIDE.md console/content/topics docs/operations/DEPLOYMENT.md
run git -C "$root" commit -m "Back to $next"

# ---------------------------------------------------------------- what is left for a person

cat <<EOF

=== released locally: $version ===

  tag     $tag          (annotated, NOT pushed)
  image   $image_repo:$version   (Java 25; built and smoke-tested, NOT pushed)
  image   $image_repo:$version-jre21   (Java 21; built and smoke-tested, NOT pushed)
  image   ${image_repo%-server}-console:$version   (built, NOT pushed)
  chart   target/pravaha-*.tgz   (if helm was available)
  jars    pravaha-*/target/*.jar

What a person still has to do, and why this script will not:

  git push && git push origin $tag       the owner decides when a release is public
  deploy/docker/build.sh --push --tag <registry>/pravaha-server:$version
  deploy/docker/build.sh --java 21 --push --tag <registry>/pravaha-server:$version-jre21
                                          there is no registry configured in this repository
  helm push target/pravaha-*.tgz oci://<registry>/charts
                                          likewise
  (cd sdk/python && python -m build)     no index and no credential here
  (cd console && python -m build)        likewise

There is no \`mvn deploy\`: the root pom has no <distributionManagement>. Adding one is a
decision with consequences beyond this script -- see docs/operations/DEPLOYMENT.md, "Release".
EOF
