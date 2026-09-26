#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The whole product, end to end, in one command: a real engine node in a container, the real
# console pointed at it, and a browser's worth of pages fetched over HTTP.
#
#   tools/qa-smoke.sh                       # build nothing, use pravaha:qa
#   tools/qa-smoke.sh --image pravaha:0.1.0
#   tools/qa-smoke.sh --keep                # leave the node and console running afterwards
#
# WHY THIS EXISTS. The console's own 844 tests run against a fake engine, and its few real-engine
# tests reach a Flight-only test server with no HTTP surface at all -- so until this script,
# nothing had ever driven the console against an engine that was really running. Everything it
# checks was already covered by a unit test somewhere; what was not covered is that the pieces
# find each other.
#
# It does NOT replace the suites. It is the last check before a release and the first check after
# an unfamiliar deployment, and it is deliberately shallow: if it passes, the parts are connected;
# if the answers are wrong, that is what the suites are for.
#
# On a machine where the daemon is reached through a group, put the whole line inside it:
#   sg docker -c "tools/qa-smoke.sh"
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
image="pravaha:qa"
keep=0
docker_bin="${DOCKER:-docker}"
http_port="${QA_HTTP_PORT:-18080}"
flight_port="${QA_FLIGHT_PORT:-19090}"
console_port="${QA_CONSOLE_PORT:-18090}"
password="qa-smoke-$RANDOM"
name="pravaha-qa-smoke-$$"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image|-i) image="$2"; shift 2 ;;
    --keep)     keep=1; shift ;;
    -h|--help)  sed -n '5,22p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "qa-smoke.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

# A port already in use means something else answers, and every check below would then be a
# statement about that something else.
for port in "$http_port" "$flight_port" "$console_port"; do
  if (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null; then
    echo "qa-smoke.sh: port $port is already in use. Stop what is on it, or set QA_HTTP_PORT," >&2
    echo "             QA_FLIGHT_PORT or QA_CONSOLE_PORT. Refusing rather than testing it." >&2
    exit 2
  fi
done

work="$(mktemp -d "${TMPDIR:-/tmp}/pravaha-qa.XXXXXX")"
mkdir -p "$work/data"
chmod 777 "$work/data"
console_pid=""
failures=0

ok()   { echo "ok:   $*"; }
bad()  { echo "BAD:  $*"; failures=$((failures + 1)); }
step() { echo; echo "=== $* ==="; }

cleanup() {
  local status=$?
  if [[ "$keep" == 1 ]]; then
    echo "qa-smoke.sh: --keep, leaving node '$name' on $http_port/$flight_port and the console on $console_port"
    return
  fi
  [[ -n "$console_pid" ]] && kill "$console_pid" 2>/dev/null || true
  "$docker_bin" rm -f "$name" >/dev/null 2>&1 || true
  # The node wrote its state as uid 10001, so the user running this script cannot remove it.
  # One throwaway container, as root, over the same bind mount, and only over $work -- which
  # mktemp made and nothing else is in. deploy/docker/smoke.sh does the same, for the same reason.
  if [[ -d "$work" ]]; then
    "$docker_bin" run --rm --user 0 --entrypoint /bin/sh \
      -v "$work:/qa-work" "$image" -c 'rm -rf /qa-work/data' >/dev/null 2>&1 || true
    rm -rf "$work" 2>/dev/null || echo "qa-smoke.sh: could not remove $work" >&2
  fi
  exit $status
}
trap cleanup EXIT

step "a node, from the image"
# PRAVAHA_SECURITY_ALLOWANONYMOUS: the node REFUSES to start otherwise (PRV-7004), because an
# engine that serves every view to every caller is a decision somebody has to make on purpose.
# A smoke test is exactly the case where that decision is fine and has to be stated anyway.
"$docker_bin" run -d --rm --name "$name" \
  -p "$http_port:8080" -p "$flight_port:9090" \
  -e PRAVAHA_SECURITY_ALLOWANONYMOUS=true \
  -v "$work/data:/var/lib/pravaha" "$image" >/dev/null
echo "      $image as $name, http $http_port, flight $flight_port"

for _ in $(seq 1 60); do
  [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$http_port/actuator/health/readiness")" == "200" ]] && break
  sleep 1
