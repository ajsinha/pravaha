# Setting up the stores

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Every case study needs somewhere for data to live. Two stores cover all five: **Aerospike** for the
studies that read a hot key-value store, **PostgreSQL** for the one that reads a ledger. You do not
need both — each case study says which it uses.

This page is the part that is identical everywhere. Each case study has its own section for the
namespaces, tables and rows *it* needs, and you should read that after this.

## What you need first

| | Check it with | If it is missing |
|---|---|---|
| Java 21 | `java -version` | `sudo apt install openjdk-21-jdk`, or SDKMAN: `sdk install java 21-tem` |
| Docker | `docker ps` | [docs.docker.com/engine/install](https://docs.docker.com/engine/install/) |
| Python 3.11+ | `python3 --version` | `sudo apt install python3 python3-venv` |
| This repository, built | `./mvnw -q -DskipTests install` | Takes a few minutes the first time |

> **Java 21 specifically.** Pravaha targets 21. If `java -version` says 25 while `javac -version`
> says 21 — which happens on distributions that ship both — set `JAVA_HOME` explicitly:
> ```bash
> export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
> ```
> Put that line in your shell profile. Every command on these pages assumes it.

## Aerospike

### Start it

```bash
docker run -d --name pravaha-aerospike \
  --network host \
  aerospike/aerospike-server:latest
```

> **`--network host` is not optional, and this is the single thing people lose an afternoon to.**
> Aerospike clients discover the cluster by asking a seed node for the *addresses of all nodes*, and
> a containerised node reports its address inside Docker's bridge network. Your client then tries to
> reach an address that does not exist from where it is standing, and the symptom is a connection
> that succeeds and then hangs rather than an error that says so. Host networking makes the address
> the node reports the address you can actually reach.
>
> The cost is that port 3000 must be free. If something else is on it, this fails with an
> unexplained launch error rather than "port in use". Check first:
> ```bash
> ss -ltn | grep ':3000 ' && echo "something is already on 3000"
> ```

### Check it is up

```bash
docker exec pravaha-aerospike asinfo -v status
# expect: ok
```

### The tool you will use to load data

```bash
docker exec -it pravaha-aerospike aql
```

`aql` is Aerospike's shell. Every case study that uses Aerospike gives you `aql` statements to paste.

### Stop and wipe it

```bash
docker rm -f pravaha-aerospike
```

Aerospike here runs in memory with no persistence configured, so removing the container removes the
data. That is what you want for a case study and not what you want in production.

## PostgreSQL

### Start it

```bash
docker run -d --name pravaha-postgres \
  -e POSTGRES_PASSWORD=pravaha \
  -e POSTGRES_USER=pravaha \
  -e POSTGRES_DB=pravaha \
  -p 5432:5432 \
  postgres:16
```

Bridge networking is fine here — a PostgreSQL client talks to the one address you gave it and is
never redirected, so the problem that forces host networking on Aerospike does not arise.

### Check it is up

```bash
docker exec pravaha-postgres pg_isready -U pravaha
# expect: /var/run/postgresql:5432 - accepting connections
```

### The tool you will use to load data

```bash
docker exec -it pravaha-postgres psql -U pravaha -d pravaha
```

### Stop and wipe it

```bash
docker rm -f pravaha-postgres
```

## Python client

Once per machine:

```bash
cd sdk/python
python3 -m venv .venv
. .venv/bin/activate
pip install -e '.[dev]'
```

Every Python snippet in the case studies assumes that virtualenv is active.

## The `pravaha` command line

Built with the rest of the engine, and the quickest way to check any of this is working:

```bash
./mvnw -q -DskipTests install
pravaha --help
```

Every case study can be driven entirely from it — register a continuous query, list what is running,
ask a question with bound parameters, watch a stream, drop it again. The commands go through the same
published SDK your application would use, so nothing works there that would not work in your code.

## The node, and the file that describes it

A client sends SQL and reads answers; the stream definitions live on the server. Each case study
ships that server's configuration as `conf/application.yaml`, and each says where to start it. The
file has three blocks: `pravaha.streams` says what a stream **is** (enough to plan a query against
it), `pravaha.sources` says where its rows **come from** (enough to run one), and `pravaha.lookups`
binds the dimension table a temporal join asks.

```bash
pravaha-server --spring.profiles.active=dev \
               --spring.config.additional-location=file:./conf/application.yaml &
pravaha queries        # expect: no continuous queries are registered
```

The `dev` profile is the acknowledgement the node demands before it will serve every view to
unauthenticated callers; without it, or real credentials, it refuses to start.

> **The connector has to be inside the jar.** The server is launched as `java -jar`, which reads
> only what the jar contains: there is no plugins directory and `-Dloader.path` is not honoured.
> Only `filesystem` ships in the server jar, so every study here — all of them read Aerospike or
> PostgreSQL — needs its plugin added as a dependency of `pravaha-server` and the jar rebuilt:
> ```xml
> <dependency>
>   <groupId>com.ash.messaging.pravaha</groupId>
>   <artifactId>pravaha-plugin-aerospike</artifactId>   <!-- or -jdbc, with the PostgreSQL driver -->
>   <version>${project.version}</version>
> </dependency>
> ```
> ```bash
> ./mvnw -q -DskipTests -pl pravaha-server -am package
> ```
> Skip it and the node starts, and the first registration fails with `PRV-5090 no source plugin
> named 'aerospike' is on the classpath`. Stated here rather than discovered there.

## A note on time

Every case study is built on **event time** — the timestamp *in the data* — and not on when a row
happened to be processed. That is what makes results reproducible: load the same rows in any order,
at any speed, and you get the same answer.

Event time is not inferred. Each study's `conf/application.yaml` names the column it lives in:

```yaml
pravaha:
  streams:
    card_auth:
      event-time: auth_time       # and its out-of-orderness beside it
```

**Declare it or the query is refused.** A windowed query over a stream with no `event-time` would
ingest every row, report `RUNNING` and emit nothing, for ever — so the engine refuses to register it
and says which column to declare. The same declaration is what the source stamps each row with, so
one key does both.

With it declared, **a window closes when data says the window is over**: the engine waits until it
has read a row whose event time is past the end of the window plus the stream's `out-of-orderness`,
and then publishes. So if you load ten rows that all fall inside one window and see no output, the
window is still open — load one row past its end and the answer appears. Each study shows exactly
which row does that, and the generators do it for you.

If a view stays empty when you think it should not, the node said what it is running on, one line
per stream, when it started:

```text
stream card_auth: event-time=auth_time, out-of-orderness=PT10S, allowed-lateness=PT0S
```
