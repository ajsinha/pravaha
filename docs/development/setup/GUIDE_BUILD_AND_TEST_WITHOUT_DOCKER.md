# Build and test Pravaha without Docker

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../../LICENSE).

From a fresh clone to a running node, a continuous query read four ways, and a restart that keeps
its answer — on a Linux machine with no Docker. Every command below was run on 2026-09-29 on the
development machine (Ubuntu, 24 cores, 61 GiB RAM, OpenJDK 21.0.12, Python 3.14.4), and the output
shown is what it printed, trimmed where marked. **The JDK is 21 or later** ([ADR-062](../../design/adr/062-java-21-or-later.md)): the prerequisites below say so, and the walkthrough's commands are the same on 21 and on 25.

[Testing Pravaha](../TESTING.md) is the reference this walks through: every tier, what it needs, what
skips. The container route is [Build and test with Docker](GUIDE_BUILD_AND_TEST_WITH_DOCKER.md).

---

## 1. Prerequisites

**JDK 21 or later** (Temurin or OpenJDK; CI runs 21 and 25), per
[ADR-062](../../design/adr/062-java-21-or-later.md). Every module, `pravaha-api` and the Java SDKs
included, compiles to Java 21 class files (`maven.compiler.release=21`); the enforcer refuses a JDK
older than 21, and `bin/pravaha-server` and `bin/pravaha-engine` refuse an older JVM by name. Newer
JVMs warn when libraries load native code (JEP 472: snappy, zstd) and, from 23, when they use
`sun.misc.Unsafe` memory methods (JEP 498: Arrow, Netty, protobuf), so the launchers always add
`--enable-native-access=ALL-UNNAMED` and, on a JVM 23 or later, `--sun-misc-unsafe-memory-access=allow`
(older JVMs refuse that option); `PRAVAHA_JAVA_OPTS` still comes last. An application embedding Pravaha
may add the same options.

```bash
/usr/lib/jvm/java-25-openjdk-amd64/bin/java -version
```
```
openjdk version "25.0.4.1" 2026-08-18
```

```bash
python3 --version && git --version
```
```
Python 3.14.4
git version 2.53.0
```

| | Needed for | Notes |
|---|---|---|
| JDK 21 or later | everything | set `JAVA_HOME` to it. The build scripts (`tools/worktree-build.sh`, `tools/verify-clean.sh`, ...) source `tools/jdk.sh`: with `JAVA_HOME` unset it takes the first of `/usr/lib/jvm/java-25-openjdk*` and `/usr/lib/jvm/java-21-openjdk*`, then a `javac` 21 or later on `PATH`, and it refuses a `JAVA_HOME` older than 21 by name; plain `./mvnw` uses whatever `JAVA_HOME` or `PATH` gives it, and the enforcer refuses anything before 21 |
| Git | the clone | |
| Python ≥ 3.11 with `venv` | the SDK (≥ 3.9) and the console (≥ 3.11) | Debian/Ubuntu split `venv` out: without `python3.X-venv`, `make install` stops at *"ensurepip is not available"*. Install that package, or create the venv with `uv venv --seed .venv` and then run `make install` |
| Chrome or Chromium | the console's browser suites only | found on `PATH`, or `PRAVAHA_CHROME=<path>` |
| `psql` | optional, step 9 | any PostgreSQL client works; this machine had none, so step 9 uses psycopg |
| Disk / RAM | | a few GiB for the build and `~/.m2`; 8 GiB RAM is ample for everything here |

**macOS.** Not run for this guide. The same commands apply with a JDK 21 or later from your package manager
(`JAVA_HOME=$(/usr/libexec/java_home -v 21)`); `/tmp` is not a tmpfs there, and the Linux-only
measurement tests (`systemd-run` memory caps, `strace`) skip themselves with a reason.

Set these for every step (the temporary directory is moved off `/tmp`, a RAM-backed tmpfs on many
Linux machines, because tests write real state files):

```bash
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export TMPDIR=$HOME/.cache/pravaha-tmp
export MAVEN_OPTS=-Djava.io.tmpdir=$HOME/.cache/pravaha-tmp
mkdir -p "$TMPDIR"
```

## 2. Clone

```bash
git clone <this repository> pravaha && cd pravaha
```

## 3. Build

```bash
./mvnw -q install -DskipTests
```

