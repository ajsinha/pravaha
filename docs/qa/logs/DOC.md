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

---

# Re-QA 2026-09-12

Second pass, same area, same ports (HTTP 18500–18508, Flight 19500–19508), scratch
`/tmp/.../scratchpad/qa-doc2`. Six server PIDs started, all killed by number. Artefacts taken as
built (`ab0eca3`); nothing rebuilt except one `dependency:build-classpath` for a TLS probe.

**The finding that frames everything below:**

```
$ git diff --stat 6030cd5..HEAD -- docs/ README.md
 docs/qa/cases/*.md  docs/qa/logs/*.md   (10 files, the QA record itself)
```

**Not one line of prose changed.** `README.md`, `QUICKSTART.md`, `OPERATIONS.md`, `SECURITY.md`,
`SQL_SUPPORT.md`, `TROUBLESHOOTING.md`, `HANDOVER.md` and `console/README.md` are byte-identical to
what the first pass assessed. Every FAIL that was purely a documentation defect therefore stands by
construction, and I have re-executed the ones with a runtime component rather than assuming.

The code, meanwhile, moved a long way. That asymmetry is the whole story of this pass: **the
remediation fixed the engine and left the documentation describing the broken one.** Eleven of my
twenty-eight FAILs are now *worse* than when I filed them, because the document is no longer merely
unhelpful — it is the only thing standing between a reader and a feature that now works.

---

## Part 1 — DOC-008, the case that was BLOCKED

### DOC-008 — The documented `pravaha.security` block — **PARTIALLY FIXED**

The startup blocker is gone. The documented block, copied verbatim from `QUICKSTART.md:104–113`
with only the two TLS paths repointed at files I own, starts a server:

```
$ pravaha-server --spring.config.additional-location=file:./application.yaml \
                 --server.port=18505 --pravaha.flight.port=19505
... PravahaNode : security: authentication=token, policy=authenticated, audit=none, flight transport=TLS
... PravahaNode : watermarks: idle-after=PT30S, tick=PT1S
... PravahaNode : Flight SQL listening on 0.0.0.0:19505
... Started PravahaServerApplication in 23.77 seconds
```

No `PRV-7002`. The `dev`-profile control starts too. The regression described in the first log's
banner is gone.

**Token authentication works, and works exactly as documented.** Against a second node with the same
security block and no TLS (18506/19506):

```
$ pravaha queries --url grpc://localhost:19506
PRV-1041  PRV-7001  this server requires a credential: send it as the header 'authorization: Bearer <token>'
$ pravaha queries --url grpc://localhost:19506 --token a-long-random-string
no continuous queries are registered
$ pravaha queries --url grpc://localhost:19506 --token nope
PRV-1041  PRV-7001  the credential presented was not accepted
$ curl -s http://127.0.0.1:18506/api/v1/streams
{"code":"PRV-7001","message":"this server requires a credential; send it as 'Authorization: Bearer <token>'",
 "helpUrl":"https://docs.pravaha.io/errors/PRV-7001","timestamp":"...","path":"/api/v1/streams","status":401}
```

Not vacuous: the same call with a valid token succeeds, and a wrong token gives a *different*
message from a missing one, which is the distinction `TROUBLESHOOTING.md:73` says is deliberate.
The 401 body now carries the full `ApiError` field set, as claimed.

**TLS works on the wire.** A real handshake, with ALPN:

```
$ echo | openssl s_client -connect 127.0.0.1:19505 -alpn h2
subject=CN=localhost
Protocol: TLSv1.3
ALPN protocol: h2
```

**And no shipped Pravaha client can use it.** See DOC-053 — this is the reason the verdict is
PARTIAL and not VERIFIED-FIXED. The documented production configuration produces a node that
`pravaha` cannot talk to.

Two further things a reader copying the block verbatim now meets, in the order they meet them:

```
$ # QUICKSTART lines 104-113 EXACTLY, on a machine with no /etc/pravaha
$ pravaha-server --spring.config.additional-location=file:./application.yaml ...
Caused by: PravahaException: PRV-6104  the TLS certificate /etc/pravaha/tls.crt is not a readable file
```

An excellent message for a code that `TROUBLESHOOTING.md` does not list, on a page that says a code
missing from it does not exist (DOC-029, DOC-060). The quickstart never tells the reader to create
those files, or that they may drop the `flight.tls` block.

---

## Part 2 — the twenty-eight FAILs

