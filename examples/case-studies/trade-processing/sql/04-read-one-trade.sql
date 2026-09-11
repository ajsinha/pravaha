SELECT trade_event_id, trade_id, source_system, trade_json
FROM trade_feed
WHERE trade_id = ?
