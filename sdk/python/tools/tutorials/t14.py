"""Tutorial 14 -- Running in production (docs/tutorials/14-running-in-production.md).

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
PROPRIETARY AND CONFIDENTIAL. See the LICENSE file for the full terms.

A node from a PRAVAHA_HOME set up as production runs one: a test CA and a server certificate made in
the home, Flight and the PostgreSQL gateway on TLS (checked with openssl, the CLI and psql, and the
plaintext and the untrusted refused), people and an API key beside the captive development account,
checkpoints, a backup taken while the node is stopped and a restart that keeps the answer, `pravaha
doctor`; then the engine's released image run on the same kind of home outside the dev profile (the
first administrator's password from a file, a captive service account, a thread dump by SIGQUIT), and
the Helm chart rendered with production values.
"""

import os

from tutorial_captures import REPO, Cli, Container, Node, Open, Restart, Sh, Snap, Until, Up

DOC = "14-running-in-production.md"

#: The engine's image, built from this checkout by deploy/docker/build.sh; started on a free port, removed after.
IMAGE = "pravaha/pravaha-server:tutorial-14"

ENGINE = Container(
    name="pravaha-node", image=IMAGE, port=28080, inside=18080, later=True,
    env=(("SPRING_PROFILES_ACTIVE", "users"),),
    options=("--user", f"{os.getuid()}:{os.getgid()}", "--read-only", "--tmpfs", "/tmp:rw,mode=1777",
             "-v", "{home}/docker/conf:/opt/pravaha/conf:ro", "-v", "{home}/docker/secrets:/opt/pravaha/secrets:ro",
             "-v", "{home}/docker/data:/opt/pravaha/data", "-v", "{home}/docker/logs:/opt/pravaha/logs",
             "-v", "{home}/docker/tmp:/opt/pravaha/tmp"),
    ready="curl -sf http://127.0.0.1:{port}/actuator/health/readiness",
    timeout=180,
)

NODE = Node(config="t14/application.yaml", pgwire=True, flight_ca="conf/tls/ca.pem", containers=(ENGINE,), files=(
    ("t14/orders.csv", "data/incoming/orders.csv"),
    ("t14/region_revenue.sql", "region_revenue.sql"),
    ("t14/make-certs.sh", "tutorial/make-certs.sh"),
    ("t14/docker/application.yaml", "tutorial/docker-application.yaml"),
    ("t14/values-prod.yaml", "tutorial/values-prod.yaml"),
))

#: The console reaches a TLS engine through gRPC's own trust setting: it has none of its own (CONSOLETLS-1).
CONSOLE_ENV = {"GRPC_DEFAULT_SSL_ROOTS_FILE_PATH": "{home}/conf/tls/ca.pem"}

#: A person's roles come in no fixed order (ROLESORDER-1): shown in one.
ROLES = (r"\boperator,admin\b", "admin,operator")

#: The node's Flight port, from the URL the reader exported.
FLIGHT = "$(echo {url} | sed 's/.*://')"
PSQL = "psql \"host=localhost port={pg} dbname=pravaha user=reporting sslmode=verify-full sslrootcert=conf/tls/ca.pem\""
PSQL_SHOWN = ("psql \"host=localhost port=5433 dbname=pravaha user=reporting sslmode=verify-full "
              "sslrootcert=$PRAVAHA_HOME/conf/tls/ca.pem\"")
READ = "SELECT region, window_end, revenue, orders FROM region_revenue"
DOCKER_HTTP = "http://localhost:{pravaha-node.port}"
ADMIN_PASSWORD = "Tutorial-admin-2026"
INGEST_PASSWORD = "Tutorial-ingest-2026"

BEFORE_START = (
    Sh("t14-certs", "bash tutorial/make-certs.sh conf/tls", show='bash make-certs.sh "$PRAVAHA_HOME/conf/tls"'),
    Sh("t14-cert", "openssl x509 -in conf/tls/server.pem -noout -subject -issuer -ext subjectAltName",
       show='openssl x509 -in "$PRAVAHA_HOME/conf/tls/server.pem" -noout -subject -issuer -ext subjectAltName'),
)

