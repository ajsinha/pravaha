SELECT window_end, machine_id, max_temperature_dc, max_vibration_um
FROM machine_health
WHERE max_temperature_dc >= ? OR max_vibration_um >= ?
