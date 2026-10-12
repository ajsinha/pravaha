-- Tutorial 3: each order with the payment for it, when the payment came within five minutes.
SELECT o.order_id, o.customer_id, o.amount, p.payment_id, p.event_time AS paid_at
FROM orders o
JOIN payments p
  ON p.order_id = o.order_id
 AND p.event_time BETWEEN o.event_time AND o.event_time + INTERVAL '5' MINUTE
