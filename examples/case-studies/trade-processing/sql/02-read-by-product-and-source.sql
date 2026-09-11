SELECT trade_event_id, trade_id, product_type, source_system, trade_time, trade_json
FROM trade_feed
WHERE product_type = ? AND source_system = ?
