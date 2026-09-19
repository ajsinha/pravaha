---
title: Metrics and alerts
slug: metrics-alerts
category: operating
order: 70
icon: graph-up-arrow
summary: "Every metric a node publishes at /actuator/prometheus, what each answers, the alert rules worth having, the health probes, and how the console's operations verdict is decided."
badge: PROMETHEUS
audience: Operators
keywords: [prometheus, actuator, grafana, alerting, state_fraction, watermark lag, checkpoint age, commit latency, health, readiness, liveness, verdict, findings]
guide: operations#watching-a-running-node
related: [metrics-index, state-spill, checkpoints-recovery, lane-sharing, console-tour]
---

A node publishes its metrics in Prometheus text format at **`http://<node>:8080/actuator/prometheus`**.
They are per query, labelled `query="<name>"`, plus a handful per node. This page lists every one,
says which to alert on and why, gives rules you can load as they are, and explains the verdict the
console's operations screen draws from the same numbers.

One principle decides what is here: **a number the engine does not measure is not published**. There
are no commit-latency percentiles, because the engine keeps a count and a total, not each commit's
duration; there are no per-operator rows or watermarks, because the runtime counts per query. A p99
it did not measure would be invented.

## Per query

| Metric | Answers |
|---|---|
| `pravaha_query_running` | Is it alive — 1 running, 0 terminal |
| `pravaha_query_rows_in` | Is anything arriving |
| `pravaha_query_state_held` | Accumulators and join rows held **now** (the ceiling's units, not bytes) |
| `pravaha_query_state_ceiling` | What those are refused at. Zero = the plan has no bounded state |
| `pravaha_query_state_fraction` | The ratio, 0 to 1. **Alert on this** — it is the warning before PRV-4001 |
| `pravaha_query_spill_bytes` | Overflow slab held on disk now. Zero until it spills |
| `pravaha_query_spill_live_bytes` | How much of that is live |
| `pravaha_query_spill_fragmentation` | `1 - live / held` |
| `pravaha_query_spill_compactions` | Compaction passes that emptied a slab |
| `pravaha_query_spill_slabs_released` | Slab files compaction gave back |
| `pravaha_query_view_size` | Keys the view holds |
| `pravaha_query_view_evicted` | What retention removed. Flat at zero on a long-running query: nothing is old enough yet, or retention is longer than intended |
| `pravaha_query_view_updates` | Corrections applied |
| `pravaha_query_view_removals` | Retractions applied |
| `pravaha_query_watermark_lag_seconds` | How far behind **event time** it is. `NaN` before the first row — never zero, which would read as up to date |
| `pravaha_query_subscribers` | Subscribers attached. A sink is not counted |
| `pravaha_query_checkpoint_last_success_timestamp_seconds` | When it last stored a checkpoint (Unix seconds). `NaN` if not checkpointing |
| `pravaha_query_checkpoint_duration_seconds` | How long that checkpoint took |
| `pravaha_query_checkpoint_failures_total` | Checkpoints that did not happen |
| `pravaha_query_commit_latency_seconds_count`, `_sum` | Commits that changed the view, and their total time — through the last subscriber **and sink** |

## Per node

| Metric | Answers |
|---|---|
| `pravaha_lane_shared_queries{lane=}` | Queries on each shared lane. Absent with [lane sharing](/help/topics/lane-sharing) off |
| `pravaha_lane_own_queries` | Queries holding a lane of their own |
| `pravaha_state_spill_bytes_mapped` | Overflow mapped across every query — what `pravaha.state.spill.max-bytes` counts |

Plus the JVM and process meters Spring Boot publishes (`jvm_memory_used_bytes`,
`process_cpu_usage`, ...). Meters are removed when a query is dropped, so a dashboard of dropped
queries goes blank rather than freezing on the last value.

## Reading it

```bash
curl -s http://localhost:8080/actuator/prometheus | grep 'query="hourly_spend"'
```

```text
pravaha_query_running{query="hourly_spend"} 1.0
pravaha_query_rows_in{query="hourly_spend"} 1284311.0
pravaha_query_state_held{query="hourly_spend"} 18204.0
pravaha_query_state_ceiling{query="hourly_spend"} 1000000.0
pravaha_query_state_fraction{query="hourly_spend"} 0.018204
pravaha_query_view_size{query="hourly_spend"} 91540.0
pravaha_query_watermark_lag_seconds{query="hourly_spend"} 12.4
pravaha_query_checkpoint_last_success_timestamp_seconds{query="hourly_spend"} 1.7898102E9
pravaha_query_commit_latency_seconds_count{query="hourly_spend"} 40211.0
pravaha_query_commit_latency_seconds_sum{query="hourly_spend"} 18.3
```

(Sample values; a real scrape also carries `# HELP` and `# TYPE` lines.)

The **mean** commit latency over a window is exact:

```text
rate(pravaha_query_commit_latency_seconds_sum[5m]) / rate(pravaha_query_commit_latency_seconds_count[5m])
```

## Alert rules

Load as a Prometheus rule file. Thresholds are starting points, each with the reason.

```yaml
groups:
  - name: pravaha
    rules:
      - alert: PravahaQueryNotRunning
        expr: pravaha_query_running == 0
        for: 1m
        labels: {severity: page}
        annotations:
          summary: "{{ $labels.query }} is registered and not running"
          description: "A failed query refuses reads rather than serving a stale view. Open it in the console to see the PRV code."

      - alert: PravahaQueryStateNearCeiling
        expr: pravaha_query_state_fraction > 0.9
        for: 5m
        labels: {severity: page}
        annotations:
          summary: "{{ $labels.query }} is at {{ $value | humanizePercentage }} of its state ceiling"
          description: "At 1.0 it stops with PRV-4001 unless pravaha.state.spill is configured."

      - alert: PravahaWatermarkBehind
        expr: pravaha_query_watermark_lag_seconds > 300
        for: 10m
        labels: {severity: warn}
        annotations:
          summary: "{{ $labels.query }} is {{ $value | humanizeDuration }} behind in event time"
          description: "Late data, a stopped source, or an idle partition holding the watermark. State grows while it lags."

      - alert: PravahaCheckpointStale
        expr: time() - pravaha_query_checkpoint_last_success_timestamp_seconds > 900
        for: 5m
        labels: {severity: warn}
        annotations:
          summary: "{{ $labels.query }} last checkpointed {{ $value | humanizeDuration }} ago"
          description: "A restart now would replay everything since."

      - alert: PravahaCheckpointFailing
        expr: increase(pravaha_query_checkpoint_failures_total[15m]) > 0
        labels: {severity: warn}
        annotations:
          summary: "{{ $labels.query }} is failing to checkpoint"
          description: "Check the node log and the checkpoint directory's disk."

      - alert: PravahaSpillNearQuota
        expr: pravaha_state_spill_bytes_mapped > 0.8 * 20e9
        for: 10m
        labels: {severity: warn}
        annotations:
          summary: "Spilled state is at {{ $value | humanize1024 }}B of a 20 GB quota"
          description: "At the quota the next query to need a slab stops with PRV-4005. Replace 20e9 with your pravaha.state.spill.max-bytes."
```

Two more worth having, depending on the deployment:

- **`pravaha_query_rows_in` flat** (`rate(...[15m]) == 0`) on a query fed by a source that should
  never be quiet — the source stopped, or a record it could not decode stopped it (see
  [Dead letters](/help/topics/dead-letters)).
- **`pravaha_query_subscribers == 0`** on a query somebody expects to be watched.

## Health probes

On port 8080, for an orchestrator:

| Endpoint | Answers | Used as |
|---|---|---|
| `/actuator/health/liveness` | Is the process alive | liveness probe; the container image's `HEALTHCHECK` |
| `/actuator/health/readiness` | Can this node serve a client — includes the `engine` indicator, so a node with Flight down, or a standby still waiting, is not ready | readiness probe |
| `/actuator/health` | Both, with details for an authorised caller | humans |

They are kept distinct because conflating them makes an orchestrator restart a node that is merely
still restoring state.

## The console's verdict

The console's **Operations** screen scrapes the same endpoint once a second (one scrape however many
people are watching) and answers "is everything healthy, and if not, where?". The rules, written once
in the console:

