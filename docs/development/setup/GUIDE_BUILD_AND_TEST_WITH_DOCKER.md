# Building and testing Pravaha with Docker, step by step

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../../LICENSE).

From a fresh clone on a machine with **only Docker and git**, to the engine, the console and Kafka
running, a continuous query answering, every test suite run in containers, and everything cleaned up
again. Each step has the command and what you should see; the outputs are the ones this machine
printed on 2026-09-29, and on 2026-10-01 where the images moved to Java 25 (steps 3 and 8, and the
checks re-run throughout), trimmed only where marked `...`.

**Java 25 throughout, and only 25.** Every image here is built on JDK 25 — the engine's JRE, the
root `Dockerfile`'s Maven stage, the test runner — and from 2.0 the classes are Java 25 class files
too ([ADR-061](../../design/adr/061-jdk-25-is-the-baseline-from-2-0.md)). The 1.x `--java 21` option
and its `-jre21` tag are gone; see [Running in Docker: the images](../../operations/RUNNING_IN_DOCKER.md#the-images).

This is the walkthrough. The reference — every path, variable, port and profile — is
[`../operations/RUNNING_IN_DOCKER.md`](../../operations/RUNNING_IN_DOCKER.md). The same journey without Docker is
[`GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md`](GUIDE_BUILD_AND_TEST_WITHOUT_DOCKER.md).

**One rule runs through it:** everything the engine and the console create lives under one directory,
`/opt/pravaha` inside their containers and `pravaha-home/` on your machine, and **every file in it,
and in the checkout, belongs to you** — none to root. Step 9 checks it.

---

## 1. Prerequisites, and checking them

| Need | Why | Check |
|---|---|---|
| Docker Engine 24+ with **BuildKit** (buildx) | the images; the root `Dockerfile` uses a cache mount | `docker buildx version` |
| Docker Compose v2 | the stack | `docker compose version` |
| git | the clone | `git --version` |
| ~6 GB free disk | images (~0.8 GB engine, ~0.5 GB console, ~1.3 GB Kafka), Maven cache (~2 GB), volumes | `df -h /var/lib/docker .` |
| 8 GB RAM for the core stack; 16 GB with `cdc` + `stores` + `observability` | the engine, Kafka, the databases | `docker info --format '{{.MemTotal}}'` |
| Network access | Maven Central and PyPI, the first time | — |

```text
$ docker version --format 'Client {{.Client.Version}}  Server {{.Server.Version}}'
Client 29.8.1  Server 29.8.1
$ docker compose version
Docker Compose version v5.5.1
$ docker buildx version
github.com/docker/buildx v0.37.1 0b265a9f62db554fa9aba6dd19e1bd5704bc7d8a
$ docker info --format 'CPUs={{.NCPU}} Mem={{.MemTotal}} Driver={{.Driver}}'
CPUs=24 Mem=65587331072 Driver=overlayfs
```

**Reaching the daemon as yourself.** Run Docker as your own user — never the helper scripts through
`sudo`, or what they create is root's. If `docker ps` says *permission denied*, you are not in the
`docker` group in this shell:

```bash
sudo usermod -aG docker "$USER"      # once; then log out and in
sg docker -c "docker ps"             # or, without logging out: run each command through the group
```

On the machine these outputs come from, the session predated the group, so every Docker command
below was run as `sg docker -c "<command>"`. The commands are written without it.

## 2. Clone

```bash
git clone <the repository URL> pravaha && cd pravaha
```

Everything after this runs from the repository root.

## 3. Build the images

Two images: the **engine** and the **console**. The console image is always built from source, inside
Docker. The engine has two routes; pick by what your machine has.

**Route A — nothing but Docker.** The root [`Dockerfile`](../../../Dockerfile) builds `pravaha-server` and
`pravaha-cli` inside a `maven:3.9-eclipse-temurin-25` stage, with no JDK on the host, and runs them on
`eclipse-temurin:25-jre`:

```text
$ docker build -t pravaha/pravaha-server:local .
...
#5 [stage-1 1/6] FROM docker.io/library/eclipse-temurin:25-jre@sha256:8da0490f...
#7 [build 1/4] FROM docker.io/library/maven:3.9-eclipse-temurin-25@sha256:93b8a14e...
...
#12 [build 4/4] RUN --mount=type=cache,target=/root/.m2  ./mvnw -B -q -pl pravaha-server,pravaha-cli -am -DskipTests ...
#12 DONE 83.2s
...
#16 unpacking to docker.io/pravaha/pravaha-server:local
```

About a minute and a half here, most of it Maven resolving and compiling; a second build reuses the
cache mount (18.7s for that step, with the cache warm). It also carries `bin/pravaha-engine`, the
offline Java CLI:

```text
$ docker run --rm --entrypoint bin/pravaha-engine pravaha/pravaha-server:local version
pravaha-engine 2.0.1-SNAPSHOT
```

**Route B — a JDK 25 on the host too.** Build the jar yourself and put the release image over it
([`deploy/docker/Dockerfile`](../../../deploy/docker/Dockerfile), [ADR-047](../../design/adr/047-the-image-is-a-dockerfile-over-built-artefacts.md)):

```text
$ ./mvnw -pl pravaha-server -am package -DskipTests
$ deploy/docker/build.sh --tag pravaha/pravaha-server:local
build.sh: pravaha/pravaha-server:local
  jar      pravaha-server-2.0.1-SNAPSHOT-app.jar (176M)
  revision <the short commit>
  java     25 (eclipse-temurin:25-jre)
...
  size  821915431 bytes
  user  10001:10001
  entry [/__cacert_entrypoint.sh bin/pravaha-server]
```

With no JDK on the host, build the jar in the test runner container instead (step 8's script, as you,
so `target/` is yours) and Route B works the same:

```bash
tools/docker-test.sh mvn -pl pravaha-server -am package -DskipTests
```

**The console**, either route:

```text
$ deploy/docker/console/build.sh --tag pravaha/pravaha-console:local
...
#15 5.131 every console include resolves
...
  built pravaha/pravaha-console:local
$ docker images pravaha/pravaha-server:local; docker images pravaha/pravaha-console:local
IMAGE                           DISK USAGE   CONTENT SIZE
pravaha/pravaha-server:local         881MB          310MB      (Route A; Route B's is 822MB / 282MB, no pravaha-engine)
pravaha/pravaha-console:local        497MB          122MB
```

## 4. Prepare `pravaha-home` — as you

```text
$ tools/docker-env.sh
docker-env.sh: wrote <repo>/deploy/docker/compose/pravaha-home/conf/application.yaml
docker-env.sh: wrote <repo>/deploy/docker/compose/pravaha-home/conf/console.yaml
docker-env.sh: <repo>/deploy/docker/compose/.env (uid 1000, gid 1000, home <repo>/deploy/docker/compose/pravaha-home)

New credentials -- shown this once (they are kept in <repo>/.../pravaha-home/secrets and <repo>/deploy/docker/compose/.env):
  grafana (profile observability):   admin / <shown once>
  console sign-in:                   admin / <shown once>
  engine token (id seed), for the CLI: <repo>/deploy/docker/compose/pravaha-home/secrets/seed.token
```

It creates `deploy/docker/compose/pravaha-home` (or `--home <dir>`) **owned by you**, before Docker can
create any of it as root, and writes `.env` beside the compose file with your uid and gid — the
containers run as those. Nothing is owned by uid 10001, and nothing by root:

```text
$ ls -l deploy/docker/compose/pravaha-home
drwx------ conf      drwx------ data      drwx------ logs
drwxr-xr-x plugins   drwx------ secrets   drwx------ tmp
$ ls -l deploy/docker/compose/pravaha-home/secrets
-rw------- initial-admin-password
-rw-r--r-- prometheus.token          (read by the Prometheus container as its own user; the 0700 directory keeps others out)
-rw------- seed.token
$ tools/docker-env.sh --print | head -5
# Written by tools/docker-env.sh -- re-run it rather than editing the first block; the ports and the
# image tag below are yours to change. Holds credentials: mode 0600, and never committed (.gitignore).
COMPOSE_PROJECT_NAME=pravaha-stack
PRAVAHA_UID=1000
PRAVAHA_GID=1000
```

Keep the admin password it printed; it is also in `pravaha-home/secrets/initial-admin-password`.

## 5. Start the stack

```text
$ C="docker compose -f deploy/docker/compose/docker-compose.yml"
$ $C --profile seed up -d
 Container pravaha-stack-kafka-1 Healthy
 Container pravaha-stack-seed-kafka-1 Exited
 Container pravaha-stack-pravaha-server-1 Healthy
 Container pravaha-stack-seed-register-1 Started
...
```

Without `--profile seed` you get the three core services only. The other profiles, any combination:

| Profile | Adds |
|---|---|
| `seed` | the topic `orders`, twelve orders, and the views `orders_live` and `spend_per_minute` (step 7) |
| `cdc` | PostgreSQL 16 (logical replication) and MySQL 8.0 (row binlog), tables ready for `postgres-cdc` / `mysql-cdc` |
| `stores` | Aerospike and Cassandra |
| `observability` | Prometheus and Grafana, wired to the shipped rules and dashboards (step 10) |
| `tools` | `cli`: the `pravaha` command line in a container |

## 6. Check it is healthy

```text
$ $C ps --format 'table {{.Service}}\t{{.Status}}\t{{.Ports}}'
SERVICE           STATUS                    PORTS
kafka             Up 13 seconds (healthy)   9092/tcp, 127.0.0.1:29092->29092/tcp
pravaha-console   Up 7 seconds (healthy)    127.0.0.1:17070->17070/tcp
pravaha-server    Up 7 seconds (healthy)    127.0.0.1:18080->18080/tcp, 127.0.0.1:19090->19090/tcp, 127.0.0.1:15432->5432/tcp
$ curl -s http://127.0.0.1:18080/actuator/health/readiness
{"status":"UP"}
$ curl -s http://127.0.0.1:17070/health/ready
{"status":"ready","engine":{"reachable":true,"url":"grpc://pravaha-server:19090","status":"UP"}}
```

The engine logs where it keeps things — every path is under `/opt/pravaha`:

```text
$ $C logs pravaha-server | grep -E 'identity:|claimed|recovered'
... identity: users, API keys and sessions kept in /opt/pravaha/data/identity/identity.journal (environment stack, ...
... claimed the checkpoint directory /opt/pravaha/data/checkpoints for node 'pravaha-stack-01'
... claimed the registry journal directory /opt/pravaha/data for node 'pravaha-stack-01'
... registry recovered 0 of 0 queries from /opt/pravaha/data/registry.journal
```

## 7. A continuous query, end to end: Kafka → query → view

The `seed` profile did it in step 5; its log is the story:

```text
$ $C --profile seed logs --no-log-prefix seed-kafka seed-register
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
...
2026-09-29 10:04:00+00:00  initech   1       7
10 rows
```

Twelve JSON orders went onto the topic; the engine's `kafka` source read them into the stream
`orders`; two continuous queries keep two views. Now read them yourself, four ways.

**7a. The CLI in a container** — nothing installed on the host:

```text
$ $C run --rm cli queries
NAME              STATE    FINGERPRINT   ROWS IN  SINK
orders_live       RUNNING  09c41f033145  12       -
spend_per_minute  RUNNING  93ae117f9e65  12       -
$ $C run --rm cli query --sql "SELECT customer, COUNT(*) AS orders, SUM(amount) AS spend FROM orders_live GROUP BY customer"
3 rows
customer  orders  spend
acme      5       1502
globex    4       834
initech   3       309
```

**7b. The CLI on the host**, if you have Python 3 (`pip install "pravaha[flight]"`, or `bin/pravaha`
from this checkout with `pyarrow` installed):

```bash
export PRAVAHA_TOKEN="$(cat deploy/docker/compose/pravaha-home/secrets/seed.token)"
export PRAVAHA_INSECURE_TOKEN=true     # the token crosses plaintext Flight, on loopback: say so
bin/pravaha query --sql "SELECT customer, COUNT(*) AS orders, SUM(amount) AS spend FROM orders_live GROUP BY customer"
```

prints the same three rows.

**7c. The console.** Open <http://localhost:17070>, sign in as `admin` with the password from step 4,
choose a new one when asked, and open **Views**: `orders_live` and `spend_per_minute` are there, each
with its rows. Then delete `pravaha-home/secrets/initial-admin-password`; the engine read it once.

**7d. psql**, over the PostgreSQL wire protocol on host port 15432, with the token as the password —
psql itself from a container:

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

**Watch it move.** Write one more order, later in event time, and the 10:05 minute closes:

```bash
echo '{"order_id": 13, "customer": "globex", "amount": 5, "event_time": "2026-09-29T10:07:00Z"}' \
  | docker exec -i pravaha-stack-kafka-1 kafka-console-producer --bootstrap-server localhost:9092 --topic orders
$C run --rm cli query --sql "SELECT window_start, customer, spend FROM spend_per_minute"
```

## 8. Run the test suites in Docker

[`tools/docker-test.sh`](../../../tools/docker-test.sh) runs each suite in a throwaway container **as you**,
from one image it builds on first use (`pravaha/test-runner:local`: Maven 3.9, JDK 25 and Python 3.12;
747 MB), with
its caches in `~/.cache/pravaha-docker` (yours too):

| Command | What runs |
|---|---|
| `tools/docker-test.sh unit [maven args]` | `./mvnw test` — the whole reactor, or what the arguments select. Container-backed tests skip: no Docker in there |
| `tools/docker-test.sh it [--modules a,b]` | the connector modules' tests **with** the Docker socket, so the Testcontainers ones (Kafka, PostgreSQL, MySQL, Aerospike, Cassandra) run. Default: all six connector modules |
| `tools/docker-test.sh sdk` | the Python SDK's pytest suite, including the tests that start the real Flight server |
| `tools/docker-test.sh console` | the console's pytest suite, browser tests off (`PRAVAHA_BROWSER_TESTS=0`) |
| `tools/docker-test.sh all` | the four, in order |
| `tools/docker-test.sh mvn <args>` | any Maven command in the same container |

The client SDKs build and are checked on their own, apart from the server: `tools/build-sdk.sh`
puts the Java SDK's jars and the Python wheel in `target/sdk-dist/`, and `sg docker -c
"tools/sdk-standalone-check.sh --docker pravaha/pravaha-server:local"` starts a throwaway node from
the image built in step 3 on 127.0.0.1:39090 and reads a view from it with four clients outside the
repository, each using only those artefacts; the container is removed afterwards. See
[Testing: the SDKs on their own](../TESTING.md#the-sdks-on-their-own).

What each printed here:

```text
$ tools/docker-test.sh unit -pl pravaha-common -am
docker-test.sh: Maven cache seeded from ~/.m2/repository (hard links)      (first run only)
docker-test.sh: mvn test -pl pravaha-common -am
...
[INFO] Tests run: 266, Failures: 0, Errors: 0, Skipped: 0                   (pravaha-api)
[INFO] Tests run: 258, Failures: 0, Errors: 0, Skipped: 0                   (pravaha-common)
[INFO] BUILD SUCCESS

$ tools/docker-test.sh it --modules plugins/pravaha-plugin-kafka
docker-test.sh: mvn -pl plugins/pravaha-plugin-kafka -am install -DskipTests -Djacoco.skip=true
...
[INFO] BUILD SUCCESS
docker-test.sh: mvn -pl plugins/pravaha-plugin-kafka test -Djacoco.skip=true
...
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, ... -- in ...KafkaSourceBrokerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, ... -- in ...KafkaSinkBrokerTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, ... -- in ...KafkaCompressedBrokerTest
...
[INFO] Tests run: 232, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS                                                        (2:29 min)

$ tools/docker-test.sh sdk
docker-test.sh: mvn -pl pravaha-flight -am test-compile ...
docker-test.sh: the SDK's pytest suite
...                                   426 passed

$ tools/docker-test.sh console
docker-test.sh: the console's pytest suite
...
565 passed, 1374 skipped in 352.74s (0:05:52)
```

What that says, honestly:

- **The Testcontainers tests ran**, against brokers Testcontainers started through the mounted
  socket — `KafkaSourceBrokerTest`, `KafkaSinkBrokerTest` and the rest report their tests run, not
  skipped. That is what `it` is for.
- **Everything passes on the JDK 25 runner**: the Kafka module's 232 (the sink-registration failure
  and the four SDK failures an earlier run of this guide recorded have since been fixed on the host),
  and the SDK's cross-language tests that start the real Flight server — in the same container, which
  is why the runner has a JDK.
- **The console's 1374 skips are its browser suites**, switched off by `PRAVAHA_BROWSER_TESTS=0` (the
  runner has no Chrome). Its real-engine tests run and pass.

The runner is JDK 25, the only JDK Pravaha 2.x builds on; there is no 21 runner to build.

Run `tools/docker-test.sh all` for everything; it takes a while — the `unit` step is the whole reactor.
Single tests go through `mvn`, with `--docker` when they need Testcontainers:

```bash
tools/docker-test.sh mvn --docker -pl plugins/pravaha-plugin-kafka test -Djacoco.skip=true -Dtest=KafkaSinkBrokerTest
```

And ownership, after all four:

```text
$ find . -path ./.git -prune -o ! -user "$(id -u)" -print | head
$ find ~/.cache/pravaha-docker ! -user "$(id -u)" | head
$
```

Testcontainers starts its own Kafka, PostgreSQL and the rest as siblings and removes them; it does not
touch the stack from step 5, or anything else running on the machine.

## 9. Restart, recover — and check ownership

State lives in `pravaha-home/data`, not in the container. Remove the engine's container entirely and
make a new one:

```text
$ $C rm --stop --force pravaha-server && $C up -d --wait pravaha-server
 Container pravaha-stack-pravaha-server-1 Removed
 Container pravaha-stack-pravaha-server-1 Healthy
$ $C logs pravaha-server | grep recovered
... registry recovered 2 of 2 queries from /opt/pravaha/data/registry.journal
$ $C run --rm cli queries
NAME              STATE    FINGERPRINT   ROWS IN  SINK
orders_live       RUNNING  09c41f033145  0        -
spend_per_minute  RUNNING  93ae117f9e65  0        -
$ $C run --rm cli query --sql "SELECT customer, COUNT(*) AS orders, SUM(amount) AS spend FROM orders_live GROUP BY customer"
3 rows
customer  orders  spend
acme      5       1502
globex    4       834
initech   3       309
```

`ROWS IN` counts what the new container has read. Each query was restored from its checkpoint and its
Kafka source resumed at the checkpoint's offsets, so it is `0` — or the few records written after the
last checkpoint (every 30 s here): restarting within 30 s of step 7's extra order shows `1`, and
`globex` then has 5 orders totalling 839, the extra order counted once. Either way nothing is lost and
nothing counted twice.

The logs are on disk as well as in `$C logs`:

```text
$ ls pravaha-home/logs          # under deploy/docker/compose/
audit.jsonl  pravaha-console.log  pravaha-server.log
```

**Nothing outside `/opt/pravaha`, and everything yours.** Both containers run with a read-only root
filesystem, so `docker diff` has nothing to show but a mount point; and no file in the home or the
checkout belongs to anyone but you:

```text
$ docker diff pravaha-stack-pravaha-server-1
$ docker diff pravaha-stack-pravaha-console-1
C /opt
C /opt/pravaha
C /opt/pravaha/conf
A /opt/pravaha/conf/console.yaml          <- the bind mount's mount point, made by Docker
$ find deploy/docker/compose/pravaha-home ! -user "$(id -u)" | head
$ find . -path ./.git -prune -o ! -user "$(id -u)" -print | head
$
```

Both `find`s print nothing. Run them again after step 8: the test suites write `target/` and their
caches as you too.

## 10. Observability

```text
$ $C --profile observability up -d
$ curl -s http://127.0.0.1:29190/api/v1/targets | jq -r '.data.activeTargets[] | "\(.labels.job) \(.health)"'
pravaha up
$ curl -s 'http://127.0.0.1:29190/api/v1/query?query=jvm_info' | jq -r '.data.result[].metric | "\(.runtime) \(.version) \(.vendor)"'
OpenJDK Runtime Environment 25.0.4.1+1-LTS Eclipse Adoptium
```

Prometheus (<http://localhost:29190>) scrapes the engine with the `prometheus` token and has the
shipped rules loaded (3 groups, 15 rules here). Grafana (<http://localhost:23030>, `admin` and
`GRAFANA_ADMIN_PASSWORD` from `.env`) has the four shipped dashboards in the **Pravaha** folder:
*Node overview*, *Query drill-down*, *Alerts and catalogue*, *Assistant*. Open *Node overview* and the
`orders_live` and `spend_per_minute` queries are on it.

The `cdc` and `stores` profiles, the `plugins/` directory and backup and restore are walked through in
[`../operations/RUNNING_IN_DOCKER.md`](../../operations/RUNNING_IN_DOCKER.md).

## 11. Tear down — only what this made

```text
$ $C --profile '*' down            # containers and the network; keeps volumes and pravaha-home
$ $C --profile '*' down -v         # ... and this project's named volumes (Kafka's topic, the databases)
 Volume pravaha-stack_kafka-data Removed
 Network pravaha-stack_default Removed
$ rm -rf deploy/docker/compose/pravaha-home deploy/docker/compose/.env     # yours: no sudo needed
```

Everything the stack creates is named `pravaha-stack-*`; nothing else on the machine is stopped or
removed. To drop the images too: `docker rmi pravaha/pravaha-server:local pravaha/pravaha-console:local
pravaha/test-runner:local`; the test caches are `~/.cache/pravaha-docker`.

## 12. Troubleshooting

| You see | Do |
|---|---|
| `permission denied while trying to connect to the Docker daemon socket` | step 1: join the `docker` group, or `sg docker -c "..."` |
| `run tools/docker-env.sh first` | step 4 — there is no `.env` beside the compose file |
| files owned by root in `pravaha-home` | something ran through `sudo`, or a directory was missing at `up`. `sudo chown -R "$(id -u):$(id -g)" deploy/docker/compose/pravaha-home`, then re-run `tools/docker-env.sh` |
| a port in use (`bind: address already in use`) | set its `PRAVAHA_*_PORT` in `.env` and `up -d` again |
| `seed-register` exits 1 with `PRV-...` | its log names the refusal and the fix; `$C --profile seed logs seed-register` |
| the console says the engine is unreachable | `$C ps`: is `pravaha-server` healthy? `$C logs pravaha-server` |
| `PRV-1031` from the host CLI | set `PRAVAHA_INSECURE_TOKEN=true` (plaintext Flight on loopback) |
| `docker-test.sh` fails at *Can not write to /root/.m2* | harmless on an old test-runner image; `docker rmi pravaha/test-runner:local` and run again |

More, with causes: [`../operations/RUNNING_IN_DOCKER.md`](../../operations/RUNNING_IN_DOCKER.md), "Troubleshooting".