The first run downloads dependencies into `~/.m2`. With them already there, the whole reactor
built from an empty `target/` in **78 s** (`-o`, offline, in a linked worktree through
`tools/worktree-build.sh`, which is `./mvnw` with a repository of the worktree's own). What you
get:

```
pravaha-server/target/pravaha-server-2.0.1-SNAPSHOT-app.jar     # the node, run by bin/pravaha-server
pravaha-cli/target/pravaha-cli-2.0.1-SNAPSHOT-cli.jar           # pravaha-engine, run by bin/pravaha-engine
```

`-DskipTests` still compiles test classes, which step 5 needs.

## 4. Unit and module tests

One test class, then one module, then two of the big ones:

```bash
./mvnw -o test -pl pravaha-registry -Dtest=UpsertSinkKeyRowsTest
./mvnw -o test -pl pravaha-algebra
./mvnw -o verify -fae -pl pravaha-server,pravaha-it
```
```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in com.ash.messaging.pravaha.registry.UpsertSinkKeyRowsTest
[INFO] Tests run: 42, Failures: 0, Errors: 0, Skipped: 0          # pravaha-algebra, 3 s, jqwik properties included
[INFO] Tests run: 323, Failures: 0, Errors: 0, Skipped: 0         # pravaha-server, 54 s
[INFO] Tests run: 960, Failures: 0, Errors: 0, Skipped: 2         # pravaha-it, 3 min 00 s
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 11         # pravaha-it's *IT classes
```

The eleven skipped ITs are expected here: three need Docker (Aerospike) and eight are performance
gates that decline to time anything under the coverage agent. [Testing](../TESTING.md#without-docker)
lists every skip and why.

**The container-backed plugin tests skip, and the build is still green.** Run them anyway to see it:

```bash
./mvnw -o verify -Pit -fae -pl plugins/pravaha-plugin-kafka,plugins/pravaha-plugin-postgres-cdc,plugins/pravaha-plugin-jdbc,plugins/pravaha-plugin-mysql-cdc,plugins/pravaha-plugin-aerospike,plugins/pravaha-plugin-cassandra
```
```
[INFO] Tests run: 229, Failures: 0, Errors: 0, Skipped: 47        # kafka
[INFO] Tests run: 64, Failures: 0, Errors: 0, Skipped: 50         # postgres-cdc
[INFO] Tests run: 13, Failures: 0, Errors: 0, Skipped: 13         # jdbc ITs
...
[INFO] BUILD SUCCESS                                              # 31 s, 198 tests skipped
```

**The full gate** is `tools/verify-clean.sh`: it deletes Pravaha's own jars from `~/.m2`, runs
`clean install`, then `verify` over the whole reactor with surefire forks. It was not run for this
guide; its header records 5 min 50 s for 2,274 tests on this machine. Run it in the main checkout,
never while another checkout is building (it deletes shared jars), and in a linked worktree use
`tools/worktree-build.sh` instead.

## 5. The Python SDK

```bash
make -C sdk/python install      # or: (cd sdk/python && uv venv --seed .venv) first, when venv lacks ensurepip
make -C sdk/python test
```

`make test` is quiet on success; this is its collection count and time:

```
426 tests collected
```
Run time 1 min 37 s, 0 failures, 0 skips. The transport tests start a real Flight server from
`pravaha-flight/target/test-classes` with `$JAVA_HOME/bin/java`; if they skip with *"pravaha-flight
is not built"*, step 3 did not run (or ran with `-Dmaven.test.skip`).

**The SDKs for a client.** The SDKs are shipped apart from the server. `tools/build-sdk.sh` builds
only them (the Java SDK's jars, its `-all` jar, the wheel and the sdist) into `target/sdk-dist/`,
without building the server; install the wheel from there with `pip install
'target/sdk-dist/python/pravaha-<version>-py3-none-any.whl[flight]'`. See
[Testing: the SDKs on their own](../TESTING.md#the-sdks-on-their-own).

## 6. The console's tests

```bash
make -C console install
make -C console test-fast       # everything but the browser
make -C console test            # the browser suites too: journeys, states, axe, visual, performance
```

```
16 failed, 1921 passed, 1 skipped in 1887.38s (0:31:27)
```

That was the first run: the 16 were the query page's screenshots, out of date since the page gained
an Owner row. After reviewing `console/tests/visual/failures/*.diff.png` and retaking those
baselines (`make -C console baselines` retakes all of them; `PRAVAHA_UPDATE_BASELINES=1
.venv/bin/python -m pytest tests/test_browser_visual.py -k query-` only the query page's), the
visual suite ran 790 passed. The one skip is `test_observability.py`'s `promtool` check.

No Playwright and no browser download: the suites drive the Chrome or Chromium already installed,
over the DevTools protocol. Without one, the browser tests skip.

## 7. Choose a PRAVAHA_HOME and start the node

A node needs a handful of directories: configuration, data (registry journal, checkpoints, identity
store, incoming files), logs, plugins, secrets and a temporary directory. The container image lays
the same things out under `/opt/pravaha`; [Running in Docker](../../operations/RUNNING_IN_DOCKER.md) has that table,
and describes `bin/pravaha-server`'s home mode: with `PRAVAHA_HOME` set it places every path under
it and reads `$PRAVAHA_HOME/conf/` by itself. This walkthrough was run before home mode existed,
so it names every path explicitly — which still works, and shows what each one is for. Pick any
directory:

```bash
export PRAVAHA_HOME=$HOME/pravaha-home
mkdir -p $PRAVAHA_HOME/{conf,data/incoming,data/registry,data/checkpoints,data/identity,logs,plugins,secrets,tmp}
printf '1,alice,500,COMPLETED,2026-09-29T10:00:00Z\n2,bob,50,PENDING,2026-09-29T10:00:05Z\n3,alice,250,COMPLETED,2026-09-29T10:00:10Z\n' \
  > $PRAVAHA_HOME/data/incoming/txn.csv

cat > $PRAVAHA_HOME/conf/application.yaml <<EOF
server:
  port: 38080
pravaha:
  flight:
    port: 39090
  pgwire:
    enabled: true
    host: 127.0.0.1
    port: 36432
  registry:
    journal: $PRAVAHA_HOME/data/registry/registry.journal
  checkpoint:
    directory: $PRAVAHA_HOME/data/checkpoints
    interval: 5s
  identity:
    store: $PRAVAHA_HOME/data/identity/identity.journal
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
  sources:
    txn:
      plugin: filesystem
      options:
        path: $PRAVAHA_HOME/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        follow: true
EOF
```

**The ports.** The defaults are 18080 (HTTP), 19090 (Flight) and 17070 (the console). On this
machine another Pravaha was already on them, so this walkthrough uses 38080, 39090, 36432 (pgwire)
and 37070 — pick free ones with `ss -ltn`. A port already taken stops the node with
`PRV-6202 could not listen on 127.0.0.1:… -- Address already in use`.

`follow: true` makes the file a stream: rows appended while the node runs arrive without a restart.
The registry journal is what brings queries back after a restart, the checkpoint directory what brings
their answers back; with neither, a restart starts empty.

```bash
cd $PRAVAHA_HOME
PRAVAHA_JAVA_OPTS="-Djava.io.tmpdir=$PRAVAHA_HOME/tmp" \
  <repo>/bin/pravaha-server --spring.profiles.active=dev,users \
  --spring.config.additional-location=file:./conf/application.yaml > logs/server.log 2>&1 &
```

`dev` acknowledges a local, loopback deployment: alone, it serves every view to callers with no credential (`allow-anonymous`) and lets `admin` keep its published password. `users` turns on sign-in against the node's own user store, which the console requires, and the `authenticated` policy: a signed-in user reads views and registers queries, drops, pauses or replaces only their own (or what a grant or the `admin` role allows), and only `admin` reads the audit trail and every tenant's use (PERMISSIVEUSERS-1). In `logs/server.log`:

```
checkpointing registered queries under …/data/checkpoints every PT5S, keeping the newest 3, timing out at PT30S
Flight SQL listening on 0.0.0.0:39090 (pravaha.flight.host, pravaha.flight.port)
PostgreSQL wire protocol listening on 127.0.0.1:36432 -- NO TLS, the credential crosses the wire in the clear; …
Started PravahaServerApplication in 3.274 seconds
```

and three warnings that are correct for this setup: the data directories were group-writable and
were narrowed to the owner (`SensitiveFiles`), Flight is plaintext with authentication on, and
`admin` still has the default password `pravaha-dev-admin`.

## 8. Start the console

```bash
cd <repo>/console
CONSOLE_PORT=37070 PRAVAHA_ENGINE=grpc://localhost:39090 \
PRAVAHA_ENGINE_HTTP=http://localhost:38080 PRAVAHA_PGWIRE=localhost:36432 \
  .venv/bin/python run_pravaha_web.py
```
```
INFO:     Uvicorn running on http://127.0.0.1:37070 (Press CTRL+C to quit)
```

Open `http://127.0.0.1:37070/login` and sign in as `admin` / `pravaha-dev-admin`. (With the default
ports, `make -C console run` is enough.) Before signing in, `/api/v1/health` says the engine wants a
credential — that is the console reporting the engine correctly, not a fault:

```
{"reachable":false,"url":"grpc://localhost:39090","queries":0,"error":"PRV-1041  PRV-7001  this server requires a credential: …"}
```

## 9. A continuous query, end to end

**The CLI.** `bin/pravaha` runs the SDK's CLI from `sdk/python/.venv`. It will not send a token over
plaintext unless told this is loopback (`PRAVAHA_INSECURE_TOKEN=true`):

```bash
export PRAVAHA_URL=grpc://localhost:39090 PRAVAHA_HTTP=http://localhost:38080 PRAVAHA_INSECURE_TOKEN=true
echo pravaha-dev-admin | bin/pravaha login --user admin --password-stdin --save
bin/pravaha register --name by_user --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'" --keys 0
bin/pravaha queries
bin/pravaha query --sql "SELECT * FROM by_user"
```
```
signed in as admin; the session token is saved to ~/.config/pravaha/token (mode 0600), expires …
registered by_user  state=RUNNING  fingerprint=6800f1454919
NAME     STATE    FINGERPRINT   ROWS IN  SINK
by_user  RUNNING  6800f1454919  3        -
1 row
user_id  amount
alice    250
```

Keyed by `user_id`, the view holds each user's latest completed amount — alice's 250 replaced her
500. Append a row and ask again, with a bound parameter:

```bash
printf '4,carol,900,COMPLETED,2026-09-29T10:01:00Z\n' >> $PRAVAHA_HOME/data/incoming/txn.csv
bin/pravaha query --sql "SELECT * FROM by_user"
bin/pravaha query --sql "SELECT amount FROM by_user WHERE user_id = ?" --params carol
```
```
2 rows
user_id  amount
alice    250
carol    900
1 row
amount
900
```

**The Python SDK**, with the token `login --save` wrote:

```python
from pathlib import Path
from pravaha import connect
from pravaha.options import ClientOptions

token = (Path.home() / ".config/pravaha/token").read_text().strip()
options = ClientOptions.create("grpc://localhost:39090", token=token, allow_insecure_token=True)
with connect(options=options) as client:
    for row in client.query("SELECT user_id, amount FROM by_user"):
        print(row)
```
```
Row({'user_id': 'alice', 'amount': 250})
Row({'user_id': 'carol', 'amount': 900})
```

**PostgreSQL wire protocol.** The password is the token, not the user's password (a password is
refused with `PRV-7001 the credential was rejected`). With psycopg:

```python
import psycopg
with psycopg.connect(host="127.0.0.1", port=36432, user="admin", password=token,
                     dbname="pravaha") as conn:
    with conn.cursor() as cur:
        cur.execute("SELECT user_id, amount FROM by_user")
        for row in cur.fetchall():
            print(row)
```
```
('alice', 250)
('carol', 900)
```

`autocommit=True` is not needed since PGWIRE-TX-1: psycopg's default mode sends `BEGIN` first,
and the gateway accepts it — transaction control is a no-op with PostgreSQL's tags and transaction
status, and each read in the block sees the view as it is when it runs. With `psql` the same
connection is
`PGPASSWORD=$token psql -h 127.0.0.1 -p 36432 -U admin -d pravaha -c "SELECT * FROM by_user"`
(not run here: this machine has no `psql`).

**The console.** `/queries` lists `by_user`, and `/queries/by_user` shows it `RUNNING` with its SQL,
fingerprint and a live tail.

## 10. Restart and recovery

Stop the node, write while it is down, start it again:

```bash
kill %1                                  # or the node's PID; it shuts down gracefully
printf '5,bob,75,COMPLETED,2026-09-29T10:02:00Z\n' >> $PRAVAHA_HOME/data/incoming/txn.csv
# start it exactly as in step 7
```
```
registry recovered 1 of 1 queries from …/data/registry/registry.journal
Started PravahaServerApplication in 4.036 seconds
```
```bash
bin/pravaha queries
bin/pravaha query --sql "SELECT * FROM by_user"
```
```
NAME     STATE    FINGERPRINT   ROWS IN  SINK
by_user  RUNNING  6800f1454919  1        -
3 rows
user_id  amount
alice    250
carol    900
bob      75
```

The query came back from the journal, its view from the newest checkpoint, and the source resumed
from the checkpoint's offset: **one** row in since the restart — bob's, written while the node was
down — not the whole file again. The CLI's saved session survived too; it is in the identity store.

## 11. What is skipped without Docker, and using stores you already have

Without Docker, every test that needs a real Kafka, PostgreSQL, MySQL, Aerospike or Cassandra skips
(step 4 shows the counts). They are not replaced by anything; the connectors are covered only by
their mocked tests. To exercise them you need Docker — see [Testing](../TESTING.md#with-docker).

The test suites do **not** read an address for an existing store: each starts its own container
and connects to the port Docker mapped, so they cannot be pointed at a Kafka or PostgreSQL you
already run, and cannot harm one. What you can point at an existing store is a **running node**: bind
a source or sink to it in `conf/application.yaml` and register a query, as in step 9. The server's
executable jar carries every plugin the project builds — Kafka, JDBC, both CDC sources, Aerospike,
Cassandra, Delta, feedfile and filesystem — and naming a plugin it does not carry stops the node at
startup with `PRV-5090`, listing those it does ([Connectors](../../guides/CONNECTORS.md)). A plugin from
outside the project goes on the classpath: in home mode `bin/pravaha-server` puts
`$PRAVAHA_HOME/plugins` there (`-Dloader.path`); [Running in Docker](../../operations/RUNNING_IN_DOCKER.md)
says how, in and out of a container — not exercised in this walkthrough. The options for each
plugin are documented in `pravaha-server/src/main/resources/application.yaml`. Use a scratch topic,
database or set: a CDC source creates a replication slot, and sinks write.

## 12. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `make install`: *ensurepip is not available* | Debian/Ubuntu `venv` package missing | install `python3.X-venv`, or `uv venv --seed .venv` then `make install` |
| A test fails in one module after a change in another, or passes when it should not | a stale Pravaha jar in `~/.m2` (MAVENRACE-1) | `./mvnw -o -pl <module> -am …`; in a worktree `tools/worktree-build.sh`; before committing `tools/verify-clean.sh` |
| `release version 21 not supported`, the enforcer refuses the JDK, or a launcher says Pravaha needs Java 21 | `JAVA_HOME` (or `java` on `PATH`) is older than 21 | `export JAVA_HOME=…` at a JDK 21 or later |
| State or spill tests slow, or `/tmp` fills | `/tmp` is RAM | `TMPDIR` and `MAVEN_OPTS=-Djava.io.tmpdir=…` as in step 1 |
| Node exits with `PRV-6202 … Address already in use` | a port in use (another node, a PostgreSQL on 5432 for pgwire) | change `server.port`, `pravaha.flight.port`, `pravaha.pgwire.port` |
| CLI: `PRV-1031` | a token over plaintext | `PRAVAHA_INSECURE_TOKEN=true` on loopback; TLS otherwise |
| pgwire: `PRV-7001 the credential was rejected` | the user's password given as the pgwire password | give the session token or an API key |
| pgwire: `PRV-2001 … near the keyword 'BEGIN'` | a node from before PGWIRE-TX-1, which refused transaction control | rebuild; no autocommit setting is needed since |
| pgwire: `PRV-6212` / `25P02` *current transaction is aborted* | an earlier statement in the same transaction failed | roll back, as against PostgreSQL |
| SDK tests skip: *pravaha-flight is not built* | test classes missing | step 3 without `-Dmaven.test.skip` |
| Console browser tests skip | no Chrome found | install one, or `PRAVAHA_CHROME=/path/to/chrome` |
| The view stays empty | the stream's schema or the file does not match, or a windowed query with no event-time | `bin/pravaha queries` (ROWS IN), `logs/server.log`, [Troubleshooting](../../guides/TROUBLESHOOTING.md) |
