---
title: The Spring Boot starter
slug: spring-boot-starter
category: embedding
order: 20
icon: flower1
summary: "The embedded engine as a Spring bean: one dependency, configuration under pravaha.*, PravahaTemplate for pushing and reading, @PravahaListener for every committed change — with a complete service and what fails at startup."
badge: SPRING
audience: Developers
keywords: [spring, spring boot, starter, PravahaTemplate, "@PravahaListener", PravahaEngineCustomizer, auto-configuration, bean, application.yaml, listener, concurrency, max-pending]
guide: user-guide#10-embed-it-in-a-spring-boot-application
related: [embedded-engine, subscriptions, configuration, checkpoints-recovery]
---

`pravaha-spring-boot-starter` makes the [embedded engine](/help/topics/embedded-engine) a bean of your
own Spring Boot application. It is a layer **above** the Spring-free engine: the engine core contains no
Spring and the build refuses to let any reach it (ADR-019, ADR-020), so the starter is where the Spring
version of *your* application meets an engine that does not care which one it is.

What you get: an auto-configured, started `PravahaEngine` built from `pravaha.*` properties; a
`PravahaTemplate` to push rows, register queries and read views; and `@PravahaListener` methods that
receive every committed change of a view off the engine's thread. There is no server, no port and no
authentication — this is one application with a maintained answer inside it.

## At a glance

| | |
|---|---|
| Artifact | `com.ash.messaging:pravaha-spring-boot-starter` |
| Auto-configuration | `PravahaAutoConfiguration`: beans `PravahaEngine` (started, closed with the context) and `PravahaTemplate`, both `@ConditionalOnMissingBean` |
| Switched off by | `enabled: false` under `pravaha` |
| Configuration | `pravaha.node`, `streams`, `sources`, `lookups`, `sinks`, `queries`, `registry`, `checkpoint`, `dlq`, `watermark`, `listener` |
| Listener signatures | `(RowChange)`, `(List<RowChange>)`, `(Record, boolean retraction)`, `(Map<String,Object>, boolean retraction)` |
| Failure policy | A listener naming a missing query, or with a signature it cannot be called with, **fails startup**; one that throws is logged and stays subscribed |
| Tested against | Spring Boot 3.5 |

## Add it

```xml
<dependency>
  <groupId>com.ash.messaging</groupId>
  <artifactId>pravaha-spring-boot-starter</artifactId>
  <version>2.4.2-SNAPSHOT</version>
</dependency>
```

## Configure it

`application.yaml` of your service. Streams and queries declared here exist before any bean is created:

```yaml
pravaha:
  node:
    id: orders-service
  streams:
    orders:
      schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 30s
  queries:
    region_revenue:
      sql: >
        SELECT region, window_start, window_end, SUM(amount) AS revenue, COUNT(*) AS orders
        FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
        GROUP BY region, window_start, window_end
      keys: [region, window_end]
      retention: 24h
  registry:
    journal: /var/lib/orders/pravaha/registry.journal
  checkpoint:
    directory: /var/lib/orders/pravaha/checkpoints
    interval: 1m
  listener:
    max-pending: 10000
```

The query is ordinary Pravaha SQL, planned exactly as the server would plan it:

```sql
CREATE CONTINUOUS QUERY region_revenue_5m
    KEYED BY (region, window_end)
    RETAIN FOR PT24H
AS
SELECT region, window_start, window_end, SUM(amount) AS revenue, COUNT(*) AS orders
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY region, window_start, window_end;
```

### Every property

| Property (under `pravaha`) | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false` turns the auto-configuration off |
| `node.id` | `pravaha-embedded` | The engine's id in logs and metrics |
| `streams.<name>.schema` | — | The stream, as `name:TYPE,…` (`?` for nullable) |
| `streams.<name>.event-time` | none | The event-time column. Without it no window over the stream could close, so a windowed query over it is refused (`PRV-2002`) |
| `streams.<name>.out-of-orderness` | the engine's | How late this stream's rows may be |
| `sources.<name>.plugin` / `.options` | — | A source binding: [Sources](/help/topics/sources-overview) |
| `lookups.<name>.plugin` / `.options` | — | A dimension table: [Lookups](/help/topics/lookups) |
| `sinks.<name>.plugin` / `.options` | — | A sink binding: [Sinks](/help/topics/sinks-overview) |
| `queries.<name>.sql` | — | A continuous query registered at start |
| `queries.<name>.keys` | — | Its key, by output column name |
| `queries.<name>.sink` | none | A bound sink its changes are also written to |
| `queries.<name>.retention` | forever | How long the view remembers, in event time |
| `registry.journal` | none (memory only) | A file: registrations come back after a restart |
| `checkpoint.directory` | none | A directory: each query's state is checkpointed under it |
| `checkpoint.interval` / `.keep` / `.timeout` | `1m` / `3` / `30s` | How often, how many to keep, how long one may take |
| `dlq.directory` | none | Where undecodable source records, and rows whose evaluation fails before state (`PRV-3027`), go instead of stopping the source or the query |
| `watermark.idle-after` / `.tick` | `30s` / `1s` | For queries fed by a bound source |
| `listener.max-pending` | `10000` | Commits a listener may have waiting before it is detached |
| `serving.read.max-concurrent` / `.max-queued` / `.queue-timeout` / `.tenant-share` / `.deadline` | `0` / `0` / `2s` / `1.0` / `0s` | Read admission and the read deadline for `query`, as on a node: [Configuration](/help/topics/configuration). Unset, every read is admitted; out of range fails the startup (`PRV-1026`) |

## A complete service

```java
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.ash.messaging.pravaha.spring.PravahaListener;
import com.ash.messaging.pravaha.spring.PravahaTemplate;

