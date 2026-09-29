---
title: Metrics and alerts
slug: metrics-alerts
category: operating
order: 70
icon: graph-up-arrow
summary: "Every metric a node publishes at /actuator/prometheus -- queries, lanes, alerts, the catalogue, Flight -- and the console's assistant metrics, what each answers, the shipped alert rules, the health probes, and the console's operations verdict."
badge: PROMETHEUS
audience: Operators
keywords: [prometheus, actuator, grafana, alerting, promtool, rules, alert metrics, catalogue metrics, flight calls, assistant metrics, state_fraction, watermark lag, checkpoint age, commit latency, health, readiness, liveness, verdict, findings]
guide: operations#watching-a-running-node
related: [metrics-index, observability, state-spill, checkpoints-recovery, lane-sharing, console-tour]
---

A node publishes its metrics in Prometheus text format at **`http://<node>:18080/actuator/prometheus`**.
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
| `pravaha_query_feed_stopped` | 1 while a source of the query has stopped mid-read and is not retried. The query stays `RUNNING` — `running` above says 1 — and its view stops moving, so **alert on this**. The code is on the query's page and in `GET /api/v1/queries/{name}` (`feed`) |
| `pravaha_query_feed_failures_total` | Failures that stopped a source of the query — distinct failures, not stopped partitions |
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
| `pravaha_lane_shared_bytes` | Off-heap the shared lanes hold between them, counted once |
| `pravaha_state_spill_bytes_mapped` | Overflow mapped across every query — what `pravaha.state.spill.max-bytes` counts |
| `pravaha_debug_sessions_open` | [Debug sessions](/help/topics/time-travel-debugger) open on this node. Each is a second copy of a query's state |
| `pravaha_flight_calls_seconds_count`, `_sum`, `_max` `{operation, error}` | Flight calls by operation -- `query`, `query.plan`, `subscribe`, `register`, `replace` and the other actions -- and how long they took. A subscription is one call for as long as it is open |

## Alerts ([ADR-057](/help/topics/alerts))

| Metric | Answers |
|---|---|
| `pravaha_alert_keys_firing{alert}` | Keys of each alert firing now |
| `pravaha_alert_transitions_total{alert, kind}` | Keys that fired (`kind="fired"`) or cleared (`"cleared"`) since the node started |
| `pravaha_alert_notifications_total{channel, outcome}` | Notification attempts a channel accepted (`delivered`) or refused (`failed`). **Alert on the failed share** |
| `pravaha_alert_notification_retries_total{channel}` | Attempts that retried a notification the channel had refused before |
| `pravaha_alert_notifications_owed` | Decided and not yet accepted by any channel. Growing means nobody is being told |
| `pravaha_alert_delivery_seconds_count`, `_sum` `{channel}` | How long each channel took to answer; the mean is exact, as for commits |
| `pravaha_alert_journal_write_failures_total` | Alert journal writes that failed. A decision is not made until it is journalled, so nothing fires or clears meanwhile |

## The catalogue ([ADR-059](/help/topics/catalog-and-grants))

| Metric | Answers |
|---|---|
| `pravaha_catalog_access_decisions_total{privilege, outcome}` | Decisions at the enforcement points -- read, subscribe, build on, register, write, administer -- by privilege and `allow` or `deny`. A listing's per-object checks are not counted: a `SHOW` is not an attack |
| `pravaha_catalog_decision_cache_lookups_total{result}` | Decisions answered from the cache (`hit`) or worked out (`miss`). The cache empties at every catalogue change |
| `pravaha_catalog_changes_total{kind}` | Grants, revokes, policy creates, drops, binds and unbinds, owner changes and moves made on this node since it started |
| `pravaha_catalog_subscriptions_ended_total{reason}` | Subscriptions the engine ended because the caller was no longer entitled: `credential_revoked`, `access_withdrawn`, `narrowing_changed` |

Labels are bounded by configuration or by a fixed set: an alert's and a channel's name, a privilege, an
outcome, a kind. **No user, key, row or statement is ever a label** -- they are on the audit trail.

## The console's assistant

The console publishes its own, at **`http://<console>:17070/metrics`** -- only with `metrics.enabled`
in the console's configuration, and behind `metrics.token` when one is set. See
[Observability](/help/topics/observability).

