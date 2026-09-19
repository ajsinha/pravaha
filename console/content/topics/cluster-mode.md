---
title: Cluster mode
slug: cluster-mode
category: operating
order: 90
icon: diagram-3
summary: "pravaha.cluster.mode and pravaha.cluster.mechanism: what SINGLE, REPLICATED and PARTITIONED ask for, which coordinator provides each, the ZooKeeper plugin — and, honestly, what a node does with them today."
badge: NOT BUILT
audience: Architects
keywords: [cluster, SINGLE, REPLICATED, PARTITIONED, coordinator, zookeeper, socket, split-brain, consensus, Raft, ADR-045, ADR-034, PRV-9002, multi-node]
guide: operations#clustering-choosing-a-coordinator
related: [standby, configuration, checkpoints-recovery]
---

Pravaha targets **one node, scaled to its cores**. Multi-node execution is deferred by decision
(ADR-034), and the high availability that exists today is a [standby](/help/topics/standby). This
page describes the two cluster settings a node reads, what each value means, what a node will and
will not start with, and the design (ADR-045) the multi-node mode will follow when it is built --
so nobody configures a cluster expecting something the engine does not do.

## The two settings

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

## What a node does with them, honestly

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

## The coordinators' settings

### `socket` — static peers, no external service

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

### `zookeeper` — real consensus

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

## The design it will follow: ADR-045

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
21 M rows/s ADR-042 cites for the lane machinery on the development laptop. What runs out first scales
by placing whole queries on more nodes.

What it will not give: scale for one query beyond one node, and zero-downtime moves — a computation
moving between nodes pauses for a checkpoint and a restore.

## Pitfalls

!!! danger "Pitfall: `socket` for anything that owns state"
    `socket` cannot exclude split-brain. It is for development and for `REPLICATED` where two leaders
    only means duplicated work. Never pair it with a mode that assigns ownership — which is why the
    node refuses `PARTITIONED` on it.

!!! warning "Pitfall: expecting REPLICATED to be failover"
    For "keep serving when this host dies", run a [standby](/help/topics/standby): it resumes from the
    newest checkpoint. That is the HA that exists.

## Where next

- [Standby](/help/topics/standby)
- [ADR-045: cluster mode assigns queries, not rows](/help/decisions/045-cluster-mode-assigns-queries-not-rows)
- [ADR-034: distribution deferred](/help/decisions/034-distribution-deferred)
- [Operations: rebalancing, as designed](/help/operations#rebalancing-what-happens-when-the-membership-changes)
