# 02 — Aggregation, and a query the engine refuses

## A global aggregate works

```bash
pravaha run \
  --sql "SELECT COUNT(*), SUM(amount) FROM txn WHERE status = 'COMPLETED'" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING' \
  --in  examples/02-aggregate/transactions.csv \
  --out /tmp/totals.csv \
  --out-schema 'n:INT64,total:INT64'
```

Output: `3,700`

## A keyed aggregate is refused, on purpose

```bash
pravaha validate \
  --sql "SELECT user_id, COUNT(*) FROM txn GROUP BY user_id" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
```

```
PRV-2050  GROUP BY [0] has no bound on its key space, so its state would grow
without limit. Add a window (arriving in Wave 4) or a state TTL. Refusing now
rather than exhausting memory later.
```

This is not a missing feature so much as a deliberate refusal. A global aggregate holds one row of
state whatever the input volume; a keyed one holds a row per key, and over an unbounded key space
that never stops growing. **Unbounded integration is how incremental engines die in production**
(design §9.6), and the only intervention that reliably works is refusing the query at registration —
where it costs a developer a minute — rather than at 3 a.m. when it costs an outage.

Windowing gives the bound, and arrives in Wave 4.
