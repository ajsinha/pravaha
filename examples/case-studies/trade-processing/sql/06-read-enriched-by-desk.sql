SELECT trade_id, product_type, legal_name, country, desk, trade_json
FROM enriched_trade
WHERE desk = ?
