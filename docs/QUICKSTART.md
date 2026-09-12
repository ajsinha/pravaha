# Quickstart

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

Ten minutes from a clone to a continuous query you can watch updating. No store to install, no
cluster, no configuration file.

---

## Before you start

| | Check | If missing |
|---|---|---|
| Java 21 | `java -version` | `sdk install java 21-tem`, or your distribution's OpenJDK 21 |
| Maven wrapper | included | — |
| Python 3.11+ | `python3 --version` | Only needed for the Python parts |

> **Set `JAVA_HOME` explicitly.** Distributions that ship several JDKs often leave `java` on 25 and
> `javac` on 21, and the wrapper then picks the wrong one and `--release 21` fails confusingly:
> ```bash
> export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
> ```

## 1. Build

```bash
git clone <this repository> && cd pravaha
./mvnw -q -DskipTests install
```

A few minutes the first time. That produces two runnable things:

| | |
|---|---|
| `pravaha-cli/target/pravaha-cli-<version>-cli.jar` | the CLI, launched by `bin/pravaha` |
| `pravaha-server/target/pravaha-server-<version>-app.jar` | the engine node, launched by `bin/pravaha-server` |

Put `bin/` on your `PATH` — every example below types `pravaha` rather than a path:

```bash
export PATH="$PWD/bin:$PATH"
pravaha --help
```

Or build a container image instead, which needs no JDK on the host:

```bash
docker build -t pravaha:local .
docker run --rm -p 8080:8080 -p 9090:9090 pravaha:local --spring.profiles.active=dev
```

## 2. Run a query with no server at all

The fastest way to see the engine work. A CSV in, a filtered projection out:

```bash
cd examples/01-filter-and-project
pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
            --schema "user_id:STRING,amount:INT64" \
            --stream txn --in transactions.csv --out out.csv
cat out.csv
```

```
alice,500
dave,150
frank,1200
```

That is the whole engine — parse, plan, off-heap execution — with no process to start. It is how the
embedded mode works, and how the tests run.

## 3. See a query the engine refuses

Worth doing early, because it is the thing that surprises people:

```bash
pravaha run --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" \
            --schema "user_id:STRING,amount:INT64" --stream txn --in transactions.csv --out out.csv
```

```
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the
number of distinct keys and never shrinks. … Bound it with a window …
```

