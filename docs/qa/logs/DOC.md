# DOC — Documentation QA execution log

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

Cases: [`../cases/DOC.md`](../cases/DOC.md). 50 written, 50 executed.

Environment for every case below unless stated otherwise:

```
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH=/home/ashutosh/IdeaProjects/pravaha/bin:$PATH
```

Ports used: HTTP 18500–18509, Flight 19500–19509. Scratch:
`/tmp/claude-1000/.../scratchpad/qa-doc`. Four server PIDs and one console PID were started and all
were killed by number; the one container built was removed along with its image.

> **A regression appeared in the shared tree part-way through this run and it colours the second
> half of this log.** At 20:22 `pravaha-server --spring.profiles.active=dev` started normally and the
> whole QUICKSTART §4 loop was exercised against it. From roughly 20:39 onward the *same command*
> fails at startup with `PRV-7002 … different SecurityPolicy instances` in **every** configuration
> tried, including the plain `dev` profile that had just worked. No commit landed in that window
> (newest is `6030cd5`, 20:16), so another agent's rebuild of `pravaha-flight`/`pravaha-server` is the
> likely cause. Verdicts recorded against the earlier artefact are marked; DOC-008 is BLOCKED by it.

---

## Group A — `docs/QUICKSTART.md`, executed literally

### DOC-001 — Prerequisites sufficient for a naive reader — **FAIL**
```
$ grep -n 'docker\|make \|git clone' docs/QUICKSTART.md
28:git clone <this repository> && cd pravaha
49:docker build -t pravaha:local .
50:docker run --rm -p 8080:8080 -p 9090:9090 pravaha:local ...
222:make install          # .venv, the Pravaha Python SDK, and the console
223:make run              # http://127.0.0.1:8090, engine at grpc://localhost:9090
296:cd sdk/python && make install && . .venv/bin/activate
```
The "Before you start" table lists three things: Java 21, the Maven wrapper, Python 3.11+.

**Verdict:** FAIL, low severity on its own. The document goes on to require `git`, `docker` and
`make`, none of which it lists or tells you how to get. Contrast `examples/case-studies/SETUP.md`,
which does it properly (Java, Docker, Python, each with an install line) — the quickstart is the
weaker of the two and it is the one everybody reads first. Note also that the Python row says "Only
needed for the Python parts", which understates it: §7, the console, is the only way to see the
product's UI and it is Python.

### DOC-002 — The build produces the two artefacts it names — **PASS**
```
$ ls pravaha-cli/target/pravaha-cli-*-cli.jar pravaha-server/target/pravaha-server-*-app.jar
pravaha-cli/target/pravaha-cli-0.1.0-SNAPSHOT-cli.jar
pravaha-server/target/pravaha-server-0.1.0-SNAPSHOT-app.jar
```
Both match the globs in `bin/pravaha` and `bin/pravaha-server`.
**Verdict:** PASS. Not vacuous: the launchers' fallback branches print a build instruction, and
neither fired.

### DOC-003 — `export PATH="$PWD/bin:$PATH"; pravaha --help` — **PASS**
```
$ pravaha --help
pravaha 0.1.0-SNAPSHOT  Ask once. Answer always.
Usage:  pravaha <command> [options]
Commands:
  validate  --sql <query> --schema <spec> [--stream <name>]
  ...
```
**Verdict:** PASS.

### DOC-004 — Container build and run — **PASS**
```
$ docker build -t pravaha:qadoc .
#14 [build 8/8] RUN ./mvnw -B -q -DskipTests package
... #21 naming to docker.io/library/pravaha:qadoc done

$ docker run -d --rm --name pravaha-qadoc -p 18503:8080 -p 19503:9090 \
      pravaha:qadoc --spring.profiles.active=dev
$ docker logs pravaha-qadoc | tail -3
Flight SQL listening on 0.0.0.0:9090
Started PravahaServerApplication in 4.062 seconds
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18503/actuator/health/liveness
200
```
**Verdict:** PASS, and the best-behaved new thing in the rewrite. The image builds from a clean
context, runs as a non-root user, `--spring.profiles.active=dev` reaches the JVM through the
`ENTRYPOINT`, and the liveness probe the `HEALTHCHECK` uses answers. Container and image removed
afterwards. Worth noting: the container starts in 4 s against ~23 s for the same jar on the host,
because `COPY . .` in the build stage discards the host's `target/`.

### DOC-005 — **QUICKSTART step 2, typed exactly — FAIL (highest severity in this report)**
```
$ cd <clean dir containing only transactions.csv>
$ pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
              --schema "user_id:STRING,amount:INT64" \
              --stream txn --in transactions.csv --out out.csv
--out-schema is required. Supplied: [sql, schema, stream, in, out]
$ cat out.csv
cat: out.csv: No such file or directory
```
**Verdict:** FAIL. **The first command in the quickstart does not run.** Two independent defects in
five lines:

1. `--out-schema` is mandatory — `RunCommand.java:45` calls `args.require("out-schema")` — and the
   document does not pass it. `pravaha --help` documents it; the quickstart omits it.
2. Even with `--out-schema` supplied, the `--schema` given is wrong. `transactions.csv` has four
   columns (`txn_id,user_id,amount,status`); the document declares two (`user_id:STRING,amount:INT64`).
   The working form is in `examples/01-filter-and-project/README.md` and declares all four.

The printed expected output (`alice,500` / `dave,150` / `frank,1200`) is real — it is example 01's
output, reached by a different command. Somebody rewrote the invocation and kept the result.

Severity: **critical.** This is the step the document calls "the fastest way to see the engine work",
it is the first thing a new user types, and it fails with a usage error that makes the tool look
broken rather than the document look wrong.

### DOC-006 — **QUICKSTART step 3, the refusal demo — FAIL**
```
$ pravaha run --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" \
              --schema "user_id:STRING,amount:INT64" --stream txn --in transactions.csv --out out.csv
--out-schema is required. Supplied: [sql, schema, stream, in, out]
```
**Verdict:** FAIL, critical. Same root cause as DOC-005, and the consequence is worse: the step
exists to *teach* that `PRV-2050` is the engine working, and the reader never sees `PRV-2050`. They
see a usage error, and the paragraph underneath ("This is the engine working, not failing") then
reads as the document lying to them.

The lesson itself is sound and reachable — `examples/02-aggregate`'s form works (DOC-041) — and
`validate` is the right verb for it, since no output file is involved:
```
$ pravaha validate --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" \
                   --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
PRV-2050  GROUP BY user_id has no bound on its key space, ...
```

### DOC-007 — `pravaha-server --spring.profiles.active=dev` then `pravaha queries` — **PASS (against the 20:22 artefact)**
```
$ pravaha-server --spring.profiles.active=dev --server.port=18500 --pravaha.flight.port=19500 &
PID=4089761
... security: authentication=none, policy=permissive, audit=none, flight transport=PLAINTEXT
... Flight SQL listening on 0.0.0.0:19500
... Started PravahaServerApplication in 23.335 seconds

$ pravaha queries --url grpc://localhost:19500
no continuous queries are registered
```
**Verdict:** PASS as executed. Not vacuous — the "no continuous queries are registered" line is a
real round trip to the node, and the same call returned a populated table later (DOC-012).

Two observations. The startup log is genuinely good: it warns about the unset checkpoint directory,
the unset journal and the absence of source bindings, each naming the key to set. And the document's
explanation of *why* the profile is needed is accurate and well-judged.

**But see the banner at the top of this log: this exact command stopped working later in the run.**

