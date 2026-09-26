---
title: The Aerospike source
slug: source-aerospike
category: sources
order: 60
icon: hdd-network
summary: "Scans an Aerospike set for records updated since the last scan, with your WHERE pushed in as server-side expressions and only the bins you read fetched — one shared scan for every query over the set."
badge: SOURCE
audience: Operators
keywords: [aerospike, lut-scan, deletes, detect, PRV-2042, repeats rows, deletes.max.keys, deletes.state.dir, retraction, last update time, scan, set, namespace, bins, expressions, pushdown, projection, share.reader, shared reader, xdr, community edition, tls.name]
guide: continuous-queries#21-every-source-type-configured
related: [sources-overview, lookups, connector-security, sink-aerospike, sharing]
---

The `aerospike` plugin reads an Aerospike **set** as a stream by scanning it for records whose
last-update time is at or after the newest one the previous scan saw — a partition-parallel scan with
the filter `record.last_update_time() >= watermark` evaluated **on the server**. That is the difference
between reading a whole set every interval and reading what changed in it.

It is a scan because it has to be. Aerospike's change propagation, XDR, is an Enterprise feature;
Community Edition has no change feed at all. `lut-scan` is the one strategy that works on every
edition, and the plugin declares exactly what a scan can and cannot promise
([ADR-029](/help/decisions/029-aerospike-scan-only)).

## At a glance

