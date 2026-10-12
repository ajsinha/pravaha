-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Five minutes of fills per symbol, recomputed every minute: windows overlap, so a fill counts five times.
CREATE CONTINUOUS QUERY rolling_volume
    KEYED BY (symbol, window_end)
AS
SELECT symbol, window_start, window_end,
       COUNT(*) AS fills,
       SUM(qty) AS shares
FROM TABLE(HOP(TABLE trades, DESCRIPTOR(event_time), INTERVAL '1' MINUTE, INTERVAL '5' MINUTE))
GROUP BY symbol, window_start, window_end;
