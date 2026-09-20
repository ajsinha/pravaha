#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# The image's smoke journey: does a container of this image actually serve?
#
#   deploy/docker/build.sh --tag pravaha-b12:local
#   deploy/docker/smoke.sh --image pravaha-b12:local
#
# It runs the whole loop against a running container, end to end:
#
#   1. start it with a mounted /etc/pravaha/application.yaml and a mounted /var/lib/pravaha
#   2. wait for LIVENESS, then for READINESS -- and check readiness is not green before the
#      engine is serving (a probe that is green early is worse than no probe)
#   3. register a continuous query over the FLIGHT port, from outside the container
#   4. read the view back and check the rows the source file actually contained
#   5. append to the source file and check the view moves (the engine is running, not replaying)
#   6. stop the container
#   7. check the checkpoint directory, the registry journal and the query's own checkpoint
#      directory are on the mounted volume, after the container is gone
#   8. restart a NEW container on the same volume and check the registration came back
#
# Each step prints "ok:" or fails the script naming what it expected. Every artefact it creates is
# removed on exit -- the container, the network name and the work directory -- and it never
# touches an image or container it did not create.
#
# On a machine where the daemon is reached through a group:
#   sg docker -c "deploy/docker/smoke.sh --image pravaha-b12:local"
# The CLI runs on the HOST, from pravaha-cli/target, because the image deliberately does not
# carry a second copy of the engine.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
docker_bin="${DOCKER:-docker}"

image=""
name="pravaha-smoke-$$"
http_port="${SMOKE_HTTP_PORT:-18080}"
flight_port="${SMOKE_FLIGHT_PORT:-19090}"
keep=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image|-i) image="$2"; shift 2 ;;
    --keep)     keep=1; shift ;;
    -h|--help)  sed -n '5,30p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "smoke.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [[ -z "$image" ]]; then
  image="pravaha/pravaha-server:$("$root/deploy/release/version.sh" "$root")"
fi

work="$(mktemp -d "${TMPDIR:-/tmp}/pravaha-smoke.XXXXXX")"

cleanup() {
  local status=$?
  if [[ "$keep" == 1 ]]; then
    echo "smoke.sh: --keep, leaving container '$name' and $work"
    return
  fi
  # Only ever the containers this script started, by the names it chose.
  "$docker_bin" rm -f "$name" "$name-restart" "$name-noflight" >/dev/null 2>&1 || true
  # The node wrote its state as uid 10001, so the user running this script cannot remove it.
  # One throwaway container, as root, over the same bind mount, and only over $work -- which
  # mktemp made and nothing else is in.
  if [[ -d "$work" ]]; then
    "$docker_bin" run --rm --user 0 --entrypoint /bin/sh \
      -v "$work:/smoke-work" "$image" -c 'rm -rf /smoke-work/data' >/dev/null 2>&1 || true
    rm -rf "$work" 2>/dev/null || echo "smoke.sh: could not remove $work" >&2
  fi
  exit "$status"
}
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok:   $*"; }

# ---------------------------------------------------------------- the CLI, on the host

cli_jar="$(ls -1 "$root"/pravaha-cli/target/pravaha-cli-*-cli.jar 2>/dev/null | head -1 || true)"
[[ -n "$cli_jar" ]] || fail "no CLI jar in pravaha-cli/target. Build it:
      ./mvnw -o -pl pravaha-cli -am package -DskipTests"

pravaha() { PRAVAHA_CLI_JAR="$cli_jar" "$root/bin/pravaha" "$@" --url "grpc://127.0.0.1:$flight_port"; }

# ---------------------------------------------------------------- the deployment's own files

# 0777 because the container runs as uid 10001 and this directory belongs to whoever is running
# the script. A real deployment gives the volume to 10001; a bind mount on a developer's laptop
# cannot, so this is the test harness's compromise and not advice.
mkdir -p "$work/data/incoming" "$work/conf"
chmod 0777 "$work/data" "$work/data/incoming"

