-- Tutorial 3: the orders nobody paid within five minutes. A LEFT join with a time bound: the order is
-- emitted, once, when the watermark passes the end of its five minutes with no payment matched.
SELECT o.order_id, o.customer_id, o.amount, o.event_time AS ordered_at
FROM orders o
LEFT JOIN payments p
  ON p.order_id = o.order_id
 AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE
WHERE p.payment_id IS NULL
