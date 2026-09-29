---
title: Observability
slug: observability
category: operating
order: 74
icon: activity
summary: "Scraping a node and the console with Prometheus, importing the four Grafana dashboards, loading the shipped alert rules, JSON logs for Loki or Elasticsearch, and OpenTelemetry traces to a collector, Jaeger or Tempo."
badge: OPS
audience: Operators
keywords: [observability, prometheus, grafana, dashboards, alert rules, promtool, loki, elasticsearch, json logs, opentelemetry, tracing, otlp, jaeger, tempo, traceparent, correlation id]
guide: operations#observability
related: [metrics-alerts, metrics-index, alerts, catalog-and-grants, console-tour]
---

Four things ship for watching Pravaha, and each is off or plain until you turn it on: **metrics**
(always published; you point Prometheus at them), **dashboards and alert rules** (files under
`deploy/observability/`), **structured logs** (`pravaha.logging.format: json`) and **traces**
(`pravaha.tracing.enabled`). What each metric means is on [Every metric](/help/topics/metrics-index).

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

## Alert rules

`deploy/observability/prometheus/pravaha-rules.yaml` holds the rules [Metrics and
alerts](/help/topics/metrics-alerts#alert-rules) shows and explains. Load it and check it:

```bash
promtool check rules deploy/observability/prometheus/pravaha-rules.yaml
```

```yaml
rule_files:
  - /etc/prometheus/pravaha-rules.yaml
```

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

## Where next

- [Metrics and alerts](/help/topics/metrics-alerts) -- what to alert on, and why
- [Every metric](/help/topics/metrics-index)
- [Alerts](/help/topics/alerts) and [the catalogue](/help/topics/catalog-and-grants), whose meters are here
