SELECT region, desk, product_type, COUNT(*) AS trades
FROM enriched_trade
GROUP BY region, desk, product_type
