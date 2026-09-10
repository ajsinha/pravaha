SELECT card_id, risk_band, auth_count, total_minor, distinct_merchants
FROM card_velocity
WHERE card_id = ?
