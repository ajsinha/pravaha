-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Each payment with the payer's tier and region, asked of the profile table as of the payment's time.
CREATE CONTINUOUS QUERY enriched_payments
    KEYED BY (txn_id)
AS
SELECT t.txn_id, t.user_id, t.amount, p.tier, p.region
FROM txn AS t
LEFT JOIN user_profile FOR SYSTEM_TIME AS OF t.event_time AS p
  ON p.user_id = t.user_id;