| Finding | Severity | When |
|---|---|---|
| Not running | critical | `pravaha_query_running` is 0, or the query's state is `FAILED` |
| State ceiling nearly reached | critical | `state_fraction` at or above 0.9 |
| State growing towards its ceiling | warn | `state_fraction` at or above 0.75 |
| No watermark yet | info | the lag is `NaN` and the query is not paused — usually no `event-time` declared |
| Event time is behind | warn | lag above `ui.lag_warn_seconds` (300 s by default) |
| Checkpoints are failing | warn | the failure counter rose since the last look |
| No recent checkpoint | warn | the last stored checkpoint is more than 15 minutes old |

The verdict is **critical** if any finding is, **"Healthy, with N things to watch"** if any warning
is, and otherwise "All N queries are healthy". An engine whose metrics endpoint does not answer is
critical in itself. Each finding names the query and says what to do.

## Pitfalls

!!! warning "Pitfall: alerting on `rows_in` alone"
    A query can be ingesting every row and emitting nothing: with no `event-time` on its stream its
    watermark never advances and no window closes. Pair a throughput panel with watermark lag.

!!! warning "Pitfall: reading `state_held` as bytes"
    It is accumulators for an aggregate and rows for a join — the units the ceiling is expressed in.
    For bytes on disk use the `spill` meters.

!!! note "What is not published"
    Lane throughput, lane backpressure, per-operator rows and state, per-plugin throughput and
    errors, and latency percentiles. The console's plan view says so rather than dividing a query's
    totals across its operators.

## Where next

- [Every metric, indexed](/help/topics/metrics-index)
- [State and spill](/help/topics/state-spill)
- [Checkpoints and recovery](/help/topics/checkpoints-recovery)
- [The console, screen by screen](/help/topics/console-tour)
