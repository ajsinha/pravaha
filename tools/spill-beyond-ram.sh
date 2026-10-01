#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
# ADR-044's beyond-RAM measurement: the spill tier with state larger than the memory the
# measured process may use, page cache included.
#
# It runs SpillBeyondRamMeasurementIT, which starts each workload in a JVM of its own under
#   systemd-run --user --scope -p MemoryMax=<cap> -p MemorySwapMax=0
# and reports throughput, latency, major faults and device bytes capped and uncapped. The test
# skips itself, with the reason, where a scope cannot be given a memory limit.
#
# Usage:
#   tools/spill-beyond-ram.sh <spill-directory> [maven args]
#
# The directory MUST be on the disk being measured. /tmp is a tmpfs on many machines -- RAM --
# and a tier pointed there measures nothing about a disk. The runs write gigabytes there and
# delete them as they go; the last run's per-size logs and report.txt stay behind.
#
# Defaults match what the ADR's table was produced with; override any of them on the command line,
# e.g. -Dpravaha.spill.beyond.kinds=aggregate -Dpravaha.spill.beyond.capMiB=1024. The knobs are in
# the test's javadoc. Expect hours for the larger multiples: past the page cache the workloads run
# at a thousand or two operations a second, which is the finding.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
# JDK 25 on JAVA_HOME, or a stop naming the requirement (ADR-061).
source tools/jdk25.sh

DIR="${1:?usage: tools/spill-beyond-ram.sh <spill-directory> [maven args]}"
shift || true
mkdir -p "$DIR"

# Keep Maven's own temporary files off a tmpfs too, for the same reason.
export TMPDIR="${TMPDIR:-$DIR/tmp}"
mkdir -p "$TMPDIR"
export MAVEN_OPTS="${MAVEN_OPTS:--Djava.io.tmpdir=$TMPDIR}"

./mvnw -o -pl pravaha-runtime -am test \
    -Dtest=SpillBeyondRamMeasurementIT \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Djacoco.skip=true \
    -Dpravaha.spill.beyond.dir="$DIR" \
    -Dpravaha.spill.beyond.multiples="${PRAVAHA_SPILL_MULTIPLES:-1,2,4,16}" \
    -Dpravaha.spill.beyond.timeoutMinutes="${PRAVAHA_SPILL_TIMEOUT_MINUTES:-25}" \
    "$@"

echo
echo "report: $DIR/report.txt"
