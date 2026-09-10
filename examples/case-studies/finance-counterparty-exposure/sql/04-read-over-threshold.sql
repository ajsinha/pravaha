SELECT counterparty_id, currency, outgoing_minor, largest_minor
FROM counterparty_exposure
WHERE currency = ? AND outgoing_minor > ?
