-- Tutorial 3: every order with its customer's name and tier, asked of the customers table as each
-- order arrives. LEFT: an order from a customer the table does not have is kept, with NULLs.
SELECT o.order_id, o.customer_id, c.name, c.tier, o.amount
FROM orders o
LEFT JOIN customers FOR SYSTEM_TIME AS OF o.event_time AS c
  ON c.customer_id = o.customer_id
