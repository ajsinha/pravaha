SELECT payment_id, merchant, card_country, amount_minor, paid_at
FROM payment
WHERE card_country <> 'GB' AND amount_minor >= 50000
