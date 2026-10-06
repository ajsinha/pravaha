#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Prepare the compose stack (deploy/docker/compose) to run as YOU: a PRAVAHA_HOME on this host owned
# by your user, the credentials the stack needs, and deploy/docker/compose/.env pointing at both.
#
#   tools/docker-env.sh                          # pravaha-home in deploy/docker/compose/pravaha-home
#   tools/docker-env.sh --home ~/pravaha-home    # anywhere else
#   tools/docker-env.sh --print                  # show what .env holds, secrets masked
#
# What it lays down, every path the engine and the console write (docs/operations/RUNNING_IN_DOCKER.md):
#
#   <home>/conf/application.yaml     the engine's configuration      (0600, from compose/templates)
#   <home>/conf/console.yaml         the console's configuration     (0600, from compose/templates)
#   <home>/secrets/                  0700: initial-admin-password, seed.token, prometheus.token
#   <home>/plugins/                  jars for the engine's classpath (empty)
#   <home>/data/, <home>/data/console/, <home>/logs/, <home>/tmp/
#   deploy/docker/compose/.env       uid, gid, the home, ports, generated passwords (0600)
#
# Every directory is created HERE, by you, before Docker sees it -- a bind-mount source that does
# not exist is created by the daemon, as root. The containers then run as your uid and gid, so what
# they write is yours too.
#
# SAFE TO RE-RUN. Nothing that exists is overwritten: a configuration you edited stays, and every
# generated credential is kept (they live in .env and secrets/). In .env, the ports, the bind
# address, the image tag and the compose project name you set are kept, and so is any other line you
# added; the uid, gid and home are refreshed each run. Credentials are printed only on the run that
# generated them. Every value is read before .env is written, and it is replaced in one rename, so
# a re-run never sees -- or leaves -- a half-written file (ENVRERUN-1).
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
compose="$root/deploy/docker/compose"
env_file="$compose/.env"
home=""
print=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)   home="$2"; shift 2 ;;
    --print)  print=1; shift ;;
    -h|--help) sed -n '5,29p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "docker-env.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done

if [[ "$print" == 1 ]]; then
  [[ -f "$env_file" ]] || { echo "docker-env.sh: no $env_file yet; run without --print" >&2; exit 1; }
  sed -E 's/^((POSTGRES|MYSQL_ROOT|PRAVAHA_CDC|GRAFANA_ADMIN)_PASSWORD|PRAVAHA_SEED_TOKEN)=.*/\1=********/' "$env_file"
  exit 0
fi

if [[ "$(id -u)" == 0 && -z "${PRAVAHA_ALLOW_ROOT:-}" ]]; then
  echo "docker-env.sh: refusing to run as root -- everything it creates would be root's, and so would" >&2
  echo "  everything the containers write. Run it as the user who will own the stack (not through sudo);" >&2
  echo "  the docker commands can use sudo or the docker group separately. PRAVAHA_ALLOW_ROOT=1 overrides." >&2
  exit 2
fi

# An existing .env is where the previous run's choices and credentials are.
existing() { [[ -f "$env_file" ]] && sed -n "s/^$1=//p" "$env_file" | tail -1 || true; }
secret()   { head -c 48 /dev/urandom | base64 | tr -d '/+=\n' | cut -c1-"$1"; }

home="${home:-$(existing PRAVAHA_HOME_DIR)}"
home="${home:-$compose/pravaha-home}"
mkdir -p "$home"
home="$(cd "$home" && pwd)"

umask 077
mkdir -p "$home/conf" "$home/data/console" "$home/logs" "$home/plugins" "$home/tmp" "$home/secrets"
chmod 0700 "$home/secrets" "$home/data"
chmod 0755 "$home/plugins"

new_credentials=""

