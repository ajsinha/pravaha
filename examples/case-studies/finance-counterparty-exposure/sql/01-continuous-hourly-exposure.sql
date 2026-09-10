SELECT STREAM
  TUMBLE_END(s.value_time, INTERVAL '1' HOUR) AS window_end,
  s.counterparty_id,
  s.currency,
  c.rating,
  COUNT(*)              AS payment_count,
  SUM(s.amount_minor)   AS outgoing_minor,
  MAX(s.amount_minor)   AS largest_minor
FROM settlement AS s
LEFT JOIN counterparty FOR SYSTEM_TIME AS OF s.value_time AS c
       ON s.counterparty_id = c.counterparty_id
WHERE s.direction = 'PAY'
GROUP BY TUMBLE(s.value_time, INTERVAL '1' HOUR), s.counterparty_id, s.currency, c.rating
