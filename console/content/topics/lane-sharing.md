---
title: Sharing lanes between queries
slug: lane-sharing
category: operating
order: 30
icon: diagram-2
summary: "pravaha.lane.multiplex.* puts many queries on a fixed set of shared lanes, so they share an inbox and an arena. What it saves, how a registration is placed, and why it is off by default."
audience: Operators
keywords: [multiplex, shared lane, max-queries-per-lane, LaneMultiplexer, ADR-027, ADR-036, pravaha_lane_shared_queries, pravaha_lane_own_queries]
guide: operations#sharing-lanes-between-queries
related: [sizing-lanes, state-spill, metrics-alerts]
---

By default each registered query holds a lane of its own — and so an inbox of its own, a megabyte
at the default sizing. [Sizing the inbox down](/help/topics/sizing-lanes) is one way to make a
thousand queries cheap. **Sharing lanes** is the other: registered queries run as pipelines on a
fixed set of shared lanes, and a lane's inbox and arena serve every query on it.

It is **off by default, deliberately**. A shared lane shares its fate: a query whose pipeline throws
-- including one refused with PRV-4001 for its state — kills the lane, and every query on it with
it. A lane per query loses one. A node already reaches its query target without sharing, so this is
a memory trade a deployment chooses, not a default it inherits.

## The settings

```yaml
pravaha:
  lane:
    multiplex:
      enabled: true
      lanes: 0                   # 0 = one per available processor
      max-queries-per-lane: 300
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.lane.multiplex.enabled` | `false` | Whether registrations are placed on shared lanes at all |
| `pravaha.lane.multiplex.lanes` | `0` | How many shared lanes. `0` means one per available processor. Negative is refused at startup |
| `pravaha.lane.multiplex.max-queries-per-lane` | `300` | The ceiling on queries one shared lane carries. Below 1 is refused at startup |

The shared lanes use the same `pravaha.lane.inbox.*`, `pravaha.lane.arena.*`, `batch-size` and
`wait-strategy` as a lane of one's own — one inbox per shared lane rather than per query.

## How a registration is placed

Three rules, in order:

1. **A query that reads more than one stream — a join — gets a lane of its own.** A shared lane
   has one inbox.
2. **A shared lane carries at most one query per stream.** Rows are dispatched on a lane by the
   stream they came from, and each registered query is fed separately, so two queries over `txn`
   on one lane would each be handed the other's copy of every row and each count would read double
   (measured: 8 where 4 was right). A second query over a stream goes to another shared lane.
3. **Of the lanes left, the least loaded below `max-queries-per-lane` wins** — by query count,
   lowest lane number on a tie.

**A registration that fits on no shared lane is not refused — it gets a lane of its own**, exactly as
with sharing off. Turning a memory setting on never makes a node accept fewer queries.

### Worked: what rule 2 does to a real workload

A node with sharing on, `lanes: 4`, registers these five queries in order:

```sql
CREATE CONTINUOUS QUERY big_card_txn KEYED BY (txn_id)
AS SELECT txn_id, user_id, amount FROM txn WHERE amount > 5000;
```

```sql
CREATE CONTINUOUS QUERY eur_txn KEYED BY (txn_id)
AS SELECT txn_id, user_id, amount FROM txn WHERE currency = 'EUR';
```

```sql
CREATE CONTINUOUS QUERY region_orders KEYED BY (region, window_end)
AS SELECT region, window_start, window_end, COUNT(*) AS orders
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end;
```

```sql
CREATE CONTINUOUS QUERY minute_fills KEYED BY (symbol, window_end)
AS SELECT symbol, window_start, window_end, COUNT(*) AS fills
FROM TABLE(TUMBLE(TABLE trades, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY symbol, window_start, window_end;
```

```sql
CREATE CONTINUOUS QUERY shipped_orders KEYED BY (order_id)
AS SELECT o.order_id, s.carrier, o.amount
FROM orders o
JOIN shipments s ON s.order_id = o.order_id
  AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' DAY;
```

