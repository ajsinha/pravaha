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
#   1. start it with a mounted /opt/pravaha/conf/application.yaml and a mounted /opt/pravaha/data
#   2. wait for LIVENESS, then for READINESS -- and check readiness is not green before the
#      engine is serving (a probe that is green early is worse than no probe); then run the
#      image's own HEALTHCHECK inside the container, which has tools the host has and it may not
#   3. register a continuous query over the FLIGHT port, from outside the container
#   4. read the view back and check the rows the source file actually contained
#   5. append to the source file and check the view moves (the engine is running, not replaying)
#   6. stop the container
#   7. check the checkpoint directory, the registry journal and the query's own checkpoint
#      directory are on the mounted volume, after the container is gone
#   8. restart a NEW container on the same volume and check the registration came back
#   9. SEED: a node with PRAVAHA_FLIGHT_ENABLED=false must answer liveness 200 and readiness 503.
#      If that ever passes readiness, the engine indicator has left the readiness group and the
#      chart's readinessProbe is decoration
#  10. SEED: the node must come ready and serve under `docker run --read-only`, which is the
#      constraint deploy/helm/pravaha sets with readOnlyRootFilesystem and which there is no
#      cluster on this machine to prove any other way
#  11. the image's native code loads: Parquet's snappy and zstd codecs round-trip bytes inside the
#      image, with the same read-only root and /tmp as step 10 (ADR-053, PORT-1)
#
# Each step prints "ok:" or fails the script naming what it expected. Every artefact it creates is
# removed on exit -- the containers, by the names it chose, and the work directory, whose contents
# it removes through a throwaway root container because the node wrote them as uid 10001. It never
# touches an image or container it did not create.
#
# On a machine where the daemon is reached through a group:
#   sg docker -c "deploy/docker/smoke.sh --image pravaha-b12:local"
# The client runs on the HOST (the Python CLI, and curl), because the image deliberately does not
# carry a client, or Python.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
docker_bin="${DOCKER:-docker}"

image=""
name="pravaha-smoke-$$"
# Host ports, deliberately not the defaults (18080, 19090): a node already running here on its
# defaults must not be what gets tested.
http_port="${SMOKE_HTTP_PORT:-28080}"
flight_port="${SMOKE_FLIGHT_PORT:-29090}"
keep=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --image|-i) image="$2"; shift 2 ;;
    --keep)     keep=1; shift ;;
    -h|--help)  sed -n '6,38p' "${BASH_SOURCE[0]}"; exit 0 ;;
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

# ---------------------------------------------------------------- the client, on the host

# Registering a query and reading a view's rows happen over Flight, and REST has no verb for
# either, so those two steps use the Python CLI on the host (bin/pravaha; the image carries no
# Python and needs none). Everything REST can answer -- which queries are registered -- is asked
# with curl instead, so the least tooling possible stands between this script and the node.
[[ -x "$root/bin/pravaha" ]] || fail "no bin/pravaha: the Python CLI registers and reads over Flight.
      pip install the Pravaha Python SDK (sdk/python) so bin/pravaha can run"
# bin/pravaha picks PRAVAHA_PYTHON, else sdk/python/.venv, else python3; the Flight steps need pyarrow
# in that interpreter. Ask before starting a container, so a missing pyarrow is named here rather
# than surfacing later as a failed "register over Flight".
smoke_python="${PRAVAHA_PYTHON:-}"
if [[ -z "$smoke_python" ]]; then
  if [[ -x "$root/sdk/python/.venv/bin/python" ]]; then smoke_python="$root/sdk/python/.venv/bin/python"; else smoke_python=python3; fi
fi
"$smoke_python" -c 'import pyarrow.flight' 2>/dev/null || fail "$smoke_python cannot import pyarrow.flight, which the Flight steps need.
      make -C sdk/python install   (creates sdk/python/.venv with pyarrow), or set PRAVAHA_PYTHON
      to an interpreter that has pyarrow"

pravaha() { "$root/bin/pravaha" "$@" --url "grpc://127.0.0.1:$flight_port"; }
# The names of the registered queries, from GET /api/v1/queries.
queries() { curl -sf "http://127.0.0.1:$http_port/api/v1/queries" 2>/dev/null || true; }

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
# What a deployment mounts at /opt/pravaha/conf/application.yaml. It overrides the image's own
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
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64"
        follow: true
YAML
chmod 0644 "$work/conf/application.yaml"

# ---------------------------------------------------------------- 1. start

echo "smoke.sh: image   $image"
echo "smoke.sh: volume  $work/data -> /opt/pravaha/data"

"$docker_bin" run -d --name "$name" \
  -p "127.0.0.1:$http_port:18080" \
  -p "127.0.0.1:$flight_port:19090" \
  -v "$work/data:/opt/pravaha/data" \
  -v "$work/conf/application.yaml:/opt/pravaha/conf/application.yaml:ro" \
  "$image" >/dev/null

