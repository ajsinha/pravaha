# Running Pravaha in Docker

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

The reference for Pravaha in containers: the two images, the one directory everything lives under,
the compose stack and its profiles, the ports, ownership, backup, upgrade and what goes wrong.
For a numbered walk from a fresh clone to a running, tested stack, read
[`../development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md`](../development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md); for the same without
Docker, [`../development/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md`](../development/GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md). Shipping a
node to Kubernetes, the image's design decisions and the release are in
[`DEPLOYMENT.md`](DEPLOYMENT.md); running one, in [`OPERATIONS.md`](OPERATIONS.md).

Everything below was run on the development machine on 2026-09-29 (Docker 29.8.1, Compose 5.5.1,
buildx 0.37.1), and again on 2026-10-01 on the Java 25 images (Docker 29.8.2) — the smoke run, the
compose journey with the `seed`, `cdc` and `observability` profiles, the restart and recreate, and the
ownership and `docker diff` checks all printed what is shown. Outputs are trimmed only where marked.

---

## The short version

```bash
deploy/docker/build.sh --tag pravaha/pravaha-server:local            # needs the server jar built
deploy/docker/console/build.sh --tag pravaha/pravaha-console:local
tools/docker-env.sh                                                  # .env + pravaha-home/, as you
docker compose -f deploy/docker/compose/docker-compose.yml --profile seed up -d
```

Then the console is at <http://localhost:17070> (sign in as `admin`, with the password
`docker-env.sh` printed), Flight SQL at `grpc://localhost:19090`, the REST API at
<http://localhost:18080>, and psql reaches the views at `localhost:15432`.

---

## One root: PRAVAHA_HOME

A Pravaha node, and the console beside it, keep **everything** under one directory, `PRAVAHA_HOME`.
In both images it is **`/opt/pravaha`**. Outside a container it is wherever you put it — an unpacked
distribution finds its own ([`DEPLOYMENT.md`](DEPLOYMENT.md), "Without Docker: the same layout").
The layout is the same in both places, and so is the configuration: every path in the compose
stack's engine configuration is relative, because the node's working directory is the home.

`bin/pravaha-server` does it. With `PRAVAHA_HOME` set, or `lib/pravaha-server.jar` beside `bin/`,
it creates the directories below that are missing (as the user it runs as, `umask 077`), makes the
home the working directory, and starts the JVM with:

| Flag | Why |
|---|---|
| `-Djava.io.tmpdir=$PRAVAHA_HOME/tmp` | the Parquet and Kafka native codecs unpack here, and Tomcat keeps its work directory here |
| `-Duser.home=$PRAVAHA_HOME/tmp` | an arbitrary `--user` has no passwd entry, and the JVM then calls the home `?` — a library writing under it made a directory named `?` |
| `-XX:+PerfDisableSharedMem` | HotSpot's counters are a file in `/tmp/hsperfdata_<user>` whatever `tmpdir` says; kept in memory instead. `jcmd` still works |
| `-XX:HeapDumpPath=$PRAVAHA_HOME/logs`, `-XX:ErrorFile=$PRAVAHA_HOME/logs/hs_err_pid%p.log` | a heap dump (add `-XX:+HeapDumpOnOutOfMemoryError` to `PRAVAHA_JAVA_OPTS`) and a crash file land with the logs |
| `-Dloader.path=$PRAVAHA_HOME/plugins` | jars dropped in `plugins/` are on the classpath at the next start |
| `-Dspring.config.additional-location=optional:classpath:/pravaha-home.yaml,optional:file:$PRAVAHA_HOME/conf/` | the layout, then your configuration, which wins |

`PRAVAHA_JAVA_OPTS` comes after all of these, so any of them can be overridden. Without
`PRAVAHA_HOME`, from a source checkout, none of it happens and the node behaves as it always did.

### The layout

| Path under `PRAVAHA_HOME` | Holds | Written by | Setting that places it |
|---|---|---|---|
| `bin/` | `pravaha-server` (and `pravaha-engine` in the source-built image) | the image / distribution | — |
| `lib/` | `pravaha-server.jar` (`pravaha-engine.jar`) | the image / distribution | — |
| `conf/application.yaml` | **the engine's configuration** (the deployment's) | you | read from `conf/` by the launcher |
| `conf/console.yaml` | **the console's configuration** | you | the console image's `--config` list |
| `conf/application-<profile>.yaml` | profile overlays, if you use any | you | Spring Boot, from `conf/` |
| `secrets/` (0700) | `initial-admin-password`, tokens, TLS keys, keystores | you | referenced from `conf/` (e.g. `secrets/initial-admin-password`) |
| `plugins/` | extra jars for the engine's classpath (a JDBC driver, a plugin) | you | `loader.path` |
| `data/registry.journal` | the registered continuous queries | the engine | `pravaha.registry.journal` (layout) |
| `data/checkpoints/<query>/` | each query's checkpoints | the engine | `pravaha.checkpoint.directory` (layout) |
| `data/identity/identity.journal` | users, password and key hashes, sessions | the engine | `pravaha.identity.store` (layout) |
| `data/catalog.journal` | the catalogue's grants (ADR-059) | the engine | beside the registry journal |
| `data/alerts.journal` | alert decisions (ADR-057) | the engine | beside the registry journal |
| `data/dlq/` | dead letters, one file per query | the engine | `pravaha.dlq.directory` — **yours to set**: it switches the queue on |
| `data/spill/` | spilled state | the engine | `pravaha.state.spill.directory` — **yours to set**: it switches spilling on |
| `data/.pravaha-owner` | the node's claim on its state | the engine | — |
| `data/console/` | the assistant's `assist.json`, usage ledger and log | the console | `PRAVAHA_CONFIG_DIR` (console image) |
| `logs/pravaha-server.log` | the engine's log, rolled at 50 MB, 10 kept, 1 GB cap | the engine | `logging.file.name` (layout) |
| `logs/audit.jsonl` | the audit trail, when `pravaha.security.audit: file` | the engine | `pravaha.security.audit-file` (layout) |
| `logs/pravaha-console.log` | the console's log, rolled at 50 MB, 10 kept | the console | `logging.file` / `CONSOLE_LOG_FILE` |
| `logs/java_pid*.hprof`, `logs/hs_err_pid*.log` | heap dumps, JVM crash files | the JVM | the launcher's flags |
| `tmp/` | `java.io.tmpdir`, `user.home`, the console's `TMPDIR` and `HOME` | both | the launcher; the console image's environment |

