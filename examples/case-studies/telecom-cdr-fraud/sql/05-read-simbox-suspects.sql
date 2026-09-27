SELECT caller, COUNT(*) AS windows_flagged, MAX(calls) AS most_calls, MAX(distinct_callees) AS most_distinct
FROM caller_velocity
WHERE calls >= ? AND distinct_callees >= ?
GROUP BY caller
