SELECT STREAM
  TUMBLE_END(ordered_at, INTERVAL '1' HOUR) AS window_end,
  category,
  COUNT(*)          AS lines,
  SUM(amount_minor) AS revenue_minor
FROM order_line
GROUP BY TUMBLE(ordered_at, INTERVAL '1' HOUR), category
