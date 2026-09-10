SELECT card_id, auth_count, distinct_merchants
FROM card_velocity
WHERE auth_count >= ? AND distinct_merchants >= ?
