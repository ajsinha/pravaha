SELECT window_start, caller, calls, rn
FROM (
  SELECT window_start, caller, calls,
         ROW_NUMBER() OVER (PARTITION BY window_start ORDER BY calls DESC) AS rn
  FROM (
    SELECT TUMBLE_START(start_time, INTERVAL '5' MINUTE) AS window_start, caller, COUNT(*) AS calls
    FROM call_record
    GROUP BY TUMBLE(start_time, INTERVAL '5' MINUTE), caller
  )
)
WHERE rn <= 3
