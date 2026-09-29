#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
# ./mvnw, with a Maven repository of the worktree's own when run in a linked git worktree
# (MAVENRACE-1).
#
# Parallel checkouts sharing ~/.m2 install each other's SNAPSHOT jars, so a module-scoped build in one
# can compile against another's half-finished change and fail -- or pass -- for reasons that are not in
# its own tree. In a linked worktree this passes -Dmaven.repo.local=<worktree>/.m2-local (gitignored).
# The first run seeds it from ~/.m2/repository with hard links -- no copy, no download, offline builds
# keep working -- leaving out Pravaha's own artefacts, which this worktree then builds and installs
# for itself. In the main checkout it is plain ./mvnw.
#
# Usage:
#   tools/worktree-build.sh [maven args]       e.g. tools/worktree-build.sh -o -pl pravaha-runtime -am test
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
cd "$root"

args=()
git_dir="$(git rev-parse --absolute-git-dir 2>/dev/null || true)"
common_dir="$(git rev-parse --git-common-dir 2>/dev/null || true)"
if [[ -n "$git_dir" && -n "$common_dir" && "$git_dir" != "$(cd "$common_dir" && pwd -P)" ]]; then
    repo="$root/.m2-local"
    shared="${HOME}/.m2/repository"
    if [[ ! -d "$repo" && -d "$shared" ]]; then
        # Hard links fail across filesystems; then Maven downloads what it needs instead.
        if cp -al "$shared" "$repo" 2>/dev/null; then
            rm -rf "$repo/com/ash/messaging"
        else
            rm -rf "$repo"
        fi
    fi
    echo "worktree-build: linked worktree, Maven repository $repo" >&2
    args+=("-Dmaven.repo.local=$repo")
fi

exec ./mvnw ${args[@]+"${args[@]}"} "$@"
