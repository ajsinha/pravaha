#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# tools/docker-env.sh's own test (ENVRERUN-1): run it, edit what its .env says is yours, run it again,
# and check that nothing you set was put back.
#
#   tools/docker-env-test.sh
#
# Runs against a copy of the script and the compose templates in a temporary directory, so the
# repository's own deploy/docker/compose/.env and pravaha-home are never touched. Needs no Docker.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"

work="$(mktemp -d "${TMPDIR:-/tmp}/pravaha-docker-env-test.XXXXXX")"
trap 'rm -rf "$work"' EXIT

pass=0
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok:   $*"; pass=$((pass + 1)); }

mkdir -p "$work/tools" "$work/deploy/docker/compose"
cp "$root/tools/docker-env.sh" "$work/tools/"
cp -r "$root/deploy/docker/compose/templates" "$work/deploy/docker/compose/"
env_file="$work/deploy/docker/compose/.env"
home="$work/home"
run() { PRAVAHA_ALLOW_ROOT=1 "$work/tools/docker-env.sh" --home "$home" >"$work/out.txt" 2>&1 \
          || fail "docker-env.sh failed: $(cat "$work/out.txt")"; }
value() { sed -n "s/^$1=//p" "$env_file" | tail -1; }

# ---------------------------------------------------------------- the first run
run
[[ -f "$env_file" ]] || fail "no .env written"
[[ "$(value PRAVAHA_TAG)" == local && "$(value PRAVAHA_HTTP_PORT)" == 18080 ]] \
  || fail "the first run did not write the defaults: $(cat "$env_file")"
[[ "$(stat -c %a "$env_file")" == 600 ]] || fail ".env is not 0600"
ok "the first run writes the defaults, 0600"
seed_token="$(value PRAVAHA_SEED_TOKEN)"
postgres_password="$(value POSTGRES_PASSWORD)"

# ---------------------------------------------------------------- the user's edits
declare -A mine=(
  [COMPOSE_PROJECT_NAME]=my-stack
  [PRAVAHA_TAG]=2.0.1-mine
  [PRAVAHA_BIND]=0.0.0.0
  [PRAVAHA_HTTP_PORT]=38080
  [PRAVAHA_FLIGHT_PORT]=39090
  [PRAVAHA_PGWIRE_PORT]=35432
  [PRAVAHA_CONSOLE_PORT]=37070
  [PRAVAHA_KAFKA_PORT]=39092
  [PRAVAHA_POSTGRES_PORT]=35433
  [PRAVAHA_MYSQL_PORT]=33306
  [PRAVAHA_AEROSPIKE_PORT]=33100
  [PRAVAHA_CASSANDRA_PORT]=39042
  [PRAVAHA_PROMETHEUS_PORT]=39190
  [PRAVAHA_GRAFANA_PORT]=33030
)
for key in "${!mine[@]}"; do
  sed -i "s|^$key=.*|$key=${mine[$key]}|" "$env_file"
done
echo "COMPOSE_PROFILES=seed" >> "$env_file"

# ---------------------------------------------------------------- the re-run
run
for key in "${!mine[@]}"; do
  [[ "$(value "$key")" == "${mine[$key]}" ]] \
    || fail "a re-run put $key back to '$(value "$key")'; it was set to '${mine[$key]}'"
done
ok "a re-run keeps every port, the bind address, the image tag and the project name"
[[ "$(value COMPOSE_PROFILES)" == seed ]] || fail "a re-run dropped a line it does not write (COMPOSE_PROFILES)"
ok "a re-run keeps a line it does not write itself"
[[ "$(value PRAVAHA_SEED_TOKEN)" == "$seed_token" && "$(value POSTGRES_PASSWORD)" == "$postgres_password" ]] \
  || fail "a re-run changed a generated credential"
ok "a re-run keeps the generated credentials"
[[ "$(value PRAVAHA_UID)" == "$(id -u)" && "$(value PRAVAHA_HOME_DIR)" == "$home" ]] \
  || fail "uid or home not refreshed"
ok "uid, gid and home are refreshed"
[[ "$(grep -c '^PRAVAHA_TAG=' "$env_file")" == 1 && "$(grep -c '^COMPOSE_PROFILES=' "$env_file")" == 1 ]] \
  || fail "a key is written twice: $(cat "$env_file")"
ok "every key is written once"

# ---------------------------------------------------------------- a third run changes nothing
cp "$env_file" "$work/second.env"
run
diff -u "$work/second.env" "$env_file" >/dev/null || fail "a third run changed .env: $(diff -u "$work/second.env" "$env_file")"
ok "a third run leaves .env byte for byte as it was"
leftovers="$(find "$work/deploy/docker/compose" -maxdepth 1 -name '.env.*' | wc -l)"
[[ "$leftovers" == 0 ]] || fail "a temporary .env was left behind"
[[ "$(stat -c %a "$env_file")" == 600 ]] || fail ".env is not 0600 after a re-run"
ok "no temporary file is left, and .env is still 0600"

echo
echo "docker-env-test.sh: $pass checks PASSED"
