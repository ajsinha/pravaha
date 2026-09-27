# Pravaha — release notes

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see [`../LICENSE`](../LICENSE).

> **What this page is.** One entry per cut, written from what the tree proves rather than from what
> was planned. Every number here comes from a command named beside it, so a reader can run the
> command and get the number. Where a target was not reached, the number that was measured is here
> instead of the target.

---

## Since 0.1.1 — unreleased

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
- **Found, open:** a query cannot be replaced while the record at its position was dead-lettered
  (REPL-2, POST-GA); since REPL-1 it fails as `FAILED` `PRV-4013` rather than silently.

Register: **385 findings — 361 fixed, 10 open, 0 GA-BLOCKER, 0 GA-REQUIRED**.

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
