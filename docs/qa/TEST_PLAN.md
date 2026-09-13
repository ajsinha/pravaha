# Pravaha QA test plan

Written after a first QA round produced 254 cases and the owner observed, correctly, that a system
this size needs closer to **1,000+** and that going fast was costing detail. This plan is the
inventory first and the case budget second, so that coverage is *derived from the surface* rather
than from how many cases an area happened to suggest.

## 1. What has to be covered — measured, not estimated

| Surface | Count | Source |
|---|---|---|
| Modules with production code | 27 | `pravaha-*`, `plugins/*`, `sdk/*` |
| Distinct `pravaha.*` config keys | 37 | `@Value`, `@ConfigurationProperties`, `Configuration.get*` |
| Data types | 16 | `TypeName` |
| Physical operators | 10 | `runtime/plan/*Operator` |
| Aggregate kinds | 5 | `COUNT COUNT_DISTINCT MIN MAX SUM` |
| Window kinds | 3 | `TUMBLE HOP SESSION` |
| Read consistency modes | 4 | `Latest Consistent AtLeast AsOf` |
| Documented SQL constructs | 77 (42 ✅ / 22 ❌) | `SQL_SUPPORT.md` |
| CLI commands | 9 | `validate explain run query register drop queries subscribe version` |
| REST endpoint paths | 6 | `pravaha-server/api` |
| Flight control verbs | 5 | `ControlWire` |
| Plugins | 9 (5 source, 2 sink, 2 lookup) | `plugins/` |
| Error codes | 104 | `new ErrorCode(...)` |
| ADRs (each a decision with behaviour) | 35 | `docs/adr/` |
| SDKs | 3 (Java, Java-Flight, Python) | `sdk/` |
| Console route modules | 6 | `console/routes/` |

**The combinations are where the cases come from, and where the first round was thin.** A type is
not one case: 16 types × {projection, filter, aggregate, join key, group key, window boundary, wire
serialisation, null, boundary value} is already ~140 before any SQL construct is considered. The
first round tested FLOAT64 in an aggregate and found the answer silently wrong — the other 15 types
in that same position were never tried.

## 2. Case budget by area

Derived from the inventory above. **Target ≈ 1,180.** Round 1 delivered 254, so roughly a fifth.

| # | Area | Cases | What drives the number |
|---|---|---:|---|
| A | SQL semantics & refusals | 190 | 77 constructs × happy / boundary / null / error |
| B | Types & expressions | 150 | 16 types × 9 positions, plus the expression layer's own matrix |
| C | Windows, watermarks & event time | 90 | 3 window kinds × ordering, lateness, idleness, close, restart |
| D | Incremental correctness (Z-sets) | 70 | insert / retract / update / net-zero × each operator |
| E | Joins | 60 | inner, lookup, multi-key, refusals × lane routing × eviction |
| F | Aggregates | 60 | 5 kinds × types × keyed / global / windowed × null |
| G | Ingestion & source plugins | 80 | 5 sources × lifecycle, malformed input, backpressure, partitions |
| H | Sinks & subscriptions | 45 | 2 sinks + subscription delivery, slow and disconnecting readers |
| I | Security & authorization | 95 | policy × auth × TLS × row filters × every verb on both transports |
| J | Config surface | 110 | 37 keys × valid / invalid / default / interaction |
| K | Error codes | 104 | each code reachable, documented, and meaning one thing |
| L | CLI | 75 | 9 commands × flags, exit codes, stdin, unicode, failure |
| M | REST API | 50 | 6 paths × method, auth, body, content type, concurrency |
| N | Flight & Flight SQL | 55 | 5 verbs + prepared statements, DoPut, tickets, metadata |
| O | State, checkpoints & recovery | 60 | write, prune, restore, journal, restart, corruption |
| P | Code generation | 35 | upgrade equivalence, refusals, metaspace, differential |
| Q | Aerospike & state tier | 45 | 4 strategies, pushdown equivalence, exactly-once claims |
| R | Cluster & coordination | 30 | SINGLE / REPLICATED / PARTITIONED × mechanism, PRV-9002 |
| S | SDKs (Java, Flight, Python) | 45 | 3 SDKs × connect, auth, TLS, query, register, subscribe |
| T | Console UI | 35 | 6 route modules × auth, pages, error states |
| U | Documentation A–Z | 60 | every instruction executed literally, both-direction key audit |
| V | Performance & resource | 30 | idle cost, throughput, leaks over cycles, backpressure |
| W | Deployment & lifecycle | 50 | jars, launchers, container, signals, ports, health, metrics |
| | **Total** | **~1,180** | |

## 3. Rules this round adds, learned from round 1

Round 1's failures were not only in the product. These are the process fixes:

1. **A frozen artefact.** Round 1's verification pass ran while I rebuilt the tree underneath it; the
   deployment agent had to tag every verdict `[handed-over]` or `[in-progress]`. No code changes
   while a QA round is running. None.
2. **Every case names its falsifier.** A case must say what result would prove the feature broken.
   Round 1 had a pause test that passed because the source ran dry, and a row-count test that passed
   because 200,000 rows collapsed into 500 keys.
3. **Vacuity check on every PASS in a state-dependent area.** If a case could pass with the feature
   removed, it is not a case yet.
4. **Combinations are cases, not footnotes.** "FLOAT64 in an aggregate" was one case and found a
   silent wrong answer; the other fifteen types in that position were not cases at all.
5. **Coverage is reported against this inventory**, so a gap is visible as a gap rather than as an
   area nobody thought of — which is how Aerospike, code generation and the algebra core were missed
   entirely in round 1.

## 4. What needs infrastructure or a human

Recorded up front so it is not discovered as a surprise mid-round:

- **Aerospike** — a real instance, and an edition decision. Community Edition has no change feed, and
  the design says that determines whether the exactly-once path exists at all.
- **Multi-node cluster** — ZooKeeper, and more than one node, for `REPLICATED` / `PARTITIONED`.
- **Sustained load** — hours, and a machine not shared with the rest of QA.
- **Power-loss durability** — distinct from SIGKILL, needs a VM or a container runtime that can be
  cut.
- **TLS with a real CA** — the shipped client has no way to supply one, which is itself a finding.

## 5. Execution order

Correctness before convenience, and each phase gated on the last.

| Phase | Areas | Why first |
|---|---|---|
| 1 | D, B, F, A | If the answers are wrong, nothing above matters |
| 2 | C, E, G, H | The streaming machinery that produces those answers |
| 3 | I, N, M, K | The surfaces that expose them, and the codes they fail with |
| 4 | O, P, Q, R, V | Durability, the fast path, the state tier, scale |
| 5 | J, L, S, T, W, U | Everything a user touches, and the documents describing it |

**Done** means: every case executed, every FAIL either fixed and re-verified or recorded in
`FINDINGS.md` with a status, and no area reporting coverage below its budget without a stated reason.
