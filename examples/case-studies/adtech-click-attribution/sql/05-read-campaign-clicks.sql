SELECT click_id, impression_id, placement, user_id, click_time
FROM attributed_clicks
WHERE campaign_id = ?
