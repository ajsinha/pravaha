SELECT STREAM
  TUMBLE_END(c.click_time, INTERVAL '5' MINUTE) AS window_end,
  i.campaign_id,
  COUNT(*)                  AS clicks,
  COUNT(DISTINCT i.user_id) AS clicking_users
FROM ad_impression AS i
JOIN ad_click AS c
  ON c.impression_id = i.impression_id
 AND c.user_id = i.user_id
 AND c.click_time BETWEEN i.impression_time AND i.impression_time + INTERVAL '10' MINUTE
GROUP BY TUMBLE(c.click_time, INTERVAL '5' MINUTE), i.campaign_id