@Service
class RevenueBoard {

    record RegionRevenue(String region, java.time.Instant windowEnd, long revenue, long orders) {}

    private final PravahaTemplate pravaha;

    RevenueBoard(PravahaTemplate pravaha) {
        this.pravaha = pravaha;
    }

    /** Called by the order-intake path: one row per order, in the stream's column order. */
    void recordOrder(long orderId, String customer, String region, long amountCents) {
        pravaha.push("orders", new Object[] {
                orderId, customer, region, amountCents, "NEW", java.time.Instant.now()});
    }

    /** The current revenue per region, from the maintained view. */
    List<RegionRevenue> current() {
        return pravaha.query(RegionRevenue.class,
                "SELECT region, window_end, revenue, orders FROM region_revenue");
    }

    List<Map<String, Object>> forRegion(String region) {
        return pravaha.queryForList(
                "SELECT window_end, revenue FROM region_revenue WHERE region = ?", region);
    }

    /** Every committed change to the view, off the engine's thread. */
    @PravahaListener(query = "region_revenue")
    void onRevenue(RegionRevenue row, boolean retraction) {
        if (!retraction) {
            System.out.println(row.region() + " " + row.windowEnd() + " revenue " + row.revenue());
        }
    }
}
```

```text
EMEA 2026-09-19T09:35:00Z revenue 982300
APAC 2026-09-19T09:35:00Z revenue 611850
```

(Sample output, printed as each five-minute window closes.) The reads the service makes:

<!-- sql: read -->
```sql
SELECT region, window_end, revenue, orders FROM region_revenue
```

<!-- sql: read -->
```sql
SELECT window_end, revenue FROM region_revenue WHERE region = ?
```

### `PravahaTemplate`

| Method | |
|---|---|
| `push(stream, Object[]…)` / `push(stream, Map)` | Rows in, returning once every query reading the stream committed them |
| `query(sql, params…)` | SQL over the views, as a `ViewQuery.Result` |
| `query(Class<R>, sql, params…)` | Rows as records, matched by column name ignoring case and underscores (`window_end` → `windowEnd`) |
| `queryForList(sql, params…)` | Rows as `Map<String, Object>` |
| `register(name, sql, keyColumns…)` / `register(ContinuousQuery)` | Register at runtime — safe from a bean constructor, because the engine bean is already started |
| `subscribe(query, Consumer<RowChange>)` | A subscription you manage yourself |
| `pause`, `resume`, `drop` | Lifecycle |
| `engine()` | The `PravahaEngine` underneath |

### `@PravahaListener`

| Attribute | |
|---|---|
| `query` | The continuous query whose view to follow. Must exist when the context starts |
| `concurrency` | Threads delivering to this method (default `1`). Changes are routed by the view's key, so one key's changes stay in order |

Accepted method signatures:

| Signature | Receives |
|---|---|
| `void m(RowChange change)` | One change at a time |
| `void m(List<RowChange> commit)` | A whole commit |
| `void m(SomeRecord row, boolean retraction)` | One change, read into a record |
| `void m(Map<String, Object> row, boolean retraction)` | One change, as a map |

The `boolean` is **required** for the record and map forms: an update arrives as the old row withdrawn
and the new one added, in that order, and a listener that cannot tell them apart would apply the stale
row last.

## Customising or replacing the engine

A `PravahaEngineCustomizer` bean adjusts the auto-configured engine before it starts — the place to bind
a source or declare a stream in code:

```java
@Bean
PravahaEngineCustomizer paymentsStream() {
    return engine -> engine.declareStream("txn",
            "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP",
            "event_time");
}
```

Declare your own `PravahaEngine` bean and the starter steps aside entirely.

## What fails, and when

| Situation | Behaviour |
|---|---|
| A listener names a query that does not exist when the context starts | **Startup fails**, naming it |
| A listener's signature cannot be called with a change | **Startup fails** |
| A listener throws | Logged; the listener stays subscribed |
| A listener more than `max-pending` commits behind | **Detached** and logged, rather than handed a stream with a gap |
| A query in `queries.*` that the engine refuses (PRV-2050, PRV-2071, …) | Startup fails with the engine's refusal |
| The engine fails to start | Whatever it opened is closed, and the context fails |

## Pitfalls

!!! warning "Pitfall: a record that does not match the columns"
    Records are matched by column name. A component with no matching column — `total` where the view
    says `spend` — is refused with an `IllegalArgumentException` naming the component, as is a `null`
    column read into a primitive. Alias the column in the query or name the component after it.

!!! warning "Pitfall: pushing with the wall clock"
    `Instant.now()` as the event time makes event time equal arrival time, which is fine for a live
    intake path and wrong for a replay of historical orders — a replay must push each order's own time,
    or every replayed order lands in the current window.

!!! warning "Pitfall: expecting the server's security"
    The starter has no authentication, policy or audit trail. Everything inside your application may
    read and register anything. If other services need the answer, run `pravaha-server`.

!!! note "Not built yet"
    `@PravahaTest`, an actuator endpoint, a listener error handler, and testing against Spring Boot
    versions other than 3.5.

## Where next

- [The embedded engine](/help/topics/embedded-engine) — every call the template wraps.
- [Subscriptions](/help/topics/subscriptions) — whole commits and weights.
- [Checkpoints and recovery](/help/topics/checkpoints-recovery) — making a restart keep state.
- How it is built: [Architecture: the Spring Boot starter](/help/architecture-hosts#pravaha-spring-boot-starter)
