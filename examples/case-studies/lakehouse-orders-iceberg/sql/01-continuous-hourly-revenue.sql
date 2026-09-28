SELECT STREAM
  TUMBLE_END(ordered_at, INTERVAL '1' HOUR) AS window_end,
  region,
  COUNT(*)                    AS orders,
  SUM(amount_minor)           AS revenue_minor,
  COUNT(DISTINCT customer_id) AS customers
FROM order_line
GROUP BY TUMBLE(ordered_at, INTERVAL '1' HOUR), region
