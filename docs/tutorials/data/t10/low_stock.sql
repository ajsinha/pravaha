SELECT sku, warehouse, on_hand, reorder_point
FROM stock
WHERE on_hand < reorder_point