The layout itself — the second row of configuration, between the jar's defaults and yours — is
[`pravaha-server/src/main/resources/pravaha-home.yaml`](../../pravaha-server/src/main/resources/pravaha-home.yaml)
inside the server jar. It sets **only places**. A key whose value changes what the engine *does*
(`dlq.directory`, `state.spill.directory`, anything under `security`) is left to your file, even
though the layout has a directory ready for it. `PravahaHomeLayoutTest` fails the build if a new
path-valued setting appears that is neither placed under the home nor deliberately left out.

Precedence, lowest first: the jar's `application.yaml` → the jar's `pravaha-home.yaml` →
`$PRAVAHA_HOME/conf/application.yaml` → profile files (`application-<profile>.yaml`, which Spring
always ranks above the plain ones) → `PRAVAHA_*`/`SPRING_*` environment variables → `--key=value`
arguments.

---

## The images

| | Engine: `pravaha/pravaha-server` | Console: `pravaha/pravaha-console` |
|---|---|---|
| Dockerfile | [`deploy/docker/Dockerfile`](../../deploy/docker/Dockerfile) (release, over a built jar); [`Dockerfile`](../../Dockerfile) at the root builds from source | [`deploy/docker/console/Dockerfile`](../../deploy/docker/console/Dockerfile) |
| Built by | [`deploy/docker/build.sh`](../../deploy/docker/build.sh) | [`deploy/docker/console/build.sh`](../../deploy/docker/console/build.sh) |
| Base | `eclipse-temurin:25-jre` (glibc, ADR-053), the only JRE (ADR-061) | `python:3.13-slim` |
| Size, as built here | **822 MB** on disk, 282 MB content (the jar is 176 MB); **881 MB** / 310 MB from the root `Dockerfile`, which adds `pravaha-engine` | **497 MB** on disk, 122 MB content |
| User | `10001:10001` by default; **any uid** works (below) | the same |
| Entrypoint / command | `/__cacert_entrypoint.sh bin/pravaha-server` | `python run_pravaha_web.py --config <three files>` |
| Ports | 18080 HTTP, 19090 Flight SQL, 5432 pgwire when enabled | 17070 |
| Volumes | `/opt/pravaha/data`, `/opt/pravaha/logs` | `/opt/pravaha/data/console`, `/opt/pravaha/logs` |
| Healthcheck | `bin/pravaha-health /actuator/health/liveness` every 30s (bash only: the 25 JRE has no `wget` or `curl`) | `GET :17070/health/live` every 30s |
| Labels | `org.opencontainers.image.{title,description,version,revision,created,authors,licenses,source}`, and `com.ash.messaging.pravaha.java` (the JRE: `25`) | the OCI ones |

**The JRE is 25, and only 25.** Every Docker build is Java 25: the engine image runs on
`eclipse-temurin:25-jre`, the root Dockerfile compiles in `maven:3.9-eclipse-temurin-25`, and the test
runner (`tools/docker-test.sh`) is `maven:3.9-eclipse-temurin-25`. From 2.0 the jar's classes are
Java 25 class files ([ADR-061](../design/adr/061-jdk-25-is-the-baseline-from-2-0.md)), so 25 is a
requirement, and the 1.x `--java 21` option, its `-jre21` tag and the `JAVA_VERSION` build argument
are gone (`build.sh --java` is refused by name). The launcher gives the JVM
`--sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED` (Arrow's allocator, Netty
and the Parquet codecs use exactly what those options permit), so the node starts with no JVM
warning. The image's JRE is recorded in the label `com.ash.messaging.pravaha.java`:

```text
$ docker image inspect pravaha/pravaha-server:1.0.1-SNAPSHOT --format '{{index .Config.Labels "com.ash.messaging.pravaha.java"}}'
25
$ docker logs pravaha-stack-pravaha-server-1 2>&1 | grep 'using Java'
... Starting PravahaServerApplication v1.0.1-SNAPSHOT using Java 25.0.4.1 with PID 1 (/opt/pravaha/lib/pravaha-server.jar ...)
```

The image passes `deploy/docker/smoke.sh`, every check, read-only root included.

