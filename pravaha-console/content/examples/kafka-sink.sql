-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- The open orders, one per order id, written to a Kafka topic as keyed upserts.
CREATE CONTINUOUS QUERY open_orders
    KEYED BY (order_id)
    WRITING TO open_orders_topic
AS
SELECT order_id, customer_id, region, amount, status
FROM orders
WHERE status = 'OPEN';
