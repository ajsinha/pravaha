SELECT product_type, source_system, COUNT(*) AS trades
FROM trade_feed
GROUP BY product_type, source_system