# ---------------------------------------------------------------- .env
seed_token="$(existing PRAVAHA_SEED_TOKEN)";         seed_token="${seed_token:-$(secret 40)}"
postgres_password="$(existing POSTGRES_PASSWORD)";   postgres_password="${postgres_password:-$(secret 24)}"
mysql_password="$(existing MYSQL_ROOT_PASSWORD)";    mysql_password="${mysql_password:-$(secret 24)}"
cdc_password="$(existing PRAVAHA_CDC_PASSWORD)";     cdc_password="${cdc_password:-$(secret 24)}"
grafana_password="$(existing GRAFANA_ADMIN_PASSWORD)"
if [[ -z "$grafana_password" ]]; then
  grafana_password="$(secret 20)"
  new_credentials+="  grafana (profile observability):   admin / $grafana_password"$'\n'
fi

# ENVRERUN-1: every value is read from the existing .env HERE, before it is written. The heredoc used
# to call port() itself, after `cat > .env` had already truncated the file it reads -- so a re-run put
# every port and the image tag back to the defaults (all but the pgwire port, read up here).
port() { local v; v="$(existing "$1")"; echo "${v:-$2}"; }
project="$(port COMPOSE_PROJECT_NAME pravaha-stack)"
tag="$(port PRAVAHA_TAG local)"
bind="$(port PRAVAHA_BIND 127.0.0.1)"
console_bind="$(port PRAVAHA_CONSOLE_BIND 0.0.0.0)"
http_port="$(port PRAVAHA_HTTP_PORT 18080)"
flight_port="$(port PRAVAHA_FLIGHT_PORT 19090)"
pgwire_port="$(port PRAVAHA_PGWIRE_PORT 15432)"
console_port="$(port PRAVAHA_CONSOLE_PORT 17070)"
kafka_port="$(port PRAVAHA_KAFKA_PORT 29092)"
postgres_port="$(port PRAVAHA_POSTGRES_PORT 25432)"
mysql_port="$(port PRAVAHA_MYSQL_PORT 23306)"
aerospike_port="$(port PRAVAHA_AEROSPIKE_PORT 23100)"
cassandra_port="$(port PRAVAHA_CASSANDRA_PORT 29042)"
prometheus_port="$(port PRAVAHA_PROMETHEUS_PORT 29190)"
grafana_port="$(port PRAVAHA_GRAFANA_PORT 23030)"

# Lines you added that this script does not write (COMPOSE_PROFILES, say) are carried over as they are.
managed='COMPOSE_PROJECT_NAME|PRAVAHA_UID|PRAVAHA_GID|PRAVAHA_HOME_DIR|PRAVAHA_SEED_TOKEN|POSTGRES_PASSWORD'
managed+='|MYSQL_ROOT_PASSWORD|PRAVAHA_CDC_PASSWORD|GRAFANA_ADMIN_PASSWORD|PRAVAHA_TAG|PRAVAHA_BIND|PRAVAHA_CONSOLE_BIND'
managed+='|PRAVAHA_(HTTP|FLIGHT|PGWIRE|CONSOLE|KAFKA|POSTGRES|MYSQL|AEROSPIKE|CASSANDRA|PROMETHEUS|GRAFANA)_PORT'
yours=""
if [[ -f "$env_file" ]]; then
  yours="$(grep -E '^[A-Za-z_][A-Za-z0-9_]*=' "$env_file" | grep -Ev "^($managed)=" || true)"
fi

next_env="$(mktemp "$env_file.XXXXXX")"
trap 'rm -f "$next_env"' EXIT
cat > "$next_env" <<EOF
# Written by tools/docker-env.sh -- re-run it rather than editing the first block; the ports, the bind
# address, the image tag and the project name below are yours to change, and a re-run keeps them.
# Holds credentials: mode 0600, and never committed (.gitignore).
COMPOSE_PROJECT_NAME=$project
PRAVAHA_UID=$(id -u)
PRAVAHA_GID=$(id -g)
PRAVAHA_HOME_DIR=$home

PRAVAHA_SEED_TOKEN=$seed_token
POSTGRES_PASSWORD=$postgres_password
MYSQL_ROOT_PASSWORD=$mysql_password
PRAVAHA_CDC_PASSWORD=$cdc_password
GRAFANA_ADMIN_PASSWORD=$grafana_password

