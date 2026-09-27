SELECT STREAM
  TUMBLE_END(impression_time, INTERVAL '5' MINUTE) AS window_end,
  campaign_id,
  COUNT(*)         AS impressions,
  SUM(cost_micros) AS spend_micros
FROM ad_impression
GROUP BY TUMBLE(impression_time, INTERVAL '5' MINUTE), campaign_id
