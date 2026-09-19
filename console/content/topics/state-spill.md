---
title: State and spill
slug: state-spill
category: operating
order: 40
icon: hdd-stack
summary: "What bounds a query's state, the gauges that show it filling before PRV-4001, and the memory-mapped spill tier (pravaha.state.spill.*) that lets join and windowed-aggregate state overflow to disk instead of stopping."
badge: ADR-044
audience: Operators
keywords: [state ceiling, spill, overflow slab, compaction, max-bytes, max-overflow-slabs, PRV-4001, PRV-4005, PRV-4006, state_fraction, ADR-037, mapped files, RocksDB]
guide: operations#disk
related: [metrics-alerts, sizing-lanes, checkpoints-recovery, windows, joins]
---

A continuous query's **state** is what it must remember between rows: a windowed aggregate's
accumulators for every open window, a join's rows on each side still inside the match window, a
`COUNT(DISTINCT)`'s per-value counts. It is separate from a lane's inbox and arena (which hold rows
*in flight*, see [Sizing lanes](/help/topics/sizing-lanes)) and from the view (which holds the
*answer*).

Every piece of state has a bound, and a query that could have none is refused when it is planned --
an unwindowed `GROUP BY` over a stream is PRV-2050 for exactly this reason. What this page is about
is the *backstop*: the ceiling each operator's state is refused at, what happens when a query reaches
it, and the on-disk tier that turns "refused" into "slower".

## What bounds what

| Holder | Bounded by | On exceeding |
|---|---|---|
| Windowed aggregate state | the window — it closes and releases | — |
| Unwindowed keyed aggregate | nothing | **refused at planning**, PRV-2050 |
| Stream-to-stream join state | the match window in event time | rows released as the watermark passes |
| ...and as a backstop | the join's row ceiling | **fails** with PRV-3021 — never evicts |
| A query's state store | its ceiling, `pravaha_query_state_ceiling` | **PRV-4001**, unless the spill tier is on |
| A served view | retention | oldest rows forgotten |
| ...and as a backstop | its key ceiling | **fails** with PRV-4022 |

> **A bound that changes the answer belongs in the query's meaning. A bound that protects the
> machine belongs in configuration, and should fail rather than quietly alter results.**

So a view *forgets* (retention is a cache policy) and a join's ceiling *fails*: evicting join rows
to fit would silently lose matches the query asked for, and a wrong answer is worse than an error.
Eviction is not an option for state either — a retraction whose insert was evicted leaves a row
that can never be withdrawn — which is why spilling is the only correct way for Z-set state to
degrade.

## Watching state fill

Every query publishes its state against its ceiling (ADR-037 B1):

| Metric | Means |
|---|---|
| `pravaha_query_state_held{query=}` | Accumulators and join rows held **now** — in the ceiling's units, not bytes |
| `pravaha_query_state_ceiling{query=}` | What that is refused at. Zero means the plan has no bounded state at all, which is not the same as empty |
| `pravaha_query_state_fraction{query=}` | The ratio, 0 to 1. **The one to alert on** |

Before these existed PRV-4001 was the first anybody heard of a query's state. Alert at **0.9** --
the console's operations screen raises "State growing towards its ceiling" at 0.75 and "State
ceiling nearly reached" (critical) at 0.9:

```yaml
groups:
  - name: pravaha-state
    rules:
      - alert: PravahaQueryStateNearCeiling
        expr: pravaha_query_state_fraction > 0.9
        for: 5m
        labels: {severity: page}
        annotations:
          summary: "{{ $labels.query }} holds {{ $value | humanizePercentage }} of its state ceiling"
          runbook: "Without the spill tier, PRV-4001 at 100% stops the query and its lane."
```

What to do at 0.9, in order of preference: shorten the window or the join's time bound (the state
is keys x open windows, so a narrower window is the direct lever); narrow the key space with a
`WHERE`; or configure the spill tier so reaching the ceiling slows the query instead of stopping it.

## The spill tier

With a directory configured, join state and windowed-aggregate state — `COUNT(DISTINCT)` included
since ADR-044 — that outgrows its in-memory ceiling is written to **memory-mapped overflow slabs**
and the query keeps running, slower, instead of dying with PRV-4001.

```yaml
pravaha:
  state:
    spill:
      directory: /var/lib/pravaha/spill   # a directory alone switches it on
      max-overflow-slabs: 512             # per state store: the most overflow slabs it holds at once
      compaction-threshold: 0.5           # compact a store once this much of its overflow is free
      max-bytes: 20GB                     # the node's disk budget for spilled state; 0 = none
      # enabled: false                    # explicit, and wins in both directions
```