### DOC-008 — The documented `pravaha.security` block — **BLOCKED**
```
$ cat application.yaml        # copied verbatim from QUICKSTART lines 104-113
pravaha:
  security:
    authentication: token
    policy: authenticated
    tokens:
      "a-long-random-string": { id: ann, tenant: acme, roles: [reader] }
  flight:
    tls: { certificate: /etc/pravaha/tls.crt, key: /etc/pravaha/tls.key }

$ pravaha-server --spring.config.additional-location=file:./application.yaml \
                 --server.port=18505 --pravaha.flight.port=19505
... security: authentication=token, policy=authenticated, audit=none, flight transport=TLS
ERROR ... Failed to start bean 'pravahaNode'
Caused by: PravahaException: PRV-7002  this server and the registry it hosts authorize against
different SecurityPolicy instances. ...
  at PravahaFlightServer.requireOnePolicy(PravahaFlightServer.java:201)
```
Isolation attempts — token auth alone, `policy: authenticated` alone, `allow-anonymous: true` alone,
each as a file and as command-line properties — all produced the identical `PRV-7002`. Then the
control did too:
```
$ pravaha-server --spring.profiles.active=dev --server.port=18508 --pravaha.flight.port=19508
PRV-7002  this server and the registry it hosts authorize against different SecurityPolicy instances.
```
**Verdict:** BLOCKED, not FAIL, and I want to be exact about why. Every key in the documented block
binds — the configuration inventory confirms `authentication`, `policy`, `tokens.<t>.{id,tenant,roles}`,
`flight.tls.certificate` and `flight.tls.key` all have real binding sites — and the node echoed the
block back correctly (`authentication=token, policy=authenticated, flight transport=TLS`) before
failing. So the *document* is not wrong about the keys. It fails later, in
`PravahaFlightServer.requireOnePolicy`, and the plain `dev` profile fails there identically, so the
fault is not specific to the documented block and I cannot attribute it to the documentation.

What I can say: **the documented production security configuration has never been demonstrated to
start a server in this session**, and at the time of writing nothing does. Hand this to whoever owns
`PravahaFlightServer` — see the banner.

### DOC-009 — **`pravaha.streams` + `pravaha.sources` deliver rows — PASS (and it refutes two other documents)**
```
$ cat application.yaml        # QUICKSTART lines 120-131, path repointed at a CSV I own
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"
  sources:
    txn:
      plugin: filesystem
      options:
        path: .../qa-doc/loop/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING"

$ pravaha-server --spring.config.additional-location=file:./application.yaml \
                 --spring.profiles.active=dev --server.port=18501 --pravaha.flight.port=19501 &
PID=4100528
... streams declared in configuration: [txn]
... sources bound: [txn <- filesystem[path, schema]]

$ pravaha register --name by_user \
      --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'" --keys 0 --url grpc://localhost:19501
registered by_user  state=RUNNING  fingerprint=f05dea4afee1

$ pravaha queries --url grpc://localhost:19501
NAME     STATE    FINGERPRINT   ROWS IN
by_user  RUNNING  f05dea4afee1  4

$ pravaha query --sql "SELECT * FROM by_user" --url grpc://localhost:19501
user_id  amount
alice    500
bob      50
dave     150
3 rows
```
**Verdict:** PASS. The central claim of the rewrite holds: a stream declared in configuration and
bound to a source feeds a registered continuous query, and the view answers.

**Proof it is not vacuous:** the source CSV has four rows, one of which (`carol,900,PENDING`) fails
the `WHERE status = 'COMPLETED'` predicate. `ROWS IN` is 4 and the view returns 3, and the missing
one is exactly the row the predicate excludes. An empty or pass-through result would have looked
different.

**What this refutes.** `README.md` line 14 tells every evaluator, in the first paragraph:

> The `pravaha-server` process additionally has **no ingestion path at all**: a query registered
> against it never receives a row.

and `OPERATIONS.md` line 298 says:

> **What is still missing: nothing feeds it.** No source plugin is connected to a registered query…

Both are false. See DOC-033.

### DOC-010 — Step 4 tells you to create the file it then uses — **FAIL**
Reading step 4 as written: line 104 prints a YAML block with no filename; line 120 prints a second
YAML block, also with no filename; line 133 says "With that file, the whole loop works"; line 136
runs `--spring.config.additional-location=file:./application.yaml`.

**Verdict:** FAIL, high severity. A reader who knows nothing is never told to create a file, what to
call it, where to put it, or that the two YAML blocks are fragments of one document that must be
merged (both start `pravaha:`, and pasting both in sequence produces an invalid YAML file with a
duplicate top-level key). Three further problems in the same twelve lines:

- Line 95 already started a server in the background. Line 136 starts a second one with no `&`
  discipline and no mention of stopping the first, so the second dies on a port clash. The document
  never says how to stop either.
- The second invocation drops `--spring.profiles.active=dev`. With the security block from line 104
  merged in that might be intended; with only the `streams`/`sources` block it is not, and the node
  refuses to start open. I had to add the profile back to make it work (DOC-009).
- The `path:` given is `/var/lib/pravaha/incoming/txn.csv`, which does not exist on any fresh
  machine, and the document never says to create it or put anything in it.

The last one I ran literally, because it is what a reader will do:
```
$ # application.yaml exactly as printed, path /var/lib/pravaha/incoming/txn.csv
$ pravaha-server --spring.config.additional-location=file:./application.yaml --spring.profiles.active=dev ...
... sources bound: [txn <- filesystem[schema, path]]          <- startup says it is fine
$ pravaha register --name t --sql "SELECT user_id FROM txn" --keys 0 --url grpc://localhost:19504
PRV-1041  PRV-5091  the 'filesystem' plugin could not be opened for stream 'txn':
  ConfigurationException: PRV-5040  plugin 'txn' cannot read /var/lib/pravaha/incoming/txn.csv
```
The failure message is good. But `PRV-5091` is **not in `TROUBLESHOOTING.md`** (see DOC-029), and the
document a reader is sent to says in so many words that a code missing from it does not exist. So the
most likely first-run failure of the newest feature is the one the troubleshooting page cannot help
with.

### DOC-011 — **The declared schema does not support the query the same step registers — FAIL**
```
$ cat velocity.sql          # verbatim from QUICKSTART lines 153-162
SELECT STREAM TUMBLE_END(t.event_time, INTERVAL '1' MINUTE) AS window_end, t.user_id,
  COUNT(*) AS txn_count, SUM(t.amount) AS total
FROM txn AS t WHERE t.status = 'COMPLETED'
GROUP BY TUMBLE(t.event_time, INTERVAL '1' MINUTE), t.user_id

$ pravaha register --name user_volume --sql-file velocity.sql --keys 1 --url grpc://localhost:19501
PRV-1041  PRV-2002  Column 'event_time' not found in table 't'. Known streams: [txn]
```
**Verdict:** FAIL, critical. Step 4 declares `txn` as
`txn_id:INT64,user_id:STRING,amount:INT64,status:STRING` and then, thirty lines later in the same
step, registers a query that groups on `t.event_time`. The document contradicts itself inside one
section, and it does so at the exact point where a reader has finally got a server running and is
about to register their first real continuous query. Everything downstream — steps 5, 6 and 8, which
all name `user_volume` — is unreachable from the quickstart as written.

There is a second, deeper problem behind it. Even with an `event_time` column declared, the windowed
query could not produce output on a server: the whole `pravaha.watermark` block is inert on this path
(see DOC-024), so no watermark ever advances and the tumbling window never closes. The document's own
"Nothing appearing? Almost certainly correct… Send an event past the window's end" is advice that
cannot work here, because the `filesystem` source does not follow a file either (DOC-014).

### DOC-012 — `pravaha queries` output matches the printed columns — **PASS**
```
$ pravaha queries --url grpc://localhost:19501
NAME     STATE    FINGERPRINT   ROWS IN
by_user  RUNNING  f05dea4afee1  4
```
Document prints `NAME  STATE  FINGERPRINT  ROWS IN` with `user_volume  RUNNING  a3f1c2d4e5b6  0`.
**Verdict:** PASS — same columns, same order. (The document's sample shows `ROWS IN 0`, which is
consistent with its own unbound-stream narrative.)

