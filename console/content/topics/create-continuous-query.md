---
title: CREATE CONTINUOUS QUERY and the management statements
slug: create-continuous-query
category: sql
order: 30
icon: plus-square
summary: "Registering a query in SQL: KEYED BY, WRITING TO, RETAIN FOR — and DROP, PAUSE, RESUME and SHOW. The full grammar, what each statement answers, and every way one is refused."
badge: STATEMENTS
audience: Analysts and developers
keywords: [create continuous query, queries on queries, over a view, follows, dependants, PRV-2075, PRV-8024, PRV-8025, PRV-8026, PRV-8027, keyed by, range, index, writing to, retain for, retain forever, with, options, drop, pause, resume, show continuous queries, indexed by, into, emit changes, insert into, PRV-2070, PRV-2071, PRV-2072, PRV-2073, PRV-8017, PRV-6211]
guide: continuous-queries#101-the-statements-that-register-and-manage-queries
related: [query-lifecycle, views-and-keys, sinks-overview, sql-reference, sharing]
---

A continuous query is not a request: you register it once and the engine keeps its answer — the
**view** — for as long as the name is registered. `CREATE CONTINUOUS QUERY` is how you register one
in SQL, from anywhere SQL arrives: the workbench, `pravaha query --sql`, either SDK's `query()`, any
Flight SQL client (JDBC, ADBC), and the embedded engine's `query(sql)`.

Four more statements manage what is running: `DROP`, `PAUSE`, `RESUME CONTINUOUS QUERY` and
`SHOW CONTINUOUS QUERIES`. They are control statements, not questions — nothing in them is planned
except the `SELECT` a `CREATE` carries, which is planned exactly as a registration argument would be.

## The grammar

```text
CREATE CONTINUOUS QUERY name
    KEYED BY (column [, column]...)
    [WRITING TO sink]
    [RETAIN FOR duration | RETAIN FOREVER]
AS select

DROP   CONTINUOUS QUERY name
PAUSE  CONTINUOUS QUERY name
RESUME CONTINUOUS QUERY name
SHOW   CONTINUOUS QUERIES
```

| Part | Required | What it does |
|---|---|---|
| `name` | yes | The name clients read: `SELECT ... FROM name`. Plain, or double-quoted (`"audit-log"`, with `""` for a quote). Keeps its case |
| `KEYED BY (...)` | **yes** | The view's key, by **output column name**. A second row with the same key replaces the first. A view with no key is a log |
| `WRITING TO sink` | no | Also write every commit to a sink bound under `pravaha.sinks.<sink>` |
| `RETAIN FOR d` / `RETAIN FOREVER` | no | How much event time the view keeps. Omitted: **forever** — a server has no setting that changes that |
| `AS select` | yes | The query itself, kept exactly as you wrote it for listings |

Rules that apply to every statement:

- **Keywords in any case.** `create continuous query` is fine.
- **The clauses before `AS` in any order, each once.** `KEYED BY` is required.
- **One trailing semicolon** is accepted, and so are comments.
- **A duration** is ISO-8601, bare or quoted — `PT24H`, `'P7D'` — or an interval in one unit:
  `INTERVAL '24' HOUR` with `SECOND`, `MINUTE`, `HOUR`, `DAY` or `WEEK`. A month is not a fixed
  length of event time and is refused.

## A complete registration

An hourly spend per merchant, kept for a week:

```sql
CREATE CONTINUOUS QUERY merchant_hourly
    KEYED BY (merchant, window_end)
    RETAIN FOR P7D
AS
SELECT merchant, window_start, window_end,
       COUNT(*) AS txn_count,
       SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
GROUP BY merchant, window_start, window_end;
```

Run it from a shell:

```bash
pravaha query --sql "$(cat merchant_hourly.sql)"
```

It answers with one row — the name, its state, the fingerprint of the computation the name landed
on (the first 12 hex digits of a SHA-256 over the normalised plan), and the sink:

```text
name	state	fingerprint	sink
merchant_hourly	RUNNING	3f9c1a07b2d4	NULL
1 row
```

(The fingerprint shown is illustrative; yours is whatever your plan hashes to.)

Then read the view it maintains — this is a separate, cheap operation, not the query running again:

<!-- sql: read -->
```sql
SELECT merchant, window_end, txn_count, spend
FROM merchant_hourly
WHERE spend > 10000
```

