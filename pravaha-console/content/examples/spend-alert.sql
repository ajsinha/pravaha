-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- The view the alert follows: spend per user per minute.
CREATE CONTINUOUS QUERY minute_spend
    KEYED BY (user_id, window_end)
AS
SELECT user_id, window_start, window_end, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE txn, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY user_id, window_start, window_end;

-- The alert: a key fires when its row enters the condition, and clears when it leaves.
CREATE ALERT big_spender ON minute_spend
  WHERE spend > 500
  NOTIFY ops_log
  WITH (severity = 'critical');