| Metric | Answers |
|---|---|
| `pravaha_console_assist_requests_total{model, profile, outcome}` | Requests, by the model that answered (or failed last) and `ok`, `model_error`, `budget`, `config` or `error` |
| `pravaha_console_assist_tokens_total{model, profile, direction}` | Tokens used, `input` and `output` |
| `pravaha_console_assist_failures_total{model, profile, kind}` | A model's failures by kind, including the ones a fallback absorbed |
| `pravaha_console_assist_fallbacks_total{model, profile}` | Times a request moved past that model to the next in its chain |
| `pravaha_console_assist_latency_seconds_bucket`, `_sum`, `_count` `{model, profile}` | Seconds per request, fallbacks included -- a histogram, so percentiles are real |
| `pravaha_console_assist_ledger_tokens_today{model}` | Today's tokens in the usage ledger, every person together |

Plus the JVM and process meters Spring Boot publishes (`jvm_memory_used_bytes`,
`process_cpu_usage`, ...). Meters are removed when a query is dropped, so a dashboard of dropped
queries goes blank rather than freezing on the last value.

## Reading it

```bash
curl -s http://localhost:18080/actuator/prometheus | grep 'query="hourly_spend"'
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

## Dashboards

Four Grafana dashboards ship in `deploy/observability/grafana/`: node overview, query drill-down,
alerts and catalogue, and the assistant. Import them and pick the Prometheus data source; every panel
reads a metric on this page, and a test fails the build if one does not exist.

## Alert rules

These are the shipped rules, `deploy/observability/prometheus/pravaha-rules.yaml` -- the same text, and
a test fails if the two differ. Load the file as a Prometheus rule file, or turn on the Helm chart's
`prometheusRule`. Thresholds are starting points, each with the reason.

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

      - alert: PravahaSourceStopped
        expr: pravaha_query_feed_stopped == 1
        for: 1m
        labels: {severity: page}
        annotations:
          summary: "{{ $labels.query }} is RUNNING and a source of it has stopped"
          description: "The view answers at the frontier it reached and will not move again until the source is fixed and the query re-registered. The query's page names the code."

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

      - alert: PravahaDeadLettersArriving
        expr: increase(pravaha_query_dead_letters[15m]) > 0
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "{{ $labels.query }} is rejecting records it cannot decode"
          description: "Its view is missing them. `pravaha dlq list --name {{ $labels.query }}`, or the query's Dead letters screen."

      - alert: PravahaRejectingTooMuch
        expr: pravaha_query_dead_letters_degraded == 1
        for: 2m
        labels: {severity: critical}
        annotations:
          summary: "{{ $labels.query }} is past its dead-letter rate threshold"
          description: "A schema change nobody announced, not a bad partner file. The view is answering, and incompletely."

      - alert: PravahaDeadLettersLost
        expr: increase(pravaha_query_dead_letters_write_failures_total[15m]) > 0
        labels: {severity: critical}
        annotations:
          summary: "{{ $labels.query }} could not write a rejected record to its dead-letter file"
          description: "Those records are gone and nothing else records them. Check the disk and the permissions on pravaha.dlq.directory (PRV-4090)."

  - name: pravaha-alerts-catalog
    rules:
      - alert: PravahaNotificationDeliveryFailing
        expr: sum by (channel) (rate(pravaha_alert_notifications_total{outcome="failed"}[15m])) / sum by (channel) (rate(pravaha_alert_notifications_total[15m])) > 0.5
        for: 10m
        labels: {severity: page}
        annotations:
          summary: "The notifier channel {{ $labels.channel }} is refusing most of what it is sent"
          description: "Alerts are deciding and nobody is being told. The alert's page shows the channel's answer; each notification is retried every pravaha.alerts.redeliver-after under the same idempotency key."

      - alert: PravahaAlertNotificationsOwedGrowing
        expr: pravaha_alert_notifications_owed > 0 and deriv(pravaha_alert_notifications_owed[30m]) > 0
        for: 15m
        labels: {severity: warn}
        annotations:
          summary: "{{ $value }} alert notifications are owed and the backlog is growing"
          description: "Keys have fired or cleared and no channel has accepted the news. Check PravahaNotificationDeliveryFailing and the channel's endpoint."

      - alert: PravahaAlertJournalFailing
        expr: increase(pravaha_alert_journal_write_failures_total[15m]) > 0
        labels: {severity: page}
        annotations:
          summary: "The alert journal cannot be written"
          description: "An alert decides nothing it cannot make durable first, so nothing fires or clears until the disk under pravaha.alerts.journal is fixed."

      - alert: PravahaCatalogDenialsSpike
        expr: sum(rate(pravaha_catalog_access_decisions_total{outcome="deny"}[5m])) > 0.2 and sum(rate(pravaha_catalog_access_decisions_total{outcome="deny"}[5m])) > 5 * sum(rate(pravaha_catalog_access_decisions_total{outcome="deny"}[1h] offset 5m))
        for: 10m
        labels: {severity: warn}
        annotations:
          summary: "The catalogue is refusing {{ $value | humanize }} requests a second, five times its usual rate"
          description: "A revoked grant with clients retrying, a policy change that took more than intended, or someone probing. GET /api/v1/audit names who was refused what."

  - name: pravaha-console
    rules:
      - alert: PravahaAssistantAllModelsFailing
        expr: sum(increase(pravaha_console_assist_requests_total{outcome="ok"}[15m])) == 0 and sum(increase(pravaha_console_assist_requests_total{outcome="model_error"}[15m])) > 0
        for: 5m
        labels: {severity: warn}
        annotations:
          summary: "Every assistant request in the last 15 minutes failed on its models"
          description: "No model in any profile's chain is answering. Admin · AI models tests each one; a key, a quota or the provider's outage."
```

