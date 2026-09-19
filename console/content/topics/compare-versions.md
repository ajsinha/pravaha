---
title: Comparing two versions of a query
slug: compare-versions
category: sql
order: 35
icon: file-diff
summary: "The workbench's Compare panel: a draft against a registered query or another draft — the SQL side by side, both plans with every operator marked added, removed or changed, and what the engine will do with the new version before you register it."
audience: Analysts and operators
keywords: [compare, diff, sql diff, plan diff, version, v1, v2, blue green, changed operator, fingerprint, sharing, keys, state shape, workbench]
guide: continuous-queries#101-the-statements-that-register-and-manage-queries
related: [create-continuous-query, sharing, upgrades, views-and-keys, query-lifecycle]
---

A registered query is never edited in place: a new version is a new registration beside the old
one ([Upgrades](/help/topics/upgrades)). Before you register it, the workbench's **Compare** panel
shows what the change is and what it will cost — the SQL diff *and* the plan diff design §23.7 asks
for, so the consequence is visible before anything runs.

## Opening it

| From | How |
|---|---|
| A registered query | its page's **Open in workbench**, edit, then the **Compare** panel — it compares with the query the draft came from |
| Any draft | the **Compare** panel, then pick a registered query by name or another open draft |
| A link | `/workbench?query=txns_per_user&panel=diff&against=txns_per_user` opens the query, the panel, and compares at once |

The draft on the right is always the one in the editor. Change it, or pick something else to compare
with, and the comparison already on screen is greyed and fenced as **out of date** until you press
**Compare again** — it is never shown as if it described the new text.

The panel needs JavaScript; the plain form the workbench falls back to runs and registers only.

## What it shows

| Part | What it is |
|---|---|
| What the engine will do | the consequences that can be known before registering — below |
| SQL | Monaco's diff editor, the earlier version on the left. **Unified view** puts both in one column (a narrow screen always does). Tab reaches both editors; they are read only |
| Plan | both plans as graphs, the registered one first. An operator on one side only is marked **+** (added, heavy border) or **−** (removed, dashed); one that changed, **~**. The mark is in the node's accessible name too |
| Measured totals | under the **registered** plan only: rows in, state against its ceiling, view size, subscribers, watermark. A draft has not run, so its side says so rather than showing numbers |
| Operators | the plan diff in words, differences only; **Show unchanged operators** lists the rest as `=` |

### How operators are matched

Operators are matched by **where they sit and what kind they are**, never by their position in the
list — an operator inserted near the root would otherwise renumber everything beneath it:

1. Each plan is read from its root as **branches**: down through one-input operators to a scan, or
   to an operator with several inputs (a join), whose inputs are paired left with left, right with
   right.
2. Along a pair of branches, operators of the **same kind** (`Filter`, `WindowedAggregate` — the
   engine's own operator names) are paired in order, as many as possible, preferring pairs whose
   labels are identical.
3. A pair is **changed** when its label (the engine's rendering, arguments included), its output
   columns, or whether it keeps state differ; otherwise it is the same. Anything unpaired is added
   or removed — and an operator never "changes" into another kind: `Project` becoming `Aggregate`
   is one removed and one added.

Where an aggregate's label carries key ordinals (`keys=[0, 1, 2]`), they are read by name through
its input's columns, so the change says `keys window_start, window_end, user_id → …` rather than
two lists of numbers.

### What the engine will do, and what nobody can tell yet

| Finding | Where it comes from |
|---|---|
| **Same or separate computation** | when both versions are registered (a draft whose text is exactly a registered query's counts), their fingerprints decide it. Otherwise plans that differ cannot share; identical plans share only if registered with the same keys and retention and the same row filters — the fingerprint the engine answers with settles it ([Sharing](/help/topics/sharing)) |
| **Output columns** | each side's validated schema: columns added, removed, retyped, or reordered |
| **Keys** | whether the registered version's key columns are still in the new output, so it can be keyed the same way and a point query by that key works on both |
| **State shape** | stateful operators added, removed or changed — a changed one holds its state in another shape, so none of v1's state can serve v2 |
| **Not determinable** | how long the new version takes to fill and how much state it will hold: the engine does not estimate either before registration, and the panel says so rather than guessing |

## Worked example

`txns_per_user` is running:

```sql
CREATE CONTINUOUS QUERY txns_per_user KEYED BY (user_id, window_end)
AS SELECT window_start, window_end, user_id, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end, user_id
```

Open it in the workbench and change it to count per merchant as well, and only transactions over
100:

```sql
SELECT window_start, window_end, user_id, merchant, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
WHERE amount > 100
GROUP BY window_start, window_end, user_id, merchant
```

**Compare** answers, for the plans the engine gives these two:

```text
What the engine will do with this version
- The plans differ, so the fingerprints will too: registered, this version is a computation of its
  own, filling its own state from its sources. txns_per_user keeps running untouched.
- Output columns: added merchant.
- txns_per_user's key (user_id, window_end) is in this version's output: it can be keyed the same
  way, and a point query by that key works on both.
- The state changes shape — changed WindowedAggregate(TUMBLING 60000ms, keys=[0, 1, 2, 3],
  1 aggregate(s)) — so none of txns_per_user's state could serve this version.
- Not determinable here: how long this version takes to fill, and how much state it will hold.

Operators
changed: WindowedAggregate keys window_start, window_end, user_id → window_start, window_end, user_id, merchant; now emits merchant
changed: Project[window_start, window_end, user_id] → Project[window_start, window_end, user_id, merchant]; now emits merchant
+ Filter(amount > 100)
```

`WindowAssign`, the input `Project` and `Scan(txn)` are the same on both sides; **Show unchanged
operators** lists them. The filter sits between the window assignment and the projection — it is
one added operator, and nothing beneath it is reported as moved.

Register it as `txns_per_user_v2` from the Register panel, compare again, and the first finding
becomes the engine's own answer: two fingerprints, two computations, each dropped independently.

## Pitfalls

- **Identical plans are not a promise of sharing.** Keys, retention and row filters are part of the
  fingerprint too; only the fingerprint the registration answers with settles it.
- **The totals are v1's.** They describe the registered query's running plan as a whole — the engine
  does not count per operator — and say nothing about how the new version will behave.
- **Comparing two drafts** works the same way, without the registered-query findings: there are no
  fingerprints, keys or totals until one is registered.
- **A side the engine will not plan** (a refusal) or will not show you (the policy) is said on that
  side — *partly compared* or *not permitted* — and the rest of the comparison still stands.
