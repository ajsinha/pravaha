SELECT reading_id, machine_id, line_id, temperature_dc, reading_time
FROM sensor_reading
WHERE temperature_dc >= 950
