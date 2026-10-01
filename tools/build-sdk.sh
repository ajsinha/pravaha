#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Builds the client SDKs, and only the client SDKs, into one directory a client can be handed.
#
#   tools/build-sdk.sh                 # jars, wheel and sdist into target/sdk-dist/
#   tools/build-sdk.sh --install       # also install the Java SDK into the local Maven repository
#   tools/build-sdk.sh --java-only     # no Python toolchain needed
#   tools/build-sdk.sh --python-only
#   tools/build-sdk.sh -- -o           # anything after -- goes to Maven (here: offline)
#
# WHY THIS EXISTS. The SDKs are used by clients, so they are built and shipped apart from the
# server: an application takes pravaha-sdk-java-flight or the pravaha wheel and nothing of the
# engine comes with it. The reactor build produces the same jars, but only as a by-product of
# building everything; this builds pravaha-api and the two Java SDK modules (plus the parent POM
# they inherit from) and the Python package, and nothing else.
#
# -Dsdk.standalone is what makes that true. The Flight SDK's own tests start a real server
# in-process and so name server modules at test scope; Maven's -am follows test scope into the
# reactor, and without the property it builds twelve engine modules to package a client. With it
# those test dependencies drop out (and the SDK's tests with them -- the gate runs them), and the
# script refuses to continue if the reactor holds anything but the four projects it expects.
#
# The Python build needs `python -m build` (pip install build) or uv on the PATH; PYTHON picks
# the interpreter. See sdk/python/README.md and sdk/pravaha-sdk-java/README.md for what a client
# does with the result; SdkIndependenceTest (pravaha-it) keeps the SDKs independent of the server.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
# JDK 25 on JAVA_HOME, or a stop naming the requirement (ADR-061).
source "$root/tools/jdk25.sh"
out="$root/target/sdk-dist"
goal="package"
java=1
python=1
maven_extra=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        --install) goal="install" ;;
        --java-only) python=0 ;;
        --python-only) java=0 ;;
        --out) out="$2"; shift ;;
        --) shift; maven_extra=("$@"); break ;;
        -h|--help) sed -n '5,13p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-sdk: unknown option $1 (see --help)" >&2; exit 2 ;;
    esac
    shift
done

rm -rf "$out"
mkdir -p "$out"

version="$(sed -n 's:^  <version>\(.*\)</version>$:\1:p' "$root/pom.xml" | head -1)"
python_version="$(sed -n 's/^version = "\(.*\)"$/\1/p' "$root/sdk/python/pyproject.toml" | head -1)"

if [[ $java -eq 1 ]]; then
    log="$out/.maven.log"
    mkdir -p "$out/java"
    echo "build-sdk: Java SDK $version (mvn $goal, -Dsdk.standalone)"
    set +e
    "$root/tools/worktree-build.sh" -B -Dsdk.standalone -DskipTests \
        -pl sdk/pravaha-sdk-java,sdk/pravaha-sdk-java-flight -am \
        ${maven_extra[@]+"${maven_extra[@]}"} "$goal" > "$log" 2>&1
    status=$?
    set -e
    if [[ $status -ne 0 ]]; then
        tail -40 "$log" >&2
        echo "build-sdk: Maven failed; the full log is $log" >&2
        exit "$status"
    fi
    # The whole point: nothing server-side was built. "Building <name> <version> [i/n]" names every
    # project in the reactor; the four below are the only ones a client needs.
    built="$(sed -n "s/^\[INFO\] Building \(.*\) ${version} *\[[0-9]*\/[0-9]*\]\$/\1/p" "$log" | sort)"
    expected="$(printf '%s\n' 'Pravaha' 'Pravaha :: api' 'Pravaha :: sdk :: java' 'Pravaha :: sdk :: java :: flight' | sort)"
    if [[ "$built" != "$expected" ]]; then
        echo "build-sdk: the reactor built more than the SDK needs:" >&2
        echo "$built" | sed 's/^/  /' >&2
        exit 1
    fi
    for jar in \
        "$root/pravaha-api/target/pravaha-api-$version.jar" \
        "$root/sdk/pravaha-sdk-java/target/pravaha-sdk-java-$version.jar" \
        "$root/sdk/pravaha-sdk-java-flight/target/pravaha-sdk-java-flight-$version.jar" \
        "$root/sdk/pravaha-sdk-java-flight/target/pravaha-sdk-java-flight-$version-all.jar"; do
        [[ -f "$jar" ]] || { echo "build-sdk: expected $jar was not built" >&2; exit 1; }
        cp "$jar" "$out/java/"
    done
    # Sources and javadoc for the three thin jars, so an IDE shows them.
    for base in \
        "$root/pravaha-api/target/pravaha-api-$version" \
        "$root/sdk/pravaha-sdk-java/target/pravaha-sdk-java-$version" \
        "$root/sdk/pravaha-sdk-java-flight/target/pravaha-sdk-java-flight-$version"; do
        for classifier in sources javadoc; do
            [[ -f "$base-$classifier.jar" ]] || { echo "build-sdk: expected $base-$classifier.jar was not built" >&2; exit 1; }
            cp "$base-$classifier.jar" "$out/java/"
        done
    done
    # The POMs, so the thin jars can be installed or deployed elsewhere with their dependencies.
    cp "$root/pom.xml" "$out/java/pravaha-$version.pom"
    cp "$root/pravaha-api/pom.xml" "$out/java/pravaha-api-$version.pom"
    cp "$root/sdk/pravaha-sdk-java/pom.xml" "$out/java/pravaha-sdk-java-$version.pom"
    cp "$root/sdk/pravaha-sdk-java-flight/pom.xml" "$out/java/pravaha-sdk-java-flight-$version.pom"
    rm -f "$log"
