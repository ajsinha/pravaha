#!/usr/bin/env bash
#
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Install Pravaha on one Linux host with Docker: the engine and the console, two images, and their
# two configuration files under /opt/pravaha.
#
#   sudo ./install.sh                       # from the unpacked QA bundle
#   sudo ./install.sh --host qa-vm.example  # the name browsers and error links should use
#   ./install.sh --home "$HOME/pravaha"     # anywhere else, without root
#
# Then:  cd /opt/pravaha && docker compose up -d
#
# What it lays down, every path the product touches:
#
#   /opt/pravaha/docker-compose.yml
#   /opt/pravaha/conf/application.yaml           the engine's configuration   (0600, uid 10001)
#   /opt/pravaha/console/conf/application.yaml   the console's configuration  (0600, uid 10001)
#   /opt/pravaha/data/                           journal, checkpoints, dead letters, spill
#   /opt/pravaha/logs/                           the engine's log and the audit trail
#   /opt/pravaha/feeds/                          files you drop for file sources: yours to write, read-only
#                                                to the engine
#
# SAFE TO RE-RUN. A configuration file that exists is never overwritten, so the edits made to it
# survive an upgrade: a new bundle brings new images and a new compose file, and your two YAML
# files stay yours. Credentials are generated only when the file that holds them is first written,
# and are printed once, at the end of that run.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
home="/opt/pravaha"
host="$(hostname -f 2>/dev/null || hostname)"
docker_bin="${DOCKER:-docker}"
version="$(cat "$here/VERSION" 2>/dev/null || echo "@@VERSION@@")"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --home)    home="$2"; shift 2 ;;
    --host)    host="$2"; shift 2 ;;
    --version) version="$2"; shift 2 ;;
    -h|--help) sed -n '5,25p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "install.sh: unknown argument '$1'" >&2; exit 2 ;;
  esac
done
[[ "$version" != "@@VERSION@@" ]] || { echo "install.sh: no VERSION file beside me; pass --version" >&2; exit 2; }

secret() { head -c 32 /dev/urandom | base64 | tr -d '/+=\n' | cut -c1-"$1"; }