### The same thing as a registration call

`KEYED BY` names columns; the registration APIs take **output ordinals** (0-based) instead. Both
reach the same registry, with the same authorization and audit:

```bash
pravaha register --name merchant_hourly --sql-file merchant_hourly_select.sql --keys 0,2 --retain P7D
```

```python
client.register("merchant_hourly", open("merchant_hourly_select.sql").read(), [0, 2], retention="P7D")
```

```text
registered merchant_hourly  state=RUNNING  fingerprint=3f9c1a07b2d4
```

Naming the key by column is the safer spelling: the engine plans the `SELECT` to turn names into
ordinals, so reordering the select list cannot silently change what the key is.

## `KEYED BY` — the view's key

The key decides what a row *replaces*. Get it wrong and the view either conflates rows that should
be distinct, or keeps rows that should have replaced each other.

- **Name output columns**, by alias where the `SELECT` gives one: `SUM(amount) AS spend` is `spend`.
- A name matches its column exactly, or else the one column differing only in case.
- A windowed view is almost always keyed by its group columns **plus** `window_end`, so each window is
  its own row.
- The key is part of the query's identity: two registrations differing only in their key are two
  computations, because the key changes the answer.

A name the `SELECT` does not produce is refused with PRV-2071, and so is a name given twice:

<!-- sql: refused PRV-2071 -->
```sql
CREATE CONTINUOUS QUERY bad_key
    KEYED BY (merchant_name)
AS SELECT merchant, amount FROM txn;
```

<!-- sql: refused PRV-2071 -->
```sql
CREATE CONTINUOUS QUERY twice_keyed
    KEYED BY (txn_id, txn_id)
AS SELECT txn_id, amount FROM txn;
```

A key differing only in case resolves:

```sql
CREATE CONTINUOUS QUERY case_tolerant
    KEYED BY (TXN_ID)
AS SELECT txn_id, amount FROM txn;
```

And a missing key is not a default — it is refused with PRV-2070:

<!-- sql: refused PRV-2070 -->
```sql
CREATE CONTINUOUS QUERY keyless AS SELECT txn_id FROM txn
```

## `WRITING TO` — a sink

```sql
CREATE CONTINUOUS QUERY large_payments
    KEYED BY (txn_id)
    WRITING TO audit_trail
AS
SELECT txn_id, user_id, merchant, amount
FROM txn
WHERE amount > 10000;
```

The view is maintained exactly as without a sink; the sink receives the same commits a subscriber
does, retractions included. Three things are checked **at registration**, before anything opens:

| Check | Refused with | Why |
|---|---|---|
| The `SELECT` list matches the sink's configured columns — order, name, type — and a keyed sink is keyed by exactly `KEYED BY` | PRV-8010 | Otherwise every value lands in another column's place |
| A query that revises its answer is not pointed at an append-only sink | PRV-2041 | Otherwise each row is right and the total is wrong for ever |
| The sink named is bound on this node | the registration fails naming it | |

Over a source that only appends, a filter, a projection, or a tumbling window without lateness never
revises its answer, and may go anywhere; over one that deletes (postgres-cdc, Delta, a Kafka
changelog) even a filter passes retractions on. An unwindowed aggregate over a view, a window with allowed lateness, or a join that can
withdraw a match needs a sink that accepts updates. [Sinks](/help/topics/sinks-overview) has the
binding and the delivery guarantees; a sink that later refuses a batch is detached (PRV-8009) and the
view carries on.

## `RETAIN` — how much event time the view keeps

```sql
CREATE CONTINUOUS QUERY recent_orders
    KEYED BY (order_id)
    RETAIN FOR INTERVAL '24' HOUR
AS SELECT order_id, customer_id, region, amount, event_time FROM orders;
```

```sql
CREATE CONTINUOUS QUERY "audit-log"
    KEYED BY (txn_id)
    RETAIN FOREVER
AS SELECT txn_id, user_id, amount, event_time FROM txn;
```

Rows whose event time falls further behind the committed frontier than the retention are evicted.
Retention is part of the fingerprint and is journalled with the registration, so a restart keeps it.

Each of these is refused with PRV-2070, with the line and column where reading stopped:

