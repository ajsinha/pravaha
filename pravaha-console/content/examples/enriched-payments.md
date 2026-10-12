# Payments with the customer's tier as it was: a lookup join

`user_profile` is a **lookup source** — a table the query asks, row by row, rather than a stream it
consumes (`jdbc-lookup`, `aerospike-lookup`). `FOR SYSTEM_TIME AS OF t.event_time` asks it as of each
payment; the lookup holds no state of its own, so there is nothing to checkpoint.

**What the data does.** Payment 21 is `u1`, found: `(21, u1, 1200, gold, EU)`. Payment 22 is `u3`,
not in the table: `LEFT JOIN` keeps it with `tier` and `region` null. An inner `JOIN` would drop it.

The binding (a `jdbc-lookup` here) and its cache are in [Lookups](/help/topics/lookups).