**Which engine Dockerfile.** `deploy/docker/build.sh` stages the launcher and a jar you already built
(`./mvnw -pl pravaha-server -am package -DskipTests`) into an ~190 MB context and builds that: fast,
and the image holds exactly the artefact you tested ([ADR-047](../design/adr/047-the-image-is-a-dockerfile-over-built-artefacts.md)).
It needs a JDK 25 on the host to produce the jar. The root `Dockerfile` needs **nothing but Docker**:
it builds `pravaha-server` and `pravaha-cli` inside a `maven:3.9-eclipse-temurin-25` stage (with a
BuildKit cache for `~/.m2`) and produces the same runtime layout, plus `bin/pravaha-engine`:

```bash
docker build -t pravaha/pravaha-server:src .
```

The console image is always built from source; its build needs Docker and network access to PyPI.
It carries the Python SDK, so it also has the **`pravaha` command line** — the compose stack uses it
for the seed job and the `cli` service, so no Python is needed on the host.

**No dev dependencies.** The console image installs the console and the SDK with `[flight]` and
nothing else — no pytest, ruff or mypy. The engine image has no build tools, no shell account and no
Python.

### Any uid, and who owns what

Both images run as uid 10001 unless told otherwise, and **neither depends on it**: nothing at runtime
needs root or `chown`s anything, and every file the process creates is created by the process. Run
them as yourself over bind-mounted directories you own and everything they write is yours:

```bash
docker run --user "$(id -u):$(id -g)" -v "$PWD/pravaha-home/data:/opt/pravaha/data" ...
```

The image's own directories under `/opt/pravaha` are owned by `10001:0` and group-writable, so an
orchestrator that assigns an arbitrary uid in group 0 (OpenShift) can write them without mounts too;
named volumes are initialised from them, which is why a **named** volume wants the default uid 10001.
Code (`bin/`, `lib/`, the console's files) is root's and read-only.

Two rules keep a bind-mounted home yours: **create the directories yourself before `docker run` or
`compose up`** (a missing bind-mount source is created by the daemon, as root), and **never run the
helper scripts through `sudo`**. `tools/docker-env.sh` does the first and refuses the second.

### Environment variables

Every engine setting has an environment spelling by Spring's relaxed binding
(`pravaha.flight.port` → `PRAVAHA_FLIGHT_PORT`). The ones worth knowing:

| Variable | Image | Default | What it does |
|---|---|---|---|
| `PRAVAHA_HOME` | engine | `/opt/pravaha` | the root; changing it in the image is possible, never useful |
| `PRAVAHA_JAVA_OPTS` | engine | `-XX:MaxRAMPercentage=50.0 -XX:+ExitOnOutOfMemoryError` | JVM flags, appended after the launcher's; replaces the default wholesale |
| `PRAVAHA_UMASK` | engine | `077` | the umask the node runs with; `027` lets a group read `logs/` |
| `PRAVAHA_NODE_ID` | engine | `pravaha-node-01` | the node's identity, which its state directories are claimed under |
| `SPRING_PROFILES_ACTIVE` | engine | — | `dev` for an open first run; `dev,users` for users with the published admin password |
| `SPRING_CONFIG_ADDITIONAL_LOCATION` | engine | — | replaces the launcher's two locations; name `optional:classpath:/pravaha-home.yaml` first to keep the layout (the Helm chart does) |
| `LOGGING_FILE_NAME` | engine | `/opt/pravaha/logs/pravaha-server.log` | empty: log to stdout only (the chart does, Kubernetes collects stdout) |
| `PRAVAHA_FLIGHT_PORT`, `SERVER_PORT`, `PRAVAHA_PGWIRE_ENABLED` | engine | `19090`, `18080`, `false` | the ports inside the container |
| `USE_SYSTEM_CA_CERTS` | engine | — | imports `/certificates/*.crt` into the JVM's trust store at start (the base image's entrypoint) |
| `PRAVAHA_ENGINE`, `PRAVAHA_ENGINE_HTTP` | console | `grpc://localhost:19090`, `http://localhost:18080` | where the engine is |
| `CONSOLE_SESSION_SECRET` | console | generated at start | signs the session cookie (an opaque id; the engine token stays in the console's memory, so a console restart signs everybody out of the console) |
| `CONSOLE_LOG_FILE` | console | `/opt/pravaha/logs/pravaha-console.log` | the rotated log file; empty for stderr only |
| `CONSOLE_LOG_FORMAT`, `LOG_LEVEL` | console | `text`, `INFO` | `json` for Loki or Elasticsearch |
| `PRAVAHA_CONFIG_DIR` | console | `/opt/pravaha/data/console` | the assistant's configuration, ledger and log (and the CLI's saved token) |
| `HOME`, `TMPDIR` | console | `/opt/pravaha/tmp` | so Python writes nothing elsewhere |
| `PRAVAHA_URL`, `PRAVAHA_TOKEN`, `PRAVAHA_INSECURE_TOKEN` | console (as CLI) | — | the `pravaha` command line's engine, credential, and the acknowledgement that Flight is plaintext |

A deployment's file is better than a stack of `-e` flags: `conf/application.yaml` and
`conf/console.yaml` are where the configuration belongs.

### One engine container, by hand

