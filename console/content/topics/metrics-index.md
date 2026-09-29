---
title: Every metric
slug: metrics-index
category: reference
order: 30
icon: graph-up
summary: "Every Prometheus metric a node publishes at /actuator/prometheus and the console at /metrics — name, type, labels, what it answers, what to alert on — and what is deliberately not published because nothing measures it."
badge: INDEX
audience: Operators
keywords: [prometheus, metrics, actuator, gauge, counter, alert, grafana, alerts, catalogue, flight, assistant, console metrics, state fraction, watermark lag, checkpoint, commit latency, spill, lane, subscribers, rows in]
guide: operations#watching-a-running-node
related: [metrics-alerts, observability, state-spill, checkpoints-recovery, lane-sharing, settings-index]
---

A Pravaha server publishes its metrics in the Prometheus text format at
**`/actuator/prometheus`** on its HTTP port (18080 by default). The engine registers them with
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

## Per query: is it alive, and is anything arriving

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_query_running{query}` | gauge | 1 while running or paused, 0 once terminal (`FAILED`, `DROPPED`) | it drops to 0 for a query that should be running |
| `pravaha_query_rows_in{query}` | gauge (monotonic) | Rows the query has ingested | `rate(...[5m]) == 0` on a query whose source should be live |
| `pravaha_query_subscribers{query}` | gauge | Subscribers attached to the computation. A sink is **not** counted | 0 on a query somebody expects to be watched |
| `pravaha_query_watermark_lag_seconds{query}` | gauge | How far behind **event time** the query is — the data, not the engine. `NaN` before the first row, never 0 | climbing without bound: event time is not advancing (a quiet source, or no `event-time` declared) |
| `pravaha_query_watermark_partitions{query}` | gauge | Input partitions contributing to this query's watermark. 0 means the query derives none | 0 on a windowed query: no window over it can close |
| `pravaha_query_watermark_partitions_idle{query}` | gauge | How many of them are **excluded right now** for having gone quiet | non-zero while lag climbs: a quiet partition is holding the watermark, or `idle-after` is longer than its normal gap |
| `pravaha_query_watermark_idle_exclusions_total{query}` | counter | How often a partition has been excluded since the query started | rising steadily: a partition keeps going quiet, so windows keep firing early and its rows keep arriving late |
| `pravaha_query_watermark_regressions_total{query}` | counter | How often a partition reported a watermark **below** the lane's — a source-side fault | non-zero at all: that source's time runs backwards, and its rows will be late from here on |

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

## Per node

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

## Per node: alerts (ADR-057)

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

## Per node: the catalogue (ADR-059)

Published while `pravaha.catalog.enabled` holds.

| Metric | Type | What it answers | Alert when |
|---|---|---|---|
| `pravaha_catalog_access_decisions_total{privilege, outcome}` | counter | Access decisions at the enforcement points -- `SELECT`, `SUBSCRIBE`, `BUILD_ON`, `CREATE`, `WRITE`, `MODIFY`, `MANAGE`, `USE` -- `allow` or `deny`. A listing filtered to what the caller may see is not counted: its refusals are not refusals of anything asked | denials five times their hourly rate (`PravahaCatalogDenialsSpike`) |
| `pravaha_catalog_decision_cache_lookups_total{result}` | counter | Decisions answered from the cache (`hit`) or worked out afresh (`miss`). Every catalogue change empties the cache, so a burst of misses follows a `GRANT` | a hit ratio that stays low: the catalogue is changing constantly |
| `pravaha_catalog_changes_total{kind}` | counter | Changes made on this node since it started: `grant`, `revoke`, `policy_create`, `policy_drop`, `policy_bind`, `policy_unbind`, `owner`, `move`, `object` (an object recorded, commented, tagged or forgotten) and `import`. Replaying the journal at start counts nothing | — |
| `pravaha_catalog_subscriptions_ended_total{reason}` | counter | Live subscriptions the engine ended because the caller stopped being entitled: `credential_revoked`, `access_withdrawn` (a grant or policy no longer allows it), `narrowing_changed` (a row filter or mask changed, so the stream's meaning would have) | — (the audit trail names who) |

## The console: the assistant (at the console's `/metrics`)

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

## Alert rules

The shipped rules are `deploy/observability/prometheus/pravaha-rules.yaml`, shown and explained in
[Metrics and alerts](/help/topics/metrics-alerts#alert-rules); the Helm chart can install them as a
`PrometheusRule`. They are kept in one place on purpose: two lists of thresholds drift.

## Not published, and why

| Would-be metric | Why it is absent |
|---|---|
| Per-operator rows, state, watermarks and time, **as Prometheus meters** | They are measured with `pravaha.metrics.operators` on, and they ride on `GET /api/v1/queries/{name}/plan` keyed by the plan's own node ids rather than on the scrape: a meter per operator per query is a cardinality bill nobody asked for. `pravaha_metrics_operators_enabled` says whether they exist at all |
| Commit-latency percentiles | Not measured — see above |
| Per-plugin throughput and errors | There are no `pravaha_plugin_*` meters; plugin health is on `GET /api/v1/plugins` |
| Read admission refusals | Counted on the `ReadAdmission` object (`rejectedCount()`, `queueTimedOutCount()`, `tenantRejectedCount()`) and not yet exported |
| An alert's keys, a principal, a policy's expression, a statement | Never labels: each would make every value a time series. They are on the alert's page and the audit trail |

Saying so beats implying a dashboard exists.

## Where next

- [Metrics and alerts](/help/topics/metrics-alerts) — reading these during an incident
- [Observability](/help/topics/observability) — scraping, dashboards, rules, logs and traces
- [Every setting](/help/topics/settings-index)
- [Operations: watching a running node (long form)](/help/operations#watching-a-running-node)
