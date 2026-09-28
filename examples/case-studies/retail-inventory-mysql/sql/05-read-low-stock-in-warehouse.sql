SELECT sku, on_hand, reorder_point
FROM low_stock
WHERE warehouse = ?
