SELECT region, SUM(orders) AS orders, SUM(revenue_minor) AS revenue_minor
FROM hourly_revenue
GROUP BY region
