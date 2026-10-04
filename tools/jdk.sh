#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
# Sourced, not run: puts a JDK 25 or later on JAVA_HOME and PATH for the build scripts, or stops.
#
# Pravaha 2.x builds, tests, runs and releases on Java 25 only (ADR-061). Every script that starts
# Maven or a JVM sources this first, so the answer to "which JDK?" is in one place:
#
#   * JAVA_HOME set: it is used, and refused if it is older than 25. A JAVA_HOME pointing at 21 is
#     a mistake to name, not one to quietly route around.
#   * JAVA_HOME unset: the first of /usr/lib/jvm/java-25-openjdk* (the Debian/Ubuntu package), then
#     the `java` on PATH if it is 25 or later. Otherwise the script stops, naming the requirement.
#
# Usage, from a script in tools/ or deploy/<dir>/:
#   source "$root/tools/jdk25.sh"

pravaha_java_feature() {
    # The feature version of the JDK at $1: from its release file (no JVM started), else from
    # `java -version`. Empty when neither says.
    local home="$1" feature=""
    if [[ -r "$home/release" ]]; then
        feature="$(sed -n 's/^JAVA_VERSION="\([0-9][0-9]*\).*/\1/p' "$home/release")"
    fi
    if [[ -z "$feature" && -x "$home/bin/java" ]]; then
        feature="$("$home/bin/java" -version 2>&1 | sed -n 's/.* version "\([0-9][0-9]*\).*/\1/p' | head -n 1)" || true
    fi
    printf '%s' "$feature"
}

pravaha_require_jdk25() {
    local feature candidate on_path
    if [[ -n "${JAVA_HOME:-}" ]]; then
        feature="$(pravaha_java_feature "$JAVA_HOME")"
        if ! [[ "$feature" =~ ^[0-9]+$ ]] || (( feature < 25 )); then
            echo "${0##*/}: JAVA_HOME=$JAVA_HOME is Java ${feature:-of unknown version}; Pravaha 2.x builds and runs on Java 25 or later only." >&2
            echo "  Point JAVA_HOME at a JDK 25, e.g. export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64" >&2
            return 1
        fi
    else
        for candidate in /usr/lib/jvm/java-25-openjdk*; do
            if [[ -x "$candidate/bin/javac" ]]; then
                JAVA_HOME="$candidate"
                break
            fi
        done
        if [[ -z "${JAVA_HOME:-}" ]] && on_path="$(command -v javac 2>/dev/null)"; then
            candidate="$(dirname "$(dirname "$(readlink -f "$on_path")")")"
            feature="$(pravaha_java_feature "$candidate")"
            if [[ "$feature" =~ ^[0-9]+$ ]] && (( feature >= 25 )); then
                JAVA_HOME="$candidate"
            fi
        fi
        if [[ -z "${JAVA_HOME:-}" ]]; then
            echo "${0##*/}: no JDK 25 found; Pravaha 2.x builds and runs on Java 25 or later only." >&2
            echo "  Looked for /usr/lib/jvm/java-25-openjdk* and a javac 25+ on PATH." >&2
            echo "  Install one (e.g. apt install openjdk-25-jdk) or set JAVA_HOME to it." >&2
            return 1
        fi
    fi
    export JAVA_HOME
    export PATH="$JAVA_HOME/bin:$PATH"
}

pravaha_require_jdk25 || exit 1
