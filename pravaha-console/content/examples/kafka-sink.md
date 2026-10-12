# Open orders, kept on a compacted Kafka topic

`WRITING TO` names a sink bound on the node, and the node writes every commit of the view to it.
`kafka-sink` in its default `upsert` mode keys each record by the view's key and writes a
**tombstone** for a retraction, so a compacted topic holds exactly the view: one record per open order.

```yaml
pravaha:
  sinks:
    open_orders_topic:
      plugin: kafka-sink
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: open-orders
        schema: "order_id:INT64,customer_id:STRING,region:STRING,amount:INT64,status:STRING"
        key.columns: order_id
        mode: upsert
```

**What the data does.** Orders 6001 and 6002 open: two records keyed 6001 and 6002. 6001 ships:
it leaves the view, so the topic gets a tombstone for key 6001, and compaction leaves 6002 alone.

The query's output must be the sink's row shape and its key the sink's key, or registration is
refused (`PRV-8010`). [The Kafka sink](/help/topics/sink-kafka) has exactly-once delivery and the formats.
