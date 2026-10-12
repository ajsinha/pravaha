-- Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
-- Each order with its shipment, when the shipment comes within a day of the order.
CREATE CONTINUOUS QUERY shipped_orders
    KEYED BY (order_id, shipment_id)
AS
SELECT o.order_id, o.customer_id, o.region, o.amount,
       s.shipment_id, s.carrier
FROM orders o
JOIN shipments s
  ON s.order_id = o.order_id
 AND s.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '1' DAY;
