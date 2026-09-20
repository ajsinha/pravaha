# Streams, continuous queries and SQL

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../LICENSE`](../LICENSE).

> **The single source of truth for what you write and what happens when you write it.** A stream
> becomes a query becomes a view (§1–§9), and every SQL construct that runs or is refused is listed
> with its reason (§10–§19). [`CONCEPTS.md`](CONCEPTS.md) holds the ideas underneath;
> [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) holds how a query executes once registered. Neither is
> repeated here.

Every construct in the reference half is **checked by a test**, not by someone's memory:
[`SqlSupportMatrixTest`](../pravaha-sql/src/test/java/com/ash/messaging/pravaha/sql/plan/SqlSupportMatrixTest.java)
plans each statement on this page, builds it, **and compiles it into a runnable pipeline**. If a
construct starts working, or stops, the build fails and names this file.

That third step is there because the first version of this page got two rows wrong without it.
Planning a statement and being able to run it are different things — a self-join plans perfectly and
is refused when the pipeline is built — so a matrix that stopped at the planner reported it as
supported and this page repeated the claim.

---

# Part I — a stream becomes a query becomes a view

## 1. The three nouns

| | What it is | Lives for |
|---|---|---|
| **Stream** | A named, typed, unbounded sequence of rows, bound to a source | The node's lifetime |
| **Continuous query** | A registered computation over one or more streams | Until dropped |
| **View** | The query's answer, maintained incrementally, readable by SQL | As long as its query |

The relationship in one line:

```
source ──feeds──► stream ──read by──► continuous query ──maintains──► view ──read by──► SELECT
```

**A continuous query is not a request.** You do not ask it for an answer; you register it once and it
keeps one. The answer is the view, and reading the view is a different operation entirely — cheap,
repeatable, and not the query running again.

---

## 2. Declaring a stream

A node is described by three blocks, and they are separate on purpose.

| Block | Says | Needed for |
|---|---|---|
| `pravaha.streams.<name>` | What the stream **is** — schema, event time, lateness | Planning. A query can be written and validated against a stream with nothing attached to it |
| `pravaha.sources.<name>` | Where its rows **come from** — a plugin and its options | Running. The key is the stream name it feeds |
| `pravaha.lookups.<name>` | A dimension table a query may **ask** | Temporal joins (§7). A source is consumed and advances event time; a lookup is only asked |

```yaml
pravaha:
  streams:
    txn:
      schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 10s
  sources:
    txn:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"
        event.time: event_time
```

**Note the `options:` nesting.** Plugin options live under `options`, not directly under the source.
A key written one level too high is not read, is not reported, and the node starts and ingests
nothing.

**And note the schema is written twice** — once under `streams` for the catalogue to plan against,
once under the source's `options` for the plugin to parse rows with. That is a wart, not a design:
the two are read by different components that do not share a parser today. They must agree.

**`event-time` is the setting people most often omit, and its absence used to be silent.** Without
it no watermark advances, so no window ever closes: a windowed query used to plan, register, report
`RUNNING`, ingest every row and emit nothing, for ever. The `name:TYPE` grammar has no syntax for
marking a column, so this key is the only way to say it.

**A windowed query over a stream that declares no event time is now refused when it is
registered** (TIME-6), because it could never have answered:

```text
PRV-2002  TUMBLE is given DESCRIPTOR(event_time), but 'txn' declares no event-time column -- so no
          watermark advances over it and no window this query opens can ever close. It would
          register, report RUNNING, ingest every row and emit nothing, for ever.
            Declare the column: pravaha.streams.txn.event-time: event_time, or 'eventTime' on
            POST /api/v1/streams. The column must be a TIMESTAMP.
          Refused at registration rather than discovered from an empty view later.
```

This is a query that planned before and does not plan now. Declaring the column is the fix; there
is no flag to turn the refusal off, because there is no configuration in which the refused query
does something useful. It is only for an unbounded input: a **bounded** read fires its windows from
the end of the scan rather than from a watermark, so the same SQL over a finite input still
answers, and is still accepted.

**The node also states what is in force, one line per stream, at startup** (TIME-6):

```
stream txn: event-time=event_time, out-of-orderness=PT10S, allowed-lateness=PT0S
stream ref: event-time=none -- no window over this stream can ever close
```

Read those first when a windowed query is `RUNNING` with a climbing `ROWS IN` and an empty view.
Between them the refusal and the startup line cover all four ways to get there: the refusal takes
the two that are decidable at plan time — a missing or blank `event-time` — and the line covers the
other two, because an out-of-orderness larger than the data's span is a legitimate setting that
simply leaves the view empty and nothing could refuse it. Before this, `grep -icE "out-of-orderness"`
over a whole startup log was 0 on every configuration tried.

A stream declared over HTTP says the same two things in its body —
`{"name": "txn", "schema": "...", "eventTime": "event_time", "outOfOrderness": "PT10S"}` to
`POST /api/v1/streams` — and `GET /api/v1/streams` reports each stream's `eventTime`,
`outOfOrderness` and the `source` plugin feeding it (the plugin's name only, never its options). An
out-of-orderness without an event time is refused: it is how late an event time may be, and there is
none for it to be about.

**A unitless number is seconds.** `out-of-orderness: 60` is a minute and `allowed-lateness: 30` is
thirty seconds. They used to bind as *milliseconds*, so `60` produced a view identical to the
ten-second default and the mistake was invisible (TIME-3); `60s`, `PT1M` and `60ms` are unchanged.

**`out-of-orderness` belongs to the stream, not to the node.** It says how late *this* source's rows
may arrive. A join across two streams takes the minimum of their watermarks — so a query is only as
current as its laggiest input, which is correct and surprises people.

### 2.1 Every source type, configured

Eight stream sources ship today. Each is a plugin discovered by `ServiceLoader`, so the set grows
without the engine changing — [`CONNECTORS.md`](CONNECTORS.md) is how you add one.

**Only `filesystem` is inside the server jar.** `feedfile`, `jdbc`, `delta`, `aerospike`,
`cassandra`, `postgres-cdc` and `kafka` are separate modules, and adding one to a deployment means dropping a jar on the
classpath rather than rebuilding the server — which is why a server that only reads a directory does
not carry Hadoop and Parquet.

Discovery happens when a query is first registered against the stream, **not at startup**, so a
binding naming a plugin that is not on the classpath starts a server cleanly and fails at the
registration that needs it — with a message listing the plugin names that *are* available. A stream
with no binding at all is not an error either: it registers, runs, and reports that nothing is
attached, because an embedder pushing rows in directly is a legitimate way to feed a query.

#### `filesystem` — one file, optionally followed

A single delimited file. The simplest source, and the one the examples use.

```yaml
pravaha:
  sources:
    txn:
      plugin: filesystem
      options:
        path: /var/lib/pravaha/incoming/txn.csv
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"
        event.time: event_time
        skip.header: "true"
        follow: "true"          # keep reading as the file grows
        op.column: op           # optional: a column saying insert or retract
        op.delete.values: "D,DELETE,-,-1"
```

| Option | Required | Default |
|---|---|---|
| `path` | yes | — |
| `schema` | yes | — |
| `event.time` | no | none — and then no window ever closes |
| `delimiter` | no | `,` |
| `skip.header` | no | `false` |
| `null.literal` | no | `""` |
| `follow` | no | `false` — read once and stop |
| `op.column` | no | none, so every row is an insertion |
| `op.delete.values` | no | `D,DELETE,-,-1` |

`op.column` is the one worth knowing about: without it a file is an append-only log of insertions,
and the engine's whole retraction model has no way in from a configured source. With it, a file can
carry deletes — which is what makes a CSV a legitimate Z-set source (§6).

#### `feedfile` — a directory of files arriving over time

The batch-feed shape: files land in a directory, each is read once, then archived. Handles partial
writes, which is what separates it from pointing `filesystem` at a directory.

```yaml
pravaha:
  sources:
    eod_positions:
      plugin: feedfile
      options:
        dir: /var/feeds/positions
        glob: "positions-*.csv"
        format: csv                       # or parquet
        schema: "account:STRING,symbol:STRING,qty:INT64,as_of:TIMESTAMP"
        completion: stable                # or marker
        completion.quiet.ms: "5000"
        order: name                       # or mtime
        archive.dir: /var/feeds/done
        quarantine.dir: /var/feeds/bad
        skip.header: "true"
```

| Option | Required | Default |
|---|---|---|
| `dir` | yes | — |
| `schema` | yes | — |
| `glob` | no | `*.csv` |
| `format` | no | inferred from the glob (`parquet` if it ends `.parquet`, else `csv`) |
| `stream` | no | the binding's name |
| `completion` | no | `stable` — a file is ready when it stops changing for `completion.quiet.ms` |
| `completion.marker.suffix` | no | `.done` — used when `completion: marker` |
| `completion.quiet.ms` | no | `5000` |
| `order` | no | `name` |
| `archive.dir` / `quarantine.dir` | no | none — files stay put |

**`completion` is the setting that matters.** A producer writing a 200 MB file is a producer whose
file is incomplete for several seconds, and reading it early yields a truncated row rather than an
error. `stable` waits for quiet; `marker` waits for a sentinel file the producer writes last, which
is the only one of the two that is actually safe if the producer can stall mid-write.

#### `delta` — a Delta Lake table

Reads a Delta table and follows its commits. Schema comes from the table, so there is no `schema`
option to keep in step.

```yaml
pravaha:
  sources:
    trades:
      plugin: delta
      options:
        path: /warehouse/trades
        start.version: "142"    # optional; default is the latest snapshot
```

| Option | Required | Default |
|---|---|---|
| `path` | yes | — |
| `stream` | no | the table directory's name |
| `start.version` | no | the latest snapshot — so history before it is not replayed |

#### `jdbc` — a relational table or an arbitrary `SELECT`

Polls a database, advancing on a monotonic column.

```yaml
pravaha:
  sources:
    orders:
      plugin: jdbc
      options:
        url: "jdbc:postgresql://db-1:5432/sales"
        user: pravaha
        password: "${PRAVAHA_DB_PASSWORD}"
        table: orders                  # exactly one of table or query
        watermark.column: updated_at
        key.column: order_id
        fetch.size: "500"
        page.clause: "LIMIT ?"         # dialect-specific
```

| Option | Required | Default |
|---|---|---|
| `url` | yes | — |
| `watermark.column` | yes | — |
| `table` **or** `query` | exactly one | — |
| `key.column` | no | none |
| `user` / `password` | no | empty |
| `fetch.size` | no | `500` |
| `page.clause` | no | `LIMIT ?` — change it for a dialect that spells paging differently |
| `stream` | no | the table name, or the binding's name when `query` is used |
| `pushdown.partial.aggregate` | no | `true` — has effect only with `key.column`; see below |
| `collation.binary` | no | `false` — set it only if the database compares and groups text byte for byte, case-sensitively |
| `watermark.moves.on.update` | no | `true` — set it `false` only when the watermark column is written once, on insert, and never by an update; with `key.column`, the source then declares that it never repeats a row, and an aggregate over it is admitted ([below](#a-source-that-repeats-rows-what-the-registry-refuses-prv-2042)) |

Giving both `table` and `query`, or neither, is refused at configuration with a message saying which
does what. Use `query` when a cast or a join has to happen in the database rather than here.

##### Polling an arbitrary `SELECT`

`query` polls any statement, which is where a join or a cast belongs when it is cheaper in the
database than here:

```yaml
pravaha:
  streams:
    enriched_orders:
      schema: "order_id:INT64,customer_tier:STRING,amount_cents:INT64,updated_at:TIMESTAMP"
      event-time: updated_at
      out-of-orderness: 30s
  sources:
    enriched_orders:
      plugin: jdbc
      options:
        url: "jdbc:postgresql://db-1:5432/sales"
        user: pravaha
        password: "${PRAVAHA_DB_PASSWORD}"
        # Joined and cast server-side. The engine refuses DECIMAL arithmetic (§16), so the
        # conversion to integer cents happens where the decimal already lives.
        query: >
          SELECT o.order_id,
                 c.tier                          AS customer_tier,
                 CAST(o.amount * 100 AS BIGINT)  AS amount_cents,
                 o.updated_at
          FROM orders o
          JOIN customers c ON c.customer_id = o.customer_id
        watermark.column: updated_at
        key.column: order_id
        page.clause: "LIMIT ?"