| | |
|---|---|
| Plugin name | `aerospike` |
| Module | `plugins/pravaha-plugin-aerospike` (also holds `aerospike-lookup` and `aerospike-sink`) — in the server jar |
| Kind | stream source |
| Strategy | `lut-scan` — the only one implemented |
| Delivery guarantee | `AT_LEAST_ONCE` — a rescan from the watermark re-delivers the boundary records. `EXACTLY_ONCE` with `deletes: detect` |
| Replayable offsets | yes — the offset is a last-update-time watermark (with `deletes: detect`, a count of emitted rows backed by files) |
| Emits deletes / before-image | no / no — **yes / yes with `deletes: detect`**, [below](#seeing-deletes-deletes-detect) |
| Repeats rows | **yes** with `deletes: ignore` — an update is read again with nothing retracted, so an aggregate, a join or an append-only sink is refused (PRV-2042); no with `deletes: detect` |
| Pushdown | `FILTER` — translated into Aerospike expressions and added to the scan filter; `PROJECT` — the scan names only the bins read. Not `PARTIAL_AGGREGATE` |
| Shared between queries | **yes** — one reader per binding feeds every query over it; not with `deletes: detect` |
| Schema comes from | the `schema` option you write (bins have no declared types) |
| Partitions | `partitions` readers, each over a slice of Aerospike's 4,096 partitions |

## Options

| Option | Required | Default | What it does |
|---|---|---|---|
| `hosts` | yes | — | `host:port,host:port` seed nodes. With TLS on, usually port 4333 |
| `namespace` | yes | — | The namespace |
| `set` | yes | — | The set |
| `schema` | yes | — | `bin:TYPE,…`. Types: `BOOLEAN`, `INT8`, `INT16`, `INT32`, `INT64`, `FLOAT32`, `FLOAT64`, `STRING`, `BYTES`, `TIMESTAMP` (and `DECIMAL`); `?` for nullable |
| `event.time` | no | on a server, the stream's declared `event-time` | The bin holding each record's own event time, as an **integer of nanoseconds since the epoch**. Without it every record carries the time its scan started. Must name a column of `schema`, or PRV-5083 |
| `strategy` | no | `lut-scan` | `xdr-kafka`, `xdr-http` and `write-intercept` are named and refused with PRV-5083 — never silently replaced by a strategy with different delivery properties |
| `partitions` | no | `1` | Parallel scan readers, 1 to 4,096. Each takes `4096 / partitions` of the cluster's partitions |
| `scan.interval.ms` | no | `1000` | The pause between one scan finishing and the next starting. `0` scans back to back |
| `records.per.second` | no | `0` | A per-scan throttle; `0` is unthrottled |
| `scan.socket.timeout.ms` | no | `30000` | Socket timeout of each scan. Must be positive |
| `scan.total.timeout.ms` | no | `120000` | Total timeout of each scan. Must be positive — zero means wait for ever, and a scan that never returns takes its lane with it and looks exactly like a hung engine |
| `user` / `password` | no | empty | Aerospike security credentials |
| `stream` | no | the set name | The stream name the plugin reports |
| `deletes` | no | `ignore` | `detect` compares each full scan with every row already emitted and retracts what is gone — [below](#seeing-deletes-deletes-detect). Anything else is PRV-5083 |
| `deletes.state.dir` | with `detect` | — | Where each reader keeps the rows it has emitted, so a restore gets them back exactly. Durable local disk. Missing is PRV-5083 |
| `deletes.max.keys` | no | `1000000` | Rows one partition reader may remember before a pass is refused with PRV-5120 |
| `share.reader` | no | `true` | Read by the engine's binding layer: `false` gives each query its own scan with its own filter, instead of one shared scan pushing the `OR` of every query's filter |
| `tls.enabled`, `tls.name`, `tls.ca`, `tls.certificate`, `tls.key`, `tls.truststore`, `tls.keystore` (and their `.password` / `.type`), `tls.verify-hostname` | no | off | TLS — see below and [connector security](/help/topics/connector-security) |

## A complete binding

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  sources:
    txn:
      plugin: aerospike
      options:
        hosts: "as-1.internal:3000,as-2.internal:3000"
        namespace: payments
        set: transactions
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        strategy: lut-scan
        partitions: "8"
        scan.interval.ms: "1000"
        records.per.second: "0"
        user: pravaha
        password: "${AEROSPIKE_PASSWORD}"
```

Each record's bins are read by name into the columns of `schema`. A record's **user key is not a
bin**: if a query needs `txn_id`, the writer must store it in a bin as well as using it as the key.

## A query over it

Over the binding above — `deletes: ignore`, the default — the natural query keeps **current state**:
a keyed filter or projection, which a record read again only overwrites.

```sql
CREATE CONTINUOUS QUERY large_approved
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, amount
FROM txn
WHERE status = 'APPROVED' AND amount > 5000;
```

An aggregate over the stream needs `deletes: detect` on the binding
([below](#seeing-deletes-deletes-detect)). With `ignore` the scan **repeats rows** — an updated
record is read again as the new row with nothing retracting the old, and a record written while a
scan ran is read by the next scan too — so a `COUNT` or `SUM` would count it twice, and registration
refuses the query with **PRV-2042**, naming the binding and the fix. With `detect`, card-velocity
style — spend per user per minute:

```sql
CREATE CONTINUOUS QUERY spend_per_minute
    KEYED BY (user_id, window_end)
AS
SELECT user_id, window_start, window_end,
       COUNT(*)    AS txns,
       SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE status = 'APPROVED'
GROUP BY user_id, window_start, window_end;
```

<!-- sql: read -->
```sql
SELECT user_id, txns, spend FROM spend_per_minute WHERE spend > 5000
```

With three approved transactions for `u7` of 2,000, 1,800 and 2,400 inside one minute, once the
watermark passes the end of that minute:

```text
user_id	txns	spend
u7	3	6200
1 row
```

## Seeing deletes: `deletes: detect`

By default a deleted record is invisible: it is simply absent from the next scan, and a view keeps
serving it. With `deletes: detect` the source remembers every row it has emitted and compares each
**full** scan with them, keyed by the record's digest:

| The scan finds | Emitted |
|---|---|
| a record not emitted before | the row at `+1` |
| a record whose bins changed | the **whole old row** at `−1`, then the new one at `+1` |
| a record unchanged | nothing |
| no record where one was emitted | the whole old row at `−1`, with the event time it was inserted at |

A view over the set then equals the set after every pass — the same shape of changelog
[`postgres-cdc`](/help/topics/source-postgres-cdc) produces, from a store with no change feed — and
an aggregate or a join over the stream is admitted where `ignore` refuses it (PRV-2042).

```yaml
pravaha:
  sources:
    txn:
      plugin: aerospike
      options:
        hosts: "as-1.internal:3000,as-2.internal:3000"
        namespace: payments
        set: transactions
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,status:STRING?"
        deletes: detect
        deletes.state.dir: /opt/pravaha/data/scan-state
        deletes.max.keys: "2000000"
        scan.interval.ms: "30000"
```

What it costs, and what it promises:

- **Every pass reads the whole set.** A deleted record never matches the last-update-time filter,
  so `detect` drops it; your `WHERE` and the bins you read are still pushed to the server. Size
  `scan.interval.ms` to the set, not to its rate of change.
- **A delete is as timely as the next pass**: up to `scan.interval.ms` plus the scan's own time.
- **About 150 bytes of heap per record plus the row** (145 measured for a three-bin row). A million
  records is about 150 MB. `deletes.max.keys` bounds each partition reader and refuses a pass that
  would exceed it, before emitting any of it (PRV-5120), rather than forgetting records whose
  deletes could then never be seen.
- **A restart is exact.** The rows are logged under `deletes.state.dir`, forced to disk at every
  checkpoint, and a restore replays them to exactly the checkpoint's count before the next pass — so
  it retracts only what the restored view holds, mid-pass or between passes. Missing or damaged
  state is refused with PRV-5121, never guessed at.
- **`EXACTLY_ONCE`, with deletes and before-images** — and therefore not shared between queries:
  each query gets its own scan. A query over it may be refused an append-only sink (PRV-2041).
- **Two writes between passes are still one.** The before-image is the row the previous pass saw.
- **Changing `deletes` on an existing checkpoint is refused** (PRV-5083): drop and re-register.

## Pushdown

`FILTER` and `PROJECT`. The engine keeps its own filter on every row regardless, so what is pushed
changes how much leaves the cluster, never the answer.

### `FILTER` — server-side expressions

Each filter the engine can push — a conjunct comparing one column with a literal, directly above this
stream's scan — is translated into an Aerospike expression and `AND`ed into the scan's server-side
filter beside the last-update-time condition:

| Bin's declared type | Pushed as |
|---|---|
| `INT8` … `INT64`, `TIMESTAMP` | an integer-bin comparison: `=`, `<>`, `<`, `<=`, `>`, `>=` |
| `FLOAT32`, `FLOAT64` | a float-bin comparison |
| `STRING` | `=` and `<>` only — an ordered string comparison would depend on a collation neither side declares |
| `BOOLEAN` | `=` and `<>`, matching both a boolean bin and a legacy integer bin |
| `BYTES`, `DECIMAL` | not pushed |
| any | `IS NULL` as "the bin does not exist", `IS NOT NULL` as "it does" |

In `spend_per_minute` above, `status = 'APPROVED'` is pushed: the records of other statuses never leave
the cluster. Anything that cannot be translated **exactly** is left out of the expression rather than
approximated — Aerospike compares integer and string bins with different constructors, and the wrong
one does not fail, it returns nothing. An `OR`, a `NOT` or a `LIKE` written in a query's `WHERE` is
not pushed; it stays with the engine.

### `PROJECT` — only the bins you read

The scan names the bins the query uses, plus the `event.time` bin the reader stamps each row with; a
bin nobody reads is never sent. The engine works this out through projections and filters directly
over the scan: a query such as `SELECT txn_id, amount FROM txn WHERE status = 'APPROVED'` fetches
`txn_id`, `amount`, `status` and the event-time bin. A query that computes a column from others,
aggregates, joins or windows — `spend_per_minute` among them — asks for every bin.

### Not `PARTIAL_AGGREGATE`

Server-side aggregation in Aerospike means Lua stream UDFs registered on the cluster — a deployment
step a plugin cannot take for you — and a last-update-time scan sees an overwritten record as a new
row with no retraction of the old one, so a partial would be exactly as wrong as the rows are.

### A shared scan pushes the `OR` of its queries' filters

Several queries over one binding share one reader (below), and that reader asks the cluster for
**everything any of them would have asked for on its own**: the filters every query has in common as
plain conjuncts, and what remains of each query's filter as one alternative of an `OR` (`Exp.or` on the
server); the bins are the union of the bins each reads. Two queries filtering
`status = 'APPROVED' AND amount > 5000` and `status = 'APPROVED' AND merchant = 'TRAVELCO'` share a
scan that pushes `status = 'APPROVED' AND (amount > 5000 OR merchant = 'TRAVELCO')`.

The union is only ever wide enough, never exact — each query still applies its own filter to what
arrives. It narrows to the shared conjuncts alone when one query asks for nothing beyond them, and to
no filter at all when one query has none. Where a single query's narrow filter matters more than
sharing the scan, set `share.reader: "false"` on the binding.

## One scan for many queries

Without `deletes: detect`, Aerospike is a source the engine shares (Cassandra is the other): an at-least-once, unordered, replayable source can hand a
query that joins late a private catch-up read and then attach it to the running scan. One reader per
(binding, stream, partition) fans each decoded record into every subscribed query. Measured by the
project against a real cluster: **four queries over one set went from 3.8 scans a second to 1.0**
(finding SRC-3).

## Delivery guarantee

`AT_LEAST_ONCE` by default (`EXACTLY_ONCE` with `deletes: detect`, above). The offset is the newest
last-update time a *completed* scan saw — it advances only
after a scan finishes, so a scan that fails halfway never moves past records it did not deliver.
Rescanning from it re-delivers the records at the boundary, which is what at-least-once means.

## TLS

Aerospike does not check the server certificate against the host you dialled; it checks it against a
configured **TLS name** — the `tls-name` in the server's `aerospike.conf`, commonly not a hostname. So
TLS here takes two settings, and the second has no default that could be right:

```yaml
pravaha:
  sources:
    txn:
      plugin: aerospike
      options:
        hosts: "as-1.internal:4333,as-2.internal:4333"
        namespace: payments
        set: transactions
        schema: "txn_id:INT64,user_id:STRING,merchant:STRING,amount:INT64,currency:STRING,status:STRING?,event_time:TIMESTAMP"
        event.time: event_time
        tls.enabled: "true"
        tls.name: aerospike-cluster
        tls.ca: /opt/pravaha/conf/tls/aerospike-ca.pem
```

`tls.enabled` without `tls.name` is refused at configuration (PRV-5083) saying which half is missing.

## Pitfalls

!!! danger "Pitfall: by default, deletes are invisible"
    A deleted record is absent from the next scan, indistinguishable from one that never existed. A view
    over this source keeps serving deleted rows — unless the binding sets `deletes: detect`, which
    retracts them at the cost of a full scan each pass and the emitted rows held in memory. The other
    way out is to have the application write a tombstone — a status bin — rather than deleting, and
    filter on it.

!!! warning "Pitfall: two writes between scans are one"
    A record written twice inside `scan.interval.ms` is seen once, with the final value, and — unless
    `deletes: detect` is set — an update arrives as a new value with nothing retracted.

!!! warning "Pitfall: by default, the scan repeats rows"
    With `deletes: ignore` an updated record is read again at `+1`, and a record written while a scan
    ran is read by that scan and the next. A query that reads current state (a filter, a projection
    into a keyed view) is right — each copy overwrites its own key. What would count the copies is
    refused at registration with PRV-2042: an aggregate over the stream, a join, and a sink that
    cannot upsert by key. Set `deletes: detect` on the binding. A subscriber to a keyed view sees each
    copy as another `+1` of a row it already has: overwrite by key rather than summing weights.

    **Why `ignore` is still the default:** `detect` turns this incremental scan into a full scan of
    the set every interval, needs a durable `deletes.state.dir`, and holds every emitted record in
    memory.

!!! warning "Pitfall: `event.time` must be nanoseconds"
    The bin named by `event.time` is used as the record's event time **as stored**, in nanoseconds.
    Epoch milliseconds put every record in 1970. A record whose event-time bin is missing or not a
    number falls back to the scan's start time, never to zero.

!!! warning "Pitfall: a containerised cluster needs host networking"
    An Aerospike client routes every operation by the cluster's own partition map, and a node in a
    bridged container advertises the address it has inside the container. Through a mapped port the
    connection succeeds and every scan then hangs. Run the cluster with host networking.

!!! note "Mind the interval on a busy cluster"
    Before `scan.interval.ms` existed, scans ran back to back; one query took a Community node from 1%
    to 200–310% CPU. The one-second default is deliberate. Setting `0` brings the hot loop back.

## Where next

- [Aerospike lookups](/help/topics/lookups) — enriching a stream from a set, as of each row's time
- [The Aerospike sink](/help/topics/sink-aerospike) — upserting a view's answer into a set
- [Connector security](/help/topics/connector-security) — every `tls.*` option and what it verifies
- [Sources overview](/help/topics/sources-overview) — scanning against polling against logs
