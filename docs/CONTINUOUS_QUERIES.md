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

A stream has three things: a name, a schema, and — if anything time-based will ever be asked of it —
an event-time column.

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
      path: /var/feeds/txn.csv
```

**`event-time` is the setting people most often omit, and its absence is silent.** Without it no
watermark advances, so no window ever closes: a windowed query plans, registers, reports `RUNNING`,
ingests every row and emits nothing, for ever. The `name:TYPE` grammar has no syntax for marking a
column, so this key is the only way to say it.

**`out-of-orderness` belongs to the stream, not to the node.** It says how late *this* source's rows
may arrive. A join across two streams takes the minimum of their watermarks — so a query is only as
current as its laggiest input, which is correct and surprises people.

Where the rows come from, and how to add a source of your own, is
[`CONNECTORS.md`](CONNECTORS.md).

---

## 3. Registering a continuous query

```bash
pravaha register --name hourly_spend \
  --sql "SELECT user_id,
                TUMBLE_END(event_time, INTERVAL '1' HOUR) AS hour,
                SUM(amount) AS spend
         FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' HOUR))
         GROUP BY user_id, window_start, window_end" \
  --keys 0,1
```

**`--keys` is the view's key**, given as output-column ordinals. It decides what a row *replaces*: a
second row with the same key supersedes the first. Get it wrong and the view conflates rows that
should be distinct, or keeps rows that should have replaced each other.

Two registrations differing only in `--keys` are **two different computations** — the key is part of
the query's identity, because it changes the answer (I-3).

### What happens at registration

1. The SQL is parsed and validated against the declared streams
2. A physical plan is built and **fingerprinted** — plan, row filters, key columns, retention
3. If an identical fingerprint is already running, **the existing computation is shared** and the new name points at it
4. Otherwise a lane is created, the plan compiled onto it, and a feed opened for each source stream
5. The view is created and registered in the catalogue

Step 3 is why a thousand dashboards asking the same question cost one computation. It matches on the
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

Or subscribe, and receive each committed change as it happens:

```bash
pravaha subscribe --name hourly_spend
```

**A subscription delivers whole commits.** Never half a batch, never a partly-closed window — a
subscriber attaching midway through a commit receives the *next* one entire rather than the tail of
that one (STRM-11).

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

**This is why a query with no `event-time` emits nothing.** It is not slow; it is waiting for a clock
that will never tick.

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

**The two sides may come from entirely different connectors** — one from Kafka, one from Postgres.
See [`CONNECTORS.md`](CONNECTORS.md) §4, which also records that this is supported by construction
and not yet demonstrated by a test.

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

A registered query may also carry bound values:

```bash
pravaha register --name eu_spend --sql "SELECT ... WHERE region = ?" --param EU
```

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
| `CAST(x AS DOUBLE)` | ✅ | Between numeric types |
| Literals — `SELECT 1` | ✅ | |
| `CASE WHEN … THEN … END` | ✅ | Any number of branches, with or without `ELSE`. Only the branch taken is evaluated, so `CASE WHEN n = 0 THEN 0 ELSE t / n END` does not divide by zero |
| A boolean-valued expression — `CASE WHEN c THEN TRUE ELSE FALSE END`, `amount > 50`, `status IS NULL` | ✅ | Only where the result cannot be UNKNOWN. See below |
| Scalar functions — `ABS`, `FLOOR`, `CEIL`, `ROUND` | ✅ | One argument. `ROUND(x, 2)` is refused: rounding to decimal places is not built |
| Numeric functions beyond those four | ❌ | `PRV-2021` |
| String literals — `SELECT 'flagged'` | ✅ | |
| `UPPER`, `LOWER` | ✅ | Converted in the root locale, so the answer does not depend on the machine the lane runs on |
| `TRIM(x)` | ✅ | Strips spaces from both ends. `TRIM(LEADING …)` and a trim character other than a space are refused |
| String concatenation — `a \|\| b` | ✅ | Any length of chain. **Null concatenated with anything is null**, not an empty string |
| `SUBSTRING(s FROM start)`, `… FOR length` | ✅ | Positions are 1-based and counted in code points, so a substring never splits an emoji in half |
| Other string functions — `REPLACE`, `POSITION`, `LPAD` | ❌ | `PRV-2021` |
| `SELECT DISTINCT` | ❌ | `PRV-2050` — it is a `GROUP BY` over an unbounded key space; see §13 |

**A projected boolean holds two values, and SQL comparisons have three.** `SELECT amount > 50`
plans, because `amount` is `NOT NULL` and the comparison is therefore TRUE or FALSE. `SELECT status
= 'ok'` over a nullable `status` is refused with `PRV-2021`: the answer for a row whose `status` is
NULL is UNKNOWN, and writing it into a boolean column would report it as `false` — a wrong answer
under a success exit code rather than a missing feature. Say which you mean and it plans: `CASE WHEN
status = 'ok' THEN TRUE ELSE FALSE END` collapses UNKNOWN to `false` deliberately, and `status IS
NOT NULL AND status = 'ok'` is never UNKNOWN in the first place. `IS NULL`, `IS NOT NULL`, `IS
TRUE`, `IS FALSE`, `IS NOT TRUE` and `IS NOT FALSE` are total by definition and project freely.

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

---

## 12. Filtering — `WHERE` and `HAVING`

| | | |
|---|---|---|
| `=`, `<>`, `<`, `<=`, `>`, `>=` | ✅ | |
| `AND`, `OR`, `NOT` | ✅ | `NOT` is pushed down at compile time by De Morgan |
| `IN (a, b, c)` | ✅ | Expanded to a chain of equalities |
| `BETWEEN a AND b` | ✅ | Expanded to `>= AND <=` |
| `IS NULL`, `IS NOT NULL` | ✅ | |
| Arithmetic in a predicate — `amount * 2 > 100` | ✅ | |
| A bare boolean column — `WHERE flagged` | ✅ | |
| Column against column — `WHERE a > b` | ✅ | Both numeric |
| `HAVING` | ✅ | A filter above the aggregate |
| `LIKE`, `NOT LIKE` | ✅ | Against a literal pattern. The pattern is compiled once when the query is registered, not once per row — so `LIKE status` is refused |
| `LIKE … ESCAPE` | ❌ | `PRV-2021`. Without it, `%` and `_` are always wildcards and cannot be matched literally |
| Text ordering — `WHERE status > user_id` | ❌ | `PRV-2021`. `>` on text needs a collation, and assuming one gives wrong answers that look right. `=` and `<>` on text do work |
| Comparing text to a number | ❌ | `PRV-2021` |

Three-valued logic is honoured throughout: a comparison with NULL is UNKNOWN, and a filter keeps
only rows where the predicate is TRUE.

---

## 13. Aggregation

| | | |
|---|---|---|
| Global `COUNT(*)` | ✅ | One group, so bounded |
| `TUMBLE` windows | ✅ | |
| `HOP` (sliding) windows | ✅ | |
| `COUNT`, `SUM`, `MIN`, `MAX`, `AVG` | ✅ | Over integer columns. `SUM`/`AVG` over a `FLOAT32`/`FLOAT64` column are refused `PRV-2020`, because floating-point addition is not associative and an incremental sum would depend on arrival order; the refusal suggests `SUM(CAST(price AS BIGINT))`, which works |
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
| `ORDER BY` | ❌ | `PRV-2020` |
| `LIMIT` / `OFFSET` | ❌ | `PRV-2020` |
| `UNION`, `UNION ALL`, `INTERSECT`, `EXCEPT` | ❌ | `PRV-2020` |
| `IN (subquery)`, `EXISTS`, scalar subqueries | ❌ | `PRV-2021` |
| Window functions — `ROW_NUMBER() OVER (…)` | ❌ | `PRV-2021` |
| `VALUES` | ❌ | `PRV-2020` |
| `INSERT`, `UPDATE`, `DELETE` | ❌ | `PRV-2020` — Pravaha answers questions; sinks write results |

Note what `ORDER BY` means over a stream: a total order over rows that have not all arrived. It is
meaningful over a *bounded* read of a maintained view, and that is where it would land if it is
added — not over a continuous query.

---

## 16. Types

Supported in expressions: `BOOLEAN`, `TINYINT`, `SMALLINT`, `INTEGER`, `BIGINT`, `REAL`, `DOUBLE`,
`VARCHAR`, `VARBINARY`, `DATE`, `TIME`, `TIMESTAMP`.

**Correction (QA, TYPE round, 2026-09-14): two of those are not actually usable on the wire today.**
`VARBINARY` (`BYTES`) and `TIME` are both declarable, both compute correctly in expressions, and
both are accepted by `ArrowSchemas.arrowTypeOf` when the schema is described to a client — but
serializing a *non-null value* of either type to a real client crashes: a non-null BYTES value
throws `ClassCastException: String cannot be cast to [B`, and a non-null TIME value throws
`ClassCastException: TimeNanoVector cannot be cast to TimeStampNanoTZVector` (`ArrowSchemas.write`
was not updated to match a since-fixed `arrowTypeOf`). `pravaha run` (the file-driven path) is
unaffected by either — only client-facing wire serialization (`pravaha query`/`subscribe`, or any
Arrow Flight SQL client) is. Everything else in this list serializes correctly, including the six
plain numeric types, `VARCHAR`, `DATE`, and `TIMESTAMP`. See `docs/qa/FINDINGS.md`'s TY-17 (BYTES)
and TY-18 (TIME).

`DECIMAL` is refused rather than sent as a floating-point number, because the rounding decision
belongs to whoever owns the ledger and not to a serialiser. Year–month intervals (`INTERVAL '1'
MONTH`) are refused because a month is not a fixed length of time; day–time intervals work and are
what windows use.

**`DECIMAL(p,s)` cannot be declared at all through the schema string**, even though the refusal for
an unknown type names it as supported. `--schema`, `--out-schema`, `pravaha.streams.*.schema` and
`POST /api/v1/streams` share one grammar, `name:TYPE,name:TYPE`, which is split on commas before any
type is parsed — so `amt:DECIMAL(10,2)` is cut at its own comma and fails as `unknown type
'DECIMAL(10'`. A decimal column can only be declared programmatically, through
`Types.decimal(p, s)`. A decimal that *is* declared that way is carried through scans, filters and
projections correctly; what is not built is arithmetic over it. Recorded as TY-7.

---

## 17. What to do when something here is refused

1. **Read the message.** Every `PRV-` refusal says what is unsupported and, where there is one, what
   to write instead.
2. **If it is an unbounded-state refusal (`PRV-2050`), add a window.** That is almost always the
   right answer, and it is usually what was meant.
3. **If the work is genuinely not streaming** — a total order, a set difference, an ad-hoc join
   across two stores — that is what ADR-030 tier 4 puts out of scope on purpose. Pravaha maintains
   the answer to a question asked in advance; a query engine answers questions asked just now, and
   trying to be both is how a system becomes bad at each.

---

## 18. Common surprises, and what they actually are

| Symptom | Cause |
|---|---|
| Query is `RUNNING`, view is empty, rows are arriving | No `event-time` declared, so no watermark, so no window ever closes (§2) |
| Windows close but lag real time badly | `out-of-orderness` larger than the data needs, or a slow input dragging a join's watermark down |
| Two queries that look identical are not shared | Different `--keys`, different retention, or `AND` operands written in a different order (§3) |
| A count went down | A correction: a late row retracted a result and replaced it. Working as designed (§6) |
| `SELECT` refuses with `PRV-8004` | The query behind the view failed; the rows are stale, and saying so is the point (§8) |
| A view holds fewer rows than expected | Retention. It defaults to forever now, but an explicit one evicts by event time |
| `GROUP BY` works on a view and is refused on a stream | Deliberate, and the reason is the input rather than the query (§13) |

---

## 19. Error codes

| Code | Means |
|---|---|
| `PRV-2001` | Syntax error, with Calcite's line and column preserved |
| `PRV-2002` | Validation failed — an unknown column, a type mismatch |
| `PRV-2003` | The query names a stream that is not registered |
| `PRV-2020` | A relational operator Pravaha cannot execute |
| `PRV-2021` | An expression or function Pravaha cannot compile |
| `PRV-2050` | The query's state would grow without bound |
| `PRV-2060`–`PRV-2063` | Parameter binding — see [ADR-032](adr/032-parameters-are-values-not-queries.md) |

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