```

The statement is wrapped as a derived table, so the watermark predicate, the ordering and the paging
clause are applied *around* it — which means `watermark.column` and `key.column` must name columns
the statement actually projects, as `updated_at` and `order_id` do above.

`page.clause` is the one dialect-specific option. `LIMIT ?` suits Postgres, MySQL and H2;
`FETCH FIRST ? ROWS ONLY` suits Oracle and Db2; `OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY` suits SQL
Server.

##### What the database does for the query

A query's `WHERE` clause becomes the poll's `WHERE` (values bound, never spliced), and the columns it
reads become the `SELECT` list — plus the watermark and key columns, which the poller orders and
resumes by. Both are safe to offer blindly: the engine keeps its own filter, and never reads a column
it did not ask for.

With `key.column` set, a continuous `SELECT COUNT(*), SUM(x) FROM t WHERE ...` goes further: each
poll asks the database for one **partial** per keyset page — `COUNT(*)`/`SUM` over the rows after
the last offset and up to the page's last row — instead of the rows themselves, and the engine adds
the partials into its running total (ADR-039 item 6). The answer is the one the rows give: a page's
partial is the sum of exactly the rows the row poll would have sent. That is why the aggregate is
registered only when no row is read twice — `key.column` set and `watermark.moves.on.update: false`,
the watermark written once, on insert — and refused with `PRV-2042` otherwise, since an update the
poll sees again would be counted again either way (below). It is
asked for only when every predicate in the `WHERE` can be carried into SQL, only for `COUNT` and for
`SUM` over a `BIGINT`, and — unless `collation.binary: true` — never when a text column is compared
or grouped on, because a case-insensitive collation would match `'done'` to `'DONE'` where the engine
does not, and a partial leaves nothing downstream to notice. Anything else is read as rows. An
unwindowed `GROUP BY` cannot be registered at all (PRV-2050), so a registered query's partial is the
global one; a windowed aggregate is not pre-combined. `pushdown.partial.aggregate: false` turns it off.

##### Choosing the watermark column — the part that goes wrong

Each poll asks for rows beyond the highest watermark value it has already seen. Three ways that
misses rows, all of them silent:

- **The column must never go backwards.** An `updated_at` written from an application clock moves
  backwards on clock skew, and a backdated correction lands below the watermark. Those rows are not
  late — they are **never seen**. A database-assigned `now()` or a monotonic sequence is safer than
  anything the application supplies.
- **Commits can become visible out of order.** A row written inside a long transaction takes its
  timestamp when the statement runs and becomes visible when the transaction commits — which may be
  after the poller has already moved past that value. This is the classic polling defect and no
  amount of care inside the connector fixes it; the remedy is to poll only up to a little behind
  now, trading latency for completeness, or to advance on a commit-ordered column rather than a
  clock.
- **Ties at the boundary.** Many rows can share one `updated_at`. `key.column` is what makes the
  order total — it is appended to the `ORDER BY` after the watermark — so paging cannot cut a group
  of equal timestamps in half. Set it to something unique whenever the watermark is not.

And index the watermark column. Every poll orders by it, so without an index each poll is a full
scan of the table.

##### What a polled source cannot do

**A polled table is not a changelog.** It sees a row's current value at poll time, so a row that
changes twice between polls yields one row, and a deleted row is simply never seen again — no
retraction is produced. A row whose update moves the watermark column is read again, as the new
value at `+1` with the old one still counted, so the source declares that it repeats rows and an
aggregate, a join or an append-only sink over it is refused (`PRV-2042`) — unless `key.column` is
set and `watermark.moves.on.update: false` vouches that the column is written only on insert.

**And it cannot produce one even if you ask.** `JdbcPartitionReader` writes `weight(1)` on every
row: the `jdbc` source has no equivalent of the `filesystem` source's `op.column`, so a table with a
soft-delete flag cannot turn that flag into a `−1` today. The flag arrives as an ordinary column and
a query can filter on it, but the already-counted row is not withdrawn. Where deletes must reduce a
total, CDC is the right shape — `postgres-cdc` below — and the choice between the two is set out in
[`CONNECTORS.md`](CONNECTORS.md) §5.

#### `aerospike` — a set, scanned by last-update time

```yaml
pravaha:
  sources:
    txn:
      plugin: aerospike
      options:
        hosts: "as-1:3000,as-2:3000"
        namespace: prod
        set: transactions
        schema: "txn_id:INT64,user_id:STRING,amount:INT64,event_time:TIMESTAMP"
        event.time: event_time
        strategy: lut-scan            # the only implemented strategy
        partitions: "8"
        scan.interval.ms: "1000"
        records.per.second: "0"       # 0 = unthrottled
```

| Option | Required | Default |
|---|---|---|
| `hosts` | yes | — |
| `namespace` | yes | — |
| `set` | yes | — |
| `schema` | yes | — |
| `stream` | no | the set name |
| `event.time` | no | none |
| `strategy` | no | `lut-scan` |
| `partitions` | no | `1` |
| `records.per.second` | no | `0`, meaning unthrottled |
| `scan.interval.ms` | no | `1000` |
| `scan.socket.timeout.ms` / `scan.total.timeout.ms` | no | `30000` / `120000` |
| `user` / `password` | no | empty |
| `deletes` | no | `ignore`; `detect` retracts records that are gone — [below](#seeing-deletes-in-a-scan-deletes-detect) |
| `deletes.state.dir` | with `detect` | — |
| `deletes.max.keys` | no | `1000000` per partition |

`strategy` declares four values and **implements one**. `lut-scan` is a partition-parallel scan
filtered on each record's last-update time; `xdr-kafka`, `xdr-http` and `write-intercept` are named
in the enum and refused at configuration if you ask for them, which is better than a silent fallback
to a strategy with different delivery properties.

Several queries over the same Aerospike set share one scan rather than each opening their own
(except under `deletes: detect`, below) —
four queries over one set measured 3.8 → 1.0 scans per second (SRC-3). The `WHERE` clause is a
server-side filter expression and the columns a query reads are the bins the scan names, so the
server sends only those. A shared scan pushes the **OR** of its queries' filters and the union of
their columns, and each query keeps its own filter in the engine (ADR-039 item 6). No partial
aggregate: Aerospike aggregates server-side only through Lua stream UDFs registered on the cluster,
and an overwritten record arrives as a new row with no retraction whichever way it is summed.

With the default `deletes: ignore` the scan **repeats rows**: an updated record is read again as the
new row with nothing retracting the old, and the filter is greater-or-equal on when the previous scan
started, so a record written while a scan ran is read by it and again by the next. A keyed view of
the records is right regardless; an aggregate, a join or an append-only sink over the stream is
refused with `PRV-2042` and needs `deletes: detect` ([below](#a-source-that-repeats-rows-what-the-registry-refuses-prv-2042)).

#### `cassandra` — a table, scanned by `token()` range

```yaml
pravaha:
  sources:
    orders:
      plugin: cassandra
      options:
        contact.points: "cass-1:9042,cass-2:9042"
        local.datacenter: dc1
        keyspace: prod
        table: orders
        schema: "id:INT64,status:STRING,amount:INT64,updated_at:TIMESTAMP"
        partition.key: id
        event.time: updated_at
        strategy: token-range-scan   # the only implemented strategy
        partitions: "8"
        scan.interval.ms: "60000"
        fetch.size: "5000"
        consistency.level: LOCAL_ONE
```

| Option | Required | Default |
|---|---|---|
| `contact.points` | yes | — |
| `keyspace` | yes | — |
| `table` | yes | — |
| `schema` | yes | — |
| `partition.key` | yes | — |
| `stream` | no | the table name |
| `local.datacenter` | no | auto-detected — name it if the cluster has more than one datacenter |
| `event.time` | no | none |
| `strategy` | no | `token-range-scan` |
| `partitions` | no | `1` |
| `scan.interval.ms` | no | `60000` — ten times the Aerospike plugin's default, because this scan is not incremental |
| `fetch.size` | no | `5000` |
| `consistency.level` | no | `LOCAL_ONE` |
| `request.timeout.ms` | no | `30000` |
| `user` / `password` | no | empty |
| `deletes` | no | `ignore`; `detect` retracts rows that are gone — [below](#seeing-deletes-in-a-scan-deletes-detect) |
| `deletes.state.dir` | with `detect` | — |
| `deletes.max.keys` | no | `1000000` per token range |

**This is a full scan, not an incremental one, and that is a deliberate choice rather than a
shortcut.** Cassandra's CDC writes commitlog segments to `cdc_raw` on every node, meant to be read
locally — a per-node agent with no ordering across nodes, which is a different project from a
connector ([`CONNECTORS.md`](CONNECTORS.md) section 5). And unlike Aerospike's `record.last_update_time()`,
CQL's `writetime()` cannot filter server-side without `ALLOW FILTERING` — which reads every partition
anyway — and is tracked per *column* rather than per row, so a key-only write moves no watermark at
all. `strategy` therefore declares `writetime-incremental` and `commitlog-cdc` and implements
neither, refusing both at configuration rather than silently downgrading: **a full periodic scan that
says what it is beats an incremental one that quietly misses rows.**

`partition.key` names the table's partition-key columns, in CQL's own order, so this plugin can page
by `token()` instead of reading through `ALLOW FILTERING`. The columns a query reads become the CQL
`SELECT` list (ADR-039 item 6); its `WHERE` clause stays in the engine, because a predicate on
anything but the partition key needs `ALLOW FILTERING`. Every pass reads the whole range assigned
to each of the `partitions` readers, then waits out `scan.interval.ms` before reading it again — so a
short interval on a large table is a scan that never stops running.

##### What a table scan cannot do

The same limits [`CONNECTORS.md`](CONNECTORS.md) documents for the Aerospike `lut-scan`, for the same
reason: scanning a store with no change feed. With the default `deletes: ignore`, **deletes are
invisible** — a tombstoned row is simply absent from the next scan, indistinguishable from one that
never existed — and **every pass adds every row again at `+1`**. **Intra-interval overwrites
collapse** — two writes between passes are seen as one, with only the final value. **There is no
before-image**, so an update arrives as an insert of the new value with nothing to retract.
`capabilities()` declares `emitsDeletes = false`, `emitsBeforeImage = false`,
`DeliveryGuarantee.AT_LEAST_ONCE` and `repeatsRows = true`. `deletes: detect`, next, removes all but
the collapsing.

##### A source that repeats rows: what the registry refuses (`PRV-2042`)

A source **repeats rows** when, in normal running, it delivers a row it has already delivered with
nothing retracting the earlier copy. Four configurations do, and each says so in its capabilities
(`repeatsRows`), which the registry reads from the configured plugin without opening it:

| Source | Repeats | Why |
|---|---|---|
| `cassandra`, `deletes: ignore` (the default) | yes | every pass emits every row again |
| `aerospike`, `deletes: ignore` (the default) | yes | an updated record is read again as the new row; a record written while a scan ran is read by that scan and the next, because the next filters from when this one started |
| `jdbc` | yes, unless `key.column` is set **and** `watermark.moves.on.update: false` | an update that moves the watermark column brings the row back; without a key, rows tied on the watermark are resumed by counting and can be re-read |
| `cassandra` or `aerospike` with `deletes: detect`, `postgres-cdc`, `kafka`, `delta`, `feedfile`, `filesystem` | no | a changelog, a log read once by offset, or files read once |

Every copy arrives at weight `+1`, and nothing downstream can tell a copy from a new row. So over a
stream whose source repeats, registration refuses, with `PRV-2042` naming the stream, the plugin and
the fix, before a feed or a sink opens:

- **any aggregate**, windowed or not — `COUNT(*)` over a Cassandra table would grow by the table's size
  every interval. `MIN`, `MAX`, `COUNT(DISTINCT)` and `DISTINCT` are refused too: they survive a copy
  of an unchanged row, but an update is never retracted from them either;
- **a join**, which pairs every copy again, and whose pair a copy keeps alive after the other side
  retracts it;
- **a sink that cannot upsert by key** — `filesystem`, `jdbc-sink` with `mode: append`, `kafka-sink`
  with `format: changelog` — which writes every copy as another row or event.

**A projection or filter is admitted**, computed columns and a lookup join included, served as a keyed
view or written to a sink that upserts by key. The served view is keyed by the registration's key
columns: a copy raises its key's weight and overwrites the row with the values it already has, so a
keyed read or a scan returns each row once, as the store holds it — and a source that repeats never
retracts, so a weight above one is never walked back past a row that should still be there. A
subscriber sees each copy as another `+1` of a row it already has; overwrite by key rather than
summing weights.

**The fix is on the binding.** `deletes: detect` makes either scan source an exact changelog (next).
For `jdbc`, poll a column only an insert sets — a sequence, a `created_at` — with `key.column`, and
say so with `watermark.moves.on.update: false`; or read PostgreSQL through `postgres-cdc`.

**Why `ignore` stays the default.** `detect` needs a durable `deletes.state.dir`, which has no safe
default location, and holds every emitted row in memory, bounded by `deletes.max.keys` — a table past
the bound is refused (`PRV-5120` / `PRV-5122`) where `ignore` serves its keyed view correctly. For
Cassandra `detect` costs no extra reads, since every pass already reads the whole range; for Aerospike
it turns the incremental last-update-time scan into a full scan every interval. With the refusal
above, no configuration of either default gives a silently wrong answer: what `ignore` cannot
answer, it refuses.

#### Seeing deletes in a scan: `deletes: detect`

Both scan sources can compare each full pass with **every row they have emitted** and emit only the
difference: a row not emitted before at `+1`; a row that changed as the **whole old row at `−1`**,
then the new one at `+1`; a row that is gone as the whole old row at `−1`, carrying the event time it
was inserted with, so it leaves the window it entered; an unchanged row not at all. A registered view
over the source then equals the store after each pass — the Z-set the engine is built on, from a
store with no change feed.

```yaml
      options:
        deletes: detect
        deletes.state.dir: /var/lib/pravaha/scan-state   # durable local disk
        deletes.max.keys: "2000000"                       # per partition / token range