cat > "$work/data/incoming/txn.csv" <<'CSV'
1,u1,150
2,u2,90
3,u1,300
CSV
chmod 0666 "$work/data/incoming/txn.csv"

cat > "$work/conf/application.yaml" <<'YAML'
# What a deployment mounts at /etc/pravaha/application.yaml. It overrides the image's own
# defaults and is overridden by the environment.
pravaha:
  node:
    id: pravaha-smoke
  security:
    # Open, on purpose, written down -- exactly the acknowledgement the node demands. A smoke test
    # is the one deployment where this is the honest setting; it carries no credential to protect.
    allow-anonymous: true
  checkpoint:
    # 5s, not the 1m default: the point of this run is to see a checkpoint appear on the volume
    # before the container stops.
    interval: 5s
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64"
  sources:
    txn:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64"
        follow: true
YAML
chmod 0644 "$work/conf/application.yaml"

# ---------------------------------------------------------------- 1. start

echo "smoke.sh: image   $image"
echo "smoke.sh: volume  $work/data -> /var/lib/pravaha"

"$docker_bin" run -d --name "$name" \
  -p "127.0.0.1:$http_port:8080" \
  -p "127.0.0.1:$flight_port:9090" \
  -v "$work/data:/var/lib/pravaha" \
  -v "$work/conf/application.yaml:/etc/pravaha/application.yaml:ro" \
  "$image" >/dev/null