### DOC-013 — Step 5, `query --params` — **FAIL (blocked by DOC-011)**
```
$ pravaha query --sql "SELECT user_id, total FROM user_volume WHERE user_id = ?" --params u1 --url grpc://localhost:19501
PRV-1041  PRV-2002  Object 'user_volume' not found. Known streams: [by_user]
```
**Verdict:** FAIL as a documentation case — step 5 cannot be executed because step 4 cannot create
`user_volume`. The mechanism itself is fine: the parameterised form is accepted by the CLI and the
server, and the error is the correct one for a view that does not exist. Also note the document
queries for `user_id = 'u1'` when nothing in the quickstart ever creates a user called `u1` — the
example data in step 2 is alice/bob/carol/dave/erin/frank. A reader who got this far would get an
empty result and not know whether that was the bug or the point.

### DOC-014 — Step 6, `subscribe` — **PASS with a serious caveat**
```
$ pravaha subscribe --view by_user --limit 2 --url grpc://localhost:19501
subscribed to by_user; changes print as they are committed. Ctrl-C to stop.
   [nothing further in 60 s, while two new COMPLETED rows were appended to the source CSV]

$ pravaha subscribe --view by_user --filter user_id=alice --limit 1 --url grpc://localhost:19501
subscribed to by_user; changes print as they are committed. Ctrl-C to stop.
```
```
$ echo "5,zoe,777,COMPLETED" >> txn.csv ; echo "6,yan,888,COMPLETED" >> txn.csv
$ pravaha queries --url grpc://localhost:19501
by_user  RUNNING  f05dea4afee1  4          <- still 4; the appends were never read
```
**Verdict:** PASS on the command surface — both documented forms are accepted, the filter is
accepted, and the CLI tells you how to get out ("Ctrl-C to stop"), which is more than most.

The caveat is a documentation defect and a real one. The `filesystem` plugin the same document tells
you to bind performs a **one-shot scan**; it does not tail a file. `ROWS IN` stayed at 4 after two
appends. So a reader who follows the quickstart to the letter reaches step 6, sees nothing, and is
told by the document — in a blockquote, confidently — that this is "almost certainly correct" and
that they should "send an event past the window's end". There is no way to send an event past
anything with the source they were just told to configure. The document needs either a source that
follows (`feedfile` watches a drop directory) or a sentence saying that the filesystem source reads
once and that step 6 needs something else.

### DOC-015 — Step 7, the console — **FAIL on the printed command, PASS on everything else**
The literal invocation the document gives for a second instance:
```
$ cd console
$ python run_pravaha_web.py --server.port=18502
bash: python: command not found
```
**Verdict:** FAIL, moderate. `python` is not a command on a stock modern Linux (only `python3` is),
and `make install` creates `.venv` but nothing in QUICKSTART §7 or `console/README.md` tells you to
activate it — so even `python3 run_pravaha_web.py` fails on imports. The same document gets this
right for the SDK forty lines later (`cd sdk/python && make install && . .venv/bin/activate`), so the
knowledge exists in the file; it just was not applied here. `console/README.md:21` carries the same
broken line.

With the venv's interpreter, everything else in §7 is accurate:
```
$ CONSOLE_PASSWORD='...' .venv/bin/python run_pravaha_web.py --server.port=18502 --engine.url=grpc://localhost:19501
Pravaha console 0.1.0 ready — engine at grpc://localhost:19501
Uvicorn running on http://127.0.0.1:18502

200  /            200  /overview     200  /queries      200  /workbench
200  /help        200  /tutorials    200  /about        200  /api/v1/queries
200  /queries/by_user
```
Every route in the document's table exists. `/help` renders eight included topics (the seven the
document names, plus `architecture`). `/tutorials` renders five studies plus setup. The four
documented keyboard shortcuts are all real — `web/static/js/theme.js:99-110` handles `t`, `d`, `/`
and `?` exactly as described, including skipping when focus is in an input.

The `CONSOLE_PASSWORD` instruction is correct and is the document's best moment in this section: the
key is `console.password: "${CONSOLE_PASSWORD:}"` in `console/config/application.yaml:39` and an empty
value really does lock the controls. See DOC-028 for where it is *not* documented.

### DOC-016 — Step 8, `drop` — **PASS**
```
$ pravaha drop --name by_user --url grpc://localhost:19501
dropped by_user
$ pravaha queries --url grpc://localhost:19501
no continuous queries are registered
```
**Verdict:** PASS. Not vacuous — the same `queries` call listed `by_user` immediately before.

### DOC-017 — The Java snippet names real API — **PASS**
```
PravahaFlightClient.java:119  public static PravahaFlightClient connect(String connectionString)
PravahaFlightClient.java:260  public RegisteredQueryInfo register(String name, String sql, List<Integer> keyColumns)
PravahaFlightClient.java:222  public QueryResult query(String sql, Object... parameters)
QueryResult.java:41           public final class QueryResult implements Iterable<Row>, AutoCloseable
Row.java:99                   public long getLong(String column)
```
**Verdict:** PASS. Every type and method in the snippet resolves with a compatible signature, and
`QueryResult` really is `Iterable<Row>`, so the `for (Row row : result)` line compiles.

### DOC-018 — The Python snippet and `cd sdk/python && make install` — **PASS**
```
$ sed -n '20,24p' sdk/python/Makefile
install: venv
	.venv/bin/python -m pip install --quiet -e '.[dev]'
$ grep -n 'def connect\|def register\|def query' sdk/python/pravaha/{__init__,client}.py
__init__.py:20  def connect(...)
client.py:314   def register(self, name, sql, key_columns)
client.py:225   def query(self, sql, parameters=None)
```
**Verdict:** PASS. The `make install` target and `.venv` exist, and the three names the snippet uses
are real. This is also the one place the document remembers to say `. .venv/bin/activate`.

