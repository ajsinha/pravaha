SELECT c.click_id, i.impression_id, i.campaign_id, i.placement, i.user_id, i.cost_micros,
       i.impression_time, c.click_time
FROM ad_impression AS i
JOIN ad_click AS c
  ON c.impression_id = i.impression_id
 AND c.user_id = i.user_id
 AND c.click_time BETWEEN i.impression_time AND i.impression_time + INTERVAL '10' MINUTE
