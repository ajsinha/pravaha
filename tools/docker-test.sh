#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Build and test Pravaha inside Docker, with nothing on the host but Docker: no JDK, no Maven, no
# Python. Each suite runs in a throwaway container AS YOU (--user $(id -u):$(id -g)), so target/,
# caches and everything else it writes are yours, never root's.
#
#   tools/docker-test.sh unit                         the reactor's tests; container-backed ones skip
#   tools/docker-test.sh unit -pl pravaha-sql -am     ... or any Maven arguments after the suite
#   tools/docker-test.sh it                           the connector modules' tests WITH Docker, so the
#                                                     Testcontainers ones (Kafka, PostgreSQL, MySQL,
#                                                     Aerospike, Cassandra) run instead of skipping
#   tools/docker-test.sh it --modules plugins/pravaha-plugin-kafka
#   tools/docker-test.sh sdk                          the Python SDK's pytest suite, real server included
#   tools/docker-test.sh console                      the console's pytest suite, browser tests off
#   tools/docker-test.sh all                          unit, it, sdk, console, in that order
#   tools/docker-test.sh mvn -pl pravaha-server -am package -DskipTests
#                                                     any Maven command, in the same container: how
#                                                     the server jar is built with no JDK on the host
#   tools/docker-test.sh mvn --docker -pl plugins/pravaha-plugin-kafka test -Dtest=KafkaSinkBrokerTest
#                                                     ... with the Docker socket, for one Testcontainers test
#
# One image, pravaha/test-runner:local (deploy/docker/test/Dockerfile: Maven, JDK 25 and Python 3),
# built on first use; PRAVAHA_TEST_IMAGE names another and skips the build. One image because the
# Python suites are cross-language: they start the real Flight server from pravaha-flight's test
# classpath, which the sdk and console suites therefore build first.
#
# Caches, kept between runs and owned by you, under ${PRAVAHA_DOCKER_CACHE:-~/.cache/pravaha-docker}:
# the Maven repository (m2/), the Python virtualenvs (venv-sdk/, venv-console/), a HOME and a TMPDIR.
# The cache and this checkout are mounted at the SAME paths inside the container as outside, so a
# classpath file the build writes, or a file a Testcontainers suite bind-mounts into a sibling
# container, names a path that is real on the host too. The first run fills m2/ -- by hard links
# from ~/.m2/repository when there is one, else from Maven Central, which takes a while once.
#
# The Testcontainers suites reach the host's Docker daemon through its socket, mounted with the
# socket's group added, on the host network so the ports Testcontainers maps are on localhost
# (TESTCONTAINERS_HOST_OVERRIDE). The containers they start are Testcontainers' own and removed by
# its reaper; nothing else on the host is touched.
#
# On a machine where the daemon is reached through a group: sg docker -c "tools/docker-test.sh all"
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
docker_bin="${DOCKER:-docker}"
cache="${PRAVAHA_DOCKER_CACHE:-$HOME/.cache/pravaha-docker}"
image="${PRAVAHA_TEST_IMAGE:-pravaha/test-runner:local}"
uid="$(id -u)"
gid="$(id -g)"

it_modules="plugins/pravaha-plugin-kafka,plugins/pravaha-plugin-postgres-cdc,plugins/pravaha-plugin-mysql-cdc,plugins/pravaha-plugin-jdbc,plugins/pravaha-plugin-aerospike,plugins/pravaha-plugin-cassandra"

usage() { sed -n '5,42p' "${BASH_SOURCE[0]}"; }

