# Gold customers from a PostgreSQL table's changes (CDC)

`crm_customers` is bound to the `postgres-cdc` source: PostgreSQL's own logical replication, no
Debezium. It is a **changelog**: an insert arrives at weight `+1`, a delete as the old row at `-1`,
an update as both. So the view goes **down** when the table does — the thing a polled source cannot do.

```yaml
pravaha:
  streams:
    crm_customers:
      schema: "id:INT64,name:STRING,tier:STRING,region:STRING?,updated_at:TIMESTAMP"
      event-time: updated_at
  sources:
    crm_customers:
      plugin: postgres-cdc
      options:
        url: "jdbc:postgresql://db-1.internal:5432/crm?sslmode=verify-full"
        user: pravaha_cdc
        password: "${PRAVAHA_CDC_PASSWORD}"
        table: public.customers
        stream: crm_customers
```

**What the data does** (the `weight` column is the change, not a field of the table): Asha is
inserted gold — in the view. Ben is inserted silver — not in it. Ben's update to gold is his silver row
at `-1` and his gold row at `+1` — he enters. Asha's delete is her row at `-1` — she leaves. The view
ends holding Ben alone.

The table needs `REPLICA IDENTITY FULL` and the database `wal_level = logical`:
[the PostgreSQL CDC source](/help/topics/source-postgres-cdc) has every option and requirement.
