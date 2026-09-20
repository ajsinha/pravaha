---
title: Every setting
slug: settings-index
category: reference
order: 20
icon: sliders
summary: "Every pravaha.* setting a node reads — key, default, what it does and where it is explained — including the ones only the embedded engine and the Spring Boot starter read."
badge: INDEX
audience: Operators
keywords: [settings, configuration, application.yaml, properties, defaults, environment, node.id, flight, pgwire, security, lane, checkpoint, spill, standby, watermark, cluster, dlq, journal]
guide: operations
related: [configuration, sizing-lanes, state-spill, checkpoints-recovery, metrics-index]
---

This is the complete list of what a Pravaha server reads under `pravaha.*`, grouped as
`application.yaml` groups it. Each key links to the topic that explains it in context. Defaults are the
engine's own — a node that sets nothing behaves exactly as described here.

**How to set one.** The server is a Spring Boot application, so every key can be given in
`application.yaml` (or an `application-<profile>.yaml`), as a command-line argument
(`--pravaha.checkpoint.directory=/var/lib/pravaha/ckpt`), or as an environment variable in Spring's
relaxed form (`PRAVAHA_CHECKPOINT_DIRECTORY`). Durations take a unit (`30s`, `1m`, `PT24H`); sizes
take one too (`512MB`, `20GB`). See [Configuration](/help/topics/configuration).

A few keys are bound from configuration classes rather than written out in the shipped
`application.yaml`; they are real settings all the same, bound by name from the engine's properties classes.


## The node

| Setting | Default | What it does |
|---|---|---|
| `pravaha.node.id` | `pravaha-node-01` | Names the node in logs and in the ownership marker of every directory it writes. A [standby](/help/topics/standby) uses the **same** id as its primary |

## Client surfaces

| Setting | Default | What it does |
|---|---|---|
| `pravaha.flight.enabled` | `true` | Serve Arrow Flight SQL — the protocol the SDKs, the CLI and the console speak |
| `pravaha.flight.host` | `0.0.0.0` | Where Flight listens. Write an address in full: `127` is legal input to `InetAddress` and means `0.0.0.127`, so an abbreviated one is refused at startup (PRV-3010). An IPv6 host is reported bracketed, `[::1]:9090` |
| `pravaha.flight.port` | `9090` | Flight's port — what every client and example defaults to. `0` lets the operating system pick, and `GET /api/v1/status`'s `flight` field reports the port it picked. Outside 0-65535 is PRV-3010 naming the key, rather than gRPC's own unnamed argument check |
| `pravaha.flight.tls.certificate` | *empty* | PEM certificate chain. With the key, Flight serves TLS; without both, plaintext. One without the other is refused at startup (PRV-6104) — see [TLS](/help/topics/tls) |
| `pravaha.flight.tls.key` | *empty* | PEM private key, the other half of the pair |
| `pravaha.pgwire.enabled` | `false` | Serve the read-only [PostgreSQL gateway](/help/topics/pgwire), for `psql`, Grafana and ORMs |
| `pravaha.pgwire.host` | `0.0.0.0` | Where it listens |
| `pravaha.pgwire.port` | `5432` | Its port. Change it if a PostgreSQL server on the host already has 5432 |
| `pravaha.pgwire.tls.certificate` | *empty* | PEM certificate; `sslmode=require` then negotiates on the same port. Refused at startup if half-set (PRV-6206) |
| `pravaha.pgwire.tls.key` | *empty* | PEM private key |

The HTTP API and Prometheus are on Spring's `server.port` (`8080`).

## Security

See [Authentication](/help/topics/authentication), [Authorization](/help/topics/authorization) and
[Audit](/help/topics/audit). **The shipped defaults refuse to start** (PRV-7004) until one of the three
coherent shapes is chosen — real credentials, an authenticated-only policy, or an open server written
down as such.

| Setting | Default | What it does |
|---|---|---|
| `pravaha.security.authentication` | `none` | `none` or `token` — whether callers present a bearer token |
| `pravaha.security.policy` | `permissive` | `permissive` (everyone sees everything) or `authenticated` (only verified callers see anything); or a `SecurityPolicy` of your own |
| `pravaha.security.allow-anonymous` | `false` | Acknowledges an open server. Deliberately awkward to set by accident |
| `pravaha.security.tokens` | *none* | Static credentials for development and tests: a map from **the token itself** to `id` (**required**), `tenant` (default `public`) and `roles`. The `id` is what the audit trail and the registry journal record, so it must not be allowed to fall back to the map key -- which is the credential. A real deployment implements a `TokenVerifier` |
| `pravaha.security.audit` | `none` | `none`, `memory` (in-process only, readable by nothing) or `file` (JSON Lines an operator can read) |
| `pravaha.security.audit-file` | `pravaha-audit.jsonl` | Where `audit: file` writes; created owner-read-write only. A path the node cannot write is PRV-7004 at startup |
| `pravaha.security.audit-rotate-bytes` | `67108864` (64 MiB) | Rotate the audit file past this size |
| `pravaha.security.audit-keep` | `5` | Rotated audit files kept; the oldest is deleted |
| `pravaha.security.audit-readers` | `[admin]` | Roles whose holders may read the audit trail over `GET /api/v1/audit` under `policy: authenticated` |
| `pravaha.security.audit-recent` | `10000` | Recent decisions kept readable in memory over `GET /api/v1/audit`; must be at least 1 |