```

- **Aerospike reads everything each pass.** A deleted record never matches a last-update-time
  filter — it is not written, it is absent — so `detect` drops that filter and scans the whole
  partition range every `scan.interval.ms` (pushed filters and projection still run server-side).
  Raise `scan.interval.ms` to match the set's size. Records are identified by their digest.
- **Cassandra reads what it always read** — every pass was already a full `token()`-range scan. The
  pass is merged with the held rows in token order, so only the current token's rows are buffered;
  the rows under one token (a partition's clustering rows) are compared as a multiset. A poll reads
  at most `fetch.size` rows. With more than one replica, use a `consistency.level` that cannot miss
  a row a replica has not yet received (`LOCAL_QUORUM`): a row absent from one pass is retracted, and
  re-inserted when it reappears.
- **Latency is the scan interval plus one pass.** A delete is seen only as an absence, so it is as
  timely as the next complete pass — up to `scan.interval.ms` plus the pass's own duration after the
  delete. A pass that fails emits nothing more, and never reads what it did not reach as deleted.
- **Memory, per remembered row: about 150 bytes plus the row's encoded size.** Measured with a
  three-column row (17 encoded bytes): 145 bytes a row on the Aerospike reader, 136 on the Cassandra.
  A row encodes as roughly two bytes per column plus the value — a small `BIGINT` in one to three
  bytes, a string in its UTF-8 length plus one. A million rows of that shape is about 150 MB of heap.
  `deletes.max.keys` bounds each partition (Aerospike) or token range (Cassandra) and **refuses by
  code** — `PRV-5120` / `PRV-5122` — rather than forgetting rows, because a forgotten row is a delete
  that can never be detected. Aerospike refuses a pass before emitting any of it; Cassandra bounds
  the rows held at every moment.
- **Disk: the rows again, plus a log of changes.** Each reader keeps, under
  `deletes.state.dir/<source>/<partition>/<reader>/`, a checksummed snapshot of its rows (written
  when the log of changes since the last one passes a quarter of the rows — a quiet pass writes
  nothing) and that log. A checkpoint's offset is `deletes=<reader>/<count>`: how many rows the reader
  had emitted, with the log forced to disk before the offset is handed over.
- **A restart is exact.** A reader resumed from a checkpoint loads the newest snapshot at or below
  the count and replays the log up to it — the rows the engine's own checkpoint, taken at the same
  frozen moment, was built from — and starts a fresh pass against them. It retracts only rows the
  restored view holds and inserts only rows it lacks, whether the checkpoint fell between passes or
  in the middle of one. State that is gone or damaged is refused (`PRV-5121` / `PRV-5123`), never
  guessed at. A durable checkpoint deletes the snapshots and the older reader's directory it
  supersedes; the directory of a query that was dropped is not deleted for you.
- **What it declares:** `emitsDeletes` and `emitsBeforeImage` true, `repeatsRows` false, and
  `EXACTLY_ONCE` — after the first pass every row of the store is counted once, so an aggregate or a
  join over the source is admitted (`PRV-2042` is for the `ignore` mode). The before-image is the row as the previous pass
  saw it: **writes between two passes still collapse**, and an intermediate value is never emitted or
  retracted. `EXACTLY_ONCE` also means the reader is **not shared** between queries: its changes are
  relative to its own emitted rows, which another query's catch-up could not be handed. A query over
  the source can now be refused an append-only sink (`PRV-2041`), as one over `postgres-cdc` is.
- **Switching modes needs a fresh start.** An offset written in the other mode is refused
  (`PRV-5083` / `PRV-5088`): the restored view holds rows the new mode knows nothing about.

#### `postgres-cdc` — a PostgreSQL table's changes, from its write-ahead log

Change data capture through PostgreSQL's own logical replication ([ADR-041](adr/041-change-data-capture-without-debezium.md)):
a replication slot, a publication, and the `pgoutput` stream decoded by the plugin. Unlike every
source above, it is a **changelog**: an `INSERT` arrives at `+1`, a `DELETE` as the whole old row at
`−1`, and an `UPDATE` as both — the old row at `−1`, then the new one at `+1` — so a query's answer
goes *down* when the table does. Every option:

```yaml
pravaha:
  sources:
    customers:
      plugin: postgres-cdc
      options:
        url: "jdbc:postgresql://db-1:5432/crm?sslmode=verify-full&sslrootcert=/etc/pravaha/db-ca.pem"
        user: pravaha_cdc
        password: "${PRAVAHA_CDC_PASSWORD}"
        table: public.customers           # schema.table, or a bare name in public
        stream: customers                 # default: the table name
        slot: pravaha_customers           # default: pravaha_<table>
        publication: pravaha_customers    # default: pravaha_<table>
        create.slot: "true"               # false: the slot must exist, or open is refused naming the statement
        create.publication: "true"        # false: the publication must exist and include the table
        schema: "id:INT64,tier:STRING,region:STRING?"   # optional; checked against the table by name
        event.time: updated_at            # optional TIMESTAMP column; default: the commit time
        heartbeat.interval: 10s           # 0 turns it off; see OPERATIONS.md before you do
        status.interval: 10s              # how often the reader reports to the server
        buffer.rows: "100000"             # decoded rows waiting for the engine before reading pauses
        start.timeout: 30s                # how long opening a reader waits to catch up with the log
        slot.lag.warn.bytes: "1073741824" # retained WAL past which health is DEGRADED
        drop.slot.on.close: "false"       # true only for a throwaway slot: see below
        snapshot.mode: initial            # initial: rows already there, then changes; never (default): changes only
        snapshot.chunk.rows: "10000"      # rows one snapshot query reads; about how many are held at once
```

| Option | Required | Default |
|---|---|---|
| `url` | yes | — must be `jdbc:postgresql:`; TLS goes here, as for `jdbc` |
| `table` | yes | — |
| `user` / `password` | no | empty |
| `stream` | no | the table name, without its schema |
| `slot` / `publication` | no | `pravaha_<table>`; 1–63 of `a-z`, `0-9`, `_` |
| `create.slot` / `create.publication` | no | `true` |
| `schema` | no | derived from the table |
| `event.time` | no | the transaction's commit time |
| `heartbeat.interval` | no | `10s` |
| `status.interval` | no | `10s` |
| `buffer.rows` | no | `100000` |
| `start.timeout` | no | `30s` |
| `slot.lag.warn.bytes` | no | `1073741824` (1 GiB) |
| `drop.slot.on.close` | no | `false` |
| `snapshot.mode` | no | `never`; `initial` needs a primary key |
| `snapshot.chunk.rows` | no | `10000` |

**What the database must allow**, each refused at open with its fix named (`PRV-5112`): PostgreSQL
14 or later; `wal_level = logical` (a restart, not a reload); the table `REPLICA IDENTITY FULL`
(`ALTER TABLE public.customers REPLICA IDENTITY FULL;` — without it a delete's before-image is the
key alone and nothing can be retracted); and a role with `REPLICATION`, which owns the table if the
plugin is to create the publication. The shared `tls.*` options are refused, as for `jdbc`
([`CONNECTOR_TLS.md`](CONNECTOR_TLS.md)); the replication connection is opened from the same URL.

**The schema** is read from the table. Declare one to pin it, or to leave out a column whose type is
not mapped (`json`, arrays, `time`, ranges): a declared schema may name any subset of the columns,
widen an integer or a float, and must mark a nullable column `?`. Mapped: `boolean`, `smallint`,
`integer`, `bigint`, `real`, `double precision`, `numeric` (its scale kept), `text`, `varchar`,
`char`, `name`, `uuid` and enums as `STRING`, `bytea`, `date`, `timestamp` (read as UTC) and
`timestamptz`.

**Rows already in the table: `snapshot.mode`.** With `never`, the default, the source delivers
changes from its slot's creation and nothing before, as it always has. With `initial`, a
registration that starts from nothing first reads the table as it stood at one point in the log —
under a snapshot PostgreSQL exports with a temporary slot — delivers every row at `+1` in
primary-key order, and streams every change after that point. A checkpoint taken half-way through
records the last key delivered (`lsn=…;snapshot=N@key`), and a restore from it takes a new snapshot
and delivers exactly the rest: no row twice, none missing, whatever changed while it was down
([`CONNECTORS.md`](CONNECTORS.md) §5 has the argument). It needs a primary key (refused without
one, `PRV-5112`), `SELECT` on the table, and no transaction left open from before it starts: the
temporary slot waits for every transaction already running, bounded by `start.timeout` (`PRV-5118`).
Health reports the progress — `initial snapshot in progress: N rows delivered of about M`, the
estimate from `pg_class.reltuples`. `never` stays the default because `initial` reads whole tables
and needs a primary key, which no existing binding asked for.

**One slot per registration.** A slot has one reader at a time, and an exactly-once source is never
shared between queries, so two registrations reading the same binding need two bindings with two
slots. A second reader of a slot already being read waits a few seconds and then fails.

**Transactions arrive whole**, so a view never publishes half of one — unless one transaction is
larger than any batch the engine has asked for, in which case it arrives in order across batches.
**`TRUNCATE` stops the source** (`PRV-5116`): there is nothing in it to retract. **The slot is
confirmed only at checkpoints**, which makes checkpointing (`pravaha.checkpoint.*`) a requirement
rather than an option: without it the slot never advances and the database keeps every byte of WAL
since the slot was created. The operational side — slot lag, the heartbeat, dropping a slot — is
[`OPERATIONS.md`](OPERATIONS.md), *Change data capture: the replication slot*.

`drop.slot.on.close: "true"` drops the slot when the source closes — a node shutdown included — so
the next start has no position to resume from and a restored view would miss everything in between.
It is for tests and throwaway environments.

#### `kafka` — a Kafka topic, one reader per partition

A topic read as a stream: one reader per Kafka partition, each seeked to an exact offset, and that
offset kept in the query's checkpoint — so a restore resumes every partition exactly where the
checkpoint left it, and the source is **exactly once** end to end with checkpointing
([`CONNECTORS.md`](CONNECTORS.md) §7 has the argument). Every option:

```yaml
pravaha:
  streams:
    orders:
      schema: "order_id:INT64,customer:STRING,amount:DECIMAL(12,2),placed_at:TIMESTAMP"
      event-time: placed_at
  sources:
    orders:
      plugin: kafka
      options:
        bootstrap.servers: "kafka-1.internal:9093,kafka-2.internal:9093"
        topic: orders
        schema: "order_id:INT64,customer:STRING,amount:DECIMAL(12,2),placed_at:TIMESTAMP"
        format: json                   # json | changelog | avro | protobuf
        # format: avro, with the writer schema on disk:
        # schema.file: /etc/pravaha/schemas/orders.avsc
        # ...or from a schema registry, by the id in each record's five-byte prefix:
        # schema.registry.url: https://registry.internal:8081
        # schema.registry.user: pravaha
        # schema.registry.password: "${REGISTRY_PASSWORD}"   # or schema.registry.token for a bearer token
        # format: protobuf:
        # schema.descriptor: /etc/pravaha/schemas/orders.desc   # protoc --include_imports --descriptor_set_out
        # schema.message: acme.orders.Order
        event.time: placed_at          # optional TIMESTAMP column; on a node, the stream's event-time
        start.from: earliest           # earliest | latest -- only when there is no checkpoint
        isolation.level: read_committed
        tombstone: reject              # reject | skip -- what a null value is in format: json
        monitoring.group: pravaha-orders   # optional: checkpointed offsets committed here for lag monitoring
        user: pravaha
        password: "${KAFKA_PASSWORD}"
        sasl.mechanism: SCRAM-SHA-512
        tls.ca: /etc/pravaha/tls/kafka-ca.pem
        # kafka.fetch.max.bytes: "52428800"   # any other Kafka consumer property, with its kafka. prefix
