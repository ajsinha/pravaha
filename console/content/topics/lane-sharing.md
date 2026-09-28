---
title: Sharing lanes between queries
slug: lane-sharing
category: operating
order: 30
icon: diagram-2
summary: "pravaha.lane.multiplex.* puts many queries on a fixed set of shared lanes, so they share an inbox and an arena. What it saves, how a registration is placed, and why it is off by default."
audience: Operators
keywords: [multiplex, shared lane, max-queries-per-lane, LaneMultiplexer, ADR-027, ADR-036, LANE-2, pravaha_lane_shared_queries, pravaha_lane_own_queries, pravaha_lane_shared_bytes]
guide: operations#sharing-lanes-between-queries
related: [sizing-lanes, state-spill, metrics-alerts]
---

By default each registered query holds a lane of its own — and so an inbox of its own, a megabyte
at the default sizing. [Sizing the inbox down](/help/topics/sizing-lanes) is one way to make a
thousand queries cheap. **Sharing lanes** is the other: registered queries run as pipelines on a
fixed set of shared lanes, and a lane's inbox and arena serve every query on it.

The default is **`auto`**. Sharing saves memory and costs isolation: a shared lane shares its fate,
so a query whose pipeline throws -- including one refused with PRV-4001 for its state — kills the
lane, and every query on it with it, where a lane per query loses one. On a node with a few dozen
queries the memory is small and the isolation is worth it; on a node with thousands the memory is
the node. So under `auto` the first **64** queries (`auto-from`) each own a lane, and every
registration after them is placed on a shared lane. Queries already running are never moved: a node
that shrinks back below the threshold keeps the placements it made. `true` shares from the first
query, `false` never shares.

## The settings

```yaml
pravaha:
  lane:
    multiplex:
      enabled: auto              # auto | true | false
      auto-from: 64              # auto: queries that own a lane before sharing starts
      lanes: 0                   # 0 = one per available processor
      max-queries-per-lane: 300
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.lane.multiplex.enabled` | `auto` | `auto`: share once `auto-from` queries are hosted; `true`: share from the first; `false`: never. Anything else is refused at startup |
| `pravaha.lane.multiplex.auto-from` | `64` | Under `auto`, how many queries own a lane before registrations start sharing. Negative is refused at startup |
| `pravaha.lane.multiplex.lanes` | `0` | How many shared lanes. `0` means one per available processor. Negative is refused at startup |
| `pravaha.lane.multiplex.max-queries-per-lane` | `300` | The ceiling on queries one shared lane carries. Below 1 is refused at startup |

The shared lanes use the same `pravaha.lane.inbox.*`, `pravaha.lane.arena.*`, `batch-size` and
`wait-strategy` as a lane of one's own — one inbox per shared lane rather than per query.

## How a registration is placed

One rule: **the least loaded shared lane below `max-queries-per-lane` wins** — by query count,
lowest lane number on a tie. What a query reads does not matter: two queries over `txn` share a
lane, and so does a join.

**A registration that fits on no shared lane is not refused — it gets a lane of its own**, exactly as
with sharing off. Turning a memory setting on never makes a node accept fewer queries.

### What the queries on a lane share

Every row on a shared lane carries a *route*. Each query has its own, and what it is fed alone — a
reader of its own, rows an embedder pushes, a catch-up read — reaches that query and no other on the
lane. A reader shared by several queries writes each row into a shared lane **once**, and every
query on the lane that reads it is handed that one copy. Readers are shared for sources that declare
at-least-once and no order (Aerospike and Cassandra); a file, Kafka, JDBC, CDC or Delta source keeps a
reader per query, so its queries share the lane and its inbox but each writes its own copy into it.

Measured, 1,000 queries over one source on 8 shared lanes: **8 lanes and 8 copies of each row**,
where lanes of their own are 1,000 lanes and 1,000 copies — 8 MiB of inboxes at the defaults
against about 1 GB.

Before LANE-2 a shared lane carried one query per stream and never a join: rows were dispatched by
stream while each query was fed separately, so two queries over `txn` on one lane each counted the
other's rows (8 where 4 was right).

### Worked: where five queries land

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

Where each one lands:

```text
big_card_txn    shared lane 0   least loaded (all empty; lowest number)
eur_txn         shared lane 1   least loaded
region_orders   shared lane 2   least loaded
minute_fills    shared lane 3   least loaded
shipped_orders  shared lane 0   all four tied at one; lowest number. A join shares like anything else
```

Four inboxes for five queries rather than five — and the saving grows with the number of queries,
whatever they read. A thousand queries over one Aerospike set fill the shared lanes up to the
ceiling, and the set's one reader writes each record into each lane once.

## Watching it

| Signal | Means |
|---|---|
| `pravaha_lane_shared_queries{lane="0"}` | Queries on each shared lane, against `max-queries-per-lane`. Absent with sharing off |
| `pravaha_lane_own_queries` | Queries holding a lane — and an inbox — of their own. All of them with sharing off |
| `pravaha_lane_shared_bytes` | Off-heap the shared lanes hold between them — counted once, however many queries they carry |
| startup log / `GET /api/v1/status` | `lanes: shared, queries per lane [..] of at most 300; N on lanes of their own` |

**`pravaha_lane_own_queries` rising on a node with sharing on** means the shared lanes are full:
raise `lanes` or `max-queries-per-lane`, or accept the inbox each own-lane query costs.

```bash
curl -s http://localhost:18080/actuator/prometheus | grep '^pravaha_lane_'
```

```text
pravaha_lane_own_queries 0.0
pravaha_lane_shared_bytes 4194304.0
pravaha_lane_shared_queries{lane="0"} 2.0
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
- **A slow neighbour.** Nothing is dropped, for anybody. A query slow for its share of a lane slows
  the lane's drain for every query on it; when the lane's inbox fills, everything feeding it is held,
  and a shared reader stalls for every query it feeds, because it writes a row to all of them or
  none. A lane that frees no cell for 30 seconds fails the feed writing into it with `PRV-3002`
  rather than hanging it.
- **Keyed aggregates stay single-lane** whatever this says (ADR-034).

## When to turn it on

| Situation | Sharing? |
|---|---|
| Hundreds or thousands of low-rate queries, memory the constraint | Yes |
| Many queries over **one Aerospike set or Cassandra table** | Yes: one reader, one copy per lane |
| Many queries over one Kafka topic or file | Yes for the inboxes; each still reads the source once |
| A few high-rate queries whose isolation matters | No |

## Pitfalls

!!! warning "Pitfall: expecting sharing to cut reads of an exactly-once source"
    Sharing a lane shares its inbox, arena and thread. It shares the *read* only where the source
    lets one reader feed several queries — at-least-once, unordered, replayable. A thousand queries
    over one Kafka topic on eight shared lanes hold eight inboxes and still read the topic a thousand
    times, because handing a late joiner over between two readers would duplicate the overlap.

!!! note "The status of ADR-027 and ADR-036"
    Threads were decoupled from lanes in Wave 9 (a fixed runner pool, one thread per core). The
    memory half — `pravaha.lane.multiplex.*` — is built and reachable from a node's configuration,
    off by default, and since LANE-2 takes any query and one copy of a shared source per lane.