### DOC-019 — "What is not built" is true — **FAIL**
| Documented as not built | Reality |
|---|---|
| Clustering, HA, failover — wave 8 | correct |
| **Metrics endpoint — wave 9** | **wrong in both directions.** Per-query Micrometer gauges exist and are registered/removed by `PravahaMetrics.java:123-133`. The *Prometheus* endpoint genuinely does not exist — see DOC-034 |
| Time-travel debugging — wave 9 | correct |
| Kafka, Cassandra, Redis plugins — wave 10 | correct; `plugins/` holds filesystem, delta, feedfile, jdbc, aerospike |
| Spring Boot starter | correct; no starter module exists |
| Column masking | not verified here (ADR-031's scope) |
| Performance evidence, gates P2/P3/P6 | correct |

**Verdict:** FAIL, low severity, but it is the same rot as DOC-034: the one row that is wrong is the
one about metrics, and it is wrong in a way that sends an operator looking for nothing when something
partial exists, while leaving them to discover the missing scrape endpoint by curling a 404.

---

## Group B — commands, flags, endpoints

### DOC-020 — Every `pravaha` subcommand named in a document exists — **PASS for user-facing docs, FAIL for the linked specification**
Extracted across `README.md`, `docs/*.md`, `examples/**`, `console/README.md`:
`validate query register queries subscribe pause resume drop explain run` — all present in
`PravahaCli.run`'s dispatch (lines 65-86). No user-facing document names a command that does not
exist.

Six that do not exist appear only in `docs/system_design.md` and `docs/implementation_plan.md`:
`pravaha dev`, `pravaha bench`, `pravaha diff`, `pravaha replay`, `pravaha test`, `pravaha import`
(`system_design.md:3343-3349`, `:678`).

**Verdict:** PASS on the letter of the case. FAIL in effect, and I am recording it because
`docs/README.md:29` and `README.md` both link `system_design.md` as "The full specification" / "the
full specification, 33 sections" with **no marker that it is aspirational**. It describes a CLI, a
configuration model (`pravaha.runtime.*`, `pravaha.state.*`, a `queries[]` list), a `pravaha.yaml`
file and a `pravaha.ui.enabled` flag, none of which exist. A reader who follows the index into it
cannot tell which half is true, which is precisely the failure mode `docs/README.md:50` warns about
one line after linking it.

### DOC-021 — Every documented flag is accepted; `--help` documents every flag the docs use — **FAIL**
```
$ pravaha register --name by_user --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED'" \
      --keys 0 --url grpc://localhost:19500
PRV-1041  PRV-2002  Object 'txn' not found. Known streams: []
```
**Verdict:** FAIL, low severity. `register --sql` *is* accepted — it reached the planner — but
`pravaha --help` documents `register` as `--sql-file <path>` only. QUICKSTART §4 uses `--sql`, so the
help is incomplete rather than the document being wrong. A user checking `--help` first would
conclude the quickstart was stale and reach for a temp file they do not need.

Two cosmetic things visible in the same output: the code is printed twice
(`PRV-1041  PRV-2002  …`), the client wrapper and the server cause, which reads like a bug; and the
help URL appended to engine errors is `https://docs.pravaha.io/errors/PRV-2050`, and
```
$ host docs.pravaha.io
Host docs.pravaha.io not found: 3(NXDOMAIN)
```
Every error the engine prints ends in a link to a domain that does not resolve, no document says the
URL is a placeholder, and `ExamplesTest.java:153` asserts on that URL — so the build actively holds
the dead link in place.

### DOC-022 — `pravaha explain --sql "…"` as printed in USER_GUIDE §7 — **FAIL**
```
$ pravaha explain --sql "SELECT user_id FROM txn WHERE amount > 100"
--schema is required. Supplied: [sql]
```
**Verdict:** FAIL, low severity. `USER_GUIDE.md:222` prints `pravaha explain --sql "..."  # the plan`
as a runnable line. It is not one. `examples/01-filter-and-project/README.md` shows the correct form
with `--schema`.

### DOC-023 — Documented HTTP endpoints respond — **FAIL**
```
$ for p in ...; do curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18500$p; done
404  /actuator/prometheus          <- OPERATIONS.md:333 says metrics are here
200  /actuator/health/liveness     <- Dockerfile HEALTHCHECK, OPERATIONS
200  /actuator/health
200  /actuator/metrics
200  /actuator/info
200  /api/v1/openapi.json
302  /api/docs                     (redirect to swagger-ui, fine)
200  /status
200  /api/v1/streams               <- application.yaml:69 names POST /api/v1/streams
```
**Verdict:** FAIL on one endpoint, and it is the operationally important one. Everything a document
names responds **except `/actuator/prometheus`**, which `OPERATIONS.md` documents as the place
Prometheus scrapes. The actuator exposure list in `application.yaml:28` includes `prometheus`, but
only three endpoints are exposed at runtime ("Exposing 3 endpoints beneath base path '/actuator'"),
so the registry dependency is absent. See DOC-034.

---

## Group C — configuration keys, both directions

### DOC-024 — **No document names a `pravaha.*` key the code does not bind — FAIL**
Inventory built from every `@ConfigurationProperties` and `@Value` site in `pravaha-server` main
sources, against every `pravaha.*` mention in the user-facing document set.

**Documented, and inert or unreachable:**

| Key | Documented at | Reality |
|---|---|---|
| `pravaha.watermark.out-of-orderness` | `OPERATIONS.md:222`, `CONCEPTS.md:66`, shipped in `application.yaml:167`, promised in javadoc `StreamSchema.java:51` | **No reader anywhere in the repository.** Setting it does nothing. The 10 s default can only be changed per stream, in Java, via `StreamSchema.Builder.outOfOrderness` |
| `pravaha.watermark.idle-after` | `OPERATIONS.md:223,258`, `application.yaml:172` | Read only by `QueryExecution.generatingWatermarks(Configuration)` (`QueryExecution.java:390`), which **`pravaha-server` never calls**. `PravahaNode.start()` builds `QueryRegistry` without `generatingWatermarks(...)`, so the guard at `QueryRegistry.java:437` skips watermark generation entirely |
| `pravaha.watermark.tick` | `OPERATIONS.md:224`, `application.yaml:175` | same |
| `pravaha.cluster.socket.peers` / `.heartbeat.millis` / `.timeout.millis` | `OPERATIONS.md:104-106` | Read by `SocketProvider.java:54,80,81`, but `PravahaNode.java:136-139` forwards only `mode` and `mechanism` into the coordinator `Configuration`. Following OPERATIONS.md verbatim makes the node fail at startup with `PRV-9005 BAD_MEMBERSHIP`, because the peers never arrive |
| `pravaha.cluster.zookeeper.*` | implied by `OPERATIONS.md:113` | same defect, `ZooKeeperProvider.java:61-68` |

**Verdict:** FAIL, high severity — and it is exactly the pattern the brief asked me to look for.
`OPERATIONS.md` is where an operator goes to tune the two settings the same page calls the ones that
"bound memory rather than taste"; both are inert on the server, and the page says of the idle-after
bounds that they are "Minimum 1s, maximum 10m, **both enforced**". Nothing enforces them on this
path, because nothing reads the key.

The consequence is worse than a wasted edit. `OPERATIONS.md:274` says "**Without this, state is
unbounded.** Windows then close only when the input ends… fatal over a stream." The document
describes the configuration that prevents that, and the configuration does not work — so an operator
who reads the page carefully, sets the keys, and believes they are protected is in the failure state
the page warns about, with the page's assurance that they are not.

The javadoc case the owner flagged is still live: `StreamSchema.java:51` names
`pravaha.watermark.out-of-orderness` in a `{@code}` tag, and the key has no reader.

Two further javadoc claims name values that do not exist:
`SecurityProperties.java:52` says the policy may be `tenant` (`PravahaNode.java:206-215` accepts only
`permissive`, `authenticated`, `authenticated-only`), and `SecurityProperties.java:55` says audit may
be `log` (rejected at `PravahaNode.java:226-233`). `application.yaml:98` gets the second one right,
so the yaml and the javadoc disagree about the same key.

### DOC-025 — Every key in `application.yaml` is documented — **FAIL**
| Key | Documented where |
|---|---|
| `pravaha.security.audit` | **nowhere** outside `application.yaml:99` |
| `pravaha.security.authentication` / `.policy` / `.allow-anonymous` / `.tokens.*` | QUICKSTART §4 and one line of HANDOVER only |
| `pravaha.flight.tls.certificate` / `.key` | QUICKSTART §4 only |
| `pravaha.node.id` | `OPERATIONS.md:321` only |
| `pravaha.checkpoint.timeout` | undocumented, **and not settable from server YAML** — `PersistenceProperties.checkpointConfiguration()` forwards only `interval` and `keep` |

**Verdict:** FAIL, high severity, and the finding is not really "some keys are undocumented" — it is
**`docs/SECURITY.md` does not document a single `pravaha.security.*` key.** A 199-line document
called Security, linked from the reference table of both `README.md` and `docs/README.md` as
"Authentication, authorization, row filters, audit", never names the configuration that turns any of
it on. The only place a reader can find it is a quickstart aside and the comments inside a file that
ships inside the jar. Those comments are excellent — lines 75-110 of `application.yaml` are the best
security writing in the repository — and they are invisible to anybody who has not unpacked the
artefact.

### DOC-026 — `OPERATIONS.md` "Starting a node" YAML is safe to copy — **FAIL**
```
docs/OPERATIONS.md:318-326
pravaha:
  flight:
    port: 8815
```
against `application.yaml:62` (`port: 9090`), `PravahaNode.java:118` (default 9090), the CLI's
default URL, both SDKs, the console's `engine.url`, the Dockerfile's `EXPOSE`, and every other
document.

**Verdict:** FAIL, high severity for how cheap it is. The shipped `application.yaml` carries a
comment at lines 58-61 explicitly rejecting 8815 — "a default that disagrees with every example in
the repository costs each new user the same twenty minutes" — and the operations guide then prints
8815. An operator who copies that block gets a node their own `pravaha queries` cannot reach, with no
error that points at the port, and the document that explains the reasoning is the one they did not
read.

### DOC-027 — No javadoc names a configuration key that does not exist — **FAIL**
| Site | Key named | Exists? |
|---|---|---|
| `pravaha-api/…/data/StreamSchema.java:51` | `pravaha.watermark.out-of-orderness` | **no** |
| `pravaha-common/…/config/ConfigurationBuilder.java:111` | `pravaha.runtime.lanes` (via `PRAVAHA_RUNTIME_LANES`) | **no** — lanes come from constructor arguments |
| `pravaha-common/…/config/Redaction.java:49` | `pravaha.state.key.fields` | **no** |
| `pravaha-server/…/SecurityProperties.java:52,55` | policy `tenant`, audit `log` | **values do not exist** |

**Verdict:** FAIL, moderate. Three of the four are illustrative comments rather than instructions,
but they name keys a user will try, and `StreamSchema.java:51` is the specific pattern the owner
called out as the thing to stop.

### DOC-028 — Console configuration is documented — **FAIL**
`console/README.md` is 106 lines and never names an environment variable. It does not mention
`CONSOLE_PASSWORD`.

**Verdict:** FAIL, high severity for a first run. The console's own README says "Running it: `make
install`, `make run`" and stops. A reader who follows *only* the console README gets a console they
cannot sign into, with no indication why, because the safe-failure design means an unset password
produces a refusal rather than a warning. The explanation — and it is a good one — lives in
`docs/QUICKSTART.md:206-218`, two directories away, in a document a console developer has no reason
to open.

Also undocumented anywhere: `CONSOLE_SESSION_SECRET`, `CONSOLE_HOST`, `CONSOLE_PORT`,
`PRAVAHA_ENGINE`, `LOG_LEVEL`. And six settings in `console/config/application.yaml` are read by
nothing — `api.prefix` (the code hard-codes `API = "/api/v1"` at `routes/base.py:45`),
`ui.tail_buffer`, `ui.query_row_limit`, `engine.health_cache_seconds`, `app.author.*`,
`app.copyright.*` — so the file, which the README points at as "every setting", documents six knobs
that do nothing.

---

## Group D — error codes

### DOC-029 — The code table matches the source, both directions — **FAIL (one direction)**
110 `ErrorCode` declarations in main sources, 110 distinct numbers, no collisions.

**Documented-but-nonexistent: none.** All 100 table rows match a real declaration on number *and*
name. That half of the claim is solid and the generation clearly happened.

**Undocumented: 10.**

| Code | Name | Declared at |
|---|---|---|
| `PRV-5090` | INGEST_NO_SUCH_PLUGIN | `pravaha-server/…/ingest/IngestErrors.java:35` |
| `PRV-5091` | INGEST_BINDING_FAILED | `IngestErrors.java:38` |
| `PRV-5092` | INGEST_FEED_FAILED | `IngestErrors.java:41` |
| `PRV-6104` | FLIGHT_TLS_UNREADABLE | `pravaha-flight/…/FlightErrors.java:51` |
| `PRV-1030` | CLIENT_MALFORMED_ENDPOINT | `sdk/…/Endpoint.java:46` |
| `PRV-1031` | CLIENT_INVALID_OPTIONS | `sdk/…/ClientOptions.java:34` |
| `PRV-1040`–`1043` | CLIENT_CONNECT_FAILED, CLIENT_QUERY_REFUSED, CLIENT_READ_FAILED, CLIENT_CLOSED | `sdk/…/ClientErrors.java:24-33` |

**Verdict:** FAIL, high severity, because of what the document says about itself:

> Generated from the source, not from memory: every row above is an `ErrorCode` declared in a
> module's main sources. **If a code is missing here it does not exist in the engine.**

That second sentence is false, and it converts a gap into a trap: a reader who cannot find a code
concludes they misread their log. The three that matter most are `5090`–`5092`, the **ingest** codes —
the newest subsystem, the one QUICKSTART §4 now teaches, and the one whose first-run failure I hit in
DOC-010. `PRV-1040`–`1043` are worse in a different way: every SDK and CLI failure surfaces as one of
them (I saw `PRV-1041` four times in this run), they are the codes a client developer meets *first*,
and they are filed under a range the document labels "Configuration".

`PRV-6104` matters for DOC-008: a TLS configuration with unreadable files has a dedicated code that
the troubleshooting page does not list.

### DOC-030 — Described causes match the throwing code — **PASS with two exceptions**
Every one of the 13 featured codes is genuinely thrown in main sources; none is declared-but-unused.

Accurate: `2050` (`PhysicalPlanBuilder.java:810`, guard `!groupKeys.isEmpty() && !boundedInput` — the
"over a view it is allowed" line is literally the `boundedInput` branch); `2021`; `7001`
(`StaticTokenVerifier.java:83` — the message really does say only "the credential presented was not
accepted", exactly as the document claims, and the verifier even scans all entries after a match to
keep the work constant); `7002`; `4026`/`4027`/`4028` (`ReadAdmission.java:138,156,123` — all three
descriptions match the guards, and `FlightErrors.java:71` confirms all three map to
`RESOURCE_EXHAUSTED`); `4022` (`ServedView.java:214`, thrown after `evict()` at 211, so "retention is
applied *before* this check" is exact, and the message really does branch to say which).

Two exceptions:

- **`PRV-2020` — "an outer join between streams" is stale.** `PhysicalPlanBuilder.java:424-431`
  refuses only RIGHT and FULL; `:448-456` refuses LEFT only when the condition states no time bound.
  A LEFT join between streams *with* a time bound is supported. `USER_GUIDE.md:212` repeats the same
  stale claim ("No outer or self joins between *streams*"). This under-sells a feature that works.
- **`PRV-2050` has a second, undocumented case** at `PhysicalPlanBuilder.java:781`: *"this GROUP BY is
  over a windowed stream but does not group by the window: add window_start and window_end to the
  GROUP BY."* That is a different mistake with a different fix, and a reader who hits it and looks up
  `PRV-2050` is told about unbounded key spaces instead.

Minor: `PRV-4001`'s one-liner covers `RowStore.java:142` but not `:117` (a single row too large for a
slab); `PRV-3001`'s "usually a batch far larger than expected" does not match the messages, which
point at `arena.slab.size`.

**Verdict:** PASS overall — the causes are in much better shape than the config keys — with the
`2020` join row logged as a FAIL-grade item because it tells users a supported join is refused.

### DOC-031 — The ranges table covers every range in use — **FAIL**
`TROUBLESHOOTING.md:16-25` lists `PRV-1xxx` … `PRV-8xxx`. The detail table at lines 261-267 documents
seven `PRV-9xxx` cluster codes. `ErrorCodeUniquenessTest.java:59` knows about `9, "cluster
coordination"`, so the summary table is the stale one.

**Verdict:** FAIL, low severity as a documentation bug — but the investigation turned up something
that is not a documentation bug and should not be lost:
`pravaha-api/…/ErrorCode.java:38-45` defines `Category` as CONFIGURATION 1000-1999 … PLUGIN 5000-5999,
**CLUSTER 6000-6999**, SECURITY 7000-7999. Cluster is mapped onto the Flight range, and **8xxx and
9xxx have no category at all**, so `ErrorCode.category()` throws `IllegalStateException` at line 69.
Its only caller is `ApiExceptionHandler.java:65`, so any registry (`8xxx`) or cluster (`9xxx`)
exception reaching the REST layer blows up inside the exception handler instead of rendering an
`ApiError`. Not mine to fix; flagging it for whoever owns the API surface.

### DOC-032 — `ErrorCodeUniquenessTest` exists and asserts what is claimed — **PASS**
`pravaha-it/src/test/java/com/ash/messaging/pravaha/it/ErrorCodeUniquenessTest.java`:
`noTwoFailuresShareACode()` (lines 61-76) scans every `*/src/main/*.java` for
`new ErrorCode\(\s*(\d+)\s*,\s*"([A-Z0-9_]+)"` and asserts no number maps to more than one name;
`everyCodeIsInAnAllocatedRange()` (78-89) asserts every code falls in an allocated thousand.
**Verdict:** PASS. One gap worth knowing: it catches *distinct names on one number*, so two constants
with the identical name and number in different modules would slip through. Currently moot.

---

## Group E — cross-document consistency

### DOC-033 — **"The server has no ingestion path at all" — FAIL, and it is the most damaging line in the documentation set**
Settled empirically by DOC-009: a stream declared under `pravaha.streams`, bound under
`pravaha.sources`, feeds a registered query, and the view answers with the right rows.

| Document | Says |
|---|---|
| `README.md:14-16` | "The `pravaha-server` process additionally has **no ingestion path at all**: a query registered against it never receives a row." |
| `docs/OPERATIONS.md:298-301` | "**What is still missing: nothing feeds it.** No source plugin is connected to a registered query, so rows arrive only from whatever calls `accept`" |
| `docs/QUICKSTART.md:133` | "With that file, the whole loop works from the command line" |
| `docs/HANDOVER.md:80-86` | "The server became a server on 2026-09-12 … the whole loop is verified end to end" |

**Verdict:** FAIL. The first two are false and the second two are true.

Severity: **critical, and above DOC-005 in business terms even though DOC-005 is the worse
engineering defect.** The false claim is in a blockquote labelled "**Read this before evaluating**",
in the first screen of `README.md`. Every evaluator reads it and most stop there. The repository's
central capability now works, and its front page tells people it does not.

`OPERATIONS.md:277-301` needs the same edit: the section is titled "One engine, and what the server
still lacks" and its conclusion — that connecting a source to a registration "is the remaining half
of making the server a stream processor" — is the half that just landed.

### DOC-034 — Metrics: does the endpoint exist? — **FAIL**
```
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18500/actuator/prometheus
404
$ curl -s http://127.0.0.1:18500/actuator/metrics | python3 -c "...names with 'pravaha'..."
NO pravaha meters
(server log) Exposing 3 endpoints beneath base path '/actuator'
```
against `PravahaMetrics.java:123-133`, which registers `pravaha.query.rows.in`,
`pravaha.query.view.size`, `.view.evicted`, `.view.updates`, `.view.removals`,
`pravaha.query.watermark.lag.seconds` and `pravaha.query.running` — i.e. the metric *names* in
`OPERATIONS.md:333-346` are right, modulo Micrometer's dot-to-underscore rendering.

**Verdict:** FAIL. Three documents give three answers:

- `OPERATIONS.md:331-350` — "Prometheus metrics are at `/actuator/prometheus`" with a table of
  metrics. **The endpoint 404s**; `application.yaml:28` asks for it but the Prometheus registry is not
  on the classpath, so only three actuator endpoints come up.
- `OPERATIONS.md:483` — "**No metrics endpoint.** Counters exist on objects; nothing scrapes them."
  Correct about the endpoint, wrong that nothing exists.
- `QUICKSTART.md:324` — metrics endpoint is Wave 9. Wrong; the meters are built.

And `OPERATIONS.md:47-51` **admits the contradiction in prose** — "which is the part that was added
later and left this sentence contradicting it" — rather than resolving it. That is the rot made
visible and then left in place, in the document an operator reads at 3 a.m. The honest state is: the
per-query meters exist and are correctly lifecycle-managed; there is no scrape endpoint; the meters
are not visible via `/actuator/metrics` either, because they are registered per query and there was
no query on that node.

### DOC-035 — Does the console exist, according to the documentation? — **FAIL**
| `README.md` | Says |
|---|---|
| line 29 | "Project status: Wave 7 of 10 — an engine with a client protocol; **no UI**, no clustering" |
| roadmap row, wave 7 | "🔨 in progress · **no console**" |
| line 55 | "The console exists and is a *functional admin* console on purpose" |
| "The console" section | a full feature table, route list and screenshot-level detail |

`console/` exists, installs, runs and serves every documented route (DOC-015).
**Verdict:** FAIL, moderate. One file, four statements, two of them false. The status line and the
roadmap row are the two an evaluator reads.

### DOC-036 — Four worked systems or five? — **FAIL**
```
$ ls examples/case-studies/ | grep -v '\.md'
banking-card-velocity  biology-sequencing-qc  finance-counterparty-exposure
trade-processing       trading-order-flow                    -> 5
```
Says **five**: `README.md` ("Five worked systems — trade processing, banking, finance, trading,
biology"), `console/tutorials` (renders 5), `QUICKSTART.md:245` ("The five worked systems").
Says **four**: `QUICKSTART.md:313`, `USER_GUIDE.md:259`, `CONCEPTS.md:186`, `docs/README.md:13`,
`examples/case-studies/SETUP.md:7` ("Two stores cover all four").
`examples/README.md` lists all five without stating a number, which is the only safe way to write it.

**Verdict:** FAIL, low severity individually. Recorded because five documents disagree with the
filesystem and with `README.md` on a fact that takes two seconds to check, and `trade-processing` —
the one that is missing from the count — is the study `examples/README.md` marks "**You are new.**"
It is the entry point, and half the documentation does not know it is there.

### DOC-037 — Wave and feature claims in `examples/` — **FAIL**
`examples/02-aggregate/README.md`, last line: "Windowing gives the bound, and **arrives in Wave 4**."
`README.md`'s roadmap marks Wave 4 built; `HANDOVER.md` says a windowed `GROUP BY` runs end to end;
`SQL_SUPPORT.md` lists `TUMBLE` and `HOP` as supported; `QUICKSTART.md` uses `TUMBLE` in its worked
example.
**Verdict:** FAIL, low severity. An example that tells the reader the fix for the error they just hit
is not built yet, when it is. The rest of that README is excellent and its refusal text matches the
engine's output exactly.

### DOC-038 — **HANDOVER's claim that the quickstart is test-enforced — FAIL**
> `HANDOVER.md:40` — "`docs/QUICKSTART.md` is accurate and every command in it is executed by
> `ExamplesTest`, so it cannot silently rot."

What the two tests actually do:
```
DocumentationFreshnessTest.java:211-218
    void theQuickstartExistsAndNamesRunnableCommands() {
        assertThat(quickstart).exists();
        assertThat(text).contains("./mvnw").contains("pravaha");
    }
DocumentationFreshnessTest.java:221-228
    void examplesReferencedByTheQuickstartArePresent() {
        assertThat(files.count()).as("examples/ must not be empty").isPositive();
    }
```
`ExamplesTest` runs examples 01 and 02 and four invocations of its own (`validate`, `explain`, a
mistyped column, a missing option). It never opens `QUICKSTART.md`. It does not execute the
quickstart's `run` command — the one with `--stream txn` and the two-column schema — which is why
DOC-005 and DOC-006 have been broken without the build noticing.

**Verdict:** FAIL, **and I would rank this second only to DOC-005 and DOC-033**, because it is the
reason those two exist. The quickstart's safety net is `.contains("./mvnw")`. A false claim of test
coverage is worse than no coverage: it is precisely what stops the next person checking, and it sits
in the handover document, which is the first thing the next session trusts.

`examples/README.md`'s narrower claim — "The commands in every `README.md` here are executed by
`pravaha-it`'s `ExamplesTest`" — is true for 01 and 02 and false for 03, whose commands are never run
(only the README's existence is asserted, `ExamplesTest.java:160-167`). Example 03 does work
(DOC-042), by luck rather than by test.

### DOC-039 — README's module list against the real module set — **FAIL**
```
modules in pom.xml never named in README.md:
pravaha-bom pravaha-catalog pravaha-state pravaha-backfill pravaha-security pravaha-serving
pravaha-cluster pravaha-registry pravaha-flight pravaha-benchmarks pravaha-it
pravaha-plugin-delta pravaha-plugin-feedfile pravaha-plugin-jdbc pravaha-plugin-aerospike
pravaha-cluster-zookeeper pravaha-sdk-java-flight
```
**Verdict:** FAIL, low severity. `README.md:291` presents an eleven-item list as "Modules currently
built"; there are 28 non-BOM modules. It omits `pravaha-flight`, `pravaha-security`,
`pravaha-registry` and `pravaha-serving` — four of the things the same README spends paragraphs
describing — and it names only one of five plugins, immediately after a sentence saying "Filesystem,
JDBC and Aerospike work now".

The freshness test is not violated: `everyMavenModuleIsDescribedInTheDocumentation` checks the whole
corpus, and all but `pravaha-bom` and `pravaha-benchmarks` are described *somewhere*
(`ARCHITECTURE.md` carries most of them). So `docs/README.md:47`'s "every module is described" is
true; the README's own list is just wrong, and the test was never meant to catch that.

---

## Group F — examples

### DOC-040 — `examples/01-filter-and-project` — **PASS**
```
$ pravaha run --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
    --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING' \
    --in examples/01-filter-and-project/transactions.csv --out .../big.csv \
    --out-schema 'user_id:STRING,amount:INT64'
ok  6 in, 3 out
  plan 597872 us, execute 24785 us
$ cat .../big.csv
alice,500
dave,150
frank,1200
```
**Verdict:** PASS, exact match with the README. Not vacuous: 6 rows in, 3 out, and the three excluded
are the two under the threshold plus the `PENDING` one, exactly as the README's closing line says.
This is what QUICKSTART step 2 was trying to be.

### DOC-041 — `examples/02-aggregate` — **PASS**
```
$ pravaha run --sql "SELECT COUNT(*), SUM(amount) FROM txn WHERE status = 'COMPLETED'" ... 
ok  4 in, 1 out
$ cat totals.csv
3,700
$ pravaha validate --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" --schema '...'
PRV-2050  GROUP BY user_id has no bound on its key space, so its state grows with the number of
distinct keys and never shrinks. One row per key is fine at a thousand keys and fatal at a hundred
million, and the failure arrives weeks after deployment.
  Bound it with a window -- GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id -- so state is
released when each window closes.
Refusing now rather than exhausting memory later.
```
**Verdict:** PASS on both commands. The refusal text matches the README **word for word**, which is
what `ExamplesTest.example02sRefusalIsRefusedForTheDocumentedReason` enforces, and it shows the
enforcement mechanism works where it is actually applied.

### DOC-042 — `examples/03-embedded-java` — **PASS**
```
$ CP=$(./mvnw -q -o -pl pravaha-embedded dependency:build-classpath -Dmdep.outputFile=/dev/stdout \
         -Dmdep.includeScope=runtime | grep -v '^\[' | tail -1)
$ CP="pravaha-embedded/target/classes:$CP"
$ javac -cp "$CP" -d /tmp/pravaha-example examples/03-embedded-java/Example.java
$ java -cp "$CP:/tmp/pravaha-example" Example
engine   : example-engine
state    : RUNNING
lanes    : 4
plugins  : []
second   : second-engine RUNNING
```
**Verdict:** PASS — five lines of output, identical to the README. The classpath incantation works as
printed (I substituted `-o` for offline and skipped the already-done `install`, per the shared
brief). Worth saying that this is the example most likely to break silently, since nothing runs it
(DOC-038).

### DOC-043 — `examples/README.md`'s build-enforcement claim — **FAIL**
Covered under DOC-038: true for 01 and 02, false for 03, and the claim is written as though it covers
all three ("The commands in every `README.md` here are executed by…").
**Verdict:** FAIL, low severity in isolation, but it is the second instance of the same habit — a
document asserting coverage broader than the test provides.

### DOC-044 — `examples/case-studies/` setup — **PASS**
`SETUP.md` is the strongest document in the repository for a naive reader. It states prerequisites
with install commands, gives the `--network host` warning with the *reason* and the symptom ("a
connection that succeeds and then hangs rather than an error that says so"), tells you how to check
port 3000 first, how to verify the container is up, what tool you will use, and how to wipe it. Every
`schema/streams.properties` it references exists in all five studies, and `CaseStudySqlTest` plans
every `.sql` against the real engine.
**Verdict:** PASS. Not executed end to end — standing up Aerospike and PostgreSQL is outside this
area's ports and would collide with other agents — so this is a documentation-completeness pass, not a
run. The one defect is the count: "Two stores cover all four" (line 7), and there are five.

---

## Group G — links, paths, structure

### DOC-045 — Every markdown link resolves — **PASS**
```
$ python3 <link checker over README.md, docs/**, docs/adr/**, examples/**, console/README.md>
BROKEN LINKS: 0
```
**Verdict:** PASS. Not vacuous — the checker resolved several hundred targets across 60+ files and
correctly flags a deliberately broken path when one is introduced. The freshness test
(`everyRepositoryPathADocumentPointsAtExists`) is evidently doing its job here.

### DOC-046 — Backticked repository paths resolve — **PASS**
Fifteen candidates flagged; all but one are false positives of the heuristic (method pairs like
`QueryExecution.checkpoint/restore`, MIME types, `ABS/FLOOR/CEIL/ROUND`, and other QA areas' case
files). The one real reference, `examples/case-studies/README.md:54`'s `schema/streams.properties`,
exists in all five studies.
**Verdict:** PASS.

### DOC-047 — Anchor links resolve — **PASS**
```
BAD ANCHORS: 0
```
Including the ones most likely to rot: `CONCEPTS.md#7-bounds-what-changes-the-answer-and-what-protects-the-machine`
(cited twice from QUICKSTART and once from USER_GUIDE) and
`SQL_SUPPORT.md#should-a-continuous-query-aggregate-at-all`.
**Verdict:** PASS.

### DOC-048 — ADRs indexed and cited ADRs exist — **PASS**
```
ADR files not linked in adr/README.md: none
cited ADRs with no file: none
ADR count: 34
```
**Verdict:** PASS in both directions. (`HANDOVER.md:21` still says 33 — see DOC-050.)

---

## Group H — onboarding and gaps

### DOC-049 — **Clone to a running query, using only the documentation — FAIL**
Walked as a competent engineer who has never seen the repository, following the documents in the
order they point at each other and typing only what is written.

| Step | What happens |
|---|---|
| Read `README.md` | Told in a "Read this before evaluating" box that a query registered against the server **never receives a row**. **Many readers stop here.** (DOC-033) |
| `README.md` "Try it" | `./mvnw -q -DskipTests install`, then `pravaha register --name user_volume --sql-file velocity.sql --keys 1`. Three failures in three lines: `pravaha` is not on `PATH` (the README never says to add `bin/`), `velocity.sql` does not exist and is never created, and no server has been started |
| Follow to `QUICKSTART.md` §1 | Works. `PATH`, `--help`, the `JAVA_HOME` warning are all correct and the container alternative works (DOC-004) |
| §2, the first real command | **Fails.** `--out-schema is required` (DOC-005). **Stuck, with nothing in the document to recover from.** A reader must find `examples/01-filter-and-project/README.md` on their own to learn the working form |
| §3 | Fails the same way, and the lesson is lost (DOC-006) |
| §4, start a server | Works, and the `dev` profile explanation is good (DOC-007) |
| §4, configuration | No file is ever named or created; two YAML blocks both starting `pravaha:` must be merged and the document does not say so; the second server collides with the first; the `path:` points at a directory that does not exist (DOC-010) |
| §4, register `velocity.sql` | **Fails.** The schema the same step declares has no `event_time` (DOC-011). Steps 5, 6 and 8 all name `user_volume` and are now unreachable |
| §6, subscribe | Would show nothing, and the document says that is expected and tells you to send a late event — which the source it just configured cannot accept (DOC-014) |
| §7, console | `python run_pravaha_web.py` — command not found (DOC-015). Everything else works |

**Verdict:** FAIL. **A reader following only the documentation does not get to a running query.**
They are stopped at the second command of the quickstart and, if they push past it, stopped again at
the first registration. Both stops are inside the section rewritten this week.

What is *not* wrong is the writing. The explanations are unusually good — why the `dev` profile
exists, why streams and sources are separate blocks, why a window does not close, why a refusal is
the feature. The prose is ahead of the commands, and that is a worse failure mode than bad prose,
because a reader trusts a document that explains itself well and blames themselves when it does not
work.

### DOC-050 — What an operator needs and cannot find — **FAIL**
| Need | State |
|---|---|
| **Security configuration** | **Gap, severe.** `SECURITY.md` names no `pravaha.security.*` key at all (DOC-025). The only prose is a QUICKSTART aside, and the configuration it shows would not start a server in this session (DOC-008). `pravaha.security.audit` is documented nowhere |
| **Source binding** | Covered in QUICKSTART §4 only, and that section is broken (DOC-010, DOC-011). **`OPERATIONS.md` — where an operator would look — still says nothing feeds a registered query** (DOC-033). The plugin option keys (`path`, `schema`, `delimiter`, `url`, `table`) are documented nowhere; the one worked example is a comment in `application.yaml` |
| **Checkpoint / journal operations** | Well covered — `OPERATIONS.md` "Restarts: what survives" and "Checkpoints: what is actually true" are genuinely useful, including what is *not* recovered. But the headline disk warning is wrong: "`FileCheckpointStore.prune(keep)` exists and **nothing calls it automatically**" (`OPERATIONS.md:55`, repeated at `HANDOVER.md:414` and `TROUBLESHOOTING.md:143`) is false — `PeriodicCheckpointer.java:149` calls `store.prune(keep)` on its scheduled run, wired in production at `QueryRegistry.java:388-395`. Three documents tell an operator to prune on a schedule they do not need |
| **Upgrade** | Thin but honest: blue/green for a query, stop-and-start for a node. No journal-format compatibility guidance beyond `PRV-8005`, and no statement of whether a checkpoint survives a version change |
| **Backup** | **Gap.** "Checkpoints are files; recovery restores from the newest complete one." No procedure: what to copy, whether it is safe to copy a live checkpoint directory, how to restore onto a new node, how to verify a restore. The three files that hold customer data are listed under a *permissions* heading, not a backup one |
| **Monitoring** | **Gap, and actively misleading.** The scrape endpoint 404s while the document says it is there (DOC-034), one section says metrics do not exist, and a third admits the two contradict. An operator cannot build a dashboard from this page |
| **Capacity planning** | Present and good — `rows ≈ arrival rate × retention × distinct keys`, with the right framing that a disagreement between retention and ceiling is a product question. What is missing is any number to plan *with*: no default ceilings are stated, no memory-per-row figure, and the document says plainly that no performance evidence exists |
| **What to do when a query fails** | **Gap.** `TROUBLESHOOTING.md` covers "nothing is happening", "the numbers are wrong" and OOM well. There is nothing on a query in a **terminal state**: what `pravaha queries` shows, whether it restarts, whether `PRV-8004 REGISTRY_QUERY_FAILED` is recoverable, whether to drop and re-register, and what happens to the view's contents meanwhile. `PRV-8004` appears in the code table with no prose anywhere |
| **Watermark tuning** | **Gap created by DOC-024.** The page explains the two settings better than most vendors do, and neither key is read on the server path |

**Verdict:** FAIL. Four real gaps (security configuration, backup procedure, monitoring, query
failure), two of them in areas where the documentation is not merely silent but wrong.

---

## Summary

| Verdict | Count |
|---|---|
| PASS | 21 |
| FAIL | 28 |
| BLOCKED | 1 |
| NOT RUN | 0 |
| **Total** | **50** |

PASS: 002, 003, 004, 007, 009, 012, 014, 015*, 016, 017, 018, 030*, 032, 040, 041, 042, 044, 045, 046, 047, 048
(*DOC-014 and DOC-015 pass on the command surface with defects recorded in their entries; DOC-030
passes overall with the `PRV-2020` join row logged as FAIL-grade. DOC-020 is counted as FAIL.)

FAIL: 001, 005, 006, 010, 011, 013, 019, 020, 021, 022, 023, 024, 025, 026, 027, 028, 029, 031, 033,
034, 035, 036, 037, 038, 039, 043, 049, 050
BLOCKED: 008

### What I could not cover, and why

**The documented production security configuration.** DOC-008 is the one case I could not settle. The
keys all bind and the node echoes them back correctly, but the server refuses to start — and so does
the plain `dev` profile, which had started twenty minutes earlier. I cannot separate "the documented
configuration is wrong" from "nothing starts right now", and I will not guess. Whoever fixes
`PravahaFlightServer.requireOnePolicy` should re-run DOC-008 as written; it is four lines of YAML.

**Token authentication, TLS and row filters end to end.** Same blocker, and they belong to the SEC
area anyway. `SECURITY.md` is therefore assessed for completeness (DOC-025) but not for accuracy — I
have not proven that what it describes behaves as described.

**The case studies as runnable systems.** DOC-044 is a read, not a run. Standing up Aerospike and
PostgreSQL means containers on fixed ports (3000, 5432) that I cannot claim without colliding with
other agents. `SETUP.md` is convincing and `CaseStudySqlTest` plans every statement, but nobody in
this run has typed the commands in a case-study README end to end.

**The console under load, and its own test suite.** I started it, probed every documented route and
confirmed the keyboard paths in source, but did not run `make test` (it starts a real server from the
Maven build, which would have collided) and did not exercise register/pause/drop through the UI.

**`docs/system_design.md` and `docs/implementation_plan.md` were only spot-checked.** At 3959 and 996
lines they are out of proportion to this area's time budget, and they are linked as specifications
rather than instructions. What I found there (DOC-020) suggests a full pass would be worth somebody's
day: they document a CLI, a configuration model and a config file that do not exist, and the
user-facing index links them with no marker saying so.

**Windowed continuous queries on a server.** Blocked twice over — by the missing `event_time` in
QUICKSTART's own schema (DOC-011) and by the inert watermark configuration (DOC-024). I could not
demonstrate a tumbling window closing on the server path, so nothing in this log confirms or refutes
the quickstart's §6 narrative beyond showing that the source it configures cannot produce the event
the document asks for.

**One thing I deliberately did not do:** re-run the build. The shared brief forbids a full `install`,
and the regression described in the banner arrived without my touching anything. Everything in Group
A that ran green ran against the 20:22 artefact, and I have said so at each case rather than
re-testing against an artefact that no longer starts.
