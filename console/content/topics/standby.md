---
title: Standby and cluster mode
slug: standby
category: operating
order: 60
icon: shield-shaded
summary: "pravaha.standby.enabled: a second process with the same node id that takes over from the newest checkpoint when the primary's claim lapses — and pravaha.cluster.*: what each mode asks for, and what a node does with it today."
audience: Operators
keywords: [standby, failover, high availability, HA, takeover, promotion, lease, .pravaha-owner, node.id, shared storage, cluster, SINGLE, REPLICATED, PARTITIONED, coordinator, zookeeper, split-brain, consensus, Raft, ADR-045, ADR-034, PRV-9002, multi-node, high availability]
guide: operations#who-owns-the-state-and-the-standby
related: [checkpoints-recovery, configuration, upgrades, observability, errors-cluster]
---

A standby is the simplest high-availability a single-node engine can have: a second `pravaha-server`
that starts, **holds no lanes and serves nothing**, and watches the primary's claim on the shared
checkpoint directory. When the primary stops refreshing that claim, the standby promotes itself,
restores every query from the newest checkpoint, and starts serving.

It is built from the mechanism that already stops two nodes corrupting one state directory, asked
from the other side. No consensus, no membership protocol, no Raft (ADR-034, ADR-035): **two
processes and a directory.**

## Configuration

```yaml
pravaha:
  node:
    id: pravaha-node-01     # the SAME id as the primary, on purpose
  registry:
    journal: /shared/pravaha/registry.journal
  checkpoint:
    directory: /shared/pravaha/checkpoints
  standby:
    enabled: true
```

| Key | Default | On the standby |
|---|---|---|
| `pravaha.standby.enabled` | `false` | `true` |
| `pravaha.node.id` | `pravaha-node-01` | **The primary's id.** A crash restart and a takeover are then one case — "our node id, claim expired" — decided by one mechanism rather than two that could disagree |
| `pravaha.checkpoint.directory` | empty | **Required**, and the same directory the primary writes: it is what is watched and what is resumed from |
| `pravaha.registry.journal` | empty | The primary's journal, so the standby knows which queries to bring back |

`pravaha.standby.enabled=true` without `pravaha.checkpoint.directory` is refused at startup:

```text
pravaha.standby.enabled=true needs pravaha.checkpoint.directory set: a standby waits on the ownership marker in the directory it would take over, and with no such directory there is nothing to wait on and nothing to resume from.
```

Both processes must see the **same** directory: a shared volume (NFS, a cloud block volume that can
be re-attached, a clustered filesystem). Two local disks give the standby nothing to resume from.

## How a takeover happens

1. The primary claims the checkpoint root at startup with a `.pravaha-owner` marker — node id, host,
   Flight port, pid — and refreshes it every lease period (30 seconds).
2. The standby starts, sees a **live** claim under its own node id, and waits. It polls the marker
   every two seconds. It reports itself not running, so an orchestrator's readiness probe keeps it
   out of rotation.
3. The primary dies. Its claim is not refreshed.
4. When the claim has gone unrefreshed for the lease, the standby **promotes**: it takes the claim,
   replays the journal, restores each query from its newest checkpoint, rewinds each source to that
   checkpoint's offsets, and starts Flight.

The promotion is logged at WARN, and it says what was lost:

```text
promoted from standby: pravaha-node-01 at 10.0.0.4:19090 (pid 8123) last refreshed its claim 41s ago. Whatever the previous owner processed after its last checkpoint is not in the state this node resumes from; it is replayed from the source offsets that checkpoint carries, and anything the source can no longer supply is lost.
```

## What it buys: recovery time, not continuity

| | With a standby | Restarting the primary |
|---|---|---|
| Time to serving again | lease expiry (up to ~30 s) + restore | however long the host takes to come back |
| State | the newest checkpoint | the newest checkpoint |
| Rows after that checkpoint | replayed from the source | replayed from the source |
| Clients | reconnect to the standby's address | reconnect to the same address |

Everything the primary processed after its last checkpoint is **replayed**, not preserved. A source
that can rewind loses nothing; a source that no longer holds those rows cannot supply them. The
standby never serves a view while the primary does: at no moment are two nodes processing the same
queries.

## Worked: a primary and a standby on one shared volume

Both hosts mount `/shared/pravaha`. The primary:

