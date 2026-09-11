SELECT asset_class, COUNT(*) AS lines, SUM(new_orders) AS orders, SUM(total_qty) AS qty
FROM order_rate
GROUP BY asset_class