<!-- sql: refused PRV-2070 -->
```sql
CREATE CONTINUOUS QUERY monthly
    KEYED BY (txn_id)
    RETAIN FOR INTERVAL '1' MONTH
AS SELECT txn_id FROM txn
```

<!-- sql: refused PRV-2070 -->
```sql
CREATE CONTINUOUS QUERY twice_retained
    KEYED BY (txn_id)
    RETAIN FOR PT24H RETAIN FOREVER
AS SELECT txn_id FROM txn
```

<!-- sql: refused PRV-2070 -->
```sql
CREATE CONTINUOUS QUERY bad_duration
    KEYED BY (txn_id)
    RETAIN FOR banana
AS SELECT txn_id FROM txn
```

```text
PRV-2070  a month is not a fixed length of event time, so a view cannot be told to keep one.
Give it in days: INTERVAL '30' DAY (at line 3, column 16). The statement's shape is: ...
```

## The design's spellings

The system design's spellings are accepted where they mean the same thing: `INDEXED BY (...)` for
`KEYED BY`, `INTO sink` for `WRITING TO`, `SERVE AS VIEW name` when it names the query itself, a
quoted duration, and a trailing `EMIT CHANGES` — which every continuous query does anyway:

```sql
CREATE CONTINUOUS QUERY design_spelling
    INDEXED BY (txn_id)
    INTO audit_trail
    RETAIN FOR 'P7D'
AS SELECT txn_id, amount FROM txn EMIT CHANGES
```

```sql
CREATE CONTINUOUS QUERY served KEYED BY (txn_id) SERVE AS VIEW served AS SELECT txn_id, amount FROM txn
```

`CREATE OR REPLACE` is built (it starts a blue/green replacement — the new version runs beside the
running one and takes the name only when the two have consumed the same input), and on it a
`WITH (...)` list carries that replacement's options:

```sql
CREATE OR REPLACE CONTINUOUS QUERY replaced KEYED BY (txn_id)
    WITH (backfill = 'history', cutover = 'manual')
AS SELECT txn_id, amount FROM txn
```

## `RANGE` — an ordered index over the key's last column

`INDEXED BY (a) RANGE (b)` is the design's spelling for "probe by `a`, scan `b` between bounds",
and the two together are the key. A read that pins the leading columns and bounds the last one then
walks that run instead of the whole view:

```sql
CREATE CONTINUOUS QUERY by_amount
    INDEXED BY (merchant) RANGE (amount)
AS SELECT merchant, amount FROM txn
```

<!-- sql: read -->
```sql
SELECT merchant, amount FROM by_amount WHERE merchant = 'acme' AND amount >= 100 AND amount < 500
```

The column `RANGE` names must be the key's last, and there is one of it. This is the only place
`INDEXED BY` and `KEYED BY` differ: under `INDEXED BY` a column the list does not already end with
is appended to the key, which is what the design's spelling means, and under `KEYED BY` it is
refused with PRV-2070 — a key of `(merchant, amount)` is a different view from one keyed by
`merchant`, and quietly widening a key somebody wrote out changes every count over it.

<!-- sql: refused PRV-2070 -->
```sql
CREATE CONTINUOUS QUERY widened KEYED BY (merchant) RANGE (amount) AS SELECT merchant, amount FROM txn
```

The ordered column must also be one this engine has a total order for: the whole-number and
temporal types. Text needs a collation (which is why `<` on text is refused in a `WHERE` clause at all),
`FLOAT` is IEEE 754 and `NaN` is ordered against nothing, and a `DECIMAL`'s `compareTo` disagrees
with its `equals`. Anything else is PRV-2073, at registration:

<!-- sql: refused PRV-2073 -->
```sql
CREATE CONTINUOUS QUERY by_currency
    INDEXED BY (merchant) RANGE (currency)
AS SELECT merchant, currency FROM txn
```

## `INDEX` — an equality index over a column outside the key

`INDEX (column)` keeps value-to-keys for one column that is not the view's key, so a read that pins
it with `=` or `IN` probes the index instead of walking every row:

```sql
CREATE CONTINUOUS QUERY by_merchant
    KEYED BY (txn_id) INDEX (merchant)
AS SELECT txn_id, merchant, amount FROM txn
```

<!-- sql: read -->
```sql
SELECT txn_id, amount FROM by_merchant WHERE merchant IN ('acme', 'globex')
```

