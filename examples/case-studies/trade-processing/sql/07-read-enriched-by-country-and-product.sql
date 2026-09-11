SELECT trade_id, legal_name, country, desk, region, trade_json
FROM enriched_trade
WHERE country = ? AND product_type = ?
