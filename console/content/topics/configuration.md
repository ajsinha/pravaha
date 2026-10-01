---
title: Configuring a node — and every setting
slug: configuration
category: operating
order: 10
icon: sliders
summary: "How a node is configured: the shipped application.yaml, environment variables, command-line overrides and profiles, the whole pravaha: tree annotated — and every setting it reads, with its default and where it is explained."
badge: SETTINGS
audience: Operators
keywords: [application.yaml, spring.profiles.active, environment variables, ports, 18080, 19090, 5432, node.id, dev profile, settings, configuration, properties, defaults, environment, flight, pgwire, security, lane, checkpoint, spill, standby, watermark, cluster, dlq, journal, every setting]
guide: operations#starting-a-node
related: [lanes, checkpoints-recovery, state-spill, authentication, observability]
---

A Pravaha node is one process, `pravaha-server`, and everything it does is decided by one
configuration tree rooted at `pravaha:`. The tree is read by Spring Boot, so it can come from the
shipped `application.yaml`, a file of your own, environment variables or the command line — and
the four layer in a fixed order. This page is the map: where each value comes from, what the
three ports are, and every key in the tree with its default and what it decides.

Two rules run through all of it. **Nothing is inferred past something you wrote down**: a key set
to `false` stays false even when the material that would switch it on sits beside it. And **a value
the node cannot honour is refused at startup**, with the key named, rather than clamped to
something you did not ask for. A node that starts is a node that is running what the file says.

## Where configuration comes from

| Layer | Example | Wins over |
|---|---|---|
| The shipped `application.yaml` inside the server jar | `pravaha.checkpoint.interval: 1m` | nothing — it is the floor |
| A profile file, `application-<profile>.yaml` | `--spring.profiles.active=dev` | the shipped file |
| Your own file, added to the search | `--spring.config.additional-location=/opt/pravaha/conf/node.yaml` | the files above |
| Environment variables | `PRAVAHA_CHECKPOINT_DIRECTORY=/opt/pravaha/data/checkpoints` | every file |
| Command-line arguments | `--pravaha.checkpoint.directory=/opt/pravaha/data/checkpoints` | everything |

Environment variables use Spring's relaxed binding: upper case, dots and dashes become
underscores. `pravaha.lane.inbox.cell-bytes` is `PRAVAHA_LANE_INBOX_CELLBYTES` (Spring drops the
dash in a variable name) — when in doubt, use the command-line form, which is spelled exactly like
the key.

### Running from a PRAVAHA_HOME

A node started by `bin/pravaha-server` with `PRAVAHA_HOME` set — or from an unpacked distribution,
or in the container image, where it is `/opt/pravaha` — keeps everything under that one directory,
and reads two more layers between the shipped file and the environment:

| Layer | What it sets |
|---|---|
| `pravaha-home.yaml`, inside the server jar | only places: `logs/pravaha-server.log`, `data/registry.journal`, `data/checkpoints`, `data/identity/identity.journal`, `logs/audit.jsonl` |
| `$PRAVAHA_HOME/conf/application.yaml` | your deployment's file; it wins over the layout |

The working directory is the home, so a relative path in your file — `data/dlq`,
`secrets/initial-admin-password` — means the same thing in a container and on a host. The JVM's
temporary directory is `tmp/`, heap dumps and `hs_err` files go to `logs/`, and jars in `plugins/`
are on the classpath. Without `PRAVAHA_HOME`, from a source checkout, none of this applies.
`docs/operations/RUNNING_IN_DOCKER.md` has the whole layout, the images and the compose stack.

### The `dev` profile

The shipped defaults **refuse to start**: `pravaha.security.authentication: none` together with
`pravaha.security.policy: permissive` would serve every view to every caller, and a node does not
do that unless somebody wrote it down. The one profile that ships, `dev`, writes it down:

```yaml
pravaha:
  security:
    allow-anonymous: true
```

Use it for a first run on a laptop and nowhere else:

```bash
pravaha-server --spring.profiles.active=dev
```

```text
... lane sizing: batch=512, inbox=2048x512B, arena=4194304B x8, wait=BACKOFF_PARK -- about 1024 KiB held per idle query
... lanes: auto (a lane each until 64 queries are hosted, then shared), queries per lane [] of at most 300; 0 on lanes of their own
... security: authentication=none, policy=permissive, audit=none, flight transport=PLAINTEXT
... WARN pravaha.checkpoint.directory is not set, so registered queries keep no checkpoints: ...
... WARN pravaha.registry.journal is not set, so registered queries live only in memory and ...
... Flight SQL listening on 0.0.0.0:19090
```