| Case | Verdict | Note |
|---|---|---|
| DOC-001 prerequisites | STILL FAILING | document unchanged |
| **DOC-005** quickstart §2 | **STILL FAILING** | re-run verbatim; identical output |
| **DOC-006** quickstart §3 | **STILL FAILING** | re-run verbatim; identical output |
| **DOC-010** §4 never names the file | **STILL FAILING, now worse** | the block it tells you to write is now *missing a key* as well (DOC-051) |
| **DOC-011** no `event_time` in §4's schema | **STILL FAILING, and promoted to the worst defect in the set** | see below |
| DOC-013 §5 unreachable | STILL FAILING | consequence of DOC-011 |
| DOC-019 "what is not built" | STILL FAILING, now wrong in a third way | meters are visible on `/actuator/metrics` (below) |
| DOC-020 `system_design.md` linked unmarked | STILL FAILING | unchanged |
| DOC-021 `--help` omits `register --sql`; dead `docs.pravaha.io` | STILL FAILING | `PravahaCli.java:124` still prints `--sql-file` only; every error still ends in the NXDOMAIN link |
| DOC-022 `explain` without `--schema` | STILL FAILING | re-run; `--schema is required. Supplied: [sql]` |
| DOC-023 `/actuator/prometheus` | STILL FAILING | re-curled: `404` |
| **DOC-024** documented keys that are inert | **PARTIALLY FIXED** | 2 of 3 watermark keys now read; the third is still inert **and is the only one any document names**. See DOC-052 |
| DOC-025 `SECURITY.md` names no `pravaha.security.*` key | STILL FAILING | `grep -c 'pravaha.security' docs/SECURITY.md` → `0` |
| DOC-026 `OPERATIONS.md:325` port 8815 | STILL FAILING | unchanged |
| DOC-027 javadoc names keys that do not exist | PARTIALLY FIXED | `StreamSchema.java:51` still names `pravaha.watermark.out-of-orderness`, which still has no reader |
| DOC-028 `console/README.md` names no env var | STILL FAILING | `grep -c CONSOLE_PASSWORD console/README.md` → `0` |
| DOC-029 ten undocumented codes | STILL FAILING, now twelve | all ten still absent; `PRV-6104` now reachable on the documented path, and two codes gained new meanings (DOC-054, DOC-055) |
| DOC-031 ranges table missing `9xxx` | STILL FAILING | unchanged |
| **DOC-033** "no ingestion path at all" | **STILL FAILING, and now false twice over** | see DOC-057 |
| DOC-034 metrics documented three ways | STILL FAILING, now four ways | below |
| DOC-035 console: four statements, two false | STILL FAILING | `README.md:29`, `:306` unchanged |
| DOC-036 four studies or five | STILL FAILING | five on disk; six documents still say four |
| DOC-037 `examples/02` "arrives in Wave 4" | STILL FAILING | unchanged |
| **DOC-038** HANDOVER's test-coverage claim | **STILL FAILING** | `HANDOVER.md:57–58` verbatim unchanged; `ExamplesTest` still names `QUICKSTART.md` only in a comment (`:41`) |
| DOC-039 README module list | STILL FAILING | unchanged |
| DOC-043 `examples/README.md` coverage claim | STILL FAILING | unchanged |
| DOC-049 clone-to-running-query | STILL FAILING | re-walked; the stops moved, see Part 4 |
| DOC-050 operator gaps | STILL FAILING, one gap closed in code only | watermark tuning is now half-real and wholly undocumented |

**Verified fixed: 0 of 28.** Partially fixed: 2 (DOC-024, DOC-027), and in both cases the fix
landed in the code while the document kept pointing at the part that did not move.

### DOC-005 / DOC-006 — re-run verbatim

```
$ cd examples/01-filter-and-project
$ pravaha run --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
              --schema "user_id:STRING,amount:INT64" --stream txn --in transactions.csv --out out.csv
--out-schema is required. Supplied: [sql, schema, stream, in, out]
$ pravaha run --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" \
              --schema "user_id:STRING,amount:INT64" --stream txn --in transactions.csv --out out.csv
--out-schema is required. Supplied: [sql, schema, stream, in, out]
```
**Verdict: STILL FAILING, unchanged, critical.** The first two commands a new user types still do
not run. Four weeks of engine work went by and the five-line fix did not.

### DOC-024 — two of three watermark keys now read; the documented one is not

`PravahaNode.java:372` now calls `registry.generatingWatermarks(watermarkIdleAfter, watermarkTick)`
and logs it. `idle-after` and `tick` are live. **`pravaha.watermark.out-of-orderness` still has no
reader**, and it is the *only* one of the three that `CONCEPTS.md` and `OPERATIONS.md` name as a
thing to set. Proven both directions in DOC-052.

### DOC-033 — the README claim, re-checked

