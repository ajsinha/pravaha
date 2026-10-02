# Quickstart

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../LICENSE`](../../LICENSE).

Ten minutes from a clone to a continuous query you can watch updating. No store to install, no
cluster, no configuration file.

---

## Before you start

| | Check | If missing |
|---|---|---|
| Java 25 | `java -version` | `sdk install java 25-tem`, or your distribution's OpenJDK 25. Pravaha 2.x needs 25; 21 is refused |
| Maven wrapper | included | — |
| Python 3.11+ | `python3 --version` | Only needed for the Python parts |

> **Set `JAVA_HOME` explicitly.** Distributions that ship several JDKs often leave `java` and
> `javac` on different versions, and the wrapper then picks the wrong one; the build refuses
> anything before 25:
> ```bash
> export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
> ```

## 1. Build

```bash
git clone <this repository> && cd pravaha
./mvnw -q -DskipTests install
```

A few minutes the first time. That produces two runnable things:

| | |
|---|---|
| `pravaha-cli/target/pravaha-cli-<version>-cli.jar` | `pravaha-engine` — `validate`, `explain` and `run` with the engine in-process, launched by `bin/pravaha-engine` |
| `pravaha-server/target/pravaha-server-<version>-app.jar` | the engine node, launched by `bin/pravaha-server` |

The commands that talk to a running node — `pravaha queries`, `register`, `query`, `subscribe` and
the rest — are the Python CLI, `pravaha`, which comes with the Python SDK (`pip install './sdk/python[flight]'`).

Put `bin/` on your `PATH` — every example below types `pravaha-engine` or `pravaha` rather than a path:

```bash
export PATH="$PWD/bin:$PATH"
pravaha-engine --help
```

Or build a container image instead, which needs no JDK on the host:

```bash
docker build -t pravaha:local .
docker run --rm -p 18080:18080 -p 19090:19090 pravaha:local --spring.profiles.active=dev
```

## 2. Run a query with no server at all

The fastest way to see the engine work. A CSV in, a filtered projection out:

```bash
cd examples/01-filter-and-project
pravaha-engine run --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
                   --schema "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING" \
                   --out-schema "user_id:STRING,amount:INT64" \
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

**A line the file cannot decode.** By default one bad line ends the run, naming the line, the column
and the value, and no rows are written. `transactions.csv` is clean, so make one that is not:

```bash
printf '1,alice,500,COMPLETED\n2,bob,not-a-number,COMPLETED\n3,carol,900,COMPLETED\n' > mixed.csv
pravaha-engine run --sql "SELECT user_id, amount FROM txn" \
                   --schema "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING" \
                   --out-schema "user_id:STRING,amount:INT64" \
                   --stream txn --in mixed.csv --out mixed-out.csv
```

```
PRV-5040  line 2, column 'amount' (INT64): 'not-a-number' is not a number
```

Add `--dlq <file>` to finish the run anyway and get the rejected lines on disk, one JSON object
each, carrying the original bytes base64-encoded:

```bash
pravaha-engine run --sql "SELECT user_id, amount FROM txn" \
                   --schema "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING" \
                   --out-schema "user_id:STRING,amount:INT64" \
                   --stream txn --in mixed.csv --out mixed-out.csv --dlq rejects.jsonl
```

```
ok  2 in, 2 out
  1 rejected -> rejects.jsonl
```

The counts are of rows the engine saw, so a rejected line is *not* counted in — two in, two out,
one rejected, from a three-line file.

## 3. See a query the engine refuses

Worth doing early, because it is the thing that surprises people:

```bash
pravaha-engine run --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" \
                   --schema "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING" \
                   --out-schema "user_id:STRING,n:INT64" \
                   --stream txn --in transactions.csv --out out.csv
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
    # Placeholders. Both paths must exist and be readable before the node will start:
    # a certificate that is not there is PRV-6104 and the process exits.
    tls: { certificate: /opt/pravaha/conf/tls.crt, key: /opt/pravaha/conf/tls.key }
```