The index is kept in the view's own commit, from the row the view held rather than from the change
that replaced it, so it is never a step behind the view; it is written down with the registration
and comes back after a restart. `WITH (index = 'merchant')` says the same thing. One column per
clause, at most four per view, and it costs one entry per row, bounded by the view's ceiling. A
replacement keeps it, carried to the new version by column name.

`FLOAT`, `DECIMAL` and `BYTES` are refused with PRV-2074 — two values the filter calls equal can be
different stored values (`0.0` and `-0.0`, `1.0` and `1.00`) — and so is the view's whole key, which
is a hash probe already:

<!-- sql: refused PRV-2074 -->
```sql
CREATE CONTINUOUS QUERY by_itself
    KEYED BY (txn_id) INDEX (txn_id)
AS SELECT txn_id, amount FROM txn
```

A predicate on a column that is neither in the key nor indexed is still a scan and a filter.

## `WITH (...)` — a registration's options

On a plain `CREATE` the list carries the arguments a registration already took:

```sql
CREATE CONTINUOUS QUERY kept_a_day
    KEYED BY (merchant)
    WITH ('retention' = '24h', sink = 'audit_trail')
AS SELECT merchant, amount FROM txn
```

| Option | Means | The other way to say it |
|---|---|---|
| `retention` | How long the view keeps a row, in event time: `'24h'`, `'7d'`, `'PT30M'`, `PT24H`, `'forever'` | `RETAIN FOR` / `RETAIN FOREVER` |
| `sink` | The binding the changelog is written to | `WRITING TO <sink>` |
| `keys` | The key columns, comma-separated | `KEYED BY (...)` |
| `index` | The one column an equality index is kept over | `INDEX (column)` |
| `lane` | `'dedicated'`: a lane of its own whatever the node's lane-sharing mode. `'shared'` (the default): the node's `pravaha.lane.multiplex.enabled` decides | — |

