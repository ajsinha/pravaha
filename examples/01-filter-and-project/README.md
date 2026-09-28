# 01 — Filter and project

The smallest useful query: select two columns from the rows that match a predicate.

```bash
pravaha-engine run \
  --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING' \
  --in  examples/01-filter-and-project/transactions.csv \
  --out /tmp/big-transactions.csv \
  --out-schema 'user_id:STRING,amount:INT64'
```

Expected output:

```
alice,500
dave,150
frank,1200
```

`bob` and `erin` are under the threshold; `carol` is `PENDING`.

## What actually happened

Calcite parsed and optimised the SQL, `PhysicalPlanBuilder` translated it into Pravaha's own plan,
and the rows were decoded into an off-heap arena and pushed through a filter and a projection.

Look at the plan:

```bash
pravaha-engine explain --level all \
  --sql "SELECT user_id, amount FROM txn WHERE status = 'COMPLETED' AND amount > 100" \
  --schema 'txn_id:INT64,user_id:STRING,amount:INT64,status:STRING'
```

The physical plan shows the filter below the projection and a scan at the leaf. The string
comparison in the generated form is a UTF-8 byte comparison — no `String` is ever materialised.
