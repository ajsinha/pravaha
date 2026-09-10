SELECT trader_id, symbol, new_orders, total_qty
FROM order_rate
WHERE trader_id = ?
