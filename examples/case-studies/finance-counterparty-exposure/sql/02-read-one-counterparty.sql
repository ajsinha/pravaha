SELECT counterparty_id, currency, rating, payment_count, outgoing_minor
FROM counterparty_exposure
WHERE counterparty_id = ?
