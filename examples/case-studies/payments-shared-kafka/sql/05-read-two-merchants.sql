SELECT merchant, SUM(payments) AS payments, SUM(amount_minor) AS amount_minor
FROM merchant_minute
WHERE merchant IN ('m-coffee', 'm-books')
GROUP BY merchant
