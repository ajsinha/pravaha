SELECT STREAM
  TUMBLE_END(event_time, INTERVAL '5' MINUTE) AS window_end,
  step,
  device,
  COUNT(*)                   AS events,
  COUNT(DISTINCT session_id) AS sessions,
  SUM(basket_minor)          AS basket_minor
FROM checkout_event
GROUP BY TUMBLE(event_time, INTERVAL '5' MINUTE), step, device