```

| Option | Required | Default |
|---|---|---|
| `bootstrap.servers` | yes | — |
| `topic` | yes | — must exist; the source never creates it |
| `schema` | yes | — `name:TYPE`, as every other connector; `?` marks a nullable column |
| `format` | no | `json`: each value a JSON object of the row, matched to the schema by column name, every row `+1`. `changelog`: `kafka-sink`'s `{"op","weight","row"}` envelope, its weight applied. `avro`: Avro's binary encoding, every row `+1`. `protobuf`: one message of a descriptor set, every row `+1` |
| `schema.file` | with `format: avro` | — | The writer schema, as Avro JSON. Exactly one of this and `schema.registry.url`; either with another format is refused by name |
| `schema.registry.url` | with `format: avro` | — | A Confluent-compatible registry (Confluent, Karapace, Apicurio's `ccompat` path). Each value must then carry the wire format's `0x00` and four-byte schema id; the schema of an id is fetched once and cached. With `format: protobuf` it means only that the prefix and Confluent's message-index array are read past — the message still comes from `schema.descriptor` |
| `schema.registry.user` / `schema.registry.password` / `schema.registry.token` | no | none | Basic auth, or a bearer token. Refused without `schema.registry.url`. TLS for an `https` registry is the same `tls.*` as the brokers'; `tls.verify-hostname: false` with an `https` registry is refused |
| `schema.registry.timeout` | no | `10s` | Per attempt; three attempts a short pause apart, then `PRV-5109` |
| `schema.descriptor` / `schema.message` | with `format: protobuf` | — | A `FileDescriptorSet` (`protoc --include_imports --descriptor_set_out=x.desc`) and the message in it a record holds, by full name or an unambiguous simple name |
| `event.time` | no | on a node, the stream's declared `event-time` column (handed down as this option); the record's Kafka timestamp only when the stream declares none |
| `start.from` | no | `earliest`; `latest` starts after what the topic already holds. A restore ignores it |
| `isolation.level` | no | `read_committed`; `read_uncommitted` also delivers what aborted transactions wrote |
| `tombstone` | no | `reject`: a null value is a dead letter, or stops the source. `skip` reads an upsert topic as insertions only |
| `monitoring.group` | no | none. Never read: the checkpoint is the position |
| `buffer.records` | no | `10000` decoded records per partition waiting for the engine before fetching pauses |
| `start.timeout` | no | `30s`: opening waits this long for the brokers, and for a reader to queue what the partition already holds |
| `lag.warn.records` | no | `100000`: health is `DEGRADED` when a partition is this far behind |
| `user` / `password` / `sasl.mechanism` | no | SASL `PLAIN` (refused without TLS), `SCRAM-SHA-256` or `SCRAM-SHA-512` |
| `tls.*` | no | the shared options ([`CONNECTOR_TLS.md`](CONNECTOR_TLS.md) §3.4) |
| `kafka.<property>` | no | any consumer property, except the ones that would move the position or that the source sets: `group.id`, `enable.auto.commit`, `auto.offset.reset`, `isolation.level`, the deserializers, `allow.auto.create.topics`, and security, which have options of their own |

**Values** are read the way `kafka-sink` writes them: numbers as JSON numbers (a `DECIMAL` exactly,
refused rather than rounded if it has more places than its scale), a float also from `"NaN"` or
`"Infinity"`, `BYTES` as base64, `DATE` as `2026-09-19`, `TIME` as `10:15:30.5`, `TIMESTAMP` as an
ISO-8601 instant or epoch milliseconds. A member the schema does not name is ignored; a missing
column is null, and refused if it is not nullable. The record's key is not read — `kafka-sink` puts
every column in the value. A record that does not fit is a dead letter (`pravaha.dlq.directory`)
named `topic/partition@offset`, or, with no dead-letter queue, stops the source with `PRV-5105`.
A dead letter can be listed, fetched whole and replayed — `pravaha dlq`, `GET
/api/v1/queries/{name}/dead-letters`, or the query's page in the console; replaying it is a new row
at the query's current frontier, not a rewind, and is refused for an exactly-once source that has
not yet read past the record.

**Deletes only in `format: changelog`.** A tombstone in an upsert topic deletes a key without saying
what row it held, so there is nothing to retract; to feed retractions from one query to another,
write the first with `kafka-sink`'s `mode: changelog` and read it with `format: changelog`.

**`format: avro`** reads Avro's binary encoding with a reader of this repository's own — no
`org.apache.avro` on the classpath. A column takes: `BOOLEAN` from `boolean`; the integer columns
from `int` or `long`, refused rather than truncated out of range; `FLOAT32` from `float`, `FLOAT64`
from `float` or `double`; `STRING` from `string` or an `enum`'s symbol; `BYTES` from `bytes` or
`fixed`; `DECIMAL` from `bytes`/`fixed` with `logicalType: decimal`; `DATE` from `int`/`date`; `TIME`
from `int`/`time-millis` or `long`/`time-micros`; `TIMESTAMP` from `long`/`timestamp-millis` or
`timestamp-micros`. A plain `int` is not a `DATE` and a plain `long` is not a `TIMESTAMP`, and
`local-timestamp-*` is refused rather than assumed to be UTC. A `["null", T]` union reads NULL or its
branch. A field no column names is skipped whole; a column no field carries is `PRV-5108` at
registration.

**`format: protobuf`** reads one message with `DynamicMessage`. A column takes: `BOOLEAN` from
`bool`; the integer columns from any integer type, unsigned widened and refused out of range;
`FLOAT32`/`FLOAT64` from `float`/`double`; `STRING` from `string` or an `enum`'s symbol; `BYTES` from
`bytes`; `DECIMAL` from a `string` holding the number exactly; `TIMESTAMP` from
`google.protobuf.Timestamp` or an ISO-8601 `string`; `DATE` and `TIME` from an ISO-8601 `string`.
**A proto3 scalar without `optional` has no presence**: absent and set-to-default are the same bytes,
so such a column is filled with `0`, `""` or `false` and is never NULL. Presence-carrying fields —
proto3 `optional`, message fields, proto2 `optional` — are NULL when absent.

**With a registry**, each value is `0x00`, a four-byte big-endian schema id, then the payload; the
schema is fetched once per id over `GET <url>/schemas/ids/{id}` and kept. A record with no such
prefix is a dead letter saying so; a registry that cannot be read stops the reader with `PRV-5109`;
a registry schema that cannot be mapped to the columns makes each record carrying it a dead letter,
naming the id. A value that begins with `0x00` while no registry is configured is refused with
`schema.registry.url` named.

**One consumer per partition per registration.** An exactly-once source is never shared between
queries, so each registration reading this binding has its own consumer and fetch thread per
partition. The partition list is read at registration; partitions added to the topic later are read
after the next restart or re-registration.

**Retention is the limit.** If retention deletes records before a checkpoint has read them — a node
down longer than the topic's `retention.ms` — the restore is refused (`PRV-5106`) rather than
resumed from wherever the log now starts; so is a checkpoint whose offsets are past the end of a
recreated topic (`PRV-5106`) or that belong to another topic (`PRV-5104`). Lag, the monitoring group
and what to do about each are in [`OPERATIONS.md`](OPERATIONS.md), *Kafka as a source*.

### 2.2 Lookup sources, for temporal joins

A lookup is asked, not consumed: it never advances event time and holds no state to checkpoint (§7).
Two ship today, and they live under `pravaha.lookups`.

**A query joins a lookup by the name its plugin gives the table, not by its key under
`pravaha.lookups`.** The key labels the binding in log lines and errors and is not passed to the
plugin; the table's name comes from the plugin's own schema — `aerospike-lookup`'s `stream` option,
which defaults to the set name, and `jdbc-lookup`'s `table`. So below, §7's `JOIN user_profile …`
works because `stream: user_profile` says so; without it the table would be `users`, and a join
against `user_profile` would not find it. The simplest rule is to use the same word for all of them.

```yaml
pravaha:
  lookups:
    user_profile:
      plugin: aerospike-lookup
      options:
        hosts: "as-1:3000"
        namespace: prod
        set: users
        stream: user_profile           # the name a query joins; defaults to the set, `users`
        schema: "user_id:STRING,tier:STRING,region:STRING"
        key.bin: user_id
        cache.seconds: "60"
        concurrency: "16"

    accounts:
      plugin: jdbc-lookup
      options:
        url: "jdbc:postgresql://db-1:5432/ref"
        table: accounts                # the name a query joins
        key.columns: "account_id"      # comma-separated for a composite key
        pool.size: "8"
        cache.seconds: "300"
```

| `aerospike-lookup` | Required | Default |
|---|---|---|
| `hosts`, `namespace`, `set`, `schema`, `key.bin` | yes | — |
| `stream` | no | the set name |
| `cache.seconds` | no | `0`, meaning no caching |
| `concurrency` | no | `16` |

| `jdbc-lookup` | Required | Default |
|---|---|---|
| `url`, `table`, `key.columns` | yes | — |
| `pool.size` | no | `8` |
| `cache.seconds` | no | `0` |

`pool.size` is not a tuning knob to leave alone: the engine asks a lookup from several threads at
once to hide the round trip, and one shared JDBC `Connection` used from two threads interleaves
result sets and returns rows attached to the wrong query. The pool is what makes concurrent lookups
correct, not merely faster.

A cache costs staleness, never correctness — a lookup holds no checkpointed state, so a cache lost
on restart costs latency and nothing else.

**This section configures sources that already ship. Writing a new one** — the SPI, the TCK, what a
connector may honestly claim, change-data-capture and Debezium — is
[`CONNECTORS.md`](CONNECTORS.md). Using versus building is the line between the two documents, and
neither repeats the other.

---

## 3. Registering a continuous query

In SQL, wherever SQL arrives — `pravaha query --sql`, either SDK's `query()`, the console's
workbench, any Flight SQL client, or the embedded engine's `query(sql)`:

```sql
CREATE CONTINUOUS QUERY hourly_spend
    KEYED BY (user_id, window_end)
    RETAIN FOR P7D
AS
SELECT user_id,
       window_end,
       SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY user_id, window_start, window_end;
```

**Keep the window's own column names.** This example once read `TUMBLE_END(event_time, INTERVAL
'1' HOUR) AS hour`, and was refused twice over: `TUMBLE_END` belongs to the older
`GROUP BY TUMBLE(...)` form, not to `TABLE(TUMBLE(...))` (`PRV-2002`), and `hour` is a reserved word
(`PRV-2001`). Renaming the boundary instead — `window_end AS hour_end` — is refused with `PRV-2050`,
because the planner no longer sees the aggregate grouped by its window. Every SQL example in the
console's help is now planned by `HelpExamplesSqlTest`, which is how this was found.

It answers with one row — the name, its state, the fingerprint of the computation the name landed
on, and the sink — and `DROP`, `PAUSE` and `RESUME CONTINUOUS QUERY hourly_spend` and `SHOW
CONTINUOUS QUERIES` manage what is running. §10.1 has the whole grammar. The key is given **by
column name** and resolved to output ordinals by planning the `SELECT`, so it cannot drift when the
`SELECT` list is reordered.

Or, with the same meaning, as a registration whose arguments name everything:

```bash
pravaha register --name hourly_spend \
  --sql "SELECT user_id,
                window_end,
                SUM(amount) AS spend
         FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
         GROUP BY user_id, window_start, window_end" \
  --keys 0,1
```

**`--keys` is the view's key** (`KEYED BY` in SQL), given here as output-column ordinals. It decides what a row *replaces*: a
second row with the same key supersedes the first. Get it wrong and the view conflates rows that
should be distinct, or keeps rows that should have replaced each other.

Two registrations differing only in `--keys` are **two different computations** — the key is part of
the query's identity, because it changes the answer (I-3).

**`--retain` is how much event time the view keeps** (`RETAIN FOR` / `RETAIN FOREVER` in SQL), as an ISO-8601 duration (`PT24H`, `P7D`) or
`forever`; left out, the view keeps **forever** (`QueryRegistry`'s default since TY-21). A server has no
setting that changes that default; an application embedding a `QueryRegistry` can, with
`retaining(...)`. Rows whose
event time falls further behind the committed frontier than that are evicted. Both SDKs take it as
the `retention` argument of `register`, and the Flight `pravaha.register` action as an optional fifth
field (the fourth, the sink, may be empty). A value the server cannot read is refused rather than
defaulted. Like the key, retention is part of the fingerprint, and it is journalled with the
registration so a restart keeps it.

**What was registered can be read back.** `pravaha queries` and both SDKs' `queries()` report each
query's key ordinals, sink and retention (trailing fields 5–7 of the `pravaha.list` action, after the
original five, so an older client reads what it always did) and whether that sink is still attached
(fields 13–15: `ATTACHED`, `DETACHED` or `NONE`, and a detached one's `PRV-8009` code and message). The HTTP API describes a query in full
(`GET /api/v1/queries/{name}`: keys by name, retention, sink and whether it is still attached, rows in,
the other names sharing it, the streams it reads, and its feed — each source partition's state and a
stopped one's code), its running plan as a graph
(`GET /api/v1/queries/{name}/plan`), its view without reading it (`GET /api/v1/views/{name}`), and the
node's sinks with what each accepts (`GET /api/v1/sinks`). Each decides what you may see exactly as the
listing does — a denied name is refused whether or not it exists, and a query reading a stream you may
not read answers as a name that was never registered.

### What happens at registration

1. The SQL is parsed and validated against the declared streams
2. Each stream's source is asked whether it repeats rows, and a query whose answer depends on how
   many times a row arrived — an aggregate, a join, or a sink that cannot upsert — over one that
   does is refused with `PRV-2042` naming the fix, `deletes: detect` on the binding (§2.1, SCAN-1).
   When the registration names a sink (§4), the plan's changelog is then checked against what that
   sink accepts, and a pair that cannot work is refused with `PRV-2041` — before anything opens
3. A physical plan is built and **fingerprinted** — plan, row filters, key columns, retention
4. If an identical fingerprint is already running, **the existing computation is shared** and the new name points at it
5. Otherwise a lane is created, the plan compiled onto it, and a feed opened for each source stream
6. The view is created and registered in the catalogue

Step 4 is why a thousand dashboards asking the same question cost one computation. It matches on the
*normalised plan*, not the text — whitespace and aliases do not matter, but operand order does
(`CONCEPTS.md` §5).

---

## 4. Reading the answer

```bash
pravaha query --sql "SELECT * FROM hourly_spend WHERE spend > 1000"
```

This is ordinary SQL over the view, planned and executed by **the same planner and operators a
continuous query uses** — so a `WHERE` means exactly what it means in a CQ rather than nearly. §10
says what that shared surface is and where the one asymmetry lies.

**How the read finds its rows.** The `WHERE` clause decides, and the answer never does:

| What you wrote | How it is answered |
|---|---|
| The whole key by equality — `WHERE user_id = 'u_42'` on a view keyed by `user_id` | One hash probe. A view already *is* a map keyed by that, so nothing has to be declared and no index is built |
| The key's leading columns by equality and its last between bounds — `WHERE user_id = 'u_42' AND window_end >= 1000 AND window_end < 2000` | The ordered index. Walks the run, not the view |
| Anything else — a partial key, a column outside the key, an `OR` across keys | Every committed row, filtered. Best effort, and honest about it |

The filter runs either way, so which path a read takes changes what it costs and not what it says.
`RANGE (column)` at registration is what tells you, *then*, that the column can be ordered at all
(`PRV-2073`); the index itself is available on any view whose key ends in an orderable column, and
is built the first time a range read needs one — see
[ADR-049](adr/049-an-ordered-index-over-the-keys-last-column.md) for why the declaration is a check
rather than an allocation. The ordered index holds one entry per row — a reference, not a copy — so the view's key ceiling
bounds it too; it is built the first time a range read needs it, maintained by every commit
afterwards, and dropped and rebuilt on a restore. A row whose ordered column is `NULL` is in no
index entry, which is the same thing SQL says about it: `NULL > 1000` is UNKNOWN, and `WHERE` reads
that as false. All of this is the same for Flight SQL, for `GET /api/v1/views/{name}/query` and for
the PostgreSQL gateway, because all three run the same reader.

Or subscribe, and receive each committed change as it happens:

```bash
pravaha subscribe --view hourly_spend
```

**A subscription delivers whole commits.** Never half a batch, never a partly-closed window — a
subscriber attaching midway through a commit receives the *next* one entire rather than the tail of
that one (STRM-11). A commit itself ends only where the engine finished a batch, so an update's
retraction and its insert arrive in the same commit, never the retraction alone (VIEW-1).
`pravaha subscribe` prints each change with its weight first — `+1` a row arriving, `-1` a row
withdrawn — and a `-- commit` line after each commit.

**A plain subscription starts at the next commit, and is gapful for a client keeping a copy.** It
carries none of what the view already holds, and reading the view beside it — before or after — can
miss the commit in flight at that moment, silently (SUB-1). `pravaha subscribe --snapshot`
(`subscribeFromSnapshot` in Java and the embedded engine, `subscribe(..., snapshot=True)` in Python)
sends the view's rows at a commit first, closed by `-- snapshot at frontier F, N rows`, and then
every commit after it, with nothing between. [`USER_GUIDE.md` §4](USER_GUIDE.md#4-subscribe) says
how it is kept gapless.

Or have the node write every commit to a sink it binds under `pravaha.sinks.<name>`
([`OPERATIONS.md`](OPERATIONS.md) has the binding), by naming it at registration:

```sql
CREATE CONTINUOUS QUERY big_txn KEYED BY (user_id) WRITING TO audit_trail
AS SELECT user_id, amount FROM txn WHERE amount > 100;
```

or, as arguments:

```bash
pravaha register --name big_txn --sql "SELECT user_id, amount FROM txn WHERE amount > 100" \
  --keys 0 --sink audit_trail
