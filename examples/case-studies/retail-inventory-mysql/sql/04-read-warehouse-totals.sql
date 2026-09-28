SELECT warehouse, COUNT(*) AS lines, SUM(on_hand) AS units
FROM stock_levels
GROUP BY warehouse
