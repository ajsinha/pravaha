SELECT window_end, calls, distinct_callees, talk_seconds
FROM caller_velocity
WHERE caller = ?
