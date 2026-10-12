-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Spend per user per minute: one window a minute wide, closing as the watermark passes it.
CREATE CONTINUOUS QUERY spend_per_minute
    KEYED BY (user_id, window_end)
AS
SELECT user_id, window_start, window_end,
       COUNT(*)    AS payments,
       SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id, window_start, window_end;