## Streams, sources, lookups and sinks

These are maps whose keys are **your** names — a stream called `txn` is configured under
`pravaha.streams.txn`. A name with anything but letters, digits and hyphens has to be **bracketed**
-- `"[txnü]": {schema: ...}` -- because Spring canonicalises a map key before binding it and
discards one it cannot. A key that reaches nothing is refused at startup with PRV-1027; it used to
be dropped with no message at any level, and the first query against the stream reported it as
unknown. See [Streams](/help/topics/streams), [Sources](/help/topics/sources-overview),
[Lookups](/help/topics/lookups) and [Sinks](/help/topics/sinks-overview).

| Setting | Default | What it does |
|---|---|---|
| `pravaha.streams.<name>.schema` | — | The stream's columns, `name:TYPE,name:TYPE` (`?` after a type for nullable). It must match a source binding's own `schema` option for the same stream, and a disagreement is refused at startup: one is what queries are planned against and the other is what the plugin decodes with |
| `pravaha.streams.<name>.event-time` | *none* | The column carrying each row's own time. **Without it no watermark advances, and a windowed query over the stream is refused with `PRV-2002`** |
| `pravaha.streams.<name>.out-of-orderness` | `10s` | How late *this* stream's rows may be. Overrides `pravaha.watermark.out-of-orderness`. Needs `event-time`: declaring it without one is refused at startup, because lateness needs an event time to be about (T-6) |
| `pravaha.streams.<name>.allowed-lateness` | `0s` | How long after a window closes a late row may still correct it (a `−1` and a `+1`). Needs `event-time`; non-zero makes windowed queries over the stream revise, so they need a sink that takes retractions (PRV-2041). See [late data](/help/topics/late-data) |
| `pravaha.sources.<stream>.plugin` | — | The source plugin feeding the stream: `filesystem`, `feedfile`, `jdbc`, `delta`, `aerospike`, `cassandra`, `postgres-cdc`, `kafka` |
| `pravaha.sources.<stream>.options.*` | — | That plugin's own options, passed to it untouched. **Note the `options:` nesting** — a key one level too high is not read |
| `pravaha.lookups.<table>.plugin` | — | A dimension table for temporal joins: `jdbc-lookup` or `aerospike-lookup` |
| `pravaha.lookups.<table>.options.*` | — | Its options |
| `pravaha.sinks.<sink>.plugin` | — | A sink a query may write to by name: `filesystem`, `jdbc-sink`, `aerospike-sink`, `kafka-sink`, `delta-sink` |
| `pravaha.sinks.<sink>.options.*` | — | Its options, including the `schema` the query's output must match (PRV-8010) |

Each connector's options are on its own page: [filesystem](/help/topics/source-filesystem),
[feedfile](/help/topics/source-feedfile), [jdbc](/help/topics/source-jdbc),
[delta](/help/topics/source-delta), [aerospike](/help/topics/source-aerospike),
[cassandra](/help/topics/source-cassandra), [postgres-cdc](/help/topics/source-postgres-cdc),
[kafka](/help/topics/source-kafka), and the
sinks [filesystem](/help/topics/sink-filesystem), [jdbc-sink](/help/topics/sink-jdbc),
[aerospike-sink](/help/topics/sink-aerospike), [kafka-sink](/help/topics/sink-kafka).

## Durability: journal, checkpoints, dead letters