fi

if [[ $python -eq 1 ]]; then
    echo "build-sdk: Python SDK $python_version (wheel and sdist)"
    mkdir -p "$out/python"
    py="${PYTHON:-python3}"
    if "$py" -c 'import build' >/dev/null 2>&1; then
        (cd "$root/sdk/python" && "$py" -m build --outdir "$out/python" >/dev/null)
    elif command -v uv >/dev/null 2>&1; then
        (cd "$root/sdk/python" && uv build --out-dir "$out/python" >/dev/null 2>&1)
    else
        echo "build-sdk: no Python build frontend: pip install build (for $py), or install uv" >&2
        exit 1
    fi
    rm -f "$out/python/.gitignore"
fi

cat > "$out/README.txt" <<EOF
Pravaha client SDKs, built by tools/build-sdk.sh from $(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo "an unknown commit").
Nothing in this directory contains the server; none of it needs one to be installed.

java/  (Maven coordinates com.ash.messaging:<artifact>:$version; every jar needs Java 25,
       the Flight client, pravaha-sdk-java and pravaha-api alike)
  pravaha-sdk-java-flight-$version.jar      The Java client: connect, query, register, subscribe.
                                            Use this with Maven or Gradle; it brings Arrow Flight
                                            SQL, gRPC and Netty through its POM.
  pravaha-sdk-java-$version.jar             The client's types and connection strings; no
                                            dependencies beyond pravaha-api.
  pravaha-api-$version.jar                  The public API types, dependency-free.
  pravaha-sdk-java-flight-$version-all.jar  Everything above plus every runtime dependency, in
                                            one jar, for a client with no build tool:
                                              java --add-opens=java.base/java.nio=ALL-UNNAMED \\
                                                   -cp pravaha-sdk-java-flight-$version-all.jar:. MyClient
                                            Nothing is relocated: do not put it beside another
                                            gRPC, Netty, Arrow or protobuf; use the thin jars.
  *-sources.jar, *-javadoc.jar              Sources and javadoc of the three thin jars.
  *.pom                                     The POMs, for mvn install:install-file -DpomFile=...

python/  (pip install; Python 3.9 or later)
  pravaha-$python_version-py3-none-any.whl  pip install 'pravaha-$python_version-py3-none-any.whl[flight]'
                                            [flight] adds pyarrow, the transport; without it the
                                            package imports and its HTTP calls work, and a Flight
                                            call names the missing extra. [tls-keystore] adds
                                            cryptography, for JKS/PKCS12 keystores.
  pravaha-$python_version.tar.gz                The source distribution of the same.

Compatibility: use the SDK from the same release as the server (the same major.minor). The
Flight protocol is not version-negotiated; an SDK feature an older server does not have is
refused by that server with an error, never silently degraded. See sdk/pravaha-sdk-java/README.md
and sdk/python/README.md.
EOF

echo "build-sdk: $out"
(cd "$out" && find . -type f | sort | sed 's|^\./|  |')
