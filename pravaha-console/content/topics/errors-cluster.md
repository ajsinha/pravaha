---
title: Cluster codes (PRV-9xxx)
slug: errors-cluster
category: errors
order: 100
icon: diagram-3
summary: "PRV-9001 to PRV-9007: an unknown coordinator, one that cannot give the guarantee a mode needs (PARTITIONED is refused today), a lost coordinator, and membership, handoff and rebalance refusals."
badge: PRV-9XXX
audience: Operators, architects
keywords: [cluster, coordinator, mechanism, mode, single, socket, zookeeper, replicated, partitioned, split-brain, consensus, leader, membership, handoff, rebalance, flapping]
guide: operations#clustering-choosing-a-coordinator
related: [standby, errors-overview, configuration]
listed_on: errors-overview
---

A deployment declares two separate things about clustering, and the codes in this range are the
engine holding them to each other:

- **`pravaha.cluster.mode`** — what is being asked of the cluster, a *correctness* question:
  `SINGLE` (one node), `REPLICATED` (several nodes, each holding the whole state), or `PARTITIONED`
  (partitions owned by particular nodes).
- **`pravaha.cluster.mechanism`** — which coordinator provides it, an *operational* question:
  `single`, `socket` (static peers, no external service, **cannot exclude split-brain**), or
  `zookeeper` (real consensus; needs `plugins/pravaha-cluster-zookeeper` on the classpath).

| Mechanism | Excludes split-brain | External service |
|---|---|---|
| `single` | yes — there is no second node | no |
| `socket` | **no** | no |
| `zookeeper` | yes | yes |

The rule behind the most important code here: **refusing to start beats starting into a configuration
that is correct until the first network partition.** Two nodes each believing they own a partition
means two nodes writing the same aggregate, and the damage is silent, durable, and found later by
whoever reconciles the numbers.

| Code | Name | In one line |
|---|---|---|
| PRV-9001 | CLUSTER_UNKNOWN_MECHANISM | No coordinator answers to the configured name |
| PRV-9002 | CLUSTER_INSUFFICIENT_GUARANTEE | The coordinator cannot give what the mode needs |
| PRV-9003 | CLUSTER_COORDINATOR_UNAVAILABLE | The coordinator is unreachable or lost its session |
| PRV-9004 | CLUSTER_NOT_LEADER | A leader's operation on a non-leader (not reachable today) |
| PRV-9005 | CLUSTER_BAD_MEMBERSHIP | The peers named cannot form a cluster |
| PRV-9006 | CLUSTER_HANDOFF_FAILED | A partition handoff did not complete |
| PRV-9007 | CLUSTER_REBALANCE_REFUSED | A rebalance asked for while one is running, or too soon |

## PRV-9001 — unknown mechanism

No coordinator answers to the name in `pravaha.cluster.mechanism`. The built-in names are `single` and
`socket`; `zookeeper` exists only when its plugin jar is on the classpath. A deployment can supply its
own coordinator (etcd, Consul) through `CoordinatorProvider` and `ServiceLoader` — then its name is
whatever that provider reports.

## PRV-9002 — insufficient guarantee

**The important one.** The configured coordinator cannot provide a guarantee this deployment needs,
and the node refuses to start rather than start into it. Two cases today:

1. **`PARTITIONED` on a coordinator without consensus** (`socket`) — refused for split-brain, not
   warned about. The socket coordinator elects "the lowest id among peers I can reach", which each side
   of a network partition computes for itself, so a partition produces two leaders, each correct from
   where it is standing. Survivable when leadership decides who does redundant work; not when it
   decides who owns state.
2. **`PARTITIONED` on any coordinator.** Membership and partition assignment are built and tested,
   but nothing in the node yet asks which partitions it owns before reading — so it would serve every
   partition while reporting itself partitioned (S-3). The refusal lifts when that consumer lands
   (ADR-039 item 8).

What starts today:

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

and the startup log says what it promises:

```text
cluster mode REPLICATED on socket (NO consensus — cannot exclude split-brain), self-contained, development only
```

For a hot spare without any of this, a [standby](/help/topics/standby) shares the primary's node id and
takes over when the primary's ownership claim expires — no consensus protocol at all.

## PRV-9003 — coordinator unavailable

The coordinator could not be reached, or lost its session (a ZooKeeper session expiry, say). A node
that cannot tell whether it is still a member must not act as one. Check the coordinator service and
the network between it and the node.

## PRV-9004 — not leader

An operation that belongs to the cluster's leader was asked of a node that is not the leader.
**Declared and not reachable today**: partition assignment is a pure function every node computes
from the membership it has observed, so nothing asks "am I the leader" before doing it; the operation
that will need this refusal — initiating a rebalance — is the next slice of work, and no running node
constructs it yet.

## PRV-9005 — bad membership

The configuration names peers that cannot form a cluster — for example assigning partitions across no
members at all ("an empty assignment would look like a valid one"), or a query's partition count
changed after registration. A query's partition count is fixed at registration (default 1024) and
immutable for its life: changing it would rehash every key. Check `pravaha.cluster.socket.peers`.

## PRV-9006 — handoff failed

A partition handoff did not complete. **Library behaviour today** — `Rebalancer` and `PartitionHandoff`
are tested with real threads and a real ZooKeeper ensemble, and no running node constructs them yet.
The message distinguishes the two cases that matter:

| You see | It means |
|---|---|
| `... must not be handed back` | Failed **past** the ownership flip. That partition needs checkpoint recovery — do not move it back |
| `... is not being served` | The rollback itself failed. A partition is paused with no owner serving it. Investigate now |

(A handoff that **rolled back** cleanly is not an error: the source kept its state and offsets and
still owns the partition, and the next membership change decides afresh.)

## PRV-9007 — rebalance refused

A rebalance was asked for while one was running, or too soon after one finished — within the cooldown
(one minute by default). The usual cause is **flapping**: a node appearing and disappearing. Fix that
before rebalancing; handoffs run one at a time on purpose, and a rebalance that restarts every few
seconds is a cluster that never finishes one.

## Where next

- [Cluster mode](/help/topics/standby#cluster-mode) — what each mode and mechanism means, and what is built
- [Standby](/help/topics/standby) — takeover without consensus
- [Operations: clustering (long form)](/help/operations#clustering-choosing-a-coordinator)