| Setting | Default | What it does |
|---|---|---|
| `pravaha.registry.journal` | *empty* | A file where registrations are written down and replayed at startup. Unset: queries live only in memory and a restart loses them (the node warns). **Its directory has to exist** -- it used to be created on the first append, so a typo journalled to the wrong place silently; a missing one is now PRV-8006 at startup. Holds SQL and bound values — permission it like data |
| `pravaha.checkpoint.directory` | *empty* | Where each query checkpoints its state, one directory per query. Unset: no checkpoints, and a restart recovers questions but not answers. Needed by a standby and by exactly-once sinks. A path that exists and is not a directory, or one whose parent does not, is PRV-4093 at startup |
| `pravaha.checkpoint.interval` | `1m` | How often a query checkpoints. Bounds how much a restart replays — and how far a transactional sink trails the view. **Write the unit**: a bare number is read as milliseconds by Spring and is refused (PRV-1023) |
| `pravaha.checkpoint.keep` | `3` | Checkpoints kept per query, at least 1 (PRV-1026 at startup). Counted, not timed: an age rule would delete the last fallback after a quiet night |
| `pravaha.checkpoint.timeout` | `30s` | How long one checkpoint may take before it is abandoned (and counted in `pravaha_query_checkpoint_failures_total`) |
| `pravaha.dlq.directory` | *empty* | Where records a source cannot decode are written, one file per query, instead of stopping the source. Set and unwritable: the node refuses to start (PRV-4090). See [Dead letters](/help/topics/dead-letters) |
| `pravaha.dlq.max-bytes` | `268435456` | The largest one query's dead-letter file may grow. Past it the **oldest** entries are evicted, and the loss is written to `<query>.dlq.evicted`, warned about, and counted on every surface. `0` for no byte bound |
| `pravaha.dlq.max-entries` | `0` | The most entries one query's file may hold. `0` is off |
| `pravaha.dlq.max-age` | `0` | How long an entry is kept (`30d`, `PT72H`). `0` is off |

See [Checkpoints and recovery](/help/topics/checkpoints-recovery).

## The debugger

| Setting | Default | What it does |
|---|---|---|
| `pravaha.debug.sessions.max` | `4` | Debug sessions this node holds at once. PRV-8014 past it. The ceiling is memory: each session is a second copy of a query's lanes, arena and state |
| `pravaha.debug.session.ttl` | `15m` | How long a session nobody has touched survives. Checked on the way in to the next call, so a node that debugs nothing runs nothing extra |
| `pravaha.debug.session.max-rows` | `20000` | How many rows one session may consume. It keeps every one, so that it can be exported as a fixture |
| `pravaha.debug.step.max-rows` | `10000` | How far one `commit` or `until:` step searches before giving up. "Until it happens" over ten million rows is a call that never returns |

A session needs `pravaha.checkpoint.directory` set, because it forks from a checkpoint. See
[The time-travel debugger](/help/topics/time-travel-debugger).

## Lanes

What one registered query costs in memory, and how its lane waits. See
[Sizing and lanes](/help/topics/sizing-lanes) and [Lane sharing](/help/topics/lane-sharing).

| Setting | Default | What it does |
|---|---|---|
| `pravaha.lane.batch-size` | `512` | Rows handed to an operator at once. `batch-size × widest output row` must fit one arena slab (PRV-3001) |
| `pravaha.lane.wait-strategy` | `BACKOFF_PARK` | How an idle lane waits: `BUSY_SPIN`, `SPIN_THEN_YIELD`, `BACKOFF_PARK`, `BLOCKING`. Spinning costs a core per few idle queries; keep `BACKOFF_PARK` unless one latency-critical query owns the node |
| `pravaha.lane.inbox.cells` | `2048` | Cells in each lane's inbox — its burst capacity |
| `pravaha.lane.inbox.cell-bytes` | `512` | Bytes per cell. Must fit the widest row the query sees (PRV-3002). 2048 × 512 is a megabyte per query; 256 × 256 is 64 KiB |
| `pravaha.lane.arena.slab-bytes` | `4194304` (4 MiB) | Size of each arena slab operators write output batches into |
| `pravaha.lane.arena.max-slabs` | `8` | Slabs an arena may grow to |
| `pravaha.docs.base-url` | *unset* | Where a failure's help link points, with the code on the end — this console's own help, for most deployments. **No default**: unset, the engine prints no link and says where to look instead, because a link that does not resolve reads as a network problem at the worst moment (DOCX-21). A value that is not an absolute http/https URL is refused at startup with `PRV-1029` |
| `pravaha.lane.backpressure.high-watermark` | `0.8` | Inbox fill at which a query's source is paused. The pair is validated at startup: a high watermark outside (0, 1] or a low one at or above it is refused, naming both keys, rather than clamped |
| `pravaha.lane.backpressure.low-watermark` | `0.5` | Fill at which the source is resumed. The gap is what stops a saturated source pausing and resuming on alternate polls; widen it where a pause is expensive to undo, such as an Aerospike scan throttle |
| `pravaha.lane.multiplex.enabled` | `false` | Let queries share a fixed set of lanes instead of one apiece. A shared lane shares its fate |
| `pravaha.lane.multiplex.lanes` | `0` | Shared lanes; `0` means one per available processor |
| `pravaha.lane.multiplex.max-queries-per-lane` | `300` | The ceiling per shared lane; a query that fits nowhere (or reads more than one stream) gets a lane of its own |

