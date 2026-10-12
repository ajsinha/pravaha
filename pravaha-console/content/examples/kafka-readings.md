# Device minutes from a Kafka topic

The SQL does not change with the source: `readings` is a stream, and here it is bound to the `kafka`
source — one reader per partition, each partition's offset kept in the query's checkpoint, so a
restore resumes exactly where the checkpoint left off.

```yaml
pravaha:
  streams:
    readings:
      schema: "device_id:STRING,site:STRING,temperature:FLOAT64,battery_pct:INT32?,event_time:TIMESTAMP"
      event-time: event_time
      out-of-orderness: 1m
  sources:
    readings:
      plugin: kafka
      options:
        bootstrap.servers: "kafka-1.internal:9093"
        topic: device-readings
        schema: "device_id:STRING,site:STRING,temperature:FLOAT64,battery_pct:INT32?,event_time:TIMESTAMP"
        format: json
        start.from: earliest
```

**What the data does.** Each record's value is one JSON object, matched to the schema by column name.
When the minute `[09:00, 09:01)` closes, `d-1` has 2 readings, lowest battery 88, and `d-2` 1 reading
with no battery figure: `MIN` skips a null, so `lowest_battery` is null.

[The Kafka source](/help/topics/source-kafka) has Avro, Protobuf, the schema registry and TLS.
