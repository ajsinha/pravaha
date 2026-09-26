---
title: The Aerospike sink
slug: sink-aerospike
category: sinks
order: 40
icon: hdd-stack
summary: "aerospike-sink upserts each row into a set by the view's key and deletes it on a retraction — effectively once, because a replay rewrites records with the values they already hold."
badge: SINK
audience: Engineers
keywords: [aerospike-sink, aerospike, upsert, idempotent, effectively once, key.bins, ttl.seconds, set, namespace, bins]
guide: operations#one-engine-and-what-the-server-still-lacks
related: [sinks-overview, delivery-guarantees, source-aerospike, connector-security]
---

`aerospike-sink` writes a query's answer back into Aerospike as one record per view row, keyed by
the view's key. A row the query commits **replaces** the record under its key; a retraction
**deletes** it. The set therefore holds the view: current per-user totals, per-window counts, the
latest state of something — served from a store your applications can already read at
sub-millisecond latency.

It is not transactional, and it does not need to be. Because every write is a keyed replace, a write
repeated after a restart puts the same value under the same key: the end state is the same whether
the write happened once or three times. Pravaha calls that **effectively once**.

## At a glance

| | |
|---|---|
| Plugin name | `aerospike-sink` (under `pravaha.sinks.<name>`) |
| Ships in | the `pravaha-plugin-aerospike` jar, with the `aerospike` source and `aerospike-lookup` |
| Accepts | `UPSERT`, `RETRACT` — any query, including one that revises its answer |
| Keyed | yes, by `key.bins`, which must be the view's key |
| Transactional / idempotent | no / **yes** |
| Delivery | **effectively once** — a replay rewrites records with the values they already hold |
| Rows per batch | up to 512 |
| TLS | the shared `tls.*` options, plus `tls.name` |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `hosts` | yes | — | `host:port,host:port` seed nodes |
| `namespace` | yes | — | The Aerospike namespace written to |
| `set` | yes | — | The set written to |
| `schema` | yes | — | The row shape, `name:TYPE,...`. Must match the registered query's `SELECT` list in order, name and type (PRV-8010). Column names become bin names |
| `key.bins` | yes (or `key.bin`) | — | Comma-separated key columns — the registration's key. Integer, string, bytes, date, time or timestamp columns only |
| `key.bin` | — | — | The single-column spelling of `key.bins`. Giving both is refused |
| `ttl.seconds` | no | `0` | Each record's time to live. `0` means the namespace's default. Negative is refused |
| `user` / `password` | no | empty | Credentials, for a cluster with security enabled |
| `stream` | no | the set name | The name the schema is registered under inside the plugin |
| `tls.enabled`, `tls.ca`, `tls.certificate`, `tls.key`, `tls.truststore`, `tls.keystore` (and their `.password` / `.type`), `tls.verify-hostname` | no | off | The shared connector TLS options — see [connector security](/help/topics/connector-security) |
| `tls.name` | with TLS | — | The `tls-name` from the server's `aerospike.conf`, which Aerospike checks the certificate against |

## How a row becomes a record

**One key column** — the record's key *is* that value, readable by any client as itself (an integer
or a string). It is **not** also written as a bin: that would duplicate it on every record.

**Several key columns** — one blob key, an injective encoding of the typed values (so `("ab","c")`
and `("a","bc")` are different records). A blob cannot be read back into its parts, so every key
column is **also** written as a bin.

Every other column is a bin named after it:

| Column type | Bin |
|---|---|
| `INT8` ... `INT64`, `DATE` | integer |
| `TIMESTAMP`, `TIME` | integer: nanoseconds since the epoch / since midnight |
| `FLOAT32`, `FLOAT64` | double |
| `BOOLEAN` | boolean |
| `STRING` | string |
| `BYTES` | blob |
| NULL | the bin is **removed** — Aerospike's way of saying "no value" |

A write replaces the whole record (`RecordExistsAction.REPLACE`), so a bin the query no longer
produces does not linger.

## A complete example: per-user hourly spend, served from Aerospike

```yaml
pravaha:
  checkpoint:
    directory: /opt/pravaha/data/checkpoints
    interval: 1m
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  sources:
    txn:
      plugin: filesystem
      options:
        path: /opt/pravaha/data/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        skip.header: "true"
        follow: "true"
  sinks:
    spend_by_user:
      plugin: aerospike-sink
      options:
        hosts: "aerospike-1:3000,aerospike-2:3000"
        namespace: analytics
        set: spend_by_user
        schema: "user_id:STRING,window_end:TIMESTAMP,spend:INT64,payments:INT64"
        key.bins: "user_id,window_end"
        ttl.seconds: "604800"
```

