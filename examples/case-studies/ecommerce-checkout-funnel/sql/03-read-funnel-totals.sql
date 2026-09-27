SELECT step, SUM(sessions) AS sessions, SUM(basket_minor) AS basket_minor
FROM checkout_funnel
GROUP BY step
