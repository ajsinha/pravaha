---
title: Configuring a node
slug: configuration
category: operating
order: 10
icon: sliders
summary: "How a Pravaha node is configured: the shipped application.yaml, environment variables, command-line overrides and profiles — and the whole pravaha: tree, annotated, key by key."
badge: SETTINGS
audience: Operators
keywords: [application.yaml, spring.profiles.active, environment variables, ports, 8080, 9090, 5432, node.id, dev profile]
guide: operations#starting-a-node
related: [settings-index, sizing-lanes, checkpoints-recovery, authentication, metrics-alerts]
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
... lanes: one per query (pravaha.lane.multiplex.enabled is false)
... security: authentication=none, policy=permissive, audit=none, flight transport=PLAINTEXT
... WARN pravaha.checkpoint.directory is not set, so registered queries keep no checkpoints: ...
... WARN pravaha.registry.journal is not set, so registered queries live only in memory and ...
... Flight SQL listening on 0.0.0.0:9090
```

Those lines are the node telling you what it is actually running with. **Read them after every
configuration change** — they come from the bound values, not from the file, so they are the
answer to "did my setting take?".

## The ports

| Port | Setting | Speaks | Used by |
|---|---|---|---|
| **9090** | `pravaha.flight.port` | Arrow Flight SQL (gRPC) | the CLI, both SDKs, the console, any Flight SQL or ADBC client |
| **8080** | `server.port` | HTTP: `/api/v1/*`, `/actuator/health`, `/actuator/prometheus` | operators, the console's catalog and metrics, Prometheus |
| **5432** | `pravaha.pgwire.port` | the PostgreSQL wire protocol, read-only, **off by default** | `psql`, DBeaver, Grafana, any PostgreSQL driver |

The console is a separate process on **8090** and reaches the engine on both 9090 and 8080.
Confusing the three is the commonest first-run failure: an SDK pointed at 8080 gets an HTTP
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
    port: 9090
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
      enabled: false
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

### Identity and transport

| Key | Default | What it decides |
|---|---|---|
| `pravaha.node.id` | `pravaha-node-01` | The name every ownership claim is made under. Two nodes must not share it — unless one is the other's [standby](/help/topics/standby), where sharing it is the point |
| `pravaha.flight.enabled` | `true` | Whether the Flight SQL endpoint starts. Off, the node answers HTTP only, and the readiness probe says so |
| `pravaha.flight.host`, `pravaha.flight.port` | `0.0.0.0`, `9090` | Where Flight listens |
| `pravaha.flight.tls.certificate`, `pravaha.flight.tls.key` | empty | PEM files. Both set: TLS. Neither: plaintext, and a node with authentication on logs a warning that credentials travel in the clear. One without the other: refused at startup |
| `pravaha.pgwire.enabled` | `false` | The read-only PostgreSQL gateway — [the gateway](/help/topics/pgwire) |
| `pravaha.pgwire.host`, `pravaha.pgwire.port` | `0.0.0.0`, `5432` | Where it listens |
| `pravaha.pgwire.tls.certificate`, `pravaha.pgwire.tls.key` | empty | The same pair rule as Flight; `psql sslmode=require` negotiates on the same port |

### Durability

| Key | Default | What it decides |
|---|---|---|
| `pravaha.registry.journal` | empty | The file registrations are journalled to. Empty: queries live in memory and a restart loses them — the node warns at startup |
| `pravaha.checkpoint.directory` | empty | Where each query's state is checkpointed, one sub-directory per query. Empty: a restart is a warm-up, not a restore |
| `pravaha.checkpoint.interval` | `1m` | How often |
| `pravaha.checkpoint.keep` | `3` | How many to keep per query — counted, never aged |
| `pravaha.checkpoint.timeout` | `30s` | How long one checkpoint may take before it is abandoned |
| `pravaha.state.allow-shared` | `false` | Skip the ownership check on the directories above |
| `pravaha.standby.enabled` | `false` | Wait for the primary's claim to lapse, then take over |
| `pravaha.dlq.directory` | empty | Where undecodable source records are written |
| `pravaha.dlq.max-bytes` | `268435456` | How much of one query's dead-letter file is kept; past it the oldest are evicted and the loss recorded. `.max-entries` and `.max-age` bound it the other two ways |

All of it is in [Checkpoints and recovery](/help/topics/checkpoints-recovery),
[Standby](/help/topics/standby) and [Dead letters](/help/topics/dead-letters).

### Capacity

`pravaha.lane.*` decides what one query costs in memory and how its lane waits --
[Sizing lanes](/help/topics/sizing-lanes) and [Sharing lanes](/help/topics/lane-sharing).
`pravaha.state.spill.*` decides what happens when a query's state reaches its ceiling --
[State and spill](/help/topics/state-spill).

### Event time

| Key | Default | What it decides |
|---|---|---|
| `pravaha.watermark.idle-after` | `30s` | How long a partition may say nothing before it stops holding the watermark back. Refused outside 1s to 10m |
| `pravaha.watermark.tick` | `1s` | How often event time advances — keep it finer than `idle-after` |
| `pravaha.watermark.out-of-orderness` | `10s` | The node's default lateness; the per-stream key overrides it. Read by nothing until 2026-09-20 (DOCX-6) |

!!! note "`pravaha.watermark.out-of-orderness` used to change no answer"
    It shipped in `application.yaml` reading like the engine-wide lateness default and nothing
    bound it: four nodes over the same out-of-order data gave identical views at `0s` and at
    `10m`, and different views under `pravaha.streams.<name>.out-of-orderness` (DOCX-6). It is
    bound now. Prefer the per-stream key anyway where the streams differ — lateness is a property
    of the source, and one number for three sources has to be wrong for two of them. See
    [Event time and watermarks](/help/topics/event-time-watermarks).

### Clustering

`pravaha.cluster.mode` and `pravaha.cluster.mechanism` — [Cluster mode](/help/topics/cluster-mode).
The default, `SINGLE` on `single`, is the only one a production node runs today.

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
... Flight SQL listening on 0.0.0.0:9090
```

And, from another shell, that the node is ready:

```bash
curl -s http://localhost:8080/actuator/health/readiness
```

```text
{"status":"UP"}
```

## Checking what a node is running with

- **The startup log**, as above — bound values, not file contents.
- **`GET /api/v1/status`** on 8080: node id, version, engine state, plugin health.
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

## Where next

- [Every setting, indexed](/help/topics/settings-index)
- [Sizing lanes](/help/topics/sizing-lanes) — what one query costs
- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what survives a restart
- [Authentication](/help/topics/authentication) — the settings the defaults refuse to start without
- [Metrics and alerts](/help/topics/metrics-alerts) — watching what you configured
