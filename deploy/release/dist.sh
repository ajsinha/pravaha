#!/usr/bin/env bash
# Project Pravaha -- Ask once. Answer always.
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
# Proprietary and confidential; see the LICENSE file in the root of this repository.
#
# Assemble the engine as a DISTRIBUTION: a directory, and a tarball of it, that is a PRAVAHA_HOME
# the moment it is unpacked -- the same layout the container image has under /opt/pravaha, for a
# machine that runs the node without Docker (docs/DEPLOYMENT.md, "Without Docker").
#
#   ./mvnw -o -pl pravaha-server,pravaha-cli -am package -DskipTests
#   deploy/release/dist.sh                   # -> target/dist/pravaha-<version>/ and .tar.gz
#
#   tar xzf pravaha-<version>.tar.gz -C /opt && mv /opt/pravaha-<version> /opt/pravaha
#   /opt/pravaha/bin/pravaha-server          # PRAVAHA_HOME is /opt/pravaha, found from bin/
#
# What is in it:
#
#   bin/pravaha-server  bin/pravaha-engine      the launchers
#   lib/pravaha-server.jar  lib/pravaha-engine.jar
#   conf/application.yaml.example               a starting point; copy it to conf/application.yaml
#   conf/pravaha-server.service                 a systemd unit for /opt/pravaha
#   data/ logs/ plugins/ tmp/ secrets/          empty; the node writes the first three
#   LICENSE  VERSION
#
# Like deploy/docker/build.sh it does NOT run Maven: a missing jar is an error naming the command.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
version="$("$root/deploy/release/version.sh" "$root")"
out="$root/target/dist"
name="pravaha-$version"

server_jar="$(ls -1 "$root"/pravaha-server/target/pravaha-server-*-app.jar 2>/dev/null | head -1 || true)"
engine_jar="$(ls -1 "$root"/pravaha-cli/target/pravaha-cli-*-cli.jar 2>/dev/null | head -1 || true)"
if [[ -z "$server_jar" || -z "$engine_jar" ]]; then
  echo "dist.sh: the jars are not built. Build them with:" >&2
  echo "    ./mvnw -o -pl pravaha-server,pravaha-cli -am package -DskipTests" >&2
  exit 1
fi

rm -rf "${out:?}/$name" "$out/$name.tar.gz"
home="$out/$name"
mkdir -p "$home/bin" "$home/lib" "$home/conf" "$home/data" "$home/logs" "$home/plugins" "$home/tmp"
mkdir -m 0700 "$home/secrets"
chmod 0700 "$home/data"

install -m 0755 "$root/bin/pravaha-server" "$root/bin/pravaha-engine" "$home/bin/"
install -m 0644 "$server_jar" "$home/lib/pravaha-server.jar"
install -m 0644 "$engine_jar" "$home/lib/pravaha-engine.jar"
install -m 0644 "$root/deploy/release/distribution/application.yaml.example" "$home/conf/application.yaml.example"
install -m 0644 "$root/deploy/release/distribution/pravaha-server.service" "$home/conf/pravaha-server.service"
install -m 0644 "$root/LICENSE" "$home/LICENSE"
printf '%s\n' "$version" > "$home/VERSION"

tar -C "$out" -czf "$out/$name.tar.gz" "$name"
echo "dist.sh: $home"
echo "dist.sh: $out/$name.tar.gz ($(du -h "$out/$name.tar.gz" | cut -f1))"
