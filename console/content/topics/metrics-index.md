---
title: Every metric
slug: metrics-index
category: reference
order: 30
icon: graph-up
summary: "Every Prometheus metric the engine publishes at /actuator/prometheus — name, type, labels, what it answers and what to alert on — and what is deliberately not published because the engine does not measure it."
badge: INDEX
audience: Operators
keywords: [prometheus, metrics, actuator, gauge, counter, alert, grafana, state fraction, watermark lag, checkpoint, commit latency, spill, lane, subscribers, rows in]
guide: operations#watching-a-running-node
related: [metrics-alerts, state-spill, checkpoints-recovery, lane-sharing, settings-index]
---

A Pravaha server publishes its metrics in the Prometheus text format at
**`/actuator/prometheus`** on its HTTP port (8080 by default). The engine registers them with
Micrometer under dotted names (`pravaha.query.rows.in`); Prometheus exposes them with underscores
(`pravaha_query_rows_in`), which is the form below and the form you query.

Every per-query metric carries the label **`query`** — the registered name. Two names sharing one
computation (see [Sharing](/help/topics/sharing)) are one computation, so they report the same
numbers. Meters are **removed when a query is dropped**, so a dashboard never shows a gauge for a
query that is gone, and no dropped query's state is kept alive by a forgotten gauge.

```bash
curl -s http://localhost:8080/actuator/prometheus | grep '^pravaha_query_state_fraction'
```

```text
pravaha_query_state_fraction{query="hourly_spend"} 0.12
pravaha_query_state_fraction{query="user_volume"} 0.87
```

(Sample values. With `pravaha.security.authentication: token`, send the bearer token.)

## Per query: is it alive, and is anything arriving

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_running{query}` | gauge | 1 while running or paused, 0 once terminal (`FAILED`, `DROPPED`) | it drops to 0 for a query that should be running |
| `pravaha_query_rows_in{query}` | gauge (monotonic) | Rows the query has ingested | `rate(...[5m]) == 0` on a query whose source should be live |
| `pravaha_query_subscribers{query}` | gauge | Subscribers attached to the computation. A sink is **not** counted | 0 on a query somebody expects to be watched |
| `pravaha_query_watermark_lag_seconds{query}` | gauge | How far behind **event time** the query is — the data, not the engine. `NaN` before the first row, never 0 | climbing without bound: event time is not advancing (a quiet source, or no `event-time` declared) |

## Per query: state

These count in the units the ceiling is expressed in — accumulators for a windowed aggregate, rows for
a join — **not bytes**. They are exactly what PRV-4001 compares.

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_state_held{query}` | gauge | State the query holds now | — (read with the ceiling) |
| `pravaha_query_state_ceiling{query}` | gauge | What that state is refused at. 0 means the plan has no bounded state at all — not the same as empty | — |
| `pravaha_query_state_fraction{query}` | gauge | held ÷ ceiling, 0 to 1. **The one to alert on** | `> 0.9` for five minutes — act before PRV-4001 stops the lane |

## Per query: the spill tier

Zero until the query spills (see [State and spill](/help/topics/state-spill)).

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_spill_bytes{query}` | gauge | Overflow slab the query's state holds on disk now | growing steadily: the query is outgrowing memory |
| `pravaha_query_spill_live_bytes{query}` | gauge | How much of that is live state | — |
| `pravaha_query_spill_fragmentation{query}` | gauge | `1 - live / held`, 0 to 1 | staying high while `_compactions` is flat: the compaction threshold is set above what this query's churn reaches |
| `pravaha_query_spill_compactions{query}` | gauge (monotonic) | Compaction passes that emptied at least one slab | — |
| `pravaha_query_spill_slabs_released{query}` | gauge (monotonic) | Overflow slabs (files) compaction gave back | — |

## Per query: the view

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_view_size{query}` | gauge | Keys the view holds | approaching the view's ceiling (PRV-4022) |
| `pravaha_query_view_evicted{query}` | gauge (monotonic) | Rows retention has removed | flat at 0 on a long-running query: retention is longer than anyone intended, or nothing is old enough yet |
| `pravaha_query_view_updates{query}` | gauge (monotonic) | Corrections applied — a key's value replaced | — |
| `pravaha_query_view_removals{query}` | gauge (monotonic) | Retractions applied — a key withdrawn | — |

