-- Tutorial 6: each user's spend per minute of event time, the view the incident is about.
SELECT user_id, window_start, window_end, SUM(amount) AS spend, COUNT(*) AS txns
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id, window_start, window_end
