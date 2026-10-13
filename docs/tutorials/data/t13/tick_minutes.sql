SELECT symbol, window_start, window_end, MIN(price) AS low, MAX(price) AS high, COUNT(*) AS ticks
FROM TABLE(TUMBLE(TABLE ticks, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY symbol, window_start, window_end
