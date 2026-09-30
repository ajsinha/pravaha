-- Project Pravaha -- Ask once. Answer always.
-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Proprietary and confidential; see the LICENSE file in the root of this repository.
--
-- The seed profile's second continuous query: each customer's orders and spend per minute of event
-- time. A window, because a GROUP BY over a stream with no bound on its keys is refused (PRV-2050).
-- A minute's row appears when the watermark passes its end: the seed's orders run from 10:00:00 to
-- 10:05:30 with 10s of allowed lateness, so 10:00 to 10:04 close and 10:05 waits for a later order.
SELECT window_start, window_end, customer, COUNT(*) AS orders, SUM(amount) AS spend
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(event_time), INTERVAL '1' MINUTE))
GROUP BY window_start, window_end, customer