done
if [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$http_port/actuator/health/readiness")" == "200" ]]; then
  ok "the node is ready"
else
  bad "the node never became ready; its log follows"
  "$docker_bin" logs "$name" 2>&1 | tail -20
  exit 1
fi

status="$(curl -s "http://localhost:$http_port/api/v1/status")"
echo "      $status"
grep -q '"engineState":"RUNNING"' <<<"$status" && ok "it says it is RUNNING" || bad "engineState is not RUNNING"

# Every connector ships inside the server jar (2026-09-26), so the node in the image must be able to
# load all of them. A plugin missing from this list is one a deployment cannot bind -- which is how
# jdbc-lookup and aerospike-lookup went unreachable on every node without anything saying so.
expected="aerospike aerospike-lookup aerospike-sink cassandra delta delta-sink feedfile filesystem jdbc jdbc-lookup jdbc-sink kafka kafka-sink postgres-cdc"
loaded="$(curl -s "http://localhost:$http_port/api/v1/plugins" \
  | python3 -c 'import sys,json; print(" ".join(sorted(p["name"] for p in json.load(sys.stdin) if p.get("loaded"))))')"
missing=""
for plugin in $expected; do
  grep -qw -- "$plugin" <<<"$loaded" || missing="$missing $plugin"
done
if [[ -z "$missing" ]]; then ok "all 14 shipped plugins load inside the image"
else bad "plugins the image cannot load:$missing"; fi

step "the console, pointed at that node"
if [[ ! -x "$root/console/.venv/bin/python" ]]; then
  bad "no console virtualenv at console/.venv -- see console/README.md"
  exit 1
fi
# `exec`, so that $! is the console itself and not the subshell around it. Without it the
# cleanup kills a shell that has already gone and leaves the console running -- and the NEXT run
# of this script talks to the previous run's console, which still holds the previous run's
# password, and reports the failed sign-in as five broken pages. Found exactly that way.
(
  cd "$root/console"
  exec env PRAVAHA_ENGINE="grpc://localhost:$flight_port" \
      PRAVAHA_ENGINE_HTTP="http://localhost:$http_port" \
      CONSOLE_PASSWORD="$password" \
      CONSOLE_SESSION_SECRET="qa-smoke-secret" \
      CONSOLE_PORT="$console_port" \
      .venv/bin/python run_pravaha_web.py > "$work/console.log" 2>&1
) &
console_pid=$!

for _ in $(seq 1 60); do
  [[ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$console_port/")" == "200" ]] && break
  sleep 1
done

jar="$work/cookies"
code() { curl -s -b "$jar" -c "$jar" -o /dev/null -w '%{http_code}' "http://localhost:$console_port$1"; }

step "what a stranger may see"
for page in / /help /about; do
  if [[ "$(code "$page")" == "200" ]]; then ok "$page answers a visitor who has not signed in"
  else bad "$page does not answer an anonymous visitor ($(code "$page"))"; fi
done
for page in /catalog /operations; do
  if [[ "$(code "$page")" == "303" ]]; then ok "$page sends an anonymous visitor to sign in"
  else bad "$page did not require a session ($(code "$page")) -- a page that reads the engine must"; fi
done

step "signed in, against the running node"
rm -f "$jar"
curl -s -c "$jar" -b "$jar" -o /dev/null -X POST \
  -d "password=$password&role=operator&next=/home" "http://localhost:$console_port/login"
for page in /catalog /operations /workbench /queries; do
  if [[ "$(code "$page")" == "200" ]]; then ok "$page renders"
  else bad "$page answered $(code "$page")"; fi
done

# The console reaching the engine, rather than the console rendering: the overview's health line
# comes from the engine's own status and says RUNNING only when the call succeeded.
if curl -s -b "$jar" -L "http://localhost:$console_port/overview" | grep -q "RUNNING"; then
  ok "the overview reports the engine RUNNING, so the console really reached it"
else
  bad "the overview does not report the engine as running -- the console is not reaching the node"
fi

step "done"
if [[ "$failures" == 0 ]]; then
  echo "qa-smoke.sh: PASSED"
else
  echo "qa-smoke.sh: FAILED -- $failures check(s); console log at $work/console.log"
  [[ "$keep" == 0 ]] && tail -20 "$work/console.log"
  exit 1
fi
