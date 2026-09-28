# Pravaha — release notes

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../LICENSE`](../LICENSE).

> **What this page is.** One entry per cut, written from what the tree proves rather than from what
> was planned. Every number here comes from a command named beside it, so a reader can run the
> command and get the number. Where a target was not reached, the number that was measured is here
> instead of the target.

---

## Unreleased

- **A research paper, and an article version of it.** `docs/research/continuous-queries-as-maintained-answers.pdf`
  (LaTeX source beside it) and `-article.md`: *Continuous Queries as Maintained Answers — Exact Cuts, Exact
  Seams and Lossless Cutover in a Single-Node Streaming SQL Engine*. It states the Z-set model and the
  operator laws, and proves, under assumptions it lists, that a snapshot subscription gets every later
  commit once, that a checkpoint is one cut (and a two-phase sink exactly once), that an ordered source's
  shared reader hands each query each record once (ADR-054), and that a replacement cuts over losslessly
  (ADR-046). Every claim names the test that carries it or says it is argued; the paper reports that
  `pravaha-algebra` is tested but imported by no running module, and quotes only measurements already in
  the repository, with their conditions. Licensed CC BY-NC-ND 4.0 (`docs/research/LICENSE`); the software
  stays proprietary. Built with `latexmk -pdf`.
- **The Java CLI is now `pravaha-engine`, and keeps only what needs the engine in-process:
  `validate`, `explain`, `run` and `version`.** `bin/pravaha` is renamed `bin/pravaha-engine`
  (`PRAVAHA_CLI_JAR` becomes `PRAVAHA_ENGINE_JAR`; the container image installs
  `lib/pravaha-engine.jar`). Every command that talked to a running engine is removed from it —
  `query`, `register`, `queries`, `drop`, `pause`, `resume`, `replace`, `cutover`, `rollback`,
  `abandon`, `finish`, `throttle`, `pause-backfill`, `resume-backfill`, `replacements`,
  `subscribe`, `dlq`, `login`, `password`, `user`, `key`, `session`, `lanes` and `debug` — and
  lives in the Python CLI, `pravaha`, with the same names and flags. Typing one of them into
  `pravaha-engine` prints where it went and exits 2. The jar no longer bundles the Java SDK.
  `deploy/docker/smoke.sh` lists queries with `GET /api/v1/queries` and registers and reads over
  Flight with the Python CLI on the host.
- **`pravaha` is a Python CLI on the Python SDK.** Everything that talks to a running engine is
  now `sdk/python/pravaha/cli` — the `pravaha` console script of `pip install "pravaha[flight]"`,
  `python -m pravaha.cli`, or `bin/pravaha` from a checkout — with no protocol code of its own:
  Flight commands call `Client`, HTTP ones the new `pravaha.api.EngineApi` (stdlib only, no
  pyarrow), which `Client`'s HTTP methods now delegate to and which adds lanes, rebalance, health
  and the identity endpoints; `RestClient` gains `put`/`patch`/`delete`. Every Java CLI command
  and flag still works; new are `status`, `health`, `version` (CLI and node), `metrics`, `plugins`,
  `sinks`, `streams`, `views`, `describe` (with the lane), `plan`, `validate`/`explain` against the
  node, `audit`, `tenants`, `permissions`, `whoami`, `logout`, `dlq count` and
  `subscribe --reconnect`. `--json` on every command; `--url`/`--http`/`--token` or
  `PRAVAHA_URL`/`PRAVAHA_HTTP`/`PRAVAHA_TOKEN`, then `login --save`'s `0600` token file; exit `0`
  ok, `1` engine refusal, `2` usage, `3` unreachable. `drop`, `abandon`, `finish`,
  `lanes rebalance`, `key revoke` and `user disable` only say what they would do without `--yes`.
  The offline `validate --schema`, `explain --schema` and `run` are the Java `pravaha-engine`.
  [`docs/CLI.md`](CLI.md); `test_cli.py`, `test_cli_flight.py`, `test_api.py`.
- **SDK subscriptions survive a server restart.** Python: `subscribe(..., reconnect=True,
  reconnect_timeout=300)`; Java: `subscribe(view, filters, Reconnect, onBatch)` and
  `subscribeFromSnapshot(view, filters, Reconnect, onBatch)` returning a `ReconnectingSubscription`.
  A stream ended by a restart, a broken connection or `PRV-6105` is reopened with backoff (250 ms to
  10 s) until the limit; a refusal that will not change is raised at once. With a snapshot
  subscription the first batch after reopening is a fresh snapshot, so a copy loses nothing
  (`batch.reconnected` in Python, `Reconnect.onReconnected` in Java). `JavaSdkReconnectTest`
  restarts a real server under a subscriber; `test_reconnect.py`.
- **Lane sharing is on by default, as `auto`.** `pravaha.lane.multiplex.enabled` was a boolean,
  `false` by default; it is now `auto` (the default), `true` or `false`, with
  `pravaha.lane.multiplex.auto-from` (64). Under `auto` a node's first 64 queries each own a lane
  -- a failing query takes down only itself -- and every registration after them is placed on a
  shared lane, saving about 1 MiB of inbox and arena per idle query. Running queries are never
  moved. `true` and `false` keep their meaning. The node's `lanes:` status line says which mode is in
  force. An embedded `QueryRegistry` still shares nothing unless asked
  (`multiplexingLanes(lanes, ceiling, shareFrom)`).
- **`WITH (lane = 'dedicated')`: one query on a lane of its own, whatever the node's mode.** A
  registration option (`'dedicated'` or `'shared'`, the default; anything else is PRV-8017),
  journalled with the registration as a new `L` record so a restart keeps it -- a build that predates
  it refuses the journal by name. On `CREATE OR REPLACE`, `lane` moves a running query between a
  shared lane and its own at a lossless cutover, with the SQL unchanged if that is all that changes; a
  replacement that does not say keeps the running version's lane. A dedicated registration that
  would join a computation already on a shared lane is refused with PRV-8017. `GET /api/v1/queries`
  and `/{name}` gain `lane` (`dedicated` | `shared` | `own`) and `sharedLane`; the new
  `GET /api/v1/lanes` summarises placement (mode, `autoFrom`, per-lane counts, own-lane, dedicated
  and hosted computations). Placements are not rebalanced when queries are dropped — until an
  administrator asks: **Admin → Lanes** in the console (every query's lane, the mode, each shared
  lane's fill, and a preview-then-run rebalance for the `admin` role), `pravaha lanes` and
  `pravaha lanes rebalance [--yes]`, and `GET|POST /api/v1/lanes/rebalance`. It moves shared queries
  onto lanes of their own while there is room under `auto-from`, oldest first and one at a time, each
  by a blue/green replacement with `lane = 'own'` (new: a lane of its own without pinning it).
- **`iceberg-sink`, an Apache Iceberg sink** (`plugins/pravaha-plugin-iceberg`), on iceberg-core and
  iceberg-parquet 1.2.1, not Spark. A table on the local filesystem; `mode: upsert` (the default)
  keeps it equal to the view by key through equality deletes (format version 2), and
  `mode: changelog` appends every change with `_op` and `_weight`. One snapshot per checkpoint,
  exactly once: files are staged unreferenced at `prepare`, and the snapshot summary carries the
  transaction id and label, so a commit repeated after a restore is skipped. New codes `PRV-5140`
  (binding), `PRV-5141` (table not the binding's) and `PRV-5142` (write). Not built: object stores,
  catalog services, partitioned tables. `IcebergSinkPluginTest`, 10 tests.
- **`mysql-cdc`, change data capture from MySQL** (REMAINING C3). One table per binding, read from the
  row-based binary log with the plugin registered as a replica, on ADR-041's model and without
  Debezium: an insert at +1, a delete as the whole old row at −1, an update as both, whole
  transactions, `EXACTLY_ONCE` from a binlog file and offset. `binlog_format` other than `ROW`,
  `binlog_row_image` other than `FULL`, and a user without `REPLICATION SLAVE` and `REPLICATION
  CLIENT` are refused by name (`PRV-5152`); a purged resume file is `PRV-5155`. Changes only:
  `snapshot.mode: initial` is refused. New codes `PRV-5150` to `PRV-5157`. Tested against a real
  MySQL 8 (`MySqlCdcIT`, `./mvnw -Pit -pl plugins/pravaha-plugin-mysql-cdc -am verify`).
- **Thirteen case studies, every one run by the build.** Three new ones: `retail-inventory-mysql`
  (`mysql-cdc`, low-stock alerts that clear themselves), `lakehouse-orders-iceberg` (`iceberg-sink`
  in upsert mode, a late row corrected in the table) and `payments-shared-kafka` (three queries on
  one shared Kafka reader, ADR-054; `INDEX (merchant)`, ADR-055). `CaseStudyRunTest` runs each
  study's continuous queries in an embedded engine over its `data/sample/` and checks every read
  against answers worked out by hand. It found four studies registering the wrong view key
  (biology, finance, trading, trade processing) and fixed them.
- **A windowed aggregate that is sent event time before its first row no longer walks from the
  epoch.** With a filter ahead of it — `cancel_rate`'s `WHERE event_type = 'CANCEL'` behind a
  `NEW` — the first watermark fired every empty window since 1970: `PRV-3022` for a ten-second
  slide, and hundreds of thousands of empty windows for an hourly one. It now has nothing to fire.
- **`PravahaEngine.retract(stream, rows...)`** pushes rows at weight −1, as a change-data-capture
  source delivers a delete or the old half of an update.


Register: **431 findings — 380 fixed, 37 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.2.0 — QA, 2026-09-27

**What this build is for.** People sign in to the console as themselves: the engine keeps users,
passwords, API keys and sessions, and the console holds no password or token of its own. **An upgrade
from 0.1.x needs its server configuration to gain an identity block** -- install.sh says so and how
when it keeps an older file. Also: one reader shared by every query on a Kafka topic, an index on a
column outside a view's key, compressed and Avro/Protobuf Kafka, and a glibc image in which Parquet's
Snappy codec loads.

- **`kafka-sink` writes Avro and Protobuf values** (`format: avro` with `schema.file`, and
  optionally `schema.id` for the Confluent prefix; `format: protobuf` with `schema.descriptor` and
  `schema.message`). Upsert mode only, the key stays JSON and a retraction stays a tombstone.
  Columns map by name, and a column the schema cannot hold exactly is refused at registration
  (`PRV-5108`); `mode: changelog` with either is refused (`PRV-5100`). No schema is registered, and
  no Avro or Confluent library is added: the encoder is written from the specification beside the
  source's reader, which reads every row back unchanged.
- **`jdbc-sink` can commit through PostgreSQL's own two-phase commit** (`commit.mode: prepared`). Each
  checkpoint's changes go straight into the table inside a transaction that `PREPARE TRANSACTION` holds
  and `COMMIT PREPARED` publishes: one write per change instead of two. It is opt-in, because the
  database must allow prepared transactions and the touched rows stay locked for a checkpoint
  interval. It is refused by name elsewhere.
- **The snapshot-and-change-feed splice is a documented boundary.** `SplicedReader` stays
  unwired: its newest-row-per-key rule would double-retract on a weighted changelog such as
  `postgres-cdc`'s, whose own `snapshot.mode: initial` is already exact. A replacement still splices
  at an offset (ADR-046), and `backfill.adaptive` is still refused (`PRV-4018`).
- **An equality index over a column outside a view's key**
  ([ADR-055](adr/055-an-equality-index-over-a-column-outside-the-key.md)). `CREATE CONTINUOUS QUERY
  ... INDEX (region)`, or `WITH (index = 'region')`, keeps value-to-keys in the view's own commit, so
  `WHERE region = 'eu'` and `WHERE region IN ('eu', 'us')` probe instead of scanning, over Flight
  SQL, REST and pgwire alike. The index is journalled with the registration (a new `X` record, which
  an older build refuses by name), rebuilt over a restored checkpoint, and carried to a replacement by
  column name. New code `PRV-2074` refuses an index over `FLOAT`, `DECIMAL`, `BYTES` or the view's
  whole key.
- **The server image runs on glibc, and Parquet's Snappy codec loads in it** (PORT-1,
  [ADR-053](adr/053-native-code-only-where-java-cannot.md)). Up to 0.1.3 the image was Alpine, where
  snappy-java cannot load, so the `feedfile` and `delta` plugins could not read a Snappy-compressed
  Parquet file inside the container. The build now refuses native libraries except Parquet's two
  codecs; TLS runs on the JDK's engine. The node says at startup if a codec cannot load.
- **One reader for many queries, even over an exactly-once source**
  ([ADR-054](adr/054-an-ordered-source-is-shared-at-an-exact-seam.md)). A source declaring ordered
  positions and bounded reads is shared at an exact seam: a query joining, resuming or restoring
  behind the reader catches up to exactly where it stands, and one restored ahead waits for it. The
  Kafka source and the filesystem source (files read once through) implement it, so a thousand queries over one topic read it once, and partitions the topic gains are joined by every query sharing its reader.
- **Users, passwords, API keys and sessions kept by the engine**
  ([ADR-052](adr/052-the-engine-is-the-identity-authority.md), stages 1 to 3), with a REST API and
  `pravaha login|user|key|session|password`. The console signs each person in against the engine and
  acts as them; it keeps no password or engine token of its own. QA installs generate `admin`'s first
  password; locally, run the engine with `--spring.profiles.active=dev,users`. New codes PRV-7010 to
  PRV-7021.

Register: **417 findings — 377 fixed, 26 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.3 — QA, 2026-09-27

**What this build is for.** 0.1.2 with one fix QA would otherwise meet in its first week: a
replacement over a source that had just dead-lettered a record failed. Nothing else changes -- the
ports, the configuration files and the images' layout are 0.1.2's, so a 0.1.2 host upgrades by
installing this bundle over it.

- **Replacing a query whose latest record was dead-lettered works** (REPL-2). The backfill counts a
  record its source rejected as read, and `PartitionReader#poll`'s `maxRecords` now bounds records
  consumed, rejected ones included, so a backfill stops on the running version's exact position
  whether or not the record there could be decoded. The filesystem and Kafka readers are brought into
  line; PostgreSQL CDC already was. 0.1.2 fails such a replacement with `PRV-4013`.