`README.md:14–15` is unchanged. `OPERATIONS.md:298` is unchanged. `HANDOVER.md:394` carries a third
copy ("`RegisteredQuery.accept` … called from four test classes and from nothing in
`pravaha-server`"). All three are false, and I demonstrated the refutation again this pass with a
windowed query (Part 3). The blockquote is still the first screen of the front page.

### DOC-034 — metrics, now documented four ways

```
$ curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18501/actuator/prometheus
404
$ curl -s http://127.0.0.1:18501/actuator/metrics | ... names containing 'pravaha'
['pravaha.query.rows.in', 'pravaha.query.running', 'pravaha.query.view.evicted',
 'pravaha.query.view.removals', 'pravaha.query.view.size', 'pravaha.query.view.updates',
 'pravaha.query.watermark.lag.seconds']
```
**New information since the first pass:** the meters *are* exposed on `/actuator/metrics` once a
query is registered — my first run found none because no query existed on that node, which I
recorded at the time. So `OPERATIONS.md:483` ("**No metrics endpoint.** Counters exist on objects;
nothing scrapes them") is wrong on both halves now, `OPERATIONS.md:333` is still wrong about
`/actuator/prometheus`, `OPERATIONS.md:51` still says Micrometer is Wave 9 work, and
`QUICKSTART.md:324` still files the metrics endpoint under Wave 9. Four statements, one true.

---

## Part 3 — QUICKSTART end to end, walked literally, now that windowing works

**Windowing genuinely works from configuration.** This is the largest thing that changed, and I
proved it with hand-checkable arithmetic.

Source, seven rows, an `event_time` column in epoch nanoseconds, two complete one-minute windows and
one row far in the future to advance the watermark past them:

```
1,alice,500,COMPLETED,1767261605000000000      10:00:05
2,bob,50,COMPLETED,1767261610000000000         10:00:10
3,alice,100,COMPLETED,1767261620000000000      10:00:20
4,carol,900,PENDING,1767261630000000000        10:00:30   <- excluded by WHERE
5,alice,250,COMPLETED,1767261670000000000      10:01:10
6,bob,75,COMPLETED,1767261680000000000         10:01:20
7,alice,1,COMPLETED,1767262000000000000        10:06:40   <- advances the watermark
```

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time          # <- NOT IN ANY DOCUMENT
      out-of-orderness: 5s            # <- NOT IN ANY DOCUMENT
  sources:
    txn: { plugin: filesystem, options: { path: .../txn2.csv, schema: "...same..." } }
```

```
$ pravaha-server --spring.config.additional-location=file:./application.yaml --spring.profiles.active=dev ...
... sources bound: [txn <- filesystem[event.time, path, schema]]
... watermarks: idle-after=PT30S, tick=PT1S

$ pravaha register --name uv4 --sql-file velocity.sql --keys 0,1     # velocity.sql VERBATIM from QUICKSTART:153-162
registered uv4  state=RUNNING  fingerprint=8a86337b7b50

$ pravaha query --sql "SELECT * FROM uv4"
window_end             user_id  txn_count  total
1767261660000000000    alice    2          600
1767261660000000000    bob      1          50
1767261720000000000    alice    1          250
1767261720000000000    bob      1          75
4 rows
```

**Proof it is not vacuous.** Every number is hand-checkable and every one is right. Window ending
10:01:00 holds alice's 500+100=600 over two rows and bob's 50 over one; window ending 10:02:00 holds
alice's 250 and bob's 75. Carol's `PENDING` row is absent from both, which is the `WHERE` clause
working. The 10:06:00 window containing row 7 has **not** emitted, because no row advances the
watermark past its end — which is the engine being correct, and is exactly the behaviour
`QUICKSTART.md:197` describes in prose and could not previously demonstrate. A broken watermark
would have produced zero rows; a broken filter would have produced 900 somewhere; a broken window
assignment would have put row 5 in the first window.

### Where a new reader now succeeds or fails, step by step

| Step | Outcome |
|---|---|
| §1 build, `PATH`, `--help`, container | **Works.** Unchanged and correct |
| §2 first `run` | **FAILS.** `--out-schema is required` (DOC-005). Nothing in the document recovers from it |
| §3 the refusal demo | **FAILS** identically, and the lesson is lost (DOC-006) |
| §4 start a server, `dev` profile | **Works** |
| §4 the production security block | **Starts now** — a real advance — but gives `PRV-6104` on a machine with no `/etc/pravaha`, and once certs exist, **no `pravaha` command can reach the node** (DOC-053) |
| §4 the `streams`/`sources` block | **Still never named as a file**, two YAML documents both starting `pravaha:` still have to be merged with no instruction, `path:` still points at `/var/lib/pravaha/incoming/txn.csv`, second `pravaha-server` still collides with the first (DOC-010) |
| §4 register `velocity.sql` | **FAILS.** Re-run against the document's own schema, on the current build: |

```
$ # QUICKSTART lines 120-131 verbatim (path repointed), velocity.sql verbatim from 153-162
$ pravaha register --name user_volume --sql-file velocity.sql --keys 1 --url grpc://localhost:19502
PRV-1041  PRV-2002  Column 'event_time' not found in table 't'. Known streams: [txn]
```

| Step | Outcome |
|---|---|
| §5 `query --params` | Unreachable |
| §6 `subscribe` | Unreachable; and the filesystem source still does not tail, so the document's "send an event past the window's end" is still impossible with the source it configures (DOC-014's caveat) |
| §7 console | `python run_pravaha_web.py` — still `command not found`; `QUICKSTART.md:229` and `console/README.md:21` unchanged |
| §8 `drop` | Unreachable |

**Verdict on DOC-011: STILL FAILING, and I am raising it to the single most damaging defect in this
area, above DOC-005.** The reasoning has changed. When I filed it, the missing `event_time` was one
of two things blocking a windowed query, and the other one — the inert watermark configuration —
was in the engine. That one is fixed. Windowing now works, from a configuration file, with correct
answers. The *only* thing between a new reader and the engine's headline feature is
`QUICKSTART.md:124`, which declares a schema with no `event_time` column, thirty lines above a query
that groups on `t.event_time`. Two words of YAML and one column in a string.

---

## Part 4 — new defects

Numbered continuing the original sequence.

### DOC-051 — **`pravaha.streams.<n>.event-time` is documented nowhere, and nothing works without it** — FAIL

```
$ grep -rn 'event-time' README.md docs/QUICKSTART.md docs/OPERATIONS.md docs/CONCEPTS.md \
        docs/USER_GUIDE.md docs/SECURITY.md docs/HANDOVER.md console/README.md \
        pravaha-server/src/main/resources/application.yaml
(no match — the only hits in docs/ are prose uses of the phrase "event-time streaming")
```

The key is real and is the load-bearing one. `StreamDeclarationProperties.Declaration.getEventTime`
says so in its own javadoc:

> Without it no watermark can advance, and without a watermark no window ever closes: a windowed
> query plans, registers, reports RUNNING, ingests every row and emits nothing, for ever.

`PravahaNode.java:250` calls `builder.eventTime(column)`; `:277` forwards it to the source as
`event.time`, which the startup log prints (`sources bound: [txn <- filesystem[event.time, path,
schema]]`). It is, on this evidence, the most important configuration key in the product.

Every place a reader would look has a `streams:` example **without it**: `QUICKSTART.md:120–124`,
and the commented block at `application.yaml:70–73`, which shows `schema:` and stops.

**Severity: critical.** The failure mode is the one the javadoc names — RUNNING, rows ingested,
nothing emitted, no error, for ever — and it is the worst shape a defect can take for a user,
because there is nothing to search for. A reader who somehow gets past DOC-011 by adding an
`event_time` column to the schema (the obvious fix, and the one the error message suggests) lands
straight in it, because declaring the column is not the same as marking it.

### DOC-052 — **The watermark key every document names is inert; the one that works is documented nowhere** — FAIL

Two nodes, identical but for one line, same source, same query, same 10-minute lateness:

```
A)  pravaha.watermark.out-of-orderness: 10m       <- the DOCUMENTED key
    $ pravaha query --sql "SELECT * FROM w_global"
    1767261660000000000  alice  2  600
    1767261660000000000  bob    1  50
    1767261720000000000  alice  1  250
    1767261720000000000  bob    1  75
    4 rows                                        <- windows closed; the key did NOTHING

B)  pravaha.streams.txn.out-of-orderness: 10m     <- the UNDOCUMENTED key
    $ pravaha queries
    w_stream  RUNNING  8a86337b7b50  7            <- all seven rows ingested
    $ pravaha query --sql "SELECT * FROM w_stream"
    0 rows                                        <- watermark held back; the key WORKED