**Declaring a stream, and where its rows come from.** Two separate things, and a query needs both.
`pravaha.streams` puts the schema in the catalog so a query can be planned against it;
`pravaha.sources` says what feeds it. A stream can be declared with nothing attached — that is what
a query written ahead of its source needs — so they are separate blocks:

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time        # the column carrying each row's own time. Declare it before
                                    # any windowed query: without it no watermark advances, so no
                                    # window could ever close, and such a query is refused (PRV-2002)
  sources:
    txn:
      plugin: filesystem            # one of the plugins the server jar carries (filesystem,
                                    # feedfile, jdbc, delta, kafka, postgres-cdc, mysql-cdc, aerospike,
                                    # cassandra); a name it does not carry stops the node (PRV-5090)
      options:
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
```

**A file that keeps growing.** By default a file source is a bounded read: it ends where the file
ends, and a query over it reaches an answer and stops changing. `follow: true` makes it `tail -f`
instead — end of file stops being end of stream, and rows appended while the query runs arrive
without anything being restarted:

```yaml
  sources:
    txn:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
        event.time: event_time      # which column holds the row's own time; on a node the
                                    # stream's own event-time is handed down as this option
        follow: true
```

A followed file that is rotated or rewritten is picked up from the start of its replacement, and a
line is a row only once its newline has arrived — so a writer caught mid-line does not produce half
a record.

**Dates and times are written the way you write them.** A `DATE`, `TIME` or `TIMESTAMP` column
accepts ISO-8601 — `2026-09-14`, `01:00:00`, `2026-09-14T09:30:00Z` — and a bare number is taken as
the engine's own unit for that type: days for a date, nanoseconds for the other two, which is what
the sink writes so a file round-trips. A time written as milliseconds used to be stored unscaled,
which made every predicate over it quietly wrong.

**If the query is windowed, name the event-time column — twice, in effect.** The stream's
`event-time` says which column a window is measured in; leave it out and the windowed query is
refused when you register it (`PRV-2002`), because no watermark could advance and no window could
ever close. On a node that same column is handed down to the source as its `event.time` option, so
you write it once; set `event.time` by hand and every row carries the time it was *read* instead,
the watermark runs at wall-clock, and every row is dropped as late — an empty view under a query
reporting `RUNNING`. The same option exists on the Aerospike source, and matters there for the
same reason.

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
NAME          STATE    FINGERPRINT   ROWS IN  SINK
user_volume   RUNNING  a3f1c2d4e5b6  0        -
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

Changes print as they are committed — one group per commit, never a partial window, closed by a
`-- commit` line. Each change leads with its **weight**: `+1` is a row arriving, `-1` a row withdrawn,
so a window corrected by late data prints its old row at `-1` and its new one at `+1`. Filter at the tap
so rows you did not ask for never cross the network:

```bash
pravaha subscribe --view user_volume --filter user_id=u1
```

> **Nothing appearing?** Almost certainly correct. A window closes when *data* says the window is
> over, not when the clock does, and a subscription starts from *now* rather than from the beginning
> of time (`pravaha subscribe --snapshot` prints the view's rows first). Send an event past the window's end. This is [Concepts §2](CONCEPTS.md#2-event-time-not-clock-time).

## 7. Open the console

The console is a **separate process** that talks to the engine over the published Python SDK
(ADR-024), and ships as its own artefact (ADR-033). So it needs the engine running first.

**Prerequisites:** Python 3.11+.

**The console signs people in against the engine's own users** (ADR-052); it keeps no password of
its own. So restart the engine from step 4 with the `users` profile added, which turns on token
authentication and a local user store:

```bash
pravaha-server --spring.profiles.active=dev,users &
```

The node creates `admin` on first start. With `dev`, `admin` keeps the published password
`pravaha-dev-admin`; without `dev` the node refuses to start on it. From now on every caller presents a
credential: you sign in to the console as `admin`, and the CLI and the SDKs use an API key you issue
from the console's **Account** page (`pravaha query --token <key> ...`).

The landing page, the documentation and the health probes stay open, because an operator opening
the console during an incident needs it to load and say what is wrong before signing in.

```bash
cd console
make install          # .venv, the Pravaha Python SDK, and the console
make run              # http://127.0.0.1:17070, engine at grpc://localhost:19090
```

Any setting can be overridden on the command line, so a second instance needs no file of its own:

```bash
python run_pravaha_web.py --server.port=8099 --engine.url=grpc://staging:19090
```

**Three ports.** Console **17070**, the engine's Flight endpoint **19090**, the engine's own
HTTP/actuator surface **18080**. Confusing them is the commonest way a first run fails.

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
`include: docs/guides/CONCEPTS.md`, so what you read here is the file in this repository — one source of
truth, and cross-references repointed at console routes when rendered.

### What is deliberately not there

A **functional admin console**: server-rendered HTML, no build step, no JavaScript framework. It is
not the product surface design §23.20 describes — no Monaco, no plan DAG, no time-travel debugger,
no Storybook, no visual-regression baseline, no WCAG 2.2 AA audit. Light/dark/terminal, density,
keyboard paths, deep links and the eight states of §23.12 are *implemented*, not yet *audited*.

## 8. Clean up

```bash
pravaha drop --name user_volume --yes
```

The computation goes when its **last** name goes — if somebody else registered the same question,
yours going leaves theirs running.

---

## From a program

Java:

```java
try (PravahaFlightClient client = PravahaFlightClient.connect("grpc://localhost:19090")) {
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

with connect("grpc://localhost:19090") as client:
    client.register("user_volume", open("velocity.sql").read(), [1])
    for row in client.query("SELECT total FROM user_volume WHERE user_id = ?", ["u1"]):
        print(row["total"])
```

## Where to go next

| | |
|---|---|
| [Concepts](CONCEPTS.md) | The eight ideas. Read this next |
| [User guide](USER_GUIDE.md) | The whole surface, task by task |
| [Case studies](../../examples/case-studies/) | Five worked systems — trade processing, banking, finance, trading, biology — with a store to stand up and code to copy |
| [Streams, queries and SQL](CONTINUOUS_QUERIES.md) | What you write, end to end, and every construct checked by a test |
| [Troubleshooting](TROUBLESHOOTING.md) | Every `PRV-` code |

## What is not built

Stated so you do not go looking. Roughly wave 9 of 11:

| | |
|---|---|
| Clustering, rebalance, multi-node execution | Deferred ([ADR-034](../design/adr/034-distribution-deferred.md)) — **one node, scaled to its cores**. Wave 8 bought survival on that node, not distribution across several ([ADR-035](../design/adr/035-wave-8-is-survival-not-distribution.md)) |
| Continuous failover | A standby (`pravaha.standby.enabled`) takes over from the newest checkpoint and says what that cost. It buys **recovery time, not continuity** |
| Time-travel debugging | Not on the road to GA — [ADR-038](../design/adr/038-one-node-ga.md) moved it to the roadmap, and [ADR-039](../design/adr/039-ga-includes-the-known-gaps-and-clustering.md) kept it there. Prometheus metrics are live now, including per-query state against its ceiling — `/actuator/prometheus`, see [Operations](../operations/OPERATIONS.md#watching-a-running-node) |
| Kafka and Redis plugins | Not built. Filesystem, feedfile, JDBC, Delta, Aerospike and Cassandra (a periodic `token()`-range scan) work now |
| Spring Boot starter | ADR-020 planned it; not built |
| State that spills instead of failing | Built, and **off by default**: set `pravaha.state.spill.directory` and join and windowed-aggregate state spill to disk rather than failing ([ADR-037](../design/adr/037-state-that-degrades-instead-of-dying.md) B2). `COUNT(DISTINCT)` cannot spill. Without it a query that reaches its ceiling is refused, and you can *watch* it approach — `pravaha_query_state_fraction` (B1) |
| Column masking | Out of ADR-031 until a deployment asks |
| Performance evidence | Gates P2/P3/P6 unmeasured — needs reference hardware |
