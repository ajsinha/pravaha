SELECT payment_id, merchant, amount_minor
FROM cross_border
WHERE card_country = ?