The first ten watch the queries. Of the rest: **delivery failing** is the share of refused attempts per
channel, so one flaky send is not a page and a channel refusing everything is; **owed growing** is the
backlog nobody has been told about, which a down channel makes climb; **journal failing** means the
alerts have stopped deciding at all; **denials spike** compares the last five minutes with the hour
before, so a steady trickle of refusals (a misconfigured client) does not fire it and a revoked grant
with a fleet retrying does; **all models failing** is the console's assistant with every chain
exhausted -- a key, a quota or a provider outage.

Alert on the **rate**, not on the queue being non-empty: every real feed produces some rejects, and
an alert that fires on the first one is an alert that gets muted in week two. And on
`increase(...)`, not on the depth: a query holding a steady hundred rejects from last Tuesday needs
nobody at three in the morning, and one that gained a hundred in fifteen minutes does. See
[Dead letters](/help/topics/dead-letters).

Three more worth having, depending on the deployment:

- **`pravaha_query_rows_in` flat** (`rate(...[15m]) == 0`) on a query fed by a source that should
  never be quiet. A source that *failed* is `pravaha_query_feed_stopped` above; this catches one
  that is merely silent (see [Dead letters](/help/topics/dead-letters)).
- **`increase(pravaha_query_dead_letters_evicted_total[1h]) > 0`**: retention is throwing the oldest
  entries away, so the queue's history is incomplete. Raise `pravaha.dlq.max-bytes`, or drain it.
- **`pravaha_query_subscribers == 0`** on a query somebody expects to be watched.

## Health probes

On port 18080, for an orchestrator:

| Endpoint | Answers | Used as |
|---|---|---|
| `/actuator/health/liveness` | Is the process alive | liveness probe; the container image's `HEALTHCHECK` |
| `/actuator/health/readiness` | Can this node serve a client — includes the `engine` indicator, so a node with Flight down, or a standby still waiting, is not ready | readiness probe |
| `/actuator/health` | Both, with details for an authorised caller | humans |

They are kept distinct because conflating them makes an orchestrator restart a node that is merely
still restoring state.

A node with a stopped source answers **`DEGRADED`** on the `engine` indicator, with `stoppedFeeds`
and the first stop's code in the detail — not `DOWN`: every view is still served, and taking the node
out of rotation would take its healthy queries with it. The server orders `DEGRADED` between
`OUT_OF_SERVICE` and `UP`, so the aggregate says it and the probe still answers 200.

## The console's verdict

The console's **Operations** screen scrapes the same endpoint once a second (one scrape however many
people are watching) and answers "is everything healthy, and if not, where?". The rules, written once
in the console:

| Finding | Severity | When |
|---|---|---|
| Not running | critical | `pravaha_query_running` is 0, or the query's state is `FAILED` |
| Source stopped | critical | `pravaha_query_feed_stopped` is 1, or the listing says the query's feed is `STOPPED`; the finding carries the code, linked to its help |
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
    Lane throughput, per-plugin throughput and errors, and latency percentiles. Lane backpressure
    **is** published now (`pravaha_query_backpressure_blocked_fraction`, `pravaha_lane_blocked_fraction`),
    and per-operator rows, state, watermarks and a sampled self time ride on
    `GET /api/v1/queries/{name}/plan` rather than on the scrape — see
    [reading the numbers on a plan](/help/topics/reading-a-plan).

## Where next

- [Every metric, indexed](/help/topics/metrics-index)
- [Observability: scraping, dashboards, rules, logs and traces](/help/topics/observability)
- [State and spill](/help/topics/state-spill)
- [Checkpoints and recovery](/help/topics/checkpoints-recovery)
- [The console, screen by screen](/help/topics/console-tour)
