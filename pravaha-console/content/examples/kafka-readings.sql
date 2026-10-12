-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Each device's readings and its lowest battery per minute, from a Kafka topic.
CREATE CONTINUOUS QUERY device_minute
    KEYED BY (device_id, window_end)
AS
SELECT device_id, window_start, window_end,
       COUNT(*)         AS readings,
       MIN(battery_pct) AS lowest_battery
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY device_id, window_start, window_end;
