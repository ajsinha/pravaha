#!/usr/bin/env bash
#
# Project Pravaha -- Ask once. Answer always.
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>.
# All rights reserved.
#
# PROPRIETARY AND CONFIDENTIAL. See the LICENSE file at the repository root.
#
# ---------------------------------------------------------------------------
# A verify that cannot pass against a stale jar.
#
# `./mvnw -pl <module> test` resolves that module's dependencies from ~/.m2,
# not from the working tree. So a change in pravaha-runtime is invisible to a
# test in pravaha-it unless something reinstalled it, and the run reports
# failures that are not real -- or worse, passes that are not real.
#
# That has cost this project four separate debugging sessions, twice in one
# day: an agent reported "pravaha-it is not green, 3 failures" against a tree
# that was green, and a findings-register check failed the same way an hour
# later. The reflex it produces is to go looking for a defect in the code that
# was just changed, which is the most expensive possible wrong turn.
#
# Deleting Pravaha's own artefacts first makes the failure mode impossible:
# there is no stale jar to resolve, so the reactor is the only source. Only
# com/ash/messaging is removed -- third-party dependencies stay, because the
# build runs offline (-o) and re-downloading them is neither possible nor the
# problem.
#
# Usage:
#   tools/verify-clean.sh                # full verify
#   tools/verify-clean.sh -pl pravaha-it -am   # any maven arguments
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ARTIFACTS="${HOME}/.m2/repository/com/ash/messaging"

if [[ -d "$ARTIFACTS" ]]; then
    echo "removing previously installed Pravaha artefacts from $ARTIFACTS"
    rm -rf "$ARTIFACTS"
else
    echo "no previously installed Pravaha artefacts to remove"
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

echo "installing from the working tree"
./mvnw -q -o -T1C install -DskipTests

if [[ $# -eq 0 ]]; then
    echo "running the full verify"
    ./mvnw -o verify
else
    echo "running: ./mvnw -o $*"
    ./mvnw -o "$@"
fi
