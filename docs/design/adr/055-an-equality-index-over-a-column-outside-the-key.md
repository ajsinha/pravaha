# ADR-055: an equality index over a column outside the key, kept from the row the view held

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `EqualityIndex` and `ServedView.index` in `pravaha-serving`, `INDEX (column)` in `ContinuousStatements`, the `X` record in `RegistryJournal` |
| Date | 2026-09-27 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-049 (the ordered index, which declined this one), ADR-046 (a replacement's cutover), ADR-044 (the mapped tier is operator state), design §17.2 |

## Decision

`CREATE CONTINUOUS QUERY ... INDEX (column)`, or `WITH (index = 'column')`, keeps an **equality
index over one column of the view**: for each value, the keys of the committed rows holding it. A
read whose `WHERE` clause has `column = literal`, or `column IN (...)`, as one of its top-level
conjuncts probes the index once per value instead of walking the view. Design §17.2's
"Secondary predicate" row is no longer only a scan.

ADR-049 declined this, and named the reason: a row's non-key values change under such an index, so
every update is a delete and an insert, and the entry to delete has to be found from the row's
**previous** values — which a Z-set retraction may not carry. Getting that wrong leaves an index
that disagrees with the view: a wrong answer with a confident face. What follows is how that is
answered, and it is the whole of the decision.

## 1. The entry to delete is filed under the row the view held

The index is never told what changed. `ServedView.commit` already reads the row it is about to
replace — `visible.get(key)` — to maintain the ordered index; the equality index is handed that same
row, under the same monitor, in the same critical section, and removes the key from the bucket of
**that row's** value before filing the new row. The retraction that caused the change is not
consulted at all, so a retraction carrying the wrong value, no value, or a value from a row the view
never held cannot mislead it. Eviction and restore take the same route: eviction removes the entry
filed under the evicted row, and a restore clears each index and rebuilds it from the restored rows
before the monitor is released.

The consequence is an invariant that holds by construction rather than by care: **what an index
holds is a function of what the view holds**. The tests check it the direct way — generated inserts,
retractions carrying arbitrary values, updates that move a key between values, eviction and
restores, with every value's probe compared to the scan after every commit (`SecondaryIndexTest`),
and the same equivalence through the registry across a checkpoint and a restart
(`SecondaryIndexRegistryTest`). Seeded with the obvious bug — removing under the *incoming* row's
value — both fail: the registry test reads `u1` under `eu` after `u1` moved to `us`.

## 2. What is indexed, and what is refused

One column per clause and per option. `INDEX (a, b)` is refused (`PRV-2070`), because a list reads
as a composite index and this is not one; `index = 'a, b'` likewise (`PRV-8017`). A row whose value
is `NULL` is not filed: `column = anything` is never true of it.

The probe uses the literal converted to the column's stored class, by the same conversion the key's
hash probe uses (`ViewAccessPath.asStored`), and finds a row by **stored-value equality**. That is
a superset of the filter's answer only where the filter's equality and the stored value's equality
are the same relation, so a column is refused at registration, against the planned output, with
the new code **`PRV-2074` (`SQL_INDEX_UNUSABLE`)** when it is:

- `FLOAT` — `0.0` and `-0.0` are equal to the filter and different stored values, and `NaN` is
  equal to nothing;
- `DECIMAL` — `1.0` and `1.00` are equal to the filter and different stored values;
- `BYTES` — arrays have identity equality;
- the view's **whole key** — a lookup by it is already a hash probe, so a second structure would cost
  memory and answer nothing faster.

Whole numbers, the temporal types, text (equality on text is exact here, which is why `<` on text is
refused) and `BOOLEAN` are indexable. A column of a **wider** key is indexable: `WHERE user_id =
'u1'` on a view keyed by `(user_id, window_end)` is a scan without one, because the ordered index is
consulted only for a range. A column the query does not produce is `PRV-2071`, as for `KEYED BY`.

## 3. The declaration is an allocation, and it is written down

ADR-049 made `RANGE` a check and built the ordered index on first use, because the flag would
otherwise have to travel in the registry journal. An equality index is the opposite case: nothing
about a read says *which* non-key column deserves one, so building on demand would mean building one
for every column anybody ever filtered on. So the declaration is what allocates, and it is written
down:

- the index is built on the view **before the registration is acknowledged**, from whatever rows
  it already holds (a shared computation's, or a restored checkpoint's);
- the journal gains a record kind, **`X`** — the name and the indexed ordinals — appended **in the
  same write** as the `R` or `W` record it belongs to, so a restart cannot bring the query back
  without it. A build that predates it refuses the record by name, as it refuses every kind it does
  not know, rather than replaying the query without the index and answering correctly but by scan;
- an `X` applies to the name's current registration: a later `R` or `C` for the name starts without
  indexes until another `X` says otherwise, and compaction writes each live entry's `X` after its
  record.

**Sharing.** Two names whose registrations are one computation share one view, so an index either
declares is on the view both read. It stays until restart when one of them is dropped, which costs
memory and never an answer.

**A replacement** (ADR-046) keeps the name's indexes: at the cutover, and at a rollback, the version
taking the name is given the leaving version's indexes **by column name** (a column the new version
does not produce, or produces at another type, is logged and dropped — the answer is the same, and a
cutover is not the moment to fail), and an `X` follows the `C` record. `INDEX` on a `CREATE OR
REPLACE` of a name that exists is refused (`PRV-2072`): a replacement changes the query behind a
name, not how the name is read.

