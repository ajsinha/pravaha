---
title: The HTTP API
slug: http-api
category: reading
order: 60
icon: globe
summary: "Every /api/v1 endpoint the engine serves on port 18080 — streams, validation, plans, query and view descriptions, sinks, plugins, status, permissions and the audit trail — with its auth, a curl call and the JSON it answers."
badge: API
audience: Developers
keywords: [rest, http, curl, json, openapi, swagger, "18080", bearer, validate, explain, status, actuator, prometheus, "/api/v1", ApiError]
guide: user-guide#5-manage-what-is-running
related: [clients, observability, authentication, audit]
---

The engine serves two protocols. **Arrow Flight SQL** (port 19090) carries every read, every
subscription and every registration. The **HTTP API** (port 18080, Spring's `server.port`) carries the
questions that have no Flight form: *what streams exist, is this SQL valid, what is the plan, describe
this query or view, what sinks and plugins are there, what may I do, what has been decided*. It never
returns a view's rows and never registers a query — those are Flight calls. The Python SDK wraps every
endpoint below (`client.streams()`, `client.validate(sql)`, …), and the console is built on exactly
this surface.

## At a glance

| | |
|---|---|
| Base URL | `http://<node>:18080` (`server.port` in the node's configuration) |
| Format | JSON in and out |
| Authentication | `Authorization: Bearer <token>` when `pravaha.security.authentication` is `token`; without a credential every call but the open ones is `401` |
| Open without a credential | `/actuator/health…`, `/actuator/info`, `/api/v1/openapi.json`, `/api/docs` (Swagger UI) |
| Authorization | Per call, by the node's `SecurityPolicy`, filtered exactly as the Flight listing is |
| Errors | One shape: `{"code", "message", "helpUrl", "timestamp", "path"}` |
| The contract | `GET /api/v1/openapi.json`, browsable at `/api/docs` |

## Every endpoint

| Method | Path | Answers | SDK (Python) |
|---|---|---|---|
| GET | `/api/v1/streams` | Every stream you may read: name, version, fields, `eventTime`, `outOfOrderness`, `allowedLateness`, `source` plugin | `streams()` |
| GET | `/api/v1/streams/{name}` | One stream | `stream(name)` |
| POST | `/api/v1/streams` | Declares a stream; **201** with its summary. Needs administer on the name | `declare_stream(…)` |
| POST | `/api/v1/queries/validate` | Plans SQL without running it: `valid`, `diagnostics`, `outputFields`, `elapsedMicros`. Given a whole `CREATE CONTINUOUS QUERY` statement, every refusal registering it would give — keys, index, sink, name, options — without registering (VALIDATEREG-1) | `validate(sql)` |
| POST | `/api/v1/queries/explain?level=&format=` | The plan: `level` = `physical` (default), `logical`, `codegen`; `format=graph` adds nodes and edges; with `keys` in the body, the fingerprint a registration would get | `explain(sql, level, graph=, keys=, retention=, sink=)` |
| GET | `/api/v1/queries` | Every registered query you may see, described in full | `describe_queries()` |
| GET | `/api/v1/queries/{name}` | One query: keys by name, retention, sink and whether it is attached, rows in, names sharing it, streams it reads, failure | `describe_query(name)` |
| GET | `/api/v1/queries/{name}/plan` | The plan it is **running**, as nodes and edges, with its measured totals | `query_plan(name)` |
| GET | `/api/v1/views/{name}` | A view's schema, key, retention, sink and fingerprint — without reading it | `describe_view(name)` |
| GET | `/api/v1/sinks` | Every bound sink you may see: plugin, fields, key, emit modes, `acceptsRetractions`, guarantee, writers. Never its options | `sinks()` |
| GET | `/api/v1/plugins` | Every plugin the node can load: manifest, compatibility, kinds, capabilities, setting names, health, visible bindings | `plugins()` |
| GET | `/api/v1/me/permissions` | What the policy lets **you** do: register, read the audit trail, and per view/stream read and administer | `permissions()` |
| GET | `/api/v1/audit` | One page of recorded authorization decisions, newest first. Needs the audit-read permission | `audit(…)` |
| GET | `/api/v1/status` | Node id, version, engine state, uptime, registered-query count, stream count, plugin health | `status()` |
| GET | `/status` | The same as a self-contained HTML page | — |
| GET | `/actuator/prometheus` | Every metric, Prometheus text format | `metrics_text()` |
| GET | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | Spring health probes (open) | — |

## Validate and explain

The one pair of calls a SQL editor needs. A refused query is an **answer** (`200` with `valid:false`),
not an error:

```bash
curl -s -X POST http://engine:18080/api/v1/queries/validate \
  -H "Authorization: Bearer $PRAVAHA_TOKEN" -H "Content-Type: application/json" \
  -d '{"sql": "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id"}'
```

```json
{
  "valid": false,
  "diagnostics": [
    {"code": "PRV-2050",
     "message": "PRV-2050  GROUP BY user_id has no bound on its key space, ...",
     "helpUrl": "",
     "severity": "error",
     "range": null}
  ],
  "outputFields": [],
  "elapsedMicros": 1840
}
```

The SQL it refused, and the windowed version it accepts:

<!-- sql: refused PRV-2050 -->
```sql
SELECT user_id, COUNT(*) FROM txn GROUP BY user_id
```

```sql
SELECT user_id, window_start, window_end, COUNT(*) AS payments
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id, window_start, window_end
```

```json
{
  "valid": true,
  "diagnostics": [],
  "outputFields": [
    {"name": "user_id", "type": "VARCHAR", "nullable": false, "ordinal": 0},
    {"name": "window_start", "type": "TIMESTAMP", "nullable": false, "ordinal": 1},
    {"name": "window_end", "type": "TIMESTAMP", "nullable": false, "ordinal": 2},
    {"name": "payments", "type": "BIGINT", "nullable": false, "ordinal": 3}
  ],
  "elapsedMicros": 2210
}
```

(Values illustrative.) A diagnostic's `range` — `startLine`, `startColumn`, `endLine`, `endColumn` — is
read from the parser's own fields when there is one, and `null` when the refusal is about the plan
rather than a place in the text. The `helpUrl` names a host that does not exist; the console links each
code to its page here instead.

A request with no `sql` at all is a `400` (PRV-1050), not a `valid:false`: a malformed request is not a
query that failed to validate.

```bash
curl -s -X POST "http://engine:18080/api/v1/queries/explain?level=physical" \
  -H "Authorization: Bearer $PRAVAHA_TOKEN" -H "Content-Type: application/json" \
  -d '{"sql": "SELECT txn_id, amount FROM txn WHERE amount > 1000"}'
```

```json
{"level": "physical", "plan": "Project[txn_id, amount]\n  Filter[amount > 1000]\n    Scan[txn]\n", "outputFields": [ ... ], "graph": null}
```

The `plan` text is one line per operator with its inputs indented beneath it (the labels above are
illustrative). `level=codegen` returns the Java the engine will actually run. `format=graph` adds `graph.nodes` (each
`id`, `operator`, `detail`, `stateful`, `fields`) and `graph.edges` (`from`, `to`).

**The fingerprint a registration would get.** Add `keys` — the view's key columns as ordinals of the
`SELECT` list, in order — and optionally `retention` (ISO-8601 or `forever`; absent for the
registration's default), `sink` and `name`, and the answer carries `fingerprint`: the short value
`GET /api/v1/queries` would list for the query once you register it, computed by the registration's
own code for **you** — plan, your row filters, the key, the retention and your tenant (EXPLAINFP-1).
Equal to a running query's, it is the same computation, and registering it would share it. When a
registration would be refused — you may not register, a sink you may not write to — `fingerprint` is
`null` and `fingerprintRefusal` is the diagnostic registration would answer. Without `keys` both are
`null`. A fingerprint does not promise acceptance: a key column the view cannot keep is still refused
at registration (`PRV-2071`).

```bash
curl -s -X POST "http://engine:18080/api/v1/queries/explain" \
  -H "Authorization: Bearer $PRAVAHA_TOKEN" -H "Content-Type: application/json" \
  -d '{"sql": "SELECT txn_id, amount FROM txn WHERE amount > 1000", "keys": [0], "retention": "P7D"}'
```

## Describing what is registered

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" http://engine:18080/api/v1/queries/hourly_spend
```

```json
{
  "name": "hourly_spend",
  "state": "RUNNING",
  "sql": "SELECT user_id, window_start, window_end, SUM(amount) AS spend FROM TABLE(TUMBLE(...)) GROUP BY ...",
  "fingerprint": "3f9c2a61d0b4",
  "sharedWith": ["spend_per_hour"],
  "keyColumns": [{"name": "user_id", "ordinal": 0}, {"name": "window_end", "ordinal": 2}],
  "retention": "P7D",
  "sink": null,
  "rowsIn": 1284551,
  "countsWithheld": false,
  "registeredAt": "2026-09-19T08:02:11Z",
  "failure": null,
  "reads": ["txn"],
  "lane": "shared",
  "sharedLane": 2
}
```

| Field | Meaning |
|---|---|
| `sharedWith` | Other names on the same computation that **you** may see |
| `sink` | `{name, attached, failure, rowsWritten}`; `attached:false` with a PRV-8009 `failure` means the sink refused a batch and was detached |
| `countsWithheld` | `true` when your access is a row-filtered slice; `rowsIn` is then `-1` rather than the unfiltered total |
| `failure` | `{code, message, helpUrl}` for a `FAILED` query |
| `lane` | Where the query's computation runs: `dedicated` (a lane of its own because it was registered `WITH (lane = 'dedicated')`), `shared`, or `own` (sharing off, not yet on under `auto`, or every shared lane full) — see [Sharing lanes](/help/topics/lanes#sharing-lanes) |
| `sharedLane` | The shared lane's number when `lane` is `shared`; otherwise `null` |

`GET /api/v1/lanes` summarises placement for the node, counts only:

```json
{
  "mode": "auto",
  "autoFrom": 64,
  "maxQueriesPerLane": 300,
  "sharedLanes": [{"lane": 0, "queries": 12}, {"lane": 1, "queries": 11}],
  "ownLaneQueries": 65,
  "dedicatedQueries": 1,
  "hosted": 88
}
```

`mode` is the one in effect: `auto` with `auto-from: 0` shares at once and reads `true`, and
`autoFrom` is `null` unless the mode is `auto`.

`GET /api/v1/queries/{name}/plan` returns `nodes`, `edges`, `operatorMetrics` (keyed by the graph's
own node ids: `rowsIn`, `rowsOut`, `stateBytes`, `watermark`, `selfNanos`, `sampledRows`,
`selfTimeShare`), `bottleneck` — the node most of the sampled time went into, measured and not
inferred from row counts — a `metricsNote` saying which of three answers a null `operatorMetrics`
is (not registered, `pravaha.metrics.operators` off, or measured), and `query`: `rowsIn`,
`stateHeld`, `stateCeiling`, `viewSize`, `watermark`, `subscribers`, `backpressureWaits`,
`backpressureWaitSeconds`, `blockedFraction`, `inboxDepth`, `inboxCells`.

## Declaring a stream

```bash
curl -s -X POST http://engine:18080/api/v1/streams \
  -H "Authorization: Bearer $PRAVAHA_TOKEN" -H "Content-Type: application/json" \
  -d '{"name": "orders", "schema": "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP",
       "eventTime": "event_time", "outOfOrderness": "PT30S", "allowedLateness": "PT2M"}'
```

```json
{"name": "orders", "version": 1, "fieldCount": 6, "fields": [ ... ], "eventTime": "event_time", "outOfOrderness": "PT30S", "source": null, "allowedLateness": "PT2M"}
```

`201 Created`. The schema uses the node's one grammar, `name:TYPE,…` with `?` for nullable.
`allowedLateness` is optional and zero when absent: how long after a window is published a late row
may still correct it ([late data](/help/topics/event-time-watermarks#late-data)). An `outOfOrderness` or an
`allowedLateness` without an `eventTime` is refused — each is about an event time, and there is none —
and a negative lateness is refused. Declaring a stream is an administrative act; a caller who may not administer
the name gets `403`.

## Status

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" http://engine:18080/api/v1/status
```

```json
{
  "instanceId": "pravaha-node-01",
  "version": "2.3.1-SNAPSHOT",
  "engineState": "RUNNING",
  "uptimeSeconds": 8123,
  "registeredQueries": 2,
  "plugins": [{"name": "filesystem", "version": "0.1.0", "health": "HEALTHY", "detail": ""}],
  "streams": 6
}
```

`registeredQueries` counts the continuous queries registered on the node, by name — two names sharing
one computation are two. `streams` counts the streams it has declared. `/status` renders the same facts
as a page that needs nothing else to load.

## The audit trail and your own permissions

```bash
curl -s -H "Authorization: Bearer $PRAVAHA_TOKEN" \
  "http://engine:18080/api/v1/audit?decision=deny&since=2026-09-19T08:00:00Z&limit=50"
```

| Parameter | |
|---|---|
| `since`, `until` | ISO-8601 instants; anything else is `400` (PRV-1051) |
| `principal`, `view`, `action` | Exact matches |
| `decision` | `allow` or `deny` |
| `limit` | Page size (default 100) |
| `cursor` | A previous page's `nextCursor` |

The answer carries `events` (each `sequence`, `at`, `principal`, `tenant`, `roles`, `action`, `target`,
`decision`, `reason`, `detail`), `nextCursor` (`null` on the last page), and what the readable window
holds: `recording`, `sink`, `capacity`, `retained`, `evicted`, `oldestRetained`. A caller the policy does
not let read the trail gets `403` — and the attempt is itself recorded. See [Audit](/help/topics/audit).

`GET /api/v1/me/permissions` answers `principal`, `tenant`, `roles`, `anonymous`, `policy`, `register` and
`readAudit` (each `{allowed, reason}`), and for every view and stream you may see, `read` (`full` or
`filtered`) and `administer`.

## Errors

Every failure has one shape:

```json
{
  "code": "PRV-7002",
  "message": "PRV-7002  ann may not read payroll_view",
  "helpUrl": "",
  "timestamp": "2026-09-19T09:30:00Z",
  "path": "/api/v1/queries/payroll_view"
}
```

| Status | When |
|---|---|
| `400` | A configuration or planning problem, a registry refusal, a malformed request (PRV-1050, PRV-1051) |
| `401` | No credential, or one the node does not accept (PRV-7001) |
| `403` | Authenticated and not authorized (PRV-7002) |
| `404` | No such query or view — **and** a query that reads a stream you may not read, deliberately indistinguishable |
| `500` | A runtime, state, plugin or cluster failure on the node |

A name your policy denies is refused whether or not it exists, so the API cannot be used to probe for
names.

## Pitfalls

!!! warning "Pitfall: looking for a read endpoint"
    There is none. Reading a view is `SELECT` over Flight SQL (or the PostgreSQL gateway); HTTP only
    describes. An SDK's `query()` is the call you want.

!!! warning "Pitfall: 18080 is also Spring's actuator"
    `/actuator/prometheus` needs a credential on a node with authentication on; only `health` and
    `info` are open. Give the scraper a token.

!!! warning "Pitfall: `http_url` pointed at the Flight port"
    The Python SDK's `http_url` is the HTTP port (`http://host:18080`), not 19090. Pointed at 19090 every
    HTTP call fails to parse a response.

## Where next

- [SDK reference](/help/topics/clients#sdk-reference) — the calls that wrap these endpoints.
- [Metrics and alerts](/help/topics/observability) — what `/actuator/prometheus` exports.
- [Audit](/help/topics/audit) — reading the trail.
- How it is built: [Architecture: the server](/help/architecture-hosts#pravaha-server)
