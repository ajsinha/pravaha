SELECT warehouse, on_hand, reorder_point, updated_at
FROM stock_levels
WHERE sku = ?
