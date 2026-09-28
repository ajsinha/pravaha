CREATE CONTINUOUS QUERY merchant_minute
  KEYED BY (window_end, merchant)
  INDEX (merchant)
AS
SELECT STREAM
  TUMBLE_END(paid_at, INTERVAL '1' MINUTE) AS window_end,
  merchant,
  COUNT(*)          AS payments,
  SUM(amount_minor) AS amount_minor
FROM payment
WHERE status = 'APPROVED'
GROUP BY TUMBLE(paid_at, INTERVAL '1' MINUTE), merchant