```

The view is maintained exactly as without `--sink`; the sink receives the same commits a subscriber
does, retractions included as rows with a negative weight. Three things to know
([ADR-043](adr/043-how-a-continuous-query-names-its-sink.md)):

- **The query's output must be the sink's row shape.** A sink reads rows through the schema in its
  binding, so the `SELECT` list must match it column for column — order, name and type — and a keyed
  sink must be keyed by exactly the query's `--keys`. Anything else is refused with `PRV-8010` at
  registration, because the alternative is every value read from another column's place.
- **The query and the sink must agree about retractions.** A query that revises its answer — any
  unwindowed aggregate, a window with `allowedLateness`, anything read from a source that emits
  deletes (`postgres-cdc`) — needs a sink that accepts updates. Pointed at an append-only sink, such
  as a file, the pair is refused with `PRV-2041` at registration, because the alternative is a sink
  holding rows that are each correct and a total that is wrong for ever (design §15.5). A join
  passes on what its inputs do: over two append-only sources it only appends, and a delete on
  either side retracts the pairs it made, so a join over a change feed is refused too (HLP-3; it
  was admitted until then, because the check assumed every source only appends). A filter, a
  projection, or a tumbling window without lateness over append-only sources never revises, and
  goes anywhere — unless a source repeats rows: over `cassandra` or `aerospike` with the default
  `deletes: ignore`, or a `jdbc` poll whose watermark an update moves, even a projection is refused
  a sink that cannot upsert by key (`PRV-2042`), because the sink would write every copy as another
  row (§2.1).
- **Delivery is as strong as the sink allows, and the node says which** in its log when you
  register. A restart replays from the last checkpoint. A *transactional* sink on a node that
  checkpoints is **exactly once**: what it is written between checkpoints is prepared at the
  checkpoint, committed once the checkpoint is safely stored, and after a crash the replay's copy is
  the only one committed. A sink that upserts idempotently is **effectively once** — the replay
  rewrites what is already there. A plain file is **at least once**: it keeps the repeats. A second
  name registered with its own sink on a query that is already running is first sent the view's
  whole contents, inside its first transaction if it has transactions. Of the shipped sinks,
  `jdbc-sink` — which maintains the answer in a relational table, upserting and deleting by key —
  is transactional, so exactly once on a node that checkpoints, with the table trailing the view by
  up to a checkpoint interval. So is `kafka-sink`, which writes the changes to a Kafka topic — keyed
  JSON upserts with a tombstone for each retraction, or an explicit changelog of every change and
  its weight — exactly once to a consumer reading with `isolation.level=read_committed`
  ([`OPERATIONS.md`](OPERATIONS.md) has the binding). `delta-sink` is transactional too: it maintains
  the answer in a Delta Lake table by key, or appends a changelog of every change and its weight, as
  one Delta commit per checkpoint — exactly once on a node that checkpoints, with the table trailing
  the view by up to a checkpoint interval. `aerospike-sink` is effectively once and
  `filesystem` at least once.
- **A sink that refuses a batch is detached** (`PRV-8009`) rather than written past, and the query
  carries on serving its view. Drop and re-register to start the sink again from the view's
  contents; whatever the sink already held, it will hold twice.

Two names for one computation may each name a different sink; the computation is shared and each
sink is fed from it.

---

## 5. Windows, worked

A window turns an unbounded stream into a sequence of finite groups. Two forms:

### Tumbling — fixed, non-overlapping

```sql
SELECT window_start, window_end, COUNT(*) AS trades
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
GROUP BY window_start, window_end
```

Every row falls in exactly one window. `[0,10)`, `[10,20)`, `[20,30)`.

### Hopping — fixed width, sliding

```sql
SELECT window_start, window_end, SUM(amount) AS spend
FROM TABLE(HOP(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND, INTERVAL '60' SECOND))
GROUP BY window_start, window_end
```

A minute of history, recomputed every ten seconds. Each row belongs to **six** windows.

### The descriptor must name the stream's declared event time

```sql
-- refused: 'other_time' is a timestamp, but not the one the watermark tracks
TUMBLE(TABLE ev, DESCRIPTOR(other_time), INTERVAL '10' SECOND)
```

Windows are cut in event time and closed by a watermark, and the watermark only advances on the
declared column — so a grid keyed to any other column would be closed by a clock that knows nothing
about it, and the answer would be **wrong rather than late** (TIME-2).

### When a window emits

When the watermark passes its end. The watermark is *"nothing earlier than this is coming"*, derived
from the rows arriving minus the stream's `out-of-orderness`. Until it passes, the window is open and
its result is not published.

**This is why a query with no `event-time` could emit nothing.** It was not slow; it was waiting for
a clock that would never tick — so it is now refused at registration instead (`PRV-2002`, §2).

---

## 6. Corrections, and why the answer can go backwards

A row arriving late for a window that already emitted does not produce a wrong answer and does not
produce a second one. It produces a **correction**: the old result is retracted at `−1` and the new
one inserted at `+1`.

A subscriber sees both. A reader of the view sees only the current state, because the retraction and
the insert are applied before the commit is visible.

```
window [10,20) COUNT=5      ← emitted when the watermark passed 20
... a late row for t=15 arrives, within allowed lateness ...
window [10,20) COUNT=5   -1 ← retracted
window [10,20) COUNT=6   +1 ← corrected
```

Allowed lateness is the stream's: `allowed-lateness` under `pravaha.streams.<name>` (or
`allowedLateness` on `POST /api/v1/streams`), zero by default, which makes a window final when it
closes and drops a row that arrives after. Until HLP-7 a server had no way to set it, so the
correction above could only happen in an embedded engine.

This is what `weight` means, and it is why the engine can be incremental at all
([`CONCEPTS.md`](CONCEPTS.md) §4).

---

## 7. Joining streams

```sql
SELECT o.order_id, c.segment, o.amount
FROM orders o
JOIN customers c ON c.customer_id = o.customer_id
```

Both sides are streams; the join keeps state for each side and emits when a pair matches. State is
bounded by a **match window** in event time — without one, an unbounded join grows for ever, so the
default exists to make the join survivable rather than to be correct in every case.

A `LEFT` join is supported **with a time bound**: the null-padded row is emitted when the watermark
passes the window, once, and never retracted — because without a bound there is no moment at which a
row can be declared unmatched.

A left row whose **join key is NULL** is one of those rows. `NULL` is not equal to `NULL` in a join,
so it matches nothing — including another `NULL` — and a `LEFT` join therefore emits it null-padded
like any other unmatched left row. It used to be dropped on arrival, because a row that can never
match looked like a row not worth holding (finding J-1); it was then not in state when the watermark
passed, so it left no trace at all. The right side still drops its own null-keyed rows: nothing
would ever read them.

**The two sides may come from entirely different connectors.** A join across a `filesystem` source
and a `feedfile` source — two independently discovered plugins with different decoders — is
demonstrated end to end by `CrossConnectorJoinTest`, which feeds an unmatched row to each side so
that a cross product or a pass-through would fail it. Joins touching `jdbc`, `delta` or `aerospike`
remain supported by construction and undemonstrated. See [`CONNECTORS.md`](CONNECTORS.md) §4.

§14 is the full matrix of which join shapes run and which are refused.

### Temporal joins — enriching from a table

```sql
SELECT t.txn_id, t.amount, p.tier
FROM txn t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id
```

The right side is a **lookup**, not a stream: each row asks the store as of its own event time.
Lookups are fanned out on virtual threads so an operator is not capped at the inverse of the store's
latency, and they hold **no state to checkpoint** — a cache lost on restart costs latency, not
correctness.

---

## 8. The life of a query

```
          register
             │
             ▼
         RUNNING ──pause──► PAUSED ──resume──► RUNNING
             │                 │
             │ lane throws     │
             ▼                 ▼
          FAILED           (drop) ──► DROPPED
```

| State | Means |
|---|---|
| `RUNNING` | Ingesting and maintaining its view |
| `PAUSED` | Feed stopped; state kept; resumable |
| `FAILED` | A lane threw. **The view refuses reads** rather than serving a snapshot frozen at the failure (E-13) |
| `DROPPED` | Gone. The view is removed from the catalogue |

`FAILED` and `DROPPED` are terminal. A failed query's rows are still held and were correct as of the
failure — what it will not do is hand them over as though they were current.

**A stopped source is not a state.** When a source fails mid-read — a deleted file, a revoked
credential, a line it cannot decode — its feed stops and is not retried, and the query stays
`RUNNING`: its view is correct up to the frontier it reached and keeps answering, it can still be
paused, resumed and dropped. `FAILED` would make the view refuse reads, and a fifth state would be a
value every client switching on the state has never seen, so the stop is reported beside the state
instead (FEED-1): the feed's own state (`RUNNING`, `PAUSED`, `STOPPED`, or `NONE` when nothing is
bound) and, for a stopped source, the code, the stream and partition, and when. It is on
`GET /api/v1/queries/{name}` (`feed`), in `pravaha queries` (`RUNNING (source stopped)`), in both
SDKs' `queries()`, in `pravaha_query_feed_stopped`, on `/status`, in the health indicator
(`DEGRADED`) and on the console's query and operations pages. See OPERATIONS, *A source that stopped*.

### 8.1 Replacing a running query: `CREATE OR REPLACE`

Changing a query's SQL used to mean dropping it and registering it again, which takes the answer
away from everybody reading it and gives them back an aggregate with no history. A replacement does
not:

```sql
CREATE OR REPLACE CONTINUOUS QUERY spend
    KEYED BY (user_id)
    WITH (backfill = 'history', backfill.rate.limit = 5000, cutover = 'manual')
AS SELECT user_id, SUM(amount) AS total, COUNT(*) AS payments
   FROM txn GROUP BY user_id, TUMBLE(ts, INTERVAL '1' HOUR);