**This is the engine working, not failing.** That aggregate would keep one counter per user forever —
fine at a thousand users, fatal at a hundred million, and it fails weeks after deployment rather than
now. See [Concepts §7](CONCEPTS.md#7-bounds-what-changes-the-answer-and-what-protects-the-machine).

## 4. Start a server and register a continuous query

```bash
pravaha-server --spring.profiles.active=dev &   # or run PravahaFlightServer from your own code
pravaha queries                                 # no continuous queries are registered
```

**Why the profile.** The server refuses to start if it would serve every view to unauthenticated
callers and nobody has said that is the intent — the `dev` profile is that acknowledgement, kept out
of the default so a production deployment cannot inherit it by copying a file. For anything beyond a
local first run, configure credentials instead:

```yaml
pravaha:
  security:
    authentication: token
    policy: authenticated
    tokens:
      "a-long-random-string": { id: ann, tenant: acme, roles: [reader] }
  flight:
    tls: { certificate: /etc/pravaha/tls.crt, key: /etc/pravaha/tls.key }
```

**Declaring a stream, and where its rows come from.** Two separate things, and a query needs both.
`pravaha.streams` puts the schema in the catalog so a query can be planned against it;
`pravaha.sources` says what feeds it. A stream can be declared with nothing attached — that is what
a query written ahead of its source needs — so they are separate blocks:

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
  sources:
    txn:
      plugin: filesystem            # filesystem, feedfile, jdbc or delta
      options:
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
```

With that file, the whole loop works from the command line:

```bash
pravaha-server --spring.config.additional-location=file:./application.yaml &
pravaha register --name by_user \
        --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'" --keys 0
pravaha query --sql "SELECT * FROM by_user"
```

A query naming a stream that is not declared is refused with `Object 'txn' not found. Known streams:
[]` — accurate, and confusing beside a configuration file that clearly mentions `txn`, so check the
`streams` block first.

Without a binding the query still registers and runs — an application pushing its own rows through
the SDK is a supported way to work — and the node logs that nothing is attached, because "zero rows"
otherwise has two causes that look identical.

Register one:

```bash
cat > velocity.sql <<'SQL'
SELECT STREAM
  TUMBLE_END(t.event_time, INTERVAL '1' MINUTE) AS window_end,
  t.user_id,
  COUNT(*)      AS txn_count,
  SUM(t.amount) AS total
FROM txn AS t
WHERE t.status = 'COMPLETED'
GROUP BY TUMBLE(t.event_time, INTERVAL '1' MINUTE), t.user_id
SQL

pravaha register --name user_volume --sql-file velocity.sql --keys 1
pravaha queries
```

```
NAME          STATE    FINGERPRINT   ROWS IN
user_volume   RUNNING  a3f1c2d4e5b6  0
```

It is now running and will keep `user_volume` current until you drop it.

## 5. Ask it something

```bash
pravaha query --sql "SELECT user_id, total FROM user_volume WHERE user_id = ?" --params u1
```

The `?` is **bound**, not pasted in. A bound value is never parsed as SQL, and the server plans the
statement once however many users you ask about.

## 6. Watch it

```bash
pravaha subscribe --view user_volume
```

Changes print as they are committed — one group per commit, never a partial window. Filter at the tap
so rows you did not ask for never cross the network:

```bash
pravaha subscribe --view user_volume --filter user_id=u1
```

> **Nothing appearing?** Almost certainly correct. A window closes when *data* says the window is
> over, not when the clock does, and a subscription starts from *now* rather than from the beginning
> of time. Send an event past the window's end. This is [Concepts §2](CONCEPTS.md#2-event-time-not-clock-time).

## 7. Open the console

The console is a **separate process** that talks to the engine over the published Python SDK
(ADR-024), and ships as its own artefact (ADR-033). So it needs the engine running first.

**Prerequisites:** Python 3.11+, and an engine listening on `9090` (step 2 above).

**Set a console password first**, or nobody can sign in — which is the safe failure, because the
console can drop queries and a default password is a public one:

```bash
export CONSOLE_PASSWORD='something only you know'
```

Reading stays open without it: the landing page, the documentation and the health probes are
deliberately ungated, because an operator opening the console during an incident needs it to load
and say what is wrong before they find a password. Registering, pausing, dropping and running
queries all require a session.

```bash
cd console
make install          # .venv, the Pravaha Python SDK, and the console
make run              # http://127.0.0.1:8090, engine at grpc://localhost:9090
```

Any setting can be overridden on the command line, so a second instance needs no file of its own:

```bash
python run_pravaha_web.py --server.port=8099 --engine.url=grpc://staging:9090
```

**Three ports.** Console **8090**, the engine's Flight endpoint **9090**, the engine's own
HTTP/actuator surface **8080**. Confusing them is the commonest way a first run fails.

### What is there

| | |
|---|---|
| `/` | What this is — the landing page, which answers without an engine |
| `/overview` | Is it up, what is registered, how much is shared, how many live feeds |
| `/queries` | The list — filter, sort and page, **every filter in the URL** |
| `/queries/{name}` | One query: SQL, fingerprint, siblings, a live tail, pause/resume/drop |
| `/workbench` | Ask once with parameters, or register it |
| `/help` | Quick start, concepts, the user guide, SQL support, operations, security, troubleshooting |
| `/tutorials` | The five worked systems, rendered from `examples/case-studies/` |
| `/about` | What Pravaha is, what the name means, and how the console is built |
| `/api/v1/...` | The JSON services the screens are built on |

Keyboard: `/` focuses the filter, `t` cycles the theme, `d` toggles density, `?` opens help.

**The engine does not have to be up.** The console starts anyway and says so on every page rather
than only the one that failed — an operator opening a console during an incident needs it to load
and tell them what is wrong.

**Everything is vendored.** No CDN, so it renders in an air-gapped deployment, which is where a
streaming engine usually lives.

**The documentation is included, not copied.** A help topic is front matter plus
`include: docs/CONCEPTS.md`, so what you read here is the file in this repository — one source of
truth, and cross-references repointed at console routes when rendered.

### What is deliberately not there

A **functional admin console**: server-rendered HTML, no build step, no JavaScript framework. It is
not the product surface design §23.20 describes — no Monaco, no plan DAG, no time-travel debugger,
no Storybook, no visual-regression baseline, no WCAG 2.2 AA audit. Light/dark/terminal, density,
keyboard paths, deep links and the eight states of §23.12 are *implemented*, not yet *audited*.

## 8. Clean up

```bash
pravaha drop --name user_volume
```

The computation goes when its **last** name goes — if somebody else registered the same question,
yours going leaves theirs running.

---

## From a program

Java:

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:9090")) {
    client.register("user_volume", Files.readString(Path.of("velocity.sql")), List.of(1));
    try (QueryResult result = client.query("SELECT total FROM user_volume WHERE user_id = ?", "u1")) {
        for (Row row : result) System.out.println(row.getLong("total"));
    }
}
```

Python:

```bash
cd sdk/python && make install && . .venv/bin/activate
```
```python
from pravaha import connect

with connect("grpc://localhost:9090") as client:
    client.register("user_volume", open("velocity.sql").read(), [1])
    for row in client.query("SELECT total FROM user_volume WHERE user_id = ?", ["u1"]):
        print(row["total"])
```

## Where to go next

| | |
|---|---|
| [Concepts](CONCEPTS.md) | The eight ideas. Read this next |
| [User guide](USER_GUIDE.md) | The whole surface, task by task |
| [Case studies](../examples/case-studies/) | Four worked systems — banking, finance, trading, biology — with a store to stand up and code to copy |
| [What SQL it runs](SQL_SUPPORT.md) | Every construct, checked by a test |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code |

## What is not built

Stated so you do not go looking. Roughly wave 7 of 10:

| | |
|---|---|
| Clustering, HA, failover | Wave 8 — **single node today** |
| Metrics endpoint, time-travel debugging | Wave 9 |
| Kafka, Cassandra, Redis plugins | Wave 10. Filesystem, JDBC and Aerospike work now |
| Spring Boot starter | ADR-020 planned it; not built |
| Column masking | Out of ADR-031 until a deployment asks |
| Performance evidence | Gates P2/P3/P6 unmeasured — needs reference hardware |
