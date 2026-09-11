SELECT STREAM
  TUMBLE_END(a.auth_time, INTERVAL '1' MINUTE) AS window_end,
  a.card_id,
  h.risk_band,
  COUNT(*)                      AS auth_count,
  SUM(a.amount_minor)           AS total_minor,
  COUNT(DISTINCT a.merchant_id) AS distinct_merchants
FROM card_auth AS a
LEFT JOIN cardholder FOR SYSTEM_TIME AS OF a.auth_time AS h
       ON a.card_id = h.card_id
WHERE a.status = 'APPROVED'
GROUP BY TUMBLE(a.auth_time, INTERVAL '1' MINUTE), a.card_id, h.risk_band
