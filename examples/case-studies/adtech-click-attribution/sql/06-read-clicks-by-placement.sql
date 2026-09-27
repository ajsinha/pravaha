SELECT placement, COUNT(*) AS clicks, SUM(cost_micros) AS spend_on_clicked_micros
FROM attributed_clicks
GROUP BY placement
