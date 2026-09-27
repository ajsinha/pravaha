SELECT window_end, readings, min_temperature_dc, max_temperature_dc, max_vibration_um
FROM machine_health
WHERE machine_id = ?
