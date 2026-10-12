-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Every payment over 1000, kept current by key.
CREATE CONTINUOUS QUERY big_payments
    KEYED BY (txn_id)
AS
SELECT txn_id, user_id, merchant, amount
FROM txn
WHERE amount > 1000;

-- sql: read
-- A snapshot read of the view: what a dashboard or the PostgreSQL gateway asks. Bounded, so it may group.
SELECT user_id, COUNT(*) AS payments, SUM(amount) AS total
FROM big_payments
GROUP BY user_id;
