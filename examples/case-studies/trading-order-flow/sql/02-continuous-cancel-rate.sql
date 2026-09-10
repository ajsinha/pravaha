SELECT STREAM
  HOP_END(o.event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE) AS window_end,
  o.trader_id,
  i.symbol,
  COUNT(*) AS cancels
FROM order_event AS o
LEFT JOIN instrument FOR SYSTEM_TIME AS OF o.event_time AS i
       ON o.instrument_id = i.instrument_id
WHERE o.event_type = 'CANCEL'
GROUP BY HOP(o.event_time, INTERVAL '10' SECOND, INTERVAL '1' MINUTE), o.trader_id, i.symbol