A query whose isolation matters more than the megabyte its own inbox costs says so at registration,
and keeps a lane of its own on a node that shares lanes — its failure cannot take a shared lane's
other queries down, and theirs cannot take it down
([Sharing lanes](/help/topics/lanes#keeping-one-query-on-its-own-lane)):

```sql
CREATE CONTINUOUS QUERY settlement_totals
    KEYED BY (txn_id)
    WITH (lane = 'dedicated')
AS SELECT txn_id, amount FROM txn
```

Anything but `'dedicated'` or `'shared'` is refused with PRV-8017. On `CREATE OR REPLACE`, `lane`
moves a running query between a shared lane and one of its own at the cutover — with the SQL
unchanged if that is all you want to change; a replacement that does not say keeps the running
version's lane. `CREATE OR REPLACE` also takes `lane = 'own'`: a lane of its own for the new
version without pinning it, which is what an administrator's [rebalance](/help/topics/lanes#seeing-placements-and-rebalancing-by-hand)
uses; a restart places such a query by the node's mode again.

An option this engine does not build is refused by name with PRV-8017 and the list of the ones that
do — an ignored option is a setting you believe is in force:

<!-- sql: refused PRV-8017 -->
```sql
CREATE CONTINUOUS QUERY tuned KEYED BY (txn_id) WITH ('consistency.default' = 'consistent') AS SELECT txn_id FROM txn
```

Saying the same thing twice is refused rather than decided by which came first:

<!-- sql: refused PRV-8017 -->
```sql
CREATE CONTINUOUS QUERY twice KEYED BY (txn_id) RETAIN FOR PT1H WITH (retention = '24h') AS SELECT txn_id FROM txn
```

## Over another query's answer: queries on queries {#queries-on-queries}

A query's `FROM` may name another registered query. It then **follows that query's answer** — the
rows its view holds, then every change to them — rather than reading its view once, so answers can
be layered: a cleaned feed, an aggregate of it, an alert condition over the aggregate.

```text
CREATE CONTINUOUS QUERY cleaned KEYED BY (user_id)
AS SELECT user_id, region, amount FROM txn WHERE amount > 0

CREATE CONTINUOUS QUERY by_region KEYED BY (region)
AS SELECT region, SUM(amount) AS total, COUNT(*) AS n FROM cleaned GROUP BY region

CREATE CONTINUOUS QUERY big_regions KEYED BY (region)
AS SELECT region, total FROM by_region WHERE total > 100
```

- **What it is fed** is the upstream's answer as a reader sees it: each row once, and for every
  commit the rows that left (weight −1) and the rows that entered (+1). A second row for a user
  replaces the first in `cleaned`, so `by_region` sees an update, not a second row.
- **What runs** over a view: filters, projections, computed columns, and `COUNT`, `SUM` and `AVG`,
  with or without `GROUP BY` — a keyed `GROUP BY` is bounded here by the upstream's key ceiling.
  Windows, joins, top-N, `MIN`, `MAX` and `COUNT(DISTINCT)` are refused with PRV-2075.
- **Exactly once**: each query checkpoints what it has consumed of the one before it, and after a
  restart is fed the difference, whichever of the two checkpointed later.
- **Dropping** a query others read is refused with PRV-8024, naming them — drop them first; there is
  no cascade. Replacing a member of a chain is PRV-8026, a replacement that would read its own
  answer is PRV-8025, and a chain deeper than eight is PRV-8027. `RETAIN FOR` on a query over a view
  is PRV-8026: retention belongs to the upstream.
- **Who may**: registering over `cleaned` needs read access to `cleaned` and to every stream behind
  it, and only views of your own tenant can be read this way. A row filter on one of those streams
  applies to the downstream as to any view.
- **When the upstream stops**: pausing it holds its answer still, and the downstream keeps answering
  at the point it reached; resuming carries on. An upstream that fails stops the downstream's feed
  with PRV-8004 naming it, and the downstream stays `RUNNING`, correct up to where it got.
- **What it is**: a computation of its own — its own lane (or a shared one), checkpoints and sink.
  Its fingerprint includes each upstream's name and fingerprint, so identical SQL over one name
  shares one computation only while that name answers the same computation. A debug fork of a
  downstream is refused (PRV-8012): its input has no history to replay.

The query's page in the console shows what it **follows** and what it is **followed by**, and
`GET /api/v1/queries/{name}` reports the same as `readsFrom` and `dependants` — the latter with the
alerts on the view too, as `ALERT <name>`, each only where you may see it (ALERTDEPS-1).

## What is still refused

Two clauses of the design are refused by name with PRV-2072 rather than ignored:

<!-- sql: refused PRV-2072 -->
```sql
CREATE CONTINUOUS QUERY emits KEYED BY (txn_id) AS SELECT txn_id FROM txn EMIT CHANGES WITH ('mode' = 'x')
```

<!-- sql: refused PRV-2072 -->
```sql
CREATE CONTINUOUS QUERY one_name KEYED BY (txn_id) SERVE AS VIEW another_name AS SELECT txn_id FROM txn
```

And `INSERT INTO <sink> SELECT` is refused with PRV-2020. It is not the same thing as `WRITING TO`:
it carries neither the name the query is managed and read by — which is not the sink's — nor the
key its view needs.

<!-- sql: refused PRV-2020 -->
```sql
INSERT INTO audit_trail SELECT txn_id, amount FROM txn
```

| Refused | Code | Say instead |
|---|---|---|
| `RANGE` over text, `FLOAT`, `DECIMAL`, `BYTES` or `BOOLEAN` | PRV-2073 | Drop the `RANGE` — the key still works as a key — or range-scan a whole-number or temporal column |
| `INDEX` over `FLOAT`, `DECIMAL`, `BYTES` or the whole key | PRV-2074 | Index a whole-number, temporal, text or `BOOLEAN` column outside the key |
| A `WITH` option that does not exist, or one said twice | PRV-8017 (PRV-4018 on a replacement) | `retention`, `sink`, `keys`, `index`, `lane` on a `CREATE`; `backfill`, `backfill.rate.limit`, `cutover`, `rollback.retention`, `lane` on a `CREATE OR REPLACE` |
| `EMIT CHANGES WITH (...)` | PRV-2072 | `EMIT CHANGES` alone, or nothing |
| `SERVE AS VIEW other` | PRV-2072 | A query and its view are one name — the one clients put in `FROM` |
| `INSERT INTO <sink> SELECT` | PRV-2020 | `WRITING TO <sink>`, `WITH (sink = '<sink>')`, or `pravaha register --sink` |

Giving the key twice, even in two spellings, is PRV-2070:

<!-- sql: refused PRV-2070 -->
```sql
CREATE CONTINUOUS QUERY double_key KEYED BY (txn_id) INDEXED BY (txn_id) AS SELECT txn_id FROM txn
```

## Managing what is running

```sql
SHOW CONTINUOUS QUERIES;
PAUSE CONTINUOUS QUERY merchant_hourly;
RESUME CONTINUOUS QUERY merchant_hourly;
DROP CONTINUOUS QUERY merchant_hourly;
```

What each answers:

| Statement | Answer row |
|---|---|
| `CREATE` | `name`, `state`, `fingerprint`, `sink` |
| `DROP` / `PAUSE` / `RESUME` | `name` and the state it is now in — `DROPPED`, `PAUSED`, `RUNNING` |
| `SHOW CONTINUOUS QUERIES` | one row per name you may learn exists: `name`, `state`, `sql`, `fingerprint`, `rows_in` (`-1` when withheld), `key_columns` (by name), `sink`, `retention` |

```text
name	state	sql	fingerprint	rows_in	key_columns	sink	retention
merchant_hourly	RUNNING	SELECT merchant, window_start, ...	3f9c1a07b2d4	18244	merchant,window_end	NULL	P7D
large_payments	RUNNING	SELECT txn_id, user_id, merchant, ...	a41e07c9d3b5	18244	txn_id	audit_trail	NULL
2 rows
```

(Illustrative values.) `SHOW` is filtered exactly as `pravaha queries` and `GET /api/v1/queries`
are: a name reading a stream you may not read is not listed, and `rows_in` is `-1` where the count is
withheld.

- **`PAUSE`** stops the feed and keeps the state; the view is still readable.
- **`RESUME`** restarts the feed from where it stopped.
- **`DROP`** removes the name. The computation is released when its **last** name is dropped — two
  names sharing one fingerprint are one computation with two names.

A statement with words after the name, or the wrong noun, is refused with PRV-2070:

<!-- sql: refused PRV-2070 -->
```sql
DROP CONTINUOUS QUERY merchant_hourly now
```

<!-- sql: refused PRV-2070 -->
```sql
SHOW CONTINUOUS QUERY
```

## Who may run them

Authorization is the registration's, whatever the spelling. A `CREATE` is authorized as
`pravaha register` is — may this principal register, and may it read every stream the query reads --
and `DROP`, `PAUSE` and `RESUME` as the lifecycle actions are, by the policy's `mayAdminister`,
audited under the same verbs. The query's own name must be one a `FROM` clause can hold; the registry
refuses a reserved word as a name with PRV-8008.

## Where they run, and where they do not

| Surface | Runs these statements |
|---|---|
| Flight SQL — both SDKs, the CLI, the console workbench, JDBC/ADBC `executeUpdate` | yes |
| The embedded engine's `query(sql)` | yes |
| The PostgreSQL gateway (psql, BI tools) | **no** — refused with PRV-6211, SQLSTATE `25006`, because it is read-only |

A JDBC or ADBC client's `executeUpdate` runs them too, and is answered with the row count.

```text
$ psql -h pravaha -p 5432 -c "DROP CONTINUOUS QUERY merchant_hourly"
ERROR:  PRV-6211 ... the PostgreSQL gateway is read-only ...
```

## Why this grammar

`KEYED BY` says what the clause does — a second row with the same key replaces the first — where
`INDEXED BY` suggests an index beside the view, which nothing builds. `WRITING TO` reads as what
happens and cannot be mistaken for `INSERT INTO`, which stays refused
([ADR-043](/help/decisions/043-how-a-continuous-query-names-its-sink)). The statements are
recognised by their leading words before Calcite sees anything, so only the `SELECT` needs a SQL
parser — and a statement that starts as one of these and goes wrong is refused with PRV-2070 and the
shape that was expected, never with a syntax error about a word Calcite has never heard of.

## Where next

- [The life of a query](/help/topics/query-lifecycle) — RUNNING, PAUSED, FAILED, DROPPED.
- [Views and keys](/help/topics/views-and-keys) — choosing the key.
- [Sinks](/help/topics/sinks-overview) — what `WRITING TO` needs.
- [Sharing by fingerprint](/help/topics/sharing) — why two names can be one computation.
