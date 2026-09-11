SELECT STREAM
  t.trade_event_id,
  t.trade_id,
  t.product_type,
  t.source_system,
  t.trade_time,
  t.trade_json
FROM trade AS t
