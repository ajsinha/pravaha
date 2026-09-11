SELECT STREAM
  t.trade_event_id,
  t.trade_id,
  t.product_type,
  t.source_system,
  c.legal_name,
  c.country,
  b.desk,
  b.region,
  t.trade_time,
  t.trade_json
FROM trade AS t
LEFT JOIN counterparty FOR SYSTEM_TIME AS OF t.trade_time AS c
       ON t.counterparty_id = c.counterparty_id
LEFT JOIN book FOR SYSTEM_TIME AS OF t.trade_time AS b
       ON t.book_id = b.book_id
