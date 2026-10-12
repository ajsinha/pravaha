-- Tutorial 2: the highest draw in the last minute, every thirty seconds.
SELECT meter_id, window_start, window_end, COUNT(*) AS readings, MAX(watts) AS max_w
FROM TABLE(HOP(TABLE readings, DESCRIPTOR(event_time), INTERVAL '30' SECOND, INTERVAL '1' MINUTE))
GROUP BY meter_id, window_start, window_end
