SELECT order_id, customer, amount
FROM orders
WHERE status = 'OPEN'