Where each one lands, and why:

```text
big_card_txn    shared lane 0   least loaded (all empty; lowest number)
eur_txn         shared lane 1   lane 0 already carries a query over txn (rule 2)
region_orders   shared lane 2   least loaded of the lanes with no orders query
minute_fills    shared lane 3   least loaded
shipped_orders  own lane        reads two streams (rule 1)
```

Four inboxes for five queries rather than five — and the saving grows with the number of
*distinct streams*, not the number of queries. A thousand queries over one Aerospike set still need
a thousand lanes' worth of placement, because at most one of them fits on each shared lane. Lifting
rule 2 means one ingest per stream per lane fanned out to every pipeline on it: the multiplexer was
built for that, and the feed layer does not yet do it.

## Watching it

| Signal | Means |
|---|---|
| `pravaha_lane_shared_queries{lane="0"}` | Queries on each shared lane, against `max-queries-per-lane`. Absent with sharing off |
| `pravaha_lane_own_queries` | Queries holding a lane — and an inbox — of their own. All of them with sharing off |
| startup log / `GET /api/v1/status` | `lanes: shared, queries per lane [..] of at most 300; N on lanes of their own` |

**`pravaha_lane_own_queries` rising on a node with sharing on** means the shared lanes are full, or
the queries all read the same few streams: raise `lanes` or `max-queries-per-lane`, or accept the
inbox each own-lane query costs.

```bash
curl -s http://localhost:8080/actuator/prometheus | grep '^pravaha_lane_'
```

```text
pravaha_lane_own_queries 1.0
pravaha_lane_shared_queries{lane="0"} 1.0
pravaha_lane_shared_queries{lane="1"} 1.0
pravaha_lane_shared_queries{lane="2"} 1.0
pravaha_lane_shared_queries{lane="3"} 1.0
```

(With the five registrations above; label formatting is Micrometer's.)

## What it costs

- **Fate.** One failing pipeline takes the whole shared lane down. A query at `state_fraction` 0.95
  on a shared lane is a risk to up to `max-queries-per-lane` others — configure
  [spill](/help/topics/state-spill) before sharing, so reaching a ceiling slows a query instead of
  killing a lane.
- **Checkpoint pauses.** A checkpoint commits each query's view on its lane; on a shared lane one
  query's slow sink or large state lengthens the pause for its neighbours. `pravaha.checkpoint.timeout`
  bounds it.
- **Keyed aggregates stay single-lane** whatever this says (ADR-034).

## When to turn it on

| Situation | Sharing? |
|---|---|
| Hundreds or thousands of low-rate queries over **many different streams**, memory the constraint | Yes |
| Many queries over **one or a few streams** | Little gain (rule 2); size the inbox down instead |
| Mostly joins | No gain (rule 1) |
| A few high-rate queries whose isolation matters | No |

## Pitfalls

!!! warning "Pitfall: expecting sharing to fix a thousand queries over one set"
    Rule 2 places at most one query per stream on a shared lane, so queries over the same stream
    spread one per lane and then fall back to lanes of their own. For that workload the inbox sizing
    in [Sizing lanes](/help/topics/sizing-lanes) is the lever that works.

!!! note "The status of ADR-027 and ADR-036"
    Threads were decoupled from lanes in Wave 9 (a fixed runner pool, one thread per core). The
    memory half — `pravaha.lane.multiplex.*` — is built and reachable from a node's configuration,
    and off by default. Sharing one ingest between queries over the same stream is what remains.
    The status line of ADR-036 still describes the multiplexer as unreachable from a node; the
    shipped `application.yaml` and this page describe the current state.

## Where next

- [Sizing lanes](/help/topics/sizing-lanes)
- [State and spill](/help/topics/state-spill) — make a ceiling survivable before a lane is shared
- [ADR-027: the lane multiplexes queries](/help/decisions/027-lane-multiplexes-queries)
- [ADR-036: one node, thousands of queries](/help/decisions/036-one-node-thousands-of-queries)