## State and the spill tier

See [State and spill](/help/topics/state-spill).

| Setting | Default | What it does |
|---|---|---|
| `pravaha.state.allow-shared` | `false` | Let this node write state into a directory another node already owns. Only for sharing on purpose — a node reclaiming its own state after a crash does not need it (PRV-4003) |
| `pravaha.state.spill.enabled` | *unset* | Tri-state, explicit wins both ways: `false` keeps spilling off even with a directory set; unset means "on if a directory is set" |
| `pravaha.state.spill.directory` | *empty* | Where overflow slabs (memory-mapped files) are created when a query outgrows memory |
| `pravaha.state.spill.max-overflow-slabs` | `512` | Most overflow slabs one state store may hold — so one runaway join cannot take the whole budget |
| `pravaha.state.spill.compaction-threshold` | `0.5` | Fraction of a store's overflow space that must be free before its sparse slabs are compacted and their files released |
| `pravaha.state.spill.max-bytes` | `0` (no quota) | The node's disk budget for spilled state across every query (`20GB`, `512MB`). Past it: PRV-4005 |

## High availability and clustering

| Setting | Default | What it does |
|---|---|---|
| `pravaha.standby.enabled` | `false` | Run as a standby: hold no lanes, serve nothing, and take over from the newest checkpoint when the primary stops refreshing its claim. Same `pravaha.node.id` as the primary; needs `pravaha.checkpoint.directory`. See [Standby](/help/topics/standby) |
| `pravaha.cluster.mode` | `SINGLE` | `SINGLE`, `REPLICATED`, or `PARTITIONED` (refused by a node today, PRV-9002) |
| `pravaha.cluster.mechanism` | `single` | `single`, `socket` (no consensus), or `zookeeper` (plugin required) |
| `pravaha.cluster.socket.peers` | — | For `socket`: `"a=host1:9070,b=host2:9070,c=host3:9070"` |
| `pravaha.cluster.socket.heartbeat.millis` | `1000` | Socket coordinator heartbeat |
| `pravaha.cluster.socket.timeout.millis` | `5000` | Socket coordinator peer timeout |

See [Cluster mode](/help/topics/cluster-mode).

## Event time

| Setting | Default | What it does |
|---|---|---|
| `pravaha.watermark.idle-after` | `30s` | How long a partition may produce nothing before it stops holding the watermark back. Too long freezes every window behind one quiet partition; too short closes windows early on a slow one |
| `pravaha.watermark.tick` | `1s` | How often event time advances — also what makes idleness detectable at all |
| `pravaha.watermark.out-of-orderness` | `10s` | The node's default lateness, applied to a stream that declares an event time and no `out-of-orderness` of its own. **Read by nothing until 2026-09-20** (DOCX-6); its default is the 10s a stream already took, so giving it a reader moved nothing that had not been set |

See [Event time and watermarks](/help/topics/event-time-watermarks).

## Read only by the embedded engine or the Spring Boot starter

| Setting | Default | What it does |
|---|---|---|
| `pravaha.embedded.push-timeout` | `30s` | How long a push into an embedded engine waits for room before PRV-8103 |
| `pravaha.queries.<name>.sql` | — | Spring Boot starter: a continuous query registered when the application starts |
| `pravaha.queries.<name>.keys` | — | Its key columns, by name |
| `pravaha.queries.<name>.sink` | *none* | A bound sink it also writes to |
| `pravaha.queries.<name>.retention` | forever | How long the view remembers |
| `pravaha.listener.max-pending` | `10000` | Commits a `@PravahaListener` may have waiting before it is detached |

See [The embedded engine](/help/topics/embedded-engine) and
[The Spring Boot starter](/help/topics/spring-boot-starter).

## JVM system properties

| Property | What it does |
|---|---|
| `pravaha.memory` | Selects the off-heap memory implementation by name -- `agrona`, `foreign` or `bytebuffer`; unset means "choose for me". A name that is not one of those is refused at startup rather than ignored, because the only reason to set it is to be certain |
| `pravaha.ffm` | Opts into the Foreign Function & Memory implementation on JDK 22 and later. On an older JDK it falls through silently and deliberately: a launcher that starts working on an upgrade is not a mistake |

Set with `-D` on the JVM command line, not in `application.yaml`. All the selections produce
byte-identical results, so this is about knowing what is running, not about correctness -- and the
node logs the answer once at startup: `off-heap access: bytebuffer (-Dpravaha.memory,
-Dpravaha.ffm=false)`.

## Where next

- [Configuration](/help/topics/configuration) — layering, profiles, and a complete example node
- [Every metric](/help/topics/metrics-index)
- [Operations (long form)](/help/operations)
