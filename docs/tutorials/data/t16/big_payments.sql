SELECT payment_id, user_id, region, card_number, amount
FROM payments
WHERE amount >= 1000