PRAVAHA_TAG=$tag
PRAVAHA_BIND=$bind
PRAVAHA_CONSOLE_BIND=$console_bind
PRAVAHA_HTTP_PORT=$http_port
PRAVAHA_FLIGHT_PORT=$flight_port
PRAVAHA_PGWIRE_PORT=$pgwire_port
PRAVAHA_CONSOLE_PORT=$console_port
PRAVAHA_KAFKA_PORT=$kafka_port
PRAVAHA_POSTGRES_PORT=$postgres_port
PRAVAHA_MYSQL_PORT=$mysql_port
PRAVAHA_AEROSPIKE_PORT=$aerospike_port
PRAVAHA_CASSANDRA_PORT=$cassandra_port
PRAVAHA_PROMETHEUS_PORT=$prometheus_port
PRAVAHA_GRAFANA_PORT=$grafana_port
EOF
if [[ -n "$yours" ]]; then
  printf '\n# Yours, kept by tools/docker-env.sh\n%s\n' "$yours" >> "$next_env"
fi
chmod 0600 "$next_env"
mv -f "$next_env" "$env_file"

# ---------------------------------------------------------------- secrets/
if [[ ! -e "$home/secrets/initial-admin-password" ]]; then
  # Meets the engine's password policy (12 characters, 3 classes) by construction.
  admin_password="$(secret 20)-Dk7"
  printf '%s\n' "$admin_password" > "$home/secrets/initial-admin-password"
  new_credentials+="  console sign-in:                   admin / $admin_password"$'\n'
fi
if [[ ! -e "$home/secrets/seed.token" ]]; then
  printf '%s\n' "$seed_token" > "$home/secrets/seed.token"
  new_credentials+="  engine token (id seed), for the CLI: $home/secrets/seed.token"$'\n'
fi
prometheus_token=""
if [[ -e "$home/secrets/prometheus.token" ]]; then
  prometheus_token="$(tr -d '\n' < "$home/secrets/prometheus.token")"
else
  prometheus_token="$(secret 40)"
  printf '%s\n' "$prometheus_token" > "$home/secrets/prometheus.token"
fi
# Read by the Prometheus container, which runs as its own user: readable by others, while the
# 0700 secrets/ directory keeps everybody else on this host out of it.
chmod 0644 "$home/secrets/prometheus.token"

# ---------------------------------------------------------------- conf/
if [[ ! -e "$home/conf/application.yaml" ]]; then
  sed -e "s/@@SEED_TOKEN@@/$seed_token/" -e "s/@@PROMETHEUS_TOKEN@@/$prometheus_token/" \
      -e "s/@@CDC_PASSWORD@@/$cdc_password/g" \
      "$compose/templates/application.yaml" > "$home/conf/application.yaml"
  echo "docker-env.sh: wrote $home/conf/application.yaml"
else
  echo "docker-env.sh: kept  $home/conf/application.yaml (exists; never overwritten)"
fi
if [[ ! -e "$home/conf/console.yaml" ]]; then
  sed -e "s/@@SESSION_SECRET@@/$(secret 40)/" -e "s/@@PGWIRE_PORT@@/$pgwire_port/" \
      "$compose/templates/console.yaml" > "$home/conf/console.yaml"
  echo "docker-env.sh: wrote $home/conf/console.yaml"
else
  echo "docker-env.sh: kept  $home/conf/console.yaml (exists; never overwritten)"
fi
chmod 0600 "$home/conf/application.yaml" "$home/conf/console.yaml"

echo "docker-env.sh: $env_file (uid $(id -u), gid $(id -g), home $home)"
if [[ -n "$new_credentials" ]]; then
  echo
  echo "New credentials -- shown this once (they are kept in $home/secrets and $env_file):"
  printf '%s' "$new_credentials"
fi
echo
echo "Next:  docker compose -f deploy/docker/compose/docker-compose.yml up -d"
echo "       docker compose -f deploy/docker/compose/docker-compose.yml --profile seed up -d"