```

What happens, in order:

1. **The new version is registered beside the running one**, as a shadow: its own state, its own
   checkpoints, its own readers, and no reader can reach it. The name goes on answering the version
   it answers now. The statement returns immediately, with the state `BACKFILLING`.
2. **It reads the history** — the source from the beginning — and **splices onto the live stream at
   the exact position the running version has reached**. The seam is a position, not a moment: the
   history is read to that offset and a reader is then created at it, so every record arrives
   exactly once.
3. **It catches up**, which means every partition has reached the live stream. `pravaha
   replacements`, `GET /api/v1/queries/spend/replacement` and the `pravaha.replacement` action all
   say how far it has got.
4. **You cut over** — `pravaha cutover --name spend`, `POST .../replacement/cutover`, or
   `cutover = 'auto'` to say you do not want to be asked. The cutover happens only when the two
   versions have consumed *exactly* the same input, compared position by position with both feeds
   stopped; a cutover that cannot find such a point in thirty seconds is refused with `PRV-4014`
   rather than taken.
5. **The version it replaced keeps running** for `rollback.retention` (an hour by default), so
   `pravaha rollback --name spend` is one swap rather than a second backfill. `pravaha finish`
   releases it early; after that there is nothing to roll back to.

**The options.**

| Option | Default | Means |
|---|---|---|
| `backfill` | `history` | `history` replays the source and splices onto the live stream. `none` starts the new version where the running one is, with empty state — cheap, and correct only for a query whose answer does not depend on history |
| `backfill.rate.limit` | none | Records a second the backfill may read. A **ceiling**: an operator may lower it while it runs (`pravaha throttle`) and may not raise it above this |
| `cutover` | `manual` | `auto` cuts over as soon as the new version has caught up |
| `rollback.retention` | `PT1H` | How long the replaced version keeps running afterwards |

The design's `backfill.parallelism`, `backfill.window` and `backfill.adaptive` are **refused by
name** with `PRV-4018`: a backfill reads each partition once, from the beginning, at the rate you
set, and nothing here probes the store's own latency to adapt to.

**What a reader sees.** A read of the name resolves to one version's view or the other's and never a
mixture, and neither is behind the other, so the answer to the old question up to the seam is
followed by the answer to the new question after it — with no gap and nothing counted twice.

**What a subscriber sees.** Every commit the replaced version ever made, the last of them taken
while it was paused at the seam, and then the subscription **ends with `PRV-4019`**. Plain and
snapshot subscriptions alike: handing a subscriber the new version's changes on top of the old
version's would be a copy that is half one query's answer and half another's, with nothing in the
stream to say so. Subscribe again — a snapshot subscription then starts from a fresh snapshot of the
new version, which is what it actually needs.

**What a sink sees.** It follows the name, at a checkpoint boundary: everything the replaced version
wrote is committed, and the new version's delivery is sent the **difference** between that and its
own view as one batch — the old answer withdrawn, the new one written. Nothing twice, nothing left
behind. A replacement may not change *which* sink the name writes to, or the view's retention:
`WRITING TO` naming a different sink and `RETAIN` are refused with `PRV-4018`. Moving a sink is a
drop and a fresh registration, so that what the old sink holds is somebody's decision rather than a
side effect.

**What is refused, by name.**

| Code | When |
|---|---|
| `PRV-4013` | The backfill read all the history there is and never reached the position it was to splice at — a source whose positions do not name the record they were taken after |
| `PRV-4014` | A cutover before the candidate caught up, or when the two versions could not be brought to the same position |
| `PRV-4016` | A cutover, rollback or status for a name nothing is replacing |
| `PRV-4017` | A second replacement of one name; a new version that is the same computation as the old; a drop while a candidate is running |
| `PRV-4018` | A stream nothing is bound to or that cannot be replayed; an option this engine does not build; a rate above the ceiling; a changed sink or retention |
| `PRV-4019` | The subscription you were holding: the view was replaced |
| `PRV-8003` | The query's computation is shared with another name — a replacement moves one name, and a shared computation cannot tell which name a subscriber arrived through |
| `PRV-7002` | You may not administer this name. A replacement requires the same permission a drop does, reading its status included |

**Where it runs.** `CREATE OR REPLACE` over Flight SQL and in the embedded engine; the actions
`pravaha.replace`, `pravaha.replacement`, `pravaha.cutover`, `pravaha.rollback`, `pravaha.abandon`,
`pravaha.finish` and `pravaha.backfill`; `pravaha replace | replacements | cutover | rollback |
abandon | finish | throttle | pause-backfill | resume-backfill`; both SDKs; and
`/api/v1/queries/{name}/replacement` with `/api/v1/queries/{name}/backfill`. The PostgreSQL gateway
is read-only and refuses it with `PRV-6211`.

---

## 9. Parameters

`?` placeholders are supported in `WHERE` and `HAVING`, and nowhere else. See
[ADR-032](adr/032-parameters-are-values-not-queries.md) for the full position table and the reasoning.

```java
client.query("SELECT total FROM user_volume WHERE user_id = ?", "u1");
```
```python
client.query("SELECT total FROM user_volume WHERE user_id = ?", ["u1"])
```

A registered query may also carry bound values, **in embedded use only** — through
`QueryRegistry.register(name, sql, keys, principal, BoundParameters.of(...))`:

```java
registry.register("eu_spend", "SELECT ... WHERE region = ?", List.of(0), principal, BoundParameters.of("EU"));
```

Nothing that registers over the wire binds values: the Flight `register` action (and so
`pravaha register` and both SDKs) carries a name, SQL, keys, a sink and a retention, and no
parameters. A `?` in a query registered that way is refused with `PRV-2060` (no value bound), and
`pravaha register` refuses `--param` rather than ignore it. Write the literal into the SQL — or,
better, let the view carry the column, as below.

**A bound value is part of the plan, so it is part of the fingerprint**: two bindings of the same SQL
are two computations, not one shared. That is the truth rather than a policy — and it is precisely
why a parameter the view *carries* is usually better than one baked in, because then a single
computation answers every region and the filter happens at read time.

---

# Part II — the SQL reference

## 10. Continuous queries and view reads run the same SQL

There is one planner and one set of operators. A continuous query registered against a source and a
request/response query against a maintained view both go through `SqlPlanner` → `PhysicalPlanBuilder`
→ the same physical operators, so **everything in this half applies to both**. That is deliberate:
two implementations of `WHERE` agree until the day they do not, and that day is a support call about
a number.

The one asymmetry worth knowing is the unwindowed keyed `GROUP BY`. For a continuous query it is
refused **and should be** — over an endless stream its state never stops growing. Over a bounded read
of a view the same argument does not hold, because the scan ends, so it is **supported there**. Same
SQL, different answer, and the difference is the input rather than the query. Covered
[below](#should-a-continuous-query-aggregate-at-all).

### The short version

Pravaha runs **the shape of query people actually write against a stream**: pick columns, filter
them, join on a key, aggregate over a window. It deliberately does not try to be a general analytics
engine — ADR-030 puts ad-hoc federated analytics explicitly out of scope, and every refusal below is
a case of that decision rather than an unfinished corner.

**A query Pravaha cannot run is refused when it is planned, with a `PRV-` code and an explanation.**
It is never accepted and then approximated. That matters more than the size of the supported set: a
refusal costs a developer five minutes, and a query that runs and returns a plausible wrong number
costs whatever was decided on the strength of it.

### 10.1 The statements that register and manage queries

```
CREATE [OR REPLACE] CONTINUOUS QUERY name
    KEYED BY (column [, column]...)
    [RANGE (column)]
    [WRITING TO sink]
    [RETAIN FOR duration | RETAIN FOREVER]
    [WITH (option = value [, option = value]...)]
AS select

DROP   CONTINUOUS QUERY name
PAUSE  CONTINUOUS QUERY name
RESUME CONTINUOUS QUERY name
SHOW   CONTINUOUS QUERIES
```

- **Keywords in any case; names plain or double-quoted** (`"audit-log"`, with `""` for a quote).
  A name keeps its case, as every identifier here does. The query's name follows the registry's
  rule — a name a `FROM` clause can hold — so a reserved word is refused with `PRV-8008`.
- **The clauses before `AS` in any order, each once.** A key is required: a view with no key is a
  log, and a point read against it has nothing to look up. `KEYED BY` says it, and so do `RANGE`
  alone (the ordered column is then the whole key) and `WITH (keys = '...')`. Saying it two ways is
  refused rather than resolved.
- **`KEYED BY` names output columns** — by alias where the `SELECT` list gives one, so
  `SUM(amount) AS total` is `total`. The `SELECT` is planned to turn the names into the ordinals the
  view is keyed by; a name it does not produce is refused with `PRV-2071`, and so is a name given
  twice. A name matches its column exactly, or else the one column differing only in case.
- **A duration** is ISO-8601, bare or quoted (`PT24H`, `'P7D'`), or an interval in one unit:
  `INTERVAL '24' HOUR` — `SECOND`, `MINUTE`, `HOUR`, `DAY` or `WEEK`. A month is not a fixed length
  of event time, and is refused.
- **`WRITING TO sink`** names a binding under `pravaha.sinks` (§4); everything §4 says about sinks
  holds.
- **One trailing semicolon** is accepted, and so are comments.

- **`RANGE (column)`** asks for an ordered index over the key's **last** column, so that a read
  which pins the columns before it and bounds that one is answered by walking the run rather than
  the view (§4). The design writes it `INDEXED BY (user_id) RANGE (window_end)`, and that is what
  it means here: `user_id` is probed, `window_end` is scanned between bounds, and the two together
  are the key. One column only, and it must be the key's last, or the columns after it would be
  what an index entry is sorted by. A column this engine has no total order for is refused at
  registration with `PRV-2073`, which says which of the reasons applies.

  **This is the one place `INDEXED BY` and `KEYED BY` are not aliases.** Under `INDEXED BY`, a
  `RANGE` column the list does not already end with is appended to the key, because that is what
  the design's spelling means. Under `KEYED BY` it is refused (`PRV-2070`): the writer has spelled
  out what the view conflates, and a key of `(a, b)` is a different view from one keyed by `a` —
  two rows sharing `a` and differing in `b` stop being one row, and every count over the view
  changes. The refusal shows both ways to say what was meant.
- **`OR REPLACE`** starts a blue/green replacement when the name already exists (§8.1), and is an
  ordinary `CREATE` when it does not — so the same script runs on the first deployment and on the
  tenth. It does **not** take the name from its readers: the new version is registered beside the
  running one and the statement answers with the state it is in, which is `BACKFILLING`.
- **`WITH (...)`** is one list parser and two vocabularies, chosen by the statement. On a plain
  `CREATE` it takes a registration's options, which are the arguments `pravaha register` already
  took:

  | Option | Means | The other way to say it |
  |---|---|---|
  | `retention` | How long the view keeps a row, in event time: `'24h'`, `'7d'`, `'PT30M'`, `PT24H`, or `'forever'` | `RETAIN FOR <duration>` / `RETAIN FOREVER` |
  | `sink` | The binding under `pravaha.sinks` the changelog is written to | `WRITING TO <sink>`, `pravaha register --sink` |
  | `keys` | The view's key columns, comma-separated, as the `SELECT` list spells them | `KEYED BY (...)` (`pravaha register --keys` takes ordinals) |

  On `CREATE OR REPLACE` it takes a replacement's instead — `backfill`, `backfill.rate.limit`,
  `cutover`, `rollback.retention` (§8.1). Either way an option the engine does not build is refused
  by name with the list of the ones that do — `PRV-8017` on a registration, `PRV-4018` on a
  replacement — and so is the same setting said twice (`RETAIN FOR` and `retention`, or two
  different sinks). An option's name may be bare, or quoted
  as the design writes it: `WITH ('retention' = '24h')`. The short duration form is quoted, because
  `24h` is a number followed by a word to any lexer.

  Not built, and refused rather than accepted and dropped: the design's `consistency.default` —
  consistency is chosen by the reader and per read (§4) — and `parallelism` and `allowed.lateness`,
  which are the engine's to decide.

**What each answers.** `CREATE`: one row — `name`, `state`, `fingerprint`, `sink`. `DROP`, `PAUSE`,
`RESUME`: `name` and the state it is now in (`DROPPED`, `PAUSED`, `RUNNING`). `SHOW CONTINUOUS
QUERIES`: a row per name you may learn exists — `name`, `state`, `sql`, `fingerprint`, `rows_in`
(`-1` when withheld), `key_columns` (by name), `sink`, `retention` — filtered exactly as `pravaha
queries` and `GET /api/v1/queries` are. A JDBC or ADBC client's `executeUpdate` runs them too, and is
answered with the row count.

**Authorization is the registration's.** A `CREATE` is authorized as `pravaha register` is — may
this principal register, and may it read every stream the query reads — and `DROP`, `PAUSE` and
`RESUME` as the actions are, by the policy's `mayAdminister`, audited under the same verbs. The
spelling changes nothing about who may do what.

**The design's spellings are accepted where they mean the same thing** (design §11.2): `INTO sink`
for `WRITING TO sink`, `INDEXED BY (...)` for `KEYED BY (...)`, `SERVE AS VIEW name` when it names
the query itself, and a trailing `EMIT CHANGES` — which every continuous query does. Two things in
that design are still refused by name rather than ignored: `EMIT CHANGES WITH (...)` (`PRV-2072`),
because every continuous query emits its changes and its options are a query's, not a clause's; and
a `SERVE AS VIEW` naming something other than the query, because here a query and its view are one
name. `CREATE OR REPLACE` was on that list until ADR-046, and `INDEXED BY ... RANGE` and a
`WITH (...)` list on a plain `CREATE` until B8 — what replaced them is not a looser refusal but a
narrower one, with its own code and its own sentence
([ADR-049](adr/049-an-ordered-index-over-the-keys-last-column.md)).

**Why this grammar.** `KEYED BY` says what the clause does — a second row with the same key replaces
the first — where `INDEXED BY` reads as an index beside the view, which is not what it is: it names
the key, and `RANGE` is what asks for the index. `WRITING TO` reads as what happens and cannot be
mistaken for `INSERT INTO`, which stays refused (§15,
[ADR-043](adr/043-how-a-continuous-query-names-its-sink.md)): it carries neither the name the query
is managed and read by — which is not the sink's, and would collide the moment a second query wrote
to the same sink — nor the key the view needs, and a view keyed on a guess conflates rows that were
never the same row. The refusal names the three ways to say it instead. The statements are recognised before
Calcite, by their leading words, rather than added to a fork of Calcite's grammar: only the `SELECT`
needs a SQL parser, and it gets the real one, exactly as a registration argument does. A statement
that starts as one of these and goes wrong is refused with `PRV-2070`, the shape that was expected,
and the line and column where reading stopped — never with Calcite's syntax error about a word it
has never heard of.

**Where they run.** Over Flight SQL — so every SDK, the CLI and the console — and in the embedded
engine. The PostgreSQL gateway is read-only and refuses all five with `PRV-6211`
(SQLSTATE `25006`).

---

## 11. Projection — `SELECT`

| | | |
|---|---|---|
| `SELECT a, b`, `SELECT *` | ✅ | |
| Column aliases — `amount AS a` | ✅ | |
| Table aliases — `FROM txn AS t`, or `FROM txn t` | ✅ | With or without `AS` |
| Qualified columns — `t.amount`, `t.*` | ✅ | In `SELECT`, `WHERE`, `GROUP BY` and join conditions |
| Integer and floating arithmetic — `amount * 2 + 1`, `price / 2` | ✅ | |
| Modulo — `amount % 3`, `MOD(price, 2)` | ✅ | Over integers and over floating point alike. The remainder takes the sign of the dividend, so `-50 % 3` is `-2` and `-50.0 % 1.0` is `-0.0` |
| `CAST(x AS DOUBLE)` | ✅ | Between numeric types, and only between numeric types. A cast to or from text is refused `PRV-2021`, and that is now true of a literal as well as of a column: `CAST(5 AS VARCHAR)` used to be folded away by the optimiser before the refusal could fire (TY-23). `CAST(NULL AS VARCHAR)` is accepted — it converts nothing, and it is what the bare-`NULL` refusal tells you to write. A cast between the same type at the same width is a no-op and passes through |
| Literals — `SELECT 1` | ✅ | |
| `CASE WHEN … THEN … END` | ✅ | Any number of branches, with or without `ELSE`. Only the branch taken is evaluated, so `CASE WHEN n = 0 THEN 0 ELSE t / n END` does not divide by zero. **Every branch must produce the same type**, and the whole `CASE` is typed by SQL rather than by this engine: `CASE WHEN c THEN 1 ELSE 1.5 END` is `DECIMAL` to SQL and is refused `PRV-2021` exactly as `amount * 1.5` is, rather than being widened to a double behind your back. It used to throw an uncoded `IllegalArgumentException` (TY-4) |
| A boolean-valued expression — `CASE WHEN c THEN TRUE ELSE FALSE END`, `amount > 50`, `status IS NULL` | ✅ | Only where the result cannot be UNKNOWN. See below |
| Scalar functions — `ABS`, `FLOOR`, `CEIL`, `ROUND` | ✅ | One argument. `ROUND(x, 2)` is refused: rounding to decimal places is not built |
| Numeric functions beyond those four | ❌ | `PRV-2021` |
| String literals — `SELECT 'flagged'` | ✅ | |
| `UPPER`, `LOWER` | ✅ | Converted in the root locale, so the answer does not depend on the machine the lane runs on |
| `TRIM(x)` | ✅ | Strips spaces from both ends. `TRIM(LEADING …)` and a trim character other than a space are refused |
| String concatenation — `a \|\| b` | ✅ | Any length of chain. **Null concatenated with anything is null**, not an empty string. Both sides must be text: there is no conversion from a number, a boolean or a date to text anywhere in this engine, so `s \|\| 5`, `s \|\| CAST(5 AS VARCHAR)` and `s \|\| amount` are all refused `PRV-2021`. The first two used to succeed and the third did not, which made the rule depend on how the value was spelled (TY-23) |
| `SUBSTRING(s FROM start)`, `… FOR length` | ✅ | Positions are 1-based and counted in code points, so a substring never splits an emoji in half |
| Other string functions — `REPLACE`, `POSITION`, `LPAD` | ❌ | `PRV-2021`, or `PRV-2002` where SQL's own validator does not recognise the name first — `LTRIM`, `RTRIM` and `CONCAT` arrive that way. Either refusal now ends with what this engine does evaluate (TY-24) |
| `SELECT DISTINCT` | ❌ | `PRV-2050` — it is a `GROUP BY` over an unbounded key space; see §13 |

**A projected boolean holds two values, and SQL comparisons have three.** `SELECT amount > 50`
plans, because `amount` is `NOT NULL` and the comparison is therefore TRUE or FALSE. `SELECT status
= 'ok'` over a nullable `status` is refused with `PRV-2021`: the answer for a row whose `status` is
NULL is UNKNOWN, and writing it into a boolean column would report it as `false` — a wrong answer
under a success exit code rather than a missing feature. Say which you mean and it plans:
`(status = 'ok') IS TRUE` reads UNKNOWN as `false`, `(status = 'ok') IS NOT FALSE` reads it as `true`, and
`CASE WHEN status = 'ok' THEN TRUE ELSE FALSE END` is the long form of the first. `IS NULL`, `IS NOT
NULL`, `IS TRUE`, `IS FALSE`, `IS NOT TRUE` and `IS NOT FALSE` are total by definition and project
freely.

**A guard does not make a comparison total, as far as the planner can tell.** `status IS NOT NULL AND
status = 'ok'` can never be UNKNOWN — a NULL `status` makes the left side FALSE, and FALSE AND
anything is FALSE — but the check is made on the nullability of the expression's operands, not by
reasoning through the `AND`, so it is refused with `PRV-2021` all the same. So is `COALESCE(status =
'ok', FALSE)`. The refusal used to recommend the first of these (HLP-14); it now recommends `IS TRUE`
or `IS NOT FALSE`.

A `CASE` may produce text as readily as a number, but every branch must produce the *same* type —
with one exception that surprises people: `CASE WHEN … THEN 'big' ELSE 0 END` is accepted, because
Calcite's validator coerces the `0` to the string `'0'` before Pravaha sees the query. The column is
text. A `SUM` over it will not plan.

Evaluating a string allocates one, where the numeric path does not. The zero-copy comparison the
engine uses elsewhere works on UTF-8 slices, and the interpreted pipeline already materialises
strings to compare them — so this costs what the engine already spends, rather than adding a new
cost. It is the reason a text projection is not the place to put your hottest query.

**Table aliases behave as SQL says.** An alias may be used with or without `AS`; columns may be
written qualified or bare while an alias is in scope; and an alias may shadow the name of a different
registered stream — inside `FROM txn AS other`, `other` means `txn`. The query on the front of the
README uses aliases on both sides of a join, and that is the one exercised against a real Aerospike.

**Name your aggregate columns.** An output column takes the alias if there is one, the column's own
name if not — and for an unaliased aggregate there is no name to take, so `COUNT(*)` comes back as
`EXPR$2`. Write `COUNT(*) AS txn_count` unless you enjoy reading `EXPR$2` in a dashboard. A qualified
column keeps its bare name: `SELECT t.amount` produces a column called `amount`, not `t.amount`.

**A row cannot have more than 64 output columns — `PRV-3030`.** `SELECT *` over a table with more
than 64 columns, `SELECT a, b, c, …` naming that many, or a join or aggregate whose *output* is that
wide, all hit the same ceiling: every row is built by `BinaryRowWriter`, which tracks which fields
have been written in a single 64-bit `long` — one bit per field — and cannot represent a 65th. It is
architectural, not a setting to raise, and it binds the width of the answer regardless of which
clause made it wide. `pravaha validate` accepts a 1,000-column projection without complaint, because
nothing writes a row during validation; the ceiling is only met once rows start moving, which is the
worst time to meet it. If a query is this wide, split it into several narrower ones. See
[`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) for the exact refusal.