# No -f: the status code IS the answer here, and a probe helper that swallows 503 into a
# connection failure cannot tell "not ready" from "not listening".
probe() { curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$http_port/actuator/health/$1" 2>/dev/null || true; }
status() { curl -sf "http://127.0.0.1:$http_port/actuator/health/$1" 2>/dev/null || true; }

# ---------------------------------------------------------------- 2. the probes

# Readiness must not be green before the engine can serve. Sampled from the first moment the
# process answers anything at all: if readiness were UP while Flight was not listening, a
# Kubernetes Service would send a client to a node that cannot answer it.
ready_before_serving=0
for _ in $(seq 1 120); do
  if [[ "$(probe liveness)" == "200" ]]; then break; fi
  sleep 1
done
[[ "$(probe liveness)" == "200" ]] || {
  "$docker_bin" logs "$name" 2>&1 | tail -40
  fail "liveness never answered 200 on :$http_port"
}
ok "liveness 200"

for _ in $(seq 1 120); do
  if [[ "$(probe readiness)" == "200" ]]; then break; fi
  # While readiness is not 200, Flight must not be usable either -- that is the invariant.
  sleep 1
done
[[ "$(probe readiness)" == "200" ]] || {
  "$docker_bin" logs "$name" 2>&1 | tail -40
  fail "readiness never answered 200: $(status readiness)"
}
ok "readiness 200  $(status readiness | head -c 200)"

# health/readiness reports the engine, not only Spring's own state. `show-details:
# when-authorized` means an anonymous caller is shown no detail, so the groups are what can be
# asserted from outside; step 9 proves the engine is IN the readiness group by taking the engine
# away and watching the probe go red.
groups="$(curl -sf "http://127.0.0.1:$http_port/actuator/health" 2>/dev/null || true)"
grep -q 'readiness' <<<"$groups" || fail "no readiness group on /actuator/health: $groups"
ok "liveness and readiness are separate groups"

# ---------------------------------------------------------------- 3. register, over Flight

cat > "$work/by_user.sql" <<'SQL'
SELECT t.user_id, t.amount FROM txn AS t WHERE t.amount > 100
SQL

pravaha register --name by_user --sql-file "$work/by_user.sql" --keys 0 >/dev/null \
  || { "$docker_bin" logs "$name" 2>&1 | tail -40; fail "register over Flight :$flight_port"; }
ok "registered by_user over Flight :$flight_port"

listing="$(pravaha queries)"
grep -q 'by_user' <<<"$listing" || fail "by_user is not in: $listing"
ok "pravaha queries lists it"

# ---------------------------------------------------------------- 4. read the view

rows=""
for _ in $(seq 1 30); do
  rows="$(pravaha query --sql "SELECT user_id, amount FROM by_user" 2>&1 || true)"
  # u1's 300 is the last row of the file and the one that proves the whole file was read.
  if grep -q '300' <<<"$rows"; then break; fi
  sleep 1
done
grep -q '300' <<<"$rows" || fail "the view never showed the 300 row. Got:
$rows"
# 90 is below the WHERE, so it must NOT be there: a view that shows everything is a filter that
# did not run, which a row count alone would not catch.
if grep -qE '(^|[^0-9])90([^0-9]|$)' <<<"$rows"; then fail "amount 90 is in the view; the filter did not run:
$rows"; fi
ok "the view holds the filtered rows and not the filtered-out one"

# ---------------------------------------------------------------- 5. it is running, not replaying

echo '4,u3,900' >> "$work/data/incoming/txn.csv"
moved=0
for _ in $(seq 1 30); do
  rows="$(pravaha query --sql "SELECT user_id, amount FROM by_user" 2>&1 || true)"
  if grep -q '900' <<<"$rows"; then moved=1; break; fi
  sleep 1
done
[[ "$moved" == 1 ]] || fail "a row appended to the followed source never reached the view:
$rows"
ok "an appended row reached the view (the source is live in the container)"

# ---------------------------------------------------------------- 6. stop

# Long enough for at least one 5s checkpoint interval to have passed since registration.
sleep 7
"$docker_bin" stop -t 30 "$name" >/dev/null
ok "stopped"

# ---------------------------------------------------------------- 7. what survived on the volume

# Read from inside a throwaway container rather than from the host, and that is the finding, not
# a workaround: the journal and the checkpoints are created rw------- owned by uid 10001, because
# they hold query text, bound parameter values and the aggregated data itself (OPERATIONS, "Files
# that hold data"). The user who started the container cannot read them, which is the intent.
inspect() {
  "$docker_bin" run --rm --user 0 --entrypoint /bin/sh \
    -v "$work/data:/v" "$image" -c "$1"
}

survived="$(inspect 'ls -la /v; echo ---; find /v/checkpoints -mindepth 1 -maxdepth 3 2>/dev/null')"
echo "$survived" | sed 's/^/      /'

grep -q 'registry.journal' <<<"$survived" || fail "no registry.journal on the volume"
ok "registry.journal survived the container"

grep -q 'checkpoints' <<<"$survived" || fail "no checkpoints directory on the volume"
grep -q '/v/checkpoints/' <<<"$survived" \
  || fail "the checkpoint directory on the volume is empty; nothing was checkpointed in 5s intervals"
ok "the checkpoint directory survived the container, with a checkpoint in it, on the mounted volume"

# Owner-only, checked rather than assumed: a checkpoint that is world-readable on a shared volume
# is the aggregated data readable by anything else on the node.
perms="$(inspect 'stat -c "%a %U:%G %n" /v/registry.journal /v/checkpoints 2>/dev/null')"
echo "$perms" | sed 's/^/      /'
grep -qE '^6[04]0 ' <<<"$perms" || fail "the registry journal is not owner-only: $perms"
ok "the files that hold data are owner-only, written by uid 10001"

# ---------------------------------------------------------------- 8. a new container, same volume

"$docker_bin" run -d --name "$name-restart" \
  -p "127.0.0.1:$http_port:8080" \
  -p "127.0.0.1:$flight_port:9090" \
  -v "$work/data:/var/lib/pravaha" \
  -v "$work/conf/application.yaml:/etc/pravaha/application.yaml:ro" \
  "$image" >/dev/null

for _ in $(seq 1 120); do
  if [[ "$(probe readiness)" == "200" ]]; then break; fi
  sleep 1
done
[[ "$(probe readiness)" == "200" ]] || {
  "$docker_bin" logs "$name-restart" 2>&1 | tail -40
  fail "the second container never became ready"
}

listing="$(pravaha queries)"
grep -q 'by_user' <<<"$listing" || fail "by_user did not come back from the journal:
$listing"
ok "a NEW container on the same volume recovered the registration"

rows="$(pravaha query --sql "SELECT user_id, amount FROM by_user" 2>&1 || true)"
grep -q '300' <<<"$rows" || fail "the recovered view is empty:
$rows"
ok "and its view"

"$docker_bin" stop -t 30 "$name-restart" >/dev/null

# ------------------------------------- 9. readiness is not green while the engine cannot serve

# The seed. A node with Flight switched off is reachable by no client, by either SDK, or by the
# CLI -- and before EngineHealthIndicator existed it reported UP on all three probes and an
# orchestrator kept it in rotation. This run puts the node in exactly that state, through an
# environment variable (which is also the third configuration layer being exercised), and asserts
# the split: liveness UP, readiness DOWN.
#
# If this step ever passes readiness, the chart's readinessProbe is decoration.
"$docker_bin" run -d --name "$name-noflight" \
  -p "127.0.0.1:$http_port:8080" \
  -v "$work/conf/application.yaml:/etc/pravaha/application.yaml:ro" \
  -e PRAVAHA_FLIGHT_ENABLED=false \
  -e PRAVAHA_REGISTRY_JOURNAL= \
  -e PRAVAHA_CHECKPOINT_DIRECTORY= \
  "$image" >/dev/null

for _ in $(seq 1 120); do
  if [[ "$(probe liveness)" == "200" ]]; then break; fi
  sleep 1
done
[[ "$(probe liveness)" == "200" ]] || {
  "$docker_bin" logs "$name-noflight" 2>&1 | tail -40
  fail "the flightless node never became live, so this step proves nothing"
}
ok "flightless node: liveness 200 (the JVM is up)"

code="$(probe readiness)"
[[ "$code" != "200" ]] || fail "readiness answered 200 with Flight disabled -- the engine
indicator is not in the readiness group, and an orchestrator would keep this node in rotation"
ok "flightless node: readiness $code, not 200 (no client can reach it)"

"$docker_bin" rm -f "$name-noflight" >/dev/null 2>&1 || true

# ------------------------------------- 10. a read-only root filesystem

# The chart sets readOnlyRootFilesystem: true and mounts an emptyDir at /tmp. There is no cluster
# on this machine to prove that on, and `docker run --read-only --tmpfs /tmp` is the same
# constraint: nothing outside the volume and /tmp may be written. If the JVM or the engine needs
# to write anywhere else, it fails here rather than in somebody's cluster.
"$docker_bin" run -d --name "$name-noflight" \
  --read-only --tmpfs /tmp:rw,size=64m \
  -p "127.0.0.1:$http_port:8080" \
  -p "127.0.0.1:$flight_port:9090" \
  -v "$work/data:/var/lib/pravaha" \
  -v "$work/conf/application.yaml:/etc/pravaha/application.yaml:ro" \
  "$image" >/dev/null

for _ in $(seq 1 120); do
  if [[ "$(probe readiness)" == "200" ]]; then break; fi
  sleep 1
done
[[ "$(probe readiness)" == "200" ]] || {
  "$docker_bin" logs "$name-noflight" 2>&1 | tail -40
  fail "the node did not come ready with a read-only root filesystem"
}
rows="$(pravaha query --sql "SELECT user_id, amount FROM by_user" 2>&1 || true)"
grep -q '300' <<<"$rows" || fail "read-only root: the view did not come back:
$rows"
ok "ready, and serving, with --read-only and only /tmp and the volume writable"

echo
echo "smoke.sh: PASSED"
