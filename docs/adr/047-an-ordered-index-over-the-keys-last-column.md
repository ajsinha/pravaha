# ADR-047: an ordered index over the key's last column, and nothing over anything else

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
Proprietary and confidential; see `../../LICENSE`.

| | |
|---|---|
| Status | Accepted; built — `ServedView` and `ViewAccessPath` in `pravaha-serving`, `RANGE` in `ContinuousStatements` |
| Date | 2026-09-19 |
| Deciders | Ashutosh Sinha |
| Relates to | ADR-043 (a query's sink), ADR-044 (no RocksDB; the mapped tier is L1), design §11.2 and §17.2 |

## Decision

`CREATE CONTINUOUS QUERY ... RANGE (column)` declares an **ordered index over the key's last
column**, and this engine builds no other kind of index over a view. A predicate on a column
outside the key stays a scan and a filter.

Design §17.2 lists four access paths against a view and the engine had one of them — the scan. This
records the three decisions that closing that gap needed and the design does not settle.

## 1. `INDEXED BY` is the key; `RANGE` is the index

The design writes:

```sql
CREATE CONTINUOUS QUERY q_user_volume
SERVE AS VIEW user_volume
  INDEXED BY (user_id) RANGE (window_end)    -- point + range
```

and, in the access table, "ordered index over the key when declared `INDEXED BY RANGE`". Read
together with the same section's last row — "Secondary predicate | Best effort | Scan + filter" —
the design is **not** asking for a secondary index over an arbitrary column. `INDEXED BY` names the
key (which is why the recognizer has always accepted it as an alias for `KEYED BY`), and `RANGE`
adds an order to its last column: `user_id` is probed for equality, `window_end` is scanned between
bounds, and the two together are the key of that aggregate.

So `RANGE (column)` names a column that **is** the key's last one, and a column the key does not
already end with is appended to the key rather than refused — that is what makes the design's own
spelling mean what it reads as. A column the key holds somewhere other than at the end is refused,
because an index entry sorted by a column with other key columns after it is sorted by those
columns too. More than one `RANGE` column is refused: two ordered columns are two indexes.

**A secondary index over a non-key column is not built, and the reason is not effort.** A row's
non-key values change under such an index, so every update is a delete and an insert in the index
as well as in the view, and the entry to delete has to be found from the row's *previous* values.
Getting that subtly wrong leaves an index that disagrees with the view it indexes — which is this
project's worst failure shape: not an error, a wrong answer with a confident face. A key column
does not have that problem, because a row with a different key is a different row.

## 2. The declaration is a check, not an allocation

`RANGE` does two things at registration and one of them is the useful one.

It is **checked**: the column is resolved against the columns the view will actually have, and a
column this engine has no total order for is refused with `PRV-2073` — text (ordering it needs a
collation, which is why `<` on text is refused in a `WHERE` clause at all), `FLOAT` (IEEE 754,
under which `NaN` is ordered against nothing, TY-3), `DECIMAL` (`compareTo` disagrees with
`equals`, so `1.0` and `1.00` would be one index entry and two view rows), `BYTES` and `BOOLEAN`.
Learning that at registration rather than at the first read that wanted the index is the value of
writing it down.

It does **not** allocate. The index is built the first time a range read needs one and maintained
from then on. That choice is deliberate and has a cost worth stating: a view that is never
range-read costs nothing for having declared `RANGE`, and a view that is range-read gets an index
whether or not it declared one. The alternative — allocate on declaration — would have to survive a
restart, which means the flag travels in the registry journal, which means a new record kind that
an older build refuses by name (the journal's stated policy, and what `W` did for sinks). That is a
real change to the recovery path for a property that costs nothing to recompute: the index is
derivable from the view's own contents in one pass. It is recomputed after a restore for exactly
that reason.

The consequence to keep in mind when reading §17.2 against the code: the ordered index is
**available** on any view whose key ends in an orderable column, and `RANGE` is the sentence that
says a reader intends to use it and gets told at registration if they cannot.

## 3. Memory is the view's ceiling, and there is no spill

An index entry is a reference to the same `Object[]` the visible map holds, plus a tree node. There
is exactly one per visible key. So the index is bounded by the ceiling that already bounds the view
(`PRV-4022`, raised on commit), and there is no second number for an operator to set or
to get wrong. It is maintained inside `commit()` and `evict()` — the same monitor that orders a
commit against a reader, which is the lock the feed died on when it was not there — so an index
entry becomes visible in the same critical section as the row it points at.

It does **not** spill, and neither does the view. ADR-044's memory-mapped tier is *operator* state:
join buffers and window accumulators, the things that grow without a ceiling of their own. The
serving map is not on that path, and an index that spilled while the rows it points at did not
would be slower than the scan it exists to replace. If the serving map ever gains a tier, the index
follows it; until then, saying "it does not spill" is more useful than implying it does.

## 4. An access path may change what a read costs and never what it answers

`ViewAccessPath` chooses a set of rows. It does not rewrite the plan and does not remove the
filter, so the rule it obeys is one-sided: **the rows it returns must be a superset of the rows the
predicate keeps**, and when it cannot prove that, it says "scan". A conjunct it does not recognise
is skipped, which widens the set; an `OR` or a `NOT` at the top pins nothing; a literal that does
not fit the column's own width makes it give up rather than answer "no rows".

That is also why the equivalence is tested rather than argued. The same rows are loaded into two
views differing only in what they are keyed by — one where the paths apply and one where they
cannot — and generated predicates are put to both, including while the views change underneath.

## Consequences

- A point lookup by the whole key is a hash probe on **every** view, declared or not, over Flight
  SQL, the REST view read and the PostgreSQL gateway alike, because all three run `ViewQuery`.
- A prefix-and-range read is an index walk when the key's last column is orderable.
- A partial-key read is still a scan: the index deliberately omits rows whose ordered column is
  null, since those satisfy no bound, so a prefix-only read answered from it would lose them.
- A predicate on a non-key column is still a scan and a filter, and design §17.2's promise that
  "the planner warns and suggests an index at registration" is **not** built — there is no index to
  suggest, and a warning that names one would be a lie.
