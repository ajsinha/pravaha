---
title: Running a standby
slug: standby
category: operating
order: 60
icon: shield-shaded
summary: "pravaha.standby.enabled: a second process with the same node id that holds nothing, watches the primary's ownership claim, and takes over from the newest checkpoint when it lapses. Recovery time, not continuity."
audience: Operators
keywords: [standby, failover, high availability, HA, takeover, promotion, lease, .pravaha-owner, node.id, shared storage]
guide: operations#who-owns-the-state-and-the-standby
related: [checkpoints-recovery, cluster-mode, configuration, metrics-alerts]
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
promoted from standby: pravaha-node-01 at 10.0.0.4:9090 (pid 8123) last refreshed its claim 41s ago. Whatever the previous owner processed after its last checkpoint is not in the state this node resumes from; it is replayed from the source offsets that checkpoint carries, and anything the source can no longer supply is lost.
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
pravaha-server --spring.config.additional-location=/etc/pravaha/node.yaml
```

The standby, with the same file plus one setting:

```bash
pravaha-server --spring.config.additional-location=/etc/pravaha/node.yaml --pravaha.standby.enabled=true
```

Its readiness probe answers not-ready while it waits:

```bash
curl -s -o /dev/null -w '%{http_code}\n' http://standby-host:8080/actuator/health/readiness
```

```text
503
```

Kill the primary (`kill -9`). About half a minute later the standby logs the promotion line above,
then the usual startup lines — journal replayed, checkpoints restored, `Flight SQL listening on
0.0.0.0:9090` — and the readiness probe turns to 200. Clients that retry against the standby's
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
    `PARTITIONED` — are a different thing; see [Cluster mode](/help/topics/cluster-mode).

## Where next

- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — what the standby resumes from
- [Cluster mode](/help/topics/cluster-mode)
- [ADR-035: Wave 8 is survival, not distribution](/help/decisions/035-wave-8-is-survival-not-distribution)