```bash
pravaha-server --spring.config.additional-location=/opt/pravaha/conf/node.yaml
```

The standby, with the same file plus one setting:

```bash
pravaha-server --spring.config.additional-location=/opt/pravaha/conf/node.yaml --pravaha.standby.enabled=true
```

Its readiness probe answers not-ready while it waits:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://standby-host:18080/actuator/health/readiness
```

```text
503
```

Kill the primary (`kill -9`). About half a minute later the standby logs the promotion line above,
then the usual startup lines — journal replayed, checkpoints restored, `Flight SQL listening on
0.0.0.0:19090` — and the readiness probe turns to 200. Clients that retry against the standby's
address, or behind a load balancer that follows readiness, carry on.

## Pitfalls

!!! warning "Pitfall: a different node id"
    A standby watching a directory owned by a *different* node id never promotes — and says so,
    rather than waiting silently. Copy `pravaha.node.id` from the primary.

!!! warning "Pitfall: expecting zero loss"
    The standby resumes from a checkpoint. With `pravaha.checkpoint.interval: 1m`, up to a minute of
    work is redone from the source after a takeover, and a sink that is not transactional receives
    those rows again. Shorten the interval if that is too much, and see
    [Delivery guarantees](/help/topics/delivery-guarantees).

!!! warning "Pitfall: the old primary coming back"
    A primary that returns after the standby promoted finds a live claim under its own id held by
    another process, and is refused with PRV-4003. That is the lock doing its job: stop it, or start
    it with `pravaha.standby.enabled=true` so it becomes the new standby.

!!! note "Not clustering"
    A standby is one active node and one waiting. Several active nodes — `REPLICATED`, or
    `PARTITIONED` — are a different thing; see [Cluster mode](/help/topics/standby#cluster-mode).

## Cluster mode {#cluster-mode}

The standby above is the high availability that exists. Cluster mode is the part that does not, yet.

Pravaha targets **one node, scaled to its cores**. Multi-node execution is deferred by decision
(ADR-034), and the high availability that exists today is a [standby](/help/topics/standby). This
page describes the two cluster settings a node reads, what each value means, what a node will and
will not start with, and the design (ADR-045) the multi-node mode will follow when it is built --
so nobody configures a cluster expecting something the engine does not do.

### The two settings

A deployment declares two separate things, because the mechanisms are **not interchangeable**:

```yaml
pravaha:
  cluster:
    mode: SINGLE            # what you are asking of the cluster -- a correctness question
    mechanism: single       # which coordinator provides it -- an operational question
```

| `pravaha.cluster.mode` | Means | Needs consensus | A node today |
|---|---|---|---|
| `SINGLE` (default) | One node | no | **runs** |
| `REPLICATED` | Several nodes, each holding the whole state | no | starts; see below |
| `PARTITIONED` | Work owned by particular nodes | **yes** | **refused at startup**, PRV-9002 |

| `pravaha.cluster.mechanism` | Excludes split-brain | External service | For |
|---|---|---|---|
| `single` (default) | yes — there is no second node | no | one node; what embedded always is |
| `socket` | **no** | no | development, and `REPLICATED` where a split brain costs duplicated work |
| `zookeeper` | yes | ZooKeeper | `PARTITIONED`, once a node can serve it. Needs `plugins/pravaha-cluster-zookeeper` on the classpath |

An unknown mechanism is refused with PRV-9001.

### What a node does with them, honestly

- **`SINGLE` on `single`** is the only configuration a production node should run.
- **`REPLICATED` starts, and replicates nothing.** The coordinator forms membership and elects a
  leader, but there is no engine wiring behind it: each node runs whatever is registered on it, from
  its own sources, and no state or registration moves between nodes (ADR-034).
- **`PARTITIONED` is refused on every coordinator** (PRV-9002). Membership and partition assignment
  are built and tested, but nothing in a node asks which partitions it owns before reading, so it
  would serve everything while reporting itself partitioned. The refusal lifts with ADR-045's
  implementation. Separately — and checked first — `PARTITIONED` on a coordinator **without
  consensus** is refused for split-brain, not warned about: two nodes each believing they own the
  same work write the same aggregate, and the damage is silent, durable and found later.
- **Rebalancing is not wired into a node.** `Rebalancer` and `PartitionHandoff` exist as a library,
  tested with real threads and a real ZooKeeper ensemble; no running node constructs either.
- **Raft is not implemented.** ADR-009 chooses embedded Raft as the eventual default; until it exists,
  consensus means ZooKeeper.

The startup log says what was chosen and what it promises, for example:

```text
cluster mode REPLICATED on socket (NO consensus — cannot exclude split-brain), self-contained, development only
```

### The coordinators' settings

#### `socket` — static peers, no external service

```yaml
pravaha:
  cluster:
    mode: REPLICATED
    mechanism: socket
    socket:
      peers: "a=host1:9070,b=host2:9070,c=host3:9070"
      heartbeat.millis: 1000
      timeout.millis: 5000
