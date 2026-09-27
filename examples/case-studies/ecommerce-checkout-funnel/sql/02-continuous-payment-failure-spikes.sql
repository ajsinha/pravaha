SELECT STREAM
  TUMBLE_END(event_time, INTERVAL '5' MINUTE) AS window_end,
  device,
  COUNT(*)          AS failures,
  SUM(basket_minor) AS basket_at_risk_minor
FROM checkout_event
WHERE step = 'PAYMENT_FAILED'
GROUP BY TUMBLE(event_time, INTERVAL '5' MINUTE), device
HAVING COUNT(*) >= 3
