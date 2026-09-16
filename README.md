<div align="center">

<img src="brand/mark.svg" alt="" width="88" height="88">

# प्रवाह · Pravaha

### Ask once. Answer always.

**An embeddable, store-native, incrementally-maintained SQL engine.**

> **Read this before evaluating.** Pravaha runs unbounded streams: a registered query derives its
> own watermark from the event-time column its stream declares, windows close as time moves on, and
> a `pravaha-server` node feeds registered queries from configured sources. Late data reopens a
> closed window as a correction when the stream declares how late it will accept.
> [What is and is not built →](docs/HANDOVER.md)

*Pravaha* (Sanskrit: *continuous, uninterrupted flow*) · pronounced *pruh-VAA-huh*

[![Status](https://img.shields.io/badge/status-wave%209%20of%2011-blue)](docs/HANDOVER.md)
[![Java](https://img.shields.io/badge/Java-21%20LTS-orange)](docs/system_design.md#4-language-decision-java-vs-scala)
[![Build](https://img.shields.io/badge/build-Maven-C71A36)](docs/implementation_plan.md)
[![License](https://img.shields.io/badge/license-Proprietary-red)](LICENSE)

</div>

---

> **Project status: Wave 9 of 11. An engine, a client protocol, an operator console, a node that
> survives its own restart, and a node whose thread and memory cost stops following its query count.
> Clustering is not built — and it is now inside GA rather than after it.**
>
> [ADR-039](docs/adr/039-ga-includes-the-known-gaps-and-clustering.md) sets the road to GA: the seven
> known gaps below, then cluster mode, then GA. It supersedes ADR-038, which cut GA to a single node
> — kept, because its reasoning still holds if the scope is ever cut again, and marked superseded so
> it cannot be read as current.
>
> Written as *what is true now* rather than as a history of waves. The wave-by-wave version of this
> section said "no UI" nine lines above a paragraph describing the console, and listed watermark
> generation as unbuilt a year after it was built. A changelog is a bad status report: every
> sentence is true of the moment it was written and none of them expire.

### What works, end to end

| | |
|---|---|
| **SQL** | Calcite parses and optimises; the plan becomes Pravaha's own operator tree; rows travel from a plugin reader through an ingest pump into a lane's off-heap inbox and out to a sink. Whole-stage code generation runs roughly **10× the interpreted path**. |
| **Continuous queries** | Registered with a name, a state and an end. Paused, resumed, dropped. Identical SQL shares one computation under many names, so ten desks asking the same question cost one read of the source. |
| **Windows** | Tumbling, sliding and session, with slicing. A window publishes when time passes its end and not before. |
| **Event time** | Watermarks are generated as well as handled: a query derives one from the event-time column its stream declares, and a partition that goes quiet stops holding the rest of the query back. |
| **Corrections** | Late data reopens a closed window as a retraction of the published answer plus the corrected one — declared per stream with `allowedLateness`, defaulting to zero, because a revising query is no longer safe for an append-only sink and that must be a choice. |
| **Z-set weights** | Every change carries `+1` or `−1`, through the engine, across the wire, and into both SDKs. |
| **Sources** | Filesystem (bounded, or `tail -f` with `follow: true`), feedfile, JDBC, Delta and Aerospike. Filters are pushed into the store on every path a deployment uses. |
| **Joins** | Stream-to-stream, and temporal lookup joins against a dimension table, reachable from a registered query. |
| **Serving** | The maintained view is read back by key in microseconds, or subscribed to for changes per commit. |
| **Recovery** | Checkpoints carry operator state, source offsets and the served view, cut at one point across every input (ADR-008); a restart resumes rather than replaying or starting empty. |
| **Survival** | A node claims the checkpoint root and the registry journal it writes, so two nodes cannot silently prune and replay each other's state (`PRV-4003`, override with `pravaha.state.allow-shared`). A standby (`pravaha.standby.enabled`) takes over when the claim goes stale and reports what the takeover lost rather than implying continuity. A bad input line goes to a dead-letter queue (`pravaha run --dlq <file>`) instead of ending the run. |
| **Scale on one node** | A registered query no longer costs a platform thread. Lanes are driven by a fixed pool sized to the cores (`LaneRunner`, ADR-027), the watermark and checkpoint clocks are one shared timer for the process, and 200 queries cost **24 platform threads — one per core, fixed** — where they used to cost 400. Off-heap per query is **~1,024 KiB idle, ~1,328 KiB active**, down from ~5 MiB, and every component reports its own bytes by name (ADR-036). |
| **Clients** | Flight SQL, a Java SDK, a Python SDK, a CLI, and a console. Authentication, authorisation, row filters and prepared statements. Also a **PostgreSQL wire protocol gateway** (`pravaha.pgwire.enabled`, read path only) so `psql` and any Postgres driver can read a maintained view without a Flight SQL client — **off by default, because this slice has no TLS**. |

### What is not built, stated plainly

- **Multi-node execution is deferred** (ADR-034). The engine targets one node scaled to its cores;
  the clustering coordination code is carried unused.
- **No Spring Boot starter** (ADR-020). The engine core contains no Spring and sits behind a plain
  `PravahaEngine` seam, so embedding it never dictates your Spring version.
- **A sink can be configured but no query writes to one** (W8-13, half built). `pravaha.sinks.<name>`
  now names a `StreamSinkPlugin`, `ServiceLoader` finds it by the name it reports for itself, and a
  binding resolves to an opened, writable instance — proven end to end with no query involved. What
  is still missing is the attachment: nothing resolves *which registered query's output goes to which
  sink*, so every continuous query still writes to a served view, which applies a retraction
  correctly. **Changelog negotiation therefore remains reachable from nothing** — there is still
  nothing for `ChangelogAnalysis.checkAgainst` to refuse, and `ErrcSqlTest` fails the moment anything
  attaches a query to a sink without calling it first, which is the silent-corruption case in design
  §15.5.
- **The L0 off-heap state map is gone** (W8-12). Operator indexes and accumulators are on-heap
  `HashMap`s; the off-heap class that was meant to replace them could only hold fixed-width keys and
  could not key a `GROUP BY` containing a string.
- **The console is a functional admin console on purpose** — it manages queries, tails a view and
  renders the documentation. It is not the design-system product surface §23.20 describes.
- **A lane still runs one query.** `LaneRunner` shares a lane's *thread* between lanes, which is
  what removed the thread-per-query cost; `LaneMultiplexer` — which would share one inbox and one
  arena between many pipelines — is built, tested and wired to nothing (W9-8). It is no longer
  *blocked*: a row now carries the identity of the stream it came from (W9-9). It is *large*, and
  the aligned checkpoint barrier is why — three hundred queries on one lane each advancing a
  watermark every second would cut the lane's batches short several hundred times a second (W9-10).
- **N queries over one Aerospike set are one scan.** Each scan is throttled to `scan.interval.ms`
  (one second by default), and since SRC-3 one reader per *source binding* feeds every query bound
  to it — four queries over one set measured at 1.0 scans/s between them, where it was 1.0 each
  (ADR-036 §3). Sources that promise exactly-once or ordering within a partition keep a reader per
  query: the handover that lets a late query join a running reader cannot preserve either.
- **State spills nowhere.** A query that reaches its ceiling is still refused, not degraded; what
  Wave 9 added is the ability to *see* the ceiling coming (ADR-037 B1). The on-disk tier is B2,
  scoped and not started.
- **Projection and partial-aggregate pushdown**, and a Cassandra plugin, are designed and not built.

### What cannot be measured here

**Gates P2 and P3 are blocked on hardware, not code.** The throughput and scaling figures need 16
physical homogeneous cores; the development machine is a 12-core heterogeneous laptop part. The
evidence packs in [`docs/gates`](docs/gates/) say exactly what is and is not measurable, and no
number from this machine is quoted as if it were. The Aerospike edition question in
[Appendix B](docs/system_design.md#appendix-b--immediate-next-steps) has procurement lead time and
is worth settling early.

What Wave 9 *can* report is a **cost per query** — threads, off-heap bytes, file descriptors,
registration time — which is a count rather than a rate and is measured by `NodeScaleTest` and
`SourceScaleTest` on the machine that exists. No throughput number is quoted from either.

---

## Try it

```bash
./mvnw -q -DskipTests install

# Register a continuous query, ask its view a question, then watch it change.
pravaha register  --name user_volume --sql "SELECT user_id, SUM(amount) AS total FROM txn \
                    GROUP BY TUMBLE(event_time, INTERVAL '1' MINUTE), user_id" --keys 0
pravaha query     --sql "SELECT total FROM user_volume WHERE user_id = ?" --params u1
pravaha subscribe --view user_volume --filter user_id=u1
```

`pravaha queries`, `pause`, `resume` and `drop` manage what is running; `run`, `explain` and
`validate` work without a server.

Register a continuous query, ask the view a question, then watch it update. Ten minutes end to end:
[**Quickstart**](docs/QUICKSTART.md).

A file source is a bounded read by default and ends where the file ends. `follow: true` makes it
`tail -f` — rows appended while the query runs arrive without anything being restarted — which is
what a continuous query over a file needs and did not have.

---

## What it is

Pravaha runs continuous SQL over your existing databases — Aerospike first, then
Cassandra/ScyllaDB, Kafka, Redis and PostgreSQL — and keeps the answers up to date as the data
changes. It then **serves those answers back** at microsecond latency, so you do not need a
second database to hold the results.

```sql
CREATE CONTINUOUS QUERY q_user_volume
INTO   user_volume_agg
SERVE  AS VIEW user_volume INDEXED BY (user_id)
AS
SELECT STREAM
    TUMBLE_END(event_time, INTERVAL '10' SECOND) AS window_end,
    t.user_id, p.tier,
    COUNT(*)      AS txn_count,
    SUM(t.amount) AS total_volume
FROM  txn_stream AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
       ON t.user_id = p.user_id
WHERE t.status = 'COMPLETED'
GROUP BY TUMBLE(t.event_time, INTERVAL '10' SECOND), t.user_id, p.tier
EMIT CHANGES;
```

That query is not an aspiration. Everything from `SELECT STREAM` down -- the tumbling window, the
temporal lookup join, the filter, the aggregates -- parses, plans and **runs against a real Aerospike
server** in `AerospikeContinuousQueryIT`, with the `WHERE` clause evaluated inside Aerospike rather
than after the read, and the answer read back by key without a second system in the call.

What is not yet parsed is the statement *around* it: `CREATE CONTINUOUS QUERY`, `INTO`,
`SERVE AS VIEW` and `EMIT CHANGES` are registration and lifecycle (design section 11.2), and the
engine is driven through its API until they exist. `SELECT STREAM` is accepted and redundant -- every
Pravaha query is continuous, so there is no non-streaming mode to distinguish it from.

…and the answer is read back from the same system, by key, without a second database in the call:

```java
// Over the wire, with the Java SDK.
try (QueryResult result = client.query("SELECT total_volume FROM user_volume WHERE user_id = ?", "u42")) {
    for (Row row : result) {
        long volume = row.getLong("total_volume");
    }
}
```

```python
# Or from Python, against the same engine.
for row in client.query("SELECT total_volume FROM user_volume WHERE user_id = ?", "u42"):
    volume = row["total_volume"]
```

A consumer that wants the changes rather than the current answer subscribes instead, and receives
one batch per commit with a weight on every row — `+1` for a row appearing, `-1` for one being
withdrawn — so a corrected window arrives as a retraction of the old answer followed by the new
one.

## Why it exists

Streaming SQL engines make you choose. Flink needs its own cluster, reads everything through
deliberately dumb connectors, and cannot answer a question about its own output. Materialize and
RisingWave are incremental and can serve queries, but they are cloud databases you move data
*into* — with no path to Aerospike or Cassandra. Hazelcast Jet embeds, but has neither real SQL
nor incremental maintenance.

**Pravaha is the only engine designed to hold all four properties at once:**

|  | What it means |
|---|---|
| **Embeddable** | A library inside your own Java process, or a server of its own. Same engine, same code paths. One node scaled to its cores — clustering is deferred (ADR-034). |
| **Store-native** | **Filters** are pushed *into* the store — against Aerospike and any JDBC source, so filtered rows never cross the network. Offered on every path a deployment uses, including a registered continuous query, which until recently scanned and filtered afterwards. Projection and partial-aggregate pushdown, and a Cassandra plugin, are designed and not yet built. |
| **Incremental** | Z-sets and DBSP-derived operators: work is proportional to what changed, not to how much data exists. Recursive SQL becomes expressible. |
| **Serving** | The maintained view *is* an indexed table in memory, with declared consistency and reported staleness. Built and working; the µs-latency target is a design goal that needs the reference hardware to measure honestly. |

Full competitive analysis, including the ten measurable claims this has to satisfy:
[design §2](docs/system_design.md#2-competitive-landscape--winning-strategy).

## Design highlights

| | |
|---|---|
| **Language** | Java 21 LTS, single language. Calcite plans; generated fused operators execute. [Why not Scala →](docs/system_design.md#4-language-decision-java-vs-scala) |
| **Execution** | Whole-stage code generation (Janino) over binary flyweight rows in off-heap arenas. No `Map<String,Object>`, no boxing, no allocation on the hot path. |
| **Concurrency** | Partitioned lanes, single-writer principle. One ring buffer, one state slice, one timer wheel per lane, and exactly one thread driving a lane *at a time* — since W9-4 that thread is shared: a fixed runner pool, one thread per core, drives every lane, so the node's thread count follows its cores and not its queries. No locks in steady state. |
| **State** | Off-heap hash arena, plus checkpoint files. **Designed** as three tiers with RocksDB as L1 (D5); the RocksDB tier is *not built* and is not a dependency. The defence against unbounded state today is refusal at plan time, not spill — but the ceiling is now visible before it is hit, through `pravaha_query_state_held` / `_ceiling` / `_fraction` (ADR-037). |
| **Correctness** | Exactly-once **state**: a checkpoint holds every source between rows, cuts every lane at one point and records the offsets of that same point, so restored state and replayed rows never overlap or gap (ADR-008). Rows crossing the lane-to-lane exchange are *not* cut and a checkpoint refuses rather than dropping them — no plan the engine compiles sends on the exchange, so that case is unreachable today. Output is **effectively-once**: `DeduplicatingSink` exists and is not yet wired, so a sink that is neither idempotent nor transactional can still see a duplicate after a restore. |
| **Operations** | Adaptive batching and backpressure to the source plugin are built. *Designed, not built:* skew remediation, elastic rescaling, blue/green updates, and the time-travel debugger. What runs today is a single node with a registry, checkpoints, metrics and a console. |

## Shape

Two processes, on purpose.

```
   pravaha-server  (Java 21)            Pravaha Console  (Python)
   engine, Flight SQL, /status   ◄───   FastAPI, built on the pravaha SDK
   plain HTML status page, works
   when the console is down
```

Queries are registered, listed, paused, resumed, dropped and subscribed to over **Flight**, not
over REST. The HTTP surface is deliberately small: `/status`, `/api/v1/streams`, and
`/api/v1/queries/validate` and `/explain`. There is no `POST /api/v1/queries`.

The console is a **separate runtime** so the API boundary cannot be violated: a test enforcing
"the console may only use the public API" can be waived under deadline pressure, and a Python
process simply cannot reach into a Java engine. It also makes the console the first real consumer of
the published SDK — a proof of the integration story rather than an assertion (ADR-024), and its own
artefact rather than a source tree to install (ADR-033).

The engine itself also embeds. One core, several ways to run it:

| Mode | Artifact | Spring | Use |
|---|---|---|---|
| **A** Plain embedded | `pravaha-embedded` | none | Any Java app. A lifecycle seam only today — start, stop, state, plugins; it cannot register or read a query, and the CLI does not use it |
| **B** Spring-embedded | `pravaha-spring-boot-starter` — **not built yet** (ADR-020) | auto-config into *your* app | Add continuous SQL to a service you already run |
| **C** Server | `pravaha-server` | it *is* a Spring Boot app | Standard production deployment |

Mode B is **not built**, and the shape it is intended to take is this — an annotation that does not
exist yet, shown so the intent is on the record rather than discovered from an ADR:

<!-- illustrative: describes an unbuilt API -->
```java
@Service
class FraudService {

    @PravahaListener(query = "high-value-orders", concurrency = 4)   // NOT BUILT (ADR-020)
    void onHighValueOrder(HighValueOrder order) {     // typed from the query's schema
        riskEngine.evaluate(order);
    }
}
```

The engine core contains **no Spring** — it sits behind a plain-Java `PravahaEngine` seam, so
embedding Pravaha never dictates your Spring version.
[Details →](docs/system_design.md#22-deployment-modes--spring-boot-integration)

## The console

A **Python FastAPI application on the published SDK**, server-rendered, with everything vendored —
no CDN, because air-gapped deployment is a precondition rather than a nicety.

```bash
cd console && make install && make run     # :8090, engine at :9090
```

| | |
|---|---|
| `/` `/about` | What this is. Both answer with the engine down |
| `/overview` | What is registered, how much is shared, how many live feeds |
| `/queries` | Filter, sort and page — every filter in the URL, so a view is shareable |
| `/queries/{name}` | SQL, fingerprint, siblings, a live tail, and pause/resume/drop |
| `/workbench` | Ask once with parameters, or register it |
| `/help` `/tutorials` | The `docs/` set and the five worked systems, rendered in place |
| `/api/v1/...` | The console's own JSON services, which its screens are built on — not the engine's |

One engine subscription serves every browser watching a view, ref-counted: ten analysts on one
dashboard are ten connections and **one** subscriber on the engine. Every page renders before its
JavaScript does, and every control is a real form, so the console works when a script does not.

**What it is not.** A functional admin console, on purpose. The IDE-grade workbench, the live plan
DAG and the time-travel debugger that §23 specifies are **not built**, and neither is the §23.20
release gate — no Storybook, no visual-regression baseline, no WCAG 2.2 AA audit. Light/dark/
terminal, density, keyboard paths, deep links and the eight states of §23.12 are implemented; they
are not audited.

[Specification →](docs/system_design.md#23-the-pravaha-console--web-ui) ·
[How it is built →](console/README.md)

## Documentation

**Start here:**

| | |
|---|---|
| **[Quickstart](docs/QUICKSTART.md)** | Clone to a running continuous query. Ten minutes |
| **[Concepts](docs/CONCEPTS.md)** | The eight ideas everything follows from. Most surprises are one of these working correctly |
| **[User guide](docs/USER_GUIDE.md)** | The whole surface, task by task, in Java, Python and the shell |
| **[Case studies](examples/case-studies/)** | Five worked systems — trade processing, banking, finance, trading, biology. A store to stand up, a data model, a continuous query and the app code |

**Reference:**

| | |
|---|---|
| [Streams, queries and SQL](docs/CONTINUOUS_QUERIES.md) | What you write, end to end — and every construct that works or is refused, checked by a test so it cannot rot |
| [Troubleshooting](docs/TROUBLESHOOTING.md) | Every `PRV-` code, and the five you will actually meet |
| [Operations](docs/OPERATIONS.md) | Memory, disk, admission, what to watch, and what is honestly not solved |
| [Security](docs/SECURITY.md) | Authentication, authorization, row filters, audit |

**How and why:**

| | |
|---|---|
| [Architecture](docs/ARCHITECTURE.md) | How it is put together, and why each part is shaped that way |
| [System design](docs/system_design.md) | The full specification, 33 sections |
| [Decision records](docs/adr/) | Every architectural decision and why, including the ones later reversed |
| [Implementation plan](docs/implementation_plan.md) | Waves, epics, staffing, risks, descope ladder |
| [Handover](docs/HANDOVER.md) | Current state and what to pick up next |
| [Gate records](docs/gates/) | Evidence packs. The retrospectives are the honest part |
| [Original SRS](docs/initial_req.md) | The 1.0-DRAFT this design supersedes. Kept for provenance |

Several of these are **verified by the build** rather than maintained by memory: every SQL statement
in `CONTINUOUS_QUERIES.md` and in the case studies is planned against the real engine (and 26 of its 42
supported constructs have their answer asserted), `ErrcCrossCuttingTest` fails the build if
`TROUBLESHOOTING.md`'s code table and the `ErrorCode` declarations disagree in either direction,
`QuickstartCommandsTest` runs the quickstart's serverless commands out of the document and checks
what they print, and a freshness test checks that every module is described, that every decision a
document cites has an ADR, and that this file's status badge, status line and roadmap table agree
about which wave it is. Four checks added after the Wave 9 sweep close the gaps that sweep found:
every `pravaha.lane.*` setting and every per-query gauge must be named in
[`OPERATIONS.md`](docs/OPERATIONS.md), a message telling an operator to raise `pravaha.x.y` must name
a key `application.yaml` actually declares, every ADR must have a row in its index with the count in
[`HANDOVER.md`](docs/HANDOVER.md) held against the directory, and
[`FINDINGS.md`](docs/qa/FINDINGS.md)'s own header totals must match the register beneath them. Its link check reaches only thirteen files and only links with a file
extension — roughly a third of the repository's internal links; `docs/adr/`, `examples/`, `console/` and `sdk/` are
outside it, and anchors are not checked at all (DOCX-034, DOCX-050).

All of them are also readable **inside the console**, with contextual help cards on each page.

## Project coordinates

```xml
<groupId>com.ash.messaging</groupId>
<artifactId>pravaha</artifactId>
<version>0.1.0-SNAPSHOT</version>
```

Base package `com.ash.messaging.pravaha`. Requires **JDK 21+**; builds with the vendored Maven
wrapper.

```bash
./mvnw clean verify                                  # full build
./mvnw -T1C -DskipITs -Dbenchmarks.skip=true test    # fast inner loop
./mvnw -Pall verify                                  # everything, as CI runs it
```

No system Maven needed — the wrapper is vendored. See
[implementation plan §2](docs/implementation_plan.md) for the toolchain and build profiles, and
[`docs/QUICKSTART.md`](docs/QUICKSTART.md) to actually run something.

**Modules currently built**, in build order — this list is checked against `pom.xml` by
`DocumentationFreshnessTest`, because the previous one named eleven modules and the build had
thirty:

`pravaha-bom`, `pravaha-api`, `pravaha-common`, `pravaha-algebra`, `pravaha-catalog`,
`pravaha-sql`, `pravaha-runtime`, `pravaha-codegen`, `pravaha-state`, `pravaha-backfill`,
`pravaha-security`, `pravaha-serving`, `pravaha-cluster`, `pravaha-registry`, `pravaha-flight`,
`pravaha-pgwire`, `pravaha-connect`, `pravaha-testkit`, `pravaha-benchmarks`, `pravaha-it`,
`pravaha-embedded`, `pravaha-cli`, `pravaha-server`.

`pravaha-pgwire` is the newest of those and the one most likely to be looked for by name. It serves
the **PostgreSQL wire protocol, read half only**: a view is a table, `SELECT` over one is answered
by the same `ViewQuery` the Arrow Flight gateway calls, and there is no write path to expose because
the planner already refuses one. It exists so that `psql`, DBeaver, Grafana and every ORM can reach
the engine without installing an Arrow Flight SQL client, which almost nobody has. Slice 1 is the
simple query protocol and nothing else — no extended query protocol, no prepared statements, no
cursors, no `COPY`, and **no TLS, so a password configured on it crosses the wire in the clear**.
Each of those is refused by name rather than half-built; the full list, with a reason each, is in the
module's `package-info.java`.

Plugins: [`filesystem`](plugins/pravaha-plugin-filesystem), [`delta`](plugins/pravaha-plugin-delta),
[`feedfile`](plugins/pravaha-plugin-feedfile), [`jdbc`](plugins/pravaha-plugin-jdbc),
[`aerospike`](plugins/pravaha-plugin-aerospike), [`cluster-zookeeper`](plugins/pravaha-cluster-zookeeper).

SDKs: [`sdk/pravaha-sdk-java`](sdk/pravaha-sdk-java),
[`sdk/pravaha-sdk-java-flight`](sdk/pravaha-sdk-java-flight), [`sdk/python`](sdk/python). The
operator console is its own artefact in [`console`](console).

## Roadmap

| Wave | Weeks | Milestone | |
|---|---|---|---|
| 1 | 1–2 | Foundations; deterministic harness | ✅ `M1` |
| 2 | 3–5 | Vertical slice; **go/no-go on the incremental core** | ✅ `M2` |
| 3 | 6–11 | Codegen, lanes, exchange — Profile A ≥ 1.2 M rec/s/lane | ✅ built · gate P2 needs hardware |
| 4 | 12–18 | Windows, watermarks, late data, tiered state | ✅ built · gate P3 needs hardware |
| 5 | 19–25 | Joins, Aerospike, checkpointing and recovery | ✅ built |
| 6 | 26–32 | Backfill, blue/green, serving layer — **first defensible demo** | ✅ built |
| 7 | 33–38 | Flight SQL, SDKs, security, registration, subscriptions, console | ✅ built · console included |
| 8 | 39–45 | Survival on one node — state ownership, checkpoint barriers, standby ([ADR-035](docs/adr/035-wave-8-is-survival-not-distribution.md)) | ✅ built · gate P7 pack not written |
| 9 | — | One node, thousands of queries — lane multiplexing onto shared threads, a shared clock, arena and inbox sizing, an Aerospike scan interval, state you can watch approach its ceiling ([ADR-036](docs/adr/036-one-node-thousands-of-queries.md), [ADR-037](docs/adr/037-state-that-degrades-instead-of-dying.md)) | ✅ built · no gate pack |
| 10–11 | 46–62 | **Redefined twice, and now holds less.** [ADR-038](docs/adr/038-one-node-ga.md) moved the time-travel debugger and the Nexmark head-to-head out to the roadmap; [ADR-039](docs/adr/039-ga-includes-the-known-gaps-and-clustering.md) superseded it, so what stands between here and **GA** is the seven known gaps above, then cluster mode | ▫️ not started |

Waves 1–7 are complete and the gap work above is **not** wave 10 — it is the unfinished part of
waves 8 and 9 (`W8-12`, `W8-13`, `W9-8`), which is why the wave counter still reads 9 while
[ADR-039](docs/adr/039-ga-includes-the-known-gaps-and-clustering.md) item 1 is closed and item 5 is
half closed.

`main` and `develop` are the **same commit**: the drill after every piece of work is to push
`develop` and then fast-forward `main`, so `main` is no longer a wave behind. It carries 316 commits
past `M7`, which is the newest tag — waves 8 and 9 are merged but **not tagged**, and that is the
part of the old sentence that is still true. **Wave 9 was inserted by [ADR-036](docs/adr/036-one-node-thousands-of-queries.md)** ahead of
the control-plane and GA waves, which keep their content and their week estimates and move down by
one — its own length was never estimated, which is why its Weeks cell is empty rather than invented.
"Built" means the code is there and tested; it does not mean a performance gate passed, and
[`docs/gates`](docs/gates/) says which ones did not and why — it holds no pack for waves 5, 6, 8
or 9.

[Full roadmap with acceptance gates →](docs/system_design.md#31-delivery-roadmap)

## Contributing

Not yet open for outside contributions while the foundations settle. The standards that will apply
are already in force and enforced by the build — see
[implementation plan §3–§4](docs/implementation_plan.md) for the definition of done, the branching
model, and the rules the build checks mechanically:

- source files stay under 1500 lines (`SourceFileSizeTest`)
- every production type carries JUnit coverage, with JaCoCo gates per module
- no Spring in the engine core, no `Serializable`, no unbounded collections (`ArchitectureRulesTest`)
- every file carries the copyright and licence notice (`LicenseHeaderTest`)

## Legal

**Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.**

**PROPRIETARY AND CONFIDENTIAL.** Project Pravaha — its source code, design documents,
architecture, algorithms, data formats, documentation and brand marks — is the sole and exclusive
property of Ashutosh Sinha. No licence or right is granted by implication or otherwise.

Use, copying, modification, distribution and disclosure are prohibited except with the express prior
written permission of the copyright holder. See [`LICENSE`](LICENSE) for the full terms.

If you have obtained a copy of this software without written authorisation, you are not permitted to
retain it; destroy all copies and contact ajsinha@gmail.com.

Third-party open-source components used by Pravaha remain the property of their respective owners
and are governed by their own licences, which this project's terms do not affect. Their attribution
notices are reproduced in [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md).

The Pravaha name, the flow mark, and the slogan "Ask once. Answer always." are proprietary; see
[`brand/README.md`](brand/README.md).
