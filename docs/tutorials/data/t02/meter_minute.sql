-- Tutorial 2: each meter's readings per minute of event time.
SELECT meter_id, window_start, window_end, COUNT(*) AS readings, MIN(watts) AS min_w, MAX(watts) AS max_w
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY meter_id, window_start, window_end
