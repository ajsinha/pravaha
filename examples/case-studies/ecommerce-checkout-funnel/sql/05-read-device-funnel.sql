SELECT step, SUM(sessions) AS sessions
FROM checkout_funnel
WHERE device = ?
GROUP BY step