```bash
mkdir -p pravaha-home/{conf,data,logs,tmp}
cat > pravaha-home/conf/application.yaml <<'EOF'
pravaha:
  security:
    allow-anonymous: true          # open, on purpose, for a first run on one machine
EOF
docker run -d --name my-pravaha --user "$(id -u):$(id -g)" --read-only \
  -p 127.0.0.1:18080:18080 -p 127.0.0.1:19090:19090 \
  -v "$PWD/pravaha-home/conf:/opt/pravaha/conf:ro" \
  -v "$PWD/pravaha-home/data:/opt/pravaha/data" \
  -v "$PWD/pravaha-home/logs:/opt/pravaha/logs" \
  -v "$PWD/pravaha-home/tmp:/opt/pravaha/tmp" \
  pravaha/pravaha-server:local
```

`--read-only` is how to see that nothing is written outside `/opt/pravaha`: the node starts and
serves with only those mounts writable. Without bind mounts, give `tmp/` a tmpfs that allows
execution (the native codecs load from it) and is writable by a non-root user:
`--tmpfs /opt/pravaha/tmp:rw,exec,mode=1777`. `deploy/docker/smoke.sh` runs exactly that, and also
runs the image's own `HEALTHCHECK` inside a serving container — the host's `curl` proves nothing about
what the image carries:

```text
ok:   the image's HEALTHCHECK passes inside the container: bin/pravaha-health /actuator/health/liveness
...
ok:   ready, and serving, with --read-only and only /opt/pravaha/{data,logs,tmp} writable
ok:   Parquet's native codecs load in the image: snappy loaded 28 bytes zstd loaded 35 bytes
smoke.sh: PASSED
```

---

## The compose stack

[`deploy/docker/compose/docker-compose.yml`](../../deploy/docker/compose/docker-compose.yml), compose
project **`pravaha-stack`**. Its containers, network and volumes are all named `pravaha-stack-*`, so
`docker compose ... down` touches nothing else on the machine — including any Kafka, PostgreSQL or
other containers you already run.

### Before the first `up`: `tools/docker-env.sh`

```bash
tools/docker-env.sh                         # home: deploy/docker/compose/pravaha-home
tools/docker-env.sh --home ~/pravaha-home   # anywhere else
tools/docker-env.sh --print                 # what .env holds, passwords masked
```

It writes `deploy/docker/compose/.env` (your uid and gid, the home, the ports, generated passwords;
mode 0600, git-ignored) and creates the home **as you**, before Docker can create any of it as root:

```text
docker-env.sh: wrote <repo>/deploy/docker/compose/pravaha-home/conf/application.yaml
docker-env.sh: wrote <repo>/deploy/docker/compose/pravaha-home/conf/console.yaml
docker-env.sh: <repo>/deploy/docker/compose/.env (uid 1000, gid 1000, home <repo>/deploy/docker/compose/pravaha-home)

New credentials -- shown this once (they are kept in <repo>/.../pravaha-home/secrets and <repo>/deploy/docker/compose/.env):
  grafana (profile observability):   admin / <shown once>
  console sign-in:                   admin / <shown once>
  engine token (id seed), for the CLI: <repo>/deploy/docker/compose/pravaha-home/secrets/seed.token
```

| Written | What |
|---|---|
| `conf/application.yaml` | the engine: identity on (people sign in; `admin` from `secrets/initial-admin-password`), token authentication, audit to a file, two static tokens (`seed` for the seed job and the CLI, `prometheus` for scraping), pgwire on, a dead-letter queue and spill under `data/`, and three streams: `orders` (Kafka), `customers` (postgres-cdc), `payments` (mysql-cdc). From [`templates/application.yaml`](../../deploy/docker/compose/templates/application.yaml) |
| `conf/console.yaml` | the console: a generated session secret, the engine by its service name. From [`templates/console.yaml`](../../deploy/docker/compose/templates/console.yaml) |
| `secrets/` (0700) | `initial-admin-password`, `seed.token`, `prometheus.token` (0644 inside the 0700 directory, because the Prometheus container reads it as its own user) |
| `data/console/`, `logs/`, `plugins/`, `tmp/` | empty, yours |

