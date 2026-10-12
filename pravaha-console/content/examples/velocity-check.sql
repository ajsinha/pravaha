-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Card velocity: users with more than three payments in one minute.
CREATE CONTINUOUS QUERY fast_spenders
    KEYED BY (user_id, window_end)
AS
SELECT user_id, window_start, window_end, COUNT(*) AS payments
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id, window_start, window_end
HAVING COUNT(*) > 3;