# No -f: the status code IS the answer here, and a probe helper that swallows 503 into a
# connection failure cannot tell "not ready" from "not listening".
probe() { curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$http_port/actuator/health/$1" 2>/dev/null || true; }
status() { curl -sf "http://127.0.0.1:$http_port/actuator/health/$1" 2>/dev/null || true; }

# ---------------------------------------------------------------- 2. the probes

# Liveness first: the JVM is up. It says nothing about whether a client could be served, which is
# the whole point of keeping the two apart -- step 9 is where that separation is proved.
for _ in $(seq 1 120); do
  if [[ "$(probe liveness)" == "200" ]]; then break; fi
  sleep 1
done
[[ "$(probe liveness)" == "200" ]] || {
  "$docker_bin" logs "$name" 2>&1 | tail -40
  fail "liveness never answered 200 on :$http_port"
}
ok "liveness 200"

# Then readiness, which is the one a Service follows. Two minutes: a node restoring a large
# checkpoint is not a node that has failed.
for _ in $(seq 1 120); do
  if [[ "$(probe readiness)" == "200" ]]; then break; fi
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

# The image's own HEALTHCHECK, run now inside the container rather than waited for (its first run is
# 30s away). Everything above probes from the HOST, which is how a HEALTHCHECK that called a wget the
# JRE 25 base does not carry passed this script while Docker called the node unhealthy forever.
mapfile -t health_cmd < <("$docker_bin" image inspect "$image" \
  --format '{{join .Config.Healthcheck.Test "\n"}}')
case "${health_cmd[0]:-}" in
  CMD)       health_cmd=("${health_cmd[@]:1}") ;;
  CMD-SHELL) health_cmd=(sh -c "${health_cmd[1]}") ;;
  *) fail "the image declares no HEALTHCHECK: ${health_cmd[*]:-none}" ;;
esac
health_out="$("$docker_bin" exec "$name" "${health_cmd[@]}" 2>&1)" \
  || fail "the image's HEALTHCHECK fails inside a serving container: ${health_cmd[*]}
$health_out"
ok "the image's HEALTHCHECK passes inside the container: ${health_cmd[*]}"

# ---------------------------------------------------------------- 3. register, over Flight

cat > "$work/by_user.sql" <<'SQL'
SELECT t.user_id, t.amount FROM txn AS t WHERE t.amount > 100
SQL

pravaha register --name by_user --sql-file "$work/by_user.sql" --keys 0 >/dev/null \
  || { "$docker_bin" logs "$name" 2>&1 | tail -40; fail "register over Flight :$flight_port"; }
ok "registered by_user over Flight :$flight_port"

listing="$(queries)"
grep -q 'by_user' <<<"$listing" || fail "by_user is not in: $listing"
ok "GET /api/v1/queries lists it"

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
  -p "127.0.0.1:$http_port:18080" \
  -p "127.0.0.1:$flight_port:19090" \
  -v "$work/data:/opt/pravaha/data" \
  -v "$work/conf/application.yaml:/opt/pravaha/conf/application.yaml:ro" \
  "$image" >/dev/null

for _ in $(seq 1 120); do
  if [[ "$(probe readiness)" == "200" ]]; then break; fi
  sleep 1
done
[[ "$(probe readiness)" == "200" ]] || {
  "$docker_bin" logs "$name-restart" 2>&1 | tail -40
  fail "the second container never became ready"
}

listing="$(queries)"
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
  -p "127.0.0.1:$http_port:18080" \
  -v "$work/conf/application.yaml:/opt/pravaha/conf/application.yaml:ro" \
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

# The chart sets readOnlyRootFilesystem: true and mounts emptyDirs at /opt/pravaha/tmp and
# /opt/pravaha/logs. There is no cluster on this machine to prove that on, and `docker run
# --read-only` with a tmpfs at /opt/pravaha/tmp and nothing at /tmp is the same constraint and a
# stricter one: NOTHING outside /opt/pravaha may be written -- the PRAVAHA_HOME rule
# (docs/operations/RUNNING_IN_DOCKER.md). logs/ is the image's VOLUME, so it gets an anonymous one. If the JVM
# or the engine needs to write anywhere else, it fails here rather than in somebody's cluster.
# `exec` because Docker's tmpfs is noexec by default and the Parquet codecs load their native
# library from java.io.tmpdir; a Kubernetes emptyDir allows it (ADR-053). mode=1777 because a
# Docker tmpfs is root's 0755 otherwise, and the node does not run as root.
"$docker_bin" run -d --name "$name-noflight" \
  --read-only --tmpfs /opt/pravaha/tmp:rw,exec,size=64m,mode=1777 \
  -p "127.0.0.1:$http_port:18080" \
  -p "127.0.0.1:$flight_port:19090" \
  -v "$work/data:/opt/pravaha/data" \
  -v "$work/conf/application.yaml:/opt/pravaha/conf/application.yaml:ro" \
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
ok "ready, and serving, with --read-only and only /opt/pravaha/{data,logs,tmp} writable"


# ------------------------------------- 11. the image's native code loads

# The only native code the build allows is Parquet's two codecs (ADR-053). Each is loaded and made
# to round-trip bytes inside the image, under the same constraints as step 10, because a native
# library that does not load fails at the first Parquet file rather than at startup -- which is how
# snappy-java failed unseen on the Alpine image (PORT-1). --enable-native-access is what
# bin/pravaha-server always gives the JVM (JEP 472); without it the Java 25 image prints four WARNING
# lines about the very loading this step is here to prove.
codecs="$("$docker_bin" run --rm --read-only --tmpfs /tmp:rw,exec,size=64m --entrypoint java "$image" \
  --enable-native-access=ALL-UNNAMED -cp lib/pravaha-server.jar -Dloader.main=com.ash.messaging.pravaha.server.NativeCodecs \
  org.springframework.boot.loader.launch.PropertiesLauncher 2>&1)" || fail "a native codec does not load in the image:
$codecs"
grep -q '^snappy loaded' <<<"$codecs" || fail "snappy did not load in the image:
$codecs"
grep -q '^zstd loaded' <<<"$codecs" || fail "zstd did not load in the image:
$codecs"
ok "Parquet's native codecs load in the image: $(tr '\n' ' ' <<<"$codecs")"

echo
echo "smoke.sh: PASSED"