```

This is the falsification the first pass could not run. With ten minutes of allowed lateness, the
last event at +400s cannot advance the watermark past a window ending at +120s, so a node that
honoured the setting must emit nothing. (A) emitted everything; (B) emitted nothing. The key that
`CONCEPTS.md:66` tells you to move, that `OPERATIONS.md:222` documents with an inline comment, that
`StreamSchema.java:51` names in javadoc, and that ships in `application.yaml:167` under the best
explanatory comment in the repository, **still has no reader anywhere in main sources.**

**Severity: critical, and this is the finding I would fix first after DOC-011.** An operator
following `OPERATIONS.md` sets `pravaha.watermark.out-of-orderness`, gets no error, gets no warning
in the startup log — which now prints `watermarks: idle-after=PT30S, tick=PT1S` and conspicuously
omits out-of-orderness — and believes lateness is configured. It is not. The remediation made this
strictly worse: it introduced a *working* key whose name differs from the inert one only by where it
sits in the tree, documented neither, and left the inert one shipping in the config file.

### DOC-053 — **No shipped Pravaha client can connect to a TLS node; the netty fix went to the server only** — FAIL

The TLS fix is real on the server: `openssl s_client` completes a TLSv1.3 handshake with ALPN `h2`
against port 19505. Every Pravaha client fails:

```
$ pravaha queries --url grpc+tls://localhost:19505 --token a-long-random-string
PRV-1041  io exception
Channel Pipeline: [ProtocolNegotiators$ClientTlsHandler#0, WriteBufferingAndExceptionHandler#0, ...]
```

Not a trust problem. A probe built on the shipped SDK's own classpath, passing the server's
certificate as an explicit trust anchor via `FlightClient.builder(...).trustedCertificates(...)`:

```
Caused by: javax.net.ssl.SSLHandshakeException: SslHandler removed before handshake completed
```

That is verbatim the symptom `pravaha-server/pom.xml` now describes in its own comment:

> gRPC's SSL handler reaches `io.netty.channel.unix.UnixChannel`, which lives in this artifact and
> in no other netty jar on this classpath. Without it a TLS node starts, logs that it is serving
> TLS, binds its port, and answers nobody.

```
$ unzip -l pravaha-server/.../-app.jar | grep -c netty-transport-native-unix
1
$ unzip -l pravaha-cli/.../-cli.jar | grep -c 'io/netty/channel/unix'
0
```

Proof by adding the one jar to the probe's classpath and changing nothing else:

```
$ java -cp "netty-transport-native-unix-common-4.1.135.Final.jar:$CP:probe" TlsProbe 19505 tls.crt <token>
org.apache.arrow.flight.FlightRuntimeException: UNIMPLEMENTED: Not implemented.
```

`UNIMPLEMENTED` is the *server answering* — the handshake completed and `listFlights` reached the
producer. One missing runtime dependency, present in `pravaha-server` and absent from `pravaha-cli`
and `sdk/pravaha-sdk-java-flight`.

**Severity: critical.** `QUICKSTART.md:111–112` and `USER_GUIDE.md:39`
(`pravaha queries --url grpc+tls://pravaha:9090 --token "$PRAVAHA_TOKEN"`) both tell an operator to
run TLS. Following them produces a node that the CLI, the Java SDK and therefore the console cannot
reach, with a message — "io exception" — that names nothing and points nowhere. The comment in
`pravaha-server/pom.xml` predicts the consequence exactly: *"The only way back to a working
deployment is to switch TLS off, which is how a fleet ends up authenticated over plaintext."* That
sentence is now true of the client side.

Related documentation gap: neither the SDK nor the CLI has **any** way to supply a trust anchor —
`PravahaFlightClient.java:145` is a bare `FlightClient.builder(allocator, location).build()`, with no
`trustedCertificates`, no `verifyServer`, no `--cacert`. No document says a Pravaha client requires
a certificate chaining to the JDK default trust store, because nobody has been able to get that far.

### DOC-054 — **The new `authenticated` + `none` refusal is undocumented, and `PRV-7002` now means three unrelated things** — FAIL

```
$ cat application.yaml
pravaha: { security: { authentication: none, policy: authenticated } }
$ pravaha-server --spring.config.additional-location=file:./application.yaml ...
Caused by: PravahaException: PRV-7002  pravaha.security.policy=authenticated with
pravaha.security.authentication=none is a node nobody can use: the policy serves only verified
callers and nothing here can verify one. Set pravaha.security.authentication=token and configure
pravaha.security.tokens, or choose a policy that admits anonymous callers.
  at PravahaNode.refuseAccidentalOpenServer(PravahaNode.java:182)
```

The refusal is right and the message is excellent. It appears in **no document**:

```
$ grep -rn 'authentication: none\|nobody can use' README.md docs/*.md console/README.md
(no match)
```

`application.yaml:75–92` is where the three supported ways to close a server are written down, and
it still lists `policy: authenticated` as a standalone option with no mention that it is refused
unless authentication is configured.

`PRV-7002` now carries three unrelated failures: `SECURITY_FORBIDDEN` at runtime (authenticated but
not authorized), the `requireOnePolicy` startup check, and this new startup check. The documentation
covers only the first:

```
docs/TROUBLESHOOTING.md:78  | PRV-7002 | Authenticated, not authorized | Ask for access — a new credential will not help |
docs/SECURITY.md:119        | PRV-7002 | Authenticated, not authorized | Ask for access |
```

**Severity: high.** An operator whose node will not start looks up `PRV-7002` and is told to ask
somebody for access. `TROUBLESHOOTING.md:73` even has a section titled "`PRV-7001` vs `PRV-7002` —
told apart deliberately", which now tells them apart incorrectly.

### DOC-055 — **Aggregates over floating-point columns are refused; `SQL_SUPPORT.md` says they work** — FAIL

```
$ pravaha validate --sql "SELECT SUM(price) FROM txn" --schema 'id:INT64,price:FLOAT64' --stream txn
PRV-2020  SUM(price) is over a FLOAT64 column, and this engine's aggregates accumulate in 64-bit
integers only. It is refused rather than answered, because the alternative was no rows and a
successful status. Cast the column to an integer if the rounding is acceptable --
SUM(CAST(price AS BIGINT)) -- or aggregate it outside the engine.

$ pravaha validate --sql "SELECT MIN(price) FROM txn" --schema 'id:INT64,price:FLOAT64' --stream txn
PRV-2020  MIN(price) is over a FLOAT64 column, ...

$ pravaha validate --sql "SELECT COUNT(price) FROM txn" --schema 'id:INT64,price:FLOAT64' --stream txn
valid                                    <- COUNT is exempt
$ pravaha validate --sql "SELECT SUM(id) FROM txn" --schema 'id:INT64,price:FLOAT64' --stream txn
valid                                    <- the control: INT64 still works
```

Against `docs/SQL_SUPPORT.md`:

| Line | Says | Now |
|---|---|---|
| `:112` | `` `COUNT`, `SUM`, `MIN`, `MAX`, `AVG` `` ✅, caveat column **empty** | false for FLOAT32/FLOAT64 |
| `:158–159` | over a view, "`KeyedAggregate` answers it — including `SUM`, `MIN`, `MAX`, `AVG`" | same refusal; it is a plan-time guard |
| `:50` | "Integer and floating arithmetic — `amount * 2 + 1`, `price / 2`" ✅ | still true of arithmetic, and now actively misleading beside `:112` |
| `:51` | `CAST(x AS DOUBLE)` ✅ | true, and the refusal's own advice is to cast the *other* way |

**Severity: high.** `SQL_SUPPORT.md` is the document whose entire job is to be the truth table, and
this is a whole column type silently promoted from ✅ to ❌. The natural first query against a price,
a rate, a latency or a temperature now fails, and the reference says it should not. `PRV-2020` also
gains a meaning: the ten `SQL_SUPPORT.md` rows that cite it are all relational-operator refusals, and
a reader looking up `PRV-2020` after a `SUM` finds a list about joins and `ORDER BY`.

Minor, inside the message: `MIN(price)` and `MAX(price)` are told to fix it with
`SUM(CAST(price AS BIGINT))`.

### DOC-056 — **`SecurityPolicy.mayAdminister` is new public API and appears in no document** — FAIL

```java
// SecurityPolicy.java, new in ab0eca3
default AccessDecision mayAdminister(Principal principal, String view) {
    return mayRead(principal, view);
}
```

`docs/SECURITY.md` documents a two-verb policy: `mayRead` and `mayRegisterQuery`. Line 38 defines
the authorization seam as answering "**What may they read?**", which is now incomplete — the policy
also decides who may `DROP`, `PAUSE` and `RESUME`. `ARCHITECTURE.md:473` describes `SecurityPolicy`
as "`(Principal, view)` in, an `AccessDecision` out" for reads only.

Worse, `SECURITY.md:73` is a blockquote written specifically to warn about this class of mistake:

> **A lambda does not override `mayRegisterQuery`.** `SecurityPolicy` is a functional interface on
> `mayRead`, so `(principal, view) -> allow()` keeps the default…

The warning is now incomplete in the dangerous direction. `(principal, view) -> allow()` also grants
`mayAdminister` to everyone who can read, and `mayAdminister` destroys accumulated state and takes a
view away from every other client holding a name for it. The javadoc says exactly this and says a
deployment separating operators from readers "should override this and say so" — advice that is
unreachable from the documentation.

Compounding it: `QUICKSTART.md:110` prints `roles: [reader]` in the production security block, and

```
$ grep -rn 'roles()' --include=*.java pravaha-*/src/main sdk/*/src/main
(no match outside Principal.java itself)
```

Nothing reads a principal's roles. The token labelled `reader` may drop every query on the node.
`SECURITY.md:198` does say "there are no roles", two documents away from the block that shows one.

**Severity: high**, and it is the kind of gap that produces an outage rather than an error.

### DOC-057 — **The remediation made `README.md`'s opening blockquote false a second time** — FAIL

```
README.md:12-16
> **Read this before evaluating.** Today Pravaha is a **bounded-input** engine: windows close when
> the input ends, because nothing generates watermarks — the embedding application supplies them or
> they do not advance. ... Over an unbounded stream, no window would close. The `pravaha-server`
> process additionally has **no ingestion path at all**: a query registered against it never
> receives a row.
```

Three claims, all false as of `ab0eca3`:

1. "nothing generates watermarks" — `PravahaNode.java:372` calls
   `registry.generatingWatermarks(idleAfter, tick)`, logged at startup as
   `watermarks: idle-after=PT30S, tick=PT1S`.
2. "no window would close" over an unbounded stream — Part 3 closed two, from a configured source,
   with correct sums, while a third stayed open because its watermark had not arrived.
3. "no ingestion path at all" — DOC-033, refuted twice now.

**Severity: critical, and it is the highest-leverage single edit available in this repository.** It
is the first screen of the front page, inside a box labelled "Read this before evaluating", and the
sentences describe an engine two releases old. `HANDOVER.md:394` carries the same claim in a
document whose purpose is to tell the next session what is true.

### DOC-058 — QUICKSTART's own `--keys 1` silently discards every window but the latest — FAIL

Same server, same `velocity.sql`, differing only in the `--keys` the document prints:

```
$ pravaha register --name user_volume --sql-file velocity.sql --keys 1        # QUICKSTART:164
$ pravaha query --sql "SELECT * FROM user_volume"
window_end             user_id  txn_count  total
1767261720000000000    alice    1          250
1767261720000000000    bob      1          75
2 rows                                                  <- the 10:01 window is gone

$ pravaha register --name uv4 --sql-file velocity.sql --keys 0,1
$ pravaha query --sql "SELECT * FROM uv4"
... 4 rows                                              <- both windows
```

Keyed on `user_id` alone, each new window overwrites the previous one for that user. That may be the
intended "current velocity per user" semantics, but the document never says so, and it prints
`window_end` as the first output column — a column that can only ever hold one value per user.
A reader checking yesterday's window finds it silently absent.

**Severity: moderate**, high for trust: the arithmetic is right and the retention is invisible.

### DOC-059 — The delimited-source format for a `TIMESTAMP` column is undocumented, and getting it wrong yields silence — FAIL

My first attempt at Part 3 used ISO-8601 in the CSV, which is what `TIMESTAMP` suggests:

```
1,alice,500,COMPLETED,2026-01-01 10:00:05
```
```
$ pravaha queries
user_volume  RUNNING  8a86337b7b50  0            <- zero rows in
(server log: no error, no warning, nothing)
```

`DelimitedCodec.java:116` requires a bare `Long.parseLong`, and the value is interpreted as
**nanoseconds since the epoch**. Nothing states this: not `QUICKSTART.md`, not `SQL_SUPPORT.md:234`
(which lists `TIMESTAMP` among the supported types and says nothing about its wire form), not
`application.yaml`, not `examples/`. The plugin option keys generally (`path`, `schema`, `delimiter`,
`event.time`) remain documented nowhere, which I filed under DOC-050.

**Severity: high**, because of the failure mode. `ROWS IN` stayed at 0 and nothing was logged — the
same silent shape as DOC-051, reached by a different wrong guess.

### DOC-060 — `PRV-6104` is now the first failure on the documented production path — FAIL

Covered in Part 1. `PRV-6104 FLIGHT_TLS_UNREADABLE` was one of the ten codes missing from
`TROUBLESHOOTING.md` (DOC-029). Before this release it was unreachable, because TLS never got that
far. It is now the first thing a reader meets after copying `QUICKSTART.md:111–112` verbatim, and
`TROUBLESHOOTING.md` still says of itself that a code missing from its table does not exist.

### DOC-061 — A shared computation's second name lists as RUNNING and answers nothing — FAIL

The remediation states: *"a shared computation's second name is journalled and registered as a
view."* The first half happened; the second did not.

```
$ pravaha register --name uv2 --sql-file velocity.sql --keys 0,1 --url grpc://localhost:19501
registered user_volume  state=RUNNING  fingerprint=8a86337b7b50      <- I asked for uv2
a query with the same fingerprint is the same computation, shared

$ pravaha queries --url grpc://localhost:19501
NAME         STATE    FINGERPRINT   ROWS IN
user_volume  RUNNING  8a86337b7b50  7
uv2          RUNNING  8a86337b7b50  7                                <- uv2 is listed

$ pravaha query --sql "SELECT * FROM uv2" --url grpc://localhost:19501
PRV-1041  PRV-2002  Object 'uv2' not found. Known streams: [user_volume]
```

`uv2` lists as RUNNING with seven rows in, and does not exist as a view. `drop --name uv2` then
succeeds, so the name is real to the registry and invisible to the serving layer. The confirmation
line printed the *other* name, so a script reading stdout cannot even tell which name it got.

This is engine behaviour rather than prose, and I am filing it here because sharing is a documented
user-facing feature — `CONCEPTS.md §5`, `QUICKSTART.md`, and `pravaha --help`'s "A computation is
released when its last name is dropped" — and the documentation currently describes something that
does not work. **Severity: high.** Hand to whoever owns `QueryRegistry`/`ViewCatalog`; the fix
appears to cover the journal and the listing and not the view registration.

Note also that the fingerprint matched across `--keys 1` and `--keys 0,1`, two registrations whose
*output* differs (DOC-058). If key columns are outside the fingerprint, sharing can hand a caller a
view keyed differently from the one they asked for.

---

## Part 5 — the configuration-key audit, both directions

Re-run against the current build.

**Keys the code binds in `pravaha-server` main sources:**

```
pravaha.cluster.mechanism           pravaha.flight.tls.certificate
pravaha.cluster.mode                pravaha.flight.tls.key
pravaha.flight.enabled              pravaha.node.id
pravaha.flight.host                 pravaha.watermark.idle-after
pravaha.flight.port                 pravaha.watermark.tick
+ @ConfigurationProperties: pravaha.security.*, pravaha.streams.*, pravaha.sources.*, pravaha.checkpoint.*, pravaha.registry.*
```

**Documented and inert** (down from five, up in severity):

| Key | Documented at | Reality |
|---|---|---|
| `pravaha.watermark.out-of-orderness` | `CONCEPTS.md:66`, `OPERATIONS.md:222`, `application.yaml:167`, javadoc `StreamSchema.java:51` | **no reader.** Proven inert by experiment (DOC-052) |
| `pravaha.cluster.socket.peers` / `.heartbeat.millis` / `.timeout.millis` | `OPERATIONS.md:104-106` | unchanged: `PravahaNode` still forwards only `mode` and `mechanism` |
| `pravaha.cluster.zookeeper.*` | `OPERATIONS.md:113` | unchanged |

`pravaha.watermark.idle-after` and `.tick` are **no longer inert** — the one improvement in this
table.

**Bound and documented nowhere** (new this pass):

| Key | Consequence of not knowing it |
|---|---|
| `pravaha.streams.<n>.event-time` | no window ever closes; RUNNING, rows in, nothing out, no error (DOC-051) |
| `pravaha.streams.<n>.out-of-orderness` | the only working lateness control (DOC-052) |
| `pravaha.security.audit` | unchanged from DOC-025 |
| `pravaha.checkpoint.timeout` | unchanged; still not settable from server YAML |
| `filesystem` plugin options (`path`, `schema`, `delimiter`, `event.time`) | unchanged from DOC-050; `event.time` is new and is forwarded by the node |

**Both directions therefore worse than at the first pass.** Two keys moved off the inert list and
three moved onto the undocumented list, and the two that arrived are the two that decide whether the
engine's headline feature runs at all.

---

## Re-QA summary

| | Count |
|---|---|
| FAILs re-tested | 28 |
| VERIFIED-FIXED | **0** |
| PARTIALLY-FIXED | 2 (DOC-024, DOC-027) — code moved, documents did not |
| STILL-FAILING | 26 |
| BLOCKED cases resolved | 1 (DOC-008 → PARTIALLY-FIXED) |
| New defects | 11 (DOC-051 … DOC-061) |

**Ranked by harm to a new user or an operator:**

1. **DOC-051** — `pravaha.streams.<n>.event-time` undocumented. Silent, permanent, unsearchable.
2. **DOC-011** — QUICKSTART §4 still declares a schema its own query cannot use. Now the *only*
   barrier to a working windowed query.
3. **DOC-053** — no shipped client can reach a TLS node; the netty fix went to the server only.
4. **DOC-052** — the documented lateness key is inert; the working one is undocumented.
5. **DOC-057** — README's first blockquote is now false three ways.
6. **DOC-005 / DOC-006** — the first two commands in the quickstart still do not run.
7. **DOC-055** — float aggregates refused; `SQL_SUPPORT.md` says ✅.
8. **DOC-056** — `mayAdminister` is new public API; a reader token can drop every query.
9. **DOC-061** — a shared computation's second name lists RUNNING and answers nothing.
10. **DOC-059** — `TIMESTAMP` in a delimited source must be epoch nanoseconds; said nowhere; wrong
    guess is silent.
11. **DOC-054 / DOC-060 / DOC-029** — new and newly-reachable codes absent from a troubleshooting
    page that claims to be exhaustive.
12. **DOC-033 / DOC-038 / DOC-034 / DOC-026 / DOC-025 / DOC-028** — unchanged false claims.
13. **DOC-058** — `--keys 1` silently keeps one window per user.

### What I could not cover, and why

**The console against the new server.** I did not restart it; §7's defects are pure text and
unchanged, and the runtime questions belong to whoever owns the console's own area.

**`mayAdminister` enforcement end to end.** I established the documentation gap from the source and
from `Principal.roles()` having no reader, but did not stand up a node with a restrictive policy and
two principals — that is SEC's area and its log should carry the enforcement verdict.

**The case studies, `system_design.md` and `implementation_plan.md`.** Unchanged since the first
pass and out of proportion to this pass's budget. `system_design.md` is still linked as "the full
specification" with no marker that it is aspirational (DOC-020).

**Whether `pravaha.streams.<n>.out-of-orderness` has bounds.** `OPERATIONS.md` claims the
`idle-after` bounds are "both enforced"; I did not test the new per-stream key against absurd values
beyond the 10m that proved it live.
