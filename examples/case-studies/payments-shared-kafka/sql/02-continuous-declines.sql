SELECT payment_id, merchant, card_country, amount_minor, paid_at
FROM payment
WHERE status = 'DECLINED'