STEPS = (
    Sh("t14-home", "ls -d conf conf/tls data data/* logs", show='cd "$PRAVAHA_HOME" && ls -d conf conf/tls data data/* logs'),
    Sh("t14-started", "grep -oE '(Flight SQL listening on|PostgreSQL wire protocol listening on) [^(]*.*' logs/pravaha-server.log "
                      "| sed 's/127.0.0.1:[0-9]*/127.0.0.1:<port>/'",
       show="grep -oE '(Flight SQL listening on|PostgreSQL wire protocol listening on) [^(]*.*' "
            "\"$PRAVAHA_HOME/logs/pravaha-server.log\""),
    Sh("t14-openssl", f"openssl s_client -connect localhost:{FLIGHT} -CAfile conf/tls/ca.pem -servername localhost "
                      "</dev/null 2>/dev/null | grep -E '^(subject|issuer)=|Verify return code'",
       show="openssl s_client -connect localhost:19090 -CAfile \"$PRAVAHA_HOME/conf/tls/ca.pem\" -servername localhost "
            "</dev/null 2>/dev/null | grep -E '^(subject|issuer)=|Verify return code'"),
    Cli("t14-register", ("register", "--name", "region_revenue", "--sql-file", "region_revenue.sql", "--keys", "0,2")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM region_revenue"), r"^3\s*$"),
    Cli("t14-query", ("query", "--sql", READ)),
    # gRPC's own native log lines (a timestamp, a thread id) are on stderr too: left out.
    Sh("t14-no-ca", f"PRAVAHA_TLS_CA= pravaha query --sql '{READ}'",
       show=f"PRAVAHA_TLS_CA= pravaha query --sql '{READ}'", omit=(r"^(WARNING: All log messages|I0000 )",)),
    Sh("t14-plaintext", f"pravaha --url grpc://localhost:{FLIGHT} query --sql '{READ}'",
       show=f"pravaha --url grpc://localhost:19090 query --sql '{READ}'"),
    # People and keys.
    Sh("t14-user", "printf '%s\\n' 'Tutorial-oncall-2026' | pravaha user create oncall --roles operator --password-stdin"),
    Cli("t14-users", ("user", "list"), subs=((r"\d{4}-\d\d-\d\dT[\d:.]+Z", "<time>"), ROLES)),
    Sh("t14-key", "pravaha key create reporting --days 30 | tee reporting.key", show="pravaha key create reporting --days 30"),
    Sh("t14-psql", f"PGPASSWORD=$(tail -n 1 reporting.key) {PSQL} -c '{READ}'",
       show=f"PGPASSWORD=\"$KEY\" {PSQL_SHOWN} -c '{READ}'"),
    Sh("t14-psql-plain", f"PGPASSWORD=$(tail -n 1 reporting.key) psql \"host=localhost port={{pg}} dbname=pravaha "
                         f"user=reporting sslmode=disable\" -c '{READ}'",
       show=f"PGPASSWORD=\"$KEY\" psql \"host=localhost port=5433 dbname=pravaha user=reporting sslmode=disable\" -c '{READ}'"),
    Open("/admin/users"),
    Snap("01-users"),
    Open("/admin/keys"),
    Snap("02-keys"),
    # Checkpoints, a backup with the node stopped, a restart.
    Until(("debug", "checkpoints", "--name", "region_revenue"), r"^\d+\s"),
    Sh("t14-checkpoints", "pravaha debug checkpoints --name region_revenue | head -n 1", subs=((r"^\d+$", "N"),)),
    Restart(during=Sh("t14-backup", "tar czf pravaha-backup.tgz conf data && tar tzf pravaha-backup.tgz | grep -v '/$' "
                                    "| sed -E 's/checkpoint-[0-9]+/checkpoint-N/' | sort -u",
                      show='cd "$PRAVAHA_HOME" && tar czf pravaha-backup.tgz conf data && tar tzf pravaha-backup.tgz '
                           "| grep -v '/$'")),
    Until(("query", "--sql", "SELECT COUNT(*) AS n FROM region_revenue"), r"^3\s*$"),
    Cli("t14-after", ("query", "--sql", READ)),
    Cli("t14-doctor", ("doctor",), omit=(r"^\S+\s+port \d+\s",), subs=((r"expires \d{4}-\d\d-\d\d$", "expires <date>"),)),
    Open("/queries/region_revenue"),
    Snap("03-query"),
    # The image.
    Sh("t14-build", f"cd {REPO} && deploy/docker/build.sh --tag {IMAGE} 2>&1 | tail -n 3",
       show=f"deploy/docker/build.sh --tag {IMAGE} | tail -n 3", subs=((r"size  \d+ bytes", "size  N bytes"),)),
    Sh("t14-docker-home", "mkdir -p docker/conf/tls docker/secrets docker/data/incoming docker/logs docker/tmp "
                          "&& cp tutorial/docker-application.yaml docker/conf/application.yaml "
                          "&& cp conf/tls/server.pem conf/tls/server-key.pem docker/conf/tls/ "
                          "&& cp data/incoming/orders.csv docker/data/incoming/ "
                          f"&& printf '%s' '{ADMIN_PASSWORD}' > docker/secrets/initial-admin-password "
                          f"&& printf '%s' '{INGEST_PASSWORD}' > docker/secrets/ingest-password "
                          "&& chmod 600 docker/secrets/* && find docker -type f | sort",
       show="find docker -type f | sort"),
    Up("pravaha-node"),
    Sh("t14-docker-ps", "docker ps --filter name={pravaha-node} --format '{{.Names}}  {{.Image}}  {{.Ports}}'",
       show="docker ps --filter name=pravaha-node --format '{{.Names}}  {{.Image}}  {{.Ports}}'"),
    Sh("t14-docker-ready", f"curl -s {DOCKER_HTTP}/actuator/health/readiness",
       show="curl -s http://localhost:28080/actuator/health/readiness"),
    Sh("t14-docker-logs", "docker logs {pravaha-node} 2>&1 | grep -oE '(security|identity): .*|(Flight SQL|PostgreSQL wire "
                          "protocol) listening on .*'",
       show="docker logs pravaha-node 2>&1 | grep -oE '(security|identity): .*|(Flight SQL|PostgreSQL wire protocol) "
            "listening on .*'"),
    Sh("t14-docker-login", f"printf '%s\\n' '{ADMIN_PASSWORD}' | pravaha --http {DOCKER_HTTP} login --user admin "
                           "--password-stdin 2>&1 > docker.token",
       show="ADMIN=$(pravaha --http http://localhost:28080 login --user admin)"),
    Sh("t14-docker-whoami", f"PRAVAHA_TOKEN=$(cat docker.token) pravaha --http {DOCKER_HTTP} whoami",
       show="PRAVAHA_TOKEN=$ADMIN pravaha --http http://localhost:28080 whoami",
       subs=((r"password expires\s+\S+", "password expires  <time>"), ROLES)),
    Sh("t14-docker-users", f"PRAVAHA_TOKEN=$(cat docker.token) pravaha --http {DOCKER_HTTP} user list",
       show="PRAVAHA_TOKEN=$ADMIN pravaha --http http://localhost:28080 user list",
       subs=((r"\d{4}-\d\d-\d\dT[\d:.]+Z", "<time>"), ROLES)),
    Sh("t14-docker-dump", "docker kill --signal=QUIT {pravaha-node} >/dev/null && sleep 2 && docker logs {pravaha-node} 2>&1 "
                          "| grep -m1 -A1 '^Full thread dump'",
       show="docker kill --signal=QUIT pravaha-node && docker logs pravaha-node 2>&1 | grep -m1 -A1 '^Full thread dump'",
       subs=((r"\(\d[^)]*\)", "(<version>)"), (r"^\d{4}-\d\d-\d\d \d\d:\d\d:\d\d$", "<time>"))),
    # Long enough after the restart for the per-query meters (every 15 s) to be published again.
    Open("/operations"),
    Snap("04-operations"),
    # The Helm chart.
    Sh("t14-helm", f"helm template prod {REPO}/deploy/helm/pravaha -f tutorial/values-prod.yaml | grep -E '^kind:' "
                   "| sort | uniq -c",
       show="helm template prod deploy/helm/pravaha -f values-prod.yaml | grep -E '^kind:' | sort | uniq -c"),
    Sh("t14-helm-tls", f"helm template prod {REPO}/deploy/helm/pravaha -f tutorial/values-prod.yaml "
                       "| grep -E 'certificate:|key: /opt|secretName:|image:'",
       show="helm template prod deploy/helm/pravaha -f values-prod.yaml | grep -E 'certificate:|key: /opt|secretName:|image:'"),
)
