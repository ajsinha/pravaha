# Setting up the stores

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Every case study needs somewhere for data to live. Two stores cover all four: **Aerospike** for the
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
cd sdk/pravaha-sdk-python
python3 -m venv .venv
. .venv/bin/activate
pip install -e '.[dev]'
```

Every Python snippet in the case studies assumes that virtualenv is active.

## A note on time

Every case study is built on **event time** — the timestamp *in the data* — and not on when a row
happened to be processed. That is what makes results reproducible: load the same rows in any order,
at any speed, and you get the same answer.

The practical consequence is that **a window does not close until data tells it to**. If you load ten
rows and see no output, that is usually correct and not a bug: the engine is still waiting to be told
that no more data belongs to the window. Each case study shows how to advance the watermark, and says
where.