# ---------------------------------------------------------------- images
shopt -s nullglob
for tar in "$here"/images/*.tar; do
  echo "install.sh: loading $(basename "$tar")"
  "$docker_bin" load -i "$tar" >/dev/null
done
for image in "pravaha/pravaha-server:$version" "pravaha/pravaha-console:$version"; do
  "$docker_bin" image inspect "$image" >/dev/null 2>&1 \
    || { echo "install.sh: image $image is not loaded, and no images/*.tar here provides it" >&2; exit 1; }
done

# ---------------------------------------------------------------- the tree
mkdir -p "$home/conf" "$home/console/conf" "$home/logs"
if [[ ! -d "$home/feeds" ]]; then
  # Owned by whoever ran the installer -- through sudo, the person behind it -- so they can write
  # their files there without root; world-readable, because the engine reads it as uid 10001.
  mkdir -p "$home/feeds"
  owner="${SUDO_USER:-$(id -un)}"
  chown "$owner" "$home/feeds" 2>/dev/null || true
  chmod 0755 "$home/feeds"
fi
# data/ is the engine's once it has run (0750, uid 10001), so it is seeded only when it is new: the
# one stream the configuration declares, with the six rows docs/PYTHON_API_GUIDE.md starts from.
if [[ ! -d "$home/data" ]]; then
  mkdir -p "$home/data/incoming" "$home/data/outgoing"
  cat > "$home/data/incoming/txn.csv" <<'CSV'
txn_id,user_id,merchant,amount,currency,status,event_time
1,u1,acme,150,USD,OK,2026-09-26T09:00:01Z
2,u2,globex,90,USD,OK,2026-09-26T09:00:05Z
3,u1,acme,3000,USD,OK,2026-09-26T09:00:30Z
4,u3,initech,12000,EUR,,2026-09-26T09:01:10Z
5,u2,globex,5000,USD,OK,2026-09-26T09:01:20Z
6,u1,acme,7000,USD,OK,2026-09-26T09:01:25Z
CSV
fi
sed "s/@@VERSION@@/$version/g" "$here/docker-compose.yml" > "$home/docker-compose.yml"
echo "$version" > "$home/VERSION"
if [[ "$home" != "/opt/pravaha" ]]; then
  echo "PRAVAHA_HOME=$home" > "$home/.env"
fi

new_credentials=""
console_token=""

if [[ ! -e "$home/conf/application.yaml" ]]; then
  console_token="$(secret 40)"
  qa_token="$(secret 40)"
  sed -e "s/@@CONSOLE_TOKEN@@/$console_token/" -e "s/@@QA_TOKEN@@/$qa_token/" -e "s/@@HOST@@/$host/g" \
      "$here/server.application.yaml" > "$home/conf/application.yaml"
  new_credentials+="  engine token for the CLI and SDKs (id qa):  $qa_token"$'\n'
  echo "install.sh: wrote $home/conf/application.yaml"
else
  echo "install.sh: kept  $home/conf/application.yaml (exists; never overwritten)"
fi

if [[ ! -e "$home/console/conf/application.yaml" ]]; then
  if [[ -z "$console_token" ]]; then
    # The engine's file was kept, so the console's credential is whatever that file already says.
    console_token="$(awk '/^ *"[^"]+":$/ { key=$1 } /id: console/ { gsub(/[":]/, "", key); print key; exit }' "$home/conf/application.yaml")"
    [[ -n "$console_token" ]] || { echo "install.sh: no 'id: console' token in $home/conf/application.yaml to give the console" >&2; exit 1; }
  fi
  password="$(secret 20)"
  sed -e "s/@@CONSOLE_PASSWORD@@/$password/" -e "s/@@SESSION_SECRET@@/$(secret 40)/" \
      -e "s/@@CONSOLE_TOKEN@@/$console_token/" -e "s/@@HOST@@/$host/g" \
      "$here/console.application.yaml" > "$home/console/conf/application.yaml"
  new_credentials+="  console password:                           $password"$'\n'
  echo "install.sh: wrote $home/console/conf/application.yaml"
else
  echo "install.sh: kept  $home/console/conf/application.yaml (exists; never overwritten)"
fi

# ---------------------------------------------------------------- ownership
# Both containers run as uid 10001. The configurations hold credentials: 0600, theirs. data/ and
# logs/ are the engine's to write. Without root, one throwaway container does the chown -- the
# same trick deploy/docker/smoke.sh uses -- over this tree and nothing else.
own='chmod 0600 "$1/conf/application.yaml" "$1/console/conf/application.yaml"
     chmod 0750 "$1/data" "$1/logs"
     chown -R 10001:10001 "$1/conf" "$1/console/conf" "$1/data" "$1/logs"'
if [[ "$(id -u)" == 0 ]]; then
  sh -c "$own" own "$home"
else
  "$docker_bin" run --rm --user 0 --entrypoint /bin/sh -v "$home:/qa-home" "pravaha/pravaha-server:$version" \
    -c "$own" own /qa-home
fi

echo
echo "install.sh: Pravaha $version installed in $home"
if [[ -n "$new_credentials" ]]; then
  echo
  echo "New credentials -- shown this once, and kept in the two configuration files:"
  printf '%s' "$new_credentials"
fi
echo
echo "Start it:   cd $home && docker compose up -d"
echo "Console:    http://$host:17070"
echo "Engine:     http://$host:18080  (HTTP)   grpc://$host:19090  (Flight SQL)"
echo "Feeds:      $home/feeds/ -- drop files for file sources here; the engine reads them as /opt/pravaha/feeds"
echo "Configure:  $home/conf/application.yaml, $home/console/conf/application.yaml -- then docker compose restart"
