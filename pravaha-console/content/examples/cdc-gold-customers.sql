-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- The gold-tier customers, kept in step with the table through its write-ahead log.
CREATE CONTINUOUS QUERY gold_customers
    KEYED BY (id)
AS
SELECT id, name, region
FROM crm_customers
WHERE tier = 'gold';
