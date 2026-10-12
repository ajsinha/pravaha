SELECT region, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS payments
FROM TABLE(TUMBLE(TABLE payments, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY region, window_start, window_end
