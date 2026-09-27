SELECT line_id, COUNT(*) AS machine_minutes, SUM(readings) AS readings, MAX(max_temperature_dc) AS hottest_dc
FROM machine_health
GROUP BY line_id
