-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Readings and the lowest battery per site per five minutes; the stream allows a minute of disorder.
CREATE CONTINUOUS QUERY site_readings
    KEYED BY (site, window_end)
AS
SELECT site, window_start, window_end,
       COUNT(*)         AS readings,
       MIN(battery_pct) AS lowest_battery
FROM TABLE(TUMBLE(TABLE readings, DESCRIPTOR(event_time), INTERVAL '5' MINUTE))
GROUP BY site, window_start, window_end;
