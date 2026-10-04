---
title: Observability — metrics, dashboards, alert rules, logs and traces
slug: observability
category: operating
order: 70
icon: activity
summary: "Every metric a node and the console publish, what each answers and what to alert on; the four Grafana dashboards, the shipped Prometheus rules, the health probes and the console's verdict; JSON logs and OpenTelemetry traces."
badge: PROMETHEUS
audience: Operators
keywords: [metrics, prometheus, actuator, scrape, grafana, dashboard, alert rules, promtool, alertmanager, health, liveness, readiness, probes, verdict, logs, json logs, loki, elasticsearch, correlation id, tracing, opentelemetry, otlp, jaeger, tempo, every metric, pravaha_query_rows_in, pravaha_query_watermark_lag_seconds, pravaha_query_state_fraction, pravaha_query_feed_stopped, pravaha_lane_blocked_fraction, pravaha_console_assist_requests_total]
guide: operations#watching-a-running-node
related: [alerts, reading-a-plan, lanes, state-spill, checkpoints-recovery]
---

A node publishes its metrics in Prometheus text format at **`http://<node>:18080/actuator/prometheus`**,
per query (labelled `query="<name>"`) and per node; the console publishes its own at `/metrics`. This
page is how to watch both: [scraping](#scraping-with-prometheus), the shipped
[dashboards](#dashboards) and [alert rules](#alert-rules), the [health probes](#health-probes) and the
console's [verdict](#the-consoles-verdict), [logs](#logs) and [traces](#traces) — and, at the end,
[every metric](#every-metric) with what it answers and when to alert on it.

One principle decides what is published: **a number the engine does not measure is not published**.
There are no commit-latency percentiles, because the engine keeps a count and a total, not each
commit's duration; a p99 it did not measure would be invented.

## Scraping with Prometheus

A node publishes at `/actuator/prometheus` on its HTTP port (18080). Every meter carries the
`application` and `node` labels, which is what the dashboards' `$node` variable reads.

```yaml
scrape_configs:
  - job_name: pravaha
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["pravaha-node-01:18080"]
  - job_name: pravaha-console
    metrics_path: /metrics
    authorization:
      credentials_file: /etc/prometheus/pravaha-console-metrics.token
    static_configs:
      - targets: ["pravaha-console:17070"]
```

The console's `/metrics` is **off by default**: turn it on with `metrics.enabled` in the console's
configuration (or `CONSOLE_METRICS_ENABLED=true`). It is gated by configuration, not by a sign-in,
because Prometheus has no session; set `metrics.token` (`CONSOLE_METRICS_TOKEN`) and every scrape must
send it as a bearer. Without a token it answers anyone who can reach the console, and the console
says so at startup -- it names the models configured and how much each is used.

On Kubernetes the chart does it for you: `serviceMonitor.enabled: true` scrapes the node, and
`prometheusRule.enabled: true` installs the rules below. Both are off by default because the
Prometheus Operator's CRDs may not be installed.

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

Import each file under `deploy/observability/grafana/` (Dashboards, New, Import) and choose your
Prometheus data source. Every dashboard has a `datasource` variable; the node ones have `$node` and
`$query`.

| File | Shows |
|---|---|
| `pravaha-node-overview.json` | Health, lanes and shared-lane fill, rows in, commit latency, checkpoint age, Flight calls, the JVM |
| `pravaha-query-drilldown.json` | One query or several: rows in, state fraction, watermark lag, checkpoint age, feed stopped, subscribers, dead letters, spill |
| `pravaha-alerts-catalogue.json` | Keys firing, fired and cleared, notifications delivered, failed and retried, the backlog owed, delivery time; access denials by privilege, the decision cache, grant and policy changes |
| `pravaha-assistant.json` | The console's assistant: requests and tokens per model, failures, fallbacks, latency percentiles |

A test starts a node with every surface on, scrapes it, and fails the build if a dashboard or a rule
names a metric the node does not publish -- so a panel does not quietly say "No data".

## Alert rules {#alert-rules}

`deploy/observability/prometheus/pravaha-rules.yaml` holds the rules below. Load it and check it:

```bash
promtool check rules deploy/observability/prometheus/pravaha-rules.yaml
```

```yaml
rule_files:
  - /etc/prometheus/pravaha-rules.yaml
```

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
        labels: {severity: critical}
        annotations:
          summary: "{{ $labels.query }} is registered and not running"
          description: "A failed query refuses reads rather than serving a stale view. Open it in the console to see the PRV code."

      - alert: PravahaSourceStopped
        expr: pravaha_query_feed_stopped == 1
        for: 1m
        labels: {severity: critical}
        annotations:
          summary: "{{ $labels.query }} is RUNNING and a source of it has stopped"
          description: "The view answers at the frontier it reached and will not move again until the source is fixed and the query re-registered. The query's page names the code."

      - alert: PravahaQueryStateNearCeiling
        expr: pravaha_query_state_fraction > 0.9
        for: 5m
        labels: {severity: critical}
        annotations:
          summary: "{{ $labels.query }} is at {{ $value | humanizePercentage }} of its state ceiling"
          description: "At 1.0 it stops with PRV-4001 unless pravaha.state.spill is configured."

      - alert: PravahaWatermarkBehind
        expr: pravaha_query_watermark_lag_seconds > 300
        for: 10m
        labels: {severity: warning}
        annotations:
          summary: "{{ $labels.query }} is {{ $value | humanizeDuration }} behind in event time"
          description: "Late data, a stopped source, or an idle partition holding the watermark. State grows while it lags."

      - alert: PravahaCheckpointStale
        expr: time() - pravaha_query_checkpoint_last_success_timestamp_seconds > 900
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "{{ $labels.query }} last checkpointed {{ $value | humanizeDuration }} ago"
          description: "A restart now would replay everything since."

      - alert: PravahaCheckpointFailing
        expr: increase(pravaha_query_checkpoint_failures_total[15m]) > 0
        labels: {severity: warning}
        annotations:
          summary: "{{ $labels.query }} is failing to checkpoint"
          description: "Check the node log and the checkpoint directory's disk."

      - alert: PravahaSpillNearQuota
        expr: pravaha_state_spill_bytes_mapped > 0.8 * 20e9
        for: 10m
        labels: {severity: warning}
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
          description: "The feed stopped at a record it could not keep (PRV-4090). Check the disk and the permissions on pravaha.dlq.directory, then drop and register the query, or restart the node."

  - name: pravaha-alerts-catalog
    rules:
      - alert: PravahaNotificationDeliveryFailing
        expr: sum by (channel) (rate(pravaha_alert_notifications_total{outcome="failed"}[15m])) / sum by (channel) (rate(pravaha_alert_notifications_total[15m])) > 0.5
        for: 10m
        labels: {severity: critical}
        annotations:
          summary: "The notifier channel {{ $labels.channel }} is refusing most of what it is sent"
          description: "Alerts are deciding and nobody is being told. The alert's page shows the channel's answer; each notification is retried every pravaha.alerts.redeliver-after under the same idempotency key."

      - alert: PravahaAlertNotificationsOwedGrowing
        expr: pravaha_alert_notifications_owed > 0 and deriv(pravaha_alert_notifications_owed[30m]) > 0
        for: 15m
        labels: {severity: warning}
        annotations:
          summary: "{{ $value }} alert notifications are owed and the backlog is growing"
          description: "Keys have fired or cleared and no channel has accepted the news. Check PravahaNotificationDeliveryFailing and the channel's endpoint."

      - alert: PravahaAlertJournalFailing
        expr: increase(pravaha_alert_journal_write_failures_total[15m]) > 0
        labels: {severity: critical}
        annotations:
          summary: "The alert journal cannot be written"
          description: "An alert decides nothing it cannot make durable first, so nothing fires or clears until the disk under pravaha.alerts.journal is fixed."

      - alert: PravahaCatalogDenialsSpike
        expr: sum(rate(pravaha_catalog_access_decisions_total{outcome="deny"}[5m])) > 0.2 and sum(rate(pravaha_catalog_access_decisions_total{outcome="deny"}[5m])) > 5 * sum(rate(pravaha_catalog_access_decisions_total{outcome="deny"}[1h] offset 5m))
        for: 10m
        labels: {severity: warning}
        annotations:
          summary: "The catalogue is refusing {{ $value | humanize }} requests a second, five times its usual rate"
          description: "A revoked grant with clients retrying, a policy change that took more than intended, or someone probing. GET /api/v1/audit names who was refused what."

  - name: pravaha-console
    rules:
      - alert: PravahaAssistantAllModelsFailing
        expr: sum(increase(pravaha_console_assist_requests_total{outcome="ok"}[15m])) == 0 and sum(increase(pravaha_console_assist_requests_total{outcome="model_error"}[15m])) > 0
        for: 5m
        labels: {severity: warning}
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

**Routing.** Every rule carries exactly one label, `severity`, and it is one of two values:
`critical` -- someone should act now (a query stopped or about to; alerts or notifications not
getting out) -- or `warning`, to look at during working hours. Route on it in Alertmanager:

```text
route:
  receiver: tickets
  routes:
    - matchers: [severity="critical"]
      receiver: pager
```

These are the words Pravaha's own `CREATE ALERT` uses for its severities (with `info`), and the
ones shared Alertmanager configurations already route on; a test holds every shipped rule to them.

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

## Logs

`pravaha.logging.format: json` makes every line the node writes one JSON object -- Spring Boot's own
structured logging, in its Logstash shape:

```json
{"@timestamp":"2026-09-28T21:00:30.49-04:00","@version":"1","message":"registered hourly_spend","logger_name":"com.ash.messaging.pravaha.registry.QueryRegistry","thread_name":"virtual-104","level":"INFO","level_value":20000,"query":"hourly_spend","correlationId":"9f2c41d07a1b3e55","traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7"}
```

The logging context is carried as fields of its own: `correlationId` on every request (the one the
caller sent in `X-Correlation-Id`, if it was plain, else a new one, answered in the same header),
`query` while a request or a Flight call concerns one query, and `traceId` and `spanId` while a span
is open. Anything but `text` or `json` refuses the start. For Elastic Common Schema instead, set Spring
Boot's own `logging.structured.format.console: ecs`.

For Loki, ship the container's stdout with Grafana Alloy or Promtail and parse it as JSON:

```yaml
pipeline_stages:
  - json:
      expressions:
        level: level
        query: query
        trace_id: traceId
  - labels:
      level:
```

Keep `query` and `trace_id` as parsed fields, not labels: a label per query or per trace is the same
cardinality mistake in Loki as in Prometheus. For Elasticsearch, Filebeat's `ndjson` parser reads the
lines as they are.

The console has the same switch: `logging.format: json` in its configuration (`CONSOLE_LOG_FORMAT`),
with `@timestamp`, `level`, `logger`, `thread`, `message`, the request's `correlationId`, and
`traceId`/`spanId` when OpenTelemetry is installed in the console's environment.

## Traces

Off by default, and off means no tracer at all: no span is made and no trace id enters a log line.
Turn it on:

```yaml
pravaha:
  tracing:
    enabled: true
    endpoint: http://otel-collector:4318/v1/traces
    sampling-probability: 1.0
```

With `pravaha.tracing.endpoint` empty the node reads `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`, then
`OTEL_EXPORTER_OTLP_ENDPOINT` (with `/v1/traces` appended). Export is OTLP over HTTP, through the
JDK's own HTTP client. Every `management.tracing.*` and `management.otlp.tracing.*` setting of Spring
Boot's still works and wins.

| Span | When |
|---|---|
| `http get /api/v1/...` | every REST request |
| `pravaha.flight.<operation>` | every Flight call: `query`, `query.plan`, `subscribe`, `register`, `replace` and the other actions, with the `operation` attribute and the query's name as `pravaha.query` |
| `pravaha.query.register`, `pravaha.query.replace` | a registration, or the start of a blue/green replacement, however it arrived (REST, Flight or SQL) |
| `pravaha.checkpoint` | each checkpoint a query takes |
| `pravaha.alert.notify` | each notification sent to a channel, with the alert, the channel and the kind |

A caller's W3C `traceparent` is continued: a trace started in an application runs through the node.
The Python SDK sends the current OpenTelemetry context on every REST and Flight call when the
`opentelemetry` package is installed, and `pravaha.tracecontext.use(traceparent)` sets one for a block
without it. The console passes on a `traceparent` its own request arrived with.

An OpenTelemetry Collector that receives OTLP and forwards to Jaeger or Tempo:

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
exporters:
  otlp/tempo:
    endpoint: tempo:4317
    tls:
      insecure: true
service:
  pipelines:
    traces:
      receivers: [otlp]
      exporters: [otlp/tempo]
```

Jaeger accepts OTLP directly on 4318, so the node's endpoint can point at it with no collector.

!!! note "What tracing costs"
    A span per call and per checkpoint; nothing per row. The lanes that move rows are not traced,
    because a span per batch would cost more than the batch. Sampling below 1.0 makes the rest cheaper
    again: an unsampled call is neither recorded nor exported.

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

## Every metric {#every-metric}

The engine registers its metrics with
Micrometer under dotted names (`pravaha.query.rows.in`); Prometheus exposes them with underscores
(`pravaha_query_rows_in`), which is the form below and the form you query.

Every per-query metric carries the label **`query`** — the registered name. Two names sharing one
computation (see [Sharing](/help/topics/sharing)) are one computation, so they report the same
numbers. Meters are **removed when a query is dropped**, so a dashboard never shows a gauge for a
query that is gone, and no dropped query's state is kept alive by a forgotten gauge.

```bash
curl -s http://localhost:18080/actuator/prometheus | grep '^pravaha_query_state_fraction'
```

```text
pravaha_query_state_fraction{query="hourly_spend"} 0.12
pravaha_query_state_fraction{query="user_volume"} 0.87
```

(Sample values. With `pravaha.security.authentication: token`, send the bearer token.)

### Per query: is it alive, and is anything arriving

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_running{query}` | gauge | 1 while running or paused, 0 once terminal (`FAILED`, `DROPPED`) | it drops to 0 for a query that should be running |
| `pravaha_query_rows_in{query}` | gauge (monotonic) | Rows the query has ingested | `rate(...[5m]) == 0` on a query whose source should be live |
| `pravaha_query_feed_stopped{query}` | gauge | 1 while a source of the query has stopped mid-read and is not retried. The query stays `RUNNING` — `running` says 1 — and its view stops moving. The code is on the query's page and in `GET /api/v1/queries/{name}` (`feed`) | 1, at all |
| `pravaha_query_feed_failures_total{query}` | counter | Failures that stopped a source of the query — distinct failures, not stopped partitions | rising |
| `pravaha_query_subscribers{query}` | gauge | Subscribers attached to the computation. A sink is **not** counted | 0 on a query somebody expects to be watched |
| `pravaha_query_view_reads_total{query,path}` | counter | Reads of the query's view, by the access path each took: `point` (one probe by the whole key), `range` (a run of `RANGE`'s ordered index), `index` (a probe of an `INDEX (column)`), `scan` (every row) | `scan` rising on a view read by one column: declare an `INDEX` on it (IDXVIS-1) |
| `pravaha_query_watermark_lag_seconds{query}` | gauge | How far behind **event time** the query is — the data, not the engine. `NaN` before the first row, never 0 | climbing without bound: event time is not advancing (a quiet source, or no `event-time` declared) |
| `pravaha_query_watermark_partitions{query}` | gauge | Input partitions contributing to this query's watermark. 0 means the query derives none | 0 on a windowed query: no window over it can close |
| `pravaha_query_watermark_partitions_idle{query}` | gauge | How many of them are **excluded right now** for having gone quiet | non-zero while lag climbs: a quiet partition is holding the watermark, or `idle-after` is longer than its normal gap |
| `pravaha_query_watermark_idle_exclusions_total{query}` | counter | How often a partition has been excluded since the query started | rising steadily: a partition keeps going quiet, so windows keep firing early and its rows keep arriving late |
| `pravaha_query_watermark_regressions_total{query}` | counter | How often a partition reported a watermark **below** the lane's — a source-side fault | non-zero at all: that source's time runs backwards, and its rows will be late from here on |

### Per query: state

These count in the units the ceiling is expressed in — accumulators for a windowed aggregate, rows for
a join — **not bytes**. They are exactly what PRV-4001 compares.

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_state_held{query}` | gauge | State the query holds now | — (read with the ceiling) |
| `pravaha_query_state_ceiling{query}` | gauge | What that state is refused at. 0 means the plan has no bounded state at all — not the same as empty | — |
| `pravaha_query_state_fraction{query}` | gauge | held ÷ ceiling, 0 to 1. **The one to alert on** | `> 0.9` for five minutes — act before PRV-4001 stops the lane |

### Per query: the spill tier

Zero until the query spills (see [State and spill](/help/topics/state-spill)).

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_spill_bytes{query}` | gauge | Overflow slab the query's state holds on disk now | growing steadily: the query is outgrowing memory |
| `pravaha_query_spill_live_bytes{query}` | gauge | How much of that is live state | — |
| `pravaha_query_spill_fragmentation{query}` | gauge | `1 - live / held`, 0 to 1 | staying high while `_compactions` is flat: the compaction threshold is set above what this query's churn reaches |
| `pravaha_query_spill_compactions{query}` | gauge (monotonic) | Compaction passes that emptied at least one slab | — |
| `pravaha_query_spill_slabs_released{query}` | gauge (monotonic) | Overflow slabs (files) compaction gave back | — |

### Per query: the view

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_view_size{query}` | gauge | Keys the view holds | approaching the view's ceiling (PRV-4022) |
| `pravaha_query_view_evicted{query}` | gauge (monotonic) | Rows retention has removed | flat at 0 on a long-running query: retention is longer than anyone intended, or nothing is old enough yet |
| `pravaha_query_view_updates{query}` | gauge (monotonic) | Corrections applied — a key's value replaced | — |
| `pravaha_query_view_removals{query}` | gauge (monotonic) | Retractions applied — a key withdrawn | — |

### Per query: checkpoints and commits

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_checkpoint_last_success_timestamp_seconds{query}` | gauge | When the query last **stored** a checkpoint, as Unix seconds. `NaN` while not checkpointing or before the first — never 0, which would read as 1970 | `time() - ... > 5 * interval`: that age is how much a recovery would replay |
| `pravaha_query_checkpoint_duration_seconds{query}` | gauge | How long that last stored checkpoint took, snapshot to stored | approaching `pravaha.checkpoint.timeout` |
| `pravaha_query_checkpoint_failures_total{query}` | counter | Checkpoints that did not happen | rising while the last-success age rises |
| `pravaha_query_commit_latency_seconds_count{query}` | counter | Commits that changed the view | — |
| `pravaha_query_commit_latency_seconds_sum{query}` | counter | Total time those commits took: from applying the changes to the last subscriber **and sink** having them (a slow sink is on this path) | the mean climbing |
| `pravaha_query_backpressure_waits_total{query}` | counter | Episodes in which one of this query's writers found nowhere to put a row. **Episodes, not rows**: a source held off for an hour is one | — |
| `pravaha_query_backpressure_wait_seconds_total{query}` | counter | How long those episodes lasted altogether, counting one still open. `rate()` of it is the share of time the query could not be fed | over about 0.2 as a rate |
| `pravaha_query_backpressure_blocked_fraction{query}` | gauge | The same share as the *lanes* see it, 0 to 1, counting every writer into those lanes. **The one to alert on** for "is this query the limit" | over 0.2 sustained; near 1 the lane is the limit |
| `pravaha_query_inbox_depth{query}` | gauge | Rows queued into the lane and not yet taken, right now. Instantaneous: a burst between two scrapes is invisible | near `inbox_cells` sustained |
| `pravaha_query_inbox_cells{query}` | gauge | What that depth is out of, so the depth reads as a fraction without knowing `pravaha.lane.inbox.cells` | — |

The mean commit latency over a window is exact:

```text
rate(pravaha_query_commit_latency_seconds_sum[5m])
  / rate(pravaha_query_commit_latency_seconds_count[5m])
```

**No percentiles are published.** The engine keeps a count and a total, not each commit's duration,
and a p99 it did not measure would be invented. Idle commits — a watermark tick with nothing in it — are
not timed. This console's Operations screen shows the mean and labels it as a mean.

### Per node

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_lane_shared_queries{lane}` | gauge | Queries on each shared lane, against `pravaha.lane.multiplex.max-queries-per-lane`. Absent with sharing off | near the ceiling on every lane |
| `pravaha_lane_blocked_fraction{lane}` | gauge | The share of time a writer into that shared lane had no room, 0 to 1. **Per lane, not per query**, because the lane is what is waited on: a query reading high on its own blocked fraction and low on its own waiting is queued behind a neighbour. Absent with sharing off | over 0.2 sustained |
| `pravaha_lane_inbox_depth{lane}` | gauge | Cells published into that shared lane and not yet drained. Absent with sharing off | near the lane's cells sustained |
| `pravaha_metrics_operators_enabled` | gauge | 1 when `pravaha.metrics.operators` is on, so this node's plans carry per-operator numbers. A dashboard that finds none can say which of the two it is looking at | — |
| `pravaha_lane_own_queries` | gauge | Queries holding a lane — and an inbox — of their own. All of them with sharing off | rising on a node with sharing on: registrations are not fitting on shared lanes |
| `pravaha_lane_shared_bytes` | gauge | Off-heap the shared lanes hold — inboxes and arenas — counted once however many queries they carry. Zero with sharing off | growing with lanes built, not with queries |
| `pravaha_state_spill_bytes_mapped` | gauge | Overflow slab mapped on the node across every query — what `pravaha.state.spill.max-bytes` counts | well before the quota: at it, the next query to need a slab stops (PRV-4005) |
| `pravaha_debug_sessions_open` | gauge | [Debug sessions](/help/topics/time-travel-debugger) open on this node, against `pravaha.debug.sessions.max`. Each holds a whole second copy of a query's lanes, arena and state. Zero when nobody is debugging | above zero for longer than an investigation takes: a forgotten session is a query running twice |
| `pravaha_flight_calls_seconds_count{operation, error}` | counter | Flight calls, by operation: `query` (a Flight SQL read), `query.plan`, `subscribe`, `register`, `replace`, `drop`, `sql.action`, `list.actions`, the `dlq.*` and `debug.*` actions, and `action.unknown` for a name the engine does not know -- never whatever a client wrote. `error` is the exception's class, or `none` | a rising `error!="none"` share |
| `pravaha_flight_calls_seconds_sum{operation, error}`, `_max` | counter, gauge | How long those calls took. A subscription is one call for as long as it is open, so its time is its lifetime | — |
| `pravaha_flight_calls_active_seconds_count{operation}`, `_sum`, `_max` | gauge | Flight calls in progress now, and how long they have been open: open subscriptions, among others | — |

Spring Boot also publishes its standard JVM, process and HTTP metrics (`jvm_*`, `process_*`,
`http_server_requests_*`) on the same endpoint.

### Per node: alerts (ADR-057)

Published while `pravaha.alerts.enabled` (the default) holds. An alert's meters appear when it is
created and go when it is dropped; a channel's when it is bound.

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_alert_keys_firing{alert}` | gauge | Keys of the alert firing now | — (it is the alert's own business to page) |
| `pravaha_alert_transitions_total{alert, kind}` | counter | Keys that fired (`kind="fired"`) or cleared (`"cleared"`) since the node started. Journalled decisions, so a restart does not count them again | a flapping alert: fired and cleared far more than its `fire_after` suggests |
| `pravaha_alert_notifications_total{channel, outcome}` | counter | Notification attempts a channel accepted (`outcome="delivered"`) or refused (`"failed"`), each attempt counted | the failed share over half for ten minutes (`PravahaNotificationDeliveryFailing`) |
| `pravaha_alert_notification_retries_total{channel}` | counter | Attempts that retried a notification the channel had refused before, every `pravaha.alerts.redeliver-after` | — |
| `pravaha_alert_delivery_seconds_count{channel}`, `_sum` | counter | Sends timed and their total time: the mean delivery time is exact | the mean approaching the webhook's timeout |
| `pravaha_alert_notifications_owed` | gauge | Keys whose news -- fired or cleared -- no channel has accepted yet | above zero and growing (`PravahaAlertNotificationsOwedGrowing`) |
| `pravaha_alert_journal_write_failures_total` | counter | Alert journal writes that failed. A decision is journalled before anything is sent, so a failed write is a decision not made | any (`PravahaAlertJournalFailing`) |

### Per node: the catalogue (ADR-059)

Published while `pravaha.catalog.enabled` holds.

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_catalog_access_decisions_total{privilege, outcome}` | counter | Access decisions at the enforcement points -- `SELECT`, `SUBSCRIBE`, `BUILD_ON`, `CREATE`, `WRITE`, `MODIFY`, `MANAGE`, `USE` -- `allow` or `deny`. A listing filtered to what the caller may see is not counted: its refusals are not refusals of anything asked | denials five times their hourly rate (`PravahaCatalogDenialsSpike`) |
| `pravaha_catalog_decision_cache_lookups_total{result}` | counter | Decisions answered from the cache (`hit`) or worked out afresh (`miss`). Every catalogue change empties the cache, so a burst of misses follows a `GRANT` | a hit ratio that stays low: the catalogue is changing constantly |
| `pravaha_catalog_changes_total{kind}` | counter | Changes made on this node since it started: `grant`, `revoke`, `policy_create`, `policy_drop`, `policy_bind`, `policy_unbind`, `owner`, `move`, `object` (an object recorded, commented, tagged or forgotten) and `import`. Replaying the journal at start counts nothing | — |
| `pravaha_catalog_subscriptions_ended_total{reason}` | counter | Live subscriptions the engine ended because the caller stopped being entitled: `credential_revoked`, `access_withdrawn` (a grant or policy no longer allows it), `narrowing_changed` (a row filter or mask changed, so the stream's meaning would have) | — (the audit trail names who) |

### The console: the assistant (at the console's `/metrics`)

Published by the console, not the node, at `http://<console>:17070/metrics` -- only with
`metrics.enabled` in the console's configuration, and behind `metrics.token` when one is set.
Counted at the one model router the console holds, so every surface's requests are in it.

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_console_info{version}` | gauge | Always 1; the console's version | — |
| `pravaha_console_assist_requests_total{model, profile, outcome}` | counter | Requests, by the model that answered -- or failed last -- and `outcome`: `ok`, `model_error` (every model in the chain failed), `budget` (a person's daily budget refused it), `config`, `error`. `model="none"` when no model was reached | no `ok` and some `model_error` for fifteen minutes (`PravahaAssistantAllModelsFailing`) |
| `pravaha_console_assist_tokens_total{model, profile, direction}` | counter | Tokens the model reported, `input` and `output` | a spend rate you did not plan for |
| `pravaha_console_assist_failures_total{model, profile, kind}` | counter | A model's failures by kind -- `model_unavailable`, `model_rate_limited`, `model_refused`, `model_output_error` -- including those a fallback absorbed | one model's failures rising while requests still succeed: its fallback is carrying it |
| `pravaha_console_assist_fallbacks_total{model, profile}` | counter | Times a request moved past this model to the next in the chain | — |
| `pravaha_console_assist_latency_seconds_bucket{model, profile, le}`, `_sum`, `_count` | histogram | Seconds per request, fallbacks included. A histogram, so `histogram_quantile` gives real percentiles | p95 past the provider's timeout |
| `pravaha_console_assist_ledger_tokens_today{model}` | gauge | Today's tokens in the usage ledger, every person together (the per-person view is Admin · AI models) | — |

No person's name is a label on any of these: who asked what is in the console's assist log.

The thresholds to alert at are in the shipped rules, [above](#alert-rules) — kept in one place on
purpose, because two lists of thresholds drift. The Helm chart can install them as a `PrometheusRule`.

### Not published, and why

| Would-be metric | Why it is absent |
|---|---|
| Per-operator rows, state, watermarks and time, **as Prometheus meters** | They are measured with `pravaha.metrics.operators` on, and they ride on `GET /api/v1/queries/{name}/plan` keyed by the plan's own node ids rather than on the scrape: a meter per operator per query is a cardinality bill nobody asked for. `pravaha_metrics_operators_enabled` says whether they exist at all |
| Commit-latency percentiles | Not measured — see above |
| Per-plugin throughput and errors | There are no `pravaha_plugin_*` meters; plugin health is on `GET /api/v1/plugins` |
| Read admission refusals | Counted on the `ReadAdmission` object (`rejectedCount()`, `queueTimedOutCount()`, `tenantRejectedCount()`) and not yet exported |
| An alert's keys, a principal, a policy's expression, a statement | Never labels: each would make every value a time series. They are on the alert's page and the audit trail |

Saying so beats implying a dashboard exists.

## Where next

- [Alerts](/help/topics/alerts) — being told when a row enters a view, and when it leaves
- [Reading the numbers on a plan](/help/topics/reading-a-plan) — per-operator counts and backpressure
- [Lanes](/help/topics/lanes) and [state and spill](/help/topics/state-spill) — what the lane and state gauges measure
- [Checkpoints and recovery](/help/topics/checkpoints-recovery)
- The long form: [Operations](/help/operations#watching-a-running-node)
