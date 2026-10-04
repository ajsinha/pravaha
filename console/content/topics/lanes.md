---
title: Lanes — sizing them, sharing them, and giving a query its own
slug: lanes
category: operating
order: 20
icon: cpu
summary: "What a registered query costs on its lane and the pravaha.lane.* settings that size it; how lanes are shared (auto from 64 queries), how one query keeps a lane of its own, and how an administrator rebalances by hand."
audience: Operators
keywords: [lane, inbox, arena, batch-size, wait-strategy, cell-bytes, slab-bytes, max-slabs, PRV-3001, memory, off-heap, BACKOFF_PARK, BUSY_SPIN, multiplex, shared lane, auto-from, max-queries-per-lane, dedicated, "lane = 'dedicated'", rebalance, "pravaha lanes", LaneMultiplexer, ADR-027, ADR-036, ADR-054, LANE-2, pravaha_lane_shared_queries, pravaha_lane_own_queries, pravaha_lane_shared_bytes, PRV-8017]
guide: operations#sizing-a-node-for-many-queries
related: [state-spill, observability, reading-a-plan, sharing, backfill-cutover]
---

A registered query runs on a **lane**, and the lane is where its fixed memory cost lives. This page
has the two halves of that: [sizing a lane](#sizing-lanes) — what one costs and the settings that
decide it — and [sharing lanes](#sharing-lanes) — many queries on a fixed set of lanes, which a node
does by itself once it hosts 64, with the ways to keep one query on a lane of its own and to hand
lanes back by hand.

## Sizing lanes {#sizing-lanes}

Every registered continuous query runs on a **lane**: a single-writer pipeline with an
**inbox** its source rows are copied into, an **arena** its operators allocate output rows from,
and a **wait strategy** for when the inbox is empty. The lane is where a query's fixed memory
cost lives, so it is what decides how many queries a node can hold — and the defaults were chosen
for the opposite workload from a node holding hundreds of small queries.

A lane is not a thread. Since Wave 9 every lane on the node is driven by one shared pool of platform
threads, **one per core**; a query costs no platform thread of its own. What a query does cost is
off-heap memory, and almost all of it is the inbox.

### The settings

```yaml
pravaha:
  lane:
    batch-size: 512            # rows drained from the inbox per step
    wait-strategy: BACKOFF_PARK
    inbox:
      cells: 2048              # ring cells; one cell holds one row
      cell-bytes: 512          # the widest row that can be ingested at all
    arena:
      slab-bytes: 4194304      # 4 MiB
      max-slabs: 8
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.lane.batch-size` | `512` | Rows drained from the inbox per step. Larger amortises per-batch work; smaller shortens the pause a checkpoint barrier waits for |
| `pravaha.lane.wait-strategy` | `BACKOFF_PARK` | What a lane does when its inbox is empty — the CPU-for-latency dial (below) |
| `pravaha.lane.inbox.cells` | `2048` | How many rows the inbox buffers: its burst capacity |
| `pravaha.lane.inbox.cell-bytes` | `512` | The size of every cell, so **the widest row that can be ingested**. A row that does not fit is refused, not buffered |
| `pravaha.lane.arena.slab-bytes` | `4194304` | The off-heap slab operators allocate output rows from, so the largest batch of output a step can produce |
| `pravaha.lane.arena.max-slabs` | `8` | The arena's ceiling: `slab-bytes x max-slabs` |

The node logs what it bound at startup — read this line, not the file:

```text
lane sizing: batch=512, inbox=2048x512B, arena=4194304B x8, wait=BACKOFF_PARK -- about 1024 KiB held per idle query
```

### What a query actually costs

Measured by `NodeScaleTest`, off-heap per query, with the defaults:

| | Formula | Measured |
|---|---|---|
| Inbox | `inbox.cells x inbox.cell-bytes` | 2048 x 512 = **1,024 KiB** |
| Pipeline arena | sized from the plan | **284 KiB** once rows move; 0 before |
| Lane arena | up to `arena.slab-bytes x arena.max-slabs`, allocated on first use | **0** for a projection *and* for a windowed aggregate |

So an **idle** query holds **1,024 KiB** and an **active** one **1,328 KiB**, for both shapes
measured. The lane arena is not where the money is: its first slab is allocated lazily, and for a
projection or a windowed aggregate it is never allocated at all, because operator output goes to
the view rather than through the lane's scratch. A join or a wide fan-out does write through it,
which is why it stays settable.

Measured on 24 cores with a thousand registered queries (`EXECUTION_MODEL.md`, section 6):

| | Default sizing | Inbox sized down to 256 x 256 |
|---|---|---|
| Platform threads added | +24 | +24 |
| Off-heap total | 1,000 MiB | **61 MiB** |
| Off-heap per lane | 1,024 KiB | 62 KiB |

**Threads stopped being the constraint and the inbox became it.** "How many queries fit" is now a
division, not a constant.

### The two sizing rules

Both fail loudly with `PRV-3001`, and the message names the setting to change.

1. **A cell must fit the widest row the query's source will deliver.** A claim is one cell whatever
   the row's size — a 40-byte row still occupies 512 — which buys a constant-time claim with no
   allocator on the hot path. The price is that `cell-bytes` is a hard ceiling: a wider row is
   refused at ingest. Size down only against a known widest row.
2. **`batch-size x widest output row` must fit one arena slab**, for a plan that writes through the
   arena. Exceeding it is `PRV-3001` naming `pravaha.lane.arena.slab-bytes`; the remedy is a larger
   slab or a smaller batch.

!!! warning "Pitfall: sizing the inbox down against the average row"
    A feed whose rows are usually 180 bytes and occasionally carry a 2 KB free-text field will run
    for days on `cell-bytes: 256` and then refuse the first long row. Size against the widest row
    the source can produce — the schema's variable-width columns at their longest — not the rows
    you have seen so far.

### A worked sizing

A node will hold **800** continuous queries, each over an Aerospike set scanned once a second.
Every stream's widest row is under 200 bytes; the queries are filters and windowed aggregates.

**With the defaults:** 800 x 1,024 KiB = **800 MiB** of inbox, almost none of which is ever used --
a source scanned once a second never fills 2048 cells.

**Sized down:**

```yaml
pravaha:
  lane:
    inbox:
      cells: 256
      cell-bytes: 256
```

- per query: 256 x 256 = 65,536 bytes = **64 KiB**
- 800 queries: 800 x 64 KiB = **50 MiB**
- headroom per cell: 256 - 200 = 56 bytes over the widest row, so rule 1 holds
- a burst of up to 256 rows per query is buffered before the source is paused

The arena is left alone: these shapes do not allocate from it. Were one of them a join producing
6 KiB output rows, rule 2 would be `512 x 6,144 = 3,145,728` bytes — under the 4 MiB slab, so it
still fits; at 10 KiB rows (`5,242,880` bytes) it would not, and either `batch-size: 256` or
`arena.slab-bytes: 8388608` would.

Restart and confirm:

```text
lane sizing: batch=512, inbox=256x256B, arena=4194304B x8, wait=BACKOFF_PARK -- about 64 KiB held per idle query
```

### Backpressure, not overflow

When an inbox fills, the producer is **paused** — at 80% by default, resuming at 50%. Nothing
overflows, nothing is dropped, nothing spills: a slow query becomes a slow read of its source,
which is the correct shape. A smaller inbox pauses the source sooner; it never loses rows.

The inbox and arena are off-heap RAM, invisible to the garbage collector and never paged to disk
by the engine. What *can* go to disk is a query's operator state, which is a different budget --
see [State and spill](/help/topics/state-spill).

### How a lane waits: `wait-strategy`

| Value | Behaviour | Use it for |
|---|---|---|
| `BUSY_SPIN` | `Thread.onSpinWait()` forever | Lowest latency; burns a core per busy runner. Dedicated hardware only |
| `SPIN_THEN_YIELD` | Spins 64 polls, yields 64, then parks 1 ns | Near-spin latency while traffic flows |
| `BACKOFF_PARK` | Spins 8 polls, then parks exponentially up to 1 ms | **The node default.** Shared and containerised hosts, many low-rate queries; a runner parks once for all its lanes, so a thousand idle queries do not wake a thousand times |
| `BLOCKING` | Parks 1 ms every time | Lowest CPU, highest latency: development, and queries below roughly a thousand records a second |

A value that is not one of the four is refused at startup.

### Pitfalls

!!! warning "Pitfall: tuning the arena to save memory"
    The lane arena is allocated on first use and, for projections and windowed aggregates, never.
    Shrinking `arena.slab-bytes` saves nothing on those queries and risks `PRV-3001` on a join.

!!! note "Where the old names came from"
    Before these settings existed, error messages told operators to raise `arena.slab.size` and
    `lane.inbox.cell.size` — neither of which ever existed. If you find either name in an old
    runbook, it means `pravaha.lane.arena.slab-bytes` and `pravaha.lane.inbox.cell-bytes`.

!!! tip "The other ceiling: file descriptors"
    One bound source costs about one file descriptor for the life of the query. At `ulimit -n 1024`
    that is under a thousand sources, whatever the lane sizing. The node logs its descriptor usage at
    startup; raise `LimitNOFILE` before binding hundreds of sources.

## Sharing lanes {#sharing-lanes}

A lane of its own gives a query an inbox of its own — a megabyte at the default sizing.
[Sizing the inbox down](#sizing-lanes) is one way to make a thousand queries cheap. **Sharing
lanes** is the other: registered queries run as pipelines on a
fixed set of shared lanes, and a lane's inbox and arena serve every query on it.

The default is **`auto`**. Sharing saves memory and costs isolation: a shared lane shares its fate,
so a query whose pipeline throws -- including one refused with PRV-4001 for its state — kills the
lane, and every query on it with it, where a lane per query loses one. On a node with a few dozen
queries the memory is small and the isolation is worth it; on a node with thousands the memory is
the node. So under `auto` the first **64** queries (`auto-from`) each own a lane, and every
registration after them is placed on a shared lane. Queries already running are never moved: a node
that shrinks back below the threshold keeps the placements it made. `true` shares from the first
query, `false` never shares. One query can opt out of whichever it is —
[`WITH (lane = 'dedicated')`](#keeping-one-query-on-its-own-lane).

### The settings

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

### How a registration is placed

One rule: **the least loaded shared lane below `max-queries-per-lane` wins** — by query count,
lowest lane number on a tie. What a query reads does not matter: two queries over `txn` share a
lane, and so does a join.

**A registration that fits on no shared lane is not refused — it gets a lane of its own**, exactly as
with sharing off. Turning a memory setting on never makes a node accept fewer queries.

**Placements are not rebalanced automatically.** When queries are dropped, the slots they free on
shared lanes, and the headroom under `auto-from`, are used by new registrations; nothing already
running moves to fill them — until an administrator [rebalances](#seeing-placements-and-rebalancing-by-hand).
A restart re-places every query in journal order, the order they were first registered, so the
placements after a restart can differ from the ones before it.

### Keeping one query on its own lane

Whatever the node's mode, a registration can ask for a lane of its own:

```sql
CREATE CONTINUOUS QUERY settlement_totals
    KEYED BY (txn_id)
    WITH (lane = 'dedicated')
AS SELECT txn_id, amount FROM txn
```

It is placed as it would be with sharing off: its own inbox, its own arena, and a fate of its own —
a neighbour's failure cannot take it down, and its failure takes nobody else down. Every other query
is placed as before. Use it for the few queries whose isolation matters more than the megabyte an
inbox costs: the settlement feed, the one a pager is attached to, the one with a sink downstream
that a stall would hurt.

- `lane = 'shared'` is the default and means "whatever the node's mode says"; anything but
  `'dedicated'` or `'shared'` is refused with PRV-8017.
- **It survives a restart.** The choice is journalled with the registration (an `L` record after
  its `R`), so the query comes back on a lane of its own; a build that predates it refuses the
  journal by name rather than replaying the query onto a shared lane.
- **It belongs to the computation.** Two names that ask the same question share one computation.
  A dedicated registration joining one that already has a lane of its own marks it dedicated; one
  joining a computation already running on a **shared** lane is refused with PRV-8017 — register it
  `lane = 'shared'`, or move the running one first (below).
- A dedicated query counts in `pravaha_lane_own_queries` and towards `auto-from`.

### Moving a running query onto its own lane

A running query is never moved in place. It is moved the way anything behind a name is changed: a
[blue/green replacement](/help/topics/create-continuous-query), with the same SQL and a `lane`
option. The new version is started on a lane of its own, backfilled, and takes the name at a cutover
at an exact input position, so no reader loses or double-counts a row:

```sql
CREATE OR REPLACE CONTINUOUS QUERY settlement_totals
    KEYED BY (txn_id)
    WITH (lane = 'dedicated', cutover = 'auto')
AS SELECT txn_id, amount FROM txn
```

`lane = 'shared'` moves a dedicated query back under the node's mode the same way. A replacement
that does not say `lane` keeps the running version's choice, and one that says the lane it already
has, with the SQL unchanged, is refused (it would cut over to itself). The flag is journalled with
the cutover, so a restart keeps the new placement; a rollback puts the old version, and its lane,
back.

#### What the queries on a lane share

Every row on a shared lane carries a *route*. Each query has its own, and what it is fed alone — a
reader of its own, rows an embedder pushes, a catch-up read — reaches that query and no other on the
lane. A reader shared by several queries writes each row into a shared lane **once**, and every
query on the lane that reads it is handed that one copy. Readers are shared for sources that declare
at-least-once and no order (Aerospike and Cassandra), and exactly for Kafka and a file read once
through, which meet a joining query at an exact position (ADR-054); a JDBC, CDC or Delta source, or a
followed file, keeps a reader per query, so its queries share the lane and its inbox but each writes
its own copy into it.

Measured, 1,000 queries over one source on 8 shared lanes: **8 lanes and 8 copies of each row**,
where lanes of their own are 1,000 lanes and 1,000 copies — 8 MiB of inboxes at the defaults
against about 1 GB.

Before LANE-2 a shared lane carried one query per stream and never a join: rows were dispatched by
stream while each query was fed separately, so two queries over `txn` on one lane each counted the
other's rows (8 where 4 was right).

#### Worked: where five queries land

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


### Seeing placements, and rebalancing by hand

**Admin → Lanes** in the console lists every query and the lane it runs on — dedicated, a lane of its
own, or shared lane *n* — with the node's mode and how full each shared lane is. `pravaha lanes` prints
the same from a shell, and `GET /api/v1/lanes` with `GET /api/v1/queries` answer it over HTTP.

When drops have left room under `auto-from`, an administrator can hand that room to queries already
sharing: **Admin → Lanes → Preview the plan**, then **Rebalance now** (or `pravaha lanes rebalance` for
the plan and `pravaha lanes rebalance --yes` to run it; `POST /api/v1/lanes/rebalance?dryRun=true`
and `POST /api/v1/lanes/rebalance` over HTTP). It never runs by itself, and it needs the `admin` role.

The oldest shared queries move first, **one at a time**, each by a blue/green replacement with its SQL
unchanged and `lane = 'own'` — a lane of its own without pinning it the way `dedicated` does — then the
old version is released, so no answer is lost or counted twice. One at a time because each move runs the
query twice until its cutover and re-reads its source's history. A query that answers to several names,
or is already being replaced, is skipped and says why; a move whose source cannot replay its history is
reported as failed and the rest go on. `GET /api/v1/lanes/rebalance` (the page, reloaded, or
`pravaha lanes rebalance status`) shows how each move went. A restart re-places every query by the
node's mode again, so a moved query keeps its own lane only until then — use `lane = 'dedicated'` for
one that must.

### Watching it

| Signal | Means |
|---|---|
| `pravaha_lane_shared_queries{lane="0"}` | Queries on each shared lane, against `max-queries-per-lane`. Absent with sharing off |
| `pravaha_lane_own_queries` | Queries holding a lane — and an inbox — of their own. All of them with sharing off |
| `pravaha_lane_shared_bytes` | Off-heap the shared lanes hold between them — counted once, however many queries they carry |
| startup log / `GET /api/v1/status` | `lanes: shared, queries per lane [..] of at most 300; N on lanes of their own` |
| `GET /api/v1/lanes` | The mode in effect, `autoFrom`, `maxQueriesPerLane`, each shared lane's query count, `ownLaneQueries`, `dedicatedQueries` and `hosted`. Counts only, no names |
| `GET /api/v1/queries/{name}` | `lane` — `dedicated`, `shared` or `own` — and `sharedLane`, the shared lane's number or null |

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

### What it costs

- **Fate.** One failing pipeline takes the whole shared lane down — every query on it, and no other:
  the other shared lanes and queries on lanes of their own keep answering, and a registration after
  the failure is never placed on the dead lane (it stays out of placement until the node restarts).
  Pace is shared the same way: a query that stalls fills its lane's inbox, so its lane-mates are held
  back with it and nobody else is. A query at `state_fraction` 0.95
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

### When to turn it on

| Situation | Sharing? |
|---|---|
| Hundreds or thousands of low-rate queries, memory the constraint | Yes |
| Many queries over **one Aerospike set or Cassandra table** | Yes: one reader, one copy per lane |
| Many queries over one Kafka topic or a file read once through | Yes: one reader, met at an exact position (ADR-054) |
| A few high-rate queries whose isolation matters | No |

### Pitfalls

!!! warning "Pitfall: expecting sharing to cut reads of every source"
    Sharing a lane shares its inbox, arena and thread. It shares the *read* only where the source
    lets one reader feed several queries: at-least-once and unordered (Aerospike, Cassandra), or
    ordered with positions a joiner can meet exactly (Kafka, a file read once through — ADR-054, in
    [sharing](/help/topics/sharing#one-reader-for-many-queries)). A JDBC, CDC or Delta source, or a
    followed file, is still read once per query, however the lanes are shared.

!!! note "The status of ADR-027 and ADR-036"
    Threads were decoupled from lanes in Wave 9 (a fixed runner pool, one thread per core). The
    memory half — `pravaha.lane.multiplex.*` — is built, on by default under `auto` from the 64th
    query, and since LANE-2 takes any query and one copy of a shared source per lane.

## Where next

- [State and spill](/help/topics/state-spill) — the budget for operator state, not rows in flight
- [Observability](/help/topics/observability) — the lane and backpressure metrics
- [Reading the numbers on a plan](/help/topics/reading-a-plan) — a query blocked by a neighbour, told from one blocking itself
- [Sharing by fingerprint](/help/topics/sharing) — one computation for one question, and one reader for many queries
- [The execution model, long form](/help/execution-model#4-the-inbox)
- How it is built: [Architecture: runtime](/help/architecture-runtime)