---

## 12. Filtering — `WHERE` and `HAVING`

| | | |
|---|---|---|
| `=`, `<>`, `<`, `<=`, `>`, `>=` | ✅ | |
| `AND`, `OR`, `NOT` | ✅ | `NOT` is pushed down at compile time by De Morgan |
| `IN (a, b, c)` | ✅ | Expanded to a chain of equalities |
| `BETWEEN a AND b` | ✅ | Expanded to `>= AND <=` |
| `IS NULL`, `IS NOT NULL` | ✅ | Over a column, and over any expression — `(CASE WHEN … END) IS NULL`, `(amount * 2) IS NULL`. Only the column form was compiled until TY-5 |
| Arithmetic in a predicate — `amount * 2 > 100` | ✅ | |
| A bare boolean column — `WHERE flagged` | ✅ | |
| Column against column — `WHERE a > b` | ✅ | Both numeric |
| `HAVING` | ✅ | A filter above the aggregate |
| `LIKE`, `NOT LIKE` | ✅ | Against a literal pattern. The pattern is compiled once when the query is registered, not once per row — so `LIKE status` is refused |
| `LIKE … ESCAPE` | ❌ | `PRV-2021`. Without it, `%` and `_` are always wildcards and cannot be matched literally |
| Text ordering — `WHERE status > user_id` | ❌ | `PRV-2021`. `>` on text needs a collation, and assuming one gives wrong answers that look right. `=` and `<>` on text do work |
| Comparing text to a number | ❌ | `PRV-2021` |
| Comparing a `BYTES`, `ARRAY`, `MAP` or `ROW` column | ❌ | `PRV-2021`, naming the column. A `BYTES` column can be selected, null-checked and used as a join key; comparing one in a predicate is not built. The `BYTES` refusal used to name only the implicit cast SQL inserted (TY-14) |

Three-valued logic is honoured throughout: a comparison with NULL is UNKNOWN, and a filter keeps
only rows where the predicate is TRUE.

**A predicate can be too large to compile — `PRV-2011`.** An `AND` or `OR` chain of a few thousand
terms — the shape a generated query or an `IN`-list rewrite produces — can be too large for the
planner to convert. The refusal says which operator and how many terms, not the predicate itself.
Shorter chains are fine; the threshold depends on the shape of the terms, not just their count.
Rewrite the filter as a range comparison, or join against a table of values instead of a long `IN`.

---

## 13. Aggregation

| | | |
|---|---|---|
| Global `COUNT(*)` | ✅ | One group, so bounded |
| `TUMBLE` windows | ✅ | |
| `HOP` (sliding) windows | ✅ | |
| `COUNT`, `SUM`, `MIN`, `MAX`, `AVG` | ✅ | Over integer columns. `SUM` of a `TINYINT`, `SMALLINT` or `INT` — a column, or an expression such as `CASE WHEN tier = 'silver' THEN 1 ELSE 0 END` — is a `BIGINT`; `MIN`, `MAX` and `AVG` keep their argument's type. Until HLP-1 a `SUM` of an `INT` planned an `INT` column the 64-bit accumulator could not write, and the first row stopped the query, as it did for `MIN`, `MAX` and `AVG` of one. `SUM`, `AVG`, `MIN` **and `MAX`** over a `FLOAT32`/`FLOAT64` column are all refused `PRV-2020`: every accumulator is a 64-bit integer, whatever the column's type, and before the refusal a float aggregate produced no rows under a success status. Only `COUNT` of a float column plans, because it never reads the value. The refusal suggests `SUM(CAST(price AS BIGINT))`, which works if the rounding is acceptable |
| `SUM` or `AVG` over a text column | ❌ | `PRV-2020`, naming the column — the same code the float-accumulator refusal carries, because it is the same kind of answer: the accumulator takes a number and this operand is not one. SQL would otherwise cast the column to `DECIMAL(38,19)` on your behalf, and the refusal you got was about decimal arithmetic in a ledger rather than about summing text (TY-16) |
| Renaming a window column — `window_end AS hour_end` | ❌ | `PRV-2050`: the window columns must keep their names. Project `window_start` and `window_end` under their own names (or `window_end AS window_end`) and rename them downstream |
| `COUNT(DISTINCT x)` | ✅ | Windowed. Over an unwindowed stream it is refused `PRV-2050`, like any other unbounded key space |
| Aggregate over an expression — `SUM(amount * 2)` | ✅ | |
| `HAVING` on an aggregate | ✅ | |
| `GROUP BY key` **without** a window, over a stream | ❌ | `PRV-2050` — unbounded state |
| `GROUP BY key` **without** a window, over a view | ✅ | The scan ends, so the state is bounded by it |
| `SESSION` windows | ❌ | `PRV-2020` — implemented in the runtime, no SQL surface yet |

### Should a continuous query aggregate at all?

Yes — **windowed aggregation is the point of the engine**, not a feature bolted onto it. A continuous
query that only filters and projects is a `grep` with extra steps, and nobody needs incremental
computation for that. The value is in maintaining `SUM`, `COUNT` and `COUNT(DISTINCT)` over a window
and keeping them correct as data arrives, late data included. The query on the front of the README is
exactly that shape, and it is what the Z-set algebra in [`CONCEPTS.md`](CONCEPTS.md) §4 exists to
make incremental.

So the answer splits, and the split is not a compromise:

- **Windowed `GROUP BY` — yes.** Supported, incremental, with bounded state and late-data correction.
  This is the primary use.
- **Unwindowed keyed `GROUP BY` — no, and it should stay that way.** Over a stream with no end, its
  state grows with the number of distinct keys and never shrinks. There is no configuration that
  fixes it and no machine large enough to outrun it.

### Why an unwindowed `GROUP BY` is refused

`SELECT user_id, COUNT(*) FROM txn GROUP BY user_id` is refused, and this surprises people, so it is
worth being clear that it is a decision and not a gap.

A stream has no end. That aggregate has to keep one entry per distinct `user_id` **forever**, so its
memory is a function of how many users ever appear — not of anything the operator configured. It
does not fail on the day it is deployed. It fails months later, in production, and the query looks
innocent in the review that approved it.

Adding a window makes the state bounded, because a window closes:

```sql
SELECT window_start, window_end, user_id, COUNT(*)
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '10' SECOND))
GROUP BY window_start, window_end, user_id
```

Grouping by a windowed stream *without* putting `window_start` and `window_end` in the `GROUP BY` is
refused too — that is the unbounded case wearing a window's clothes.

**And over a bounded read of a view?** Supported. `SELECT tier, COUNT(*) FROM user_volume GROUP BY
tier` is what a dashboard asks, the scan ends, and `KeyedAggregate` answers it — including `SUM`,
`MIN`, `MAX`, `AVG`, `COUNT(DISTINCT)`, multiple group columns, `HAVING`, and parameters.

Two details that follow SQL rather than convenience. **NULL is a group**, not a row that vanishes —
unlike a comparison, where NULL is UNKNOWN — so rows with no `tier` gather under one NULL key. And
`COUNT(DISTINCT x)` does not count NULL.

"Bounded by the scan" is only true if the scan is, so the operator caps distinct groups and refuses
rather than growing. Row order is stable between identical reads: there is no `ORDER BY` to make it
meaningful, but an answer that shuffles is one somebody wastes an afternoon on.

---

## 14. Joins — what runs