Re-running it is safe: nothing that exists is overwritten, credentials are kept, and only the uid,
gid and home in `.env` are refreshed. The ports, `PRAVAHA_BIND`, `PRAVAHA_TAG` and
`COMPOSE_PROJECT_NAME` you set in `.env` are kept, and so is any line you added
(`COMPOSE_PROFILES=...`); the file is replaced in one rename. (Before 2.0.1 a re-run put every port
but pgwire's, and the tag, back to the defaults -- ENVRERUN-1.) `tools/docker-env-test.sh` checks
exactly this, with no Docker.

### Services and profiles

| Service | Profile | Image | Host port (`.env` variable) | State |
|---|---|---|---|---|
| `pravaha-server` | always | `pravaha/pravaha-server:${PRAVAHA_TAG:-local}` | 18080 HTTP (`PRAVAHA_HTTP_PORT`), 19090 Flight (`PRAVAHA_FLIGHT_PORT`), 15432 pgwire (`PRAVAHA_PGWIRE_PORT`) | `pravaha-home/{conf,secrets,plugins}` read-only; `data`, `logs`, `tmp` |
| `pravaha-console` | always | `pravaha/pravaha-console:${PRAVAHA_TAG:-local}` | 17070 (`PRAVAHA_CONSOLE_PORT`) | `conf/console.yaml` read-only; `data/console`, `logs`, `tmp` |
| `kafka` | always | `confluentinc/cp-kafka:7.6.0`, KRaft, no ZooKeeper | 29092 (`PRAVAHA_KAFKA_PORT`); containers use `kafka:9092` | volume `kafka-data` |
| `seed-kafka`, `seed-register` | `seed` | cp-kafka; the console image | — | none; run once and exit |
| `cli` | `tools` | the console image | — | none |
| `postgres` | `cdc` | `postgres:16-alpine`, `wal_level=logical` | 25432 (`PRAVAHA_POSTGRES_PORT`) | volume `postgres-data` |
| `mysql` | `cdc` | `mysql:8.0`, row binlog, full images, GTIDs | 23306 (`PRAVAHA_MYSQL_PORT`) | volume `mysql-data` |
| `aerospike` | `stores` | `aerospike/aerospike-server:latest` | 23100 (`PRAVAHA_AEROSPIKE_PORT`) | volume `aerospike-data` |
| `cassandra` | `stores` | `cassandra:4.1` | 29042 (`PRAVAHA_CASSANDRA_PORT`) | volume `cassandra-data` |
| `prometheus` | `observability` | `prom/prometheus:v2.53.2` | 29190 (`PRAVAHA_PROMETHEUS_PORT`) | volume `prometheus-data` |
| `grafana` | `observability` | `grafana/grafana:11.2.0` | 23030 (`PRAVAHA_GRAFANA_PORT`) | volume `grafana-data` |

Every port is published on `127.0.0.1` only (`PRAVAHA_BIND`), and none is a product's usual default,
so a PostgreSQL on 5432, a Kafka on 9092 or a Grafana on 3000 already on the machine keeps its port.
Change any of them in `.env`.

The engine and the console run with `user: ${PRAVAHA_UID}:${PRAVAHA_GID}` and `read_only: true`.
The only places either can write are the `pravaha-home` directories mounted onto the same names under
`/opt/pravaha` — so the stack itself proves, every time it runs, that nothing is written outside
`/opt/pravaha`, and everything written is yours. The directory is mounted piecewise, not whole,
because the images keep their code under `/opt/pravaha` too and a mount over the root would hide it.
Kafka, the databases and the monitoring keep their state in named volumes, as their own images'
users.

```bash
C="docker compose -f deploy/docker/compose/docker-compose.yml"
$C up -d                                         # engine, console, Kafka
$C --profile seed up -d                          # ... and the sample queries
$C --profile cdc --profile observability up -d   # ... and PostgreSQL, MySQL, Prometheus, Grafana
$C --profile '*' ps
$C logs -f pravaha-server
$C --profile '*' down                            # stop everything; keeps volumes and pravaha-home
$C --profile '*' down -v                         # ... and drop the named volumes
```

`docker compose` finds `.env` beside the compose file, so these work from the repository root.

---

## The seed: Kafka → continuous query → view

`--profile seed` adds two one-shot services. `seed-kafka` creates the topic `orders` (three
partitions) and writes the twelve orders in
[`seed/orders.jsonl`](../../deploy/docker/compose/seed/orders.jsonl) — only when it created the topic,
so running it again does not double every total. `seed-register` then uses the `pravaha` command line
in the console image to register two continuous queries, and prints what they hold:

| View | SQL | Keyed by |
|---|---|---|
| `orders_live` | [`seed/orders_live.sql`](../../deploy/docker/compose/seed/orders_live.sql): every order | `order_id` |
| `spend_per_minute` | [`seed/spend_per_minute.sql`](../../deploy/docker/compose/seed/spend_per_minute.sql): orders and spend per customer per minute of event time (`TUMBLE`) | `window_start, window_end, customer` |

What it printed:

```text
$ docker compose -f deploy/docker/compose/docker-compose.yml --profile seed up -d
$ docker compose -f deploy/docker/compose/docker-compose.yml --profile seed logs --no-log-prefix seed-kafka seed-register
Created topic orders.
seed: wrote 12 orders to topic orders
no continuous queries are registered
registered orders_live  state=RUNNING  fingerprint=09c41f033145
registered spend_per_minute  state=RUNNING  fingerprint=93ae117f9e65

seed: pravaha queries
NAME              STATE    FINGERPRINT   ROWS IN  SINK
orders_live       RUNNING  09c41f033145  12       -
spend_per_minute  RUNNING  93ae117f9e65  12       -

seed: what each customer has spent, asked of the view orders_live
customer  orders  spend
acme      5       1502
globex    4       834
initech   3       309
3 rows

seed: the view spend_per_minute
window_start               customer  orders  spend
2026-09-29 10:00:00+00:00  acme      1       120
2026-09-29 10:00:00+00:00  globex    1       75
2026-09-29 10:01:00+00:00  acme      1       300
...                                                    (ten rows: 10:00 to 10:04)
10 rows
```

Two things in that output are the engine being right, not the seed being wrong:

- A **`GROUP BY customer` over the stream is refused** (`PRV-2050`: unbounded state). Over the
  *view* `orders_live` it is a finite read, and answers — so the totals are asked of the view.
- **The 10:05 minute is not there yet.** The last order is at 10:05:30 and the stream allows 10s of
  lateness, so the watermark stands at 10:05:20 and the 10:05 window has not closed. Write one more
  order with a later `event_time` and it appears.

### Reading the views, four ways

From the **host**, with the Python CLI (`bin/pravaha` from a checkout, or `pip install "pravaha[flight]"`):

```text
$ export PRAVAHA_TOKEN="$(cat deploy/docker/compose/pravaha-home/secrets/seed.token)" PRAVAHA_INSECURE_TOKEN=true
$ bin/pravaha query --sql "SELECT customer, COUNT(*) AS orders, SUM(amount) AS spend FROM orders_live GROUP BY customer"
3 rows
customer  orders  spend
acme      5       1502
globex    4       834
initech   3       309
```

`PRAVAHA_INSECURE_TOKEN=true` acknowledges that the token crosses plaintext Flight, which the SDK
otherwise refuses (`PRV-1031`); on one machine, over loopback, that is the honest setting.

From a **container**, with no Python on the host — the `tools` profile's `cli` service:

```text
$ docker compose -f deploy/docker/compose/docker-compose.yml run --rm cli queries
NAME              STATE    FINGERPRINT   ROWS IN  SINK
orders_live       RUNNING  09c41f033145  12       -
spend_per_minute  RUNNING  93ae117f9e65  12       -
```

With **psql** over the PostgreSQL wire protocol, the token as the password (here psql itself from a
container, so nothing is installed):

```text
$ docker run --rm --network host -e PGPASSWORD="$(cat deploy/docker/compose/pravaha-home/secrets/seed.token)" \
    postgres:16-alpine psql "host=127.0.0.1 port=15432 dbname=pravaha user=seed" \
    -c "SELECT customer, orders, spend FROM spend_per_minute"
 customer | orders | spend
----------+--------+-------
 acme     |      1 |   120
 globex   |      1 |    75
 ...
(10 rows)
```

In the **console**, <http://localhost:17070>: sign in as `admin` with the password from
`secrets/initial-admin-password`; **Views** lists `orders_live` and `spend_per_minute`, and each
opens to its rows. Checked here by signing in over HTTP and reading the pages:

```text
POST /login -> 200 /operations
GET /views -> 200 (orders_live listed: True, spend_per_minute listed: True)
GET /views/spend_per_minute -> 200
GET /health/ready -> 200 {"status":"ready","engine":{"reachable":true,"url":"grpc://pravaha-server:19090","queries":2}}
```

Change the password at the first sign-in (**Account**), then delete `secrets/initial-admin-password`:
the engine reads it only while it has no users.

---

## Change data capture: the `cdc` profile

`--profile cdc` starts PostgreSQL and MySQL, each prepared by an init script in
[`initdb/`](../../deploy/docker/compose/initdb) on its first start: PostgreSQL gets
`public.customers` (`REPLICA IDENTITY FULL`, three rows) and a role `pravaha_cdc` that may replicate,
owns the table and may create in the database (the plugin creates its own publication); MySQL gets
`shop.payments` and a `pravaha_cdc` user with `REPLICATION SLAVE, REPLICATION CLIENT, SELECT`. The
engine's configuration already binds the streams `customers` (`postgres-cdc`, with an initial
snapshot) and `payments` (`mysql-cdc`); a source opens only when a query over it registers, so they
cost nothing while the profile is down.

