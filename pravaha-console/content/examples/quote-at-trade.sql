-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Each fill with the quotes for its symbol in the five seconds before it.
CREATE CONTINUOUS QUERY fills_with_quotes
    KEYED BY (trade_id, quoted_at)
AS
SELECT t.trade_id, t.symbol, t.side, t.price_cents,
       q.bid_cents, q.ask_cents, q.event_time AS quoted_at
FROM trades t
JOIN quotes q
  ON q.symbol = t.symbol
 AND q.event_time BETWEEN t.event_time - INTERVAL '5' SECOND AND t.event_time;