Those lines are the node telling you what it is actually running with. **Read them after every
configuration change** — they come from the bound values, not from the file, so they are the
answer to "did my setting take?".

## The ports

| Port | Setting | Speaks | Used by |
|---|---|---|---|
| **19090** | `pravaha.flight.port` | Arrow Flight SQL (gRPC) | the CLI, both SDKs, the console, any Flight SQL or ADBC client |
| **18080** | `server.port` | HTTP: `/api/v1/*`, `/actuator/health`, `/actuator/prometheus` | operators, the console's catalog and metrics, Prometheus |
| **5432** | `pravaha.pgwire.port` | the PostgreSQL wire protocol, read-only, **off by default** | `psql`, DBeaver, Grafana, any PostgreSQL driver |

The console is a separate process on **17070** and reaches the engine on both 19090 and 18080.
Confusing the three is the commonest first-run failure: an SDK pointed at 18080 gets an HTTP
answer to a gRPC question and reports a protocol error.

!!! warning "Pitfall: 5432 is probably taken"
    A PostgreSQL server on the same host already listens on 5432. Change `pravaha.pgwire.port`
    rather than stopping it; the gateway is a transport, and clients take a port like any other.

## The whole tree, annotated

The complete `pravaha:` block with the shipped defaults. Every key here is real; the commented
ones are the maps you fill in yourself.

```yaml
pravaha:
  node:
    id: pravaha-node-01          # who this node is: names its state directories and ownership claims

  flight:
    enabled: true                # false = this node serves HTTP only
    host: 0.0.0.0
    port: 19090
    tls:
      certificate: ""            # PEM chain; set both or neither -- half a pair is refused
      key: ""

  pgwire:                        # the PostgreSQL gateway: read-only, off by default
    enabled: false
    host: 0.0.0.0
    port: 5432
    tls:
      certificate: ""
      key: ""

  security:
    authentication: none         # none | token
    policy: permissive           # permissive | authenticated
    audit: none                  # none | memory | file
    audit-file: pravaha-audit.jsonl
    audit-rotate-bytes: 67108864
    audit-keep: 5
    allow-anonymous: false       # the acknowledgement an open server needs

  registry:
    journal: ""                  # where registrations are written; empty = lost on restart

  checkpoint:
    directory: ""                # where query state is checkpointed; empty = none
    interval: 1m
    keep: 3
    timeout: 30s

  dlq:
    directory: ""                # where undecodable records go; empty = fail loudly instead

  lane:
    batch-size: 512
    wait-strategy: BACKOFF_PARK  # BUSY_SPIN | SPIN_THEN_YIELD | BACKOFF_PARK | BLOCKING
    inbox:
      cells: 2048
      cell-bytes: 512
    arena:
      slab-bytes: 4194304
      max-slabs: 8
    multiplex:
      enabled: auto              # auto | true | false
      auto-from: 64
      lanes: 0
      max-queries-per-lane: 300

  state:
    allow-shared: false          # let two nodes share a state directory -- almost never
    spill:
      enabled: false             # tri-state: unset + a directory = on
      directory: ""
      max-overflow-slabs: 512
      compaction-threshold: 0.5
      max-bytes: 0               # the node's disk budget for spilled state; 0 = no quota

  standby:
    enabled: false

  watermark:
    out-of-orderness: 10s        # NOT READ -- set it per stream (see below)
    idle-after: 30s
    tick: 1s

  cluster:
    mode: SINGLE                 # SINGLE | REPLICATED | PARTITIONED (refused today)
    mechanism: single            # single | socket | zookeeper
```

And the four maps that describe *what* the node runs rather than *how*, each keyed by a name you
choose:

| Map | Key is | Holds | Topic |
|---|---|---|---|
| `pravaha.streams.<name>` | a stream name | `schema`, `event-time`, `out-of-orderness` | [Streams](/help/topics/streams) |
| `pravaha.sources.<name>` | the stream it feeds | `plugin`, `options` | [Sources](/help/topics/sources-overview) |
| `pravaha.lookups.<name>` | a dimension table | `plugin`, `options` | [Lookup tables](/help/topics/lookups) |
| `pravaha.sinks.<name>` | a sink binding | `plugin`, `options` | [Sinks](/help/topics/sinks-overview) |

## Key by key