| Key | Default | What it decides |
|---|---|---|
| `pravaha.state.spill.enabled` | unset | **Tri-state, and explicit wins in both directions.** Unset + a directory = on. `false` keeps it off even with a directory configured |
| `pravaha.state.spill.directory` | empty | Where overflow slabs are written. Must be a **real disk** |
| `pravaha.state.spill.max-overflow-slabs` | `512` | The most overflow slabs **one** state store (a join, an aggregate's accumulators or distinct values) may hold — so one runaway store cannot take the whole budget |
| `pravaha.state.spill.compaction-threshold` | `0.5` | Once this fraction of a store's carved overflow space is free, its sparse slabs are emptied into the rest and their files truncated and deleted. `1.0` = only when a slab is entirely free |
| `pravaha.state.spill.max-bytes` | `0` | The **node's** disk budget for spilled state across every query (`20GB`, `512MB`, or bytes). `0` = no quota |

Mapped files through `java.nio`, not an embedded key-value store: no native library, nothing to
ship per platform. **There is no RocksDB, by decision** (ADR-044).

### Two ways a disk bound is enforced

- **PRV-4005, the quota.** A query whose state needs an overflow slab past `max-bytes` stops before
  anything is written. Watch `pravaha_state_spill_bytes_mapped` against the quota.
- **PRV-4006, the disk.** Before every slab is created the directory's filesystem is asked how much
  space it has, and a slab that would not fit is refused — rather than the disk filling inside a
  write to a mapped file, which would surface as a fault from whatever operator touched the page.

The disk check is per slab, not a reservation: another process filling the same filesystem between
two slabs can still get there first. **`max-bytes` below what the filesystem holds is the setting to
rely on.**

### Compaction

Released state is reused within its size class, but a slab is never handed back by reuse alone, so a
churning query — windows expiring, join rows retracted — would hold every file it ever carved.
Compaction moves the live blocks out of sparse slabs and deletes those files. It runs at the end of a
batch on the query's own lane thread, so it costs that lane a pause proportional to what it moves
and nothing on any other lane. A slab that cannot be emptied is simply kept: compaction can fail to
free a slab, never lose a block.

| Per-query metric | Means |
|---|---|
| `pravaha_query_spill_bytes` | Overflow slab the query holds on disk now. Zero until it spills |
| `pravaha_query_spill_live_bytes` | How much of that is live state |
| `pravaha_query_spill_fragmentation` | `1 - live / held`. **High while `_compactions` is flat** means the threshold is above what this query's churn reaches |
| `pravaha_query_spill_compactions` | Passes that emptied at least one slab |
| `pravaha_query_spill_slabs_released` | Slab files compaction gave back |
| `pravaha_state_spill_bytes_mapped` (node) | Overflow mapped across every query — what `max-bytes` counts |

## What it costs: the measurement

ADR-044 drove join and windowed-aggregate state to 1, 2, 4, 8 and 16 times a **64 MiB** in-memory
ceiling, on an **NVMe development laptop** (`SpillTierMeasurementIT`, 2026-09-19):

- Spilled state ran at **0.44x to 1.15x** the throughput of the same load held in RAM. Windowed-
  aggregate inserts were the worst case; probes and window firing were nearly unaffected.
- That held **while the files stayed in the page cache**. State larger than free RAM was not
  measured, and will be slower.
- Compaction brought the files back to the live state in about **a second per gigabyte freed**.
- A join's key index spills with its rows except its slot table: 16 bytes a slot, at most 0.7 full,
  always in RAM.

The recommendation that came with it: the tier **stays off by default** — there is no directory it
could safely assume — and should be turned on for a node with a local disk whose queries could
surprise it, with `max-bytes` below what the disk holds.

## Worked: turning it on for a join-heavy node

A node runs this join, whose state is every order waiting up to a day for its shipment:

```sql
CREATE CONTINUOUS QUERY awaiting_shipment KEYED BY (order_id)
RETAIN FOR P2D
AS SELECT o.order_id, o.customer_id, o.amount, s.carrier
FROM orders o
JOIN shipments s ON s.order_id = o.order_id
  AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' DAY;
```

On a sale day `pravaha_query_state_fraction{query="awaiting_shipment"}` climbs past 0.8. Before it
reaches 1.0, add the tier on the node's local NVMe volume, sized at half the volume:

```yaml
pravaha:
  state:
    spill:
      directory: /data/pravaha/spill
      max-bytes: 200GB
```

After the restart the query resumes from its checkpoint and, once past its ceiling, spills:

```text
pravaha_query_state_fraction{query="awaiting_shipment"}   1.0
pravaha_query_spill_bytes{query="awaiting_shipment"}       2.147483648E9
pravaha_query_spill_live_bytes{query="awaiting_shipment"}  1.9E9
pravaha_state_spill_bytes_mapped                           2.147483648E9
```

(Illustrative values.) `state_fraction` stays at 1.0 — the in-memory part is full, which is why it
spills — so once the tier is on, alert on `pravaha_state_spill_bytes_mapped` approaching `max-bytes`
as well.

## Pitfalls

!!! danger "Pitfall: a spill directory on tmpfs"
    `/tmp` is RAM on many distributions. Spilling there only moves the out-of-memory from the JVM to
    the kernel. Put the directory on a real disk.

!!! warning "Pitfall: no quota"
    `max-bytes: 0` means spilled state may fill the filesystem until PRV-4006 refuses the next slab --
    and whatever else writes to that filesystem (the checkpoint directory, the journal, the logs) is
    starved first. Set a quota.

!!! warning "Pitfall: on a shared lane, a ceiling is everybody's problem"
    With [lane sharing](/help/topics/lane-sharing) on and no spill tier, one query refused with
    PRV-4001 kills its lane and every query on it. Configure spill before sharing lanes.

!!! note "Older documents say nothing spills"
    `EXECUTION_MODEL.md` section 6 still reads "It is all RAM. Nothing spills" and calls the on-disk
    tier "scoped, not built". That was true before ADR-037 B2; the tier is built and configured by
    `pravaha.state.spill.*`. The *inbox and arena* are still RAM only.

## Where next

- [Metrics and alerts](/help/topics/metrics-alerts) — every gauge and the alert rules
- [Windows](/help/topics/windows) and [Joins](/help/topics/joins) — the bounds that come from the query itself
- [ADR-037: state that degrades instead of dying](/help/decisions/037-state-that-degrades-instead-of-dying)
- [ADR-044: no RocksDB; the mapped tier is L1](/help/decisions/044-no-rocksdb-the-mapped-tier-is-l1)