```

| Key | What it decides |
|---|---|
| `pravaha.cluster.socket.peers` | Every member as `id=host:port`, comma-separated |
| `pravaha.cluster.socket.heartbeat.millis` | How often members ping each other |
| `pravaha.cluster.socket.timeout.millis` | How long before a silent peer is considered gone |

It elects "the lowest id among peers I can reach", which each side of a network partition computes
for itself — so a partition produces **two leaders**, each correct from where it stands. Survivable
when leadership decides only who does redundant work; not survivable when it decides who owns state.

#### `zookeeper` — real consensus

```yaml
pravaha:
  cluster:
    mode: REPLICATED
    mechanism: zookeeper
    zookeeper:
      connect: "zk1:2181,zk2:2181,zk3:2181"
      root: /pravaha
      session.timeout.millis: 15000
      connect.timeout.millis: 10000
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.cluster.zookeeper.connect` | — (required) | The ensemble, `host:port,...`. Missing is refused with a message showing the form |
| `pravaha.cluster.zookeeper.root` | `/pravaha` | The znode under which membership and leases live |
| `pravaha.cluster.zookeeper.session.timeout.millis` | `15000` | ZooKeeper session timeout |
| `pravaha.cluster.zookeeper.connect.timeout.millis` | `10000` | How long to wait for the first connection |

The plugin is found by `ServiceLoader` under the name `zookeeper`; a deployment that runs etcd or
Consul can supply its own coordinator the same way (`CoordinatorProvider`) without the engine knowing.

### The design it will follow: ADR-045

**Status: accepted, design only, not built.** In `PARTITIONED` mode the unit a node owns will be a
**computation** — one registered query's fingerprint, with every name that shares it — not a slice of
rows. The owner runs the whole computation: it opens every source partition the query reads, holds all
its state, serves its view and writes its sinks. Nothing is shuffled between nodes.

Why not partition rows: source partitions (an Aerospike partition, a Cassandra token range, a file)
are not aligned with a query's `GROUP BY` or join key, so each node would hold a partial total for every
key it saw and serve a wrong answer with nothing to say so. Making that right needs a distributed
shuffle — the most expensive thing a streaming engine builds — and it buys scale for a *single*
query. This engine's constraint is the number of queries a node holds (ADR-036), and ADR-042 puts
the throughput requirement at about 1,000 rows per second — four orders of magnitude below the
68 M rows/s the lane machinery measured on the development laptop on JDK 25 (ADR-042; 21 M on JDK 21). What runs out first scales
by placing whole queries on more nodes.

What it will not give: scale for one query beyond one node, and zero-downtime moves — a computation
moving between nodes pauses for a checkpoint and a restore.

### Pitfalls

!!! danger "Pitfall: `socket` for anything that owns state"
    `socket` cannot exclude split-brain. It is for development and for `REPLICATED` where two leaders
    only means duplicated work. Never pair it with a mode that assigns ownership — which is why the
    node refuses `PARTITIONED` on it.

!!! warning "Pitfall: expecting REPLICATED to be failover"
    For "keep serving when this host dies", run a [standby](#configuration): it resumes from the
    newest checkpoint. That is the HA that exists.

## Where next

- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what the standby resumes from
- [ADR-035: Wave 8 is survival, not distribution](/help/decisions/035-wave-8-is-survival-not-distribution)
- [ADR-045: cluster mode assigns queries, not rows](/help/decisions/045-cluster-mode-assigns-queries-not-rows)
- [ADR-034: distribution deferred](/help/decisions/034-distribution-deferred)
- [Operations: rebalancing, as designed](/help/operations#rebalancing-what-happens-when-the-membership-changes)
