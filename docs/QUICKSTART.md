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

A few minutes the first time. Then:

```bash
pravaha --help
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
pravaha-server &                      # or run PravahaFlightServer from your own code
pravaha queries                       # no continuous queries are registered
```

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
(ADR-024) — the same client an integrator uses. So it needs the engine running first.

**Prerequisites:** Python 3.11+, and an engine listening on `9090` (step 2 above, or
`java -jar pravaha-server/target/pravaha-server-*.jar`).

```bash
cd console
make install          # creates .venv, installs the console and the Pravaha Python SDK
make run              # serves on :8090, talking to grpc://localhost:9090
```

Open **<http://127.0.0.1:8090>**.

Ports: the console is on **8090**, the engine's Flight endpoint on **9090**, and the engine's own
HTTP/actuator surface on **8080**. To point the console somewhere else:

```bash
.venv/bin/python -m pravaha_console --engine grpc://otherhost:9090 --port 8090 --token "$TOKEN"
```

**The engine does not have to be up.** The console starts anyway and says the engine is unreachable
rather than failing to boot — an operator opening a console during an incident needs it to load and
tell them what is wrong, which is exactly the moment a console that refuses to start is least
useful. `/health` reports the same thing as JSON.

### What you get

| | |
|---|---|
| `/` | Registered queries, their state, row counts and watermark lag |
| `/queries/{name}` | One query: its SQL, its view, what it is producing |
| `/queries/{name}/tail` | A live tail of changes |
| `/query` | Ask an ad-hoc question |
| `/help` | Quick start, concepts, user guide, case studies — the `docs/` set, rendered |

### What it is not

It is a **functional admin console, on purpose**. Server-rendered HTML, no build step, no JavaScript
framework, about four hundred lines. It does the operator's job and does not pretend to be the
product surface design §23.20 describes — a full-featured UI is on the roadmap and is **not** this.
The trade is recorded in [the console's README](../console/README.md) rather than left to be
discovered.

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
