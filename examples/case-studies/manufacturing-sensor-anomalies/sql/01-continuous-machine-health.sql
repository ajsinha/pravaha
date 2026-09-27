SELECT STREAM
  TUMBLE_END(reading_time, INTERVAL '1' MINUTE) AS window_end,
  machine_id,
  line_id,
  COUNT(*)            AS readings,
  MIN(temperature_dc) AS min_temperature_dc,
  MAX(temperature_dc) AS max_temperature_dc,
  MAX(vibration_um)   AS max_vibration_um
FROM sensor_reading
GROUP BY TUMBLE(reading_time, INTERVAL '1' MINUTE), machine_id, line_id
