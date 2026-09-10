# Quickstart

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
Proprietary and confidential; see [`LICENSE`](../LICENSE).

Ten minutes from a clone to a query producing output. Every command here is executed by the build
(`ExamplesTest`), so if one stops working the build fails rather than this page quietly misleading
you.

## What you need

| | |
|---|---|
| **JDK 21+** | `java -version`. That is the whole list. |
| Maven | Not needed — the wrapper is vendored. |
| Docker | Not needed for any of this. |

## 1. Build

```bash
./mvnw clean install -DskipTests     # about a minute
```

Or with the full suite, which is what CI runs:

```bash
./mvnw clean verify
```

## 2. Set up the CLI

The CLI is not yet packaged as a launcher, so put it on a classpath once:

```bash
CP=$(./mvnw -q -pl pravaha-cli dependency:build-classpath \
       -Dmdep.outputFile=/dev/stdout -Dmdep.includeScope=runtime | grep -v '^\[' | tail -1)
CP="pravaha-cli/target/classes:$CP"

pravaha() { java -cp "$CP" com.ash.messaging.pravaha.cli.PravahaCli "$@"; }
```

Check it:

```bash
pravaha version
```

## 3. Validate a query

Nothing runs yet — this parses, validates and plans:

```bash
pravaha validate \
  --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
```

```
valid  2082692 us
  output: [user_id VARCHAR NOT NULL, amount INT64 NOT NULL]
```

That two-second figure is almost entirely **JVM startup and Calcite class loading**, not planning.
Design §24.1 targets sub-50 ms validation, and that target is for a warm server answering the
console's editor — not a cold CLI process. Both numbers are real; they measure different things,
and quoting the fast one here would flatter the CLI.

Now break it deliberately:

```bash
pravaha validate --sql "SELECT user_idd FROM txn" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
```

```
PRV-2002  Column 'user_idd' not found in any table. Known streams: [txn]
  https://docs.pravaha.io/errors/PRV-2002
```

Every error carries a stable code and a documentation link. Exit status is `1` for a query problem
and `2` for a command-line mistake, so scripts can tell them apart.

## 4. See the plan

```bash
pravaha explain --level all \
  --sql "SELECT user_id FROM txn WHERE amount > 100 AND status = 'COMPLETED'" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
```

```
Logical plan
LogicalProject(user_id=[$1])
  LogicalFilter(condition=[AND(>($2, 100), =($3, 'COMPLETED'))])
    LogicalTableScan(table=[[pravaha, txn]])

Physical plan
Project[user_id]
  Filter((amount > 100 AND status = 'COMPLETED'))
    Scan(txn)
```

The logical plan is Calcite's; the physical plan is Pravaha's own, and nothing below that boundary
knows Calcite exists (ADR-002).

## 5. Run it

```bash
pravaha run \
  --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING' \
  --in  examples/01-filter-and-project/transactions.csv \
  --out /tmp/big-transactions.csv \
  --out-schema 'user_id:STRING,amount:INT64'

cat /tmp/big-transactions.csv
```

```
alice,500
dave,150
frank,1200
```

Plan time and execution time are reported separately, because planning is paid once at registration
and execution per record — a slow run needs to say which half is slow.

## 6. Meet a deliberate refusal

```bash
pravaha validate --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
```

```
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of
distinct keys and never shrinks. One row per key is fine at a thousand keys and fatal at a hundred
million, and the failure arrives weeks after deployment.
  Bound it with a window -- GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id -- so state is
released when each window closes.
Refusing now rather than exhausting memory later.
  https://docs.pravaha.io/errors/PRV-2050
```

The message names **the column**, not its ordinal. `GROUP BY [0]` -- which is what this said until
Wave 4 -- tells a reader nothing, and somebody debugging at speed will map that ordinal to the wrong
column at least once.

This is not an unimplemented feature so much as a policy. Unbounded integration over an unbounded
key space is how incremental engines die in production, and the only intervention that reliably
works is refusing at registration — where it costs a minute — rather than at 3 a.m. (design §9.6).

## 7. Run the server

```bash
./mvnw -q -pl pravaha-server spring-boot:run
```

Then:

```bash
curl -s localhost:8080/api/v1/status | jq
curl -s -XPOST localhost:8080/api/v1/streams -H 'content-type: application/json' \
  -d '{"name":"txn","schema":"txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"}'
curl -s -XPOST localhost:8080/api/v1/queries/validate -H 'content-type: application/json' \
  -d '{"sql":"SELECT user_id FROM txn WHERE amount > 100"}' | jq
```

Two things worth opening in a browser:

- <http://localhost:8080/status> — a plain HTML page rendered by the engine with no JavaScript and
  no external assets. It exists so a node stays diagnosable when the console process is down.
- <http://localhost:8080/api/docs> — the OpenAPI surface, which is locked in
  [`api/openapi.lock.json`](../api/openapi.lock.json) so it cannot change without a reviewed diff.

## 8. Embed it

See [`examples/03-embedded-java`](../examples/03-embedded-java/). The engine runs in your process
with no Spring and no cluster, which is the property the whole competitive position rests on
(design §2.2).

## Where next

| | |
|---|---|
| [Architecture in two pages](ARCHITECTURE.md) | The shape, before the long version |
| [Examples](../examples/) | Each one runnable and checked by the build |
| [Decision records](adr/) | Why things are the way they are |
| [System design](system_design.md) | Everything, in 33 sections |

## What does not work yet

Stated so you do not go looking:

| | |
|---|---|
| Subscriptions over Flight | Wave 7; `query` is request/response today |
| Kafka, Cassandra, Redis | Later waves. Filesystem, JDBC and Aerospike work now |
| Parameters in a continuous query | Decided in ADR-032, not built: registration has no surface to classify against yet |
| Column masking, per-column policy | Deliberately out of ADR-031 until a deployment asks |
| The console | Wave 7 |
| Clustering | Wave 8 |
| Read replicas, range indexes | Wave 6 remainder |

Since this list was last written, windowing and keyed `GROUP BY` (Wave 4), joins, checkpointing and
recovery (Wave 5), backfill and served views (Wave 6), and the Aerospike and JDBC plugins have all
landed — along with the Flight SQL gateway, both SDKs, authentication and authorization.

---

<sub>**Project Pravaha (प्रवाह)** — *Ask once. Answer always.*<br>
Copyright © 2026 Ashutosh Sinha &lt;ajsinha@gmail.com&gt;. All rights reserved. **Proprietary and confidential.**</sub>