Register: **399 findings — 370 fixed, 15 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.2 — QA, 2026-09-27

**What this build is for.** The QA host's second build, and the one to test on: 0.1.1's console image
shipped without the documentation its help pages include (IMG-1), and 0.1.1's query sharing could
hand one query another's answer (FP-1). Both are fixed here. **The default ports change** — 18080,
19090 and 17070 — so a QA host moving from 0.1.1 installs this bundle fresh or moves its published
ports. `install.sh` keeps a host's two configuration files, and 0.1.1's name the old ports
explicitly: it detects that and prints the one `sed` that moves them.

- **Default ports moved** (the owner's decision): the engine's HTTP port is **18080** (was 8080),
  Flight SQL **19090** (was 9090), the console **17070** (was 8090). Both SDKs' default port, the CLI's
  default URL, the images, the Helm chart and the QA install follow. **A 0.1.1 deployment that
  relied on the defaults must move its clients and published ports**; one that set them explicitly
  keeps working. The documented help-link base now points at a console path that exists (HELPURL-1).
- **`docs/PYTHON_API_GUIDE.md`**: an integrator's guide and reference for every call the Python SDK
  makes and every REST endpoint, each sample run against a 0.1.1 node. It ships in the QA bundle.
- **Python SDK:** a refusal over Flight carries the engine's code as `QueryError.engine_code`, as
  `ApiError` always has (PYSDK-1); an engine that is down is `ConnectError`, retryable, not a
  refusal (PYSDK-2); `pravaha.__version__` is the installed wheel's (PYSDK-3).
- **The QA host's demonstration stream** is the seven-column `txn` with an event-time column and a
  `large_payments` sink, so the guide's samples, windows included, run on a fresh install.
- **Replacement:** a replacement whose backfill stops is `FAILED`, with the source's code, and its
  candidate is released; it used to go on reporting `BACKFILLING` with no failure (REPL-1).
- **Console image:** it carries the documentation its help pages include; the 0.1.1 image did not,
  so its tutorials, guides and code browser were empty (IMG-1). The build now checks every include.
  The Python guide has a help card (`/help/python-api-guide`).
- **Query sharing (FP-1, fixed):** two queries differing only in a join's time bound, INNER against
  LEFT, which column a projected name came from, or an aggregate's function shared one computation,
  and the second read the first one's answer. The fingerprint now hashes each operator's full
  identity, and EXPLAIN shows a join's window, `LeftJoin`, renamed columns' sources and aggregate
  arguments. **0.1.1 has this defect**; nothing on disk changes on upgrade.
- **A tutorial joining two Aerospike sets and a CSV file** (`docs/tutorials/aerospike-fulfilment.md`,
  and a console card), with a script that pushes live orders; the QA install gains `/opt/pravaha/feeds/`
  for files you drop for file sources, and the bundle carries the tutorials' scripts.
- **Console help:** `docs/COMPETITIVE_LANDSCAPE.md` scores Pravaha against five categories of
  product, with a card per row and where it loses, and is its own page (`/about/competitive`). The
  About page gains problem-and-fix pairs, "What makes it different", this list ("In this release",
  read from this file) and a condensed landscape; the help gains an FAQ, and guides for these
  notes, the roadmap (`REMAINING.md`) and `DEPLOYMENT.md`.
- **Fixed from the tutorials' runs:** a parameterised query from the Java SDK or CLI works under a
  token (SDKJ-1); a second name on a shared computation is answered with that name (NAME-1); a debug
  fixture of a windowed query carries its event time and runs (FIX-1).
- **Found, open:** a query cannot be replaced while the record at its position was dead-lettered
  (REPL-2, POST-GA); since REPL-1 it fails as `FAILED` `PRV-4013` rather than silently.

Register: **399 findings — 369 fixed, 16 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

---

## 0.1.1 — QA, 2026-09-26

**What this build is for.** The same as 0.1.0 — quality assurance on one node — now handed to a QA
team as files: two container images, a compose file and two configuration files, installed on one
Linux machine with Docker. 68 commits since `v0.1.0`; the tag is the only thing published.

### How it is delivered

| | |
|---|---|
| **Two images** | `pravaha/pravaha-server` (615 MB, every connector inside) and, new, `pravaha/pravaha-console` (481 MB). Built by `deploy/docker/build.sh` and `deploy/docker/console/build.sh`; both run as uid 10001 |
| **One root** | Every path is under `/opt/pravaha`: `conf/` and `console/conf/` for the two configuration files, `data/` for state, `logs/` for the engine's log and the audit trail. `/var/lib/pravaha` and `/etc/pravaha` are gone — **a 0.1.0 deployment that mounted them must move its mounts** ([`DEPLOYMENT.md`](DEPLOYMENT.md), "One root") |
| **Two files** | The engine and the console are each configured by their own YAML file, edited in place and read on restart |
| **A bundle** | `deploy/qa/bundle.sh` writes `pravaha-qa-0.1.1.tar.gz`: both images as `docker save` archives, `install.sh`, the compose file, the jars, wheels and chart, and `SHA256SUMS`. [`deploy/qa/README.md`](../deploy/qa/README.md) is the page for the QA team |

### What changed in behaviour

A QA reader who tried 0.1.0 will meet these:

- **Every connector is in the server jar.** Kafka, Delta, JDBC, PostgreSQL CDC, Aerospike and
  Cassandra bind with nothing to install. Two lookups, `jdbc-lookup` and `aerospike-lookup`, had never
  been declared to the plugin loader, so a lookup join on a real node was refused `PRV-5090`; they now
  load. 14 plugins, checked by `ShippedConnectorsTest` and by the smoke run inside the image.
- **A filter on a `TINYINT`, `SMALLINT` or `REAL` column gave wrong answers** in 0.1.0 (NARROW-1): the
  comparison read the column's neighbour too. Fixed, and the reason 0.1.0 should not be used for
  answers over narrow columns.
- **Filters and projections run generated code** by default (`pravaha.codegen.enabled`); a query's
  description says which path each chain is on.
- **Tenants.** A tenant is charged for its queries and its state against quotas
  (`pravaha.tenancy.*`, ADR-050), and **identical SQL from two tenants is now two computations** —
  a tenant shares a computation only with itself. Refusals `PRV-8020`–`PRV-8023`.
- **SQL that 0.1.0 refused and now runs:** a stream joined with itself; `ROW_NUMBER() ... rn <= N`
  as a maintained top-N; exact `DECIMAL` arithmetic; `DATE_FORMAT`, `REGEXP_EXTRACT`, `SPLIT_INDEX`; a
  comma join with its condition in `WHERE`. Nexmark: **12 of 23** queries run (5 at 0.1.0).
- **Delta** reads deletion vectors (a deleted row arrives as a retraction) and `delta-sink` writes
  partitioned tables. **Kafka** resolves Avro against a reader schema and fetches Protobuf
  descriptors from the schema registry.
- **Firing a large window streams its groups** instead of building the window on the heap (SPILL-3),
  and an Aerospike `lut-scan` reads a page at a time (SRC-7) — the two defects 0.1.0 told QA to watch.
- An empty paging parameter on the REST debug read is refused rather than read as the default, and a
  lone surrogate over Flight is refused `PRV-1053`, as over HTTP.
- The console has **blue** and **green** themes and a new landing page.

### The numbers

| Measurement | Result | Command |
|---|---|---|
| Java tests | **4,175 run, 0 failures, 189 skipped**, 37 reactor projects | `tools/verify-clean.sh` |
| With the Docker integration tests | **4,185 run, 0 failures**, 18 skipped | `sg docker -c "./mvnw -o verify"` |
| Console tests | **1,732 passed, 0 failed** (380 without a browser, 1,352 in Chrome: visual, accessibility, journeys, states, performance) | `cd console && python -m pytest` |
| The images | `smoke.sh` **PASSED** (register, read, follow, restart, `--read-only`); `qa-smoke.sh` **PASSED** (the console against a real node, all 14 plugins); the QA compose stack installed, signed in to, queried and restarted | `deploy/docker/smoke.sh`, `tools/qa-smoke.sh` |

The performance figures of 0.1.0 stand and have not been re-measured; PERF-1 below says why the
default command's figures should be distrusted until they are.

### Open defects

At this cut: **377 findings — 354 fixed, 9 open, 0 GA-BLOCKER, 0 GA-REQUIRED**, 7 of the open ones
triaged POST-GA and 2 recorded as notes rather than defects ([`qa/FINDINGS.md`](qa/FINDINGS.md)).
Two worth knowing before starting:

- **EMIT-1** — a fired window still holds heap per group for as long as its lateness lasts, and a
  late row can fire it again. Size lateness with the group count in mind.
- **PERF-1** — every performance figure taken with the default command ran under the coverage agent.
  Treat 0.1.0's throughput table as an ordering, not as measurements.

---

## 0.1.0 — QA, 2026-09-20

**What this build is for.** Quality assurance on a single node. It is the first cut offered to
anyone but its author, and the point of it is to be tried, not to be deployed: run a node, register
continuous queries, watch answers change, break it, and tell the author what broke.

**Nothing has been published from this tree.** No Maven repository, no container registry, no
Python index, no signing key. A QA reader builds it, or is handed the artefacts the release script
produced. The licence is proprietary ([`../LICENSE`](../LICENSE)) and every file in the tree says so.

### What it does

A SQL query registered once keeps answering. Rows arrive from a source, the answer is maintained
incrementally as a Z-set — a change carries a weight, `+1` for an insert and `-1` for a retraction —
and the current answer is a view that can be read, subscribed to, or written to a sink.

| | |
|---|---|
| **Ask it** | Flight SQL, a REST API (`/api/v1`), the PostgreSQL wire protocol, `pravaha` on the command line, a Java SDK, a Python SDK, and the console in a browser |
| **Sources** | Filesystem (bounded or followed), feedfile directories (CSV, Parquet), Delta Lake, JDBC polling, Aerospike scans, Cassandra `token()`-range scans, PostgreSQL change data capture, Kafka topics (JSON, Avro and Protobuf — with no Avro or Confluent library) |
| **Sinks** | `filesystem`, `aerospike-sink`, `jdbc-sink`, `kafka-sink`, `delta-sink`. Five, and each states its delivery guarantee at registration rather than in a document |
| **Survives a restart** | Checkpoints, a registry journal, and sinks that stage a checkpoint and commit it once the checkpoint is durable |
| **Explains itself** | Every refusal is a `PRV-nnnn` code with a sentence saying what to do; `EXPLAIN` shows the plan; a query's plan carries per-operator rows in, rows out and a measured bottleneck; a debug session forks a query from a checkpoint and steps it row by row |

### The numbers, and the commands that produce them

| Measurement | Result | Command |
|---|---|---|
| Java tests | **4,043 run, 0 failures, 183 skipped**, 37 reactor projects | `tools/verify-clean.sh` (offline, wipes the project from `~/.m2` first) |
| Console tests | **844 run, 842 passed** at the last full run; the two failures were a test-isolation defect and a load flake, both since fixed | `cd console && python -m pytest` |
| Python SDK tests | **132 collected, 131 passed, 1 skipped** without the `tls-keystore` extra | `cd sdk/python && python -m pytest` |
| Skips | 184, and every one of them names its reason: Docker, Cassandra, Aerospike or `psql` absent on the machine | in the surefire output |

The skips matter for QA: a machine without Docker does not run the Kafka broker, PostgreSQL CDC or
Aerospike integration tests, and they are skipped **by name** rather than passing quietly.

### Performance, measured here and nowhere else

There is no reference hardware, so the gates were measured on the development machine — an AMD
Ryzen AI 9 HX 370, 12 physical cores, frequency-scaled, with other work running — and every number
in [`gates/measured-2026-09-20/`](gates/measured-2026-09-20) carries the machine's load average
beside it.

| Gate | Target | Measured | Verdict |
|---|---|---|---|
| P2, Profile A throughput | ≥ 1.2 M rows/s per lane | ~30 M warm, ~11 M cold; the worst pass, at load 77, was 1.04 M | **reached** |
| P2, scaling 1 → 8 lanes | ≥ 90 % of linear | **28–42 %** | **not reached** |
| P3, Profile B throughput | ≥ 350 k rows/s per lane | 2.5–2.8 M, worst pass 1.1 M | **reached**, and measured for the first time |
| ADR-038's Nexmark comparison | head-to-head against Flink | **not run** — no Flink, no quiet machine, no reference generator. Of Nexmark's 23 queries, **5 run** on this engine today | **not reached** |

A QA reader should not quote the throughput figures as product numbers. They were taken on a laptop
part under load, several are too noisy to state as a figure, and the harness says so where they are.

### Known, and deliberately not in this build

- **Multi-node execution.** Designed ([ADR-045](adr/045-cluster-mode-assigns-queries-not-rows.md))
  and on hold by the owner's decision. A node refuses `PARTITIONED` with `PRV-9002` rather than
  serving every partition while claiming to own some.
- **Continuous integration.** Four workflows exist; **none has ever run**. Nothing is built, tested,
  published or signed by a machine other than this one.
- **Tenancy and admission quotas.**
- **The manual WCAG 2.2 AA audit.** The automated half — axe on every page, both themes, both
  densities — is green; a person still has to do the rest.
- **The console's design-system surface**, by decision.
- Smaller refusals, each named and reasoned where it is raised: no secondary index over a non-key
  column, no `INSERT INTO <sink> SELECT`, no Iceberg or Hudi sink, no partitioned Delta tables,
  `COUNT(DISTINCT)` cannot spill.

### Open defects

The register is [`qa/FINDINGS.md`](qa/FINDINGS.md), and it is the honest list: every defect found,
what happened to it, and what is still true of the build.

At this cut: **362 findings — 332 fixed, 17 open, 0 GA-BLOCKER, 0 GA-REQUIRED**, 15 of the open ones
triaged POST-GA and 2 recorded as notes rather than defects. The header's counts are enforced by
`FindingsRegisterTest`, so this page and the register cannot drift apart silently.

Three worth a QA reader's attention before they start:

- **SPILL-3** — firing a very large window builds it on the heap and can exhaust it, whether or not
  the spill tier is on. Bounded by the operator's own sizing; `OPERATIONS.md` gives the arithmetic.
- **PF-12** — a benchmark harness reported 131 % of linear scaling on a loaded machine and *passed*.
  Fixed, and recorded because it is the first defect here that produced a pass rather than a
  failure. If a number looks too good on a busy machine, distrust it.
- **SRC-7** — an Aerospike `lut-scan` buffers a whole scan on the heap, and `maxRecords` bounds
  only what it hands on. Size the heap for the scan, or use `deletes: detect` with a narrower
  range.

### Running it

[`QUICKSTART.md`](QUICKSTART.md) is the five-minute path, and its `application.yaml` declares an
event-time column — which it did not until today, so a first-time reader's windowed query silently
never emitted. [`DEPLOYMENT.md`](DEPLOYMENT.md) covers the container image and the Helm chart, both
of which run one node by design. [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) carries the code index.
