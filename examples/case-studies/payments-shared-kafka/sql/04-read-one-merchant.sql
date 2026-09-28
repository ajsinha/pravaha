SELECT window_end, payments, amount_minor
FROM merchant_minute
WHERE merchant = ?
