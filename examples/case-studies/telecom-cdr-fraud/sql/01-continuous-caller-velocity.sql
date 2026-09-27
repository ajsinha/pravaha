SELECT STREAM
  HOP_END(start_time, INTERVAL '1' MINUTE, INTERVAL '5' MINUTE) AS window_end,
  caller,
  COUNT(*)               AS calls,
  COUNT(DISTINCT callee) AS distinct_callees,
  SUM(duration_s)        AS talk_seconds
FROM call_record
GROUP BY HOP(start_time, INTERVAL '1' MINUTE, INTERVAL '5' MINUTE), caller
