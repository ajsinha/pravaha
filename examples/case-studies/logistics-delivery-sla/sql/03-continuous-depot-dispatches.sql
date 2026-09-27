SELECT STREAM
  TUMBLE_START(dispatch_time, INTERVAL '1' HOUR) AS hour_start,
  depot,
  COUNT(*) AS dispatched
FROM dispatch
GROUP BY TUMBLE(dispatch_time, INTERVAL '1' HOUR), depot