## Per query: checkpoints and commits

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_checkpoint_last_success_timestamp_seconds{query}` | gauge | When the query last **stored** a checkpoint, as Unix seconds. `NaN` while not checkpointing or before the first — never 0, which would read as 1970 | `time() - ... > 5 * interval`: that age is how much a recovery would replay |
| `pravaha_query_checkpoint_duration_seconds{query}` | gauge | How long that last stored checkpoint took, snapshot to stored | approaching `pravaha.checkpoint.timeout` |
| `pravaha_query_checkpoint_failures_total{query}` | counter | Checkpoints that did not happen | rising while the last-success age rises |
| `pravaha_query_commit_latency_seconds_count{query}` | counter | Commits that changed the view | — |
| `pravaha_query_commit_latency_seconds_sum{query}` | counter | Total time those commits took: from applying the changes to the last subscriber **and sink** having them (a slow sink is on this path) | the mean climbing |

The mean commit latency over a window is exact:

```text
rate(pravaha_query_commit_latency_seconds_sum[5m])
  / rate(pravaha_query_commit_latency_seconds_count[5m])
```

**No percentiles are published.** The engine keeps a count and a total, not each commit's duration,
and a p99 it did not measure would be invented. Idle commits — a watermark tick with nothing in it — are
not timed. This console's Operations screen shows the mean and labels it as a mean.

## Per node

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_lane_shared_queries{lane}` | gauge | Queries on each shared lane, against `pravaha.lane.multiplex.max-queries-per-lane`. Absent with sharing off | near the ceiling on every lane |
| `pravaha_lane_own_queries` | gauge | Queries holding a lane — and an inbox — of their own. All of them with sharing off | rising on a node with sharing on: registrations are not fitting on shared lanes |
| `pravaha_lane_shared_bytes` | gauge | Off-heap the shared lanes hold — inboxes and arenas — counted once however many queries they carry. Zero with sharing off | growing with lanes built, not with queries |
| `pravaha_state_spill_bytes_mapped` | gauge | Overflow slab mapped on the node across every query — what `pravaha.state.spill.max-bytes` counts | well before the quota: at it, the next query to need a slab stops (PRV-4005) |
| `pravaha_debug_sessions_open` | gauge | [Debug sessions](/help/topics/time-travel-debugger) open on this node, against `pravaha.debug.sessions.max`. Each holds a whole second copy of a query's lanes, arena and state. Zero when nobody is debugging | above zero for longer than an investigation takes: a forgotten session is a query running twice |

Spring Boot also publishes its standard JVM, process and HTTP metrics (`jvm_*`, `process_*`,
`http_server_requests_*`) on the same endpoint.

## A starting set of alerts

```text
groups:
- name: pravaha
  rules:
  - alert: PravahaQueryStopped
    expr: pravaha_query_running == 0
    for: 1m
  - alert: PravahaStateNearCeiling
    expr: pravaha_query_state_fraction > 0.9
    for: 5m
  - alert: PravahaCheckpointStale
    expr: time() - pravaha_query_checkpoint_last_success_timestamp_seconds > 300
    for: 5m
  - alert: PravahaWatermarkBehind
    expr: pravaha_query_watermark_lag_seconds > 600
    for: 10m
  - alert: PravahaSpillNearQuota
    expr: pravaha_state_spill_bytes_mapped > 0.8 * 21474836480
    for: 5m
```

The thresholds are examples: 300 s is five checkpoint intervals at the default `1m`; 600 s of lag and
a 20 GB spill quota stand in for your own numbers. The console's Operations screen raises the
watermark-lag finding at its own `ui.lag_warn_seconds` (300 s by default).

## Not published, and why

| Would-be metric | Why it is absent |
|---|---|
| Per-operator rows, state or watermarks | The runtime counts per query, not per operator. `GET /api/v1/queries/{name}/plan` says so rather than splitting a query's totals across its operators |
| Lane backpressure | The engine does not sample it |
| Commit-latency percentiles | Not measured — see above |
| Per-plugin throughput and errors | There are no `pravaha_plugin_*` meters; plugin health is on `GET /api/v1/plugins` |
| Read admission refusals | Counted on the `ReadAdmission` object (`rejectedCount()`, `queueTimedOutCount()`, `tenantRejectedCount()`) and not yet exported |

Saying so beats implying a dashboard exists.

## Where next

- [Metrics and alerts](/help/topics/metrics-alerts) — reading these during an incident
- [Every setting](/help/topics/settings-index)
- [Operations: watching a running node (long form)](/help/operations#watching-a-running-node)
