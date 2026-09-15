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
#
# Runs of this script serialise against each other on a lock in ~/.m2. They do
# not serialise against a plain `./mvnw` you start yourself -- if one of those
# is running, let it finish first, or its jars will be deleted underneath it.
# `./mvnw -o -pl <module> -am ...` is the safe alternative when something else
# is already building: -am gets the same working-tree guarantee by rebuilding
# dependencies in the reactor, without touching ~/.m2 at all.
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ARTIFACTS="${HOME}/.m2/repository/com/ash/messaging"
LOCK="${HOME}/.m2/.pravaha-verify-clean.lock"

# ---------------------------------------------------------------------------
# Serialised, because the cure had the same shape as the disease.
#
# This script deletes shared state -- Pravaha's artefacts in ~/.m2 -- and two
# copies of it running at once is one build deleting the jars another is in
# the middle of resolving. Three agents and a drill can be building here
# concurrently, and that is the normal case rather than the unlucky one.
#
# flock makes the delete-then-install sequence atomic with respect to other
# runs of this script. It cannot protect against a bare `./mvnw` started
# elsewhere: nothing takes this lock but this file. If you are running a long
# build outside it, wait for that to finish before running this.
# ---------------------------------------------------------------------------
mkdir -p "$(dirname "$LOCK")"
exec 9>"$LOCK"
if ! flock -n 9; then
    echo "another verify-clean is holding $LOCK; waiting for it rather than deleting its jars"
    flock 9
fi

# Every maven invocation below runs with fd 9 closed (9>&-), and the lock is
# dropped once the install is done.
#
# Found by this script deadlocking against itself. A redirection is inherited
# by children, so the first maven it started held the lock too -- and killing
# the script left maven alive, holding a lock on behalf of a process that no
# longer existed. The next run waited on it for ever, with nothing in the
# process list to say why. A lock whose holder cannot be identified is worse
# than no lock, and it is the same shape as everything else found here this
# week: shared state with an owner nobody wrote down.

if [[ -d "$ARTIFACTS" ]]; then
    echo "removing previously installed Pravaha artefacts from $ARTIFACTS"
    rm -rf "$ARTIFACTS"
else
    echo "no previously installed Pravaha artefacts to remove"
fi

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"

echo "installing from the working tree"
./mvnw -q -o -T1C install -DskipTests 9>&-

# Released here rather than at exit. The delete and the install are what needed
# protecting; the tests that follow take minutes and read nothing another run
# would remove, so holding it through them would serialise the slow part for no
# reason.
exec 9>&-

# Module-level parallelism, which the install above already used and this did not.
# The reactor is ~30 modules and this machine has 24 cores; running them one at a
# time was most of the wall clock outside pravaha-it.
#
# PRAVAHA_FORKS controls surefire's fork count. Empty means surefire's default of
# one JVM, which is the conservative setting and what a release build should use.
# Set it (e.g. PRAVAHA_FORKS=0.5C) to run test classes across several JVMs --
# measured on this machine before being offered, and kept opt-in because a shared
# port or a shared directory between two test classes fails only under it, and
# fails confusingly.
# Half a fork per core. Measured on this machine, both runs complete and green:
# 6:36 single-JVM, 5:50 with this and -T1C, for the same 2,274 tests.
#
# That is ~11%, and it is worth writing down that the first figure taken here was
# 3:30 -- from a run that *failed* at pravaha-server and so never reached
# pravaha-it, which is 3:24 of the build on its own. Comparing a complete run with
# an aborted one is how a speedup gets overstated by a factor of four. The honest
# ceiling is low because pravaha-it dominates and much of it is deliberately slow:
# SourceScaleTest 40s, AerospikeSourceScaleIT 33s, StandbyWatchTest 20s. Those
# measure things, and making them quick would mean measuring less.
#
# Turning it on found a real defect rather than needing a workaround: two
# @SpringBootTest classes both bound the *fixed* Flight port 9090, which collides
# whenever they do not run sequentially -- and would collide equally with a node
# the developer happens to be running. Both now use port 0. If a future test fails
# only under forks, that is the same smell: look for shared fixed state before
# reaching for PRAVAHA_FORKS=1.
FORKS="-DforkCount=${PRAVAHA_FORKS:-0.5C} -DreuseForks=true"
echo "surefire forks: ${PRAVAHA_FORKS:-0.5C}"

if [[ $# -eq 0 ]]; then
    echo "running the full verify"
    ./mvnw -o -T1C $FORKS verify
else
    echo "running: ./mvnw -o -T1C $FORKS $*"
    ./mvnw -o -T1C $FORKS "$@"
fi
