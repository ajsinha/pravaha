---
title: Sizing lanes
slug: sizing-lanes
category: operating
order: 20
icon: cpu
summary: "What one registered query costs in memory and CPU, and the pravaha.lane.* settings that decide it — batch size, wait strategy, inbox, arena — with the sizing rules, measured numbers and a worked example."
audience: Operators
keywords: [lane, inbox, arena, batch-size, wait-strategy, cell-bytes, slab-bytes, max-slabs, PRV-3001, memory, off-heap, BACKOFF_PARK, BUSY_SPIN]
guide: operations#sizing-a-node-for-many-queries
related: [lane-sharing, state-spill, metrics-alerts, configuration]
---

Every registered continuous query runs on a **lane**: a single-writer pipeline with an
**inbox** its source rows are copied into, an **arena** its operators allocate output rows from,
and a **wait strategy** for when the inbox is empty. The lane is where a query's fixed memory
cost lives, so it is what decides how many queries a node can hold — and the defaults were chosen
for the opposite workload from a node holding hundreds of small queries.

A lane is not a thread. Since Wave 9 every lane on the node is driven by one shared pool of platform
threads, **one per core**; a query costs no platform thread of its own. What a query does cost is
off-heap memory, and almost all of it is the inbox.

## The settings

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

## What a query actually costs

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

## The two sizing rules

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

## A worked sizing

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

## Backpressure, not overflow

When an inbox fills, the producer is **paused** — at 80% by default, resuming at 50%. Nothing
overflows, nothing is dropped, nothing spills: a slow query becomes a slow read of its source,
which is the correct shape. A smaller inbox pauses the source sooner; it never loses rows.

The inbox and arena are off-heap RAM, invisible to the garbage collector and never paged to disk
by the engine. What *can* go to disk is a query's operator state, which is a different budget --
see [State and spill](/help/topics/state-spill).

## How a lane waits: `wait-strategy`

| Value | Behaviour | Use it for |
|---|---|---|
| `BUSY_SPIN` | `Thread.onSpinWait()` forever | Lowest latency; burns a core per busy runner. Dedicated hardware only |
| `SPIN_THEN_YIELD` | Spins 64 polls, yields 64, then parks 1 ns | Near-spin latency while traffic flows |
| `BACKOFF_PARK` | Spins 8 polls, then parks exponentially up to 1 ms | **The node default.** Shared and containerised hosts, many low-rate queries; a runner parks once for all its lanes, so a thousand idle queries do not wake a thousand times |
| `BLOCKING` | Parks 1 ms every time | Lowest CPU, highest latency: development, and queries below roughly a thousand records a second |

A value that is not one of the four is refused at startup.

## Pitfalls

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

## Where next

- [Sharing lanes between queries](/help/topics/lane-sharing) — the other way to take the inbox out of the arithmetic
- [State and spill](/help/topics/state-spill) — the budget for operator state, not rows in flight
- [Metrics and alerts](/help/topics/metrics-alerts)
- [The execution model, long form](/help/execution-model#4-the-inbox)