| | | |
|---|---|---|
| `INNER JOIN` on an equality | ✅ | Symmetric hash join, incremental both ways |
| Multi-column equi-join | ✅ | `ON a.x = b.x AND a.y = b.y` |
| Equi-join with a time bound | ✅ | `AND a.t BETWEEN b.t - INTERVAL '5' MINUTE AND b.t`. Decides which pairs match **and** how long state is kept |
| One-sided time bound | ✅ | `AND a.t >= b.t - INTERVAL '30' SECOND`. The unstated side closes at zero |
| Time bound with no equality | ❌ | `PRV-2020` — a window narrows which pairs count but still leaves every row a candidate for every other inside it |
| Time bound in months or years | ❌ | A month has no fixed length; guessing 30 days is wrong twice a year |
| Three-way and deeper | ✅ | Between *distinct* streams |
| Self join — one stream on both sides | ❌ | Rows enter a join by stream name, which cannot say which side a row is for |
| Lookup join against a dimension table | ✅ | Async, on virtual threads, ordered output — §7 |
| A filter on a looked-up column, over an inner lookup join — `JOIN users FOR SYSTEM_TIME AS OF … AS u … WHERE u.tier = 'gold'` | ❌ | `PRV-2020`, with a message about correlated subqueries that does not describe the query. Write the same join as `LEFT JOIN … WHERE u.tier = 'gold'`: the filter drops the null-padded rows, so it keeps exactly the rows the inner form would have, and it plans and runs (`LookupJoinTest`). A filter on the stream's own columns works with either |
| A condition on a looked-up column in the `ON` clause — `ON u.user_id = t.user_id AND u.tier = 'gold'` | ❌ | `PRV-2020`. A lookup's `ON` is its key, and only equalities between a stream column and a lookup column are keys; filter in `WHERE`, as above |
| `LEFT JOIN` **with a time bound** | ✅ | The null-padded row is emitted when the watermark passes the window, once, never retracted |
| `LEFT JOIN` without a time bound | ❌ | `PRV-2020` — there is no moment at which an unmatched row can be declared unmatched, so every one is held for the life of the process |
| `RIGHT` / `FULL OUTER` | ❌ | `PRV-2020` — swap the inputs and use `LEFT` |
| `CROSS JOIN` | ❌ | `PRV-2020` |
| Non-equi join — `ON a.x > b.x` | ❌ | `PRV-2020`. An inequality between *timestamp* columns is a time bound and is supported; between anything else it is a cross product |

An outer join between streams has to hold every unmatched row indefinitely, in case its partner
arrives later — the same unbounded-state problem as an unwindowed `GROUP BY`, which is why it is
refused rather than shipped and hoped for. A cross join has no key to partition on, so it cannot be
made incremental at all.

**In practice this covers the joins people write.** A stream joined to another stream on a key, and a
stream enriched from a dimension table, are the two shapes that make up nearly all of it.

A self-join is refused later than the rest — when the pipeline is built rather than when the query is
planned — and it is the one refusal that arrives without a `PRV-` code. Both are worth fixing; until
then, the message says plainly what is wrong.

---

## 15. Sorting, sets and subqueries

| | | |
|---|---|---|
| Derived tables — `FROM (SELECT …) x` | ✅ | |
| `WITH` (common table expressions) | ✅ | |
| `SELECT STREAM` | ✅ | Accepted as a synonym; every query here is a streaming query |
| `ORDER BY` | ❌ | `PRV-2020`, in every shape it can be written in — including one inside a derived table with no `FETCH`, which the optimiser deletes as harmless before any plan exists. That one planned and ran, exit 0, while every other spelling was refused (TY-20). The refusal is made on the statement now, not on the plan |
| `LIMIT` / `OFFSET` | ❌ | `PRV-2020` |
| `UNION`, `UNION ALL`, `INTERSECT`, `EXCEPT` | ❌ | `PRV-2020` |
| `IN (subquery)`, uncorrelated `EXISTS`, uncorrelated scalar subqueries | ❌ | `PRV-2021` |
| Correlated subqueries — an `EXISTS` or a scalar subquery naming a column of the outer row | ❌ | `PRV-2020`, and the refusal names the one correlated form that does run: `JOIN dim FOR SYSTEM_TIME AS OF <time>`. Until X-7 was fixed the two shapes anybody writes were answered by the expression compiler's generic arms instead, so the refusal that names the alternative was unreachable |
| Window functions — `ROW_NUMBER() OVER (…)` | ❌ | `PRV-2021` |
| `VALUES` | ❌ | `PRV-2020` |
| `INSERT`, `UPDATE`, `DELETE`, `MERGE` | ❌ | `PRV-2020` — Pravaha answers questions; sinks write results, and there is nothing here whose rows a statement may edit in place. `INSERT INTO <sink> SELECT` carries neither the query's name nor its key, so it is refused rather than read as a registration: write `CREATE CONTINUOUS QUERY <name> KEYED BY (...) WRITING TO <sink> AS <select>`, or `WITH (sink = '<sink>')`, or `pravaha register --sink` (§10.1) |

Note what `ORDER BY` means over a stream: a total order over rows that have not all arrived. It is
meaningful over a *bounded* read of a maintained view, and that is where it would land if it is
added — not over a continuous query.

---

## 16. Types

Supported in expressions: `BOOLEAN`, `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT`, `REAL`, `DOUBLE`,
`VARCHAR`, `VARBINARY`, `DATE`, `TIME`, `TIMESTAMP`.

**Correction, and then a correction to the correction.** A QA round on 2026-09-14 found that
`VARBINARY` (`BYTES`) and `TIME` were declarable and computed correctly but **crashed when a non-null
value was serialised to a real client** — a `ClassCastException` in each case, because
`ArrowSchemas.write` had not been updated to match a since-fixed `arrowTypeOf`. That paragraph stood
here after both were fixed, which made this document describe two live defects that no longer
existed.

**Both are fixed** (`TY-17`, `TY-18`). `ArrowSchemas.write` now writes `BYTES` through a
`VarBinaryVector` and `TIME` through a `TimeNanoVector`, each with its own case rather than sharing
one, and `JavaSdkQueryTest` covers every type on the wire. Every type in the list above serialises.

The one caveat that is still true: the **PostgreSQL wire gateway refuses `BYTES` and `TIME` by
name** rather than encoding them, and refuses them *before* sending a `RowDescription` so a client
gets a clean error instead of a truncated result set it might treat as complete. That is a gap in
that gateway's type mapping, not in the engine — Arrow Flight carries both.

`DECIMAL` is refused rather than sent as a floating-point number, because the rounding decision
belongs to whoever owns the ledger and not to a serialiser. Year–month intervals (`INTERVAL '1'
MONTH`) are refused because a month is not a fixed length of time; day–time intervals work and are
what windows use.

**`DECIMAL(p,s)` can be declared through the schema string** (TY-7, fixed). `--schema`,
`--out-schema`, `pravaha.streams.*.schema` and `POST /api/v1/streams` share one grammar,
`name:TYPE,name:TYPE`, and it used to be split on every comma before any type was parsed — so
`amt:DECIMAL(10,2)` was cut at its own comma and failed as `unknown type 'DECIMAL(10'`, while the
refusal went on listing `DECIMAL(p,s)` as supported. The split is paren-aware now. A decimal column
is carried through scans, filters and projections correctly; what is still not built is arithmetic
over it.

**A schema string that will not parse is `PRV-1028`, and it names the stream and the column.** It
was `PRV-5040`, the filesystem plugin's decode code, because that is where the parser lives — which
put a caller's typo in the plugin series and made `POST /api/v1/streams` answer `500 the server is
broken` to a request the caller could fix themselves (TY-8). It is a configuration code now, so the
same mistake answers `400`. The message reads
`stream 'd', column 'amt': unknown type 'DECIMAL'`; it used to name neither, so an operator whose
node refused to start had one sentence and every declared stream to check it against (TY-9).
`PRV-5040` still means what it always meant: a line of data a file could not decode.

**An `ARRAY`, `MAP` or `ROW` column can be declared and cannot be selected.** All three reach the
planner as SQL's `ANY`, and projecting one is refused `PRV-2021` naming the column and saying which
Pravaha types arrive that way — the refusal used to name only `ANY`, a word nobody wrote (TY-10).

---

## 17. What to do when something here is refused

1. **Read the message.** Every `PRV-` refusal says what is unsupported and, where there is one, what
   to write instead.
2. **If it is an unbounded-state refusal (`PRV-2050`), add a window.** That is almost always the
   right answer, and it is usually what was meant.
3. **If it says the source repeats rows (`PRV-2042`), fix the binding, not the SQL.** Set
   `deletes: detect` on the `cassandra` or `aerospike` binding the message names, which makes its
   passes an exact changelog (§2.1); for `jdbc`, see `watermark.moves.on.update`. A keyed view of the
   rows needs neither.
4. **If the work is genuinely not streaming** — a total order, a set difference, an ad-hoc join
   across two stores — that is what ADR-030 tier 4 puts out of scope on purpose. Pravaha maintains
   the answer to a question asked in advance; a query engine answers questions asked just now, and
   trying to be both is how a system becomes bad at each.

---

## 18. Common surprises, and what they actually are

| Symptom | Cause |
|---|---|
| Query is `RUNNING`, view is empty, rows are arriving | `out-of-orderness` larger than the data's span, or a bounded source that has read its last window. It is no longer a missing `event-time`: that is refused at registration with `PRV-2002` (§2) |
| A windowed query that used to register is refused with `PRV-2002` | Its stream declares no `event-time`, so it could never have emitted. Declare the column (§2) |
| Windows close but lag real time badly | `out-of-orderness` larger than the data needs, or a slow input dragging a join's watermark down |
| Two queries that look identical are not shared | Different `--keys`, different retention, or `AND` operands written in a different order (§3) |
| A count went down | A correction: a late row retracted a result and replaced it. Working as designed (§6) |
| `SELECT` refuses with `PRV-8004` | The query behind the view failed; the rows are stale, and saying so is the point (§8) |
| A view holds fewer rows than expected | Retention. It defaults to forever now, but an explicit one evicts by event time |
| `GROUP BY` works on a view and is refused on a stream | Deliberate, and the reason is the input rather than the query (§13) |
| `COUNT(*)` over a Cassandra or Aerospike binding is refused, a projection of it is not | The default `deletes: ignore` repeats rows, which a keyed view absorbs and a count does not (`PRV-2042`, §2.1) |

**When the symptom is not on that list**, the query is doing exactly what it was asked and the
question is which row and which operator. Fork it: a debug session replays the query from one of
its checkpoints, one row at a time, reporting every operator's rows in and out beside the view's
changes — which is what tells a filter that rejected the row apart from an aggregate that produced
a zero delta. Every sink is disabled and nothing can read the fork, so the live query is
untouched. See [`USER_GUIDE.md` §11](USER_GUIDE.md#11-the-time-travel-debugger) and
[ADR-048](adr/048-a-debug-fork-is-a-second-computation-nothing-can-read.md); the incident exports
as a JUnit test that compiles and passes.

---

## 19. Error codes

| Code | Means |
|---|---|
| `PRV-1028` | A `name:TYPE,name:TYPE` schema string that will not parse — an entry with no colon, or a type nothing knows. Names the stream and the column — §16 |
| `PRV-2001` | Syntax error, with Calcite's line and column preserved |
| `PRV-2002` | Validation failed — an unknown column, a type mismatch |
| `PRV-2003` | The query names a stream that is not registered |
| `PRV-2011` | A predicate (a long `AND`/`OR` chain, usually) is too large for the planner to convert — §12 |
| `PRV-2020` | A relational operator Pravaha cannot execute, including `INSERT`, `UPDATE`, `DELETE` and `MERGE` — there is no DML surface, and `INSERT INTO <sink> SELECT` is not a registration: it carries neither the query's name nor its key — §10.1 |
| `PRV-2021` | An expression or function Pravaha cannot compile |
| `PRV-2041` | The query revises its answer and the sink it names can only append — §4 |
| `PRV-2042` | The query's answer depends on how many times a row arrived — an aggregate, a join, a sink that cannot upsert — and its source repeats rows (`cassandra` or `aerospike` with `deletes: ignore`, a `jdbc` poll an update can re-read); set `deletes: detect` on the binding — §2.1 |
| `PRV-2050` | The query's state would grow without bound |
| `PRV-2060`–`PRV-2063` | Parameter binding — see [ADR-032](adr/032-parameters-are-values-not-queries.md) |
| `PRV-2070` | A `CREATE`/`DROP`/`PAUSE`/`RESUME CONTINUOUS QUERY` or `SHOW CONTINUOUS QUERIES` without that statement's shape — §10.1 |
| `PRV-2071` | `KEYED BY` names a column the query does not produce, or one twice — §10.1 |
| `PRV-2072` | A clause of the design's `CREATE CONTINUOUS QUERY` that is not built: `EMIT CHANGES WITH (...)`, a `SERVE AS VIEW` naming another view — §10.1 |
| `PRV-2073` | `RANGE (column)` over a column this engine has no total order for: text, `FLOAT`, `DECIMAL`, `BYTES`, `BOOLEAN` — §10.1 |
| `PRV-8017` | A `WITH (...)` option this engine does not build, or one said twice — §10.1 |
| `PRV-4013` | A backfill reached the end of the history without reaching its seam — §8.1 |
| `PRV-4014` | A cutover before the new version had caught up, or at a position the two do not share — §8.1 |
| `PRV-4016` | No replacement of that name is in flight — §8.1 |
| `PRV-4017` | A second replacement of one name, a replacement by the same computation, or a drop during one — §8.1 |
| `PRV-4018` | A stream that cannot be backfilled, an option that is not built, or a rate above the ceiling — §8.1 |
| `PRV-4019` | A subscription ended because the view it followed was replaced — §8.1 |
| `PRV-3030` | A row's output is wider than 64 columns — §11 |
| `PRV-6211` | A continuous-query statement sent to the read-only PostgreSQL gateway — §10.1 |
| `PRV-8009` | A sink refused a batch and was detached from the query; the view carries on — §4 |
| `PRV-8010` | The sink's configured columns, or its key, do not match the query's output or `--keys` — §4 |

---

## 20. Where to go next

| You want | Read |
|---|---|
| The ideas underneath | [`CONCEPTS.md`](CONCEPTS.md) |
| Getting a node running | [`QUICKSTART.md`](QUICKSTART.md) |
| Operating one | [`OPERATIONS.md`](OPERATIONS.md) |
| Adding a data source | [`CONNECTORS.md`](CONNECTORS.md) |
| How a query executes | [`EXECUTION_MODEL.md`](EXECUTION_MODEL.md) |
| What an error code means | [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) |
