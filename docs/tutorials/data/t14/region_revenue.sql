SELECT region, window_start, window_end, SUM(amount) AS revenue, COUNT(*) AS orders
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY region, window_start, window_end
