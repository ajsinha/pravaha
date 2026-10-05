# Trade processing — a fixed study for the visual tests

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential.** A fixed fixture (ABOUTBASE-1); the real study is in the repository.

**Store:** Aerospike · **Time to first result:** about ten minutes · **Setup:** the setup page

## The problem

Records arrive all day from several source systems, and several teams want different slices of
them. This paragraph stands where a study states its problem, long enough to wrap at every width the
visual tests photograph, and it does not change when the real study does.

A second paragraph, with **bold**, `code` and an [anchor](#the-data-model), because a study's prose
carries all three.

## The data model

One set. No reference table, no join.

### `trade`

| Bin | Type | Meaning |
|---|---|---|
| `trade_event_id` | integer | Monotonic per event. **The key** |
| `trade_id` | string | The trade. Several events share one |
| `product_type` | string | `SWAP`, `EQUITY`, `FX`, `BOND` — **a filter column** |
| `source_system` | string | Where the record came from — **a filter column** |
| `trade_time` | integer | Event time, epoch nanoseconds |

## The continuous query

```sql
CREATE CONTINUOUS QUERY all_trades AS
SELECT trade_event_id, trade_id, product_type, source_system, trade_time
FROM trade
```

## What to try next

- Subscribe with a filter and watch only your slice arrive.
- Read one row by its key.