```sql
CREATE CONTINUOUS QUERY spend_by_user
    KEYED BY (user_id, window_end)
    WRITING TO spend_by_user
AS
SELECT user_id, window_end, SUM(amount) AS spend, COUNT(*) AS payments
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

The registration logs:

```text
query 'spend_by_user' writes to sink 'spend_by_user', effectively-once: the sink upserts idempotently, so what a restart delivers again rewrites records with the values they already hold
```

With the key on two columns, each record carries all four bins. Read in `aql` (a scan, so the key
itself — a blob — is not shown):

```text
aql> SELECT * FROM analytics.spend_by_user
+---------+---------------------+-------+----------+
| user_id | window_end          | spend | payments |
+---------+---------------------+-------+----------+
| "ann"   | 1789812000000000000 | 2050  | 2        |
| "bob"   | 1789812000000000000 | 4200  | 1        |
+---------+---------------------+-------+----------+
```

(Illustrative: two users' payments in the hour ending 10:00 UTC.) Each record expires seven days
after it was last written, so old windows clean themselves up with no job to run.

The view keeps its own copy and answers point reads directly:

<!-- sql: read -->
```sql
SELECT user_id, window_end, spend, payments FROM spend_by_user WHERE user_id = 'ann'
```

## A single-column key: the latest state per user

With one key column the record key is the value itself — the natural shape for a lookup table
another service reads with a single `get`:

```yaml
pravaha:
  sinks:
    big_spenders:
      plugin: aerospike-sink
      options:
        hosts: "aerospike-1:3000"
        namespace: analytics
        set: big_spenders
        schema: "txn_id:INT64,user_id:STRING,amount:INT64"
        key.bin: txn_id
```

```sql
CREATE CONTINUOUS QUERY big_spenders
    KEYED BY (txn_id)
    WRITING TO big_spenders
AS
SELECT txn_id, user_id, amount FROM txn WHERE amount > 1000;
```

Each record's key is the integer `txn_id`, and its bins are `user_id` and `amount`.

## Delivery, and why no transaction is needed

A restart resumes from the last checkpoint and replays what came after it. Every replayed upsert
lands on the key it landed on before, with the value it had before; every replayed delete deletes
what was already gone. The set converges to the view. What a reader *can* observe during the replay
is a record briefly holding an older value it held before, until the replay catches up — the writes
are not atomic across records.

`GET /api/v1/sinks` reports this sink's `guarantee` as `EFFECTIVELY_ONCE`, as the registration's log
line does. (`GET /api/v1/plugins` shows the plugin's own declared guarantee, which is the SPI's word
for an idempotent upsert, `EXACTLY_ONCE`.) See [delivery guarantees](/help/topics/delivery-guarantees).

## When a write fails

A put or delete the cluster refuses is PRV-5081 (naming the namespace and set); a connection that
cannot be made when the sink opens is PRV-5080; a configuration the plugin refuses is PRV-5083. A
refused write detaches the sink with PRV-8009 — the query and its view carry on, and nothing more is
written. Drop and re-register the query to start the sink again from the view's contents, which the
upsert makes harmless.

## Pitfalls

!!! warning "Pitfall: `key.bins` must be the view's key, exactly"
    The registration is refused with PRV-8010 when `key.bins` and the query's `KEYED BY` differ. On
    fewer columns, two view rows would share one record and deleting one would delete the other; on
    more, a changed row would leave its old record behind.

!!! warning "Pitfall: no floating-point or boolean key"
    A key column of type `FLOAT64` or `BOOLEAN` is refused at configuration (PRV-5083): two values
    that compare equal would be two records.

!!! warning "Pitfall: bin names are column names"
    Aerospike limits bin names to 15 characters. A `SELECT` alias longer than that becomes a bin the
    server refuses. Alias columns to short names.

!!! note "TLS needs `tls.name`"
    Aerospike checks the server certificate against the configured `tls-name`, not against the host
    dialled. Setting `tls.enabled` without `tls.name` is refused at configuration. A TLS-enabled
    cluster usually listens on 4333, not 3000.

## Where next

- [Delivery guarantees](/help/topics/delivery-guarantees)
- [How a query writes to a sink](/help/topics/sinks-overview)
- [The Aerospike source](/help/topics/source-aerospike) — reading a set, by last-update time
- [Connector security](/help/topics/connector-security) — `tls.*` and credentials