## 4. Memory: one entry per indexed row, on the heap, bounded by the view's ceiling

An entry is the key and a reference to the same `Object[]` the view's committed map holds — not a
copy of the row — in a hash bucket per distinct value. There is exactly one entry per visible row
whose indexed value is not null, so an index is bounded by the ceiling that already bounds the view
(`PRV-4022`, raised on commit), and a view keeps **at most four** (`ServedView.MAX_EQUALITY_INDEXES`;
a fifth, which only sharing can reach, is `PRV-2074`). Worst case is therefore four entries per
row on top of the view's own three map entries per key.

It is on the heap and does not spill, for ADR-049's reason: the serving map is not on ADR-044's
mapped tier — that tier is operator state — and an index that spilled while the rows it points at
did not would be slower than the scan it replaces. If the serving map gains a tier, the indexes
follow it.

## 5. How a read shows the index was used

There is no `EXPLAIN` of a view read to show it in: `EXPLAIN` describes a registration's plan over
streams, and a view read is planned against the view each time. As with ADR-049's paths, the
evidence is the view's own counters — `ServedView.indexLookups()` counts reads answered by a probe,
`scans()` reads that walked the view, `indexEntries(ordinal)` and `equalityIndexBuilds()` what is
held and how often it was built — and the tests assert on them: a read by the indexed column moves
`indexLookups` and not `scans`, and the same predicate widened by an `OR` on another column moves
`scans`. Showing the chosen path in a plan a user can ask for is not built.

As with every access path, `ViewAccessPath` chooses rows and never an answer: the filter still runs
over what the probe returns, so an index can make a read slow or return rows the filter drops, and
cannot change what it says. The key's own paths are tried first; an `OR` across columns, `<>`, or a
literal that does not fit the column's type makes the conjunct unusable and the read scans.

## Consequences

- `WHERE column = literal` and `WHERE column IN (...)` over a declared column are a probe per
  value, over Flight SQL, the REST view read and the PostgreSQL gateway alike, because all three run
  `ViewQuery`.
- A restart keeps the index; a journal written by this build is refused by an older one (`X`).
- The index is not a registration argument of `pravaha register` or the SDKs, because `RANGE` is
  not either: both are clauses of the statement, and `WITH (index = ...)` is the value form.
- Design §17.2's "the planner warns and suggests an index at registration" is still not built.
