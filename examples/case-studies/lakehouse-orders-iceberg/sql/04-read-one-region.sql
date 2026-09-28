SELECT window_end, orders, revenue_minor, customers
FROM hourly_revenue
WHERE region = ?