```text
$ C="docker compose -f deploy/docker/compose/docker-compose.yml"
$ $C --profile cdc up -d --wait postgres mysql
$ bin/pravaha register --name customers_live --keys 0 --sql "SELECT id, name, tier FROM customers"
registered customers_live  state=RUNNING  fingerprint=268d31a9f5cd
$ bin/pravaha register --name payments_live --keys 0 --sql "SELECT id, customer, amount FROM payments"
registered payments_live  state=RUNNING  fingerprint=2408ac96215a

# in PostgreSQL: UPDATE customers SET tier='platinum' WHERE id=1; INSERT (4,'umbrella','silver'); DELETE id=3
# in MySQL:      INSERT INTO payments VALUES (1,'acme',500),(2,'globex',250); UPDATE ... SET amount=300 WHERE id=2

$ bin/pravaha query --sql "SELECT id, name, tier FROM customers_live"
3 rows
id  name      tier
2   globex    silver
1   acme      platinum
4   umbrella  silver
$ bin/pravaha query --sql "SELECT id, customer, amount FROM payments_live"
2 rows
id  customer  amount
1   acme      500
2   globex    300
```

The snapshot's three rows, then an update, an insert and a delete from PostgreSQL, and two inserts
and an update from MySQL — each arriving as the retraction and insertion it is. The databases are
reachable from the host on 25432 and 23306 with the passwords in `.env`.

## Stores: the `stores` profile