Every key, its default and what it decides is in [Every setting](#every-setting), below, grouped as
`application.yaml` groups it. One thing there is worth reading first:

!!! note "`pravaha.watermark.out-of-orderness` used to change no answer"
    It shipped in `application.yaml` reading like the engine-wide lateness default and nothing
    bound it: four nodes over the same out-of-order data gave identical views at `0s` and at
    `10m`, and different views under `pravaha.streams.<name>.out-of-orderness` (DOCX-6). It is
    bound now. Prefer the per-stream key anyway where the streams differ — lateness is a property
    of the source, and one number for three sources has to be wrong for two of them. See
    [Event time and watermarks](/help/topics/event-time-watermarks).

## A production file, worked

A node with real credentials, TLS on Flight, durable registrations and state, spill on a local
disk, and one source bound. Save it as `/opt/pravaha/conf/node.yaml`:

```yaml
pravaha:
  node:
    id: risk-node-01
  flight:
    tls:
      certificate: /opt/pravaha/conf/tls/node.crt
      key: /opt/pravaha/conf/tls/node.key
  security:
    authentication: token
    policy: authenticated
    audit: file
    audit-file: /opt/pravaha/logs/audit.jsonl
  registry:
    journal: /opt/pravaha/data/registry.journal
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
    interval: 30s
    keep: 3
  state:
    spill:
      directory: /opt/pravaha/data/spill
      max-bytes: 20GB
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  sources:
    txn:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        skip.header: "true"
        follow: "true"
```

The credentials `authentication: token` checks against are configured as
[Authentication](/help/topics/authentication) describes — they are left out here because they
belong in a file only the service account can read, not in the node's main configuration. Start it:

```bash
pravaha-server --spring.config.additional-location=/opt/pravaha/conf/node.yaml
```

What the startup log should now say, and what each line confirms:

```text
... claimed the checkpoint directory /opt/pravaha/data/checkpoints for node 'risk-node-01'
... checkpointing registered queries under /opt/pravaha/data/checkpoints
... security: authentication=token, policy=authenticated, audit=file, flight transport=TLS
... sources bound: [txn <- filesystem[path, schema, event.time, skip.header, follow]]
... Flight SQL listening on 0.0.0.0:19090
```

And, from another shell, that the node is ready:

```bash
curl -s http://localhost:18080/actuator/health/readiness
```

```text
{"status":"UP"}
```

## Checking what a node is running with

- **The startup log**, as above — bound values, not file contents.
- **`GET /api/v1/status`** on 18080: node id, version, engine state, plugin health.
- **The console's Operations screen**, which reads both.

Configuration is read once, at startup. There is no reload: change the file, restart the node.
A node with a journal and a checkpoint directory comes back with its queries and their state
(see [Checkpoints and recovery](/help/topics/checkpoints-recovery)).

## Pitfalls

!!! warning "Pitfall: plugin options one level too high"
    Options go under `options:`. A source written as `pravaha.sources.txn.path: ...` has a key
    nobody reads; the node starts, binds nothing useful and ingests nothing, silently.

!!! warning "Pitfall: the schema is written twice"
    A `filesystem` source's `options.schema` and the stream's `pravaha.streams.<name>.schema` are
    read by different components and must agree column for column.

!!! warning "Pitfall: no `event-time`, no answers"
    A stream without `event-time` has no watermark, so no window over it could ever close — and a
    windowed query over it is refused with `PRV-2002` when you register it, rather than reporting
    `RUNNING` and ingesting every row for ever. An unwindowed query over the same stream is fine,
    and the console's operations screen shows it as "No watermark yet".

!!! tip "The JVM flags are not optional"
    `bin/pravaha-server` adds `--add-opens=java.base/java.nio=ALL-UNNAMED` and
    `--add-opens=java.base/java.lang=ALL-UNNAMED`, which Arrow needs. A launcher of your own must
    add them too, or Flight fails inside a stream with `RST_STREAM` and nothing to explain why.
    Extra JVM options go in `PRAVAHA_JAVA_OPTS`.

## Every setting {#every-setting}

This is the complete list of what a Pravaha server reads under `pravaha.*`, grouped as
`application.yaml` groups it. Each key links to the topic that explains it in context. Defaults are the
engine's own — a node that sets nothing behaves exactly as described here.

**How to set one.** The server is a Spring Boot application, so every key can be given in
`application.yaml` (or an `application-<profile>.yaml`), as a command-line argument
(`--pravaha.checkpoint.directory=/opt/pravaha/data/ckpt`), or as an environment variable in Spring's
relaxed form (`PRAVAHA_CHECKPOINT_DIRECTORY`). Durations take a unit (`30s`, `1m`, `PT24H`); sizes
take one too (`512MB`, `20GB`). The layering is [above](#where-configuration-comes-from).

A few keys are bound from configuration classes rather than written out in the shipped
`application.yaml`; they are real settings all the same, bound by name from the engine's properties classes.


### The node

| Setting | Default | What it does |
|---|---|---|
| `pravaha.node.id` | `pravaha-node-01` | Names the node in logs and in the ownership marker of every directory it writes. A [standby](/help/topics/standby) uses the **same** id as its primary |
| `pravaha.codegen.enabled` | `true` | Compile each filter-and-projection chain on a scan to generated code. `false` runs every query registered from start-up interpreted — same answers, slower; each query's `execution` lines say `generated:` or `interpreted:`. Read once, at start. `-Dpravaha.codegen.enabled` on the JVM wins over `application.yaml`, as every system property does |

### Client surfaces

| Setting | Default | What it does |
|---|---|---|
| `pravaha.flight.enabled` | `true` | Serve Arrow Flight SQL — the protocol the SDKs, the CLI and the console speak |
| `pravaha.flight.host` | `0.0.0.0` | Where Flight listens. Write an address in full: `127` is legal input to `InetAddress` and means `0.0.0.127`, so an abbreviated one is refused at startup (PRV-3010). An IPv6 host is reported bracketed, `[::1]:19090` |
| `pravaha.flight.port` | `19090` | Flight's port — what every client and example defaults to. `0` lets the operating system pick, and `GET /api/v1/status`'s `flight` field reports the port it picked. Outside 0-65535 is PRV-3010 naming the key, rather than gRPC's own unnamed argument check |
| `pravaha.flight.tls.certificate` | *empty* | PEM certificate chain. With the key, Flight serves TLS; without both, plaintext. One without the other is refused at startup (PRV-6104) — see [TLS](/help/topics/tls) |
| `pravaha.flight.tls.key` | *empty* | PEM private key, the other half of the pair |
| `pravaha.pgwire.enabled` | `false` | Serve the read-only [PostgreSQL gateway](/help/topics/pgwire), for `psql`, Grafana and ORMs |
| `pravaha.pgwire.host` | `0.0.0.0` | Where it listens |
| `pravaha.pgwire.port` | `5432` | Its port. Change it if a PostgreSQL server on the host already has 5432 |
| `pravaha.pgwire.tls.certificate` | *empty* | PEM certificate; `sslmode=require` then negotiates on the same port. Refused at startup if half-set (PRV-6206) |
| `pravaha.pgwire.tls.key` | *empty* | PEM private key |

The HTTP API and Prometheus are on Spring's `server.port` (`18080`).

### Security

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

### Streams, sources, lookups and sinks

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
| `pravaha.streams.<name>.allowed-lateness` | `0s` | How long after a window closes a late row may still correct it (a `−1` and a `+1`). Needs `event-time`; non-zero makes windowed queries over the stream revise, so they need a sink that takes retractions (PRV-2041). See [late data](/help/topics/event-time-watermarks#late-data) |
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

### Durability: journal, checkpoints, dead letters

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

### The debugger

| Setting | Default | What it does |
|---|---|---|
| `pravaha.debug.sessions.max` | `4` | Debug sessions this node holds at once. PRV-8014 past it. The ceiling is memory: each session is a second copy of a query's lanes, arena and state |
| `pravaha.debug.session.ttl` | `15m` | How long a session nobody has touched survives. Checked on the way in to the next call, so a node that debugs nothing runs nothing extra |
| `pravaha.debug.session.max-rows` | `20000` | How many rows one session may consume. It keeps every one, so that it can be exported as a fixture |
| `pravaha.debug.step.max-rows` | `10000` | How far one `commit` or `until:` step searches before giving up. "Until it happens" over ten million rows is a call that never returns |

A session needs `pravaha.checkpoint.directory` set, because it forks from a checkpoint. See
[The time-travel debugger](/help/topics/time-travel-debugger).

### Lanes

What one registered query costs in memory, and how its lane waits. See
[Sizing and lanes](/help/topics/lanes#sizing-lanes) and [Lane sharing](/help/topics/lanes#sharing-lanes).

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
| `pravaha.lane.multiplex.enabled` | `auto` | Let queries share a fixed set of lanes instead of one apiece: `auto` once `auto-from` queries are hosted, `true` always, `false` never. A shared lane shares its fate |
| `pravaha.lane.multiplex.auto-from` | `64` | Under `auto`, queries that own a lane before registrations start sharing |
| `pravaha.lane.multiplex.lanes` | `0` | Shared lanes; `0` means one per available processor |
| `pravaha.lane.multiplex.max-queries-per-lane` | `300` | The ceiling per shared lane; a query that fits nowhere (or reads more than one stream) gets a lane of its own |

### State and the spill tier

See [State and spill](/help/topics/state-spill).

| Setting | Default | What it does |
|---|---|---|
| `pravaha.state.allow-shared` | `false` | Let this node write state into a directory another node already owns. Only for sharing on purpose — a node reclaiming its own state after a crash does not need it (PRV-4003) |
| `pravaha.state.spill.enabled` | *unset* | Tri-state, explicit wins both ways: `false` keeps spilling off even with a directory set; unset means "on if a directory is set" |
| `pravaha.state.spill.directory` | *empty* | Where overflow slabs (memory-mapped files) are created when a query outgrows memory |
| `pravaha.state.spill.max-overflow-slabs` | `512` | Most overflow slabs one state store may hold — so one runaway join cannot take the whole budget |
| `pravaha.state.spill.compaction-threshold` | `0.5` | Fraction of a store's overflow space that must be free before its sparse slabs are compacted and their files released |
| `pravaha.state.spill.max-bytes` | `0` (no quota) | The node's disk budget for spilled state across every query (`20GB`, `512MB`). Past it: PRV-4005 |

### High availability and clustering

| Setting | Default | What it does |
|---|---|---|
| `pravaha.standby.enabled` | `false` | Run as a standby: hold no lanes, serve nothing, and take over from the newest checkpoint when the primary stops refreshing its claim. Same `pravaha.node.id` as the primary; needs `pravaha.checkpoint.directory`. See [Standby](/help/topics/standby) |
| `pravaha.cluster.mode` | `SINGLE` | `SINGLE`, `REPLICATED`, or `PARTITIONED` (refused by a node today, PRV-9002) |
| `pravaha.cluster.mechanism` | `single` | `single`, `socket` (no consensus), or `zookeeper` (plugin required) |
| `pravaha.cluster.socket.peers` | — | For `socket`: `"a=host1:9070,b=host2:9070,c=host3:9070"` |
| `pravaha.cluster.socket.heartbeat.millis` | `1000` | Socket coordinator heartbeat |
| `pravaha.cluster.socket.timeout.millis` | `5000` | Socket coordinator peer timeout |

See [Cluster mode](/help/topics/standby#cluster-mode).

### Event time

| Setting | Default | What it does |
|---|---|---|
| `pravaha.watermark.idle-after` | `30s` | How long a partition may produce nothing before it stops holding the watermark back. Too long freezes every window behind one quiet partition; too short closes windows early on a slow one |
| `pravaha.watermark.tick` | `1s` | How often event time advances — also what makes idleness detectable at all |
| `pravaha.watermark.out-of-orderness` | `10s` | The node's default lateness, applied to a stream that declares an event time and no `out-of-orderness` of its own. **Read by nothing until 2026-09-20** (DOCX-6); its default is the 10s a stream already took, so giving it a reader moved nothing that had not been set |

See [Event time and watermarks](/help/topics/event-time-watermarks).

### Read only by the embedded engine or the Spring Boot starter

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

### JVM system properties

| Property | What it does |
|---|---|
| `pravaha.memory` | Selects the off-heap memory implementation by name -- `agrona`, `foreign` or `bytebuffer`; unset means "choose for me". A name that is not one of those is refused at startup rather than ignored, because the only reason to set it is to be certain |
| `pravaha.ffm` | Opts into the Foreign Function & Memory implementation on JDK 22 and later. On an older JDK it falls through silently and deliberately: a launcher that starts working on an upgrade is not a mistake |

Set with `-D` on the JVM command line, not in `application.yaml`. All the selections produce
byte-identical results, so this is about knowing what is running, not about correctness -- and the
node logs the answer once at startup: `off-heap access: bytebuffer (-Dpravaha.memory,
-Dpravaha.ffm=false)`.

## Where next

- [Lanes](/help/topics/lanes) — what one query costs, and sharing lanes
- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what survives a restart
- [Authentication](/help/topics/authentication) — the settings the defaults refuse to start without
- [Observability](/help/topics/observability) — watching what you configured
- [Operations (long form)](/help/operations)