[[ $# -ge 1 ]] || { usage; exit 2; }
suite="$1"; shift
[[ "$suite" == "-h" || "$suite" == "--help" ]] && { usage; exit 0; }

if [[ "$uid" == 0 ]]; then
  echo "docker-test.sh: run it as the user who owns this checkout, not root: the containers run as" >&2
  echo "  the calling user, and everything they write here would be root's." >&2
  exit 2
fi

mkdir -p "$cache/m2" "$cache/home" "$cache/tmp"
cache="$(cd "$cache" && pwd)"

# Every run: a no-op from the build cache unless deploy/docker/test/Dockerfile changed.
if [[ -z "${PRAVAHA_TEST_IMAGE:-}" ]]; then
  "$docker_bin" build -q -t "$image" "$root/deploy/docker/test" >/dev/null
fi

seed_m2() {
  # Hard links from the host's own repository, when there is one on the same filesystem: no copy,
  # no download. Pravaha's own artefacts are left out, so the build uses what it builds.
  if [[ ! -e "$cache/m2/.seeded" ]]; then
    if [[ -d "$HOME/.m2/repository" ]] && cp -al "$HOME/.m2/repository/." "$cache/m2/" 2>/dev/null; then
      rm -rf "$cache/m2/com/ash/messaging"
      echo "docker-test.sh: Maven cache seeded from ~/.m2/repository (hard links)" >&2
    fi
    touch "$cache/m2/.seeded"
  fi
}

# in [--docker] <command...>: run a command in the test runner, as you, in this checkout.
in_runner() {
  local extra=()
  if [[ "${1:-}" == "--docker" ]]; then
    shift
    local sock=/var/run/docker.sock
    [[ -S "$sock" ]] || { echo "docker-test.sh: no Docker socket at $sock for the Testcontainers suites" >&2; exit 1; }
    extra=(-v "$sock:$sock" --group-add "$(stat -c %g "$sock")" --network host
           -e TESTCONTAINERS_HOST_OVERRIDE=localhost)
  fi
  "$docker_bin" run --rm --user "$uid:$gid" \
    -v "$root:$root" -w "$root" -v "$cache:$cache" \
    -e HOME="$cache/home" -e TMPDIR="$cache/tmp" -e MAVEN_OPTS="-Djava.io.tmpdir=$cache/tmp" \
    -e PYTHONDONTWRITEBYTECODE=1 -e PRAVAHA_BROWSER_TESTS=0 \
    ${extra[@]+"${extra[@]}"} "$image" "$@"
}

mvn() {
  local docker=()
  if [[ "${1:-}" == "--docker" ]]; then docker=(--docker); shift; fi
  seed_m2
  echo "docker-test.sh: mvn $*" >&2
  in_runner ${docker[@]+"${docker[@]}"} ./mvnw -B -ntp -Dmaven.repo.local="$cache/m2" "$@"
}

# pravaha-flight's test classes and test-classpath.txt: what the Python suites start the real
# server from.
flight_classpath() {
  mvn -pl pravaha-flight -am test-compile -Djacoco.skip=true -Dspotless.check.skip=true -q
}

run_unit() {
  mvn test "$@"
}

run_it() {
  local modules="$it_modules"
  if [[ "${1:-}" == "--modules" ]]; then modules="$2"; shift 2; fi
  # What the modules depend on, installed into the cache without its tests; then the modules'
  # own tests, with Docker. jacoco's coverage gate belongs to a whole-reactor verify, not to this.
  mvn --docker -pl "$modules" -am install -DskipTests -Djacoco.skip=true "$@"
  mvn --docker -pl "$modules" test -Djacoco.skip=true "$@"
}

run_sdk() {
  flight_classpath
  echo "docker-test.sh: the SDK's pytest suite" >&2
  in_runner sh -ec "
    [ -x '$cache/venv-sdk/bin/python' ] || python3 -m venv '$cache/venv-sdk'
    '$cache/venv-sdk/bin/pip' install --quiet --disable-pip-version-check -e 'sdk/python[flight,dev]'
    cd sdk/python && '$cache/venv-sdk/bin/python' -m pytest -q -p no:cacheprovider tests"
}

run_console() {
  flight_classpath
  echo "docker-test.sh: the console's pytest suite" >&2
  in_runner sh -ec "
    [ -x '$cache/venv-console/bin/python' ] || python3 -m venv '$cache/venv-console'
    '$cache/venv-console/bin/pip' install --quiet --disable-pip-version-check -e 'sdk/python[flight]' -e 'console[dev]'
    cd console && '$cache/venv-console/bin/python' -m pytest -q -p no:cacheprovider tests"
}

case "$suite" in
  unit)    run_unit "$@" ;;
  it)      run_it "$@" ;;
  sdk)     run_sdk ;;
  console) run_console ;;
  all)     run_unit; run_it; run_sdk; run_console ;;
  mvn)     mvn "$@" ;;
  *) echo "docker-test.sh: unknown suite '$suite' (unit, it, sdk, console, all, mvn)" >&2; exit 2 ;;
esac