`--profile stores` starts Aerospike (with `nofile` raised to 65536: the server refuses to start below
15000 descriptors, and Docker's default is 1024) and Cassandra 4.1 (512 MB heap). Nothing is bound to
them by default; add a `lookups:`, `sinks:` or `sources:` entry to `conf/application.yaml` naming
`aerospike:3000` or `cassandra:9042`, following the connector's page in the console's help. Checked
here: `asinfo -v status` answered `ok`, `cqlsh` answered `release_version 4.1.12`.

## Adding a jar: `plugins/`

The server jar carries every connector and the PostgreSQL JDBC driver. Anything else — a JDBC driver
for another database, a plugin of your own — goes in `pravaha-home/plugins/` and is on the classpath
at the next start (`loader.path`). Run here against the `cdc` profile's MySQL with a `jdbc` source
added to `conf/application.yaml`:

```yaml
pravaha:
  streams:
    payments_polled:
      schema: "id:INT64,customer:STRING,amount:INT64"
  sources:
    payments_polled:
      plugin: jdbc
      options:
        url: "jdbc:mysql://mysql:3306/shop"
        user: pravaha_cdc
        password: "<PRAVAHA_CDC_PASSWORD from .env>"
        table: payments
        watermark.column: id
        key.column: id
        schema: "id:INT64,customer:STRING,amount:INT64"
```

```text
$ bin/pravaha register --name payments_polled_live --keys 0 --sql "SELECT id, customer, amount FROM payments_polled"
PRV-5091  the 'jdbc' plugin could not be opened for stream 'payments_polled': ... PRV-5070  plugin 'payments_polled'
  cannot connect to jdbc:mysql://mysql:3306/shop: No suitable driver found for jdbc:mysql://mysql:3306/shop. ...
$ cp ~/.m2/repository/com/mysql/mysql-connector-j/9.1.0/mysql-connector-j-9.1.0.jar deploy/docker/compose/pravaha-home/plugins/
$ $C restart pravaha-server
$ bin/pravaha register --name payments_polled_live --keys 0 --sql "SELECT id, customer, amount FROM payments_polled"
registered payments_polled_live  state=RUNNING  fingerprint=a3c47ca136f1
$ bin/pravaha query --sql "SELECT id, customer, amount FROM payments_polled_live"
2 rows
id  customer  amount
1   acme      500
2   globex    300
```

Check the licence of what you add: a driver under the GPL, or its vendor's terms, is why it is not in
the jar.

## Observability: the `observability` profile

Prometheus scrapes `pravaha-server:18080/actuator/prometheus` every 15s with the `prometheus` token
([`observability/prometheus.yml`](../../deploy/docker/compose/observability/prometheus.yml)) and loads
the shipped rules, [`deploy/observability/prometheus/pravaha-rules.yaml`](../../deploy/observability/prometheus/pravaha-rules.yaml).
Grafana (<http://localhost:23030>, `admin` and `GRAFANA_ADMIN_PASSWORD` from `.env`) has Prometheus
as its default datasource and the four shipped dashboards in a folder called **Pravaha**. What they
answered here:

```text
target pravaha: up
rules: 3 groups, 15 rules
pravaha_query_running: orders_live 1, spend_per_minute 1, payments_live 1, customers_live 1
dashboards: Pravaha / Pravaha · Alerts and catalogue, Pravaha · Assistant, Pravaha · Node overview, Pravaha · Query drill-down
```

---

## Restarts, recovery and logs

State is in `pravaha-home/data`, not in the container. Removing the engine container outright and
creating a new one:

```text
$ $C rm --stop --force pravaha-server && $C up -d --wait pravaha-server
... registry recovered 2 of 2 queries from /opt/pravaha/data/registry.journal
$ bin/pravaha queries
NAME              STATE    FINGERPRINT   ROWS IN  SINK
orders_live       RUNNING  09c41f033145  0        -
spend_per_minute  RUNNING  93ae117f9e65  0        -
$ bin/pravaha query --sql "SELECT customer, COUNT(*) AS orders, SUM(amount) AS spend FROM orders_live GROUP BY customer"
3 rows
customer  orders  spend
acme      5       1502
globex    4       834
initech   3       309
```

`ROWS IN 0` is the point: the new container restored each query from its checkpoint
(`data/checkpoints/<query>/checkpoint-N.bin`) and resumed the Kafka source at the offsets the
checkpoint carried, so the twelve orders were not read again — and the views hold the same answers.

The logs are in `pravaha-home/logs` as well as on `docker compose logs`:

```text
$ ls -l pravaha-home/logs
-rw------- 1 you you  37070 audit.jsonl
-rw-r--r-- 1 you you    340 pravaha-console.log
-rw------- 1 you you 103111 pravaha-server.log
$ head -c 190 pravaha-home/logs/audit.jsonl
{"at":"2026-09-30T01:18:47.391591476Z","principal":"system","tenant":"public","roles":[],"action":"bootstrap.admin_created",...
```

## Nothing outside /opt/pravaha, and everything yours

Three checks, all run after the seed, the CDC queries, a restart and a sign-in:

```text
$ docker diff pravaha-stack-pravaha-server-1
                                         (nothing: the root filesystem is read-only)
$ docker diff pravaha-stack-pravaha-console-1
C /opt
C /opt/pravaha
C /opt/pravaha/conf
A /opt/pravaha/conf/console.yaml         (the bind-mount's own mount point, made by Docker)
$ find deploy/docker/compose/pravaha-home ! -user "$(id -u)" | head
                                         (nothing)
$ find . -path ./.git -prune -o ! -user "$(id -u)" -print | head
                                         (nothing)
```

The last two are the ownership rule: every file under `pravaha-home`, and every file in the
checkout, belongs to the user who ran the stack — none to root, none to uid 10001. The same holds
without Docker, trivially: the node runs as you.

---

## Backup, restore and upgrade

**What to back up** is `pravaha-home/data` (engine state) and `pravaha-home/conf` + `secrets` (what
it is configured with). `logs/` is optional; `tmp/` never. The consistent way is with the engine
stopped:

```bash
$C stop pravaha-server
tar -C deploy/docker/compose -czf pravaha-home-$(date +%F).tar.gz pravaha-home/conf pravaha-home/secrets pravaha-home/data
$C start pravaha-server
```

Stopping first is the advice because it is the case that was exercised: a copy taken while the node
writes may catch the newest checkpoint of a query half-written, and a restore from such a copy has not
been tried here. Restore by stopping the engine, replacing the directories, and starting it. The
files are yours, so neither step needs root. Run here with five queries registered (two over Kafka,
two over CDC, one polling MySQL):

```text
$ $C stop pravaha-server
$ tar -C deploy/docker/compose -czf backup.tgz pravaha-home/conf pravaha-home/secrets pravaha-home/data
$ rm -rf deploy/docker/compose/pravaha-home/data                       # the loss
$ tar -C deploy/docker/compose -xzf backup.tgz pravaha-home/data       # the restore
$ $C start pravaha-server
$ bin/pravaha queries
NAME                  STATE    FINGERPRINT   ROWS IN  SINK
orders_live           RUNNING  09c41f033145  0        -
spend_per_minute      RUNNING  93ae117f9e65  0        -
payments_live         RUNNING  2408ac96215a  0        -
customers_live        RUNNING  268d31a9f5cd  0        -
payments_polled_live  RUNNING  a3c47ca136f1  0        -
$ bin/pravaha query --sql "SELECT id, name, tier FROM customers_live"
3 rows
id  name      tier
2   globex    silver
1   acme      platinum
4   umbrella  silver
```

**Upgrading** is a new image over the same home: build or pull the new tag, set `PRAVAHA_TAG` in
`.env`, and `$C up -d`. (Not exercised across two versions here: every run above used one build.) The journal and checkpoints are read by the new version
([`OPERATIONS.md`](OPERATIONS.md), "Upgrades", says what a version may change); take the backup
above first. `tools/docker-env.sh` never rewrites your configuration on a re-run, so a template that
gained a key in a new version is merged by hand — `diff` your `conf/application.yaml` against
[`templates/application.yaml`](../../deploy/docker/compose/templates/application.yaml).

---

## Troubleshooting

| Symptom | Cause, and the fix |
|---|---|
| `permission denied` running any `docker` command | the user is not in the `docker` group in this shell. `sg docker -c "<command>"`, or log in again after `usermod -aG docker $USER` |
| `run tools/docker-env.sh first` from compose | there is no `.env` beside the compose file |
| files in `pravaha-home` owned by root | a directory was missing when compose started, and the daemon created it. `sudo chown -R $(id -u):$(id -g) pravaha-home`, then run `tools/docker-env.sh` (which creates everything) before `up` |
| the engine exits with `Unable to create tempDir. java.io.tmpdir is set to /opt/pravaha/tmp` | `/opt/pravaha/tmp` is not writable: under `--read-only`, mount it or give it `--tmpfs /opt/pravaha/tmp:rw,exec,mode=1777` |
| `pravaha-server: WARNING /opt/pravaha/data is not writable by uid N` | the mounted directory belongs to someone else: run with `--user` matching its owner, or give it to the user you run as |
| `snappy` / `zstd` fails to load | `tmp/` is `noexec`; a Docker `--tmpfs` is unless it says `exec` |
| the node refuses to start: `allow-anonymous` / `PRV-7019` | no security model was chosen, or the admin password is the published default outside `dev` — see `conf/application.yaml`'s identity block |
| `PRV-1031` from the CLI or SDK | a token over plaintext Flight: add `--insecure-token` / `PRAVAHA_INSECURE_TOKEN=true` on one machine, or configure TLS |
| `PRV-2050` registering a `GROUP BY` | unbounded state over a stream: add a window, or group a view instead (the seed does both) |
| a windowed view stays empty | its windows have not closed: the watermark is the newest event time minus the allowed lateness, so a stream that has gone quiet holds its last window open. Watch `pravaha_query_watermark_lag_seconds` |
| `PRV-5112 ... has no CREATE on database` from postgres-cdc | the role cannot create the publication: `GRANT CREATE ON DATABASE <db> TO <role>` (the stack's init script does). Before 2026-09-30 the same thing surfaced as `PRV-5111 ... permission denied for database` |
| Aerospike restarts in a loop, `1024 system file descriptors not enough` | its `nofile` limit; the compose file raises it — keep `ulimits` if you copy the service |
| a port is already taken | change its variable in `.env` (`PRAVAHA_HTTP_PORT`, ...) and `up -d` again |
| the console says the engine is unreachable | `$C ps`: is `pravaha-server` healthy? The console reaches it as `pravaha-server:19090` on the compose network |

## Where next

- [`../development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md`](../development/GUIDE_BUILD_AND_TEST_WITH_DOCKER.md): the numbered
  walkthrough, including the test suites in containers (`tools/docker-test.sh`).
- [`DEPLOYMENT.md`](DEPLOYMENT.md): the Helm chart, the release, and the same layout without Docker.
- [`OPERATIONS.md`](OPERATIONS.md): what a running node needs from you.
